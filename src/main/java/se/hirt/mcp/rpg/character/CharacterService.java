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
package se.hirt.mcp.rpg.character;

import se.hirt.mcp.rpg.campaign.SetupDraft;
import se.hirt.mcp.rpg.choice.Alignment;
import se.hirt.mcp.rpg.choice.Decision;
import se.hirt.mcp.rpg.choice.Described;
import se.hirt.mcp.rpg.choice.Option;
import se.hirt.mcp.rpg.content.RulesData;
import se.hirt.mcp.rpg.dice.Roll;
import se.hirt.mcp.rpg.dice.RollService;
import se.hirt.mcp.rpg.harness.Harness;
import se.hirt.mcp.rpg.inventory.InventoryService;
import se.hirt.mcp.rpg.persistence.Database;
import se.hirt.mcp.rpg.persistence.Row;
import se.hirt.mcp.rpg.persistence.Tx;
import se.hirt.mcp.rpg.protocol.Json;
import se.hirt.mcp.rpg.protocol.Ref;
import se.hirt.mcp.rpg.protocol.RpgException;
import se.hirt.mcp.rpg.protocol.Violation;
import se.hirt.mcp.rpg.rules.Ability;
import se.hirt.mcp.rpg.rules.Money;
import se.hirt.mcp.rpg.rules.Rules;

import java.time.Instant;
import java.util.*;

/**
 * Character drafts, ability generation, validation, finalization, and character sheets (MCP_PROTOCOL.md §10, §13.1;
 * DOMAIN_MODEL.md §5).
 */
public final class CharacterService {

	public static final List<String> ALIGNMENTS = Described.names(Alignment.class);
	private static final Set<String> DRAFT_FIELDS = Set.of("name", "description", "appearance", "personality",
			"backstory", "goals", "alignment", "age", "presentation", "species", "class", "ability_scores",
			"background", "background_ability_scores", "skills", "species_skill", "species_choice", "origin_feat",
			"feat_choices", "background_tool", "starting_equipment", "background_equipment", "cantrips", "spells");

	private final Database db;
	private final RulesData rules;
	private final RollService roller;
	private final CharacterChoices choices;

	public CharacterService(Database db, RulesData rules, RollService roller) {
		this.db = db;
		this.rules = rules;
		this.roller = roller;
		this.choices = new CharacterChoices(rules);
	}

	/** The options and outstanding decisions of character creation (shared with campaign setup). */
	public CharacterChoices choiceCatalog() {
		return choices;
	}

	// ── create_character_draft ─────────────────────────────────────────

	public Map<String, Object> createDraft(
			String operationId, String campaignRef, Map<String, Object> initial, boolean playerControlled) {
		long campaignId = Ref.id(campaignRef, Ref.CAMPAIGN);
		var args = new LinkedHashMap<String, Object>();
		args.put("campaign", campaignRef);
		args.put("initial", initial);
		args.put("player_controlled", playerControlled);
		return db.mutate(Database.Mutation.of("create_character_draft", campaignId, operationId, "PLAYER", args),
				tx -> {
					Row campaign = Harness.requireMutation(tx, campaignRef, "create_character_draft");
					var cols = new LinkedHashMap<String, Object>();
					cols.put("campaign_id", campaignId);
					cols.put("lifecycle", "DRAFT");
					cols.put("life_state", "ALIVE");
					cols.put("revision", 0);
					cols.put("created_at", Instant.now().toString());
					cols.put("creation_json", "{}");
					long id = tx.insert("character", cols);
					Row draftRow = setupDraftRow(tx, campaignId);
					SetupDraft draft = new SetupDraft(draftRow.map("payload_json"));
					if (playerControlled) {
						if (draft.playerCharacterId() != null && tx.find("character", draft.playerCharacterId())
								.map(r -> !"ARCHIVED".equals(r.str("lifecycle"))).orElse(false)) {
							throw RpgException.notAllowed(
									"The setup already has a player character (" + Ref.of(Ref.CHARACTER,
											draft.playerCharacterId()) + "); set player_controlled=false for companions or change player_character via update_campaign_setup.");
						}
						draft.payload().put("player_character", Ref.of(Ref.CHARACTER, id));
						tx.update("campaign_setup_draft", draftRow.id(),
								Map.of("payload_json", Json.write(draft.payload()), "revision",
										draftRow.lng("revision") + 1));
					}
					if (initial != null && !initial.isEmpty()) {
						applyChanges(tx, draft, tx.get("character", id), initial);
					}
					refreshHarness(tx, campaignId, campaign);
					Row created = tx.get("character", id);
					tx.touched(Ref.of(Ref.CHARACTER, id), created.lng("revision"));
					var result = new LinkedHashMap<String, Object>();
					result.put("character", Ref.of(Ref.CHARACTER, id));
					result.put("lifecycle", "DRAFT");
					result.put("player_controlled", playerControlled);
					result.put("revision", created.lng("revision"));
					result.put("sheet", sheet(tx, created, "PLAY"));
					result.put("next_steps", nextSteps(tx, created));
					result.put("decisions",
							Decision.render(choices.decisionsFor(tx, created, draft.abilityGeneration())));
					result.put("meta", Harness.meta(tx.get("campaign", campaignId), null));
					return result;
				});
	}

	// ── get_character_choices ──────────────────────────────────────────

	public Map<String, Object> choices(String campaignRef, String scope, String characterRef) {
		return db.read(tx -> {
			Row campaign = Harness.campaign(tx, campaignRef);
			SetupDraft draft = new SetupDraft(setupDraftRow(tx, campaign.id()).map("payload_json"));
			String s = scope == null ? "ALL" : scope.toUpperCase();
			var result = new LinkedHashMap<String, Object>();
			result.put("scope", s);
			if (!Set.of("ALL", "ABILITY_GENERATION", "SPECIES", "CLASS", "BACKGROUND", "FEAT", "SKILLS", "ALIGNMENT",
					"SPELLS", "EQUIPMENT").contains(s)) {
				throw RpgException.invalidArgument(
						"Unknown choice scope '" + scope + "'. Supported: ALL, ABILITY_GENERATION, SPECIES, CLASS, BACKGROUND, FEAT, SKILLS, ALIGNMENT, SPELLS, EQUIPMENT.");
			}
			Row c = characterRef == null || characterRef.isBlank() ? null : character(tx, campaign.id(), characterRef);
			Optional<RulesData.Definition> cls = c == null ? Optional.empty() : classOf(tx, c);
			if (s.equals("ALL") || s.equals("ABILITY_GENERATION")) {
				var gen = new LinkedHashMap<String, Object>();
				gen.put("campaign_method", draft.abilityGeneration());
				gen.put("methods",
						Described.options(se.hirt.mcp.rpg.choice.AbilityGeneration.class).stream().map(Option::toMap)
								.toList());
				gen.put("standard_array", Rules.STANDARD_ARRAY);
				gen.put("point_buy", Map.of("budget", Rules.POINT_BUY_BUDGET, "min", Rules.POINT_BUY_MIN, "max",
						Rules.POINT_BUY_MAX));
				gen.put("roll", "4d6 drop lowest, six times, via generate_ability_scores");
				result.put("ability_generation", gen);
			}
			if (s.equals("ALL") || s.equals("SPECIES")) {
				result.put("species", choices.species().stream().map(Option::toMap).toList());
			}
			if (s.equals("ALL") || s.equals("BACKGROUND")) {
				result.put("backgrounds",
						choices.backgrounds(tx, campaign.id()).stream().map(Option::toMap).toList());
			}
			if (s.equals("ALL") || s.equals("FEAT")) {
				result.put("feats", choices.feats(null).stream().map(Option::toMap).toList());
			}
			if (s.equals("ALL") || s.equals("CLASS")) {
				result.put("classes", rules.ofKind("CLASS").stream().map(d -> {
					var m = Option.of(d.id(), d.name(), String.valueOf(d.payload().getOrDefault("summary", "")))
							.toMap();
					m.put("hit_die", d.payload().get("hit_die"));
					m.put("primary_abilities", d.payload().get("primary_abilities"));
					m.put("saving_throws", d.payload().get("saving_throws"));
					m.put("skill_choices", d.payload().get("skill_choices"));
					m.put("starting_gold_gp", d.payload().get("starting_gold_gp"));
					return m;
				}).toList());
			}
			if (s.equals("ALL") || s.equals("SKILLS")) {
				var skills = new LinkedHashMap<String, Object>();
				skills.put("all", choices.skills(null).stream().map(Option::toMap).toList());
				cls.ifPresent(k -> {
					skills.put("class", k.id());
					skills.put("choose", skillChoiceCount(k));
					skills.put("options", choices.skills(skillOptions(k)).stream().map(Option::toMap).toList());
				});
				result.put("skills", skills);
			}
			if (s.equals("ALL") || s.equals("ALIGNMENT")) {
				result.put("alignments", choices.alignments().stream().map(Option::toMap).toList());
			}
			if (s.equals("SPELLS") || (s.equals("ALL") && c != null)) {
				var spells = new LinkedHashMap<String, Object>();
				Optional<se.hirt.mcp.rpg.magic.SpellService.Casting> casting =
						c == null ? Optional.empty() : se.hirt.mcp.rpg.magic.SpellService.castingOf(tx, rules, c);
				if (casting.isEmpty()) {
					spells.put("note", c == null ? "Pass a character with a class to list its spells."
							: c.str("name") + " has no spellcasting.");
				} else {
					for (Decision d : choices.spellDecisions(casting.get())) {
						spells.put(d.id(), d.toMap());
					}
				}
				result.put("spells", spells);
			}
			if (s.equals("EQUIPMENT") || (s.equals("ALL") && c != null)) {
				result.put("equipment", cls.map(k -> choices.equipmentDecision(k).toMap()).orElse(Map.of("note",
						"Pass a character with a class to list its starting equipment options.")));
			}
			if (c != null && "DRAFT".equals(c.str("lifecycle"))) {
				result.put("decisions", Decision.render(choices.decisionsFor(tx, c, draft.abilityGeneration())));
			}
			result.put("meta", Harness.meta(campaign, null));
			return result;
		});
	}

	// ── generate_ability_scores ────────────────────────────────────────

	public Map<String, Object> generateAbilityScores(
			String operationId, String campaignRef, String characterRef, String method) {
		long campaignId = Ref.id(campaignRef, Ref.CAMPAIGN);
		var args = new LinkedHashMap<String, Object>();
		args.put("campaign", campaignRef);
		args.put("character", characterRef);
		args.put("method", method);
		return db.mutate(Database.Mutation.of("generate_ability_scores", campaignId, operationId, "PLAYER", args),
				tx -> {
					Row campaign = Harness.requireMutation(tx, campaignRef, "generate_ability_scores");
					Row c = draftCharacter(tx, campaignId, characterRef);
					SetupDraft draft = new SetupDraft(setupDraftRow(tx, campaignId).map("payload_json"));
					String campaignMethod = draft.abilityGeneration();
					String m = method == null || method.isBlank() ? campaignMethod : method.toUpperCase();
					if (!SetupDraft.ABILITY_METHODS.contains(m)) {
						throw RpgException.invalidArgument("method must be one of " + SetupDraft.ABILITY_METHODS + ".");
					}
					if (!m.equals(campaignMethod)) {
						throw RpgException.policyDenied(
								"The campaign rules use " + campaignMethod + "; change rules.ability_generation first.");
					}
					Map<String, Object> creation = c.map("creation_json");
					var result = new LinkedHashMap<String, Object>();
					result.put("character", Ref.of(Ref.CHARACTER, c.id()));
					result.put("method", m);
					switch (m) {
					case "STANDARD_ARRAY" -> {
						creation.put("method", m);
						creation.put("available_scores", Rules.STANDARD_ARRAY);
						result.put("available_scores", Rules.STANDARD_ARRAY);
						result.put("instructions",
								"Assign these six values to the six abilities via update_character_draft.ability_scores.");
					}
					case "POINT_BUY" -> {
						creation.put("method", m);
						result.put("budget", Rules.POINT_BUY_BUDGET);
						result.put("range", List.of(Rules.POINT_BUY_MIN, Rules.POINT_BUY_MAX));
						result.put("instructions",
								"Propose six scores between 8 and 15 costing at most 27 points via update_character_draft.ability_scores.");
					}
					default -> {
						if (creation.get("available_scores") != null && !draft.allowReroll()) {
							throw RpgException.conflict(
									"Ability scores were already rolled for " + characterRef + " and campaign rules do not allow rerolls (rules.allow_reroll).");
						}
						var rolls = new ArrayList<Map<String, Object>>();
						var available = new ArrayList<Integer>();
						for (int i = 0; i < 6; i++) {
							Roll roll = roller.roll("4d6dl1");
							recordRoll(tx, campaignId, "ability_generation", roll);
							var r = new LinkedHashMap<String, Object>();
							var allDice = new ArrayList<>(roll.dice());
							allDice.addAll(roll.dropped());
							r.put("dice", allDice);
							r.put("dropped", roll.dropped());
							r.put("result", roll.total());
							rolls.add(r);
							available.add(roll.total());
						}
						creation.put("method", m);
						creation.put("rolls", rolls);
						creation.put("available_scores", available);
						result.put("rolls", rolls);
						result.put("available_scores", available);
						result.put("instructions",
								"Assign the rolled values to the six abilities via update_character_draft.ability_scores.");
					}
					}
					tx.update("character", c.id(),
							Map.of("creation_json", Json.write(creation), "revision", c.lng("revision") + 1));
					tx.touched(Ref.of(Ref.CHARACTER, c.id()), c.lng("revision") + 1);
					result.put("revision", c.lng("revision") + 1);
					result.put("meta", Harness.meta(campaign, null));
					return result;
				});
	}

	public static long recordRoll(Tx tx, Long campaignId, String purpose, Roll roll) {
		var cols = new LinkedHashMap<String, Object>();
		cols.put("campaign_id", campaignId);
		cols.put("journal_id", tx.journalId());
		cols.put("purpose", purpose);
		cols.put("expression", roll.expression());
		cols.put("dice_json", Json.write(roll.dice()));
		cols.put("dropped_json", Json.write(roll.dropped()));
		cols.put("modifier", roll.modifier());
		cols.put("total", roll.total());
		return tx.insert("roll", cols);
	}

	// ── update_character_draft ─────────────────────────────────────────

	public Map<String, Object> updateDraft(
			String operationId, String campaignRef, String characterRef,
			Long expectedRevision, Map<String, Object> changes) {
		long campaignId = Ref.id(campaignRef, Ref.CAMPAIGN);
		var args = new LinkedHashMap<String, Object>();
		args.put("campaign", campaignRef);
		args.put("character", characterRef);
		args.put("changes", changes);
		return db.mutate(Database.Mutation.of("update_character_draft", campaignId, operationId, "PLAYER", args),
				tx -> {
					Row campaign = Harness.requireMutation(tx, campaignRef, "update_character_draft");
					Row c = draftCharacter(tx, campaignId, characterRef);
					Harness.requireRevision(c, characterRef, expectedRevision);
					if (changes == null || changes.isEmpty()) {
						throw RpgException.invalidArgument("changes must contain at least one field.");
					}
					SetupDraft draft = new SetupDraft(setupDraftRow(tx, campaignId).map("payload_json"));
					applyChanges(tx, draft, c, changes);
					refreshHarness(tx, campaignId, campaign);
					Row updated = tx.get("character", c.id());
					tx.touched(Ref.of(Ref.CHARACTER, c.id()), updated.lng("revision"));
					var result = new LinkedHashMap<String, Object>();
					result.put("character", Ref.of(Ref.CHARACTER, c.id()));
					result.put("revision", updated.lng("revision"));
					result.put("sheet", sheet(tx, updated, "PLAY"));
					result.put("next_steps", nextSteps(tx, updated));
					result.put("decisions", "DRAFT".equals(updated.str("lifecycle")) ? Decision.render(
							choices.decisionsFor(tx, updated, draft.abilityGeneration())) : List.of());
					result.put("meta", Harness.meta(tx.get("campaign", campaignId), null));
					return result;
				});
	}

	@SuppressWarnings("unchecked")
	private void applyChanges(Tx tx, SetupDraft draft, Row c, Map<String, Object> changes) {
		var cols = new LinkedHashMap<String, Object>();
		Map<String, Object> creation = c.map("creation_json");
		boolean creationDirty = false;
		for (var e : changes.entrySet()) {
			String key = e.getKey();
			Object value = e.getValue();
			if (!DRAFT_FIELDS.contains(key)) {
				throw RpgException.invalidArgument(
						"Unknown character field '" + key + "'. Known: " + DRAFT_FIELDS + ".");
			}
			switch (key) {
			case "name", "description", "appearance", "personality", "backstory", "presentation" ->
					cols.put(key, value == null ? null : value.toString().trim());
			case "goals" -> cols.put("goals_json",
					value == null ? null : Json.write(value instanceof List<?> l ? l : List.of(value.toString())));
			case "alignment" -> {
				String a = value == null ? null : value.toString().trim().toUpperCase().replace(' ', '_');
				if (a != null && !ALIGNMENTS.contains(a)) {
					throw RpgException.invalidArgument("alignment must be one of " + ALIGNMENTS + ".");
				}
				cols.put("alignment", a);
			}
			case "age" -> {
				if (value != null && !(value instanceof Number)) {
					throw RpgException.invalidArgument("age must be a number.");
				}
				cols.put("age", value == null ? null : ((Number) value).intValue());
			}
			case "species" -> {
				RulesData.Definition species = rules.resolve("SPECIES", String.valueOf(value)).orElseThrow(
						() -> RpgException.invalidArgument(
								"Unknown species '" + value + "'; see get_character_choices SPECIES."));
				cols.put("species_ref", species.id());
				Origins.onSpeciesSet(tx, rules, species, c, cols);
			}
			case "class" -> {
				RulesData.Definition cls = rules.resolve("CLASS", String.valueOf(value)).orElseThrow(
						() -> RpgException.invalidArgument(
								"Unknown class '" + value + "'; see get_character_choices CLASS."));
				for (Row existing : tx.query("SELECT id FROM character_class WHERE character_id = ?", c.id())) {
					tx.delete("character_class", existing.id());
				}
				tx.insert("character_class", Map.of("character_id", c.id(), "class_ref", cls.id(), "level", 1));
				// Saving-throw proficiencies come from the class; class-chosen skills and spells are reset,
				// background/species/feat grants stay.
				for (Row t : tx.query(
						"SELECT * FROM character_trait WHERE character_id = ? AND kind IN ('SAVE','SKILL','SPELL_KNOWN','SPELL_PREPARED')",
						c.id())) {
					if (Origins.SOURCE_CLASS.equals(Origins.sourceOf(t))) {
						tx.delete("character_trait", t.id());
					}
				}
				for (Object save : (List<Object>) cls.payload().get("saving_throws")) {
					tx.insert("character_trait",
							Map.of("character_id", c.id(), "kind", "SAVE", "content_ref", save.toString()));
				}
			}
			case "ability_scores" -> {
				if (!(value instanceof Map<?, ?> m)) {
					throw RpgException.invalidArgument(
							"ability_scores must be an object like {\"STR\": 9, \"DEX\": 14, ...}.");
				}
				var scores = new EnumMap<Ability, Integer>(Ability.class);
				for (var s : m.entrySet()) {
					Ability a = Ability.parse(String.valueOf(s.getKey()));
					if (!(s.getValue() instanceof Number n) || n.intValue() < Rules.MIN_SCORE || n.intValue() > Rules.MAX_SCORE) {
						throw RpgException.invalidArgument(a.fullName() + " must be an integer between 1 and 20.");
					}
					scores.put(a, n.intValue());
				}
				List<Integer> rolled = creation.get("available_scores") == null ? null
						: ((List<Object>) creation.get("available_scores")).stream().map(o -> ((Number) o).intValue())
								.toList();
				List<Violation> violations = Rules.validateScores(scores, draft.abilityGeneration(),
						"ROLL_4D6_DROP_LOWEST".equals(draft.abilityGeneration()) ? rolled : null);
				if (!violations.isEmpty()) {
					throw RpgException.validation(violations);
				}
				var base = new LinkedHashMap<String, Object>();
				for (var s : scores.entrySet()) {
					base.put(s.getKey().name(), s.getValue());
				}
				creation.put("base_scores", base);
				creationDirty = true;
			}
			case "background" -> {
				Origins.applyBackground(tx, rules, c, value, cols, creation);
				creationDirty = true;
			}
			case "background_ability_scores" -> {
				// Drafts scored before the origins milestone carry their base scores in the columns only;
				// capture them so the increase composes instead of compounding.
				if (!(creation.get("base_scores") instanceof Map<?, ?>)) {
					var base = new LinkedHashMap<String, Object>();
					for (Ability a : Ability.values()) {
						if (!c.isNull(a.column())) {
							base.put(a.name(), c.integer(a.column()));
						}
					}
					if (base.size() == 6) {
						creation.put("base_scores", base);
					}
				}
				Origins.applyBackgroundAbilityScores(requireBackground(tx, c, cols), value, creation);
				creationDirty = true;
			}
			case "species_skill" -> Origins.applySpeciesSkill(tx, rules, requireSpecies(c, cols), c, value);
			case "species_choice" -> Origins.applySpeciesChoice(tx, rules, requireSpecies(c, cols), c, value, cols);
			case "origin_feat" -> Origins.applyOriginFeat(tx, rules, requireSpecies(c, cols), c, value);
			case "feat_choices" -> Origins.applyFeatChoices(tx, rules, c, value);
			case "background_tool" -> Origins.applyBackgroundTool(tx, rules, requireBackground(tx, c, cols), c, value);
			case "skills" -> {
				if (!(value instanceof List<?> list)) {
					throw RpgException.invalidArgument("skills must be a list of skill names or ids.");
				}
				RulesData.Definition cls = classOf(tx, c).orElseThrow(
						() -> RpgException.invalidArgument("Choose a class before choosing skills."));
				List<String> options = skillOptions(cls);
				int count = skillChoiceCount(cls);
				var granted = new java.util.HashSet<String>();
				for (Row t : tx.query("SELECT * FROM character_trait WHERE character_id = ? AND kind = 'SKILL'",
						c.id())) {
					if (!Origins.SOURCE_CLASS.equals(Origins.sourceOf(t))) {
						granted.add(t.str("content_ref"));
					}
				}
				var chosen = new ArrayList<String>();
				for (Object o : list) {
					RulesData.Definition skill = rules.resolve("SKILL", String.valueOf(o))
							.orElseThrow(() -> RpgException.invalidArgument("Unknown skill '" + o + "'."));
					if (!options.contains(skill.id())) {
						throw RpgException.validation(List.of(new Violation("skills", "CLASS_SKILL_LIST",
								skill.name() + " is not on the " + cls.name() + " skill list: " + options)));
					}
					if (granted.contains(skill.id())) {
						throw RpgException.validation(List.of(new Violation("skills", "DUPLICATE_PROFICIENCY",
								skill.name() + " is already granted by the background or species; choose a different skill (SRD 5.2.1: duplicate proficiencies are re-chosen).")));
					}
					if (!chosen.contains(skill.id())) {
						chosen.add(skill.id());
					}
				}
				if (chosen.size() != count) {
					throw RpgException.validation(List.of(new Violation("skills", "SKILL_COUNT",
							cls.name() + " chooses exactly " + count + " skills; got " + chosen.size() + ".")));
				}
				for (Row t : tx.query("SELECT * FROM character_trait WHERE character_id = ? AND kind = 'SKILL'",
						c.id())) {
					if (Origins.SOURCE_CLASS.equals(Origins.sourceOf(t))) {
						tx.delete("character_trait", t.id());
					}
				}
				for (String id : chosen) {
					tx.insert("character_trait", Map.of("character_id", c.id(), "kind", "SKILL", "content_ref", id));
				}
			}
			case "cantrips", "spells" -> {
				se.hirt.mcp.rpg.magic.SpellService.Casting casting = se.hirt.mcp.rpg.magic.SpellService.castingOf(tx,
						rules, c).orElseThrow(() -> RpgException.validation(List.of(new Violation(key, "NOT_A_CASTER",
						"Choose a spellcasting class before choosing spells."))));
				List<Object> list =
						value instanceof List<?> l ? new ArrayList<Object>(l) : List.of(String.valueOf(value));
				List<Violation> spellViolations = se.hirt.mcp.rpg.magic.SpellService.setSpells(tx, rules, c, casting,
						key.equals("cantrips") ? list : null, key.equals("spells") ? list : null);
				if (!spellViolations.isEmpty()) {
					throw RpgException.validation(spellViolations);
				}
			}
			case "starting_equipment" -> {
				RulesData.Definition cls = classOf(tx, c).orElseThrow(
						() -> RpgException.invalidArgument("Choose a class before choosing starting equipment."));
				creation.put("starting_equipment",
						equipmentChoice(cls.name(), (Map<String, Object>) cls.payload().get("starting_equipment"),
								value));
				creationDirty = true;
			}
			case "background_equipment" -> {
				RulesData.Definition bg = requireBackground(tx, c, cols);
				creation.put("background_equipment",
						equipmentChoice(bg.name(), (Map<String, Object>) bg.payload().get("starting_equipment"),
								value));
				creationDirty = true;
			}
			default -> throw RpgException.invalidArgument("Unhandled field " + key);
			}
		}
		// Final ability columns = base scores + the background increase (SRD 5.2.1 "Ability Scores" under Backgrounds).
		if (creation.get("base_scores") instanceof Map<?, ?> baseRaw) {
			Map<String, Object> base = (Map<String, Object>) baseRaw;
			Map<String, Object> asi =
					creation.get("background_ability_scores") instanceof Map<?, ?> a ? (Map<String, Object>) a
							: Map.of();
			for (Ability a : Ability.values()) {
				if (base.get(a.name()) instanceof Number n) {
					int bonus = asi.get(a.name()) instanceof Number x ? x.intValue() : 0;
					cols.put(a.column(), n.intValue() + bonus);
				}
			}
		}
		if (creationDirty) {
			cols.put("creation_json", Json.write(creation));
		}
		// Derived: level-1 max HP = hit die + CON modifier (SRD 5.2.1 "Hit Points"), plus species bonuses
		// such as Dwarven Toughness.
		Row merged = new Row(mergedRow(c, cols));
		Optional<RulesData.Definition> cls = classOf(tx, merged);
		Integer con = merged.integer("con_score");
		if (cls.isPresent() && con != null) {
			int hitDie = ((Number) cls.get().payload().get("hit_die")).intValue();
			int level = Math.max(1, Origins.characterLevel(tx, c));
			cols.put("max_hp", Math.max(1, hitDie + Rules.modifier(con)) + Origins.hpPerLevel(rules, merged) * level);
		}
		cols.put("revision", c.lng("revision") + 1);
		tx.update("character", c.id(), cols);
	}

	/** Parses an equipment option answer ("A", or {option, choices}) against the offered options. */
	private Map<String, Object> equipmentChoice(String ownerName, Map<String, Object> options, Object value) {
		String option;
		Map<String, Object> choices = new LinkedHashMap<>();
		if (value instanceof Map<?, ?> m) {
			option = String.valueOf(m.get("option")).toUpperCase();
			if (m.get("choices") instanceof Map<?, ?> cm) {
				for (var ce : cm.entrySet()) {
					RulesData.Definition chosen = rules.resolve("ITEM", String.valueOf(ce.getValue())).orElseThrow(
							() -> RpgException.invalidArgument(
									"Unknown item '" + ce.getValue() + "' for choice " + ce.getKey() + "."));
					choices.put(String.valueOf(ce.getKey()), chosen.id());
				}
			}
		} else {
			option = String.valueOf(value).toUpperCase();
		}
		if (options == null || !options.containsKey(option)) {
			throw RpgException.validation(List.of(new Violation("starting_equipment.option", "UNKNOWN_OPTION",
					ownerName + " offers starting equipment options " + (options == null ? "[]"
							: options.keySet()) + "; got '" + option + "'.")));
		}
		var se = new LinkedHashMap<String, Object>();
		se.put("option", option);
		se.put("choices", choices);
		return se;
	}

	private RulesData.Definition requireSpecies(Row c, Map<String, Object> cols) {
		String ref = cols.get("species_ref") instanceof String s ? s : c.str("species_ref");
		return Optional.ofNullable(ref).flatMap(rules::find)
				.orElseThrow(() -> RpgException.invalidArgument("Choose a species first."));
	}

	private RulesData.Definition requireBackground(Tx tx, Row c, Map<String, Object> cols) {
		String ref = cols.get("background_ref") instanceof String s ? s : c.str("background_ref");
		return Optional.ofNullable(ref)
				.flatMap(r -> r.startsWith("content:") ? Origins.customBackground(tx, c.lng("campaign_id"), r)
						: rules.find(r))
				.orElseThrow(() -> RpgException.invalidArgument("Choose a background first."));
	}

	private static Map<String, Object> mergedRow(Row base, Map<String, Object> overrides) {
		Map<String, Object> m = base.asMap();
		m.putAll(overrides);
		return m;
	}

	// ── validate / commit ──────────────────────────────────────────────

	public Map<String, Object> validateDraft(String campaignRef, String characterRef) {
		return db.read(tx -> {
			Row campaign = Harness.campaign(tx, campaignRef);
			Row c = character(tx, campaign.id(), characterRef);
			SetupDraft draft = new SetupDraft(setupDraftRow(tx, campaign.id()).map("payload_json"));
			List<Violation> violations = validate(tx, draft, c);
			var result = new LinkedHashMap<String, Object>();
			result.put("character", Ref.of(Ref.CHARACTER, c.id()));
			result.put("lifecycle", c.str("lifecycle"));
			result.put("valid", violations.isEmpty());
			result.put("violations", violations.stream().map(Violation::toMap).toList());
			result.put("warnings", warnings(c));
			result.put("review", sheet(tx, c, "FULL"));
			result.put("meta", Harness.meta(campaign, null));
			return result;
		});
	}

	private List<Violation> validate(Tx tx, SetupDraft draft, Row c) {
		var v = new ArrayList<Violation>();
		if (c.isNull("name") || c.str("name").isBlank()) {
			v.add(new Violation("name", "REQUIRED", "The character needs a name."));
		}
		if (c.isNull("species_ref")) {
			v.add(new Violation("species", "REQUIRED", "Choose a species."));
		}
		Optional<RulesData.Definition> cls = classOf(tx, c);
		if (cls.isEmpty()) {
			v.add(new Violation("class", "REQUIRED", "Choose a class."));
		}
		// Base scores are validated against the generation method; the columns carry the background increase.
		Map<String, Object> creation = c.map("creation_json");
		var scores = new EnumMap<Ability, Integer>(Ability.class);
		if (creation.get("base_scores") instanceof Map<?, ?> baseRaw) {
			for (var s : baseRaw.entrySet()) {
				if (s.getValue() instanceof Number n) {
					scores.put(Ability.parse(String.valueOf(s.getKey())), n.intValue());
				}
			}
		} else {
			for (Ability a : Ability.values()) {
				if (!c.isNull(a.column())) {
					scores.put(a, c.integer(a.column()));
				}
			}
		}
		if (scores.size() < 6) {
			v.add(new Violation("ability_scores", "REQUIRED", "All six ability scores must be assigned."));
		} else {
			@SuppressWarnings("unchecked") List<Object> rolledRaw = (List<Object>) creation.get("available_scores");
			List<Integer> rolled =
					rolledRaw == null ? null : rolledRaw.stream().map(o -> ((Number) o).intValue()).toList();
			v.addAll(Rules.validateScores(scores, draft.abilityGeneration(),
					"ROLL_4D6_DROP_LOWEST".equals(draft.abilityGeneration()) ? rolled : null));
		}
		cls.ifPresent(d -> {
			long skills = tx.query("SELECT * FROM character_trait WHERE character_id = ? AND kind = 'SKILL'", c.id())
					.stream().filter(t -> Origins.SOURCE_CLASS.equals(Origins.sourceOf(t))).count();
			int expected = skillChoiceCount(d);
			if (skills != expected) {
				v.add(new Violation("skills", "SKILL_COUNT",
						d.name() + " must choose exactly " + expected + " class skills; " + skills + " chosen."));
			}
		});
		v.addAll(Origins.validate(tx, rules, c));
		return v;
	}

	private static List<String> warnings(Row c) {
		var w = new ArrayList<String>();
		if (c.isNull("personality")) {
			w.add("No personality summary yet; companions and the Director rely on it.");
		}
		if (c.isNull("alignment")) {
			w.add("No alignment chosen; propose one from the personality interview.");
		}
		if (c.isNull("backstory")) {
			w.add("No backstory summary; it seeds adventure hooks.");
		}
		if (c.map("creation_json").get("starting_equipment") == null) {
			w.add("No starting_equipment option chosen; the gold-only option will be used at campaign commit.");
		}
		return w;
	}

	public Map<String, Object> commitDraft(
			String operationId, String campaignRef, String characterRef, Long expectedRevision) {
		long campaignId = Ref.id(campaignRef, Ref.CAMPAIGN);
		var args = new LinkedHashMap<String, Object>();
		args.put("campaign", campaignRef);
		args.put("character", characterRef);
		return db.mutate(Database.Mutation.of("commit_character_draft", campaignId, operationId, "PLAYER", args),
				tx -> {
					Row campaign = Harness.requireMutation(tx, campaignRef, "commit_character_draft");
					Row c = draftCharacter(tx, campaignId, characterRef);
					Harness.requireRevision(c, characterRef, expectedRevision);
					SetupDraft draft = new SetupDraft(setupDraftRow(tx, campaignId).map("payload_json"));
					List<Violation> violations = validate(tx, draft, c);
					if (!violations.isEmpty()) {
						throw RpgException.validation(violations);
					}
					tx.update("character", c.id(),
							Map.of("lifecycle", "FINALIZED_DRAFT", "revision", c.lng("revision") + 1));
					tx.touched(Ref.of(Ref.CHARACTER, c.id()), c.lng("revision") + 1);
					refreshHarness(tx, campaignId, campaign);
					Row finalized = tx.get("character", c.id());
					var result = new LinkedHashMap<String, Object>();
					result.put("character", Ref.of(Ref.CHARACTER, c.id()));
					result.put("lifecycle", "FINALIZED_DRAFT");
					result.put("revision", finalized.lng("revision"));
					result.put("sheet", sheet(tx, finalized, "FULL"));
					result.put("warnings", warnings(finalized));
					result.put("meta", Harness.meta(tx.get("campaign", campaignId), null));
					return result;
				});
	}

	// ── update_character (canonical narrative identity) ────────────────

	/** Narrative/identity fields that may be changed on a committed character (MCP_PROTOCOL.md §10.9). */
	private static final Set<String> NARRATIVE_FIELDS = Set.of("name", "description", "appearance", "personality",
			"backstory", "goals", "age", "presentation", "alignment");

	/**
	 * Applies canonical narrative changes to a non-draft character: name, appearance, personality, backstory, goals,
	 * age and presentation. Mechanical state is never touched here — that belongs to rules-governed transactions or an
	 * audited override.
	 */
	public Map<String, Object> updateCharacter(
			String operationId, String campaignRef, String characterRef,
			Long expectedRevision, Map<String, Object> changes) {
		long campaignId = Ref.id(campaignRef, Ref.CAMPAIGN);
		var args = new LinkedHashMap<String, Object>();
		args.put("campaign", campaignRef);
		args.put("character", characterRef);
		args.put("changes", changes);
		return db.mutate(Database.Mutation.of("update_character", campaignId, operationId, "GM", args), tx -> {
			Row campaign = Harness.requireMutation(tx, campaignRef, "update_character");
			Row c = character(tx, campaignId, characterRef);
			if ("DRAFT".equals(c.str("lifecycle"))) {
				throw RpgException.notAllowed(characterRef + " is a DRAFT; edit it with update_character_draft.");
			}
			Harness.requireRevision(c, characterRef, expectedRevision);
			if (changes == null || changes.isEmpty()) {
				throw RpgException.invalidArgument(
						"changes must contain at least one field. Known: " + NARRATIVE_FIELDS + ".");
			}
			var cols = new LinkedHashMap<String, Object>();
			for (var e : changes.entrySet()) {
				String key = e.getKey();
				Object value = e.getValue();
				if (!NARRATIVE_FIELDS.contains(key)) {
					throw RpgException.invalidArgument(
							"'" + key + "' is not a narrative field. Known: " + NARRATIVE_FIELDS + ". Mechanical state changes go through their own operations or an audited override.");
				}
				switch (key) {
				case "goals" -> cols.put("goals_json",
						value == null ? null : Json.write(value instanceof List<?> l ? l : List.of(value.toString())));
				case "age" -> {
					if (value != null && !(value instanceof Number)) {
						throw RpgException.invalidArgument("age must be a number.");
					}
					cols.put("age", value == null ? null : ((Number) value).intValue());
				}
				case "alignment" -> {
					String a = value == null ? null : value.toString().trim().toUpperCase().replace(' ', '_');
					if (a != null && !ALIGNMENTS.contains(a)) {
						throw RpgException.invalidArgument("alignment must be one of " + ALIGNMENTS + ".");
					}
					cols.put("alignment", a);
				}
				default -> cols.put(key, value == null ? null : value.toString().trim());
				}
			}
			cols.put("revision", c.lng("revision") + 1);
			tx.update("character", c.id(), cols);
			Row updated = tx.get("character", c.id());
			tx.touched(Ref.of(Ref.CHARACTER, c.id()), updated.lng("revision"));
			var result = new LinkedHashMap<String, Object>();
			result.put("character", Ref.of(Ref.CHARACTER, c.id()));
			result.put("changed", changes.keySet());
			result.put("revision", updated.lng("revision"));
			result.put("sheet", sheet(tx, updated, "FULL"));
			result.put("meta", Harness.meta(campaign, null));
			return result;
		});
	}

	/**
	 * What a party member still lacks before they are a character a player could inherit. A companion recruited from a
	 * creature definition starts as a stat block; until they are promoted, {@code transfer_player_control} would hand
	 * the player a sheet with no class, no proficiencies and no origin. Reported so the gap is visible before it
	 * matters rather than at the moment a player character dies (RULES_ENGINE.md §6).
	 */
	public static List<String> sheetGaps(Tx tx, Row c) {
		var gaps = new java.util.ArrayList<String>();
		if (tx.count("SELECT COUNT(*) FROM character_class WHERE character_id = ?", c.id()) == 0) {
			gaps.add("class");
		}
		if (c.isNull("species_ref")) {
			gaps.add("species");
		}
		if (c.isNull("background_ref")) {
			gaps.add("background");
		}
		if (c.isNull("alignment")) {
			gaps.add("alignment");
		}
		if (tx.count("SELECT COUNT(*) FROM inventory_entry WHERE character_id = ?", c.id()) == 0) {
			gaps.add("inventory");
		}
		return gaps;
	}

	// ── get_character_sheet ────────────────────────────────────────────

	public Map<String, Object> characterSheet(String campaignRef, String characterRef, String detail) {
		return db.read(tx -> {
			Row campaign = Harness.campaign(tx, campaignRef);
			Row c = character(tx, campaign.id(), characterRef);
			String d = detail == null || detail.isBlank() ? "PLAY" : detail.toUpperCase();
			if (!Set.of("SUMMARY", "PLAY", "FULL").contains(d)) {
				throw RpgException.invalidArgument("detail must be SUMMARY, PLAY or FULL.");
			}
			var result = new LinkedHashMap<String, Object>(sheet(tx, c, d));
			result.put("meta", Harness.meta(campaign, null));
			return result;
		});
	}

	/** Compact sheet at the requested detail level; safe for LLM context. */
	public Map<String, Object> sheet(Tx tx, Row c, String detail) {
		var m = new LinkedHashMap<String, Object>();
		m.put("ref", Ref.of(Ref.CHARACTER, c.id()));
		m.put("name", c.str("name"));
		m.put("lifecycle", c.str("lifecycle"));
		m.put("life_state", c.str("life_state"));
		rules.find(c.str("species_ref") == null ? "" : c.str("species_ref")).ifPresent(s -> m.put("species", s.name()));
		List<Row> classes = tx.query("SELECT * FROM character_class WHERE character_id = ? ORDER BY id", c.id());
		int level = classes.stream().mapToInt(r -> r.intOr("level", 1)).sum();
		m.put("classes", classes.stream().map(r -> rules.find(r.str("class_ref")).map(RulesData.Definition::name)
				.orElse(r.str("class_ref")) + " " + r.lng("level")).toList());
		m.put("level", classes.isEmpty() ? null : level);
		var hp = new LinkedHashMap<String, Object>();
		hp.put("current", c.integer("current_hp"));
		hp.put("max", c.integer("max_hp"));
		hp.put("temp", c.integer("temp_hp"));
		m.put("hp", hp);
		if (detail.equals("SUMMARY")) {
			return m;
		}
		var abilities = new LinkedHashMap<String, Object>();
		for (Ability a : Ability.values()) {
			Integer score = c.integer(a.column());
			if (score != null) {
				abilities.put(a.name(), Map.of("score", score, "modifier", Rules.modifier(score)));
			}
		}
		m.put("abilities", abilities);
		int prof = rules.proficiencyBonus(Math.max(1, level));
		m.put("proficiency_bonus", prof);
		List<Row> traits = tx.query("SELECT * FROM character_trait WHERE character_id = ? ORDER BY id", c.id());
		m.put("saving_throw_proficiencies",
				traits.stream().filter(t -> "SAVE".equals(t.str("kind"))).map(t -> t.str("content_ref")).toList());
		m.put("skill_proficiencies", traits.stream().filter(t -> "SKILL".equals(t.str("kind")))
				.map(t -> rules.find(t.str("content_ref")).map(RulesData.Definition::name).orElse(t.str("content_ref")))
				.toList());
		m.put("speed", c.integer("speed"));
		m.put("xp", c.lng("xp"));
		m.put("level_up_eligible", level > 0 && rules.levelForXp(c.lng("xp")) > level);
		m.put("conditions", RuntimeService.conditions(tx, c.id()));
		if (!c.isNull("death_saves_json")) {
			m.put("death_saves", c.map("death_saves_json"));
		}
		m.put("encounter", Ref.ofNullable(Ref.ENCOUNTER, c.lng("encounter_id")));
		m.put("effects", se.hirt.mcp.rpg.rules.Effects.view(tx, c.id()));
		Map<String, Object> spellcasting = se.hirt.mcp.rpg.magic.SpellService.sheet(tx, rules, c);
		if (spellcasting != null) {
			m.put("spellcasting", spellcasting);
		}
		if (!c.isNull("origin_content_ref")) {
			rules.find(c.str("origin_content_ref")).ifPresent(d -> {
				var creature = new LinkedHashMap<String, Object>();
				creature.put("definition", d.id());
				creature.put("cr", d.payload().get("cr"));
				creature.put("xp_value", d.payload().get("xp_value"));
				creature.put("armor_class", d.payload().get("ac"));
				creature.put("actions", d.payload().get("actions"));
				creature.put("multiattack", d.payload().get("multiattack"));
				creature.put("traits", d.payload().get("traits"));
				creature.put("defenses",
						Map.of("resistances", d.payload().getOrDefault("damage_resistances", List.of()),
								"vulnerabilities", d.payload().getOrDefault("damage_vulnerabilities", List.of()),
								"immunities", d.payload().getOrDefault("damage_immunities", List.of())));
				m.put("creature", creature);
			});
		}
		List<Map<String, Object>> classFeatures =
				se.hirt.mcp.rpg.progression.ClassFeatures.featuresOf(tx, rules, c);
		if (!classFeatures.isEmpty()) {
			m.put("class_features", classFeatures.stream().map(f -> {
				var e = new LinkedHashMap<String, Object>();
				e.put("name", f.get("name"));
				e.put("class", f.get("class"));
				e.put("level", f.get("level"));
				e.put("summary", f.get("summary"));
				e.put("adjudication", "ENGINE".equals(f.get("enforcement")) ? "engine" : "GM");
				if (f.get("mechanic") instanceof Map<?, ?> mech && "SNEAK_ATTACK".equals(
						((Map<String, Object>) mech).get("kind"))) {
					se.hirt.mcp.rpg.progression.ClassFeatures.mechanic(tx, rules, c, "SNEAK_ATTACK")
							.ifPresent(x -> e.put("dice", x.scaledDice()));
				}
				return e;
			}).toList());
		}
		Origins.appendSheet(tx, rules, c, m, detail);
		m.put("money", money(c.lng("money_cp")));
		if ("ACTIVE".equals(c.str("lifecycle"))) {
			m.put("armor_class", RuntimeService.usesStatBlock(tx, c)
					? Map.of("value", RuntimeService.armorClass(tx, rules, c), "basis", "stat block")
					: InventoryService.armorClass(tx, rules, c));
			m.put("carrying", InventoryService.carrying(tx, rules, c));
			m.put("inventory", InventoryService.entries(tx, rules, c.id()));
		} else {
			m.put("starting_equipment", startingEquipmentPreview(tx, c));
		}
		m.put("alignment", c.str("alignment"));
		m.put("location", Ref.ofNullable(Ref.LOCATION, c.lng("location_id")));
		m.put("revision", c.lng("revision"));
		if (detail.equals("PLAY")) {
			m.put("personality", c.str("personality"));
			return m;
		}
		m.put("description", c.str("description"));
		m.put("appearance", c.str("appearance"));
		m.put("personality", c.str("personality"));
		m.put("backstory", c.str("backstory"));
		m.put("goals", c.isNull("goals_json") ? List.of() : c.list("goals_json"));
		m.put("age", c.integer("age"));
		m.put("presentation", c.str("presentation"));
		m.put("creation", c.map("creation_json"));
		if (!c.isNull("agenda_json")) {
			m.put("agenda", Map.of("visibility", "GM_ONLY", "agenda", c.map("agenda_json")));
		}
		return m;
	}

	/** Renders canonical copper as SRD denominations (DESIGN.md §12). */
	public static Map<String, Object> money(Long cp) {
		return Money.render(cp);
	}

	/** What the draft will receive at commit: the chosen (or default gold-only) starting equipment option. */
	@SuppressWarnings("unchecked")
	private Map<String, Object> startingEquipmentPreview(Tx tx, Row c) {
		Optional<RulesData.Definition> cls = classOf(tx, c);
		if (cls.isEmpty()) {
			return null;
		}
		Map<String, Object> options = (Map<String, Object>) cls.get().payload().get("starting_equipment");
		Map<String, Object> chosen =
				c.map("creation_json").get("starting_equipment") instanceof Map<?, ?> m ? (Map<String, Object>) m
						: null;
		String key = chosen == null ? InventoryService.goldOnlyOption(options) : String.valueOf(chosen.get("option"));
		var preview = new LinkedHashMap<String, Object>();
		preview.put("option", key);
		preview.put("chosen_explicitly", chosen != null);
		preview.put("contents", options.get(key));
		preview.put("choices", chosen == null ? Map.of() : chosen.get("choices"));
		preview.put("available_options", options.keySet());
		return preview;
	}

	private List<String> nextSteps(Tx tx, Row c) {
		var steps = new ArrayList<String>();
		if (c.isNull("species_ref")) {
			steps.add("choose species");
		}
		if (classOf(tx, c).isEmpty()) {
			steps.add("choose class");
		}
		if (c.isNull("background_ref")) {
			steps.add("choose background (ability increase, origin feat, skills, tool, equipment)");
		}
		if (c.isNull("str_score")) {
			steps.add("generate/assign ability scores");
		} else if (tx.query("SELECT * FROM character_trait WHERE character_id = ? AND kind = 'SKILL'", c.id()).stream()
				.noneMatch(t -> Origins.SOURCE_CLASS.equals(Origins.sourceOf(t)))) {
			steps.add("choose class skills");
		}
		if (c.isNull("name")) {
			steps.add("name the character");
		}
		if (se.hirt.mcp.rpg.magic.SpellService.castingOf(tx, rules, c).isPresent()) {
			boolean classCantrips = tx.query(
							"SELECT * FROM character_trait WHERE character_id = ? AND kind = 'SPELL_KNOWN'", c.id()).stream()
					.anyMatch(t -> Origins.SOURCE_CLASS.equals(Origins.sourceOf(t)));
			boolean classSpells = tx.query(
							"SELECT * FROM character_trait WHERE character_id = ? AND kind = 'SPELL_PREPARED'", c.id()).stream()
					.anyMatch(t -> Origins.SOURCE_CLASS.equals(Origins.sourceOf(t)));
			if (!classCantrips || !classSpells) {
				steps.add("choose cantrips and prepared spells (get_content_definitions kind SPELL)");
			}
		}
		if (c.isNull("personality")) {
			steps.add("personality, backstory, appearance, alignment");
		}
		if (steps.isEmpty()) {
			steps.add("validate_character_draft, then commit_character_draft");
		}
		return steps;
	}

	// ── helpers ────────────────────────────────────────────────────────

	static Row setupDraftRow(Tx tx, long campaignId) {
		return tx.queryOne("SELECT * FROM campaign_setup_draft WHERE campaign_id = ?", campaignId)
				.orElseThrow(() -> RpgException.notFound("Setup draft for campaign " + campaignId));
	}

	private static void refreshHarness(Tx tx, long campaignId, Row campaign) {
		if (!"SETUP".equals(campaign.str("status"))) {
			return;
		}
		SetupDraft draft = new SetupDraft(setupDraftRow(tx, campaignId).map("payload_json"));
		Row current = tx.get("campaign", campaignId);
		String state = draft.deriveState(tx, campaignId).name();
		if (!state.equals(current.str("harness_state"))) {
			tx.update("campaign", campaignId, Map.of("harness_state", state));
		}
	}

	public static Row character(Tx tx, long campaignId, String characterRef) {
		long id = Ref.id(characterRef, Ref.CHARACTER);
		Row c = tx.find("character", id).orElseThrow(() -> RpgException.notFound("Character " + characterRef));
		if (c.lng("campaign_id") != campaignId) {
			throw RpgException.invalidArgument(characterRef + " belongs to another campaign.");
		}
		return c;
	}

	private static Row draftCharacter(Tx tx, long campaignId, String characterRef) {
		Row c = character(tx, campaignId, characterRef);
		if (!"DRAFT".equals(c.str("lifecycle"))) {
			throw RpgException.notAllowed(
					characterRef + " is " + c.str("lifecycle") + "; only DRAFT characters can be edited this way.");
		}
		return c;
	}

	Optional<RulesData.Definition> classOf(Tx tx, Row c) {
		return tx.queryOne("SELECT class_ref FROM character_class WHERE character_id = ? ORDER BY id LIMIT 1", c.id())
				.flatMap(r -> rules.find(r.str("class_ref")));
	}

	@SuppressWarnings("unchecked")
	List<String> skillOptions(RulesData.Definition cls) {
		Map<String, Object> choices = (Map<String, Object>) cls.payload().get("skill_choices");
		Object options = choices.get("options");
		if ("ANY".equals(options)) {
			return rules.ofKind("SKILL").stream().map(RulesData.Definition::id).toList();
		}
		return ((List<Object>) options).stream().map(Object::toString).toList();
	}

	@SuppressWarnings("unchecked")
	static int skillChoiceCount(RulesData.Definition cls) {
		Map<String, Object> choices = (Map<String, Object>) cls.payload().get("skill_choices");
		return ((Number) choices.get("count")).intValue();
	}
}
