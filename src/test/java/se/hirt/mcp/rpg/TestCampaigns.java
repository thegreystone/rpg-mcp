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

import se.hirt.mcp.rpg.dice.ScriptedRollService;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Test helpers: builds the EXECUTION_EXAMPLE.md campaign (Richard Greystone, human sorcerer) through the real
 * services.
 */
final class TestCampaigns {

	private static final AtomicInteger OPS = new AtomicInteger();

	private TestCampaigns() {
	}

	static String op() {
		return "op-" + OPS.incrementAndGet();
	}

	static Path tempDb(String name) throws Exception {
		Path dir = Files.createTempDirectory("rpg-mcp-" + name);
		return dir.resolve("rpg.db");
	}

	static Engine engine(Path db) {
		return new Engine(db, new ScriptedRollService(42), "test");
	}

	static Map<String, Object> map(Object... kv) {
		var m = new LinkedHashMap<String, Object>();
		for (int i = 0; i < kv.length; i += 2) {
			m.put((String) kv[i], kv[i + 1]);
		}
		return m;
	}

	/** Runs the whole wizard and returns the committed campaign ref ("campaign:N"). */
	static String committedCampaign(Engine engine) {
		String campaign = (String) engine.campaigns().create(op(), null, "srd5e").get("campaign");
		engine.campaigns().updateSetup(op(), campaign, null, map("player_age", 53));
		engine.campaigns().updateSetup(op(), campaign, null, map("content_profile", "PEGI_18"));
		engine.campaigns().updateSetup(op(), campaign, null, map("experience",
				map("authorship", "GUIDED_SURPRISE", "fantasy_style", "HIGH_FANTASY", "themes",
						List.of("MYSTERY", "EXPLORATION", "CHARACTER_DRAMA"), "excluded_themes", "SURPRISE_ME"),
				"rules", map("ability_generation", "ROLL_4D6_DROP_LOWEST", "progression", "XP", "gm_override_policy",
						"EXPLICIT_AUDITED", "hp_progression", "ROLL"), "continuation", "CHECKPOINT"));
		String pc = (String) engine.characters()
				.createDraft(op(), campaign, map("species", "Human", "class", "Sorcerer", "presentation", "male"), true)
				.get("character");
		engine.characters().generateAbilityScores(op(), campaign, pc, null);
		@SuppressWarnings("unchecked") Map<String, Object> creation = (Map<String, Object>) engine.characters()
				.characterSheet(campaign, pc, "FULL").get("creation");
		@SuppressWarnings("unchecked") List<Integer> available = (List<Integer>) creation.get("available_scores");
		var sorted = new java.util.ArrayList<>(available);
		sorted.sort(java.util.Collections.reverseOrder());
		engine.characters().updateDraft(op(), campaign, pc, null, map("name", "Lucien Vale", "ability_scores",
				map("CHA", sorted.get(0), "CON", sorted.get(1), "DEX", sorted.get(2), "INT", sorted.get(3), "WIS",
						sorted.get(4), "STR", sorted.get(5)), "background", "Sage", "background_ability_scores",
				map("CON", 2, "INT", 1), "skills", List.of("Deception", "Persuasion"), "species_skill", "Insight",
				"origin_feat", map("feat", "Skilled", "proficiencies", List.of("Athletics", "Perception", "Stealth")),
				"feat_choices",
				map("feat", "Magic Initiate", "ability", "INT", "cantrips", List.of("Light", "Mage Hand"), "spell",
						"Detect Magic"), "personality",
				"Intelligent and charismatic; highly self-confident, fundamentally decent, but willing to make morally questionable pragmatic choices.",
				"backstory",
				"Younger son of a minor noble house. Innate magic manifested publicly during a diplomatic banquet.",
				"appearance", "Tall, dark hair, green eyes.", "alignment", "Chaotic Good"));
		engine.characters().updateDraft(op(), campaign, pc, null, map("name", "Richard Greystone"));
		engine.characters().commitDraft(op(), campaign, pc, null);
		engine.campaigns().updateSetup(op(), campaign, null, map("party",
				map("desired_roles", List.of("ROGUE", "FRONTLINE"), "relationship_seeds",
						List.of("FRIENDSHIP", "RIVALRY", "ROMANCE"), "reveal_relationship_targets", false,
						"companion_agency", "HIGH"), "adventure", map("premise",
						"The apparition seen by Richard was connected to an ancient magical boundary that is beginning to fail.",
						"background_truth", "Someone is deliberately accelerating the failures.", "opening_location",
						map("name", "Bellhaven - South Gate", "kind", "SETTLEMENT", "description",
								"Trading town on a broad silver river."), "immediate_goal",
						"Find the late Professor Aldren's contact in Bellhaven (The Copper Kettle).", "start_time",
						"Day 1, 16:40")));
		engine.campaigns().commitSetup(op(), campaign, null);
		return campaign;
	}
}
