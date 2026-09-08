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

import se.hirt.mcp.rpg.ledger.LedgerService;
import se.hirt.mcp.rpg.persistence.Row;
import se.hirt.mcp.rpg.persistence.Tx;
import se.hirt.mcp.rpg.protocol.Json;
import se.hirt.mcp.rpg.protocol.Ref;
import se.hirt.mcp.rpg.protocol.RpgException;
import se.hirt.mcp.rpg.rules.Money;
import se.hirt.mcp.rpg.session.Calendar;
import se.hirt.mcp.rpg.session.GameTime;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Fires cash flows whose due point the clock has crossed (MCP_PROTOCOL.md §14.7). Called by every operation that
 * moves the clock — {@code advance_time}, {@code move_party}, {@code perform_rest} — after the clock is updated.
 * <p>
 * Due flows are processed one due point at a time, earliest first; within one due point fixed amounts go first, then
 * shares of another rule's payout, then shares of a bag's inflows, so a stipend that is "a tenth of the House's
 * takings this week" sees the market money that arrived the same morning. Each firing inserts a {@code cash_flow_run},
 * writes a ledger event dated at the due point (not at the end of the advance) and moves the rule's next due point
 * forward, so the loop always terminates.
 */
public final class Scheduler {

	private Scheduler() {
	}

	/** Returns one consequence map per run (PAID, UNPAID, SKIPPED or FIRED). */
	public static List<Map<String, Object>> onClockAdvance(Tx tx, long campaignId, long fromSeq, long toSeq) {
		var runs = new ArrayList<Map<String, Object>>();
		if (toSeq <= fromSeq) {
			return runs;
		}
		if (tx.count("SELECT COUNT(*) FROM cash_flow WHERE campaign_id = ? AND active = 1 AND next_due_seq IS NOT NULL AND next_due_seq <= ?",
				campaignId, toSeq) == 0) {
			return runs;
		}
		Calendar cal = Calendar.forCampaign(tx, campaignId);
		int guard = 0;
		while (true) {
			List<Row> due = tx.query(
					"SELECT * FROM cash_flow WHERE campaign_id = ? AND active = 1 AND next_due_seq IS NOT NULL AND next_due_seq <= ? " + "ORDER BY next_due_seq, id",
					campaignId, toSeq);
			if (due.isEmpty()) {
				return runs;
			}
			long dueSeq = due.get(0).lng("next_due_seq");
			var tick = new ArrayList<Row>();
			for (Row f : due) {
				if (f.lng("next_due_seq") == dueSeq) {
					tick.add(f);
				}
			}
			tick.sort((a, b) -> {
				int ra = rank(tx, a, new HashSet<>());
				int rb = rank(tx, b, new HashSet<>());
				return ra != rb ? Integer.compare(ra, rb) : Long.compare(a.id(), b.id());
			});
			for (Row f : tick) {
				runs.add(fire(tx, campaignId, cal, f, dueSeq));
			}
			if (++guard > 20_000) {
				throw RpgException.internal("Cash-flow scheduler did not converge for campaign " + campaignId, null);
			}
		}
	}

	private static int rank(Tx tx, Row flow, Set<Long> seen) {
		if (!"MONEY".equals(flow.str("kind"))) {
			return 0;
		}
		Map<String, Object> amount = flow.map("amount_json");
		if (amount.get("of_inflows") != null) {
			return 100;
		}
		if (amount.get("of_rule") instanceof Number n) {
			if (!seen.add(flow.id())) {
				return 1;
			}
			return 1 + tx.find(AccountService.CASH_FLOW, n.longValue()).map(r -> rank(tx, r, seen)).orElse(0);
		}
		return 0;
	}

	private static Map<String, Object> fire(Tx tx, long campaignId, Calendar cal, Row flow, long dueSeq) {
		String name = flow.str("name");
		Map<String, Object> date = cal.dateOf(dueSeq);
		String when = GameTime.render(dueSeq) + " (" + date.get("display") + ")";
		var consequence = new LinkedHashMap<String, Object>();
		consequence.put("type", "CASH_FLOW");
		consequence.put("cash_flow", Ref.of(AccountService.CASH_FLOW, flow.id()));
		consequence.put("name", name);
		consequence.put("kind", flow.str("kind"));
		consequence.put("due", when);

		String status;
		long amountCp = 0;
		boolean cleared = false;
		Long capCp = null;
		String note = null;
		Long eventId = null;
		var payload = new LinkedHashMap<String, Object>();
		payload.put("cash_flow", Ref.of(AccountService.CASH_FLOW, flow.id()));
		payload.put("name", name);
		payload.put("due", GameTime.render(dueSeq));
		payload.put("date", date.get("display"));

		String conditionNote = conditionBlocks(tx, flow);
		if (conditionNote != null) {
			status = "SKIPPED";
			note = conditionNote;
		} else if ("EVENT".equals(flow.str("kind"))) {
			status = "FIRED";
			payload.put("status", status);
			eventId = LedgerService.append(tx, campaignId,
					new LedgerService.EventSpec("WORLD_EVENT", flow.str("description"), null, "NOTABLE", "PARTY_KNOWN",
							"GM", dueSeq, null, null, payload));
			consequence.put("description", flow.str("description"));
		} else {
			MoneyRef from = MoneyRef.parse(flow.str("from_ref"));
			MoneyRef to = MoneyRef.parse(flow.str("to_ref"));
			String fromName = from.name(tx, campaignId);
			String toName = to.name(tx, campaignId);
			consequence.put("from", from.toString());
			consequence.put("to", to.toString());
			payload.put("from", from.toString());
			payload.put("to", to.toString());
			Amount computed = amount(tx, campaignId, cal, flow, dueSeq, date);
			amountCp = computed.cp();
			// A capped flow (a debt) pays at most what is still owed and finishes itself once cleared.
			capCp = flow.map("amount_json").get("total_cp") instanceof Number c ? c.longValue() : null;
			long paidBefore = capCp == null ? 0 : paidSoFar(tx, flow.id());
			long remaining = capCp == null ? Long.MAX_VALUE : Math.max(0, capCp - paidBefore);
			if (capCp != null) {
				amountCp = Math.min(amountCp, remaining);
				payload.put("cap_cp", capCp);
				payload.put("paid_before_cp", paidBefore);
				consequence.put("cap", Money.render(capCp));
			}
			payload.put("money_cp", amountCp);
			payload.put("basis", computed.basis());
			if (capCp != null && remaining <= 0) {
				status = "SKIPPED";
				note = "cleared: " + Money.format(paidBefore) + " of " + Money.format(capCp) + " already paid";
				cleared = true;
			} else if (amountCp <= 0) {
				status = "SKIPPED";
				note = computed.basis() + ": nothing to pay";
			} else {
				long have = from.balance(tx, campaignId);
				if (have < amountCp) {
					status = "UNPAID";
					note = fromName + " holds " + Money.format(have) + ", " + Money.format(amountCp) + " was due";
					payload.put("status", status);
					payload.put("held_cp", have);
					eventId = AccountService.moneyFlowEvent(tx, campaignId, from, to, amountCp,
							"Unpaid: " + name + " — " + Money.format(amountCp) + " due from " + fromName + " to " + toName
									+ ", but " + fromName + " holds only " + Money.format(have) + ".", "NOTABLE", dueSeq,
							null, payload);
				} else {
					status = "PAID";
					from.adjust(tx, campaignId, -amountCp);
					to.adjust(tx, campaignId, amountCp);
					payload.put("status", status);
					String summary = name + ": " + Money.format(amountCp) + (from.isWorld() ? " to " + toName
							: to.isWorld() ? " paid out of " + fromName : " from " + fromName + " to " + toName) + " ("
							+ date.get("display") + ")";
					if (capCp != null) {
						long paidNow = paidBefore + amountCp;
						payload.put("paid_cp", paidNow);
						payload.put("remaining_cp", Math.max(0, capCp - paidNow));
						if (paidNow >= capCp) {
							cleared = true;
							note = "cleared: " + Money.format(paidNow) + " of " + Money.format(capCp) + " paid";
							summary += "; cleared: " + Money.format(paidNow) + " of " + Money.format(capCp) + " paid";
						} else {
							summary += "; " + Money.format(capCp - paidNow) + " of " + Money.format(capCp) + " still owed";
						}
					}
					summary += ".";
					eventId = AccountService.moneyFlowEvent(tx, campaignId, from, to, amountCp,
							summary, amountCp >= 10 * Money.GP ? "NOTABLE" : "MINOR", dueSeq, null, payload);
				}
			}
			consequence.put("amount", Money.render(amountCp));
			consequence.put("basis", computed.basis());
		}
		var run = new LinkedHashMap<String, Object>();
		run.put("cash_flow_id", flow.id());
		run.put("due_seq", dueSeq);
		run.put("amount_cp", amountCp);
		run.put("status", status);
		run.put("event_id", eventId);
		run.put("note", note);
		long runId = tx.insert("cash_flow_run", run);
		consequence.put("run", runId);
		consequence.put("status", status);
		if (capCp != null) {
			consequence.put("remaining", Money.render(Math.max(0, capCp - paidSoFar(tx, flow.id()))));
		}
		consequence.put("event", Ref.ofNullable(Ref.EVENT, eventId));
		if (note != null) {
			consequence.put("note", note);
		}

		// Advance the rule; a cleared debt is finished.
		Long next = cleared ? null : CashFlowService.nextDue(flow.map("schedule_json"), cal, dueSeq,
				flow.lng("start_seq"), flow.lng("end_seq"));
		var cols = new LinkedHashMap<String, Object>();
		cols.put("next_due_seq", next);
		if (next == null) {
			cols.put("active", 0L);
			consequence.put("finished", true);
		} else {
			consequence.put("next_due", cal.render(next));
		}
		cols.put("revision", flow.lng("revision") + 1);
		tx.update(AccountService.CASH_FLOW, flow.id(), cols);
		return consequence;
	}

	/** The sum of everything a flow has paid so far (PAID runs only; UNPAID and SKIPPED runs owe nothing). */
	static long paidSoFar(Tx tx, long flowId) {
		return tx.queryOne(
				"SELECT COALESCE(SUM(amount_cp), 0) AS s FROM cash_flow_run WHERE cash_flow_id = ? AND status = 'PAID'",
				flowId).map(r -> r.lng("s")).orElse(0L);
	}

	private static String conditionBlocks(Tx tx, Row flow) {
		if (flow.str("condition_json") == null) {
			return null;
		}
		Map<String, Object> condition = flow.map("condition_json");
		if (condition.get("quest") == null) {
			return null;
		}
		long questId = Ref.id(String.valueOf(condition.get("quest")), "quest");
		String required = String.valueOf(condition.get("status"));
		String actual = tx.find("quest", questId).map(q -> q.str("status")).orElse("MISSING");
		return required.equals(actual) ? null
				: "condition not met: " + condition.get("quest") + " is " + actual + ", not " + required;
	}

	private record Amount(long cp, String basis) {
	}

	private static Amount amount(Tx tx, long campaignId, Calendar cal, Row flow, long dueSeq, Map<String, Object> date) {
		Map<String, Object> amount = flow.map("amount_json");
		double multiplier = 1.0;
		String season = String.valueOf(date.get("season"));
		if (flow.str("season_json") != null && flow.map("season_json").get(season) instanceof Number n) {
			multiplier = n.doubleValue();
		}
		String seasonNote = multiplier == 1.0 ? "" : " × " + multiplier + " (" + season.toLowerCase() + ")";
		if (amount.get("fixed_cp") instanceof Number n) {
			long cp = (long) Math.floor(n.longValue() * multiplier);
			return new Amount(cp, Money.format(n.longValue()) + seasonNote);
		}
		double percent = ((Number) amount.get("percent")).doubleValue();
		if (amount.get("of_rule") instanceof Number n) {
			Row paid = tx.queryOne(
					"SELECT * FROM cash_flow_run WHERE cash_flow_id = ? AND due_seq = ? AND status = 'PAID'",
					n.longValue(), dueSeq).orElse(null);
			String ruleName = tx.find(AccountService.CASH_FLOW, n.longValue()).map(r -> r.str("name"))
					.orElse("cash_flow:" + n);
			if (paid == null) {
				return new Amount(0, "'" + ruleName + "' paid nothing at this due time");
			}
			long base = paid.lng("amount_cp");
			long cp = (long) Math.floor(base * percent / 100.0 * multiplier);
			return new Amount(cp, pct(percent) + "% of " + Money.format(base) + " ('" + ruleName + "')" + seasonNote);
		}
		MoneyRef of = MoneyRef.parse(amount.get("of_inflows"));
		Long lastRun = tx.queryOne("SELECT MAX(due_seq) AS s FROM cash_flow_run WHERE cash_flow_id = ?", flow.id())
				.map(r -> r.lng("s")).orElse(null);
		long previous = lastRun == null ? flow.lng("start_seq") - 1 : lastRun;
		// The first run counts only what arrived after the rule itself was written: an opening balance granted in the
		// same game minute the rule was defined is capital, not that week's income.
		long afterEventId = lastRun == null && !flow.isNull("created_event_id") ? flow.lng("created_event_id") : 0;
		long inflows = inflows(tx, campaignId, of, previous, dueSeq, afterEventId);
		long cp = (long) Math.floor(inflows * percent / 100.0 * multiplier);
		return new Amount(cp, pct(percent) + "% of " + Money.format(inflows) + " received by " + of.name(tx, campaignId)
				+ " since " + GameTime.render(Math.max(0, previous)) + seasonNote);
	}

	private static String pct(double p) {
		return p == Math.floor(p) ? String.valueOf((long) p) : String.valueOf(p);
	}

	/** Coin that arrived in a bag in {@code (fromSeq, toSeq]}: MONEY_FLOW and MONEY_GIVEN to it, loot it acquired. */
	static long inflows(Tx tx, long campaignId, MoneyRef ref, long fromSeq, long toSeq) {
		return inflows(tx, campaignId, ref, fromSeq, toSeq, 0);
	}

	/** As above, ignoring events written before {@code afterEventId} (the rule's own creation event on its first run). */
	static long inflows(Tx tx, long campaignId, MoneyRef ref, long fromSeq, long toSeq, long afterEventId) {
		long total = 0;
		String target = ref.toString();
		for (Row e : tx.query(
				"SELECT payload_json FROM event WHERE campaign_id = ? AND type IN ('MONEY_FLOW','MONEY_GIVEN') AND fictional_seq > ? AND fictional_seq <= ? AND id > ?",
				campaignId, fromSeq, toSeq, afterEventId)) {
			Map<String, Object> p = Json.readMap(e.str("payload_json"));
			if (target.equalsIgnoreCase(String.valueOf(p.get("to"))) && p.get("money_cp") instanceof Number n
					&& !"UNPAID".equals(p.get("status"))) {
				total += n.longValue();
			}
		}
		if (ref.kind() == MoneyRef.Kind.CHARACTER) {
			for (Row e : tx.query(
					"SELECT e.payload_json FROM event e JOIN event_actor a ON a.event_id = e.id WHERE e.campaign_id = ? AND e.type = 'LOOT_ACQUIRED' " + "AND a.character_id = ? AND e.fictional_seq > ? AND e.fictional_seq <= ?",
					campaignId, ref.id(), fromSeq, toSeq)) {
				Map<String, Object> p = Json.readMap(e.str("payload_json"));
				if (p.get("money_cp") instanceof Number n) {
					total += n.longValue();
				}
			}
		}
		return total;
	}
}
