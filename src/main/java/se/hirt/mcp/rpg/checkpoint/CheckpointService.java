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
package se.hirt.mcp.rpg.checkpoint;

import se.hirt.mcp.rpg.harness.Harness;
import se.hirt.mcp.rpg.harness.HarnessState;
import se.hirt.mcp.rpg.persistence.Database;
import se.hirt.mcp.rpg.persistence.Row;
import se.hirt.mcp.rpg.persistence.Tx;
import se.hirt.mcp.rpg.protocol.Json;
import se.hirt.mcp.rpg.protocol.Ref;
import se.hirt.mcp.rpg.protocol.RpgException;
import se.hirt.mcp.rpg.session.GameTime;

import java.time.Instant;
import java.util.*;

/**
 * Journal-marker checkpoints and restoration (DATABASE.md §5, MCP_PROTOCOL.md §19). A checkpoint is
 * a marker entry in the change journal; restoring applies the inverse of every later journal entry,
 * in reverse order, inside one transaction, and leaves an audit trail that survives the rollback
 * (I-53).
 */
public final class CheckpointService {

	private final Database db;

	public CheckpointService(Database db) {
		this.db = db;
	}

	/**
	 * Creates a checkpoint inside an existing unit of work (used by campaign commit and
	 * encounters).
	 */
	public static String createInTx(Tx tx, long campaignId, String reason) {
		return createInTx(tx, campaignId, reason, false);
	}

	/**
	 * @param includesMarker
	 *            when true, restoring also undoes the journal entry that created the checkpoint —
	 *            used for encounter-retry checkpoints, which must rewind the encounter's own
	 *            creation.
	 */
	public static String createInTx(Tx tx, long campaignId, String reason, boolean includesMarker) {
		tx.markCheckpoint();
		long seq = GameTime.currentSeq(tx, campaignId);
		var cols = new LinkedHashMap<String, Object>();
		cols.put("campaign_id", campaignId);
		cols.put("journal_id", tx.journalId());
		cols.put("reason", reason);
		cols.put("game_time", GameTime.render(seq));
		cols.put("game_seq", seq);
		cols.put("created_at", Instant.now().toString());
		cols.put("status", "ACTIVE");
		cols.put("includes_marker", includesMarker ? 1 : 0);
		long id = tx.rawInsert("checkpoint", cols);
		return Ref.of(Ref.CHECKPOINT, id);
	}

	// ── create_checkpoint ──────────────────────────────────────────────

	public Map<String, Object> create(String operationId, String campaignRef, String reason) {
		long campaignId = Ref.id(campaignRef, Ref.CAMPAIGN);
		var args = new LinkedHashMap<String, Object>();
		args.put("campaign", campaignRef);
		args.put("reason", reason);
		return db.mutate(Database.Mutation.of("create_checkpoint", campaignId, operationId, "GM", args), tx -> {
			Row campaign = Harness.requireMutation(tx, campaignRef, "create_checkpoint");
			if ("IRONMAN".equals(campaign.str("continuation_policy"))) {
				throw RpgException.policyDenied(
						"This campaign uses the IRONMAN continuation policy; checkpoints are not available.");
			}
			String ref = createInTx(tx, campaignId, reason == null || reason.isBlank() ? "manual" : reason);
			var result = new LinkedHashMap<String, Object>();
			result.put("checkpoint", ref);
			result.put("reason", reason);
			result.put("game_time", GameTime.toMap(tx, campaignId, GameTime.currentSeq(tx, campaignId)));
			result.put("meta", Harness.meta(campaign, null));
			return result;
		});
	}

	// ── get_continuation_options ───────────────────────────────────────

	public Map<String, Object> options(String campaignRef) {
		return db.read(tx -> {
			Row campaign = Harness.campaign(tx, campaignRef);
			long campaignId = campaign.id();
			String policy = campaign.str("continuation_policy");
			var result = new LinkedHashMap<String, Object>();
			result.put("continuation_policy", policy);
			Optional<Row> pc = tx
					.queryOne("SELECT c.* FROM player_control_assignment p JOIN character c ON c.id = p.character_id "
							+ "WHERE p.campaign_id = ? AND p.active = 1", campaignId);
			pc.ifPresent(c -> {
				var m = new LinkedHashMap<String, Object>();
				m.put("ref", Ref.of(Ref.CHARACTER, c.id()));
				m.put("name", c.str("name"));
				m.put("life_state", c.str("life_state"));
				result.put("player_character", m);
			});
			var options = new ArrayList<Map<String, Object>>();
			boolean survivors = tx.count(
					"SELECT COUNT(*) FROM party_membership m JOIN character c ON c.id = m.character_id WHERE m.campaign_id = ? "
							+ "AND m.state IN ('ACTIVE','SEPARATED') AND c.life_state <> 'DEAD' AND c.lifecycle = 'ACTIVE' AND c.id <> IFNULL(?, -1)",
					campaignId, pc.map(Row::id).orElse(null)) > 0;
			if (!"IRONMAN".equals(policy)) {
				List<Row> checkpoints = tx
						.query("SELECT * FROM checkpoint WHERE campaign_id = ? AND status IN ('ACTIVE','RESTORED_TO') "
								+ "ORDER BY id DESC", campaignId);
				var restore = new LinkedHashMap<String, Object>();
				restore.put("action", "RESTORE_CHECKPOINT");
				restore.put("operation", "restore_checkpoint");
				restore.put("checkpoints", checkpoints.stream().map(CheckpointService::checkpointSummary).toList());
				options.add(restore);
			}
			var transfer = new LinkedHashMap<String, Object>();
			transfer.put("action", "CONTINUE_WITH_SURVIVOR");
			transfer.put("operation", "transfer_player_control");
			transfer.put("available", survivors);
			transfer.put("note",
					survivors
							? "A surviving party member can become the player character; identity and history are kept."
							: "No surviving party member to take over.");
			options.add(transfer);
			var end = new LinkedHashMap<String, Object>();
			end.put("action", "COMPLETE_CAMPAIGN");
			end.put("operation", "complete_campaign");
			options.add(end);
			result.put("options", options);
			result.put("meta", Harness.meta(campaign, null));
			return result;
		});
	}

	static Map<String, Object> checkpointSummary(Row c) {
		var m = new LinkedHashMap<String, Object>();
		m.put("ref", Ref.of(Ref.CHECKPOINT, c.id()));
		m.put("reason", c.str("reason"));
		m.put("game_time", c.str("game_time"));
		m.put("created_at", c.str("created_at"));
		m.put("status", c.str("status"));
		return m;
	}

	// ── restore_checkpoint ─────────────────────────────────────────────

	public Map<String, Object> restore(String operationId, String campaignRef, String checkpointRef, String reason) {
		long campaignId = Ref.id(campaignRef, Ref.CAMPAIGN);
		long checkpointId = Ref.id(checkpointRef, Ref.CHECKPOINT);
		var args = new LinkedHashMap<String, Object>();
		args.put("campaign", campaignRef);
		args.put("checkpoint", checkpointRef);
		return db.mutate(Database.Mutation.of("restore_checkpoint", campaignId, operationId, "PLAYER", args), tx -> {
			Row campaign = Harness.requireMutation(tx, campaignRef, "restore_checkpoint");
			if ("IRONMAN".equals(campaign.str("continuation_policy"))) {
				throw RpgException.policyDenied("IRONMAN campaigns cannot be rewound.");
			}
			Row checkpoint = tx.find("checkpoint", checkpointId)
					.orElseThrow(() -> RpgException.notFound("Checkpoint " + checkpointRef));
			if (checkpoint.lng("campaign_id") != campaignId) {
				throw RpgException.invalidArgument(checkpointRef + " belongs to another campaign.");
			}
			if (!List.of("ACTIVE", "RESTORED_TO").contains(checkpoint.str("status"))) {
				throw RpgException
						.notAllowed(checkpointRef + " is " + checkpoint.str("status") + " and cannot be restored.");
			}
			if (tx.count("SELECT COUNT(*) FROM pending_transaction WHERE campaign_id = ? AND status = 'OPEN'",
					campaignId) > 0) {
				throw RpgException.notAllowed("Abandon the open pending transaction before restoring a checkpoint.");
			}
			long marker = checkpoint.lng("journal_id");
			long current = tx.journalId();
			// Retry checkpoints rewind the entry that created them (the encounter start) as well.
			long from = checkpoint.bool("includes_marker") ? marker - 1 : marker;

			// 2–4: undo every later entry in reverse chronology, then drop those entries.
			List<Row> later = tx.query(
					"SELECT id, undo_json, game_seq FROM journal_entry WHERE campaign_id = ? AND id > ? AND id < ? ORDER BY id DESC",
					campaignId, from, current);
			long fromSeq = -1;
			long toSeq = -1;
			for (Row entry : later) {
				List<Object> ops = entry.list("undo_json");
				// Inverse operations compose only in reverse order — across entries and within one.
				for (int i = ops.size() - 1; i >= 0; i--) {
					@SuppressWarnings("unchecked")
					Map<String, Object> m = (Map<String, Object>) ops.get(i);
					tx.applyUndo(m);
				}
				if (!entry.isNull("game_seq")) {
					long seq = entry.lng("game_seq");
					toSeq = toSeq < 0 ? seq : Math.max(toSeq, seq);
					fromSeq = fromSeq < 0 ? seq : Math.min(fromSeq, seq);
				}
			}
			for (Row entry : later) {
				tx.rawDelete("journal_entry", entry.id());
			}

			// 5: checkpoint bookkeeping (metadata, not rewindable).
			tx.rawExecute(
					"UPDATE checkpoint SET status = 'INVALIDATED' WHERE campaign_id = ? AND journal_id > ? AND status <> 'INVALIDATED'",
					campaignId, marker);
			tx.rawUpdate("checkpoint", checkpointId, Map.of("status", "RESTORED_TO"));

			// Sessions are audit history: a session that ended after the marker described a discarded branch.
			tx.rawExecute(
					"UPDATE session SET superseded = 1 WHERE campaign_id = ? AND (end_journal_id > ? OR (start_journal_id > ? AND ended_at IS NOT NULL))",
					campaignId, marker, marker);
			Optional<Row> openSession = tx.queryOne(
					"SELECT * FROM session WHERE campaign_id = ? AND ended_at IS NULL ORDER BY id DESC LIMIT 1",
					campaignId);
			Row restored = tx.get("campaign", campaignId);
			var fix = new LinkedHashMap<String, Object>();
			fix.put("active_session_id", openSession.map(Row::id).orElse(null));
			// Sessions are not rewound: if one is open, play simply continues from the restored state.
			if (List.of("READY_TO_PLAY", "ACTIVE", "SUSPENDED").contains(restored.str("status"))) {
				boolean encounterRunning = tx.count(
						"SELECT COUNT(*) FROM encounter WHERE campaign_id = ? AND status IN ('RUNNING','WAITING_CHOICE')",
						campaignId) > 0;
				if (openSession.isPresent()) {
					fix.put("status", "ACTIVE");
					fix.put("harness_state",
							encounterRunning ? HarnessState.ENCOUNTER.name() : HarnessState.EXPLORATION.name());
				} else if (!"READY_TO_PLAY".equals(restored.str("status"))) {
					fix.put("status", "SUSPENDED");
					fix.put("harness_state", HarnessState.SESSION_SUSPEND.name());
				}
			}
			tx.rawUpdate("campaign", campaignId, fix);

			// 6: audit lineage that survives the rollback.
			long minId = later.isEmpty() ? -1 : later.get(later.size() - 1).id();
			long maxId = later.isEmpty() ? -1 : later.get(0).id();
			var discarded = new LinkedHashMap<String, Object>();
			discarded.put("journal_id_from", minId);
			discarded.put("journal_id_to", maxId);
			discarded.put("entries", later.size());
			discarded.put("game_seq_from", fromSeq);
			discarded.put("game_seq_to", toSeq);
			audit(tx, campaignId, "CHECKPOINT_RESTORE", reason,
					Map.of("checkpoint", checkpointRef, "marker_journal_id", marker), discarded);
			audit(tx, campaignId, "DISCARDED_BRANCH", reason, discarded, Map.of("restored_to", checkpointRef));

			Row after = tx.get("campaign", campaignId);
			long seq = GameTime.currentSeq(tx, campaignId);
			var result = new LinkedHashMap<String, Object>();
			result.put("restored_to", checkpointRef);
			result.put("discarded_journal_entries", later.size());
			result.put("game_time", GameTime.toMap(tx, campaignId, seq));
			result.put("status", after.str("status"));
			result.put("requires_context_reset", true);
			result.put("audit", "restoration recorded; discarded events are no longer canonical");
			result.put("meta", Harness.meta(after, List.of(
					"Rebuild your context from bootstrap_session; prior narration has no authority over restored state.")));
			return result;
		});
	}

	private static void audit(
		Tx tx, long campaignId, String kind, String reason, Map<String, Object> before, Map<String, Object> after) {
		var cols = new LinkedHashMap<String, Object>();
		cols.put("campaign_id", campaignId);
		cols.put("kind", kind);
		cols.put("actor", "player");
		cols.put("provenance", "PLAYER");
		cols.put("reason", reason);
		cols.put("before_json", Json.write(before));
		cols.put("after_json", Json.write(after));
		cols.put("recorded_at", Instant.now().toString());
		tx.rawInsert("audit_record", cols);
	}
}
