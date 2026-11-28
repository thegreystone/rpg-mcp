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
package se.hirt.mcp.rpg.harness;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Single source of truth mapping the engine's internal operation identifiers (persisted in SQLite
 * idempotency and audit rows) to the consolidated 24-tool MCP surface ({@code tool:ACTION}, or
 * {@code tool} for single-action tools).
 */
public final class ToolOperations {

	/** Ordered list of every MCP tool exposed by the server. */
	public static final List<String> IMPLEMENTED_TOOLS = List.of("get_instructions", "session", "campaign_setup",
			"character_draft", "level_up", "character", "roll_check", "encounter", "spellcasting", "rest_time",
			"inventory", "economy", "world_travel", "patrols", "narrative", "chronicle", "checkpoints", "rules",
			"map_preparation", "map_play", "render_map", "restore_checkpoint", "complete_campaign",
			"apply_gm_override");

	private static final Map<String, String> INTERNAL_TO_PUBLIC;
	private static final Map<String, String> PUBLIC_TO_INTERNAL;

	static {
		var m = new LinkedHashMap<String, String>();
		// Standalone (read-only & destructive boundaries)
		m.put("get_instructions", "get_instructions");
		m.put("render_map", "render_map");
		m.put("restore_checkpoint", "restore_checkpoint");
		m.put("complete_campaign", "complete_campaign");
		m.put("apply_gm_override", "apply_gm_override");
		// session
		m.put("get_server_state", "session:STATE");
		m.put("create_campaign", "session:CREATE");
		m.put("open_campaign", "session:OPEN");
		m.put("bootstrap_session", "session:BOOTSTRAP");
		m.put("suspend_session", "session:SUSPEND");
		// campaign_setup
		m.put("get_setup_state", "campaign_setup:GET");
		m.put("update_campaign_setup", "campaign_setup:UPDATE");
		m.put("validate_campaign_setup", "campaign_setup:VALIDATE");
		m.put("commit_campaign_setup", "campaign_setup:COMMIT");
		m.put("update_party_design", "campaign_setup:PARTY_DESIGN");
		m.put("update_house_rules", "campaign_setup:HOUSE_RULES");
		// character_draft
		m.put("create_character_draft", "character_draft:CREATE");
		m.put("get_character_choices", "character_draft:CHOICES");
		m.put("generate_ability_scores", "character_draft:GENERATE_ABILITIES");
		m.put("update_character_draft", "character_draft:UPDATE");
		m.put("validate_character_draft", "character_draft:VALIDATE");
		m.put("commit_character_draft", "character_draft:COMMIT");
		// level_up
		m.put("begin_level_up", "level_up:BEGIN");
		m.put("get_level_up_choices", "level_up:CHOICES");
		m.put("update_level_up", "level_up:UPDATE");
		m.put("validate_level_up", "level_up:VALIDATE");
		m.put("commit_level_up", "level_up:COMMIT");
		m.put("abandon_transaction", "level_up:ABANDON");
		// character
		m.put("get_character_sheet", "character:SHEET");
		m.put("get_party", "character:PARTY");
		m.put("update_character", "character:UPDATE");
		m.put("materialize_character", "character:MATERIALIZE");
		m.put("apply_runtime_change", "character:RUNTIME_CHANGE");
		m.put("award_xp", "character:AWARD_XP");
		m.put("update_party_membership", "character:MEMBERSHIP");
		m.put("set_tactical_control", "character:TACTICAL_CONTROL");
		m.put("transfer_player_control", "character:TRANSFER_CONTROL");
		// roll_check
		m.put("resolve_check", "roll_check:CHECK");
		m.put("roll_dice", "roll_check:DICE");
		// encounter
		m.put("start_encounter", "encounter:START");
		m.put("get_encounter_state", "encounter:STATE");
		m.put("perform_encounter_action", "encounter:ACTION");
		m.put("resolve_pending_choice", "encounter:RESOLVE_CHOICE");
		m.put("end_encounter", "encounter:END");
		m.put("annotate_encounter", "encounter:ANNOTATE");
		m.put("add_encounter_participants", "encounter:ADD_PARTICIPANTS");
		m.put("recover_ammunition", "encounter:RECOVER_AMMO");
		m.put("convert_encounter_to_grid", "encounter:CONVERT_TO_GRID");
		// spellcasting
		m.put("prepare_spells", "spellcasting:PREPARE");
		m.put("cast_spell", "spellcasting:CAST");
		// rest_time
		m.put("perform_rest", "rest_time:REST");
		m.put("advance_time", "rest_time:ADVANCE");
		m.put("perform_timed_action", "rest_time:TIMED_ACTION");
		// inventory
		m.put("transfer_item", "inventory:TRANSFER_ITEM");
		m.put("equip_item", "inventory:EQUIP");
		m.put("give_money", "inventory:GIVE_MONEY");
		m.put("trade", "inventory:TRADE");
		m.put("grant_loot", "inventory:GRANT_LOOT");
		// economy
		m.put("set_calendar", "economy:SET_CALENDAR");
		m.put("create_account", "economy:CREATE_ACCOUNT");
		m.put("transfer_money", "economy:TRANSFER_MONEY");
		m.put("get_accounts", "economy:GET_ACCOUNTS");
		m.put("define_cash_flow", "economy:DEFINE_CASH_FLOW");
		m.put("update_cash_flow", "economy:UPDATE_CASH_FLOW");
		m.put("list_cash_flows", "economy:LIST_CASH_FLOWS");
		// world_travel
		m.put("materialize_location", "world_travel:MATERIALIZE_LOCATION");
		m.put("move_party", "world_travel:MOVE_PARTY");
		m.put("get_travel_state", "world_travel:TRAVEL_STATE");
		m.put("continue_journey", "world_travel:CONTINUE_JOURNEY");
		m.put("resolve_travel_opportunity", "world_travel:RESOLVE_OPPORTUNITY");
		m.put("define_travel_risk", "world_travel:DEFINE_TRAVEL_RISK");
		m.put("schedule_world_event", "world_travel:SCHEDULE_EVENT");
		// patrols
		m.put("define_patrol", "patrols:DEFINE");
		m.put("update_patrol", "patrols:UPDATE");
		m.put("get_patrol_state", "patrols:STATE");
		// narrative
		m.put("get_context", "narrative:CONTEXT");
		m.put("upsert_narrative_state", "narrative:UPSERT");
		m.put("get_diegetic_information", "narrative:DIEGETIC");
		m.put("get_director_context", "narrative:DIRECTOR_CONTEXT");
		m.put("commit_director_changes", "narrative:COMMIT_DIRECTOR");
		m.put("get_relationship", "narrative:GET_RELATIONSHIP");
		m.put("update_relationship", "narrative:UPDATE_RELATIONSHIP");
		// chronicle
		m.put("record_memory", "chronicle:RECORD_MEMORY");
		m.put("query_memories", "chronicle:QUERY_MEMORIES");
		m.put("query_timeline", "chronicle:QUERY_TIMELINE");
		m.put("find", "chronicle:FIND");
		m.put("get_chronicle_material", "chronicle:GET_MATERIAL");
		m.put("write_chronicle", "chronicle:WRITE");
		// checkpoints
		m.put("create_checkpoint", "checkpoints:CREATE");
		m.put("get_continuation_options", "checkpoints:OPTIONS");
		// rules
		m.put("search_rules", "rules:SEARCH");
		m.put("get_content_definitions", "rules:DEFINITIONS");
		m.put("define_content", "rules:DEFINE");
		// map_preparation
		m.put("begin_map_preparation", "map_preparation:BEGIN");
		m.put("revise_map_preparation", "map_preparation:REVISE");
		m.put("validate_map_preparation", "map_preparation:VALIDATE");
		m.put("publish_map_preparation", "map_preparation:PUBLISH");
		m.put("import_map_asset", "map_preparation:IMPORT_ASSET");
		m.put("export_map_bundle", "map_preparation:EXPORT_BUNDLE");
		m.put("import_map_bundle", "map_preparation:IMPORT_BUNDLE");
		// map_play
		m.put("get_map_context", "map_play:CONTEXT");
		m.put("find_maps", "map_play:FIND");
		m.put("place_map_actors", "map_play:PLACE_ACTORS");
		m.put("move_map_actor", "map_play:MOVE_ACTOR");
		m.put("observe_map", "map_play:OBSERVE");
		m.put("preview_spatial_action", "map_play:PREVIEW_ACTION");
		m.put("use_map_transition", "map_play:USE_TRANSITION");
		m.put("update_map_object", "map_play:UPDATE_OBJECT");
		m.put("correct_map_geometry", "map_play:CORRECT_GEOMETRY");
		m.put("get_actor_perception", "map_play:PERCEPTION");
		m.put("emit_map_sound", "map_play:EMIT_SOUND");
		m.put("update_map_annotations", "map_play:ANNOTATE");

		INTERNAL_TO_PUBLIC = Collections.unmodifiableMap(m);
		var reverse = new LinkedHashMap<String, String>();
		for (var e : m.entrySet()) {
			reverse.put(e.getValue(), e.getKey());
		}
		PUBLIC_TO_INTERNAL = Collections.unmodifiableMap(reverse);
	}

	private ToolOperations() {
	}

	/** Maps an internal operation id to its public MCP {@code tool:ACTION} label. */
	public static String publicName(String internalOperation) {
		return INTERNAL_TO_PUBLIC.getOrDefault(internalOperation, internalOperation);
	}

	/**
	 * Maps a public {@code tool:ACTION} label (or internal name) back to its internal operation id.
	 */
	public static String internalName(String operation) {
		return PUBLIC_TO_INTERNAL.getOrDefault(operation, operation);
	}

	/** Complete unmodifiable view of internal → public operation labels. */
	public static Map<String, String> all() {
		return INTERNAL_TO_PUBLIC;
	}
}
