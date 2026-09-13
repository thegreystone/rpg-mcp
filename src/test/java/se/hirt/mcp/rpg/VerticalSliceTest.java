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
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static se.hirt.mcp.rpg.TestCampaigns.map;
import static se.hirt.mcp.rpg.TestCampaigns.op;

/**
 * Acceptance requirements from MCP_PROTOCOL.md §25 covered by the vertical slice: 25.1 fresh
 * campaign, 25.2 policy enforcement, 25.3 setup resumption, 25.4 context boundary, 25.5
 * deterministic mechanics (sanity invariants), 25.10 provider replacement / long hiatus.
 */
class VerticalSliceTest {

	@SuppressWarnings("unchecked")
	private static Map<String, Object> m(Object o) {
		return (Map<String, Object>) o;
	}

	@Test
	void freshServerAdvertisesCampaignCreation() throws Exception {
		try (Engine engine = TestCampaigns.engine(TestCampaigns.tempDb("fresh"))) {
			Map<String, Object> state = engine.campaigns().serverState(true, 20, null);
			assertEquals("CAMPAIGN_SELECTION", state.get("harness_state"));
			assertEquals(List.of(), state.get("campaigns"));
			assertTrue(((List<?>) state.get("allowed_operations")).contains("create_campaign"));
			assertEquals(List.of("srd5e:5.2.1"), state.get("rulesets"));
		}
	}

	@Test
	void policyIsEnforcedServerSide() throws Exception {
		try (Engine engine = TestCampaigns.engine(TestCampaigns.tempDb("policy"))) {
			String campaign = (String) engine.campaigns().create(op(), "Test", null).get("campaign");
			engine.campaigns().updateSetup(op(), campaign, null, map("player_age", 14));
			RpgException e = assertThrows(RpgException.class,
					() -> engine.campaigns().updateSetup(op(), campaign, null, map("content_profile", "PEGI_18")));
			assertEquals(ErrorCode.POLICY_DENIED, e.code());
			engine.campaigns().updateSetup(op(), campaign, null, map("content_profile", "PEGI_12"));
			Map<String, Object> setup = engine.campaigns().setupState(campaign);
			Map<String, Object> constraints = m(setup.get("constraints"));
			assertEquals(List.of("PEGI_3", "PEGI_7", "PEGI_12"), constraints.get("profiles"));
			// The age itself is never stored — only the cap.
			assertFalse(setup.get("setup").toString().contains("14"),
					"age must not be persisted: " + setup.get("setup"));

			// Gameplay tools are illegal during setup (I-8).
			RpgException notAllowed = assertThrows(RpgException.class,
					() -> engine.sessions().bootstrap(op(), campaign, null));
			assertEquals(ErrorCode.OPERATION_NOT_ALLOWED, notAllowed.code());
			assertTrue(((List<?>) notAllowed.details().get("allowed_operations")).contains("update_campaign_setup"));
		}
	}

	@Test
	void setupIsResumableAndCommitCreatesPlayableCampaign() throws Exception {
		Path db = TestCampaigns.tempDb("slice");
		String campaign;
		String pc;
		try (Engine first = TestCampaigns.engine(db)) {
			campaign = (String) first.campaigns().create(op(), null, "srd5e").get("campaign");
			assertEquals("campaign:1", campaign);
			first.campaigns().updateSetup(op(), campaign, null, map("player_age", 53, "content_profile", "PEGI_18"));
			assertEquals("SETUP_EXPERIENCE",
					m(first.campaigns().setupState(campaign).get("meta")).get("harness_state"));
		}
		// A "new AI" opens the interrupted campaign and discovers where it stands (25.3).
		try (Engine second = TestCampaigns.engine(db)) {
			Map<String, Object> opened = second.campaigns().open(campaign);
			assertEquals("RESUME_SETUP", opened.get("resume"));
			Map<String, Object> setup = second.campaigns().setupState(campaign);
			assertEquals("SETUP_EXPERIENCE", m(setup.get("meta")).get("harness_state"));
			assertTrue(((List<?>) setup.get("outstanding")).size() >= 5);

			// Commit is refused while outstanding decisions remain.
			RpgException early = assertThrows(RpgException.class,
					() -> second.campaigns().commitSetup(op(), campaign, null));
			assertEquals(ErrorCode.OPERATION_NOT_ALLOWED, early.code());

			second.campaigns().updateSetup(op(), campaign, null, map("experience", "SURPRISE_ME", "rules",
					map("ability_generation", "STANDARD_ARRAY"), "continuation", map("policy", "IRONMAN")));
			assertEquals("CHARACTER_CONCEPT",
					m(second.campaigns().setupState(campaign).get("meta")).get("harness_state"));

			Map<String, Object> created = second.characters().createDraft(op(), campaign, map("name", "Mara"), true);
			pc = (String) created.get("character");
			assertEquals("character:1", pc);
			// Standard array must be exactly the array.
			RpgException bad = assertThrows(RpgException.class, () -> second.characters().updateDraft(op(), campaign,
					pc, null,
					map("ability_scores", map("STR", 18, "DEX", 18, "CON", 18, "INT", 18, "WIS", 18, "CHA", 18))));
			assertEquals(ErrorCode.VALIDATION_FAILED, bad.code());
			second.characters().updateDraft(op(), campaign, pc, null, map("species", "Halfling", "class", "Rogue",
					"ability_scores", map("DEX", 15, "CON", 14, "INT", 13, "WIS", 12, "CHA", 10, "STR", 8),
					"background", "Acolyte", "background_ability_scores", map("INT", 2, "WIS", 1), "feat_choices",
					map("feat", "Magic Initiate", "ability", "WIS", "cantrips", List.of("Light", "Guidance"), "spell",
							"Cure Wounds"),
					"skills", List.of("Stealth", "Sleight of Hand", "Deception", "Perception"), "personality",
					"Dry, observant, allergic to authority."));
			Map<String, Object> validation = second.characters().validateDraft(campaign, pc);
			assertEquals(Boolean.TRUE, validation.get("valid"), validation.toString());
			Map<String, Object> sheet = m(validation.get("review"));
			assertEquals(10, m(sheet.get("hp")).get("max"), "d8 + CON 14 (+2) = 10");
			second.characters().commitDraft(op(), campaign, pc, null);
			assertEquals("PARTY_DESIGN", m(second.campaigns().setupState(campaign).get("meta")).get("harness_state"));

			second.campaigns().updateSetup(op(), campaign, null, map("party", "SURPRISE_ME"));
			second.campaigns().updateSetup(op(), campaign, null,
					map("adventure",
							map("premise", "A quiet river town hides a failing magical boundary.", "opening_location",
									"Bellhaven", "immediate_goal", "Find Aldren's contact at The Copper Kettle.")));
			Map<String, Object> validate = second.campaigns().validateSetup(campaign);
			assertEquals(Boolean.TRUE, validate.get("valid"), validate.toString());
			assertTrue(((List<?>) validate.get("delegated")).contains("experience.authorship"));

			Map<String, Object> committed = second.campaigns().commitSetup(op(), campaign, null);
			assertEquals("READY_TO_PLAY", committed.get("status"));
			assertEquals(pc, committed.get("player_character"));
			assertNull(committed.get("checkpoint_created"), "IRONMAN creates no checkpoint");
			assertEquals(Boolean.TRUE, committed.get("requires_context_reset"));
			assertTrue(committed.get("title").toString().startsWith("Mara's Campaign"));

			// Setup tools are now illegal (I-8) and the draft is consumed.
			RpgException late = assertThrows(RpgException.class,
					() -> second.campaigns().updateSetup(op(), campaign, null, map("title", "x")));
			assertEquals(ErrorCode.OPERATION_NOT_ALLOWED, late.code());
		}
		// Long hiatus: a third AI with no transcript bootstraps and plays (25.4, 25.10).
		try (Engine third = TestCampaigns.engine(db)) {
			Map<String, Object> state = third.campaigns().serverState(true, 20, null);
			List<Map<String, Object>> campaigns = (List<Map<String, Object>>) state.get("campaigns");
			assertEquals(1, campaigns.size());
			assertEquals("Mara", campaigns.get(0).get("player_character"));
			assertEquals("BOOTSTRAP_SESSION", third.campaigns().open(campaign).get("resume"));

			Map<String, Object> ctx = third.sessions().bootstrap(op(), campaign, 8000);
			assertEquals("EXPLORATION", ctx.get("harness_state"));
			assertEquals("Bellhaven", m(ctx.get("location")).get("name"));
			Map<String, Object> player = m(ctx.get("player_character"));
			assertEquals("Mara", player.get("name"));
			assertEquals(10, m(player.get("hp")).get("current"));
			assertEquals(150L, m(player.get("money")).get("gp"),
					"Rogue Option B 100 GP + background gold-only option 50 GP");
			assertEquals(1, ((List<?>) ctx.get("party")).size());
			assertEquals(1, ((List<?>) ctx.get("story_beats")).size());
			assertEquals("GM_ONLY", m(m(ctx.get("adventure")).get("gm_only")).get("visibility"));
			assertFalse(((List<?>) ctx.get("recent_events")).isEmpty());
			assertFalse(ctx.toString().contains("SURPRISE_ME") && ctx.toString().contains("wizard"),
					"no setup transcript leaks");

			// 25.5: a deterministic check with sanity invariants.
			Map<String, Object> check = third.checks().resolveCheck(op(), campaign, pc, "SKILL_CHECK", null, "Stealth",
					15, "ADVANTAGE", "sneaking");
			assertEquals("DEX", check.get("ability"));
			assertEquals(Boolean.TRUE, check.get("proficient"));
			assertEquals(2, check.get("proficiency_bonus"));
			assertEquals(2, check.get("ability_modifier"));
			Map<String, Object> roll = m(check.get("roll"));
			assertEquals("2d20kh1+4", roll.get("expression"));
			int natural = (Integer) check.get("natural");
			assertTrue(natural >= 1 && natural <= 20);
			assertEquals(natural + 4, check.get("total"));
			assertEquals(((Integer) check.get("total")) >= 15, check.get("success"));
			assertTrue(roll.get("roll_ref").toString().startsWith("roll:"));

			// Saving throw proficiency from the class (Rogue: DEX, INT).
			Map<String, Object> save = third.checks().resolveCheck(op(), campaign, pc, "SAVING_THROW", "INT", null, 10,
					null, null);
			assertEquals(Boolean.TRUE, save.get("proficient"));
			Map<String, Object> wisSave = third.checks().resolveCheck(op(), campaign, pc, "SAVING_THROW", "WIS", null,
					10, null, null);
			assertEquals(Boolean.FALSE, wisSave.get("proficient"));

			// Memory round trip.
			Map<String, Object> recorded = third.ledger().record(op(), campaign, "IMPORTANT_FIRST_MEETING",
					"Mara met a survivor of the lost caravan in a riverside tavern.", List.of(pc), "MAJOR", null, null,
					null, null, "The survivor insisted the wagons burned without ordinary fire.", null);
			assertTrue(recorded.get("event").toString().startsWith("event:"));
			Map<String, Object> memories = third.ledger().queryMemories(campaign, List.of(pc), null, "caravan survivor",
					null, 5);
			assertTrue(((List<?>) third.ledger().queryMemories(campaign, List.of("character:99"), null, null, null, 5)
					.get("events")).isEmpty(), "participant filters bind to the character, not the campaign");
			List<Map<String, Object>> events = (List<Map<String, Object>>) memories.get("events");
			assertEquals(1, events.size());
			assertTrue(events.get(0).get("detail").toString().contains("ordinary fire"));

			// Time and suspension.
			Map<String, Object> time = third.sessions().advanceTime(op(), campaign, 90, "walking to the tavern");
			assertEquals("Day 1, 09:30", m(time.get("to")).get("instant"));
			Map<String, Object> suspended = third.sessions().suspend(op(), campaign,
					"Mara found the survivor and heard about the burned wagons.");
			assertEquals(Boolean.TRUE, suspended.get("session_closed"));
			assertEquals(1L, suspended.get("important_events_written"),
					"CAMPAIGN_STARTED predates the session; only the meeting counts");
		}
		// And again after the hiatus: recap comes from the server.
		try (Engine fourth = TestCampaigns.engine(db)) {
			Map<String, Object> ctx = fourth.sessions().bootstrap(op(), campaign, 4000);
			// The suspend summary became a chapter; the recap is the chapter plus the ledger since it.
			Map<String, Object> chronicle = m(ctx.get("chronicle"));
			assertEquals("Mara found the survivor and heard about the burned wagons.",
					m(((List<?>) chronicle.get("chapters_since_synopsis")).get(0)).get("summary"));
			assertEquals(0, m(chronicle.get("since_last_chapter")).get("events"), "everything is covered");
			assertEquals("Day 1, 09:30", m(ctx.get("game_time")).get("instant"));
		}
	}

	@Test
	void idempotentReplayAndConflicts() throws Exception {
		try (Engine engine = TestCampaigns.engine(TestCampaigns.tempDb("idem"))) {
			String campaign = (String) engine.campaigns().create("create-1", "Idem", null).get("campaign");
			Map<String, Object> replay = engine.campaigns().create("create-1", "Idem", null);
			assertEquals(campaign, replay.get("campaign"));
			assertEquals(Boolean.TRUE, replay.get("replayed"));
			assertEquals(1, ((List<?>) engine.campaigns().serverState(true, 20, null).get("campaigns")).size());

			RpgException conflict = assertThrows(RpgException.class,
					() -> engine.campaigns().create("create-1", "Different", null));
			assertEquals(ErrorCode.IDEMPOTENCY_CONFLICT, conflict.code());

			Map<String, Object> first = engine.campaigns().updateSetup("u-1", campaign, 0L, map("player_age", 30));
			long revision = (Long) m(first.get("meta")).get("campaign_revision");
			RpgException stale = assertThrows(RpgException.class,
					() -> engine.campaigns().updateSetup("u-2", campaign, 0L, map("content_profile", "PEGI_16")));
			assertEquals(ErrorCode.CONFLICT, stale.code());
			engine.campaigns().updateSetup("u-3", campaign, revision, map("content_profile", "PEGI_16"));
		}
	}

	@Test
	void rolledScoresCannotBeSilentlyRerolled() throws Exception {
		try (Engine engine = TestCampaigns.engine(TestCampaigns.tempDb("reroll"))) {
			String campaign = TestCampaigns.committedCampaign(engine);
			assertNotNull(campaign);
			// committedCampaign rolled once; a second campaign exercises the reroll guard directly.
			String c2 = (String) engine.campaigns().create(op(), null, null).get("campaign");
			engine.campaigns().updateSetup(op(), c2, null,
					map("content_profile", "PEGI_16", "experience", "SURPRISE_ME", "rules",
							map("ability_generation", "ROLL_4D6_DROP_LOWEST"), "continuation", "CHECKPOINT"));
			String pc = (String) engine.characters().createDraft(op(), c2, null, true).get("character");
			Map<String, Object> rolled = engine.characters().generateAbilityScores(op(), c2, pc, null);
			List<?> rolls = (List<?>) rolled.get("rolls");
			assertEquals(6, rolls.size());
			for (Object r : rolls) {
				Map<String, Object> roll = m(r);
				assertEquals(4, ((List<?>) roll.get("dice")).size());
				int result = (Integer) roll.get("result");
				assertTrue(result >= 3 && result <= 18);
			}
			RpgException e = assertThrows(RpgException.class,
					() -> engine.characters().generateAbilityScores(op(), c2, pc, null));
			assertEquals(ErrorCode.CONFLICT, e.code());
		}
	}
}
