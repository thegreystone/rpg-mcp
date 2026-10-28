package se.hirt.mcp.rpg;

import io.quarkiverse.mcp.server.ToolResponse;
import org.junit.jupiter.api.Test;
import se.hirt.mcp.rpg.maps.SceneService;
import se.hirt.mcp.rpg.protocol.Json;
import se.hirt.mcp.rpg.protocol.Ref;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static se.hirt.mcp.rpg.TestCampaigns.*;

class MapChallengePrivacyTest {

	@Test
	void gmPublicationAndMapContextsKeepDoorObjectAndPortalChecksWhilePlayerSeesOnlyPhysicalFeatures()
			throws Exception {
		try (Engine e = engine(tempDb("map-challenge-projection"))) {
			String campaign = ready(e), board = publish(e, campaign);
			placeAndObserve(e, campaign, board);
			var authoring = SceneService.map(e.maps().get(campaign, board).get("document"));
			assertGmChallenges(authoring);
			MapTools tools = new MapTools();
			tools.engine = e;
			for (String detail : List.of("FULL", "SUMMARY")) {
				var gm = result(tools.context(campaign, board, Optional.of("GM"), Optional.of(0), Optional.of(detail)));
				assertGmChallenges(SceneService.map(gm.get("view")));
				var player = result(
						tools.context(campaign, board, Optional.of("PLAYER"), Optional.of(0), Optional.of(detail)));
				assertVisiblePlayerFeatures(SceneService.map(player.get("view")));
				assertPrivateDetailsAbsent(player);
			}
			var rendered = result(tools.render(campaign, board, Optional.of("PLAYER"), Optional.of(0), Optional.empty(),
					Optional.of(false)));
			assertVisiblePlayerFeatures(SceneService.map(rendered.get("view")));
			assertPrivateDetailsAbsent(rendered);
			assertTrue(rendered.get("text").toString().contains("Iron coffer"));
		}
	}

	@Test
	void canonicalPortalUpdatesAndPrivateChecksSurviveReloadWithoutEnteringObservedOrRememberedPlayerData()
			throws Exception {
		var file = tempDb("map-challenge-memory");
		String campaign, board;
		try (Engine e = engine(file)) {
			campaign = ready(e);
			board = publish(e, campaign);
			placeAndObserve(e, campaign, board);
			e.runtime().applyRuntimeChange(op(), campaign, "character:1", map("kind", "ADD_CONDITION", "condition",
					"BLINDED", "reason", "The observer can retain only prior map evidence."));
			e.scenes().observe(op(), campaign, board);
			var hatch = part(SceneService.map(e.scenes().context(campaign, board, "GM", 0).get("view")), "portals",
					"upper-hatch");
			e.rest().override(op(), campaign, "SET_CONNECTION_STATE", hatch.get("from_location_ref").toString(),
					map("to", hatch.get("to_location_ref"), "state", "OPEN"),
					"A resolved key opens the hatch while the observer cannot see it.", null);
			assertRememberedPlayerPrivacy(e, campaign, board);
			var gm = SceneService.map(e.scenes().context(campaign, board, "GM", 0).get("view"));
			assertGmChallenges(gm);
			assertEquals("OPEN", part(gm, "portals", "upper-hatch").get("state"),
					"GM portals hydrate the canonical passage state rather than the original map copy.");
		}
		try (Engine e = engine(file)) {
			assertRememberedPlayerPrivacy(e, campaign, board);
			var gm = SceneService.map(e.scenes().context(campaign, board, "GM", 0).get("view"));
			assertGmChallenges(gm);
			assertEquals("OPEN", part(gm, "portals", "upper-hatch").get("state"));
			MapTools tools = new MapTools();
			tools.engine = e;
			assertPrivateDetailsAbsent(result(
					tools.context(campaign, board, Optional.of("PLAYER"), Optional.of(0), Optional.of("SUMMARY"))));
			assertPrivateDetailsAbsent(result(tools.render(campaign, board, Optional.of("PLAYER"), Optional.of(0),
					Optional.empty(), Optional.of(false))));
		}
	}

	private static void assertRememberedPlayerPrivacy(Engine e, String campaign, String board) {
		var player = SceneService.map(e.scenes().context(campaign, board, "PLAYER", 0).get("view"));
		assertVisiblePlayerFeatures(player);
		assertEquals(true, part(player, "objects", "coffer").get("last_known"));
		assertPrivateDetailsAbsent(player);
		long sid = Ref.id(board, Ref.MAP);
		for (var memory : e.db().read(tx -> tx.query("SELECT * FROM map_observation WHERE scene_id=?", sid)))
			assertPrivateDetailsAbsent(memory.map("knowledge_json"));
	}

	private static void assertGmChallenges(Map<String, Object> view) {
		var door = SceneService.map(part(view, "doors", "iron-door").get("challenges"));
		assertEquals(17, ((Number) SceneService.map(door.get("pick_lock")).get("difficulty")).intValue());
		assertEquals(23, ((Number) SceneService.map(door.get("force_open")).get("difficulty")).intValue());
		var coffer = SceneService.map(part(view, "objects", "coffer").get("challenges"));
		var pick = SceneService.map(coffer.get("pick_lock"));
		assertEquals(15, ((Number) pick.get("difficulty")).intValue());
		assertEquals("DEX", pick.get("ability"));
		assertEquals("Sleight of Hand", pick.get("skill"));
		assertEquals("Thieves' Tools", pick.get("tool"));
		var portal = SceneService.map(part(view, "portals", "upper-hatch").get("challenges"));
		assertEquals(15, ((Number) SceneService.map(portal.get("pick_lock")).get("difficulty")).intValue());
		var plate = SceneService.map(part(view, "objects", "plate").get("challenges"));
		assertFalse(plate.containsKey("discover"), "An apparent hazard needs no automatic discovery guidance.");
		assertEquals(15, ((Number) SceneService.map(plate.get("disarm")).get("difficulty")).intValue());
	}

	private static void assertVisiblePlayerFeatures(Map<String, Object> view) {
		assertEquals("Iron coffer", part(view, "objects", "coffer").get("name"),
				"Private checks must not hide the visible object itself.");
		assertEquals("Copper floor plate", part(view, "objects", "plate").get("name"));
		assertEquals("LOCKED", part(view, "doors", "iron-door").get("state"));
		assertTrue(SceneService.maps(view.get("objects")).stream()
				.noneMatch(row -> "Hidden cache".equals(row.get("name"))));
	}

	private static void assertPrivateDetailsAbsent(Object value) {
		if (value instanceof Map<?, ?> map) {
			for (String key : Set.of("challenges", "difficulty", "dc", "pick_lock", "discover", "disarm", "force_open",
					"tool", "skill", "ability", "solution", "solutions"))
				assertFalse(map.containsKey(key), "GM-only check field leaked: " + key);
			map.values().forEach(MapChallengePrivacyTest::assertPrivateDetailsAbsent);
		} else if (value instanceof Collection<?> collection)
			collection.forEach(MapChallengePrivacyTest::assertPrivateDetailsAbsent);
		else if (value instanceof String text)
			for (String privateText : List.of("Thieves' Tools", "Sleight of Hand", "SKILL_CHECK", "ABILITY_CHECK",
					"PRIVATE_CACHE_SOLUTION"))
				assertFalse(text.contains(privateText), "GM-only check text leaked: " + privateText);
	}

	private static Map<String, Object> part(Map<String, Object> view, String layer, String key) {
		return SceneService.maps(view.get(layer)).stream()
				.filter(row -> key.equals(row.get("key")) || ("object:" + key).equals(row.get("key"))).findFirst()
				.orElseThrow(() -> new AssertionError("Missing " + layer + " " + key));
	}

	private static String publish(Engine e, String campaign) {
		String home = e.db().read(tx -> Ref.of(Ref.LOCATION,
				tx.get("campaign", Ref.id(campaign, Ref.CAMPAIGN)).lng("current_location_id")));
		var draft = e.maps()
				.begin(op(), campaign, "DUNGEON",
						map("name", "Challenge privacy mine", "stage", "SCENE", "location_ref", home, "room_count", 3,
								"levels", 2, "illumination", "BRIGHT", "geometry",
								map("bounds_ft", List.of(0, 0, 40, 20)), "rooms",
								List.of(map("key", "west", "level", 0, "rect_ft", List.of(0, 0, 20, 20)),
										map("key", "east", "level", 0, "rect_ft",
												List.of(20, 0, 20, 20)),
										map("key", "upper", "level", 1, "rect_ft", List.of(0, 0, 40, 20))),
								"connections", List.of(
										map("key", "iron-door", "name", "Iron door", "kind", "DOOR", "state", "LOCKED",
												"from", "west", "to", "east", "from_ft", List.of(20, 5), "to_ft",
												List.of(20, 10), "challenges",
												map("pick_lock", map("kind", "SKILL_CHECK", "ability", "DEX", "skill",
														"Sleight of Hand", "tool", "Thieves' Tools", "difficulty", 17),
														"force_open",
														map("kind", "ABILITY_CHECK", "ability", "STR", "difficulty",
																23))),
										map("key", "upper-hatch", "name", "Upper hatch", "kind", "STAIRS", "state",
												"LOCKED", "from", "west", "to", "upper", "from_position_ft",
												List.of(5, 10), "to_position_ft", List.of(5, 10))),
								"objects",
								List.of(map("key", "coffer", "room", "west", "name", "Iron coffer", "kind", "CHEST",
										"locked", true, "x_ft", 5, "y_ft", 5, "width_ft", 5, "height_ft", 5,
										"blocks_movement", true),
										map("key", "plate", "room", "west", "name", "Copper floor plate", "kind",
												"TRAP", "known_hazard", true, "x_ft", 10, "y_ft", 15, "width_ft", 5,
												"height_ft", 5),
										map("key", "cache", "room", "west", "name", "Hidden cache", "secret", true,
												"description", "PRIVATE_CACHE_SOLUTION", "x_ft", 0, "y_ft", 0))));
		return e.maps().publish(op(), campaign, draft.get("preparation").toString(), null).get("map_ref").toString();
	}

	private static void placeAndObserve(Engine e, String campaign, String board) {
		e.scenes().place(op(), campaign, board, List.of(map("character", "character:1", "x_ft", 10, "y_ft", 5)));
		e.scenes().observe(op(), campaign, board);
	}

	private static String ready(Engine e) {
		String campaign = committedCampaign(e);
		e.sessions().bootstrap(op(), campaign, null);
		return campaign;
	}

	private static Map<String, Object> result(ToolResponse response) {
		assertFalse(response.isError(), response.toString());
		return SceneService.map(Json.readMap(response.firstContent().asText().text()).get("result"));
	}
}
