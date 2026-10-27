/*
 * Copyright (C) 2026 Marcus Hirt
 *
 * This software is free:
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 * 1. Redistributions of source code must retain the above copyright notice,
 *    this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 * 3. The name of the author may not be used to endorse or promote products
 *    derived from this software without specific prior written permission.
 * THIS SOFTWARE IS PROVIDED BY THE AUTHOR ``AS IS'' AND ANY EXPRESSED OR IMPLIED
 * WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF
 * MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO
 * EVENT SHALL THE AUTHOR BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
 * PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS;
 * OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
 * WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR
 * OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED
 * OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package se.hirt.mcp.rpg;

import org.junit.jupiter.api.Test;
import se.hirt.mcp.rpg.dice.ScriptedRollService;
import se.hirt.mcp.rpg.party.TacticalControl;
import se.hirt.mcp.rpg.protocol.Ref;
import se.hirt.mcp.rpg.protocol.RpgException;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static se.hirt.mcp.rpg.TestCampaigns.*;

/** Companion decision routing changes neither primary identity nor tactical action ownership. */
class CompanionTacticalControlTest {

	@Test
	void companionOpportunityAttacksAreAutomaticUntilDetailedPlayerControlIsSelected() throws Exception {
		for (boolean detailed : List.of(false, true)) {
			try (Engine engine = engine(tempDb("companion-opportunity"))) {
				String campaign = committedCampaign(engine);
				engine.sessions().bootstrap(op(), campaign, null);
				String companion = bandit(engine, campaign, "Mira"), foe = bandit(engine, campaign, "Road sentry");
				engine.party().updateMembership(op(), campaign, companion, "JOIN", "An established companion.");
				ScriptedRollService dice = (ScriptedRollService) engine.roller();
				dice.queue(1, 2, 20);
				String encounter = (String) engine.encounters()
						.start(op(), campaign, map("party", List.of("character:1", companion), "foes", List.of(foe)),
								null, null, null, null, null,
								map("placements",
										List.of(map("character", "character:1", "x_ft", 0, "y_ft", 0),
												map("character", companion, "x_ft", 25, "y_ft", 25),
												map("character", foe, "x_ft", 30, "y_ft", 25))))
						.get("encounter");
				if (detailed)
					new TacticalControl(engine.db()).set(op(), campaign, List.of(companion), "PLAYER", "ENCOUNTER",
							encounter, "I will direct Mira this fight.");
				Map<String, Object> before = engine.encounters().encounterState(campaign, encounter, 5);
				assertEquals(detailed ? "PLAYER" : "DM", actor(before, companion).get("controller"));
				assertEquals("DM", actor(before, foe).get("controller"));
				assertThrows(RpgException.class,
						() -> engine.encounters().perform(op(), campaign, encounter, companion,
								map("kind", "MOVE", "x_ft", 25, "y_ft", 30), false),
						"control grants no out-of-turn movement");
				if (!detailed)
					dice.queue(1);
				Map<String, Object> moving = engine.encounters().perform(op(), campaign, encounter, foe,
						map("kind", "MOVE", "x_ft", 45, "y_ft", 25), false);
				if (detailed) {
					Map<String, Object> choice = list(moving.get("pending_choices")).getFirst();
					assertEquals(companion, choice.get("chooser"));
					assertEquals("PLAYER", choice.get("decision_controller"));
					assertEquals(Boolean.TRUE, m(moving.get("state")).get("awaiting_player_input"));
					assertEquals(List.of(30.0, 25.0), actor(m(moving.get("state")), foe).get("position_ft"));
					String transaction = choice.get("transaction").toString();
					new TacticalControl(engine.db()).set(op(), campaign, List.of(companion), "DM", "ENCOUNTER",
							encounter, "Mira can decide the remaining choices.");
					Map<String, Object> handedBack = engine.encounters().encounterState(campaign, encounter, 5);
					Map<String, Object> retained = list(handedBack.get("pending_choices")).getFirst();
					assertEquals(transaction, retained.get("transaction"),
							"handing control back preserves the existing choice");
					assertEquals("DM", retained.get("decision_controller"));
					assertEquals(Boolean.FALSE, handedBack.get("awaiting_player_input"));
					assertEquals("WAITING_CHOICE", handedBack.get("status"));
					Map<String, Object> resumed = engine.encounters().resolveChoice(op(), campaign, transaction,
							map("option", "DECLINE"));
					assertEquals(List.of(45.0, 25.0), actor(m(resumed.get("state")), foe).get("position_ft"));
					assertEquals(15.0, m(actor(m(resumed.get("state")), foe).get("movement")).get("remaining_ft"));
				} else {
					assertNull(moving.get("pending_choices"));
					assertEquals(companion, list(moving.get("opportunity_attacks")).getFirst().get("reactor"));
					assertEquals(Boolean.FALSE, list(moving.get("opportunity_attacks")).getFirst().get("hit"));
				}
				assertPrimaryUnchanged(engine, campaign);
				assertTrue(dice.exhausted());
				engine.encounters().end(op(), campaign, encounter, "OTHER", "The parties withdraw.");
				assertEquals("DEFAULT", assignment(engine, campaign, companion).get("source"));
			}
		}
	}

	@Test
	void detailedControlOfACompanionDoesNotPromptForHostileReactions() throws Exception {
		try (Engine engine = engine(tempDb("companion-hostile-reaction"))) {
			String campaign = committedCampaign(engine);
			engine.sessions().bootstrap(op(), campaign, null);
			String companion = bandit(engine, campaign, "Mira"), foe = bandit(engine, campaign, "Sentry");
			engine.party().updateMembership(op(), campaign, companion, "JOIN", "An established companion.");
			ScriptedRollService dice = (ScriptedRollService) engine.roller();
			dice.queue(1, 20, 2);
			String encounter = (String) engine.encounters()
					.start(op(), campaign, map("party", List.of("character:1", companion), "foes", List.of(foe)), null,
							null, null, null, null,
							map("placements",
									List.of(map("character", "character:1", "x_ft", 0, "y_ft", 0),
											map("character", companion, "x_ft", 25, "y_ft", 25),
											map("character", foe, "x_ft", 20, "y_ft", 25))))
					.get("encounter");
			new TacticalControl(engine.db()).set(op(), campaign, List.of(companion), "PLAYER", "ENCOUNTER", encounter,
					null);
			dice.queue(1);
			Map<String, Object> move = engine.encounters().perform(op(), campaign, encounter, companion,
					map("kind", "MOVE", "x_ft", 40, "y_ft", 25), false);
			assertNull(move.get("pending_choices"));
			assertEquals(foe, list(move.get("opportunity_attacks")).getFirst().get("reactor"));
			assertEquals(Boolean.FALSE, list(move.get("opportunity_attacks")).getFirst().get("hit"));
			assertEquals(15.0, m(actor(m(move.get("state")), companion).get("movement")).get("remaining_ft"));
			assertEquals(Boolean.TRUE, m(move.get("state")).get("awaiting_player_input"));
			assertPrimaryUnchanged(engine, campaign);
			assertTrue(dice.exhausted());
		}
	}

	@Test
	void companionShieldRoutesToItsControllerAndEncounterControlExpiresAfterTheFight() throws Exception {
		for (boolean detailed : List.of(false, true)) {
			try (Engine engine = engine(tempDb("companion-shield"))) {
				String campaign = wizardParty(engine), companion = "character:2";
				String foe = bandit(engine, campaign, "Cutpurse");
				ScriptedRollService dice = (ScriptedRollService) engine.roller();
				dice.queue(1, 2, 20);
				String encounter = (String) engine.encounters()
						.start(op(), campaign, map("party", List.of("character:1", companion), "foes", List.of(foe)),
								null, null, null, null, null,
								map("placements",
										List.of(map("character", "character:1", "x_ft", 0, "y_ft", 0),
												map("character", companion, "x_ft", 25, "y_ft", 25),
												map("character", foe, "x_ft", 30, "y_ft", 25))))
						.get("encounter");
				if (detailed)
					new TacticalControl(engine.db()).set(op(), campaign, List.of(companion), "PLAYER", "ENCOUNTER",
							encounter, null);
				int hp = ((Number) m(engine.characters().characterSheet(campaign, companion, "PLAY").get("hp"))
						.get("current")).intValue();
				dice.queue(10);
				Map<String, Object> hit = engine.encounters().perform(op(), campaign, encounter, foe,
						map("kind", "ATTACK", "attack", "Scimitar", "target", companion), true);
				Map<String, Object> finished;
				if (detailed) {
					Map<String, Object> choice = m(hit.get("pending_choice"));
					assertEquals("SHIELD_SPELL", choice.get("kind"));
					assertEquals("PLAYER", choice.get("decision_controller"));
					assertEquals(companion, choice.get("chooser"));
					assertEquals(2, slots(engine, campaign, companion), "an offered Shield has spent no slot yet");
					finished = engine.encounters().resolveChoice(op(), campaign, choice.get("transaction").toString(),
							map("option", "CAST_SHIELD"));
					assertEquals(Boolean.FALSE, m(finished.get("attack")).get("hit"));
				} else {
					assertNull(hit.get("pending_choice"));
					assertEquals(Boolean.FALSE, hit.get("hit"));
					assertEquals("Shield", m(hit.get("shield")).get("cast"));
					finished = hit;
				}
				assertEquals(1, slots(engine, campaign, companion));
				assertEquals(hp,
						m(engine.characters().characterSheet(campaign, companion, "PLAY").get("hp")).get("current"));
				assertEquals(companion, m(finished.get("next_turn")).get("character"));
				assertEquals(detailed ? "PLAYER" : "DM", m(finished.get("next_turn")).get("controller"));
				assertEquals(detailed, m(finished.get("state")).get("awaiting_player_input"));
				assertPrimaryUnchanged(engine, campaign);
				assertTrue(dice.exhausted());
				engine.encounters().end(op(), campaign, encounter, "OTHER", "The clash ends.");
				assertEquals("DM", assignment(engine, campaign, companion).get("controller"));
				assertEquals("DEFAULT", assignment(engine, campaign, companion).get("source"));
				assertPrimaryUnchanged(engine, campaign);
			}
		}
	}

	private static String wizardParty(Engine engine) {
		String campaign = (String) engine.campaigns().create(op(), null, null).get("campaign");
		engine.campaigns().updateSetup(op(), campaign, null,
				map("content_profile", "PEGI_16", "experience", "SURPRISE_ME", "rules", Map.of(), "continuation",
						"CHECKPOINT", "party", "SURPRISE_ME", "adventure", map("premise", "Companions defend a tower",
								"opening_location", "Tower", "immediate_goal", "Meet the sentry")));
		for (boolean primary : List.of(true, false)) {
			String character = (String) engine.characters().createDraft(op(), campaign,
					map("name", primary ? "Aric" : "Mira", "species", "Human", "class", "Wizard", "ability_scores",
							map("INT", 15, "DEX", 14, "CON", 13, "WIS", 12, "CHA", 10, "STR", 8), "skills",
							List.of("Arcana", "History"), "personality", "An experienced companion.", "background",
							"Criminal", "background_ability_scores", map("CON", 2, "INT", 1), "species_skill",
							"Insight", "origin_feat",
							map("feat", "Skilled", "proficiencies", List.of("Nature", "Survival", "Medicine")),
							"cantrips", List.of("Fire Bolt"), "spells", List.of("Shield", "Magic Missile")),
					primary).get("character");
			engine.characters().commitDraft(op(), campaign, character, null);
		}
		engine.campaigns().commitSetup(op(), campaign, null);
		engine.sessions().bootstrap(op(), campaign, null);
		engine.party().updateMembership(op(), campaign, "character:2", "JOIN", "An established companion.");
		return campaign;
	}

	private static String bandit(Engine engine, String campaign, String name) {
		return (String) engine.runtime().materialize(op(), campaign, "Bandit", name, null, null, null, null, false)
				.get("character");
	}

	private static Map<String, Object> assignment(Engine engine, String campaign, String character) {
		return engine.db().read(
				tx -> TacticalControl.assignment(tx, Ref.id(campaign, Ref.CAMPAIGN), Ref.id(character, Ref.CHARACTER)));
	}

	private static void assertPrimaryUnchanged(Engine engine, String campaign) {
		assertEquals("PRIMARY_CHARACTER", assignment(engine, campaign, "character:1").get("source"));
		assertEquals("PLAYER", assignment(engine, campaign, "character:1").get("controller"));
		assertEquals(1L,
				engine.db()
						.read(tx -> tx.queryOne(
								"SELECT character_id FROM player_control_assignment WHERE campaign_id=? AND active=1",
								Ref.id(campaign, Ref.CAMPAIGN)).orElseThrow().lng("character_id"))
						.longValue());
	}

	private static int slots(Engine engine, String campaign, String character) {
		return ((Number) m(
				m(m(engine.characters().characterSheet(campaign, character, "PLAY").get("spellcasting")).get("slots"))
						.get("1"))
				.get("current")).intValue();
	}

	private static Map<String, Object> actor(Map<String, Object> state, String ref) {
		return list(state.get("participants")).stream().filter(p -> ref.equals(p.get("character"))).findFirst()
				.orElseThrow();
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
