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
import se.hirt.mcp.rpg.dice.ScriptedRollService;
import se.hirt.mcp.rpg.protocol.Ref;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static se.hirt.mcp.rpg.TestCampaigns.*;

/** Temporary Hit Points last until they are depleted or a Long Rest finishes. */
class TemporaryHpRestTest {

	@Test
	void completedLongRestClearsRuntimeTemporaryHp() throws Exception {
		try (Engine engine = engine(tempDb("temporary-hp-long-rest"))) {
			String campaign = committedCampaign(engine);
			engine.sessions().bootstrap(op(), campaign, null);
			String pc = "character:1";
			engine.runtime().applyRuntimeChange(op(), campaign, pc,
					map("kind", "SET_TEMP_HP", "amount", 7, "reason", "a protective blessing"));
			Map<String, Object> result = engine.rest().rest(op(), campaign, "LONG", null, List.of(pc));
			Map<String, Object> sheet = engine.characters().characterSheet(campaign, pc, "PLAY");
			Map<String, Object> character = characterRow(engine, pc);
			assertEquals(Boolean.TRUE, result.get("completed"));
			assertEquals(0, m(list(result.get("characters")).get(0).get("hp")).get("temp"));
			assertEquals(0, m(sheet.get("hp")).get("temp"));
			assertEquals(0, ((Number) character.get("temp_hp")).intValue());
		}
	}

	@Test
	void completedLongRestClearsTemporaryHpGrantedByFalseLife() throws Exception {
		try (Engine engine = engine(tempDb("temporary-hp-false-life"))) {
			String campaign = committedCampaign(engine);
			engine.sessions().bootstrap(op(), campaign, null);
			String pc = "character:1";
			engine.spells().prepare(op(), campaign, pc, null, List.of("False Life"));
			((ScriptedRollService) engine.roller()).queue(1, 2);
			Map<String, Object> cast = engine.spells().cast(op(), campaign, pc, "False Life", null, null, null);
			assertEquals(7, list(cast.get("targets")).get(0).get("temp_hp"));
			assertTemporaryHp(engine, campaign, pc, 7);
			engine.sessions().advanceTime(op(), campaign, 2 * 60, "travel after the spell");
			assertTemporaryHp(engine, campaign, pc, 7);
			Map<String, Object> result = engine.rest().rest(op(), campaign, "LONG", null, List.of(pc));
			assertEquals(0, m(list(result.get("characters")).get(0).get("hp")).get("temp"));
			assertTemporaryHp(engine, campaign, pc, 0);
		}
	}

	@Test
	void shortRestHealingPreservesTemporaryHp() throws Exception {
		try (Engine engine = engine(tempDb("temporary-hp-short-rest"))) {
			String campaign = committedCampaign(engine);
			engine.sessions().bootstrap(op(), campaign, null);
			String pc = "character:1";
			int maxHp = ((Number) characterRow(engine, pc).get("max_hp")).intValue();
			engine.runtime().applyRuntimeChange(op(), campaign, pc,
					map("kind", "DAMAGE", "amount", 3, "damage_type", "slashing", "reason", "a cut"));
			grantTemporaryHp(engine, campaign, pc, 7);
			((ScriptedRollService) engine.roller()).queue(6);
			Map<String, Object> result = engine.rest().rest(op(), campaign, "SHORT", map(pc, 1), List.of(pc));
			Map<String, Object> rested = list(result.get("characters")).get(0);
			assertEquals(1, rested.get("hit_dice_spent"));
			assertEquals(maxHp, m(rested.get("hp")).get("current"));
			assertEquals(7, m(rested.get("hp")).get("temp"));
			assertTemporaryHp(engine, campaign, pc, 7);
		}
	}

	@Test
	void selectedLongRestLeavesOtherPartyMembersTemporaryHpIntact() throws Exception {
		try (Engine engine = engine(tempDb("temporary-hp-selected-rest"))) {
			String campaign = committedCampaign(engine);
			engine.sessions().bootstrap(op(), campaign, null);
			String pc = "character:1";
			String companion = (String) engine.runtime()
					.materialize(op(), campaign, "Bandit", "Restless companion", null, null, null, null, false)
					.get("character");
			engine.party().updateMembership(op(), campaign, companion, "JOIN", "travelling together");
			grantTemporaryHp(engine, campaign, pc, 7);
			grantTemporaryHp(engine, campaign, companion, 9);
			Map<String, Object> beforeCompanion = characterRow(engine, companion);
			Map<String, Object> result = engine.rest().rest(op(), campaign, "LONG", null, List.of(pc));
			assertEquals(List.of(pc), list(result.get("characters")).stream().map(c -> c.get("character")).toList());
			assertTemporaryHp(engine, campaign, pc, 0);
			assertTemporaryHp(engine, campaign, companion, 9);
			assertEquals(beforeCompanion, characterRow(engine, companion));
		}
	}

	@Test
	void replayLeavesNewTemporaryHpIntactAndCheckpointRestoresTheEarlierGrant() throws Exception {
		try (Engine engine = engine(tempDb("temporary-hp-rest-checkpoint"))) {
			String campaign = committedCampaign(engine);
			engine.sessions().bootstrap(op(), campaign, null);
			String pc = "character:1";
			grantTemporaryHp(engine, campaign, pc, 7);
			String checkpoint = (String) engine.checkpoints().create(op(), campaign, "before resting")
					.get("checkpoint");
			String restOperation = op();
			engine.rest().rest(restOperation, campaign, "LONG", null, List.of(pc));
			assertTemporaryHp(engine, campaign, pc, 0);
			grantTemporaryHp(engine, campaign, pc, 9);
			Map<String, Object> afterNewGrant = characterRow(engine, pc);
			Map<String, Object> replay = engine.rest().rest(restOperation, campaign, "LONG", null, List.of(pc));
			assertEquals(Boolean.TRUE, replay.get("replayed"));
			assertEquals(0, m(list(replay.get("characters")).get(0).get("hp")).get("temp"));
			assertTemporaryHp(engine, campaign, pc, 9);
			assertEquals(afterNewGrant, characterRow(engine, pc));
			engine.checkpoints().restore(op(), campaign, checkpoint, "undo the Long Rest");
			assertTemporaryHp(engine, campaign, pc, 7);
			engine.rest().rest(op(), campaign, "LONG", null, List.of(pc));
			assertTemporaryHp(engine, campaign, pc, 0);
		}
	}

	private static void grantTemporaryHp(Engine engine, String campaign, String pc, int amount) {
		engine.runtime().applyRuntimeChange(op(), campaign, pc,
				map("kind", "SET_TEMP_HP", "amount", amount, "reason", "a protective blessing"));
	}

	private static void assertTemporaryHp(Engine engine, String campaign, String pc, int expected) {
		assertEquals(expected, m(engine.characters().characterSheet(campaign, pc, "PLAY").get("hp")).get("temp"));
		assertEquals(expected, ((Number) characterRow(engine, pc).get("temp_hp")).intValue());
	}

	private static Map<String, Object> characterRow(Engine engine, String pc) {
		return engine.db().read(tx -> tx.get("character", Ref.id(pc, Ref.CHARACTER)).asMap());
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> m(Object value) {
		return (Map<String, Object>) value;
	}

	@SuppressWarnings("unchecked")
	private static List<Map<String, Object>> list(Object value) {
		return (List<Map<String, Object>>) value;
	}
}
