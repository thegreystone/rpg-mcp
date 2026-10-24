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

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import se.hirt.mcp.rpg.dice.ScriptedRollService;
import se.hirt.mcp.rpg.protocol.Ref;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static se.hirt.mcp.rpg.TestCampaigns.engine;
import static se.hirt.mcp.rpg.TestCampaigns.map;
import static se.hirt.mcp.rpg.TestCampaigns.op;
import static se.hirt.mcp.rpg.TestCampaigns.tempDb;

class SharedSpellDamageTest {

	@Test
	void empoweredAreaDamageIsSharedByEveryTarget() throws Exception {
		try (Engine engine = engine(tempDb("shared-empowered-area"))) {
			String[] ids = sorcerer(engine);
			String campaign = ids[0], pc = ids[1];
			String first = ogre(engine, campaign, "First");
			String second = ogre(engine, campaign, "Second");
			ScriptedRollService dice = (ScriptedRollService) engine.roller();
			// Failed save, 3d6 = 1, 1, 6, two rerolls = 6, 6; another failed save.
			dice.queue(1, 1, 1, 6, 6, 6, 1);
			Map<String, Object> cast = engine.spells().cast(op(), campaign, pc, "Burning Hands", null,
					List.of(first, second), map("metamagic", "Empowered Spell"));
			List<Map<String, Object>> targets = list(cast.get("targets"));
			assertEquals(18, targets.get(0).get("damage"));
			assertEquals(18, targets.get(1).get("damage"), "the same empowered damage roll applies to both targets");
			assertEquals(Boolean.FALSE, targets.get(0).get("saved"));
			assertEquals(Boolean.FALSE, targets.get(1).get("saved"));
			assertEquals(1, m(cast.get("metamagic")).get("points_spent"));
			assertEquals(2, m(m(cast.get("metamagic")).get("sorcery_points")).get("current"));
			assertEquals(damageRoll(targets.get(0)), damageRoll(targets.get(1)));
			assertEquals(1L, damageRollCount(engine, campaign));
			assertTrue(dice.exhausted(), "two saves share exactly one original and one empowered damage roll");
		}
	}

	@Test
	void successfulSaveHalvesOnlyThatTargetAndDoesNotAlterTheSharedRoll() throws Exception {
		try (Engine engine = engine(tempDb("shared-area-save"))) {
			String[] ids = sorcerer(engine);
			String campaign = ids[0], pc = ids[1];
			String first = ogre(engine, campaign, "Successful save");
			String second = ogre(engine, campaign, "Failed save");
			ScriptedRollService dice = (ScriptedRollService) engine.roller();
			dice.queue(20, 3, 3, 3, 1);
			List<Map<String, Object>> targets = list(engine.spells()
					.cast(op(), campaign, pc, "Burning Hands", null, List.of(first, second), null).get("targets"));
			assertEquals(Boolean.TRUE, targets.get(0).get("saved"));
			assertEquals(Boolean.FALSE, targets.get(1).get("saved"));
			assertEquals(4, targets.get(0).get("damage"), "half of nine rounds down for this target only");
			assertEquals(9, targets.get(1).get("damage"));
			assertEquals(9, damageRoll(targets.get(0)).get("total"));
			assertEquals(damageRoll(targets.get(0)), damageRoll(targets.get(1)));
			assertEquals(1L, damageRollCount(engine, campaign));
			assertTrue(dice.exhausted());
		}
	}

	@Test
	void carefulFirstTargetTakesNothingWhileLaterTargetsShareTheEmpoweredRoll() throws Exception {
		try (Engine engine = engine(tempDb("shared-area-careful"))) {
			String[] ids = sorcerer(engine);
			String campaign = ids[0], pc = ids[1];
			String ally = ogre(engine, campaign, "Protected ally");
			String first = ogre(engine, campaign, "First foe");
			String second = ogre(engine, campaign, "Second foe");
			int allyHp = hp(engine, ally);
			ScriptedRollService dice = (ScriptedRollService) engine.roller();
			// The protected creature needs no save; damage is rolled once before the foes' saves.
			dice.queue(1, 1, 6, 6, 6, 1, 1);
			Map<String, Object> cast = engine.spells().cast(op(), campaign, pc, "Burning Hands", null,
					List.of(ally, first, second),
					map("metamagic", List.of("Careful Spell", "Empowered Spell"), "careful", List.of(ally)));
			List<Map<String, Object>> targets = list(cast.get("targets"));
			assertEquals(Boolean.TRUE, targets.get(0).get("careful"));
			assertEquals(Boolean.TRUE, targets.get(0).get("saved"));
			assertNull(targets.get(0).get("save_roll"));
			assertNull(targets.get(0).get("damage"));
			assertEquals(allyHp, hp(engine, ally));
			assertEquals(18, targets.get(1).get("damage"));
			assertEquals(18, targets.get(2).get("damage"));
			assertEquals(damageRoll(targets.get(1)), damageRoll(targets.get(2)));
			assertEquals(2, m(cast.get("metamagic")).get("points_spent"));
			assertEquals(1L, damageRollCount(engine, campaign));
			assertTrue(dice.exhausted());
		}
	}

	@Test
	void eachTargetsResistanceAndImmunityApplyAfterTheSharedRollAndItsOwnSave() throws Exception {
		try (Engine engine = engine(tempDb("shared-area-defenses"))) {
			String[] ids = sorcerer(engine);
			String campaign = ids[0], pc = ids[1];
			String resistant = creature(engine, campaign, "Dretch", "Resistant");
			String ordinary = ogre(engine, campaign, "Ordinary");
			String immune = creature(engine, campaign, "Fire Elemental", "Immune");
			ScriptedRollService dice = (ScriptedRollService) engine.roller();
			dice.queue(20, 6, 6, 6, 1, 1);
			List<Map<String, Object>> targets = list(engine.spells()
					.cast(op(), campaign, pc, "Burning Hands", null, List.of(resistant, ordinary, immune), null)
					.get("targets"));
			assertEquals(4, targets.get(0).get("damage"), "eighteen is halved for the save, then for resistance");
			assertEquals(18, targets.get(1).get("damage"));
			assertEquals(0, targets.get(2).get("damage"));
			assertEquals("resistant (halved)", list(targets.get(0).get("breakdown")).get(0).get("note"));
			assertEquals("immune", list(targets.get(2).get("breakdown")).get(0).get("note"));
			assertEquals(damageRoll(targets.get(0)), damageRoll(targets.get(1)));
			assertEquals(damageRoll(targets.get(1)), damageRoll(targets.get(2)));
			assertEquals(1L, damageRollCount(engine, campaign));
			assertTrue(dice.exhausted());
		}
	}

	@Test
	void upcastAreaDamageAddsItsDieOnceForTheWholeCasting() throws Exception {
		try (Engine engine = engine(tempDb("shared-area-upcast"))) {
			String[] ids = sorcerer(engine);
			String campaign = ids[0], pc = ids[1];
			String first = ogre(engine, campaign, "First");
			String second = ogre(engine, campaign, "Second");
			ScriptedRollService dice = (ScriptedRollService) engine.roller();
			dice.queue(1, 1, 1, 6, 6, 6, 6, 1);
			Map<String, Object> cast = engine.spells().cast(op(), campaign, pc, "Burning Hands", 2,
					List.of(first, second), map("metamagic", "Empowered Spell"));
			List<Map<String, Object>> targets = list(cast.get("targets"));
			assertEquals(24, targets.get(0).get("damage"));
			assertEquals(24, targets.get(1).get("damage"));
			assertEquals("4d6 (Empowered)", damageRoll(targets.get(0)).get("expression"));
			assertEquals(List.of(6, 6, 6, 6), damageRoll(targets.get(1)).get("dice"));
			assertEquals(1, m(cast.get("slot")).get("remaining"));
			assertEquals(1, m(cast.get("metamagic")).get("points_spent"));
			assertEquals(1L, damageRollCount(engine, campaign));
			assertTrue(dice.exhausted());
		}
	}

	@Test
	void magicMissileRollsEachDartAndCarriesOnlyTheRemainingRerollBudgetForward() throws Exception {
		try (Engine engine = engine(tempDb("shared-empowered-missile"))) {
			String[] ids = sorcerer(engine);
			String campaign = ids[0], pc = ids[1];
			engine.spells().prepare(op(), campaign, pc, null, List.of("Magic Missile"));
			String first = ogre(engine, campaign, "First");
			String second = ogre(engine, campaign, "Second");
			ScriptedRollService dice = (ScriptedRollService) engine.roller();
			// Four separate d4 rolls; CHA +2 rerolls the first and second darts once each.
			dice.queue(1, 4, 1, 3, 2, 4);
			Map<String, Object> cast = engine.spells().cast(op(), campaign, pc, "Magic Missile", 2,
					List.of(first, second), map("metamagic", "Empowered Spell"));
			List<Map<String, Object>> darts = list(cast.get("targets"));
			assertEquals(List.of(5, 4, 3, 5), darts.stream().map(d -> d.get("damage")).toList());
			assertEquals(List.of(1, 2, 3, 4), darts.stream().map(d -> d.get("dart")).toList());
			assertEquals(List.of(first, second, second, second), darts.stream().map(d -> d.get("target")).toList());
			assertEquals(List.of(4), damageRoll(darts.get(0)).get("dice"));
			assertEquals(List.of(3), damageRoll(darts.get(1)).get("dice"));
			assertEquals("1d4+1 (Empowered)", damageRoll(darts.get(1)).get("expression"));
			assertEquals("1d4+1", damageRoll(darts.get(2)).get("expression"));
			assertEquals(1, damageRoll(darts.get(1)).get("modifier"), "the flat +1 is preserved, never rerolled");
			assertEquals(1, m(cast.get("metamagic")).get("points_spent"));
			assertEquals(2, m(m(cast.get("metamagic")).get("sorcery_points")).get("current"));
			assertEquals(1, m(cast.get("slot")).get("remaining"));
			assertTrue(dice.exhausted(), "the budget is two rerolled dice for the casting, not two per dart");
		}
	}

	@Test
	void scorchingRaysKeepIndependentDamageAndCriticalDiceWithoutResettingTheBudget() throws Exception {
		try (Engine engine = engine(tempDb("shared-empowered-rays"))) {
			String[] ids = sorcerer(engine);
			String campaign = ids[0], pc = ids[1];
			engine.spells().prepare(op(), campaign, pc, null, List.of("Scorching Ray"));
			String first = ogre(engine, campaign, "First");
			String second = ogre(engine, campaign, "Second");
			ScriptedRollService dice = (ScriptedRollService) engine.roller();
			dice.queue(20, 1, 1, 6, 6, 6, 6, 15, 2, 3, 15, 4, 5);
			Map<String, Object> cast = engine.spells().cast(op(), campaign, pc, "Scorching Ray", 2,
					List.of(first, second), map("metamagic", "Empowered Spell"));
			List<Map<String, Object>> rays = list(cast.get("targets"));
			assertEquals(List.of(24, 5, 9), rays.stream().map(r -> r.get("damage")).toList());
			assertEquals(List.of(Boolean.TRUE, Boolean.FALSE, Boolean.FALSE),
					rays.stream().map(r -> r.get("critical")).toList());
			assertEquals("4d6 (Empowered)", damageRoll(rays.get(0)).get("expression"));
			assertEquals("2d6", damageRoll(rays.get(1)).get("expression"));
			assertEquals(List.of(2, 3), damageRoll(rays.get(1)).get("dice"));
			assertEquals(List.of(4, 5), damageRoll(rays.get(2)).get("dice"));
			assertEquals(1, m(cast.get("metamagic")).get("points_spent"));
			assertTrue(dice.exhausted());
		}
	}

	@Test
	void deadFirstTargetDoesNotConsumeASaveOrTheSharedDamageRoll() throws Exception {
		try (Engine engine = engine(tempDb("shared-area-dead"))) {
			String[] ids = sorcerer(engine);
			String campaign = ids[0], pc = ids[1];
			String dead = ogre(engine, campaign, "Already dead");
			String first = ogre(engine, campaign, "First survivor");
			String second = ogre(engine, campaign, "Second survivor");
			engine.runtime().applyRuntimeChange(op(), campaign, dead,
					map("kind", "DAMAGE", "amount", 1000, "reason", "already dead before casting"));
			ScriptedRollService dice = (ScriptedRollService) engine.roller();
			dice.queue(1, 1, 1, 6, 6, 6, 1);
			List<Map<String, Object>> targets = list(engine.spells().cast(op(), campaign, pc, "Burning Hands", null,
					List.of(dead, first, second), map("metamagic", "Empowered Spell")).get("targets"));
			assertEquals("already dead", targets.get(0).get("skipped"));
			assertNull(targets.get(0).get("save_roll"));
			assertEquals(18, targets.get(1).get("damage"));
			assertEquals(18, targets.get(2).get("damage"));
			assertEquals(1L, damageRollCount(engine, campaign));
			assertTrue(dice.exhausted());
		}
	}

	@Test
	void replayDoesNotRepeatDamageOrSpendingAndCheckpointRestoresTheCasting() throws Exception {
		try (Engine engine = engine(tempDb("shared-area-checkpoint"))) {
			String[] ids = sorcerer(engine);
			String campaign = ids[0], pc = ids[1];
			String first = ogre(engine, campaign, "First");
			String second = ogre(engine, campaign, "Second");
			int firstHp = hp(engine, first), secondHp = hp(engine, second);
			String checkpoint = (String) engine.checkpoints().create(op(), campaign, "before shared damage")
					.get("checkpoint");
			String castOperation = op();
			Map<String, Object> options = map("metamagic", "Empowered Spell");
			ScriptedRollService dice = (ScriptedRollService) engine.roller();
			dice.queue(1, 1, 1, 6, 6, 6, 1);
			Map<String, Object> cast = engine.spells().cast(castOperation, campaign, pc, "Burning Hands", null,
					List.of(first, second), options);
			Map<String, List<Map<String, Object>>> after = engine.db().snapshot(Ref.id(campaign, Ref.CAMPAIGN));
			Map<String, Object> replay = engine.spells().cast(castOperation, campaign, pc, "Burning Hands", null,
					List.of(first, second), options);
			assertEquals(Boolean.TRUE, replay.get("replayed"));
			assertEquals(cast.get("targets"), replay.get("targets"));
			assertEquals(after, engine.db().snapshot(Ref.id(campaign, Ref.CAMPAIGN)));
			assertEquals(firstHp - 18, hp(engine, first));
			assertEquals(secondHp - 18, hp(engine, second));
			engine.checkpoints().restore(op(), campaign, checkpoint, "undo the shared damage casting");
			assertEquals(firstHp, hp(engine, first));
			assertEquals(secondHp, hp(engine, second));
			Map<String, Object> restored = m(
					engine.characters().characterSheet(campaign, pc, "PLAY").get("spellcasting"));
			assertEquals(3, m(restored.get("sorcery_points")).get("current"));
			assertEquals(4, m(m(restored.get("slots")).get("1")).get("current"));
			assertEquals(0L, damageRollCount(engine, campaign));
			dice.queue(1, 2, 2, 6, 5, 5, 1);
			List<Map<String, Object>> fresh = list(engine.spells()
					.cast(op(), campaign, pc, "Burning Hands", null, List.of(first, second), options).get("targets"));
			assertEquals(16, fresh.get(0).get("damage"));
			assertEquals(16, fresh.get(1).get("damage"), "the restored campaign uses one new shared roll");
			assertEquals(1L, damageRollCount(engine, campaign));
			assertTrue(dice.exhausted());
		}
	}

	private static Map<String, Object> damageRoll(Map<String, Object> target) {
		return m(list(target.get("breakdown")).get(0).get("roll"));
	}

	private static long damageRollCount(Engine engine, String campaign) {
		return engine.db().read(tx -> tx.count("SELECT COUNT(*) FROM roll WHERE campaign_id = ? AND purpose = ?",
				Ref.id(campaign, Ref.CAMPAIGN), "damage Burning Hands (fire)"));
	}

	private static int hp(Engine engine, String character) {
		return engine.db().read(tx -> tx.get("character", Ref.id(character, Ref.CHARACTER)).intOr("current_hp", 0));
	}

	private static String ogre(Engine engine, String campaign, String name) {
		return creature(engine, campaign, "Ogre", name);
	}

	private static String creature(Engine engine, String campaign, String creature, String name) {
		return (String) engine.runtime().materialize(op(), campaign, creature, name, null, null, null, null, false)
				.get("character");
	}

	private static String[] sorcerer(Engine engine) {
		String campaign = (String) engine.campaigns().create(op(), null, null).get("campaign");
		engine.campaigns().updateSetup(op(), campaign, null,
				map("content_profile", "PEGI_16", "experience", "SURPRISE_ME", "rules",
						map("gm_override_policy", "EXPLICIT_AUDITED"), "continuation", "CHECKPOINT", "party",
						"SURPRISE_ME", "adventure",
						map("premise", "x", "opening_location", "Tower", "immediate_goal", "y")));
		String pc = (String) engine.characters()
				.createDraft(op(), campaign,
						map("name", "Lucien", "species", "Human", "class", "Sorcerer", "ability_scores",
								map("CHA", 15, "DEX", 14, "CON", 13, "INT", 12, "WIS", 10, "STR", 8), "skills",
								List.of("Arcana", "Persuasion"), "personality", "x", "background", "Criminal",
								"background_ability_scores", map("CON", 2, "DEX", 1), "species_skill",
								"Animal Handling", "origin_feat",
								map("feat", "Skilled", "proficiencies", List.of("Nature", "Survival", "Medicine")),
								"cantrips", List.of("Fire Bolt", "Light", "Mage Hand", "Prestidigitation"), "spells",
								List.of("Burning Hands", "Chromatic Orb")),
						true)
				.get("character");
		engine.characters().commitDraft(op(), campaign, pc, null);
		engine.campaigns().commitSetup(op(), campaign, null);
		engine.sessions().bootstrap(op(), campaign, null);
		engine.runtime().awardXp(op(), campaign, List.of(pc), 900, "MILESTONE", "test");
		for (int next = 2; next <= 3; next++) {
			Map<String, Object> begun = engine.levelUps().begin(op(), campaign, pc);
			String transaction = (String) m(begun.get("transaction")).get("ref");
			if (next == 2) {
				engine.levelUps().update(op(), campaign, transaction, null,
						map("metamagic", List.of("Empowered Spell", "Careful Spell")));
			}
			engine.levelUps().commit(op(), campaign, transaction, null);
		}
		return new String[] {campaign, pc};
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
