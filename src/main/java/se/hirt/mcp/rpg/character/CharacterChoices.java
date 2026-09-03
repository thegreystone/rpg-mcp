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

import se.hirt.mcp.rpg.choice.*;
import se.hirt.mcp.rpg.content.RulesData;
import se.hirt.mcp.rpg.inventory.InventoryService;
import se.hirt.mcp.rpg.magic.SpellService;
import se.hirt.mcp.rpg.persistence.Row;
import se.hirt.mcp.rpg.persistence.Tx;
import se.hirt.mcp.rpg.protocol.Ref;
import se.hirt.mcp.rpg.rules.Rules;

import java.util.*;

/**
 * The legal options of every character-creation decision, each with a description, derived from the installed rules
 * content and the {@link Described} enums — never authored twice. Serves both {@code get_character_choices} and the
 * ordered {@code decisions} list of a draft (MCP_PROTOCOL.md §9.3.1, §10.2).
 */
public final class CharacterChoices {

	private static final int DESCRIPTION_LIMIT = 220;

	private final RulesData rules;

	public CharacterChoices(RulesData rules) {
		this.rules = rules;
	}

	// ── option lists ───────────────────────────────────────────────────

	public List<Option> species() {
		return rules.ofKind("SPECIES").stream().map(d -> option(d).details(pick(d.payload(), "speed", "size")))
				.toList();
	}

	public List<Option> classes() {
		return rules.ofKind("CLASS").stream().map(d -> option(d).details(
				pick(d.payload(), "hit_die", "primary_abilities", "saving_throws", "starting_gold_gp"))).toList();
	}

	/** All skills, or the given subset (skill ids), each with its ability and description. */
	public List<Option> skills(List<String> ids) {
		var out = new ArrayList<Option>();
		for (RulesData.Definition d : rules.ofKind("SKILL")) {
			if (ids == null || ids.contains(d.id())) {
				out.add(option(d).details(pick(d.payload(), "ability")));
			}
		}
		return out;
	}

	public List<Option> alignments() {
		return Described.options(Alignment.class);
	}

	/** Spells a caster of the given class slug may take at the given level (0 = cantrips). */
	@SuppressWarnings("unchecked")
	public List<Option> spells(String classSlug, int level) {
		var out = new ArrayList<Option>();
		for (RulesData.Definition d : rules.ofKind("SPELL")) {
			Map<String, Object> p = d.payload();
			Object classes = p.get("classes");
			int spellLevel = p.get("level") instanceof Number n ? n.intValue() : -1;
			if (spellLevel == level && classes instanceof List<?> l && l.contains(classSlug)) {
				var details = new LinkedHashMap<String, Object>();
				details.put("level", spellLevel);
				details.put("school", p.get("school"));
				if (Boolean.TRUE.equals(p.get("concentration"))) {
					details.put("concentration", true);
				}
				out.add(new Option(d.id(), d.name(), clip(String.valueOf(p.getOrDefault("text", ""))), false, details));
			}
		}
		return out;
	}

	/** The class's starting-equipment options rendered with item names and gold. */
	@SuppressWarnings("unchecked")
	public List<Option> equipment(RulesData.Definition cls) {
		Map<String, Object> options = (Map<String, Object>) cls.payload().get("starting_equipment");
		var out = new ArrayList<Option>();
		if (options == null) {
			return out;
		}
		String goldOnly = InventoryService.goldOnlyOption(options);
		for (var e : options.entrySet()) {
			Map<String, Object> bundle = (Map<String, Object>) e.getValue();
			var parts = new ArrayList<String>();
			if (bundle.get("items") instanceof List<?> items) {
				for (Object o : items) {
					Map<String, Object> line = (Map<String, Object>) o;
					String name;
					if (line.get("item") != null) {
						name = rules.find(String.valueOf(line.get("item"))).map(RulesData.Definition::name)
								.orElse(String.valueOf(line.get("item")));
					} else {
						// A bundle choice slot (HOLY_SYMBOL, GAMING_SET, ...) rendered by its default item.
						name = rules.find(String.valueOf(line.get("default"))).map(RulesData.Definition::name)
								.orElse(String.valueOf(line.get("choice"))) + " (or another " + String.valueOf(
								line.get("choice")).toLowerCase().replace('_', ' ') + ")";
					}
					int qty = line.get("quantity") instanceof Number n ? n.intValue() : 1;
					parts.add(qty > 1 ? name + " ×" + qty : name);
				}
			}
			Object gold = bundle.get("gold_gp");
			if (gold != null) {
				parts.add(gold + " gp");
			}
			boolean isGoldOnly = e.getKey().equals(goldOnly);
			String label = "Option " + e.getKey() + (isGoldOnly ? " — money only" : " — equipment kit");
			String description =
					isGoldOnly ? gold + " gp to buy your own equipment in play." : String.join(", ", parts) + ".";
			out.add(new Option(e.getKey(), label, description, false, Map.of("contents", bundle)));
		}
		return out;
	}

	// ── decisions for a draft ──────────────────────────────────────────

	/** Every decision this draft still needs, in creation order; a review decision when nothing is missing. */
	@SuppressWarnings("unchecked")
	public List<Decision> decisionsFor(Tx tx, Row c, String abilityMethod) {
		String ref = Ref.of(Ref.CHARACTER, c.id());
		var out = new ArrayList<Decision>();
		Optional<RulesData.Definition> cls = classOf(tx, c);
		Optional<RulesData.Definition> speciesDef = Origins.speciesOf(rules, c);
		Optional<RulesData.Definition> backgroundDef = Origins.backgroundOf(rules, c);
		if (speciesDef.isEmpty()) {
			out.add(Decision.of("species", "Which species is the character?")
					.recordedBy("update_character_draft", "changes.species").legal(species()));
		}
		if (cls.isEmpty()) {
			out.add(Decision.of("class", "Which class is the character?")
					.recordedBy("update_character_draft", "changes.class").legal(classes()));
		}
		if (backgroundDef.isEmpty()) {
			out.add(Decision.of("background", "Which background shaped the character before adventuring?")
					.recordedBy("update_character_draft", "changes.background").legal(backgrounds())
					.note("A background grants +2/+1 or +1/+1/+1 among its three abilities, an Origin feat, two skills, a tool proficiency and starting equipment (SRD 5.2.1 \"Character Backgrounds\")."));
		}
		if (c.isNull("str_score")) {
			out.add(abilityScores(c, cls.orElse(null), abilityMethod));
		} else {
			if (backgroundDef.isPresent() && c.map("creation_json").get("background_ability_scores") == null) {
				out.add(backgroundAsiDecision(backgroundDef.get()));
			}
			if (cls.isPresent() && tx.query("SELECT * FROM character_trait WHERE character_id = ? AND kind = 'SKILL'",
					c.id()).stream().noneMatch(t -> Origins.SOURCE_CLASS.equals(Origins.sourceOf(t)))) {
				out.add(skillDecision(tx, c, cls.get()));
			}
		}
		speciesDef.ifPresent(species -> {
			if (Origins.traitWith(species, "skill_grant").isPresent() && tx.query(
							"SELECT * FROM character_trait WHERE character_id = ? AND kind = 'SKILL'", c.id()).stream()
					.noneMatch(t -> Origins.SOURCE_SPECIES.equals(Origins.sourceOf(t)))) {
				out.add(speciesSkillDecision(tx, c, species));
			}
			if (Origins.traitWith(species, "choice").isPresent() && Origins.speciesChoiceRow(tx, c).isEmpty()) {
				out.add(speciesChoiceDecision(species));
			}
			if (Origins.traitWith(species, "feat_grant").isPresent() && Origins.featRows(tx, c).stream()
					.noneMatch(f -> Origins.SOURCE_SPECIES.equals(Origins.sourceOf(f)))) {
				out.add(originFeatDecision(species));
			}
		});
		for (Row f : Origins.featRows(tx, c)) {
			if (f.map("payload_json").get("pending") instanceof List<?> pending && !pending.isEmpty()) {
				out.add(featChoicesDecision(tx, c, f));
			}
		}
		backgroundDef.ifPresent(bg -> {
			Map<String, Object> tool = Origins.castMap(bg.payload().get("tool"));
			if (tool.get("choice") != null && tx.query(
							"SELECT * FROM character_trait WHERE character_id = ? AND kind = 'PROFICIENCY'", c.id()).stream()
					.noneMatch(t -> Origins.SOURCE_BACKGROUND.equals(Origins.sourceOf(t)))) {
				out.add(backgroundToolDecision(bg));
			}
		});
		if (cls.isPresent()) {
			Optional<SpellService.Casting> casting = SpellService.castingOf(tx, rules, c);
			if (casting.isPresent()) {
				// Cantrips and prepared spells gate independently (species/feat-granted spells don't count).
				boolean classCantrips = tx.query(
								"SELECT * FROM character_trait WHERE character_id = ? AND kind = 'SPELL_KNOWN'", c.id())
						.stream().anyMatch(t -> Origins.SOURCE_CLASS.equals(Origins.sourceOf(t)));
				boolean classSpells = tx.query(
								"SELECT * FROM character_trait WHERE character_id = ? AND kind = 'SPELL_PREPARED'", c.id())
						.stream().anyMatch(t -> Origins.SOURCE_CLASS.equals(Origins.sourceOf(t)));
				for (Decision d : spellDecisions(casting.get())) {
					if (("cantrips".equals(d.id()) && !classCantrips) || ("spells".equals(d.id()) && !classSpells)) {
						out.add(d);
					}
				}
			}
			Map<String, Object> chosen =
					c.map("creation_json").get("starting_equipment") instanceof Map<?, ?> m ? (Map<String, Object>) m
							: null;
			if (chosen == null) {
				out.add(equipmentDecision(cls.get()));
			}
		}
		backgroundDef.ifPresent(bg -> {
			if (!(c.map("creation_json").get("background_equipment") instanceof Map<?, ?>)) {
				out.add(backgroundEquipmentDecision(bg));
			}
		});
		if (c.isNull("name")) {
			out.add(Decision.of("name", "What is the character's name?")
					.recordedBy("update_character_draft", "changes.name").custom().surpriseMe());
		}
		if (c.isNull("personality")) {
			out.add(Decision.of("personality",
							"Who are they? Personality, backstory, appearance and goals — proposed by the GM, written by the player, or delegated.")
					.recordedBy("update_character_draft", "changes.personality")
					.note("Also record backstory, appearance, goals, age and presentation in the same call when known.")
					.custom().surpriseMe());
		}
		if (c.isNull("age")) {
			out.add(Decision.of("age", "How old is the character, and how do they present themselves?")
					.recordedBy("update_character_draft", "changes.age").custom().surpriseMe().optional(null)
					.note("Record age (a number) and presentation in the same call; both are tracked on the sheet and matter to how NPCs read them."));
		}
		if (c.isNull("alignment")) {
			out.add(Decision.of("alignment", "Which alignment fits them?")
					.recordedBy("update_character_draft", "changes.alignment").legal(alignments()).optional(null)
					.note("Suggest one from the personality; the player may pick any."));
		}
		if (out.isEmpty()) {
			out.add(Decision.of("character_review",
							"Read the sheet back in prose. Lock the character in, or change something first?")
					.recordedBy("commit_character_draft", "character").legal(List.of(Option.of("COMMIT", "Lock it in",
									"validate_character_draft, then commit_character_draft " + ref + "."),
							Option.of("REVISE", "Change something",
									"Apply the change with update_character_draft, then read it back again."))));
		}
		for (Decision d : out) {
			d.detail("character", ref);
		}
		return out;
	}

	public Decision skillDecision(Tx tx, Row c, RulesData.Definition cls) {
		int count = CharacterService.skillChoiceCount(cls);
		var held = new java.util.HashSet<String>();
		for (Row t : tx.query("SELECT * FROM character_trait WHERE character_id = ? AND kind = 'SKILL'", c.id())) {
			if (!Origins.SOURCE_CLASS.equals(Origins.sourceOf(t))) {
				held.add(t.str("content_ref"));
			}
		}
		List<String> ids = skillOptionIds(cls).stream().filter(id -> !held.contains(id)).toList();
		Decision d = Decision.of("skills", "Which " + count + " skills is the character proficient in?")
				.recordedBy("update_character_draft", "changes.skills").legal(skills(ids)).choose(count, count)
				.detail("class", cls.id());
		if (!held.isEmpty()) {
			d.note("Skills already granted by the background or species are excluded (duplicate proficiencies are re-chosen, SRD 5.2.1).");
		}
		return d;
	}

	// ── origins: backgrounds, species grants, feats ────────────────────

	public List<Option> backgrounds() {
		return rules.ofKind("BACKGROUND").stream()
				.map(d -> option(d).details(pick(d.payload(), "ability_scores", "skills", "feat"))).toList();
	}

	/** Feats, optionally restricted to a category (ORIGIN, GENERAL, FIGHTING_STYLE, EPIC_BOON). */
	public List<Option> feats(String category) {
		var out = new ArrayList<Option>();
		for (RulesData.Definition d : rules.ofKind("FEAT")) {
			if (category == null || category.equals(String.valueOf(d.payload().get("category")))) {
				out.add(option(d).details(pick(d.payload(), "category", "prerequisite")));
			}
		}
		return out;
	}

	@SuppressWarnings("unchecked")
	public Decision backgroundAsiDecision(RulesData.Definition bg) {
		List<Object> abilities = (List<Object>) bg.payload().get("ability_scores");
		return Decision.of("background_ability_scores",
						"Apply " + bg.name() + "'s ability increase: +2 to one and +1 to another, or +1 to each of " + abilities + ".")
				.recordedBy("update_character_draft", "changes.background_ability_scores").custom()
				.detail("abilities", abilities)
				.note("E.g. {\"" + abilities.get(0) + "\": 2, \"" + abilities.get(1) + "\": 1} or {\"" + abilities.get(
						0) + "\": 1, \"" + abilities.get(1) + "\": 1, \"" + abilities.get(
						2) + "\": 1}. No score above 20 (SRD 5.2.1 \"Ability Scores\" under Backgrounds).");
	}

	public Decision speciesSkillDecision(Tx tx, Row c, RulesData.Definition species) {
		Map<String, Object> trait = Origins.traitWith(species, "skill_grant").orElseThrow();
		Map<String, Object> grant = Origins.castMap(trait.get("skill_grant"));
		var held = Origins.heldSkills(tx, c.id());
		List<String> ids = Origins.skillGrantOptions(rules, grant).stream().filter(id -> !held.contains(id)).toList();
		return Decision.of("species_skill", species.name() + " — " + trait.get("name") + ": which bonus skill?")
				.recordedBy("update_character_draft", "changes.species_skill").legal(skills(ids)).choose(1, 1)
				.note("Skills already held are excluded (duplicate proficiencies are re-chosen, SRD 5.2.1).");
	}

	@SuppressWarnings("unchecked")
	public Decision speciesChoiceDecision(RulesData.Definition species) {
		Map<String, Object> trait = Origins.traitWith(species, "choice").orElseThrow();
		Map<String, Object> choice = Origins.castMap(trait.get("choice"));
		var options = new ArrayList<Option>();
		for (Map<String, Object> o : (List<Map<String, Object>>) choice.get("options")) {
			options.add(Option.of(String.valueOf(o.get("value")), String.valueOf(o.get("label")),
					String.valueOf(o.get("description"))));
		}
		Decision d = Decision.of("species_choice", String.valueOf(choice.get("question")))
				.recordedBy("update_character_draft", "changes.species_choice").legal(options)
				.detail("trait", trait.get("name"));
		if (Boolean.TRUE.equals(choice.get("spellcasting_ability"))) {
			d.note("These spells use INT, WIS or CHA (defaults to the class's spellcasting ability): answer a value, or {\"choice\": ..., \"ability\": \"CHA\"}.");
		}
		return d;
	}

	public Decision originFeatDecision(RulesData.Definition species) {
		Map<String, Object> grant = Origins.traitWith(species, "feat_grant")
				.map(t -> Origins.castMap(t.get("feat_grant"))).orElseThrow();
		String category = String.valueOf(grant.getOrDefault("category", "ORIGIN"));
		String recommended = grant.get("recommended") == null ? null : String.valueOf(grant.get("recommended"));
		var options = new ArrayList<Option>();
		for (Option o : feats(category)) {
			options.add(o.recommended(o.value().equals(recommended)));
		}
		return Decision.of("origin_feat", "Which " + ("ORIGIN".equals(category) ? "Origin feat"
						: category + " feat") + " does the character gain from their species?")
				.recordedBy("update_character_draft", "changes.origin_feat").legal(options)
				.note("Answer a feat, or a feat with its choices in one call, e.g. {\"feat\": \"Skilled\", \"proficiencies\": [three skills or tools]}.");
	}

	@SuppressWarnings("unchecked")
	public Decision featChoicesDecision(Tx tx, Row c, Row featRow) {
		RulesData.Definition feat = Origins.featDefinition(rules, featRow).orElseThrow();
		Map<String, Object> payload = featRow.map("payload_json");
		List<Object> pending = payload.get("pending") instanceof List<?> l ? (List<Object>) l : List.of();
		Map<String, Object> chosen = Origins.castMap(payload.get("choices"));
		Map<String, Object> spec = Origins.castMap(feat.payload().get("choices"));
		Decision d = Decision.of("feat_choices",
						feat.name() + " still needs: " + pending + ". " + feat.payload().getOrDefault("summary", ""))
				.recordedBy("update_character_draft", "changes.feat_choices").detail("feat", feat.id())
				.detail("pending", pending)
				.note("Answer with the feat plus one or more choices, e.g. {\"feat\": \"" + feat.name() + "\", \"" + pending.get(
						0) + "\": ...}.");
		switch (String.valueOf(pending.get(0))) {
		case "spell_list" -> {
			var options = new ArrayList<Option>();
			for (Object o : (List<Object>) spec.get("spell_list")) {
				options.add(Option.of(o.toString(), o.toString(),
						"Choose the cantrips and the level 1 spell from the " + o + " spell list."));
			}
			d.legal(options).choose(1, 1);
		}
		case "ability" -> {
			var options = new ArrayList<Option>();
			for (Object o : (List<Object>) spec.get("ability")) {
				options.add(Option.of(o.toString(), o.toString(),
						"Use " + o + " as the spellcasting ability for this feat's spells."));
			}
			d.legal(options).choose(1, 1);
		}
		case "cantrips" -> {
			int n = ((Number) spec.get("cantrips")).intValue();
			d.legal(spells(String.valueOf(chosen.get("spell_list")), 0)).choose(n, n);
		}
		case "spells" -> d.legal(spells(String.valueOf(chosen.get("spell_list")), 1)).choose(1, 1)
				.note("Answer {\"feat\": \"" + feat.name() + "\", \"spell\": ...}: the spell is always prepared, with one free cast per Long Rest.");
		case "proficiencies" -> {
			int n = ((Number) spec.get("proficiencies")).intValue();
			d.legal(skillAndToolOptions(tx, c)).choose(n, n);
		}
		default -> d.custom();
		}
		return d;
	}

	/** Skills not yet held plus every tool, for the Skilled feat. */
	List<Option> skillAndToolOptions(Tx tx, Row c) {
		var held = Origins.heldSkills(tx, c.id());
		var out = new ArrayList<Option>();
		for (RulesData.Definition d : rules.ofKind("SKILL")) {
			if (!held.contains(d.id())) {
				out.add(option(d).details(pick(d.payload(), "ability")));
			}
		}
		for (RulesData.Definition d : rules.ofKind("ITEM")) {
			if ("TOOL".equals(String.valueOf(d.payload().get("type"))) && !Origins.hasTrait(tx, c.id(), "PROFICIENCY",
					d.id())) {
				out.add(Option.of(d.id(), d.name(), "Proficiency with " + d.name() + "."));
			}
		}
		return out;
	}

	public Decision backgroundToolDecision(RulesData.Definition bg) {
		Map<String, Object> tool = Origins.castMap(bg.payload().get("tool"));
		String kind = String.valueOf(tool.get("choice"));
		var options = new ArrayList<Option>();
		for (RulesData.Definition d : Origins.toolOptions(rules, kind)) {
			options.add(Option.of(d.id(), d.name(), "Proficiency with " + d.name() + "."));
		}
		return Decision.of("background_tool",
						bg.name() + " grants proficiency with one " + kind.toLowerCase().replace('_', ' ') + "; which one?")
				.recordedBy("update_character_draft", "changes.background_tool").legal(options);
	}

	@SuppressWarnings("unchecked")
	public Decision backgroundEquipmentDecision(RulesData.Definition bg) {
		Map<String, Object> options = (Map<String, Object>) bg.payload().get("starting_equipment");
		return Decision.of("background_equipment", "Which background starting equipment option (" + bg.name() + ")?")
				.recordedBy("update_character_draft", "changes.background_equipment.option").legal(equipment(bg))
				.optional(options == null ? null : InventoryService.goldOnlyOption(options))
				.note("Granted in addition to the class equipment (SRD 5.2.1 \"Choose Starting Equipment\").");
	}

	public List<Decision> spellDecisions(SpellService.Casting casting) {
		var out = new ArrayList<Decision>();
		int cantrips = casting.cantripsKnown();
		if (cantrips > 0) {
			out.add(Decision.of("cantrips", "Which " + cantrips + " cantrips does the character know?")
					.recordedBy("update_character_draft", "changes.cantrips").legal(spells(casting.classSlug(), 0))
					.choose(cantrips, cantrips));
		}
		int prepared = casting.preparedCount();
		if (prepared > 0) {
			var options = new ArrayList<Option>();
			for (int level = 1; level <= casting.maxSpellLevel(); level++) {
				options.addAll(spells(casting.classSlug(), level));
			}
			out.add(Decision.of("spells", "Which " + prepared + " spells does the character prepare?")
					.recordedBy("update_character_draft", "changes.spells").legal(options).choose(prepared, prepared)
					.detail("max_spell_level", casting.maxSpellLevel()));
		}
		return out;
	}

	@SuppressWarnings("unchecked")
	public Decision equipmentDecision(RulesData.Definition cls) {
		Map<String, Object> options = (Map<String, Object>) cls.payload().get("starting_equipment");
		return Decision.of("starting_equipment", "Which starting equipment option?")
				.recordedBy("update_character_draft", "changes.starting_equipment.option").legal(equipment(cls))
				.optional(options == null ? null : InventoryService.goldOnlyOption(options))
				.note("Starting wealth is fixed by the rules: an equipment bundle plus a little gold, or the flat amount. Never top it up.");
	}

	public Decision abilityScores(Row c, RulesData.Definition cls, String method) {
		AbilityGeneration gen = AbilityGeneration.valueOf(method);
		Decision d = Decision.of("ability_scores", "How are the six ability scores placed? (" + gen.label() + ")")
				.recordedBy("update_character_draft", "changes.ability_scores").custom().detail("method", gen.name())
				.detail("primary_abilities", cls == null ? null : cls.payload().get("primary_abilities"));
		switch (gen) {
		case STANDARD_ARRAY -> d.detail("standard_array", Rules.STANDARD_ARRAY)
				.note("Assign each value of the array to exactly one ability; propose a spread that favours the class's primary abilities.");
		case POINT_BUY -> {
			var cost = new LinkedHashMap<String, Integer>();
			for (int s = Rules.POINT_BUY_MIN; s <= Rules.POINT_BUY_MAX; s++) {
				cost.put(String.valueOf(s), Rules.pointCost(s));
			}
			d.detail("point_buy",
							Map.of("budget", Rules.POINT_BUY_BUDGET, "min", Rules.POINT_BUY_MIN, "max", Rules.POINT_BUY_MAX,
									"cost", cost))
					.note("Do the arithmetic for the player: offer two or three complete spreads within the budget, plus placing them themselves.");
		}
		case ROLL_4D6_DROP_LOWEST -> {
			Object rolled = c.map("creation_json").get("available_scores");
			if (rolled == null) {
				d.recordedBy("generate_ability_scores", "character")
						.note("Roll first with generate_ability_scores (the server reports every die), then assign the six results.");
			} else {
				d.detail("available_scores", rolled).note("Assign each rolled value to exactly one ability.");
			}
		}
		}
		return d;
	}

	// ── helpers ────────────────────────────────────────────────────────

	Optional<RulesData.Definition> classOf(Tx tx, Row c) {
		return tx.queryOne("SELECT class_ref FROM character_class WHERE character_id = ? ORDER BY id LIMIT 1", c.id())
				.flatMap(r -> rules.find(r.str("class_ref")));
	}

	@SuppressWarnings("unchecked")
	List<String> skillOptionIds(RulesData.Definition cls) {
		Map<String, Object> choices = (Map<String, Object>) cls.payload().get("skill_choices");
		Object options = choices.get("options");
		if ("ANY".equals(options)) {
			return rules.ofKind("SKILL").stream().map(RulesData.Definition::id).toList();
		}
		return ((List<Object>) options).stream().map(Object::toString).toList();
	}

	private static Option option(RulesData.Definition d) {
		return Option.of(d.id(), d.name(), String.valueOf(d.payload().getOrDefault("summary", "")));
	}

	private static Map<String, Object> pick(Map<String, Object> payload, String... keys) {
		var m = new LinkedHashMap<String, Object>();
		for (String k : keys) {
			if (payload.get(k) != null) {
				m.put(k, payload.get(k));
			}
		}
		return m;
	}

	private static String clip(String text) {
		if (text.length() <= DESCRIPTION_LIMIT) {
			return text;
		}
		int cut = text.lastIndexOf(' ', DESCRIPTION_LIMIT);
		return text.substring(0, cut > 40 ? cut : DESCRIPTION_LIMIT) + "…";
	}
}
