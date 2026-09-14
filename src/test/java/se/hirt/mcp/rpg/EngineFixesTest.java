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

import static org.junit.jupiter.api.Assertions.*;
import static se.hirt.mcp.rpg.TestCampaigns.map;
import static se.hirt.mcp.rpg.TestCampaigns.op;

/**
 * Defects found in play (the harpy fights of 2026-09-13/14) and the rules they violated: the
 * retroactive Constitution hit points at an ASI (SRD 5.2.1 "Constitution"), allies who never
 * threaten each other with opportunity attacks, a turn order that moves past the dead and the
 * removed, spell slots spendable as resources, readable condition durations, and surprise.
 */
class EngineFixesTest {

	@SuppressWarnings("unchecked")
	private static Map<String, Object> m(Object o) {
		return (Map<String, Object>) o;
	}

	@SuppressWarnings("unchecked")
	private static List<Map<String, Object>> list(Object o) {
		return (List<Map<String, Object>>) o;
	}

	private static Map<String, Object> participant(Map<String, Object> state, String character) {
		return list(state.get("participants")).stream().filter(p -> character.equals(p.get("character"))).findFirst()
				.orElseThrow();
	}

	private static int score(Engine engine, String campaign, String pc, String ability) {
		return (Integer) m(m(engine.characters().characterSheet(campaign, pc, "PLAY").get("abilities")).get(ability))
				.get("score");
	}

	private static Map<String, Object> hp(Engine engine, String campaign, String pc) {
		return m(engine.characters().characterSheet(campaign, pc, "PLAY").get("hp"));
	}

	/**
	 * Levels the fixture sorcerer to 3 with a rolled d6 of 4 each time; returns the level-4
	 * transaction.
	 */
	private static String levelToFour(Engine engine, ScriptedRollService dice, String campaign, String pc) {
		engine.runtime().awardXp(op(), campaign, List.of(pc), 2700, "QUEST", "a long road");
		for (int level = 2; level <= 3; level++) {
			dice.queue(4);
			String t = (String) m(engine.levelUps().begin(op(), campaign, pc).get("transaction")).get("ref");
			if (level == 2) {
				engine.levelUps().update(op(), campaign, t, null, map("metamagic", List.of("empowered", "quickened")));
			}
			engine.levelUps().commit(op(), campaign, t, null);
		}
		dice.queue(4);
		return (String) m(engine.levelUps().begin(op(), campaign, pc).get("transaction")).get("ref");
	}

	@Test
	void aHigherConstitutionModifierRaisesHitPointsForEveryLevelAttained() throws Exception {
		try (Engine engine = TestCampaigns.engine(TestCampaigns.tempDb("con-hp"))) {
			ScriptedRollService dice = (ScriptedRollService) engine.roller();
			String campaign = TestCampaigns.committedCampaign(engine);
			engine.sessions().bootstrap(op(), campaign, null);
			String pc = "character:1";
			String t4 = levelToFour(engine, dice, campaign, pc);
			int conBefore = score(engine, campaign, pc, "CON");
			int maxBefore = (Integer) hp(engine, campaign, pc).get("max");
			// +2 to an even score, or +1 to an odd one: either way the modifier goes up by exactly one.
			Map<String, Object> asi = conBefore % 2 == 0 ? map("CON", 2) : map("CON", 1, "INT", 1);
			int conAfter = conBefore + ((Number) asi.get("CON")).intValue();
			assertEquals(4, LevelUpService.constitutionHp(conBefore, conAfter, 4), "one hit point per level attained");
			engine.levelUps().update(op(), campaign, t4, null, map("ability_score_improvement", asi));
			Map<String, Object> done = engine.levelUps().commit(op(), campaign, t4, null);
			int gain = (Integer) done.get("hp_gain");
			assertEquals(4, done.get("constitution_hp"), "reported on the commit");
			Map<String, Object> after = hp(engine, campaign, pc);
			assertEquals(maxBefore + gain + 4, after.get("max"), "the die, plus one retroactive point per level");
			assertEquals(after.get("max"), after.get("current"), "the raise is granted, not just the ceiling");
			assertEquals(conAfter, score(engine, campaign, pc, "CON"));

			// The same rule through a fiat score change: +2 more Constitution at level 4 is four more hit points.
			engine.rest().override(op(), campaign, "SET_ABILITY_SCORE", pc,
					map("ability", "CON", "score", conAfter + 2), "a boon", null);
			Map<String, Object> boosted = hp(engine, campaign, pc);
			assertEquals(maxBefore + gain + 8, boosted.get("max"));
			assertEquals(boosted.get("max"), boosted.get("current"));
			// Losing it again clamps current hit points to the new maximum.
			engine.rest().override(op(), campaign, "SET_ABILITY_SCORE", pc, map("ability", "CON", "score", conAfter),
					"the boon fades", null);
			Map<String, Object> faded = hp(engine, campaign, pc);
			assertEquals(maxBefore + gain + 4, faded.get("max"));
			assertEquals(faded.get("max"), faded.get("current"));
		}
	}

	@Test
	void spellSlotsCanBeSpentAndRestoredAsResources() throws Exception {
		try (Engine engine = TestCampaigns.engine(TestCampaigns.tempDb("slot-resource"))) {
			String campaign = TestCampaigns.committedCampaign(engine);
			engine.sessions().bootstrap(op(), campaign, null);
			String pc = "character:1";
			Map<String, Object> slots = m(
					m(engine.characters().characterSheet(campaign, pc, "PLAY").get("spellcasting")).get("slots"));
			int max = (Integer) m(slots.get("1")).get("max");
			assertTrue(max >= 2, "a level-1 sorcerer has two first-level slots");

			// A readied spell is cast on its trigger, outside cast_spell: the slot is spent by hand.
			Map<String, Object> used = engine.runtime().applyRuntimeChange(op(), campaign, pc,
					map("kind", "USE_RESOURCE", "resource", "spell_slot:1", "reason", "readied Magic Missile"));
			assertEquals("slot:1", m(used.get("resource")).get("ref"));
			assertEquals(max - 1, m(used.get("resource")).get("current"));
			assertEquals(max - 1,
					m(m(m(engine.characters().characterSheet(campaign, pc, "PLAY").get("spellcasting")).get("slots"))
							.get("1")).get("current"));
			engine.runtime().applyRuntimeChange(op(), campaign, pc,
					map("kind", "RESTORE_RESOURCE", "resource", "slot 1", "reason", "the trigger never came"));
			assertEquals(max,
					m(m(m(engine.characters().characterSheet(campaign, pc, "PLAY").get("spellcasting")).get("slots"))
							.get("1")).get("current"));
			RpgException unknown = assertThrows(RpgException.class, () -> engine.runtime().applyRuntimeChange(op(),
					campaign, pc, map("kind", "USE_RESOURCE", "resource", "spell_slot:9", "reason", "x")));
			assertEquals(ErrorCode.INVALID_ARGUMENT, unknown.code());
			assertTrue(unknown.getMessage().contains("spell_slot:<level>"), unknown.getMessage());
		}
	}

	@Test
	void conditionDurationsAreReadFromPlainWords() throws Exception {
		try (Engine engine = TestCampaigns.engine(TestCampaigns.tempDb("condition-duration"))) {
			String campaign = TestCampaigns.committedCampaign(engine);
			engine.sessions().bootstrap(op(), campaign, null);
			String pc = "character:1";
			Map<String, Object> prone = engine.runtime().applyRuntimeChange(op(), campaign, pc,
					map("kind", "ADD_CONDITION", "condition", "PRONE", "duration", "1 minute", "reason", "tripped"));
			Map<String, Object> d = m(list(prone.get("conditions")).get(0).get("duration"));
			assertEquals("MINUTES", d.get("kind"));
			assertTrue(d.get("expires_seq") instanceof Number, "an expiry the clock can reach: " + d);
			Map<String, Object> charmed = engine.runtime().applyRuntimeChange(op(), campaign, pc,
					map("kind", "ADD_CONDITION", "condition", "CHARMED", "duration", "long rest", "reason", "a song"));
			assertEquals("LONG_REST",
					m(list(charmed.get("conditions")).stream().filter(c -> "CHARMED".equals(c.get("condition")))
							.findFirst().orElseThrow().get("duration")).get("until"));
			Map<String, Object> blinded = engine.runtime().applyRuntimeChange(op(), campaign, pc, map("kind",
					"ADD_CONDITION", "condition", "BLINDED", "duration", map("rounds", 3), "reason", "sand"));
			assertEquals("MINUTES",
					m(list(blinded.get("conditions")).stream().filter(c -> "BLINDED".equals(c.get("condition")))
							.findFirst().orElseThrow().get("duration")).get("kind"),
					"rounds outside an encounter degrade to a minute");
			RpgException bad = assertThrows(RpgException.class, () -> engine.runtime().applyRuntimeChange(op(),
					campaign, pc,
					map("kind", "ADD_CONDITION", "condition", "DEAFENED", "duration", "a while", "reason", "x")));
			assertEquals(ErrorCode.INVALID_ARGUMENT, bad.code());

			// The clock moves two minutes: the minute-long condition is gone, the long-rest one stays.
			engine.sessions().advanceTime(op(), campaign, 2, "a moment");
			List<Map<String, Object>> left = list(
					engine.characters().characterSheet(campaign, pc, "PLAY").get("conditions"));
			assertTrue(left.stream().noneMatch(c -> "PRONE".equals(c.get("condition"))), "expired: " + left);
			assertTrue(left.stream().anyMatch(c -> "CHARMED".equals(c.get("condition"))), "kept: " + left);
		}
	}

	@Test
	void alliesNeverThreatenEachOtherWithOpportunityAttacks() throws Exception {
		try (Engine engine = TestCampaigns.engine(TestCampaigns.tempDb("ally-oa"))) {
			ScriptedRollService dice = (ScriptedRollService) engine.roller();
			String campaign = TestCampaigns.committedCampaign(engine);
			engine.sessions().bootstrap(op(), campaign, null);
			String pc = "character:1";
			String ally = (String) engine.runtime()
					.materialize(op(), campaign, "Bandit", "Hob", null, null, null, null, false).get("character");
			String enemy = (String) engine.runtime()
					.materialize(op(), campaign, "Bandit", null, null, null, null, null, false).get("character");
			dice.queue(20, 50, 15, 50, 10, 50);
			Map<String, Object> started = engine.encounters().start(op(), campaign,
					map("party", List.of(pc, ally), "raiders", List.of(enemy)), null, map(enemy, "far"), null, null,
					null, null);
			String enc = (String) started.get("encounter");
			assertEquals(25, ((Number) m(started.get("sides")).get("xp_pool")).intValue(),
					"only the hostile bandit is in the pool, not the ally with the same stat block");
			// The PC walks away from the ally: no ally reaction, no pending choice, nothing to resolve.
			Map<String, Object> move = engine.encounters().perform(op(), campaign, enc, pc,
					map("kind", "MOVE", "zone", "far"), true);
			assertEquals(List.of(), move.get("opportunity_attacks"));
			assertNull(move.get("pending_choices"));
			assertEquals(Boolean.TRUE, move.get("turn_advanced"));
			assertEquals("RUNNING", m(move.get("state")).get("status"));
		}
	}

	@Test
	void aTurnHolderKilledOutOfTurnIsSkippedAndCounted() throws Exception {
		try (Engine engine = TestCampaigns.engine(TestCampaigns.tempDb("turn-skip"))) {
			ScriptedRollService dice = (ScriptedRollService) engine.roller();
			String campaign = TestCampaigns.committedCampaign(engine);
			engine.sessions().bootstrap(op(), campaign, null);
			String pc = "character:1";
			String first = (String) engine.runtime()
					.materialize(op(), campaign, "Bandit", "First", null, null, null, null, false).get("character");
			String second = (String) engine.runtime()
					.materialize(op(), campaign, "Bandit", "Second", null, null, null, null, false).get("character");
			dice.queue(5, 50, 20, 50, 12, 50);
			Map<String, Object> started = engine.encounters().start(op(), campaign,
					map("party", List.of(pc), "raiders", List.of(first, second)), null, null, null, null, null, null);
			String enc = (String) started.get("encounter");
			assertEquals(first, m(started.get("turn")).get("character"), "First goes first");

			// A fall kills the turn holder outside the attack loop.
			Map<String, Object> fall = engine.runtime().applyRuntimeChange(op(), campaign, first,
					map("kind", "DAMAGE", "amount", 30, "damage_type", "bludgeoning", "reason", "a fall"));
			assertEquals("DEAD", fall.get("life_state"));
			assertEquals("DEFEATED",
					participant(engine.encounters().encounterState(campaign, enc, 3), first).get("status"),
					"out of the fight at once");
			// The next participant acts: the corpse's turn is skipped, not wedged.
			Map<String, Object> next = engine.encounters().perform(op(), campaign, enc, second, map("kind", "END_TURN"),
					true);
			assertEquals(List.of("First"), next.get("turns_skipped"));
			assertEquals(pc, m(next.get("next_turn")).get("character"));

			// A fiat revival puts the participant back into the order; a fiat removal keeps the order moving.
			engine.rest().override(op(), campaign, "SET_LIFE_STATE", first, map("life_state", "ALIVE", "hp", 5),
					"the GM relents", null);
			assertEquals("ACTIVE",
					participant(engine.encounters().encounterState(campaign, enc, 3), first).get("status"));
			engine.rest().override(op(), campaign, "REMOVE_ENCOUNTER_PARTICIPANT", pc, null, "the PC steps out", null);
			Map<String, Object> afterRemoval = engine.encounters().perform(op(), campaign, enc, first,
					map("kind", "END_TURN"), true);
			assertTrue(list(afterRemoval.get("turns_skipped")) != null
					&& afterRemoval.get("turns_skipped").toString().contains("Richard"), "" + afterRemoval);
			assertEquals(second, m(afterRemoval.get("next_turn")).get("character"));

			// A hostile killed by a runtime change is itemized among the defeated.
			engine.runtime().applyRuntimeChange(op(), campaign, second,
					map("kind", "DAMAGE", "amount", 30, "damage_type", "fire", "reason", "a wall of fire"));
			Map<String, Object> ended = engine.encounters().end(op(), campaign, enc, "PARTY_VICTORY", "done");
			List<String> defeated = (List<String>) ended.get("defeated");
			assertTrue(defeated.stream().anyMatch(s -> s.startsWith("Second (25 XP)")), "" + defeated);
			assertTrue(defeated.stream().noneMatch(s -> s.startsWith("First")), "revived: " + defeated);
		}
	}

	@Test
	void anOpportunityChoiceWhoseMoverAlreadyFellIsVoid() throws Exception {
		try (Engine engine = TestCampaigns.engine(TestCampaigns.tempDb("oa-void"))) {
			ScriptedRollService dice = (ScriptedRollService) engine.roller();
			String campaign = TestCampaigns.committedCampaign(engine);
			engine.sessions().bootstrap(op(), campaign, null);
			String pc = "character:1";
			String ally = (String) engine.runtime()
					.materialize(op(), campaign, "Bandit", "Hob", null, null, null, null, false).get("character");
			String enemy = (String) engine.runtime()
					.materialize(op(), campaign, "Bandit", null, null, null, null, null, false).get("character");
			engine.runtime().applyRuntimeChange(op(), campaign, enemy,
					map("kind", "DAMAGE", "amount", 10, "damage_type", "slashing", "reason", "worn down"));
			dice.queue(20, 50, 15, 50, 10, 50);
			String enc = (String) engine.encounters().start(op(), campaign,
					map("party", List.of(pc, ally), "raiders", List.of(enemy)), null, null, null, null, null, null)
					.get("encounter");
			engine.encounters().perform(op(), campaign, enc, pc, map("kind", "END_TURN"), true);
			engine.encounters().perform(op(), campaign, enc, ally, map("kind", "END_TURN"), true);
			// The enemy leaves the zone: the PC is asked first, then the ally's automatic scimitar kills it.
			dice.queue(19, 6);
			Map<String, Object> move = engine.encounters().perform(op(), campaign, enc, enemy,
					map("kind", "MOVE", "zone", "far"), true);
			List<Map<String, Object>> pending = list(move.get("pending_choices"));
			assertEquals(1, pending.size());
			assertEquals(pc, pending.get(0).get("chooser"));
			assertEquals("DEAD", engine.characters().characterSheet(campaign, enemy, "PLAY").get("life_state"));
			Map<String, Object> resolved = engine.encounters().resolveChoice(op(), campaign,
					(String) pending.get(0).get("transaction"), map("option", "TAKE"));
			assertNotNull(resolved.get("cancelled"), "the reaction is void, not an error: " + resolved);
			assertNull(resolved.get("attack"));
			assertEquals("RUNNING", m(resolved.get("state")).get("status"));
			assertEquals(Boolean.TRUE, resolved.get("turn_advanced"));
			assertEquals(pc, m(resolved.get("next_turn")).get("character"));
		}
	}

	@Test
	void aSurprisedSideRollsInitiativeWithDisadvantage() throws Exception {
		try (Engine engine = TestCampaigns.engine(TestCampaigns.tempDb("surprise"))) {
			ScriptedRollService dice = (ScriptedRollService) engine.roller();
			String campaign = TestCampaigns.committedCampaign(engine);
			engine.sessions().bootstrap(op(), campaign, null);
			String pc = "character:1";
			String bandit = (String) engine.runtime()
					.materialize(op(), campaign, "Bandit", null, null, null, null, null, false).get("character");
			// A side that is not in the fight cannot be surprised.
			RpgException unknown = assertThrows(RpgException.class,
					() -> engine.encounters().start(op(), campaign,
							map("party", List.of(pc), "raiders", List.of(bandit)), null, null, null, null, null,
							map("surprised", List.of("goblins"))));
			assertEquals(ErrorCode.INVALID_ARGUMENT, unknown.code());
			// PC: d20 then the d100 tiebreak; the surprised bandit: two d20 (keep the lower) then its tiebreak.
			dice.queue(10, 50, 18, 3, 50);
			Map<String, Object> started = engine.encounters().start(op(), campaign,
					map("party", List.of(pc), "raiders", List.of(bandit)), null, null, null, null, null,
					map("surprised", List.of("raiders")));
			Map<String, Object> banditRoll = list(started.get("initiative_rolls")).stream()
					.filter(r -> "Bandit".equals(r.get("name"))).findFirst().orElseThrow();
			assertEquals(Boolean.TRUE, banditRoll.get("surprised"));
			assertEquals(3 + 1, banditRoll.get("initiative"), "the lower d20 plus the bandit's +1 Dexterity");
			assertEquals("2d20kl1+1", m(banditRoll.get("roll")).get("expression"));
			assertEquals(List.of("raiders"), m(started.get("sides")).get("surprised"));
		}
	}
}
