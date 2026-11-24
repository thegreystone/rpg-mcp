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
import se.hirt.mcp.rpg.protocol.ErrorCode;
import se.hirt.mcp.rpg.protocol.RpgException;
import se.hirt.mcp.rpg.rules.Combat;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static se.hirt.mcp.rpg.TestCampaigns.map;
import static se.hirt.mcp.rpg.TestCampaigns.meleeZones;
import static se.hirt.mcp.rpg.TestCampaigns.op;

/**
 * Encounter loop invariants (DOMAIN_MODEL.md I-31..I-35) and the death rules, with a scripted
 * roller (MCP_PROTOCOL.md §25.5: sanity invariants, not golden transcripts).
 */
class EncounterTest {

	@SuppressWarnings("unchecked")
	private static Map<String, Object> m(Object o) {
		return (Map<String, Object>) o;
	}

	@SuppressWarnings("unchecked")
	private static List<Map<String, Object>> list(Object o) {
		return (List<Map<String, Object>>) o;
	}

	private static int hp(Engine engine, String campaign, String character) {
		return (Integer) m(engine.characters().characterSheet(campaign, character, "SUMMARY").get("hp")).get("current");
	}

	/**
	 * The ogre ends its turn; the dying player character's automatic death saving throw rolls the
	 * scripted natural and the event for it is returned.
	 */
	private static Map<String, Object> ogreEndsTurn(
		Engine engine, ScriptedRollService dice, String campaign, String encounter, String ogre, int natural) {
		dice.queue(natural);
		Map<String, Object> turn = engine.encounters().perform(op(), campaign, encounter, ogre, map("kind", "END_TURN"),
				true);
		assertTrue(dice.exhausted(), "exactly one death save was rolled");
		List<Map<String, Object>> saves = list(turn.get("death_saves"));
		assertEquals(1, saves.size(), "one death save per turn of the dying character: " + turn);
		assertEquals(natural, saves.get(0).get("natural"));
		return saves.get(0);
	}

	@Test
	void combatArithmetic() {
		assertEquals("2d6+3", Combat.critical("1d6+3"));
		assertEquals("4d6", Combat.critical("2d6"));
		var rolled = List.of(new Combat.RolledDamage("bludgeoning", null, 7),
				new Combat.RolledDamage("poison", null, 5));
		var defenses = new Combat.Defenses(java.util.Set.of(), java.util.Set.of("bludgeoning"),
				java.util.Set.of("poison"));
		Combat.DamageResult r = Combat.applyDamage(13, 3, 13, rolled, defenses);
		assertEquals(14, r.totalDealt(), "bludgeoning doubled (vulnerable), poison ignored (immune)");
		assertEquals(0, r.tempHpAfter());
		assertEquals(2, r.hpAfter(), "13 - (14 - 3 absorbed)");
		Combat.DamageResult massive = Combat.applyDamage(3, 0, 10, List.of(new Combat.RolledDamage("fire", null, 14)),
				Combat.Defenses.NONE);
		assertTrue(massive.droppedToZero());
		assertTrue(massive.massiveDamage(), "14 - 3 = 11 >= max 10");
		Combat.DamageResult notMassive = Combat.applyDamage(3, 0, 10,
				List.of(new Combat.RolledDamage("fire", null, 12)), Combat.Defenses.NONE);
		assertFalse(notMassive.massiveDamage());

		Map<String, Object> saves = Combat.freshDeathSaves();
		saves = Combat.deathSave(4, saves);
		assertEquals("FAILURE", saves.get("outcome"));
		saves = Combat.deathSave(1, saves);
		assertEquals("DEAD", saves.get("outcome"));
		Map<String, Object> lucky = Combat.deathSave(20, Combat.freshDeathSaves());
		assertEquals("REGAIN_1_HP", lucky.get("outcome"));
		Map<String, Object> stable = Combat.deathSave(15,
				Combat.deathSave(12, Combat.deathSave(10, Combat.freshDeathSaves())));
		assertEquals("STABLE", stable.get("outcome"));
	}

	@Test
	void fullEncounterWithScriptedDice() throws Exception {
		try (Engine engine = TestCampaigns.engine(TestCampaigns.tempDb("encounter"))) {
			ScriptedRollService dice = (ScriptedRollService) engine.roller();
			String campaign = TestCampaigns.committedCampaign(engine);
			engine.sessions().bootstrap(op(), campaign, null);
			String pc = "character:1";
			// Arm Richard: buy a shortbow and arrows, equip the bow.
			Map<String, Object> bow = engine.inventory().trade(op(), campaign, pc, "BUY", "Shortbow", null, 1, null,
					null, null);
			engine.inventory().trade(op(), campaign, pc, "BUY", "Arrow", null, 1, null, null, null);
			engine.inventory().equip(op(), campaign, pc, (String) bow.get("entry"), true);

			Map<String, Object> bandit = engine.runtime().materialize(op(), campaign, "Bandit", "Grubb", null,
					"cowardly", null, null, false);
			String b1 = (String) bandit.get("character");
			assertEquals("character:2", b1);
			Map<String, Object> banditSheet = m(bandit.get("sheet"));
			assertEquals(11, m(banditSheet.get("hp")).get("max"));
			assertEquals(12, m(banditSheet.get("armor_class")).get("value"));
			assertEquals("Grubb", banditSheet.get("name"));
			String b2 = (String) engine.runtime()
					.materialize(op(), campaign, "srd5e:creature/bandit", null, null, null, null, null, false)
					.get("character");

			// Encounter tools are illegal before an encounter starts.
			RpgException early = assertThrows(RpgException.class,
					() -> engine.encounters().perform(op(), campaign, null, pc, map("kind", "DODGE"), true));
			assertEquals(ErrorCode.OPERATION_NOT_ALLOWED, early.code(),
					"harness gating: no encounter tools in EXPLORATION");

			// Initiative: Richard (DEX 14+) rolls 20; the two identical bandits share one roll of 5 (SRD 5.2.1) → Richard first.
			dice.queue(20, 5);
			Map<String, Object> started = engine.encounters().start(op(), campaign,
					map("party", List.of(pc), "raiders", List.of(b1, b2)), null, map(b1, "near", b2, "far"),
					"a muddy road at dusk", List.of("survive"), null);
			String encounter = (String) started.get("encounter");
			assertEquals("ENCOUNTER", m(started.get("meta")).get("harness_state"));
			assertNotNull(started.get("retry_checkpoint"),
					"CHECKPOINT policy saves the encounter start for a bounded retry");
			assertEquals(pc, m(started.get("turn")).get("character"));
			assertEquals(1L, started.get("round"));
			assertEquals("GRID", started.get("spatial_model"));
			assertNotNull(list(started.get("participants")).get(2).get("position_ft"));

			// Not your turn (I-31).
			RpgException notTurn = assertThrows(RpgException.class,
					() -> engine.encounters().perform(op(), campaign, encounter, b1, map("kind", "DODGE"), true));
			assertEquals(ErrorCode.OPERATION_NOT_ALLOWED, notTurn.code());

			// Richard shoots Grubb, who stands in his own zone: a ranged attack within 5 feet of an enemy has
			// Disadvantage (SRD 5.2.1 "Ranged Attacks in Close Combat"), so 2d20kl1 keeps the 15 of 15 and 18 → hit
			// vs AC 12; damage die 4 + DEX mod.
			int dexMod = (Integer) m(
					m(engine.characters().characterSheet(campaign, pc, "PLAY").get("abilities")).get("DEX"))
					.get("modifier");
			dice.queue(15, 18, 4);
			Map<String, Object> shot = engine.encounters().perform(op(), campaign, encounter, pc,
					map("kind", "ATTACK", "target", b1, "weapon", "Shortbow"), true);
			assertEquals(Boolean.TRUE, shot.get("hit"));
			assertEquals("DISADVANTAGE", shot.get("advantage"), "an enemy stands within 5 feet of the archer");
			assertEquals(List.of(15), m(shot.get("attack_roll")).get("dice"), "the lower die is kept");
			assertEquals(15, shot.get("natural"));
			assertEquals(15 + dexMod + 2, m(shot.get("attack_roll")).get("total"), "d20 + DEX + proficiency");
			assertEquals(4 + dexMod, shot.get("damage"));
			assertEquals(11 - 4 - dexMod, shot.get("hp_after"));
			assertEquals(19L, m(shot.get("ammunition")).get("remaining"), "one arrow consumed");
			assertEquals(b1, m(shot.get("next_turn")).get("character"));

			// Grubb attacks with his scimitar: natural 1 always misses.
			dice.queue(1);
			Map<String, Object> miss = engine.encounters().perform(op(), campaign, encounter, b1,
					map("kind", "ATTACK", "target", pc, "attack", "Scimitar"), true);
			assertEquals(Boolean.FALSE, miss.get("hit"));
			assertEquals(b2, m(miss.get("next_turn")).get("character"));

			// Second bandit dodges; round 2 begins with Richard.
			Map<String, Object> dodge = engine.encounters().perform(op(), campaign, encounter, b2, map("kind", "DODGE"),
					true);
			assertEquals(2L, dodge.get("round"));
			assertEquals(pc, m(dodge.get("next_turn")).get("character"));

			// Richard attacks the dodging bandit: disadvantage (Dodge, and Grubb still beside him) → 2d20kl1; queue
			// 18 and 2 → 2.
			dice.queue(18, 2);
			Map<String, Object> vsDodge = engine.encounters().perform(op(), campaign, encounter, pc,
					map("kind", "ATTACK", "target", b2, "weapon", "Shortbow"), false);
			assertEquals("DISADVANTAGE", vsDodge.get("advantage"));
			assertEquals(Boolean.FALSE, vsDodge.get("hit"));
			assertEquals(Boolean.FALSE, vsDodge.getOrDefault("turn_advanced", false));
			// On Richard's next turn, finish Grubb with a critical (natural 20 on both dice of Disadvantage →
			// doubled dice: 2d6 → 6 + 6 + DEX mod).
			engine.encounters().perform(op(), campaign, encounter, pc, map("kind", "END_TURN"), true);
			engine.encounters().perform(op(), campaign, encounter, b1, map("kind", "END_TURN"), true);
			engine.encounters().perform(op(), campaign, encounter, b2, map("kind", "END_TURN"), true);
			dice.queue(20, 20, 6, 6);
			Map<String, Object> crit = engine.encounters().perform(op(), campaign, encounter, pc,
					map("kind", "ATTACK", "target", b1, "weapon", "Shortbow"), true);
			assertEquals(Boolean.TRUE, crit.get("critical"));
			assertEquals(12 + dexMod, crit.get("damage"));
			assertEquals(Boolean.TRUE, crit.get("died"), "non-party creatures die at 0 HP");
			assertEquals("DEAD", crit.get("life_state"));
			assertEquals(0, hp(engine, campaign, b1));
			assertEquals(b2, m(crit.get("next_turn")).get("character"), "the dead bandit is skipped");

			// Ammunition runs out eventually; buying none means NO_AMMUNITION.
			// (Skip: 18 arrows left.)

			// End: victory. XP for one bandit (25) goes to the single classed survivor.
			Map<String, Object> ended = engine.encounters().end(op(), campaign, encounter, "PARTY_VICTORY",
					"Grubb fell; the other fled.");
			// The pool is fixed at start (both bandits count) and pays out because the encounter was overcome.
			assertEquals(50L, ended.get("xp_pool"));
			assertEquals(50L, list(ended.get("xp_awarded")).get(0).get("xp_gained"));
			assertEquals("EXPLORATION", m(ended.get("meta")).get("harness_state"));
			assertEquals(List.of(), ended.get("level_up_eligible"));
			assertEquals(18L, ended.get("elapsed_seconds"), "three legal grid rounds advance eighteen actual seconds");
			assertEquals(0L, ended.get("elapsed_minutes"), "combat no longer rounds each encounter up to a minute");
			assertNull(engine.characters().characterSheet(campaign, pc, "PLAY").get("encounter"));

			// Memory: the death and the resolution are in the ledger.
			Map<String, Object> events = engine.ledger().queryTimeline(campaign, null, null, null,
					List.of("CHARACTER_DIED", "ENCOUNTER_RESOLVED"), null, false, 10);
			assertEquals(2, list(events.get("events")).size());

			// XP awards: discretionary is audited; enough XP flags level-up eligibility.
			Map<String, Object> award = engine.runtime().awardXp(op(), campaign, List.of(pc), 275, "DISCRETIONARY",
					"brilliant roleplay");
			assertEquals(Boolean.TRUE, award.get("audited"));
			assertEquals(Boolean.TRUE, list(award.get("awarded")).get(0).get("level_up_eligible"),
					"300 XP reaches level 2");
			assertEquals(Boolean.TRUE,
					engine.characters().characterSheet(campaign, pc, "PLAY").get("level_up_eligible"));
		}
	}

	@Test
	void playerDeathSavesAndContinuation() throws Exception {
		try (Engine engine = TestCampaigns.engine(TestCampaigns.tempDb("death"))) {
			ScriptedRollService dice = (ScriptedRollService) engine.roller();
			String campaign = TestCampaigns.committedCampaign(engine);
			engine.sessions().bootstrap(op(), campaign, null);
			String pc = "character:1";
			int maxHp = (Integer) m(engine.characters().characterSheet(campaign, pc, "SUMMARY").get("hp")).get("max");
			String ogre = (String) engine.runtime()
					.materialize(op(), campaign, "Ogre", null, null, null, null, null, false).get("character");

			// Ogre wins initiative.
			dice.queue(1, 20);
			Map<String, Object> started = engine.encounters().start(op(), campaign,
					map("party", List.of(pc), "monsters", List.of(ogre)), null, meleeZones(pc, ogre), null, null, null);
			String encounter = (String) started.get("encounter");
			assertEquals(ogre, m(started.get("turn")).get("character"));

			// The ogre's greatclub (2d8+4) at its minimum of 6 (natural 19, dice 1 and 1) lands as often as it takes to
			// bring Richard to exactly 0: each blow is short of massive damage (the overflow stays below his maximum),
			// so he drops to 0 and starts dying rather than dying outright. Living Richard passes between blows.
			Map<String, Object> smash = null;
			while (hp(engine, campaign, pc) > 0) {
				dice.queue(19, 1, 1);
				smash = engine.encounters().perform(op(), campaign, encounter, ogre,
						map("kind", "ATTACK", "target", pc, "attack", "Greatclub"), false);
				assertEquals(Boolean.TRUE, smash.get("hit"));
				assertEquals(6, smash.get("damage"));
				assertTrue(dice.exhausted());
				if (hp(engine, campaign, pc) > 0) {
					engine.encounters().perform(op(), campaign, encounter, ogre, map("kind", "END_TURN"), true);
					engine.encounters().perform(op(), campaign, encounter, pc, map("kind", "END_TURN"), true);
				}
			}
			assertEquals("DYING", smash.get("life_state"), "party members drop to 0 and start dying: " + smash);
			assertEquals(Boolean.TRUE, smash.get("dropped_to_zero"));
			Map<String, Object> pcState = engine.characters().characterSheet(campaign, pc, "PLAY");
			assertEquals("DYING", pcState.get("life_state"));
			assertEquals(0, m(pcState.get("hp")).get("current"));
			assertTrue(
					list(pcState.get("conditions")).stream().anyMatch(c -> "UNCONSCIOUS".equals(c.get("condition"))));
			assertEquals(0, m(pcState.get("death_saves")).get("failures"));
			assertEquals(0, m(pcState.get("death_saves")).get("successes"));

			// Healing revives: a potion via apply_runtime_change. Unconscious ends; he remains Prone (SRD 5.2.1
			// "Unconscious") until he stands up (STAND on his own turn) or, as here during the ogre's turn, the GM
			// records it by removing the condition.
			Map<String, Object> healed = engine.runtime().applyRuntimeChange(op(), campaign, pc,
					map("kind", "HEAL", "amount", 3, "reason", "companion's potion"));
			assertEquals("ALIVE", healed.get("life_state"));
			assertEquals(Boolean.TRUE, healed.get("revived"));
			assertEquals(3, m(healed.get("hp")).get("current"));
			assertEquals(List.of("PRONE"),
					list(healed.get("conditions")).stream().map(c -> c.get("condition")).toList(),
					"Unconscious ended, Prone remains");
			assertNull(engine.characters().characterSheet(campaign, pc, "PLAY").get("death_saves"));
			engine.runtime().applyRuntimeChange(op(), campaign, pc,
					map("kind", "REMOVE_CONDITION", "condition", "PRONE", "reason", "staggers to his feet"));
			engine.encounters().perform(op(), campaign, encounter, ogre, map("kind", "END_TURN"), true);
			engine.encounters().perform(op(), campaign, encounter, pc, map("kind", "END_TURN"), true);

			// The ogre drops Richard again (3 HP, one minimum blow) and ends its turn: the turn passes to Richard,
			// whose death saving throw is rolled at the start of his turn (natural 5: a failure), and he is skipped
			// back to the ogre.
			dice.queue(19, 1, 1, 5);
			Map<String, Object> again = engine.encounters().perform(op(), campaign, encounter, ogre,
					map("kind", "ATTACK", "target", pc, "attack", "Greatclub"), true);
			assertEquals("DYING", again.get("life_state"));
			assertTrue(dice.exhausted(), "one death save was rolled for Richard's skipped turn");
			List<Map<String, Object>> saves = list(again.get("death_saves"));
			assertEquals(1, saves.size());
			assertEquals("FAILURE", saves.get(0).get("outcome"));
			assertEquals(1, saves.get(0).get("failures"));
			assertEquals(0, saves.get(0).get("successes"));
			assertEquals(ogre, m(again.get("next_turn")).get("character"), "unconscious characters skip their turn");
			assertTrue(list(engine.characters().characterSheet(campaign, pc, "PLAY").get("conditions")).stream()
					.anyMatch(c -> "UNCONSCIOUS".equals(c.get("condition"))));

			// Each ogre turn that ends hands Richard another save: 12 succeeds (1/1), 3 fails (1/2), 2 fails: dead.
			Map<String, Object> second = ogreEndsTurn(engine, dice, campaign, encounter, ogre, 12);
			assertEquals("SUCCESS", second.get("outcome"));
			assertEquals(1, second.get("successes"));
			assertEquals(1, second.get("failures"));
			Map<String, Object> third = ogreEndsTurn(engine, dice, campaign, encounter, ogre, 3);
			assertEquals("FAILURE", third.get("outcome"));
			assertEquals(2, third.get("failures"));
			assertEquals("DYING", engine.characters().characterSheet(campaign, pc, "SUMMARY").get("life_state"));
			Map<String, Object> last = ogreEndsTurn(engine, dice, campaign, encounter, ogre, 2);
			assertEquals("DEAD", last.get("outcome"), "the third failure kills");
			assertEquals(3, last.get("failures"));
			assertEquals("DEAD", engine.characters().characterSheet(campaign, pc, "SUMMARY").get("life_state"));
			assertEquals("DEFEATED",
					list(engine.encounters().encounterState(campaign, encounter, 3).get("participants")).stream()
							.filter(p -> pc.equals(p.get("character"))).findFirst().orElseThrow().get("status"));

			// Ending with a dead player character → CHECKPOINT_DECISION with the campaign_start checkpoint restorable.
			Map<String, Object> ended = engine.encounters().end(op(), campaign, encounter, "PARTY_DEFEAT",
					"The ogre won.");
			assertEquals("CHECKPOINT_DECISION", m(ended.get("meta")).get("harness_state"));
			assertEquals("DEAD", m(ended.get("continuation")).get("player_character_status"));
			RpgException noPlay = assertThrows(RpgException.class, () -> engine.checks().resolveCheck(op(), campaign,
					pc, "ABILITY_CHECK", "STR", null, 10, null, null));
			assertEquals(ErrorCode.OPERATION_NOT_ALLOWED, noPlay.code());
			Map<String, Object> options = engine.checkpoints().options(campaign);
			List<Map<String, Object>> checkpoints = list(list(options.get("options")).get(0).get("checkpoints"));
			assertTrue(checkpoints.stream().anyMatch(c -> "encounter_retry".equals(c.get("reason"))));
			Map<String, Object> campaignStart = checkpoints.stream()
					.filter(c -> "campaign_start".equals(c.get("reason"))).findFirst().orElseThrow();
			Map<String, Object> restored = engine.checkpoints().restore(op(), campaign,
					(String) campaignStart.get("ref"), "rewind the crypt");
			assertEquals("EXPLORATION", m(restored.get("meta")).get("harness_state"));
			assertEquals("ALIVE", engine.characters().characterSheet(campaign, pc, "SUMMARY").get("life_state"));
			assertEquals(maxHp, hp(engine, campaign, pc));
			assertEquals(ErrorCode.NOT_FOUND,
					assertThrows(RpgException.class,
							() -> engine.characters().characterSheet(campaign, ogre, "SUMMARY")).code(),
					"the ogre was materialized after the checkpoint and is gone");
		}
	}

	@Test
	void encounterRetryCheckpointRewindsTheEncounterItself() throws Exception {
		try (Engine engine = TestCampaigns.engine(TestCampaigns.tempDb("retry"))) {
			ScriptedRollService dice = (ScriptedRollService) engine.roller();
			String campaign = (String) engine.campaigns().create(op(), null, null).get("campaign");
			engine.campaigns().updateSetup(op(), campaign, null,
					map("content_profile", "PEGI_16", "experience", "SURPRISE_ME", "rules", Map.of(), "continuation",
							"ENCOUNTER_RETRY", "party", "SURPRISE_ME", "adventure",
							map("premise", "x", "opening_location", "Road", "immediate_goal", "y")));
			String pc = (String) engine.characters()
					.createDraft(op(), campaign, map("name", "Brakk", "species", "Orc", "class", "Fighter",
							"ability_scores", map("STR", 15, "CON", 14, "DEX", 13, "WIS", 12, "INT", 10, "CHA", 8),
							"background", "Criminal", "background_ability_scores", map("DEX", 2, "CON", 1), "skills",
							List.of("Athletics", "Perception"), "personality", "Loud.", "starting_equipment", "A"),
							true)
					.get("character");
			engine.characters().commitDraft(op(), campaign, pc, null);
			engine.campaigns().commitSetup(op(), campaign, null);
			engine.sessions().bootstrap(op(), campaign, null);
			String wolf = (String) engine.runtime()
					.materialize(op(), campaign, "Wolf", null, null, null, null, null, true).get("character");
			int before = hp(engine, campaign, pc);

			dice.queue(20, 1);
			Map<String, Object> started = engine.encounters().start(op(), campaign,
					map("party", List.of(pc), "wild", List.of(wolf)), null, meleeZones(pc, wolf), null, null, null);
			String retry = (String) started.get("retry_checkpoint");
			assertNotNull(retry, "ENCOUNTER_RETRY creates the retry checkpoint before the first round (I-35)");
			// Greatsword (Option A) with two hands: 2d6 + STR.
			dice.queue(12, 3, 3);
			Map<String, Object> swing = engine.encounters().perform(op(), campaign, null, pc,
					map("kind", "ATTACK", "target", wolf, "weapon", "Greatsword"), true);
			assertEquals(Boolean.TRUE, swing.get("hit"));
			assertEquals(6 + 2, swing.get("damage"));
			dice.queue(18, 6);
			Map<String, Object> bite = engine.encounters().perform(op(), campaign, null, wolf,
					map("kind", "ATTACK", "target", pc), true);
			assertEquals(Boolean.TRUE, bite.get("hit"));
			assertTrue(hp(engine, campaign, pc) < before);

			// Mid-encounter suspension is not supported; restore is.
			RpgException suspend = assertThrows(RpgException.class,
					() -> engine.sessions().suspend(op(), campaign, "nope"));
			assertEquals(ErrorCode.OPERATION_NOT_ALLOWED, suspend.code());
			Map<String, Object> restored = engine.checkpoints().restore(op(), campaign, retry, "retry the fight");
			assertEquals("EXPLORATION", m(restored.get("meta")).get("harness_state"));
			assertEquals(before, hp(engine, campaign, pc));
			assertEquals("ALIVE", engine.characters().characterSheet(campaign, wolf, "SUMMARY").get("life_state"));
			assertTrue(engine.db().read(tx -> tx.count("SELECT COUNT(*) FROM encounter WHERE campaign_id = 1")) == 0,
					"the encounter itself was rewound");
			// And the fight can be started again.
			dice.queue(10, 10);
			Map<String, Object> again = engine.encounters().start(op(), campaign,
					map("party", List.of(pc), "wild", List.of(wolf)), null, meleeZones(pc, wolf), null, null, null);
			assertNotNull(again.get("retry_checkpoint"));
		}
	}

	@Test
	void ironmanTransfersControlToASurvivor() throws Exception {
		try (Engine engine = TestCampaigns.engine(TestCampaigns.tempDb("ironman-transfer"))) {
			ScriptedRollService dice = (ScriptedRollService) engine.roller();
			String campaign = (String) engine.campaigns().create(op(), null, null).get("campaign");
			engine.campaigns().updateSetup(op(), campaign, null,
					map("content_profile", "PEGI_16", "experience", "SURPRISE_ME", "rules", Map.of(), "continuation",
							"IRONMAN", "party", "SURPRISE_ME", "adventure",
							map("premise", "x", "opening_location", "Camp", "immediate_goal", "y")));
			String pc = (String) engine.characters()
					.createDraft(op(), campaign,
							map("name", "Ash", "species", "Human", "class", "Wizard", "ability_scores",
									map("INT", 15, "DEX", 14, "CON", 13, "WIS", 12, "CHA", 10, "STR", 8), "background",
									"Criminal", "background_ability_scores", map("DEX", 2, "INT", 1), "species_skill",
									"Insight", "origin_feat",
									map("feat", "Skilled", "proficiencies", List.of("Nature", "Survival", "Medicine")),
									"skills", List.of("Arcana", "History"), "personality", "Curious."),
							true)
					.get("character");
			engine.characters().commitDraft(op(), campaign, pc, null);
			engine.campaigns().commitSetup(op(), campaign, null);
			engine.sessions().bootstrap(op(), campaign, null);
			// A companion: a materialized guard recruited into the party.
			String mara = (String) engine.runtime()
					.materialize(op(), campaign, "Guard", "Mara", null, "dry, loyal", null, null, false)
					.get("character");
			engine.db().mutate(se.hirt.mcp.rpg.persistence.Database.Mutation.of("test_join", 1L, op(), "GM", null),
					tx -> {
						tx.insert("party_membership",
								map("campaign_id", 1L, "character_id", 2L, "state", "ACTIVE", "joined_seq", 0));
						return new java.util.LinkedHashMap<>(Map.of("ok", true));
					});
			String ogre = (String) engine.runtime()
					.materialize(op(), campaign, "Ogre", null, null, null, null, null, false).get("character");
			dice.queue(20, 5, 1);
			String encounter = (String) engine.encounters()
					.start(op(), campaign, map("party", List.of(pc, mara), "monsters", List.of(ogre)), null,
							meleeZones(pc, mara, ogre), null, null, null)
					.get("encounter");
			// Ash dies to massive damage: 2d8+4 with 8,8 = 20 vs a level-1 wizard (max 6 + CON 1 = 7): overflow 13 >= 7.
			engine.encounters().perform(op(), campaign, encounter, pc, map("kind", "DODGE"), true);
			engine.encounters().perform(op(), campaign, encounter, mara, map("kind", "DODGE"), true);
			dice.queue(20, 8, 8);
			Map<String, Object> smash = engine.encounters().perform(op(), campaign, encounter, ogre,
					map("kind", "ATTACK", "target", pc, "attack", "Greatclub"), true);
			assertEquals("DEAD", smash.get("life_state"), "massive damage kills outright: " + smash);
			Map<String, Object> ended = engine.encounters().end(op(), campaign, encounter, "PARTY_FLED",
					"Mara dragged herself away.");
			assertEquals("PLAYER_CHARACTER_TRANSFER", m(ended.get("meta")).get("harness_state"));
			RpgException noRewind = assertThrows(RpgException.class,
					() -> engine.checkpoints().restore(op(), campaign, "checkpoint:1", "please"));
			assertTrue(List.of(ErrorCode.POLICY_DENIED, ErrorCode.OPERATION_NOT_ALLOWED).contains(noRewind.code()),
					"IRONMAN never rewinds: " + noRewind.code());
			Map<String, Object> transferred = engine.runtime().transferControl(op(), campaign, mara, "Ash is gone");
			assertEquals(mara, transferred.get("player_character"));
			assertEquals("EXPLORATION", m(transferred.get("meta")).get("harness_state"));
			Map<String, Object> ctx = engine.sessions().bootstrap(op(), campaign, null);
			assertEquals("Mara", m(ctx.get("player_character")).get("name"));
			assertEquals(1, list(engine.ledger()
					.queryTimeline(campaign, null, null, null, List.of("PLAYER_CONTROL_TRANSFERRED"), null, false, 5)
					.get("events")).size());
		}
	}
}
