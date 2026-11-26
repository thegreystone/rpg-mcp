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
import se.hirt.mcp.rpg.world.PatrolService;
import se.hirt.mcp.rpg.world.TravelJourneyService;
import se.hirt.mcp.rpg.world.WorldTimeline;

import java.util.List;
import java.util.Map;

/** Consolidated world locations, interruptible travel, scheduled events and finite patrol tools. */
public class LivingWorldTools {

	@Inject
	Engine engine;

	@Tool(name = "world_travel", description = "Persistent locations, party travel, interruptible journeys, travel risk and scheduled world events (see get_instructions topic ECONOMY). Actions: "
			+ "MATERIALIZE_LOCATION (mut) {location?, spec?}; "
			+ "MOVE_PARTY (mut) {to, characters?, authorized_route?, travel_minutes?, reason?}; "
			+ "TRAVEL_STATE (read) {}; " + "CONTINUE_JOURNEY (mut) {seconds}; "
			+ "RESOLVE_OPPORTUNITY (mut) {resolution: RESOLVED|AVOIDED|NEGOTIATED|CONTINUE}; "
			+ "DEFINE_TRAVEL_RISK (mut) {scope: location:N|connection:N|DEFAULT, policy}; "
			+ "SCHEDULE_EVENT (mut) {spec: {kind, payload, after_seconds|due_seconds, interrupting?, visibility?, region_ref?}}.", annotations = @Tool.Annotations(destructiveHint = false, openWorldHint = false))
	ToolResponse worldTravel(
		@ToolArg(description = "MATERIALIZE_LOCATION | MOVE_PARTY | TRAVEL_STATE | CONTINUE_JOURNEY | RESOLVE_OPPORTUNITY | DEFINE_TRAVEL_RISK | SCHEDULE_EVENT")
		String action, @ToolArg(description = "campaign:N")
		String campaign, @ToolArg(description = "Idempotency key (required for mutating actions)", required = false)
		String operation_id, @ToolArg(description = "Action parameters (see description)", required = false)
		Map<String, Object> params) {
		return ToolSupport.run("world_travel", () -> {
			String act = ToolArgs.action("world_travel", action, List.of("MATERIALIZE_LOCATION", "MOVE_PARTY",
					"TRAVEL_STATE", "CONTINUE_JOURNEY", "RESOLVE_OPPORTUNITY", "DEFINE_TRAVEL_RISK", "SCHEDULE_EVENT"));
			String c = ToolArgs.reqCampaign("world_travel", act, campaign);
			return switch (act) {
			case "MATERIALIZE_LOCATION" -> {
				String op = ToolArgs.reqOp("world_travel", act, operation_id);
				ToolArgs a = ToolArgs.of("world_travel", act, "MAPS", params, "location?, spec?", "location", "spec");
				yield engine.world().materialize(op, c, a.optStr("location"), a.optMap("spec"), "GM");
			}
			case "MOVE_PARTY" -> {
				String op = ToolArgs.reqOp("world_travel", act, operation_id);
				ToolArgs a = ToolArgs.of("world_travel", act, "ECONOMY", params,
						"to, characters?, authorized_route?, travel_minutes?, reason?", "to", "characters",
						"authorized_route", "travel_minutes", "reason");
				yield engine.world().move(op, c, a.reqStr("to"), a.optStringList("characters"),
						a.optBool("authorized_route", false), a.optInt("travel_minutes"), a.optStr("reason"));
			}
			case "TRAVEL_STATE" -> {
				ToolArgs.of("world_travel", act, "ECONOMY", params, "(none)");
				yield new TravelJourneyService(engine.db(), engine.rules(), engine.roller()).state(c);
			}
			case "CONTINUE_JOURNEY" -> {
				String op = ToolArgs.reqOp("world_travel", act, operation_id);
				ToolArgs a = ToolArgs.of("world_travel", act, "ECONOMY", params, "seconds", "seconds");
				yield new TravelJourneyService(engine.db(), engine.rules(), engine.roller()).advance(op, c,
						a.reqLong("seconds"));
			}
			case "RESOLVE_OPPORTUNITY" -> {
				String op = ToolArgs.reqOp("world_travel", act, operation_id);
				ToolArgs a = ToolArgs.of("world_travel", act, "ECONOMY", params, "resolution", "resolution");
				yield new TravelJourneyService(engine.db(), engine.rules(), engine.roller()).resolveOpportunity(op, c,
						a.reqStr("resolution"));
			}
			case "DEFINE_TRAVEL_RISK" -> {
				String op = ToolArgs.reqOp("world_travel", act, operation_id);
				ToolArgs a = ToolArgs.of("world_travel", act, "ECONOMY", params, "scope, policy", "scope", "policy");
				yield new TravelJourneyService(engine.db(), engine.rules(), engine.roller()).defineRisk(op, c,
						a.reqStr("scope"), a.reqMap("policy"));
			}
			case "SCHEDULE_EVENT" -> {
				String op = ToolArgs.reqOp("world_travel", act, operation_id);
				ToolArgs a = ToolArgs.of("world_travel", act, "ECONOMY", params, "spec", "spec");
				yield new WorldTimeline(engine.db()).schedule(op, c, a.reqMap("spec"));
			}
			default -> throw new IllegalStateException(act);
			};
		});
	}

	@Tool(name = "patrols", description = "Finite authored regional patrols using existing actors (GM private; see get_instructions topic ECONOMY). Actions: "
			+ "DEFINE (mut) {spec: {name, actors, route, region_ref?, properties?}}; "
			+ "UPDATE (mut) {patrol, changes, expected_revision?}; "
			+ "STATE (read) {patrol}.", annotations = @Tool.Annotations(destructiveHint = false, openWorldHint = false))
	ToolResponse patrols(@ToolArg(description = "DEFINE | UPDATE | STATE")
	String action, @ToolArg(description = "campaign:N")
	String campaign, @ToolArg(description = "Idempotency key (required for mutating actions)", required = false)
	String operation_id, @ToolArg(description = "Action parameters (see description)", required = false)
	Map<String, Object> params) {
		return ToolSupport.run("patrols", () -> {
			String act = ToolArgs.action("patrols", action, List.of("DEFINE", "UPDATE", "STATE"));
			String c = ToolArgs.reqCampaign("patrols", act, campaign);
			return switch (act) {
			case "DEFINE" -> {
				String op = ToolArgs.reqOp("patrols", act, operation_id);
				ToolArgs a = ToolArgs.of("patrols", act, "ECONOMY", params, "spec", "spec");
				yield new PatrolService(engine.db()).define(op, c, a.reqMap("spec"));
			}
			case "UPDATE" -> {
				String op = ToolArgs.reqOp("patrols", act, operation_id);
				ToolArgs a = ToolArgs.of("patrols", act, "ECONOMY", params, "patrol, changes, expected_revision?",
						"patrol", "changes", "expected_revision");
				yield new PatrolService(engine.db()).update(op, c, a.reqStr("patrol"), a.optLong("expected_revision"),
						a.reqMap("changes"));
			}
			case "STATE" -> {
				ToolArgs a = ToolArgs.of("patrols", act, "ECONOMY", params, "patrol", "patrol");
				yield new PatrolService(engine.db()).state(c, a.reqStr("patrol"));
			}
			default -> throw new IllegalStateException(act);
			};
		});
	}
}
