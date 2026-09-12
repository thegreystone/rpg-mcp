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
 * Level-up transaction invariants (DOMAIN_MODEL.md I-55..I-57): nothing touches the live character until commit; commit
 * is atomic; abandon leaves canonical state unchanged.
 */
class LevelUpTest {

	@SuppressWarnings("unchecked")
	private static Map<String, Object> m(Object o) {
		return (Map<String, Object>) o;
	}

	@Test
	void levelUpTransaction() throws Exception {
		try (Engine engine = TestCampaigns.engine(TestCampaigns.tempDb("levelup"))) {
			ScriptedRollService dice = (ScriptedRollService) engine.roller();
			String campaign = TestCampaigns.committedCampaign(engine);
			engine.sessions().bootstrap(op(), campaign, null);
			String pc = "character:1";
			Map<String, Object> sheet = engine.characters().characterSheet(campaign, pc, "PLAY");
			int maxHp = (Integer) m(sheet.get("hp")).get("max");
			int conMod = (Integer) m(m(sheet.get("abilities")).get("CON")).get("modifier");

			// Not enough XP.
			RpgException tooEarly = assertThrows(RpgException.class, () -> engine.levelUps().begin(op(), campaign, pc));
			assertEquals(ErrorCode.VALIDATION_FAILED, tooEarly.code());

			engine.runtime().awardXp(op(), campaign, List.of(pc), 300, "QUEST", "found the notebook");
			dice.queue(5);
			Map<String, Object> begun = engine.levelUps().begin(op(), campaign, pc);
			String tx = (String) m(begun.get("transaction")).get("ref");
			assertTrue(tx.startsWith("transaction:"));
			assertEquals("LEVEL_UP", m(begun.get("meta")).get("harness_state"));
			assertEquals(2, begun.get("to_level"));
			assertNull(begun.get("ability_score_improvement"), "no ASI at level 2");
			assertEquals("ROLL", m(begun.get("hit_points")).get("policy"), "fixture campaign policy");
			assertEquals(Math.max(1, 5 + conMod), m(begun.get("hit_points")).get("gain"),
					"d6 rolled 5 + CON, fixed at begin");

			// Gameplay is gated while the transaction is open; a second transaction is refused (I-55).
			RpgException gated = assertThrows(RpgException.class, () -> engine.checks()
					.resolveCheck(op(), campaign, pc, "ABILITY_CHECK", "STR", null, 10, null, null));
			assertEquals(ErrorCode.OPERATION_NOT_ALLOWED, gated.code());
			assertTrue(((List<?>) gated.details().get("allowed_operations")).contains("commit_level_up"));

			// A sorcerer reaching level 2 owes two Metamagic options (SRD 5.2.1); until they are chosen the
			// transaction does not validate. Hit points themselves are preset by the campaign policy.
			assertEquals(Boolean.FALSE, engine.levelUps().validate(campaign, tx).get("valid"));
			assertEquals(2, m(begun.get("metamagic_choice")).get("choose"));
			engine.levelUps().update(op(), campaign, tx, null,
					map("metamagic", List.of("Empowered Spell", "Quickened Spell")));
			assertEquals(Boolean.TRUE, engine.levelUps().validate(campaign, tx).get("valid"));
			// ASI is not offered at level 2.
			RpgException noAsi = assertThrows(RpgException.class, () -> engine.levelUps()
					.update(op(), campaign, tx, null, map("ability_score_improvement", map("CHA", 2))));
			assertEquals(ErrorCode.VALIDATION_FAILED, noAsi.code());

			// hp_method is no longer a per-level choice; the campaign policy governs.
			assertEquals(ErrorCode.INVALID_ARGUMENT, assertThrows(RpgException.class,
					() -> engine.levelUps().update(op(), campaign, tx, null, map("hp_method", "ROLL"))).code());
			// The live character is untouched until commit.
			assertEquals(maxHp, m(engine.characters().characterSheet(campaign, pc, "PLAY").get("hp")).get("max"));
			assertEquals(1, engine.characters().characterSheet(campaign, pc, "PLAY").get("level"));

			Map<String, Object> valid = engine.levelUps().validate(campaign, tx);
			assertEquals(Boolean.TRUE, valid.get("valid"));
			assertEquals(maxHp + Math.max(1, 5 + conMod), m(m(valid.get("preview")).get("after")).get("max_hp"));

			Map<String, Object> committed = engine.levelUps().commit(op(), campaign, tx, null);
			assertEquals(2, committed.get("level"));
			assertEquals("EXPLORATION", m(committed.get("meta")).get("harness_state"));
			Map<String, Object> after = engine.characters().characterSheet(campaign, pc, "PLAY");
			assertEquals(2, after.get("level"));
			assertEquals(maxHp + Math.max(1, 5 + conMod), m(after.get("hp")).get("max"));
			assertEquals(maxHp + Math.max(1, 5 + conMod), m(after.get("hp")).get("current"),
					"current HP rises with the maximum");
			assertEquals(Boolean.FALSE, after.get("level_up_eligible"));
			assertEquals(1, ((List<?>) engine.ledger()
					.queryTimeline(campaign, null, null, null, List.of("LEVEL_UP"), null, false, 5)
					.get("events")).size());
			assertEquals(ErrorCode.OPERATION_NOT_ALLOWED,
					assertThrows(RpgException.class, () -> engine.levelUps().commit(op(), campaign, tx, null)).code(),
					"a committed transaction cannot be committed again");

			// Jump to level 4 (2700 XP) for an ASI, and abandon once on the way.
			engine.runtime().awardXp(op(), campaign, List.of(pc), 2400, "MILESTONE", "chapter one");
			String t3 = (String) m(engine.levelUps().begin(op(), campaign, pc).get("transaction")).get("ref");
			Map<String, Object> abandoned = engine.levelUps().abandon(op(), campaign, t3, "changed my mind");
			assertEquals("ABANDONED", abandoned.get("status"));
			assertEquals(2, engine.characters().characterSheet(campaign, pc, "PLAY").get("level"),
					"abandon changes nothing (I-56)");
			assertEquals("EXPLORATION", m(abandoned.get("meta")).get("harness_state"));
			t3 = (String) m(engine.levelUps().begin(op(), campaign, pc).get("transaction")).get("ref");
			engine.levelUps().commit(op(), campaign, t3, null);
			Map<String, Object> begun4 = engine.levelUps().begin(op(), campaign, pc);
			String t4 = (String) m(begun4.get("transaction")).get("ref");
			assertNotNull(begun4.get("ability_score_improvement"), "level 4 grants an ASI");
			int cha = (Integer) m(
					m(engine.characters().characterSheet(campaign, pc, "PLAY").get("abilities")).get("CHA")).get(
					"score");
			RpgException badAsi = assertThrows(RpgException.class, () -> engine.levelUps()
					.update(op(), campaign, t4, null, map("ability_score_improvement", map("CHA", 3))));
			assertEquals(ErrorCode.VALIDATION_FAILED, badAsi.code());
			// A feat may be taken instead of the ASI (SRD 5.2.1); an unknown feat is refused, a legal one recorded.
			assertEquals(ErrorCode.INVALID_ARGUMENT, assertThrows(RpgException.class,
					() -> engine.levelUps().update(op(), campaign, t4, null, map("feat", "No Such Feat"))).code());
			engine.levelUps().update(op(), campaign, t4, null, map("feat", "Alert"));
			engine.levelUps()
					.update(op(), campaign, t4, null, map("ability_score_improvement", map("CHA", 1, "CON", 1)));
			Map<String, Object> done = engine.levelUps().commit(op(), campaign, t4, null);
			assertEquals(4, done.get("level"));
			assertEquals(cha + 1, m(m(m(done.get("sheet")).get("abilities")).get("CHA")).get("score"));
			assertEquals(2, m(done.get("sheet")).get("proficiency_bonus"));
		}
	}
}
