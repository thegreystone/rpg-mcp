package se.hirt.mcp.rpg;

import org.junit.jupiter.api.Test;
import se.hirt.mcp.rpg.maps.AtlasCoordinates;
import se.hirt.mcp.rpg.maps.MapAuthoringService;
import se.hirt.mcp.rpg.protocol.ErrorCode;
import se.hirt.mcp.rpg.protocol.Ref;
import se.hirt.mcp.rpg.protocol.RpgException;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static se.hirt.mcp.rpg.TestCampaigns.map;
import static se.hirt.mcp.rpg.TestCampaigns.op;

class MapAuthoringTest {

	@Test
	void blueprintRealizationPreservesCommitmentsAndInstantiatesSourcesOnce() throws Exception {
		Path db = TestCampaigns.tempDb("map-blueprint");
		String campaign, preparation, mapRef, altarRef, rewardRef;
		Map<String, Object> savedDocument;
		try (Engine engine = TestCampaigns.engine(db)) {
			campaign = TestCampaigns.committedCampaign(engine);
			engine.sessions().bootstrap(op(), campaign, null);
			MapAuthoringService maps = new MapAuthoringService(engine.db(), engine.rules());
			Map<String, Object> draft = maps.begin(op(), campaign, "DUNGEON", blueprint());
			preparation = (String) draft.get("preparation");
			assertEquals(true, draft.get("ready"));
			long beforeItems = count(engine, "inventory_entry", campaign);
			Map<String, Object> accepted = maps.publish(op(), campaign, preparation, revision(draft));
			assertEquals("ACCEPTED", accepted.get("status"));
			assertFalse(accepted.containsKey("map_ref"));
			assertEquals(beforeItems, count(engine, "inventory_entry", campaign));
			assertEquals(0, count(engine, "map_actor", campaign));
			altarRef = (String) m(accepted.get("references")).get("room:altar");
			rewardRef = (String) m(accepted.get("references")).get("reward:staff");
			Map<String, Object> realized = maps.revise(op(), campaign, preparation, revision(accepted),
					map("generate_layout", true, "rewards", List.of(map("key", "staff", "item_ref", "Quarterstaff")),
							"actors", List.of(map("key", "guard", "room", "altar", "creature_ref", "Bandit"))));
			assertEquals(false, realized.get("ready"), "A fitting helper cannot invent the missing room graph.");
			assertEquals(1, list(m(realized.get("proposal")).get("connections")).size());
			realized = authorMissingConnections(maps, campaign, preparation, realized);
			assertEquals(true, realized.get("ready"), realized.get("violations").toString());
			assertEquals(16, list(m(realized.get("proposal")).get("rooms")).size());
			String publishOp = op();
			Map<String, Object> published = maps.publish(publishOp, campaign, preparation, revision(realized));
			mapRef = (String) published.get("map_ref");
			assertEquals(altarRef, m(published.get("references")).get("room:altar"));
			assertEquals(rewardRef, m(published.get("references")).get("reward:staff"));
			assertEquals(beforeItems + 1, count(engine, "inventory_entry", campaign));
			assertEquals(1, count(engine, "map_actor", campaign));
			Map<String, Object> replay = maps.publish(publishOp, campaign, preparation, revision(realized));
			assertEquals(true, replay.get("replayed"));
			maps.publish(op(), campaign, preparation, null);
			assertEquals(beforeItems + 1, count(engine, "inventory_entry", campaign));
			assertEquals(1, count(engine, "map_actor", campaign));
			savedDocument = m(maps.get(campaign, mapRef).get("document"));
			assertEquals(16, list(savedDocument.get("rooms")).size());
			assertFalse(list(savedDocument.get("portals")).isEmpty());
		}
		try (Engine engine = TestCampaigns.engine(db)) {
			MapAuthoringService maps = new MapAuthoringService(engine.db(), engine.rules());
			assertEquals(savedDocument, maps.get(campaign, mapRef).get("document"));
			assertEquals(mapRef, maps.get(campaign, altarRef).get("map"));
			assertEquals(rewardRef, m(maps.get(campaign, preparation).get("references")).get("reward:staff"));
		}
	}

	@Test
	void acceptedBlueprintCannotBeChangedByLayoutHelpersOrPatches() throws Exception {
		try (Engine engine = TestCampaigns.engine(TestCampaigns.tempDb("map-constraints"))) {
			String campaign = TestCampaigns.committedCampaign(engine);
			engine.sessions().bootstrap(op(), campaign, null);
			MapAuthoringService maps = new MapAuthoringService(engine.db(), engine.rules());
			Map<String, Object> draft = maps.begin(op(), campaign, "DUNGEON", blueprint());
			String prep = (String) draft.get("preparation");
			Map<String, Object> accepted = maps.publish(op(), campaign, prep, null);
			assertEquals(ErrorCode.VALIDATION_FAILED, assertThrows(RpgException.class,
					() -> maps.revise(op(), campaign, prep, revision(accepted), map("room_count", 17))).code());
			assertEquals(ErrorCode.VALIDATION_FAILED, assertThrows(RpgException.class, () -> maps.revise(op(), campaign,
					prep, revision(accepted), map("rooms", List.of(map("key", "altar", "level", 0))))).code());
			assertEquals(accepted.get("proposal"), maps.get(campaign, prep).get("proposal"));
		}
	}

	@Test
	void failedPlayablePublicationLeavesNoLiveRoomsActorsOrItems() throws Exception {
		try (Engine engine = TestCampaigns.engine(TestCampaigns.tempDb("map-atomic"))) {
			String campaign = TestCampaigns.committedCampaign(engine);
			engine.sessions().bootstrap(op(), campaign, null);
			MapAuthoringService maps = new MapAuthoringService(engine.db(), engine.rules());
			Map<String, Object> draft = maps.begin(op(), campaign, "SCENE",
					map("name", "Broken proposal", "room_count", 1, "rooms",
							List.of(map("key", "room", "rect_ft", List.of(0, 0, 20, 20))), "geometry",
							map("bounds_ft", List.of(0, 0, 10, 10)), "actors",
							List.of(map("key", "guard", "room", "room", "creature_ref", "Bandit")), "rewards",
							List.of(map("key", "loot", "room", "room", "item_ref", "Quarterstaff"))));
			Map<String, List<Map<String, Object>>> before = engine.db().snapshot(Ref.id(campaign, "campaign"));
			assertThrows(RpgException.class,
					() -> maps.publish(op(), campaign, (String) draft.get("preparation"), null));
			assertEquals(before, engine.db().snapshot(Ref.id(campaign, "campaign")));
		}
	}

	@Test
	void independentDoorsBetweenSameRoomsKeepTheirReferencesAndState() throws Exception {
		try (Engine engine = TestCampaigns.engine(TestCampaigns.tempDb("map-doors"))) {
			String campaign = TestCampaigns.committedCampaign(engine);
			engine.sessions().bootstrap(op(), campaign, null);
			MapAuthoringService maps = new MapAuthoringService(engine.db(), engine.rules());
			Map<String, Object> draft = maps.begin(op(), campaign, "SCENE",
					map("name", "Two doors", "room_count", 2, "rooms",
							List.of(map("key", "a", "rect_ft", List.of(0, 0, 20, 20)),
									map("key", "b", "rect_ft", List.of(20, 0, 20, 20))),
							"geometry", map("bounds_ft", List.of(0, 0, 40, 20)), "connections",
							List.of(map("key", "front", "from", "a", "to", "b", "state", "LOCKED", "from_ft",
									List.of(20, 0), "to_ft", List.of(20, 5)),
									map("key", "secret", "from", "a", "to", "b", "secret", true, "from_ft",
											List.of(20, 15), "to_ft", List.of(20, 20)))));
			Map<String, Object> published = maps.publish(op(), campaign, (String) draft.get("preparation"), null);
			Map<String, Object> refs = m(published.get("references"));
			assertNotEquals(refs.get("connection:front"), refs.get("connection:secret"));
			List<Map<String, Object>> doors = list(m(m(published.get("map")).get("document")).get("doors"));
			assertEquals(List.of("LOCKED", "SECRET"), doors.stream().map(d -> d.get("state")).toList());
		}
	}

	@Test
	void atlasAddressesUseStableSignedCoordinatesAndPreserveWindingPaths() throws Exception {
		try (Engine engine = TestCampaigns.engine(TestCampaigns.tempDb("map-atlas"))) {
			String campaign = TestCampaigns.committedCampaign(engine);
			engine.sessions().bootstrap(op(), campaign, null);
			MapAuthoringService maps = new MapAuthoringService(engine.db(), engine.rules());
			List<List<Integer>> river = List.of(List.of(100, 0), List.of(140, 200), List.of(90, 450),
					List.of(120, 1000));
			Map<String, Object> draft = maps.begin(op(), campaign, "REGION",
					map("name", "Sarg coast", "geometry", map("bounds_ft", List.of(0, 0, 1000, 1000)), "features",
							List.of(map("key", "river", "kind", "RIVER", "path_ft", river))));
			Map<String, Object> published = maps.publish(op(), campaign, (String) draft.get("preparation"), null);
			String atlas = (String) m(published.get("references")).get("atlas");
			Map<String, Object> initial = maps.get(campaign, atlas + "[0,0]");
			assertEquals(published.get("map_ref"), initial.get("map"));
			assertEquals(river.toString(),
					list(m(initial.get("document")).get("features")).getFirst().get("path_ft").toString());
			assertEquals(false, maps.get(campaign, atlas + "[-1,0]").get("generated"));
			assertEquals(atlas + "[-1,-1]",
					maps.find(campaign, map("atlas_ref", atlas, "position_ft", List.of(-0.1, -1))).get("address"));
			assertEquals(43.5, ((Number) maps.get(campaign, atlas).get("origin_latitude")).doubleValue());
			assertEquals(new AtlasCoordinates.Sheet(-1, 0), AtlasCoordinates.containing(-0.1, 0, 1000, 1000));
		}
	}

	@Test
	void checkpointRestoresAcceptedBlueprintAndRemovesLaterRealization() throws Exception {
		try (Engine engine = TestCampaigns.engine(TestCampaigns.tempDb("map-rewind"))) {
			String campaign = TestCampaigns.committedCampaign(engine);
			engine.sessions().bootstrap(op(), campaign, null);
			MapAuthoringService maps = new MapAuthoringService(engine.db(), engine.rules());
			Map<String, Object> draft = maps.begin(op(), campaign, "DUNGEON", blueprint());
			String prep = (String) draft.get("preparation");
			maps.publish(op(), campaign, prep, null);
			String cp = (String) engine.checkpoints().create(op(), campaign, "MAJOR_ENCOUNTER").get("checkpoint");
			Map<String, List<Map<String, Object>>> before = engine.db().snapshot(Ref.id(campaign, "campaign"));
			Map<String, Object> realized = maps.revise(op(), campaign, prep, null,
					map("generate_layout", true, "rewards", List.of(map("key", "staff", "item_ref", "Quarterstaff"))));
			authorMissingConnections(maps, campaign, prep, realized);
			maps.publish(op(), campaign, prep, null);
			engine.checkpoints().restore(op(), campaign, cp, "return to accepted blueprint");
			assertEquals(before, engine.db().snapshot(Ref.id(campaign, "campaign")));
			assertEquals("ACCEPTED", maps.get(campaign, prep).get("status"));
		}
	}

	@Test
	void neighboringSheetRequiresContinuousPathsAndKeepsGeometryWhenAddingPoi() throws Exception {
		try (Engine engine = TestCampaigns.engine(TestCampaigns.tempDb("map-neighbor"))) {
			String campaign = TestCampaigns.committedCampaign(engine);
			engine.sessions().bootstrap(op(), campaign, null);
			MapAuthoringService maps = new MapAuthoringService(engine.db(), engine.rules());
			List<List<Integer>> initialPath = List.of(List.of(500, 0), List.of(550, 450), List.of(500, 1000));
			Map<String, Object> initial = maps.begin(op(), campaign, "REGION",
					map("name", "River country", "geometry", map("bounds_ft", List.of(0, 0, 1000, 1000)), "features",
							List.of(map("key", "river", "kind", "RIVER", "path_ft", initialPath, "width_ft", 10))));
			Map<String, Object> published = maps.publish(op(), campaign, (String) initial.get("preparation"), null);
			String atlas = (String) m(published.get("references")).get("atlas");
			Map<String, Object> north = maps.begin(op(), campaign, "REGION",
					map("name", "Northern river country", "atlas_ref", atlas, "sheet_y", 1));
			String prep = (String) north.get("preparation");
			assertEquals(false, north.get("ready"));
			assertTrue(north.get("violations").toString().contains("BOUNDARY_CONTINUITY"));
			List<List<Integer>> extended = List.of(List.of(500, 0), List.of(550, 450), List.of(500, 1000),
					List.of(480, 1500), List.of(600, 2000));
			north = maps.revise(op(), campaign, prep, null, map("features", List.of(map("key", "river", "kind", "RIVER",
					"path_ft", extended, "width_ft", 10, "extend_feature", true))));
			assertEquals(true, north.get("ready"), north.get("violations").toString());
			Map<String, Object> saved = maps.publish(op(), campaign, prep, null);
			String northMap = (String) saved.get("map_ref");
			Map<String, Object> note = maps.annotate(op(), campaign, northMap, null,
					map("key", "castle-nefaria", "kind", "WORLD_POI", "name", "Castle Nefaria", "position_ft",
							List.of(750, 1250), "create_location", map("name", "Castle Nefaria", "kind", "SITE")));
			assertEquals(true, note.get("created"));
			Map<String, Object> document = m(maps.get(campaign, northMap).get("document"));
			assertEquals(extended.toString(), list(document.get("features")).stream()
					.filter(f -> "river".equals(f.get("key"))).findFirst().orElseThrow().get("path_ft").toString());
			assertTrue(list(document.get("features")).stream().anyMatch(f -> "castle-nefaria".equals(f.get("key"))));
			assertEquals(atlas + "[0,1]", maps.get(campaign, northMap).get("address"));
		}
	}

	@Test
	void depletedRewardSourceIsNotRecreatedByRepublication() throws Exception {
		try (Engine engine = TestCampaigns.engine(TestCampaigns.tempDb("map-depletion"))) {
			String campaign = TestCampaigns.committedCampaign(engine);
			engine.sessions().bootstrap(op(), campaign, null);
			MapAuthoringService maps = new MapAuthoringService(engine.db(), engine.rules());
			Map<String, Object> draft = maps.begin(op(), campaign, "SCENE",
					map("name", "Loot room", "room_count", 1, "generate_layout", true, "rooms",
							List.of(map("key", "room")), "rewards",
							List.of(map("key", "staff", "room", "room", "item_ref", "Quarterstaff"))));
			String prep = (String) draft.get("preparation");
			Map<String, Object> published = maps.publish(op(), campaign, prep, null);
			long inventory = Ref.id((String) m(published.get("references")).get("inventory:staff"), "inventory");
			engine.db().mutate(se.hirt.mcp.rpg.persistence.Database.Mutation.of("test_resolved_loot_depletion",
					Ref.id(campaign, "campaign"), op(), "MECHANICAL_CONSEQUENCE", Map.of()), tx -> {
						tx.delete("inventory_entry", inventory);
						return Map.of("depleted", true);
					});
			long afterLoot = count(engine, "inventory_entry", campaign);
			maps.publish(op(), campaign, prep, null);
			assertEquals(afterLoot, count(engine, "inventory_entry", campaign));
			assertTrue(list(m(maps.get(campaign, (String) published.get("map_ref")).get("document")).get("objects"))
					.isEmpty());
		}
	}

	@Test
	void acceptedDungeonAllowsApproachButStopsOutsideUntilItsCommittedLayoutIsReady() throws Exception {
		try (Engine engine = TestCampaigns.engine(TestCampaigns.tempDb("map-entry-readiness"))) {
			String campaign = TestCampaigns.committedCampaign(engine);
			engine.sessions().bootstrap(op(), campaign, null);
			MapAuthoringService maps = new MapAuthoringService(engine.db(), engine.rules());
			Map<String, Object> draft = maps.begin(op(), campaign, "DUNGEON", blueprint());
			String prep = (String) draft.get("preparation");
			Map<String, Object> accepted = maps.publish(op(), campaign, prep, null);
			String site = (String) m(accepted.get("references")).get("site");
			Map<String, Object> approached = engine.world().move(op(), campaign, site, null, true, 30,
					"approaching the accepted dungeon");
			assertEquals(30L, approached.get("travel_minutes"));
			assertEquals(prep, m(approached.get("preparation_required")).get("preparation"));
			assertEquals("MAP_PREPARATION_REQUIRED", approached.get("stop_reason"));
			assertNotEquals(site, m(approached.get("arrived_at_exterior")).get("ref"));
			Map<String, Object> realized = maps.revise(op(), campaign, prep, null,
					map("generate_layout", true, "rewards", List.of(map("key", "staff", "item_ref", "Quarterstaff"))));
			authorMissingConnections(maps, campaign, prep, realized);
			maps.publish(op(), campaign, prep, null);
			Map<String, Object> entered = engine.world().move(op(), campaign, site, null, true, 30,
					"enter the coherent prepared dungeon");
			assertEquals(0L, entered.get("travel_minutes"));
			assertEquals(List.of(), m(entered.get("meta")).get("warnings"),
					"Published dungeon coverage prevents the obsolete semantic-only warning.");
		}
	}

	@Test
	void oldCampaignResumeStagesOneSourcedMapWithoutMovingOrAgingTheParty() throws Exception {
		try (Engine engine = TestCampaigns.engine(TestCampaigns.tempDb("map-legacy-preparation"))) {
			String campaign = TestCampaigns.committedCampaign(engine);
			engine.sessions().bootstrap(op(), campaign, null);
			long cid = Ref.id(campaign, "campaign");
			String regionalMap = TestCampaigns.publishRegionalFixture(engine, campaign);
			String existingRegion = engine.db()
					.read(tx -> "location:" + tx.get("map_scene", Ref.id(regionalMap, "map")).lng("location_id"));
			String opening = engine.db().read(tx -> "location:" + tx.get("campaign", cid).lng("current_location_id"));
			engine.world().materialize(op(), campaign, null,
					map("name", "Old quarry", "kind", "SITE", "parent", existingRegion, "connections",
							List.of(map("to", opening, "kind", "ROAD", "travel_minutes", 45, "distance_miles", 2.25))),
					"GM");
			engine.db().mutate(se.hirt.mcp.rpg.persistence.Database.Mutation.of("test_legacy_mapless_state", cid, op(),
					"GM", Map.of()), tx -> {
						for (String table : List.of("map_feature", "map_observation", "map_actor", "map_object",
								"map_scene", "map_preparation", "map_atlas", "map_asset"))
							for (var row : tx.query("SELECT id FROM " + table + " WHERE campaign_id=? ORDER BY id DESC",
									cid))
								tx.delete(table, row.id());
						return Map.of("legacy_mapless", true);
					});
			Map<String, List<Map<String, Object>>> before = engine.db().snapshot(cid);
			Map<String, Object> resumed = engine.sessions().bootstrap(op(), campaign, null);
			Map<String, Object> world = m(resumed.get("world_maps"));
			assertEquals("PREPARATION_REQUIRED", world.get("status"));
			String prep = (String) world.get("preparation");
			assertEquals(prep,
					m(engine.sessions().bootstrap(op(), campaign, null).get("world_maps")).get("preparation"));
			Map<String, List<Map<String, Object>>> staged = engine.db().snapshot(cid);
			assertEquals(before.get("game_clock"), staged.get("game_clock"));
			assertEquals(before.get("character"), staged.get("character"));
			assertEquals(before.get("location_connection"), staged.get("location_connection"));
			MapAuthoringService maps = new MapAuthoringService(engine.db(), engine.rules());
			assertFalse(list(m(maps.get(campaign, prep).get("proposal")).get("legacy_constraints")).isEmpty());
			assertThrows(RpgException.class, () -> maps.publish(op(), campaign, prep, null));
			maps.revise(op(), campaign, prep, null, map("review_required", false));
			maps.publish(op(), campaign, prep, null);
			Map<String, List<Map<String, Object>>> published = engine.db().snapshot(cid);
			assertEquals(before.get("game_clock"), published.get("game_clock"));
			assertEquals(before.get("character"), published.get("character"));
			assertEquals(before.get("location_connection"), published.get("location_connection"));
			assertEquals(before.get("location"), published.get("location"));
		}
	}

	@Test
	void farNorthernSheetDerivesColdClimateInsteadOfCopyingTheOpeningClimate() throws Exception {
		try (Engine engine = TestCampaigns.engine(TestCampaigns.tempDb("map-latitude-climate"))) {
			String campaign = TestCampaigns.committedCampaign(engine);
			engine.sessions().bootstrap(op(), campaign, null);
			MapAuthoringService initialMaps = new MapAuthoringService(engine.db(), engine.rules());
			Map<String, Object> initialRegion = initialMaps.begin(op(), campaign, "REGION",
					map("name", "Climate frame", "geometry", map("bounds_ft", List.of(0, 0, 528000, 528000))));
			initialMaps.publish(op(), campaign, initialRegion.get("preparation").toString(), null);
			String atlas = engine.db().read(
					tx -> "atlas:" + tx.queryOne("SELECT id FROM map_atlas WHERE campaign_id=? ORDER BY id LIMIT 1",
							Ref.id(campaign, "campaign")).orElseThrow().id());
			MapAuthoringService maps = new MapAuthoringService(engine.db(), engine.rules());
			Map<String, Object> north = maps.begin(op(), campaign, "REGION",
					map("name", "Polar reaches", "atlas_ref", atlas, "sheet_y", 24));
			assertEquals("POLAR", m(m(north.get("proposal")).get("climate")).get("baseline"));
			Map<String, Object> saved = maps.publish(op(), campaign, (String) north.get("preparation"), null);
			assertEquals("POLAR", m(m(m(saved.get("map")).get("document")).get("climate")).get("baseline"));
		}
	}

	@Test
	void inferredHelperDoorAdmitsACharacterOnTheFiveFootLattice() throws Exception {
		try (Engine engine = TestCampaigns.engine(TestCampaigns.tempDb("map-helper-door"))) {
			String campaign = TestCampaigns.committedCampaign(engine);
			engine.sessions().bootstrap(op(), campaign, null);
			String opening = engine.db().read(
					tx -> "location:" + tx.get("campaign", Ref.id(campaign, "campaign")).lng("current_location_id"));
			MapAuthoringService maps = new MapAuthoringService(engine.db(), engine.rules());
			Map<String, Object> draft = maps.begin(op(), campaign, "SCENE",
					map("name", "Fitted two-room house", "location_ref", opening, "room_count", 2, "rooms",
							List.of(map("key", "a"), map("key", "b")), "connections",
							List.of(map("key", "entry", "from", "a", "to", "b")), "generate_layout", true));
			String mapRef = (String) maps.publish(op(), campaign, (String) draft.get("preparation"), null)
					.get("map_ref");
			engine.scenes().place(op(), campaign, mapRef,
					List.of(map("character", "character:1", "x_ft", 10, "y_ft", 10, "level", 0)));
			assertEquals("FOUND", engine.scenes().preview(campaign, mapRef, "character:1", 25, 10, "GM").get("status"));
		}
	}

	@Test
	void localCustomGeometryIsResolvedToInchesAndDegenerateCutsCannotPublish() throws Exception {
		try (Engine engine = TestCampaigns.engine(TestCampaigns.tempDb("map-geometry-precision"))) {
			String campaign = TestCampaigns.committedCampaign(engine);
			engine.sessions().bootstrap(op(), campaign, null);
			MapAuthoringService maps = new MapAuthoringService(engine.db(), engine.rules());
			Map<String, Object> draft = maps.begin(op(), campaign, "SCENE", map("name", "Precise cut", "room_count", 1,
					"geometry", map("bounds_ft", List.of(0, 0, 25, 25)), "rooms",
					List.of(map("key", "room", "attributes", map("temperature", .1), "polygon_ft",
							List.of(List.of(.1, .1), List.of(20.1, .1), List.of(20.1, 20.1), List.of(.1, 20.1))))));
			Map<String, Object> room = list(m(draft.get("proposal")).get("rooms")).getFirst();
			assertEquals(1.0 / 12,
					((Number) ((List<?>) ((List<?>) room.get("polygon_ft")).getFirst()).getFirst()).doubleValue());
			assertEquals(.1, ((Number) m(room.get("attributes")).get("temperature")).doubleValue());
			String mapRef = (String) maps.publish(op(), campaign, (String) draft.get("preparation"), null)
					.get("map_ref");
			Map<String, Object> document = m(maps.get(campaign, mapRef).get("document"));
			assertEquals(1,
					se.hirt.mcp.rpg.maps.SceneService.geometry(document, 0).floors().getFirst().shell().getFirst().x());
			assertTrue(engine.mapRenderer().render(document, Map.of("max_pixels", 128)).png().length > 100);
			Map<String, Object> broken = maps.begin(op(), campaign, "SCENE",
					map("name", "Degenerate cut", "room_count", 1, "geometry", map("bounds_ft", List.of(0, 0, 25, 25)),
							"rooms", List.of(map("key", "room", "rect_ft", List.of(0, 0, 20, 20), "holes_ft",
									List.of(List.of(List.of(5.01, 5.01), List.of(5.02, 5.01), List.of(5.02, 5.02)))))));
			assertEquals(false, broken.get("ready"));
			assertThrows(RpgException.class,
					() -> maps.publish(op(), campaign, (String) broken.get("preparation"), null));
		}
	}

	/** The fixture DM explicitly supplies the graph; generate_layout only fits its room shapes. */
	private static Map<String, Object> authorMissingConnections(
		MapAuthoringService maps, String campaign, String preparation, Map<String, Object> realized) {
		Map<String, Object> proposal = m(realized.get("proposal"));
		var edges = new java.util.ArrayList<Map<String, Object>>(list(proposal.get("connections")));
		var floors = new java.util.TreeMap<Integer, java.util.List<Map<String, Object>>>();
		for (Map<String, Object> room : list(proposal.get("rooms")))
			floors.computeIfAbsent(((Number) room.get("level")).intValue(), k -> new java.util.ArrayList<>()).add(room);
		Map<String, Object> previous = null;
		for (var floor : floors.entrySet()) {
			floor.getValue().sort(java.util.Comparator
					.comparingDouble(r -> ((Number) ((List<?>) r.get("rect_ft")).getFirst()).doubleValue()));
			for (int i = 1; i < floor.getValue().size(); i++) {
				String a = floor.getValue().get(i - 1).get("key").toString(),
						b = floor.getValue().get(i).get("key").toString();
				if (edges.stream().noneMatch(e -> a.equals(e.get("from")) && b.equals(e.get("to"))
						|| b.equals(e.get("from")) && a.equals(e.get("to"))))
					edges.add(map("key", "authored-" + floor.getKey() + "-" + i, "from", a, "to", b));
			}
			Map<String, Object> first = floor.getValue().getFirst();
			if (previous != null)
				edges.add(map("key", "authored-stairs-" + floor.getKey(), "kind", "STAIRS", "from", previous.get("key"),
						"to", first.get("key")));
			previous = first;
		}
		return maps.revise(op(), campaign, preparation, null, map("connections", edges));
	}

	private static Map<String, Object> blueprint() {
		return map("name", "The forgotten altar", "room_count", 16, "levels", 2, "rooms",
				List.of(map("key", "altar", "name", "Altar room", "level", 1, "role", "ALTAR"),
						map("key", "treasure", "name", "Treasure room", "level", 1)),
				"connections", List.of(map("key", "secret-entry", "from", "altar", "to", "treasure", "secret", true)),
				"rewards", List.of(map("key", "staff", "room", "treasure", "name", "Ceremonial staff of lightning",
						"quantity", 1)));
	}

	private static long count(Engine engine, String table, String campaign) {
		return engine.db().read(
				tx -> tx.count("SELECT COUNT(*) FROM " + table + " WHERE campaign_id=?", Ref.id(campaign, "campaign")));
	}

	private static Long revision(Map<String, Object> value) {
		return ((Number) value.get("revision")).longValue();
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> m(Object value) {
		return (Map<String, Object>) value;
	}

	@SuppressWarnings("unchecked")
	private static List<Map<String, Object>> list(Object value) {
		return (List<Map<String, Object>>) value;
	}
}
