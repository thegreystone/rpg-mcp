package se.hirt.mcp.rpg;

import org.junit.jupiter.api.Test;
import se.hirt.mcp.rpg.dice.ScriptedRollService;
import se.hirt.mcp.rpg.maps.SceneService;
import se.hirt.mcp.rpg.persistence.Row;
import se.hirt.mcp.rpg.protocol.Json;
import se.hirt.mcp.rpg.protocol.Ref;
import se.hirt.mcp.rpg.protocol.RpgException;
import se.hirt.mcp.rpg.world.TravelJourneyService;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static se.hirt.mcp.rpg.TestCampaigns.*;

class Run10SpatialTest {

	@Test
	void placingOnlyAnNpcDoesNotResynchronizeAnExistingPlayerFigureOrSplitAnUnplacedCompanion() throws Exception {
		var file = tempDb("run10-npc-placement-scope");
		String campaign, home, ally, board, checkpoint;
		Map<String, Object> playerFigure;
		try (Engine e = engine(file)) {
			campaign = ready(e);
			home = location(e, "character:1");
			ally = member(e, campaign, "Oddny", home, "JOIN");
			var published = tavern(e, campaign, home);
			board = published.get("map_ref").toString();
			e.scenes().place(op(), campaign, board, List.of(position("character:1", 5, 5, -1)));
			// A same-place audited focus/older save may retain the enclosing semantic place.
			e.rest().override(op(), campaign, "SET_LOCATION", "character:1", map("location", home),
					"Keep the established figure while focusing on its enclosing inn.", null);
			playerFigure = actor(e, campaign, "character:1").asMap();
			var playerBefore = character(e, "character:1").asMap();
			String clerk = npc(e, campaign, "Pell", home);
			checkpoint = e.checkpoints().create(op(), campaign, "Before placing the unrelated clerk").get("checkpoint")
					.toString();
			String placementOperation = op();
			var placements = List.of(position(clerk, 35, 35, -1));
			e.scenes().place(placementOperation, campaign, board, placements);
			assertEquals(playerBefore, character(e, "character:1").asMap());
			assertEquals(playerFigure, actor(e, campaign, "character:1").asMap());
			assertPartyAt(e, campaign, home, "character:1", ally);
			var afterPlacement = e.db().snapshot(Ref.id(campaign, Ref.CAMPAIGN));
			assertEquals(true, e.scenes().place(placementOperation, campaign, board, placements).get("replayed"));
			assertEquals(afterPlacement, e.db().snapshot(Ref.id(campaign, Ref.CAMPAIGN)));
			e.checkpoints().restore(op(), campaign, checkpoint, "Repeat only the clerk's independent placement.");
			e.scenes().place(op(), campaign, board, placements);
			assertEquals(playerBefore, character(e, "character:1").asMap());
			assertPartyAt(e, campaign, home, "character:1", ally);
		}
		try (Engine e = engine(file)) {
			assertEquals(playerFigure, actor(e, campaign, "character:1").asMap());
			assertPartyAt(e, campaign, home, "character:1", ally);
			assertEquals(-1, actor(e, campaign, "character:1").intOr("level", 0));
		}
	}

	@Test
	void actualDeploymentSynchronizesOnlySelectedParticipantsAndPreservesSavedRoomsLevelsAndCells() throws Exception {
		var file = tempDb("run10-deployment-location-sync");
		String campaign, ally, board, lower, upper, clerk, checkpoint;
		Map<String, Object> savedPlayerFigure, savedUpperFigure;
		try (Engine e = engine(file)) {
			campaign = ready(e);
			String home = location(e, "character:1");
			ally = member(e, campaign, "Oddny", home, "JOIN");
			var published = tavern(e, campaign, home);
			board = published.get("map_ref").toString();
			lower = SceneService.map(published.get("references")).get("room:taproom").toString();
			upper = SceneService.map(published.get("references")).get("room:upper").toString();
			clerk = npc(e, campaign, "Pell", home);
			String bystander = npc(e, campaign, "Unselected upstairs patron", home);
			e.scenes().place(op(), campaign, board, List.of(position("character:1", 5, 5, -1),
					position(clerk, 35, 35, -1), position(bystander, 10, 10, 0)));
			savedPlayerFigure = actor(e, campaign, "character:1").asMap();
			savedUpperFigure = actor(e, campaign, bystander).asMap();
			assertEquals(upper, location(e, bystander));
			var before = start(e, campaign, List.of("character:1", ally), List.of(clerk), null,
					map("map_ref", board, "level", -1));
			assertPartyAt(e, campaign, lower, "character:1", ally);
			assertEquals(lower, location(e, clerk));
			assertEquals(upper, location(e, bystander));
			assertEquals(savedPlayerFigure, actor(e, campaign, "character:1").asMap());
			assertEquals(savedUpperFigure, actor(e, campaign, bystander).asMap());
			assertEquals(-1, actor(e, campaign, ally).intOr("level", 0));
			var deployed = actor(e, campaign, ally).asMap();
			end(e, campaign, before);
			checkpoint = e.checkpoints().create(op(), campaign, "After the synchronized deployment").get("checkpoint")
					.toString();
			var repeat = start(e, campaign, List.of("character:1", ally), List.of(clerk), null, Map.of());
			assertPartyAt(e, campaign, lower, "character:1", ally);
			assertEquals(deployed, actor(e, campaign, ally).asMap());
			assertEquals(savedPlayerFigure, actor(e, campaign, "character:1").asMap());
			end(e, campaign, repeat);
			e.checkpoints().restore(op(), campaign, checkpoint,
					"Restore the exact deployed positions and canonical rooms.");
			assertPartyAt(e, campaign, lower, "character:1", ally);
			assertEquals(deployed, actor(e, campaign, ally).asMap());
		}
		try (Engine e = engine(file)) {
			assertPartyAt(e, campaign, lower, "character:1", ally);
			var repeat = start(e, campaign, List.of("character:1", ally), List.of(clerk), null, Map.of());
			assertEquals(board, SceneService.map(repeat.get("grid")).get("map_ref"));
			assertEquals(savedPlayerFigure, actor(e, campaign, "character:1").asMap());
		}
	}

	@Test
	void guestsOwnContactIgnoresPlayerDestinationAndExplicitLocationCannotRelocateOrRevealTheHomeParty()
			throws Exception {
		var file = tempDb("run10-guest-contact-at-player-destination");
		String campaign, home, mine, guest, contact, foe, privateBoard, playerRoom, checkpoint;
		Map<String, Object> playerFigure;
		try (Engine e = engine(file)) {
			campaign = ready(e);
			home = location(e, "character:1");
			guest = member(e, campaign, "Pell the guest", home, "GUEST_ADD");
			mine = mine(e, campaign, home);
			var journeys = journeys(e);
			journeys.defineRisk(op(), campaign, "DEFAULT", map("frequency_seconds", 3600, "chance_percent", 0));
			e.world().move(op(), campaign, mine, List.of("character:1"), false, null,
					"The player reaches the Mine first.");
			var published = tavern(e, campaign, mine);
			privateBoard = published.get("map_ref").toString();
			playerRoom = SceneService.map(published.get("references")).get("room:upper").toString();
			e.scenes().place(op(), campaign, privateBoard, List.of(position("character:1", 10, 15, 0)));
			playerFigure = actor(e, campaign, "character:1").asMap();
			journeys.defineRisk(op(), campaign, "DEFAULT", map("frequency_seconds", 3600, "chance_percent", 0,
					"authored_at_fraction", .5, "story", "Pell is challenged halfway to the Mine."));
			var departure = e.world().move(op(), campaign, mine, List.of(guest), false, null,
					"The guest travels from the Inn.");
			contact = departure.get("contact_location").toString();
			assertNotEquals(playerRoom, contact);
			assertEquals(home, location(e, guest));
			foe = npc(e, campaign, "Mott at the guest's contact", contact);
			checkpoint = e.checkpoints().create(op(), campaign, "The guest waits at the contact").get("checkpoint")
					.toString();
			((ScriptedRollService) e.roller()).queue(20, 1);
			var started = start(e, campaign, List.of(guest), List.of(foe), null,
					map("placements", List.of(position(guest, 50, 50, 0), position(foe, 80, 80, 0))));
			assertContact(e, campaign, started, contact, privateBoard, guest, playerRoom, playerFigure);
			e.encounters().perform(op(), campaign, started.get("encounter").toString(), guest,
					map("kind", "MOVE", "x_ft", 55, "y_ft", 50), false);
			assertEquals(List.of(55.0, 50.0),
					List.of(actor(e, campaign, guest).get("x_ft"), actor(e, campaign, guest).get("y_ft")));
			assertEquals(home, location(e, guest),
					"A road figure keeps its canonical departure as saved travel provenance.");
			assertEquals(home, journeys.state(campaign).get("from"));
			assertEquals(1500L, journeys.state(campaign).get("progress_seconds"));
			end(e, campaign, started);
			var beforeReads = e.db().snapshot(Ref.id(campaign, Ref.CAMPAIGN));
			long locations = e.db().read(tx -> tx.count("SELECT COUNT(*) FROM location WHERE campaign_id=?",
					Ref.id(campaign, Ref.CAMPAIGN)));
			assertEquals(contact, journeys.state(campaign).get("contact_location"));
			e.sessions().party(campaign, "SUMMARY");
			e.encounters().encounterState(campaign, started.get("encounter").toString(), 100, "PLAYER");
			assertEquals(locations, e.db().read(
					tx -> tx.count("SELECT COUNT(*) FROM location WHERE campaign_id=?", Ref.id(campaign, Ref.CAMPAIGN)))
					.longValue());
			assertEquals(beforeReads, e.db().snapshot(Ref.id(campaign, Ref.CAMPAIGN)),
					"Contact reads cannot create another derived place.");
		}
		try (Engine e = engine(file)) {
			assertEquals(playerFigure, actor(e, campaign, "character:1").asMap());
			assertEquals(playerRoom, location(e, "character:1"));
			String operation = op();
			var explicit = e.encounters().start(operation, campaign, map("party", List.of(guest), "foes", List.of(foe)),
					null, null, null, null, contact);
			assertContact(e, campaign, explicit, contact, privateBoard, guest, playerRoom, playerFigure);
			var beforeReplay = e.db().snapshot(Ref.id(campaign, Ref.CAMPAIGN));
			assertEquals(true,
					e.encounters().start(operation, campaign, map("party", List.of(guest), "foes", List.of(foe)), null,
							null, null, null, contact).get("replayed"));
			assertThrows(RpgException.class, () -> e.encounters().start(operation, campaign,
					map("party", List.of(guest), "foes", List.of(foe)), null, null, null, null, playerRoom));
			assertEquals(beforeReplay, e.db().snapshot(Ref.id(campaign, Ref.CAMPAIGN)));
			end(e, campaign, explicit);
			var beforeRefusal = e.db().snapshot(Ref.id(campaign, Ref.CAMPAIGN));
			RpgException wrongPlace = assertThrows(RpgException.class,
					() -> start(e, campaign, List.of(guest), List.of(foe), playerRoom, Map.of()));
			assertTrue(wrongPlace.getMessage().contains("contact_location"));
			assertFalse(wrongPlace.getMessage().contains("PRIVATE_UPPER_ROOM"));
			assertThrows(RpgException.class, () -> start(e, campaign, List.of(guest), List.of(foe), mine, Map.of()));
			assertEquals(beforeRefusal, e.db().snapshot(Ref.id(campaign, Ref.CAMPAIGN)));
			var departureAlias = start(e, campaign, List.of(guest), List.of(foe), home, Map.of());
			assertContact(e, campaign, departureAlias, contact, privateBoard, guest, playerRoom, playerFigure);
			assertEquals(List.of(55.0, 50.0),
					List.of(actor(e, campaign, guest).get("x_ft"), actor(e, campaign, guest).get("y_ft")));
			end(e, campaign, departureAlias);
			e.checkpoints().restore(op(), campaign, checkpoint, "Return to the original guest-only contact.");
			assertEquals(playerFigure, actor(e, campaign, "character:1").asMap());
			assertEquals(playerRoom, location(e, "character:1"));
			var afterRestore = start(e, campaign, List.of(guest), List.of(foe), null, Map.of());
			assertContact(e, campaign, afterRestore, contact, privateBoard, guest, playerRoom, playerFigure);
		}
	}

	@Test
	void guestReusingAPriorJourneysContactKeepsCurrentBindingAndActualBodyProvenance() throws Exception {
		try (Engine e = engine(tempDb("run10-reused-contact-current-journey"))) {
			String campaign = ready(e), home = location(e, "character:1"), mine = mine(e, campaign, home);
			long cid = Ref.id(campaign, Ref.CAMPAIGN);
			var journeys = journeys(e);
			journeys.defineRisk(op(), campaign, "DEFAULT", map("frequency_seconds", 3600, "chance_percent", 0,
					"authored_at_fraction", .5, "story", "A watcher at the fixed road midpoint."));
			var firstTrip = e.world().move(op(), campaign, mine, List.of("character:1"), false, null,
					"The player travels first.");
			String contact = firstTrip.get("contact_location").toString();
			String firstFoe = npc(e, campaign, "First road watcher", contact);
			var firstFight = start(e, campaign, List.of("character:1"), List.of(firstFoe), null, Map.of());
			String board = SceneService.map(firstFight.get("grid")).get("map_ref").toString();
			Map<String, Object> original = e.db()
					.read(tx -> SceneService.document(tx, SceneService.scene(tx, cid, board)));
			var oldFigure = actor(e, campaign, firstFoe).asMap();
			var rememberedCells = e.db()
					.read(tx -> SceneService
							.map(tx.queryOne("SELECT * FROM map_observation WHERE scene_id=? AND observer_key='party'",
									Ref.id(board, Ref.MAP)).orElseThrow().map("knowledge_json").get("cells"))
							.keySet());
			end(e, campaign, firstFight);
			journeys.resolveOpportunity(op(), campaign, "RESOLVED");
			e.world().move(op(), campaign, mine, List.of("character:1"), false, null, "Finish the first journey.");
			String guest = member(e, campaign, "Second-journey guest", home, "GUEST_ADD");
			var secondTrip = e.world().move(op(), campaign, mine, List.of(guest), false, null,
					"The guest follows later.");
			assertEquals(contact, secondTrip.get("contact_location"));
			assertNotEquals(firstTrip.get("journey"), secondTrip.get("journey"));
			String secondFoe = npc(e, campaign, "Second road watcher", contact);
			var secondFight = start(e, campaign, List.of(guest), List.of(secondFoe), null, Map.of());
			assertEquals(board, SceneService.map(secondFight.get("grid")).get("map_ref"));
			Map<String, Object> rebound = e.db()
					.read(tx -> SceneService.document(tx, SceneService.scene(tx, cid, board)));
			assertEquals(secondTrip.get("journey"), rebound.get("journey_ref"));
			assertEquals(secondTrip.get("anchor"), rebound.get("route_anchor"));
			for (String key : List.of("bounds_ft", "rooms", "walls", "doors", "portals", "objects", "features", "roads",
					"rivers", "illumination", "world_origin_ft", "route_path_ft", "route_world_path_ft",
					"journey_scene_key", "journey_entry_origin_ft", "journey_entry_recorded"))
				assertEquals(original.get(key), rebound.get(key), "Rebinding the current journey preserves " + key);
			assertEquals(oldFigure, actor(e, campaign, firstFoe).asMap());
			assertEquals(mine, location(e, "character:1"));
			assertEquals(1500L, journeys.state(campaign).get("progress_seconds"));
			boolean retainsExploredCells = e.db()
					.read(tx -> SceneService
							.map(tx.queryOne("SELECT * FROM map_observation WHERE scene_id=? AND observer_key='party'",
									Ref.id(board, Ref.MAP)).orElseThrow().map("knowledge_json").get("cells"))
							.keySet().containsAll(rememberedCells));
			assertTrue(retainsExploredCells);
			var body = actor(e, campaign, guest).asMap();
			e.runtime().applyRuntimeChange(op(), campaign, guest, map("kind", "DAMAGE", "amount", 1000, "reason",
					"A resolved lethal collapse kills the actual guest at this reused contact."));
			var ended = e.encounters().end(op(), campaign, secondFight.get("encounter").toString(), "OTHER",
					"The guest falls at the road contact.");
			var travel = SceneService.map(ended.get("travel_roster"));
			var removed = SceneService.maps(travel.get("removed_travelers")).stream()
					.filter(row -> guest.equals(row.get("character"))).findFirst().orElseThrow();
			assertEquals("CANCELLED", travel.get("status"));
			assertEquals("DEAD", removed.get("reason"));
			assertEquals(board, removed.get("map_ref"));
			assertEquals(List.of(body.get("x_ft"), body.get("y_ft")), removed.get("move_origin_ft"));
			assertEquals(((Number) body.get("level")).intValue(), ((Number) removed.get("level")).intValue());
			assertEquals(contact, removed.get("left_at"));
			assertEquals(body, actor(e, campaign, guest).asMap());
			assertEquals(contact, location(e, guest));
			assertEquals(mine, location(e, "character:1"));
		}
	}

	private static void assertContact(
		Engine e, String campaign, Map<String, Object> started, String contact, String privateBoard, String guest,
		String playerRoom, Map<String, Object> playerFigure) {
		String encounter = started.get("encounter").toString();
		assertEquals(contact, e.db().read(
				tx -> Ref.of(Ref.LOCATION, tx.get("encounter", Ref.id(encounter, Ref.ENCOUNTER)).lng("location_id"))));
		assertNotEquals(privateBoard, SceneService.map(started.get("grid")).get("map_ref"));
		assertEquals(playerFigure, actor(e, campaign, "character:1").asMap());
		assertEquals(playerRoom, location(e, "character:1"));
		var player = e.encounters().encounterState(campaign, encounter, 100, "PLAYER");
		assertTrue(
				SceneService.maps(player.get("participants")).stream().anyMatch(p -> guest.equals(p.get("character"))));
		assertTrue(SceneService.maps(player.get("participants")).stream()
				.noneMatch(p -> "character:1".equals(p.get("character"))));
		assertFalse(Json.write(player).contains("PRIVATE_UPPER_ROOM"));
		assertFalse(Json.write(player).contains("PRIVATE_UPPER_CACHE"));
	}

	private static Map<String, Object> tavern(Engine e, String campaign, String home) {
		var draft = e.maps().begin(op(), campaign, "SCENE",
				map("name", "Two-level inn", "location_ref", home, "room_count", 2, "levels", List.of(-1, 0),
						"illumination", "BRIGHT", "geometry", map("bounds_ft", List.of(0, 0, 40, 40)), "rooms",
						List.of(map("key", "taproom", "name", "Taproom", "role", "ENTRY", "level", -1, "rect_ft",
								List.of(0, 0, 40, 40)),
								map("key", "upper", "name", "PRIVATE_UPPER_ROOM", "level", 0, "rect_ft",
										List.of(0, 0, 40, 40))),
						"connections",
						List.of(map("key", "inn-stair", "from", "taproom", "to", "upper", "kind", "STAIRS", "state",
								"OPEN", "from_position_ft", List.of(20, 20), "to_position_ft", List.of(20, 20))),
						"objects", List.of(map("key", "upper-secret", "room", "upper", "name", "PRIVATE_UPPER_CACHE",
								"secret", true, "x_ft", 30, "y_ft", 30))));
		return e.maps().publish(op(), campaign, draft.get("preparation").toString(), null);
	}

	private static void assertPartyAt(Engine e, String campaign, String place, String ... members) {
		var party = e.sessions().party(campaign, "SUMMARY");
		assertEquals(place, SceneService.map(party.get("location")).get("ref"));
		for (String ref : members) {
			var member = SceneService.maps(party.get("members")).stream().filter(m -> ref.equals(m.get("ref")))
					.findFirst().orElseThrow();
			assertEquals(place, member.get("location"));
			assertEquals(true, member.get("with_party"));
		}
	}

	private static Map<String, Object> start(
		Engine e, String campaign, List<String> party, List<String> foes, String location,
		Map<String, Object> options) {
		return e.encounters().start(op(), campaign, map("party", party, "foes", foes), null, null, null, null, location,
				options);
	}

	private static void end(Engine e, String campaign, Map<String, Object> started) {
		e.encounters().end(op(), campaign, started.get("encounter").toString(), "OTHER",
				"The participants stand down.");
	}

	private static String ready(Engine e) {
		String campaign = committedCampaign(e);
		e.sessions().bootstrap(op(), campaign, null);
		return campaign;
	}

	private static Map<String, Object> position(String character, int x, int y, int level) {
		return map("character", character, "x_ft", x, "y_ft", y, "level", level);
	}

	private static String member(Engine e, String campaign, String name, String home, String change) {
		String character = npc(e, campaign, name, home);
		e.party().updateMembership(op(), campaign, character, change, "A legitimate traveling companion.");
		return character;
	}

	private static String npc(Engine e, String campaign, String name, String location) {
		return e.runtime().materialize(op(), campaign, "Guard", name, null, null, null, location, false)
				.get("character").toString();
	}

	private static Row actor(Engine e, String campaign, String character) {
		return e.db().read(tx -> SceneService.actor(tx, Ref.id(campaign, Ref.CAMPAIGN), character));
	}

	private static Row character(Engine e, String character) {
		return e.db().read(tx -> tx.get("character", Ref.id(character, Ref.CHARACTER)));
	}

	private static String location(Engine e, String character) {
		return Ref.of(Ref.LOCATION, character(e, character).lng("location_id"));
	}

	private static TravelJourneyService journeys(Engine e) {
		return new TravelJourneyService(e.db(), e.rules(), e.roller());
	}

	private static String mine(Engine e, String campaign, String home) {
		return e.world()
				.materialize(op(), campaign, null,
						map("name", "Mine", "kind", "SITE", "connections",
								List.of(map("to", home, "kind", "ROAD", "travel_minutes", 50))),
						"GM")
				.get("ref").toString();
	}
}
