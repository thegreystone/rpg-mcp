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

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static se.hirt.mcp.rpg.TestCampaigns.map;
import static se.hirt.mcp.rpg.TestCampaigns.op;

/**
 * Reactions and pending choices (MCP_PROTOCOL.md §15.3/§15.4, I-31, I-33): opportunity attacks,
 * Disengage, Shield as a reaction, and knocking a creature out instead of killing it.
 */
class ReactionTest {

	@SuppressWarnings("unchecked")
	private static Map<String, Object> m(Object o) {
		return (Map<String, Object>) o;
	}

	@SuppressWarnings("unchecked")
	private static List<Map<String, Object>> list(Object o) {
		return (List<Map<String, Object>>) o;
	}

	@Test
	void opportunityAttacksDisengageAndKnockOut() throws Exception {
		try (Engine engine = TestCampaigns.engine(TestCampaigns.tempDb("reactions"))) {
			ScriptedRollService dice = (ScriptedRollService) engine.roller();
			String campaign = TestCampaigns.committedCampaign(engine);
			engine.sessions().bootstrap(op(), campaign, null);
			String pc = "character:1";
			String bandit = (String) engine.runtime()
					.materialize(op(), campaign, "Bandit", null, null, null, null, null, false).get("character");
			engine.inventory().grantLoot(op(), campaign, pc, List.of(map("item", "Club", "quantity", 1)), null,
					"GM_GRANT", "a stout branch");
			dice.queue(20, 50, 1, 50);
			String enc = (String) engine.encounters().start(op(), campaign,
					map("party", List.of(pc), "raiders", List.of(bandit)), null, null, null, null, null)
					.get("encounter");

			// The PC leaves the bandit's zone: the NPC bandit takes its opportunity attack automatically (natural 1 → miss).
			dice.queue(1);
			Map<String, Object> move = engine.encounters().perform(op(), campaign, enc, pc,
					map("kind", "MOVE", "zone", "far"), false);
			List<Map<String, Object>> oas = list(move.get("opportunity_attacks"));
			assertEquals(1, oas.size());
			assertEquals(Boolean.FALSE, oas.get(0).get("hit"));
			assertEquals("OPPORTUNITY_ATTACK", oas.get(0).get("reaction"));
			assertEquals(bandit, oas.get(0).get("reactor"));
			assertEquals("OPPORTUNITY_ATTACK", list(m(move.get("state")).get("recent_log")).get(0).get("kind"));
			// Its reaction is spent for the round: moving back provokes nothing.
			Map<String, Object> back = engine.encounters().perform(op(), campaign, enc, pc,
					map("kind", "MOVE", "zone", "near"), true);
			assertTrue(list(back.get("opportunity_attacks")).isEmpty(), "reaction already used");
			assertEquals(Boolean.TRUE, back.get("turn_advanced"));

			// The bandit's turn: it leaves the PC's zone; the PC is player-controlled, so the engine asks.
			Map<String, Object> banditMove = engine.encounters().perform(op(), campaign, enc, bandit,
					map("kind", "MOVE", "zone", "far"), true);
			List<Map<String, Object>> pending = list(banditMove.get("pending_choices"));
			assertEquals(1, pending.size());
			assertEquals("OPPORTUNITY_ATTACK", pending.get(0).get("kind"));
			assertEquals(pc, pending.get(0).get("chooser"));
			assertEquals(Boolean.FALSE, banditMove.get("turn_advanced"));
			String choice = (String) pending.get(0).get("transaction");
			Map<String, Object> state = engine.encounters().encounterState(campaign, enc, 5);
			assertEquals("WAITING_CHOICE", state.get("status"));
			assertEquals(List.of("resolve_pending_choice"), state.get("legal_actions"));
			// I-33 / I-31: nothing else can happen until it is resolved.
			assertEquals(ErrorCode.OPERATION_NOT_ALLOWED,
					assertThrows(RpgException.class,
							() -> engine.encounters().perform(op(), campaign, enc, pc, map("kind", "END_TURN"), true))
							.code());
			assertEquals(ErrorCode.OPERATION_NOT_ALLOWED, assertThrows(RpgException.class,
					() -> engine.encounters().end(op(), campaign, enc, "PARTY_VICTORY", "x")).code());
			assertEquals(ErrorCode.INVALID_ARGUMENT,
					assertThrows(RpgException.class,
							() -> engine.encounters().resolveChoice(op(), campaign, choice, map("option", "FLEE")))
							.code());
			// Take the opportunity attack unarmed: 19 + STR mod + prof hits AC 12; the turn then advances to the PC.
			dice.queue(19);
			Map<String, Object> resolved = engine.encounters().resolveChoice(op(), campaign, choice,
					map("option", "TAKE", "weapon", "unarmed"));
			Map<String, Object> oa = m(resolved.get("attack"));
			assertEquals(Boolean.TRUE, oa.get("hit"));
			assertEquals("OPPORTUNITY_ATTACK", oa.get("reaction"));
			assertEquals(Boolean.TRUE, resolved.get("turn_advanced"));
			assertEquals(pc, m(resolved.get("next_turn")).get("character"));
			assertEquals("RUNNING", m(resolved.get("state")).get("status"));
			assertEquals(ErrorCode.OPERATION_NOT_ALLOWED,
					assertThrows(RpgException.class,
							() -> engine.encounters().resolveChoice(op(), campaign, choice, map("option", "DECLINE")))
							.code(),
					"already committed");
			int banditHp = (Integer) m(list(m(resolved.get("state")).get("participants")).stream()
					.filter(p -> p.get("character").equals(bandit)).findFirst().orElseThrow().get("hp")).get("current");
			assertEquals(11 - (Integer) oa.get("damage"), banditHp,
					"unarmed strike damage applied (1 + STR mod, floored at 0)");

			// The PC joins the bandit in "far" and ends the turn; the bandit Disengages and walks away: no choice is offered.
			engine.encounters().perform(op(), campaign, enc, pc, map("kind", "MOVE", "zone", "far"), true);
			engine.encounters().perform(op(), campaign, enc, bandit, map("kind", "DISENGAGE"), false);
			Map<String, Object> away = engine.encounters().perform(op(), campaign, enc, bandit,
					map("kind", "MOVE", "zone", "near"), true);
			assertEquals("none (Disengage)", away.get("opportunity_attacks"));
			assertNull(away.get("pending_choices"));
			assertEquals(Boolean.TRUE, away.get("turn_advanced"));

			// Knock-out: bring the bandit to 1 HP, then a nonlethal unarmed hit leaves it unconscious and stable rather than dead.
			engine.runtime().applyRuntimeChange(op(), campaign, bandit,
					map("kind", "DAMAGE", "amount", banditHp - 1, "damage_type", "bludgeoning", "reason", "test"));
			engine.encounters().perform(op(), campaign, enc, pc, map("kind", "MOVE", "zone", "near"), false);
			dice.queue(19, 4);
			Map<String, Object> ko = engine.encounters().perform(op(), campaign, enc, pc,
					map("kind", "ATTACK", "target", bandit, "weapon", "Club", "nonlethal", true), false);
			assertEquals(Boolean.TRUE, ko.get("knocked_out"));
			assertEquals("DYING", ko.get("life_state"));
			Map<String, Object> sheet = engine.characters().characterSheet(campaign, bandit, "PLAY");
			assertEquals("DYING", sheet.get("life_state"));
			assertEquals(Boolean.TRUE, m(sheet.get("death_saves")).get("stable"));
			assertTrue(list(sheet.get("conditions")).stream().anyMatch(c -> "UNCONSCIOUS".equals(c.get("condition"))));
			assertEquals(ErrorCode.OPERATION_NOT_ALLOWED,
					assertThrows(RpgException.class,
							() -> engine.encounters().perform(op(), campaign, enc, pc,
									map("kind", "ATTACK", "target", bandit, "weapon", "unarmed"), false))
							.code(),
					"out of the fight");
			Map<String, Object> ended = engine.encounters().end(op(), campaign, enc, "PARTY_VICTORY", "subdued");
			assertEquals("PARTY_VICTORY", ended.get("outcome"));
			assertNotNull(ended.get("xp_awarded"));
		}
	}

	@Test
	void shieldSpellReaction() throws Exception {
		try (Engine engine = TestCampaigns.engine(TestCampaigns.tempDb("shield"))) {
			ScriptedRollService dice = (ScriptedRollService) engine.roller();
			String campaign = (String) engine.campaigns().create(op(), null, null).get("campaign");
			engine.campaigns().updateSetup(op(), campaign, null,
					map("content_profile", "PEGI_16", "experience", "SURPRISE_ME", "rules", Map.of(), "continuation",
							"CHECKPOINT", "party", "SURPRISE_ME", "adventure",
							map("premise", "x", "opening_location", "Tower", "immediate_goal", "y")));
			String pc = (String) engine.characters().createDraft(op(), campaign,
					map("name", "Wiz", "species", "Human", "class", "Wizard", "ability_scores",
							map("INT", 15, "DEX", 14, "CON", 13, "WIS", 12, "CHA", 10, "STR", 8), "skills",
							List.of("Arcana", "History"), "personality", "x", "background", "Criminal",
							"background_ability_scores", map("CON", 2, "INT", 1), "species_skill", "Insight",
							"origin_feat",
							map("feat", "Skilled", "proficiencies", List.of("Nature", "Survival", "Medicine")),
							"cantrips", List.of("Fire Bolt"), "spells", List.of("Shield", "Magic Missile")),
					true).get("character");
			engine.characters().commitDraft(op(), campaign, pc, null);
			engine.campaigns().commitSetup(op(), campaign, null);
			engine.sessions().bootstrap(op(), campaign, null);
			String bandit = (String) engine.runtime()
					.materialize(op(), campaign, "Bandit", null, null, null, null, null, false).get("character");
			dice.queue(1, 50, 20, 50);
			String enc = (String) engine.encounters().start(op(), campaign,
					map("party", List.of(pc), "raiders", List.of(bandit)), null, null, null, null, null)
					.get("encounter");
			// Bandit scimitar +3: 10 + 3 = 13 vs AC 12 hits, and Shield (+5) would turn it into a miss → the wizard is asked.
			dice.queue(10);
			Map<String, Object> swing = engine.encounters().perform(op(), campaign, enc, bandit,
					map("kind", "ATTACK", "target", pc, "attack", "Scimitar"), true);
			assertEquals(Boolean.FALSE, swing.get("resolved"));
			assertEquals(Boolean.TRUE, swing.get("hit"));
			Map<String, Object> pending = m(swing.get("pending_choice"));
			assertEquals("SHIELD_SPELL", pending.get("kind"));
			assertEquals(pc, pending.get("chooser"));
			assertEquals(Boolean.FALSE, swing.get("turn_advanced"));
			int hpBefore = (Integer) m(engine.characters().characterSheet(campaign, pc, "PLAY").get("hp"))
					.get("current");
			Map<String, Object> resolved = engine.encounters().resolveChoice(op(), campaign,
					(String) pending.get("transaction"), map("option", "CAST_SHIELD"));
			Map<String, Object> attack = m(resolved.get("attack"));
			assertEquals(Boolean.FALSE, attack.get("hit"));
			assertEquals(17, attack.get("target_armor_class"));
			assertEquals("Shield", m(attack.get("shield")).get("cast"));
			assertEquals(hpBefore, m(engine.characters().characterSheet(campaign, pc, "PLAY").get("hp")).get("current"),
					"no damage taken");
			Map<String, Object> sc = m(engine.characters().characterSheet(campaign, pc, "PLAY").get("spellcasting"));
			assertEquals(1, m(m(sc.get("slots")).get("1")).get("current"), "one slot spent on Shield");
			// The turn advanced to the wizard, whose turn start ends the Shield effect (until the start of your next turn).
			assertEquals(Boolean.TRUE, resolved.get("turn_advanced"));
			assertEquals(pc, m(resolved.get("next_turn")).get("character"));
			assertEquals(12,
					m(engine.characters().characterSheet(campaign, pc, "PLAY").get("armor_class")).get("value"));
			// Without a reaction available (already used this round) a second hit would not ask; a new round resets it.
			engine.encounters().perform(op(), campaign, enc, pc, map("kind", "END_TURN"), true);
			dice.queue(10);
			Map<String, Object> swing2 = engine.encounters().perform(op(), campaign, enc, bandit,
					map("kind", "ATTACK", "target", pc, "attack", "Scimitar"), false);
			assertEquals("SHIELD_SPELL", m(swing2.get("pending_choice")).get("kind"),
					"reaction reset at the start of the wizard's turn");
			Map<String, Object> declined = engine.encounters().resolveChoice(op(), campaign,
					(String) m(swing2.get("pending_choice")).get("transaction"), map("option", "DECLINE"));
			assertEquals(Boolean.TRUE, m(declined.get("attack")).get("hit"));
			assertTrue((Integer) m(engine.characters().characterSheet(campaign, pc, "PLAY").get("hp"))
					.get("current") < hpBefore);
			assertNull(declined.get("turn_advanced"), "the bandit's turn continues (end_turn was false)");
			assertEquals("RUNNING", m(declined.get("state")).get("status"));
		}
	}
}
