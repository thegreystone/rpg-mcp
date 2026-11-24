/* Copyright (C) 2026 Marcus Hirt. SPDX-License-Identifier: BSD-3-Clause */
package se.hirt.mcp.rpg;

import org.junit.jupiter.api.Test;
import se.hirt.mcp.rpg.dice.ScriptedRollService;
import se.hirt.mcp.rpg.protocol.ErrorCode;
import se.hirt.mcp.rpg.protocol.Ref;
import se.hirt.mcp.rpg.protocol.RpgException;
import se.hirt.mcp.rpg.persistence.Database;
import se.hirt.mcp.rpg.rules.TurnEconomy;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static se.hirt.mcp.rpg.TestCampaigns.*;

class CreatureSpellcastingTest {
	private static final String AID = "creature:priest-acolyte:divine-aid";

	@Test
	void structuredCastingIsReadOnlyUntilUsedAndSharesOneSavedDailyUse() throws Exception {
		Path path = tempDb("creature-spell-reload");
		String campaign, priest;
		try (Engine e = engine(path)) {
			campaign = committedCampaign(e);
			e.sessions().bootstrap(op(), campaign, null);
			priest = priest(e, campaign);
			Map<String, Object> casting = cast(
					e.characters().characterSheet(campaign, priest, "PLAY").get("spellcasting"));
			assertEquals("STAT_BLOCK", casting.get("source"));
			assertTrue(String.valueOf(casting).contains("Healing Word"));
			assertTrue(String.valueOf(casting).contains("BONUS_ACTION"));
			assertEquals(0L, resources(e, priest));
			for (String cantrip : List.of("Light", "Thaumaturgy", "Light")) {
				Map<String, Object> result = e.spells().cast(op(), campaign, priest, cantrip, null, List.of(), null);
				assertEquals("ACTION", result.get("action_cost"));
			}
			assertEquals(0L, resources(e, priest), "at-will casting creates no finite resource");
			long before = hp(e, "character:1");
			e.runtime().applyRuntimeChange(op(), campaign, "character:1",
					map("kind", "DAMAGE", "amount", 4, "reason", "A minor wound."));
			((ScriptedRollService) e.roller()).queue(1, 1);
			String operation = op();
			Map<String, Object> healed = e.spells().cast(operation, campaign, priest, "Healing Word", null,
					List.of("character:1"), null);
			assertEquals(before, hp(e, "character:1"), "2d4 rolls 2, plus Wisdom modifier 2");
			assertEquals(12, healed.get("save_dc"));
			assertEquals(4, healed.get("spell_attack_bonus"));
			assertEquals("BONUS_ACTION", healed.get("action_cost"));
			assertEquals(0, remaining(e, priest));
			assertEquals(true,
					e.spells().cast(operation, campaign, priest, "Healing Word", null, List.of("character:1"), null)
							.get("replayed"));
			assertSharedExhausted(e, campaign, priest);
			e.spells().cast(op(), campaign, priest, "Thaumaturgy", null, List.of(), null);
		}
		try (Engine e = engine(path)) {
			assertSharedExhausted(e, campaign, priest);
			e.rest().rest(op(), campaign, "LONG", null, List.of(priest));
			assertEquals(1, remaining(e, priest));
			Map<String, Object> blessing = e.spells().cast(op(), campaign, priest, "Bless", null,
					List.of("character:1"), null);
			assertEquals("BONUS_ACTION", blessing.get("action_cost"), "Divine Aid changes Bless's usual Action cost");
			assertEquals(true, blessing.get("concentration"));
			assertEquals(0, remaining(e, priest));
		}
	}

	@Test
	void unsupportedSpellsUpcastingAndRitualsDoNotConsumeAid() throws Exception {
		try (Engine e = engine(tempDb("creature-spell-permission"))) {
			String campaign = committedCampaign(e);
			e.sessions().bootstrap(op(), campaign, null);
			String priest = priest(e, campaign);
			assertThrows(RpgException.class,
					() -> e.spells().cast(op(), campaign, priest, "Healing Word", 2, List.of("character:1"), null));
			assertThrows(RpgException.class, () -> e.spells().cast(op(), campaign, priest, "Bless", null,
					List.of("character:1"), map("ritual", true)));
			assertThrows(RpgException.class,
					() -> e.spells().cast(op(), campaign, priest, "Cure Wounds", null, List.of("character:1"), null));
			String guard = (String) e.runtime()
					.materialize(op(), campaign, "Guard", null, null, null, null, null, false).get("character");
			assertThrows(RpgException.class,
					() -> e.spells().cast(op(), campaign, guard, "Healing Word", null, List.of("character:1"), null));
			assertEquals(0L, resources(e, priest));
		}
	}

	@Test
	void divineAidSpendsBonusActionAndRetainsTheActionInCombat() throws Exception {
		try (Engine e = engine(tempDb("creature-spell-turn"))) {
			String campaign = committedCampaign(e);
			e.sessions().bootstrap(op(), campaign, null);
			String priest = priest(e, campaign);
			String guard = (String) e.runtime()
					.materialize(op(), campaign, "Guard", null, null, null, null, null, false).get("character");
			((ScriptedRollService) e.roller()).queue(1, 20, 2);
			String encounter = (String) e.encounters()
					.start(op(), campaign, map("party", List.of("character:1", priest), "foes", List.of(guard)), null,
							null, null, null, null,
							map("placements",
									List.of(map("character", "character:1", "x_ft", 0, "y_ft", 0),
											map("character", priest, "x_ft", 5, "y_ft", 0),
											map("character", guard, "x_ft", 20, "y_ft", 0))))
					.get("encounter");
			Map<String, Object> result = e.encounters().perform(op(), campaign, encounter, priest,
					map("kind", "CAST", "spell", "Bless", "targets", List.of(priest)), false);
			assertEquals("BONUS_ACTION", result.get("action_cost"));
			assertEquals("AVAILABLE", cast(result.get("turn_economy")).get("action"));
			assertEquals("SPENT", cast(result.get("turn_economy")).get("bonus_action"));
			Map<String, Object> cantrip = e.encounters().perform(op(), campaign, encounter, priest,
					map("kind", "CAST", "spell", "Thaumaturgy"), false);
			assertEquals("SPENT", cast(cantrip.get("turn_economy")).get("action"));
			assertEquals(0, remaining(e, priest));
		}
	}

	@Test
	void classPromotionEndsStatblockGrants() throws Exception {
		try (Engine e = engine(tempDb("creature-spell-promotion"))) {
			String campaign = committedCampaign(e);
			e.sessions().bootstrap(op(), campaign, null);
			String priest = priest(e, campaign);
			e.party().updateMembership(op(), campaign, priest, "JOIN", "Offers to join the expedition.");
			String transaction = (String) cast(e.levelUps().begin(op(), campaign, priest).get("transaction"))
					.get("ref");
			e.levelUps().update(op(), campaign, transaction, null,
					map("class", "Fighter", "skills", List.of("Athletics", "Survival")));
			e.levelUps().commit(op(), campaign, transaction, null);
			assertNull(e.characters().characterSheet(campaign, priest, "PLAY").get("spellcasting"));
			assertThrows(RpgException.class,
					() -> e.spells().cast(op(), campaign, priest, "Healing Word", null, List.of("character:1"), null));
			assertEquals(0L, resources(e, priest));
		}
	}

	@Test
	void aPriorBonusActionRejectsDivineAidWithoutConsumingTheSharedUse() throws Exception {
		try (Engine e = engine(tempDb("creature-spell-bonus-limit"))) {
			String campaign = committedCampaign(e);
			e.sessions().bootstrap(op(), campaign, null);
			String priest = priest(e, campaign);
			((ScriptedRollService) e.roller()).queue(1, 20);
			String encounter = (String) e.encounters().start(op(), campaign,
					map("party", List.of("character:1"), "foes", List.of(priest)), null, null, null, null, null)
					.get("encounter");
			e.db().mutate(
					Database.Mutation.of("fixture_bonus_action", Ref.id(campaign, Ref.CAMPAIGN), op(), "GM", Map.of()),
					tx -> {
						TurnEconomy.spendBonusAction(tx, tx.get("encounter", Ref.id(encounter, Ref.ENCOUNTER)),
								Ref.id(priest, Ref.CHARACTER), "an earlier Bonus Action");
						return Map.of();
					});
			RpgException ex = assertThrows(RpgException.class, () -> e.encounters().perform(op(), campaign, encounter,
					priest, map("kind", "CAST", "spell", "Bless", "targets", List.of(priest)), false));
			assertEquals(ErrorCode.OPERATION_NOT_ALLOWED, ex.code());
			assertTrue(ex.getMessage().contains("Bonus Action"));
			assertEquals(0L, resources(e, priest),
					"failed casting rolls back lazy resource initialization and payment");
		}
	}

	private static void assertSharedExhausted(Engine e, String campaign, String priest) {
		for (String spell : List.of("Bless", "Healing Word", "Sanctuary")) {
			RpgException ex = assertThrows(RpgException.class,
					() -> e.spells().cast(op(), campaign, priest, spell, null, List.of("character:1"), null));
			assertEquals(ErrorCode.INSUFFICIENT_RESOURCE, ex.code());
		}
	}

	private static String priest(Engine e, String campaign) {
		return (String) e.runtime()
				.materialize(op(), campaign, "Priest Acolyte", "Sister Ilyra", null, null, null, null, false)
				.get("character");
	}

	private static long hp(Engine e, String character) {
		return e.db().read(tx -> tx.get("character", Ref.id(character, Ref.CHARACTER)).lng("current_hp"));
	}

	private static long resources(Engine e, String character) {
		return e.db().read(tx -> tx.count("SELECT COUNT(*) FROM resource_state WHERE character_id=? AND resource_ref=?",
				Ref.id(character, Ref.CHARACTER), AID));
	}

	private static int remaining(Engine e, String character) {
		return e.db().read(tx -> tx.queryOne("SELECT * FROM resource_state WHERE character_id=? AND resource_ref=?",
				Ref.id(character, Ref.CHARACTER), AID).orElseThrow().intOr("current", 0));
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> cast(Object v) {
		return (Map<String, Object>) v;
	}
}
