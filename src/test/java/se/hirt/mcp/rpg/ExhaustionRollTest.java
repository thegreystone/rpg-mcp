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
package se.hirt.mcp.rpg;

import org.junit.jupiter.api.Test;
import se.hirt.mcp.rpg.dice.ScriptedRollService;
import se.hirt.mcp.rpg.persistence.Database;
import se.hirt.mcp.rpg.protocol.ErrorCode;
import se.hirt.mcp.rpg.protocol.Json;
import se.hirt.mcp.rpg.protocol.Ref;
import se.hirt.mcp.rpg.protocol.RpgException;
import se.hirt.mcp.rpg.rules.Combat;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static se.hirt.mcp.rpg.TestCampaigns.*;

/** The 2024 Exhaustion penalty applies once to every D20 Test while preserving natural rolls. */
class ExhaustionRollTest {

	@Test
	void abilitySkillAndSavingThrowsApplyThePenaltyOnce() throws Exception {
		try (Engine engine = engine(tempDb("exhaustion-checks"))) {
			String campaign = committedCampaign(engine);
			engine.sessions().bootstrap(op(), campaign, null);
			String pc = "character:1";
			updateCharacter(engine, campaign, pc,
					map("str_score", 14, "dex_score", 14, "con_score", 14, "exhaustion", 2));
			ScriptedRollService dice = (ScriptedRollService) engine.roller();
			for (String kind : List.of("ABILITY_CHECK", "SKILL_CHECK", "SAVING_THROW")) {
				String ability = kind.equals("ABILITY_CHECK") ? "STR" : kind.equals("SKILL_CHECK") ? "DEX" : "CON";
				dice.queue(12);
				Map<String, Object> check = engine.checks().resolveCheck(op(), campaign, pc, kind, ability,
						kind.equals("SKILL_CHECK") ? "Stealth" : null, 13, null, "exhaustion regression");
				int expectedModifier = kind.equals("ABILITY_CHECK") ? -2 : 0;
				assertEquals(expectedModifier, check.get("modifier"), kind);
				assertEquals(expectedModifier, m(check.get("roll")).get("modifier"), kind);
				assertEquals(12, check.get("natural"), kind);
				assertEquals(12 + expectedModifier, check.get("total"), kind);
				assertEquals(Boolean.FALSE, check.get("success"), kind);
				assertEquals("NONE", check.get("advantage"), "2024 exhaustion uses a flat penalty");
			}
			assertTrue(dice.exhausted());
		}
	}

	@Test
	void initiativeHideAndWeaponAttacksUseExhaustionWithoutReducingDamage() throws Exception {
		try (Fixture f = fixture(2, 30)) {
			Map<String, Object> initiative = list(f.started.get("initiative_rolls")).get(0);
			assertEquals(-2, m(initiative.get("roll")).get("modifier"));
			assertEquals(18, initiative.get("initiative"));

			f.dice.queue(14);
			Map<String, Object> hidden = f.engine.encounters().perform(op(), f.campaign, f.encounter, f.pc,
					map("kind", "HIDE"), true);
			assertEquals(0, m(hidden.get("roll")).get("modifier"));
			assertEquals(14, m(hidden.get("roll")).get("total"));
			assertEquals(Boolean.FALSE, hidden.get("hidden"), "the exhausted Stealth check misses DC 15");
			f.engine.encounters().perform(op(), f.campaign, f.encounter, f.ogre, map("kind", "END_TURN"), true);

			f.dice.queue(19, 3);
			Map<String, Object> attack = f.engine.encounters().perform(op(), f.campaign, f.encounter, f.pc,
					map("kind", "ATTACK", "weapon", "Dagger", "target", f.ogre), false);
			assertEquals(0, m(attack.get("attack_roll")).get("modifier"));
			assertEquals(19, attack.get("attack_total"));
			assertEquals(Boolean.TRUE, attack.get("hit"));
			Map<String, Object> damage = m(list(attack.get("breakdown")).get(0).get("roll"));
			assertEquals(2, damage.get("modifier"), "exhaustion affects the attack, not weapon damage");
			assertEquals(5, damage.get("total"));
			assertTrue(f.dice.exhausted());
		}
	}

	@Test
	void zeroSpeedFromExhaustionDeniesMovingAndDashingWithoutAdvancingTheTurn() throws Exception {
		try (Fixture f = fixture(5, 25)) {
			assertEquals(0, f.engine.characters().characterSheet(f.campaign, f.pc, "PLAY").get("speed"));
			for (String kind : List.of("MOVE", "DASH")) {
				RpgException denied = assertThrows(RpgException.class, () -> f.engine.encounters().perform(op(),
						f.campaign, f.encounter, f.pc, map("kind", kind, "zone", "far"), true));
				assertEquals(ErrorCode.OPERATION_NOT_ALLOWED, denied.code());
				assertTrue(denied.getMessage().contains("Speed 0"), denied.getMessage());
			}
			Map<String, Object> state = f.engine.encounters().encounterState(f.campaign, f.encounter, 3);
			assertEquals(f.pc, m(state.get("turn")).get("character"));
			// Reducing exhaustion leaves 5 feet of Speed; a zone move is possible again.
			updateCharacter(f.engine, f.campaign, f.pc, map("exhaustion", 4));
			Map<String, Object> moved = f.engine.encounters().perform(op(), f.campaign, f.encounter, f.pc,
					map("kind", "MOVE", "zone", "far"), false);
			assertEquals("far", moved.get("zone"));
		}
	}

	@Test
	void deathSavesUseTheAdjustedTotalButNaturalTwentyStillRevives() throws Exception {
		try (Fixture f = fixture(5, 30)) {
			f.engine.encounters().perform(op(), f.campaign, f.encounter, f.pc, map("kind", "END_TURN"), true);
			updateCharacter(f.engine, f.campaign, f.pc, map("current_hp", 0, "life_state", "DYING", "death_saves_json",
					Json.write(Combat.freshDeathSaves())));
			f.dice.queue(19);
			Map<String, Object> ended = f.engine.encounters().perform(op(), f.campaign, f.encounter, f.ogre,
					map("kind", "END_TURN"), true);
			Map<String, Object> failed = list(ended.get("death_saves")).get(0);
			assertEquals(19, failed.get("natural"));
			assertEquals(9, failed.get("death_save"));
			assertEquals("FAILURE", failed.get("outcome"));
			assertEquals(1, failed.get("failures"));
			assertEquals(f.ogre, m(ended.get("next_turn")).get("character"));

			f.dice.queue(20);
			Map<String, Object> lucky = f.engine.encounters().perform(op(), f.campaign, f.encounter, f.ogre,
					map("kind", "END_TURN"), true);
			Map<String, Object> revived = list(lucky.get("death_saves")).get(0);
			assertEquals(20, revived.get("natural"));
			assertEquals(10, revived.get("death_save"));
			assertEquals("REGAIN_1_HP", revived.get("outcome"));
			Map<String, Object> sheet = f.engine.characters().characterSheet(f.campaign, f.pc, "PLAY");
			assertEquals("ALIVE", sheet.get("life_state"));
			assertEquals(1, m(sheet.get("hp")).get("current"));
			assertEquals(5, sheet.get("exhaustion"), "a natural 20 restores HP without removing exhaustion");
			assertTrue(f.dice.exhausted());
		}
	}

	@Test
	void modifiedDeathSavesRetainNaturalOneAndOrdinarySuccessRules() {
		assertEquals("TWO_FAILURES", Combat.deathSave(1, -9, Combat.freshDeathSaves()).get("outcome"));
		assertEquals("SUCCESS", Combat.deathSave(14, 10, Combat.freshDeathSaves()).get("outcome"));
		assertEquals("FAILURE", Combat.deathSave(13, 9, Combat.freshDeathSaves()).get("outcome"));
	}

	@Test
	void dyingWithLegacyExhaustionPreservesItsLevelThroughExpiryAndDeathCleanup() throws Exception {
		for (String until : List.of("LONG_REST", "START_OF_TURN")) {
			try (Fixture f = fixture(0, 30)) {
				f.engine.encounters().perform(op(), f.campaign, f.encounter, f.pc, map("kind", "END_TURN"), true);
				long characterId = Ref.id(f.pc, Ref.CHARACTER);
				f.engine.db().mutate(Database.Mutation.of("legacy_death_save_fixture", Ref.id(f.campaign, Ref.CAMPAIGN),
						op(), "GM", null), tx -> {
							long participantId = tx.queryOne(
									"SELECT id FROM encounter_participant WHERE encounter_id = ? AND character_id = ?",
									Ref.id(f.encounter, Ref.ENCOUNTER), characterId).orElseThrow().id();
							tx.update("character", characterId,
									map("current_hp", 0, "life_state", "DYING", "death_saves_json",
											Json.write(map("successes", 0, "failures", 2, "stable", false))));
							tx.insert("active_effect",
									map("campaign_id", Ref.id(f.campaign, Ref.CAMPAIGN), "character_id", characterId,
											"source_description", "Legacy forced march", "provenance", "GM",
											"condition_ref", "srd5e:condition/exhaustion", "start_seq", 0L,
											"start_journal_id", tx.journalId(), "duration_json",
											Json.write(map("until", until, "participant", participantId)),
											"stacking_key", "EXHAUSTION"));
							return Map.of();
						});
				long beforeRevision = f.engine.db().read(tx -> tx.get("character", characterId).lng("revision"));
				f.dice.queue(2);
				Map<String, Object> ended = f.engine.encounters().perform(op(), f.campaign, f.encounter, f.ogre,
						map("kind", "END_TURN"), true);
				assertEquals("DEAD", list(ended.get("death_saves")).get(0).get("outcome"), until);
				Map<String, Object> row = f.engine.db().read(tx -> tx.get("character", characterId).asMap());
				assertEquals(1, ((Number) row.get("exhaustion")).intValue(), until);
				assertTrue(((Number) row.get("revision")).longValue() >= beforeRevision + 2,
						"normalization and death each advance the revision: " + until);
				long effects = f.engine.db()
						.read(tx -> tx.count("SELECT COUNT(*) FROM active_effect WHERE character_id = ?", characterId));
				assertEquals(0L, effects);
			}
		}
	}

	private record Fixture(Engine engine, ScriptedRollService dice, String campaign, String pc, String ogre,
			String encounter, Map<String, Object> started) implements AutoCloseable {
		@Override
		public void close() {
			engine.close();
		}
	}

	private static Fixture fixture(int exhaustion, int speed) throws Exception {
		Engine engine = engine(tempDb("exhaustion-rolls"));
		try {
			String campaign = committedCampaign(engine);
			engine.sessions().bootstrap(op(), campaign, null);
			String pc = "character:1";
			updateCharacter(engine, campaign, pc,
					map("str_score", 14, "dex_score", 14, "exhaustion", exhaustion, "speed", speed));
			engine.inventory().grantLoot(op(), campaign, pc, List.of(map("item", "Dagger")), null, "QUEST", null);
			String ogre = (String) engine.runtime()
					.materialize(op(), campaign, "Ogre", "Target", null, null, null, null, false).get("character");
			ScriptedRollService dice = (ScriptedRollService) engine.roller();
			dice.queue(20, 100, 1, 1);
			Map<String, Object> started = engine.encounters().start(op(), campaign,
					map("party", List.of(pc), "enemy", List.of(ogre)), null, map(pc, "rear", ogre, "near"), null, null,
					null);
			assertEquals(pc, m(started.get("turn")).get("character"));
			assertTrue(dice.exhausted());
			return new Fixture(engine, dice, campaign, pc, ogre, (String) started.get("encounter"), started);
		} catch (Exception | Error failure) {
			engine.close();
			throw failure;
		}
	}

	private static void updateCharacter(Engine engine, String campaign, String character, Map<String, Object> changes) {
		engine.db().mutate(
				Database.Mutation.of("exhaustion_roll_fixture", Ref.id(campaign, Ref.CAMPAIGN), op(), "GM", null),
				tx -> {
					tx.update("character", Ref.id(character, Ref.CHARACTER), changes);
					return Map.of();
				});
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> m(Object value) {
		return (Map<String, Object>) value;
	}

	@SuppressWarnings("unchecked")
	private static List<Map<String, Object>> list(Object value) {
		return (List<Map<String, Object>>) value;
	}
}
