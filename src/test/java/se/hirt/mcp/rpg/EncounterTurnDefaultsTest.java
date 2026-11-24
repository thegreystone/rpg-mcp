/* Copyright (C) 2026 Marcus Hirt. SPDX-License-Identifier: BSD-3-Clause */
package se.hirt.mcp.rpg;

import org.junit.jupiter.api.Test;
import se.hirt.mcp.rpg.dice.ScriptedRollService;
import se.hirt.mcp.rpg.protocol.Json;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static se.hirt.mcp.rpg.TestCampaigns.*;

class EncounterTurnDefaultsTest {
	@Test
	void omittedEndTurnAllowsMoveThenAttackThroughBothEngineAndMcp() throws Exception {
		for (boolean tool : List.of(false, true)) {
			try (Engine e = engine(tempDb("move-default-" + tool))) {
				String campaign = committedCampaign(e);
				e.sessions().bootstrap(op(), campaign, null);
				String guard = (String) e.runtime()
						.materialize(op(), campaign, "Guard", "Sentinel", null, null, null, null, false)
						.get("character");
				((ScriptedRollService) e.roller()).queue(20, 1);
				String encounter = (String) e.encounters()
						.start(op(), campaign, map("party", List.of("character:1"), "guards", List.of(guard)), null,
								null, null, null, null,
								map("placements", List.of(map("character", "character:1", "x_ft", 0, "y_ft", 0),
										map("character", guard, "x_ft", 15, "y_ft", 0))))
						.get("encounter");
				Map<String, Object> moved;
				if (tool) {
					RpgTools tools = new RpgTools();
					tools.engine = e;
					var response = tools.performEncounterAction(op(), campaign, "character:1",
							map("kind", "MOVE", "x_ft", 10, "y_ft", 0), Optional.of(encounter), Optional.empty());
					assertFalse(response.isError());
					moved = cast(Json.readMap(response.firstContent().asText().text()).get("result"));
				} else {
					moved = e.encounters().perform(op(), campaign, encounter, "character:1",
							map("kind", "MOVE", "x_ft", 10, "y_ft", 0));
				}
				assertEquals(false, moved.get("turn_advanced"));
				assertEquals("AVAILABLE", cast(moved.get("turn_economy")).get("action"));
				Map<String, Object> attack = e.encounters().perform(op(), campaign, encounter, "character:1",
						map("kind", "ATTACK", "target", guard), false);
				assertEquals("ATTACK", attack.get("kind"));
				assertEquals(false, attack.get("turn_advanced"));
			}
		}
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> cast(Object v) {
		return (Map<String, Object>) v;
	}
}
