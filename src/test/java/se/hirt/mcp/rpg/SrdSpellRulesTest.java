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
import se.hirt.mcp.rpg.content.RulesData;
import se.hirt.mcp.rpg.dice.ScriptedRollService;
import se.hirt.mcp.rpg.magic.Metamagic;
import se.hirt.mcp.rpg.protocol.ErrorCode;
import se.hirt.mcp.rpg.protocol.Ref;
import se.hirt.mcp.rpg.protocol.RpgException;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static se.hirt.mcp.rpg.TestCampaigns.committedCampaign;
import static se.hirt.mcp.rpg.TestCampaigns.engine;
import static se.hirt.mcp.rpg.TestCampaigns.map;
import static se.hirt.mcp.rpg.TestCampaigns.meleeZones;
import static se.hirt.mcp.rpg.TestCampaigns.op;
import static se.hirt.mcp.rpg.TestCampaigns.tempDb;

/**
 * Spellcasting rules as the SRD 5.2.1 text states them, one test per rule, each with the operative
 * sentence quoted: rituals need no class feature, the Sorcerer's Metamagic counts, one spell slot
 * per turn, Quickened Spell's level 1+ clause, Heal's conditions and upcast, the Shield spell's
 * triggers and duration, the Concentration DC cap, Extended and Heightened on later saves,
 * Empowered as an optional reroll, and Sleep's single repeat save. These encode the SRD, not the
 * engine: where the engine deviates today the test is red until the engine follows the book.
 */
class SrdSpellRulesTest {

	@SuppressWarnings("unchecked")
	private static Map<String, Object> m(Object o) {
		return (Map<String, Object>) o;
	}

	@SuppressWarnings("unchecked")
	private static List<Map<String, Object>> list(Object o) {
		return (List<Map<String, Object>>) o;
	}

	private static Map<String, Object> sheet(Engine engine, String campaign, String character) {
		return engine.characters().characterSheet(campaign, character, "PLAY");
	}

	private static int hp(Engine engine, String campaign, String character) {
		return (Integer) m(sheet(engine, campaign, character).get("hp")).get("current");
	}

	private static Map<String, Object> slots(Engine engine, String campaign, String character) {
		return m(m(sheet(engine, campaign, character).get("spellcasting")).get("slots"));
	}

	private static int slotsLeft(Engine engine, String campaign, String character, int level) {
		return (Integer) m(slots(engine, campaign, character).get(Integer.toString(level))).get("current");
	}

	private static int sorceryPoints(Engine engine, String campaign, String character) {
		return (Integer) m(m(sheet(engine, campaign, character).get("spellcasting")).get("sorcery_points"))
				.get("current");
	}

	private static List<String> conditionNames(Engine engine, String campaign, String character) {
		return list(sheet(engine, campaign, character).get("conditions")).stream()
				.map(c -> c.get("condition").toString()).toList();
	}

	private static String creature(Engine engine, String campaign, String creature, String name) {
		return (String) engine.runtime().materialize(op(), campaign, creature, name, null, null, null, null, false)
				.get("character");
	}

	/**
	 * Levels a character to the given level through the real transactions: Metamagic when owed, +2
	 * Strength when an Ability Score Improvement is owed (the fixture's rolled scores make any
	 * other ability a risk of passing 20).
	 */
	private static void levelTo(Engine engine, String campaign, String pc, int level, List<String> metamagic) {
		engine.runtime().awardXp(op(), campaign, List.of(pc), engine.rules().xpThreshold(level), "MILESTONE", "test");
		for (int next = 2; next <= level; next++) {
			Map<String, Object> begun = engine.levelUps().begin(op(), campaign, pc);
			String t = (String) m(begun.get("transaction")).get("ref");
			if (begun.get("metamagic_choice") != null) {
				engine.levelUps().update(op(), campaign, t, null, map("metamagic", metamagic));
			}
			if (begun.get("ability_score_improvement") != null) {
				engine.levelUps().update(op(), campaign, t, null, map("ability_score_improvement", map("STR", 2)));
			}
			engine.levelUps().commit(op(), campaign, t, null);
		}
	}

	/** Starts an encounter with scripted initiative: the player character first, then each foe. */
	private static String encounter(Engine engine, String campaign, String pc, List<String> foes) {
		return encounter(engine, campaign, pc, foes, null);
	}

	private static String encounter(
		Engine engine, String campaign, String pc, List<String> foes, Map<String, Object> zones) {
		ScriptedRollService dice = (ScriptedRollService) engine.roller();
		dice.queue(20);
		// One d20 per group of identical creatures on the foes' side (SRD 5.2.1 "Initiative"); no tie-break die.
		var definitions = new java.util.HashSet<Object>();
		for (String foe : foes) {
			Object creature = engine.characters().characterSheet(campaign, foe, "PLAY").get("creature");
			// A built character (no stat block) always rolls on its own.
			Object definition = creature == null ? foe : m(creature).get("definition");
			if (definitions.add(definition)) {
				dice.queue(11 - definitions.size());
			}
		}
		Map<String, Object> started = engine.encounters().start(op(), campaign, map("party", List.of(pc), "foes", foes),
				null, zones, null, null, null);
		assertEquals(pc, m(started.get("turn")).get("character"));
		assertTrue(dice.exhausted());
		return (String) started.get("encounter");
	}

	private static Map<String, Object> perform(
		Engine engine, String campaign, String encounter, String actor, Map<String, Object> action, boolean endTurn) {
		return engine.encounters().perform(op(), campaign, encounter, actor, action, endTurn);
	}

	private static void endTurn(Engine engine, String campaign, String encounter, String actor) {
		perform(engine, campaign, encounter, actor, map("kind", "END_TURN"), true);
	}

	/** A fresh campaign (overrides permitted) with its setup still open for drafts. */
	private static String openCampaign(Engine engine) {
		String campaign = (String) engine.campaigns().create(op(), null, null).get("campaign");
		engine.campaigns().updateSetup(op(), campaign, null,
				map("content_profile", "PEGI_16", "experience", "SURPRISE_ME", "rules",
						map("gm_override_policy", "EXPLICIT_AUDITED"), "continuation", "CHECKPOINT", "party",
						"SURPRISE_ME", "adventure",
						map("premise", "x", "opening_location", "Tower", "immediate_goal", "y")));
		return campaign;
	}

	private static String sorcererDraft(
		Engine engine, String campaign, Map<String, Object> originFeat, List<String> spells, boolean player) {
		return (String) engine.characters().createDraft(op(), campaign,
				map("name", player ? "Lucien" : "Vesper", "species", "Human", "class", "Sorcerer", "ability_scores",
						map("CHA", 15, "DEX", 14, "CON", 13, "INT", 12, "WIS", 10, "STR", 8), "skills",
						List.of("Arcana", "Persuasion"), "personality", "x", "background", "Criminal",
						"background_ability_scores", map("CON", 2, "DEX", 1), "species_skill", "Animal Handling",
						"origin_feat", originFeat, "cantrips",
						List.of("Fire Bolt", "Light", "Mage Hand", "Prestidigitation"), "spells", spells),
				player).get("character");
	}

	private static String wizardDraft(Engine engine, String campaign, List<String> spells) {
		return (String) engine.characters()
				.createDraft(op(), campaign, map("name", "Ash", "species", "Human", "class", "Wizard", "ability_scores",
						map("INT", 15, "DEX", 14, "CON", 13, "WIS", 12, "CHA", 10, "STR", 8), "skills",
						List.of("Arcana", "History"), "personality", "x", "background", "Criminal",
						"background_ability_scores", map("CON", 2, "INT", 1), "species_skill", "Insight", "origin_feat",
						map("feat", "Skilled", "proficiencies", List.of("Nature", "Survival", "Medicine")), "cantrips",
						List.of("Fire Bolt", "Mage Hand", "Light"), "spells", spells), false)
				.get("character");
	}

	private static Map<String, Object> skilled() {
		return map("feat", "Skilled", "proficiencies", List.of("Nature", "Survival", "Medicine"));
	}

	private static Map<String, List<Map<String, Object>>> snapshot(Engine engine, String campaign) {
		return engine.db().snapshot(Ref.id(campaign, Ref.CAMPAIGN));
	}

	/**
	 * SRD 5.2.1 p.104 "Rituals": "Certain spells have the Ritual tag in the Casting Time entry.
	 * Such a spell can be cast following the normal rules for spellcasting, or it can be cast as a
	 * Ritual. The Ritual version of a spell takes 10 minutes longer to cast than normal, but it
	 * doesn't expend a spell slot. To cast a spell as a Ritual, a spellcaster must have it
	 * prepared." No class feature is named: the fixture Sorcerer has Detect Magic prepared (Magic
	 * Initiate) and Detect Magic carries the Ritual tag, so the ritual casting must go through.
	 */
	@Test
	void anyPreparedRitualSpellCanBeCastAsARitualWithoutAClassFeature() throws Exception {
		try (Engine engine = engine(tempDb("srd-ritual"))) {
			String campaign = committedCampaign(engine);
			engine.sessions().bootstrap(op(), campaign, null);
			String pc = "character:1";
			Map<String, Object> slotsBefore = slots(engine, campaign, pc);
			assertTrue(
					((List<?>) m(sheet(engine, campaign, pc).get("spellcasting")).get("prepared")).stream()
							.anyMatch(s -> String.valueOf(s).startsWith("Detect Magic")),
					"the fixture has Detect Magic prepared");

			Map<String, Object> ritual = assertDoesNotThrow(
					() -> engine.spells().cast(op(), campaign, pc, "Detect Magic", null, null, map("ritual", true)),
					"a prepared Ritual-tagged spell can be cast as a ritual by any caster (SRD 5.2.1 p.104)");
			assertEquals(Boolean.TRUE, m(ritual.get("slot")).get("ritual"), "cast as a ritual: " + ritual.get("slot"));
			assertEquals(slotsBefore, slots(engine, campaign, pc), "a ritual expends no spell slot");
		}
	}

	/**
	 * SRD 5.2.1 p.66 "Metamagic": "You gain two more options at Sorcerer level 10 and two more at
	 * Sorcerer level 17." Two at level 2, four at level 10, six at level 17.
	 */
	@Test
	void sorcerersGainTwoMoreMetamagicOptionsAtLevelTenAndTwoMoreAtSeventeen() throws Exception {
		try (Engine engine = engine(tempDb("srd-metamagic-count"))) {
			String campaign = committedCampaign(engine);
			engine.sessions().bootstrap(op(), campaign, null);
			String pc = "character:1";
			engine.runtime().awardXp(op(), campaign, List.of(pc), engine.rules().xpThreshold(10), "MILESTONE", "test");
			for (int next = 2; next <= 10; next++) {
				Map<String, Object> begun = engine.levelUps().begin(op(), campaign, pc);
				String t = (String) m(begun.get("transaction")).get("ref");
				if (next == 2) {
					assertEquals(2, m(begun.get("metamagic_choice")).get("choose"), "two options at level 2");
					engine.levelUps().update(op(), campaign, t, null,
							map("metamagic", List.of("Empowered Spell", "Quickened Spell")));
				} else if (next == 10) {
					assertNotNull(begun.get("metamagic_choice"), "level 10 owes Metamagic options: " + begun);
					assertEquals(2, m(begun.get("metamagic_choice")).get("choose"), "two more options at level 10");
					engine.levelUps().update(op(), campaign, t, null,
							map("metamagic", List.of("Extended Spell", "Heightened Spell")));
				} else {
					assertNull(begun.get("metamagic_choice"), "nothing owed at level " + next);
				}
				if (begun.get("ability_score_improvement") != null) {
					engine.levelUps().update(op(), campaign, t, null, map("ability_score_improvement", map("STR", 2)));
				}
				engine.levelUps().commit(op(), campaign, t, null);
			}
			Map<String, Object> metamagic = m(m(sheet(engine, campaign, pc).get("spellcasting")).get("metamagic"));
			assertEquals(4, metamagic.get("options_known_limit"), "four options at level 10");
			assertEquals(4, list(metamagic.get("known")).size());
			assertNull(metamagic.get("unchosen"));

			// Level 17 is read from the class definition: two more again, six in all.
			RulesData.Definition sorcerer = engine.rules().resolve("CLASS", "Sorcerer").orElseThrow();
			Map<String, Object> spec = Metamagic.featureSpec(sorcerer, 17).orElseThrow();
			assertEquals(6, Metamagic.allowed(spec, 17), "six options at level 17");
			assertEquals(4, Metamagic.allowed(spec, 16), "still four until level 17");
		}
	}

	/**
	 * SRD 5.2.1 p.105 "One Spell with a Spell Slot per Turn": "On a turn, you can expend only one
	 * spell slot to cast a spell. This rule means you can't, for example, cast a spell with a spell
	 * slot using the Magic action and another one using a Bonus Action on the same turn." A cantrip
	 * after a slot spell is fine: it expends no slot.
	 */
	@Test
	void onlyOneSpellSlotMayBeExpendedPerTurn() throws Exception {
		try (Engine engine = engine(tempDb("srd-one-slot-per-turn"))) {
			ScriptedRollService dice = (ScriptedRollService) engine.roller();
			String campaign = openCampaign(engine);
			// Magic Initiate (Cleric) gives the sorcerer Healing Word, a Bonus Action spell.
			String pc = sorcererDraft(engine, campaign,
					map("feat", "Magic Initiate", "spell_list", "cleric", "ability", "WIS", "cantrips",
							List.of("Guidance", "Sacred Flame"), "spell", "Healing Word"),
					List.of("Burning Hands", "Chromatic Orb"), true);
			engine.characters().commitDraft(op(), campaign, pc, null);
			engine.campaigns().commitSetup(op(), campaign, null);
			engine.sessions().bootstrap(op(), campaign, null);
			levelTo(engine, campaign, pc, 3, List.of("Subtle Spell", "Distant Spell"));
			// The feat's free casting is used up outside combat, so every Healing Word below spends a slot.
			dice.queue(1, 1);
			Map<String, Object> free = engine.spells().cast(op(), campaign, pc, "Healing Word", null, List.of(pc),
					null);
			assertNotNull(m(free.get("slot")).get("free_cast"), free.get("slot").toString());

			String ogre = creature(engine, campaign, "Ogre", "Brawn");
			String enc = encounter(engine, campaign, pc, List.of(ogre));
			int slotsBefore = slotsLeft(engine, campaign, pc, 1);

			// Turn 1: a slot spell as a Bonus Action, then a cantrip with the action — legal.
			dice.queue(1, 1);
			Map<String, Object> word = perform(engine, campaign, enc, pc,
					map("kind", "CAST", "spell", "Healing Word", "targets", List.of(pc)), false);
			assertEquals("BONUS_ACTION", word.get("action_cost"));
			assertEquals(1, m(word.get("slot")).get("slot_level"));
			assertEquals(slotsBefore - 1, slotsLeft(engine, campaign, pc, 1));
			dice.queue(15, 1);
			Map<String, Object> bolt = perform(engine, campaign, enc, pc,
					map("kind", "CAST", "spell", "Fire Bolt", "target", ogre), false);
			assertNull(bolt.get("slot"), "a cantrip after a slot spell expends nothing and is allowed");
			endTurn(engine, campaign, enc, pc);
			endTurn(engine, campaign, enc, ogre);

			// Turn 2: a slot spell with the action, then a second slot spell as a Bonus Action — refused.
			dice.queue(1, 1, 1, 1);
			perform(engine, campaign, enc, pc, map("kind", "CAST", "spell", "Burning Hands", "targets", List.of(ogre)),
					false);
			assertEquals(slotsBefore - 2, slotsLeft(engine, campaign, pc, 1));
			Map<String, List<Map<String, Object>>> before = snapshot(engine, campaign);
			RpgException refused = assertThrows(RpgException.class, () -> perform(engine, campaign, enc, pc,
					map("kind", "CAST", "spell", "Healing Word", "targets", List.of(pc)), false));
			assertEquals(ErrorCode.OPERATION_NOT_ALLOWED, refused.code(), refused.getMessage());
			assertEquals(before, snapshot(engine, campaign), "the refused casting spends no slot and no Bonus Action");
			assertEquals(slotsBefore - 2, slotsLeft(engine, campaign, pc, 1));
		}
	}

	/**
	 * SRD 5.2.1 p.67 "Quickened Spell": "You can't modify a spell in this way if you've already
	 * cast a level 1+ spell on the current turn, nor can you cast a level 1+ spell on this turn
	 * after modifying a spell in this way." Both orders are refused without spending sorcery
	 * points; a cantrip after a Quickened level 1+ spell is allowed.
	 */
	@Test
	void quickenedSpellForbidsAnotherLeveledSpellOnTheSameTurnInEitherOrder() throws Exception {
		try (Engine engine = engine(tempDb("srd-quickened-order"))) {
			ScriptedRollService dice = (ScriptedRollService) engine.roller();
			String campaign = committedCampaign(engine);
			engine.sessions().bootstrap(op(), campaign, null);
			String pc = "character:1";
			levelTo(engine, campaign, pc, 3, List.of("Quickened Spell", "Subtle Spell"));
			// The fixture's cantrips come from Magic Initiate (Light, Mage Hand); Fire Bolt is chosen here as a class cantrip.
			engine.spells().prepare(op(), campaign, pc, List.of("Fire Bolt"),
					List.of("Burning Hands", "Chromatic Orb"));
			String ogre = creature(engine, campaign, "Ogre", "Brawn");
			String enc = encounter(engine, campaign, pc, List.of(ogre));
			assertEquals(3, sorceryPoints(engine, campaign, pc));
			Map<String, Object> quickenedOrb = map("kind", "CAST", "spell", "Chromatic Orb", "target", ogre,
					"metamagic", "Quickened Spell");
			Map<String, Object> hands = map("kind", "CAST", "spell", "Burning Hands", "targets", List.of(ogre));

			// Order 1: a level-1 spell with the action, then Quickened — refused, points untouched.
			dice.queue(1, 1, 1, 1);
			perform(engine, campaign, enc, pc, hands, false);
			Map<String, List<Map<String, Object>>> before = snapshot(engine, campaign);
			RpgException first = assertThrows(RpgException.class,
					() -> perform(engine, campaign, enc, pc, quickenedOrb, false));
			assertEquals(ErrorCode.OPERATION_NOT_ALLOWED, first.code(), first.getMessage());
			assertEquals(before, snapshot(engine, campaign), "a refused Quickened casting spends nothing");
			assertEquals(3, sorceryPoints(engine, campaign, pc));
			endTurn(engine, campaign, enc, pc);
			endTurn(engine, campaign, enc, ogre);

			// Order 2: Quickened first, then another level-1 spell with the action — refused; a cantrip is fine.
			dice.queue(15, 1, 1, 1);
			Map<String, Object> quickened = perform(engine, campaign, enc, pc, quickenedOrb, false);
			assertEquals("BONUS_ACTION", quickened.get("action_cost"));
			assertEquals(1, sorceryPoints(engine, campaign, pc), "Quickened costs 2");
			before = snapshot(engine, campaign);
			RpgException second = assertThrows(RpgException.class,
					() -> perform(engine, campaign, enc, pc, hands, false));
			assertEquals(ErrorCode.OPERATION_NOT_ALLOWED, second.code(), second.getMessage());
			assertEquals(before, snapshot(engine, campaign));
			dice.queue(15, 1);
			Map<String, Object> bolt = perform(engine, campaign, enc, pc,
					map("kind", "CAST", "spell", "Fire Bolt", "target", ogre), false);
			assertNull(bolt.get("slot"), "a cantrip after a Quickened spell is allowed");
			assertEquals(1, sorceryPoints(engine, campaign, pc));
		}
	}

	/**
	 * SRD 5.2.1 p.139 "Heal": "Positive energy washes through the target, restoring 70 Hit Points.
	 * This spell also ends the Blinded, Deafened, and Poisoned conditions on the target. Using a
	 * Higher-Level Spell Slot. The healing increases by 10 for each spell slot level above 6."
	 */
	@Test
	void healAlsoEndsPoisonedAndScalesByTenPerSlotLevelAboveSix() throws Exception {
		try (Engine engine = engine(tempDb("srd-heal"))) {
			String campaign = openCampaign(engine);
			String cleric = (String) engine.characters()
					.createDraft(op(), campaign,
							map("name", "Healer", "species", "Human", "class", "Cleric", "ability_scores",
									map("WIS", 15, "CON", 14, "STR", 13, "DEX", 12, "CHA", 10, "INT", 8), "skills",
									List.of("Insight", "Religion"), "personality", "kind", "background", "Criminal",
									"background_ability_scores", map("CON", 2, "DEX", 1), "species_skill",
									"Animal Handling", "origin_feat", skilled(), "cantrips",
									List.of("Sacred Flame", "Guidance", "Spare the Dying"), "spells",
									List.of("Bless", "Detect Magic", "Cure Wounds", "Guiding Bolt")),
							true)
					.get("character");
			engine.characters().commitDraft(op(), campaign, cleric, null);
			engine.campaigns().commitSetup(op(), campaign, null);
			engine.sessions().bootstrap(op(), campaign, null);
			// Level 13: one level-6 and one level-7 slot.
			levelTo(engine, campaign, cleric, 13, List.of());
			assertEquals(1, slotsLeft(engine, campaign, cleric, 7));
			engine.spells().prepare(op(), campaign, cleric, null, List.of("Heal"));

			// A living target with room for both castings and all three conditions on it.
			String ogre = creature(engine, campaign, "Ogre", "Patient");
			engine.rest().override(op(), campaign, "SET_MAX_HP", ogre, map("set_to", 200), "room to heal", null);
			engine.runtime().applyRuntimeChange(op(), campaign, ogre,
					map("kind", "DAMAGE", "amount", 180, "reason", "badly wounded"));
			for (String condition : List.of("BLINDED", "DEAFENED", "POISONED")) {
				engine.runtime().applyRuntimeChange(op(), campaign, ogre, map("kind", "ADD_CONDITION", "condition",
						condition, "duration", "long rest", "reason", "afflicted"));
			}
			assertTrue(conditionNames(engine, campaign, ogre).containsAll(List.of("BLINDED", "DEAFENED", "POISONED")));
			int hpBefore = hp(engine, campaign, ogre);

			Map<String, Object> heal = engine.spells().cast(op(), campaign, cleric, "Heal", null, List.of(ogre), null);
			assertEquals(70, list(heal.get("targets")).get(0).get("healed"));
			assertEquals(hpBefore + 70, hp(engine, campaign, ogre));
			List<String> conditions = conditionNames(engine, campaign, ogre);
			assertFalse(conditions.contains("BLINDED"), conditions.toString());
			assertFalse(conditions.contains("DEAFENED"), conditions.toString());
			assertFalse(conditions.contains("POISONED"), "Heal also ends Poisoned: " + conditions);

			Map<String, Object> upcast = engine.spells().cast(op(), campaign, cleric, "Heal", 7, List.of(ogre), null);
			assertEquals(7, m(upcast.get("slot")).get("slot_level"));
			assertEquals(80, list(upcast.get("targets")).get(0).get("healed"), "70 + 10 for the level-7 slot");
			assertEquals(hpBefore + 70 + 80, hp(engine, campaign, ogre));
		}
	}

	/**
	 * SRD 5.2.1 p.161 "Shield": "Casting Time: Reaction, which you take when you are hit by an
	 * attack roll or targeted by the Magic Missile spell" and "you take no damage from Magic
	 * Missile." A caster targeted by Magic Missile is offered the reaction; casting it costs a slot
	 * and negates every dart; declining takes the darts.
	 */
	@Test
	void shieldIsOfferedAgainstMagicMissileAndNegatesItsDamage() throws Exception {
		try (Engine engine = engine(tempDb("srd-shield-magic-missile"))) {
			ScriptedRollService dice = (ScriptedRollService) engine.roller();
			String campaign = openCampaign(engine);
			String pc = sorcererDraft(engine, campaign, skilled(), List.of("Shield", "Burning Hands"), true);
			String wizard = wizardDraft(engine, campaign,
					List.of("Magic Missile", "Mage Armor", "Sleep", "Detect Magic"));
			engine.characters().commitDraft(op(), campaign, pc, null);
			engine.characters().commitDraft(op(), campaign, wizard, null);
			engine.campaigns().commitSetup(op(), campaign, null);
			engine.sessions().bootstrap(op(), campaign, null);
			engine.rest().override(op(), campaign, "SET_MAX_HP", pc, map("set_to", 100), "sturdy for the test", null);
			String enc = encounter(engine, campaign, pc, List.of(wizard));
			int slotsBefore = slotsLeft(engine, campaign, pc, 1);
			int hpBefore = hp(engine, campaign, pc);

			// The wizard targets the sorcerer with Magic Missile: Shield is offered before any dart lands.
			endTurn(engine, campaign, enc, pc);
			Map<String, Object> missiles = perform(engine, campaign, enc, wizard,
					map("kind", "CAST", "spell", "Magic Missile", "targets", List.of(pc)), true);
			assertNotNull(missiles.get("pending_choices"),
					"Shield is offered against Magic Missile: " + missiles.keySet());
			Map<String, Object> pending = list(missiles.get("pending_choices")).get(0);
			assertEquals("SHIELD_SPELL", pending.get("kind"));
			assertEquals(pc, pending.get("chooser"));
			assertEquals(hpBefore, hp(engine, campaign, pc), "no dart lands before the decision");
			engine.encounters().resolveChoice(op(), campaign, (String) pending.get("transaction"),
					map("option", "CAST_SHIELD"));
			assertEquals(hpBefore, hp(engine, campaign, pc), "no damage from Magic Missile");
			assertEquals(slotsBefore - 1, slotsLeft(engine, campaign, pc, 1), "Shield spends a slot");
			assertEquals("RUNNING", engine.encounters().encounterState(campaign, enc, 1).get("status"));

			// Next round: declined, the three darts (1d4 + 1 each, scripted 1) deal 6.
			endTurn(engine, campaign, enc, pc);
			dice.queue(1, 1, 1);
			Map<String, Object> again = perform(engine, campaign, enc, wizard,
					map("kind", "CAST", "spell", "Magic Missile", "targets", List.of(pc)), true);
			Map<String, Object> second = list(again.get("pending_choices")).get(0);
			assertEquals("SHIELD_SPELL", second.get("kind"));
			engine.encounters().resolveChoice(op(), campaign, (String) second.get("transaction"),
					map("option", "DECLINE"));
			assertEquals(hpBefore - 6, hp(engine, campaign, pc), "three darts of 2 each");
			assertEquals(slotsBefore - 1, slotsLeft(engine, campaign, pc, 1), "declining spends nothing");
		}
	}

	/**
	 * SRD 5.2.1 p.161 "Shield": the trigger is simply "when you are hit by an attack roll", and
	 * "Until the start of your next turn, you have a +5 bonus to AC, including against the
	 * triggering attack." So the reaction is offered on an Opportunity Attack, on a hit that beats
	 * AC + 5 and on a natural 20 (the caster may want the +5 for the rest of the round), and the +5
	 * persists against later attacks until the caster's next turn starts.
	 */
	@Test
	void shieldIsOfferedOnEveryHitIncludingOpportunityAttacksAndCriticals() throws Exception {
		try (Engine engine = engine(tempDb("srd-shield-triggers"))) {
			ScriptedRollService dice = (ScriptedRollService) engine.roller();
			String campaign = committedCampaign(engine);
			engine.sessions().bootstrap(op(), campaign, null);
			String pc = "character:1";
			levelTo(engine, campaign, pc, 3, List.of("Subtle Spell", "Distant Spell"));
			engine.spells().prepare(op(), campaign, pc, null, List.of("Shield"));
			engine.rest().override(op(), campaign, "SET_MAX_HP", pc, map("set_to", 100), "sturdy for the test", null);
			engine.rest().override(op(), campaign, "SET_ARMOR_CLASS", pc, map("armor_class", 12), "a known AC", null);
			assertEquals(4, slotsLeft(engine, campaign, pc, 1), "four Shields to cast");
			String grubb = creature(engine, campaign, "Bandit", "Grubb");
			String hesk = creature(engine, campaign, "Bandit", "Hesk");
			String enc = encounter(engine, campaign, pc, List.of(grubb, hesk), meleeZones(pc, grubb, hesk));
			Map<String, Object> scimitar = map("kind", "ATTACK", "target", pc, "attack", "Scimitar");

			// Round 1 (c): Grubb's scimitar +3 hits AC 12 on a 10; Shield turns it into a miss and stays up.
			endTurn(engine, campaign, enc, pc);
			dice.queue(10);
			Map<String, Object> swing = perform(engine, campaign, enc, grubb, scimitar, true);
			Map<String, Object> choice = m(swing.get("pending_choice"));
			assertEquals("SHIELD_SPELL", choice.get("kind"));
			Map<String, Object> shielded = engine.encounters().resolveChoice(op(), campaign,
					(String) choice.get("transaction"), map("option", "CAST_SHIELD"));
			assertEquals(Boolean.FALSE, m(shielded.get("attack")).get("hit"));
			assertEquals(17, m(shielded.get("attack")).get("target_armor_class"));
			assertEquals(hesk, m(shielded.get("next_turn")).get("character"));
			dice.queue(13);
			Map<String, Object> later = perform(engine, campaign, enc, hesk, scimitar, true);
			assertEquals(17, later.get("target_armor_class"), "the +5 lasts until the start of the caster's next turn");
			assertEquals(Boolean.FALSE, later.get("hit"), "16 misses AC 17");
			assertEquals(pc, m(later.get("next_turn")).get("character"));
			assertEquals(12, m(sheet(engine, campaign, pc).get("armor_class")).get("value"),
					"the bonus ends at the start of the caster's next turn");

			// Round 2 (a): the sorcerer leaves the bandits' zone; Grubb's Opportunity Attack hits: Shield is offered.
			dice.queue(10);
			Map<String, Object> move = perform(engine, campaign, enc, pc, map("kind", "MOVE", "zone", "far"), false);
			assertNotNull(move.get("pending_choices"),
					"a hit by an Opportunity Attack offers Shield: " + move.get("opportunity_attacks"));
			Map<String, Object> onOpportunity = list(move.get("pending_choices")).stream()
					.filter(c -> "SHIELD_SPELL".equals(c.get("kind")) && pc.equals(c.get("chooser"))).findFirst()
					.orElseThrow();
			Map<String, Object> reacted = engine.encounters().resolveChoice(op(), campaign,
					(String) onOpportunity.get("transaction"), map("option", "CAST_SHIELD"));
			assertEquals(Boolean.FALSE, m(reacted.get("attack")).get("hit"));
			assertEquals(17, m(reacted.get("attack")).get("target_armor_class"));
			for (Map<String, Object> remaining : list(reacted.get("remaining_choices"))) {
				engine.encounters().resolveChoice(op(), campaign, (String) remaining.get("transaction"),
						map("option", "DECLINE"));
			}
			assertEquals(2, slotsLeft(engine, campaign, pc, 1));
			endTurn(engine, campaign, enc, pc);
			endTurn(engine, campaign, enc, grubb);
			endTurn(engine, campaign, enc, hesk);

			// Round 3 (b): a natural 20 still offers Shield; it hits anyway but the +5 is up for the round.
			perform(engine, campaign, enc, pc, map("kind", "MOVE", "x_ft", 25, "y_ft", 25), false);
			endTurn(engine, campaign, enc, pc);
			dice.queue(20);
			Map<String, Object> critical = perform(engine, campaign, enc, grubb, scimitar, true);
			assertNotNull(critical.get("pending_choice"), "a natural 20 still offers Shield: " + critical);
			Map<String, Object> onCritical = engine.encounters().resolveChoice(op(), campaign,
					(String) m(critical.get("pending_choice")).get("transaction"), map("option", "CAST_SHIELD"));
			assertEquals(Boolean.TRUE, m(onCritical.get("attack")).get("hit"), "a natural 20 hits regardless of AC");
			assertEquals(17, m(onCritical.get("attack")).get("target_armor_class"));
			assertEquals(1, slotsLeft(engine, campaign, pc, 1));
			endTurn(engine, campaign, enc, hesk);

			// Round 4 (b): a hit that beats AC + 5 (15 + 3 = 18 vs 12) is still a hit by an attack roll — offered.
			endTurn(engine, campaign, enc, pc);
			dice.queue(15);
			Map<String, Object> heavy = perform(engine, campaign, enc, grubb, scimitar, true);
			assertNotNull(heavy.get("pending_choice"), "a hit beyond AC + 5 still offers Shield: " + heavy);
			Map<String, Object> onHeavy = engine.encounters().resolveChoice(op(), campaign,
					(String) m(heavy.get("pending_choice")).get("transaction"), map("option", "CAST_SHIELD"));
			assertEquals(Boolean.TRUE, m(onHeavy.get("attack")).get("hit"), "18 still hits AC 17");
			assertEquals(17, m(onHeavy.get("attack")).get("target_armor_class"));
			assertEquals(0, slotsLeft(engine, campaign, pc, 1));
		}
	}

	/**
	 * SRD 5.2.1 p.179 "Concentration": "If you take damage, you must succeed on a Constitution
	 * saving throw to maintain Concentration. The DC equals 10 or half the damage taken (round
	 * down), whichever number is higher, up to a maximum DC of 30."
	 */
	@Test
	void concentrationSaveDcIsHalfTheDamageCappedAtThirty() throws Exception {
		try (Engine engine = engine(tempDb("srd-concentration-dc"))) {
			ScriptedRollService dice = (ScriptedRollService) engine.roller();
			String campaign = committedCampaign(engine);
			engine.sessions().bootstrap(op(), campaign, null);
			String pc = "character:1";
			engine.rest().override(op(), campaign, "SET_MAX_HP", pc, map("set_to", 200), "sturdy for the test", null);
			engine.spells().cast(op(), campaign, pc, "Detect Magic", null, null, null);
			assertEquals(1,
					((List<?>) m(sheet(engine, campaign, pc).get("spellcasting")).get("concentrating_on")).size());

			dice.queue(20);
			Map<String, Object> thirty = engine.runtime().applyRuntimeChange(op(), campaign, pc,
					map("kind", "DAMAGE", "amount", 30, "reason", "a heavy blow"));
			assertEquals(15, m(thirty.get("concentration")).get("dc"), "half of 30");
			assertEquals(Boolean.TRUE, m(thirty.get("concentration")).get("kept"));

			dice.queue(20);
			Map<String, Object> seventy = engine.runtime().applyRuntimeChange(op(), campaign, pc,
					map("kind", "DAMAGE", "amount", 70, "reason", "a crushing blow"));
			assertEquals(30, m(seventy.get("concentration")).get("dc"), "half of 70 is 35, capped at 30");
			assertTrue(dice.exhausted());
		}
	}

	/**
	 * SRD 5.2.1 p.67 "Extended Spell": "If the affected spell requires Concentration, you have
	 * Advantage on any saving throw you make to maintain that Concentration."
	 */
	@Test
	void extendedSpellGrantsAdvantageOnConcentrationSaves() throws Exception {
		try (Engine engine = engine(tempDb("srd-extended-concentration"))) {
			ScriptedRollService dice = (ScriptedRollService) engine.roller();
			String campaign = committedCampaign(engine);
			engine.sessions().bootstrap(op(), campaign, null);
			String pc = "character:1";
			levelTo(engine, campaign, pc, 3, List.of("Extended Spell", "Subtle Spell"));
			Map<String, Object> cast = engine.spells().cast(op(), campaign, pc, "Detect Magic", null, null,
					map("metamagic", "Extended Spell"));
			assertEquals(List.of("Extended Spell"), m(cast.get("metamagic")).get("options"));
			assertEquals(2, sorceryPoints(engine, campaign, pc));

			dice.queue(20, 20);
			Map<String, Object> hurt = engine.runtime().applyRuntimeChange(op(), campaign, pc,
					map("kind", "DAMAGE", "amount", 4, "reason", "a glancing blow"));
			Map<String, Object> save = m(m(hurt.get("concentration")).get("roll"));
			assertTrue(String.valueOf(save.get("expression")).startsWith("2d20kh1"),
					"Advantage on the Concentration save: " + save);
			assertEquals(Boolean.TRUE, m(hurt.get("concentration")).get("kept"));
			assertTrue(dice.exhausted(), "two d20s rolled, the higher kept");
		}
	}

	/**
	 * SRD 5.2.1 p.67 "Heightened Spell": "you can spend 2 Sorcery Points to give one target of the
	 * spell Disadvantage on saves against the spell." Saves, plural: the repeat save Sleep grants
	 * at the end of the target's next turn is made with Disadvantage too.
	 */
	@Test
	void heightenedSpellAppliesDisadvantageToRepeatSaves() throws Exception {
		try (Engine engine = engine(tempDb("srd-heightened-repeat"))) {
			ScriptedRollService dice = (ScriptedRollService) engine.roller();
			String campaign = committedCampaign(engine);
			engine.sessions().bootstrap(op(), campaign, null);
			String pc = "character:1";
			levelTo(engine, campaign, pc, 3, List.of("Heightened Spell", "Subtle Spell"));
			engine.spells().prepare(op(), campaign, pc, null, List.of("Sleep"));
			String bandit = creature(engine, campaign, "Bandit", "Grubb");
			String enc = encounter(engine, campaign, pc, List.of(bandit));

			// The first save: 2d20 keep lowest (18, 3 → 3) fails.
			dice.queue(18, 3);
			Map<String, Object> sleep = perform(engine, campaign, enc, pc,
					map("kind", "CAST", "spell", "Sleep", "targets", List.of(bandit), "metamagic", "Heightened Spell"),
					true);
			Map<String, Object> target = list(sleep.get("targets")).get(0);
			assertEquals(Boolean.TRUE, target.get("heightened"));
			assertTrue(String.valueOf(m(target.get("save_roll")).get("expression")).startsWith("2d20kl1"));
			assertEquals(Boolean.FALSE, target.get("saved"));
			assertEquals(1, sorceryPoints(engine, campaign, pc), "Heightened costs 2");

			// The repeat save at the end of the bandit's turn is also made with Disadvantage: 18, 3 → 3 fails again.
			dice.queue(18, 3);
			Map<String, Object> ended = perform(engine, campaign, enc, bandit, map("kind", "END_TURN"), true);
			Map<String, Object> repeat = list(ended.get("repeat_saves")).get(0);
			assertEquals("Sleep", repeat.get("spell"));
			assertTrue(String.valueOf(m(repeat.get("roll")).get("expression")).startsWith("2d20kl1"),
					"Disadvantage on the repeat save: " + repeat);
			assertEquals(Boolean.FALSE, repeat.get("saved"));
			assertEquals("UNCONSCIOUS", repeat.get("escalated_to"));
			assertTrue(dice.exhausted());
		}
	}

	/**
	 * SRD 5.2.1 p.67 "Empowered Spell": "When you roll damage for a spell, you can spend 1 Sorcery
	 * Point to reroll a number of the damage dice up to your Charisma modifier (minimum of one),
	 * and you must use the new rolls." The point is spent when damage is rolled, so a missed attack
	 * spell costs nothing; and "up to" makes rerolling optional: the API chosen here is that the
	 * engine decides at damage time and rerolls only dice that can improve, so with every die at
	 * its maximum nothing is rerolled, no extra die is consumed and no point is spent.
	 */
	@Test
	void empoweredSpellReRollsUpToTheModifierAndCostsNothingOnAMiss() throws Exception {
		try (Engine engine = engine(tempDb("srd-empowered"))) {
			ScriptedRollService dice = (ScriptedRollService) engine.roller();
			String campaign = committedCampaign(engine);
			engine.sessions().bootstrap(op(), campaign, null);
			String pc = "character:1";
			levelTo(engine, campaign, pc, 3, List.of("Empowered Spell", "Subtle Spell"));
			engine.spells().prepare(op(), campaign, pc, null, List.of("Chromatic Orb"));
			String bandit = creature(engine, campaign, "Bandit", "Grubb");
			assertEquals(3, sorceryPoints(engine, campaign, pc));

			// (a) A natural 1 misses: no damage is rolled, so nothing is rerolled and no point is spent.
			dice.queue(1);
			Map<String, Object> miss = engine.spells().cast(op(), campaign, pc, "Chromatic Orb", null, List.of(bandit),
					map("metamagic", "Empowered Spell"));
			assertEquals(Boolean.FALSE, list(miss.get("targets")).get(0).get("hit"));
			assertTrue(dice.exhausted(), "a miss rolls no damage dice");
			assertEquals(0, m(miss.get("metamagic")).get("points_spent"), "no damage roll, no point: " + miss);
			assertEquals(3, sorceryPoints(engine, campaign, pc));

			// (b) A hit with every die at its maximum: rerolling is "up to", so nothing is rerolled.
			dice.queue(15, 8, 8, 8);
			Map<String, Object> hit = engine.spells().cast(op(), campaign, pc, "Chromatic Orb", null, List.of(bandit),
					map("metamagic", "Empowered Spell"));
			Map<String, Object> t = list(hit.get("targets")).get(0);
			assertEquals(Boolean.TRUE, t.get("hit"));
			assertEquals(24, t.get("damage"), t.toString());
			assertEquals(List.of(8, 8, 8), m(list(t.get("breakdown")).get(0).get("roll")).get("dice"));
			assertTrue(dice.exhausted(), "no die is rerolled when none can improve");
			assertEquals(0, m(hit.get("metamagic")).get("points_spent"), "nothing rerolled, nothing spent");
			assertEquals(3, sorceryPoints(engine, campaign, pc));
		}
	}

	/**
	 * SRD 5.2.1 p.163 "Sleep": "... have the Incapacitated condition until the end of its next
	 * turn, at which point it must repeat the save. If the target fails the second save, the target
	 * has the Unconscious condition for the duration. The spell ends on a target if it takes damage
	 * or someone within 5 feet of it takes an action to shake it out of the spell's effect." One
	 * repeat save, not one per turn; damage ends the spell on that target.
	 */
	@Test
	void sleepRepeatsItsSaveOnceAndEndsWhenTheTargetTakesDamage() throws Exception {
		try (Engine engine = engine(tempDb("srd-sleep"))) {
			ScriptedRollService dice = (ScriptedRollService) engine.roller();
			String campaign = committedCampaign(engine);
			engine.sessions().bootstrap(op(), campaign, null);
			String pc = "character:1";
			engine.spells().prepare(op(), campaign, pc, null, List.of("Sleep"));
			String bandit = creature(engine, campaign, "Bandit", "Grubb");
			String enc = encounter(engine, campaign, pc, List.of(bandit));

			// Fail, fail: Incapacitated, then Unconscious at the end of the bandit's next turn.
			dice.queue(1);
			Map<String, Object> sleep = perform(engine, campaign, enc, pc,
					map("kind", "CAST", "spell", "Sleep", "targets", List.of(bandit)), true);
			assertEquals(Boolean.FALSE, list(sleep.get("targets")).get(0).get("saved"));
			dice.queue(1);
			Map<String, Object> ended = perform(engine, campaign, enc, bandit, map("kind", "END_TURN"), true);
			Map<String, Object> repeat = list(ended.get("repeat_saves")).get(0);
			assertEquals(Boolean.FALSE, repeat.get("saved"));
			assertEquals("UNCONSCIOUS", repeat.get("escalated_to"));
			assertTrue(conditionNames(engine, campaign, bandit).contains("UNCONSCIOUS"));
			assertTrue(dice.exhausted());

			// The following turn: no further save. A sentinel die proves nothing was rolled.
			endTurn(engine, campaign, enc, pc);
			dice.queue(7);
			Map<String, Object> next = perform(engine, campaign, enc, bandit, map("kind", "END_TURN"), true);
			assertNull(next.get("repeat_saves"),
					"the save is repeated once, not every turn: " + next.get("repeat_saves"));
			assertFalse(dice.exhausted(), "no die consumed by a save that does not exist");
			assertEquals(7, dice.roll("1d20").total());
			assertTrue(conditionNames(engine, campaign, bandit).contains("UNCONSCIOUS"));

			// Damage ends the spell on the target: no longer Unconscious or Incapacitated.
			engine.runtime().applyRuntimeChange(op(), campaign, bandit,
					map("kind", "DAMAGE", "amount", 1, "reason", "a kick"));
			List<String> conditions = conditionNames(engine, campaign, bandit);
			assertFalse(conditions.contains("UNCONSCIOUS"), "damage wakes the sleeper: " + conditions);
			assertFalse(conditions.contains("INCAPACITATED"), "the Sleep effect ended: " + conditions);
			assertTrue(list(sheet(engine, campaign, bandit).get("effects")).stream()
					.noneMatch(e -> String.valueOf(e.get("source")).startsWith("Sleep")), "no Sleep effect remains");
		}
	}
}
