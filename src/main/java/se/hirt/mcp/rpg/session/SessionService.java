/*
 * Copyright (C) 2026 Marcus Hirt
 *
 * This software is free:
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions
 * are met:
 *
 * 1. Redistributions of source code must retain the above copyright
 *    notice, this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright
 *    notice, this list of conditions and the following disclaimer in the
 *    documentation and/or other materials provided with the distribution.
 * 3. The name of the author may not be used to endorse or promote products
 *    derived from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE AUTHOR ``AS IS'' AND ANY EXPRESSED OR
 * IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES
 * OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED.
 * IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR ANY DIRECT, INDIRECT,
 * INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT
 * NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
 * THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF
 * THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package se.hirt.mcp.rpg.session;

import se.hirt.mcp.rpg.character.CharacterService;
import se.hirt.mcp.rpg.choice.ContentProfile;
import se.hirt.mcp.rpg.choice.FantasyStyle;
import se.hirt.mcp.rpg.harness.Harness;
import se.hirt.mcp.rpg.harness.HarnessState;
import se.hirt.mcp.rpg.ledger.LedgerService;
import se.hirt.mcp.rpg.persistence.Database;
import se.hirt.mcp.rpg.persistence.Row;
import se.hirt.mcp.rpg.persistence.Tx;
import se.hirt.mcp.rpg.protocol.Ref;
import se.hirt.mcp.rpg.protocol.RpgException;

import java.time.Instant;
import java.time.Duration;
import java.util.*;

/**
 * Gameplay sessions and the Context Builder (MCP_PROTOCOL.md §11, EXECUTION_MODEL.md §10–11): bootstrap returns the
 * smallest sufficient context; suspend closes the session with a compact summary.
 */
public final class SessionService {

	private final Database db;
	private final CharacterService characters;
	private final se.hirt.mcp.rpg.encounter.EncounterService encounters;

	public SessionService(
			Database db, CharacterService characters, se.hirt.mcp.rpg.encounter.EncounterService encounters) {
		this.db = db;
		this.characters = characters;
		this.encounters = encounters;
	}

	// ── bootstrap_session ──────────────────────────────────────────────

	/**
	 * A session is never closed by hand (a lost connection must lose nothing): an open session is resumed when the
	 * campaign was touched more recently than this, and closed and replaced by a new one otherwise. Tests shorten it.
	 */
	public static volatile Duration SESSION_GAP = Duration.ofHours(3);

	public Map<String, Object> bootstrap(String operationId, String campaignRef, Integer contextBudget) {
		long campaignId = Ref.id(campaignRef, Ref.CAMPAIGN);
		var args = new LinkedHashMap<String, Object>();
		args.put("campaign", campaignRef);
		return db.mutate(Database.Mutation.of("bootstrap_session", campaignId, operationId, "GM", args), tx -> {
			Row campaign = Harness.requireMutation(tx, campaignRef, "bootstrap_session");
			long seq = GameTime.currentSeq(tx, campaignId);
			Optional<Row> open = tx.queryOne(
					"SELECT * FROM session WHERE campaign_id = ? AND ended_at IS NULL ORDER BY id DESC LIMIT 1",
					campaignId);
			long sessionId;
			boolean resumed;
			if (open.isPresent() && !stale(tx, campaignId)) {
				sessionId = open.get().id();
				resumed = true;
			} else {
				if (open.isPresent()) {
					Row stale = open.get();
					var end = new LinkedHashMap<String, Object>();
					end.put("ended_at", Instant.now().toString());
					end.put("end_game_seq", seq);
					end.put("end_journal_id", tx.journalId() - 1);
					end.put("events_written", tx.count(
							"SELECT COUNT(*) FROM event WHERE campaign_id = ? AND recorded_journal_id >= ? AND recorded_journal_id < ?",
							campaignId, stale.lng("start_journal_id"), tx.journalId()));
					tx.rawUpdate("session", stale.id(), end);
				}
				var cols = new LinkedHashMap<String, Object>();
				cols.put("campaign_id", campaignId);
				cols.put("started_at", Instant.now().toString());
				cols.put("start_game_seq", seq);
				cols.put("start_journal_id", tx.journalId());
				sessionId = tx.rawInsert("session", cols);
				resumed = false;
			}
			HarnessState stateBefore = Harness.state(campaign);
			boolean encounterRunning = tx.count(
					"SELECT COUNT(*) FROM encounter WHERE campaign_id = ? AND status IN ('RUNNING','WAITING_CHOICE')",
					campaignId) > 0;
			HarnessState next = encounterRunning ? HarnessState.ENCOUNTER
					: (stateBefore == HarnessState.CHECKPOINT_DECISION || stateBefore == HarnessState.PLAYER_CHARACTER_TRANSFER)
					  ? stateBefore : HarnessState.EXPLORATION;
			var cols = new LinkedHashMap<String, Object>();
			cols.put("status", "ACTIVE");
			cols.put("harness_state", next.name());
			cols.put("active_session_id", sessionId);
			cols.put("last_played_at", Instant.now().toString());
			cols.put("revision", campaign.lng("revision") + 1);
			tx.update("campaign", campaignId, cols);
			tx.touched(Ref.of(Ref.CAMPAIGN, campaignId), campaign.lng("revision") + 1);
			Row current = tx.get("campaign", campaignId);
			Map<String, Object> context = context(tx, current, contextBudget == null ? 12000 : contextBudget);
			context.put("session", Ref.of(Ref.SESSION, sessionId));
			context.put("session_resumed", resumed);
			context.put("meta", Harness.meta(current, null));
			return context;
		});
	}

	/** True when the campaign's last journal entry before this one is older than {@link #SESSION_GAP}. */
	private static boolean stale(Tx tx, long campaignId) {
		return tx.queryOne(
						"SELECT recorded_at FROM journal_entry WHERE campaign_id = ? AND id < ? ORDER BY id DESC LIMIT 1",
						campaignId, tx.journalId())
				.map(r -> Duration.between(Instant.parse(r.str("recorded_at")), Instant.now()).compareTo(SESSION_GAP) > 0)
				.orElse(false);
	}

	/** The campaign's standing table rulings (V007), an empty list when none were recorded. */
	public static List<String> houseRules(Row campaign) {
		if (campaign.isNull("house_rules_json")) {
			return List.of();
		}
		return campaign.list("house_rules_json").stream().map(String::valueOf).toList();
	}

	/**
	 * "Since you last played", derived from the ledger: everything recorded since the previous session began (going
	 * back further when that session was tiny, at most three sessions), by importance under a share of the budget.
	 */
	static Map<String, Object> sinceLastSession(Tx tx, Row campaign, int budget) {
		long campaignId = campaign.id();
		long current = campaign.isNull("active_session_id") ? Long.MAX_VALUE : campaign.lng("active_session_id");
		List<Row> previous = tx.query(
				"SELECT * FROM session WHERE campaign_id = ? AND id < ? AND ended_at IS NOT NULL AND superseded = 0 " + "ORDER BY id DESC LIMIT 3",
				campaignId, current);
		if (previous.isEmpty()) {
			return null;
		}
		int chars = Math.max(2000, budget * 3 / 2);
		Map<String, Object> digest = null;
		int covered = 0;
		for (Row session : previous) {
			covered++;
			long from = session.isNull("start_journal_id") ? 0 : session.lng("start_journal_id") - 1;
			digest = LedgerService.digest(tx, campaignId, from, tx.journalId(), chars);
			if (((Number) digest.get("events")).intValue() >= 5) {
				break;
			}
		}
		var out = new LinkedHashMap<String, Object>();
		out.put("sessions_covered", covered);
		out.put("note",
				"Derived from the ledger, so it is authoritative; previous_session_summary, when present, is only a pinned note that may be older.");
		out.putAll(digest);
		return out;
	}

	/** Members who died, left, were dismissed or whose guest spell ended, with where they are now and why they went. */
	public static List<Map<String, Object>> formerMembers(Tx tx, long campaignId) {
		var out = new ArrayList<Map<String, Object>>();
		for (Row m : tx.query(
				"SELECT m.state AS membership_state, m.left_time, m.notes AS membership_notes, c.* FROM party_membership m " + "JOIN character c ON c.id = m.character_id WHERE m.campaign_id = ? AND m.state IN ('DEAD','LEFT','DISMISSED','ENDED') ORDER BY m.id",
				campaignId)) {
			var f = new LinkedHashMap<String, Object>();
			f.put("ref", Ref.of(Ref.CHARACTER, m.id()));
			f.put("name", m.str("name"));
			f.put("membership", m.str("membership_state"));
			f.put("life_state", m.str("life_state"));
			f.put("since", m.str("left_time"));
			if (!m.isNull("location_id")) {
				tx.find("location", m.lng("location_id")).ifPresent(
						l -> f.put("whereabouts", Ref.of(Ref.LOCATION, l.id()) + " (" + l.str("name") + ")"));
			}
			if (!m.isNull("membership_notes")) {
				f.put("notes", m.str("membership_notes"));
			}
			out.add(f);
		}
		return out;
	}

	/** The Context Builder: canonical current state first, recent history second, never the whole campaign. */
	public Map<String, Object> context(Tx tx, Row campaign, int budget) {
		long campaignId = campaign.id();
		int eventLimit = Math.max(3, Math.min(20, budget / 1000));
		var ctx = new LinkedHashMap<String, Object>();

		var c = new LinkedHashMap<String, Object>();
		c.put("ref", Ref.of(Ref.CAMPAIGN, campaignId));
		c.put("title", campaign.str("title"));
		c.put("status", campaign.str("status"));
		c.put("continuation_policy", campaign.str("continuation_policy"));
		tx.queryOne("SELECT content_profile FROM policy_state WHERE campaign_id = ?", campaignId).ifPresent(p -> {
			c.put("content_profile", p.str("content_profile"));
			ContentProfile profile = ContentProfile.of(p.str("content_profile"));
			c.put("content_profile_guidance", profile == null ? null : profile.guidance());
		});
		c.put("gm_override_policy", campaign.isNull("gm_override_policy_json") ? null
				: campaign.map("gm_override_policy_json").get("policy"));
		Map<String, Object> prefs = campaign.map("preferences_json");
		c.put("experience_preferences", prefs.get("experience"));
		FantasyStyle style = FantasyStyle.of(prefs.get("experience"));
		c.put("experience_guidance", style == null ? null : style.guidance());
		c.put("party_preferences", prefs.get("party"));
		c.put("rules", prefs.get("rules"));
		c.put("house_rules", houseRules(campaign));
		ctx.put("campaign", c);
		ctx.put("harness_state", campaign.str("harness_state"));
		ctx.put("game_time", GameTime.toMap(tx, campaignId, GameTime.currentSeq(tx, campaignId)));
		ctx.put("location",
				campaign.isNull("current_location_id") ? null : location(tx, campaign.lng("current_location_id")));

		Optional<Row> pc = tx.queryOne(
				"SELECT c.* FROM player_control_assignment p JOIN character c ON c.id = p.character_id " + "WHERE p.campaign_id = ? AND p.active = 1",
				campaignId);
		ctx.put("player_character", pc.map(r -> characters.sheet(tx, r, "PLAY")).orElse(null));
		ctx.put("relationships",
				pc.map(r -> se.hirt.mcp.rpg.party.PartyService.compact(tx, campaignId, r.id(), 12)).orElse(List.of()));

		var party = new ArrayList<Map<String, Object>>();
		for (Row m : tx.query(
				"SELECT m.state, m.notes, c.* FROM party_membership m JOIN character c ON c.id = m.character_id " + "WHERE m.campaign_id = ? AND m.state IN ('ACTIVE','SEPARATED','GUEST') ORDER BY m.id",
				campaignId)) {
			var member = characters.sheet(tx, m, "SUMMARY");
			member.put("membership", m.str("state"));
			member.put("player_controlled", pc.isPresent() && pc.get().id() == m.id());
			party.add(member);
		}
		ctx.put("party", party);
		ctx.put("former_members", formerMembers(tx, campaignId));

		ctx.put("story_beats", tx.query(
				"SELECT * FROM story_beat WHERE campaign_id = ? AND state IN ('AVAILABLE','PLANNED','BLOCKED') " + "AND visibility <> 'DIRECTOR_ONLY' ORDER BY id",
				campaignId).stream().map(b -> {
			var m = new LinkedHashMap<String, Object>();
			m.put("ref", Ref.of(Ref.STORY_BEAT, b.id()));
			m.put("title", b.str("title"));
			m.put("state", b.str("state"));
			m.put("visibility", b.str("visibility"));
			return m;
		}).toList());
		ctx.put("active_quests",
				tx.query("SELECT * FROM quest WHERE campaign_id = ? AND status IN ('OFFERED','ACCEPTED') ORDER BY id",
						campaignId).stream().map(q -> {
					var m = new LinkedHashMap<String, Object>();
					m.put("title", q.str("title"));
					m.put("status", q.str("status"));
					m.put("visibility", q.str("visibility"));
					return m;
				}).toList());

		Map<String, Object> adventure = campaign.map("adventure_json");
		var adv = new LinkedHashMap<String, Object>();
		adv.put("premise", adventure.get("premise"));
		adv.put("immediate_goal", adventure.get("immediate_goal"));
		var gmOnly = new LinkedHashMap<String, Object>();
		gmOnly.put("visibility", "GM_ONLY");
		gmOnly.put("background_truth", adventure.get("background_truth"));
		gmOnly.put("gm_notes", adventure.get("gm_notes"));
		adv.put("gm_only", gmOnly);
		ctx.put("adventure", adv);

		tx.queryOne(
				"SELECT * FROM encounter WHERE campaign_id = ? AND status IN ('RUNNING','WAITING_CHOICE') ORDER BY id DESC LIMIT 1",
				campaignId).ifPresent(e -> ctx.put("encounter", encounters.state(tx, e, 5)));
		ctx.put("recent_events", LedgerService.recent(tx, campaignId, eventLimit));
		ctx.put("since_last_session", sinceLastSession(tx, campaign, budget));
		ctx.put("previous_session_summary", tx.queryOne(
				"SELECT summary FROM session WHERE campaign_id = ? AND ended_at IS NOT NULL AND superseded = 0 ORDER BY id DESC LIMIT 1",
				campaignId).map(r -> r.str("summary")).orElse(null));
		ctx.put("pending_transaction",
				tx.queryOne("SELECT * FROM pending_transaction WHERE campaign_id = ? AND status = 'OPEN'", campaignId)
						.map(t -> Map.<String, Object> of("ref", Ref.of(Ref.TRANSACTION, t.id()), "kind",
								t.str("kind"))).orElse(null));
		ctx.put("director_seeds",
				tx.query("SELECT id, kind, state FROM director_seed WHERE campaign_id = ? AND state = 'OPEN'",
						campaignId).stream().map(s -> Ref.of(Ref.SEED, s.id()) + " (" + s.str("kind") + ")").toList());
		return ctx;
	}

	static Map<String, Object> location(Tx tx, long locationId) {
		return tx.find("location", locationId).map(l -> {
			var m = new LinkedHashMap<String, Object>();
			m.put("ref", Ref.of(Ref.LOCATION, l.id()));
			m.put("name", l.str("name"));
			m.put("kind", l.str("kind"));
			m.put("description", l.str("description"));
			m.put("materialization", l.str("materialization"));
			return (Map<String, Object>) m;
		}).orElse(null);
	}

	// ── get_party ──────────────────────────────────────────────────────

	/**
	 * The player may always ask about their own state and their party's, in detail, in any harness state: every current
	 * member with a full sheet, the fallen, the player character, location, time and any running encounter. During
	 * setup the draft characters are listed instead.
	 */
	public Map<String, Object> party(String campaignRef, String detail) {
		return db.read(tx -> {
			Row campaign = Harness.campaign(tx, campaignRef);
			long campaignId = campaign.id();
			String d = detail == null || detail.isBlank() ? "PLAY" : detail.toUpperCase();
			if (!List.of("SUMMARY", "PLAY", "FULL").contains(d)) {
				throw RpgException.invalidArgument("detail must be SUMMARY, PLAY or FULL.");
			}
			var result = new LinkedHashMap<String, Object>();
			result.put("campaign", Ref.of(Ref.CAMPAIGN, campaignId));
			result.put("harness_state", campaign.str("harness_state"));
			Optional<Row> pc = tx.queryOne(
					"SELECT c.* FROM player_control_assignment p JOIN character c ON c.id = p.character_id " + "WHERE p.campaign_id = ? AND p.active = 1",
					campaignId);
			result.put("player_character", pc.map(r -> Ref.of(Ref.CHARACTER, r.id())).orElse(null));
			if ("SETUP".equals(campaign.str("status"))) {
				var drafts = new ArrayList<Map<String, Object>>();
				for (Row c : tx.query(
						"SELECT * FROM character WHERE campaign_id = ? AND lifecycle IN ('DRAFT','FINALIZED_DRAFT') ORDER BY id",
						campaignId)) {
					drafts.add(characters.sheet(tx, c, d));
				}
				result.put("setup", true);
				result.put("members", drafts);
				result.put("note", "The campaign is still in setup; these are the character drafts.");
				result.put("meta", Harness.meta(campaign, null));
				return result;
			}
			result.put("members", partyMembers(tx, campaignId, d));
			result.put("former_members", formerMembers(tx, campaignId));
			result.put("location",
					campaign.isNull("current_location_id") ? null : location(tx, campaign.lng("current_location_id")));
			tx.queryOne("SELECT seq FROM game_clock WHERE campaign_id = ?", campaignId)
					.ifPresent(r -> result.put("game_time", GameTime.toMap(tx, campaignId, r.lng("seq"))));
			tx.queryOne(
					"SELECT * FROM encounter WHERE campaign_id = ? AND status IN ('RUNNING','WAITING_CHOICE') ORDER BY id DESC LIMIT 1",
					campaignId).ifPresent(e -> result.put("encounter", encounters.state(tx, e, 3)));
			result.put("meta", Harness.meta(campaign, null));
			return result;
		});
	}

	/** Encounter view delegate for context builders. */
	public Map<String, Object> encounterState(Tx tx, Row encounter, int logLimit) {
		return encounters.state(tx, encounter, logLimit);
	}

	/** Current party members (active, separated, guests) with sheets at the given detail. */
	public List<Map<String, Object>> partyMembers(Tx tx, long campaignId, String detail) {
		Optional<Row> pc = tx.queryOne(
				"SELECT character_id AS id FROM player_control_assignment WHERE campaign_id = ? AND active = 1",
				campaignId);
		var members = new ArrayList<Map<String, Object>>();
		for (Row m : tx.query(
				"SELECT m.state AS membership_state, m.joined_time, m.notes, c.* FROM party_membership m JOIN character c ON c.id = m.character_id " + "WHERE m.campaign_id = ? AND m.state IN ('ACTIVE','SEPARATED','GUEST') ORDER BY m.id",
				campaignId)) {
			var sheet = characters.sheet(tx, m, detail);
			sheet.put("membership", m.str("membership_state"));
			sheet.put("joined", m.str("joined_time"));
			sheet.put("player_controlled", pc.isPresent() && pc.get().id() == m.id());
			List<String> gaps = se.hirt.mcp.rpg.character.CharacterService.sheetGaps(tx, m);
			if (!gaps.isEmpty()) {
				sheet.put("sheet_gaps", gaps);
				sheet.put("sheet_gaps_note",
						"Not yet a character a player could inherit; begin_level_up offers the class, species and background.");
			}
			members.add(sheet);
		}
		return members;
	}

	// ── update_house_rules ─────────────────────────────────────────────

	/** Table rulings every client sees at bootstrap: short strings, added to, removed from or replaced; audited. */
	public Map<String, Object> updateHouseRules(String operationId, String campaignRef, List<Object> rules, String mode) {
		long campaignId = Ref.id(campaignRef, Ref.CAMPAIGN);
		var args = new LinkedHashMap<String, Object>();
		args.put("campaign", campaignRef);
		args.put("rules", rules);
		args.put("mode", mode);
		return db.mutate(Database.Mutation.of("update_house_rules", campaignId, operationId, "GM", args), tx -> {
			Row campaign = Harness.requireMutation(tx, campaignRef, "update_house_rules");
			String m = mode == null || mode.isBlank() ? "ADD" : mode.trim().toUpperCase();
			if (!List.of("ADD", "REMOVE", "REPLACE").contains(m)) {
				throw RpgException.invalidArgument("mode must be ADD, REMOVE or REPLACE.");
			}
			List<String> given = rules == null ? List.of()
					: rules.stream().map(String::valueOf).map(String::trim).filter(s -> !s.isEmpty()).toList();
			if (given.isEmpty() && !"REPLACE".equals(m)) {
				throw RpgException.invalidArgument("Give at least one rule.");
			}
			var current = new ArrayList<>(houseRules(campaign));
			switch (m) {
			case "REPLACE" -> {
				current.clear();
				current.addAll(given);
			}
			case "ADD" -> given.stream().filter(r -> !current.contains(r)).forEach(current::add);
			default -> current.removeIf(given::contains);
			}
			var cols = new LinkedHashMap<String, Object>();
			cols.put("house_rules_json", se.hirt.mcp.rpg.protocol.Json.write(current));
			cols.put("revision", campaign.lng("revision") + 1);
			tx.update("campaign", campaignId, cols);
			tx.touched(Ref.of(Ref.CAMPAIGN, campaignId), campaign.lng("revision") + 1);
			long eventId = LedgerService.append(tx, campaignId,
					new LedgerService.EventSpec("HOUSE_RULE", "House rules (" + m.toLowerCase() + "): " + (
							given.isEmpty() ? "cleared" : String.join("; ", given)), List.of(), "NOTABLE",
							"PARTY_KNOWN", "GM", null, null, null, Map.of("mode", m, "rules", given)));
			var result = new LinkedHashMap<String, Object>();
			result.put("house_rules", current);
			result.put("event", Ref.of(Ref.EVENT, eventId));
			result.put("meta", Harness.meta(tx.get("campaign", campaignId), null));
			return result;
		});
	}

	// ── suspend_session ────────────────────────────────────────────────

	public Map<String, Object> suspend(String operationId, String campaignRef, String summary) {
		long campaignId = Ref.id(campaignRef, Ref.CAMPAIGN);
		var args = new LinkedHashMap<String, Object>();
		args.put("campaign", campaignRef);
		args.put("summary", summary);
		return db.mutate(Database.Mutation.of("suspend_session", campaignId, operationId, "GM", args), tx -> {
			Row campaign = Harness.requireMutation(tx, campaignRef, "suspend_session");
			if (summary == null || summary.isBlank()) {
				throw RpgException.invalidArgument(
						"A compact session summary is required; it is the recap the next session starts from.");
			}
			if (tx.count("SELECT COUNT(*) FROM pending_transaction WHERE campaign_id = ? AND status = 'OPEN'",
					campaignId) > 0) {
				throw RpgException.notAllowed("Resolve or abandon the open pending transaction before suspending.");
			}
			Row session = tx.queryOne(
							"SELECT * FROM session WHERE campaign_id = ? AND ended_at IS NULL ORDER BY id DESC LIMIT 1",
							campaignId)
					.orElseThrow(() -> RpgException.notAllowed("No session is open for " + campaignRef + "."));
			long seq = GameTime.currentSeq(tx, campaignId);
			long events = tx.count(
					"SELECT COUNT(*) FROM event WHERE campaign_id = ? AND recorded_journal_id >= ? AND recorded_journal_id <= ?",
					campaignId, session.lng("start_journal_id"), tx.journalId());
			var sessionCols = new LinkedHashMap<String, Object>();
			sessionCols.put("ended_at", Instant.now().toString());
			sessionCols.put("end_game_seq", seq);
			sessionCols.put("end_journal_id", tx.journalId());
			sessionCols.put("summary", summary.trim());
			sessionCols.put("events_written", events);
			tx.rawUpdate("session", session.id(), sessionCols);
			var cols = new LinkedHashMap<String, Object>();
			cols.put("status", "SUSPENDED");
			cols.put("harness_state", HarnessState.SESSION_SUSPEND.name());
			cols.put("active_session_id", null);
			cols.put("last_played_at", Instant.now().toString());
			cols.put("revision", campaign.lng("revision") + 1);
			tx.update("campaign", campaignId, cols);
			tx.touched(Ref.of(Ref.CAMPAIGN, campaignId), campaign.lng("revision") + 1);
			Row current = tx.get("campaign", campaignId);
			var result = new LinkedHashMap<String, Object>();
			result.put("session_closed", true);
			result.put("session", Ref.of(Ref.SESSION, session.id()));
			result.put("game_time", GameTime.toMap(tx, campaignId, seq));
			result.put("location",
					current.isNull("current_location_id") ? null : location(tx, current.lng("current_location_id")));
			result.put("session_summary", summary.trim());
			result.put("important_events_written", events);
			result.put("meta", Harness.meta(current, null));
			return result;
		});
	}

	// ── advance_time ───────────────────────────────────────────────────

	public Map<String, Object> advanceTime(String operationId, String campaignRef, Integer minutes, String reason) {
		long campaignId = Ref.id(campaignRef, Ref.CAMPAIGN);
		var args = new LinkedHashMap<String, Object>();
		args.put("campaign", campaignRef);
		args.put("minutes", minutes);
		args.put("reason", reason);
		return db.mutate(Database.Mutation.of("advance_time", campaignId, operationId, "GM", args), tx -> {
			Row campaign = Harness.requireMutation(tx, campaignRef, "advance_time");
			if (minutes == null || minutes <= 0 || minutes > 365 * GameTime.MINUTES_PER_DAY) {
				throw RpgException.invalidArgument("minutes must be a positive number of minutes (at most one year).");
			}
			Row clock = GameTime.clock(tx, campaignId);
			long from = clock.lng("seq");
			long to = from + minutes;
			tx.update("game_clock", clock.id(), Map.of("seq", to, "instant", GameTime.render(to)));
			int expired = se.hirt.mcp.rpg.rules.Effects.expireByTime(tx, campaignId, to);
			var consequences = new java.util.ArrayList<Object>();
			if (expired > 0) {
				consequences.add(expired + " timed effect(s) expired");
			}
			consequences.addAll(se.hirt.mcp.rpg.economy.Scheduler.onClockAdvance(tx, campaignId, from, to));
			var result = new LinkedHashMap<String, Object>();
			result.put("from", GameTime.toMap(tx, campaignId, from));
			result.put("to", GameTime.toMap(tx, campaignId, to));
			result.put("elapsed_minutes", minutes);
			result.put("consequences", consequences);
			result.put("expired_effects", expired);
			result.put("director_trigger", directorTrigger(from, to));
			result.put("meta", Harness.meta(campaign, null));
			return result;
		});
	}

	private static Map<String, Object> directorTrigger(long from, long to) {
		boolean significant = to / GameTime.MINUTES_PER_DAY - from / GameTime.MINUTES_PER_DAY >= 7;
		var m = new LinkedHashMap<String, Object>();
		m.put("recommended", significant);
		m.put("reasons", significant ? List.of("SIGNIFICANT_TIME_PASSED") : List.of());
		m.put("urgency", significant ? "NORMAL" : "NONE");
		return m;
	}
}
