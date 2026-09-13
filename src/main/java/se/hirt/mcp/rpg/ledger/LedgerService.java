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
package se.hirt.mcp.rpg.ledger;

import se.hirt.mcp.rpg.harness.Harness;
import se.hirt.mcp.rpg.persistence.Database;
import se.hirt.mcp.rpg.persistence.Row;
import se.hirt.mcp.rpg.persistence.Tx;
import se.hirt.mcp.rpg.protocol.Json;
import se.hirt.mcp.rpg.protocol.Ref;
import se.hirt.mcp.rpg.protocol.RpgException;
import se.hirt.mcp.rpg.session.GameTime;

import java.util.*;

/**
 * The semantic event ledger (DOMAIN_MODEL.md §14.1): compact structured memories with two
 * independent orderings — insertion (event id) and fictional (game time).
 */
public final class LedgerService {

	public static final Set<String> IMPORTANCE = Set.of("MINOR", "NOTABLE", "MAJOR", "CRITICAL");
	public static final Set<String> VISIBILITY = Set.of("PLAYER_KNOWN", "PARTY_KNOWN", "CHARACTER_KNOWN",
			"FACTION_KNOWN", "GM_ONLY", "DIRECTOR_ONLY");
	public static final Set<String> PROVENANCE = Set.of("PLAYER", "GM", "DIRECTOR", "MECHANICAL_CONSEQUENCE",
			"ADMINISTRATIVE_OVERRIDE");

	private final Database db;

	public LedgerService(Database db) {
		this.db = db;
	}

	/** Parameters for one ledger event. Nulls fall back to sensible defaults. */
	public record EventSpec(String type, String summary, List<Long> actorIds, String importance, String visibility,
			String provenance, Long fictionalSeq, Long locationId, String episodicDetail, Map<String, Object> payload) {
	}

	/**
	 * Appends an event inside an existing unit of work — this is how mechanical operations write
	 * their own ledger entries (I-47). Returns the new event id.
	 */
	public static long append(Tx tx, long campaignId, EventSpec spec) {
		long now = GameTime.currentSeq(tx, campaignId);
		long fictional = spec.fictionalSeq() == null ? now : spec.fictionalSeq();
		var cols = new LinkedHashMap<String, Object>();
		cols.put("campaign_id", campaignId);
		cols.put("type", spec.type());
		cols.put("summary", spec.summary());
		cols.put("payload_json", spec.payload() == null ? null : Json.write(spec.payload()));
		cols.put("importance", spec.importance() == null ? "NOTABLE" : spec.importance());
		cols.put("visibility", spec.visibility() == null ? "PARTY_KNOWN" : spec.visibility());
		cols.put("provenance", spec.provenance() == null ? "GM" : spec.provenance());
		cols.put("fictional_time", GameTime.render(fictional));
		cols.put("fictional_seq", fictional);
		cols.put("recorded_time", GameTime.render(now));
		cols.put("recorded_seq", now);
		cols.put("recorded_journal_id", tx.journalId());
		cols.put("location_id", spec.locationId());
		cols.put("episodic_detail", spec.episodicDetail());
		// Every event remembers the session it was written in (V007), so the recap can be derived from the ledger.
		cols.put("session_id", tx.queryOne("SELECT active_session_id FROM campaign WHERE id = ?", campaignId)
				.filter(r -> !r.isNull("active_session_id")).map(r -> r.lng("active_session_id")).orElse(null));
		long eventId = tx.insert("event", cols);
		if (spec.actorIds() != null) {
			for (Long actor : new java.util.LinkedHashSet<>(spec.actorIds())) {
				tx.insert("event_actor", Map.of("event_id", eventId, "character_id", actor));
			}
		}
		return eventId;
	}

	// ── record_memory ──────────────────────────────────────────────────

	public Map<String, Object> record(
		String operationId, String campaignRef, String type, String summary, List<String> participants,
		String importance, String visibility, String provenance, String gameTime, String locationRef, String detail,
		Map<String, Object> payload) {
		long campaignId = Ref.id(campaignRef, Ref.CAMPAIGN);
		var args = new LinkedHashMap<String, Object>();
		args.put("campaign", campaignRef);
		args.put("type", type);
		args.put("summary", summary);
		args.put("participants", participants);
		args.put("importance", importance);
		args.put("visibility", visibility);
		args.put("game_time", gameTime);
		args.put("location", locationRef);
		return db.mutate(Database.Mutation.of("record_memory", campaignId, operationId, provenance, args), tx -> {
			Row campaign = Harness.requireMutation(tx, campaignRef, "record_memory");
			if (type == null || type.isBlank()) {
				throw RpgException.invalidArgument("An event type is required (for example RELATIONSHIP_MILESTONE).");
			}
			if (summary == null || summary.isBlank()) {
				throw RpgException.invalidArgument("A concise summary is required.");
			}
			String imp = importance == null ? "NOTABLE" : importance.toUpperCase();
			if (!IMPORTANCE.contains(imp)) {
				throw RpgException.invalidArgument("importance must be one of " + IMPORTANCE + ".");
			}
			String vis = visibility == null ? "PARTY_KNOWN" : visibility.toUpperCase();
			if (!VISIBILITY.contains(vis)) {
				throw RpgException.invalidArgument("visibility must be one of " + VISIBILITY + ".");
			}
			String prov = provenance == null ? "GM" : provenance.toUpperCase();
			if (!PROVENANCE.contains(prov)) {
				throw RpgException.invalidArgument("provenance must be one of " + PROVENANCE + ".");
			}
			var actorIds = new ArrayList<Long>();
			if (participants != null) {
				for (String p : participants) {
					long id = Ref.id(p, Ref.CHARACTER);
					Row c = tx.find("character", id).orElseThrow(() -> RpgException.notFound("Character " + p));
					if (c.lng("campaign_id") != campaignId) {
						throw RpgException.invalidArgument(p + " belongs to another campaign.");
					}
					if (!"ACTIVE".equals(c.str("lifecycle"))) {
						throw RpgException
								.invalidArgument(p + " is not an active character and cannot appear in the ledger.");
					}
					actorIds.add(id);
				}
			}
			Long locationId = null;
			if (locationRef != null && !locationRef.isBlank()) {
				locationId = Ref.id(locationRef, Ref.LOCATION);
				Row loc = tx.get("location", locationId);
				if (loc.lng("campaign_id") != campaignId) {
					throw RpgException.invalidArgument(locationRef + " belongs to another campaign.");
				}
			}
			Long fictional = gameTime == null || gameTime.isBlank() ? null : GameTime.parse(gameTime);
			long eventId = append(tx, campaignId, new EventSpec(type.toUpperCase(), summary, actorIds, imp, vis, prov,
					fictional, locationId, detail, payload));
			tx.touched(Ref.of(Ref.EVENT, eventId), 0);
			var result = new LinkedHashMap<String, Object>();
			result.put("event", Ref.of(Ref.EVENT, eventId));
			result.put("game_time", GameTime.toMap(tx, campaignId,
					fictional == null ? GameTime.currentSeq(tx, campaignId) : fictional));
			// A long session learns here, not only at bootstrap, that the chronicle is owed a chapter.
			String due = se.hirt.mcp.rpg.session.ChronicleService.dueWarning(tx, campaignId);
			result.put("meta", Harness.meta(campaign, due == null ? null : List.of(due)));
			return result;
		});
	}

	// ── queries ────────────────────────────────────────────────────────

	public Map<String, Object> queryMemories(
		String campaignRef, List<String> participants, List<String> types, String focus, String minImportance,
		int limit) {
		long campaignId = Ref.id(campaignRef, Ref.CAMPAIGN);
		int max = Math.max(1, Math.min(limit <= 0 ? 10 : limit, 100));
		return db.read(tx -> {
			Row campaign = Harness.campaign(tx, campaignRef);
			var sql = new StringBuilder("SELECT DISTINCT e.* FROM event e");
			// JOIN placeholders precede WHERE placeholders: keep their parameters in that order.
			var params = new ArrayList<Object>();
			var where = new ArrayList<String>();
			if (participants != null && !participants.isEmpty()) {
				int i = 0;
				for (String p : participants) {
					String alias = "a" + i++;
					sql.append(" JOIN event_actor ").append(alias).append(" ON ").append(alias)
							.append(".event_id = e.id AND ").append(alias).append(".character_id = ?");
					params.add(Ref.id(p, Ref.CHARACTER));
				}
			}
			where.add("e.campaign_id = ?");
			params.add(campaignId);
			if (types != null && !types.isEmpty()) {
				where.add("e.type IN (" + String.join(",", java.util.Collections.nCopies(types.size(), "?")) + ")");
				for (String t : types) {
					params.add(t.toUpperCase());
				}
			}
			if (focus != null && !focus.isBlank()) {
				var terms = new ArrayList<String>();
				for (String word : focus.toLowerCase().split("\\s+")) {
					if (word.length() < 3) {
						continue;
					}
					terms.add(
							"(LOWER(e.summary) LIKE ? OR LOWER(IFNULL(e.episodic_detail,'')) LIKE ? OR LOWER(e.type) LIKE ?)");
					String like = "%" + word + "%";
					params.add(like);
					params.add(like);
					params.add(like);
				}
				if (!terms.isEmpty()) {
					where.add("(" + String.join(" OR ", terms) + ")");
				}
			}
			if (minImportance != null && !minImportance.isBlank()) {
				where.add("e.importance IN (" + importanceAtLeast(minImportance.toUpperCase()) + ")");
			}
			sql.append(" WHERE ").append(String.join(" AND ", where));
			sql.append(
					" ORDER BY CASE e.importance WHEN 'CRITICAL' THEN 0 WHEN 'MAJOR' THEN 1 WHEN 'NOTABLE' THEN 2 ELSE 3 END, e.fictional_seq DESC LIMIT ?");
			params.add(max);
			List<Row> rows = tx.query(sql.toString(), params.toArray());
			var result = new LinkedHashMap<String, Object>();
			result.put("events", rows.stream().map(r -> eventSummary(tx, r, true)).toList());
			result.put("meta", Harness.meta(campaign, null));
			return result;
		});
	}

	public Map<String, Object> queryTimeline(
		String campaignRef, String fromTime, String toTime, List<String> participants, List<String> types,
		String minImportance, boolean byInsertion, int limit) {
		long campaignId = Ref.id(campaignRef, Ref.CAMPAIGN);
		int max = Math.max(1, Math.min(limit <= 0 ? 25 : limit, 100));
		return db.read(tx -> {
			Row campaign = Harness.campaign(tx, campaignRef);
			var sql = new StringBuilder("SELECT DISTINCT e.* FROM event e");
			var params = new ArrayList<Object>();
			var where = new ArrayList<String>();
			if (participants != null) {
				int i = 0;
				for (String p : participants) {
					String alias = "a" + i++;
					sql.append(" JOIN event_actor ").append(alias).append(" ON ").append(alias)
							.append(".event_id = e.id AND ").append(alias).append(".character_id = ?");
					params.add(Ref.id(p, Ref.CHARACTER));
				}
			}
			where.add("e.campaign_id = ?");
			params.add(campaignId);
			if (fromTime != null && !fromTime.isBlank()) {
				where.add("e.fictional_seq >= ?");
				params.add(GameTime.parse(fromTime));
			}
			if (toTime != null && !toTime.isBlank()) {
				where.add("e.fictional_seq <= ?");
				params.add(GameTime.parse(toTime));
			}
			if (types != null && !types.isEmpty()) {
				where.add("e.type IN (" + String.join(",", java.util.Collections.nCopies(types.size(), "?")) + ")");
				for (String t : types) {
					params.add(t.toUpperCase());
				}
			}
			if (minImportance != null && !minImportance.isBlank()) {
				where.add("e.importance IN (" + importanceAtLeast(minImportance.toUpperCase()) + ")");
			}
			sql.append(" WHERE ").append(String.join(" AND ", where));
			sql.append(byInsertion ? " ORDER BY e.id DESC" : " ORDER BY e.fictional_seq DESC, e.id DESC");
			sql.append(" LIMIT ?");
			params.add(max);
			List<Row> rows = tx.query(sql.toString(), params.toArray());
			var result = new LinkedHashMap<String, Object>();
			result.put("ordering", byInsertion ? "INSERTION" : "FICTIONAL");
			result.put("events", rows.stream().map(r -> eventSummary(tx, r, false)).toList());
			result.put("meta", Harness.meta(campaign, null));
			return result;
		});
	}

	/** Recent significant events for a bootstrap context (most recent first). */
	public static List<Map<String, Object>> recent(Tx tx, long campaignId, int limit) {
		List<Row> rows = tx.query("SELECT * FROM event WHERE campaign_id = ? AND importance <> 'MINOR' "
				+ "AND visibility <> 'DIRECTOR_ONLY' ORDER BY id DESC LIMIT ?", campaignId, limit);
		return rows.stream().map(r -> eventSummary(tx, r, false)).toList();
	}

	public static Map<String, Object> eventSummary(Tx tx, Row r, boolean includeDetail) {
		var m = new LinkedHashMap<String, Object>();
		m.put("ref", Ref.of(Ref.EVENT, r.id()));
		m.put("type", r.str("type"));
		m.put("summary", r.str("summary"));
		m.put("importance", r.str("importance"));
		m.put("visibility", r.str("visibility"));
		m.put("game_time", r.str("fictional_time"));
		List<Row> actors = tx.query("SELECT c.id, c.name FROM event_actor a JOIN character c ON c.id = a.character_id "
				+ "WHERE a.event_id = ? ORDER BY a.id", r.id());
		m.put("participants",
				actors.stream().map(a -> Ref.of(Ref.CHARACTER, a.id()) + " (" + a.str("name") + ")").toList());
		if (!r.isNull("location_id")) {
			m.put("location", Ref.of(Ref.LOCATION, r.lng("location_id")));
		}
		if (includeDetail && !r.isNull("episodic_detail")) {
			m.put("detail", r.str("episodic_detail"));
		}
		return m;
	}

	/**
	 * "Since you last played": the ledger between two journal points, arranged by importance under
	 * a character budget and never by a model. CRITICAL events keep their episodic detail, MAJOR
	 * their summaries, NOTABLE one line each while the budget lasts, MINOR only a count by type.
	 * Within each group the order is chronological. When the budget is exceeded the least important
	 * lines go first, oldest first, and the digest says how many were left out.
	 */
	public static Map<String, Object> digest(
		Tx tx, long campaignId, long fromJournalId, long toJournalId, int charBudget) {
		List<Row> rows = tx.query(
				"SELECT * FROM event WHERE campaign_id = ? AND recorded_journal_id > ? AND recorded_journal_id <= ? "
						+ "AND visibility <> 'DIRECTOR_ONLY' AND type <> 'CHRONICLE_WRITTEN' ORDER BY id",
				campaignId, fromJournalId, toJournalId);
		var critical = new ArrayList<Map<String, Object>>();
		var major = new ArrayList<Map<String, Object>>();
		var notable = new ArrayList<String>();
		var minor = new TreeMap<String, Integer>();
		for (Row r : rows) {
			switch (r.str("importance")) {
			case "CRITICAL" -> critical.add(eventSummary(tx, r, true));
			case "MAJOR" -> major.add(eventSummary(tx, r, false));
			case "NOTABLE" -> notable.add(r.str("fictional_time") + " " + r.str("type") + ": " + r.str("summary"));
			default -> minor.merge(r.str("type"), 1, Integer::sum);
			}
		}
		int omitted = 0;
		while (size(critical) + size(major) + notable.stream().mapToInt(String::length).sum() > charBudget) {
			if (!notable.isEmpty()) {
				notable.remove(0);
			} else if (!major.isEmpty()) {
				major.remove(0);
			} else if (critical.stream().anyMatch(m -> m.containsKey("detail"))) {
				critical.stream().filter(m -> m.containsKey("detail")).findFirst().ifPresent(m -> m.remove("detail"));
				continue;
			} else if (!critical.isEmpty()) {
				critical.remove(0);
			} else {
				break;
			}
			omitted++;
		}
		var out = new LinkedHashMap<String, Object>();
		out.put("events", rows.size());
		if (!rows.isEmpty()) {
			out.put("from", rows.get(0).str("fictional_time"));
			out.put("to", rows.get(rows.size() - 1).str("fictional_time"));
		}
		out.put("critical", critical);
		out.put("major", major);
		out.put("notable", notable);
		out.put("minor_by_type", minor);
		if (omitted > 0) {
			out.put("omitted_for_budget", omitted);
		}
		return out;
	}

	private static int size(List<Map<String, Object>> events) {
		return events.stream().mapToInt(m -> Json.write(m).length()).sum();
	}

	private static String importanceAtLeast(String min) {
		return switch (min) {
		case "CRITICAL" -> "'CRITICAL'";
		case "MAJOR" -> "'CRITICAL','MAJOR'";
		case "NOTABLE" -> "'CRITICAL','MAJOR','NOTABLE'";
		default -> "'CRITICAL','MAJOR','NOTABLE','MINOR'";
		};
	}
}
