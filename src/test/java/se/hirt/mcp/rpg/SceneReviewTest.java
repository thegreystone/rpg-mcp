package se.hirt.mcp.rpg;

import org.junit.jupiter.api.Test;
import se.hirt.mcp.rpg.maps.MapAuthoringService;
import se.hirt.mcp.rpg.protocol.Json;
import se.hirt.mcp.rpg.protocol.Ref;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static se.hirt.mcp.rpg.TestCampaigns.map;
import static se.hirt.mcp.rpg.TestCampaigns.op;

/** Player projections must remove private fields before they reach either text or PNG rendering. */
class SceneReviewTest {
	@Test
	void rectangularFloorCutMatchesRenderedPitAndRejectsPlacement() {
		var hole = List.of(List.of(10, 10), List.of(20, 10), List.of(20, 20), List.of(10, 20));
		Map<String, Object> document = map("bounds_ft", List.of(0, 0, 30, 30), "rooms",
				List.of(map("rect_ft", List.of(0, 0, 30, 30), "holes_ft", List.of(hole), "material", "STONE")));
		var physical = se.hirt.mcp.rpg.maps.SceneService.geometry(document, 0);
		var spatial = new se.hirt.mcp.rpg.spatial.SpatialRules();
		assertFalse(
				spatial.placement(physical, new se.hirt.mcp.rpg.spatial.Geometry.Footprint(
						se.hirt.mcp.rpg.maps.SceneService.point(10, 10), 1, 1), List.of()).valid(),
				"A figurine cannot stand inside the rendered cut.");
		assertTrue(spatial.placement(physical,
				new se.hirt.mcp.rpg.spatial.Geometry.Footprint(se.hirt.mcp.rpg.maps.SceneService.point(0, 0), 1, 1),
				List.of()).valid());
		var renderer = new se.hirt.mcp.rpg.maps.MapRenderer();
		var options = Map.<String, Object> of("max_pixels", 128, "show_grid", false, "show_labels", false);
		var cut = se.hirt.mcp.rpg.maps.PngCodec.decode(renderer.render(document, options).png());
		var blank = se.hirt.mcp.rpg.maps.PngCodec
				.decode(renderer.render(map("bounds_ft", List.of(0, 0, 30, 30)), options).png());
		int center = (cut.height() / 2) * cut.width() + cut.width() / 2;
		assertEquals(blank.pixels()[center], cut.pixels()[center],
				"The rejected placement lies on the background exposed by the cut.");
		assertNotEquals(blank.pixels()[10 * cut.width() + 10], cut.pixels()[10 * cut.width() + 10],
				"The surrounding rectangular floor remains drawn.");
	}

	@Test
	void knownRegionalFeatureDoesNotExposeItsGmProperties() throws Exception {
		try (Engine engine = TestCampaigns.engine(TestCampaigns.tempDb("region-projection-review"))) {
			String campaign = TestCampaigns.committedCampaign(engine);
			engine.sessions().bootstrap(op(), campaign, null);
			String mapRef = TestCampaigns.publishRegionalFixture(engine, campaign);
			MapAuthoringService maps = new MapAuthoringService(engine.db(), engine.rules());
			maps.annotate(op(), campaign, mapRef, null,
					map("key", "known-keep", "kind", "WORLD_POI", "name", "The Old Keep", "visibility", "PARTY_KNOWN",
							"position_ft", List.of(10000, 20000), "gm_notes", "vault-below-keep", "hidden_occupants",
							List.of("unseen-necromancer")));
			String player = Json.write(engine.scenes().context(campaign, mapRef, "PLAYER", 0));
			assertTrue(player.contains("The Old Keep"));
			assertFalse(player.contains("vault-below-keep"));
			assertFalse(player.contains("unseen-necromancer"));
		}
	}

	@Test
	void GmOnlyObjectStaysPrivateEvenOnVisibleFloor() throws Exception {
		try (Engine engine = TestCampaigns.engine(TestCampaigns.tempDb("object-projection-review"))) {
			String campaign = TestCampaigns.committedCampaign(engine);
			engine.sessions().bootstrap(op(), campaign, null);
			String mapRef = scene(engine, campaign, map("key", "private-clue", "room", "room", "name",
					"An unrevealed hidden rune", "x_ft", 5, "y_ft", 5, "visibility", "GM_ONLY"));
			engine.scenes().place(op(), campaign, mapRef,
					List.of(map("character", "character:1", "x_ft", 0, "y_ft", 0, "level", 0)));
			String player = Json.write(engine.scenes().context(campaign, mapRef, "PLAYER", 0));
			assertFalse(player.contains("An unrevealed hidden rune"));
			assertFalse(player.contains("private-clue"));
		}
	}

	@Test
	void partiallySeenObjectDoesNotPublishItsUnseenExtent() throws Exception {
		try (Engine engine = TestCampaigns.engine(TestCampaigns.tempDb("object-shape-review"))) {
			String campaign = TestCampaigns.committedCampaign(engine);
			engine.sessions().bootstrap(op(), campaign, null);
			String mapRef = scene(engine, campaign, map("key", "long-object", "room", "room", "name", "Long sculpture",
					"rect_ft", List.of(10, 10, 180, 5)));
			engine.scenes().place(op(), campaign, mapRef,
					List.of(map("character", "character:1", "x_ft", 0, "y_ft", 0, "level", 0)));
			Map<String, Object> view = object(engine.scenes().context(campaign, mapRef, "PLAYER", 0).get("view"));
			assertTrue(
					objects(view.get("objects")).stream().anyMatch(item -> "Long sculpture".equals(item.get("name"))),
					"The visible portion should remain useful to the player.");
			for (Map<String, Object> item : objects(view.get("objects"))) {
				if (item.get("rect_ft") instanceof List<?> rect)
					assertTrue(((Number) rect.get(0)).doubleValue() + ((Number) rect.get(2)).doubleValue() <= 125,
							"Player geometry cannot include a sculpture's extent outside current or remembered sight.");
			}
		}
	}

	private static String scene(Engine engine, String campaign, Map<String, Object> placedObject) {
		String opening = engine.db()
				.read(tx -> "location:" + tx.get("campaign", Ref.id(campaign, "campaign")).lng("current_location_id"));
		MapAuthoringService maps = new MapAuthoringService(engine.db(), engine.rules());
		Map<String, Object> draft = maps.begin(op(), campaign, "SCENE",
				map("name", "Observation test scene", "location_ref", opening, "room_count", 1, "rooms",
						List.of(map("key", "room", "location_ref", opening, "rect_ft", List.of(0, 0, 200, 30))),
						"geometry", map("bounds_ft", List.of(0, 0, 200, 30)), "objects", List.of(placedObject)));
		return (String) maps.publish(op(), campaign, (String) draft.get("preparation"), null).get("map_ref");
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> object(Object value) {
		return (Map<String, Object>) value;
	}

	@SuppressWarnings("unchecked")
	private static List<Map<String, Object>> objects(Object value) {
		return (List<Map<String, Object>>) value;
	}
}
