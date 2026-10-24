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
import se.hirt.mcp.rpg.protocol.Ref;
import se.hirt.mcp.rpg.protocol.RpgException;
import se.hirt.mcp.rpg.session.GameTime;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;

/**
 * The two slow-changing narrative aggregates a character carries besides the identity columns
 * (DOMAIN_MODEL.md §5.1, MCP_PROTOCOL.md §12.7): the <b>biography</b> (dated life events, verbatim
 * lines, long-running bodily state, permanent marks, and the <b>wants</b> that drive the person:
 * dated, closable, shown open in every party list) and, under a PEGI_18 content profile only, the
 * <b>intimate profile</b> (the character's own body, likes, dislikes, limits, hard lines, the wants
 * that concern intimacy, the standing terms of their household, and how they talk in bed). Both are
 * JSON maps of lists merged the way relationship profiles are: lists append without duplicates, an
 * entry with the same {@code note} updates the older one, a null removes a key,
 * {@code replace: true} starts over. Wants additionally have stable references and merge partial
 * updates by reference, so their wording can change without losing their identity or history.
 */
public final class Biography {

	/**
	 * Keys of {@code biography_json}; {@code wants} are the character's drives: what they are
	 * working toward.
	 */
	public static final Set<String> BIOGRAPHY_KEYS = Set.of("timeline", "voice", "state", "marks", "wants");

	/** Keys of {@code intimacy_json}; its {@code wants} are the drives that concern intimacy. */
	public static final Set<String> INTIMACY_KEYS = Set.of("body", "likes", "dislikes", "limits", "hard_lines", "wants",
			"household_terms", "voice_in_bed");

	/** Entries of these lists are maps stamped with the game time when written. */
	private static final Map<String, String> STAMPED = Map.of("timeline", "game_time", "state", "since", "wants",
			"since", "household_terms", "since");

	/** A want is OPEN until it is DONE or ABANDONED. */
	public static final Set<String> WANT_STATUS = Set.of("OPEN", "DONE", "ABANDONED");
	private static final String ENGINE = "_engine";
	private static final String CLOSED_JOURNAL_ID = "_closed_journal_id";
	private static final String ENGINE_CLOSED_JOURNAL_ID = "closed_journal_id";

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

	/** Narrative output carries usable want references, without engine closure bookkeeping. */
	public static Map<String, Object> publicBiography(Tx tx, Row c) {
		return publicProfile(tx, c, false);
	}

	/** The caller is responsible for admitting intimate detail under the campaign policy. */
	public static Map<String, Object> publicIntimacy(Tx tx, Row c) {
		return publicProfile(tx, c, true);
	}

	private static Map<String, Object> publicProfile(Tx tx, Row c, boolean intimate) {
		Map<String, Object> out = intimate ? intimacy(c) : biography(c);
		if (out.get("wants") instanceof List<?>) {
			out.put("wants", wants(tx, c, intimate));
		}
		return out;
	}

	/** All current wants, including closed ones, with stable public references where available. */
	public static List<Map<String, Object>> wants(Tx tx, Row c, boolean intimate) {
		var out = new ArrayList<Map<String, Object>>();
		Object value = (intimate ? intimacy(c) : biography(c)).get("wants");
		if (value instanceof List<?> list) {
			for (Object entry : list) {
				Map<String, Object> want = wantMap(entry);
				String ref = wantRef(tx, c.id(), intimate ? "intimate" : "general", entry);
				want.remove("ref");
				if (ref != null) {
					want.put("ref", ref);
				}
				want.remove(ENGINE);
				want.remove(CLOSED_JOURNAL_ID);
				out.add(want);
			}
		}
		return out;
	}

	/** True when the campaign's content profile admits intimate detail (PEGI_18). */
	public static boolean intimacyAllowed(Tx tx, long campaignId) {
		return tx.queryOne("SELECT content_profile FROM policy_state WHERE campaign_id = ?", campaignId)
				.map(r -> "PEGI_18".equals(r.str("content_profile"))).orElse(false);
	}

	/**
	 * Merges {@code given} into {@code current} under the allowed keys. Lists append without
	 * duplicates at every depth; an entry that is a map with a {@code note} replaces an existing
	 * entry with the same note. Wants instead merge partial updates by ref, falling back to a note
	 * match for older clients. Entries of dated lists are stamped with the current game time when
	 * the caller gave none; maps merge key by key; a null value removes the key it sits under; null
	 * list entries are rejected with their indexed path; {@code replace: true} starts from an empty
	 * aggregate. Nothing stored is dropped by a value of another shape ({@link DeepMerge}).
	 */
	public static Map<String, Object> merge(
		Tx tx, long campaignId, long characterId, Map<String, Object> current, Map<String, Object> given,
		Set<String> allowed, String what) {
		if (given == null) {
			return current;
		}
		requireNonNullListEntries(given, what);
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
				continue;
			}
			if ("wants".equals(key)) {
				out.put(key, mergeWants(tx, campaignId, characterId, what.equals("intimacy") ? "intimate" : "general",
						current.get(key), out.get(key), v));
				continue;
			}
			// A single entry given for a dated list is that list's one new entry.
			if (v instanceof Map<?, ?> && STAMPED.containsKey(key) && !(out.get(key) instanceof Map<?, ?>)) {
				v = List.of(v);
			}
			UnaryOperator<Object> normalize = item -> normalize(tx, campaignId, key, item);
			Object merged = DeepMerge.merge(out.get(key), v, key, normalize, Biography::noteOf);
			out.put(key, merged instanceof String s ? s.trim() : merged);
		}
		return out;
	}

	/** Null removes a map field; a list entry must contain a value, including in nested lists. */
	private static void requireNonNullListEntries(Object value, String path) {
		if (value instanceof List<?> list) {
			for (int i = 0; i < list.size(); i++) {
				String entryPath = path + "[" + i + "]";
				Object entry = list.get(i);
				if (entry == null) {
					throw RpgException
							.invalidArgument(
									"'" + entryPath + "' must not be null; use null as a map value to remove a field.")
							.withDetail("path", entryPath);
				}
				requireNonNullListEntries(entry, entryPath);
			}
		} else if (value instanceof Map<?, ?> map) {
			map.forEach((key, entry) -> requireNonNullListEntries(entry, path + "." + key));
		}
	}

	/** Wants merge by stable ref, with the original case-insensitive note match as a fallback. */
	private static List<Object> mergeWants(
		Tx tx, long campaignId, long characterId, String kind, Object original, Object old, Object given) {
		var out = new ArrayList<Object>();
		if (old instanceof List<?> list) {
			out.addAll(list);
		} else if (old != null) {
			out.add(old);
		}
		List<?> entries = given instanceof List<?> list ? list : List.of(given);
		for (Object entry : entries) {
			if (entry == null) {
				continue;
			}
			Map<String, Object> patch = wantMap(entry);
			Object previous = null;
			String ref = patch.get("ref") == null ? null : Ref.parse(patch.get("ref").toString(), "want").toString();
			if (ref != null) {
				previous = findWant(tx, characterId, kind, out, ref, null);
				if (previous == null && original instanceof List<?> list) {
					previous = findWant(tx, characterId, kind, list, ref, null);
				}
				if (previous == null) {
					throw RpgException.invalidArgument(
							"Want '" + ref + "' does not belong to this character's " + kind + " wants.");
				}
			} else {
				String note = noteOf(patch);
				if (note != null) {
					previous = findWant(tx, characterId, kind, out, null, note);
					if (previous == null && original instanceof List<?> list) {
						previous = findWant(tx, characterId, kind, list, null, note);
					}
				}
			}
			Map<String, Object> want = previous == null ? new LinkedHashMap<>() : wantMap(previous);
			for (var field : patch.entrySet()) {
				if (ENGINE.equals(field.getKey()) || CLOSED_JOURNAL_ID.equals(field.getKey())
						|| "ref".equals(field.getKey())) {
					continue;
				}
				if (field.getValue() == null) {
					want.remove(field.getKey());
				} else {
					want.put(field.getKey(), field.getValue());
				}
			}
			if (want.get("note") == null || want.get("note").toString().isBlank()) {
				throw RpgException.invalidArgument("Every entry of 'wants' needs a note, or an existing want ref.");
			}
			want.put("note", want.get("note").toString().trim());
			String status = want.get("status") == null ? "OPEN"
					: want.get("status").toString().trim().toUpperCase(Locale.ROOT);
			if (!WANT_STATUS.contains(status)) {
				throw RpgException.invalidArgument("A want's status is OPEN, DONE or ABANDONED.");
			}
			want.put("status", status);
			String now = GameTime.render(GameTime.currentSeq(tx, campaignId));
			if (want.get("since") == null) {
				want.put("since", now);
			}
			if (!isClosed(want) && isClosed(previous)) {
				want.remove("done_at");
				if (!patch.containsKey("resolved_by")) {
					want.remove("resolved_by");
				}
				if (!patch.containsKey("done_note")) {
					want.remove("done_note");
				}
			}
			validateResolution(tx, campaignId, want);
			if (ref == null && previous != null) {
				ref = wantRef(tx, characterId, kind, previous);
			}
			if (ref == null) {
				var columns = new LinkedHashMap<String, Object>();
				columns.put("campaign_id", campaignId);
				columns.put("character_id", characterId);
				columns.put("kind", kind);
				if (previous != null) {
					columns.put("legacy_note", wantMap(previous).get("note"));
				}
				ref = Ref.of("want", tx.insert("want_identity", columns));
			}
			want.put("ref", ref);
			want.remove(CLOSED_JOURNAL_ID);
			var engine = new LinkedHashMap<String, Object>();
			engine.put("ref", ref);
			if (isClosed(want)) {
				if (isClosed(previous)) {
					Number cursor = closureCursor(previous);
					if (cursor != null) {
						engine.put(ENGINE_CLOSED_JOURNAL_ID, cursor);
					}
				} else {
					engine.put(ENGINE_CLOSED_JOURNAL_ID, tx.journalId());
				}
				stampClosed(want, now);
			}
			want.put(ENGINE, engine);
			Object matched = previous;
			String identity = ref;
			out.removeIf(o -> o == matched || identity.equals(wantRef(tx, characterId, kind, o)));
			out.add(want);
		}
		return out;
	}

	private static Object findWant(Tx tx, long characterId, String kind, List<?> wants, String ref, String note) {
		Object found = null;
		for (Object want : wants) {
			if (ref != null ? ref.equals(wantRef(tx, characterId, kind, want)) : note.equals(noteOf(wantMap(want)))) {
				if (found != null && ref == null) {
					throw RpgException
							.invalidArgument("Several wants have that note; update the intended want by its ref.");
				}
				found = want;
			}
		}
		return found;
	}

	private static Map<String, Object> wantMap(Object value) {
		return value instanceof Map<?, ?> map ? copy(map)
				: new LinkedHashMap<>(Map.of("note", value.toString().trim()));
	}

	private static String wantRef(Tx tx, long characterId, String kind, Object value) {
		if (value instanceof Map<?, ?> map && map.get(ENGINE) instanceof Map<?, ?> engine
				&& engine.get("ref") != null) {
			Ref candidate = null;
			try {
				candidate = Ref.parse(engine.get("ref").toString(), "want");
			} catch (RpgException legacyField) {
				// Only engine-managed references participate in identity; legacy notes use the registry.
			}
			if (candidate != null && tx.queryOne(
					"SELECT w.id FROM want_identity w JOIN character c ON c.id = w.character_id WHERE w.id = ? AND w.character_id = ? AND w.kind = ? AND w.campaign_id = c.campaign_id",
					candidate.id(), characterId, kind).isPresent()) {
				return candidate.toString();
			}
		}
		Object note = wantMap(value).get("note");
		if (note != null) {
			for (Row identity : tx.query(
					"SELECT w.id, w.legacy_note FROM want_identity w JOIN character c ON c.id = w.character_id WHERE w.character_id = ? AND w.kind = ? AND w.campaign_id = c.campaign_id AND w.legacy_note IS NOT NULL ORDER BY w.id",
					characterId, kind)) {
				if (identity.str("legacy_note").trim().equalsIgnoreCase(note.toString().trim())) {
					return Ref.of("want", identity.id());
				}
			}
		}
		return null;
	}

	/** Reads current metadata and the older flat cursor without rewriting stored legacy entries. */
	private static Number closureCursor(Object value) {
		if (value instanceof Map<?, ?> want) {
			if (want.get(ENGINE) instanceof Map<?, ?> engine
					&& engine.get(ENGINE_CLOSED_JOURNAL_ID) instanceof Number cursor) {
				return cursor;
			}
			if (want.get(CLOSED_JOURNAL_ID) instanceof Number cursor) {
				return cursor;
			}
		}
		return null;
	}

	private static void validateResolution(Tx tx, long campaignId, Map<String, Object> want) {
		if (want.get("resolved_by") == null) {
			return;
		}
		Ref event = Ref.parse(want.get("resolved_by").toString(), Ref.EVENT);
		Row row = tx.find("event", event.id()).orElseThrow(() -> RpgException
				.invalidArgument("A want's resolved_by must reference an existing event in this campaign."));
		if (row.lng("campaign_id") == null || row.lng("campaign_id") != campaignId) {
			throw RpgException.invalidArgument("A want's resolved_by event must belong to this campaign.");
		}
		want.put("resolved_by", event.toString());
	}

	/**
	 * A dated list's entry becomes a map stamped with the game time; a bare string is its
	 * {@code note}.
	 */
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
		return out;
	}

	/**
	 * A want that is DONE or ABANDONED carries the game time it closed in {@code done_at}; the
	 * clock stamps it when the caller gave none, so the history of fulfilled wants keeps its dates
	 * without anyone remembering to write them.
	 */
	public static void stampClosed(Map<String, Object> want, String now) {
		if (isClosed(want) && (want.get("done_at") == null || want.get("done_at").toString().isBlank())) {
			want.put("done_at", now);
		}
	}

	/** True for a want entry whose status is DONE or ABANDONED. */
	public static boolean isClosed(Object want) {
		return want instanceof Map<?, ?> m && m.get("status") != null
				&& !"OPEN".equalsIgnoreCase(String.valueOf(m.get("status")).trim());
	}

	/**
	 * True when the given aggregate changes close at least one want: a biography or intimacy update
	 * whose {@code wants} carry an entry with status DONE or ABANDONED. Used to recommend a
	 * Director review, since a person whose want has just been settled needs a next one.
	 */
	public static boolean closesAWant(Map<String, Object> given) {
		if (given == null) {
			return false;
		}
		for (var e : given.entrySet()) {
			if (!"wants".equalsIgnoreCase(e.getKey().trim())) {
				continue;
			}
			Object v = e.getValue();
			if (v instanceof List<?> list) {
				for (Object o : list) {
					if (isClosed(o)) {
						return true;
					}
				}
			} else if (isClosed(v)) {
				return true;
			}
		}
		return false;
	}

	private static Map<String, Object> copy(Map<?, ?> m) {
		var out = new LinkedHashMap<String, Object>();
		m.forEach((k, v) -> out.put(String.valueOf(k), v));
		return out;
	}

	private static String noteOf(Object o) {
		if (o instanceof Map<?, ?> m && m.get("note") != null) {
			return m.get("note").toString().trim().toLowerCase(Locale.ROOT);
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
	public static List<Map<String, Object>> openWants(Tx tx, Row c) {
		return open(wants(tx, c, false));
	}

	/** The open drives that concern intimacy (PEGI_18: the intimate profile's wants). */
	public static List<Map<String, Object>> openIntimateWants(Tx tx, Row c) {
		return open(wants(tx, c, true));
	}

	/**
	 * The character's closed drives (biography and, when allowed, intimate wants that are DONE or
	 * ABANDONED) recorded after {@code sinceJournalId}. Legacy wants without a closure cursor use
	 * {@code done_at} after {@code sinceSeq}; with no review, every closed want is included. Each
	 * entry is tagged {@code kind: general|intimate}.
	 */
	public static List<Map<String, Object>> closedWantsSince(
		Tx tx, Row c, Long sinceJournalId, Long sinceSeq, boolean includeIntimate) {
		var out = new ArrayList<Map<String, Object>>();
		closed(tx, c, biography(c).get("wants"), "general", sinceJournalId, sinceSeq, out);
		if (includeIntimate) {
			closed(tx, c, intimacy(c).get("wants"), "intimate", sinceJournalId, sinceSeq, out);
		}
		return out;
	}

	@SuppressWarnings("unchecked")
	private static void closed(
		Tx tx, Row c, Object wants, String kind, Long sinceJournalId, Long sinceSeq, List<Map<String, Object>> out) {
		if (!(wants instanceof List<?> list)) {
			return;
		}
		for (Object o : list) {
			if (!isClosed(o)) {
				continue;
			}
			var w = new LinkedHashMap<String, Object>((Map<String, Object>) o);
			Number journal = closureCursor(o);
			if (sinceJournalId != null && journal != null) {
				if (journal.longValue() <= sinceJournalId) {
					continue;
				}
			} else if (sinceSeq != null) {
				Object at = w.get("done_at");
				if (at == null) {
					continue;
				}
				try {
					if (GameTime.parse(at.toString()) <= sinceSeq) {
						continue;
					}
				} catch (RuntimeException unparsable) {
					continue;
				}
			}
			w.remove(ENGINE);
			w.remove(CLOSED_JOURNAL_ID);
			String ref = wantRef(tx, c.id(), kind, o);
			w.remove("ref");
			if (ref != null) {
				w.put("ref", ref);
			}
			w.put("kind", kind);
			out.add(w);
		}
	}

	@SuppressWarnings("unchecked")
	private static List<Map<String, Object>> open(Object wants) {
		var out = new ArrayList<Map<String, Object>>();
		if (wants instanceof List<?> list) {
			for (Object o : list) {
				if (o instanceof Map<?, ?> m
						&& (m.get("status") == null || "OPEN".equalsIgnoreCase(String.valueOf(m.get("status"))))) {
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
	 * The one-line person behind the numbers, added to party lists at every detail level: age,
	 * presentation, a brief appearance, the state entries that are still running (carrying a child,
	 * an arm in a sling) and the open wants, so an NPC is played toward the same things in every
	 * scene.
	 */
	public static void appendBrief(Tx tx, Row c, Map<String, Object> m) {
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
		List<Map<String, Object>> wants = openWants(tx, c);
		if (!wants.isEmpty()) {
			m.put("wants", wants.stream().map(w -> {
				var entry = new LinkedHashMap<String, Object>();
				if (w.get("ref") != null) {
					entry.put("ref", w.get("ref"));
				}
				entry.put("note", w.get("note"));
				return entry;
			}).toList());
		}
	}
}
