/* Copyright (C) 2026 Marcus Hirt. SPDX-License-Identifier: BSD-3-Clause */
package se.hirt.mcp.rpg;

import org.junit.jupiter.api.Test;
import se.hirt.mcp.rpg.dice.ScriptedRollService;
import se.hirt.mcp.rpg.maps.SceneService;
import se.hirt.mcp.rpg.protocol.Json;
import se.hirt.mcp.rpg.protocol.Ref;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static se.hirt.mcp.rpg.TestCampaigns.*;

class Run12DeathReportingTest {

	@Test
	void passingTurnsReportsTheActualRollAndPlayerDeathWithoutEndingOrDuplicatingTheEncounter() throws Exception {
		var file = tempDb("run12-death-reporting");
		String campaign, encounter, guard, checkpoint;
		Map<String, Object> savedRecord;
		try (Engine e = engine(file)) {
			campaign = ready(e);
			guard = guard(e, campaign, "Watching guard");
			drop(e, campaign, "character:1");
			dice(e).queue(1, 1, 20); // Unconscious Richard rolls Initiative with Disadvantage.
			var started = e.encounters().start(op(), campaign,
					map("party", List.of("character:1"), "guards", List.of(guard)), null, null, null, null, null);
			encounter = started.get("encounter").toString();
			checkpoint = started.get("retry_checkpoint").toString();
			dice(e).queue(5);
			var first = e.encounters().perform(op(), campaign, encounter, guard, map("kind", "END_TURN"));
			var failure = SceneService.maps(first.get("death_saves")).getFirst();
			assertEquals("DEATH_SAVE", failure.get("kind"));
			assertEquals("character:1", failure.get("character"));
			assertEquals(5, ((Number) failure.get("death_save")).intValue());
			assertEquals(5, ((Number) failure.get("natural")).intValue());
			assertEquals(1L, ((Number) failure.get("round")).longValue());
			assertEquals("FAILURE", failure.get("outcome"));
			assertEquals("DYING", failure.get("life_state"));
			assertEquals(false, failure.get("died"));
			assertTrue(failure.get("summary").toString().contains("round 1: rolled 5"));
			assertFalse(failure.containsKey("position_ft"));
			assertFalse(failure.containsKey("roll"), "The compact report does not duplicate the full GM roll record.");
			String operation = op();
			dice(e).queue(1);
			var died = e.encounters().perform(operation, campaign, encounter, guard, map("kind", "END_TURN"));
			var terminal = SceneService.maps(died.get("death_saves")).getFirst();
			assertEquals("DEAD", terminal.get("outcome"));
			assertEquals(true, terminal.get("died"));
			assertEquals(2L, ((Number) terminal.get("round")).longValue());
			assertEquals(3, ((Number) terminal.get("failures")).intValue(), "Natural 1 contributes two failures.");
			assertEquals("character:1", SceneService.maps(died.get("deaths")).getFirst().get("character"));
			assertEquals("DEAD", died.get("player_character_status"));
			assertEquals(true, died.get("encounter_end_required"));
			assertTrue(died.get("next_step").toString().contains("has died"));
			assertTrue(died.get("next_step").toString().contains("end_encounter"));
			assertTrue(died.get("next_step").toString().contains("get_continuation_options"));
			assertEquals("ENCOUNTER", SceneService.map(died.get("meta")).get("harness_state"));
			assertNull(died.get("next_turn"), "An unconscious party never observed the guard's identity or turn.");
			assertEquals("get_encounter_state", SceneService.map(died.get("gm_handoff")).get("tool"));
			assertEquals(guard, SceneService
					.map(e.encounters().encounterState(campaign, encounter, 10, "GM").get("turn")).get("character"),
					"Only the explicit GM read supplies the unobserved next actor.");
			var saved = e.db().snapshot(Ref.id(campaign, Ref.CAMPAIGN));
			assertEquals(true, e.encounters().perform(operation, campaign, encounter, guard, map("kind", "END_TURN"))
					.get("replayed"));
			assertEquals(saved, e.db().snapshot(Ref.id(campaign, Ref.CAMPAIGN)));
			assertTrue(dice(e).exhausted());
			assertEquals(2L,
					e.db().read(tx -> tx.count(
							"SELECT COUNT(*) FROM encounter_log WHERE encounter_id=? AND kind='DEATH_SAVE'",
							Ref.id(encounter, Ref.ENCOUNTER))).longValue());
			var ended = e.encounters().end(op(), campaign, encounter, "PARTY_DEFEAT", "Richard dies of his wounds.");
			assertEquals("CHECKPOINT_DECISION", SceneService.map(ended.get("meta")).get("harness_state"));
			assertEquals("DEAD", SceneService.map(ended.get("continuation")).get("player_character_status"));
			savedRecord = SceneService.map(e.encounters().encounterState(campaign, encounter, 100, "GM").get("record"));
			assertTrue(Json.write(savedRecord).contains("FAILED_DEATH_SAVES"));
		}
		try (Engine e = engine(file)) {
			assertEquals(savedRecord,
					SceneService.map(e.encounters().encounterState(campaign, encounter, 100, "GM").get("record")));
			e.checkpoints().restore(op(), campaign, checkpoint,
					"Retry the saved dying state rather than revive by fiat.");
			dice(e).queue(1, 1, 20);
			String retry = e.encounters().start(op(), campaign,
					map("party", List.of("character:1"), "guards", List.of(guard)), null, null, null, null, null)
					.get("encounter").toString();
			dice(e).queue(20);
			var recovered = e.encounters().perform(op(), campaign, retry, guard, map("kind", "END_TURN"));
			var save = SceneService.maps(recovered.get("death_saves")).getFirst();
			assertEquals("REGAIN_1_HP", save.get("outcome"));
			assertEquals("ALIVE", save.get("life_state"));
			assertEquals(false, save.get("died"));
			assertFalse(recovered.containsKey("player_character_status"));
			assertEquals("character:1", SceneService.map(recovered.get("next_turn")).get("character"));
		}
	}

	@Test
	void savedUnavailableTurnAcknowledgementIncludesEveryAutomaticSaveWithoutSpendingTheNextActorsTurn()
			throws Exception {
		try (Engine e = engine(tempDb("run12-reconciled-death-report"))) {
			String campaign = ready(e), ally = guard(e, campaign, "Next living ally"),
					fallen = guard(e, campaign, "Out of turn casualty");
			e.party().updateMembership(op(), campaign, ally, "JOIN", "The next actor is a real companion.");
			dice(e).queue(10, 1, 20);
			String encounter = e.encounters().start(op(), campaign,
					map("party", List.of("character:1", ally), "guards", List.of(fallen)), null, null, null, null, null,
					map("placements",
							List.of(position("character:1", 10, 5), position(ally, 15, 5), position(fallen, 5, 5))))
					.get("encounter").toString();
			drop(e, campaign, "character:1");
			drop(e, campaign, fallen);
			dice(e).queue(5);
			String operation = op();
			var acknowledged = e.encounters().perform(operation, campaign, encounter, fallen, map("kind", "END_TURN"));
			assertEquals(true, acknowledged.get("turn_advanced"));
			assertEquals(ally, SceneService.map(acknowledged.get("next_turn")).get("character"));
			assertEquals(1, SceneService.maps(acknowledged.get("death_saves")).size());
			assertEquals("FAILURE", SceneService.maps(acknowledged.get("death_saves")).getFirst().get("outcome"));
			var next = SceneService.maps(SceneService.map(acknowledged.get("state")).get("participants")).stream()
					.filter(p -> ally.equals(p.get("character"))).findFirst().orElseThrow();
			assertEquals(true, next.get("action_available"));
			var saved = e.db().snapshot(Ref.id(campaign, Ref.CAMPAIGN));
			assertEquals(true, e.encounters().perform(operation, campaign, encounter, fallen, map("kind", "END_TURN"))
					.get("replayed"));
			assertEquals(saved, e.db().snapshot(Ref.id(campaign, Ref.CAMPAIGN)));
			assertTrue(dice(e).exhausted(), "Saved-turn reconciliation rolls the dying participant only once.");
		}
	}

	@Test
	void resolvingAPendingOpportunityChoiceReportsTheSaveRolledByItsDeferredTurnAdvance() throws Exception {
		try (Engine e = engine(tempDb("run12-reaction-death-report"))) {
			String campaign = ready(e), ally = guard(e, campaign, "Wounded companion"),
					mover = guard(e, campaign, "Retreating guard");
			e.party().updateMembership(op(), campaign, ally, "JOIN", "The wounded companion remains a party member.");
			dice(e).queue(1, 10, 20);
			String encounter = e.encounters().start(op(), campaign,
					map("party", List.of("character:1", ally), "guards", List.of(mover)), null, null, null, null, null,
					map("placements",
							List.of(position("character:1", 5, 5), position(ally, 30, 15), position(mover, 10, 5))))
					.get("encounter").toString();
			drop(e, campaign, ally);
			var paused = e.encounters().perform(op(), campaign, encounter, mover,
					map("kind", "MOVE", "path_ft", List.of(List.of(10, 10), List.of(10, 15))), true);
			assertEquals(false, paused.get("turn_advanced"));
			assertFalse(paused.containsKey("death_saves"));
			String transaction = SceneService.maps(paused.get("pending_choices")).getFirst().get("transaction")
					.toString();
			dice(e).queue(5);
			String operation = op();
			var resumed = e.encounters().resolveChoice(operation, campaign, transaction, map("option", "DECLINE"));
			assertEquals(true, resumed.get("turn_advanced"));
			assertEquals("character:1", SceneService.map(resumed.get("next_turn")).get("character"));
			var save = SceneService.maps(resumed.get("death_saves")).getFirst();
			assertEquals(ally, save.get("character"));
			assertEquals("FAILURE", save.get("outcome"));
			assertEquals("DYING", save.get("life_state"));
			assertTrue(save.get("summary").toString().contains("rolled 5"));
			var saved = e.db().snapshot(Ref.id(campaign, Ref.CAMPAIGN));
			assertEquals(true, e.encounters().resolveChoice(operation, campaign, transaction, map("option", "DECLINE"))
					.get("replayed"));
			assertEquals(saved, e.db().snapshot(Ref.id(campaign, Ref.CAMPAIGN)));
			assertTrue(dice(e).exhausted());
		}
	}

	@Test
	void hiddenFormerCompanionsAutomaticDeathRemainsPrivateInRepliesLedgerAndFrozenPlayerHistory() throws Exception {
		var file = tempDb("run12-private-death-save");
		String campaign, encounter, hidden;
		Map<String, Object> playerHistory, gmRecord;
		try (Engine e = engine(file)) {
			campaign = ready(e);
			hidden = guard(e, campaign, "PRIVATE_WOUNDED_WATCHER");
			String visible = guard(e, campaign, "Visible guard");
			e.party().updateMembership(op(), campaign, hidden, "GUEST_ADD", "An earlier temporary companion.");
			drop(e, campaign, hidden);
			e.party().updateMembership(op(), campaign, hidden, "GUEST_REMOVE",
					"The temporary membership has already ended.");
			dice(e).queue(20, 1, 1, 1); // The wounded hidden guard rolls Initiative with Disadvantage.
			encounter = e.encounters()
					.start(op(), campaign, map("party", List.of("character:1"), "guards", List.of(hidden, visible)),
							null, null, null, null, null,
							map("scene", map("name", "Divided infirmary", "illumination", "BRIGHT", "bounds_ft",
									List.of(0, 0, 40, 20), "rooms",
									List.of(map("key", "west", "rect_ft", List.of(0, 0, 20, 20)),
											map("key", "east", "rect_ft", List.of(20, 0, 20, 20)))),
									"placements", List.of(position("character:1", 5, 5), position(hidden, 30, 5),
											position(visible, 10, 5))))
					.get("encounter").toString();
			for (int natural : List.of(5, 5, 1)) {
				dice(e).queue(natural);
				var passed = e.encounters().perform(op(), campaign, encounter, "character:1", map("kind", "END_TURN"));
				assertPrivate(passed, hidden);
				assertFalse(passed.containsKey("death_saves"));
				assertFalse(passed.containsKey("deaths"));
				assertEquals("get_encounter_state", SceneService.map(passed.get("gm_handoff")).get("tool"));
				if (natural != 1)
					e.encounters().perform(op(), campaign, encounter, visible, map("kind", "END_TURN"));
			}
			assertEquals("DEAD",
					e.db().read(tx -> tx.get("character", Ref.id(hidden, Ref.CHARACTER)).str("life_state")));
			var death = e.db().read(tx -> tx.queryOne(
					"SELECT * FROM event WHERE campaign_id=? AND type='CHARACTER_DIED' ORDER BY id DESC LIMIT 1",
					Ref.id(campaign, Ref.CAMPAIGN)).orElseThrow());
			assertEquals("GM_ONLY", death.str("visibility"));
			var gm = e.encounters().encounterState(campaign, encounter, 100, "GM");
			assertEquals(3L, SceneService.maps(gm.get("recent_log")).stream()
					.filter(row -> "DEATH_SAVE".equals(row.get("kind"))).count());
			assertTrue(Json.write(gm).contains("PRIVATE_WOUNDED_WATCHER"));
			e.encounters().end(op(), campaign, encounter, "OTHER", "The visible guard stands down.");
			playerHistory = withoutMeta(e.encounters().encounterState(campaign, encounter, 100, "PLAYER"));
			assertPrivate(playerHistory, hidden);
			gmRecord = SceneService.map(e.encounters().encounterState(campaign, encounter, 100, "GM").get("record"));
			assertTrue(Json.write(gmRecord).contains("PRIVATE_WOUNDED_WATCHER"));
		}
		try (Engine e = engine(file)) {
			assertEquals(playerHistory, withoutMeta(e.encounters().encounterState(campaign, encounter, 100, "PLAYER")));
			assertEquals(gmRecord,
					SceneService.map(e.encounters().encounterState(campaign, encounter, 100, "GM").get("record")));
		}
	}

	private static void assertPrivate(Map<String, Object> response, String hidden) {
		String json = Json.write(response);
		assertFalse(json.contains("PRIVATE_WOUNDED_WATCHER"), json);
		assertFalse(json.contains('"' + hidden + '"'), json);
	}

	private static Map<String, Object> withoutMeta(Map<String, Object> response) {
		var copy = new java.util.LinkedHashMap<>(response);
		copy.remove("meta");
		return copy;
	}

	private static Map<String, Object> position(String character, int x, int y) {
		return map("character", character, "x_ft", x, "y_ft", y);
	}

	private static ScriptedRollService dice(Engine e) {
		return (ScriptedRollService) e.roller();
	}

	private static String ready(Engine e) {
		String campaign = committedCampaign(e);
		e.sessions().bootstrap(op(), campaign, null);
		return campaign;
	}

	private static String guard(Engine e, String campaign, String name) {
		return e.runtime().materialize(op(), campaign, "Guard", name, null, null, null, null, false).get("character")
				.toString();
	}

	private static void drop(Engine e, String campaign, String character) {
		int hp = e.db().read(tx -> tx.get("character", Ref.id(character, Ref.CHARACTER)).integer("current_hp"));
		e.runtime().applyRuntimeChange(op(), campaign, character,
				map("kind", "DAMAGE", "amount", hp, "reason", "A previously adjudicated wound."));
	}
}
