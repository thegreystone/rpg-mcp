/* Copyright (C) 2026 Marcus Hirt. SPDX-License-Identifier: BSD-3-Clause */
package se.hirt.mcp.rpg;

import org.junit.jupiter.api.Test;
import se.hirt.mcp.rpg.dice.ScriptedRollService;
import se.hirt.mcp.rpg.maps.SceneService;
import se.hirt.mcp.rpg.protocol.ErrorCode;
import se.hirt.mcp.rpg.protocol.Ref;
import se.hirt.mcp.rpg.protocol.RpgException;
import se.hirt.mcp.rpg.session.GameTime;
import se.hirt.mcp.rpg.spatial.Geometry.Footprint;
import se.hirt.mcp.rpg.spatial.Geometry.Point;
import se.hirt.mcp.rpg.spatial.SpatialRules;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static se.hirt.mcp.rpg.TestCampaigns.*;

class Run4CombatTest {

	@Test
	void theReportedGalleryPairHasReciprocalSightButDifferentExposureThroughTheDoorway() {
		// Run4 map:7: the gallery and drift share y=10, with a 5 ft opening at x=40..45.
		var geometry = SceneService.geometry(map("bounds_ft", List.of(0, 0, 120, 70), "rooms",
				List.of(map("key", "main-drift", "rect_ft", List.of(20, 0, 60, 10)),
						map("key", "diggers-gallery", "rect_ft", List.of(30, 10, 30, 25))),
				"doors", List.of(map("key", "drift-to-gallery", "from_ft", List.of(40, 10), "to_ft", List.of(45, 10),
						"state", "OPEN"))),
				0);
		var whipHand = new Footprint(new Point(55 * 12, 20 * 12), 1, 1);
		var oddny = new Footprint(new Point(30 * 12, 5 * 12), 1, 1);
		var towardOddny = SpatialRules.cover(geometry, whipHand, oddny);
		var towardWhipHand = SpatialRules.cover(geometry, oddny, whipHand);
		assertEquals(SpatialRules.Cover.THREE_QUARTERS, towardOddny.cover());
		assertEquals(SpatialRules.Cover.HALF, towardWhipHand.cover());
		assertTrue(towardOddny.blockedSamples() > towardWhipHand.blockedSamples(),
				"Aiming from the best exposed corner is reciprocal sight, not equal target exposure.");
	}

	@Test
	void aDownedCompanionKeepsHerRoleAndCannotBeAttackedWithoutExplicitConfirmation() throws Exception {
		try (Engine e = engine(tempDb("run4-downed-ally"))) {
			String campaign = ready(e), ally = npc(e, campaign, "Bandit", "Mira"),
					foe = npc(e, campaign, "Ogre", "Foe");
			e.party().updateMembership(op(), campaign, ally, "JOIN", "A real companion, not merely an encounter side.");
			dice(e).queue(20, 1, 1);
			String encounter = e.encounters().start(op(), campaign,
					map("party", List.of("character:1", ally), "foes", List.of(foe)), null, null, null, null, null,
					map("placements",
							List.of(position("character:1", 5, 5), position(ally, 10, 5), position(foe, 25, 5))))
					.get("encounter").toString();
			e.runtime().applyRuntimeChange(op(), campaign, ally,
					map("kind", "DAMAGE", "amount", hp(e, ally), "reason", "The companion is knocked down."));
			Map<String, Object> member = participant(e, campaign, encounter, ally);
			assertEquals("DYING", member.get("life_state"));
			assertEquals("ACTIVE", member.get("membership"));
			assertEquals("COMPANION", member.get("role"));
			assertTrue(member.containsKey("care"));
			assertEquals("PLAYER_CHARACTER", participant(e, campaign, encounter, "character:1").get("role"));
			assertEquals("NON_PARTY", participant(e, campaign, encounter, foe).get("membership"));
			long cid = Ref.id(campaign, Ref.CAMPAIGN);
			var before = e.db().snapshot(cid);
			dice(e).queue(20, 20);
			RpgException refusal = assertThrows(RpgException.class, () -> e.encounters().perform(op(), campaign,
					encounter, "character:1", map("kind", "ATTACK", "weapon", "unarmed", "target", ally), false));
			assertEquals(ErrorCode.OPERATION_NOT_ALLOWED, refusal.code());
			assertTrue(refusal.getMessage().contains("confirm_hostile_ally:true"));
			assertTrue(refusal.details().containsKey("care"));
			assertEquals(before, e.db().snapshot(cid), "Refusal must not spend the Action or change the dying ally.");
			Map<String, Object> intentional = e.encounters().perform(op(), campaign, encounter, "character:1",
					map("kind", "ATTACK", "weapon", "unarmed", "target", ally, "confirm_hostile_ally", true), false);
			assertEquals(true, intentional.get("hit"), "An explicitly confirmed hostile act remains possible.");
			assertEquals("ADVANTAGE", intentional.get("advantage"));
			assertTrue(intentional.get("advantage_reasons").toString().contains("target is unconscious"));
			assertFalse(intentional.get("advantage_reasons").toString().contains("declared with the action"),
					"The unconscious target cannot see the attacker, but no Advantage was declared.");
			assertTrue(dice(e).exhausted(), "The refused attack must leave the two queued Advantage dice untouched.");
		}
	}

	@Test
	void aMeleeOpportunityAttackCanKnockOutInsteadOfKillingAndReplaysOnce() throws Exception {
		try (Engine e = engine(tempDb("run4-nonlethal-reaction"))) {
			String campaign = ready(e), foe = npc(e, campaign, "Bandit", "Fleeing brawler");
			String dagger = SceneService.maps(e.inventory()
					.grantLoot(op(), campaign, "character:1", List.of(map("item", "Dagger")), null, "QUEST", null)
					.get("granted")).getFirst().get("entry").toString();
			e.inventory().equip(op(), campaign, "character:1", dagger, true);
			dice(e).queue(1, 20);
			String encounter = start(e, campaign, foe, "ASK");
			e.runtime().applyRuntimeChange(op(), campaign, foe,
					map("kind", "DAMAGE", "amount", hp(e, foe) - 1, "reason", "The brawler has one Hit Point left."));
			Map<String, Object> move = e.encounters().perform(op(), campaign, encounter, foe,
					map("kind", "MOVE", "x_ft", 25, "y_ft", 5), false);
			Map<String, Object> pending = SceneService.maps(move.get("pending_choices")).getFirst();
			assertTrue(pending.get("options").toString().contains("nonlethal:true"));
			String transaction = pending.get("transaction").toString(), operation = op();
			dice(e).queue(19, 1);
			Map<String, Object> choice = map("option", "TAKE", "weapon", dagger, "nonlethal", true);
			Map<String, Object> resolved = e.encounters().resolveChoice(operation, campaign, transaction, choice);
			Map<String, Object> attack = SceneService.map(resolved.get("attack"));
			assertEquals(true, attack.get("nonlethal"));
			assertEquals(true, attack.get("hit"));
			assertTrue(((Number) attack.get("damage")).intValue() > 0);
			assertEquals(true, attack.get("knocked_out"));
			assertEquals("ALIVE", attack.get("life_state"));
			assertEquals(1, ((Number) attack.get("hp_after")).intValue());
			assertEquals("DEFEATED", participant(e, campaign, encounter, foe).get("status"));
			assertEquals(false, SceneService.map(resolved.get("movement_resumed")).get("completed"),
					"The knocked-out mover must stop rather than complete the saved path while Unconscious.");
			assertTrue(dice(e).exhausted());
			var after = e.db().snapshot(Ref.id(campaign, Ref.CAMPAIGN));
			assertEquals(true, e.encounters().resolveChoice(operation, campaign, transaction, choice).get("replayed"));
			assertEquals(after, e.db().snapshot(Ref.id(campaign, Ref.CAMPAIGN)));
		}
	}

	@Test
	void unsupportedHideDoesNotConsumeAnActionRollOrChangeVisibility() throws Exception {
		try (Engine e = engine(tempDb("run4-hide-capability"))) {
			String campaign = ready(e), foe = npc(e, campaign, "Bandit", "Visible brawler");
			dice(e).queue(20, 1);
			String encounter = start(e, campaign, foe, "AUTO");
			var before = e.db().snapshot(Ref.id(campaign, Ref.CAMPAIGN));
			dice(e).queue(20);
			RpgException refusal = assertThrows(RpgException.class,
					() -> e.encounters().perform(op(), campaign, encounter, "character:1", map("kind", "HIDE"), false));
			assertEquals(ErrorCode.CAPABILITY_UNAVAILABLE, refusal.code());
			assertEquals(false, refusal.details().get("action_spent"));
			assertTrue(refusal.getMessage().contains("resolve_check"));
			assertEquals(before, e.db().snapshot(Ref.id(campaign, Ref.CAMPAIGN)));
			Map<String, Object> attack = e.encounters().perform(op(), campaign, encounter, "character:1",
					map("kind", "ATTACK", "weapon", "unarmed", "target", foe), false);
			assertEquals(20, attack.get("natural"));
			assertTrue(dice(e).exhausted());
		}
	}

	@Test
	void aDeadOpponentRefusalNamesTheIllegalTargetWithoutSuggestingAllyCare() throws Exception {
		try (Engine e = engine(tempDb("run4-dead-target"))) {
			String campaign = ready(e), foe = npc(e, campaign, "Bandit", "Defeated foe");
			dice(e).queue(20, 1);
			String encounter = start(e, campaign, foe, "AUTO");
			e.runtime().applyRuntimeChange(op(), campaign, foe,
					map("kind", "DAMAGE", "amount", hp(e, foe) * 2, "reason", "A fatal separately resolved hazard."));
			RpgException refusal = assertThrows(RpgException.class, () -> e.encounters().perform(op(), campaign,
					encounter, "character:1", map("kind", "ATTACK", "weapon", "unarmed", "target", foe), false));
			assertEquals("DEAD", refusal.details().get("life_state"));
			assertTrue(refusal.getMessage().contains("no longer a legal attack target"));
			assertFalse(refusal.getMessage().contains("healing"));
		}
	}

	@Test
	void shortRestSkipsUnsupportedCompanionHitDiceAndStillCompletesThePartyRest() throws Exception {
		var file = tempDb("run4-statblock-short-rest");
		String campaign, ally;
		long before, cid;
		try (Engine e = engine(file)) {
			campaign = ready(e);
			ally = npc(e, campaign, "Guard", "Stat-block companion");
			e.party().updateMembership(op(), campaign, ally, "JOIN",
					"A companion who retains her creature stat block.");
			e.runtime().applyRuntimeChange(op(), campaign, "character:1",
					map("kind", "DAMAGE", "amount", 2, "reason", "A light wound before the rest."));
			e.runtime().applyRuntimeChange(op(), campaign, ally,
					map("kind", "DAMAGE", "amount", 1, "reason", "The companion also has a light wound."));
			cid = Ref.id(campaign, Ref.CAMPAIGN);
			before = e.db().read(tx -> GameTime.currentSeconds(tx, cid));
			dice(e).queue(6);
			Map<String, Object> rest = e.rest().rest(op(), campaign, "SHORT", map("character:1", 1, ally, 1), null);
			assertEquals(true, rest.get("completed"));
			assertEquals(3600L, ((Number) rest.get("elapsed_seconds")).longValue());
			var results = SceneService.maps(rest.get("characters"));
			Map<String, Object> pc = results.stream().filter(row -> "character:1".equals(row.get("character")))
					.findFirst().orElseThrow();
			Map<String, Object> companion = results.stream().filter(row -> ally.equals(row.get("character")))
					.findFirst().orElseThrow();
			assertEquals(1, pc.get("hit_dice_spent"));
			assertEquals(0, companion.get("hit_dice_spent"));
			assertEquals(1, companion.get("hit_dice_requested"));
			assertTrue(companion.get("hit_dice_skipped").toString().contains("Other Short Rest benefits still apply"));
			assertTrue(dice(e).exhausted(), "Only the player's supported Hit Die is rolled.");
		}
		try (Engine e = engine(file)) {
			assertEquals(before + 3600, e.db().read(tx -> GameTime.currentSeconds(tx, cid)).longValue());
			long aid = Ref.id(ally, Ref.CHARACTER);
			assertEquals(0L,
					e.db().read(tx -> tx.count(
							"SELECT COUNT(*) FROM resource_state WHERE character_id=? AND resource_ref='hit_dice'",
							aid)).longValue());
			assertEquals("ACTIVE", e.db().read(tx -> tx
					.queryOne("SELECT state FROM party_membership WHERE character_id=? ORDER BY id DESC LIMIT 1", aid)
					.orElseThrow().str("state")));
		}
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

	private static String start(Engine e, String campaign, String foe, String reactions) {
		return e.encounters()
				.start(op(), campaign, map("party", List.of("character:1"), "foes", List.of(foe)), null, null, null,
						null, null,
						map("npc_reactions", reactions, "placements",
								List.of(position("character:1", 5, 5), position(foe, 10, 5))))
				.get("encounter").toString();
	}

	private static Map<String, Object> position(String character, int x, int y) {
		return map("character", character, "x_ft", x, "y_ft", y);
	}

	private static Map<String, Object> participant(Engine e, String campaign, String encounter, String character) {
		return SceneService.maps(e.encounters().encounterState(campaign, encounter, 100, "GM").get("participants"))
				.stream().filter(row -> character.equals(row.get("character"))).findFirst().orElseThrow();
	}

	private static long hp(Engine e, String character) {
		return e.db().read(tx -> tx.get("character", Ref.id(character, Ref.CHARACTER)).lng("current_hp"));
	}

	private static ScriptedRollService dice(Engine e) {
		return (ScriptedRollService) e.roller();
	}
}
