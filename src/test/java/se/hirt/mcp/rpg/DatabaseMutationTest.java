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

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.AbstractMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import se.hirt.mcp.rpg.persistence.Database;
import se.hirt.mcp.rpg.protocol.ErrorCode;
import se.hirt.mcp.rpg.protocol.Ref;
import se.hirt.mcp.rpg.protocol.RpgException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static se.hirt.mcp.rpg.TestCampaigns.committedCampaign;
import static se.hirt.mcp.rpg.TestCampaigns.engine;
import static se.hirt.mcp.rpg.TestCampaigns.map;
import static se.hirt.mcp.rpg.TestCampaigns.op;
import static se.hirt.mcp.rpg.TestCampaigns.tempDb;

class DatabaseMutationTest {

	@Test
	void errorRollsBackBeforeTheNextMutationCanCommitItsPartialWrites() throws Exception {
		Path database = tempDb("mutation-error-rollback");
		Map<String, Object> before;
		String failedOperation = op();
		String successfulOperation = op();
		try (Engine engine = engine(database)) {
			String campaign = committedCampaign(engine);
			long campaignId = Ref.id(campaign, Ref.CAMPAIGN);
			long characterId = 1;
			before = engine.db().read(tx -> tx.get("character", characterId).asMap());
			AssertionError sentinel = new AssertionError("abort a partially written mutation");
			AssertionError propagated = assertThrows(AssertionError.class, () -> engine.db()
					.mutate(Database.Mutation.of("failing_error", campaignId, failedOperation, "GM", null), tx -> {
						tx.update("character", characterId, map("name", "Partial failed write", "current_hp", 1));
						throw sentinel;
					}));
			// No read/snapshot may run here: Database.read would roll back and hide the failure.
			Map<String, Object> committed = engine.db().mutate(
					Database.Mutation.of("following_success", campaignId, successfulOperation, "GM", null), tx -> {
						tx.update("character", characterId, Map.of("temp_hp", 7));
						return tx.get("character", characterId).asMap();
					});
			assertSame(sentinel, propagated, "the original Error must propagate unchanged");
			assertEquals(before.get("name"), committed.get("name"), "the next commit cannot publish the failed write");
			assertEquals(before.get("current_hp"), committed.get("current_hp"));
			assertEquals(7, ((Number) committed.get("temp_hp")).intValue());
			assertEquals(0L, journalCount(engine.db(), failedOperation));
			assertEquals(1L, journalCount(engine.db(), successfulOperation));
		}
		try (Database reopened = new Database(database)) {
			Map<String, Object> persisted = reopened.read(tx -> tx.get("character", 1).asMap());
			assertEquals(before.get("name"), persisted.get("name"));
			assertEquals(before.get("current_hp"), persisted.get("current_hp"));
			assertEquals(7, ((Number) persisted.get("temp_hp")).intValue(), "the legitimate next write reached disk");
			assertEquals(0L, journalCount(reopened, failedOperation));
			assertEquals(1L, journalCount(reopened, successfulOperation));
		}
	}

	@Test
	void errorWhileSerializingTheResultAlsoRollsBackTheWorkAndReservedJournal() throws Exception {
		try (Engine engine = engine(tempDb("mutation-result-error"))) {
			String campaign = committedCampaign(engine);
			long campaignId = Ref.id(campaign, Ref.CAMPAIGN);
			String originalName = engine.db().read(tx -> tx.get("character", 1).str("name"));
			String failedOperation = op();
			AssertionError sentinel = new AssertionError("result serialization failed");
			Map<String, Object> brokenResult = new AbstractMap<>() {
				@Override
				public Set<Entry<String, Object>> entrySet() {
					throw sentinel;
				}
			};
			AssertionError propagated = assertThrows(AssertionError.class, () -> engine.db()
					.mutate(Database.Mutation.of("failing_result", campaignId, failedOperation, "GM", null), tx -> {
						tx.update("character", 1, Map.of("name", "Unserializable partial write"));
						return brokenResult;
					}));
			// Observe through the next committing mutation, never through a masking read transaction.
			Map<String, Object> committed = engine.db()
					.mutate(Database.Mutation.of("after_result_error", campaignId, op(), "GM", null), tx -> {
						tx.update("character", 1, Map.of("temp_hp", 5));
						return tx.get("character", 1).asMap();
					});
			assertSame(sentinel, propagated);
			assertEquals(originalName, committed.get("name"));
			assertEquals(5, ((Number) committed.get("temp_hp")).intValue());
			assertEquals(0L, journalCount(engine.db(), failedOperation));
		}
	}

	@Test
	void runtimeExceptionStillRollsBackAndPropagatesTheOriginalFailure() throws Exception {
		try (Engine engine = engine(tempDb("mutation-runtime-rollback"))) {
			String campaign = committedCampaign(engine);
			long campaignId = Ref.id(campaign, Ref.CAMPAIGN);
			Map<String, Object> before = engine.db().read(tx -> tx.get("character", 1).asMap());
			String failedOperation = op();
			IllegalStateException sentinel = new IllegalStateException("callback rejected its partial writes");
			IllegalStateException propagated = assertThrows(IllegalStateException.class, () -> engine.db()
					.mutate(Database.Mutation.of("failing_runtime", campaignId, failedOperation, "GM", null), tx -> {
						tx.update("character", 1, map("name", "Rejected partial write", "current_hp", 1));
						throw sentinel;
					}));
			Map<String, Object> committed = engine.db()
					.mutate(Database.Mutation.of("after_runtime_error", campaignId, op(), "GM", null), tx -> {
						tx.update("character", 1, Map.of("temp_hp", 3));
						return tx.get("character", 1).asMap();
					});
			assertSame(sentinel, propagated);
			assertEquals(before.get("name"), committed.get("name"));
			assertEquals(before.get("current_hp"), committed.get("current_hp"));
			assertEquals(3, ((Number) committed.get("temp_hp")).intValue());
			assertEquals(0L, journalCount(engine.db(), failedOperation));
		}
	}

	@Test
	void anErroredOperationCanBeRetriedAndOnlyTheSuccessfulAttemptIsReplayable() throws Exception {
		try (Engine engine = engine(tempDb("mutation-error-retry"))) {
			String campaign = committedCampaign(engine);
			long campaignId = Ref.id(campaign, Ref.CAMPAIGN);
			Database.Mutation mutation = Database.Mutation.of("retry_after_error", campaignId, op(), "GM",
					Map.of("name", "Recovered name"));
			AssertionError sentinel = new AssertionError("first attempt aborted");
			AtomicInteger callbackCount = new AtomicInteger();
			assertSame(sentinel, assertThrows(AssertionError.class, () -> engine.db().mutate(mutation, tx -> {
				callbackCount.incrementAndGet();
				tx.update("character", 1, Map.of("name", "Failed first attempt"));
				throw sentinel;
			})));
			// A dangling reserved journal entry would incorrectly short-circuit this as a replay.
			Map<String, Object> retried = engine.db().mutate(mutation, tx -> {
				callbackCount.incrementAndGet();
				tx.update("character", 1, Map.of("name", "Recovered name"));
				return Map.of("name", tx.get("character", 1).str("name"));
			});
			assertEquals(2, callbackCount.get());
			assertEquals(Map.of("name", "Recovered name"), retried);
			Map<String, Object> replayed = engine.db().mutate(mutation, tx -> {
				callbackCount.incrementAndGet();
				throw new AssertionError("successful replay must not run the callback");
			});
			assertEquals(2, callbackCount.get());
			assertEquals(Boolean.TRUE, replayed.get("replayed"));
			assertEquals("Recovered name", replayed.get("name"));
			assertEquals("Recovered name", engine.db().read(tx -> tx.get("character", 1).str("name")));
			assertEquals(1L, journalCount(engine.db(), mutation.operationId()));
		}
	}

	@Test
	void deferredForeignKeyCommitFailureRollsBackBeforeTheNextMutation() throws Exception {
		try (Engine engine = engine(tempDb("mutation-commit-failure"))) {
			String campaign = committedCampaign(engine);
			long campaignId = Ref.id(campaign, Ref.CAMPAIGN);
			Map<String, Object> before = engine.db().read(tx -> tx.get("character", 1).asMap());
			String failedOperation = op();
			AtomicBoolean callbackCompleted = new AtomicBoolean();
			RpgException failure = assertThrows(RpgException.class, () -> engine.db()
					.mutate(Database.Mutation.of("failing_commit", campaignId, failedOperation, "GM", null), tx -> {
						tx.rawExecute("PRAGMA defer_foreign_keys = ON");
						tx.update("character", 1,
								map("name", "Leaked by failed commit", "location_id", Long.MAX_VALUE));
						callbackCompleted.set(true);
						return Map.of("changed", true);
					}));
			// Without rollback this repair makes the failed transaction committable, exposing its other writes.
			Map<String, Object> committed = engine.db()
					.mutate(Database.Mutation.of("after_commit_failure", campaignId, op(), "GM", null), tx -> {
						tx.update("character", 1, map("location_id", before.get("location_id"), "temp_hp", 9));
						return tx.get("character", 1).asMap();
					});
			assertTrue(callbackCompleted.get(), "the deferred constraint fails at commit, after the callback returns");
			assertEquals(ErrorCode.INTERNAL_ERROR, failure.code());
			assertInstanceOf(SQLException.class, failure.getCause());
			assertEquals(before.get("name"), committed.get("name"));
			assertEquals(before.get("location_id"), committed.get("location_id"));
			assertEquals(9, ((Number) committed.get("temp_hp")).intValue());
			assertEquals(0L, journalCount(engine.db(), failedOperation));
		}
	}

	@Test
	void rollbackFailureIsSuppressedWithoutReplacingTheOriginalError() throws Exception {
		try (Database database = new Database(tempDb("mutation-rollback-failure"))) {
			AssertionError sentinel = new AssertionError("original mutation failure");
			AssertionError propagated = assertThrows(AssertionError.class,
					() -> database.mutate(Database.Mutation.of("rollback_failure", null, op(), "GM", null), tx -> {
						// End the transaction directly so JDBC's cleanup rollback encounters an inactive one.
						tx.rawExecute("ROLLBACK");
						throw sentinel;
					}));
			assertSame(sentinel, propagated);
			assertEquals(1, propagated.getSuppressed().length);
			assertInstanceOf(SQLException.class, propagated.getSuppressed()[0]);
			// Discard this disposable connection after deliberately disrupting its transaction state.
		}
	}

	private static long journalCount(Database database, String operationId) {
		return database.read(tx -> tx.count("SELECT COUNT(*) FROM journal_entry WHERE operation_id = ?", operationId));
	}
}
