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

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static se.hirt.mcp.rpg.TestCampaigns.map;
import static se.hirt.mcp.rpg.TestCampaigns.op;

/**
 * Character origins per SRD 5.2.1 (RULES_ENGINE.md §8): backgrounds (ability increase, Origin feat,
 * skills, tool, equipment), species special traits (lineages, resistances, toughness, relentless
 * endurance, tracked resources) and feats (Alert, Savage Attacker, Magic Initiate free casts).
 */
class OriginsTest {

	@SuppressWarnings("unchecked")
	private static Map<String, Object> m(Object o) {
		return (Map<String, Object>) o;
	}

	@SuppressWarnings("unchecked")
	private static List<Map<String, Object>> list(Object o) {
		return (List<Map<String, Object>>) o;
	}

	private static String setup(Engine engine, String location) {
		String campaign = (String) engine.campaigns().create(op(), null, null).get("campaign");
		engine.campaigns().updateSetup(op(), campaign, null,
				map("content_profile", "PEGI_16", "experience", "SURPRISE_ME", "rules", Map.of(), "continuation",
						"CHECKPOINT", "party", "SURPRISE_ME", "adventure",
						map("premise", "x", "opening_location", location, "immediate_goal", "y")));
		return campaign;
	}

	/**
	 * Narrative facts established in play are recorded on committed characters; mechanical state is
	 * not.
	 */
	@Test
	void updateCharacterRecordsNarrativeFactsOnly() throws Exception {
		try (Engine engine = TestCampaigns.engine(TestCampaigns.tempDb("origins-narrative"))) {
			String campaign = TestCampaigns.committedCampaign(engine);
			engine.sessions().bootstrap(op(), campaign, null);
			String pc = "character:1";
			Map<String, Object> updated = engine.characters().updateCharacter(op(), campaign, pc, null, map("age", 27,
					"appearance", "Tall, dark hair, green eyes.", "goals", List.of("Prove the gift is not a flaw")));
			assertEquals(27, m(updated.get("sheet")).get("age"));
			assertEquals("Tall, dark hair, green eyes.", m(updated.get("sheet")).get("appearance"));
			assertEquals(List.of("Prove the gift is not a flaw"), m(updated.get("sheet")).get("goals"));
			assertEquals(27, engine.characters().characterSheet(campaign, pc, "FULL").get("age"),
					"the age persists on the sheet");
			assertEquals(ErrorCode.INVALID_ARGUMENT,
					assertThrows(RpgException.class,
							() -> engine.characters().updateCharacter(op(), campaign, pc, null, map("max_hp", 99)))
							.code(),
					"mechanical state is not a narrative field");
		}
	}

	@Test
	void tieflingLegacyGrantsResistanceCantripsAndTheLevelThreeSpell() throws Exception {
		try (Engine engine = TestCampaigns.engine(TestCampaigns.tempDb("origins-tiefling"))) {
			ScriptedRollService dice = (ScriptedRollService) engine.roller();
			String campaign = setup(engine, "Gate");
			String pc = (String) engine.characters()
					.createDraft(op(), campaign, map("name", "Nyx", "species", "Tiefling", "class", "Warlock",
							"ability_scores", map("CHA", 15, "CON", 14, "DEX", 13, "INT", 12, "WIS", 10, "STR", 8),
							"background", "Soldier", "background_ability_scores", map("STR", 2, "CON", 1),
							"background_tool", "srd5e:item/gaming-set-dice", "species_choice",
							map("choice", "infernal", "ability", "CHA"), "skills", List.of("Arcana", "Deception"),
							"cantrips", List.of("Eldritch Blast", "Minor Illusion"), "spells",
							List.of("Hex", "Charm Person"), "personality", "x"), true)
					.get("character");
			Map<String, Object> validated = engine.characters().validateDraft(campaign, pc);
			assertEquals(Boolean.TRUE, validated.get("valid"), validated.toString());
			engine.characters().commitDraft(op(), campaign, pc, null);
			engine.campaigns().commitSetup(op(), campaign, null);
			engine.sessions().bootstrap(op(), campaign, null);

			Map<String, Object> sheet = engine.characters().characterSheet(campaign, pc, "PLAY");
			assertEquals(List.of("Darkvision 60 ft."), sheet.get("senses"));
			assertTrue(((List<?>) sheet.get("tool_proficiencies")).contains("Gaming Set (Dice)"),
					sheet.get("tool_proficiencies").toString());
			List<String> cantrips = (List<String>) m(sheet.get("spellcasting")).get("cantrips");
			assertTrue(cantrips.stream().anyMatch(c -> c.contains("Fire Bolt")),
					"Infernal legacy cantrip: " + cantrips);
			assertTrue(cantrips.stream().anyMatch(c -> c.contains("Thaumaturgy")),
					"Otherworldly Presence: " + cantrips);
			assertTrue(list(sheet.get("feats")).stream().anyMatch(f -> "Savage Attacker".equals(f.get("name"))),
					"Soldier's feat");
			assertTrue(list(sheet.get("species_traits")).stream().anyMatch(t -> "Infernal".equals(t.get("chosen"))),
					sheet.get("species_traits").toString());

			// Fire resistance from the legacy is applied in the damage pipeline.
			Map<String, Object> burned = engine.runtime().applyRuntimeChange(op(), campaign, pc,
					map("kind", "DAMAGE", "amount", 10, "damage_type", "fire", "reason", "test"));
			assertEquals(5, burned.get("damage"), "fire resistance halves");

			// Levels 2 and 3: at 3 the legacy grants Hellish Rebuke, always prepared with a free cast per Long Rest.
			engine.runtime().awardXp(op(), campaign, List.of(pc), 900, "MILESTONE", "x");
			for (int level = 2; level <= 3; level++) {
				String t = (String) m(engine.levelUps().begin(op(), campaign, pc).get("transaction")).get("ref");
				Map<String, Object> committed = engine.levelUps().commit(op(), campaign, t, null);
				if (level == 3) {
					assertEquals(List.of("Hellish Rebuke"), committed.get("species_spells_granted"));
				}
			}
			String bandit = (String) engine.runtime()
					.materialize(op(), campaign, "Bandit", null, null, null, null, null, false).get("character");
			dice.queue(1, 5, 5);
			Map<String, Object> rebuke = engine.spells().cast(op(), campaign, pc, "Hellish Rebuke", null,
					List.of(bandit), null);
			assertEquals("species:hellish-rebuke", m(rebuke.get("slot")).get("free_cast"), "cast without a slot");
			assertEquals(10, list(rebuke.get("targets")).get(0).get("damage"), "2d10 fire (5+5), failed save");
			// The free cast is spent; the next one uses a pact slot.
			dice.queue(1, 5, 5, 5);
			Map<String, Object> again = engine.spells().cast(op(), campaign, pc, "Hellish Rebuke", null,
					List.of(bandit), null);
			assertNotNull(m(again.get("slot")).get("pact_slot_level"), again.toString());
		}
	}

	@Test
	void dwarvenToughnessAndAlertInitiative() throws Exception {
		try (Engine engine = TestCampaigns.engine(TestCampaigns.tempDb("origins-dwarf"))) {
			ScriptedRollService dice = (ScriptedRollService) engine.roller();
			String campaign = setup(engine, "Hold");
			String pc = (String) engine.characters().createDraft(op(), campaign,
					map("name", "Bruni", "species", "Dwarf", "class", "Fighter", "ability_scores",
							map("STR", 15, "CON", 14, "DEX", 13, "WIS", 12, "INT", 10, "CHA", 8), "background",
							"Criminal", "background_ability_scores", map("DEX", 2, "CON", 1), "skills",
							List.of("Athletics", "Perception"), "personality", "x", "starting_equipment", "A"),
					true).get("character");
			Map<String, Object> sheet = m(engine.characters().validateDraft(campaign, pc).get("review"));
			assertEquals(10 + 2 + 1, m(sheet.get("hp")).get("max"), "d10 + CON 15 (+2) + Dwarven Toughness");
			engine.characters().commitDraft(op(), campaign, pc, null);
			engine.campaigns().commitSetup(op(), campaign, null);
			engine.sessions().bootstrap(op(), campaign, null);

			// Alert (Criminal's feat): the proficiency bonus is added to initiative.
			String bandit = (String) engine.runtime()
					.materialize(op(), campaign, "Bandit", null, null, null, null, null, false).get("character");
			dice.queue(10, 50, 10, 50);
			Map<String, Object> started = engine.encounters().start(op(), campaign,
					map("party", List.of(pc), "foes", List.of(bandit)), null, null, null, null, null);
			Map<String, Object> pcRoll = list(started.get("initiative_rolls")).get(0);
			assertEquals("Bruni", pcRoll.get("name"));
			assertEquals(10 + 2 + 2, pcRoll.get("initiative"), "d20 10 + DEX 15 (+2) + Alert proficiency 2");
			engine.encounters().end(op(), campaign, (String) started.get("encounter"), "NEGOTIATED", "x");

			// Dwarven Toughness raises every gain by 1; default policy FIRST_3_MAX takes the full die at level 2.
			engine.runtime().awardXp(op(), campaign, List.of(pc), 300, "MILESTONE", "x");
			Map<String, Object> begun = engine.levelUps().begin(op(), campaign, pc);
			assertEquals(10 + 2 + 1, m(begun.get("chosen")).get("hp_gain"),
					"FIRST_3_MAX: full d10 + CON 2 + Dwarven Toughness 1");
			assertEquals(ErrorCode.INVALID_ARGUMENT,
					assertThrows(RpgException.class,
							() -> engine.levelUps().update(op(), campaign, null, null, map("hp_method", "AVERAGE")))
							.code(),
					"hp is fixed by the campaign policy");
		}
	}

	@Test
	void orcRelentlessEnduranceSavageAttackerAndTrackedResources() throws Exception {
		try (Engine engine = TestCampaigns.engine(TestCampaigns.tempDb("origins-orc"))) {
			ScriptedRollService dice = (ScriptedRollService) engine.roller();
			String campaign = setup(engine, "Steading");
			String pc = (String) engine.characters()
					.createDraft(op(), campaign,
							map("name", "Hilda", "species", "Human", "class", "Fighter", "ability_scores",
									map("STR", 15, "CON", 14, "DEX", 13, "WIS", 12, "INT", 10, "CHA", 8), "background",
									"Soldier", "background_ability_scores", map("STR", 2, "CON", 1), "background_tool",
									"srd5e:item/gaming-set-dice", "species_skill", "Insight", "origin_feat",
									map("feat", "Skilled", "proficiencies", List.of("Nature", "Survival", "Medicine")),
									"skills", List.of("Acrobatics", "Perception"), "personality", "x",
									"starting_equipment", "A"),
							true)
					.get("character");
			String orc = (String) engine.characters()
					.createDraft(op(), campaign,
							map("name", "Gruk", "species", "Orc", "class", "Fighter", "ability_scores",
									map("STR", 15, "CON", 14, "DEX", 13, "WIS", 12, "INT", 10, "CHA", 8), "background",
									"Criminal", "background_ability_scores", map("DEX", 2, "CON", 1), "skills",
									List.of("Athletics", "Perception"), "personality", "x"),
							false)
					.get("character");
			engine.characters().commitDraft(op(), campaign, pc, null);
			engine.characters().commitDraft(op(), campaign, orc, null);
			engine.campaigns().commitSetup(op(), campaign, null);
			engine.sessions().bootstrap(op(), campaign, null);

			// Human Resourceful: Heroic Inspiration is tracked; the GM spends and restores it explicitly.
			Map<String, Object> sheet = engine.characters().characterSheet(campaign, pc, "PLAY");
			assertTrue(list(sheet.get("resources")).stream().anyMatch(
					r -> "heroic_inspiration".equals(r.get("ref")) && Integer.valueOf(1).equals(r.get("current"))),
					sheet.get("resources").toString());
			Map<String, Object> used = engine.runtime().applyRuntimeChange(op(), campaign, pc,
					map("kind", "USE_RESOURCE", "resource", "heroic_inspiration", "reason", "reroll the missed save"));
			assertEquals(0, m(used.get("resource")).get("current"));
			assertEquals(ErrorCode.INSUFFICIENT_RESOURCE,
					assertThrows(RpgException.class, () -> engine.runtime().applyRuntimeChange(op(), campaign, pc,
							map("kind", "USE_RESOURCE", "resource", "heroic_inspiration"))).code());
			assertEquals(ErrorCode.INVALID_ARGUMENT,
					assertThrows(RpgException.class, () -> engine.runtime().applyRuntimeChange(op(), campaign, pc,
							map("kind", "USE_RESOURCE", "resource", "no_such_thing"))).code());
			engine.rest().rest(op(), campaign, "LONG", null, null);
			assertTrue(
					list(engine.characters().characterSheet(campaign, pc, "PLAY").get("resources")).stream()
							.anyMatch(r -> "heroic_inspiration".equals(r.get("ref"))
									&& Integer.valueOf(1).equals(r.get("current"))),
					"a Long Rest grants Heroic Inspiration again");

			// Orc Relentless Endurance: a drop to exactly 0 (no overkill) leaves Gruk at 1 HP, once per Long Rest.
			int orcHp = (Integer) m(m(engine.characters().characterSheet(campaign, orc, "PLAY")).get("hp")).get("max");
			Map<String, Object> dropped = engine.runtime().applyRuntimeChange(op(), campaign, orc,
					map("kind", "DAMAGE", "amount", orcHp, "damage_type", "bludgeoning", "reason", "test"));
			assertNotNull(dropped.get("relentless_endurance"), dropped.toString());
			assertEquals(1, m(dropped.get("hp")).get("current"));
			assertEquals("ALIVE", dropped.get("life_state"));

			// Savage Attacker (Soldier's feat): the first weapon hit each turn rolls its dice twice, higher total kept.
			dice.queue(20, 50, 1, 50);
			String enc = (String) engine.encounters().start(op(), campaign,
					map("party", List.of(pc), "foes", List.of(orc)), null, null, null, null, null).get("encounter");
			dice.queue(15, 1, 1, 6, 6);
			Map<String, Object> swing = engine.encounters().perform(op(), campaign, enc, pc,
					map("kind", "ATTACK", "target", orc, "weapon", "Greatsword"), false);
			assertEquals(Boolean.TRUE, swing.get("hit"));
			Map<String, Object> savage = m(swing.get("savage_attacker"));
			assertEquals(1 + 1 + 3, savage.get("first_total"), "2d6 (1+1) + STR 17 (+3)");
			assertEquals(6 + 6 + 3, savage.get("second_total"));
			assertEquals(15, swing.get("damage"), "the higher of the two rolls is used");
		}
	}
}
