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
 * Temporary weapon buffs in battle, alone and stacked on enchanted weapons: Bless and Divine Favor
 * on the wielder of a frost blade, Magic Weapon bound to one nonmagical weapon and raised by the
 * slot, Shillelagh turning a +1 quarterstaff into a Wisdom weapon, and a conjured Flame Blade.
 */
class WeaponBuffTest {

	@SuppressWarnings("unchecked")
	private static Map<String, Object> m(Object o) {
		return (Map<String, Object>) o;
	}

	@SuppressWarnings("unchecked")
	private static List<Map<String, Object>> l(Object o) {
		return (List<Map<String, Object>>) o;
	}

	/** A level-1 caster of the given class in a committed campaign: {campaign, pc}. */
	private static String[] caster(
		Engine engine, String cls, Map<String, Object> scores, List<String> skills, List<String> cantrips,
		List<String> spells) {
		String campaign = (String) engine.campaigns().create(op(), null, null).get("campaign");
		engine.campaigns().updateSetup(op(), campaign, null,
				map("content_profile", "PEGI_16", "experience", "SURPRISE_ME", "rules", Map.of(), "continuation",
						"CHECKPOINT", "party", "SURPRISE_ME", "adventure",
						map("premise", "x", "opening_location", "Tower", "immediate_goal", "y")));
		String pc = (String) engine.characters()
				.createDraft(op(), campaign,
						map("name", "Caster", "species", "Human", "class", cls, "ability_scores", scores, "skills",
								skills, "personality", "x", "background", "Criminal", "background_ability_scores",
								map("CON", 2, "DEX", 1), "species_skill", "Animal Handling", "origin_feat",
								map("feat", "Skilled", "proficiencies", List.of("Nature", "Survival", "Medicine")),
								"cantrips", cantrips, "spells", spells),
						true)
				.get("character");
		engine.characters().commitDraft(op(), campaign, pc, null);
		engine.campaigns().commitSetup(op(), campaign, null);
		engine.sessions().bootstrap(op(), campaign, null);
		return new String[] {campaign, pc};
	}

	/** Levels a prepared caster; the level-4 improvement goes into {@code asi}. */
	private static void levelTo(Engine engine, String campaign, String pc, int level, String asi) {
		int[] xp = {0, 0, 300, 900, 2700, 6500};
		engine.runtime().awardXp(op(), campaign, List.of(pc), xp[level], "MILESTONE", "test");
		for (int next = 2; next <= level; next++) {
			String t = (String) m(engine.levelUps().begin(op(), campaign, pc).get("transaction")).get("ref");
			if (next == 4) {
				engine.levelUps().update(op(), campaign, t, null, map("ability_score_improvement", map(asi, 2)));
			}
			engine.levelUps().commit(op(), campaign, t, null);
		}
	}

	/**
	 * Starts a fight against an ogre (59 HP, so nothing dies to one hit) with the player character
	 * acting first.
	 */
	private static String[] fight(Engine engine, String campaign, String pc) {
		String ogre = (String) engine.runtime()
				.materialize(op(), campaign, "Ogre", "Brawn", null, null, null, null, false).get("character");
		String encounter = (String) engine.encounters()
				.start(op(), campaign, map("a", List.of(pc), "b", List.of(ogre)), null, null, null, null, null)
				.get("encounter");
		if (!pc.equals(m(engine.encounters().encounterState(campaign, encounter, 3).get("turn")).get("character"))) {
			engine.encounters().perform(op(), campaign, encounter, ogre, map("kind", "END_TURN"), true);
		}
		return new String[] {encounter, ogre};
	}

	/**
	 * One action that hits (a 19 is queued: no critical, so the dice are not doubled); the
	 * opponent's turn is skipped after.
	 */
	private static Map<String, Object> hit(
		Engine engine, String campaign, String encounter, String pc, String ogre, Map<String, Object> action) {
		((ScriptedRollService) engine.roller()).queue(19);
		Map<String, Object> out = engine.encounters().perform(op(), campaign, encounter, pc, action, true);
		assertEquals(Boolean.TRUE, out.get("hit"), String.valueOf(out));
		if (Boolean.TRUE.equals(out.get("turn_advanced"))) {
			engine.encounters().perform(op(), campaign, encounter, ogre, map("kind", "END_TURN"), true);
		}
		return out;
	}

	private static int attackModifier(Map<String, Object> out) {
		return ((Number) m(out.get("attack_roll")).get("modifier")).intValue();
	}

	private static List<Map<String, Object>> breakdown(Map<String, Object> out) {
		Object b = out.get("breakdown") != null ? out.get("breakdown") : m(out.get("damage")).get("breakdown");
		return l(b);
	}

	private static Map<String, Object> part(Map<String, Object> out, String type) {
		return breakdown(out).stream().filter(x -> type.equals(x.get("type"))).findFirst()
				.orElseThrow(() -> new AssertionError("no " + type + " damage in " + out));
	}

	private static String expression(Map<String, Object> part) {
		return String.valueOf(m(part.get("roll")).get("expression"));
	}

	@Test
	void blessAndDivineFavorStackOnAnEnchantedBlade() throws Exception {
		try (Engine engine = engine(tempDb("buff-paladin"))) {
			String[] ids = caster(engine, "Paladin",
					map("STR", 15, "CHA", 14, "CON", 13, "DEX", 12, "WIS", 10, "INT", 8),
					List.of("Athletics", "Persuasion"), List.of(), List.of("Bless", "Divine Favor"));
			String campaign = ids[0], pc = ids[1];
			// A GM-made frost blade: +1, and 1d6 cold on a hit.
			engine.inventory().grantLoot(op(), campaign, pc,
					List.of(map("item", "Longsword", "magic", map("name", "Frostbrand", "rarity", "RARE", "bonus", 1,
							"damage_bonus_dice", "1d6", "damage_type", "cold", "text", "A blade rimed with frost."))),
					null, "QUEST", null);
			engine.spells().cast(op(), campaign, pc, "Bless", null, List.of(pc), null);
			engine.spells().cast(op(), campaign, pc, "Divine Favor", null, null, null);
			String[] f = fight(engine, campaign, pc);
			String encounter = f[0], ogre = f[1];

			Map<String, Object> swing = hit(engine, campaign, encounter, pc, ogre,
					map("kind", "ATTACK", "attack", "Frostbrand", "target", ogre));
			assertEquals(2 + 2 + 1, attackModifier(swing), "STR +2, proficiency +2, the blade's +1: " + swing);
			assertEquals(1, l(swing.get("bonus_dice")).size(), "Bless adds a d4 to the attack roll: " + swing);
			assertEquals("1d4", m(l(swing.get("bonus_dice")).get(0)).get("expression"));
			assertEquals("1d8+3", expression(part(swing, "slashing")), "STR +2 and the blade's +1");
			assertEquals("1d6", expression(part(swing, "cold")), "the blade's frost");
			assertEquals("1d4", expression(part(swing, "radiant")), "Divine Favor's radiant");
			assertEquals(3, breakdown(swing).size(), String.valueOf(breakdown(swing)));
		}
	}

	@Test
	void magicWeaponBindsToOneNonmagicalWeaponAndTheSlotRaisesIt() throws Exception {
		try (Engine engine = engine(tempDb("buff-magic-weapon"))) {
			String[] ids = caster(engine, "Wizard",
					map("INT", 15, "STR", 14, "CON", 13, "DEX", 12, "WIS", 10, "CHA", 8), List.of("Arcana", "History"),
					List.of("Fire Bolt", "Light", "Mage Hand"),
					List.of("Mage Armor", "Magic Missile", "Shield", "Sleep"));
			String campaign = ids[0], pc = ids[1];
			levelTo(engine, campaign, pc, 5, "INT"); // level-3 slots arrive at wizard level 5
			engine.spells().prepare(op(), campaign, pc, null, List.of("Magic Weapon", "Mage Armor", "Magic Missile"));
			engine.inventory().grantLoot(op(), campaign, pc,
					List.of(map("item", "Longsword"), map("item", "Dagger"), map("item", "+1 Longsword")), null,
					"QUEST", null);

			// A magical weapon cannot take it; a weapon must be named or wielded.
			RpgException magical = assertThrows(RpgException.class, () -> engine.spells().cast(op(), campaign, pc,
					"Magic Weapon", null, null, map("weapon", "+1 Longsword")));
			assertEquals(ErrorCode.VALIDATION_FAILED, magical.code());
			assertTrue(magical.getMessage().contains("nonmagical"), magical.getMessage());
			RpgException none = assertThrows(RpgException.class,
					() -> engine.spells().cast(op(), campaign, pc, "Magic Weapon", null, null, null));
			assertEquals(ErrorCode.VALIDATION_FAILED, none.code());

			// Cast with a level-3 slot on the longsword: +2 (SRD 5.2.1: +2 with a level 3-5 slot).
			Map<String, Object> cast = engine.spells().cast(op(), campaign, pc, "Magic Weapon", 3, null,
					map("weapon", "Longsword"));
			assertEquals("Longsword", l(cast.get("targets")).get(0).get("weapon"));
			Map<String, Object> effect = l(engine.characters().characterSheet(campaign, pc, "PLAY").get("effects"))
					.get(0);
			assertEquals(2, m(effect.get("modifiers")).get("attack_bonus"));
			assertEquals(2, m(effect.get("modifiers")).get("damage_bonus"));
			assertEquals("Longsword", m(effect.get("modifiers")).get("weapon_name"));

			String[] f = fight(engine, campaign, pc);
			String encounter = f[0], ogre = f[1];
			Map<String, Object> sword = hit(engine, campaign, encounter, pc, ogre,
					map("kind", "ATTACK", "attack", "Longsword", "target", ogre));
			assertEquals(2 + 3 + 2, attackModifier(sword), "STR +2, proficiency +3, Magic Weapon +2: " + sword);
			assertEquals("1d8+4", expression(part(sword, "slashing")), "STR +2 and Magic Weapon +2 on the damage roll");
			Map<String, Object> dagger = hit(engine, campaign, encounter, pc, ogre,
					map("kind", "ATTACK", "attack", "Dagger", "target", ogre));
			assertEquals(2 + 3, attackModifier(dagger), "the dagger is not the buffed weapon: " + dagger);
			assertEquals("1d4+2", expression(part(dagger, "piercing")));
			Map<String, Object> fist = hit(engine, campaign, encounter, pc, ogre,
					map("kind", "ATTACK", "attack", "unarmed", "target", ogre));
			assertEquals(2 + 3, attackModifier(fist), "nor is a fist: " + fist);

			// Casting it again ends the previous one: still exactly one effect, now on the dagger, at +1.
			engine.encounters().end(op(), campaign, encounter, "ENEMIES_FLED", null);
			engine.spells().cast(op(), campaign, pc, "Magic Weapon", null, null, map("weapon", "Dagger"));
			List<Map<String, Object>> effects = l(
					engine.characters().characterSheet(campaign, pc, "PLAY").get("effects"));
			assertEquals(1, effects.size(), String.valueOf(effects));
			assertEquals("Dagger", m(effects.get(0).get("modifiers")).get("weapon_name"));
			assertEquals(1, m(effects.get(0).get("modifiers")).get("attack_bonus"), "a level-2 slot: +1");
		}
	}

	@Test
	void shillelaghTurnsAnEnchantedStaffIntoAWisdomWeapon() throws Exception {
		try (Engine engine = engine(tempDb("buff-shillelagh"))) {
			String[] ids = caster(engine, "Druid", map("WIS", 15, "CON", 14, "DEX", 13, "INT", 12, "CHA", 10, "STR", 8),
					List.of("Insight", "Perception"), List.of("Shillelagh", "Druidcraft"),
					List.of("Cure Wounds", "Entangle"));
			String campaign = ids[0], pc = ids[1];
			engine.inventory().grantLoot(op(), campaign, pc,
					List.of(map("item", "+1 Quarterstaff"), map("item", "Longsword")), null, "QUEST", null);

			RpgException wrongKind = assertThrows(RpgException.class, () -> engine.spells().cast(op(), campaign, pc,
					"Shillelagh", null, null, map("weapon", "Longsword")));
			assertEquals(ErrorCode.VALIDATION_FAILED, wrongKind.code());
			assertTrue(wrongKind.getMessage().contains("Quarterstaff"), wrongKind.getMessage());

			Map<String, Object> cast = engine.spells().cast(op(), campaign, pc, "Shillelagh", null, null,
					map("weapon", "+1 Quarterstaff", "damage_type", "force"));
			assertEquals("+1 Quarterstaff", l(cast.get("targets")).get(0).get("weapon"));
			String[] f = fight(engine, campaign, pc);
			String encounter = f[0], ogre = f[1];
			Map<String, Object> staff = hit(engine, campaign, encounter, pc, ogre,
					map("kind", "ATTACK", "attack", "+1 Quarterstaff", "target", ogre));
			assertEquals(2 + 2 + 1, attackModifier(staff),
					"WIS +2 instead of STR -1, proficiency +2, the staff's +1: " + staff);
			assertEquals("1d8+3", expression(part(staff, "force")), "a d8 of Force with WIS +2 and the staff's +1");
			Map<String, Object> sword = hit(engine, campaign, encounter, pc, ogre,
					map("kind", "ATTACK", "attack", "Longsword", "target", ogre));
			assertEquals(-1 + 2, attackModifier(sword), "the longsword still swings with STR -1: " + sword);
		}
	}

	@Test
	void flameBladeIsAConjuredWeaponForAsLongAsItLasts() throws Exception {
		try (Engine engine = engine(tempDb("buff-flame-blade"))) {
			String[] ids = caster(engine, "Druid", map("WIS", 15, "CON", 14, "DEX", 13, "INT", 12, "CHA", 10, "STR", 8),
					List.of("Insight", "Perception"), List.of("Shillelagh", "Druidcraft"),
					List.of("Cure Wounds", "Entangle"));
			String campaign = ids[0], pc = ids[1];
			levelTo(engine, campaign, pc, 5, "WIS"); // WIS 17 (+3), proficiency +3, level-3 slots
			engine.spells().prepare(op(), campaign, pc, null, List.of("Flame Blade", "Cure Wounds", "Entangle"));

			String[] f = fight(engine, campaign, pc);
			String encounter = f[0], ogre = f[1];
			assertThrows(RpgException.class, () -> engine.encounters().perform(op(), campaign, encounter, pc,
					map("kind", "ATTACK", "attack", "Flame Blade", "target", ogre), true), "no blade yet");

			// Evoke it in the fight with a level-3 slot (3d6 + 1d6), then strike with it on the next turn.
			engine.encounters().perform(op(), campaign, encounter, pc,
					map("kind", "CAST", "spell", "Flame Blade", "slot_level", 3), true);
			engine.encounters().perform(op(), campaign, encounter, ogre, map("kind", "END_TURN"), true);
			assertEquals(List.of("Flame Blade (Caster)"),
					m(engine.characters().characterSheet(campaign, pc, "PLAY").get("spellcasting"))
							.get("concentrating_on"));
			Map<String, Object> blade = hit(engine, campaign, encounter, pc, ogre,
					map("kind", "ATTACK", "attack", "Flame Blade", "target", ogre));
			assertEquals(3 + 3, attackModifier(blade), "a melee spell attack: proficiency +3, WIS +3: " + blade);
			assertEquals("4d6+3", expression(part(blade, "fire")), "3d6 +1d6 for the level-3 slot, plus WIS");
			assertEquals(1, breakdown(blade).size(), "fire only");
		}
	}
}
