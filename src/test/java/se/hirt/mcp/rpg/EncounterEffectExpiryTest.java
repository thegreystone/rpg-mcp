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
import se.hirt.mcp.rpg.persistence.Database;
import se.hirt.mcp.rpg.protocol.Ref;
import se.hirt.mcp.rpg.rules.Effects;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static se.hirt.mcp.rpg.TestCampaigns.map;
import static se.hirt.mcp.rpg.TestCampaigns.op;

/**
 * Effect duration belongs to the source encounter, independently of recipient membership. When the
 * encounter ends, the rounds an effect has left become minutes on the game clock (6 seconds per
 * round, SRD 5.2.1 "Combat") instead of ending with the fight.
 */
class EncounterEffectExpiryTest {

	@Test
	void removedParticipantsKeepRoundConditionsAsMinutesWhenTheirEncounterEnds() throws Exception {
		try (Engine engine = TestCampaigns.engine(TestCampaigns.tempDb("removed-effect-expiry"))) {
			String campaign = TestCampaigns.committedCampaign(engine);
			engine.sessions().bootstrap(op(), campaign, null);
			String pc = "character:1";
			String encounter = start(engine, campaign).ref();
			condition(engine, campaign, pc, "BLINDED", map("rounds", 10));
			condition(engine, campaign, pc, "CHARMED", map("minutes", 30));
			condition(engine, campaign, pc, "PRONE", null);
			// Before raw expires_round inputs were scoped, these entries had no encounter field.
			effect(engine, campaign, pc, "DEAFENED", map("expires_round", 20), null);
			engine.rest().override(op(), campaign, "REMOVE_ENCOUNTER_PARTICIPANT", pc, null, "steps aside", null);
			assertNull(engine.db().read(tx -> tx.get("character", Ref.id(pc, Ref.CHARACTER)).lng("encounter_id")));

			String operation = op();
			engine.encounters().end(operation, campaign, encounter, "OTHER", "the danger passes");
			assertEquals(Set.of("BLINDED", "DEAFENED", "CHARMED", "PRONE"), conditions(engine, campaign, pc),
					"both scoped and legacy round effects outlive the fight as game time");
			assertEquals("MINUTES", duration(engine, pc, "BLINDED").get("kind"));
			assertEquals(10, duration(engine, pc, "BLINDED").get("converted_from_rounds"), "ten rounds = one minute");
			assertEquals(19, duration(engine, pc, "DEAFENED").get("converted_from_rounds"), "19 rounds = two minutes");
			assertEquals(Boolean.TRUE, engine.encounters()
					.end(operation, campaign, encounter, "OTHER", "the danger passes").get("replayed"));
			assertEquals(Set.of("BLINDED", "DEAFENED", "CHARMED", "PRONE"), conditions(engine, campaign, pc));

			engine.sessions().advanceTime(op(), campaign, 1, "a minute");
			assertEquals(Set.of("DEAFENED", "CHARMED", "PRONE"), conditions(engine, campaign, pc));
			engine.sessions().advanceTime(op(), campaign, 1, "another minute");
			assertEquals(Set.of("CHARMED", "PRONE"), conditions(engine, campaign, pc),
					"the minute and indefinite effects remain");
		}
	}

	private static Map<String, Object> duration(Engine engine, String character, String condition) {
		return engine.db().read(tx -> tx
				.queryOne("SELECT duration_json FROM active_effect WHERE character_id = ? AND condition_ref = ?",
						Ref.id(character, Ref.CHARACTER), "srd5e:condition/" + condition.toLowerCase())
				.orElseThrow().map("duration_json"));
	}

	@Test
	void roundBoundariesAndEncounterEndExpireEffectsOnRemovedAndNonparticipantRecipients() throws Exception {
		try (Engine engine = TestCampaigns.engine(TestCampaigns.tempDb("detached-round-expiry"))) {
			String campaign = TestCampaigns.committedCampaign(engine);
			engine.sessions().bootstrap(op(), campaign, null);
			String pc = "character:1";
			Fight fight = start(engine, campaign);
			String observer = (String) engine.runtime()
					.materialize(op(), campaign, "Commoner", "Mara", null, null, null, null, false).get("character");
			long encounterId = Ref.id(fight.ref(), Ref.ENCOUNTER);
			long concentrator = Ref.id(fight.enemy(), Ref.CHARACTER);
			condition(engine, campaign, pc, "BLINDED", map("rounds", 1));
			effect(engine, campaign, observer, "DEAFENED", map("encounter", encounterId, "expires_round", 2),
					concentrator);
			effect(engine, campaign, observer, "POISONED", map("encounter", encounterId, "expires_round", 3),
					concentrator);
			engine.rest().override(op(), campaign, "REMOVE_ENCOUNTER_PARTICIPANT", pc, null, "steps aside", null);
			assertEquals(Set.of("BLINDED"), conditions(engine, campaign, pc));
			assertEquals(Set.of("DEAFENED", "POISONED"), conditions(engine, campaign, observer));

			// The wolf is the sole active participant; ending its turn begins round 2.
			engine.encounters().perform(op(), campaign, fight.ref(), fight.enemy(), map("kind", "END_TURN"), false);
			long round = engine.db().read(tx -> tx.get("encounter", encounterId).lng("round"));
			assertEquals(2L, round);
			assertTrue(conditions(engine, campaign, pc).isEmpty());
			assertEquals(Set.of("POISONED"), conditions(engine, campaign, observer),
					"round 2 expires exactly the due effect, keeping the effect due in round 3");
			boolean concentratingBeforeEnd = engine.db().read(tx -> Effects.isConcentrating(tx, concentrator));
			assertTrue(concentratingBeforeEnd);

			engine.encounters().end(op(), campaign, fight.ref(), "OTHER", "the wolf leaves");
			assertEquals(Set.of("POISONED"), conditions(engine, campaign, observer),
					"a future round effect on a recipient who never joined continues as game time");
			assertEquals(1, duration(engine, observer, "POISONED").get("converted_from_rounds"),
					"one round left after round 2");
			boolean concentratingAfterConversion = engine.db().read(tx -> Effects.isConcentrating(tx, concentrator));
			assertTrue(concentratingAfterConversion, "the converted effect is still concentrated on");
			engine.sessions().advanceTime(op(), campaign, 1, "a minute");
			assertTrue(conditions(engine, campaign, observer).isEmpty(), "the minute the round became has passed");
			boolean concentratingAfterEnd = engine.db().read(tx -> Effects.isConcentrating(tx, concentrator));
			assertFalse(concentratingAfterEnd,
					"expired off-list effects must not leave the caster concentrating on them");
		}
	}

	@Test
	void storedEncounterScopeOverridesCurrentMembershipAndBareRoundInputsAcquireScope() throws Exception {
		try (Engine engine = TestCampaigns.engine(TestCampaigns.tempDb("effect-encounter-scope"))) {
			String campaign = TestCampaigns.committedCampaign(engine);
			engine.sessions().bootstrap(op(), campaign, null);
			String pc = "character:1";
			String earlier = start(engine, campaign).ref();
			engine.encounters().end(op(), campaign, earlier, "OTHER", "the first wolf leaves");
			String current = start(engine, campaign).ref();
			condition(engine, campaign, pc, "BLINDED",
					map("encounter", Ref.id(earlier, Ref.ENCOUNTER), "expires_round", 2));
			condition(engine, campaign, pc, "DEAFENED", map("expires_round", 2));
			Map<String, Object> duration = engine.db()
					.read(tx -> tx.queryOne(
							"SELECT duration_json FROM active_effect WHERE character_id = ? AND condition_ref = ?",
							Ref.id(pc, Ref.CHARACTER), "srd5e:condition/deafened").orElseThrow().map("duration_json"));
			assertEquals(Ref.id(current, Ref.ENCOUNTER), ((Number) duration.get("encounter")).longValue(),
					"a newly supplied expires_round value is scoped before the character can leave");
			engine.encounters().end(op(), campaign, current, "OTHER", "the second wolf leaves");
			assertEquals(Set.of("BLINDED", "DEAFENED"), conditions(engine, campaign, pc));
			assertEquals("MINUTES", duration(engine, pc, "DEAFENED").get("kind"),
					"the ending encounter converts the duration it owns");
			assertNotNull(duration(engine, pc, "BLINDED").get("expires_round"),
					"current participant membership must not touch a duration owned by another encounter");
			assertNull(duration(engine, pc, "BLINDED").get("expires_seq"));
			engine.sessions().advanceTime(op(), campaign, 1, "a minute");
			assertEquals(Set.of("BLINDED"), conditions(engine, campaign, pc));
		}
	}

	private record Fight(String ref, String enemy) {
	}

	private static Fight start(Engine engine, String campaign) {
		String wolf = (String) engine.runtime().materialize(op(), campaign, "Wolf", null, null, null, null, null, false)
				.get("character");
		String encounter = (String) engine.encounters().start(op(), campaign,
				map("party", List.of("character:1"), "beasts", List.of(wolf)), null, null, null, null, null)
				.get("encounter");
		return new Fight(encounter, wolf);
	}

	private static void condition(Engine engine, String campaign, String character, String condition, Object duration) {
		engine.runtime().applyRuntimeChange(op(), campaign, character,
				map("kind", "ADD_CONDITION", "condition", condition, "duration", duration, "reason", "expiry test"));
	}

	/** Models effects cast onto someone outside the turn order, or a legacy untagged duration. */
	private static void effect(
		Engine engine, String campaign, String character, String condition, Map<String, Object> duration,
		Long concentrator) {
		long campaignId = Ref.id(campaign, Ref.CAMPAIGN);
		engine.db().mutate(Database.Mutation.of("effect_expiry_fixture", campaignId, op(), "GM", null), tx -> {
			Effects.add(tx, campaignId, Ref.id(character, Ref.CHARACTER), concentrator, null, "expiry test",
					"srd5e:condition/" + condition.toLowerCase(java.util.Locale.ROOT), null, duration, concentrator,
					null, "GM");
			return Map.of();
		});
	}

	@SuppressWarnings("unchecked")
	private static Set<String> conditions(Engine engine, String campaign, String character) {
		List<Map<String, Object>> conditions = (List<Map<String, Object>>) engine.characters()
				.characterSheet(campaign, character, "PLAY").get("conditions");
		return conditions.stream().map(c -> c.get("condition").toString()).collect(Collectors.toSet());
	}
}
