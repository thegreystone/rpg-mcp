/* Copyright (C) 2026 Marcus Hirt. SPDX-License-Identifier: BSD-3-Clause */
package se.hirt.mcp.rpg;

import org.junit.jupiter.api.Test;
import se.hirt.mcp.rpg.dice.ScriptedRollService;
import se.hirt.mcp.rpg.maps.SceneService;
import se.hirt.mcp.rpg.persistence.Database;
import se.hirt.mcp.rpg.protocol.Json;
import se.hirt.mcp.rpg.protocol.Ref;
import se.hirt.mcp.rpg.protocol.RpgException;
import se.hirt.mcp.rpg.spatial.SpatialRules;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static se.hirt.mcp.rpg.TestCampaigns.*;

class MapSpatialRepairTest {
	@Test
	void diagonalDoorwayExposureUsesTheSameSamplesForBothObserversAndAttackCover() throws Exception {
		try (Engine e = engine(tempDb("doorway-sight-cover"))) {
			String campaign = committedCampaign(e);
			e.sessions().bootstrap(op(), campaign, null);
			String prep = (String) e.maps()
					.begin(op(), campaign, "SCENE", map("name", "Mine gallery", "location_ref", opening(e, campaign),
							"room_count", 2, "geometry", map("bounds_ft", List.of(0, 0, 100, 40)), "rooms",
							List.of(map("key", "drift", "rect_ft",
									List.of(20, 0, 60, 10)), map("key", "gallery", "rect_ft", List.of(30, 10, 30, 25))),
							"connections",
							List.of(map("key", "gallery-door", "from", "drift", "to", "gallery", "from_ft",
									List.of(40, 10), "to_ft", List.of(45, 10))),
							"actors", List.of(map("key", "overseer", "room", "gallery", "creature_ref", "Bandit",
									"x_ft", 40, "y_ft", 10))))
					.get("preparation");
			Map<String, Object> published = e.maps().publish(op(), campaign, prep, null);
			String mapRef = published.get("map_ref").toString(),
					guard = m(published.get("references")).get("actor:overseer").toString();
			e.scenes().place(op(), campaign, mapRef, List.of(map("character", "character:1", "x_ft", 35, "y_ft", 5)));
			long cid = Ref.id(campaign, Ref.CAMPAIGN);
			e.db().read(tx -> {
				var scene = SceneService.scene(tx, cid, mapRef);
				var hero = SceneService.actor(tx, cid, "character:1");
				var enemy = SceneService.actor(tx, cid, guard);
				assertFalse(
						SpatialRules.lineOfSight(SceneService.geometry(SceneService.document(tx, scene), 0),
								SceneService.center(hero), SceneService.center(enemy)),
						"the center ray touches the wall endpoint");
				assertTrue(SceneService.canSee(tx, scene, hero, enemy));
				assertTrue(SceneService.canSee(tx, scene, enemy, hero));
				assertEquals(SpatialRules.Cover.HALF, SceneService.cover(tx, scene, hero, enemy).cover());
				assertEquals(SpatialRules.Cover.HALF, SceneService.cover(tx, scene, enemy, hero).cover());
				return null;
			});
			assertTrue(Json.write(e.scenes().perception(campaign, mapRef, guard)).contains("character:1"));
			assertTrue(Json.write(e.scenes().context(campaign, mapRef, "PLAYER", 0)).contains(guard));
			ScriptedRollService dice = (ScriptedRollService) e.roller();
			dice.queue(1, 20);
			String encounter = e.encounters()
					.start(op(), campaign, map("party", List.of("character:1"), "guards", List.of(guard)), null, null,
							null, null, null, map("map_ref", mapRef))
					.get("encounter").toString();
			dice.queue(1);
			Map<String, Object> enemyAttack = e.encounters().perform(op(), campaign, encounter, guard,
					map("kind", "ATTACK", "attack", "Scimitar", "target", "character:1"), true);
			assertEquals(2, enemyAttack.get("cover_bonus"));
			assertEquals("HALF", enemyAttack.get("cover"));
			assertEquals("NONE", enemyAttack.get("advantage"));
			dice.queue(1);
			Map<String, Object> heroAttack = e.encounters().perform(op(), campaign, encounter, "character:1",
					map("kind", "ATTACK", "weapon", "unarmed", "target", guard), false);
			assertEquals(2, heroAttack.get("cover_bonus"));
			assertEquals(14, heroAttack.get("target_armor_class"));
			assertEquals("NONE", heroAttack.get("advantage"));
			assertTrue(dice.exhausted());
		}
	}

	@Test
	void openingsMustLieOnTheSharedWallAndAdmitAnActualGridCrossing() throws Exception {
		try (Engine e = engine(tempDb("opening-validation"))) {
			String campaign = committedCampaign(e);
			e.sessions().bootstrap(op(), campaign, null);
			for (Map<String, Object> door : List.of(map("from_ft", List.of(10, 5), "to_ft", List.of(10, 10)),
					map("from_ft", List.of(20, 2.5), "to_ft", List.of(20, 7.5)))) {
				var edge = map("key", "door", "from", "west", "to", "east");
				edge.putAll(door);
				Map<String, Object> draft = e.maps().begin(op(), campaign, "SCENE", twoRooms(e, campaign, edge));
				assertEquals(false, draft.get("ready"));
				String violations = Json.write(draft.get("violations"));
				assertTrue(violations.contains("OPENING_OFF_SHARED_WALL")
						|| violations.contains("OPENING_NOT_TRAVERSABLE"), violations);
				var before = e.db().snapshot(Ref.id(campaign, Ref.CAMPAIGN));
				assertThrows(RpgException.class,
						() -> e.maps().publish(op(), campaign, draft.get("preparation").toString(), null));
				assertEquals(before, e.db().snapshot(Ref.id(campaign, Ref.CAMPAIGN)));
			}
		}
	}

	@Test
	void layoutHelperPreservesTopologyAndDraftEntriesCanBeRemovedWithoutErasingCommitments() throws Exception {
		try (Engine e = engine(tempDb("layout-topology-removal"))) {
			String campaign = committedCampaign(e);
			e.sessions().bootstrap(op(), campaign, null);
			Map<String, Object> authored = map("key", "known-route", "from", "a", "to", "b");
			Map<String, Object> draft = e.maps().begin(op(), campaign, "SCENE",
					map("room_count", 3, "rooms", List.of(map("key", "a"), map("key", "b"), map("key", "c")),
							"connections", List.of(authored), "generate_layout", true));
			assertEquals(List.of(authored), list(m(draft.get("proposal")).get("connections")));
			assertEquals(false, draft.get("ready"), "The undeclared route to room c is a DM decision.");
			String prep = draft.get("preparation").toString();
			e.maps().revise(op(), campaign, prep, null,
					map("connections", List.of(map("key", "optional-route", "from", "b", "to", "c"))));
			Map<String, Object> removed = e.maps().revise(op(), campaign, prep, null,
					map("connections", List.of(map("key", "optional-route", "remove", true))));
			assertEquals(List.of(authored), list(m(removed.get("proposal")).get("connections")));
			Map<String, Object> blueprint = e.maps().begin(op(), campaign, "DUNGEON", map("room_count", 2, "rooms",
					List.of(map("key", "a"), map("key", "b")), "connections", List.of(authored)));
			String accepted = blueprint.get("preparation").toString();
			e.maps().publish(op(), campaign, accepted, null);
			assertThrows(RpgException.class, () -> e.maps().revise(op(), campaign, accepted, null,
					map("connections", List.of(map("key", "known-route", "remove", true)))));
		}
	}

	@Test
	void sameLevelPortalPublishesAsATransitionAndWorksInBothDirections() throws Exception {
		try (Engine e = engine(tempDb("same-level-portal"))) {
			String campaign = committedCampaign(e);
			e.sessions().bootstrap(op(), campaign, null);
			Map<String, Object> draft = e.maps().begin(op(), campaign, "SCENE", map("name", "Two linked sanctums",
					"location_ref", opening(e, campaign), "room_count", 2, "geometry",
					map("bounds_ft", List.of(0, 0, 60, 20)), "rooms",
					List.of(map("key", "west", "rect_ft", List.of(0, 0, 20, 20)),
							map("key", "east", "rect_ft", List.of(40, 0, 20, 20))),
					"connections", List.of(map("key", "mirror", "kind", "PORTAL", "from", "west", "to", "east",
							"from_position_ft", List.of(5, 5), "to_position_ft", List.of(45, 5), "movement_ft", 5))));
			Map<String, Object> published = e.maps().publish(op(), campaign, draft.get("preparation").toString(), null);
			String mapRef = published.get("map_ref").toString();
			Map<String, Object> doc = m(m(published.get("map")).get("document"));
			assertEquals(1, list(doc.get("portals")).size());
			assertTrue(list(doc.get("doors")).isEmpty());
			e.scenes().place(op(), campaign, mapRef, List.of(map("character", "character:1", "x_ft", 5, "y_ft", 5)));
			long cid = Ref.id(campaign, Ref.CAMPAIGN);
			long actorId = e.db().read(tx -> SceneService.actor(tx, cid, "character:1").id());
			e.scenes().transit(op(), campaign, mapRef, "character:1", "mirror");
			assertEquals(45.0,
					e.db().read(tx -> ((Number) tx.get("map_actor", actorId).get("x_ft")).doubleValue()).doubleValue());
			e.scenes().transit(op(), campaign, mapRef, "character:1", "mirror");
			assertEquals(5.0,
					e.db().read(tx -> ((Number) tx.get("map_actor", actorId).get("x_ft")).doubleValue()).doubleValue());
			assertEquals(actorId, e.db().read(tx -> SceneService.actor(tx, cid, "character:1").id()).longValue());
		}
	}

	@Test
	void correctingALegacyPublishedDoorPreservesAllEntitiesAndUnseenExploredMemory() throws Exception {
		try (Engine e = engine(tempDb("published-geometry-rescue"))) {
			String campaign = committedCampaign(e);
			e.sessions().bootstrap(op(), campaign, null);
			Map<String, Object> proposal = twoRooms(e, campaign, map("key", "door", "from", "west", "to", "east",
					"from_ft", List.of(20, 5), "to_ft", List.of(20, 10)));
			proposal.put("objects", List.of(map("key", "chest", "room", "west", "name", "Battered chest", "x_ft", 5,
					"y_ft", 15, "locked", true, "hit_points", 8)));
			proposal.put("actors",
					List.of(map("key", "guard", "room", "west", "creature_ref", "Bandit", "x_ft", 10, "y_ft", 10)));
			proposal.put("rewards",
					List.of(map("key", "staff", "room", "east", "item_ref", "Quarterstaff", "x_ft", 25, "y_ft", 10)));
			String prep = e.maps().begin(op(), campaign, "SCENE", proposal).get("preparation").toString();
			Map<String, Object> published = e.maps().publish(op(), campaign, prep, null);
			String mapRef = published.get("map_ref").toString(),
					guard = m(published.get("references")).get("actor:guard").toString();
			e.scenes().place(op(), campaign, mapRef, List.of(map("character", "character:1", "x_ft", 5, "y_ft", 5)));
			e.scenes().move(op(), campaign, mapRef, "character:1", 25, 5);
			e.scenes().move(op(), campaign, mapRef, "character:1", 5, 5);
			e.scenes().changeObject(op(), campaign, mapRef, "door", map("state", "CLOSED"), "The door swings shut.");
			e.scenes().changeObject(op(), campaign, mapRef, "object:chest", map("hit_points", 4),
					"A resolved blow damages the chest.");
			e.runtime().applyRuntimeChange(op(), campaign, guard,
					map("kind", "DAMAGE", "amount", 1, "reason", "Resolved prior injury."));
			long cid = Ref.id(campaign, Ref.CAMPAIGN), mid = Ref.id(mapRef, Ref.MAP);
			// Simulate a map accepted by the older validator: its opening was drawn inside one room.
			e.db().mutate(Database.Mutation.of("legacy-bad-opening-fixture", cid, op(), "GM", null), tx -> {
				var scene = tx.get("map_scene", mid);
				var doc = new java.util.LinkedHashMap<>(scene.map("document_json"));
				var door = new java.util.LinkedHashMap<>(list(doc.get("doors")).getFirst());
				door.put("from_ft", List.of(10, 5));
				door.put("to_ft", List.of(10, 10));
				doc.put("doors", List.of(door));
				tx.update("map_scene", mid,
						map("document_json", Json.write(doc), "revision", scene.lng("revision") + 1));
				SceneService.remember(tx, tx.get("map_scene", mid));
				return Map.of();
			});
			long revision = ((Number) e.scenes().context(campaign, mapRef, "GM", 0).get("map_revision")).longValue();
			Map<String, List<Map<String, Object>>> before = e.db().snapshot(cid);
			Map<String, Object> memory = rememberedCell(e, mid, "0:5:1");
			assertFalse(memory.isEmpty(), "The visited eastern room has remembered floor.");
			assertThrows(RpgException.class, () -> e.maps().correctGeometry(op(), campaign, mapRef, revision,
					map("bounds_ft", List.of(0, 0, 10, 10)), "A correction that would strand existing actors."));
			assertEquals(before, e.db().snapshot(cid));
			RpgException invalidActor = assertThrows(RpgException.class,
					() -> e.maps().correctGeometry(op(), campaign, mapRef, revision, map("doors",
							List.of(map("key", "door", "from_ft", List.of(20, 5), "to_ft", List.of(20, 10))), "rooms",
							List.of(map("key", "west", "holes_ft",
									List.of(List.of(List.of(5, 5), List.of(10, 5), List.of(10, 10), List.of(5, 10)))))),
							"This otherwise coherent correction would place the hero over a pit."));
			assertTrue(Json.write(invalidActor.details()).contains("ACTOR_WOULD_BE_INVALID"));
			assertEquals(before, e.db().snapshot(cid),
					"Actor-fit failures roll back the staged scene and journal changes.");
			assertThrows(RpgException.class,
					() -> e.maps().correctGeometry(op(), campaign, mapRef, revision - 1,
							map("doors",
									List.of(map("key", "door", "from_ft", List.of(20, 5), "to_ft", List.of(20, 10)))),
							"Stale revision."));
			String operation = op();
			Map<String, Object> patch = map("doors",
					List.of(map("key", "door", "from_ft", List.of(20, 5), "to_ft", List.of(20, 10))));
			Map<String, Object> corrected = e.maps().correctGeometry(operation, campaign, mapRef, revision, patch,
					"Move the misplaced opening onto the existing shared wall.");
			assertEquals(revision + 1, corrected.get("map_revision"));
			assertEquals(mapRef, corrected.get("map"));
			assertEquals(true, e.maps().correctGeometry(operation, campaign, mapRef, revision, patch,
					"Move the misplaced opening onto the existing shared wall.").get("replayed"));
			var after = e.db().snapshot(cid);
			for (String table : List.of("character", "map_actor", "map_object", "inventory_entry", "location",
					"location_connection", "active_effect"))
				assertEquals(before.get(table), after.get(table),
						table + " retains its canonical identities and live state");
			assertEquals(memory, rememberedCell(e, mid, "0:5:1"),
					"A correction does not replace unseen explored evidence.");
			assertEquals("NO_PATH", e.scenes().preview(campaign, mapRef, "character:1", 25, 5, "GM").get("status"),
					"The canonical closed-door state was preserved.");
			e.scenes().changeObject(op(), campaign, mapRef, "door", map("state", "OPEN"),
					"The party opens the corrected doorway.");
			assertEquals("FOUND", e.scenes().preview(campaign, mapRef, "character:1", 25, 5, "GM").get("status"));
			e.scenes().move(op(), campaign, mapRef, "character:1", 25, 5);
		}
	}

	@Test
	void anIncidentalBoardCanBeCorrectedUsingItsExistingPhysicalRoomBoundaries() throws Exception {
		try (Engine e = engine(tempDb("incidental-geometry-correction"))) {
			String campaign = committedCampaign(e);
			e.sessions().bootstrap(op(), campaign, null);
			String guard = e.runtime()
					.materialize(op(), campaign, "Bandit", "Roadside guard", null, null, null, null, false)
					.get("character").toString();
			((ScriptedRollService) e.roller()).queue(20, 1);
			Map<String, Object> started = e.encounters()
					.start(op(), campaign, map("party", List.of("character:1"), "guards", List.of(guard)), null, null,
							null, null, null, map(
									"scene", map(
											"bounds_ft", List.of(0, 0, 40, 20), "rooms", List
													.of(map("key", "taproom", "rect_ft", List.of(0, 0, 20, 20)),
															map("key", "porch", "rect_ft", List.of(20, 0, 20, 20))),
											"doors",
											List.of(map("key", "front-door", "from_ft", List.of(20, 5), "to_ft",
													List.of(20, 10), "state", "CLOSED"))),
									"placements", List.of(map("character", "character:1", "x_ft", 5, "y_ft", 5),
											map("character", guard, "x_ft", 25, "y_ft", 5))));
			String mapRef = m(started.get("grid")).get("map_ref").toString();
			e.encounters().end(op(), campaign, started.get("encounter").toString(), "OTHER",
					"The guard and party stand down before correcting the physical map.");
			long cid = Ref.id(campaign, Ref.CAMPAIGN);
			long revision = ((Number) e.scenes().context(campaign, mapRef, "GM", 0).get("map_revision")).longValue();
			var before = e.db().snapshot(cid);
			Map<String, Object> result = e.maps().correctGeometry(op(), campaign, mapRef, revision,
					map("bounds_ft", List.of(0, 0, 50, 20), "rooms",
							List.of(map("key", "porch", "rect_ft", List.of(20, 0, 30, 20)))),
					"Extend the existing physical porch without changing the route or occupants.");
			assertEquals(true, result.get("corrected"));
			var after = e.db().snapshot(cid);
			for (String table : List.of("location", "location_connection", "character", "map_actor", "encounter",
					"encounter_participant"))
				assertEquals(before.get(table), after.get(table), table + " is not reauthored by physical validation.");
			assertEquals("NO_PATH", e.scenes().preview(campaign, mapRef, "character:1", 30, 5, "GM").get("status"));
		}
	}

	@Test
	void geometryCorrectionsRejectMalformedArraysAndUnknownLevelsWithoutChangingTheMap() throws Exception {
		try (Engine e = engine(tempDb("typed-geometry-correction"))) {
			String campaign = committedCampaign(e);
			e.sessions().bootstrap(op(), campaign, null);
			String prep = e.maps()
					.begin(op(), campaign, "SCENE", twoRooms(e, campaign, map("key", "door", "from", "west", "to",
							"east", "from_ft", List.of(20, 5), "to_ft", List.of(20, 10))))
					.get("preparation").toString();
			String mapRef = e.maps().publish(op(), campaign, prep, null).get("map_ref").toString();
			long revision = ((Number) e.scenes().context(campaign, mapRef, "GM", 0).get("map_revision")).longValue();
			var before = e.db().snapshot(Ref.id(campaign, Ref.CAMPAIGN));
			List<Map<String, Object>> malformed = List.of(
					map("rooms", List.of(map("key", "west", "holes_ft", "not-rings"))),
					map("rooms", List.of(map("key", "west", "holes_ft", List.of("not-a-ring")))),
					map("rooms",
							List.of(map("key", "west", "holes_ft",
									List.of(List.of(List.of(1, 1), "bad", List.of(2, 2)))))),
					map("rooms", List.of(map("key", "west", "rect_ft", List.of(0, 0, 20)))),
					map("rooms", List.of(map("key", "west", "rect_ft", List.of(0, "zero", 20, 20)))),
					map("rooms", List.of(map("key", "west", "polygon_ft", "not-a-polygon"))),
					map("rooms",
							List.of(map("key", "west", "rect_ft", List.of(0, 0, 20, 20), "polygon_ft",
									List.of(List.of(0, 0), List.of(20, 0), List.of(0, 20))))),
					map("rooms", List.of(map("key", "west", "rect_ft", List.of(0, 0, 20, 20)), "bad")),
					map("rooms", List.of()), map("doors", List.of(map("key", "door", "from_ft", List.of(20, 5, 0)))),
					map("bounds_ft", List.of(0, 0, 40)), map("walls", List.of("bad")),
					map("walls", List.of(map("from_ft", List.of(5, 0), "to_ft", List.of(5, 5), "level", "bad"))),
					map("walls", List.of(map("from_ft", List.of(5, 0), "to_ft", List.of(5, 5), "level", 2))),
					map("terrain", List.of(map("rect_ft", List.of(0, 0, 5, 5), "level", 0.5))),
					map("terrain", List.of(map("rect_ft", List.of(0, 0, 5, 5), "movement_multiplier", 1.5))),
					map("terrain", List.of(map("rect_ft", List.of(0, 0, 5, 5), "holes_ft", false))));
			for (Map<String, Object> patch : malformed) {
				RpgException invalid = assertThrows(RpgException.class, () -> e.maps().correctGeometry(op(), campaign,
						mapRef, revision, patch, "Reject malformed geometry."), Json.write(patch));
				assertEquals("INVALID_ARGUMENT", invalid.code().name(), Json.write(patch));
				assertEquals(before, e.db().snapshot(Ref.id(campaign, Ref.CAMPAIGN)), Json.write(patch));
			}
		}
	}

	@Test
	void geometryCorrectionsPreserveFloorSupportForIntactObjectsAndUnclaimedLoot() throws Exception {
		try (Engine e = engine(tempDb("geometry-object-floor"))) {
			String campaign = committedCampaign(e);
			e.sessions().bootstrap(op(), campaign, null);
			Map<String, Object> proposal = twoRooms(e, campaign, map("key", "door", "from", "west", "to", "east",
					"from_ft", List.of(20, 5), "to_ft", List.of(20, 10)));
			proposal.put("objects", List.of(map("key", "chest", "room", "west", "rect_ft", List.of(5, 5, 5, 5),
					"hit_points", 8, "blocks_movement", true)));
			proposal.put("rewards",
					List.of(map("key", "staff", "room", "east", "item_ref", "Quarterstaff", "x_ft", 25, "y_ft", 5)));
			String prep = e.maps().begin(op(), campaign, "SCENE", proposal).get("preparation").toString();
			Map<String, Object> published = e.maps().publish(op(), campaign, prep, null);
			String mapRef = published.get("map_ref").toString();
			long cid = Ref.id(campaign, Ref.CAMPAIGN);
			long revision = ((Number) e.scenes().context(campaign, mapRef, "GM", 0).get("map_revision")).longValue();
			for (String room : List.of("west", "east")) {
				int x = room.equals("west") ? 5 : 25;
				var before = e.db().snapshot(cid);
				RpgException invalid = assertThrows(RpgException.class, () -> e.maps().correctGeometry(op(), campaign,
						mapRef, revision, roomHole(room, x, 5), "A floor correction must not strand a saved object."));
				assertTrue(Json.write(invalid.details()).contains("OBJECT_WOULD_BE_INVALID"));
				assertEquals(before, e.db().snapshot(cid));
			}
			e.scenes().changeObject(op(), campaign, mapRef, "object:chest", map("state", "DESTROYED", "hit_points", 0),
					"Resolved destruction.");
			long damagedRevision = ((Number) e.scenes().context(campaign, mapRef, "GM", 0).get("map_revision"))
					.longValue();
			var destroyedObject = e.db()
					.read(tx -> tx.queryOne("SELECT * FROM map_object WHERE scene_id=? AND object_key='object:chest'",
							Ref.id(mapRef, Ref.MAP)).orElseThrow().asMap());
			Map<String, Object> corrected = e.maps().correctGeometry(op(), campaign, mapRef, damagedRevision,
					roomHole("west", 5, 5), "The destroyed chest no longer needs an intact supporting floor.");
			assertEquals(true, corrected.get("corrected"));
			assertEquals(destroyedObject,
					e.db().read(
							tx -> tx.queryOne("SELECT * FROM map_object WHERE scene_id=? AND object_key='object:chest'",
									Ref.id(mapRef, Ref.MAP)).orElseThrow().asMap()));
		}
	}

	private static Map<String, Object> roomHole(String room, int x, int y) {
		return map("rooms", List.of(map("key", room, "holes_ft",
				List.of(List.of(List.of(x, y), List.of(x + 5, y), List.of(x + 5, y + 5), List.of(x, y + 5))))));
	}

	@Test
	void aGuardCanExplicitlyThrowItsAuthoredSpearWithoutChangingTheDefaultMeleeIntent() throws Exception {
		try (Engine e = engine(tempDb("guard-spear-grid"))) {
			String campaign = committedCampaign(e);
			e.sessions().bootstrap(op(), campaign, null);
			String guard = e.runtime()
					.materialize(op(), campaign, "Guard", "Spear guard", null, null, null, null, false).get("character")
					.toString();
			ScriptedRollService dice = (ScriptedRollService) e.roller();
			dice.queue(1, 20);
			String encounter = e.encounters()
					.start(op(), campaign, map("party", List.of("character:1"), "guards", List.of(guard)), null, null,
							null, null, null,
							map("placements",
									List.of(map("character", "character:1", "x_ft", 0, "y_ft", 0),
											map("character", guard, "x_ft", 20, "y_ft", 0))))
					.get("encounter").toString();
			assertThrows(RpgException.class, () -> e.encounters().perform(op(), campaign, encounter, guard,
					map("kind", "ATTACK", "attack", "Spear", "target", "character:1"), false));
			dice.queue(1);
			Map<String, Object> normal = e.encounters().perform(op(), campaign, encounter, guard,
					map("kind", "ATTACK", "attack", "Spear", "thrown", true, "target", "character:1"), true);
			assertEquals("NONE", normal.get("advantage"));
			e.encounters().perform(op(), campaign, encounter, "character:1", map("kind", "END_TURN"), true);
			e.encounters().perform(op(), campaign, encounter, guard, map("kind", "MOVE", "x_ft", 35, "y_ft", 0), false);
			dice.queue(2, 3);
			Map<String, Object> distant = e.encounters().perform(op(), campaign, encounter, guard,
					map("kind", "ATTACK", "attack", "Spear", "mode", "RANGED", "target", "character:1"), false);
			assertEquals("DISADVANTAGE", distant.get("advantage"));
			assertTrue(m(distant.get("attack_roll")).get("expression").toString().startsWith("2d20kl1"));
			assertTrue(dice.exhausted());
		}
	}

	private static Map<String, Object> rememberedCell(Engine e, long scene, String cell) {
		return e.db().read(tx -> m(m(tx
				.queryOne("SELECT knowledge_json FROM map_observation WHERE scene_id=? AND observer_key='party'", scene)
				.orElseThrow().map("knowledge_json").get("cells")).get(cell)));
	}

	private static Map<String, Object> twoRooms(Engine e, String campaign, Map<String, Object> door) {
		return map("name", "Two rooms", "location_ref", opening(e, campaign), "room_count", 2, "geometry",
				map("bounds_ft", List.of(0, 0, 40, 20)), "rooms",
				List.of(map("key", "west", "rect_ft", List.of(0, 0, 20, 20)),
						map("key", "east", "rect_ft", List.of(20, 0, 20, 20))),
				"connections", List.of(door));
	}

	private static String opening(Engine e, String campaign) {
		return e.db().read(tx -> Ref.of(Ref.LOCATION,
				tx.get("campaign", Ref.id(campaign, Ref.CAMPAIGN)).lng("current_location_id")));
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> m(Object v) {
		return v == null ? Map.of() : (Map<String, Object>) v;
	}

	@SuppressWarnings("unchecked")
	private static List<Map<String, Object>> list(Object v) {
		return (List<Map<String, Object>>) v;
	}
}
