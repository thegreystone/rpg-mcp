package se.hirt.mcp.rpg.maps;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class Run4RendererTest {

	@Test
	void darknessKeepsPartyFigurinesWithoutRevealingTerrainOrEnemies() {
		var scene = Map.<String, Object> of("audience", "PLAYER", "bounds_ft", List.of(0, 0, 40, 40), "visibility",
				Map.of("visible_cells", List.of(), "explored_cells", List.of()), "rooms",
				List.of(Map.of("rect_ft", List.of(0, 0, 40, 40))), "actors",
				List.of(Map.of("name", "Tam", "party_member", true, "position_ft", List.of(7.5, 7.5)),
						Map.of("name", "Unseen guard", "party_member", false, "position_ft", List.of(20, 20))));
		assertEquals(1, MapRenderer.visibleActors(scene, Map.of()).size());
		var rendered = new MapRenderer().render(scene, Map.of("max_pixels", 401, "show_labels", false));
		assertTrue(rendered.text().contains("Tam"));
		assertFalse(rendered.text().contains("Unseen guard"));
		var image = PngCodec.decode(rendered.png());
		assertEquals(0xff2876a8, pixel(image, 75, 325), "A known party figurine remains visible in darkness.");
		assertEquals(0xff141a20, pixel(image, 200, 200), "Neither the guard nor the unlit floor is revealed.");
	}

	@Test
	void incidentalFurnitureUsesTheSameSouthwestOriginAsCollision() {
		var scene = Map.<String, Object> of("bounds_ft", List.of(0, 0, 40, 40), "rooms",
				List.of(Map.of("rect_ft", List.of(0, 0, 40, 40))), "objects",
				List.of(Map.of("key", "bar", "x_ft", 0, "y_ft", 25, "width_ft", 25, "height_ft", 5)));
		var image = PngCodec.decode(new MapRenderer()
				.render(scene, Map.of("max_pixels", 401, "show_grid", false, "show_labels", false)).png());
		assertEquals(0xff946b42, pixel(image, 200, 130), "The bar covers world (20,27), like its collision shape.");
		assertNotEquals(0xff946b42, pixel(image, 100, 170), "World (10,23) is south of the bar.");
	}

	private static int pixel(PngCodec.Raster image, int x, int y) {
		return image.pixels()[y * image.width() + x];
	}

	@Test
	void observedDoorAtTheFogBoundaryAndPassageFragmentsAreNamedOnce() {
		var scene = Map.<String, Object> of("name", "Doorway", "audience", "PLAYER", "bounds_ft", List.of(0, 0, 20, 20),
				"visibility",
				Map.of("origin_ft", List.of(0, 0), "visible_cells", List.of(List.of(0, 0)), "explored_cells",
						List.of()),
				"actors", List.of(Map.of("name", "Tam", "party_member", true, "position_ft", List.of(2.5, 2.5))),
				"doors",
				List.of(Map.of("key", "entry", "kind", "DOOR", "from_ft", List.of(5, 0), "to_ft", List.of(5, 5),
						"state", "OPEN", "observed", true),
						Map.of("key", "drift", "kind", "PASSAGE", "from_ft", List.of(0, 5), "to_ft", List.of(2.5, 5),
								"state", "OPEN", "observed", true),
						Map.of("key", "drift", "kind", "PASSAGE", "from_ft", List.of(2.5, 5), "to_ft", List.of(5, 5),
								"state", "OPEN", "observed", true)));
		String text = new MapRenderer().describe(scene, Map.of("technical_text", false));
		assertTrue(text.contains("Door"), text);
		assertEquals(1, text.lines().filter(line -> line.startsWith("Passage")).count(), text);
		assertFalse(text.contains("  of Tam"), text);
		assertTrue(text.contains("A=open passage"), text);
	}

	@Test
	void doorAndTransitionStateGlyphsDoNotCollideAndRegionalRidgesAreNotRoads() {
		var doors = List.of(Map.of("state", "CLOSED", "position_ft", List.of(5, 5)),
				Map.of("state", "BLOCKED", "position_ft", List.of(10, 5)),
				Map.of("state", "SECRET", "position_ft", List.of(15, 5)));
		var scene = Map.<String, Object> of("bounds_ft", List.of(0, 0, 40, 40), "doors", doors, "portals",
				List.of(Map.of("kind", "STAIRS", "from_level", 0, "to_level", -1, "from_position_ft", List.of(20, 5),
						"to_position_ft", List.of(20, 5))));
		String text = new MapRenderer().describe(scene, Map.of("level", 0));
		for (String entry : List.of("C=closed door", "B=blocked door", "H=secret door", "S=stairs"))
			assertTrue(text.contains(entry), text);
		var region = Map.<String, Object> of("kind", "REGION", "bounds_ft", List.of(0, 0, 100, 100), "features",
				List.of(Map.of("kind", "MOUNTAIN", "path_ft", List.of(List.of(20, 50), List.of(80, 50)))));
		var rendered = new MapRenderer().render(region, Map.of("max_pixels", 501, "show_labels", false));
		assertEquals(0xff645c4d, pixel(PngCodec.decode(rendered.png()), 250, 250));
		assertTrue(rendered.text().contains("Gray=ridge"));
	}
}
