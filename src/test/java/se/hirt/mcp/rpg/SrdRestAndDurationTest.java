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
import se.hirt.mcp.rpg.progression.LevelUpService;
import se.hirt.mcp.rpg.protocol.ErrorCode;
import se.hirt.mcp.rpg.protocol.RpgException;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static se.hirt.mcp.rpg.TestCampaigns.*;

/**
 * Rests, durations, Exhaustion recovery, Temporary Hit Points and the hit point maximum as the SRD
 * 5.2.1 text has them: each test quotes the operative sentence and page. Written against the SRD,
 * not against the engine; where a test fails today the engine deviates from the quoted rule.
 */
class SrdRestAndDurationTest {

	// SRD 5.2.1 p.185 "Long Rest": "Regain All HP. You regain all lost Hit Points and all spent Hit
	// Point Dice."
	@Test
	void aLongRestRegainsAllSpentHitPointDice() throws Exception {
		try (Engine engine = engine(tempDb("srd-long-rest-hit-dice"))) {
			ScriptedRollService dice = (ScriptedRollService) engine.roller();
			String campaign = committedCampaign(engine);
			engine.sessions().bootstrap(op(), campaign, null);
			String pc = "character:1";
			levelTo(engine, dice, campaign, pc, 3);
			assertEquals(3, sheet(engine, campaign, pc).get("level"));
			int max = (Integer) hp(engine, campaign, pc).get("max");
			engine.runtime().applyRuntimeChange(op(), campaign, pc,
					map("kind", "DAMAGE", "amount", max - 1, "damage_type", "slashing", "reason", "a hard day"));
			// Every Hit Point Die the level grants is spent on one Short Rest.
			dice.queue(1, 1, 1);
			Map<String, Object> shortRest = engine.rest().rest(op(), campaign, "SHORT", map(pc, 3), List.of(pc));
			Map<String, Object> afterShort = list(shortRest.get("characters")).get(0);
			assertEquals(3, afterShort.get("hit_dice_spent"));
			assertEquals(0, afterShort.get("hit_dice_left"), "all three dice are spent");

			Map<String, Object> longRest = engine.rest().rest(op(), campaign, "LONG", null, List.of(pc));
			Map<String, Object> rested = list(longRest.get("characters")).get(0);
			assertEquals(max, m(rested.get("hp")).get("current"), "all lost Hit Points");
			assertEquals(3, m(rested.get("hit_dice")).get("max"));
			assertEquals(3, m(rested.get("hit_dice")).get("current"),
					"all spent Hit Point Dice return on a Long Rest (SRD 5.2.1 p.185), not half of them");
		}
	}

	// SRD 5.2.1 p.187 "Short Rest": "For each Hit Point Die you spend in this way, roll the die and add
	// your Constitution modifier to it. You regain Hit Points equal to the total (minimum of 1 Hit
	// Point)."
	@Test
	void eachHitPointDieSpentOnAShortRestRegainsAtLeastOneHitPoint() throws Exception {
		try (Engine engine = engine(tempDb("srd-short-rest-minimum"))) {
			ScriptedRollService dice = (ScriptedRollService) engine.roller();
			String campaign = committedCampaign(engine);
			engine.sessions().bootstrap(op(), campaign, null);
			String pc = "character:1";
			// An audited fiat score change gives Richard a Constitution modifier of -2 (score 6).
			engine.rest().override(op(), campaign, "SET_ABILITY_SCORE", pc, map("ability", "CON", "score", 6),
					"a wasting sickness", null);
			assertEquals(-2, m(m(sheet(engine, campaign, pc).get("abilities")).get("CON")).get("modifier"));
			int max = (Integer) hp(engine, campaign, pc).get("max");
			assertTrue(max >= 3, "fixture: a d6 sorcerer at -2 Constitution still has a few hit points, has " + max);
			engine.runtime().applyRuntimeChange(op(), campaign, pc,
					map("kind", "DAMAGE", "amount", 2, "damage_type", "slashing", "reason", "a scratch"));
			int before = (Integer) hp(engine, campaign, pc).get("current");
			assertEquals(max - 2, before);
			// The die shows 1: 1 + (-2) = -1, which the rule lifts to a minimum of 1 Hit Point.
			dice.queue(1);
			Map<String, Object> rest = engine.rest().rest(op(), campaign, "SHORT", map(pc, 1), List.of(pc));
			Map<String, Object> rested = list(rest.get("characters")).get(0);
			assertEquals(1, rested.get("hit_dice_spent"));
			assertEquals(before + 1, m(rested.get("hp")).get("current"),
					"a spent Hit Point Die regains at least 1 Hit Point (SRD 5.2.1 p.187)");
			assertEquals(before + 1, hp(engine, campaign, pc).get("current"));
		}
	}

	// SRD 5.2.1 p.185 "Long Rest": "To start a Long Rest, you must have at least 1 Hit Point." and
	// p.187 "Short Rest": "To start a Short Rest, you must have at least 1 Hit Point."
	@Test
	void aRestCannotBeStartedWithZeroHitPoints() throws Exception {
		try (Engine engine = engine(tempDb("srd-rest-at-zero"))) {
			String campaign = committedCampaign(engine);
			engine.sessions().bootstrap(op(), campaign, null);
			String pc = "character:1";
			int max = (Integer) hp(engine, campaign, pc).get("max");
			engine.runtime().applyRuntimeChange(op(), campaign, pc,
					map("kind", "DAMAGE", "amount", max, "damage_type", "bludgeoning", "reason", "knocked out"));
			assertEquals("DYING", sheet(engine, campaign, pc).get("life_state"));
			assertEquals(0, hp(engine, campaign, pc).get("current"));

			// Either the rest is refused outright, or the character at 0 HP is reported skipped; in both
			// cases nothing about him changes.
			for (String kind : List.of("SHORT", "LONG")) {
				try {
					Map<String, Object> rest = engine.rest().rest(op(), campaign, kind, null, List.of(pc));
					for (Map<String, Object> rested : list(rest.get("characters"))) {
						if (pc.equals(rested.get("character"))) {
							assertNotNull(rested.get("skipped"),
									kind + " rest at 0 HP must be refused or reported skipped: " + rested);
						}
					}
				} catch (RpgException e) {
					assertEquals(ErrorCode.OPERATION_NOT_ALLOWED, e.code(), kind + ": " + e.getMessage());
				}
				assertEquals(0, hp(engine, campaign, pc).get("current"),
						"a " + kind + " rest cannot be started with 0 Hit Points (SRD 5.2.1 p.185/187)");
				assertEquals("DYING", sheet(engine, campaign, pc).get("life_state"),
						"a rest is not healing: the character stays Dying");
			}
		}
	}

	// SRD 5.2.1 p.185 "Long Rest": "After you finish a Long Rest, you must wait at least 16 hours
	// before starting another one."
	@Test
	void aSecondLongRestNeedsSixteenHoursAfterTheFirst() throws Exception {
		try (Engine engine = engine(tempDb("srd-long-rest-gap"))) {
			String campaign = committedCampaign(engine);
			engine.sessions().bootstrap(op(), campaign, null);
			String pc = "character:1";
			for (int level = 0; level < 2; level++) {
				engine.runtime().applyRuntimeChange(op(), campaign, pc,
						map("kind", "ADD_CONDITION", "condition", "EXHAUSTION", "reason", "a forced march"));
			}
			assertEquals(2, sheet(engine, campaign, pc).get("exhaustion"));
			engine.rest().rest(op(), campaign, "LONG", null, List.of(pc));
			assertEquals(1, sheet(engine, campaign, pc).get("exhaustion"), "the first Long Rest removes one level");

			// Straight back to bed: no 16 hours have passed, so this is not a Long Rest. Either it is
			// refused or it confers no benefit; the Exhaustion level is the observable.
			try {
				engine.rest().rest(op(), campaign, "LONG", null, List.of(pc));
			} catch (RpgException e) {
				assertEquals(ErrorCode.OPERATION_NOT_ALLOWED, e.code(), e.getMessage());
			}
			assertEquals(1, sheet(engine, campaign, pc).get("exhaustion"),
					"a second Long Rest within 16 hours of the first confers no benefit (SRD 5.2.1 p.185)");

			engine.sessions().advanceTime(op(), campaign, 16 * 60, "a day of travel");
			engine.rest().rest(op(), campaign, "LONG", null, List.of(pc));
			assertEquals(0, sheet(engine, campaign, pc).get("exhaustion"), "16 hours later a Long Rest counts");
		}
	}

	// SRD 5.2.1 p.13 "Combat": "A round represents about 6 seconds in the game world." A condition
	// that lasts 10 rounds lasts 1 minute; nothing in the SRD ends it because the fight ended.
	@Test
	void roundScopedEffectsOutliveTheEncounterAndExpireOnTheGameClock() throws Exception {
		try (Engine engine = engine(tempDb("srd-rounds-outlive-encounter"))) {
			String campaign = committedCampaign(engine);
			engine.sessions().bootstrap(op(), campaign, null);
			String pc = "character:1";
			String wolf = (String) engine.runtime()
					.materialize(op(), campaign, "Wolf", null, null, null, null, null, false).get("character");
			String encounter = (String) engine.encounters().start(op(), campaign,
					map("party", List.of(pc), "beasts", List.of(wolf)), null, null, null, null, null).get("encounter");
			Map<String, Object> blinded = engine.runtime().applyRuntimeChange(op(), campaign, pc, map("kind",
					"ADD_CONDITION", "condition", "BLINDED", "duration", map("rounds", 10), "reason", "sand"));
			assertEquals("ROUNDS", m(condition(blinded, "BLINDED").get("duration")).get("kind"),
					"inside an encounter the duration is counted in rounds");
			// Two rounds are 12 seconds; the game clock counts minutes, so this one is also gone after the
			// first minute (noted: a finer clock would end it at 12 seconds).
			engine.runtime().applyRuntimeChange(op(), campaign, pc, map("kind", "ADD_CONDITION", "condition",
					"DEAFENED", "duration", map("rounds", 2), "reason", "a thunderclap"));
			assertEquals(Set.of("BLINDED", "DEAFENED"), conditions(engine, campaign, pc));

			// The fight ends in round 1: 6 seconds of the minute have passed, the sand is still in his eyes.
			engine.encounters().end(op(), campaign, encounter, "PARTY_VICTORY", "the wolf flees");
			assertEquals(Set.of("BLINDED", "DEAFENED"), conditions(engine, campaign, pc),
					"a 10-round condition is a 1-minute condition; the encounter ending does not cure it (SRD p.13)");

			engine.sessions().advanceTime(op(), campaign, 1, "catching breath");
			assertEquals(Set.of(), conditions(engine, campaign, pc),
					"after a minute of game time both round-counted conditions have run out");
		}
	}

	// SRD 5.2.1 p.181 "Exhaustion": "Finishing a Long Rest removes 1 of your Exhaustion levels."
	// p.137 "Greater Restoration": "remove one of the following effects from it: 1 Exhaustion level".
	// No SRD effect removes more than one level at a time; clearing all levels is a GM decision, so
	// REMOVE_CONDITION EXHAUSTION takes one level unless an explicit `levels` argument says how many.
	@Test
	void removingExhaustionTakesOneLevelAtATimeUnlessTheGmOverrides() throws Exception {
		try (Engine engine = engine(tempDb("srd-exhaustion-removal"))) {
			String campaign = committedCampaign(engine);
			engine.sessions().bootstrap(op(), campaign, null);
			String pc = "character:1";
			for (int level = 0; level < 3; level++) {
				engine.runtime().applyRuntimeChange(op(), campaign, pc,
						map("kind", "ADD_CONDITION", "condition", "EXHAUSTION", "reason", "a forced march"));
			}
			assertEquals(3, sheet(engine, campaign, pc).get("exhaustion"));

			Map<String, Object> removed = engine.runtime().applyRuntimeChange(op(), campaign, pc,
					map("kind", "REMOVE_CONDITION", "condition", "EXHAUSTION", "reason", "a night's recovery"));
			assertEquals(1, removed.get("removed"), "one level at a time (SRD 5.2.1 p.181, p.137)");
			assertEquals(2, removed.get("exhaustion"));
			assertEquals(2, sheet(engine, campaign, pc).get("exhaustion"));
			assertEquals(2, condition(sheet(engine, campaign, pc), "EXHAUSTION").get("level"));

			// The chosen API for a GM who wants more: an explicit `levels` count on the same change.
			Map<String, Object> cleared = engine.runtime().applyRuntimeChange(op(), campaign, pc,
					map("kind", "REMOVE_CONDITION", "condition", "EXHAUSTION", "levels", 2, "reason", "GM fiat"));
			assertEquals(2, cleared.get("removed"));
			assertEquals(0, sheet(engine, campaign, pc).get("exhaustion"));
			assertFalse(conditions(engine, campaign, pc).contains("EXHAUSTION"));
		}
	}

	// SRD 5.2.1 p.18 "Temporary Hit Points": "If you have Temporary Hit Points and take damage, those
	// points are lost first, and any leftover damage carries over to your Hit Points." and "If you
	// have 0 Hit Points, receiving Temporary Hit Points doesn't restore you to consciousness."
	@Test
	void temporaryHitPointsAreLostFirstAndDoNotWakeAnUnconsciousCreature() throws Exception {
		try (Engine engine = engine(tempDb("srd-temporary-hp"))) {
			String campaign = committedCampaign(engine);
			engine.sessions().bootstrap(op(), campaign, null);
			String pc = "character:1";
			int max = (Integer) hp(engine, campaign, pc).get("max");
			assertTrue(max > 3, "fixture: enough hit points to survive the carry-over, has " + max);
			engine.runtime().applyRuntimeChange(op(), campaign, pc,
					map("kind", "SET_TEMP_HP", "amount", 7, "reason", "a protective blessing"));
			assertEquals(7, hp(engine, campaign, pc).get("temp"));
			Map<String, Object> hit = engine.runtime().applyRuntimeChange(op(), campaign, pc,
					map("kind", "DAMAGE", "amount", 10, "damage_type", "slashing", "reason", "a claw"));
			assertEquals(7, hit.get("temp_hp_absorbed"));
			Map<String, Object> afterHit = hp(engine, campaign, pc);
			assertEquals(0, afterHit.get("temp"), "the Temporary Hit Points are lost first");
			assertEquals(max - 3, afterHit.get("current"), "the leftover 3 carries over to the Hit Points");

			// Down to 0: Dying and Unconscious. Temporary Hit Points are not healing.
			engine.runtime().applyRuntimeChange(op(), campaign, pc,
					map("kind", "DAMAGE", "amount", max - 3, "damage_type", "slashing", "reason", "a second claw"));
			assertEquals("DYING", sheet(engine, campaign, pc).get("life_state"));
			assertTrue(conditions(engine, campaign, pc).contains("UNCONSCIOUS"));
			Map<String, Object> granted = engine.runtime().applyRuntimeChange(op(), campaign, pc,
					map("kind", "SET_TEMP_HP", "amount", 5, "reason", "a blessing on the fallen"));
			assertEquals(5, granted.get("temp_hp"));
			Map<String, Object> afterGrant = hp(engine, campaign, pc);
			assertEquals(5, afterGrant.get("temp"));
			assertEquals(0, afterGrant.get("current"), "Temporary Hit Points are not Hit Points");
			assertEquals("DYING", sheet(engine, campaign, pc).get("life_state"),
					"receiving Temporary Hit Points at 0 HP does not restore consciousness (SRD 5.2.1 p.18)");
			assertTrue(conditions(engine, campaign, pc).contains("UNCONSCIOUS"));
		}
	}

	// SRD 5.2.1 p.17 "Hit Point Maximum of 0": "A creature dies if its Hit Point maximum reaches 0."
	// Legal scores alone cannot get a level-1 d6 sorcerer there (Constitution 1 is -5: 6 - 5 = 1), so
	// a reduction effect first brings the maximum to 1 and a Constitution loss of one modifier step
	// then takes the last point: the maximum reaches 0 and the creature dies, whatever lowered it.
	@Test
	void aCreatureWhoseHitPointMaximumDropsToZeroDiesEvenThroughAnAbilityScoreChange() throws Exception {
		try (Engine engine = engine(tempDb("srd-max-hp-zero"))) {
			String campaign = committedCampaign(engine);
			engine.sessions().bootstrap(op(), campaign, null);
			String pc = "character:1";
			assertEquals(1, sheet(engine, campaign, pc).get("level"));
			int base = (Integer) hp(engine, campaign, pc).get("max");
			Map<String, Object> drained = engine.runtime().applyRuntimeChange(op(), campaign, pc,
					map("kind", "ADJUST_MAX_HP", "amount", 1 - base, "until", "RESTORED", "reason", "a life drain"));
			assertEquals("ALIVE", drained.get("life_state"));
			assertEquals(1, hp(engine, campaign, pc).get("max"));
			assertEquals(1, hp(engine, campaign, pc).get("current"));

			int con = (Integer) m(m(sheet(engine, campaign, pc).get("abilities")).get("CON")).get("score");
			assertTrue(con >= 3, "fixture: room to lower Constitution by two, has " + con);
			assertEquals(-1, LevelUpService.constitutionHp(con, con - 2, 1),
					"two points of Constitution are one modifier step, one hit point per level");
			engine.rest().override(op(), campaign, "SET_ABILITY_SCORE", pc, map("ability", "CON", "score", con - 2),
					"the sickness deepens", null);
			Map<String, Object> after = sheet(engine, campaign, pc);
			assertEquals(0, m(after.get("hp")).get("current"));
			assertEquals("DEAD", after.get("life_state"),
					"a creature dies if its Hit Point maximum reaches 0 (SRD 5.2.1 p.17), also by a Constitution loss");
		}
	}

	/** Levels the fixture sorcerer with a rolled d6 of 4 each time (the campaign's ROLL policy). */
	private static void levelTo(Engine engine, ScriptedRollService dice, String campaign, String pc, int level) {
		engine.runtime().awardXp(op(), campaign, List.of(pc), engine.rules().xpThreshold(level), "QUEST",
				"a long road");
		for (int next = 2; next <= level; next++) {
			dice.queue(4);
			String t = (String) m(engine.levelUps().begin(op(), campaign, pc).get("transaction")).get("ref");
			if (next == 2) {
				engine.levelUps().update(op(), campaign, t, null, map("metamagic", List.of("empowered", "quickened")));
			}
			engine.levelUps().commit(op(), campaign, t, null);
		}
	}

	private static Map<String, Object> sheet(Engine engine, String campaign, String pc) {
		return engine.characters().characterSheet(campaign, pc, "PLAY");
	}

	private static Map<String, Object> hp(Engine engine, String campaign, String pc) {
		return m(sheet(engine, campaign, pc).get("hp"));
	}

	private static Set<String> conditions(Engine engine, String campaign, String pc) {
		return list(sheet(engine, campaign, pc).get("conditions")).stream().map(c -> c.get("condition").toString())
				.collect(Collectors.toSet());
	}

	/** The named condition from a result or sheet carrying a {@code conditions} list. */
	private static Map<String, Object> condition(Map<String, Object> carrier, String condition) {
		return list(carrier.get("conditions")).stream().filter(c -> condition.equals(c.get("condition"))).findFirst()
				.orElseThrow(() -> new AssertionError(condition + " missing from " + carrier.get("conditions")));
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
