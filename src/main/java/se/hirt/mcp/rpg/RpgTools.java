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
import java.util.Optional;

/**
 * The MCP tool surface (MCP_PROTOCOL.md §23) — the vertical slice: discovery, resumable campaign setup, character
 * drafts, commit, session bootstrap, deterministic checks, the ledger, the clock, checkpoints, suspension. Tool names
 * are the protocol's snake_case operation names.
 * <p>
 * Every response is a JSON envelope {@code {"result": ..., "meta": {...}}}; every failure is a JSON
 * {@code {"error": {code, message, retryable, details}}} tool error.
 */
public class RpgTools {

	private static final String REF = "Campaign reference, e.g. 'campaign:1'";
	private static final String OP = "Client-generated idempotency key, unique within the campaign (e.g. a ULID). " + "Repeating a call with the same operation_id and arguments returns the original result without re-applying it.";

	@Inject
	Engine engine;

	// ── Discovery ──────────────────────────────────────────────────────

	@Tool(name = "get_server_state", description = "Call this FIRST in every new conversation. Returns protocol/server version, " + "the current harness state, the active campaign (if one was opened in this process), pending transactions, " + "a page of existing campaigns with their player character and last-played time, legal next operations, and the policy summary. " + "No chat history is ever needed: everything canonical is reconstructed from the server. Read-only.", annotations = @Tool.Annotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false))
	ToolResponse getServerState(
			@ToolArg(description = "Include the campaign list (default true)") Optional<Boolean> include_campaigns,
			@ToolArg(description = "Max campaigns per page (default 20, max 100)") Optional<Integer> campaign_limit,
			@ToolArg(description = "Opaque cursor from a previous next_campaign_cursor")
			Optional<String> campaign_cursor) {
		return ToolSupport.run("get_server_state", () -> engine.campaigns()
				.serverState(include_campaigns.orElse(true), campaign_limit.orElse(20), campaign_cursor.orElse(null)));
	}

	// ── Campaign setup ─────────────────────────────────────────────────

	@Tool(name = "create_campaign", description = "MUTATING. Creates a new campaign in SETUP with a resumable setup draft — not yet playable. " + "Returns `decisions`: the outstanding setup decisions in interview order, each with its legal options and their descriptions. " + "Ask decisions[0] only — one question per turn, every option listed with its description, plus a custom answer where allowed — " + "record the answer with update_campaign_setup, then read the decisions again. For almost every creative question " + "'surprise me' (SURPRISE_ME) is a valid answer.", annotations = @Tool.Annotations(destructiveHint = false, openWorldHint = false))
	ToolResponse createCampaign(
			@ToolArg(description = OP) String operation_id,
			@ToolArg(description = "Optional title; generated at commit from the premise if omitted")
			Optional<String> title, @ToolArg(description = "Ruleset, e.g. 'srd5e' or 'srd5e:5.2.1' (default: srd5e)")
			Optional<String> ruleset) {
		return ToolSupport.run("create_campaign",
				() -> engine.campaigns().create(operation_id, title.orElse(null), ruleset.orElse(null)));
	}

	@Tool(name = "open_campaign", description = "Selects an existing campaign and tells you how to resume: RESUME_SETUP, BOOTSTRAP_SESSION, " + "RESOLVE_CHECKPOINT_DECISION or CAMPAIGN_ENDED. Never discards pending work. Read-only apart from remembering the active campaign.", annotations = @Tool.Annotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false))
	ToolResponse openCampaign(@ToolArg(description = REF) String campaign) {
		return ToolSupport.run("open_campaign", () -> engine.campaigns().open(campaign));
	}

	@Tool(name = "get_setup_state", description = "Returns the full setup draft, the outstanding `decisions` in interview order (each with every legal " + "option and its description — ask decisions[0] only), characters, and constraints. Read-only.", annotations = @Tool.Annotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false))
	ToolResponse getSetupState(@ToolArg(description = REF) String campaign) {
		return ToolSupport.run("get_setup_state", () -> engine.campaigns().setupState(campaign));
	}

	@Tool(name = "update_campaign_setup", description = "MUTATING. Atomically records one or more setup sections. `changes` keys: " + "title (string); player_age (integer — only the derived content cap is stored, never the age); " + "content_profile (value or {profile}); " + "experience (object: authorship, fantasy_style (defaults to EPIC, a Baldur's Gate-style fantasy epic), tone, themes, relationship_focus, excluded_themes … or SURPRISE_ME); " + "rules ({ability_generation, progression, hp_progression, xp_policy, companion_level_up, gm_override_policy, allow_reroll: bool}); " + "continuation (value or {policy}); party (object or SURPRISE_ME); " + "adventure ({premise, background_truth (GM-only), opening_location: name or {name, kind, description}, immediate_goal, start_time 'Day 1, 16:40', gm_notes}); " + "player_character ('character:N'). The legal values of every choice come from the `decisions` in the response — never hard-code them. " + "Sections merge, so one call per answer is fine; unknown keys are rejected; sections may be revisited freely before commit.", annotations = @Tool.Annotations(destructiveHint = false, openWorldHint = false))
	ToolResponse updateCampaignSetup(
			@ToolArg(description = OP) String operation_id, @ToolArg(description = REF) String campaign,
			@ToolArg(description = "Campaign revision you last read (CONFLICT if stale)")
			Optional<Long> expected_revision,
			@ToolArg(description = "Object of setup sections to apply") Map<String, Object> changes) {
		return ToolSupport.run("update_campaign_setup",
				() -> engine.campaigns().updateSetup(operation_id, campaign, expected_revision.orElse(null), changes));
	}

	@Tool(name = "validate_campaign_setup", description = "Validates the complete setup graph without committing: violations, warnings, " + "delegated (SURPRISE_ME) choices and a compact review to present to the player. Read-only.", annotations = @Tool.Annotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false))
	ToolResponse validateCampaignSetup(@ToolArg(description = REF) String campaign) {
		return ToolSupport.run("validate_campaign_setup", () -> engine.campaigns().validateSetup(campaign));
	}

	@Tool(name = "commit_campaign_setup", description = "MUTATING, one transaction. Freezes the setup as canonical configuration, promotes the finalized " + "player character to ACTIVE (starting HP, money, location), creates the party/control assignment, the opening location, the clock, " + "the immediate-goal story beat, the setup ledger event and (unless IRONMAN) the campaign_start checkpoint. " + "After this the setup conversation is disposable: call bootstrap_session and play from server state only.", annotations = @Tool.Annotations(destructiveHint = false, openWorldHint = false))
	ToolResponse commitCampaignSetup(
			@ToolArg(description = OP) String operation_id, @ToolArg(description = REF) String campaign,
			@ToolArg(description = "Campaign revision you last read") Optional<Long> expected_revision) {
		return ToolSupport.run("commit_campaign_setup",
				() -> engine.campaigns().commitSetup(operation_id, campaign, expected_revision.orElse(null)));
	}

	@Tool(name = "complete_campaign", description = "MUTATING. Ends a campaign as COMPLETED, FAILED or ABANDONED with an explicit reason. " + "Abandoning during setup archives its draft characters. Irreversible from the gameplay protocol.", annotations = @Tool.Annotations(destructiveHint = true, openWorldHint = false))
	ToolResponse completeCampaign(
			@ToolArg(description = OP) String operation_id, @ToolArg(description = REF) String campaign,
			@ToolArg(description = "COMPLETED, FAILED or ABANDONED") String outcome,
			@ToolArg(description = "Why the campaign ends") String reason,
			@ToolArg(description = "Optional final summary for the ledger") Optional<String> summary) {
		return ToolSupport.run("complete_campaign",
				() -> engine.campaigns().complete(operation_id, campaign, outcome, reason, summary.orElse(null)));
	}

	// ── Character design ───────────────────────────────────────────────

	@Tool(name = "create_character_draft", description = "MUTATING. Reserves a stable character:N reference with lifecycle DRAFT. " + "The first player-controlled draft becomes the campaign's player character. Optionally applies initial fields " + "(same keys as update_character_draft). Returns the sheet and `decisions`: what the draft still needs, in order, " + "each with every legal option and its description — ask decisions[0] only.", annotations = @Tool.Annotations(destructiveHint = false, openWorldHint = false))
	ToolResponse createCharacterDraft(
			@ToolArg(description = OP) String operation_id, @ToolArg(description = REF) String campaign,
			@ToolArg(description = "Initial fields, e.g. {name, species: 'Human', class: 'Sorcerer', presentation: 'male'}", required = false)
			Map<String, Object> initial,
			@ToolArg(description = "true (default) for the player's own character; false for authored companions")
			Optional<Boolean> player_controlled) {
		return ToolSupport.run("create_character_draft",
				() -> engine.characters().createDraft(operation_id, campaign, initial, player_controlled.orElse(true)));
	}

	@Tool(name = "get_character_choices", description = "Legal choices for a decision scope, every option with a description: ALL, ABILITY_GENERATION, " + "SPECIES, CLASS, BACKGROUND, FEAT, SKILLS, ALIGNMENT, SPELLS and EQUIPMENT (the last two, and the class skill list, need `character`). " + "With a draft character the response also carries its outstanding `decisions` in order. Use this instead of inventing options. Read-only.", annotations = @Tool.Annotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false))
	ToolResponse getCharacterChoices(
			@ToolArg(description = REF) String campaign,
			@ToolArg(description = "Scope (default ALL)") Optional<String> scope,
			@ToolArg(description = "Character reference for class-dependent scopes") Optional<String> character) {
		return ToolSupport.run("get_character_choices",
				() -> engine.characters().choices(campaign, scope.orElse("ALL"), character.orElse(null)));
	}

	@Tool(name = "generate_ability_scores", description = "MUTATING. Performs the campaign's ability generation method for a draft: " + "rolled methods roll six scores on the server (every die and dropped value returned; no silent rerolls); array and " + "point-buy methods return the array/budget. Then assign with update_character_draft.ability_scores.", annotations = @Tool.Annotations(destructiveHint = false, openWorldHint = false))
	ToolResponse generateAbilityScores(
			@ToolArg(description = OP) String operation_id, @ToolArg(description = REF) String campaign,
			@ToolArg(description = "Draft character reference") String character,
			@ToolArg(description = "Must match rules.ability_generation; defaults to it") Optional<String> method) {
		return ToolSupport.run("generate_ability_scores", () -> engine.characters()
				.generateAbilityScores(operation_id, campaign, character, method.orElse(null)));
	}

	@Tool(name = "update_character_draft", description = "MUTATING. Applies draft changes. `changes` keys: name, description, appearance, personality, " + "backstory, goals (list), alignment (e.g. CHAOTIC_GOOD), age, presentation, species (name or id), class (name or id), " + "background (name or id — grants the SRD background's two skills, tool and Origin feat), " + "background_ability_scores (+2/+1 or +1/+1/+1 among the background's three abilities), " + "ability_scores ({STR, DEX, CON, INT, WIS, CHA} — validated against the generation method immediately), skills (list, exact class count), " + "species_skill, species_choice (lineage/ancestry, optionally {choice, ability}), origin_feat (feat or {feat, ...choices}), " + "feat_choices ({feat, ...}), background_tool, starting_equipment, background_equipment, cantrips, spells. " + "Max HP is derived (hit die + CON modifier + species bonuses). Locally checkable constraints fail immediately; whole-character checks happen in validate.", annotations = @Tool.Annotations(destructiveHint = false, openWorldHint = false))
	ToolResponse updateCharacterDraft(
			@ToolArg(description = OP) String operation_id, @ToolArg(description = REF) String campaign,
			@ToolArg(description = "Draft character reference") String character,
			@ToolArg(description = "Character revision you last read") Optional<Long> expected_revision,
			@ToolArg(description = "Fields to change") Map<String, Object> changes) {
		return ToolSupport.run("update_character_draft", () -> engine.characters()
				.updateDraft(operation_id, campaign, character, expected_revision.orElse(null), changes));
	}

	@Tool(name = "validate_character_draft", description = "Complete validation of a draft plus a review sheet to read back to the player. Read-only.", annotations = @Tool.Annotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false))
	ToolResponse validateCharacterDraft(
			@ToolArg(description = REF) String campaign,
			@ToolArg(description = "Character reference") String character) {
		return ToolSupport.run("validate_character_draft",
				() -> engine.characters().validateDraft(campaign, character));
	}

	@Tool(name = "commit_character_draft", description = "MUTATING. Validates and finalizes a draft (DRAFT → FINALIZED_DRAFT). The identity never changes; " + "the character becomes ACTIVE, with starting money and HP, at commit_campaign_setup.", annotations = @Tool.Annotations(destructiveHint = false, openWorldHint = false))
	ToolResponse commitCharacterDraft(
			@ToolArg(description = OP) String operation_id, @ToolArg(description = REF) String campaign,
			@ToolArg(description = "Draft character reference") String character,
			@ToolArg(description = "Character revision you last read") Optional<Long> expected_revision) {
		return ToolSupport.run("commit_character_draft", () -> engine.characters()
				.commitDraft(operation_id, campaign, character, expected_revision.orElse(null)));
	}

	@Tool(name = "update_party_design", description = "MUTATING. Stores companion preferences and introduction intentions (desired_roles, relationship_seeds, " + "companion_agency, personality_distinctiveness, notes …) or SURPRISE_ME. Intentions are Director planning state, never guarantees.", annotations = @Tool.Annotations(destructiveHint = false, openWorldHint = false))
	ToolResponse updatePartyDesign(
			@ToolArg(description = OP) String operation_id, @ToolArg(description = REF) String campaign,
			@ToolArg(description = "Campaign revision you last read") Optional<Long> expected_revision,
			@ToolArg(description = "Party preferences object (use {\"authorship\": \"SURPRISE_ME\"} to delegate)")
			Map<String, Object> preferences) {
		return ToolSupport.run("update_party_design", () -> engine.campaigns()
				.updateSetup(operation_id, campaign, expected_revision.orElse(null), Map.of("party", preferences)));
	}

	// ── Session and context ────────────────────────────────────────────

	@Tool(name = "bootstrap_session", description = "MUTATING (opens/resumes a session). Returns the bounded context package to continue play: " + "campaign config, game time, location, player character sheet, party, story beats, quests, adventure premise (+ GM_ONLY truth), " + "recent significant events, previous session summary, pending transaction and legal operations. Call after commit and at the start of every new conversation. " + "Trust this over anything you remember.", annotations = @Tool.Annotations(destructiveHint = false, openWorldHint = false))
	ToolResponse bootstrapSession(
			@ToolArg(description = OP) String operation_id, @ToolArg(description = REF) String campaign,
			@ToolArg(description = "Approximate token budget for the context (default 12000)")
			Optional<Integer> context_budget) {
		return ToolSupport.run("bootstrap_session",
				() -> engine.sessions().bootstrap(operation_id, campaign, context_budget.orElse(null)));
	}

	@Tool(name = "suspend_session", description = "MUTATING. Closes the session with a compact summary (the recap the next session starts from), " + "records game time/location and the number of events written. Commit important consequences (record_memory) first.", annotations = @Tool.Annotations(destructiveHint = false, openWorldHint = false))
	ToolResponse suspendSession(
			@ToolArg(description = OP) String operation_id, @ToolArg(description = REF) String campaign,
			@ToolArg(description = "Compact narrative summary of what happened this session") String summary) {
		return ToolSupport.run("suspend_session", () -> engine.sessions().suspend(operation_id, campaign, summary));
	}

	@Tool(name = "get_character_sheet", description = "Character sheet at detail SUMMARY, PLAY (default: abilities, proficiencies, HP, money, XP) or FULL " + "(+ narrative fields). Read-only.", annotations = @Tool.Annotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false))
	ToolResponse getCharacterSheet(
			@ToolArg(description = REF) String campaign, @ToolArg(description = "Character reference") String character,
			@ToolArg(description = "SUMMARY, PLAY or FULL") Optional<String> detail) {
		return ToolSupport.run("get_character_sheet",
				() -> engine.characters().characterSheet(campaign, character, detail.orElse("PLAY")));
	}

	@Tool(name = "update_character", description = "MUTATING. Applies canonical narrative/identity changes to a committed character: name, description, " + "appearance, personality, backstory, goals (list), age (number), presentation. Use this to record facts established in play " + "(an NPC's revealed age, a companion's true name, a changed appearance). Mechanical state is never changed here — that belongs to " + "rules-governed operations or an audited override. Drafts use update_character_draft instead.", annotations = @Tool.Annotations(destructiveHint = false, openWorldHint = false))
	ToolResponse updateCharacter(
			@ToolArg(description = OP) String operation_id, @ToolArg(description = REF) String campaign,
			@ToolArg(description = "Character reference") String character,
			@ToolArg(description = "Character revision you last read") Optional<Long> expected_revision,
			@ToolArg(description = "Narrative fields to change") Map<String, Object> changes) {
		return ToolSupport.run("update_character", () -> engine.characters()
				.updateCharacter(operation_id, campaign, character, expected_revision.orElse(null), changes));
	}

	// ── Rules ──────────────────────────────────────────────────────────

	@Tool(name = "resolve_check", description = "MUTATING (journaled roll). Resolves an ABILITY_CHECK, SKILL_CHECK or SAVING_THROW with authoritative " + "modifiers and server dice — in exploration and in the middle of an encounter alike (a parley, a shove, a lock under fire). " + "You supply the fictional intent and the DC; never supply a die result. Name the `tool` when one is used (thieves' tools, a forger's " + "calligrapher's supplies): a proficient actor adds their proficiency bonus, and proficiency in both the skill and the tool gives advantage. " + "Returns the full breakdown, success/margin against the DC, and a roll_ref. Narrate failure as new complications, not a dead end.", annotations = @Tool.Annotations(destructiveHint = false, openWorldHint = false))
	ToolResponse resolveCheck(
			@ToolArg(description = OP) String operation_id, @ToolArg(description = REF) String campaign,
			@ToolArg(description = "Acting character reference") String actor,
			@ToolArg(description = "ABILITY_CHECK, SKILL_CHECK or SAVING_THROW") String kind,
			@ToolArg(description = "Ability (STR/DEX/CON/INT/WIS/CHA); optional for SKILL_CHECK (defaults to the skill's ability)")
			Optional<String> ability,
			@ToolArg(description = "Skill name for SKILL_CHECK, e.g. Deception") Optional<String> skill,
			@ToolArg(description = "Tool used for the check, e.g. \"Thieves' Tools\" or \"Calligrapher's Supplies\" (SRD 5.2.1: proficiency bonus if proficient; advantage when also proficient in the skill)")
			Optional<String> tool,
			@ToolArg(description = "Difficulty class (DC), 1–40") Optional<Integer> difficulty,
			@ToolArg(description = "NONE (default), ADVANTAGE or DISADVANTAGE") Optional<String> advantage,
			@ToolArg(description = "Short reason, e.g. 'Bluffing the magistrate'") Optional<String> reason) {
		return ToolSupport.run("resolve_check", () -> engine.checks()
				.resolveCheck(operation_id, campaign, actor, kind, ability.orElse(null), skill.orElse(null),
						tool.orElse(null), difficulty.orElse(null), advantage.orElse(null), reason.orElse(null)));
	}

	@Tool(name = "advance_time", description = "MUTATING. Advances the world clock by minutes and returns the new time plus a Director trigger " + "recommendation when a lot of time passed. (Effect expiry and scheduled world events arrive in a later milestone.)", annotations = @Tool.Annotations(destructiveHint = false, openWorldHint = false))
	ToolResponse advanceTime(
			@ToolArg(description = OP) String operation_id, @ToolArg(description = REF) String campaign,
			@ToolArg(description = "Minutes to advance (positive)") int minutes,
			@ToolArg(description = "Why, e.g. 'travel to the ruined tower'") Optional<String> reason) {
		return ToolSupport.run("advance_time",
				() -> engine.sessions().advanceTime(operation_id, campaign, minutes, reason.orElse(null)));
	}

	// ── Memory ─────────────────────────────────────────────────────────

	@Tool(name = "record_memory", description = "MUTATING. Records a significant canonical event in the ledger — only what would damage continuity if forgotten " + "(first meetings, promises, betrayals, proposals, deaths, major discoveries). Mechanical operations write their own events; do not duplicate them. " + "Give CRITICAL events an episodic `detail` so they can be recalled years later.", annotations = @Tool.Annotations(destructiveHint = false, openWorldHint = false))
	ToolResponse recordMemory(
			@ToolArg(description = OP) String operation_id, @ToolArg(description = REF) String campaign,
			@ToolArg(description = "Event type, e.g. IMPORTANT_FIRST_MEETING, RELATIONSHIP_MILESTONE, PROMISE, SECRET_DISCOVERED")
			String type, @ToolArg(description = "Concise summary (one or two sentences)") String summary,
			@ToolArg(description = "Participating character references") Optional<List<String>> participants,
			@ToolArg(description = "MINOR, NOTABLE (default), MAJOR or CRITICAL") Optional<String> importance,
			@ToolArg(description = "PLAYER_KNOWN, PARTY_KNOWN (default), CHARACTER_KNOWN, FACTION_KNOWN, GM_ONLY, DIRECTOR_ONLY")
			Optional<String> visibility,
			@ToolArg(description = "PLAYER, GM (default), DIRECTOR, MECHANICAL_CONSEQUENCE, ADMINISTRATIVE_OVERRIDE")
			Optional<String> provenance,
			@ToolArg(description = "Fictional game time if not now, e.g. 'Day 3, 22:14' (retroactive events are fine)")
			Optional<String> game_time, @ToolArg(description = "Location reference if known") Optional<String> location,
			@ToolArg(description = "Richer episodic detail (bounded; for important memories)") Optional<String> detail,
			@ToolArg(description = "Structured payload", required = false) Map<String, Object> payload) {
		return ToolSupport.run("record_memory", () -> engine.ledger()
				.record(operation_id, campaign, type, summary, participants.orElse(null), importance.orElse(null),
						visibility.orElse(null), provenance.orElse(null), game_time.orElse(null), location.orElse(null),
						detail.orElse(null), payload));
	}

	@Tool(name = "query_memories", description = "Retrieves relevant ledger events by participants, types, a natural-language focus (keyword match over " + "summary/detail) and minimum importance — for recalling shared history on demand (\"do you remember when I proposed?\"). Read-only.", annotations = @Tool.Annotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false))
	ToolResponse queryMemories(
			@ToolArg(description = REF) String campaign,
			@ToolArg(description = "Character references that must all participate")
			Optional<List<String>> participants,
			@ToolArg(description = "Event types to include") Optional<List<String>> types,
			@ToolArg(description = "Focus words, e.g. 'proposal ring'") Optional<String> focus,
			@ToolArg(description = "Minimum importance") Optional<String> min_importance,
			@ToolArg(description = "Max results (default 10)") Optional<Integer> limit) {
		return ToolSupport.run("query_memories", () -> engine.ledger()
				.queryMemories(campaign, participants.orElse(null), types.orElse(null), focus.orElse(null),
						min_importance.orElse(null), limit.orElse(10)));
	}

	@Tool(name = "query_timeline", description = "Ordered events for a game-time range / entity set / types, newest first by fictional time " + "(or by insertion id for audit-style views). Read-only.", annotations = @Tool.Annotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false))
	ToolResponse queryTimeline(
			@ToolArg(description = REF) String campaign,
			@ToolArg(description = "From game time, e.g. 'Day 1, 00:00'") Optional<String> from_time,
			@ToolArg(description = "To game time") Optional<String> to_time,
			@ToolArg(description = "Character references that must all participate")
			Optional<List<String>> participants,
			@ToolArg(description = "Event types to include") Optional<List<String>> types,
			@ToolArg(description = "Minimum importance") Optional<String> min_importance,
			@ToolArg(description = "Order by insertion id instead of fictional time") Optional<Boolean> by_insertion,
			@ToolArg(description = "Max results (default 25)") Optional<Integer> limit) {
		return ToolSupport.run("query_timeline", () -> engine.ledger()
				.queryTimeline(campaign, from_time.orElse(null), to_time.orElse(null), participants.orElse(null),
						types.orElse(null), min_importance.orElse(null), by_insertion.orElse(false), limit.orElse(25)));
	}

	// ── Checkpoints and continuation ───────────────────────────────────

	@Tool(name = "create_checkpoint", description = "MUTATING. Creates a restorable checkpoint (a marker in the change journal) when policy permits — " + "before major encounters and story transitions. Denied under IRONMAN.", annotations = @Tool.Annotations(destructiveHint = false, openWorldHint = false))
	ToolResponse createCheckpoint(
			@ToolArg(description = OP) String operation_id, @ToolArg(description = REF) String campaign,
			@ToolArg(description = "Reason, e.g. MAJOR_ENCOUNTER or 'before entering the crypt'")
			Optional<String> reason) {
		return ToolSupport.run("create_checkpoint",
				() -> engine.checkpoints().create(operation_id, campaign, reason.orElse(null)));
	}

	@Tool(name = "get_continuation_options", description = "Legal continuation options: restorable checkpoints, survivor transfer (availability), campaign completion. " + "Usable after player-character death or, under CHECKPOINT policy, whenever the player regrets a decision. Read-only.", annotations = @Tool.Annotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false))
	ToolResponse getContinuationOptions(@ToolArg(description = REF) String campaign) {
		return ToolSupport.run("get_continuation_options", () -> engine.checkpoints().options(campaign));
	}

	@Tool(name = "restore_checkpoint", description = "MUTATING, DESTRUCTIVE for later history. Rewinds ALL canonical campaign state to the checkpoint in one " + "transaction (characters, inventory, money, party, relationships, quests, story, encounters, ledger, clock). Later events stop being canonical; " + "an audit record of the restoration survives. Afterwards call bootstrap_session and trust server state over prior narration.", annotations = @Tool.Annotations(destructiveHint = true, openWorldHint = false))
	ToolResponse restoreCheckpoint(
			@ToolArg(description = OP) String operation_id, @ToolArg(description = REF) String campaign,
			@ToolArg(description = "Checkpoint reference, e.g. 'checkpoint:2'") String checkpoint,
			@ToolArg(description = "Why the player chose to rewind") Optional<String> reason) {
		return ToolSupport.run("restore_checkpoint",
				() -> engine.checkpoints().restore(operation_id, campaign, checkpoint, reason.orElse(null)));
	}
	// ── Content, inventory and economy ─────────────────────────────────

	@Tool(name = "get_content_definitions", description = "Searches installed rules content (SRD 5.2.1) and, when a campaign is given, its custom " + "definitions. kind defaults to ITEM (weapons, armor, gear, tools, packs, ammunition, focuses, mounts…); other kinds: CLASS, SPECIES, " + "BACKGROUND, FEAT, SKILL, SPELL, CREATURE, TABLE. " + "Filter by item_type, free text, and price; cursor-paginated. Use this to price purchases and answer equipment questions instead of inventing them. Read-only.", annotations = @Tool.Annotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false))
	ToolResponse getContentDefinitions(
			@ToolArg(description = "Campaign reference (optional; includes that campaign's custom content)")
			Optional<String> campaign, @ToolArg(description = "Content kind (default ITEM)") Optional<String> kind,
			@ToolArg(description = "Item type filter: WEAPON, ARMOR, SHIELD, GEAR, TOOL, AMMUNITION, FOCUS, PACK, CONTAINER, MOUNT, TACK, VEHICLE")
			Optional<String> item_type,
			@ToolArg(description = "Free-text search over name/id/rules text, e.g. 'sword finesse'")
			Optional<String> text,
			@ToolArg(description = "Minimum cost, e.g. '1 gp' or 100 (cp)") Optional<String> min_cost,
			@ToolArg(description = "Maximum cost, e.g. '50 gp'") Optional<String> max_cost,
			@ToolArg(description = "SUMMARY (default) or FULL (includes the complete payload)") Optional<String> detail,
			@ToolArg(description = "Opaque cursor from next_cursor") Optional<String> cursor,
			@ToolArg(description = "Page size (default 25, max 100)") Optional<Integer> limit) {
		return ToolSupport.run("get_content_definitions", () -> engine.content()
				.definitions(campaign.orElse(null), kind.orElse(null), item_type.orElse(null), text.orElse(null),
						min_cost.orElse(null), max_cost.orElse(null), cursor.orElse(null), limit.orElse(25),
						detail.orElse("SUMMARY")));
	}

	@Tool(name = "search_rules", description = "READ-ONLY. Ranked free-text search across every installed rules definition and the campaign's custom content: " + "the SRD Rules Glossary (kind RULE - conditions, actions, hazards, areas of effect, cover, resting, movement, carrying capacity), " + "plus spells, items, creatures, classes, species, backgrounds, feats and skills. Returns each hit's `ref`, `kind`, `name`, `tag` and a `snippet` of the actual rules text. " + "USE THIS BEFORE ANSWERING A RULES QUESTION OR ADJUDICATING AN UNFAMILIAR SITUATION, and cite the returned ref. If nothing matches, say the rule is not in the SRD rather than recalling one.", annotations = @Tool.Annotations(readOnlyHint = true, openWorldHint = false))
	ToolResponse searchRules(
			@ToolArg(description = "Campaign reference (optional; include it to search custom content too)",
					required = false) String campaign,
			@ToolArg(description = "What to look up, e.g. 'grappled', 'sneak attack', 'half cover', 'long rest'")
			String query,
			@ToolArg(description = "Restrict to one kind, e.g. RULE, SPELL, ITEM, CREATURE, FEAT", required = false)
			String kind, @ToolArg(description = "Maximum hits (default 10, max 50)", required = false) Integer limit) {
		return ToolSupport.run("search_rules",
				() -> engine.content().search(campaign, query, kind, limit == null ? 0 : limit));
	}

	@Tool(name = "define_content", description = "MUTATING. Creates campaign-scoped custom content with a stable content:N reference and an optional 'custom:<kind>/slug' symbolic id. " + "kind ITEM (default): a local newspaper, a regional delicacy, a quest letter — buyable, carried, transferable; defining it grants it to nobody. " + "kind BACKGROUND: a campaign background (a Noble, a Fen Keeper) built like an SRD one — properties {ability_scores: [3 abilities], feat: an Origin feat, " + "skills: [2 skills], tool: {item} or {choice: ARTISANS_TOOLS|GAMING_SET|MUSICAL_INSTRUMENT|TOOL}, feat_choices?, starting_equipment?} — " + "usable by name wherever a background is chosen (drafts and companion promotions). Mechanical WEAPON/ARMOR definitions arrive in a later milestone.", annotations = @Tool.Annotations(destructiveHint = false, openWorldHint = false))
	ToolResponse defineContent(
			@ToolArg(description = OP) String operation_id, @ToolArg(description = REF) String campaign,
			@ToolArg(description = "Display name") String name,
			@ToolArg(description = "Content kind: ITEM (default) or BACKGROUND") Optional<String> kind,
			@ToolArg(description = "Symbolic id like 'custom:item/bellhaven-broadsheet' or 'custom:background/noble'") Optional<String> symbolic_id,
			@ToolArg(description = "Description / rules text (for a BACKGROUND: its summary)") Optional<String> description,
			@ToolArg(description = "ITEM only: GEAR (default), CONSUMABLE, VALUABLE, DOCUMENT, TOOL, CONTAINER, MOUNT, OTHER…")
			Optional<String> item_type,
			@ToolArg(description = "ITEM only: list price, e.g. '2 cp', '15 gp 5 sp' or an integer in cp (default 0)")
			Optional<String> cost, @ToolArg(description = "ITEM only: weight in pounds (default 0)") Optional<Double> weight_lb,
			@ToolArg(description = "Extra structured properties; for a BACKGROUND the ability_scores/feat/skills/tool spec", required = false) Map<String, Object> properties,
			@ToolArg(description = "Tags for search") Optional<List<String>> tags) {
		return ToolSupport.run("define_content", () -> engine.content()
				.define(operation_id, campaign, kind.orElse("ITEM"), name, symbolic_id.orElse(null),
						description.orElse(null), item_type.orElse(null), cost.orElse(null), weight_lb.orElse(null),
						properties, tags.orElse(null), "GM"));
	}

	@Tool(name = "transfer_item", description = "MUTATING. Moves an inventory stack (or part of it) to another character or to a location (dropping / stashing). " + "Equipped items are unequipped by the move. Enforces ownership and quantity.", annotations = @Tool.Annotations(destructiveHint = false, openWorldHint = false))
	ToolResponse transferItem(
			@ToolArg(description = OP) String operation_id, @ToolArg(description = REF) String campaign,
			@ToolArg(description = "Inventory entry reference from a character sheet, e.g. 'inventory:12'")
			String entry, @ToolArg(description = "Destination: 'character:N' or 'location:N'") String to,
			@ToolArg(description = "Quantity to move (default: the whole stack)") Optional<Integer> quantity,
			@ToolArg(description = "Why (narrative note)") Optional<String> reason) {
		return ToolSupport.run("transfer_item", () -> engine.inventory()
				.transfer(operation_id, campaign, entry, to, quantity.orElse(null), reason.orElse(null)));
	}

	@Tool(name = "equip_item", description = "MUTATING. Equips or unequips a carried weapon, armor, shield or focus with slot validation (one body armor, " + "one shield, two hands). Returns Armor Class before/after and the equipped set.", annotations = @Tool.Annotations(destructiveHint = false, openWorldHint = false))
	ToolResponse equipItem(
			@ToolArg(description = OP) String operation_id, @ToolArg(description = REF) String campaign,
			@ToolArg(description = "Character reference") String character,
			@ToolArg(description = "Inventory entry reference, e.g. 'inventory:12'") String entry,
			@ToolArg(description = "true to equip, false to unequip") boolean equipped) {
		return ToolSupport.run("equip_item",
				() -> engine.inventory().equip(operation_id, campaign, character, entry, equipped));
	}

	@Tool(name = "trade", description = "MUTATING, atomic. BUY an item from the market/merchant at its list price (ammunition is sold in bundles: quantity " + "counts bundles) or SELL a carried stack for half its list price. Money and items move together; insufficient funds fail with " + "INSUFFICIENT_RESOURCE. A negotiated_price (from a successful Persuasion check, say) requires a reason and is recorded with GM provenance.", annotations = @Tool.Annotations(destructiveHint = false, openWorldHint = false))
	ToolResponse trade(
			@ToolArg(description = OP) String operation_id, @ToolArg(description = REF) String campaign,
			@ToolArg(description = "Buying/selling character reference") String character,
			@ToolArg(description = "BUY or SELL") String kind,
			@ToolArg(description = "Item name or id ('Longsword', 'srd5e:item/longsword', 'content:5', 'custom:item/x')")
			Optional<String> item,
			@ToolArg(description = "For SELL: a specific inventory entry reference instead of an item name")
			Optional<String> entry,
			@ToolArg(description = "Quantity (default 1; for BUY of ammunition this is the number of bundles)")
			Optional<Integer> quantity,
			@ToolArg(description = "Merchant character reference, if the merchant is a persisted NPC")
			Optional<String> merchant, @ToolArg(description = "Negotiated total price, e.g. '12 gp' (requires reason)")
			Optional<String> negotiated_price,
			@ToolArg(description = "Reason for a negotiated price") Optional<String> reason) {
		return ToolSupport.run("trade", () -> engine.inventory()
				.trade(operation_id, campaign, character, kind, item.orElse(null), entry.orElse(null),
						quantity.orElse(null), merchant.orElse(null), negotiated_price.orElse(null),
						reason.orElse(null)));
	}

	@Tool(name = "grant_loot", description = "MUTATING. Materializes rewards — items and/or money — for a character or a location from an ENCOUNTER, QUEST or WORLD " + "source, or as an explicit GM_GRANT (requires a reason, is audited, and is refused when the campaign disables GM overrides). " + "Writes a LOOT_ACQUIRED ledger event.", annotations = @Tool.Annotations(destructiveHint = false, openWorldHint = false))
	ToolResponse grantLoot(
			@ToolArg(description = OP) String operation_id, @ToolArg(description = REF) String campaign,
			@ToolArg(description = "Recipient: 'character:N' or 'location:N'") String to,
			@ToolArg(description = "Items: [{\"item\": \"Dagger\", \"quantity\": 2}, …]", required = false)
			List<Map<String, Object>> items,
			@ToolArg(description = "Money, e.g. '25 gp' (characters only)") Optional<String> money,
			@ToolArg(description = "ENCOUNTER, QUEST, WORLD or GM_GRANT (default)") Optional<String> source,
			@ToolArg(description = "Why (required for GM_GRANT)") Optional<String> reason) {
		return ToolSupport.run("grant_loot", () -> engine.inventory()
				.grantLoot(operation_id, campaign, to, items, money.orElse(null), source.orElse(null),
						reason.orElse(null)));
	}
	// ── Creatures, encounters and runtime ──────────────────────────────

	@Tool(name = "materialize_character", description = "MUTATING. Turns an installed creature definition (e.g. 'Bandit', 'srd5e:creature/wolf', " + "'Commoner' for any ordinary NPC) into a canonical character with its own stable character:N identity, stat block, HP and location. " + "Do this before an encounter for every opponent, and for any NPC who must persist. Rename freely; identity never changes. " + "A materialized creature is a stat block, not a character: give `alignment` to anyone who is a person, and see get_party's " + "sheet_gaps for what a prospective party member still lacks (begin_level_up grants a class, species and background).", annotations = @Tool.Annotations(destructiveHint = false, openWorldHint = false))
	ToolResponse materializeCharacter(
			@ToolArg(description = OP) String operation_id, @ToolArg(description = REF) String campaign,
			@ToolArg(description = "Creature definition id or name") String source,
			@ToolArg(description = "Display name (default: the creature's name)") Optional<String> name,
			@ToolArg(description = "Description") Optional<String> description,
			@ToolArg(description = "Personality summary") Optional<String> personality,
			@ToolArg(description = "Alignment, e.g. CHAOTIC_GOOD — set it for anyone who is a character rather than an opponent")
			Optional<String> alignment,
			@ToolArg(description = "Location reference (default: the party's current location)")
			Optional<String> location,
			@ToolArg(description = "Roll hit points from the stat block dice instead of using the average")
			Optional<Boolean> roll_hp) {
		return ToolSupport.run("materialize_character", () -> engine.runtime()
				.materialize(operation_id, campaign, source, name.orElse(null), description.orElse(null),
						personality.orElse(null), alignment.orElse(null), location.orElse(null),
						roll_hp.orElse(false)));
	}

	@Tool(name = "start_encounter", description = "MUTATING. Creates and starts an encounter: sides map names to character references " + "({\"party\": [\"character:1\"], \"raiders\": [\"character:5\", \"character:6\"]}), optional stances between sides " + "({\"party|raiders\": \"HOSTILE\"}; default HOSTILE), optional zones per character ({\"character:5\": \"far\"}). " + "Rolls initiative (d20 + DEX), creates the retry checkpoint under ENCOUNTER_RETRY, and returns the first turn. All participants must be materialized characters. " + "options.npc_reactions: AUTO (default; the engine resolves NPC opportunity attacks and Shield) or ASK (every reaction becomes a pending choice).", annotations = @Tool.Annotations(destructiveHint = false, openWorldHint = false))
	ToolResponse startEncounter(
			@ToolArg(description = OP) String operation_id, @ToolArg(description = REF) String campaign,
			@ToolArg(description = "Side name → list of character references") Map<String, Object> sides,
			@ToolArg(description = "Stances between sides, e.g. {\"party|raiders\": \"HOSTILE\"}", required = false)
			Map<String, Object> stances,
			@ToolArg(description = "Starting zone per character reference, e.g. {\"character:5\": \"far\"}", required = false)
			Map<String, Object> zones,
			@ToolArg(description = "Environment/terrain description") Optional<String> environment,
			@ToolArg(description = "Encounter objectives") Optional<List<String>> objectives,
			@ToolArg(description = "Location reference (default: current location)") Optional<String> location,
			@ToolArg(description = "Options: {npc_reactions: AUTO|ASK}", required = false)
			Map<String, Object> options) {
		return ToolSupport.run("start_encounter", () -> engine.encounters()
				.start(operation_id, campaign, sides, stances, zones, environment.orElse(null), objectives.orElse(null),
						location.orElse(null), options));
	}

	@Tool(name = "resolve_pending_choice", description = "MUTATING. Completes a pending choice the engine created during an encounter (opportunity attack: TAKE {weapon?}|DECLINE; " + "Shield spell: CAST_SHIELD|DECLINE). The encounter waits (status WAITING_CHOICE) until every open choice is resolved; the interrupted action then " + "continues and the turn advances if it was ending. Choices and their legal options are listed in get_encounter_state.pending_choices.", annotations = @Tool.Annotations(destructiveHint = false, openWorldHint = false))
	ToolResponse resolvePendingChoice(
			@ToolArg(description = OP) String operation_id, @ToolArg(description = REF) String campaign,
			@ToolArg(description = "Pending choice reference (transaction:N)") String transaction,
			@ToolArg(description = "The choice: {option: 'TAKE'|'DECLINE'|'CAST_SHIELD', weapon?: 'Longsword'}")
			Map<String, Object> choice) {
		return ToolSupport.run("resolve_pending_choice",
				() -> engine.encounters().resolveChoice(operation_id, campaign, transaction, choice));
	}

	@Tool(name = "get_encounter_state", description = "Authoritative encounter view: round, whose turn, participants with HP (enemy HP labeled GM_ONLY), AC, " + "conditions, death saves, zones, available attacks, and the recent log ('what happened last turn'). Read-only.", annotations = @Tool.Annotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false))
	ToolResponse getEncounterState(
			@ToolArg(description = REF) String campaign,
			@ToolArg(description = "Encounter reference (default: the running encounter)") Optional<String> encounter,
			@ToolArg(description = "Number of recent log entries (default 10)") Optional<Integer> log_limit) {
		return ToolSupport.run("get_encounter_state",
				() -> engine.encounters().encounterState(campaign, encounter.orElse(null), log_limit.orElse(10)));
	}

	@Tool(name = "perform_encounter_action", description = "MUTATING, atomic. Resolves one action for the participant whose turn it is. action.kind: " + "ATTACK {target, weapon|attack (item name, inventory:N, or a creature action name; omit for the creature's first attack or an unarmed strike), " + "two_handed, advantage: ADVANTAGE|DISADVANTAGE, nonlethal: true (melee only; knocks out at 0 HP instead of killing)}; CAST {spell, targets|target, slot_level}; " + "DODGE; DASH/MOVE {zone} (leaving a zone with hostile creatures provokes Opportunity Attacks unless you Disengaged); DISENGAGE; HELP; HIDE; " + "USE_ITEM {item, target} (Potion of Healing is mechanical); INTERACT/OTHER_RULES_ACTION {description}; END_TURN. Attacks use server dice, real AC, " + "crits (natural 20; melee vs unconscious), resistances, temp HP, ammunition, and death rules. Reactions owned by player-controlled characters " + "(opportunity attacks, Shield) come back as pending_choices: ask the player, then call resolve_pending_choice — the action and turn resume afterwards. " + "end_turn (default true) advances to the next participant, rolling death saves for the dying.", annotations = @Tool.Annotations(destructiveHint = false, openWorldHint = false))
	ToolResponse performEncounterAction(
			@ToolArg(description = OP) String operation_id, @ToolArg(description = REF) String campaign,
			@ToolArg(description = "Acting character reference (must be the current turn)") String actor,
			@ToolArg(description = "The action object; see description") Map<String, Object> action,
			@ToolArg(description = "Encounter reference (default: the running encounter)") Optional<String> encounter,
			@ToolArg(description = "Advance to the next turn after this action (default true); pass false for multi-part turns")
			Optional<Boolean> end_turn) {
		return ToolSupport.run("perform_encounter_action", () -> engine.encounters()
				.perform(operation_id, campaign, encounter.orElse(null), actor, action, end_turn.orElse(true)));
	}

	@Tool(name = "end_encounter", description = "MUTATING. Ends the encounter with an outcome (PARTY_VICTORY, PARTY_DEFEAT, PARTY_FLED, ENEMIES_FLED, NEGOTIATED, OTHER): " + "awards the encounter's XP pool (fixed at start from every hostile participant) split among surviving party members whenever the encounter " + "was overcome — killed, captured, routed or talked down all pay the same; a defeat or flight pays nothing. Also reports level-up eligibility, advances the clock, writes the ledger event, " + "and — if the player character died — moves the harness to the continuation decision (CHECKPOINT_DECISION, or PLAYER_CHARACTER_TRANSFER under IRONMAN).", annotations = @Tool.Annotations(destructiveHint = false, openWorldHint = false))
	ToolResponse endEncounter(
			@ToolArg(description = OP) String operation_id, @ToolArg(description = REF) String campaign,
			@ToolArg(description = "Outcome") String outcome,
			@ToolArg(description = "Encounter reference (default: the running encounter)") Optional<String> encounter,
			@ToolArg(description = "One-line summary for the ledger") Optional<String> summary) {
		return ToolSupport.run("end_encounter", () -> engine.encounters()
				.end(operation_id, campaign, encounter.orElse(null), outcome, summary.orElse(null)));
	}

	@Tool(name = "apply_runtime_change", description = "MUTATING. A named, rules-aware change outside the attack loop. change.kind: HEAL {amount}; DAMAGE {amount, damage_type}; " + "SET_TEMP_HP {amount}; ADD_CONDITION / REMOVE_CONDITION {condition: BLINDED|CHARMED|…|PRONE|UNCONSCIOUS, duration}; STABILIZE. Plus reason. " + "Death rules apply to DAMAGE (dropping to 0, death-save failures, massive damage). Not a free-form mutation.", annotations = @Tool.Annotations(destructiveHint = false, openWorldHint = false))
	ToolResponse applyRuntimeChange(
			@ToolArg(description = OP) String operation_id, @ToolArg(description = REF) String campaign,
			@ToolArg(description = "Character reference") String character,
			@ToolArg(description = "The change object; see description") Map<String, Object> change) {
		return ToolSupport.run("apply_runtime_change",
				() -> engine.runtime().applyRuntimeChange(operation_id, campaign, character, change));
	}

	@Tool(name = "award_xp", description = "MUTATING. Awards XP from an explicit source (QUEST, MILESTONE, ROLEPLAY, EXPLORATION, CLEVER_SOLUTION, DISCRETIONARY) with a reason. " + "Leave `characters` out and the award follows the campaign's rules.xp_policy — normally the whole party; that is the right call for quests, discoveries and clever solutions. " + "Encounter XP is awarded automatically by end_encounter — do not duplicate it. DISCRETIONARY awards are audited and refused when GM overrides are disabled. " + "Companions that reach a new level are advanced or flagged according to rules.companion_level_up; check companion_level_ups and companions_awaiting_level_up in the response.", annotations = @Tool.Annotations(destructiveHint = false, openWorldHint = false))
	ToolResponse awardXp(
			@ToolArg(description = OP) String operation_id, @ToolArg(description = REF) String campaign,
			@ToolArg(description = "Character references; omit to follow the campaign's xp_policy", required = false)
			List<String> characters, @ToolArg(description = "XP per character") int amount,
			@ToolArg(description = "Source") Optional<String> source, @ToolArg(description = "Why") String reason) {
		return ToolSupport.run("award_xp", () -> engine.runtime()
				.awardXp(operation_id, campaign, characters, amount, source.orElse(null), reason));
	}

	@Tool(name = "transfer_player_control", description = "MUTATING. Makes a living party member the player character (IRONMAN death, or a voluntary switch). " + "The character keeps its identity, relationships, memories and items; only the control assignment changes.", annotations = @Tool.Annotations(destructiveHint = false, openWorldHint = false))
	ToolResponse transferPlayerControl(
			@ToolArg(description = OP) String operation_id, @ToolArg(description = REF) String campaign,
			@ToolArg(description = "Character reference to take control of") String to,
			@ToolArg(description = "Why") Optional<String> reason) {
		return ToolSupport.run("transfer_player_control",
				() -> engine.runtime().transferControl(operation_id, campaign, to, reason.orElse(null)));
	}

	@Tool(name = "get_party", description = "The party in detail — ALWAYS available, in every state (setup, exploration, mid-encounter, after a death): " + "every current member's sheet (HP, conditions, death saves, AC, abilities, inventory, money, XP), who the player controls, former/fallen members, " + "location, game time and any running encounter. Use it (with get_character_sheet FULL) whenever the player asks how they or their companions are doing. Read-only.", annotations = @Tool.Annotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false))
	ToolResponse getParty(
			@ToolArg(description = REF) String campaign,
			@ToolArg(description = "SUMMARY, PLAY (default) or FULL per member") Optional<String> detail) {
		return ToolSupport.run("get_party", () -> engine.sessions().party(campaign, detail.orElse("PLAY")));
	}
	// ── Progression ────────────────────────────────────────────────────

	@Tool(name = "begin_level_up", description = "MUTATING. Opens a level-up transaction for a character whose XP reaches the next level (one level at a time). " + "Nothing changes on the live character until commit_level_up. Hit points are fixed at begin by campaign rules.hp_progression " + "(rolled openly when the policy calls for it); the remaining choice arrives at ASI levels: " + "the Ability Score Improvement. A companion recruited as a stat block has no class: their first level-up is the class choice itself (choices.class), " + "which replaces the stat block's hit points with the class's own and grants its saving throws. " + "The harness enters LEVEL_UP until commit or abandon_transaction.", annotations = @Tool.Annotations(destructiveHint = false, openWorldHint = false))
	ToolResponse beginLevelUp(
			@ToolArg(description = OP) String operation_id, @ToolArg(description = REF) String campaign,
			@ToolArg(description = "Character reference") String character) {
		return ToolSupport.run("begin_level_up", () -> engine.levelUps().begin(operation_id, campaign, character));
	}

	@Tool(name = "get_level_up_choices", description = "Legal remaining level-up choices, what was chosen so far, automatic changes and a before/after preview. Read-only.", annotations = @Tool.Annotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false))
	ToolResponse getLevelUpChoices(
			@ToolArg(description = REF) String campaign,
			@ToolArg(description = "Transaction reference (default: the open level-up)") Optional<String> transaction) {
		return ToolSupport.run("get_level_up_choices",
				() -> engine.levelUps().choices(campaign, transaction.orElse(null)));
	}

	@Tool(name = "update_level_up", description = "MUTATING (transaction only). Records choices (hit points are fixed by campaign rules.hp_progression and are not a choice): " + "{\"ability_score_improvement\": {\"CHA\": 2}} or {\"DEX\": 1, \"CON\": 1}. A companion's promotion also takes class, skills, species, background and their follow-ups; " + "feat_choices is one object naming the feat or a list of them (a Human Sage owes both Skilled and Magic Initiate their choices), and a feat left with " + "pending choices can be completed at any later level-up. The live character is untouched.", annotations = @Tool.Annotations(destructiveHint = false, openWorldHint = false))
	ToolResponse updateLevelUp(
			@ToolArg(description = OP) String operation_id, @ToolArg(description = REF) String campaign,
			@ToolArg(description = "Choices object") Map<String, Object> choices,
			@ToolArg(description = "Transaction reference (default: the open level-up)") Optional<String> transaction,
			@ToolArg(description = "Transaction revision you last read") Optional<Long> expected_revision) {
		return ToolSupport.run("update_level_up", () -> engine.levelUps()
				.update(operation_id, campaign, transaction.orElse(null), expected_revision.orElse(null), choices));
	}

	@Tool(name = "validate_level_up", description = "Violations and a complete before/after preview of the pending level-up. Read-only.", annotations = @Tool.Annotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false))
	ToolResponse validateLevelUp(
			@ToolArg(description = REF) String campaign,
			@ToolArg(description = "Transaction reference (default: the open level-up)") Optional<String> transaction) {
		return ToolSupport.run("validate_level_up",
				() -> engine.levelUps().validate(campaign, transaction.orElse(null)));
	}

	@Tool(name = "commit_level_up", description = "MUTATING, atomic. Validates and applies the level-up (level, HP maximum and current, ability scores), writes the " + "LEVEL_UP ledger event and returns the harness to EXPLORATION.", annotations = @Tool.Annotations(destructiveHint = false, openWorldHint = false))
	ToolResponse commitLevelUp(
			@ToolArg(description = OP) String operation_id, @ToolArg(description = REF) String campaign,
			@ToolArg(description = "Transaction reference (default: the open level-up)") Optional<String> transaction,
			@ToolArg(description = "Transaction revision you last read") Optional<Long> expected_revision) {
		return ToolSupport.run("commit_level_up", () -> engine.levelUps()
				.commit(operation_id, campaign, transaction.orElse(null), expected_revision.orElse(null)));
	}

	@Tool(name = "abandon_transaction", description = "MUTATING. Discards an open pending transaction (e.g. a level-up); canonical state is unchanged.", annotations = @Tool.Annotations(destructiveHint = false, openWorldHint = false))
	ToolResponse abandonTransaction(
			@ToolArg(description = OP) String operation_id, @ToolArg(description = REF) String campaign,
			@ToolArg(description = "Transaction reference (default: the open transaction)")
			Optional<String> transaction, @ToolArg(description = "Why") Optional<String> reason) {
		return ToolSupport.run("abandon_transaction",
				() -> engine.levelUps().abandon(operation_id, campaign, transaction.orElse(null), reason.orElse(null)));
	}

	// ── Party and relationships ────────────────────────────────────────

	@Tool(name = "update_party_membership", description = "MUTATING. One typed membership change for a character: JOIN, LEAVE, DISMISS, SEPARATE (temporary), REJOIN, " + "GUEST_ADD, GUEST_REMOVE. Validated against the current membership state, recorded as a ledger event the membership row references. " + "The player character cannot leave (transfer_player_control first). Membership is state, never identity.", annotations = @Tool.Annotations(destructiveHint = false, openWorldHint = false))
	ToolResponse updatePartyMembership(
			@ToolArg(description = OP) String operation_id, @ToolArg(description = REF) String campaign,
			@ToolArg(description = "Character reference") String character,
			@ToolArg(description = "JOIN | LEAVE | DISMISS | SEPARATE | REJOIN | GUEST_ADD | GUEST_REMOVE")
			String change, @ToolArg(description = "Why (goes into the ledger event)") Optional<String> reason) {
		return ToolSupport.run("update_party_membership",
				() -> engine.party().updateMembership(operation_id, campaign, character, change, reason.orElse(null)));
	}

	@Tool(name = "get_relationship", description = "Both directions of a relationship between two characters: compact summary, dimensions (-5..+5 with labels) and the " + "significant ledger events that shaped it, with their summaries and episodic detail — this is how you recall \"do you remember when I proposed?\". Read-only.", annotations = @Tool.Annotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false))
	ToolResponse getRelationship(
			@ToolArg(description = REF) String campaign, @ToolArg(description = "First character reference") String a,
			@ToolArg(description = "Second character reference") String b) {
		return ToolSupport.run("get_relationship", () -> engine.party().relationship(campaign, a, b));
	}

	@Tool(name = "update_relationship", description = "MUTATING (upsert). Records a meaningful relationship development from `from` towards `to`: dimensions " + "({\"trust\": \"+1\", \"affection\": 3} — strings with a sign are deltas, numbers are absolute; affection, trust, respect, attraction, fear, resentment, loyalty; -5..+5), " + "a concise current summary, and a cause: cause_event (event:N whose participants include both — the event is linked as a significant memory) or a reason. " + "mutual=true applies the same change in both directions. Never converts a Director seed into a predetermined outcome.", annotations = @Tool.Annotations(destructiveHint = false, openWorldHint = false))
	ToolResponse updateRelationship(
			@ToolArg(description = OP) String operation_id, @ToolArg(description = REF) String campaign,
			@ToolArg(description = "Character whose feelings change") String from,
			@ToolArg(description = "Character the feelings are about") String to,
			@ToolArg(description = "Dimension changes", required = false) Map<String, Object> dimensions,
			@ToolArg(description = "Concise current summary of how `from` relates to `to` now")
			Optional<String> summary,
			@ToolArg(description = "Ledger event reference that caused/embodies this development")
			Optional<String> cause_event,
			@ToolArg(description = "Reason when no event is linked") Optional<String> reason,
			@ToolArg(description = "Apply in both directions (default false)") Optional<Boolean> mutual,
			@ToolArg(description = "GM (default) or DIRECTOR") Optional<String> provenance) {
		return ToolSupport.run("update_relationship", () -> engine.party()
				.updateRelationship(operation_id, campaign, from, to, dimensions, summary.orElse(null),
						cause_event.orElse(null), reason.orElse(null), mutual.orElse(false), provenance.orElse(null)));
	}
	// ── World ──────────────────────────────────────────────────────────

	@Tool(name = "materialize_location", description = "MUTATING. Generates and persists detail for a place, once, so it stays the same next year: pass an existing " + "location reference (a semantic node) and/or a spec {name, kind: REGION|SETTLEMENT|DISTRICT|SITE|BUILDING|AREA, parent, description, tags, " + "features: [{name, description, visibility}], secrets: [{name, description}] (GM_ONLY), children: [{name, kind, description}], " + "connections: [{to: 'location:N' or {name, kind}, kind: ROAD|PATH|RIVER|STREET|DOOR|PASSAGE…, travel_minutes, distance_miles, state: OPEN|LOCKED|BLOCKED|SECRET, visibility}]}. " + "Committed features cannot be redefined (extend=true adds more). Dungeons are AREA children joined by DOOR/PASSAGE connections.", annotations = @Tool.Annotations(destructiveHint = false, openWorldHint = false))
	ToolResponse materializeLocation(
			@ToolArg(description = OP) String operation_id, @ToolArg(description = REF) String campaign,
			@ToolArg(description = "Existing location reference to materialize (omit to create a new place from spec)")
			Optional<String> location,
			@ToolArg(description = "Location spec; see description", required = false) Map<String, Object> spec) {
		return ToolSupport.run("materialize_location",
				() -> engine.world().materialize(operation_id, campaign, location.orElse(null), spec, "GM"));
	}

	@Tool(name = "move_party", description = "MUTATING. Moves the party (or named characters) to a location over known traversable connections, advancing the clock by " + "the route's travel time; with no known route, pass authorized_route=true with a reason and travel_minutes (the route is then remembered). " + "Returns the arrival view, elapsed time and a Director trigger for long journeys. Interruptions arrive in a later milestone.", annotations = @Tool.Annotations(destructiveHint = false, openWorldHint = false))
	ToolResponse moveParty(
			@ToolArg(description = OP) String operation_id, @ToolArg(description = REF) String campaign,
			@ToolArg(description = "Destination location reference") String to,
			@ToolArg(description = "Subset of character references (default: the whole party)")
			Optional<List<String>> characters,
			@ToolArg(description = "Allow a route with no known connection (requires reason)")
			Optional<Boolean> authorized_route,
			@ToolArg(description = "Override the travel time in minutes") Optional<Integer> travel_minutes,
			@ToolArg(description = "Why / how they travel") Optional<String> reason) {
		return ToolSupport.run("move_party", () -> engine.world()
				.move(operation_id, campaign, to, characters.orElse(null), authorized_route.orElse(false),
						travel_minutes.orElse(null), reason.orElse(null)));
	}

	// ── Narrative state and the Director ───────────────────────────────

	@Tool(name = "upsert_narrative_state", description = "MUTATING. Creates or updates one bounded narrative aggregate. kind: QUEST {title, objective, status: OFFERED|ACCEPTED|COMPLETED|FAILED|ABANDONED, " + "issuer, rewards, deadline, hidden_objectives, visibility}; STORY_BEAT {title, description, state: PLANNED|AVAILABLE|BLOCKED|SUPERSEDED|COMPLETED|ABANDONED, superseded_by}; " + "STORY_SEED {seed_kind: STORY_SEED|COMPANION_INTRO|PRESSURE|PACING_INTENT, intention, hooks, conditions, state, superseded_by, materialized_character}; " + "FACTION_STATE {name, goals, standing, agenda, members, secrets}; WORLD_EVENT {title, description, channels: [NEWSPAPER, RUMOR, TAVERN, TOWN_CRIER, REFUGEES, MERCHANT, PRICES, SOLDIERS, LETTER, WITNESS, ENVIRONMENT…], " + "locations: [location:N], affected, game_time, visibility}; LOCATION_DETAIL {description, tags, features} (existing location ref); NPC_AGENDA {goals, fears, secrets, current_plan} (character ref). " + "Pass ref to update. Status/state transitions write ledger events. Fields that would force a player choice or relationship outcome are rejected.", annotations = @Tool.Annotations(destructiveHint = false, openWorldHint = false))
	ToolResponse upsertNarrativeState(
			@ToolArg(description = OP) String operation_id, @ToolArg(description = REF) String campaign,
			@ToolArg(description = "Aggregate kind") String kind,
			@ToolArg(description = "Fields; see description") Map<String, Object> changes,
			@ToolArg(description = "Existing reference to update (quest:N, story_beat:N, seed:N, faction:N, world_event:N, location:N, character:N)")
			Optional<String> ref,
			@ToolArg(description = "GM (default), DIRECTOR or MECHANICAL_CONSEQUENCE") Optional<String> provenance) {
		return ToolSupport.run("upsert_narrative_state", () -> engine.narrative()
				.upsert(operation_id, campaign, kind, ref.orElse(null), changes, provenance.orElse(null)));
	}

	@Tool(name = "get_diegetic_information", description = "What the world can tell the player right now through a fictional channel (NEWSPAPER, RUMOR, TAVERN, TOWN_CRIER, " + "REFUGEES, MERCHANT, PRICES, SOLDIERS, LETTER, WITNESS, ENVIRONMENT, or ANY) at a location: committed world events that have already happened and touch this place " + "(or the whole world). Asking never creates a fact. Read-only.", annotations = @Tool.Annotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false))
	ToolResponse getDiegeticInformation(
			@ToolArg(description = REF) String campaign,
			@ToolArg(description = "Channel (default ANY)") Optional<String> channel,
			@ToolArg(description = "Location reference (default: current location)") Optional<String> location,
			@ToolArg(description = "Max items (default 10)") Optional<Integer> limit) {
		return ToolSupport.run("get_diegetic_information", () -> engine.narrative()
				.diegetic(campaign, channel.orElse(null), location.orElse(null), limit.orElse(10)));
	}

	@Tool(name = "get_director_context", description = "DIRECTOR-ONLY view for the slow narrative loop (invoke at meaningful boundaries, not every turn): premise and background truth, " + "open seeds and companion intentions, story beats (incl. blocked/invalidated plans), quests, factions, world events, party, relationships, NPC agendas, recent major events, " + "pacing composition and when the last review happened. Read-only.", annotations = @Tool.Annotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false))
	ToolResponse getDirectorContext(
			@ToolArg(description = REF) String campaign,
			@ToolArg(description = "INITIAL_CAMPAIGN, SESSION_START or CAMPAIGN_REVIEW (default)")
			Optional<String> scope) {
		return ToolSupport.run("get_director_context",
				() -> engine.narrative().directorContext(campaign, scope.orElse(null)));
	}

	@Tool(name = "commit_director_changes", description = "MUTATING, atomic. Commits a list of typed Director proposals: [{kind: STORY_SEED|PRESSURE|PACING_INTENT|COMPANION_INTRO|STORY_BEAT|QUEST|FACTION_STATE|WORLD_EVENT|LOCATION_DETAIL|NPC_AGENDA, ref?, …fields}]. " + "Seeds and pressures create possibilities, world events create facts with diegetic channels; nothing may prescribe a player choice or a relationship outcome. " + "An empty list is valid and still records that a review occurred.", annotations = @Tool.Annotations(destructiveHint = false, openWorldHint = false))
	ToolResponse commitDirectorChanges(
			@ToolArg(description = OP) String operation_id, @ToolArg(description = REF) String campaign,
			@ToolArg(description = "Proposals; may be empty", required = false) List<Map<String, Object>> changes,
			@ToolArg(description = "One-line note on the review's conclusion") Optional<String> review_note) {
		return ToolSupport.run("commit_director_changes", () -> engine.narrative()
				.commitDirectorChanges(operation_id, campaign, changes, review_note.orElse(null)));
	}

	@Tool(name = "get_context", description = "Purpose-built context for one scope: SCENE (like bootstrap, read-only), CHARACTER {ref}, RELATIONSHIP {ref, second_ref}, " + "LOCATION {ref, default current}, QUEST {ref optional}, ENCOUNTER, DIRECTOR. Visibility labels apply. Read-only.", annotations = @Tool.Annotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false))
	ToolResponse getContext(
			@ToolArg(description = REF) String campaign, @ToolArg(description = "Scope") String scope,
			@ToolArg(description = "Primary reference for the scope") Optional<String> ref,
			@ToolArg(description = "Second reference (RELATIONSHIP)") Optional<String> second_ref,
			@ToolArg(description = "Natural-language focus (includes episodic detail for RELATIONSHIP)")
			Optional<String> focus,
			@ToolArg(description = "Approximate token budget") Optional<Integer> context_budget) {
		return ToolSupport.run("get_context", () -> engine.narrative()
				.context(campaign, scope, ref.orElse(null), second_ref.orElse(null), focus.orElse(null),
						context_budget.orElse(null)));
	}

	// ── Rest and overrides ─────────────────────────────────────────────

	@Tool(name = "perform_rest", description = "MUTATING. SHORT rest (1 hour; characters may spend Hit Point Dice: hit_dice {\"character:1\": 2} — rolled by the server) or LONG rest " + "(8 hours; HP to maximum, half the Hit Point Dice regained, exhaustion −1, other resources recharged). Advances the clock and records the rest. Not legal mid-encounter.", annotations = @Tool.Annotations(destructiveHint = false, openWorldHint = false))
	ToolResponse performRest(
			@ToolArg(description = OP) String operation_id, @ToolArg(description = REF) String campaign,
			@ToolArg(description = "SHORT or LONG") String kind,
			@ToolArg(description = "Hit Point Dice to spend per character on a short rest", required = false)
			Map<String, Object> hit_dice,
			@ToolArg(description = "Resting characters (default: the party)") Optional<List<String>> characters) {
		return ToolSupport.run("perform_rest",
				() -> engine.rest().rest(operation_id, campaign, kind, hit_dice, characters.orElse(null)));
	}

	@Tool(name = "apply_gm_override", description = "MUTATING, AUDITED. The only general exceptional mutation, permitted only when the campaign's gm_override_policy is EXPLICIT_AUDITED. " + "kind: ADJUST_HP {amount|set_to}, SET_MAX_HP {amount|set_to} (the only way to record a maximum granted outside a level-up: a subclass feature, a boon, a permanent injury), " + "SET_ARMOR_CLASS {armor_class} or {clear: true} (a fixed AC outside the equipment path — Draconic Resilience, Unarmored Defense; effect bonuses still apply on top), " + "SET_LIFE_STATE {life_state, hp}, SET_MONEY {money}, SET_XP {xp}, SET_ABILITY_SCORE {ability, score}, SET_LOCATION {location}, " + "SET_CAMPAIGN_RULE {rule, value} (retunes a committed house rule: hp_progression, xp_policy, companion_level_up, progression, gm_override_policy; no target), " + "REMOVE_ENCOUNTER_PARTICIPANT, SET_CONNECTION_STATE {to, state}. Requires a reason; writes an immutable audit record and a GM_ONLY ledger event; " + "the result is labeled as an override and must never be narrated as a normal roll.", annotations = @Tool.Annotations(destructiveHint = true, openWorldHint = false))
	ToolResponse applyGmOverride(
			@ToolArg(description = OP) String operation_id, @ToolArg(description = REF) String campaign,
			@ToolArg(description = "Override kind") String kind,
			@ToolArg(description = "Target reference (character:N or location:N); omit for SET_CAMPAIGN_RULE",
					required = false) String target,
			@ToolArg(description = "Effect object; see description", required = false) Map<String, Object> effect,
			@ToolArg(description = "Human-readable reason") String reason,
			@ToolArg(description = "Target revision you last read") Optional<Long> expected_revision) {
		return ToolSupport.run("apply_gm_override", () -> engine.rest()
				.override(operation_id, campaign, kind, target, effect, reason, expected_revision.orElse(null)));
	}
	// ── Spellcasting ───────────────────────────────────────────────────

	@Tool(name = "prepare_spells", description = "MUTATING. Sets an active caster's known cantrips and/or prepared spells (names or ids), validated against the class " + "spell list, the level's cantrip and prepared-spell limits and the highest slot level. Class rules govern *when* the list may change (typically after a Long Rest). " + "During setup use update_character_draft with cantrips/spells instead.", annotations = @Tool.Annotations(destructiveHint = false, openWorldHint = false))
	ToolResponse prepareSpells(
			@ToolArg(description = OP) String operation_id, @ToolArg(description = REF) String campaign,
			@ToolArg(description = "Caster reference") String character,
			@ToolArg(description = "Cantrips (replaces the current list)") Optional<List<String>> cantrips,
			@ToolArg(description = "Prepared spells (replaces the current list)") Optional<List<String>> spells) {
		return ToolSupport.run("prepare_spells", () -> engine.spells()
				.prepare(operation_id, campaign, character, cantrips.map(l -> (List<Object>) (List<?>) l).orElse(null),
						spells.map(l -> (List<Object>) (List<?>) l).orElse(null)));
	}

	@Tool(name = "cast_spell", description = "MUTATING, atomic (outside encounters; in a fight use perform_encounter_action with kind CAST). Validates that the spell is known/prepared, " + "spends the slot (slot_level to upcast; cantrips are free; warlocks use pact slots), and resolves structured mechanics against the targets: spell attacks vs AC, " + "saving throws vs your DC (half/no damage, conditions with durations), healing, Magic Missile, temporary HP, buffs (Mage Armor, Bless, Shield of Faith…), cures, " + "resurrection. Concentration is enforced (a new concentration spell ends the previous one; damage forces a CON save). Utility spells return their rules text for you to adjudicate. " + "options: {against: 'character:N'} for Hex/Hunter's Mark, {damage_type} for Chromatic Orb, {condition} for cures.", annotations = @Tool.Annotations(destructiveHint = false, openWorldHint = false))
	ToolResponse castSpell(
			@ToolArg(description = OP) String operation_id, @ToolArg(description = REF) String campaign,
			@ToolArg(description = "Caster reference") String caster,
			@ToolArg(description = "Spell name or id") String spell,
			@ToolArg(description = "Slot level to use (default: the spell's level)") Optional<Integer> slot_level,
			@ToolArg(description = "Target character references (repeat a target for multi-dart/ray spells; omit for self-only)")
			Optional<List<String>> targets,
			@ToolArg(description = "Spell-specific options; see description", required = false)
			Map<String, Object> options) {
		return ToolSupport.run("cast_spell", () -> engine.spells()
				.cast(operation_id, campaign, caster, spell, slot_level.orElse(null), targets.orElse(null), options));
	}
}
