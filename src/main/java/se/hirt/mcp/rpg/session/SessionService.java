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
 * Gameplay sessions and the Context Builder (MCP_PROTOCOL.md §11, EXECUTION_MODEL.md §10–11):
 * bootstrap returns the smallest sufficient context; suspend closes the session with a compact
 * summary.
 */
public final class SessionService {

	private final Database db;
	private final se.hirt.mcp.rpg.content.RulesData rules;
	private final CharacterService characters;
	private final se.hirt.mcp.rpg.encounter.EncounterService encounters;

	public SessionService(Database db, se.hirt.mcp.rpg.content.RulesData rules, CharacterService characters,
			se.hirt.mcp.rpg.encounter.EncounterService encounters) {
		this.db = db;
		this.rules = rules;
		this.characters = characters;
		this.encounters = encounters;
	}

	// ── bootstrap_session ──────────────────────────────────────────────

	/**
	 * A session is never closed by hand (a lost connection must lose nothing): an open session is
	 * resumed when the campaign was touched more recently than this, and closed and replaced by a
	 * new one otherwise. Tests shorten it.
	 */
	public static volatile Duration SESSION_GAP = Duration.ofHours(3);

	/**
	 * Default bootstrap budget in approximate tokens; JSON tokenizes at about three characters a
	 * token.
	 */
	public static final int DEFAULT_BUDGET = 16_000;
	public static final int CHARS_PER_TOKEN = 3;

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
					: (stateBefore == HarnessState.CHECKPOINT_DECISION
							|| stateBefore == HarnessState.PLAYER_CHARACTER_TRANSFER) ? stateBefore
									: HarnessState.EXPLORATION;
			var cols = new LinkedHashMap<String, Object>();
			cols.put("status", "ACTIVE");
			cols.put("harness_state", next.name());
			cols.put("active_session_id", sessionId);
			cols.put("last_played_at", Instant.now().toString());
			cols.put("revision", campaign.lng("revision") + 1);
			tx.update("campaign", campaignId, cols);
			tx.touched(Ref.of(Ref.CAMPAIGN, campaignId), campaign.lng("revision") + 1);
			// Tracked pools that a newer rules seed introduces (sorcery points, feature uses) reach characters who
			// levelled before it existed here: the upsert is idempotent and only ever adds or resizes.
			for (Row member : se.hirt.mcp.rpg.progression.PartyXp.activeParty(tx, campaignId)) {
				Row c = tx.get("character", member.id());
				if ("ACTIVE".equals(c.str("lifecycle"))) {
					se.hirt.mcp.rpg.character.Origins.initializeResources(tx, rules, c);
				}
			}
			Row current = tx.get("campaign", campaignId);
			Map<String, Object> context = context(tx, current, contextBudget == null ? DEFAULT_BUDGET : contextBudget);
			context.put("session", Ref.of(Ref.SESSION, sessionId));
			context.put("session_resumed", resumed);
			context.put("meta", Harness.meta(current, null));
			return context;
		});
	}

	/**
	 * True when the campaign's last journal entry before this one is older than
	 * {@link #SESSION_GAP}.
	 */
	private static boolean stale(Tx tx, long campaignId) {
		return tx.queryOne(
				"SELECT recorded_at FROM journal_entry WHERE campaign_id = ? AND id < ? ORDER BY id DESC LIMIT 1",
				campaignId, tx.journalId())
				.map(r -> Duration.between(Instant.parse(r.str("recorded_at")), Instant.now())
						.compareTo(SESSION_GAP) > 0)
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
	 * Former members as current state, not history: one entry per character whose latest membership
	 * episode ended and who has no open one, with where they are now and why they went. The full
	 * episode list is in the CHARACTER context ({@link #membershipHistory}).
	 */
	public static List<Map<String, Object>> formerMembers(Tx tx, long campaignId) {
		var out = new ArrayList<Map<String, Object>>();
		for (Row m : tx.query(
				"SELECT m.state AS membership_state, m.left_time, m.notes AS membership_notes, c.* FROM party_membership m "
						+ "JOIN character c ON c.id = m.character_id WHERE m.campaign_id = ? AND m.state IN ('DEAD','LEFT','DISMISSED','ENDED') "
						+ "AND m.id = (SELECT MAX(id) FROM party_membership x WHERE x.character_id = m.character_id) "
						+ "AND NOT EXISTS (SELECT 1 FROM party_membership o WHERE o.character_id = m.character_id AND o.state IN ('ACTIVE','SEPARATED','GUEST')) "
						+ "ORDER BY m.id",
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

	/** Every membership episode of one character, oldest first. */
	public static List<Map<String, Object>> membershipHistory(Tx tx, long characterId) {
		var out = new ArrayList<Map<String, Object>>();
		for (Row m : tx.query("SELECT * FROM party_membership WHERE character_id = ? ORDER BY id", characterId)) {
			var e = new LinkedHashMap<String, Object>();
			e.put("state", m.str("state"));
			e.put("joined", m.str("joined_time"));
			e.put("left", m.str("left_time"));
			e.put("notes", m.str("notes"));
			out.add(e);
		}
		return out;
	}

	// ── find ───────────────────────────────────────────────────────────

	/**
	 * Lookup by name for characters and locations: case-insensitive, exact name first, then a name
	 * prefix, then a substring of the name, then a mention in the description. Each hit is one
	 * line: ref, name and what the reader needs to pick the right one (membership, life state and
	 * whereabouts for people; kind and parent for places).
	 */
	public Map<String, Object> find(String campaignRef, String kind, String query, Integer limit) {
		return db.read(tx -> {
			Row campaign = Harness.campaign(tx, campaignRef);
			long campaignId = campaign.id();
			String k = kind == null ? "CHARACTER" : kind.trim().toUpperCase();
			if (query == null || query.isBlank()) {
				throw RpgException.invalidArgument("query is required.");
			}
			String q = query.trim().toLowerCase();
			int max = limit == null ? 20 : Math.max(1, Math.min(100, limit));
			var hits = new ArrayList<Map<String, Object>>();
			var scored = new ArrayList<Object[]>();
			switch (k) {
			case "CHARACTER" -> {
				for (Row c : tx.query(
						"SELECT * FROM character WHERE campaign_id = ? AND lifecycle IN ('ACTIVE','DRAFT','FINALIZED_DRAFT') ORDER BY id",
						campaignId)) {
					int score = score(q, c.str("name"), c.str("description"), c.str("backstory"));
					if (score > 0) {
						scored.add(new Object[] {score, c});
					}
				}
				scored.sort((a, b) -> Integer.compare((Integer) b[0], (Integer) a[0]));
				for (Object[] s : scored) {
					Row c = (Row) s[1];
					var h = new LinkedHashMap<String, Object>();
					h.put("ref", Ref.of(Ref.CHARACTER, c.id()));
					h.put("name", c.str("name"));
					h.put("life_state", c.str("life_state"));
					tx.queryOne(
							"SELECT state FROM party_membership WHERE character_id = ? AND state IN ('ACTIVE','SEPARATED','GUEST')",
							c.id()).ifPresent(m -> h.put("membership", m.str("state")));
					if (!c.isNull("location_id")) {
						tx.find("location", c.lng("location_id")).ifPresent(
								l -> h.put("location", Ref.of(Ref.LOCATION, l.id()) + " (" + l.str("name") + ")"));
					}
					String brief = se.hirt.mcp.rpg.character.Biography.brief(c.str("description"));
					if (brief != null) {
						h.put("description", brief);
					}
					h.put("matched", (Integer) s[0] >= 20 ? "name" : "description");
					hits.add(h);
					if (hits.size() >= max) {
						break;
					}
				}
			}
			case "LOCATION" -> {
				for (Row l : tx.query("SELECT * FROM location WHERE campaign_id = ? ORDER BY id", campaignId)) {
					int score = score(q, l.str("name"), l.str("description"), null);
					if (score > 0) {
						scored.add(new Object[] {score, l});
					}
				}
				scored.sort((a, b) -> Integer.compare((Integer) b[0], (Integer) a[0]));
				for (Object[] s : scored) {
					Row l = (Row) s[1];
					var h = new LinkedHashMap<String, Object>();
					h.put("ref", Ref.of(Ref.LOCATION, l.id()));
					h.put("name", l.str("name"));
					h.put("kind", l.str("kind"));
					h.put("materialization", l.str("materialization"));
					if (!l.isNull("parent_id")) {
						tx.find("location", l.lng("parent_id")).ifPresent(
								p -> h.put("parent", Ref.of(Ref.LOCATION, p.id()) + " (" + p.str("name") + ")"));
					}
					h.put("characters_here", tx.count(
							"SELECT COUNT(*) FROM character WHERE location_id = ? AND lifecycle = 'ACTIVE' AND life_state <> 'DEAD'",
							l.id()));
					h.put("matched", (Integer) s[0] >= 20 ? "name" : "description");
					hits.add(h);
					if (hits.size() >= max) {
						break;
					}
				}
			}
			default -> throw RpgException.invalidArgument("kind must be CHARACTER or LOCATION.");
			}
			var result = new LinkedHashMap<String, Object>();
			result.put("kind", k);
			result.put("query", query.trim());
			result.put("hits", hits);
			result.put("total_matches", scored.size());
			result.put("meta", Harness.meta(campaign, null));
			return result;
		});
	}

	/**
	 * 40 exact name, 30 name prefix, 20 name substring, 10 word in description or backstory, 0 no
	 * match.
	 */
	private static int score(String q, String name, String description, String backstory) {
		String n = name == null ? "" : name.trim().toLowerCase();
		if (n.equals(q)) {
			return 40;
		}
		if (n.startsWith(q)) {
			return 30;
		}
		if (n.contains(q)) {
			return 20;
		}
		for (String text : new String[] {description, backstory}) {
			if (text != null && text.toLowerCase().contains(q)) {
				return 10;
			}
		}
		return 0;
	}

	/**
	 * The Context Builder: canonical current state first, recent history second, never the whole
	 * campaign. The budget is honoured in characters ({@link #CHARS_PER_TOKEN} per token): the
	 * story section (synopsis, chapters, the digest of the uncovered tail) gets a third,
	 * relationship profiles and full inventories stay out of bootstrap (the RELATIONSHIP, INTIMACY
	 * and CHARACTER scopes carry them), and the result reports its own size.
	 */
	public Map<String, Object> context(Tx tx, Row campaign, int budget) {
		long campaignId = campaign.id();
		int chars = Math.max(6_000, budget * CHARS_PER_TOKEN);
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

		Optional<Row> pc = tx
				.queryOne("SELECT c.* FROM player_control_assignment p JOIN character c ON c.id = p.character_id "
						+ "WHERE p.campaign_id = ? AND p.active = 1", campaignId);
		Long here = campaign.isNull("current_location_id") ? null : campaign.lng("current_location_id");
		ctx.put("player_character", pc.map(r -> compactSheet(characters.sheet(tx, r, "PLAY"))).orElse(null));
		ctx.put("relationships",
				pc.map(r -> se.hirt.mcp.rpg.party.PartyService.compact(tx, campaignId, r.id(), 12, false))
						.orElse(List.of()));

		var party = new ArrayList<Map<String, Object>>();
		for (Row m : tx.query(
				"SELECT m.state, m.notes, c.* FROM party_membership m JOIN character c ON c.id = m.character_id "
						+ "WHERE m.campaign_id = ? AND m.state IN ('ACTIVE','SEPARATED','GUEST') ORDER BY m.id",
				campaignId)) {
			var member = characters.sheet(tx, m, "SUMMARY");
			member.put("membership", m.str("state"));
			member.put("with_party", withParty(m, here));
			member.put("player_controlled", pc.isPresent() && pc.get().id() == m.id());
			party.add(member);
		}
		ctx.put("party", party);
		ctx.put("former_members", formerMembers(tx, campaignId));

		ctx.put("story_beats", tx
				.query("SELECT * FROM story_beat WHERE campaign_id = ? AND state IN ('AVAILABLE','PLANNED','BLOCKED') "
						+ "AND visibility <> 'DIRECTOR_ONLY' ORDER BY id", campaignId)
				.stream().map(b -> {
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
		ctx.put("chronicle", ChronicleService.view(tx, campaignId, chars / 3));
		ctx.put("pending_transaction", tx
				.queryOne("SELECT * FROM pending_transaction WHERE campaign_id = ? AND status = 'OPEN'", campaignId)
				.map(t -> Map.<String, Object> of("ref", Ref.of(Ref.TRANSACTION, t.id()), "kind", t.str("kind")))
				.orElse(null));
		ctx.put("director_seeds", tx
				.query("SELECT id, kind, state FROM director_seed WHERE campaign_id = ? AND state = 'OPEN'", campaignId)
				.stream().map(s -> Ref.of(Ref.SEED, s.id()) + " (" + s.str("kind") + ")").toList());
		var b = new LinkedHashMap<String, Object>();
		b.put("tokens", budget);
		b.put("chars_target", chars);
		b.put("chars_used", se.hirt.mcp.rpg.protocol.Json.write(ctx).length());
		ctx.put("budget", b);
		return ctx;
	}

	/** True when the character stands where the party stands (or has no location yet). */
	static boolean withParty(Row c, Long partyLocationId) {
		return partyLocationId == null || c.isNull("location_id") || c.lng("location_id") == partyLocationId;
	}

	/**
	 * A PLAY sheet for bootstrap: the inventory becomes one line per item; get_character_sheet has
	 * the rest.
	 */
	@SuppressWarnings("unchecked")
	static Map<String, Object> compactSheet(Map<String, Object> sheet) {
		if (sheet.get("inventory") instanceof List<?> items) {
			var brief = new ArrayList<String>();
			for (Object o : items) {
				if (o instanceof Map<?, ?> item) {
					Object qty = item.get("quantity");
					brief.add(item.get("name")
							+ (qty instanceof Number n && n.intValue() != 1 ? " x" + n.intValue() : ""));
				}
			}
			sheet.put("inventory", brief);
			sheet.put("inventory_note", "Names only; get_character_sheet carries refs, weights and charges.");
		}
		return sheet;
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
	 * The player may always ask about their own state and their party's, in detail, in any harness
	 * state: every current member with a full sheet, the fallen, the player character, location,
	 * time and any running encounter. During setup the draft characters are listed instead.
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
			Optional<Row> pc = tx
					.queryOne("SELECT c.* FROM player_control_assignment p JOIN character c ON c.id = p.character_id "
							+ "WHERE p.campaign_id = ? AND p.active = 1", campaignId);
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
		Long here = tx.queryOne("SELECT current_location_id AS id FROM campaign WHERE id = ?", campaignId)
				.filter(r -> !r.isNull("id")).map(Row::id).orElse(null);
		var members = new ArrayList<Map<String, Object>>();
		for (Row m : tx.query(
				"SELECT m.state AS membership_state, m.joined_time, m.notes, c.* FROM party_membership m JOIN character c ON c.id = m.character_id "
						+ "WHERE m.campaign_id = ? AND m.state IN ('ACTIVE','SEPARATED','GUEST') ORDER BY m.id",
				campaignId)) {
			var sheet = characters.sheet(tx, m, detail);
			sheet.put("membership", m.str("membership_state"));
			sheet.put("joined", m.str("joined_time"));
			sheet.put("with_party", withParty(m, here));
			if (!m.isNull("location_id")) {
				tx.find("location", m.lng("location_id")).ifPresent(l -> sheet.put("location_name", l.str("name")));
			}
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

	/**
	 * Table rulings every client sees at bootstrap: short strings, added to, removed from or
	 * replaced; audited.
	 */
	public Map<String, Object> updateHouseRules(
		String operationId, String campaignRef, List<Object> rules, String mode) {
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
					new LedgerService.EventSpec("HOUSE_RULE",
							"House rules (" + m.toLowerCase() + "): "
									+ (given.isEmpty() ? "cleared" : String.join("; ", given)),
							List.of(), "NOTABLE", "PARTY_KNOWN", "GM", null, null, null,
							Map.of("mode", m, "rules", given)));
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
			if (tx.count("SELECT COUNT(*) FROM pending_transaction WHERE campaign_id = ? AND status = 'OPEN'",
					campaignId) > 0) {
				throw RpgException.notAllowed("Resolve or abandon the open pending transaction before suspending.");
			}
			// A summary given here is a chapter: it closes the ledger at this point like write_chronicle would.
			Map<String, Object> chapter = null;
			if (summary != null && !summary.isBlank() && ChronicleService.uncovered(tx, campaignId)[0] > 0) {
				chapter = ChronicleService.writeChapter(tx, campaign, null, summary.trim(), null);
			}
			Row session = tx
					.queryOne(
							"SELECT * FROM session WHERE campaign_id = ? AND ended_at IS NULL ORDER BY id DESC LIMIT 1",
							campaignId)
					.orElseThrow(() -> RpgException.notAllowed("No session is open for " + campaignRef + "."));
			long seq = GameTime.currentSeq(tx, campaignId);
			long events = tx.count(
					"SELECT COUNT(*) FROM event WHERE campaign_id = ? AND recorded_journal_id >= ? AND recorded_journal_id <= ? AND type <> 'CHRONICLE_WRITTEN'",
					campaignId, session.lng("start_journal_id"), tx.journalId());
			var sessionCols = new LinkedHashMap<String, Object>();
			sessionCols.put("ended_at", Instant.now().toString());
			sessionCols.put("end_game_seq", seq);
			sessionCols.put("end_journal_id", tx.journalId());
			sessionCols.put("summary", summary == null || summary.isBlank() ? null : summary.trim());
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
			result.put("session_summary", summary == null || summary.isBlank() ? null : summary.trim());
			if (chapter != null) {
				result.put("chapter", chapter);
			}
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
			String due = ChronicleService.dueWarning(tx, campaignId);
			if (due != null) {
				consequences.add(due);
			}
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
