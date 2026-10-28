/* Copyright (C) 2026 Marcus Hirt. SPDX-License-Identifier: BSD-3-Clause */
package se.hirt.mcp.rpg;

import org.junit.jupiter.api.Test;
import se.hirt.mcp.rpg.dice.ScriptedRollService;
import se.hirt.mcp.rpg.maps.SceneService;
import se.hirt.mcp.rpg.persistence.Database;
import se.hirt.mcp.rpg.persistence.Row;
import se.hirt.mcp.rpg.protocol.Json;
import se.hirt.mcp.rpg.protocol.Ref;
import se.hirt.mcp.rpg.protocol.RpgException;
import se.hirt.mcp.rpg.session.GameTime;
import se.hirt.mcp.rpg.world.TravelJourneyService;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static se.hirt.mcp.rpg.TestCampaigns.*;

class Run9TravelTest {

	@Test
	void freshExplicitNonmembersAloneOrMixedRefuseBeforeOriginRoutingTimeAndRandomness() throws Exception {
		try (Engine e = engine(tempDb("run9-nonmember-departure"))) {
			String campaign = ready(e), home = location(e, "character:1"), stranger = npc(e, campaign, "Pell"),
					former = npc(e, campaign, "Former companion"), mine = site(e, campaign, "Mine", home, 50);
			e.party().updateMembership(op(), campaign, former, "JOIN", "A former road companion.");
			e.party().updateMembership(op(), campaign, former, "LEAVE", "The companion leaves before departure.");
			var before = e.db().snapshot(Ref.id(campaign, Ref.CAMPAIGN));
			((ScriptedRollService) e.roller()).queue(4);
			for (List<String> selected : List.of(List.of(stranger), List.of("character:1", stranger),
					List.of(former))) {
				var refusal = assertThrows(RpgException.class, () -> e.world().move(op(), campaign, mine, selected,
						true, 1, "Authorization is not membership."));
				assertTrue(refusal.getMessage().contains("update_party_membership"));
				assertTrue(refusal.details().containsKey("character"));
				assertEquals(before, e.db().snapshot(Ref.id(campaign, Ref.CAMPAIGN)));
			}
			assertEquals(4, e.roller().roll("1d6").total());
			assertEquals(false, journeys(e).state(campaign).get("active"));
		}
	}

	@Test
	void freshSeparatedMembersAndGuestsCanTravelTogetherWithoutMovingTheControlledStayer() throws Exception {
		try (Engine e = engine(tempDb("run9-eligible-guests"))) {
			String campaign = ready(e), home = location(e, "character:1"), member = npc(e, campaign, "Oddny"),
					guest = npc(e, campaign, "Escort"), mine = site(e, campaign, "Mine", home, 50);
			e.party().updateMembership(op(), campaign, member, "JOIN", "An established companion.");
			e.party().updateMembership(op(), campaign, member, "SEPARATE", "Travels separately, still a member.");
			e.party().updateMembership(op(), campaign, guest, "GUEST_ADD", "An escort travels as a guest.");
			quiet(e, campaign);
			var memberships = e.db().read(
					tx -> tx.query("SELECT * FROM party_membership ORDER BY id").stream().map(Row::asMap).toList());
			var arrived = e.world().move(op(), campaign, mine, List.of(member, guest), false, null,
					"The companions scout ahead.");
			assertEquals("COMPLETED", arrived.get("status"));
			assertEquals(3000L, arrived.get("elapsed_seconds"));
			assertEquals(List.of(member, guest), arrived.get("movers"));
			assertEquals(home, arrived.get("current_location"));
			assertEquals(home, location(e, "character:1"));
			assertEquals(mine, location(e, member));
			assertEquals(mine, location(e, guest));
			assertEquals(memberships, e.db().read(
					tx -> tx.query("SELECT * FROM party_membership ORDER BY id").stream().map(Row::asMap).toList()));
		}
	}

	@Test
	void legacyNeverMemberOnlyJourneyCancelsAtTheContactWithoutMovingTheFigureOrSpendingTravelTime() throws Exception {
		try (Engine e = engine(tempDb("run9-legacy-nonmember-only"))) {
			String campaign = ready(e), home = location(e, "character:1"), escort = npc(e, campaign, "Pell"),
					mine = site(e, campaign, "Mine", home, 50);
			e.party().updateMembership(op(), campaign, escort, "GUEST_ADD",
					"Temporary setup for a legacy saved journey.");
			journeys(e).defineRisk(op(), campaign, "DEFAULT",
					map("authored_at_fraction", .5, "frequency_seconds", 3600, "chance_percent", 0));
			var travel = e.world().move(op(), campaign, mine, List.of(escort), false, null, "The guest travels.");
			assertTrue(travel.get("contact_location") instanceof String);
			assertNotEquals(home, travel.get("contact_location"));
			var beforeRead = e.db().snapshot(Ref.id(campaign, Ref.CAMPAIGN));
			assertEquals(travel.get("contact_location"), journeys(e).state(campaign).get("contact_location"));
			assertEquals(travel.get("contact_location"),
					SceneService.map(journeys(e).state(campaign).get("opportunity")).get("contact_location"));
			assertEquals(beforeRead, e.db().snapshot(Ref.id(campaign, Ref.CAMPAIGN)),
					"Reading the saved contact pointer must create no location or other state.");
			String foe = e.runtime().materialize(op(), campaign, "Guard", "Road watcher", null, null, null,
					travel.get("contact_location").toString(), false).get("character").toString();
			var fight = e.encounters().start(op(), campaign, map("party", List.of(escort), "foes", List.of(foe)), null,
					null, null, null, null);
			var figure = e.db().read(tx -> SceneService.actor(tx, Ref.id(campaign, Ref.CAMPAIGN), escort).asMap());
			// Model a saved pre-fix journey whose named NPC never had a membership episode.
			e.db().mutate(Database.Mutation.of("legacy_nonmember_fixture", Ref.id(campaign, Ref.CAMPAIGN), op(), "GM",
					Map.of()), tx -> {
						for (Row membership : tx.query("SELECT * FROM party_membership WHERE character_id=?",
								Ref.id(escort, Ref.CHARACTER)))
							tx.delete("party_membership", membership.id());
						return Map.of();
					});
			long clock = clock(e, campaign), checks = checks(e);
			var ended = e.encounters().end(op(), campaign, fight.get("encounter").toString(), "OTHER",
					"The legacy contact ends.");
			var roster = SceneService.map(ended.get("travel_roster"));
			assertEquals("CANCELLED", roster.get("status"));
			assertEquals(1500L, roster.get("progress_seconds"));
			assertEquals(0L, roster.get("elapsed_seconds"));
			var removed = SceneService.maps(roster.get("removed_travelers")).getFirst();
			assertEquals("NO_CURRENT_PARTY_MEMBERSHIP", removed.get("reason"));
			assertEquals(escort, removed.get("character"));
			assertEquals(removed.get("left_at"), location(e, escort));
			assertEquals(figure,
					e.db().read(tx -> SceneService.actor(tx, Ref.id(campaign, Ref.CAMPAIGN), escort).asMap()));
			assertEquals(clock + ((Number) ended.get("elapsed_seconds")).longValue(), clock(e, campaign));
			assertEquals(checks, checks(e));
			assertEquals(home, location(e, "character:1"));
			var next = e.world().move(op(), campaign, mine, List.of("character:1"), false, null,
					"The actual party can depart.");
			assertNotEquals(travel.get("journey"), next.get("journey"));
			assertEquals(List.of("character:1"), next.get("movers"));
		}
	}

	@Test
	void legacyMixedRosterPrunesNeverMembersAndResumesTheSavedJourneyWithoutRerolling() throws Exception {
		try (Engine e = engine(tempDb("run9-legacy-mixed-roster"))) {
			String campaign = ready(e), home = location(e, "character:1"), stranger = npc(e, campaign, "Pell"),
					mine = site(e, campaign, "Mine", home, 50);
			journeys(e).defineRisk(op(), campaign, "DEFAULT",
					map("authored_at_fraction", .5, "frequency_seconds", 3600, "chance_percent", 0));
			var travel = e.world().move(op(), campaign, mine, null, false, null, "The party departs.");
			e.db().mutate(Database.Mutation.of("legacy_mixed_roster_fixture", Ref.id(campaign, Ref.CAMPAIGN), op(),
					"GM", Map.of()), tx -> {
						tx.update("travel_journey", Ref.id(travel.get("journey").toString(), "journey"),
								map("movers_json", Json.write(List.of("character:1", stranger))));
						return Map.of();
					});
			long clock = clock(e, campaign), checks = checks(e);
			journeys(e).resolveOpportunity(op(), campaign, "RESOLVED");
			((ScriptedRollService) e.roller()).queue(4);
			var arrived = journeys(e).advance(op(), campaign, 1500);
			assertEquals(travel.get("journey"), arrived.get("journey"));
			assertEquals("COMPLETED", arrived.get("status"));
			assertEquals(List.of("character:1"), arrived.get("movers"));
			assertEquals("NO_CURRENT_PARTY_MEMBERSHIP",
					SceneService.maps(arrived.get("removed_travelers")).getFirst().get("reason"));
			assertEquals(home, location(e, stranger));
			assertEquals(mine, location(e, "character:1"));
			assertEquals(clock + 1500, clock(e, campaign));
			assertEquals(checks, checks(e));
			assertEquals(4, e.roller().roll("1d6").total());
		}
	}

	@Test
	void authoredRoadPointIsStableAcrossAnApproachLegReverseTravelAndReload() throws Exception {
		var file = tempDb("run9-fixed-road-point");
		String campaign, home, mine, road;
		List<?> physicalPoint;
		try (Engine e = engine(file)) {
			campaign = ready(e);
			home = location(e, "character:1");
			String junction = site(e, campaign, "Junction", home, 10);
			mine = site(e, campaign, "Mine", junction, 50);
			road = road(e, campaign, junction, mine);
			var prep = e.maps().begin(op(), campaign, "REGION",
					map("name", "Authored road orientation", "geometry", map("bounds_ft", List.of(0, 0, 20000, 20000)),
							"rooms",
							List.of(map("key", "mine", "location_ref", mine, "point_ft", List.of(9000, 9000)),
									map("key", "junction", "location_ref", junction, "point_ft", List.of(1000, 1000))),
							"connections",
							List.of(map("key", "track", "from", "mine", "to", "junction", "connection_ref", road)),
							"features", List.of(map("key", "track", "kind", "ROAD", "graph_ref", "track", "path_ft",
									List.of(List.of(9000, 9000), List.of(9000, 1000), List.of(1000, 1000))))));
			e.maps().publish(op(), campaign, prep.get("preparation").toString(), null);
			quiet(e, campaign);
			journeys(e).defineRisk(op(), campaign, road,
					map("authored_at_fraction", .25, "frequency_seconds", 3600, "chance_percent", 0));
			var outward = e.world().move(op(), campaign, mine, null, false, null, "Approach then follow the road.");
			assertEquals(3600L, outward.get("total_seconds"));
			assertEquals(2850L, outward.get("progress_seconds"),
					"Ten-minute approach plus three quarters of the fifty-minute road.");
			assertEquals(.75, ((Number) SceneService.map(outward.get("anchor")).get("leg_fraction")).doubleValue());
			var document = e.db()
					.read(tx -> TravelJourneyService.encounterSceneDocument(tx, Ref.id(campaign, Ref.CAMPAIGN), null));
			assertEquals(true, document.get("route_reverse"));
			physicalPoint = (List<?>) document.get("journey_world_anchor_ft");
			assertEquals(List.of(9000d, 5000d), physicalPoint);
		}
		try (Engine e = engine(file)) {
			long before = clock(e, campaign), checks = checks(e);
			journeys(e).resolveOpportunity(op(), campaign, "RESOLVED");
			var arrived = journeys(e).advance(op(), campaign, 3600);
			assertEquals("COMPLETED", arrived.get("status"));
			assertEquals(750L, arrived.get("elapsed_seconds"));
			assertEquals(before + 750, clock(e, campaign));
			assertEquals(checks, checks(e));
			var reverse = e.world().move(op(), campaign, home, null, false, null,
					"Return along the same physical road.");
			assertEquals(750L, reverse.get("progress_seconds"));
			assertEquals(.25, ((Number) SceneService.map(reverse.get("anchor")).get("leg_fraction")).doubleValue());
			assertEquals(road, SceneService.map(reverse.get("anchor")).get("connection_ref"));
			var document = e.db()
					.read(tx -> TravelJourneyService.encounterSceneDocument(tx, Ref.id(campaign, Ref.CAMPAIGN), null));
			assertEquals(false, document.get("route_reverse"));
			assertEquals(physicalPoint, document.get("journey_world_anchor_ft"));
		}
	}

	@Test
	void partialLegFiresOnlyIfTheFixedPointIsStillAheadInThatDirection() throws Exception {
		for (boolean back : List.of(false, true))
			for (double point : List.of(.25, .5, .75)) {
				try (Engine e = engine(tempDb("run9-partial-point-" + back + "-" + point))) {
					Contact contact = leftBehind(e);
					journeys(e).defineRisk(op(), contact.campaign(), contact.road(),
							map("authored_at_fraction", point, "frequency_seconds", 3600, "chance_percent", 0));
					boolean ahead = back ? point < .5 : point > .5;
					var moving = e.world().move(op(), contact.campaign(), back ? contact.home() : contact.mine(),
							List.of(contact.ally()), false, null, "Follow the remaining road.");
					assertEquals(1500L, moving.get("total_seconds"));
					assertEquals(ahead ? 750L : 1500L, moving.get("elapsed_seconds"));
					assertEquals(ahead ? "INTERRUPTED" : "COMPLETED", moving.get("status"));
					if (ahead) {
						assertEquals(back ? .75 : point,
								((Number) SceneService.map(moving.get("anchor")).get("leg_fraction")).doubleValue());
						journeys(e).resolveOpportunity(op(), contact.campaign(), "RESOLVED");
						assertEquals("COMPLETED", journeys(e).advance(op(), contact.campaign(), 1500).get("status"));
					} else
						assertEquals(Map.of(), moving.get("opportunity"));
					assertEquals(contact.mine(), location(e, "character:1"),
							"A companion's trip cannot move the controlled stayer.");
				}
			}
	}

	@Test
	void interiorRefusalsIdentifyTheActualPassageAndParentEntryChargesTheAuthoredInteriorCost() throws Exception {
		try (Engine e = engine(tempDb("run9-door-guidance-and-cost"))) {
			String campaign = ready(e), home = location(e, "character:1");
			quiet(e, campaign);
			var prep = e.maps().begin(op(), campaign, "DUNGEON", map("name", "Mine interior", "stage", "SCENE",
					"location_ref", home, "illumination", "BRIGHT", "geometry", map("bounds_ft", List.of(0, 0, 60, 20)),
					"rooms",
					List.of(map("key", "entry", "role", "ENTRY", "rect_ft", List.of(0, 0, 20, 20)),
							map("key", "post", "rect_ft",
									List.of(20, 0, 20, 20)),
							map("key", "hall", "rect_ft", List.of(40, 0, 20, 20))),
					"connections",
					List.of(map("key", "entry-post", "from", "entry", "to", "post", "kind", "DOOR", "state", "OPEN",
							"travel_minutes", 2, "from_ft", List.of(20, 5), "to_ft", List.of(20, 10)),
							map("key", "post-to-hall", "from", "post", "to", "hall", "kind", "DOOR", "state", "CLOSED",
									"travel_minutes", 3, "from_ft", List.of(40, 5), "to_ft", List.of(40, 10)))));
			var published = e.maps().publish(op(), campaign, prep.get("preparation").toString(), null);
			String board = published.get("map_ref").toString(),
					hall = SceneService.map(published.get("references")).get("room:hall").toString(),
					post = SceneService.map(published.get("references")).get("room:post").toString(),
					door = SceneService.map(published.get("references")).get("connection:post-to-hall").toString();
			for (String state : List.of("CLOSED", "LOCKED", "SECRET", "BLOCKED")) {
				e.scenes().changeObject(op(), campaign, board, "post-to-hall",
						map("state", state, "locked", state.equals("LOCKED")), "The DM establishes the passage state.");
				for (String origin : List.of(home, post)) {
					e.rest().override(op(), campaign, "SET_LOCATION", "character:1",
							map("location", origin, "clear_map_position", true),
							"Establish the approach to this boundary.", null);
					var before = e.db().snapshot(Ref.id(campaign, Ref.CAMPAIGN));
					var refusal = assertThrows(RpgException.class, () -> e.world().move(op(), campaign, hall, null,
							true, 1, "Authorization cannot bypass the door."));
					assertEquals("post-to-hall", refusal.details().get("key"));
					assertEquals(door, refusal.details().get("connection_ref"));
					assertEquals(state, refusal.details().get("state"));
					assertEquals(state.equals("LOCKED"), refusal.details().get("locked"));
					assertEquals(state.equals("CLOSED"), refusal.getMessage().contains("update_map_object"));
					assertEquals(before, e.db().snapshot(Ref.id(campaign, Ref.CAMPAIGN)));
				}
			}
			for (String open : List.of("OPEN", "BREACHED", "DESTROYED")) {
				e.scenes().changeObject(op(), campaign, board, "post-to-hall", map("state", open, "locked", false),
						"The interior passage is now passable.");
				e.rest().override(op(), campaign, "SET_LOCATION", "character:1",
						map("location", home, "clear_map_position", true), "Approach from the containing site.", null);
				long clock = clock(e, campaign);
				var entered = e.world().move(op(), campaign, hall, null, false, null, "Walk the actual interior path.");
				assertEquals("COMPLETED", entered.get("status"));
				assertEquals(home, entered.get("from"));
				assertEquals(300L, entered.get("elapsed_seconds"));
				assertEquals(2, SceneService.maps(entered.get("route")).size());
				assertEquals(clock + 300, clock(e, campaign));
				assertEquals(hall, location(e, "character:1"));
			}
			e.scenes().place(op(), campaign, board, List.of(map("character", "character:1", "x_ft", 45, "y_ft", 5)));
			var before = e.db().snapshot(Ref.id(campaign, Ref.CAMPAIGN));
			assertEquals(0L, e.world()
					.move(op(), campaign, home, null, false, null, "Select the parent without moving the figure.")
					.get("elapsed_seconds"));
			assertEquals(before, e.db().snapshot(Ref.id(campaign, Ref.CAMPAIGN)));
			e.rest().override(op(), campaign, "SET_LOCATION", "character:1",
					map("location", hall, "clear_map_position", true),
					"Release the figure while retaining its actual authored hall.", null);
			e.scenes().changeObject(op(), campaign, board, "post-to-hall", map("state", "LOCKED", "locked", true),
					"The hall's boundary is locked after the figure is released.");
			var unplaced = e.db().snapshot(Ref.id(campaign, Ref.CAMPAIGN));
			long unplacedClock = clock(e, campaign);
			var focus = e.world().move(op(), campaign, home, null, false, null,
					"Parent focus cannot release a locked room's actual occupant.");
			assertEquals(0L, focus.get("elapsed_seconds"));
			assertEquals(hall, focus.get("current_location"));
			assertEquals(hall, location(e, "character:1"));
			assertEquals(unplaced, e.db().snapshot(Ref.id(campaign, Ref.CAMPAIGN)));
			var blockedSibling = assertThrows(RpgException.class, () -> e.world().move(op(), campaign, post, null,
					false, null, "Try to leave through the locked boundary after parent focus."));
			assertEquals("post-to-hall", blockedSibling.details().get("key"));
			assertEquals("LOCKED", blockedSibling.details().get("state"));
			assertEquals(unplacedClock, clock(e, campaign));
			assertEquals(unplaced, e.db().snapshot(Ref.id(campaign, Ref.CAMPAIGN)));
		}
	}

	private record Contact(String campaign, String home, String mine, String ally, String road) {
	}

	private static Contact leftBehind(Engine e) {
		String campaign = ready(e), home = location(e, "character:1"), ally = npc(e, campaign, "Oddny"),
				mine = site(e, campaign, "Mine", home, 50), road = road(e, campaign, home, mine);
		e.party().updateMembership(op(), campaign, ally, "JOIN", "A road companion.");
		journeys(e).defineRisk(op(), campaign, road,
				map("authored_at_fraction", .5, "frequency_seconds", 3600, "chance_percent", 0));
		e.world().move(op(), campaign, mine, null, false, null, "Travel to the midpoint.");
		String foe = npc(e, campaign, "Road watcher");
		var encounter = e.encounters().start(op(), campaign,
				map("party", List.of("character:1", ally), "foes", List.of(foe)), null, null, null, null, null);
		e.encounters().end(op(), campaign, encounter.get("encounter").toString(), "OTHER",
				"The contact ends peacefully.");
		journeys(e).resolveOpportunity(op(), campaign, "RESOLVED");
		e.world().move(op(), campaign, mine, List.of("character:1"), false, null,
				"The player continues without Oddny.");
		return new Contact(campaign, home, mine, ally, road);
	}

	private static String ready(Engine e) {
		String campaign = committedCampaign(e);
		e.sessions().bootstrap(op(), campaign, null);
		return campaign;
	}

	private static String npc(Engine e, String campaign, String name) {
		return e.runtime().materialize(op(), campaign, "Guard", name, null, null, null, null, false).get("character")
				.toString();
	}

	private static String site(Engine e, String campaign, String name, String from, int minutes) {
		return e.world()
				.materialize(op(), campaign, null,
						map("name", name, "kind", "SITE", "connections",
								List.of(map("to", from, "kind", "ROAD", "travel_minutes", minutes))),
						"GM")
				.get("ref").toString();
	}

	private static String road(Engine e, String campaign, String a, String b) {
		long aid = Ref.id(a, Ref.LOCATION), bid = Ref.id(b, Ref.LOCATION);
		return e.db()
				.read(tx -> Ref.of(Ref.CONNECTION, tx.queryOne(
						"SELECT * FROM location_connection WHERE campaign_id=? AND location_a_id=? AND location_b_id=?",
						Ref.id(campaign, Ref.CAMPAIGN), Math.min(aid, bid), Math.max(aid, bid)).orElseThrow().id()));
	}

	private static String location(Engine e, String character) {
		return e.db().read(
				tx -> Ref.of(Ref.LOCATION, tx.get("character", Ref.id(character, Ref.CHARACTER)).lng("location_id")));
	}

	private static TravelJourneyService journeys(Engine e) {
		return new TravelJourneyService(e.db(), e.rules(), e.roller());
	}

	private static void quiet(Engine e, String campaign) {
		journeys(e).defineRisk(op(), campaign, "DEFAULT", map("frequency_seconds", 3600, "chance_percent", 0));
	}

	private static long clock(Engine e, String campaign) {
		return e.db().read(tx -> GameTime.currentSeconds(tx, Ref.id(campaign, Ref.CAMPAIGN)));
	}

	private static long checks(Engine e) {
		return e.db().read(tx -> tx.count("SELECT COUNT(*) FROM travel_check"));
	}
}
