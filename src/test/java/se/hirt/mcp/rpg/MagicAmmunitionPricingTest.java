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

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static se.hirt.mcp.rpg.TestCampaigns.*;

class MagicAmmunitionPricingTest {

	@SuppressWarnings("unchecked")
	private static Map<String, Object> m(Object value) {
		return (Map<String, Object>) value;
	}

	@SuppressWarnings("unchecked")
	private static List<Map<String, Object>> list(Object value) {
		return (List<Map<String, Object>>) value;
	}

	private record Base(String name, int bundle, long cost, double weight) {
	}

	@Test
	void plusAmmunitionPricesTenPiecesAtThePotionValueForEveryRarityAndBase() throws Exception {
		try (Engine engine = engine(tempDb("magic-ammo-values"))) {
			String campaign = committedCampaign(engine);
			engine.sessions().bootstrap(op(), campaign, null);
			engine.content().define(op(), campaign, "ITEM", "Obsidian Dart", "custom:item/obsidian-dart",
					"An individual dart.", "AMMUNITION", "7 cp", 0.1, null, null, "GM");
			List<Base> bases = List.of(new Base("Arrow", 20, 100, 1), new Base("Bolt", 20, 100, 1.5),
					new Base("Bullet, Firearm", 10, 300, 2), new Base("Bullet, Sling", 20, 4, 1.5),
					new Base("Needle", 50, 100, 1), new Base("Obsidian Dart", 1, 7, 0.1));
			var specs = new ArrayList<Map<String, Object>>();
			for (int bonus = 1; bonus <= 3; bonus++) {
				for (Base base : bases) {
					specs.add(map("item", "+" + bonus + " " + base.name()));
				}
			}
			Map<String, Object> granted = engine.inventory().grantLoot(op(), campaign, "character:1", specs, null,
					"QUEST", null);
			List<Map<String, Object>> definitions = list(engine.content()
					.definitions(campaign, "ITEM", "AMMUNITION", null, null, null, null, true, null, 100, "FULL")
					.get("items"));
			long[] potionValues = {20_000, 200_000, 2_000_000};
			for (int bonus = 1; bonus <= 3; bonus++) {
				for (Base base : bases) {
					String name = "+" + bonus + " " + base.name();
					Map<String, Object> definition = definitions.stream().filter(d -> name.equals(d.get("name")))
							.findFirst().orElseThrow();
					Map<String, Object> payload = m(definition.get("payload"));
					long expected = base.cost() + potionValues[bonus - 1] * base.bundle() / 10;
					assertAll(name, () -> assertEquals(expected, ((Number) definition.get("cost_cp")).longValue()),
							() -> assertEquals(expected, ((Number) payload.get("cost_cp")).longValue()),
							() -> assertEquals(base.bundle() == 1 ? null : base.bundle(),
									definition.get("sold_in_bundles_of")),
							() -> assertEquals(base.weight(), ((Number) payload.get("weight_lb")).doubleValue()));
				}
			}
			Map<String, Object> repeated = engine.inventory().grantLoot(op(), campaign, "character:1",
					List.of(map("item", "+2 Arrow")), null, "QUEST", null);
			assertEquals(list(granted.get("granted")).get(6).get("item"),
					list(repeated.get("granted")).getFirst().get("item"), "repeat grants reuse the priced definition");
		}
	}

	@Test
	void purchasesAndPartialSalesKeepTheirUnitsAndPricesAfterReopeningTheCampaign() throws Exception {
		Path database = tempDb("magic-ammo-trade");
		String campaign;
		String entry;
		long balance;
		try (Engine engine = engine(database)) {
			campaign = committedCampaign(engine);
			engine.sessions().bootstrap(op(), campaign, null);
			Map<String, Object> loot = engine.inventory().grantLoot(op(), campaign, "character:1",
					List.of(map("item", "+1 Arrow", "quantity", 40)), "1000 gp", "QUEST", null);
			entry = (String) list(loot.get("granted")).getFirst().get("entry");
			balance = ((Number) m(engine.characters().characterSheet(campaign, "character:1", "PLAY").get("money"))
					.get("total_cp")).longValue();
		}
		try (Engine engine = engine(database)) {
			engine.sessions().bootstrap(op(), campaign, null);
			Map<String, Object> bought = engine.inventory().trade(op(), campaign, "character:1", "BUY", "+1 Arrow",
					null, 2, null, null, null);
			balance -= 80_200;
			assertEquals(entry, bought.get("entry"));
			assertEquals(40L, bought.get("quantity"), "BUY quantities still count bundles of twenty");
			assertEquals("802 gp", bought.get("list_price"));
			assertEquals("802 gp", bought.get("price_paid"));
			assertEquals(balance, m(bought.get("money")).get("total_cp"));
			int remaining = 80;
			Integer[] quantities = {1, 20, 21, null};
			long[] proceeds = {1002, 20_050, 21_052, 38_095};
			for (int i = 0; i < quantities.length; i++) {
				int pieces = quantities[i] == null ? remaining : quantities[i];
				Map<String, Object> sold = engine.inventory().trade(op(), campaign, "character:1", "SELL", null, entry,
						quantities[i], null, null, null);
				balance += proceeds[i];
				remaining -= pieces;
				assertEquals((long) pieces, sold.get("quantity"));
				assertEquals(Money.format(proceeds[i]), sold.get("list_price"));
				assertEquals(Money.format(proceeds[i]), sold.get("price_received"));
				assertEquals(balance, m(sold.get("money")).get("total_cp"));
				List<Map<String, Object>> arrows = list(
						engine.characters().characterSheet(campaign, "character:1", "PLAY").get("inventory")).stream()
						.filter(e -> "+1 Arrow".equals(e.get("name"))).toList();
				assertEquals(remaining == 0 ? 0 : 1, arrows.size());
				if (remaining > 0) {
					assertEquals((long) remaining, arrows.getFirst().get("quantity"));
				}
			}
		}
	}

	@Test
	void slayingAndHomebrewAmmunitionUsePerPieceConsumableValues() throws Exception {
		try (Engine engine = engine(tempDb("magic-ammo-other-values"))) {
			String campaign = committedCampaign(engine);
			engine.sessions().bootstrap(op(), campaign, null);
			engine.inventory().grantLoot(op(), campaign, "character:1",
					List.of(map("item", "Ammunition of Slaying", "base", "Arrow"),
							map("item", "Ammunition of Slaying", "base", "Needle"),
							map("item", "Arrow", "magic", map("name", "Arrow of Fire", "rarity", "UNCOMMON", "text",
									"Deals extra fire damage.", "damage_bonus_dice", "1d6", "damage_type", "fire")),
							map("item", "Bullet, Firearm", "magic",
									map("name", "Burning Bullet", "rarity", "UNCOMMON", "text", "Glows brightly.")),
							map("item", "Needle", "magic",
									map("name", "Frost Needle", "rarity", "UNCOMMON", "text", "Feels cold.")),
							map("item", "Longsword", "magic",
									map("name", "Frostbrand", "rarity", "RARE", "text", "A blade rimed with frost."))),
					null, "QUEST", null);
			Map<String, Long> expected = Map.of("Ammunition of Slaying (Arrow)", 40_000_100L,
					"Ammunition of Slaying (Needle)", 100_000_100L, "Arrow of Fire", 400_100L, "Burning Bullet",
					200_300L, "Frost Needle", 1_000_100L, "Frostbrand", 401_500L);
			assertAll(expected.entrySet().stream().map(e -> () -> {
				List<Map<String, Object>> definitions = list(engine.content()
						.definitions(campaign, "ITEM", null, e.getKey(), null, null, null, true, null, 100, "FULL")
						.get("items"));
				Map<String, Object> definition = definitions.stream().filter(d -> e.getKey().equals(d.get("name")))
						.findFirst().orElseThrow();
				assertEquals(e.getValue().longValue(), ((Number) definition.get("cost_cp")).longValue(), e.getKey());
			}));
		}
	}

	@Test
	void overflowingAmmunitionValuesRejectTheEntireLootBatch() throws Exception {
		try (Engine engine = engine(tempDb("magic-ammo-overflow"))) {
			String campaign = committedCampaign(engine);
			engine.sessions().bootstrap(op(), campaign, null);
			engine.content().define(op(), campaign, "ITEM", "Priceless Arrow", "custom:item/priceless-arrow", null,
					"AMMUNITION", Long.MAX_VALUE - 20_000, 1.0, map("bundle_size", 20), null, "GM");
			Map<String, Object> before = engine.characters().characterSheet(campaign, "character:1", "FULL");
			Object definitions = engine.content()
					.definitions(campaign, "ITEM", null, null, null, null, null, 1, "SUMMARY").get("total");
			List<Map<String, Object>> invalid = List.of(map("item", "+1 Priceless Arrow"),
					map("item", "Priceless Arrow", "magic",
							map("name", "Priceless Flame Arrow", "rarity", "UNCOMMON", "text", "Glows brightly.")));
			assertAll(invalid.stream().map(spec -> () -> {
				RpgException failure = assertThrows(RpgException.class, () -> engine.inventory().grantLoot(op(),
						campaign, "character:1", List.of(map("item", "+1 Longsword"), spec), "1 gp", "QUEST", null));
				assertEquals(ErrorCode.INVALID_ARGUMENT, failure.code());
				Map<String, Object> after = engine.characters().characterSheet(campaign, "character:1", "FULL");
				assertEquals(before.get("inventory"), after.get("inventory"), "earlier loot must roll back");
				assertEquals(before.get("money"), after.get("money"));
				assertEquals(definitions,
						engine.content().definitions(campaign, "ITEM", null, null, null, null, null, 1, "SUMMARY")
								.get("total"),
						"neither the valid first item nor the overflowing item leaves a definition behind");
			}));
		}
	}
}
