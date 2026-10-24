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
import se.hirt.mcp.rpg.dice.ScriptedRollService;
import se.hirt.mcp.rpg.persistence.Database;
import se.hirt.mcp.rpg.protocol.ErrorCode;
import se.hirt.mcp.rpg.protocol.Ref;
import se.hirt.mcp.rpg.protocol.RpgException;
import se.hirt.mcp.rpg.rules.Combat;
import se.hirt.mcp.rpg.rules.Effects;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static se.hirt.mcp.rpg.TestCampaigns.committedCampaign;
import static se.hirt.mcp.rpg.TestCampaigns.engine;
import static se.hirt.mcp.rpg.TestCampaigns.map;
import static se.hirt.mcp.rpg.TestCampaigns.op;
import static se.hirt.mcp.rpg.TestCampaigns.tempDb;

/**
 * Pins the death-saving-throw bookkeeping, the damage order of application and the condition matrix
 * exactly as SRD 5.2.1 states them (pages 17-18 "Dropping to 0 Hit Points", "Order of Application";
 * pages 177-191 "Rules Glossary" conditions and the Dodge action). Each test quotes the operative
 * sentence and asserts the rule's outcome through the real services; the fixtures are the
 * EXECUTION_EXAMPLE campaign (Richard Greystone, a human sorcerer) against an Ogre or a Bandit,
 * with every d20 scripted. Attack rolls that only prove the roll's shape are scripted as natural
 * 1s, which miss under any advantage form and so never consume damage dice.
 */
class SrdDeathAndConditionTest {

	private static final String PC = "character:1";
	private static final String PC_NAME = "Richard Greystone";

	/**
	 * SRD 5.2.1 p.17 "Death Saving Throws": "The successes and failures don't need to be
	 * consecutive; keep track of both until you collect three of a kind. The number of both is
	 * reset to zero when you regain any Hit Points or become Stable." SRD p.18 "Damage at 0 Hit
	 * Points": "If you take any damage while you have 0 Hit Points, you suffer a Death Saving Throw
	 * failure." and "Stabilizing a Character": "If the creature takes damage, it stops being Stable
	 * and starts making Death Saving Throws again." A creature stabilised at two failures therefore
	 * has one failure after the next hit, not three.
	 */
	@Test
	void becomingStableResetsBothDeathSaveCounters() throws Exception {
		// The ogre acts first and stands in another zone, so its club never lands an automatic critical hit.
		try (Arena a = arena("stable-reset", false, "near", "far", List.of())) {
			dropToZero(a.engine, a.campaign, PC);
			// Two failures, then three successes: 5, 5, 12, 12, 12.
			Map<String, Object> last = null;
			for (int natural : List.of(5, 5, 12, 12, 12)) {
				a.dice.queue(natural);
				last = a.engine.encounters().perform(op(), a.campaign, a.encounter, a.ogre, map("kind", "END_TURN"),
						true);
			}
			assertEquals("STABLE", list(last.get("death_saves")).get(0).get("outcome"));
			assertTrue(a.dice.exhausted(), "each ogre turn rolled exactly one death save for the dying sorcerer");
			Map<String, Object> saves = deathSaves(a.engine, a.campaign, PC);
			assertEquals(Boolean.TRUE, saves.get("stable"));
			assertEquals(0, saves.get("successes"), "becoming Stable resets the successes to zero: " + saves);
			assertEquals(0, saves.get("failures"), "becoming Stable resets the failures to zero: " + saves);

			// The ogre hits the stable, unconscious sorcerer with a non-critical blow from across the room.
			a.dice.queue(19, 19, 1, 1);
			Map<String, Object> hit = a.engine.encounters().perform(op(), a.campaign, a.encounter, a.ogre,
					map("kind", "ATTACK", "attack", "Greatclub", "target", PC), false);
			assertEquals(Boolean.TRUE, hit.get("hit"), hit.toString());
			assertEquals(Boolean.FALSE, hit.get("critical"), "a natural 19 from another zone is no critical hit");
			Map<String, Object> after = deathSaves(a.engine, a.campaign, PC);
			assertEquals("DYING", sheet(a.engine, a.campaign, PC).get("life_state"),
					"one failure after a reset cannot kill: " + after);
			assertEquals(1, after.get("failures"), "damage at 0 HP is one failure on a fresh count: " + after);
			assertEquals(Boolean.FALSE, after.get("stable"), "damage ends Stable");
		}
	}

	/**
	 * SRD 5.2.1 p.18 "Damage at 0 Hit Points": "If you take any damage while you have 0 Hit Points,
	 * you suffer a Death Saving Throw failure. If the damage is from a Critical Hit, you suffer two
	 * failures instead."
	 */
	@Test
	void damageAtZeroHitPointsCountsOneFailureOrTwoOnACriticalHit() throws Exception {
		try (Arena a = arena("zero-hp-damage", false, "near", "far", List.of())) {
			dropToZero(a.engine, a.campaign, PC);
			// An unconscious target grants Advantage: two d20s, then 2d8 for the club.
			a.dice.queue(19, 19, 1, 1);
			Map<String, Object> hit = a.engine.encounters().perform(op(), a.campaign, a.encounter, a.ogre,
					map("kind", "ATTACK", "attack", "Greatclub", "target", PC), false);
			assertEquals(Boolean.TRUE, hit.get("hit"), hit.toString());
			assertEquals(Boolean.FALSE, hit.get("critical"));
			assertEquals("DYING", hit.get("life_state"));
			assertEquals(1, deathSaves(a.engine, a.campaign, PC).get("failures"), "one failure for ordinary damage");
			// Richard's own turn: a death save of 10 succeeds and leaves the failure count alone.
			a.dice.queue(10);
			a.engine.encounters().perform(op(), a.campaign, a.encounter, a.ogre, map("kind", "END_TURN"), true);
			assertEquals(1, deathSaves(a.engine, a.campaign, PC).get("failures"));
			assertEquals(1, deathSaves(a.engine, a.campaign, PC).get("successes"));

			// A natural 20: the critical hit doubles the club's dice (4d8) and counts two failures: 1 + 2 = 3, dead.
			a.dice.queue(20, 20, 1, 1, 1, 1);
			Map<String, Object> critical = a.engine.encounters().perform(op(), a.campaign, a.encounter, a.ogre,
					map("kind", "ATTACK", "attack", "Greatclub", "target", PC), false);
			assertEquals(Boolean.TRUE, critical.get("critical"), critical.toString());
			assertEquals("DEAD", critical.get("life_state"), "two failures from a critical hit complete the three");
			assertEquals("DEAD", sheet(a.engine, a.campaign, PC).get("life_state"));
			assertTrue(a.dice.exhausted(), "the critical hit rolled exactly four damage dice");
		}
	}

	/**
	 * SRD 5.2.1 p.191 "Unconscious [Condition]": "Inert. You have the Incapacitated and Prone
	 * conditions, and you drop whatever you're holding. When this condition ends, you remain
	 * Prone." Healing ends the condition (p.17 "Falling Unconscious": "until you regain any Hit
	 * Points"), so the revived creature is Prone without the GM adding it.
	 */
	@Test
	void anUnconsciousCreatureThatRegainsHitPointsRemainsProne() throws Exception {
		try (Engine engine = engine(tempDb("unconscious-prone"))) {
			String campaign = committedCampaign(engine);
			engine.sessions().bootstrap(op(), campaign, null);
			dropToZero(engine, campaign, PC);
			assertTrue(conditionNames(sheet(engine, campaign, PC)).contains("UNCONSCIOUS"));
			Map<String, Object> healed = engine.runtime().applyRuntimeChange(op(), campaign, PC,
					map("kind", "HEAL", "amount", 3, "reason", "a potion"));
			assertEquals(Boolean.TRUE, healed.get("revived"));
			Map<String, Object> after = sheet(engine, campaign, PC);
			assertEquals("ALIVE", after.get("life_state"));
			List<String> conditions = conditionNames(after);
			assertFalse(conditions.contains("UNCONSCIOUS"), "regaining HP ends Unconscious: " + conditions);
			assertTrue(conditions.contains("PRONE"), "when Unconscious ends the creature remains Prone: " + conditions);
		}
	}

	/**
	 * SRD 5.2.1 p.18 "Rolling a 1 or 20": "If you roll a 20 on the d20, you regain 1 Hit Point."
	 * and p.191 "Unconscious [Condition]": "When this condition ends, you remain Prone."
	 */
	@Test
	void aNaturalTwentyDeathSaveRegainsOneHitPointAndLeavesTheCreatureProne() throws Exception {
		try (Arena a = arena("natural-twenty-prone", false, "near", "far", List.of())) {
			dropToZero(a.engine, a.campaign, PC);
			a.dice.queue(20);
			Map<String, Object> ended = a.engine.encounters().perform(op(), a.campaign, a.encounter, a.ogre,
					map("kind", "END_TURN"), true);
			assertEquals("REGAIN_1_HP", list(ended.get("death_saves")).get(0).get("outcome"));
			Map<String, Object> after = sheet(a.engine, a.campaign, PC);
			assertEquals("ALIVE", after.get("life_state"));
			assertEquals(1, m(after.get("hp")).get("current"));
			List<String> conditions = conditionNames(after);
			assertFalse(conditions.contains("UNCONSCIOUS"), conditions.toString());
			assertTrue(conditions.contains("PRONE"), "the revived sorcerer is still on the ground: " + conditions);
		}
	}

	/**
	 * SRD 5.2.1 p.184 "Incapacitated [Condition]": "No Concentration. Your Concentration is
	 * broken." Paralyzed, Stunned, Unconscious and Petrified each include Incapacitated (p.185,
	 * p.189, p.191, p.186), so gaining any of them ends the caster's Concentration. Detect Magic is
	 * always prepared through Richard's Magic Initiate feat (Concentration, up to 10 minutes).
	 */
	@Test
	void gainingAnIncapacitatingConditionBreaksConcentration() throws Exception {
		try (Engine engine = engine(tempDb("incapacitated-concentration"))) {
			String campaign = committedCampaign(engine);
			engine.sessions().bootstrap(op(), campaign, null);
			for (String condition : List.of("PARALYZED", "STUNNED", "UNCONSCIOUS", "INCAPACITATED", "PETRIFIED")) {
				engine.spells().cast(op(), campaign, PC, "Detect Magic", null, null, null);
				assertEquals(1, concentratingOn(engine, campaign, PC).size(), "Detect Magic requires Concentration");
				addCondition(engine, campaign, PC, condition);
				assertTrue(concentratingOn(engine, campaign, PC).isEmpty(),
						condition + " includes Incapacitated, which breaks Concentration: "
								+ concentratingOn(engine, campaign, PC));
				removeCondition(engine, campaign, PC, condition);
				// A Long Rest returns the free cast and the slots for the next round of the loop; 16 hours must
				// pass after the previous one (SRD 5.2.1 "Long Rest").
				engine.sessions().advanceTime(op(), campaign, 16 * 60, "a day between rests");
				engine.rest().rest(op(), campaign, "LONG", null, List.of(PC));
			}
		}
	}

	/**
	 * SRD 5.2.1 p.177 "Blinded [Condition]": "Attacks Affected. Attack rolls against you have
	 * Advantage, and your attack rolls have Disadvantage." p.187 "Restrained [Condition]": the same
	 * sentence, plus "Saving Throws Affected. You have Disadvantage on Dexterity saving throws."
	 */
	@Test
	void blindedAndRestrainedAttackersHaveDisadvantageAndTargetsGrantAdvantage() throws Exception {
		try (Arena a = arena("blinded-restrained", true, "near", "near", List.of("Dagger"))) {
			for (String condition : List.of("BLINDED", "RESTRAINED")) {
				assertConditionAffectsAttacks(a, condition, "2d20kh1", "2d20kl1");
			}
		}
	}

	/**
	 * SRD 5.2.1 p.186 "Poisoned [Condition]": "Ability Checks and Attacks Affected. You have
	 * Disadvantage on attack rolls and ability checks."
	 */
	@Test
	void poisonedAttackersHaveDisadvantageOnAttackRollsAndAbilityChecks() throws Exception {
		try (Arena a = arena("poisoned", true, "near", "near", List.of("Dagger"))) {
			addCondition(a.engine, a.campaign, PC, "POISONED");
			Map<String, Object> attack = weaponAttack(a, "Dagger", a.ogre);
			assertTrue(expression(attack).startsWith("2d20kl1"),
					"a poisoned attacker rolls with Disadvantage: " + expression(attack));
			assertEquals("DISADVANTAGE", attack.get("advantage"));
			// Checks are resolved outside the fight so the roll is not gated by the turn order.
			a.engine.encounters().end(op(), a.campaign, a.encounter, "OTHER", "the ogre lumbers off");
			a.dice.queue(1, 1);
			Map<String, Object> check = a.engine.checks().resolveCheck(op(), a.campaign, PC, "ABILITY_CHECK", "STR",
					null, 10, null, "forcing a door while poisoned");
			assertEquals("DISADVANTAGE", check.get("advantage"), "a poisoned creature's ability checks: " + check);
			assertTrue(String.valueOf(m(check.get("roll")).get("expression")).startsWith("2d20kl1"),
					String.valueOf(m(check.get("roll")).get("expression")));
		}
	}

	/**
	 * SRD 5.2.1 p.186 "Prone [Condition]": "Attacks Affected. You have Disadvantage on attack
	 * rolls. An attack roll against you has Advantage if the attacker is within 5 feet of you.
	 * Otherwise, that attack roll has Disadvantage." The engine equates "within 5 feet" with
	 * sharing an encounter zone (Combat.withinFiveFeet), as it already does for automatic critical
	 * hits.
	 */
	@Test
	void proneGrantsAdvantageWithinFiveFeetAndDisadvantageBeyond() throws Exception {
		try (Arena a = arena("prone", true, "far", "near", List.of("Dagger", "Shortbow", "Arrow"))) {
			addCondition(a.engine, a.campaign, a.ogre, "PRONE");
			Map<String, Object> ranged = weaponAttack(a, "Shortbow", a.ogre);
			assertTrue(expression(ranged).startsWith("2d20kl1"),
					"an arrow from another zone at a prone ogre has Disadvantage: " + expression(ranged));
			assertEquals("DISADVANTAGE", ranged.get("advantage"));
			backToPc(a);

			// Closing to the ogre's zone puts the sorcerer within 5 feet; moving toward it provokes nothing.
			a.engine.encounters().perform(op(), a.campaign, a.encounter, PC, map("kind", "MOVE", "zone", "near"),
					false);
			Map<String, Object> melee = weaponAttack(a, "Dagger", a.ogre);
			assertTrue(expression(melee).startsWith("2d20kh1"),
					"a dagger within 5 feet of a prone ogre has Advantage: " + expression(melee));
			assertEquals("ADVANTAGE", melee.get("advantage"));
			backToPc(a);

			removeCondition(a.engine, a.campaign, a.ogre, "PRONE");
			addCondition(a.engine, a.campaign, PC, "PRONE");
			Map<String, Object> fromTheGround = weaponAttack(a, "Dagger", a.ogre);
			assertTrue(expression(fromTheGround).startsWith("2d20kl1"),
					"a prone attacker has Disadvantage: " + expression(fromTheGround));
			assertEquals("DISADVANTAGE", fromTheGround.get("advantage"));
		}
	}

	/**
	 * SRD 5.2.1 p.182 "Grappled [Condition]": "Attacks Affected. You have Disadvantage on attack
	 * rolls against any target other than the grappler." The GM's ADD_CONDITION carries no
	 * grappler, so the condition is written the way the engine's own effects name their origin: the
	 * active_effect row's source_character_id is the grappler (Effects.add's sourceCharacterId).
	 */
	@Test
	void grappledCreaturesHaveDisadvantageAgainstAnyoneButTheGrappler() throws Exception {
		try (Engine engine = engine(tempDb("grappled"))) {
			String campaign = committedCampaign(engine);
			engine.sessions().bootstrap(op(), campaign, null);
			engine.inventory().grantLoot(op(), campaign, PC, List.of(map("item", "Dagger")), null, "QUEST", null);
			String ogre = (String) engine.runtime()
					.materialize(op(), campaign, "Ogre", "Grappler", null, null, null, null, false).get("character");
			String bandit = (String) engine.runtime()
					.materialize(op(), campaign, "Bandit", "Bystander", null, null, null, null, false).get("character");
			ScriptedRollService dice = (ScriptedRollService) engine.roller();
			dice.queue(20, 1, 1);
			Map<String, Object> started = engine.encounters().start(op(), campaign,
					map("party", List.of(PC), "enemy", List.of(ogre, bandit)), null,
					map(PC, "near", ogre, "near", bandit, "near"), null, null, null);
			assertEquals(PC, m(started.get("turn")).get("character"));
			assertTrue(dice.exhausted());
			long campaignId = Ref.id(campaign, Ref.CAMPAIGN);
			engine.db().mutate(Database.Mutation.of("grapple_fixture", campaignId, op(), "GM", null), tx -> {
				Effects.add(tx, campaignId, Ref.id(PC, Ref.CHARACTER), Ref.id(ogre, Ref.CHARACTER), null,
						"grappled by Grappler", RuntimeService.conditionRef("GRAPPLED"), null, null, null, "GRAPPLED",
						"GM");
				return Map.of();
			});
			assertTrue(conditionNames(sheet(engine, campaign, PC)).contains("GRAPPLED"));
			Arena a = new Arena(engine, dice, campaign, PC, ogre, (String) started.get("encounter"), started);

			Map<String, Object> atTheBystander = weaponAttack(a, "Dagger", bandit);
			assertTrue(expression(atTheBystander).startsWith("2d20kl1"),
					"attacking anyone but the grappler has Disadvantage: " + expression(atTheBystander));
			assertEquals("DISADVANTAGE", atTheBystander.get("advantage"));
			backToPc(a);
			Map<String, Object> atTheGrappler = weaponAttack(a, "Dagger", ogre);
			assertTrue(expression(atTheGrappler).startsWith("1d20"),
					"attacking the grappler itself is a plain roll: " + expression(atTheGrappler));
			assertEquals("NONE", atTheGrappler.get("advantage"));
		}
	}

	/**
	 * SRD 5.2.1 p.184 "Invisible [Condition]": "Attacks Affected. Attack rolls against you have
	 * Disadvantage, and your attack rolls have Advantage." The "if a creature can somehow see you"
	 * exception is the GM's call and is not modelled here.
	 */
	@Test
	void invisibleAttackersHaveAdvantageAndAreAttackedWithDisadvantage() throws Exception {
		try (Arena a = arena("invisible", true, "near", "near", List.of("Dagger"))) {
			assertConditionAffectsAttacks(a, "INVISIBLE", "2d20kl1", "2d20kh1");
		}
	}

	/**
	 * SRD 5.2.1 p.186 "Petrified [Condition]": "Resist Damage. You have Resistance to all damage."
	 * and "Poison Immunity. You have Immunity to the Poisoned condition." p.17 "Resistance and
	 * Vulnerability": "If you have Resistance to a damage type, damage of that type is halved
	 * against you" (rounded down).
	 */
	@Test
	void petrifiedCreaturesResistAllDamageAndAreImmuneToPoison() throws Exception {
		try (Engine engine = engine(tempDb("petrified"))) {
			String campaign = committedCampaign(engine);
			engine.sessions().bootstrap(op(), campaign, null);
			String bandit = (String) engine.runtime()
					.materialize(op(), campaign, "Bandit", "Statue", null, null, null, null, false).get("character");
			addCondition(engine, campaign, bandit, "PETRIFIED");
			int before = ((Number) m(sheet(engine, campaign, bandit).get("hp")).get("current")).intValue();
			Map<String, Object> struck = engine.runtime().applyRuntimeChange(op(), campaign, bandit,
					map("kind", "DAMAGE", "amount", 7, "damage_type", "slashing", "reason", "a sword on stone"));
			assertEquals(3, struck.get("damage"), "7 slashing halved and rounded down: " + struck);
			assertEquals(before - 3, struck.get("hp_after"), "a petrified creature resists all damage: " + struck);

			try {
				engine.runtime().applyRuntimeChange(op(), campaign, bandit,
						map("kind", "ADD_CONDITION", "condition", "POISONED", "reason", "venom on the stone"));
			} catch (RpgException refused) {
				// Refusing the condition is one acceptable way to report the immunity.
			}
			List<String> conditions = conditionNames(sheet(engine, campaign, bandit));
			assertFalse(conditions.contains("POISONED"), "a petrified creature is immune to Poisoned: " + conditions);
		}
	}

	/**
	 * SRD 5.2.1 p.180 "Dodge [Action]": "until the start of your next turn, any attack roll made
	 * against you has Disadvantage if you can see the attacker ... You lose these benefits if you
	 * have the Incapacitated condition or if your Speed is 0." Paralyzed gives both (p.185), so the
	 * attack is made with the plain Advantage a paralyzed target grants, not cancelled to 1d20.
	 */
	@Test
	void dodgeIsLostWhileIncapacitatedOrAtSpeedZero() throws Exception {
		try (Arena a = arena("dodge-lost", false, "near", "near", List.of("Dagger"))) {
			Map<String, Object> dodged = a.engine.encounters().perform(op(), a.campaign, a.encounter, a.ogre,
					map("kind", "DODGE"), true);
			assertEquals(PC, m(dodged.get("next_turn")).get("character"));
			addCondition(a.engine, a.campaign, a.ogre, "PARALYZED");
			Map<String, Object> attack = weaponAttack(a, "Dagger", a.ogre);
			assertTrue(expression(attack).startsWith("2d20kh1"),
					"Dodge is lost once the ogre is paralyzed; the paralyzed target still grants Advantage: "
							+ expression(attack) + " " + attack.get("advantage_reasons"));
			assertEquals("ADVANTAGE", attack.get("advantage"));
		}
	}

	/**
	 * SRD 5.2.1 Rules Glossary "Prone": "Your only movement options are to crawl or to spend an
	 * amount of movement equal to half your Speed (round down) to right yourself and thereby end
	 * the condition. If your Speed is 0, you can't right yourself." Standing is movement, so it
	 * spends no action and the turn stays open; the engine's zones carry no movement budget, so the
	 * cost is reported for the GM.
	 */
	@Test
	void aProneCreatureStandsUpForHalfItsSpeedUnlessItsSpeedIsZero() throws Exception {
		try (Arena a = arena("stand-up", true, "far", "near", List.of())) {
			addCondition(a.engine, a.campaign, PC, "PRONE");
			// Moving while Prone is crawling and does not end the condition (no hostile shares "far", so the move
			// provokes no Opportunity Attack).
			Map<String, Object> crawl = a.engine.encounters().perform(op(), a.campaign, a.encounter, PC,
					map("kind", "MOVE", "zone", "ridge"), false);
			assertNotNull(crawl.get("crawling"), "a Prone mover crawls: " + crawl);
			assertTrue(conditionNames(sheet(a.engine, a.campaign, PC)).contains("PRONE"), "crawling keeps Prone");
			assertEquals("USED", m(crawl.get("turn_economy")).get("movement"), "a zone move is movement: " + crawl);

			Map<String, Object> stood = a.engine.encounters().perform(op(), a.campaign, a.encounter, PC,
					map("kind", "STAND"), false);
			assertEquals(Boolean.TRUE, stood.get("stood_up"), stood.toString());
			assertEquals(30, stood.get("speed"));
			assertEquals(15, stood.get("movement_cost"), "half of a 30-foot Speed, rounded down");
			assertEquals(Boolean.FALSE, stood.get("turn_advanced"), "standing is movement, not an action");
			assertEquals("AVAILABLE", m(stood.get("turn_economy")).get("action"), stood.toString());
			assertTrue(String.valueOf(stood.get("next_step")).contains("Action"), stood.toString());
			assertFalse(conditionNames(sheet(a.engine, a.campaign, PC)).contains("PRONE"), "standing ends Prone");
			RpgException upright = assertThrows(RpgException.class, () -> a.engine.encounters().perform(op(),
					a.campaign, a.encounter, PC, map("kind", "STAND"), false));
			assertEquals(ErrorCode.OPERATION_NOT_ALLOWED, upright.code(), upright.getMessage());

			// Grappled sets Speed to 0: the creature cannot right itself.
			addCondition(a.engine, a.campaign, PC, "PRONE");
			addCondition(a.engine, a.campaign, PC, "GRAPPLED");
			RpgException held = assertThrows(RpgException.class, () -> a.engine.encounters().perform(op(), a.campaign,
					a.encounter, PC, map("kind", "STAND"), false));
			assertEquals(ErrorCode.OPERATION_NOT_ALLOWED, held.code(), held.getMessage());
			assertTrue(held.getMessage().contains("Speed 0"), held.getMessage());
			assertTrue(conditionNames(sheet(a.engine, a.campaign, PC)).contains("PRONE"), "still on the ground");
		}
	}

	/**
	 * SRD 5.2.1 "Your Turn": an action, a Bonus Action and movement each turn. The engine records
	 * the Action and reports what remains while the turn stays open, so the GM can tell an
	 * incomplete declaration from a finished one; it never refuses a second Action.
	 */
	@Test
	void anOpenTurnReportsWhatRemainsOfIt() throws Exception {
		try (Arena a = arena("turn-economy", true, "near", "near", List.of("Dagger", "Potion of Healing"))) {
			addCondition(a.engine, a.campaign, PC, "PRONE");
			Map<String, Object> stood = a.engine.encounters().perform(op(), a.campaign, a.encounter, PC,
					map("kind", "STAND"), false);
			Map<String, Object> economy = m(stood.get("turn_economy"));
			assertEquals("AVAILABLE", economy.get("action"), stood.toString());
			assertEquals("AVAILABLE", economy.get("bonus_action"));
			assertEquals("AVAILABLE", economy.get("reaction"));
			assertEquals("HALF_SPENT", economy.get("movement"));
			assertTrue(String.valueOf(stood.get("next_step")).contains("Action"), stood.toString());

			// A potion is the Bonus Action, so the Action stays.
			a.dice.queue(2, 2);
			Map<String, Object> drank = a.engine.encounters().perform(op(), a.campaign, a.encounter, PC,
					map("kind", "USE_ITEM", "item", "Potion of Healing"), false);
			economy = m(drank.get("turn_economy"));
			assertEquals("AVAILABLE", economy.get("action"), drank.toString());
			assertEquals("SPENT", economy.get("bonus_action"));
			assertTrue(String.valueOf(drank.get("next_step")).contains("Action"));

			// An attack is the Action; with nothing notable left, no next_step nags.
			Map<String, Object> attack = weaponAttack(a, "Dagger", a.ogre);
			economy = m(attack.get("turn_economy"));
			assertEquals("SPENT", economy.get("action"), attack.toString());
			assertEquals("SPENT", economy.get("bonus_action"));
			assertEquals("HALF_SPENT", economy.get("movement"));
			assertFalse(attack.containsKey("next_step"), attack.toString());
			assertEquals(Boolean.FALSE, attack.get("turn_advanced"));

			// A second Action is reported, not refused (Action Surge and the like are the GM's call); once the turn
			// ends there is nothing left to report.
			Map<String, Object> dodge = a.engine.encounters().perform(op(), a.campaign, a.encounter, PC,
					map("kind", "DODGE"), true);
			assertEquals(Boolean.TRUE, dodge.get("turn_advanced"));
			assertFalse(dodge.containsKey("turn_economy"), "the turn ended: " + dodge);
		}
	}

	/**
	 * SRD 5.2.1 p.17 "Order of Application": "Modifiers to damage are applied in the following
	 * order: adjustments such as bonuses, penalties, or multipliers are applied first; Resistance
	 * is applied second; and Vulnerability is applied third." Both apply to the same type: 28 is
	 * halved to 14 and then doubled back to 28, never a bare 14.
	 */
	@Test
	void resistanceIsAppliedBeforeVulnerability() {
		var defenses = new Combat.Defenses(Set.of("fire"), Set.of("fire"), Set.of());
		Combat.DamageResult result = Combat.applyDamage(100, 0, 100, List.of(new Combat.RolledDamage("fire", null, 28)),
				defenses);
		assertEquals(28, result.totalDealt(),
				"halved for Resistance, then doubled for Vulnerability: " + result.breakdown());
		assertEquals(72, result.hpAfter());
	}

	/**
	 * SRD 5.2.1 p.184 "Incapacitated [Condition]": "Surprised. If you're Incapacitated when you
	 * roll Initiative, you have Disadvantage on the roll."
	 */
	@Test
	void incapacitatedCombatantsRollInitiativeWithDisadvantage() throws Exception {
		assertInitiativeExpression("initiative-incapacitated", "INCAPACITATED", "2d20kl1");
	}

	/**
	 * SRD 5.2.1 p.184 "Invisible [Condition]": "Surprise. If you're Invisible when you roll
	 * Initiative, you have Advantage on the roll."
	 */
	@Test
	void invisibleCombatantsRollInitiativeWithAdvantage() throws Exception {
		assertInitiativeExpression("initiative-invisible", "INVISIBLE", "2d20kh1");
	}

	private static void assertInitiativeExpression(String name, String condition, String form) throws Exception {
		try (Engine engine = engine(tempDb(name))) {
			String campaign = committedCampaign(engine);
			engine.sessions().bootstrap(op(), campaign, null);
			String ogre = (String) engine.runtime()
					.materialize(op(), campaign, "Ogre", "Target", null, null, null, null, false).get("character");
			addCondition(engine, campaign, PC, condition);
			ScriptedRollService dice = (ScriptedRollService) engine.roller();
			// Richard: two d20s (3 and 18); the ogre: one d20. No tie-break die follows either.
			dice.queue(3, 18, 10);
			Map<String, Object> started = engine.encounters().start(op(), campaign,
					map("party", List.of(PC), "enemy", List.of(ogre)), null, null, null, null, null);
			Map<String, Object> roll = list(started.get("initiative_rolls")).stream()
					.filter(r -> PC_NAME.equals(r.get("name"))).findFirst().orElseThrow();
			String expression = String.valueOf(m(roll.get("roll")).get("expression"));
			assertTrue(expression.startsWith(form), condition + " initiative is rolled as " + form + ": " + expression);
		}
	}

	/**
	 * The target carries the condition first (the sorcerer attacks it), then the sorcerer does (he
	 * attacks the unaffected ogre); the expected d20 forms are given in that order.
	 */
	private static void assertConditionAffectsAttacks(
		Arena a, String condition, String againstTarget, String byAttacker) {
		addCondition(a.engine, a.campaign, a.ogre, condition);
		Map<String, Object> against = weaponAttack(a, "Dagger", a.ogre);
		assertTrue(expression(against).startsWith(againstTarget),
				"attacking a " + condition + " target rolls " + againstTarget + ": " + expression(against));
		assertEquals(advantageOf(againstTarget), against.get("advantage"), condition + " target");
		removeCondition(a.engine, a.campaign, a.ogre, condition);
		backToPc(a);

		addCondition(a.engine, a.campaign, PC, condition);
		Map<String, Object> by = weaponAttack(a, "Dagger", a.ogre);
		assertTrue(expression(by).startsWith(byAttacker),
				"a " + condition + " attacker rolls " + byAttacker + ": " + expression(by));
		assertEquals(advantageOf(byAttacker), by.get("advantage"), condition + " attacker");
		removeCondition(a.engine, a.campaign, PC, condition);
		backToPc(a);
	}

	private static String advantageOf(String form) {
		return form.startsWith("2d20kh1") ? "ADVANTAGE" : form.startsWith("2d20kl1") ? "DISADVANTAGE" : "NONE";
	}

	/**
	 * A weapon attack by the sorcerer scripted as a natural 1: it misses under any form and rolls
	 * no damage.
	 */
	private static Map<String, Object> weaponAttack(Arena a, String weapon, String target) {
		a.dice.queue(1, 1);
		return a.engine.encounters().perform(op(), a.campaign, a.encounter, PC,
				map("kind", "ATTACK", "weapon", weapon, "target", target), false);
	}

	private static String expression(Map<String, Object> attack) {
		return String.valueOf(m(attack.get("attack_roll")).get("expression"));
	}

	/**
	 * Ends the sorcerer's open turn (his attacks are performed without ending it) and every turn
	 * after it until he is up again, so each scripted attack is made on a fresh turn of his own.
	 */
	private static void backToPc(Arena a) {
		boolean ownTurnEnded = false;
		for (int i = 0; i < 8; i++) {
			Map<String, Object> state = a.engine.encounters().encounterState(a.campaign, a.encounter, 3);
			String turn = (String) m(state.get("turn")).get("character");
			if (PC.equals(turn) && ownTurnEnded) {
				return;
			}
			ownTurnEnded |= PC.equals(turn);
			a.engine.encounters().perform(op(), a.campaign, a.encounter, turn, map("kind", "END_TURN"), true);
		}
		throw new AssertionError("the turn never came back to " + PC_NAME);
	}

	private static void dropToZero(Engine engine, String campaign, String who) {
		int max = ((Number) m(engine.characters().characterSheet(campaign, who, "SUMMARY").get("hp")).get("max"))
				.intValue();
		Map<String, Object> dropped = engine.runtime().applyRuntimeChange(op(), campaign, who,
				map("kind", "DAMAGE", "amount", max, "reason", "knocked down"));
		assertEquals("DYING", dropped.get("life_state"),
				"exactly max HP of damage drops a party member to 0: " + dropped);
	}

	private static void addCondition(Engine engine, String campaign, String who, String condition) {
		engine.runtime().applyRuntimeChange(op(), campaign, who,
				map("kind", "ADD_CONDITION", "condition", condition, "reason", condition.toLowerCase() + " fixture"));
	}

	private static void removeCondition(Engine engine, String campaign, String who, String condition) {
		engine.runtime().applyRuntimeChange(op(), campaign, who,
				map("kind", "REMOVE_CONDITION", "condition", condition, "reason", "fixture cleared"));
	}

	private static Map<String, Object> sheet(Engine engine, String campaign, String who) {
		return engine.characters().characterSheet(campaign, who, "PLAY");
	}

	private static Map<String, Object> deathSaves(Engine engine, String campaign, String who) {
		return m(sheet(engine, campaign, who).get("death_saves"));
	}

	private static List<String> conditionNames(Map<String, Object> sheet) {
		return list(sheet.get("conditions")).stream().map(c -> String.valueOf(c.get("condition"))).toList();
	}

	private static List<?> concentratingOn(Engine engine, String campaign, String who) {
		return (List<?>) m(sheet(engine, campaign, who).get("spellcasting")).get("concentrating_on");
	}

	private record Arena(Engine engine, ScriptedRollService dice, String campaign, String pc, String ogre,
			String encounter, Map<String, Object> started) implements AutoCloseable {
		@Override
		public void close() {
			engine.close();
		}
	}

	/**
	 * The EXECUTION_EXAMPLE sorcerer against an Ogre with scripted initiative: the sorcerer acts
	 * first (20 to the ogre's 1) or the ogre does (1 to its 20); no tie-break die follows a d20
	 * (SRD 5.2.1 "Initiative").
	 */
	private static Arena arena(String name, boolean pcFirst, String pcZone, String ogreZone, List<String> loot)
			throws Exception {
		Engine engine = engine(tempDb(name));
		try {
			String campaign = committedCampaign(engine);
			engine.sessions().bootstrap(op(), campaign, null);
			if (!loot.isEmpty()) {
				engine.inventory().grantLoot(op(), campaign, PC, loot.stream().map(item -> map("item", item)).toList(),
						null, "QUEST", null);
			}
			String ogre = (String) engine.runtime()
					.materialize(op(), campaign, "Ogre", "Target", null, null, null, null, false).get("character");
			ScriptedRollService dice = (ScriptedRollService) engine.roller();
			if (pcFirst) {
				dice.queue(20, 1);
			} else {
				dice.queue(1, 20);
			}
			Map<String, Object> started = engine.encounters().start(op(), campaign,
					map("party", List.of(PC), "enemy", List.of(ogre)), null, map(PC, pcZone, ogre, ogreZone), null,
					null, null);
			assertEquals(pcFirst ? PC : ogre, m(started.get("turn")).get("character"));
			assertTrue(dice.exhausted(), "the scripted initiative has no leftover dice");
			return new Arena(engine, dice, campaign, PC, ogre, (String) started.get("encounter"), started);
		} catch (Exception | Error failure) {
			engine.close();
			throw failure;
		}
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
