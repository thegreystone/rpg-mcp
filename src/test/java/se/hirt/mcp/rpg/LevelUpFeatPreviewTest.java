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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.junit.jupiter.api.Test;
import se.hirt.mcp.rpg.content.SeedImporter;
import se.hirt.mcp.rpg.dice.ScriptedRollService;
import se.hirt.mcp.rpg.persistence.Database;
import se.hirt.mcp.rpg.protocol.Json;
import se.hirt.mcp.rpg.protocol.Ref;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static se.hirt.mcp.rpg.TestCampaigns.committedCampaign;
import static se.hirt.mcp.rpg.TestCampaigns.engine;
import static se.hirt.mcp.rpg.TestCampaigns.map;
import static se.hirt.mcp.rpg.TestCampaigns.op;
import static se.hirt.mcp.rpg.TestCampaigns.tempDb;

class LevelUpFeatPreviewTest {

	@Test
	void constitutionFeatIncreaseAppearsInEveryPreviewAlongsideItsHitPointIncrease() throws Exception {
		try (Engine engine = engineWithConstitutionFeat(tempDb("level-up-feat-preview"))) {
			String[] fixture = pendingFour(engine, 15);
			String campaign = fixture[0], pc = fixture[1], transaction = fixture[2];
			Map<String, List<Map<String, Object>>> liveBefore = snapshot(engine, campaign);
			String updateId = op();
			Map<String, Object> selection = map("feat", map("feat", "Test Stalwart", "ability_increase", "CON"));
			Map<String, Object> updated = engine.levelUps().update(updateId, campaign, transaction, null, selection);
			Map<String, List<Map<String, Object>>> pending = snapshot(engine, campaign);
			Map<String, Object> choices = engine.levelUps().choices(campaign, transaction);
			Map<String, Object> validated = engine.levelUps().validate(campaign, transaction);
			Map<String, Object> after = m(m(updated.get("preview")).get("after"));
			assertEquals(16, m(after.get("abilities")).get("CON"));
			assertEquals(4, after.get("constitution_hp"));
			assertEquals(updated.get("preview"), choices.get("preview"));
			assertEquals(updated.get("preview"), validated.get("preview"));
			assertEquals(true, validated.get("valid"));
			assertEquals(pending, snapshot(engine, campaign), "preview reads do not change canonical or pending state");
			assertLiveCharacterUnchanged(liveBefore, pending);
			assertEquals(true,
					engine.levelUps().update(updateId, campaign, transaction, null, selection).get("replayed"));
			assertEquals(pending, snapshot(engine, campaign),
					"replaying the choice does not apply its ability increase twice");

			Map<String, Object> committed = engine.levelUps().commit(op(), campaign, transaction, null);
			assertEquals(4, committed.get("constitution_hp"));
			assertCommittedMatchesPreview(engine, campaign, pc, updated, committed);
			assertEquals(16, score(engine.characters().characterSheet(campaign, pc, "FULL"), "CON"));
		}
	}

	@Test
	void grapplerPreviewsTheChosenStrengthOrDexterityIncreaseAndNormalizesCasing() throws Exception {
		for (String input : List.of("STR", "dEx")) {
			try (Engine engine = engine(tempDb("level-up-grappler-" + input))) {
				String[] fixture = pendingFour(engine, 15);
				String campaign = fixture[0], pc = fixture[1], transaction = fixture[2];
				String ability = input.toUpperCase(Locale.ROOT);
				Map<String, Object> updated = engine.levelUps().update(op(), campaign, transaction, null,
						map("feat", map("feat", "Grappler", "ability_increase", input)));
				Map<String, Object> preview = assertReadOnlyPreviews(engine, campaign, transaction, updated);
				Map<String, Object> beforeScores = m(m(preview.get("before")).get("abilities"));
				Map<String, Object> after = m(preview.get("after"));
				Map<String, Object> expected = new LinkedHashMap<>(beforeScores);
				expected.put(ability, 16);
				assertEquals(expected, after.get("abilities"));
				assertFalse(after.containsKey("constitution_hp"));
				assertEquals(ability,
						m(m(m(updated.get("chosen")).get("feat")).get("choices")).get("ability_increase"));

				Map<String, Object> committed = engine.levelUps().commit(op(), campaign, transaction, null);
				assertCommittedMatchesPreview(engine, campaign, pc, updated, committed);
			}
		}
	}

	@Test
	void constitutionFeatPreviewAndCommitRespectTheAbilityCapAtTwenty() throws Exception {
		for (int constitution : List.of(19, 20)) {
			try (Engine engine = engineWithConstitutionFeat(tempDb("level-up-feat-cap-" + constitution))) {
				String[] fixture = pendingFour(engine, constitution);
				String campaign = fixture[0], pc = fixture[1], transaction = fixture[2];
				Map<String, Object> updated = engine.levelUps().update(op(), campaign, transaction, null,
						map("feat", map("feat", "Test Stalwart", "ability_increase", "con")));
				Map<String, Object> preview = assertReadOnlyPreviews(engine, campaign, transaction, updated);
				Map<String, Object> after = m(preview.get("after"));
				Map<String, Object> expected = new LinkedHashMap<>(m(m(preview.get("before")).get("abilities")));
				expected.put("CON", 20);
				assertEquals(expected, after.get("abilities"));
				if (constitution == 19) {
					assertEquals(4, after.get("constitution_hp"));
				} else {
					assertFalse(after.containsKey("constitution_hp"));
				}
				Map<String, Object> committed = engine.levelUps().commit(op(), campaign, transaction, null);
				assertCommittedMatchesPreview(engine, campaign, pc, updated, committed);
			}
		}
	}

	@Test
	void featWithoutAnAbilityIncreaseLeavesEveryPreviewScoreUnchanged() throws Exception {
		try (Engine engine = engine(tempDb("level-up-feat-without-increase"))) {
			String[] fixture = pendingFour(engine, 15);
			String campaign = fixture[0], pc = fixture[1], transaction = fixture[2];
			Map<String, Object> updated = engine.levelUps().update(op(), campaign, transaction, null,
					map("feat", "Alert"));
			Map<String, Object> preview = assertReadOnlyPreviews(engine, campaign, transaction, updated);
			Map<String, Object> before = m(preview.get("before"));
			Map<String, Object> after = m(preview.get("after"));
			assertEquals(before.get("abilities"), after.get("abilities"));
			assertFalse(after.containsKey("constitution_hp"));
			assertEquals(((Number) before.get("max_hp")).intValue()
					+ ((Number) m(updated.get("hit_points")).get("gain")).intValue(), after.get("max_hp"));
			Map<String, Object> committed = engine.levelUps().commit(op(), campaign, transaction, null);
			assertEquals("Alert", committed.get("feat"));
			assertCommittedMatchesPreview(engine, campaign, pc, updated, committed);
		}
	}

	@Test
	void switchingBetweenFeatsAndAbilityImprovementsReplacesEarlierPreviewBonuses() throws Exception {
		try (Engine engine = engineWithConstitutionFeat(tempDb("level-up-feat-switch"))) {
			String[] fixture = pendingFour(engine, 15);
			String campaign = fixture[0], pc = fixture[1], transaction = fixture[2];
			Map<String, List<Map<String, Object>>> liveBefore = snapshot(engine, campaign);
			Map<String, Object> feat = engine.levelUps().update(op(), campaign, transaction, null,
					map("feat", map("feat", "Test Stalwart", "ability_increase", "CON")));
			Map<String, Object> baseScores = m(m(m(feat.get("preview")).get("before")).get("abilities"));
			assertScoreChanges(feat, baseScores, map("CON", 16));

			Map<String, Object> asi = engine.levelUps().update(op(), campaign, transaction, null,
					map("ability_score_improvement", map("CON", 1, "INT", 1)));
			assertScoreChanges(asi, baseScores, map("CON", 16, "INT", ((Number) baseScores.get("INT")).intValue() + 1));
			assertFalse(m(asi.get("chosen")).containsKey("feat"));

			Map<String, Object> grappler = engine.levelUps().update(op(), campaign, transaction, null,
					map("feat", map("feat", "Grappler", "ability_increase", "DEX")));
			assertScoreChanges(grappler, baseScores, map("DEX", 16));
			assertFalse(m(grappler.get("chosen")).containsKey("ability_score_improvement"));
			assertFalse(m(m(grappler.get("preview")).get("after")).containsKey("constitution_hp"));

			Map<String, Object> alert = engine.levelUps().update(op(), campaign, transaction, null,
					map("feat", "Alert"));
			assertScoreChanges(alert, baseScores, Map.of());
			Map<String, Object> finalAsi = engine.levelUps().update(op(), campaign, transaction, null,
					map("ability_score_improvement", map("STR", 2)));
			assertScoreChanges(finalAsi, baseScores, map("STR", 17));
			assertReadOnlyPreviews(engine, campaign, transaction, finalAsi);
			assertLiveCharacterUnchanged(liveBefore, snapshot(engine, campaign));
			Map<String, Object> committed = engine.levelUps().commit(op(), campaign, transaction, null);
			assertCommittedMatchesPreview(engine, campaign, pc, finalAsi, committed);
		}
	}

	@Test
	void abandoningAFeatPreviewKeepsTheLiveCharacterUnchanged() throws Exception {
		try (Engine engine = engineWithConstitutionFeat(tempDb("level-up-feat-abandon"))) {
			String[] fixture = pendingFour(engine, 15);
			String campaign = fixture[0], pc = fixture[1], transaction = fixture[2];
			Map<String, Object> sheetBefore = liveSheet(engine, campaign, pc);
			Map<String, List<Map<String, Object>>> liveBefore = snapshot(engine, campaign);
			Map<String, Object> updated = engine.levelUps().update(op(), campaign, transaction, null,
					map("feat", map("feat", "Test Stalwart", "ability_increase", "CON")));
			assertReadOnlyPreviews(engine, campaign, transaction, updated);
			assertEquals(sheetBefore, liveSheet(engine, campaign, pc));
			assertLiveCharacterUnchanged(liveBefore, snapshot(engine, campaign));

			engine.levelUps().abandon(op(), campaign, transaction, "keep the existing character");

			assertEquals(sheetBefore, liveSheet(engine, campaign, pc));
			assertLiveCharacterUnchanged(liveBefore, snapshot(engine, campaign));
			assertEquals(3, engine.characters().characterSheet(campaign, pc, "FULL").get("level"));
			assertTrue(((ScriptedRollService) engine.roller()).exhausted(),
					"preview reads and abandonment consume no dice");
		}
	}

	/**
	 * A test-only installed feat supplies a Constitution choice absent from the level-four SRD
	 * feats.
	 */
	private static Engine engineWithConstitutionFeat(Path database) {
		try (Database db = new Database(database)) {
			SeedImporter.importIfMissing(db, SeedImporter.DEFAULT_RULESET);
			db.mutate(Database.Mutation.of("feat_preview_fixture", null, op(), "GM", null), tx -> {
				long ruleset = tx
						.queryOne("SELECT id FROM installed_ruleset WHERE namespace = ? ORDER BY id DESC LIMIT 1",
								"srd5e")
						.orElseThrow().id();
				tx.rawInsert("installed_content", map("ruleset_id", ruleset, "content_id", "srd5e:feat/test-stalwart",
						"kind", "FEAT", "name", "Test Stalwart", "payload_json", Json.write(map("category", "GENERAL",
								"prerequisite_level", 4, "asi", map("choose_one", List.of("CON"), "amount", 1)))));
				return Map.of();
			});
		}
		return engine(database);
	}

	private static String[] pendingFour(Engine engine, int constitution) {
		String campaign = committedCampaign(engine);
		String pc = "character:1";
		engine.sessions().bootstrap(op(), campaign, null);
		engine.runtime().awardXp(op(), campaign, List.of(pc), 2700, "QUEST", "reach the next ability improvement");
		ScriptedRollService dice = (ScriptedRollService) engine.roller();
		for (int level = 2; level <= 3; level++) {
			dice.queue(4);
			String transaction = (String) m(engine.levelUps().begin(op(), campaign, pc).get("transaction")).get("ref");
			if (level == 2) {
				engine.levelUps().update(op(), campaign, transaction, null,
						map("metamagic", List.of("Subtle Spell", "Distant Spell")));
			}
			engine.levelUps().commit(op(), campaign, transaction, null);
		}
		engine.rest().override(op(), campaign, "SET_ABILITY_SCORE", pc, map("ability", "CON", "score", constitution),
				"set the Constitution boundary for the feat", null);
		engine.rest().override(op(), campaign, "SET_ABILITY_SCORE", pc, map("ability", "STR", "score", 15),
				"set a legal Strength for Grappler", null);
		engine.rest().override(op(), campaign, "SET_ABILITY_SCORE", pc, map("ability", "DEX", "score", 15),
				"set a legal Dexterity for Grappler", null);
		dice.queue(4);
		String transaction = (String) m(engine.levelUps().begin(op(), campaign, pc).get("transaction")).get("ref");
		return new String[] {campaign, pc, transaction};
	}

	private static Map<String, Object> assertReadOnlyPreviews(
		Engine engine, String campaign, String transaction, Map<String, Object> updated) {
		Map<String, List<Map<String, Object>>> before = snapshot(engine, campaign);
		Map<String, Object> choices = engine.levelUps().choices(campaign, transaction);
		Map<String, Object> validated = engine.levelUps().validate(campaign, transaction);
		assertEquals(updated.get("preview"), choices.get("preview"));
		assertEquals(updated.get("preview"), validated.get("preview"));
		assertEquals(true, validated.get("valid"));
		assertEquals(before, snapshot(engine, campaign));
		return m(updated.get("preview"));
	}

	private static void assertCommittedMatchesPreview(
		Engine engine, String campaign, String pc, Map<String, Object> updated, Map<String, Object> committed) {
		Map<String, Object> after = m(m(updated.get("preview")).get("after"));
		Map<String, Object> sheet = engine.characters().characterSheet(campaign, pc, "FULL");
		assertEquals(after.get("level"), sheet.get("level"));
		assertEquals(after.get("proficiency_bonus"), sheet.get("proficiency_bonus"));
		for (var entry : m(after.get("abilities")).entrySet()) {
			assertEquals(entry.getValue(), score(sheet, entry.getKey()), entry.getKey());
		}
		assertEquals(after.get("max_hp"), m(sheet.get("hp")).get("max"));
		assertEquals(after.get("max_hp"), m(sheet.get("hp")).get("current"));
		assertEquals(after.get("constitution_hp"), committed.get("constitution_hp"));
		Map<String, Object> returnedSheet = m(committed.get("sheet"));
		assertEquals(sheet.get("abilities"), returnedSheet.get("abilities"));
		assertEquals(sheet.get("hp"), returnedSheet.get("hp"));
		assertEquals(sheet.get("revision"), returnedSheet.get("revision"));
	}

	private static Map<String, Object> liveSheet(Engine engine, String campaign, String pc) {
		Map<String, Object> sheet = new LinkedHashMap<>(engine.characters().characterSheet(campaign, pc, "FULL"));
		sheet.remove("meta");
		return sheet;
	}

	private static int score(Map<String, Object> sheet, String ability) {
		return ((Number) m(m(sheet.get("abilities")).get(ability)).get("score")).intValue();
	}

	private static void assertScoreChanges(
		Map<String, Object> updated, Map<String, Object> baseScores, Map<String, Object> changes) {
		Map<String, Object> expected = new LinkedHashMap<>(baseScores);
		expected.putAll(changes);
		assertEquals(expected, m(m(updated.get("preview")).get("after")).get("abilities"));
	}

	private static void assertLiveCharacterUnchanged(
		Map<String, List<Map<String, Object>>> before, Map<String, List<Map<String, Object>>> after) {
		for (String table : List.of("character", "character_class", "character_trait", "resource_state")) {
			assertEquals(before.get(table), after.get(table), table);
		}
	}

	private static Map<String, List<Map<String, Object>>> snapshot(Engine engine, String campaign) {
		return engine.db().snapshot(Ref.id(campaign, Ref.CAMPAIGN));
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> m(Object value) {
		return (Map<String, Object>) value;
	}
}
