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

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import se.hirt.mcp.rpg.protocol.ErrorCode;
import se.hirt.mcp.rpg.protocol.Ref;
import se.hirt.mcp.rpg.protocol.RpgException;
import se.hirt.mcp.rpg.rules.Money;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static se.hirt.mcp.rpg.TestCampaigns.committedCampaign;
import static se.hirt.mcp.rpg.TestCampaigns.engine;
import static se.hirt.mcp.rpg.TestCampaigns.map;
import static se.hirt.mcp.rpg.TestCampaigns.op;
import static se.hirt.mcp.rpg.TestCampaigns.tempDb;

class MoneyParsingTest {

	@Test
	void decimalAndSignedAmountsAreNotPartiallyMatched() {
		assertEquals(150L, Money.parseCp("1.5 gp"), "one and a half gold is 150 copper");
		assertInvalidMoney("-5 gp");
	}

	@Test
	void denominationStringsAcceptExactDecimalsWhitespaceAndCompactNotation() {
		for (var example : Map.ofEntries(Map.entry("15 gp 2 sp 5 cp", 1525L), Map.entry("1gp2sp5cp", 125L),
				Map.entry(" \t1.50 GP\n2.5 SP 5 Cp ", 180L), Map.entry(".5gp", 50L), Map.entry("0.1sp", 1L),
				Map.entry("0.02ep", 1L), Map.entry("0.001pp", 1L), Map.entry("0001.50gp", 150L),
				Map.entry("1.5gp.5sp", 155L), Map.entry("0.0 gp", 0L)).entrySet()) {
			assertEquals(example.getValue().longValue(), Money.parseCp(example.getKey()), example.getKey());
		}
	}

	@Test
	void bareStringsAndNumbersRepresentExactWholeCopperWithoutTruncation() {
		assertEquals(150L, Money.parseCp("150.000"));
		assertEquals(42L, Money.parseCp("00042"));
		assertEquals(0L, Money.parseCp(".0"));
		assertEquals(42L, Money.parseCp(42));
		assertEquals(150L, Money.parseCp(150.0));
		assertEquals(12L, Money.parseCp(12.0f));
		assertEquals(150L, Money.parseCp(new BigDecimal("150.000")));
		assertEquals(1000L, Money.parseCp(new BigDecimal("1E3")),
				"numeric values may use their exact Number representation");
		assertEquals(Long.MAX_VALUE, Money.parseCp(Long.MAX_VALUE));
		assertEquals(Long.MAX_VALUE, Money.parseCp(new BigInteger("9223372036854775807")));
		assertEquals(Long.MAX_VALUE, Money.parseCp("9223372036854775807.0"));
	}

	@Test
	void denominationMapsAcceptExactFractionsAndPreserveTheExistingCoinConversions() {
		assertEquals(0L, Money.parseCp(Map.of()));
		assertEquals(0L, Money.parseCp(Map.of("cp", 0)));
		assertEquals(180L, Money.parseCp(Map.of("GP", new BigDecimal("1.5"), "sp", new BigDecimal("2.5"), "cp", 5)));
		assertEquals(1234L, Money.parseCp(Map.of("pp", 1, "ep", 4, "sp", 3, "cp", 4)));
		assertEquals(1L, Money.parseCp(Map.of("sp", new BigDecimal("0.1"))));
		assertEquals(1L, Money.parseCp(Map.of("ep", new BigDecimal("0.02"))));
		assertEquals(Long.MAX_VALUE, Money.parseCp(Map.of("gp", new BigDecimal("92233720368547758.07"))));
		assertEquals(Long.MAX_VALUE, Money.parseCp(Map.of("gp", 92233720368547758L, "cp", 7)));
		assertEquals(Long.MAX_VALUE, Money.parseCp("92233720368547758 gp 7 cp"));
	}

	@Test
	void stringsRejectSignsJunkPunctuationAndUnconsumedText() {
		for (String invalid : List.of("-5gp", "-5", "-0gp", "+5gp", "1gp -2sp", "1gp+2sp", "1gp,2sp", "cost 5gp",
				"5gpp", "5gp extra", "5gp2", "1e3gp", "1e3", "NaN", "Infinity", "5g", "gp", ".gp", "1..5gp", "1.gp",
				"1. gp", "1,000gp", "1 gp / 2 sp", "1 gp and 2 sp", "", " \t\n")) {
			assertInvalidMoney(invalid);
		}
	}

	@Test
	void fractionalCopperIsRejectedInEveryRepresentationAndCannotBeCompensatedByAnotherPart() {
		for (Object invalid : List.of("1.5", ".5", "0.001gp", "0.01sp", "0.005gp0.5cp", 1.5, 0.5f,
				new BigDecimal("1.50"), Map.of("cp", new BigDecimal("0.5")), Map.of("gp", new BigDecimal("0.001")),
				Map.of("gp", new BigDecimal("0.005"), "cp", new BigDecimal("0.5")))) {
			assertInvalidMoney(invalid);
		}
	}

	@Test
	void negativeNonfiniteAndMalformedValuesReturnInvalidArgument() {
		assertInvalidMoney(null);
		for (Object invalid : List.of(-1, -1.0, new BigDecimal("-0.001"), Double.NaN, Double.POSITIVE_INFINITY,
				Double.NEGATIVE_INFINITY, Float.NaN, Map.of("gp", -1, "sp", 10), Map.of("gp", Double.NaN),
				Map.of("gp", "1.5"), map("gp", null), Map.of("gp", true), Map.of("unknown", 1), true,
				List.of("1 gp"))) {
			assertInvalidMoney(invalid);
		}
	}

	@Test
	void overflowingNumbersProductsAndSumsReturnInvalidArgumentInsteadOfWrapping() {
		for (Object invalid : List.of("9223372036854775808", "92233720368547758.08gp", "9223372036854775807gp",
				"9223372036854775807cp1cp", "18446744073709551616cp", "9".repeat(2000) + "gp",
				new BigInteger("9223372036854775808"), new BigDecimal("9223372036854775808"), Double.MAX_VALUE,
				Float.MAX_VALUE, Map.of("gp", Long.MAX_VALUE), Map.of("cp", Long.MAX_VALUE, "sp", 1),
				Map.of("gp", Long.MAX_VALUE, "cp", 100))) {
			assertInvalidMoney(invalid);
		}
	}

	@Test
	void lootUsesExactDecimalMoneyAndRejectedAmountsRollBackItemsAndFunds() throws Exception {
		try (Engine engine = engine(tempDb("money-parser-loot"))) {
			String campaign = committedCampaign(engine);
			engine.sessions().bootstrap(op(), campaign, null);
			String pc = "character:1";
			long beforeMoney = engine.db().read(tx -> tx.get("character", 1).lng("money_cp"));
			String operation = op();
			List<Map<String, Object>> items = List.of(map("item", "Torch"));
			engine.inventory().grantLoot(operation, campaign, pc, items, "1.5 gp", "QUEST", "a precise reward");
			long expectedMoney = beforeMoney + 150;
			assertEquals(expectedMoney, engine.db().read(tx -> tx.get("character", 1).lng("money_cp")).longValue());
			Map<String, List<Map<String, Object>>> accepted = engine.db().snapshot(Ref.id(campaign, Ref.CAMPAIGN));
			Map<String, Object> replay = engine.inventory().grantLoot(operation, campaign, pc, items, "1.5 gp", "QUEST",
					"a precise reward");
			assertEquals(Boolean.TRUE, replay.get("replayed"));
			assertEquals(accepted, engine.db().snapshot(Ref.id(campaign, Ref.CAMPAIGN)));
			for (Object invalid : List.of("-5 gp", "1 gp junk", Map.of("cp", new BigDecimal("0.5")),
					"9223372036854775808 cp")) {
				RpgException rejected = assertThrows(RpgException.class, () -> engine.inventory().grantLoot(op(),
						campaign, pc, items, invalid, "QUEST", "a rejected reward"));
				assertEquals(ErrorCode.INVALID_ARGUMENT, rejected.code());
				assertEquals(accepted, engine.db().snapshot(Ref.id(campaign, Ref.CAMPAIGN)),
						"failed money parsing also undoes the item inserted first");
			}
		}
	}

	private static void assertInvalidMoney(Object value) {
		RpgException rejected = assertThrows(RpgException.class, () -> Money.parseCp(value), String.valueOf(value));
		assertEquals(ErrorCode.INVALID_ARGUMENT, rejected.code(), String.valueOf(value));
	}
}
