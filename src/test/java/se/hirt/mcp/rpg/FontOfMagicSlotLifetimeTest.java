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
import se.hirt.mcp.rpg.protocol.Ref;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static se.hirt.mcp.rpg.TestCampaigns.committedCampaign;
import static se.hirt.mcp.rpg.TestCampaigns.engine;
import static se.hirt.mcp.rpg.TestCampaigns.map;
import static se.hirt.mcp.rpg.TestCampaigns.op;
import static se.hirt.mcp.rpg.TestCampaigns.tempDb;

class FontOfMagicSlotLifetimeTest {

	@Test
	void createdSlotSurvivesLevelUpAlongsideNewClassSlotsAndSpentSlots() throws Exception {
		try (Engine engine = engine(tempDb("font-slot-level-up"))) {
			String campaign = committedCampaign(engine);
			String pc = "character:1";
			engine.sessions().bootstrap(op(), campaign, null);
			engine.runtime().awardXp(op(), campaign, List.of(pc), 300, "MILESTONE", "reach Font of Magic");
			levelUp(engine, campaign, pc);
			engine.runtime().applyRuntimeChange(op(), campaign, pc, map("kind", "CREATE_SPELL_SLOT", "slot_level", 1,
					"reason", "an extra slot until the next Long Rest"));
			engine.runtime().applyRuntimeChange(op(), campaign, pc,
					map("kind", "CONVERT_SPELL_SLOT", "slot_level", 1, "reason", "spend one slot before levelling"));
			Map<String, Object> beforeLevelUp = slot(engine, campaign, pc, 1);
			assertEquals(4, beforeLevelUp.get("max"));
			assertEquals(3, beforeLevelUp.get("current"));

			engine.runtime().awardXp(op(), campaign, List.of(pc), 600, "MILESTONE", "reach the next level");
			Map<String, Object> committed = levelUp(engine, campaign, pc);
			Map<String, Object> afterLevelUp = slot(engine, campaign, pc, 1);
			assertEquals(5, afterLevelUp.get("max"), "four class slots plus the created slot");
			assertEquals(4, afterLevelUp.get("current"),
					"one spent slot stays spent while one new class slot is gained");
			assertSlot(engine, campaign, pc, 2, 2, 2);
			assertEquals(3, committed.get("level"));
		}
	}

	@Test
	void twoCreatedSlotsSurviveAnUnchangedClassPoolWhileOtherPoolsGrow() throws Exception {
		try (Engine engine = engine(tempDb("font-slot-unchanged-base"))) {
			String campaign = sorcererAt(engine, 3);
			String pc = "character:1";
			font(engine, campaign, pc, "CREATE_SPELL_SLOT", 1);
			font(engine, campaign, pc, "CONVERT_SPELL_SLOT", 2);
			font(engine, campaign, pc, "CREATE_SPELL_SLOT", 1);
			font(engine, campaign, pc, "CONVERT_SPELL_SLOT", 1);
			assertSlot(engine, campaign, pc, 1, 5, 6);
			assertSlot(engine, campaign, pc, 2, 1, 2);

			advanceTo(engine, campaign, pc, 4);

			assertSlot(engine, campaign, pc, 1, 5, 6);
			assertSlot(engine, campaign, pc, 2, 2, 3);
		}
	}

	@Test
	void shortRestPreservesSpentAndCreatedSlotsAndLongRestRemovesEveryCreatedTier() throws Exception {
		try (Engine engine = engine(tempDb("font-slot-rest-lifetime"))) {
			String campaign = sorcererAt(engine, 3);
			String pc = "character:1";
			font(engine, campaign, pc, "CREATE_SPELL_SLOT", 1);
			font(engine, campaign, pc, "CONVERT_SPELL_SLOT", 1);
			font(engine, campaign, pc, "CONVERT_SPELL_SLOT", 1);
			font(engine, campaign, pc, "CREATE_SPELL_SLOT", 2);
			advanceTo(engine, campaign, pc, 4);
			assertSlot(engine, campaign, pc, 1, 3, 5);
			assertSlot(engine, campaign, pc, 2, 4, 4);

			engine.rest().rest(op(), campaign, "SHORT", null, null);
			assertSlot(engine, campaign, pc, 1, 3, 5);
			assertSlot(engine, campaign, pc, 2, 4, 4);
			engine.rest().rest(op(), campaign, "LONG", null, null);
			assertSlot(engine, campaign, pc, 1, 4, 4);
			assertSlot(engine, campaign, pc, 2, 3, 3);

			advanceTo(engine, campaign, pc, 5);
			assertSlot(engine, campaign, pc, 1, 4, 4);
			assertSlot(engine, campaign, pc, 2, 3, 3);
			assertSlot(engine, campaign, pc, 3, 2, 2);
		}
	}

	@Test
	void unspentCreatedSlotsRemainAvailableAfterIncreasingAndUnchangedBasePools() throws Exception {
		try (Engine engine = engine(tempDb("font-slot-unspent"))) {
			String campaign = sorcererAt(engine, 2);
			String pc = "character:1";
			font(engine, campaign, pc, "CREATE_SPELL_SLOT", 1);
			assertSlot(engine, campaign, pc, 1, 4, 4);
			advanceTo(engine, campaign, pc, 3);
			assertSlot(engine, campaign, pc, 1, 5, 5);
			advanceTo(engine, campaign, pc, 4);
			assertSlot(engine, campaign, pc, 1, 5, 5);
		}
	}

	@Test
	void automaticCompanionLevelUpsPreserveCreatedSlotsAndPriorSpending() throws Exception {
		try (Engine engine = engine(tempDb("font-slot-companion"))) {
			String campaign = campaign(engine);
			String companion = promote(engine, campaign, "Sorcerer", List.of("Arcana", "Persuasion"));
			advanceTo(engine, campaign, companion, 2);
			font(engine, campaign, companion, "CREATE_SPELL_SLOT", 1);
			font(engine, campaign, companion, "CONVERT_SPELL_SLOT", 1);
			assertSlot(engine, campaign, companion, 1, 3, 4);
			engine.rest().override(op(), campaign, "SET_CAMPAIGN_RULE", null,
					map("rule", "companion_level_up", "value", "ENGINE"), "let the engine advance companions", null);

			Map<String, Object> award = engine.runtime().awardXp(op(), campaign, List.of(companion), 600, "MILESTONE",
					"the companion reaches level three");

			List<Map<String, Object>> levelUps = list(award.get("companion_level_ups"));
			assertEquals(1, levelUps.size());
			assertEquals(companion, levelUps.getFirst().get("character"));
			assertEquals(3, levelUps.getFirst().get("level"));
			assertSlot(engine, campaign, companion, 1, 4, 5);
			assertSlot(engine, campaign, companion, 2, 2, 2);
		}
	}

	@Test
	void ordinaryCasterLevelUpsGrantOnlyNewSlotsAndPreservePriorSpending() throws Exception {
		try (Engine engine = engine(tempDb("font-slot-ordinary-caster"))) {
			String campaign = campaign(engine);
			String wizard = promote(engine, campaign, "Wizard", List.of("Arcana", "History"));
			useResource(engine, campaign, wizard, "spell_slot:1", 1);
			assertSlot(engine, campaign, wizard, 1, 1, 2);
			advanceTo(engine, campaign, wizard, 2);
			assertSlot(engine, campaign, wizard, 1, 2, 3);
			advanceTo(engine, campaign, wizard, 3);
			assertSlot(engine, campaign, wizard, 1, 3, 4);
			assertSlot(engine, campaign, wizard, 2, 2, 2);
			engine.rest().rest(op(), campaign, "SHORT", null, null);
			assertSlot(engine, campaign, wizard, 1, 3, 4);
			engine.rest().rest(op(), campaign, "LONG", null, null);
			assertSlot(engine, campaign, wizard, 1, 4, 4);
		}
	}

	@Test
	void pactSlotProgressionAndShortRestRechargeRemainUnchanged() throws Exception {
		try (Engine engine = engine(tempDb("font-slot-pact-regression"))) {
			String campaign = campaign(engine);
			String warlock = promote(engine, campaign, "Warlock", List.of("Arcana", "Deception"));
			useResource(engine, campaign, warlock, "pact_slot", 1);
			assertEquals(map("current", 0, "max", 1, "level", 1), pactSlots(engine, campaign, warlock));
			advanceTo(engine, campaign, warlock, 2);
			assertEquals(map("current", 1, "max", 2, "level", 1), pactSlots(engine, campaign, warlock));
			useResource(engine, campaign, warlock, "pact_slot", 1);
			advanceTo(engine, campaign, warlock, 3);
			assertEquals(map("current", 0, "max", 2, "level", 2), pactSlots(engine, campaign, warlock));
			engine.rest().rest(op(), campaign, "SHORT", null, null);
			assertEquals(map("current", 2, "max", 2, "level", 2), pactSlots(engine, campaign, warlock));
		}
	}

	@Test
	void markerFreeStoredSlotsSurviveReopenReplayAndCheckpointRestore() throws Exception {
		Path database = tempDb("font-slot-legacy-reopen");
		String campaign;
		String checkpoint;
		Map<String, List<Map<String, Object>>> beforeLevelUp;
		try (Engine engine = engine(database)) {
			campaign = sorcererAt(engine, 2);
			String pc = "character:1";
			String creationId = op();
			Map<String, Object> creation = map("kind", "CREATE_SPELL_SLOT", "slot_level", 1, "reason",
					"a saved extra slot");
			engine.runtime().applyRuntimeChange(creationId, campaign, pc, creation);
			font(engine, campaign, pc, "CONVERT_SPELL_SLOT", 1);
			Map<String, List<Map<String, Object>>> beforeReplay = snapshot(engine, campaign);
			assertEquals(true, engine.runtime().applyRuntimeChange(creationId, campaign, pc, creation).get("replayed"));
			assertEquals(beforeReplay, snapshot(engine, campaign));
			assertSlot(engine, campaign, pc, 1, 3, 4);
			checkpoint = (String) engine.checkpoints().create(op(), campaign, "save the extra slot before levelling")
					.get("checkpoint");
			beforeLevelUp = snapshot(engine, campaign);
		}
		try (Engine reopened = engine(database)) {
			reopened.sessions().bootstrap(op(), campaign, null);
			String pc = "character:1";
			assertSlot(reopened, campaign, pc, 1, 3, 4);
			reopened.runtime().awardXp(op(), campaign, List.of(pc), 600, "MILESTONE", "level up after reopening");
			String transaction = (String) m(reopened.levelUps().begin(op(), campaign, pc).get("transaction"))
					.get("ref");
			String commitId = op();
			reopened.levelUps().commit(commitId, campaign, transaction, null);
			assertSlot(reopened, campaign, pc, 1, 4, 5);
			Map<String, List<Map<String, Object>>> after = snapshot(reopened, campaign);
			assertEquals(true, reopened.levelUps().commit(commitId, campaign, transaction, null).get("replayed"));
			assertEquals(after, snapshot(reopened, campaign));

			reopened.checkpoints().restore(op(), campaign, checkpoint, "return to the old slots and class level");
			assertEquals(beforeLevelUp, snapshot(reopened, campaign));
			assertSlot(reopened, campaign, pc, 1, 3, 4);
		}
		try (Engine reopened = engine(database)) {
			reopened.sessions().bootstrap(op(), campaign, null);
			String pc = "character:1";
			assertSlot(reopened, campaign, pc, 1, 3, 4);
			advanceTo(reopened, campaign, pc, 3);
			assertSlot(reopened, campaign, pc, 1, 4, 5);
			reopened.rest().rest(op(), campaign, "LONG", null, null);
			assertSlot(reopened, campaign, pc, 1, 4, 4);
		}
	}

	private static Map<String, Object> levelUp(Engine engine, String campaign, String pc) {
		Map<String, Object> begun = engine.levelUps().begin(op(), campaign, pc);
		String transaction = (String) m(begun.get("transaction")).get("ref");
		if (begun.get("metamagic_choice") != null) {
			engine.levelUps().update(op(), campaign, transaction, null,
					map("metamagic", List.of("Subtle Spell", "Distant Spell")));
		}
		if (begun.get("ability_score_improvement") instanceof Map<?, ?> improvement) {
			engine.levelUps().update(op(), campaign, transaction, null,
					map("ability_score_improvement", improvement.get("recommended")));
		}
		return engine.levelUps().commit(op(), campaign, transaction, null);
	}

	private static String campaign(Engine engine) {
		String campaign = committedCampaign(engine);
		engine.sessions().bootstrap(op(), campaign, null);
		return campaign;
	}

	private static String sorcererAt(Engine engine, int level) {
		String campaign = campaign(engine);
		advanceTo(engine, campaign, "character:1", level);
		return campaign;
	}

	private static void advanceTo(Engine engine, String campaign, String character, int level) {
		Map<String, Object> sheet = engine.characters().characterSheet(campaign, character, "PLAY");
		int currentLevel = ((Number) sheet.get("level")).intValue();
		int neededXp = engine.rules().xpThreshold(level) - ((Number) sheet.get("xp")).intValue();
		if (neededXp > 0) {
			engine.runtime().awardXp(op(), campaign, List.of(character), neededXp, "MILESTONE",
					"advance to level " + level);
		}
		for (int next = currentLevel + 1; next <= level; next++) {
			levelUp(engine, campaign, character);
		}
	}

	private static String promote(Engine engine, String campaign, String characterClass, List<String> skills) {
		String companion = (String) engine.runtime()
				.materialize(op(), campaign, "Scout", characterClass + " companion", null, null, null, null, false)
				.get("character");
		engine.party().updateMembership(op(), campaign, companion, "JOIN", "travelling together");
		String transaction = (String) m(engine.levelUps().begin(op(), campaign, companion).get("transaction"))
				.get("ref");
		engine.levelUps().update(op(), campaign, transaction, null, map("class", characterClass, "skills", skills));
		engine.levelUps().commit(op(), campaign, transaction, null);
		return companion;
	}

	private static Map<String, Object> font(Engine engine, String campaign, String character, String kind, int level) {
		return engine.runtime().applyRuntimeChange(op(), campaign, character,
				map("kind", kind, "slot_level", level, "reason", "Font of Magic during the adventure"));
	}

	private static void useResource(Engine engine, String campaign, String character, String resource, int amount) {
		engine.runtime().applyRuntimeChange(op(), campaign, character, map("kind", "USE_RESOURCE", "resource", resource,
				"amount", amount, "reason", "spend a slot during play"));
	}

	private static Map<String, List<Map<String, Object>>> snapshot(Engine engine, String campaign) {
		return engine.db().snapshot(Ref.id(campaign, Ref.CAMPAIGN));
	}

	private static void assertSlot(Engine engine, String campaign, String character, int level, int current, int max) {
		assertEquals(map("current", current, "max", max), slot(engine, campaign, character, level));
	}

	private static Map<String, Object> pactSlots(Engine engine, String campaign, String character) {
		return m(m(engine.characters().characterSheet(campaign, character, "PLAY").get("spellcasting"))
				.get("pact_slots"));
	}

	private static Map<String, Object> slot(Engine engine, String campaign, String pc, int level) {
		Map<String, Object> spellcasting = m(
				engine.characters().characterSheet(campaign, pc, "PLAY").get("spellcasting"));
		return m(m(spellcasting.get("slots")).get(Integer.toString(level)));
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
