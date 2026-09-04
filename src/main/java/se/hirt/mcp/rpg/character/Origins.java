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

import se.hirt.mcp.rpg.content.RulesData;
import se.hirt.mcp.rpg.persistence.Row;
import se.hirt.mcp.rpg.persistence.Tx;
import se.hirt.mcp.rpg.protocol.Json;
import se.hirt.mcp.rpg.protocol.RpgException;
import se.hirt.mcp.rpg.protocol.Violation;
import se.hirt.mcp.rpg.rules.Ability;
import se.hirt.mcp.rpg.rules.Rules;

import java.util.*;

/**
 * Character origins per SRD 5.2.1 "Character Origins" and "Feats": mechanical backgrounds (three-ability increase,
 * Origin feat, two skills, a tool, equipment), species special traits, and feats — granted as {@code character_trait}
 * rows whose payload records the granting {@code source} ({@code class} when absent, {@code background},
 * {@code species}, {@code feat:<ref>}). Traits the engine cannot enforce are surfaced on the sheet with their
 * {@code enforcement} marker instead of being silently dropped (RULES_ENGINE.md §2.3).
 */
public final class Origins {

	/** Trait sources; a missing source on a SKILL/SPELL trait means the class chose it. */
	public static final String SOURCE_CLASS = "class";
	public static final String SOURCE_BACKGROUND = "background";
	public static final String SOURCE_SPECIES = "species";

	private Origins() {
	}

	// ── lookups ────────────────────────────────────────────────────────

	public static Optional<RulesData.Definition> speciesOf(RulesData rules, Row c) {
		return c.isNull("species_ref") ? Optional.empty() : rules.find(c.str("species_ref"));
	}

	public static Optional<RulesData.Definition> backgroundOf(RulesData rules, Row c) {
		return c.isNull("background_ref") ? Optional.empty() : rules.find(c.str("background_ref"));
	}

	/** The character's background, installed or campaign-defined ({@code content:N}, MCP_PROTOCOL.md §13.7). */
	public static Optional<RulesData.Definition> backgroundOf(Tx tx, RulesData rules, Row c) {
		if (c.isNull("background_ref")) {
			return Optional.empty();
		}
		String ref = c.str("background_ref");
		return ref.startsWith("content:") ? customBackground(tx, c.lng("campaign_id"), ref) : rules.find(ref);
	}

	/** Resolves a background by id, symbolic id or name: installed content first, then the campaign's own. */
	public static Optional<RulesData.Definition> resolveBackground(Tx tx, RulesData rules, long campaignId, String text) {
		Optional<RulesData.Definition> installed = rules.resolve("BACKGROUND", text);
		return installed.isPresent() ? installed : customBackground(tx, campaignId, text);
	}

	/** The campaign's own backgrounds, defined with {@code define_content} kind BACKGROUND. */
	public static List<RulesData.Definition> customBackgrounds(Tx tx, long campaignId) {
		return tx.query("SELECT * FROM custom_content WHERE campaign_id = ? AND kind = 'BACKGROUND' ORDER BY id",
				campaignId).stream().map(Origins::customDefinition).toList();
	}

	static Optional<RulesData.Definition> customBackground(Tx tx, long campaignId, String text) {
		if (text == null || text.isBlank()) {
			return Optional.empty();
		}
		String t = text.trim();
		for (Row row : tx.query("SELECT * FROM custom_content WHERE campaign_id = ? AND kind = 'BACKGROUND' ORDER BY id",
				campaignId)) {
			boolean symbolic = !row.isNull("symbolic_id") && row.str("symbolic_id").equalsIgnoreCase(t);
			if (("content:" + row.id()).equals(t) || row.str("name").equalsIgnoreCase(t) || symbolic) {
				return Optional.of(customDefinition(row));
			}
		}
		return Optional.empty();
	}

	static RulesData.Definition customDefinition(Row row) {
		return new RulesData.Definition("content:" + row.id(), row.str("kind"), row.str("name"), row.map("payload_json"));
	}

	@SuppressWarnings("unchecked")
	public static List<Map<String, Object>> traitsOf(RulesData.Definition species) {
		return species.payload().get("traits") instanceof List<?> l ? (List<Map<String, Object>>) l : List.of();
	}

	/** The first species trait carrying the given payload key (skill_grant, feat_grant, choice, ...). */
	public static Optional<Map<String, Object>> traitWith(RulesData.Definition species, String key) {
		return traitsOf(species).stream().filter(t -> t.get(key) != null).findFirst();
	}

	public static String sourceOf(Row trait) {
		Map<String, Object> p = trait.isNull("payload_json") ? Map.of() : trait.map("payload_json");
		Object s = p.get("source");
		return s == null ? SOURCE_CLASS : s.toString();
	}

	public static List<Row> featRows(Tx tx, Row c) {
		return tx.query("SELECT * FROM character_trait WHERE character_id = ? AND kind = 'FEAT' ORDER BY id", c.id());
	}

	public static Optional<Row> speciesChoiceRow(Tx tx, Row c) {
		return tx.query("SELECT * FROM character_trait WHERE character_id = ? AND kind = 'FEATURE' ORDER BY id", c.id())
				.stream().filter(t -> SOURCE_SPECIES.equals(sourceOf(t))).findFirst();
	}

	/** The chosen option of the species' choice trait (Draconic Ancestry, Elven Lineage, ...), if any. */
	@SuppressWarnings("unchecked")
	public static Optional<Map<String, Object>> chosenSpeciesOption(Tx tx, RulesData rules, Row c) {
		Optional<RulesData.Definition> species = speciesOf(rules, c);
		Optional<Row> row = speciesChoiceRow(tx, c);
		if (species.isEmpty() || row.isEmpty()) {
			return Optional.empty();
		}
		Map<String, Object> choice = (Map<String, Object>) traitWith(species.get(), "choice").map(t -> t.get("choice"))
				.orElse(null);
		if (choice == null) {
			return Optional.empty();
		}
		String value = String.valueOf(row.get().map("payload_json").get("choice"));
		for (Map<String, Object> o : (List<Map<String, Object>>) choice.get("options")) {
			if (value.equals(o.get("value"))) {
				return Optional.of(o);
			}
		}
		return Optional.empty();
	}

	// ── engine aggregates ──────────────────────────────────────────────

	/** Extra maximum hit points per character level (Dwarven Toughness). */
	public static int hpPerLevel(RulesData rules, Row c) {
		return speciesOf(rules, c).map(
				s -> traitsOf(s).stream().mapToInt(t -> t.get("hp_per_level") instanceof Number n ? n.intValue() : 0)
						.sum()).orElse(0);
	}

	/** Carrying-capacity multiplier (Goliath Powerful Build counts as one size larger). */
	public static int carryCapacityMultiplier(RulesData rules, Row c) {
		return speciesOf(rules, c).map(s -> traitsOf(s).stream()
				.mapToInt(t -> t.get("carry_capacity_multiplier") instanceof Number n ? n.intValue() : 1).max()
				.orElse(1)).orElse(1);
	}

	/** Damage resistances from species traits and the chosen lineage/ancestry (lowercase types). */
	public static Set<String> speciesResistances(Tx tx, RulesData rules, Row c) {
		var out = new LinkedHashSet<String>();
		speciesOf(rules, c).ifPresent(s -> traitsOf(s).forEach(t -> addResistances(out, t.get("damage_resistances"))));
		chosenSpeciesOption(tx, rules, c).ifPresent(o -> addResistances(out, o.get("damage_resistances")));
		return out;
	}

	private static void addResistances(Set<String> out, Object list) {
		if (list instanceof List<?> l) {
			l.forEach(o -> out.add(o.toString().toLowerCase(Locale.ROOT)));
		}
	}

	/** True when a feat adds the proficiency bonus to initiative (Alert). */
	public static boolean initiativeProficient(Tx tx, RulesData rules, Row c) {
		return featRows(tx, c).stream().anyMatch(
				f -> featDefinition(rules, f).map(d -> Boolean.TRUE.equals(d.payload().get("initiative_proficiency")))
						.orElse(false));
	}

	/** True when a feat rerolls weapon damage once per turn (Savage Attacker). */
	public static boolean savageAttacker(Tx tx, RulesData rules, Row c) {
		return featRows(tx, c).stream().anyMatch(
				f -> featDefinition(rules, f).map(d -> d.payload().get("damage_reroll") != null).orElse(false));
	}

	public static Optional<RulesData.Definition> featDefinition(RulesData rules, Row featRow) {
		return rules.find(baseFeatRef(featRow.str("content_ref")));
	}

	/** Strips the {@code #2} repeat suffix from a stored feat content ref. */
	public static String baseFeatRef(String contentRef) {
		int hash = contentRef.indexOf('#');
		return hash < 0 ? contentRef : contentRef.substring(0, hash);
	}

	/**
	 * Relentless Endurance (Orc): when a drop to 0 HP is not outright death, spend the tracked use and stay at 1 HP.
	 * Returns true when the trait applied.
	 */
	public static boolean spendRelentlessEndurance(Tx tx, RulesData rules, Row c) {
		boolean has = speciesOf(rules, c).map(
						s -> traitsOf(s).stream().anyMatch(t -> Boolean.TRUE.equals(t.get("relentless_endurance"))))
				.orElse(false);
		if (!has) {
			return false;
		}
		Optional<Row> res = tx.queryOne("SELECT * FROM resource_state WHERE character_id = ? AND resource_ref = ?",
				c.id(), "relentless_endurance");
		if (res.isEmpty() || res.get().intOr("current", 0) <= 0) {
			return false;
		}
		tx.update("resource_state", res.get().id(), Map.of("current", res.get().intOr("current", 0) - 1));
		return true;
	}

	// ── trait-row helpers ──────────────────────────────────────────────

	public static void insertTrait(
			Tx tx, long characterId, String kind, String contentRef,
			Map<String, Object> payload) {
		var cols = new LinkedHashMap<String, Object>();
		cols.put("character_id", characterId);
		cols.put("kind", kind);
		cols.put("content_ref", contentRef);
		cols.put("payload_json", payload == null || payload.isEmpty() ? null : Json.write(payload));
		tx.insert("character_trait", cols);
	}

	static void deleteBySource(Tx tx, long characterId, Set<String> kinds, String source) {
		for (Row t : tx.query("SELECT * FROM character_trait WHERE character_id = ? ORDER BY id", characterId)) {
			if (kinds.contains(t.str("kind")) && source.equals(sourceOf(t))) {
				tx.delete("character_trait", t.id());
			}
		}
	}

	static boolean hasTrait(Tx tx, long characterId, String kind, String contentRef) {
		return tx.count("SELECT COUNT(*) FROM character_trait WHERE character_id = ? AND kind = ? AND content_ref = ?",
				characterId, kind, contentRef) > 0;
	}

	/** Every skill the character is already proficient in, whatever the source. */
	public static Set<String> heldSkills(Tx tx, long characterId) {
		var out = new LinkedHashSet<String>();
		for (Row t : tx.query("SELECT content_ref FROM character_trait WHERE character_id = ? AND kind = 'SKILL'",
				characterId)) {
			out.add(t.str("content_ref"));
		}
		return out;
	}

	// ── species application ────────────────────────────────────────────

	/** Removes everything a previously chosen species granted (skills, cantrips, features, feats, senses). */
	public static void clearSpeciesGrants(Tx tx, Row c) {
		for (Row t : tx.query("SELECT * FROM character_trait WHERE character_id = ? ORDER BY id", c.id())) {
			String source = sourceOf(t);
			if (SOURCE_SPECIES.equals(source)) {
				if ("FEAT".equals(t.str("kind"))) {
					clearFeatGrants(tx, c, t.str("content_ref"));
				}
				tx.delete("character_trait", t.id());
			}
		}
	}

	/** Applies a freshly set species: base cantrips, senses and speed (the choice traits become decisions). */
	public static void onSpeciesSet(
			Tx tx, RulesData rules, RulesData.Definition species, Row c, Map<String, Object> cols) {
		clearSpeciesGrants(tx, c);
		for (Map<String, Object> trait : traitsOf(species)) {
			grantCantrips(tx, rules, c, trait.get("cantrips"), null);
		}
		applySpeciesDerived(species, Optional.empty(), cols);
	}

	/** Speed and senses from the species and (optionally) the chosen lineage/ancestry option. */
	public static void applySpeciesDerived(
			RulesData.Definition species, Optional<Map<String, Object>> option, Map<String, Object> cols) {
		int speed = species.payload().get("speed") instanceof Number n ? n.intValue() : 30;
		if (option.isPresent() && option.get().get("speed") instanceof Number n) {
			speed = n.intValue();
		}
		cols.put("speed", speed);
		int darkvision = 0;
		for (Map<String, Object> t : traitsOf(species)) {
			if (t.get("darkvision_ft") instanceof Number n) {
				darkvision = Math.max(darkvision, n.intValue());
			}
		}
		if (option.isPresent() && option.get().get("darkvision_ft") instanceof Number n) {
			darkvision = Math.max(darkvision, n.intValue());
		}
		cols.put("senses_json", darkvision > 0 ? Json.write(List.of("Darkvision " + darkvision + " ft.")) : null);
	}

	/** Records the species bonus skill (Skillful, Keen Senses). */
	public static void applySpeciesSkill(Tx tx, RulesData rules, RulesData.Definition species, Row c, Object value) {
		Map<String, Object> grant = traitWith(species, "skill_grant").map(t -> castMap(t.get("skill_grant")))
				.orElseThrow(() -> RpgException.invalidArgument(species.name() + " grants no bonus skill."));
		RulesData.Definition skill = rules.resolve("SKILL",
						String.valueOf(value instanceof List<?> l && !l.isEmpty() ? l.get(0) : value))
				.orElseThrow(() -> RpgException.invalidArgument("Unknown skill '" + value + "'."));
		List<String> options = skillGrantOptions(rules, grant);
		if (!options.contains(skill.id())) {
			throw RpgException.validation(List.of(new Violation("species_skill", "NOT_AN_OPTION",
					skill.name() + " is not among the species options: " + options)));
		}
		deleteBySource(tx, c.id(), Set.of("SKILL"), SOURCE_SPECIES);
		if (heldSkills(tx, c.id()).contains(skill.id())) {
			throw RpgException.validation(List.of(new Violation("species_skill", "ALREADY_PROFICIENT",
					c.str("name") + " is already proficient in " + skill.name() + "; choose a different skill (SRD 5.2.1: duplicate proficiencies are re-chosen).")));
		}
		insertTrait(tx, c.id(), "SKILL", skill.id(), Map.of("source", SOURCE_SPECIES));
	}

	public static List<String> skillGrantOptions(RulesData rules, Map<String, Object> grant) {
		Object options = grant.get("options");
		if (options instanceof List<?> l) {
			return l.stream().map(Object::toString).toList();
		}
		return rules.ofKind("SKILL").stream().map(RulesData.Definition::id).toList();
	}

	/** Records the species lineage/ancestry choice with its mechanical consequences. */
	@SuppressWarnings("unchecked")
	public static void applySpeciesChoice(
			Tx tx, RulesData rules, RulesData.Definition species, Row c, Object value, Map<String, Object> cols) {
		Map<String, Object> trait = traitWith(species, "choice").orElseThrow(
				() -> RpgException.invalidArgument(species.name() + " has no lineage or ancestry choice."));
		Map<String, Object> choice = castMap(trait.get("choice"));
		String chosen;
		String ability = null;
		if (value instanceof Map<?, ?> m) {
			chosen = String.valueOf(m.get("choice") == null ? m.get("value") : m.get("choice"));
			ability = m.get("ability") == null ? null : String.valueOf(m.get("ability")).toUpperCase(Locale.ROOT);
		} else {
			chosen = String.valueOf(value);
		}
		String slug = chosen.trim().toLowerCase(Locale.ROOT).replace(' ', '-').replace("'", "");
		Map<String, Object> option = null;
		for (Map<String, Object> o : (List<Map<String, Object>>) choice.get("options")) {
			if (slug.equals(o.get("value")) || String.valueOf(o.get("label")).equalsIgnoreCase(chosen)) {
				option = o;
				break;
			}
		}
		if (option == null) {
			throw RpgException.validation(List.of(new Violation("species_choice", "NOT_AN_OPTION",
					"'" + chosen + "' is not an option of " + trait.get("name") + ".")));
		}
		if (Boolean.TRUE.equals(choice.get("spellcasting_ability"))) {
			if (ability == null) {
				ability = defaultSpellAbility(tx, rules, c);
			}
			if (!Set.of("INT", "WIS", "CHA").contains(ability)) {
				throw RpgException.invalidArgument("The lineage spellcasting ability must be INT, WIS or CHA.");
			}
		}
		// Re-choosing replaces the previous option and everything it granted.
		speciesChoiceRow(tx, c).ifPresent(r -> tx.delete("character_trait", r.id()));
		deleteBySource(tx, c.id(), Set.of("SPELL_KNOWN", "SPELL_PREPARED"), SOURCE_SPECIES);
		var payload = new LinkedHashMap<String, Object>();
		payload.put("source", SOURCE_SPECIES);
		payload.put("trait", trait.get("name"));
		payload.put("choice", option.get("value"));
		payload.put("label", option.get("label"));
		payload.put("description", option.get("description"));
		if (ability != null) {
			payload.put("ability", ability);
		}
		insertTrait(tx, c.id(), "FEATURE", species.id() + "#" + choice.get("id"), payload);
		// Base species cantrips plus the option's, all cast with the chosen ability.
		for (Map<String, Object> t : traitsOf(species)) {
			grantCantrips(tx, rules, c, t.get("cantrips"), ability);
		}
		grantCantrips(tx, rules, c, option.get("cantrips"), ability);
		applySpeciesDerived(species, Optional.of(option), cols);
	}

	private static String defaultSpellAbility(Tx tx, RulesData rules, Row c) {
		return se.hirt.mcp.rpg.magic.SpellService.castingOf(tx, rules, c).map(cast -> cast.ability().name())
				.orElse("CHA");
	}

	private static void grantCantrips(Tx tx, RulesData rules, Row c, Object cantrips, String ability) {
		if (!(cantrips instanceof List<?> list)) {
			return;
		}
		for (Object o : list) {
			RulesData.Definition spell = rules.require(o.toString(), "SPELL");
			if (hasTrait(tx, c.id(), "SPELL_KNOWN", spell.id())) {
				continue;
			}
			var payload = new LinkedHashMap<String, Object>();
			payload.put("cantrip", true);
			payload.put("source", SOURCE_SPECIES);
			if (ability != null) {
				payload.put("ability", ability);
			}
			insertTrait(tx, c.id(), "SPELL_KNOWN", spell.id(), payload);
		}
	}

	/**
	 * Grants the species spells (Elven Lineage, Fiendish Legacy, Gnomish Lineage) whose level threshold the character
	 * has reached, each always prepared with a free-cast resource. Idempotent; called at activation and at level-up
	 * commit.
	 */
	@SuppressWarnings("unchecked")
	public static List<String> grantSpeciesSpells(Tx tx, RulesData rules, Row c) {
		var granted = new ArrayList<String>();
		Optional<Map<String, Object>> option = chosenSpeciesOption(tx, rules, c);
		if (option.isEmpty() || !(option.get().get("spells_by_level") instanceof Map<?, ?> byLevel)) {
			return granted;
		}
		int level = characterLevel(tx, c);
		String ability = speciesChoiceRow(tx, c).map(r -> (String) r.map("payload_json").get("ability")).orElse("CHA");
		Object freeCasts = option.get().get("free_casts");
		for (var e : ((Map<String, Object>) byLevel).entrySet()) {
			if (Integer.parseInt(e.getKey()) > level) {
				continue;
			}
			RulesData.Definition spell = rules.require(String.valueOf(e.getValue()), "SPELL");
			if (hasTrait(tx, c.id(), "SPELL_PREPARED", spell.id())) {
				continue;
			}
			String resource = "species:" + spell.id().substring(spell.id().lastIndexOf('/') + 1);
			var payload = new LinkedHashMap<String, Object>();
			payload.put("source", SOURCE_SPECIES);
			payload.put("ability", ability);
			payload.put("free_cast_resource", resource);
			insertTrait(tx, c.id(), "SPELL_PREPARED", spell.id(), payload);
			int max = "PROFICIENCY_BONUS".equals(freeCasts) ? RuntimeService.proficiencyBonus(tx, rules, c) : 1;
			upsertResource(tx, c.id(), resource, max, "LONG_REST");
			granted.add(spell.name());
		}
		return granted;
	}

	public static int characterLevel(Tx tx, Row c) {
		return tx.query("SELECT level FROM character_class WHERE character_id = ?", c.id()).stream()
				.mapToInt(r -> r.intOr("level", 1)).sum();
	}

	// ── background application ─────────────────────────────────────────

	/** Removes everything a previously chosen background granted. */
	public static void clearBackgroundGrants(Tx tx, Row c, Map<String, Object> creation) {
		for (Row t : tx.query("SELECT * FROM character_trait WHERE character_id = ? ORDER BY id", c.id())) {
			if (SOURCE_BACKGROUND.equals(sourceOf(t))) {
				if ("FEAT".equals(t.str("kind"))) {
					clearFeatGrants(tx, c, t.str("content_ref"));
				}
				tx.delete("character_trait", t.id());
			}
		}
		creation.remove("background_ability_scores");
		creation.remove("background_equipment");
	}

	@SuppressWarnings("unchecked")
	public static void applyBackground(
			Tx tx, RulesData rules, Row c, Object value, Map<String, Object> cols,
			Map<String, Object> creation) {
		RulesData.Definition bg = resolveBackground(tx, rules, c.lng("campaign_id"), String.valueOf(value)).orElseThrow(
				() -> RpgException.invalidArgument(
						"Unknown background '" + value + "'; see get_character_choices BACKGROUND."));
		clearBackgroundGrants(tx, c, creation);
		cols.put("background_ref", bg.id());
		// The two background skills are fixed; a class/species pick that duplicates one is released for re-choice
		// (SRD 5.2.1 "Choose a Background": duplicate proficiencies are re-chosen from the flexible source).
		for (Object skillRef : (List<Object>) bg.payload().get("skills")) {
			RulesData.Definition skill = rules.require(skillRef.toString(), "SKILL");
			for (Row t : tx.query(
					"SELECT * FROM character_trait WHERE character_id = ? AND kind = 'SKILL' AND content_ref = ?",
					c.id(), skill.id())) {
				tx.delete("character_trait", t.id());
			}
			insertTrait(tx, c.id(), "SKILL", skill.id(), Map.of("source", SOURCE_BACKGROUND));
		}
		Map<String, Object> tool = castMap(bg.payload().get("tool"));
		if (tool.get("item") != null) {
			insertTrait(tx, c.id(), "PROFICIENCY", String.valueOf(tool.get("item")),
					Map.of("source", SOURCE_BACKGROUND, "kind", "TOOL"));
		}
		RulesData.Definition feat = rules.require(String.valueOf(bg.payload().get("feat")), "FEAT");
		Map<String, Object> preset =
				bg.payload().get("feat_choices") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
		grantFeat(tx, rules, c, feat, SOURCE_BACKGROUND, preset);
	}

	/** Validates and records the background ability-score increase (+2/+1 or +1/+1/+1 among its three abilities). */
	@SuppressWarnings("unchecked")
	public static void applyBackgroundAbilityScores(
			RulesData.Definition bg, Object value, Map<String, Object> creation) {
		if (!(value instanceof Map<?, ?> m) || m.isEmpty()) {
			throw RpgException.invalidArgument(
					"background_ability_scores must be like {\"CHA\": 2, \"CON\": 1} or {\"CON\": 1, \"INT\": 1, \"WIS\": 1}.");
		}
		List<String> allowed = ((List<Object>) bg.payload().get("ability_scores")).stream().map(Object::toString)
				.toList();
		var asi = new LinkedHashMap<String, Object>();
		int total = 0;
		for (var e : m.entrySet()) {
			Ability a = Ability.parse(String.valueOf(e.getKey()));
			if (!allowed.contains(a.name())) {
				throw RpgException.validation(
						List.of(new Violation("background_ability_scores." + a.name(), "NOT_A_BACKGROUND_ABILITY",
								bg.name() + " increases " + allowed + " only.")));
			}
			if (!(e.getValue() instanceof Number n) || n.intValue() < 1 || n.intValue() > 2) {
				throw RpgException.validation(List.of(new Violation("background_ability_scores." + a.name(), "RANGE",
						"Increases are +1 or +2.")));
			}
			asi.put(a.name(), n.intValue());
			total += n.intValue();
		}
		boolean twoOne = asi.size() == 2 && total == 3 && asi.values().stream()
				.anyMatch(v -> ((Number) v).intValue() == 2);
		boolean threeOnes = asi.size() == 3 && total == 3 && asi.values().stream()
				.allMatch(v -> ((Number) v).intValue() == 1);
		if (!twoOne && !threeOnes) {
			throw RpgException.validation(List.of(new Violation("background_ability_scores", "PATTERN",
					"Increase one ability by 2 and another by 1, or all three by 1 (SRD 5.2.1 \"Ability Scores\" under Backgrounds).")));
		}
		creation.put("background_ability_scores", asi);
	}

	/** Records the background tool proficiency where the background offers a category choice. */
	public static void applyBackgroundTool(Tx tx, RulesData rules, RulesData.Definition bg, Row c, Object value) {
		Map<String, Object> tool = castMap(bg.payload().get("tool"));
		if (tool.get("choice") == null) {
			throw RpgException.invalidArgument(bg.name() + "'s tool proficiency is fixed (" + tool.get("item") + ").");
		}
		String kind = String.valueOf(tool.get("choice"));
		RulesData.Definition item = rules.resolve("ITEM", String.valueOf(value))
				.orElseThrow(() -> RpgException.invalidArgument("Unknown tool '" + value + "'."));
		String toolKind = String.valueOf(item.payload().get("tool_kind"));
		boolean legal = switch (kind) {
			case "GAMING_SET" -> "GAMING_SET".equals(toolKind);
			case "ARTISANS_TOOLS" -> "ARTISAN".equals(toolKind);
			default -> "TOOL".equals(String.valueOf(item.payload().get("type")));
		};
		if (!legal) {
			throw RpgException.validation(List.of(new Violation("background_tool", "NOT_AN_OPTION",
					item.name() + " is not a legal " + kind.toLowerCase(Locale.ROOT).replace('_', ' ') + " choice.")));
		}
		for (Row t : tx.query("SELECT * FROM character_trait WHERE character_id = ? AND kind = 'PROFICIENCY'",
				c.id())) {
			if (SOURCE_BACKGROUND.equals(sourceOf(t))) {
				tx.delete("character_trait", t.id());
			}
		}
		insertTrait(tx, c.id(), "PROFICIENCY", item.id(), Map.of("source", SOURCE_BACKGROUND, "kind", "TOOL"));
	}

	/** Background tool candidates for a category choice. */
	public static List<RulesData.Definition> toolOptions(RulesData rules, String choiceKind) {
		String wanted = "ARTISANS_TOOLS".equals(choiceKind) ? "ARTISAN" : choiceKind;
		return rules.ofKind("ITEM").stream().filter(d -> wanted.equals(String.valueOf(d.payload().get("tool_kind"))))
				.toList();
	}

	// ── feats ──────────────────────────────────────────────────────────

	/** Removes what a feat granted (its spells and proficiencies) before the feat row itself is deleted. */
	public static void clearFeatGrants(Tx tx, Row c, String featContentRef) {
		String source = "feat:" + featContentRef;
		deleteBySource(tx, c.id(), Set.of("SKILL", "PROFICIENCY", "SPELL_KNOWN", "SPELL_PREPARED"), source);
	}

	/** The species' Origin-feat grant (Human Versatile): applies the chosen feat, replacing an earlier pick. */
	public static void applyOriginFeat(Tx tx, RulesData rules, RulesData.Definition species, Row c, Object value) {
		Map<String, Object> grant = traitWith(species, "feat_grant").map(t -> castMap(t.get("feat_grant")))
				.orElseThrow(() -> RpgException.invalidArgument(species.name() + " grants no origin feat."));
		Map<String, Object> extras = Map.of();
		Object featValue;
		if (value instanceof Map<?, ?> m) {
			featValue = m.get("feat");
			var rest = new LinkedHashMap<String, Object>();
			m.forEach((k, v) -> {
				if (!"feat".equals(k)) {
					rest.put(String.valueOf(k), v);
				}
			});
			extras = rest;
		} else {
			featValue = value;
		}
		final Object featRef = featValue;
		RulesData.Definition feat = rules.resolve("FEAT", String.valueOf(featRef)).orElseThrow(
				() -> RpgException.invalidArgument("Unknown feat '" + featRef + "'; see get_character_choices FEAT."));
		String category = String.valueOf(grant.getOrDefault("category", "ORIGIN"));
		if (!category.equals(String.valueOf(feat.payload().get("category")))) {
			throw RpgException.validation(List.of(new Violation("origin_feat", "WRONG_CATEGORY",
					feat.name() + " is not " + (category.equals("ORIGIN") ? "an Origin feat"
							: "a " + category + " feat") + ".")));
		}
		for (Row f : featRows(tx, c)) {
			if (SOURCE_SPECIES.equals(sourceOf(f))) {
				clearFeatGrants(tx, c, f.str("content_ref"));
				tx.delete("character_trait", f.id());
			}
		}
		Row featRow = grantFeat(tx, rules, c, feat, SOURCE_SPECIES, Map.of());
		if (!extras.isEmpty()) {
			applyFeatChoiceValues(tx, rules, c, featRow, extras);
		}
	}

	/**
	 * Grants a feat as a FEAT trait whose payload records source, resolved choices, and the pending choice keys still
	 * owed. Repeatable feats get a {@code #n} suffix on the stored ref.
	 */
	@SuppressWarnings("unchecked")
	public static Row grantFeat(
			Tx tx, RulesData rules, Row c, RulesData.Definition feat, String source, Map<String, Object> preset) {
		String ref = feat.id();
		if (hasTrait(tx, c.id(), "FEAT", ref)) {
			if (feat.payload().get("repeatable") == null) {
				throw RpgException.validation(List.of(new Violation("feat", "ALREADY_TAKEN",
						c.str("name") + " already has " + feat.name() + ".")));
			}
			int n = 2;
			while (hasTrait(tx, c.id(), "FEAT", feat.id() + "#" + n)) {
				n++;
			}
			ref = feat.id() + "#" + n;
		}
		var payload = new LinkedHashMap<String, Object>();
		payload.put("source", source);
		var choices = new LinkedHashMap<String, Object>();
		var pending = new ArrayList<String>();
		if (feat.payload().get("choices") instanceof Map<?, ?> spec) {
			pending.addAll(pendingOrder(((Map<String, Object>) spec).keySet()));
		}
		payload.put("choices", choices);
		payload.put("pending", pending);
		insertTrait(tx, c.id(), "FEAT", ref, payload);
		Row row = tx.query("SELECT * FROM character_trait WHERE character_id = ? AND kind = 'FEAT' AND content_ref = ?",
				c.id(), ref).get(0);
		if (preset != null && !preset.isEmpty()) {
			row = applyFeatChoiceValues(tx, rules, c, row, preset);
		}
		return row;
	}

	/**
	 * Resolves feat-choice values ({@code feat_choices} draft field / level-up feat choices): one object naming the
	 * feat, or a list of them when several feats owe choices at once (a Human Sage: Skilled and Magic Initiate).
	 */
	public static void applyFeatChoices(Tx tx, RulesData rules, Row c, Object value) {
		if (value instanceof List<?> list) {
			if (list.isEmpty()) {
				throw RpgException.invalidArgument("feat_choices is an empty list; name at least one feat.");
			}
			for (Object one : list) {
				applyFeatChoices(tx, rules, c, one);
			}
			return;
		}
		if (!(value instanceof Map<?, ?> m) || m.get("feat") == null) {
			throw RpgException.invalidArgument(
					"feat_choices must be an object naming the feat, e.g. {\"feat\": \"Skilled\", \"proficiencies\": [...]}, or a list of such objects (one per feat).");
		}
		RulesData.Definition feat = rules.resolve("FEAT", String.valueOf(m.get("feat")))
				.orElseThrow(() -> RpgException.invalidArgument("Unknown feat '" + m.get("feat") + "'."));
		Row featRow = featRows(tx, c).stream().filter(f -> baseFeatRef(f.str("content_ref")).equals(feat.id()))
				.findFirst().orElseThrow(() -> RpgException.invalidArgument(
						c.str("name") + " does not have the feat " + feat.name() + "."));
		var values = new LinkedHashMap<String, Object>();
		m.forEach((k, v) -> {
			if (!"feat".equals(k)) {
				values.put(String.valueOf(k), v);
			}
		});
		applyFeatChoiceValues(tx, rules, c, featRow, values);
	}

	@SuppressWarnings("unchecked")
	static Row applyFeatChoiceValues(Tx tx, RulesData rules, Row c, Row featRow, Map<String, Object> values) {
		RulesData.Definition feat = featDefinition(rules, featRow).orElseThrow();
		Map<String, Object> spec =
				feat.payload().get("choices") instanceof Map<?, ?> s ? (Map<String, Object>) s : Map.of();
		Map<String, Object> payload = featRow.map("payload_json");
		Map<String, Object> choices =
				payload.get("choices") instanceof Map<?, ?> ch ? new LinkedHashMap<>((Map<String, Object>) ch)
						: new LinkedHashMap<>();
		String source = "feat:" + featRow.str("content_ref");
		for (var e : values.entrySet()) {
			String key = e.getKey();
			Object v = e.getValue();
			if (!spec.containsKey(key) && !("spell".equals(key) && spec.containsKey("spells"))) {
				throw RpgException.invalidArgument(
						feat.name() + " has no choice '" + key + "'; expected " + spec.keySet() + ".");
			}
			switch (key) {
			case "spell_list" -> {
				String list = String.valueOf(v).toLowerCase(Locale.ROOT);
				List<String> lists = ((List<Object>) spec.get("spell_list")).stream().map(Object::toString).toList();
				if (!lists.contains(list)) {
					throw RpgException.validation(List.of(new Violation("feat_choices.spell_list", "NOT_AN_OPTION",
							"Choose one of " + lists + ".")));
				}
				choices.put("spell_list", list);
			}
			case "ability" -> {
				String ability = String.valueOf(v).toUpperCase(Locale.ROOT);
				List<String> abilities = ((List<Object>) spec.get("ability")).stream().map(Object::toString).toList();
				if (!abilities.contains(ability)) {
					throw RpgException.validation(List.of(new Violation("feat_choices.ability", "NOT_AN_OPTION",
							"Choose one of " + abilities + ".")));
				}
				choices.put("ability", ability);
			}
			case "cantrips", "spells", "spell" -> {
				// handled below once list and ability are known
			}
			case "proficiencies" -> {
				// handled below
			}
			default -> choices.put(key, v);
			}
		}
		// Skilled: any combination of three skills or tools.
		if (values.get("proficiencies") != null) {
			if (!(values.get("proficiencies") instanceof List<?> list) || list.size() != ((Number) spec.get(
					"proficiencies")).intValue()) {
				throw RpgException.validation(List.of(new Violation("feat_choices.proficiencies", "COUNT",
						feat.name() + " grants exactly " + spec.get(
								"proficiencies") + " skill or tool proficiencies.")));
			}
			deleteBySource(tx, c.id(), Set.of("SKILL", "PROFICIENCY"), source);
			var names = new ArrayList<String>();
			for (Object o : list) {
				Optional<RulesData.Definition> skill = rules.resolve("SKILL", String.valueOf(o));
				if (skill.isPresent()) {
					if (heldSkills(tx, c.id()).contains(skill.get().id())) {
						throw RpgException.validation(
								List.of(new Violation("feat_choices.proficiencies", "ALREADY_PROFICIENT",
										"Already proficient in " + skill.get()
												.name() + "; choose a different skill or tool.")));
					}
					insertTrait(tx, c.id(), "SKILL", skill.get().id(), Map.of("source", source));
					names.add(skill.get().name());
					continue;
				}
				RulesData.Definition item = rules.resolve("ITEM", String.valueOf(o))
						.filter(d -> "TOOL".equals(String.valueOf(d.payload().get("type"))))
						.orElseThrow(() -> RpgException.invalidArgument("'" + o + "' is neither a skill nor a tool."));
				if (hasTrait(tx, c.id(), "PROFICIENCY", item.id())) {
					throw RpgException.validation(
							List.of(new Violation("feat_choices.proficiencies", "ALREADY_PROFICIENT",
									"Already proficient with " + item.name() + "; choose a different skill or tool.")));
				}
				insertTrait(tx, c.id(), "PROFICIENCY", item.id(), Map.of("source", source, "kind", "TOOL"));
				names.add(item.name());
			}
			choices.put("proficiencies", names);
		}
		// Magic Initiate: two cantrips and one level 1 spell from the chosen list, cast with the chosen ability.
		if (values.get("cantrips") != null || values.get("spell") != null || values.get("spells") != null) {
			String list = (String) choices.get("spell_list");
			String ability = (String) choices.get("ability");
			if (list == null || ability == null) {
				throw RpgException.invalidArgument(
						"Choose spell_list and ability before (or together with) the spells of " + feat.name() + ".");
			}
			if (values.get("cantrips") != null) {
				if (!(values.get("cantrips") instanceof List<?> cs) || cs.size() != ((Number) spec.get(
						"cantrips")).intValue()) {
					throw RpgException.validation(List.of(new Violation("feat_choices.cantrips", "COUNT",
							feat.name() + " grants exactly " + spec.get("cantrips") + " cantrips.")));
				}
				deleteBySource(tx, c.id(), Set.of("SPELL_KNOWN"), source);
				var names = new ArrayList<String>();
				for (Object o : cs) {
					RulesData.Definition spell = resolveListSpell(rules, o, list, 0);
					if (hasTrait(tx, c.id(), "SPELL_KNOWN", spell.id())) {
						throw RpgException.validation(List.of(new Violation("feat_choices.cantrips", "ALREADY_KNOWN",
								spell.name() + " is already known; choose a different cantrip.")));
					}
					insertTrait(tx, c.id(), "SPELL_KNOWN", spell.id(),
							Map.of("cantrip", true, "source", source, "ability", ability));
					names.add(spell.name());
				}
				choices.put("cantrips", names);
			}
			Object spellValue = values.get("spell") != null ? values.get("spell") : values.get("spells");
			if (spellValue != null) {
				Object single = spellValue instanceof List<?> l ? (l.size() == 1 ? l.get(0) : null) : spellValue;
				if (single == null) {
					throw RpgException.validation(List.of(new Violation("feat_choices.spell", "COUNT",
							feat.name() + " grants exactly one level 1 spell.")));
				}
				RulesData.Definition spell = resolveListSpell(rules, single, list, 1);
				deleteBySource(tx, c.id(), Set.of("SPELL_PREPARED"), source);
				String resource = "feat:" + slugOf(feat.id()) + ":" + list;
				insertTrait(tx, c.id(), "SPELL_PREPARED", spell.id(),
						Map.of("source", source, "ability", ability, "free_cast_resource", resource));
				choices.put("spell", spell.name());
				if ("ACTIVE".equals(c.str("lifecycle"))) {
					upsertResource(tx, c.id(), resource, 1, "LONG_REST");
				}
			}
		}
		var pending = new ArrayList<String>();
		for (String key : pendingOrder(spec.keySet())) {
			boolean satisfied = switch (key) {
				case "cantrips" -> choices.get("cantrips") != null;
				case "spells" -> choices.get("spell") != null;
				default -> choices.get(key) != null;
			};
			if (!satisfied) {
				pending.add(key);
			}
		}
		payload.put("choices", choices);
		payload.put("pending", pending);
		tx.update("character_trait", featRow.id(), Map.of("payload_json", Json.write(payload)));
		return tx.get("character_trait", featRow.id());
	}

	/**
	 * The answerable choice keys of a feat's choices spec, in ask-order (the spell list and ability come before the
	 * spells they constrain). Other spec keys ("from", counts' metadata) are not choices.
	 */
	public static List<String> pendingOrder(Set<String> keys) {
		var out = new ArrayList<String>();
		for (String k : List.of("spell_list", "ability", "proficiencies", "cantrips", "spells")) {
			if (keys.contains(k)) {
				out.add(k);
			}
		}
		return out;
	}

	@SuppressWarnings("unchecked")
	private static RulesData.Definition resolveListSpell(RulesData rules, Object value, String list, int level) {
		RulesData.Definition spell = rules.resolve("SPELL", String.valueOf(value))
				.orElseThrow(() -> RpgException.invalidArgument("Unknown spell '" + value + "'."));
		int spellLevel = ((Number) spell.payload().get("level")).intValue();
		if (spellLevel != level) {
			throw RpgException.validation(List.of(new Violation("feat_choices", "LEVEL",
					spell.name() + " is level " + spellLevel + "; a level " + level + " spell from the " + list + " list is required.")));
		}
		if (!((List<Object>) spell.payload().get("classes")).contains(list)) {
			throw RpgException.validation(List.of(new Violation("feat_choices", "CLASS_LIST",
					spell.name() + " is not on the " + list + " spell list.")));
		}
		return spell;
	}

	private static String slugOf(String contentId) {
		return contentId.substring(contentId.lastIndexOf('/') + 1);
	}

	// ── validation ─────────────────────────────────────────────────────

	/** Origin-related whole-character violations (backgrounds, species grants, unresolved feat choices). */
	public static List<Violation> validate(Tx tx, RulesData rules, Row c) {
		var v = new ArrayList<Violation>();
		Optional<RulesData.Definition> bg = backgroundOf(tx, rules, c);
		if (bg.isEmpty()) {
			v.add(new Violation("background", "REQUIRED",
					"Choose a background (SRD 5.2.1 \"Character Backgrounds\")."));
		} else {
			if (c.map("creation_json").get("background_ability_scores") == null) {
				v.add(new Violation("background_ability_scores", "REQUIRED",
						"Apply the background ability increase: +2/+1 or +1/+1/+1 among " + bg.get().payload()
								.get("ability_scores") + "."));
			}
			Map<String, Object> tool = castMap(bg.get().payload().get("tool"));
			if (tool.get("choice") != null && tx.query(
							"SELECT * FROM character_trait WHERE character_id = ? AND kind = 'PROFICIENCY'", c.id()).stream()
					.noneMatch(t -> SOURCE_BACKGROUND.equals(sourceOf(t)))) {
				v.add(new Violation("background_tool", "REQUIRED",
						bg.get().name() + " grants a " + tool.get("choice") + " tool proficiency; choose one."));
			}
		}
		speciesOf(rules, c).ifPresent(species -> {
			if (traitWith(species, "skill_grant").isPresent() && tx.query(
							"SELECT * FROM character_trait WHERE character_id = ? AND kind = 'SKILL'", c.id()).stream()
					.noneMatch(t -> SOURCE_SPECIES.equals(sourceOf(t)))) {
				v.add(new Violation("species_skill", "REQUIRED",
						species.name() + " grants a bonus skill; choose one."));
			}
			if (traitWith(species, "choice").isPresent() && speciesChoiceRow(tx, c).isEmpty()) {
				Map<String, Object> choice = castMap(traitWith(species, "choice").get().get("choice"));
				v.add(new Violation("species_choice", "REQUIRED", String.valueOf(choice.get("question"))));
			}
			if (traitWith(species, "feat_grant").isPresent() && featRows(tx, c).stream()
					.noneMatch(f -> SOURCE_SPECIES.equals(sourceOf(f)))) {
				v.add(new Violation("origin_feat", "REQUIRED", species.name() + " grants an Origin feat; choose one."));
			}
		});
		for (Row f : featRows(tx, c)) {
			Map<String, Object> payload = f.map("payload_json");
			if (payload.get("pending") instanceof List<?> pending && !pending.isEmpty()) {
				String name = featDefinition(rules, f).map(RulesData.Definition::name).orElse(f.str("content_ref"));
				v.add(new Violation("feat_choices", "REQUIRED", name + " still needs choices: " + pending + "."));
			}
		}
		for (Ability a : Ability.values()) {
			Integer score = c.integer(a.column());
			if (score != null && score > Rules.MAX_SCORE) {
				v.add(new Violation("ability_scores." + a.name(), "CAP", a.fullName() + " cannot exceed 20."));
			}
		}
		return v;
	}

	// ── activation and progression ─────────────────────────────────────

	/**
	 * Creates or resizes the tracked-use resources of species traits and feats (breath weapon, heroic inspiration,
	 * Magic Initiate free casts, ...). Proficiency-bonus maxima resize on level-up; called at activation and level-up
	 * commit alongside spell slots.
	 */
	public static void initializeResources(Tx tx, RulesData rules, Row c) {
		int level = characterLevel(tx, c);
		int pb = RuntimeService.proficiencyBonus(tx, rules, c);
		speciesOf(rules, c).ifPresent(species -> {
			for (Map<String, Object> trait : traitsOf(species)) {
				Map<String, Object> resource =
						trait.get("resource") instanceof Map<?, ?> ? castMap(trait.get("resource")) : null;
				if (resource == null) {
					continue;
				}
				if (resource.get("from_level") instanceof Number from && level < from.intValue()) {
					continue;
				}
				int max = "PROFICIENCY_BONUS".equals(resource.get("max")) ? pb
						: ((Number) resource.get("max")).intValue();
				upsertResource(tx, c.id(), String.valueOf(resource.get("ref")), max,
						String.valueOf(resource.getOrDefault("recharge", "LONG_REST")));
			}
		});
		grantSpeciesSpells(tx, rules, c);
		for (Row t : tx.query("SELECT * FROM character_trait WHERE character_id = ? AND kind = 'SPELL_PREPARED'",
				c.id())) {
			Map<String, Object> p = t.isNull("payload_json") ? Map.of() : t.map("payload_json");
			if (p.get("free_cast_resource") instanceof String ref && tx.queryOne(
							"SELECT id FROM resource_state WHERE character_id = ? AND resource_ref = ?", c.id(), ref)
					.isEmpty()) {
				upsertResource(tx, c.id(), ref, 1, "LONG_REST");
			}
		}
	}

	static void upsertResource(Tx tx, long characterId, String ref, int max, String recharge) {
		Optional<Row> existing = tx.queryOne("SELECT * FROM resource_state WHERE character_id = ? AND resource_ref = ?",
				characterId, ref);
		if (existing.isPresent()) {
			int delta = max - existing.get().intOr("max", 0);
			int current = Math.max(0, Math.min(max, existing.get().intOr("current", 0) + Math.max(0, delta)));
			tx.update("resource_state", existing.get().id(), Map.of("max", max, "current", current));
		} else {
			var cols = new LinkedHashMap<String, Object>();
			cols.put("character_id", characterId);
			cols.put("resource_ref", ref);
			cols.put("current", max);
			cols.put("max", max);
			cols.put("recharge", recharge);
			tx.insert("resource_state", cols);
		}
	}

	// ── sheet ──────────────────────────────────────────────────────────

	/** Origin blocks for the character sheet: background, species traits, feats, tools, tracked resources. */
	public static void appendSheet(Tx tx, RulesData rules, Row c, Map<String, Object> m, String detail) {
		boolean full = "FULL".equals(detail);
		backgroundOf(tx, rules, c).ifPresent(bg -> {
			var b = new LinkedHashMap<String, Object>();
			b.put("ref", bg.id());
			b.put("name", bg.name());
			rules.find(String.valueOf(bg.payload().get("feat"))).ifPresent(f -> b.put("feat", f.name()));
			m.put("background", b);
		});
		var tools = new ArrayList<String>();
		for (Row t : tx.query(
				"SELECT * FROM character_trait WHERE character_id = ? AND kind = 'PROFICIENCY' ORDER BY id", c.id())) {
			tools.add(rules.find(t.str("content_ref")).map(RulesData.Definition::name).orElse(t.str("content_ref")));
		}
		if (!tools.isEmpty()) {
			m.put("tool_proficiencies", tools);
		}
		Optional<Row> choiceRow = speciesChoiceRow(tx, c);
		speciesOf(rules, c).ifPresent(species -> {
			var traits = new ArrayList<Map<String, Object>>();
			for (Map<String, Object> trait : traitsOf(species)) {
				var t = new LinkedHashMap<String, Object>();
				t.put("name", trait.get("name"));
				t.put("summary", trait.get("summary"));
				String enforcement = String.valueOf(trait.getOrDefault("enforcement", "GM"));
				if (!"ENGINE".equals(enforcement)) {
					t.put("adjudication", "ENGINE".equals(enforcement) ? "ENGINE"
							: ("MIXED".equals(enforcement) ? "GM (uses tracked)" : "GM"));
				}
				if (trait.get("choice") != null && choiceRow.isPresent() && String.valueOf(
						castMap(trait.get("choice")).get("id")).equals(refChoiceId(choiceRow.get()))) {
					Map<String, Object> p = choiceRow.get().map("payload_json");
					t.put("chosen", p.get("label"));
					if (p.get("ability") != null) {
						t.put("spellcasting_ability", p.get("ability"));
					}
				}
				if (trait.get("resource") instanceof Map<?, ?> res) {
					String ref = String.valueOf(castMap(trait.get("resource")).get("ref"));
					tx.queryOne("SELECT * FROM resource_state WHERE character_id = ? AND resource_ref = ?", c.id(), ref)
							.ifPresent(r -> t.put("uses",
									Map.of("current", r.intOr("current", 0), "max", r.intOr("max", 0))));
				}
				if (full) {
					t.put("text", trait.get("text"));
					if (trait.get("gm_note") != null) {
						t.put("gm_note", trait.get("gm_note"));
					}
				}
				traits.add(t);
			}
			if (!traits.isEmpty()) {
				m.put("species_traits", traits);
			}
		});
		var feats = new ArrayList<Map<String, Object>>();
		for (Row f : featRows(tx, c)) {
			var e = new LinkedHashMap<String, Object>();
			Optional<RulesData.Definition> def = featDefinition(rules, f);
			e.put("name", def.map(RulesData.Definition::name).orElse(f.str("content_ref")));
			Map<String, Object> payload = f.map("payload_json");
			e.put("source", payload.getOrDefault("source", SOURCE_CLASS));
			if (payload.get("choices") instanceof Map<?, ?> ch && !ch.isEmpty()) {
				e.put("choices", ch);
			}
			if (payload.get("pending") instanceof List<?> pending && !pending.isEmpty()) {
				e.put("pending", pending);
			}
			def.ifPresent(d -> {
				e.put("summary", d.payload().get("summary"));
				String enforcement = String.valueOf(d.payload().getOrDefault("enforcement", "GM"));
				if (!"ENGINE".equals(enforcement)) {
					e.put("adjudication", "MIXED".equals(enforcement) ? "GM (state tracked)" : "GM");
				}
			});
			feats.add(e);
		}
		if (!feats.isEmpty()) {
			m.put("feats", feats);
		}
		if (!c.isNull("senses_json")) {
			m.put("senses", c.list("senses_json"));
		}
		if ("ACTIVE".equals(c.str("lifecycle"))) {
			var resources = new ArrayList<Map<String, Object>>();
			for (Row r : tx.query("SELECT * FROM resource_state WHERE character_id = ? ORDER BY resource_ref",
					c.id())) {
				String ref = r.str("resource_ref");
				if (ref.startsWith("slot:") || ref.equals("pact_slot") || ref.equals("hit_dice")) {
					continue;
				}
				var e = new LinkedHashMap<String, Object>();
				e.put("ref", ref);
				e.put("current", r.intOr("current", 0));
				e.put("max", r.intOr("max", 0));
				e.put("recharge", r.str("recharge"));
				resources.add(e);
			}
			if (!resources.isEmpty()) {
				m.put("resources", resources);
			}
		}
	}

	private static String refChoiceId(Row choiceRow) {
		String ref = choiceRow.str("content_ref");
		int hash = ref.indexOf('#');
		return hash < 0 ? ref : ref.substring(hash + 1);
	}

	@SuppressWarnings("unchecked")
	static Map<String, Object> castMap(Object o) {
		return o instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
	}
}
