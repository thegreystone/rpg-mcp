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

import se.hirt.mcp.rpg.protocol.RpgException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.UnaryOperator;

/**
 * The one merge rule for the narrative aggregates (relationship profiles, biographies, intimate
 * profiles): nothing already stored is lost by adding to it. At every depth a list appends without
 * duplicates, a map merges key by key, a null removes the key it sits under, and a value whose
 * shape differs from the stored one joins it instead of replacing it: a map given for a stored list
 * contributes its values, a plain value given for a stored list is appended, and a plain value
 * given for a stored map is refused with INVALID_ARGUMENT so that a whole object is never flattened
 * by a slip. Only an explicit REPLACE starts over.
 */
public final class DeepMerge {

	/** Items are compared as they are; nothing is treated as an updatable entry. */
	public static final Function<Object, String> NO_KEY = o -> null;

	private DeepMerge() {
	}

	/**
	 * Merges {@code given} into {@code old} and returns the result; {@code old} is never modified.
	 * {@code normalizeItem} is applied to every item that joins a list at this level (a milestone
	 * gets its date, a state its start); deeper levels take items as they are. {@code keyOf} names
	 * the identity of a list item (a biography entry's note) so that a new item with the same
	 * identity replaces the old one instead of sitting beside it; {@link #NO_KEY} compares items
	 * whole. {@code path} names the value in error messages.
	 */
	public static Object merge(
		Object old, Object given, String path, UnaryOperator<Object> normalizeItem, Function<Object, String> keyOf) {
		if (given == null) {
			return null;
		}
		if (given instanceof List<?> list) {
			var merged = new ArrayList<Object>();
			if (old instanceof List<?> oldList) {
				merged.addAll(oldList);
			} else if (old instanceof Map<?, ?> oldMap) {
				oldMap.values().forEach(v -> add(merged, v, keyOf));
			} else if (old != null) {
				merged.add(old);
			}
			for (Object item : list) {
				add(merged, normalizeItem.apply(item), keyOf);
			}
			return merged;
		}
		if (given instanceof Map<?, ?> map) {
			if (old instanceof Map<?, ?> oldMap) {
				var merged = copy(oldMap);
				map.forEach((k, v) -> {
					String key = String.valueOf(k);
					if (v == null) {
						merged.remove(key);
					} else {
						merged.put(key, merge(merged.get(key), v, path + "." + key, UnaryOperator.identity(), keyOf));
					}
				});
				return merged;
			}
			if (old instanceof List<?> oldList) {
				var merged = new ArrayList<Object>(oldList);
				map.values().forEach(v -> {
					if (v instanceof List<?> l) {
						l.forEach(item -> add(merged, normalizeItem.apply(item), keyOf));
					} else if (v != null) {
						add(merged, normalizeItem.apply(v), keyOf);
					}
				});
				return merged;
			}
			return copy(map);
		}
		if (old instanceof List<?> oldList) {
			var merged = new ArrayList<Object>(oldList);
			add(merged, normalizeItem.apply(given), keyOf);
			return merged;
		}
		if (old instanceof Map<?, ?> oldMap) {
			throw RpgException.invalidArgument("'" + path + "' holds a map with keys " + oldMap.keySet()
					+ "; give a map to merge into it, or null to remove it, not a plain value.");
		}
		return given;
	}

	private static void add(List<Object> into, Object item, Function<Object, String> keyOf) {
		if (item == null) {
			return;
		}
		String key = keyOf.apply(item);
		if (key != null) {
			into.removeIf(o -> key.equals(keyOf.apply(o)));
			into.add(item);
		} else if (!into.contains(item)) {
			into.add(item);
		}
	}

	private static Map<String, Object> copy(Map<?, ?> m) {
		var out = new LinkedHashMap<String, Object>();
		m.forEach((k, v) -> out.put(String.valueOf(k), v));
		return out;
	}
}
