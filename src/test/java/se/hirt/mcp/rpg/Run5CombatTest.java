/* Copyright (C) 2026 Marcus Hirt. SPDX-License-Identifier: BSD-3-Clause */
package se.hirt.mcp.rpg;

import org.junit.jupiter.api.Test;
import se.hirt.mcp.rpg.dice.ScriptedRollService;
import se.hirt.mcp.rpg.maps.SceneService;
import se.hirt.mcp.rpg.persistence.Database;
import se.hirt.mcp.rpg.protocol.Ref;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static se.hirt.mcp.rpg.TestCampaigns.*;

class Run5CombatTest {

	@Test
	void aNonlethalReactionSavesTheNextTurnEvenWhenTheMoverDidNotEndItsTurn() throws Exception {
		var file = tempDb("run5-knockout-next-turn");
		Fight fight;
		String resolution;
		Map<String, Object> choice;
		try (Engine e = engine(file)) {
			fight = reactionFight(e);
			Map<String, Object> move = e.encounters().perform(op(), fight.campaign(), fight.encounter(), fight.mover(),
					map("kind", "MOVE", "x_ft", 25, "y_ft", 5), false);
			assertEquals(false, move.get("turn_advanced"));
			String transaction = SceneService.maps(move.get("pending_choices")).getFirst().get("transaction")
					.toString();
			resolution = op();
			choice = map("option", "TAKE", "weapon", fight.dagger(), "nonlethal", true);
			dice(e).queue(19, 1);
			Map<String, Object> resolved = e.encounters().resolveChoice(resolution, fight.campaign(), transaction,
					choice);
			Map<String, Object> attack = SceneService.map(resolved.get("attack"));
			assertEquals(true, attack.get("knocked_out"));
			assertEquals(1, ((Number) attack.get("hp_before")).intValue());
			assertEquals(1, ((Number) attack.get("hp_after")).intValue());
			String summary = attack.get("summary").toString();
			assertTrue(summary.contains("instead of dropping to 0 HP"));
			assertTrue(summary.contains("Unconscious at 1 HP"));
			assertFalse(summary.contains("1 → 1 HP"),
					"The summary explains the knockout choice rather than apparent zero damage.");
			assertEquals(false, SceneService.map(resolved.get("movement_resumed")).get("completed"));
			assertEquals(true, resolved.get("turn_advanced"));
			assertEquals(fight.next(), SceneService.map(resolved.get("next_turn")).get("character"));
			assertEquals(fight.next(), turnCharacter(SceneService.map(resolved.get("state"))));
			assertCurrentTurn(e, fight, fight.next());
			long cid = Ref.id(fight.campaign(), Ref.CAMPAIGN);
			var after = e.db().snapshot(cid);
			assertEquals(true,
					e.encounters().resolveChoice(resolution, fight.campaign(), transaction, choice).get("replayed"));
			assertEquals(after, e.db().snapshot(cid));
			assertTrue(dice(e).exhausted());
		}
		try (Engine e = engine(file)) {
			assertCurrentTurn(e, fight, fight.next());
			Map<String, Object> acted = e.encounters().perform(op(), fight.campaign(), fight.encounter(), fight.next(),
					map("kind", "DODGE"), false);
			assertEquals(false, acted.get("turn_advanced"));
			assertEquals(fight.next(), turnCharacter(SceneService.map(acted.get("state"))));
			assertTrue(e.encounters().encounterState(fight.campaign(), fight.encounter(), 100, "GM").get("recent_log")
					.toString().contains("instead of dropping to 0 HP"));
		}
	}

	@Test
	void endingALegacyUnavailableSavedTurnRepairsItWithoutEndingTheNextCreaturesTurn() throws Exception {
		try (Engine e = engine(tempDb("run5-legacy-unavailable-turn"))) {
			Fight fight = reactionFight(e);
			var move = e.encounters().perform(op(), fight.campaign(), fight.encounter(), fight.mover(),
					map("kind", "MOVE", "x_ft", 25, "y_ft", 5), false);
			String transaction = SceneService.maps(move.get("pending_choices")).getFirst().get("transaction")
					.toString();
			dice(e).queue(19, 1);
			e.encounters().resolveChoice(op(), fight.campaign(), transaction,
					map("option", "TAKE", "weapon", fight.dagger(), "nonlethal", true));
			long cid = Ref.id(fight.campaign(), Ref.CAMPAIGN), eid = Ref.id(fight.encounter(), Ref.ENCOUNTER);
			long mid = Ref.id(fight.mover(), Ref.CHARACTER);
			// Reproduce the saved state left by the older binary after the same completed reaction.
			e.db().mutate(Database.Mutation.of("legacy-reaction-turn-fixture", cid, op(), "GM", Map.of()), tx -> {
				long pointer = tx
						.queryOne("SELECT id FROM encounter_participant WHERE encounter_id=? AND character_id=?", eid,
								mid)
						.orElseThrow().id();
				tx.update("encounter", eid, map("turn_participant_id", pointer));
				return Map.of();
			});
			assertEquals(fight.mover(),
					turnCharacter(e.encounters().encounterState(fight.campaign(), fight.encounter(), 10, "GM")));
			Map<String, Object> recovered = e.encounters().perform(op(), fight.campaign(), fight.encounter(),
					fight.mover(), map("kind", "END_TURN"), true);
			assertEquals(true, recovered.get("turn_advanced"));
			assertEquals(fight.next(), SceneService.map(recovered.get("next_turn")).get("character"));
			assertCurrentTurn(e, fight, fight.next());
			Map<String, Object> current = SceneService
					.map(e.encounters().encounterState(fight.campaign(), fight.encounter(), 10, "GM").get("turn"));
			assertEquals(true, current.get("action_available"));
			e.encounters().perform(op(), fight.campaign(), fight.encounter(), fight.next(), map("kind", "DODGE"),
					false);
			assertTrue(dice(e).exhausted());
		}
	}

	@Test
	void otherAwardsOnlyDefeatedHostilesAndPersistsReplaysAndRewindsTheAward() throws Exception {
		var file = tempDb("run5-other-xp");
		String campaign, encounter, checkpoint, ending, knockedOut, killed;
		long beforeXp, expectedPool;
		try (Engine e = engine(file)) {
			campaign = ready(e);
			String dagger = dagger(e, campaign);
			knockedOut = bandit(e, campaign, "Knocked-out foe");
			killed = bandit(e, campaign, "Killed foe");
			String untouched = bandit(e, campaign, "Unbeaten foe");
			long each = ((Number) e.rules().require("srd5e:creature/bandit", "CREATURE").payload().get("xp_value"))
					.longValue();
			assertTrue(each > 0);
			expectedPool = each * 2;
			beforeXp = xp(e);
			checkpoint = e.checkpoints().create(op(), campaign, "Before a partial victory").get("checkpoint")
					.toString();
			dice(e).queue(20, 1);
			encounter = e.encounters().start(op(), campaign,
					map("party", List.of("character:1"), "foes", List.of(knockedOut, killed, untouched)), null, null,
					null, null, null, map("placements", List.of(position("character:1", 5, 5),
							position(knockedOut, 10, 5), position(killed, 20, 5), position(untouched, 30, 5))))
					.get("encounter").toString();
			e.runtime().applyRuntimeChange(op(), campaign, knockedOut,
					map("kind", "DAMAGE", "amount", hp(e, knockedOut) - 1, "reason", "A previously resolved wound."));
			dice(e).queue(19, 1);
			var knockout = e.encounters().perform(op(), campaign, encounter, "character:1",
					map("kind", "ATTACK", "target", knockedOut, "weapon", dagger, "nonlethal", true), false);
			assertEquals(true, knockout.get("knocked_out"));
			e.runtime().applyRuntimeChange(op(), campaign, killed, map("kind", "DAMAGE", "amount", hp(e, killed) * 2,
					"reason", "A separately resolved fatal hazard."));
			ending = op();
			var ended = e.encounters().end(ending, campaign, encounter, "OTHER",
					"The remaining foe negotiates a pause.");
			assertEquals(expectedPool, ((Number) ended.get("xp_pool")).longValue());
			assertEquals("DEFEATED_HOSTILES", ended.get("xp_award_basis"));
			assertEquals(2, ((List<?>) ended.get("defeated")).size());
			assertEquals(expectedPool, SceneService.maps(ended.get("xp_awarded")).stream()
					.mapToLong(row -> ((Number) row.get("xp_gained")).longValue()).sum());
			assertEquals(beforeXp + expectedPool, xp(e));
			assertEquals(expectedPool,
					((Number) SceneService.map(SceneService.map(ended.get("record")).get("xp")).get("pool"))
							.longValue());
			assertTrue(dice(e).exhausted());
		}
		try (Engine e = engine(file)) {
			assertEquals(beforeXp + expectedPool, xp(e));
			assertEquals(true,
					e.encounters().end(ending, campaign, encounter, "OTHER", "The remaining foe negotiates a pause.")
							.get("replayed"));
			assertEquals(beforeXp + expectedPool, xp(e));
			e.checkpoints().restore(op(), campaign, checkpoint, "Rewind the partial victory and its XP.");
			assertEquals(beforeXp, xp(e));
			for (String foe : List.of(knockedOut, killed))
				assertEquals("ALIVE",
						e.db().read(tx -> tx.get("character", Ref.id(foe, Ref.CHARACTER)).str("life_state")));
		}
	}

	@Test
	void aNegotiatedEncounterStillAwardsTheSavedFullPoolWithoutDefeatedFoes() throws Exception {
		try (Engine e = engine(tempDb("run5-negotiated-xp"))) {
			String campaign = ready(e), first = bandit(e, campaign, "Negotiating foe"),
					second = bandit(e, campaign, "Listening foe");
			long each = ((Number) e.rules().require("srd5e:creature/bandit", "CREATURE").payload().get("xp_value"))
					.longValue();
			long before = xp(e);
			dice(e).queue(20, 1);
			String encounter = e.encounters().start(op(), campaign,
					map("party", List.of("character:1"), "foes", List.of(first, second)), null, null, null, null, null)
					.get("encounter").toString();
			var ended = e.encounters().end(op(), campaign, encounter, "NEGOTIATED", "Both sides honor the truce.");
			assertTrue(((List<?>) ended.get("defeated")).isEmpty());
			assertEquals(each * 2, ((Number) ended.get("xp_pool")).longValue());
			assertEquals("ENCOUNTER_OVERCOME", ended.get("xp_award_basis"));
			assertEquals(before + each * 2, xp(e));
		}
	}

	private record Fight(String campaign, String encounter, String mover, String next, String dagger) {
	}

	private static Fight reactionFight(Engine e) {
		String campaign = ready(e), dagger = dagger(e, campaign), mover = bandit(e, campaign, "Moving brawler"),
				next = bandit(e, campaign, "Next brawler");
		e.runtime().applyRuntimeChange(op(), campaign, mover, map("kind", "DAMAGE", "amount", hp(e, mover) - 1,
				"reason", "The moving brawler has one Hit Point left."));
		dice(e).queue(1, 20);
		String encounter = e.encounters()
				.start(op(), campaign, map("party", List.of("character:1"), "foes", List.of(mover, next)), null, null,
						null, null, null,
						map("npc_reactions", "ASK", "placements",
								List.of(position("character:1", 5, 5), position(mover, 10, 5), position(next, 30, 15))))
				.get("encounter").toString();
		assertEquals(mover, turnCharacter(e.encounters().encounterState(campaign, encounter, 10, "GM")));
		return new Fight(campaign, encounter, mover, next, dagger);
	}

	private static String ready(Engine e) {
		String campaign = committedCampaign(e);
		e.sessions().bootstrap(op(), campaign, null);
		return campaign;
	}

	private static String bandit(Engine e, String campaign, String name) {
		return e.runtime().materialize(op(), campaign, "Bandit", name, null, null, null, null, false).get("character")
				.toString();
	}

	private static String dagger(Engine e, String campaign) {
		String entry = SceneService.maps(e.inventory()
				.grantLoot(op(), campaign, "character:1", List.of(map("item", "Dagger")), null, "QUEST", null)
				.get("granted")).getFirst().get("entry").toString();
		e.inventory().equip(op(), campaign, "character:1", entry, true);
		return entry;
	}

	private static Map<String, Object> position(String character, int x, int y) {
		return map("character", character, "x_ft", x, "y_ft", y);
	}

	private static String turnCharacter(Map<String, Object> state) {
		return SceneService.map(state.get("turn")).get("character").toString();
	}

	private static void assertCurrentTurn(Engine e, Fight fight, String expected) {
		for (String audience : List.of("GM", "PLAYER"))
			assertEquals(expected,
					turnCharacter(e.encounters().encounterState(fight.campaign(), fight.encounter(), 100, audience)),
					audience);
	}

	private static long hp(Engine e, String character) {
		return e.db().read(tx -> tx.get("character", Ref.id(character, Ref.CHARACTER)).lng("current_hp"));
	}

	private static long xp(Engine e) {
		return e.db().read(tx -> tx.get("character", 1).lng("xp"));
	}

	private static ScriptedRollService dice(Engine e) {
		return (ScriptedRollService) e.roller();
	}
}
