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

import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import io.quarkiverse.mcp.server.ToolResponse;
import jakarta.inject.Inject;

import java.util.Map;
import java.util.Optional;

/**
 * The economy tool surface (MCP_PROTOCOL.md §14.6–14.8): the calendar, accounts (treasuries that
 * are not a character's purse) and cash-flow rules the scheduler fires as the clock moves. Same
 * envelope and error conventions as {@link RpgTools}.
 */
public class EconomyTools {

	private static final String REF = "Campaign reference, e.g. 'campaign:1'";
	private static final String OP = "Client-generated idempotency key, unique within the campaign (e.g. a ULID). "
			+ "Repeating a call with the same operation_id and arguments returns the original result without re-applying it.";
	private static final String MONEY_REF = "'account:n', 'character:n', an account name, or 'WORLD' (the bottomless outside world)";

	@Inject
	Engine engine;

	@Tool(name = "set_calendar", description = "MUTATING, audited. Fixes the campaign calendar: which date campaign Day 1 falls on, in a 365-day year of twelve months "
			+ "(no leap years), seven-day weeks (Monday = 1; 1 January of year 1 is a Monday) and four seasons (spring Mar–May, summer Jun–Aug, "
			+ "autumn Sep–Nov, winter Dec–Feb). 'Day N' stays the primary clock; dates, weekdays and seasons are derived from it and drive "
			+ "WEEKLY/MONTHLY/YEARLY/SEASONAL cash flows and season multipliers. Without a calendar Day 1 is 1 March of year 1. "
			+ "Refuses to move an already-set epoch unless force is true (every WEEKLY/SEASONAL rule shifts with it; their next due points are recomputed).", annotations = @Tool.Annotations(destructiveHint = false, openWorldHint = false))
	ToolResponse setCalendar(@ToolArg(description = OP)
	String operation_id, @ToolArg(description = REF)
	String campaign, @ToolArg(description = "Year of campaign Day 1 (1 or later)")
	int year, @ToolArg(description = "Month of campaign Day 1 (1–12)")
	int month, @ToolArg(description = "Day of the month of campaign Day 1")
	int day, @ToolArg(description = "true to replace an epoch that is already set")
	Optional<Boolean> force) {
		return ToolSupport.run("set_calendar",
				() -> engine.cashFlows().setCalendar(operation_id, campaign, year, month, day, force.orElse(false)));
	}

	@Tool(name = "create_account", description = "MUTATING. Opens a money bag that is not a character's purse: an estate or faction treasury ('The House of Greystone'). "
			+ "Balances are coin like purses; an opening balance is a recorded GM grant from WORLD. Cash flows and transfer_money name it as 'account:n' or by name.", annotations = @Tool.Annotations(destructiveHint = false, openWorldHint = false))
	ToolResponse createAccount(@ToolArg(description = OP)
	String operation_id, @ToolArg(description = REF)
	String campaign, @ToolArg(description = "Unique account name, e.g. 'The House of Greystone'")
	String name, @ToolArg(description = "FACTION, ESTATE, CHARACTER or OTHER (default OTHER)")
	Optional<String> owner_kind,
		@ToolArg(description = "Owner reference matching owner_kind: 'faction:n', 'location:n' (ESTATE) or 'character:n'")
		Optional<String> owner,
		@ToolArg(description = "Opening balance: '25 gp', {\"gp\": 25} or an integer in copper (default none)")
		Optional<String> money, @ToolArg(description = "Free-text notes: what the treasury is for, who may draw on it")
		Optional<String> notes) {
		return ToolSupport.run("create_account", () -> engine.accounts().create(operation_id, campaign, name,
				owner_kind.orElse(null), owner.orElse(null), money.orElse(null), notes.orElse(null)));
	}

	@Tool(name = "transfer_money", description = "MUTATING, atomic. Moves coin between any two money bags — a character's purse, an account, or WORLD "
			+ "(coin leaving the campaign: a lease paid to a distant lord; or entering it as an audited GM grant). One MONEY_FLOW ledger event; "
			+ "the source must hold the amount. Between two characters prefer give_money.", annotations = @Tool.Annotations(destructiveHint = false, openWorldHint = false))
	ToolResponse transferMoney(@ToolArg(description = OP)
	String operation_id, @ToolArg(description = REF)
	String campaign, @ToolArg(description = "Source: " + MONEY_REF)
	String from, @ToolArg(description = "Destination: " + MONEY_REF)
	String to, @ToolArg(description = "Amount: '1 gp', '2 sp 5 cp', {\"gp\": 15} or an integer in copper")
	Object money, @ToolArg(description = "Why (recorded on the event)")
	Optional<String> reason) {
		return ToolSupport.run("transfer_money",
				() -> engine.accounts().transfer(operation_id, campaign, from, to, money, reason.orElse(null)));
	}

	@Tool(name = "get_accounts", description = "Read-only, always allowed. Every account with its balance and upcoming cash flows, the party's purses, "
			+ "the calendar, and the most recent cash-flow runs (what was paid, unpaid or skipped, and when).", annotations = @Tool.Annotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false))
	ToolResponse getAccounts(@ToolArg(description = REF)
	String campaign, @ToolArg(description = "How many recent runs to include (default 10, max 100)")
	Optional<Integer> last_runs) {
		return ToolSupport.run("get_accounts", () -> engine.accounts().accounts(campaign, last_runs.orElse(null)));
	}

	@Tool(name = "define_cash_flow", description = "MUTATING. Defines a rule the engine fires by itself whenever the clock crosses its due point (advance_time, "
			+ "move_party, perform_rest): a recurring or one-off transfer between money bags, or a dated event. `spec` keys: "
			+ "name (unique); kind MONEY (default) or EVENT; from/to (" + MONEY_REF
			+ "); amount — money ('3 gp 4 sp', {\"gp\": 25}, cp), "
			+ "{\"percent\": 10, \"of_rule\": <cash flow name or 'cash_flow:n'>} (a share of that rule's payout at the same due time) or "
			+ "{\"percent\": 10, \"of_inflows\": 'account:n'|'character:n'} (a share of all coin that bag received since this rule last ran, or since the rule was written for its first run); "
			+ "cap — an optional total ('662 gp 5 sp' or {\"total_cp\": n}) for a debt: the flow pays at most what is still owed and "
			+ "finishes itself once the total is cleared (UNPAID months leave the debt untouched); "
			+ "schedule {kind: ONCE|DAILY|WEEKLY|MONTHLY|YEARLY|SEASONAL, weekday 1–7 or name, day 1–31, month 1–12 or name, season, "
			+ "minute_of_day 0–1439 or time 'HH:MM'} (ONCE fires at start); season {SPRING|SUMMER|AUTUMN|WINTER: multiplier} applied "
			+ "by the season of the due date (e.g. {\"WINTER\": 0.2}); condition {quest: 'quest:n', status} — the run is SKIPPED unless the quest "
			+ "has that status; description (required for EVENT: the event's text, written as a WORLD_EVENT when it fires); start and end as "
			+ "'Day N, HH:MM' or {year, month, day, minute_of_day} (start defaults to now). Paid runs write MONEY_FLOW ledger events; an "
			+ "insufficient source writes an UNPAID one for you to play out. The rule appears in the consequences of every clock-moving call.", annotations = @Tool.Annotations(destructiveHint = false, openWorldHint = false))
	ToolResponse defineCashFlow(@ToolArg(description = OP)
	String operation_id, @ToolArg(description = REF)
	String campaign,
		@ToolArg(description = "The rule: {name, kind, from, to, amount, schedule, season, condition, description, start, end}")
		Map<String, Object> spec) {
		return ToolSupport.run("define_cash_flow", () -> engine.cashFlows().define(operation_id, campaign, spec));
	}

	@Tool(name = "update_cash_flow", description = "MUTATING. Changes a cash flow: any of name, from, to, amount, cap (null removes it), schedule, season, condition, description, start, end, "
			+ "active (false stops it, true restarts it). Changing the schedule, start or end recomputes the next due point.", annotations = @Tool.Annotations(destructiveHint = false, openWorldHint = false))
	ToolResponse updateCashFlow(@ToolArg(description = OP)
	String operation_id, @ToolArg(description = REF)
	String campaign, @ToolArg(description = "'cash_flow:n' or the rule's name")
	String cash_flow, @ToolArg(description = "Fields to change (same shapes as define_cash_flow)")
	Map<String, Object> changes) {
		return ToolSupport.run("update_cash_flow",
				() -> engine.cashFlows().update(operation_id, campaign, cash_flow, changes));
	}

	@Tool(name = "list_cash_flows", description = "Read-only, always allowed. Every cash-flow rule with its amount, schedule, next due date and last run, "
			+ "soonest due first.", annotations = @Tool.Annotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false))
	ToolResponse listCashFlows(@ToolArg(description = REF)
	String campaign, @ToolArg(description = "Include stopped and finished rules (default false)")
	Optional<Boolean> include_inactive) {
		return ToolSupport.run("list_cash_flows",
				() -> engine.cashFlows().list(campaign, include_inactive.orElse(false)));
	}
}
