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

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;

/**
 * The one SQLite database file per installation (DATABASE.md §1). Single-writer: all work is
 * serialized through one connection and a lock; SQLite's own locking is only a backstop.
 */
public final class Database implements AutoCloseable {

	private final Path file;
	private final Connection conn;
	private final ReentrantLock lock = new ReentrantLock();

	public Database(Path file) {
		this.file = file;
		try {
			if (file.getParent() != null) {
				Files.createDirectories(file.getParent());
			}
			// Instantiate the driver directly: DriverManager rejects drivers loaded by another classloader
			// (e.g. after a Quarkus test in the same JVM).
			conn = new org.sqlite.JDBC().connect("jdbc:sqlite:" + file.toAbsolutePath(), new java.util.Properties());
			try (Statement st = conn.createStatement()) {
				st.execute("PRAGMA journal_mode = WAL");
				st.execute("PRAGMA foreign_keys = ON");
				st.execute("PRAGMA synchronous = NORMAL");
				st.execute("PRAGMA busy_timeout = 5000");
			}
			conn.setAutoCommit(false);
			Migrations.apply(conn);
		} catch (Exception e) {
			throw RpgException.internal("Cannot open database " + file + ": " + e.getMessage(), e);
		}
	}

	public Path file() {
		return file;
	}

	/** Describes a mutating tool invocation for journaling and idempotency. */
	public record Mutation(String operation, Long campaignId, String operationId, String provenance, Object args) {

		public static Mutation of(
			String operation, Long campaignId, String operationId, String provenance, Object args) {
			return new Mutation(operation, campaignId, operationId, provenance, args);
		}
	}

	/** A read-only unit of work. */
	public <T> T read(Function<Tx, T> work) {
		lock.lock();
		try {
			Tx tx = new Tx(conn, false, -1, null);
			try {
				return work.apply(tx);
			} finally {
				conn.rollback();
			}
		} catch (SQLException e) {
			throw RpgException.internal("Read transaction failed", e);
		} finally {
			lock.unlock();
		}
	}

	/**
	 * A mutating unit of work: reserves the journal entry, enforces idempotency (I-60), runs the
	 * work, records undo information and the result, and commits atomically (I-69). On any failure
	 * the transaction is rolled back and nothing — not even the journal entry — is observable.
	 */
	public Map<String, Object> mutate(Mutation mutation, Function<Tx, Map<String, Object>> work) {
		lock.lock();
		try {
			try {
				Tx probe = new Tx(conn, false, -1, mutation.campaignId());
				Optional<Map<String, Object>> replay = checkIdempotency(probe, mutation);
				if (replay.isPresent()) {
					conn.rollback();
					return replay.get();
				}
				String argsHash = mutation.args() == null ? null : Json.hash(mutation.args());
				long journalId = reserveJournalEntry(probe, mutation, argsHash);
				Tx tx = new Tx(conn, true, journalId, mutation.campaignId());
				Map<String, Object> result = work.apply(tx);
				finishJournalEntry(tx, mutation, result);
				conn.commit();
				return result;
			} catch (RuntimeException e) {
				conn.rollback();
				throw e;
			}
		} catch (SQLException e) {
			throw RpgException.internal("Transaction failed", e);
		} finally {
			lock.unlock();
		}
	}

	private Optional<Map<String, Object>> checkIdempotency(Tx tx, Mutation m) {
		if (m.operationId() == null || m.operationId().isBlank()) {
			return Optional.empty();
		}
		Optional<Row> existing = m.campaignId() != null
				? tx.queryOne("SELECT * FROM journal_entry WHERE campaign_id = ? AND operation_id = ?", m.campaignId(),
						m.operationId())
				: tx.queryOne("SELECT * FROM journal_entry WHERE operation = ? AND operation_id = ?", m.operation(),
						m.operationId());
		if (existing.isEmpty()) {
			return Optional.empty();
		}
		Row row = existing.get();
		String argsHash = m.args() == null ? null : Json.hash(m.args());
		if (argsHash != null && !argsHash.equals(row.str("args_hash"))) {
			throw RpgException.idempotencyConflict(m.operationId());
		}
		if (!m.operation().equals(row.str("operation"))) {
			throw RpgException.idempotencyConflict(m.operationId());
		}
		Map<String, Object> result = Json.readMap(row.str("result_json"));
		result.put("replayed", true);
		return Optional.of(result);
	}

	private long reserveJournalEntry(Tx probe, Mutation m, String argsHash) {
		var cols = new LinkedHashMap<String, Object>();
		cols.put("campaign_id", m.campaignId());
		cols.put("operation", m.operation());
		cols.put("operation_id", m.operationId() == null || m.operationId().isBlank() ? null : m.operationId());
		cols.put("args_hash", argsHash);
		cols.put("provenance", m.provenance() == null ? "GM" : m.provenance());
		cols.put("recorded_at", Instant.now().toString());
		cols.put("undo_json", "[]");
		Tx writer = new Tx(conn, true, -1, m.campaignId());
		return writer.rawInsert("journal_entry", cols);
	}

	private void finishJournalEntry(Tx tx, Mutation m, Map<String, Object> result) {
		var cols = new LinkedHashMap<String, Object>();
		cols.put("campaign_id", tx.campaignId());
		cols.put("undo_json", Json.write(tx.undoOps()));
		cols.put("touched_json", Json.write(tx.touchedRefs()));
		cols.put("result_json", Json.writeOrNull(result));
		cols.put("is_checkpoint_marker", tx.isCheckpointMarker() ? 1 : 0);
		if (tx.campaignId() != null) {
			tx.queryOne("SELECT seq FROM game_clock WHERE campaign_id = ?", tx.campaignId())
					.ifPresent(r -> cols.put("game_seq", r.lng("seq")));
		}
		tx.rawUpdate("journal_entry", tx.journalId(), cols);
	}

	/**
	 * Dumps every rewindable table for a campaign, ordered by primary key. Used by the checkpoint
	 * round-trip test (I-51) and available for diagnostics. {@code campaign.active_session_id} is
	 * excluded because sessions are audit history, not rewindable state.
	 */
	public Map<String, List<Map<String, Object>>> snapshot(long campaignId) {
		return read(tx -> {
			var out = new LinkedHashMap<String, List<Map<String, Object>>>();
			for (String table : Tables.REWINDABLE) {
				String scope = table.equals("campaign") ? "id" : "campaign_id";
				List<Row> rows = Tables.WITHOUT_CAMPAIGN_ID.contains(table)
						? tx.query("SELECT * FROM " + table + " ORDER BY id")
						: tx.query("SELECT * FROM " + table + " WHERE " + scope + " = ? ORDER BY id", campaignId);
				out.put(table, rows.stream().map(r -> {
					Map<String, Object> m = r.asMap();
					if (table.equals("campaign")) {
						m.remove("active_session_id");
					}
					return m;
				}).toList());
			}
			return out;
		});
	}

	@Override
	public void close() {
		lock.lock();
		try {
			try (Statement st = conn.createStatement()) {
				st.execute("PRAGMA wal_checkpoint(TRUNCATE)");
			} catch (SQLException ignored) {
				// best effort
			}
			conn.close();
		} catch (SQLException e) {
			throw RpgException.internal("Failed to close database", e);
		} finally {
			lock.unlock();
		}
	}
}
