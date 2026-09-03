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
import se.hirt.mcp.rpg.persistence.Tables;
import se.hirt.mcp.rpg.protocol.ErrorCode;
import se.hirt.mcp.rpg.protocol.Ref;
import se.hirt.mcp.rpg.protocol.RpgException;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static se.hirt.mcp.rpg.TestCampaigns.op;

/**
 * The one exact-equality state test in the suite (DOMAIN_MODEL.md I-51, MCP_PROTOCOL.md §25.9): create checkpoint →
 * mutate every rewindable table → restore → assert exact equality with the snapshot.
 */
class CheckpointRoundTripTest {

	@SuppressWarnings("unchecked")
	private static Map<String, Object> m(Object o) {
		return (Map<String, Object>) o;
	}

	@Test
	void restoreReturnsEveryRewindableTableToTheCheckpoint() throws Exception {
		try (Engine engine = TestCampaigns.engine(TestCampaigns.tempDb("checkpoint"))) {
			String campaignRef = TestCampaigns.committedCampaign(engine);
			long campaignId = Ref.id(campaignRef, Ref.CAMPAIGN);
			Database db = engine.db();

			engine.sessions().bootstrap(op(), campaignRef, null);
			engine.sessions().advanceTime(op(), campaignRef, 30, "settling in");
			Map<String, Object> cp = engine.checkpoints().create(op(), campaignRef, "MAJOR_ENCOUNTER");
			String checkpoint = (String) cp.get("checkpoint");
			Map<String, List<Map<String, Object>>> before = db.snapshot(campaignId);
			assertFalse(before.get("character").isEmpty());

			// A battery of mutations through the public services …
			String pc = Ref.of(Ref.CHARACTER,
					before.get("character").get(0).get("id") instanceof Number n ? n.longValue() : 1);
			engine.checks().resolveCheck(op(), campaignRef, pc, "ABILITY_CHECK", "CHA", null, 12, null, "bluffing");
			engine.ledger()
					.record(op(), campaignRef, "PROMISE", "Richard promised to return the notebook.", List.of(pc),
							"CRITICAL", "PARTY_KNOWN", "GM", "Day 1, 12:00", null, "He meant it at the time.",
							Map.of("k", "v"));
			engine.sessions().advanceTime(op(), campaignRef, 3 * 24 * 60, "travel");
			engine.sessions().suspend(op(), campaignRef, "A doomed detour.");
			engine.sessions().bootstrap(op(), campaignRef, null);

			// … plus direct journaled writes covering every rewindable table the slice's tools do not yet touch.
			db.mutate(Database.Mutation.of("test_battery", campaignId, op(), "GM", null), tx -> {
				long charId = Ref.id(pc, Ref.CHARACTER);
				long locId = tx.insert("location",
						cols("campaign_id", campaignId, "kind", "SITE", "name", "Ruined tower", "materialization",
								"SEMANTIC", "revision", 0));
				long homeId = tx.queryOne("SELECT current_location_id AS l FROM campaign WHERE id = ?", campaignId)
						.orElseThrow().lng("l");
				tx.insert("location_connection",
						cols("campaign_id", campaignId, "location_a_id", Math.min(homeId, locId), "location_b_id",
								Math.max(homeId, locId), "kind", "road"));
				long customId = tx.insert("custom_content",
						cols("campaign_id", campaignId, "kind", "ITEM", "symbolic_id", "custom:item/broadsheet", "name",
								"Bellhaven Broadsheet", "payload_json", "{}", "cost_cp", 2, "provenance", "GM",
								"revision", 0, "created_at", Instant.now().toString()));
				tx.insert("inventory_entry",
						cols("campaign_id", campaignId, "character_id", charId, "content_ref_kind", "CUSTOM",
								"custom_content_id", customId, "quantity", 1));
				long seedId = tx.insert("director_seed",
						cols("campaign_id", campaignId, "visibility", "DIRECTOR_ONLY", "provenance", "DIRECTOR",
								"created_at", Instant.now().toString(), "kind", "COMPANION_INTRO", "state", "OPEN"));
				long npcId = tx.insert("character",
						cols("campaign_id", campaignId, "lifecycle", "ACTIVE", "life_state", "ALIVE", "name", "Mara",
								"origin_seed_id", seedId, "created_at", Instant.now().toString(), "max_hp", 9,
								"current_hp", 9));
				tx.insert("character_class", cols("character_id", npcId, "class_ref", "srd5e:class/rogue", "level", 1));
				tx.insert("character_trait",
						cols("character_id", npcId, "kind", "SKILL", "content_ref", "srd5e:skill/stealth"));
				tx.insert("party_membership",
						cols("campaign_id", campaignId, "character_id", npcId, "state", "ACTIVE", "joined_seq", 0));
				long relId = tx.insert("relationship",
						cols("campaign_id", campaignId, "from_character_id", charId, "to_character_id", npcId,
								"summary", "wary allies", "revision", 0));
				long eventId = tx.queryOne("SELECT MAX(id) AS id FROM event WHERE campaign_id = ?", campaignId)
						.orElseThrow().lng("id");
				tx.insert("relationship_event", cols("relationship_id", relId, "event_id", eventId));
				tx.insert("event_causal", cols("event_id", eventId, "caused_by_event_id", eventId));
				tx.insert("active_effect",
						cols("campaign_id", campaignId, "character_id", charId, "provenance", "GM", "condition_ref",
								"srd5e:condition/poisoned", "start_seq", 0));
				tx.insert("resource_state",
						cols("character_id", charId, "resource_ref", "test:widget", "current", 1, "max", 2));
				long encId = tx.insert("encounter",
						cols("campaign_id", campaignId, "status", "RUNNING", "round", 1, "revision", 0));
				tx.insert("encounter_participant",
						cols("encounter_id", encId, "character_id", charId, "side", "party", "status", "ACTIVE"));
				tx.insert("encounter_log",
						cols("campaign_id", campaignId, "encounter_id", encId, "round", 1, "kind", "TEST", "summary",
								"battery"));
				tx.insert("quest",
						cols("campaign_id", campaignId, "visibility", "PLAYER_KNOWN", "provenance", "GM", "created_at",
								Instant.now().toString(), "title", "Find the notebook", "status", "ACCEPTED"));
				tx.insert("faction",
						cols("campaign_id", campaignId, "visibility", "GM_ONLY", "provenance", "DIRECTOR", "created_at",
								Instant.now().toString(), "name", "River Guild"));
				tx.insert("world_event",
						cols("campaign_id", campaignId, "visibility", "GM_ONLY", "provenance", "DIRECTOR", "created_at",
								Instant.now().toString(), "title", "Third caravan lost"));
				tx.insert("pending_transaction",
						cols("campaign_id", campaignId, "kind", "LEVEL_UP", "status", "ABANDONED", "revision", 0));
				tx.insert("player_control_assignment",
						cols("campaign_id", campaignId, "seat", "player-2", "character_id", npcId, "active", 0));
				// Updates and deletes on existing rows.
				tx.update("character", charId, cols("current_hp", 1, "money_cp", 1, "name", "Renamed", "xp", 300));
				long beatId = tx.queryOne("SELECT id FROM story_beat WHERE campaign_id = ?", campaignId).orElseThrow()
						.id();
				tx.update("story_beat", beatId, cols("state", "COMPLETED"));
				tx.update("policy_state",
						tx.queryOne("SELECT id FROM policy_state WHERE campaign_id = ?", campaignId).orElseThrow().id(),
						cols("content_profile", "PEGI_12"));
				long draftId = tx.queryOne("SELECT id FROM campaign_setup_draft WHERE campaign_id = ?", campaignId)
						.orElseThrow().id();
				tx.update("campaign_setup_draft", draftId, cols("payload_json", "{\"tampered\":true}"));
				for (var t : tx.query("SELECT id FROM character_trait WHERE character_id = ? AND kind = 'SAVE'",
						charId)) {
					tx.delete("character_trait", t.id());
				}
				tx.update("campaign", campaignId, cols("title", "Mutated", "harness_state", "CHECKPOINT_DECISION"));
				return new LinkedHashMap<>(Map.of("ok", true));
			});

			Map<String, List<Map<String, Object>>> mutated = db.snapshot(campaignId);
			for (String table : Tables.REWINDABLE) {
				assertNotEquals(before.get(table), mutated.get(table), "battery must touch " + table);
			}

			Map<String, Object> restored = engine.checkpoints().restore(op(), campaignRef, checkpoint, "regret");
			assertEquals(Boolean.TRUE, restored.get("requires_context_reset"));
			assertTrue(((Integer) restored.get("discarded_journal_entries")) >= 6);

			Map<String, List<Map<String, Object>>> after = db.snapshot(campaignId);
			for (String table : Tables.REWINDABLE) {
				assertEquals(before.get(table), after.get(table),
						"table " + table + " must match the checkpoint exactly");
			}

			// Audit lineage survives; the checkpoint is marked; later checkpoints would be invalidated.
			long audits = db.read(tx -> tx.count(
					"SELECT COUNT(*) FROM audit_record WHERE campaign_id = ? AND kind IN ('CHECKPOINT_RESTORE','DISCARDED_BRANCH')",
					campaignId));
			assertEquals(2, audits);
			String status = db.read(tx -> tx.get("checkpoint", Ref.id(checkpoint, Ref.CHECKPOINT)).str("status"));
			assertEquals("RESTORED_TO", status);

			// The campaign remains playable and the restore can be repeated.
			Map<String, Object> ctx = engine.sessions().bootstrap(op(), campaignRef, null);
			assertEquals("Day 1, 17:10", m(ctx.get("game_time")).get("instant"));
			engine.sessions().advanceTime(op(), campaignRef, 5, "again");
			engine.checkpoints().restore(op(), campaignRef, checkpoint, "again");
			assertEquals(before.get("game_clock"), db.snapshot(campaignId).get("game_clock"));
		}
	}

	@Test
	void ironmanDeniesCheckpoints() throws Exception {
		try (Engine engine = TestCampaigns.engine(TestCampaigns.tempDb("ironman"))) {
			String campaign = (String) engine.campaigns().create(op(), null, null).get("campaign");
			engine.campaigns().updateSetup(op(), campaign, null,
					TestCampaigns.map("content_profile", "PEGI_16", "experience", "SURPRISE_ME", "rules", Map.of(),
							"continuation", "IRONMAN"));
			String pc = (String) engine.characters().createDraft(op(), campaign,
					TestCampaigns.map("name", "Solo", "species", "Dwarf", "class", "Fighter", "ability_scores",
							TestCampaigns.map("STR", 15, "CON", 14, "DEX", 13, "WIS", 12, "INT", 10, "CHA", 8),
							"background", "Criminal", "background_ability_scores",
							TestCampaigns.map("DEX", 2, "CON", 1), "skills", List.of("Athletics", "Perception"),
							"personality", "Grim."), true).get("character");
			engine.characters().commitDraft(op(), campaign, pc, null);
			engine.campaigns().updateSetup(op(), campaign, null, TestCampaigns.map("party", "SURPRISE_ME", "adventure",
					TestCampaigns.map("premise", "x", "opening_location", "Camp", "immediate_goal", "survive")));
			engine.campaigns().commitSetup(op(), campaign, null);
			engine.sessions().bootstrap(op(), campaign, null);
			RpgException e = assertThrows(RpgException.class, () -> engine.checkpoints().create(op(), campaign, "x"));
			assertEquals(ErrorCode.POLICY_DENIED, e.code());
			assertEquals(2, ((List<?>) engine.checkpoints().options(campaign).get("options")).size());
		}
	}

	private static Map<String, Object> cols(Object... kv) {
		return TestCampaigns.map(kv);
	}
}
