package se.hirt.mcp.rpg;

import org.junit.jupiter.api.Test;
import se.hirt.mcp.rpg.maps.SceneService;
import se.hirt.mcp.rpg.protocol.Json;
import se.hirt.mcp.rpg.protocol.Ref;
import se.hirt.mcp.rpg.world.TravelJourneyService;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static se.hirt.mcp.rpg.TestCampaigns.*;

class WorldBelievabilityTest {
	@Test
	void missingCreativeGeographyStagesFactsWithoutInventingAnotherCampaignsWorld() throws Exception {
		try (Engine e = engine(tempDb("source-only-world"))) {
			String c = committedCampaign(e);
			long cid = Ref.id(c, "campaign");
			assertEquals(0L,
					e.db().read(tx -> tx.count("SELECT COUNT(*) FROM map_atlas WHERE campaign_id=?", cid)).longValue());
			var resumed = e.sessions().bootstrap(op(), c, null);
			var world = m(resumed.get("world_maps"));
			assertEquals("PREPARATION_REQUIRED", world.get("status"));
			String prep = world.get("preparation").toString();
			String proposal = Json.write(e.maps().get(c, prep));
			assertFalse(proposal.contains("Alderford"));
			assertFalse(proposal.contains("Highmere"));
			assertFalse(proposal.contains("Silver River"));
			assertFalse(proposal.contains("Oldwood"));
			assertTrue(proposal.contains("Bellhaven"));
			assertTrue(proposal.contains("authoring_requirements"));
			assertEquals(prep, m(e.sessions().bootstrap(op(), c, null).get("world_maps")).get("preparation"));
			assertThrows(se.hirt.mcp.rpg.protocol.RpgException.class, () -> e.maps().publish(op(), c, prep, null));
		}
	}

	@Test
	void journeyContactUsesBoundedVisibleGeographyAndResumesAtItsActualPlace() throws Exception {
		try (Engine e = engine(tempDb("grounded-roadside"))) {
			String c = committedCampaign(e);
			e.sessions().bootstrap(op(), c, null);
			long cid = Ref.id(c, "campaign");
			String opening = e.db().read(tx -> "location:" + tx.get("campaign", cid).lng("current_location_id"));
			var draft = e.maps().begin(op(), c, "REGION", map("name", "Bellhaven road country", "rooms",
					List.of(map("key", "opening", "name", "Bellhaven", "location_ref", opening),
							map("key", "mine", "name", "Harrow Mine")),
					"connections",
					List.of(map("key", "mine-road", "from", "opening", "to", "mine", "kind", "ROAD", "visibility",
							"PARTY_KNOWN", "travel_minutes", 60, "distance_miles", 16000d / 5280)),
					"geometry", map("bounds_ft", List.of(0, 0, 20000, 20000)), "features",
					List.of(map("key", "mine-road", "kind", "ROAD", "graph_ref", "mine-road", "path_ft",
							List.of(List.of(1000, 1000), List.of(17000, 1000)), "width_ft", 10, "visibility",
							"PARTY_KNOWN"),
							map("key", "harrow-wood", "kind", "FOREST", "polygon_ft",
									List.of(List.of(0, 0), List.of(20000, 0), List.of(20000, 990), List.of(0, 990)),
									"visibility", "PARTY_KNOWN"))));
			var published = e.maps().publish(op(), c, draft.get("preparation").toString(), null);
			String target = m(published.get("references")).get("room:mine").toString();
			new TravelJourneyService(e.db(), e.roller()).defineRisk(op(), c, "DEFAULT",
					map("frequency_seconds", 60, "chance_percent", 0, "authored_at_fraction", .5, "terrain", "forest"));
			var stopped = e.world().move(op(), c, target, null, false, null, "Follow the authored mine road");
			assertEquals(true, stopped.get("interrupted"));
			String foe = e.runtime()
					.materialize(op(), c, "Goblin Warrior", "Roadside lookout", null, null, null, null, false)
					.get("character").toString();
			String foeLocation = e.db()
					.read(tx -> "location:" + tx.get("character", Ref.id(foe, "character")).lng("location_id"));
			assertNotEquals(opening, foeLocation,
					"An NPC materialized here belongs to the contact, not the departure inn.");
			((se.hirt.mcp.rpg.dice.ScriptedRollService) e.roller()).queue(20, 1);
			var encounter = e.encounters().start(op(), c, map("party", List.of("character:1"), "foes", List.of(foe)),
					null, null, "A forest edge beside the mine road", null, null,
					map("placements", List.of(map("character", "character:1", "x_ft", 50, "y_ft", 50),
							map("character", foe, "x_ft", 80, "y_ft", 50))));
			String mapRef = m(encounter.get("grid")).get("map_ref").toString();
			var view = m(e.scenes().context(c, mapRef, "PLAYER", 0).get("view"));
			assertFalse(SceneService.maps(view.get("roads")).isEmpty());
			assertFalse(SceneService.maps(view.get("features")).isEmpty());
			for (String layer : List.of("roads", "features"))
				for (var feature : SceneService.maps(view.get(layer)))
					for (String shape : List.of("path_ft", "polygon_ft"))
						if (feature.get(shape) instanceof List<?> points)
							for (Object raw : points) {
								List<?> point = (List<?>) raw;
								for (Object value : point) {
									double coordinate = ((Number) value).doubleValue();
									assertTrue(coordinate >= 0 && coordinate <= 100,
											"Player local coordinates must stay within the encounter window.");
								}
							}
			var resumed = e.sessions().bootstrap(op(), c, null);
			var spatial = m(resumed.get("spatial"));
			assertTrue(Json.write(spatial).contains(mapRef));
			assertTrue(Json.write(spatial.get("party_placements")).contains("character:1"));
			assertEquals(true, m(spatial.get("journey")).get("active"));
		}
	}

	@Test
	void explorationBootstrapRecoversLevelPositionsAndNames() throws Exception {
		try (Engine e = engine(tempDb("spatial-cold-bootstrap"))) {
			String c = committedCampaign(e);
			e.sessions().bootstrap(op(), c, null);
			String opening = e.db()
					.read(tx -> "location:" + tx.get("campaign", Ref.id(c, "campaign")).lng("current_location_id"));
			var draft = e.maps()
					.begin(op(), c, "SCENE", map("name", "Upper gallery", "location_ref", opening, "room_count", 1,
							"levels", List.of(1), "rooms", List.of(map("key", "gallery", "name", "Upper gallery",
									"level", 1, "rect_ft", List.of(0, 0, 30, 30))),
							"geometry", map("bounds_ft", List.of(0, 0, 30, 30))));
			String mapRef = e.maps().publish(op(), c, draft.get("preparation").toString(), null).get("map_ref")
					.toString();
			e.scenes().place(op(), c, mapRef,
					List.of(map("character", "character:1", "x_ft", 10, "y_ft", 10, "level", 1)));
			var resumed = e.sessions().bootstrap(op(), c, null);
			var spatial = m(resumed.get("spatial"));
			var actor = SceneService.maps(spatial.get("party_placements")).getFirst();
			assertEquals(mapRef, actor.get("map"));
			assertEquals(1, actor.get("level"));
			assertEquals(10d, ((Number) actor.get("x_ft")).doubleValue());
			assertTrue(SceneService.maps(resumed.get("party")).stream()
					.allMatch(member -> member.get("location_name") != null));
		}
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> m(Object value) {
		return (Map<String, Object>) value;
	}
}
