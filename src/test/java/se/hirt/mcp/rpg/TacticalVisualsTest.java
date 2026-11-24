/*
 * Copyright (C) 2026 Marcus Hirt
 * SPDX-License-Identifier: BSD-3-Clause
 */
package se.hirt.mcp.rpg;

import org.junit.jupiter.api.Test;
import se.hirt.mcp.rpg.maps.MapPropCatalog;
import se.hirt.mcp.rpg.maps.MapRenderer;
import se.hirt.mcp.rpg.maps.SceneService;
import se.hirt.mcp.rpg.protocol.RpgException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static se.hirt.mcp.rpg.TestCampaigns.*;

class TacticalVisualsTest {

	@Test
	void propCatalogResolvesNaturalAliasesAndExposesStructure() {
		assertEquals("table_rect", MapPropCatalog.prop("rectangular wooden table").id());
		assertEquals("table_round", MapPropCatalog.prop("round wooden table").id());
		assertEquals("chair", MapPropCatalog.prop("wooden chair").id());
		assertEquals("bar_counter", MapPropCatalog.resolve(Map.of("key", "taproom-bar")).id());
		assertEquals("hearth", MapPropCatalog.resolve(Map.of("name", "Stone Fireplace")).id());
		assertTrue(MapPropCatalog.isLightEmitting(Map.of("prop", "hearth")));
		assertFalse(MapPropCatalog.isLightEmitting(Map.of("prop", "hearth", "state", "EXTINGUISHED")));

		Map<String, Object> catalog = MapPropCatalog.catalog();
		assertTrue(((List<?>) catalog.get("materials")).contains("WOOD"));
		assertTrue(((List<?>) catalog.get("materials")).contains("COBBLESTONE"));
		assertNotNull(catalog.get("props"));
		assertNotNull(catalog.get("light_presets"));
	}

	@Test
	void tavernCryptAndRoadsideScenesRenderDistinctlyAndStayDeterministic() throws Exception {
		var renderer = new MapRenderer();
		Path outDir = Path.of("target", "visual-previews");
		Files.createDirectories(outDir);

		// 1. Cozy evening tavern
		var tavern = new LinkedHashMap<String, Object>();
		tavern.put("name", "The Broken Pick Taproom");
		tavern.put("kind", "SCENE");
		tavern.put("illumination", "DIM");
		tavern.put("bounds_ft", List.of(0, 0, 40, 30));
		tavern.put("rooms",
				List.of(Map.of("key", "taproom", "name", "Taproom", "rect_ft", List.of(0, 0, 30, 30), "material",
						"WOOD", "illumination", "DIM"),
						Map.of("key", "cellar-entry", "name", "Back Room", "rect_ft", List.of(30, 0, 10, 30),
								"material", "STONE", "illumination", "DIM")));
		tavern.put("walls",
				List.of(Map.of("from_ft", List.of(0, 0), "to_ft", List.of(12, 0)),
						Map.of("from_ft", List.of(17, 0), "to_ft", List.of(40, 0)),
						Map.of("from_ft", List.of(0, 30), "to_ft", List.of(40, 30)),
						Map.of("from_ft", List.of(0, 0), "to_ft", List.of(0, 30)),
						Map.of("from_ft", List.of(40, 0), "to_ft", List.of(40, 30)),
						Map.of("from_ft", List.of(30, 0), "to_ft", List.of(30, 10)),
						Map.of("from_ft", List.of(30, 15), "to_ft", List.of(30, 30))));
		tavern.put("doors",
				List.of(Map.of("key", "front-door", "kind", "DOOR", "from_ft", List.of(12, 0), "to_ft", List.of(17, 0),
						"state", "CLOSED"),
						Map.of("key", "back-door", "kind", "DOOR", "from_ft", List.of(30, 10), "to_ft", List.of(30, 15),
								"state", "OPEN")));
		tavern.put("portals", List.of(Map.of("key", "stairs", "kind", "STAIRS", "from_level", 0, "to_level", -1,
				"from_position_ft", List.of(35, 25), "to_position_ft", List.of(35, 25))));
		tavern.put("objects", List.of(
				Map.of("key", "bar", "prop", "bar_counter", "x_ft", 2, "y_ft", 24, "width_ft", 16, "height_ft", 3.5),
				Map.of("key", "hearth", "prop", "hearth", "position_ft", List.of(24, 28), "width_ft", 7, "height_ft",
						3),
				Map.of("key", "round-1", "prop", "round wooden table", "position_ft", List.of(8, 12), "seats", 4,
						"clutter", true),
				Map.of("key", "round-2", "prop", "round wooden table", "position_ft", List.of(22, 16), "seats", 4,
						"clutter", true),
				Map.of("key", "banquet", "prop", "rectangular wooden table", "position_ft", List.of(18, 7), "width_ft",
						8, "height_ft", 3.2, "seating", "benches", "clutter", true),
				Map.of("key", "chest", "prop", "chest", "position_ft", List.of(36, 5), "state", "LOCKED"),
				Map.of("key", "barrel-1", "prop", "barrel", "position_ft", List.of(33, 5)),
				Map.of("key", "barrel-2", "prop", "barrel", "position_ft", List.of(33, 8)),
				Map.of("key", "crate-1", "prop", "crate", "position_ft", List.of(36, 18))));
		tavern.put("decor", List.of(
				Map.of("key", "hearth-rug", "prop", "rug", "position_ft", List.of(24, 22), "width_ft", 9, "height_ft",
						5, "tint", "CRIMSON"),
				Map.of("key", "stool-1", "prop", "stool", "position_ft", List.of(5, 22)),
				Map.of("key", "stool-2", "prop", "stool", "position_ft", List.of(9, 22)),
				Map.of("key", "stool-3", "prop", "stool", "position_ft", List.of(13, 22)),
				Map.of("key", "stool-4", "prop", "stool", "position_ft", List.of(17, 22)),
				Map.of("key", "sconce-1", "prop", "torch_sconce", "position_ft", List.of(1.5, 10)),
				Map.of("key", "sconce-2", "prop", "lantern", "position_ft", List.of(35, 14))));
		tavern.put("actors", List.of(
				Map.of("name", "Tam", "label", "1", "side", "PARTY", "party_member", true, "position_ft",
						List.of(12.5, 2.5)),
				Map.of("name", "Mira", "label", "2", "side", "PARTY", "party_member", true, "position_ft",
						List.of(17.5, 2.5)),
				Map.of("name", "Brawler", "label", "3", "side", "HOSTILE", "position_ft", List.of(22.5, 22.5))));
		var tavernRender = renderer.render(tavern, Map.of("max_pixels", 960));
		assertEquals("raster-7", tavernRender.transform().get("renderer_version"));
		assertArrayEquals(tavernRender.png(), renderer.render(tavern, Map.of("max_pixels", 960)).png());
		Files.write(outDir.resolve("tavern-preview.png"), tavernRender.png());

		// 2. Damp Dungeon / Crypt with wall-occluded brazier firelight
		var crypt = new LinkedHashMap<String, Object>();
		crypt.put("name", "Sunken Shrine");
		crypt.put("kind", "DUNGEON");
		crypt.put("illumination", "DARK");
		crypt.put("bounds_ft", List.of(0, 0, 45, 30));
		crypt.put("rooms",
				List.of(Map.of("key", "hall", "name", "Entry Hall", "rect_ft", List.of(0, 0, 20, 30), "material",
						"DAMP_STONE", "illumination", "DIM"),
						Map.of("key", "sanctum", "name", "Altar Sanctum", "rect_ft", List.of(20, 0, 25, 30), "material",
								"DECORATIVE_TILE", "illumination", "DIM")));
		crypt.put("walls",
				List.of(Map.of("from_ft", List.of(0, 0), "to_ft", List.of(45, 0)),
						Map.of("from_ft", List.of(0, 30), "to_ft", List.of(45, 30)),
						Map.of("from_ft", List.of(0, 0), "to_ft", List.of(0, 30)),
						Map.of("from_ft", List.of(45, 0), "to_ft", List.of(45, 30)),
						Map.of("from_ft", List.of(20, 0), "to_ft", List.of(20, 12)),
						Map.of("from_ft", List.of(20, 18), "to_ft", List.of(20, 30))));
		crypt.put("doors", List.of(Map.of("key", "arch", "kind", "DOOR", "from_ft", List.of(20, 12), "to_ft",
				List.of(20, 18), "state", "OPEN")));
		crypt.put("objects",
				List.of(Map.of("key", "altar", "prop", "altar", "position_ft", List.of(39, 15), "rotation_deg", 90,
						"tint", "PURPLE"),
						Map.of("key", "brazier-1", "prop", "brazier", "position_ft", List.of(33, 22)),
						Map.of("key", "brazier-2", "prop", "brazier", "position_ft", List.of(33, 8)),
						Map.of("key", "pillar-1", "prop", "pillar_round", "position_ft", List.of(26, 23)),
						Map.of("key", "pillar-2", "prop", "pillar_round", "position_ft", List.of(26, 7)),
						Map.of("key", "sarc", "prop", "sarcophagus", "position_ft", List.of(10, 24), "rotation_deg",
								90),
						Map.of("key", "fountain", "prop", "fountain", "position_ft", List.of(10, 10)),
						Map.of("key", "rubble", "prop", "rubble", "position_ft", List.of(4, 24))));
		crypt.put("decor",
				List.of(Map.of("key", "runner", "prop", "carpet_runner", "position_ft", List.of(30, 15), "width_ft", 14,
						"height_ft", 4, "tint", "PURPLE"),
						Map.of("key", "puddle", "prop", "puddle", "position_ft", List.of(15, 6)),
						Map.of("key", "cracks", "prop", "cracks", "position_ft", List.of(6, 16)),
						Map.of("key", "scorch", "prop", "scorch_mark", "position_ft", List.of(16, 15))));
		crypt.put("lights", List.of(Map.of("key", "arcane-altar", "position_ft", List.of(39, 15), "preset", "ARCANE",
				"radius_ft", 14, "intensity", 0.7)));
		crypt.put("actors", List.of(
				Map.of("name", "Kael", "label", "1", "side", "PARTY", "party_member", true, "position_ft",
						List.of(12.5, 15.0)),
				Map.of("name", "Cultist", "label", "2", "side", "HOSTILE", "position_ft", List.of(35.0, 15.0))));
		var cryptRender = renderer.render(crypt, Map.of("max_pixels", 960));
		Files.write(outDir.resolve("crypt-preview.png"), cryptRender.png());

		// 3. Roadside wilderness ambush
		var road = new LinkedHashMap<String, Object>();
		road.put("name", "Thornwood Road Ambush");
		road.put("kind", "SCENE");
		road.put("bounds_ft", List.of(0, 0, 80, 80));
		road.put("rooms", List.of(Map.of("key", "meadow", "rect_ft", List.of(0, 0, 80, 80), "material", "GRASS")));
		road.put("features", List.of(Map.of("kind", "FOREST", "material", "FOREST", "polygon_ft",
				List.of(List.of(0, 48), List.of(45, 58), List.of(80, 74), List.of(80, 80), List.of(0, 80)))));
		road.put("roads", List.of(Map.of("kind", "ROAD", "path_ft",
				List.of(List.of(0, 22), List.of(35, 36), List.of(80, 58)), "width_ft", 12)));
		road.put("objects",
				List.of(Map.of("key", "wagon", "prop", "wagon", "position_ft", List.of(38, 37), "rotation_deg", -24),
						Map.of("key", "boulder-cover", "prop", "boulder", "position_ft", List.of(52, 26), "width_ft",
								5.5, "height_ft", 5.0),
						Map.of("key", "camp", "prop", "campfire", "position_ft", List.of(62, 18)), Map.of("key", "tent",
								"prop", "tent", "position_ft", List.of(70, 14), "rotation_deg", 25, "tint", "GREEN")));
		road.put("decor", List.of(
				Map.of("key", "t1", "prop", "tree", "position_ft", List.of(12, 62), "width_ft", 11, "height_ft", 11),
				Map.of("key", "t2", "prop", "pine_tree", "position_ft", List.of(26, 68), "width_ft", 9, "height_ft", 9),
				Map.of("key", "t3", "prop", "tree", "position_ft", List.of(44, 70), "width_ft", 10, "height_ft", 10),
				Map.of("key", "t4", "prop", "pine_tree", "position_ft", List.of(66, 74), "width_ft", 8, "height_ft", 8),
				Map.of("key", "t5", "prop", "tree", "position_ft", List.of(14, 10), "width_ft", 9, "height_ft", 9),
				Map.of("key", "b1", "prop", "bush", "position_ft", List.of(22, 54)),
				Map.of("key", "b2", "prop", "bush", "position_ft", List.of(58, 30)),
				Map.of("key", "stump", "prop", "stump", "position_ft", List.of(28, 18))));
		road.put("actors",
				List.of(Map.of("name", "Tam", "label", "1", "side", "PARTY", "party_member", true, "position_ft",
						List.of(27.5, 32.5)),
						Map.of("name", "Mira", "label", "2", "side", "PARTY", "party_member", true, "position_ft",
								List.of(22.5, 27.5)),
						Map.of("name", "Bandit", "label", "3", "side", "HOSTILE", "position_ft", List.of(52.5, 32.5)),
						Map.of("name", "Scout", "label", "4", "side", "HOSTILE", "position_ft", List.of(22.5, 57.5))));
		var roadRender = renderer.render(road, Map.of("max_pixels", 960));
		Files.write(outDir.resolve("roadside-preview.png"), roadRender.png());

		assertTrue(tavernRender.png().length > 2000);
		assertTrue(cryptRender.png().length > 2000);
		assertTrue(roadRender.png().length > 2000);
	}

	@Test
	void graphicsPassGuidanceDraftPreviewFogPrivacyAndPostPublishDressing() throws Exception {
		try (Engine engine = engine(tempDb("tactical-visuals"))) {
			String campaign = committedCampaign(engine);
			engine.sessions().bootstrap(op(), campaign, null);
			String opening = engine.db().read(tx -> "location:" + tx.get("campaign", 1).lng("current_location_id"));

			// Bare realized SCENE preparation surfaces advisory visual dressing gaps + dressing_brief
			Map<String, Object> draft = engine.maps().begin(op(), campaign, "SCENE",
					Map.of("name", "Inn Common Room", "location_ref", opening, "room_count", 2, "geometry",
							Map.of("bounds_ft", List.of(0, 0, 40, 20)), "rooms",
							List.of(Map.of("key", "taproom", "name", "Taproom", "rect_ft", List.of(0, 0, 20, 20)),
									Map.of("key", "backroom", "name", "Back Room", "rect_ft", List.of(20, 0, 20, 20))),
							"connections", List.of(Map.of("key", "inner-door", "from", "taproom", "to", "backroom",
									"kind", "DOOR", "state", "CLOSED"))));
			assertEquals(true, draft.get("ready"));
			assertNotNull(draft.get("dressing_brief"));
			String gaps = draft.get("optional_gaps").toString();
			assertTrue(gaps.contains("Visual dressing pass recommended"), gaps);
			assertTrue(gaps.contains("default STONE"), gaps);
			assertTrue(gaps.contains("mood light"), gaps);
			String prepRef = draft.get("preparation").toString();

			// Draft preview works in GM mode before publishing
			Map<String, Object> preview = engine.maps().previewDocument(campaign, prepRef, 0);
			Map<String, Object> previewView = SceneService.map(preview.get("view"));
			assertEquals("GM", previewView.get("audience"));
			assertEquals(true, previewView.get("draft_preview"));
			assertFalse(SceneService.maps(previewView.get("walls")).isEmpty());
			assertTrue(engine.mapRenderer().render(previewView, Map.of("max_pixels", 400)).png().length > 500);

			// Final graphics & mood pass clears the advisory visual gaps
			Map<String, Object> dressed = engine.maps().revise(op(), campaign, prepRef, 0L, Map.of("illumination",
					"DIM", "rooms",
					List.of(Map.of("key", "taproom", "material", "WOOD"),
							Map.of("key", "backroom", "material", "ROUGH_STONE")),
					"objects",
					List.of(Map.of("key", "table-1", "name", "Oak Table", "prop", "table_round", "seats", 4, "room",
							"taproom", "position_ft", List.of(10, 10))),
					"decor",
					List.of(Map.of("key", "rug-1", "prop", "rug", "room", "taproom", "position_ft", List.of(10, 10)),
							Map.of("key", "hidden-rug", "prop", "rug", "room", "backroom", "position_ft",
									List.of(30, 10))),
					"lights",
					List.of(Map.of("key", "hearth-glow", "preset", "HEARTH", "room", "taproom", "position_ft",
							List.of(5, 15), "radius_ft", 16),
							Map.of("key", "secret-lantern", "preset", "LANTERN", "room", "backroom", "position_ft",
									List.of(30, 10), "radius_ft", 12))));
			String dressedGaps = dressed.get("optional_gaps").toString();
			assertFalse(dressedGaps.contains("Visual dressing pass recommended"), dressedGaps);
			assertFalse(dressedGaps.contains("default STONE"), dressedGaps);
			assertFalse(dressedGaps.contains("mood light"), dressedGaps);

			Map<String, Object> published = engine.maps().publish(op(), campaign, prepRef, 1L);
			String mapRef = published.get("map_ref").toString();
			String hero = engine.db().read(tx -> "character:" + tx
					.queryOne("SELECT character_id FROM player_control_assignment WHERE campaign_id=? AND active=1", 1L)
					.orElseThrow().lng("character_id"));
			engine.scenes().place(op(), campaign, mapRef,
					List.of(Map.of("character", hero, "x_ft", 5, "y_ft", 5, "level", 0)));

			// PLAYER projection exposes taproom decor/lights, hides backroom decor/lights behind CLOSED door
			Map<String, Object> playerView = SceneService
					.map(engine.scenes().context(campaign, mapRef, "PLAYER", 0).get("view"));
			List<Map<String, Object>> playerDecor = SceneService.maps(playerView.get("decor"));
			List<Map<String, Object>> playerLights = SceneService.maps(playerView.get("lights"));
			assertTrue(playerDecor.stream().anyMatch(d -> "rug-1".equals(d.get("key"))));
			assertFalse(playerDecor.stream().anyMatch(d -> "hidden-rug".equals(d.get("key"))),
					"Unexplored room decor must not leak to PLAYER views.");
			assertTrue(playerLights.stream().anyMatch(l -> "hearth-glow".equals(l.get("key"))));
			assertFalse(playerLights.stream().anyMatch(l -> "secret-lantern".equals(l.get("key"))),
					"Unexplored room lights must not leak to PLAYER views.");

			// Post-publish visual dressing via correct_map_geometry updates room material, decor and lights
			long rev = ((Number) engine.scenes().context(campaign, mapRef, "GM", 0).get("map_revision")).longValue();
			Map<String, Object> patched = engine.maps().correctGeometry(op(), campaign, mapRef, rev,
					Map.of("rooms", List.of(Map.of("key", "taproom", "material", "DARK_WOOD")), "decor",
							List.of(Map.of("key", "rug-1", "prop", "rug", "position_ft", List.of(10, 10), "tint",
									"BLUE"),
									Map.of("key", "chair-extra", "prop", "chair", "position_ft", List.of(4, 4))),
							"lights",
							List.of(Map.of("key", "hearth-glow", "position_ft", List.of(5, 15), "preset", "TORCH",
									"radius_ft", 14))),
					"Post-publish graphics pass adding dark timber and extra chair.");
			assertEquals(true, patched.get("corrected"));
			Map<String, Object> gmAfter = SceneService
					.map(engine.scenes().context(campaign, mapRef, "GM", 0).get("view"));
			assertEquals("DARK_WOOD", SceneService.maps(gmAfter.get("rooms")).getFirst().get("material"));
			assertEquals(2, SceneService.maps(gmAfter.get("decor")).size());

			// BLUEPRINT without geometry refuses draft preview cleanly
			Map<String, Object> bp = engine.maps().begin(op(), campaign, "DUNGEON",
					Map.of("name", "Untiled Vault", "room_count", 4, "levels", 1));
			assertThrows(RpgException.class,
					() -> engine.maps().previewDocument(campaign, bp.get("preparation").toString(), 0));

			// MCP MapTools draft preview requires GM audience and renders preparation PNG
			MapTools mapTools = new MapTools();
			mapTools.engine = engine;
			var deniedPlayerPreview = mapTools.render(campaign, prepRef, java.util.Optional.of("PLAYER"),
					java.util.Optional.of(0), java.util.Optional.empty(), java.util.Optional.of(false));
			assertTrue(deniedPlayerPreview.isError());
			var allowedGmPreview = mapTools.render(campaign, prepRef, java.util.Optional.of("GM"),
					java.util.Optional.of(0), java.util.Optional.of(Map.of("max_pixels", 320)),
					java.util.Optional.of(true));
			assertFalse(allowedGmPreview.isError());
		}
	}

	@Test
	void partiallyObservedPropClipsStrictlyToObservedRects() {
		var scene = Map.<String, Object> of("bounds_ft", List.of(0, 0, 20, 20), "rooms",
				List.of(Map.of("rect_ft", List.of(0, 0, 20, 20), "material", "WOOD")), "objects",
				List.of(Map.of("key", "bar", "prop", "bar_counter", "extent_known", false, "observed_rects_ft",
						List.of(List.of(0, 10, 5, 5), List.of(5, 10, 5, 5)))));
		var bare = Map.<String, Object> of("bounds_ft", List.of(0, 0, 20, 20), "rooms",
				List.of(Map.of("rect_ft", List.of(0, 0, 20, 20), "material", "WOOD")));
		var opts = Map.<String, Object> of("max_pixels", 201, "show_grid", false, "show_labels", false);
		var renderer = new MapRenderer();
		var withBar = se.hirt.mcp.rpg.maps.PngCodec.decode(renderer.render(scene, opts).png());
		var withoutBar = se.hirt.mcp.rpg.maps.PngCodec.decode(renderer.render(bare, opts).png());
		// Inside observed rect (5, 12.5) -> px (50, 75) differs from bare floor
		assertNotEquals(withoutBar.pixels()[75 * withoutBar.width() + 50], withBar.pixels()[75 * withBar.width() + 50]);
		// Outside observed rect (15, 12.5) -> px (150, 75) remains identical to bare floor
		assertEquals(withoutBar.pixels()[75 * withoutBar.width() + 150], withBar.pixels()[75 * withBar.width() + 150]);
	}
}
