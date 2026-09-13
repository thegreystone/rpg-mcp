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
import se.hirt.mcp.rpg.protocol.ErrorCode;
import se.hirt.mcp.rpg.protocol.RpgException;
import se.hirt.mcp.rpg.rules.Money;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static se.hirt.mcp.rpg.TestCampaigns.map;
import static se.hirt.mcp.rpg.TestCampaigns.op;

/**
 * Inventory and economy invariants (DOMAIN_MODEL.md I-23..I-27): the engine does the accounting.
 */
class EconomyTest {

	@SuppressWarnings("unchecked")
	private static Map<String, Object> m(Object o) {
		return (Map<String, Object>) o;
	}

	@SuppressWarnings("unchecked")
	private static List<Map<String, Object>> list(Object o) {
		return (List<Map<String, Object>>) o;
	}

	@Test
	void moneyParsingAndRendering() {
		assertEquals(1525, Money.parseCp("15 gp 2 sp 5 cp"));
		assertEquals(1500, Money.parseCp(Map.of("gp", 15)));
		assertEquals(42, Money.parseCp(42));
		assertEquals("15 gp 2 sp 5 cp", Money.format(1525));
		assertEquals("0 cp", Money.format(0));
		assertEquals(3, Money.coinCount(1110), "1 pp + 1 gp + 1 sp");
		assertThrows(RpgException.class, () -> Money.parseCp("lots"));
	}

	@Test
	void startingEquipmentBundleIsGrantedAtCommit() throws Exception {
		try (Engine engine = TestCampaigns.engine(TestCampaigns.tempDb("economy-start"))) {
			String campaign = (String) engine.campaigns().create(op(), null, null).get("campaign");
			engine.campaigns().updateSetup(op(), campaign, null,
					map("content_profile", "PEGI_16", "experience", "SURPRISE_ME", "rules", Map.of(), "continuation",
							"CHECKPOINT", "party", "SURPRISE_ME", "adventure",
							map("premise", "x", "opening_location", "Market", "immediate_goal", "shop")));
			String pc = (String) engine.characters().createDraft(op(), campaign, map("name", "Richard", "species",
					"Human", "class", "Sorcerer", "ability_scores",
					map("CHA", 15, "CON", 14, "DEX", 13, "INT", 12, "WIS", 10, "STR", 8), "background", "Criminal",
					"background_ability_scores", map("CON", 2, "INT", 1), "species_skill", "Insight", "origin_feat",
					map("feat", "Skilled", "proficiencies", List.of("Nature", "Survival", "Medicine")), "skills",
					List.of("Deception", "Persuasion"), "personality", "Confident.", "starting_equipment", "A"), true)
					.get("character");
			Map<String, Object> sheet = engine.characters().characterSheet(campaign, pc, "FULL");
			assertEquals("A", m(sheet.get("starting_equipment")).get("option"));
			engine.characters().commitDraft(op(), campaign, pc, null);
			Map<String, Object> committed = engine.campaigns().commitSetup(op(), campaign, null);
			Map<String, Object> granted = m(m(committed.get("starting_equipment")).get(pc));
			assertEquals(7800L, granted.get("money_cp"), "Sorcerer Option A 28 GP + background gold-only option 50 GP");
			engine.sessions().bootstrap(op(), campaign, null);

			Map<String, Object> play = engine.characters().characterSheet(campaign, pc, "PLAY");
			List<Map<String, Object>> inventory = list(play.get("inventory"));
			assertTrue(inventory.stream().anyMatch(e -> e.get("name").equals("Spear")));
			assertTrue(inventory.stream()
					.anyMatch(e -> e.get("name").equals("Dagger") && ((Long) e.get("quantity")) == 2));
			assertTrue(
					inventory.stream()
							.anyMatch(e -> e.get("name").equals("Rations") && ((Long) e.get("quantity")) == 10),
					"Dungeoneer's Pack is expanded");
			assertTrue(inventory.stream().anyMatch(e -> e.get("name").equals("Arcane Focus (Crystal)")));
			assertFalse(inventory.stream().anyMatch(e -> e.get("name").equals("Dungeoneer's Pack")));
			assertEquals(78L, m(play.get("money")).get("gp"), "28 gp (class Option A) + 50 gp (background Option B)");
			assertEquals(11, m(play.get("armor_class")).get("value"), "unarmored 10 + DEX 13 (+1)");
			Map<String, Object> carrying = m(play.get("carrying"));
			assertEquals(120, carrying.get("capacity_lb"), "STR 8 × 15");
			assertEquals(Boolean.FALSE, carrying.get("over_capacity"));
			assertTrue((Double) carrying.get("items_lb") > 30, "pack contents weigh something: " + carrying);
		}
	}

	@Test
	void buySellEquipAndLoot() throws Exception {
		try (Engine engine = TestCampaigns.engine(TestCampaigns.tempDb("economy"))) {
			String campaign = TestCampaigns.committedCampaign(engine);
			engine.sessions().bootstrap(op(), campaign, null);
			String pc = "character:1";
			long start = ((Number) m(engine.characters().characterSheet(campaign, pc, "PLAY").get("money"))
					.get("total_cp")).longValue();
			assertEquals(10000, start, "Sorcerer gold-only 50 GP + Sage background gold-only 50 GP");

			// Buy
			Map<String, Object> buy = engine.inventory().trade(op(), campaign, pc, "BUY", "Leather Armor", null, 1,
					null, null, null);
			assertEquals("10 gp", buy.get("price_paid"));
			assertEquals(9000L, m(buy.get("money")).get("total_cp"), "100 gp start - 10 gp armor");
			String armorEntry = (String) buy.get("entry");
			Map<String, Object> shield = engine.inventory().trade(op(), campaign, pc, "BUY", "srd5e:item/shield", null,
					1, null, null, null);
			Map<String, Object> arrows = engine.inventory().trade(op(), campaign, pc, "BUY", "Arrow", null, 2, null,
					null, null);
			assertEquals(40L, arrows.get("quantity"), "two bundles of 20 arrows");
			assertEquals("2 gp", arrows.get("price_paid"));

			// Insufficient funds
			RpgException poor = assertThrows(RpgException.class, () -> engine.inventory().trade(op(), campaign, pc,
					"BUY", "Plate Armor", null, 1, null, null, null));
			assertEquals(ErrorCode.INSUFFICIENT_RESOURCE, poor.code());

			// Negotiated price requires a reason, is recorded with GM provenance
			RpgException noReason = assertThrows(RpgException.class,
					() -> engine.inventory().trade(op(), campaign, pc, "BUY", "Rope", null, 1, null, "5 sp", null));
			assertEquals(ErrorCode.INVALID_ARGUMENT, noReason.code());
			Map<String, Object> haggled = engine.inventory().trade(op(), campaign, pc, "BUY", "Rope", null, 1, null,
					"5 sp", "won the Persuasion check");
			assertEquals("5 sp", haggled.get("price_paid"));
			assertEquals("1 gp", haggled.get("list_price"));

			// Equip: AC = 11 + DEX, +2 shield; two-handed + shield is refused
			Map<String, Object> equipped = engine.inventory().equip(op(), campaign, pc, armorEntry, true);
			Map<String, Object> sheet = engine.characters().characterSheet(campaign, pc, "PLAY");
			int dexMod = (Integer) m(m(sheet.get("abilities")).get("DEX")).get("modifier");
			assertEquals(11 + dexMod, m(equipped.get("armor_class")).get("value"));
			engine.inventory().equip(op(), campaign, pc, (String) shield.get("entry"), true);
			assertEquals(13 + dexMod,
					m(engine.characters().characterSheet(campaign, pc, "PLAY").get("armor_class")).get("value"));
			Map<String, Object> bow = engine.inventory().trade(op(), campaign, pc, "BUY", "Shortbow", null, 1, null,
					null, null);
			RpgException hands = assertThrows(RpgException.class,
					() -> engine.inventory().equip(op(), campaign, pc, (String) bow.get("entry"), true));
			assertEquals(ErrorCode.VALIDATION_FAILED, hands.code());
			engine.inventory().equip(op(), campaign, pc, (String) shield.get("entry"), false);
			engine.inventory().equip(op(), campaign, pc, (String) bow.get("entry"), true);

			// Sell at half price; equipped stack of one is fully removed
			long before = ((Number) m(engine.characters().characterSheet(campaign, pc, "PLAY").get("money"))
					.get("total_cp")).longValue();
			Map<String, Object> sold = engine.inventory().trade(op(), campaign, pc, "SELL", "Shortbow", null, null,
					null, null, null);
			assertEquals("12 gp 5 sp", sold.get("price_received"));
			assertEquals(before + 1250, ((Number) m(sold.get("money")).get("total_cp")).longValue());
			assertFalse(list(engine.characters().characterSheet(campaign, pc, "PLAY").get("inventory")).stream()
					.anyMatch(e -> e.get("name").equals("Shortbow")));

			// Custom content: define, buy, transfer to a location and back
			Map<String, Object> defined = engine.content().define(op(), campaign, "ITEM", "Bellhaven Broadsheet",
					"custom:item/bellhaven-broadsheet", "The evening paper.", "DOCUMENT", "2 cp", 0.1,
					Map.of("perishable", true), List.of("news"), "GM");
			String content = (String) defined.get("content");
			assertTrue(content.startsWith("content:"));
			Map<String, Object> paper = engine.inventory().trade(op(), campaign, pc, "BUY",
					"custom:item/bellhaven-broadsheet", null, 1, null, null, null);
			assertEquals(content, paper.get("item"));
			Map<String, Object> dropped = engine.inventory().transfer(op(), campaign, (String) paper.get("entry"),
					locationOf(engine, campaign), null, "left it on the table");
			assertEquals(locationOf(engine, campaign), dropped.get("to"));
			engine.inventory().transfer(op(), campaign, (String) dropped.get("entry"), pc, null, null);
			assertTrue(list(engine.characters().characterSheet(campaign, pc, "PLAY").get("inventory")).stream()
					.anyMatch(e -> e.get("name").equals("Bellhaven Broadsheet")));

			// Search
			Map<String, Object> search = engine.content().definitions(campaign, "ITEM", "WEAPON", "finesse", null, null,
					null, 25, "SUMMARY");
			List<Map<String, Object>> found = list(search.get("items"));
			assertTrue(found.stream().anyMatch(i -> i.get("name").equals("Rapier")));
			assertTrue(found.stream().noneMatch(i -> i.get("name").equals("Longsword")), "Longsword is not finesse");
			Map<String, Object> customs = engine.content().definitions(campaign, "ITEM", null, "broadsheet", null, null,
					null, 25, "SUMMARY");
			assertEquals(1, ((Number) customs.get("total")).intValue());

			// Loot: GM_GRANT is audited; quest loot is not
			Map<String, Object> loot = engine.inventory().grantLoot(op(), campaign, pc,
					List.of(map("item", "Potion of Healing", "quantity", 2)), "25 gp", "QUEST", "reward");
			assertEquals(Boolean.FALSE, loot.get("audited"));
			Map<String, Object> grant = engine.inventory().grantLoot(op(), campaign, pc, null, "1 gp", "GM_GRANT",
					"found a coin in the mud");
			assertEquals(Boolean.TRUE, grant.get("audited"));
			RpgException noReason2 = assertThrows(RpgException.class,
					() -> engine.inventory().grantLoot(op(), campaign, pc, null, "1 gp", "GM_GRANT", null));
			assertEquals(ErrorCode.INVALID_ARGUMENT, noReason2.code());
			long audits = engine.db()
					.read(tx -> tx.count("SELECT COUNT(*) FROM audit_record WHERE kind = 'DISCRETIONARY_LOOT'"));
			assertEquals(1, audits);
			Map<String, Object> memories = engine.ledger().queryMemories(campaign, List.of(pc),
					List.of("LOOT_ACQUIRED"), null, null, 10);
			assertEquals(2, list(memories.get("events")).size());
		}
	}

	private static String locationOf(Engine engine, String campaign) {
		return engine
				.db().read(
						tx -> "location:" + tx
								.queryOne("SELECT current_location_id AS l FROM campaign WHERE id = ?",
										Long.parseLong(campaign.substring("campaign:".length())))
								.orElseThrow().lng("l"));
	}

	@Test
	void giveMoneyBetweenCharacters() throws Exception {
		try (Engine engine = TestCampaigns.engine(TestCampaigns.tempDb("give-money"))) {
			String campaign = TestCampaigns.committedCampaign(engine);
			engine.sessions().bootstrap(op(), campaign, null);
			String pc = "character:1";
			String porter = (String) engine.runtime()
					.materialize(op(), campaign, "Commoner", "Wat", null, null, null, null, false).get("character");
			long start = ((Number) m(engine.characters().characterSheet(campaign, pc, "PLAY").get("money"))
					.get("total_cp")).longValue();

			Map<String, Object> given = engine.inventory().giveMoney(op(), campaign, pc, porter, "1 gp 5 sp",
					"for the dive");
			assertEquals(150L, m(given.get("given")).get("total_cp"));
			assertEquals(start - 150, m(given.get("giver_money")).get("total_cp"));
			assertEquals(150L, m(given.get("receiver_money")).get("total_cp"));
			assertEquals(150L,
					m(engine.characters().characterSheet(campaign, porter, "PLAY").get("money")).get("total_cp"));
			assertTrue(((String) given.get("event")).startsWith("event:"), "one ledger event names both");

			// The giver must hold the amount; nobody pays themselves; the amount must be positive.
			assertEquals(ErrorCode.INSUFFICIENT_RESOURCE, assertThrows(RpgException.class,
					() -> engine.inventory().giveMoney(op(), campaign, porter, pc, "2 gp", null)).code());
			assertEquals(ErrorCode.INVALID_ARGUMENT, assertThrows(RpgException.class,
					() -> engine.inventory().giveMoney(op(), campaign, pc, pc, "1 gp", null)).code());
			assertEquals(ErrorCode.INVALID_ARGUMENT, assertThrows(RpgException.class,
					() -> engine.inventory().giveMoney(op(), campaign, pc, porter, 0, null)).code());
			// The porter can pay it back in copper.
			Map<String, Object> back = engine.inventory().giveMoney(op(), campaign, porter, pc, 150, "repaid");
			assertEquals(0L, m(back.get("giver_money")).get("total_cp"));
			assertEquals(start, m(back.get("receiver_money")).get("total_cp"));
		}
	}
}
