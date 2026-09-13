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

import static org.junit.jupiter.api.Assertions.*;
import static se.hirt.mcp.rpg.TestCampaigns.map;
import static se.hirt.mcp.rpg.TestCampaigns.op;

/**
 * Gaps found in play on 2026-09-04 and closed the same day: checks during an encounter, tool
 * proficiency in checks, feat choices for several feats at once (and completed later),
 * campaign-defined backgrounds, and a fixed Armor Class by audited override.
 */
class SessionGapsTest {

	@SuppressWarnings("unchecked")
	private static Map<String, Object> m(Object o) {
		return (Map<String, Object>) o;
	}

	@SuppressWarnings("unchecked")
	private static List<Map<String, Object>> list(Object o) {
		return (List<Map<String, Object>>) o;
	}

	/** A campaign in setup with every section but the player character recorded. */
	private static String setup(Engine engine) {
		String campaign = (String) engine.campaigns().create(op(), null, null).get("campaign");
		engine.campaigns().updateSetup(op(), campaign, null,
				map("content_profile", "PEGI_16", "experience", "SURPRISE_ME", "rules", Map.of(), "continuation",
						"CHECKPOINT", "party", "SURPRISE_ME", "adventure",
						map("premise", "x", "opening_location", "Camp", "immediate_goal", "y")));
		return campaign;
	}

	/** A Criminal wizard (Thieves' Tools, Sleight of Hand, Stealth) committed and in play. */
	private static String playableCriminal(Engine engine, String campaign) {
		String pc = (String) engine.characters()
				.createDraft(op(), campaign, map("name", "Ash", "species", "Human", "class", "Wizard", "ability_scores",
						map("INT", 15, "DEX", 14, "CON", 13, "WIS", 12, "CHA", 10, "STR", 8), "skills",
						List.of("Arcana", "History"), "personality", "x", "background", "Criminal",
						"background_ability_scores", map("CON", 2, "INT", 1), "species_skill", "Insight", "origin_feat",
						map("feat", "Skilled", "proficiencies", List.of("Nature", "Survival", "Medicine"))), true)
				.get("character");
		engine.characters().commitDraft(op(), campaign, pc, null);
		engine.campaigns().commitSetup(op(), campaign, null);
		engine.sessions().bootstrap(op(), campaign, null);
		return pc;
	}

	@Test
	void checksAreLegalDuringAnEncounter() throws Exception {
		try (Engine engine = TestCampaigns.engine(TestCampaigns.tempDb("gaps-encounter-check"))) {
			String campaign = setup(engine);
			String pc = playableCriminal(engine, campaign);
			String bandit = (String) engine.runtime()
					.materialize(op(), campaign, "Bandit", null, null, null, null, null, false).get("character");
			engine.encounters().start(op(), campaign, map("party", List.of(pc), "camp", List.of(bandit)), null, null,
					"A hollow at dusk", List.of("Take the camp"), null);
			Map<String, Object> parley = engine.checks().resolveCheck(op(), campaign, pc, "SKILL_CHECK", null,
					"Persuasion", 10, null, "offering terms");
			assertNotNull(m(parley.get("roll")).get("roll_ref"));
			assertEquals("ENCOUNTER", m(parley.get("meta")).get("harness_state"), "the fight is still on");
			assertTrue(((List<?>) m(parley.get("meta")).get("allowed_operations")).contains("resolve_check"));
		}
	}

	@Test
	void toolProficiencyAppliesToChecks() throws Exception {
		try (Engine engine = TestCampaigns.engine(TestCampaigns.tempDb("gaps-tool-check"))) {
			String campaign = setup(engine);
			String pc = playableCriminal(engine, campaign);
			assertTrue(((List<?>) engine.characters().characterSheet(campaign, pc, "PLAY").get("tool_proficiencies"))
					.contains("Thieves' Tools"), "the Criminal background grants Thieves' Tools");

			// An ability check made with a tool the character is proficient with adds the proficiency bonus.
			Map<String, Object> pick = engine.checks().resolveCheck(op(), campaign, pc, "ABILITY_CHECK", "DEX", null,
					"Thieves' Tools", 12, null, "a lock");
			assertEquals("Thieves' Tools", pick.get("tool"));
			assertEquals(Boolean.TRUE, pick.get("tool_proficient"));
			assertEquals(Boolean.TRUE, pick.get("proficient"));
			assertEquals(2, pick.get("proficiency_bonus"));
			assertEquals(2 + 2, pick.get("modifier"), "DEX +2 and proficiency +2");

			// Proficiency in both the skill and the tool gives advantage (SRD 5.2.1 "Tools and Skills Together").
			Map<String, Object> both = engine.checks().resolveCheck(op(), campaign, pc, "SKILL_CHECK", null,
					"Sleight of Hand", "Thieves' Tools", 12, null, "palming the key");
			assertEquals("ADVANTAGE", both.get("advantage"));
			assertTrue(String.valueOf(both.get("advantage_source")).contains("Thieves' Tools"));
			assertEquals("2d20kh1+4", m(both.get("roll")).get("expression"));

			// ...and cancels a disadvantage the GM imposed.
			Map<String, Object> cancelled = engine.checks().resolveCheck(op(), campaign, pc, "SKILL_CHECK", null,
					"Stealth", "Thieves' Tools", 12, "DISADVANTAGE", "in the dark");
			assertEquals("NONE", cancelled.get("advantage"));

			// A tool the character is not proficient with adds nothing; an unknown tool is refused.
			Map<String, Object> smith = engine.checks().resolveCheck(op(), campaign, pc, "ABILITY_CHECK", "STR", null,
					"Smith's Tools", 12, null, "a hinge");
			assertEquals(Boolean.FALSE, smith.get("tool_proficient"));
			assertEquals(0, smith.get("proficiency_bonus"));
			RpgException unknown = assertThrows(RpgException.class, () -> engine.checks().resolveCheck(op(), campaign,
					pc, "ABILITY_CHECK", "DEX", null, "Lockpick of Doom", 12, null, null));
			assertEquals(ErrorCode.INVALID_ARGUMENT, unknown.code());
		}
	}

	@Test
	void featChoicesAcceptAListAndPendingChoicesCompleteLater() throws Exception {
		try (Engine engine = TestCampaigns.engine(TestCampaigns.tempDb("gaps-feat-list"))) {
			String campaign = setup(engine);
			playableCriminal(engine, campaign);
			// A Human Sage owes both Skilled (species) and Magic Initiate (background) their choices at once.
			String maude = (String) engine.runtime()
					.materialize(op(), campaign, "Scout", "Maude", null, null, "NEUTRAL_GOOD", null, false)
					.get("character");
			engine.levelUps().begin(op(), campaign, maude);
			engine.levelUps().update(op(), campaign, null, null, map("class", "Ranger", "skills",
					List.of("Survival", "Perception", "Nature"), "species", "Human", "species_skill", "Stealth",
					"origin_feat", "Skilled", "background", "Sage", "feat_choices",
					List.of(map("feat", "Skilled", "proficiencies", List.of("Animal Handling", "Medicine", "Insight")),
							map("feat", "Magic Initiate", "spell_list", "druid", "ability", "WIS", "cantrips",
									List.of("Guidance", "Resistance"), "spell", "Goodberry"))));
			Map<String, Object> committed = engine.levelUps().commit(op(), campaign, null, null);
			List<Map<String, Object>> feats = list(m(committed.get("sheet")).get("feats"));
			Map<String, Object> initiate = feats.stream().filter(f -> "Magic Initiate".equals(f.get("name")))
					.findFirst().orElseThrow();
			assertNull(initiate.get("pending"), "both feats resolved in one call");
			assertEquals("Goodberry", m(initiate.get("choices")).get("spell"));
			assertEquals("druid", m(initiate.get("choices")).get("spell_list"));

			// A second companion leaves Magic Initiate pending and completes it at the next level.
			String hob = (String) engine.runtime()
					.materialize(op(), campaign, "Scout", "Hob", null, null, "NEUTRAL_GOOD", null, false)
					.get("character");
			engine.levelUps().begin(op(), campaign, hob);
			engine.levelUps().update(op(), campaign, null, null,
					map("class", "Ranger", "skills", List.of("Survival", "Perception", "Nature"), "species", "Human",
							"species_skill", "Stealth", "origin_feat", "Alert", "background", "Sage"));
			Map<String, Object> first = engine.levelUps().commit(op(), campaign, null, null);
			Map<String, Object> pending = list(m(first.get("sheet")).get("feats")).stream()
					.filter(f -> "Magic Initiate".equals(f.get("name"))).findFirst().orElseThrow();
			assertEquals(List.of("ability", "cantrips", "spells"), pending.get("pending"));

			engine.rest().override(op(), campaign, "SET_XP", hob, map("xp", 300), "test", null);
			Map<String, Object> begun = engine.levelUps().begin(op(), campaign, hob);
			List<Map<String, Object>> owed = list(begun.get("pending_feat_choices"));
			assertEquals("Magic Initiate", owed.get(0).get("feat"));
			engine.levelUps().update(op(), campaign, null, null,
					map("feat_choices", map("feat", "Magic Initiate", "spell_list", "druid", "ability", "WIS",
							"cantrips", List.of("Guidance", "Resistance"), "spell", "Goodberry")));
			Map<String, Object> second = engine.levelUps().commit(op(), campaign, null, null);
			assertEquals(2, m(second.get("sheet")).get("level"));
			Map<String, Object> done = list(m(second.get("sheet")).get("feats")).stream()
					.filter(f -> "Magic Initiate".equals(f.get("name"))).findFirst().orElseThrow();
			assertNull(done.get("pending"));
			assertEquals("Goodberry", m(done.get("choices")).get("spell"));

			// With nothing pending, feat_choices at a later level is still refused.
			engine.rest().override(op(), campaign, "SET_XP", hob, map("xp", 900), "test", null);
			engine.levelUps().begin(op(), campaign, hob);
			RpgException refused = assertThrows(RpgException.class, () -> engine.levelUps().update(op(), campaign, null,
					null, map("feat_choices", map("feat", "Alert"))));
			assertEquals(ErrorCode.VALIDATION_FAILED, refused.code());
		}
	}

	@Test
	void customBackgroundsBehaveLikeSeededOnes() throws Exception {
		try (Engine engine = TestCampaigns.engine(TestCampaigns.tempDb("gaps-custom-background"))) {
			String campaign = setup(engine);
			Map<String, Object> defined = engine.content().define(op(), campaign, "BACKGROUND", "Noble",
					"custom:background/noble", "Born to a house with a name to keep up.", null, null, null,
					map("ability_scores", List.of("CHA", "INT", "WIS"), "feat", "Skilled", "skills",
							List.of("History", "Persuasion"), "tool", map("item", "Calligrapher's Supplies")),
					List.of("noble"), "GM");
			String ref = (String) defined.get("content");
			assertTrue(ref.startsWith("content:"));
			assertEquals("Skilled", m(defined.get("definition")).get("feat"));

			// Listed next to the SRD backgrounds, by the choices tool and by get_content_definitions.
			List<Map<String, Object>> options = list(
					engine.characters().choices(campaign, "BACKGROUND", null).get("backgrounds"));
			assertTrue(options.stream().anyMatch(o -> "Noble".equals(o.get("label")) && ref.equals(o.get("value"))));
			List<Map<String, Object>> items = list(engine.content()
					.definitions(campaign, "BACKGROUND", null, "noble", null, null, null, 25, "SUMMARY").get("items"));
			assertTrue(items.stream().anyMatch(i -> "Noble".equals(i.get("name"))));

			// Validation: three abilities, an Origin feat, two skills, a tool — refused otherwise; names must be new.
			assertEquals(ErrorCode.INVALID_ARGUMENT, assertThrows(RpgException.class,
					() -> engine.content().define(op(), campaign, "BACKGROUND", "Broken", null, null, null, null, null,
							map("ability_scores", List.of("CHA"), "feat", "Skilled", "skills",
									List.of("History", "Persuasion"), "tool", map("item", "Calligrapher's Supplies")),
							null, "GM"))
					.code());
			assertEquals(ErrorCode.CONFLICT, assertThrows(RpgException.class,
					() -> engine.content().define(op(), campaign, "BACKGROUND", "Acolyte", null, null, null, null, null,
							map("ability_scores", List.of("CHA", "INT", "WIS"), "feat", "Skilled", "skills",
									List.of("History", "Persuasion"), "tool", map("item", "Calligrapher's Supplies")),
							null, "GM"))
					.code());

			// Chosen by name in a draft, it grants exactly what a seeded background grants.
			String pc = (String) engine.characters().createDraft(op(), campaign,
					map("name", "Elowen", "species", "Human", "class", "Wizard", "ability_scores",
							map("INT", 15, "DEX", 14, "CON", 13, "WIS", 12, "CHA", 10, "STR", 8), "skills",
							List.of("Arcana", "Investigation"), "personality", "x", "background", "Noble",
							"background_ability_scores", map("CHA", 2, "INT", 1), "species_skill", "Insight",
							"origin_feat", "Alert", "feat_choices",
							map("feat", "Skilled", "proficiencies", List.of("Nature", "Survival", "Medicine"))),
					true).get("character");
			engine.characters().commitDraft(op(), campaign, pc, null);
			engine.campaigns().commitSetup(op(), campaign, null);
			engine.sessions().bootstrap(op(), campaign, null);
			Map<String, Object> sheet = engine.characters().characterSheet(campaign, pc, "FULL");
			assertEquals("Noble", m(sheet.get("background")).get("name"));
			assertEquals(ref, m(sheet.get("background")).get("ref"));
			List<?> skills = (List<?>) sheet.get("skill_proficiencies");
			assertTrue(skills.contains("History") && skills.contains("Persuasion"), skills.toString());
			assertTrue(((List<?>) sheet.get("tool_proficiencies")).contains("Calligrapher's Supplies"));
			assertTrue(list(sheet.get("feats")).stream()
					.anyMatch(f -> "Skilled".equals(f.get("name")) && "background".equals(f.get("source"))));
			assertEquals(12, m(m(sheet.get("abilities")).get("CHA")).get("score"), "10 + 2 from the background");
		}
	}

	@Test
	void armorClassCanBeFixedByAuditedOverride() throws Exception {
		try (Engine engine = TestCampaigns.engine(TestCampaigns.tempDb("gaps-armor-class"))) {
			String campaign = setup(engine);
			String pc = playableCriminal(engine, campaign);
			assertEquals(12,
					m(engine.characters().characterSheet(campaign, pc, "PLAY").get("armor_class")).get("value"),
					"unarmored: 10 + DEX");
			Map<String, Object> fixed = engine.rest().override(op(), campaign, "SET_ARMOR_CLASS", pc,
					map("armor_class", 15), "Draconic Resilience", null);
			assertEquals(15, m(fixed.get("after")).get("armor_class_override"));
			Map<String, Object> ac = m(engine.characters().characterSheet(campaign, pc, "PLAY").get("armor_class"));
			assertEquals(15, ac.get("value"));
			assertTrue(String.valueOf(ac.get("basis")).contains("override"), ac.toString());

			engine.rest().override(op(), campaign, "SET_ARMOR_CLASS", pc, map("clear", true), "armour found", null);
			Map<String, Object> back = m(engine.characters().characterSheet(campaign, pc, "PLAY").get("armor_class"));
			assertEquals(12, back.get("value"));
			assertEquals("Unarmored (10 + DEX)", back.get("basis"));

			RpgException bad = assertThrows(RpgException.class, () -> engine.rest().override(op(), campaign,
					"SET_ARMOR_CLASS", pc, map("armor_class", 99), "nope", null));
			assertEquals(ErrorCode.INVALID_ARGUMENT, bad.code());
		}
	}
}
