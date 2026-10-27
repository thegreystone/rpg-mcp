/* Copyright (C) 2026 Marcus Hirt. SPDX-License-Identifier: BSD-3-Clause */
package se.hirt.mcp.rpg;

import org.junit.jupiter.api.Test;
import se.hirt.mcp.rpg.dice.ScriptedRollService;
import se.hirt.mcp.rpg.maps.SceneService;
import se.hirt.mcp.rpg.persistence.Database;
import se.hirt.mcp.rpg.protocol.Json;
import se.hirt.mcp.rpg.protocol.Ref;
import se.hirt.mcp.rpg.session.GameTime;
import se.hirt.mcp.rpg.world.TravelJourneyService;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static se.hirt.mcp.rpg.TestCampaigns.*;

class Run11TravelTest {

	@Test
	void companionArrivalAtTheInnIsPresentedTogetherWithoutMovingEitherCanonicalLocationOrFigure() throws Exception {
		var file = tempDb("run11-entry-arrival-presence");
		String campaign, inn, taproom, ally, operation = op();
		long arrivalClock;
		Map<String, Object> playerFigure, arrival;
		try (Engine e = engine(file)) {
			Inn fixture = inn(e, "taproom");
			campaign = fixture.campaign();
			inn = fixture.inn();
			taproom = fixture.rooms().get("taproom").toString();
			String mine = site(e, campaign, "Mine", inn, 50);
			ally = companion(e, campaign, "Oddny", mine);
			playerFigure = actor(e, campaign, "character:1");
			long before = clock(e, campaign);
			arrival = e.world().move(operation, campaign, inn, List.of(ally), false, null,
					"Oddny returns to the inn containing the player's taproom.");
			arrivalClock = clock(e, campaign);
			assertEquals(before + 3000, arrivalClock);
			assertEquals(inn, location(e, ally));
			assertEquals(taproom, location(e, "character:1"));
			assertEquals(playerFigure, actor(e, campaign, "character:1"));
			assertEquals(0L, e.db().read(
					tx -> tx.count("SELECT COUNT(*) FROM map_actor WHERE character_id=?", Ref.id(ally, Ref.CHARACTER)))
					.longValue());
			var snapshot = domainSnapshot(e, campaign);
			assertPresence(e, campaign, ally, true);
			assertEquals(snapshot, domainSnapshot(e, campaign),
					"Presence views must not move an unplaced arrival through any door.");
		}
		try (Engine e = engine(file)) {
			assertPresence(e, campaign, ally, true);
			var before = e.db().snapshot(Ref.id(campaign, Ref.CAMPAIGN));
			var replay = e.world().move(operation, campaign, inn, List.of(ally), false, null,
					"Oddny returns to the inn containing the player's taproom.");
			assertEquals(true, replay.get("replayed"));
			var comparable = new LinkedHashMap<>(replay);
			comparable.remove("replayed");
			assertEquals(Json.hash(arrival), Json.hash(comparable));
			assertEquals(before, e.db().snapshot(Ref.id(campaign, Ref.CAMPAIGN)));
			assertEquals(arrivalClock, clock(e, campaign));
			assertEquals(inn, location(e, ally));
			assertEquals(taproom, location(e, "character:1"));
			assertEquals(playerFigure, actor(e, campaign, "character:1"));
		}
	}

	@Test
	void entryPresenceDoesNotGroupSiblingRoomsDifferentFloorsRegionsOrExplicitFigures() throws Exception {
		for (String partyRoom : List.of("taproom", "office", "cellar", "loft"))
			try (Engine e = engine(tempDb("run11-entry-boundaries-" + partyRoom))) {
				Inn fixture = inn(e, partyRoom);
				String campaign = fixture.campaign(),
						atBuilding = companion(e, campaign, "At the inn entrance", fixture.inn());
				var before = domainSnapshot(e, campaign);
				assertPresence(e, campaign, atBuilding, partyRoom.equals("taproom"));
				assertEquals(before, domainSnapshot(e, campaign));
				if (!partyRoom.equals("taproom"))
					continue;
				String sibling = companion(e, campaign, "In the locked office",
						fixture.rooms().get("office").toString());
				String lower = companion(e, campaign, "In the cellar", fixture.rooms().get("cellar").toString());
				String region = e.world()
						.materialize(op(), campaign, null, map("name", "Wide region", "kind", "REGION"), "GM")
						.get("ref").toString();
				String elsewhere = companion(e, campaign, "Somewhere in the region", region);
				assertPresence(e, campaign, sibling, false);
				assertPresence(e, campaign, lower, false);
				assertPresence(e, campaign, elsewhere, false);
				String marked = companion(e, campaign, "Explicitly placed in the office", fixture.inn());
				e.scenes().place(op(), campaign, fixture.map(),
						List.of(map("character", marked, "x_ft", 25, "y_ft", 5, "level", 0)));
				// A legacy parent-site canonical value must not erase an actual authored-room figure.
				e.db().mutate(Database.Mutation.of("legacy_parent_location_fixture", Ref.id(campaign, Ref.CAMPAIGN),
						op(), "GM", Map.of()), tx -> {
							tx.update("character", Ref.id(marked, Ref.CHARACTER),
									map("location_id", Ref.id(fixture.inn(), Ref.LOCATION)));
							return Map.of();
						});
				var markedFigure = actor(e, campaign, marked);
				var physical = domainSnapshot(e, campaign);
				assertPresence(e, campaign, marked, false);
				assertEquals(markedFigure, actor(e, campaign, marked));
				assertEquals(physical, domainSnapshot(e, campaign));
			}
	}

	@Test
	void blockedOrSecretExternalEntrancesAndActiveTravelKeepPresenceDistinctInEveryView() throws Exception {
		try (Engine e = engine(tempDb("run11-entrance-state-and-journey"))) {
			Inn fixture = inn(e, "taproom");
			String campaign = fixture.campaign(), ally = companion(e, campaign, "Oddny at the building", fixture.inn()),
					taproom = fixture.rooms().get("taproom").toString();
			for (String state : List.of("CLOSED", "LOCKED", "SECRET", "BLOCKED", "OPEN", "BREACHED", "DESTROYED")) {
				e.world().materialize(op(), campaign, taproom,
						map("extend", true, "connections",
								List.of(map("to", fixture.inn(), "kind", "DOOR", "state", state, "travel_minutes", 1))),
						"GM");
				var before = domainSnapshot(e, campaign);
				assertPresence(e, campaign, ally, List.of("CLOSED", "OPEN", "BREACHED", "DESTROYED").contains(state));
				assertEquals(before, domainSnapshot(e, campaign));
			}
			String mine = site(e, campaign, "Mine", fixture.inn(), 50);
			journeys(e).defineRisk(op(), campaign, "DEFAULT",
					map("authored_at_fraction", .5, "frequency_seconds", 3600, "chance_percent", 0));
			var travelling = e.world().move(op(), campaign, mine, List.of(ally), false, null,
					"Oddny travels separately while the player remains in the taproom.");
			assertEquals("INTERRUPTED", travelling.get("status"));
			assertPresence(e, campaign, ally, false);
			assertEquals(fixture.inn(), location(e, ally),
					"A presentation correction does not move the saved departure location.");
			assertEquals(taproom, location(e, "character:1"));
			var party = e.sessions().party(campaign, "SUMMARY");
			assertEquals("JOURNEY",
					SceneService.map(member(party.get("members"), ally).get("location_context")).get("kind"));
		}
	}

	@Test
	void serializedFractionsAreStableWhileRawRoadProjectionAndARealFiveFootStepRemainUnchanged() throws Exception {
		try (Engine e = engine(tempDb("run11-anchor-presentation-precision"))) {
			String campaign = committedCampaign(e);
			e.sessions().bootstrap(op(), campaign, null);
			String home = location(e, "character:1"), mine = site(e, campaign, "Mine", home, 50);
			String road = e.db().read(tx -> Ref.of(Ref.CONNECTION,
					tx.query("SELECT * FROM location_connection WHERE kind='ROAD' ORDER BY id DESC").getFirst().id()));
			var region = e.maps().begin(op(), campaign, "REGION",
					map("name", "Actual road", "geometry", map("bounds_ft", List.of(0, 0, 20000, 20000)), "rooms",
							List.of(map("key", "home", "location_ref", home, "point_ft", List.of(1000, 1000)),
									map("key", "mine", "location_ref", mine, "point_ft", List.of(17000, 1000))),
							"connections",
							List.of(map("key", "road", "from", "home", "to", "mine", "connection_ref", road)),
							"features", List.of(map("key", "road", "kind", "ROAD", "graph_ref", "road", "path_ft",
									List.of(List.of(1000, 1000), List.of(17000, 1000))))));
			e.maps().publish(op(), campaign, region.get("preparation").toString(), null);
			var prep = e.maps().begin(op(), campaign, "SCENE", map("name", "Two minutes inside the mine",
					"location_ref", mine, "geometry", map("bounds_ft", List.of(0, 0, 60, 20)), "rooms",
					List.of(map("key", "entry", "role", "ENTRY", "rect_ft", List.of(0, 0, 20, 20)),
							map("key", "drift", "rect_ft",
									List.of(20, 0, 20, 20)),
							map("key", "deep", "rect_ft", List.of(40, 0, 20, 20))),
					"connections",
					List.of(map("key", "first", "from", "entry", "to", "drift", "kind", "DOOR", "state", "OPEN",
							"travel_minutes", 1, "from_ft", List.of(20, 5), "to_ft", List.of(20, 10)),
							map("key", "second", "from", "drift", "to", "deep", "kind", "DOOR", "state", "OPEN",
									"travel_minutes", 1, "from_ft", List.of(40, 5), "to_ft", List.of(40, 10)))));
			var published = e.maps().publish(op(), campaign, prep.get("preparation").toString(), null);
			String deep = SceneService.map(published.get("references")).get("room:deep").toString();
			e.rest().override(op(), campaign, "SET_LOCATION", "character:1",
					map("location", deep, "clear_map_position", true),
					"Establish the travel fixture at the actual deep room.", null);
			journeys(e).defineRisk(op(), campaign, road,
					map("authored_at_fraction", .5, "frequency_seconds", 3600, "chance_percent", 0));
			var departure = e.world().move(op(), campaign, home, null, false, null,
					"Leave two interior passages before the road.");
			assertEquals(3120L, departure.get("total_seconds"));
			assertEquals(1620L, departure.get("progress_seconds"));
			var anchor = SceneService.map(departure.get("anchor"));
			assertEquals(.5, ((Number) anchor.get("leg_fraction")).doubleValue());
			assertEquals(.519230769231, ((Number) anchor.get("journey_fraction")).doubleValue());
			assertEquals(anchor, SceneService.map(SceneService.map(departure.get("opportunity")).get("anchor")));
			var stored = e.db()
					.read(tx -> tx.get("travel_journey", Ref.id(departure.get("journey").toString(), "journey")));
			assertEquals(.5000000000000001,
					((Number) SceneService.map(stored.map("opportunity_json").get("anchor")).get("leg_fraction"))
							.doubleValue(),
					"Only presentation is rounded; internal physical projection retains its original precision.");
			var geometry = e.db().read(tx -> tx.query("SELECT geometry_json FROM map_feature ORDER BY id").stream()
					.map(row -> row.str("geometry_json")).toList());
			String foe = e.runtime()
					.materialize(op(), campaign, "Commoner", "Road watcher", null, null, null, null, false)
					.get("character").toString();
			((ScriptedRollService) e.roller()).queue(20, 1);
			var fight = e.encounters().start(op(), campaign, map("party", List.of("character:1"), "foes", List.of(foe)),
					null, null, null, null, null);
			var pc = e.db().read(tx -> SceneService.actor(tx, Ref.id(campaign, Ref.CAMPAIGN), "character:1"));
			e.encounters().perform(op(), campaign, fight.get("encounter").toString(), "character:1",
					map("kind", "MOVE", "x_ft", ((Number) pc.get("x_ft")).doubleValue() + 5, "y_ft", pc.get("y_ft")),
					false);
			e.encounters().end(op(), campaign, fight.get("encounter").toString(), "OTHER",
					"The traveler ends five feet back along the reversed road.");
			var state = journeys(e).state(campaign);
			assertEquals(1619L, state.get("progress_seconds"));
			assertEquals(.499666666667,
					((Number) SceneService.map(state.get("anchor")).get("leg_fraction")).doubleValue());
			assertEquals(List.of(5d, 0d),
					SceneService.map(state.get("last_tactical_position")).get("offset_from_contact_ft"));
			assertEquals(1619L,
					e.db().read(tx -> tx.get("travel_journey", stored.id()).lng("elapsed_seconds")).longValue());
			assertEquals(geometry, e.db().read(tx -> tx.query("SELECT geometry_json FROM map_feature ORDER BY id")
					.stream().map(row -> row.str("geometry_json")).toList()));
		}
	}

	private record Inn(String campaign, String inn, String map, Map<String, Object> rooms) {
	}

	private static Inn inn(Engine e, String partyRoom) {
		String campaign = committedCampaign(e);
		e.sessions().bootstrap(op(), campaign, null);
		quiet(e, campaign);
		String home = location(e, "character:1");
		String inn = e.world()
				.materialize(op(), campaign, null,
						map("name", "The Broken Pick", "kind", "BUILDING", "connections",
								List.of(map("to", home, "kind", "ROAD", "travel_minutes", 10))),
						"GM")
				.get("ref").toString();
		e.world().move(op(), campaign, inn, null, false, null, "Establish the party at the inn.");
		var prep = e.maps()
				.begin(op(), campaign, "DUNGEON",
						map("name", "Inn rooms", "stage", "SCENE", "location_ref", inn, "illumination", "BRIGHT",
								"levels", List.of(-1, 0, 1), "geometry", map("bounds_ft", List.of(0, 0, 40, 20)),
								"rooms",
								List.of(map("key", "taproom", "role", "ENTRY", "level", 0, "rect_ft",
										List.of(0, 0, 20, 20)),
										map("key", "office", "level", 0, "rect_ft", List.of(20, 0, 20, 20)),
										map("key", "cellar", "level", -1, "rect_ft",
												List.of(0, 0, 20, 20)),
										map("key", "loft", "level", 1, "rect_ft", List.of(0, 0, 20, 20))),
								"connections",
								List.of(map("key", "office-door", "from", "taproom", "to", "office", "kind", "DOOR",
										"state", "LOCKED", "from_ft", List.of(20, 5), "to_ft", List.of(20, 10)),
										map("key", "cellar-stair", "from", "taproom", "to", "cellar", "kind", "STAIRS",
												"state", "OPEN", "from_position_ft", List.of(5, 5), "to_position_ft",
												List.of(5, 5)),
										map("key", "loft-stair", "from", "taproom", "to", "loft", "kind", "STAIRS",
												"state", "OPEN", "from_position_ft", List.of(15, 5), "to_position_ft",
												List.of(15, 5)))));
		var published = e.maps().publish(op(), campaign, prep.get("preparation").toString(), null);
		String board = published.get("map_ref").toString();
		var refs = SceneService.map(published.get("references"));
		var rooms = new LinkedHashMap<String, Object>();
		for (String key : List.of("taproom", "office", "cellar", "loft"))
			rooms.put(key, refs.get("room:" + key));
		e.scenes().place(op(), campaign, board,
				List.of(map("character", "character:1", "x_ft", partyRoom.equals("office") ? 25 : 5, "y_ft", 5, "level",
						partyRoom.equals("cellar") ? -1 : partyRoom.equals("loft") ? 1 : 0)));
		return new Inn(campaign, inn, board, rooms);
	}

	private static void assertPresence(Engine e, String campaign, String companion, boolean expected) {
		var beforeReadOnly = e.db().snapshot(Ref.id(campaign, Ref.CAMPAIGN));
		for (String detail : List.of("SUMMARY", "PLAY", "FULL"))
			assertEquals(expected,
					member(e.sessions().party(campaign, detail).get("members"), companion).get("with_party"));
		assertEquals(expected,
				member(e.narrative().context(campaign, "SCENE", null, null, null, null).get("party"), companion)
						.get("with_party"));
		var context = e.narrative().context(campaign, "INTIMACY", "character:1", companion, null, null);
		assertEquals(expected, SceneService.maps(context.get("partners")).getFirst().get("with_party"));
		assertEquals(beforeReadOnly, e.db().snapshot(Ref.id(campaign, Ref.CAMPAIGN)),
				"Party and narrative reads preserve every canonical table, including rolls and clock.");
		var beforeBootstrap = domainSnapshot(e, campaign);
		assertEquals(expected,
				member(e.sessions().bootstrap(op(), campaign, null).get("party"), companion).get("with_party"));
		assertEquals(beforeBootstrap, domainSnapshot(e, campaign),
				"Bootstrap may update its campaign bookkeeping, but preserves physical state, rolls and clock.");
	}

	private static Map<String, List<Map<String, Object>>> domainSnapshot(Engine e, String campaign) {
		var snapshot = e.db().snapshot(Ref.id(campaign, Ref.CAMPAIGN));
		// Session bootstrap intentionally updates these two campaign bookkeeping fields.
		for (var row : snapshot.get("campaign")) {
			row.remove("last_played_at");
			row.remove("revision");
		}
		return snapshot;
	}

	private static Map<String, Object> member(Object members, String ref) {
		return SceneService.maps(members).stream().filter(member -> ref.equals(member.get("ref"))).findFirst()
				.orElseThrow();
	}

	private static String companion(Engine e, String campaign, String name, String place) {
		String ref = e.runtime().materialize(op(), campaign, "Guard", name, null, null, null, place, false)
				.get("character").toString();
		e.party().updateMembership(op(), campaign, ref, "JOIN", "An established party companion.");
		return ref;
	}

	private static String site(Engine e, String campaign, String name, String from, int minutes) {
		return e.world()
				.materialize(op(), campaign, null,
						map("name", name, "kind", "SITE", "connections",
								List.of(map("to", from, "kind", "ROAD", "travel_minutes", minutes))),
						"GM")
				.get("ref").toString();
	}

	private static Map<String, Object> actor(Engine e, String campaign, String character) {
		return e.db().read(tx -> SceneService.actor(tx, Ref.id(campaign, Ref.CAMPAIGN), character).asMap());
	}

	private static String location(Engine e, String character) {
		return e.db().read(
				tx -> Ref.of(Ref.LOCATION, tx.get("character", Ref.id(character, Ref.CHARACTER)).lng("location_id")));
	}

	private static long clock(Engine e, String campaign) {
		return e.db().read(tx -> GameTime.currentSeconds(tx, Ref.id(campaign, Ref.CAMPAIGN)));
	}

	private static TravelJourneyService journeys(Engine e) {
		return new TravelJourneyService(e.db(), e.rules(), e.roller());
	}

	private static void quiet(Engine e, String campaign) {
		journeys(e).defineRisk(op(), campaign, "DEFAULT", map("frequency_seconds", 3600, "chance_percent", 0));
	}
}
