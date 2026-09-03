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
package se.hirt.mcp.rpg.party;

import se.hirt.mcp.rpg.character.CharacterService;
import se.hirt.mcp.rpg.harness.Harness;
import se.hirt.mcp.rpg.ledger.LedgerService;
import se.hirt.mcp.rpg.persistence.Database;
import se.hirt.mcp.rpg.persistence.Row;
import se.hirt.mcp.rpg.persistence.Tx;
import se.hirt.mcp.rpg.progression.PartyXp;
import se.hirt.mcp.rpg.protocol.Json;
import se.hirt.mcp.rpg.protocol.Ref;
import se.hirt.mcp.rpg.protocol.RpgException;
import se.hirt.mcp.rpg.protocol.Violation;
import se.hirt.mcp.rpg.session.GameTime;

import java.util.*;

/**
 * Party membership as history (DOMAIN_MODEL.md §6, I-17..I-19) and relationships as compact state plus references to
 * the ledger events that shaped them (DESIGN.md §11, DOMAIN_MODEL.md §7, I-20..I-22).
 * <p>
 * Relationship dimensions are integers from -5 to +5 (RULES_ENGINE.md open item, decided here): affection, trust,
 * respect, attraction, fear, resentment, loyalty. Values are rendered with qualitative labels for the GM.
 */
public final class PartyService {

	public static final Set<String> CHANGES = Set.of("JOIN", "LEAVE", "DISMISS", "SEPARATE", "REJOIN", "GUEST_ADD",
			"GUEST_REMOVE");
	public static final List<String> DIMENSIONS = List.of("affection", "trust", "respect", "attraction", "fear",
			"resentment", "loyalty");
	public static final int MIN = -5;
	public static final int MAX = 5;

	private final Database db;
	private final se.hirt.mcp.rpg.content.RulesData rules;
	private final se.hirt.mcp.rpg.session.SessionService sessions;

	public PartyService(
			Database db, se.hirt.mcp.rpg.content.RulesData rules,
			se.hirt.mcp.rpg.session.SessionService sessions) {
		this.db = db;
		this.rules = rules;
		this.sessions = sessions;
	}

	// ── update_party_membership ────────────────────────────────────────

	public Map<String, Object> updateMembership(
			String operationId, String campaignRef, String characterRef, String change, String reason) {
		long campaignId = Ref.id(campaignRef, Ref.CAMPAIGN);
		var args = new LinkedHashMap<String, Object>();
		args.put("campaign", campaignRef);
		args.put("character", characterRef);
		args.put("change", change);
		args.put("reason", reason);
		return db.mutate(Database.Mutation.of("update_party_membership", campaignId, operationId, "GM", args), tx -> {
			Row campaign = Harness.requireMutation(tx, campaignRef, "update_party_membership");
			String k = change == null ? "" : change.toUpperCase();
			if (!CHANGES.contains(k)) {
				throw RpgException.invalidArgument("change must be one of " + CHANGES.stream().sorted().toList() + ".");
			}
			Row c = CharacterService.character(tx, campaignId, characterRef);
			if (!"ACTIVE".equals(c.str("lifecycle"))) {
				throw RpgException.notAllowed(characterRef + " is not an active character.");
			}
			boolean playerControlled = tx.count(
					"SELECT COUNT(*) FROM player_control_assignment WHERE campaign_id = ? AND character_id = ? AND active = 1",
					campaignId, c.id()) > 0;
			if (playerControlled && !k.equals("JOIN") && !k.equals("REJOIN")) {
				throw RpgException.notAllowed(
						"The player character cannot leave the party; transfer_player_control first.");
			}
			Optional<Row> open = tx.queryOne(
					"SELECT * FROM party_membership WHERE campaign_id = ? AND character_id = ? AND state IN ('ACTIVE','SEPARATED','GUEST')",
					campaignId, c.id());
			String openState = open.map(r -> r.str("state")).orElse(null);
			long seq = GameTime.currentSeq(tx, campaignId);
			String eventType;
			String newState;
			boolean newRow = false;
			switch (k) {
			case "JOIN" -> {
				if (open.isPresent()) {
					throw transition(k, openState, "the character already has an open membership");
				}
				if ("DEAD".equals(c.str("life_state"))) {
					throw RpgException.notAllowed(c.str("name") + " is dead.");
				}
				eventType = "PARTY_MEMBER_JOINED";
				newState = "ACTIVE";
				newRow = true;
			}
			case "REJOIN" -> {
				if ("SEPARATED".equals(openState)) {
					newState = "ACTIVE";
					newRow = false;
				} else if (open.isEmpty()) {
					if ("DEAD".equals(c.str("life_state"))) {
						throw RpgException.notAllowed(c.str("name") + " is dead.");
					}
					newState = "ACTIVE";
					newRow = true;
				} else {
					throw transition(k, openState, "only separated or former members rejoin");
				}
				eventType = "PARTY_MEMBER_REJOINED";
			}
			case "SEPARATE" -> {
				if (!"ACTIVE".equals(openState)) {
					throw transition(k, openState, "only active members separate");
				}
				eventType = "PARTY_MEMBER_SEPARATED";
				newState = "SEPARATED";
			}
			case "LEAVE", "DISMISS" -> {
				if (!"ACTIVE".equals(openState) && !"SEPARATED".equals(openState)) {
					throw transition(k, openState, "the character is not a member");
				}
				eventType = k.equals("LEAVE") ? "PARTY_MEMBER_LEFT" : "PARTY_MEMBER_DISMISSED";
				newState = k.equals("LEAVE") ? "LEFT" : "DISMISSED";
			}
			case "GUEST_ADD" -> {
				if (open.isPresent()) {
					throw transition(k, openState, "the character already has an open membership");
				}
				eventType = "GUEST_JOINED";
				newState = "GUEST";
				newRow = true;
			}
			case "GUEST_REMOVE" -> {
				if (!"GUEST".equals(openState)) {
					throw transition(k, openState, "the character is not a guest");
				}
				eventType = "GUEST_LEFT";
				newState = "ENDED";
			}
			default -> throw RpgException.invalidArgument("Unhandled change " + k);
			}
			boolean major = Set.of("PARTY_MEMBER_JOINED", "PARTY_MEMBER_LEFT", "PARTY_MEMBER_DISMISSED")
					.contains(eventType);
			long eventId = LedgerService.append(tx, campaignId, new LedgerService.EventSpec(eventType,
					c.str("name") + " " + describe(k) + (reason == null || reason.isBlank() ? "." : ": " + reason),
					List.of(c.id()), major ? "MAJOR" : "NOTABLE", "PARTY_KNOWN", "GM", null, c.lng("location_id"), null,
					Map.of("change", k)));
			long membershipId;
			if (newRow) {
				var cols = new LinkedHashMap<String, Object>();
				cols.put("campaign_id", campaignId);
				cols.put("character_id", c.id());
				cols.put("state", newState);
				cols.put("joined_time", GameTime.render(seq));
				cols.put("joined_seq", seq);
				cols.put("cause_event_id", eventId);
				cols.put("notes", reason);
				membershipId = tx.insert("party_membership", cols);
			} else {
				var cols = new LinkedHashMap<String, Object>();
				cols.put("state", newState);
				cols.put("cause_event_id", eventId);
				if (Set.of("LEFT", "DISMISSED", "ENDED").contains(newState)) {
					cols.put("left_time", GameTime.render(seq));
					cols.put("left_seq", seq);
				}
				tx.update("party_membership", open.get().id(), cols);
				membershipId = open.get().id();
			}
			if (Set.of("LEFT", "DISMISSED", "ENDED").contains(newState)) {
				tx.update("character", c.id(), Map.of("revision", c.lng("revision") + 1));
			}
			// A recruit joins at the party's current experience rather than at zero, under every xp_policy: what the
			// policy governs is what a companion earns from here on, not what they arrive with (RULES_ENGINE.md §6).
			Map<String, Object> joined = null;
			if ("ACTIVE".equals(newState) && !PartyXp.playerControlled(tx, campaignId, c.id())) {
				Row live = tx.get("character", c.id());
				long target = PartyXp.joiningXp(tx, campaign);
				if (target > live.lng("xp")) {
					joined = se.hirt.mcp.rpg.character.RuntimeService.grantXp(tx, rules, live,
							target - live.lng("xp"));
					LedgerService.append(tx, campaignId, new LedgerService.EventSpec("XP_AWARDED",
							live.str("name") + " joined the party at its current experience (" + target + " XP).",
							List.of(live.id()), "MINOR", "GM_ONLY", "GM", null, live.lng("location_id"), null,
							Map.of("amount", target - live.lng("xp"), "source", "PARTY_JOIN")));
				}
			}
			var result = new LinkedHashMap<String, Object>();
			result.put("character", Ref.of(Ref.CHARACTER, c.id()));
			result.put("name", c.str("name"));
			result.put("change", k);
			result.put("membership_state", newState);
			result.put("membership", membershipId);
			result.put("event", Ref.of(Ref.EVENT, eventId));
			if (joined != null) {
				result.put("joined_at_party_experience", joined);
			}
			result.put("party", sessions.partyMembers(tx, campaignId, "SUMMARY"));
			result.put("meta", Harness.meta(campaign, null));
			return result;
		});
	}

	private static String describe(String change) {
		return switch (change) {
			case "JOIN" -> "joined the party";
			case "REJOIN" -> "rejoined the party";
			case "SEPARATE" -> "temporarily separated from the party";
			case "LEAVE" -> "left the party";
			case "DISMISS" -> "was dismissed from the party";
			case "GUEST_ADD" -> "is travelling with the party as a guest";
			default -> "is no longer travelling with the party";
		};
	}

	private static RpgException transition(String change, String state, String why) {
		return RpgException.validation(List.of(new Violation("change", "ILLEGAL_TRANSITION",
				change + " is not legal from membership state " + (state == null ? "NONE"
						: state) + ": " + why + ".")));
	}

	// ── relationships ──────────────────────────────────────────────────

	public Map<String, Object> relationship(String campaignRef, String aRef, String bRef) {
		return db.read(tx -> {
			Row campaign = Harness.campaign(tx, campaignRef);
			Row a = CharacterService.character(tx, campaign.id(), aRef);
			Row b = CharacterService.character(tx, campaign.id(), bRef);
			var result = new LinkedHashMap<String, Object>();
			result.put("a", Map.of("ref", Ref.of(Ref.CHARACTER, a.id()), "name", a.str("name")));
			result.put("b", Map.of("ref", Ref.of(Ref.CHARACTER, b.id()), "name", b.str("name")));
			result.put("a_to_b", direction(tx, campaign.id(), a.id(), b.id()));
			result.put("b_to_a", direction(tx, campaign.id(), b.id(), a.id()));
			result.put("dimension_scale",
					"integers " + MIN + " (strongly negative) to " + MAX + " (strongly positive); 0 is neutral");
			result.put("meta", Harness.meta(campaign, null));
			return result;
		});
	}

	private static Map<String, Object> direction(Tx tx, long campaignId, long from, long to) {
		Optional<Row> r = tx.queryOne(
				"SELECT * FROM relationship WHERE campaign_id = ? AND from_character_id = ? AND to_character_id = ?",
				campaignId, from, to);
		if (r.isEmpty()) {
			return null;
		}
		Row rel = r.get();
		var m = new LinkedHashMap<String, Object>();
		m.put("summary", rel.str("summary"));
		m.put("dimensions", rendered(rel.map("dimensions_json")));
		m.put("revision", rel.lng("revision"));
		var events = new ArrayList<Map<String, Object>>();
		for (Row e : tx.query(
				"SELECT e.* FROM relationship_event re JOIN event e ON e.id = re.event_id WHERE re.relationship_id = ? ORDER BY e.fictional_seq, e.id",
				rel.id())) {
			events.add(LedgerService.eventSummary(tx, e, true));
		}
		m.put("significant_events", events);
		return m;
	}

	public static Map<String, Object> rendered(Map<String, Object> dimensions) {
		var out = new LinkedHashMap<String, Object>();
		for (var e : dimensions.entrySet()) {
			int v = ((Number) e.getValue()).intValue();
			out.put(e.getKey(), Map.of("value", v, "label", label(v)));
		}
		return out;
	}

	static String label(int v) {
		if (v <= -4) {
			return "very strongly negative";
		}
		if (v <= -2) {
			return "negative";
		}
		if (v == -1) {
			return "slightly negative";
		}
		if (v == 0) {
			return "neutral";
		}
		if (v == 1) {
			return "slight";
		}
		if (v <= 3) {
			return "strong";
		}
		return "very strong";
	}

	/** Compact summaries of one character's relationships, for context building. */
	public static List<Map<String, Object>> compact(Tx tx, long campaignId, long characterId, int limit) {
		var out = new ArrayList<Map<String, Object>>();
		for (Row r : tx.query(
				"SELECT r.*, c.name AS to_name FROM relationship r JOIN character c ON c.id = r.to_character_id WHERE r.campaign_id = ? AND r.from_character_id = ? ORDER BY r.revision DESC, r.id LIMIT ?",
				campaignId, characterId, limit)) {
			var m = new LinkedHashMap<String, Object>();
			m.put("to", Ref.of(Ref.CHARACTER, r.lng("to_character_id")));
			m.put("name", r.str("to_name"));
			m.put("summary", r.str("summary"));
			m.put("dimensions", r.map("dimensions_json"));
			m.put("significant_events",
					tx.count("SELECT COUNT(*) FROM relationship_event WHERE relationship_id = ?", r.id()));
			out.add(m);
		}
		return out;
	}

	public Map<String, Object> updateRelationship(
			String operationId, String campaignRef, String fromRef, String toRef, Map<String, Object> dimensions,
			String summary, String causeEventRef, String reason, boolean mutual, String provenance) {
		long campaignId = Ref.id(campaignRef, Ref.CAMPAIGN);
		var args = new LinkedHashMap<String, Object>();
		args.put("campaign", campaignRef);
		args.put("from", fromRef);
		args.put("to", toRef);
		args.put("dimensions", dimensions);
		args.put("summary", summary);
		args.put("cause_event", causeEventRef);
		args.put("reason", reason);
		args.put("mutual", mutual);
		String prov = provenance == null || provenance.isBlank() ? "GM" : provenance.toUpperCase();
		return db.mutate(Database.Mutation.of("update_relationship", campaignId, operationId, prov, args), tx -> {
			Row campaign = Harness.requireMutation(tx, campaignRef, "update_relationship");
			Row from = CharacterService.character(tx, campaignId, fromRef);
			Row to = CharacterService.character(tx, campaignId, toRef);
			if (from.id() == to.id()) {
				throw RpgException.invalidArgument("A relationship needs two different characters.");
			}
			for (Row c : List.of(from, to)) {
				if (!"ACTIVE".equals(c.str("lifecycle"))) {
					throw RpgException.notAllowed(Ref.of(Ref.CHARACTER, c.id()) + " is not an active character.");
				}
			}
			if ((causeEventRef == null || causeEventRef.isBlank()) && (reason == null || reason.isBlank())) {
				throw RpgException.invalidArgument(
						"A relationship change needs a cause: cause_event (event:N) or a reason (I-20).");
			}
			if ((summary == null || summary.isBlank()) && (dimensions == null || dimensions.isEmpty()) && (causeEventRef == null || causeEventRef.isBlank())) {
				throw RpgException.invalidArgument("Provide a summary, dimensions, or a cause_event to link.");
			}
			Long eventId = null;
			if (causeEventRef != null && !causeEventRef.isBlank()) {
				Row e = tx.find("event", Ref.id(causeEventRef, Ref.EVENT))
						.orElseThrow(() -> RpgException.notFound("Event " + causeEventRef));
				if (e.lng("campaign_id") != campaignId) {
					throw RpgException.invalidArgument(causeEventRef + " belongs to another campaign.");
				}
				for (Row c : List.of(from, to)) {
					if (tx.count("SELECT COUNT(*) FROM event_actor WHERE event_id = ? AND character_id = ?", e.id(),
							c.id()) == 0) {
						throw RpgException.validation(List.of(new Violation("cause_event", "NOT_A_PARTICIPANT",
								c.str("name") + " is not a participant of " + causeEventRef + " (I-21).")));
					}
				}
				eventId = e.id();
			}
			var updated = new ArrayList<Map<String, Object>>();
			updated.add(upsert(tx, campaignId, from, to, dimensions, summary, eventId));
			if (mutual) {
				updated.add(upsert(tx, campaignId, to, from, dimensions, summary, eventId));
			}
			if (eventId == null) {
				eventId = LedgerService.append(tx, campaignId, new LedgerService.EventSpec("RELATIONSHIP_CHANGED",
						from.str("name") + " → " + to.str("name") + (mutual ? " (mutual)" : "") + ": " + (
								summary == null ? reason : summary) + " (" + reason + ")", List.of(from.id(), to.id()),
						"NOTABLE", "GM_ONLY", prov, null, from.lng("location_id"), null,
						Map.of("dimensions", dimensions == null ? Map.of() : dimensions)));
			}
			var result = new LinkedHashMap<String, Object>();
			result.put("relationships", updated);
			result.put("event", Ref.of(Ref.EVENT, eventId));
			result.put("meta", Harness.meta(campaign, null));
			return result;
		});
	}

	private static Map<String, Object> upsert(
			Tx tx, long campaignId, Row from, Row to, Map<String, Object> dimensions, String summary, Long eventId) {
		Optional<Row> existing = tx.queryOne(
				"SELECT * FROM relationship WHERE campaign_id = ? AND from_character_id = ? AND to_character_id = ?",
				campaignId, from.id(), to.id());
		Map<String, Object> dims = existing.map(r -> r.map("dimensions_json")).orElseGet(LinkedHashMap::new);
		if (dimensions != null) {
			for (var e : dimensions.entrySet()) {
				String key = e.getKey().toLowerCase();
				if (!DIMENSIONS.contains(key)) {
					throw RpgException.invalidArgument(
							"Unknown relationship dimension '" + e.getKey() + "'; known: " + DIMENSIONS + ".");
				}
				int current = dims.get(key) instanceof Number n ? n.intValue() : 0;
				Object v = e.getValue();
				int value;
				if (v instanceof Number n) {
					value = n.intValue();
				} else {
					String s = String.valueOf(v).trim();
					if (s.startsWith("+") || s.startsWith("-")) {
						value = current + Integer.parseInt(s.startsWith("+") ? s.substring(1) : s);
					} else {
						value = Integer.parseInt(s);
					}
				}
				dims.put(key, Math.max(MIN, Math.min(MAX, value)));
			}
		}
		long id;
		if (existing.isPresent()) {
			var cols = new LinkedHashMap<String, Object>();
			cols.put("dimensions_json", Json.write(dims));
			if (summary != null && !summary.isBlank()) {
				cols.put("summary", summary.trim());
			}
			cols.put("revision", existing.get().lng("revision") + 1);
			tx.update("relationship", existing.get().id(), cols);
			id = existing.get().id();
		} else {
			var cols = new LinkedHashMap<String, Object>();
			cols.put("campaign_id", campaignId);
			cols.put("from_character_id", from.id());
			cols.put("to_character_id", to.id());
			cols.put("dimensions_json", Json.write(dims));
			cols.put("summary", summary == null ? null : summary.trim());
			cols.put("revision", 0);
			id = tx.insert("relationship", cols);
		}
		if (eventId != null && tx.count(
				"SELECT COUNT(*) FROM relationship_event WHERE relationship_id = ? AND event_id = ?", id,
				eventId) == 0) {
			tx.insert("relationship_event", Map.of("relationship_id", id, "event_id", eventId));
		}
		Row rel = tx.get("relationship", id);
		var m = new LinkedHashMap<String, Object>();
		m.put("from", Ref.of(Ref.CHARACTER, from.id()));
		m.put("to", Ref.of(Ref.CHARACTER, to.id()));
		m.put("summary", rel.str("summary"));
		m.put("dimensions", rendered(dims));
		m.put("revision", rel.lng("revision"));
		m.put("significant_events", tx.count("SELECT COUNT(*) FROM relationship_event WHERE relationship_id = ?", id));
		return m;
	}
}
