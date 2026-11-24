package se.hirt.mcp.rpg;

import org.junit.jupiter.api.Test;
import se.hirt.mcp.rpg.maps.MapAuthoringService;
import se.hirt.mcp.rpg.maps.MapRenderer;
import se.hirt.mcp.rpg.maps.SceneService;
import se.hirt.mcp.rpg.maps.WorldMapPreparation;
import se.hirt.mcp.rpg.persistence.Database;
import se.hirt.mcp.rpg.protocol.Json;
import se.hirt.mcp.rpg.protocol.Ref;
import se.hirt.mcp.rpg.protocol.RpgException;
import se.hirt.mcp.rpg.world.TravelJourneyService;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static se.hirt.mcp.rpg.TestCampaigns.*;

class Run3GeographyTest {

	@Test
	void sourcedDraftPublishesOnlyAuthoredGeographyAndTravelUsesItsActualMidpoint() throws Exception {
		try (Engine e = engine(tempDb("run3-authored-geography"))) {
			String c = committedCampaign(e);
			e.sessions().bootstrap(op(), c, null);
			long cid = Ref.id(c, "campaign");
			String opening = e.db().read(tx -> "location:" + tx.get("campaign", cid).lng("current_location_id"));
			String mine = e.world().materialize(op(), c, null,
					map("name", "Ashcombe Mine", "kind", "SITE", "connections", List.of(map("to", opening, "kind",
							"ROAD", "travel_minutes", 60, "distance_miles", 4, "state", "LOCKED"))),
					"GM").get("ref").toString();
			String connection = e.db().read(tx -> "connection:" + tx
					.queryOne("SELECT id FROM location_connection WHERE campaign_id=? ORDER BY id DESC LIMIT 1", cid)
					.orElseThrow().id());
			var source = e.db().mutate(Database.Mutation.of("stage_source_fixture", cid, op(), "GM", Map.of()),
					tx -> WorldMapPreparation.initialize(tx, e.rules(), cid, Ref.id(opening, "location"), Map.of()));
			String preparation = source.get("preparation").toString();
			assertTrue(Json.write(e.maps().get(c, preparation)).contains("DRAFT_APPROXIMATION"));
			e.maps().revise(op(), c, preparation, null,
					map("review_required", false, "rooms", List.of(
							map("key", "town", "name", "Harrowgate", "location_ref", opening, "point_ft",
									List.of(1000, 1000), "coordinate_precision", "EXACT"),
							map("key", "mine", "name", "Ashcombe Mine", "location_ref", mine, "point_ft",
									List.of(9000, 9000), "coordinate_precision", "EXACT"),
							map("key", "inn", "name", "The Copper Kettle", "parent_ref", "town", "point_ft",
									List.of(1100, 1100), "coordinate_precision", "EXACT"),
							map("key", "secret", "name", "Secret Shrine", "parent_ref", "town", "point_ft",
									List.of(1200, 1200), "coordinate_precision", "EXACT", "visibility", "GM_ONLY")),
							"connections",
							List.of(map("key", "track", "from", "town", "to", "mine", "connection_ref", connection,
									"travel_minutes", 30)),
							"features",
							List.of(map("key", "authored-track", "kind", "ROAD", "name", "Mine Track", "graph_ref",
									"track", "coordinate_precision", "EXACT", "path_ft",
									List.of(List.of(1000, 1000), List.of(9000, 1000), List.of(9000, 9000))))));
			var published = e.maps().publish(op(), c, preparation, null);
			String mapRef = published.get("map_ref").toString();
			var document = object(e.maps().get(c, mapRef).get("document"));
			assertEquals(4, SceneService.maps(document.get("rooms")).size());
			assertEquals(5, SceneService.maps(document.get("features")).size());
			assertFalse(Json.write(document).contains("DRAFT_APPROXIMATION"));
			assertEquals(1, SceneService.maps(document.get("features")).stream()
					.filter(f -> opening.equals(f.get("graph_ref"))).count());
			var refs = object(published.get("references"));
			assertEquals(opening, refs.get("room:town"));
			assertEquals(mine, refs.get("room:mine"));
			assertEquals(Ref.id(opening, "location"), e.db().read(
					tx -> tx.get("location", Ref.id(refs.get("room:inn").toString(), "location")).lng("parent_id"))
					.longValue());
			var edge = e.db().read(tx -> tx.get("location_connection", Ref.id(connection, "connection")));
			assertEquals(30, ((Number) edge.map("distance_json").get("travel_minutes")).intValue());
			assertEquals(4, ((Number) edge.map("distance_json").get("distance_miles")).intValue());
			assertEquals("LOCKED", edge.map("state_json").get("state"));
			// Opening the saved route is a live gameplay change, independent of publication.
			e.world().materialize(op(), c, opening,
					map("connections", List.of(map("to", mine, "connection_ref", connection, "state", "OPEN"))), "GM");
			var player = object(e.scenes().context(c, mapRef, "PLAYER", 0).get("view"));
			String text = e.mapRenderer().describe(player, Map.of("technical_text", false));
			assertTrue(text.contains("Harrowgate"));
			assertTrue(text.contains("Ashcombe Mine"));
			assertFalse(text.contains("Secret Shrine"));
			assertTrue(e.mapRenderer().describe(object(e.scenes().context(c, mapRef, "GM", 0).get("view")), Map.of())
					.contains("Secret Shrine"));
			assertEquals(List.of(1000d, 1000d), SceneService.maps(player.get("actors")).getFirst().get("position_ft"));
			assertEquals(0L,
					e.db().read(tx -> tx.count("SELECT COUNT(*) FROM map_actor WHERE campaign_id=?", cid)).longValue());
			new TravelJourneyService(e.db(), e.roller()).defineRisk(op(), c, "DEFAULT",
					map("frequency_seconds", 60, "chance_percent", 0, "authored_at_fraction", .5));
			assertEquals(true, e.world().move(op(), c, mine, null, false, null, "Follow the track").get("interrupted"));
			var contact = e.db().read(tx -> TravelJourneyService.encounterSceneDocument(tx, cid, null));
			assertEquals(List.of(9000d, 1000d), contact.get("journey_world_anchor_ft"));
			var anchored = e.db()
					.read(tx -> MapAuthoringService.regionalAnchor(tx, cid, null, connection, opening, 2d));
			assertEquals(List.of(9000d, 1000d), anchored.get("world_position_ft"));
			player = object(e.scenes().context(c, mapRef, "PLAYER", 0).get("view"));
			assertEquals(List.of(9000d, 1000d), SceneService.maps(player.get("actors")).getFirst().get("position_ft"));
		}
	}

	@Test
	void legacyPointHintsAndAParentAnchoredRoadRetainOnlyTheAuthoredRoute() throws Exception {
		try (Engine e = engine(tempDb("run3-legacy-endpoint-road"))) {
			String c = committedCampaign(e);
			e.sessions().bootstrap(op(), c, null);
			long cid = Ref.id(c, "campaign");
			String town = e.db().read(tx -> "location:" + tx.get("campaign", cid).lng("current_location_id"));
			String mine = e.world().materialize(op(), c, null, map("name", "Mine", "kind", "SITE"), "GM").get("ref")
					.toString();
			String inn = e.world()
					.materialize(op(), c, null, map("name", "Inn", "kind", "BUILDING", "parent", town, "connections",
							List.of(map("to", mine, "kind", "ROAD", "travel_minutes", 60, "distance_miles", 4))), "GM")
					.get("ref").toString();
			String route = e.db().read(tx -> "connection:" + tx
					.queryOne("SELECT id FROM location_connection WHERE campaign_id=? ORDER BY id DESC LIMIT 1", cid)
					.orElseThrow().id());
			var draft = e.maps().begin(op(), c, "REGION",
					map("name", "Legacy authored region", "authorship", "SOURCED_GRAPH_DRAFT", "geometry",
							map("bounds_ft", List.of(0, 0, 20000, 20000)), "rooms",
							List.of(map("key", "existing-1", "name", "Town", "location_ref", town, "point_ft",
									List.of(1000, 1000), "coordinate_precision", "EXACT"),
									map("key", "existing-2", "name", "Inn", "location_ref", inn, "parent_ref", town,
											"point_ft", List.of(1200, 1100), "coordinate_precision", "EXACT"),
									map("key", "existing-3", "name", "Mine", "location_ref", mine, "point_ft",
											List.of(9000, 9000), "coordinate_precision", "EXACT")),
							"connections",
							List.of(map("key", "saved-route", "from", "existing-2", "to", "existing-3",
									"connection_ref", route, "travel_minutes", 50)),
							"features",
							List.of(map("key", "existing-1", "name", "Old Town Hint", "kind", "SETTLEMENT", "point_ft",
									List.of(15000, 15000), "approximate", true, "graph_ref", "existing-1"),
									map("key", "existing-3", "name", "Mine", "kind", "SITE", "point_ft",
											List.of(9000, 9000), "approximate", true, "coordinate_precision", "EXACT",
											"graph_ref", "existing-3"),
									map("key", "existing-route-1", "kind", "ROUTE", "graph_ref", "saved-route",
											"path_ft", List.of(List.of(15000, 15000), List.of(16000, 18000)),
											"approximate", true, "coordinate_precision", "DRAFT_APPROXIMATION"),
									map("key", "mine-track", "kind", "ROAD", "path_ft",
											List.of(List.of(1000, 1000), List.of(9000, 1000), List.of(9000, 9000))))));
			String prep = draft.get("preparation").toString();
			var published = e.maps().publish(op(), c, prep, null);
			var features = SceneService
					.maps(object(e.maps().get(c, published.get("map_ref").toString()).get("document")).get("features"));
			assertEquals(4, features.size());
			assertFalse(Json.write(features).contains("Old Town Hint"));
			assertTrue(features.stream().anyMatch(f -> "existing-3".equals(f.get("key"))),
					"Explicit EXACT wins over an inherited approximate flag.");
			var road = features.stream().filter(f -> "mine-track".equals(f.get("key"))).findFirst().orElseThrow();
			assertEquals(route, road.get("graph_ref"));
			assertEquals("UNAMBIGUOUS_AUTHORED_ENDPOINTS", road.get("graph_association_source"));
			assertEquals(inn, road.get("from_location_ref"));
			assertEquals(List.of(9000d, 1000d),
					e.db().read(tx -> MapAuthoringService.regionalAnchor(tx, cid, null, route, inn, 2d))
							.get("world_position_ft"));
			var conflicting = e.maps().begin(op(), c, "REGION",
					map("name", "Contradictory distance", "geometry", map("bounds_ft", List.of(0, 0, 20000, 20000)),
							"connections", List.of(map("key", "route", "from", inn, "to", mine, "connection_ref", route,
									"distance_miles", 7))));
			assertEquals(false, conflicting.get("ready"));
			assertTrue(Json.write(conflicting.get("violations")).contains("DISTANCE_CONTRADICTION"));
		}
	}

	@Test
	void invalidDurationAndCyclicParentsCannotPublish() throws Exception {
		try (Engine e = engine(tempDb("run3-geography-validation"))) {
			String c = committedCampaign(e);
			e.sessions().bootstrap(op(), c, null);
			var draft = e.maps().begin(op(), c, "REGION",
					map("name", "Invalid duration", "geometry", map("bounds_ft", List.of(0, 0, 1000, 1000)), "rooms",
							List.of(map("key", "a", "point_ft", List.of(100, 100)),
									map("key", "b", "point_ft", List.of(200, 200))),
							"connections", List.of(map("key", "path", "from", "a", "to", "b", "travel_minutes", -1))));
			assertEquals(false, draft.get("ready"));
			assertThrows(RpgException.class,
					() -> e.maps().publish(op(), c, draft.get("preparation").toString(), null));
			var cyclic = e.maps().begin(op(), c, "REGION",
					map("name", "Cyclic parents", "geometry", map("bounds_ft", List.of(0, 0, 1000, 1000)), "rooms",
							List.of(map("key", "a", "parent_ref", "b", "point_ft", List.of(100, 100)),
									map("key", "b", "parent_ref", "a", "point_ft", List.of(200, 200)))));
			long cid = Ref.id(c, "campaign");
			var before = e.db().snapshot(cid);
			assertThrows(RpgException.class,
					() -> e.maps().publish(op(), c, cyclic.get("preparation").toString(), null));
			assertEquals(before, e.db().snapshot(cid));
		}
	}

	@Test
	void textNamesRoadsDoorsAndRelativeDirectionsAndExplainsStateSymbols() {
		var scene = map("name", "Roadside", "kind", "SCENE", "audience", "PLAYER", "bounds_ft", List.of(0, 0, 100, 100),
				"actors",
				List.of(map("name", "Tam", "position_ft", List.of(10, 10), "party_member", true),
						map("name", "Goblin", "position_ft", List.of(30, 25))),
				"roads", List.of(map("path_ft", List.of(List.of(0, 50), List.of(100, 50)))), "doors",
				List.of(map("from_ft", List.of(20, 20), "to_ft", List.of(20, 25), "state", "OPEN"),
						map("from_ft", List.of(40, 20), "to_ft", List.of(40, 25), "state", "CLOSED")),
				"portals", List.of(map("kind", "STAIRS", "from_level", 0, "to_level", 1, "from_position_ft",
						List.of(70, 20), "to_position_ft", List.of(70, 20))));
		var renderer = new MapRenderer();
		String text = renderer.describe(scene, Map.of("technical_text", false, "level", 0));
		assertTrue(text.contains("Road"));
		assertTrue(text.contains("Door north-east of Tam: OPEN"));
		assertTrue(text.contains("Goblin north-east of Tam"));
		assertTrue(text.contains("O=open door"));
		assertTrue(text.contains("C=closed door"));
		assertTrue(text.contains("S=stairs"));
		assertTrue(renderer.render(scene, Map.of("max_pixels", 256, "level", 0)).png().length > 100);
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> object(Object value) {
		return (Map<String, Object>) value;
	}
}
