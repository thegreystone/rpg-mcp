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
import se.hirt.mcp.rpg.checkpoint.CheckpointService;
import se.hirt.mcp.rpg.dice.ScriptedRollService;
import se.hirt.mcp.rpg.persistence.Database;
import se.hirt.mcp.rpg.protocol.ErrorCode;
import se.hirt.mcp.rpg.protocol.Ref;
import se.hirt.mcp.rpg.protocol.RpgException;
import se.hirt.mcp.rpg.rules.Effects;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static se.hirt.mcp.rpg.TestCampaigns.*;

/** Conditions timed to a participant's next turn expire when that turn begins. */
class TurnDurationTest {

	@Test
	void startOfTurnConditionExpiresOnTheRecipientsNextTurn() throws Exception {
		try (Engine engine = engine(tempDb("start-of-turn-condition"))) {
			String campaign = committedCampaign(engine);
			engine.sessions().bootstrap(op(), campaign, null);
			String pc = "character:1";
			String ogre = (String) engine.runtime()
					.materialize(op(), campaign, "Ogre", null, null, null, null, null, false).get("character");
			((ScriptedRollService) engine.roller()).queue(20, 50, 1, 50);
			Map<String, Object> started = engine.encounters().start(op(), campaign,
					map("party", List.of(pc), "ogres", List.of(ogre)), null, null, null, null, null);
			String encounter = (String) started.get("encounter");
			assertEquals(pc, m(started.get("turn")).get("character"));
			Map<String, Object> applied = engine.runtime().applyRuntimeChange(op(), campaign, pc,
					map("kind", "ADD_CONDITION", "condition", "POISONED", "duration", "start of turn", "reason",
							"a lingering venom"));
			Map<String, Object> storedDuration = engine.db()
					.read(tx -> tx.queryOne(
							"SELECT duration_json FROM active_effect WHERE character_id = ? AND condition_ref = ?",
							Ref.id(pc, Ref.CHARACTER), "srd5e:condition/poisoned").orElseThrow().map("duration_json"));
			assertNotNull(applied.get("effect"));
			assertEquals("START_OF_TURN", storedDuration.get("until"));
			Map<String, Object> endedPc = engine.encounters().perform(op(), campaign, encounter, pc,
					map("kind", "END_TURN"), false);
			assertEquals(ogre, m(endedPc.get("next_turn")).get("character"));
			assertEquals(Set.of("POISONED"), conditions(engine, campaign, pc));
			Map<String, Object> endedOgre = engine.encounters().perform(op(), campaign, encounter, ogre,
					map("kind", "END_TURN"), false);
			assertEquals(pc, m(endedOgre.get("next_turn")).get("character"));
			Map<String, Object> sheet = engine.characters().characterSheet(campaign, pc, "PLAY");
			assertTrue(list(sheet.get("conditions")).isEmpty(),
					"POISONED must expire at the start of the recipient's next turn");
		}
	}

	@Test
	void aliasesAndMapsBindTheRecipientRatherThanTheCurrentActor() throws Exception {
		try (Engine engine = engine(tempDb("start-of-turn-aliases"))) {
			String campaign = committedCampaign(engine);
			engine.sessions().bootstrap(op(), campaign, null);
			String pc = "character:1";
			Fight fight = start(engine, campaign);
			long encounterId = Ref.id(fight.ref(), Ref.ENCOUNTER);
			long recipient = participant(engine, fight.ref(), fight.enemy());
			List<Object> inputs = List.of("START_OF_TURN", "until start of turn", "until-start-of-next-turn",
					map("until", "start_of_turn"), map("until", "START_OF_TURN"), map("until", "START_OF_TURN",
							"encounter", encounterId, "participant", recipient, "label", "until the ogre acts"));
			for (Object input : inputs) {
				condition(engine, campaign, fight.enemy(), "POISONED", input);
				Map<String, Object> duration = duration(engine, fight.enemy(), "POISONED");
				assertEquals(encounterId, ((Number) duration.get("encounter")).longValue(), input.toString());
				assertEquals(recipient, ((Number) duration.get("participant")).longValue(), input.toString());
				assertEquals(Set.of("POISONED"), conditions(engine, campaign, fight.enemy()));
				endTurn(engine, campaign, fight.ref(), pc);
				assertTrue(conditions(engine, campaign, fight.enemy()).isEmpty(), input.toString());
				endTurn(engine, campaign, fight.ref(), fight.enemy());
			}
		}
	}

	@Test
	void turnDurationsRejectMissingParticipantsAndStaleOrForeignBindings() throws Exception {
		try (Engine engine = engine(tempDb("start-of-turn-invalid"))) {
			String campaign = committedCampaign(engine);
			engine.sessions().bootstrap(op(), campaign, null);
			String pc = "character:1";
			for (Object input : List.of("start of turn", map("until", "START_OF_TURN"))) {
				assertInvalidDuration(engine, campaign, pc, input);
			}
			Fight previous = start(engine, campaign);
			long previousParticipant = participant(engine, previous.ref(), pc);
			engine.encounters().end(op(), campaign, previous.ref(), "OTHER", "the ogre leaves");
			Fight current = start(engine, campaign);
			String observer = (String) engine.runtime()
					.materialize(op(), campaign, "Commoner", "Observer", null, null, null, null, false)
					.get("character");
			assertInvalidDuration(engine, campaign, observer, "start of turn");
			for (Object input : List.of(
					map("until", "START_OF_TURN", "encounter", Ref.id(previous.ref(), Ref.ENCOUNTER)),
					map("until", "START_OF_TURN", "participant", previousParticipant),
					map("until", "START_OF_TURN", "participant", participant(engine, current.ref(), current.enemy())),
					map("until", "START_OF_TURN", "participant", Long.MAX_VALUE))) {
				assertInvalidDuration(engine, campaign, pc, input);
			}
			assertTrue(conditions(engine, campaign, pc).isEmpty());
			assertTrue(conditions(engine, campaign, observer).isEmpty());
		}
	}

	@Test
	void replayDoesNotReapplyAnExpiredConditionAndCheckpointRestoresItsBinding() throws Exception {
		try (Engine engine = engine(tempDb("start-of-turn-checkpoint"))) {
			String campaign = committedCampaign(engine);
			engine.sessions().bootstrap(op(), campaign, null);
			String pc = "character:1";
			Fight fight = start(engine, campaign);
			String operation = op();
			Map<String, Object> change = map("kind", "ADD_CONDITION", "condition", "POISONED", "duration",
					"start of turn", "reason", "a lingering venom");
			engine.runtime().applyRuntimeChange(operation, campaign, pc, change);
			Map<String, Object> originalDuration = duration(engine, pc, "POISONED");
			// Manual checkpoints are blocked in combat; exercise the engine's checkpoint path.
			long campaignId = Ref.id(campaign, Ref.CAMPAIGN);
			String checkpoint = (String) engine.db()
					.mutate(Database.Mutation.of("turn_checkpoint_fixture", campaignId, op(), "GM", null),
							tx -> Map.of("checkpoint",
									CheckpointService.createInTx(tx, campaignId, "before the next turn")))
					.get("checkpoint");
			endTurn(engine, campaign, fight.ref(), pc);
			endTurn(engine, campaign, fight.ref(), fight.enemy());
			assertTrue(conditions(engine, campaign, pc).isEmpty());
			assertEquals(Boolean.TRUE,
					engine.runtime().applyRuntimeChange(operation, campaign, pc, change).get("replayed"));
			assertTrue(conditions(engine, campaign, pc).isEmpty(), "replaying the grant cannot extend its duration");
			engine.checkpoints().restore(op(), campaign, checkpoint, "undo the elapsed turns");
			assertEquals(Set.of("POISONED"), conditions(engine, campaign, pc));
			assertEquals(originalDuration, duration(engine, pc, "POISONED"));
			endTurn(engine, campaign, fight.ref(), pc);
			assertEquals(Set.of("POISONED"), conditions(engine, campaign, pc));
			endTurn(engine, campaign, fight.ref(), fight.enemy());
			assertTrue(conditions(engine, campaign, pc).isEmpty());
		}
	}

	@Test
	void legacyDurationsExpireAtTheRecipientsStartAndRespectTheirOriginalEncounter() throws Exception {
		try (Engine engine = engine(tempDb("start-of-turn-legacy"))) {
			String campaign = committedCampaign(engine);
			engine.sessions().bootstrap(op(), campaign, null);
			String pc = "character:1";
			Fight previous = start(engine, campaign);
			long previousParticipant = participant(engine, previous.ref(), pc);
			engine.encounters().end(op(), campaign, previous.ref(), "OTHER", "the first ogre leaves");
			Fight current = start(engine, campaign);
			legacyCondition(engine, campaign, pc, "BLINDED",
					map("until", "START_OF_TURN", "encounter", Ref.id(previous.ref(), Ref.ENCOUNTER)));
			legacyCondition(engine, campaign, pc, "DEAFENED",
					map("until", "START_OF_TURN", "participant", previousParticipant));
			legacyCondition(engine, campaign, pc, "POISONED", map("until", "START_OF_TURN"));
			legacyCondition(engine, campaign, pc, "PRONE",
					map("until", "START_OF_TURN", "participant", participant(engine, current.ref(), pc)));
			endTurn(engine, campaign, current.ref(), pc);
			assertEquals(Set.of("BLINDED", "DEAFENED", "POISONED", "PRONE"), conditions(engine, campaign, pc));
			endTurn(engine, campaign, current.ref(), current.enemy());
			assertEquals(Set.of("BLINDED", "DEAFENED"), conditions(engine, campaign, pc));
			engine.encounters().end(op(), campaign, current.ref(), "OTHER", "the second ogre leaves");
			assertEquals(Set.of("BLINDED", "DEAFENED"), conditions(engine, campaign, pc),
					"an old encounter or participant binding must survive a different encounter ending");
		}
	}

	@Test
	void endingAnEncounterClearsPendingTurnConditionsOnRemovedRecipients() throws Exception {
		try (Engine engine = engine(tempDb("start-of-turn-removed"))) {
			String campaign = committedCampaign(engine);
			engine.sessions().bootstrap(op(), campaign, null);
			String pc = "character:1";
			Fight previous = start(engine, campaign);
			engine.encounters().end(op(), campaign, previous.ref(), "OTHER", "the first ogre leaves");
			Fight current = start(engine, campaign);
			long currentParticipant = participant(engine, current.ref(), pc);
			condition(engine, campaign, pc, "POISONED", "start of turn");
			condition(engine, campaign, pc, "CHARMED", "long rest");
			condition(engine, campaign, pc, "DEAFENED", "restored");
			legacyCondition(engine, campaign, pc, "PRONE", map("until", "START_OF_TURN"));
			legacyCondition(engine, campaign, pc, "FRIGHTENED",
					map("until", "START_OF_TURN", "participant", currentParticipant));
			legacyCondition(engine, campaign, pc, "BLINDED", map("until", "START_OF_TURN", "participant",
					currentParticipant, "encounter", Ref.id(previous.ref(), Ref.ENCOUNTER)));
			engine.rest().override(op(), campaign, "REMOVE_ENCOUNTER_PARTICIPANT", pc, null, "steps aside", null);
			assertEquals(Set.of("POISONED", "CHARMED", "DEAFENED", "PRONE", "FRIGHTENED", "BLINDED"),
					conditions(engine, campaign, pc));
			engine.encounters().end(op(), campaign, current.ref(), "OTHER", "the second ogre leaves");
			assertEquals(Set.of("CHARMED", "DEAFENED", "BLINDED"), conditions(engine, campaign, pc),
					"turn cleanup keeps other milestones and gives an explicit encounter precedence");
		}
	}

	private record Fight(String ref, String enemy) {
	}

	private static Fight start(Engine engine, String campaign) {
		String ogre = (String) engine.runtime().materialize(op(), campaign, "Ogre", null, null, null, null, null, false)
				.get("character");
		((ScriptedRollService) engine.roller()).queue(20, 50, 1, 50);
		Map<String, Object> started = engine.encounters().start(op(), campaign,
				map("party", List.of("character:1"), "ogres", List.of(ogre)), null, null, null, null, null);
		assertEquals("character:1", m(started.get("turn")).get("character"));
		return new Fight((String) started.get("encounter"), ogre);
	}

	private static long participant(Engine engine, String encounter, String character) {
		return engine.db().read(
				tx -> tx.queryOne("SELECT id FROM encounter_participant WHERE encounter_id = ? AND character_id = ?",
						Ref.id(encounter, Ref.ENCOUNTER), Ref.id(character, Ref.CHARACTER)).orElseThrow().id());
	}

	private static Map<String, Object> duration(Engine engine, String character, String condition) {
		return engine.db().read(tx -> tx
				.queryOne("SELECT duration_json FROM active_effect WHERE character_id = ? AND condition_ref = ?",
						Ref.id(character, Ref.CHARACTER),
						"srd5e:condition/" + condition.toLowerCase(java.util.Locale.ROOT))
				.orElseThrow().map("duration_json"));
	}

	private static void endTurn(Engine engine, String campaign, String encounter, String character) {
		engine.encounters().perform(op(), campaign, encounter, character, map("kind", "END_TURN"), false);
	}

	private static void condition(Engine engine, String campaign, String character, String condition, Object duration) {
		engine.runtime().applyRuntimeChange(op(), campaign, character,
				map("kind", "ADD_CONDITION", "condition", condition, "duration", duration, "reason", "turn test"));
	}

	private static void assertInvalidDuration(Engine engine, String campaign, String character, Object duration) {
		assertEquals(ErrorCode.INVALID_ARGUMENT,
				assertThrows(RpgException.class, () -> condition(engine, campaign, character, "POISONED", duration))
						.code(),
				duration.toString());
	}

	/** Restores the duration shape written by older versions without running the new parser. */
	private static void legacyCondition(
		Engine engine, String campaign, String character, String condition, Map<String, Object> duration) {
		long campaignId = Ref.id(campaign, Ref.CAMPAIGN);
		engine.db().mutate(Database.Mutation.of("turn_duration_fixture", campaignId, op(), "GM", null), tx -> {
			Effects.add(tx, campaignId, Ref.id(character, Ref.CHARACTER), null, null, "legacy turn test",
					"srd5e:condition/" + condition.toLowerCase(java.util.Locale.ROOT), null, duration, null, null,
					"GM");
			return Map.of();
		});
	}

	private static Set<String> conditions(Engine engine, String campaign, String character) {
		return list(engine.characters().characterSheet(campaign, character, "PLAY").get("conditions")).stream()
				.map(c -> c.get("condition").toString()).collect(Collectors.toSet());
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
