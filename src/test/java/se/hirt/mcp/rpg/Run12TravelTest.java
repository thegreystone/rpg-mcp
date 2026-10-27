/* Copyright (C) 2026 Marcus Hirt. SPDX-License-Identifier: BSD-3-Clause */
package se.hirt.mcp.rpg;

import org.junit.jupiter.api.Test;
import se.hirt.mcp.rpg.maps.SceneService;
import se.hirt.mcp.rpg.protocol.Json;
import se.hirt.mcp.rpg.protocol.Ref;
import se.hirt.mcp.rpg.session.GameTime;
import se.hirt.mcp.rpg.world.TravelJourneyService;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static se.hirt.mcp.rpg.TestCampaigns.*;

class Run12TravelTest {

	@Test
	void realBuildingArrivalThroughANormalClosedStreetDoorHasPresenceWithoutOpeningOrPlacingAnyone() throws Exception {
		var file = tempDb("run12-closed-street-door-arrival");
		String campaign, ally, inn, taproom, board, arrivalOperation = op();
		Map<String, Object> arrival, playerFigure;
		long arrivalClock;
		try (Engine e = engine(file)) {
			var f = tavern(e);
			campaign = f.campaign();
			inn = f.inn();
			taproom = f.rooms().get("room:taproom").toString();
			board = f.board();
			String mine = e.world()
					.materialize(op(), campaign, null,
							map("name", "Mine", "kind", "SITE", "connections",
									List.of(map("to", inn, "kind", "ROAD", "travel_minutes", 50))),
							"GM")
					.get("ref").toString();
			ally = companion(e, campaign, "Oddny returning to the Pick", mine);
			playerFigure = figure(e, campaign, "character:1");
			long before = clock(e, campaign);
			arrival = e.world().move(arrivalOperation, campaign, inn, List.of(ally), false, null,
					"Oddny returns to the inn while Tam remains in its taproom.");
			arrivalClock = clock(e, campaign);
			assertEquals(before + 3000, arrivalClock);
			assertEquals(inn, location(e, ally));
			assertEquals(taproom, location(e, "character:1"));
			assertPresence(e, campaign, ally, true);
			assertEquals(playerFigure, figure(e, campaign, "character:1"));
			assertNull(figure(e, campaign, ally));
			assertEquals("CLOSED", streetDoor(e, campaign, board).get("state"));
			assertEquals("GM_ONLY", streetDoor(e, campaign, board).get("visibility"),
					"Published passage metadata is GM authoring data, not physical concealment.");
		}
		try (Engine e = engine(file)) {
			assertPresence(e, campaign, ally, true);
			var snapshot = e.db().snapshot(Ref.id(campaign, Ref.CAMPAIGN));
			var replay = new LinkedHashMap<>(e.world().move(arrivalOperation, campaign, inn, List.of(ally), false, null,
					"Oddny returns to the inn while Tam remains in its taproom."));
			assertEquals(true, replay.remove("replayed"));
			assertEquals(Json.hash(arrival), Json.hash(replay));
			assertEquals(snapshot, e.db().snapshot(Ref.id(campaign, Ref.CAMPAIGN)));
			assertEquals(arrivalClock, clock(e, campaign));
			assertEquals(inn, location(e, ally));
			assertEquals(taproom, location(e, "character:1"));
			assertEquals(playerFigure, figure(e, campaign, "character:1"));
			assertNull(figure(e, campaign, ally));
			assertEquals("CLOSED", streetDoor(e, campaign, board).get("state"));
		}
	}

	@Test
	void closedDoorPresenceStillHonorsLocksSealsHiddenPassagesRoomsFloorsFiguresAndJourneys() throws Exception {
		try (Engine e = engine(tempDb("run12-closed-street-door-boundaries"))) {
			var f = tavern(e);
			String campaign = f.campaign(), ally = companion(e, campaign, "At the inn", f.inn());
			for (var boundary : List.of(map("state", "CLOSED"), map("state", "OPEN"), map("state", "LOCKED"),
					map("state", "BLOCKED"), map("state", "SECRET"), map("state", "CLOSED", "locked", true),
					map("state", "CLOSED", "sealed", true), map("state", "CLOSED", "secret", true),
					map("state", "CLOSED", "concealed", true), map("state", "OPEN", "sealed", true))) {
				var changes = map("state", "CLOSED", "locked", false, "sealed", false, "secret", false, "concealed",
						false);
				changes.putAll(boundary);
				e.scenes().changeObject(op(), campaign, f.board(), "street-door", changes,
						"Record the resolved physical entrance boundary for this case.");
				assertPresence(e, campaign, ally,
						boundary.size() == 1 && List.of("CLOSED", "OPEN").contains(boundary.get("state")));
				assertEquals(changes.get("state"), streetDoor(e, campaign, f.board()).get("state"));
			}
			e.scenes().changeObject(op(), campaign, f.board(), "street-door",
					map("state", "CLOSED", "locked", false, "sealed", false, "secret", false, "concealed", false),
					"Restore the ordinary closed street door without moving anybody.");
			String sibling = companion(e, campaign, "In the locked office", f.rooms().get("room:office").toString());
			String lower = companion(e, campaign, "In the cellar", f.rooms().get("room:cellar").toString());
			assertPresence(e, campaign, sibling, false);
			assertPresence(e, campaign, lower, false);
			e.scenes().place(op(), campaign, f.board(),
					List.of(map("character", ally, "x_ft", 25, "y_ft", 15, "level", 0)));
			var placed = figure(e, campaign, ally);
			assertPresence(e, campaign, ally, false);
			assertEquals(placed, figure(e, campaign, ally));
			String travelling = companion(e, campaign, "A separately travelling guest", f.inn());
			String mine = e.world()
					.materialize(op(), campaign, null,
							map("name", "Mine", "kind", "SITE", "connections",
									List.of(map("to", f.inn(), "kind", "ROAD", "travel_minutes", 50))),
							"GM")
					.get("ref").toString();
			new TravelJourneyService(e.db(), e.rules(), e.roller()).defineRisk(op(), campaign, "DEFAULT",
					map("frequency_seconds", 3600, "chance_percent", 0, "authored_at_fraction", .5));
			var departure = e.world().move(op(), campaign, mine, List.of(travelling), false, null,
					"Travel separately while the player remains in the taproom.");
			assertEquals("INTERRUPTED", departure.get("status"));
			assertPresence(e, campaign, travelling, false);
			assertEquals(f.inn(), location(e, travelling));
		}
	}

	private record Tavern(String campaign, String inn, String board, Map<String, Object> rooms) {
	}

	private static Tavern tavern(Engine e) {
		String campaign = committedCampaign(e);
		e.sessions().bootstrap(op(), campaign, null);
		new TravelJourneyService(e.db(), e.rules(), e.roller()).defineRisk(op(), campaign, "DEFAULT",
				map("frequency_seconds", 3600, "chance_percent", 0));
		String town = location(e, "character:1");
		String inn = e.world()
				.materialize(op(), campaign, null,
						map("name", "The Broken Pick", "kind", "BUILDING", "parent", town, "connections",
								List.of(map("to", town, "kind", "STREET", "travel_minutes", 10))),
						"GM")
				.get("ref").toString();
		String street = e.world()
				.materialize(op(), campaign, null, map("name", "Town Street", "kind", "AREA", "parent", town), "GM")
				.get("ref").toString();
		e.world().move(op(), campaign, inn, null, false, null, "The party reaches the inn normally.");
		// No ENTRY marker: infer the taproom from its actual external Town Street opening, not room order.
		var prep = e.maps()
				.begin(op(), campaign, "DUNGEON",
						map("name", "Tavern with normal closed street door", "stage", "SCENE", "location_ref", inn,
								"illumination", "BRIGHT", "levels", List.of(-1, 0), "geometry",
								map("bounds_ft", List.of(0, 0, 40, 30)), "rooms",
								List.of(map("key", "office", "level", 0, "rect_ft", List.of(20, 10, 20, 20)),
										map("key", "taproom", "level", 0, "rect_ft", List.of(0, 10, 20, 20)),
										map("key", "street", "level", 0, "location_ref", street, "rect_ft",
												List.of(0, 0, 40, 10), "enclosed", false),
										map("key", "cellar", "level", -1, "rect_ft", List.of(0, 10, 20, 20))),
								"connections",
								List.of(map("key", "street-door", "from", "taproom", "to", "street", "kind", "DOOR",
										"state", "CLOSED", "from_ft", List.of(5, 10), "to_ft", List.of(10, 10)),
										map("key", "office-door", "from", "taproom", "to", "office", "kind", "DOOR",
												"state", "LOCKED", "from_ft", List.of(20, 15), "to_ft",
												List.of(20, 20)),
										map("key", "cellar-stair", "from", "taproom", "to", "cellar", "kind", "STAIRS",
												"state", "OPEN", "from_position_ft", List.of(5, 15), "to_position_ft",
												List.of(5, 15)))));
		var published = e.maps().publish(op(), campaign, prep.get("preparation").toString(), null);
		String board = published.get("map_ref").toString();
		e.scenes().place(op(), campaign, board,
				List.of(map("character", "character:1", "x_ft", 5, "y_ft", 15, "level", 0)));
		return new Tavern(campaign, inn, board, SceneService.map(published.get("references")));
	}

	private static void assertPresence(Engine e, String campaign, String companion, boolean expected) {
		var before = e.db().snapshot(Ref.id(campaign, Ref.CAMPAIGN));
		for (String detail : List.of("SUMMARY", "PLAY", "FULL"))
			assertEquals(expected,
					member(e.sessions().party(campaign, detail).get("members"), companion).get("with_party"));
		assertEquals(expected,
				member(e.narrative().context(campaign, "SCENE", null, null, null, null).get("party"), companion)
						.get("with_party"));
		assertEquals(expected, member(
				e.narrative().context(campaign, "INTIMACY", "character:1", companion, null, null).get("partners"),
				companion).get("with_party"));
		assertEquals(before, e.db().snapshot(Ref.id(campaign, Ref.CAMPAIGN)),
				"Read-only views preserve all canonical tables, rolls and time.");
		var beforeBootstrap = domainSnapshot(e, campaign);
		assertEquals(expected,
				member(e.sessions().bootstrap(op(), campaign, null).get("party"), companion).get("with_party"));
		assertEquals(beforeBootstrap, domainSnapshot(e, campaign), "Only bootstrap campaign bookkeeping may change.");
	}

	private static Map<String, List<Map<String, Object>>> domainSnapshot(Engine e, String campaign) {
		var snapshot = e.db().snapshot(Ref.id(campaign, Ref.CAMPAIGN));
		for (var row : snapshot.get("campaign")) {
			row.remove("last_played_at");
			row.remove("revision");
		}
		return snapshot;
	}

	private static Map<String, Object> member(Object members, String ref) {
		return SceneService.maps(members).stream().filter(m -> ref.equals(m.get("ref"))).findFirst().orElseThrow();
	}

	private static Map<String, Object> streetDoor(Engine e, String campaign, String board) {
		return SceneService
				.maps(SceneService.map(e.scenes().context(campaign, board, "GM", 0).get("view")).get("doors")).stream()
				.filter(d -> "street-door".equals(d.get("key"))).findFirst().orElseThrow();
	}

	private static String companion(Engine e, String campaign, String name, String location) {
		String ally = e.runtime().materialize(op(), campaign, "Guard", name, null, null, null, location, false)
				.get("character").toString();
		e.party().updateMembership(op(), campaign, ally, "JOIN", "An established companion.");
		return ally;
	}

	private static Map<String, Object> figure(Engine e, String campaign, String character) {
		return e.db()
				.read(tx -> tx
						.queryOne("SELECT * FROM map_actor WHERE campaign_id=? AND character_id=?",
								Ref.id(campaign, Ref.CAMPAIGN), Ref.id(character, Ref.CHARACTER))
						.map(row -> row.asMap()).orElse(null));
	}

	private static String location(Engine e, String character) {
		return e.db().read(
				tx -> Ref.of(Ref.LOCATION, tx.get("character", Ref.id(character, Ref.CHARACTER)).lng("location_id")));
	}

	private static long clock(Engine e, String campaign) {
		return e.db().read(tx -> GameTime.currentSeconds(tx, Ref.id(campaign, Ref.CAMPAIGN)));
	}
}
