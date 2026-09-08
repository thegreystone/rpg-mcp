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
package se.hirt.mcp.rpg.persistence;

import java.util.List;
import java.util.Set;

/**
 * Table classification for the change journal (DATABASE.md §4).
 * <p>
 * Every campaign-owned mutable canonical aggregate is rewindable. Immutable audit lineage, the journal itself,
 * checkpoint metadata, sessions (audit history), installed content and schema history are not.
 */
public final class Tables {

	/** Rewindable tables, in a dependency-friendly order (parents first). Used by snapshot dumps. */
	public static final List<String> REWINDABLE = List.of("campaign", "policy_state", "game_clock",
			"campaign_setup_draft", "location", "location_connection", "custom_content", "director_seed", "character",
			"character_class", "character_trait", "player_control_assignment", "event", "event_actor", "event_causal",
			"party_membership", "relationship", "relationship_event", "inventory_entry", "active_effect",
			"resource_state", "encounter", "encounter_participant", "encounter_log", "quest", "faction", "story_beat",
			"world_event", "pending_transaction", "roll", "account", "cash_flow", "cash_flow_run");

	public static final Set<String> REWINDABLE_SET = Set.copyOf(REWINDABLE);

	/** Tables that carry a campaign_id column (used to scope snapshot dumps). */
	public static final Set<String> WITHOUT_CAMPAIGN_ID = Set.of("character_class", "character_trait", "event_actor",
			"event_causal", "relationship_event", "resource_state", "encounter_participant", "cash_flow_run");

	public static final Set<String> NOT_REWINDABLE = Set.of("schema_version", "installed_ruleset", "installed_content",
			"session", "journal_entry", "checkpoint", "audit_record");

	private Tables() {
	}

	public static boolean isRewindable(String table) {
		return REWINDABLE_SET.contains(table);
	}

	public static boolean isKnown(String table) {
		return REWINDABLE_SET.contains(table) || NOT_REWINDABLE.contains(table);
	}
}
