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

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static se.hirt.mcp.rpg.TestCampaigns.committedCampaign;
import static se.hirt.mcp.rpg.TestCampaigns.engine;
import static se.hirt.mcp.rpg.TestCampaigns.map;
import static se.hirt.mcp.rpg.TestCampaigns.op;
import static se.hirt.mcp.rpg.TestCampaigns.tempDb;

/**
 * Two things the engine used to leave to the GM's memory: a stat block that says "makes two attacks" only ever got
 * one, and a spell that grants the target another save at the end of its turn never rolled it.
 */
class EncounterRulesTest {

	@SuppressWarnings("unchecked")
	private static Map<String, Object> m(Object o) {
		return (Map<String, Object>) o;
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
			String encounter = (String) engine.encounters()
					.start(op(), campaign, map("a", List.of(scout), "b", List.of(bandit)), null, null, null, null,
							null).get("encounter");

			Map<String, Object> state = engine.encounters().encounterState(campaign, encounter, 3);
			String first = (String) m(m(state).get("turn")).get("character");
			String other = first.equals(scout) ? bandit : scout;

			if (first.equals(scout)) {
				Map<String, Object> one = engine.encounters().perform(op(), campaign, encounter, scout,
						map("kind", "ATTACK", "attack", "Shortsword", "target", other), true);
				assertEquals(1, one.get("attacks_remaining"), "a Scout makes two attacks: " + one.get("summary"));
				assertEquals(Boolean.FALSE, one.getOrDefault("turn_advanced", Boolean.FALSE),
						"the turn does not end under the actor while an attack remains");
				Map<String, Object> two = engine.encounters().perform(op(), campaign, encounter, scout,
						map("kind", "ATTACK", "attack", "Shortsword", "target", other), true);
				assertNull(two.get("attacks_remaining"), "both attacks used");
				assertEquals(Boolean.TRUE, two.get("turn_advanced"));
			} else {
				// The bandit has no Multiattack, so one attack ends its turn exactly as before.
				Map<String, Object> one = engine.encounters().perform(op(), campaign, encounter, bandit,
						map("kind", "ATTACK", "attack", "Scimitar", "target", other), true);
				assertNull(one.get("attacks_remaining"));
				assertEquals(Boolean.TRUE, one.get("turn_advanced"));
			}
		}
	}

	@Test
	void aSpellThatGrantsAnotherSaveRollsItAtTheEndOfTheTargetsTurn() throws Exception {
		Path db = tempDb("enc-repeat-save");
		try (Engine engine = engine(db)) {
			String campaign = committedCampaign(engine);
			engine.sessions().bootstrap(op(), campaign, null);
			String pc = (String) engine.sessions().party(campaign, "SUMMARY").get("player_character");
			engine.spells().prepare(op(), campaign, pc, null, List.of("Sleep", "Magic Missile"));
			String bandit = (String) engine.runtime()
					.materialize(op(), campaign, "Bandit", "Grubb", null, null, null, null, false).get("character");
			String encounter = (String) engine.encounters()
					.start(op(), campaign, map("party", List.of(pc), "foes", List.of(bandit)), null, null, null, null,
							null).get("encounter");

			// Put the bandit under Sleep however the initiative fell, then let its turn end.
			Map<String, Object> state = engine.encounters().encounterState(campaign, encounter, 3);
			if (!pc.equals(m(m(state).get("turn")).get("character"))) {
				engine.encounters().perform(op(), campaign, encounter, bandit, map("kind", "DODGE"), true);
			}
			Map<String, Object> cast = engine.encounters().perform(op(), campaign, encounter, pc,
					map("kind", "CAST", "spell", "Sleep", "targets", List.of(bandit)), true);
			assertNotNull(cast, "sleep cast");

			Map<String, Object> ended = engine.encounters()
					.perform(op(), campaign, encounter, bandit, map("kind", "END_TURN"), true);
			List<Map<String, Object>> saves = (List<Map<String, Object>>) ended.get("repeat_saves");
			assertNotNull(saves, "the engine rolls the repeat save itself: " + ended.keySet());
			Map<String, Object> save = saves.get(0);
			assertEquals("Sleep", save.get("spell"));
			assertEquals("WIS", save.get("ability"));
			assertNotNull(save.get("roll"));
			assertNotNull(save.get("dc"));
			// Either it shook the spell off, or it failed again and Sleep escalates to Unconscious.
			if (Boolean.TRUE.equals(save.get("saved"))) {
				assertEquals(Boolean.TRUE, save.get("effect_ended"));
			} else {
				assertEquals("UNCONSCIOUS", save.get("escalated_to"), "second failure: " + save);
			}
		}
	}

	/** The content profile carries running guidance, not just a label, so it reaches the GM at play time. */
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
