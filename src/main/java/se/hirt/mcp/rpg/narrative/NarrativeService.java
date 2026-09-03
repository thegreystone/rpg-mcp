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
package se.hirt.mcp.rpg.narrative;

import se.hirt.mcp.rpg.character.CharacterService;
import se.hirt.mcp.rpg.harness.Harness;
import se.hirt.mcp.rpg.ledger.LedgerService;
import se.hirt.mcp.rpg.party.PartyService;
import se.hirt.mcp.rpg.persistence.Database;
import se.hirt.mcp.rpg.persistence.Row;
import se.hirt.mcp.rpg.persistence.Tx;
import se.hirt.mcp.rpg.protocol.Json;
import se.hirt.mcp.rpg.protocol.Ref;
import se.hirt.mcp.rpg.protocol.RpgException;
import se.hirt.mcp.rpg.protocol.Violation;
import se.hirt.mcp.rpg.session.GameTime;
import se.hirt.mcp.rpg.session.SessionService;
import se.hirt.mcp.rpg.world.WorldService;

import java.time.Instant;
import java.util.*;

/**
 * Canonical narrative state and the Narrative Director (DESIGN.md §6, §19; DOMAIN_MODEL.md §11; MCP_PROTOCOL.md §12.1,
 * §12.4, §18). Director output constrains the world, never the player (I-37); diegetic queries only read committed
 * world events (I-38).
 */
public final class NarrativeService {

	public static final Set<String> KINDS = Set.of("QUEST", "STORY_BEAT", "STORY_SEED", "FACTION_STATE", "WORLD_EVENT",
			"LOCATION_DETAIL", "NPC_AGENDA");
	public static final Set<String> QUEST_STATUS = Set.of("OFFERED", "ACCEPTED", "COMPLETED", "FAILED", "ABANDONED");
	public static final Set<String> BEAT_STATES = Set.of("PLANNED", "AVAILABLE", "BLOCKED", "SUPERSEDED", "COMPLETED",
			"ABANDONED");
	public static final Set<String> SEED_KINDS = Set.of("STORY_SEED", "COMPANION_INTRO", "PRESSURE", "PACING_INTENT");
	public static final Set<String> SEED_STATES = Set.of("OPEN", "MATERIALIZED", "SUPERSEDED", "EXPIRED");
	public static final Set<String> CHANNELS = Set.of("NEWSPAPER", "TOWN_CRIER", "RUMOR", "TAVERN", "REFUGEES",
			"TRAVELERS", "MERCHANT", "PRICES", "SOLDIERS", "LETTER", "WITNESS", "ENVIRONMENT", "FACTION_BEHAVIOR",
			"ANY");
	/** Keys that would let planning state dictate player choices or relationship outcomes (I-37). */
	private static final Set<String> FORBIDDEN_KEYS = Set.of("required_player_choice", "player_must", "forced_scene",
			"predetermined_outcome", "relationship_outcome", "must_accept", "unavoidable");

	private final Database db;
	private final SessionService sessions;
	private final CharacterService characters;

	public NarrativeService(Database db, SessionService sessions, CharacterService characters) {
		this.db = db;
		this.sessions = sessions;
		this.characters = characters;
	}

	// ── upsert_narrative_state ─────────────────────────────────────────

	public Map<String, Object> upsert(
			String operationId, String campaignRef, String kind, String ref,
			Map<String, Object> changes, String provenance) {
		long campaignId = Ref.id(campaignRef, Ref.CAMPAIGN);
		var args = new LinkedHashMap<String, Object>();
		args.put("campaign", campaignRef);
		args.put("kind", kind);
		args.put("ref", ref);
		args.put("changes", changes);
		String prov = provenance == null || provenance.isBlank() ? "GM" : provenance.toUpperCase();
		return db.mutate(Database.Mutation.of("upsert_narrative_state", campaignId, operationId, prov, args), tx -> {
			Row campaign = Harness.requireMutation(tx, campaignRef, "upsert_narrative_state");
			Map<String, Object> result = applyOne(tx, campaignId, kind, ref, changes, prov);
			result.put("meta", Harness.meta(campaign, null));
			return result;
		});
	}

	/**
	 * Applies one typed narrative change; shared by upsert_narrative_state, commit_director_changes and campaign
	 * commit.
	 */
	public static Map<String, Object> applyOne(
			Tx tx, long campaignId, String kind, String ref, Map<String, Object> changes, String provenance) {
		String k = kind == null ? "" : kind.toUpperCase();
		if (!KINDS.contains(k)) {
			throw RpgException.invalidArgument("kind must be one of " + KINDS.stream().sorted().toList() + ".");
		}
		if (changes == null || changes.isEmpty()) {
			throw RpgException.invalidArgument("changes must not be empty.");
		}
		for (String key : changes.keySet()) {
			if (FORBIDDEN_KEYS.contains(key.toLowerCase())) {
				throw RpgException.validation(List.of(new Violation(key, "PLAYER_AGENCY",
						"Narrative state may not encode a required player choice or a predetermined relationship outcome (I-37).")));
			}
		}
		return switch (k) {
			case "QUEST" -> quest(tx, campaignId, ref, changes, provenance);
			case "STORY_BEAT" -> beat(tx, campaignId, ref, changes, provenance);
			case "STORY_SEED" -> seed(tx, campaignId, ref, changes, provenance);
			case "FACTION_STATE" -> faction(tx, campaignId, ref, changes, provenance);
			case "WORLD_EVENT" -> worldEvent(tx, campaignId, ref, changes, provenance);
			case "LOCATION_DETAIL" -> locationDetail(tx, campaignId, ref, changes);
			case "NPC_AGENDA" -> agenda(tx, campaignId, ref, changes);
			default -> throw RpgException.invalidArgument("Unhandled kind " + k);
		};
	}

	private static String visibility(Map<String, Object> changes, String fallback) {
		Object v = changes.get("visibility");
		String vis = v == null ? fallback : v.toString().toUpperCase();
		if (!LedgerService.VISIBILITY.contains(vis)) {
			throw RpgException.invalidArgument("visibility must be one of " + LedgerService.VISIBILITY + ".");
		}
		return vis;
	}

	private static Map<String, Object> mergedPayload(
			Row existing, Map<String, Object> changes, Set<String> columnKeys) {
		Map<String, Object> payload = existing == null || existing.isNull("payload_json") ? new LinkedHashMap<>()
				: existing.map("payload_json");
		for (var e : changes.entrySet()) {
			if (!columnKeys.contains(e.getKey())) {
				payload.put(e.getKey(), e.getValue());
			}
		}
		return payload;
	}

	private static Row existing(Tx tx, long campaignId, String table, String ref, String type) {
		if (ref == null || ref.isBlank()) {
			return null;
		}
		Row row = tx.find(table, Ref.id(ref, type)).orElseThrow(() -> RpgException.notFound(type + " " + ref));
		if (row.lng("campaign_id") != campaignId) {
			throw RpgException.invalidArgument(ref + " belongs to another campaign.");
		}
		return row;
	}

	private static Map<String, Object> common(
			Tx tx, long campaignId, String provenance, Map<String, Object> changes,
			String defaultVisibility, Row existing) {
		var cols = new LinkedHashMap<String, Object>();
		if (existing == null) {
			cols.put("campaign_id", campaignId);
			cols.put("created_at", Instant.now().toString());
			cols.put("revision", 0);
			long seq = GameTime.currentSeq(tx, campaignId);
			cols.put("game_time", GameTime.render(seq));
			cols.put("game_seq", seq);
			cols.put("visibility", visibility(changes, defaultVisibility));
		} else {
			cols.put("revision", existing.lng("revision") + 1);
			if (changes.get("visibility") != null) {
				cols.put("visibility", visibility(changes, defaultVisibility));
			}
		}
		cols.put("provenance", provenance);
		if (changes.get("visibility_targets") != null) {
			cols.put("visibility_targets_json", Json.write(changes.get("visibility_targets")));
		}
		return cols;
	}

	// QUEST ------------------------------------------------------------------

	private static Map<String, Object> quest(
			Tx tx, long campaignId, String ref, Map<String, Object> changes, String provenance) {
		Row existing = existing(tx, campaignId, "quest", ref, "quest");
		var cols = common(tx, campaignId, provenance, changes, "PLAYER_KNOWN", existing);
		String status = changes.get("status") == null ? (existing == null ? "OFFERED" : existing.str("status"))
				: changes.get("status").toString().toUpperCase();
		if (!QUEST_STATUS.contains(status)) {
			throw RpgException.invalidArgument("Quest status must be one of " + QUEST_STATUS + ".");
		}
		if (existing == null && changes.get("title") == null) {
			throw RpgException.invalidArgument("A new quest needs a title.");
		}
		if (existing != null && Set.of("COMPLETED", "FAILED", "ABANDONED")
				.contains(existing.str("status")) && !status.equals(existing.str("status"))) {
			throw RpgException.notAllowed(
					"Quest " + ref + " is " + existing.str("status") + " and cannot change status.");
		}
		if (changes.get("title") != null) {
			cols.put("title", changes.get("title").toString());
		}
		cols.put("status", status);
		if (changes.get("issuer") != null) {
			cols.put("issuer_character_id",
					CharacterService.character(tx, campaignId, changes.get("issuer").toString()).id());
		}
		if (changes.get("deadline") != null) {
			cols.put("deadline_seq", GameTime.parse(changes.get("deadline").toString()));
		}
		cols.put("payload_json", Json.write(mergedPayload(existing, changes,
				Set.of("title", "status", "issuer", "deadline", "visibility", "visibility_targets"))));
		long id = existing == null ? tx.insert("quest", cols) : upd(tx, "quest", existing.id(), cols);
		Row q = tx.get("quest", id);
		String previous = existing == null ? null : existing.str("status");
		if (!status.equals(previous)) {
			String type = existing == null && status.equals("OFFERED") ? "QUEST_OFFERED" : "QUEST_" + status;
			LedgerService.append(tx, campaignId,
					new LedgerService.EventSpec(type, "Quest \"" + q.str("title") + "\": " + status.toLowerCase() + ".",
							partyIds(tx, campaignId),
							Set.of("COMPLETED", "FAILED", "ACCEPTED").contains(status) ? "MAJOR" : "NOTABLE",
							q.str("visibility"), provenance, null, null, null, Map.of("quest", Ref.of("quest", id))));
		}
		tx.touched(Ref.of("quest", id), q.lng("revision"));
		return questView(q);
	}

	private static long upd(Tx tx, String table, long id, Map<String, Object> cols) {
		tx.update(table, id, cols);
		return id;
	}

	static List<Long> partyIds(Tx tx, long campaignId) {
		return tx.query(
				"SELECT character_id AS id FROM party_membership WHERE campaign_id = ? AND state IN ('ACTIVE','SEPARATED','GUEST')",
				campaignId).stream().map(Row::id).toList();
	}

	public static Map<String, Object> questView(Row q) {
		var m = new LinkedHashMap<String, Object>();
		m.put("ref", Ref.of("quest", q.id()));
		m.put("kind", "QUEST");
		m.put("title", q.str("title"));
		m.put("status", q.str("status"));
		m.put("issuer", Ref.ofNullable(Ref.CHARACTER, q.lng("issuer_character_id")));
		m.put("deadline", q.isNull("deadline_seq") ? null : GameTime.render(q.lng("deadline_seq")));
		m.put("visibility", q.str("visibility"));
		m.putAll(q.map("payload_json"));
		m.put("revision", q.lng("revision"));
		return m;
	}

	// STORY_BEAT ---------------------------------------------------------------

	private static Map<String, Object> beat(
			Tx tx, long campaignId, String ref, Map<String, Object> changes, String provenance) {
		Row existing = existing(tx, campaignId, "story_beat", ref, Ref.STORY_BEAT);
		var cols = common(tx, campaignId, provenance, changes, "GM_ONLY", existing);
		String state = changes.get("state") == null ? (existing == null ? "PLANNED" : existing.str("state"))
				: changes.get("state").toString().toUpperCase();
		if (!BEAT_STATES.contains(state)) {
			throw RpgException.invalidArgument("Story beat state must be one of " + BEAT_STATES + ".");
		}
		if (existing == null && changes.get("title") == null) {
			throw RpgException.invalidArgument("A new story beat needs a title.");
		}
		if (changes.get("title") != null) {
			cols.put("title", changes.get("title").toString());
		}
		cols.put("state", state);
		if (changes.get("superseded_by") != null) {
			Row successor = existing(tx, campaignId, "story_beat", changes.get("superseded_by").toString(),
					Ref.STORY_BEAT);
			cols.put("superseded_by_id", successor.id());
		}
		cols.put("payload_json", Json.write(mergedPayload(existing, changes,
				Set.of("title", "state", "superseded_by", "visibility", "visibility_targets"))));
		long id = existing == null ? tx.insert("story_beat", cols) : upd(tx, "story_beat", existing.id(), cols);
		Row b = tx.get("story_beat", id);
		if (state.equals("COMPLETED") && (existing == null || !"COMPLETED".equals(existing.str("state")))) {
			LedgerService.append(tx, campaignId,
					new LedgerService.EventSpec("STORY_BEAT_COMPLETED", "Story beat completed: " + b.str("title"),
							partyIds(tx, campaignId), "NOTABLE", b.str("visibility"), provenance, null, null, null,
							Map.of("story_beat", Ref.of(Ref.STORY_BEAT, id))));
		}
		tx.touched(Ref.of(Ref.STORY_BEAT, id), b.lng("revision"));
		return beatView(b);
	}

	public static Map<String, Object> beatView(Row b) {
		var m = new LinkedHashMap<String, Object>();
		m.put("ref", Ref.of(Ref.STORY_BEAT, b.id()));
		m.put("kind", "STORY_BEAT");
		m.put("title", b.str("title"));
		m.put("state", b.str("state"));
		m.put("superseded_by", Ref.ofNullable(Ref.STORY_BEAT, b.lng("superseded_by_id")));
		m.put("visibility", b.str("visibility"));
		m.putAll(b.map("payload_json"));
		m.put("revision", b.lng("revision"));
		return m;
	}

	// STORY_SEED (Director planning) --------------------------------------------

	private static Map<String, Object> seed(
			Tx tx, long campaignId, String ref, Map<String, Object> changes, String provenance) {
		Row existing = existing(tx, campaignId, "director_seed", ref, Ref.SEED);
		var cols = common(tx, campaignId, provenance, changes, "DIRECTOR_ONLY", existing);
		String seedKind = changes.get("seed_kind") == null ? (existing == null ? "STORY_SEED" : existing.str("kind"))
				: changes.get("seed_kind").toString().toUpperCase();
		if (!SEED_KINDS.contains(seedKind)) {
			throw RpgException.invalidArgument("seed_kind must be one of " + SEED_KINDS + ".");
		}
		String state = changes.get("state") == null ? (existing == null ? "OPEN" : existing.str("state"))
				: changes.get("state").toString().toUpperCase();
		if (!SEED_STATES.contains(state)) {
			throw RpgException.invalidArgument("Seed state must be one of " + SEED_STATES + ".");
		}
		if (existing == null && changes.get("intention") == null && changes.get("title") == null) {
			throw RpgException.invalidArgument(
					"A new seed needs an intention (what the Director wants to become possible).");
		}
		cols.put("kind", seedKind);
		cols.put("state", state);
		if (changes.get("superseded_by") != null) {
			Row successor = existing(tx, campaignId, "director_seed", changes.get("superseded_by").toString(),
					Ref.SEED);
			cols.put("superseded_by_id", successor.id());
			if (existing != null && !"SUPERSEDED".equals(state)) {
				cols.put("state", "SUPERSEDED");
			}
		}
		if (changes.get("materialized_character") != null) {
			cols.put("materialized_character_id",
					CharacterService.character(tx, campaignId, changes.get("materialized_character").toString()).id());
			cols.put("state", "MATERIALIZED");
		}
		cols.put("payload_json", Json.write(mergedPayload(existing, changes,
				Set.of("seed_kind", "state", "superseded_by", "materialized_character", "visibility",
						"visibility_targets"))));
		long id = existing == null ? tx.insert("director_seed", cols) : upd(tx, "director_seed", existing.id(), cols);
		Row s = tx.get("director_seed", id);
		tx.touched(Ref.of(Ref.SEED, id), s.lng("revision"));
		return seedView(s);
	}

	public static Map<String, Object> seedView(Row s) {
		var m = new LinkedHashMap<String, Object>();
		m.put("ref", Ref.of(Ref.SEED, s.id()));
		m.put("kind", "STORY_SEED");
		m.put("seed_kind", s.str("kind"));
		m.put("state", s.str("state"));
		m.put("superseded_by", Ref.ofNullable(Ref.SEED, s.lng("superseded_by_id")));
		m.put("materialized_character", Ref.ofNullable(Ref.CHARACTER, s.lng("materialized_character_id")));
		m.put("visibility", s.str("visibility"));
		m.putAll(s.map("payload_json"));
		m.put("revision", s.lng("revision"));
		return m;
	}

	// FACTION_STATE ----------------------------------------------------------------

	private static Map<String, Object> faction(
			Tx tx, long campaignId, String ref, Map<String, Object> changes, String provenance) {
		Row existing = existing(tx, campaignId, "faction", ref, "faction");
		var cols = common(tx, campaignId, provenance, changes, "GM_ONLY", existing);
		if (existing == null && changes.get("name") == null) {
			throw RpgException.invalidArgument("A new faction needs a name.");
		}
		if (changes.get("name") != null) {
			cols.put("name", changes.get("name").toString());
		}
		if (changes.get("standing") != null) {
			Map<String, Object> standing = existing == null || existing.isNull("standing_json") ? new LinkedHashMap<>()
					: existing.map("standing_json");
			if (changes.get("standing") instanceof Map<?, ?> sm) {
				for (var e : sm.entrySet()) {
					standing.put(String.valueOf(e.getKey()), e.getValue());
				}
			} else {
				standing.put("player", changes.get("standing"));
			}
			cols.put("standing_json", Json.write(standing));
		}
		if (changes.get("agenda") != null) {
			cols.put("agenda_json", Json.write(changes.get("agenda")));
		}
		cols.put("payload_json", Json.write(mergedPayload(existing, changes,
				Set.of("name", "standing", "agenda", "visibility", "visibility_targets"))));
		long id = existing == null ? tx.insert("faction", cols) : upd(tx, "faction", existing.id(), cols);
		Row f = tx.get("faction", id);
		tx.touched(Ref.of("faction", id), f.lng("revision"));
		return factionView(f);
	}

	public static Map<String, Object> factionView(Row f) {
		var m = new LinkedHashMap<String, Object>();
		m.put("ref", Ref.of("faction", f.id()));
		m.put("kind", "FACTION_STATE");
		m.put("name", f.str("name"));
		m.put("standing", f.isNull("standing_json") ? Map.of() : f.map("standing_json"));
		m.put("agenda", f.isNull("agenda_json") ? null : Json.readList(
				f.str("agenda_json").startsWith("[") ? f.str("agenda_json") : "[" + f.str("agenda_json") + "]"));
		m.put("visibility", f.str("visibility"));
		m.putAll(f.map("payload_json"));
		m.put("revision", f.lng("revision"));
		return m;
	}

	// WORLD_EVENT -----------------------------------------------------------------

	private static Map<String, Object> worldEvent(
			Tx tx, long campaignId, String ref, Map<String, Object> changes, String provenance) {
		Row existing = existing(tx, campaignId, "world_event", ref, "world_event");
		var cols = common(tx, campaignId, provenance, changes, "GM_ONLY", existing);
		if (existing == null && changes.get("title") == null) {
			throw RpgException.invalidArgument("A new world event needs a title.");
		}
		if (changes.get("title") != null) {
			cols.put("title", changes.get("title").toString());
		}
		if (changes.get("channels") != null) {
			if (!(changes.get("channels") instanceof List<?> list)) {
				throw RpgException.invalidArgument("channels must be a list, e.g. [\"NEWSPAPER\", \"RUMOR\"].");
			}
			var channels = new ArrayList<String>();
			for (Object c : list) {
				String ch = c.toString().toUpperCase();
				if (!CHANNELS.contains(ch)) {
					throw RpgException.invalidArgument(
							"Unknown channel '" + c + "'; known: " + CHANNELS.stream().sorted().toList());
				}
				channels.add(ch);
			}
			cols.put("channels_json", Json.write(channels));
		}
		if (changes.get("locations") != null || changes.get("affected") != null) {
			var refs = new ArrayList<String>();
			for (Object o : changes.get("locations") instanceof List<?> l ? l : List.of()) {
				refs.add(Ref.of(Ref.LOCATION, WorldService.location(tx, campaignId, o.toString()).id()));
			}
			for (Object o : changes.get("affected") instanceof List<?> l ? l : List.of()) {
				refs.add(Ref.parse(o.toString()).toString());
			}
			cols.put("affected_refs_json", Json.write(refs));
		}
		if (changes.get("game_time") != null) {
			long seq = GameTime.parse(changes.get("game_time").toString());
			cols.put("game_time", GameTime.render(seq));
			cols.put("game_seq", seq);
		}
		cols.put("payload_json", Json.write(mergedPayload(existing, changes,
				Set.of("title", "channels", "locations", "affected", "game_time", "visibility",
						"visibility_targets"))));
		long id = existing == null ? tx.insert("world_event", cols) : upd(tx, "world_event", existing.id(), cols);
		Row w = tx.get("world_event", id);
		if (existing == null) {
			LedgerService.append(tx, campaignId,
					new LedgerService.EventSpec("WORLD_EVENT", w.str("title"), List.of(), "NOTABLE",
							w.str("visibility"), provenance, w.lng("game_seq"), null,
							w.map("payload_json").get("description") == null ? null
									: w.map("payload_json").get("description").toString(),
							Map.of("world_event", Ref.of("world_event", id))));
		}
		tx.touched(Ref.of("world_event", id), w.lng("revision"));
		return worldEventView(w);
	}

	public static Map<String, Object> worldEventView(Row w) {
		var m = new LinkedHashMap<String, Object>();
		m.put("ref", Ref.of("world_event", w.id()));
		m.put("kind", "WORLD_EVENT");
		m.put("title", w.str("title"));
		m.put("game_time", w.str("game_time"));
		m.put("channels", w.isNull("channels_json") ? List.of() : w.list("channels_json"));
		m.put("affected", w.isNull("affected_refs_json") ? List.of() : w.list("affected_refs_json"));
		m.put("visibility", w.str("visibility"));
		m.putAll(w.map("payload_json"));
		m.put("revision", w.lng("revision"));
		return m;
	}

	// LOCATION_DETAIL --------------------------------------------------------------

	@SuppressWarnings("unchecked")
	private static Map<String, Object> locationDetail(Tx tx, long campaignId, String ref, Map<String, Object> changes) {
		if (ref == null || ref.isBlank()) {
			throw RpgException.invalidArgument(
					"LOCATION_DETAIL needs an existing location reference; use materialize_location to create places.");
		}
		Row l = WorldService.location(tx, campaignId, ref);
		var cols = new LinkedHashMap<String, Object>();
		if (changes.get("description") != null) {
			cols.put("description", changes.get("description").toString());
		}
		if (changes.get("tags") != null) {
			cols.put("tags_json", Json.write(changes.get("tags")));
		}
		if (changes.get("features") != null) {
			List<Map<String, Object>> features = l.isNull("features_json") ? new ArrayList<>()
					: (List<Map<String, Object>>) (List<?>) l.list("features_json");
			for (Map<String, Object> f : (List<Map<String, Object>>) changes.get("features")) {
				String name = String.valueOf(f.get("name"));
				Optional<Map<String, Object>> match = features.stream()
						.filter(x -> name.equalsIgnoreCase(String.valueOf(x.get("name")))).findFirst();
				if (match.isPresent()) {
					// Knowledge transitions are allowed (a secret becoming known); redefinitions are not (I-41, I-64).
					if (f.get("visibility") != null) {
						match.get().put("visibility", f.get("visibility").toString().toUpperCase());
					}
					if (f.get("description") != null && !f.get("description").equals(match.get().get("description"))) {
						throw RpgException.validation(List.of(new Violation("features", "CONTRADICTION",
								"Feature '" + name + "' already exists; only its visibility may change without an override (I-41).")));
					}
				} else {
					var feature = new LinkedHashMap<String, Object>(f);
					feature.putIfAbsent("visibility", "PARTY_KNOWN");
					features.add(feature);
				}
			}
			cols.put("features_json", Json.write(features));
		}
		cols.put("revision", l.lng("revision") + 1);
		tx.update("location", l.id(), cols);
		Row after = tx.get("location", l.id());
		tx.touched(Ref.of(Ref.LOCATION, l.id()), after.lng("revision"));
		var m = new LinkedHashMap<String, Object>(WorldService.detail(tx, after));
		m.put("kind", "LOCATION_DETAIL");
		return m;
	}

	// NPC_AGENDA -------------------------------------------------------------------

	private static Map<String, Object> agenda(Tx tx, long campaignId, String ref, Map<String, Object> changes) {
		if (ref == null || ref.isBlank()) {
			throw RpgException.invalidArgument("NPC_AGENDA needs a character reference.");
		}
		Row c = CharacterService.character(tx, campaignId, ref);
		Map<String, Object> agenda = c.isNull("agenda_json") ? new LinkedHashMap<>() : c.map("agenda_json");
		agenda.putAll(changes);
		agenda.remove("visibility");
		tx.update("character", c.id(), Map.of("agenda_json", Json.write(agenda), "revision", c.lng("revision") + 1));
		tx.touched(Ref.of(Ref.CHARACTER, c.id()), c.lng("revision") + 1);
		var m = new LinkedHashMap<String, Object>();
		m.put("ref", Ref.of(Ref.CHARACTER, c.id()));
		m.put("kind", "NPC_AGENDA");
		m.put("name", c.str("name"));
		m.put("visibility", "GM_ONLY");
		m.put("agenda", agenda);
		return m;
	}

	// ── get_diegetic_information ───────────────────────────────────────

	/** Committed world events reachable through a channel at a location, at or before now (I-38). */
	public Map<String, Object> diegetic(String campaignRef, String channel, String locationRef, int limit) {
		return db.read(tx -> {
			Row campaign = Harness.campaign(tx, campaignRef);
			long campaignId = campaign.id();
			String ch = channel == null || channel.isBlank() ? "ANY" : channel.toUpperCase();
			if (!CHANNELS.contains(ch)) {
				throw RpgException.invalidArgument(
						"channel must be one of " + CHANNELS.stream().sorted().toList() + ".");
			}
			Long locationId = locationRef == null || locationRef.isBlank() ? campaign.lng("current_location_id")
					: WorldService.location(tx, campaignId, locationRef).id();
			var here = new java.util.HashSet<String>();
			if (locationId != null) {
				for (Long id : WorldService.lineage(tx, locationId)) {
					here.add(Ref.of(Ref.LOCATION, id));
				}
			}
			long now = GameTime.currentSeq(tx, campaignId);
			var items = new ArrayList<Map<String, Object>>();
			for (Row w : tx.query(
					"SELECT * FROM world_event WHERE campaign_id = ? AND (game_seq IS NULL OR game_seq <= ?) ORDER BY game_seq DESC, id DESC",
					campaignId, now)) {
				List<Object> channels = w.isNull("channels_json") ? List.of() : w.list("channels_json");
				if (!ch.equals("ANY") && !channels.contains(ch) && !channels.contains("ANY")) {
					continue;
				}
				List<Object> affected = w.isNull("affected_refs_json") ? List.of() : w.list("affected_refs_json");
				boolean local = affected.stream()
						.noneMatch(a -> a.toString().startsWith(Ref.LOCATION + ":")) || affected.stream()
						.anyMatch(a -> here.contains(a.toString()));
				if (!local) {
					continue;
				}
				var m = worldEventView(w);
				m.put("age_minutes", w.isNull("game_seq") ? null : now - w.lng("game_seq"));
				items.add(m);
				if (items.size() >= Math.max(1, Math.min(limit <= 0 ? 10 : limit, 50))) {
					break;
				}
			}
			var result = new LinkedHashMap<String, Object>();
			result.put("channel", ch);
			result.put("location", Ref.ofNullable(Ref.LOCATION, locationId));
			result.put("game_time", GameTime.toMap(now));
			result.put("items", items);
			result.put("note",
					"These are committed facts the world can surface here; how (a headline, a drunk, a price hike) is yours. Nothing was created by asking.");
			result.put("meta", Harness.meta(campaign, null));
			return result;
		});
	}

	// ── Director ───────────────────────────────────────────────────────

	public Map<String, Object> directorContext(String campaignRef, String scope) {
		return db.read(tx -> {
			Row campaign = Harness.campaign(tx, campaignRef);
			Map<String, Object> result = directorView(tx, campaign,
					scope == null ? "CAMPAIGN_REVIEW" : scope.toUpperCase());
			result.put("meta", Harness.meta(campaign, null));
			return result;
		});
	}

	public Map<String, Object> directorView(Tx tx, Row campaign, String scope) {
		long campaignId = campaign.id();
		var ctx = new LinkedHashMap<String, Object>();
		ctx.put("visibility", "DIRECTOR_ONLY");
		ctx.put("scope", scope);
		ctx.put("game_time", tx.queryOne("SELECT seq FROM game_clock WHERE campaign_id = ?", campaignId)
				.map(r -> GameTime.toMap(r.lng("seq"))).orElse(null));
		ctx.put("adventure", campaign.isNull("adventure_json") ? Map.of() : campaign.map("adventure_json"));
		Map<String, Object> prefs = campaign.map("preferences_json");
		ctx.put("experience_preferences", prefs.get("experience"));
		ctx.put("party_preferences", prefs.get("party"));
		ctx.put("seeds",
				tx.query("SELECT * FROM director_seed WHERE campaign_id = ? AND state = 'OPEN' ORDER BY id", campaignId)
						.stream().map(NarrativeService::seedView).toList());
		ctx.put("companion_intentions", tx.query(
				"SELECT * FROM director_seed WHERE campaign_id = ? AND kind = 'COMPANION_INTRO' AND state IN ('OPEN','SUPERSEDED') ORDER BY id",
				campaignId).stream().map(NarrativeService::seedView).toList());
		ctx.put("story_beats", tx.query(
				"SELECT * FROM story_beat WHERE campaign_id = ? AND state IN ('PLANNED','AVAILABLE','BLOCKED') ORDER BY id",
				campaignId).stream().map(NarrativeService::beatView).toList());
		ctx.put("invalidated_plans",
				tx.query("SELECT * FROM story_beat WHERE campaign_id = ? AND state = 'BLOCKED' ORDER BY id", campaignId)
						.stream().map(NarrativeService::beatView).toList());
		ctx.put("quests",
				tx.query("SELECT * FROM quest WHERE campaign_id = ? AND status IN ('OFFERED','ACCEPTED') ORDER BY id",
						campaignId).stream().map(NarrativeService::questView).toList());
		ctx.put("factions", tx.query("SELECT * FROM faction WHERE campaign_id = ? ORDER BY id", campaignId).stream()
				.map(NarrativeService::factionView).toList());
		ctx.put("world_events",
				tx.query("SELECT * FROM world_event WHERE campaign_id = ? ORDER BY id DESC LIMIT 10", campaignId)
						.stream().map(NarrativeService::worldEventView).toList());
		Optional<Row> pc = tx.queryOne(
				"SELECT c.* FROM player_control_assignment p JOIN character c ON c.id = p.character_id WHERE p.campaign_id = ? AND p.active = 1",
				campaignId);
		ctx.put("player_character", pc.map(r -> characters.sheet(tx, r, "SUMMARY")).orElse(null));
		ctx.put("party", sessions.partyMembers(tx, campaignId, "SUMMARY"));
		ctx.put("relationships", pc.map(r -> PartyService.compact(tx, campaignId, r.id(), 20)).orElse(List.of()));
		ctx.put("npc_agendas", tx.query(
						"SELECT id, name, agenda_json FROM character WHERE campaign_id = ? AND agenda_json IS NOT NULL AND lifecycle = 'ACTIVE' ORDER BY id",
						campaignId).stream()
				.map(c -> Map.of("character", Ref.of(Ref.CHARACTER, c.id()), "name", c.str("name"), "agenda",
						c.map("agenda_json"))).toList());
		ctx.put("recent_major_events", tx.query(
				"SELECT * FROM event WHERE campaign_id = ? AND importance IN ('MAJOR','CRITICAL') ORDER BY id DESC LIMIT 15",
				campaignId).stream().map(e -> LedgerService.eventSummary(tx, e, false)).toList());
		// Pacing: composition of the last 30 events by type family.
		var pacing = new LinkedHashMap<String, Integer>();
		for (Row e : tx.query("SELECT type FROM event WHERE campaign_id = ? ORDER BY id DESC LIMIT 30", campaignId)) {
			String t = e.str("type");
			String family = t.startsWith("ENCOUNTER") || t.equals("CHARACTER_DIED") ? "combat"
					: t.contains("RELATIONSHIP") || t.contains("PARTY_MEMBER") || t.contains("MEETING")
					  ? "relationships" : t.startsWith("QUEST") || t.contains("STORY_BEAT") ? "quests_and_story"
							: t.contains("MOVED") || t.contains("LOCATION") ? "travel_and_exploration"
									: t.contains("PURCHASED") || t.contains("SOLD") || t.contains("LOOT")
									  ? "commerce_and_loot" : "other";
			pacing.merge(family, 1, Integer::sum);
		}
		ctx.put("pacing_last_30_events", pacing);
		Optional<Row> lastReview = tx.queryOne(
				"SELECT * FROM journal_entry WHERE campaign_id = ? AND operation = 'commit_director_changes' ORDER BY id DESC LIMIT 1",
				campaignId);
		ctx.put("last_director_review", lastReview.map(
				r -> Map.of("journal_id", r.id(), "game_seq", r.lng("game_seq") == null ? 0 : r.lng("game_seq"),
						"recorded_at", r.str("recorded_at"))).orElse(null));
		ctx.put("guidance",
				List.of("Create seeds, pressures and world facts — never scenes the player must play or outcomes the player must feel.",
						"A review may legitimately change nothing.",
						"Prefer diegetic delivery: attach channels and locations to world events so the GM can surface them naturally."));
		return ctx;
	}

	/**
	 * Validates and atomically commits a set of typed Director proposals; records that a review occurred even if
	 * empty.
	 */
	@SuppressWarnings("unchecked")
	public Map<String, Object> commitDirectorChanges(
			String operationId, String campaignRef, List<Map<String, Object>> changes, String reviewNote) {
		long campaignId = Ref.id(campaignRef, Ref.CAMPAIGN);
		var args = new LinkedHashMap<String, Object>();
		args.put("campaign", campaignRef);
		args.put("changes", changes);
		args.put("review_note", reviewNote);
		return db.mutate(Database.Mutation.of("commit_director_changes", campaignId, operationId, "DIRECTOR", args),
				tx -> {
					Row campaign = Harness.requireMutation(tx, campaignRef, "commit_director_changes");
					var applied = new ArrayList<Map<String, Object>>();
					int index = 0;
					for (Map<String, Object> change : changes == null ? List.<Map<String, Object>> of() : changes) {
						String kind = change.get("kind") == null ? null : change.get("kind").toString().toUpperCase();
						if (kind == null) {
							throw RpgException.invalidArgument("changes[" + index + "].kind is required.");
						}
						kind = switch (kind) {
							case "PRESSURE", "PACING_INTENT", "COMPANION_INTRO", "SEED" -> "STORY_SEED";
							case "FACTION" -> "FACTION_STATE";
							default -> kind;
						};
						var body = new LinkedHashMap<String, Object>(
								change.get("changes") instanceof Map<?, ?> m ? (Map<String, Object>) m : change);
						body.remove("kind");
						body.remove("ref");
						String original = change.get("kind").toString().toUpperCase();
						if (Set.of("PRESSURE", "PACING_INTENT", "COMPANION_INTRO").contains(original)) {
							body.put("seed_kind", original);
						}
						applied.add(applyOne(tx, campaignId, kind,
								change.get("ref") == null ? null : change.get("ref").toString(), body, "DIRECTOR"));
						index++;
					}
					LedgerService.append(tx, campaignId, new LedgerService.EventSpec("DIRECTOR_REVIEW",
							"Director review: " + applied.size() + " change(s)" + (
									reviewNote == null || reviewNote.isBlank() ? "." : " — " + reviewNote), List.of(),
							"MINOR", "DIRECTOR_ONLY", "DIRECTOR", null, campaign.lng("current_location_id"), null,
							Map.of("changes", applied.size())));
					var result = new LinkedHashMap<String, Object>();
					result.put("applied", applied);
					result.put("review_recorded", true);
					result.put("meta", Harness.meta(campaign, null));
					return result;
				});
	}

	// ── get_context ────────────────────────────────────────────────────

	public Map<String, Object> context(
			String campaignRef, String scope, String ref, String secondRef, String focus, Integer budget) {
		return db.read(tx -> {
			Row campaign = Harness.campaign(tx, campaignRef);
			long campaignId = campaign.id();
			String s = scope == null ? "SCENE" : scope.toUpperCase();
			int b = budget == null ? 8000 : budget;
			Map<String, Object> ctx;
			switch (s) {
			case "SCENE" -> ctx = sessions.context(tx, campaign, b);
			case "CHARACTER" -> {
				Row c = CharacterService.character(tx, campaignId, required(ref, "ref (character)"));
				ctx = new LinkedHashMap<>();
				ctx.put("character", characters.sheet(tx, c, "FULL"));
				if (!c.isNull("agenda_json")) {
					ctx.put("agenda", Map.of("visibility", "GM_ONLY", "agenda", c.map("agenda_json")));
				}
				ctx.put("relationships", PartyService.compact(tx, campaignId, c.id(), 20));
				ctx.put("recent_events", tx.query(
						"SELECT e.* FROM event e JOIN event_actor a ON a.event_id = e.id AND a.character_id = ? WHERE e.campaign_id = ? ORDER BY e.id DESC LIMIT 10",
						c.id(), campaignId).stream().map(e -> LedgerService.eventSummary(tx, e, false)).toList());
				ctx.put("location", c.isNull("location_id") ? null
						: WorldService.summary(tx.get("location", c.lng("location_id"))));
			}
			case "RELATIONSHIP" -> {
				Row a = CharacterService.character(tx, campaignId, required(ref, "ref (character)"));
				Row other = CharacterService.character(tx, campaignId, required(secondRef, "second_ref (character)"));
				ctx = new LinkedHashMap<>();
				ctx.put("a", Map.of("ref", Ref.of(Ref.CHARACTER, a.id()), "name", a.str("name")));
				ctx.put("b", Map.of("ref", Ref.of(Ref.CHARACTER, other.id()), "name", other.str("name")));
				ctx.put("relationship", db == null ? null : relationshipPair(tx, campaignId, a, other));
				ctx.put("shared_events", tx.query(
								"SELECT DISTINCT e.* FROM event e JOIN event_actor x ON x.event_id = e.id AND x.character_id = ? " + "JOIN event_actor y ON y.event_id = e.id AND y.character_id = ? WHERE e.campaign_id = ? ORDER BY e.fictional_seq DESC LIMIT 15",
								a.id(), other.id(), campaignId).stream()
						.map(e -> LedgerService.eventSummary(tx, e, focus != null)).toList());
			}
			case "LOCATION" -> {
				Row l = ref == null || ref.isBlank() ? tx.get("location",
						Optional.ofNullable(campaign.lng("current_location_id"))
								.orElseThrow(() -> RpgException.notFound("A current location")))
						: WorldService.location(tx, campaignId, ref);
				ctx = new LinkedHashMap<>(WorldService.detail(tx, l));
				var here = new java.util.HashSet<String>();
				for (Long id : WorldService.lineage(tx, l.id())) {
					here.add(Ref.of(Ref.LOCATION, id));
				}
				ctx.put("world_events_here",
						tx.query("SELECT * FROM world_event WHERE campaign_id = ? ORDER BY id DESC LIMIT 20",
										campaignId).stream().map(NarrativeService::worldEventView)
								.filter(w -> ((List<?>) w.get("affected")).stream()
										.anyMatch(a -> here.contains(a.toString()))).toList());
				ctx.put("events_here", tx.query(
						"SELECT * FROM event WHERE campaign_id = ? AND location_id = ? ORDER BY id DESC LIMIT 10",
						campaignId, l.id()).stream().map(e -> LedgerService.eventSummary(tx, e, false)).toList());
			}
			case "QUEST" -> {
				ctx = new LinkedHashMap<>();
				if (ref != null && !ref.isBlank()) {
					Row q = existing(tx, campaignId, "quest", ref, "quest");
					ctx.put("quest", questView(q));
				} else {
					ctx.put("quests", tx.query(
							"SELECT * FROM quest WHERE campaign_id = ? ORDER BY CASE status WHEN 'ACCEPTED' THEN 0 WHEN 'OFFERED' THEN 1 ELSE 2 END, id",
							campaignId).stream().map(NarrativeService::questView).toList());
				}
				ctx.put("story_beats", tx.query(
						"SELECT * FROM story_beat WHERE campaign_id = ? AND state IN ('AVAILABLE','PLANNED','BLOCKED') ORDER BY id",
						campaignId).stream().map(NarrativeService::beatView).toList());
			}
			case "ENCOUNTER" -> {
				Row e = tx.queryOne(
						"SELECT * FROM encounter WHERE campaign_id = ? AND status IN ('RUNNING','WAITING_CHOICE') ORDER BY id DESC LIMIT 1",
						campaignId).orElseThrow(() -> RpgException.notFound("A running encounter"));
				ctx = new LinkedHashMap<>();
				ctx.put("encounter", sessions.encounterState(tx, e, 10));
			}
			case "DIRECTOR" -> ctx = directorView(tx, campaign, "CAMPAIGN_REVIEW");
			default -> throw RpgException.invalidArgument(
					"scope must be SCENE, CHARACTER, RELATIONSHIP, LOCATION, QUEST, ENCOUNTER or DIRECTOR.");
			}
			ctx.put("scope", s);
			ctx.put("meta", Harness.meta(campaign, null));
			return ctx;
		});
	}

	private static Map<String, Object> relationshipPair(Tx tx, long campaignId, Row a, Row b) {
		var m = new LinkedHashMap<String, Object>();
		m.put("a_to_b", tx.queryOne(
						"SELECT summary, dimensions_json FROM relationship WHERE campaign_id = ? AND from_character_id = ? AND to_character_id = ?",
						campaignId, a.id(), b.id())
				.map(r -> Map.<String, Object> of("summary", r.str("summary") == null ? "" : r.str("summary"),
						"dimensions", PartyService.rendered(r.map("dimensions_json")))).orElse(null));
		m.put("b_to_a", tx.queryOne(
						"SELECT summary, dimensions_json FROM relationship WHERE campaign_id = ? AND from_character_id = ? AND to_character_id = ?",
						campaignId, b.id(), a.id())
				.map(r -> Map.<String, Object> of("summary", r.str("summary") == null ? "" : r.str("summary"),
						"dimensions", PartyService.rendered(r.map("dimensions_json")))).orElse(null));
		return m;
	}

	private static String required(String value, String what) {
		if (value == null || value.isBlank()) {
			throw RpgException.invalidArgument(what + " is required for this scope.");
		}
		return value;
	}
}
