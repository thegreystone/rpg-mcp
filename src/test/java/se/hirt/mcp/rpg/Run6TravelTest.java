/* Copyright (C) 2026 Marcus Hirt. SPDX-License-Identifier: BSD-3-Clause */
package se.hirt.mcp.rpg;

import org.junit.jupiter.api.Test;
import se.hirt.mcp.rpg.dice.ScriptedRollService;
import se.hirt.mcp.rpg.maps.SceneService;
import se.hirt.mcp.rpg.protocol.ErrorCode;
import se.hirt.mcp.rpg.protocol.Ref;
import se.hirt.mcp.rpg.protocol.RpgException;
import se.hirt.mcp.rpg.session.GameTime;
import se.hirt.mcp.rpg.world.TravelJourneyService;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static se.hirt.mcp.rpg.TestCampaigns.*;

class Run6TravelTest {
	@Test
	void unaidedDepartureRefusesIncapacitatingConditionsBeforeRouteTimeOrRandomness() throws Exception {
		try (Engine e = engine(tempDb("run6-travel-conditions"))) {
			String campaign = ready(e), mine = mine(e, campaign);
			long cid = Ref.id(campaign, Ref.CAMPAIGN);
			for (String condition : List.of("UNCONSCIOUS", "PARALYZED", "PETRIFIED", "GRAPPLED", "RESTRAINED",
					"INCAPACITATED", "STUNNED")) {
				e.runtime().applyRuntimeChange(op(), campaign, "character:1", map("kind", "ADD_CONDITION", "condition",
						condition, "reason", "Resolved condition before travel."));
				var before = e.db().snapshot(cid);
				RpgException refusal = assertThrows(RpgException.class, () -> e.world().move(op(), campaign, mine, null,
						true, 1, "This authorization must not bypass the mover's condition."));
				assertEquals(ErrorCode.OPERATION_NOT_ALLOWED, refusal.code());
				assertEquals("character:1", refusal.details().get("blocked_character"));
				assertEquals(before, e.db().snapshot(cid),
						"Refusal must precede ad-hoc routing and every clock/exposure mutation.");
				e.runtime().applyRuntimeChange(op(), campaign, "character:1", map("kind", "REMOVE_CONDITION",
						"condition", condition, "reason", "The condition is resolved."));
			}
			String dead = e.runtime()
					.materialize(op(), campaign, "Guard", "Fallen guard", null, null, null, null, false)
					.get("character").toString();
			e.runtime().applyRuntimeChange(op(), campaign, dead,
					map("kind", "DAMAGE", "amount", 100, "reason", "The guard died in a resolved incident."));
			var before = e.db().snapshot(cid);
			assertThrows(RpgException.class,
					() -> e.world().move(op(), campaign, mine, List.of(dead), false, null, null));
			assertEquals(before, e.db().snapshot(cid));
		}
	}

	@Test
	void dyingSavedMoverCannotResumeOrContinueAndHealingPreservesTheSavedJourney() throws Exception {
		try (Engine e = engine(tempDb("run6-dying-journey"))) {
			String campaign = ready(e), mine = mine(e, campaign);
			long cid = Ref.id(campaign, Ref.CAMPAIGN);
			var journeys = new TravelJourneyService(e.db(), e.rules(), e.roller());
			journeys.defineRisk(op(), campaign, "DEFAULT", map("frequency_seconds", 3600, "chance_percent", 0,
					"authored_at_fraction", .5, "story", "A road obstruction requires a pause."));
			var departure = e.world().move(op(), campaign, mine, null, false, null, "Follow the authored road.");
			assertEquals(true, departure.get("interrupted"));
			assertEquals(1500L, departure.get("progress_seconds"));
			int hp = e.db().read(tx -> tx.get("character", 1).intOr("current_hp", 0));
			e.runtime().applyRuntimeChange(op(), campaign, "character:1", map("kind", "DAMAGE", "amount", hp, "reason",
					"The resolved road incident leaves the traveler Dying."));
			// Resolving the story is allowed; it is not travel and spends no time.
			journeys.resolveOpportunity(op(), campaign, "RESOLVED");
			var before = e.db().snapshot(cid);
			long clock = e.db().read(tx -> GameTime.currentSeconds(tx, cid));
			dice(e).queue(4);
			for (boolean resume : List.of(false, true)) {
				RpgException refusal = assertThrows(RpgException.class, () -> {
					if (resume)
						e.world().move(op(), campaign, mine, null, false, null, "Resume the saved road.");
					else
						journeys.advance(op(), campaign, 1500);
				});
				assertTrue(refusal.getMessage().contains("cannot travel unaided"));
				assertEquals(before, e.db().snapshot(cid));
				assertEquals(1500L, journeys.state(campaign).get("progress_seconds"));
				assertEquals(clock, e.db().read(tx -> GameTime.currentSeconds(tx, cid)).longValue());
			}
			assertEquals(4, e.roller().roll("1d6").total(), "Refusals must not consume queued randomness.");
			e.runtime().applyRuntimeChange(op(), campaign, "character:1", map("kind", "HEAL", "amount", 1, "reason",
					"A previously resolved healing effect restores the traveler."));
			var arrived = journeys.advance(op(), campaign, 1500);
			assertEquals("COMPLETED", arrived.get("status"));
			assertEquals(departure.get("journey"), arrived.get("journey"));
			assertEquals(3000L, arrived.get("progress_seconds"));
			assertEquals(clock + 1500, e.db().read(tx -> GameTime.currentSeconds(tx, cid)).longValue());
		}
	}

	@Test
	void parentFocusPreservesFigurinesCanonicalRoomsAndClock() throws Exception {
		try (Engine e = engine(tempDb("run6-parent-focus"))) {
			String campaign = ready(e);
			long cid = Ref.id(campaign, Ref.CAMPAIGN);
			String home = e.db().read(tx -> "location:" + tx.get("campaign", cid).lng("current_location_id"));
			var prep = e.maps().begin(op(), campaign, "SCENE", map("name", "Tavern rooms", "location_ref", home,
					"geometry", map("bounds_ft", List.of(0, 0, 40, 20)), "rooms",
					List.of(map("key", "taproom", "rect_ft", List.of(0, 0, 20, 20)),
							map("key", "locked", "rect_ft", List.of(20, 0, 20, 20))),
					"connections",
					List.of(map("key", "door", "from", "taproom", "to", "locked", "kind", "DOOR", "state", "LOCKED"))));
			var published = e.maps().publish(op(), campaign, prep.get("preparation").toString(), null);
			String mapRef = published.get("map_ref").toString(),
					taproom = SceneService.map(published.get("references")).get("room:taproom").toString();
			e.scenes().place(op(), campaign, mapRef, List.of(map("character", "character:1", "x_ft", 5, "y_ft", 5)));
			var before = e.db().snapshot(cid);
			var focus = e.world().move(op(), campaign, home, null, false, null,
					"Focus on the enclosing tavern without moving its figurine.");
			assertEquals("COMPLETED", focus.get("status"));
			assertEquals(home, focus.get("destination"));
			assertEquals(taproom, focus.get("current_location"));
			assertEquals(mapRef, SceneService.map(focus.get("current_board")).get("map_ref"));
			assertEquals(1, SceneService.maps(focus.get("preserved_positions")).size());
			assertEquals(before, e.db().snapshot(cid),
					"Semantic parent focus must preserve physical position, canonical room and time.");
			String locked = SceneService.map(published.get("references")).get("room:locked").toString();
			assertThrows(RpgException.class, () -> e.world().move(op(), campaign, locked, null, false, null, null));
			assertEquals(before, e.db().snapshot(cid), "Parent focus cannot bypass an authored locked room passage.");
			e.rest().override(op(), campaign, "SET_LOCATION", "character:1",
					map("location", taproom, "clear_map_position", true),
					"Release the figurine without changing its canonical room.", null);
			var unplaced = e.db().snapshot(cid);
			assertThrows(RpgException.class, () -> e.world().move(op(), campaign, locked, null, false, null, null));
			assertEquals(unplaced, e.db().snapshot(cid),
					"Clearing a figurine cannot bypass the same locked sibling-room passage.");
		}
	}

	@Test
	void repeatPartyLongRestSurfacesTheActualTimingReasonAndDoesNotAdvanceTime() throws Exception {
		try (Engine e = engine(tempDb("run6-rest-timing-note"))) {
			String campaign = ready(e);
			e.rest().rest(op(), campaign, "LONG", null, null);
			long cid = Ref.id(campaign, Ref.CAMPAIGN),
					before = e.db().read(tx -> GameTime.currentSeconds(tx, Ref.id(campaign, Ref.CAMPAIGN)));
			var repeated = e.rest().rest(op(), campaign, "LONG", null, null);
			assertEquals(false, repeated.get("completed"));
			assertEquals(0L, repeated.get("elapsed_seconds"));
			assertTrue(repeated.get("note").toString().contains("at least 16 hours"));
			assertTrue(repeated.get("note").toString().contains("SRD 5.2.1"));
			assertEquals(before, e.db().read(tx -> GameTime.currentSeconds(tx, cid)).longValue());
			RpgException named = assertThrows(RpgException.class,
					() -> e.rest().rest(op(), campaign, "LONG", null, List.of("character:1")));
			assertTrue(named.getMessage().contains("at least 16 hours"));
		}
	}

	private static String ready(Engine e) {
		String campaign = committedCampaign(e);
		e.sessions().bootstrap(op(), campaign, null);
		return campaign;
	}

	private static String mine(Engine e, String campaign) {
		String home = e.db().read(
				tx -> "location:" + tx.get("campaign", Ref.id(campaign, Ref.CAMPAIGN)).lng("current_location_id"));
		return e.world()
				.materialize(op(), campaign, null,
						map("name", "Mine", "kind", "SITE", "connections",
								List.of(map("to", home, "kind", "ROAD", "travel_minutes", 50))),
						"GM")
				.get("ref").toString();
	}

	private static ScriptedRollService dice(Engine e) {
		return (ScriptedRollService) e.roller();
	}
}
