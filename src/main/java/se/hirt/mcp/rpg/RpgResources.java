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

import io.quarkiverse.mcp.server.Resource;
import io.quarkiverse.mcp.server.ResourceResponse;
import io.quarkiverse.mcp.server.TextResourceContents;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import se.hirt.mcp.rpg.campaign.CampaignService;
import se.hirt.mcp.rpg.harness.ToolOperations;
import se.hirt.mcp.rpg.protocol.Json;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The three required MCP resources (MCP_PROTOCOL.md §3.2): the GM guide, protocol capabilities, and
 * the installed rulesets with their licensing/attribution.
 */
public class RpgResources {

	@Inject
	Engine engine;

	@ConfigProperty(name = "quarkus.application.version", defaultValue = "unknown")
	String applicationVersion;

	@Resource(uri = "rpg://protocol/guide", name = "gm-guide", mimeType = "text/markdown", description = "Core Game Master instructions for this server (topic CORE): tool-use rules, the Harness/GM/Director split, "
			+ "and an index of on-demand topic manuals available via get_instructions(topic).")
	ResourceResponse guide() {
		return new ResourceResponse(TextResourceContents.create("rpg://protocol/guide", ProtocolGuide.read("CORE")));
	}

	@Resource(uri = "rpg://protocol/guide/full", name = "gm-guide-full", mimeType = "text/markdown", description = "Complete Game Master instructions (CORE plus all topic manuals: SETUP, COMBAT, MAPS, CHARACTERS, DIRECTOR, ECONOMY).")
	ResourceResponse fullGuide() {
		return new ResourceResponse(
				TextResourceContents.create("rpg://protocol/guide/full", ProtocolGuide.read("ALL")));
	}

	@Resource(uri = "rpg://protocol/capabilities", name = "capabilities", mimeType = "application/json", description = "Protocol version, implemented and optional capabilities, and limits.")
	ResourceResponse capabilities() {
		var m = new LinkedHashMap<String, Object>();
		m.put("protocol", Map.of("name", "rpg-mcp", "version", CampaignService.PROTOCOL_VERSION));
		m.put("server", Map.of("name", "rpg-mcp-server", "version", applicationVersion));
		var caps = new LinkedHashMap<String, Object>();
		caps.put("rulesets", true);
		caps.put("setup", true);
		caps.put("character_creation", true);
		caps.put("checks", true);
		caps.put("ledger", true);
		caps.put("checkpoints", true);
		caps.put("game_clock", true);
		caps.put("encounters", true);
		caps.put("ammunition_recovery", true);
		caps.put("creatures", true);
		caps.put("death_and_continuation", true);
		caps.put("inventory_economy", true);
		caps.put("custom_content", true);
		caps.put("progression", true);
		caps.put("party_membership", true);
		caps.put("companion_tactical_control", true);
		caps.put("director", true);
		caps.put("narrative_state", true);
		caps.put("want_references", true);
		caps.put("diegetic_information", true);
		caps.put("rests", true);
		caps.put("spellcasting", true);
		caps.put("reactions", true);
		caps.put("pending_choices", true);
		caps.put("effects", true);
		caps.put("relationship_memory", true);
		caps.put("locations_and_travel", true);
		caps.put("gm_overrides", true);
		caps.put("persistent_maps", true);
		caps.put("staged_map_authoring", true);
		caps.put("party_visibility", true);
		caps.put("living_atlas", true);
		caps.put("finite_regional_patrols", true);
		caps.put("interruptible_travel", true);
		caps.put("compact_tool_surface", true);
		m.put("capabilities", caps);
		var optional = new LinkedHashMap<String, Object>();
		optional.put("full_text_memory_search", false);
		optional.put("semantic_memory_search", false);
		optional.put("tactical_grid", true);
		optional.put("map_images", true);
		optional.put("map_text_fallback", true);
		optional.put("portable_map_bundles", true);
		optional.put("interactive_map_editor", false);
		optional.put("automatic_image_provider", false);
		optional.put("remote_transport", false);
		m.put("optional_capabilities", optional);
		m.put("limits", Map.of("maximum_page_size", 100, "maximum_context_budget", 32000));
		m.put("implemented_tools", ToolOperations.IMPLEMENTED_TOOLS);
		m.put("guide_topics", ProtocolGuide.TOPICS);
		return new ResourceResponse(TextResourceContents.create("rpg://protocol/capabilities", Json.write(m)));
	}

	@Resource(uri = "rpg://rulesets", name = "rulesets", mimeType = "application/json", description = "Installed rulesets/versions with license and attribution text (SRD 5.2.1 is CC-BY-4.0).")
	ResourceResponse rulesets() {
		return new ResourceResponse(TextResourceContents.create("rpg://rulesets",
				Json.write(Map.of("rulesets", engine.rules().rulesets()))));
	}

	@Resource(uri = "rpg://maps/catalog", name = "map-catalog", mimeType = "application/json", description = "Built-in tactical map materials, general prop/furniture catalog, light presets and graphics-pass options.")
	ResourceResponse mapCatalog() {
		return new ResourceResponse(TextResourceContents.create("rpg://maps/catalog",
				Json.write(se.hirt.mcp.rpg.maps.MapPropCatalog.catalog())));
	}

	static String classpath(String path) {
		try (InputStream in = RpgResources.class.getClassLoader().getResourceAsStream(path)) {
			if (in == null) {
				return "(missing resource " + path + ")";
			}
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		} catch (IOException e) {
			return "(unreadable resource " + path + ": " + e.getMessage() + ")";
		}
	}
}
