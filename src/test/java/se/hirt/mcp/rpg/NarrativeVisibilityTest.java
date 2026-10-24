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
import se.hirt.mcp.rpg.protocol.ErrorCode;
import se.hirt.mcp.rpg.protocol.RpgException;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static se.hirt.mcp.rpg.TestCampaigns.map;
import static se.hirt.mcp.rpg.TestCampaigns.op;

/** Director planning remains available to the Director without leaking into the GM's context. */
class NarrativeVisibilityTest {

	@Test
	void gmLocationContextKeepsPartyKnownEventsAndExcludesDirectorEvents() throws Exception {
		try (Engine engine = TestCampaigns.engine(TestCampaigns.tempDb("location-visibility"))) {
			String campaign = TestCampaigns.committedCampaign(engine);
			engine.sessions().bootstrap(op(), campaign, null);
			String location = (String) m(engine.sessions().party(campaign, "SUMMARY").get("location")).get("ref");
			String known = "PARTY_EVENT_SENTINEL: the watch rang the noon bell";
			String secret = "DIRECTOR_EVENT_SENTINEL: the duke plans a false rescue";
			engine.ledger().record(op(), campaign, "DISCOVERY", known, List.of(), "MAJOR", "PARTY_KNOWN", "GM", null,
					location, null, null);
			engine.ledger().record(op(), campaign, "DIRECTOR_PLAN", secret, List.of(), "MAJOR", "DIRECTOR_ONLY",
					"DIRECTOR", null, location, null, null);

			Map<String, Object> context = engine.narrative().context(campaign, "LOCATION", location, null, null, null);
			assertTrue(context.toString().contains(known), "the GM still needs the party's visible location history");
			assertFalse(context.toString().contains(secret), "the GM must not receive the Director's unrevealed plan");
			assertTrue(engine.narrative().directorContext(campaign, null).toString().contains(secret),
					"the Director keeps access to the planning event");
		}
	}

	@Test
	void locationEventFiltersApplyBeforeLimitsSoPlansCannotHideVisibleHistory() throws Exception {
		try (Engine engine = TestCampaigns.engine(TestCampaigns.tempDb("location-visibility-limit"))) {
			String campaign = TestCampaigns.committedCampaign(engine);
			engine.sessions().bootstrap(op(), campaign, null);
			String location = currentLocation(engine, campaign);
			String knownWorld = "PARTY_WORLD_SENTINEL: the old bridge has reopened";
			String knownHistory = "PARTY_HISTORY_SENTINEL: the party inspected the bridge";
			String secret = "DIRECTOR_LOCATION_SENTINEL";
			worldEvent(engine, campaign, knownWorld, "PARTY_KNOWN", List.of(location), "RUMOR", null);
			engine.ledger().record(op(), campaign, "DISCOVERY", knownHistory, List.of(), "NOTABLE", "PARTY_KNOWN", "GM",
					null, location, null, null);
			for (int i = 0; i < 21; i++) {
				worldEvent(engine, campaign, secret + " world " + i, "DIRECTOR_ONLY", List.of(location), "RUMOR", null);
				engine.ledger().record(op(), campaign, "DIRECTOR_PLAN", secret + " history " + i, List.of(), "NOTABLE",
						"DIRECTOR_ONLY", "DIRECTOR", null, location, null, null);
			}

			Map<String, Object> context = engine.narrative().context(campaign, "LOCATION", null, null, null, null);
			assertTrue(context.get("world_events_here").toString().contains(knownWorld),
					"newer planning events must not use up the visible world-event limit");
			assertTrue(context.get("events_here").toString().contains(knownHistory),
					"newer planning events must not use up the visible history limit");
			assertFalse(context.toString().contains(secret));
		}
	}

	@Test
	void questAndSceneContextsPreserveEveryNonDirectorVisibilityAndOmitPlanning() throws Exception {
		try (Engine engine = TestCampaigns.engine(TestCampaigns.tempDb("quest-visibility"))) {
			String campaign = TestCampaigns.committedCampaign(engine);
			engine.sessions().bootstrap(op(), campaign, null);
			for (String visibility : gmVisibilities()) {
				engine.narrative().upsert(op(), campaign, "QUEST", null,
						map("title", "VISIBLE_QUEST_SENTINEL " + visibility, "visibility", visibility), "GM");
				engine.narrative().upsert(op(), campaign, "STORY_BEAT", null, map("title",
						"VISIBLE_BEAT_SENTINEL " + visibility, "visibility", visibility, "state", "AVAILABLE"), "GM");
			}
			String secret = "DIRECTOR_NARRATIVE_SENTINEL";
			engine.narrative().upsert(op(), campaign, "QUEST", null,
					map("title", secret + " offered quest", "visibility", "DIRECTOR_ONLY"), "DIRECTOR");
			engine.narrative().upsert(op(), campaign, "QUEST", null,
					map("title", secret + " accepted quest", "status", "ACCEPTED", "visibility", "DIRECTOR_ONLY"),
					"DIRECTOR");
			engine.narrative().upsert(op(), campaign, "STORY_BEAT", null,
					map("title", secret + " story beat", "state", "AVAILABLE", "visibility", "DIRECTOR_ONLY"),
					"DIRECTOR");

			Map<String, Object> quests = engine.narrative().context(campaign, "QUEST", null, null, null, null);
			Map<String, Object> boot = engine.sessions().bootstrap(op(), campaign, null);
			Map<String, Object> scene = engine.narrative().context(campaign, "SCENE", null, null, null, null);
			for (Map<String, Object> context : List.of(quests, boot, scene)) {
				assertFalse(context.toString().contains(secret));
				for (String visibility : gmVisibilities()) {
					assertTrue(context.toString().contains("VISIBLE_QUEST_SENTINEL " + visibility), visibility);
					assertTrue(context.toString().contains("VISIBLE_BEAT_SENTINEL " + visibility), visibility);
				}
			}
			assertTrue(engine.narrative().context(campaign, "DIRECTOR", null, null, null, null).toString()
					.contains(secret), "an explicit Director scope still includes its own planning");
		}
	}

	@Test
	void directDirectorQuestReferencesAreNotFoundWhileDirectorMutationsRemainUsable() throws Exception {
		try (Engine engine = TestCampaigns.engine(TestCampaigns.tempDb("direct-quest-visibility"))) {
			String campaign = TestCampaigns.committedCampaign(engine);
			engine.sessions().bootstrap(op(), campaign, null);
			String known = "PARTY_QUEST_SENTINEL: take medicine to the mill";
			String secret = "DIRECTOR_QUEST_SENTINEL: arrange the duke's disappearance";
			String visibleQuest = (String) engine.narrative()
					.upsert(op(), campaign, "QUEST", null, map("title", known, "visibility", "PARTY_KNOWN"), "GM")
					.get("ref");
			String hiddenQuest = (String) engine.narrative().upsert(op(), campaign, "QUEST", null,
					map("title", secret, "visibility", "DIRECTOR_ONLY"), "DIRECTOR").get("ref");
			assertTrue(engine.narrative().context(campaign, "QUEST", visibleQuest, null, null, null).toString()
					.contains(known));
			RpgException rejected = assertThrows(RpgException.class,
					() -> engine.narrative().context(campaign, "QUEST", hiddenQuest, null, null, null));
			assertEquals(ErrorCode.NOT_FOUND, rejected.code());
			Map<String, Object> changed = engine.narrative().upsert(op(), campaign, "QUEST", hiddenQuest,
					map("status", "ACCEPTED"), "DIRECTOR");
			assertEquals("ACCEPTED", changed.get("status"));
			assertTrue(engine.narrative().directorContext(campaign, null).toString().contains(secret));
			engine.narrative().upsert(op(), campaign, "QUEST", hiddenQuest, map("visibility", "PARTY_KNOWN"),
					"DIRECTOR");
			assertTrue(engine.narrative().context(campaign, "QUEST", hiddenQuest, null, null, null).toString()
					.contains(secret), "promoting a plan to party knowledge makes it available to the GM");
			assertTrue(
					engine.sessions().bootstrap(op(), campaign, null).get("active_quests").toString().contains(secret));
		}
	}

	@Test
	void diegeticInformationExcludesDirectorPlansBeforeApplyingItsLimit() throws Exception {
		try (Engine engine = TestCampaigns.engine(TestCampaigns.tempDb("diegetic-visibility"))) {
			String campaign = TestCampaigns.committedCampaign(engine);
			engine.sessions().bootstrap(op(), campaign, null);
			String location = currentLocation(engine, campaign);
			String elsewhere = (String) engine.world()
					.materialize(op(), campaign, null, map("name", "Remote harbour", "kind", "SITE"), "GM").get("ref");
			String known = "PARTY_NEWS_SENTINEL: a bridge has reopened";
			String gm = "GM_NEWS_SENTINEL: merchants have begun rationing salt";
			String secret = "DIRECTOR_NEWS_SENTINEL";
			worldEvent(engine, campaign, known, "PARTY_KNOWN", List.of(location), "RUMOR", null);
			worldEvent(engine, campaign, gm, "GM_ONLY", List.of(), "MERCHANT", null);
			worldEvent(engine, campaign, "FUTURE_NEWS_SENTINEL", "PARTY_KNOWN", List.of(location), "RUMOR",
					"Day 30, 12:00");
			worldEvent(engine, campaign, "REMOTE_NEWS_SENTINEL", "PARTY_KNOWN", List.of(elsewhere), "RUMOR", null);
			worldEvent(engine, campaign, secret + " local plan", "DIRECTOR_ONLY", List.of(location), "RUMOR", null);
			worldEvent(engine, campaign, secret + " global plan", "DIRECTOR_ONLY", List.of(), "MERCHANT", null);

			Map<String, Object> all = engine.narrative().diegetic(campaign, "ANY", null, 50);
			assertTrue(all.toString().contains(known));
			assertTrue(all.toString().contains(gm), "GM_ONLY facts remain available for diegetic delivery");
			assertFalse(all.toString().contains(secret));
			assertFalse(all.toString().contains("FUTURE_NEWS_SENTINEL"));
			assertFalse(all.toString().contains("REMOTE_NEWS_SENTINEL"));
			assertEquals(2, list(all.get("items")).size());
			Map<String, Object> limited = engine.narrative().diegetic(campaign, "MERCHANT", location, 1);
			assertEquals(1, list(limited.get("items")).size());
			assertTrue(limited.toString().contains(gm), "a newer plan cannot consume the requested item limit");
			assertFalse(limited.toString().contains(secret));
			Map<String, Object> rumor = engine.narrative().diegetic(campaign, "RUMOR", location, 50);
			assertTrue(rumor.toString().contains(known));
			assertFalse(rumor.toString().contains(gm), "the channel filter still applies");
			assertFalse(rumor.toString().contains(secret));
			assertTrue(engine.narrative().directorContext(campaign, null).toString().contains(secret));
		}
	}

	@Test
	void characterRelationshipAndIntimacyContextsFilterPlanningBeforeLimits() throws Exception {
		try (Engine engine = TestCampaigns.engine(TestCampaigns.tempDb("people-visibility"))) {
			String campaign = TestCampaigns.committedCampaign(engine);
			engine.sessions().bootstrap(op(), campaign, null);
			String character = (String) engine.sessions().party(campaign, "SUMMARY").get("player_character");
			String companion = (String) engine.runtime()
					.materialize(op(), campaign, "Commoner", "Mara", null, null, null, null, false).get("character");
			String known = "PARTY_MILESTONE_SENTINEL: they promised to travel together";
			String detail = "PARTY_DETAIL_SENTINEL: the promise was made beside the river";
			String secret = "DIRECTOR_MILESTONE_SENTINEL";
			String knownEvent = (String) engine.ledger().record(op(), campaign, "FIRST_KISS", known,
					List.of(character, companion), "MAJOR", "PARTY_KNOWN", "GM", null, null, detail, null).get("event");
			engine.party().updateRelationship(op(), campaign, character, companion, null, "Travelling companions",
					knownEvent, null, true, "GM");
			engine.sessions().advanceTime(op(), campaign, 1, "time passes before the Director reviews the future");
			for (int i = 0; i < 26; i++) {
				String hiddenEvent = (String) engine.ledger()
						.record(op(), campaign, "FIRST_KISS", secret + " " + i, List.of(character, companion), "MAJOR",
								"DIRECTOR_ONLY", "DIRECTOR", null, null, secret + " private detail " + i, null)
						.get("event");
				if (i == 0) {
					engine.party().updateRelationship(op(), campaign, character, companion, null, null, hiddenEvent,
							null, true, "DIRECTOR");
				}
			}

			Map<String, Object> person = engine.narrative().context(campaign, "CHARACTER", character, null, null, null);
			Map<String, Object> relationship = engine.narrative().context(campaign, "RELATIONSHIP", character,
					companion, "the promise", null);
			Map<String, Object> intimacy = engine.narrative().context(campaign, "INTIMACY", character, companion, null,
					null);
			Map<String, Object> allIntimacy = engine.narrative().context(campaign, "INTIMACY", character, null, null,
					null);
			for (Map<String, Object> context : List.of(person, relationship, intimacy, allIntimacy)) {
				assertTrue(context.toString().contains(known), "newer plans must not consume visible event limits");
				assertFalse(context.toString().contains(secret));
			}
			assertTrue(relationship.toString().contains(detail), "requested relationship detail is preserved");
			assertTrue(intimacy.toString().contains(detail), "PEGI_18 intimacy keeps the visible episode's detail");
			Map<String, Object> pair = engine.party().relationship(campaign, character, companion);
			assertFalse(pair.toString().contains(secret));
			for (String direction : List.of("a_to_b", "b_to_a")) {
				assertEquals(1, list(m(pair.get(direction)).get("significant_events")).size());
			}
			for (Map<String, Object> compact : list(
					engine.sessions().bootstrap(op(), campaign, null).get("relationships"))) {
				assertEquals(1L, compact.get("significant_events"),
						"compact relationship counts exclude the Director's linked event");
			}
			Map<String, Object> director = engine.narrative().directorContext(campaign, null);
			assertTrue(director.toString().contains(secret));
			assertEquals(1, list(director.get("relationships")).size());
			assertEquals(2L, list(director.get("relationships")).getFirst().get("significant_events"),
					"the Director still counts both the known and planning events linked to the relationship");
		}
	}

	@Test
	void locationDetailKeepsGmFeaturesAndConnectionsWhileExcludingDirectorPlans() throws Exception {
		try (Engine engine = TestCampaigns.engine(TestCampaigns.tempDb("location-detail-visibility"))) {
			String campaign = TestCampaigns.committedCampaign(engine);
			engine.sessions().bootstrap(op(), campaign, null);
			String location = currentLocation(engine, campaign);
			engine.world().materialize(op(), campaign, location, map("features", List.of(
					map("name", "PARTY_FEATURE_SENTINEL", "description", "A public fountain.", "visibility",
							"PARTY_KNOWN"),
					map("name", "GM_FEATURE_SENTINEL", "description", "A hidden cellar.", "visibility", "GM_ONLY"),
					map("name", "DIRECTOR_FEATURE_SENTINEL", "description", "A future ambush.", "visibility",
							"DIRECTOR_ONLY")),
					"connections",
					List.of(map("to", map("name", "PARTY_ROUTE_SENTINEL", "kind", "SITE"), "kind", "ROAD", "visibility",
							"PARTY_KNOWN"),
							map("to", map("name", "GM_ROUTE_SENTINEL", "kind", "SITE"), "kind", "ROAD", "visibility",
									"GM_ONLY"),
							map("to", map("name", "DIRECTOR_ROUTE_SENTINEL", "kind", "SITE"), "kind", "ROAD",
									"visibility", "DIRECTOR_ONLY"))),
					"GM");
			engine.narrative().upsert(op(), campaign, "LOCATION_DETAIL", location,
					map("features", List.of(map("name", "LOWERCASE_DIRECTOR_FEATURE_SENTINEL", "description",
							"A plan stored with a lowercase visibility label.", "visibility", "director_only"))),
					"DIRECTOR");
			Map<String, Object> context = engine.narrative().context(campaign, "LOCATION", location, null, null, null);
			for (String sentinel : List.of("PARTY_FEATURE_SENTINEL", "GM_FEATURE_SENTINEL", "PARTY_ROUTE_SENTINEL",
					"GM_ROUTE_SENTINEL")) {
				assertTrue(context.toString().contains(sentinel), sentinel);
			}
			assertFalse(context.toString().contains("DIRECTOR_FEATURE_SENTINEL"));
			assertFalse(context.toString().contains("DIRECTOR_ROUTE_SENTINEL"));
			assertFalse(context.toString().contains("LOWERCASE_DIRECTOR_FEATURE_SENTINEL"));
			Map<String, Object> director = engine.narrative().context(campaign, "DIRECTOR", location, null, null, null);
			for (String sentinel : List.of("PARTY_FEATURE_SENTINEL", "GM_FEATURE_SENTINEL", "DIRECTOR_FEATURE_SENTINEL",
					"LOWERCASE_DIRECTOR_FEATURE_SENTINEL", "PARTY_ROUTE_SENTINEL", "GM_ROUTE_SENTINEL",
					"DIRECTOR_ROUTE_SENTINEL")) {
				assertTrue(director.toString().contains(sentinel),
						"an explicit Director location request retains " + sentinel);
			}
		}
	}

	@Test
	void memoryAndTimelineReadsPreserveNonDirectorLabelsAndFilterBeforeLimits() throws Exception {
		try (Engine engine = TestCampaigns.engine(TestCampaigns.tempDb("ledger-visibility"))) {
			String campaign = TestCampaigns.committedCampaign(engine);
			engine.sessions().bootstrap(op(), campaign, null);
			String secret = "DIRECTOR_LOOKUP_SENTINEL vigil";
			for (String visibility : gmVisibilities()) {
				engine.ledger().record(op(), campaign, "VIGIL", "VISIBLE_LOOKUP_SENTINEL vigil " + visibility,
						List.of(), "MAJOR", visibility, "GM", null, null, "visible vigil detail " + visibility, null);
			}
			engine.sessions().advanceTime(op(), campaign, 1, "a later Director review");
			engine.ledger().record(op(), campaign, "VIGIL", secret, List.of(), "CRITICAL", "DIRECTOR_ONLY", "DIRECTOR",
					null, null, secret + " detail", null);
			Map<String, Object> memories = engine.ledger().queryMemories(campaign, null, List.of("VIGIL"), "vigil",
					null, 50);
			Map<String, Object> chronological = engine.ledger().queryTimeline(campaign, null, null, null,
					List.of("VIGIL"), null, false, 50);
			Map<String, Object> insertion = engine.ledger().queryTimeline(campaign, null, null, null, List.of("VIGIL"),
					null, true, 50);
			for (Map<String, Object> result : List.of(memories, chronological, insertion)) {
				assertFalse(result.toString().contains(secret));
				assertEquals(gmVisibilities().size(), list(result.get("events")).size());
				for (String visibility : gmVisibilities()) {
					assertTrue(result.toString().contains("VISIBLE_LOOKUP_SENTINEL vigil " + visibility), visibility);
				}
			}
			for (Map<String, Object> result : List.of(
					engine.ledger().queryMemories(campaign, null, List.of("VIGIL"), "vigil", null, 1),
					engine.ledger().queryTimeline(campaign, null, null, null, List.of("VIGIL"), null, false, 1),
					engine.ledger().queryTimeline(campaign, null, null, null, List.of("VIGIL"), null, true, 1))) {
				assertEquals(1, list(result.get("events")).size());
				assertTrue(result.toString().contains("VISIBLE_LOOKUP_SENTINEL"));
				assertFalse(result.toString().contains(secret));
			}
		}
	}

	private static List<String> gmVisibilities() {
		return List.of("PLAYER_KNOWN", "PARTY_KNOWN", "CHARACTER_KNOWN", "FACTION_KNOWN", "GM_ONLY");
	}

	private static String currentLocation(Engine engine, String campaign) {
		return (String) m(engine.sessions().party(campaign, "SUMMARY").get("location")).get("ref");
	}

	private static void worldEvent(
		Engine engine, String campaign, String title, String visibility, List<String> locations, String channel,
		String gameTime) {
		engine.narrative()
				.upsert(op(), campaign, "WORLD_EVENT", null,
						map("title", title, "description", title + " description", "visibility", visibility,
								"locations", locations, "channels", List.of(channel), "game_time", gameTime),
						"DIRECTOR");
	}

	@SuppressWarnings("unchecked")
	private static List<Map<String, Object>> list(Object value) {
		return (List<Map<String, Object>>) value;
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> m(Object value) {
		return (Map<String, Object>) value;
	}
}
