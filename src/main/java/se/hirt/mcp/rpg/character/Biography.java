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

import se.hirt.mcp.rpg.persistence.Row;
import se.hirt.mcp.rpg.persistence.Tx;
import se.hirt.mcp.rpg.protocol.RpgException;
import se.hirt.mcp.rpg.session.GameTime;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The two slow-changing narrative aggregates a character carries besides the identity columns (DOMAIN_MODEL.md §5.1,
 * MCP_PROTOCOL.md §12.7): the <b>biography</b> (dated life events, verbatim lines, long-running bodily state, permanent
 * marks, and the <b>wants</b> that drive the person: dated, closable, shown open in every party list) and, under a
 * PEGI_18 content profile only, the <b>intimate profile</b> (the character's own body, likes, dislikes, limits, hard
 * lines, the wants that concern intimacy, the standing terms of their household, and how they talk in bed). Both are JSON maps of lists merged the way relationship profiles are: lists append without duplicates, an
 * entry with the same {@code note} replaces the older one (so a want can be marked done and a state given an end), a
 * null removes a key, {@code replace: true} starts over.
 */
public final class Biography {

	/** Keys of {@code biography_json}; {@code wants} are the character's drives: what they are working toward. */
	public static final Set<String> BIOGRAPHY_KEYS = Set.of("timeline", "voice", "state", "marks", "wants");

	/** Keys of {@code intimacy_json}; its {@code wants} are the drives that concern intimacy. */
	public static final Set<String> INTIMACY_KEYS = Set.of("body", "likes", "dislikes", "limits", "hard_lines",
			"wants", "household_terms", "voice_in_bed");

	/** Entries of these lists are maps stamped with the game time when written. */
	private static final Map<String, String> STAMPED = Map.of("timeline", "game_time", "state", "since", "wants",
			"since", "household_terms", "since");

	/** A want is OPEN until it is DONE or ABANDONED. */
	public static final Set<String> WANT_STATUS = Set.of("OPEN", "DONE", "ABANDONED");

	private Biography() {
	}

	public static Map<String, Object> biography(Row c) {
		return c == null || c.isNull("biography_json") ? new LinkedHashMap<>()
				: new LinkedHashMap<>(c.map("biography_json"));
	}

	public static Map<String, Object> intimacy(Row c) {
		return c == null || c.isNull("intimacy_json") ? new LinkedHashMap<>()
				: new LinkedHashMap<>(c.map("intimacy_json"));
	}

	/** True when the campaign's content profile admits intimate detail (PEGI_18). */
	public static boolean intimacyAllowed(Tx tx, long campaignId) {
		return tx.queryOne("SELECT content_profile FROM policy_state WHERE campaign_id = ?", campaignId)
				.map(r -> "PEGI_18".equals(r.str("content_profile"))).orElse(false);
	}

	/**
	 * Merges {@code given} into {@code current} under the allowed keys. Lists append without duplicates; an entry that
	 * is a map with a {@code note} replaces an existing entry with the same note; entries of dated lists are stamped
	 * with the current game time when the caller gave none; a null value removes the key; {@code replace: true} starts
	 * from an empty aggregate.
	 */
	@SuppressWarnings("unchecked")
	public static Map<String, Object> merge(
			Tx tx, long campaignId, Map<String, Object> current, Map<String, Object> given, Set<String> allowed,
			String what) {
		if (given == null) {
			return current;
		}
		boolean replace = Boolean.TRUE.equals(given.get("replace"));
		var out = replace ? new LinkedHashMap<String, Object>() : new LinkedHashMap<>(current);
		for (var e : given.entrySet()) {
			String key = e.getKey().trim().toLowerCase();
			if ("replace".equals(key)) {
				continue;
			}
			if (!allowed.contains(key)) {
				throw RpgException.invalidArgument(
						"'" + key + "' is not a " + what + " key; known: " + allowed.stream().sorted().toList() + ".");
			}
			Object v = e.getValue();
			if (v == null) {
				out.remove(key);
			} else if (v instanceof List<?> list) {
				var merged = new ArrayList<Object>();
				if (out.get(key) instanceof List<?> old) {
					merged.addAll(old);
				}
				for (Object item : list) {
					Object norm = normalize(tx, campaignId, key, item);
					String note = noteOf(norm);
					if (note != null) {
						merged.removeIf(o -> note.equals(noteOf(o)));
						merged.add(norm);
					} else if (!merged.contains(norm)) {
						merged.add(norm);
					}
				}
				out.put(key, merged);
			} else if (v instanceof Map<?, ?> map) {
				out.put(key, normalize(tx, campaignId, key, map));
			} else {
				out.put(key, v.toString().trim());
			}
		}
		return out;
	}

	/** A dated list's entry becomes a map stamped with the game time; a bare string is its {@code note}. */
	private static Object normalize(Tx tx, long campaignId, String key, Object item) {
		String stamp = STAMPED.get(key);
		if (stamp == null) {
			return item instanceof Map<?, ?> m ? copy(m) : item.toString().trim();
		}
		var out = new LinkedHashMap<String, Object>();
		if (item instanceof Map<?, ?> m) {
			out.putAll(copy(m));
		} else {
			out.put("note", item.toString().trim());
		}
		if (out.get("note") == null || out.get("note").toString().isBlank()) {
			throw RpgException.invalidArgument("Every entry of '" + key + "' needs a note.");
		}
		if (!out.containsKey(stamp)) {
			out.put(stamp, GameTime.render(GameTime.currentSeq(tx, campaignId)));
		}
		if ("wants".equals(key)) {
			String status = out.get("status") == null ? "OPEN" : out.get("status").toString().trim().toUpperCase();
			if (!WANT_STATUS.contains(status)) {
				throw RpgException.invalidArgument("A want's status is OPEN, DONE or ABANDONED.");
			}
			out.put("status", status);
		}
		return out;
	}

	private static Map<String, Object> copy(Map<?, ?> m) {
		var out = new LinkedHashMap<String, Object>();
		m.forEach((k, v) -> out.put(String.valueOf(k), v));
		return out;
	}

	private static String noteOf(Object o) {
		if (o instanceof Map<?, ?> m && m.get("note") != null) {
			return m.get("note").toString().trim().toLowerCase();
		}
		return null;
	}

	/** State entries that have not ended: no {@code until} written yet. */
	@SuppressWarnings("unchecked")
	public static List<Map<String, Object>> currentState(Row c) {
		var out = new ArrayList<Map<String, Object>>();
		if (biography(c).get("state") instanceof List<?> list) {
			for (Object o : list) {
				if (o instanceof Map<?, ?> m && m.get("until") == null) {
					out.add((Map<String, Object>) m);
				}
			}
		}
		return out;
	}

	/** The character's open drives (biography wants that are neither DONE nor ABANDONED). */
	public static List<Map<String, Object>> openWants(Row c) {
		return open(biography(c).get("wants"));
	}

	/** The open drives that concern intimacy (PEGI_18: the intimate profile's wants). */
	public static List<Map<String, Object>> openIntimateWants(Row c) {
		return open(intimacy(c).get("wants"));
	}

	@SuppressWarnings("unchecked")
	private static List<Map<String, Object>> open(Object wants) {
		var out = new ArrayList<Map<String, Object>>();
		if (wants instanceof List<?> list) {
			for (Object o : list) {
				if (o instanceof Map<?, ?> m && (m.get("status") == null || "OPEN".equalsIgnoreCase(
						String.valueOf(m.get("status"))))) {
					out.add((Map<String, Object>) m);
				}
			}
		}
		return out;
	}

	/** The first sentence of an appearance, capped, for party lists. */
	public static String brief(String text) {
		if (text == null || text.isBlank()) {
			return null;
		}
		String t = text.trim();
		int end = t.indexOf(". ");
		if (end > 0 && end < 160) {
			t = t.substring(0, end + 1);
		}
		return t.length() <= 160 ? t : t.substring(0, 157) + "...";
	}

	/**
	 * The one-line person behind the numbers, added to party lists at every detail level: age, presentation, a brief
	 * appearance, the state entries that are still running (carrying a child, an arm in a sling) and the open wants,
	 * so an NPC is played toward the same things in every scene.
	 */
	public static void appendBrief(Row c, Map<String, Object> m) {
		if (!c.isNull("age")) {
			m.put("age", c.integer("age"));
		}
		if (!c.isNull("presentation")) {
			m.put("presentation", c.str("presentation"));
		}
		String brief = brief(c.str("appearance"));
		if (brief != null) {
			m.put("appearance_brief", brief);
		}
		List<Map<String, Object>> state = currentState(c);
		if (!state.isEmpty()) {
			m.put("state", state.stream().map(s -> s.get("note")).toList());
		}
		List<Map<String, Object>> wants = openWants(c);
		if (!wants.isEmpty()) {
			m.put("wants", wants.stream().map(w -> w.get("note")).toList());
		}
	}
}
