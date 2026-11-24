package se.hirt.mcp.rpg;

import org.junit.jupiter.api.Test;
import se.hirt.mcp.rpg.maps.SceneService;
import se.hirt.mcp.rpg.persistence.Row;
import se.hirt.mcp.rpg.protocol.Ref;
import se.hirt.mcp.rpg.protocol.RpgException;
import se.hirt.mcp.rpg.spatial.SpatialRules;
import se.hirt.mcp.rpg.world.TravelJourneyService;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static se.hirt.mcp.rpg.TestCampaigns.*;

/** Reproduces run3's taproom/cellar/published-board sequence through the real services. */
class Run3SceneLifecycleTest {

	@Test
	void authoredCellarReplacesStaleTaproomAndKeepsHistoryAndCheckpoint() throws Exception {
		var file = tempDb("run3-cellar-lifecycle");
		String campaign, guard, taproom, cellar, checkpoint;
		Map<String, Object> original;
		try (Engine e = engine(file)) {
			campaign = committedCampaign(e);
			e.sessions().bootstrap(op(), campaign, null);
			guard = guard(e, campaign);
			var first = start(e, campaign, guard, map("scene",
					map("name", "Old taproom", "bounds_ft", List.of(0, 0, 30, 30), "objects", List
							.of(map("key", "oak-table", "rect_ft", List.of(10, 10, 5, 5), "blocks_movement", true))),
					"placements", placements(guard, 0, 0, 20, 0)));
			taproom = board(first);
			end(e, campaign, first);
			original = document(e, campaign, taproom);
			checkpoint = e.checkpoints().create(op(), campaign, "Before descending to the cellar").get("checkpoint")
					.toString();
			var second = start(e, campaign, guard,
					map("scene", map("name", "New cellar", "bounds_ft", List.of(0, 0, 40, 30), "illumination", "DIM"),
							"placements", placements(guard, 10, 10, 25, 10)));
			cellar = board(second);
			assertNotEquals(taproom, cellar);
			assertEquals("DIM", document(e, campaign, cellar).get("illumination"));
			assertEquals(original, document(e, campaign, taproom));
			assertEquals(Ref.id(cellar, Ref.MAP), actor(e, campaign, guard).lng("scene_id").longValue());
			end(e, campaign, second);
			var third = start(e, campaign, guard, Map.of());
			assertEquals(cellar, board(third), "An incidental sibling remains current while its figurines are there.");
			end(e, campaign, third);
		}
		try (Engine e = engine(file)) {
			assertEquals(Ref.id(cellar, Ref.MAP), actor(e, campaign, "character:1").lng("scene_id").longValue());
			e.checkpoints().restore(op(), campaign, checkpoint, "Return to the taproom before the descent.");
			assertEquals(original, document(e, campaign, taproom));
			assertEquals(Ref.id(taproom, Ref.MAP), actor(e, campaign, guard).lng("scene_id").longValue());
		}
	}

	@Test
	void publishedCoverageSupersedesIncidentalFigurinesWithoutDuplicatingLoot() throws Exception {
		try (Engine e = engine(tempDb("run3-published-coverage"))) {
			String campaign = committedCampaign(e);
			e.sessions().bootstrap(op(), campaign, null);
			String guard = guard(e, campaign);
			var first = start(e, campaign, guard, Map.of());
			String old = board(first);
			end(e, campaign, first);
			long guardFigurine = actor(e, campaign, guard).id();
			String published = publish(e, campaign, opening(e, campaign), "Authored tavern", true);
			long cid = Ref.id(campaign, Ref.CAMPAIGN);
			long loot = e.db().read(tx -> tx.count("SELECT COUNT(*) FROM inventory_entry WHERE campaign_id=?", cid));
			var second = start(e, campaign, guard, Map.of());
			assertEquals(published, board(second));
			assertEquals(guardFigurine, actor(e, campaign, guard).id());
			assertEquals(Ref.id(published, Ref.MAP), actor(e, campaign, guard).lng("scene_id").longValue());
			assertNotNull(document(e, campaign, old), "Old encounter history remains readable.");
			end(e, campaign, second);
			var third = start(e, campaign, guard, map("map_ref", published));
			assertEquals(published, board(third));
			assertEquals(loot, e.db()
					.read(tx -> tx.count("SELECT COUNT(*) FROM inventory_entry WHERE campaign_id=?", cid)).longValue());
		}
	}

	@Test
	void explicitSameLocationSceneMigratesParticipantsButUnrelatedMapCannotTeleportThem() throws Exception {
		try (Engine e = engine(tempDb("run3-explicit-map"))) {
			String campaign = committedCampaign(e);
			e.sessions().bootstrap(op(), campaign, null);
			String guard = guard(e, campaign);
			var first = start(e, campaign, guard, Map.of());
			end(e, campaign, first);
			String destination = publish(e, campaign, opening(e, campaign), "Same-location cellar", false);
			var second = start(e, campaign, guard,
					map("map_ref", destination, "placements", placements(guard, 10, 5, 25, 5)));
			assertEquals(destination, board(second));
			assertEquals(10.0, ((Number) actor(e, campaign, "character:1").get("x_ft")).doubleValue());
			end(e, campaign, second);
			String away = e.world()
					.materialize(op(), campaign, null, map("name", "Unrelated distant tower", "kind", "SITE"), "GM")
					.get("ref").toString();
			String unrelated = publish(e, campaign, away, "Far-away tower map", false);
			var before = e.db().snapshot(Ref.id(campaign, Ref.CAMPAIGN));
			RpgException refused = assertThrows(RpgException.class,
					() -> start(e, campaign, guard, map("map_ref", unrelated)));
			assertTrue(refused.getMessage().contains("Far-away tower map"));
			assertTrue(refused.getMessage().contains(unrelated));
			assertEquals(before, e.db().snapshot(Ref.id(campaign, Ref.CAMPAIGN)));
		}
	}

	@Test
	void refinementPreservesDestroyedFurnishingsAndDeploymentErrorsNameActorMapAndBlocker() throws Exception {
		try (Engine e = engine(tempDb("run3-deployment-errors"))) {
			String campaign = committedCampaign(e);
			e.sessions().bootstrap(op(), campaign, null);
			String guard = guard(e, campaign);
			var table = map("key", "oak-table", "name", "Heavy oak table", "rect_ft", List.of(10, 10, 5, 5),
					"blocks_movement", true);
			RpgException blocked = assertThrows(RpgException.class, () -> start(e, campaign, guard, map("scene",
					map("name", "Blocked taproom", "bounds_ft", List.of(0, 0, 30, 30), "objects", List.of(table)),
					"placements", placements(guard, 10, 10, 20, 0))));
			assertTrue(blocked.getMessage().contains("Richard Greystone (character:1)"), blocked.getMessage());
			assertTrue(blocked.getMessage().contains("Blocked taproom (map:"), blocked.getMessage());
			assertTrue(blocked.getMessage().contains("Heavy oak table [oak-table]"), blocked.getMessage());
			var first = start(e, campaign, guard,
					map("scene", map("name", "Intact taproom", "scope_key", "taproom", "bounds_ft",
							List.of(0, 0, 30, 30), "objects", List.of(table)), "placements",
							placements(guard, 0, 0, 20, 0)));
			String old = board(first);
			end(e, campaign, first);
			e.scenes().changeObject(op(), campaign, old, "oak-table", map("state", "DESTROYED"), "The table breaks.");
			var second = start(e, campaign, guard,
					map("scene", map("name", "Extended taproom", "scope_key", "taproom", "bounds_ft",
							List.of(0, 0, 40, 30), "objects", List.of(table)), "placements",
							placements(guard, 10, 10, 25, 0)));
			assertEquals("DESTROYED",
					SceneService.maps(document(e, campaign, board(second)).get("objects")).getFirst().get("state"));
		}
	}

	@Test
	void roadsideOppositionStartsAtRangeWhileExplicitMeleePlacementStillWorks() throws Exception {
		try (Engine e = engine(tempDb("run3-roadside-deployment"))) {
			String campaign = committedCampaign(e);
			e.sessions().bootstrap(op(), campaign, null);
			var materialized = e.world()
					.materialize(op(), campaign, opening(e, campaign),
							map("extend", true, "connections", List.of(map("to",
									map("name", "Gorse pass", "kind", "SITE"), "kind", "PATH", "travel_minutes", 20))),
							"GM");
			String target = SceneService.maps(materialized.get("new_connections")).getFirst().get("to").toString();
			var journeys = new TravelJourneyService(e.db(), e.roller());
			journeys.defineRisk(op(), campaign, "DEFAULT", map("authored_at_fraction", .5, "frequency_seconds", 60,
					"chance_percent", 0, "opposition", List.of("Scouts in the gorse")));
			e.world().move(op(), campaign, target, null, false, null, "Follow the road.");
			String guard = guard(e, campaign);
			var first = start(e, campaign, guard, Map.of());
			assertTrue(SpatialRules.distance(SceneService.footprint(actor(e, campaign, "character:1")),
					SceneService.footprint(actor(e, campaign, guard))) >= 30 * 12);
			end(e, campaign, first);
			var second = start(e, campaign, guard,
					map("scene", map("name", "Explicit road ambush", "bounds_ft", List.of(0, 0, 100, 100)),
							"placements", placements(guard, 50, 50, 55, 50)));
			assertEquals(5 * 12, SpatialRules.distance(SceneService.footprint(actor(e, campaign, "character:1")),
					SceneService.footprint(actor(e, campaign, guard))));
			assertNotEquals(board(first), board(second));
		}
	}

	private static Map<String, Object> start(Engine e, String campaign, String guard, Map<String, Object> options) {
		return e.encounters().start(op(), campaign, map("party", List.of("character:1"), "guards", List.of(guard)),
				null, null, null, null, null, options);
	}

	private static void end(Engine e, String campaign, Map<String, Object> started) {
		e.encounters().end(op(), campaign, started.get("encounter").toString(), "OTHER", "The confrontation ends.");
	}

	private static String board(Map<String, Object> started) {
		return SceneService.map(started.get("grid")).get("map_ref").toString();
	}

	private static String guard(Engine e, String campaign) {
		return e.runtime().materialize(op(), campaign, "Bandit", "Cellar guard", null, null, null, null, false)
				.get("character").toString();
	}

	private static String opening(Engine e, String campaign) {
		return e.db().read(
				tx -> "location:" + tx.get("campaign", Ref.id(campaign, Ref.CAMPAIGN)).lng("current_location_id"));
	}

	private static String publish(Engine e, String campaign, String location, String name, boolean reward) {
		var proposal = map("name", name, "location_ref", location, "room_count", 1, "geometry",
				map("bounds_ft", List.of(0, 0, 40, 30)), "rooms",
				List.of(map("key", "room", "location_ref", location, "rect_ft", List.of(0, 0, 40, 30))));
		if (reward)
			proposal.put("rewards",
					List.of(map("key", "staff", "room", "room", "item_ref", "Quarterstaff", "x_ft", 35, "y_ft", 20)));
		String prep = e.maps().begin(op(), campaign, "SCENE", proposal).get("preparation").toString();
		return e.maps().publish(op(), campaign, prep, null).get("map_ref").toString();
	}

	private static List<Map<String, Object>> placements(String guard, int x, int y, int gx, int gy) {
		return List.of(map("character", "character:1", "x_ft", x, "y_ft", y),
				map("character", guard, "x_ft", gx, "y_ft", gy));
	}

	private static Row actor(Engine e, String campaign, String character) {
		return e.db().read(tx -> SceneService.actor(tx, Ref.id(campaign, Ref.CAMPAIGN), character));
	}

	private static Map<String, Object> document(Engine e, String campaign, String map) {
		return e.db()
				.read(tx -> SceneService.document(tx, SceneService.scene(tx, Ref.id(campaign, Ref.CAMPAIGN), map)));
	}
}
