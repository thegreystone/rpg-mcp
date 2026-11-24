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
import se.hirt.mcp.rpg.protocol.RpgException;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static se.hirt.mcp.rpg.TestCampaigns.*;

/**
 * Combat and equipment rules encoded from the SRD 5.2.1 text, each test quoting the operative
 * sentence and page: knocking a creature out, ranged attacks in close combat, armor training and
 * the Shield, untrained armor, weapon proficiency, the Unarmed Strike, heavy armor's Strength
 * requirement, potions as a Bonus Action, group initiative and initiative ties. The assertions
 * follow the book, not the engine; where the engine deviates these tests are red until it is fixed.
 */
class SrdCombatAndEquipmentTest {

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

	private static int ac(Engine engine, String campaign, String character) {
		return ((Number) m(sheet(engine, campaign, character).get("armor_class")).get("value")).intValue();
	}

	private static int modifier(Engine engine, String campaign, String character, String ability) {
		return ((Number) m(m(sheet(engine, campaign, character).get("abilities")).get(ability)).get("modifier"))
				.intValue();
	}

	private static boolean hasCondition(Map<String, Object> sheet, String condition) {
		return list(sheet.get("conditions")).stream().anyMatch(c -> condition.equals(c.get("condition")));
	}

	/** Grants one item (QUEST provenance: not audited) and returns its inventory entry ref. */
	private static String grant(Engine engine, String campaign, String character, String item) {
		return (String) list(engine.inventory()
				.grantLoot(op(), campaign, character, List.of(map("item", item)), null, "QUEST", null).get("granted"))
				.get(0).get("entry");
	}

	private static String materialize(Engine engine, String campaign, String creature) {
		return (String) engine.runtime().materialize(op(), campaign, creature, null, null, null, null, null, false)
				.get("character");
	}

	/**
	 * The PC acts first: a 20 for the PC, a 1 for the single foe; no hidden tie-break die is rolled
	 * (SRD 5.2.1 "Initiative").
	 */
	private static String duel(Engine engine, ScriptedRollService dice, String campaign, String pc, String foe) {
		dice.queue(20, 1);
		Map<String, Object> started = engine.encounters().start(op(), campaign,
				map("party", List.of(pc), "foes", List.of(foe)), null, meleeZones(pc, foe), null, null, null);
		assertEquals(pc, m(started.get("turn")).get("character"));
		assertTrue(dice.exhausted(), "the scripted initiative has no leftover dice");
		return (String) started.get("encounter");
	}

	/**
	 * Independent Action comparisons each use a fresh player turn; the foe passes without a roll.
	 */
	private static void nextDuelTurn(Engine engine, String campaign, String encounter, String pc, String foe) {
		engine.encounters().perform(op(), campaign, encounter, pc, map("kind", "END_TURN"), true);
		engine.encounters().perform(op(), campaign, encounter, foe, map("kind", "END_TURN"), true);
	}

	/**
	 * A committed campaign whose player character is a level-1 Sorcerer with explicit ability
	 * scores (STR 8, DEX 15 after the background) and Fire Bolt among its cantrips, like
	 * SorcererTest's fixture; the EXECUTION_EXAMPLE Sorcerer of {@link TestCampaigns} has no attack
	 * cantrip.
	 */
	private static String sorcererWithFireBolt(Engine engine) {
		String campaign = (String) engine.campaigns().create(op(), null, null).get("campaign");
		engine.campaigns().updateSetup(op(), campaign, null,
				map("content_profile", "PEGI_16", "experience", "SURPRISE_ME", "rules", Map.of(), "continuation",
						"CHECKPOINT", "party", "SURPRISE_ME", "adventure",
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
		return campaign;
	}

	/**
	 * A committed campaign whose player character is a level-1 Orc Fighter (STR 15) with the gold
	 * option.
	 */
	private static String fighterCampaign(Engine engine) {
		String campaign = (String) engine.campaigns().create(op(), null, null).get("campaign");
		engine.campaigns().updateSetup(op(), campaign, null,
				map("content_profile", "PEGI_16", "experience", "SURPRISE_ME", "rules", Map.of(), "continuation",
						"CHECKPOINT", "party", "SURPRISE_ME", "adventure",
						map("premise", "x", "opening_location", "Road", "immediate_goal", "y")));
		String pc = (String) engine.characters().createDraft(op(), campaign,
				map("name", "Brakk", "species", "Orc", "class", "Fighter", "ability_scores",
						map("STR", 15, "CON", 14, "DEX", 13, "WIS", 12, "INT", 10, "CHA", 8), "background", "Criminal",
						"background_ability_scores", map("DEX", 2, "CON", 1), "skills",
						List.of("Athletics", "Perception"), "personality", "Loud.", "starting_equipment", "C"),
				true).get("character");
		engine.characters().commitDraft(op(), campaign, pc, null);
		engine.campaigns().commitSetup(op(), campaign, null);
		engine.sessions().bootstrap(op(), campaign, null);
		return campaign;
	}

	/**
	 * SRD 5.2.1 p.17 "Knocking Out a Creature": "When you would reduce a creature to 0 Hit Points
	 * with a melee attack, you can instead reduce the creature to 1 Hit Point and give it the
	 * Unconscious condition. It then starts a Short Rest, at the end of which that condition ends
	 * on it. The condition ends early if the creature regains any Hit Points or if someone takes an
	 * action to administer first aid to it, making a successful DC 10 Wisdom (Medicine) check."
	 */
	@Test
	void knockingOutLeavesTheCreatureAtOneHitPointAndUnconsciousForAShortRest() throws Exception {
		try (Engine engine = engine(tempDb("srd-knock-out"))) {
			ScriptedRollService dice = (ScriptedRollService) engine.roller();
			String campaign = committedCampaign(engine);
			engine.sessions().bootstrap(op(), campaign, null);
			String pc = "character:1";
			String bandit = materialize(engine, campaign, "Bandit");
			grant(engine, campaign, pc, "Club");
			String enc = duel(engine, dice, campaign, pc, bandit);
			int banditHp = ((Number) m(sheet(engine, campaign, bandit).get("hp")).get("current")).intValue();
			engine.runtime().applyRuntimeChange(op(), campaign, bandit,
					map("kind", "DAMAGE", "amount", banditHp - 1, "damage_type", "bludgeoning", "reason", "test"));

			// A nonlethal club hit (natural 19, 4 damage) would reduce the bandit to 0: it goes to 1 HP instead.
			dice.queue(19, 4);
			Map<String, Object> ko = engine.encounters().perform(op(), campaign, enc, pc,
					map("kind", "ATTACK", "target", bandit, "weapon", "Club", "nonlethal", true), false);
			assertEquals(Boolean.TRUE, ko.get("hit"));
			assertEquals(Boolean.TRUE, ko.get("knocked_out"));
			assertEquals(1, ko.get("hp_after"), "reduced to 1 Hit Point, not 0: " + ko);
			assertEquals("ALIVE", ko.get("life_state"), "a knocked-out creature is not dying");
			Map<String, Object> knocked = sheet(engine, campaign, bandit);
			assertEquals(1, m(knocked.get("hp")).get("current"));
			assertEquals("ALIVE", knocked.get("life_state"));
			assertNull(knocked.get("death_saves"), "no death saves and no 'stable' flag: it never dropped to 0");
			Map<String, Object> unconscious = list(knocked.get("conditions")).stream()
					.filter(c -> "UNCONSCIOUS".equals(c.get("condition"))).findFirst()
					.orElseThrow(() -> new AssertionError("Unconscious condition missing: " + knocked));
			Map<String, Object> duration = m(unconscious.get("duration"));
			assertNotNull(duration, "the condition lasts for the Short Rest the creature starts");
			String text = String.valueOf(duration).toLowerCase();
			assertTrue("SHORT_REST".equals(duration.get("until")) || text.contains("short rest"),
					"ends at the end of a Short Rest (one hour), not after 1d4 hours: " + duration);
			assertFalse(text.contains("1d4"), "the 2014 'wakes after 1d4 hours' wording is not the SRD 5.2.1 rule");

			// At the end of the Short Rest the condition ends on it.
			engine.encounters().end(op(), campaign, enc, "PARTY_VICTORY", "subdued");
			engine.rest().rest(op(), campaign, "SHORT", null, List.of(bandit));
			Map<String, Object> rested = sheet(engine, campaign, bandit);
			assertFalse(hasCondition(rested, "UNCONSCIOUS"), "the Short Rest ended the condition: " + rested);
			assertEquals("ALIVE", rested.get("life_state"));
			assertTrue(((Number) m(rested.get("hp")).get("current")).intValue() >= 1);
		}
	}

	/**
	 * SRD 5.2.1 p.15 "Ranged Attacks in Close Combat": "When you make a ranged attack roll with a
	 * weapon, a spell, or some other means, you have Disadvantage on the roll if you are within 5
	 * feet of an enemy who can see you and doesn't have the Incapacitated condition." The engine
	 * already equates a shared encounter zone with "within 5 feet" (Combat.withinFiveFeet).
	 */
	@Test
	void rangedAttacksWithinFiveFeetOfAnEnemyHaveDisadvantage() throws Exception {
		try (Engine engine = engine(tempDb("srd-ranged-close"))) {
			ScriptedRollService dice = (ScriptedRollService) engine.roller();
			String campaign = sorcererWithFireBolt(engine);
			String pc = "character:1";
			String bandit = materialize(engine, campaign, "Bandit");
			grant(engine, campaign, pc, "Shortbow");
			// Three shots are fired below; the single-item grant helper would leave the quiver empty after the first.
			engine.inventory().grantLoot(op(), campaign, pc, List.of(map("item", "Arrow", "quantity", 20)), null,
					"QUEST", null);
			String enc = duel(engine, dice, campaign, pc, bandit);

			// Both start in the same zone: the shot is made within 5 feet of an enemy → 2d20kl1 keeps the 17. The
			// damage dice below are 1s so the Bandit (11 HP) survives three hits of 1 + DEX before the last shot.
			dice.queue(17, 19, 1);
			Map<String, Object> close = engine.encounters().perform(op(), campaign, enc, pc,
					map("kind", "ATTACK", "target", bandit, "weapon", "Shortbow"), false);
			String expression = String.valueOf(m(close.get("attack_roll")).get("expression"));
			assertTrue(expression.startsWith("2d20kl1"), "disadvantage next to an enemy: " + expression);
			assertEquals("DISADVANTAGE", close.get("advantage"));
			assertEquals(List.of(17), m(close.get("attack_roll")).get("dice"));
			assertEquals(Boolean.TRUE, close.get("hit"));
			assertTrue(dice.exhausted(), "two attack dice and one damage die were consumed");

			nextDuelTurn(engine, campaign, enc, pc, bandit);
			// Step away (the NPC bandit takes its opportunity attack: natural 1, a miss) and shoot from the other zone.
			dice.queue(1);
			Map<String, Object> move = engine.encounters().perform(op(), campaign, enc, pc,
					map("kind", "MOVE", "x_ft", 35, "y_ft", 25), false);
			assertEquals(1, list(move.get("opportunity_attacks")).size());
			dice.queue(15, 1);
			Map<String, Object> far = engine.encounters().perform(op(), campaign, enc, pc,
					map("kind", "ATTACK", "target", bandit, "weapon", "Shortbow"), false);
			assertTrue(String.valueOf(m(far.get("attack_roll")).get("expression")).startsWith("1d20"),
					"no enemy within 5 feet: " + far.get("attack_roll"));
			assertEquals("NONE", far.get("advantage"));
			assertTrue(dice.exhausted());

			nextDuelTurn(engine, campaign, enc, pc, bandit);
			// Returning beside the bandit does not provoke; a spell attack is a ranged attack too.
			engine.encounters().perform(op(), campaign, enc, pc, map("kind", "MOVE", "x_ft", 25, "y_ft", 25), false);
			dice.queue(17, 19, 1);
			Map<String, Object> cast = engine.encounters().perform(op(), campaign, enc, pc,
					map("kind", "CAST", "spell", "Fire Bolt", "targets", List.of(bandit)), false);
			Map<String, Object> bolt = list(cast.get("targets")).get(0);
			assertEquals(Boolean.TRUE, bolt.get("hit"), bolt.toString());
			assertTrue(String.valueOf(m(bolt.get("attack_roll")).get("expression")).startsWith("2d20kl1"),
					"a ranged spell attack within 5 feet of an enemy: " + bolt.get("attack_roll"));
			assertEquals(List.of(17), m(bolt.get("attack_roll")).get("dice"));
			assertTrue(dice.exhausted());

			// An Incapacitated adjacent enemy does not impose it.
			nextDuelTurn(engine, campaign, enc, pc, bandit);
			engine.runtime().applyRuntimeChange(op(), campaign, bandit,
					map("kind", "ADD_CONDITION", "condition", "INCAPACITATED", "reason", "test"));
			dice.queue(15, 4);
			Map<String, Object> vsIncapacitated = engine.encounters().perform(op(), campaign, enc, pc,
					map("kind", "ATTACK", "target", bandit, "weapon", "Shortbow"), false);
			assertTrue(String.valueOf(m(vsIncapacitated.get("attack_roll")).get("expression")).startsWith("1d20"),
					"the only adjacent enemy is Incapacitated: " + vsIncapacitated.get("attack_roll"));
			assertEquals("NONE", vsIncapacitated.get("advantage"));
			assertTrue(dice.exhausted());
		}
	}

	/**
	 * SRD 5.2.1 p.92 "Shield": "You gain the Armor Class benefit of a Shield only if you have
	 * training with it." Sorcerer p.64 "Armor Training: None"; Fighter p.47 "Armor Training: Light,
	 * Medium, and Heavy armor and Shields".
	 */
	@Test
	void anUntrainedCharacterGainsNoArmorClassFromAShield() throws Exception {
		try (Engine engine = engine(tempDb("srd-shield-untrained"))) {
			String campaign = committedCampaign(engine);
			engine.sessions().bootstrap(op(), campaign, null);
			String pc = "character:1";
			int dexMod = modifier(engine, campaign, pc, "DEX");
			assertEquals(10 + dexMod, ac(engine, campaign, pc), "unarmored Sorcerer");
			Map<String, Object> shield = engine.inventory().trade(op(), campaign, pc, "BUY", "srd5e:item/shield", null,
					1, null, null, null);
			Map<String, Object> equipped = engine.inventory().equip(op(), campaign, pc, (String) shield.get("entry"),
					true);
			assertEquals(10 + dexMod, m(equipped.get("armor_class")).get("value"),
					"no armor training: the Shield gives no AC: " + equipped.get("armor_class"));
			assertEquals(10 + dexMod, ac(engine, campaign, pc));
		}
		try (Engine engine = engine(tempDb("srd-shield-trained"))) {
			String campaign = fighterCampaign(engine);
			String pc = "character:1";
			int dexMod = modifier(engine, campaign, pc, "DEX");
			assertEquals(10 + dexMod, ac(engine, campaign, pc), "the gold option: no armor yet");
			Map<String, Object> shield = engine.inventory().trade(op(), campaign, pc, "BUY", "srd5e:item/shield", null,
					1, null, null, null);
			engine.inventory().equip(op(), campaign, pc, (String) shield.get("entry"), true);
			assertEquals(10 + dexMod + 2, ac(engine, campaign, pc), "Fighters are trained with Shields: +2");
		}
	}

	/**
	 * SRD 5.2.1 "Advantage and Disadvantage": "If circumstances cause a roll to have both Advantage
	 * and Disadvantage, the roll has neither of them, and you roll one d20. This is true even if
	 * multiple circumstances impose Disadvantage and only one grants Advantage or vice versa." The
	 * GM's declaration, untrained armor and the actor's conditions are decided together.
	 */
	@Test
	void everyAdvantageSourceCancelsAgainstEveryDisadvantageSourceOnChecksAndSaves() throws Exception {
		try (Engine engine = engine(tempDb("srd-three-sources"))) {
			ScriptedRollService dice = (ScriptedRollService) engine.roller();
			String campaign = sorcererWithFireBolt(engine);
			String pc = "character:1";
			engine.inventory().equip(op(), campaign, pc, grant(engine, campaign, pc, "Chain Mail"), true);
			engine.runtime().applyRuntimeChange(op(), campaign, pc,
					map("kind", "ADD_CONDITION", "condition", "POISONED", "reason", "bad stew"));

			// The GM's Advantage against two Disadvantage sources (the untrained mail, Poisoned): one plain d20.
			dice.queue(9);
			Map<String, Object> plain = engine.checks().resolveCheck(op(), campaign, pc, "ABILITY_CHECK", "DEX", null,
					10, "ADVANTAGE", "three sources");
			assertEquals("NONE", plain.get("advantage"), plain.toString());
			assertTrue(String.valueOf(m(plain.get("roll")).get("expression")).startsWith("1d20"), plain.toString());
			assertTrue(String.valueOf(plain.get("advantage_source")).contains("cancel"), plain.toString());
			assertTrue(dice.exhausted());

			// The GM's Disadvantage with the same two: Disadvantage, not a plain roll.
			dice.queue(6, 14);
			Map<String, Object> worse = engine.checks().resolveCheck(op(), campaign, pc, "ABILITY_CHECK", "DEX", null,
					10, "DISADVANTAGE", "three sources");
			assertEquals("DISADVANTAGE", worse.get("advantage"), worse.toString());
			assertEquals(6, worse.get("natural"));
			assertTrue(dice.exhausted());

			// A Dexterity saving throw while Restrained (Disadvantage) in the untrained mail (Disadvantage) with the
			// GM's Advantage: again one plain d20.
			engine.runtime().applyRuntimeChange(op(), campaign, pc,
					map("kind", "REMOVE_CONDITION", "condition", "POISONED", "reason", "antitoxin"));
			engine.runtime().applyRuntimeChange(op(), campaign, pc,
					map("kind", "ADD_CONDITION", "condition", "RESTRAINED", "reason", "netted"));
			dice.queue(11);
			Map<String, Object> save = engine.checks().resolveCheck(op(), campaign, pc, "SAVING_THROW", "DEX", null, 10,
					"ADVANTAGE", "three sources");
			assertEquals("NONE", save.get("advantage"), save.toString());
			assertTrue(String.valueOf(m(save.get("roll")).get("expression")).startsWith("1d20"), save.toString());
			assertTrue(dice.exhausted());
		}
	}

	/**
	 * SRD 5.2.1 p.92 "Armor Training" (and Rules Glossary p.177): "If you wear Light, Medium, or
	 * Heavy armor and lack training with it, you have Disadvantage on any D20 Test that involves
	 * Strength or Dexterity, and you can't cast spells." Sorcerer p.64 "Armor Training: None", so
	 * even Light armor is untrained.
	 */
	@Test
	void untrainedArmorImposesDisadvantageOnStrengthAndDexterityTestsAndBlocksSpellcasting() throws Exception {
		try (Engine engine = engine(tempDb("srd-untrained-armor"))) {
			ScriptedRollService dice = (ScriptedRollService) engine.roller();
			String campaign = sorcererWithFireBolt(engine);
			String pc = "character:1";
			String bandit = materialize(engine, campaign, "Bandit");
			String leather = grant(engine, campaign, pc, "Leather Armor");
			String mail = grant(engine, campaign, pc, "Chain Mail");
			grant(engine, campaign, pc, "Club");

			for (String armor : List.of(leather, mail)) {
				engine.inventory().equip(op(), campaign, pc, armor, true);
				String name = String.valueOf(sheet(engine, campaign, pc).get("armor_class"));
				// A Dexterity check: 2d20kl1 keeps the 7.
				dice.queue(7, 18);
				Map<String, Object> dex = engine.checks().resolveCheck(op(), campaign, pc, "ABILITY_CHECK", "DEX", null,
						10, null, "untrained armor");
				assertEquals("DISADVANTAGE", dex.get("advantage"), name + ": a Dexterity test");
				assertTrue(String.valueOf(m(dex.get("roll")).get("expression")).startsWith("2d20kl1"), name);
				assertEquals(7, dex.get("natural"));
				assertTrue(dice.exhausted());
				// A Wisdom check is unaffected.
				dice.queue(12);
				Map<String, Object> wis = engine.checks().resolveCheck(op(), campaign, pc, "ABILITY_CHECK", "WIS", null,
						10, null, "untrained armor");
				assertEquals("NONE", wis.get("advantage"), name + ": a Wisdom test");
				assertTrue(String.valueOf(m(wis.get("roll")).get("expression")).startsWith("1d20"), name);
				assertTrue(dice.exhausted());
				// And no spells: the refusal is a rule, so OPERATION_NOT_ALLOWED (a validation failure would also do).
				RpgException noCasting = assertThrows(RpgException.class,
						() -> engine.spells().cast(op(), campaign, pc, "Fire Bolt", null, List.of(bandit), null),
						name + ": you can't cast spells");
				assertTrue(List.of(ErrorCode.OPERATION_NOT_ALLOWED, ErrorCode.VALIDATION_FAILED)
						.contains(noCasting.code()), noCasting.code() + ": " + noCasting.getMessage());
				engine.inventory().equip(op(), campaign, pc, armor, false);
			}

			// In combat, in the Chain Mail: a Strength-based attack roll has Disadvantage and Fire Bolt is refused.
			// Initiative is a Dexterity check, so the untrained mail gives it Disadvantage too: the PC's roll takes
			// two d20s (this 20 and the duel's 20) before the bandit's 1.
			engine.inventory().equip(op(), campaign, pc, mail, true);
			dice.queue(20);
			String enc = duel(engine, dice, campaign, pc, bandit);
			dice.queue(17, 19, 3);
			Map<String, Object> swing = engine.encounters().perform(op(), campaign, enc, pc,
					map("kind", "ATTACK", "target", bandit, "weapon", "Club"), false);
			assertTrue(String.valueOf(m(swing.get("attack_roll")).get("expression")).startsWith("2d20kl1"),
					"a Strength attack roll in untrained armor: " + swing.get("attack_roll"));
			assertEquals("DISADVANTAGE", swing.get("advantage"));
			assertEquals(List.of(17), m(swing.get("attack_roll")).get("dice"));
			assertTrue(dice.exhausted());
			RpgException noCasting = assertThrows(RpgException.class, () -> engine.encounters().perform(op(), campaign,
					enc, pc, map("kind", "CAST", "spell", "Fire Bolt", "targets", List.of(bandit)), false));
			assertTrue(List.of(ErrorCode.OPERATION_NOT_ALLOWED, ErrorCode.VALIDATION_FAILED).contains(noCasting.code()),
					noCasting.code() + ": " + noCasting.getMessage());
		}
	}

	/**
	 * SRD 5.2.1 p.9 "Weapons": "If you have proficiency with a weapon, you add your Proficiency
	 * Bonus to attack rolls you make with it." Sorcerer p.64 "Weapon Proficiencies: Simple
	 * weapons"; Fighter p.47 "Weapon Proficiencies: Simple and Martial weapons".
	 */
	@Test
	void weaponProficiencyGatesTheProficiencyBonusOnAttackRolls() throws Exception {
		try (Engine engine = engine(tempDb("srd-weapon-proficiency-sorcerer"))) {
			ScriptedRollService dice = (ScriptedRollService) engine.roller();
			String campaign = committedCampaign(engine);
			engine.sessions().bootstrap(op(), campaign, null);
			String pc = "character:1";
			String bandit = materialize(engine, campaign, "Bandit");
			grant(engine, campaign, pc, "Dagger");
			grant(engine, campaign, pc, "Longsword");
			int strMod = modifier(engine, campaign, pc, "STR");
			int dexMod = modifier(engine, campaign, pc, "DEX");
			int proficiency = ((Number) sheet(engine, campaign, pc).get("proficiency_bonus")).intValue();
			assertEquals(2, proficiency, "level 1");
			String enc = duel(engine, dice, campaign, pc, bandit);

			// Dagger: a Simple weapon, Finesse (the better of STR and DEX) + the Proficiency Bonus.
			dice.queue(19, 2);
			Map<String, Object> dagger = engine.encounters().perform(op(), campaign, enc, pc,
					map("kind", "ATTACK", "target", bandit, "weapon", "Dagger"), false);
			assertEquals(Boolean.TRUE, dagger.get("hit"));
			assertEquals(Math.max(strMod, dexMod) + proficiency, m(dagger.get("attack_roll")).get("modifier"),
					"a Sorcerer is proficient with Simple weapons");
			assertTrue(dice.exhausted());
			// Longsword: a Martial weapon the Sorcerer lacks proficiency with: Strength only.
			nextDuelTurn(engine, campaign, enc, pc, bandit);
			dice.queue(19, 3);
			Map<String, Object> longsword = engine.encounters().perform(op(), campaign, enc, pc,
					map("kind", "ATTACK", "target", bandit, "weapon", "Longsword"), false);
			assertEquals(strMod, m(longsword.get("attack_roll")).get("modifier"),
					"no Proficiency Bonus without proficiency: " + longsword.get("attack_roll"));
			assertEquals(19 + strMod, m(longsword.get("attack_roll")).get("total"));
		}
		try (Engine engine = engine(tempDb("srd-weapon-proficiency-fighter"))) {
			ScriptedRollService dice = (ScriptedRollService) engine.roller();
			String campaign = fighterCampaign(engine);
			String pc = "character:1";
			String bandit = materialize(engine, campaign, "Bandit");
			grant(engine, campaign, pc, "Longsword");
			int strMod = modifier(engine, campaign, pc, "STR");
			assertEquals(2, strMod, "STR 15");
			String enc = duel(engine, dice, campaign, pc, bandit);
			dice.queue(19, 5);
			Map<String, Object> longsword = engine.encounters().perform(op(), campaign, enc, pc,
					map("kind", "ATTACK", "target", bandit, "weapon", "Longsword"), false);
			assertEquals(strMod + 2, m(longsword.get("attack_roll")).get("modifier"),
					"a Fighter is proficient with Martial weapons");
			assertTrue(dice.exhausted());
		}
	}

	/**
	 * SRD 5.2.1 p.190 "Unarmed Strike", Damage: "Your bonus to the roll equals your Strength
	 * modifier plus your Proficiency Bonus. On a hit, the target takes Bludgeoning damage equal to
	 * 1 plus your Strength modifier."
	 */
	@Test
	void unarmedStrikeDealsOnePlusStrengthAndUsesStrengthPlusProficiency() throws Exception {
		try (Engine engine = engine(tempDb("srd-unarmed-strike"))) {
			ScriptedRollService dice = (ScriptedRollService) engine.roller();
			String campaign = committedCampaign(engine);
			engine.sessions().bootstrap(op(), campaign, null);
			String pc = "character:1";
			String bandit = materialize(engine, campaign, "Bandit");
			int strMod = modifier(engine, campaign, pc, "STR");
			int proficiency = ((Number) sheet(engine, campaign, pc).get("proficiency_bonus")).intValue();
			int banditHp = ((Number) m(sheet(engine, campaign, bandit).get("hp")).get("current")).intValue();
			String enc = duel(engine, dice, campaign, pc, bandit);

			// Natural 19 hits AC 12 with any Strength modifier; the damage has no die, so no further value is consumed.
			dice.queue(19);
			Map<String, Object> punch = engine.encounters().perform(op(), campaign, enc, pc,
					map("kind", "ATTACK", "target", bandit, "weapon", "unarmed"), false);
			assertEquals(Boolean.TRUE, punch.get("hit"));
			assertEquals(19, punch.get("natural"));
			assertEquals(strMod + proficiency, m(punch.get("attack_roll")).get("modifier"), "STR + Proficiency Bonus");
			assertEquals(19 + strMod + proficiency, m(punch.get("attack_roll")).get("total"));
			assertEquals(Math.max(0, 1 + strMod), punch.get("damage"), "1 + STR modifier Bludgeoning");
			assertEquals("bludgeoning", list(punch.get("breakdown")).get(0).get("type"));
			assertEquals(banditHp - Math.max(0, 1 + strMod), punch.get("hp_after"));
			assertTrue(dice.exhausted(), "only the attack die was consumed");
		}
	}

	/**
	 * SRD 5.2.1 p.92 "Armor", Strength: "If the table shows a Strength score in the Strength column
	 * for an armor type, that armor reduces the wearer's speed by 10 feet unless the wearer has a
	 * Strength score equal to or higher than the listed score." Chain Mail lists Str 13.
	 */
	@Test
	void heavyArmorStrengthRequirementReducesSpeedByTen() throws Exception {
		try (Engine engine = engine(tempDb("srd-heavy-armor-speed"))) {
			String campaign = committedCampaign(engine);
			engine.sessions().bootstrap(op(), campaign, null);
			String pc = "character:1";
			// Pin the Strength below the requirement with an audited override (the rolled score may already be).
			engine.rest().override(op(), campaign, "SET_ABILITY_SCORE", pc, map("ability", "STR", "score", 8),
					"a slight frame for the armor test", null);
			assertEquals(8, m(m(sheet(engine, campaign, pc).get("abilities")).get("STR")).get("score"));
			int speed = ((Number) sheet(engine, campaign, pc).get("speed")).intValue();
			assertEquals(30, speed, "a Human's speed");

			String mail = grant(engine, campaign, pc, "Chain Mail");
			engine.inventory().equip(op(), campaign, pc, mail, true);
			assertEquals(speed - 10, ((Number) sheet(engine, campaign, pc).get("speed")).intValue(),
					"Str 8 in Chain Mail (Str 13): speed reduced by 10 feet, not merely warned about");
			// Taking it off restores the speed.
			engine.inventory().equip(op(), campaign, pc, mail, false);
			assertEquals(speed, ((Number) sheet(engine, campaign, pc).get("speed")).intValue());
			// Meeting the requirement removes the penalty.
			engine.rest().override(op(), campaign, "SET_ABILITY_SCORE", pc, map("ability", "STR", "score", 13),
					"strong enough for the armor test", null);
			engine.inventory().equip(op(), campaign, pc, mail, true);
			assertEquals(speed, ((Number) sheet(engine, campaign, pc).get("speed")).intValue(),
					"Str 13 meets the Chain Mail requirement");
		}
	}

	/**
	 * SRD 5.2.1 p.204 "Potions", Using a Potion: "Drinking a potion or administering it to another
	 * creature requires a Bonus Action." The Bonus Action is one per turn, shared with the Light
	 * property's extra attack and bonus-action spells.
	 */
	@Test
	void drinkingAPotionInCombatSpendsTheBonusAction() throws Exception {
		try (Engine engine = engine(tempDb("srd-potion-bonus-action"))) {
			ScriptedRollService dice = (ScriptedRollService) engine.roller();
			String campaign = committedCampaign(engine);
			engine.sessions().bootstrap(op(), campaign, null);
			String pc = "character:1";
			String bandit = materialize(engine, campaign, "Bandit");
			grant(engine, campaign, pc, "Potion of Healing");
			String dagger = grant(engine, campaign, pc, "Dagger");
			String handaxe = grant(engine, campaign, pc, "Handaxe");
			engine.inventory().equip(op(), campaign, pc, dagger, true);
			engine.inventory().equip(op(), campaign, pc, handaxe, true);
			String enc = duel(engine, dice, campaign, pc, bandit);
			engine.runtime().applyRuntimeChange(op(), campaign, pc,
					map("kind", "DAMAGE", "amount", 3, "reason", "a bruise"));

			// Drinking the potion (2d4+2) is the turn's Bonus Action.
			dice.queue(2, 2);
			Map<String, Object> drunk = engine.encounters().perform(op(), campaign, enc, pc,
					map("kind", "USE_ITEM", "item", "Potion of Healing"), false);
			assertEquals("2d4+2", m(drunk.get("roll")).get("expression"));
			assertEquals("BONUS_ACTION", drunk.get("action_cost"), "a potion costs the Bonus Action: " + drunk);
			Map<String, Object> me = list(engine.encounters().encounterState(campaign, enc, 3).get("participants"))
					.stream().filter(p -> pc.equals(p.get("character"))).findFirst().orElseThrow();
			assertEquals(Boolean.FALSE, me.get("bonus_action_available"), "the Bonus Action is spent for the turn");

			// The Attack action with the Dagger is still open, but the Light extra attack (a Bonus Action) is not.
			dice.queue(19, 2);
			Map<String, Object> stab = engine.encounters().perform(op(), campaign, enc, pc,
					map("kind", "ATTACK", "target", bandit, "weapon", "Dagger"), false);
			assertEquals(Boolean.TRUE, stab.get("hit"));
			RpgException refused = assertThrows(RpgException.class,
					() -> engine.encounters()
							.perform(op(), campaign, enc, pc, map("kind", "ATTACK", "target", bandit, "weapon",
									"Handaxe", "light_extra_attack", true), false),
					"a second Bonus Action in the same turn");
			assertEquals(ErrorCode.OPERATION_NOT_ALLOWED, refused.code());
			assertTrue(dice.exhausted(), "the refused attack rolled nothing");
		}
	}

	/**
	 * SRD 5.2.1 p.13 "Initiative": "For a group of identical creatures, the GM makes a single roll,
	 * so each member of the group has the same Initiative."
	 */
	@Test
	void identicalCreaturesShareOneInitiativeRoll() throws Exception {
		try (Engine engine = engine(tempDb("srd-group-initiative"))) {
			ScriptedRollService dice = (ScriptedRollService) engine.roller();
			String campaign = committedCampaign(engine);
			engine.sessions().bootstrap(op(), campaign, null);
			String pc = "character:1";
			String bandit1 = materialize(engine, campaign, "Bandit");
			String bandit2 = materialize(engine, campaign, "Bandit");
			String scout = materialize(engine, campaign, "Scout");

			// One d20 per distinct roll: the PC,
			// the Bandit group, the Scout. The trailing 1 is a sentinel that must still be queued afterwards.
			dice.queue(20, 10, 5, 1);
			Map<String, Object> started = engine.encounters().start(op(), campaign,
					map("party", List.of(pc), "raiders", List.of(bandit1, bandit2, scout)), null, null, null, null,
					null);
			List<Map<String, Object>> rolls = list(started.get("initiative_rolls"));
			List<Object> banditInitiatives = rolls.stream().filter(r -> "Bandit".equals(r.get("name")))
					.map(r -> r.get("initiative")).toList();
			assertEquals(2, banditInitiatives.size(), rolls.toString());
			assertEquals(banditInitiatives.get(0), banditInitiatives.get(1),
					"identical creatures share one roll: " + rolls);
			assertEquals(11, ((Number) banditInitiatives.get(0)).intValue(), "the single 10 plus the Bandit's +1");
			Map<String, Object> scoutRoll = rolls.stream().filter(r -> "Scout".equals(r.get("name"))).findFirst()
					.orElseThrow();
			assertEquals(5 + 2, ((Number) scoutRoll.get("initiative")).intValue(), "the Scout's own roll, +2 DEX");
			assertFalse(dice.exhausted(), "no extra die was consumed for the second Bandit");
			assertEquals(pc, m(started.get("turn")).get("character"));
		}
	}

	/**
	 * SRD 5.2.1 p.13 "Initiative", Ties: "If a tie occurs, the GM decides the order among tied
	 * monsters, and the players decide the order among tied characters. The GM decides the order if
	 * the tie is between a monster and a player character." No Dexterity comparison and no hidden
	 * die break the tie; the engine reports it for the GM.
	 */
	@Test
	void initiativeTiesAreNotBrokenByDexterityOrAHiddenRoll() throws Exception {
		try (Engine engine = engine(tempDb("srd-initiative-ties"))) {
			ScriptedRollService dice = (ScriptedRollService) engine.roller();
			String campaign = committedCampaign(engine);
			engine.sessions().bootstrap(op(), campaign, null);
			String pc = "character:1";
			String bandit = materialize(engine, campaign, "Bandit");
			int pcDexMod = modifier(engine, campaign, pc, "DEX");

			// Equal totals of 11: the PC's d20 is 11 - DEX modifier, the Bandit's (+1) is 10. No d100 follows either
			// d20; the trailing 1 is a sentinel that must still be queued afterwards.
			dice.queue(11 - pcDexMod, 10, 1);
			Map<String, Object> started = engine.encounters().start(op(), campaign,
					map("party", List.of(pc), "raiders", List.of(bandit)), null, null, null, null, null);
			List<Map<String, Object>> rolls = list(started.get("initiative_rolls"));
			assertEquals(2, rolls.size());
			assertEquals(11, ((Number) rolls.get(0).get("initiative")).intValue(), rolls.toString());
			assertEquals(11, ((Number) rolls.get(1).get("initiative")).intValue(), rolls.toString());
			assertFalse(dice.exhausted(), "no hidden tie-break die was rolled");
			// Both are in the order (the engine must pick some provisional order to run the round)...
			List<Map<String, Object>> participants = list(started.get("participants"));
			assertEquals(2, participants.size());
			assertEquals(11, ((Number) participants.get(0).get("initiative")).intValue());
			assertEquals(11, ((Number) participants.get(1).get("initiative")).intValue());
			// ...and the tie is surfaced for the GM to decide. The key name "ties" is a design choice; the rule only
			// requires that the GM is told. Each tie lists the tied participants' names.
			assertNotNull(started.get("ties"), "the tie between a monster and a player character is the GM's call");
			String ties = String.valueOf(started.get("ties"));
			assertTrue(ties.contains("Bandit") && ties.contains("Richard Greystone"), ties);
		}
	}
}
