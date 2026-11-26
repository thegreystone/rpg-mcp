/* Copyright (C) 2026 Marcus Hirt. SPDX-License-Identifier: BSD-3-Clause */
package se.hirt.mcp.rpg;

import se.hirt.mcp.rpg.harness.ToolOperations;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Translates persisted pre-0.2 tool hints at delivery time, leaving canonical history untouched.
 */
final class ProtocolHints {

	private static final Set<String> LABEL_FIELDS = Set.of("allowed_operations", "legal_actions", "resolve_with",
			"annotation_tool", "supported_alternative");

	private ProtocolHints() {
	}

	@SuppressWarnings("unchecked")
	static Map<String, Object> publicView(Map<String, Object> value) {
		return (Map<String, Object>) visit(value, "", false);
	}

	private static Object visit(Object value, String context, boolean labels) {
		if (value instanceof String text)
			return labels ? ToolOperations.publicName(text) : text;
		if (value instanceof List<?> list) {
			var copy = new ArrayList<Object>(list.size());
			for (Object item : list)
				copy.add(visit(item, context, labels));
			return copy;
		}
		if (!(value instanceof Map<?, ?> map))
			return value;
		var copy = new LinkedHashMap<String, Object>();
		for (var entry : map.entrySet()) {
			String key = String.valueOf(entry.getKey());
			boolean operationLabels = LABEL_FIELDS.contains(key) || "options".equals(key)
					&& ("continuation".equals(context) || map.containsKey("continuation_policy"));
			copy.put(key, visit(entry.getValue(), key, operationLabels));
		}
		if (map.get("tool") instanceof String legacy) {
			String operation = ToolOperations.publicName(legacy);
			int colon = operation.indexOf(':');
			if (!operation.equals(legacy) && colon > 0) {
				String tool = operation.substring(0, colon), action = operation.substring(colon + 1);
				copy.put("tool", tool);
				if (copy.get("arguments") instanceof Map<?, ?> arguments) {
					var call = new LinkedHashMap<String, Object>();
					var params = new LinkedHashMap<String, Object>();
					call.put("action", action);
					for (var argument : arguments.entrySet()) {
						String key = String.valueOf(argument.getKey());
						if (key.equals("campaign") || key.equals("operation_id"))
							call.put(key, argument.getValue());
						else
							params.put(key, argument.getValue());
					}
					if (!params.isEmpty())
						call.put("params", params);
					copy.put("arguments", call);
				} else {
					copy.put("action", action);
					if (copy.get("path") instanceof String path && !path.startsWith("params.")
							&& !Set.of("campaign", "operation_id").contains(path))
						copy.put("path", "params." + path);
				}
			}
		}
		// Continuation options use 'operation'; ordinary journal/audit rows keep their internal identity.
		if (map.get("action") instanceof String action
				&& Set.of("RESTORE_CHECKPOINT", "CONTINUE_WITH_SURVIVOR", "COMPLETE_CAMPAIGN").contains(action)
				&& map.get("operation") instanceof String operation)
			copy.put("operation", ToolOperations.publicName(operation));
		return copy;
	}
}
