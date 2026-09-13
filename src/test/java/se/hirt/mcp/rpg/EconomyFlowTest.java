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
import se.hirt.mcp.rpg.protocol.RpgException;
import se.hirt.mcp.rpg.session.Calendar;
import se.hirt.mcp.rpg.session.GameTime;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static se.hirt.mcp.rpg.TestCampaigns.map;
import static se.hirt.mcp.rpg.TestCampaigns.op;

/**
 * The calendar, accounts and scheduled cash flows (MCP_PROTOCOL.md §14.6–14.8, DATABASE.md §3.11):
 * the engine does the bookkeeping when the clock moves.
 */
class EconomyFlowTest {

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

	/** Advances to 00:00 of the next day so the tests start from a known point. */
	private static void toMidnight(Engine engine, String campaign) {
		long now = seq(engine, campaign);
		long target = (now / GameTime.MINUTES_PER_DAY + 1) * GameTime.MINUTES_PER_DAY;
		engine.sessions().advanceTime(op(), campaign, (int) (target - now), "to midnight");
		assertEquals(target, seq(engine, campaign));
	}

	@Test
	void calendarMath() {
		Calendar cal = Calendar.DEFAULT;
		Map<String, Object> day1 = cal.dateOf(0);
		assertEquals(1L, day1.get("year"));
		assertEquals(3, day1.get("month"));
		assertEquals(1, day1.get("day"));
		assertEquals("Thursday", day1.get("weekday_name"), "1 March of year 1 is a Thursday (1 January is a Monday)");
		assertEquals("SPRING", day1.get("season"));
		assertEquals(60, day1.get("day_of_year"));

		Map<String, Object> newYear = cal.dateOf(306L * GameTime.MINUTES_PER_DAY + 90);
		assertEquals(2L, newYear.get("year"), "306 days after 1 March is 1 January of the next year");
		assertEquals(1, newYear.get("month"));
		assertEquals(1, newYear.get("day"));
		assertEquals("WINTER", newYear.get("season"));
		assertEquals("Tuesday", newYear.get("weekday_name"), "365 days move the weekday by one");
		assertEquals(306L * GameTime.MINUTES_PER_DAY + 90, cal.seqOf(2, 1, 1, 90));
		assertEquals("Day 307, 01:30 (Tuesday 1 January, year 2, winter)",
				cal.render(306L * GameTime.MINUTES_PER_DAY + 90));

		Calendar december = new Calendar(1, 12, 1);
		assertEquals("Saturday", december.dateOf(0).get("weekday_name"));
		assertEquals("WINTER", december.dateOf(0).get("season"));
		assertEquals("SPRING", december.dateOf(90L * GameTime.MINUTES_PER_DAY).get("season"), "1 March is 90 days on");
		assertEquals(4, Calendar.DEFAULT.dateOf(0).get("weekday"));
		assertThrows(RpgException.class, () -> new Calendar(1, 2, 30));

		Map<String, Object> withDate = GameTime.toMap(0, cal);
		assertEquals("Day 1, 00:00", withDate.get("instant"));
		assertEquals("SPRING", m(withDate.get("date")).get("season"));
	}

	@Test
	void weeklyFlowsWithSeasonsSharesAndInflows() throws Exception {
		try (Engine engine = TestCampaigns.engine(TestCampaigns.tempDb("economy-flows"))) {
			String campaign = TestCampaigns.committedCampaign(engine);
			engine.sessions().bootstrap(op(), campaign, null);
			String pc = "character:1";

			// Day 1 = 1 December, year 1: a Saturday in winter.
			Map<String, Object> calendar = engine.cashFlows().setCalendar(op(), campaign, 1, 12, 1, false);
			assertEquals("WINTER", m(m(calendar.get("game_time")).get("date")).get("season"));
			assertThrows(RpgException.class, () -> engine.cashFlows().setCalendar(op(), campaign, 1, 1, 1, false),
					"a set epoch is not moved without force");

			Map<String, Object> house = engine.accounts().create(op(), campaign, "The House of Greystone", "OTHER",
					null, null, "The estate treasury.");
			assertEquals("account:1", house.get("account"));
			assertEquals(0L, m(house.get("money")).get("total_cp"));
			assertThrows(RpgException.class,
					() -> engine.accounts().create(op(), campaign, "The House of Greystone", null, null, null, null));

			toMidnight(engine, campaign); // Day 2, 00:00 — Sunday 2 December
			long start = seq(engine, campaign);

			Map<String, Object> market = engine.cashFlows().define(op(), campaign,
					map("name", "Thursday market", "from", "WORLD", "to", "The House of Greystone", "amount",
							"3 gp 4 sp", "schedule", map("kind", "WEEKLY", "weekday", "Thursday", "time", "06:00"),
							"season", map("WINTER", 0.5), "description", "Stallage from the Thursday market."));
			assertEquals("cash_flow:1", market.get("cash_flow"));
			assertEquals("Day 6, 06:00", m(market.get("next_due")).get("instant"), "the first Thursday after Day 2");
			assertEquals("Thursday", m(m(market.get("next_due")).get("date")).get("weekday_name"));

			Map<String, Object> stipend = engine.cashFlows().define(op(), campaign,
					map("name", "Keeper's stipend", "from", "account:1", "to", pc, "amount",
							map("percent", 10, "of_rule", "Thursday market"), "schedule",
							map("kind", "WEEKLY", "weekday", 4, "minute_of_day", 360)));
			assertEquals("cash_flow:1", m(stipend.get("amount")).get("of_rule"));

			engine.cashFlows().define(op(), campaign,
					map("name", "Friday tithe", "from", "The House of Greystone", "to", "WORLD", "amount",
							map("percent", 10, "of_inflows", "account:1"), "schedule",
							map("kind", "WEEKLY", "weekday", "Friday")));

			long purseBefore = balance(engine, "character", 1);
			Map<String, Object> advanced = engine.sessions().advanceTime(op(), campaign, 10 * GameTime.MINUTES_PER_DAY,
					"ten days");
			List<Map<String, Object>> consequences = list(advanced.get("consequences"));
			List<Map<String, Object>> flows = consequences.stream().filter(c -> "CASH_FLOW".equals(c.get("type")))
					.toList();
			assertEquals(3, flows.size(), "one Thursday and one Friday in Day 2..12: " + consequences);
			assertEquals("Thursday market", flows.get(0).get("name"));
			assertEquals("PAID", flows.get(0).get("status"));
			assertEquals(170L, m(flows.get(0).get("amount")).get("total_cp"), "3 gp 4 sp × 0.5 in winter");
			assertEquals("Keeper's stipend", flows.get(1).get("name"), "shares of a rule fire after the rule");
			assertEquals(17L, m(flows.get(1).get("amount")).get("total_cp"));
			assertEquals("Friday tithe", flows.get(2).get("name"));
			assertEquals(17L, m(flows.get(2).get("amount")).get("total_cp"), "10% of the 170 cp that came in");
			assertEquals("Day 13, 06:00", flows.get(0).get("next_due").toString().substring(0, 13));
			assertEquals(136L, balance(engine, "account", 1), "170 in, 17 to the Keeper, 17 tithed");
			assertEquals(purseBefore + 17, balance(engine, "character", 1));
			assertEquals("WINTER", m(m(advanced.get("to")).get("date")).get("season"));

			// The ledger carries dated MONEY_FLOW events at the due point, not at the end of the advance.
			List<Row> events = engine.db().read(
					tx -> tx.query("SELECT * FROM event WHERE campaign_id = 1 AND type = 'MONEY_FLOW' ORDER BY id"));
			assertEquals(3, events.size());
			assertEquals(start + 4 * GameTime.MINUTES_PER_DAY + 360, events.get(0).lng("fictional_seq"));
			assertTrue(events.get(0).str("summary").startsWith("Thursday market: 1 gp 7 sp to The House of Greystone"),
					events.get(0).str("summary"));

			// A long rest crossing a DAILY due point fires it too.
			engine.cashFlows().define(op(), campaign, map("name", "Alms", "from", "WORLD", "to", "account:1", "amount",
					5, "schedule", map("kind", "DAILY", "time", "04:00")));
			Map<String, Object> rest = engine.rest().rest(op(), campaign, "LONG", null, null);
			assertEquals(1, list(rest.get("consequences")).size(), rest.get("consequences").toString());
			assertEquals(141L, balance(engine, "account", 1));

			// Stopping a rule.
			Map<String, Object> stopped = engine.cashFlows().update(op(), campaign, "Alms", map("active", false));
			assertEquals(Boolean.FALSE, stopped.get("active"));
			assertEquals(3, list(engine.cashFlows().list(campaign, false).get("cash_flows")).size());
			assertEquals(4, list(engine.cashFlows().list(campaign, true).get("cash_flows")).size());

			Map<String, Object> accounts = engine.accounts().accounts(campaign, 10);
			List<Map<String, Object>> accs = list(accounts.get("accounts"));
			assertEquals(1, accs.size());
			assertEquals("The House of Greystone", accs.get(0).get("name"));
			assertEquals(141L, m(accs.get(0).get("money")).get("total_cp"));
			assertEquals(3, list(accs.get(0).get("upcoming")).size());
			assertEquals("Thursday market", list(accs.get(0).get("upcoming")).get(0).get("name"));
			assertEquals(4, list(accounts.get("recent_runs")).size());
			assertEquals(1, list(accounts.get("purses")).size());
			assertEquals(Boolean.TRUE, m(accounts.get("calendar")).get("set"));
		}
	}

	@Test
	void unpaidEventsAndOneOffs() throws Exception {
		try (Engine engine = TestCampaigns.engine(TestCampaigns.tempDb("economy-unpaid"))) {
			String campaign = TestCampaigns.committedCampaign(engine);
			engine.sessions().bootstrap(op(), campaign, null);
			engine.accounts().create(op(), campaign, "Sterncliff", "OTHER", null, null, null);
			toMidnight(engine, campaign); // Day 2 — 2 March (default calendar)

			engine.cashFlows().define(op(), campaign, map("name", "Hollins Reach lease", "from", "Sterncliff", "to",
					"WORLD", "amount", map("gp", 25), "schedule", map("kind", "MONTHLY", "day", 1)));
			engine.cashFlows().define(op(), campaign,
					map("name", "Dark of the moon", "kind", "EVENT", "description",
							"The dark of the moon: the fen hearth is at its weakest.", "schedule", "ONCE", "start",
							"Day 20, 00:00"));
			engine.cashFlows().define(op(), campaign, map("name", "Midsummer", "kind", "EVENT", "description",
					"Midsummer.", "schedule", map("kind", "YEARLY", "month", "June", "day", 21)));
			engine.cashFlows().define(op(), campaign, map("name", "Harvest", "from", "WORLD", "to", "Sterncliff",
					"amount", "100 gp", "schedule", map("kind", "SEASONAL", "season", "AUTUMN")));

			// Day 2 → Day 40: crosses 1 April (lease, unpaid) and Day 20 (the event); not midsummer, not autumn.
			Map<String, Object> advanced = engine.sessions().advanceTime(op(), campaign, 38 * GameTime.MINUTES_PER_DAY,
					"spring");
			List<Map<String, Object>> flows = list(advanced.get("consequences")).stream()
					.filter(c -> "CASH_FLOW".equals(c.get("type"))).toList();
			assertEquals(2, flows.size(), flows.toString());
			Map<String, Object> event = flows.stream().filter(f -> f.get("name").equals("Dark of the moon")).findFirst()
					.orElseThrow();
			assertEquals("FIRED", event.get("status"));
			assertEquals(Boolean.TRUE, event.get("finished"), "a ONCE rule is finished after it fires");
			Map<String, Object> lease = flows.stream().filter(f -> f.get("name").equals("Hollins Reach lease"))
					.findFirst().orElseThrow();
			assertEquals("UNPAID", lease.get("status"));
			assertEquals(2500L, m(lease.get("amount")).get("total_cp"));
			assertEquals(0L, balance(engine, "account", 1));

			List<Row> events = engine.db().read(tx -> tx.query(
					"SELECT * FROM event WHERE campaign_id = 1 AND type IN ('MONEY_FLOW','WORLD_EVENT') ORDER BY id"));
			assertEquals(2, events.size());
			assertEquals("WORLD_EVENT", events.get(0).str("type"));
			assertEquals("The dark of the moon: the fen hearth is at its weakest.", events.get(0).str("summary"));
			assertEquals("NOTABLE", events.get(1).str("importance"));
			assertTrue(events.get(1).str("summary").startsWith("Unpaid: Hollins Reach lease"),
					events.get(1).str("summary"));

			// Money arrives; the next due month is paid. Midsummer and the harvest fall in the same long advance.
			engine.accounts().transfer(op(), campaign, "WORLD", "Sterncliff", "60 gp", "the Archbishop's purse");
			assertThrows(RpgException.class,
					() -> engine.accounts().transfer(op(), campaign, "Sterncliff", "character:1", "500 gp", null));
			Map<String, Object> summer = engine.sessions().advanceTime(op(), campaign, 200 * GameTime.MINUTES_PER_DAY,
					"to autumn");
			List<Map<String, Object>> later = list(summer.get("consequences")).stream()
					.filter(c -> "CASH_FLOW".equals(c.get("type"))).toList();
			long paid = later.stream()
					.filter(f -> "PAID".equals(f.get("status")) && f.get("name").equals("Hollins Reach lease")).count();
			long unpaid = later.stream().filter(f -> "UNPAID".equals(f.get("status"))).count();
			assertEquals(3, paid, "60 gp covers May and June; the harvest pays October: " + later);
			assertEquals(3, unpaid, "July to September the treasury holds only 10 gp: " + later);
			assertTrue(
					later.stream().anyMatch(f -> f.get("name").equals("Midsummer") && "FIRED".equals(f.get("status"))));
			assertTrue(later.stream().anyMatch(f -> f.get("name").equals("Harvest") && "PAID".equals(f.get("status"))));
			List<Map<String, Object>> listed = list(engine.cashFlows().list(campaign, true).get("cash_flows"));
			assertEquals(4, listed.size());
			assertEquals(Boolean.FALSE, listed.stream().filter(f -> f.get("name").equals("Dark of the moon"))
					.findFirst().orElseThrow().get("active"));
		}
	}

	@Test
	void aCappedDebtClearsItself() throws Exception {
		try (Engine engine = TestCampaigns.engine(TestCampaigns.tempDb("economy-debt"))) {
			String campaign = TestCampaigns.committedCampaign(engine);
			engine.sessions().bootstrap(op(), campaign, null);
			engine.accounts().create(op(), campaign, "The House of Greystone", "OTHER", null, "300 gp", null);
			toMidnight(engine, campaign); // Day 2 — 2 March, year 1 (default calendar)
			long purseBefore = balance(engine, "character", 1);

			Map<String, Object> advance = engine.cashFlows().define(op(), campaign,
					map("name", "Keeper's Advance", "from", "The House of Greystone", "to", "character:1", "amount",
							"100 gp", "cap", "662 gp 5 sp", "schedule", map("kind", "MONTHLY", "day", 1), "description",
							"The Keeper's Advance, repaid from House money."));
			assertEquals(66250L, m(advance.get("cap")).get("total_cp"));
			assertEquals(66250L, m(advance.get("remaining")).get("total_cp"));
			assertEquals("Day 32, 00:00", m(advance.get("next_due")).get("instant"), "1 April");

			// Day 2 -> Day 125 (2 July): April, May, June paid; July unpaid — the treasury holds nothing.
			Map<String, Object> spring = engine.sessions().advanceTime(op(), campaign, 123 * GameTime.MINUTES_PER_DAY,
					"spring");
			List<Map<String, Object>> runs = list(spring.get("consequences")).stream()
					.filter(c -> "CASH_FLOW".equals(c.get("type"))).toList();
			assertEquals(4, runs.size(), runs.toString());
			assertEquals(List.of("PAID", "PAID", "PAID", "UNPAID"), runs.stream().map(r -> r.get("status")).toList());
			assertEquals(36250L, m(runs.get(3).get("remaining")).get("total_cp"),
					"an unpaid month leaves the debt as it was");
			assertEquals(0L, balance(engine, "account", 1));
			assertEquals(purseBefore + 30000, balance(engine, "character", 1));

			// Money arrives; four more months clear the debt with a final partial payment, then the rule stops.
			engine.accounts().transfer(op(), campaign, "WORLD", "The House of Greystone", "400 gp", "the harvest");
			Map<String, Object> autumn = engine.sessions().advanceTime(op(), campaign, 123 * GameTime.MINUTES_PER_DAY,
					"to November");
			runs = list(autumn.get("consequences")).stream().filter(c -> "CASH_FLOW".equals(c.get("type"))).toList();
			assertEquals(4, runs.size(), runs.toString());
			assertEquals(List.of(10000L, 10000L, 10000L, 6250L),
					runs.stream().map(r -> m(r.get("amount")).get("total_cp")).toList());
			assertEquals(Boolean.TRUE, runs.get(3).get("finished"));
			assertEquals("cleared: 662 gp 5 sp of 662 gp 5 sp paid", runs.get(3).get("note"));
			assertEquals(0L, m(runs.get(3).get("remaining")).get("total_cp"));
			assertEquals(40000L - 36250L, balance(engine, "account", 1));
			assertEquals(purseBefore + 66250L, balance(engine, "character", 1));
			List<Row> paid = engine.db().read(tx -> tx.query(
					"SELECT * FROM event WHERE campaign_id = 1 AND type = 'MONEY_FLOW' AND summary LIKE 'Keeper''s Advance:%' ORDER BY id"));
			assertEquals(7, paid.size());
			assertTrue(paid.get(6).str("summary").contains("cleared: 662 gp 5 sp of 662 gp 5 sp paid"),
					paid.get(6).str("summary"));
			assertTrue(paid.get(0).str("summary").contains("562 gp 5 sp of 662 gp 5 sp still owed"),
					paid.get(0).str("summary"));

			Map<String, Object> listed = list(engine.cashFlows().list(campaign, true).get("cash_flows")).get(0);
			assertEquals(Boolean.FALSE, listed.get("active"));
			assertNull(listed.get("next_due"));
			assertEquals(66250L, m(listed.get("paid")).get("total_cp"));
			assertTrue(list(engine.cashFlows().list(campaign, false).get("cash_flows")).isEmpty());

			// No eighth run.
			Map<String, Object> winter = engine.sessions().advanceTime(op(), campaign, 40 * GameTime.MINUTES_PER_DAY,
					"December");
			assertTrue(list(winter.get("consequences")).stream().noneMatch(c -> "CASH_FLOW".equals(c.get("type"))));
			assertEquals(40000L - 36250L, balance(engine, "account", 1));
		}
	}

	@Test
	void conditionsAndValidation() throws Exception {
		try (Engine engine = TestCampaigns.engine(TestCampaigns.tempDb("economy-conditions"))) {
			String campaign = TestCampaigns.committedCampaign(engine);
			engine.sessions().bootstrap(op(), campaign, null);
			engine.accounts().create(op(), campaign, "Chest", "OTHER", null, "10 gp", null);
			assertEquals(1000L, balance(engine, "account", 1));
			toMidnight(engine, campaign);

			assertThrows(RpgException.class, () -> engine.cashFlows().define(op(), campaign,
					map("name", "Nowhere", "from", "WORLD", "to", "WORLD", "amount", 5, "schedule", "DAILY")));
			assertThrows(RpgException.class, () -> engine.cashFlows().define(op(), campaign,
					map("name", "No schedule", "from", "WORLD", "to", "Chest", "amount", 5)));
			assertThrows(RpgException.class,
					() -> engine.cashFlows().define(op(), campaign, map("name", "Bad weekday", "from", "WORLD", "to",
							"Chest", "amount", 5, "schedule", map("kind", "WEEKLY", "weekday", "Someday"))));
			assertThrows(RpgException.class,
					() -> engine.cashFlows().define(op(), campaign, map("name", "Missing", "from", "WORLD", "to",
							"Chest", "amount", 5, "schedule", "DAILY", "condition", map("quest", "quest:99"))));

			// A quest-conditioned flow is SKIPPED until the quest reaches the required status.
			engine.narrative().upsert(op(), campaign, "QUEST", null,
					map("title", "Keep the hearth", "status", "OFFERED"), null);
			engine.cashFlows().define(op(), campaign,
					map("name", "Keeper's fee", "from", "WORLD", "to", "Chest", "amount", "1 gp", "schedule",
							map("kind", "DAILY", "time", "12:00"), "condition",
							map("quest", "quest:1", "status", "ACCEPTED")));
			Map<String, Object> day = engine.sessions().advanceTime(op(), campaign, GameTime.MINUTES_PER_DAY, "a day");
			List<Map<String, Object>> flows = list(day.get("consequences")).stream()
					.filter(c -> "CASH_FLOW".equals(c.get("type"))).toList();
			assertEquals(1, flows.size());
			assertEquals("SKIPPED", flows.get(0).get("status"));
			assertEquals(1000L, balance(engine, "account", 1));
		}
	}
}
