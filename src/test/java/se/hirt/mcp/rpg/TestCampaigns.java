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
 * Test helpers: builds the EXECUTION_EXAMPLE.md campaign (Richard Greystone, human sorcerer)
 * through the real services.
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

	/** Explicitly stage a rules fixture in melee; production deployments remain story-neutral. */
	static Map<String, Object> meleeZones(String ... characters) {
		var positions = new LinkedHashMap<String, Object>();
		for (String character : characters)
			positions.put(character, "near");
		return positions;
	}

	static Map<String, Object> map(Object ... kv) {
		var m = new LinkedHashMap<String, Object>();
		for (int i = 0; i < kv.length; i += 2) {
			m.put((String) kv[i], kv[i + 1]);
		}
		return m;
	}

	/**
	 * Explicit physical setup for rules fixtures that formerly repositioned actors by zone names.
	 */
	static void gridPositions(Engine engine, String campaign, Map<String, List<Integer>> positions) {
		long cid = se.hirt.mcp.rpg.protocol.Ref.id(campaign, se.hirt.mcp.rpg.protocol.Ref.CAMPAIGN);
		engine.db().mutate(
				se.hirt.mcp.rpg.persistence.Database.Mutation.of("grid_positions_fixture", cid, op(), "GM", positions),
				tx -> {
					var scenes = new java.util.HashSet<Long>();
					for (var entry : positions.entrySet()) {
						var actor = se.hirt.mcp.rpg.maps.SceneService.actor(tx, cid, entry.getKey());
						tx.update("map_actor", actor.id(), map("x_ft", entry.getValue().get(0), "y_ft",
								entry.getValue().get(1), "revision", actor.lng("revision") + 1));
						scenes.add(actor.lng("scene_id"));
					}
					for (long sceneId : scenes) {
						var scene = tx.get("map_scene", sceneId);
						for (var actor : tx.query("SELECT * FROM map_actor WHERE scene_id=?", sceneId)) {
							var geometry = se.hirt.mcp.rpg.maps.SceneService.geometry(
									se.hirt.mcp.rpg.maps.SceneService.document(tx, scene), actor.intOr("level", 0));
							assert se.hirt.mcp.rpg.spatial.SpatialRules.canPlace(geometry,
									se.hirt.mcp.rpg.maps.SceneService.footprint(actor), se.hirt.mcp.rpg.maps.GridCombat
											.occupied(tx, sceneId, actor.intOr("level", 0), actor.lng("character_id")))
									: "Invalid physical test fixture";
						}
						se.hirt.mcp.rpg.maps.SceneService.remember(tx, scene);
					}
					return Map.of();
				});
	}

	/**
	 * Explicit authored atlas for tests that require regional geometry; ordinary setup remains
	 * source-only.
	 */
	static String publishRegionalFixture(Engine engine, String campaign) {
		String opening = engine.db().read(tx -> "location:"
				+ tx.get("campaign", se.hirt.mcp.rpg.protocol.Ref.id(campaign, "campaign")).lng("current_location_id"));
		var draft = engine.maps().begin(op(), campaign, "REGION", map("name", "Authored Bellhaven coast", "geometry",
				map("bounds_ft", List.of(0, 0, 528000, 528000)), "rooms", List.of(map("key", "bellhaven", "name",
						"Bellhaven - South Gate", "location_ref", opening, "point_ft", List.of(180000, 180000))),
				"features",
				List.of(map("key", "bellhaven-symbol", "kind", "TOWN", "name", "Bellhaven", "point_ft",
						List.of(180000, 180000), "graph_ref", "bellhaven"),
						map("key", "coast", "kind", "OCEAN", "name", "Western Sea", "polygon_ft",
								List.of(List.of(0, 0), List.of(90000, 0), List.of(70000, 300000), List.of(0, 528000))),
						map("key", "river", "kind", "RIVER", "name", "Bellhaven River", "width_ft", 60, "path_ft",
								List.of(List.of(85000, 150000), List.of(180000, 180000), List.of(260000, 260000),
										List.of(300000, 528000))),
						map("key", "road", "kind", "ROAD", "name", "Eastern Road", "width_ft", 10, "path_ft",
								List.of(List.of(180000, 180000), List.of(260000, 190000), List.of(330000, 250000),
										List.of(528000, 260000))),
						map("key", "forest", "kind", "FOREST", "name", "Whisperwood", "polygon_ft",
								List.of(List.of(200000, 300000), List.of(300000, 300000), List.of(280000, 420000),
										List.of(180000, 400000))),
						map("key", "ridge", "kind", "MOUNTAIN", "name", "Harrow Hills", "polygon_ft",
								List.of(List.of(380000, 330000), List.of(480000, 300000), List.of(500000, 440000),
										List.of(430000, 450000))))));
		return (String) engine.maps().publish(op(), campaign, (String) draft.get("preparation"), null).get("map_ref");
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
						"EXPLICIT_AUDITED", "hp_progression", "ROLL"),
				"continuation", "CHECKPOINT"));
		String pc = (String) engine.characters()
				.createDraft(op(), campaign, map("species", "Human", "class", "Sorcerer", "presentation", "male"), true)
				.get("character");
		engine.characters().generateAbilityScores(op(), campaign, pc, null);
		@SuppressWarnings("unchecked")
		Map<String, Object> creation = (Map<String, Object>) engine.characters().characterSheet(campaign, pc, "FULL")
				.get("creation");
		@SuppressWarnings("unchecked")
		List<Integer> available = (List<Integer>) creation.get("available_scores");
		var sorted = new java.util.ArrayList<>(available);
		sorted.sort(java.util.Collections.reverseOrder());
		engine.characters().updateDraft(op(), campaign, pc, null, map("name", "Lucien Vale", "ability_scores",
				map("CHA", sorted.get(0), "CON", sorted.get(1), "DEX", sorted.get(2), "INT", sorted.get(3), "WIS",
						sorted.get(4), "STR", sorted.get(5)),
				"background", "Sage", "background_ability_scores", map("CON", 2, "INT", 1), "skills",
				List.of("Deception", "Persuasion"), "species_skill", "Insight", "origin_feat",
				map("feat", "Skilled", "proficiencies", List.of("Athletics", "Perception", "Stealth")), "feat_choices",
				map("feat", "Magic Initiate", "ability", "INT", "cantrips", List.of("Light", "Mage Hand"), "spell",
						"Detect Magic"),
				"personality",
				"Intelligent and charismatic; highly self-confident, fundamentally decent, but willing to make morally questionable pragmatic choices.",
				"backstory",
				"Younger son of a minor noble house. Innate magic manifested publicly during a diplomatic banquet.",
				"appearance", "Tall, dark hair, green eyes.", "alignment", "Chaotic Good"));
		engine.characters().updateDraft(op(), campaign, pc, null, map("name", "Richard Greystone"));
		engine.characters().commitDraft(op(), campaign, pc, null);
		engine.campaigns().updateSetup(op(), campaign, null,
				map("party", map("desired_roles", List.of("ROGUE", "FRONTLINE"), "relationship_seeds",
						List.of("FRIENDSHIP", "RIVALRY", "ROMANCE"), "reveal_relationship_targets", false,
						"companion_agency", "HIGH"), "adventure",
						map("premise",
								"The apparition seen by Richard was connected to an ancient magical boundary that is beginning to fail.",
								"background_truth", "Someone is deliberately accelerating the failures.",
								"opening_location",
								map("name", "Bellhaven - South Gate", "kind", "SETTLEMENT", "description",
										"Trading town on a broad silver river."),
								"immediate_goal",
								"Find the late Professor Aldren's contact in Bellhaven (The Copper Kettle).",
								"start_time", "Day 1, 16:40")));
		engine.campaigns().commitSetup(op(), campaign, null);
		return campaign;
	}
}
