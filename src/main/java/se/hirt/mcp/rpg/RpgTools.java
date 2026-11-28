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
import se.hirt.mcp.rpg.encounter.EncounterDigest;
import se.hirt.mcp.rpg.ledger.LedgerService;
import se.hirt.mcp.rpg.maps.SceneService;
import se.hirt.mcp.rpg.world.WorldTimeline;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Core consolidated MCP tool surface (protocol 0.2.0). Domain tools dispatch on {@code action} over
 * the same server-authoritative engine services, preserving SQLite idempotency keys, revision
 * checks, harness gating and structured envelopes.
 */
public class RpgTools {

	private static final String CAMP = "campaign:N";
	private static final String CAMP_SESSION = "campaign:N (required except STATE/CREATE)";
	private static final String CAMP_RULES = "campaign:N (optional for SEARCH/DEFINITIONS; required for DEFINE)";
	private static final String OP = "Idempotency key (required for mutating actions)";
	private static final String PARAMS = "Action parameters (see description)";

	@Inject
	Engine engine;

	// ── Guide & session lifecycle ──────────────────────────────────────

	@Tool(name = "get_instructions", description = "READ-ONLY. Call FIRST once per conversation before starting or continuing a campaign. "
			+ "Returns the core GM guide (default topic CORE) or a focused manual on demand: SETUP, COMBAT, MAPS, CHARACTERS, DIRECTOR, ECONOMY, or ALL.", annotations = @Tool.Annotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false))
	ToolResponse getInstructions(
		@ToolArg(description = "CORE (default), SETUP, COMBAT, MAPS, CHARACTERS, DIRECTOR, ECONOMY or ALL", required = false)
		String topic) {
		return ToolSupport.run("get_instructions", () -> {
			String resolved = ProtocolGuide.resolveTopic(topic);
			var out = new LinkedHashMap<String, Object>();
			out.put("topic", resolved);
			out.put("available_topics", ProtocolGuide.TOPICS);
			out.put("guide", ProtocolGuide.read(resolved));
			return out;
		});
	}

	@Tool(name = "session", description = "Campaign discovery and session lifecycle. Actions: "
			+ "STATE (read; no campaign) {include_campaigns?, campaign_limit?, campaign_cursor?}; "
			+ "CREATE (mut; no campaign) {title?, ruleset?}; " + "OPEN (read) {}; "
			+ "BOOTSTRAP (mut) {context_budget?}; "
			+ "SUSPEND (mut) {summary?}. (To end a campaign permanently, use standalone complete_campaign.)", annotations = @Tool.Annotations(destructiveHint = false, openWorldHint = false))
	ToolResponse session(@ToolArg(description = "STATE | CREATE | OPEN | BOOTSTRAP | SUSPEND")
	String action, @ToolArg(description = CAMP_SESSION, required = false)
	String campaign, @ToolArg(description = OP, required = false)
	String operation_id, @ToolArg(description = PARAMS, required = false)
	Map<String, Object> params) {
		return ToolSupport.run("session", () -> {
			String act = ToolArgs.action("session", action, List.of("STATE", "CREATE", "OPEN", "BOOTSTRAP", "SUSPEND"));
			return switch (act) {
			case "STATE" -> {
				ToolArgs a = ToolArgs.of("session", act, "CORE", params,
						"include_campaigns?, campaign_limit?, campaign_cursor?", "include_campaigns", "campaign_limit",
						"campaign_cursor");
				var result = engine.campaigns().serverState(a.optBool("include_campaigns", true),
						a.optInt("campaign_limit", 20), a.optStr("campaign_cursor"));
				result.put("instruction_hint",
						"If you have not read get_instructions in this conversation, call it before starting or continuing a campaign.");
				yield result;
			}
			case "CREATE" -> {
				String op = ToolArgs.reqOp("session", act, operation_id);
				ToolArgs a = ToolArgs.of("session", act, "SETUP", params, "title?, ruleset?", "title", "ruleset");
				yield engine.campaigns().create(op, a.optStr("title"), a.optStr("ruleset"));
			}
			case "OPEN" -> {
				String c = ToolArgs.reqCampaign("session", act, campaign);
				ToolArgs.of("session", act, "CORE", params, "(none)");
				yield engine.campaigns().open(c);
			}
			case "BOOTSTRAP" -> {
				String c = ToolArgs.reqCampaign("session", act, campaign);
				String op = ToolArgs.reqOp("session", act, operation_id);
				ToolArgs a = ToolArgs.of("session", act, "CORE", params, "context_budget?", "context_budget");
				yield engine.sessions().bootstrap(op, c, a.optInt("context_budget"));
			}
			case "SUSPEND" -> {
				String c = ToolArgs.reqCampaign("session", act, campaign);
				String op = ToolArgs.reqOp("session", act, operation_id);
				ToolArgs a = ToolArgs.of("session", act, "CORE", params, "summary?", "summary");
				yield engine.sessions().suspend(op, c, a.optStr("summary"));
			}
			default -> throw new IllegalStateException(act);
			};
		});
	}

	// ── Campaign setup & house rules ───────────────────────────────────

	@Tool(name = "campaign_setup", description = "Resumable campaign setup interview and standing house rules (see get_instructions topic SETUP). Actions: "
			+ "GET (read) {}; "
			+ "UPDATE (mut) {changes, expected_revision?} (legal choices come from returned decisions); "
			+ "VALIDATE (read) {}; " + "COMMIT (mut) {expected_revision?}; "
			+ "PARTY_DESIGN (mut) {preferences, expected_revision?}; "
			+ "HOUSE_RULES (mut) {rules?, mode?: ADD|REMOVE|REPLACE}.", annotations = @Tool.Annotations(destructiveHint = false, openWorldHint = false))
	ToolResponse campaignSetup(@ToolArg(description = "GET | UPDATE | VALIDATE | COMMIT | PARTY_DESIGN | HOUSE_RULES")
	String action, @ToolArg(description = CAMP)
	String campaign, @ToolArg(description = OP, required = false)
	String operation_id, @ToolArg(description = PARAMS, required = false)
	Map<String, Object> params) {
		return ToolSupport.run("campaign_setup", () -> {
			String act = ToolArgs.action("campaign_setup", action,
					List.of("GET", "UPDATE", "VALIDATE", "COMMIT", "PARTY_DESIGN", "HOUSE_RULES"));
			String c = ToolArgs.reqCampaign("campaign_setup", act, campaign);
			return switch (act) {
			case "GET" -> {
				ToolArgs.of("campaign_setup", act, "SETUP", params, "(none)");
				yield engine.campaigns().setupState(c);
			}
			case "UPDATE" -> {
				String op = ToolArgs.reqOp("campaign_setup", act, operation_id);
				ToolArgs a = ToolArgs.of("campaign_setup", act, "SETUP", params, "changes, expected_revision?",
						"changes", "expected_revision");
				yield engine.campaigns().updateSetup(op, c, a.optLong("expected_revision"), a.reqMap("changes"));
			}
			case "VALIDATE" -> {
				ToolArgs.of("campaign_setup", act, "SETUP", params, "(none)");
				yield engine.campaigns().validateSetup(c);
			}
			case "COMMIT" -> {
				String op = ToolArgs.reqOp("campaign_setup", act, operation_id);
				ToolArgs a = ToolArgs.of("campaign_setup", act, "SETUP", params, "expected_revision?",
						"expected_revision");
				yield engine.campaigns().commitSetup(op, c, a.optLong("expected_revision"));
			}
			case "PARTY_DESIGN" -> {
				String op = ToolArgs.reqOp("campaign_setup", act, operation_id);
				ToolArgs a = ToolArgs.of("campaign_setup", act, "SETUP", params, "preferences, expected_revision?",
						"preferences", "expected_revision");
				Object raw = a.reqObj("preferences");
				Map<String, Object> preferences = raw instanceof String s && !s.isBlank()
						? Map.of("authorship", s.trim()) : a.reqMap("preferences");
				yield engine.campaigns().updateSetup(op, c, a.optLong("expected_revision"),
						Map.of("party", preferences));
			}
			case "HOUSE_RULES" -> {
				String op = ToolArgs.reqOp("campaign_setup", act, operation_id);
				ToolArgs a = ToolArgs.of("campaign_setup", act, "CORE", params, "rules?, mode?", "rules", "mode");
				yield engine.sessions().updateHouseRules(op, c, a.optList("rules"), a.optStr("mode"));
			}
			default -> throw new IllegalStateException(act);
			};
		});
	}

	// ── Character creation draft ───────────────────────────────────────

	@Tool(name = "character_draft", description = "Character creation draft workflow during setup (see get_instructions topic SETUP). Actions: "
			+ "CREATE (mut) {initial?, player_controlled?}; " + "CHOICES (read) {scope?, character?}; "
			+ "GENERATE_ABILITIES (mut) {character, method?}; "
			+ "UPDATE (mut) {character, changes, expected_revision?}; " + "VALIDATE (read) {character}; "
			+ "COMMIT (mut) {character, expected_revision?}.", annotations = @Tool.Annotations(destructiveHint = false, openWorldHint = false))
	ToolResponse characterDraft(
		@ToolArg(description = "CREATE | CHOICES | GENERATE_ABILITIES | UPDATE | VALIDATE | COMMIT")
		String action, @ToolArg(description = CAMP)
		String campaign, @ToolArg(description = OP, required = false)
		String operation_id, @ToolArg(description = PARAMS, required = false)
		Map<String, Object> params) {
		return ToolSupport.run("character_draft", () -> {
			String act = ToolArgs.action("character_draft", action,
					List.of("CREATE", "CHOICES", "GENERATE_ABILITIES", "UPDATE", "VALIDATE", "COMMIT"));
			String c = ToolArgs.reqCampaign("character_draft", act, campaign);
			return switch (act) {
			case "CREATE" -> {
				String op = ToolArgs.reqOp("character_draft", act, operation_id);
				ToolArgs a = ToolArgs.of("character_draft", act, "SETUP", params, "initial?, player_controlled?",
						"initial", "player_controlled");
				yield engine.characters().createDraft(op, c, a.optMap("initial"), a.optBool("player_controlled", true));
			}
			case "CHOICES" -> {
				ToolArgs a = ToolArgs.of("character_draft", act, "SETUP", params, "scope?, character?", "scope",
						"character");
				yield engine.characters().choices(c, a.optStr("scope", "ALL"), a.optStr("character"));
			}
			case "GENERATE_ABILITIES" -> {
				String op = ToolArgs.reqOp("character_draft", act, operation_id);
				ToolArgs a = ToolArgs.of("character_draft", act, "SETUP", params, "character, method?", "character",
						"method");
				yield engine.characters().generateAbilityScores(op, c, a.reqStr("character"), a.optStr("method"));
			}
			case "UPDATE" -> {
				String op = ToolArgs.reqOp("character_draft", act, operation_id);
				ToolArgs a = ToolArgs.of("character_draft", act, "SETUP", params,
						"character, changes, expected_revision?", "character", "changes", "expected_revision");
				yield engine.characters().updateDraft(op, c, a.reqStr("character"), a.optLong("expected_revision"),
						a.reqMap("changes"));
			}
			case "VALIDATE" -> {
				ToolArgs a = ToolArgs.of("character_draft", act, "SETUP", params, "character", "character");
				yield engine.characters().validateDraft(c, a.reqStr("character"));
			}
			case "COMMIT" -> {
				String op = ToolArgs.reqOp("character_draft", act, operation_id);
				ToolArgs a = ToolArgs.of("character_draft", act, "SETUP", params, "character, expected_revision?",
						"character", "expected_revision");
				yield engine.characters().commitDraft(op, c, a.reqStr("character"), a.optLong("expected_revision"));
			}
			default -> throw new IllegalStateException(act);
			};
		});
	}

	// ── Level-up progression ───────────────────────────────────────────

	@Tool(name = "level_up", description = "Transactional character level-up and companion class promotion (see get_instructions topic CHARACTERS). Actions: "
			+ "BEGIN (mut) {character}; " + "CHOICES (read) {transaction?}; "
			+ "UPDATE (mut) {choices, transaction?, expected_revision?}; " + "VALIDATE (read) {transaction?}; "
			+ "COMMIT (mut) {transaction?, expected_revision?}; "
			+ "ABANDON (mut) {transaction?, reason?}.", annotations = @Tool.Annotations(destructiveHint = false, openWorldHint = false))
	ToolResponse levelUp(@ToolArg(description = "BEGIN | CHOICES | UPDATE | VALIDATE | COMMIT | ABANDON")
	String action, @ToolArg(description = CAMP)
	String campaign, @ToolArg(description = OP, required = false)
	String operation_id, @ToolArg(description = PARAMS, required = false)
	Map<String, Object> params) {
		return ToolSupport.run("level_up", () -> {
			String act = ToolArgs.action("level_up", action,
					List.of("BEGIN", "CHOICES", "UPDATE", "VALIDATE", "COMMIT", "ABANDON"));
			String c = ToolArgs.reqCampaign("level_up", act, campaign);
			return switch (act) {
			case "BEGIN" -> {
				String op = ToolArgs.reqOp("level_up", act, operation_id);
				ToolArgs a = ToolArgs.of("level_up", act, "CHARACTERS", params, "character", "character");
				yield engine.levelUps().begin(op, c, a.reqStr("character"));
			}
			case "CHOICES" -> {
				ToolArgs a = ToolArgs.of("level_up", act, "CHARACTERS", params, "transaction?", "transaction");
				yield engine.levelUps().choices(c, a.optStr("transaction"));
			}
			case "UPDATE" -> {
				String op = ToolArgs.reqOp("level_up", act, operation_id);
				ToolArgs a = ToolArgs.of("level_up", act, "CHARACTERS", params,
						"choices, transaction?, expected_revision?", "choices", "transaction", "expected_revision");
				yield engine.levelUps().update(op, c, a.optStr("transaction"), a.optLong("expected_revision"),
						a.reqMap("choices"));
			}
			case "VALIDATE" -> {
				ToolArgs a = ToolArgs.of("level_up", act, "CHARACTERS", params, "transaction?", "transaction");
				yield engine.levelUps().validate(c, a.optStr("transaction"));
			}
			case "COMMIT" -> {
				String op = ToolArgs.reqOp("level_up", act, operation_id);
				ToolArgs a = ToolArgs.of("level_up", act, "CHARACTERS", params, "transaction?, expected_revision?",
						"transaction", "expected_revision");
				yield engine.levelUps().commit(op, c, a.optStr("transaction"), a.optLong("expected_revision"));
			}
			case "ABANDON" -> {
				String op = ToolArgs.reqOp("level_up", act, operation_id);
				ToolArgs a = ToolArgs.of("level_up", act, "CHARACTERS", params, "transaction?, reason?", "transaction",
						"reason");
				yield engine.levelUps().abandon(op, c, a.optStr("transaction"), a.optStr("reason"));
			}
			default -> throw new IllegalStateException(act);
			};
		});
	}

	// ── Characters, party, runtime changes, tactical control ───────────

	@Tool(name = "character", description = "Party sheets, NPC materialization, narrative updates, out-of-combat runtime changes, XP and control (see get_instructions topic CHARACTERS). Actions: "
			+ "SHEET (read) {character, detail?: SUMMARY|PLAY|FULL}; "
			+ "PARTY (read, always available) {detail?: SUMMARY|PLAY|FULL}; "
			+ "UPDATE (mut, narrative/biography/wants/intimacy) {character, changes, expected_revision?}; "
			+ "MATERIALIZE (mut, stat block -> character:N) {source, name?, description?, personality?, alignment?, location?, roll_hp?}; "
			+ "RUNTIME_CHANGE (mut, HEAL|USE_ITEM|DAMAGE|SET_TEMP_HP|ADD_CONDITION|REMOVE_CONDITION|STABILIZE|STAND|USE_RESOURCE|RESTORE_RESOURCE|CREATE_SPELL_SLOT|CONVERT_SPELL_SLOT|ADJUST_MAX_HP|RESTORE_MAX_HP) {character, change}; "
			+ "AWARD_XP (mut) {amount, reason, characters?, source?}; "
			+ "MEMBERSHIP (mut) {character, change: JOIN|LEAVE|DISMISS|SEPARATE|REJOIN|GUEST_ADD|GUEST_REMOVE, reason?}; "
			+ "TACTICAL_CONTROL (mut) {controller: DM|PLAYER, characters?, scope?: ENCOUNTER|UNTIL_RELEASED, encounter?, reason?}; "
			+ "TRANSFER_CONTROL (mut) {to, reason?}.", annotations = @Tool.Annotations(destructiveHint = false, openWorldHint = false))
	ToolResponse character(
		@ToolArg(description = "SHEET | PARTY | UPDATE | MATERIALIZE | RUNTIME_CHANGE | AWARD_XP | MEMBERSHIP | TACTICAL_CONTROL | TRANSFER_CONTROL")
		String action, @ToolArg(description = CAMP)
		String campaign, @ToolArg(description = OP, required = false)
		String operation_id, @ToolArg(description = PARAMS, required = false)
		Map<String, Object> params) {
		return ToolSupport.run("character", () -> {
			String act = ToolArgs.action("character", action, List.of("SHEET", "PARTY", "UPDATE", "MATERIALIZE",
					"RUNTIME_CHANGE", "AWARD_XP", "MEMBERSHIP", "TACTICAL_CONTROL", "TRANSFER_CONTROL"));
			String c = ToolArgs.reqCampaign("character", act, campaign);
			return switch (act) {
			case "SHEET" -> {
				ToolArgs a = ToolArgs.of("character", act, "CHARACTERS", params, "character, detail?", "character",
						"detail");
				yield engine.characters().characterSheet(c, a.reqStr("character"), a.optStr("detail", "PLAY"));
			}
			case "PARTY" -> {
				ToolArgs a = ToolArgs.of("character", act, "CORE", params, "detail?", "detail");
				yield engine.sessions().party(c, a.optStr("detail", "PLAY"));
			}
			case "UPDATE" -> {
				String op = ToolArgs.reqOp("character", act, operation_id);
				ToolArgs a = ToolArgs.of("character", act, "CHARACTERS", params,
						"character, changes, expected_revision?", "character", "changes", "expected_revision");
				yield engine.characters().updateCharacter(op, c, a.reqStr("character"), a.optLong("expected_revision"),
						a.reqMap("changes"));
			}
			case "MATERIALIZE" -> {
				String op = ToolArgs.reqOp("character", act, operation_id);
				ToolArgs a = ToolArgs.of("character", act, "CHARACTERS", params,
						"source, name?, description?, personality?, alignment?, location?, roll_hp?", "source", "name",
						"description", "personality", "alignment", "location", "roll_hp");
				yield engine.runtime().materialize(op, c, a.reqStr("source"), a.optStr("name"), a.optStr("description"),
						a.optStr("personality"), a.optStr("alignment"), a.optStr("location"),
						a.optBool("roll_hp", false));
			}
			case "RUNTIME_CHANGE" -> {
				String op = ToolArgs.reqOp("character", act, operation_id);
				ToolArgs a = ToolArgs.of("character", act, "CHARACTERS", params, "character, change", "character",
						"change");
				yield engine.runtime().applyRuntimeChange(op, c, a.reqStr("character"), a.reqMap("change"));
			}
			case "AWARD_XP" -> {
				String op = ToolArgs.reqOp("character", act, operation_id);
				ToolArgs a = ToolArgs.of("character", act, "CHARACTERS", params, "amount, reason, characters?, source?",
						"amount", "reason", "characters", "source");
				yield engine.runtime().awardXp(op, c, a.optStringList("characters"), a.reqInt("amount"),
						a.optStr("source"), a.reqStr("reason"));
			}
			case "MEMBERSHIP" -> {
				String op = ToolArgs.reqOp("character", act, operation_id);
				ToolArgs a = ToolArgs.of("character", act, "CHARACTERS", params, "character, change, reason?",
						"character", "change", "reason");
				yield engine.party().updateMembership(op, c, a.reqStr("character"), a.reqStr("change"),
						a.optStr("reason"));
			}
			case "TACTICAL_CONTROL" -> {
				String op = ToolArgs.reqOp("character", act, operation_id);
				ToolArgs a = ToolArgs.of("character", act, "CORE", params,
						"controller, characters?, scope?, encounter?, reason?", "controller", "characters", "scope",
						"encounter", "reason");
				yield engine.tacticalControl().set(op, c, a.optStringList("characters"), a.reqStr("controller"),
						a.optStr("scope"), a.optStr("encounter"), a.optStr("reason"));
			}
			case "TRANSFER_CONTROL" -> {
				String op = ToolArgs.reqOp("character", act, operation_id);
				ToolArgs a = ToolArgs.of("character", act, "CORE", params, "to, reason?", "to", "reason");
				yield engine.runtime().transferControl(op, c, a.reqStr("to"), a.optStr("reason"));
			}
			default -> throw new IllegalStateException(act);
			};
		});
	}

	// ── Server dice & checks ───────────────────────────────────────────

	@Tool(name = "roll_check", description = "Authoritative server dice and checks (mutating/journaled; never supply die results). Actions: "
			+ "CHECK {actor, kind: ABILITY_CHECK|SKILL_CHECK|SAVING_THROW, ability?, skill?, tool?, difficulty?, advantage?: NONE|ADVANTAGE|DISADVANTAGE, reason?}; "
			+ "DICE {expression (e.g. '2d6+3', '4d6dl1'), actor?, reason?}.", annotations = @Tool.Annotations(destructiveHint = false, openWorldHint = false))
	ToolResponse rollCheck(@ToolArg(description = "CHECK | DICE")
	String action, @ToolArg(description = CAMP)
	String campaign, @ToolArg(description = OP)
	String operation_id, @ToolArg(description = PARAMS)
	Map<String, Object> params) {
		return ToolSupport.run("roll_check", () -> {
			String act = ToolArgs.action("roll_check", action, List.of("CHECK", "DICE"));
			String c = ToolArgs.reqCampaign("roll_check", act, campaign);
			String op = ToolArgs.reqOp("roll_check", act, operation_id);
			return switch (act) {
			case "CHECK" -> {
				ToolArgs a = ToolArgs.of("roll_check", act, "CORE", params,
						"actor, kind, ability?, skill?, tool?, difficulty?, advantage?, reason?", "actor", "kind",
						"ability", "skill", "tool", "difficulty", "advantage", "reason");
				yield engine.checks().resolveCheck(op, c, a.reqStr("actor"), a.reqStr("kind"), a.optStr("ability"),
						a.optStr("skill"), a.optStr("tool"), a.optInt("difficulty"), a.optStr("advantage"),
						a.optStr("reason"));
			}
			case "DICE" -> {
				ToolArgs a = ToolArgs.of("roll_check", act, "CORE", params, "expression, actor?, reason?", "expression",
						"actor", "reason");
				yield engine.checks().rollDice(op, c, a.reqStr("expression"), a.optStr("actor"), a.optStr("reason"));
			}
			default -> throw new IllegalStateException(act);
			};
		});
	}

	// ── Encounters & combat ────────────────────────────────────────────

	@Tool(name = "encounter", description = "Tactical grid encounters, turn resolution, reactions and post-combat digests (see get_instructions topic COMBAT). Actions: "
			+ "START (mut) {sides, stances?, zones?, environment?, objectives?, location?, options?}; "
			+ "STATE (read) {encounter?, log_limit?, audience?: PLAYER|GM}; "
			+ "ACTION (mut) {actor, action: {kind: ATTACK|CAST|DODGE|DASH|MOVE|STAND|DISENGAGE|HELP|USE_ITEM|INTERACT|OTHER_RULES_ACTION|END_TURN, ...}, encounter?, end_turn?}; "
			+ "RESOLVE_CHOICE (mut) {transaction, choice: {option: TAKE|DECLINE|CAST_SHIELD, weapon?, nonlethal?}}; "
			+ "END (mut) {outcome: PARTY_VICTORY|PARTY_DEFEAT|PARTY_FLED|ENEMIES_FLED|NEGOTIATED|OTHER, encounter?, summary?, narrative?}; "
			+ "ANNOTATE (mut) {encounter, narrations}; " + "ADD_PARTICIPANTS (mut) {encounter, sides}; "
			+ "RECOVER_AMMO (mut) {encounter, character, selection?}; "
			+ "CONVERT_TO_GRID (mut) {encounter, options?}.", annotations = @Tool.Annotations(destructiveHint = false, openWorldHint = false))
	ToolResponse encounter(
		@ToolArg(description = "START | STATE | ACTION | RESOLVE_CHOICE | END | ANNOTATE | ADD_PARTICIPANTS | RECOVER_AMMO | CONVERT_TO_GRID")
		String action, @ToolArg(description = CAMP)
		String campaign, @ToolArg(description = OP, required = false)
		String operation_id, @ToolArg(description = PARAMS, required = false)
		Map<String, Object> params) {
		return ToolSupport.run("encounter", () -> {
			String act = ToolArgs.action("encounter", action, List.of("START", "STATE", "ACTION", "RESOLVE_CHOICE",
					"END", "ANNOTATE", "ADD_PARTICIPANTS", "RECOVER_AMMO", "CONVERT_TO_GRID"));
			String c = ToolArgs.reqCampaign("encounter", act, campaign);
			return switch (act) {
			case "START" -> {
				String op = ToolArgs.reqOp("encounter", act, operation_id);
				ToolArgs a = ToolArgs.of("encounter", act, "COMBAT", params,
						"sides, stances?, zones?, environment?, objectives?, location?, options?", "sides", "stances",
						"zones", "environment", "objectives", "location", "options");
				yield engine.encounters().start(op, c, a.reqMap("sides"), a.optMap("stances"), a.optMap("zones"),
						a.optStr("environment"), a.optStringList("objectives"), a.optStr("location"),
						a.optMap("options"));
			}
			case "STATE" -> {
				ToolArgs a = ToolArgs.of("encounter", act, "COMBAT", params, "encounter?, log_limit?, audience?",
						"encounter", "log_limit", "audience");
				yield engine.encounters().encounterState(c, a.optStr("encounter"), a.optInt("log_limit", 10),
						a.optStr("audience", "PLAYER"));
			}
			case "ACTION" -> {
				String op = ToolArgs.reqOp("encounter", act, operation_id);
				ToolArgs a = ToolArgs.of("encounter", act, "COMBAT", params, "actor, action, encounter?, end_turn?",
						"actor", "action", "encounter", "end_turn");
				yield engine.encounters().perform(op, c, a.optStr("encounter"), a.reqStr("actor"), a.reqMap("action"),
						a.optBool("end_turn"));
			}
			case "RESOLVE_CHOICE" -> {
				String op = ToolArgs.reqOp("encounter", act, operation_id);
				ToolArgs a = ToolArgs.of("encounter", act, "COMBAT", params, "transaction, choice", "transaction",
						"choice");
				yield engine.encounters().resolveChoice(op, c, a.reqStr("transaction"), a.reqMap("choice"));
			}
			case "END" -> {
				String op = ToolArgs.reqOp("encounter", act, operation_id);
				ToolArgs a = ToolArgs.of("encounter", act, "COMBAT", params,
						"outcome, encounter?, summary?, narrative?", "outcome", "encounter", "summary", "narrative");
				var result = new LinkedHashMap<>(engine.encounters().end(op, c, a.optStr("encounter"),
						a.reqStr("outcome"), a.optStr("summary"), a.optStr("narrative")));
				result.remove("record");
				var digest = LedgerService.boundedRecallDigest(
						EncounterDigest.compactPublic(SceneService.map(result.get("party_digest"))));
				result.put("party_digest", digest);
				result.put("party_digest_bytes", LedgerService.serializedBytes(digest));
				result.put("party_digest_max_bytes", LedgerService.ENCOUNTER_DIGEST_MAX_BYTES);
				result.put("full_gm_record",
						Map.of("tool", "encounter", "arguments",
								Map.of("action", "STATE", "campaign", c, "params",
										Map.of("encounter", result.get("encounter"), "audience", "GM")),
								"field", "record"));
				yield result;
			}
			case "ANNOTATE" -> {
				String op = ToolArgs.reqOp("encounter", act, operation_id);
				ToolArgs a = ToolArgs.of("encounter", act, "COMBAT", params, "encounter, narrations", "encounter",
						"narrations");
				yield engine.encounters().annotate(op, c, a.reqStr("encounter"), a.reqStringMap("narrations"));
			}
			case "ADD_PARTICIPANTS" -> {
				String op = ToolArgs.reqOp("encounter", act, operation_id);
				ToolArgs a = ToolArgs.of("encounter", act, "COMBAT", params, "encounter, sides", "encounter", "sides");
				yield engine.encounters().addParticipants(op, c, a.reqStr("encounter"), a.reqStringListMap("sides"));
			}
			case "RECOVER_AMMO" -> {
				String op = ToolArgs.reqOp("encounter", act, operation_id);
				ToolArgs a = ToolArgs.of("encounter", act, "COMBAT", params, "encounter, character, selection?",
						"encounter", "character", "selection");
				yield engine.encounters().recoverAmmunition(op, c, a.reqStr("encounter"), a.reqStr("character"),
						a.optMapList("selection"));
			}
			case "CONVERT_TO_GRID" -> {
				String op = ToolArgs.reqOp("encounter", act, operation_id);
				ToolArgs a = ToolArgs.of("encounter", act, "COMBAT", params, "encounter, options?", "encounter",
						"options");
				yield engine.encounters().convertToGrid(op, c, a.reqStr("encounter"), a.optMapOrEmpty("options"));
			}
			default -> throw new IllegalStateException(act);
			};
		});
	}

	// ── Spellcasting ───────────────────────────────────────────────────

	@Tool(name = "spellcasting", description = "Out-of-combat spell preparation and casting (in combat use encounter:ACTION CAST; see get_instructions topic COMBAT). Actions: "
			+ "PREPARE (mut) {character, cantrips?, spells?}; "
			+ "CAST (mut) {caster, spell, slot_level?, targets?, options?: {ritual?, damage_type?, against?, condition?, weapon?, metamagic?}}.", annotations = @Tool.Annotations(destructiveHint = false, openWorldHint = false))
	ToolResponse spellcasting(@ToolArg(description = "PREPARE | CAST")
	String action, @ToolArg(description = CAMP)
	String campaign, @ToolArg(description = OP)
	String operation_id, @ToolArg(description = PARAMS)
	Map<String, Object> params) {
		return ToolSupport.run("spellcasting", () -> {
			String act = ToolArgs.action("spellcasting", action, List.of("PREPARE", "CAST"));
			String c = ToolArgs.reqCampaign("spellcasting", act, campaign);
			String op = ToolArgs.reqOp("spellcasting", act, operation_id);
			return switch (act) {
			case "PREPARE" -> {
				ToolArgs a = ToolArgs.of("spellcasting", act, "COMBAT", params, "character, cantrips?, spells?",
						"character", "cantrips", "spells");
				List<String> cantrips = a.optStringList("cantrips");
				List<String> spells = a.optStringList("spells");
				yield engine.spells().prepare(op, c, a.reqStr("character"),
						cantrips == null ? null : List.copyOf(cantrips), spells == null ? null : List.copyOf(spells));
			}
			case "CAST" -> {
				ToolArgs a = ToolArgs.of("spellcasting", act, "COMBAT", params,
						"caster, spell, slot_level?, targets?, options?", "caster", "spell", "slot_level", "targets",
						"options");
				yield engine.spells().cast(op, c, a.reqStr("caster"), a.reqStr("spell"), a.optInt("slot_level"),
						a.optStringList("targets"), a.optMap("options"));
			}
			default -> throw new IllegalStateException(act);
			};
		});
	}

	// ── Rests & campaign clock ─────────────────────────────────────────

	@Tool(name = "rest_time", description = "Rests, advancing the campaign clock and resumable timed activities (all mutating; interruptions stop time early). Actions: "
			+ "REST {kind: SHORT|LONG, hit_dice?, characters?}; " + "ADVANCE {minutes, reason?}; "
			+ "TIMED_ACTION {kind: SEARCH|WAIT|WORK, duration_seconds, action_key, parameters?}.", annotations = @Tool.Annotations(destructiveHint = false, openWorldHint = false))
	ToolResponse restTime(@ToolArg(description = "REST | ADVANCE | TIMED_ACTION")
	String action, @ToolArg(description = CAMP)
	String campaign, @ToolArg(description = OP)
	String operation_id, @ToolArg(description = PARAMS)
	Map<String, Object> params) {
		return ToolSupport.run("rest_time", () -> {
			String act = ToolArgs.action("rest_time", action, List.of("REST", "ADVANCE", "TIMED_ACTION"));
			String c = ToolArgs.reqCampaign("rest_time", act, campaign);
			String op = ToolArgs.reqOp("rest_time", act, operation_id);
			return switch (act) {
			case "REST" -> {
				ToolArgs a = ToolArgs.of("rest_time", act, "CORE", params, "kind, hit_dice?, characters?", "kind",
						"hit_dice", "characters");
				yield engine.rest().rest(op, c, a.reqStr("kind"), a.optMap("hit_dice"), a.optStringList("characters"));
			}
			case "ADVANCE" -> {
				ToolArgs a = ToolArgs.of("rest_time", act, "CORE", params, "minutes, reason?", "minutes", "reason");
				yield engine.sessions().advanceTime(op, c, a.reqInt("minutes"), a.optStr("reason"));
			}
			case "TIMED_ACTION" -> {
				ToolArgs a = ToolArgs.of("rest_time", act, "ECONOMY", params,
						"kind, duration_seconds, action_key, parameters?", "kind", "duration_seconds", "action_key",
						"parameters");
				yield new WorldTimeline(engine.db()).perform(op, c, a.reqStr("kind"), a.reqLong("duration_seconds"),
						a.reqStr("action_key"), a.optMapOrEmpty("parameters"));
			}
			default -> throw new IllegalStateException(act);
			};
		});
	}

	// ── Inventory, commerce & loot ─────────────────────────────────────

	@Tool(name = "inventory", description = "Equipment, item/coin transfers, market trades and audited loot (all mutating; see get_instructions topic ECONOMY). Actions: "
			+ "TRANSFER_ITEM {entry, to, quantity?, reason?}; " + "EQUIP {character, entry, equipped}; "
			+ "GIVE_MONEY {from, to, money, reason?}; "
			+ "TRADE {character, kind: BUY|SELL, item?, entry?, quantity?, merchant?, negotiated_price?, reason?}; "
			+ "GRANT_LOOT {to, items?, money?, source?: ENCOUNTER|QUEST|WORLD|GM_GRANT, reason?}.", annotations = @Tool.Annotations(destructiveHint = false, openWorldHint = false))
	ToolResponse inventory(@ToolArg(description = "TRANSFER_ITEM | EQUIP | GIVE_MONEY | TRADE | GRANT_LOOT")
	String action, @ToolArg(description = CAMP)
	String campaign, @ToolArg(description = OP)
	String operation_id, @ToolArg(description = PARAMS)
	Map<String, Object> params) {
		return ToolSupport.run("inventory", () -> {
			String act = ToolArgs.action("inventory", action,
					List.of("TRANSFER_ITEM", "EQUIP", "GIVE_MONEY", "TRADE", "GRANT_LOOT"));
			String c = ToolArgs.reqCampaign("inventory", act, campaign);
			String op = ToolArgs.reqOp("inventory", act, operation_id);
			return switch (act) {
			case "TRANSFER_ITEM" -> {
				ToolArgs a = ToolArgs.of("inventory", act, "ECONOMY", params, "entry, to, quantity?, reason?", "entry",
						"to", "quantity", "reason");
				yield engine.inventory().transfer(op, c, a.reqStr("entry"), a.reqStr("to"), a.optInt("quantity"),
						a.optStr("reason"));
			}
			case "EQUIP" -> {
				ToolArgs a = ToolArgs.of("inventory", act, "ECONOMY", params, "character, entry, equipped", "character",
						"entry", "equipped");
				yield engine.inventory().equip(op, c, a.reqStr("character"), a.reqStr("entry"), a.reqBool("equipped"));
			}
			case "GIVE_MONEY" -> {
				ToolArgs a = ToolArgs.of("inventory", act, "ECONOMY", params, "from, to, money, reason?", "from", "to",
						"money", "reason");
				yield engine.inventory().giveMoney(op, c, a.reqStr("from"), a.reqStr("to"), a.reqObj("money"),
						a.optStr("reason"));
			}
			case "TRADE" -> {
				ToolArgs a = ToolArgs.of("inventory", act, "ECONOMY", params,
						"character, kind, item?, entry?, quantity?, merchant?, negotiated_price?, reason?", "character",
						"kind", "item", "entry", "quantity", "merchant", "negotiated_price", "reason");
				yield engine.inventory().trade(op, c, a.reqStr("character"), a.reqStr("kind"), a.optStr("item"),
						a.optStr("entry"), a.optInt("quantity"), a.optStr("merchant"), a.optObj("negotiated_price"),
						a.optStr("reason"));
			}
			case "GRANT_LOOT" -> {
				ToolArgs a = ToolArgs.of("inventory", act, "ECONOMY", params, "to, items?, money?, source?, reason?",
						"to", "items", "money", "source", "reason");
				yield engine.inventory().grantLoot(op, c, a.reqStr("to"), a.optMapList("items"), a.optObj("money"),
						a.optStr("source"), a.optStr("reason"));
			}
			default -> throw new IllegalStateException(act);
			};
		});
	}

	// ── Narrative, Director & relationships ────────────────────────────

	@Tool(name = "narrative", description = "Scoped context retrieval, quests/beats/factions/events, diegetic info, Director reviews and relationships (see get_instructions topic DIRECTOR). Actions: "
			+ "CONTEXT (read) {scope: SCENE|CHARACTER|RELATIONSHIP|INTIMACY|WANT|STORY_BEAT|LOCATION|QUEST|ENCOUNTER|DIRECTOR, ref?, second_ref?, focus?, context_budget?}; "
			+ "UPSERT (mut) {kind: QUEST|STORY_BEAT|STORY_SEED|FACTION_STATE|WORLD_EVENT|LOCATION_DETAIL|NPC_AGENDA, changes, ref?, provenance?}; "
			+ "DIEGETIC (read) {channel?, location?, limit?}; "
			+ "DIRECTOR_CONTEXT (read, DIRECTOR_ONLY) {scope?: INITIAL_CAMPAIGN|SESSION_START|CAMPAIGN_REVIEW}; "
			+ "COMMIT_DIRECTOR (mut) {changes?, review_note?}; " + "GET_RELATIONSHIP (read) {a, b}; "
			+ "UPDATE_RELATIONSHIP (mut) {from, to, dimensions?, summary?, cause_event?, reason?, mutual?, provenance?, profile?, profile_mode?: MERGE|REPLACE}.", annotations = @Tool.Annotations(destructiveHint = false, openWorldHint = false))
	ToolResponse narrative(
		@ToolArg(description = "CONTEXT | UPSERT | DIEGETIC | DIRECTOR_CONTEXT | COMMIT_DIRECTOR | GET_RELATIONSHIP | UPDATE_RELATIONSHIP")
		String action, @ToolArg(description = CAMP)
		String campaign, @ToolArg(description = OP, required = false)
		String operation_id, @ToolArg(description = PARAMS, required = false)
		Map<String, Object> params) {
		return ToolSupport.run("narrative", () -> {
			String act = ToolArgs.action("narrative", action, List.of("CONTEXT", "UPSERT", "DIEGETIC",
					"DIRECTOR_CONTEXT", "COMMIT_DIRECTOR", "GET_RELATIONSHIP", "UPDATE_RELATIONSHIP"));
			String c = ToolArgs.reqCampaign("narrative", act, campaign);
			return switch (act) {
			case "CONTEXT" -> {
				ToolArgs a = ToolArgs.of("narrative", act, "DIRECTOR", params,
						"scope, ref?, second_ref?, focus?, context_budget?", "scope", "ref", "second_ref", "focus",
						"context_budget");
				yield engine.narrative().context(c, a.reqStr("scope"), a.optStr("ref"), a.optStr("second_ref"),
						a.optStr("focus"), a.optInt("context_budget"));
			}
			case "UPSERT" -> {
				String op = ToolArgs.reqOp("narrative", act, operation_id);
				ToolArgs a = ToolArgs.of("narrative", act, "DIRECTOR", params, "kind, changes, ref?, provenance?",
						"kind", "changes", "ref", "provenance");
				yield engine.narrative().upsert(op, c, a.reqStr("kind"), a.optStr("ref"), a.reqMap("changes"),
						a.optStr("provenance"));
			}
			case "DIEGETIC" -> {
				ToolArgs a = ToolArgs.of("narrative", act, "DIRECTOR", params, "channel?, location?, limit?", "channel",
						"location", "limit");
				yield engine.narrative().diegetic(c, a.optStr("channel"), a.optStr("location"), a.optInt("limit", 10));
			}
			case "DIRECTOR_CONTEXT" -> {
				ToolArgs a = ToolArgs.of("narrative", act, "DIRECTOR", params, "scope?", "scope");
				yield engine.narrative().directorContext(c, a.optStr("scope"));
			}
			case "COMMIT_DIRECTOR" -> {
				String op = ToolArgs.reqOp("narrative", act, operation_id);
				ToolArgs a = ToolArgs.of("narrative", act, "DIRECTOR", params, "changes?, review_note?", "changes",
						"review_note");
				yield engine.narrative().commitDirectorChanges(op, c, a.optMapList("changes"), a.optStr("review_note"));
			}
			case "GET_RELATIONSHIP" -> {
				ToolArgs a = ToolArgs.of("narrative", act, "CHARACTERS", params, "a, b", "a", "b");
				yield engine.party().relationship(c, a.reqStr("a"), a.reqStr("b"));
			}
			case "UPDATE_RELATIONSHIP" -> {
				String op = ToolArgs.reqOp("narrative", act, operation_id);
				ToolArgs a = ToolArgs.of("narrative", act, "CHARACTERS", params,
						"from, to, dimensions?, summary?, cause_event?, reason?, mutual?, provenance?, profile?, profile_mode?",
						"from", "to", "dimensions", "summary", "cause_event", "reason", "mutual", "provenance",
						"profile", "profile_mode");
				yield engine.party().updateRelationship(op, c, a.reqStr("from"), a.reqStr("to"), a.optMap("dimensions"),
						a.optStr("summary"), a.optStr("cause_event"), a.optStr("reason"), a.optBool("mutual", false),
						a.optStr("provenance"), a.optMap("profile"), a.optStr("profile_mode"));
			}
			default -> throw new IllegalStateException(act);
			};
		});
	}

	// ── Ledger memory, lookup & chronicle ──────────────────────────────

	@Tool(name = "chronicle", description = "Episodic ledger memory, timeline queries, entity lookup and chapter/synopsis summaries (see get_instructions topic CHARACTERS). Actions: "
			+ "RECORD_MEMORY (mut) {type, summary, participants?, importance?, visibility?, provenance?, game_time?, location?, detail?, payload?}; "
			+ "QUERY_MEMORIES (read) {participants?, types?, focus?, min_importance?, limit?}; "
			+ "QUERY_TIMELINE (read) {from_time?, to_time?, participants?, types?, min_importance?, by_insertion?, limit?}; "
			+ "FIND (read) {query, kind?: CHARACTER|LOCATION|WANT, limit?}; "
			+ "GET_MATERIAL (read) {kind?: CHAPTER|SYNOPSIS, cursor?}; "
			+ "WRITE (mut) {summary, kind?: CHAPTER|SYNOPSIS, title?, through?}.", annotations = @Tool.Annotations(destructiveHint = false, openWorldHint = false))
	ToolResponse chronicle(
		@ToolArg(description = "RECORD_MEMORY | QUERY_MEMORIES | QUERY_TIMELINE | FIND | GET_MATERIAL | WRITE")
		String action, @ToolArg(description = CAMP)
		String campaign, @ToolArg(description = OP, required = false)
		String operation_id, @ToolArg(description = PARAMS, required = false)
		Map<String, Object> params) {
		return ToolSupport.run("chronicle", () -> {
			String act = ToolArgs.action("chronicle", action,
					List.of("RECORD_MEMORY", "QUERY_MEMORIES", "QUERY_TIMELINE", "FIND", "GET_MATERIAL", "WRITE"));
			String c = ToolArgs.reqCampaign("chronicle", act, campaign);
			return switch (act) {
			case "RECORD_MEMORY" -> {
				String op = ToolArgs.reqOp("chronicle", act, operation_id);
				ToolArgs a = ToolArgs.of("chronicle", act, "CORE", params,
						"type, summary, participants?, importance?, visibility?, provenance?, game_time?, location?, detail?, payload?",
						"type", "summary", "participants", "importance", "visibility", "provenance", "game_time",
						"location", "detail", "payload");
				yield engine.ledger().record(op, c, a.reqStr("type"), a.reqStr("summary"),
						a.optStringList("participants"), a.optStr("importance"), a.optStr("visibility"),
						a.optStr("provenance"), a.optStr("game_time"), a.optStr("location"), a.optStr("detail"),
						a.optMap("payload"));
			}
			case "QUERY_MEMORIES" -> {
				ToolArgs a = ToolArgs.of("chronicle", act, "CORE", params,
						"participants?, types?, focus?, min_importance?, limit?", "participants", "types", "focus",
						"min_importance", "limit");
				yield engine.ledger().queryMemories(c, a.optStringList("participants"), a.optStringList("types"),
						a.optStr("focus"), a.optStr("min_importance"), a.optInt("limit", 10));
			}
			case "QUERY_TIMELINE" -> {
				ToolArgs a = ToolArgs.of("chronicle", act, "CORE", params,
						"from_time?, to_time?, participants?, types?, min_importance?, by_insertion?, limit?",
						"from_time", "to_time", "participants", "types", "min_importance", "by_insertion", "limit");
				yield engine.ledger().queryTimeline(c, a.optStr("from_time"), a.optStr("to_time"),
						a.optStringList("participants"), a.optStringList("types"), a.optStr("min_importance"),
						a.optBool("by_insertion", false), a.optInt("limit", 25));
			}
			case "FIND" -> {
				ToolArgs a = ToolArgs.of("chronicle", act, "CHARACTERS", params, "query, kind?, limit?", "query",
						"kind", "limit");
				yield engine.sessions().find(c, a.optStr("kind"), a.reqStr("query"), a.optInt("limit"));
			}
			case "GET_MATERIAL" -> {
				ToolArgs a = ToolArgs.of("chronicle", act, "CHARACTERS", params, "kind?, cursor?", "kind", "cursor");
				yield engine.chronicle().material(c, a.optStr("kind"), a.optStr("cursor"));
			}
			case "WRITE" -> {
				String op = ToolArgs.reqOp("chronicle", act, operation_id);
				ToolArgs a = ToolArgs.of("chronicle", act, "CHARACTERS", params, "summary, kind?, title?, through?",
						"summary", "kind", "title", "through");
				yield engine.chronicle().write(op, c, a.optStr("kind"), a.optStr("title"), a.reqStr("summary"),
						a.optLong("through"));
			}
			default -> throw new IllegalStateException(act);
			};
		});
	}

	// ── Checkpoints & continuation ─────────────────────────────────────

	@Tool(name = "checkpoints", description = "Policy-gated checkpoint creation and continuation options. Actions: "
			+ "CREATE (mut) {reason?}; "
			+ "OPTIONS (read) {}. (To rewind to a checkpoint, use standalone restore_checkpoint.)", annotations = @Tool.Annotations(destructiveHint = false, openWorldHint = false))
	ToolResponse checkpoints(@ToolArg(description = "CREATE | OPTIONS")
	String action, @ToolArg(description = CAMP)
	String campaign, @ToolArg(description = OP, required = false)
	String operation_id, @ToolArg(description = PARAMS, required = false)
	Map<String, Object> params) {
		return ToolSupport.run("checkpoints", () -> {
			String act = ToolArgs.action("checkpoints", action, List.of("CREATE", "OPTIONS"));
			String c = ToolArgs.reqCampaign("checkpoints", act, campaign);
			return switch (act) {
			case "CREATE" -> {
				String op = ToolArgs.reqOp("checkpoints", act, operation_id);
				ToolArgs a = ToolArgs.of("checkpoints", act, "CORE", params, "reason?", "reason");
				yield engine.checkpoints().create(op, c, a.optStr("reason"));
			}
			case "OPTIONS" -> {
				ToolArgs.of("checkpoints", act, "CORE", params, "(none)");
				yield engine.checkpoints().options(c);
			}
			default -> throw new IllegalStateException(act);
			};
		});
	}

	// ── Rules lookup & custom content ──────────────────────────────────

	@Tool(name = "rules", description = "SRD 5.2.1 rules search, content catalog lookup and campaign custom content. Actions: "
			+ "SEARCH (read; campaign optional) {query, kind?: RULE|SPELL|ITEM|CREATURE|CLASS|SPECIES|BACKGROUND|FEAT|SKILL, limit?}; "
			+ "DEFINITIONS (read; campaign optional) {kind?, item_type?, text?, min_cost?, max_cost?, rarity?, magic?, detail?: SUMMARY|FULL, cursor?, limit?}; "
			+ "DEFINE (mut; campaign required) {name, kind?: ITEM|BACKGROUND, symbolic_id?, description?, item_type?, cost?, weight_lb?, properties?, tags?}.", annotations = @Tool.Annotations(destructiveHint = false, openWorldHint = false))
	ToolResponse rules(@ToolArg(description = "SEARCH | DEFINITIONS | DEFINE")
	String action, @ToolArg(description = CAMP_RULES, required = false)
	String campaign, @ToolArg(description = OP, required = false)
	String operation_id, @ToolArg(description = PARAMS, required = false)
	Map<String, Object> params) {
		return ToolSupport.run("rules", () -> {
			String act = ToolArgs.action("rules", action, List.of("SEARCH", "DEFINITIONS", "DEFINE"));
			return switch (act) {
			case "SEARCH" -> {
				ToolArgs a = ToolArgs.of("rules", act, "CORE", params, "query, kind?, limit?", "query", "kind",
						"limit");
				yield engine.content().search(campaign, a.reqStr("query"), a.optStr("kind"), a.optInt("limit", 0));
			}
			case "DEFINITIONS" -> {
				ToolArgs a = ToolArgs.of("rules", act, "ECONOMY", params,
						"kind?, item_type?, text?, min_cost?, max_cost?, rarity?, magic?, detail?, cursor?, limit?",
						"kind", "item_type", "text", "min_cost", "max_cost", "rarity", "magic", "detail", "cursor",
						"limit");
				yield engine.content().definitions(campaign, a.optStr("kind"), a.optStr("item_type"), a.optStr("text"),
						a.optObj("min_cost"), a.optObj("max_cost"), a.optStr("rarity"), a.optBool("magic"),
						a.optStr("cursor"), a.optInt("limit", 25), a.optStr("detail", "SUMMARY"));
			}
			case "DEFINE" -> {
				String c = ToolArgs.reqCampaign("rules", act, campaign);
				String op = ToolArgs.reqOp("rules", act, operation_id);
				ToolArgs a = ToolArgs.of("rules", act, "ECONOMY", params,
						"name, kind?, symbolic_id?, description?, item_type?, cost?, weight_lb?, properties?, tags?",
						"name", "kind", "symbolic_id", "description", "item_type", "cost", "weight_lb", "properties",
						"tags");
				yield engine.content().define(op, c, a.optStr("kind", "ITEM"), a.reqStr("name"),
						a.optStr("symbolic_id"), a.optStr("description"), a.optStr("item_type"), a.optObj("cost"),
						a.optDouble("weight_lb"), a.optMap("properties"), a.optStringList("tags"), "GM");
			}
			default -> throw new IllegalStateException(act);
			};
		});
	}

	// ── Standalone destructive boundaries ──────────────────────────────

	@Tool(name = "restore_checkpoint", description = "MUTATING, DESTRUCTIVE to later history. Policy-gated transactional rewind to a saved checkpoint. "
			+ "Call session:BOOTSTRAP afterwards and trust server state over prior narration.", annotations = @Tool.Annotations(destructiveHint = true, openWorldHint = false))
	ToolResponse restoreCheckpoint(@ToolArg(description = "Idempotency key")
	String operation_id, @ToolArg(description = CAMP)
	String campaign, @ToolArg(description = "checkpoint:N")
	String checkpoint, @ToolArg(description = "Reason for rewinding", required = false)
	String reason) {
		return ToolSupport.run("restore_checkpoint",
				() -> engine.checkpoints().restore(operation_id, campaign, checkpoint, reason));
	}

	@Tool(name = "complete_campaign", description = "MUTATING, IRREVERSIBLE. Permanently ends a campaign with outcome COMPLETED, FAILED or ABANDONED. "
			+ "Requires an explicit reason; optional summary is stored as the closing chronicle entry.", annotations = @Tool.Annotations(destructiveHint = true, openWorldHint = false))
	ToolResponse completeCampaign(@ToolArg(description = "Idempotency key")
	String operation_id, @ToolArg(description = CAMP)
	String campaign, @ToolArg(description = "COMPLETED | FAILED | ABANDONED")
	String outcome, @ToolArg(description = "Reason for ending the campaign")
	String reason, @ToolArg(description = "Optional closing summary", required = false)
	String summary) {
		return ToolSupport.run("complete_campaign",
				() -> engine.campaigns().complete(operation_id, campaign, outcome, reason, summary));
	}

	@Tool(name = "apply_gm_override", description = "MUTATING, AUDITED. Exceptional rule override permitted only under explicit-audited override policy. "
			+ "kind: ADJUST_HP, SET_MAX_HP, SET_ARMOR_CLASS, SET_LIFE_STATE, SET_MONEY, SET_XP, SET_ABILITY_SCORE, SET_LOCATION, SET_CAMPAIGN_RULE (no target), REMOVE_ENCOUNTER_PARTICIPANT, SET_CONNECTION_STATE. "
			+ "Requires reason; writes an immutable audit record and must be disclosed as a GM call, never a roll.", annotations = @Tool.Annotations(destructiveHint = true, openWorldHint = false))
	ToolResponse applyGmOverride(@ToolArg(description = "Idempotency key")
	String operation_id, @ToolArg(description = CAMP)
	String campaign, @ToolArg(description = "Override kind")
	String kind, @ToolArg(description = "character:N or location:N (omit for SET_CAMPAIGN_RULE)", required = false)
	String target, @ToolArg(description = "Override effect fields", required = false)
	Map<String, Object> effect, @ToolArg(description = "Human-readable reason")
	String reason, @ToolArg(description = "Target revision last read", required = false)
	Optional<Long> expected_revision) {
		return ToolSupport.run("apply_gm_override", () -> engine.rest().override(operation_id, campaign, kind, target,
				effect, reason, expected_revision.orElse(null)));
	}
}
