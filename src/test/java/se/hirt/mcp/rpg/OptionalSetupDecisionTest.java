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

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import se.hirt.mcp.rpg.persistence.Database;
import se.hirt.mcp.rpg.protocol.ErrorCode;
import se.hirt.mcp.rpg.protocol.Json;
import se.hirt.mcp.rpg.protocol.Ref;
import se.hirt.mcp.rpg.protocol.RpgException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static se.hirt.mcp.rpg.TestCampaigns.engine;
import static se.hirt.mcp.rpg.TestCampaigns.map;
import static se.hirt.mcp.rpg.TestCampaigns.op;
import static se.hirt.mcp.rpg.TestCampaigns.tempDb;

class OptionalSetupDecisionTest {

	private static final Map<String, Object> DEFAULT_RULES = map("ability_generation", "STANDARD_ARRAY", "progression",
			"XP", "hp_progression", "FIRST_3_MAX", "xp_policy", "SHARED", "companion_level_up", "PLAYER",
			"gm_override_policy", "EXPLICIT_AUDITED");

	@Test
	void explicitlySkippingTheOptionalFantasyStyleAdvancesTheInterview() throws Exception {
		try (Engine engine = engine(tempDb("optional-fantasy-style"))) {
			String campaign = guidedCampaign(engine);
			Map<String, Object> before = engine.campaigns().setupState(campaign);
			assertEquals("experience.fantasy_style", list(before.get("decisions")).getFirst().get("id"));
			Map<String, Object> skipped = engine.campaigns().updateSetup(op(), campaign, null,
					map("experience", map("fantasy_style", null)));
			assertEquals("EPIC", section(skipped, "experience").get("fantasy_style"));
			assertEquals("experience.relationship_focus", firstDecision(skipped));
			assertTrue(decisionIds(skipped).contains("experience.excluded_themes"));
			assertFalse(section(skipped, "experience").containsKey("relationship_focus"),
					"omitted questions stay unanswered");
			assertFalse(m(skipped.get("setup")).containsKey("rules"));
		}
	}

	@Test
	void skippingEachOptionalQuestionOnceReachesCampaignReviewAndStartsPlay() throws Exception {
		try (Engine engine = engine(tempDb("optional-setup-review"))) {
			String campaign = guidedCampaign(engine);
			Map<String, Object> state = engine.campaigns().setupState(campaign);
			for (String decision : List.of("experience.fantasy_style", "experience.relationship_focus",
					"experience.excluded_themes", "rules.ability_generation", "rules.progression",
					"rules.hp_progression", "rules.xp_policy", "rules.companion_level_up",
					"rules.gm_override_policy")) {
				assertEquals(decision, firstDecision(state), "the interview follows its advertised order");
				if ("experience.excluded_themes".equals(decision)) {
					assertEquals(List.of(), list(state.get("decisions")).getFirst().get("default"),
							"a list default has its protocol type");
				}
				state = skip(engine, campaign, decision);
				assertFalse(decisionIds(state).contains(decision),
						"an accepted default is not asked again: " + decision);
			}
			assertEquals(map("authorship", "GUIDED", "fantasy_style", "EPIC", "relationship_focus", "CENTRAL",
					"excluded_themes", List.of()), section(state, "experience"));
			assertEquals(DEFAULT_RULES, section(state, "rules"));
			assertEquals("continuation", firstDecision(state));
			engine.campaigns().updateSetup(op(), campaign, null, map("continuation", "CHECKPOINT"));
			String pc = createPlayerCharacter(engine, campaign);
			engine.characters().commitDraft(op(), campaign, pc, null);
			state = engine.campaigns().updateSetup(op(), campaign, null,
					map("party", "SURPRISE_ME", "adventure", map("premise", "Find the lost courier", "opening_location",
							"Harbor", "immediate_goal", "Meet the courier's contact")));
			assertEquals("campaign_review", firstDecision(state));
			assertEquals(Boolean.TRUE, engine.campaigns().validateSetup(campaign).get("valid"));
			engine.campaigns().commitSetup(op(), campaign, null);
			engine.sessions().bootstrap(op(), campaign, null);
			assertEquals(List.of(), engine.campaigns().setupState(campaign).get("decisions"));
			assertEquals(DEFAULT_RULES, section(engine.campaigns().setupState(campaign), "rules"));
		}
	}

	@Test
	void explicitAnswersAndCustomToneSurviveUnrelatedUpdatesAndStyleDefaults() throws Exception {
		try (Engine engine = engine(tempDb("optional-setup-explicit"))) {
			String campaign = guidedCampaign(engine);
			String tone = "A strange maritime mystery with gentle humor";
			Map<String, Object> chosenRules = map("ability_generation", "POINT_BUY", "progression", "MILESTONE",
					"hp_progression", "AVERAGE", "xp_policy", "PLAYER_ONLY", "companion_level_up", "ENGINE",
					"gm_override_policy", "DISABLED");
			engine.campaigns()
					.updateSetup(op(), campaign, null,
							map("experience", map("fantasy_style", "Gothic seafaring", "tone", tone,
									"relationship_focus", "PRESENT", "excluded_themes", List.of("spiders")), "rules",
									chosenRules));
			Map<String, Object> unchanged = engine.campaigns().updateSetup(op(), campaign, null,
					map("title", "The Drowned Bell"));
			assertEquals("Gothic seafaring", section(unchanged, "experience").get("fantasy_style"));
			assertEquals(tone, section(unchanged, "experience").get("tone"));
			assertEquals("PRESENT", section(unchanged, "experience").get("relationship_focus"));
			assertEquals(List.of("spiders"), section(unchanged, "experience").get("excluded_themes"));
			assertEquals(chosenRules, section(unchanged, "rules"));
			Map<String, Object> skipped = skip(engine, campaign, "experience.fantasy_style");
			assertEquals(tone, section(skipped, "experience").get("tone"));
			assertNull(section(skipped, "experience").get("fantasy_style"),
					"a custom tone does not acquire an EPIC style");
			assertFalse(decisionIds(skipped).contains("experience.fantasy_style"));
			assertEquals(chosenRules, section(skipped, "rules"));
			String secondCampaign = (String) engine.campaigns().create(op(), null, null).get("campaign");
			Map<String, Object> samePatch = engine.campaigns().updateSetup(op(), secondCampaign, null,
					map("content_profile", "PEGI_18", "experience",
							map("authorship", "GUIDED", "fantasy_style", null, "tone", tone)));
			assertEquals(tone, section(samePatch, "experience").get("tone"));
			assertNull(section(samePatch, "experience").get("fantasy_style"));
			assertFalse(decisionIds(samePatch).contains("experience.fantasy_style"));
		}
	}

	@Test
	void decliningPlayerAgeAdvancesWithoutRecordingAnAgeOrLooseningAnExistingCap() throws Exception {
		try (Engine engine = engine(tempDb("optional-setup-age"))) {
			String campaign = (String) engine.campaigns().create(op(), null, null).get("campaign");
			assertEquals("player_age", firstDecision(engine.campaigns().setupState(campaign)));
			Map<String, Object> declined = engine.campaigns().updateSetup(op(), campaign, null,
					map("player_age", null));
			assertEquals("content_profile", firstDecision(declined));
			assertFalse(m(declined.get("setup")).containsKey("player_age"));
			assertEquals(Map.of("max_profile", "PEGI_18"),
					m(section(declined, "content_profile").get("player_constraints")));
			Map<String, Object> child = engine.campaigns().updateSetup(op(), campaign, null, map("player_age", 12));
			Map<String, Object> childConstraints = m(section(child, "content_profile").get("player_constraints"));
			assertEquals(Map.of("max_profile", "PEGI_12"), childConstraints);
			Map<String, Object> stillRestricted = engine.campaigns().updateSetup(op(), campaign, null,
					map("player_age", null));
			assertEquals(childConstraints, m(section(stillRestricted, "content_profile").get("player_constraints")));
			assertEquals("content_profile", firstDecision(stillRestricted));
			assertFalse(m(stillRestricted.get("setup")).containsKey("player_age"));
			assertFalse(((List<?>) m(stillRestricted.get("constraints")).get("profiles")).contains("PEGI_18"));
			Map<String, Object> storedConstraints = engine.db()
					.read(tx -> tx.queryOne("SELECT player_constraints_json FROM policy_state WHERE campaign_id = ?",
							Ref.id(campaign, Ref.CAMPAIGN)).orElseThrow().map("player_constraints_json"));
			assertEquals(childConstraints, storedConstraints);
		}
	}

	@Test
	void skippedDefaultsSurviveReplayAndReopenWithoutOverwritingLaterAnswers() throws Exception {
		Path database = tempDb("optional-setup-reopen");
		String campaign;
		try (Engine engine = engine(database)) {
			campaign = guidedCampaign(engine);
			String skipOperation = op();
			Map<String, Object> changes = map("experience", map("fantasy_style", null));
			engine.campaigns().updateSetup(skipOperation, campaign, null, changes);
			skip(engine, campaign, "experience.relationship_focus");
			skip(engine, campaign, "experience.excluded_themes");
			engine.campaigns().updateSetup(op(), campaign, null,
					map("experience", map("fantasy_style", "Quiet folktales")));
			Map<String, List<Map<String, Object>>> beforeReplay = engine.db().snapshot(Ref.id(campaign, Ref.CAMPAIGN));
			Map<String, Object> replay = engine.campaigns().updateSetup(skipOperation, campaign, null, changes);
			assertEquals(Boolean.TRUE, replay.get("replayed"));
			assertEquals("EPIC", section(replay, "experience").get("fantasy_style"),
					"replay returns the original accepted default");
			assertEquals(beforeReplay, engine.db().snapshot(Ref.id(campaign, Ref.CAMPAIGN)));
			assertEquals("Quiet folktales",
					section(engine.campaigns().setupState(campaign), "experience").get("fantasy_style"));
		}
		try (Engine reopened = engine(database)) {
			Map<String, Object> resumed = reopened.campaigns().setupState(campaign);
			assertEquals("Quiet folktales", section(resumed, "experience").get("fantasy_style"));
			assertEquals("CENTRAL", section(resumed, "experience").get("relationship_focus"));
			assertEquals(List.of(), section(resumed, "experience").get("excluded_themes"));
			assertEquals("rules.ability_generation", firstDecision(resumed));
			assertFalse(decisionIds(resumed).stream().anyMatch(id -> id.startsWith("experience.")));
		}
	}

	@Test
	void omissionLeavesQuestionsPendingAndInvalidRequiredAnswersRollBackEarlierDefaults() throws Exception {
		try (Engine engine = engine(tempDb("optional-setup-invalid"))) {
			String campaign = guidedCampaign(engine);
			Map<String, Object> unchanged = engine.campaigns().updateSetup(op(), campaign, null,
					map("title", "A pending interview"));
			assertEquals("experience.fantasy_style", firstDecision(unchanged));
			assertFalse(section(unchanged, "experience").containsKey("fantasy_style"));
			assertTrue(decisionIds(unchanged).contains("rules.ability_generation"));
			Map<String, List<Map<String, Object>>> before = engine.db().snapshot(Ref.id(campaign, Ref.CAMPAIGN));
			for (Map<String, Object> invalid : List.of(
					map("experience", map("fantasy_style", null), "content_profile", null),
					map("experience", map("relationship_focus", null), "continuation", null), map("experience", null),
					map("rules", null), map("content_profile", map("profile", null)),
					map("rules", map("ability_generation", "NOT_A_METHOD")))) {
				RpgException rejected = assertThrows(RpgException.class,
						() -> engine.campaigns().updateSetup(op(), campaign, null, invalid));
				assertEquals(ErrorCode.INVALID_ARGUMENT, rejected.code(), invalid.toString());
				assertEquals(before, engine.db().snapshot(Ref.id(campaign, Ref.CAMPAIGN)), invalid.toString());
			}
			assertEquals("experience.fantasy_style", firstDecision(engine.campaigns().setupState(campaign)));
		}
	}

	@Test
	void emptyRulesAcceptMissingDefaultsAndKeepThePlayersExistingChoices() throws Exception {
		try (Engine engine = engine(tempDb("optional-setup-empty-rules"))) {
			String campaign = guidedCampaign(engine);
			Map<String, Object> oneAnswer = engine.campaigns().updateSetup(op(), campaign, null,
					map("rules", map("progression", "MILESTONE")));
			assertEquals(Map.of("progression", "MILESTONE"), section(oneAnswer, "rules"),
					"a nonempty patch answers only named questions");
			assertTrue(decisionIds(oneAnswer).contains("rules.ability_generation"));
			Map<String, Object> accepted = engine.campaigns().updateSetup(op(), campaign, null, map("rules", Map.of()));
			var expected = new java.util.LinkedHashMap<>(DEFAULT_RULES);
			expected.put("progression", "MILESTONE");
			assertEquals(expected, section(accepted, "rules"));
			assertEquals("experience.fantasy_style", firstDecision(accepted),
					"unasked experience questions remain pending");
			assertFalse(decisionIds(accepted).stream().anyMatch(id -> id.startsWith("rules.")));
			engine.campaigns().updateSetup(op(), campaign, null,
					map("rules", map("ability_generation", "POINT_BUY", "hp_progression", "AVERAGE")));
			expected.put("ability_generation", "POINT_BUY");
			expected.put("hp_progression", "AVERAGE");
			Map<String, Object> again = engine.campaigns().updateSetup(op(), campaign, null, map("rules", Map.of()));
			assertEquals(expected, section(again, "rules"));
			Map<String, Object> resetHp = skip(engine, campaign, "rules.hp_progression");
			expected.put("hp_progression", "FIRST_3_MAX");
			assertEquals(expected, section(resetHp, "rules"),
					"an explicit null accepts that field's default, keeping other answers");
		}
	}

	@Test
	void delegatingTheFantasyStyleStillBootstrapsTheBaldursGateEpic() throws Exception {
		try (Engine engine = engine(tempDb("optional-style-delegation"))) {
			String campaign = guidedCampaign(engine);
			engine.campaigns().updateSetup(op(), campaign, null, map("experience", map("fantasy_style", "SURPRISE_ME"),
					"rules", Map.of(), "continuation", "CHECKPOINT"));
			Map<String, Object> context = commitAndBootstrap(engine, campaign);
			assertEpicDefaults(context);
			assertEquals("GUIDED", m(context.get("experience_preferences")).get("authorship"));
		}
	}

	@Test
	void unansweredAndDelegatedCreativeChoicesUseAllAdvertisedDefaultsWhenTheCampaignStarts() throws Exception {
		try (Engine engine = engine(tempDb("optional-experience-defaults"))) {
			for (Object answer : List.of("SURPRISE_ME", Map.of(), Map.of("authorship", "GUIDED"),
					map("authorship", "GUIDED", "tone", "SURPRISE_ME", "relationship_focus", "SURPRISE_ME",
							"excluded_themes", "SURPRISE_ME"))) {
				String campaign = (String) engine.campaigns().create(op(), null, null).get("campaign");
				Map<String, Object> state = engine.campaigns().updateSetup(op(), campaign, null, map("content_profile",
						"PEGI_18", "experience", answer, "rules", Map.of(), "continuation", "CHECKPOINT"));
				if (answer instanceof Map<?, ?> fields && fields.isEmpty()) {
					assertEquals("EPIC", section(state, "experience").get("fantasy_style"));
					assertEquals("CENTRAL", section(state, "experience").get("relationship_focus"));
					assertEquals(List.of(), section(state, "experience").get("excluded_themes"));
					assertFalse(decisionIds(state).stream().anyMatch(id -> id.startsWith("experience.")));
				}
				Map<String, Object> context = commitAndBootstrap(engine, campaign);
				assertEpicDefaults(context);
				Map<String, Object> effective = m(context.get("experience_preferences"));
				if (answer instanceof String) {
					assertEquals("SURPRISE_ME", effective.get("authorship"), "the player's delegation is retained");
				} else {
					assertEquals(((Map<?, ?>) answer).get("authorship"), effective.get("authorship"));
				}
				Map<String, Object> persisted = engine.db()
						.read(tx -> tx.get("campaign", Ref.id(campaign, Ref.CAMPAIGN)).map("preferences_json"));
				assertEquals(effective, persisted.get("experience"),
						"the committed campaign stores the effective defaults");
			}
		}
	}

	@Test
	void acceptingRemainingDefaultsPreservesAuthoredToneRelationshipsExclusionsAndRulesAtBootstrap() throws Exception {
		try (Engine engine = engine(tempDb("optional-defaults-authored"))) {
			String campaign = guidedCampaign(engine);
			String tone = "Small coastal mysteries with gentle humor";
			Map<String, Object> chosenRules = map("ability_generation", "POINT_BUY", "progression", "MILESTONE",
					"hp_progression", "AVERAGE", "xp_policy", "PLAYER_ONLY", "companion_level_up", "ENGINE",
					"gm_override_policy", "DISABLED");
			engine.campaigns().updateSetup(op(), campaign, null,
					map("experience", map("tone", tone, "relationship_focus", "COMRADESHIP", "excluded_themes",
							List.of("spiders", "romance")), "rules", chosenRules));
			Map<String, Object> accepted = engine.campaigns().updateSetup(op(), campaign, null,
					map("experience", Map.of(), "rules", Map.of()));
			assertEquals(tone, section(accepted, "experience").get("tone"));
			assertNull(section(accepted, "experience").get("fantasy_style"));
			assertEquals(chosenRules, section(accepted, "rules"));
			Map<String, Object> context = commitAndBootstrap(engine, campaign);
			Map<String, Object> effective = m(context.get("experience_preferences"));
			assertEquals("GUIDED", effective.get("authorship"));
			assertEquals(tone, effective.get("tone"));
			assertNull(effective.get("fantasy_style"), "an authored tone does not become a different campaign style");
			assertEquals("COMRADESHIP", effective.get("relationship_focus"));
			assertEquals(List.of("spiders", "romance"), effective.get("excluded_themes"));
			assertNull(context.get("experience_guidance"));
			assertEquals(committedRules(chosenRules), context.get("rules"));
			String explicitCampaign = guidedCampaign(engine);
			engine.campaigns().updateSetup(op(), explicitCampaign, null,
					map("experience", map("fantasy_style", "Folktales on a winter coast")));
			engine.campaigns().updateSetup(op(), explicitCampaign, null, map("experience", Map.of()));
			Map<String, Object> explicit = commitAndBootstrap(engine, explicitCampaign);
			assertEquals("Folktales on a winter coast", m(explicit.get("experience_preferences")).get("fantasy_style"));
			assertNull(explicit.get("experience_guidance"));
		}
	}

	@Test
	void olderStoredDelegationGetsEffectiveDefaultsAtBootstrapWithoutRewritingPreferences() throws Exception {
		try (Engine engine = engine(tempDb("optional-defaults-legacy"))) {
			String campaign = guidedCampaign(engine);
			commitAndBootstrap(engine, campaign);
			long campaignId = Ref.id(campaign, Ref.CAMPAIGN);
			Map<String, Object> olderExperience = map("authorship", "SURPRISE_ME", "fantasy_style", "SURPRISE_ME",
					"relationship_focus", "SURPRISE_ME", "excluded_themes", "SURPRISE_ME");
			engine.db().mutate(
					Database.Mutation.of("older_experience_fixture", campaignId, op(), "ADMINISTRATIVE_OVERRIDE", null),
					tx -> {
						Map<String, Object> preferences = tx.get("campaign", campaignId).map("preferences_json");
						preferences.put("experience", olderExperience);
						tx.rawUpdate("campaign", campaignId, Map.of("preferences_json", Json.write(preferences)));
						return Map.of();
					});
			String persistedBefore = engine.db().read(tx -> tx.get("campaign", campaignId).str("preferences_json"));
			Map<String, Object> context = m(engine.sessions().bootstrap(op(), campaign, null).get("campaign"));
			assertEpicDefaults(context);
			assertEquals("SURPRISE_ME", m(context.get("experience_preferences")).get("authorship"));
			assertEquals(context.get("experience_preferences"),
					engine.narrative().directorContext(campaign, null).get("experience_preferences"));
			Map<String, Object> voice = m(engine.chronicle().material(campaign, "CHAPTER", null).get("voice"));
			assertEquals("EPIC", voice.get("fantasy_style"));
			assertTrue(String.valueOf(voice.get("style_guidance")).contains("Baldur's Gate III"));
			assertEquals(persistedBefore,
					engine.db().read(tx -> tx.get("campaign", campaignId).str("preferences_json")),
					"older preferences are projected into effective defaults without a persistence rewrite");
		}
	}

	private static Map<String, Object> commitAndBootstrap(Engine engine, String campaign) {
		engine.campaigns().updateSetup(op(), campaign, null, map("rules", Map.of(), "continuation", "CHECKPOINT"));
		String pc = createPlayerCharacter(engine, campaign);
		engine.characters().commitDraft(op(), campaign, pc, null);
		engine.campaigns().updateSetup(op(), campaign, null,
				map("party", "SURPRISE_ME", "adventure", map("premise", "Find the lost courier", "opening_location",
						"Harbor", "immediate_goal", "Meet the courier's contact")));
		engine.campaigns().commitSetup(op(), campaign, null);
		return m(engine.sessions().bootstrap(op(), campaign, null).get("campaign"));
	}

	private static void assertEpicDefaults(Map<String, Object> context) {
		Map<String, Object> effective = m(context.get("experience_preferences"));
		assertEquals("EPIC", effective.get("fantasy_style"));
		assertEquals("CENTRAL", effective.get("relationship_focus"));
		assertEquals(List.of(), effective.get("excluded_themes"));
		assertTrue(String.valueOf(context.get("experience_guidance")).contains("Baldur's Gate III"));
		assertEquals("PEGI_18", context.get("content_profile"));
		assertEquals(committedRules(DEFAULT_RULES), context.get("rules"));
	}

	private static Map<String, Object> committedRules(Map<String, Object> chosen) {
		var rules = new java.util.LinkedHashMap<>(chosen);
		rules.put("starting_wealth", "FIXED");
		return rules;
	}

	private static String guidedCampaign(Engine engine) {
		String campaign = (String) engine.campaigns().create(op(), null, null).get("campaign");
		engine.campaigns().updateSetup(op(), campaign, null,
				map("content_profile", "PEGI_18", "experience", map("authorship", "GUIDED")));
		return campaign;
	}

	private static Map<String, Object> skip(Engine engine, String campaign, String decision) {
		String[] path = decision.split("\\.", 2);
		return engine.campaigns().updateSetup(op(), campaign, null, map(path[0], map(path[1], null)));
	}

	private static List<String> decisionIds(Map<String, Object> state) {
		return list(state.get("decisions")).stream().map(d -> (String) d.get("id")).toList();
	}

	private static String firstDecision(Map<String, Object> state) {
		return decisionIds(state).getFirst();
	}

	private static Map<String, Object> section(Map<String, Object> state, String name) {
		return m(m(state.get("setup")).get(name));
	}

	private static String createPlayerCharacter(Engine engine, String campaign) {
		return (String) engine.characters()
				.createDraft(op(), campaign,
						map("name", "Lucien", "species", "Human", "class", "Sorcerer", "ability_scores",
								map("CHA", 15, "DEX", 14, "CON", 13, "INT", 12, "WIS", 10, "STR", 8), "skills",
								List.of("Arcana", "Persuasion"), "personality", "Curious and persistent", "background",
								"Criminal", "background_ability_scores", map("CON", 2, "DEX", 1), "species_skill",
								"Animal Handling", "origin_feat",
								map("feat", "Skilled", "proficiencies", List.of("Nature", "Survival", "Medicine")),
								"cantrips", List.of("Fire Bolt", "Light", "Mage Hand", "Prestidigitation"), "spells",
								List.of("Burning Hands", "Chromatic Orb")),
						true)
				.get("character");
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> m(Object value) {
		return (Map<String, Object>) value;
	}

	@SuppressWarnings("unchecked")
	private static List<Map<String, Object>> list(Object value) {
		return (List<Map<String, Object>>) value;
	}
}
