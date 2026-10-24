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
import se.hirt.mcp.rpg.character.Origins;
import se.hirt.mcp.rpg.persistence.Database;
import se.hirt.mcp.rpg.protocol.ErrorCode;
import se.hirt.mcp.rpg.protocol.Ref;
import se.hirt.mcp.rpg.protocol.RpgException;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static se.hirt.mcp.rpg.TestCampaigns.map;
import static se.hirt.mcp.rpg.TestCampaigns.op;

/** Background skills take precedence over flexible picks, which must be chosen again. */
class BackgroundSkillTest {

	@Test
	void changingBackgroundReopensDisplacedFeatProficiencies() throws Exception {
		try (Engine engine = TestCampaigns.engine(TestCampaigns.tempDb("background-feat-skill"))) {
			String campaign = setup(engine);
			String pc = skilledSoldier(engine, campaign, List.of("Stealth", "Nature", "Medicine"));
			Map<String, Object> before = engine.characters().characterSheet(campaign, pc, "FULL");
			assertEquals(8, ((List<?>) before.get("skill_proficiencies")).size());
			assertEquals(List.of("Stealth", "Nature", "Medicine"),
					m(feat(before, "Skilled").get("choices")).get("proficiencies"));
			assertEquals(Boolean.TRUE, engine.characters().validateDraft(campaign, pc).get("valid"));

			Map<String, Object> updated = engine.characters().updateDraft(op(), campaign, pc, null,
					map("background", "Criminal", "background_ability_scores", map("DEX", 2, "CON", 1)));
			Map<String, Object> after = m(updated.get("sheet"));
			Map<String, Object> skilled = feat(after, "Skilled");
			Map<String, Object> validation = engine.characters().validateDraft(campaign, pc);
			Map<String, Object> choices = engine.characters().choices(campaign, "FEAT", pc);

			assertEquals(List.of("proficiencies"), skilled.get("pending"),
					"Criminal takes over Stealth, so Skilled must ask for replacement proficiencies: " + after);
			assertEquals(Boolean.FALSE, validation.get("valid"), validation.toString());
			assertTrue(list(validation.get("violations")).stream().anyMatch(v -> "feat_choices".equals(v.get("path"))),
					validation.toString());
			assertTrue(
					list(choices.get("decisions")).stream().anyMatch(
							d -> "feat_choices".equals(d.get("id")) && "srd5e:feat/skilled".equals(d.get("feat"))),
					choices.toString());
		}
	}

	@Test
	void replacingDisplacedFeatProficienciesRestoresFullSkillCountAndAllowsCommit() throws Exception {
		try (Engine engine = TestCampaigns.engine(TestCampaigns.tempDb("background-feat-reselect"))) {
			String campaign = setup(engine);
			String pc = skilledSoldier(engine, campaign, List.of("Stealth", "Nature", "Medicine"));
			changeToCriminal(engine, campaign, pc);
			assertEquals(ErrorCode.VALIDATION_FAILED,
					assertThrows(RpgException.class, () -> engine.characters().commitDraft(op(), campaign, pc, null))
							.code());

			Map<String, Object> updated = replaceSkilled(engine, campaign, pc, "Skilled",
					List.of("Nature", "Medicine", "Survival"));
			Map<String, Object> sheet = m(updated.get("sheet"));
			assertEquals(Set.of("Acrobatics", "Perception", "Insight", "Sleight of Hand", "Stealth", "Nature",
					"Medicine", "Survival"), Set.copyOf((List<?>) sheet.get("skill_proficiencies")));
			assertNull(feat(sheet, "Skilled").get("pending"));
			assertEquals(Boolean.TRUE, engine.characters().validateDraft(campaign, pc).get("valid"));
			engine.characters().commitDraft(op(), campaign, pc, null);
			assertEquals(8,
					((List<?>) engine.characters().characterSheet(campaign, pc, "FULL").get("skill_proficiencies"))
							.size());
		}
	}

	@Test
	void twoDisplacedSkillsReopenOneCompleteFeatChoice() throws Exception {
		try (Engine engine = TestCampaigns.engine(TestCampaigns.tempDb("background-feat-two-skills"))) {
			String campaign = setup(engine);
			String pc = skilledSoldier(engine, campaign, List.of("Stealth", "Sleight of Hand", "Medicine"));
			Map<String, Object> updated = changeToCriminal(engine, campaign, pc);
			Map<String, Object> skilled = feat(m(updated.get("sheet")), "Skilled");
			assertEquals(List.of("proficiencies"), skilled.get("pending"));
			assertFalse(m(skilled.getOrDefault("choices", Map.of())).containsKey("proficiencies"));
			List<Map<String, Object>> decisions = list(updated.get("decisions"));
			List<Map<String, Object>> owed = decisions.stream().filter(d -> "feat_choices".equals(d.get("id")))
					.toList();
			assertEquals(1, owed.size(), decisions.toString());
			assertEquals(map("min", 3, "max", 3), owed.get(0).get("choose"));
			assertEquals(5, ((List<?>) m(updated.get("sheet")).get("skill_proficiencies")).size(),
					"the entire flexible choice group is released for a fresh selection");

			Map<String, Object> completed = replaceSkilled(engine, campaign, pc, "Skilled",
					List.of("Nature", "Medicine", "Survival"));
			assertEquals(8, ((List<?>) m(completed.get("sheet")).get("skill_proficiencies")).size());
			assertEquals(Boolean.TRUE, engine.characters().validateDraft(campaign, pc).get("valid"));
		}
	}

	@Test
	void backgroundChangeWithoutOverlapPreservesCompletedFeatChoices() throws Exception {
		try (Engine engine = TestCampaigns.engine(TestCampaigns.tempDb("background-feat-no-overlap"))) {
			String campaign = setup(engine);
			String pc = skilledSoldier(engine, campaign, List.of("Nature", "Medicine", "Survival"));
			Map<String, Object> before = feat(engine.characters().characterSheet(campaign, pc, "FULL"), "Skilled");
			Map<String, Object> updated = changeToCriminal(engine, campaign, pc);
			Map<String, Object> after = feat(m(updated.get("sheet")), "Skilled");
			assertEquals(before, after, "unaffected feat choices stay complete");
			assertFalse(list(updated.get("decisions")).stream().anyMatch(d -> "feat_choices".equals(d.get("id"))),
					updated.toString());
			assertEquals(8, ((List<?>) m(updated.get("sheet")).get("skill_proficiencies")).size());
			assertEquals(Boolean.TRUE, engine.characters().validateDraft(campaign, pc).get("valid"));
		}
	}

	@Test
	void displacedMixedSkillAndToolChoiceCanBeSelectedAgain() throws Exception {
		try (Engine engine = TestCampaigns.engine(TestCampaigns.tempDb("background-feat-tool"))) {
			String campaign = setup(engine);
			String pc = skilledSoldier(engine, campaign, List.of("Stealth", "Nature", "Calligrapher's Supplies"));
			Map<String, Object> reopened = m(changeToCriminal(engine, campaign, pc).get("sheet"));
			assertEquals(List.of("proficiencies"), feat(reopened, "Skilled").get("pending"));
			assertFalse(((List<?>) reopened.get("tool_proficiencies")).contains("Calligrapher's Supplies"),
					"tools are released with the displaced skill's choice group");
			Map<String, Object> completed = m(replaceSkilled(engine, campaign, pc, "Skilled",
					List.of("Nature", "Medicine", "Calligrapher's Supplies")).get("sheet"));
			assertEquals(7, ((List<?>) completed.get("skill_proficiencies")).size());
			assertEquals(Set.of("Thieves' Tools", "Calligrapher's Supplies"),
					Set.copyOf((List<?>) completed.get("tool_proficiencies")));
			assertNull(feat(completed, "Skilled").get("pending"));
			assertEquals(Boolean.TRUE, engine.characters().validateDraft(campaign, pc).get("valid"));
		}
	}

	@Test
	void staleReplacementIsRejectedWithoutChangingPendingChoicesOrOtherGrants() throws Exception {
		try (Engine engine = TestCampaigns.engine(TestCampaigns.tempDb("background-feat-rollback"))) {
			String campaign = setup(engine);
			String pc = skilledSoldier(engine, campaign, List.of("Stealth", "Nature", "Medicine"));
			changeToCriminal(engine, campaign, pc);
			Map<String, List<Map<String, Object>>> before = engine.db().snapshot(Ref.id(campaign, Ref.CAMPAIGN));
			RpgException rejected = assertThrows(RpgException.class,
					() -> replaceSkilled(engine, campaign, pc, "Skilled", List.of("Nature", "Medicine", "Stealth")));
			assertEquals(ErrorCode.VALIDATION_FAILED, rejected.code());
			assertEquals(before, engine.db().snapshot(Ref.id(campaign, Ref.CAMPAIGN)),
					"rejection rolls back even the new picks inserted before the conflicting skill");
			Map<String, Object> sheet = engine.characters().characterSheet(campaign, pc, "FULL");
			assertEquals(List.of("proficiencies"), feat(sheet, "Skilled").get("pending"));
			assertEquals(Set.of("Acrobatics", "Perception", "Insight", "Sleight of Hand", "Stealth"),
					Set.copyOf((List<?>) sheet.get("skill_proficiencies")));
			replaceSkilled(engine, campaign, pc, "Skilled", List.of("Nature", "Medicine", "Survival"));
			assertEquals(Boolean.TRUE, engine.characters().validateDraft(campaign, pc).get("valid"));
		}
	}

	@Test
	void repeatedFeatReopensAndReselectsOnlyTheAffectedInstance() throws Exception {
		try (Engine engine = TestCampaigns.engine(TestCampaigns.tempDb("background-feat-repeat"))) {
			String campaign = setup(engine);
			String pc = skilledSoldier(engine, campaign, List.of("Nature", "Medicine", "Survival"));
			// Normal creation grants only one species feat; model a second grant using the feat engine.
			engine.db().mutate(
					Database.Mutation.of("second_skilled_fixture", Ref.id(campaign, Ref.CAMPAIGN), op(), "GM", null),
					tx -> {
						Origins.grantFeat(tx, engine.rules(), tx.get("character", Ref.id(pc, Ref.CHARACTER)),
								engine.rules().resolve("FEAT", "Skilled").orElseThrow(), "class",
								map("proficiencies", List.of("Stealth", "Arcana", "History")));
						return Map.of();
					});
			Map<String, Object> updated = changeToCriminal(engine, campaign, pc);
			Map<String, Object> sheet = m(updated.get("sheet"));
			List<Map<String, Object>> skilledFeats = list(sheet.get("feats")).stream()
					.filter(f -> "Skilled".equals(f.get("name"))).toList();
			assertEquals(2, skilledFeats.size());
			Map<String, Object> unaffected = skilledFeats.stream().filter(f -> "species".equals(f.get("source")))
					.findFirst().orElseThrow();
			Map<String, Object> affected = skilledFeats.stream().filter(f -> "class".equals(f.get("source")))
					.findFirst().orElseThrow();
			assertEquals(List.of("Nature", "Medicine", "Survival"), m(unaffected.get("choices")).get("proficiencies"));
			assertNull(unaffected.get("pending"));
			assertEquals(List.of("proficiencies"), affected.get("pending"));
			assertTrue(
					list(updated.get("decisions")).stream().anyMatch(d -> "srd5e:feat/skilled#2".equals(d.get("feat"))),
					updated.toString());

			Map<String, Object> completed = m(replaceSkilled(engine, campaign, pc, "srd5e:feat/skilled#2",
					List.of("Arcana", "History", "Religion")).get("sheet"));
			assertEquals(11, ((List<?>) completed.get("skill_proficiencies")).size());
			assertEquals(unaffected, feat(completed, "Skilled"));
			assertTrue(list(completed.get("feats")).stream().filter(f -> "Skilled".equals(f.get("name")))
					.allMatch(f -> f.get("pending") == null));
			assertEquals(Boolean.TRUE, engine.characters().validateDraft(campaign, pc).get("valid"));
		}
	}

	private static Map<String, Object> changeToCriminal(Engine engine, String campaign, String pc) {
		return engine.characters().updateDraft(op(), campaign, pc, null,
				map("background", "Criminal", "background_ability_scores", map("DEX", 2, "CON", 1)));
	}

	private static Map<String, Object> replaceSkilled(
		Engine engine, String campaign, String pc, String feat, List<String> proficiencies) {
		return engine.characters().updateDraft(op(), campaign, pc, null,
				map("feat_choices", map("feat", feat, "proficiencies", proficiencies)));
	}

	private static String setup(Engine engine) {
		String campaign = (String) engine.campaigns().create(op(), null, null).get("campaign");
		engine.campaigns().updateSetup(op(), campaign, null,
				map("content_profile", "PEGI_16", "experience", "SURPRISE_ME", "rules", Map.of(), "continuation",
						"CHECKPOINT", "party", "SURPRISE_ME", "adventure",
						map("premise", "x", "opening_location", "Gate", "immediate_goal", "y")));
		return campaign;
	}

	private static String skilledSoldier(Engine engine, String campaign, List<String> proficiencies) {
		return (String) engine.characters().createDraft(op(), campaign,
				map("name", "Hilda", "species", "Human", "class", "Fighter", "ability_scores",
						map("STR", 15, "CON", 14, "DEX", 13, "WIS", 12, "INT", 10, "CHA", 8), "background", "Soldier",
						"background_ability_scores", map("STR", 2, "CON", 1), "background_tool",
						"srd5e:item/gaming-set-dice", "species_skill", "Insight", "origin_feat",
						map("feat", "Skilled", "proficiencies", proficiencies), "skills",
						List.of("Acrobatics", "Perception"), "personality", "x", "starting_equipment", "A"),
				true).get("character");
	}

	private static Map<String, Object> feat(Map<String, Object> sheet, String name) {
		return list(sheet.get("feats")).stream().filter(f -> name.equals(f.get("name"))).findFirst().orElseThrow();
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
