/* Copyright (C) 2026 Marcus Hirt. SPDX-License-Identifier: BSD-3-Clause */
package se.hirt.mcp.rpg;

import org.junit.jupiter.api.Test;
import se.hirt.mcp.rpg.dice.ScriptedRollService;
import se.hirt.mcp.rpg.maps.SceneService;
import se.hirt.mcp.rpg.persistence.Database;
import se.hirt.mcp.rpg.protocol.Json;
import se.hirt.mcp.rpg.protocol.Ref;
import se.hirt.mcp.rpg.session.GameTime;
import se.hirt.mcp.rpg.world.WorldTimeline;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static se.hirt.mcp.rpg.TestCampaigns.*;

class Run12DyingTimeTest {

	@Test
	void exactSixSecondDeadlineSurvivesReloadReplayAndCheckpointWithoutRollingOnRead() throws Exception {
		var file = tempDb("run12-dying-clock-reload");
		String campaign, checkpoint, operation = op();
		long start;
		try (Engine e = engine(file)) {
			campaign = ready(e);
			drop(e, campaign, "character:1");
			start = now(e, campaign);
			checkpoint = e.checkpoints().create(op(), campaign, "Before the first death save").get("checkpoint")
					.toString();
			var before = e.db().snapshot(Ref.id(campaign, Ref.CAMPAIGN));
			e.sessions().party(campaign, "FULL");
			e.db().read(tx -> e.characters().sheet(tx, tx.get("character", 1), "FULL"));
			assertEquals(before, e.db().snapshot(Ref.id(campaign, Ref.CAMPAIGN)));
			assertEquals(start + 6, ((Number) saves(e, "character:1").get("next_save_seconds")).longValue());
			assertTrue(SceneService.maps(waitFor(e, campaign, 5).get("consequences")).isEmpty());
			assertEquals(0, deathRolls(e, campaign));
		}
		try (Engine e = engine(file)) {
			dice(e).queue(11);
			var result = e.sessions().advanceTime(operation, campaign, 600, "Wait while the patient needs aid.");
			assertEquals(1L, result.get("elapsed_seconds"));
			assertEquals(start + 5, SceneService.map(result.get("from")).get("elapsed_seconds"));
			assertEquals(start + 6, SceneService.map(result.get("to")).get("elapsed_seconds"));
			assertEquals(true, result.get("interrupted"));
			var save = save(result);
			assertEquals("SUCCESS", save.get("outcome"));
			assertEquals(11, save.get("natural"));
			assertEquals(start + 6, save.get("at_seconds"));
			assertEquals(1, deathRolls(e, campaign));
			assertEquals(start + 12, ((Number) saves(e, "character:1").get("next_save_seconds")).longValue());
			var snapshot = e.db().snapshot(Ref.id(campaign, Ref.CAMPAIGN));
			assertEquals(true, e.sessions().advanceTime(operation, campaign, 600, "Wait while the patient needs aid.")
					.get("replayed"));
			assertEquals(snapshot, e.db().snapshot(Ref.id(campaign, Ref.CAMPAIGN)));
			e.checkpoints().restore(op(), campaign, checkpoint,
					"Rewind the exact death-save deadline and recorded dice.");
			assertEquals(start, now(e, campaign));
			assertEquals(0, deathRolls(e, campaign));
			dice(e).queue(20);
			assertEquals("REGAIN_1_HP", save(waitFor(e, campaign, 6)).get("outcome"));
			assertEquals(1, character(e, "character:1").intOr("current_hp", 0));
			assertNull(character(e, "character:1").get("death_saves_json"));
		}
	}

	@Test
	void threeSuccessesStartNaturalRecoveryAtTheActualStabilizationInstant() throws Exception {
		try (Engine e = engine(tempDb("run12-clock-stabilization"))) {
			String campaign = ready(e);
			drop(e, campaign, "character:1");
			long start = now(e, campaign);
			dice(e).queue(11, 12, 13, 2);
			for (int i = 1; i <= 3; i++) {
				var tick = waitFor(e, campaign, 10 * 3600);
				assertEquals(6L, tick.get("elapsed_seconds"));
				assertEquals(true, tick.get("interrupted"));
				assertEquals(i == 3 ? "STABLE" : "SUCCESS", save(tick).get("outcome"));
			}
			assertEquals(start + 18, now(e, campaign));
			assertEquals(start + 18 + 7200, ((Number) saves(e, "character:1").get("recovery_due_seconds")).longValue());
			assertFalse(saves(e, "character:1").containsKey("next_save_seconds"));
			assertEquals(0, saves(e, "character:1").get("successes"));
			waitFor(e, campaign, 7199);
			assertEquals(0, character(e, "character:1").intOr("current_hp", 0));
			assertEquals("STABLE_RECOVERY",
					SceneService.maps(waitFor(e, campaign, 1).get("consequences")).getFirst().get("kind"));
			assertEquals(1, character(e, "character:1").intOr("current_hp", 0));
			assertEquals(3, deathRolls(e, campaign));
		}
	}

	@Test
	void naturalOneThenFailureCommitsDeathAndCheckpointContinuationInTheTimeReply() throws Exception {
		try (Engine e = engine(tempDb("run12-clock-death-policy"))) {
			String campaign = ready(e);
			drop(e, campaign, "character:1");
			dice(e).queue(1, 2);
			assertEquals("TWO_FAILURES", save(waitFor(e, campaign, 600)).get("outcome"));
			var death = e.sessions().advanceTime(op(), campaign, 600, "Another six seconds pass.");
			assertEquals(6L, death.get("elapsed_seconds"));
			assertEquals("DEAD", save(death).get("life_state"));
			assertEquals(true, save(death).get("died"));
			assertEquals("PARTY_KNOWN", save(death).get("visibility"));
			assertEquals("CHECKPOINT_DECISION", SceneService.map(death.get("meta")).get("harness_state"));
			assertEquals("DEAD", character(e, "character:1").str("life_state"));
			assertNull(character(e, "character:1").get("death_saves_json"));
			assertEquals("DEAD", e.db().read(tx -> tx
					.queryOne("SELECT state FROM party_membership WHERE character_id=1 ORDER BY id DESC LIMIT 1")
					.orElseThrow().str("state")));
			assertEquals(0L,
					e.db().read(tx -> tx.count("SELECT COUNT(*) FROM active_effect WHERE character_id=1")).longValue());
		}
	}

	@Test
	void ironmanDeathUsesTheExistingSurvivorAndFailurePolicies() throws Exception {
		for (boolean survivor : List.of(false, true))
			try (Engine e = engine(tempDb("run12-ironman-clock-" + survivor))) {
				String campaign = ready(e);
				e.db().mutate(Database.Mutation.of("run12_policy_fixture", 1L, op(), "GM", Map.of()), tx -> {
					tx.update("campaign", 1, map("continuation_policy", "IRONMAN"));
					return Map.of();
				});
				if (survivor) {
					String ally = e.runtime()
							.materialize(op(), campaign, "Guard", "Surviving companion", null, null, null, null, false)
							.get("character").toString();
					e.party().updateMembership(op(), campaign, ally, "JOIN", "An established companion.");
				}
				drop(e, campaign, "character:1");
				dice(e).queue(1, 1);
				waitFor(e, campaign, 6);
				var result = waitFor(e, campaign, 6);
				assertEquals(survivor ? "PLAYER_CHARACTER_TRANSFER" : "CAMPAIGN_FAILED",
						SceneService.map(result.get("meta")).get("harness_state"));
			}
	}

	@Test
	void simultaneousIronmanDeathsEvaluateSurvivorsAfterAllSavesAtThatInstant() throws Exception {
		try (Engine e = engine(tempDb("run12-simultaneous-deaths"))) {
			String campaign = ready(e);
			e.db().mutate(Database.Mutation.of("run12_ironman_fixture", 1L, op(), "GM", Map.of()), tx -> {
				tx.update("campaign", 1, map("continuation_policy", "IRONMAN"));
				return Map.of();
			});
			String ally = e.runtime()
					.materialize(op(), campaign, "Guard", "Dying companion", null, null, null, null, false)
					.get("character").toString();
			e.party().updateMembership(op(), campaign, ally, "JOIN", "An established companion.");
			for (String actor : List.of("character:1", ally)) {
				drop(e, campaign, actor);
				e.runtime().applyRuntimeChange(op(), campaign, actor,
						map("kind", "DAMAGE", "amount", 1, "reason", "The wounds worsen."));
			}
			dice(e).queue(1, 1);
			var result = waitFor(e, campaign, 3600);
			assertEquals(6L, result.get("elapsed_seconds"));
			assertEquals("CAMPAIGN_FAILED", SceneService.map(result.get("meta")).get("harness_state"));
			assertEquals("DEAD", character(e, ally).str("life_state"));
			assertEquals("DEAD", character(e, "character:1").str("life_state"));
		}
	}

	@Test
	void unseenFormerCompanionResolvesGloballyWithoutPublishingItsIdentity() throws Exception {
		try (Engine e = engine(tempDb("run12-private-clock-death"))) {
			String campaign = ready(e);
			String npc = e.runtime()
					.materialize(op(), campaign, "Guard", "Unseen patient", null, null, null, null, false)
					.get("character").toString();
			e.party().updateMembership(op(), campaign, npc, "JOIN", "Briefly joins.");
			drop(e, campaign, npc);
			e.party().updateMembership(op(), campaign, npc, "LEAVE", "Remains behind outside the active region.");
			dice(e).queue(2, 3, 4);
			var elapsed = waitFor(e, campaign, 3600);
			assertEquals(3600L, elapsed.get("elapsed_seconds"));
			assertEquals(false, elapsed.get("interrupted"));
			for (var event : SceneService.maps(elapsed.get("consequences")))
				assertEquals("GM_ONLY", event.get("visibility"));
			assertEquals("DEAD", character(e, npc).str("life_state"));
			assertEquals(3L, e.db().read(tx -> tx.count(
					"SELECT COUNT(*) FROM event WHERE campaign_id=1 AND type='DEATH_SAVE' AND visibility='GM_ONLY'"))
					.longValue());
			assertEquals(0L, e.db().read(tx -> tx.count(
					"SELECT COUNT(*) FROM event WHERE campaign_id=1 AND type='CHARACTER_DIED' AND visibility='PARTY_KNOWN'"))
					.longValue());
		}
	}

	@Test
	void legacyUnstableRowsInitializeOnlyAtExplicitTimeMutationAndHealingCancelsTheDeadline() throws Exception {
		try (Engine e = engine(tempDb("run12-legacy-clock-save"))) {
			String campaign = ready(e);
			drop(e, campaign, "character:1");
			e.db().mutate(Database.Mutation.of("run12_legacy_fixture", 1L, op(), "GM", Map.of()), tx -> {
				var saves = tx.get("character", 1).map("death_saves_json");
				saves.remove("next_save_seconds");
				tx.update("character", 1, map("death_saves_json", Json.write(saves)));
				return Map.of();
			});
			var before = e.db().snapshot(1);
			e.sessions().party(campaign, "FULL");
			assertEquals(before, e.db().snapshot(1));
			waitFor(e, campaign, 5);
			assertEquals(0, deathRolls(e, campaign));
			e.runtime().applyRuntimeChange(op(), campaign, "character:1",
					map("kind", "HEAL", "amount", 1, "reason", "Healing before the next death save."));
			waitFor(e, campaign, 3600);
			assertEquals(0, deathRolls(e, campaign));
			assertEquals("ALIVE", character(e, "character:1").str("life_state"));
		}
	}

	@Test
	void combatOwnsItsSavesUntilTheEndedEncounterRebasesTheExplorationDeadline() throws Exception {
		try (Engine e = engine(tempDb("run12-combat-clock-ownership"))) {
			String campaign = ready(e);
			String foe = e.runtime().materialize(op(), campaign, "Guard", "Watchman", null, null, null, null, false)
					.get("character").toString();
			dice(e).queue(20, 1);
			String encounter = e.encounters().start(op(), campaign,
					map("party", List.of("character:1"), "foes", List.of(foe)), null, null, null, null, null)
					.get("encounter").toString();
			drop(e, campaign, "character:1");
			e.encounters().end(op(), campaign, encounter, "OTHER", "The watchman leaves the unconscious patient.");
			assertEquals(0, deathRolls(e, campaign),
					"Charging the last combat round must not add an exploration save.");
			long ended = now(e, campaign);
			assertEquals(ended + 6, ((Number) saves(e, "character:1").get("next_save_seconds")).longValue());
			waitFor(e, campaign, 5);
			dice(e).queue(20);
			waitFor(e, campaign, 1);
			assertEquals(1, deathRolls(e, campaign));
			assertEquals("ALIVE", character(e, "character:1").str("life_state"));
		}
	}

	private static String ready(Engine e) {
		String campaign = committedCampaign(e);
		e.sessions().bootstrap(op(), campaign, null);
		return campaign;
	}

	private static void drop(Engine e, String campaign, String character) {
		e.runtime().applyRuntimeChange(op(), campaign, character, map("kind", "DAMAGE", "amount",
				character(e, character).intOr("current_hp", 0), "reason", "Knocked unconscious."));
	}

	private static se.hirt.mcp.rpg.persistence.Row character(Engine e, String reference) {
		return e.db().read(tx -> tx.get("character", Ref.id(reference, Ref.CHARACTER)));
	}

	private static Map<String, Object> saves(Engine e, String character) {
		return character(e, character).map("death_saves_json");
	}

	private static ScriptedRollService dice(Engine e) {
		return (ScriptedRollService) e.roller();
	}

	private static long now(Engine e, String campaign) {
		return e.db().read(tx -> GameTime.currentSeconds(tx, Ref.id(campaign, Ref.CAMPAIGN)));
	}

	private static int deathRolls(Engine e, String campaign) {
		return e.db()
				.read(tx -> (int) tx.count(
						"SELECT COUNT(*) FROM roll WHERE campaign_id=? AND purpose LIKE 'death saving throw %'",
						Ref.id(campaign, Ref.CAMPAIGN)));
	}

	private static Map<String, Object> waitFor(Engine e, String campaign, long seconds) {
		return new WorldTimeline(e.db()).perform(op(), campaign, "WAIT", seconds, op(), Map.of());
	}

	private static Map<String, Object> save(Map<String, Object> result) {
		return SceneService.maps(result.get("consequences")).stream()
				.filter(event -> "DEATH_SAVE".equals(event.get("kind"))).findFirst().orElseThrow();
	}
}
