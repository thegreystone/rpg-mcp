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
import se.hirt.mcp.rpg.content.ContentService;
import se.hirt.mcp.rpg.content.RulesData;
import se.hirt.mcp.rpg.dice.DiceExpression;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Structural integrity of the embedded SRD 5.2.1 seed data: every reference resolves, every weapon has parseable damage
 * and known properties, every class bundle is grantable.
 */
class SeedDataTest {

	private static final Set<String> WEAPON_PROPERTIES = Set.of("ammunition", "finesse", "heavy", "light", "loading",
			"reach", "thrown", "two-handed", "versatile");
	private static final Set<String> MASTERIES = Set.of("cleave", "graze", "nick", "push", "sap", "slow", "topple",
			"vex");

	@Test
	@SuppressWarnings("unchecked")
	void itemSeedIsInternallyConsistent() throws Exception {
		try (Engine engine = TestCampaigns.engine(TestCampaigns.tempDb("seed"))) {
			RulesData rules = engine.rules();
			List<RulesData.Definition> items = rules.ofKind("ITEM");
			assertTrue(items.size() >= 190, "expected the full equipment tables, got " + items.size());
			for (RulesData.Definition d : items) {
				Map<String, Object> p = d.payload();
				assertTrue(ContentService.ITEM_TYPES.contains(String.valueOf(p.get("type"))),
						d.id() + " has unknown type " + p.get("type"));
				assertTrue(((Number) p.get("cost_cp")).longValue() >= 0, d.id());
				assertTrue(((Number) p.get("weight_lb")).doubleValue() >= 0, d.id());
				switch (String.valueOf(p.get("type"))) {
				case "WEAPON" -> {
					Map<String, Object> damage = (Map<String, Object>) p.get("damage");
					DiceExpression.parse(String.valueOf(damage.get("dice")));
					for (Object prop : (List<Object>) p.get("properties")) {
						assertTrue(WEAPON_PROPERTIES.contains(prop.toString()), d.id() + " property " + prop);
					}
					assertTrue(MASTERIES.contains(String.valueOf(p.get("mastery"))), d.id() + " mastery");
					if (((List<Object>) p.get("properties")).contains("ammunition")) {
						assertTrue(rules.find(String.valueOf(p.get("ammunition"))).isPresent(),
								d.id() + " ammunition ref");
					}
					assertEquals(((List<Object>) p.get("properties")).contains("two-handed") ? "TWO_HANDS" : "ONE_HAND",
							p.get("slot"), d.id() + " slot");
				}
				case "ARMOR" -> {
					Map<String, Object> armor = (Map<String, Object>) p.get("armor");
					assertTrue(Set.of("LIGHT", "MEDIUM", "HEAVY").contains(String.valueOf(armor.get("category"))),
							d.id());
					assertNotNull(armor.get("base_ac"), d.id());
				}
				case "PACK" -> {
					for (Map<String, Object> c : (List<Map<String, Object>>) p.get("contents")) {
						RulesData.Definition content = rules.find(String.valueOf(c.get("item"))).orElse(null);
						assertNotNull(content, d.id() + " content " + c.get("item"));
						assertNotEquals("PACK", content.payload().get("type"), "packs must not nest");
					}
				}
				case "AMMUNITION" ->
						assertTrue(rules.find(String.valueOf(p.get("container"))).isPresent(), d.id() + " container");
				default -> {
				}
				}
			}
			assertEquals(38, items.stream().filter(d -> "WEAPON".equals(d.payload().get("type"))).count(),
					"SRD 5.2.1 lists 38 weapons (10 simple melee, 4 simple ranged, 18 martial melee, 6 martial ranged)");
			assertEquals(12, items.stream().filter(d -> "ARMOR".equals(d.payload().get("type"))).count(),
					"SRD 5.2.1 lists 12 armors");
			assertEquals(7, items.stream().filter(d -> "PACK".equals(d.payload().get("type"))).count());
		}
	}

	@Test
	@SuppressWarnings("unchecked")
	void classStartingEquipmentResolves() throws Exception {
		try (Engine engine = TestCampaigns.engine(TestCampaigns.tempDb("seed-classes"))) {
			RulesData rules = engine.rules();
			assertEquals(12, rules.ofKind("CLASS").size());
			for (RulesData.Definition cls : rules.ofKind("CLASS")) {
				Map<String, Object> options = (Map<String, Object>) cls.payload().get("starting_equipment");
				assertNotNull(options, cls.id());
				assertTrue(options.values().stream().anyMatch(o -> !((Map<String, Object>) o).containsKey("items")),
						cls.id() + " needs a gold-only option");
				for (var e : options.entrySet()) {
					Map<String, Object> option = (Map<String, Object>) e.getValue();
					assertTrue(((Number) option.get("gold_gp")).intValue() >= 0);
					for (Map<String, Object> item : (List<Map<String, Object>>) option.getOrDefault("items",
							List.of())) {
						String ref =
								item.containsKey("choice") ? (String) item.get("default") : (String) item.get("item");
						assertTrue(rules.find(ref).isPresent(), cls.id() + " option " + e.getKey() + " item " + ref);
					}
				}
				Map<String, Object> skills = (Map<String, Object>) cls.payload().get("skill_choices");
				if (!"ANY".equals(skills.get("options"))) {
					for (Object s : (List<Object>) skills.get("options")) {
						assertTrue(rules.find(s.toString()).isPresent(), cls.id() + " skill " + s);
					}
				}
			}
			assertEquals(18, rules.ofKind("SKILL").size());
			assertEquals(9, rules.ofKind("SPECIES").size());
		}
	}

	@Test
	@SuppressWarnings("unchecked")
	void creatureSeedIsInternallyConsistent() throws Exception {
		try (Engine engine = TestCampaigns.engine(TestCampaigns.tempDb("seed-creatures"))) {
			RulesData rules = engine.rules();
			List<Map<String, Object>> xpRows = (List<Map<String, Object>>) rules.find("srd5e:table/xp-by-cr")
					.orElseThrow().payload().get("rows");
			assertEquals(34, xpRows.size(), "CR 0, 1/8, 1/4, 1/2 and 1..30");
			List<RulesData.Definition> creatures = rules.ofKind("CREATURE");
			assertTrue(creatures.size() >= 12);
			for (RulesData.Definition d : creatures) {
				Map<String, Object> p = d.payload();
				Map<String, Object> row = xpRows.stream().filter(r -> r.get("cr").equals(p.get("cr"))).findFirst()
						.orElseThrow(() -> new AssertionError(d.id() + " cr"));
				if ("0".equals(p.get("cr"))) {
					// SRD 5.2.1: CR 0 creatures are worth 10 XP if they have attacks, 0 XP otherwise (Shrieker Fungus, Frog).
					assertTrue(List.of(0, 10).contains(p.get("xp_value")), d.id() + " CR 0 xp must be 0 or 10");
				} else {
					assertEquals(row.get("xp"), p.get("xp_value"), d.id() + " xp must match the CR table");
				}
				assertEquals(row.get("cr_times_8"), p.get("cr_times_8"), d.id());
				assertEquals(row.get("proficiency_bonus"), p.get("proficiency_bonus"), d.id());
				Map<String, Object> abilities = (Map<String, Object>) p.get("abilities");
				for (String a : List.of("STR", "DEX", "CON", "INT", "WIS", "CHA")) {
					assertNotNull(abilities.get(a), d.id() + " " + a);
				}
				Map<String, Object> hp = (Map<String, Object>) p.get("hp");
				DiceExpression.parse(String.valueOf(hp.get("dice")));
				assertTrue(((Number) hp.get("average")).intValue() > 0);
				List<Map<String, Object>> actions = (List<Map<String, Object>>) p.get("actions");
				assertNotNull(actions, d.id() + " needs an actions list (the Shrieker Fungus's is empty: it only reacts)");
				for (Map<String, Object> a : actions) {
					if (String.valueOf(a.get("kind")).endsWith("ATTACK")) {
						assertNotNull(a.get("attack_bonus"), d.id() + " " + a.get("name"));
						for (Map<String, Object> dmg : (List<Map<String, Object>>) a.get("damage")) {
							DiceExpression.parse(String.valueOf(dmg.get("dice")));
							assertTrue(
									se.hirt.mcp.rpg.rules.Combat.DAMAGE_TYPES.contains(String.valueOf(dmg.get("type"))),
									d.id() + " damage type " + dmg.get("type"));
						}
					}
				}
				for (String key : List.of("damage_resistances", "damage_vulnerabilities", "damage_immunities")) {
					for (Object t : (List<Object>) p.getOrDefault(key, List.of())) {
						assertTrue(se.hirt.mcp.rpg.rules.Combat.DAMAGE_TYPES.contains(t.toString()),
								d.id() + " " + key + " " + t);
					}
				}
			}
		}
	}
}
