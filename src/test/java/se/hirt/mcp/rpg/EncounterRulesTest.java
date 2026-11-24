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
import se.hirt.mcp.rpg.protocol.ErrorCode;
import se.hirt.mcp.rpg.protocol.RpgException;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static se.hirt.mcp.rpg.TestCampaigns.committedCampaign;
import static se.hirt.mcp.rpg.TestCampaigns.engine;
import static se.hirt.mcp.rpg.TestCampaigns.map;
import static se.hirt.mcp.rpg.TestCampaigns.meleeZones;
import static se.hirt.mcp.rpg.TestCampaigns.op;
import static se.hirt.mcp.rpg.TestCampaigns.tempDb;

/**
 * Two things the engine used to leave to the GM's memory: a stat block that says "makes two
 * attacks" only ever got one, and a spell that grants the target another save at the end of its
 * turn never rolled it.
 */
class EncounterRulesTest {

	@SuppressWarnings("unchecked")
	private static Map<String, Object> m(Object o) {
		return (Map<String, Object>) o;
	}

	@Test
	void anIncapacitatedActorCannotActButStillEndsItsTurn() throws Exception {
		Path db = tempDb("enc-incapacitated");
		try (Engine engine = engine(db)) {
			String campaign = committedCampaign(engine);
			engine.sessions().bootstrap(op(), campaign, null);
			String pc = "character:1";
			String bandit = (String) engine.runtime()
					.materialize(op(), campaign, "Bandit", "Grubb", null, null, null, null, false).get("character");
			Map<String, Object> started = engine.encounters().start(op(), campaign,
					map("party", List.of(pc), "road", List.of(bandit)), null, map(bandit, "near"), null, null, null);
			String encounter = (String) started.get("encounter");
			String actor = (String) m(started.get("turn")).get("character");
			String other = actor.equals(pc) ? bandit : pc;

			// Held on its own turn (Hold Person by fiat): no attack, no step, but the turn still passes.
			engine.runtime().applyRuntimeChange(op(), campaign, actor, map("kind", "ADD_CONDITION", "condition",
					"PARALYZED", "duration", map("rounds", 3), "reason", "held"));
			RpgException attack = assertThrows(RpgException.class, () -> engine.encounters().perform(op(), campaign,
					encounter, actor, map("kind", "ATTACK", "target", other), actor.equals(pc)));
			assertEquals(ErrorCode.OPERATION_NOT_ALLOWED, attack.code());
			assertTrue(attack.getMessage().contains("PARALYZED"), attack.getMessage());
			RpgException move = assertThrows(RpgException.class, () -> engine.encounters().perform(op(), campaign,
					encounter, actor, map("kind", "MOVE", "zone", "far"), actor.equals(pc)));
			assertEquals(ErrorCode.OPERATION_NOT_ALLOWED, move.code());
			assertTrue(move.getMessage().contains("Speed 0"), move.getMessage());
			Map<String, Object> ended = engine.encounters().perform(op(), campaign, encounter, actor,
					map("kind", "END_TURN"), actor.equals(pc));
			assertEquals(other, m(ended.get("next_turn")).get("character"), "the turn passes to the other side");

			// Incapacitated alone leaves Speed untouched: the other one may still move, but not act.
			engine.runtime().applyRuntimeChange(op(), campaign, other, map("kind", "ADD_CONDITION", "condition",
					"INCAPACITATED", "duration", map("rounds", 3), "reason", "dazed"));
			RpgException dodge = assertThrows(RpgException.class, () -> engine.encounters().perform(op(), campaign,
					encounter, other, map("kind", "DODGE"), other.equals(pc)));
			assertEquals(ErrorCode.OPERATION_NOT_ALLOWED, dodge.code());
			Map<String, Object> moved = engine.encounters().perform(op(), campaign, encounter, other,
					map("kind", "MOVE", "zone", "far"), other.equals(pc));
			assertEquals("far", moved.get("zone"));
		}
	}

	@Test
	void aStunnedActorCanMoveButCannotAct() throws Exception {
		Path db = tempDb("enc-stunned");
		try (Engine engine = engine(db)) {
			String campaign = committedCampaign(engine);
			engine.sessions().bootstrap(op(), campaign, null);
			String pc = "character:1";
			String bandit = (String) engine.runtime()
					.materialize(op(), campaign, "Bandit", "Grubb", null, null, null, null, false).get("character");
			Map<String, Object> started = engine.encounters().start(op(), campaign,
					map("party", List.of(pc), "road", List.of(bandit)), null, map(pc, "gate", bandit, "road"), null,
					null, null);
			String encounter = (String) started.get("encounter");
			String actor = (String) m(started.get("turn")).get("character");
			String other = actor.equals(pc) ? bandit : pc;
			engine.runtime().applyRuntimeChange(op(), campaign, actor, map("kind", "ADD_CONDITION", "condition",
					"STUNNED", "duration", map("rounds", 3), "reason", "stunned"));

			// SRD 5.2.1 Stunned includes Incapacitated, but leaves normal movement available.
			for (String kind : List.of("ATTACK", "DASH", "DODGE")) {
				RpgException denied = assertThrows(RpgException.class, () -> engine.encounters().perform(op(), campaign,
						encounter, actor, map("kind", kind, "target", other, "zone", "far"), false));
				assertEquals(ErrorCode.OPERATION_NOT_ALLOWED, denied.code());
				assertTrue(denied.getMessage().contains("STUNNED"), denied.getMessage());
			}
			Map<String, Object> moved = engine.encounters().perform(op(), campaign, encounter, actor,
					map("kind", "MOVE", "zone", "far"), false);
			assertEquals("far", moved.get("zone"));
			Map<String, Object> ended = engine.encounters().perform(op(), campaign, encounter, actor,
					map("kind", "END_TURN"), false);
			assertEquals(other, m(ended.get("next_turn")).get("character"));
		}
	}

	@Test
	void multiattackKeepsTheTurnOpenUntilTheAttacksAreUsed() throws Exception {
		Path db = tempDb("enc-multiattack");
		try (Engine engine = engine(db)) {
			String campaign = committedCampaign(engine);
			engine.sessions().bootstrap(op(), campaign, null);
			// A Scout's stat block grants two attacks; a Bandit's grants none.
			String scout = (String) engine.runtime()
					.materialize(op(), campaign, "Scout", "Pell", null, null, null, null, false).get("character");
			String bandit = (String) engine.runtime()
					.materialize(op(), campaign, "Bandit", "Grubb", null, null, null, null, false).get("character");
			// Scripted initiative: the Scout rolls 20 and the Bandit 1 (no tie-break die is rolled, SRD 5.2.1
			// "Initiative"), so the Scout always acts first and the Multiattack branch is the one exercised.
			ScriptedRollService dice = (ScriptedRollService) engine.roller();
			dice.queue(20, 1);
			Map<String, Object> started = engine.encounters().start(op(), campaign,
					map("a", List.of(scout), "b", List.of(bandit)), null, meleeZones(scout, bandit), null, null, null);
			String encounter = (String) started.get("encounter");
			assertEquals(scout, m(started.get("turn")).get("character"), "the Scout won initiative");
			assertTrue(dice.exhausted());

			// Two melee attacks on the bandit (AC 12): a natural 1 misses and rolls no damage.
			dice.queue(1);
			Map<String, Object> one = engine.encounters().perform(op(), campaign, encounter, scout,
					map("kind", "ATTACK", "attack", "Shortsword", "target", bandit), true);
			assertEquals(1, one.get("attacks_remaining"), "a Scout makes two attacks: " + one.get("summary"));
			assertEquals(Boolean.FALSE, one.getOrDefault("turn_advanced", Boolean.FALSE),
					"the turn does not end under the actor while an attack remains");
			dice.queue(1);
			Map<String, Object> two = engine.encounters().perform(op(), campaign, encounter, scout,
					map("kind", "ATTACK", "attack", "Shortsword", "target", bandit), true);
			assertNull(two.get("attacks_remaining"), "both attacks used");
			assertEquals(Boolean.TRUE, two.get("turn_advanced"));
			assertEquals(bandit, m(two.get("next_turn")).get("character"));
			assertTrue(dice.exhausted());

			// The bandit has no Multiattack, so one attack ends its turn.
			dice.queue(1);
			Map<String, Object> single = engine.encounters().perform(op(), campaign, encounter, bandit,
					map("kind", "ATTACK", "attack", "Scimitar", "target", scout), true);
			assertNull(single.get("attacks_remaining"));
			assertEquals(Boolean.TRUE, single.get("turn_advanced"));
			assertTrue(dice.exhausted());
		}
	}

	@Test
	void aSpellThatGrantsAnotherSaveRollsItAtTheEndOfTheTargetsTurn() throws Exception {
		// A failed first save, then a failed repeat save: Sleep escalates to Unconscious (SRD 5.2.1 "Sleep").
		Map<String, Object> failed = sleepRepeatSave(tempDb("enc-repeat-save-fail"), 1);
		assertEquals(Boolean.FALSE, failed.get("saved"));
		assertEquals("UNCONSCIOUS", failed.get("escalated_to"), "second failure: " + failed);
		assertNull(failed.get("effect_ended"));

		// A failed first save, then a passing repeat save: the effect ends.
		Map<String, Object> passed = sleepRepeatSave(tempDb("enc-repeat-save-pass"), 20);
		assertEquals(Boolean.TRUE, passed.get("saved"));
		assertEquals(Boolean.TRUE, passed.get("effect_ended"));
		assertNull(passed.get("escalated_to"));
	}

	/**
	 * Casts Sleep on a bandit that fails its first save (a scripted 1) and returns the repeat save
	 * the engine rolls at the end of the bandit's next turn with the given d20.
	 */
	private static Map<String, Object> sleepRepeatSave(Path db, int repeatD20) throws Exception {
		try (Engine engine = engine(db)) {
			ScriptedRollService dice = (ScriptedRollService) engine.roller();
			String campaign = committedCampaign(engine);
			engine.sessions().bootstrap(op(), campaign, null);
			String pc = (String) engine.sessions().party(campaign, "SUMMARY").get("player_character");
			engine.spells().prepare(op(), campaign, pc, null, List.of("Sleep", "Magic Missile"));
			String bandit = (String) engine.runtime()
					.materialize(op(), campaign, "Bandit", "Grubb", null, null, null, null, false).get("character");
			// Initiative: the sorcerer first, then the bandit.
			dice.queue(20, 10);
			Map<String, Object> started = engine.encounters().start(op(), campaign,
					map("party", List.of(pc), "foes", List.of(bandit)), null, null, null, null, null);
			String encounter = (String) started.get("encounter");
			assertEquals(pc, m(started.get("turn")).get("character"));

			dice.queue(1);
			Map<String, Object> cast = engine.encounters().perform(op(), campaign, encounter, pc,
					map("kind", "CAST", "spell", "Sleep", "targets", List.of(bandit)), true);
			assertEquals(Boolean.FALSE, list(cast.get("targets")).get(0).get("saved"), "the first save fails");

			dice.queue(repeatD20);
			Map<String, Object> ended = engine.encounters().perform(op(), campaign, encounter, bandit,
					map("kind", "END_TURN"), true);
			List<Map<String, Object>> saves = list(ended.get("repeat_saves"));
			assertNotNull(saves, "the engine rolls the repeat save itself: " + ended.keySet());
			Map<String, Object> save = saves.get(0);
			assertEquals("Sleep", save.get("spell"));
			assertEquals("WIS", save.get("ability"));
			assertEquals(List.of(repeatD20), m(save.get("roll")).get("dice"));
			assertNotNull(save.get("dc"));
			assertTrue(dice.exhausted());
			return save;
		}
	}

	@SuppressWarnings("unchecked")
	private static List<Map<String, Object>> list(Object o) {
		return (List<Map<String, Object>>) o;
	}

	/**
	 * The content profile carries running guidance, not just a label, so it reaches the GM at play
	 * time.
	 */
	@Test
	void adultProfileSaysScenesArePlayedThroughRatherThanCutAway() {
		String guidance = se.hirt.mcp.rpg.choice.ContentProfile.PEGI_18.guidance();
		assertTrue(guidance.contains("STAY IN THE MOMENT AND STAY PHYSICAL"), guidance);
		assertTrue(guidance.toLowerCase().contains("fade to black"), guidance);
		assertTrue(guidance.toLowerCase().contains("consent"), guidance);
		for (se.hirt.mcp.rpg.choice.ContentProfile p : se.hirt.mcp.rpg.choice.ContentProfile.values()) {
			assertTrue(p.guidance().length() > 60, p + " has no running guidance");
		}
	}
}
