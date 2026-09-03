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
package se.hirt.mcp.rpg.progression;

import se.hirt.mcp.rpg.content.RulesData;
import se.hirt.mcp.rpg.persistence.Row;
import se.hirt.mcp.rpg.persistence.Tx;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Class features that the engine enforces, read from the class definition's {@code features} array rather than from
 * Java (RULES_ENGINE.md §2.2). A feature carries {@code enforcement} - ENGINE features have a {@code mechanic} the
 * rules engine applies; GM features are surfaced on the sheet and adjudicated in prose - so adding one is a seed edit,
 * not a code change, as long as its {@code mechanic.kind} is understood here.
 * <p>
 * Understood kinds so far: {@code SNEAK_ATTACK}.
 */
public final class ClassFeatures {

	private ClassFeatures() {
	}

	/** Every class level the character holds, as (definition, level) pairs. */
	public static List<Map.Entry<RulesData.Definition, Integer>> classesOf(Tx tx, RulesData rules, Row c) {
		var out = new ArrayList<Map.Entry<RulesData.Definition, Integer>>();
		for (Row row : tx.query("SELECT * FROM character_class WHERE character_id = ? ORDER BY id", c.id())) {
			rules.find(row.str("class_ref"))
					.ifPresent(d -> out.add(Map.entry(d, row.intOr("level", 1))));
		}
		return out;
	}

	/** Features the character has actually reached, newest class first, each with the level it was gained at. */
	@SuppressWarnings("unchecked")
	public static List<Map<String, Object>> featuresOf(Tx tx, RulesData rules, Row c) {
		var out = new ArrayList<Map<String, Object>>();
		for (var entry : classesOf(tx, rules, c)) {
			Object features = entry.getKey().payload().get("features");
			if (!(features instanceof List<?> list)) {
				continue;
			}
			for (Object o : list) {
				if (!(o instanceof Map<?, ?> f)) {
					continue;
				}
				Map<String, Object> feature = (Map<String, Object>) f;
				int at = feature.get("level") instanceof Number n ? n.intValue() : 1;
				if (at <= entry.getValue()) {
					var m = new LinkedHashMap<String, Object>(feature);
					m.put("class", entry.getKey().name());
					m.put("class_level", entry.getValue());
					out.add(m);
				}
			}
		}
		return out;
	}

	/** The first ENGINE-enforced feature of a given mechanic kind, with the class level the character has in it. */
	@SuppressWarnings("unchecked")
	public static Optional<Mechanic> mechanic(Tx tx, RulesData rules, Row c, String kind) {
		for (Map<String, Object> feature : featuresOf(tx, rules, c)) {
			if (!(feature.get("mechanic") instanceof Map<?, ?> m)) {
				continue;
			}
			Map<String, Object> mech = (Map<String, Object>) m;
			if (kind.equals(mech.get("kind"))) {
				return Optional.of(new Mechanic(String.valueOf(feature.get("name")), mech,
						((Number) feature.get("class_level")).intValue()));
			}
		}
		return Optional.empty();
	}

	/** A feature's mechanic block plus the character's level in the class that granted it. */
	public record Mechanic(String name, Map<String, Object> spec, int classLevel) {

		/**
		 * The dice expression for a feature that scales one die per N class levels, rounded up: a Rogue's Sneak Attack
		 * is 1d6 at levels 1-2, 2d6 at 3-4, and so on (SRD 5.2.1 "Rogue").
		 */
		public String scaledDice() {
			String die = String.valueOf(spec.getOrDefault("die", "d6"));
			int per = spec.get("dice_per_levels") instanceof Number n ? Math.max(1, n.intValue()) : 1;
			int count = Math.max(1, (classLevel + per - 1) / per);
			return count + die;
		}

		@SuppressWarnings("unchecked")
		public List<String> requiresWeapon() {
			return spec.get("requires_weapon") instanceof List<?> l ? l.stream().map(String::valueOf).toList()
					: List.of();
		}

		public boolean oncePerTurn() {
			return !Boolean.FALSE.equals(spec.get("once_per_turn"));
		}
	}
}
