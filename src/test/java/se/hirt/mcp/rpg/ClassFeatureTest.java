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
import se.hirt.mcp.rpg.protocol.RpgException;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
 * Class features that the engine enforces come from the class definition's {@code features} array,
 * not from Java (RULES_ENGINE.md §2.2). Sneak Attack is the first of them: adding the next one
 * should be a seed edit.
 */
class ClassFeatureTest {

	@SuppressWarnings("unchecked")
	private static Map<String, Object> m(Object o) {
		return (Map<String, Object>) o;
	}

	/** Builds a campaign whose party has a Rogue companion, and returns [campaign, rogueRef]. */
	private static String[] withRogue(Engine engine) {
		String campaign = committedCampaign(engine);
		engine.sessions().bootstrap(op(), campaign, null);
		String rogue = (String) engine.runtime()
				.materialize(op(), campaign, "Scout", "Vess", null, null, "CHAOTIC_GOOD", null, false).get("character");
		engine.party().updateMembership(op(), campaign, rogue, "JOIN", "recruited");
		engine.inventory().grantLoot(op(), campaign, rogue, List.of(map("item", "Shortsword", "quantity", 1)), null,
				"GM_GRANT", "her own blade");
		engine.runtime().awardXp(op(), campaign, null, 900, "QUEST", "enough for level 3");
		for (int level = 1; level <= 3; level++) {
			Map<String, Object> begun = engine.levelUps().begin(op(), campaign, rogue);
			String t = (String) m(begun.get("transaction")).get("ref");
			if (level == 1) {
				engine.levelUps().update(op(), campaign, t, null,
						map("class", "Rogue", "skills", List.of("Stealth", "Perception", "Deception", "Acrobatics")));
			}
			engine.levelUps().commit(op(), campaign, t, null);
		}
		return new String[] {campaign, rogue};
	}

	@Test
	void sneakAttackScalesWithRogueLevelAndShowsOnTheSheet() throws Exception {
		Path db = tempDb("feature-sheet");
		try (Engine engine = engine(db)) {
			String[] setup = withRogue(engine);
			Map<String, Object> sheet = engine.characters().characterSheet(setup[0], setup[1], "PLAY");
			List<Map<String, Object>> features = (List<Map<String, Object>>) sheet.get("class_features");
			assertNotNull(features, "class features are on the sheet");
			Map<String, Object> sneak = features.stream().filter(f -> "Sneak Attack".equals(f.get("name"))).findFirst()
					.orElseThrow();
			assertEquals("engine", sneak.get("adjudication"));
			assertEquals("2d6", sneak.get("dice"), "1d6 per two rogue levels, rounded up");
		}
	}

	/**
	 * SRD 5.2.1 p.61 "Sneak Attack": "Once per turn, you can deal an extra 1d6 damage to one
	 * creature you hit with an attack roll if you have Advantage on the roll and the attack uses a
	 * Finesse or a Ranged weapon. The extra damage's type is the same as the weapon's type."
	 */
	@Test
	void sneakAttackAppliesOnAQualifyingHitAndOnlyOncePerTurn() throws Exception {
		Path db = tempDb("feature-sneak");
		try (Engine engine = engine(db)) {
			ScriptedRollService dice = (ScriptedRollService) engine.roller();
			String[] setup = withRogue(engine);
			String campaign = setup[0], rogue = setup[1];
			engine.inventory().grantLoot(op(), campaign, rogue, List.of(map("item", "Dagger")), null, "GM_GRANT",
					"A different Light Finesse weapon for the Bonus Action attack.");
			String ogre = (String) engine.runtime()
					.materialize(op(), campaign, "Ogre", null, null, null, null, null, false).get("character");
			int ogreHp = ((Number) m(engine.characters().characterSheet(campaign, ogre, "PLAY").get("hp"))
					.get("current")).intValue();
			// The rogue acts first (20 to the ogre's 1).
			dice.queue(20, 1);
			String encounter = (String) engine.encounters()
					.start(op(), campaign, map("party", List.of(rogue), "foes", List.of(ogre)), null,
							meleeZones(rogue, ogre), null, null, null)
					.get("encounter");
			assertTrue(dice.exhausted());

			// A shortsword is a Finesse weapon, and advantage qualifies the attack: 2d20kh1 keeps the 19 (a hit
			// against AC 11), 1d6 → 4 for the blade, 2d6 → 3 + 5 for the Sneak Attack.
			dice.queue(4, 19, 4, 3, 5);
			Map<String, Object> first = engine.encounters().perform(op(), campaign, encounter, rogue, map("kind",
					"ATTACK", "attack", "Shortsword", "weapon", "Shortsword", "target", ogre, "advantage", "ADVANTAGE"),
					false);
			assertEquals(Boolean.TRUE, first.get("hit"), first.toString());
			assertEquals("ADVANTAGE", first.get("advantage"));
			Map<String, Object> sneak = m(first.get("sneak_attack"));
			assertNotNull(sneak, "sneak attack applied: " + first);
			assertEquals("Sneak Attack", sneak.get("feature"));
			assertEquals("2d6", sneak.get("dice"));
			assertEquals(8, sneak.get("damage"), "the scripted 3 + 5");
			assertEquals("piercing", sneak.get("type"), "the extra damage's type is the Shortsword's");
			assertEquals("advantage on the attack", sneak.get("qualified_by"));
			int dexMod = 2; // the Scout's DEX 14 is the better Finesse modifier (STR 11)
			assertEquals(4 + dexMod + 8, first.get("damage"), "blade plus Sneak Attack: " + first.get("breakdown"));
			assertEquals(ogreHp - (4 + dexMod + 8), first.get("hp_after"));
			assertTrue(dice.exhausted(), "two attack dice, one weapon die and two Sneak Attack dice were consumed");

			// A legitimate Light-property Bonus Action uses a different Finesse weapon on this same turn.
			// Advantage would qualify again, but Sneak Attack is spent; only the dagger die is rolled.
			dice.queue(4, 19, 2);
			Map<String, Object> second = engine.encounters()
					.perform(
							op(), campaign, encounter, rogue, map("kind", "ATTACK", "attack", "Dagger", "weapon",
									"Dagger", "target", ogre, "advantage", "ADVANTAGE", "light_extra_attack", true),
							false);
			assertEquals(Boolean.TRUE, second.get("hit"), second.toString());
			assertNull(second.get("sneak_attack"), "once per turn");
			assertEquals(2, second.get("damage"),
					"the Light bonus attack also omits the positive ability damage modifier");
			assertEquals("BONUS_ACTION", second.get("action_cost"));
			assertTrue(dice.exhausted(), "no Sneak Attack dice on the second hit");
		}
	}

	@Test
	void sneakAttackNeedsAFinesseOrRangedWeapon() throws Exception {
		Path db = tempDb("feature-weapon");
		try (Engine engine = engine(db)) {
			ScriptedRollService dice = (ScriptedRollService) engine.roller();
			String[] setup = withRogue(engine);
			String campaign = setup[0], rogue = setup[1];
			engine.inventory().grantLoot(op(), campaign, rogue, List.of(map("item", "Mace", "quantity", 1)), null,
					"GM_GRANT", "a test mace");
			String ogre = (String) engine.runtime()
					.materialize(op(), campaign, "Ogre", null, null, null, null, null, false).get("character");
			dice.queue(20, 1);
			String encounter = (String) engine.encounters()
					.start(op(), campaign, map("party", List.of(rogue), "foes", List.of(ogre)), null,
							meleeZones(rogue, ogre), null, null, null)
					.get("encounter");
			assertTrue(dice.exhausted());
			// The mace hits with Advantage (19 kept), so the missing Sneak Attack is the weapon's doing, not a miss.
			dice.queue(4, 19, 3);
			Map<String, Object> hit = engine.encounters().perform(op(), campaign, encounter, rogue,
					map("kind", "ATTACK", "attack", "Mace", "weapon", "Mace", "target", ogre, "advantage", "ADVANTAGE"),
					false);
			assertEquals(Boolean.TRUE, hit.get("hit"), hit.toString());
			assertEquals("ADVANTAGE", hit.get("advantage"));
			assertNull(hit.get("sneak_attack"), "a mace is neither Finesse nor Ranged: " + hit);
			assertTrue(dice.exhausted(), "only the mace's 1d6 was rolled for damage");
		}
	}

	/**
	 * Sleep has NO higher-level clause in SRD 5.2.1 — its radius does not grow with the slot. This
	 * test exists because the GM asserted otherwise from memory, edited the seed to match, and had
	 * to be corrected by the PDF. Three spells verified against the SRD text on 2026-09-03 are
	 * pinned here.
	 */
	@Test
	void spellScalingMatchesTheVerifiedSrdText() throws Exception {
		Path db = tempDb("feature-spells");
		try (Engine engine = engine(db)) {
			String campaign = committedCampaign(engine);
			assertNull(mechanicsOf(engine, campaign, "Sleep").get("upcast"),
					"SRD 5.2.1 Sleep has no Using a Higher-Level Spell Slot clause");
			assertEquals("1d8",
					m(mechanicsOf(engine, campaign, "Glyph of Warding").get("upcast")).get("per_level_dice"),
					"explosive rune damage per slot level above 3");
		}
	}

	@Test
	void protectionFromEvilAndGoodIsConcentration() throws Exception {
		Path db = tempDb("feature-conc");
		try (Engine engine = engine(db)) {
			String campaign = committedCampaign(engine);
			Map<String, Object> payload = payloadOf(engine, campaign, "Protection from Evil and Good");
			assertEquals(Boolean.TRUE, payload.get("concentration"));
			assertEquals("Concentration, up to 10 minutes", payload.get("duration"));
		}
	}

	private static Map<String, Object> payloadOf(Engine engine, String campaign, String name) {
		Map<String, Object> defs = engine.content().definitions(campaign, "SPELL", null, name, null, null, null, 50,
				"FULL");
		List<Map<String, Object>> items = (List<Map<String, Object>>) defs.get("items");
		return m(items.stream().filter(d -> name.equals(d.get("name"))).findFirst().orElseThrow().get("payload"));
	}

	private static Map<String, Object> mechanicsOf(Engine engine, String campaign, String name) {
		return m(payloadOf(engine, campaign, name).get("mechanics"));
	}

	/**
	 * A companion promoted from a stat block attacks with what it carries, but an action printed on
	 * the stat block stays reachable by name when no carried weapon answers to it (a Priest's
	 * Radiant Flame, a Scout's Longbow it never owned).
	 */
	@Test
	@SuppressWarnings("unchecked")
	void aClassedCompanionReachesItsStatBlockActionsByName() throws Exception {
		Path db = tempDb("feature-statblock-name");
		try (Engine engine = engine(db)) {
			ScriptedRollService dice = (ScriptedRollService) engine.roller();
			String[] setup = withRogue(engine);
			String campaign = setup[0], rogue = setup[1];
			String ogre = (String) engine.runtime()
					.materialize(op(), campaign, "Ogre", null, null, null, null, null, false).get("character");
			dice.queue(20, 1);
			String encounter = (String) engine.encounters()
					.start(op(), campaign, map("party", List.of(rogue), "foes", List.of(ogre)), null,
							meleeZones(rogue, ogre), null, null, null)
					.get("encounter");
			// The Scout's Longbow was never carried: by name it is still the stat block's attack, and says so.
			Map<String, Object> bow = engine.encounters().perform(op(), campaign, encounter, rogue,
					map("kind", "ATTACK", "attack", "Longbow", "target", ogre), false);
			assertEquals("Longbow", bow.get("attack"));
			assertTrue(((List<String>) bow.get("warnings")).stream().anyMatch(w -> w.contains("stat-block action")),
					"" + bow.get("warnings"));
			engine.encounters().perform(op(), campaign, encounter, rogue, map("kind", "END_TURN"), true);
			engine.encounters().perform(op(), campaign, encounter, ogre, map("kind", "END_TURN"), true);
			// The shortsword she carries is her own weapon, not the stat block's.
			Map<String, Object> sword = engine.encounters().perform(op(), campaign, encounter, rogue,
					map("kind", "ATTACK", "attack", "Shortsword", "target", ogre), false);
			assertEquals("Shortsword", sword.get("attack"));
			assertTrue(sword.get("warnings") == null
					|| ((List<String>) sword.get("warnings")).stream().noneMatch(w -> w.contains("stat-block")));
			engine.encounters().perform(op(), campaign, encounter, rogue, map("kind", "END_TURN"), true);
			engine.encounters().perform(op(), campaign, encounter, ogre, map("kind", "END_TURN"), true);
			// Something neither carried nor printed is still refused.
			assertThrows(RpgException.class, () -> engine.encounters().perform(op(), campaign, encounter, rogue,
					map("kind", "ATTACK", "attack", "Greatsword", "target", ogre), false));
		}
	}

	/**
	 * "Beside the target" means an enemy of the target in the target's zone, not an ally anywhere
	 * in the fight.
	 */
	@Test
	void sneakAttackNeedsAnEnemyOfTheTargetInTheTargetsZone() throws Exception {
		Path db = tempDb("feature-sneak-zone");
		try (Engine engine = engine(db)) {
			ScriptedRollService dice = (ScriptedRollService) engine.roller();
			String[] setup = withRogue(engine);
			String campaign = setup[0], rogue = setup[1], pc = "character:1";
			String ogre = (String) engine.runtime()
					.materialize(op(), campaign, "Ogre", null, null, null, null, null, false).get("character");
			// The PC stands in another zone from the ogre: nobody is beside the target.
			dice.queue(20, 5, 1);
			String apart = (String) engine.encounters()
					.start(op(), campaign, map("party", List.of(rogue, pc), "foes", List.of(ogre)), null,
							map(rogue, "far", pc, "near", ogre, "far"), null, null, null)
					.get("encounter");
			dice.queue(15);
			Map<String, Object> alone = engine.encounters().perform(op(), campaign, apart, rogue,
					map("kind", "ATTACK", "attack", "Shortsword", "target", ogre), false);
			assertEquals(Boolean.TRUE, alone.get("hit"));
			assertNull(alone.get("sneak_attack"), "the ally is not within 5 feet of the target: " + alone);
			engine.encounters().end(op(), campaign, apart, "OTHER", "reset");
			TestCampaigns.gridPositions(engine, campaign, Map.of(pc, List.of(40, 25)));

			// Now the PC shares the ogre's zone: the rogue's blade finds the opening.
			dice.queue(20, 5, 1);
			String together = (String) engine.encounters()
					.start(op(), campaign, map("party", List.of(rogue, pc), "foes", List.of(ogre)), null,
							map(rogue, "far", pc, "far", ogre, "far"), null, null, null)
					.get("encounter");
			dice.queue(15);
			Map<String, Object> flanked = engine.encounters().perform(op(), campaign, together, rogue,
					map("kind", "ATTACK", "attack", "Shortsword", "target", ogre), false);
			Map<String, Object> sneak = m(flanked.get("sneak_attack"));
			assertNotNull(sneak, "an enemy of the target is beside it: " + flanked);
			assertEquals("Richard Greystone is beside the target", sneak.get("qualified_by"));
		}
	}
}
