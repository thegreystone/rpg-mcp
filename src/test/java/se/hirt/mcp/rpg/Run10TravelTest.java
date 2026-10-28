/* Copyright (C) 2026 Marcus Hirt. SPDX-License-Identifier: BSD-3-Clause */
package se.hirt.mcp.rpg;

import org.junit.jupiter.api.Test;
import se.hirt.mcp.rpg.dice.ScriptedRollService;
import se.hirt.mcp.rpg.maps.SceneService;
import se.hirt.mcp.rpg.protocol.Ref;
import se.hirt.mcp.rpg.protocol.RpgException;
import se.hirt.mcp.rpg.session.GameTime;
import se.hirt.mcp.rpg.world.TravelJourneyService;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static se.hirt.mcp.rpg.TestCampaigns.*;

class Run10TravelTest {

	@Test
	void loneUnplacedDeadTravelerStaysAtTheResolvedContactAcrossCancellationReloadReplayAndRevival() throws Exception {
		var file = tempDb("run10-unplaced-contact-death");
		String campaign, home, mine, ally, contact, cancelOperation = op();
		long atContact;
		try (Engine e = engine(file)) {
			campaign = ready(e);
			home = location(e, "character:1");
			mine = mine(e, campaign, home);
			ally = e.runtime().materialize(op(), campaign, "Guard", "Oddny", null, null, null, home, false)
					.get("character").toString();
			e.party().updateMembership(op(), campaign, ally, "JOIN", "Oddny travels on her own.");
			journeys(e).defineRisk(op(), campaign, road(e),
					map("authored_at_fraction", .5, "frequency_seconds", 3600, "chance_percent", 0));
			var interrupted = e.world().move(op(), campaign, mine, List.of(ally), false, null,
					"Oddny walks ahead while the player stays home.");
			contact = interrupted.get("contact_location").toString();
			assertEquals(1500L, interrupted.get("progress_seconds"));
			assertEquals(0L, e.db().read(
					tx -> tx.count("SELECT COUNT(*) FROM map_actor WHERE character_id=?", Ref.id(ally, Ref.CHARACTER)))
					.longValue());
			e.runtime().applyRuntimeChange(op(), campaign, ally, map("kind", "DAMAGE", "amount", 1000, "reason",
					"An adjudicated fatal incident at the actual road contact."));
			journeys(e).resolveOpportunity(op(), campaign, "RESOLVED");
			atContact = clock(e, campaign);
			var beforeRead = e.db().snapshot(Ref.id(campaign, Ref.CAMPAIGN));
			var pending = journeys(e).state(campaign);
			var removed = SceneService.maps(pending.get("removed_travelers")).getFirst();
			assertEquals(contact, removed.get("left_at"));
			assertFalse(removed.containsKey("map_ref"));
			assertFalse(removed.containsKey("move_origin_ft"));
			assertFalse(removed.containsKey("level"));
			assertEquals(beforeRead, e.db().snapshot(Ref.id(campaign, Ref.CAMPAIGN)),
					"Reading pending removal cannot relocate anyone or create a figure.");
			((ScriptedRollService) e.roller()).queue(4);
			var cancelled = journeys(e).advance(cancelOperation, campaign, 60);
			assertEquals("CANCELLED", cancelled.get("status"));
			assertEquals(0L, cancelled.get("elapsed_seconds"));
			assertEquals(1500L, cancelled.get("progress_seconds"));
			assertEquals("DEAD", SceneService.maps(cancelled.get("removed_travelers")).getFirst().get("reason"));
			assertEquals(contact, SceneService.maps(cancelled.get("removed_travelers")).getFirst().get("left_at"));
			assertEquals(contact, location(e, ally));
			assertEquals(home, location(e, "character:1"));
			assertEquals(atContact, clock(e, campaign));
			assertEquals(4, e.roller().roll("1d6").total());
		}
		try (Engine e = engine(file)) {
			assertEquals(contact, location(e, ally));
			var beforeReplay = e.db().snapshot(Ref.id(campaign, Ref.CAMPAIGN));
			var replay = journeys(e).advance(cancelOperation, campaign, 60);
			assertEquals(true, replay.get("replayed"));
			assertEquals("CANCELLED", replay.get("status"));
			assertEquals(contact, SceneService.maps(replay.get("removed_travelers")).getFirst().get("left_at"));
			assertEquals(beforeReplay, e.db().snapshot(Ref.id(campaign, Ref.CAMPAIGN)));
			e.rest().override(op(), campaign, "SET_LIFE_STATE", ally, map("life_state", "ALIVE", "hp", 1),
					"An explicitly adjudicated revival restores Oddny at the place where she died.", null);
			assertEquals(contact, location(e, ally));
			var resumed = e.world().move(op(), campaign, mine, List.of(ally), false, null,
					"The revived traveler walks only the remaining road.");
			assertEquals(contact, resumed.get("from"));
			assertEquals("COMPLETED", resumed.get("status"));
			assertEquals(1500L, resumed.get("elapsed_seconds"));
			assertEquals(atContact + 1500, clock(e, campaign));
			assertEquals(mine, location(e, ally));
			assertEquals(home, location(e, "character:1"));
		}
	}

	@Test
	void interiorArrivalAndDepartureAreSymmetricAndTheRoadContactKeepsItsPhysicalFraction() throws Exception {
		var file = tempDb("run10-interior-symmetric-route");
		String campaign, home, mine, vault;
		try (Engine e = engine(file)) {
			campaign = ready(e);
			home = location(e, "character:1");
			mine = mine(e, campaign, home);
			var rooms = rooms(e, campaign, mine);
			vault = rooms.vault();
			journeys(e).defineRisk(op(), campaign, road(e),
					map("authored_at_fraction", .5, "frequency_seconds", 3600, "chance_percent", 0));
			var arrival = e.world().move(op(), campaign, vault, null, false, null, "Road then entry, drift and vault.");
			assertEquals(3300L, arrival.get("total_seconds"));
			assertEquals(1500L, arrival.get("progress_seconds"));
			assertEquals(.5, ((Number) SceneService.map(arrival.get("anchor")).get("leg_fraction")).doubleValue(),
					1e-12);
			journeys(e).resolveOpportunity(op(), campaign, "RESOLVED");
			assertEquals("COMPLETED", journeys(e).advance(op(), campaign, 3300).get("status"));
			assertEquals(vault, location(e, "character:1"));
			var departing = e.world().move(op(), campaign, home, null, false, null,
					"Vault, drift and entry before the same road.");
			assertEquals(vault, departing.get("from"));
			assertEquals(3300L, departing.get("total_seconds"));
			assertEquals(1800L, departing.get("progress_seconds"),
					"Five actual interior minutes precede the fixed road midpoint.");
			var route = SceneService.maps(departing.get("route"));
			assertEquals(3, route.size());
			assertEquals(vault, route.getFirst().get("from"));
			assertEquals(rooms.drift(), route.getFirst().get("to"));
			assertEquals(rooms.entry(), route.get(1).get("to"));
			assertEquals(mine, route.getLast().get("from"));
			assertEquals(road(e), SceneService.map(departing.get("anchor")).get("connection_ref"));
			assertEquals(.5, ((Number) SceneService.map(departing.get("anchor")).get("leg_fraction")).doubleValue(),
					1e-12);
		}
		try (Engine e = engine(file)) {
			var before = journeys(e).state(campaign);
			assertEquals(1800L, before.get("progress_seconds"));
			journeys(e).resolveOpportunity(op(), campaign, "RESOLVED");
			var arrived = journeys(e).advance(op(), campaign, 3300);
			assertEquals("COMPLETED", arrived.get("status"));
			assertEquals(1500L, arrived.get("elapsed_seconds"));
			assertEquals(home, location(e, "character:1"));
		}
	}

	@Test
	void blockedInteriorExitsCannotBeSkippedAndClosedLockedDoorsSayTheyAreLocked() throws Exception {
		try (Engine e = engine(tempDb("run10-interior-exit-locks"))) {
			String campaign = ready(e), home = location(e, "character:1"), mine = mine(e, campaign, home);
			var rooms = rooms(e, campaign, mine);
			e.rest().override(op(), campaign, "SET_LOCATION", "character:1",
					map("location", rooms.vault(), "clear_map_position", true),
					"Establish the unplaced traveler in the authored vault.", null);
			for (var state : List.of(map("state", "CLOSED", "locked", false), map("state", "CLOSED", "locked", true),
					map("state", "LOCKED", "locked", true), map("state", "SECRET", "locked", false),
					map("state", "BLOCKED", "locked", false))) {
				e.scenes().changeObject(op(), campaign, rooms.map(), "drift-vault", state,
						"The DM establishes the actual exit boundary.");
				var before = e.db().snapshot(Ref.id(campaign, Ref.CAMPAIGN));
				long clock = clock(e, campaign);
				((ScriptedRollService) e.roller()).queue(4);
				assertEquals(0L, e.world()
						.move(op(), campaign, mine, null, false, null, "Parent focus retains the actual locked room.")
						.get("elapsed_seconds"));
				var refusal = assertThrows(RpgException.class, () -> e.world().move(op(), campaign, home, null, true, 1,
						"Authorization cannot skip the interior exit."));
				assertTrue(refusal.getMessage().contains("open exit from"));
				assertEquals("drift-vault", refusal.details().get("key"));
				assertEquals(state.get("state"), refusal.details().get("state"));
				assertEquals(state.get("locked"), refusal.details().get("locked"));
				if (Boolean.TRUE.equals(state.get("locked")))
					assertTrue(refusal.getMessage().toLowerCase(java.util.Locale.ROOT).contains("locked"));
				if ("CLOSED".equals(state.get("state")) && Boolean.TRUE.equals(state.get("locked")))
					assertTrue(refusal.getMessage().contains("CLOSED and locked"));
				if ("CLOSED".equals(state.get("state")))
					assertEquals(!Boolean.TRUE.equals(state.get("locked")),
							refusal.getMessage().contains("update_map_object"));
				assertEquals(rooms.vault(), location(e, "character:1"));
				assertEquals(clock, clock(e, campaign));
				assertEquals(before, e.db().snapshot(Ref.id(campaign, Ref.CAMPAIGN)));
				assertEquals(4, e.roller().roll("1d6").total());
			}
		}
	}

	@Test
	void anExplicitAuthoredRoomExitIsChargedOnceWithoutAddingTheOtherInteriorPath() throws Exception {
		try (Engine e = engine(tempDb("run10-explicit-room-exit"))) {
			String campaign = ready(e), home = location(e, "character:1"), mine = mine(e, campaign, home);
			var rooms = rooms(e, campaign, mine);
			e.world().materialize(op(), campaign, rooms.vault(), map("extend", true, "connections",
					List.of(map("to", mine, "kind", "STAIRS", "state", "OPEN", "travel_minutes", 7))), "GM");
			e.rest().override(op(), campaign, "SET_LOCATION", "character:1",
					map("location", rooms.vault(), "clear_map_position", true),
					"The traveler uses the explicitly authored exterior stair.", null);
			quiet(e, campaign);
			var arrived = e.world().move(op(), campaign, home, null, false, null,
					"Use the authored seven-minute exit, then the road.");
			assertEquals("COMPLETED", arrived.get("status"));
			assertEquals(3420L, arrived.get("elapsed_seconds"));
			assertEquals(2, SceneService.maps(arrived.get("route")).size());
			assertEquals(rooms.vault(), SceneService.maps(arrived.get("route")).getFirst().get("from"));
		}
	}

	@Test
	void aContactFightKeepsAnUnmovedAnchorButRealBackwardMovementProjectsIntoJourneyProgress() throws Exception {
		for (boolean stepBack : List.of(false, true))
			try (Engine e = engine(tempDb("run10-contact-physical-progress-" + stepBack))) {
				String campaign = ready(e), home = location(e, "character:1"), mine = mine(e, campaign, home),
						road = road(e);
				var region = e.maps().begin(op(), campaign, "REGION",
						map("name", "Physical road", "geometry", map("bounds_ft", List.of(0, 0, 20000, 20000)), "rooms",
								List.of(map("key", "home", "location_ref", home, "point_ft", List.of(1000, 1000)),
										map("key", "mine", "location_ref", mine, "point_ft", List.of(17000, 1000))),
								"connections",
								List.of(map("key", "road", "from", "home", "to", "mine", "connection_ref", road)),
								"features", List.of(map("key", "road", "kind", "ROAD", "graph_ref", "road", "path_ft",
										List.of(List.of(1000, 1000), List.of(17000, 1000))))));
				e.maps().publish(op(), campaign, region.get("preparation").toString(), null);
				journeys(e).defineRisk(op(), campaign, road,
						map("authored_at_fraction", .5, "frequency_seconds", 3600, "chance_percent", 0));
				var departure = e.world().move(op(), campaign, mine, null, false, null, "Reach the fixed contact.");
				String foe = e.runtime()
						.materialize(op(), campaign, "Commoner", "Road watcher", null, null, null, null, false)
						.get("character").toString();
				((ScriptedRollService) e.roller()).queue(20, 1);
				var encounter = e.encounters().start(op(), campaign,
						map("party", List.of("character:1"), "foes", List.of(foe)), null, null, null, null, null);
				var actor = e.db().read(tx -> SceneService.actor(tx, Ref.id(campaign, Ref.CAMPAIGN), "character:1"));
				if (stepBack)
					e.encounters().perform(
							op(), campaign, encounter.get("encounter").toString(), "character:1", map("kind", "MOVE",
									"x_ft", ((Number) actor.get("x_ft")).doubleValue() - 5, "y_ft", actor.get("y_ft")),
							false);
				e.encounters().end(op(), campaign, encounter.get("encounter").toString(), "OTHER",
						"The contact ends at the actual saved figure position.");
				var state = journeys(e).state(campaign);
				assertEquals(stepBack ? 1499L : 1500L, state.get("progress_seconds"));
				var saved = SceneService.map(state.get("last_tactical_position"));
				assertEquals(1500L, ((Number) saved.get("progress_before_seconds")).longValue());
				assertEquals(stepBack ? 1499L : 1500L, ((Number) saved.get("progress_after_seconds")).longValue());
				assertEquals(List.of(stepBack ? -5d : 0d, 0d), saved.get("offset_from_contact_ft"));
				assertEquals(departure.get("contact_location"), saved.get("contact_location"));
				var beforeRead = e.db().snapshot(Ref.id(campaign, Ref.CAMPAIGN));
				assertEquals(departure.get("contact_location"), journeys(e).state(campaign).get("contact_location"));
				assertEquals(beforeRead, e.db().snapshot(Ref.id(campaign, Ref.CAMPAIGN)));
				long locations = e.db().read(tx -> tx.count("SELECT COUNT(*) FROM location"));
				String nextFoe = e.runtime().materialize(op(), campaign, "Commoner", "Another contact watcher", null,
						null, null, null, false).get("character").toString();
				assertEquals(departure.get("contact_location"), location(e, nextFoe),
						"Tactical projection must not replace the saved contact place when progress changes by a real step.");
				assertEquals(locations, e.db().read(tx -> tx.count("SELECT COUNT(*) FROM location")).longValue());
			}
	}

	@Test
	void actualGraphProgressClearsThePreviousContactBeforeCreatingTheNextInterruption() throws Exception {
		try (Engine e = engine(tempDb("run10-successive-contact-places"))) {
			String campaign = ready(e), home = location(e, "character:1"), mine = mine(e, campaign, home),
					road = road(e);
			var region = e.maps().begin(op(), campaign, "REGION", map("name", "Successive road positions", "geometry",
					map("bounds_ft", List.of(0, 0, 20000, 20000)), "rooms",
					List.of(map("key", "home", "location_ref", home, "point_ft", List.of(1000, 1000)),
							map("key", "mine", "location_ref", mine, "point_ft", List.of(17000, 1000))),
					"connections", List.of(map("key", "road", "from", "home", "to", "mine", "connection_ref", road)),
					"features", List.of(map("key", "road", "kind", "ROAD", "graph_ref", "road", "path_ft",
							List.of(List.of(1000, 1000), List.of(17000, 1000))))));
			e.maps().publish(op(), campaign, region.get("preparation").toString(), null);
			journeys(e).defineRisk(op(), campaign, road,
					map("frequency_seconds", 60, "chance_percent", 100, "placement_method", "END"));
			((ScriptedRollService) e.roller()).queue(1, 1);
			var first = e.world().move(op(), campaign, mine, null, false, null,
					"Walk to the first actual interruption.");
			assertEquals(60L, first.get("progress_seconds"));
			journeys(e).resolveOpportunity(op(), campaign, "RESOLVED");
			var next = journeys(e).advance(op(), campaign, 60);
			assertEquals(120L, next.get("progress_seconds"));
			assertNotEquals(first.get("contact_location"), next.get("contact_location"),
					"Actual onward graph movement must not reuse the previous physical contact place.");
			var beforeRead = e.db().snapshot(Ref.id(campaign, Ref.CAMPAIGN));
			assertEquals(next.get("contact_location"), journeys(e).state(campaign).get("contact_location"));
			assertEquals(beforeRead, e.db().snapshot(Ref.id(campaign, Ref.CAMPAIGN)));
		}
	}

	private record Rooms(String map, String entry, String drift, String vault) {
	}

	private static Rooms rooms(Engine e, String campaign, String mine) {
		var prep = e.maps().begin(op(), campaign, "DUNGEON", map("name", "Actual interior passages", "stage", "SCENE",
				"location_ref", mine, "illumination", "BRIGHT", "geometry", map("bounds_ft", List.of(0, 0, 60, 20)),
				"rooms",
				List.of(map("key", "entry", "role", "ENTRY", "rect_ft", List.of(0, 0, 20, 20)),
						map("key", "drift", "rect_ft",
								List.of(20, 0, 20, 20)),
						map("key", "vault", "rect_ft", List.of(40, 0, 20, 20))),
				"connections",
				List.of(map("key", "entry-drift", "from", "entry", "to", "drift", "kind", "DOOR", "state", "OPEN",
						"travel_minutes", 2, "from_ft", List.of(20, 5), "to_ft", List.of(20, 10)),
						map("key", "drift-vault", "from", "drift", "to", "vault", "kind", "DOOR", "state", "OPEN",
								"travel_minutes", 3, "from_ft", List.of(40, 5), "to_ft", List.of(40, 10)))));
		var published = e.maps().publish(op(), campaign, prep.get("preparation").toString(), null);
		var refs = SceneService.map(published.get("references"));
		return new Rooms(published.get("map_ref").toString(), refs.get("room:entry").toString(),
				refs.get("room:drift").toString(), refs.get("room:vault").toString());
	}

	private static String ready(Engine e) {
		String campaign = committedCampaign(e);
		e.sessions().bootstrap(op(), campaign, null);
		return campaign;
	}

	private static String mine(Engine e, String campaign, String home) {
		return e.world()
				.materialize(op(), campaign, null,
						map("name", "Mine", "kind", "SITE", "connections",
								List.of(map("to", home, "kind", "ROAD", "travel_minutes", 50))),
						"GM")
				.get("ref").toString();
	}

	private static String road(Engine e) {
		return e.db().read(tx -> Ref.of(Ref.CONNECTION,
				tx.query("SELECT * FROM location_connection WHERE kind='ROAD' ORDER BY id DESC").getFirst().id()));
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
