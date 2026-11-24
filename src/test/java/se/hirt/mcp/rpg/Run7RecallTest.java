/* Copyright (C) 2026 Marcus Hirt. SPDX-License-Identifier: BSD-3-Clause */
package se.hirt.mcp.rpg;

import org.junit.jupiter.api.Test;
import se.hirt.mcp.rpg.dice.ScriptedRollService;
import se.hirt.mcp.rpg.ledger.LedgerService;
import se.hirt.mcp.rpg.maps.SceneService;
import se.hirt.mcp.rpg.protocol.Json;
import se.hirt.mcp.rpg.protocol.Ref;
import se.hirt.mcp.rpg.world.TravelJourneyService;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static se.hirt.mcp.rpg.TestCampaigns.*;

class Run7RecallTest {

	@Test
	void fourteenCompletedFightsDoNotRepeatTheirFullDigestsAtEveryBootstrap() throws Exception {
		try (Engine e = engine(tempDb("run7-recent-fights"))) {
			String campaign = ready(e);
			String foe = e.runtime()
					.materialize(op(), campaign, "Bandit", "The patient challenger", null, null, null, null, false)
					.get("character").toString();
			for (int i = 0; i < 14; i++) {
				((ScriptedRollService) e.roller()).queue(20, 1);
				String encounter = e.encounters().start(op(), campaign,
						map("party", List.of("character:1"), "foes", List.of(foe)), null, null, null, null, null)
						.get("encounter").toString();
				e.encounters().perform(op(), campaign, encounter, "character:1", map("kind", "DODGE"), true);
				e.encounters().perform(op(), campaign, encounter, foe, map("kind", "DODGE"), true);
				e.encounters().end(op(), campaign, encounter, "OTHER", "Practice bout " + (i + 1) + " ends.",
						"The witnesses remember the harmless exchange. ".repeat(28));
			}
			var timeline = SceneService.maps(
					e.ledger().queryTimeline(campaign, null, null, null, List.of("ENCOUNTER_RESOLVED"), null, false, 30)
							.get("events"));
			assertEquals(14, timeline.size());
			assertTrue(timeline.stream().allMatch(row -> row.containsKey("encounter_digest")));
			String before = Json.hash(timeline);
			var boot = e.sessions().bootstrap(op(), campaign, null);
			var recent = SceneService.maps(boot.get("recent_events"));
			assertTrue(recent.size() <= 8);
			var detailed = recent.stream().filter(row -> row.containsKey("encounter_digest")).toList();
			assertFalse(detailed.isEmpty());
			assertTrue(detailed.size() <= 2);
			assertTrue(detailed.stream()
					.mapToInt(row -> LedgerService.serializedBytes(SceneService.map(row.get("encounter_digest"))))
					.sum() <= 3000);
			var abbreviated = recent.stream().filter(row -> Boolean.TRUE.equals(row.get("encounter_digest_omitted")))
					.toList();
			assertFalse(abbreviated.isEmpty());
			for (var row : abbreviated) {
				assertTrue(row.get("encounter").toString().startsWith("encounter:"));
				assertEquals("get_encounter_state", SceneService.map(row.get("full_gm_record")).get("tool"));
				assertTrue(row.get("summary").toString().contains("Encounter"));
			}
			assertTrue(Json.write(recent).length() < Json.write(timeline).length() / 2);
			assertTrue(Json.utf8(Json.write(boot)).length < 26000,
					"Normal repeated fights must not bloat every bootstrap.");
			assertEquals(before,
					Json.hash(e.ledger()
							.queryTimeline(campaign, null, null, null, List.of("ENCOUNTER_RESOLVED"), null, false, 30)
							.get("events")));
			assertTrue(((ScriptedRollService) e.roller()).exhausted());
		}
	}

	@Test
	void timelineLightingRecallNamesTheResultingMapAndRoomLightAndTheReason() throws Exception {
		try (Engine e = engine(tempDb("run7-lighting-summary"))) {
			String campaign = ready(e), home = home(e, campaign);
			var draft = e.maps().begin(op(), campaign, "SCENE",
					map("name", "The old temple", "location_ref", home, "illumination", "BRIGHT", "geometry",
							map("bounds_ft", List.of(0, 0, 40, 20)), "rooms",
							List.of(map("key", "altar", "rect_ft", List.of(0, 0, 20, 20)),
									map("key", "vault", "rect_ft", List.of(20, 0, 20, 20))),
							"connections", List.of(map("key", "vault-door", "from", "altar", "to", "vault", "kind",
									"DOOR", "state", "OPEN", "from_ft", List.of(20, 5), "to_ft", List.of(20, 10)))));
			String scene = e.maps().publish(op(), campaign, draft.get("preparation").toString(), null).get("map_ref")
					.toString();
			long revision = e.db().read(tx -> tx.get("map_scene", Ref.id(scene, Ref.MAP)).lng("revision"));
			String reason = "The altar braziers burn; the vault candles are dim and the rest of the temple is dark.";
			e.maps().correctGeometry(op(), campaign, scene, revision, map("illumination", "DARK", "rooms",
					List.of(map("key", "altar", "illumination", "BRIGHT"), map("key", "vault", "illumination", "DIM"))),
					reason);
			var event = SceneService.maps(e.ledger()
					.queryTimeline(campaign, null, null, null, List.of("MAP_ILLUMINATION_CHANGED"), null, false, 30)
					.get("events")).getFirst();
			String summary = event.get("summary").toString();
			assertTrue(summary.contains("map DARK"), summary);
			assertTrue(summary.contains("altar BRIGHT"), summary);
			assertTrue(summary.contains("vault DIM"), summary);
			assertEquals(reason, event.get("detail"));
			assertEquals("GM_ONLY", event.get("visibility"));
		}
	}

	@Test
	void partyAndBootstrapDescribeActualJourneyMoversWithoutMovingTheCanonicalDeparture() throws Exception {
		try (Engine e = engine(tempDb("run7-party-journey"))) {
			String campaign = ready(e), home = home(e, campaign);
			String stayer = e.runtime()
					.materialize(op(), campaign, "Guard", "Mira stays at the inn", null, null, null, home, false)
					.get("character").toString();
			e.party().updateMembership(op(), campaign, stayer, "JOIN", "Mira keeps watch while Tam travels.");
			String mine = e.world()
					.materialize(op(), campaign, null,
							map("name", "The old mine", "kind", "SITE", "connections",
									List.of(map("to", home, "kind", "ROAD", "travel_minutes", 50))),
							"GM")
					.get("ref").toString();
			var travel = new TravelJourneyService(e.db(), e.rules(), e.roller());
			travel.defineRisk(op(), campaign, "DEFAULT", map("frequency_seconds", 3600, "chance_percent", 0,
					"authored_at_fraction", .5, "story", "A lookout pauses the journey."));
			e.world().move(op(), campaign, mine, List.of("character:1"), false, null, "Tam takes the mine road alone.");
			var before = e.db().snapshot(Ref.id(campaign, Ref.CAMPAIGN));
			var party = e.sessions().party(campaign, "SUMMARY");
			Map<String, Object> position = SceneService.map(party.get("location"));
			assertEquals("JOURNEY", position.get("kind"));
			assertFalse(position.containsKey("ref"));
			var mover = SceneService.maps(party.get("members")).stream()
					.filter(row -> "character:1".equals(row.get("ref"))).findFirst().orElseThrow();
			assertNull(mover.get("location"));
			assertEquals(home, mover.get("departure_location"));
			assertEquals(position, mover.get("location_context"));
			assertEquals(true, mover.get("with_party"));
			var left = SceneService.maps(party.get("members")).stream().filter(row -> stayer.equals(row.get("ref")))
					.findFirst().orElseThrow();
			assertEquals(home, left.get("location"));
			assertEquals(false, left.get("with_party"));
			assertFalse(left.containsKey("location_context"));
			assertEquals(before, e.db().snapshot(Ref.id(campaign, Ref.CAMPAIGN)), "get_party is a read.");
			var bootstrap = e.sessions().bootstrap(op(), campaign, null);
			assertEquals(position, bootstrap.get("location"));
			assertEquals(position, SceneService.map(bootstrap.get("player_character")).get("location_context"));
			assertEquals(home, e.db().read(tx -> Ref.of(Ref.LOCATION, tx.get("character", 1).lng("location_id"))));
			travel.resolveOpportunity(op(), campaign, "RESOLVED");
			travel.advance(op(), campaign, 1500);
			var arrived = e.sessions().party(campaign, "SUMMARY");
			assertEquals(mine, SceneService.map(arrived.get("location")).get("ref"));
			assertTrue(SceneService.maps(arrived.get("members")).stream()
					.noneMatch(row -> row.containsKey("departure_location")));
		}
	}

	@Test
	void aCompanionTravelingAloneDoesNotMoveThePlayersContextOntoTheirJourney() throws Exception {
		try (Engine e = engine(tempDb("run7-companion-journey-context"))) {
			String campaign = ready(e), home = home(e, campaign);
			String scout = e.runtime()
					.materialize(op(), campaign, "Guard", "Mira scouts alone", null, null, null, home, false)
					.get("character").toString();
			e.party().updateMembership(op(), campaign, scout, "JOIN", "Mira scouts ahead.");
			String mine = e.world()
					.materialize(op(), campaign, null,
							map("name", "The old mine", "kind", "SITE", "connections",
									List.of(map("to", home, "kind", "ROAD", "travel_minutes", 50))),
							"GM")
					.get("ref").toString();
			var travel = new TravelJourneyService(e.db(), e.rules(), e.roller());
			travel.defineRisk(op(), campaign, "DEFAULT", map("frequency_seconds", 3600, "chance_percent", 0,
					"authored_at_fraction", .5, "story", "A lookout pauses Mira's scouting trip."));
			e.world().move(op(), campaign, mine, List.of(scout), false, null, "Mira goes ahead while Tam waits.");
			var party = e.sessions().party(campaign, "SUMMARY");
			assertEquals(home, SceneService.map(party.get("location")).get("ref"));
			var mover = SceneService.maps(party.get("members")).stream().filter(row -> scout.equals(row.get("ref")))
					.findFirst().orElseThrow();
			assertEquals("JOURNEY", SceneService.map(mover.get("location_context")).get("kind"));
			assertEquals(false, mover.get("with_party"));
			var pc = SceneService.maps(party.get("members")).stream()
					.filter(row -> "character:1".equals(row.get("ref"))).findFirst().orElseThrow();
			assertEquals(home, pc.get("location"));
			assertEquals(true, pc.get("with_party"));
			var bootstrap = e.sessions().bootstrap(op(), campaign, null);
			assertEquals(home, SceneService.map(bootstrap.get("location")).get("ref"));
			assertFalse(SceneService.map(bootstrap.get("player_character")).containsKey("location_context"));
		}
	}

	private static String ready(Engine e) {
		String c = committedCampaign(e);
		e.sessions().bootstrap(op(), c, null);
		return c;
	}

	private static String home(Engine e, String c) {
		return e.db().read(
				tx -> Ref.of(Ref.LOCATION, tx.get("campaign", Ref.id(c, Ref.CAMPAIGN)).lng("current_location_id")));
	}
}
