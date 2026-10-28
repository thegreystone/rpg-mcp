package se.hirt.mcp.rpg;

import org.junit.jupiter.api.Test;
import se.hirt.mcp.rpg.maps.SceneService;
import se.hirt.mcp.rpg.persistence.Row;
import se.hirt.mcp.rpg.protocol.Ref;
import se.hirt.mcp.rpg.protocol.RpgException;
import se.hirt.mcp.rpg.spatial.SpatialRules;
import se.hirt.mcp.rpg.world.TravelJourneyService;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static se.hirt.mcp.rpg.TestCampaigns.*;

class Run6DeploymentTest {

	@Test
	void parentPlaceDeploymentUsesTheAuthoredEntryRoleEvenWhenTheLockedVaultIsListedFirst() throws Exception {
		try (Engine e = engine(tempDb("run6-entry-room-role"))) {
			String campaign = ready(e);
			String home = e.db().read(tx -> Ref.of(Ref.LOCATION,
					tx.get("campaign", Ref.id(campaign, Ref.CAMPAIGN)).lng("current_location_id")));
			var draft = e.maps().begin(op(), campaign, "SCENE",
					map("name", "Entry and vault", "location_ref", home, "room_count", 2, "illumination", "BRIGHT",
							"geometry", map("bounds_ft", List.of(0, 0, 40, 20)), "rooms",
							List.of(map("key", "vault", "rect_ft", List.of(0, 0, 20, 20)),
									map("key", "entry", "role", "ENTRY", "rect_ft", List.of(20, 0, 20, 20))),
							"connections",
							List.of(map("key", "locked-door", "from", "vault", "to", "entry", "kind", "DOOR", "state",
									"LOCKED", "from_ft", List.of(20, 5), "to_ft", List.of(20, 10))),
							"actors", List.of(map("key", "guard", "room", "entry", "creature_ref", "Bandit", "name",
									"Entry guard", "x_ft", 25, "y_ft", 5))));
			var published = e.maps().publish(op(), campaign, draft.get("preparation").toString(), null);
			String guard = SceneService.map(published.get("references")).get("actor:guard").toString();
			var started = start(e, campaign, List.of("character:1"), List.of(guard));
			assertTrue(SceneService.footprint(actor(e, campaign, "character:1")).bounds().minX() >= 20 * 12);
			var report = SceneService.maps(SceneService.map(started.get("grid")).get("party_deployment")).getFirst();
			assertEquals("entry", SceneService.maps(report.get("rooms")).getFirst().get("key"));
		}
	}

	@Test
	void innPartyNeverDefaultsIntoTheTownStreetOrClosedBackRoomAndLaterSynchronizationKeepsThemTogether()
			throws Exception {
		try (Engine e = engine(tempDb("run6-town-street-deployment"))) {
			String campaign = ready(e);
			Tavern tavern = tavern(e, campaign, false);
			String ally = companion(e, campaign, tavern.inn());
			var authored = tavern.foes().stream().map(ref -> actor(e, campaign, ref).asMap()).toList();
			var started = start(e, campaign, List.of("character:1", ally), tavern.foes());
			assertEquals(tavern.map(), board(started));
			for (String member : List.of("character:1", ally)) {
				var figure = SceneService.footprint(actor(e, campaign, member));
				assertTrue(figure.bounds().minY() >= 10 * 12, "The Town street is outside the Inn's canonical place.");
				assertTrue(figure.bounds().maxX() <= 40 * 12,
						"A closed passage does not make the back room an entry cell.");
			}
			assertEquals(authored, tavern.foes().stream().map(ref -> actor(e, campaign, ref).asMap()).toList(),
					"Authored Street and Back-room NPCs remain on their own saved cells.");
			List<Map<String, Object>> report = SceneService
					.maps(SceneService.map(started.get("grid")).get("party_deployment"));
			assertEquals(2, report.size());
			assertTrue(report.stream().allMatch(member -> SceneService.maps(member.get("rooms")).stream().anyMatch(
					room -> "Taproom".equals(room.get("name")) && tavern.taproom().equals(room.get("location_ref")))));
			end(e, campaign, started);
			var samePositions = List.of(position(e, campaign, "character:1"), position(e, campaign, ally));
			e.scenes().place(op(), campaign, tavern.map(), samePositions);
			assertEquals(tavern.taproom(), location(e, "character:1"));
			assertEquals(tavern.taproom(), location(e, ally));
			String mine = roadToMine(e, campaign, tavern.inn());
			var moved = e.world().move(op(), campaign, mine, null, false, null, "Leave the same taproom together.");
			assertTrue(((List<?>) moved.get("moved")).containsAll(List.of("character:1", ally)));
			assertEquals(mine, location(e, "character:1"));
			assertEquals(mine, location(e, ally));
		}
	}

	@Test
	void publishedRoomDeparturePlainContactFightAndReloadedResumeUseTheActualJourneyPlace() throws Exception {
		var file = tempDb("run6-room-road-contact");
		String campaign, mine, ally, room, encounter;
		try (Engine e = engine(file)) {
			campaign = ready(e);
			Tavern tavern = tavern(e, campaign, false);
			ally = companion(e, campaign, tavern.inn());
			e.scenes().place(op(), campaign, tavern.map(),
					List.of(map("character", "character:1", "x_ft", 5, "y_ft", 15),
							map("character", ally, "x_ft", 10, "y_ft", 15)));
			room = tavern.taproom();
			mine = roadToMine(e, campaign, tavern.inn());
			var journeys = new TravelJourneyService(e.db(), e.rules(), e.roller());
			journeys.defineRisk(op(), campaign, "DEFAULT", map("frequency_seconds", 3600, "chance_percent", 0,
					"authored_at_fraction", .5, "story", "Scouts wait halfway up the track."));
			var outward = e.world().move(op(), campaign, mine, null, false, null,
					"Follow the fifty-minute track from the room.");
			assertEquals(true, outward.get("interrupted"));
			assertTrue(((List<?>) journeys.state(campaign).get("movers")).containsAll(List.of("character:1", ally)));
			assertEquals(room, location(e, "character:1"),
					"The saved departure room is not a deployment constraint on the road.");
			String first = npc(e, campaign, "Scout one", null), second = npc(e, campaign, "Scout two", null);
			var started = start(e, campaign, List.of("character:1", ally), List.of(first, second));
			encounter = started.get("encounter").toString();
			assertNotEquals(tavern.map(), board(started));
			Map<String, Object> document = e.db().read(tx -> SceneService.document(tx,
					SceneService.scene(tx, Ref.id(campaign, Ref.CAMPAIGN), board(started))));
			assertTrue(document.containsKey("journey_ref"));
			for (String foe : List.of(first, second))
				for (String member : List.of("character:1", ally))
					assertTrue(SpatialRules.distance(SceneService.footprint(actor(e, campaign, foe)),
							SceneService.footprint(actor(e, campaign, member))) >= 30 * 12);
			end(e, campaign, started);
		}
		try (Engine e = engine(file)) {
			var journeys = new TravelJourneyService(e.db(), e.rules(), e.roller());
			assertEquals(room, location(e, "character:1"));
			assertEquals("ENDED",
					e.db().read(tx -> tx.get("encounter", Ref.id(encounter, Ref.ENCOUNTER)).str("status")));
			journeys.resolveOpportunity(op(), campaign, "CONTINUE");
			var arrival = e.world().move(op(), campaign, mine, null, false, null,
					"Complete the saved remaining journey.");
			assertEquals(25L, arrival.get("travel_minutes"));
			assertEquals(mine, location(e, "character:1"));
			assertEquals(mine, location(e, ally));
			assertEquals(false, journeys.state(campaign).get("active"));
		}
	}

	@Test
	void blockedEntryRefusesRatherThanUsingASiblingStreetOrDisconnectedRoom() throws Exception {
		try (Engine e = engine(tempDb("run6-no-legal-entry"))) {
			String campaign = ready(e);
			Tavern tavern = tavern(e, campaign, true);
			var before = e.db().snapshot(Ref.id(campaign, Ref.CAMPAIGN));
			RpgException error = assertThrows(RpgException.class,
					() -> start(e, campaign, List.of("character:1"), tavern.foes()));
			assertTrue(error.getMessage().contains("Richard Greystone"));
			assertTrue(error.getMessage().contains(tavern.map()));
			assertTrue(error.getMessage().contains("options.placements"));
			assertEquals(before, e.db().snapshot(Ref.id(campaign, Ref.CAMPAIGN)));
		}
	}

	private record Tavern(String inn, String map, String taproom, List<String> foes) {
	}

	private static Tavern tavern(Engine e, String campaign, boolean blocked) {
		String town = e.world().materialize(op(), campaign, null, map("name", "Harrowgate", "kind", "SETTLEMENT"), "GM")
				.get("ref").toString();
		String inn = e.world().materialize(op(), campaign, null,
				map("name", "The Broken Pick", "kind", "BUILDING", "parent", town), "GM").get("ref").toString();
		e.rest().override(op(), campaign, "SET_LOCATION", "character:1", map("location", inn),
				"The campaign opens inside the Inn.", null);
		var actors = new ArrayList<Map<String, Object>>();
		if (!blocked)
			actors.add(map("key", "clerk", "room", "taproom", "creature_ref", "Bandit", "name", "Clerk", "x_ft", 30,
					"y_ft", 25));
		actors.add(map("key", "storekeeper", "room", "back-room", "creature_ref", "Bandit", "name", "Storekeeper",
				"x_ft", 45, "y_ft", 25));
		actors.add(map("key", "street-guard", "room", "street", "creature_ref", "Bandit", "name", "Street guard",
				"x_ft", 45, "y_ft", 0));
		var proposal = map("name", "The Broken Pick prepared", "location_ref", inn, "room_count", 3, "illumination",
				"BRIGHT", "geometry", map("bounds_ft", List.of(0, 0, 50, 40)), "rooms", List
						.of(map("key", "back-room", "name", "Back room", "rect_ft", List.of(40, 10, 10, 30)),
								map("key", "street", "name", "Market street", "location_ref", town, "rect_ft",
										List.of(0, 0, 50, 10), "enclosed", false),
								map("key", "taproom", "name", "Taproom", "rect_ft", List.of(0, 10, 40, 30))),
				"connections",
				List.of(map("key", "street-door", "from", "taproom", "to", "street", "kind", "DOOR", "state", "CLOSED",
						"from_ft", List.of(15, 10), "to_ft", List.of(20, 10)),
						map("key", "back-door", "from", "taproom", "to", "back-room", "kind", "DOOR", "state", "CLOSED",
								"from_ft", List.of(40, 30), "to_ft", List.of(40, 35))),
				"actors", actors);
		if (blocked)
			proposal.put("objects", List.of(map("key", "collapsed-ceiling", "room", "taproom", "rect_ft",
					List.of(0, 10, 40, 30), "blocks_movement", true)));
		var draft = e.maps().begin(op(), campaign, "SCENE", proposal);
		var published = e.maps().publish(op(), campaign, draft.get("preparation").toString(), null);
		var refs = SceneService.map(published.get("references"));
		var foes = actors.stream().map(actor -> refs.get("actor:" + actor.get("key")).toString()).toList();
		return new Tavern(inn, published.get("map_ref").toString(), refs.get("room:taproom").toString(), foes);
	}

	private static String ready(Engine e) {
		String c = committedCampaign(e);
		e.sessions().bootstrap(op(), c, null);
		return c;
	}

	private static String companion(Engine e, String campaign, String inn) {
		String ally = npc(e, campaign, "Oddny", inn);
		e.party().updateMembership(op(), campaign, ally, "JOIN", "Travels with the player.");
		return ally;
	}

	private static String npc(Engine e, String campaign, String name, String location) {
		return e.runtime().materialize(op(), campaign, "Bandit", name, null, null, null, location, false)
				.get("character").toString();
	}

	private static String roadToMine(Engine e, String campaign, String inn) {
		return e.world()
				.materialize(op(), campaign, null,
						map("name", "Ashcombe Mine", "kind", "SITE", "connections",
								List.of(map("to", inn, "kind", "ROAD", "travel_minutes", 50))),
						"GM")
				.get("ref").toString();
	}

	private static Row actor(Engine e, String campaign, String actor) {
		return e.db().read(tx -> SceneService.actor(tx, Ref.id(campaign, Ref.CAMPAIGN), actor));
	}

	private static String location(Engine e, String actor) {
		return e.db()
				.read(tx -> Ref.of(Ref.LOCATION, tx.get("character", Ref.id(actor, Ref.CHARACTER)).lng("location_id")));
	}

	private static Map<String, Object> position(Engine e, String campaign, String actor) {
		Row p = actor(e, campaign, actor);
		return map("character", actor, "x_ft", p.get("x_ft"), "y_ft", p.get("y_ft"), "level", p.get("level"));
	}

	private static Map<String, Object> start(Engine e, String campaign, List<String> party, List<String> foes) {
		return e.encounters().start(op(), campaign, map("party", party, "foes", foes), null, null, null, null, null);
	}

	private static String board(Map<String, Object> start) {
		return SceneService.map(start.get("grid")).get("map_ref").toString();
	}

	private static void end(Engine e, String campaign, Map<String, Object> start) {
		e.encounters().end(op(), campaign, start.get("encounter").toString(), "OTHER",
				"The participants settle the scene.");
	}
}
