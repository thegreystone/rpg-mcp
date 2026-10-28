/* Copyright (C) 2026 Marcus Hirt. SPDX-License-Identifier: BSD-3-Clause */
package se.hirt.mcp.rpg;

import org.junit.jupiter.api.Test;
import se.hirt.mcp.rpg.persistence.Database;
import se.hirt.mcp.rpg.protocol.Json;
import se.hirt.mcp.rpg.protocol.Ref;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static se.hirt.mcp.rpg.TestCampaigns.*;

/** Schema-13 journal images remain usable after V014 adds the encounter record and narration. */
class LegacyEncounterCheckpointTest {

	@Test
	void restoringSchema13ImagesClearsLaterRecordAndNarration() throws Exception {
		assertLegacyCheckpoint(false);
	}

	@Test
	void reinsertingSchema13ImagesDefaultsRecordAndNarrationToNull() throws Exception {
		assertLegacyCheckpoint(true);
	}

	private static void assertLegacyCheckpoint(boolean deleted) throws Exception {
		try (Engine engine = engine(tempDb("legacy-encounter-checkpoint"))) {
			String campaign = committedCampaign(engine);
			long campaignId = Ref.id(campaign, Ref.CAMPAIGN);
			engine.sessions().bootstrap(op(), campaign, null);
			Database db = engine.db();
			Map<String, Object> ids = insertEncounter(db, campaignId);
			long encounterId = ((Number) ids.get("encounter")).longValue();
			long logId = ((Number) ids.get("log")).longValue();
			Map<String, Object> encounterBefore = db.read(tx -> tx.get("encounter", encounterId).asMap());
			Map<String, Object> logBefore = db.read(tx -> tx.get("encounter_log", logId).asMap());
			String checkpoint = (String) engine.checkpoints().create(op(), campaign, "schema 13 encounter")
					.get("checkpoint");
			String mutationId = op();
			db.mutate(Database.Mutation.of("test_legacy_encounter_change", campaignId, mutationId, "GM", null), tx -> {
				if (deleted) {
					tx.delete("encounter_log", logId);
					tx.delete("encounter", encounterId);
				} else {
					tx.update("encounter", encounterId, map("round", 2, "record_json", "{\"discarded\":true}"));
					tx.update("encounter_log", logId,
							map("summary", "Discarded branch.", "narration", "A discarded flourish."));
				}
				return Map.of();
			});
			db.mutate(Database.Mutation.of("test_schema13_journal_fixture", campaignId, op(), "GM", null), tx -> {
				var entry = tx.queryOne("SELECT * FROM journal_entry WHERE campaign_id=? AND operation_id=?",
						campaignId, mutationId).orElseThrow();
				List<Object> undo = entry.list("undo_json");
				assertEquals(2, undo.size());
				for (Object value : undo) {
					Map<String, Object> operation = asMap(value);
					Map<String, Object> row = asMap(operation.get("row"));
					if ("encounter".equals(operation.get("table"))) {
						row.remove("record_json");
					} else {
						assertEquals("encounter_log", operation.get("table"));
						row.remove("narration");
					}
				}
				tx.rawUpdate("journal_entry", entry.id(), map("undo_json", Json.write(undo)));
				return Map.of();
			});

			engine.checkpoints().restore(op(), campaign, checkpoint, "return to schema 13 encounter");

			assertEquals(encounterBefore, db.read(tx -> tx.get("encounter", encounterId).asMap()));
			assertEquals(logBefore, db.read(tx -> tx.get("encounter_log", logId).asMap()));
			assertNull(db.read(tx -> tx.get("encounter", encounterId).str("record_json")));
			assertNull(db.read(tx -> tx.get("encounter_log", logId).str("narration")));
		}
	}

	@Test
	void legacyNormalizationLeavesImagesUntouchedAndPreservesExplicitModernValues() throws Exception {
		try (Engine engine = engine(tempDb("legacy-encounter-undo"))) {
			String campaign = committedCampaign(engine);
			long campaignId = Ref.id(campaign, Ref.CAMPAIGN);
			Database db = engine.db();
			Map<String, Object> ids = insertEncounter(db, campaignId);
			for (String table : List.of("encounter", "encounter_log")) {
				long id = ((Number) ids.get("encounter".equals(table) ? "encounter" : "log")).longValue();
				String column = "encounter".equals(table) ? "record_json" : "narration";
				Map<String, Object> row = db.read(tx -> tx.get(table, id).asMap());
				row.remove(column);
				Map<String, Object> undo = map("op", "restore", "table", table, "pk", id, "row", row);
				String original = Json.write(undo);
				db.mutate(Database.Mutation.of("test_apply_schema13_image", campaignId, op(), "GM", null), tx -> {
					tx.update(table, id, map(column, "Discarded value."));
					tx.applyUndo(undo);
					return Map.of();
				});
				assertEquals(original, Json.write(undo), "replay must not rewrite the retained historical image");
				assertNull(db.read(tx -> tx.get(table, id).str(column)));
				for (String explicit : new String[] {"Modern value.", null}) {
					row.put(column, explicit);
					db.mutate(Database.Mutation.of("test_apply_schema14_image", campaignId, op(), "GM", null), tx -> {
						tx.applyUndo(undo);
						return Map.of();
					});
					assertEquals(explicit, db.read(tx -> tx.get(table, id).str(column)));
				}
			}
		}
	}

	private static Map<String, Object> insertEncounter(Database db, long campaignId) {
		return db.mutate(Database.Mutation.of("test_historical_encounter", campaignId, op(), "GM", null), tx -> {
			long encounter = tx.insert("encounter", map("campaign_id", campaignId, "status", "ENDED", "round", 1));
			long log = tx.insert("encounter_log", map("campaign_id", campaignId, "encounter_id", encounter, "round", 1,
					"kind", "TEST", "summary", "The original action."));
			return map("encounter", encounter, "log", log);
		});
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> asMap(Object value) {
		return (Map<String, Object>) value;
	}
}
