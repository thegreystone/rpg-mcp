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
import se.hirt.mcp.rpg.content.ContentService;
import se.hirt.mcp.rpg.inventory.MagicItems;
import se.hirt.mcp.rpg.persistence.Database;
import se.hirt.mcp.rpg.protocol.ErrorCode;
import se.hirt.mcp.rpg.protocol.Json;
import se.hirt.mcp.rpg.protocol.Ref;
import se.hirt.mcp.rpg.protocol.RpgException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static se.hirt.mcp.rpg.TestCampaigns.committedCampaign;
import static se.hirt.mcp.rpg.TestCampaigns.engine;
import static se.hirt.mcp.rpg.TestCampaigns.map;
import static se.hirt.mcp.rpg.TestCampaigns.op;
import static se.hirt.mcp.rpg.TestCampaigns.tempDb;

class MagicItemIdentityTest {

	@Test
	void contradictoryBonusCannotReuseAnExistingPlusOneLongsword() throws Exception {
		try (Engine engine = engine(tempDb("magic-item-contradictory-bonus"))) {
			String campaign = campaign(engine);
			Map<String, Object> first = grant(engine, campaign, map("item", "+1 Longsword"));
			assertEquals(1, grantedBonus(engine, first));
			Map<String, List<Map<String, Object>>> before = snapshot(engine, campaign);
			for (String item : List.of("+1 Longsword", "+1 Shortsword")) {
				RpgException failure = assertThrows(RpgException.class,
						() -> grant(engine, campaign, map("item", item, "bonus", 2)));
				assertEquals(ErrorCode.INVALID_ARGUMENT, failure.code());
				assertEquals(before, snapshot(engine, campaign),
						"a contradictory request neither reuses nor creates a definition");
			}
			for (Object invalidBonus : List.of(0, 4, 1.5, Double.NaN, Double.POSITIVE_INFINITY,
					new java.math.BigInteger("999999999999999999999"), "1")) {
				assertEquals(ErrorCode.INVALID_ARGUMENT,
						assertThrows(RpgException.class,
								() -> grant(engine, campaign, map("item", "+1 Longsword", "bonus", invalidBonus)))
								.code());
				assertEquals(before, snapshot(engine, campaign));
			}
			assertEquals(item(first), item(
					grant(engine, campaign, map("item", "+1 Longsword", "base", "srd5e:item/longsword", "bonus", 1L))));
			Map<String, List<Map<String, Object>>> beforeCompleteItemRequests = snapshot(engine, campaign);
			for (Map<String, Object> spec : List.of(map("item", item(first), "bonus", 2),
					map("item", item(first), "name", "Another blade"))) {
				assertEquals(ErrorCode.INVALID_ARGUMENT,
						assertThrows(RpgException.class, () -> grant(engine, campaign, spec)).code());
				assertEquals(beforeCompleteItemRequests, snapshot(engine, campaign));
			}
		}
	}

	@Test
	void definingASecondCustomItemWithTheSameNameIsRejected() throws Exception {
		try (Engine engine = engine(tempDb("magic-item-duplicate-name"))) {
			String campaign = campaign(engine);
			Map<String, Object> first = define(engine, campaign, "Silver Keepsake", "custom:item/first-keepsake");
			Map<String, List<Map<String, Object>>> before = snapshot(engine, campaign);
			for (String name : List.of("Silver Keepsake", " silver KEEPSAKE ")) {
				RpgException failure = assertThrows(RpgException.class,
						() -> define(engine, campaign, name, "custom:item/second-keepsake"));
				assertEquals(ErrorCode.CONFLICT, failure.code());
				assertEquals(before, snapshot(engine, campaign));
			}
			Map<String, Object> background = background(engine, campaign, "Silver Keepsake",
					"custom:background/keepsake");
			assertNotEquals(first.get("content"), background.get("content"),
					"item names and background names have separate scopes");
			String otherCampaign = campaign(engine);
			Map<String, Object> other = define(engine, otherCampaign, "Silver Keepsake", "custom:item/first-keepsake");
			assertNotEquals(first.get("content"), other.get("content"), "another campaign may author the same name");
		}
	}

	@Test
	void matchingTemplateRequestsReuseIdentityAndPreserveLegacyPriceAndText() throws Exception {
		try (Engine engine = engine(tempDb("magic-item-template-reuse"))) {
			String campaign = campaign(engine);
			Map<String, Object> unnamed = grant(engine, campaign, map("item", "+1 Longsword"));
			Map<String, Object> named = grant(engine, campaign, map("item", "+1 Longsword", "name", "Sentinel"));
			String content = item(named);
			assertNotEquals(item(unnamed), content, "an explicit new name creates the requested named definition");
			assertEquals("Sentinel", granted(named).get("name"));
			engine.db().mutate(fixtureMutation(campaign), tx -> {
				var row = tx.get("custom_content", Ref.id(content, Ref.CONTENT));
				Map<String, Object> payload = row.map("payload_json");
				payload.put("text", "A historically authored description.");
				payload.put("summary", "Historical Sentinel");
				payload.put("cost_cp", 12345L);
				tx.update("custom_content", row.id(), map("payload_json", Json.write(payload), "cost_cp", 12345L));
				return Map.of();
			});
			Map<String, Object> preserved = content(engine, content);
			Map<String, Object> repeated = grant(engine, campaign, template("srd5e:item/longsword", 1L, " sentinel "));
			assertEquals(content, item(repeated));
			assertEquals(preserved, content(engine, content),
					"matching identity does not reprice or rewrite an old definition");
			assertEquals(1, grantedBonus(engine, repeated));
			assertEquals(2L, quantity(engine, (String) granted(repeated).get("entry")));
		}
	}

	@Test
	void templateIdentityAndCrossCreatorNameCollisionsRejectDifferentDefinitions() throws Exception {
		try (Engine engine = engine(tempDb("magic-item-template-conflicts"))) {
			String campaign = campaign(engine);
			String sentinel = item(grant(engine, campaign, template("Longsword", 1, "Sentinel")));
			define(engine, campaign, "Plain Keepsake", "custom:item/plain-keepsake");
			List<Map<String, Object>> conflicts = List.of(template("Shortsword", 1, "Sentinel"),
					template("Longsword", 2, "Sentinel"),
					map("item", "Flame Tongue", "base", "Longsword", "name", "Sentinel"),
					template("Longsword", 1, "Plain Keepsake"),
					map("item", "Longsword", "magic", enchantment("Plain Keepsake")),
					map("item", "Longsword", "magic", enchantment("Sentinel")));
			Map<String, List<Map<String, Object>>> before = snapshot(engine, campaign);
			for (Map<String, Object> spec : conflicts) {
				RpgException failure = assertThrows(RpgException.class, () -> grant(engine, campaign, spec));
				assertEquals(ErrorCode.CONFLICT, failure.code(), spec.toString());
				if ("Sentinel".equals(spec.get("name"))) {
					assertEquals(sentinel, failure.details().get("existing"));
				}
				assertEquals(before, snapshot(engine, campaign));
			}
			RpgException definedCollision = assertThrows(RpgException.class,
					() -> define(engine, campaign, "Sentinel", "custom:item/sentinel-copy"));
			assertEquals(ErrorCode.CONFLICT, definedCollision.code());
			assertEquals(before, snapshot(engine, campaign));
			String frost = item(grant(engine, campaign, map("item", "Longsword", "magic", enchantment("Frostbrand"))));
			assertNotEquals(sentinel, frost);
			assertEquals(ErrorCode.CONFLICT, assertThrows(RpgException.class,
					() -> grant(engine, campaign, template("Longsword", 1, "Frostbrand"))).code());
		}
	}

	@Test
	void authoredEnchantmentsReuseOnlyMatchingNormalizedSpecifications() throws Exception {
		try (Engine engine = engine(tempDb("magic-item-enchantment-identity"))) {
			String campaign = campaign(engine);
			Map<String, Object> originalMagic = enchantment("Frostbrand");
			String original = item(grant(engine, campaign, map("item", "Longsword", "magic", originalMagic)));
			Map<String, Object> normalized = new LinkedHashMap<>(originalMagic);
			normalized.putAll(map("name", " frostBRAND ", "rarity", "rare", "bonus", 1L, "damage_type", "COLD", "slot",
					"one_hand", "text", " A sword rimed with frost. "));
			assertEquals(original,
					item(grant(engine, campaign, map("item", "srd5e:item/longsword", "magic", normalized))));
			Map<String, List<Map<String, Object>>> before = snapshot(engine, campaign);
			List<Map<String, Object>> changes = List.of(map("bonus", 2), map("attunement", false),
					map("rarity", "VERY_RARE"), map("text", "A different frost enchantment."),
					map("damage_bonus_dice", "2d6"), map("slot", "TWO_HANDS"), map("resistance", "fire"));
			for (Map<String, Object> change : changes) {
				Map<String, Object> changed = new LinkedHashMap<>(originalMagic);
				changed.putAll(change);
				assertEquals(ErrorCode.CONFLICT,
						assertThrows(RpgException.class,
								() -> grant(engine, campaign, map("item", "Longsword", "magic", changed))).code(),
						change.toString());
				assertEquals(before, snapshot(engine, campaign));
			}
			assertEquals(ErrorCode.CONFLICT, assertThrows(RpgException.class,
					() -> grant(engine, campaign, map("item", "Shortsword", "magic", originalMagic))).code());
			assertEquals(before, snapshot(engine, campaign));
		}
	}

	@Test
	void repeatedEnchantmentsStillValidateAndConsumableEffectsBelongToTheirIdentity() throws Exception {
		try (Engine engine = engine(tempDb("magic-item-enchantment-validation"))) {
			String campaign = campaign(engine);
			Map<String, Object> magic = enchantment("Frosttip");
			grant(engine, campaign, map("item", "Arrow", "magic", magic));
			Map<String, List<Map<String, Object>>> before = snapshot(engine, campaign);
			List<Map<String, Object>> invalidChanges = List.of(map("text", null), map("rarity", "IMPOSSIBLE"),
					map("bonus", 4), map("damage_bonus_dice", "1d6+"));
			for (Map<String, Object> change : invalidChanges) {
				Map<String, Object> invalid = new LinkedHashMap<>(magic);
				invalid.putAll(change);
				assertEquals(ErrorCode.INVALID_ARGUMENT, assertThrows(RpgException.class,
						() -> grant(engine, campaign, map("item", "Arrow", "magic", invalid))).code());
				assertEquals(before, snapshot(engine, campaign));
			}
			for (Map<String, Object> outerRecipe : List.of(map("bonus", 2), map("base", "Longsword"),
					map("name", "Another Frosttip"))) {
				Map<String, Object> mixed = map("item", "Arrow", "magic", magic);
				mixed.putAll(outerRecipe);
				assertEquals(ErrorCode.INVALID_ARGUMENT,
						assertThrows(RpgException.class, () -> grant(engine, campaign, mixed)).code());
				assertEquals(before, snapshot(engine, campaign),
						"mixed recipe forms cannot silently ignore item identity");
			}
			Map<String, Object> remedy = map("name", "Silver Remedy", "text", "A healing draught.", "consumable",
					map("heal", "2d4+2"));
			String original = item(grant(engine, campaign, map("item", "Vial", "magic", remedy)));
			assertEquals(original, item(grant(engine, campaign, map("item", "Vial", "magic", remedy))));
			Map<String, Object> different = new LinkedHashMap<>(remedy);
			different.put("consumable", map("heal", "1d4+1"));
			assertEquals(ErrorCode.CONFLICT, assertThrows(RpgException.class,
					() -> grant(engine, campaign, map("item", "Vial", "magic", different))).code());
		}
	}

	@Test
	void legacyDuplicateNamesReportCandidatesWhileExplicitReferencesStayPlayable() throws Exception {
		try (Engine engine = engine(tempDb("magic-item-legacy-duplicates"))) {
			String campaign = campaign(engine);
			String first = (String) define(engine, campaign, "Echo Charm", "custom:item/echo-first").get("content");
			String second = duplicate(engine, campaign, first, " echo CHARM ", "custom:item/echo-second");
			Map<String, Object> firstRow = content(engine, first), secondRow = content(engine, second);
			assertAmbiguous(engine, campaign, "Echo Charm", first, second);
			assertEquals(first, item(grant(engine, campaign, map("item", first))));
			assertEquals(second, item(grant(engine, campaign, map("item", "custom:item/echo-second"))));
			assertEquals(firstRow, content(engine, first));
			assertEquals(secondRow, content(engine, second));

			String plusOne = item(grant(engine, campaign, map("item", "+1 Longsword")));
			String duplicatePlusOne = duplicate(engine, campaign, plusOne, " +1 LONGSWORD ",
					"custom:item/legacy-plus-one");
			assertAmbiguous(engine, campaign, "+1 Longsword", plusOne, duplicatePlusOne);
			assertEquals(1, grantedBonus(engine, grant(engine, campaign, map("item", duplicatePlusOne))));
		}
	}

	@Test
	void explicitReferencesRequireItemsAndCampaignOwnershipWhileInstalledNamesKeepPrecedence() throws Exception {
		try (Engine engine = engine(tempDb("magic-item-reference-scope"))) {
			String campaign = campaign(engine);
			String customTorch = (String) define(engine, campaign, "Torch", "custom:item/authored-torch")
					.get("content");
			assertEquals("srd5e:item/torch", item(grant(engine, campaign, map("item", "Torch"))));
			assertEquals(customTorch, item(grant(engine, campaign, map("item", customTorch))));
			assertEquals(customTorch, item(grant(engine, campaign, map("item", "custom:item/authored-torch"))));
			String background = (String) background(engine, campaign, "Lantern Keeper",
					"custom:background/lantern-keeper").get("content");
			Map<String, List<Map<String, Object>>> before = snapshot(engine, campaign);
			for (String reference : List.of(background, "custom:background/lantern-keeper")) {
				assertEquals(ErrorCode.INVALID_ARGUMENT,
						assertThrows(RpgException.class, () -> grant(engine, campaign, map("item", reference))).code());
				assertEquals(before, snapshot(engine, campaign));
			}
			String otherCampaign = campaign(engine);
			Map<String, List<Map<String, Object>>> otherBefore = snapshot(engine, otherCampaign);
			assertEquals(ErrorCode.INVALID_ARGUMENT,
					assertThrows(RpgException.class, () -> grant(engine, otherCampaign, map("item", customTorch)))
							.code());
			assertEquals(otherBefore, snapshot(engine, otherCampaign));
		}
	}

	@Test
	void atomicLootReplayAndCheckpointRestoreKeepIdentityAndLegacyDuplicatesAcrossReopen() throws Exception {
		Path database = tempDb("magic-item-identity-reopen");
		String campaign, persistent, firstLegacy, secondLegacy;
		Map<String, Object> firstLegacyRow, secondLegacyRow;
		try (Engine engine = engine(database)) {
			campaign = campaign(engine);
			persistent = item(grant(engine, campaign, map("item", "+1 Longsword")));
			firstLegacy = (String) define(engine, campaign, "Old Token", "custom:item/old-first").get("content");
			secondLegacy = duplicate(engine, campaign, firstLegacy, " old TOKEN ", "custom:item/old-second");
			firstLegacyRow = content(engine, firstLegacy);
			secondLegacyRow = content(engine, secondLegacy);
			String checkpoint = (String) engine.checkpoints().create(op(), campaign, "before the named weapon")
					.get("checkpoint");
			Map<String, List<Map<String, Object>>> original = snapshot(engine, campaign);
			String operation = op();
			List<Map<String, Object>> specs = List.of(template("Longsword", 2, "Radiance"));
			String pc = player(engine, campaign);
			engine.inventory().grantLoot(operation, campaign, pc, specs, null, "QUEST", "the named weapon");
			Map<String, List<Map<String, Object>>> after = snapshot(engine, campaign);
			assertEquals(true, engine.inventory()
					.grantLoot(operation, campaign, pc, specs, null, "QUEST", "the named weapon").get("replayed"));
			assertEquals(after, snapshot(engine, campaign));
			String activeCampaign = campaign;
			assertEquals(ErrorCode.CONFLICT,
					assertThrows(RpgException.class,
							() -> engine.inventory().grantLoot(op(), activeCampaign, pc,
									List.of(map("item", "+2 Dagger"), template("Shortsword", 2, "Radiance")), "1 gp",
									"QUEST", "rejected batch"))
							.code());
			assertEquals(after, snapshot(engine, campaign),
					"an earlier new definition, inventory row and revision all roll back");
			engine.checkpoints().restore(op(), campaign, checkpoint, "undo the named weapon grant");
			assertEquals(original, snapshot(engine, campaign));
		}
		try (Engine reopened = engine(database)) {
			reopened.sessions().bootstrap(op(), campaign, null);
			assertEquals(firstLegacyRow, content(reopened, firstLegacy));
			assertEquals(secondLegacyRow, content(reopened, secondLegacy));
			assertAmbiguous(reopened, campaign, "Old Token", firstLegacy, secondLegacy);
			assertEquals(firstLegacy, item(grant(reopened, campaign, map("item", firstLegacy))));
			assertEquals(secondLegacy, item(grant(reopened, campaign, map("item", "custom:item/old-second"))));
			assertEquals(persistent, item(grant(reopened, campaign, map("item", "+1 Longsword"))));
		}
	}

	private static String campaign(Engine engine) {
		String campaign = committedCampaign(engine);
		engine.sessions().bootstrap(op(), campaign, null);
		return campaign;
	}

	private static String player(Engine engine, String campaign) {
		return (String) engine.sessions().party(campaign, "SUMMARY").get("player_character");
	}

	private static Map<String, Object> grant(Engine engine, String campaign, Map<String, Object> spec) {
		return engine.inventory().grantLoot(op(), campaign, player(engine, campaign), List.of(spec), null, "QUEST",
				"an authored item");
	}

	private static Map<String, Object> define(Engine engine, String campaign, String name, String symbolic) {
		return engine.content().define(op(), campaign, "ITEM", name, symbolic, "A little silver charm.", "GEAR", "1 gp",
				0.1, null, null, "GM");
	}

	private static Map<String, Object> background(Engine engine, String campaign, String name, String symbolic) {
		return engine.content()
				.define(op(), campaign, "BACKGROUND", name, symbolic, "A keeper of the old customs.", null, null, null,
						map("ability_scores", List.of("CHA", "INT", "WIS"), "feat", "Alert", "skills",
								List.of("History", "Persuasion"), "tool", map("item", "Calligrapher's Supplies")),
						null, "GM");
	}

	private static Map<String, Object> template(String base, Number bonus, String name) {
		return map("item", "Weapon, +1, +2, or +3", "base", base, "bonus", bonus, "name", name);
	}

	private static Map<String, Object> enchantment(String name) {
		return map("name", name, "rarity", "RARE", "bonus", 1, "attunement", true, "damage_bonus_dice", "1d6",
				"damage_type", "cold", "slot", "ONE_HAND", "text", "A sword rimed with frost.");
	}

	private static Map<String, Object> granted(Map<String, Object> result) {
		return list(result.get("granted")).getFirst();
	}

	private static String item(Map<String, Object> result) {
		return (String) granted(result).get("item");
	}

	private static Map<String, Object> content(Engine engine, String reference) {
		return engine.db().read(tx -> tx.get("custom_content", Ref.id(reference, Ref.CONTENT)).asMap());
	}

	private static long quantity(Engine engine, String entry) {
		return engine.db().read(tx -> tx.get("inventory_entry", Ref.id(entry, Ref.INVENTORY)).lng("quantity"));
	}

	private static String duplicate(Engine engine, String campaign, String reference, String name, String symbolic) {
		Map<String, Object> duplicated = engine.db().mutate(fixtureMutation(campaign), tx -> {
			Map<String, Object> columns = new LinkedHashMap<>(
					tx.get("custom_content", Ref.id(reference, Ref.CONTENT)).asMap());
			columns.remove("id");
			columns.put("name", name);
			columns.put("symbolic_id", symbolic);
			return map("content", Ref.of(Ref.CONTENT, tx.insert("custom_content", columns)));
		});
		return (String) duplicated.get("content");
	}

	private static void assertAmbiguous(Engine engine, String campaign, String name, String first, String second) {
		Map<String, List<Map<String, Object>>> before = snapshot(engine, campaign);
		RpgException failure = assertThrows(RpgException.class, () -> grant(engine, campaign, map("item", name)));
		assertEquals(ErrorCode.CONFLICT, failure.code());
		List<?> candidates = (List<?>) failure.details().get("candidates");
		assertEquals(2, candidates.size());
		assertTrue(candidates.containsAll(List.of(first, second)));
		assertEquals(before, snapshot(engine, campaign));
	}

	private static Database.Mutation fixtureMutation(String campaign) {
		return Database.Mutation.of("magic_item_identity_fixture", Ref.id(campaign, Ref.CAMPAIGN), op(), "GM", null);
	}

	private static Map<String, List<Map<String, Object>>> snapshot(Engine engine, String campaign) {
		return engine.db().snapshot(Ref.id(campaign, Ref.CAMPAIGN));
	}

	private static int grantedBonus(Engine engine, Map<String, Object> granted) {
		String entry = (String) list(granted.get("granted")).getFirst().get("entry");
		return engine.db().read(tx -> MagicItems.bonus(ContentService
				.itemForEntry(tx, engine.rules(), tx.get("inventory_entry", Ref.id(entry, Ref.INVENTORY))).payload()));
	}

	@SuppressWarnings("unchecked")
	private static List<Map<String, Object>> list(Object value) {
		return (List<Map<String, Object>>) value;
	}
}
