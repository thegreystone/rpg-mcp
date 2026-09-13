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
package se.hirt.mcp.rpg.economy;

import se.hirt.mcp.rpg.harness.Harness;
import se.hirt.mcp.rpg.ledger.LedgerService;
import se.hirt.mcp.rpg.persistence.Database;
import se.hirt.mcp.rpg.persistence.Row;
import se.hirt.mcp.rpg.persistence.Tx;
import se.hirt.mcp.rpg.protocol.Json;
import se.hirt.mcp.rpg.protocol.Ref;
import se.hirt.mcp.rpg.protocol.RpgException;
import se.hirt.mcp.rpg.rules.Money;
import se.hirt.mcp.rpg.session.Calendar;
import se.hirt.mcp.rpg.session.GameTime;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Cash-flow rules (MCP_PROTOCOL.md §14.7, DATABASE.md §3.11): recurring or one-off transfers
 * between money bags, and dated events, that the {@link Scheduler} fires whenever the clock crosses
 * their next due point. The GM defines them once — "Thursday market stallage to the House", "a
 * fifth of the yield at harvest", "the Keeper's stipend, a tenth of the House's takings, every
 * week" — and never has to remember them again.
 * <p>
 * Stored shapes (all JSON columns on {@code cash_flow}):
 * <ul>
 * <li>{@code amount_json}: {@code {"fixed_cp": n}}, {@code {"percent": p, "of_rule": cashFlowId}}
 * (a share of another rule's payout at the same due time) or
 * {@code {"percent": p, "of_inflows": "account:n"|"character:n"}} (a share of everything that
 * flowed into that bag since this rule last ran).</li>
 * <li>{@code schedule_json}:
 * {@code {"kind": ONCE|DAILY|WEEKLY|MONTHLY|YEARLY|SEASONAL, "weekday": 1-7, "day": 1-31,
 * "month": 1-12, "season": SPRING|SUMMER|AUTUMN|WINTER, "minute_of_day": 0-1439}}.</li>
 * <li>{@code season_json}: multipliers per season of the due date, e.g.
 * {@code {"WINTER": 0.2}}.</li>
 * <li>{@code condition_json}: {@code {"quest": "quest:n", "status": "ACCEPTED"}}; a mismatch skips
 * the run.</li>
 * </ul>
 */
public final class CashFlowService {

	static final Set<String> KINDS = Set.of("MONEY", "EVENT");
	static final Set<String> SCHEDULES = Set.of("ONCE", "DAILY", "WEEKLY", "MONTHLY", "YEARLY", "SEASONAL");
	private static final Set<String> SPEC_KEYS = Set.of("name", "kind", "from", "to", "amount", "cap", "schedule",
			"season", "condition", "description", "start", "end");
	private static final Set<String> UPDATE_KEYS = Set.of("name", "from", "to", "amount", "cap", "schedule", "season",
			"condition", "description", "start", "end", "active");

	private final Database db;

	public CashFlowService(Database db) {
		this.db = db;
	}

	// ── define_cash_flow ───────────────────────────────────────────────

	public Map<String, Object> define(String operationId, String campaignRef, Map<String, Object> spec) {
		long campaignId = Ref.id(campaignRef, Ref.CAMPAIGN);
		var args = new LinkedHashMap<String, Object>();
		args.put("campaign", campaignRef);
		args.put("spec", spec);
		return db.mutate(Database.Mutation.of("define_cash_flow", campaignId, operationId, "GM", args), tx -> {
			Row campaign = Harness.requireMutation(tx, campaignRef, "define_cash_flow");
			if (spec == null || spec.isEmpty()) {
				throw RpgException
						.invalidArgument("A cash-flow spec is required: name, kind, from, to, amount, schedule …");
			}
			for (String key : spec.keySet()) {
				if (!SPEC_KEYS.contains(key)) {
					throw RpgException
							.invalidArgument("Unknown cash-flow key '" + key + "'; allowed: " + SPEC_KEYS + ".");
				}
			}
			String name = requireName(spec.get("name"));
			if (tx.queryOne("SELECT id FROM cash_flow WHERE campaign_id = ? AND name = ?", campaignId, name)
					.isPresent()) {
				throw RpgException.conflict("A cash flow named '" + name + "' already exists in this campaign.");
			}
			String kind = spec.get("kind") == null ? "MONEY" : spec.get("kind").toString().trim().toUpperCase();
			if (!KINDS.contains(kind)) {
				throw RpgException.invalidArgument("kind must be MONEY or EVENT.");
			}
			Calendar cal = Calendar.forCampaign(campaign);
			long now = GameTime.currentSeq(tx, campaignId);
			var cols = new LinkedHashMap<String, Object>();
			cols.put("campaign_id", campaignId);
			cols.put("name", name);
			cols.put("kind", kind);
			String description = spec.get("description") == null ? null : spec.get("description").toString();
			if (kind.equals("MONEY")) {
				MoneyRef from = MoneyRef.resolve(tx, campaignId, spec.get("from"));
				MoneyRef to = MoneyRef.resolve(tx, campaignId, spec.get("to"));
				if (from.isWorld() && to.isWorld()) {
					throw RpgException.invalidArgument("At least one of from/to must be an account or a character.");
				}
				if (from.equals(to)) {
					throw RpgException.invalidArgument("from and to are the same money bag.");
				}
				cols.put("from_ref", from.toString());
				cols.put("to_ref", to.toString());
				Map<String, Object> amount = normalizeAmount(tx, campaignId, spec.get("amount"));
				if (spec.get("cap") != null) {
					amount.put("total_cp", normalizeCap(spec.get("cap")));
				}
				cols.put("amount_json", Json.write(amount));
			} else {
				if (spec.get("cap") != null) {
					throw RpgException.invalidArgument("An EVENT cash flow has no cap: it moves no money.");
				}
				if (description == null || description.isBlank()) {
					throw RpgException
							.invalidArgument("An EVENT cash flow needs a description: the event it announces.");
				}
				if (spec.get("amount") != null || spec.get("from") != null || spec.get("to") != null) {
					throw RpgException.invalidArgument("An EVENT cash flow moves no money: omit from, to and amount.");
				}
			}
			cols.put("description", description);
			Map<String, Object> schedule = normalizeSchedule(spec.get("schedule"));
			cols.put("schedule_json", Json.write(schedule));
			cols.put("season_json", Json.writeOrNull(normalizeSeason(spec.get("season"))));
			cols.put("condition_json", Json.writeOrNull(normalizeCondition(tx, campaignId, spec.get("condition"))));
			long start = spec.get("start") == null ? now : cal.seqOf(spec.get("start"));
			Long end = spec.get("end") == null ? null : cal.seqOf(spec.get("end"));
			if (end != null && end < start) {
				throw RpgException.invalidArgument("end must not be before start.");
			}
			Long nextDue = schedule.get("kind").equals("ONCE") ? start : nextDue(schedule, cal, now, start, end);
			if (nextDue == null) {
				throw RpgException.invalidArgument("This schedule never falls due between start and end.");
			}
			cols.put("start_seq", start);
			cols.put("end_seq", end);
			cols.put("next_due_seq", nextDue);
			cols.put("active", 1L);
			cols.put("revision", 0L);
			long id = tx.insert(AccountService.CASH_FLOW, cols);
			Row flow = tx.get(AccountService.CASH_FLOW, id);
			Map<String, Object> view = view(tx, campaignId, flow, cal);
			long eventId = LedgerService.append(tx, campaignId, new LedgerService.EventSpec("CASH_FLOW_DEFINED",
					"Cash flow defined: " + name + " — " + view.get("summary") + ".", null, "MINOR", "PARTY_KNOWN",
					"GM", null, null, null, Map.of("cash_flow", Ref.of(AccountService.CASH_FLOW, id))));
			tx.update(AccountService.CASH_FLOW, id, Map.of("created_event_id", eventId));
			var result = new LinkedHashMap<String, Object>(view);
			result.put("event", Ref.of(Ref.EVENT, eventId));
			result.put("meta", Harness.meta(campaign, null));
			return result;
		});
	}

	// ── update_cash_flow ───────────────────────────────────────────────

	public Map<String, Object> update(
		String operationId, String campaignRef, String flowRef, Map<String, Object> changes) {
		long campaignId = Ref.id(campaignRef, Ref.CAMPAIGN);
		var args = new LinkedHashMap<String, Object>();
		args.put("campaign", campaignRef);
		args.put("cash_flow", flowRef);
		args.put("changes", changes);
		return db.mutate(Database.Mutation.of("update_cash_flow", campaignId, operationId, "GM", args), tx -> {
			Row campaign = Harness.requireMutation(tx, campaignRef, "update_cash_flow");
			Row flow = flow(tx, campaignId, flowRef);
			if (changes == null || changes.isEmpty()) {
				throw RpgException.invalidArgument("changes must name at least one of " + UPDATE_KEYS + ".");
			}
			for (String key : changes.keySet()) {
				if (!UPDATE_KEYS.contains(key)) {
					throw RpgException.invalidArgument("Unknown change '" + key + "'; allowed: " + UPDATE_KEYS + ".");
				}
			}
			Calendar cal = Calendar.forCampaign(campaign);
			long now = GameTime.currentSeq(tx, campaignId);
			String kind = flow.str("kind");
			var cols = new LinkedHashMap<String, Object>();
			if (changes.containsKey("name")) {
				String name = requireName(changes.get("name"));
				if (tx.queryOne("SELECT id FROM cash_flow WHERE campaign_id = ? AND name = ? AND id <> ?", campaignId,
						name, flow.id()).isPresent()) {
					throw RpgException.conflict("A cash flow named '" + name + "' already exists in this campaign.");
				}
				cols.put("name", name);
			}
			if (kind.equals("MONEY")) {
				MoneyRef from = changes.containsKey("from") ? MoneyRef.resolve(tx, campaignId, changes.get("from"))
						: MoneyRef.parse(flow.str("from_ref"));
				MoneyRef to = changes.containsKey("to") ? MoneyRef.resolve(tx, campaignId, changes.get("to"))
						: MoneyRef.parse(flow.str("to_ref"));
				if (from.isWorld() && to.isWorld()) {
					throw RpgException.invalidArgument("At least one of from/to must be an account or a character.");
				}
				if (from.equals(to)) {
					throw RpgException.invalidArgument("from and to are the same money bag.");
				}
				cols.put("from_ref", from.toString());
				cols.put("to_ref", to.toString());
				if (changes.containsKey("amount") || changes.containsKey("cap")) {
					Map<String, Object> amount = changes.containsKey("amount")
							? normalizeAmount(tx, campaignId, changes.get("amount")) : flow.map("amount_json");
					if (changes.containsKey("cap")) {
						amount.remove("total_cp");
						if (changes.get("cap") != null) {
							amount.put("total_cp", normalizeCap(changes.get("cap")));
						}
					} else if (flow.map("amount_json").get("total_cp") != null) {
						amount.put("total_cp", flow.map("amount_json").get("total_cp"));
					}
					cols.put("amount_json", Json.write(amount));
				}
			} else if (changes.containsKey("from") || changes.containsKey("to") || changes.containsKey("amount")
					|| changes.containsKey("cap")) {
				throw RpgException.invalidArgument("An EVENT cash flow moves no money: omit from, to and amount.");
			}
			if (changes.containsKey("description")) {
				cols.put("description",
						changes.get("description") == null ? null : changes.get("description").toString());
			}
			if (changes.containsKey("season")) {
				cols.put("season_json", Json.writeOrNull(normalizeSeason(changes.get("season"))));
			}
			if (changes.containsKey("condition")) {
				cols.put("condition_json",
						Json.writeOrNull(normalizeCondition(tx, campaignId, changes.get("condition"))));
			}
			Map<String, Object> schedule = changes.containsKey("schedule") ? normalizeSchedule(changes.get("schedule"))
					: flow.map("schedule_json");
			long start = changes.containsKey("start") ? cal.seqOf(changes.get("start")) : flow.lng("start_seq");
			Long end = changes.containsKey("end") ? (changes.get("end") == null ? null : cal.seqOf(changes.get("end")))
					: flow.lng("end_seq");
			if (end != null && end < start) {
				throw RpgException.invalidArgument("end must not be before start.");
			}
			boolean active = changes.containsKey("active") ? Boolean.parseBoolean(String.valueOf(changes.get("active")))
					: flow.bool("active");
			boolean reschedule = changes.containsKey("schedule") || changes.containsKey("start")
					|| changes.containsKey("end") || (active && !flow.bool("active"));
			cols.put("schedule_json", Json.write(schedule));
			cols.put("start_seq", start);
			cols.put("end_seq", end);
			if (reschedule && active) {
				Long nextDue = schedule.get("kind").equals("ONCE")
						? (start > now || flow.lng("next_due_seq") != null ? start : null)
						: nextDue(schedule, cal, now, start, end);
				if (nextDue == null) {
					throw RpgException.invalidArgument("This schedule never falls due again between now and end.");
				}
				cols.put("next_due_seq", nextDue);
			}
			cols.put("active", active ? 1L : 0L);
			cols.put("revision", flow.lng("revision") + 1);
			tx.update(AccountService.CASH_FLOW, flow.id(), cols);
			Row updated = tx.get(AccountService.CASH_FLOW, flow.id());
			Map<String, Object> view = view(tx, campaignId, updated, cal);
			long eventId = LedgerService.append(tx, campaignId,
					new LedgerService.EventSpec("CASH_FLOW_UPDATED",
							"Cash flow " + (active ? "updated" : "stopped") + ": " + updated.str("name") + " — "
									+ view.get("summary") + ".",
							null, "MINOR", "PARTY_KNOWN", "GM", null, null, null,
							Map.of("cash_flow", Ref.of(AccountService.CASH_FLOW, flow.id()), "changes",
									changes.keySet().stream().sorted().toList())));
			var result = new LinkedHashMap<String, Object>(view);
			result.put("event", Ref.of(Ref.EVENT, eventId));
			result.put("meta", Harness.meta(campaign, null));
			return result;
		});
	}

	// ── list_cash_flows ────────────────────────────────────────────────

	public Map<String, Object> list(String campaignRef, boolean includeInactive) {
		long campaignId = Ref.id(campaignRef, Ref.CAMPAIGN);
		return db.read(tx -> {
			Row campaign = Harness.campaign(tx, campaignRef);
			Calendar cal = Calendar.forCampaign(campaign);
			long now = GameTime.currentSeq(tx, campaignId);
			var flows = new ArrayList<Map<String, Object>>();
			for (Row f : tx.query(
					"SELECT * FROM cash_flow WHERE campaign_id = ?" + (includeInactive ? "" : " AND active = 1")
							+ " ORDER BY CASE WHEN next_due_seq IS NULL THEN 1 ELSE 0 END, next_due_seq, id",
					campaignId)) {
				flows.add(view(tx, campaignId, f, cal));
			}
			var result = new LinkedHashMap<String, Object>();
			result.put("game_time", GameTime.toMap(now, cal));
			result.put("cash_flows", flows);
			result.put("meta", Harness.meta(campaign, null));
			return result;
		});
	}

	// ── set_calendar ───────────────────────────────────────────────────

	public Map<String, Object> setCalendar(
		String operationId, String campaignRef, int year, int month, int day, boolean force) {
		long campaignId = Ref.id(campaignRef, Ref.CAMPAIGN);
		var args = new LinkedHashMap<String, Object>();
		args.put("campaign", campaignRef);
		args.put("year", year);
		args.put("month", month);
		args.put("day", day);
		args.put("force", force);
		return db.mutate(Database.Mutation.of("set_calendar", campaignId, operationId, "GM", args), tx -> {
			Row campaign = Harness.requireMutation(tx, campaignRef, "set_calendar");
			Calendar before = Calendar.forCampaign(campaign);
			boolean wasSet = campaign.str("calendar_json") != null;
			if (wasSet && !force) {
				throw RpgException.conflict("The calendar is already set (Day 1 = " + before.dateOf(0).get("display")
						+ "); pass force=true to move it.").withDetail("epoch", before.epoch());
			}
			Calendar cal = new Calendar(year, month, day);
			long now = GameTime.currentSeq(tx, campaignId);
			tx.update("campaign", campaignId,
					Map.of("calendar_json", cal.epochJson(), "revision", campaign.lng("revision") + 1));
			int rescheduled = 0;
			for (Row f : tx.query(
					"SELECT * FROM cash_flow WHERE campaign_id = ? AND active = 1 AND next_due_seq IS NOT NULL",
					campaignId)) {
				Map<String, Object> schedule = f.map("schedule_json");
				if (schedule.get("kind").equals("ONCE") || schedule.get("kind").equals("DAILY")) {
					continue;
				}
				Long next = nextDue(schedule, cal, now, f.lng("start_seq"), f.lng("end_seq"));
				var cols = new LinkedHashMap<String, Object>();
				cols.put("next_due_seq", next);
				if (next == null) {
					cols.put("active", 0L);
				}
				cols.put("revision", f.lng("revision") + 1);
				tx.update(AccountService.CASH_FLOW, f.id(), cols);
				rescheduled++;
			}
			long eventId = LedgerService.append(tx, campaignId,
					new LedgerService.EventSpec("CALENDAR_SET",
							"The calendar was " + (wasSet ? "moved" : "set") + ": Day 1 is "
									+ cal.dateOf(0).get("display") + "; today is " + cal.render(now) + ".",
							null, "MINOR", "GM_ONLY", "GM", null, null, null, Map.of("epoch", cal.epoch(), "previous",
									wasSet ? before.epoch() : Map.of(), "rescheduled", rescheduled)));
			var result = new LinkedHashMap<String, Object>();
			result.put("calendar", AccountService.calendarView(tx.get("campaign", campaignId), cal));
			result.put("game_time", GameTime.toMap(now, cal));
			result.put("rescheduled_cash_flows", rescheduled);
			result.put("event", Ref.of(Ref.EVENT, eventId));
			result.put("meta", Harness.meta(tx.get("campaign", campaignId), null));
			return result;
		});
	}

	// ── Lookup and views ───────────────────────────────────────────────

	public static Row flow(Tx tx, long campaignId, String refOrName) {
		if (refOrName == null || refOrName.isBlank()) {
			throw RpgException.invalidArgument("A cash-flow reference ('cash_flow:n') or name is required.");
		}
		Row f;
		if (refOrName.trim().toLowerCase().startsWith("cash_flow:")) {
			long id = Ref.id(refOrName.trim().toLowerCase(), AccountService.CASH_FLOW);
			f = tx.find(AccountService.CASH_FLOW, id)
					.orElseThrow(() -> RpgException.notFound("Cash flow " + refOrName));
		} else {
			f = tx.queryOne("SELECT * FROM cash_flow WHERE campaign_id = ? AND name = ?", campaignId, refOrName.trim())
					.orElseThrow(() -> RpgException.notFound("Cash flow '" + refOrName + "'"));
		}
		if (f.lng("campaign_id") != campaignId) {
			throw RpgException.invalidArgument(refOrName + " belongs to another campaign.");
		}
		return f;
	}

	public static Map<String, Object> view(Tx tx, long campaignId, Row f, Calendar cal) {
		var m = new LinkedHashMap<String, Object>();
		m.put("cash_flow", Ref.of(AccountService.CASH_FLOW, f.id()));
		m.put("name", f.str("name"));
		m.put("kind", f.str("kind"));
		Map<String, Object> schedule = f.map("schedule_json");
		String when = describeSchedule(schedule, cal, f.lng("start_seq"));
		if (f.str("kind").equals("MONEY")) {
			MoneyRef from = MoneyRef.parse(f.str("from_ref"));
			MoneyRef to = MoneyRef.parse(f.str("to_ref"));
			m.put("from", from.toString());
			m.put("from_name", from.name(tx, campaignId));
			m.put("to", to.toString());
			m.put("to_name", to.name(tx, campaignId));
			m.put("amount", amountView(tx, campaignId, f));
			if (f.map("amount_json").get("total_cp") instanceof Number cap) {
				long paid = Scheduler.paidSoFar(tx, f.id());
				m.put("cap", Money.render(cap.longValue()));
				m.put("paid", Money.render(paid));
				m.put("remaining", Money.render(Math.max(0, cap.longValue() - paid)));
			}
			m.put("summary", describeAmount(tx, campaignId, f) + " from " + from.name(tx, campaignId) + " to "
					+ to.name(tx, campaignId) + ", " + when);
		} else {
			m.put("summary", "event '" + f.str("description") + "', " + when);
		}
		m.put("schedule", schedule);
		m.put("schedule_display", when);
		m.put("season", f.str("season_json") == null ? null : f.map("season_json"));
		m.put("condition", f.str("condition_json") == null ? null : f.map("condition_json"));
		m.put("description", f.str("description"));
		m.put("start", cal.render(f.lng("start_seq")));
		m.put("end", f.lng("end_seq") == null ? null : cal.render(f.lng("end_seq")));
		m.put("next_due", f.lng("next_due_seq") == null ? null : GameTime.toMap(f.lng("next_due_seq"), cal));
		m.put("active", f.bool("active"));
		tx.queryOne("SELECT * FROM cash_flow_run WHERE cash_flow_id = ? ORDER BY due_seq DESC, id DESC LIMIT 1", f.id())
				.ifPresent(r -> m.put("last_run", AccountService.runView(r, cal)));
		m.put("revision", f.lng("revision"));
		return m;
	}

	static Map<String, Object> amountView(Tx tx, long campaignId, Row f) {
		Map<String, Object> amount = f.map("amount_json");
		var m = new LinkedHashMap<String, Object>(amount);
		if (amount.get("of_rule") instanceof Number n) {
			m.put("of_rule", Ref.of(AccountService.CASH_FLOW, n.longValue()));
			tx.find(AccountService.CASH_FLOW, n.longValue()).ifPresent(r -> m.put("of_rule_name", r.str("name")));
		}
		m.put("display", describeAmount(tx, campaignId, f));
		return m;
	}

	static String describeAmount(Tx tx, long campaignId, Row f) {
		if (!"MONEY".equals(f.str("kind"))) {
			return "no money";
		}
		Map<String, Object> amount = f.map("amount_json");
		String base;
		if (amount.get("fixed_cp") instanceof Number n) {
			base = Money.format(n.longValue());
		} else if (amount.get("of_rule") instanceof Number n) {
			String name = tx.find(AccountService.CASH_FLOW, n.longValue()).map(r -> r.str("name"))
					.orElse("cash_flow:" + n);
			base = percent(amount) + "% of '" + name + "'";
		} else {
			MoneyRef of = MoneyRef.parse(amount.get("of_inflows"));
			base = percent(amount) + "% of the inflows to " + of.name(tx, campaignId);
		}
		if (amount.get("total_cp") instanceof Number cap) {
			long remaining = Math.max(0, cap.longValue() - Scheduler.paidSoFar(tx, f.id()));
			base += " until " + Money.format(cap.longValue()) + " is paid (" + Money.format(remaining) + " remaining)";
		}
		if (f.str("season_json") != null) {
			Map<String, Object> season = f.map("season_json");
			var parts = new ArrayList<String>();
			for (String s : Calendar.SEASONS) {
				if (season.get(s) instanceof Number n && n.doubleValue() != 1.0) {
					parts.add(s.toLowerCase() + " ×" + n);
				}
			}
			if (!parts.isEmpty()) {
				base += " (" + String.join(", ", parts) + ")";
			}
		}
		return base;
	}

	private static String percent(Map<String, Object> amount) {
		double p = ((Number) amount.get("percent")).doubleValue();
		return p == Math.floor(p) ? String.valueOf((long) p) : String.valueOf(p);
	}

	// ── Normalisation ──────────────────────────────────────────────────

	private static String requireName(Object name) {
		if (name == null || name.toString().isBlank()) {
			throw RpgException.invalidArgument("A cash-flow name is required (e.g. 'Thursday market').");
		}
		return name.toString().trim();
	}

	/**
	 * Accepts money in any form (fixed), {@code {"fixed": money}} or
	 * {@code {"percent": p, "of_rule"|"of_inflows"}}.
	 */
	static Map<String, Object> normalizeAmount(Tx tx, long campaignId, Object amount) {
		if (amount == null) {
			throw RpgException.invalidArgument(
					"amount is required: money ('3 gp 4 sp', {\"gp\": 25}, cp), {\"percent\": 10, \"of_rule\": \"Thursday market\"} "
							+ "or {\"percent\": 10, \"of_inflows\": \"account:1\"}.");
		}
		var out = new LinkedHashMap<String, Object>();
		if (amount instanceof Map<?, ?> raw
				&& (raw.containsKey("percent") || raw.containsKey("fixed") || raw.containsKey("fixed_cp"))) {
			@SuppressWarnings("unchecked")
			Map<String, Object> m = (Map<String, Object>) raw;
			if (m.containsKey("percent")) {
				if (!(m.get("percent") instanceof Number p) || p.doubleValue() <= 0) {
					throw RpgException.invalidArgument("percent must be a positive number.");
				}
				out.put("percent", p.doubleValue());
				if (m.get("of_rule") != null) {
					Row rule = flow(tx, campaignId, m.get("of_rule").toString());
					if (!"MONEY".equals(rule.str("kind"))) {
						throw RpgException.invalidArgument("of_rule must name a MONEY cash flow.");
					}
					out.put("of_rule", rule.id());
				} else if (m.get("of_inflows") != null) {
					MoneyRef of = MoneyRef.resolve(tx, campaignId, m.get("of_inflows"));
					if (of.isWorld()) {
						throw RpgException.invalidArgument("of_inflows must be an account or a character, not WORLD.");
					}
					out.put("of_inflows", of.toString());
				} else {
					throw RpgException.invalidArgument(
							"A percent amount needs of_rule (another cash flow) or of_inflows (a money bag).");
				}
				return out;
			}
			long cp = Money.parseCp(m.containsKey("fixed_cp") ? m.get("fixed_cp") : m.get("fixed"));
			if (cp <= 0) {
				throw RpgException.invalidArgument("A fixed amount must be positive.");
			}
			out.put("fixed_cp", cp);
			return out;
		}
		long cp = Money.parseCp(amount);
		if (cp <= 0) {
			throw RpgException.invalidArgument("A fixed amount must be positive.");
		}
		out.put("fixed_cp", cp);
		return out;
	}

	/** A total the flow stops at: {@code {"total_cp": n}} or money in any form. */
	static long normalizeCap(Object cap) {
		Object value = cap instanceof Map<?, ?> m && m.containsKey("total_cp") ? m.get("total_cp") : cap;
		long cp = Money.parseCp(value);
		if (cp <= 0) {
			throw RpgException
					.invalidArgument("cap must be a positive total, e.g. '662 gp 5 sp' or {\"total_cp\": 66250}.");
		}
		return cp;
	}

	static Map<String, Object> normalizeSchedule(Object schedule) {
		Map<String, Object> m;
		if (schedule instanceof Map<?, ?> raw) {
			@SuppressWarnings("unchecked")
			Map<String, Object> cast = (Map<String, Object>) raw;
			m = cast;
		} else if (schedule != null) {
			m = Map.of("kind", schedule.toString());
		} else {
			throw RpgException.invalidArgument(
					"schedule is required: {\"kind\": ONCE|DAILY|WEEKLY|MONTHLY|YEARLY|SEASONAL, \"weekday\", \"day\", \"month\", \"season\", \"minute_of_day\"}.");
		}
		String kind = m.get("kind") == null ? null : m.get("kind").toString().trim().toUpperCase();
		if (kind == null || !SCHEDULES.contains(kind)) {
			throw RpgException.invalidArgument("schedule.kind must be one of " + SCHEDULES + ".");
		}
		var out = new LinkedHashMap<String, Object>();
		out.put("kind", kind);
		switch (kind) {
		case "WEEKLY" -> out.put("weekday", weekday(m.get("weekday")));
		case "MONTHLY" -> out.put("day", dayOfMonth(m.get("day"), 31));
		case "YEARLY" -> {
			int month = month(m.get("month"));
			out.put("month", month);
			out.put("day", dayOfMonth(m.get("day"), Calendar.daysInMonth(month)));
		}
		case "SEASONAL" ->
			out.put("season", Calendar.normalizeSeason(m.get("season") == null ? null : m.get("season").toString()));
		default -> {
		}
		}
		int minute = 0;
		if (m.get("minute_of_day") instanceof Number n) {
			minute = n.intValue();
		} else if (m.get("time") != null) {
			String[] hm = m.get("time").toString().trim().split(":");
			try {
				minute = Integer.parseInt(hm[0]) * 60 + (hm.length > 1 ? Integer.parseInt(hm[1]) : 0);
			} catch (NumberFormatException e) {
				throw RpgException.invalidArgument("schedule.time must look like '06:00'.");
			}
		}
		if (minute < 0 || minute >= GameTime.MINUTES_PER_DAY) {
			throw RpgException.invalidArgument("schedule.minute_of_day must be 0–1439.");
		}
		out.put("minute_of_day", minute);
		return out;
	}

	private static int weekday(Object v) {
		if (v instanceof Number n && n.intValue() >= 1 && n.intValue() <= 7) {
			return n.intValue();
		}
		if (v != null) {
			String s = v.toString().trim();
			for (int i = 0; i < Calendar.WEEKDAYS.size(); i++) {
				if (Calendar.WEEKDAYS.get(i).equalsIgnoreCase(s)) {
					return i + 1;
				}
			}
			if (s.matches("[1-7]")) {
				return Integer.parseInt(s);
			}
		}
		throw RpgException.invalidArgument("A WEEKLY schedule needs weekday 1–7 (Monday = 1) or a weekday name.");
	}

	private static int month(Object v) {
		if (v instanceof Number n && n.intValue() >= 1 && n.intValue() <= 12) {
			return n.intValue();
		}
		if (v != null) {
			String s = v.toString().trim();
			for (int i = 0; i < Calendar.MONTHS.size(); i++) {
				if (Calendar.MONTHS.get(i).equalsIgnoreCase(s)) {
					return i + 1;
				}
			}
			if (s.matches("1[0-2]|[1-9]")) {
				return Integer.parseInt(s);
			}
		}
		throw RpgException.invalidArgument("A YEARLY schedule needs month 1–12 or a month name.");
	}

	private static int dayOfMonth(Object v, int max) {
		int d = v instanceof Number n ? n.intValue()
				: v != null && v.toString().trim().matches("\\d+") ? Integer.parseInt(v.toString().trim()) : -1;
		if (d < 1 || d > max) {
			throw RpgException.invalidArgument("day must be 1–" + max + ".");
		}
		return d;
	}

	static Map<String, Object> normalizeSeason(Object season) {
		if (season == null) {
			return null;
		}
		if (!(season instanceof Map<?, ?> raw)) {
			throw RpgException.invalidArgument("season must be a map of multipliers, e.g. {\"WINTER\": 0.2}.");
		}
		var out = new LinkedHashMap<String, Object>();
		for (var e : raw.entrySet()) {
			String s = Calendar.normalizeSeason(String.valueOf(e.getKey()));
			if (!(e.getValue() instanceof Number n) || n.doubleValue() < 0) {
				throw RpgException.invalidArgument("Season multipliers must be non-negative numbers.");
			}
			out.put(s, n.doubleValue());
		}
		return out.isEmpty() ? null : out;
	}

	static Map<String, Object> normalizeCondition(Tx tx, long campaignId, Object condition) {
		if (condition == null) {
			return null;
		}
		if (!(condition instanceof Map<?, ?> raw) || raw.get("quest") == null) {
			throw RpgException.invalidArgument(
					"condition must be {\"quest\": \"quest:n\", \"status\": \"ACCEPTED\"} (the only condition kind so far).");
		}
		long questId = Ref.id(raw.get("quest").toString().trim().toLowerCase(), "quest");
		Row quest = tx.find("quest", questId).orElseThrow(() -> RpgException.notFound("Quest " + raw.get("quest")));
		if (quest.lng("campaign_id") != campaignId) {
			throw RpgException.invalidArgument(raw.get("quest") + " belongs to another campaign.");
		}
		String status = raw.get("status") == null ? "ACCEPTED" : raw.get("status").toString().trim().toUpperCase();
		if (!Set.of("OFFERED", "ACCEPTED", "COMPLETED", "FAILED", "ABANDONED").contains(status)) {
			throw RpgException.invalidArgument("condition.status must be a quest status.");
		}
		var out = new LinkedHashMap<String, Object>();
		out.put("quest", Ref.of("quest", questId));
		out.put("status", status);
		return out;
	}

	// ── Schedule math ──────────────────────────────────────────────────

	/**
	 * The first due point strictly after {@code afterSeq}, at or after {@code startSeq} and (when
	 * set) at or before {@code endSeq}; null when the schedule never falls due again. ONCE
	 * schedules are due at their start and never again.
	 */
	public static Long nextDue(Map<String, Object> schedule, Calendar cal, long afterSeq, long startSeq, Long endSeq) {
		String kind = String.valueOf(schedule.get("kind"));
		if (kind.equals("ONCE")) {
			return startSeq > afterSeq && (endSeq == null || startSeq <= endSeq) ? startSeq : null;
		}
		int minute = schedule.get("minute_of_day") instanceof Number n ? n.intValue() : 0;
		long firstDay = Math.floorDiv(Math.max(afterSeq + 1, startSeq), GameTime.MINUTES_PER_DAY);
		for (long day = firstDay; day <= firstDay + 2L * Calendar.DAYS_PER_YEAR + 31; day++) {
			long candidate = day * GameTime.MINUTES_PER_DAY + minute;
			if (candidate <= afterSeq || candidate < startSeq) {
				continue;
			}
			if (endSeq != null && candidate > endSeq) {
				return null;
			}
			Map<String, Object> date = cal.dateOfAbsoluteDay(cal.absoluteDayOf(candidate));
			if (matches(kind, schedule, date)) {
				return candidate;
			}
		}
		return null;
	}

	private static boolean matches(String kind, Map<String, Object> schedule, Map<String, Object> date) {
		int month = (Integer) date.get("month");
		int day = (Integer) date.get("day");
		return switch (kind) {
		case "DAILY" -> true;
		case "WEEKLY" -> ((Number) schedule.get("weekday")).intValue() == (Integer) date.get("weekday");
		case "MONTHLY" -> day == Math.min(((Number) schedule.get("day")).intValue(), Calendar.daysInMonth(month));
		case "YEARLY" -> month == ((Number) schedule.get("month")).intValue()
				&& day == Math.min(((Number) schedule.get("day")).intValue(), Calendar.daysInMonth(month));
		case "SEASONAL" -> day == 1 && month == Calendar.seasonStartMonth(String.valueOf(schedule.get("season")));
		default -> false;
		};
	}

	static String describeSchedule(Map<String, Object> schedule, Calendar cal, long startSeq) {
		String kind = String.valueOf(schedule.get("kind"));
		int minute = schedule.get("minute_of_day") instanceof Number n ? n.intValue() : 0;
		String at = String.format("%02d:%02d", minute / 60, minute % 60);
		return switch (kind) {
		case "ONCE" -> "once, on " + cal.render(startSeq);
		case "DAILY" -> "daily at " + at;
		case "WEEKLY" ->
			"every " + Calendar.WEEKDAYS.get(((Number) schedule.get("weekday")).intValue() - 1) + " at " + at;
		case "MONTHLY" -> "on day " + schedule.get("day") + " of every month at " + at;
		case "YEARLY" -> "every year on " + schedule.get("day") + " "
				+ Calendar.MONTHS.get(((Number) schedule.get("month")).intValue() - 1) + " at " + at;
		case "SEASONAL" ->
			"on the first day of every " + String.valueOf(schedule.get("season")).toLowerCase() + " at " + at;
		default -> kind;
		};
	}
}
