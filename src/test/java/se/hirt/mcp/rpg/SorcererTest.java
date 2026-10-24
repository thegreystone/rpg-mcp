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
import se.hirt.mcp.rpg.protocol.ErrorCode;
import se.hirt.mcp.rpg.protocol.Ref;
import se.hirt.mcp.rpg.protocol.RpgException;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static se.hirt.mcp.rpg.TestCampaigns.engine;
import static se.hirt.mcp.rpg.TestCampaigns.map;
import static se.hirt.mcp.rpg.TestCampaigns.op;
import static se.hirt.mcp.rpg.TestCampaigns.tempDb;

/**
 * The Sorcerer's Font of Magic and Metamagic (SRD 5.2.1 "Sorcerer") as data-driven class features
 * (RULES_ENGINE.md §2.2): sorcery points as a tracked pool, options chosen at level-up, and the
 * casting core shaped by them.
 */
class SorcererTest {

	@SuppressWarnings("unchecked")
	private static Map<String, Object> m(Object o) {
		return (Map<String, Object>) o;
	}

	@SuppressWarnings("unchecked")
	private static List<Map<String, Object>> list(Object o) {
		return (List<Map<String, Object>>) o;
	}

	/**
	 * A committed campaign with a CHA 15 human sorcerer (standard array) who prepares Burning Hands
	 * and Chromatic Orb; returns {campaign, pc}.
	 */
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
		return new String[] {campaign, pc};
	}

	/**
	 * Levels the sorcerer to the given level, choosing the Metamagic options at level 2 and CHA at
	 * level 4.
	 */
	private static void levelTo(Engine engine, String campaign, String pc, int level, List<String> metamagic) {
		int[] xp = {0, 0, 300, 900, 2700, 6500, 14000, 23000};
		engine.runtime().awardXp(op(), campaign, List.of(pc), xp[level], "MILESTONE", "test");
		for (int next = 2; next <= level; next++) {
			Map<String, Object> begun = engine.levelUps().begin(op(), campaign, pc);
			String t = (String) m(begun.get("transaction")).get("ref");
			if (next == 2) {
				engine.levelUps().update(op(), campaign, t, null, map("metamagic", metamagic));
			}
			if (next == 4) {
				engine.levelUps().update(op(), campaign, t, null, map("ability_score_improvement", map("CHA", 2)));
			}
			engine.levelUps().commit(op(), campaign, t, null);
		}
	}

	private record LightEncounter(Engine engine, String campaign, String pc, String ref, String target, String dagger,
			String handaxe) {
		private Map<String, Object> perform(Map<String, Object> action) {
			return engine.encounters().perform(op(), campaign, ref, pc, action, false);
		}

		private Map<String, Object> attack(boolean extra) {
			return perform(map("kind", "ATTACK", "weapon", extra ? handaxe : dagger, "target", target,
					"light_extra_attack", extra));
		}

		private void nextTurn() {
			engine.encounters().perform(op(), campaign, ref, pc, map("kind", "END_TURN"), true);
			Map<String, Object> ended = engine.encounters().perform(op(), campaign, ref, target,
					map("kind", "END_TURN"), true);
			assertEquals(pc, m(ended.get("next_turn")).get("character"));
		}
	}

	private static LightEncounter lightEncounter(Engine engine, String campaign, String pc) {
		List<Map<String, Object>> granted = list(engine.inventory().grantLoot(op(), campaign, pc,
				List.of(map("item", "Dagger"), map("item", "Handaxe")), null, "QUEST", null).get("granted"));
		String dagger = (String) engine.inventory()
				.equip(op(), campaign, pc, (String) granted.get(0).get("entry"), true).get("entry");
		String handaxe = (String) engine.inventory()
				.equip(op(), campaign, pc, (String) granted.get(1).get("entry"), true).get("entry");
		String target = (String) engine.runtime()
				.materialize(op(), campaign, "Ogre", "Brawn", null, null, null, null, false).get("character");
		ScriptedRollService dice = (ScriptedRollService) engine.roller();
		dice.queue(20, 100, 1, 1);
		Map<String, Object> started = engine.encounters().start(op(), campaign,
				map("party", List.of(pc), "foes", List.of(target)), null, null, null, null, null);
		assertEquals(pc, m(started.get("turn")).get("character"));
		assertTrue(dice.exhausted());
		return new LightEncounter(engine, campaign, pc, (String) started.get("encounter"), target, dagger, handaxe);
	}

	private static void assertBonusActionRejected(Engine engine, String campaign, Runnable action) {
		Map<String, List<Map<String, Object>>> before = engine.db().snapshot(Ref.id(campaign, Ref.CAMPAIGN));
		assertEquals(ErrorCode.OPERATION_NOT_ALLOWED, assertThrows(RpgException.class, action::run).code());
		assertEquals(before, engine.db().snapshot(Ref.id(campaign, Ref.CAMPAIGN)),
				"a rejected Bonus Action preserves resources, hit points and turn state");
	}

	@Test
	void metamagicIsChosenAtLevelTwoAndFontOfMagicArrivesWithIt() throws Exception {
		try (Engine engine = engine(tempDb("sorc-level"))) {
			String[] ids = sorcerer(engine);
			String campaign = ids[0], pc = ids[1];
			Map<String, Object> before = m(
					engine.characters().characterSheet(campaign, pc, "PLAY").get("spellcasting"));
			assertNull(before.get("sorcery_points"), "no Font of Magic at level 1");

			engine.runtime().awardXp(op(), campaign, List.of(pc), 300, "MILESTONE", "test");
			Map<String, Object> begun = engine.levelUps().begin(op(), campaign, pc);
			String t = (String) m(begun.get("transaction")).get("ref");
			Map<String, Object> choice = m(begun.get("metamagic_choice"));
			assertNotNull(choice, "level 2 offers Metamagic: " + begun);
			assertEquals(2, choice.get("choose"));
			assertEquals(10, list(choice.get("options")).size(), "the ten SRD options");

			// Owed, so the commit is refused until chosen; the count is exact; unknown names are refused.
			assertEquals(ErrorCode.VALIDATION_FAILED,
					assertThrows(RpgException.class, () -> engine.levelUps().commit(op(), campaign, t, null)).code());
			assertEquals(ErrorCode.VALIDATION_FAILED, assertThrows(RpgException.class, () -> engine.levelUps()
					.update(op(), campaign, t, null, map("metamagic", List.of("Empowered Spell")))).code(),
					"one is too few");
			assertEquals(ErrorCode.VALIDATION_FAILED, assertThrows(RpgException.class, () -> engine.levelUps()
					.update(op(), campaign, t, null, map("metamagic", List.of("Empowered", "Loud Spell")))).code());
			engine.levelUps().update(op(), campaign, t, null,
					map("metamagic", List.of("Empowered", "Quickened Spell")));
			Map<String, Object> committed = engine.levelUps().commit(op(), campaign, t, null);
			assertEquals(List.of("Empowered Spell", "Quickened Spell"), committed.get("metamagic"));

			Map<String, Object> sheet = engine.characters().characterSheet(campaign, pc, "PLAY");
			Map<String, Object> sc = m(sheet.get("spellcasting"));
			assertEquals(2, m(sc.get("sorcery_points")).get("max"), "one point per sorcerer level");
			assertEquals(2, m(sc.get("sorcery_points")).get("current"));
			assertEquals(2, list(m(sc.get("metamagic")).get("known")).size());
			assertNull(m(sc.get("metamagic")).get("unchosen"));
			assertTrue(list(sheet.get("class_features")).stream()
					.anyMatch(f -> "Font of Magic".equals(f.get("name")) && "engine".equals(f.get("adjudication"))));
			assertTrue(
					list(sheet.get("resources")).stream().anyMatch(
							r -> "innate_sorcery".equals(r.get("ref")) && Integer.valueOf(2).equals(r.get("max"))),
					"Innate Sorcery uses are tracked");

			// Level 3 owes nothing more; the next increase is at 10.
			engine.runtime().awardXp(op(), campaign, List.of(pc), 600, "MILESTONE", "test");
			Map<String, Object> three = engine.levelUps().begin(op(), campaign, pc);
			assertNull(three.get("metamagic_choice"));
			engine.levelUps().commit(op(), campaign, (String) m(three.get("transaction")).get("ref"), null);
			assertEquals(3, m(m(engine.characters().characterSheet(campaign, pc, "PLAY").get("spellcasting"))
					.get("sorcery_points")).get("max"));
		}
	}

	@Test
	void empoweredRerollsTheLowestDiceAndQuickenedSpendsPoints() throws Exception {
		try (Engine engine = engine(tempDb("sorc-empower"))) {
			ScriptedRollService dice = (ScriptedRollService) engine.roller();
			String[] ids = sorcerer(engine);
			String campaign = ids[0], pc = ids[1];
			levelTo(engine, campaign, pc, 3, List.of("Empowered Spell", "Quickened Spell"));
			String bandit = (String) engine.runtime()
					.materialize(op(), campaign, "Bandit", null, null, null, null, null, false).get("character");

			// Attack 15 + 4 hits AC 12; damage 3d8 = 1, 2, 8; CHA +2 rerolls the two lowest: 8, 7 → 8, 7, 8 = 23.
			dice.queue(15, 1, 2, 8, 8, 7);
			Map<String, Object> orb = engine.spells().cast(op(), campaign, pc, "Chromatic Orb", null, List.of(bandit),
					map("metamagic", List.of("Empowered Spell", "Quickened Spell"), "damage_type", "cold"));
			Map<String, Object> mm = m(orb.get("metamagic"));
			assertEquals(List.of("Empowered Spell", "Quickened Spell"), mm.get("options"));
			assertEquals(3, mm.get("points_spent"), "1 + 2");
			assertEquals(0, m(mm.get("sorcery_points")).get("current"));
			Map<String, Object> hit = list(orb.get("targets")).get(0);
			assertEquals(Boolean.TRUE, hit.get("hit"));
			assertEquals(23, hit.get("damage"), hit.toString());
			assertEquals(List.of(8, 7, 8), m(list(hit.get("breakdown")).get(0).get("roll")).get("dice"));
			assertEquals("cold", list(hit.get("breakdown")).get(0).get("type"));

			// No points left: the same casting is refused before anything is spent.
			int slotsBefore = (Integer) m(
					m(m(engine.characters().characterSheet(campaign, pc, "PLAY").get("spellcasting")).get("slots"))
							.get("1"))
					.get("current");
			assertEquals(ErrorCode.INSUFFICIENT_RESOURCE,
					assertThrows(RpgException.class, () -> engine.spells().cast(op(), campaign, pc, "Chromatic Orb",
							null, List.of(bandit), map("metamagic", "Quickened Spell"))).code());
			assertEquals(slotsBefore,
					m(m(m(engine.characters().characterSheet(campaign, pc, "PLAY").get("spellcasting")).get("slots"))
							.get("1")).get("current"),
					"the slot was not spent");
			// An option that is not known, and two shaping options at once, are refused.
			assertEquals(ErrorCode.VALIDATION_FAILED,
					assertThrows(RpgException.class, () -> engine.spells().cast(op(), campaign, pc, "Chromatic Orb",
							null, List.of(bandit), map("metamagic", List.of("Seeking Spell")))).code());
			// After a Long Rest the points are back; naming an option twice counts once.
			engine.rest().rest(op(), campaign, "LONG", null, null);
			dice.queue(15, 4, 4, 4);
			Map<String, Object> again = engine.spells().cast(op(), campaign, pc, "Chromatic Orb", null, List.of(bandit),
					map("metamagic", List.of("Quickened Spell", "Quickened")));
			assertEquals(List.of("Quickened Spell"), m(again.get("metamagic")).get("options"));
			assertEquals(2, m(again.get("metamagic")).get("points_spent"));
			assertEquals(1, m(m(again.get("metamagic")).get("sorcery_points")).get("current"));
		}
	}

	@Test
	void quickenedCastingAndTheLightExtraAttackShareOneBonusActionPerTurn() throws Exception {
		try (Engine engine = engine(tempDb("sorc-quickened-light"))) {
			String[] ids = sorcerer(engine);
			String campaign = ids[0], pc = ids[1];
			levelTo(engine, campaign, pc, 4, List.of("Quickened Spell", "Subtle Spell"));
			LightEncounter encounter = lightEncounter(engine, campaign, pc);
			ScriptedRollService dice = (ScriptedRollService) engine.roller();
			Map<String, Object> quickened = map("kind", "CAST", "spell", "Fire Bolt", "target", encounter.target,
					"metamagic", "Quickened Spell");

			dice.queue(19, 2);
			encounter.attack(false);
			dice.queue(19, 2);
			Map<String, Object> cast = encounter.perform(quickened);
			assertEquals("BONUS_ACTION", cast.get("action_cost"));
			assertEquals(2, m(m(cast.get("metamagic")).get("sorcery_points")).get("current"));
			assertBonusActionRejected(engine, campaign, () -> encounter.attack(true));

			encounter.nextTurn();
			dice.queue(19, 2);
			encounter.attack(false);
			dice.queue(19, 3);
			assertEquals("BONUS_ACTION", encounter.attack(true).get("action_cost"));
			assertBonusActionRejected(engine, campaign, () -> encounter.perform(quickened));

			encounter.nextTurn();
			dice.queue(19, 2);
			Map<String, Object> nextCast = encounter.perform(quickened);
			assertEquals("BONUS_ACTION", nextCast.get("action_cost"));
			assertEquals(0, m(m(nextCast.get("metamagic")).get("sorcery_points")).get("current"),
					"the rejected casting kept the two points needed on the next turn");
			assertTrue(dice.exhausted());
		}
	}

	@Test
	void creatingASpellSlotAndTheLightExtraAttackShareOneBonusActionPerTurn() throws Exception {
		try (Engine engine = engine(tempDb("sorc-create-slot-light"))) {
			String[] ids = sorcerer(engine);
			String campaign = ids[0], pc = ids[1];
			levelTo(engine, campaign, pc, 4, List.of("Subtle Spell", "Distant Spell"));
			LightEncounter encounter = lightEncounter(engine, campaign, pc);
			ScriptedRollService dice = (ScriptedRollService) engine.roller();
			Map<String, Object> creation = map("kind", "CREATE_SPELL_SLOT", "slot_level", 1, "reason", "test");

			dice.queue(19, 2);
			encounter.attack(false);
			Map<String, Object> created = engine.runtime().applyRuntimeChange(op(), campaign, pc, creation);
			assertEquals("BONUS_ACTION", created.get("action_cost"));
			assertBonusActionRejected(engine, campaign, () -> encounter.attack(true));

			encounter.nextTurn();
			dice.queue(19, 2);
			encounter.attack(false);
			dice.queue(19, 3);
			assertEquals("BONUS_ACTION", encounter.attack(true).get("action_cost"));
			assertBonusActionRejected(engine, campaign,
					() -> engine.runtime().applyRuntimeChange(op(), campaign, pc, creation));

			encounter.nextTurn();
			Map<String, Object> nextCreation = engine.runtime().applyRuntimeChange(op(), campaign, pc, creation);
			assertEquals("BONUS_ACTION", nextCreation.get("action_cost"));
			assertEquals(0, m(nextCreation.get("sorcery_points")).get("current"),
					"the rejected creation preserves both points and slots");
			assertTrue(dice.exhausted());
		}
	}

	@Test
	void convertingSpellSlotsNeedsNoActionBeforeOrAfterTheLightExtraAttack() throws Exception {
		try (Engine engine = engine(tempDb("sorc-convert-slot-light"))) {
			String[] ids = sorcerer(engine);
			String campaign = ids[0], pc = ids[1];
			levelTo(engine, campaign, pc, 4, List.of("Subtle Spell", "Distant Spell"));
			engine.runtime().applyRuntimeChange(op(), campaign, pc,
					map("kind", "USE_RESOURCE", "resource", "sorcery_points", "amount", 4, "reason", "test"));
			LightEncounter encounter = lightEncounter(engine, campaign, pc);
			ScriptedRollService dice = (ScriptedRollService) engine.roller();
			Map<String, Object> conversion = map("kind", "CONVERT_SPELL_SLOT", "slot_level", 1, "reason", "test");

			Map<String, Object> beforeAttack = engine.runtime().applyRuntimeChange(op(), campaign, pc, conversion);
			assertEquals("NONE", beforeAttack.get("action_cost"));
			assertEquals(1, m(beforeAttack.get("sorcery_points")).get("current"));
			dice.queue(19, 2);
			encounter.attack(false);
			dice.queue(19, 3);
			assertEquals("BONUS_ACTION", encounter.attack(true).get("action_cost"));
			Map<String, Object> afterAttack = engine.runtime().applyRuntimeChange(op(), campaign, pc, conversion);
			assertEquals("NONE", afterAttack.get("action_cost"));
			assertEquals(2, m(afterAttack.get("sorcery_points")).get("current"));
			assertEquals(2, m(afterAttack.get("slot")).get("current"));

			encounter.nextTurn();
			Map<String, Object> beforeCreation = engine.runtime().applyRuntimeChange(op(), campaign, pc, conversion);
			assertEquals("NONE", beforeCreation.get("action_cost"));
			assertEquals(3, m(beforeCreation.get("sorcery_points")).get("current"));
			Map<String, Object> created = engine.runtime().applyRuntimeChange(op(), campaign, pc,
					map("kind", "CREATE_SPELL_SLOT", "slot_level", 1, "reason", "test"));
			assertEquals("BONUS_ACTION", created.get("action_cost"));
			assertEquals(1, m(created.get("sorcery_points")).get("current"));
			assertEquals(2, m(created.get("slot")).get("current"));
			assertTrue(dice.exhausted());
		}
	}

	@Test
	void heightenedAndCarefulShapeSavingThrows() throws Exception {
		try (Engine engine = engine(tempDb("sorc-save"))) {
			ScriptedRollService dice = (ScriptedRollService) engine.roller();
			String[] ids = sorcerer(engine);
			String campaign = ids[0], pc = ids[1];
			levelTo(engine, campaign, pc, 3, List.of("Heightened Spell", "Careful Spell"));
			String bandit = (String) engine.runtime()
					.materialize(op(), campaign, "Bandit", null, null, null, null, null, false).get("character");
			String guard = (String) engine.runtime()
					.materialize(op(), campaign, "Guard", "Ally", null, null, null, null, false).get("character");

			// Heightened: the bandit rolls 2d20 keep lowest (18, 3 → 3) + DEX 1 = 4 vs DC 12: fails; 3d6 = 12.
			dice.queue(18, 3, 4, 4, 4);
			Map<String, Object> hands = engine.spells().cast(op(), campaign, pc, "Burning Hands", null, List.of(bandit),
					map("metamagic", "Heightened Spell"));
			Map<String, Object> t = list(hands.get("targets")).get(0);
			assertEquals(Boolean.TRUE, t.get("heightened"));
			assertEquals("2d20kl1+1", m(t.get("save_roll")).get("expression"));
			assertEquals(Boolean.FALSE, t.get("saved"));
			assertEquals(12, t.get("damage"));
			assertEquals(1, m(m(hands.get("metamagic")).get("sorcery_points")).get("current"), "3 - 2");

			// Careful: the named ally succeeds automatically and takes nothing; a fresh bandit still saves normally
			// (the first one died of the twelve).
			String second = (String) engine.runtime()
					.materialize(op(), campaign, "Bandit", null, null, null, null, null, false).get("character");
			dice.queue(10, 2, 2, 2);
			Map<String, Object> careful = engine.spells().cast(op(), campaign, pc, "Burning Hands", null,
					List.of(second, guard), map("metamagic", List.of("Careful Spell"), "careful", List.of(guard)));
			Map<String, Object> ally = list(careful.get("targets")).get(1);
			assertEquals(Boolean.TRUE, ally.get("careful"));
			assertEquals(Boolean.TRUE, ally.get("saved"));
			assertNull(ally.get("damage"), "no damage on an automatic success: " + ally);
			Map<String, Object> foe = list(careful.get("targets")).get(0);
			assertNotNull(foe.get("save_roll"), "the bandit is not protected");
			assertEquals(0, m(m(careful.get("metamagic")).get("sorcery_points")).get("current"));
			// Careful needs the protected creatures named.
			engine.rest().rest(op(), campaign, "LONG", null, null);
			assertEquals(ErrorCode.VALIDATION_FAILED, assertThrows(RpgException.class, () -> engine.spells().cast(op(),
					campaign, pc, "Burning Hands", null, List.of(second), map("metamagic", "Careful Spell"))).code());
			// Heightened is for saving throws only.
			assertEquals(ErrorCode.VALIDATION_FAILED,
					assertThrows(RpgException.class, () -> engine.spells().cast(op(), campaign, pc, "Chromatic Orb",
							null, List.of(second), map("metamagic", "Heightened Spell"))).code());
		}
	}

	@Test
	void extendedAttackSpellConcentrationSurvivesAMissAndExpiresAtItsDoubledDuration() throws Exception {
		try (Engine engine = engine(tempDb("sorc-extended-attack"))) {
			String[] ids = sorcerer(engine);
			String campaign = ids[0], pc = ids[1];
			levelTo(engine, campaign, pc, 5, List.of("Empowered Spell", "Extended Spell"));
			engine.spells().prepare(op(), campaign, pc, null, List.of("Vampiric Touch"));
			String ogre = (String) engine.runtime()
					.materialize(op(), campaign, "Ogre", "Brawn", null, null, null, null, false).get("character");
			ScriptedRollService dice = (ScriptedRollService) engine.roller();
			dice.queue(1);
			Map<String, Object> cast = engine.spells().cast(op(), campaign, pc, "Vampiric Touch", null, List.of(ogre),
					map("metamagic", "Extended Spell"));
			assertEquals(Boolean.FALSE, list(cast.get("targets")).get(0).get("hit"));
			assertTrue(dice.exhausted(), "the missed attack rolls no damage dice");
			assertEquals(1, ((List<?>) cast.get("effects_created")).size(),
					"the missed spell still creates exactly one concentration marker");
			assertEquals(1, m(cast.get("metamagic")).get("points_spent"));
			assertEquals(4, m(m(cast.get("metamagic")).get("sorcery_points")).get("current"));
			Map<String, Object> sheet = engine.characters().characterSheet(campaign, pc, "PLAY");
			List<Map<String, Object>> markers = list(sheet.get("effects")).stream()
					.filter(e -> pc.equals(e.get("concentration_by"))).toList();
			assertEquals(1, markers.size(), "concentration belongs to the caster even when the target has no effect");
			assertEquals("CONCENTRATING", markers.get(0).get("condition"));
			assertEquals(List.of("Vampiric Touch (Lucien)"), m(sheet.get("spellcasting")).get("concentrating_on"));

			engine.sessions().advanceTime(op(), campaign, 1, "the printed one-minute duration passes");
			sheet = engine.characters().characterSheet(campaign, pc, "PLAY");
			assertEquals(markers,
					list(sheet.get("effects")).stream().filter(e -> pc.equals(e.get("concentration_by"))).toList(),
					"Extended Spell preserves the same marker beyond the printed duration");
			engine.sessions().advanceTime(op(), campaign, 1, "the doubled two-minute duration passes");
			sheet = engine.characters().characterSheet(campaign, pc, "PLAY");
			assertTrue(list(sheet.get("effects")).stream().noneMatch(e -> pc.equals(e.get("concentration_by"))),
					"concentration expires when the doubled duration ends");
			assertEquals(List.of(), m(sheet.get("spellcasting")).get("concentrating_on"));
			assertEquals(4, m(m(sheet.get("spellcasting")).get("sorcery_points")).get("current"),
					"maintaining and expiring the marker charge no additional sorcery points");
		}
	}

	@Test
	void fontOfMagicConvertsPointsAndSlotsBothWays() throws Exception {
		try (Engine engine = engine(tempDb("sorc-font"))) {
			String[] ids = sorcerer(engine);
			String campaign = ids[0], pc = ids[1];
			levelTo(engine, campaign, pc, 3, List.of("Subtle Spell", "Distant Spell"));

			// Two points make a level-1 slot; a level-2 slot back into points is worth two.
			Map<String, Object> created = engine.runtime().applyRuntimeChange(op(), campaign, pc,
					map("kind", "CREATE_SPELL_SLOT", "slot_level", 1, "reason", "test"));
			assertEquals(2, created.get("points_spent"));
			assertEquals(1, m(created.get("sorcery_points")).get("current"));
			assertEquals(5, m(created.get("slot")).get("current"), "4 + 1 created");
			assertEquals(5, m(created.get("slot")).get("max"),
					"the pool grows with the created slot until the Long Rest");
			Map<String, Object> converted = engine.runtime().applyRuntimeChange(op(), campaign, pc,
					map("kind", "CONVERT_SPELL_SLOT", "slot_level", 2, "reason", "test"));
			assertEquals(2, converted.get("points_gained"));
			assertEquals(3, m(converted.get("sorcery_points")).get("current"));
			assertEquals(1, m(converted.get("slot")).get("current"));
			// Only slot levels the character can cast; only what the points allow.
			assertEquals(ErrorCode.VALIDATION_FAILED,
					assertThrows(RpgException.class, () -> engine.runtime().applyRuntimeChange(op(), campaign, pc,
							map("kind", "CREATE_SPELL_SLOT", "slot_level", 3, "reason", "test"))).code());
			engine.runtime().applyRuntimeChange(op(), campaign, pc,
					map("kind", "CREATE_SPELL_SLOT", "slot_level", 2, "reason", "test"));
			assertEquals(ErrorCode.INSUFFICIENT_RESOURCE,
					assertThrows(RpgException.class, () -> engine.runtime().applyRuntimeChange(op(), campaign, pc,
							map("kind", "CREATE_SPELL_SLOT", "slot_level", 1, "reason", "test"))).code());
			// A Long Rest refills the points and returns the slots to their maxima: created slots vanish.
			engine.rest().rest(op(), campaign, "LONG", null, null);
			Map<String, Object> sc = m(engine.characters().characterSheet(campaign, pc, "PLAY").get("spellcasting"));
			assertEquals(3, m(sc.get("sorcery_points")).get("current"));
			assertEquals(4, m(m(sc.get("slots")).get("1")).get("current"));
			assertEquals(2, m(m(sc.get("slots")).get("2")).get("current"));
		}
	}

	@Test
	void sorcerousRestorationRegainsPointsOnAShortRestOncePerLongRest() throws Exception {
		try (Engine engine = engine(tempDb("sorc-restore"))) {
			String[] ids = sorcerer(engine);
			String campaign = ids[0], pc = ids[1];
			levelTo(engine, campaign, pc, 5, List.of("Empowered Spell", "Extended Spell"));
			engine.runtime().applyRuntimeChange(op(), campaign, pc,
					map("kind", "USE_RESOURCE", "resource", "sorcery_points", "amount", 4, "reason", "test"));
			Map<String, Object> rest = engine.rest().rest(op(), campaign, "SHORT", null, null);
			Map<String, Object> me = list(rest.get("characters")).stream().filter(c -> pc.equals(c.get("character")))
					.findFirst().orElseThrow();
			assertEquals(2, m(me.get("sorcerous_restoration")).get("points_regained"), "half of level 5, rounded down");
			assertEquals(3, m(m(me.get("sorcerous_restoration")).get("sorcery_points")).get("current"));
			Map<String, Object> again = engine.rest().rest(op(), campaign, "SHORT", null, null);
			assertNull(list(again.get("characters")).stream().filter(c -> pc.equals(c.get("character"))).findFirst()
					.orElseThrow().get("sorcerous_restoration"), "once per Long Rest");
		}
	}

	@Test
	void setMetamagicOverrideReplacesTheOptionsOfAnExistingSorcerer() throws Exception {
		try (Engine engine = engine(tempDb("sorc-override"))) {
			String[] ids = sorcerer(engine);
			String campaign = ids[0], pc = ids[1];
			levelTo(engine, campaign, pc, 3, List.of("Empowered Spell", "Quickened Spell"));
			Map<String, Object> set = engine.rest().override(op(), campaign, "SET_METAMAGIC", pc,
					map("options", List.of("Subtle Spell", "Transmuted Spell")), "levelled before the feature existed",
					null);
			assertEquals(List.of("subtle", "transmuted"),
					set.get("after") instanceof Map<?, ?> a ? a.get("metamagic") : null);
			List<Map<String, Object>> known = list(
					m(m(engine.characters().characterSheet(campaign, pc, "PLAY").get("spellcasting")).get("metamagic"))
							.get("known"));
			assertEquals(List.of("Subtle Spell", "Transmuted Spell"), known.stream().map(k -> k.get("name")).toList());
			assertEquals(ErrorCode.VALIDATION_FAILED,
					assertThrows(RpgException.class,
							() -> engine.rest().override(op(), campaign, "SET_METAMAGIC", pc,
									map("options", List.of("Subtle Spell", "Transmuted Spell", "Distant Spell")),
									"too many", null))
							.code(),
					"three options at level 3");
		}
	}
}
