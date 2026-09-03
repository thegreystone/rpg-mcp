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

import java.util.Map;
import java.util.Optional;

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

	private static Map<String, Object> json(ToolResponse response) {
		return Json.readMap(((TextContent) response.firstContent()).text());
	}

	@Test
	void serverStateEnvelope() {
		ToolResponse response = tools.getServerState(Optional.empty(), Optional.empty(), Optional.empty());
		assertFalse(response.isError());
		Map<String, Object> body = json(response);
		@SuppressWarnings("unchecked") Map<String, Object> result = (Map<String, Object>) body.get("result");
		assertEquals("0.1.0", result.get("protocol_version"));
		assertNotNull(result.get("harness_state"));
		assertNotNull(result.get("campaigns"));
	}

	@Test
	void structuredErrors() {
		ToolResponse response = tools.openCampaign("campaign:999999");
		assertTrue(response.isError());
		@SuppressWarnings("unchecked") Map<String, Object> error = (Map<String, Object>) json(response).get("error");
		assertEquals("NOT_FOUND", error.get("code"));

		ToolResponse malformed = tools.openCampaign("bogus");
		assertTrue(malformed.isError());
		@SuppressWarnings("unchecked") Map<String, Object> error2 = (Map<String, Object>) json(malformed).get("error");
		assertEquals("INVALID_ARGUMENT", error2.get("code"));
	}

	@Test
	void createAndSetupThroughTools() {
		ToolResponse created = tools.createCampaign("tools-create-1", Optional.of("Tool Test"), Optional.empty());
		assertFalse(created.isError(), () -> json(created).toString());
		@SuppressWarnings("unchecked") Map<String, Object> result = (Map<String, Object>) json(created).get("result");
		String campaign = (String) result.get("campaign");
		ToolResponse updated = tools.updateCampaignSetup("tools-update-1", campaign, Optional.empty(),
				Map.of("player_age", 40, "content_profile", "PEGI_16"));
		assertFalse(updated.isError(), () -> json(updated).toString());
		@SuppressWarnings("unchecked") Map<String, Object> meta = (Map<String, Object>) json(updated).get("meta");
		assertEquals("SETUP_EXPERIENCE", meta.get("harness_state"));

		ToolResponse denied = tools.bootstrapSession("tools-boot-1", campaign, Optional.empty());
		assertTrue(denied.isError());
		assertTrue(((TextContent) denied.firstContent()).text().contains("OPERATION_NOT_ALLOWED"));
	}

	@Test
	void resourcesAreServed() {
		assertTrue(text(resources.guide()).contains("engine owns the truth"));
		assertTrue(text(resources.rulesets()).contains("CC-BY-4.0"));
		assertTrue(text(resources.capabilities()).contains("\"checkpoints\":true"));
	}

	private static String text(io.quarkiverse.mcp.server.ResourceResponse response) {
		return ((io.quarkiverse.mcp.server.TextResourceContents) response.firstContents()).text();
	}
}
