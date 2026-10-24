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
import se.hirt.mcp.rpg.character.RuntimeService;
import se.hirt.mcp.rpg.persistence.Database;
import se.hirt.mcp.rpg.protocol.Ref;
import se.hirt.mcp.rpg.rules.Effects;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static se.hirt.mcp.rpg.TestCampaigns.*;

/** Exhaustion is one accumulated level across runtime changes, recovery and the character sheet. */
class ExhaustionTest {

	@Test
	void repeatedExhaustionAccumulatesInTheStoredLevelAndSheet() throws Exception {
		try (Engine engine = engine(tempDb("exhaustion-accumulation"))) {
			String campaign = committedCampaign(engine);
			engine.sessions().bootstrap(op(), campaign, null);
			String pc = "character:1";
			Map<String, Object> first = engine.runtime().applyRuntimeChange(op(), campaign, pc,
					map("kind", "ADD_CONDITION", "condition", "EXHAUSTION", "reason", "first forced march"));
			Map<String, Object> second = engine.runtime().applyRuntimeChange(op(), campaign, pc,
					map("kind", "ADD_CONDITION", "condition", "EXHAUSTION", "reason", "second forced march"));
			Map<String, Object> row = engine.db().read(tx -> tx.get("character", Ref.id(pc, Ref.CHARACTER)).asMap());
			Map<String, Object> sheet = engine.characters().characterSheet(campaign, pc, "PLAY");
			List<Map<String, Object>> conditions = list(sheet.get("conditions"));
			assertEquals(2, ((Number) row.get("exhaustion")).intValue(), "each application adds a level");
			assertEquals(2, sheet.get("exhaustion"), "the sheet exposes the stored level");
			assertEquals(1, conditions.size(), "exhaustion is one condition carrying the accumulated level");
			assertEquals("EXHAUSTION", conditions.get(0).get("condition"));
			assertEquals(2, conditions.get(0).get("level"));
			assertEquals("Exhaustion", conditions.get(0).get("source"));
			assertEquals(1, first.get("exhaustion"));
			assertEquals(2, second.get("exhaustion"));
			assertFalse(second.containsKey("already_present"), "a repeated application increases exhaustion");

			String operation = op();
			Map<String, Object> change = map("kind", "ADD_CONDITION", "condition", "EXHAUSTION", "duration", "1 minute",
					"reason", "third forced march");
			engine.runtime().applyRuntimeChange(operation, campaign, pc, change);
			Map<String, Object> after = characterRow(engine, pc);
			Map<String, Object> replay = engine.runtime().applyRuntimeChange(operation, campaign, pc, change);
			assertEquals(Boolean.TRUE, replay.get("replayed"));
			assertEquals(3, replay.get("exhaustion"));
			assertEquals(after, characterRow(engine, pc), "replaying the same operation adds no further level");
			engine.sessions().advanceTime(op(), campaign, 2, "a pause");
			assertExhaustion(engine, campaign, pc, 3);
		}
	}

	@Test
	void shortRestsLeaveExhaustionAndEachLongRestRemovesOneLevel() throws Exception {
		try (Engine engine = engine(tempDb("exhaustion-rests"))) {
			String campaign = committedCampaign(engine);
			engine.sessions().bootstrap(op(), campaign, null);
			String pc = "character:1";
			gain(engine, campaign, pc, 3);
			engine.rest().rest(op(), campaign, "SHORT", null, List.of(pc));
			assertExhaustion(engine, campaign, pc, 3);
			for (int remaining = 2; remaining >= 0; remaining--) {
				engine.sessions().advanceTime(op(), campaign, 16 * 60, "time between Long Rests");
				engine.rest().rest(op(), campaign, "LONG", null, List.of(pc));
				assertExhaustion(engine, campaign, pc, remaining);
			}
			engine.sessions().advanceTime(op(), campaign, 16 * 60, "time between Long Rests");
			engine.rest().rest(op(), campaign, "LONG", null, List.of(pc));
			assertExhaustion(engine, campaign, pc, 0);
		}
	}

	@Test
	void explicitRemovalClearsAllLevelsAndLeavesOtherConditions() throws Exception {
		try (Engine engine = engine(tempDb("exhaustion-remove"))) {
			String campaign = committedCampaign(engine);
			engine.sessions().bootstrap(op(), campaign, null);
			String pc = "character:1";
			gain(engine, campaign, pc, 3);
			engine.runtime().applyRuntimeChange(op(), campaign, pc,
					map("kind", "ADD_CONDITION", "condition", "PRONE", "reason", "fallen"));
			Map<String, Object> cleared = engine.runtime().applyRuntimeChange(op(), campaign, pc,
					map("kind", "REMOVE_CONDITION", "condition", "EXHAUSTION", "reason", "GM recovery"));
			assertEquals(3, cleared.get("removed"));
			assertExhaustion(engine, campaign, pc, 0);
			assertEquals(List.of("PRONE"), list(sheet(engine, campaign, pc).get("conditions")).stream()
					.map(condition -> condition.get("condition")).toList());
			Map<String, Object> absent = engine.runtime().applyRuntimeChange(op(), campaign, pc,
					map("kind", "REMOVE_CONDITION", "condition", "EXHAUSTION", "reason", "already recovered"));
			assertEquals(0, absent.get("removed"));
			assertExhaustion(engine, campaign, pc, 0);
		}
	}

	@Test
	void legacyRowsAreReadWithoutMutationAndNormalizationCanBeRestoredAndReopened() throws Exception {
		Path db = tempDb("exhaustion-legacy-checkpoint");
		String campaign;
		Map<String, Object> originalCharacter;
		List<Map<String, Object>> originalEffects;
		try (Engine engine = engine(db)) {
			campaign = committedCampaign(engine);
			engine.sessions().bootstrap(op(), campaign, null);
			String pc = "character:1";
			legacyExhaustion(engine, campaign, pc, 0, 2);
			originalCharacter = characterRow(engine, pc);
			originalEffects = effectRows(engine, pc);
			long journals = journalCount(engine);
			assertExhaustionView(engine, campaign, pc, 1);
			assertExhaustionView(engine, campaign, pc, 1);
			assertEquals(originalCharacter, characterRow(engine, pc), "reading leaves the legacy column and revision");
			assertEquals(originalEffects, effectRows(engine, pc), "reading does not rewrite historical effects");
			assertEquals(journals, journalCount(engine), "reading adds no journal entries");

			String checkpoint = (String) engine.checkpoints().create(op(), campaign, "legacy exhaustion")
					.get("checkpoint");
			gain(engine, campaign, pc, 1);
			assertExhaustion(engine, campaign, pc, 2);
			assertTrue(effectRows(engine, pc).isEmpty(), "the journaled mutation folds legacy rows into the column");
			engine.checkpoints().restore(op(), campaign, checkpoint, "restore legacy exhaustion");
			assertEquals(originalCharacter, characterRow(engine, pc));
			assertEquals(originalEffects, effectRows(engine, pc));
			assertExhaustionView(engine, campaign, pc, 1);
		}
		try (Engine engine = engine(db)) {
			long journals = journalCount(engine);
			assertEquals(originalCharacter, characterRow(engine, "character:1"));
			assertEquals(originalEffects, effectRows(engine, "character:1"));
			assertExhaustionView(engine, campaign, "character:1", 1);
			assertEquals(originalCharacter, characterRow(engine, "character:1"));
			assertEquals(originalEffects, effectRows(engine, "character:1"));
			assertEquals(journals, journalCount(engine));
		}
	}

	@Test
	void legacyRowsDoNotAddToAnExistingStoredLevelAndLongRestRecoversOne() throws Exception {
		try (Engine engine = engine(tempDb("exhaustion-legacy-column"))) {
			String campaign = committedCampaign(engine);
			engine.sessions().bootstrap(op(), campaign, null);
			String pc = "character:1";
			legacyExhaustion(engine, campaign, pc, 3, 2);
			assertExhaustionView(engine, campaign, pc, 3);
			gain(engine, campaign, pc, 1);
			assertExhaustion(engine, campaign, pc, 4);
			assertTrue(effectRows(engine, pc).isEmpty());
			engine.rest().rest(op(), campaign, "LONG", null, List.of(pc));
			assertExhaustion(engine, campaign, pc, 3);

			legacyExhaustion(engine, campaign, pc, 0, 2);
			engine.sessions().advanceTime(op(), campaign, 16 * 60, "time between Long Rests");
			engine.rest().rest(op(), campaign, "LONG", null, List.of(pc));
			assertExhaustion(engine, campaign, pc, 0);
			assertTrue(effectRows(engine, pc).isEmpty(), "a Long Rest recovers the single legacy level");
		}
	}

	@Test
	void creatureAndEffectImmunityPreventExhaustionWithoutChangingTheCharacter() throws Exception {
		try (Engine engine = engine(tempDb("exhaustion-immunity"))) {
			String campaign = committedCampaign(engine);
			engine.sessions().bootstrap(op(), campaign, null);
			String golem = (String) engine.runtime()
					.materialize(op(), campaign, "Flesh Golem", null, null, null, null, null, false).get("character");
			Map<String, Object> before = characterRow(engine, golem);
			gain(engine, campaign, golem, 1);
			assertExhaustion(engine, campaign, golem, 0);
			assertEquals(before, characterRow(engine, golem));

			String pc = "character:1";
			engine.db().mutate(
					Database.Mutation.of("test_exhaustion_immunity", Ref.id(campaign, Ref.CAMPAIGN), op(), "GM", null),
					tx -> {
						Effects.add(tx, Ref.id(campaign, Ref.CAMPAIGN), 1L, null, null, "Enduring blessing", null,
								map("immune_conditions", List.of("EXHAUSTION")), Map.of("until", "LONG_REST"), null,
								null, "GM");
						return Map.of("ok", true);
					});
			before = characterRow(engine, pc);
			gain(engine, campaign, pc, 1);
			assertExhaustion(engine, campaign, pc, 0);
			assertEquals(before, characterRow(engine, pc));
			engine.rest().rest(op(), campaign, "LONG", null, List.of(pc));
			gain(engine, campaign, pc, 1);
			assertExhaustion(engine, campaign, pc, 1);
		}
	}

	@Test
	void theSixthLevelKillsAndAppliesNormalDeathConsequences() throws Exception {
		try (Engine engine = engine(tempDb("exhaustion-death"))) {
			String campaign = committedCampaign(engine);
			engine.sessions().bootstrap(op(), campaign, null);
			String pc = "character:1";
			String ogre = (String) engine.runtime()
					.materialize(op(), campaign, "Ogre", null, null, null, null, null, false).get("character");
			String encounter = (String) engine.encounters().start(op(), campaign,
					map("party", List.of(pc), "enemies", List.of(ogre)), null, null, null, null, null).get("encounter");
			engine.runtime().applyRuntimeChange(op(), campaign, pc,
					map("kind", "ADD_CONDITION", "condition", "PRONE", "reason", "fallen"));
			engine.db().mutate(Database.Mutation.of("test_exhaustion_concentration", Ref.id(campaign, Ref.CAMPAIGN),
					op(), "GM", null), tx -> {
						Effects.add(tx, Ref.id(campaign, Ref.CAMPAIGN), Ref.id(ogre, Ref.CHARACTER), 1L, null,
								"Concentrated effect", null, Map.of(), Map.of("until", "LONG_REST"), 1L, null, "GM");
						return Map.of("ok", true);
					});
			gain(engine, campaign, pc, 5);
			assertEquals("ALIVE", sheet(engine, campaign, pc).get("life_state"));
			Map<String, Object> died = gain(engine, campaign, pc, 1);
			assertEquals(Boolean.TRUE, died.get("died"));
			assertEquals("DEAD", died.get("life_state"));
			assertExhaustion(engine, campaign, pc, 6);
			assertEquals(0, m(sheet(engine, campaign, pc).get("hp")).get("current"));
			assertNull(characterRow(engine, pc).get("death_saves_json"));
			assertEquals(List.of("EXHAUSTION"), list(sheet(engine, campaign, pc).get("conditions")).stream()
					.map(condition -> condition.get("condition")).toList());
			assertTrue(effectRows(engine, ogre).isEmpty(), "death breaks effects sustained on another creature");
			assertEquals("DEAD",
					engine.db().read(tx -> tx
							.queryOne("SELECT state FROM party_membership WHERE character_id = ? ORDER BY id DESC", 1L)
							.orElseThrow().str("state")));
			assertEquals("DEFEATED",
					engine.db().read(tx -> tx.queryOne(
							"SELECT status FROM encounter_participant WHERE encounter_id = ? AND character_id = ?",
							Ref.id(encounter, Ref.ENCOUNTER), 1L).orElseThrow().str("status")));
			gain(engine, campaign, pc, 1);
			assertExhaustion(engine, campaign, pc, 6);
			assertEquals(1,
					list(engine.ledger()
							.queryTimeline(campaign, null, null, null, List.of("CHARACTER_DIED"), null, true, 10)
							.get("events")).size());
		}
	}

	@Test
	void ordinaryDeathPreservesLegacyExhaustionWhenEffectsAreCleared() throws Exception {
		try (Engine engine = engine(tempDb("exhaustion-legacy-death"))) {
			String campaign = committedCampaign(engine);
			engine.sessions().bootstrap(op(), campaign, null);
			String bandit = (String) engine.runtime()
					.materialize(op(), campaign, "Bandit", null, null, null, null, null, false).get("character");
			legacyExhaustion(engine, campaign, bandit, 0, 1);
			engine.runtime().applyRuntimeChange(op(), campaign, bandit,
					map("kind", "DAMAGE", "amount", 30, "reason", "a fatal fall"));
			assertEquals("DEAD", sheet(engine, campaign, bandit).get("life_state"));
			assertExhaustion(engine, campaign, bandit, 1);
			assertTrue(effectRows(engine, bandit).isEmpty());
			legacyExhaustion(engine, campaign, bandit, 0, 1);
			engine.rest().override(op(), campaign, "SET_LIFE_STATE", bandit, map("life_state", "ALIVE", "hp", 1),
					"revival and recovery by fiat", null);
			assertEquals("ALIVE", sheet(engine, campaign, bandit).get("life_state"));
			assertExhaustion(engine, campaign, bandit, 0);
		}
	}

	@Test
	void greaterRestorationRemovesOneLevel() throws Exception {
		try (Engine engine = engine(tempDb("exhaustion-greater-restoration"))) {
			String campaign = clericCampaign(engine, 9);
			String pc = "character:1";
			engine.spells().prepare(op(), campaign, pc, null, List.of("Greater Restoration"));
			gain(engine, campaign, pc, 3);
			Map<String, Object> cast = engine.spells().cast(op(), campaign, pc, "Greater Restoration", null,
					List.of(pc), map("condition", "EXHAUSTION"));
			assertEquals(List.of("EXHAUSTION"), list(cast.get("targets")).get(0).get("removed_conditions"));
			assertEquals(2, list(cast.get("targets")).get(0).get("exhaustion"));
			assertExhaustion(engine, campaign, pc, 2);
		}
	}

	@Test
	void revivifyReducesFatalExhaustionByOneAndReturnsTheCreatureToLife() throws Exception {
		try (Engine engine = engine(tempDb("exhaustion-revivify"))) {
			String campaign = clericCampaign(engine, 5);
			engine.spells().prepare(op(), campaign, "character:1", null, List.of("Revivify"));
			String bandit = (String) engine.runtime()
					.materialize(op(), campaign, "Bandit", null, null, null, null, null, false).get("character");
			gain(engine, campaign, bandit, 6);
			assertEquals("DEAD", sheet(engine, campaign, bandit).get("life_state"));
			assertExhaustion(engine, campaign, bandit, 6);
			Map<String, Object> cast = engine.spells().cast(op(), campaign, "character:1", "Revivify", null,
					List.of(bandit), null);
			assertEquals(Boolean.TRUE, list(cast.get("targets")).get(0).get("revived"));
			assertEquals(5, list(cast.get("targets")).get(0).get("exhaustion"));
			assertEquals("ALIVE", sheet(engine, campaign, bandit).get("life_state"));
			assertEquals(1, m(sheet(engine, campaign, bandit).get("hp")).get("current"));
			assertExhaustion(engine, campaign, bandit, 5);
		}
	}

	private static Map<String, Object> gain(Engine engine, String campaign, String pc, int levels) {
		Map<String, Object> result = Map.of();
		for (int level = 0; level < levels; level++) {
			result = engine.runtime().applyRuntimeChange(op(), campaign, pc,
					map("kind", "ADD_CONDITION", "condition", "EXHAUSTION", "reason", "forced march"));
		}
		return result;
	}

	private static void assertExhaustion(Engine engine, String campaign, String pc, int level) {
		assertEquals(level, ((Number) characterRow(engine, pc).get("exhaustion")).intValue());
		assertExhaustionView(engine, campaign, pc, level);
	}

	private static void assertExhaustionView(Engine engine, String campaign, String pc, int level) {
		Map<String, Object> sheet = sheet(engine, campaign, pc);
		assertEquals(level, sheet.get("exhaustion"));
		List<Map<String, Object>> conditions = list(sheet.get("conditions")).stream()
				.filter(condition -> "EXHAUSTION".equals(condition.get("condition"))).toList();
		assertEquals(level == 0 ? List.of()
				: List.of(map("condition", "EXHAUSTION", "level", level, "source", "Exhaustion")), conditions);
	}

	private static void legacyExhaustion(Engine engine, String campaign, String pc, int columnLevel, int rows) {
		long campaignId = Ref.id(campaign, Ref.CAMPAIGN);
		long characterId = Ref.id(pc, Ref.CHARACTER);
		engine.db().mutate(Database.Mutation.of("test_legacy_exhaustion", campaignId, op(), "GM", null), tx -> {
			tx.update("character", characterId, Map.of("exhaustion", columnLevel));
			for (int row = 0; row < rows; row++) {
				tx.insert("active_effect", map("campaign_id", campaignId, "character_id", characterId,
						"source_description", "Legacy forced march", "provenance", "GM", "condition_ref",
						RuntimeService.conditionRef("EXHAUSTION"), "start_seq", 0L, "start_journal_id", tx.journalId(),
						"duration_json", "{\"until\":\"LONG_REST\"}", "stacking_key", "EXHAUSTION"));
			}
			return Map.of("ok", true);
		});
	}

	private static String clericCampaign(Engine engine, int level) {
		String campaign = (String) engine.campaigns().create(op(), null, null).get("campaign");
		engine.campaigns().updateSetup(op(), campaign, null, map("content_profile", "PEGI_16", "experience",
				"SURPRISE_ME", "rules", Map.of(), "continuation", "CHECKPOINT", "party", "SURPRISE_ME", "adventure",
				map("premise", "a rescue", "opening_location", "Temple", "immediate_goal", "save the exhausted")));
		String cleric = (String) engine.characters()
				.createDraft(op(), campaign,
						map("name", "Healer", "species", "Human", "class", "Cleric", "ability_scores",
								map("WIS", 15, "CON", 14, "STR", 13, "DEX", 12, "CHA", 10, "INT", 8), "skills",
								List.of("Insight", "Religion"), "personality", "kind", "background", "Criminal",
								"background_ability_scores", map("CON", 2, "DEX", 1), "species_skill",
								"Animal Handling", "origin_feat",
								map("feat", "Skilled", "proficiencies", List.of("Nature", "Survival", "Medicine")),
								"cantrips", List.of("Sacred Flame", "Guidance", "Spare the Dying"), "spells",
								List.of("Bless", "Detect Magic", "Cure Wounds", "Guiding Bolt")),
						true)
				.get("character");
		engine.characters().commitDraft(op(), campaign, cleric, null);
		engine.campaigns().commitSetup(op(), campaign, null);
		engine.sessions().bootstrap(op(), campaign, null);
		engine.runtime().awardXp(op(), campaign, List.of(cleric), engine.rules().xpThreshold(level), "MILESTONE",
				"test");
		for (int next = 2; next <= level; next++) {
			String transaction = (String) m(engine.levelUps().begin(op(), campaign, cleric).get("transaction"))
					.get("ref");
			if (next == 4 || next == 8) {
				engine.levelUps().update(op(), campaign, transaction, null,
						map("ability_score_improvement", map("WIS", 2)));
			}
			engine.levelUps().commit(op(), campaign, transaction, null);
		}
		return campaign;
	}

	private static Map<String, Object> characterRow(Engine engine, String pc) {
		return engine.db().read(tx -> tx.get("character", Ref.id(pc, Ref.CHARACTER)).asMap());
	}

	private static Map<String, Object> sheet(Engine engine, String campaign, String pc) {
		return engine.characters().characterSheet(campaign, pc, "PLAY");
	}

	private static List<Map<String, Object>> effectRows(Engine engine, String pc) {
		return engine.db().read(tx -> tx
				.query("SELECT * FROM active_effect WHERE character_id = ? ORDER BY id", Ref.id(pc, Ref.CHARACTER))
				.stream().map(row -> row.asMap()).toList());
	}

	private static long journalCount(Engine engine) {
		return engine.db().read(tx -> tx.count("SELECT COUNT(*) FROM journal_entry"));
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
