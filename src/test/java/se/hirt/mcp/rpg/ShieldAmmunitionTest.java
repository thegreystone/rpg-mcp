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
import se.hirt.mcp.rpg.persistence.Database;
import se.hirt.mcp.rpg.protocol.Ref;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static se.hirt.mcp.rpg.TestCampaigns.map;
import static se.hirt.mcp.rpg.TestCampaigns.op;

/** A Shield decision resumes the ammunition-enhanced attack that was already rolled. */
class ShieldAmmunitionTest {

	@Test
	void decliningShieldKeepsTheConsumedMagicArrowsBonusesAndExtraDice() throws Exception {
		try (Fixture fixture = fixture(false, 1)) {
			int hpBefore = hp(fixture);
			fixture.dice.queue(8);
			Map<String, Object> pending = shoot(fixture);
			assertEquals(Boolean.FALSE, pending.get("resolved"));
			assertEquals(13, m(pending.get("attack_roll")).get("total"), "DEX +2, proficiency +2 and the arrow's +1");
			assertEquals(0, arrowsRemaining(fixture), "the last enchanted arrow is consumed before the decision");
			fixture.dice.queue(3, 4);
			Map<String, Object> attack = m(resolve(fixture, pending, "DECLINE").get("attack"));
			assertEquals(10, attack.get("damage"), "1d6 + DEX + the arrow's +1, plus its 1d4 fire");
			assertEquals("1d6+3", m(parts(attack).get(0).get("roll")).get("expression"));
			assertEquals("1d4", m(parts(attack).get(1).get("roll")).get("expression"));
			assertEquals("fire", parts(attack).get(1).get("type"));
			assertEquals(hpBefore - 10, hp(fixture));
			assertEquals(0, arrowsRemaining(fixture));
			assertTrue(fixture.dice.exhausted(), "both damage dice are rolled exactly once after the decision");
		}
	}

	@Test
	void decliningShieldStacksTheMagicBowsAndArrowsDamageWithoutAddingTheirBonusesTwice() throws Exception {
		try (Fixture fixture = fixture(true, 1)) {
			int hpBefore = hp(fixture);
			fixture.dice.queue(6);
			Map<String, Object> pending = shoot(fixture);
			assertEquals(13, m(pending.get("attack_roll")).get("total"));
			assertEquals(7, m(pending.get("attack_roll")).get("modifier"),
					"DEX +2, proficiency +2, bow +2 and arrow +1");
			fixture.dice.queue(3, 2, 4);
			Map<String, Object> attack = m(resolve(fixture, pending, "DECLINE").get("attack"));
			assertEquals(14, attack.get("damage"));
			assertEquals(13, m(attack.get("attack_roll")).get("total"),
					"resolving the choice retains the original attack roll");
			assertEquals("1d6+5", m(parts(attack).get(0).get("roll")).get("expression"));
			assertEquals(List.of("piercing", "cold", "fire"), parts(attack).stream().map(p -> p.get("type")).toList());
			assertEquals(hpBefore - 14, hp(fixture));
			assertEquals(0, arrowsRemaining(fixture));
			assertTrue(fixture.dice.exhausted());
		}
	}

	@Test
	void castingShieldConsumesOneArrowAndReplayingEitherOperationChangesNothing() throws Exception {
		try (Fixture fixture = fixture(false, 2)) {
			int hpBefore = hp(fixture);
			String attackOperation = op();
			fixture.dice.queue(8);
			Map<String, Object> pending = shoot(fixture, attackOperation);
			assertEquals(1, arrowsRemaining(fixture));
			Map<String, List<Map<String, Object>>> parked = snapshot(fixture);
			Map<String, Object> replayedAttack = shoot(fixture, attackOperation);
			assertEquals(Boolean.TRUE, replayedAttack.get("replayed"));
			assertEquals(m(pending.get("pending_choice")).get("transaction"),
					m(replayedAttack.get("pending_choice")).get("transaction"));
			assertEquals(parked, snapshot(fixture), "replaying the parked attack does not consume the second arrow");
			String transaction = (String) m(pending.get("pending_choice")).get("transaction");
			String choiceOperation = op();
			Map<String, Object> result = fixture.engine.encounters().resolveChoice(choiceOperation, fixture.campaign,
					transaction, map("option", "CAST_SHIELD"));
			Map<String, Object> attack = m(result.get("attack"));
			assertEquals(Boolean.FALSE, attack.get("hit"));
			assertEquals(13, m(attack.get("attack_roll")).get("total"));
			assertEquals(16, attack.get("target_armor_class"));
			assertEquals(hpBefore, hp(fixture));
			assertEquals(1, arrowsRemaining(fixture));
			assertEquals(1, shieldSlots(fixture), "one slot is spent on Shield");
			Map<String, List<Map<String, Object>>> resolved = snapshot(fixture);
			Map<String, Object> replayedChoice = fixture.engine.encounters().resolveChoice(choiceOperation,
					fixture.campaign, transaction, map("option", "CAST_SHIELD"));
			assertEquals(Boolean.TRUE, replayedChoice.get("replayed"));
			assertEquals(Boolean.FALSE, m(replayedChoice.get("attack")).get("hit"));
			assertEquals(resolved, snapshot(fixture),
					"replaying the choice spends neither ammunition nor a second slot");
			assertTrue(fixture.dice.exhausted(), "a Shield miss never rolls damage dice or rerolls the attack");
		}
	}

	@Test
	void aPersistedShieldChoiceKeepsTheDeletedArrowsEffectiveMagicAfterRestart() throws Exception {
		Fixture original = fixture(false, 1);
		Map<String, Object> pending;
		int hpBefore;
		try (original) {
			hpBefore = hp(original);
			original.dice.queue(8);
			pending = shoot(original);
			assertEquals(0, arrowsRemaining(original));
		}
		try (Engine engine = TestCampaigns.engine(original.database)) {
			Fixture reopened = original.withEngine(engine);
			assertEquals("WAITING_CHOICE", reopened.engine.encounters()
					.encounterState(reopened.campaign, reopened.encounter, 3).get("status"));
			reopened.dice.queue(3, 4);
			Map<String, Object> attack = m(resolve(reopened, pending, "DECLINE").get("attack"));
			assertEquals(10, attack.get("damage"));
			assertEquals("1d6+3", m(parts(attack).get(0).get("roll")).get("expression"));
			assertEquals("fire", parts(attack).get(1).get("type"));
			assertEquals(hpBefore - 10, hp(reopened));
			assertEquals(0, arrowsRemaining(reopened));
			assertTrue(reopened.dice.exhausted());
		}
	}

	@Test
	void decliningShieldDoesNotRestoreAnUnattunedArrowsInactiveMagic() throws Exception {
		try (Fixture fixture = fixture(false, 1, true)) {
			int hpBefore = hp(fixture);
			fixture.dice.queue(9);
			Map<String, Object> pending = shoot(fixture);
			assertEquals(13, m(pending.get("attack_roll")).get("total"));
			assertNull(m(pending.get("ammunition")).get("bonus"));
			assertNull(m(pending.get("ammunition")).get("extra_damage"));
			fixture.dice.queue(3);
			Map<String, Object> attack = m(resolve(fixture, pending, "DECLINE").get("attack"));
			assertEquals(5, attack.get("damage"));
			assertEquals(1, parts(attack).size());
			assertEquals("1d6+2", m(parts(attack).get(0).get("roll")).get("expression"));
			assertNull(m(attack.get("ammunition")).get("bonus"));
			assertNull(m(attack.get("ammunition")).get("extra_damage"));
			assertEquals(hpBefore - 5, hp(fixture));
			assertEquals(0, arrowsRemaining(fixture));
			assertTrue(fixture.dice.exhausted());
		}
	}

	private record Fixture(Engine engine, ScriptedRollService dice, Path database, String campaign, String actor,
			String target, String encounter, String bow, String arrow) implements AutoCloseable {
		Fixture withEngine(Engine replacement) {
			return new Fixture(replacement, (ScriptedRollService) replacement.roller(), database, campaign, actor,
					target, encounter, bow, arrow);
		}

		@Override
		public void close() {
			engine.close();
		}
	}

	private static Fixture fixture(boolean magicBow, int arrowCount) throws Exception {
		return fixture(magicBow, arrowCount, false);
	}

	private static Fixture fixture(boolean magicBow, int arrowCount, boolean arrowAttunement) throws Exception {
		Path database = TestCampaigns.tempDb("shield-ammunition");
		Engine engine = TestCampaigns.engine(database);
		try {
			String campaign = (String) engine.campaigns().create(op(), null, null).get("campaign");
			engine.campaigns().updateSetup(op(), campaign, null,
					map("content_profile", "PEGI_16", "experience", "SURPRISE_ME", "rules", Map.of(), "continuation",
							"CHECKPOINT", "party", "SURPRISE_ME", "adventure",
							map("premise", "an ambush", "opening_location", "Tower", "immediate_goal", "escape")));
			String actor = (String) engine.characters().createDraft(op(), campaign,
					map("name", "Vess", "species", "Human", "class", "Fighter", "ability_scores",
							map("STR", 15, "DEX", 14, "CON", 13, "INT", 12, "WIS", 10, "CHA", 8), "skills",
							List.of("Athletics", "Perception"), "personality", "patient", "background", "Criminal",
							"background_ability_scores", map("CON", 2, "DEX", 1), "species_skill", "Insight",
							"origin_feat",
							map("feat", "Skilled", "proficiencies", List.of("Nature", "Survival", "Medicine"))),
					true).get("character");
			engine.characters().commitDraft(op(), campaign, actor, null);
			engine.campaigns().commitSetup(op(), campaign, null);
			engine.sessions().bootstrap(op(), campaign, null);
			Map<String, Object> bowSpec = magicBow
					? map("item", "Shortbow", "magic",
							map("name", "Frost Bow", "rarity", "RARE", "bonus", 2, "damage_bonus_dice", "1d4",
									"damage_type", "cold", "text", "+2 and 1d4 cold on a hit."))
					: map("item", "Shortbow");
			String bow = grant(engine, campaign, actor, bowSpec);
			engine.inventory().equip(op(), campaign, actor, bow, true);
			String arrow = grant(engine, campaign, actor,
					map("item", "Arrow", "quantity", arrowCount, "magic",
							map("name", "Ember Arrow", "rarity", "RARE", "bonus", 1, "attunement", arrowAttunement,
									"damage_bonus_dice", "1d4", "damage_type", "fire", "text",
									"+1 and 1d4 fire on a hit.")));
			String target = (String) engine.runtime()
					.materialize(op(), campaign, "Ogre", "Brawn", null, null, null, null, false).get("character");
			engine.db().mutate(
					Database.Mutation.of("shield_ammunition_fixture", Ref.id(campaign, Ref.CAMPAIGN), op(), "GM", null),
					tx -> {
						tx.insert("character_trait", map("character_id", Ref.id(target, Ref.CHARACTER), "kind",
								"SPELL_PREPARED", "content_ref", "srd5e:spell/shield", "payload_json", "{}"));
						tx.insert("resource_state", map("character_id", Ref.id(target, Ref.CHARACTER), "resource_ref",
								"slot:1", "current", 2, "max", 2, "recharge", "LONG_REST"));
						return Map.of();
					});
			ScriptedRollService dice = (ScriptedRollService) engine.roller();
			dice.queue(20, 1);
			Map<String, Object> started = engine.encounters().start(op(), campaign,
					map("party", List.of(actor), "foes", List.of(target)), null, null, null, null, null);
			String encounter = (String) started.get("encounter");
			engine.db().mutate(Database.Mutation.of("shield_ammunition_reactions_fixture",
					Ref.id(campaign, Ref.CAMPAIGN), op(), "GM", null), tx -> {
						tx.update("encounter", Ref.id(encounter, Ref.ENCOUNTER), map("npc_reactions", "ASK"));
						return Map.of();
					});
			assertEquals(actor, m(started.get("turn")).get("character"));
			assertTrue(dice.exhausted());
			return new Fixture(engine, dice, database, campaign, actor, target, encounter, bow, arrow);
		} catch (Exception | Error failure) {
			engine.close();
			throw failure;
		}
	}

	private static Map<String, Object> shoot(Fixture fixture) {
		return shoot(fixture, op());
	}

	private static Map<String, Object> shoot(Fixture fixture, String operation) {
		return fixture.engine.encounters().perform(operation, fixture.campaign, fixture.encounter, fixture.actor,
				map("kind", "ATTACK", "weapon", fixture.bow, "ammunition", "Ember Arrow", "target", fixture.target),
				false);
	}

	private static Map<String, Object> resolve(Fixture fixture, Map<String, Object> pending, String option) {
		Map<String, Object> choice = m(pending.get("pending_choice"));
		assertEquals("SHIELD_SPELL", choice.get("kind"));
		return fixture.engine.encounters().resolveChoice(op(), fixture.campaign, (String) choice.get("transaction"),
				map("option", option));
	}

	private static int arrowsRemaining(Fixture fixture) {
		return fixture.engine.db().read(tx -> tx.find("inventory_entry", Ref.id(fixture.arrow, Ref.INVENTORY))
				.map(row -> row.intOr("quantity", 0)).orElse(0));
	}

	private static int hp(Fixture fixture) {
		return ((Number) m(
				fixture.engine.characters().characterSheet(fixture.campaign, fixture.target, "PLAY").get("hp"))
				.get("current")).intValue();
	}

	private static int shieldSlots(Fixture fixture) {
		return fixture.engine.db()
				.read(tx -> tx.queryOne("SELECT * FROM resource_state WHERE character_id = ? AND resource_ref = ?",
						Ref.id(fixture.target, Ref.CHARACTER), "slot:1").orElseThrow().intOr("current", 0));
	}

	private static Map<String, List<Map<String, Object>>> snapshot(Fixture fixture) {
		return fixture.engine.db().snapshot(Ref.id(fixture.campaign, Ref.CAMPAIGN));
	}

	private static String grant(Engine engine, String campaign, String actor, Map<String, Object> item) {
		return (String) parts(
				engine.inventory().grantLoot(op(), campaign, actor, List.of(item), null, "QUEST", null).get("granted"))
				.get(0).get("entry");
	}

	private static List<Map<String, Object>> parts(Map<String, Object> attack) {
		return parts(attack.get("breakdown"));
	}

	@SuppressWarnings("unchecked")
	private static List<Map<String, Object>> parts(Object value) {
		return (List<Map<String, Object>>) value;
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> m(Object value) {
		return (Map<String, Object>) value;
	}
}
