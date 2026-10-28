/* Copyright (C) 2026 Marcus Hirt. SPDX-License-Identifier: BSD-3-Clause */
package se.hirt.mcp.rpg;

import org.junit.jupiter.api.Test;
import se.hirt.mcp.rpg.character.RuntimeService;
import se.hirt.mcp.rpg.dice.ScriptedRollService;
import se.hirt.mcp.rpg.magic.SpellService;
import se.hirt.mcp.rpg.maps.SceneService;
import se.hirt.mcp.rpg.persistence.Database;
import se.hirt.mcp.rpg.persistence.Row;
import se.hirt.mcp.rpg.protocol.Json;
import se.hirt.mcp.rpg.protocol.Ref;
import se.hirt.mcp.rpg.protocol.RpgException;
import se.hirt.mcp.rpg.rules.Effects;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static se.hirt.mcp.rpg.TestCampaigns.*;

class Run7ConditionTest {

	@Test
	void fallingUnconsciousImmediatelyAppliesProneAndRecoveryKeepsItsIdentityAndAccurateSource() throws Exception {
		var file = tempDb("run7-unconscious-prone");
		String campaign;
		long prone;
		try (Engine e = engine(file)) {
			campaign = ready(e);
			long hp = e.db().read(tx -> tx.get("character", 1).lng("current_hp"));
			e.runtime().applyRuntimeChange(op(), campaign, "character:1",
					map("kind", "DAMAGE", "amount", hp, "reason", "A resolved fall drops the character to 0 HP."));
			assertEquals("DYING", e.db().read(tx -> tx.get("character", 1).str("life_state")));
			assertTrue(has(e, 1, "UNCONSCIOUS"));
			Row effect = prone(e, 1);
			prone = effect.id();
			assertEquals(RuntimeService.UNCONSCIOUS_PRONE_SOURCE, effect.str("source_description"));
			assertFalse(effect.str("source_description").contains("ended"));
			assertEquals(1L, proneCount(e, 1));
		}
		try (Engine e = engine(file)) {
			assertEquals(prone, prone(e, 1).id());
			var healed = e.runtime().applyRuntimeChange(op(), campaign, "character:1",
					map("kind", "HEAL", "amount", 1, "reason", "A resolved healing effect restores consciousness."));
			assertEquals(true, healed.get("revived"));
			assertFalse(has(e, 1, "UNCONSCIOUS"));
			assertEquals(prone, prone(e, 1).id(),
					"Waking keeps the existing Prone effect instead of replacing its identity.");
			e.runtime().applyRuntimeChange(op(), campaign, "character:1",
					map("kind", "DAMAGE", "amount", 1, "reason", "Another resolved wound causes Unconscious again."));
			assertTrue(has(e, 1, "UNCONSCIOUS"));
			assertEquals(prone, prone(e, 1).id());
			assertEquals(RuntimeService.UNCONSCIOUS_PRONE_SOURCE, prone(e, 1).str("source_description"));
			assertEquals(1L, proneCount(e, 1));
			e.runtime().applyRuntimeChange(op(), campaign, "character:1",
					map("kind", "HEAL", "amount", 1, "reason", "A second recovery."));
			assertEquals(true,
					e.runtime()
							.applyRuntimeChange(op(), campaign, "character:1",
									map("kind", "STAND", "reason", "The conscious character gets up."))
							.get("stood_up"));
			assertEquals(0L, proneCount(e, 1));
		}
	}

	@Test
	void legacyGeneratedProneSourceIsCorrectedWithoutReplacingDurationOrIndependentSources() throws Exception {
		try (Engine e = engine(tempDb("run7-prone-source-compatibility"))) {
			String campaign = ready(e);
			long cid = Ref.id(campaign, Ref.CAMPAIGN);
			e.runtime().applyRuntimeChange(op(), campaign, "character:1",
					map("kind", "ADD_CONDITION", "condition", "PRONE", "duration", "1 minute", "reason",
							"Unconscious ended: the creature remains Prone until it stands up"));
			Row original = prone(e, 1);
			e.db().mutate(Database.Mutation.of("resolved_sleep_effect", cid, op(), "SYSTEM", Map.of()), tx -> {
				Effects.add(tx, cid, 1, null, "srd5e:spell/sleep", "A resolved sleep effect",
						RuntimeService.UNCONSCIOUS_REF, null, null, null, "run7-sleep", "MECHANICAL_CONSEQUENCE");
				return Map.of();
			});
			Row updated = prone(e, 1);
			assertEquals(original.id(), updated.id());
			assertEquals(Json.hash(original.map("duration_json")), Json.hash(updated.map("duration_json")));
			assertEquals(RuntimeService.UNCONSCIOUS_PRONE_SOURCE, updated.str("source_description"));
			assertEquals(1L, proneCount(e, 1));
			String other = e.runtime()
					.materialize(op(), campaign, "Bandit", "Already shoved down", null, null, null, null, false)
					.get("character").toString();
			long oid = Ref.id(other, Ref.CHARACTER);
			e.runtime().applyRuntimeChange(op(), campaign, other, map("kind", "ADD_CONDITION", "condition", "PRONE",
					"reason", "An independent shove knocked this actor down."));
			Row independent = prone(e, oid);
			e.runtime().applyRuntimeChange(op(), campaign, other, map("kind", "ADD_CONDITION", "condition",
					"UNCONSCIOUS", "reason", "A separately resolved sleep effect."));
			assertEquals(independent.asMap(), prone(e, oid).asMap());
			assertEquals(1L, proneCount(e, oid));
		}
	}

	@Test
	void aDeadCasterGetsAnExplicitReasonBeforeAnyFiniteGrantOrRollIsSpent() throws Exception {
		try (Engine e = engine(tempDb("run7-dead-caster"))) {
			String campaign = ready(e), priest = priest(e, campaign);
			long cid = Ref.id(campaign, Ref.CAMPAIGN), pid = Ref.id(priest, Ref.CHARACTER);
			e.runtime().applyRuntimeChange(op(), campaign, priest, map("kind", "DAMAGE", "amount", 100, "reason",
					"The caster died in a separately resolved incident."));
			var before = e.db().snapshot(cid);
			dice(e).queue(4);
			var refusal = assertThrows(RpgException.class,
					() -> e.spells().cast(op(), campaign, priest, "Healing Word", null, List.of("character:1"), null));
			assertTrue(refusal.getMessage().contains("DEAD"));
			assertTrue(refusal.getMessage().contains("0 HP"));
			assertTrue(refusal.getMessage().contains("Restore the caster to life"));
			assertEquals(priest, refusal.details().get("caster"));
			assertEquals("DEAD", refusal.details().get("life_state"));
			assertEquals("EXPLORATION", refusal.details().get("harness_state"));
			assertEquals(before, e.db().snapshot(cid));
			assertThrows(RpgException.class,
					() -> e.db().mutate(Database.Mutation.of("direct_dead_cast_fixture", cid, op(), "SYSTEM", Map.of()),
							tx -> SpellService.castCore(tx, e.rules(), e.roller(), cid, tx.get("character", pid),
									"Healing Word", null, List.of("character:1"), null, null, 0)));
			assertEquals(before, e.db().snapshot(cid));
			assertEquals(4, e.roller().roll("1d4").total(), "Both entry points reject before spending queued dice.");
			assertTrue(dice(e).exhausted());
		}
	}

	@Test
	void incapacitatingAndArchivedCasterRefusalsNameTheirStateAndPreserveAllResources() throws Exception {
		try (Engine e = engine(tempDb("run7-caster-condition-gate"))) {
			String campaign = ready(e), priest = priest(e, campaign);
			long cid = Ref.id(campaign, Ref.CAMPAIGN), pid = Ref.id(priest, Ref.CHARACTER);
			dice(e).queue(4);
			for (String condition : List.of("UNCONSCIOUS", "PARALYZED", "PETRIFIED", "STUNNED", "INCAPACITATED")) {
				e.runtime().applyRuntimeChange(op(), campaign, priest, map("kind", "ADD_CONDITION", "condition",
						condition, "reason", "A resolved incapacitating effect."));
				var before = e.db().snapshot(cid);
				var refusal = assertThrows(RpgException.class, () -> e.spells().cast(op(), campaign, priest,
						"Healing Word", null, List.of("character:1"), null));
				assertTrue(refusal.getMessage().contains(condition));
				assertEquals("ALIVE", refusal.details().get("life_state"));
				assertTrue(((Number) refusal.details().get("current_hp")).intValue() > 0);
				assertTrue(((List<?>) refusal.details().get("incapacitating_conditions")).contains(condition));
				assertEquals(before, e.db().snapshot(cid));
				e.runtime().applyRuntimeChange(op(), campaign, priest,
						map("kind", "REMOVE_CONDITION", "condition", condition, "reason", "The effect is resolved."));
			}
			e.runtime().applyRuntimeChange(op(), campaign, priest, map("kind", "ADD_CONDITION", "condition",
					"RESTRAINED", "reason", "Ropes do not themselves make the caster Incapacitated."));
			e.runtime().applyRuntimeChange(op(), campaign, "character:1",
					map("kind", "DAMAGE", "amount", 1, "reason", "A separately resolved scratch."));
			dice(e).queue(2);
			var legal = e.spells().cast(op(), campaign, priest, "Healing Word", null, List.of("character:1"), null);
			assertEquals(1, ((Number) SceneService.maps(legal.get("targets")).getFirst().get("healed")).intValue());
			assertTrue(dice(e).exhausted(), "Prone and Restrained alone do not prevent this legal verbal spell.");
			e.db().mutate(Database.Mutation.of("archived_caster_fixture", cid, op(), "SYSTEM", Map.of()), tx -> {
				tx.update("character", pid, map("lifecycle", "ARCHIVED"));
				return Map.of();
			});
			var before = e.db().snapshot(cid);
			var archived = assertThrows(RpgException.class,
					() -> e.spells().cast(op(), campaign, priest, "Light", null, List.of(), null));
			assertTrue(archived.getMessage().contains("ARCHIVED"));
			assertEquals("ARCHIVED", archived.details().get("lifecycle"));
			assertEquals(before, e.db().snapshot(cid));
		}
	}

	private static Row prone(Engine e, long id) {
		return e.db()
				.read(tx -> tx.queryOne(
						"SELECT * FROM active_effect WHERE character_id=? AND condition_ref='srd5e:condition/prone'",
						id).orElseThrow());
	}

	private static long proneCount(Engine e, long id) {
		return e.db().read(tx -> tx.count(
				"SELECT COUNT(*) FROM active_effect WHERE character_id=? AND condition_ref='srd5e:condition/prone'",
				id));
	}

	private static boolean has(Engine e, long id, String condition) {
		return e.db().read(
				tx -> RuntimeService.conditions(tx, id).stream().anyMatch(c -> condition.equals(c.get("condition"))));
	}

	private static String priest(Engine e, String campaign) {
		return e.runtime()
				.materialize(op(), campaign, "Priest Acolyte", "Sister Oddny Vane", null, null, null, null, false)
				.get("character").toString();
	}

	private static String ready(Engine e) {
		String c = committedCampaign(e);
		e.sessions().bootstrap(op(), c, null);
		return c;
	}

	private static ScriptedRollService dice(Engine e) {
		return (ScriptedRollService) e.roller();
	}
}
