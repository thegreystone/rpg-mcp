/* Copyright (C) 2026 Marcus Hirt. SPDX-License-Identifier: BSD-3-Clause */
package se.hirt.mcp.rpg;

import io.quarkiverse.mcp.server.TextContent;
import org.junit.jupiter.api.Test;
import se.hirt.mcp.rpg.persistence.Database;
import se.hirt.mcp.rpg.protocol.Json;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static se.hirt.mcp.rpg.TestCampaigns.*;

class ProtocolHintsTest {

	@Test
	void upgradesSavedRecordPointersWithoutChangingHistoricalData() {
		Map<String, Object> saved = map("loot", map("tool", "grant_loot", "source", "ENCOUNTER"), "full_gm_record",
				map("tool", "get_encounter_state", "arguments",
						map("campaign", "campaign:1", "encounter", "encounter:2", "audience", "GM")),
				"summary", "We agreed to grant_loot later.", "operation", "grant_loot");
		String original = Json.write(saved);
		Map<String, Object> delivered = result(ToolSupport.envelope(saved));
		assertEquals(map("tool", "inventory", "action", "GRANT_LOOT", "source", "ENCOUNTER"), delivered.get("loot"));
		assertEquals(map("tool", "encounter", "arguments", map("action", "STATE", "campaign", "campaign:1", "params",
				map("encounter", "encounter:2", "audience", "GM"))), delivered.get("full_gm_record"));
		assertEquals(saved.get("summary"), delivered.get("summary"));
		assertEquals("grant_loot", delivered.get("operation"), "Audit operation identities stay internal");
		assertEquals(original, Json.write(saved), "Reading cannot rewrite persisted historical data");
	}

	@Test
	void upgradesDecisionsAllowedOperationsAndNestedCombatArguments() {
		Map<String, Object> delivered = result(ToolSupport.envelope(map("decision",
				map("tool", "update_character_draft", "path", "changes.skills"), "handoff",
				map("tool", "perform_encounter_action", "arguments",
						map("campaign", "campaign:1", "operation_id", "retry-1", "actor", "character:1", "action",
								map("kind", "ATTACK", "target", "character:2"))),
				"allowed_operations", List.of("get_character_sheet", "perform_encounter_action"), "resolve_with",
				"resolve_pending_choice", "continuation",
				map("options", List.of("restore_checkpoint", "complete_campaign")))));
		assertEquals(map("tool", "character_draft", "action", "UPDATE", "path", "params.changes.skills"),
				delivered.get("decision"));
		assertEquals(List.of("character:SHEET", "encounter:ACTION"), delivered.get("allowed_operations"));
		assertEquals("encounter:RESOLVE_CHOICE", delivered.get("resolve_with"));
		assertEquals(map("options", List.of("checkpoints:RESTORE", "session:COMPLETE")), delivered.get("continuation"));
		assertEquals(
				map("tool", "encounter", "arguments",
						map("action", "ACTION", "campaign", "campaign:1", "operation_id", "retry-1", "params",
								map("actor", "character:1", "action", map("kind", "ATTACK", "target", "character:2")))),
				delivered.get("handoff"));
		assertEquals(delivered, result(ToolSupport.envelope(delivered)), "Already upgraded hints must be stable");
	}

	@Test
	void leavesPlayerTextRuleOptionsAndUnknownToolsAlone() {
		Map<String, Object> payload = map("summary", "grant_loot", "options", List.of("trade", "find"), "tool",
				"Thieves' Tools", "path", "changes.some_story_field", "operation", "trade", "action", "ATTACK");
		assertEquals(payload, result(ToolSupport.envelope(payload)));
	}

	@Test
	void retryOfPreUpgradeMutationKeepsCoinsAndCachedResultUnchanged() throws Exception {
		try (Engine e = engine(tempDb("legacy-response-replay"))) {
			String campaign = committedCampaign(e);
			e.sessions().bootstrap(op(), campaign, null);
			String retry = "pre-upgrade-loot";
			e.inventory().grantLoot(retry, campaign, "character:1", null, map("gp", 3), "QUEST", "Earlier bounty");
			// An old binary persisted its old response metadata in the idempotency journal.
			e.db().mutate(Database.Mutation.of("legacy_test_fixture", 1L, op(), "GM", null), tx -> {
				var row = tx.queryOne("SELECT * FROM journal_entry WHERE operation_id=?", retry).orElseThrow();
				Map<String, Object> saved = row.map("result_json");
				saved.put("meta", map("allowed_operations", List.of("grant_loot", "get_character_sheet")));
				tx.update("journal_entry", row.id(), map("result_json", saved));
				return Map.of();
			});
			long coins = e.db().read(tx -> tx.get("character", 1).lng("money_cp"));
			String saved = e.db()
					.read(tx -> tx.queryOne("SELECT result_json FROM journal_entry WHERE operation_id=?", retry)
							.orElseThrow().str("result_json"));
			RpgTools tools = new RpgTools();
			tools.engine = e;
			var response = tools.inventory("GRANT_LOOT", campaign, retry,
					map("to", "character:1", "money", map("gp", 3), "source", "QUEST", "reason", "Earlier bounty"));
			assertFalse(response.isError(), ((TextContent) response.firstContent()).text());
			Map<String, Object> envelope = Json.readMap(((TextContent) response.firstContent()).text());
			assertEquals(List.of("inventory:GRANT_LOOT", "character:SHEET"),
					((Map<?, ?>) envelope.get("meta")).get("allowed_operations"));
			assertEquals(true, ((Map<?, ?>) envelope.get("result")).get("replayed"));
			assertEquals(coins, e.db().read(tx -> tx.get("character", 1).lng("money_cp")).longValue());
			assertEquals(saved,
					e.db().read(tx -> tx.queryOne("SELECT result_json FROM journal_entry WHERE operation_id=?", retry)
							.orElseThrow().str("result_json")));
		}
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> result(String json) {
		return (Map<String, Object>) Json.readMap(json).get("result");
	}
}
