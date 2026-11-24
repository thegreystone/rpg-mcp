/* Copyright (C) 2026 Marcus Hirt. SPDX-License-Identifier: BSD-3-Clause */
package se.hirt.mcp.rpg;

import org.junit.jupiter.api.Test;
import se.hirt.mcp.rpg.maps.SceneService;
import se.hirt.mcp.rpg.persistence.Database;
import se.hirt.mcp.rpg.protocol.Json;
import se.hirt.mcp.rpg.protocol.Ref;
import se.hirt.mcp.rpg.spatial.Geometry.Footprint;
import se.hirt.mcp.rpg.spatial.SpatialRules;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static se.hirt.mcp.rpg.TestCampaigns.*;

class Run5PerceptionTest {

	@Test
	void adjacentObservedCellsPresentOnePassageWithoutChangingItsPhysicalGeometry() throws Exception {
		try (Engine e = engine(tempDb("run5-passage-fragments"))) {
			String campaign = ready(e);
			String scene = publish(e, campaign, reviewMine(opening(e, campaign)));
			e.scenes().place(op(), campaign, scene,
					List.of(map("character", "character:1", "x_ft", 70, "y_ft", 0, "level", 0)));
			long cid = Ref.id(campaign, Ref.CAMPAIGN);
			var before = e.db().snapshot(cid);
			Map<String, Object> gm = view(e, campaign, scene, "GM", 0);
			Map<String, Object> player = view(e, campaign, scene, "PLAYER", 0);
			var passage = SceneService.maps(player.get("doors")).stream()
					.filter(door -> "drift-to-breach".equals(door.get("key"))).toList();
			assertEquals(1, passage.size());
			assertEquals(List.of(80.0, 0.0), passage.getFirst().get("from_ft"));
			assertEquals(List.of(80.0, 10.0), passage.getFirst().get("to_ft"));
			assertEquals("OPEN", passage.getFirst().get("state"));
			assertEquals(gm, view(e, campaign, scene, "GM", 0));
			assertEquals(before, e.db().snapshot(cid), "Projection must not rewrite geometry or remembered cells.");
		}
	}

	@Test
	void rememberedIntervalsMergeOnlyAcrossObservedCollinearSameStatePieces() throws Exception {
		try (Engine e = engine(tempDb("run5-passage-unseen-gap"))) {
			String campaign = ready(e), site = opening(e, campaign);
			String scene = publish(e, campaign,
					map("name", "A partially surveyed arch", "stage", "SCENE", "location_ref", site, "room_count", 2,
							"illumination", "BRIGHT", "geometry", map("bounds_ft", List.of(0, 0, 40, 40)), "rooms",
							List.of(map("key", "west", "rect_ft", List.of(0, 0, 20, 40)),
									map("key", "east", "rect_ft", List.of(20, 0, 20, 40))),
							"connections", List.of(map("key", "arch", "kind", "PASSAGE", "state", "OPEN", "from",
									"west", "to", "east", "from_ft", List.of(20, 0), "to_ft", List.of(20, 40)))));
			var fragments = List.of(door("arch", "OPEN", 20, 0, 20, 5), door("arch", "OPEN", 20, 10, 20, 5),
					door("arch", "OPEN", 20, 7, 20, 12), door("arch", "OPEN", 20, 20, 20, 25),
					door("arch", "CLOSED", 20, 12, 20, 15), door("other-arch", "OPEN", 20, 12, 20, 20),
					door("arch", "OPEN", 20, 30, 22.5, 32.5), door("arch", "OPEN", 25, 35, 22.5, 32.5));
			long cid = Ref.id(campaign, Ref.CAMPAIGN), sid = Ref.id(scene, Ref.MAP);
			e.db().mutate(Database.Mutation.of("fixture_saved_observations", cid, op(), "SYSTEM", Map.of()), tx -> {
				var cells = new LinkedHashMap<String, Object>();
				for (int i = 0; i < fragments.size(); i++)
					cells.put("0:4:" + i, map("rect_ft", List.of(20, i * 5, 5, 5), "level", 0, "observed_doors",
							List.of(fragments.get(i))));
				var knowledge = map("cells", cells, "objects", Map.of());
				var existing = tx.queryOne("SELECT * FROM map_observation WHERE scene_id=? AND observer_key='party'",
						sid);
				if (existing.isPresent())
					tx.update("map_observation", existing.get().id(), map("knowledge_json", knowledge));
				else
					tx.insert("map_observation", map("campaign_id", cid, "scene_id", sid, "observer_key", "party",
							"knowledge_json", knowledge));
				return Map.of();
			});
			var before = e.db().snapshot(cid);
			var playerDoors = SceneService.maps(view(e, campaign, scene, "PLAYER", 0).get("doors"));
			var open = playerDoors.stream().filter(d -> "arch".equals(d.get("key")) && "OPEN".equals(d.get("state")))
					.toList();
			assertEquals(3, open.size(),
					"A gap, a different state, and a bend must not become one unknown-length opening.");
			assertTrue(open.stream().anyMatch(d -> segmentIs(d, 20, 0, 20, 12)));
			assertTrue(open.stream().anyMatch(d -> segmentIs(d, 20, 20, 20, 25)));
			assertTrue(open.stream().anyMatch(d -> segmentIs(d, 20, 30, 25, 35)));
			assertTrue(playerDoors.stream()
					.anyMatch(d -> "CLOSED".equals(d.get("state")) && segmentIs(d, 20, 12, 20, 15)));
			assertTrue(playerDoors.stream()
					.anyMatch(d -> "other-arch".equals(d.get("key")) && segmentIs(d, 20, 12, 20, 20)));
			assertFalse(playerDoors.stream().anyMatch(d -> segmentIs(d, 20, 0, 20, 40)),
					"The canonical door endpoint must never fill a gap in player knowledge.");
			assertEquals(before, e.db().snapshot(cid));
		}
	}

	@Test
	void theReviewedStairFootSeesTheGongCornerButNeitherSentryThroughTheNarrowOpening() throws Exception {
		try (Engine e = engine(tempDb("run5-exact-gong-sight"))) {
			String campaign = ready(e), scene = publish(e, campaign, reviewMine(opening(e, campaign)));
			e.scenes().place(op(), campaign, scene,
					List.of(map("character", "character:1", "x_ft", 10, "y_ft", 5, "level", -1)));
			long cid = Ref.id(campaign, Ref.CAMPAIGN);
			var evidence = e.scenes().perception(campaign, scene, "character:1");
			assertTrue(SceneService.maps(evidence.get("currently_seen")).isEmpty());
			var player = view(e, campaign, scene, "PLAYER", -1);
			assertTrue(SceneService.maps(player.get("objects")).stream()
					.anyMatch(object -> "Bronze gong".equals(object.get("name"))
							&& !Boolean.TRUE.equals(object.get("last_known"))));
			assertEquals(List.of("character:1"),
					SceneService.maps(player.get("actors")).stream().map(actor -> actor.get("character_ref")).toList());
			e.db().read(tx -> {
				var physical = SceneService.geometry(SceneService.document(tx, SceneService.scene(tx, cid, scene)), -1);
				var observer = new Footprint(SceneService.point(10, 5), 1, 1);
				var gong = new Footprint(SceneService.point(35, 25), 1, 1);
				assertNotEquals(SpatialRules.Cover.TOTAL, SpatialRules.cover(physical, observer, gong).cover());
				for (var sentry : List.of(new Footprint(SceneService.point(25, 20), 1, 1),
						new Footprint(SceneService.point(30, 25), 1, 1))) {
					assertEquals(SpatialRules.Cover.TOTAL, SpatialRules.cover(physical, observer, sentry).cover());
				}
				// This ray crosses y=15 at x=27.313, inside the opening; both sentry footprints miss it.
				assertTrue(SpatialRules.lineOfSight(physical, SceneService.point(14 + 11d / 12, 5 + 1d / 12),
						SceneService.point(39 + 11d / 12, 25 + 1d / 12)));
				return null;
			});
		}
	}

	private static Map<String, Object> door(String key, String state, double x1, double y1, double x2, double y2) {
		return map("key", key, "kind", "PASSAGE", "level", 0, "state", state, "observed", true, "from_ft",
				List.of(x1, y1), "to_ft", List.of(x2, y2));
	}

	private static boolean segmentIs(Map<String, Object> door, double x1, double y1, double x2, double y2) {
		return List.of(x1, y1).equals(door.get("from_ft")) && List.of(x2, y2).equals(door.get("to_ft"));
	}

	/**
	 * Run5 dpub2.json proposal: exact physical rooms, passages, objects and actor placements; plot
	 * rewards omitted.
	 */
	private static Map<String, Object> reviewMine(String site) {
		var proposal = Json.readMap(
				"""
						{
						  "name": "The Ashcombe gallery and the Ironhand Reliquary (lit)",
						  "stage": "SCENE",
						  "room_count": 9,
						  "levels": [0,-1],
						  "geometry": {"bounds_ft":[0,0,120,70]},
						  "rooms": [
						    {"key":"winch-house","name":"Winch house","level":0,"role":"ENTRY","rect_ft":[0,0,20,20],"material":"WOOD"},
						    {"key":"main-drift","name":"Main drift","level":0,"rect_ft":[20,0,60,10],"material":"ROUGH_STONE"},
						    {"key":"diggers-gallery","name":"Diggers' gallery","level":0,"rect_ft":[30,10,30,25],"material":"ROUGH_STONE"},
						    {"key":"breach","name":"The breach","level":0,"rect_ft":[80,0,20,20],"material":"ROUGH_STONE"},
						    {"key":"vault-road","name":"Vault road","level":-1,"rect_ft":[0,0,80,15],"material":"STONE"},
						    {"key":"sentry-post","name":"Sentry post","level":-1,"rect_ft":[20,15,20,15],"material":"STONE"},
						    {"key":"skarr-hall","name":"Skarr's hall","level":-1,"rect_ft":[40,15,40,35],"material":"DECORATIVE_TILE"},
						    {"key":"ossuary","name":"Ironhand ossuary","level":-1,"role":"ALTAR","rect_ft":[80,0,40,30],"material":"DAMP_STONE"},
						    {"key":"mattock-shrine","name":"Mattock shrine","level":-1,"secret":true,"rect_ft":[80,30,20,15],"material":"DECORATIVE_TILE"}
						  ],
						  "connections": [
						    {"key":"shaft-door","from":"winch-house","to":"main-drift","kind":"DOOR","state":"OPEN","from_ft":[20,5],"to_ft":[20,10]},
						    {"key":"drift-to-gallery","from":"main-drift","to":"diggers-gallery","kind":"PASSAGE","state":"OPEN","from_ft":[40,10],"to_ft":[45,10]},
						    {"key":"drift-to-breach","from":"main-drift","to":"breach","kind":"PASSAGE","state":"OPEN","from_ft":[80,0],"to_ft":[80,10]},
						    {"key":"dwarven-stair","from":"breach","to":"vault-road","kind":"STAIRS","state":"OPEN","from_position_ft":[95,15],"to_position_ft":[10,5]},
						    {"key":"road-to-post","from":"vault-road","to":"sentry-post","kind":"PASSAGE","state":"OPEN","from_ft":[25,15],"to_ft":[30,15]},
						    {"key":"post-to-hall","from":"sentry-post","to":"skarr-hall","kind":"DOOR","state":"CLOSED","from_ft":[40,20],"to_ft":[40,25]},
						    {"key":"road-to-ossuary","from":"vault-road","to":"ossuary","kind":"DOOR","state":"LOCKED","from_ft":[80,5],"to_ft":[80,10]},
						    {"key":"secret-shrine","from":"ossuary","to":"mattock-shrine","kind":"DOOR","secret":true,"state":"SECRET","from_ft":[85,30],"to_ft":[90,30]},
						    {"key":"walled-arch","from":"skarr-hall","to":"ossuary","kind":"PASSAGE","state":"BLOCKED","from_ft":[80,20],"to_ft":[80,25]},
						    {"key":"ore-chute","from":"diggers-gallery","to":"main-drift","kind":"PORTAL","state":"OPEN","from_position_ft":[55,30],"to_position_ft":[70,5]}
						  ],
						  "objects": [
						    {"key":"winch-drum","room":"winch-house","name":"Winch drum","x_ft":10,"y_ft":10,"width_ft":5,"height_ft":5,"blocks_movement":true},
						    {"key":"ore-cart","room":"main-drift","name":"Ore cart","x_ft":50,"y_ft":5,"width_ft":5,"height_ft":5,"blocks_movement":true,"cover":"HALF"},
						    {"key":"gong","room":"sentry-post","name":"Bronze gong","x_ft":35,"y_ft":25,"width_ft":5,"height_ft":5,"blocks_movement":true},
						    {"key":"brazier","room":"skarr-hall","name":"Iron brazier","x_ft":60,"y_ft":30,"width_ft":5,"height_ft":5,"blocks_movement":true},
						    {"key":"altar","room":"ossuary","name":"Altar stone","x_ft":100,"y_ft":15,"width_ft":10,"height_ft":5,"blocks_movement":true},
						    {"key":"silver-chest","room":"skarr-hall","name":"Iron-strapped chest","item_ref":"Chest","x_ft":75,"y_ft":45,"locked":true}
						  ],
						  "actors": [
						    {"key":"colm","room":"diggers-gallery","name":"Colm Ashcombe","creature_ref":"Commoner","x_ft":45,"y_ft":30},
						    {"key":"ned","room":"diggers-gallery","name":"Ned Pike","creature_ref":"Commoner","x_ft":50,"y_ft":30},
						    {"key":"gallery-guard-1","room":"diggers-gallery","name":"Goblin overseer","creature_ref":"Goblin Warrior","x_ft":40,"y_ft":20},
						    {"key":"gallery-guard-2","room":"diggers-gallery","name":"Goblin whip-hand","creature_ref":"Goblin Warrior","x_ft":55,"y_ft":20},
						    {"key":"sentry-1","room":"sentry-post","name":"Goblin sentry","creature_ref":"Goblin Warrior","x_ft":25,"y_ft":20},
						    {"key":"sentry-2","room":"sentry-post","name":"Goblin gong-keeper","creature_ref":"Goblin Warrior","x_ft":30,"y_ft":25},
						    {"key":"skarr","room":"skarr-hall","name":"Captain Skarr","creature_ref":"Hobgoblin Captain","x_ft":60,"y_ft":40},
						    {"key":"skarr-guard","room":"skarr-hall","name":"Goblin bodyguard","creature_ref":"Goblin Warrior","x_ft":55,"y_ft":35},
						    {"key":"ghoul-1","room":"ossuary","name":"Ghoul","creature_ref":"Ghoul","x_ft":95,"y_ft":20},
						    {"key":"ghoul-2","room":"ossuary","name":"Ghoul","creature_ref":"Ghoul","x_ft":110,"y_ft":10}
						  ],
						  "illumination": "DIM"
						}
						""");
		proposal.put("location_ref", site);
		return proposal;
	}

	private static String ready(Engine e) {
		String c = committedCampaign(e);
		e.sessions().bootstrap(op(), c, null);
		return c;
	}

	private static String opening(Engine e, String campaign) {
		return e.db().read(tx -> Ref.of(Ref.LOCATION,
				tx.get("campaign", Ref.id(campaign, Ref.CAMPAIGN)).lng("current_location_id")));
	}

	private static String publish(Engine e, String campaign, Map<String, Object> proposal) {
		var draft = e.maps().begin(op(), campaign, "DUNGEON", proposal);
		var published = e.maps().publish(op(), campaign, draft.get("preparation").toString(), null);
		assertNotNull(published.get("map_ref"), () -> "Expected a physical map publication: " + Json.write(published));
		return published.get("map_ref").toString();
	}

	private static Map<String, Object> view(Engine e, String campaign, String scene, String audience, int level) {
		return SceneService.map(e.scenes().context(campaign, scene, audience, level).get("view"));
	}
}
