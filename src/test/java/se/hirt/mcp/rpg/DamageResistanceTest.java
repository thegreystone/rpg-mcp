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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import se.hirt.mcp.rpg.content.RulesData;
import se.hirt.mcp.rpg.dice.Roll;
import se.hirt.mcp.rpg.dice.ScriptedRollService;
import se.hirt.mcp.rpg.magic.SpellService;
import se.hirt.mcp.rpg.persistence.Database;
import se.hirt.mcp.rpg.protocol.ErrorCode;
import se.hirt.mcp.rpg.protocol.Json;
import se.hirt.mcp.rpg.protocol.Ref;
import se.hirt.mcp.rpg.protocol.RpgException;
import se.hirt.mcp.rpg.rules.Combat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static se.hirt.mcp.rpg.TestCampaigns.committedCampaign;
import static se.hirt.mcp.rpg.TestCampaigns.engine;
import static se.hirt.mcp.rpg.TestCampaigns.map;
import static se.hirt.mcp.rpg.TestCampaigns.op;
import static se.hirt.mcp.rpg.TestCampaigns.tempDb;

class DamageResistanceTest {

	@Test
	void resistanceHalvesTheCombinedDamageOfEachType() {
		Combat.DamageResult result = Combat.applyDamage(20, 0, 20,
				List.of(new Combat.RolledDamage("fire", null, 3), new Combat.RolledDamage("fire", null, 3)),
				new Combat.Defenses(Set.of("fire"), Set.of(), Set.of()));
		assertEquals(3, result.totalDealt(), "six fire damage is halved once, after combining its parts");
		assertEquals(17, result.hpAfter());
		assertEquals(1, result.breakdown().size());
		assertEquals(6, result.breakdown().getFirst().get("rolled"));
		assertEquals(3, result.breakdown().getFirst().get("applied"));
	}

	@Test
	void caseInsensitiveInterleavedTypesKeepTheirOwnTotalsAndFirstSeenOrder() {
		Combat.DamageResult result = Combat.applyDamage(20, 0, 20,
				List.of(part("Fire", 1), part("cold", 3), part("fire", 1), part("COLD", 3), part("FIRE", 1)),
				new Combat.Defenses(Set.of("fire", "cold"), Set.of(), Set.of()));
		assertEquals(4, result.totalDealt(), "three fire is halved to one; six cold is halved to three");
		assertEquals(List.of("Fire", "cold"), result.breakdown().stream().map(p -> p.get("type")).toList());
		assertEquals(List.of(3, 6), result.breakdown().stream().map(p -> p.get("rolled")).toList());
		assertEquals(List.of(1, 3), result.breakdown().stream().map(p -> p.get("applied")).toList());
		assertEquals(3, list(result.breakdown().getFirst().get("parts")).size());
		assertEquals(2, list(result.breakdown().get(1).get("parts")).size());
	}

	@Test
	void aSinglePartKeepsTheExistingRollAndBreakdownShape() {
		Roll roll = new Roll("1d6", List.of(5), List.of(), 0, 5);
		Combat.DamageResult result = Combat.applyDamage(20, 0, 20, List.of(new Combat.RolledDamage("fire", roll, 5)),
				new Combat.Defenses(Set.of("fire"), Set.of(), Set.of()));
		assertEquals(2, result.totalDealt());
		assertEquals(map("type", "fire", "rolled", 5, "applied", 2, "note", "resistant (halved)", "roll", roll.toMap()),
				result.breakdown().getFirst());
		assertFalse(result.breakdown().getFirst().containsKey("parts"));
		assertFalse(result.breakdown().getFirst().containsKey("before_save"));
	}

	@Test
	void combinedPartsPreserveEveryOriginalRollAndFixedAmountInTheAudit() {
		Roll first = new Roll("1d6+1", List.of(2), List.of(), 1, 3);
		Roll second = new Roll("1d4", List.of(3), List.of(), 0, 3);
		Combat.DamageResult result = Combat.applyDamage(
				20, 0, 20, List.of(new Combat.RolledDamage("Fire", first, 3),
						new Combat.RolledDamage("fire", second, 3), part("FIRE", 1)),
				new Combat.Defenses(Set.of("fire"), Set.of(), Set.of()));
		Map<String, Object> fire = result.breakdown().getFirst();
		assertEquals(7, fire.get("rolled"));
		assertEquals(3, fire.get("applied"));
		assertNull(fire.get("roll"), "a combined result must not pretend one component roll produced its total");
		assertEquals(
				List.of(map("type", "Fire", "rolled", 3, "roll", first.toMap()),
						map("type", "fire", "rolled", 3, "roll", second.toMap()), map("type", "FIRE", "rolled", 1)),
				fire.get("parts"));
	}

	@Test
	void immunityWinsAndResistancePrecedesVulnerabilityOnTheCombinedAmount() {
		List<Combat.RolledDamage> fire = List.of(part("fire", 3), part("fire", 3));
		assertEquals(6, Combat.applyDamage(20, 0, 20, fire, Combat.Defenses.NONE).totalDealt());
		assertEquals(12, Combat.applyDamage(20, 0, 20, fire, new Combat.Defenses(Set.of(), Set.of("fire"), Set.of()))
				.totalDealt());
		Combat.DamageResult both = Combat.applyDamage(20, 0, 20, List.of(part("fire", 3)),
				new Combat.Defenses(Set.of("fire"), Set.of("fire"), Set.of()));
		assertEquals(2, both.totalDealt(), "three is halved to one, then doubled to two");
		assertEquals("resistant (halved), vulnerable (doubled)", both.breakdown().getFirst().get("note"));
		Combat.DamageResult immune = Combat.applyDamage(20, 2, 20, fire,
				new Combat.Defenses(Set.of("fire"), Set.of("fire"), Set.of("fire")));
		assertEquals(0, immune.totalDealt());
		assertEquals(20, immune.hpAfter());
		assertEquals(2, immune.tempHpAfter());
		assertEquals("immune", immune.breakdown().getFirst().get("note"));
	}

	@Test
	void aSuccessfulSaveHalvesEachTypeOnceBeforeItsDefenses() {
		Roll first = new Roll("1d6", List.of(3), List.of(), 0, 3);
		Roll second = new Roll("1d6", List.of(3), List.of(), 0, 3);
		List<Combat.RolledDamage> damage = List.of(new Combat.RolledDamage("fire", first, 3),
				new Combat.RolledDamage("fire", second, 3), part("cold", 3));
		Combat.DamageResult saved = Combat.applyDamage(20, 0, 20, damage, Combat.Defenses.NONE, true);
		assertEquals(4, saved.totalDealt(), "six fire becomes three; three cold becomes one");
		assertEquals(List.of(6, 3), saved.breakdown().stream().map(p -> p.get("before_save")).toList());
		assertEquals(List.of(3, 1), saved.breakdown().stream().map(p -> p.get("rolled")).toList());
		assertEquals(List.of(3, 3),
				list(saved.breakdown().getFirst().get("parts")).stream().map(p -> p.get("rolled")).toList());
		assertEquals(first.toMap(), list(saved.breakdown().getFirst().get("parts")).getFirst().get("roll"));
		Combat.DamageResult defended = Combat.applyDamage(20, 0, 20, damage,
				new Combat.Defenses(Set.of("fire"), Set.of("cold"), Set.of()), true);
		assertEquals(3, defended.totalDealt(),
				"save then resistance leaves one fire; save then vulnerability deals two cold");
		assertEquals(List.of(1, 2), defended.breakdown().stream().map(p -> p.get("applied")).toList());
	}

	@Test
	void separateDamageEventsNeverShareTheirRoundingRemainders() {
		Combat.Defenses resistance = new Combat.Defenses(Set.of("fire"), Set.of(), Set.of());
		Combat.DamageResult first = Combat.applyDamage(20, 0, 20, List.of(part("fire", 3)), resistance);
		Combat.DamageResult second = Combat.applyDamage(first.hpAfter(), first.tempHpAfter(), 20,
				List.of(part("fire", 3)), resistance);
		Combat.DamageResult combined = Combat.applyDamage(20, 0, 20, List.of(part("fire", 3), part("fire", 3)),
				resistance);
		assertEquals(2, first.totalDealt() + second.totalDealt());
		assertEquals(18, second.hpAfter());
		assertEquals(3, combined.totalDealt());
		assertEquals(17, combined.hpAfter());
	}

	@Test
	void correctedDamageFlowsThroughTemporaryHpAndMassiveDamageThresholds() {
		List<Combat.RolledDamage> fire = List.of(part("fire", 3), part("fire", 3));
		Combat.Defenses resistance = new Combat.Defenses(Set.of("fire"), Set.of(), Set.of());
		Combat.DamageResult protectedTarget = Combat.applyDamage(20, 2, 20, fire, resistance);
		assertEquals(3, protectedTarget.totalDealt());
		assertEquals(0, protectedTarget.tempHpAfter());
		assertEquals(19, protectedTarget.hpAfter());
		assertFalse(protectedTarget.droppedToZero());
		assertFalse(protectedTarget.massiveDamage());
		Combat.DamageResult frailTarget = Combat.applyDamage(1, 0, 2, fire, resistance);
		assertEquals(0, frailTarget.hpAfter());
		assertTrue(frailTarget.droppedToZero());
		assertTrue(frailTarget.massiveDamage(),
				"three damage leaves two after the last HP, reaching the maximum of two");
	}

	@Test
	void aRoguesWeaponAndSneakAttackShareResistanceAndRemainReplayableAndRewindable() throws Exception {
		try (Engine engine = engine(tempDb("resistance-sneak-attack"))) {
			String[] ids = withRogue(engine);
			String campaign = ids[0], rogue = ids[1];
			String ghost = creature(engine, campaign, "Ghost");
			int ghostHp = hp(engine, ghost);
			String checkpoint = (String) engine.checkpoints().create(op(), campaign, "before attacking the ghost")
					.get("checkpoint");
			String encounter = startEncounter(engine, campaign, rogue, ghost);
			ScriptedRollService dice = (ScriptedRollService) engine.roller();
			dice.queue(15, 10, 1, 1, 2);
			Map<String, Object> action = map("kind", "ATTACK", "attack", "Shortsword", "weapon", "Shortsword", "target",
					ghost, "advantage", "ADVANTAGE");
			String operation = op();
			Map<String, Object> attacked = engine.encounters().perform(operation, campaign, encounter, rogue, action,
					false);
			assertEquals(3, attacked.get("damage"), "weapon three plus Sneak Attack three is halved once");
			assertEquals(3, m(attacked.get("sneak_attack")).get("damage"));
			assertEquals(ghostHp - 3, hp(engine, ghost));
			List<Map<String, Object>> breakdown = list(attacked.get("breakdown"));
			assertEquals(1, breakdown.size());
			assertEquals("piercing", breakdown.getFirst().get("type"));
			assertEquals(6, breakdown.getFirst().get("rolled"));
			assertEquals(List.of(3, 3),
					list(breakdown.getFirst().get("parts")).stream().map(p -> p.get("rolled")).toList());
			assertEquals(List.of("1d6+2", "2d6"), list(breakdown.getFirst().get("parts")).stream()
					.map(p -> m(p.get("roll")).get("expression")).toList());
			assertTrue(dice.exhausted());
			Map<String, List<Map<String, Object>>> after = engine.db().snapshot(Ref.id(campaign, Ref.CAMPAIGN));
			dice.queue(7);
			Map<String, Object> replay = engine.encounters().perform(operation, campaign, encounter, rogue, action,
					false);
			assertEquals(Boolean.TRUE, replay.get("replayed"));
			assertEquals(attacked.get("breakdown"), replay.get("breakdown"));
			assertEquals(after, engine.db().snapshot(Ref.id(campaign, Ref.CAMPAIGN)));
			assertEquals(7, dice.roll("1d20").total(), "replay did not consume a die");
			engine.checkpoints().restore(op(), campaign, checkpoint, "try the same attack after restoring");
			assertEquals(ghostHp, hp(engine, ghost));
			String restoredEncounter = startEncounter(engine, campaign, rogue, ghost);
			dice.queue(15, 10, 1, 1, 2);
			Map<String, Object> fresh = engine.encounters().perform(op(), campaign, restoredEncounter, rogue, action,
					false);
			assertEquals(3, fresh.get("damage"));
			assertEquals(ghostHp - 3, hp(engine, ghost));
			assertTrue(dice.exhausted());
		}
	}

	@Test
	void splitSpellDamageIsSharedThenSavedAndResistedInTheCastingPipeline() throws Exception {
		try (Engine engine = engine(tempDb("resistance-split-save"))) {
			String campaign = committedCampaign(engine);
			engine.sessions().bootstrap(op(), campaign, null);
			RulesData rules = installedSpell(engine, campaign, map("kind", "SAVE", "save", "DEX", "on_success", "HALF",
					"damage", List.of(map("dice", "1d6", "type", "fire"), map("dice", "1d6", "type", "fire"))));
			String ogre = creature(engine, campaign, "Ogre"), ghost = creature(engine, campaign, "Ghost");
			ScriptedRollService dice = (ScriptedRollService) engine.roller();
			dice.queue(20, 3, 3, 20);
			List<Map<String, Object>> targets = list(
					castInstalled(engine, rules, campaign, List.of(ogre, ghost)).get("targets"));
			assertEquals(Boolean.TRUE, targets.get(0).get("saved"));
			assertEquals(Boolean.TRUE, targets.get(1).get("saved"));
			assertEquals(3, targets.get(0).get("damage"), "six is halved once on the successful save");
			assertEquals(1, targets.get(1).get("damage"), "the ghost then resists the three fire damage");
			for (Map<String, Object> target : targets) {
				Map<String, Object> fire = list(target.get("breakdown")).getFirst();
				assertEquals(6, fire.get("before_save"));
				assertEquals(3, fire.get("rolled"));
				assertEquals(List.of(3, 3), list(fire.get("parts")).stream().map(p -> p.get("rolled")).toList());
				assertEquals(List.of(List.of(3), List.of(3)),
						list(fire.get("parts")).stream().map(p -> m(p.get("roll")).get("dice")).toList());
			}
			assertTrue(dice.exhausted(), "both targets use the same two original damage rolls");
		}
	}

	@Test
	void separateSpellRaysApplyResistanceToEachHitInsteadOfTheWholeCasting() throws Exception {
		try (Engine engine = engine(tempDb("resistance-separate-rays"))) {
			String campaign = committedCampaign(engine);
			engine.sessions().bootstrap(op(), campaign, null);
			RulesData rules = installedSpell(engine, campaign,
					map("kind", "ATTACK", "rays", 3, "damage", List.of(map("dice", "1d6", "type", "fire"))));
			String ghost = creature(engine, campaign, "Ghost");
			int beforeHp = hp(engine, ghost);
			ScriptedRollService dice = (ScriptedRollService) engine.roller();
			dice.queue(15, 3, 15, 3, 15, 3);
			List<Map<String, Object>> rays = list(
					castInstalled(engine, rules, campaign, List.of(ghost)).get("targets"));
			assertEquals(List.of(1, 1, 1), rays.stream().map(r -> r.get("damage")).toList());
			assertEquals(beforeHp - 3, hp(engine, ghost));
			assertTrue(rays.stream().allMatch(r -> Boolean.TRUE.equals(r.get("hit"))));
			assertTrue(dice.exhausted());
		}
	}

	@Test
	void largeSameTypeDamageIsCombinedWithoutOverflow() {
		Combat.DamageResult result = Combat.applyDamage(2_000_000_000, 100, 2_000_000_000,
				List.of(part("fire", 1_500_000_000), part("fire", 1_500_000_000)),
				new Combat.Defenses(Set.of("fire"), Set.of(), Set.of()));
		assertEquals(1_500_000_000, result.totalDealt(), "combining damage must not wrap before resistance is applied");
		assertEquals(0, result.tempHpAfter());
		assertEquals(500_000_100, result.hpAfter());
		assertEquals(3_000_000_000L, result.breakdown().getFirst().get("rolled"));
		assertInstanceOf(Long.class, result.breakdown().getFirst().get("rolled"));
		List<Combat.RolledDamage> maximumParts = List.of(part("fire", Integer.MAX_VALUE),
				part("fire", Integer.MAX_VALUE));
		Combat.DamageResult resisted = Combat.applyDamage(Integer.MAX_VALUE, 100, Integer.MAX_VALUE, maximumParts,
				new Combat.Defenses(Set.of("fire"), Set.of(), Set.of()));
		assertEquals(Integer.MAX_VALUE, resisted.totalDealt());
		assertEquals(4_294_967_294L, resisted.breakdown().getFirst().get("rolled"));
		Combat.DamageResult saved = Combat.applyDamage(Integer.MAX_VALUE, 100, Integer.MAX_VALUE, maximumParts,
				Combat.Defenses.NONE, true);
		assertEquals(Integer.MAX_VALUE, saved.totalDealt());
		assertEquals(100, saved.hpAfter());
		assertEquals(0, saved.tempHpAfter());
		assertEquals(4_294_967_294L, saved.breakdown().getFirst().get("before_save"));
		assertInstanceOf(Long.class, saved.breakdown().getFirst().get("before_save"));
		assertEquals(Integer.MAX_VALUE, saved.breakdown().getFirst().get("rolled"));
		assertInstanceOf(Integer.class, saved.breakdown().getFirst().get("rolled"),
				"ordinary-sized audit fields keep their existing number type");
		Combat.DamageResult immune = Combat.applyDamage(2_000_000_000, 100, 2_000_000_000, maximumParts,
				new Combat.Defenses(Set.of("fire"), Set.of("fire"), Set.of("fire")));
		assertEquals(0, immune.totalDealt(), "immunity can absorb a large raw group without rejecting it");
		assertEquals(100, immune.tempHpAfter());
		assertEquals(2_000_000_000, immune.hpAfter());
		assertEquals(4_294_967_294L, immune.breakdown().getFirst().get("rolled"));
	}

	@Test
	void unrepresentablePostDefenseDamageIsRejectedInsteadOfWrapping() {
		List<Combat.RolledDamage> largeFire = List.of(part("fire", 1_500_000_000), part("fire", 1_500_000_000));
		Combat.Defenses vulnerable = new Combat.Defenses(Set.of(), Set.of("fire"), Set.of());
		assertInvalidDamage(largeFire, Combat.Defenses.NONE, false);
		assertInvalidDamage(List.of(part("fire", 1_073_741_824)), vulnerable, false);
		assertInvalidDamage(largeFire, vulnerable, false);
		assertInvalidDamage(largeFire, vulnerable, true);
		assertInvalidDamage(largeFire, new Combat.Defenses(Set.of("fire"), Set.of("fire"), Set.of()), false);
		assertInvalidDamage(List.of(part("fire", Integer.MAX_VALUE), part("cold", 1)), Combat.Defenses.NONE, false);
		assertInvalidDamage(List.of(part("fire", Integer.MAX_VALUE), part("cold", Integer.MAX_VALUE)),
				new Combat.Defenses(Set.of("fire"), Set.of(), Set.of()), false);
	}

	private static void assertInvalidDamage(List<Combat.RolledDamage> parts, Combat.Defenses defenses, boolean saved) {
		RpgException rejected = assertThrows(RpgException.class,
				() -> Combat.applyDamage(2_000_000_000, 100, 2_000_000_000, parts, defenses, saved));
		assertEquals(ErrorCode.INVALID_ARGUMENT, rejected.code());
	}

	private static Combat.RolledDamage part(String type, int amount) {
		return new Combat.RolledDamage(type, null, amount);
	}

	private static String creature(Engine engine, String campaign, String name) {
		return (String) engine.runtime().materialize(op(), campaign, name, null, null, null, null, null, false)
				.get("character");
	}

	private static int hp(Engine engine, String character) {
		return engine.db().read(tx -> tx.get("character", Ref.id(character, Ref.CHARACTER)).intOr("current_hp", 0));
	}

	private static String[] withRogue(Engine engine) {
		String campaign = committedCampaign(engine);
		engine.sessions().bootstrap(op(), campaign, null);
		String rogue = (String) engine.runtime()
				.materialize(op(), campaign, "Scout", "Vess", null, null, null, null, false).get("character");
		engine.party().updateMembership(op(), campaign, rogue, "JOIN", "a test companion");
		engine.inventory().grantLoot(op(), campaign, rogue, List.of(map("item", "Shortsword")), null, "QUEST",
				"her own blade");
		engine.runtime().awardXp(op(), campaign, List.of(rogue), 900, "MILESTONE", "level the rogue for Sneak Attack");
		for (int level = 1; level <= 3; level++) {
			String transaction = (String) m(engine.levelUps().begin(op(), campaign, rogue).get("transaction"))
					.get("ref");
			if (level == 1) {
				engine.levelUps().update(op(), campaign, transaction, null,
						map("class", "Rogue", "skills", List.of("Stealth", "Perception", "Deception", "Acrobatics")));
			}
			engine.levelUps().commit(op(), campaign, transaction, null);
		}
		return new String[] {campaign, rogue};
	}

	private static String startEncounter(Engine engine, String campaign, String rogue, String ghost) {
		((ScriptedRollService) engine.roller()).queue(20, 50, 1, 50);
		Map<String, Object> started = engine.encounters().start(op(), campaign,
				map("party", List.of(rogue), "foes", List.of(ghost)), null, null, null, null, null);
		assertEquals(rogue, m(started.get("turn")).get("character"));
		return (String) started.get("encounter");
	}

	/**
	 * Installed-content fixture loaded into a fresh rules view, preserving the engine's cached
	 * rules.
	 */
	private static RulesData installedSpell(Engine engine, String campaign, Map<String, Object> mechanics) {
		String spell = "srd5e:spell/acid-splash";
		engine.db().mutate(Database.Mutation.of("install_split_damage_spell", Ref.id(campaign, Ref.CAMPAIGN), op(),
				"ADMINISTRATIVE_OVERRIDE", null), tx -> {
					var installed = tx.queryOne("SELECT * FROM installed_content WHERE content_id = ?", spell)
							.orElseThrow();
					var payload = new LinkedHashMap<>(installed.map("payload_json"));
					payload.put("mechanics", mechanics);
					tx.rawUpdate("installed_content", installed.id(), Map.of("payload_json", Json.write(payload)));
					if (tx.count(
							"SELECT COUNT(*) FROM character_trait WHERE character_id = ? AND kind = 'SPELL_KNOWN' AND content_ref = ?",
							1L, spell) == 0) {
						tx.insert("character_trait",
								map("character_id", 1L, "kind", "SPELL_KNOWN", "content_ref", spell));
					}
					return Map.of();
				});
		return new RulesData(engine.db());
	}

	private static Map<String, Object> castInstalled(
		Engine engine, RulesData rules, String campaign, List<String> targets) {
		long campaignId = Ref.id(campaign, Ref.CAMPAIGN);
		return engine.db().mutate(
				Database.Mutation.of("cast_split_damage_spell", campaignId, op(), "PLAYER", map("targets", targets)),
				tx -> SpellService.castCore(tx, rules, engine.roller(), campaignId, tx.get("character", 1L),
						"srd5e:spell/acid-splash", null, targets, null, null, 0));
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
