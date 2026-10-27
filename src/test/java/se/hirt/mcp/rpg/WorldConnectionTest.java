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

import org.junit.jupiter.api.Test;
import se.hirt.mcp.rpg.persistence.Database;
import se.hirt.mcp.rpg.protocol.ErrorCode;
import se.hirt.mcp.rpg.protocol.Json;
import se.hirt.mcp.rpg.protocol.Ref;
import se.hirt.mcp.rpg.protocol.RpgException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static se.hirt.mcp.rpg.TestCampaigns.map;
import static se.hirt.mcp.rpg.TestCampaigns.op;

/** Extending geography updates only the supplied fields of an existing connection. */
class WorldConnectionTest {

	@Test
	void stateOnlyExtensionPreservesKnownRoadDetails() throws Exception {
		try (Engine engine = TestCampaigns.engine(TestCampaigns.tempDb("connection-state-patch"))) {
			String campaign = TestCampaigns.committedCampaign(engine);
			engine.sessions().bootstrap(op(), campaign, null);
			String origin = (String) m(engine.sessions().party(campaign, "SUMMARY").get("location")).get("ref");
			Map<String, Object> materialized = engine.world().materialize(op(), campaign, origin,
					map("connections",
							List.of(map("to", map("name", "Old Watchtower", "kind", "SITE"), "kind", "ROAD", "state",
									"OPEN", "visibility", "GM_ONLY", "travel_minutes", 240, "distance_miles", 12))),
					"GM");
			String destination = (String) list(materialized.get("new_connections")).getFirst().get("to");

			Map<String, Object> extended = engine.world().materialize(op(), campaign, origin,
					map("extend", true, "connections", List.of(map("to", destination, "state", "BLOCKED"))), "GM");

			Map<String, Object> road = list(extended.get("new_connections")).getFirst();
			assertEquals("BLOCKED", road.get("state"));
			assertEquals(240, ((Number) road.get("travel_minutes")).intValue());
			assertEquals("ROAD", road.get("kind"));
			assertEquals(12, ((Number) road.get("distance_miles")).intValue());
			assertEquals("GM_ONLY", road.get("visibility"));
		}
	}

	@Test
	void reversedEndpointsAndNullFieldsKeepOneConnectionAndItsKnownDetails() throws Exception {
		try (Engine engine = TestCampaigns.engine(TestCampaigns.tempDb("connection-reversed-patch"))) {
			Fixture fixture = knownRoad(engine, "BLOCKED", "GM_ONLY");
			long originalId = connectionId(fixture);
			Map<String, Object> reversed = engine.world().materialize(op(), fixture.campaign, fixture.destination,
					map("connections", List.of(map("to", fixture.origin, "kind", "TRAIL"))), "GM");
			Map<String, Object> trail = list(reversed.get("new_connections")).getFirst();
			assertEquals(fixture.origin, trail.get("to"));
			assertConnection(trail, "TRAIL", "BLOCKED", "GM_ONLY", 240, 12);

			Map<String, Object> unchanged = patch(fixture, map("kind", null, "state", null, "visibility", null,
					"travel_minutes", null, "distance_miles", null));
			assertConnection(unchanged, "TRAIL", "BLOCKED", "GM_ONLY", 240, 12);
			assertEquals(originalId, connectionId(fixture), "both endpoint orders update the existing edge");
			long edgeCount = engine.db().read(tx -> tx.count(
					"SELECT COUNT(*) FROM location_connection WHERE campaign_id=? AND location_a_id=? AND location_b_id=?",
					campaignId(fixture),
					Math.min(Ref.id(fixture.origin, "location"), Ref.id(fixture.destination, "location")),
					Math.max(Ref.id(fixture.origin, "location"), Ref.id(fixture.destination, "location"))));
			assertEquals(1L, edgeCount);
			assertEquals(1, list(location(fixture).get("connections")).stream()
					.filter(c -> fixture.destination.equals(c.get("to"))).count());
		}
	}

	@Test
	void timeAndDistancePatchesPreserveOtherFieldsAndStoredMetadata() throws Exception {
		try (Engine engine = TestCampaigns.engine(TestCampaigns.tempDb("connection-distance-patch"))) {
			Fixture fixture = knownRoad(engine, "BLOCKED", "GM_ONLY");
			long edgeId = connectionId(fixture);
			engine.db().mutate(Database.Mutation.of("test_connection_metadata", campaignId(fixture), op(), "GM", null),
					tx -> {
						var edge = tx.get("location_connection", edgeId);
						var state = edge.map("state_json");
						state.put("block_reason", "Fallen masonry");
						var distance = edge.map("distance_json");
						distance.put("terrain_note", "Old paved road");
						tx.update("location_connection", edgeId,
								map("state_json", Json.write(state), "distance_json", Json.write(distance)));
						return map("ok", true);
					});

			assertConnection(patch(fixture, map("travel_minutes", 300)), "ROAD", "BLOCKED", "GM_ONLY", 300, 12);
			assertConnection(patch(fixture, map("distance_miles", 15.5)), "ROAD", "BLOCKED", "GM_ONLY", 300, 15.5);
			assertConnection(road(fixture), "ROAD", "BLOCKED", "GM_ONLY", 300, 15.5);
			var persisted = engine.db().read(tx -> tx.get("location_connection", edgeId));
			assertEquals("Fallen masonry", persisted.map("state_json").get("block_reason"));
			assertEquals("Old paved road", persisted.map("distance_json").get("terrain_note"));
		}
	}

	@Test
	void explicitlySuppliedConnectionFieldsReplaceKnownValues() throws Exception {
		try (Engine engine = TestCampaigns.engine(TestCampaigns.tempDb("connection-explicit-patch"))) {
			Fixture fixture = knownRoad(engine, "BLOCKED", "GM_ONLY");
			Map<String, Object> changed = patch(fixture, map("kind", "trail", "state", "open", "visibility",
					"party_known", "travel_minutes", 90, "distance_miles", 4.5));
			assertConnection(changed, "TRAIL", "OPEN", "PARTY_KNOWN", 90, 4.5);
			assertConnection(road(fixture), "TRAIL", "OPEN", "PARTY_KNOWN", 90, 4.5);
		}
	}

	@Test
	void newConnectionStillReceivesLegacyDefaults() throws Exception {
		try (Engine engine = TestCampaigns.engine(TestCampaigns.tempDb("connection-defaults"))) {
			String campaign = TestCampaigns.committedCampaign(engine);
			engine.sessions().bootstrap(op(), campaign, null);
			String origin = (String) m(engine.sessions().party(campaign, "SUMMARY").get("location")).get("ref");
			Map<String, Object> created = engine.world().materialize(op(), campaign, origin,
					map("connections", List.of(map("to", map("name", "River Landing", "kind", "SITE")))), "GM");
			Map<String, Object> connection = list(created.get("new_connections")).getFirst();
			assertEquals("PATH", connection.get("kind"));
			assertEquals("OPEN", connection.get("state"));
			assertEquals("PARTY_KNOWN", connection.get("visibility"));
			assertEquals(60, ((Number) connection.get("travel_minutes")).intValue());
			assertFalse(connection.containsKey("distance_miles"));
			Map<String, Object> moved = engine.world().move(op(), campaign, (String) connection.get("to"), null, false,
					null, null);
			assertEquals(60L, moved.get("travel_minutes"));
		}
	}

	@Test
	void reopeningRoadKeepsTravelTimeAndMoveRetryDoesNotTravelAgain() throws Exception {
		try (Engine engine = TestCampaigns.engine(TestCampaigns.tempDb("connection-reopened-travel"))) {
			Fixture fixture = knownRoad(engine, "OPEN", "PARTY_KNOWN");
			long before = clock(fixture);
			patch(fixture, map("state", "BLOCKED"));
			RpgException blocked = assertThrows(RpgException.class,
					() -> engine.world().move(op(), fixture.campaign, fixture.destination, null, false, null, null));
			assertEquals(ErrorCode.VALIDATION_FAILED, blocked.code());
			assertEquals(before, clock(fixture), "a blocked road costs no travel time");
			assertEquals(fixture.origin,
					m(engine.sessions().party(fixture.campaign, "SUMMARY").get("location")).get("ref"));
			patch(fixture, map("state", "OPEN"));

			String moveOperation = op();
			Map<String, Object> moved = engine.world().move(moveOperation, fixture.campaign, fixture.destination, null,
					false, null, "The road has reopened.");
			assertEquals(240L, moved.get("travel_minutes"));
			assertEquals(1, list(moved.get("route")).size());
			assertEquals("ROAD", list(moved.get("route")).getFirst().get("via"));
			assertEquals(before + 240, clock(fixture));
			long movementEvents = engine.db()
					.read(tx -> tx.count("SELECT COUNT(*) FROM event WHERE campaign_id = ? AND type = 'PARTY_MOVED'",
							campaignId(fixture)));
			assertEquals(1L, movementEvents);
			Map<String, Object> replayed = engine.world().move(moveOperation, fixture.campaign, fixture.destination,
					null, false, null, "The road has reopened.");
			assertEquals(moved.get("event"), replayed.get("event"));
			assertEquals(before + 240, clock(fixture));
			long replayedEvents = engine.db()
					.read(tx -> tx.count("SELECT COUNT(*) FROM event WHERE campaign_id = ? AND type = 'PARTY_MOVED'",
							campaignId(fixture)));
			assertEquals(movementEvents, replayedEvents);
		}
	}

	@Test
	void invalidPatchRollsBackAndCheckpointRestoresOriginalRoad() throws Exception {
		try (Engine engine = TestCampaigns.engine(TestCampaigns.tempDb("connection-patch-restore"))) {
			Fixture fixture = knownRoad(engine, "BLOCKED", "GM_ONLY");
			String checkpoint = (String) engine.checkpoints().create(op(), fixture.campaign, "ROAD_REPAIR")
					.get("checkpoint");
			var before = engine.db().snapshot(campaignId(fixture));
			RpgException invalid = assertThrows(RpgException.class,
					() -> engine.world()
							.materialize(op(), fixture.campaign, fixture.origin,
									map("extend", true, "description", "This edit must roll back.", "connections",
											List.of(map("to", fixture.destination, "state", "OPEN", "travel_minutes",
													90), map("to", fixture.destination, "kind", "INVALID_KIND"))),
									"GM"));
			assertEquals(ErrorCode.INVALID_ARGUMENT, invalid.code());
			var afterFailure = engine.db().snapshot(campaignId(fixture));
			assertEquals(before.get("location"), afterFailure.get("location"));
			assertEquals(before.get("location_connection"), afterFailure.get("location_connection"),
					"a later invalid connection rolls back the earlier valid patch in the same request");

			assertConnection(patch(fixture, map("state", "OPEN", "travel_minutes", 90)), "ROAD", "OPEN", "GM_ONLY", 90,
					12);
			engine.checkpoints().restore(op(), fixture.campaign, checkpoint, "Undo the attempted road repair.");
			var restored = engine.db().snapshot(campaignId(fixture));
			assertEquals(before.get("location"), restored.get("location"));
			assertEquals(before.get("location_connection"), restored.get("location_connection"));
			engine.sessions().bootstrap(op(), fixture.campaign, null);
			assertConnection(road(fixture), "ROAD", "BLOCKED", "GM_ONLY", 240, 12);
		}
	}

	private static Fixture knownRoad(Engine engine, String state, String visibility) {
		String campaign = TestCampaigns.committedCampaign(engine);
		engine.sessions().bootstrap(op(), campaign, null);
		String origin = (String) m(engine.sessions().party(campaign, "SUMMARY").get("location")).get("ref");
		Map<String, Object> materialized = engine.world().materialize(op(), campaign, origin,
				map("connections", List.of(map("to", map("name", "Old Watchtower", "kind", "SITE"), "kind", "ROAD",
						"state", state, "visibility", visibility, "travel_minutes", 240, "distance_miles", 12))),
				"GM");
		String destination = (String) list(materialized.get("new_connections")).getFirst().get("to");
		return new Fixture(engine, campaign, origin, destination);
	}

	private static Map<String, Object> patch(Fixture fixture, Map<String, Object> fields) {
		var connection = new LinkedHashMap<>(fields);
		connection.put("to", fixture.destination);
		Map<String, Object> extended = fixture.engine.world().materialize(op(), fixture.campaign, fixture.origin,
				map("extend", true, "connections", List.of(connection)), "GM");
		return list(extended.get("new_connections")).getFirst();
	}

	private static Map<String, Object> location(Fixture fixture) {
		return fixture.engine.narrative().context(fixture.campaign, "LOCATION", fixture.origin, null, null, null);
	}

	private static void assertConnection(
		Map<String, Object> connection, String kind, String state, String visibility, int minutes, double miles) {
		assertEquals(kind, connection.get("kind"));
		assertEquals(state, connection.get("state"));
		assertEquals(visibility, connection.get("visibility"));
		assertEquals(minutes, ((Number) connection.get("travel_minutes")).intValue());
		assertEquals(miles, ((Number) connection.get("distance_miles")).doubleValue());
	}

	private static long campaignId(Fixture fixture) {
		return Ref.id(fixture.campaign, Ref.CAMPAIGN);
	}

	private static long connectionId(Fixture fixture) {
		return fixture.engine.db()
				.read(tx -> tx.queryOne(
						"SELECT * FROM location_connection WHERE campaign_id=? AND location_a_id=? AND location_b_id=?",
						campaignId(fixture),
						Math.min(Ref.id(fixture.origin, "location"), Ref.id(fixture.destination, "location")),
						Math.max(Ref.id(fixture.origin, "location"), Ref.id(fixture.destination, "location")))
						.orElseThrow().id());
	}

	private static Map<String, Object> road(Fixture fixture) {
		return list(location(fixture).get("connections")).stream().filter(c -> fixture.destination.equals(c.get("to")))
				.findFirst().orElseThrow();
	}

	private static long clock(Fixture fixture) {
		return ((Number) m(fixture.engine.sessions().party(fixture.campaign, "SUMMARY").get("game_time"))
				.get("sequence")).longValue();
	}

	private record Fixture(Engine engine, String campaign, String origin, String destination) {
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
