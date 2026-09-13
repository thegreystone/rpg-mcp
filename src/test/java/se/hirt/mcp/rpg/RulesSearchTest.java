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

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static se.hirt.mcp.rpg.TestCampaigns.committedCampaign;
import static se.hirt.mcp.rpg.TestCampaigns.engine;
import static se.hirt.mcp.rpg.TestCampaigns.op;
import static se.hirt.mcp.rpg.TestCampaigns.tempDb;

/**
 * The SRD's Rules Glossary is installed as searchable RULE content so a rules question can be
 * answered with a citation instead of a recollection (MCP_PROTOCOL.md §13.8). These tests pin the
 * corpus's shape and the search's ranking.
 */
class RulesSearchTest {

	@SuppressWarnings("unchecked")
	private static List<Map<String, Object>> hits(Map<String, Object> result) {
		return (List<Map<String, Object>>) result.get("results");
	}

	@Test
	void everyConditionActionHazardAndAreaOfEffectIsInstalled() throws Exception {
		Path db = tempDb("rules-corpus");
		try (Engine engine = engine(db)) {
			Map<String, Object> all = engine.content().definitions(null, "RULE", null, null, null, null, null, 100,
					"SUMMARY");
			assertTrue(((Number) all.get("total")).intValue() > 120, "the glossary is installed: " + all.get("total"));
			for (String condition : List.of("Blinded", "Charmed", "Deafened", "Exhaustion", "Frightened", "Grappled",
					"Incapacitated", "Invisible", "Paralyzed", "Petrified", "Poisoned", "Prone", "Restrained",
					"Stunned", "Unconscious")) {
				Map<String, Object> found = engine.content().search(null, condition, "RULE", 5);
				assertTrue(hits(found).stream().anyMatch(h -> (condition + " [Condition]").equals(h.get("name"))),
						"all fifteen SRD conditions are searchable; missing " + condition);
			}
		}
	}

	@Test
	void searchRanksTheRuleItselfAboveThingsThatMentionIt() throws Exception {
		Path db = tempDb("rules-rank");
		try (Engine engine = engine(db)) {
			Map<String, Object> grappled = engine.content().search(null, "grappled", null, 10);
			Map<String, Object> top = hits(grappled).get(0);
			assertEquals("Grappled [Condition]", top.get("name"));
			assertEquals("RULE", top.get("kind"));
			assertEquals("CONDITION", top.get("tag"));
			assertTrue(String.valueOf(top.get("snippet")).contains("Speed is 0"), top.get("snippet") + "");

			// The snippet carries enough to adjudicate without a second call.
			Map<String, Object> cover = engine.content().search(null, "half cover", "RULE", 5);
			assertTrue(String.valueOf(hits(cover).get(0).get("snippet")).contains("+2"),
					"cover bonuses are in the snippet: " + hits(cover).get(0));
		}
	}

	@Test
	void searchSpansEveryKindAndFindsTheCampaignsOwnContent() throws Exception {
		Path db = tempDb("rules-kinds");
		try (Engine engine = engine(db)) {
			String campaign = committedCampaign(engine);
			engine.sessions().bootstrap(op(), campaign, null);
			engine.content().define(op(), campaign, "ITEM", "Keeper's Bell", "custom:item/keepers-bell",
					"A bronze handbell rung to mark the boundary at the start of the tending rite.", "TOOL", "0", 0.5,
					null, List.of("keeper"), null);

			Map<String, Object> bell = engine.content().search(campaign, "tending rite bell", null, 5);
			assertTrue(hits(bell).stream().anyMatch(h -> "Keeper's Bell".equals(h.get("name"))),
					"custom content is searchable: " + hits(bell));

			// A spell, an item and a rule all reachable through one entry point.
			assertEquals("SPELL", hits(engine.content().search(null, "misty step", null, 3)).get(0).get("kind"));
			assertTrue(hits(engine.content().search(null, "shortsword", null, 3)).stream()
					.anyMatch(h -> "ITEM".equals(h.get("kind"))));
			assertEquals("RULE", hits(engine.content().search(null, "long rest", "RULE", 3)).get(0).get("kind"));
		}
	}

	@Test
	void anEmptyQueryIsRefusedAndAMissReturnsNothingRatherThanAGuess() throws Exception {
		Path db = tempDb("rules-miss");
		try (Engine engine = engine(db)) {
			assertThrows(se.hirt.mcp.rpg.protocol.RpgException.class,
					() -> engine.content().search(null, "   ", null, 5));
			Map<String, Object> miss = engine.content().search(null, "zzzznotarule", null, 5);
			assertEquals(0, ((Number) miss.get("total")).intValue());
			assertTrue(hits(miss).isEmpty());
			assertNotNull(miss.get("note"), "the miss says what to do instead");
		}
	}

	/**
	 * The glossary is the SRD's own wording, not a paraphrase — that is the whole point of
	 * installing it.
	 */
	@Test
	void ruleTextIsNotAParaphrase() throws Exception {
		Path db = tempDb("rules-verbatim");
		try (Engine engine = engine(db)) {
			Map<String, Object> defs = engine.content().definitions(null, "RULE", null, "Difficult Terrain", null, null,
					null, 5, "FULL");
			List<Map<String, Object>> items = (List<Map<String, Object>>) defs.get("items");
			Map<String, Object> payload = (Map<String, Object>) items.stream()
					.filter(d -> "Difficult Terrain".equals(d.get("name"))).findFirst().orElseThrow().get("payload");
			assertEquals(Boolean.FALSE, payload.get("text_is_paraphrase"));
			assertTrue(String.valueOf(payload.get("text")).startsWith("If a space is Difficult Terrain, every foot"),
					payload.get("text") + "");
		}
	}
}
