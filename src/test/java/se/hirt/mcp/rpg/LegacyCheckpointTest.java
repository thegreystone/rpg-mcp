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

import org.junit.jupiter.api.Test;
import se.hirt.mcp.rpg.persistence.Database;
import se.hirt.mcp.rpg.protocol.Json;
import se.hirt.mcp.rpg.protocol.Ref;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static se.hirt.mcp.rpg.TestCampaigns.map;
import static se.hirt.mcp.rpg.TestCampaigns.op;

/** V005 renamed the biography column, but historical journal before-images keep the old name. */
class LegacyCheckpointTest {

	@Test
	void restoresCharacterBeforeImageWithLegacyBackgroundColumn() throws Exception {
		assertLegacyCharacterRestored(false, "A biography written before V005.");
	}

	@Test
	void reinsertsCharacterBeforeImageWithLegacyBackgroundColumn() throws Exception {
		assertLegacyCharacterRestored(true, "A biography written before V005.");
	}

	@Test
	void restoresExplicitlyNullLegacyBackground() throws Exception {
		assertLegacyCharacterRestored(false, null);
	}

	@Test
	void reinsertsExplicitlyNullLegacyBackground() throws Exception {
		assertLegacyCharacterRestored(true, null);
	}

	private static void assertLegacyCharacterRestored(boolean deleted, String biography) throws Exception {
		try (Engine engine = TestCampaigns.engine(TestCampaigns.tempDb("legacy-checkpoint"))) {
			String campaign = TestCampaigns.committedCampaign(engine);
			long campaignId = Ref.id(campaign, Ref.CAMPAIGN);
			Database db = engine.db();
			engine.sessions().bootstrap(op(), campaign, null);
			long playerId = db.read(
					tx -> tx.queryOne("SELECT id FROM character WHERE campaign_id = ?", campaignId).orElseThrow().id());
			String mechanicalBackground = db.read(tx -> tx.get("character", playerId).str("background_ref"));
			assertNotNull(mechanicalBackground);

			// An earlier legacy before-image survives restoration and must never be rewritten in place.
			String retainedId = op();
			db.mutate(Database.Mutation.of("test_retained_legacy_journal", campaignId, retainedId, "GM", null), tx -> {
				tx.update("character", playerId, map("backstory", biography));
				return Map.of("ok", true);
			});
			String retainedJson = replaceBiographyColumnInJournal(db, campaignId, retainedId);

			// A standalone NPC can be deleted without disturbing the player's dependent character rows.
			long characterId = playerId;
			if (deleted) {
				Map<String, Object> inserted = db
						.mutate(Database.Mutation.of("test_checkpoint_character", campaignId, op(), "GM", null), tx -> {
							long id = tx.insert("character",
									map("campaign_id", campaignId, "lifecycle", "ACTIVE", "life_state", "ALIVE", "name",
											"Mara", "backstory", biography, "created_at", "2026-01-01T00:00:00Z",
											"max_hp", 9, "current_hp", 9));
							return Map.of("id", id);
						});
				characterId = ((Number) inserted.get("id")).longValue();
			}
			long targetId = characterId;
			Map<String, Object> before = db.read(tx -> tx.get("character", targetId).asMap());
			assertEquals(biography, before.get("backstory"));
			String checkpoint = (String) engine.checkpoints().create(op(), campaign, "before V005").get("checkpoint");
			String mutationId = op();
			db.mutate(Database.Mutation.of("test_legacy_character_mutation", campaignId, mutationId, "GM", null),
					tx -> {
						if (deleted) {
							tx.delete("character", targetId);
						} else {
							tx.update("character", targetId,
									map("backstory", "A discarded biography.", "current_hp", 1));
						}
						return Map.of("ok", true);
					});
			replaceBiographyColumnInJournal(db, campaignId, mutationId, deleted);

			engine.checkpoints().restore(op(), campaign, checkpoint, "restore pre-V005 history");

			Map<String, Object> after = db.read(tx -> tx.get("character", targetId).asMap());
			assertEquals(before, after);
			assertEquals(deleted ? null : mechanicalBackground, after.get("background_ref"));
			assertEquals(mechanicalBackground, db.read(tx -> tx.get("character", playerId).str("background_ref")));
			assertEquals(retainedJson, db.read(
					tx -> tx.queryOne("SELECT undo_json FROM journal_entry WHERE campaign_id = ? AND operation_id = ?",
							campaignId, retainedId).orElseThrow().str("undo_json")));
			Map<String, Object> resumed = engine.sessions().bootstrap(op(), campaign, null);
			assertEquals("EXPLORATION", ((Map<?, ?>) resumed.get("meta")).get("harness_state"));
		}
	}

	@Test
	void applyingLegacyUndoDoesNotChangeItsOriginalRow() throws Exception {
		try (Engine engine = TestCampaigns.engine(TestCampaigns.tempDb("legacy-undo-row"))) {
			String campaign = TestCampaigns.committedCampaign(engine);
			long campaignId = Ref.id(campaign, Ref.CAMPAIGN);
			Database db = engine.db();
			Map<String, Object> row = db.read(tx -> tx
					.queryOne("SELECT * FROM character WHERE campaign_id = ?", campaignId).orElseThrow().asMap());
			long characterId = ((Number) row.get("id")).longValue();
			String biography = (String) row.remove("backstory");
			row.put("background", biography);
			Map<String, Object> undo = map("op", "restore", "table", "character", "pk", characterId, "row", row);
			String originalJson = Json.write(undo);

			db.mutate(Database.Mutation.of("test_apply_legacy_undo", campaignId, op(), "GM", null), tx -> {
				tx.applyUndo(undo);
				return Map.of("ok", true);
			});

			assertEquals(originalJson, Json.write(undo));
			assertEquals(biography, db.read(tx -> tx.get("character", characterId).str("backstory")));
		}
	}

	@Test
	void existingBackstoryIncludingExplicitNullTakesPrecedenceOverLegacyBackground() throws Exception {
		try (Engine engine = TestCampaigns.engine(TestCampaigns.tempDb("mixed-undo-row"))) {
			String campaign = TestCampaigns.committedCampaign(engine);
			long campaignId = Ref.id(campaign, Ref.CAMPAIGN);
			Database db = engine.db();
			Map<String, Object> row = db.read(tx -> tx
					.queryOne("SELECT * FROM character WHERE campaign_id = ?", campaignId).orElseThrow().asMap());
			long characterId = ((Number) row.get("id")).longValue();
			Object mechanicalBackground = row.get("background_ref");
			row.put("background", "Stale legacy prose.");
			for (String biography : new String[] {"Modern biography.", null}) {
				row.put("backstory", biography);
				Map<String, Object> undo = map("op", "restore", "table", "character", "pk", characterId, "row", row);
				String originalJson = Json.write(undo);
				db.mutate(Database.Mutation.of("test_apply_mixed_undo", campaignId, op(), "GM", null), tx -> {
					tx.applyUndo(undo);
					return Map.of("ok", true);
				});
				Map<String, Object> restored = db.read(tx -> tx.get("character", characterId).asMap());
				assertEquals(biography, restored.get("backstory"));
				assertEquals(mechanicalBackground, restored.get("background_ref"));
				assertEquals(originalJson, Json.write(undo));
			}
			assertNull(db.read(tx -> tx.get("character", characterId).str("backstory")));
		}
	}

	private static String replaceBiographyColumnInJournal(Database db, long campaignId, String mutationId) {
		return replaceBiographyColumnInJournal(db, campaignId, mutationId, false);
	}

	@SuppressWarnings("unchecked")
	private static String replaceBiographyColumnInJournal(
		Database db, long campaignId, String mutationId, boolean preV005Columns) {
		Map<String, Object> result = db
				.mutate(Database.Mutation.of("test_legacy_journal_fixture", campaignId, op(), "GM", null), tx -> {
					var entry = tx.queryOne(
							"SELECT id, undo_json FROM journal_entry WHERE campaign_id = ? AND operation_id = ?",
							campaignId, mutationId).orElseThrow();
					var undo = Json.readList(entry.str("undo_json"));
					assertEquals(1, undo.size());
					Map<String, Object> operation = (Map<String, Object>) undo.get(0);
					assertEquals("character", operation.get("table"));
					Map<String, Object> row = (Map<String, Object>) operation.get("row");
					assertFalse(row.containsKey("background"));
					row.put("background", row.remove("backstory"));
					if (preV005Columns) {
						// A pre-V005 image naturally omits columns added by later migrations.
						row.remove("background_ref");
						row.remove("armor_class_override");
						row.remove("biography_json");
						row.remove("intimacy_json");
					}
					String legacyJson = Json.write(undo);
					tx.rawUpdate("journal_entry", entry.id(), map("undo_json", legacyJson));
					return Map.of("undo_json", legacyJson);
				});
		return (String) result.get("undo_json");
	}
}
