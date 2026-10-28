/* Copyright (C) 2026 Marcus Hirt. SPDX-License-Identifier: BSD-3-Clause */
package se.hirt.mcp.rpg;

import org.junit.jupiter.api.Test;
import se.hirt.mcp.rpg.dice.ScriptedRollService;
import se.hirt.mcp.rpg.maps.SceneService;
import se.hirt.mcp.rpg.protocol.Ref;
import se.hirt.mcp.rpg.protocol.RpgException;
import se.hirt.mcp.rpg.session.GameTime;
import se.hirt.mcp.rpg.world.TravelJourneyService;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static se.hirt.mcp.rpg.TestCampaigns.*;

class Run8TravelTest {

	@Test
	void namedCompanionTravelsFromTheirOwnSiteToTheControlledCharactersDestination() throws Exception {
		try (Engine e = engine(tempDb("run8-companion-origin"))) {
			String campaign = ready(e), home = location(e, "character:1"), ally = companion(e, campaign),
					mine = mine(e, campaign, home);
			quiet(e, campaign);
			var emptyBefore = e.db().snapshot(Ref.id(campaign, Ref.CAMPAIGN));
			assertThrows(RpgException.class, () -> e.world().move(op(), campaign, mine, List.of(), false, null,
					"An empty selector cannot move the default party."));
			assertEquals(emptyBefore, e.db().snapshot(Ref.id(campaign, Ref.CAMPAIGN)));
			e.world().move(op(), campaign, mine, List.of("character:1"), false, null, "The player goes ahead.");
			long clock = clock(e, campaign);
			var moved = e.world().move(op(), campaign, mine, List.of(ally, ally), false, null,
					"Oddny joins the player.");
			assertEquals(home, moved.get("from"));
			assertEquals(List.of(ally), moved.get("moved"));
			assertEquals(3000L, moved.get("elapsed_seconds"));
			assertEquals(clock + 3000, clock(e, campaign));
			assertEquals(mine, location(e, ally));
			assertEquals(mine, location(e, "character:1"));
			assertEquals(mine, moved.get("current_location"));
			var before = e.db().snapshot(Ref.id(campaign, Ref.CAMPAIGN));
			RpgException already = assertThrows(RpgException.class,
					() -> e.world().move(op(), campaign, mine, List.of(ally), false, null, "Already joined."));
			assertTrue(already.getMessage().contains("selected travelers are already"));
			assertEquals(before, e.db().snapshot(Ref.id(campaign, Ref.CAMPAIGN)));
		}
	}

	@Test
	void leftBehindContactUsesTheRemainingRoadInEitherDirectionAndKeepsTheOriginalRiskScope() throws Exception {
		for (boolean back : List.of(false, true)) {
			try (Engine e = engine(tempDb("run8-contact-origin-" + back))) {
				Contact contact = leftBehind(e);
				var topology = e.db().read(tx -> tx.query("SELECT * FROM location_connection ORDER BY id").stream()
						.map(row -> row.asMap()).toList());
				long clock = clock(e, contact.campaign());
				String target = back ? contact.home() : contact.mine();
				var moving = e.world().move(op(), contact.campaign(), target, List.of(contact.ally()), false, null,
						"The companion walks from the actual contact.");
				assertEquals(contact.place(), moving.get("from"));
				assertEquals(1500L, moving.get("total_seconds"),
						"Only half of the fifty-minute authored road remains.");
				assertEquals(1500L, moving.get("elapsed_seconds"));
				var leg = SceneService.maps(moving.get("route")).getFirst();
				assertEquals(contact.road(), leg.get("connection_ref"));
				assertEquals(contact.place(), leg.get("from"));
				assertEquals(target, leg.get("to"));
				assertEquals(back ? contact.mine() : contact.home(), leg.get("connection_from"));
				assertEquals(.5, ((Number) leg.get("start_fraction")).doubleValue());
				assertEquals(1d, ((Number) SceneService.map(moving.get("anchor")).get("leg_fraction")).doubleValue());
				assertEquals(Map.of(), moving.get("opportunity"),
						"A contact at the departure point must not re-fire halfway along the remaining road.");
				assertEquals("COMPLETED", moving.get("status"));
				assertEquals(clock + 1500, clock(e, contact.campaign()));
				assertEquals(target, location(e, contact.ally()));
				assertEquals(contact.mine(), location(e, "character:1"));
				assertEquals(contact.mine(), moving.get("current_location"));
				assertEquals(contact.place(), e.db().read(
						tx -> Ref.of(Ref.LOCATION, tx.get("location", Ref.id(contact.place(), Ref.LOCATION)).id())));
				assertEquals(topology,
						e.db().read(tx -> tx.query("SELECT * FROM location_connection ORDER BY id").stream()
								.map(row -> row.asMap()).toList()),
						"Contact travel must create no synthetic connection.");
			}
		}
	}

	@Test
	void namedRoomOriginAndDestinationUseTheAuthoredPlaceHierarchyWithoutMovingTheControlledFigure() throws Exception {
		try (Engine e = engine(tempDb("run8-room-origin"))) {
			String campaign = ready(e), home = location(e, "character:1"), ally = companion(e, campaign),
					mine = mine(e, campaign, home);
			quiet(e, campaign);
			Room innRoom = room(e, campaign, home, "Taproom");
			e.scenes().place(op(), campaign, innRoom.map(), List.of(map("character", ally, "x_ft", 5, "y_ft", 5)));
			e.world().move(op(), campaign, mine, List.of("character:1"), false, null, "The player travels ahead.");
			Room mineRoom = room(e, campaign, mine, "Winch house");
			e.scenes().place(op(), campaign, mineRoom.map(),
					List.of(map("character", "character:1", "x_ft", 5, "y_ft", 5)));
			var playerFigure = e.db()
					.read(tx -> SceneService.actor(tx, Ref.id(campaign, Ref.CAMPAIGN), "character:1").asMap());
			var arrived = e.world().move(op(), campaign, mineRoom.location(), List.of(ally), false, null,
					"Oddny joins the player inside the winch house.");
			assertEquals(home, SceneService.maps(arrived.get("route")).getFirst().get("from"));
			assertEquals(3000L, arrived.get("elapsed_seconds"));
			assertEquals(mineRoom.location(), location(e, ally));
			assertEquals(mineRoom.location(), arrived.get("current_location"));
			assertEquals(playerFigure,
					e.db().read(tx -> SceneService.actor(tx, Ref.id(campaign, Ref.CAMPAIGN), "character:1").asMap()));
		}
	}

	@Test
	void splitExplicitOriginsRefuseBeforeAnyRouteClockOrRandomMutation() throws Exception {
		try (Engine e = engine(tempDb("run8-split-origin"))) {
			String campaign = ready(e), home = location(e, "character:1"), ally = companion(e, campaign),
					mine = mine(e, campaign, home);
			quiet(e, campaign);
			e.world().move(op(), campaign, mine, List.of("character:1"), false, null, "The player travels ahead.");
			var before = e.db().snapshot(Ref.id(campaign, Ref.CAMPAIGN));
			((ScriptedRollService) e.roller()).queue(4);
			RpgException refused = assertThrows(RpgException.class, () -> e.world().move(op(), campaign, home,
					List.of("character:1", ally), true, 1, "Authorization cannot merge separated origins."));
			assertTrue(refused.getMessage().contains("different locations"));
			assertEquals(2, SceneService.maps(refused.details().get("origins")).size());
			assertEquals(before, e.db().snapshot(Ref.id(campaign, Ref.CAMPAIGN)));
			assertEquals(4, e.roller().roll("1d6").total());
		}
	}

	@Test
	void initialAndSavedTravelRefusalsGiveContextualEscapesAndUniqueConditionNames() throws Exception {
		try (Engine e = engine(tempDb("run8-travel-refusal-context"))) {
			String campaign = ready(e), home = location(e, "character:1"), ally = companion(e, campaign),
					mine = mine(e, campaign, home);
			e.runtime().applyRuntimeChange(op(), campaign, ally, map("kind", "ADD_CONDITION", "condition",
					"UNCONSCIOUS", "reason", "The companion needs recovery."));
			var initial = assertThrows(RpgException.class,
					() -> e.world().move(op(), campaign, mine, null, false, null, "Depart."));
			assertTrue(initial.getMessage().contains("before departure"));
			assertFalse(initial.getMessage().contains("saved travelers"));
			e.runtime().applyRuntimeChange(op(), campaign, ally,
					map("kind", "REMOVE_CONDITION", "condition", "UNCONSCIOUS", "reason", "The companion recovers."));
			journeys(e).defineRisk(op(), campaign, "DEFAULT",
					map("frequency_seconds", 3600, "chance_percent", 0, "authored_at_fraction", .5));
			var outward = e.world().move(op(), campaign, mine, null, false, null, "Follow the road.");
			int hp = e.db().read(tx -> tx.get("character", Ref.id(ally, Ref.CHARACTER)).intOr("current_hp", 0));
			e.runtime().applyRuntimeChange(op(), campaign, ally,
					map("kind", "DAMAGE", "amount", hp, "reason", "The companion is Dying at the saved interruption."));
			var before = e.db().snapshot(Ref.id(campaign, Ref.CAMPAIGN));
			var turnBack = assertThrows(RpgException.class,
					() -> e.world().move(op(), campaign, home, List.of("character:1"), false, null, "Turn back."));
			assertTrue(turnBack.getMessage().contains("characters"));
			assertTrue(turnBack.getMessage().contains("pending travel opportunity"));
			assertEquals(mine, turnBack.details().get("saved_destination"));
			var blocked = assertThrows(RpgException.class, () -> journeys(e).advance(op(), campaign, 100));
			assertTrue(blocked.getMessage().contains("other saved travelers"));
			List<?> conditions = (List<?>) blocked.details().get("conditions");
			assertEquals(conditions.size(), new LinkedHashSet<>(conditions).size());
			assertTrue(conditions.contains("UNCONSCIOUS"));
			assertEquals(before, e.db().snapshot(Ref.id(campaign, Ref.CAMPAIGN)));
			assertEquals(outward.get("journey"), journeys(e).state(campaign).get("journey"));
		}
	}

	@Test
	void aClosedContactRoadCannotBeBypassedByAuthorizedTravel() throws Exception {
		try (Engine e = engine(tempDb("run8-contact-closed"))) {
			Contact contact = leftBehind(e);
			e.world().materialize(op(), contact.campaign(), contact.mine(),
					map("extend", true, "connections",
							List.of(map("to", contact.home(), "connection_ref", contact.road(), "state", "BLOCKED"))),
					"GM");
			var before = e.db().snapshot(Ref.id(contact.campaign(), Ref.CAMPAIGN));
			var blocked = assertThrows(RpgException.class, () -> e.world().move(op(), contact.campaign(),
					contact.mine(), List.of(contact.ally()), true, 1, "Cannot authorize past the authored blockage."));
			assertTrue(blocked.getMessage().contains("no longer traversable"));
			assertEquals(before, e.db().snapshot(Ref.id(contact.campaign(), Ref.CAMPAIGN)));
		}
	}

	@Test
	void remoteAndContactTravelCannotArriveBeyondALockedInteriorDoorButOpenPathsPermitReunion() throws Exception {
		for (boolean contactOrigin : List.of(false, true)) {
			try (Engine e = engine(tempDb("run8-interior-arrival-" + contactOrigin))) {
				String campaign, home, ally, mine;
				if (contactOrigin) {
					Contact contact = leftBehind(e);
					campaign = contact.campaign();
					home = contact.home();
					ally = contact.ally();
					mine = contact.mine();
				} else {
					campaign = ready(e);
					home = location(e, "character:1");
					ally = companion(e, campaign);
					mine = mine(e, campaign, home);
					quiet(e, campaign);
					e.world().move(op(), campaign, mine, List.of("character:1"), false, null, "The player goes ahead.");
				}
				var prep = e.maps().begin(op(), campaign, "SCENE", map("name", "Entry and locked vault", "location_ref",
						mine, "geometry", map("bounds_ft", List.of(0, 0, 40, 20)), "rooms",
						List.of(map("key", "vault", "rect_ft", List.of(20, 0, 20, 20)),
								map("key", "entry", "role", "ENTRY", "rect_ft", List.of(0, 0, 20, 20))),
						"connections", List.of(map("key", "vault-door", "from", "entry", "to", "vault", "kind", "DOOR",
								"state", "LOCKED", "from_ft", List.of(20, 5), "to_ft", List.of(20, 10)))));
				var published = e.maps().publish(op(), campaign, prep.get("preparation").toString(), null);
				String vault = SceneService.map(published.get("references")).get("room:vault").toString(),
						entry = SceneService.map(published.get("references")).get("room:entry").toString();
				e.scenes().place(op(), campaign, published.get("map_ref").toString(),
						List.of(map("character", "character:1", "x_ft", 25, "y_ft", 5)));
				var before = e.db().snapshot(Ref.id(campaign, Ref.CAMPAIGN));
				var blocked = assertThrows(RpgException.class, () -> e.world().move(op(), campaign, vault,
						List.of(ally), false, null, "A reunion must respect the locked interior door."));
				assertTrue(blocked.getMessage().contains("does not provide an open entry"));
				assertEquals(before, e.db().snapshot(Ref.id(campaign, Ref.CAMPAIGN)));
				e.world().materialize(op(), campaign, vault,
						map("extend", true, "connections", List.of(map("to", entry, "state", "OPEN"))), "GM");
				quiet(e, campaign);
				String road = e.db().read(tx -> Ref.of(Ref.CONNECTION,
						tx.query("SELECT * FROM location_connection WHERE kind='ROAD'").getFirst().id()));
				if (contactOrigin) {
					journeys(e).defineRisk(op(), campaign, road, map("frequency_seconds", 3600, "chance_percent", 0));
				} else
					journeys(e).defineRisk(op(), campaign, road,
							map("frequency_seconds", 3600, "chance_percent", 0, "authored_at_fraction", .5));
				var arrived = e.world().move(op(), campaign, vault, List.of(ally), false, null,
						"The authored interior path is open.");
				long elapsed = ((Number) arrived.get("elapsed_seconds")).longValue();
				if (!contactOrigin) {
					assertEquals(3060L, arrived.get("total_seconds"));
					assertEquals(1500L, arrived.get("progress_seconds"),
							"The appended interior minute must not shift the fixed road midpoint.");
					assertEquals(.5,
							((Number) SceneService.map(arrived.get("anchor")).get("leg_fraction")).doubleValue());
					journeys(e).resolveOpportunity(op(), campaign, "RESOLVED");
					arrived = journeys(e).advance(op(), campaign, 3060);
					elapsed += ((Number) arrived.get("elapsed_seconds")).longValue();
				}
				assertEquals("COMPLETED", arrived.get("status"));
				assertEquals(vault, location(e, ally));
				assertEquals(vault, location(e, "character:1"));
				assertEquals(contactOrigin ? 1560L : 3060L, elapsed,
						"Every origin pays its actual road distance and the authored entry-to-vault passage.");
			}
		}
	}

	private record Contact(String campaign, String home, String mine, String ally, String place, String road) {
	}

	private record Room(String map, String location) {
	}

	private static Contact leftBehind(Engine e) {
		String campaign = ready(e), home = location(e, "character:1"), ally = companion(e, campaign),
				mine = mine(e, campaign, home);
		String road = e.db().read(tx -> Ref.of(Ref.CONNECTION,
				tx.query("SELECT * FROM location_connection ORDER BY id DESC").getFirst().id()));
		journeys(e).defineRisk(op(), campaign, road,
				map("frequency_seconds", 3600, "chance_percent", 0, "authored_at_fraction", .5));
		e.world().move(op(), campaign, mine, null, false, null, "Travel halfway along the authored road.");
		String foe = e.runtime().materialize(op(), campaign, "Guard", "Road watcher", null, null, null, null, false)
				.get("character").toString();
		var start = e.encounters().start(op(), campaign,
				map("party", List.of("character:1", ally), "foes", List.of(foe)), null, null, null, null, null);
		e.encounters().end(op(), campaign, start.get("encounter").toString(), "OTHER",
				"The road contact ends peacefully.");
		journeys(e).resolveOpportunity(op(), campaign, "RESOLVED");
		e.world().move(op(), campaign, mine, List.of("character:1"), false, null,
				"The player continues without Oddny.");
		return new Contact(campaign, home, mine, ally, location(e, ally), road);
	}

	private static Room room(Engine e, String campaign, String location, String name) {
		var draft = e.maps().begin(op(), campaign, "SCENE",
				map("name", name, "location_ref", location, "geometry", map("bounds_ft", List.of(0, 0, 20, 20)),
						"rooms", List.of(map("key", "room", "rect_ft", List.of(0, 0, 20, 20)))));
		var published = e.maps().publish(op(), campaign, draft.get("preparation").toString(), null);
		return new Room(published.get("map_ref").toString(),
				SceneService.map(published.get("references")).get("room:room").toString());
	}

	private static String ready(Engine e) {
		String c = committedCampaign(e);
		e.sessions().bootstrap(op(), c, null);
		return c;
	}

	private static String companion(Engine e, String c) {
		String a = e.runtime().materialize(op(), c, "Guard", "Oddny", null, null, null, null, false).get("character")
				.toString();
		e.party().updateMembership(op(), c, a, "JOIN", "Travels with the player.");
		return a;
	}

	private static String mine(Engine e, String c, String home) {
		return e.world()
				.materialize(op(), c, null,
						map("name", "Mine", "kind", "SITE", "connections",
								List.of(map("to", home, "kind", "ROAD", "travel_minutes", 50))),
						"GM")
				.get("ref").toString();
	}

	private static String location(Engine e, String c) {
		return e.db()
				.read(tx -> Ref.of(Ref.LOCATION, tx.get("character", Ref.id(c, Ref.CHARACTER)).lng("location_id")));
	}

	private static long clock(Engine e, String c) {
		return e.db().read(tx -> GameTime.currentSeconds(tx, Ref.id(c, Ref.CAMPAIGN)));
	}

	private static TravelJourneyService journeys(Engine e) {
		return new TravelJourneyService(e.db(), e.rules(), e.roller());
	}

	private static void quiet(Engine e, String c) {
		journeys(e).defineRisk(op(), c, "DEFAULT", map("frequency_seconds", 3600, "chance_percent", 0));
	}
}
