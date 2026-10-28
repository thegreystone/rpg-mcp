/*
 * Copyright (C) 2026 Marcus Hirt
 * SPDX-License-Identifier: BSD-3-Clause
 */
package se.hirt.mcp.rpg.maps;

import org.junit.jupiter.api.Test;
import se.hirt.mcp.rpg.protocol.Json;
import se.hirt.mcp.rpg.protocol.RpgException;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MapRendererTest {

	@Test
	void deterministicPngPreservesHolesScaleAndInput() {
		Map<String, Object> scene = Map.of("name", "Hall", "bounds_ft", List.of(0, 0, 40, 20), "rooms",
				List.of(Map.of("rect_ft", List.of(0, 0, 40, 20), "material", "STONE", "holes_ft",
						List.of(List.of(List.of(10, 5), List.of(20, 5), List.of(20, 15), List.of(10, 15))))));
		String before = Json.write(scene);
		var renderer = new MapRenderer();
		Map<String, Object> options = Map.of("max_pixels", 201, "show_labels", false, "show_grid", false, "style",
				"HIGH_CONTRAST");
		MapRenderer.Rendered first = renderer.render(scene, options);
		MapRenderer.Rendered again = renderer.render(scene, options);
		assertArrayEquals(first.png(), again.png());
		assertEquals(before, Json.write(scene));
		assertEquals(201, first.width());
		assertEquals(101, first.height());
		assertEquals(5d, first.transform().get("pixels_per_foot"));
		PngCodec.Raster raster = PngCodec.decode(first.png());
		assertEquals(0xffeeeeea, pixel(raster, 25, 50), "stone remains outside the hole");
		assertEquals(0xff202020, pixel(raster, 75, 50), "the hole retains the underlying void");
		assertTrue(first.text().contains("40 by 20 ft"));
	}

	@Test
	void overlayStyleAndResolutionDoNotMovePhysicalMarkers() {
		Map<String, Object> scene = Map.of("bounds_ft", List.of(-10, -10, 40, 20), "rooms",
				List.of(Map.of("rect_ft", List.of(-10, -10, 40, 20))), "actors",
				List.of(Map.of("character_ref", "character:7", "label", "M", "x_ft", 5, "y_ft", 0)));
		var renderer = new MapRenderer();
		MapRenderer.Rendered five = renderer.render(scene, Map.of("max_pixels", 201, "grid_ft", 5));
		MapRenderer.Rendered ten = renderer.render(scene,
				Map.of("max_pixels", 201, "grid_ft", 10, "style", "PARCHMENT"));
		assertEquals(five.transform().get("world_to_pixel"), ten.transform().get("world_to_pixel"));
		assertEquals(five.text().replace("5 ft grid", "10 ft grid"), ten.text());
		assertTrue(five.text().contains("character:7"));
		assertEquals(pixel(PngCodec.decode(five.png()), 75, 45), pixel(PngCodec.decode(ten.png()), 75, 45));
	}

	@Test
	void fogHidesActorsAndDistinguishesExplorationFromCurrentSight() {
		Map<String, Object> scene = Map.of("bounds_ft", List.of(0, 0, 15, 5), "rooms",
				List.of(Map.of("rect_ft", List.of(0, 0, 15, 5))), "actors",
				List.of(Map.of("name", "Mira", "x_ft", 2.5, "y_ft", 2.5),
						Map.of("name", "Unseen Guard", "x_ft", 7.5, "y_ft", 2.5, "side", "ENEMY"),
						Map.of("name", "Secret Monster", "x_ft", 12.5, "y_ft", 2.5, "side", "ENEMY")),
				"visibility", Map.of("cell_ft", 5, "origin_ft", List.of(0, 0), "visible_cells", List.of(List.of(0, 0)),
						"explored_cells", List.of(List.of(1, 0))));
		MapRenderer.Rendered rendered = new MapRenderer().render(scene,
				Map.of("max_pixels", 301, "show_grid", false, "show_labels", false));
		PngCodec.Raster raster = PngCodec.decode(rendered.png());
		assertEquals(0xff2876a8, pixel(raster, 50, 50));
		assertNotEquals(0xffb6473a, pixel(raster, 150, 50));
		assertNotEquals(0xff141a20, pixel(raster, 150, 50));
		assertEquals(0xff141a20, pixel(raster, 250, 50));
		assertTrue(rendered.text().contains("Mira"));
		assertFalse(rendered.text().contains("Unseen Guard"));
		assertFalse(rendered.text().contains("Secret Monster"));
	}

	@Test
	void importedTextureRepeatsInWorldCoordinatesAcrossRoomPieces() {
		var texture = new AssetService.Texture(
				new PngCodec.Raster(2, 2, new int[] {0xffe01122, 0xff10ee22, 0xff1122ee, 0xffeeee11}), 4);
		Map<String, Object> scene = Map.of("bounds_ft", List.of(-4, 0, 12, 4), "rooms",
				List.of(Map.of("rect_ft", List.of(-4, 0, 6, 4), "material", "CUSTOM"),
						Map.of("rect_ft", List.of(2, 0, 6, 4), "material", "CUSTOM")));
		var rendered = new MapRenderer().render(scene,
				Map.of("max_pixels", 121, "show_grid", false, "show_labels", false), Map.of("CUSTOM", texture));
		PngCodec.Raster raster = PngCodec.decode(rendered.png());
		assertEquals(pixel(raster, 10, 10), pixel(raster, 50, 10));
		assertEquals(pixel(raster, 50, 10), pixel(raster, 90, 10));
		assertNotEquals(pixel(raster, 50, 10), pixel(raster, 70, 10));
	}

	@Test
	void regionalLandmarksHaveVisibleSymbolsAndNamesInBothPresentations() {
		Map<String, Object> scene = Map.of("kind", "REGION", "bounds_ft", List.of(0, 0, 1000, 1000), "features",
				List.of(Map.of("kind", "CITY", "name", "Sarg", "point_ft", List.of(200, 200)),
						Map.of("kind", "LANDMARK", "name", "Castle Nefaria", "point_ft", List.of(800, 800)),
						Map.of("kind", "FOREST", "name", "Whisperwood", "polygon_ft",
								List.of(List.of(300, 300), List.of(600, 300), List.of(600, 600), List.of(300, 600))),
						Map.of("kind", "ROAD", "name", "Old Road", "path_ft",
								List.of(List.of(200, 200), List.of(450, 450), List.of(800, 800)))));
		var renderer = new MapRenderer();
		var rendered = renderer.render(scene, Map.of("max_pixels", 501, "style", "PARCHMENT", "show_labels", false));
		var image = PngCodec.decode(rendered.png());
		assertNotEquals(pixel(image, 10, 10), pixel(image, 100, 400), "city has a symbol");
		assertNotEquals(pixel(image, 10, 10), pixel(image, 400, 100), "castle has a symbol");
		assertTrue(rendered.text().contains("Sarg"));
		assertTrue(rendered.text().contains("Castle Nefaria"));
		assertTrue(rendered.text().contains("Whisperwood"));
		assertTrue(rendered.text().contains("Old Road"));
		assertTrue(renderer.describe(scene, Map.of()).contains("Castle Nefaria"));
		assertFalse(
				java.util.Arrays.equals(rendered.png(),
						renderer.render(scene, Map.of("max_pixels", 501, "style", "PARCHMENT")).png()),
				"labels are painted as well as described");
	}

	@Test
	void regionalMapsUseMilesAndScaleWithoutClaimingATacticalGrid() {
		var rendered = new MapRenderer().render(
				Map.of("kind", "REGION", "name", "Sarg Coast", "bounds_ft", List.of(0, 0, 528000, 264000)),
				Map.of("max_pixels", 501));
		assertEquals(false, rendered.transform().get("grid_visible"));
		assertFalse(rendered.transform().containsKey("grid_ft"));
		assertTrue(rendered.text().contains("100 by 50 mi"));
		assertTrue(rendered.text().contains("scale bar"));
		assertFalse(rendered.text().contains("5 ft grid"));
		PngCodec.Raster image = PngCodec.decode(rendered.png());
		assertNotEquals(image.pixels()[0], image.pixels()[(image.height() - 16) * image.width() + 12],
				"a physical scale is drawn");
	}

	@Test
	void renderingBoundsAndProfilesAreValidated() {
		var renderer = new MapRenderer();
		Map<String, Object> scene = Map.of("bounds_ft", List.of(0, 0, 40, 20));
		assertThrows(RpgException.class, () -> renderer.render(scene, Map.of("max_pixels", 4096)));
		assertThrows(RpgException.class, () -> renderer.render(scene, Map.of("grid_ft", 7)));
		assertThrows(RpgException.class, () -> renderer.render(scene, Map.of("style", "UNKNOWN")));
		assertThrows(RpgException.class, () -> renderer.render(Map.of("bounds_ft", List.of(0, 0, -1, 20)), Map.of()));
	}

	@Test
	void observedRoadFragmentsDoNotDrawInternalEndCaps() {
		var scene = new java.util.LinkedHashMap<String, Object>();
		scene.put("bounds_ft", List.of(0, 0, 20, 20));
		scene.put("rooms", List.of(Map.of("rect_ft", List.of(0, 0, 20, 20), "material", "GRASS")));
		scene.put("roads", List.of(Map.of("path_ft", List.of(List.of(0, 10), List.of(20, 10)), "width_ft", 5)));
		var options = Map.<String, Object> of("max_pixels", 201, "show_grid", false, "show_labels", false);
		var renderer = new MapRenderer();
		byte[] continuous = renderer.render(scene, options).png();
		var fragments = new java.util.ArrayList<Map<String, Object>>();
		for (int x = 0; x < 20; x += 5)
			fragments.add(Map.of("path_ft", List.of(List.of(x, 10), List.of(x + 5, 10)), "width_ft", 5));
		scene.put("roads", fragments);
		assertArrayEquals(continuous, renderer.render(scene, options).png(),
				"Cell clipping must not invent white borders across a continuous road.");
	}

	@Test
	void playerTextNamesMarkersWithoutTechnicalReferencesOrCoordinates() {
		var scene = Map.<String, Object> of("name", "Skarr's Hall", "bounds_ft", List.of(0, 0, 40, 20), "rooms",
				List.of(Map.of("name", "Vault", "rect_ft", List.of(20, 0, 20, 20))), "actors",
				List.of(Map.of("name", "Mira", "character_ref", "character:7", "x_ft", 5, "y_ft", 0)));
		var renderer = new MapRenderer();
		String player = renderer.describe(scene, Map.of("technical_text", false));
		assertTrue(player.contains("Marker 1: Mira in the southwest"), player);
		assertTrue(player.contains("Vault in the east"), player);
		assertFalse(player.contains("character:"));
		assertFalse(player.contains(" at ("));
		assertFalse(player.contains("move origin"));
		assertTrue(renderer.describe(scene, Map.of("technical_text", true)).contains("character:7"));
		var curly = new java.util.LinkedHashMap<>(scene);
		curly.put("rooms", List.of(Map.of("name", "Skarr\u2019s Hall", "rect_ft", List.of(0, 0, 40, 20))));
		var straight = new java.util.LinkedHashMap<>(curly);
		straight.put("rooms", List.of(Map.of("name", "Skarr's Hall", "rect_ft", List.of(0, 0, 40, 20))));
		assertArrayEquals(renderer.render(straight, Map.of("max_pixels", 501)).png(),
				renderer.render(curly, Map.of("max_pixels", 501)).png(),
				"Typographic apostrophes must use the apostrophe glyph rather than a question mark.");
	}

	private static int pixel(PngCodec.Raster image, int x, int y) {
		return image.pixels()[y * image.width() + x];
	}
}
