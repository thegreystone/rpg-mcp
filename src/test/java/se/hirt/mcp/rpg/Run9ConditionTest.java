/* Copyright (C) 2026 Marcus Hirt. SPDX-License-Identifier: BSD-3-Clause */
package se.hirt.mcp.rpg;

import org.junit.jupiter.api.Test;
import se.hirt.mcp.rpg.character.RuntimeService;
import se.hirt.mcp.rpg.dice.ScriptedRollService;
import se.hirt.mcp.rpg.persistence.Row;
import se.hirt.mcp.rpg.protocol.Ref;
import se.hirt.mcp.rpg.session.GameTime;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static se.hirt.mcp.rpg.TestCampaigns.*;

class Run9ConditionTest {

	@Test
	void massiveDeathRevivalAddsProneWithoutSpendingTimeOrDiceAndCanStandAfterReload() throws Exception {
		var file = tempDb("run9-dead-revival-prone");
		String campaign;
		long proneId;
		try (Engine e = engine(file)) {
			campaign = ready(e);
			e.runtime().applyRuntimeChange(op(), campaign, "character:1", map("kind", "DAMAGE", "amount", 1000,
					"reason", "An adjudicated massive impact kills the character."));
			assertEquals("DEAD", e.db().read(tx -> tx.get("character", 1).str("life_state")));
			assertNull(condition(e, "PRONE"), "Ordinary death has ended all prior effects.");
			long before = seconds(e, campaign), rolls = rolls(e);
			((ScriptedRollService) e.roller()).queue(4);
			String operation = op();
			var revived = recover(e, campaign, operation);
			assertTrue(mapOf(revived.get("after")).get("condition_note").toString().contains("Dead"));
			assertEquals("ALIVE", e.db().read(tx -> tx.get("character", 1).str("life_state")));
			Row prone = condition(e, "PRONE");
			assertNotNull(prone);
			proneId = prone.id();
			assertEquals(RuntimeService.REVIVAL_PRONE_SOURCE, prone.str("source_description"));
			assertNull(condition(e, "UNCONSCIOUS"));
			assertEquals(before, seconds(e, campaign));
			assertEquals(rolls, rolls(e));
			assertEquals(4, e.roller().roll("1d4").total());
			assertTrue(((ScriptedRollService) e.roller()).exhausted());
			var saved = e.db().snapshot(Ref.id(campaign, Ref.CAMPAIGN));
			assertEquals(true, recover(e, campaign, operation).get("replayed"));
			assertEquals(saved, e.db().snapshot(Ref.id(campaign, Ref.CAMPAIGN)));
		}
		try (Engine e = engine(file)) {
			assertEquals(proneId, condition(e, "PRONE").id());
			var stood = e.runtime().applyRuntimeChange(op(), campaign, "character:1",
					map("kind", "STAND", "reason", "The restored character gets back on their feet."));
			assertEquals(true, stood.get("stood_up"));
			assertNull(condition(e, "PRONE"));
		}
	}

	@Test
	void deadRevivalKeepsExistingProneAndIndependentMagicalConditions() throws Exception {
		try (Engine e = engine(tempDb("run9-revival-independent-condition"))) {
			String campaign = ready(e);
			e.rest().override(op(), campaign, "SET_LIFE_STATE", "character:1", map("life_state", "DEAD"),
					"An explicit death for the recovery regression.", null);
			for (String condition : new String[] {"PRONE", "POISONED", "UNCONSCIOUS"})
				e.runtime().applyRuntimeChange(op(), campaign, "character:1",
						map("kind", "ADD_CONDITION", "condition", condition, "duration", "1 minute", "reason",
								"An independently authored " + condition + " effect."));
			Row prone = condition(e, "PRONE"), poisoned = condition(e, "POISONED"), sleep = condition(e, "UNCONSCIOUS");
			recover(e, campaign, op());
			assertEquals(prone.asMap(), condition(e, "PRONE").asMap());
			assertEquals(poisoned.asMap(), condition(e, "POISONED").asMap());
			assertEquals(sleep.asMap(), condition(e, "UNCONSCIOUS").asMap());
			assertEquals(1L,
					e.db().read(tx -> tx.count(
							"SELECT COUNT(*) FROM active_effect WHERE character_id=1 AND condition_ref=?",
							RuntimeService.conditionRef("PRONE"))).longValue());
		}
	}

	private static String ready(Engine e) {
		String campaign = committedCampaign(e);
		e.sessions().bootstrap(op(), campaign, null);
		return campaign;
	}

	private static Map<String, Object> recover(Engine e, String campaign, String operation) {
		return e.rest().override(operation, campaign, "SET_LIFE_STATE", "character:1",
				map("life_state", "ALIVE", "hp", 3),
				"An explicitly adjudicated revival restores life but does not stand the creature up.", null);
	}

	private static Row condition(Engine e, String condition) {
		return e.db()
				.read(tx -> tx.queryOne(
						"SELECT * FROM active_effect WHERE character_id=1 AND condition_ref=? ORDER BY id LIMIT 1",
						RuntimeService.conditionRef(condition)).orElse(null));
	}

	private static long seconds(Engine e, String campaign) {
		return e.db().read(tx -> GameTime.currentSeconds(tx, Ref.id(campaign, Ref.CAMPAIGN)));
	}

	private static long rolls(Engine e) {
		return e.db().read(tx -> tx.count("SELECT COUNT(*) FROM roll"));
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> mapOf(Object value) {
		return (Map<String, Object>) value;
	}
}
