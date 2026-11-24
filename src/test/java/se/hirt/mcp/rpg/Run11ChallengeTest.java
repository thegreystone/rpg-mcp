/* Copyright (C) 2026 Marcus Hirt. SPDX-License-Identifier: BSD-3-Clause */
package se.hirt.mcp.rpg;

import org.junit.jupiter.api.Test;
import se.hirt.mcp.rpg.dice.ScriptedRollService;
import se.hirt.mcp.rpg.maps.SceneService;
import se.hirt.mcp.rpg.persistence.Database;
import se.hirt.mcp.rpg.protocol.Json;
import se.hirt.mcp.rpg.protocol.Ref;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static se.hirt.mcp.rpg.TestCampaigns.*;

class Run11ChallengeTest {

	@Test
	void apparentTrapsOmitOnlyAutomaticDiscoveryAndKeepTheirDisarmGuidance() throws Exception {
		try (Engine e = engine(tempDb("run11-apparent-trap-defaults"))) {
			String campaign = ready(e);
			var proposal = proposal(e, campaign, List.of(
					trap("known", map("known_hazard", true, "challenges", map("disarm", map("difficulty", 13)))),
					trap("revealed", map("concealed", false)),
					trap("authored", map("known_hazard", true, "challenges", map("discover", map("difficulty", 15)))),
					trap("hidden", Map.of()), trap("concealed-known", map("known_hazard", true, "concealed", true)),
					trap("secret-known", map("known_hazard", true, "secret", true)),
					trap("gm-known", map("known_hazard", true, "visibility", "GM")),
					trap("gm-only-known", map("known_hazard", true, "visibility", "GM_ONLY")),
					trap("director-known", map("concealed", false, "visibility", "DIRECTOR_ONLY"))));
			var draft = e.maps().begin(op(), campaign, "DUNGEON", proposal);
			var prepared = SceneService.map(draft.get("proposal"));
			assertFalse(checks(part(prepared, "known")).containsKey("discover"));
			assertEquals(13, dc(part(prepared, "known"), "disarm"));
			assertFalse(checks(part(prepared, "revealed")).containsKey("discover"));
			assertEquals(15, dc(part(prepared, "revealed"), "disarm"));
			assertEquals(15, dc(part(prepared, "authored"), "discover"));
			for (String key : List.of("hidden", "concealed-known", "secret-known", "gm-known", "gm-only-known",
					"director-known"))
				assertEquals(15, dc(part(prepared, key), "discover"), key);
		}
	}

	@Test
	void draftRevisionsBetweenConcealedAndApparentRegenerateOnlyMissingDefaultDiscovery() throws Exception {
		try (Engine e = engine(tempDb("run11-trap-discovery-revisions"))) {
			String campaign = ready(e);
			var draft = e.maps().begin(op(), campaign, "DUNGEON", proposal(e, campaign,
					List.of(trap("hazard", map("challenges", map("disarm", map("difficulty", 18)))))));
			String prep = draft.get("preparation").toString();
			assertEquals(15, dc(part(SceneService.map(draft.get("proposal")), "hazard"), "discover"));
			for (var flags : List.of(map("known_hazard", true), map("known_hazard", false), map("concealed", false),
					map("concealed", true), map("concealed", false))) {
				var patch = map("key", "hazard");
				patch.putAll(flags);
				var revised = e.maps().revise(op(), campaign, prep, null, map("objects", List.of(patch)));
				var hazard = part(SceneService.map(revised.get("proposal")), "hazard");
				boolean apparent = Boolean.TRUE.equals(hazard.get("known_hazard"))
						|| Boolean.FALSE.equals(hazard.get("concealed"));
				assertEquals(!apparent, checks(hazard).containsKey("discover"), flags.toString());
				if (!apparent)
					assertEquals(15, dc(hazard, "discover"));
				assertEquals(18, dc(hazard, "disarm"));
			}
		}
	}

	@Test
	void apparentRevisionPreservesExplicitDefaultFifteenAndUnmarkedLegacyIntent() throws Exception {
		for (String mode : List.of("GENERATED", "AUTHORED", "LEGACY"))
			try (Engine e = engine(tempDb("run11-trap-provenance-" + mode))) {
				String campaign = ready(e);
				var draft = e.maps().begin(op(), campaign, "DUNGEON",
						proposal(e, campaign, List.of(trap("hazard", Map.of()))));
				String prep = draft.get("preparation").toString();
				if (mode.equals("LEGACY"))
					e.db().mutate(Database.Mutation.of("run11_legacy_guidance_fixture", Ref.id(campaign, Ref.CAMPAIGN),
							op(), "GM", Map.of()), tx -> {
								var row = tx.get("map_preparation", Ref.id(prep, "preparation"));
								var value = row.map("payload_json");
								part(value, "hazard").remove("generated_challenges");
								tx.update("map_preparation", row.id(), map("payload_json", Json.write(value)));
								return Map.of("ok", true);
							});
				var hazardPatch = map("key", "hazard", "known_hazard", true);
				if (mode.equals("AUTHORED"))
					hazardPatch.put("challenges", map("discover", map("difficulty", 15)));
				var revised = e.maps().revise(op(), campaign, prep, null, map("objects", List.of(hazardPatch)));
				var hazard = part(SceneService.map(revised.get("proposal")), "hazard");
				assertEquals(!mode.equals("GENERATED"), checks(hazard).containsKey("discover"), mode);
				if (!mode.equals("GENERATED"))
					assertEquals(15, dc(hazard, "discover"));
				assertEquals(15, dc(hazard, "disarm"));
			}
	}

	@Test
	void publicationAndReadOnlyRestartKeepLiveChecksAndPlayerHazardKnowledgeSeparate() throws Exception {
		var file = tempDb("run11-published-hazard-guidance");
		String campaign, board;
		try (Engine e = engine(file)) {
			campaign = ready(e);
			var draft = e.maps().begin(op(), campaign, "DUNGEON", proposal(
					e, campaign, List.of(
							trap("known", map("name", "Loose flagstones", "known_hazard", true, "challenges",
									map("disarm", map("difficulty", 13)))),
							trap("authored",
									map("name", "Apparent rune", "concealed", false, "challenges",
											map("discover", map("difficulty", 15), "disarm", map("difficulty", 22)))),
							trap("hidden", map("name", "Hidden needle", "challenges",
									map("discover", map("difficulty", 12), "disarm", map("difficulty", 19)))))));
			var published = e.maps().publish(op(), campaign, draft.get("preparation").toString(), null);
			assertNotNull(published.get("map_ref"), published.toString());
			board = published.get("map_ref").toString();
			e.scenes().place(op(), campaign, board, List.of(map("character", "character:1", "x_ft", 5, "y_ft", 5)));
			e.scenes().observe(op(), campaign, board);
		}
		try (Engine e = engine(file)) {
			var before = e.db().snapshot(Ref.id(campaign, Ref.CAMPAIGN));
			((ScriptedRollService) e.roller()).queue(4);
			var gm = SceneService.map(e.scenes().context(campaign, board, "GM", 0).get("view"));
			assertFalse(checks(part(gm, "object:known")).containsKey("discover"));
			assertEquals(13, dc(part(gm, "object:known"), "disarm"));
			assertEquals(15, dc(part(gm, "object:authored"), "discover"));
			assertEquals(22, dc(part(gm, "object:authored"), "disarm"));
			String player = Json.write(e.scenes().context(campaign, board, "PLAYER", 0));
			assertTrue(player.contains("Loose flagstones"));
			assertTrue(player.contains("Apparent rune"));
			assertFalse(player.contains("Hidden needle"));
			for (String privateField : List.of("challenges", "generated_challenges", "difficulty", "Thieves' Tools"))
				assertFalse(player.contains(privateField), privateField);
			assertEquals(before, e.db().snapshot(Ref.id(campaign, Ref.CAMPAIGN)));
			assertEquals(4, e.roller().roll("1d6").total(), "Guidance cannot roll or discover anything.");
			e.scenes().changeObject(op(), campaign, board, "object:hidden", map("concealed", false),
					"The DM explicitly reveals the needle after adjudication.");
			gm = SceneService.map(e.scenes().context(campaign, board, "GM", 0).get("view"));
			assertEquals(12, dc(part(gm, "object:hidden"), "discover"),
					"A live reveal cannot reset or remove an authored discovery check.");
			assertEquals(19, dc(part(gm, "object:hidden"), "disarm"));
			assertTrue(Json.write(e.scenes().context(campaign, board, "PLAYER", 0)).contains("Hidden needle"));
		}
	}

	private static Map<String, Object> trap(String key, Map<String, Object> values) {
		var result = map("key", key, "room", "entry", "kind", "TRAP", "name", key, "x_ft", 10, "y_ft", 5, "width_ft", 5,
				"height_ft", 5);
		result.putAll(values);
		return result;
	}

	private static Map<String, Object> proposal(Engine e, String campaign, List<Map<String, Object>> objects) {
		String home = e.db().read(tx -> Ref.of(Ref.LOCATION,
				tx.get("campaign", Ref.id(campaign, Ref.CAMPAIGN)).lng("current_location_id")));
		return map("name", "Apparent hazard guidance", "stage", "SCENE", "location_ref", home, "illumination", "BRIGHT",
				"geometry", map("bounds_ft", List.of(0, 0, 40, 20)), "rooms",
				List.of(map("key", "entry", "rect_ft", List.of(0, 0, 40, 20))), "objects", objects);
	}

	private static Map<String, Object> part(Map<String, Object> proposal, String key) {
		return SceneService.maps(proposal.get("objects")).stream().filter(p -> key.equals(p.get("key"))).findFirst()
				.orElseThrow(() -> new AssertionError("Missing " + key + " in " + proposal));
	}

	private static Map<String, Object> checks(Map<String, Object> part) {
		return SceneService.map(part.get("challenges"));
	}

	private static int dc(Map<String, Object> part, String challenge) {
		return ((Number) SceneService.map(checks(part).get(challenge)).get("difficulty")).intValue();
	}

	private static String ready(Engine e) {
		String campaign = committedCampaign(e);
		e.sessions().bootstrap(op(), campaign, null);
		return campaign;
	}
}
