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
package se.hirt.mcp.rpg.session;

import se.hirt.mcp.rpg.choice.FantasyStyle;
import se.hirt.mcp.rpg.harness.Harness;
import se.hirt.mcp.rpg.ledger.LedgerService;
import se.hirt.mcp.rpg.persistence.Database;
import se.hirt.mcp.rpg.persistence.Row;
import se.hirt.mcp.rpg.persistence.Tx;
import se.hirt.mcp.rpg.protocol.Json;
import se.hirt.mcp.rpg.protocol.Ref;
import se.hirt.mcp.rpg.protocol.RpgException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The chronicle (MCP_PROTOCOL.md §11.6, DOMAIN_MODEL.md §15): the story so far in two bounded
 * artifacts written by a summarizer the client delegates to, never by the server. A <b>CHAPTER</b>
 * is an immutable prose summary of a span of the ledger, written from the raw events; the
 * <b>SYNOPSIS</b> is the rolling "story so far", rewritten from the previous synopsis and the
 * chapters since it, with closed arcs compressed and the settled facts carried forward.
 * <p>
 * Triggers are sizes, not counts: a chapter is due when the uncovered ledger exceeds
 * {@link #CHAPTER_DUE_CHARS} characters of summary and detail, a synopsis when
 * {@link #SYNOPSIS_DUE_CHAPTERS} chapters have been written since it or a quest closed. Bootstrap
 * shows the synopsis, the chapters since it and a digest of the uncovered tail, so its story
 * section has the same size for a campaign of any length.
 */
public final class ChronicleService {

	/**
	 * A chapter is due when this many characters of non-minor ledger text are not yet covered by
	 * one.
	 */
	public static final int CHAPTER_DUE_CHARS = 20_000;
	/** A synopsis rewrite is due when this many chapters have been written since it. */
	public static final int SYNOPSIS_DUE_CHAPTERS = 4;
	/**
	 * Target and ceiling of a chapter, in characters (about 300 tokens; the ceiling refuses the
	 * write).
	 */
	public static final int CHAPTER_TARGET_CHARS = 1_200;
	public static final int CHAPTER_MAX_CHARS = 3_000;
	/** Target and ceiling of the synopsis (about 1,500 tokens). */
	public static final int SYNOPSIS_TARGET_CHARS = 6_000;
	public static final int SYNOPSIS_MAX_CHARS = 12_000;
	/** Chapter material is paged at this many characters of events. */
	public static final int MATERIAL_PAGE_CHARS = 40_000;

	private static final String UNCOVERED_EVENTS = "SELECT * FROM event WHERE campaign_id = ? AND recorded_journal_id > ? AND importance <> 'MINOR' "
			+ "AND visibility <> 'DIRECTOR_ONLY' ORDER BY id";

	private final Database db;

	public ChronicleService(Database db) {
		this.db = db;
	}

	// ── reads shared with bootstrap ────────────────────────────────────

	static Optional<Row> latestChapter(Tx tx, long campaignId) {
		return tx.queryOne(
				"SELECT * FROM chronicle WHERE campaign_id = ? AND kind = 'CHAPTER' ORDER BY id DESC LIMIT 1",
				campaignId);
	}

	static Optional<Row> synopsis(Tx tx, long campaignId) {
		return tx.queryOne(
				"SELECT * FROM chronicle WHERE campaign_id = ? AND kind = 'SYNOPSIS' AND superseded = 0 ORDER BY id DESC LIMIT 1",
				campaignId);
	}

	/** The journal point the last chapter closed at; 0 before the first. */
	static long coveredThrough(Tx tx, long campaignId) {
		return latestChapter(tx, campaignId).map(c -> c.lng("to_journal_id")).orElse(0L);
	}

	/**
	 * Chapters written after the current synopsis (all of them when there is none), oldest first.
	 */
	static List<Row> chaptersSinceSynopsis(Tx tx, long campaignId) {
		long after = synopsis(tx, campaignId).map(ChronicleService::lastCoveredChapter).orElse(0L);
		return tx.query("SELECT * FROM chronicle WHERE campaign_id = ? AND kind = 'CHAPTER' AND id > ? ORDER BY id",
				campaignId, after);
	}

	private static long lastCoveredChapter(Row synopsis) {
		if (synopsis.isNull("covers_json")) {
			return 0L;
		}
		return synopsis.list("covers_json").stream().mapToLong(o -> ((Number) o).longValue()).max().orElse(0L);
	}

	/** Size of the ledger not yet covered by a chapter: {events, chars}. */
	static long[] uncovered(Tx tx, long campaignId) {
		long events = 0;
		long chars = 0;
		for (Row e : tx.query(UNCOVERED_EVENTS, campaignId, coveredThrough(tx, campaignId))) {
			events++;
			chars += textLength(e);
		}
		return new long[] {events, chars};
	}

	private static long textLength(Row e) {
		return (e.isNull("summary") ? 0 : e.str("summary").length())
				+ (e.isNull("episodic_detail") ? 0 : e.str("episodic_detail").length());
	}

	/**
	 * What is due, if anything: {chapter, synopsis, reasons, how}. Cheap enough to sit in the
	 * consequences of every clock move and in every record_memory result, so a long session learns
	 * of it without a bootstrap.
	 */
	public static Map<String, Object> due(Tx tx, long campaignId) {
		var reasons = new ArrayList<String>();
		long[] u = uncovered(tx, campaignId);
		boolean chapter = u[1] > CHAPTER_DUE_CHARS;
		if (chapter) {
			reasons.add("CHAPTER_DUE: " + u[1] + " characters of ledger (" + u[0]
					+ " events) since the last chapter; the threshold is " + CHAPTER_DUE_CHARS + ".");
		}
		List<Row> chapters = chaptersSinceSynopsis(tx, campaignId);
		boolean synopsis = chapters.size() > SYNOPSIS_DUE_CHAPTERS;
		if (synopsis) {
			reasons.add("SYNOPSIS_DUE: " + chapters.size() + " chapters since the synopsis; the threshold is "
					+ SYNOPSIS_DUE_CHAPTERS + ".");
		}
		if (!synopsis && !chapters.isEmpty()) {
			// A quest that closed since the synopsis was written is an arc that can now be compressed.
			long since = synopsis(tx, campaignId).map(s -> s.lng("to_journal_id")).orElse(0L);
			List<Row> closed = tx.query(
					"SELECT summary FROM event WHERE campaign_id = ? AND recorded_journal_id > ? AND type IN ('QUEST_COMPLETED','QUEST_FAILED') ORDER BY id",
					campaignId, since);
			if (!closed.isEmpty()) {
				synopsis = true;
				reasons.add("SYNOPSIS_DUE: a quest closed since the synopsis (" + closed.get(0).str("summary") + ").");
			}
		}
		var out = new LinkedHashMap<String, Object>();
		out.put("chapter", chapter);
		out.put("synopsis", synopsis);
		out.put("reasons", reasons);
		if (chapter || synopsis) {
			out.put("how", "Delegate: a fresh summarizing agent calls get_chronicle_material (kind "
					+ (chapter ? "CHAPTER" : "SYNOPSIS")
					+ "), writes in the campaign's voice at the target length, and calls write_chronicle with the material's `through` marker. Play continues meanwhile.");
		}
		return out;
	}

	/** One line for a warnings list, or null when nothing is due. */
	public static String dueWarning(Tx tx, long campaignId) {
		Map<String, Object> d = due(tx, campaignId);
		if (Boolean.TRUE.equals(d.get("chapter")) || Boolean.TRUE.equals(d.get("synopsis"))) {
			return String.join(" ", ((List<?>) d.get("reasons")).stream().map(Object::toString).toList()) + " "
					+ d.get("how");
		}
		return null;
	}

	private static Map<String, Object> entry(Row c, boolean full) {
		var m = new LinkedHashMap<String, Object>();
		m.put("ref", "chronicle:" + c.id());
		m.put("kind", c.str("kind"));
		m.put("title", c.str("title"));
		m.put("covers", c.str("from_time") + " to " + c.str("to_time"));
		m.put("written_at_game_time", c.str("written_time"));
		if (full) {
			m.put("summary", c.str("summary"));
		} else {
			String s = c.str("summary");
			m.put("summary_head", s.length() <= 200 ? s : s.substring(0, 197) + "...");
		}
		return m;
	}

	/**
	 * The bootstrap story section under a character budget: the synopsis, the chapters since it
	 * (newest kept in full, older ones shortened to a head when the budget runs out), the digest of
	 * the uncovered tail, and what is due.
	 */
	public static Map<String, Object> view(Tx tx, long campaignId, int charBudget) {
		var out = new LinkedHashMap<String, Object>();
		int left = charBudget;
		Optional<Row> synopsis = synopsis(tx, campaignId);
		if (synopsis.isPresent()) {
			Map<String, Object> s = entry(synopsis.get(), true);
			out.put("synopsis", s);
			left -= synopsis.get().str("summary").length();
		} else {
			out.put("synopsis", null);
		}
		List<Row> chapters = chaptersSinceSynopsis(tx, campaignId);
		var shown = new ArrayList<Map<String, Object>>();
		int tailShare = Math.max(2_000, charBudget / 3);
		int chapterBudget = Math.max(0, left - tailShare);
		int used = 0;
		// Newest first so the most recent chapter is always whole.
		var newestFirst = new ArrayList<>(chapters);
		java.util.Collections.reverse(newestFirst);
		for (Row c : newestFirst) {
			int len = c.str("summary").length();
			boolean full = used + len <= chapterBudget;
			shown.add(0, entry(c, full));
			used += full ? len : 200;
		}
		out.put("chapters_since_synopsis", shown);
		left = Math.max(2_000, left - used);
		Map<String, Object> tail = LedgerService.digest(tx, campaignId, coveredThrough(tx, campaignId), tx.journalId(),
				left);
		tail.put("note", "The ledger since the last chapter, by importance; older detail is one query_memories away.");
		out.put("since_last_chapter", tail);
		out.put("due", due(tx, campaignId));
		return out;
	}

	// ── get_chronicle_material ─────────────────────────────────────────

	/**
	 * Everything a summarizer needs and nothing else: the previous text, the new material, the
	 * voice, the target.
	 */
	public Map<String, Object> material(String campaignRef, String kind, String cursor) {
		return db.read(tx -> {
			Row campaign = Harness.campaign(tx, campaignRef);
			long campaignId = campaign.id();
			String k = kind == null ? "CHAPTER" : kind.trim().toUpperCase();
			var out = new LinkedHashMap<String, Object>();
			out.put("kind", k);
			out.put("voice", voice(campaign));
			switch (k) {
			case "CHAPTER" -> {
				Optional<Row> last = latestChapter(tx, campaignId);
				out.put("previous_chapter", last.map(c -> entry(c, true)).orElse(null));
				out.put("earlier_chapters", tx.query(
						"SELECT * FROM chronicle WHERE campaign_id = ? AND kind = 'CHAPTER' ORDER BY id DESC LIMIT 2 OFFSET 1",
						campaignId).stream().map(c -> entry(c, false)).toList());
				long from = last.map(c -> c.lng("to_journal_id")).orElse(0L);
				long afterEvent = cursor == null || cursor.isBlank() ? 0 : Long.parseLong(cursor.trim());
				var events = new ArrayList<Map<String, Object>>();
				long chars = 0;
				Long lastJournal = null;
				String lastTime = null;
				Long nextCursor = null;
				int total = 0;
				for (Row e : tx.query(UNCOVERED_EVENTS, campaignId, from)) {
					total++;
					if (e.id() <= afterEvent) {
						continue;
					}
					if (chars > MATERIAL_PAGE_CHARS) {
						nextCursor = events.isEmpty() ? null
								: ((Number) events.get(events.size() - 1).get("id")).longValue();
						break;
					}
					Map<String, Object> m = LedgerService.eventSummary(tx, e, true);
					m.put("id", e.id());
					events.add(m);
					chars += textLength(e);
					lastJournal = e.lng("recorded_journal_id");
					lastTime = e.str("fictional_time");
				}
				if (nextCursor != null) {
					// The page was cut: the marker is the last event shown, so the next page continues there.
					out.put("next_cursor", String.valueOf(nextCursor));
				}
				out.put("events", events);
				out.put("events_uncovered", total);
				out.put("nothing_to_cover", events.isEmpty() && nextCursor == null);
				out.put("through", lastJournal == null ? null : Map.of("journal_id", lastJournal, "game_time", lastTime,
						"note",
						"Pass through.journal_id to write_chronicle; events recorded after it stay uncovered."));
				out.put("target", Map.of("chars", CHAPTER_TARGET_CHARS, "max_chars", CHAPTER_MAX_CHARS, "note",
						"About 300 tokens of prose in the campaign's voice: what happened, what was decided, what changed between people, what is now open. Name people and places; keep the game days. Never invent; omit before you guess."));
			}
			case "SYNOPSIS" -> {
				Optional<Row> current = synopsis(tx, campaignId);
				out.put("current_synopsis", current.map(c -> entry(c, true)).orElse(null));
				List<Row> chapters = chaptersSinceSynopsis(tx, campaignId);
				out.put("chapters_since", chapters.stream().map(c -> entry(c, true)).toList());
				out.put("nothing_to_cover", chapters.isEmpty());
				out.put("through", chapters.isEmpty() ? null : Map.of("chapter_id",
						chapters.get(chapters.size() - 1).id(), "game_time",
						chapters.get(chapters.size() - 1).str("to_time"), "note",
						"Pass through.chapter_id to write_chronicle; chapters written after it wait for the next rewrite."));
				out.put("target", Map.of("chars", SYNOPSIS_TARGET_CHARS, "max_chars", SYNOPSIS_MAX_CHARS, "note",
						"About 1,500 tokens: the story so far. Closed arcs compress to a few sentences; the current arc stays detailed; end with a short list of settled facts (who is wed to whom, who is dead, what the House holds, standing promises) carried forward verbatim from the previous synopsis unless a chapter contradicts one. Never invent."));
			}
			default -> throw RpgException.invalidArgument("kind must be CHAPTER or SYNOPSIS.");
			}
			out.put("meta", Harness.meta(campaign, null));
			return out;
		});
	}

	private static Map<String, Object> voice(Row campaign) {
		Map<String, Object> prefs = campaign.isNull("preferences_json") ? Map.of() : campaign.map("preferences_json");
		var v = new LinkedHashMap<String, Object>();
		if (prefs.get("experience") instanceof Map<?, ?> exp) {
			v.put("tone", exp.get("tone"));
			v.put("fantasy_style", exp.get("fantasy_style"));
			FantasyStyle style = FantasyStyle.of(prefs.get("experience"));
			if (style != null) {
				v.put("style_guidance", style.guidance());
			}
		}
		if (!campaign.isNull("title")) {
			v.put("campaign_title", campaign.str("title"));
		}
		return v;
	}

	// ── write_chronicle ────────────────────────────────────────────────

	public Map<String, Object> write(
		String operationId, String campaignRef, String kind, String title, String summary, Long through) {
		long campaignId = Ref.id(campaignRef, Ref.CAMPAIGN);
		var args = new LinkedHashMap<String, Object>();
		args.put("campaign", campaignRef);
		args.put("kind", kind);
		args.put("title", title);
		args.put("summary", summary);
		args.put("through", through);
		return db.mutate(Database.Mutation.of("write_chronicle", campaignId, operationId, "GM", args), tx -> {
			Row campaign = Harness.requireMutation(tx, campaignRef, "write_chronicle");
			String k = kind == null ? "CHAPTER" : kind.trim().toUpperCase();
			if (summary == null || summary.isBlank()) {
				throw RpgException.invalidArgument("A summary is required.");
			}
			Map<String, Object> written = switch (k) {
			case "CHAPTER" -> writeChapter(tx, campaign, title, summary.trim(), through);
			case "SYNOPSIS" -> writeSynopsis(tx, campaign, title, summary.trim(), through);
			default -> throw RpgException.invalidArgument("kind must be CHAPTER or SYNOPSIS.");
			};
			var result = new LinkedHashMap<String, Object>();
			result.put("chronicle", written);
			result.put("due", due(tx, campaignId));
			result.put("meta", Harness.meta(campaign, null));
			return result;
		});
	}

	private static LinkedHashMap<String, Object> base(Tx tx, long campaignId, String kind, String title, String text) {
		long now = GameTime.currentSeq(tx, campaignId);
		var cols = new LinkedHashMap<String, Object>();
		cols.put("campaign_id", campaignId);
		cols.put("kind", kind);
		cols.put("title", title == null || title.isBlank() ? null : title.trim());
		cols.put("summary", text);
		cols.put("written_seq", now);
		cols.put("written_time", GameTime.render(now));
		cols.put("written_at", Instant.now().toString());
		return cols;
	}

	/**
	 * Closes a chapter at {@code through} (a journal position; now when null) over the uncovered
	 * ledger.
	 */
	static Map<String, Object> writeChapter(Tx tx, Row campaign, String title, String text, Long through) {
		long campaignId = campaign.id();
		if (text.length() > CHAPTER_MAX_CHARS) {
			throw RpgException.invalidArgument("A chapter is at most " + CHAPTER_MAX_CHARS + " characters (target "
					+ CHAPTER_TARGET_CHARS + "); this one is " + text.length() + ".");
		}
		long from = coveredThrough(tx, campaignId);
		long to = through == null ? tx.journalId() : through;
		if (to <= from || to > tx.journalId()) {
			throw RpgException.conflict("through must be a journal position after the last chapter (" + from
					+ ") and not after now (" + tx.journalId() + "); re-read get_chronicle_material.");
		}
		List<Row> events = tx.query(
				"SELECT * FROM event WHERE campaign_id = ? AND recorded_journal_id > ? AND recorded_journal_id <= ? AND importance <> 'MINOR' AND visibility <> 'DIRECTOR_ONLY' ORDER BY id",
				campaignId, from, to);
		if (events.isEmpty()) {
			throw RpgException.notAllowed("Nothing to cover: no non-minor events since the last chapter.");
		}
		var cols = base(tx, campaignId, "CHAPTER", title, text);
		long fromSeq = events.stream().mapToLong(e -> e.lng("fictional_seq")).min().orElseThrow();
		long toSeq = events.stream().mapToLong(e -> e.lng("fictional_seq")).max().orElseThrow();
		cols.put("from_journal_id", from);
		cols.put("to_journal_id", to);
		cols.put("from_seq", fromSeq);
		cols.put("to_seq", toSeq);
		cols.put("from_time", GameTime.render(fromSeq));
		cols.put("to_time", GameTime.render(toSeq));
		cols.put("events_covered", events.size());
		cols.put("chars_covered", events.stream().mapToLong(ChronicleService::textLength).sum());
		return record(tx, campaignId, cols);
	}

	/**
	 * Replaces the synopsis with one built from the chapters up to {@code through} (a chapter id;
	 * the last when null).
	 */
	static Map<String, Object> writeSynopsis(Tx tx, Row campaign, String title, String text, Long through) {
		long campaignId = campaign.id();
		if (text.length() > SYNOPSIS_MAX_CHARS) {
			throw RpgException.invalidArgument("A synopsis is at most " + SYNOPSIS_MAX_CHARS + " characters (target "
					+ SYNOPSIS_TARGET_CHARS + "); this one is " + text.length() + ".");
		}
		List<Row> chapters = chaptersSinceSynopsis(tx, campaignId);
		if (chapters.isEmpty()) {
			throw RpgException.notAllowed("Nothing to cover: no chapters since the current synopsis.");
		}
		long last = through == null ? chapters.get(chapters.size() - 1).id() : through;
		List<Row> covered = chapters.stream().filter(c -> c.id() <= last).toList();
		if (covered.isEmpty()) {
			throw RpgException.conflict(
					"through must be the id of a chapter written since the current synopsis; re-read get_chronicle_material.");
		}
		Optional<Row> previous = synopsis(tx, campaignId);
		previous.ifPresent(p -> tx.update("chronicle", p.id(), Map.of("superseded", 1)));
		Row first = covered.get(0);
		Row end = covered.get(covered.size() - 1);
		var cols = base(tx, campaignId, "SYNOPSIS", title, text);
		cols.put("from_journal_id", previous.map(p -> p.lng("from_journal_id")).orElse(first.lng("from_journal_id")));
		cols.put("to_journal_id", end.lng("to_journal_id"));
		cols.put("from_seq", previous.map(p -> p.lng("from_seq")).orElse(first.lng("from_seq")));
		cols.put("from_time", previous.map(p -> p.str("from_time")).orElse(first.str("from_time")));
		cols.put("to_seq", end.lng("to_seq"));
		cols.put("to_time", end.str("to_time"));
		cols.put("events_covered", covered.stream().mapToLong(c -> c.lng("events_covered")).sum());
		cols.put("chars_covered", covered.stream().mapToLong(c -> c.lng("chars_covered")).sum());
		cols.put("covers_json", Json.write(covered.stream().map(Row::id).toList()));
		return record(tx, campaignId, cols);
	}

	private static Map<String, Object> record(Tx tx, long campaignId, Map<String, Object> cols) {
		long id = tx.insert("chronicle", cols);
		Row written = tx.get("chronicle", id);
		String k = written.str("kind");
		LedgerService.append(tx, campaignId,
				new LedgerService.EventSpec("CHRONICLE_WRITTEN",
						k.charAt(0) + k.substring(1).toLowerCase() + " written: "
								+ (written.isNull("title") ? "(untitled)" : written.str("title")) + ", covering "
								+ written.str("from_time") + " to " + written.str("to_time") + ".",
						List.of(), "MINOR", "GM_ONLY", "GM", null, null, null, Map.of("chronicle", id, "kind", k)));
		return entry(written, true);
	}
}
