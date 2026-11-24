/* Copyright (C) 2026 Marcus Hirt. SPDX-License-Identifier: BSD-3-Clause */
package se.hirt.mcp.rpg;

import org.junit.jupiter.api.Test;
import se.hirt.mcp.rpg.dice.ScriptedRollService;
import se.hirt.mcp.rpg.maps.SceneService;
import se.hirt.mcp.rpg.protocol.ErrorCode;
import se.hirt.mcp.rpg.protocol.Ref;
import se.hirt.mcp.rpg.protocol.RpgException;
import se.hirt.mcp.rpg.spatial.Geometry.Footprint;
import se.hirt.mcp.rpg.spatial.SpatialRules;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static se.hirt.mcp.rpg.TestCampaigns.*;

class Run5SpellTest {

	@Test
	void originalGalleryTargetsHaveDifferentCoverDespiteSharingTheSameObserver() {
		var geometry = SceneService.geometry(gallery(), 0);
		var oddny = new Footprint(SceneService.point(30, 5), 1, 1);
		var tam = new Footprint(SceneService.point(40, 15), 1, 1);
		var whipHand = new Footprint(SceneService.point(55, 20), 1, 1);
		assertEquals(SpatialRules.Cover.TOTAL, SpatialRules.cover(geometry, oddny, tam).cover(),
				"The actual Healing Word target is behind the wall west of the opening.");
		assertEquals(SpatialRules.Cover.HALF, SpatialRules.cover(geometry, oddny, whipHand).cover(),
				"The separately reported Radiant Flame target is partially exposed through the opening.");
		assertFalse(SpatialRules.lineOfSight(geometry, oddny.center(), whipHand.center()),
				"A blocked center ray must not turn partial creature cover into Total Cover.");
	}

	@Test
	void attacksAndHealingShareTheSamePartialCoverPathAndTotalCoverRefusalsAreAtomic() throws Exception {
		try (Engine e = engine(tempDb("run5-spell-cover"))) {
			String campaign = ready(e), priest = npc(e, campaign, "Priest Acolyte", "Sister Oddny Vane"),
					whipHand = npc(e, campaign, "Goblin Warrior", "Goblin whip-hand");
			e.party().updateMembership(op(), campaign, priest, "JOIN", "Oddny accompanies the party.");
			e.runtime().applyRuntimeChange(op(), campaign, "character:1",
					map("kind", "DAMAGE", "amount", 4, "reason", "Tam was wounded before the gallery encounter."));
			e.runtime().applyRuntimeChange(op(), campaign, whipHand,
					map("kind", "DAMAGE", "amount", 6, "reason", "The whip-hand was wounded in the earlier scuffle."));
			dice(e).queue(1, 20, 1);
			String encounter = e.encounters()
					.start(op(), campaign, map("party", List.of("character:1", priest), "foes", List.of(whipHand)),
							null, null, null, null, null,
							map("scene", gallery(), "placements", List.of(position("character:1", 40, 15),
									position(priest, 30, 5), position(whipHand, 55, 20))))
					.get("encounter").toString();
			turnTo(e, campaign, encounter, priest);
			var before = e.db().snapshot(Ref.id(campaign, Ref.CAMPAIGN));
			RpgException healing = assertThrows(RpgException.class,
					() -> e.encounters().perform(op(), campaign, encounter, priest,
							map("kind", "CAST", "spell", "Healing Word", "targets", List.of("character:1")), false));
			assertEquals(ErrorCode.OPERATION_NOT_ALLOWED, healing.code());
			assertTrue(healing.getMessage().contains("no clear path"));
			assertEquals(before, e.db().snapshot(Ref.id(campaign, Ref.CAMPAIGN)),
					"Total Cover refusal must preserve HP, daily Divine Aid, dice and Bonus Action.");
			RpgException attack = assertThrows(RpgException.class,
					() -> e.encounters().perform(op(), campaign, encounter, priest,
							map("kind", "ATTACK", "weapon", "Radiant Flame", "target", "character:1"), false));
			assertEquals(ErrorCode.OPERATION_NOT_ALLOWED, attack.code());
			assertTrue(attack.getMessage().contains("no unobstructed path"));
			assertEquals(before, e.db().snapshot(Ref.id(campaign, Ref.CAMPAIGN)),
					"The identical blocked target also refuses the ranged attack without spending its Action.");
			dice(e).queue(1, 2, 3);
			Map<String, Object> permittedAttack = e.encounters().perform(op(), campaign, encounter, priest,
					map("kind", "ATTACK", "weapon", "Radiant Flame", "target", whipHand), false);
			assertEquals("HALF", permittedAttack.get("cover"));
			assertEquals(false, permittedAttack.get("hit"));
			Map<String, Object> permittedHealing = e.encounters().perform(op(), campaign, encounter, priest,
					map("kind", "CAST", "spell", "Healing Word", "targets", List.of(whipHand)), false);
			assertEquals("BONUS_ACTION", permittedHealing.get("action_cost"));
			assertEquals(1, permittedHealing.get("level"));
			Map<String, Object> healed = SceneService.maps(permittedHealing.get("targets")).getFirst();
			assertEquals("2d4", SceneService.map(healed.get("roll")).get("expression"));
			assertTrue(((Number) healed.get("healed")).intValue() > 0,
					"A spell may heal any creature; the same partially exposed foe is a legal target.");
			assertTrue(dice(e).exhausted(), "Both refusals must leave the queued attack/healing dice untouched.");
		}
	}

	@Test
	void divineAidHealingWordUsesSrd521LevelOneTwoD4PlusWisdom() throws Exception {
		// SRD 5.2.1 Spells, Healing Word (p139): level1 heals 2d4+ability modifier.
		// Official source: https://www.dndbeyond.com/sources/dnd/br-2024/spell-descriptions#HealingWord
		// Priest Acolyte Divine Aid changes the casting permission/cost, not the spell's healing dice.
		try (Engine e = engine(tempDb("run5-divine-aid-healing-dice"))) {
			String campaign = ready(e), priest = npc(e, campaign, "Priest Acolyte", "Sister Oddny Vane");
			e.runtime().applyRuntimeChange(op(), campaign, priest,
					map("kind", "DAMAGE", "amount", 9, "reason", "Oddny has a serious but nonfatal wound."));
			dice(e).queue(2, 3);
			Map<String, Object> result = e.spells().cast(op(), campaign, priest, "Healing Word", 1, List.of(priest),
					null);
			assertEquals(1, result.get("level"));
			assertEquals("Divine Aid", result.get("creature_ability"));
			assertEquals("BONUS_ACTION", result.get("action_cost"));
			Map<String, Object> healed = SceneService.maps(result.get("targets")).getFirst();
			Map<String, Object> roll = SceneService.map(healed.get("roll"));
			assertEquals("2d4", roll.get("expression"));
			assertEquals(List.of(2, 3), roll.get("dice"));
			assertEquals(5, roll.get("total"));
			assertEquals(7, healed.get("healed"), "The Priest Acolyte's Wisdom14 adds2 to both d4 dice.");
			assertEquals(9, healed.get("hp_after"));
			assertEquals(0,
					e.db().read(tx -> tx
							.queryOne("SELECT * FROM resource_state WHERE character_id=? AND resource_ref=?",
									Ref.id(priest, Ref.CHARACTER), "creature:priest-acolyte:divine-aid")
							.orElseThrow().intOr("current", -1)).intValue());
			assertTrue(dice(e).exhausted());
		}
	}

	private static Map<String, Object> gallery() {
		return map("name", "Run5 original gallery opening", "bounds_ft", List.of(0, 0, 120, 70), "illumination",
				"BRIGHT", "rooms",
				List.of(map("key", "main-drift", "rect_ft", List.of(20, 0, 60, 10)),
						map("key", "diggers-gallery", "rect_ft", List.of(30, 10, 30, 25))),
				"doors", List.of(map("key", "drift-to-gallery", "from_ft", List.of(40, 10), "to_ft", List.of(45, 10),
						"state", "OPEN")));
	}

	private static Map<String, Object> position(String character, int x, int y) {
		return map("character", character, "x_ft", x, "y_ft", y);
	}

	private static String ready(Engine e) {
		String campaign = committedCampaign(e);
		e.sessions().bootstrap(op(), campaign, null);
		return campaign;
	}

	private static String npc(Engine e, String campaign, String creature, String name) {
		return e.runtime().materialize(op(), campaign, creature, name, null, null, null, null, false).get("character")
				.toString();
	}

	private static void turnTo(Engine e, String campaign, String encounter, String actor) {
		for (int attempt = 0; attempt < 6; attempt++) {
			String current = SceneService.map(e.encounters().encounterState(campaign, encounter, 0, "GM").get("turn"))
					.get("character").toString();
			if (actor.equals(current))
				return;
			e.encounters().perform(op(), campaign, encounter, current, map("kind", "END_TURN"), true);
		}
		fail("Could not reach Oddny's turn.");
	}

	private static ScriptedRollService dice(Engine e) {
		return (ScriptedRollService) e.roller();
	}
}
