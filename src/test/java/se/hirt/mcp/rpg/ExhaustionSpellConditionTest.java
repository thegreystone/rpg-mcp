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
import se.hirt.mcp.rpg.content.RulesData;
import se.hirt.mcp.rpg.dice.ScriptedRollService;
import se.hirt.mcp.rpg.magic.SpellService;
import se.hirt.mcp.rpg.persistence.Database;
import se.hirt.mcp.rpg.protocol.Json;
import se.hirt.mcp.rpg.protocol.Ref;
import se.hirt.mcp.rpg.rules.Effects;
import se.hirt.mcp.rpg.rules.Exhaustion;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static se.hirt.mcp.rpg.TestCampaigns.*;

/** Installed spell mechanics inflict cumulative Exhaustion through the shared casting core. */
class ExhaustionSpellConditionTest {

	private static final String SPELL = "srd5e:spell/acid-splash";

	@Test
	void repeatedFailedSavesGainLevelsAndTheSixthKillsWithoutCreatingConditionEffects() throws Exception {
		try (Engine engine = engine(tempDb("exhaustion-installed-spell"))) {
			String campaign = committedCampaign(engine);
			engine.sessions().bootstrap(op(), campaign, null);
			RulesData rules = installedExhaustionSpell(engine, campaign);
			String target = (String) engine.runtime()
					.materialize(op(), campaign, "Bandit", null, null, null, null, null, false).get("character");
			for (int level = 1; level <= 6; level++) {
				Map<String, Object> result = cast(engine, rules, campaign, target);
				Map<String, Object> outcome = targets(result).get(0);
				assertEquals(Boolean.FALSE, outcome.get("saved"));
				assertEquals(level, outcome.get("exhaustion"));
				assertFalse(outcome.containsKey("duration"), "Exhaustion has no automatic spell-duration expiry");
				assertEquals(level,
						engine.db()
								.read(tx -> tx.get("character", Ref.id(target, Ref.CHARACTER)).intOr("exhaustion", 0))
								.intValue());
				assertEquals(level < 6 ? "ALIVE" : "DEAD",
						engine.db().read(tx -> tx.get("character", Ref.id(target, Ref.CHARACTER)).str("life_state")));
				assertEquals(List.of(), result.get("effects_created"));
				assertEquals(0L,
						engine.db().read(tx -> tx.count(
								"SELECT COUNT(*) FROM active_effect WHERE character_id = ? AND condition_ref = ?",
								Ref.id(target, Ref.CHARACTER), Exhaustion.CONDITION_REF)).longValue());
				if (level == 6) {
					assertEquals(Boolean.TRUE, outcome.get("died"));
					assertEquals(0,
							engine.db().read(
									tx -> tx.get("character", Ref.id(target, Ref.CHARACTER)).intOr("current_hp", 0))
									.intValue());
				}
			}
			assertTrue(((ScriptedRollService) engine.roller()).exhausted());
		}
	}

	@Test
	void failedSavesRespectCreatureAndEffectImmunity() throws Exception {
		try (Engine engine = engine(tempDb("exhaustion-installed-spell-immunity"))) {
			String campaign = committedCampaign(engine);
			engine.sessions().bootstrap(op(), campaign, null);
			RulesData rules = installedExhaustionSpell(engine, campaign);
			String golem = (String) engine.runtime()
					.materialize(op(), campaign, "Flesh Golem", null, null, null, null, null, false).get("character");
			String bandit = (String) engine.runtime()
					.materialize(op(), campaign, "Bandit", null, null, null, null, null, false).get("character");
			long campaignId = Ref.id(campaign, Ref.CAMPAIGN);
			engine.db().mutate(Database.Mutation.of("test_spell_exhaustion_immunity", campaignId, op(), "GM", null),
					tx -> {
						Effects.add(tx, campaignId, Ref.id(bandit, Ref.CHARACTER), null, null, "Exhaustion ward", null,
								Map.of("immune_conditions", List.of("EXHAUSTION")), null, null, null, "GM");
						return Map.of();
					});
			for (String target : List.of(golem, bandit)) {
				Map<String, Object> before = engine.db()
						.read(tx -> tx.get("character", Ref.id(target, Ref.CHARACTER)).asMap());
				Map<String, Object> result = cast(engine, rules, campaign, target);
				Map<String, Object> outcome = targets(result).get(0);
				assertEquals(Boolean.FALSE, outcome.get("saved"));
				assertEquals("EXHAUSTION", outcome.get("immune"));
				assertEquals(before,
						engine.db().read(tx -> tx.get("character", Ref.id(target, Ref.CHARACTER)).asMap()));
				assertEquals(List.of(), result.get("effects_created"));
			}
			assertTrue(((ScriptedRollService) engine.roller()).exhausted());
		}
	}

	/**
	 * A test installed-content fixture, loaded into a fresh rules view. The engine's cached
	 * definitions remain untouched; this exercises mechanics a content pack can install.
	 */
	private static RulesData installedExhaustionSpell(Engine engine, String campaign) {
		engine.db().mutate(Database.Mutation.of("test_install_exhaustion_spell", Ref.id(campaign, Ref.CAMPAIGN), op(),
				"ADMINISTRATIVE_OVERRIDE", null), tx -> {
					var installed = tx.queryOne("SELECT * FROM installed_content WHERE content_id = ?", SPELL)
							.orElseThrow();
					var payload = new LinkedHashMap<>(installed.map("payload_json"));
					payload.put("mechanics", map("kind", "SAVE", "save", "DEX", "on_fail_condition", "EXHAUSTION",
							"on_success", "NONE", "duration_minutes", 1));
					tx.rawExecute("UPDATE installed_content SET payload_json = ? WHERE content_id = ?",
							Json.write(payload), SPELL);
					if (tx.count(
							"SELECT COUNT(*) FROM character_trait WHERE character_id = ? AND kind = 'SPELL_KNOWN' AND content_ref = ?",
							1L, SPELL) == 0) {
						tx.insert("character_trait",
								map("character_id", 1L, "kind", "SPELL_KNOWN", "content_ref", SPELL));
					}
					return Map.of();
				});
		return new RulesData(engine.db());
	}

	private static Map<String, Object> cast(Engine engine, RulesData rules, String campaign, String target) {
		long campaignId = Ref.id(campaign, Ref.CAMPAIGN);
		((ScriptedRollService) engine.roller()).queue(1);
		return engine.db()
				.mutate(Database.Mutation.of("test_cast_installed_exhaustion_spell", campaignId, op(), "PLAYER",
						Map.of("target", target)),
						tx -> SpellService.castCore(tx, rules, engine.roller(), campaignId, tx.get("character", 1L),
								SPELL, null, List.of(target), null, null, 0));
	}

	@SuppressWarnings("unchecked")
	private static List<Map<String, Object>> targets(Map<String, Object> result) {
		return (List<Map<String, Object>>) result.get("targets");
	}
}
