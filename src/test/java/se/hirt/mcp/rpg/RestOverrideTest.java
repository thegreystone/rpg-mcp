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
 * Rests restore only what the rules allow; overrides are policy-gated, audited and labeled
 * (DOMAIN_MODEL.md I-54).
 */
class RestOverrideTest {

	@SuppressWarnings("unchecked")
	private static Map<String, Object> m(Object o) {
		return (Map<String, Object>) o;
	}

	@SuppressWarnings("unchecked")
	private static List<Map<String, Object>> list(Object o) {
		return (List<Map<String, Object>>) o;
	}

	@Test
	void restsAndOverrides() throws Exception {
		try (Engine engine = TestCampaigns.engine(TestCampaigns.tempDb("rest"))) {
			ScriptedRollService dice = (ScriptedRollService) engine.roller();
			String campaign = TestCampaigns.committedCampaign(engine);
			engine.sessions().bootstrap(op(), campaign, null);
			String pc = "character:1";
			int max = (Integer) m(engine.characters().characterSheet(campaign, pc, "SUMMARY").get("hp")).get("max");
			int conMod = (Integer) m(
					m(engine.characters().characterSheet(campaign, pc, "PLAY").get("abilities")).get("CON"))
					.get("modifier");
			engine.runtime().applyRuntimeChange(op(), campaign, pc,
					map("kind", "DAMAGE", "amount", max - 1, "damage_type", "slashing", "reason", "test"));
			assertEquals(1,
					(Integer) m(engine.characters().characterSheet(campaign, pc, "SUMMARY").get("hp")).get("current"));

			// Short rest: one Hit Point Die (d6 + CON), rolled by the server; the pool is level-sized.
			dice.queue(4);
			Map<String, Object> shortRest = engine.rest().rest(op(), campaign, "SHORT", map(pc, 1), null);
			Map<String, Object> richard = list(shortRest.get("characters")).get(0);
			assertEquals(1, richard.get("hit_dice_spent"));
			assertEquals(0, richard.get("hit_dice_left"), "level 1 has one die");
			assertEquals(Math.min(max, 1 + Math.max(0, 4 + conMod)), m(richard.get("hp")).get("current"));
			assertEquals(60L, shortRest.get("elapsed_minutes"));
			assertEquals(ErrorCode.INSUFFICIENT_RESOURCE, assertThrows(RpgException.class,
					() -> engine.rest().rest(op(), campaign, "SHORT", map(pc, 1), null)).code());

			// Long rest: full HP, half the dice back (minimum 1), 8 hours.
			Map<String, Object> longRest = engine.rest().rest(op(), campaign, "LONG", null, null);
			Map<String, Object> rested = list(longRest.get("characters")).get(0);
			assertEquals(max, m(rested.get("hp")).get("current"));
			assertEquals(1, m(rested.get("hit_dice")).get("current"));
			assertEquals(480L, longRest.get("elapsed_minutes"));

			// Overrides: audited, labeled, revision-checked.
			Map<String, Object> override = engine.rest().override(op(), campaign, "ADJUST_HP", pc, map("set_to", 2),
					"the story needs him barely standing", null);
			assertEquals(Boolean.TRUE, override.get("override"));
			assertEquals(Boolean.TRUE, override.get("audited"));
			assertEquals(2, m(override.get("after")).get("current_hp"));
			assertTrue(override.get("label").toString().startsWith("OVERRIDE"));
			assertEquals(ErrorCode.INVALID_ARGUMENT,
					assertThrows(RpgException.class,
							() -> engine.rest().override(op(), campaign, "SET_XP", pc, map("xp", 5), null, null))
							.code());
			RpgException stale = assertThrows(RpgException.class,
					() -> engine.rest().override(op(), campaign, "SET_XP", pc, map("xp", 5), "why", 0L));
			assertEquals(ErrorCode.CONFLICT, stale.code());
			// Kill and revive by fiat.
			engine.rest().override(op(), campaign, "SET_LIFE_STATE", pc, map("life_state", "DEAD"), "a curse", null);
			assertEquals("DEAD", engine.characters().characterSheet(campaign, pc, "SUMMARY").get("life_state"));
			Map<String, Object> revived = engine.rest().override(op(), campaign, "SET_LIFE_STATE", pc,
					map("life_state", "ALIVE", "hp", 3), "the curse lifted", null);
			assertEquals("ALIVE", m(revived.get("after")).get("life_state"));
			assertEquals(3,
					(Integer) m(engine.characters().characterSheet(campaign, pc, "SUMMARY").get("hp")).get("current"));
			long audits = engine.db()
					.read(tx -> tx.count("SELECT COUNT(*) FROM audit_record WHERE kind = 'GM_OVERRIDE'"));
			assertEquals(3, audits);
			assertEquals(3,
					list(engine.ledger()
							.queryTimeline(campaign, null, null, null, List.of("GM_OVERRIDE"), null, true, 10)
							.get("events")).size());
			// Overrides survive nothing: they are ordinary journaled mutations, and the audit survives a restore.
			Map<String, Object> checkpoints = engine.checkpoints().options(campaign);
			String cp = (String) list(list(checkpoints.get("options")).get(0).get("checkpoints")).get(0).get("ref");
			engine.checkpoints().restore(op(), campaign, cp, "undo it all");
			assertEquals(max,
					(Integer) m(engine.characters().characterSheet(campaign, pc, "SUMMARY").get("hp")).get("current"));
			long auditsAfterRestore = engine.db()
					.read(tx -> tx.count("SELECT COUNT(*) FROM audit_record WHERE kind = 'GM_OVERRIDE'"));
			assertEquals(3, auditsAfterRestore, "audit lineage survives rollback (I-53)");
		}
	}

	@Test
	void overridesRespectPolicy() throws Exception {
		try (Engine engine = TestCampaigns.engine(TestCampaigns.tempDb("policy-override"))) {
			String campaign = (String) engine.campaigns().create(op(), null, null).get("campaign");
			engine.campaigns().updateSetup(op(), campaign, null,
					map("content_profile", "PEGI_16", "experience", "SURPRISE_ME", "rules",
							map("gm_override_policy", "DISABLED"), "continuation", "CHECKPOINT", "party", "SURPRISE_ME",
							"adventure", map("premise", "x", "opening_location", "Camp", "immediate_goal", "y")));
			String pc = (String) engine.characters().createDraft(op(), campaign,
					map("name", "Ash", "species", "Human", "class", "Wizard", "ability_scores",
							map("INT", 15, "DEX", 14, "CON", 13, "WIS", 12, "CHA", 10, "STR", 8), "skills",
							List.of("Arcana", "History"), "personality", "x", "background", "Criminal",
							"background_ability_scores", map("CON", 2, "INT", 1), "species_skill", "Insight",
							"origin_feat",
							map("feat", "Skilled", "proficiencies", List.of("Nature", "Survival", "Medicine"))),
					true).get("character");
			engine.characters().commitDraft(op(), campaign, pc, null);
			engine.campaigns().commitSetup(op(), campaign, null);
			engine.sessions().bootstrap(op(), campaign, null);
			RpgException denied = assertThrows(RpgException.class, () -> engine.rest().override(op(), campaign,
					"SET_MONEY", pc, map("money", "1000 gp"), "greed", null));
			assertEquals(ErrorCode.POLICY_DENIED, denied.code());
			assertEquals(ErrorCode.POLICY_DENIED,
					assertThrows(RpgException.class,
							() -> engine.inventory().grantLoot(op(), campaign, pc, null, "1 gp", "GM_GRANT", "x"))
							.code());
			assertEquals(ErrorCode.POLICY_DENIED,
					assertThrows(RpgException.class,
							() -> engine.runtime().awardXp(op(), campaign, List.of(pc), 10, "DISCRETIONARY", "x"))
							.code());
		}
	}
}
