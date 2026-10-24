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

import java.util.List;
import java.util.Map;
import java.util.Optional;

import io.quarkiverse.mcp.server.TextContent;
import io.quarkiverse.mcp.server.ToolResponse;
import org.junit.jupiter.api.Test;
import se.hirt.mcp.rpg.dice.DiceExpression;
import se.hirt.mcp.rpg.dice.Roll;
import se.hirt.mcp.rpg.dice.ScriptedRollService;
import se.hirt.mcp.rpg.protocol.ErrorCode;
import se.hirt.mcp.rpg.protocol.Json;
import se.hirt.mcp.rpg.protocol.Ref;
import se.hirt.mcp.rpg.protocol.RpgException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static se.hirt.mcp.rpg.TestCampaigns.committedCampaign;
import static se.hirt.mcp.rpg.TestCampaigns.engine;
import static se.hirt.mcp.rpg.TestCampaigns.op;
import static se.hirt.mcp.rpg.TestCampaigns.tempDb;

class DiceValidationTest {

	@Test
	void malformedCountsAndIncompleteExpressionsAreRejectedAsInvalidArguments() {
		assertInvalidExpression("1d6+");
		assertInvalidExpression("2d6kh0");
		assertInvalidExpression("999999999999999999999d6");
	}

	@Test
	void everyNumericFieldRejectsOverflowAndExistingPerTermLimitsRemainEnforced() {
		for (String invalid : List.of("999999999999999999999d6", "1d999999999999999999999",
				"2d6kh999999999999999999999", "2d6kl999999999999999999999", "2d6dl999999999999999999999",
				"2d6dh999999999999999999999", "999999999999999999999", "2147483648", "-2147483648", "0d6", "1001d6",
				"1d0", "1d10001", "2147483647d6", "1d2147483647")) {
			assertInvalidExpression(invalid);
		}
		DiceExpression largestGroup = DiceExpression.parse("1000d10000");
		assertEquals(1000, largestGroup.terms().getFirst().count());
		assertEquals(10000, largestGroup.terms().getFirst().sides());
		assertEquals(1000, DiceExpression.parse("1000d10000kh1000").terms().getFirst().keepCount());
		assertEquals(999, DiceExpression.parse("1000d10000dl999").terms().getFirst().keepCount());
	}

	@Test
	void missingOperandsAndMalformedTermsAreRejectedWithoutIgnoringAnyOperator() {
		assertInvalidExpression(null);
		for (String invalid : List.of("", " \t\r\n", "+", "-", "1d6+", "1d6-", "5+", "5-", "1d6+ \t\n", "1d6\u2013",
				"1d6++1", "1d6+-1", "1d6--1", "--1d6", "+-1d6", "1d6+*2", "d", "1d", "1d6kh", "2x6", "1d6junk", "(1d6)",
				"1.5d6")) {
			assertInvalidExpression(invalid);
		}
	}

	@Test
	void keepAndDropCountsAlwaysRetainAtLeastOneDie() {
		for (String invalid : List.of("2d6kh0", "2d6kl0", "1d6kh0", "1d6kl0", "2d6kh3", "2d6kl3", "2d6dl2", "2d6dh2",
				"2d6dl3", "2d6dh3", "1d6dl1", "1d6dh1", "2d6kh-1", "2d6dl-1")) {
			assertInvalidExpression(invalid);
		}
		assertEquals(1, DiceExpression.parse("1d6kh1").terms().getFirst().keepCount());
		assertEquals(1, DiceExpression.parse("1d6kl1").terms().getFirst().keepCount());
		assertEquals(0, DiceExpression.parse("1d6dl0").terms().getFirst().keepCount());
		assertEquals(0, DiceExpression.parse("1d6dh0").terms().getFirst().keepCount());
	}

	@Test
	void keepingAllDroppingNoneAndLeavingOneHaveAuditableRollResults() {
		for (String suffix : List.of("kh2", "kl2", "dl0", "dh0")) {
			Roll all = new ScriptedRollService(1).queue(1, 6).roll("2d6" + suffix);
			assertEquals(7, all.total(), suffix);
			assertEquals(2, all.dice().size(), suffix);
			assertTrue(all.dropped().isEmpty(), suffix);
		}
		Roll highest = new ScriptedRollService(1).queue(1, 2, 3, 6).roll("4d6dl3");
		assertEquals(List.of(6), highest.dice());
		assertEquals(List.of(1, 2, 3), highest.dropped());
		assertEquals(6, highest.total());
		Roll lowest = new ScriptedRollService(1).queue(1, 2, 3, 6).roll("4d6dh3");
		assertEquals(List.of(1), lowest.dice());
		assertEquals(List.of(2, 3, 6), lowest.dropped());
		assertEquals(1, lowest.total());
	}

	@Test
	void validSignsTypesetDashesAsciiWhitespaceAndIntegerBoundariesStillWork() {
		Roll spaced = new ScriptedRollService(1).queue(2, 5).roll("\t + 2D6 \n + 3\r");
		assertEquals("2d6+3", spaced.expression());
		assertEquals(List.of(2, 5), spaced.dice());
		assertEquals(3, spaced.modifier());
		assertEquals(10, spaced.total());
		assertEquals(-19, new ScriptedRollService(1).queue(20).roll("-d20+1").total());
		for (String dash : List.of("\u2013", "\u2014", "\u2212")) {
			Roll typeset = new ScriptedRollService(1).queue(4).roll("d4" + dash + "1");
			assertEquals(3, typeset.total());
			assertEquals(-1, typeset.modifier());
		}
		assertEquals(0, new ScriptedRollService(1).roll("0").total());
		assertEquals(Integer.MAX_VALUE, new ScriptedRollService(1).roll("2147483647").total());
		assertEquals(Integer.MIN_VALUE, new ScriptedRollService(1).roll("-2147483647-1").total());
	}

	@Test
	void aggregateModifierAndPossibleTotalOverflowAreRejectedBeforeRolling() {
		for (String invalid : List.of("2147483647+1", "-2147483647-2", "2147483647+1d1", "-2147483647-2d1",
				"2147483646+1d2", "-2147483647-1d2", "2147483647+1-1d1", "2147483647+1d6-1d6", "2147483646+2d1kh2",
				"2147483646+3d1dl1", "-2147483647-3d1dh1", "2147483640+2d6kl2")) {
			assertInvalidExpression(invalid);
		}
		ScriptedRollService dice = new ScriptedRollService(1).queue(6);
		RpgException invalid = assertThrows(RpgException.class, () -> dice.roll("2147483647+1d6"));
		assertEquals(ErrorCode.INVALID_ARGUMENT, invalid.code());
		assertEquals(6, dice.roll("1d6").total(), "even a possible-overflow failure consumes no random draw");
		assertTrue(dice.exhausted());
	}

	@Test
	void finalCancellationAndActualRetainedDiceDetermineTheAllowedResultRange() {
		assertEquals(Integer.MAX_VALUE, new ScriptedRollService(1).roll("2147483647+1-1").total());
		assertEquals(Integer.MIN_VALUE, new ScriptedRollService(1).roll("-2147483647-2+1").total());
		assertEquals(Integer.MAX_VALUE, new ScriptedRollService(1).queue(1, 1).roll("2147483647+1d1-1d1").total());
		assertEquals(Integer.MAX_VALUE, new ScriptedRollService(1).queue(2).roll("2147483645+1d2").total());
		assertEquals(Integer.MAX_VALUE, new ScriptedRollService(1).queue(1, 2, 3, 6).roll("2147483641+4d6kh1").total());
		assertEquals(Integer.MIN_VALUE,
				new ScriptedRollService(1).queue(6, 6, 6, 6).roll("-2147483642-4d6kl1").total());
		assertEquals(Integer.MAX_VALUE, new ScriptedRollService(1).queue(1, 1, 1, 1).roll("2147483646+4d1dl3").total());
		assertEquals(Integer.MIN_VALUE,
				new ScriptedRollService(1).queue(1, 1, 1, 1).roll("-2147483647-4d1dh3").total());
		Roll separateGroups = new ScriptedRollService(1).roll("1000d1+1000d1");
		assertEquals(2000, separateGroups.total());
		assertEquals(2000, separateGroups.dice().size(),
				"the existing limit applies to each term, not the whole expression");
	}

	@Test
	void invalidDiceToolResponsesPreserveTheCampaignAndJournalAndSuccessfulReplayDoesNotDrawAgain() throws Exception {
		try (Engine engine = engine(tempDb("dice-validation-free-roll"))) {
			String campaign = committedCampaign(engine);
			engine.sessions().bootstrap(op(), campaign, null);
			long campaignId = Ref.id(campaign, Ref.CAMPAIGN);
			Map<String, List<Map<String, Object>>> before = engine.db().snapshot(campaignId);
			long journalBefore = engine.db()
					.read(tx -> tx.count("SELECT COUNT(*) FROM journal_entry WHERE campaign_id = ?", campaignId));
			ScriptedRollService dice = (ScriptedRollService) engine.roller();
			DiceTools tools = new DiceTools();
			tools.engine = engine;
			dice.queue(2, 6, 4);
			for (String invalid : List.of("1d6+", "2d6kh0", "2d6dl2", "999999999999999999999d6", "2147483647+1d6")) {
				ToolResponse rejected = tools.rollDice(op(), campaign, invalid, Optional.empty(), Optional.empty());
				assertTrue(rejected.isError(), invalid);
				Map<String, Object> response = Json.readMap(((TextContent) rejected.firstContent()).text());
				assertEquals(ErrorCode.INVALID_ARGUMENT.name(), ((Map<?, ?>) response.get("error")).get("code"),
						"malformed tool arguments never become INTERNAL_ERROR: " + invalid);
				assertEquals(before, engine.db().snapshot(campaignId));
				assertEquals(journalBefore,
						engine.db().read(
								tx -> tx.count("SELECT COUNT(*) FROM journal_entry WHERE campaign_id = ?", campaignId))
								.longValue());
			}
			String operation = op();
			Map<String, Object> rolled = engine.checks().rollDice(operation, campaign, "2d6kh1", "character:1",
					"a valid roll");
			assertEquals(6, rolled.get("total"), "failed validations leave the scripted dice untouched");
			Map<String, List<Map<String, Object>>> after = engine.db().snapshot(campaignId);
			Map<String, Object> replay = engine.checks().rollDice(operation, campaign, "2d6kh1", "character:1",
					"a valid roll");
			assertEquals(Boolean.TRUE, replay.get("replayed"));
			assertEquals(rolled.get("roll_ref"), replay.get("roll_ref"));
			assertEquals(after, engine.db().snapshot(campaignId));
			assertEquals(4,
					engine.checks().rollDice(op(), campaign, "1d6", "character:1", "the next valid roll").get("total"));
			assertTrue(dice.exhausted(), "replaying a successful operation does not consume another die");
		}
	}

	private static void assertInvalidExpression(String expression) {
		RpgException rejected = assertThrows(RpgException.class, () -> DiceExpression.parse(expression),
				String.valueOf(expression));
		assertEquals(ErrorCode.INVALID_ARGUMENT, rejected.code(), String.valueOf(expression));
	}
}
