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
package se.hirt.mcp.rpg;

import se.hirt.mcp.rpg.protocol.RpgException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Validated, self-documenting argument parser for consolidated domain tools. Every error names the
 * tool, action, accepted parameter keys and (when relevant) the {@code get_instructions(topic)}
 * manual so AI clients recover in one turn without guessing.
 */
final class ToolArgs {

	private final String tool;
	private final String action;
	private final String helpTopic;
	private final Map<String, Object> params;
	private final String expectedParams;

	private ToolArgs(String tool, String action, String helpTopic, Map<String, Object> params, String expectedParams) {
		this.tool = tool;
		this.action = action;
		this.helpTopic = helpTopic;
		this.params = params == null ? Map.of() : params;
		this.expectedParams = expectedParams;
	}

	/** Normalizes and validates {@code action} against the legal actions for {@code tool}. */
	static String action(String tool, String raw, List<String> legalActions) {
		if (raw == null || raw.isBlank()) {
			throw RpgException.invalidArgument("Missing 'action' for " + tool + ". Legal actions: " + legalActions)
					.withDetail("tool", tool).withDetail("legal_actions", legalActions);
		}
		String normalized = raw.trim().toUpperCase(Locale.ROOT);
		if (!legalActions.contains(normalized)) {
			throw RpgException
					.invalidArgument("Unknown action '" + raw + "' for " + tool + ". Legal actions: " + legalActions)
					.withDetail("tool", tool).withDetail("legal_actions", legalActions);
		}
		return normalized;
	}

	/** Validates parameter keys for an action and returns a typed accessor. */
	static ToolArgs of(
		String tool, String action, String helpTopic, Map<String, Object> params, String expectedParams,
		String ... allowedKeys) {
		Map<String, Object> safe = params == null ? Map.of() : params;
		Set<String> allowed = Set.of(allowedKeys);
		var unknown = new ArrayList<String>();
		for (String key : safe.keySet()) {
			if (!allowed.contains(key)) {
				unknown.add(key);
			}
		}
		if (!unknown.isEmpty()) {
			var err = RpgException
					.invalidArgument("Unknown parameter(s) " + unknown + " for " + tool + ":" + action
							+ ". Expected params: " + expectedParams)
					.withDetail("tool", tool).withDetail("action", action)
					.withDetail("expected_params", expectedParams);
			if (helpTopic != null) {
				err.withDetail("help_topic", helpTopic);
			}
			throw err;
		}
		return new ToolArgs(tool, action, helpTopic, safe, expectedParams);
	}

	/** Requires a non-blank {@code campaign} argument. */
	static String reqCampaign(String tool, String action, String campaign) {
		if (campaign == null || campaign.isBlank()) {
			throw RpgException.invalidArgument("Missing required 'campaign' for " + tool + ":" + action)
					.withDetail("tool", tool).withDetail("action", action);
		}
		return campaign;
	}

	/** Requires a non-blank {@code operation_id} argument for mutating actions. */
	static String reqOp(String tool, String action, String operationId) {
		if (operationId == null || operationId.isBlank()) {
			throw RpgException
					.invalidArgument("Missing required 'operation_id' for mutating action " + tool + ":" + action)
					.withDetail("tool", tool).withDetail("action", action);
		}
		return operationId;
	}

	private RpgException bad(String message) {
		var err = RpgException
				.invalidArgument(message + " (" + tool + ":" + action + "; expected params: " + expectedParams + ")")
				.withDetail("tool", tool).withDetail("action", action).withDetail("expected_params", expectedParams);
		if (helpTopic != null) {
			err.withDetail("help_topic", helpTopic);
		}
		return err;
	}

	String reqStr(String key) {
		Object v = params.get(key);
		if (v == null) {
			throw bad("Missing required parameter '" + key + "'");
		}
		if (!(v instanceof String s)) {
			throw bad("Parameter '" + key + "' must be a string");
		}
		return s;
	}

	String optStr(String key) {
		Object v = params.get(key);
		if (v == null) {
			return null;
		}
		if (!(v instanceof String s)) {
			throw bad("Parameter '" + key + "' must be a string");
		}
		return s;
	}

	String optStr(String key, String fallback) {
		String s = optStr(key);
		return s == null ? fallback : s;
	}

	int reqInt(String key) {
		Integer v = optInt(key);
		if (v == null) {
			throw bad("Missing required integer parameter '" + key + "'");
		}
		return v;
	}

	Integer optInt(String key) {
		Object v = params.get(key);
		if (v == null) {
			return null;
		}
		if (v instanceof Number n) {
			double d = n.doubleValue();
			if (Double.isFinite(d) && d == Math.rint(d) && d >= Integer.MIN_VALUE && d <= Integer.MAX_VALUE) {
				return n.intValue();
			}
		}
		throw bad("Parameter '" + key + "' must be an integer");
	}

	int optInt(String key, int fallback) {
		Integer v = optInt(key);
		return v == null ? fallback : v;
	}

	long reqLong(String key) {
		Long v = optLong(key);
		if (v == null) {
			throw bad("Missing required integer parameter '" + key + "'");
		}
		return v;
	}

	Long optLong(String key) {
		Object v = params.get(key);
		if (v == null) {
			return null;
		}
		if (v instanceof Number n) {
			if (n instanceof Byte || n instanceof Short || n instanceof Integer || n instanceof Long) {
				return n.longValue();
			}
			double d = n.doubleValue();
			if (Double.isFinite(d) && d == Math.rint(d)) {
				return (long) d;
			}
		}
		throw bad("Parameter '" + key + "' must be an integer");
	}

	double reqDouble(String key) {
		Double v = optDouble(key);
		if (v == null) {
			throw bad("Missing required numeric parameter '" + key + "'");
		}
		return v;
	}

	Double optDouble(String key) {
		Object v = params.get(key);
		if (v == null) {
			return null;
		}
		if (v instanceof Number n && Double.isFinite(n.doubleValue())) {
			return n.doubleValue();
		}
		throw bad("Parameter '" + key + "' must be a number");
	}

	double optDouble(String key, double fallback) {
		Double v = optDouble(key);
		return v == null ? fallback : v;
	}

	boolean reqBool(String key) {
		Boolean v = optBool(key);
		if (v == null) {
			throw bad("Missing required boolean parameter '" + key + "'");
		}
		return v;
	}

	Boolean optBool(String key) {
		Object v = params.get(key);
		if (v == null) {
			return null;
		}
		if (v instanceof Boolean b) {
			return b;
		}
		throw bad("Parameter '" + key + "' must be a boolean");
	}

	boolean optBool(String key, boolean fallback) {
		Boolean v = optBool(key);
		return v == null ? fallback : v;
	}

	Object reqObj(String key) {
		Object v = params.get(key);
		if (v == null) {
			throw bad("Missing required parameter '" + key + "'");
		}
		return v;
	}

	Object optObj(String key) {
		return params.get(key);
	}

	@SuppressWarnings("unchecked")
	Map<String, Object> reqMap(String key) {
		Map<String, Object> v = optMap(key);
		if (v == null) {
			throw bad("Missing required object parameter '" + key + "'");
		}
		return v;
	}

	@SuppressWarnings("unchecked")
	Map<String, Object> optMap(String key) {
		Object v = params.get(key);
		if (v == null) {
			return null;
		}
		if (v instanceof Map<?, ?> m) {
			var copy = new LinkedHashMap<String, Object>();
			for (var e : m.entrySet()) {
				copy.put(String.valueOf(e.getKey()), e.getValue());
			}
			return copy;
		}
		throw bad("Parameter '" + key + "' must be a JSON object");
	}

	Map<String, Object> optMapOrEmpty(String key) {
		Map<String, Object> v = optMap(key);
		return v == null ? Map.of() : v;
	}

	List<Object> optList(String key) {
		Object v = params.get(key);
		if (v == null) {
			return null;
		}
		if (v instanceof List<?> list) {
			return new ArrayList<>(list);
		}
		throw bad("Parameter '" + key + "' must be a list");
	}

	List<String> optStringList(String key) {
		Object v = params.get(key);
		if (v == null) {
			return null;
		}
		if (v instanceof List<?> list) {
			var out = new ArrayList<String>(list.size());
			for (Object item : list) {
				if (!(item instanceof String s)) {
					throw bad("Parameter '" + key + "' must be a list of strings");
				}
				out.add(s);
			}
			return out;
		}
		throw bad("Parameter '" + key + "' must be a list of strings");
	}

	List<Map<String, Object>> reqMapList(String key) {
		List<Map<String, Object>> list = optMapList(key);
		if (list == null) {
			throw bad("Missing required list parameter '" + key + "'");
		}
		return list;
	}

	List<Map<String, Object>> optMapList(String key) {
		Object v = params.get(key);
		if (v == null) {
			return null;
		}
		if (v instanceof List<?> list) {
			var out = new ArrayList<Map<String, Object>>(list.size());
			for (Object item : list) {
				if (!(item instanceof Map<?, ?> m)) {
					throw bad("Parameter '" + key + "' must be a list of objects");
				}
				var copy = new LinkedHashMap<String, Object>();
				for (var e : m.entrySet()) {
					copy.put(String.valueOf(e.getKey()), e.getValue());
				}
				out.add(copy);
			}
			return out;
		}
		throw bad("Parameter '" + key + "' must be a list of objects");
	}

	Map<String, String> reqStringMap(String key) {
		Map<String, String> m = optStringMap(key);
		if (m == null) {
			throw bad("Missing required string-map parameter '" + key + "'");
		}
		return m;
	}

	Map<String, String> optStringMap(String key) {
		Object v = params.get(key);
		if (v == null) {
			return null;
		}
		if (v instanceof Map<?, ?> m) {
			var out = new LinkedHashMap<String, String>();
			for (var e : m.entrySet()) {
				if (!(e.getValue() instanceof String s)) {
					throw bad("Parameter '" + key + "' must map strings to strings");
				}
				out.put(String.valueOf(e.getKey()), s);
			}
			return out;
		}
		throw bad("Parameter '" + key + "' must be a JSON object mapping strings to strings");
	}

	Map<String, List<String>> reqStringListMap(String key) {
		Object v = params.get(key);
		if (v == null) {
			throw bad("Missing required object parameter '" + key + "'");
		}
		if (v instanceof Map<?, ?> m) {
			var out = new LinkedHashMap<String, List<String>>();
			for (var e : m.entrySet()) {
				if (!(e.getValue() instanceof List<?> list)) {
					throw bad("Parameter '" + key + "' must map side names to lists of character references");
				}
				var refs = new ArrayList<String>(list.size());
				for (Object item : list) {
					if (!(item instanceof String s)) {
						throw bad("Parameter '" + key + "' must map side names to lists of string references");
					}
					refs.add(s);
				}
				out.put(String.valueOf(e.getKey()), refs);
			}
			return out;
		}
		throw bad("Parameter '" + key + "' must be a JSON object");
	}
}
