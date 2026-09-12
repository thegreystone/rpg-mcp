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
package se.hirt.mcp.rpg.magic;

import se.hirt.mcp.rpg.character.CharacterService;
import se.hirt.mcp.rpg.character.Origins;
import se.hirt.mcp.rpg.content.RulesData;
import se.hirt.mcp.rpg.dice.DiceExpression;
import se.hirt.mcp.rpg.dice.Roll;
import se.hirt.mcp.rpg.dice.RollService;
import se.hirt.mcp.rpg.persistence.Row;
import se.hirt.mcp.rpg.persistence.Tx;
import se.hirt.mcp.rpg.progression.ClassFeatures;
import se.hirt.mcp.rpg.protocol.RpgException;
import se.hirt.mcp.rpg.protocol.Violation;
import se.hirt.mcp.rpg.rules.Rules;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The Sorcerer's Font of Magic and Metamagic (SRD 5.2.1 "Sorcerer"), driven by the class definition's feature blocks:
 * <ul>
 * <li>{@code FONT_OF_MAGIC}: sorcery points as a tracked resource (one per sorcerer level, back on a Long Rest) and the
 * two Bonus-Action conversions, points into a spell slot ({@code slot_costs}) and a slot into points.</li>
 * <li>{@code METAMAGIC}: the options the class knows ({@code options}, each with its point cost), how many a sorcerer
 * has at a given level ({@code options_known}), and the rule that only one option shapes a spell unless the option says
 * otherwise (Empowered, Seeking).</li>
 * <li>{@code SORCEROUS_RESTORATION}: points regained on a Short Rest, once per Long Rest.</li>
 * </ul>
 * Known options are {@code METAMAGIC} traits ({@code metamagic:<id>}), chosen at level-up when the count rises or set by
 * the audited {@code SET_METAMAGIC} override for a character that levelled before the feature existed.
 * <p>
 * At casting, {@code options.metamagic} names the options; the plan validates them against the spell, spends the
 * points and is consulted by the casting core: Empowered rerolls the lowest damage dice once per casting, Seeking
 * rerolls a missed spell attack (one point per reroll, spent when used), Heightened gives one target Disadvantage on
 * its save, Careful lets named creatures succeed automatically and take no damage, Transmuted swaps the element,
 * Extended doubles a duration of a minute or more (to at most 24 hours); Distant, Quickened, Subtle and Twinned are
 * bookkeeping the fiction honours (range, casting time, components, an extra target), reported in the result.
 */
public final class Metamagic {

	public static final String POINTS = "sorcery_points";
	public static final String RESTORATION = "sorcerous_restoration";
	/** Known options are FEATURE traits whose content ref is {@code metamagic:<id>} (the trait kinds are a DB CHECK). */
	public static final String TRAIT_KIND = "FEATURE";
	public static final String TRAIT_PREFIX = "metamagic:";
	public static final String KIND_FONT = "FONT_OF_MAGIC";
	public static final String KIND_METAMAGIC = "METAMAGIC";
	public static final String KIND_RESTORATION = "SORCEROUS_RESTORATION";

	/** Options that may be added to a spell that already has another Metamagic option on it. */
	static final Set<String> COMBINABLE = Set.of("empowered", "seeking");
	static final Set<String> ELEMENTS = Set.of("acid", "cold", "fire", "lightning", "poison", "thunder");
	/** Point costs when the seed does not say (SRD 5.2.1). */
	private static final Map<String, Integer> DEFAULT_COST =
			Map.of("careful", 1, "distant", 1, "empowered", 1, "extended", 1, "heightened", 2, "quickened", 2,
					"seeking", 1, "subtle", 1, "transmuted", 1, "twinned", 1);

	private Metamagic() {
	}

	/** One Metamagic option as the class definition describes it. */
	public record Option(String id, String name, int cost, String summary) {

		public Map<String, Object> toMap() {
			var m = new LinkedHashMap<String, Object>();
			m.put("id", id);
			m.put("name", name);
			m.put("cost", cost);
			if (summary != null) {
				m.put("summary", summary);
			}
			return m;
		}
	}

	// ── the feature and its options ────────────────────────────────────

	public static Optional<ClassFeatures.Mechanic> feature(Tx tx, RulesData rules, Row c) {
		return ClassFeatures.mechanic(tx, rules, c, KIND_METAMAGIC);
	}

	/** The options the class definition offers, from the feature's {@code options} list. */
	@SuppressWarnings("unchecked")
	public static List<Option> options(Map<String, Object> spec) {
		var out = new ArrayList<Option>();
		if (!(spec.get("options") instanceof List<?> list)) {
			return out;
		}
		for (Object o : list) {
			if (!(o instanceof Map<?, ?> m)) {
				continue;
			}
			Map<String, Object> option = (Map<String, Object>) m;
			String id = String.valueOf(option.get("id")).toLowerCase();
			int cost = option.get("cost") instanceof Number n ? n.intValue() : DEFAULT_COST.getOrDefault(id, 1);
			out.add(new Option(id, String.valueOf(option.getOrDefault("name", id)), cost,
					option.get("summary") == null ? null : String.valueOf(option.get("summary"))));
		}
		return out;
	}

	/** How many options a sorcerer of the given class level knows: the largest {@code options_known} step reached. */
	@SuppressWarnings("unchecked")
	public static int allowed(Map<String, Object> spec, int classLevel) {
		int allowed = 0;
		if (spec.get("options_known") instanceof Map<?, ?> steps) {
			for (var e : ((Map<String, Object>) steps).entrySet()) {
				int at = Integer.parseInt(e.getKey());
				if (classLevel >= at && ((Number) e.getValue()).intValue() > allowed) {
					allowed = ((Number) e.getValue()).intValue();
				}
			}
		}
		return allowed;
	}

	/** The METAMAGIC feature block of a class definition, if the class has one reachable at the given level. */
	@SuppressWarnings("unchecked")
	public static Optional<Map<String, Object>> featureSpec(RulesData.Definition classDef, int classLevel) {
		if (!(classDef.payload().get("features") instanceof List<?> list)) {
			return Optional.empty();
		}
		for (Object o : list) {
			if (o instanceof Map<?, ?> f && ((Map<String, Object>) f).get("mechanic") instanceof Map<?, ?> m
					&& KIND_METAMAGIC.equals(((Map<String, Object>) m).get("kind"))) {
				int at = ((Map<String, Object>) f).get("level") instanceof Number n ? n.intValue() : 1;
				if (classLevel >= at) {
					return Optional.of((Map<String, Object>) m);
				}
			}
		}
		return Optional.empty();
	}

	public static List<String> known(Tx tx, long characterId) {
		return tx.query(
				"SELECT content_ref FROM character_trait WHERE character_id = ? AND kind = ? AND content_ref LIKE 'metamagic:%' ORDER BY id",
				characterId, TRAIT_KIND).stream().map(r -> r.str("content_ref").substring(TRAIT_PREFIX.length())).toList();
	}

	/** Resolves an option by id or name ("empowered", "Empowered Spell", "Empowered"). */
	public static Optional<Option> resolve(List<Option> options, String text) {
		if (text == null) {
			return Optional.empty();
		}
		String t = text.trim().toLowerCase();
		String bare = t.endsWith(" spell") ? t.substring(0, t.length() - " spell".length()) : t;
		return options.stream().filter(o -> o.id().equals(bare) || o.name().toLowerCase().equals(t)).findFirst();
	}

	/**
	 * Validates a list of option names against the class's list and the count the character may know, and returns
	 * the ids. Used by the level-up choice and the SET_METAMAGIC override.
	 */
	public static List<String> validateChoice(
			List<Option> options, int allowed, List<String> alreadyKnown, Object value, boolean exactlyOwed) {
		if (!(value instanceof List<?> list) || list.isEmpty()) {
			throw RpgException.invalidArgument(
					"metamagic must be a list of option names, e.g. [\"Empowered Spell\", \"Quickened Spell\"]; the options are "
							+ options.stream().map(Option::name).toList() + ".");
		}
		var ids = new ArrayList<String>();
		for (Object o : list) {
			Option opt = resolve(options, String.valueOf(o)).orElseThrow(() -> RpgException.validation(
					List.of(new Violation("metamagic", "UNKNOWN_OPTION",
							"'" + o + "' is not a Metamagic option; choose from " + options.stream().map(Option::name)
									.toList() + "."))));
			if (alreadyKnown.contains(opt.id())) {
				throw RpgException.validation(
						List.of(new Violation("metamagic", "ALREADY_KNOWN", opt.name() + " is already known.")));
			}
			if (!ids.contains(opt.id())) {
				ids.add(opt.id());
			}
		}
		int owed = allowed - alreadyKnown.size();
		if (ids.size() > owed || (exactlyOwed && ids.size() != owed)) {
			throw RpgException.validation(List.of(new Violation("metamagic", "COUNT",
					"Choose " + owed + " option" + (owed == 1 ? "" : "s") + " (" + allowed + " known at this level, "
							+ alreadyKnown.size() + " already chosen).")));
		}
		return ids;
	}

	/** Replaces the character's known options. */
	public static void setKnown(Tx tx, long characterId, List<String> ids, List<Option> options) {
		for (Row t : tx.query(
				"SELECT id FROM character_trait WHERE character_id = ? AND kind = ? AND content_ref LIKE 'metamagic:%'",
				characterId, TRAIT_KIND)) {
			tx.delete("character_trait", t.id());
		}
		addKnown(tx, characterId, ids, options);
	}

	public static void addKnown(Tx tx, long characterId, List<String> ids, List<Option> options) {
		for (String id : ids) {
			Option opt = options.stream().filter(o -> o.id().equals(id)).findFirst().orElse(null);
			var payload = new LinkedHashMap<String, Object>();
			payload.put("source", Origins.SOURCE_CLASS);
			if (opt != null) {
				payload.put("name", opt.name());
				payload.put("cost", opt.cost());
			}
			if (tx.count("SELECT COUNT(*) FROM character_trait WHERE character_id = ? AND kind = ? AND content_ref = ?",
					characterId, TRAIT_KIND, TRAIT_PREFIX + id) == 0) {
				Origins.insertTrait(tx, characterId, TRAIT_KIND, TRAIT_PREFIX + id, payload);
			}
		}
	}

	// ── sheet ──────────────────────────────────────────────────────────

	/** Adds {@code sorcery_points} and {@code metamagic} to a spellcasting block when the character has the features. */
	public static void appendSheet(Tx tx, RulesData rules, Row c, Map<String, Object> m) {
		ClassFeatures.mechanic(tx, rules, c, KIND_FONT).ifPresent(font -> {
			var points = new LinkedHashMap<String, Object>();
			pointsRow(tx, c.id()).ifPresentOrElse(r -> {
				points.put("current", r.intOr("current", 0));
				points.put("max", r.intOr("max", 0));
			}, () -> {
				points.put("current", 0);
				points.put("max", 0);
			});
			points.put("slot_costs", font.spec().getOrDefault("slot_costs", defaultSlotCosts()));
			points.put("note",
					"apply_runtime_change CREATE_SPELL_SLOT {slot_level} and CONVERT_SPELL_SLOT {slot_level} are the Font of Magic conversions (Bonus Action).");
			m.put("sorcery_points", points);
		});
		feature(tx, rules, c).ifPresent(mm -> {
			List<Option> options = options(mm.spec());
			List<String> known = known(tx, c.id());
			var block = new LinkedHashMap<String, Object>();
			block.put("options_known_limit", allowed(mm.spec(), mm.classLevel()));
			block.put("known", known.stream()
					.map(id -> options.stream().filter(o -> o.id().equals(id)).findFirst().map(Option::toMap)
							.orElse(Map.of("id", id))).toList());
			if (known.size() < allowed(mm.spec(), mm.classLevel())) {
				block.put("unchosen", allowed(mm.spec(), mm.classLevel()) - known.size());
				block.put("note", "Options still to choose: at the next level-up (choices.metamagic) or with apply_gm_override SET_METAMAGIC.");
			}
			block.put("rule",
					"Pass options.metamagic (cast_spell) or action.metamagic (CAST) with option names; one option per spell, plus Empowered and/or Seeking.");
			m.put("metamagic", block);
		});
	}

	public static Optional<Row> pointsRow(Tx tx, long characterId) {
		return tx.queryOne("SELECT * FROM resource_state WHERE character_id = ? AND resource_ref = ?", characterId,
				POINTS);
	}

	private static Map<String, Object> defaultSlotCosts() {
		var m = new LinkedHashMap<String, Object>();
		m.put("1", 2);
		m.put("2", 3);
		m.put("3", 5);
		m.put("4", 6);
		m.put("5", 7);
		return m;
	}

	// ── Font of Magic conversions ──────────────────────────────────────

	/**
	 * {@code CREATE_SPELL_SLOT}: sorcery points become one spell slot of {@code slot_level} (1-5) at the table's cost;
	 * {@code CONVERT_SPELL_SLOT}: one unexpended slot becomes points equal to its level, never above the maximum. Both
	 * are Bonus Actions in the fiction. A created slot must be of a level the character can normally cast: created
	 * slots live in the same pool and vanish at the Long Rest that refills it (RULES_ENGINE.md §6).
	 */
	@SuppressWarnings("unchecked")
	public static Map<String, Object> fontOfMagic(
			Tx tx, RulesData rules, Row c, String kind, Map<String, Object> change) {
		ClassFeatures.Mechanic font = ClassFeatures.mechanic(tx, rules, c, KIND_FONT).orElseThrow(
				() -> RpgException.notAllowed(c.str("name") + " has no Font of Magic feature."));
		int level = change.get("slot_level") instanceof Number n ? n.intValue() : 0;
		if (level < 1) {
			throw RpgException.invalidArgument("change.slot_level (1-5) is required.");
		}
		Row points = pointsRow(tx, c.id()).orElseThrow(
				() -> RpgException.notAllowed(c.str("name") + " has no sorcery points resource."));
		Row slot = tx.queryOne("SELECT * FROM resource_state WHERE character_id = ? AND resource_ref = ?", c.id(),
				SpellService.SLOT_PREFIX + level).filter(r -> r.intOr("max", 0) > 0).orElseThrow(
				() -> RpgException.validation(List.of(new Violation("change.slot_level", "NO_SUCH_SLOT",
						c.str("name") + " has no level " + level + " spell slots to " + (kind.equals("CREATE_SPELL_SLOT")
								? "create" : "convert") + "."))));
		var result = new LinkedHashMap<String, Object>();
		if (kind.equals("CREATE_SPELL_SLOT")) {
			Map<String, Object> costs = font.spec().get("slot_costs") instanceof Map<?, ?> m
					? (Map<String, Object>) m : defaultSlotCosts();
			if (!(costs.get(Integer.toString(level)) instanceof Number costN)) {
				throw RpgException.validation(List.of(new Violation("change.slot_level", "TOO_HIGH",
						"Font of Magic creates slots of level 1 to " + costs.keySet().stream().mapToInt(Integer::parseInt)
								.max().orElse(5) + " only.")));
			}
			int cost = costN.intValue();
			if (points.intOr("current", 0) < cost) {
				throw RpgException.insufficientResource(
						c.str("name") + " has " + points.intOr("current", 0) + " sorcery points; a level " + level
								+ " slot costs " + cost + ".");
			}
			tx.update("resource_state", points.id(), Map.of("current", points.intOr("current", 0) - cost));
			// The created slot is an extra one: the pool's maximum grows with it until the Long Rest, which resizes
			// the pool back to the class table (SpellService.initializeSlots) and so makes the created slot vanish.
			tx.update("resource_state", slot.id(),
					Map.of("current", slot.intOr("current", 0) + 1, "max", slot.intOr("max", 0) + 1));
			result.put("created_slot_level", level);
			result.put("points_spent", cost);
			result.put("sorcery_points", Map.of("current", points.intOr("current", 0) - cost, "max",
					points.intOr("max", 0)));
			result.put("slot", Map.of("slot_level", level, "current", slot.intOr("current", 0) + 1, "max",
					slot.intOr("max", 0) + 1));
			result.put("note", "A Bonus Action; the created slot vanishes with the next Long Rest (SRD 5.2.1 Font of Magic).");
		} else {
			if (slot.intOr("current", 0) <= 0) {
				throw RpgException.insufficientResource(
						c.str("name") + " has no level " + level + " spell slots left to convert.");
			}
			int gained = Math.min(level, points.intOr("max", 0) - points.intOr("current", 0));
			tx.update("resource_state", slot.id(), Map.of("current", slot.intOr("current", 0) - 1));
			tx.update("resource_state", points.id(), Map.of("current", points.intOr("current", 0) + gained));
			result.put("converted_slot_level", level);
			result.put("points_gained", gained);
			if (gained < level) {
				result.put("note", "Points cannot exceed the maximum; " + (level - gained) + " lost.");
			}
			result.put("sorcery_points", Map.of("current", points.intOr("current", 0) + gained, "max",
					points.intOr("max", 0)));
			result.put("slot", Map.of("slot_level", level, "current", slot.intOr("current", 0) - 1, "max",
					slot.intOr("max", 0)));
		}
		tx.update("character", c.id(), Map.of("revision", c.lng("revision") + 1));
		return result;
	}

	/** Sorcerous Restoration on a Short Rest: regain up to half the class level in points, once per Long Rest. */
	public static Optional<Map<String, Object>> sorcerousRestoration(Tx tx, RulesData rules, Row c) {
		Optional<ClassFeatures.Mechanic> feature = ClassFeatures.mechanic(tx, rules, c, KIND_RESTORATION);
		if (feature.isEmpty()) {
			return Optional.empty();
		}
		Optional<Row> use = tx.queryOne("SELECT * FROM resource_state WHERE character_id = ? AND resource_ref = ?",
				c.id(), RESTORATION);
		Optional<Row> points = pointsRow(tx, c.id());
		if (use.isEmpty() || use.get().intOr("current", 0) <= 0 || points.isEmpty()) {
			return Optional.empty();
		}
		int missing = points.get().intOr("max", 0) - points.get().intOr("current", 0);
		if (missing <= 0) {
			return Optional.empty();
		}
		int regain = Math.min(missing, Math.max(1, feature.get().classLevel() / 2));
		tx.update("resource_state", points.get().id(), Map.of("current", points.get().intOr("current", 0) + regain));
		tx.update("resource_state", use.get().id(), Map.of("current", use.get().intOr("current", 0) - 1));
		var m = new LinkedHashMap<String, Object>();
		m.put("feature", feature.get().name());
		m.put("points_regained", regain);
		m.put("sorcery_points", Map.of("current", points.get().intOr("current", 0) + regain, "max",
				points.get().intOr("max", 0)));
		m.put("note", "Once per Long Rest.");
		return Optional.of(m);
	}

	// ── a casting plan ─────────────────────────────────────────────────

	/** The Metamagic applied to one casting: which options, what they cost, and what the casting core must do. */
	public static final class Plan {

		final List<Option> used = new ArrayList<>();
		final List<String> notes = new ArrayList<>();
		int pointsSpent;
		long pointsRowId;
		int pointsRemaining;
		int pointsMax;
		Long heightenedTarget;
		final Set<Long> careful = new LinkedHashSet<>();
		String transmuteTo;
		int rerollBudget;
		boolean empoweredSpent;
		int seekingRerolls;

		public boolean has(String id) {
			return used.stream().anyMatch(o -> o.id().equals(id));
		}

		public boolean extended() {
			return has("extended");
		}

		public Long heightenedTarget() {
			return heightenedTarget;
		}

		public boolean carefulFor(long characterId) {
			return careful.contains(characterId);
		}

		public String summary() {
			return used.stream().map(Option::name).toList().toString();
		}

		public Map<String, Object> toMap() {
			var m = new LinkedHashMap<String, Object>();
			m.put("options", used.stream().map(Option::name).toList());
			m.put("points_spent", pointsSpent);
			m.put("sorcery_points", Map.of("current", pointsRemaining, "max", pointsMax));
			if (!notes.isEmpty()) {
				m.put("notes", notes);
			}
			return m;
		}
	}

	/**
	 * Builds and pays for the plan named by {@code options.metamagic} (a name or a list of names), or returns null
	 * when no Metamagic was asked for. Every option is checked against the spell it is applied to.
	 */
	@SuppressWarnings("unchecked")
	public static Plan plan(
			Tx tx, RulesData rules, long campaignId, Row caster, Map<String, Object> opts, RulesData.Definition def,
			Map<String, Object> mech, List<Row> targets) {
		Object asked = opts.get("metamagic");
		if (asked == null) {
			return null;
		}
		List<String> names = asked instanceof List<?> l ? l.stream().map(String::valueOf).toList()
				: List.of(String.valueOf(asked));
		if (names.isEmpty()) {
			return null;
		}
		ClassFeatures.Mechanic feature = feature(tx, rules, caster).orElseThrow(() -> RpgException.validation(
				List.of(new Violation("options.metamagic", "NOT_AVAILABLE",
						caster.str("name") + " has no Metamagic feature."))));
		List<Option> options = options(feature.spec());
		List<String> known = known(tx, caster.id());
		Map<String, Object> p = def.payload();
		String kind = String.valueOf(mech.getOrDefault("kind", "UTILITY"));
		Plan plan = new Plan();
		int chaMod = Rules.modifier(caster.intOr("cha_score", 10));
		// Sorcery Incarnate (level 7): while Innate Sorcery is active, two options may shape one spell. The fiction
		// says whether it is active; the caster passes options.sorcery_incarnate: true and the feature must exist.
		boolean incarnate = Boolean.TRUE.equals(opts.get("sorcery_incarnate")) || "true".equalsIgnoreCase(
				String.valueOf(opts.get("sorcery_incarnate")));
		if (incarnate && ClassFeatures.mechanic(tx, rules, caster, "SORCERY_INCARNATE").isEmpty()) {
			throw RpgException.validation(List.of(new Violation("options.sorcery_incarnate", "NOT_AVAILABLE",
					caster.str("name") + " does not have Sorcery Incarnate (Sorcerer level 7).")));
		}
		int shapingLimit = incarnate ? 2 : 1;
		var shaping = new ArrayList<String>();
		if (incarnate) {
			plan.notes.add("Sorcery Incarnate: up to two options on this spell.");
		}
		for (String name : names) {
			Option opt = resolve(options, name).orElseThrow(() -> RpgException.validation(
					List.of(new Violation("options.metamagic", "UNKNOWN_OPTION",
							"'" + name + "' is not a Metamagic option; the options are " + options.stream()
									.map(Option::name).toList() + "."))));
			if (!known.contains(opt.id())) {
				throw RpgException.validation(List.of(new Violation("options.metamagic", "NOT_KNOWN",
						caster.str("name") + " does not know " + opt.name() + "; known: " + known + ".")));
			}
			if (plan.has(opt.id())) {
				continue;
			}
			if (!COMBINABLE.contains(opt.id())) {
				if (shaping.size() >= shapingLimit) {
					throw RpgException.validation(List.of(new Violation("options.metamagic", "ONE_OPTION",
							(incarnate ? "Sorcery Incarnate allows two options" : "Only one Metamagic option shapes a spell")
									+ " (plus Empowered and Seeking); " + String.join(", ", shaping) + " and " + opt.name()
									+ " cannot all be used.")));
				}
				shaping.add(opt.name());
			}
			checkApplies(tx, campaignId, caster, opt, p, mech, kind, targets, opts, plan, chaMod);
			plan.used.add(opt);
			if (!opt.id().equals("seeking")) {
				plan.pointsSpent += opt.cost();
			}
		}
		Row points = pointsRow(tx, caster.id()).orElseThrow(
				() -> RpgException.notAllowed(caster.str("name") + " has no sorcery points resource."));
		int current = points.intOr("current", 0);
		if (current < plan.pointsSpent) {
			throw RpgException.insufficientResource(
					caster.str("name") + " has " + current + " sorcery point" + (current == 1 ? "" : "s") + "; "
							+ plan.summary() + " cost" + (plan.used.size() == 1 ? "s" : "") + " " + plan.pointsSpent + ".");
		}
		plan.pointsRowId = points.id();
		plan.pointsMax = points.intOr("max", 0);
		plan.pointsRemaining = current - plan.pointsSpent;
		if (plan.pointsSpent > 0) {
			tx.update("resource_state", points.id(), Map.of("current", plan.pointsRemaining));
		}
		return plan;
	}

	@SuppressWarnings("unchecked")
	private static void checkApplies(
			Tx tx, long campaignId, Row caster, Option opt, Map<String, Object> p, Map<String, Object> mech,
			String kind, List<Row> targets, Map<String, Object> opts, Plan plan, int chaMod) {
		String range = String.valueOf(p.getOrDefault("range", "")).toLowerCase();
		String duration = String.valueOf(p.getOrDefault("duration", "")).toLowerCase();
		String castingTime = String.valueOf(p.getOrDefault("casting_time", "")).toLowerCase();
		boolean hasDamage = mech.get("damage") instanceof List<?> d && !d.isEmpty() || mech.get("per_dart") != null;
		switch (opt.id()) {
		case "careful" -> {
			requireKind(opt, kind, "SAVE", "forces a saving throw");
			Object list = opts.get("careful");
			if (!(list instanceof List<?> refs) || refs.isEmpty()) {
				throw violation(opt, "TARGETS_REQUIRED",
						"pass options.careful = [character refs] naming the creatures that automatically succeed.");
			}
			int limit = Math.max(1, chaMod);
			if (refs.size() > limit) {
				throw violation(opt, "TOO_MANY", "protects up to " + limit + " creature" + (limit == 1 ? "" : "s")
						+ " (Charisma modifier, minimum 1).");
			}
			for (Object ref : refs) {
				plan.careful.add(CharacterService.character(tx, campaignId, String.valueOf(ref)).id());
			}
			plan.notes.add("Careful: " + refs.size() + " creature(s) succeed automatically and take no damage.");
		}
		case "distant" -> {
			if (range.startsWith("self")) {
				throw violation(opt, "RANGE", "needs a spell with a range of 5+ feet or Touch, not Self.");
			}
			plan.notes.add(range.startsWith("touch") ? "Distant: range 30 feet instead of Touch."
					: "Distant: range doubled (" + p.get("range") + ").");
		}
		case "empowered" -> {
			if (!hasDamage) {
				throw violation(opt, "NO_DAMAGE", "needs a spell that rolls damage.");
			}
			plan.rerollBudget = Math.max(1, chaMod);
			plan.notes.add("Empowered: up to " + plan.rerollBudget + " lowest damage dice rerolled once this casting.");
		}
		case "extended" -> {
			if (!(duration.contains("minute") || duration.contains("hour") || duration.contains("day"))) {
				throw violation(opt, "DURATION", "needs a duration of 1 minute or longer.");
			}
			plan.notes.add("Extended: duration doubled (at most 24 hours); Advantage on saves to keep concentration.");
		}
		case "heightened" -> {
			requireKind(opt, kind, "SAVE", "forces a saving throw");
			Object ref = opts.get("heightened_target");
			Row target = ref != null ? CharacterService.character(tx, campaignId, String.valueOf(ref))
					: targets.isEmpty() ? null : targets.get(0);
			if (target == null) {
				throw violation(opt, "TARGET_REQUIRED", "needs a target (options.heightened_target or the first target).");
			}
			plan.heightenedTarget = target.id();
			plan.notes.add("Heightened: " + target.str("name") + " has Disadvantage on the save.");
		}
		case "quickened" -> {
			if (!castingTime.startsWith("1 action")) {
				throw violation(opt, "CASTING_TIME", "needs a casting time of 1 action (this spell: " + p.get(
						"casting_time") + ").");
			}
			plan.notes.add("Quickened: cast as a Bonus Action; still one spell slot per turn (SRD 5.2.1).");
		}
		case "seeking" -> {
			requireKind(opt, kind, "ATTACK", "makes an attack roll");
			plan.notes.add("Seeking: a missed attack roll is rerolled, 1 point per reroll, spent when used.");
		}
		case "subtle" -> {
			List<Object> components = p.get("components") instanceof List<?> l ? (List<Object>) l : List.of();
			if (!components.contains("V") && !components.contains("S")) {
				throw violation(opt, "COMPONENTS", "needs a spell with a Verbal or Somatic component.");
			}
			plan.notes.add("Subtle: no Verbal or Somatic component; cannot be Counterspelled by sight.");
		}
		case "transmuted" -> {
			Object wanted = opts.get("damage_type");
			if (wanted == null || !ELEMENTS.contains(String.valueOf(wanted).toLowerCase())) {
				throw violation(opt, "DAMAGE_TYPE",
						"pass options.damage_type as one of " + ELEMENTS.stream().sorted().toList() + ".");
			}
			boolean elemental = mech.get("damage") instanceof List<?> parts && parts.stream().anyMatch(
					part -> part instanceof Map<?, ?> pm && ELEMENTS.contains(
							String.valueOf(pm.get("type")).toLowerCase()));
			if (!elemental) {
				throw violation(opt, "NO_ELEMENT", "needs a spell that deals acid, cold, fire, lightning, poison or thunder damage.");
			}
			plan.transmuteTo = String.valueOf(wanted).toLowerCase();
			plan.notes.add("Transmuted: damage type changed to " + plan.transmuteTo + ".");
		}
		case "twinned" -> {
			boolean scalesTargets = mech.get("upcast") instanceof Map<?, ?> u && u.get("per_level_targets") != null;
			if (!scalesTargets) {
				throw violation(opt, "NO_EXTRA_TARGET",
						"needs a spell that gains a target when cast with a higher-level slot (Charm Person, Hold Person, ...).");
			}
			plan.notes.add("Twinned: the spell's effective level is one higher for its target count: one more target.");
		}
		default -> plan.notes.add(opt.name() + ": adjudicate from the rules text.");
		}
	}

	private static void requireKind(Option opt, String kind, String wanted, String what) {
		if (!wanted.equals(kind)) {
			throw violation(opt, "SPELL_KIND", "needs a spell that " + what + ".");
		}
	}

	private static RpgException violation(Option opt, String code, String message) {
		return RpgException.validation(
				List.of(new Violation("options.metamagic", code, opt.name() + " " + message)));
	}

	// ── hooks used by the casting core ─────────────────────────────────

	/**
	 * Empowered Spell: rerolls the lowest damage dice of the casting's first damage roll (up to the Charisma modifier,
	 * minimum one) and uses the new results. Returns the roll unchanged when the plan does not empower or has already.
	 */
	public static Roll empower(
			Tx tx, RollService roller, long campaignId, Plan plan, String expression, Roll roll, String label) {
		if (plan == null || !plan.has("empowered") || plan.empoweredSpent || roll.dice().isEmpty()) {
			return roll;
		}
		var sides = new ArrayList<Integer>();
		try {
			for (DiceExpression.Term term : DiceExpression.parse(expression).terms()) {
				if (term.isDice()) {
					for (int i = 0; i < term.count(); i++) {
						sides.add(term.sides());
					}
				}
			}
		} catch (RuntimeException e) {
			return roll;
		}
		if (sides.size() != roll.dice().size()) {
			// Kept/dropped dice make the mapping ambiguous; leave the roll alone rather than guess.
			return roll;
		}
		var dice = new ArrayList<>(roll.dice());
		var order = new ArrayList<Integer>();
		for (int i = 0; i < dice.size(); i++) {
			order.add(i);
		}
		order.sort((a, b) -> Integer.compare(dice.get(a), dice.get(b)));
		var rerolled = new ArrayList<Map<String, Object>>();
		int total = roll.total();
		for (int k = 0; k < Math.min(plan.rerollBudget, order.size()); k++) {
			int index = order.get(k);
			int was = dice.get(index);
			Roll fresh = roller.roll("1d" + sides.get(index));
			int now = fresh.total();
			dice.set(index, now);
			total += now - was;
			rerolled.add(Map.of("die", "d" + sides.get(index), "was", was, "now", now));
		}
		plan.empoweredSpent = true;
		plan.notes.add("Empowered on " + label + ": rerolled " + rerolled);
		Roll out = new Roll(expression + " (Empowered)", dice, roll.dropped(), roll.modifier(), total);
		CharacterService.recordRoll(tx, campaignId, "Empowered reroll " + label, out);
		return out;
	}

	/** Seeking Spell: whether a missed spell attack may be rerolled now; spends the point when it says yes. */
	public static boolean seek(Tx tx, Plan plan) {
		if (plan == null || !plan.has("seeking")) {
			return false;
		}
		Row points = tx.get("resource_state", plan.pointsRowId);
		int cost = plan.used.stream().filter(o -> o.id().equals("seeking")).findFirst().map(Option::cost).orElse(1);
		if (points.intOr("current", 0) < cost) {
			plan.notes.add("Seeking: no sorcery points left for a reroll.");
			return false;
		}
		tx.update("resource_state", points.id(), Map.of("current", points.intOr("current", 0) - cost));
		plan.pointsSpent += cost;
		plan.pointsRemaining = points.intOr("current", 0) - cost;
		plan.seekingRerolls++;
		return true;
	}

	/** Transmuted Spell: the damage type to use for an elemental part, else the printed one. */
	public static String transmute(Plan plan, String type) {
		if (plan != null && plan.transmuteTo != null && ELEMENTS.contains(type.toLowerCase())) {
			return plan.transmuteTo;
		}
		return type;
	}
}
