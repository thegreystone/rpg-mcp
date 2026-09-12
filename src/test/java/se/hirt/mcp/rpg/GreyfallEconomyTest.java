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
import se.hirt.mcp.rpg.persistence.Row;
import se.hirt.mcp.rpg.session.GameTime;
import se.hirt.mcp.rpg.session.SessionService;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static se.hirt.mcp.rpg.TestCampaigns.map;
import static se.hirt.mcp.rpg.TestCampaigns.op;

/**
 * The Greyfall campaign's economy as it is meant to be entered after the upgrade (played state as of Day 96): the
 * calendar epoch, the House of Greystone's treasury, every recurring flow and dated event, the Keeper's Advance as a
 * capped debt, and the standing house rules. This is the starting point the live campaign is set up from, so every
 * spec here must be accepted as written and fire as expected when the clock moves.
 */
class GreyfallEconomyTest {

	private static final String HOUSE = "The House of Greystone";

	@SuppressWarnings("unchecked")
	private static Map<String, Object> m(Object o) {
		return (Map<String, Object>) o;
	}

	@SuppressWarnings("unchecked")
	private static List<Map<String, Object>> list(Object o) {
		return (List<Map<String, Object>>) o;
	}

	private static long seq(Engine engine, String campaign) {
		long id = Long.parseLong(campaign.substring(campaign.indexOf(':') + 1));
		return engine.db().read(tx -> GameTime.currentSeq(tx, id));
	}

	private static long balance(Engine engine, String table, long id) {
		return engine.db().read(tx -> tx.get(table, id).lng("money_cp"));
	}

	private static List<Map<String, Object>> flows(Map<String, Object> advanced) {
		return list(advanced.get("consequences")).stream().filter(c -> "CASH_FLOW".equals(c.get("type"))).toList();
	}

	private static long count(List<Map<String, Object>> runs, String name, String status) {
		return runs.stream().filter(r -> name.equals(r.get("name")) && (status == null || status.equals(r.get("status"))))
				.count();
	}

	@Test
	void theGreyfallLedgerCanBeEnteredAndRuns() throws Exception {
		Duration gap = SessionService.SESSION_GAP;
		try (Engine engine = TestCampaigns.engine(TestCampaigns.tempDb("greyfall-economy"))) {
			String campaign = TestCampaigns.committedCampaign(engine);
			engine.sessions().bootstrap(op(), campaign, null);
			String richard = "character:1";

			// ── The table's standing rulings ──
			engine.sessions().updateHouseRules(op(), campaign,
					List.of("No firearms in this world; a stat-block pistol is played as a light crossbow.",
							"Richard's never-a-life-that-can-be-avoided rule does not cover monsters (anything that would eat him for supper).",
							"Probationers are asked, every day, whether they still hold to their oath.", "Ilsa is nineteen."),
					"REPLACE");

			// ── Calendar: Day 1 = 1 February, year 1; the campaign is on Day 96 = 7 May, a Monday in spring ──
			Map<String, Object> calendar = engine.cashFlows().setCalendar(op(), campaign, 1, 2, 1, false);
			assertEquals("WINTER", m(m(calendar.get("game_time")).get("date")).get("season"), "Day 1 is 1 February");
			long now = seq(engine, campaign);
			long day96 = 95L * GameTime.MINUTES_PER_DAY + 1; // Day 96, 00:01
			engine.sessions().advanceTime(op(), campaign, (int) (day96 - now), "catching the clock up to the played state");
			Map<String, Object> time = engine.sessions().party(campaign, "SUMMARY");
			Map<String, Object> date = m(m(time.get("game_time")).get("date"));
			assertEquals("Day 96, 00:01", m(time.get("game_time")).get("instant"));
			assertEquals("May", date.get("month_name"));
			assertEquals(7, date.get("day"));
			assertEquals("Monday", date.get("weekday_name"));
			assertEquals("SPRING", date.get("season"));

			// ── Money bags: the House treasury (opening balance is an audited grant) and the Keeper's own purse ──
			Map<String, Object> house = engine.accounts()
					.create(op(), campaign, HOUSE, "FACTION", null, "120 gp",
							"The estate treasury: market stallage, the mill toll, the fifth at harvest, the Hollins Reach lease.");
			long houseId = Long.parseLong(house.get("account").toString().substring("account:".length()));
			assertEquals(12000L, m(house.get("money")).get("total_cp"));
			long purseBefore = balance(engine, "character", 1);

			// ── Income to the House ──
			Map<String, Object> market = engine.cashFlows().define(op(), campaign,
					map("name", "Thursday market", "from", "WORLD", "to", HOUSE, "amount", "3 gp 4 sp", "schedule",
							map("kind", "WEEKLY", "weekday", "Thursday", "time", "06:00"), "season", map("WINTER", 0.5),
							"description", "Stallage from the Thursday market on the new square before the manor."));
			assertEquals("Day 99, 06:00", m(market.get("next_due")).get("instant"), "the first Thursday after Monday Day 96");
			engine.cashFlows().define(op(), campaign,
					map("name", "Mill toll", "from", "WORLD", "to", HOUSE, "amount", "12 sp", "schedule",
							map("kind", "WEEKLY", "weekday", "Monday", "time", "12:00"), "description",
							"The miller's toll, paid every Monday noon."));
			engine.cashFlows().define(op(), campaign,
					map("name", "The fifth at harvest", "from", "WORLD", "to", HOUSE, "amount", "200 gp", "schedule",
							map("kind", "SEASONAL", "season", "AUTUMN"), "description",
							"A fifth of the yield of the cots' land, paid to the House at harvest instead of rent."));
			// ── Outgoings from the House ──
			Map<String, Object> stipend = engine.cashFlows().define(op(), campaign,
					map("name", "Keeper's stipend", "from", HOUSE, "to", richard, "amount",
							map("percent", 10, "of_inflows", "account:" + houseId), "schedule",
							map("kind", "WEEKLY", "weekday", "Sunday", "time", "18:00"), "description",
							"The Keeper's tenth of everything the House took in that week."));
			assertEquals("Day 102, 18:00", m(stipend.get("next_due")).get("instant"));
			engine.cashFlows().define(op(), campaign,
					map("name", "Hollins Reach lease", "from", HOUSE, "to", WORLD_REF, "amount", map("gp", 25),
							"schedule", map("kind", "YEARLY", "month", "March", "day", 25), "description",
							"The Hollins Reach lease, due on Lady Day."));
			Map<String, Object> advance = engine.cashFlows().define(op(), campaign,
					map("name", "Keeper's Advance", "from", HOUSE, "to", richard, "amount", "100 gp", "cap",
							"662 gp 5 sp", "schedule", map("kind", "MONTHLY", "day", 1), "description",
							"What Richard advanced the House from his own purse, repaid monthly until cleared."));
			assertEquals(66250L, m(advance.get("remaining")).get("total_cp"));
			assertEquals("Day 121, 00:00", m(advance.get("next_due")).get("instant"), "1 June");
			// ── Dated events the GM must not have to remember ──
			engine.cashFlows().define(op(), campaign,
					map("name", "Dark of the moon", "kind", "EVENT", "schedule", "ONCE", "start", "Day 118, 00:00",
							"description", "The dark of the moon: the seventh breath at the fen edge is at its weakest."));
			engine.cashFlows().define(op(), campaign,
					map("name", "Midsummer", "kind", "EVENT", "schedule",
							map("kind", "YEARLY", "month", "June", "day", 21), "description",
							"Midsummer: Ilsa's term at Sterncliff ends; Richard promised to fetch her."));
			engine.cashFlows().define(op(), campaign,
					map("name", "Elowen's child", "kind", "EVENT", "schedule", "ONCE", "start", "Day 205, 00:00",
							"description", "Elowen's child is due around the harvest."));
			engine.cashFlows().define(op(), campaign,
					map("name", "Maren's term", "kind", "EVENT", "schedule", "ONCE", "start", "Day 360, 00:00",
							"description", "Maren's child is due."));
			assertEquals(10, list(engine.cashFlows().list(campaign, false).get("cash_flows")).size());

			// ── Week one: Monday noon toll, Thursday market, Sunday stipend of a tenth ──
			Map<String, Object> week = engine.sessions().advanceTime(op(), campaign, 7 * GameTime.MINUTES_PER_DAY, "a week at home");
			List<Map<String, Object>> runs = flows(week);
			assertEquals(List.of("Mill toll", "Thursday market", "Keeper's stipend"),
					runs.stream().map(r -> r.get("name")).toList(), runs.toString());
			assertTrue(runs.stream().allMatch(r -> "PAID".equals(r.get("status"))), runs.toString());
			assertEquals(46L, m(runs.get(2).get("amount")).get("total_cp"), "a tenth of 3 gp 4 sp + 12 sp");
			assertEquals(12000 + 460 - 46, balance(engine, "account", houseId));
			assertEquals(purseBefore + 46, balance(engine, "character", 1));

			// ── To 1 June: three more weeks, the dark of the moon on Day 118, the first Advance payment ──
			Map<String, Object> june = engine.sessions().advanceTime(op(), campaign, 18 * GameTime.MINUTES_PER_DAY, "to the first of June");
			runs = flows(june);
			assertEquals(3, count(runs, "Thursday market", "PAID"));
			assertEquals(3, count(runs, "Mill toll", "PAID"));
			assertEquals(2, count(runs, "Keeper's stipend", "PAID"));
			assertEquals(1, count(runs, "Dark of the moon", "FIRED"));
			assertEquals(1, count(runs, "Keeper's Advance", "PAID"));
			Map<String, Object> firstPayment = runs.stream().filter(r -> "Keeper's Advance".equals(r.get("name"))).findFirst().orElseThrow();
			assertEquals(10000L, m(firstPayment.get("amount")).get("total_cp"));
			assertEquals(56250L, m(firstPayment.get("remaining")).get("total_cp"));
			assertEquals("Day 121, 00:01", m(june.get("to")).get("instant"));
			List<Row> worldEvents = engine.db().read(tx -> tx.query(
					"SELECT * FROM event WHERE campaign_id = 1 AND type = 'WORLD_EVENT' ORDER BY id"));
			assertEquals(1, worldEvents.size());
			assertTrue(worldEvents.get(0).str("summary").contains("dark of the moon"));
			assertEquals(117L * GameTime.MINUTES_PER_DAY, worldEvents.get(0).lng("fictional_seq"), "dated at Day 118 00:00");

			// ── Through the summer to the first of September: midsummer, the harvest fifth, lean months unpaid ──
			Map<String, Object> autumn = engine.sessions().advanceTime(op(), campaign, 93 * GameTime.MINUTES_PER_DAY, "to the first of September");
			runs = flows(autumn);
			assertEquals(1, count(runs, "Midsummer", "FIRED"));
			assertEquals(1, count(runs, "Elowen's child", null) + count(runs, "Maren's term", null), "Elowen's child (Day 205) fires, Maren's term (Day 360) does not yet");
			assertEquals(1, count(runs, "The fifth at harvest", "PAID"), runs.toString());
			assertEquals(20000L, m(runs.stream().filter(r -> "The fifth at harvest".equals(r.get("name"))).findFirst().orElseThrow().get("amount")).get("total_cp"));
			assertEquals(3, count(runs, "Keeper's Advance", null), "July, August, September");
			assertTrue(count(runs, "Keeper's Advance", "UNPAID") >= 1, "the House cannot pay 100 gp every month out of market stallage: " + runs);
			assertEquals(0, count(runs, "Hollins Reach lease", null), "Lady Day is next spring");
			assertEquals(1, list(engine.cashFlows().list(campaign, false).get("cash_flows")).stream()
					.filter(f -> "Keeper's Advance".equals(f.get("name"))).count(), "the debt is still open");
			Map<String, Object> accounts = engine.accounts().accounts(campaign, 5);
			Map<String, Object> houseView = list(accounts.get("accounts")).get(0);
			assertEquals(HOUSE, houseView.get("name"));
			assertTrue(list(houseView.get("upcoming")).stream().anyMatch(u -> "Hollins Reach lease".equals(u.get("name"))));
			assertEquals(5, list(accounts.get("recent_runs")).size());

			// ── The recap the next session opens with carries the money and the dates ──
			SessionService.SESSION_GAP = Duration.ZERO;
			Map<String, Object> next = engine.sessions().bootstrap(op(), campaign, null);
			assertEquals(false, next.get("session_resumed"));
			Map<String, Object> recap = m(m(next.get("chronicle")).get("since_last_chapter"));
			assertNotNull(recap);
			assertTrue(((List<?>) recap.get("notable")).stream().anyMatch(n -> n.toString().contains("Midsummer"))
					|| m(recap.get("minor_by_type")).containsKey("WORLD_EVENT")
					|| ((List<?>) recap.get("notable")).stream().anyMatch(n -> n.toString().contains("WORLD_EVENT")),
					recap.toString());
			assertEquals(4, ((List<?>) m(next.get("campaign")).get("house_rules")).size());
			assertEquals("AUTUMN", m(m(engine.cashFlows().setCalendar(op(), campaign, 1, 2, 1, true).get("game_time")).get("date")).get("season"),
					"re-setting the same epoch with force is harmless; and 1 September is autumn");
		} finally {
			SessionService.SESSION_GAP = gap;
		}
	}

	private static final String WORLD_REF = "WORLD";
}
