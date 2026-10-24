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

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import se.hirt.mcp.rpg.content.SeedImporter;
import se.hirt.mcp.rpg.dice.ScriptedRollService;
import se.hirt.mcp.rpg.persistence.Database;
import se.hirt.mcp.rpg.protocol.ErrorCode;
import se.hirt.mcp.rpg.protocol.Json;
import se.hirt.mcp.rpg.protocol.Ref;
import se.hirt.mcp.rpg.protocol.RpgException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static se.hirt.mcp.rpg.TestCampaigns.committedCampaign;
import static se.hirt.mcp.rpg.TestCampaigns.engine;
import static se.hirt.mcp.rpg.TestCampaigns.map;
import static se.hirt.mcp.rpg.TestCampaigns.op;
import static se.hirt.mcp.rpg.TestCampaigns.tempDb;

class RangedOpportunityAttackTest {

	@Test
	void movingAwayFromARangedOnlyCreatureDoesNotAbortWithNotMelee() throws Exception {
		try (Engine engine = engineWithRangedCreature(tempDb("ranged-opportunity-move"))) {
			String campaign = campaign(engine);
			String enemy = creature(engine, campaign, "Test Arc Drone");
			String checkpoint = (String) engine.checkpoints().create(op(), campaign, "before the encounter")
					.get("checkpoint");
			Map<String, List<Map<String, Object>>> before = snapshot(engine, campaign);
			Fight fight = new Fight(campaign, "character:1", enemy, start(engine, campaign, List.of(enemy), "AUTO"));
			int beforeHp = hp(engine, fight.campaign(), fight.pc());
			String operation = op();
			Map<String, Object> action = map("kind", "MOVE", "zone", "far");
			ScriptedRollService dice = (ScriptedRollService) engine.roller();
			dice.queue(19);

			Map<String, Object> move = engine.encounters().perform(operation, fight.campaign(), fight.encounter(),
					fight.pc(), action, false);

			Map<String, Object> reaction = list(move.get("opportunity_attacks")).getFirst();
			assertEquals("far", move.get("zone"));
			assertEquals("Unarmed Strike", reaction.get("attack"));
			assertEquals("OPPORTUNITY_ATTACK", reaction.get("reaction"));
			assertEquals(fight.enemy(), reaction.get("reactor"));
			assertEquals(1, reaction.get("damage"));
			assertEquals(beforeHp - 1, hp(engine, fight.campaign(), fight.pc()));
			assertEquals(1, ((Number) participant(engine, fight, fight.enemy()).get("reaction_used")).intValue());
			assertEquals("RUNNING", m(move.get("state")).get("status"));
			assertNull(move.get("pending_choices"));
			assertFalse((Boolean) move.get("turn_advanced"));
			assertTrue(dice.exhausted(), "the fallback rolls one unarmed attack, with no ranged damage dice");

			Map<String, List<Map<String, Object>>> after = snapshot(engine, fight.campaign());
			assertEquals(true,
					engine.encounters()
							.perform(operation, fight.campaign(), fight.encounter(), fight.pc(), action, false)
							.get("replayed"));
			assertEquals(after, snapshot(engine, fight.campaign()));
			engine.checkpoints().restore(op(), fight.campaign(), checkpoint, "undo the movement and reaction");
			assertEquals(before, snapshot(engine, fight.campaign()));
			assertEquals(beforeHp, hp(engine, fight.campaign(), fight.pc()));
			String retryEncounter = start(engine, campaign, List.of(enemy), "AUTO");
			dice.queue(1);
			Map<String, Object> fresh = engine.encounters().perform(op(), fight.campaign(), retryEncounter, fight.pc(),
					action, false);
			assertEquals(false, list(fresh.get("opportunity_attacks")).getFirst().get("hit"));
			assertTrue(dice.exhausted());
		}
	}

	@Test
	void askedRangedOnlyReactionsUseUnarmedForDefaultAndExplicitChoices() throws Exception {
		for (Map<String, Object> choice : List.of(map("option", "TAKE"), map("option", "TAKE", "weapon", "unarmed"))) {
			try (Engine engine = engineWithRangedCreature(tempDb("ranged-opportunity-ask"))) {
				Fight fight = fight(engine, "Test Arc Drone", "ASK");
				Map<String, Object> move = engine.encounters().perform(op(), fight.campaign(), fight.encounter(),
						fight.pc(), map("kind", "MOVE", "zone", "far"), false);
				Map<String, Object> pending = list(move.get("pending_choices")).getFirst();
				assertEquals("OPPORTUNITY_ATTACK", pending.get("kind"));
				assertEquals(fight.enemy(), pending.get("chooser"));
				assertEquals("WAITING_CHOICE", m(move.get("state")).get("status"));
				assertEquals(0, ((Number) participant(engine, fight, fight.enemy()).get("reaction_used")).intValue());
				((ScriptedRollService) engine.roller()).queue(19);

				Map<String, Object> resolved = engine.encounters().resolveChoice(op(), fight.campaign(),
						(String) pending.get("transaction"), choice);

				Map<String, Object> attack = m(resolved.get("attack"));
				assertEquals("Unarmed Strike", attack.get("attack"));
				assertEquals(1, attack.get("damage"));
				assertEquals("RUNNING", m(resolved.get("state")).get("status"));
				assertEquals(1, ((Number) participant(engine, fight, fight.enemy()).get("reaction_used")).intValue());
				assertEquals("far", participant(engine, fight, fight.pc()).get("position_zone"));
				assertTrue(((ScriptedRollService) engine.roller()).exhausted());
			}
		}
	}

	@Test
	void rangedFirstStatBlockUsesItsLaterMeleeActionAlongsideAnUnarmedHostile() throws Exception {
		try (Engine engine = engineWithRangedCreature(tempDb("ranged-opportunity-mixed-actions"))) {
			String campaign = campaign(engine);
			String mixed = creature(engine, campaign, "Test Mixed Drone");
			String ranged = creature(engine, campaign, "Test Arc Drone");
			String encounter = start(engine, campaign, List.of(mixed, ranged), "AUTO");
			ScriptedRollService dice = (ScriptedRollService) engine.roller();
			dice.queue(19, 3, 19);

			Map<String, Object> move = engine.encounters().perform(op(), campaign, encounter, "character:1",
					map("kind", "MOVE", "zone", "far"), false);

			List<Map<String, Object>> reactions = list(move.get("opportunity_attacks"));
			assertEquals(2, reactions.size());
			Map<String, Object> claw = reactions.stream().filter(r -> mixed.equals(r.get("reactor"))).findFirst()
					.orElseThrow();
			assertEquals("Claw", claw.get("attack"));
			assertEquals(4, claw.get("damage"));
			Map<String, Object> unarmed = reactions.stream().filter(r -> ranged.equals(r.get("reactor"))).findFirst()
					.orElseThrow();
			assertEquals("Unarmed Strike", unarmed.get("attack"));
			assertEquals(1, unarmed.get("damage"));
			assertEquals("RUNNING", m(move.get("state")).get("status"));
			assertTrue(dice.exhausted());
		}
	}

	@Test
	void rangedOnlyStatBlockPrefersItsEquippedMeleeWeaponBeforeUnarmed() throws Exception {
		try (Engine engine = engineWithRangedCreature(tempDb("ranged-opportunity-carried-melee"))) {
			String campaign = campaign(engine);
			String enemy = creature(engine, campaign, "Test Arc Drone");
			List<Map<String, Object>> granted = list(engine.inventory().grantLoot(op(), campaign, enemy,
					List.of(map("item", "Club"), map("item", "Dagger")), null, "QUEST", "its carried weapons")
					.get("granted"));
			String dagger = (String) granted.get(1).get("entry");
			engine.inventory().equip(op(), campaign, enemy, dagger, true);
			String encounter = start(engine, campaign, List.of(enemy), "AUTO");
			((ScriptedRollService) engine.roller()).queue(19, 2);

			Map<String, Object> move = engine.encounters().perform(op(), campaign, encounter, "character:1",
					map("kind", "MOVE", "zone", "far"), false);

			Map<String, Object> reaction = list(move.get("opportunity_attacks")).getFirst();
			assertEquals("Dagger", reaction.get("attack"));
			assertEquals(2, reaction.get("damage"));
			assertTrue(((ScriptedRollService) engine.roller()).exhausted());
		}
	}

	@Test
	void explicitRangedReactionIsRejectedWithoutSpendingTheReactionOrClosingTheChoice() throws Exception {
		try (Engine engine = engineWithRangedCreature(tempDb("ranged-opportunity-rejected-choice"))) {
			Fight fight = fight(engine, "Test Arc Drone", "ASK");
			Map<String, Object> move = engine.encounters().perform(op(), fight.campaign(), fight.encounter(),
					fight.pc(), map("kind", "MOVE", "zone", "far"), false);
			String transaction = (String) list(move.get("pending_choices")).getFirst().get("transaction");
			Map<String, List<Map<String, Object>>> before = snapshot(engine, fight.campaign());
			RpgException invalid = assertThrows(RpgException.class, () -> engine.encounters().resolveChoice(op(),
					fight.campaign(), transaction, map("option", "TAKE", "weapon", "Arc Bolt")));
			assertEquals(ErrorCode.VALIDATION_FAILED, invalid.code());
			assertEquals("NOT_MELEE", list(invalid.details().get("violations")).getFirst().get("rule"));
			assertEquals(before, snapshot(engine, fight.campaign()));
			assertEquals(0, ((Number) participant(engine, fight, fight.enemy()).get("reaction_used")).intValue());
			assertEquals("OPEN", engine.db()
					.read(tx -> tx.get("pending_transaction", Ref.id(transaction, Ref.TRANSACTION)).str("status")));
			((ScriptedRollService) engine.roller()).queue(19);
			Map<String, Object> resolved = engine.encounters().resolveChoice(op(), fight.campaign(), transaction,
					map("option", "TAKE", "weapon", "unarmed"));
			assertEquals("Unarmed Strike", m(resolved.get("attack")).get("attack"));
			assertEquals("RUNNING", m(resolved.get("state")).get("status"));
			assertTrue(((ScriptedRollService) engine.roller()).exhausted());
		}
	}

	@Test
	void ordinaryAttacksStillChooseRangedDefaultsAndHonorNamedMeleeAndUnarmedAttacks() throws Exception {
		try (Engine engine = engineWithRangedCreature(tempDb("ranged-opportunity-ordinary-attacks"))) {
			Fight fight = fight(engine, "Test Mixed Drone", "AUTO");
			engine.encounters().perform(op(), fight.campaign(), fight.encounter(), fight.pc(), map("kind", "END_TURN"),
					true);
			List<Map<String, Object>> actions = List.of(map("kind", "ATTACK", "target", fight.pc()),
					map("kind", "ATTACK", "target", fight.pc(), "weapon", "Arc Bolt"),
					map("kind", "ATTACK", "target", fight.pc(), "attack", "Claw"),
					map("kind", "ATTACK", "target", fight.pc(), "weapon", "Claw"),
					map("kind", "ATTACK", "target", fight.pc(), "weapon", "unarmed"));
			List<String> expected = List.of("Arc Bolt", "Arc Bolt", "Claw", "Claw", "Unarmed Strike");
			ScriptedRollService dice = (ScriptedRollService) engine.roller();
			for (int i = 0; i < actions.size(); i++) {
				dice.queue(1);
				Map<String, Object> attack = engine.encounters().perform(op(), fight.campaign(), fight.encounter(),
						fight.enemy(), actions.get(i), false);
				assertEquals(expected.get(i), attack.get("attack"));
				assertEquals(false, attack.get("hit"));
				assertEquals(true, attack.get("resolved"));
			}
			assertTrue(dice.exhausted());
		}
	}

	private static Engine engineWithRangedCreature(Path database) {
		try (Database db = new Database(database)) {
			SeedImporter.importIfMissing(db, SeedImporter.DEFAULT_RULESET);
			db.mutate(Database.Mutation.of("ranged_opportunity_fixture", null, op(), "GM", null), tx -> {
				long ruleset = tx
						.queryOne("SELECT id FROM installed_ruleset WHERE namespace = ? ORDER BY id DESC LIMIT 1",
								"srd5e")
						.orElseThrow().id();
				Map<String, Object> payload = map("size", "Small", "creature_type", "Construct", "ac", 12, "hp",
						map("average", 30, "dice", "6d6+9"), "speed", map("walk", 30), "abilities",
						map("STR", 10, "DEX", 10, "CON", 10, "INT", 10, "WIS", 10, "CHA", 10), "actions",
						List.of(map("name", "Arc Bolt", "kind", "RANGED_ATTACK", "attack_bonus", 4, "range", "60/120",
								"damage", List.of(map("dice", "1d4", "type", "lightning")))));
				tx.rawInsert("installed_content",
						map("ruleset_id", ruleset, "content_id", "srd5e:creature/test-arc-drone", "kind", "CREATURE",
								"name", "Test Arc Drone", "payload_json", Json.write(payload)));
				Map<String, Object> mixed = new LinkedHashMap<>(payload);
				List<Map<String, Object>> actions = new ArrayList<>(list(payload.get("actions")));
				actions.add(map("name", "Claw", "kind", "MELEE_ATTACK", "attack_bonus", 4, "reach", 5, "damage",
						List.of(map("dice", "1d4+1", "type", "slashing"))));
				mixed.put("actions", actions);
				tx.rawInsert("installed_content",
						map("ruleset_id", ruleset, "content_id", "srd5e:creature/test-mixed-drone", "kind", "CREATURE",
								"name", "Test Mixed Drone", "payload_json", Json.write(mixed)));
				return Map.of();
			});
		}
		return engine(database);
	}

	private record Fight(String campaign, String pc, String enemy, String encounter) {
	}

	private static Fight fight(Engine engine, String creature, String policy) {
		String campaign = campaign(engine);
		String enemy = creature(engine, campaign, creature);
		return new Fight(campaign, "character:1", enemy, start(engine, campaign, List.of(enemy), policy));
	}

	private static String campaign(Engine engine) {
		String campaign = committedCampaign(engine);
		engine.sessions().bootstrap(op(), campaign, null);
		return campaign;
	}

	private static String creature(Engine engine, String campaign, String name) {
		return (String) engine.runtime().materialize(op(), campaign, name, name, null, null, null, null, false)
				.get("character");
	}

	private static String start(Engine engine, String campaign, List<String> enemies, String policy) {
		ScriptedRollService dice = (ScriptedRollService) engine.roller();
		dice.queue(20, 100);
		for (int i = 0; i < enemies.size(); i++) {
			dice.queue(1, 1);
		}
		Map<String, Object> started = engine.encounters().start(op(), campaign,
				map("party", List.of("character:1"), "foes", enemies), null, null, null, null, null,
				map("npc_reactions", policy));
		assertEquals("character:1", m(started.get("turn")).get("character"));
		assertTrue(dice.exhausted());
		return (String) started.get("encounter");
	}

	private static int hp(Engine engine, String campaign, String character) {
		return ((Number) m(engine.characters().characterSheet(campaign, character, "PLAY").get("hp")).get("current"))
				.intValue();
	}

	private static Map<String, Object> participant(Engine engine, Fight fight, String character) {
		return engine.db()
				.read(tx -> tx
						.queryOne("SELECT * FROM encounter_participant WHERE encounter_id = ? AND character_id = ?",
								Ref.id(fight.encounter(), Ref.ENCOUNTER), Ref.id(character, Ref.CHARACTER))
						.orElseThrow().asMap());
	}

	private static Map<String, List<Map<String, Object>>> snapshot(Engine engine, String campaign) {
		return engine.db().snapshot(Ref.id(campaign, Ref.CAMPAIGN));
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
