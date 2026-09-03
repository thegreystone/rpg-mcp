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

import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import org.junit.jupiter.api.Test;
import se.hirt.mcp.rpg.choice.*;
import se.hirt.mcp.rpg.content.RulesData;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static se.hirt.mcp.rpg.TestCampaigns.map;
import static se.hirt.mcp.rpg.TestCampaigns.op;

/**
 * Structured decisions (MCP_PROTOCOL.md §9.3.1): every setting the player decides on is returned by the server as an
 * ordered list of decisions, each carrying every legal option with a description, derived from one source — never
 * authored twice in tool descriptions.
 */
class DecisionsTest {

	@SuppressWarnings("unchecked")
	private static Map<String, Object> m(Object o) {
		return (Map<String, Object>) o;
	}

	@SuppressWarnings("unchecked")
	private static List<Map<String, Object>> decisions(Map<String, Object> response) {
		return (List<Map<String, Object>>) response.get("decisions");
	}

	@SuppressWarnings("unchecked")
	private static List<Map<String, Object>> options(Map<String, Object> decision) {
		return (List<Map<String, Object>>) decision.get("options");
	}

	private static List<String> ids(Map<String, Object> response) {
		return decisions(response).stream().map(d -> (String) d.get("id")).toList();
	}

	private static void assertDescribed(List<Map<String, Object>> options) {
		assertFalse(options.isEmpty());
		for (Map<String, Object> o : options) {
			assertNotNull(o.get("value"), o.toString());
			assertFalse(String.valueOf(o.get("label")).isBlank(), o.toString());
			assertFalse(String.valueOf(o.get("description")).isBlank(), "option without description: " + o);
		}
	}

	@Test
	void everyLegalValueIsDescribedExactlyOnce() {
		List<Class<? extends Enum<?>>> enums = List.of(ContentProfile.class, AbilityGeneration.class, Progression.class,
				GmOverridePolicy.class, ContinuationPolicy.class, Authorship.class, Alignment.class,
				se.hirt.mcp.rpg.choice.HpProgression.class, se.hirt.mcp.rpg.choice.XpPolicy.class,
				se.hirt.mcp.rpg.choice.CompanionLevelUp.class);
		for (Class<? extends Enum<?>> type : enums) {
			int recommended = 0;
			for (Enum<?> constant : type.getEnumConstants()) {
				Described d = (Described) constant;
				assertFalse(d.label().isBlank(), type.getSimpleName() + "." + constant + " has no label");
				assertTrue(d.description().length() > 20,
						type.getSimpleName() + "." + constant + " has no real description");
				if (d.recommended()) {
					recommended++;
				}
			}
			assertTrue(recommended <= 1, type.getSimpleName() + " recommends more than one value");
		}
		assertEquals(ContentProfile.PEGI_3, ContentProfile.capForAge(6));
		assertEquals(ContentProfile.PEGI_12, ContentProfile.capForAge(14));
		assertEquals(ContentProfile.PEGI_18, ContentProfile.capForAge(53));
	}

	@Test
	void seedContentCarriesSummaries() throws Exception {
		try (Engine engine = TestCampaigns.engine(TestCampaigns.tempDb("summaries"))) {
			for (String kind : List.of("SPECIES", "CLASS", "SKILL")) {
				List<RulesData.Definition> defs = engine.rules().ofKind(kind);
				assertFalse(defs.isEmpty(), kind);
				for (RulesData.Definition d : defs) {
					assertTrue(d.payload().get("summary") instanceof String s && s.length() > 20,
							d.id() + " has no summary");
					assertTrue(d.summary().containsKey("summary"), d.id());
				}
			}
			Map<String, Object> spells = engine.content()
					.definitions(null, "SPELL", null, "sorcerer", null, null, null, 100, "SUMMARY");
			@SuppressWarnings("unchecked") List<Map<String, Object>> items = (List<Map<String, Object>>) spells.get(
					"items");
			assertFalse(items.isEmpty());
			for (Map<String, Object> item : items) {
				assertTrue(item.get("level") instanceof Integer, item.toString());
				assertNotNull(item.get("school"), item.toString());
				assertTrue(((List<?>) item.get("classes")).contains("sorcerer"), item.toString());
				assertFalse(String.valueOf(item.get("summary")).isBlank(), item.toString());
			}
		}
	}

	@Test
	void setupInterviewIsOrderedOneDecisionAtATime() throws Exception {
		try (Engine engine = TestCampaigns.engine(TestCampaigns.tempDb("decisions"))) {
			Map<String, Object> created = engine.campaigns().create(op(), null, null);
			String campaign = (String) created.get("campaign");
			assertEquals("player_age", ids(created).get(0));
			Map<String, Object> profile = decisions(created).get(1);
			assertEquals("content_profile", profile.get("id"));
			assertEquals("update_campaign_setup", profile.get("tool"));
			assertEquals("changes.content_profile", profile.get("path"));
			assertEquals("LEGAL_VALUES", profile.get("options_are"));
			assertEquals(5, options(profile).size());
			assertDescribed(options(profile));
			assertEquals("PEGI_18", profile.get("recommended"));
			assertTrue(String.valueOf(profile.get("note")).contains("Baldur's Gate"), String.valueOf(profile.get("note")));

			// A young player caps the profile options; the age question disappears.
			Map<String, Object> aged = engine.campaigns().updateSetup(op(), campaign, null, map("player_age", 14));
			assertEquals("content_profile", ids(aged).get(0));
			assertEquals(List.of("PEGI_3", "PEGI_7", "PEGI_12"),
					options(decisions(aged).get(0)).stream().map(o -> o.get("value")).toList());

			Map<String, Object> exp = engine.campaigns()
					.updateSetup(op(), campaign, null, map("content_profile", "PEGI_12"));
			Map<String, Object> authorship = decisions(exp).get(0);
			assertEquals("experience.authorship", authorship.get("id"));
			assertEquals("SUGGESTIONS", authorship.get("options_are"));
			assertEquals(Boolean.TRUE, authorship.get("allow_custom"));
			assertEquals(Boolean.TRUE, authorship.get("allow_surprise_me"));

			// Guided authorship opens the creative follow-ups one by one; each answer merges into the section.
			Map<String, Object> tone = engine.campaigns()
					.updateSetup(op(), campaign, null, map("experience", map("authorship", "GUIDED")));
			Map<String, Object> style = decisions(tone).get(0);
			assertEquals("experience.fantasy_style", style.get("id"));
			assertEquals("changes.experience.fantasy_style", style.get("path"));
			assertEquals("SUGGESTIONS", style.get("options_are"));
			assertEquals("EPIC", style.get("recommended"));
			assertEquals("EPIC", style.get("default"));
			assertTrue(String.valueOf(style.get("question")).contains("Baldur's Gate"), String.valueOf(style.get("question")));
			assertEquals(5, options(style).size());
			assertDescribed(options(style));
			Map<String, Object> rel = engine.campaigns().updateSetup(op(), campaign, null,
					map("experience", map("tone", "Classic heroic with a mystery underneath")));
			assertEquals("experience.relationship_focus", ids(rel).get(0));
			Map<String, Object> excl = engine.campaigns()
					.updateSetup(op(), campaign, null, map("experience", map("relationship_focus", "CENTRAL")));
			assertEquals("experience.excluded_themes", ids(excl).get(0));
			Map<String, Object> gen = engine.campaigns()
					.updateSetup(op(), campaign, null, map("experience", map("excluded_themes", List.of())));
			Map<String, Object> ability = decisions(gen).get(0);
			assertEquals("rules.ability_generation", ability.get("id"));
			assertEquals(3, options(ability).size());
			assertDescribed(options(ability));
			assertEquals(Boolean.TRUE, ability.get("optional"));
			assertEquals("STANDARD_ARRAY", ability.get("default"));

			Map<String, Object> prog = engine.campaigns()
					.updateSetup(op(), campaign, null, map("rules", map("ability_generation", "POINT_BUY")));
			assertEquals("rules.progression", ids(prog).get(0));
			Map<String, Object> hpq = engine.campaigns()
					.updateSetup(op(), campaign, null, map("rules", map("progression", "XP")));
			assertEquals("rules.hp_progression", ids(hpq).get(0));
			assertEquals(3, options(decisions(hpq).get(0)).size());
			assertDescribed(options(decisions(hpq).get(0)));
			assertEquals("FIRST_3_MAX", decisions(hpq).get(0).get("recommended"));
			Map<String, Object> xpq = engine.campaigns()
					.updateSetup(op(), campaign, null, map("rules", map("hp_progression", "FIRST_3_MAX")));
			assertEquals("rules.xp_policy", ids(xpq).get(0));
			assertEquals(3, options(decisions(xpq).get(0)).size());
			assertDescribed(options(decisions(xpq).get(0)));
			assertEquals("SHARED", decisions(xpq).get(0).get("recommended"));
			Map<String, Object> compq = engine.campaigns()
					.updateSetup(op(), campaign, null, map("rules", map("xp_policy", "LOCKSTEP")));
			assertEquals("rules.companion_level_up", ids(compq).get(0));
			assertEquals(2, options(decisions(compq).get(0)).size());
			Map<String, Object> ovr = engine.campaigns()
					.updateSetup(op(), campaign, null, map("rules", map("companion_level_up", "ENGINE")));
			assertEquals("rules.gm_override_policy", ids(ovr).get(0));
			Map<String, Object> cont = engine.campaigns()
					.updateSetup(op(), campaign, null, map("rules", map("gm_override_policy", "EXPLICIT_AUDITED")));
			assertEquals("continuation", ids(cont).get(0));
			assertEquals(3, options(decisions(cont).get(0)).size());
			Map<String, Object> pcq = engine.campaigns()
					.updateSetup(op(), campaign, null, map("continuation", "CHECKPOINT"));
			assertEquals("player_character", ids(pcq).get(0));
			assertEquals("create_character_draft", decisions(pcq).get(0).get("tool"));

			// The draft's own decisions take over, in creation order: the SRD background comes first.
			Map<String, Object> draft = engine.characters().createDraft(op(), campaign,
					map("name", "Richard Greystone", "species", "Human", "class", "Sorcerer"), true);
			String pc = (String) draft.get("character");
			List<String> draftIds = ids(draft);
			assertEquals("background", draftIds.get(0), draftIds.toString());
			Map<String, Object> background = decisions(draft).get(0);
			assertEquals(4, options(background).size(), "the four SRD 5.2.1 backgrounds");
			assertDescribed(options(background));
			assertEquals("ability_scores", draftIds.get(1), draftIds.toString());
			Map<String, Object> scores = decisions(draft).get(1);
			assertEquals("POINT_BUY", scores.get("method"));
			assertEquals(27, m(scores.get("point_buy")).get("budget"));
			assertEquals(List.of("CHA"), scores.get("primary_abilities"));
			assertEquals(pc, scores.get("character"));
			// The campaign-level list shows the same decisions first, then party and adventure.
			List<String> campaignIds = ids(engine.campaigns().setupState(campaign));
			assertEquals(draftIds, campaignIds.subList(0, draftIds.size()));
			assertEquals(List.of("party", "adventure"), campaignIds.subList(draftIds.size(), campaignIds.size()));

			Map<String, Object> scored = engine.characters().updateDraft(op(), campaign, pc, null,
					map("ability_scores", map("STR", 8, "DEX", 13, "CON", 14, "INT", 12, "WIS", 10, "CHA", 15)));
			List<Map<String, Object>> after = decisions(scored);
			assertEquals(List.of("background", "skills", "species_skill", "origin_feat", "cantrips", "spells",
					"starting_equipment", "personality", "age", "alignment"), ids(scored));
			Map<String, Object> skills = after.get(1);
			assertEquals(6, options(skills).size());
			assertEquals(Map.of("min", 2, "max", 2), skills.get("choose"));
			assertDescribed(options(skills));
			assertEquals("INT", options(skills).get(0).get("ability"));
			Map<String, Object> speciesSkill = after.get(2);
			assertEquals(18, options(speciesSkill).size(), "Skillful: any skill");
			Map<String, Object> originFeat = after.get(3);
			assertEquals(4, options(originFeat).size(), "the four SRD Origin feats");
			assertDescribed(options(originFeat));
			assertEquals("srd5e:feat/skilled", originFeat.get("recommended"));
			Map<String, Object> cantrips = after.get(4);
			assertEquals(Map.of("min", 4, "max", 4), cantrips.get("choose"));
			assertTrue(options(cantrips).size() >= 10, "sorcerer cantrips: " + options(cantrips).size());
			assertDescribed(options(cantrips));
			assertTrue(options(cantrips).stream().allMatch(o -> Integer.valueOf(0).equals(o.get("level"))));
			Map<String, Object> spells = after.get(5);
			assertEquals(Map.of("min", 2, "max", 2), spells.get("choose"));
			assertTrue(options(spells).stream().allMatch(o -> Integer.valueOf(1).equals(o.get("level"))));
			assertTrue(options(spells).stream().anyMatch(o -> "srd5e:spell/shield".equals(o.get("value"))));
			Map<String, Object> equipment = after.get(6);
			assertEquals(List.of("A", "B"), options(equipment).stream().map(o -> o.get("value")).toList());
			assertTrue(String.valueOf(options(equipment).get(0).get("description")).contains("Spear"),
					options(equipment).toString());
			assertTrue(String.valueOf(options(equipment).get(1).get("description")).contains("50 gp"),
					options(equipment).toString());
			assertEquals("B", equipment.get("default"));
			Map<String, Object> alignment = after.get(9);
			assertEquals(9, options(alignment).size());
			assertDescribed(options(alignment));
			assertEquals(Boolean.TRUE, alignment.get("optional"));

			// Choosing the Criminal background grants its two skills, tool and the Alert feat; the background
			// ability increase, the species bonus skill and the origin feat are answered like any decision.
			Map<String, Object> done = engine.characters().updateDraft(op(), campaign, pc, null,
					map("background", "Criminal", "background_ability_scores", map("DEX", 2, "CON", 1), "skills",
							List.of("Arcana", "Persuasion"), "species_skill", "Insight", "origin_feat",
							"Savage Attacker", "cantrips",
							List.of("Fire Bolt", "Mage Hand", "Minor Illusion", "Prestidigitation"), "spells",
							List.of("Magic Missile", "Shield"), "starting_equipment", "A", "background_equipment", "A",
							"personality", "Proud, quick and hungry to prove himself.", "age", 27, "alignment",
							"Chaotic Good"));
			assertEquals(List.of("character_review"), ids(done));
			// The background increase is applied on top of the base scores (DEX 13+2, CON 14+1).
			Map<String, Object> sheet = engine.characters().characterSheet(campaign, pc, "FULL");
			assertEquals(15, m(m(sheet.get("abilities")).get("DEX")).get("score"));
			assertEquals(15, m(m(sheet.get("abilities")).get("CON")).get("score"));
			assertEquals("Criminal", m(sheet.get("background")).get("name"));
			assertEquals(27, sheet.get("age"), "age is asked for during creation and tracked on the sheet");
			assertEquals(List.of("COMMIT", "REVISE"),
					options(decisions(done).get(0)).stream().map(o -> o.get("value")).toList());

			// get_character_choices answers with descriptions everywhere, and the draft's decisions.
			Map<String, Object> choices = engine.characters().choices(campaign, "ALL", pc);
			assertDescribed(m(choices.get("skills")).get("all") instanceof List<?> l ? castList(l) : List.of());
			assertDescribed(castList((List<?>) choices.get("species")));
			assertDescribed(castList((List<?>) choices.get("classes")));
			assertDescribed(castList((List<?>) choices.get("alignments")));
			assertTrue(m(choices.get("spells")).containsKey("cantrips"));
			assertTrue(m(choices.get("equipment")).containsKey("options"));
			assertEquals(List.of("character_review"), ids(choices));

			engine.characters().commitDraft(op(), campaign, pc, null);
			assertEquals("party", ids(engine.campaigns().setupState(campaign)).get(0));
			Map<String, Object> adv = engine.campaigns().updateSetup(op(), campaign, null, map("party", "SURPRISE_ME"));
			assertEquals("adventure", ids(adv).get(0));
			assertEquals("GM", decisions(adv).get(0).get("owner"));
			Map<String, Object> review = engine.campaigns().updateSetup(op(), campaign, null, map("adventure",
					map("premise", "A quiet river town hides a failing magical boundary.", "opening_location",
							"Bellhaven", "immediate_goal", "Find Aldren's contact at The Copper Kettle.")));
			assertEquals(List.of("campaign_review"), ids(review));
			assertEquals("commit_campaign_setup", decisions(review).get(0).get("tool"));

			engine.campaigns().commitSetup(op(), campaign, null);
			assertEquals(List.of(), engine.campaigns().setupState(campaign).get("decisions"));
		}
	}

	/** A delegated experience commits as the fantasy epic, and the running guidance reaches the GM every session. */
	@Test
	void delegatedExperienceDefaultsToTheFantasyEpic() throws Exception {
		try (Engine engine = TestCampaigns.engine(TestCampaigns.tempDb("epic"))) {
			String campaign = (String) engine.campaigns().create(op(), null, null).get("campaign");
			engine.campaigns().updateSetup(op(), campaign, null,
					map("content_profile", "PEGI_18", "experience", "SURPRISE_ME", "rules", Map.of(), "continuation",
							"CHECKPOINT"));
			String pc = (String) engine.characters().createDraft(op(), campaign,
					map("name", "Tav", "species", "Dwarf", "class", "Fighter", "ability_scores",
							map("STR", 15, "CON", 14, "DEX", 13, "WIS", 12, "INT", 10, "CHA", 8), "background",
							"Criminal", "background_ability_scores", map("DEX", 2, "CON", 1), "skills",
							List.of("Athletics", "Perception"), "personality", "Wry."), true).get("character");
			engine.characters().commitDraft(op(), campaign, pc, null);
			engine.campaigns().updateSetup(op(), campaign, null, map("party", "SURPRISE_ME", "adventure",
					map("premise", "x", "opening_location", "Beach", "immediate_goal", "get the tadpole out")));
			Map<String, Object> review = m(engine.campaigns().validateSetup(campaign).get("review"));
			assertEquals("EPIC", m(review.get("experience")).get("fantasy_style"));
			engine.campaigns().commitSetup(op(), campaign, null);

			Map<String, Object> ctx = m(engine.sessions().bootstrap(op(), campaign, null).get("campaign"));
			assertEquals("EPIC", m(ctx.get("experience_preferences")).get("fantasy_style"));
			assertEquals("SURPRISE_ME", m(ctx.get("experience_preferences")).get("authorship"));
			assertEquals(FantasyStyle.EPIC.guidance(), ctx.get("experience_guidance"));
			assertTrue(String.valueOf(ctx.get("experience_guidance")).contains("Baldur's Gate III"));
			assertEquals("PEGI_18", ctx.get("content_profile"));
			assertEquals(ContentProfile.PEGI_18.guidance(), ctx.get("content_profile_guidance"));

			// A player who described the flavour in their own words is never overridden.
			String own = (String) engine.campaigns().create(op(), null, null).get("campaign");
			engine.campaigns().updateSetup(op(), own, null,
					map("content_profile", "PEGI_12", "experience", map("tone", "Cosy village mysteries")));
			assertNull(m(engine.campaigns().validateSetup(own).get("review")).get("experience") == null ? null
					: m(m(engine.campaigns().validateSetup(own).get("review")).get("experience")).get("fantasy_style"));
		}
	}

	@SuppressWarnings("unchecked")
	private static List<Map<String, Object>> castList(List<?> l) {
		return (List<Map<String, Object>>) l;
	}

	/** Tool descriptions point at the decisions; they never re-enumerate legal values. */
	@Test
	void toolDescriptionsDoNotDoubleBookLegalValues() {
		var texts = new ArrayList<String>();
		for (Method method : RpgTools.class.getDeclaredMethods()) {
			Tool tool = method.getAnnotation(Tool.class);
			if (tool == null) {
				continue;
			}
			texts.add(tool.name() + ": " + tool.description());
			for (Parameter p : method.getParameters()) {
				ToolArg arg = p.getAnnotation(ToolArg.class);
				if (arg != null) {
					texts.add(tool.name() + "." + p.getName() + ": " + arg.description());
				}
			}
		}
		assertTrue(texts.size() > 50,
				"expected the @Tool annotations to be readable at runtime, found " + texts.size());
		for (String text : texts) {
			for (Class<? extends Enum<?>> type : List.<Class<? extends Enum<?>>> of(ContentProfile.class,
					AbilityGeneration.class, ContinuationPolicy.class, GmOverridePolicy.class, Alignment.class)) {
				long mentioned = java.util.Arrays.stream(type.getEnumConstants()).filter(c -> text.contains(c.name()))
						.count();
				assertTrue(mentioned < type.getEnumConstants().length,
						"tool text enumerates " + type.getSimpleName() + " instead of pointing at decisions: " + text);
			}
		}
	}
}
