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
import se.hirt.mcp.rpg.dice.ScriptedRollService;
import se.hirt.mcp.rpg.protocol.Ref;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static se.hirt.mcp.rpg.TestCampaigns.map;
import static se.hirt.mcp.rpg.TestCampaigns.op;

/** Ordinary healing cannot restore hit points to a dead creature. */
class DeadHealingTest {

	@Test
	void ordinaryHealingLeavesADeadCreatureUnchanged() throws Exception {
		try (Engine engine = TestCampaigns.engine(TestCampaigns.tempDb("dead-healing"))) {
			String campaign = TestCampaigns.committedCampaign(engine);
			engine.sessions().bootstrap(op(), campaign, null);
			String bandit = (String) engine.runtime()
					.materialize(op(), campaign, "Bandit", null, null, null, null, null, false).get("character");
			engine.runtime().applyRuntimeChange(op(), campaign, bandit,
					map("kind", "DAMAGE", "amount", 30, "reason", "a fatal fall"));
			Map<String, Object> before = engine.characters().characterSheet(campaign, bandit, "PLAY");
			Map<String, Object> rowBefore = characterRow(engine, bandit);
			String healingOperation = op();
			Map<String, Object> healed = engine.runtime().applyRuntimeChange(healingOperation, campaign, bandit,
					map("kind", "HEAL", "amount", 5, "reason", "ordinary healing"));
			Map<String, Object> after = engine.characters().characterSheet(campaign, bandit, "PLAY");
			assertEquals("DEAD", before.get("life_state"));
			assertEquals("DEAD", after.get("life_state"));
			assertEquals(0, m(after.get("hp")).get("current"));
			assertEquals(0, healed.get("healed"));
			assertEquals(0, healed.get("hp_before"));
			assertEquals(0, healed.get("hp_after"));
			assertFalse(healed.containsKey("revived"));
			assertEquals("already dead", healed.get("skipped"));
			assertEquals(rowBefore, characterRow(engine, bandit),
					"healing preserves the dead character's revision and death-save state");
			Map<String, Object> replayed = engine.runtime().applyRuntimeChange(healingOperation, campaign, bandit,
					map("kind", "HEAL", "amount", 5, "reason", "ordinary healing"));
			assertEquals(Boolean.TRUE, replayed.get("replayed"));
			assertEquals(0, replayed.get("healed"));
			assertEquals(rowBefore, characterRow(engine, bandit));
		}
	}

	@Test
	void aHealingPotionIsSpentWithoutRestoringADeadTarget() throws Exception {
		try (Engine engine = TestCampaigns.engine(TestCampaigns.tempDb("dead-healing-potion"))) {
			String campaign = TestCampaigns.committedCampaign(engine);
			engine.sessions().bootstrap(op(), campaign, null);
			String bandit = deadBandit(engine, campaign);
			Map<String, Object> rowBefore = characterRow(engine, bandit);
			engine.inventory().grantLoot(op(), campaign, "character:1", List.of(map("item", "Potion of Healing")), null,
					"QUEST", null);
			ScriptedRollService dice = (ScriptedRollService) engine.roller();
			dice.queue(4, 4);
			Map<String, Object> used = engine.runtime().applyRuntimeChange(op(), campaign, "character:1",
					map("kind", "USE_ITEM", "item", "Potion of Healing", "target", bandit));
			assertEquals(Boolean.TRUE, used.get("consumed"));
			assertEquals(bandit, used.get("target"));
			assertEquals(10, m(used.get("roll")).get("total"));
			assertEquals(0, used.get("healed"));
			assertEquals("already dead", used.get("skipped"));
			assertEquals(rowBefore, characterRow(engine, bandit));
			assertTrue(list(engine.characters().characterSheet(campaign, "character:1", "PLAY").get("inventory"))
					.stream().noneMatch(item -> "Potion of Healing".equals(item.get("name"))));
			assertTrue(dice.exhausted());
		}
	}

	@Test
	void aHealingSpellSpendsItsSlotWithoutRestoringADeadTarget() throws Exception {
		try (Engine engine = TestCampaigns.engine(TestCampaigns.tempDb("dead-healing-spell"))) {
			String campaign = clericCampaign(engine, 1);
			String bandit = deadBandit(engine, campaign);
			Map<String, Object> rowBefore = characterRow(engine, bandit);
			int slotsBefore = slots(engine, campaign, 1);
			ScriptedRollService dice = (ScriptedRollService) engine.roller();
			dice.queue(4, 4);
			Map<String, Object> cast = engine.spells().cast(op(), campaign, "character:1", "Cure Wounds", null,
					List.of(bandit), null);
			Map<String, Object> target = list(cast.get("targets")).get(0);
			assertEquals(0, target.get("healed"));
			assertEquals("already dead", target.get("skipped"));
			assertEquals(rowBefore, characterRow(engine, bandit));
			assertEquals(slotsBefore - 1, slots(engine, campaign, 1));
			assertTrue(dice.exhausted());
		}
	}

	@Test
	void healDoesNotCureConditionsOnADeadTarget() throws Exception {
		try (Engine engine = TestCampaigns.engine(TestCampaigns.tempDb("dead-heal-conditions"))) {
			String campaign = clericCampaign(engine, 11);
			engine.spells().prepare(op(), campaign, "character:1", null, List.of("Heal"));
			String bandit = deadBandit(engine, campaign);
			// Death normally clears effects; these post-death effects distinguish Heal's cure branch.
			for (String condition : List.of("BLINDED", "DEAFENED", "POISONED")) {
				engine.runtime().applyRuntimeChange(op(), campaign, bandit, map("kind", "ADD_CONDITION", "condition",
						condition, "duration", "long rest", "reason", "condition on the remains"));
			}
			Map<String, Object> rowBefore = characterRow(engine, bandit);
			Object conditionsBefore = engine.characters().characterSheet(campaign, bandit, "PLAY").get("conditions");
			Map<String, Object> cast = engine.spells().cast(op(), campaign, "character:1", "Heal", null,
					List.of(bandit), null);
			assertEquals(0, list(cast.get("targets")).get(0).get("healed"));
			assertEquals(rowBefore, characterRow(engine, bandit));
			assertEquals(conditionsBefore,
					engine.characters().characterSheet(campaign, bandit, "PLAY").get("conditions"));
			assertEquals(0, slots(engine, campaign, 6));

			// On a living target the same casting restores hit points and "ends the Blinded, Deafened, and Poisoned
			// conditions" (SRD 5.2.1 "Heal"). A Long Rest first brings the level-6 slot back.
			engine.rest().rest(op(), campaign, "LONG", null, null);
			assertEquals(1, slots(engine, campaign, 6));
			String living = (String) engine.runtime()
					.materialize(op(), campaign, "Bandit", "Wounded", null, null, null, null, false).get("character");
			engine.runtime().applyRuntimeChange(op(), campaign, living,
					map("kind", "DAMAGE", "amount", 5, "reason", "a cut"));
			for (String condition : List.of("BLINDED", "DEAFENED", "POISONED")) {
				engine.runtime().applyRuntimeChange(op(), campaign, living, map("kind", "ADD_CONDITION", "condition",
						condition, "duration", "long rest", "reason", "afflicted"));
			}
			assertTrue(conditionNames(engine.characters().characterSheet(campaign, living, "PLAY"))
					.containsAll(List.of("BLINDED", "DEAFENED", "POISONED")));
			int hpBefore = ((Number) characterRow(engine, living).get("current_hp")).intValue();
			Map<String, Object> cured = engine.spells().cast(op(), campaign, "character:1", "Heal", null,
					List.of(living), null);
			assertEquals(5, list(cured.get("targets")).get(0).get("healed"), "healed up to the maximum");
			assertEquals(hpBefore + 5, ((Number) characterRow(engine, living).get("current_hp")).intValue());
			List<String> conditions = conditionNames(engine.characters().characterSheet(campaign, living, "PLAY"));
			assertFalse(conditions.contains("BLINDED"), conditions.toString());
			assertFalse(conditions.contains("DEAFENED"), conditions.toString());
			assertFalse(conditions.contains("POISONED"), "Heal also ends Poisoned: " + conditions);
			assertEquals(0, slots(engine, campaign, 6));
		}
	}

	@Test
	void massHealingStillRestoresLivingTargetsWhenADeadTargetComesFirst() throws Exception {
		try (Engine engine = TestCampaigns.engine(TestCampaigns.tempDb("mixed-healing-targets"))) {
			String campaign = clericCampaign(engine, 5);
			engine.spells().prepare(op(), campaign, "character:1", null, List.of("Mass Healing Word"));
			String dead = deadBandit(engine, campaign);
			Map<String, Object> deadBefore = characterRow(engine, dead);
			String living = (String) engine.runtime()
					.materialize(op(), campaign, "Bandit", null, null, null, null, null, false).get("character");
			engine.runtime().applyRuntimeChange(op(), campaign, living,
					map("kind", "DAMAGE", "amount", 7, "reason", "injured"));
			int hpBefore = ((Number) characterRow(engine, living).get("current_hp")).intValue();
			int slotsBefore = slots(engine, campaign, 3);
			ScriptedRollService dice = (ScriptedRollService) engine.roller();
			dice.queue(1, 1, 1, 1);
			Map<String, Object> cast = engine.spells().cast(op(), campaign, "character:1", "Mass Healing Word", null,
					List.of(dead, living), null);
			List<Map<String, Object>> targets = list(cast.get("targets"));
			assertEquals(0, targets.get(0).get("healed"));
			assertEquals("already dead", targets.get(0).get("skipped"));
			assertEquals(5, targets.get(1).get("healed"), "2d4 (1 + 1) + Wisdom 3");
			assertEquals(hpBefore + 5, ((Number) characterRow(engine, living).get("current_hp")).intValue());
			assertEquals("ALIVE", characterRow(engine, living).get("life_state"));
			assertEquals(deadBefore, characterRow(engine, dead));
			assertEquals(slotsBefore - 1, slots(engine, campaign, 3));
		}
	}

	@Test
	void ordinaryHealingRevivesAStableDyingCharacterAndClearsDeathSaves() throws Exception {
		try (Engine engine = TestCampaigns.engine(TestCampaigns.tempDb("dying-healing"))) {
			String campaign = TestCampaigns.committedCampaign(engine);
			engine.sessions().bootstrap(op(), campaign, null);
			int max = ((Number) characterRow(engine, "character:1").get("max_hp")).intValue();
			engine.runtime().applyRuntimeChange(op(), campaign, "character:1",
					map("kind", "DAMAGE", "amount", max, "reason", "knocked out"));
			engine.runtime().applyRuntimeChange(op(), campaign, "character:1",
					map("kind", "STABILIZE", "reason", "aid"));
			Map<String, Object> before = engine.characters().characterSheet(campaign, "character:1", "PLAY");
			assertEquals("DYING", before.get("life_state"));
			assertEquals(Boolean.TRUE, m(before.get("death_saves")).get("stable"));
			assertTrue(conditionNames(before).contains("UNCONSCIOUS"));
			Map<String, Object> healed = engine.runtime().applyRuntimeChange(op(), campaign, "character:1",
					map("kind", "HEAL", "amount", 3, "reason", "aid"));
			Map<String, Object> after = engine.characters().characterSheet(campaign, "character:1", "PLAY");
			assertEquals(3, healed.get("healed"));
			assertEquals(Boolean.TRUE, healed.get("revived"));
			assertEquals("ALIVE", after.get("life_state"));
			assertEquals(3, m(after.get("hp")).get("current"));
			assertNull(characterRow(engine, "character:1").get("death_saves_json"));
			// "When this condition ends, you remain Prone" (SRD 5.2.1 "Unconscious"): nobody added it by hand.
			assertEquals(List.of("PRONE"), conditionNames(after));
		}
	}

	@Test
	void ordinaryHealingStillStopsAtTheLivingTargetsMaximum() throws Exception {
		try (Engine engine = TestCampaigns.engine(TestCampaigns.tempDb("living-healing-cap"))) {
			String campaign = TestCampaigns.committedCampaign(engine);
			engine.sessions().bootstrap(op(), campaign, null);
			engine.runtime().applyRuntimeChange(op(), campaign, "character:1",
					map("kind", "DAMAGE", "amount", 3, "reason", "scratched"));
			Map<String, Object> healed = engine.runtime().applyRuntimeChange(op(), campaign, "character:1",
					map("kind", "HEAL", "amount", 100, "reason", "healed"));
			assertEquals(3, healed.get("healed"));
			Map<String, Object> row = characterRow(engine, "character:1");
			assertEquals(row.get("max_hp"), row.get("current_hp"));
			assertEquals("ALIVE", row.get("life_state"));
		}
	}

	@Test
	void resurrectionStillReturnsADeadCreatureToLifeBeforeOrdinaryHealing() throws Exception {
		try (Engine engine = TestCampaigns.engine(TestCampaigns.tempDb("resurrection-healing"))) {
			String campaign = clericCampaign(engine, 5);
			engine.spells().prepare(op(), campaign, "character:1", null, List.of("Revivify"));
			String bandit = deadBandit(engine, campaign);
			Map<String, Object> cast = engine.spells().cast(op(), campaign, "character:1", "Revivify", null,
					List.of(bandit), null);
			assertEquals(Boolean.TRUE, list(cast.get("targets")).get(0).get("revived"));
			assertEquals("ALIVE", characterRow(engine, bandit).get("life_state"));
			assertEquals(1, ((Number) characterRow(engine, bandit).get("current_hp")).intValue());
			Map<String, Object> healed = engine.runtime().applyRuntimeChange(op(), campaign, bandit,
					map("kind", "HEAL", "amount", 5, "reason", "after revival"));
			assertEquals(5, healed.get("healed"));
			assertEquals(6, ((Number) characterRow(engine, bandit).get("current_hp")).intValue());
			assertEquals("ALIVE", characterRow(engine, bandit).get("life_state"));
		}
	}

	private static String deadBandit(Engine engine, String campaign) {
		String bandit = (String) engine.runtime()
				.materialize(op(), campaign, "Bandit", null, null, null, null, null, false).get("character");
		engine.runtime().applyRuntimeChange(op(), campaign, bandit,
				map("kind", "DAMAGE", "amount", 30, "reason", "a fatal fall"));
		assertEquals("DEAD", characterRow(engine, bandit).get("life_state"));
		return bandit;
	}

	private static String clericCampaign(Engine engine, int level) {
		String campaign = (String) engine.campaigns().create(op(), null, null).get("campaign");
		engine.campaigns().updateSetup(op(), campaign, null, map("content_profile", "PEGI_16", "experience",
				"SURPRISE_ME", "rules", Map.of(), "continuation", "CHECKPOINT", "party", "SURPRISE_ME", "adventure",
				map("premise", "a rescue", "opening_location", "Temple", "immediate_goal", "save the injured")));
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
		if (level > 1) {
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
		}
		return campaign;
	}

	private static Map<String, Object> characterRow(Engine engine, String character) {
		return engine.db().read(tx -> tx.get("character", Ref.id(character, Ref.CHARACTER)).asMap());
	}

	private static int slots(Engine engine, String campaign, int level) {
		return ((Number) m(m(
				m(engine.characters().characterSheet(campaign, "character:1", "PLAY").get("spellcasting")).get("slots"))
				.get(Integer.toString(level))).get("current")).intValue();
	}

	private static List<String> conditionNames(Map<String, Object> sheet) {
		return list(sheet.get("conditions")).stream().map(condition -> condition.get("condition").toString()).toList();
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
