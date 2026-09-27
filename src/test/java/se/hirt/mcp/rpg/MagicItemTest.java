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
import se.hirt.mcp.rpg.dice.ScriptedRollService;
import se.hirt.mcp.rpg.inventory.MagicItems;
import se.hirt.mcp.rpg.inventory.Treasure;
import se.hirt.mcp.rpg.protocol.ErrorCode;
import se.hirt.mcp.rpg.protocol.RpgException;
import se.hirt.mcp.rpg.rules.Combat;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static se.hirt.mcp.rpg.TestCampaigns.*;

/**
 * Magic items end to end (RULES_ENGINE.md §10): the seeded SRD 5.2.1 Magic Items A–Z, template
 * instantiation on a base item, the +N of weapons, armor, shields and ammunition, the modifiers of
 * a Ring of Protection, wearable slots and the attunement limit, potions of every potency, the
 * sheet views, the content filters, and the level-appropriate treasure guidance that completed
 * quests, major encounters and bootstrap carry.
 */
class MagicItemTest {

	@SuppressWarnings("unchecked")
	private static Map<String, Object> m(Object o) {
		return (Map<String, Object>) o;
	}

	@SuppressWarnings("unchecked")
	private static List<Map<String, Object>> l(Object o) {
		return (List<Map<String, Object>>) o;
	}

	private static int ac(Engine engine, String campaign, String pc) {
		return ((Number) m(engine.characters().characterSheet(campaign, pc, "PLAY").get("armor_class")).get("value"))
				.intValue();
	}

	/** Grants one item and returns its inventory entry ref. */
	private static String grant(Engine engine, String campaign, String pc, Map<String, Object> spec) {
		Map<String, Object> r = engine.inventory().grantLoot(op(), campaign, pc, List.of(spec), null, "QUEST", null);
		return (String) l(r.get("granted")).get(0).get("entry");
	}

	// ── the seed ───────────────────────────────────────────────────────

	@Test
	void theMagicItemSeedIsComplete() throws Exception {
		try (Engine engine = engine(tempDb("magic-seed"))) {
			RulesData rules = engine.rules();
			List<RulesData.Definition> magic = rules.ofKind("ITEM").stream()
					.filter(d -> MagicItems.isMagic(d.payload())).toList();
			assertEquals(260, magic.size(), "SRD 5.2.1 Magic Items A-Z, plus the three higher potions of healing");
			int templates = 0;
			int engineEnforced = 0;
			for (RulesData.Definition d : magic) {
				Map<String, Object> p = d.payload();
				Map<String, Object> mg = MagicItems.magic(p);
				assertTrue(ContentService.ITEM_TYPES.contains(String.valueOf(p.get("type"))), d.id() + " type");
				String rarity = String.valueOf(mg.get("rarity"));
				assertTrue(MagicItems.RARITIES.contains(rarity) || rarity.equals("VARIES"),
						d.id() + " rarity " + rarity);
				assertEquals(Boolean.FALSE, p.get("text_is_paraphrase"), d.id() + " must be verbatim SRD text");
				assertTrue(String.valueOf(p.get("text")).length() >= 40, d.id() + " has a body");
				assertTrue(String.valueOf(p.get("summary")).length() > 0, d.id() + " has a summary");
				assertTrue(((Number) p.get("cost_cp")).longValue() >= 0, d.id());
				if (Boolean.TRUE.equals(mg.get("template"))) {
					templates++;
					assertTrue(
							Set.of("WEAPON", "ARMOR", "SHIELD", "AMMUNITION").contains(String.valueOf(p.get("type"))),
							d.id() + " templates are weapons, armor, shields and ammunition");
					assertNotNull(mg.get("applies_to"), d.id());
				}
				if (mg.get("bonus_by_rarity") instanceof Map<?, ?> b) {
					for (Object v : b.values()) {
						int n = ((Number) v).intValue();
						assertTrue(n >= 1 && n <= 3, d.id() + " bonus " + n);
					}
				}
				if (p.get("consumable") instanceof Map<?, ?> c) {
					DiceExpression.parse(String.valueOf(c.get("heal")));
				}
				if ("ENGINE".equals(mg.get("enforcement"))) {
					engineEnforced++;
					assertTrue(
							mg.get("bonus_by_rarity") != null || p.get("modifiers") != null
									|| p.get("consumable") != null,
							d.id() + " claims engine enforcement without a mechanic");
				}
			}
			assertEquals(52, templates);
			assertEquals(11, engineEnforced);
			// The potion of healing is one SRD entry with four potencies; the seed carries one item per potency.
			for (String name : List.of("Potion of Healing", "Potion of Healing (Greater)",
					"Potion of Healing (Superior)", "Potion of Healing (Supreme)")) {
				assertTrue(rules.resolve("ITEM", name).isPresent(), name);
			}
			assertEquals("2d4+2",
					m(rules.resolve("ITEM", "Potion of Healing").get().payload().get("consumable")).get("heal"));
			assertEquals("POTION", rules.resolve("ITEM", "Potion of Healing").get().payload().get("type"));
			Map<String, Object> ring = rules.resolve("ITEM", "Ring of Protection").get().payload();
			assertEquals(Map.of("ac_bonus", 1, "save_bonus", 1), ring.get("modifiers"));
			assertEquals("RING", ring.get("slot"));
			assertTrue(MagicItems.requiresAttunement(ring));
			assertEquals("Rare, requires attunement", MagicItems.label(ring));
			// Wearables carry a slot; a carried item does not.
			assertEquals("CLOAK", rules.resolve("ITEM", "Cloak of Protection").get().payload().get("slot"));
			assertEquals("BOOTS", rules.resolve("ITEM", "Boots of Elvenkind").get().payload().get("slot"));
			assertNull(rules.resolve("ITEM", "Bag of Holding").get().payload().get("slot"));
			// Values follow the rarity table; a Bag of Holding is Uncommon (400 GP), a potion is Common halved.
			assertEquals(40_000L,
					((Number) rules.resolve("ITEM", "Bag of Holding").get().payload().get("cost_cp")).longValue());
			assertEquals(5_000L,
					((Number) rules.resolve("ITEM", "Potion of Healing").get().payload().get("cost_cp")).longValue());
		}
	}

	// ── templates and the +N ───────────────────────────────────────────

	@Test
	void templatesAreInstantiatedOnABaseAndTheBonusReachesAttacks() throws Exception {
		try (Engine engine = engine(tempDb("magic-templates"))) {
			String campaign = committedCampaign(engine);
			engine.sessions().bootstrap(op(), campaign, null);
			String pc = "character:1";

			// A template without a base is refused with an explanation.
			RpgException noBase = assertThrows(RpgException.class, () -> engine.inventory().grantLoot(op(), campaign,
					pc, List.of(map("item", "Weapon, +1, +2, or +3")), null, "QUEST", null));
			assertEquals(ErrorCode.INVALID_ARGUMENT, noBase.code());
			assertTrue(noBase.getMessage().contains("base"), noBase.getMessage());

			// The +N form: base and bonus in one string.
			Map<String, Object> plus = engine.inventory().grantLoot(op(), campaign, pc,
					List.of(map("item", "+1 Longsword")), null, "QUEST", null);
			Map<String, Object> g = l(plus.get("granted")).get(0);
			assertEquals("+1 Longsword", g.get("name"));
			assertEquals("Uncommon", g.get("magic"));
			assertTrue(String.valueOf(g.get("item")).startsWith("content:"), "instantiated as campaign content");

			// The explicit form with a bonus, and a named template with a name of its own.
			Map<String, Object> two = engine.inventory()
					.grantLoot(op(), campaign, pc,
							List.of(map("item", "Weapon, +1, +2, or +3", "base", "Longsword", "bonus", 2),
									map("item", "Flame Tongue", "base", "Longsword", "name", "Ember")),
							null, "QUEST", null);
			assertEquals("+2 Longsword", l(two.get("granted")).get(0).get("name"));
			assertEquals("Rare", l(two.get("granted")).get(0).get("magic"));
			assertEquals("Ember", l(two.get("granted")).get(1).get("name"));
			assertEquals("Rare, requires attunement", l(two.get("granted")).get(1).get("magic"));

			// The definition carries the base's mechanics plus the magic, priced base + rarity value.
			Map<String, Object> defs = engine.content().definitions(campaign, "ITEM", null, "+1 Longsword", null, null,
					null, true, null, 10, "FULL");
			Map<String, Object> def = l(defs.get("items")).stream().filter(d -> "+1 Longsword".equals(d.get("name")))
					.findFirst().orElseThrow();
			Map<String, Object> payload = m(def.get("payload"));
			assertEquals("WEAPON", payload.get("type"));
			assertEquals("1d8", m(payload.get("damage")).get("dice"));
			assertEquals(1, MagicItems.bonus(payload));
			assertEquals(1_500L + 40_000L, ((Number) payload.get("cost_cp")).longValue(), "Longsword + Uncommon");
			assertEquals("srd5e:item/longsword", MagicItems.magic(payload).get("base_ref"));

			// A second +1 Longsword reuses the definition instead of making another.
			engine.inventory().grantLoot(op(), campaign, pc, List.of(map("item", "+1 Longsword")), null, "QUEST", null);
			assertEquals(1,
					l(engine.content()
							.definitions(campaign, "ITEM", null, "+1 Longsword", null, null, null, true, null, 10,
									"SUMMARY")
							.get("items")).stream().filter(d -> "+1 Longsword".equals(d.get("name"))).count());

			// Wrong bases are refused: Flame Tongue is made on a sword, a +N needs a mundane base.
			RpgException wrongKind = assertThrows(RpgException.class, () -> engine.inventory().grantLoot(op(), campaign,
					pc, List.of(map("item", "Dagger of Venom", "base", "Longsword")), null, "QUEST", null));
			assertEquals(ErrorCode.VALIDATION_FAILED, wrongKind.code());
			RpgException wrongType = assertThrows(RpgException.class, () -> engine.inventory().grantLoot(op(), campaign,
					pc, List.of(map("item", "+1 Chain Mail", "base", "Longsword")), null, "QUEST", null));
			assertEquals(ErrorCode.INVALID_ARGUMENT, wrongType.code());
			// A template cannot be bought as it is.
			RpgException buy = assertThrows(RpgException.class, () -> engine.inventory().trade(op(), campaign, pc,
					"BUY", "Armor, +1, +2, or +3", null, 1, null, null, null));
			assertEquals(ErrorCode.INVALID_ARGUMENT, buy.code());

			// In a fight the +1 reaches the attack roll: the same weapon, plain and enchanted, one point apart.
			String bandit = (String) engine.runtime()
					.materialize(op(), campaign, "Bandit", "Grubb", null, null, null, null, false).get("character");
			engine.inventory().grantLoot(op(), campaign, pc, List.of(map("item", "Longsword")), null, "QUEST", null);
			String encounter = (String) engine.encounters()
					.start(op(), campaign, map("a", List.of(pc), "b", List.of(bandit)), null, null, null, null, null)
					.get("encounter");
			if (!pc.equals(
					m(engine.encounters().encounterState(campaign, encounter, 3).get("turn")).get("character"))) {
				engine.encounters().perform(op(), campaign, encounter, bandit, map("kind", "END_TURN"), true);
			}
			Map<String, Object> plain = engine.encounters().perform(op(), campaign, encounter, pc,
					map("kind", "ATTACK", "attack", "Longsword", "target", bandit), true);
			int plainModifier = ((Number) m(plain.get("attack_roll")).get("modifier")).intValue();
			engine.encounters().perform(op(), campaign, encounter, bandit, map("kind", "END_TURN"), true);
			Map<String, Object> magic = engine.encounters().perform(op(), campaign, encounter, pc,
					map("kind", "ATTACK", "attack", "+1 Longsword", "target", bandit), true);
			assertEquals(plainModifier + 1, ((Number) m(magic.get("attack_roll")).get("modifier")).intValue(),
					"the +1 applies to the attack roll: " + magic.get("summary"));

			// And the +N of magic ammunition rides on a mundane bow.
			Combat.AttackProfile bow = new Combat.AttackProfile("Longbow", true, 5,
					List.of(new Combat.DamagePart("1d8", 3, "piercing")), true, "srd5e:item/arrow", null, "weapon",
					false);
			Combat.AttackProfile withArrow = Combat.withBonus(bow, 2);
			assertEquals(7, withArrow.attackBonus());
			assertEquals(5, withArrow.damage().get(0).modifier());
			assertSame(bow, Combat.withBonus(bow, 0));
		}
	}

	@Test
	void magicAmmunitionAddsItsBonusToTheShot() throws Exception {
		try (Engine engine = engine(tempDb("magic-ammo"))) {
			String campaign = committedCampaign(engine);
			engine.sessions().bootstrap(op(), campaign, null);
			String pc = "character:1";
			engine.inventory().grantLoot(op(), campaign, pc,
					List.of(map("item", "Shortbow"), map("item", "Arrow", "quantity", 20),
							map("item", "+1 Arrow", "quantity", 5), map("item", "Bolt", "quantity", 5)),
					null, "QUEST", null);
			String bandit = (String) engine.runtime()
					.materialize(op(), campaign, "Bandit", "Grubb", null, null, null, null, false).get("character");
			String encounter = (String) engine.encounters()
					.start(op(), campaign, map("a", List.of(pc), "b", List.of(bandit)), null, null, null, null, null)
					.get("encounter");
			if (!pc.equals(
					m(engine.encounters().encounterState(campaign, encounter, 3).get("turn")).get("character"))) {
				engine.encounters().perform(op(), campaign, encounter, bandit, map("kind", "END_TURN"), true);
			}
			Map<String, Object> plain = engine.encounters().perform(op(), campaign, encounter, pc,
					map("kind", "ATTACK", "attack", "Shortbow", "target", bandit), true);
			assertEquals("Arrow", m(plain.get("ammunition")).get("name"), "the mundane arrows by default");
			int plainModifier = ((Number) m(plain.get("attack_roll")).get("modifier")).intValue();
			engine.encounters().perform(op(), campaign, encounter, bandit, map("kind", "END_TURN"), true);
			Map<String, Object> magic = engine.encounters().perform(op(), campaign, encounter, pc,
					map("kind", "ATTACK", "attack", "Shortbow", "target", bandit, "ammunition", "+1 Arrow"), true);
			assertEquals("+1 Arrow", m(magic.get("ammunition")).get("name"));
			assertEquals(1, m(magic.get("ammunition")).get("bonus"));
			assertEquals(4L, ((Number) m(magic.get("ammunition")).get("remaining")).longValue(), "one arrow spent");
			assertEquals(plainModifier + 1, ((Number) m(magic.get("attack_roll")).get("modifier")).intValue());
			// Ammunition of another kind is refused.
			engine.encounters().perform(op(), campaign, encounter, bandit, map("kind", "END_TURN"), true);
			RpgException wrong = assertThrows(RpgException.class,
					() -> engine.encounters().perform(op(), campaign, encounter, pc,
							map("kind", "ATTACK", "attack", "Shortbow", "target", bandit, "ammunition", "Bolt"), true));
			assertEquals(ErrorCode.VALIDATION_FAILED, wrong.code());
		}
	}

	// ── armor class, saves, slots and attunement ───────────────────────

	@Test
	void wornMagicChangesArmorClassAndSavesWhileEquipped() throws Exception {
		try (Engine engine = engine(tempDb("magic-worn"))) {
			String campaign = committedCampaign(engine);
			engine.sessions().bootstrap(op(), campaign, null);
			String pc = "character:1";
			int unarmored = ac(engine, campaign, pc);

			// +1 Chain Mail: 16 + 1, no Dexterity.
			String mail = grant(engine, campaign, pc, map("item", "+1 Chain Mail"));
			Map<String, Object> worn = engine.inventory().equip(op(), campaign, pc, mail, true);
			assertEquals(17, ((Number) m(worn.get("armor_class")).get("value")).intValue(),
					String.valueOf(worn.get("armor_class")));
			assertTrue(String.valueOf(m(worn.get("armor_class")).get("basis")).contains("+ 1 magic"));
			// A +2 Shield adds its own 2 and the enchantment.
			String shield = grant(engine, campaign, pc,
					map("item", "Shield, +1, +2, or +3", "base", "Shield", "bonus", 2));
			engine.inventory().equip(op(), campaign, pc, shield, true);
			assertEquals(21, ac(engine, campaign, pc));
			// A Ring of Protection adds one more, and one to every saving throw, only while worn.
			String ring = grant(engine, campaign, pc, map("item", "Ring of Protection"));
			ScriptedRollService dice = (ScriptedRollService) engine.roller();
			dice.queue(10);
			Map<String, Object> before = engine.checks().resolveCheck(op(), campaign, pc, "SAVING_THROW", "DEX", null,
					10, null, "test");
			assertEquals(((Number) m(before.get("roll")).get("total")).intValue(),
					((Number) before.get("total")).intValue());
			Map<String, Object> ringOn = engine.inventory().equip(op(), campaign, pc, ring, true);
			assertEquals(22, ((Number) m(ringOn.get("armor_class")).get("value")).intValue());
			assertEquals(List.of("Ring of Protection"), ringOn.get("attuned"));
			dice.queue(10);
			Map<String, Object> after = engine.checks().resolveCheck(op(), campaign, pc, "SAVING_THROW", "DEX", null,
					10, null, "test");
			assertEquals(((Number) m(after.get("roll")).get("total")).intValue() + 1,
					((Number) after.get("total")).intValue(), "the ring's +1 to saving throws");
			engine.inventory().equip(op(), campaign, pc, ring, false);
			assertEquals(21, ac(engine, campaign, pc));

			// Bracers of Defense count only without armor and shield.
			String bracers = grant(engine, campaign, pc, map("item", "Bracers of Defense"));
			engine.inventory().equip(op(), campaign, pc, bracers, true);
			assertEquals(21, ac(engine, campaign, pc), "no bonus while armored");
			engine.inventory().equip(op(), campaign, pc, mail, false);
			engine.inventory().equip(op(), campaign, pc, shield, false);
			assertEquals(unarmored + 2, ac(engine, campaign, pc), "the bracers' +2 once unarmored");
			assertEquals(2, m(engine.characters().characterSheet(campaign, pc, "PLAY").get("armor_class"))
					.get("unarmored_bonus"));
		}
	}

	@Test
	void slotsAndAttunementAreEnforced() throws Exception {
		try (Engine engine = engine(tempDb("magic-attune"))) {
			String campaign = committedCampaign(engine);
			engine.sessions().bootstrap(op(), campaign, null);
			String pc = "character:1";
			String ring1 = grant(engine, campaign, pc, map("item", "Ring of Protection"));
			String ring2 = grant(engine, campaign, pc, map("item", "Ring of Jumping"));
			String ring3 = grant(engine, campaign, pc, map("item", "Ring of Swimming"));
			String cloak = grant(engine, campaign, pc, map("item", "Cloak of Protection"));
			String cloak2 = grant(engine, campaign, pc, map("item", "Cloak of Elvenkind"));
			String boots = grant(engine, campaign, pc, map("item", "Boots of Levitation"));
			String bag = grant(engine, campaign, pc, map("item", "Bag of Holding"));

			engine.inventory().equip(op(), campaign, pc, ring1, true);
			engine.inventory().equip(op(), campaign, pc, ring2, true);
			RpgException thirdRing = assertThrows(RpgException.class,
					() -> engine.inventory().equip(op(), campaign, pc, ring3, true));
			assertEquals(ErrorCode.VALIDATION_FAILED, thirdRing.code());
			assertTrue(thirdRing.getMessage().contains("ring"), thirdRing.getMessage());

			Map<String, Object> cloaked = engine.inventory().equip(op(), campaign, pc, cloak, true);
			assertEquals(3, l(cloaked.get("attuned")).size(), "three attuned items: " + cloaked.get("attuned"));
			RpgException secondCloak = assertThrows(RpgException.class,
					() -> engine.inventory().equip(op(), campaign, pc, cloak2, true));
			assertEquals(ErrorCode.VALIDATION_FAILED, secondCloak.code());
			assertTrue(secondCloak.getMessage().contains("cloak"), secondCloak.getMessage());

			// A fourth attunement is refused (SRD 5.2.1 "Attunement"); after removing one it goes on.
			RpgException fourth = assertThrows(RpgException.class,
					() -> engine.inventory().equip(op(), campaign, pc, boots, true));
			assertEquals(ErrorCode.VALIDATION_FAILED, fourth.code());
			assertTrue(fourth.getMessage().contains("attuned to 3"), fourth.getMessage());
			engine.inventory().equip(op(), campaign, pc, ring2, false);
			engine.inventory().equip(op(), campaign, pc, boots, true);

			// A Bag of Holding is carried, not worn.
			RpgException carried = assertThrows(RpgException.class,
					() -> engine.inventory().equip(op(), campaign, pc, bag, true));
			assertEquals(ErrorCode.VALIDATION_FAILED, carried.code());
			assertTrue(carried.getMessage().contains("carried"), carried.getMessage());

			// The sheets show what is carried, what is attuned, and what the GM plays.
			Map<String, Object> full = engine.characters().characterSheet(campaign, pc, "FULL");
			List<Map<String, Object>> items = l(full.get("magic_items"));
			assertEquals(7, items.size());
			Map<String, Object> ringView = items.stream().filter(i -> "Ring of Protection".equals(i.get("name")))
					.findFirst().orElseThrow();
			assertEquals(Boolean.TRUE, ringView.get("attuned"));
			assertEquals("engine", ringView.get("adjudication"));
			Map<String, Object> bagView = items.stream().filter(i -> "Bag of Holding".equals(i.get("name"))).findFirst()
					.orElseThrow();
			assertEquals("GM", bagView.get("adjudication"));
			assertTrue(String.valueOf(bagView.get("note")).contains("GM-adjudicated"));
			Map<String, Object> summary = engine.characters().characterSheet(campaign, pc, "SUMMARY");
			assertTrue(((List<?>) summary.get("magic_items")).contains("Ring of Protection (attuned)"),
					String.valueOf(summary.get("magic_items")));
			Map<String, Object> entry = l(full.get("inventory")).stream()
					.filter(i -> "Cloak of Protection".equals(i.get("name"))).findFirst().orElseThrow();
			assertEquals("Uncommon, requires attunement", entry.get("magic"));
			assertEquals(Boolean.TRUE, entry.get("attuned"));
		}
	}

	// ── potions ────────────────────────────────────────────────────────

	@Test
	void potionsOfEveryPotencyHealByTheirDiceInAndOutOfCombat() throws Exception {
		try (Engine engine = engine(tempDb("magic-potions"))) {
			String campaign = committedCampaign(engine);
			engine.sessions().bootstrap(op(), campaign, null);
			String pc = "character:1";
			engine.inventory().grantLoot(op(), campaign, pc, List.of(map("item", "Potion of Healing (Greater)"),
					map("item", "Potion of Healing", "quantity", 2)), null, "QUEST", null);
			int max = ((Number) m(engine.characters().characterSheet(campaign, pc, "PLAY").get("hp")).get("max"))
					.intValue();
			engine.runtime().applyRuntimeChange(op(), campaign, pc,
					map("kind", "DAMAGE", "amount", max - 1, "reason", "fall"));
			ScriptedRollService dice = (ScriptedRollService) engine.roller();
			dice.queue(4, 4, 4, 4);
			Map<String, Object> drunk = engine.runtime().applyRuntimeChange(op(), campaign, pc,
					map("kind", "USE_ITEM", "item", "Potion of Healing (Greater)"));
			assertEquals("4d4+4", m(drunk.get("roll")).get("expression"));
			assertEquals(Math.min(max - 1, 20), ((Number) drunk.get("healed")).intValue(), String.valueOf(drunk));
			assertEquals(Boolean.TRUE, drunk.get("consumed"));
			assertTrue(l(engine.characters().characterSheet(campaign, pc, "PLAY").get("inventory")).stream()
					.noneMatch(i -> "Potion of Healing (Greater)".equals(i.get("name"))), "the potion is used up");
			// A carried item with no encoded effect is the GM's to narrate.
			engine.inventory().grantLoot(op(), campaign, pc, List.of(map("item", "Potion of Invisibility")), null,
					"QUEST", null);
			RpgException noEffect = assertThrows(RpgException.class, () -> engine.runtime().applyRuntimeChange(op(),
					campaign, pc, map("kind", "USE_ITEM", "item", "Potion of Invisibility")));
			assertEquals(ErrorCode.CAPABILITY_UNAVAILABLE, noEffect.code());

			// In an encounter the USE_ITEM action shares the same core, for any potency.
			String bandit = (String) engine.runtime()
					.materialize(op(), campaign, "Bandit", "Grubb", null, null, null, null, false).get("character");
			String encounter = (String) engine.encounters()
					.start(op(), campaign, map("a", List.of(pc), "b", List.of(bandit)), null, null, null, null, null)
					.get("encounter");
			if (!pc.equals(
					m(engine.encounters().encounterState(campaign, encounter, 3).get("turn")).get("character"))) {
				engine.encounters().perform(op(), campaign, encounter, bandit, map("kind", "END_TURN"), true);
			}
			engine.runtime().applyRuntimeChange(op(), campaign, pc,
					map("kind", "DAMAGE", "amount", 5, "reason", "test"));
			Map<String, Object> used = engine.encounters().perform(op(), campaign, encounter, pc,
					map("kind", "USE_ITEM", "item", "Potion of Healing"), true);
			assertEquals("2d4+2", m(used.get("roll")).get("expression"));
			assertTrue(((Number) used.get("healed")).intValue() >= 1, String.valueOf(used));
		}
	}

	// ── content filters ────────────────────────────────────────────────

	@Test
	void definitionsFilterByMagicAndRarity() throws Exception {
		try (Engine engine = engine(tempDb("magic-filters"))) {
			Map<String, Object> rare = engine.content().definitions(null, "ITEM", null, null, null, null, "RARE", true,
					null, 100, "SUMMARY");
			assertEquals(83, rare.get("total"), "Rare entries of the Magic Items A-Z");
			for (Map<String, Object> item : l(rare.get("items"))) {
				assertEquals("RARE", m(item.get("magic")).get("rarity"), String.valueOf(item.get("name")));
				assertNotNull(item.get("magic_label"));
				assertNull(item.get("text"), "the summary list carries the summary, FULL detail the text");
				assertNotNull(item.get("summary"));
			}
			assertEquals(MagicItems.RARITIES, rare.get("rarities"));
			Map<String, Object> mundane = engine.content().definitions(null, "ITEM", "WEAPON", null, null, null, null,
					false, null, 100, "SUMMARY");
			assertEquals(38, mundane.get("total"), "the 38 SRD weapons, no templates");
			Map<String, Object> rings = engine.content().definitions(null, "ITEM", "RING", null, null, null, null, null,
					null, 100, "SUMMARY");
			assertEquals(22, rings.get("total"));
			RpgException bad = assertThrows(RpgException.class, () -> engine.content().definitions(null, "ITEM", null,
					null, null, null, "EPIC", null, null, 10, "SUMMARY"));
			assertEquals(ErrorCode.INVALID_ARGUMENT, bad.code());
			// Free text reaches the magic items too.
			Map<String, Object> search = engine.content().search(null, "bonus to Armor Class and saving throws", "ITEM",
					10);
			assertTrue(l(search.get("results")).stream().anyMatch(h -> "Ring of Protection".equals(h.get("name"))),
					String.valueOf(search));
		}
	}

	// ── treasure guidance ──────────────────────────────────────────────

	@Test
	void treasureFollowsTheLevel() {
		assertEquals("levels 1-4", Treasure.tier(1).name());
		assertEquals("levels 1-4", Treasure.tier(4).name());
		assertEquals("levels 5-10", Treasure.tier(5).name());
		assertEquals("levels 11-16", Treasure.tier(16).name());
		assertEquals("levels 17-20", Treasure.tier(20).name());
		assertEquals("levels 17-20", Treasure.tier(23).name());
		for (Treasure.Tier t : Treasure.TIERS) {
			assertEquals(100, t.weights().values().stream().mapToInt(Integer::intValue).sum(), t.name());
		}
		// A beginner very rarely sees a Rare item; a veteran never draws a Common one.
		assertEquals(10, Treasure.tier(1).weights().get("RARE"));
		assertNull(Treasure.tier(1).weights().get("VERY_RARE"));
		assertNull(Treasure.tier(17).weights().get("COMMON"));
		assertNotNull(Treasure.tier(17).weights().get("LEGENDARY"));
	}

	@Test
	void completedQuestsMajorEncountersAndBootstrapPointAtTreasure() throws Exception {
		try (Engine engine = engine(tempDb("magic-treasure"))) {
			String campaign = committedCampaign(engine);
			Map<String, Object> first = engine.sessions().bootstrap(op(), campaign, null);
			assertEquals(List.of(), m(first.get("meta")).get("warnings"), "a fresh level-1 party is not nagged");
			String pc = "character:1";

			Map<String, Object> offered = engine.narrative().upsert(op(), campaign, "QUEST", null,
					map("title", "Rats in the cellar", "status", "ACCEPTED"), null);
			assertNull(offered.get("treasure"), "no treasure until the quest is done");
			String quest = (String) offered.get("ref");
			ScriptedRollService dice = (ScriptedRollService) engine.roller();
			dice.queue(1, 1, 50, 1, 95, 1); // Common, Uncommon, Rare; the first item of each pool
			Map<String, Object> done = engine.narrative().upsert(op(), campaign, "QUEST", quest,
					map("status", "COMPLETED"), null);
			Map<String, Object> treasure = m(done.get("treasure"));
			assertNotNull(treasure, String.valueOf(done));
			assertEquals("QUEST_COMPLETED", treasure.get("occasion"));
			assertEquals(1, treasure.get("party_level"));
			assertEquals("levels 1-4", treasure.get("tier"));
			assertEquals(0, treasure.get("party_magic_items"));
			List<Map<String, Object>> suggestions = l(treasure.get("suggestions"));
			assertEquals(List.of("COMMON", "UNCOMMON", "RARE"),
					suggestions.stream().map(s -> s.get("rarity")).toList());
			for (Map<String, Object> s : suggestions) {
				assertTrue(String.valueOf(s.get("ref")).startsWith("srd5e:item/"), String.valueOf(s));
				assertNotNull(s.get("summary"));
			}
			assertTrue(String.valueOf(treasure.get("note")).contains("grant_loot"));
			// Completing it again does not repeat the nudge.
			assertThrows(RpgException.class,
					() -> engine.narrative().upsert(op(), campaign, "QUEST", quest, map("status", "ABANDONED"), null));

			// Two completed quests and still nothing: bootstrap says so.
			String second = (String) engine.narrative().upsert(op(), campaign, "QUEST", null,
					map("title", "The miller's debt", "status", "ACCEPTED"), null).get("ref");
			engine.narrative().upsert(op(), campaign, "QUEST", second, map("status", "COMPLETED"), null);
			engine.sessions().suspend(op(), campaign, null);
			Map<String, Object> nagged = engine.sessions().bootstrap(op(), campaign, null);
			List<?> warnings = (List<?>) m(nagged.get("meta")).get("warnings");
			assertEquals(1, warnings.size(), String.valueOf(warnings));
			assertTrue(String.valueOf(warnings.get(0)).contains("owns no magic items"), String.valueOf(warnings));
			assertTrue(String.valueOf(warnings.get(0)).contains("2 completed quests"), String.valueOf(warnings));

			// Once something is granted the warning goes away and the count shows.
			engine.inventory().grantLoot(op(), campaign, pc, List.of(map("item", "Cloak of Protection")), null, "QUEST",
					null);
			engine.sessions().suspend(op(), campaign, null);
			assertEquals(List.of(), m(engine.sessions().bootstrap(op(), campaign, null).get("meta")).get("warnings"));
			String third = (String) engine.narrative()
					.upsert(op(), campaign, "QUEST", null, map("title", "The road east", "status", "ACCEPTED"), null)
					.get("ref");
			assertEquals(1,
					m(engine.narrative().upsert(op(), campaign, "QUEST", third, map("status", "COMPLETED"), null)
							.get("treasure")).get("party_magic_items"));

			// A major encounter (200 XP or more in the pool) carries the same guidance; a skirmish does not.
			String ogre = (String) engine.runtime()
					.materialize(op(), campaign, "Ogre", "Brawn", null, null, null, null, false).get("character");
			String big = (String) engine.encounters()
					.start(op(), campaign, map("a", List.of(pc), "b", List.of(ogre)), null, null, null, null, null)
					.get("encounter");
			Map<String, Object> ended = engine.encounters().end(op(), campaign, big, "ENEMIES_FLED", null);
			assertNotNull(ended.get("treasure"), String.valueOf(ended.keySet()));
			assertEquals("MAJOR_ENCOUNTER", m(ended.get("treasure")).get("occasion"));
			String rat = (String) engine.runtime()
					.materialize(op(), campaign, "Giant Rat", "Nibbles", null, null, null, null, false)
					.get("character");
			String small = (String) engine.encounters()
					.start(op(), campaign, map("a", List.of(pc), "b", List.of(rat)), null, null, null, null, null)
					.get("encounter");
			assertNull(engine.encounters().end(op(), campaign, small, "PARTY_VICTORY", null).get("treasure"));
		}
	}

	// ── enchantments the SRD does not list ─────────────────────────────

	@SuppressWarnings("unchecked")
	private static boolean dealtType(Map<String, Object> attack, String type) {
		Object breakdown = attack.get("breakdown") != null ? attack.get("breakdown")
				: attack.get("damage") instanceof Map<?, ?> d ? ((Map<String, Object>) d).get("breakdown") : null;
		return breakdown instanceof List<?> parts
				&& parts.stream().anyMatch(x -> type.equals(((Map<String, Object>) x).get("type")));
	}

	@Test
	void theGmCanEnchantAMundaneBaseAndTheEngineAppliesIt() throws Exception {
		try (Engine engine = engine(tempDb("magic-enchant"))) {
			String campaign = committedCampaign(engine);
			engine.sessions().bootstrap(op(), campaign, null);
			String pc = "character:1";
			// A name and a text are required; the rest is what the engine applies.
			RpgException unnamed = assertThrows(RpgException.class,
					() -> engine.inventory().grantLoot(op(), campaign, pc, List
							.of(map("item", "Arrow", "magic", map("damage_bonus_dice", "1d6", "damage_type", "fire"))),
							null, "QUEST", null));
			assertEquals(ErrorCode.INVALID_ARGUMENT, unnamed.code());
			Map<String, Object> fire = map("name", "Arrow of Fire", "rarity", "UNCOMMON", "damage_bonus_dice", "1d6",
					"damage_type", "fire", "text",
					"The arrowhead glows like a coal; on a hit it burns for an extra 1d6 fire.");
			Map<String, Object> frost = map("name", "Frostbrand", "rarity", "RARE", "bonus", 1, "damage_bonus_dice",
					"1d6", "damage_type", "cold", "attunement", true, "text",
					"A blade rimed with frost: +1, and 1d6 cold on a hit.");
			Map<String, Object> granted = engine.inventory().grantLoot(op(), campaign, pc,
					List.of(map("item", "Shortbow"), map("item", "Arrow", "quantity", 10, "magic", fire),
							map("item", "Longsword", "magic", frost)),
					null, "QUEST", null);
			List<Map<String, Object>> g = l(granted.get("granted"));
			assertEquals("Arrow of Fire", g.get(1).get("name"));
			assertEquals(10L, ((Number) g.get(1).get("quantity")).longValue());
			assertEquals("Uncommon", g.get(1).get("magic"));
			assertEquals("Frostbrand", g.get(2).get("name"));
			assertEquals("Rare, requires attunement", g.get(2).get("magic"));

			Map<String, Object> def = l(
					engine.content()
							.definitions(campaign, "ITEM", null, "Arrow of Fire", null, null, null, true, null, 100,
									"FULL")
							.get("items"))
					.stream().filter(d -> "Arrow of Fire".equals(d.get("name"))).findFirst().orElseThrow();
			Map<String, Object> payload = m(def.get("payload"));
			assertEquals("AMMUNITION", payload.get("type"));
			assertEquals(Map.of("damage_bonus_dice", "1d6", "damage_bonus_type", "fire"), payload.get("modifiers"));
			assertEquals("srd5e:item/arrows", MagicItems.magic(payload).get("base_ref"));
			assertEquals(Boolean.TRUE, payload.get("text_is_paraphrase"), "homebrew text is the GM's, not the SRD's");
			assertEquals("engine", MagicItems.adjudication(payload));

			String bandit = (String) engine.runtime()
					.materialize(op(), campaign, "Bandit", "Grubb", null, null, null, null, false).get("character");
			String encounter = (String) engine.encounters()
					.start(op(), campaign, map("a", List.of(pc), "b", List.of(bandit)), null, null, null, null, null)
					.get("encounter");
			if (!pc.equals(
					m(engine.encounters().encounterState(campaign, encounter, 3).get("turn")).get("character"))) {
				engine.encounters().perform(op(), campaign, encounter, bandit, map("kind", "END_TURN"), true);
			}
			ScriptedRollService dice = (ScriptedRollService) engine.roller();
			dice.queue(20); // a natural 20: the arrow hits
			Map<String, Object> shot = engine.encounters().perform(op(), campaign, encounter, pc,
					map("kind", "ATTACK", "attack", "Shortbow", "target", bandit, "ammunition", "Arrow of Fire"), true);
			assertEquals("Arrow of Fire", m(shot.get("ammunition")).get("name"));
			assertEquals(Map.of("dice", "1d6", "type", "fire"), m(shot.get("ammunition")).get("extra_damage"));
			assertEquals(Boolean.TRUE, shot.get("hit"), String.valueOf(shot));
			assertTrue(dealtType(shot, "fire"), "the arrow's fire is rolled and applied: " + shot);
			assertTrue(dealtType(shot, "piercing"), String.valueOf(shot));

			engine.encounters().perform(op(), campaign, encounter, bandit, map("kind", "END_TURN"), true);
			dice.queue(20);
			Map<String, Object> slash = engine.encounters().perform(op(), campaign, encounter, pc,
					map("kind", "ATTACK", "attack", "Frostbrand", "target", bandit), true);
			assertEquals(Boolean.TRUE, slash.get("hit"), String.valueOf(slash));
			assertTrue(dealtType(slash, "cold"), "the blade's frost rides on its own attacks: " + slash);
			assertTrue(dealtType(slash, "slashing"), String.valueOf(slash));
		}
	}
}
