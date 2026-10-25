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

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import se.hirt.mcp.rpg.dice.ScriptedRollService;
import se.hirt.mcp.rpg.persistence.Database;
import se.hirt.mcp.rpg.protocol.Ref;
import se.hirt.mcp.rpg.rules.Effects;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static se.hirt.mcp.rpg.TestCampaigns.map;
import static se.hirt.mcp.rpg.TestCampaigns.op;

/** Target conditions affect attack rolls and nearby hits independently of the attack's kind. */
class AttackConditionTest {

	private enum Attack {
		DAGGER("Dagger", false),
		GLAIVE("Glaive", false),
		SHORTBOW("Shortbow", false),
		SHOCKING_GRASP("Shocking Grasp", true),
		FIRE_BOLT("Fire Bolt", true);

		final String name;
		final boolean spell;

		Attack(String name, boolean spell) {
			this.name = name;
			this.spell = spell;
		}
	}

	@Test
	void aParalyzedTargetGrantsWeaponAdvantageAndNearbyCriticalHits() throws Exception {
		verifyHit("PARALYZED", Attack.DAGGER, true, true, true, false);
	}

	@Test
	void aParalyzedTargetGrantsSpellAdvantageAndNearbyCriticalHits() throws Exception {
		verifyHit("PARALYZED", Attack.SHOCKING_GRASP, true, true, true, false);
	}

	@TestFactory
	Stream<DynamicTest> onlyTouchEstablishesNearbySpellHitsOutsideEncounters() {
		return Stream.of("PARALYZED", "UNCONSCIOUS")
				.flatMap(condition -> Stream.of(Attack.SHOCKING_GRASP, Attack.FIRE_BOLT).map(attack -> DynamicTest
						.dynamicTest(condition + " against " + attack.name + " outside combat", () -> {
							try (Fixture fixture = fixture(condition, true, false)) {
								fixture.engine.encounters().end(op(), fixture.campaign, fixture.encounter, "OTHER",
										"combat ends");
								boolean critical = attack == Attack.SHOCKING_GRASP;
								fixture.dice.queue(4, 19, 2);
								if (critical) {
									fixture.dice.queue(3);
								}
								Map<String, Object> cast = fixture.engine.spells().cast(op(), fixture.campaign,
										fixture.actor, attack.name, null, List.of(fixture.target), null);
								Map<String, Object> result = list(cast.get("targets")).get(0);
								assertEquals(Boolean.TRUE, result.get("hit"));
								assertEquals(critical, result.get("critical"));
								assertEquals(List.of(19), m(result.get("attack_roll")).get("dice"));
								assertTrue(fixture.dice.exhausted());
							}
						})));
	}

	@TestFactory
	Stream<DynamicTest> conditionsThatDoNotGrantAutomaticCriticalHits() {
		return Stream.of("STUNNED", "PETRIFIED", "INCAPACITATED").flatMap(condition -> Stream
				.of(Attack.DAGGER, Attack.SHOCKING_GRASP)
				.map(attack -> DynamicTest.dynamicTest(condition + " against " + attack.name,
						() -> verifyHit(condition, attack, true, !"INCAPACITATED".equals(condition), false, false))));
	}

	@TestFactory
	Stream<DynamicTest> automaticCriticalHitsRequireTheSameZoneForMeleeAndRangedAttacks() {
		return Stream
				.of("PARALYZED",
						"UNCONSCIOUS")
				.flatMap(
						condition -> Stream
								.of(Attack.GLAIVE, Attack.SHORTBOW, Attack.SHOCKING_GRASP,
										Attack.FIRE_BOLT)
								.flatMap(
										attack -> Stream.of(true, false)
												.map(nearby -> DynamicTest
														.dynamicTest(
																condition + " against " + attack.name
																		+ (nearby ? " in the same zone"
																				: " in another zone"),
																() -> verifyHit(condition, attack, nearby, true, nearby,
																		false)))));
	}

	@Test
	void disadvantageCancelsConditionAdvantageForWeaponAttacks() throws Exception {
		verifyHit("PARALYZED", Attack.DAGGER, true, false, true, true);
	}

	@Test
	void disadvantageCancelsConditionAdvantageForSpellAttacks() throws Exception {
		verifyHit("PARALYZED", Attack.SHOCKING_GRASP, true, false, true, true);
	}

	@TestFactory
	Stream<DynamicTest> naturalOneStillMissesAnOtherwiseAutomaticCriticalTarget() {
		return Stream.of(Attack.DAGGER, Attack.SHORTBOW, Attack.SHOCKING_GRASP, Attack.FIRE_BOLT)
				.map(attack -> DynamicTest.dynamicTest(attack.name + " misses on a natural 1", () -> {
					try (Fixture fixture = fixture("PARALYZED", true, false)) {
						Object hpBefore = m(fixture.engine.characters()
								.characterSheet(fixture.campaign, fixture.target, "PLAY").get("hp")).get("current");
						fixture.dice.queue(1, 1);
						Map<String, Object> result = attack(fixture, attack);
						assertAll(
								() -> assertTrue(m(result.get("attack_roll")).get("expression").toString()
										.startsWith("2d20kh1")),
								() -> assertEquals(Boolean.FALSE, result.get("hit")),
								() -> assertEquals(Boolean.FALSE, result.get("critical")),
								() -> assertEquals(hpBefore,
										m(fixture.engine.characters()
												.characterSheet(fixture.campaign, fixture.target, "PLAY").get("hp"))
												.get("current")),
								() -> assertTrue(fixture.dice.exhausted(), "only the two attack dice are consumed"));
					}
				}));
	}

	private static void verifyHit(
		String condition, Attack attack, boolean nearby, boolean advantage, boolean critical, boolean cancellation)
			throws Exception {
		try (Fixture fixture = fixture(condition, nearby, cancellation && attack.spell)) {
			// A 19 hits without being a natural critical. The low discarded die proves advantage was rolled.
			if (advantage) {
				fixture.dice.queue(4, 19);
			} else {
				fixture.dice.queue(19);
			}
			fixture.dice.queue(2);
			if (critical) {
				fixture.dice.queue(3);
			}
			Map<String, Object> action = action(fixture, attack);
			if (cancellation && !attack.spell) {
				action.put("advantage", "DISADVANTAGE");
			}
			Map<String, Object> result = result(fixture, attack, action);
			String expression = m(result.get("attack_roll")).get("expression").toString();
			assertAll(condition + " against " + attack.name,
					() -> assertTrue(expression.startsWith(advantage ? "2d20kh1" : "1d20"), expression),
					() -> assertEquals(List.of(19), m(result.get("attack_roll")).get("dice")),
					() -> assertEquals(Boolean.TRUE, result.get("hit")),
					() -> assertEquals(critical, result.get("critical")),
					() -> assertTrue(fixture.dice.exhausted(), "the expected attack and damage dice are consumed"));
			Map<String, Object> damage = list(result.get("breakdown")).get(0);
			Map<String, Object> damageRoll = m(damage.get("roll"));
			assertEquals(critical ? 2 : 1, ((List<?>) damageRoll.get("dice")).size(),
					"a nearby automatic critical doubles damage dice, while a distant hit leaves them alone");
			int rolled = ((Number) damage.get("rolled")).intValue();
			if ("PETRIFIED".equals(condition)) {
				// SRD 5.2.1 "Petrified": "Resist Damage. You have Resistance to all damage." (halved, rounded down).
				assertEquals(rolled / 2, damage.get("applied"), "a petrified target halves the damage: " + damage);
				assertEquals("resistant (halved)", damage.get("note"));
				assertEquals(rolled / 2, result.get("damage"));
			} else {
				assertEquals(rolled, damage.get("applied"), "no resistance applies: " + damage);
			}
			if (!attack.spell) {
				assertEquals(advantage ? "ADVANTAGE" : "NONE", result.get("advantage"));
			}
		}
	}

	private static Map<String, Object> attack(Fixture fixture, Attack attack) {
		return result(fixture, attack, action(fixture, attack));
	}

	private static Map<String, Object> action(Fixture fixture, Attack attack) {
		return attack.spell ? map("kind", "CAST", "spell", attack.name, "targets", List.of(fixture.target))
				: map("kind", "ATTACK", "weapon", attack.name, "target", fixture.target);
	}

	private static Map<String, Object> result(Fixture fixture, Attack attack, Map<String, Object> action) {
		Map<String, Object> result = fixture.engine.encounters().perform(op(), fixture.campaign, fixture.encounter,
				fixture.actor, action, false);
		return attack.spell ? list(result.get("targets")).get(0) : result;
	}

	private record Fixture(Engine engine, ScriptedRollService dice, String campaign, String actor, String target,
			String encounter) implements AutoCloseable {
		@Override
		public void close() {
			engine.close();
		}
	}

	private static Fixture fixture(String condition, boolean nearby, boolean protectedTarget) throws Exception {
		Engine engine = TestCampaigns.engine(TestCampaigns.tempDb("attack-conditions"));
		try {
			String campaign = (String) engine.campaigns().create(op(), null, null).get("campaign");
			engine.campaigns().updateSetup(op(), campaign, null,
					map("content_profile", "PEGI_16", "experience", "SURPRISE_ME", "rules", Map.of(), "continuation",
							"CHECKPOINT", "party", "SURPRISE_ME", "adventure", map("premise", "a broken observatory",
									"opening_location", "Tower", "immediate_goal", "find the key")));
			String actor = (String) engine.characters()
					.createDraft(op(), campaign,
							map("name", "Caster", "species", "Human", "class", "Wizard", "ability_scores",
									map("INT", 15, "DEX", 14, "CON", 13, "WIS", 12, "CHA", 10, "STR", 8), "skills",
									List.of("Arcana", "History"), "personality", "patient", "background", "Criminal",
									"background_ability_scores", map("CON", 2, "DEX", 1), "species_skill",
									"Animal Handling", "origin_feat",
									map("feat", "Skilled", "proficiencies", List.of("Nature", "Survival", "Medicine")),
									"cantrips", List.of("Shocking Grasp", "Fire Bolt", "Light"), "spells",
									List.of("Magic Missile", "Mage Armor", "Sleep", "Shield")),
							true)
					.get("character");
			engine.characters().commitDraft(op(), campaign, actor, null);
			engine.campaigns().commitSetup(op(), campaign, null);
			engine.sessions().bootstrap(op(), campaign, null);
			engine.inventory().grantLoot(op(), campaign, actor, List.of(map("item", "Dagger"), map("item", "Glaive"),
					map("item", "Shortbow"), map("item", "Arrow")), null, "QUEST", "attack fixture");
			String target = (String) engine.runtime()
					.materialize(op(), campaign, "Ogre", "Target", null, null, null, null, false).get("character");
			ScriptedRollService dice = (ScriptedRollService) engine.roller();
			dice.queue(20, 1);
			Map<String, Object> started = engine.encounters().start(op(), campaign,
					map("party", List.of(actor), "enemy", List.of(target)), null,
					map(actor, "near", target, nearby ? "near" : "far"), null, null, null);
			assertEquals(actor, m(started.get("turn")).get("character"));
			assertTrue(dice.exhausted(), "the scripted initiative has no leftover dice");
			engine.runtime().applyRuntimeChange(op(), campaign, target,
					map("kind", "ADD_CONDITION", "condition", condition, "reason", "target condition fixture"));
			if (protectedTarget) {
				engine.db().mutate(Database.Mutation.of("attack_disadvantage_fixture", Ref.id(campaign, Ref.CAMPAIGN),
						op(), "GM", null), tx -> {
							Effects.add(tx, Ref.id(campaign, Ref.CAMPAIGN), Ref.id(target, Ref.CHARACTER), null, null,
									"a protective illusion", null, map("disadvantage_on_attacks_against", true), null,
									null, null, "GM");
							return Map.of();
						});
			}
			return new Fixture(engine, dice, campaign, actor, target, (String) started.get("encounter"));
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
