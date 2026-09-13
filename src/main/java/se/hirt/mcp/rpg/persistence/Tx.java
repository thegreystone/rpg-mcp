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
package se.hirt.mcp.rpg.persistence;

import se.hirt.mcp.rpg.protocol.Json;
import se.hirt.mcp.rpg.protocol.RpgException;

import java.sql.*;
import java.util.*;

/**
 * One tool invocation = one database transaction (DOMAIN_MODEL.md I-69).
 * <p>
 * This is the <em>only</em> write API available to service code. Every write to a rewindable table
 * automatically records its inverse (a full before-image) so that a checkpoint restore can roll the
 * journal back exactly (DATABASE.md §4, I-48). Raw access is reserved for the importer, migrations,
 * and the non-rewindable log tables.
 */
public final class Tx {

	private final Connection c;
	private final boolean mutable;
	private final long journalId;
	private final List<Map<String, Object>> undo = new ArrayList<>();
	private final Map<String, Object> touched = new LinkedHashMap<>();
	private Long campaignId;
	private boolean checkpointMarker;

	Tx(Connection c, boolean mutable, long journalId, Long campaignId) {
		this.c = c;
		this.mutable = mutable;
		this.journalId = journalId;
		this.campaignId = campaignId;
	}

	// ── context ────────────────────────────────────────────────────────

	/** ID of the journal entry being written by this invocation (reserved up front). */
	public long journalId() {
		return journalId;
	}

	public Long campaignId() {
		return campaignId;
	}

	/** Used by operations that create the campaign they belong to. */
	public void bindCampaign(long id) {
		this.campaignId = id;
	}

	public void touched(String ref, long revision) {
		touched.put(ref, revision);
	}

	public void markCheckpoint() {
		this.checkpointMarker = true;
	}

	boolean isCheckpointMarker() {
		return checkpointMarker;
	}

	List<Map<String, Object>> undoOps() {
		return undo;
	}

	Map<String, Object> touchedRefs() {
		return touched;
	}

	private void requireMutable() {
		if (!mutable) {
			throw new IllegalStateException("Write attempted inside a read-only transaction");
		}
	}

	// ── reads ──────────────────────────────────────────────────────────

	public List<Row> query(String sql, Object ... params) {
		try (PreparedStatement ps = c.prepareStatement(sql)) {
			bind(ps, params);
			try (ResultSet rs = ps.executeQuery()) {
				var rows = new ArrayList<Row>();
				while (rs.next()) {
					rows.add(Row.from(rs));
				}
				return rows;
			}
		} catch (SQLException e) {
			throw RpgException.internal("Query failed: " + sql, e);
		}
	}

	public Optional<Row> queryOne(String sql, Object ... params) {
		List<Row> rows = query(sql, params);
		return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
	}

	public long count(String sql, Object ... params) {
		return queryOne(sql, params).map(r -> r.lng(r.asMap().keySet().iterator().next())).orElse(0L);
	}

	public Optional<Row> find(String table, long id) {
		checkTable(table);
		return queryOne("SELECT * FROM " + table + " WHERE id = ?", id);
	}

	public Row get(String table, long id) {
		return find(table, id).orElseThrow(() -> RpgException.notFound(table + ":" + id));
	}

	// ── journaled writes ───────────────────────────────────────────────

	public long insert(String table, Map<String, Object> columns) {
		requireMutable();
		checkTable(table);
		long id = rawInsert(table, columns);
		if (Tables.isRewindable(table)) {
			undo.add(op("delete", table, id, null));
		}
		return id;
	}

	public void update(String table, long id, Map<String, Object> columns) {
		requireMutable();
		checkTable(table);
		if (columns.isEmpty()) {
			return;
		}
		if (Tables.isRewindable(table)) {
			Row before = get(table, id);
			undo.add(op("restore", table, id, before.asMap()));
		}
		rawUpdate(table, id, columns);
	}

	public void delete(String table, long id) {
		requireMutable();
		checkTable(table);
		if (Tables.isRewindable(table)) {
			Row before = get(table, id);
			undo.add(op("insert", table, id, before.asMap()));
		}
		rawDelete(table, id);
	}

	// ── raw writes (non-rewindable tables, undo application, importer) ──

	public long rawInsert(String table, Map<String, Object> columns) {
		requireMutable();
		var cols = new ArrayList<>(columns.keySet());
		String sql = "INSERT INTO " + table + " (" + String.join(", ", cols) + ") VALUES ("
				+ String.join(", ", java.util.Collections.nCopies(cols.size(), "?")) + ")";
		try (PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
			int i = 1;
			for (String col : cols) {
				ps.setObject(i++, jdbcValue(columns.get(col)));
			}
			ps.executeUpdate();
			try (ResultSet keys = ps.getGeneratedKeys()) {
				if (keys.next()) {
					return keys.getLong(1);
				}
			}
			Object explicit = columns.get("id");
			if (explicit instanceof Number n) {
				return n.longValue();
			}
			throw new SQLException("No generated key for insert into " + table);
		} catch (SQLException e) {
			throw RpgException.internal("Insert into " + table + " failed: " + e.getMessage(), e);
		}
	}

	public void rawUpdate(String table, long id, Map<String, Object> columns) {
		requireMutable();
		var cols = new ArrayList<>(columns.keySet());
		cols.remove("id");
		if (cols.isEmpty()) {
			return;
		}
		String sql = "UPDATE " + table + " SET " + String.join(", ", cols.stream().map(col -> col + " = ?").toList())
				+ " WHERE id = ?";
		try (PreparedStatement ps = c.prepareStatement(sql)) {
			int i = 1;
			for (String col : cols) {
				ps.setObject(i++, jdbcValue(columns.get(col)));
			}
			ps.setLong(i, id);
			if (ps.executeUpdate() != 1) {
				throw RpgException.notFound(table + ":" + id);
			}
		} catch (SQLException e) {
			throw RpgException.internal("Update of " + table + ":" + id + " failed: " + e.getMessage(), e);
		}
	}

	public void rawDelete(String table, long id) {
		requireMutable();
		try (PreparedStatement ps = c.prepareStatement("DELETE FROM " + table + " WHERE id = ?")) {
			ps.setLong(1, id);
			if (ps.executeUpdate() != 1) {
				throw RpgException.notFound(table + ":" + id);
			}
		} catch (SQLException e) {
			throw RpgException.internal("Delete of " + table + ":" + id + " failed: " + e.getMessage(), e);
		}
	}

	public int rawExecute(String sql, Object ... params) {
		requireMutable();
		try (PreparedStatement ps = c.prepareStatement(sql)) {
			bind(ps, params);
			return ps.executeUpdate();
		} catch (SQLException e) {
			throw RpgException.internal("Statement failed: " + sql, e);
		}
	}

	/** Applies one inverse operation from a journal entry's undo list (DATABASE.md §5). */
	public void applyUndo(Map<String, Object> op) {
		requireMutable();
		String kind = (String) op.get("op");
		String table = (String) op.get("table");
		long pk = ((Number) op.get("pk")).longValue();
		@SuppressWarnings("unchecked")
		Map<String, Object> row = (Map<String, Object>) op.get("row");
		switch (kind) {
		case "delete" -> rawDelete(table, pk);
		case "restore" -> rawUpdate(table, pk, row);
		case "insert" -> rawInsert(table, row);
		default -> throw new IllegalStateException("Unknown undo op " + kind);
		}
	}

	// ── helpers ────────────────────────────────────────────────────────

	private static Map<String, Object> op(String kind, String table, long pk, Map<String, Object> row) {
		var m = new LinkedHashMap<String, Object>();
		m.put("op", kind);
		m.put("table", table);
		m.put("pk", pk);
		if (row != null) {
			m.put("row", row);
		}
		return m;
	}

	private static void checkTable(String table) {
		if (!Tables.isKnown(table)) {
			throw new IllegalArgumentException("Unknown table " + table);
		}
	}

	private static void bind(PreparedStatement ps, Object ... params) throws SQLException {
		for (int i = 0; i < params.length; i++) {
			ps.setObject(i + 1, jdbcValue(params[i]));
		}
	}

	private static Object jdbcValue(Object v) {
		if (v == null) {
			return null;
		}
		if (v instanceof Boolean b) {
			return b ? 1 : 0;
		}
		if (v instanceof Enum<?> e) {
			return e.name();
		}
		if (v instanceof Map<?, ?> || v instanceof List<?>) {
			return Json.write(v);
		}
		return v;
	}
}
