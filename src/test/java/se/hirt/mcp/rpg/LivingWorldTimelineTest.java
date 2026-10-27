/* Copyright (C) 2026 Marcus Hirt. SPDX-License-Identifier: BSD-3-Clause */
package se.hirt.mcp.rpg;

import org.junit.jupiter.api.Test;
import se.hirt.mcp.rpg.persistence.Database;
import se.hirt.mcp.rpg.protocol.Ref;
import se.hirt.mcp.rpg.session.GameTime;
import se.hirt.mcp.rpg.world.PatrolService;
import se.hirt.mcp.rpg.world.TravelJourneyService;
import se.hirt.mcp.rpg.world.WorldTimeline;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static se.hirt.mcp.rpg.TestCampaigns.*;

class LivingWorldTimelineTest {

	@Test
	void searchAndRestStopBeforeBenefitsAndResumeAfterReload() throws Exception {
		Path path = tempDb("interrupted-work");
		String campaign, hero;
		long initial;
		try (Engine e = engine(path)) {
			campaign = committedCampaign(e);
			e.sessions().bootstrap(op(), campaign, null);
			hero = hero(e, campaign);
			long cid = Ref.id(campaign, "campaign");
			initial = e.db().read(tx -> GameTime.currentSeconds(tx, cid));
			new WorldTimeline(e.db()).schedule(op(), campaign, map("after_seconds", 180, "kind", "FOOTSTEPS", "payload",
					map("description", "Footsteps approach.")));
			Map<String, Object> stopped = new WorldTimeline(e.db()).perform(op(), campaign, "SEARCH", 600,
					"search-locker", Map.of());
			assertEquals(false, stopped.get("completed"));
			assertEquals(true, stopped.get("interrupted"));
			assertEquals(180L, stopped.get("completed_seconds"));
			assertEquals(initial + 180, e.db().read(tx -> GameTime.currentSeconds(tx, cid)).longValue());
		}
		try (Engine e = engine(path)) {
			long cid = Ref.id(campaign, "campaign");
			Map<String, Object> finished = new WorldTimeline(e.db()).perform(op(), campaign, "SEARCH", 600,
					"search-locker", Map.of());
			assertEquals(true, finished.get("completed"));
			assertEquals(420L, finished.get("elapsed_seconds"));
			e.db().mutate(Database.Mutation.of("wound-fixture", cid, op(), "GM", null), tx -> {
				tx.update("character", Ref.id(hero, "character"), Map.of("current_hp", 1));
				return Map.of();
			});
			new WorldTimeline(e.db()).schedule(op(), campaign, map("after_seconds", 180, "kind", "REST_INTERRUPT"));
			Map<String, Object> rest = e.rest().rest(op(), campaign, "LONG", null, List.of(hero));
			assertEquals(false, rest.get("completed"));
			assertEquals(1L,
					e.db().read(tx -> tx.get("character", Ref.id(hero, "character")).lng("current_hp")).longValue());
			assertNull(e.db().read(tx -> tx.get("character", Ref.id(hero, "character")).lng("last_long_rest_seq")));
			Map<String, Object> done = e.rest().rest(op(), campaign, "LONG", null, List.of(hero));
			assertEquals(true, done.get("completed"));
			assertEquals(e.db().read(tx -> tx.get("character", Ref.id(hero, "character")).lng("max_hp")).longValue(),
					e.db().read(tx -> tx.get("character", Ref.id(hero, "character")).lng("current_hp")).longValue());
		}
	}

	@Test
	void secondsAndCheckpointRestorationDoNotRoundEveryRoundUpToAMinute() throws Exception {
		try (Engine e = engine(tempDb("precise-rounds"))) {
			String c = committedCampaign(e);
			e.sessions().bootstrap(op(), c, null);
			long cid = Ref.id(c, "campaign");
			long from = e.db().read(tx -> GameTime.currentSeconds(tx, cid));
			for (int i = 0; i < 2; i++)
				e.db().mutate(Database.Mutation.of("round-fixture", cid, op(), "GM", null),
						tx -> WorldTimeline.advanceRound(tx, cid).toMap());
			assertEquals(from + 12, e.db().read(tx -> GameTime.currentSeconds(tx, cid)).longValue());
			String cp = (String) e.checkpoints().create(op(), c, "after two rounds").get("checkpoint");
			e.sessions().advanceTime(op(), c, 1, "wait");
			assertEquals(from + 72, e.db().read(tx -> GameTime.currentSeconds(tx, cid)).longValue());
			e.checkpoints().restore(op(), c, cp, "restore precise clock");
			assertEquals(from + 12, e.db().read(tx -> GameTime.currentSeconds(tx, cid)).longValue());
		}
	}

	@Test
	void regionalPatrolAndTimerSuspendForAbsenceWithoutCatchup() throws Exception {
		try (Engine e = engine(tempDb("regional-pause"))) {
			Fixture f = region(e);
			var patrols = new PatrolService(e.db());
			var timeline = new WorldTimeline(e.db());
			String patrol = definePatrol(e, f, 120);
			timeline.schedule(op(), f.campaign,
					map("after_seconds", 120, "kind", "LOCAL_BELL", "region_ref", f.region, "interrupting", false));
			timeline.perform(op(), f.campaign, "WAIT", 60, "first-minute", Map.of());
			e.world().move(op(), f.campaign, f.outside, null, false, null, "leave the region");
			long cid = Ref.id(f.campaign, "campaign"), pid = Ref.id(patrol, "patrol");
			assertEquals(60L, e.db().read(tx -> tx.get("patrol_plan", pid).lng("remaining_seconds")).longValue());
			e.sessions().advanceTime(op(), f.campaign, 100 * 24 * 60, "one hundred days away");
			assertEquals(0L, e.db().read(tx -> tx.get("patrol_plan", pid).lng("waypoint_index")).longValue());
			assertEquals("OPEN",
					e.db().read(tx -> tx.queryOne("SELECT status FROM timeline_event WHERE campaign_id=?", cid)
							.orElseThrow().str("status")));
			e.world().move(op(), f.campaign, f.region, null, false, null, "return");
			long returned = e.db().read(tx -> GameTime.currentSeconds(tx, cid));
			assertEquals(returned + 60,
					e.db().read(tx -> tx.get("patrol_plan", pid).lng("next_due_seconds")).longValue());
			timeline.perform(op(), f.campaign, "WAIT", 59, "almost-arrival", Map.of());
			assertEquals(0L, e.db().read(tx -> tx.get("patrol_plan", pid).lng("waypoint_index")).longValue());
			timeline.perform(op(), f.campaign, "WAIT", 1, "arrival", Map.of());
			assertEquals(1L, e.db().read(tx -> tx.get("patrol_plan", pid).lng("waypoint_index")).longValue());
			assertEquals("FIRED",
					e.db().read(tx -> tx.queryOne("SELECT status FROM timeline_event WHERE campaign_id=?", cid)
							.orElseThrow().str("status")));
			assertEquals("ACTIVE", patrols.state(f.campaign, patrol).get("status"));
		}
	}

	@Test
	void separatedCompanionKeepsRegionalTimeActiveAndExhaustedRosterNeverRespawns() throws Exception {
		Path path = tempDb("finite-roster");
		String c, p;
		long actors;
		try (Engine e = engine(path)) {
			Fixture f = region(e);
			c = f.campaign;
			p = definePatrol(e, f, 60);
			long cid = Ref.id(c, "campaign");
			String companion = (String) e.runtime()
					.materialize(op(), c, "Scout", "Mira", null, null, null, f.region, false).get("character");
			e.db().mutate(Database.Mutation.of("separated-party-fixture", cid, op(), "GM", null), tx -> {
				tx.insert("party_membership",
						map("campaign_id", cid, "character_id", Ref.id(companion, "character"), "state", "SEPARATED"));
				return Map.of();
			});
			e.world().move(op(), c, f.outside, List.of(f.hero), false, null, "Mira remains behind");
			new WorldTimeline(e.db()).perform(op(), c, "WAIT", 60, "while-separated", Map.of());
			assertEquals(0L,
					e.db().read(tx -> tx.get("patrol_plan", Ref.id(p, "patrol")).lng("waypoint_index")).longValue(),
					"two sixty-second legs have elapsed while Mira remained");
			actors = e.db().read(tx -> tx.count("SELECT COUNT(*) FROM character WHERE campaign_id=?", cid));
			e.db().mutate(Database.Mutation.of("defeat-guard-fixture", cid, op(), "GM", null), tx -> {
				tx.update("character", Ref.id(f.guard, "character"), Map.of("life_state", "DEAD", "current_hp", 0));
				return Map.of();
			});
			e.sessions().advanceTime(op(), c, 24 * 60, "the defeated patrol cannot return");
			assertEquals("DONE", new PatrolService(e.db()).state(c, p).get("status"));
		}
		try (Engine e = engine(path)) {
			e.sessions().advanceTime(op(), c, 24 * 60, "another day");
			assertEquals("DONE", new PatrolService(e.db()).state(c, p).get("status"));
			long cid = Ref.id(c, "campaign");
			assertEquals(actors,
					e.db().read(tx -> tx.count("SELECT COUNT(*) FROM character WHERE campaign_id=?", cid)).longValue());
		}
	}

	@Test
	void petrificationAndRestraintPauseTheExistingDutyUntilAnExplainedResumption() throws Exception {
		for (String condition : List.of("PETRIFIED", "RESTRAINED")) {
			Path path = tempDb("blocked-patrol-" + condition);
			Fixture f;
			String patrol;
			long count;
			try (Engine e = engine(path)) {
				f = region(e);
				patrol = definePatrol(e, f, 60);
				long cid = Ref.id(f.campaign, "campaign");
				count = e.db().read(tx -> tx.count("SELECT COUNT(*) FROM character WHERE campaign_id=?", cid));
				e.runtime().applyRuntimeChange(op(), f.campaign, f.guard, map("kind", "ADD_CONDITION", "condition",
						condition, "reason", "The guard cannot continue his duty."));
				new WorldTimeline(e.db()).perform(op(), f.campaign, "WAIT", 120, op(), Map.of());
				Map<String, Object> stopped = new PatrolService(e.db()).state(f.campaign, patrol);
				assertEquals("PAUSED", stopped.get("status"));
				assertEquals(0L, stopped.get("waypoint_index"));
				assertEquals(60L, stopped.get("remaining_seconds"));
				assertEquals(false, stopped.get("timer_active"));
			}
			try (Engine e = engine(path)) {
				var patrols = new PatrolService(e.db());
				assertEquals("PAUSED", patrols.state(f.campaign, patrol).get("status"));
				assertThrows(se.hirt.mcp.rpg.protocol.RpgException.class, () -> patrols.update(op(), f.campaign, patrol,
						null, map("status", "ACTIVE", "reason", "The condition is still present.")));
				e.runtime().applyRuntimeChange(op(), f.campaign, f.guard,
						map("kind", "REMOVE_CONDITION", "condition", condition, "reason", "The guard recovers."));
				patrols.update(op(), f.campaign, patrol, null,
						map("status", "ACTIVE", "reason", "The same recovered guard resumes his saved duty."));
				new WorldTimeline(e.db()).perform(op(), f.campaign, "WAIT", 59, op(), Map.of());
				assertEquals(0L, patrols.state(f.campaign, patrol).get("waypoint_index"));
				new WorldTimeline(e.db()).perform(op(), f.campaign, "WAIT", 1, op(), Map.of());
				assertEquals(1L, patrols.state(f.campaign, patrol).get("waypoint_index"));
				assertEquals(List.of(f.guard), patrols.state(f.campaign, patrol).get("actors"));
				long cid = Ref.id(f.campaign, "campaign");
				assertEquals(count, e.db()
						.read(tx -> tx.count("SELECT COUNT(*) FROM character WHERE campaign_id=?", cid)).longValue());
			}
		}
	}

	@Test
	void legacyEncounterCompletionProcessesTheSharedClockAndOnlyPresentRegionalTimers() throws Exception {
		try (Engine e = engine(tempDb("legacy-encounter-timeline"))) {
			Fixture f = region(e);
			long cid = Ref.id(f.campaign, "campaign");
			var timeline = new WorldTimeline(e.db());
			timeline.schedule(op(), f.campaign, map("after_seconds", 20, "kind", "LOCAL_BELL", "region_ref", f.region));
			timeline.schedule(op(), f.campaign,
					map("after_seconds", 20, "kind", "DISTANT_BELL", "region_ref", f.outside));
			timeline.schedule(op(), f.campaign, map("after_seconds", 20, "kind", "GLOBAL_EVENT"));
			long initial = e.db().read(tx -> GameTime.currentSeconds(tx, cid));
			String encounter = (String) e.encounters().start(op(), f.campaign,
					map("party", List.of(f.hero), "bandits", List.of(f.guard)), null, null, null, null, null)
					.get("encounter");
			// This is the persisted shape a campaign created before grids has when it resumes.
			e.db().mutate(Database.Mutation.of("legacy-encounter-fixture", cid, op(), "GM", null), tx -> {
				var row = tx.get("encounter", Ref.id(encounter, "encounter"));
				var environment = row.map("environment_json");
				environment.remove("grid");
				tx.update("encounter", row.id(), map("spatial_model", "ZONES", "environment_json", environment));
				return Map.of();
			});
			Map<String, Object> ended = e.encounters().end(op(), f.campaign, encounter, "OTHER",
					"The bandits withdraw.");
			assertEquals(initial + 60, e.db().read(tx -> GameTime.currentSeconds(tx, cid)).longValue());
			assertEquals(60L, ended.get("elapsed_seconds"));
			assertEquals(2, ((List<?>) ended.get("consequences")).size());
			assertEquals("FIRED", e.db().read(tx -> tx
					.queryOne("SELECT status FROM timeline_event WHERE campaign_id=? AND kind='LOCAL_BELL'", cid)
					.orElseThrow().str("status")));
			assertEquals("FIRED", e.db().read(tx -> tx
					.queryOne("SELECT status FROM timeline_event WHERE campaign_id=? AND kind='GLOBAL_EVENT'", cid)
					.orElseThrow().str("status")));
			assertEquals("OPEN", e.db().read(tx -> tx
					.queryOne("SELECT status FROM timeline_event WHERE campaign_id=? AND kind='DISTANT_BELL'", cid)
					.orElseThrow().str("status")));
			assertEquals(20L,
					e.db().read(tx -> tx.queryOne(
							"SELECT remaining_seconds FROM timeline_event WHERE campaign_id=? AND kind='DISTANT_BELL'",
							cid).orElseThrow().lng("remaining_seconds")).longValue());
		}
	}

	@Test
	void authoredTravelContactStopsAtItsSavedRouteAnchorAndContinuesOnce() throws Exception {
		Path path = tempDb("story-travel");
		String campaign, target;
		long initial;
		try (Engine e = engine(path)) {
			Fixture f = region(e);
			campaign = f.campaign;
			target = f.outside;
			var travel = new TravelJourneyService(e.db(), e.roller());
			travel.defineRisk(op(), campaign, "DEFAULT",
					map("frequency_seconds", 60, "chance_percent", 0, "authored_at_fraction", .5, "opposition",
							List.of("The tollhouse bandits"), "story", "A disputed ancient toll."));
			long cid = Ref.id(campaign, "campaign");
			initial = e.db().read(tx -> GameTime.currentSeconds(tx, cid));
			Map<String, Object> stopped = e.world().move(op(), campaign, target, null, false, 10,
					"take the northern road");
			assertEquals(true, stopped.get("interrupted"));
			assertEquals(300L, stopped.get("progress_seconds"));
			assertEquals(initial + 300, e.db().read(tx -> GameTime.currentSeconds(tx, cid)).longValue());
			assertEquals(Ref.id(f.region, "location"),
					e.db().read(tx -> tx.get("campaign", cid).lng("current_location_id")).longValue());
			Map<String, Object> repeat = e.world().move(op(), campaign, target, null, false, 10, null);
			assertEquals(stopped.get("opportunity"), repeat.get("opportunity"));
		}
		try (Engine e = engine(path)) {
			var travel = new TravelJourneyService(e.db(), e.roller());
			assertEquals(300L, travel.state(campaign).get("progress_seconds"));
			travel.resolveOpportunity(op(), campaign, "NEGOTIATED");
			Map<String, Object> arrival = e.world().move(op(), campaign, target, null, false, null, null);
			assertEquals(false, arrival.get("interrupted"));
			assertEquals("COMPLETED", arrival.get("status"));
			long cid = Ref.id(campaign, "campaign");
			assertEquals(initial + 600, e.db().read(tx -> GameTime.currentSeconds(tx, cid)).longValue());
		}
	}

	@Test
	void roadCombatUsesSavedBendsAndResumesFromTheFinalFigurinePosition() throws Exception {
		try (Engine e = engine(tempDb("road-combat-resume"))) {
			Fixture f = region(e);
			long cid = Ref.id(f.campaign, "campaign");
			e.db().mutate(Database.Mutation.of("saved-road-fixture", cid, op(), "GM", null), tx -> {
				long atlas = tx.insert("map_atlas", map("campaign_id", cid, "name", "Bandit March atlas",
						"sheet_width_ft", 2000, "sheet_height_ft", 2000));
				long scene = tx.insert("map_scene",
						map("campaign_id", cid, "location_id", Ref.id(f.region, "location"), "atlas_id", atlas, "name",
								"The old road", "kind", "REGION", "document_json",
								map("bounds_ft", List.of(0, 0, 2000, 2000))));
				long edge = tx.queryOne(
						"SELECT id FROM location_connection WHERE campaign_id=? AND location_a_id=? AND location_b_id=?",
						cid, Math.min(Ref.id(f.region, "location"), Ref.id(f.outside, "location")),
						Math.max(Ref.id(f.region, "location"), Ref.id(f.outside, "location"))).orElseThrow().id();
				tx.insert("map_feature",
						map("campaign_id", cid, "atlas_id", atlas, "map_id", scene, "feature_key", "old-road", "kind",
								"ROAD", "graph_ref", "connection:" + edge, "geometry_json",
								map("type", "PATH", "coordinates",
										List.of(List.of(0, 0), List.of(500, 100), List.of(1000, 0))),
								"properties_json", map("width_ft", 10, "from_location_ref", f.region)));
				return Map.of();
			});
			var journeys = new TravelJourneyService(e.db(), e.roller());
			journeys.defineRisk(op(), f.campaign, "DEFAULT",
					map("authored_at_fraction", .5, "frequency_seconds", 60, "chance_percent", 0));
			e.world().move(op(), f.campaign, f.outside, null, false, 10, "follow the old road");
			((se.hirt.mcp.rpg.dice.ScriptedRollService) e.roller()).queue(20, 50, 1, 50);
			Map<String, Object> started = e.encounters().start(op(), f.campaign,
					map("party", List.of(f.hero), "bandits", List.of(f.guard)), null, map(f.guard, "distant"), null,
					null, f.region);
			String encounter = (String) started.get("encounter");
			String first = String.valueOf(((Map<?, ?>) started.get("turn")).get("character"));
			if (!f.hero.equals(first))
				e.encounters().perform(op(), f.campaign, encounter, first, map("kind", "END_TURN"), false);
			long sceneId = e.db()
					.read(tx -> tx.get("map_actor",
							tx.queryOne("SELECT id FROM map_actor WHERE campaign_id=? AND character_id=?", cid,
									Ref.id(f.hero, "character")).orElseThrow().id())
							.lng("scene_id"));
			Map<String, Object> document = e.db().read(tx -> tx.get("map_scene", sceneId).map("document_json"));
			assertEquals(true, document.get("route_geometry_known"));
			assertEquals(3, ((List<?>) document.get("route_world_path_ft")).size());
			e.encounters().perform(op(), f.campaign, encounter, f.hero, map("kind", "MOVE", "x_ft", 55, "y_ft", 50),
					false);
			e.encounters().end(op(), f.campaign, encounter, "OTHER", "Negotiate passage after changing position.");
			Map<String, Object> saved = journeys.state(f.campaign);
			assertTrue(((Number) saved.get("progress_seconds")).longValue() > 300);
			Map<?, ?> position = (Map<?, ?>) saved.get("last_tactical_position");
			assertEquals(List.of(5.0, 0.0), position.get("offset_from_contact_ft"));
			assertEquals(List.of(505.0, 100.0), position.get("world_position_ft"));
			journeys.resolveOpportunity(op(), f.campaign, "NEGOTIATED");
			Map<String, Object> arrived = e.world().move(op(), f.campaign, f.outside, null, false, null,
					"continue from our final position");
			assertEquals("COMPLETED", arrived.get("status"));
			assertTrue(((Number) arrived.get("elapsed_seconds")).longValue() < 300,
					"the advanced physical position shortens the remaining walk");
		}
	}

	@Test
	void riskExposureAndItsSampleAreSharedAcrossSeparateShortJourneys() throws Exception {
		for (boolean split : List.of(false, true)) {
			try (Engine e = engine(tempDb("shared-exposure-" + split))) {
				Fixture f = region(e);
				var travel = new TravelJourneyService(e.db(), e.roller());
				travel.defineRisk(op(), f.campaign, "DEFAULT",
						map("frequency_seconds", 120, "chance_percent", 100, "placement_method", "RANDOM"));
				((se.hirt.mcp.rpg.dice.ScriptedRollService) e.roller()).queue(1, 750);
				long cid = Ref.id(f.campaign, "campaign"), before = e.db().read(tx -> GameTime.currentSeconds(tx, cid));
				Map<String, Object> stopped;
				if (split) {
					Map<String, Object> first = e.world().move(op(), f.campaign, f.outside, null, false, 1,
							"first short leg");
					assertEquals("COMPLETED", first.get("status"));
					stopped = e.world().move(op(), f.campaign, f.region, null, false, 1, "second short leg");
					assertEquals(30L, stopped.get("progress_seconds"));
				} else
					stopped = e.world().move(op(), f.campaign, f.outside, null, false, 2, "one long leg");
				assertEquals(true, stopped.get("interrupted"));
				assertEquals(before + 90, e.db().read(tx -> GameTime.currentSeconds(tx, cid)).longValue());
				assertEquals(90L, e.db().read(tx -> tx.queryOne(
						"SELECT exposure_seconds FROM travel_exposure WHERE campaign_id=? AND scope_ref='DEFAULT'", cid)
						.orElseThrow().lng("exposure_seconds")).longValue());
				assertEquals(2L,
						e.db().read(tx -> tx.count(
								"SELECT COUNT(*) FROM roll WHERE campaign_id=? AND purpose LIKE 'journey %'", cid))
								.longValue(),
						"only one risk draw and one placement draw");
			}
		}
	}

	private record Fixture(String campaign, String hero, String region, String outside, String guard) {
	}

	private static Fixture region(Engine e) {
		String c = committedCampaign(e);
		e.sessions().bootstrap(op(), c, null);
		String hero = hero(e, c);
		String region = (String) e.world()
				.materialize(op(), c, null, map("name", "Bandit March", "kind", "REGION"), "GM").get("ref");
		String outside = (String) e.world()
				.materialize(op(), c, null, map("name", "Quiet Coast", "kind", "REGION"), "GM").get("ref");
		e.world().materialize(op(), c, region,
				map("extend", true, "connections",
						List.of(map("to", outside, "kind", "ROAD", "travel_minutes", 1, "visibility", "PARTY_KNOWN"))),
				"GM");
		long cid = Ref.id(c, "campaign");
		e.db().mutate(Database.Mutation.of("region-party-fixture", cid, op(), "GM", null), tx -> {
			tx.update("character", Ref.id(hero, "character"), Map.of("location_id", Ref.id(region, "location")));
			tx.update("campaign", cid, Map.of("current_location_id", Ref.id(region, "location")));
			return Map.of();
		});
		new TravelJourneyService(e.db(), e.roller()).defineRisk(op(), c, "DEFAULT", map("chance_percent", 0));
		String guard = (String) e.runtime().materialize(op(), c, "Bandit", "Rusk", null, null, null, region, false)
				.get("character");
		return new Fixture(c, hero, region, outside, guard);
	}

	private static String definePatrol(Engine e, Fixture f, long delay) {
		return (String) new PatrolService(e.db()).define(op(), f.campaign,
				map("name", "Rusk's circuit", "region_ref", f.region, "actors", List.of(f.guard), "route",
						List.of(map("location_ref", f.region, "travel_seconds", delay),
								map("location_ref", f.region, "travel_seconds", delay))))
				.get("patrol");
	}

	private static String hero(Engine e, String c) {
		return e.db()
				.read(tx -> "character:" + tx
						.queryOne("SELECT character_id FROM player_control_assignment WHERE campaign_id=? AND active=1",
								Ref.id(c, "campaign"))
						.orElseThrow().lng("character_id"));
	}
}
