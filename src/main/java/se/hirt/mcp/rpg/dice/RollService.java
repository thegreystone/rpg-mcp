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
package se.hirt.mcp.rpg.dice;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The roller (RULES_ENGINE.md §4). One interface, two implementations: production randomness and a scripted test
 * roller. Only the die source differs; the arithmetic is shared and deterministic.
 */
public abstract class RollService {

	/** Returns a uniformly distributed value in {@code [1, sides]}. */
	protected abstract int rollDie(int sides);

	public Roll roll(String expression) {
		return roll(DiceExpression.parse(expression));
	}

	public Roll roll(DiceExpression expression) {
		var kept = new ArrayList<Integer>();
		var dropped = new ArrayList<Integer>();
		int total = 0;
		for (DiceExpression.Term term : expression.terms()) {
			if (!term.isDice()) {
				total += term.sign() * term.constant();
				continue;
			}
			var values = new ArrayList<Integer>(term.count());
			for (int i = 0; i < term.count(); i++) {
				values.add(rollDie(term.sides()));
			}
			var keptHere = selectKept(values, term);
			var droppedHere = new ArrayList<>(values);
			for (Integer k : keptHere) {
				droppedHere.remove(k);
			}
			kept.addAll(keptHere);
			dropped.addAll(droppedHere);
			total += term.sign() * keptHere.stream().mapToInt(Integer::intValue).sum();
		}
		return new Roll(render(expression), List.copyOf(kept), List.copyOf(dropped), expression.constantModifier(),
				total);
	}

	private static List<Integer> selectKept(List<Integer> values, DiceExpression.Term term) {
		int n = term.keepCount();
		return switch (term.keep()) {
			case ALL -> values;
			case HIGHEST -> values.stream().sorted(Comparator.reverseOrder()).limit(n).toList();
			case LOWEST -> values.stream().sorted().limit(n).toList();
			case DROP_LOWEST -> values.stream().sorted(Comparator.reverseOrder()).limit(values.size() - n).toList();
			case DROP_HIGHEST -> values.stream().sorted().limit(values.size() - n).toList();
		};
	}

	private static String render(DiceExpression expression) {
		var sb = new StringBuilder();
		boolean first = true;
		for (DiceExpression.Term t : expression.terms()) {
			if (!first || t.sign() < 0) {
				sb.append(t.sign() < 0 ? "-" : "+");
			}
			first = false;
			if (t.isDice()) {
				sb.append(t.count()).append('d').append(t.sides());
				switch (t.keep()) {
				case HIGHEST -> sb.append("kh").append(t.keepCount());
				case LOWEST -> sb.append("kl").append(t.keepCount());
				case DROP_LOWEST -> sb.append("dl").append(t.keepCount());
				case DROP_HIGHEST -> sb.append("dh").append(t.keepCount());
				default -> {
				}
				}
			} else {
				sb.append(t.constant());
			}
		}
		return sb.toString();
	}
}
