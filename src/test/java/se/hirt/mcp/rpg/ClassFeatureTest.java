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
import static org.junit.jupiter.api.Assertions.assertNull;
import static se.hirt.mcp.rpg.TestCampaigns.committedCampaign;
import static se.hirt.mcp.rpg.TestCampaigns.engine;
import static se.hirt.mcp.rpg.TestCampaigns.map;
import static se.hirt.mcp.rpg.TestCampaigns.op;
import static se.hirt.mcp.rpg.TestCampaigns.tempDb;

/**
 * Class features that the engine enforces come from the class definition's {@code features} array,
 * not from Java (RULES_ENGINE.md §2.2). Sneak Attack is the first of them: adding the next one
 * should be a seed edit.
 */
class ClassFeatureTest {

	@SuppressWarnings("unchecked")
	private static Map<String, Object> m(Object o) {
		return (Map<String, Object>) o;
	}

	/** Builds a campaign whose party has a Rogue companion, and returns [campaign, rogueRef]. */
	private static String[] withRogue(Engine engine) {
		String campaign = committedCampaign(engine);
		engine.sessions().bootstrap(op(), campaign, null);
		String rogue = (String) engine.runtime()
				.materialize(op(), campaign, "Scout", "Vess", null, null, "CHAOTIC_GOOD", null, false).get("character");
		engine.party().updateMembership(op(), campaign, rogue, "JOIN", "recruited");
		engine.inventory().grantLoot(op(), campaign, rogue, List.of(map("item", "Shortsword", "quantity", 1)), null,
				"GM_GRANT", "her own blade");
		engine.runtime().awardXp(op(), campaign, null, 900, "QUEST", "enough for level 3");
		for (int level = 1; level <= 3; level++) {
			Map<String, Object> begun = engine.levelUps().begin(op(), campaign, rogue);
			String t = (String) m(begun.get("transaction")).get("ref");
			if (level == 1) {
				engine.levelUps().update(op(), campaign, t, null,
						map("class", "Rogue", "skills", List.of("Stealth", "Perception", "Deception", "Acrobatics")));
			}
			engine.levelUps().commit(op(), campaign, t, null);
		}
		return new String[] {campaign, rogue};
	}

	@Test
	void sneakAttackScalesWithRogueLevelAndShowsOnTheSheet() throws Exception {
		Path db = tempDb("feature-sheet");
		try (Engine engine = engine(db)) {
			String[] setup = withRogue(engine);
			Map<String, Object> sheet = engine.characters().characterSheet(setup[0], setup[1], "PLAY");
			List<Map<String, Object>> features = (List<Map<String, Object>>) sheet.get("class_features");
			assertNotNull(features, "class features are on the sheet");
			Map<String, Object> sneak = features.stream().filter(f -> "Sneak Attack".equals(f.get("name"))).findFirst()
					.orElseThrow();
			assertEquals("engine", sneak.get("adjudication"));
			assertEquals("2d6", sneak.get("dice"), "1d6 per two rogue levels, rounded up");
		}
	}

	@Test
	void sneakAttackAppliesOnAQualifyingHitAndOnlyOncePerTurn() throws Exception {
		Path db = tempDb("feature-sneak");
		try (Engine engine = engine(db)) {
			String[] setup = withRogue(engine);
			String campaign = setup[0], rogue = setup[1];
			String ogre = (String) engine.runtime()
					.materialize(op(), campaign, "Ogre", null, null, null, null, null, false).get("character");
			String encounter = (String) engine.encounters().start(op(), campaign,
					map("party", List.of(rogue), "foes", List.of(ogre)), null, null, null, null, null).get("encounter");

			// A shortsword is a Finesse weapon, and advantage qualifies the attack.
			Map<String, Object> first = engine.encounters().perform(op(), campaign, encounter, rogue, map("kind",
					"ATTACK", "attack", "Shortsword", "weapon", "Shortsword", "target", ogre, "advantage", "ADVANTAGE"),
					false);
			Map<String, Object> sneak = m(first.get("sneak_attack"));
			assertNotNull(sneak, "sneak attack applied: " + first);
			assertEquals("Sneak Attack", sneak.get("feature"));
			assertEquals("2d6", sneak.get("dice"));
			assertEquals("advantage on the attack", sneak.get("qualified_by"));

			// Second attack in the same turn: the feature is spent.
			Map<String, Object> second = engine.encounters().perform(op(), campaign, encounter, rogue, map("kind",
					"ATTACK", "attack", "Shortsword", "weapon", "Shortsword", "target", ogre, "advantage", "ADVANTAGE"),
					false);
			assertNull(second.get("sneak_attack"), "once per turn");
		}
	}

	@Test
	void sneakAttackNeedsAFinesseOrRangedWeapon() throws Exception {
		Path db = tempDb("feature-weapon");
		try (Engine engine = engine(db)) {
			String[] setup = withRogue(engine);
			String campaign = setup[0], rogue = setup[1];
			engine.inventory().grantLoot(op(), campaign, rogue, List.of(map("item", "Mace", "quantity", 1)), null,
					"GM_GRANT", "a test mace");
			String ogre = (String) engine.runtime()
					.materialize(op(), campaign, "Ogre", null, null, null, null, null, false).get("character");
			String encounter = (String) engine.encounters().start(op(), campaign,
					map("party", List.of(rogue), "foes", List.of(ogre)), null, null, null, null, null).get("encounter");
			Map<String, Object> hit = engine.encounters().perform(op(), campaign, encounter, rogue,
					map("kind", "ATTACK", "attack", "Mace", "weapon", "Mace", "target", ogre, "advantage", "ADVANTAGE"),
					false);
			assertNull(hit.get("sneak_attack"), "a mace is neither Finesse nor Ranged: " + hit);
		}
	}

	/**
	 * Sleep has NO higher-level clause in SRD 5.2.1 — its radius does not grow with the slot. This
	 * test exists because the GM asserted otherwise from memory, edited the seed to match, and had
	 * to be corrected by the PDF. Three spells verified against the SRD text on 2026-09-03 are
	 * pinned here.
	 */
	@Test
	void spellScalingMatchesTheVerifiedSrdText() throws Exception {
		Path db = tempDb("feature-spells");
		try (Engine engine = engine(db)) {
			String campaign = committedCampaign(engine);
			assertNull(mechanicsOf(engine, campaign, "Sleep").get("upcast"),
					"SRD 5.2.1 Sleep has no Using a Higher-Level Spell Slot clause");
			assertEquals("1d8",
					m(mechanicsOf(engine, campaign, "Glyph of Warding").get("upcast")).get("per_level_dice"),
					"explosive rune damage per slot level above 3");
		}
	}

	@Test
	void protectionFromEvilAndGoodIsConcentration() throws Exception {
		Path db = tempDb("feature-conc");
		try (Engine engine = engine(db)) {
			String campaign = committedCampaign(engine);
			Map<String, Object> payload = payloadOf(engine, campaign, "Protection from Evil and Good");
			assertEquals(Boolean.TRUE, payload.get("concentration"));
			assertEquals("Concentration, up to 10 minutes", payload.get("duration"));
		}
	}

	private static Map<String, Object> payloadOf(Engine engine, String campaign, String name) {
		Map<String, Object> defs = engine.content().definitions(campaign, "SPELL", null, name, null, null, null, 50,
				"FULL");
		List<Map<String, Object>> items = (List<Map<String, Object>>) defs.get("items");
		return m(items.stream().filter(d -> name.equals(d.get("name"))).findFirst().orElseThrow().get("payload"));
	}

	private static Map<String, Object> mechanicsOf(Engine engine, String campaign, String name) {
		return m(payloadOf(engine, campaign, name).get("mechanics"));
	}
}
