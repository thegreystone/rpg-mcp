/*
 * Copyright (C) 2026 Marcus Hirt
 *
 * This software is free:
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions
 * are met:
 *
 * 1. Redistributions of source code must retain the above copyright
 *    notice, this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright
 *    notice, this list of conditions and the following disclaimer in the
 *    documentation and/or other materials provided with the distribution.
 * 3. The name of the author may not be used to endorse or promote products
 *    derived from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE AUTHOR ``AS IS'' AND ANY EXPRESSED OR
 * IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES
 * OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED.
 * IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR ANY DIRECT, INDIRECT,
 * INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT
 * NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
 * THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF
 * THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package se.hirt.mcp.rpg;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import se.hirt.mcp.rpg.persistence.Database;
import se.hirt.mcp.rpg.protocol.ErrorCode;
import se.hirt.mcp.rpg.protocol.Json;
import se.hirt.mcp.rpg.protocol.Ref;
import se.hirt.mcp.rpg.protocol.RpgException;
import se.hirt.mcp.rpg.world.WorldService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static se.hirt.mcp.rpg.TestCampaigns.committedCampaign;
import static se.hirt.mcp.rpg.TestCampaigns.engine;
import static se.hirt.mcp.rpg.TestCampaigns.map;
import static se.hirt.mcp.rpg.TestCampaigns.op;
import static se.hirt.mcp.rpg.TestCampaigns.tempDb;

class MaterializeLocationTest {

	@Test
	void repeatingAnExtendedChildSpecReusesTheSameLocation() throws Exception {
		try (Engine engine = engine(tempDb("materialize-repeat-child"))) {
			String campaign = committedCampaign(engine);
			engine.sessions().bootstrap(op(), campaign, null);
			String origin = (String) m(engine.sessions().party(campaign, "SUMMARY").get("location")).get("ref");
			Map<String, Object> spec = map("extend", true, "children",
					List.of(map("name", "Harbour District", "kind", "DISTRICT")));
			Map<String, Object> first = engine.world().materialize(op(), campaign, origin, spec, "GM");
			String childRef = (String) list(first.get("new_children")).getFirst().get("ref");
			Map<String, Object> repeated = engine.world().materialize(op(), campaign, origin, spec, "GM");
			List<Map<String, Object>> children = list(repeated.get("children"));
			assertEquals(childRef, children.getFirst().get("ref"));
			assertEquals(1, children.size(), "a later operation extending the same child must not duplicate it");
			assertTrue(list(repeated.get("new_children")).isEmpty(),
					"only newly inserted children are reported as new");
		}
	}

	@Test
	void namesKindAliasesAndEmptyTagsReuseOneChildEvenWithinTheSameBatch() throws Exception {
		try (Engine engine = engine(tempDb("materialize-normalized-children"))) {
			Fixture fixture = fixture(engine);
			Map<String, Object> materialized = children(fixture,
					List.of(map("name", "  Old Cellar  ", "kind", "ROOM", "description", "Stone stairs", "connection",
							false),
							map("name", "old cellar", "kind", "AREA", "description", "Stone stairs", "tags", List.of(),
									"connection", false),
							map("name", "OLD CELLAR", "connection", false)));
			assertEquals(1, list(materialized.get("new_children")).size());
			assertEquals(1, list(materialized.get("children")).size());
			String cellar = (String) list(materialized.get("children")).getFirst().get("ref");
			Map<String, Object> before = location(engine, cellar);
			assertEquals("Old Cellar", before.get("name"));
			assertEquals("AREA", before.get("kind"));
			assertEquals("Stone stairs", before.get("description"));
			Map<String, Object> repeated = children(fixture, List.of(
					map("name", " old cellar ", "kind", null, "description", null, "tags", null, "connection", false)));
			assertTrue(list(repeated.get("new_children")).isEmpty());
			assertEquals(before, location(engine, cellar), "equivalent or omitted details never rewrite the child row");
			assertNull(edge(fixture, cellar));
		}
	}

	@Test
	void matchingNamesUnderDifferentParentsOrCampaignsRemainDifferentLocations() throws Exception {
		try (Engine engine = engine(tempDb("materialize-child-scope"))) {
			Fixture first = fixture(engine);
			String hall = createChild(first, "Hall");
			String upperWard = newParent(first, "Upper Ward");
			Fixture upper = new Fixture(engine, first.campaign, upperWard);
			String upperHall = createChild(upper, "Hall");
			Fixture otherCampaign = fixture(engine);
			String otherHall = createChild(otherCampaign, "Hall");
			assertNotEquals(hall, upperHall);
			assertNotEquals(hall, otherHall);
			assertNotEquals(upperHall, otherHall);
			assertEquals(Ref.id(first.origin, Ref.LOCATION),
					((Number) location(engine, hall).get("parent_id")).longValue());
			assertEquals(Ref.id(upperWard, Ref.LOCATION),
					((Number) location(engine, upperHall).get("parent_id")).longValue());
			assertEquals(Ref.id(otherCampaign.campaign, Ref.CAMPAIGN),
					((Number) location(engine, otherHall).get("campaign_id")).longValue());
			assertEquals(2L, namedLocationCount(first, "Hall"));
			assertEquals(1L, namedLocationCount(otherCampaign, "Hall"));
		}
	}

	@Test
	void reusedMaterializedChildrenKeepTheirDetailsAndContradictionsRollBackTheWholeBatch() throws Exception {
		try (Engine engine = engine(tempDb("materialize-child-contradiction"))) {
			Fixture fixture = fixture(engine);
			Map<String, Object> first = children(fixture, List.of(map("name", "Hall", "kind", "BUILDING", "description",
					"An old stone hall", "tags", List.of("stone"))));
			String hall = (String) list(first.get("new_children")).getFirst().get("ref");
			engine.world().materialize(op(), fixture.campaign, hall, map("description", "An old stone hall", "features",
					List.of(map("name", "Lantern", "description", "A brass lantern"))), "GM");
			Map<String, Object> beforeChild = location(engine, hall);
			Map<String, Object> beforeDetail = detail(engine, hall);
			children(fixture, List.of(map("name", "hall")));
			children(fixture, List.of(map("ref", hall, "name", " HALL ", "kind", "BUILDING", "description",
					"An old stone hall", "tags", List.of("stone"))));
			assertEquals(beforeChild, location(engine, hall));
			assertEquals(beforeDetail, detail(engine, hall));
			for (Map<String, Object> contradiction : List.of(map("kind", "SITE"), map("description", "A wooden shack"),
					map("tags", List.of("wood")), map("ref", hall, "name", "A different hall"))) {
				Map<String, List<Map<String, Object>>> before = snapshot(fixture);
				var conflictingChild = new LinkedHashMap<>(map("name", "Hall"));
				conflictingChild.putAll(contradiction);
				RpgException rejected = assertThrows(RpgException.class,
						() -> children(fixture, List.of(map("name", "Uncommitted annex"), conflictingChild)));
				assertEquals(ErrorCode.VALIDATION_FAILED, rejected.code());
				assertTrue(String.valueOf(rejected.details().get("violations")).contains("CONTRADICTION"));
				assertEquals(before, snapshot(fixture), "the earlier annex insertion and all parent writes roll back");
			}
			assertEquals(beforeChild, location(engine, hall));
		}
	}

	@Test
	void childConnectionsPreserveKnownGeographyAndOnlyExplicitKindsChangeIt() throws Exception {
		try (Engine engine = engine(tempDb("materialize-child-edge"))) {
			Fixture fixture = fixture(engine);
			Map<String, Object> first = children(fixture, List.of(map("name", "Hall", "connection", false)));
			String hall = (String) list(first.get("new_children")).getFirst().get("ref");
			assertNull(edge(fixture, hall));
			Map<String, Object> connected = children(fixture, List.of(map("name", "Hall")));
			assertTrue(list(connected.get("new_children")).isEmpty());
			Map<String, Object> passage = edge(fixture, hall);
			assertEquals("PASSAGE", passage.get("kind"));
			assertEquals(Map.of("state", "OPEN", "visibility", "PARTY_KNOWN"),
					Json.readMap((String) passage.get("state_json")));
			assertEquals(Map.of("travel_minutes", 5), Json.readMap((String) passage.get("distance_json")));
			engine.world()
					.materialize(op(), fixture.campaign, fixture.origin,
							map("extend", true, "connections", List.of(map("to", hall, "kind", "DOOR", "state",
									"LOCKED", "visibility", "GM_ONLY", "travel_minutes", 17, "distance_miles", 0.2))),
							"GM");
			long edgeId = ((Number) edge(fixture, hall).get("id")).longValue();
			engine.db().mutate(fixtureMutation(fixture), tx -> {
				var stored = tx.get("location_connection", edgeId);
				Map<String, Object> state = stored.map("state_json");
				state.put("lock_reason", "An iron bar");
				Map<String, Object> distance = stored.map("distance_json");
				distance.put("terrain", "Steep stairs");
				tx.update("location_connection", edgeId,
						map("state_json", Json.write(state), "distance_json", Json.write(distance)));
				return Map.of();
			});
			Map<String, Object> known = edge(fixture, hall);
			children(fixture, List.of(map("name", "Hall")));
			assertEquals(known, edge(fixture, hall));
			children(fixture, List.of(map("name", "Hall", "connection", false)));
			assertEquals(known, edge(fixture, hall), "connection:false does not delete or reopen a known edge");
			children(fixture, List.of(map("name", "Hall", "connection_kind", "STAIRS")));
			var changed = new LinkedHashMap<>(known);
			changed.put("kind", "STAIRS");
			assertEquals(changed, edge(fixture, hall));
		}
	}

	@Test
	void embeddedConnectionDestinationsReuseNamesWithinTheirDeclaredParentScope() throws Exception {
		try (Engine engine = engine(tempDb("materialize-destination-reuse"))) {
			Fixture fixture = fixture(engine);
			Map<String, Object> first = engine.world().materialize(op(), fixture.campaign, fixture.origin, map(
					"connections",
					List.of(map("to", map("name", "Old Watchtower", "kind", "SITE", "description", "Stone walls"),
							"kind", "ROAD", "state", "BLOCKED", "visibility", "GM_ONLY", "travel_minutes", 240))),
					"GM");
			String tower = (String) list(first.get("new_connections")).getFirst().get("to");
			Map<String, Object> beforeTower = location(engine, tower);
			Map<String, Object> beforeRoad = edge(fixture, tower);
			Map<String, Object> repeated = engine.world()
					.materialize(op(), fixture.campaign, fixture.origin,
							map("extend", true, "connections",
									List.of(map("to", map("name", " old watchtower ")), map("to", map("ref", tower)))),
							"GM");
			assertEquals(List.of(tower, tower),
					list(repeated.get("new_connections")).stream().map(c -> c.get("to")).toList());
			assertEquals(beforeTower, location(engine, tower));
			assertEquals(beforeRoad, edge(fixture, tower));
			Map<String, Object> nested = engine.world().materialize(op(), fixture.campaign, fixture.origin,
					map("extend", true, "connections",
							List.of(map("to", map("name", "Old Watchtower", "parent", fixture.origin)))),
					"GM");
			String childTower = (String) list(nested.get("new_connections")).getFirst().get("to");
			assertNotEquals(tower, childTower);
			assertEquals(Ref.id(fixture.origin, Ref.LOCATION),
					((Number) location(engine, childTower).get("parent_id")).longValue());
			Map<String, Object> nestedAgain = engine.world().materialize(op(), fixture.campaign, fixture.origin,
					map("extend", true, "connections",
							List.of(map("to", map("name", "OLD WATCHTOWER", "parent", fixture.origin)))),
					"GM");
			assertEquals(childTower, list(nestedAgain.get("new_connections")).getFirst().get("to"));
			assertEquals(2L, namedLocationCount(fixture, "Old Watchtower"));
		}
	}

	@Test
	void legacyDuplicateNamesArePreservedAndExplicitReferencesResolveTheAmbiguity() throws Exception {
		try (Engine engine = engine(tempDb("materialize-legacy-duplicates"))) {
			Fixture fixture = fixture(engine);
			String firstHall = createChild(fixture, "Hall");
			Map<String, Object> duplicate = engine.db().mutate(fixtureMutation(fixture), tx -> {
				long id = WorldService.insertLocation(tx, Ref.id(fixture.campaign, Ref.CAMPAIGN), " hall ", "AREA",
						"A distinct legacy hall", Ref.id(fixture.origin, Ref.LOCATION), "SEMANTIC", null);
				return map("ref", Ref.of(Ref.LOCATION, id));
			});
			String secondHall = (String) duplicate.get("ref");
			Map<String, Object> firstRow = location(engine, firstHall), secondRow = location(engine, secondHall);
			Map<String, List<Map<String, Object>>> before = snapshot(fixture);
			RpgException ambiguous = assertThrows(RpgException.class,
					() -> children(fixture, List.of(map("name", "Hall"))));
			assertEquals(ErrorCode.INVALID_ARGUMENT, ambiguous.code());
			String candidates = String.valueOf(ambiguous.details().get("candidates"));
			assertTrue(candidates.contains(firstHall));
			assertTrue(candidates.contains(secondHall));
			assertEquals(before, snapshot(fixture), "legacy duplicates and their history are not merged or rewritten");
			Map<String, Object> selected = children(fixture, List.of(map("ref", firstHall, "connection", false)));
			assertTrue(list(selected.get("new_children")).isEmpty());
			assertEquals(2, list(selected.get("children")).size());
			assertEquals(firstRow, location(engine, firstHall));
			assertEquals(secondRow, location(engine, secondHall));
		}
	}

	@Test
	void childReferencesRequireTheirParentAndCampaignWhileConnectionReferencesMayCrossParents() throws Exception {
		try (Engine engine = engine(tempDb("materialize-reference-scope"))) {
			Fixture fixture = fixture(engine);
			String upperWard = newParent(fixture, "Upper Ward");
			String upperHall = createChild(new Fixture(engine, fixture.campaign, upperWard), "Hall");
			Fixture other = fixture(engine);
			String foreignHall = createChild(other, "Hall");
			for (Map<String, Object> invalid : List.of(map("ref", upperHall), map("ref", foreignHall), map("name", 42),
					map("name", "  "), map("name", "Misparented child", "parent", upperWard),
					map("name", "Foreign parent", "parent", other.origin))) {
				Map<String, List<Map<String, Object>>> before = snapshot(fixture);
				RpgException rejected = assertThrows(RpgException.class, () -> children(fixture, List.of(invalid)));
				assertEquals(ErrorCode.INVALID_ARGUMENT, rejected.code());
				assertEquals(before, snapshot(fixture));
			}
			Map<String, Object> connected = engine.world().materialize(op(), fixture.campaign, fixture.origin,
					map("extend", true, "connections", List.of(map("to", map("ref", upperHall)))), "GM");
			assertEquals(upperHall, list(connected.get("new_connections")).getFirst().get("to"));
			Map<String, List<Map<String, Object>>> before = snapshot(fixture);
			RpgException wrongParent = assertThrows(RpgException.class,
					() -> engine.world().materialize(op(), fixture.campaign, fixture.origin, map("extend", true,
							"connections", List.of(map("to", map("ref", upperHall, "parent", fixture.origin)))), "GM"));
			assertEquals(ErrorCode.INVALID_ARGUMENT, wrongParent.code());
			assertEquals(before, snapshot(fixture));
			Map<String, Object> agreedParent = engine.world().materialize(op(), fixture.campaign, fixture.origin,
					map("extend", true, "connections", List.of(map("to", map("ref", upperHall, "parent", upperWard)))),
					"GM");
			assertEquals(upperHall, list(agreedParent.get("new_connections")).getFirst().get("to"));
		}
	}

	@Test
	void replayCheckpointRestorationAndReopenKeepChildIdentityConsistent() throws Exception {
		Path database = tempDb("materialize-child-reopen");
		String campaign, origin, recreatedChild;
		try (Engine engine = engine(database)) {
			Fixture fixture = fixture(engine);
			campaign = fixture.campaign;
			origin = fixture.origin;
			String checkpoint = (String) engine.checkpoints().create(op(), campaign, "before map details")
					.get("checkpoint");
			String operation = op();
			Map<String, Object> spec = map("extend", true, "children", List.of(map("name", "Hall")));
			Map<String, Object> first = engine.world().materialize(operation, campaign, origin, spec, "GM");
			String firstChild = (String) list(first.get("new_children")).getFirst().get("ref");
			Map<String, List<Map<String, Object>>> after = snapshot(fixture);
			Map<String, Object> replay = engine.world().materialize(operation, campaign, origin, spec, "GM");
			assertEquals(Boolean.TRUE, replay.get("replayed"));
			assertEquals(first.get("new_children"), replay.get("new_children"),
					"replay returns the original insertion result");
			assertEquals(after, snapshot(fixture));
			assertTrue(list(children(fixture, List.of(map("name", "Hall"))).get("new_children")).isEmpty());
			createChild(fixture, "Annex");
			engine.checkpoints().restore(op(), campaign, checkpoint, "undo map expansion");
			assertTrue(list(detail(engine, origin).get("children")).isEmpty());
			recreatedChild = createChild(fixture, "Hall");
			assertNotEquals(firstChild, recreatedChild,
					"restoring removes the old insertion; a new row receives a new reference");
		}
		try (Engine reopened = engine(database)) {
			reopened.sessions().bootstrap(op(), campaign, null);
			Fixture fixture = new Fixture(reopened, campaign, origin);
			Map<String, Object> repeated = children(fixture, List.of(map("name", " hall ")));
			assertTrue(list(repeated.get("new_children")).isEmpty());
			assertEquals(1, list(repeated.get("children")).size());
			assertEquals(recreatedChild, list(repeated.get("children")).getFirst().get("ref"));
			assertEquals(1L, namedLocationCount(fixture, "Hall"));
		}
	}

	private record Fixture(Engine engine, String campaign, String origin) {
	}

	private static Fixture fixture(Engine engine) {
		String campaign = committedCampaign(engine);
		engine.sessions().bootstrap(op(), campaign, null);
		String origin = (String) m(engine.sessions().party(campaign, "SUMMARY").get("location")).get("ref");
		return new Fixture(engine, campaign, origin);
	}

	private static Map<String, Object> children(Fixture fixture, List<Map<String, Object>> specs) {
		return fixture.engine.world().materialize(op(), fixture.campaign, fixture.origin,
				map("extend", true, "children", specs), "GM");
	}

	private static String createChild(Fixture fixture, String name) {
		return (String) list(children(fixture, List.of(map("name", name))).get("new_children")).getFirst().get("ref");
	}

	private static String newParent(Fixture fixture, String name) {
		return (String) fixture.engine.world().materialize(op(), fixture.campaign, null,
				map("name", name, "kind", "DISTRICT", "parent", fixture.origin), "GM").get("ref");
	}

	private static Map<String, Object> location(Engine engine, String ref) {
		return engine.db().read(tx -> tx.get("location", Ref.id(ref, Ref.LOCATION)).asMap());
	}

	private static Map<String, Object> detail(Engine engine, String ref) {
		return engine.db().read(tx -> WorldService.detail(tx, tx.get("location", Ref.id(ref, Ref.LOCATION))));
	}

	private static Map<String, Object> edge(Fixture fixture, String other) {
		long a = Ref.id(fixture.origin, Ref.LOCATION), b = Ref.id(other, Ref.LOCATION);
		return fixture.engine.db().read(tx -> tx.queryOne(
				"SELECT * FROM location_connection WHERE campaign_id = ? AND location_a_id = ? AND location_b_id = ?",
				Ref.id(fixture.campaign, Ref.CAMPAIGN), Math.min(a, b), Math.max(a, b)).map(r -> r.asMap())
				.orElse(null));
	}

	private static long namedLocationCount(Fixture fixture, String name) {
		return fixture.engine.db()
				.read(tx -> tx.count(
						"SELECT COUNT(*) FROM location WHERE campaign_id = ? AND LOWER(TRIM(name)) = LOWER(TRIM(?))",
						Ref.id(fixture.campaign, Ref.CAMPAIGN), name));
	}

	private static Map<String, List<Map<String, Object>>> snapshot(Fixture fixture) {
		return fixture.engine.db().snapshot(Ref.id(fixture.campaign, Ref.CAMPAIGN));
	}

	private static Database.Mutation fixtureMutation(Fixture fixture) {
		return Database.Mutation.of("materialize_legacy_fixture", Ref.id(fixture.campaign, Ref.CAMPAIGN), op(), "GM",
				null);
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
