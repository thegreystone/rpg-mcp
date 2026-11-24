/* Copyright (C) 2026 Marcus Hirt. SPDX-License-Identifier: BSD-3-Clause */
package se.hirt.mcp.rpg;

import org.junit.jupiter.api.Test;
import se.hirt.mcp.rpg.dice.ScriptedRollService;
import se.hirt.mcp.rpg.protocol.Ref;
import se.hirt.mcp.rpg.protocol.RpgException;
import se.hirt.mcp.rpg.session.GameTime;
import se.hirt.mcp.rpg.world.WorldTimeline;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static se.hirt.mcp.rpg.TestCampaigns.*;

class StableRecoveryTest {
	@Test
	void savedRecoveryRollSurvivesReloadRepeatedStabilizationAndCheckpointRewind() throws Exception {
		Path path = tempDb("stable-recovery-reload");
		String campaign, checkpoint;
		long cid, due;
		try (Engine e = engine(path)) {
			campaign = committedCampaign(e);
			e.sessions().bootstrap(op(), campaign, null);
			cid = Ref.id(campaign, Ref.CAMPAIGN);
			waitSeconds(e, campaign, 59);
			drop(e, campaign);
			((ScriptedRollService) e.roller()).queue(2);
			String operation = op();
			Map<String, Object> change = map("kind", "STABILIZE", "reason", "Mira binds the wounds.");
			e.runtime().applyRuntimeChange(operation, campaign, "character:1", change);
			due = ((Number) saves(e).get("recovery_due_seconds")).longValue();
			assertEquals(e.db().read(tx -> GameTime.currentSeconds(tx, cid)).longValue() + 7200, due);
			assertEquals(true,
					e.runtime().applyRuntimeChange(operation, campaign, "character:1", change).get("replayed"));
			checkpoint = (String) e.checkpoints().create(op(), campaign, "stable and waiting").get("checkpoint");
			waitSeconds(e, campaign, 60);
			e.runtime().applyRuntimeChange(op(), campaign, "character:1", change);
			assertEquals(due, ((Number) saves(e).get("recovery_due_seconds")).longValue());
			waitSeconds(e, campaign, 7139);
			assertEquals(0L, hp(e));
		}
		try (Engine e = engine(path)) {
			assertEquals(due, ((Number) saves(e).get("recovery_due_seconds")).longValue());
			Map<String, Object> completed = waitSeconds(e, campaign, 1);
			assertEquals(1L, hp(e));
			assertEquals("ALIVE", e.db().read(tx -> tx.get("character", 1).str("life_state")));
			assertNull(e.db().read(tx -> tx.get("character", 1).get("death_saves_json")));
			assertTrue(String.valueOf(completed.get("consequences")).contains("STABLE_RECOVERY"));
			assertTrue(prone(e));
			e.checkpoints().restore(op(), campaign, checkpoint, "rewind natural recovery");
			assertEquals(0L, hp(e));
			assertEquals(due, ((Number) saves(e).get("recovery_due_seconds")).longValue());
			waitSeconds(e, campaign, 7200);
			assertEquals(1L, hp(e));
			assertEquals(1L,
					e.db().read(tx -> tx.count(
							"SELECT COUNT(*) FROM roll WHERE campaign_id=? AND purpose LIKE 'stable recovery %'", cid))
							.longValue());
		}
	}

	@Test
	void newDamageCancelsRecoveryAndZeroHpStillCannotBeginARest() throws Exception {
		try (Engine e = engine(tempDb("stable-recovery-damage"))) {
			String campaign = committedCampaign(e);
			e.sessions().bootstrap(op(), campaign, null);
			drop(e, campaign);
			((ScriptedRollService) e.roller()).queue(1);
			e.runtime().applyRuntimeChange(op(), campaign, "character:1",
					map("kind", "STABILIZE", "reason", "First aid."));
			long originalDue = ((Number) saves(e).get("recovery_due_seconds")).longValue();
			assertThrows(RpgException.class, () -> e.rest().rest(op(), campaign, "LONG", null, List.of("character:1")));
			waitSeconds(e, campaign, 1800);
			e.runtime().applyRuntimeChange(op(), campaign, "character:1", map("kind", "DAMAGE", "amount", 1,
					"damage_type", "bludgeoning", "reason", "Falling debris reopens the wound."));
			assertEquals(false, saves(e).get("stable"));
			((ScriptedRollService) e.roller()).queue(11, 11, 11, 4);
			for (int i = 0; i < 3; i++)
				assertEquals(true, waitSeconds(e, campaign, 3600).get("interrupted"));
			waitSeconds(e, campaign, 3600);
			assertTrue(e.db().read(tx -> GameTime.currentSeconds(tx, Ref.id(campaign, Ref.CAMPAIGN))) > originalDue);
			assertEquals(0L, hp(e), "the old recovery deadline cannot heal a creature made unstable by damage");
			e.runtime().applyRuntimeChange(op(), campaign, "character:1",
					map("kind", "STABILIZE", "reason", "Fresh first aid."));
			Map<String, Object> rest = e.rest().rest(op(), campaign, "LONG", null, null);
			assertEquals(false, rest.get("completed"));
			assertEquals(0L, rest.get("elapsed_seconds"), "nobody can begin a rest at zero HP");
			waitSeconds(e, campaign, 8 * 3600);
			assertEquals(1L, hp(e), "natural recovery is not retroactive eligibility for full long-rest healing");
			assertTrue(prone(e), "recovery leaves the creature lying down until it explicitly stands");
		}
	}

	@Test
	void recoveryAfterLeavingThePartyRemainsGmOnly() throws Exception {
		try (Engine e = engine(tempDb("stable-recovery-private"))) {
			String campaign = committedCampaign(e);
			e.sessions().bootstrap(op(), campaign, null);
			String npc = (String) e.runtime()
					.materialize(op(), campaign, "Guard", "Unseen former companion", null, null, null, null, false)
					.get("character");
			e.party().updateMembership(op(), campaign, npc, "JOIN", "Briefly joins the expedition.");
			long hp = e.db().read(tx -> tx.get("character", Ref.id(npc, Ref.CHARACTER)).lng("current_hp"));
			e.runtime().applyRuntimeChange(op(), campaign, npc,
					map("kind", "DAMAGE", "amount", hp, "reason", "Knocked unconscious."));
			((ScriptedRollService) e.roller()).queue(1);
			e.runtime().applyRuntimeChange(op(), campaign, npc,
					map("kind", "STABILIZE", "reason", "First aid before leaving."));
			e.party().updateMembership(op(), campaign, npc, "LEAVE", "The party leaves the patient in a safe cottage.");
			Map<String, Object> action = waitSeconds(e, campaign, 3600);
			assertTrue(String.valueOf(action.get("consequences")).contains("visibility=GM_ONLY"));
			assertEquals("GM_ONLY", e.db().read(tx -> tx.queryOne(
					"SELECT visibility FROM event WHERE campaign_id=? AND type='STABLE_RECOVERY' ORDER BY id DESC LIMIT 1",
					Ref.id(campaign, Ref.CAMPAIGN)).orElseThrow().str("visibility")));
		}
	}

	@Test
	void threeSuccessfulDeathSavesScheduleTheSameGlobalRecoveryPath() throws Exception {
		try (Engine e = engine(tempDb("stable-recovery-saves"))) {
			String campaign = committedCampaign(e);
			e.sessions().bootstrap(op(), campaign, null);
			String wolf = (String) e.runtime()
					.materialize(op(), campaign, "Wolf", "Wolf", null, null, null, null, false).get("character");
			ScriptedRollService dice = (ScriptedRollService) e.roller();
			dice.queue(1, 20);
			String encounter = (String) e.encounters().start(op(), campaign,
					map("party", List.of("character:1"), "foes", List.of(wolf)), null, null, null, null, null)
					.get("encounter");
			drop(e, campaign);
			for (int i = 0; i < 3; i++) {
				if (i == 2)
					dice.queue(11, 3);
				else
					dice.queue(11);
				e.encounters().perform(op(), campaign, encounter, wolf, map("kind", "END_TURN"), true);
			}
			assertEquals(true, saves(e).get("stable"));
			assertEquals(3, saves(e).get("recovery_hours"));
			long due = ((Number) saves(e).get("recovery_due_seconds")).longValue();
			e.encounters().end(op(), campaign, encounter, "OTHER", "The wolf leaves the motionless party behind.");
			long now = e.db().read(tx -> GameTime.currentSeconds(tx, Ref.id(campaign, Ref.CAMPAIGN)));
			waitSeconds(e, campaign, due - now - 1);
			assertEquals(0L, hp(e));
			waitSeconds(e, campaign, 1);
			assertEquals(1L, hp(e));
		}
	}

	private static void drop(Engine e, String campaign) {
		long hp = hp(e);
		e.runtime().applyRuntimeChange(op(), campaign, "character:1",
				map("kind", "DAMAGE", "amount", hp, "damage_type", "bludgeoning", "reason", "Knocked unconscious."));
	}

	private static long hp(Engine e) {
		return e.db().read(tx -> tx.get("character", 1).lng("current_hp"));
	}

	private static Map<String, Object> saves(Engine e) {
		return e.db().read(tx -> tx.get("character", 1).map("death_saves_json"));
	}

	private static boolean prone(Engine e) {
		return e.db().read(tx -> tx.count(
				"SELECT COUNT(*) FROM active_effect WHERE character_id=1 AND condition_ref='srd5e:condition/prone'") > 0);
	}

	private static Map<String, Object> waitSeconds(Engine e, String campaign, long seconds) {
		return new WorldTimeline(e.db()).perform(op(), campaign, "WAIT", seconds, op(), Map.of());
	}
}
