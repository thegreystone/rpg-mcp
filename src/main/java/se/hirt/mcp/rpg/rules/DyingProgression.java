/* Copyright (C) 2026 Marcus Hirt. SPDX-License-Identifier: BSD-3-Clause */
package se.hirt.mcp.rpg.rules;

import se.hirt.mcp.rpg.character.CharacterService;
import se.hirt.mcp.rpg.character.RuntimeService;
import se.hirt.mcp.rpg.dice.RollService;
import se.hirt.mcp.rpg.ledger.LedgerService;
import se.hirt.mcp.rpg.persistence.Row;
import se.hirt.mcp.rpg.persistence.Tx;
import se.hirt.mcp.rpg.protocol.Json;
import se.hirt.mcp.rpg.protocol.Ref;
import se.hirt.mcp.rpg.session.GameTime;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** The same death saves in combat and on the six-second exploration clock. */
public final class DyingProgression {

	private DyingProgression() {
	}

	public static Map<String, Object> scheduleNext(Tx tx, long cid, Map<String, Object> saves) {
		var next = new LinkedHashMap<>(saves);
		for (String key : List.of("recovery_due_seconds", "recovery_started_seconds", "recovery_hours",
				"recovery_roll"))
			next.remove(key);
		next.put("next_save_seconds", Math.addExact(GameTime.currentSeconds(tx, cid), 6));
		return next;
	}

	/**
	 * A legacy row starts at the next mutation, rather than rolling an invented historical backlog.
	 */
	public static void initializeDeadlines(Tx tx, long cid, long now) {
		for (Row c : outsideCombat(tx, cid)) {
			var saves = c.map("death_saves_json");
			if (!(saves.get("next_save_seconds") instanceof Number due) || due.longValue() < now)
				tx.update("character", c.id(), Map.of("death_saves_json", Json.write(scheduleNext(tx, cid, saves)),
						"revision", c.lng("revision") + 1));
		}
	}

	public static Long nextDue(Tx tx, long cid, long target) {
		Long next = null;
		for (Row c : outsideCombat(tx, cid))
			if (c.map("death_saves_json").get("next_save_seconds") instanceof Number due && due.longValue() <= target
					&& (next == null || due.longValue() < next))
				next = due.longValue();
		return next;
	}

	public static List<Map<String, Object>> resolveDue(Tx tx, long cid, long now) {
		var events = new ArrayList<Map<String, Object>>();
		Map<String, Object> controlledDeath = null;
		Row controlledCharacter = null;
		for (Row c : outsideCombat(tx, cid)) {
			if (!(c.map("death_saves_json").get("next_save_seconds") instanceof Number due) || due.longValue() > now)
				continue;
			boolean known = RuntimeService.isPartyMember(tx, cid, c.id()) || controlled(tx, cid, c.id());
			String visibility = known ? "PARTY_KNOWN" : "GM_ONLY";
			var event = resolveSave(tx, tx.rollService(), cid, c, visibility, Map.of("source", "WORLD_CLOCK"));
			event.put("visibility", visibility);
			event.put("interrupting", known);
			LedgerService.append(tx, cid,
					new LedgerService.EventSpec("DEATH_SAVE",
							c.str("name") + " death saving throw: " + event.get("death_save") + " → "
									+ event.get("outcome") + ".",
							List.of(c.id()), known ? "CRITICAL" : "MINOR", visibility, "MECHANICAL_CONSEQUENCE", null,
							c.lng("location_id"), null, event));
			if (Boolean.TRUE.equals(event.get("died")) && controlled(tx, cid, c.id())) {
				controlledDeath = event;
				controlledCharacter = c;
			}
			events.add(event);
		}
		if (controlledDeath != null)
			controlledDeath.putAll(continueAfterClockDeath(tx, cid, controlledCharacter));
		return events;
	}

	/** Resolve one throw; callers supply event-time visibility before membership can end. */
	public static Map<String, Object> resolveSave(
		Tx tx, RollService roller, long cid, Row c, String visibility, Map<String, Object> context) {
		int penalty = Exhaustion.penalty(tx, c.id());
		var roll = roller.roll("1d20" + (penalty == 0 ? "" : "-" + penalty));
		long rollId = CharacterService.recordRoll(tx, cid, "death saving throw " + Ref.of(Ref.CHARACTER, c.id()), roll);
		var after = Combat.deathSave(roll.dice().getFirst(), roll.total(), c.map("death_saves_json"));
		String outcome = String.valueOf(after.get("outcome"));
		if ("STABLE".equals(outcome))
			after = StableRecovery.schedule(tx, roller, c, after);
		else if (!Set.of("REGAIN_1_HP", "DEAD").contains(outcome))
			after = scheduleNext(tx, cid, after);
		if ("REGAIN_1_HP".equals(outcome)) {
			RuntimeService.heal(tx, c, 1, "natural 20 on a death saving throw");
		} else if ("DEAD".equals(outcome)) {
			Effects.removeAll(tx, c.id());
			var columns = new LinkedHashMap<String, Object>();
			columns.put("life_state", "DEAD");
			columns.put("death_saves_json", null);
			columns.put("revision", tx.get("character", c.id()).lng("revision") + 1);
			tx.update("character", c.id(), columns);
			RuntimeService.endMemberships(tx, cid, c.id(), "DEAD");
			RuntimeService.markDefeatedInRunningEncounter(tx, c.id());
			var details = new LinkedHashMap<String, Object>(context);
			details.put("cause", "FAILED_DEATH_SAVES");
			LedgerService.append(tx, cid,
					new LedgerService.EventSpec("CHARACTER_DIED",
							c.str("name") + " died of their wounds (three failed death saves).", List.of(c.id()),
							"PARTY_KNOWN".equals(visibility) ? "CRITICAL" : "NOTABLE", visibility,
							"MECHANICAL_CONSEQUENCE", null, c.lng("location_id"), null, details));
		} else {
			tx.update("character", c.id(),
					Map.of("death_saves_json", Json.write(after), "revision", c.lng("revision") + 1));
		}
		var event = new LinkedHashMap<String, Object>();
		event.put("kind", "DEATH_SAVE");
		event.put("character", Ref.of(Ref.CHARACTER, c.id()));
		event.put("name", c.str("name"));
		event.put("death_save", roll.total());
		event.put("natural", roll.dice().getFirst());
		event.put("outcome", outcome);
		event.put("successes", after.get("successes"));
		event.put("failures", after.get("failures"));
		event.put("stable", after.get("stable"));
		event.put("life_state", tx.get("character", c.id()).str("life_state"));
		event.put("died", "DEAD".equals(outcome));
		event.put("at_seconds", GameTime.currentSeconds(tx, cid));
		var evidence = new LinkedHashMap<String, Object>(roll.toMap());
		evidence.put("roll_ref", Ref.of(Ref.ROLL, rollId));
		event.put("roll", evidence);
		return event;
	}

	public static void resumeAfterEncounter(Tx tx, long cid, long encounterId) {
		for (Row c : tx.query("SELECT c.* FROM character c JOIN encounter_participant p ON p.character_id=c.id "
				+ "WHERE p.encounter_id=? AND c.campaign_id=? AND c.life_state='DYING' AND c.current_hp=0 ORDER BY c.id",
				encounterId, cid))
			if (!Boolean.TRUE.equals(c.map("death_saves_json").get("stable")))
				tx.update("character", c.id(),
						Map.of("death_saves_json", Json.write(scheduleNext(tx, cid, c.map("death_saves_json"))),
								"revision", c.lng("revision") + 1));
	}

	private static List<Row> outsideCombat(Tx tx, long cid) {
		return tx.query("SELECT c.* FROM character c WHERE c.campaign_id=? AND c.lifecycle='ACTIVE' "
				+ "AND c.life_state='DYING' AND c.current_hp=0 AND COALESCE(json_extract(c.death_saves_json,'$.stable'),0)=0 "
				+ "AND NOT EXISTS(SELECT 1 FROM encounter_participant p JOIN encounter e ON e.id=p.encounter_id "
				+ "WHERE p.character_id=c.id AND p.status='ACTIVE' AND e.status IN ('RUNNING','WAITING_CHOICE')) ORDER BY c.id",
				cid);
	}

	private static boolean controlled(Tx tx, long cid, long characterId) {
		return tx.count(
				"SELECT COUNT(*) FROM player_control_assignment WHERE campaign_id=? AND character_id=? AND active=1",
				cid, characterId) > 0;
	}

	private static Map<String, Object> continueAfterClockDeath(Tx tx, long cid, Row character) {
		if (tx.count("SELECT COUNT(*) FROM encounter WHERE campaign_id=? AND status IN ('RUNNING','WAITING_CHOICE')",
				cid) > 0)
			return Map.of("player_character_status", "DEAD", "encounter_end_required", true);
		Row campaign = tx.get("campaign", cid);
		boolean survivors = tx.count("SELECT COUNT(*) FROM party_membership m JOIN character c ON c.id=m.character_id "
				+ "WHERE m.campaign_id=? AND m.state IN ('ACTIVE','SEPARATED') AND c.life_state<>'DEAD' AND c.lifecycle='ACTIVE'",
				cid) > 0;
		String state = "CHECKPOINT_DECISION", status = campaign.str("status");
		if ("IRONMAN".equals(campaign.str("continuation_policy"))) {
			state = survivors ? "PLAYER_CHARACTER_TRANSFER" : "CAMPAIGN_FAILED";
			if (!survivors) {
				status = "FAILED";
				LedgerService.append(tx, cid,
						new LedgerService.EventSpec("CAMPAIGN_FAILED", "The party was destroyed.", List.of(),
								"CRITICAL", "PARTY_KNOWN", "MECHANICAL_CONSEQUENCE", null, character.lng("location_id"),
								null, null));
			}
		}
		tx.update("campaign", cid,
				Map.of("harness_state", state, "status", status, "revision", campaign.lng("revision") + 1));
		return Map.of("player_character_status", "DEAD", "harness_state", state, "next_step",
				"Read get_continuation_options and choose the campaign's continuation.");
	}
}
