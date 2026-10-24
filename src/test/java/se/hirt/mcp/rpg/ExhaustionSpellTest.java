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

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static se.hirt.mcp.rpg.TestCampaigns.*;

/** Exhaustion affects spell D20 Tests, including listed creature saves and Concentration saves. */
class ExhaustionSpellTest {

	@Test
	void aSpellAttackLosesTwoPerLevelWithoutChangingItsDamage() throws Exception {
		try (Engine engine = engine(tempDb("exhaustion-spell-attack"))) {
			String campaign = wizardCampaign(engine);
			String pc = "character:1";
			String ogre = (String) engine.runtime()
					.materialize(op(), campaign, "Ogre", "Target", null, null, null, null, false).get("character");
			ScriptedRollService dice = (ScriptedRollService) engine.roller();
			dice.queue(19, 6);
			Map<String, Object> ordinary = list(
					engine.spells().cast(op(), campaign, pc, "Fire Bolt", null, List.of(ogre), null).get("targets"))
					.get(0);
			exhaustion(engine, campaign, pc, 2);
			dice.queue(19, 6);
			Map<String, Object> tired = list(
					engine.spells().cast(op(), campaign, pc, "Fire Bolt", null, List.of(ogre), null).get("targets"))
					.get(0);
			assertEquals(4, m(ordinary.get("attack_roll")).get("modifier"));
			assertEquals(0, m(tired.get("attack_roll")).get("modifier"));
			assertEquals(19, tired.get("total"));
			assertEquals(Boolean.TRUE, tired.get("hit"));
			Map<String, Object> ordinaryDamage = m(list(ordinary.get("breakdown")).get(0).get("roll"));
			Map<String, Object> tiredDamage = m(list(tired.get("breakdown")).get(0).get("roll"));
			assertEquals("1d10", tiredDamage.get("expression"));
			assertEquals(ordinaryDamage, tiredDamage, "the same damage die is unaffected by exhaustion");
			assertEquals(6, tired.get("damage"));
			assertTrue(dice.exhausted());
		}
	}

	@Test
	void creatureSpellSavesAndCasterConcentrationSavesApplyThePenaltyOnce() throws Exception {
		try (Engine engine = engine(tempDb("exhaustion-spell-saves"))) {
			String campaign = wizardCampaign(engine);
			String pc = "character:1";
			String mage = (String) engine.runtime()
					.materialize(op(), campaign, "Mage", "Target", null, null, null, null, false).get("character");
			exhaustion(engine, campaign, pc, 2);
			exhaustion(engine, campaign, mage, 2);
			ScriptedRollService dice = (ScriptedRollService) engine.roller();
			dice.queue(11);
			Map<String, Object> target = list(
					engine.spells().cast(op(), campaign, pc, "Sleep", null, List.of(mage), null).get("targets")).get(0);
			assertEquals(0, m(target.get("save_roll")).get("modifier"), "the Mage's listed WIS save +4 loses 4");
			assertEquals(11, target.get("save_total"));
			assertEquals(12, target.get("dc"));
			assertEquals(Boolean.FALSE, target.get("saved"));

			// Wizard CON 15 gives +2 with no save proficiency; two levels reduce the total bonus to -2.
			dice.queue(11);
			Map<String, Object> damaged = engine.runtime().applyRuntimeChange(op(), campaign, pc,
					map("kind", "DAMAGE", "amount", 1, "reason", "concentration regression"));
			Map<String, Object> concentration = m(damaged.get("concentration"));
			assertEquals(-2, m(concentration.get("roll")).get("modifier"));
			assertEquals(9, m(concentration.get("roll")).get("total"));
			assertEquals(Boolean.FALSE, concentration.get("kept"));
			assertTrue(
					list(engine.characters().characterSheet(campaign, mage, "PLAY").get("conditions")).stream()
							.noneMatch(condition -> "INCAPACITATED".equals(condition.get("condition"))),
					"losing Concentration ends Sleep on the target");
			assertTrue(dice.exhausted());
		}
	}

	private static String wizardCampaign(Engine engine) {
		String campaign = (String) engine.campaigns().create(op(), null, null).get("campaign");
		engine.campaigns().updateSetup(op(), campaign, null, map("content_profile", "PEGI_16", "experience",
				"SURPRISE_ME", "rules", Map.of(), "continuation", "CHECKPOINT", "party", "SURPRISE_ME", "adventure",
				map("premise", "a haunted tower", "opening_location", "Tower", "immediate_goal", "find the key")));
		String pc = (String) engine.characters()
				.createDraft(op(), campaign,
						map("name", "Caster", "species", "Human", "class", "Wizard", "ability_scores",
								map("INT", 15, "DEX", 14, "CON", 13, "WIS", 12, "CHA", 10, "STR", 8), "skills",
								List.of("Arcana", "History"), "personality", "curious", "background", "Criminal",
								"background_ability_scores", map("CON", 2, "DEX", 1), "species_skill",
								"Animal Handling", "origin_feat",
								map("feat", "Skilled", "proficiencies", List.of("Nature", "Survival", "Medicine")),
								"cantrips", List.of("Fire Bolt", "Mage Hand", "Light"), "spells",
								List.of("Sleep", "Magic Missile", "Mage Armor", "Detect Magic")),
						true)
				.get("character");
		engine.characters().commitDraft(op(), campaign, pc, null);
		engine.campaigns().commitSetup(op(), campaign, null);
		engine.sessions().bootstrap(op(), campaign, null);
		return campaign;
	}

	private static void exhaustion(Engine engine, String campaign, String character, int level) {
		engine.db().mutate(
				Database.Mutation.of("exhaustion_spell_fixture", Ref.id(campaign, Ref.CAMPAIGN), op(), "GM", null),
				tx -> {
					tx.update("character", Ref.id(character, Ref.CHARACTER), Map.of("exhaustion", level));
					return Map.of();
				});
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
