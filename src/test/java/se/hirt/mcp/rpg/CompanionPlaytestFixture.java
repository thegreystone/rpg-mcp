/* Copyright (C) 2026 Marcus Hirt. SPDX-License-Identifier: BSD-3-Clause */
package se.hirt.mcp.rpg;

import se.hirt.mcp.rpg.protocol.Json;
import se.hirt.mcp.rpg.protocol.Ref;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static se.hirt.mcp.rpg.TestCampaigns.*;

/** Creates a fresh scratch campaign; subsequent adaptive play uses native public MCP tools only. */
public final class CompanionPlaytestFixture {
	private CompanionPlaytestFixture() {
	}

	public static void main(String[] args) throws Exception {
		Path directory = Path.of(args.length == 0 ? "target/manual-playtests/companion-control" : args[0])
				.toAbsolutePath();
		Files.createDirectories(directory);
		Path database = directory.resolve("rpg.db");
		if (Files.exists(database))
			throw new IllegalStateException("Refusing to replace an existing play session: " + database);
		try (Engine engine = new Engine(database, null, "adaptive-playtest")) {
			String campaign = committedCampaign(engine);
			engine.sessions().bootstrap(op(), campaign, null);
			long cid = Ref.id(campaign, Ref.CAMPAIGN);
			String hero = engine.db()
					.read(tx -> "character:" + tx.queryOne(
							"SELECT character_id FROM player_control_assignment WHERE campaign_id=? AND active=1", cid)
							.orElseThrow().lng("character_id"));
			String opening = engine.db().read(tx -> "location:" + tx.get("campaign", cid).lng("current_location_id"));
			Map<String, Object> proposal = map("name", "The Copperbridge Tollhouse", "location_ref", opening,
					"room_count", 3, "levels", 1, "geometry", map("bounds_ft", List.of(0, 0, 90, 40)), "rooms",
					List.of(map("key", "entry", "name", "Tollhouse vestibule", "level", 0, "rect_ft",
							List.of(0, 0, 30, 40), "material", "STONE"),
							map("key", "office", "name", "Bandit counting room", "level", 0, "rect_ft",
									List.of(30, 0, 30, 40), "material", "WOOD"),
							map("key", "vault", "name", "Locked toll vault", "level", 0, "rect_ft",
									List.of(60, 0, 30, 40), "material", "STONE")),
					"connections",
					List.of(map("key", "narrow-door", "from", "entry", "to", "office", "name",
							"Five-foot office doorway", "state", "OPEN", "from_ft", List.of(30, 15), "to_ft",
							List.of(30, 20)),
							map("key", "vault-door", "from", "office", "to", "vault", "name", "Iron vault door",
									"state", "LOCKED", "from_ft", List.of(60, 15), "to_ft", List.of(60, 20))),
					"objects",
					List.of(map("key", "counting-table", "room", "office", "name", "Heavy counting table", "x_ft", 40,
							"y_ft", 20, "width_ft", 10, "height_ft", 5, "blocks_movement", true, "cover", "HALF",
							"hit_points", 12),
							map("key", "entry-crates", "room", "entry", "name", "Stacked tax crates", "x_ft", 20,
									"y_ft", 25, "width_ft", 5, "height_ft", 5, "blocks_movement", true, "cover",
									"HALF"),
							map("key", "chest", "room", "vault", "name", "Ironbound toll chest", "item_ref", "Chest",
									"x_ft", 80, "y_ft", 25, "locked", true)),
					"rewards",
					List.of(map("key", "staff", "room", "vault", "name", "The carved tollkeeper's staff", "item_ref",
							"Quarterstaff", "container", "chest", "x_ft", 80, "y_ft", 25)),
					"actors",
					List.of(map("key", "mira", "room", "entry", "name", "Mira", "creature_ref", "Scout", "x_ft", 10,
							"y_ft", 10),
							map("key", "thrain", "room", "entry", "name", "Thrain", "creature_ref", "Guard", "x_ft", 20,
									"y_ft", 15),
							map("key", "rusk", "room", "office", "name", "Rusk the toll collector", "creature_ref",
									"Bandit", "x_ft", 40, "y_ft", 15),
							map("key", "lenna", "room", "office", "name", "Lenna the bookkeeper", "creature_ref",
									"Bandit", "x_ft", 50, "y_ft", 25),
							map("key", "vault-guard", "room", "vault", "name", "Hadrik the vault guard", "creature_ref",
									"Bandit", "x_ft", 70, "y_ft", 15)));
			Map<String, Object> draft = engine.maps().begin(op(), campaign, "SCENE", proposal);
			if (!Boolean.TRUE.equals(draft.get("ready")))
				throw new IllegalStateException("Playtest fixture failed validation: " + Json.write(draft));
			Map<String, Object> published = engine.maps().publish(op(), campaign, (String) draft.get("preparation"),
					null);
			Map<String, Object> refs = cast(published.get("references"));
			String mapRef = (String) published.get("map_ref");
			String mira = (String) refs.get("actor:mira"), thrain = (String) refs.get("actor:thrain");
			engine.party().updateMembership(op(), campaign, mira, "JOIN",
					"Mira guides the expedition through the old tollhouse.");
			engine.party().updateMembership(op(), campaign, thrain, "JOIN",
					"Thrain accompanies the player as a trusted guard.");
			engine.scenes().place(op(), campaign, mapRef, List.of(map("character", hero, "x_ft", 15, "y_ft", 15)));
			engine.scenes().observe(op(), campaign, mapRef);
			new se.hirt.mcp.rpg.world.PatrolService(engine.db()).define(op(), campaign,
					map("name", "Hadrik's finite vault watch", "actors", List.of(refs.get("actor:vault-guard")),
							"route",
							List.of(map("location_ref", refs.get("room:vault"), "map_ref", mapRef, "x_ft", 70, "y_ft",
									15, "travel_seconds", 120),
									map("location_ref", refs.get("room:vault"), "map_ref", mapRef, "x_ft", 75, "y_ft",
											15, "travel_seconds", 120))));
			var fixture = new LinkedHashMap<String, Object>();
			fixture.put("campaign", campaign);
			fixture.put("map", mapRef);
			fixture.put("hero", hero);
			fixture.put("mira", mira);
			fixture.put("thrain", thrain);
			fixture.put("rusk", refs.get("actor:rusk"));
			fixture.put("lenna", refs.get("actor:lenna"));
			fixture.put("vault_guard", refs.get("actor:vault-guard"));
			fixture.put("opening", opening);
			fixture.put("references", refs);
			fixture.put("preparation", draft.get("preparation"));
			fixture.put("database", database.toString());
			fixture.put("setup",
					"Random character generation and real seeded NPC statistics; no scripted dice. Play starts after this fixture completes.");
			Files.writeString(directory.resolve("fixture.json"), Json.write(fixture));
			System.out.println(Json.write(fixture));
		}
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> cast(Object value) {
		return (Map<String, Object>) value;
	}
}
