/* Copyright (C) 2026 Marcus Hirt. SPDX-License-Identifier: BSD-3-Clause */
package se.hirt.mcp.rpg;

import org.junit.jupiter.api.Test;
import se.hirt.mcp.rpg.dice.ScriptedRollService;
import se.hirt.mcp.rpg.maps.SceneService;
import se.hirt.mcp.rpg.persistence.Database;
import se.hirt.mcp.rpg.protocol.Json;
import se.hirt.mcp.rpg.protocol.Ref;
import se.hirt.mcp.rpg.protocol.RpgException;
import se.hirt.mcp.rpg.session.GameTime;
import se.hirt.mcp.rpg.world.WorldTimeline;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static se.hirt.mcp.rpg.TestCampaigns.*;

class Run12RestTest {

	@Test
	void interruptedRestRetainsItsInitialEligibleRosterAcrossNaturalTwentyReloadReplayAndRewind() throws Exception {
		var file = tempDb("run12-rest-initial-roster");
		String campaign, guard, action, checkpoint;
		long start;
		try (Engine e = engine(file)) {
			campaign = ready(e);
			guard = guard(e, campaign);
			markers(e, campaign, List.of("character:1", guard));
			start = seconds(e, campaign);
			drop(e, campaign);
			dice(e).queue(20);
			var paused = e.rest().rest(op(), campaign, "SHORT", null, null);
			action = paused.get("action").toString();
			assertEquals(false, paused.get("completed"));
			assertEquals(true, paused.get("interrupted"));
			assertEquals(6L, paused.get("elapsed_seconds"));
			assertEquals(1L, hp(e, "character:1"));
			assertEquals(0L, marker(e, "character:1"));
			assertEquals(0L, marker(e, guard));
			var roster = e.db().read(tx -> SceneService.map(
					tx.get("action_progress", Ref.id(action, "action")).map("parameters_json").get("rest_roster")));
			assertEquals(List.of(guard), roster.get("eligible"));
			assertEquals("character:1", SceneService.maps(roster.get("skipped")).getFirst().get("character"));
			checkpoint = e.checkpoints()
					.create(op(), campaign, "After natural recovery interrupted another member's rest")
					.get("checkpoint").toString();
		}
		try (Engine e = engine(file)) {
			String operation = op();
			var completed = e.rest().rest(operation, campaign, "SHORT", null, null);
			assertEquals(action, completed.get("action"));
			assertEquals(true, completed.get("completed"));
			assertEquals(3594L, completed.get("elapsed_seconds"));
			assertEquals(start + 3600, seconds(e, campaign));
			assertEquals(1L, marker(e, guard));
			assertEquals(0L, marker(e, "character:1"), "A skipped actor cannot inherit the rest's remaining time.");
			assertEquals(1L, hp(e, "character:1"));
			assertTrue(person(completed, "character:1").containsKey("skipped"));
			var beforeReplay = e.db().snapshot(Ref.id(campaign, Ref.CAMPAIGN));
			assertEquals(true, e.rest().rest(operation, campaign, "SHORT", null, null).get("replayed"));
			assertEquals(beforeReplay, e.db().snapshot(Ref.id(campaign, Ref.CAMPAIGN)));
			e.checkpoints().restore(op(), campaign, checkpoint,
					"Restore original rest eligibility and partial progress");
			e.sessions().bootstrap(op(), campaign, null);
			var restored = e.rest().rest(op(), campaign, "SHORT", null, null);
			assertEquals(action, restored.get("action"));
			assertEquals(3594L, restored.get("elapsed_seconds"));
			assertEquals(0L, marker(e, "character:1"));
			assertEquals(1L, marker(e, guard));
		}
	}

	@Test
	void legacyPausedRestCancelsUnverifiedCreditAndRequiresAnExplicitFreshFullRest() throws Exception {
		try (Engine e = engine(tempDb("run12-legacy-rest-roster"))) {
			String campaign = ready(e);
			markers(e, campaign, List.of("character:1"));
			new WorldTimeline(e.db()).schedule(op(), campaign, map("after_seconds", 180, "kind", "A_BELL"));
			var partial = e.rest().rest(op(), campaign, "SHORT", null, null);
			String action = partial.get("action").toString();
			e.db().mutate(Database.Mutation.of("run12_pre_roster_rest_fixture", Ref.id(campaign, Ref.CAMPAIGN), op(),
					"GM", Map.of()), tx -> {
						var row = tx.get("action_progress", Ref.id(action, "action"));
						var parameters = row.map("parameters_json");
						parameters.remove("rest_roster");
						tx.update("action_progress", row.id(), map("parameters_json", Json.write(parameters)));
						return Map.of("ok", true);
					});
			long now = seconds(e, campaign), rolls = rollCount(e);
			String operation = op();
			var refused = e.rest().rest(operation, campaign, "SHORT", null, null);
			assertEquals(true, refused.get("restart_required"));
			assertEquals(false, refused.get("completed"));
			assertEquals(0L, refused.get("elapsed_seconds"));
			assertEquals(action, refused.get("cancelled_action"));
			assertEquals("CANCELLED",
					e.db().read(tx -> tx.get("action_progress", Ref.id(action, "action")).str("status")));
			assertEquals(180L,
					e.db().read(tx -> tx.get("action_progress", Ref.id(action, "action")).lng("completed_seconds"))
							.longValue());
			assertEquals(now, seconds(e, campaign));
			assertEquals(rolls, rollCount(e));
			assertEquals(0L, marker(e, "character:1"));
			assertEquals(true, e.rest().rest(operation, campaign, "SHORT", null, null).get("replayed"));
			assertEquals(now, seconds(e, campaign), "Retrying the cancellation cannot silently start a new rest.");
			var fresh = e.rest().rest(op(), campaign, "SHORT", null, null);
			assertEquals(true, fresh.get("completed"));
			assertEquals(3600L, fresh.get("elapsed_seconds"));
			assertNotEquals(action, fresh.get("action"));
			assertEquals(1L, marker(e, "character:1"));
		}
	}

	@Test
	void anUnstablePartyCannotUseAnIneligibleRestToSilentlyPassTime() throws Exception {
		try (Engine e = engine(tempDb("run12-unstable-rest-refusal"))) {
			String campaign = ready(e);
			drop(e, campaign);
			long now = seconds(e, campaign), rolls = rollCount(e);
			var refused = e.rest().rest(op(), campaign, "LONG", null, null);
			assertEquals(false, refused.get("completed"));
			assertEquals(0L, refused.get("elapsed_seconds"));
			assertTrue(refused.get("note").toString().contains("Dying and unstable"));
			assertTrue(refused.get("note").toString().contains("risks death"));
			assertEquals(now, seconds(e, campaign));
			assertEquals(rolls, rollCount(e));
			assertEquals(0L, e.db().read(tx -> tx.count("SELECT COUNT(*) FROM action_progress")).longValue());
			var named = assertThrows(RpgException.class,
					() -> e.rest().rest(op(), campaign, "SHORT", null, List.of("character:1")));
			assertTrue(named.getMessage().contains("Death Saving Throws"));
			assertEquals(now, seconds(e, campaign));
			assertEquals(rolls, rollCount(e));
		}
	}

	@Test
	void aDeathDuringACompanionsRestCommitsTheSaveAndReturnsFreshContinuationMetadata() throws Exception {
		try (Engine e = engine(tempDb("run12-rest-death-meta"))) {
			String campaign = ready(e), guard = guard(e, campaign);
			markers(e, campaign, List.of("character:1", guard));
			drop(e, campaign);
			for (int i = 0; i < 2; i++)
				e.runtime().applyRuntimeChange(op(), campaign, "character:1",
						map("kind", "DAMAGE", "amount", 1, "reason", "A wound worsens before the rest begins."));
			dice(e).queue(5);
			long rolls = rollCount(e);
			var stopped = e.rest().rest(op(), campaign, "SHORT", null, null);
			assertEquals(false, stopped.get("completed"));
			assertEquals(true, stopped.get("interrupted"));
			assertEquals(6L, stopped.get("elapsed_seconds"));
			assertEquals(rolls + 1, rollCount(e));
			assertEquals("DEAD", e.db().read(tx -> tx.get("character", 1).str("life_state")));
			assertEquals("CHECKPOINT_DECISION", SceneService.map(stopped.get("meta")).get("harness_state"));
			assertEquals("CHECKPOINT_DECISION",
					e.db().read(tx -> tx.get("campaign", Ref.id(campaign, Ref.CAMPAIGN)).str("harness_state")));
			assertEquals(0L, marker(e, guard), "An interrupted rest cannot recharge resources.");
			assertEquals("DEAD", SceneService.maps(stopped.get("interruptions")).getFirst().get("outcome"));
			assertEquals("PARTY_KNOWN", SceneService.maps(stopped.get("interruptions")).getFirst().get("visibility"));
		}
	}

	private static void markers(Engine e, String campaign, List<String> people) {
		e.db().mutate(Database.Mutation.of("run12_short_rest_resource_fixture", Ref.id(campaign, Ref.CAMPAIGN), op(),
				"GM", Map.of()), tx -> {
					for (String ref : people)
						tx.insert("resource_state", map("character_id", Ref.id(ref, Ref.CHARACTER), "resource_ref",
								"rest_marker", "current", 0, "max", 1, "recharge", "SHORT_REST"));
					return Map.of("ok", true);
				});
	}

	private static String guard(Engine e, String campaign) {
		String guard = e.runtime()
				.materialize(op(), campaign, "Guard", "Resting companion", null, null, null, null, false)
				.get("character").toString();
		e.party().updateMembership(op(), campaign, guard, "JOIN", "Shares a safe camp.");
		return guard;
	}

	private static void drop(Engine e, String campaign) {
		e.runtime().applyRuntimeChange(op(), campaign, "character:1",
				map("kind", "DAMAGE", "amount", hp(e, "character:1"), "reason", "A fall leaves the character dying."));
	}

	private static long marker(Engine e, String character) {
		return e.db()
				.read(tx -> tx.queryOne(
						"SELECT current FROM resource_state WHERE character_id=? AND resource_ref='rest_marker'",
						Ref.id(character, Ref.CHARACTER)).orElseThrow().lng("current"));
	}

	private static long hp(Engine e, String character) {
		return e.db().read(tx -> tx.get("character", Ref.id(character, Ref.CHARACTER)).lng("current_hp"));
	}

	private static long seconds(Engine e, String campaign) {
		return e.db().read(tx -> GameTime.currentSeconds(tx, Ref.id(campaign, Ref.CAMPAIGN)));
	}

	private static long rollCount(Engine e) {
		return e.db().read(tx -> tx.count("SELECT COUNT(*) FROM roll"));
	}

	private static Map<String, Object> person(Map<String, Object> result, String ref) {
		return SceneService.maps(result.get("characters")).stream().filter(c -> ref.equals(c.get("character")))
				.findFirst().orElseThrow();
	}

	private static ScriptedRollService dice(Engine e) {
		return (ScriptedRollService) e.roller();
	}

	private static String ready(Engine e) {
		String campaign = committedCampaign(e);
		e.sessions().bootstrap(op(), campaign, null);
		return campaign;
	}
}
