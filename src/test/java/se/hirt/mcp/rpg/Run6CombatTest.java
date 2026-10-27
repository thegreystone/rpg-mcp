/* Copyright (C) 2026 Marcus Hirt. SPDX-License-Identifier: BSD-3-Clause */
package se.hirt.mcp.rpg;

import org.junit.jupiter.api.Test;
import se.hirt.mcp.rpg.dice.ScriptedRollService;
import se.hirt.mcp.rpg.maps.SceneService;
import se.hirt.mcp.rpg.persistence.Database;
import se.hirt.mcp.rpg.protocol.Json;
import se.hirt.mcp.rpg.protocol.Ref;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static se.hirt.mcp.rpg.TestCampaigns.*;

class Run6CombatTest {

	@Test
	void automaticOpportunityKnockdownAdvancesInsideTheMoveReplyExactlyOnce() throws Exception {
		for (Boolean endTurn : Arrays.asList(null, false, true)) {
			var file = tempDb("run6-auto-oa-" + endTurn);
			String campaign, encounter, companion;
			try (Engine e = engine(file)) {
				campaign = ready(e);
				companion = bandit(e, campaign, "Oddny the next actor");
				String reactor = bandit(e, campaign, "Rask the reactor");
				e.party().updateMembership(op(), campaign, companion, "JOIN", "An established companion.");
				long cid = Ref.id(campaign, Ref.CAMPAIGN);
				long hp = e.db().read(tx -> tx.get("character", 1).lng("current_hp"));
				e.runtime().applyRuntimeChange(op(), campaign, "character:1",
						map("kind", "DAMAGE", "amount", hp - 1, "reason", "A previously resolved wound."));
				dice(e).queue(20, 10, 1);
				encounter = e.encounters()
						.start(op(), campaign,
								map("party", List.of("character:1", companion), "foes", List.of(reactor)), null, null,
								null, null, null,
								map("npc_reactions", "AUTO", "placements", List.of(position("character:1", 5, 5),
										position(companion, 30, 15), position(reactor, 10, 5))))
						.get("encounter").toString();
				dice(e).queue(20, 1, 1); // A critical scimitar hit: enough to drop, without massive damage.
				String operation = op();
				Map<String, Object> action = map("kind", "MOVE", "path_ft", List.of(List.of(5, 10), List.of(5, 15)));
				var moved = e.encounters().perform(operation, campaign, encounter, "character:1", action, endTurn);
				assertEquals(true, moved.get("turn_advanced"));
				assertEquals("ACTOR_CAN_NO_LONGER_ACT", moved.get("turn_end_reason"));
				assertEquals(companion, SceneService.map(moved.get("next_turn")).get("character"));
				assertEquals(companion, turn(SceneService.map(moved.get("state"))));
				assertEquals(1L, ((Number) moved.get("round")).longValue());
				assertEquals(false, moved.get("completed"));
				assertEquals(List.of(5.0, 10.0), moved.get("position_ft"));
				assertEquals(5.0, ((Number) moved.get("movement_cost_ft")).doubleValue());
				var reaction = SceneService.maps(moved.get("opportunity_attacks")).getFirst();
				assertEquals(true, reaction.get("critical"));
				assertEquals("DYING", reaction.get("life_state"));
				assertEquals(0L, e.db().read(tx -> tx.get("character", 1).lng("current_hp")).longValue());
				assertEquals(0,
						((Number) e.db().read(tx -> tx.get("character", 1).map("death_saves_json").get("failures")))
								.intValue());
				assertCurrentTurn(e, campaign, encounter, companion);
				var after = e.db().snapshot(cid);
				assertEquals(true, e.encounters()
						.perform(operation, campaign, encounter, "character:1", action, endTurn).get("replayed"));
				assertEquals(after, e.db().snapshot(cid));
				assertTrue(dice(e).exhausted(), "Ending a turn after falling must not roll a premature death save.");
			}
			try (Engine e = engine(file)) {
				assertCurrentTurn(e, campaign, encounter, companion);
				var acted = e.encounters().perform(op(), campaign, encounter, companion, map("kind", "DODGE"), false);
				assertEquals(false, acted.get("turn_advanced"));
				assertEquals(companion, turn(SceneService.map(acted.get("state"))));
			}
		}
	}

	@Test
	void endedRecordsRemainReadableOnAnotherBoardAndFreezePlayerKnowledgeAndCharacterState() throws Exception {
		var file = tempDb("run6-historical-encounter");
		String campaign, encounter, hidden;
		Map<String, Object> savedRecord, savedPlayerState;
		long endedHp;
		try (Engine e = engine(file)) {
			campaign = ready(e);
			String visible = bandit(e, campaign, "Known tavern guard");
			hidden = bandit(e, campaign, "NEVER_SEEN_DURING_OLD_FIGHT");
			dice(e).queue(20, 1);
			encounter = e.encounters().start(op(), campaign,
					map("party", List.of("character:1"), "foes", List.of(visible, hidden)), null, null, null, null,
					null,
					map("scene",
							map("name", "Old divided taproom", "bounds_ft", List.of(0, 0, 40, 20), "rooms",
									List.of(map("key", "west", "rect_ft", List.of(0, 0, 20, 20)),
											map("key", "east", "rect_ft", List.of(20, 0, 20, 20)))),
							"placements",
							List.of(position("character:1", 5, 5), position(visible, 10, 5), position(hidden, 30, 5))))
					.get("encounter").toString();
			e.runtime().applyRuntimeChange(op(), campaign, "character:1",
					map("kind", "DAMAGE", "amount", 1, "reason", "A scratch during the old fight."));
			e.encounters().perform(op(), campaign, encounter, "character:1", map("kind", "DODGE"), false);
			savedRecord = SceneService
					.map(e.encounters().end(op(), campaign, encounter, "OTHER", "The sides stand down.").get("record"));
			endedHp = e.db().read(tx -> tx.get("character", 1).lng("current_hp"));
			savedPlayerState = e.encounters().encounterState(campaign, encounter, 100, "PLAYER");
			assertFalse(Json.write(savedPlayerState).contains(hidden));
			e.runtime().applyRuntimeChange(op(), campaign, "character:1",
					map("kind", "HEAL", "amount", 1, "reason", "Later first aid."));
			e.runtime().applyRuntimeChange(op(), campaign, "character:1",
					map("kind", "ADD_CONDITION", "condition", "POISONED", "reason", "A later unrelated poison."));
			String site = e.db().read(tx -> Ref.of(Ref.LOCATION,
					tx.get("campaign", Ref.id(campaign, Ref.CAMPAIGN)).lng("current_location_id")));
			var draft = e.maps().begin(op(), campaign, "SCENE",
					map("name", "A later open hall", "location_ref", site, "room_count", 1, "illumination", "BRIGHT",
							"geometry", map("bounds_ft", List.of(0, 0, 40, 20)), "rooms",
							List.of(map("key", "hall", "rect_ft", List.of(0, 0, 40, 20)))));
			String laterMap = e.maps().publish(op(), campaign, draft.get("preparation").toString(), null).get("map_ref")
					.toString();
			e.scenes().place(op(), campaign, laterMap, List.of(position("character:1", 5, 5), position(hidden, 25, 5)));
			assertTrue(Json.write(e.scenes().perception(campaign, laterMap, "character:1")).contains(hidden),
					"The identity is discovered only later.");
			dice(e).queue(20, 1);
			e.encounters().start(op(), campaign, map("party", List.of("character:1"), "foes", List.of(hidden)), null,
					null, null, null, null, map("map_ref", laterMap));
			assertHistorical(e, campaign, encounter, savedRecord, savedPlayerState, endedHp);
			assertTrue(dice(e).exhausted());
		}
		try (Engine e = engine(file)) {
			assertHistorical(e, campaign, encounter, savedRecord, savedPlayerState, endedHp);
		}
	}

	@Test
	void legacyEndedRowsWithoutSnapshotsDoNotNeedSurvivingFigurinesOrInventHistoricalState() throws Exception {
		try (Engine e = engine(tempDb("run6-legacy-history"))) {
			String campaign = ready(e), hidden = bandit(e, campaign, "LEGACY_HIDDEN_ROSTER");
			dice(e).queue(20, 1);
			String encounter = e.encounters().start(op(), campaign,
					map("party", List.of("character:1"), "foes", List.of(hidden)), null, null, null, null, null)
					.get("encounter").toString();
			e.encounters().end(op(), campaign, encounter, "OTHER", "The legacy fight ends.");
			long cid = Ref.id(campaign, Ref.CAMPAIGN), eid = Ref.id(encounter, Ref.ENCOUNTER);
			e.db().mutate(Database.Mutation.of("legacy_history_fixture", cid, op(), "SYSTEM", Map.of()), tx -> {
				tx.update("encounter", eid, map("record_json", null));
				for (var actor : tx.query("SELECT * FROM map_actor WHERE campaign_id=?", cid))
					tx.delete("map_actor", actor.id());
				return Map.of();
			});
			var before = e.db().snapshot(cid);
			var gm = e.encounters().encounterState(campaign, encounter, 100, "GM");
			assertEquals("UNAVAILABLE", gm.get("snapshot_status"));
			assertFalse(gm.containsKey("record"));
			assertEquals(2, SceneService.maps(gm.get("participants")).size());
			var player = e.encounters().encounterState(campaign, encounter, 100, "PLAYER");
			assertEquals(List.of("character:1"),
					SceneService.maps(player.get("participants")).stream().map(p -> p.get("character")).toList());
			assertFalse(Json.write(player).contains(hidden));
			assertFalse(Json.write(player).contains("LEGACY_HIDDEN_ROSTER"));
			assertTrue(SceneService.maps(gm.get("participants")).stream()
					.noneMatch(p -> p.containsKey("hp") || p.containsKey("position_ft")));
			assertEquals(before, e.db().snapshot(cid));
		}
	}

	private static void assertHistorical(
		Engine e, String campaign, String encounter, Map<String, Object> savedRecord,
		Map<String, Object> savedPlayerState, long endedHp) {
		var before = e.db().snapshot(Ref.id(campaign, Ref.CAMPAIGN));
		for (String audience : List.of("GM", "PLAYER")) {
			var history = e.encounters().encounterState(campaign, encounter, 100, audience);
			assertEquals("ENDED", history.get("status"));
			assertEquals(true, history.get("historical"));
			assertNull(history.get("turn"));
			assertEquals(List.of(), history.get("legal_actions"));
			var pc = SceneService.maps(history.get("participants")).stream()
					.filter(p -> "character:1".equals(p.get("character"))).findFirst().orElseThrow();
			assertFalse(Json.write(pc.get("conditions")).contains("poisoned"));
			assertEquals(List.of(5.0, 5.0), pc.get("position_ft"),
					"A saved final position remains on the old board after the live marker moves elsewhere.");
			if ("GM".equals(audience)) {
				assertEquals(endedHp, ((Number) SceneService.map(pc.get("hp")).get("current")).longValue());
				assertEquals(Json.hash(savedRecord), Json.hash(history.get("record")));
			} else {
				assertEquals(Json.hash(savedPlayerState.get("participants")), Json.hash(history.get("participants")),
						"Party history retains exactly its frozen known or last-known roster, including omitted private facts.");
				assertEquals(Json.hash(savedPlayerState.get("digest")), Json.hash(history.get("digest")));
				assertFalse(history.containsKey("record"));
				assertFalse(Json.write(history).contains("NEVER_SEEN_DURING_OLD_FIGHT"));
			}
		}
		assertEquals(before, e.db().snapshot(Ref.id(campaign, Ref.CAMPAIGN)));
	}

	private static void assertCurrentTurn(Engine e, String campaign, String encounter, String expected) {
		for (String audience : List.of("GM", "PLAYER"))
			assertEquals(expected, turn(e.encounters().encounterState(campaign, encounter, 100, audience)));
	}

	private static String turn(Map<String, Object> state) {
		return SceneService.map(state.get("turn")).get("character").toString();
	}

	private static String ready(Engine e) {
		String c = committedCampaign(e);
		e.sessions().bootstrap(op(), c, null);
		return c;
	}

	private static String bandit(Engine e, String campaign, String name) {
		return e.runtime().materialize(op(), campaign, "Bandit", name, null, null, null, null, false).get("character")
				.toString();
	}

	private static Map<String, Object> position(String actor, int x, int y) {
		return map("character", actor, "x_ft", x, "y_ft", y);
	}

	private static ScriptedRollService dice(Engine e) {
		return (ScriptedRollService) e.roller();
	}
}
