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
import se.hirt.mcp.rpg.protocol.Json;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The three required MCP resources (MCP_PROTOCOL.md §3.2): the GM guide, protocol capabilities, and the installed
 * rulesets with their licensing/attribution.
 */
public class RpgResources {

	@Inject
	Engine engine;

	@ConfigProperty(name = "quarkus.application.version", defaultValue = "unknown")
	String applicationVersion;

	@Resource(uri = "rpg://protocol/guide", name = "gm-guide", mimeType = "text/markdown", description = "Concise instructions for an AI Game Master using this server: tool-use rules, the Harness/GM/Director split, " + "the setup → commit → context-boundary → play → suspend lifecycle.")
	ResourceResponse guide() {
		return new ResourceResponse(
				TextResourceContents.create("rpg://protocol/guide", classpath("protocol/guide.md")));
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
		caps.put("creatures", true);
		caps.put("death_and_continuation", true);
		caps.put("inventory_economy", true);
		caps.put("custom_content", true);
		caps.put("progression", true);
		caps.put("party_membership", true);
		caps.put("director", true);
		caps.put("narrative_state", true);
		caps.put("diegetic_information", true);
		caps.put("rests", true);
		caps.put("spellcasting", true);
		caps.put("reactions", true);
		caps.put("pending_choices", true);
		caps.put("effects", true);
		caps.put("relationship_memory", true);
		caps.put("locations_and_travel", true);
		caps.put("gm_overrides", true);
		m.put("capabilities", caps);
		var optional = new LinkedHashMap<String, Object>();
		optional.put("full_text_memory_search", false);
		optional.put("semantic_memory_search", false);
		optional.put("tactical_grid", false);
		optional.put("remote_transport", false);
		m.put("optional_capabilities", optional);
		m.put("limits", Map.of("maximum_page_size", 100, "maximum_context_budget", 32000));
		m.put("implemented_tools", List.of("get_server_state", "create_campaign", "open_campaign", "get_setup_state",
				"update_campaign_setup", "validate_campaign_setup", "commit_campaign_setup", "complete_campaign",
				"create_character_draft", "get_character_choices", "generate_ability_scores", "update_character_draft",
				"validate_character_draft", "commit_character_draft", "update_party_design", "bootstrap_session",
				"suspend_session", "get_character_sheet", "resolve_check", "roll_dice", "advance_time", "record_memory",
				"query_memories", "query_timeline", "create_checkpoint", "get_continuation_options",
				"restore_checkpoint", "get_content_definitions", "define_content", "transfer_item", "give_money",
				"equip_item", "trade", "grant_loot", "materialize_character", "start_encounter", "get_encounter_state",
				"perform_encounter_action", "resolve_pending_choice", "end_encounter", "apply_runtime_change",
				"award_xp", "transfer_player_control", "get_party", "begin_level_up", "get_level_up_choices",
				"update_level_up", "validate_level_up", "commit_level_up", "abandon_transaction",
				"update_party_membership", "get_relationship", "update_relationship", "update_house_rules", "materialize_location",
				"move_party", "upsert_narrative_state", "get_diegetic_information", "get_director_context",
				"commit_director_changes", "get_context", "perform_rest", "apply_gm_override", "prepare_spells",
				"cast_spell", "set_calendar", "create_account", "transfer_money", "get_accounts", "define_cash_flow",
				"update_cash_flow", "list_cash_flows"));
		return new ResourceResponse(TextResourceContents.create("rpg://protocol/capabilities", Json.write(m)));
	}

	@Resource(uri = "rpg://rulesets", name = "rulesets", mimeType = "application/json", description = "Installed rulesets/versions with license and attribution text (SRD 5.2.1 is CC-BY-4.0).")
	ResourceResponse rulesets() {
		return new ResourceResponse(TextResourceContents.create("rpg://rulesets",
				Json.write(Map.of("rulesets", engine.rules().rulesets()))));
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
