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
package se.hirt.mcp.rpg.tools;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Small helpers that keep the verifier close to the Python original it was ported from: Python-style value
 * formatting for the report, lenient JSON access, and Unicode-aware regex compilation.
 */
final class Py {
	private Py() {
	}

	/** Compile with Python's Unicode semantics for {@code \w}, {@code \d}, {@code \s} and {@code \b}. */
	static Pattern re(String regex) {
		return Pattern.compile(regex, Pattern.UNICODE_CHARACTER_CLASS);
	}

	/** Text of a JSON node, or null when missing/null. Non-text nodes render as JSON. */
	static String str(JsonNode n) {
		if (n == null || n.isMissingNode() || n.isNull()) {
			return null;
		}
		return n.isTextual() ? n.asText() : n.toString();
	}

	/** Numeric value, or null when the node is not a number. */
	static Double num(JsonNode n) {
		return n != null && n.isNumber() ? n.doubleValue() : null;
	}

	/** Integer value, or null when the node is not a number. */
	static Integer integer(JsonNode n) {
		return n != null && n.isNumber() ? n.intValue() : null;
	}

	/** Python truthiness: absent, null, false, 0, "" and empty containers are false. */
	static boolean truthy(JsonNode n) {
		if (n == null || n.isMissingNode() || n.isNull()) {
			return false;
		}
		if (n.isBoolean()) {
			return n.booleanValue();
		}
		if (n.isNumber()) {
			return n.doubleValue() != 0;
		}
		if (n.isTextual()) {
			return !n.asText().isEmpty();
		}
		return !n.isEmpty();
	}

	static List<String> strings(JsonNode array) {
		List<String> out = new ArrayList<>();
		if (array != null && array.isArray()) {
			array.forEach(x -> out.add(str(x)));
		}
		return out;
	}

	static List<Integer> ints(JsonNode array) {
		List<Integer> out = new ArrayList<>();
		if (array != null && array.isArray()) {
			array.forEach(x -> out.add(integer(x)));
		}
		return out;
	}

	static boolean neq(Object a, Object b) {
		return !Objects.equals(a, b);
	}

	/** Python's {@code s.rstrip(chars)}. */
	static String rstrip(String s, char c) {
		int end = s.length();
		while (end > 0 && s.charAt(end - 1) == c) {
			end--;
		}
		return s.substring(0, end);
	}

	/** Python's {@code s.strip(chars)}. */
	static String strip(String s, char c) {
		int start = 0;
		int end = s.length();
		while (start < end && s.charAt(start) == c) {
			start++;
		}
		while (end > start && s.charAt(end - 1) == c) {
			end--;
		}
		return s.substring(start, end);
	}

	/** Python's {@code s[:n]}. */
	static String head(String s, int n) {
		return s.length() <= n ? s : s.substring(0, n);
	}

	static boolean isDigits(String s) {
		return !s.isEmpty() && s.chars().allMatch(Character::isDigit);
	}

	/** Python's {@code repr()} for the report: quoted strings, None/True/False, lists and dicts. */
	static String repr(Object o) {
		switch (o) {
		case null -> {
			return "None";
		}
		case String s -> {
			return s.indexOf('\'') >= 0 && s.indexOf('"') < 0 ? "\"" + s + "\"" : "'" + s.replace("'", "\\'") + "'";
		}
		case Boolean b -> {
			return b ? "True" : "False";
		}
		case Double d -> {
			return d == Math.rint(d) && !Double.isInfinite(d) ? String.valueOf(d.longValue()) : String.valueOf(d);
		}
		case Number n -> {
			return String.valueOf(n);
		}
		case JsonNode n -> {
			return reprNode(n);
		}
		case Map<?, ?> m -> {
			return m.entrySet().stream().map(e -> repr(e.getKey()) + ": " + repr(e.getValue()))
					.collect(Collectors.joining(", ", "{", "}"));
		}
		case Collection<?> c -> {
			return c.stream().map(Py::repr).collect(Collectors.joining(", ", "[", "]"));
		}
		default -> {
			return String.valueOf(o);
		}
		}
	}

	private static String reprNode(JsonNode n) {
		if (n.isMissingNode() || n.isNull()) {
			return "None";
		}
		if (n.isTextual()) {
			return repr(n.asText());
		}
		if (n.isBoolean()) {
			return repr(n.booleanValue());
		}
		if (n.isNumber()) {
			return n.toString();
		}
		if (n.isArray()) {
			List<String> parts = new ArrayList<>();
			n.forEach(x -> parts.add(reprNode(x)));
			return "[" + String.join(", ", parts) + "]";
		}
		List<String> parts = new ArrayList<>();
		for (Iterator<Map.Entry<String, JsonNode>> it = n.fields(); it.hasNext();) {
			Map.Entry<String, JsonNode> e = it.next();
			parts.add(repr(e.getKey()) + ": " + reprNode(e.getValue()));
		}
		return "{" + String.join(", ", parts) + "}";
	}
}
