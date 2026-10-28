/* Copyright (C) 2026 Marcus Hirt. SPDX-License-Identifier: BSD-3-Clause */
package se.hirt.mcp.rpg.rules;

import se.hirt.mcp.rpg.character.CharacterService;
import se.hirt.mcp.rpg.character.RuntimeService;
import se.hirt.mcp.rpg.dice.RollService;
import se.hirt.mcp.rpg.ledger.LedgerService;
import se.hirt.mcp.rpg.persistence.Row;
import se.hirt.mcp.rpg.persistence.Tx;
import se.hirt.mcp.rpg.protocol.Ref;
import se.hirt.mcp.rpg.session.GameTime;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** SRD 5.2.1: an unhealed Stable creature regains one Hit Point after a saved 1d4-hour delay. */
public final class StableRecovery {
	private StableRecovery() {
	}

	/**
	 * Called at the transition to Stable; retries and repeated stabilization retain the original
	 * roll.
	 */
	public static Map<String, Object> schedule(Tx tx, RollService roller, Row character, Map<String, Object> saves) {
		var result = new LinkedHashMap<>(saves);
		result.remove("next_save_seconds");
		Map<String, Object> previous = character.map("death_saves_json");
		if (Boolean.TRUE.equals(previous.get("stable")) && previous.get("recovery_due_seconds") instanceof Number) {
			for (String key : List.of("recovery_due_seconds", "recovery_started_seconds", "recovery_hours",
					"recovery_roll"))
				if (previous.containsKey(key))
					result.put(key, previous.get(key));
			return result;
		}
		var roll = roller.roll("1d4");
		long cid = character.lng("campaign_id"), start = GameTime.currentSeconds(tx, cid);
		long rollId = CharacterService.recordRoll(tx, cid, "stable recovery " + Ref.of(Ref.CHARACTER, character.id()),
				roll);
		var evidence = new LinkedHashMap<>(roll.toMap());
		evidence.put("roll_ref", Ref.of(Ref.ROLL, rollId));
		result.put("recovery_started_seconds", start);
		result.put("recovery_hours", roll.total());
		result.put("recovery_due_seconds", Math.addExact(start, Math.multiplyExact(roll.total(), 3600L)));
		result.put("recovery_roll", evidence);
		return result;
	}

	public static Long nextDue(Tx tx, long cid, long target) {
		Long next = null;
		for (Row character : candidates(tx, cid)) {
			Map<String, Object> saves = character.map("death_saves_json");
			if (Boolean.TRUE.equals(saves.get("stable")) && saves.get("recovery_due_seconds") instanceof Number n
					&& n.longValue() <= target && (next == null || n.longValue() < next))
				next = n.longValue();
		}
		return next;
	}

	public static List<Map<String, Object>> recoverDue(Tx tx, long cid, long now) {
		var results = new ArrayList<Map<String, Object>>();
		for (Row character : candidates(tx, cid)) {
			Map<String, Object> saves = character.map("death_saves_json");
			if (!Boolean.TRUE.equals(saves.get("stable")) || !(saves.get("recovery_due_seconds") instanceof Number due)
					|| due.longValue() > now)
				continue;
			var result = new LinkedHashMap<>(
					RuntimeService.heal(tx, character, 1, "natural recovery after stabilization"));
			result.put("kind", "STABLE_RECOVERY");
			result.put("character", Ref.of(Ref.CHARACTER, character.id()));
			result.put("at_seconds", now);
			boolean partyKnown = tx.queryOne(
					"SELECT id FROM party_membership WHERE campaign_id=? AND character_id=? AND state IN ('ACTIVE','GUEST','SEPARATED') LIMIT 1",
					cid, character.id()).isPresent()
					|| tx.queryOne(
							"SELECT id FROM player_control_assignment WHERE campaign_id=? AND character_id=? AND active=1 LIMIT 1",
							cid, character.id()).isPresent();
			String visibility = partyKnown ? "PARTY_KNOWN" : "GM_ONLY";
			result.put("visibility", visibility);
			results.add(result);
			LedgerService.append(tx, cid,
					new LedgerService.EventSpec("STABLE_RECOVERY",
							character.str("name")
									+ " regains 1 Hit Point after recovering from stable unconsciousness.",
							List.of(character.id()), "MINOR", visibility, "MECHANICAL_CONSEQUENCE", null,
							character.lng("location_id"), null, Map.of("at_seconds", now)));
		}
		return results;
	}

	private static List<Row> candidates(Tx tx, long cid) {
		return tx.query(
				"SELECT * FROM character WHERE campaign_id=? AND lifecycle='ACTIVE' AND life_state='DYING' AND current_hp=0 AND death_saves_json IS NOT NULL ORDER BY id",
				cid);
	}
}
