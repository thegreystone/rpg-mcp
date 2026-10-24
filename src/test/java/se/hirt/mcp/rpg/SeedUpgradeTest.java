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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import se.hirt.mcp.rpg.content.SeedImporter;
import se.hirt.mcp.rpg.persistence.Database;
import se.hirt.mcp.rpg.protocol.ErrorCode;
import se.hirt.mcp.rpg.protocol.Ref;
import se.hirt.mcp.rpg.protocol.RpgException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static se.hirt.mcp.rpg.TestCampaigns.committedCampaign;
import static se.hirt.mcp.rpg.TestCampaigns.engine;
import static se.hirt.mcp.rpg.TestCampaigns.map;
import static se.hirt.mcp.rpg.TestCampaigns.op;
import static se.hirt.mcp.rpg.TestCampaigns.tempDb;

class SeedUpgradeTest {

	private static final String OLDER_VERSION = "older-test-version";
	private static final String HUMAN = "srd5e:species/human";
	private static final String STEALTH = "srd5e:skill/stealth";
	private static final String KEEPSAKE = "homebrew:item/keepsake";

	@Test
	void existingDatabaseAcceptsTheNextEmbeddedSeedVersion() throws Exception {
		Path database = tempDb("seed-version-upgrade");
		try (Engine first = engine(database)) {
			assertTrue(first.rules().find("srd5e:species/human").isPresent());
		}
		try (Database raw = new Database(database)) {
			markOlder(raw, null);
			assertTrue(SeedImporter.importIfMissing(raw, "srd5e"));
		}
		try (Engine resumed = engine(database)) {
			assertTrue(resumed.rules().find(HUMAN).isPresent(), "the upgraded installation starts normally");
		}
	}

	@Test
	void upgradePreservesIdsRefreshesEveryColumnAndSelectsTheLatestVersionOnNewCampaigns() throws Exception {
		Path database = tempDb("seed-upgrade-identities");
		String currentVersion;
		try (Engine first = engine(database)) {
			currentVersion = currentVersion(first.db());
		}
		long journalAfterUpgrade;
		try (Database raw = new Database(database)) {
			Map<String, Object> expectedMetadata = ruleset(raw, currentVersion);
			Map<String, Object> expectedHuman = content(raw, HUMAN);
			Map<String, Map<String, Object>> originalDefinitions = contentRows(raw);
			markOlder(raw, null);
			raw.mutate(fixtureMutation(), tx -> {
				tx.rawExecute(
						"UPDATE installed_ruleset SET license = ?, attribution = ? WHERE namespace = ? AND version = ?",
						"Legacy license", "Legacy attribution", "srd5e", OLDER_VERSION);
				tx.rawUpdate("installed_content", ((Number) expectedHuman.get("id")).longValue(),
						map("kind", "SPELL", "name", "Hooman", "payload_json", "{\"speed\":1}", "cost_cp", 19,
								"weight_g", 20, "spell_level", 9, "cr_times_8", 88, "xp_value", 123, "tags_json",
								"[\"legacy\"]"));
				tx.rawExecute("DELETE FROM installed_content WHERE content_id = ?", STEALTH);
				return Map.of();
			});
			Map<String, Object> oldMetadata = ruleset(raw, OLDER_VERSION);
			assertTrue(SeedImporter.importIfMissing(raw, "srd5e"));
			Map<String, Object> newMetadata = ruleset(raw, currentVersion);
			assertEquals(oldMetadata, ruleset(raw, OLDER_VERSION), "historical metadata remains intact");
			assertNotEquals(oldMetadata.get("id"), newMetadata.get("id"));
			for (String field : List.of("namespace", "version", "license", "attribution")) {
				assertEquals(expectedMetadata.get(field), newMetadata.get(field), field);
			}
			Map<String, Map<String, Object>> upgraded = contentRows(raw);
			assertEquals(originalDefinitions.keySet(), upgraded.keySet(), "the missing definition is reinserted");
			for (var entry : originalDefinitions.entrySet()) {
				if (!STEALTH.equals(entry.getKey())) {
					assertEquals(entry.getValue().get("id"), upgraded.get(entry.getKey()).get("id"), entry.getKey());
				}
				assertEquals(newMetadata.get("id"), upgraded.get(entry.getKey()).get("ruleset_id"), entry.getKey());
			}
			var refreshedHuman = new LinkedHashMap<>(expectedHuman);
			refreshedHuman.put("ruleset_id", newMetadata.get("id"));
			assertEquals(refreshedHuman, content(raw, HUMAN), "all indexed columns and tags refresh with the payload");
			Map<String, Object> installed = installedSnapshot(raw);
			journalAfterUpgrade = journalCount(raw);
			assertFalse(SeedImporter.importIfMissing(raw, "srd5e"));
			assertEquals(installed, installedSnapshot(raw));
			assertEquals(journalAfterUpgrade, journalCount(raw), "unchanged startup opens no seed mutation");
		}
		try (Engine restarted = engine(database)) {
			assertEquals(journalAfterUpgrade, journalCount(restarted.db()));
			assertEquals(currentVersion, restarted.rules().rulesets().getFirst().get("version"));
			assertCampaignVersion(restarted, null, currentVersion);
			assertCampaignVersion(restarted, "srd5e", currentVersion);
			assertCampaignVersion(restarted, "srd5e:" + OLDER_VERSION, OLDER_VERSION);
		}
	}

	@Test
	void olderCampaignResumesAndRestoresItsCheckpointWithoutRewritingItsVersion() throws Exception {
		Path database = tempDb("seed-upgrade-campaign");
		String campaign;
		String checkpoint;
		Map<String, Object> originalCharacter;
		try (Engine first = engine(database)) {
			campaign = committedCampaign(first);
			first.sessions().bootstrap(op(), campaign, null);
			markOlder(first.db(), campaign);
			first.runtime().applyRuntimeChange(op(), campaign, "character:1",
					map("kind", "SET_TEMP_HP", "amount", 7, "reason", "protection before the checkpoint"));
			originalCharacter = first.db().read(tx -> tx.get("character", 1).asMap());
			checkpoint = (String) first.checkpoints().create(op(), campaign, "before a second blessing")
					.get("checkpoint");
			first.runtime().applyRuntimeChange(op(), campaign, "character:1",
					map("kind", "SET_TEMP_HP", "amount", 9, "reason", "a later blessing"));
		}
		try (Engine resumed = engine(database)) {
			assertEquals(OLDER_VERSION, campaignVersion(resumed.db(), campaign));
			assertEquals("BOOTSTRAP_SESSION", resumed.campaigns().open(campaign).get("resume"));
			resumed.sessions().bootstrap(op(), campaign, null);
			assertEquals(9,
					m(resumed.characters().characterSheet(campaign, "character:1", "PLAY").get("hp")).get("temp"));
			resumed.checkpoints().restore(op(), campaign, checkpoint, "restore the pre-upgrade checkpoint");
			assertEquals(originalCharacter, resumed.db().read(tx -> tx.get("character", 1).asMap()));
			assertEquals(OLDER_VERSION, campaignVersion(resumed.db(), campaign));
			Map<String, Object> damage = resumed.runtime().applyRuntimeChange(op(), campaign, "character:1", map("kind",
					"DAMAGE", "amount", 2, "damage_type", "fire", "reason", "play continues after upgrade"));
			assertEquals(2, damage.get("temp_hp_absorbed"));
			assertEquals(5,
					m(resumed.characters().characterSheet(campaign, "character:1", "PLAY").get("hp")).get("temp"));
			assertEquals(OLDER_VERSION, campaignVersion(resumed.db(), campaign));
			assertTrue(resumed.rules().find(HUMAN).isPresent());
		}
	}

	@Test
	void unrelatedNamespacesAndDefinitionsRemovedFromTheSeedRemainAvailable() throws Exception {
		Path database = tempDb("seed-upgrade-preservation");
		try (Engine first = engine(database)) {
			assertTrue(first.rules().find(HUMAN).isPresent());
		}
		Map<String, Object> other;
		Map<String, Object> retired;
		try (Database raw = new Database(database)) {
			markOlder(raw, null);
			other = addOtherNamespace(raw);
			long oldRulesetId = ((Number) ruleset(raw, OLDER_VERSION).get("id")).longValue();
			raw.mutate(fixtureMutation(), tx -> {
				tx.rawInsert("installed_content", map("content_id", "srd5e:item/retired-keepsake", "ruleset_id",
						oldRulesetId, "kind", "ITEM", "name", "Retired Keepsake", "payload_json", "{\"cost_cp\":2}"));
				return Map.of();
			});
			retired = content(raw, "srd5e:item/retired-keepsake");
			assertTrue(SeedImporter.importIfMissing(raw, "srd5e"));
			assertEquals(retired, content(raw, "srd5e:item/retired-keepsake"));
			assertOtherNamespaceUnchanged(raw, other);
		}
		try (Engine restarted = engine(database)) {
			assertTrue(restarted.rules().find("srd5e:item/retired-keepsake").isPresent());
			assertTrue(restarted.rules().find(KEEPSAKE).isPresent());
			assertEquals(retired, content(restarted.db(), "srd5e:item/retired-keepsake"));
			assertOtherNamespaceUnchanged(restarted.db(), other);
		}
	}

	@Test
	void failedUpgradeRollsBackMetadataAndDefinitionsAndCanBeRetried() throws Exception {
		Path database = tempDb("seed-upgrade-rollback");
		try (Engine first = engine(database)) {
			assertTrue(first.rules().find(HUMAN).isPresent());
		}
		try (Database raw = new Database(database)) {
			markOlder(raw, null);
			Map<String, Object> other = addOtherNamespace(raw);
			raw.mutate(fixtureMutation(), tx -> {
				tx.rawExecute("UPDATE installed_content SET name = ? WHERE content_id = ?", "Hooman", HUMAN);
				tx.rawExecute("CREATE TRIGGER reject_seed_upgrade BEFORE UPDATE ON installed_content "
						+ "WHEN NEW.content_id = 'srd5e:species/human' BEGIN SELECT RAISE(ABORT, 'seed upgrade fixture failure'); END");
				return Map.of();
			});
			Map<String, Object> before = installedSnapshot(raw);
			long journalBefore = journalCount(raw);
			RpgException failed = assertThrows(RpgException.class, () -> SeedImporter.importIfMissing(raw, "srd5e"));
			assertEquals(ErrorCode.INTERNAL_ERROR, failed.code());
			assertEquals(before, installedSnapshot(raw),
					"the new metadata and all earlier definition writes roll back");
			assertEquals(journalBefore, journalCount(raw), "a failed upgrade leaves no journal entry");
			assertOtherNamespaceUnchanged(raw, other);
			raw.mutate(fixtureMutation(), tx -> {
				tx.rawExecute("DROP TRIGGER reject_seed_upgrade");
				return Map.of();
			});
			assertTrue(SeedImporter.importIfMissing(raw, "srd5e"));
			assertEquals("Human", content(raw, HUMAN).get("name"));
			assertOtherNamespaceUnchanged(raw, other);
		}
	}

	@Test
	void upgradeRejectsContentOwnedByAnotherNamespaceWithoutChangingEitherInstallation() throws Exception {
		Path database = tempDb("seed-upgrade-ownership");
		try (Engine first = engine(database)) {
			assertTrue(first.rules().find(HUMAN).isPresent());
		}
		try (Database raw = new Database(database)) {
			markOlder(raw, null);
			Map<String, Object> other = addOtherNamespace(raw);
			long otherRulesetId = ((Number) m(other.get("ruleset")).get("id")).longValue();
			raw.mutate(fixtureMutation(), tx -> {
				tx.rawExecute("UPDATE installed_content SET ruleset_id = ? WHERE content_id = ?", otherRulesetId,
						HUMAN);
				return Map.of();
			});
			Map<String, Object> before = installedSnapshot(raw);
			long journalBefore = journalCount(raw);
			RpgException collision = assertThrows(RpgException.class, () -> SeedImporter.importIfMissing(raw, "srd5e"));
			assertEquals(ErrorCode.INVALID_ARGUMENT, collision.code());
			assertEquals(before, installedSnapshot(raw));
			assertEquals(journalBefore, journalCount(raw));
			assertOtherNamespaceUnchanged(raw, other);
		}
	}

	private static void markOlder(Database database, String campaign) {
		database.mutate(fixtureMutation(), tx -> {
			tx.rawExecute("UPDATE installed_ruleset SET version = ? WHERE namespace = ?", OLDER_VERSION, "srd5e");
			if (campaign != null) {
				tx.rawUpdate("campaign", Ref.id(campaign, Ref.CAMPAIGN), Map.of("ruleset_version", OLDER_VERSION));
			}
			return Map.of();
		});
	}

	private static Database.Mutation fixtureMutation() {
		return Database.Mutation.of("seed_upgrade_fixture", null, op(), "ADMINISTRATIVE_OVERRIDE", null);
	}

	private static String currentVersion(Database database) {
		return database.read(tx -> tx
				.queryOne("SELECT version FROM installed_ruleset WHERE namespace = ? ORDER BY id DESC LIMIT 1", "srd5e")
				.orElseThrow().str("version"));
	}

	private static Map<String, Object> ruleset(Database database, String version) {
		return database.read(tx -> tx
				.queryOne("SELECT * FROM installed_ruleset WHERE namespace = ? AND version = ?", "srd5e", version)
				.orElseThrow().asMap());
	}

	private static Map<String, Object> content(Database database, String contentId) {
		return database.read(tx -> tx.queryOne("SELECT * FROM installed_content WHERE content_id = ?", contentId)
				.orElseThrow().asMap());
	}

	private static Map<String, Map<String, Object>> contentRows(Database database) {
		return database.read(tx -> {
			var rows = new LinkedHashMap<String, Map<String, Object>>();
			for (var row : tx.query("SELECT * FROM installed_content ORDER BY id")) {
				rows.put(row.str("content_id"), row.asMap());
			}
			return rows;
		});
	}

	private static Map<String, Object> installedSnapshot(Database database) {
		return database.read(tx -> map("rulesets",
				tx.query("SELECT * FROM installed_ruleset ORDER BY id").stream().map(r -> r.asMap()).toList(),
				"content",
				tx.query("SELECT * FROM installed_content ORDER BY id").stream().map(r -> r.asMap()).toList()));
	}

	private static long journalCount(Database database) {
		return database.read(tx -> tx.count("SELECT COUNT(*) FROM journal_entry"));
	}

	private static void assertCampaignVersion(Engine engine, String requested, String expectedVersion) {
		String campaign = (String) engine.campaigns().create(op(), null, requested).get("campaign");
		assertEquals(expectedVersion, campaignVersion(engine.db(), campaign));
	}

	private static String campaignVersion(Database database, String campaign) {
		return database.read(tx -> tx.get("campaign", Ref.id(campaign, Ref.CAMPAIGN)).str("ruleset_version"));
	}

	private static Map<String, Object> addOtherNamespace(Database database) {
		return database.mutate(fixtureMutation(), tx -> {
			long rulesetId = tx.rawInsert("installed_ruleset", map("namespace", "homebrew", "version", "1",
					"imported_at", "2026-01-01T00:00:00Z", "license", "Private", "attribution", "Campaign author"));
			long contentId = tx.rawInsert("installed_content", map("content_id", KEEPSAKE, "ruleset_id", rulesetId,
					"kind", "ITEM", "name", "Keepsake", "payload_json", "{\"cost_cp\":1}"));
			return map("ruleset", tx.get("installed_ruleset", rulesetId).asMap(), "content",
					tx.get("installed_content", contentId).asMap());
		});
	}

	private static void assertOtherNamespaceUnchanged(Database database, Map<String, Object> other) {
		assertEquals(other.get("content"), content(database, KEEPSAKE));
		long rulesetId = ((Number) m(other.get("ruleset")).get("id")).longValue();
		Map<String, Object> stored = database.read(tx -> tx.get("installed_ruleset", rulesetId).asMap());
		assertEquals(other.get("ruleset"), stored);
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> m(Object value) {
		return (Map<String, Object>) value;
	}
}
