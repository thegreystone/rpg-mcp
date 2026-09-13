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
package se.hirt.mcp.rpg.world;

import se.hirt.mcp.rpg.character.Origins;
import se.hirt.mcp.rpg.content.RulesData;
import se.hirt.mcp.rpg.dice.Roll;
import se.hirt.mcp.rpg.dice.RollService;
import se.hirt.mcp.rpg.persistence.Row;
import se.hirt.mcp.rpg.persistence.Tx;
import se.hirt.mcp.rpg.protocol.Ref;

import java.util.*;

/**
 * Travel encounters (RULES_ENGINE.md, "Travel encounters"). The SRD 5.2.1 has no random-encounter
 * tables; this is the engine's own: a chance per four hours of travel that depends on the ground
 * crossed (the tags of the locations along the route), a creature drawn from a terrain table of
 * installed SRD stat blocks, and a count sized from the SRD 5.2.1 XP budget for a Moderate
 * encounter at the party's level. The result is a <em>suggestion</em> in the {@code consequences}
 * of {@code move_party}: the GM starts an encounter with it, reshapes it, or ignores it. Nothing is
 * written. The rolls go through the {@link RollService} so tests can script them.
 */
final class TravelEncounters {

	/**
	 * Ground that does not produce encounters at all: the party is inside walls or on water it
	 * owns.
	 */
	private static final Set<String> SAFE = Set.of("town", "city", "settlement", "inn", "building", "manor", "castle",
			"village", "home", "market", "chapter", "cathedral", "capital", "quay", "wharf");
	/** Ground where the chance is one in twelve per four hours. */
	private static final Set<String> ROAD = Set.of("road", "highway", "bridge", "ford", "estuary", "lane", "path",
			"river", "coast", "shore");
	/**
	 * Terrain tables: the first tag found on the route decides the table; the wilderness table is
	 * the fallback.
	 */
	private static final Map<String, List<String>> TABLES = new LinkedHashMap<>();

	static {
		TABLES.put("road", List.of("bandit", "bandit-captain", "scout", "wolf", "goblin-warrior", "hobgoblin-warrior",
				"guard", "cultist", "spy", "thug", "giant-rat", "swarm-of-rats"));
		TABLES.put("forest",
				List.of("wolf", "dire-wolf", "brown-bear", "black-bear", "boar", "goblin-warrior", "hobgoblin-warrior",
						"bugbear-warrior", "giant-spider", "owlbear", "bandit", "ogre", "awakened-tree", "blink-dog",
						"worg"));
		TABLES.put("marsh",
				List.of("giant-rat", "swarm-of-rats", "crocodile", "giant-frog", "giant-toad", "lizardfolk",
						"will-o-wisp", "ghoul", "zombie", "skeleton", "stirge", "giant-constrictor-snake",
						"swarm-of-insects", "bullywug"));
		TABLES.put("hills", List.of("wolf", "goblin-warrior", "hobgoblin-warrior", "ogre", "giant-goat", "hill-giant",
				"bandit", "worg", "gnoll-warrior", "harpy", "griffon", "bugbear-warrior"));
		TABLES.put("mountain", List.of("giant-eagle", "griffon", "ogre", "hill-giant", "stone-giant", "harpy",
				"giant-goat", "wolf", "winter-wolf", "manticore", "goblin-warrior"));
		TABLES.put("ruin", List.of("skeleton", "zombie", "ghoul", "ghast", "shadow", "specter", "giant-spider",
				"swarm-of-rats", "cultist", "gargoyle", "animated-armor", "mimic", "wight"));
		TABLES.put("dungeon", List.of("skeleton", "zombie", "ghoul", "giant-rat", "swarm-of-rats", "giant-spider",
				"gelatinous-cube", "ochre-jelly", "mimic", "animated-armor", "goblin-warrior", "cultist", "shadow"));
		TABLES.put("coast", List.of("bandit", "scout", "giant-crab", "sahuagin-warrior", "merfolk-skirmisher", "harpy",
				"giant-eagle", "reef-shark", "swarm-of-rats", "smuggler", "pirate"));
		TABLES.put("river", List.of("crocodile", "giant-frog", "giant-toad", "bandit", "merfolk-skirmisher",
				"giant-constrictor-snake", "swarm-of-insects", "lizardfolk", "wolf"));
		TABLES.put("wilderness", List.of("wolf", "dire-wolf", "brown-bear", "boar", "goblin-warrior", "bandit", "ogre",
				"giant-spider", "worg", "gnoll-warrior", "elk", "giant-eagle"));
	}

	/**
	 * Tags that select a terrain table (checked in this order); the wilderness table is the
	 * fallback.
	 */
	private static final List<Map.Entry<Set<String>, String>> TERRAIN = List.of(
			Map.entry(Set.of("dungeon", "cave", "crypt", "cellar", "mine", "tomb"), "dungeon"),
			Map.entry(Set.of("ruin", "ruins", "barrow", "haunted", "graveyard"), "ruin"),
			Map.entry(Set.of("marsh", "fen", "swamp", "bog"), "marsh"),
			Map.entry(Set.of("mountain", "mountains", "peak", "pass"), "mountain"),
			Map.entry(Set.of("hills", "hill", "chalk", "downs", "moor"), "hills"),
			Map.entry(Set.of("forest", "wood", "woods", "willows"), "forest"),
			Map.entry(Set.of("coast", "sea", "shore", "salt", "beach", "cliffs"), "coast"),
			Map.entry(Set.of("river", "estuary", "ford", "mooring", "lake"), "river"),
			Map.entry(Set.of("road", "highway", "lane", "path", "bridge"), "road"));

	/**
	 * SRD 5.2.1 Gameplay Toolbox, "XP Budget per Character" (Low, Moderate, High) by character
	 * level 1..20. The Moderate column sizes a travel encounter.
	 */
	private static final int[][] XP_BUDGET = {{50, 75, 100}, {100, 150, 200}, {150, 225, 400}, {250, 375, 500},
			{500, 750, 1100}, {600, 1000, 1400}, {750, 1300, 1700}, {1000, 1700, 2100}, {1300, 2000, 2600},
			{1600, 2300, 3100}, {1900, 2900, 4100}, {2200, 3700, 4700}, {2600, 4200, 5400}, {2900, 4900, 6200},
			{3300, 5400, 7800}, {3800, 6100, 9800}, {4500, 7200, 11700}, {5000, 8700, 14200}, {6400, 10700, 17200},
			{7000, 13200, 22000}};

	private TravelEncounters() {
	}

	/**
	 * Returns a suggestion map, or null when the ground is safe or the dice say nothing happened.
	 * Four-hour blocks of travel each roll once: 1 on a d6 on wild ground, 1 on a d12 on roads and
	 * rivers; settlements and buildings never.
	 */
	static Map<String, Object> suggest(
		Tx tx, RulesData rules, RollService roller, long campaignId, List<Row> movers, List<Row> path, Row destination,
		long minutes) {
		if (rules == null || roller == null || minutes < 240) {
			return null;
		}
		List<String> tags = routeTags(tx, path, destination);
		// Only ground that is tagged as wild or as a road rolls at all: streets, districts, quays and untagged nodes
		// are the GM's to describe, not the dice's. Anything the SAFE set names never rolls, whatever else is tagged.
		List<String> wild = tags.stream().filter(t -> !SAFE.contains(t) && (ROAD.contains(t) || isTerrain(t))).toList();
		if (wild.isEmpty()) {
			return null;
		}
		boolean road = wild.stream().allMatch(ROAD::contains);
		String table = terrain(wild);
		long blocks = Math.max(1, minutes / 240);
		String die = road ? "1d12" : "1d6";
		Roll hit = null;
		long block = 0;
		for (long b = 1; b <= blocks; b++) {
			Roll r = roller.roll(die);
			if (r.total() == 1) {
				hit = r;
				block = b;
				break;
			}
		}
		if (hit == null) {
			return null;
		}
		List<RulesData.Definition> pool = new ArrayList<>();
		for (String slug : TABLES.getOrDefault(table, TABLES.get("wilderness"))) {
			rules.resolve("CREATURE", "srd5e:creature/" + slug).ifPresent(pool::add);
		}
		if (pool.isEmpty()) {
			for (String slug : TABLES.get("wilderness")) {
				rules.resolve("CREATURE", "srd5e:creature/" + slug).ifPresent(pool::add);
			}
		}
		if (pool.isEmpty()) {
			return null;
		}
		Roll pick = roller.roll("1d" + pool.size());
		RulesData.Definition creature = pool.get((int) Math.max(0, Math.min(pool.size() - 1, pick.total() - 1)));
		int xp = creature.payload().get("xp_value") instanceof Number n ? Math.max(1, n.intValue()) : 25;
		int budget = 0;
		for (Row mover : movers) {
			int level = Math.max(1, Math.min(20, Origins.characterLevel(tx, mover)));
			budget += XP_BUDGET[level - 1][1];
		}
		int count = (int) Math.max(1, Math.min(8, budget / xp));
		var hooks = new ArrayList<String>();
		for (Row seed : tx.query(
				"SELECT * FROM director_seed WHERE campaign_id = ? AND state = 'OPEN' AND kind = 'PRESSURE' ORDER BY id DESC LIMIT 2",
				campaignId)) {
			Map<String, Object> payload = seed.isNull("payload_json") ? Map.of() : seed.map("payload_json");
			Object summary = payload.get("summary");
			if (summary == null) {
				summary = payload.get("title");
			}
			if (summary != null) {
				hooks.add(Ref.of("seed", seed.id()) + ": " + summary);
			}
		}
		var m = new LinkedHashMap<String, Object>();
		m.put("kind", "TRAVEL_ENCOUNTER_SUGGESTED");
		m.put("terrain", table);
		m.put("ground", tags);
		m.put("at_hour", block * 4);
		m.put("roll", Map.of("expression", die, "total", hit.total(), "blocks", blocks));
		m.put("creature", Map.of("id", creature.id(), "name", creature.name(), "cr",
				String.valueOf(creature.payload().getOrDefault("cr", "?")), "xp_value", xp));
		m.put("count", count);
		m.put("xp_total", count * xp);
		m.put("difficulty", "MODERATE");
		m.put("xp_budget", budget);
		m.put("hooks", hooks);
		m.put("note", "A suggestion only, from the ground crossed and the party's level: start_encounter to play it "
				+ "(materialize_character for the creatures), reshape it to an open pressure, or ignore it. Nothing "
				+ "has been written.");
		return m;
	}

	/**
	 * Lower-cased tags of every location along the route plus the destination, in route order,
	 * without duplicates.
	 */
	private static List<String> routeTags(Tx tx, List<Row> path, Row destination) {
		var ids = new LinkedHashSet<Long>();
		for (Row edge : path) {
			ids.add(edge.lng("location_a_id"));
			ids.add(edge.lng("location_b_id"));
		}
		ids.add(destination.id());
		var tags = new LinkedHashSet<String>();
		for (Long id : ids) {
			tx.find("location", id).ifPresent(l -> {
				if (!l.isNull("tags_json")) {
					for (Object t : l.list("tags_json")) {
						tags.add(String.valueOf(t).trim().toLowerCase(Locale.ROOT));
					}
				}
				String kind = l.str("kind");
				if (kind != null) {
					tags.add(kind.trim().toLowerCase(Locale.ROOT));
				}
			});
		}
		for (Row edge : path) {
			String kind = edge.str("kind");
			if (kind != null) {
				tags.add(kind.trim().toLowerCase(Locale.ROOT));
			}
		}
		return new ArrayList<>(tags);
	}

	private static boolean isTerrain(String tag) {
		for (var entry : TERRAIN) {
			if (entry.getKey().contains(tag)) {
				return true;
			}
		}
		return false;
	}

	private static String terrain(List<String> tags) {
		for (var entry : TERRAIN) {
			for (String t : tags) {
				if (entry.getKey().contains(t)) {
					return entry.getValue();
				}
			}
		}
		return "wilderness";
	}
}
