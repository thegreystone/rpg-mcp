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
import se.hirt.mcp.rpg.persistence.Database;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static se.hirt.mcp.rpg.TestCampaigns.map;
import static se.hirt.mcp.rpg.TestCampaigns.op;

/**
 * The player must always be able to ask about their own state and their party's, in detail, whatever the harness state
 * — setup, exploration, mid-encounter, after a death.
 */
class PartyTest {

	@SuppressWarnings("unchecked")
	private static Map<String, Object> m(Object o) {
		return (Map<String, Object>) o;
	}

	@SuppressWarnings("unchecked")
	private static List<Map<String, Object>> list(Object o) {
		return (List<Map<String, Object>>) o;
	}

	@Test
	void partyIsAlwaysReadableInDetail() throws Exception {
		try (Engine engine = TestCampaigns.engine(TestCampaigns.tempDb("party"))) {
			ScriptedRollService dice = (ScriptedRollService) engine.roller();
			// During setup: drafts are listed.
			String campaign = (String) engine.campaigns().create(op(), null, null).get("campaign");
			engine.campaigns().updateSetup(op(), campaign, null,
					map("content_profile", "PEGI_16", "experience", "SURPRISE_ME", "rules", Map.of(), "continuation",
							"CHECKPOINT", "party", "SURPRISE_ME", "adventure",
							map("premise", "x", "opening_location", "Bellhaven", "immediate_goal", "y")));
			String pc = (String) engine.characters().createDraft(op(), campaign,
					map("name", "Richard", "species", "Human", "class", "Sorcerer", "ability_scores",
							map("CHA", 15, "CON", 14, "DEX", 13, "INT", 12, "WIS", 10, "STR", 8), "background",
							"Criminal", "background_ability_scores", map("CON", 2, "INT", 1), "species_skill",
							"Insight", "origin_feat",
							map("feat", "Skilled", "proficiencies", List.of("Nature", "Survival", "Medicine")),
							"skills", List.of("Deception", "Persuasion"), "personality", "Confident.",
							"starting_equipment", "A"), true).get("character");
			Map<String, Object> setupParty = engine.sessions().party(campaign, "FULL");
			assertEquals(Boolean.TRUE, setupParty.get("setup"));
			assertEquals("Richard", list(setupParty.get("members")).get(0).get("name"));

			engine.characters().commitDraft(op(), campaign, pc, null);
			engine.campaigns().commitSetup(op(), campaign, null);
			// READY_TO_PLAY, before any session: still readable.
			Map<String, Object> ready = engine.sessions().party(campaign, null);
			assertEquals(1, list(ready.get("members")).size());
			assertEquals("Bellhaven", m(ready.get("location")).get("name"));

			engine.sessions().bootstrap(op(), campaign, null);
			String mara = (String) engine.runtime()
					.materialize(op(), campaign, "Guard", "Mara", null, "dry, loyal", null, null, false).get("character");
			engine.db().mutate(Database.Mutation.of("test_join", 1L, op(), "GM", null), tx -> {
				tx.insert("party_membership",
						map("campaign_id", 1L, "character_id", 2L, "state", "ACTIVE", "joined_seq", 0));
				return new LinkedHashMap<>(Map.of("ok", true));
			});
			Map<String, Object> party = engine.sessions().party(campaign, "PLAY");
			List<Map<String, Object>> members = list(party.get("members"));
			assertEquals(2, members.size());
			Map<String, Object> richard = members.get(0);
			assertEquals(Boolean.TRUE, richard.get("player_controlled"));
			assertEquals(pc, party.get("player_character"));
			assertNotNull(richard.get("hp"));
			assertNotNull(richard.get("armor_class"));
			assertNotNull(richard.get("inventory"), "detail includes the inventory");
			assertNotNull(richard.get("money"));
			assertNotNull(richard.get("conditions"));
			assertEquals("Mara", members.get(1).get("name"));
			assertEquals(Boolean.FALSE, members.get(1).get("player_controlled"));
			assertNotNull(m(members.get(1).get("hp")).get("current"));
			assertNull(party.get("encounter"));

			// Mid-encounter: still readable, and the encounter is included.
			String wolf = (String) engine.runtime().materialize(op(), campaign, "Wolf", null, null, null, null, null, false)
					.get("character");
			dice.queue(20, 50, 10, 50, 1, 50);
			engine.encounters()
					.start(op(), campaign, map("party", List.of(pc, mara), "wild", List.of(wolf)), null, null, null,
							null, null);
			Map<String, Object> inFight = engine.sessions().party(campaign, "FULL");
			assertEquals("ENCOUNTER", inFight.get("harness_state"));
			assertNotNull(inFight.get("encounter"));
			assertEquals(2, list(inFight.get("members")).size());
			assertNotNull(list(inFight.get("members")).get(0).get("personality"), "FULL includes narrative fields");

			// After the player character dies: readable, with the fallen listed.
			dice.queue(20, 20, 20);
			engine.encounters().perform(op(), campaign, null, pc, map("kind", "DODGE"), true);
			engine.encounters().perform(op(), campaign, null, mara, map("kind", "DODGE"), true);
			engine.runtime().applyRuntimeChange(op(), campaign, pc,
					map("kind", "DAMAGE", "amount", 100, "damage_type", "fire", "reason", "test"));
			assertEquals("DEAD", engine.characters().characterSheet(campaign, pc, "SUMMARY").get("life_state"),
					"100 fire damage is massive damage");
			engine.encounters().end(op(), campaign, null, "PARTY_DEFEAT", "the wolf won");
			Map<String, Object> afterDeath = engine.sessions().party(campaign, "PLAY");
			assertEquals("CHECKPOINT_DECISION", afterDeath.get("harness_state"));
			assertEquals(1, list(afterDeath.get("members")).size(), "Mara remains");
			assertEquals("Richard", list(afterDeath.get("former_members")).get(0).get("name"));
			assertEquals("DEAD", list(afterDeath.get("former_members")).get(0).get("life_state"));
			// And the individual sheet of the dead character remains readable in full.
			assertEquals("DEAD", engine.characters().characterSheet(campaign, pc, "FULL").get("life_state"));
		}
	}
}
