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

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static se.hirt.mcp.rpg.TestCampaigns.map;
import static se.hirt.mcp.rpg.TestCampaigns.op;

/** Director planning remains available to the Director without leaking into the GM's context. */
class NarrativeVisibilityTest {

	@Test
	void freshContextReceivesActionableDirectorHandoffWithoutPrivatePlanning() throws Exception {
		Path database = TestCampaigns.tempDb("director-handoff-restart");
		String campaign;
		String beatRef;
		String privatePlan = "PRIVATE_HANDOFF_SENTINEL: the ferryman secretly serves the missing professor";
		String fullDetail = "FULL_HANDOFF_DETAIL_SENTINEL: use the old quay map if they investigate";
		String brief = "GM_HANDOFF_SENTINEL: a ferryman asks about the professor at the quay; "
				+ "offer a conversation or let the party follow his riverboat.";
		try (Engine engine = TestCampaigns.engine(database)) {
			campaign = TestCampaigns.committedCampaign(engine);
			engine.sessions().bootstrap(op(), campaign, null);
			Map<String, Object> review = engine.narrative().commitDirectorChanges(op(), campaign,
					List.of(map("kind", "STORY_SEED", "intention", privatePlan, "visibility", "DIRECTOR_ONLY"),
							map("kind", "STORY_BEAT", "title", "The ferryman's question", "visibility", "GM_ONLY",
									"state", "AVAILABLE", "gm_brief", brief, "investigation_detail", fullDetail)),
					"Offer an optional lead at the quay.");
			beatRef = (String) list(review.get("applied")).get(1).get("ref");
		}

		try (Engine fresh = TestCampaigns.engine(database)) {
			Map<String, Object> boot = fresh.sessions().bootstrap(op(), campaign, null);
			Map<String, Object> handoff = list(boot.get("story_beats")).stream()
					.filter(beat -> beatRef.equals(beat.get("ref"))).findFirst().orElseThrow();
			assertEquals(brief, handoff.get("gm_brief"), "a fresh GM receives a playable hook and possible approaches");
			assertFalse(boot.toString().contains(privatePlan), "private Director planning remains hidden");
			assertFalse(boot.toString().contains(fullDetail), "bootstrap keeps the handoff compact");
			assertTrue(boot.get("story_beats_hint").toString().contains("STORY_BEAT"),
					"the compact handoff explains how to retrieve its full details");
			assertTrue(fresh.narrative().directorContext(campaign, null).toString().contains(privatePlan));
		}
	}

	@Test
	void compactHandoffSupportsLegacyTextWithoutExpandingOpaquePayloads() throws Exception {
		try (Engine engine = TestCampaigns.engine(TestCampaigns.tempDb("director-handoff-legacy"))) {
			String campaign = TestCampaigns.committedCampaign(engine);
			engine.sessions().bootstrap(op(), campaign, null);
			String explicit = storyBeat(engine, campaign,
					map("title", "Explicit brief", "gm_brief", "Offer a ferry ride.", "summary", "Old summary.",
							"description", "Old description.", "options", List.of("Talk", "Investigate")));
			String summary = storyBeat(engine, campaign, map("title", "Legacy summary", "summary",
					"Ask the watch about the missing boat.", "description", "A longer legacy description."));
			String description = storyBeat(engine, campaign,
					map("title", "Legacy description", "description", "A merchant offers news at the quay."));
			String opaque = storyBeat(engine, campaign, map("title", "Opaque legacy details", "summary",
					map("nested", "OPAQUE_SUMMARY_SENTINEL"), "description", List.of("OPAQUE_DESCRIPTION_SENTINEL")));
			String noText = storyBeat(engine, campaign, map("title", "Legacy metadata only"));

			for (Map<String, Object> context : List.of(engine.sessions().bootstrap(op(), campaign, null),
					engine.narrative().context(campaign, "SCENE", null, null, null, null))) {
				assertEquals("Offer a ferry ride.", beat(context, explicit).get("gm_brief"));
				assertEquals("Ask the watch about the missing boat.", beat(context, summary).get("gm_brief"));
				assertEquals("A merchant offers news at the quay.", beat(context, description).get("gm_brief"));
				for (String ref : List.of(opaque, noText)) {
					assertEquals(Set.of("ref", "title", "state", "visibility"), beat(context, ref).keySet(),
							"legacy beats without text keep their compact shape");
				}
				assertFalse(context.toString().contains("OPAQUE_SUMMARY_SENTINEL"));
				assertFalse(context.toString().contains("OPAQUE_DESCRIPTION_SENTINEL"));
				assertFalse(beat(context, explicit).containsKey("options"));
				assertFalse(beat(context, explicit).containsKey("gm_brief_truncated"));
			}
		}
	}

	@Test
	void compactHandoffBoundsBriefsAndKeepsFullTextAvailableOnDemand() throws Exception {
		try (Engine engine = TestCampaigns.engine(TestCampaigns.tempDb("director-handoff-budget"))) {
			String campaign = TestCampaigns.committedCampaign(engine);
			engine.sessions().bootstrap(op(), campaign, null);
			String fullBrief = "x" + "\uD83C\uDF0A".repeat(600);
			engine.narrative().upsert(
					op(), campaign, "STORY_BEAT", null, map("title", "PRIVATE_BUDGET_SENTINEL", "state", "AVAILABLE",
							"visibility", "DIRECTOR_ONLY", "gm_brief", "PRIVATE_BUDGET_SENTINEL".repeat(500)),
					"DIRECTOR");
			var refs = new ArrayList<String>();
			for (int i = 0; i < 9; i++) {
				refs.add(storyBeat(engine, campaign, map("title", "Optional river lead " + i, "gm_brief", fullBrief)));
			}

			for (Map<String, Object> context : List.of(engine.sessions().bootstrap(op(), campaign, null),
					engine.narrative().context(campaign, "SCENE", null, null, null, 1000))) {
				int charsTarget = ((Number) m(context.get("budget")).get("chars_target")).intValue();
				int sharedLimit = Math.min(4000, charsTarget / 8);
				int total = 0;
				for (Map<String, Object> handoff : list(context.get("story_beats"))) {
					if (handoff.get("gm_brief") instanceof String text) {
						total += text.length();
						assertTrue(text.length() <= 600, "each brief has a bounded character allowance");
						assertTrue(text.isEmpty() || !Character.isHighSurrogate(text.charAt(text.length() - 1)),
								"clipping preserves whole Unicode characters");
					}
				}
				assertTrue(total > 0 && total <= sharedLimit, "all briefs share the context's text allowance");
				assertFalse(context.toString().contains("PRIVATE_BUDGET_SENTINEL"));
				for (String ref : refs) {
					assertEquals(true, beat(context, ref).get("gm_brief_truncated"),
							"clipped and omitted guidance both direct the GM to the full read");
				}
				assertTrue(refs.stream().map(ref -> beat(context, ref)).anyMatch(b -> !b.containsKey("gm_brief")),
						"references survive even when their text allowance is exhausted");
			}
			assertEquals(fullBrief,
					m(engine.narrative().context(campaign, "STORY_BEAT", refs.getLast(), null, null, 1)
							.get("story_beat")).get("gm_brief"),
					"requesting full beat context recovers text omitted from the compact handoff");
		}
	}

	@Test
	void directStoryBeatContextReturnsFullVisiblePayloadAndHidesPrivateReferences() throws Exception {
		try (Engine engine = TestCampaigns.engine(TestCampaigns.tempDb("director-handoff-full"))) {
			String campaign = TestCampaigns.committedCampaign(engine);
			engine.sessions().bootstrap(op(), campaign, null);
			Map<String, Object> detail = map("when", "If the party asks about the ferry", "approaches",
					List.of("Talk to the ferryman", "Inspect the boat"));
			for (String visibility : gmVisibilities()) {
				Map<String, Object> created = engine.narrative().upsert(op(), campaign, "STORY_BEAT", null,
						map("title", "Visible river lead " + visibility, "state", "AVAILABLE", "visibility", visibility,
								"gm_brief", "A ferryman offers an optional lead.", "detail", detail),
						"DIRECTOR");
				Map<String, Object> full = m(engine.narrative()
						.context(campaign, "STORY_BEAT", (String) created.get("ref"), null, null, null)
						.get("story_beat"));
				created.remove("meta");
				assertEquals(created, full, "a direct GM read keeps the full visible beat and its revision");
				assertEquals(detail, full.get("detail"));
			}
			String privateText = "PRIVATE_DIRECT_BEAT_SENTINEL: the duke intercepts the riverboat";
			String hidden = (String) engine.narrative()
					.upsert(op(), campaign, "STORY_BEAT", null, map("title", privateText, "state", "AVAILABLE",
							"visibility", "DIRECTOR_ONLY", "gm_brief", privateText, "detail", detail), "DIRECTOR")
					.get("ref");
			RpgException rejected = assertThrows(RpgException.class,
					() -> engine.narrative().context(campaign, "STORY_BEAT", hidden, null, null, null));
			assertEquals(ErrorCode.NOT_FOUND, rejected.code());
			assertFalse(engine.sessions().bootstrap(op(), campaign, null).toString().contains(privateText));
			assertFalse(engine.narrative().context(campaign, "SCENE", null, null, null, null).toString()
					.contains(privateText));
			Map<String, Object> privateBeat = beat(engine.narrative().directorContext(campaign, null), hidden);
			assertEquals(privateText, privateBeat.get("gm_brief"));
			assertEquals(detail, privateBeat.get("detail"), "Director context retains the complete private payload");
		}
	}

	@Test
	void currentHandoffReflectsRevisionsAndOmitsClosedOrSupersededBeats() throws Exception {
		try (Engine engine = TestCampaigns.engine(TestCampaigns.tempDb("director-handoff-lifecycle"))) {
			String campaign = TestCampaigns.committedCampaign(engine);
			engine.sessions().bootstrap(op(), campaign, null);
			String original = storyBeat(engine, campaign,
					map("title", "An optional ferry lead", "gm_brief", "Old hook."));
			engine.narrative().commitDirectorChanges(op(), campaign,
					List.of(map("kind", "STORY_BEAT", "ref", original, "gm_brief", "Ask at the new landing.")),
					"The ferry has moved.");
			for (Map<String, Object> context : List.of(engine.sessions().bootstrap(op(), campaign, null),
					engine.narrative().context(campaign, "SCENE", null, null, null, null))) {
				assertEquals("Ask at the new landing.", beat(context, original).get("gm_brief"));
			}
			String successor = storyBeat(engine, campaign,
					map("title", "A riverbank lead", "gm_brief", "The ferryman waits beside the stranded boat."));
			engine.narrative().commitDirectorChanges(op(), campaign, List
					.of(map("kind", "STORY_BEAT", "ref", original, "state", "SUPERSEDED", "superseded_by", successor)),
					"Offer the revised possibility.");
			String completed = storyBeat(engine, campaign,
					map("title", "Completed hook", "state", "COMPLETED", "gm_brief", "CLOSED_HOOK_SENTINEL"));
			String abandoned = storyBeat(engine, campaign,
					map("title", "Abandoned hook", "state", "ABANDONED", "gm_brief", "CLOSED_HOOK_SENTINEL"));
			String planned = storyBeat(engine, campaign,
					map("title", "A later possibility", "state", "PLANNED", "gm_brief", "Offer news if they return."));
			String blocked = storyBeat(engine, campaign,
					map("title", "An unavailable ferry", "state", "BLOCKED", "gm_brief", "The ferry is under repair."));
			for (Map<String, Object> context : List.of(engine.sessions().bootstrap(op(), campaign, null),
					engine.narrative().context(campaign, "SCENE", null, null, null, null))) {
				for (String closed : List.of(original, completed, abandoned)) {
					assertTrue(list(context.get("story_beats")).stream().noneMatch(b -> closed.equals(b.get("ref"))));
				}
				assertEquals("The ferryman waits beside the stranded boat.", beat(context, successor).get("gm_brief"));
				assertEquals("PLANNED", beat(context, planned).get("state"));
				assertEquals("BLOCKED", beat(context, blocked).get("state"));
				assertFalse(context.toString().contains("CLOSED_HOOK_SENTINEL"));
			}
			Map<String, Object> historical = m(
					engine.narrative().context(campaign, "STORY_BEAT", original, null, null, null).get("story_beat"));
			assertEquals("SUPERSEDED", historical.get("state"));
			assertEquals(successor, historical.get("superseded_by"));
			assertEquals("Ask at the new landing.", historical.get("gm_brief"));
		}
	}

	@Test
	void suppliedGmBriefMustBeTextAndCanBeClearedWithoutBreakingLegacyBeats() throws Exception {
		try (Engine engine = TestCampaigns.engine(TestCampaigns.tempDb("director-handoff-validation"))) {
			String campaign = TestCampaigns.committedCampaign(engine);
			engine.sessions().bootstrap(op(), campaign, null);
			String ref = storyBeat(engine, campaign,
					map("title", "A ferry lead", "gm_brief", "Ask the ferryman.", "summary", "Ask the watch instead."));
			for (Object invalid : List.of(42, List.of("A hook"), map("hook", "A hook"))) {
				RpgException rejected = assertThrows(RpgException.class,
						() -> engine.narrative().commitDirectorChanges(op(), campaign,
								List.of(map("kind", "STORY_BEAT", "ref", ref, "gm_brief", invalid)), null));
				assertEquals(ErrorCode.INVALID_ARGUMENT, rejected.code());
				assertEquals("Ask the ferryman.",
						m(engine.narrative().context(campaign, "STORY_BEAT", ref, null, null, null).get("story_beat"))
								.get("gm_brief"),
						"invalid edits leave the published handoff intact");
			}
			engine.narrative().commitDirectorChanges(op(), campaign,
					List.of(map("kind", "STORY_BEAT", "ref", ref, "gm_brief", null)), "Clear the bespoke brief.");
			assertEquals("Ask the watch instead.",
					beat(engine.sessions().bootstrap(op(), campaign, null), ref).get("gm_brief"),
					"clearing explicit text allows the existing legacy summary to supply the compact brief");
		}
	}

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

	private static String storyBeat(Engine engine, String campaign, Map<String, Object> changes) {
		Map<String, Object> fields = map("state", "AVAILABLE", "visibility", "GM_ONLY");
		fields.putAll(changes);
		return (String) engine.narrative().upsert(op(), campaign, "STORY_BEAT", null, fields, "DIRECTOR").get("ref");
	}

	private static Map<String, Object> beat(Map<String, Object> context, String ref) {
		return list(context.get("story_beats")).stream().filter(b -> ref.equals(b.get("ref"))).findFirst()
				.orElseThrow();
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
