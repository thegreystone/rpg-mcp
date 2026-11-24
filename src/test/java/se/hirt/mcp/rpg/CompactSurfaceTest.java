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

import io.quarkiverse.mcp.server.TextContent;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import io.quarkiverse.mcp.server.ToolResponse;
import org.junit.jupiter.api.Test;
import se.hirt.mcp.rpg.harness.Harness;
import se.hirt.mcp.rpg.harness.HarnessState;
import se.hirt.mcp.rpg.harness.ToolOperations;
import se.hirt.mcp.rpg.protocol.Json;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static se.hirt.mcp.rpg.TestCampaigns.*;

/**
 * Verifies the consolidated 22-tool MCP surface (Option 2): exact tool inventory, 112-operation
 * bijection, compact schema budget, topic-split guide, and dispatch across all domain tools.
 */
class CompactSurfaceTest {

	private static final List<Class<?>> TOOL_CLASSES = List.of(RpgTools.class, MapTools.class, EconomyTools.class,
			LivingWorldTools.class);

	@Test
	void toolSurfaceIsCompactWellDocumentedAndMatchesToolOperations() {
		Set<String> exposed = new LinkedHashSet<>();
		int descChars = 0;
		int argChars = 0;
		for (Class<?> cls : TOOL_CLASSES) {
			for (Method m : cls.getDeclaredMethods()) {
				Tool tool = m.getAnnotation(Tool.class);
				if (tool == null) {
					continue;
				}
				assertTrue(exposed.add(tool.name()), "Duplicate @Tool name: " + tool.name());
				assertFalse(tool.description().isBlank(), tool.name() + " needs a description");
				descChars += tool.description().length();
				for (Parameter p : m.getParameters()) {
					ToolArg arg = p.getAnnotation(ToolArg.class);
					assertNotNull(arg, tool.name() + "." + p.getName() + " missing @ToolArg");
					assertFalse(arg.description().isBlank(), tool.name() + "." + p.getName() + " blank @ToolArg");
					argChars += arg.description().length();
				}
			}
		}
		assertEquals(22, exposed.size(), "Expected 22 consolidated tools, got: " + exposed);
		assertEquals(Set.copyOf(ToolOperations.IMPLEMENTED_TOOLS), exposed);
		assertTrue(descChars < 16_000, "Tool descriptions grew too large: " + descChars + " chars");
		assertTrue(argChars < 6_000, "Arg descriptions grew too large: " + argChars + " chars");

		// Every one of the 112 internal operations maps 1-to-1 to a real exposed MCP tool.
		assertEquals(112, ToolOperations.all().size());
		for (var entry : ToolOperations.all().entrySet()) {
			String internal = entry.getKey();
			String pub = entry.getValue();
			assertEquals(internal, ToolOperations.internalName(pub), "Round-trip failed for " + internal);
			String toolName = pub.contains(":") ? pub.substring(0, pub.indexOf(':')) : pub;
			assertTrue(exposed.contains(toolName), "Public operation " + pub + " points to missing tool " + toolName);
		}
		// Every HarnessState advertises only public tool names backed by exposed tools.
		for (HarnessState state : HarnessState.values()) {
			for (String op : Harness.allowedOperations(state)) {
				String toolName = op.contains(":") ? op.substring(0, op.indexOf(':')) : op;
				assertTrue(exposed.contains(toolName), "HarnessState " + state + " advertises unknown tool " + op);
			}
		}
	}

	@Test
	void modularProtocolGuideCoversAllTopicsAndFitsCompactCoreBudget() {
		String core = ProtocolGuide.read("CORE");
		assertTrue(core.length() < 12_000, "CORE guide should stay lean (~8-10 KB), got " + core.length());
		assertTrue(core.contains("engine owns the truth"));
		assertTrue(core.contains("Baldur's Gate"));
		for (String topic : ProtocolGuide.TOPICS) {
			String text = ProtocolGuide.read(topic);
			assertFalse(text.isBlank(), "Empty guide topic: " + topic);
			assertFalse(text.contains("(missing resource"), "Missing resource for topic: " + topic);
		}
		String all = ProtocolGuide.read("ALL");
		for (String topic : List.of("SETUP", "COMBAT", "MAPS", "CHARACTERS", "DIRECTOR", "ECONOMY")) {
			assertTrue(all.contains(ProtocolGuide.read(topic).strip()), "ALL missing topic " + topic);
		}
	}

	@Test
	void domainToolsDispatchAndValidateActionsAcrossAllModules() throws Exception {
		try (Engine engine = engine(tempDb("compact-surface-dispatch"))) {
			String campaign = committedCampaign(engine);
			engine.sessions().bootstrap(op(), campaign, null);

			RpgTools rpg = new RpgTools();
			rpg.engine = engine;
			EconomyTools econ = new EconomyTools();
			econ.engine = engine;
			LivingWorldTools world = new LivingWorldTools();
			world.engine = engine;
			MapTools maps = new MapTools();
			maps.engine = engine;

			// rules (read-only global & campaign-scoped)
			Map<String, Object> search = ok(rpg.rules("SEARCH", null, null, Map.of("query", "grappled")));
			assertFalse(((List<?>) search.get("results")).isEmpty());
			Map<String, Object> defs = ok(
					rpg.rules("DEFINITIONS", campaign, null, Map.of("kind", "SPELL", "limit", 5)));
			assertFalse(((List<?>) defs.get("items")).isEmpty());

			// roll_check (DICE & CHECK)
			Map<String, Object> dice = ok(rpg.rollCheck("DICE", campaign, op(), Map.of("expression", "1d20")));
			assertNotNull(dice.get("roll_ref"));
			Map<String, Object> check = ok(rpg.rollCheck("CHECK", campaign, op(),
					Map.of("actor", "character:1", "kind", "ABILITY_CHECK", "ability", "STR", "difficulty", 10)));
			assertNotNull(check.get("roll"));

			// economy (SET_CALENDAR, CREATE_ACCOUNT, GET_ACCOUNTS, LIST_CASH_FLOWS)
			ok(econ.economy("SET_CALENDAR", campaign, op(), Map.of("year", 1492, "month", 5, "day", 1)));
			ok(econ.economy("CREATE_ACCOUNT", campaign, op(),
					Map.of("name", "House Treasury", "owner_kind", "OTHER", "money", "10 gp")));
			Map<String, Object> accounts = ok(econ.economy("GET_ACCOUNTS", campaign, null, null));
			assertFalse(((List<?>) accounts.get("accounts")).isEmpty());
			ok(econ.economy("LIST_CASH_FLOWS", campaign, null, null));

			// rest_time (ADVANCE)
			ok(rpg.restTime("ADVANCE", campaign, op(), Map.of("minutes", 10, "reason", "Walking across town")));

			// world_travel (TRAVEL_STATE) & patrols validation error
			ok(world.worldTravel("TRAVEL_STATE", campaign, null, null));
			ToolResponse badPatrol = world.patrols("STATE", campaign, null, Map.of("wrong_key", "patrol:1"));
			assertTrue(badPatrol.isError());
			Map<String, Object> patrolErr = err(badPatrol);
			assertEquals("INVALID_ARGUMENT", patrolErr.get("code"));
			assertTrue(patrolErr.get("message").toString().contains("wrong_key"));
			assertEquals("ECONOMY", ((Map<?, ?>) patrolErr.get("details")).get("help_topic"));

			// inventory (GRANT_LOOT)
			ok(rpg.inventory("GRANT_LOOT", campaign, op(),
					Map.of("to", "character:1", "money", "5 gp", "source", "QUEST", "reason", "Found coin pouch")));

			// checkpoints (CREATE, OPTIONS)
			ok(rpg.checkpoints("CREATE", campaign, op(), Map.of("reason", "Before test")));
			Map<String, Object> cont = ok(rpg.checkpoints("OPTIONS", campaign, null, null));
			assertNotNull(cont.get("options"));

			// narrative & chronicle
			ok(rpg.narrative("CONTEXT", campaign, null, Map.of("scope", "SCENE")));
			ok(rpg.chronicle("RECORD_MEMORY", campaign, op(),
					Map.of("type", "NOTABLE_EVENT", "summary", "Met the harbourmaster")));
			Map<String, Object> timeline = ok(rpg.chronicle("QUERY_TIMELINE", campaign, null, Map.of("limit", 5)));
			assertFalse(((List<?>) timeline.get("events")).isEmpty());

			// map_play (FIND)
			ok(maps.mapPlay("FIND", campaign, null, null));

			// Missing operation_id on mutating action returns actionable INVALID_ARGUMENT
			ToolResponse missingOp = rpg.chronicle("RECORD_MEMORY", campaign, null,
					Map.of("type", "X", "summary", "Y"));
			assertTrue(missingOp.isError());
			assertEquals("INVALID_ARGUMENT", err(missingOp).get("code"));
			assertTrue(err(missingOp).get("message").toString().contains("operation_id"));
		}
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> ok(ToolResponse response) {
		assertFalse(response.isError(), () -> ((TextContent) response.firstContent()).text());
		return (Map<String, Object>) Json.readMap(((TextContent) response.firstContent()).text()).get("result");
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> err(ToolResponse response) {
		return (Map<String, Object>) Json.readMap(((TextContent) response.firstContent()).text()).get("error");
	}
}
