/* Copyright (C) 2026 Marcus Hirt. SPDX-License-Identifier: BSD-3-Clause */
package se.hirt.mcp.rpg.party;

import se.hirt.mcp.rpg.character.CharacterService;
import se.hirt.mcp.rpg.harness.Harness;
import se.hirt.mcp.rpg.persistence.Database;
import se.hirt.mcp.rpg.persistence.Row;
import se.hirt.mcp.rpg.persistence.Tx;
import se.hirt.mcp.rpg.protocol.Ref;
import se.hirt.mcp.rpg.protocol.RpgException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Saved companion decision control; never changes character identity or party membership. */
public final class TacticalControl {

	private final Database db;

	public TacticalControl(Database db) {
		this.db = db;
	}

	/** Null characters selects every companion and establishes a policy for later recruits. */
	public Map<String, Object> set(
		String op, String campaignRef, List<String> characters, String controller, String scope, String encounterRef,
		String reason) {
		long cid = Ref.id(campaignRef, Ref.CAMPAIGN);
		var args = new LinkedHashMap<String, Object>();
		args.put("characters", characters);
		args.put("controller", controller);
		args.put("scope", scope);
		args.put("encounter", encounterRef);
		args.put("reason", reason);
		return db.mutate(Database.Mutation.of("set_tactical_control", cid, op, "PLAYER", args), tx -> {
			Row campaign = Harness.requireMutation(tx, campaignRef, "set_tactical_control");
			String owner = controller == null ? "" : controller.toUpperCase(Locale.ROOT);
			if (!List.of("DM", "PLAYER").contains(owner))
				throw RpgException.invalidArgument("controller must be DM or PLAYER.");
			if (reason != null && reason.length() > 2000)
				throw RpgException.invalidArgument("Use a reason of at most 2000 characters.");
			Row encounter = encounterRef == null ? activeEncounter(tx, cid)
					: requireLiveEncounter(tx, cid, encounterRef);
			String lifetime = scope == null ? encounter == null ? "UNTIL_RELEASED" : "ENCOUNTER"
					: scope.toUpperCase(Locale.ROOT);
			if (!List.of("ENCOUNTER", "UNTIL_RELEASED").contains(lifetime))
				throw RpgException.invalidArgument("scope must be ENCOUNTER or UNTIL_RELEASED.");
			if (lifetime.equals("ENCOUNTER") && encounter == null)
				throw RpgException.notAllowed(
						"Encounter control requires a running encounter; use UNTIL_RELEASED outside combat.");
			if (lifetime.equals("UNTIL_RELEASED") && encounterRef != null)
				throw RpgException.invalidArgument("UNTIL_RELEASED control must not name an encounter.");
			Long eid = lifetime.equals("ENCOUNTER") ? encounter.id() : null;
			var targets = new LinkedHashSet<Long>();
			if (characters != null) {
				if (characters.isEmpty() || characters.size() > 200)
					throw RpgException
							.invalidArgument("List 1–200 companions, or omit characters to select all companions.");
				for (String ref : characters) {
					Row c = CharacterService.character(tx, cid, ref);
					if (primary(tx, cid, c.id()))
						throw RpgException.invalidArgument(
								"The primary character remains player-controlled; select companions or omit characters for all companions.");
					if (!"ACTIVE".equals(c.str("lifecycle")) || membership(tx, cid, c.id()) == null)
						throw RpgException
								.invalidArgument(ref + " is not a current companion (ACTIVE, GUEST or SEPARATED).");
					targets.add(c.id());
				}
			}
			var cleared = new ArrayList<String>();
			var bridges = new ArrayList<String>();
			if (characters == null) {
				// An all-party handback also clears earlier opt-outs in this exact scope.
				for (Row override : tx.query(
						"SELECT * FROM tactical_control WHERE campaign_id=? AND encounter_id IS ? AND character_id IS NOT NULL",
						cid, eid))
					tx.delete("tactical_control", override.id());
				write(tx, cid, null, eid, owner, lifetime, reason);
				if (lifetime.equals("UNTIL_RELEASED")) {
					for (Row override : tx.query(
							"SELECT t.* FROM tactical_control t JOIN encounter e ON e.id=t.encounter_id WHERE t.campaign_id=? AND e.status IN ('RUNNING','WAITING_CHOICE')",
							cid)) {
						cleared.add("tactical_control:" + override.id());
						tx.delete("tactical_control", override.id());
					}
				}
			} else {
				for (long character : targets) {
					write(tx, cid, character, eid, owner, lifetime, reason);
					if (lifetime.equals("UNTIL_RELEASED")) {
						Row current = encounterFor(tx, cid, tx.get("character", character));
						if (current != null) {
							for (Row override : tx.query(
									"SELECT id FROM tactical_control WHERE campaign_id=? AND encounter_id=? AND character_id=?",
									cid, current.id(), character)) {
								cleared.add("tactical_control:" + override.id());
								tx.delete("tactical_control", override.id());
							}
							if (tx.count(
									"SELECT COUNT(*) FROM tactical_control WHERE campaign_id=? AND encounter_id=? AND character_id IS NULL",
									cid, current.id()) > 0) {
								write(tx, cid, character, current.id(), owner, "ENCOUNTER", reason);
								bridges.add("tactical_control:" + tx.queryOne(
										"SELECT id FROM tactical_control WHERE campaign_id=? AND encounter_id=? AND character_id=?",
										cid, current.id(), character).orElseThrow().id());
							}
						}
					}
				}
			}
			var result = new LinkedHashMap<String, Object>();
			result.put("controller", owner);
			result.put("scope", lifetime);
			result.put("encounter", Ref.ofNullable(Ref.ENCOUNTER, eid));
			result.put("applies_to", characters == null ? "ALL_COMPANIONS" : "CHARACTERS");
			result.put("characters", targets.stream().map(id -> Ref.of(Ref.CHARACTER, id)).toList());
			result.put("cleared_encounter_overrides", cleared);
			result.put("immediate_encounter_overrides", bridges);
			result.put("tactical_control", summary(tx, cid));
			result.put("note", "The primary character stays player-controlled. This changes tactical decisions only.");
			result.put("meta", Harness.meta(campaign, null));
			return result;
		});
	}

	private static void write(
		Tx tx, long cid, Long character, Long encounter, String controller, String scope, String reason) {
		Row existing = tx.queryOne(
				"SELECT * FROM tactical_control WHERE campaign_id=? AND character_id IS ? AND encounter_id IS ?", cid,
				character, encounter).orElse(null);
		var columns = new LinkedHashMap<String, Object>();
		columns.put("controller", controller);
		columns.put("scope", scope);
		columns.put("reason", reason);
		if (existing == null) {
			columns.put("campaign_id", cid);
			columns.put("character_id", character);
			columns.put("encounter_id", encounter);
			tx.insert("tactical_control", columns);
		} else {
			columns.put("revision", existing.lng("revision") + 1);
			tx.update("tactical_control", existing.id(), columns);
		}
	}

	public static boolean playerControlled(Tx tx, long campaignId, long characterId) {
		return "PLAYER".equals(assignment(tx, campaignId, characterId).get("controller"));
	}

	/** Encounter-character > encounter-all > persistent-character > persistent-all > DM. */
	public static Map<String, Object> assignment(Tx tx, long cid, long characterId) {
		Row character = CharacterService.character(tx, cid, Ref.of(Ref.CHARACTER, characterId));
		boolean primary = primary(tx, cid, characterId);
		String membership = membership(tx, cid, characterId);
		Row encounter = encounterFor(tx, cid, character);
		Long eid = encounter == null ? null : encounter.id();
		Row policy = null;
		if (!primary && membership != null && "ACTIVE".equals(character.str("lifecycle"))) {
			if (eid != null)
				policy = findPolicy(tx, cid, characterId, eid);
			if (policy == null)
				policy = findPolicy(tx, cid, characterId, null);
		}
		var result = new LinkedHashMap<String, Object>();
		result.put("character", Ref.of(Ref.CHARACTER, characterId));
		result.put("name", character.str("name"));
		result.put("membership", membership);
		result.put("primary_character", primary);
		result.put("controller", primary ? "PLAYER" : policy == null ? "DM" : policy.str("controller"));
		result.put("source", primary ? "PRIMARY_CHARACTER"
				: membership == null ? "NON_PARTY" : policy == null ? "DEFAULT" : "ASSIGNMENT");
		result.put("scope", primary ? "PRIMARY_CHARACTER" : policy == null ? "DM_DEFAULT" : policy.str("scope"));
		result.put("encounter", Ref.ofNullable(Ref.ENCOUNTER, eid));
		result.put("policy", policy == null ? null : "tactical_control:" + policy.id());
		result.put("applies_to",
				policy == null ? "DEFAULT" : policy.isNull("character_id") ? "ALL_COMPANIONS" : "CHARACTER");
		if (policy != null) {
			result.put("reason", policy.str("reason"));
			result.put("revision", policy.lng("revision"));
		}
		return result;
	}

	private static Row findPolicy(Tx tx, long cid, long character, Long encounter) {
		return tx.queryOne(
				"SELECT * FROM tactical_control WHERE campaign_id=? AND encounter_id IS ? AND (character_id=? OR character_id IS NULL) ORDER BY CASE WHEN character_id IS NULL THEN 1 ELSE 0 END,id DESC LIMIT 1",
				cid, encounter, character).orElse(null);
	}

	public static Map<String, Object> summary(Tx tx, long cid) {
		Harness.campaign(tx, Ref.of(Ref.CAMPAIGN, cid));
		List<Row> members = tx.query(
				"SELECT DISTINCT c.* FROM character c LEFT JOIN party_membership p ON p.campaign_id=c.campaign_id AND p.character_id=c.id LEFT JOIN player_control_assignment pc ON pc.campaign_id=c.campaign_id AND pc.character_id=c.id WHERE c.campaign_id=? AND c.lifecycle='ACTIVE' AND (p.state IN ('ACTIVE','GUEST','SEPARATED') OR pc.active=1) ORDER BY c.id",
				cid);
		var policies = new ArrayList<Map<String, Object>>();
		for (Row policy : tx.query("SELECT * FROM tactical_control WHERE campaign_id=? ORDER BY id", cid)) {
			if (!policy.isNull("encounter_id")
					&& tx.find("encounter", policy.lng("encounter_id")).filter(e -> live(e, cid)).isEmpty())
				continue;
			var p = new LinkedHashMap<String, Object>();
			p.put("policy", "tactical_control:" + policy.id());
			p.put("character", Ref.ofNullable(Ref.CHARACTER, policy.lng("character_id")));
			p.put("encounter", Ref.ofNullable(Ref.ENCOUNTER, policy.lng("encounter_id")));
			p.put("controller", policy.str("controller"));
			p.put("scope", policy.str("scope"));
			p.put("reason", policy.str("reason"));
			p.put("revision", policy.lng("revision"));
			p.put("applies_to", policy.isNull("character_id") ? "ALL_COMPANIONS" : "CHARACTER");
			policies.add(p);
		}
		var result = new LinkedHashMap<String, Object>();
		result.put("default_companion_controller", "DM");
		result.put("primary_characters", members.stream().filter(c -> primary(tx, cid, c.id()))
				.map(c -> Ref.of(Ref.CHARACTER, c.id())).toList());
		result.put("members", members.stream().map(c -> assignment(tx, cid, c.id())).toList());
		result.put("policies", policies);
		return result;
	}

	/** Deletions are journaled so restoring a pre-completion checkpoint restores the old policy. */
	public static void endEncounter(Tx tx, long cid, long encounterId) {
		Row encounter = tx.get("encounter", encounterId);
		if (encounter.lng("campaign_id") != cid)
			throw RpgException.invalidArgument("Encounter belongs to another campaign.");
		for (Row policy : tx.query("SELECT id FROM tactical_control WHERE campaign_id=? AND encounter_id=?", cid,
				encounterId))
			tx.delete("tactical_control", policy.id());
	}

	private static boolean primary(Tx tx, long cid, long character) {
		return tx.count(
				"SELECT COUNT(*) FROM player_control_assignment WHERE campaign_id=? AND character_id=? AND active=1",
				cid, character) > 0;
	}

	private static String membership(Tx tx, long cid, long character) {
		return tx.queryOne(
				"SELECT state FROM party_membership WHERE campaign_id=? AND character_id=? AND state IN ('ACTIVE','GUEST','SEPARATED') ORDER BY id DESC LIMIT 1",
				cid, character).map(p -> p.str("state")).orElse(null);
	}

	private static Row activeEncounter(Tx tx, long cid) {
		return tx.queryOne(
				"SELECT * FROM encounter WHERE campaign_id=? AND status IN ('RUNNING','WAITING_CHOICE') ORDER BY id DESC LIMIT 1",
				cid).orElse(null);
	}

	private static Row encounterFor(Tx tx, long cid, Row character) {
		Row encounter = character.isNull("encounter_id") ? null
				: tx.find("encounter", character.lng("encounter_id")).filter(e -> live(e, cid)).orElse(null);
		return encounter == null ? activeEncounter(tx, cid) : encounter;
	}

	private static boolean live(Row encounter, long cid) {
		return encounter.lng("campaign_id") == cid
				&& List.of("RUNNING", "WAITING_CHOICE").contains(encounter.str("status"));
	}

	private static Row requireLiveEncounter(Tx tx, long cid, String reference) {
		Row encounter = tx.get("encounter", Ref.id(reference, Ref.ENCOUNTER));
		if (encounter.lng("campaign_id") != cid)
			throw RpgException.invalidArgument("Encounter belongs to another campaign.");
		if (!live(encounter, cid))
			throw RpgException.notAllowed("Encounter control must name a running encounter.");
		return encounter;
	}
}
