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
import se.hirt.mcp.rpg.protocol.Ref;
import se.hirt.mcp.rpg.protocol.RpgException;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static se.hirt.mcp.rpg.TestCampaigns.map;
import static se.hirt.mcp.rpg.TestCampaigns.op;

/** A starting equipment answer belongs to the class for which it was chosen. */
class ClassStartingEquipmentTest {

	@Test
	void changingClassClearsThePreviousEquipmentAnswerAndOffersANewChoice() throws Exception {
		try (Engine engine = TestCampaigns.engine(TestCampaigns.tempDb("class-equipment-change"))) {
			String campaign = setup(engine);
			String pc = monk(engine, campaign);
			Map<String, Object> updated = engine.characters().updateDraft(op(), campaign, pc, null,
					map("class", "Fighter"));
			Map<String, Object> sheet = m(updated.get("sheet"));
			Map<String, Object> preview = m(sheet.get("starting_equipment"));
			List<Map<String, Object>> decisions = list(updated.get("decisions"));
			Map<String, Object> creation = engine.db()
					.read(tx -> tx.get("character", Ref.id(pc, Ref.CHARACTER)).map("creation_json"));

			assertFalse(creation.containsKey("starting_equipment"), creation.toString());
			assertEquals(Boolean.FALSE, preview.get("chosen_explicitly"), preview.toString());
			assertEquals("C", preview.get("option"), "Fighter defaults to its own gold-only option");
			assertEquals(Map.of(), preview.get("choices"));
			assertTrue(decisions.stream().anyMatch(d -> "starting_equipment".equals(d.get("id"))),
					decisions.toString());
			assertEquals(Map.of("option", "A", "choices", Map.of()), creation.get("background_equipment"));
		}
	}

	@Test
	void changedClassUsesItsGoldDefaultAtCampaignCommitAndKeepsTheBackgroundBundle() throws Exception {
		try (Engine engine = TestCampaigns.engine(TestCampaigns.tempDb("class-equipment-gold"))) {
			String campaign = setup(engine);
			String pc = monk(engine, campaign);
			engine.characters().updateDraft(op(), campaign, pc, null,
					map("class", "Fighter", "skills", List.of("Acrobatics", "History")));

			Map<String, Object> granted = commit(engine, campaign, pc);
			assertEquals("C", granted.get("option"));
			assertEquals("A", granted.get("background_option"));
			assertEquals(16900L, granted.get("money_cp"), "155 GP from Fighter + 14 GP from Soldier A");
			List<Map<String, Object>> inventory = list(
					engine.characters().characterSheet(campaign, pc, "PLAY").get("inventory"));
			assertTrue(inventory.stream().anyMatch(e -> "Spear".equals(e.get("name"))),
					"the selected Soldier bundle is still granted");
			assertFalse(
					inventory.stream()
							.anyMatch(e -> List.of("Chain Mail", "Greatsword", "Lute").contains(e.get("name"))),
					"neither the unchosen Fighter bundle nor the old Monk choice is granted");
		}
	}

	@Test
	void anOptionUnavailableForTheNewClassCannotBreakCampaignCommit() throws Exception {
		try (Engine engine = TestCampaigns.engine(TestCampaigns.tempDb("class-equipment-unavailable"))) {
			String campaign = setup(engine);
			String pc = monk(engine, campaign);
			engine.characters().updateDraft(op(), campaign, pc, null,
					map("class", "Fighter", "starting_equipment", "C"));
			engine.characters().updateDraft(op(), campaign, pc, null,
					map("class", "Monk", "skills", List.of("Acrobatics", "History")));

			Map<String, Object> granted = commit(engine, campaign, pc);
			assertEquals("B", granted.get("option"));
			assertEquals("A", granted.get("background_option"));
			assertEquals(6400L, granted.get("money_cp"), "50 GP from Monk + 14 GP from Soldier A");
		}
	}

	@Test
	void selectingTheSameClassPreservesTheCompleteEquipmentAnswer() throws Exception {
		try (Engine engine = TestCampaigns.engine(TestCampaigns.tempDb("class-equipment-same"))) {
			String campaign = setup(engine);
			String pc = monk(engine, campaign);
			Map<String, Object> before = m(
					engine.characters().characterSheet(campaign, pc, "FULL").get("starting_equipment"));
			Map<String, Object> updated = engine.characters().updateDraft(op(), campaign, pc, null,
					map("class", "srd5e:class/monk"));
			Map<String, Object> after = m(m(updated.get("sheet")).get("starting_equipment"));

			assertEquals(before, after);
			assertEquals(Boolean.TRUE, after.get("chosen_explicitly"));
			assertEquals(Map.of("ARTISANS_TOOLS_OR_MUSICAL_INSTRUMENT", "srd5e:item/lute"), after.get("choices"));
			assertFalse(
					list(updated.get("decisions")).stream().anyMatch(d -> "starting_equipment".equals(d.get("id"))));
		}
	}

	@Test
	void explicitReplacementUsesTheFinalClassInEitherFieldOrder() throws Exception {
		for (boolean equipmentFirst : List.of(false, true)) {
			try (Engine engine = TestCampaigns.engine(TestCampaigns.tempDb("class-equipment-order"))) {
				String campaign = setup(engine);
				String pc = monk(engine, campaign);
				// C exists only for the new Fighter class, so the answer must use that class in either order.
				Map<String, Object> changes = equipmentFirst ? map("starting_equipment", "C", "class", "Fighter")
						: map("class", "Fighter", "starting_equipment", "C");
				Map<String, Object> updated = engine.characters().updateDraft(op(), campaign, pc, null, changes);
				Map<String, Object> preview = m(m(updated.get("sheet")).get("starting_equipment"));
				assertEquals("C", preview.get("option"));
				assertEquals(Boolean.TRUE, preview.get("chosen_explicitly"));
				assertEquals(Map.of(), preview.get("choices"));
				assertFalse(list(updated.get("decisions")).stream()
						.anyMatch(d -> "starting_equipment".equals(d.get("id"))));

				Map<String, Object> monkAnswer = map("option", "A", "choices",
						map("ARTISANS_TOOLS_OR_MUSICAL_INSTRUMENT", "Lute"));
				Map<String, Object> backToMonk = equipmentFirst ? map("starting_equipment", monkAnswer, "class", "Monk")
						: map("class", "Monk", "starting_equipment", monkAnswer);
				Map<String, Object> monk = engine.characters().updateDraft(op(), campaign, pc, null, backToMonk);
				Map<String, Object> monkPreview = m(m(monk.get("sheet")).get("starting_equipment"));
				assertEquals("A", monkPreview.get("option"));
				assertEquals(Boolean.TRUE, monkPreview.get("chosen_explicitly"));
				assertEquals(Map.of("ARTISANS_TOOLS_OR_MUSICAL_INSTRUMENT", "srd5e:item/lute"),
						monkPreview.get("choices"));
			}
		}
	}

	@Test
	void invalidReplacementRollsBackTheClassAndBothEquipmentAnswersInEitherFieldOrder() throws Exception {
		for (boolean equipmentFirst : List.of(false, true)) {
			try (Engine engine = TestCampaigns.engine(TestCampaigns.tempDb("class-equipment-rollback"))) {
				String campaign = setup(engine);
				String pc = monk(engine, campaign);
				engine.characters().updateDraft(op(), campaign, pc, null,
						map("class", "Fighter", "starting_equipment", "C"));
				Map<String, List<Map<String, Object>>> before = engine.db().snapshot(Ref.id(campaign, Ref.CAMPAIGN));
				Map<String, Object> changes = equipmentFirst ? map("starting_equipment", "C", "class", "Monk")
						: map("class", "Monk", "starting_equipment", "C");

				RpgException rejected = assertThrows(RpgException.class,
						() -> engine.characters().updateDraft(op(), campaign, pc, null, changes));
				assertEquals(ErrorCode.VALIDATION_FAILED, rejected.code());
				assertEquals(before, engine.db().snapshot(Ref.id(campaign, Ref.CAMPAIGN)),
						"Monk does not offer C; all class, equipment, proficiency and revision changes must roll back");
			}
		}
	}

	private static Map<String, Object> commit(Engine engine, String campaign, String pc) {
		Map<String, Object> validation = engine.characters().validateDraft(campaign, pc);
		assertEquals(Boolean.TRUE, validation.get("valid"), validation.toString());
		engine.characters().commitDraft(op(), campaign, pc, null);
		Map<String, Object> result = engine.campaigns().commitSetup(op(), campaign, null);
		engine.sessions().bootstrap(op(), campaign, null);
		return m(m(result.get("starting_equipment")).get(pc));
	}

	private static String setup(Engine engine) {
		String campaign = (String) engine.campaigns().create(op(), null, null).get("campaign");
		engine.campaigns().updateSetup(op(), campaign, null,
				map("content_profile", "PEGI_16", "experience", "SURPRISE_ME", "rules", Map.of(), "continuation",
						"CHECKPOINT", "party", "SURPRISE_ME", "adventure",
						map("premise", "x", "opening_location", "Gate", "immediate_goal", "y")));
		return campaign;
	}

	private static String monk(Engine engine, String campaign) {
		return (String) engine.characters()
				.createDraft(op(), campaign,
						map("name", "Hilda", "species", "Human", "class", "Monk", "ability_scores",
								map("STR", 15, "CON", 14, "DEX", 13, "WIS", 12, "INT", 10, "CHA", 8), "background",
								"Soldier", "background_ability_scores", map("STR", 2, "CON", 1), "background_tool",
								"srd5e:item/gaming-set-dice", "species_skill", "Insight", "origin_feat",
								map("feat", "Skilled", "proficiencies", List.of("Nature", "Medicine", "Survival")),
								"skills", List.of("Acrobatics", "History"), "personality", "x", "starting_equipment",
								map("option", "A", "choices", map("ARTISANS_TOOLS_OR_MUSICAL_INSTRUMENT", "Lute")),
								"background_equipment", "A"),
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
