/* Copyright (C) 2026 Marcus Hirt. SPDX-License-Identifier: BSD-3-Clause */
package se.hirt.mcp.rpg;

import org.junit.jupiter.api.Test;
import se.hirt.mcp.rpg.dice.ScriptedRollService;
import se.hirt.mcp.rpg.maps.SceneService;
import se.hirt.mcp.rpg.persistence.Row;
import se.hirt.mcp.rpg.protocol.Json;
import se.hirt.mcp.rpg.protocol.Ref;
import se.hirt.mcp.rpg.protocol.RpgException;
import se.hirt.mcp.rpg.session.GameTime;
import se.hirt.mcp.rpg.world.TravelJourneyService;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static se.hirt.mcp.rpg.TestCampaigns.*;

class Run7TravelTest {

	@Test
	void contactDeathPrunesTheRosterPreservesTheBodyAndSurvivesReloadAndReplay() throws Exception {
		var file = tempDb("run7-dead-traveler");
		String campaign, mine, ally, journey, board;
		long clock, checks;
		try (Engine e = engine(file)) {
			campaign = ready(e);
			ally = npc(e, campaign, "Oddny", true);
			mine = mine(e, campaign);
			var outward = depart(e, campaign, mine, null);
			journey = outward.get("journey").toString();
			String foe = npc(e, campaign, "Road watcher", false);
			var start = e.encounters().start(op(), campaign,
					map("party", List.of("character:1", ally), "foes", List.of(foe)), null, null, null, null, null);
			board = SceneService.map(start.get("grid")).get("map_ref").toString();
			Row figure = actor(e, campaign, ally);
			e.runtime().applyRuntimeChange(op(), campaign, ally,
					map("kind", "DAMAGE", "amount", 1000, "reason", "The resolved road combat killed Oddny."));
			var ended = e.encounters().end(op(), campaign, start.get("encounter").toString(), "OTHER",
					"The road contact ends.");
			var roster = SceneService.map(ended.get("travel_roster"));
			assertEquals(List.of("character:1"), roster.get("movers"));
			var removed = removal(roster, ally);
			assertEquals("DEAD", removed.get("reason"));
			assertEquals("Oddny", removed.get("name"));
			assertEquals(board, removed.get("map_ref"));
			assertEquals(List.of(figure.get("x_ft"), figure.get("y_ft")), removed.get("move_origin_ft"));
			assertEquals(figure.asMap(), actor(e, campaign, ally).asMap(),
					"The body's figurine must remain untouched.");
			assertEquals(removed.get("left_at"), location(e, ally));
			assertEquals(1500L, roster.get("progress_seconds"));
			clock = clock(e, campaign);
			checks = checks(e);
		}
		try (Engine e = engine(file)) {
			var journeys = journeys(e);
			assertEquals(List.of("character:1"), journeys.state(campaign).get("movers"));
			assertEquals(board, removal(journeys.state(campaign), ally).get("map_ref"));
			journeys.resolveOpportunity(op(), campaign, "RESOLVED");
			String operation = op();
			((ScriptedRollService) e.roller()).queue(4);
			var arrival = e.world().move(operation, campaign, mine, null, false, null,
					"Continue without the fallen companion.");
			assertEquals(journey, arrival.get("journey"));
			assertEquals("COMPLETED", arrival.get("status"));
			assertEquals(1500L, arrival.get("elapsed_seconds"));
			assertEquals(3000L, arrival.get("progress_seconds"));
			assertEquals(List.of("character:1"), arrival.get("moved"));
			assertEquals("DEAD", removal(arrival, ally).get("reason"));
			assertEquals(mine, location(e, "character:1"));
			assertNotEquals(mine, location(e, ally));
			assertEquals(board, Ref.of(Ref.MAP, actor(e, campaign, ally).lng("scene_id")));
			assertEquals(clock + 1500, clock(e, campaign));
			assertEquals(checks, checks(e), "A consumed saved risk check must not be sampled again.");
			var snapshot = e.db().snapshot(Ref.id(campaign, Ref.CAMPAIGN));
			var replay = e.world().move(operation, campaign, mine, null, false, null,
					"Continue without the fallen companion.");
			assertEquals(true, replay.get("replayed"));
			var replayResult = new LinkedHashMap<>(replay);
			replayResult.remove("replayed");
			assertEquals(Json.hash(arrival), Json.hash(replayResult));
			assertEquals(snapshot, e.db().snapshot(Ref.id(campaign, Ref.CAMPAIGN)));
			assertEquals(4, e.roller().roll("1d6").total());
		}
	}

	@Test
	void legacyJourneyBlocksAliveIncapacitatedCompanionUntilAnExplicitNonemptySavedSubsetIsChosen() throws Exception {
		try (Engine e = engine(tempDb("run7-rescope-journey"))) {
			String campaign = ready(e), ally = npc(e, campaign, "Oddny", true), mine = mine(e, campaign);
			var outward = depart(e, campaign, mine, null);
			e.runtime().applyRuntimeChange(op(), campaign, ally,
					map("kind", "ADD_CONDITION", "condition", "UNCONSCIOUS", "reason", "Oddny cannot walk unaided."));
			journeys(e).resolveOpportunity(op(), campaign, "RESOLVED");
			String stranger = npc(e, campaign, "Unrelated traveler", false);
			long cid = Ref.id(campaign, Ref.CAMPAIGN), beforeClock = clock(e, campaign), beforeChecks = checks(e);
			var before = e.db().snapshot(cid);
			for (List<String> selection : java.util.Arrays.asList(null, List.<String> of(),
					List.of("character:1", stranger), List.of("character:999999"))) {
				RpgException refused = assertThrows(RpgException.class,
						() -> e.world().move(op(), campaign, mine, selection, false, null, "Resume the saved road."));
				assertEquals(before, e.db().snapshot(cid));
				if (selection == null) {
					assertEquals(ally, refused.details().get("blocked_character"));
					assertTrue(refused.getMessage().contains("characters"));
				}
			}
			assertEquals(List.of("character:1", ally), journeys(e).state(campaign).get("movers"));
			var memberships = e.db().read(
					tx -> tx.query("SELECT * FROM party_membership ORDER BY id").stream().map(Row::asMap).toList());
			var resumed = e.world().move(op(), campaign, mine, List.of("character:1"), false, null,
					"Leave Oddny safely at the contact.");
			assertEquals(outward.get("journey"), resumed.get("journey"));
			assertEquals(1500L, resumed.get("elapsed_seconds"));
			assertEquals(List.of("character:1"), resumed.get("moved"));
			assertEquals("EXPLICIT_SELECTION", removal(resumed, ally).get("reason"));
			assertEquals(removal(resumed, ally).get("left_at"), location(e, ally));
			assertNotEquals(mine, location(e, ally));
			assertEquals(beforeClock + 1500, clock(e, campaign));
			assertEquals(beforeChecks, checks(e));
			assertEquals(memberships, e.db().read(
					tx -> tx.query("SELECT * FROM party_membership ORDER BY id").stream().map(Row::asMap).toList()),
					"An explicit journey selection changes no party membership episode.");
		}
	}

	@Test
	void legacyResumePrunesFormerMembersButRetainsSeparatedMembersAndGuests() throws Exception {
		try (Engine e = engine(tempDb("run7-membership-roster"))) {
			String campaign = ready(e), former = npc(e, campaign, "Departed", true),
					separated = npc(e, campaign, "Separated", true), guest = npc(e, campaign, "Guest", false),
					independent = npc(e, campaign, "Explicit escort", false), mine = mine(e, campaign);
			e.party().updateMembership(op(), campaign, guest, "GUEST_ADD", "A guest travels on this road.");
			e.party().updateMembership(op(), campaign, independent, "GUEST_ADD",
					"An escort is a legitimate guest traveler.");
			depart(e, campaign, mine, List.of("character:1", former, separated, guest, independent));
			e.party().updateMembership(op(), campaign, former, "LEAVE", "The traveler has left the party.");
			e.party().updateMembership(op(), campaign, separated, "SEPARATE",
					"Still a party member on the saved road.");
			var read = journeys(e).state(campaign);
			assertEquals(List.of("character:1", separated, guest, independent), read.get("movers"));
			assertEquals("NO_CURRENT_PARTY_MEMBERSHIP", removal(read, former).get("reason"));
			assertEquals(true, read.get("roster_reconciliation_pending"));
			journeys(e).resolveOpportunity(op(), campaign, "RESOLVED");
			var continued = journeys(e).advance(op(), campaign, 100);
			assertEquals(read.get("movers"), continued.get("movers"));
			assertEquals(1600L, continued.get("progress_seconds"));
			assertEquals("NO_CURRENT_PARTY_MEMBERSHIP", removal(continued, former).get("reason"));
			assertEquals(1L, e.db()
					.read(tx -> tx.count("SELECT COUNT(*) FROM event WHERE type='TRAVEL_ROSTER_CHANGED'")).longValue());
		}
	}

	@Test
	void selectingOnlyADeadTravelerCannotCancelTheHealthyTravelersSavedJourney() throws Exception {
		try (Engine e = engine(tempDb("run7-dead-only-selection"))) {
			String campaign = ready(e), ally = npc(e, campaign, "Oddny", true), mine = mine(e, campaign);
			var outward = depart(e, campaign, mine, null);
			e.runtime().applyRuntimeChange(op(), campaign, ally,
					map("kind", "DAMAGE", "amount", 1000, "reason", "Oddny died before resumption."));
			journeys(e).resolveOpportunity(op(), campaign, "RESOLVED");
			var before = e.db().snapshot(Ref.id(campaign, Ref.CAMPAIGN));
			RpgException refused = assertThrows(RpgException.class, () -> e.world().move(op(), campaign, mine,
					List.of(ally), false, null, "Select only the dead traveler."));
			assertTrue(refused.getMessage().contains("eligible saved traveler"));
			assertEquals(List.of("character:1"), refused.details().get("eligible_saved_travelers"));
			assertEquals(before, e.db().snapshot(Ref.id(campaign, Ref.CAMPAIGN)));
			assertEquals(outward.get("journey"), journeys(e).state(campaign).get("journey"));
			var continued = journeys(e).advance(op(), campaign, 100);
			assertEquals("ACTIVE", continued.get("status"));
			assertEquals(1600L, continued.get("progress_seconds"));
			assertEquals(List.of("character:1"), continued.get("movers"));
		}
	}

	@Test
	void emptyLegacyRosterCancelsWithoutSpendingTimeAndAllowsTheHealthyPlayerToTravel() throws Exception {
		try (Engine e = engine(tempDb("run7-empty-roster"))) {
			String campaign = ready(e), escort = npc(e, campaign, "Escort", false), mine = mine(e, campaign);
			e.party().updateMembership(op(), campaign, escort, "GUEST_ADD", "The escort travels as a party guest.");
			var outward = depart(e, campaign, mine, List.of(escort));
			e.runtime().applyRuntimeChange(op(), campaign, escort,
					map("kind", "DAMAGE", "amount", 1000, "reason", "The escort died during the interruption."));
			journeys(e).resolveOpportunity(op(), campaign, "RESOLVED");
			long clock = clock(e, campaign), checks = checks(e);
			String home = location(e, "character:1");
			var cancelled = journeys(e).advance(op(), campaign, 100);
			assertEquals("CANCELLED", cancelled.get("status"));
			assertEquals(false, cancelled.get("active"));
			assertTrue(cancelled.get("cancelled_reason").toString().contains("Escort (DEAD)"));
			assertEquals("DEAD", removal(cancelled, escort).get("reason"));
			assertEquals(1500L, cancelled.get("progress_seconds"));
			assertEquals(0L, cancelled.get("elapsed_seconds"));
			assertEquals(clock, clock(e, campaign));
			assertEquals(checks, checks(e));
			assertEquals(home, location(e, "character:1"));
			assertEquals(false, journeys(e).state(campaign).get("active"));
			var next = e.world().move(op(), campaign, mine, List.of("character:1"), false, null,
					"The healthy player now takes the known road.");
			assertNotEquals(outward.get("journey"), next.get("journey"));
			assertEquals(List.of("character:1"), next.get("movers"));
			assertEquals(1500L, next.get("progress_seconds"));
			assertEquals(clock + 1500, clock(e, campaign));
		}
	}

	@Test
	void onlyNpcTravelersDyingAtAContactCancelTheJourneyWithoutMovingTheirBodiesOrTheHealthyPlayer() throws Exception {
		try (Engine e = engine(tempDb("run7-empty-contact-roster"))) {
			String campaign = ready(e), escort = npc(e, campaign, "Escort", false), mine = mine(e, campaign);
			e.party().updateMembership(op(), campaign, escort, "GUEST_ADD", "The escort travels as a party guest.");
			var contact = depart(e, campaign, mine, List.of(escort));
			String home = location(e, "character:1"), foe = e.runtime().materialize(op(), campaign, "Guard", "Watcher",
					null, null, null, contact.get("contact_location").toString(), false).get("character").toString();
			long startedAt = clock(e, campaign);
			var start = e.encounters().start(op(), campaign, map("party", List.of(escort), "foes", List.of(foe)), null,
					null, null, null, null);
			var figure = actor(e, campaign, escort).asMap();
			e.runtime().applyRuntimeChange(op(), campaign, escort,
					map("kind", "DAMAGE", "amount", 1000, "reason", "The only traveler died at the contact."));
			long checks = checks(e);
			var ended = e.encounters().end(op(), campaign, start.get("encounter").toString(), "OTHER",
					"Nobody remains on this journey.");
			var cancelled = SceneService.map(ended.get("travel_roster"));
			assertEquals("CANCELLED", cancelled.get("status"));
			assertEquals(1500L, cancelled.get("progress_seconds"));
			assertEquals(0L, cancelled.get("elapsed_seconds"));
			assertEquals(startedAt + ((Number) ended.get("elapsed_seconds")).longValue(), clock(e, campaign),
					"Only the encounter's charged time passes.");
			assertEquals(checks, checks(e));
			assertEquals(figure, actor(e, campaign, escort).asMap());
			assertEquals(home, location(e, "character:1"));
			assertEquals("ALIVE", e.characters().characterSheet(campaign, "character:1", "SUMMARY").get("life_state"));
			assertEquals(false, journeys(e).state(campaign).get("active"));
			var next = e.world().move(op(), campaign, mine, List.of("character:1"), false, null,
					"The player can take the known road.");
			assertEquals(List.of("character:1"), next.get("movers"));
		}
	}

	@Test
	void explicitOmissionOfTheControlledCharacterLeavesFocusAndFigurineAtTheContact() throws Exception {
		try (Engine e = engine(tempDb("run7-pc-left-behind"))) {
			String campaign = ready(e), ally = npc(e, campaign, "Oddny", true), mine = mine(e, campaign);
			depart(e, campaign, mine, null);
			String foe = npc(e, campaign, "Watcher", false);
			var start = e.encounters().start(op(), campaign,
					map("party", List.of("character:1", ally), "foes", List.of(foe)), null, null, null, null, null);
			e.encounters().end(op(), campaign, start.get("encounter").toString(), "OTHER", "A peaceful pause.");
			var figure = actor(e, campaign, "character:1").asMap();
			e.runtime().applyRuntimeChange(op(), campaign, "character:1", map("kind", "ADD_CONDITION", "condition",
					"UNCONSCIOUS", "reason", "The controlled traveler needs care."));
			journeys(e).resolveOpportunity(op(), campaign, "RESOLVED");
			assertThrows(RpgException.class, () -> journeys(e).advance(op(), campaign, 100));
			var continued = e.world().move(op(), campaign, mine, List.of(ally), false, null, "Oddny continues alone.");
			assertEquals(List.of(ally), continued.get("moved"));
			assertEquals(mine, location(e, ally));
			assertEquals(figure, actor(e, campaign, "character:1").asMap());
			assertEquals(removal(continued, "character:1").get("left_at"), location(e, "character:1"));
			assertEquals(location(e, "character:1"), continued.get("current_location"));
			assertNotEquals(mine, continued.get("current_location"));
		}
	}

	@Test
	void deadControlledCharacterStillRequiresAContinuationDecisionBeforeOtherTravelersAdvance() throws Exception {
		try (Engine e = engine(tempDb("run7-dead-pc-gate"))) {
			String campaign = ready(e), ally = npc(e, campaign, "Oddny", true), mine = mine(e, campaign);
			depart(e, campaign, mine, null);
			String foe = npc(e, campaign, "Watcher", false);
			var start = e.encounters().start(op(), campaign,
					map("party", List.of("character:1", ally), "foes", List.of(foe)), null, null, null, null, null);
			e.runtime().applyRuntimeChange(op(), campaign, "character:1",
					map("kind", "DAMAGE", "amount", 1000, "reason", "The controlled traveler died."));
			e.encounters().end(op(), campaign, start.get("encounter").toString(), "OTHER",
					"The surviving traveler holds position.");
			var before = e.db().snapshot(Ref.id(campaign, Ref.CAMPAIGN));
			assertThrows(RpgException.class, () -> journeys(e).advance(op(), campaign, 100));
			assertThrows(RpgException.class,
					() -> e.world().move(op(), campaign, mine, List.of(ally), false, null, "Continue."));
			assertEquals(before, e.db().snapshot(Ref.id(campaign, Ref.CAMPAIGN)));
		}
	}

	private static String ready(Engine e) {
		String campaign = committedCampaign(e);
		e.sessions().bootstrap(op(), campaign, null);
		return campaign;
	}

	private static String npc(Engine e, String campaign, String name, boolean join) {
		String ref = e.runtime().materialize(op(), campaign, "Guard", name, null, null, null, null, false)
				.get("character").toString();
		if (join)
			e.party().updateMembership(op(), campaign, ref, "JOIN", "A road companion.");
		return ref;
	}

	private static String mine(Engine e, String campaign) {
		String home = e.db().read(tx -> Ref.of(Ref.LOCATION,
				tx.get("campaign", Ref.id(campaign, Ref.CAMPAIGN)).lng("current_location_id")));
		return e.world()
				.materialize(op(), campaign, null,
						map("name", "Mine", "kind", "SITE", "connections",
								List.of(map("to", home, "kind", "ROAD", "travel_minutes", 50))),
						"GM")
				.get("ref").toString();
	}

	private static Map<String, Object> depart(Engine e, String campaign, String mine, List<String> selected) {
		journeys(e).defineRisk(op(), campaign, "DEFAULT", map("frequency_seconds", 3600, "chance_percent", 0,
				"authored_at_fraction", .5, "story", "An authored contact halfway along the road."));
		var result = e.world().move(op(), campaign, mine, selected, false, null, "Follow the saved road.");
		assertEquals(1500L, result.get("progress_seconds"));
		return result;
	}

	private static TravelJourneyService journeys(Engine e) {
		return new TravelJourneyService(e.db(), e.rules(), e.roller());
	}

	private static Map<String, Object> removal(Map<String, Object> report, String ref) {
		return SceneService.maps(report.get("removed_travelers")).stream()
				.filter(row -> ref.equals(row.get("character"))).findFirst().orElseThrow();
	}

	private static Row actor(Engine e, String campaign, String ref) {
		return e.db().read(tx -> SceneService.actor(tx, Ref.id(campaign, Ref.CAMPAIGN), ref));
	}

	private static String location(Engine e, String ref) {
		return e.db()
				.read(tx -> Ref.of(Ref.LOCATION, tx.get("character", Ref.id(ref, Ref.CHARACTER)).lng("location_id")));
	}

	private static long clock(Engine e, String campaign) {
		return e.db().read(tx -> GameTime.currentSeconds(tx, Ref.id(campaign, Ref.CAMPAIGN)));
	}

	private static long checks(Engine e) {
		return e.db().read(tx -> tx.count("SELECT COUNT(*) FROM travel_check"));
	}
}
