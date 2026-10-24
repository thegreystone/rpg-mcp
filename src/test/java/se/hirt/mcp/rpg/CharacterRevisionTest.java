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
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import se.hirt.mcp.rpg.persistence.Database;
import se.hirt.mcp.rpg.protocol.ErrorCode;
import se.hirt.mcp.rpg.protocol.Ref;
import se.hirt.mcp.rpg.protocol.RpgException;
import se.hirt.mcp.rpg.rules.Effects;

import static org.junit.jupiter.api.Assertions.*;
import static se.hirt.mcp.rpg.TestCampaigns.committedCampaign;
import static se.hirt.mcp.rpg.TestCampaigns.engine;
import static se.hirt.mcp.rpg.TestCampaigns.map;
import static se.hirt.mcp.rpg.TestCampaigns.op;
import static se.hirt.mcp.rpg.TestCampaigns.tempDb;

class CharacterRevisionTest {

	private static final String PC = "character:1";

	@Test
	void inventoryAndConditionChangesAdvanceTheCharacterRevision() throws Exception {
		try (Engine engine = engine(tempDb("character-revision-baseline"))) {
			String campaign = campaign(engine);
			long beforeLoot = revision(engine, PC);
			Map<String, Object> granted = engine.inventory().grantLoot(op(), campaign, PC,
					List.of(map("item", "Shield")), null, "QUEST", "a shield recovered from the ruin");
			assertRevision(engine, campaign, PC, beforeLoot + 1);
			String entry = (String) list(granted.get("granted")).getFirst().get("entry");
			engine.inventory().equip(op(), campaign, PC, entry, true);
			assertRevision(engine, campaign, PC, beforeLoot + 2);
			engine.runtime().applyRuntimeChange(op(), campaign, PC,
					map("kind", "ADD_CONDITION", "condition", "PRONE", "reason", "knocked to the ground"));
			assertRevision(engine, campaign, PC, beforeLoot + 3);
		}
	}

	@Test
	void batchLootAndStackMergesAdvanceOnlyTheRecipientOnce() throws Exception {
		try (Engine engine = engine(tempDb("character-revision-loot"))) {
			String campaign = campaign(engine);
			String observer = npc(engine, campaign, "The observer");
			long before = revision(engine, PC);
			long observerBefore = revision(engine, observer);
			Map<String, Object> granted = engine.inventory().grantLoot(op(), campaign, PC,
					List.of(map("item", "Torch", "quantity", 2), map("item", "Shield")), null, "QUEST", "supplies");
			String torch = (String) list(granted.get("granted")).getFirst().get("entry");
			assertRevision(engine, campaign, PC, before + 1);
			assertRevision(engine, campaign, observer, observerBefore);

			String merged = grant(engine, campaign, PC, "Torch", 3);
			assertEquals(torch, merged);
			assertEquals(5, quantity(engine, torch));
			assertRevision(engine, campaign, PC, before + 2);

			long money = ((Number) characterRow(engine, PC).get("money_cp")).longValue();
			engine.inventory().grantLoot(op(), campaign, PC, List.of(map("item", "Dagger")), "2 gp", "QUEST",
					"a reward of coin and equipment");
			assertTrue(revision(engine, PC) >= before + 3);
			assertEquals(money + 200, ((Number) characterRow(engine, PC).get("money_cp")).longValue());
			assertRevision(engine, campaign, PC, revision(engine, PC));
			assertRevision(engine, campaign, observer, observerBefore);
		}
	}

	@Test
	void equippingASplitStackCoalescesWritesAndRepeatedEquipmentChoicesAreNoOps() throws Exception {
		try (Engine engine = engine(tempDb("character-revision-equip"))) {
			String campaign = campaign(engine);
			String stack = grant(engine, campaign, PC, "Dagger", 2);
			long before = revision(engine, PC);
			String equipped = (String) engine.inventory().equip(op(), campaign, PC, stack, true).get("entry");
			assertNotEquals(stack, equipped);
			assertEquals(1, quantity(engine, stack));
			assertEquals(1, quantity(engine, equipped));
			assertRevision(engine, campaign, PC, before + 1);

			engine.inventory().equip(op(), campaign, PC, equipped, true);
			assertRevision(engine, campaign, PC, before + 1);
			engine.inventory().equip(op(), campaign, PC, equipped, false);
			assertRevision(engine, campaign, PC, before + 2);
			engine.inventory().equip(op(), campaign, PC, equipped, false);
			assertRevision(engine, campaign, PC, before + 2);
		}
	}

	@Test
	void wholeStackTransfersInvalidateBothOwnersAndGroundHasNoCharacterRevision() throws Exception {
		try (Engine engine = engine(tempDb("character-revision-transfer"))) {
			String campaign = campaign(engine);
			String receiver = npc(engine, campaign, "The receiver");
			String observer = npc(engine, campaign, "The observer");
			String ground = (String) m(engine.sessions().party(campaign, "SUMMARY").get("location")).get("ref");
			String entry = grant(engine, campaign, PC, "Torch", 2);
			long pcBefore = revision(engine, PC);
			long receiverBefore = revision(engine, receiver);
			long observerBefore = revision(engine, observer);

			String carried = (String) engine.inventory().transfer(op(), campaign, entry, receiver, null, "hand over")
					.get("entry");
			assertRevision(engine, campaign, PC, pcBefore + 1);
			assertRevision(engine, campaign, receiver, receiverBefore + 1);
			String dropped = (String) engine.inventory().transfer(op(), campaign, carried, ground, null, "put down")
					.get("entry");
			assertRevision(engine, campaign, PC, pcBefore + 1);
			assertRevision(engine, campaign, receiver, receiverBefore + 2);
			engine.inventory().transfer(op(), campaign, dropped, PC, null, "pick up");
			assertRevision(engine, campaign, PC, pcBefore + 2);
			assertRevision(engine, campaign, receiver, receiverBefore + 2);
			assertRevision(engine, campaign, observer, observerBefore);
		}
	}

	@Test
	void partialTransfersAndDestinationMergesAdvanceEachOwnerOnce() throws Exception {
		try (Engine engine = engine(tempDb("character-revision-partial-transfer"))) {
			String campaign = campaign(engine);
			String receiver = npc(engine, campaign, "The receiver");
			String source = grant(engine, campaign, PC, "Torch", 5);
			String destination = grant(engine, campaign, receiver, "Torch", 2);
			long pcBefore = revision(engine, PC);
			long receiverBefore = revision(engine, receiver);

			Map<String, Object> result = engine.inventory().transfer(op(), campaign, source, receiver, 2,
					"share supplies");

			assertEquals(destination, result.get("entry"));
			assertEquals(3, quantity(engine, source));
			assertEquals(4, quantity(engine, destination));
			assertRevision(engine, campaign, PC, pcBefore + 1);
			assertRevision(engine, campaign, receiver, receiverBefore + 1);
		}
	}

	@Test
	void conditionChangesAdvanceRevisionButRepeatedAddsAndRemovalsDoNot() throws Exception {
		try (Engine engine = engine(tempDb("character-revision-conditions"))) {
			String campaign = campaign(engine);
			long before = revision(engine, PC);
			changeCondition(engine, campaign, PC, "ADD_CONDITION", "PRONE", null);
			assertRevision(engine, campaign, PC, before + 1);
			assertEquals(true,
					changeCondition(engine, campaign, PC, "ADD_CONDITION", "PRONE", null).get("already_present"));
			assertRevision(engine, campaign, PC, before + 1);
			assertEquals(1, changeCondition(engine, campaign, PC, "REMOVE_CONDITION", "PRONE", null).get("removed"));
			assertRevision(engine, campaign, PC, before + 2);
			assertEquals(0, changeCondition(engine, campaign, PC, "REMOVE_CONDITION", "PRONE", null).get("removed"));
			assertRevision(engine, campaign, PC, before + 2);
		}
	}

	@Test
	void multipleTimedEffectsExpiringTogetherAdvanceTheOwnerOnce() throws Exception {
		try (Engine engine = engine(tempDb("character-revision-expiry"))) {
			String campaign = campaign(engine);
			String observer = npc(engine, campaign, "The observer");
			changeCondition(engine, campaign, PC, "ADD_CONDITION", "PRONE", "1 minute");
			changeCondition(engine, campaign, PC, "ADD_CONDITION", "POISONED", "1 minute");
			long before = revision(engine, PC);
			long observerBefore = revision(engine, observer);

			engine.sessions().advanceTime(op(), campaign, 1, "wait for the effects to end");

			assertRevision(engine, campaign, PC, before + 1);
			assertRevision(engine, campaign, observer, observerBefore);
			assertEquals(0L, engine.db()
					.read(tx -> tx.count("SELECT COUNT(*) FROM active_effect WHERE character_id = ?", 1)).longValue());
			assertEquals(List.of(), engine.characters().characterSheet(campaign, PC, "FULL").get("conditions"));
		}
	}

	@Test
	void effectRecipientsAndConcentrationOwnersAreTrackedAcrossLinkChangesAndExpiry() throws Exception {
		try (Engine engine = engine(tempDb("character-revision-effect-owners"))) {
			String campaign = campaign(engine);
			String recipient = npc(engine, campaign, "The recipient");
			String source = npc(engine, campaign, "The source");
			long campaignId = Ref.id(campaign, Ref.CAMPAIGN);
			long recipientId = Ref.id(recipient, Ref.CHARACTER);
			long sourceId = Ref.id(source, Ref.CHARACTER);
			long pcBefore = revision(engine, PC);
			long recipientBefore = revision(engine, recipient);
			long sourceBefore = revision(engine, source);
			long effect = ((Number) engine.db().mutate(mutation(campaign, op()), tx -> map("effect",
					Effects.add(tx, campaignId, recipientId, sourceId, null, "a concentrated ward", null,
							Map.of("ac_bonus", 1), Effects.minutes(tx, campaignId, 1, "1 minute"), 1L, null, "GM")))
					.get("effect")).longValue();
			assertRevision(engine, campaign, PC, pcBefore + 1);
			assertRevision(engine, campaign, recipient, recipientBefore + 1);
			assertRevision(engine, campaign, source, sourceBefore);

			engine.db().mutate(mutation(campaign, op()), tx -> {
				tx.update("active_effect", effect,
						map("character_id", sourceId, "concentration_character_id", recipientId));
				return Map.of("moved", true);
			});
			assertRevision(engine, campaign, PC, pcBefore + 2);
			assertRevision(engine, campaign, recipient, recipientBefore + 2);
			assertRevision(engine, campaign, source, sourceBefore + 1);

			engine.sessions().advanceTime(op(), campaign, 1, "the ward expires");
			assertRevision(engine, campaign, PC, pcBefore + 2);
			assertRevision(engine, campaign, recipient, recipientBefore + 3);
			assertRevision(engine, campaign, source, sourceBefore + 2);
			assertTrue(engine.db().read(tx -> tx.find("active_effect", effect).isEmpty()).booleanValue());
		}
	}

	@Test
	void inventoryAndConditionChangesInvalidateStaleCharacterEdits() throws Exception {
		try (Engine engine = engine(tempDb("character-revision-stale-guard"))) {
			String campaign = campaign(engine);
			long stale = revision(engine, PC);
			grant(engine, campaign, PC, "Shield", 1);
			Map<String, List<Map<String, Object>>> beforeRejected = snapshot(engine, campaign);
			RpgException rejected = assertThrows(RpgException.class, () -> engine.characters().updateCharacter(op(),
					campaign, PC, stale, map("appearance", "An edit based on the old sheet")));
			assertEquals(ErrorCode.CONFLICT, rejected.code());
			assertEquals(beforeRejected, snapshot(engine, campaign));

			long current = revision(engine, PC);
			Map<String, Object> updated = engine.characters().updateCharacter(op(), campaign, PC, current,
					map("appearance", "Carrying a newly acquired shield"));
			assertRevision(engine, campaign, PC, current + 1);
			assertEquals(current + 1, ((Number) updated.get("revision")).longValue());
			assertEquals(current + 1, ((Number) m(updated.get("sheet")).get("revision")).longValue());
			long beforeCondition = revision(engine, PC);
			changeCondition(engine, campaign, PC, "ADD_CONDITION", "PRONE", null);
			RpgException conditionConflict = assertThrows(RpgException.class, () -> engine.characters()
					.updateCharacter(op(), campaign, PC, beforeCondition, map("appearance", "Another stale edit")));
			assertEquals(ErrorCode.CONFLICT, conditionConflict.code());
			assertRevision(engine, campaign, PC, beforeCondition + 1);
		}
	}

	@Test
	void rejectedOperationsRollBackChildChangesAndAutomaticRevisionWrites() throws Exception {
		try (Engine engine = engine(tempDb("character-revision-rollback"))) {
			String campaign = campaign(engine);
			long before = revision(engine, PC);
			Map<String, List<Map<String, Object>>> original = snapshot(engine, campaign);
			RpgException invalidMoney = assertThrows(RpgException.class, () -> engine.inventory().grantLoot(op(),
					campaign, PC, List.of(map("item", "Torch")), "-1 gp", "QUEST", "a rejected grant"));
			assertEquals(ErrorCode.INVALID_ARGUMENT, invalidMoney.code());
			assertEquals(original, snapshot(engine, campaign));
			assertRevision(engine, campaign, PC, before);

			RuntimeException sentinel = new IllegalStateException("fail after inserting an effect");
			RuntimeException failure = assertThrows(RuntimeException.class,
					() -> engine.db().mutate(mutation(campaign, op()), tx -> {
						Effects.add(tx, Ref.id(campaign, Ref.CAMPAIGN), 1, null, null, "an uncommitted ward", null,
								Map.of("ac_bonus", 1), null, 1L, null, "GM");
						throw sentinel;
					}));
			assertSame(sentinel, failure);
			assertEquals(original, snapshot(engine, campaign));
			assertRevision(engine, campaign, PC, before);
		}
	}

	@Test
	void replayDoesNotAdvanceRevisionsAndCheckpointRestorePreservesHistoricRevisionsOnReopen() throws Exception {
		Path database = tempDb("character-revision-replay-restore");
		String campaign;
		long historicRevision;
		Map<String, Object> historicCharacter;
		try (Engine engine = engine(database)) {
			campaign = campaign(engine);
			historicRevision = revision(engine, PC);
			historicCharacter = characterRow(engine, PC);
			String checkpoint = (String) engine.checkpoints().create(op(), campaign, "before acquiring the shield")
					.get("checkpoint");
			String lootId = op();
			String equipId = op();
			String conditionId = op();
			List<Map<String, Object>> items = List.of(map("item", "Shield"));
			Map<String, Object> change = map("kind", "ADD_CONDITION", "condition", "PRONE", "reason", "fell down");
			String entry = (String) list(
					engine.inventory().grantLoot(lootId, campaign, PC, items, null, "QUEST", "reward").get("granted"))
					.getFirst().get("entry");
			engine.inventory().equip(equipId, campaign, PC, entry, true);
			engine.runtime().applyRuntimeChange(conditionId, campaign, PC, change);
			assertRevision(engine, campaign, PC, historicRevision + 3);
			Map<String, List<Map<String, Object>>> played = snapshot(engine, campaign);

			assertEquals(true,
					engine.inventory().grantLoot(lootId, campaign, PC, items, null, "QUEST", "reward").get("replayed"));
			assertEquals(true, engine.inventory().equip(equipId, campaign, PC, entry, true).get("replayed"));
			assertEquals(true, engine.runtime().applyRuntimeChange(conditionId, campaign, PC, change).get("replayed"));
			assertEquals(played, snapshot(engine, campaign));
			assertRevision(engine, campaign, PC, historicRevision + 3);

			engine.checkpoints().restore(op(), campaign, checkpoint, "return to before the reward");
			assertEquals(historicCharacter, characterRow(engine, PC));
			assertRevision(engine, campaign, PC, historicRevision);
		}
		try (Engine reopened = engine(database)) {
			reopened.sessions().bootstrap(op(), campaign, null);
			assertEquals(historicCharacter, characterRow(reopened, PC));
			assertRevision(reopened, campaign, PC, historicRevision);
			grant(reopened, campaign, PC, "Torch", 1);
			assertRevision(reopened, campaign, PC, historicRevision + 1);
		}
	}

	@Test
	void storedValueNoOpsAndStaleRowsPreserveTheLatestRevisionAndJournalTouch() throws Exception {
		try (Engine engine = engine(tempDb("character-revision-stale-row"))) {
			String campaign = campaign(engine);
			String stack = grant(engine, campaign, PC, "Torch", 2);
			long entryId = Ref.id(stack, Ref.INVENTORY);
			long effect = ((Number) engine.db()
					.mutate(mutation(campaign, op()),
							tx -> map("effect", Effects.add(tx, Ref.id(campaign, Ref.CAMPAIGN), 1, null, null, "a ward",
									null, Map.of("ac_bonus", 1), null, 1L, null, "GM")))
					.get("effect")).longValue();
			long before = revision(engine, PC);
			engine.db().mutate(mutation(campaign, op()), tx -> {
				tx.update("inventory_entry", entryId, Map.of("quantity", Integer.valueOf(2)));
				tx.update("active_effect", effect, Map.of("character_id", Integer.valueOf(1)));
				return Map.of("unchanged", true);
			});
			assertRevision(engine, campaign, PC, before);

			String operationId = op();
			Map<String, Object> result = engine.db().mutate(mutation(campaign, operationId), tx -> {
				var stale = tx.get("character", 1);
				tx.update("inventory_entry", entryId, Map.of("quantity", 3));
				long afterInventory = tx.get("character", 1).lng("revision");
				tx.update("active_effect", effect, Map.of("source_description", "a strengthened ward"));
				tx.update("character", 1,
						map("appearance", "An appearance written from a stale row", "revision", stale.lng("revision")));
				long afterStaleWrite = tx.get("character", 1).lng("revision");
				tx.update("character", 1, Map.of("revision", afterStaleWrite + 1));
				tx.touched(PC, stale.lng("revision"));
				return map("after_inventory", afterInventory, "after_stale_write", afterStaleWrite, "final_revision",
						tx.get("character", 1).lng("revision"));
			});

			assertEquals(before + 1, ((Number) result.get("after_inventory")).longValue());
			assertEquals(before + 1, ((Number) result.get("after_stale_write")).longValue());
			assertEquals(before + 2, ((Number) result.get("final_revision")).longValue());
			assertRevision(engine, campaign, PC, before + 2);
			Map<String, Object> touched = engine.db()
					.read(tx -> tx
							.queryOne("SELECT touched_json FROM journal_entry WHERE operation_id = ?", operationId)
							.orElseThrow().map("touched_json"));
			assertEquals(before + 2, ((Number) touched.get(PC)).longValue());

			long beforeManualWrite = revision(engine, PC);
			engine.db().mutate(mutation(campaign, op()), tx -> {
				long current = tx.get("character", 1).lng("revision");
				tx.update("character", 1, Map.of("revision", current + 1));
				tx.update("active_effect", effect,
						Map.of("source_description", "a ward renewed after the character update"));
				tx.update("inventory_entry", entryId, Map.of("quantity", 4));
				return Map.of("renewed", true);
			});
			assertRevision(engine, campaign, PC, beforeManualWrite + 1);
		}
	}

	private static String campaign(Engine engine) {
		String campaign = committedCampaign(engine);
		engine.sessions().bootstrap(op(), campaign, null);
		return campaign;
	}

	private static String npc(Engine engine, String campaign, String name) {
		return (String) engine.runtime().materialize(op(), campaign, "Bandit", name, null, null, null, null, false)
				.get("character");
	}

	private static String grant(Engine engine, String campaign, String owner, String item, int quantity) {
		return (String) list(engine.inventory().grantLoot(op(), campaign, owner,
				List.of(map("item", item, "quantity", quantity)), null, "QUEST", "supplies").get("granted")).getFirst()
				.get("entry");
	}

	private static Map<String, Object> changeCondition(
		Engine engine, String campaign, String character, String kind, String condition, String duration) {
		return engine.runtime().applyRuntimeChange(op(), campaign, character,
				map("kind", kind, "condition", condition, "duration", duration, "reason", "a condition from play"));
	}

	private static Database.Mutation mutation(String campaign, String operationId) {
		return Database.Mutation.of("revision_fixture", Ref.id(campaign, Ref.CAMPAIGN), operationId, "GM", null);
	}

	private static Map<String, List<Map<String, Object>>> snapshot(Engine engine, String campaign) {
		return engine.db().snapshot(Ref.id(campaign, Ref.CAMPAIGN));
	}

	private static Map<String, Object> characterRow(Engine engine, String character) {
		return engine.db().read(tx -> tx.get("character", Ref.id(character, Ref.CHARACTER)).asMap());
	}

	private static long quantity(Engine engine, String entry) {
		return engine.db().read(tx -> tx.get("inventory_entry", Ref.id(entry, Ref.INVENTORY)).lng("quantity"));
	}

	private static void assertRevision(Engine engine, String campaign, String character, long expected) {
		assertEquals(expected, revision(engine, character), character + " stored revision");
		assertEquals(expected,
				((Number) engine.characters().characterSheet(campaign, character, "FULL").get("revision")).longValue(),
				character + " sheet revision");
	}

	private static long revision(Engine engine, String character) {
		return engine.db().read(tx -> tx.get("character", Ref.id(character, Ref.CHARACTER)).lng("revision"));
	}

	@SuppressWarnings("unchecked")
	private static List<Map<String, Object>> list(Object value) {
		return (List<Map<String, Object>>) value;
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> m(Object value) {
		return (Map<String, Object>) value;
	}
}
