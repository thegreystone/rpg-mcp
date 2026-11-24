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

import java.util.List;
import java.util.Map;

/**
 * Consolidated estate economy, calendar and scheduled cash-flow tool surface.
 */
public class EconomyTools {

	@Inject
	Engine engine;

	@Tool(name = "economy", description = "Campaign calendar, non-purse accounts and clock-driven cash flows (see get_instructions topic ECONOMY). Actions: "
			+ "SET_CALENDAR (mut) {year, month, day, force?}; "
			+ "CREATE_ACCOUNT (mut) {name, owner_kind?: FACTION|ESTATE|CHARACTER|OTHER, owner?, money?, notes?}; "
			+ "TRANSFER_MONEY (mut) {from, to, money, reason?}; " + "GET_ACCOUNTS (read) {last_runs?}; "
			+ "DEFINE_CASH_FLOW (mut) {spec: {name, kind?: MONEY|EVENT, from?, to?, amount?, cap?, schedule, season?, condition?, description?, start?, end?}}; "
			+ "UPDATE_CASH_FLOW (mut) {cash_flow, changes}; "
			+ "LIST_CASH_FLOWS (read) {include_inactive?}.", annotations = @Tool.Annotations(destructiveHint = false, openWorldHint = false))
	ToolResponse economy(
		@ToolArg(description = "SET_CALENDAR | CREATE_ACCOUNT | TRANSFER_MONEY | GET_ACCOUNTS | DEFINE_CASH_FLOW | UPDATE_CASH_FLOW | LIST_CASH_FLOWS")
		String action, @ToolArg(description = "campaign:N")
		String campaign, @ToolArg(description = "Idempotency key (required for mutating actions)", required = false)
		String operation_id, @ToolArg(description = "Action parameters (see description)", required = false)
		Map<String, Object> params) {
		return ToolSupport.run("economy", () -> {
			String act = ToolArgs.action("economy", action, List.of("SET_CALENDAR", "CREATE_ACCOUNT", "TRANSFER_MONEY",
					"GET_ACCOUNTS", "DEFINE_CASH_FLOW", "UPDATE_CASH_FLOW", "LIST_CASH_FLOWS"));
			String c = ToolArgs.reqCampaign("economy", act, campaign);
			return switch (act) {
			case "SET_CALENDAR" -> {
				String op = ToolArgs.reqOp("economy", act, operation_id);
				ToolArgs a = ToolArgs.of("economy", act, "ECONOMY", params, "year, month, day, force?", "year", "month",
						"day", "force");
				yield engine.cashFlows().setCalendar(op, c, a.reqInt("year"), a.reqInt("month"), a.reqInt("day"),
						a.optBool("force", false));
			}
			case "CREATE_ACCOUNT" -> {
				String op = ToolArgs.reqOp("economy", act, operation_id);
				ToolArgs a = ToolArgs.of("economy", act, "ECONOMY", params, "name, owner_kind?, owner?, money?, notes?",
						"name", "owner_kind", "owner", "money", "notes");
				yield engine.accounts().create(op, c, a.reqStr("name"), a.optStr("owner_kind"), a.optStr("owner"),
						a.optStr("money"), a.optStr("notes"));
			}
			case "TRANSFER_MONEY" -> {
				String op = ToolArgs.reqOp("economy", act, operation_id);
				ToolArgs a = ToolArgs.of("economy", act, "ECONOMY", params, "from, to, money, reason?", "from", "to",
						"money", "reason");
				yield engine.accounts().transfer(op, c, a.reqStr("from"), a.reqStr("to"), a.reqObj("money"),
						a.optStr("reason"));
			}
			case "GET_ACCOUNTS" -> {
				ToolArgs a = ToolArgs.of("economy", act, "ECONOMY", params, "last_runs?", "last_runs");
				yield engine.accounts().accounts(c, a.optInt("last_runs"));
			}
			case "DEFINE_CASH_FLOW" -> {
				String op = ToolArgs.reqOp("economy", act, operation_id);
				ToolArgs a = ToolArgs.of("economy", act, "ECONOMY", params, "spec", "spec");
				yield engine.cashFlows().define(op, c, a.reqMap("spec"));
			}
			case "UPDATE_CASH_FLOW" -> {
				String op = ToolArgs.reqOp("economy", act, operation_id);
				ToolArgs a = ToolArgs.of("economy", act, "ECONOMY", params, "cash_flow, changes", "cash_flow",
						"changes");
				yield engine.cashFlows().update(op, c, a.reqStr("cash_flow"), a.reqMap("changes"));
			}
			case "LIST_CASH_FLOWS" -> {
				ToolArgs a = ToolArgs.of("economy", act, "ECONOMY", params, "include_inactive?", "include_inactive");
				yield engine.cashFlows().list(c, a.optBool("include_inactive", false));
			}
			default -> throw new IllegalStateException(act);
			};
		});
	}
}
