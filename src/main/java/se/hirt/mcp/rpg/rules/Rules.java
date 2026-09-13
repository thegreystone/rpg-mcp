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

import se.hirt.mcp.rpg.protocol.RpgException;
import se.hirt.mcp.rpg.protocol.Violation;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The small set of hard-coded formulas (RULES_ENGINE.md §3). Everything tabular comes from seed
 * data.
 * <p>
 * Citations: SRD 5.2.1 "Playing the Game" — ability modifiers, D20 Tests, advantage/disadvantage.
 */
public final class Rules {

	/**
	 * Ability score bounds during character creation (SRD 5.2.1: generated scores; 20 is the hard
	 * cap in play).
	 */
	public static final int MIN_SCORE = 1;
	public static final int MAX_SCORE = 20;

	public static final List<Integer> STANDARD_ARRAY = List.of(15, 14, 13, 12, 10, 8);
	public static final int POINT_BUY_BUDGET = 27;
	public static final int POINT_BUY_MIN = 8;
	public static final int POINT_BUY_MAX = 15;

	private Rules() {
	}

	/** {@code floor((score - 10) / 2)} — SRD 5.2.1 "Ability Modifiers". */
	public static int modifier(int score) {
		return Math.floorDiv(score - 10, 2);
	}

	/** SRD 5.2.1 "Point Cost" table — scores 8–15 only. */
	public static int pointCost(int score) {
		return switch (score) {
		case 8 -> 0;
		case 9 -> 1;
		case 10 -> 2;
		case 11 -> 3;
		case 12 -> 4;
		case 13 -> 5;
		case 14 -> 7;
		case 15 -> 9;
		default -> throw RpgException.invalidArgument("Point buy scores must be between 8 and 15; got " + score + ".");
		};
	}

	public static String formatModifier(int modifier) {
		return modifier >= 0 ? "+" + modifier : Integer.toString(modifier);
	}

	/**
	 * Validates a full six-score assignment against a generation method.
	 *
	 * @param scores
	 *            map of ability → score (all six required)
	 * @param method
	 *            STANDARD_ARRAY | POINT_BUY | ROLL_4D6_DROP_LOWEST
	 * @param rolledScores
	 *            the multiset produced by the roller (only for ROLL_4D6_DROP_LOWEST)
	 */
	public static List<Violation> validateScores(
		Map<Ability, Integer> scores, String method, List<Integer> rolledScores) {
		var violations = new ArrayList<Violation>();
		for (Ability a : Ability.values()) {
			if (!scores.containsKey(a)) {
				violations.add(
						new Violation("ability_scores." + a.name(), "SCORE_REQUIRED", a.fullName() + " has no score."));
			}
		}
		if (!violations.isEmpty()) {
			return violations;
		}
		var values = new ArrayList<Integer>(scores.values());
		switch (method) {
		case "STANDARD_ARRAY" -> {
			if (!sameMultiset(values, STANDARD_ARRAY)) {
				violations.add(new Violation("ability_scores", "STANDARD_ARRAY",
						"Scores must be exactly the standard array " + STANDARD_ARRAY + " in some order."));
			}
		}
		case "POINT_BUY" -> {
			int spent = 0;
			for (var e : scores.entrySet()) {
				int s = e.getValue();
				if (s < POINT_BUY_MIN || s > POINT_BUY_MAX) {
					violations.add(new Violation("ability_scores." + e.getKey().name(), "POINT_BUY_RANGE",
							e.getKey().fullName() + " must be between 8 and 15 before other adjustments."));
				} else {
					spent += pointCost(s);
				}
			}
			if (spent > POINT_BUY_BUDGET) {
				violations.add(new Violation("ability_scores", "POINT_BUY_TOTAL",
						"The proposed scores cost " + spent + " points; the budget is " + POINT_BUY_BUDGET + "."));
			}
		}
		case "ROLL_4D6_DROP_LOWEST" -> {
			if (rolledScores == null || rolledScores.isEmpty()) {
				violations.add(new Violation("ability_scores", "ROLL_REQUIRED",
						"Generate ability scores with generate_ability_scores before assigning them."));
			} else if (!sameMultiset(values, rolledScores)) {
				violations.add(new Violation("ability_scores", "ROLLED_ASSIGNMENT",
						"Scores must be exactly the rolled values " + rolledScores + " in some order."));
			}
		}
		default -> violations.add(new Violation("rules.ability_generation", "UNKNOWN_METHOD",
				"Unknown ability generation method '" + method + "'."));
		}
		return violations;
	}

	private static boolean sameMultiset(List<Integer> a, List<Integer> b) {
		var x = new ArrayList<>(a);
		var y = new ArrayList<>(b);
		java.util.Collections.sort(x);
		java.util.Collections.sort(y);
		return x.equals(y);
	}
}
