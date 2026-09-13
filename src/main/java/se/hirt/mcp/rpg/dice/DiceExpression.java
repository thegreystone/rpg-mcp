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

import se.hirt.mcp.rpg.protocol.RpgException;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parsed dice expression: a sum of terms such as {@code 2d6+1d4+3}, {@code d20-1}, {@code 4d6dl1}
 * (drop lowest), {@code 2d20kh1} (keep highest / advantage) or {@code 2d20kl1} (keep lowest /
 * disadvantage).
 */
public record DiceExpression(List<Term> terms) {

	/** One term: a dice group ({@code count > 0}) or a flat constant ({@code count == 0}). */
	public record Term(int sign, int count, int sides, Keep keep, int keepCount, int constant) {

		public boolean isDice() {
			return count > 0;
		}
	}

	public enum Keep {
		ALL, HIGHEST, LOWEST, DROP_LOWEST, DROP_HIGHEST
	}

	private static final Pattern TERM = Pattern.compile("^(?:(\\d*)d(\\d+)(?:(kh|kl|dl|dh)(\\d+))?|(\\d+))$");
	private static final int MAX_DICE = 1000;
	private static final int MAX_SIDES = 10_000;

	public static DiceExpression parse(String text) {
		if (text == null || text.isBlank()) {
			throw RpgException.invalidArgument("A dice expression is required.");
		}
		// Typeset text (the SRD PDF, a model's prose) writes "1d4–1" with an en dash or a minus sign; read them as '-'.
		String s = text.replace(" ", "").replace('–', '-').replace('—', '-').replace('−', '-').toLowerCase();
		var terms = new ArrayList<Term>();
		int i = 0;
		int sign = 1;
		if (s.charAt(0) == '+' || s.charAt(0) == '-') {
			sign = s.charAt(0) == '-' ? -1 : 1;
			i = 1;
		}
		while (i < s.length()) {
			int j = i;
			while (j < s.length() && s.charAt(j) != '+' && s.charAt(j) != '-') {
				j++;
			}
			terms.add(parseTerm(s.substring(i, j), sign, text));
			if (j < s.length()) {
				sign = s.charAt(j) == '-' ? -1 : 1;
			}
			i = j + 1;
		}
		if (terms.isEmpty()) {
			throw RpgException.invalidArgument("Empty dice expression '" + text + "'.");
		}
		return new DiceExpression(List.copyOf(terms));
	}

	private static Term parseTerm(String term, int sign, String whole) {
		Matcher m = TERM.matcher(term);
		if (!m.matches()) {
			throw RpgException.invalidArgument("Malformed dice expression '" + whole + "' near '" + term + "'.");
		}
		if (m.group(5) != null) {
			return new Term(sign, 0, 0, Keep.ALL, 0, Integer.parseInt(m.group(5)));
		}
		int count = m.group(1).isEmpty() ? 1 : Integer.parseInt(m.group(1));
		int sides = Integer.parseInt(m.group(2));
		if (count < 1 || count > MAX_DICE || sides < 1 || sides > MAX_SIDES) {
			throw RpgException.invalidArgument("Dice term '" + term + "' is out of range.");
		}
		Keep keep = Keep.ALL;
		int keepCount = count;
		if (m.group(3) != null) {
			int n = Integer.parseInt(m.group(4));
			keep = switch (m.group(3)) {
			case "kh" -> Keep.HIGHEST;
			case "kl" -> Keep.LOWEST;
			case "dl" -> Keep.DROP_LOWEST;
			default -> Keep.DROP_HIGHEST;
			};
			if (n < 0 || n > count) {
				throw RpgException.invalidArgument("Keep/drop count in '" + term + "' exceeds the number of dice.");
			}
			keepCount = n;
		}
		return new Term(sign, count, sides, keep, keepCount, 0);
	}

	public int constantModifier() {
		return terms.stream().filter(t -> !t.isDice()).mapToInt(t -> t.sign() * t.constant()).sum();
	}
}
