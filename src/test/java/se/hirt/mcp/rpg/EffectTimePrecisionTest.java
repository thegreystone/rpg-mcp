/* Copyright (C) 2026 Marcus Hirt. SPDX-License-Identifier: BSD-3-Clause */
package se.hirt.mcp.rpg;

import org.junit.jupiter.api.Test;
import se.hirt.mcp.rpg.persistence.Database;
import se.hirt.mcp.rpg.protocol.Ref;
import se.hirt.mcp.rpg.rules.Effects;
import se.hirt.mcp.rpg.session.GameTime;
import se.hirt.mcp.rpg.world.WorldTimeline;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static se.hirt.mcp.rpg.TestCampaigns.*;

class EffectTimePrecisionTest {

	@Test
	void minuteEffectStartedAtSecond59Lasts60SecondsAcrossReloadAndCheckpoint() throws Exception {
		Path path = tempDb("precise-effect");
		String campaign, checkpoint;
		long cid, effect, started;
		try (Engine e = engine(path)) {
			campaign = committedCampaign(e);
			e.sessions().bootstrap(op(), campaign, null);
			cid = Ref.id(campaign, Ref.CAMPAIGN);
			waitSeconds(e, campaign, 59);
			started = e.db().read(tx -> GameTime.currentSeconds(tx, cid));
			e.runtime().applyRuntimeChange(op(), campaign, "character:1", map("kind", "ADD_CONDITION", "condition",
					"POISONED", "duration", "1 minute", "reason", "venom lasts one full minute"));
			effect = e.db().read(
					tx -> tx.queryOne("SELECT id FROM active_effect WHERE campaign_id=?", cid).orElseThrow().id());
			assertEquals(started + 60,
					e.db().read(tx -> tx.get("active_effect", effect).lng("end_seconds")).longValue());
			checkpoint = (String) e.checkpoints().create(op(), campaign, "venom begins").get("checkpoint");
			waitSeconds(e, campaign, 1);
			assertTrue(present(e, effect), "crossing the minute boundary must not expire a one-minute effect");
			waitSeconds(e, campaign, 58);
			assertTrue(present(e, effect));
		}
		try (Engine e = engine(path)) {
			assertTrue(present(e, effect));
			waitSeconds(e, campaign, 1);
			assertFalse(present(e, effect), "expiry occurs at exactly sixty elapsed seconds");
			e.checkpoints().restore(op(), campaign, checkpoint, "rewind venom timer");
			assertEquals(started, e.db().read(tx -> GameTime.currentSeconds(tx, cid)).longValue());
			assertEquals(started + 60,
					e.db().read(tx -> tx.get("active_effect", effect).lng("end_seconds")).longValue());
			waitSeconds(e, campaign, 59);
			assertTrue(present(e, effect));
			waitSeconds(e, campaign, 1);
			assertFalse(present(e, effect));
		}
	}

	@Test
	void remainingEncounterRoundsBecomeExactSixSecondDurations() throws Exception {
		try (Engine e = engine(tempDb("precise-round-conversion"))) {
			String campaign = committedCampaign(e);
			e.sessions().bootstrap(op(), campaign, null);
			long cid = Ref.id(campaign, Ref.CAMPAIGN);
			waitSeconds(e, campaign, 59);
			String wolf = (String) e.runtime().materialize(op(), campaign, "Wolf", null, null, null, null, null, false)
					.get("character");
			String encounter = (String) e.encounters().start(op(), campaign,
					map("party", List.of("character:1"), "wolves", List.of(wolf)), null, null, null, null, null)
					.get("encounter");
			e.runtime().applyRuntimeChange(op(), campaign, "character:1", map("kind", "ADD_CONDITION", "condition",
					"POISONED", "duration", "2 rounds", "reason", "brief venom"));
			long effect = e.db().read(
					tx -> tx.queryOne("SELECT id FROM active_effect WHERE campaign_id=?", cid).orElseThrow().id());
			e.encounters().end(op(), campaign, encounter, "OTHER", "the wolf withdraws");
			long ended = e.db().read(tx -> GameTime.currentSeconds(tx, cid));
			assertEquals(ended + 12, e.db().read(tx -> tx.get("active_effect", effect).lng("end_seconds")).longValue());
			waitSeconds(e, campaign, 11);
			assertTrue(present(e, effect));
			waitSeconds(e, campaign, 1);
			assertFalse(present(e, effect));
		}
	}

	@Test
	void legacyDurationWritersGetPreciseExpiryAndOldUndoImagesRetainMinuteSemantics() throws Exception {
		try (Engine e = engine(tempDb("precise-central-effect"))) {
			String campaign = committedCampaign(e);
			e.sessions().bootstrap(op(), campaign, null);
			long cid = Ref.id(campaign, Ref.CAMPAIGN);
			waitSeconds(e, campaign, 59);
			long started = e.db().read(tx -> GameTime.currentSeconds(tx, cid));
			long effect = ((Number) e.db()
					.mutate(Database.Mutation.of("legacy-duration-writer", cid, op(), "GM", null), tx -> {
						long id = Effects.add(tx, cid, 1, null, null, "legacy duration shape",
								"srd5e:condition/poisoned", null, map("expires_seq", GameTime.currentSeq(tx, cid) + 1),
								null, null, "GM");
						return map("effect", id);
					}).get("effect")).longValue();
			assertEquals(started + 60,
					e.db().read(tx -> tx.get("active_effect", effect).lng("end_seconds")).longValue());
			e.db().mutate(Database.Mutation.of("legacy-effect-undo", cid, op(), "GM", null), tx -> {
				var old = tx.get("active_effect", effect).asMap();
				old.remove("end_seconds");
				tx.applyUndo(map("op", "restore", "table", "active_effect", "pk", effect, "row", old));
				return Map.of();
			});
			assertNull(e.db().read(tx -> tx.get("active_effect", effect).lng("end_seconds")));
			waitSeconds(e, campaign, 1);
			assertFalse(present(e, effect), "a historical before-image keeps its original minute-boundary expiry");
		}
	}

	private static void waitSeconds(Engine e, String campaign, long seconds) {
		new WorldTimeline(e.db()).perform(op(), campaign, "WAIT", seconds, op(), Map.of());
	}

	private static boolean present(Engine e, long effect) {
		return e.db().read(tx -> tx.find("active_effect", effect).isPresent());
	}
}
