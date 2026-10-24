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

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import se.hirt.mcp.rpg.character.CharacterService;
import se.hirt.mcp.rpg.dice.Roll;
import se.hirt.mcp.rpg.dice.ScriptedRollService;
import se.hirt.mcp.rpg.persistence.Database;
import se.hirt.mcp.rpg.protocol.Ref;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static se.hirt.mcp.rpg.TestCampaigns.committedCampaign;
import static se.hirt.mcp.rpg.TestCampaigns.engine;
import static se.hirt.mcp.rpg.TestCampaigns.map;
import static se.hirt.mcp.rpg.TestCampaigns.op;
import static se.hirt.mcp.rpg.TestCampaigns.tempDb;

class LevelUpHitPointRollTest {

	@Test
	void abandoningAndBeginningAgainKeepsTheOriginalHitPointRoll() throws Exception {
		try (Engine engine = engine(tempDb("level-up-hit-point-roll"))) {
			String campaign = campaign(engine);
			String pc = "character:1";
			award(engine, campaign, pc, 300);
			ScriptedRollService dice = (ScriptedRollService) engine.roller();
			dice.queue(2, 6);
			Map<String, Object> first = engine.levelUps().begin(op(), campaign, pc);
			assertEquals(2, total(first));
			for (int retry = 0; retry < 3; retry++) {
				engine.levelUps().abandon(op(), campaign, transaction(first), "reconsider the level-up choices");
				Map<String, Object> next = engine.levelUps().begin(op(), campaign, pc);
				assertNotEquals(transaction(first), transaction(next));
				assertEquals(m(first.get("hit_points")).get("roll"), m(next.get("hit_points")).get("roll"));
				assertEquals(m(first.get("hit_points")).get("gain"), m(next.get("hit_points")).get("gain"));
				assertEquals(1L, rollCount(engine, campaign, pc, 2));
				first = next;
			}
			assertFalse(dice.exhausted(), "the queued replacement roll was never drawn");
		}
	}

	@Test
	void reopenAndOperationReplayReuseThePersistedOutcome() throws Exception {
		Path database = tempDb("level-up-roll-reopen");
		String campaign, firstOperation, firstTransaction;
		Map<String, Object> originalRoll;
		try (Engine engine = engine(database)) {
			campaign = campaign(engine);
			award(engine, campaign, "character:1", 300);
			((ScriptedRollService) engine.roller()).queue(2);
			firstOperation = op();
			Map<String, Object> first = engine.levelUps().begin(firstOperation, campaign, "character:1");
			firstTransaction = transaction(first);
			originalRoll = m(m(first.get("hit_points")).get("roll"));
			engine.levelUps().abandon(op(), campaign, firstTransaction, "continue in another session");
		}
		try (Engine reopened = engine(database)) {
			reopened.sessions().bootstrap(op(), campaign, null);
			ScriptedRollService dice = (ScriptedRollService) reopened.roller();
			dice.queue(6);
			Map<String, Object> retry = reopened.levelUps().begin(op(), campaign, "character:1");
			assertNotEquals(firstTransaction, transaction(retry));
			assertEquals(originalRoll, m(retry.get("hit_points")).get("roll"));
			Map<String, List<Map<String, Object>>> beforeReplay = snapshot(reopened, campaign);
			Map<String, Object> replay = reopened.levelUps().begin(firstOperation, campaign, "character:1");
			assertEquals(true, replay.get("replayed"));
			assertEquals(firstTransaction, transaction(replay));
			assertEquals(beforeReplay, snapshot(reopened, campaign));
			assertEquals(1L, rollCount(reopened, campaign, "character:1", 2));
			assertFalse(dice.exhausted());
		}
	}

	@Test
	void committingUsesTheRetainedRollAndTheFollowingLevelGetsItsOwnRoll() throws Exception {
		try (Engine engine = engine(tempDb("level-up-roll-next-level"))) {
			String campaign = campaign(engine);
			String pc = "character:1";
			award(engine, campaign, pc, 300);
			ScriptedRollService dice = (ScriptedRollService) engine.roller();
			dice.queue(2, 6);
			Map<String, Object> first = engine.levelUps().begin(op(), campaign, pc);
			engine.levelUps().abandon(op(), campaign, transaction(first), "reconsider");
			Map<String, Object> retry = engine.levelUps().begin(op(), campaign, pc);
			assertEquals(2, total(retry));
			Map<String, Object> committed = commit(engine, campaign, retry);
			assertEquals(m(retry.get("hit_points")).get("gain"), committed.get("hp_gain"));
			award(engine, campaign, pc, 600);
			Map<String, Object> third = engine.levelUps().begin(op(), campaign, pc);
			assertEquals(6, total(third));
			assertEquals(1L, rollCount(engine, campaign, pc, 2));
			assertEquals(1L, rollCount(engine, campaign, pc, 3));
			assertTrue(dice.exhausted());
		}
	}

	@Test
	void differentCharactersKeepIndependentHitPointOutcomesAtTheSameLevel() throws Exception {
		try (Engine engine = engine(tempDb("level-up-roll-character-isolation"))) {
			String campaign = campaign(engine);
			String pc = "character:1";
			String companion = promoteSorcerer(engine, campaign);
			award(engine, campaign, pc, 300);
			award(engine, campaign, companion, 300);
			ScriptedRollService dice = (ScriptedRollService) engine.roller();
			dice.queue(2, 5);
			Map<String, Object> playerAttempt = engine.levelUps().begin(op(), campaign, pc);
			assertEquals(2, total(playerAttempt));
			engine.levelUps().abandon(op(), campaign, transaction(playerAttempt), "level the companion first");
			Map<String, Object> companionAttempt = engine.levelUps().begin(op(), campaign, companion);
			assertEquals(5, total(companionAttempt));
			engine.levelUps().abandon(op(), campaign, transaction(companionAttempt), "return to the player");
			assertEquals(2, total(engine.levelUps().begin(op(), campaign, pc)));
			assertEquals(1L, rollCount(engine, campaign, pc, 2));
			assertEquals(1L, rollCount(engine, campaign, companion, 2));
			assertTrue(dice.exhausted());
		}
	}

	@Test
	void firstThreeMaximumPolicyStartsRetainingRolledOutcomesAtLevelFour() throws Exception {
		try (Engine engine = engine(tempDb("level-up-roll-first-three"))) {
			String campaign = campaign(engine);
			String pc = "character:1";
			policy(engine, campaign, "FIRST_3_MAX");
			award(engine, campaign, pc, 2700);
			ScriptedRollService dice = (ScriptedRollService) engine.roller();
			dice.queue(3, 6);
			for (int level = 2; level <= 3; level++) {
				Map<String, Object> begun = engine.levelUps().begin(op(), campaign, pc);
				assertEquals("MAX", m(begun.get("hit_points")).get("method"));
				assertNull(m(begun.get("hit_points")).get("roll"));
				commit(engine, campaign, begun);
				assertEquals(0L, rollCount(engine, campaign, pc, level));
			}
			Map<String, Object> fourth = engine.levelUps().begin(op(), campaign, pc);
			assertEquals(3, total(fourth));
			engine.levelUps().abandon(op(), campaign, transaction(fourth), "consider the feat choice again");
			Map<String, Object> retry = engine.levelUps().begin(op(), campaign, pc);
			assertEquals(3, total(retry));
			assertEquals(1L, rollCount(engine, campaign, pc, 4));
			assertFalse(dice.exhausted());
		}
	}

	@Test
	void averageProgressionRemainsFixedWithoutRecordingOrDrawingHitDice() throws Exception {
		try (Engine engine = engine(tempDb("level-up-roll-average"))) {
			String campaign = campaign(engine);
			String pc = "character:1";
			policy(engine, campaign, "AVERAGE");
			award(engine, campaign, pc, 300);
			ScriptedRollService dice = (ScriptedRollService) engine.roller();
			dice.queue(6);
			Map<String, Object> first = engine.levelUps().begin(op(), campaign, pc);
			engine.levelUps().abandon(op(), campaign, transaction(first), "reconsider a fixed gain");
			Map<String, Object> retry = engine.levelUps().begin(op(), campaign, pc);
			assertEquals("AVERAGE", m(retry.get("hit_points")).get("method"));
			assertNull(m(retry.get("hit_points")).get("roll"));
			assertEquals(m(first.get("hit_points")).get("gain"), m(retry.get("hit_points")).get("gain"));
			assertEquals(0L, rollCount(engine, campaign, pc, 2));
			assertFalse(dice.exhausted());
		}
	}

	@Test
	void legacyDuplicateRollsReuseTheEarliestMatchingDieAndRecomputeConstitution() throws Exception {
		Path database = tempDb("level-up-roll-legacy");
		String campaign;
		try (Engine engine = engine(database)) {
			campaign = campaign(engine);
			award(engine, campaign, "character:1", 300);
			constitution(engine, campaign, "character:1", 15);
			long campaignId = Ref.id(campaign, Ref.CAMPAIGN);
			engine.db().mutate(Database.Mutation.of("legacy_hit_point_roll_fixture", campaignId, op(), "GM", null),
					tx -> {
						String purpose = "hit points level 2 character:1";
						CharacterService.recordRoll(tx, campaignId, purpose,
								new Roll("1d8", List.of(1), List.of(), 0, 1));
						CharacterService.recordRoll(tx, campaignId, purpose,
								new Roll("1d6", List.of(2), List.of(), 0, 2));
						CharacterService.recordRoll(tx, campaignId, purpose,
								new Roll("1d6", List.of(6), List.of(), 0, 6));
						return Map.of();
					});
			Map<String, Object> first = engine.levelUps().begin(op(), campaign, "character:1");
			assertEquals(2, total(first));
			assertEquals(4, m(first.get("hit_points")).get("gain"));
			engine.levelUps().abandon(op(), campaign, transaction(first), "resume the older campaign later");
		}
		try (Engine reopened = engine(database)) {
			reopened.sessions().bootstrap(op(), campaign, null);
			constitution(reopened, campaign, "character:1", 17);
			ScriptedRollService dice = (ScriptedRollService) reopened.roller();
			dice.queue(5);
			Map<String, Object> retry = reopened.levelUps().begin(op(), campaign, "character:1");
			assertEquals(2, total(retry));
			assertEquals(5, m(retry.get("hit_points")).get("gain"),
					"the retained raw die uses the current Constitution modifier");
			assertEquals(3L, rollCount(reopened, campaign, "character:1", 2));
			assertFalse(dice.exhausted());
		}
	}

	@Test
	void checkpointsKeepEarlierOutcomesAndRemoveOnlyOutcomesFromTheDiscardedBranch() throws Exception {
		try (Engine engine = engine(tempDb("level-up-roll-checkpoints"))) {
			String campaign = campaign(engine);
			String pc = "character:1";
			award(engine, campaign, pc, 300);
			String beforeRoll = (String) engine.checkpoints().create(op(), campaign, "before the first hit die")
					.get("checkpoint");
			ScriptedRollService dice = (ScriptedRollService) engine.roller();
			dice.queue(2, 6);
			Map<String, Object> first = engine.levelUps().begin(op(), campaign, pc);
			engine.levelUps().abandon(op(), campaign, transaction(first), "retain the first outcome");
			String afterRoll = (String) engine.checkpoints().create(op(), campaign, "after the first hit die")
					.get("checkpoint");
			Map<String, Object> retry = engine.levelUps().begin(op(), campaign, pc);
			assertEquals(2, total(retry));
			engine.levelUps().abandon(op(), campaign, transaction(retry), "restore the later checkpoint");
			engine.checkpoints().restore(op(), campaign, afterRoll, "the original outcome predates this checkpoint");
			assertEquals(1L, rollCount(engine, campaign, pc, 2));
			Map<String, Object> retained = engine.levelUps().begin(op(), campaign, pc);
			assertEquals(2, total(retained));
			engine.levelUps().abandon(op(), campaign, transaction(retained), "restore before any hit die existed");
			engine.checkpoints().restore(op(), campaign, beforeRoll, "discard the branch containing the outcome");
			assertEquals(0L, rollCount(engine, campaign, pc, 2));
			Map<String, Object> fresh = engine.levelUps().begin(op(), campaign, pc);
			assertEquals(6, total(fresh));
			assertEquals(1L, rollCount(engine, campaign, pc, 2));
			assertTrue(dice.exhausted());
		}
	}

	@Test
	void automaticCompanionAdvancementUsesTheAbandonedManualRollBeforeRollingTheNextLevel() throws Exception {
		try (Engine engine = engine(tempDb("level-up-roll-companion-handoff"))) {
			String campaign = campaign(engine);
			String companion = promoteSorcerer(engine, campaign);
			int beforeHp = ((Number) m(engine.characters().characterSheet(campaign, companion, "PLAY").get("hp"))
					.get("max")).intValue();
			int con = ((Number) m(
					m(engine.characters().characterSheet(campaign, companion, "PLAY").get("abilities")).get("CON"))
					.get("modifier")).intValue();
			award(engine, campaign, companion, 300);
			ScriptedRollService dice = (ScriptedRollService) engine.roller();
			dice.queue(2, 6);
			Map<String, Object> manual = engine.levelUps().begin(op(), campaign, companion);
			assertEquals(2, total(manual));
			engine.levelUps().abandon(op(), campaign, transaction(manual), "hand companion advancement to the engine");
			engine.rest().override(op(), campaign, "SET_CAMPAIGN_RULE", null,
					map("rule", "companion_level_up", "value", "ENGINE"), "the engine advances the companion", null);

			Map<String, Object> awarded = engine.runtime().awardXp(op(), campaign, List.of(companion), 600, "QUEST",
					"reach level three");

			List<Map<String, Object>> levels = list(awarded.get("companion_level_ups"));
			assertEquals(2, levels.size());
			assertEquals(Math.max(1, 2 + con), levels.getFirst().get("hp_gain"));
			assertEquals(Math.max(1, 6 + con), levels.getLast().get("hp_gain"));
			Map<String, Object> sheet = engine.characters().characterSheet(campaign, companion, "PLAY");
			assertEquals(3, sheet.get("level"));
			assertEquals(beforeHp + Math.max(1, 2 + con) + Math.max(1, 6 + con), m(sheet.get("hp")).get("max"));
			assertEquals(1L, rollCount(engine, campaign, companion, 2));
			assertEquals(1L, rollCount(engine, campaign, companion, 3));
			assertTrue(dice.exhausted());
		}
	}

	private static String campaign(Engine engine) {
		String campaign = committedCampaign(engine);
		engine.sessions().bootstrap(op(), campaign, null);
		return campaign;
	}

	private static void award(Engine engine, String campaign, String character, int xp) {
		engine.runtime().awardXp(op(), campaign, List.of(character), xp, "QUEST", "reach the next level");
	}

	private static String transaction(Map<String, Object> begun) {
		return (String) m(begun.get("transaction")).get("ref");
	}

	private static int total(Map<String, Object> begun) {
		return ((Number) m(m(begun.get("hit_points")).get("roll")).get("total")).intValue();
	}

	private static long rollCount(Engine engine, String campaign, String character, int level) {
		return engine.db().read(tx -> tx.count("SELECT COUNT(*) FROM roll WHERE campaign_id = ? AND purpose = ?",
				Ref.id(campaign, Ref.CAMPAIGN), "hit points level " + level + " " + character));
	}

	private static void policy(Engine engine, String campaign, String policy) {
		engine.rest().override(op(), campaign, "SET_CAMPAIGN_RULE", null,
				map("rule", "hp_progression", "value", policy), "set the campaign's hit-point progression", null);
	}

	private static void constitution(Engine engine, String campaign, String character, int score) {
		engine.rest().override(op(), campaign, "SET_ABILITY_SCORE", character, map("ability", "CON", "score", score),
				"Constitution changes before finishing the level-up", null);
	}

	private static Map<String, Object> commit(Engine engine, String campaign, Map<String, Object> begun) {
		if (begun.get("metamagic_choice") != null) {
			engine.levelUps().update(op(), campaign, transaction(begun), null,
					map("metamagic", List.of("Subtle Spell", "Distant Spell")));
		}
		return engine.levelUps().commit(op(), campaign, transaction(begun), null);
	}

	private static String promoteSorcerer(Engine engine, String campaign) {
		String companion = (String) engine.runtime()
				.materialize(op(), campaign, "Scout", "The companion", null, null, null, null, false).get("character");
		engine.party().updateMembership(op(), campaign, companion, "JOIN", "travelling together");
		Map<String, Object> first = engine.levelUps().begin(op(), campaign, companion);
		assertNull(m(first.get("hit_points")).get("roll"), "the first class level uses the maximum hit die");
		engine.levelUps().update(op(), campaign, transaction(first), null,
				map("class", "Sorcerer", "skills", List.of("Arcana", "Persuasion")));
		engine.levelUps().commit(op(), campaign, transaction(first), null);
		return companion;
	}

	private static Map<String, List<Map<String, Object>>> snapshot(Engine engine, String campaign) {
		return engine.db().snapshot(Ref.id(campaign, Ref.CAMPAIGN));
	}

	@SuppressWarnings("unchecked")
	private static List<Map<String, Object>> list(Object value) {
		return (List<Map<String, Object>>) value;
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> m(Object value) {
		return (Map<String, Object>) value;
	}
}
