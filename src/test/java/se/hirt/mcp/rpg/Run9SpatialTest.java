package se.hirt.mcp.rpg;

import org.junit.jupiter.api.Test;
import se.hirt.mcp.rpg.maps.GridCombat;
import se.hirt.mcp.rpg.maps.SceneService;
import se.hirt.mcp.rpg.protocol.Json;
import se.hirt.mcp.rpg.protocol.Ref;
import se.hirt.mcp.rpg.protocol.RpgException;
import se.hirt.mcp.rpg.world.TravelJourneyService;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static se.hirt.mcp.rpg.TestCampaigns.*;

class Run9SpatialTest {

	@Test
	void semanticArrivalAtLowerPublishedRoomGivesAnUnplacedPrivatePointerThatSurvivesReload() throws Exception {
		var file = tempDb("run9-unplaced-room-arrival");
		String campaign, board, room;
		Map<String, Object> savedHint;
		try (Engine e = engine(file)) {
			campaign = ready(e);
			String home = location(e, "character:1"), mine = mine(e, campaign, home);
			quiet(e, campaign);
			var dungeon = dungeon(e, campaign, mine);
			board = dungeon.get("map_ref").toString();
			room = SceneService.map(dungeon.get("references")).get("room:ossuary").toString();
			var arrived = e.world().move(op(), campaign, room, null, false, null,
					"Enter the known lower ossuary through its open stairs.");
			savedHint = SceneService.map(arrived.get("placement_hint"));
			assertUniqueHint(savedHint, board, room, -1);
			assertFalse(arrived.containsKey("current_board"), "Coverage is a separate hint, not an actual board.");
			assertEquals(Map.of("status", "NO_CURRENT_BOARD"),
					e.db().read(tx -> GridCombat.currentBoard(tx, Ref.id(campaign, Ref.CAMPAIGN))));
			assertEquals(room, location(e, "character:1"));
			assertEquals(0L, figures(e, campaign));
			long cid = Ref.id(campaign, Ref.CAMPAIGN);
			var before = e.db().snapshot(cid);
			assertEquals(savedHint, e.db().read(tx -> GridCombat.placementHint(tx, cid)));
			var player = SceneService.map(e.scenes().context(campaign, board, "PLAYER", -1).get("view"));
			assertTrue(SceneService.maps(player.get("rooms")).isEmpty());
			assertTrue(SceneService.maps(player.get("objects")).isEmpty());
			assertFalse(Json.write(player).contains("UNSEEN_SACRISTY"));
			assertFalse(Json.write(player).contains("PRIVATE_LOCK_SOLUTION"));
			assertEquals(before, e.db().snapshot(cid),
					"Reading pointers and PLAYER context cannot place or observe actors.");
			var boot = e.sessions().bootstrap(op(), campaign, null);
			var spatial = SceneService.map(boot.get("spatial"));
			assertEquals(savedHint, spatial.get("placement_hint"));
			assertEquals(Map.of("status", "NO_CURRENT_BOARD"), spatial.get("current_board"));
			assertEquals("LOCAL_MAP_AVAILABLE", spatial.get("status"));
			assertEquals(savedHint.get("coverage"), spatial.get("maps"));
			assertFalse(Json.write(spatial).contains("UNSEEN_SACRISTY"));
			assertFalse(Json.write(spatial).contains("PRIVATE_LOCK_SOLUTION"));
			assertFalse(Json.write(spatial).contains("rect_ft"));
			assertEquals(0L, figures(e, campaign));
		}
		try (Engine e = engine(file)) {
			var boot = e.sessions().bootstrap(op(), campaign, null);
			assertEquals(savedHint, SceneService.map(boot.get("spatial")).get("placement_hint"));
			assertEquals(room, location(e, "character:1"));
			assertEquals(0L, figures(e, campaign));
		}
	}

	@Test
	void multiplePublishedRepresentationsRemainAmbiguousUntilAnActualFigureIsPlaced() throws Exception {
		try (Engine e = engine(tempDb("run9-ambiguous-room-coverage"))) {
			String campaign = ready(e), home = location(e, "character:1"), mine = mine(e, campaign, home);
			quiet(e, campaign);
			var first = dungeon(e, campaign, mine);
			String room = SceneService.map(first.get("references")).get("room:ossuary").toString();
			var draft = e.maps().begin(op(), campaign, "SCENE",
					map("name", "PRIVATE_SECOND_BLUEPRINT", "location_ref", mine, "room_count", 1, "levels",
							List.of(-1), "geometry", map("bounds_ft", List.of(0, 0, 30, 30)), "rooms",
							List.of(map("key", "same-ossuary", "location_ref", room, "level", -1, "rect_ft",
									List.of(0, 0, 30, 30)))));
			String second = e.maps().publish(op(), campaign, draft.get("preparation").toString(), null).get("map_ref")
					.toString();
			var arrived = e.world().move(op(), campaign, room, null, false, null,
					"Arrive without choosing a representation.");
			var hint = SceneService.map(arrived.get("placement_hint"));
			assertEquals("UNPLACED", hint.get("status"));
			assertEquals("AMBIGUOUS", hint.get("coverage_status"));
			assertFalse(hint.containsKey("map_ref"));
			assertFalse(hint.containsKey("level"));
			assertEquals(Set.of(first.get("map_ref"), second), SceneService.maps(hint.get("coverage")).stream()
					.map(candidate -> candidate.get("map_ref")).collect(java.util.stream.Collectors.toSet()));
			assertTrue(SceneService.maps(hint.get("coverage")).stream()
					.allMatch(candidate -> ((Number) candidate.get("level")).intValue() == -1));
			assertFalse(Json.write(hint).contains("PRIVATE_SECOND_BLUEPRINT"));
			assertFalse(arrived.containsKey("current_board"), "Ambiguous coverage cannot choose an actual board.");
			assertEquals(Map.of("status", "NO_CURRENT_BOARD"),
					e.db().read(tx -> GridCombat.currentBoard(tx, Ref.id(campaign, Ref.CAMPAIGN))));
			assertEquals(0L, figures(e, campaign));
			e.scenes().place(op(), campaign, second,
					List.of(map("character", "character:1", "x_ft", 5, "y_ft", 5, "level", -1)));
			var spatial = SceneService.map(e.sessions().bootstrap(op(), campaign, null).get("spatial"));
			assertEquals(second, SceneService.map(spatial.get("current_board")).get("map_ref"));
			assertFalse(spatial.containsKey("placement_hint"));
		}
	}

	@Test
	void companionOnlyJourneyCannotHijackHomeCombatButStillSupportsTheCompanionsOwnContact() throws Exception {
		try (Engine e = engine(tempDb("run9-companion-journey-contact-scope"))) {
			String campaign = ready(e), home = location(e, "character:1"), mine = mine(e, campaign, home);
			String ally = npc(e, campaign, "Oddny", home);
			e.party().updateMembership(op(), campaign, ally, "JOIN", "A party companion travels ahead alone.");
			var journeys = journeys(e);
			journeys.defineRisk(op(), campaign, "DEFAULT", map("frequency_seconds", 3600, "chance_percent", 0,
					"authored_at_fraction", .5, "story", "Oddny meets a watcher halfway along the road."));
			var departure = e.world().move(op(), campaign, mine, List.of(ally), false, null, "Oddny scouts ahead.");
			assertEquals(true, departure.get("interrupted"));
			assertEquals(List.of(ally), journeys.state(campaign).get("movers"));
			String contactLocation = departure.get("contact_location").toString();
			assertNotEquals(home, contactLocation);
			assertEquals(contactLocation, journeys.state(campaign).get("contact_location"));
			String foe = npc(e, campaign, "A brawler at home", null);
			assertEquals(home, location(e, foe),
					"Unspecified NPC placement follows controlled focus, not another member's journey.");
			var before = e.db().snapshot(Ref.id(campaign, Ref.CAMPAIGN));
			RpgException split = assertThrows(RpgException.class,
					() -> start(e, campaign, List.of("character:1", ally), foe));
			assertTrue(split.getMessage().contains("Richard Greystone (character:1)"));
			assertTrue(split.getMessage().contains("saved journey"));
			assertEquals(before, e.db().snapshot(Ref.id(campaign, Ref.CAMPAIGN)));
			var boot = e.sessions().bootstrap(op(), campaign, null);
			assertEquals(home, SceneService.map(boot.get("location")).get("ref"));
			assertEquals("SEMANTIC_LOCATION", SceneService.map(boot.get("spatial")).get("status"));
			var atHome = start(e, campaign, List.of("character:1"), foe);
			String homeBoard = SceneService.map(atHome.get("grid")).get("map_ref").toString();
			assertEquals(home, e.db().read(tx -> Ref.of(Ref.LOCATION, tx
					.get("encounter", Ref.id(atHome.get("encounter").toString(), Ref.ENCOUNTER)).lng("location_id"))));
			assertFalse(document(e, campaign, homeBoard).containsKey("journey_ref"));
			e.encounters().end(op(), campaign, atHome.get("encounter").toString(), "OTHER",
					"The home brawler stands down.");
			var homeFigure = e.db()
					.read(tx -> SceneService.actor(tx, Ref.id(campaign, Ref.CAMPAIGN), "character:1").asMap());
			String watcher = npc(e, campaign, "A watcher on Oddny's road", contactLocation);
			var atContact = start(e, campaign, List.of(ally), watcher);
			String contactBoard = SceneService.map(atContact.get("grid")).get("map_ref").toString();
			assertNotEquals(homeBoard, contactBoard);
			assertEquals(departure.get("journey"), document(e, campaign, contactBoard).get("journey_ref"));
			assertEquals(homeFigure,
					e.db().read(tx -> SceneService.actor(tx, Ref.id(campaign, Ref.CAMPAIGN), "character:1").asMap()));
			var contactBoot = e.sessions().bootstrap(op(), campaign, null);
			assertEquals(homeBoard,
					SceneService.map(SceneService.map(contactBoot.get("spatial")).get("current_board")).get("map_ref"));
			assertEquals(1500L, journeys.state(campaign).get("progress_seconds"));
		}
	}

	private static Map<String, Object> dungeon(Engine e, String campaign, String site) {
		var draft = e.maps().begin(op(), campaign, "DUNGEON", map("name", "PRIVATE_OSSUARY_BLUEPRINT", "stage", "SCENE",
				"location_ref", site, "room_count", 3, "levels", List.of(0, -1), "illumination", "BRIGHT", "geometry",
				map("bounds_ft", List.of(0, 0, 40, 20)), "rooms",
				List.of(map("key", "entry", "name", "Mine entrance", "role", "ENTRY", "level", 0, "rect_ft",
						List.of(0, 0, 20, 20)),
						map("key", "ossuary", "name", "Known ossuary", "level", -1, "rect_ft", List.of(0, 0, 20, 20)),
						map("key", "sacristy", "name", "UNSEEN_SACRISTY", "level", -1, "rect_ft",
								List.of(20, 0, 20, 20))),
				"connections",
				List.of(map("key", "stairs", "kind", "STAIRS", "state", "OPEN", "from", "entry", "to", "ossuary",
						"from_position_ft", List.of(5, 5), "to_position_ft", List.of(5, 5)),
						map("key", "hidden-door", "kind", "DOOR", "state", "SECRET", "secret", true, "from", "ossuary",
								"to", "sacristy", "from_ft", List.of(20, 5), "to_ft", List.of(20, 10))),
				"objects", List.of(map("key", "secret-cache", "room", "sacristy", "name", "PRIVATE_LOCK_SOLUTION",
						"secret", true, "x_ft", 25, "y_ft", 5))));
		return e.maps().publish(op(), campaign, draft.get("preparation").toString(), null);
	}

	private static void assertUniqueHint(Map<String, Object> hint, String board, String room, int level) {
		assertEquals("UNPLACED", hint.get("status"));
		assertEquals("AVAILABLE", hint.get("coverage_status"));
		assertEquals(board, hint.get("map_ref"));
		assertEquals(room, hint.get("location_ref"));
		assertEquals(level, ((Number) hint.get("level")).intValue());
		assertEquals(true, hint.get("placement_required"));
		assertEquals("get_map_context", hint.get("next_operation"));
		assertEquals(List.of(map("map_ref", board, "kind", "DUNGEON", "level", level)), hint.get("coverage"));
		assertEquals(Set.of("status", "location_ref", "coverage_status", "coverage", "placement_required",
				"next_operation", "map_ref", "level"), hint.keySet());
	}

	private static Map<String, Object> start(Engine e, String campaign, List<String> party, String foe) {
		return e.encounters().start(op(), campaign, map("party", party, "foes", List.of(foe)), null, null, null, null,
				null);
	}

	private static Map<String, Object> document(Engine e, String campaign, String board) {
		return e.db()
				.read(tx -> SceneService.document(tx, SceneService.scene(tx, Ref.id(campaign, Ref.CAMPAIGN), board)));
	}

	private static String ready(Engine e) {
		String campaign = committedCampaign(e);
		e.sessions().bootstrap(op(), campaign, null);
		return campaign;
	}

	private static String npc(Engine e, String campaign, String name, String location) {
		return e.runtime().materialize(op(), campaign, "Guard", name, null, null, null, location, false)
				.get("character").toString();
	}

	private static String location(Engine e, String character) {
		return e.db().read(
				tx -> Ref.of(Ref.LOCATION, tx.get("character", Ref.id(character, Ref.CHARACTER)).lng("location_id")));
	}

	private static long figures(Engine e, String campaign) {
		return e.db().read(
				tx -> tx.count("SELECT COUNT(*) FROM map_actor WHERE campaign_id=?", Ref.id(campaign, Ref.CAMPAIGN)));
	}

	private static String mine(Engine e, String campaign, String home) {
		return e.world()
				.materialize(op(), campaign, null,
						map("name", "Old mine", "kind", "SITE", "connections",
								List.of(map("to", home, "kind", "ROAD", "travel_minutes", 50))),
						"GM")
				.get("ref").toString();
	}

	private static TravelJourneyService journeys(Engine e) {
		return new TravelJourneyService(e.db(), e.rules(), e.roller());
	}

	private static void quiet(Engine e, String campaign) {
		journeys(e).defineRisk(op(), campaign, "DEFAULT", map("frequency_seconds", 3600, "chance_percent", 0));
	}
}
