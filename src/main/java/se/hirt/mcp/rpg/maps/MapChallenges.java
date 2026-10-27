/* Copyright (C) 2026 Marcus Hirt. SPDX-License-Identifier: BSD-3-Clause */
package se.hirt.mcp.rpg.maps;

import se.hirt.mcp.rpg.content.RulesData;
import se.hirt.mcp.rpg.protocol.Json;
import se.hirt.mcp.rpg.protocol.Violation;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Saved GM check guidance; these declarations do not execute discovery, locks or traps. */
public final class MapChallenges {
	private static final String GENERATED = "generated_challenges";
	private static final Set<String> KEYS = Set.of("pick_lock", "force_open", "discover", "disarm");
	private static final Set<String> FIELDS = Set.of("difficulty", "kind", "ability", "skill", "tool");
	private static final Set<String> KINDS = Set.of("ABILITY_CHECK", "SKILL_CHECK", "SAVING_THROW");
	private static final Set<String> ABILITIES = Set.of("STR", "DEX", "CON", "INT", "WIS", "CHA");

	private MapChallenges() {
	}

	/**
	 * Fill missing declarations deterministically, retaining all authored values for validation.
	 */
	public static void normalize(Map<String, Object> part, Map<String, Object> canonical) {
		normalize(part, canonical, true);
	}

	/** A secret entrance can own discovery without imposing a second room check. */
	static void normalize(Map<String, Object> part, Map<String, Object> canonical, boolean defaultDiscovery) {
		if (part.containsKey("challenges") && !(part.get("challenges") instanceof Map<?, ?>))
			return;
		var generated = new LinkedHashSet<String>();
		for (Map<String, Object> source : List.of(canonical, part))
			if (source.get(GENERATED) instanceof List<?> keys)
				keys.forEach(key -> generated.add(String.valueOf(key)));
		var challenges = copy(SceneService.map(canonical.get("challenges")));
		for (var declared : SceneService.map(part.get("challenges")).entrySet()) {
			if (declared.getValue() instanceof Map<?, ?>) {
				var spec = copy(SceneService.map(challenges.get(declared.getKey())));
				spec.putAll(SceneService.map(declared.getValue()));
				challenges.put(declared.getKey(), spec);
			} else
				challenges.put(declared.getKey(), declared.getValue());
		}
		String state = String.valueOf(canonical.getOrDefault("state", part.getOrDefault("state", "")))
				.toUpperCase(Locale.ROOT);
		boolean locked = "LOCKED".equals(state)
				|| Boolean.TRUE.equals(canonical.getOrDefault("locked", part.get("locked")));
		boolean secret = "SECRET".equals(state)
				|| Boolean.TRUE.equals(canonical.getOrDefault("secret", part.get("secret")));
		boolean trap = "TRAP".equalsIgnoreCase(String.valueOf(canonical.getOrDefault("kind", part.get("kind"))));
		Object concealed = canonical.getOrDefault("concealed", part.get("concealed"));
		boolean privateVisibility = Set.of("GM", "GM_ONLY", "DIRECTOR_ONLY")
				.contains(String
						.valueOf(canonical.getOrDefault("visibility", part.getOrDefault("visibility", "PARTY_KNOWN")))
						.toUpperCase(Locale.ROOT));
		boolean apparentTrap = trap && !secret && !privateVisibility && !Boolean.TRUE.equals(concealed)
				&& (Boolean.TRUE.equals(canonical.getOrDefault("known_hazard", part.get("known_hazard")))
						|| Boolean.FALSE.equals(concealed));
		defaultDiscovery = defaultDiscovery && !apparentTrap;
		generated.removeIf(
				key -> !KEYS.contains(key) || !Json.hash(challenges.get(key)).equals(Json.hash(defaults(key))));
		if (!defaultDiscovery && generated.remove("discover"))
			challenges.remove("discover");
		if (locked && !challenges.containsKey("pick_lock")) {
			challenges.put("pick_lock", defaults("pick_lock"));
			generated.add("pick_lock");
		}
		if (secret && defaultDiscovery && !challenges.containsKey("discover")) {
			challenges.put("discover", defaults("discover"));
			generated.add("discover");
		}
		if (trap) {
			if (defaultDiscovery && !challenges.containsKey("discover")) {
				challenges.put("discover", defaults("discover"));
				generated.add("discover");
			}
			if (!challenges.containsKey("disarm")) {
				challenges.put("disarm", defaults("disarm"));
				generated.add("disarm");
			}
		}
		for (var entry : challenges.entrySet()) {
			if (!KEYS.contains(entry.getKey()) || !(entry.getValue() instanceof Map<?, ?>))
				continue;
			var spec = copy(SceneService.map(entry.getValue()));
			Map<String, Object> fallback = defaults(entry.getKey());
			if (spec.get("kind") instanceof String requested
					&& !requested.equalsIgnoreCase(fallback.get("kind").toString())) {
				fallback.remove("skill");
				fallback.remove("tool");
			}
			fallback.forEach((field, value) -> {
				if (!spec.containsKey(field))
					spec.put(field, value);
			});
			for (String key : List.of("kind", "ability"))
				if (spec.get(key) instanceof String text)
					spec.put(key, text.toUpperCase(Locale.ROOT));
			entry.setValue(spec);
		}
		if (!challenges.isEmpty() || part.containsKey("challenges"))
			part.put("challenges", challenges);
		generated.retainAll(challenges.keySet());
		if (generated.isEmpty())
			part.remove(GENERATED);
		else
			part.put(GENERATED, List.copyOf(generated));
	}

	/** An explicit revision is authored even if it deliberately chooses the default DC. */
	static void markAuthored(Map<String, Object> part, Set<String> keys) {
		if (part.get(GENERATED) instanceof List<?> generated) {
			var remaining = generated.stream().map(String::valueOf).filter(key -> !keys.contains(key)).toList();
			if (remaining.isEmpty())
				part.remove(GENERATED);
			else
				part.put(GENERATED, remaining);
		}
	}

	private static Map<String, Object> defaults(String key) {
		if (key.equals("force_open"))
			return new LinkedHashMap<>(Map.of("kind", "ABILITY_CHECK", "ability", "STR", "difficulty", 15));
		if (key.equals("discover"))
			return new LinkedHashMap<>(
					Map.of("kind", "SKILL_CHECK", "ability", "WIS", "skill", "Perception", "difficulty", 15));
		return new LinkedHashMap<>(Map.of("kind", "SKILL_CHECK", "ability", "DEX", "skill", "Sleight of Hand", "tool",
				"Thieves' Tools", "difficulty", 15));
	}

	public static void validate(Map<String, Object> part, String path, RulesData rules, List<Violation> errors) {
		for (String flag : List.of("concealed", "known_hazard"))
			if (part.containsKey(flag) && !(part.get(flag) instanceof Boolean))
				errors.add(new Violation(path + "." + flag, "BOOLEAN_REQUIRED", flag + " must be true or false."));
		if (!part.containsKey("challenges"))
			return;
		if (!(part.get("challenges") instanceof Map<?, ?> raw)) {
			errors.add(new Violation(path + ".challenges", "CHALLENGES_INVALID",
					"challenges must be a map of named check specifications."));
			return;
		}
		for (var entry : raw.entrySet()) {
			String at = path + ".challenges." + entry.getKey();
			if (!KEYS.contains(String.valueOf(entry.getKey()))) {
				errors.add(new Violation(at, "CHALLENGE_UNKNOWN", "Use pick_lock, force_open, discover or disarm."));
				continue;
			}
			if (!(entry.getValue() instanceof Map<?, ?>)) {
				errors.add(new Violation(at, "CHECK_INVALID", "A challenge must be a check specification."));
				continue;
			}
			Map<String, Object> spec = SceneService.map(entry.getValue());
			for (String field : spec.keySet())
				if (!FIELDS.contains(field))
					errors.add(new Violation(at + "." + field, "CHECK_FIELD_UNKNOWN",
							"Use difficulty, kind, ability, skill or tool; stored challenges are resolve_check guidance."));
			if (!(spec.get("difficulty") instanceof Number number) || !Double.isFinite(number.doubleValue())
					|| number.doubleValue() < 1 || number.doubleValue() > 40
					|| number.doubleValue() != Math.rint(number.doubleValue()))
				errors.add(new Violation(at + ".difficulty", "DIFFICULTY_INVALID",
						"difficulty must be a whole number from 1 to 40."));
			String kind = String.valueOf(spec.get("kind"));
			if (!KINDS.contains(kind))
				errors.add(new Violation(at + ".kind", "CHECK_KIND_INVALID",
						"Use ABILITY_CHECK, SKILL_CHECK or SAVING_THROW."));
			if (!ABILITIES.contains(String.valueOf(spec.get("ability"))))
				errors.add(new Violation(at + ".ability", "ABILITY_INVALID", "Use STR, DEX, CON, INT, WIS or CHA."));
			if (kind.equals("SKILL_CHECK") || spec.containsKey("skill"))
				if (!(spec.get("skill") instanceof String skill) || rules.resolve("SKILL", skill).isEmpty())
					errors.add(new Violation(at + ".skill", "SKILL_INVALID", "Use an installed skill for this check."));
			if (spec.containsKey("tool")) {
				if (!(spec.get("tool") instanceof String tool) || rules.resolve("ITEM", tool)
						.filter(item -> "TOOL".equals(item.payload().get("type"))).isEmpty())
					errors.add(new Violation(at + ".tool", "TOOL_INVALID",
							"Use an installed tool, such as Thieves' Tools."));
				if (kind.equals("SAVING_THROW"))
					errors.add(
							new Violation(at + ".tool", "SAVE_TOOL_INVALID", "A tool cannot apply to a saving throw."));
			}
		}
	}

	/**
	 * An existing live challenge remains authoritative when another preparation binds its identity.
	 */
	public static void validateCanonical(
		Map<String, Object> part, Map<String, Object> canonical, String path, List<Violation> errors) {
		for (var entry : SceneService.map(canonical.get("challenges")).entrySet()) {
			Object declared = SceneService.map(part.get("challenges")).get(entry.getKey());
			if (!preservesEstablished(entry.getValue(), declared))
				errors.add(new Violation(path + ".challenges." + entry.getKey(), "CANONICAL_CHALLENGE_CHANGED",
						"Preserve the existing passage or object's saved challenge; authoring cannot reset a live difficulty."));
		}
	}

	private static boolean preservesEstablished(Object established, Object declared) {
		if (established instanceof Map<?, ?> previous && declared instanceof Map<?, ?> candidate)
			return previous.entrySet().stream().allMatch(entry -> candidate.containsKey(entry.getKey())
					&& preservesEstablished(entry.getValue(), candidate.get(entry.getKey())));
		return Json.hash(established).equals(Json.hash(declared));
	}

	private static Map<String, Object> copy(Map<String, Object> value) {
		return new LinkedHashMap<>(Json.readMap(Json.write(value)));
	}
}
