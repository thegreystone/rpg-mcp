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

import se.hirt.mcp.rpg.persistence.Row;
import se.hirt.mcp.rpg.persistence.Tx;
import se.hirt.mcp.rpg.protocol.ErrorCode;
import se.hirt.mcp.rpg.protocol.Ref;
import se.hirt.mcp.rpg.protocol.RpgException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Answers "which operations are legal now" (DOMAIN_MODEL.md §16, I-61). Reads are always legal against an existing
 * campaign; mutations are gated by the campaign's harness state. The advisory {@code allowed_operations} list and the
 * server-side validation come from the same table.
 */
public final class Harness {

	private static final List<String> SETUP_TOOLS = List.of("get_setup_state", "update_campaign_setup",
			"validate_campaign_setup", "create_character_draft", "get_character_choices", "generate_ability_scores",
			"update_character_draft", "validate_character_draft", "commit_character_draft", "update_party_design",
			"define_content", "complete_campaign");

	private static final List<String> READ_TOOLS = List.of("get_server_state", "open_campaign", "get_character_sheet",
			"query_memories", "query_timeline", "get_continuation_options", "get_content_definitions", "search_rules",
			"get_encounter_state", "get_party", "get_relationship", "get_level_up_choices", "validate_level_up",
			"get_context", "get_diegetic_information", "get_director_context");

	private Harness() {
	}

	public static List<String> allowedOperations(HarnessState state) {
		var ops = new ArrayList<String>();
		switch (state) {
		case CAMPAIGN_SELECTION -> ops.addAll(List.of("get_server_state", "create_campaign", "open_campaign"));
		case CAMPAIGN_REVIEW -> {
			ops.addAll(SETUP_TOOLS);
			ops.add("commit_campaign_setup");
		}
		case READY_TO_PLAY, SESSION_SUSPEND -> ops.addAll(
				List.of("bootstrap_session", "get_character_sheet", "query_memories", "query_timeline",
						"get_continuation_options", "restore_checkpoint", "materialize_location",
						"upsert_narrative_state", "commit_director_changes", "apply_gm_override", "prepare_spells",
						"update_character", "complete_campaign"));
		case EXPLORATION -> ops.addAll(
				List.of("bootstrap_session", "get_character_sheet", "resolve_check", "record_memory", "query_memories",
						"query_timeline", "advance_time", "create_checkpoint", "get_continuation_options",
						"restore_checkpoint", "transfer_item", "equip_item", "trade", "grant_loot", "define_content",
						"materialize_character", "start_encounter", "apply_runtime_change", "award_xp",
						"transfer_player_control", "begin_level_up", "update_character", "update_party_membership",
						"update_relationship", "materialize_location", "move_party", "upsert_narrative_state",
						"commit_director_changes", "perform_rest", "apply_gm_override", "cast_spell", "prepare_spells",
						"suspend_session", "complete_campaign"));
		case LEVEL_UP -> ops.addAll(
				List.of("get_level_up_choices", "update_level_up", "validate_level_up", "commit_level_up",
						"abandon_transaction", "get_character_sheet", "get_party", "record_memory",
						"complete_campaign"));
		case ENCOUNTER -> ops.addAll(
				List.of("get_encounter_state", "perform_encounter_action", "resolve_pending_choice", "end_encounter",
						"resolve_check", "apply_runtime_change", "equip_item", "update_relationship",
						"materialize_character",
						"get_character_sheet", "record_memory", "get_continuation_options", "restore_checkpoint",
						"transfer_player_control", "apply_gm_override", "complete_campaign"));
		case CHECKPOINT_DECISION -> ops.addAll(
				List.of("get_continuation_options", "restore_checkpoint", "transfer_player_control",
						"complete_campaign"));
		case PLAYER_CHARACTER_TRANSFER ->
				ops.addAll(List.of("get_continuation_options", "transfer_player_control", "complete_campaign"));
		case CAMPAIGN_COMPLETED, CAMPAIGN_FAILED, CAMPAIGN_ABANDONED ->
				ops.addAll(List.of("get_server_state", "open_campaign", "query_memories", "query_timeline"));
		default -> {
			if (state.isSetup()) {
				ops.addAll(SETUP_TOOLS);
			} else {
				ops.addAll(List.of("get_server_state", "open_campaign"));
			}
		}
		}
		return ops;
	}

	public static HarnessState state(Row campaign) {
		return HarnessState.valueOf(campaign.str("harness_state"));
	}

	/** Loads a campaign row by typed reference or fails with NOT_FOUND. */
	public static Row campaign(Tx tx, String campaignRef) {
		long id = Ref.id(campaignRef, Ref.CAMPAIGN);
		return tx.find("campaign", id).orElseThrow(() -> RpgException.notFound("Campaign " + campaignRef));
	}

	/** Validates that {@code tool} may mutate the campaign in its current harness state. */
	public static Row requireMutation(Tx tx, String campaignRef, String tool) {
		Row campaign = campaign(tx, campaignRef);
		HarnessState state = state(campaign);
		List<String> allowed = allowedOperations(state);
		if (!allowed.contains(tool)) {
			var details = new LinkedHashMap<String, Object>();
			details.put("harness_state", state.name());
			details.put("allowed_operations", allowed);
			throw new RpgException(ErrorCode.OPERATION_NOT_ALLOWED,
					"'" + tool + "' is not allowed while the campaign is in " + state + ".", false, details);
		}
		return campaign;
	}

	/** Verifies an optimistic-concurrency expectation (MCP_PROTOCOL.md §5.6). */
	public static void requireRevision(Row row, String what, Long expectedRevision) {
		if (expectedRevision == null) {
			return;
		}
		long actual = row.lng("revision");
		if (actual != expectedRevision) {
			throw RpgException.conflict(
							what + " has revision " + actual + " but expected_revision was " + expectedRevision + "; re-read it and retry.")
					.withDetail("actual_revision", actual).withDetail("expected_revision", expectedRevision);
		}
	}

	/** Standard response metadata (MCP_PROTOCOL.md §6). */
	public static Map<String, Object> meta(Row campaign, List<String> warnings) {
		var meta = new LinkedHashMap<String, Object>();
		HarnessState state = state(campaign);
		meta.put("campaign", Ref.of(Ref.CAMPAIGN, campaign.id()));
		meta.put("harness_state", state.name());
		meta.put("campaign_revision", campaign.lng("revision"));
		meta.put("warnings", warnings == null ? List.of() : warnings);
		meta.put("allowed_operations", allowedOperations(state));
		return meta;
	}

	public static boolean isRead(String tool) {
		return READ_TOOLS.contains(tool);
	}
}
