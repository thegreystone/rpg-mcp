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
package se.hirt.mcp.rpg.rules;

import se.hirt.mcp.rpg.content.RulesData;
import se.hirt.mcp.rpg.dice.Roll;
import se.hirt.mcp.rpg.dice.RollService;
import se.hirt.mcp.rpg.harness.Harness;
import se.hirt.mcp.rpg.persistence.Database;
import se.hirt.mcp.rpg.persistence.Row;
import se.hirt.mcp.rpg.persistence.Tx;
import se.hirt.mcp.rpg.protocol.Json;
import se.hirt.mcp.rpg.protocol.Ref;
import se.hirt.mcp.rpg.protocol.RpgException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * D20 Tests outside encounters: ability checks, skill checks and saving throws (MCP_PROTOCOL.md §13.2; SRD 5.2.1 "D20
 * Tests"). The AI supplies intent and the DC; the engine owns modifiers and dice.
 */
public final class CheckService {

	private final Database db;
	private final RulesData rules;
	private final RollService roller;

	public CheckService(Database db, RulesData rules, RollService roller) {
		this.db = db;
		this.rules = rules;
		this.roller = roller;
	}

	public Map<String, Object> resolveCheck(
			String operationId, String campaignRef, String actorRef, String kind,
			String abilityText, String skillText, Integer difficulty, String advantageText, String reason) {
		return resolveCheck(operationId, campaignRef, actorRef, kind, abilityText, skillText, null, difficulty,
				advantageText, reason);
	}

	/**
	 * @param toolText
	 * 		a tool used for the check (SRD 5.2.1 "Tool Proficiency"): the actor's proficiency bonus applies when they
	 * 		are proficient with it, and a skill check made with a tool the actor is also proficient in has advantage
	 * 		("Tools and Skills Together")
	 */
	public Map<String, Object> resolveCheck(
			String operationId, String campaignRef, String actorRef, String kind,
			String abilityText, String skillText, String toolText, Integer difficulty, String advantageText,
			String reason) {
		long campaignId = Ref.id(campaignRef, Ref.CAMPAIGN);
		var args = new LinkedHashMap<String, Object>();
		args.put("campaign", campaignRef);
		args.put("actor", actorRef);
		args.put("kind", kind);
		args.put("ability", abilityText);
		args.put("skill", skillText);
		args.put("tool", toolText);
		args.put("difficulty", difficulty);
		args.put("advantage", advantageText);
		args.put("reason", reason);
		return db.mutate(Database.Mutation.of("resolve_check", campaignId, operationId, "GM", args), tx -> {
			Row campaign = Harness.requireMutation(tx, campaignRef, "resolve_check");
			Row actor = activeCharacter(tx, campaignId, actorRef);
			String k = kind == null ? "ABILITY_CHECK" : kind.toUpperCase();
			if (!k.equals("ABILITY_CHECK") && !k.equals("SKILL_CHECK") && !k.equals("SAVING_THROW")) {
				throw RpgException.invalidArgument("kind must be ABILITY_CHECK, SKILL_CHECK or SAVING_THROW.");
			}
			if (difficulty != null && (difficulty < 1 || difficulty > 40)) {
				throw RpgException.invalidArgument("difficulty (DC) must be between 1 and 40.");
			}
			String advantage = advantageText == null || advantageText.isBlank() ? "NONE" : advantageText.toUpperCase();
			if (!advantage.equals("NONE") && !advantage.equals("ADVANTAGE") && !advantage.equals("DISADVANTAGE")) {
				throw RpgException.invalidArgument("advantage must be NONE, ADVANTAGE or DISADVANTAGE.");
			}

			Ability ability;
			RulesData.Definition skill = null;
			boolean proficient;
			if (k.equals("SKILL_CHECK")) {
				skill = rules.resolve("SKILL", skillText).orElseThrow(() -> RpgException.invalidArgument(
						"A known skill is required for a SKILL_CHECK; got '" + skillText + "'."));
				ability = abilityText == null || abilityText.isBlank() ? Ability.parse(
						(String) skill.payload().get("ability")) : Ability.parse(abilityText);
				proficient = tx.count(
						"SELECT COUNT(*) FROM character_trait WHERE character_id = ? AND kind = 'SKILL' AND content_ref = ?",
						actor.id(), skill.id()) > 0;
			} else {
				ability = Ability.parse(abilityText);
				proficient = k.equals("SAVING_THROW") && tx.count(
						"SELECT COUNT(*) FROM character_trait WHERE character_id = ? AND kind = 'SAVE' AND content_ref = ?",
						actor.id(), ability.name()) > 0;
			}
			RulesData.Definition tool = null;
			boolean toolProficient = false;
			String advantageSource = null;
			if (toolText != null && !toolText.isBlank()) {
				if (k.equals("SAVING_THROW")) {
					throw RpgException.invalidArgument("A tool does not apply to a saving throw.");
				}
				tool = rules.resolve("ITEM", toolText)
						.filter(d -> "TOOL".equals(String.valueOf(d.payload().get("type"))))
						.orElseThrow(() -> RpgException.invalidArgument(
								"Unknown tool '" + toolText + "'; see get_content_definitions item_type TOOL."));
				toolProficient = tx.count(
						"SELECT COUNT(*) FROM character_trait WHERE character_id = ? AND kind = 'PROFICIENCY' AND content_ref = ?",
						actor.id(), tool.id()) > 0;
				if (k.equals("SKILL_CHECK")) {
					// SRD 5.2.1 "Tools and Skills Together": proficiency in both the skill and the tool gives
					// advantage; the skill's proficiency bonus is already in the modifier.
					if (proficient && toolProficient) {
						if (advantage.equals("NONE")) {
							advantage = "ADVANTAGE";
							advantageSource = "proficient in both " + skill.name() + " and " + tool.name();
						} else if (advantage.equals("DISADVANTAGE")) {
							advantage = "NONE";
							advantageSource = "proficiency in both " + skill.name() + " and " + tool.name()
									+ " cancels the disadvantage";
						}
					}
				} else {
					// An ability check made with a tool: the tool proficiency is the proficiency.
					proficient = toolProficient;
				}
			}
			Integer score = actor.integer(ability.column());
			if (score == null) {
				throw RpgException.invalidArgument(actor.str("name") + " has no " + ability.fullName() + " score.");
			}
			int level = tx.query("SELECT level FROM character_class WHERE character_id = ?", actor.id()).stream()
					.mapToInt(r -> r.intOr("level", 1)).sum();
			int abilityMod = Rules.modifier(score);
			// A creature-backed character uses its stat block's listed total, which already contains proficiency and
			// any expertise: the Scout's "Perception +5" is the whole bonus, not something to add a modifier to
			// (SRD 5.2.1 "Statistics"; RULES_ENGINE.md §3.1). The moment that character takes a class it stops being
			// a stat block and is built from its own abilities and proficiencies, which is the whole point of
			// classing a companion — otherwise a rogue with Expertise would still roll the Scout's numbers.
			Integer block = level > 0 ? null
					: k.equals("SKILL_CHECK") ? statBlockSkill(rules, actor, skill.name())
							: k.equals("SAVING_THROW") ? statBlockSave(rules, actor, ability) : null;
			int profBonus;
			int modifier;
			if (block != null) {
				proficient = true;
				profBonus = 0;
				modifier = block;
			} else {
				profBonus = proficient ? rules.proficiencyBonus(Math.max(1, level)) : 0;
				modifier = abilityMod + profBonus;
			}

			String dice = switch (advantage) {
				case "ADVANTAGE" -> "2d20kh1";
				case "DISADVANTAGE" -> "2d20kl1";
				default -> "1d20";
			};
			String expression = dice + (modifier >= 0 ? "+" + modifier : Integer.toString(modifier));
			Roll roll = roller.roll(expression);
			long rollId = recordRoll(tx, campaignId, k.toLowerCase(), roll, actor.id(), reason);
			Effects.Modifiers mods = Effects.modifiers(tx, actor.id());
			int total = roll.total();
			var bonusDice = new ArrayList<Map<String, Object>>();
			for (String bd : k.equals("SAVING_THROW") ? mods.saveBonusDice : mods.checkBonusDice) {
				Roll b = roller.roll(bd);
				recordRoll(tx, campaignId, "bonus die", b, actor.id(), null);
				total += b.total();
				bonusDice.add(b.toMap());
			}
			if (k.equals("SAVING_THROW")) {
				total += mods.saveBonus;
			}

			var result = new LinkedHashMap<String, Object>();
			result.put("kind", k);
			result.put("actor", Ref.of(Ref.CHARACTER, actor.id()));
			result.put("actor_name", actor.str("name"));
			result.put("ability", ability.name());
			if (skill != null) {
				result.put("skill", skill.name());
			}
			if (tool != null) {
				result.put("tool", tool.name());
				result.put("tool_proficient", toolProficient);
			}
			result.put("proficient", proficient);
			result.put("ability_modifier", abilityMod);
			result.put("proficiency_bonus", profBonus);
			result.put("modifier", modifier);
			if (block != null) {
				result.put("modifier_source", "STAT_BLOCK");
			}
			result.put("advantage", advantage);
			if (advantageSource != null) {
				result.put("advantage_source", advantageSource);
			}
			var rollMap = roll.toMap();
			rollMap.put("roll_ref", Ref.of(Ref.ROLL, rollId));
			result.put("roll", rollMap);
			result.put("natural", roll.dice().get(0));
			if (!bonusDice.isEmpty()) {
				result.put("bonus_dice", bonusDice);
			}
			result.put("total", total);
			result.put("difficulty", difficulty);
			if (difficulty != null) {
				result.put("success", total >= difficulty);
				result.put("margin", total - difficulty);
			}
			result.put("reason", reason);
			result.put("meta", Harness.meta(campaign, null));
			return result;
		});
	}

	/**
	 * The stat block's listed total for a skill, or null when the character is not creature-backed or lacks it.
	 * Callers must ignore it for a character that has class levels; see {@code resolveCheck}.
	 */
	@SuppressWarnings("unchecked")
	public static Integer statBlockSkill(RulesData rules, Row actor, String skillName) {
		if (actor.isNull("origin_content_ref")) {
			return null;
		}
		// Callers gate on class levels; see resolveCheck and RuntimeService.usesStatBlock.
		return rules.find(actor.str("origin_content_ref")).map(d -> (Map<String, Object>) d.payload().get("skills"))
				.filter(s -> s != null && s.get(skillName) instanceof Number)
				.map(s -> ((Number) s.get(skillName)).intValue()).orElse(null);
	}

	/** The stat block's listed total for a saving throw, or null when there is none. */
	@SuppressWarnings("unchecked")
	public static Integer statBlockSave(RulesData rules, Row actor, Ability ability) {
		if (actor.isNull("origin_content_ref")) {
			return null;
		}
		return rules.find(actor.str("origin_content_ref")).map(d -> (Map<String, Object>) d.payload().get("saves"))
				.filter(s -> s != null && s.get(ability.name()) instanceof Number)
				.map(s -> ((Number) s.get(ability.name())).intValue()).orElse(null);
	}

	/** A character's own bonus for a named skill: ability modifier plus proficiency when they have it. */
	public static Integer characterSkill(Tx tx, RulesData rules, Row c, String skillName) {
		return rules.resolve("SKILL", skillName).map(skill -> {
			Ability a = Ability.parse((String) skill.payload().get("ability"));
			int level = tx.query("SELECT level FROM character_class WHERE character_id = ?", c.id()).stream()
					.mapToInt(r -> r.intOr("level", 1)).sum();
			boolean proficient = tx.count(
					"SELECT COUNT(*) FROM character_trait WHERE character_id = ? AND kind = 'SKILL' AND content_ref = ?",
					c.id(), skill.id()) > 0;
			return Rules.modifier(c.intOr(a.column(), 10)) + (proficient ? rules.proficiencyBonus(Math.max(1, level))
					: 0);
		}).orElse(null);
	}

	static Row activeCharacter(Tx tx, long campaignId, String ref) {
		long id = Ref.id(ref, Ref.CHARACTER);
		Row c = tx.find("character", id).orElseThrow(() -> RpgException.notFound("Character " + ref));
		if (c.lng("campaign_id") != campaignId) {
			throw RpgException.invalidArgument(ref + " belongs to another campaign.");
		}
		if (!"ACTIVE".equals(c.str("lifecycle"))) {
			throw RpgException.notAllowed(ref + " is not an active character (lifecycle " + c.str("lifecycle") + ").");
		}
		if ("DEAD".equals(c.str("life_state"))) {
			throw RpgException.notAllowed(c.str("name") + " is dead and cannot attempt checks.");
		}
		return c;
	}

	static long recordRoll(Tx tx, long campaignId, String purpose, Roll roll, long actorId, String reason) {
		var cols = new LinkedHashMap<String, Object>();
		cols.put("campaign_id", campaignId);
		cols.put("journal_id", tx.journalId());
		cols.put("purpose", purpose + " character:" + actorId + (reason == null ? "" : " — " + reason));
		cols.put("expression", roll.expression());
		cols.put("dice_json", Json.write(roll.dice()));
		cols.put("dropped_json", Json.write(roll.dropped()));
		cols.put("modifier", roll.modifier());
		cols.put("total", roll.total());
		return tx.insert("roll", cols);
	}
}
