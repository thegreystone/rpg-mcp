package se.hirt.mcp.rpg;

import org.junit.jupiter.api.Test;
import se.hirt.mcp.rpg.dice.ScriptedRollService;
import se.hirt.mcp.rpg.maps.SceneService;
import se.hirt.mcp.rpg.persistence.Database;
import se.hirt.mcp.rpg.protocol.Json;
import se.hirt.mcp.rpg.protocol.Ref;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static se.hirt.mcp.rpg.TestCampaigns.*;

class Run7CombatTest {

	@Test
	void finalBoardLevelFootprintAndFallenPositionsSurviveRebindingReloadReplayAndCheckpointRestore() throws Exception {
		var file = tempDb("run7-frozen-final-positions");
		String campaign, encounter, ogre, unseen, board;
		Map<String, Object> savedGmHistory, savedPlayerHistory;
		try (Engine e = engine(file)) {
			campaign = ready(e);
			var draft = e.maps().begin(op(), campaign, "SCENE",
					map("name", "Two-floor mine", "location_ref", opening(e, campaign), "room_count", 2, "levels", 2,
							"illumination", "BRIGHT", "geometry", map("bounds_ft", List.of(0, 0, 40, 30)), "rooms", List
									.of(map("key", "lower", "level", 0, "rect_ft", List.of(0, 0, 40, 30)),
											map("key", "upper", "level", 1, "rect_ft", List.of(0, 0, 40, 30))),
							"connections",
							List.of(map("key", "mine-stair", "kind", "STAIRS", "from", "lower", "to", "upper", "state",
									"OPEN", "from_position_ft", List.of(5, 10), "to_position_ft", List.of(5, 10))),
							"actors",
							List.of(map("key", "ogre", "room", "upper", "creature_ref", "Ogre", "name",
									"Fallen upper ogre", "x_ft", 20, "y_ft", 5, "footprint_ft", 10),
									map("key", "hidden", "room", "lower", "creature_ref", "Bandit", "name",
											"NEVER_SEEN_LOWER_GUARD", "x_ft", 10, "y_ft", 5))));
			var published = e.maps().publish(op(), campaign, draft.get("preparation").toString(), null);
			board = published.get("map_ref").toString();
			var refs = SceneService.map(published.get("references"));
			ogre = refs.get("actor:ogre").toString();
			unseen = refs.get("actor:hidden").toString();
			e.scenes().place(op(), campaign, board,
					List.of(map("character", "character:1", "x_ft", 5, "y_ft", 5, "level", 1)));
			((ScriptedRollService) e.roller()).queue(20, 1);
			encounter = e.encounters()
					.start(op(), campaign, map("party", List.of("character:1"), "foes", List.of(ogre, unseen)), null,
							null, null, null, null, map("map_ref", board))
					.get("encounter").toString();
			e.runtime().applyRuntimeChange(op(), campaign, ogre,
					map("kind", "DAMAGE", "amount", 10000, "reason", "The adjudicated lethal collapse."));
			String endOperation = op();
			var ended = e.encounters().end(endOperation, campaign, encounter, "OTHER", "The collapse ends the fight.");
			assertFalse(Json.write(ended.get("party_digest")).contains("position_ft"), "Recall remains compact.");
			var gm = e.encounters().encounterState(campaign, encounter, 100, "GM");
			var player = e.encounters().encounterState(campaign, encounter, 100, "PLAYER");
			assertEquals("ENCOUNTER_END", gm.get("position_status"));
			assertPosition(person(gm, ogre), board, 1, 20, 5, 10);
			assertEquals("DEAD", person(gm, ogre).get("life_state"));
			assertPosition(person(player, ogre), board, 1, 20, 5, 10);
			assertPosition(person(player, "character:1"), board, 1, 5, 5, 5);
			assertFalse(Json.write(player).contains(unseen));
			assertFalse(Json.write(player).contains("NEVER_SEEN_LOWER_GUARD"));
			savedGmHistory = historyData(gm);
			savedPlayerHistory = historyData(player);
			long cid = Ref.id(campaign, Ref.CAMPAIGN);
			var after = e.db().snapshot(cid);
			assertEquals(true, e.encounters()
					.end(endOperation, campaign, encounter, "OTHER", "The collapse ends the fight.").get("replayed"));
			assertEquals(after, e.db().snapshot(cid));
			String checkpoint = e.checkpoints().create(op(), campaign, "After the mine collapse").get("checkpoint")
					.toString();
			String later = e.world()
					.materialize(op(), campaign, null, map("name", "Later courtyard", "kind", "SITE"), "GM").get("ref")
					.toString();
			e.rest().override(op(), campaign, "SET_LOCATION", "character:1", map("location", later),
					"A later adjudicated relocation.", null);
			var laterDraft = e.maps().begin(op(), campaign, "SCENE",
					map("name", "Later courtyard board", "location_ref", later, "room_count", 1, "geometry",
							map("bounds_ft", List.of(0, 0, 30, 30)), "rooms",
							List.of(map("key", "courtyard", "rect_ft", List.of(0, 0, 30, 30)))));
			String laterBoard = e.maps().publish(op(), campaign, laterDraft.get("preparation").toString(), null)
					.get("map_ref").toString();
			e.scenes().place(op(), campaign, laterBoard,
					List.of(map("character", "character:1", "x_ft", 10, "y_ft", 10)));
			assertEquals(savedGmHistory, historyData(e.encounters().encounterState(campaign, encounter, 100, "GM")));
			assertEquals(savedPlayerHistory,
					historyData(e.encounters().encounterState(campaign, encounter, 100, "PLAYER")));
			e.checkpoints().restore(op(), campaign, checkpoint, "Return to the saved aftermath.");
			assertEquals(savedGmHistory, historyData(e.encounters().encounterState(campaign, encounter, 100, "GM")));
			assertEquals(savedPlayerHistory,
					historyData(e.encounters().encounterState(campaign, encounter, 100, "PLAYER")));
		}
		try (Engine e = engine(file)) {
			assertEquals(savedGmHistory, historyData(e.encounters().encounterState(campaign, encounter, 100, "GM")));
			assertEquals(savedPlayerHistory,
					historyData(e.encounters().encounterState(campaign, encounter, 100, "PLAYER")));
		}
	}

	@Test
	void aPreviouslySeenEnemyMovingWhileInvisibleHasNoPlayerFinalCoordinateEvenAfterLaterDiscovery() throws Exception {
		try (Engine e = engine(tempDb("run7-unseen-final-coordinate"))) {
			String campaign = ready(e);
			String foe = e.runtime()
					.materialize(op(), campaign, "Bandit", "Remembered guard", null, null, null, null, false)
					.get("character").toString();
			((ScriptedRollService) e.roller()).queue(20, 1);
			var started = e.encounters().start(op(), campaign,
					map("party", List.of("character:1"), "foes", List.of(foe)), null, null, null, null, null,
					map("scene", map("name", "Open hall", "bounds_ft", List.of(0, 0, 40, 20)), "placements",
							List.of(map("character", "character:1", "x_ft", 5, "y_ft", 5),
									map("character", foe, "x_ft", 20, "y_ft", 5))));
			String encounter = started.get("encounter").toString();
			e.runtime().applyRuntimeChange(op(), campaign, foe,
					map("kind", "ADD_CONDITION", "condition", "INVISIBLE", "reason", "Adjudicated invisibility."));
			e.encounters().perform(op(), campaign, encounter, "character:1", map("kind", "END_TURN"), false);
			e.encounters().perform(op(), campaign, encounter, foe,
					map("kind", "MOVE", "path_ft", List.of(List.of(25, 5))), false);
			e.encounters().end(op(), campaign, encounter, "OTHER", "The sides disengage.");
			String board = SceneService.map(started.get("grid")).get("map_ref").toString();
			var gm = e.encounters().encounterState(campaign, encounter, 100, "GM");
			assertPosition(person(gm, foe), board, 0, 25, 5, 5);
			var player = e.encounters().encounterState(campaign, encounter, 100, "PLAYER");
			var remembered = person(player, foe);
			assertEquals(true, remembered.get("last_known"));
			assertFalse(remembered.containsKey("position_ft"));
			assertFalse(remembered.containsKey("move_origin_ft"));
			assertFalse(remembered.containsKey("map_ref"));
			assertFalse(Json.write(player.get("digest")).contains("25.0"));
			String frozen = Json.hash(player);
			e.runtime().applyRuntimeChange(op(), campaign, foe, map("kind", "REMOVE_CONDITION", "condition",
					"INVISIBLE", "reason", "The later invisibility ends."));
			assertTrue(Json.write(e.scenes().perception(campaign, board, "character:1")).contains(foe));
			assertEquals(frozen, Json.hash(e.encounters().encounterState(campaign, encounter, 100, "PLAYER")));
		}
	}

	@Test
	void anOlderDigestWithoutPositionSnapshotsDoesNotReconstructCoordinatesFromSurvivingFigures() throws Exception {
		try (Engine e = engine(tempDb("run7-legacy-position-record"))) {
			String campaign = ready(e);
			String foe = e.runtime().materialize(op(), campaign, "Bandit", "Guard", null, null, null, null, false)
					.get("character").toString();
			String encounter = e.encounters().start(op(), campaign,
					map("party", List.of("character:1"), "foes", List.of(foe)), null, null, null, null, null)
					.get("encounter").toString();
			e.encounters().end(op(), campaign, encounter, "OTHER", "A pre-snapshot encounter.");
			long cid = Ref.id(campaign, Ref.CAMPAIGN), eid = Ref.id(encounter, Ref.ENCOUNTER);
			e.db().mutate(Database.Mutation.of("legacy_position_fixture", cid, op(), "SYSTEM", Map.of()), tx -> {
				var record = new LinkedHashMap<>(tx.get("encounter", eid).map("record_json"));
				record.remove("position_status");
				record.remove("party_final_positions");
				for (var row : SceneService.maps(record.get("final_roster")))
					for (String key : List.of("position_status", "map_ref", "map_name", "level", "move_origin_ft",
							"position_ft", "footprint_ft"))
						row.remove(key);
				tx.update("encounter", eid, map("record_json", record));
				return Map.of();
			});
			for (String audience : List.of("GM", "PLAYER")) {
				var state = e.encounters().encounterState(campaign, encounter, 100, audience);
				assertEquals("ENCOUNTER_END", state.get("snapshot_status"));
				assertEquals("NOT_RECORDED", state.get("position_status"));
				assertTrue(SceneService.maps(state.get("participants")).stream()
						.noneMatch(p -> p.containsKey("position_ft")));
			}
		}
	}

	private static Map<String, Object> historyData(Map<String, Object> state) {
		var history = new LinkedHashMap<>(state);
		// The public wrapper supplies current harness/revision metadata even for historical reads.
		// Every historical field, including the record, positions and filtered logs, must stay frozen.
		history.remove("meta");
		return history;
	}

	private static void assertPosition(Map<String, Object> row, String board, int level, int x, int y, int footprint) {
		assertEquals(board, row.get("map_ref"));
		assertEquals(level, ((Number) row.get("level")).intValue());
		assertEquals(List.of((double) x, (double) y), row.get("move_origin_ft"));
		assertEquals(row.get("move_origin_ft"), row.get("position_ft"));
		assertEquals(footprint, ((Number) row.get("footprint_ft")).intValue());
		assertEquals("ENCOUNTER_END", row.get("position_status"));
	}

	private static Map<String, Object> person(Map<String, Object> state, String ref) {
		return SceneService.maps(state.get("participants")).stream().filter(row -> ref.equals(row.get("character")))
				.findFirst().orElseThrow();
	}

	private static String ready(Engine e) {
		String campaign = committedCampaign(e);
		e.sessions().bootstrap(op(), campaign, null);
		return campaign;
	}

	private static String opening(Engine e, String campaign) {
		return e.db().read(tx -> Ref.of(Ref.LOCATION,
				tx.get("campaign", Ref.id(campaign, Ref.CAMPAIGN)).lng("current_location_id")));
	}
}
