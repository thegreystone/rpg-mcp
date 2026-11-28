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
import io.quarkiverse.mcp.server.ToolResponse;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import se.hirt.mcp.rpg.protocol.Json;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The MCP tool layer inside the Quarkus container: envelope shape, structured errors, resources.
 */
@QuarkusTest
class RpgToolsTest {

	@Inject
	RpgTools tools;

	@Inject
	RpgResources resources;

	@Inject
	Engine engine;

	private static Map<String, Object> json(ToolResponse response) {
		return Json.readMap(((TextContent) response.firstContent()).text());
	}

	@Test
	void serverStateEnvelope() {
		ToolResponse response = tools.session("STATE", null, null, null);
		assertFalse(response.isError());
		Map<String, Object> body = json(response);
		@SuppressWarnings("unchecked")
		Map<String, Object> result = (Map<String, Object>) body.get("result");
		assertEquals("0.2.0", result.get("protocol_version"));
		assertNotNull(result.get("harness_state"));
		assertNotNull(result.get("campaigns"));
		assertTrue(((List<?>) result.get("allowed_operations")).contains("session:CREATE"));
		assertTrue(((List<?>) result.get("allowed_operations")).contains("get_instructions"));
		assertFalse(result.containsKey("guide"));
		assertTrue(((String) result.get("instruction_hint")).contains("get_instructions"));
	}

	@Test
	@SuppressWarnings("unchecked")
	void companionControlIsAnExplicitSavedPreferenceThroughTheToolLayer() throws Exception {
		try (Engine isolated = TestCampaigns.engine(TestCampaigns.tempDb("control-tools"))) {
			RpgTools controlTools = new RpgTools();
			controlTools.engine = isolated;
			String campaign = (String) ((Map<String, Object>) json(controlTools.session("CREATE", null,
					TestCampaigns.op(), Map.of("title", "Tactical control tool test"))).get("result")).get("campaign");
			String op = TestCampaigns.op();
			Map<String, Object> ctrlParams = Map.of("controller", "PLAYER", "scope", "UNTIL_RELEASED", "reason",
					"The player wants party tactics.");
			ToolResponse changed = controlTools.character("TACTICAL_CONTROL", campaign, op, ctrlParams);
			assertFalse(changed.isError(), () -> json(changed).toString());
			Map<String, Object> replay = (Map<String, Object>) json(
					controlTools.character("TACTICAL_CONTROL", campaign, op, ctrlParams)).get("result");
			assertEquals(Boolean.TRUE, replay.remove("replayed"));
			assertEquals(json(changed).get("result"), replay);
			Map<String, Object> party = (Map<String, Object>) json(
					controlTools.character("PARTY", campaign, null, null)).get("result");
			Map<String, Object> control = (Map<String, Object>) party.get("tactical_control");
			assertEquals("DM", control.get("default_companion_controller"));
			assertTrue(Json.write(control.get("policies")).contains("PLAYER"));
			ToolResponse invalid = controlTools.character("TACTICAL_CONTROL", campaign, TestCampaigns.op(),
					Map.of("controller", "PLAYER", "scope", "ENCOUNTER"));
			assertTrue(invalid.isError(), "Encounter-scoped control requires a running encounter.");
			assertTrue(text(resources.capabilities()).contains("character"));
		}
	}

	@Test
	void instructionsAreAvailableWithoutReadingAResource() {
		ToolResponse response = tools.getInstructions(null);
		assertFalse(response.isError());
		@SuppressWarnings("unchecked")
		Map<String, Object> result = (Map<String, Object>) json(response).get("result");
		String guide = (String) result.get("guide");
		assertNotNull(guide);
		assertEquals("CORE", result.get("topic"));
		assertTrue(guide.contains("engine owns the truth"));
		assertEquals(text(resources.guide()), guide);

		ToolResponse mapsTopic = tools.getInstructions("MAPS");
		assertFalse(mapsTopic.isError());
		String mapsGuide = (String) ((Map<?, ?>) json(mapsTopic).get("result")).get("guide");
		assertTrue(mapsGuide.contains("Final graphics & mood pass"));

		ToolResponse allTopics = tools.getInstructions("ALL");
		assertFalse(allTopics.isError());
		assertEquals(text(resources.fullGuide()), ((Map<?, ?>) json(allTopics).get("result")).get("guide"));

		ToolResponse badTopic = tools.getInstructions("NONEXISTENT");
		assertTrue(badTopic.isError());
		assertEquals("INVALID_ARGUMENT", ((Map<?, ?>) json(badTopic).get("error")).get("code"));
	}

	@Test
	void structuredErrors() {
		ToolResponse response = tools.session("OPEN", "campaign:999999", null, null);
		assertTrue(response.isError());
		@SuppressWarnings("unchecked")
		Map<String, Object> error = (Map<String, Object>) json(response).get("error");
		assertEquals("NOT_FOUND", error.get("code"));

		ToolResponse malformed = tools.session("OPEN", "bogus", null, null);
		assertTrue(malformed.isError());
		@SuppressWarnings("unchecked")
		Map<String, Object> error2 = (Map<String, Object>) json(malformed).get("error");
		assertEquals("INVALID_ARGUMENT", error2.get("code"));

		ToolResponse badAction = tools.session("NO_SUCH_ACTION", null, null, null);
		assertTrue(badAction.isError());
		@SuppressWarnings("unchecked")
		Map<String, Object> error3 = (Map<String, Object>) json(badAction).get("error");
		assertEquals("INVALID_ARGUMENT", error3.get("code"));
		assertTrue(error3.get("message").toString().contains("STATE"));

		ToolResponse badParam = tools.session("STATE", null, null, Map.of("typo_key", 1));
		assertTrue(badParam.isError());
		@SuppressWarnings("unchecked")
		Map<String, Object> error4 = (Map<String, Object>) json(badParam).get("error");
		assertEquals("INVALID_ARGUMENT", error4.get("code"));
		assertTrue(error4.get("message").toString().contains("typo_key"));
	}

	@Test
	void createAndSetupThroughTools() {
		ToolResponse created = tools.session("CREATE", null, "tools-create-1", Map.of("title", "Tool Test"));
		assertFalse(created.isError(), () -> json(created).toString());
		@SuppressWarnings("unchecked")
		Map<String, Object> result = (Map<String, Object>) json(created).get("result");
		String campaign = (String) result.get("campaign");
		ToolResponse updated = tools.campaignSetup("UPDATE", campaign, "tools-update-1",
				Map.of("changes", Map.of("player_age", 40, "content_profile", "PEGI_16")));
		assertFalse(updated.isError(), () -> json(updated).toString());
		@SuppressWarnings("unchecked")
		Map<String, Object> meta = (Map<String, Object>) json(updated).get("meta");
		assertEquals("SETUP_EXPERIENCE", meta.get("harness_state"));

		ToolResponse denied = tools.session("BOOTSTRAP", campaign, "tools-boot-1", null);
		assertTrue(denied.isError());
		assertTrue(((TextContent) denied.firstContent()).text().contains("OPERATION_NOT_ALLOWED"));
	}

	@Test
	void wantsCanBeFoundAndClosedByReferenceThroughTools() {
		String campaign = TestCampaigns.committedCampaign(engine);
		engine.sessions().bootstrap(TestCampaigns.op(), campaign, null);
		ToolResponse created = tools.character("UPDATE", campaign, TestCampaigns.op(),
				Map.of("character", "character:1", "changes",
						Map.of("biography", Map.of("wants", List.of(Map.of("note", "Visit the observatory"))))));
		assertFalse(created.isError(), () -> json(created).toString());
		ToolResponse found = tools.chronicle("FIND", campaign, null, Map.of("kind", "WANT", "query", "observatory"));
		assertFalse(found.isError(), () -> json(found).toString());
		@SuppressWarnings("unchecked")
		List<Map<String, Object>> hits = (List<Map<String, Object>>) ((Map<?, ?>) json(found).get("result"))
				.get("hits");
		String ref = (String) hits.getFirst().get("ref");
		assertTrue(ref.startsWith("want:"));
		ToolResponse closed = tools.character("UPDATE", campaign, TestCampaigns.op(), Map.of("character", "character:1",
				"changes", Map.of("biography", Map.of("wants", List.of(Map.of("ref", ref, "status", "DONE"))))));
		assertFalse(closed.isError(), () -> json(closed).toString());
		ToolResponse context = tools.narrative("CONTEXT", campaign, null, Map.of("scope", "WANT", "ref", ref));
		assertFalse(context.isError(), () -> json(context).toString());
		Map<?, ?> want = (Map<?, ?>) ((Map<?, ?>) json(context).get("result")).get("want");
		assertEquals(ref, want.get("ref"));
		assertEquals("Visit the observatory", want.get("note"));
		assertEquals("DONE", want.get("status"));
		assertFalse(want.containsKey("_closed_journal_id"));
	}

	@Test
	void resourcesAreServedAndCompactSurfaceIsBounded() {
		assertTrue(text(resources.guide()).contains("engine owns the truth"));
		assertTrue(text(resources.fullGuide()).contains("Final graphics & mood pass"));
		assertTrue(text(resources.rulesets()).contains("CC-BY-4.0"));
		assertTrue(text(resources.mapCatalog()).contains("\"table_round\""));
		assertTrue(text(resources.capabilities()).contains("\"checkpoints\":true"));
		Map<String, Object> caps = Json.readMap(text(resources.capabilities()));
		List<?> implemented = (List<?>) caps.get("implemented_tools");
		assertEquals(24, implemented.size(), "Compact consolidated surface should stay small for multi-MCP hosts");
		assertTrue(implemented.contains("get_instructions"));
		assertTrue(implemented.contains("character"));
		assertTrue(implemented.contains("render_map"));
		assertTrue(implemented.contains("restore_checkpoint"));
		assertTrue(implemented.contains("complete_campaign"));
		assertEquals(Boolean.TRUE, ((Map<?, ?>) caps.get("capabilities")).get("want_references"));
		assertEquals(Boolean.TRUE, ((Map<?, ?>) caps.get("capabilities")).get("compact_tool_surface"));
	}

	private static String text(io.quarkiverse.mcp.server.ResourceResponse response) {
		return ((io.quarkiverse.mcp.server.TextResourceContents) response.firstContents()).text();
	}
}
