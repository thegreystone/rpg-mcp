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
import se.hirt.mcp.rpg.checkpoint.CheckpointService;
import se.hirt.mcp.rpg.dice.ScriptedRollService;
import se.hirt.mcp.rpg.persistence.Database;
import se.hirt.mcp.rpg.protocol.ErrorCode;
import se.hirt.mcp.rpg.protocol.Ref;
import se.hirt.mcp.rpg.protocol.RpgException;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static se.hirt.mcp.rpg.TestCampaigns.map;
import static se.hirt.mcp.rpg.TestCampaigns.op;

/** The Light property's extra attack has its own cost, prerequisites and damage rule. */
class LightWeaponAttackTest {

	@Test
	void lightExtraAttackOmitsOnlyThePositiveAbilityDamageModifier() throws Exception {
		try (Fixture fixture = fixture(15)) {
			fixture.dice.queue(19, 2);
			Map<String, Object> normal = attack(fixture, fixture.dagger, false, false);
			fixture.dice.queue(19, 3);
			Map<String, Object> extra = attack(fixture, fixture.handaxe, true, false);
			assertEquals(4, normal.get("damage"), "the ordinary attack retains its +2 ability modifier");
			assertEquals("1d4+2", damageRoll(normal).get("expression"));
			assertEquals(3, extra.get("damage"), "the extra attack omits the positive ability modifier");
			assertEquals("1d6", damageRoll(extra).get("expression"));
			assertEquals(4, m(extra.get("attack_roll")).get("modifier"),
					"the attack roll still adds ability and proficiency");
			assertEquals(Boolean.TRUE, extra.get("light_extra_attack"));
			assertEquals("BONUS_ACTION", extra.get("action_cost"));
			assertTrue(fixture.dice.exhausted());
		}
	}

	@Test
	void aNegativeAbilityModifierStillAppliesToTheLightExtraAttack() throws Exception {
		try (Fixture fixture = fixture(8)) {
			fixture.dice.queue(19, 2);
			attack(fixture, fixture.dagger, false, false);
			fixture.dice.queue(19, 3);
			Map<String, Object> extra = attack(fixture, fixture.handaxe, true, false);
			assertEquals(2, extra.get("damage"));
			assertEquals("1d6-1", damageRoll(extra).get("expression"));
			assertEquals(1, m(extra.get("attack_roll")).get("modifier"));
		}
	}

	@Test
	void magicAndExtraDamageSurviveOmittingThePositiveAbilityModifier() throws Exception {
		Map<String, Object> magic = map("name", "Ember Handaxe", "rarity", "UNCOMMON", "bonus", 1, "damage_bonus_dice",
				"1d4", "damage_type", "fire", "text", "A light axe with a glowing edge.");
		try (Fixture fixture = fixture(15, map("item", "Handaxe", "magic", magic), false, false)) {
			fixture.dice.queue(19, 2);
			attack(fixture, fixture.dagger, false, false);
			fixture.dice.queue(19, 3, 4);
			Map<String, Object> extra = attack(fixture, fixture.handaxe, true, false);
			assertEquals(8, extra.get("damage"), "3 + 1 magic + 4 fire; the ability bonus alone is omitted");
			assertEquals("1d6+1", damageRoll(extra).get("expression"));
			assertEquals("1d4", m(list(extra.get("breakdown")).get(1).get("roll")).get("expression"));
			assertEquals("fire", list(extra.get("breakdown")).get(1).get("type"));
			assertEquals(5, m(extra.get("attack_roll")).get("modifier"), "magic still improves the attack roll too");
		}
	}

	@Test
	void twoWeaponFightingAddsTheAbilityModifierExactlyOnce() throws Exception {
		try (Fixture fixture = fixture(15, map("item", "Handaxe"), true, false)) {
			fixture.dice.queue(19, 2);
			attack(fixture, fixture.dagger, false, false);
			fixture.dice.queue(19, 3);
			Map<String, Object> extra = attack(fixture, fixture.handaxe, true, false);
			assertEquals(5, extra.get("damage"));
			assertEquals("1d6+2", damageRoll(extra).get("expression"));
			assertEquals("BONUS_ACTION", extra.get("action_cost"), "the feat changes damage, not the action cost");
		}
	}

	@Test
	void twoDifferentDaggersQualifyEvenThoughTheyHaveTheSameItemDefinition() throws Exception {
		try (Fixture fixture = fixture(15, map("item", "Dagger"), false, false)) {
			assertNotEquals(fixture.dagger, fixture.handaxe, "equipping a two-dagger stack creates distinct entries");
			fixture.dice.queue(19, 2);
			attack(fixture, fixture.dagger, false, false);
			fixture.dice.queue(19, 3);
			Map<String, Object> extra = attack(fixture, fixture.handaxe, true, false);
			assertEquals(3, extra.get("damage"));
			assertEquals("1d4", damageRoll(extra).get("expression"));
		}
	}

	@Test
	void aMissedLightAttackStillQualifiesForTheExtraAttack() throws Exception {
		try (Fixture fixture = fixture(15)) {
			fixture.dice.queue(1);
			Map<String, Object> missed = attack(fixture, fixture.dagger, false, true);
			assertEquals(Boolean.FALSE, missed.get("hit"));
			assertEquals(Boolean.FALSE, missed.get("turn_advanced"));
			assertTrue(((List<?>) missed.get("light_extra_attack_available")).contains(fixture.handaxe));
			fixture.dice.queue(19, 3);
			assertEquals(Boolean.TRUE, attack(fixture, fixture.handaxe, true, true).get("hit"));
		}
	}

	@Test
	void ordinaryLightAttacksKeepTheTurnOpenAndTheExtraAttackResetsEachTurn() throws Exception {
		try (Fixture fixture = fixture(15)) {
			fixture.dice.queue(19, 2);
			Map<String, Object> normal = attack(fixture, fixture.dagger, false, true);
			assertEquals(Boolean.FALSE, normal.get("turn_advanced"));
			assertTrue(((List<?>) normal.get("light_extra_attack_available")).contains(fixture.handaxe));
			assertNotNull(normal.get("next_step"));
			fixture.dice.queue(19, 3);
			attack(fixture, fixture.handaxe, true, false);
			assertRejectedWithoutChanges(fixture, map("kind", "ATTACK", "weapon", fixture.handaxe, "target",
					fixture.target, "light_extra_attack", true));
			endTurn(fixture, fixture.actor);
			endTurn(fixture, fixture.target);
			assertRejectedWithoutChanges(fixture, map("kind", "ATTACK", "weapon", fixture.handaxe, "target",
					fixture.target, "light_extra_attack", true));
			fixture.dice.queue(19, 2);
			attack(fixture, fixture.dagger, false, true);
			fixture.dice.queue(19, 3);
			Map<String, Object> nextTurnExtra = attack(fixture, fixture.handaxe, true, true);
			assertEquals(Boolean.TRUE, nextTurnExtra.get("turn_advanced"));
			assertEquals(fixture.target, m(nextTurnExtra.get("next_turn")).get("character"));
		}
	}

	@Test
	void endingTheTurnExplicitlyDeclinesAnAvailableLightExtraAttack() throws Exception {
		try (Fixture fixture = fixture(15)) {
			fixture.dice.queue(19, 2);
			attack(fixture, fixture.dagger, false, true);
			Map<String, Object> ended = endTurn(fixture, fixture.actor);
			assertEquals(Boolean.TRUE, ended.get("turn_advanced"));
			assertEquals(fixture.target, m(ended.get("next_turn")).get("character"));
		}
	}

	@Test
	void lightExtraAttackRequiresAnEarlierAttackWithADifferentLightWeapon() throws Exception {
		try (Fixture fixture = fixture(15)) {
			assertRejectedWithoutChanges(fixture, map("kind", "ATTACK", "weapon", fixture.handaxe, "target",
					fixture.target, "light_extra_attack", true));
			fixture.dice.queue(19, 2);
			attack(fixture, fixture.longsword, false, false);
			assertRejectedWithoutChanges(fixture, map("kind", "ATTACK", "weapon", fixture.handaxe, "target",
					fixture.target, "light_extra_attack", true));
			fixture.dice.queue(19, 2);
			attack(fixture, fixture.dagger, false, false);
			for (String invalid : List.of(fixture.dagger, fixture.longsword, "unarmed")) {
				assertRejectedWithoutChanges(fixture,
						map("kind", "ATTACK", "weapon", invalid, "target", fixture.target, "light_extra_attack", true));
			}
			fixture.dice.queue(19, 3);
			assertEquals(3, attack(fixture, fixture.handaxe, true, false).get("damage"),
					"invalid requests do not consume the valid extra attack");
		}
	}

	@Test
	void aLightOpportunityAttackDoesNotQualifyForAnExtraAttackOnTheNextTurn() throws Exception {
		try (Fixture fixture = fixture(15)) {
			endTurn(fixture, fixture.actor);
			Map<String, Object> move = fixture.engine.encounters().perform(op(), fixture.campaign, fixture.encounter,
					fixture.target, map("kind", "MOVE", "zone", "far"), true);
			Map<String, Object> choice = list(move.get("pending_choices")).get(0);
			fixture.dice.queue(19, 2);
			Map<String, Object> reacted = fixture.engine.encounters().resolveChoice(op(), fixture.campaign,
					(String) choice.get("transaction"), map("option", "TAKE", "weapon", fixture.dagger));
			assertEquals(Boolean.TRUE, m(reacted.get("attack")).get("hit"));
			assertEquals(fixture.actor, m(reacted.get("next_turn")).get("character"));
			assertRejectedWithoutChanges(fixture, map("kind", "ATTACK", "weapon", fixture.handaxe, "target",
					fixture.target, "light_extra_attack", true));
		}
	}

	@Test
	void lightExtraAttackFlagMustBeBoolean() throws Exception {
		try (Fixture fixture = fixture(15)) {
			for (Object invalid : List.of("true", 1, Map.of("enabled", true))) {
				RpgException rejected = assertRejectedWithoutChanges(fixture, map("kind", "ATTACK", "weapon",
						fixture.handaxe, "target", fixture.target, "light_extra_attack", invalid));
				assertEquals(ErrorCode.INVALID_ARGUMENT, rejected.code());
			}
		}
	}

	@Test
	void aBonusActionSpellConsumesTheLightExtraAttacksBudget() throws Exception {
		try (Fixture fixture = fixture(15, map("item", "Handaxe"), false, true)) {
			fixture.dice.queue(2, 2);
			Map<String, Object> spell = healingWord(fixture);
			assertEquals("BONUS_ACTION", spell.get("action_cost"));
			fixture.dice.queue(19, 2);
			attack(fixture, fixture.dagger, false, false);
			RpgException rejected = assertRejectedWithoutChanges(fixture, map("kind", "ATTACK", "weapon",
					fixture.handaxe, "target", fixture.target, "light_extra_attack", true));
			assertEquals(ErrorCode.OPERATION_NOT_ALLOWED, rejected.code());
		}
	}

	@Test
	void aLightExtraAttackConsumesTheBonusActionSpellsBudgetWithoutSpendingItsResource() throws Exception {
		try (Fixture fixture = fixture(15, map("item", "Handaxe"), false, true)) {
			fixture.dice.queue(19, 2);
			attack(fixture, fixture.dagger, false, false);
			fixture.dice.queue(19, 3);
			attack(fixture, fixture.handaxe, true, false);
			RpgException rejected = assertRejectedWithoutChanges(fixture,
					map("kind", "CAST", "spell", "Healing Word", "targets", List.of(fixture.actor)));
			assertEquals(ErrorCode.OPERATION_NOT_ALLOWED, rejected.code());
		}
	}

	@Test
	void replayingAnExtraAttackDoesNotDealDamageOrSpendItsCostTwice() throws Exception {
		try (Fixture fixture = fixture(15)) {
			fixture.dice.queue(19, 2);
			attack(fixture, fixture.dagger, false, false);
			String operation = op();
			Map<String, Object> action = map("kind", "ATTACK", "weapon", fixture.handaxe, "target", fixture.target,
					"light_extra_attack", true);
			fixture.dice.queue(19, 3);
			Map<String, Object> first = fixture.engine.encounters().perform(operation, fixture.campaign,
					fixture.encounter, fixture.actor, action, false);
			Map<String, List<Map<String, Object>>> after = fixture.engine.db()
					.snapshot(Ref.id(fixture.campaign, Ref.CAMPAIGN));
			Map<String, Object> replay = fixture.engine.encounters().perform(operation, fixture.campaign,
					fixture.encounter, fixture.actor, action, false);
			assertEquals(Boolean.TRUE, replay.get("replayed"));
			assertEquals(first.get("damage"), replay.get("damage"));
			assertEquals("BONUS_ACTION", replay.get("action_cost"));
			assertEquals(after, fixture.engine.db().snapshot(Ref.id(fixture.campaign, Ref.CAMPAIGN)),
					"an idempotent replay leaves HP, bonus-action budget and encounter state unchanged");
			assertTrue(fixture.dice.exhausted());
			assertRejectedWithoutChanges(fixture, action);
		}
	}

	@Test
	void restoringAfterTheNormalAttackRestoresEligibilityBudgetAndHitPoints() throws Exception {
		try (Fixture fixture = fixture(15, map("item", "Handaxe", "quantity", 2), false, false)) {
			String otherHandaxe = (String) list(fixture.engine.characters()
					.characterSheet(fixture.campaign, fixture.actor, "PLAY").get("inventory")).stream()
					.filter(i -> "Handaxe".equals(i.get("name")) && !Boolean.TRUE.equals(i.get("equipped"))).findFirst()
					.orElseThrow().get("ref");
			fixture.dice.queue(19, 2);
			attack(fixture, fixture.dagger, false, false);
			Object hpAtCheckpoint = targetHp(fixture);
			// Encounter retry checkpoints use this same internal entry point; the manual tool is exploration-only.
			String checkpoint = (String) fixture.engine.db()
					.mutate(Database.Mutation.of("light_attack_checkpoint_fixture",
							Ref.id(fixture.campaign, Ref.CAMPAIGN), op(), "GM", null),
							tx -> map("checkpoint", CheckpointService.createInTx(tx,
									Ref.id(fixture.campaign, Ref.CAMPAIGN), "before the extra attack")))
					.get("checkpoint");
			fixture.dice.queue(19, 3);
			assertEquals(3, attack(fixture, fixture.handaxe, true, false).get("damage"));
			assertRejectedWithoutChanges(fixture, map("kind", "ATTACK", "weapon", fixture.handaxe, "target",
					fixture.target, "light_extra_attack", true));
			fixture.engine.checkpoints().restore(op(), fixture.campaign, checkpoint, "try the other axe");
			assertEquals(hpAtCheckpoint, targetHp(fixture));
			assertEquals(fixture.actor,
					m(fixture.engine.encounters().encounterState(fixture.campaign, fixture.encounter, 3).get("turn"))
							.get("character"));
			fixture.engine.inventory().equip(op(), fixture.campaign, fixture.actor, fixture.handaxe, false);
			fixture.engine.inventory().equip(op(), fixture.campaign, fixture.actor, otherHandaxe, true);
			fixture.dice.queue(19, 5);
			Map<String, Object> alternate = attack(fixture, otherHandaxe, true, false);
			assertEquals(5, alternate.get("damage"), "the restored branch can choose a different extra-attack weapon");
			assertEquals(((Number) hpAtCheckpoint).intValue() - 5, targetHp(fixture));
		}
	}

	@Test
	void decliningShieldPreservesTheLightExtraAttacksReducedDamageAndSpentAllowance() throws Exception {
		try (Fixture fixture = fixture(15)) {
			giveTargetShield(fixture);
			fixture.dice.queue(19, 2);
			attack(fixture, fixture.dagger, false, false);
			fixture.dice.queue(8);
			Map<String, Object> pending = attack(fixture, fixture.handaxe, true, false);
			assertEquals(Boolean.FALSE, pending.get("resolved"));
			Object hpBefore = targetHp(fixture);
			fixture.dice.queue(3);
			Map<String, Object> resolved = resolveShield(fixture, pending, "DECLINE");
			Map<String, Object> hit = m(resolved.get("attack"));
			assertEquals(3, hit.get("damage"));
			assertEquals("1d6", damageRoll(hit).get("expression"));
			assertEquals(Boolean.TRUE, hit.get("light_extra_attack"));
			assertEquals("BONUS_ACTION", hit.get("action_cost"));
			assertEquals(((Number) hpBefore).intValue() - 3, targetHp(fixture));
			assertRejectedWithoutChanges(fixture, map("kind", "ATTACK", "weapon", fixture.handaxe, "target",
					fixture.target, "light_extra_attack", true));
		}
	}

	@Test
	void shieldTurningTheExtraAttackIntoAMissStillSpendsItsAllowance() throws Exception {
		try (Fixture fixture = fixture(15)) {
			giveTargetShield(fixture);
			fixture.dice.queue(19, 2);
			attack(fixture, fixture.dagger, false, false);
			fixture.dice.queue(8);
			Map<String, Object> pending = attack(fixture, fixture.handaxe, true, false);
			Object hpBefore = targetHp(fixture);
			Map<String, Object> resolved = resolveShield(fixture, pending, "CAST_SHIELD");
			assertEquals(Boolean.FALSE, m(resolved.get("attack")).get("hit"));
			assertEquals(Boolean.TRUE, m(resolved.get("attack")).get("light_extra_attack"));
			assertEquals(hpBefore, targetHp(fixture));
			assertTrue(fixture.dice.exhausted(), "a Shield miss rolls no damage dice");
			assertRejectedWithoutChanges(fixture, map("kind", "ATTACK", "weapon", fixture.handaxe, "target",
					fixture.target, "light_extra_attack", true));
		}
	}

	@Test
	void resolvingThePrimaryAttacksShieldChoiceKeepsTheLightExtraAttackAvailable() throws Exception {
		try (Fixture fixture = fixture(15)) {
			giveTargetShield(fixture);
			fixture.dice.queue(8);
			Map<String, Object> pending = attack(fixture, fixture.dagger, false, true);
			fixture.dice.queue(2);
			Map<String, Object> resolved = resolveShield(fixture, pending, "DECLINE");
			Map<String, Object> turn = m(m(resolved.get("state")).get("turn"));
			assertEquals(fixture.actor, turn.get("character"));
			assertTrue(((List<?>) turn.get("light_extra_attack_available")).contains(fixture.handaxe));
			fixture.dice.queue(19, 3);
			Map<String, Object> extra = attack(fixture, fixture.handaxe, true, true);
			assertEquals(3, extra.get("damage"));
			assertEquals(Boolean.TRUE, extra.get("turn_advanced"));
			assertEquals(fixture.target, m(extra.get("next_turn")).get("character"));
		}
	}

	@Test
	void aLightExtraAttackDoesNotConsumeTheRemainingOrdinaryMultiattack() throws Exception {
		try (Fixture fixture = fixture(15)) {
			fixture.engine.encounters().end(op(), fixture.campaign, fixture.encounter, "OTHER",
					"make room for the scout");
			String scout = (String) fixture.engine.runtime()
					.materialize(op(), fixture.campaign, "Scout", "Reed", null, null, null, null, false)
					.get("character");
			String stack = grant(fixture.engine, fixture.campaign, scout, map("item", "Shortsword", "quantity", 2));
			String firstSword = (String) fixture.engine.inventory().equip(op(), fixture.campaign, scout, stack, true)
					.get("entry");
			String secondSword = (String) fixture.engine.inventory().equip(op(), fixture.campaign, scout, stack, true)
					.get("entry");
			assertNotEquals(firstSword, secondSword);
			fixture.dice.queue(20, 100, 1, 1);
			Map<String, Object> started = fixture.engine.encounters().start(op(), fixture.campaign,
					map("party", List.of(scout), "foes", List.of(fixture.target)), null, null, null, null, null);
			String encounter = (String) started.get("encounter");
			assertEquals(scout, m(started.get("turn")).get("character"));
			fixture.dice.queue(19, 2);
			Map<String, Object> first = fixture.engine.encounters().perform(op(), fixture.campaign, encounter, scout,
					map("kind", "ATTACK", "attack", firstSword, "target", fixture.target), true);
			assertEquals(1, first.get("attacks_remaining"));
			fixture.dice.queue(19, 3);
			Map<String, Object> extra = fixture.engine.encounters().perform(op(), fixture.campaign, encounter, scout,
					map("kind", "ATTACK", "attack", secondSword, "target", fixture.target, "light_extra_attack", true),
					true);
			assertEquals(3, extra.get("damage"));
			assertEquals(1, extra.get("attacks_remaining"),
					"the bonus attack leaves the second ordinary attack available");
			assertEquals(Boolean.FALSE, extra.get("turn_advanced"));
			assertEquals(scout, m(m(extra.get("state")).get("turn")).get("character"));
			fixture.dice.queue(19, 2);
			Map<String, Object> last = fixture.engine.encounters().perform(op(), fixture.campaign, encounter, scout,
					map("kind", "ATTACK", "attack", firstSword, "target", fixture.target), true);
			assertEquals(Boolean.TRUE, last.get("turn_advanced"));
			assertEquals(fixture.target, m(last.get("next_turn")).get("character"));
			assertTrue(fixture.dice.exhausted());
		}
	}

	private static void giveTargetShield(Fixture fixture) {
		fixture.engine.db().mutate(Database.Mutation.of("light_attack_shield_fixture",
				Ref.id(fixture.campaign, Ref.CAMPAIGN), op(), "GM", null), tx -> {
					tx.insert("character_trait", map("character_id", Ref.id(fixture.target, Ref.CHARACTER), "kind",
							"SPELL_PREPARED", "content_ref", "srd5e:spell/shield", "payload_json", "{}"));
					tx.insert("resource_state", map("character_id", Ref.id(fixture.target, Ref.CHARACTER),
							"resource_ref", "slot:1", "current", 2, "max", 2, "recharge", "LONG_REST"));
					tx.update("encounter", Ref.id(fixture.encounter, Ref.ENCOUNTER), map("npc_reactions", "ASK"));
					return Map.of();
				});
	}

	private static Map<String, Object> resolveShield(Fixture fixture, Map<String, Object> pending, String option) {
		Map<String, Object> choice = m(pending.get("pending_choice"));
		assertEquals("SHIELD_SPELL", choice.get("kind"));
		return fixture.engine.encounters().resolveChoice(op(), fixture.campaign, (String) choice.get("transaction"),
				map("option", option));
	}

	private static Object targetHp(Fixture fixture) {
		return m(fixture.engine.characters().characterSheet(fixture.campaign, fixture.target, "PLAY").get("hp"))
				.get("current");
	}

	private static Map<String, Object> healingWord(Fixture fixture) {
		return fixture.engine.encounters().perform(op(), fixture.campaign, fixture.encounter, fixture.actor,
				map("kind", "CAST", "spell", "Healing Word", "targets", List.of(fixture.actor)), false);
	}

	private static Map<String, Object> endTurn(Fixture fixture, String actor) {
		return fixture.engine.encounters().perform(op(), fixture.campaign, fixture.encounter, actor,
				map("kind", "END_TURN"), true);
	}

	private static RpgException assertRejectedWithoutChanges(Fixture fixture, Map<String, Object> action) {
		Map<String, List<Map<String, Object>>> before = fixture.engine.db()
				.snapshot(Ref.id(fixture.campaign, Ref.CAMPAIGN));
		RpgException rejected = assertThrows(RpgException.class, () -> fixture.engine.encounters().perform(op(),
				fixture.campaign, fixture.encounter, fixture.actor, action, false));
		assertEquals(before, fixture.engine.db().snapshot(Ref.id(fixture.campaign, Ref.CAMPAIGN)),
				"rejected actions preserve resources, inventory, hit points and turn state");
		return rejected;
	}

	private static Map<String, Object> attack(Fixture fixture, String weapon, boolean extra, boolean endTurn) {
		return fixture.engine.encounters().perform(op(), fixture.campaign, fixture.encounter, fixture.actor,
				map("kind", "ATTACK", "weapon", weapon, "target", fixture.target, "light_extra_attack", extra),
				endTurn);
	}

	private static Map<String, Object> damageRoll(Map<String, Object> attack) {
		return m(list(attack.get("breakdown")).get(0).get("roll"));
	}

	private record Fixture(Engine engine, ScriptedRollService dice, String campaign, String actor, String target,
			String encounter, String dagger, String handaxe, String longsword) implements AutoCloseable {
		@Override
		public void close() {
			engine.close();
		}
	}

	private static Fixture fixture(int strength) throws Exception {
		return fixture(strength, map("item", "Handaxe"), false, false);
	}

	private static Fixture fixture(
		int strength, Map<String, Object> handaxeSpec, boolean fightingStyle, boolean healingWord) throws Exception {
		Engine engine = TestCampaigns.engine(TestCampaigns.tempDb("light-weapons"));
		try {
			String campaign = (String) engine.campaigns().create(op(), null, null).get("campaign");
			engine.campaigns().updateSetup(op(), campaign, null,
					map("content_profile", "PEGI_16", "experience", "SURPRISE_ME", "rules", Map.of(), "continuation",
							"CHECKPOINT", "party", "SURPRISE_ME", "adventure", map("premise", "a broken observatory",
									"opening_location", "Tower", "immediate_goal", "find the key")));
			Map<String, Object> feat = healingWord
					? map("feat", "Magic Initiate", "spell_list", "cleric", "ability", "WIS", "cantrips",
							List.of("Guidance", "Light"), "spell", "Healing Word")
					: map("feat", "Skilled", "proficiencies", List.of("Nature", "Survival", "Medicine"));
			String actor = (String) engine.characters()
					.createDraft(op(), campaign,
							map("name", "Vess", "species", "Human", "class", "Fighter", "ability_scores",
									map("STR", strength, "DEX", 14, "CON", 13, "INT", 12, "WIS", 10, "CHA",
											strength == 8 ? 15 : 8),
									"skills", List.of("Athletics", "Perception"), "personality", "patient",
									"background", "Criminal", "background_ability_scores", map("CON", 2, "DEX", 1),
									"species_skill", "Insight", "origin_feat", feat),
							true)
					.get("character");
			engine.characters().commitDraft(op(), campaign, actor, null);
			engine.campaigns().commitSetup(op(), campaign, null);
			engine.sessions().bootstrap(op(), campaign, null);
			String dagger = grant(engine, campaign, actor, map("item", "Dagger"));
			String handaxe = grant(engine, campaign, actor, handaxeSpec);
			String longsword = grant(engine, campaign, actor, map("item", "Longsword"));
			dagger = (String) engine.inventory().equip(op(), campaign, actor, dagger, true).get("entry");
			handaxe = (String) engine.inventory().equip(op(), campaign, actor, handaxe, true).get("entry");
			if (fightingStyle) {
				engine.db().mutate(Database.Mutation.of("two_weapon_fighting_fixture", Ref.id(campaign, Ref.CAMPAIGN),
						op(), "GM", null), tx -> {
							tx.insert("character_trait", map("character_id", Ref.id(actor, Ref.CHARACTER), "kind",
									"FEAT", "content_ref", "srd5e:feat/two-weapon-fighting", "payload_json", "{}"));
							return Map.of();
						});
			}
			String target = (String) engine.runtime()
					.materialize(op(), campaign, "Ogre", "Brawn", null, null, null, null, false).get("character");
			ScriptedRollService dice = (ScriptedRollService) engine.roller();
			dice.queue(20, 100, 1, 1);
			Map<String, Object> started = engine.encounters().start(op(), campaign,
					map("party", List.of(actor), "foes", List.of(target)), null, null, null, null, null);
			assertEquals(actor, m(started.get("turn")).get("character"));
			assertTrue(dice.exhausted());
			return new Fixture(engine, dice, campaign, actor, target, (String) started.get("encounter"), dagger,
					handaxe, longsword);
		} catch (Exception | Error failure) {
			engine.close();
			throw failure;
		}
	}

	private static String grant(Engine engine, String campaign, String actor, Map<String, Object> item) {
		return (String) list(
				engine.inventory().grantLoot(op(), campaign, actor, List.of(item), null, "QUEST", null).get("granted"))
				.get(0).get("entry");
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
