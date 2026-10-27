/* Copyright (C) 2026 Marcus Hirt. SPDX-License-Identifier: BSD-3-Clause */
package se.hirt.mcp.rpg;

import org.junit.jupiter.api.Test;
import se.hirt.mcp.rpg.character.RuntimeService;
import se.hirt.mcp.rpg.dice.ScriptedRollService;
import se.hirt.mcp.rpg.persistence.Database;
import se.hirt.mcp.rpg.persistence.Row;
import se.hirt.mcp.rpg.protocol.Json;
import se.hirt.mcp.rpg.protocol.Ref;
import se.hirt.mcp.rpg.rules.Effects;
import se.hirt.mcp.rpg.session.GameTime;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static se.hirt.mcp.rpg.TestCampaigns.*;

class Run8ConditionTest {

	@Test
	void dyingOverrideKeepsProneAndIndependentConditionsAcrossReplayRestartAndCheckpoint() throws Exception {
		var file = tempDb("run8-dying-override-prone");
		String campaign, checkpoint, operation = op();
		Map<String, Object> recovered;
		Row prone, poisoned;
		try (Engine e = engine(file)) {
			campaign = ready(e);
			add(e, campaign, "POISONED", "An independently adjudicated poison.", "1 hour");
			poisoned = condition(e, "POISONED");
			int hp = e.db().read(tx -> tx.get("character", 1).intOr("current_hp", 0));
			e.runtime().applyRuntimeChange(op(), campaign, "character:1",
					map("kind", "DAMAGE", "amount", hp, "reason", "A resolved wound causes Dying."));
			prone = condition(e, "PRONE");
			assertNotNull(condition(e, "UNCONSCIOUS"));
			checkpoint = e.checkpoints().create(op(), campaign, "Before the explicit recovery.").get("checkpoint")
					.toString();
			long seconds = now(e, campaign), rolls = rolls(e);
			dice(e).queue(4);
			recovered = recover(e, campaign, operation, 3);
			assertEquals("ALIVE", e.db().read(tx -> tx.get("character", 1).str("life_state")));
			assertEquals(3, e.db().read(tx -> tx.get("character", 1).intOr("current_hp", 0)).intValue());
			assertNull(condition(e, "UNCONSCIOUS"));
			assertEquals(prone.asMap(), condition(e, "PRONE").asMap());
			assertEquals(poisoned.asMap(), condition(e, "POISONED").asMap());
			assertNull(e.db().read(tx -> tx.get("character", 1).get("death_saves_json")));
			assertTrue(asMap(recovered.get("after")).get("condition_note").toString().contains("Prone"));
			assertEquals(seconds, now(e, campaign));
			assertEquals(rolls, rolls(e));
			assertEquals(4, e.roller().roll("1d4").total(), "The override consumes no queued dice.");
			assertTrue(dice(e).exhausted());
			var saved = e.db().snapshot(Ref.id(campaign, Ref.CAMPAIGN));
			var replayed = new LinkedHashMap<>(recover(e, campaign, operation, 3));
			assertEquals(true, replayed.remove("replayed"));
			assertEquals(Json.hash(recovered), Json.hash(replayed));
			assertEquals(saved, e.db().snapshot(Ref.id(campaign, Ref.CAMPAIGN)));
		}
		try (Engine e = engine(file)) {
			assertEquals(prone.asMap(), condition(e, "PRONE").asMap());
			assertEquals(poisoned.asMap(), condition(e, "POISONED").asMap());
			assertNull(condition(e, "UNCONSCIOUS"));
			e.checkpoints().restore(op(), campaign, checkpoint, "Undo only the recovery.");
			assertEquals("DYING", e.db().read(tx -> tx.get("character", 1).str("life_state")));
			assertNotNull(condition(e, "UNCONSCIOUS"));
			assertEquals(prone.asMap(), condition(e, "PRONE").asMap());
			assertEquals(poisoned.asMap(), condition(e, "POISONED").asMap());
		}
	}

	@Test
	void deadOverrideRetainsExistingProneAndIndependentConditionsWithoutChangingFiatExhaustionReset() throws Exception {
		try (Engine e = engine(tempDb("run8-dead-override-prone"))) {
			String campaign = ready(e);
			add(e, campaign, "PRONE", "The actor was shoved down independently.", "1 hour");
			add(e, campaign, "RESTRAINED", "Bindings remain after the revival.", null);
			add(e, campaign, "EXHAUSTION", "A separately resolved forced march.", null);
			Row prone = condition(e, "PRONE"), restrained = condition(e, "RESTRAINED");
			e.rest().override(op(), campaign, "SET_LIFE_STATE", "character:1", map("life_state", "DEAD"),
					"An explicit fictional death preserves the existing physical state.", null);
			assertEquals("DEAD", e.db().read(tx -> tx.get("character", 1).str("life_state")));
			var result = recover(e, campaign, op(), 2);
			assertEquals("ALIVE", e.db().read(tx -> tx.get("character", 1).str("life_state")));
			assertEquals(prone.asMap(), condition(e, "PRONE").asMap());
			assertEquals(restrained.asMap(), condition(e, "RESTRAINED").asMap());
			assertEquals(0, e.db().read(tx -> tx.get("character", 1).intOr("exhaustion", 0)).intValue(),
					"The explicit override keeps its established Exhaustion reset contract.");
			assertEquals(true, result.get("audited"));
		}
	}

	@Test
	void aliveProneOverrideLeavesIndependentConditionIdentitiesAndDurationsUnchanged() throws Exception {
		try (Engine e = engine(tempDb("run8-alive-override-independent"))) {
			String campaign = ready(e);
			for (String condition : List.of("PRONE", "POISONED", "STUNNED", "INCAPACITATED"))
				add(e, campaign, condition, "A separately authored " + condition + " effect.", "1 minute");
			var before = effects(e);
			int hp = e.db().read(tx -> tx.get("character", 1).intOr("current_hp", 0));
			recover(e, campaign, op(), hp);
			assertEquals(before, effects(e), "ALIVE is not an instruction to stand up or clear unrelated conditions.");
			assertEquals("ALIVE", e.db().read(tx -> tx.get("character", 1).str("life_state")));
		}
	}

	@Test
	void positiveHitPointMagicalUnconsciousSurvivesWhileRecoveryLinkedKnockoutEndsOnRestoredHitPoints()
			throws Exception {
		try (Engine e = engine(tempDb("run8-alive-override-unconscious"))) {
			String campaign = ready(e);
			add(e, campaign, "UNCONSCIOUS", "A magical sleep with its own ending condition.", "1 minute");
			Row sleep = condition(e, "UNCONSCIOUS"), prone = condition(e, "PRONE");
			int hp = e.db().read(tx -> tx.get("character", 1).intOr("current_hp", 0));
			recover(e, campaign, op(), hp);
			assertEquals(sleep.asMap(), condition(e, "UNCONSCIOUS").asMap());
			assertEquals(prone.asMap(), condition(e, "PRONE").asMap());
			e.runtime().applyRuntimeChange(op(), campaign, "character:1", map("kind", "REMOVE_CONDITION", "condition",
					"UNCONSCIOUS", "reason", "The magical sleep is resolved separately."));
			e.rest().override(op(), campaign, "ADJUST_HP", "character:1", map("set_to", 1),
					"Set up an already living knocked-out creature.", null);
			add(e, campaign, "UNCONSCIOUS", "A separately resolved nonlethal knockout.", map("until", "SHORT_REST"));
			Row knockout = condition(e, "UNCONSCIOUS");
			recover(e, campaign, op(), 1);
			assertEquals(knockout.asMap(), condition(e, "UNCONSCIOUS").asMap(),
					"Merely restating ALIVE without regaining HP does not wake an existing knockout.");
			recover(e, campaign, op(), 2);
			assertNull(condition(e, "UNCONSCIOUS"));
			assertEquals(prone.asMap(), condition(e, "PRONE").asMap());
		}
	}

	@Test
	void overridingDyingEndsOnlyItsGeneratedUnconsciousAndRetainsIndependentSleepAndTemporaryMaximumReset()
			throws Exception {
		try (Engine e = engine(tempDb("run8-dying-independent-sleep"))) {
			String campaign = ready(e);
			long cid = Ref.id(campaign, Ref.CAMPAIGN);
			int max = e.db().read(tx -> tx.get("character", 1).intOr("max_hp", 0));
			e.runtime().applyRuntimeChange(op(), campaign, "character:1",
					map("kind", "ADJUST_MAX_HP", "amount", 5, "reason", "A temporary increase to the maximum."));
			int hp = e.db().read(tx -> tx.get("character", 1).intOr("current_hp", 0));
			e.runtime().applyRuntimeChange(op(), campaign, "character:1",
					map("kind", "DAMAGE", "amount", hp, "reason", "A resolved wound causes Dying."));
			e.db().mutate(Database.Mutation.of("run8_independent_sleep", cid, op(), "GM", Map.of()), tx -> {
				Effects.add(tx, cid, 1, null, "srd5e:spell/sleep", "An independent magical sleep also remains active.",
						RuntimeService.UNCONSCIOUS_REF, null,
						map("kind", "MINUTES", "expires_seq", GameTime.currentSeq(tx, cid) + 1), null,
						"run8-independent-sleep", "MECHANICAL_CONSEQUENCE");
				return Map.of();
			});
			Row independent = e.db()
					.read(tx -> tx.queryOne("SELECT * FROM active_effect WHERE character_id=1 AND source_description=?",
							"An independent magical sleep also remains active.").orElseThrow());
			Row prone = condition(e, "PRONE");
			recover(e, campaign, op(), max + 5);
			assertEquals(independent.asMap(), condition(e, "UNCONSCIOUS").asMap());
			assertEquals(prone.asMap(), condition(e, "PRONE").asMap());
			assertEquals(max, e.db().read(tx -> tx.get("character", 1).intOr("max_hp", 0)).intValue());
			assertEquals(max, e.db().read(tx -> tx.get("character", 1).intOr("current_hp", 0)).intValue(),
					"The existing non-condition maximum-HP reset and clamping remain intact.");
		}
	}

	private static String ready(Engine e) {
		String campaign = committedCampaign(e);
		e.sessions().bootstrap(op(), campaign, null);
		return campaign;
	}

	private static void add(Engine e, String campaign, String condition, String reason, Object duration) {
		e.runtime().applyRuntimeChange(op(), campaign, "character:1",
				map("kind", "ADD_CONDITION", "condition", condition, "reason", reason, "duration", duration));
	}

	private static Map<String, Object> recover(Engine e, String campaign, String operation, int hp) {
		return e.rest().override(operation, campaign, "SET_LIFE_STATE", "character:1",
				map("life_state", "ALIVE", "hp", hp), "An explicitly audited life-state recovery.", null);
	}

	private static Row condition(Engine e, String condition) {
		return e.db()
				.read(tx -> tx.queryOne(
						"SELECT * FROM active_effect WHERE character_id=1 AND condition_ref=? ORDER BY id LIMIT 1",
						RuntimeService.conditionRef(condition)).orElse(null));
	}

	private static List<Map<String, Object>> effects(Engine e) {
		return e.db().read(tx -> tx.query("SELECT * FROM active_effect WHERE character_id=1 ORDER BY id").stream()
				.map(Row::asMap).toList());
	}

	private static long now(Engine e, String campaign) {
		return e.db().read(tx -> GameTime.currentSeconds(tx, Ref.id(campaign, Ref.CAMPAIGN)));
	}

	private static long rolls(Engine e) {
		return e.db().read(tx -> tx.count("SELECT COUNT(*) FROM roll"));
	}

	private static ScriptedRollService dice(Engine e) {
		return (ScriptedRollService) e.roller();
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> asMap(Object value) {
		return (Map<String, Object>) value;
	}
}
