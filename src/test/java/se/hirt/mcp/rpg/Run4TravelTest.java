package se.hirt.mcp.rpg;

import org.junit.jupiter.api.Test;
import se.hirt.mcp.rpg.maps.SceneService;
import se.hirt.mcp.rpg.protocol.Ref;
import se.hirt.mcp.rpg.protocol.RpgException;
import se.hirt.mcp.rpg.session.GameTime;
import se.hirt.mcp.rpg.world.TravelJourneyService;
import se.hirt.mcp.rpg.world.WorldService;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static se.hirt.mcp.rpg.TestCampaigns.*;

class Run4TravelTest {

	@Test
	void publishedRoomDepartureAndReturnReuseTheFiftyMinuteRoadAndItsRisk() throws Exception {
		try (Engine e = engine(tempDb("run4-room-route"))) {
			String c = committedCampaign(e);
			e.sessions().bootstrap(op(), c, null);
			long cid = Ref.id(c, "campaign");
			String home = e.db().read(tx -> "location:" + tx.get("campaign", cid).lng("current_location_id"));
			String mine = e.world().materialize(op(), c, null,
					map("name", "Ashcombe Mine", "kind", "SITE", "connections", List.of(
							map("to", home, "kind", "ROAD", "travel_minutes", 50, "distance_miles", 16000d / 5280))),
					"GM").get("ref").toString();
			String road = e.db().read(tx -> "connection:" + tx
					.queryOne("SELECT id FROM location_connection WHERE campaign_id=? ORDER BY id DESC LIMIT 1", cid)
					.orElseThrow().id());
			var region = e.maps().begin(op(), c, "REGION",
					map("name", "The mine road", "geometry", map("bounds_ft", List.of(0, 0, 20000, 20000)), "rooms",
							List.of(map("key", "home", "location_ref", home, "point_ft", List.of(1000, 1000)),
									map("key", "mine", "location_ref", mine, "point_ft", List.of(9000, 9000))),
							"connections",
							List.of(map("key", "track", "from", "home", "to", "mine", "connection_ref", road)),
							"features", List.of(map("key", "track", "kind", "ROAD", "graph_ref", "track", "path_ft",
									List.of(List.of(1000, 1000), List.of(9000, 1000), List.of(9000, 9000))))));
			e.maps().publish(op(), c, region.get("preparation").toString(), null);
			Prepared tavern = prepareRooms(e, c, home, "The Broken Pick"),
					workings = prepareRooms(e, c, mine, "Mine rooms");
			String companion = e.runtime().materialize(op(), c, "Guard", "Ansel", null, null, null, home, false)
					.get("character").toString();
			e.party().updateMembership(op(), c, companion, "JOIN", "Ansel accompanies the party.");
			e.scenes().place(op(), c, tavern.map(), List.of(map("character", "character:1", "x_ft", 5, "y_ft", 5),
					map("character", companion, "x_ft", 25, "y_ft", 5)));
			assertEquals(tavern.first(), current(e, cid));
			assertEquals(home, e.db().read(
					tx -> "location:" + WorldService.travelAnchor(tx, cid, Ref.id(tavern.first(), "location")).id()));
			TravelJourneyService journeys = new TravelJourneyService(e.db(), e.roller());
			journeys.defineRisk(op(), c, "DEFAULT", map("frequency_seconds", 3600, "chance_percent", 0));
			journeys.defineRisk(op(), c, road, map("frequency_seconds", 3600, "chance_percent", 0,
					"authored_at_fraction", .5, "story", "Track-specific contact", "terrain", "hills"));
			long connectionCount = e.db()
					.read(tx -> tx.count("SELECT COUNT(*) FROM location_connection WHERE campaign_id=?", cid));
			long before = e.db().read(tx -> GameTime.currentSeconds(tx, cid));
			var outbound = e.world().move(op(), c, mine, null, false, null, "Follow the existing mine track.");
			assertRoadContact(outbound, home, mine, road, companion);
			assertEquals(List.of(9000d, 1000d),
					e.db().read(tx -> TravelJourneyService.encounterSceneDocument(tx, cid, null))
							.get("journey_world_anchor_ft"));
			String scout = e.runtime()
					.materialize(op(), c, "Goblin Warrior", "Track scout", null, null, null, null, false)
					.get("character").toString();
			long scoutLocation = e.db().read(tx -> tx.get("character", Ref.id(scout, "character")).lng("location_id"));
			assertNotEquals(Ref.id(tavern.first(), "location"), scoutLocation);
			assertNotEquals(Ref.id(home, "location"), scoutLocation);
			assertTrue(e.db().read(tx -> tx.get("location", scoutLocation).str("name"))
					.startsWith("Travel contact toward"));
			journeys.resolveOpportunity(op(), c, "CONTINUE");
			assertEquals(25L, e.world().move(op(), c, mine, null, false, null, "Finish the remaining road.")
					.get("travel_minutes"));
			assertEquals(3000L, e.db().read(tx -> GameTime.currentSeconds(tx, cid)) - before);
			assertEquals(mine, current(e, cid));
			e.scenes().place(op(), c, workings.map(), List.of(map("character", "character:1", "x_ft", 5, "y_ft", 5),
					map("character", companion, "x_ft", 25, "y_ft", 5)));
			var inbound = e.world().move(op(), c, home, null, true, null,
					"Return from the mapped room; use the existing road when it is already known.");
			assertRoadContact(inbound, mine, home, road, companion);
			journeys.resolveOpportunity(op(), c, "CONTINUE");
			e.world().move(op(), c, home, null, false, null, "Finish returning.");
			assertEquals(6000L, e.db().read(tx -> GameTime.currentSeconds(tx, cid)) - before);
			assertEquals(home, current(e, cid));
			assertEquals(connectionCount,
					e.db().read(tx -> tx.count("SELECT COUNT(*) FROM location_connection WHERE campaign_id=?", cid))
							.longValue());
			assertEquals(0L,
					e.db().read(tx -> tx.count(
							"SELECT COUNT(*) FROM location_connection WHERE campaign_id=? AND kind='OTHER'", cid))
							.longValue());
		}
	}

	@Test
	void leavingAMappedRoomForItsParentIsFreeButOtherContainmentDoesNotInventTravel() throws Exception {
		try (Engine e = engine(tempDb("run4-parent-hop"))) {
			String c = committedCampaign(e);
			e.sessions().bootstrap(op(), c, null);
			long cid = Ref.id(c, "campaign");
			String home = current(e, cid);
			Prepared scene = prepareRooms(e, c, home, "Rooms");
			e.scenes().place(op(), c, scene.map(), List.of(map("character", "character:1", "x_ft", 5, "y_ft", 5)));
			long before = e.db().read(tx -> GameTime.currentSeconds(tx, cid));
			long connections = e.db()
					.read(tx -> tx.count("SELECT COUNT(*) FROM location_connection WHERE campaign_id=?", cid));
			var left = e.world().move(op(), c, home, null, false, null, "Leave the room for its parent place.");
			assertEquals(0L, left.get("travel_minutes"));
			assertEquals(0L, left.get("elapsed_seconds"));
			assertEquals(List.of(), left.get("route"));
			assertEquals(scene.first(), current(e, cid), "A parent focus preserves the canonical physical room.");
			assertEquals(before, e.db().read(tx -> GameTime.currentSeconds(tx, cid)).longValue());
			assertEquals(1L,
					e.db().read(tx -> tx.count("SELECT COUNT(*) FROM map_actor WHERE campaign_id=?", cid)).longValue());
			assertEquals(0L, e.db().read(tx -> tx.count("SELECT COUNT(*) FROM travel_journey WHERE campaign_id=?", cid))
					.longValue());
			assertEquals(connections,
					e.db().read(tx -> tx.count("SELECT COUNT(*) FROM location_connection WHERE campaign_id=?", cid))
							.longValue());
			for (String kind : List.of("SITE", "BUILDING", "AREA")) {
				String child = e
						.world().materialize(op(), c, null,
								map("name", "Unconnected " + kind, "kind", kind, "parent", home), "GM")
						.get("ref").toString();
				assertThrows(RpgException.class, () -> e.world().move(op(), c, child, null, false, null, null));
			}
		}
	}

	@Test
	void roomFallbackAndAuthorizedFlagDoNotReplaceALockedAuthoredRoad() throws Exception {
		try (Engine e = engine(tempDb("run4-locked-road"))) {
			String c = committedCampaign(e);
			e.sessions().bootstrap(op(), c, null);
			long cid = Ref.id(c, "campaign");
			String home = current(e, cid);
			String mine = e.world().materialize(op(), c, null,
					map("name", "Locked Mine", "kind", "SITE", "connections",
							List.of(map("to", home, "kind", "ROAD", "state", "LOCKED", "travel_minutes", 50))),
					"GM").get("ref").toString();
			Prepared scene = prepareRooms(e, c, home, "Rooms");
			e.scenes().place(op(), c, scene.map(), List.of(map("character", "character:1", "x_ft", 5, "y_ft", 5)));
			var before = e.db().snapshot(cid);
			assertThrows(RpgException.class, () -> e.world().move(op(), c, mine, null, false, null, null));
			assertThrows(RpgException.class,
					() -> e.world().move(op(), c, mine, null, true, 1, "This flag must not rewrite the locked road."));
			assertEquals(before, e.db().snapshot(cid));
			// A real room-to-parent passage is also authoritative, including a closed/locked state.
			e.world().materialize(op(), c, scene.first(), map("extend", true, "connections",
					List.of(map("to", home, "kind", "DOOR", "state", "LOCKED", "travel_minutes", 1))), "GM");
			assertEquals(scene.first(), e.db().read(
					tx -> "location:" + WorldService.travelAnchor(tx, cid, Ref.id(scene.first(), "location")).id()));
			assertThrows(RpgException.class, () -> e.world().move(op(), c, home, null, false, null, null));
		}
	}

	@Test
	void newlyPublishedDungeonStoresDarknessByDefaultAndPreservesAuthoredLight() throws Exception {
		try (Engine e = engine(tempDb("run4-dungeon-light"))) {
			String c = committedCampaign(e);
			e.sessions().bootstrap(op(), c, null);
			for (String light : List.of("DEFAULT", "DIM", "BRIGHT")) {
				var proposal = map("name", "Dungeon " + light, "stage", "SCENE", "room_count", 1, "geometry",
						map("bounds_ft", List.of(0, 0, 20, 20)), "rooms",
						List.of(map("key", "room", "rect_ft", List.of(0, 0, 20, 20))));
				if (!light.equals("DEFAULT"))
					proposal.put("illumination", light);
				var prep = e.maps().begin(op(), c, "DUNGEON", proposal);
				if (light.equals("DEFAULT"))
					assertTrue(prep.get("optional_gaps").toString().contains("defaults to DARK"));
				var published = e.maps().publish(op(), c, prep.get("preparation").toString(), null);
				var document = object(object(published.get("map")).get("document"));
				assertEquals(light.equals("DEFAULT") ? "DARK" : light, document.get("illumination"));
			}
		}
	}

	@Test
	void liveRegionalClimateUsesTheCalendarSeasonAndKeepsAuthoredSeasonAsProvenance() throws Exception {
		try (Engine e = engine(tempDb("run4-climate-calendar"))) {
			String c = committedCampaign(e);
			e.sessions().bootstrap(op(), c, null);
			var prep = e.maps().begin(op(), c, "REGION",
					map("name", "Seasonal hills", "geometry", map("bounds_ft", List.of(0, 0, 1000, 1000)), "climate",
							map("baseline", "TEMPERATE", "season", "AUTUMN", "moisture", "WET")));
			var published = e.maps().publish(op(), c, prep.get("preparation").toString(), null);
			String mapRef = published.get("map_ref").toString(),
					atlas = object(published.get("references")).get("atlas").toString();
			var climate = object(object(published.get("map")).get("document"));
			climate = object(climate.get("climate"));
			assertEquals("SPRING", climate.get("season"));
			assertEquals("AUTUMN", climate.get("authored_season"));
			assertEquals("WET", climate.get("moisture"));
			e.cashFlows().setCalendar(op(), c, 1, 9, 1, true);
			assertEquals("AUTUMN", object(e.maps().get(c, atlas).get("climate")).get("season"));
			var context = object(e.scenes().context(c, mapRef, "GM", 0).get("view"));
			assertEquals("AUTUMN", object(context.get("climate")).get("season"));
			assertEquals("CAMPAIGN_CALENDAR", object(context.get("climate")).get("season_source"));
		}
	}

	private static void assertRoadContact(
		Map<String, Object> result, String from, String to, String road, String companion) {
		assertEquals(true, result.get("interrupted"));
		assertEquals(3000L, result.get("total_seconds"));
		assertEquals(1500L, result.get("progress_seconds"));
		assertEquals(from, result.get("from"));
		assertEquals(to, result.get("destination"));
		var legs = SceneService.maps(result.get("route"));
		assertEquals(1, legs.size());
		assertEquals(road, legs.getFirst().get("connection_ref"));
		assertEquals(from, legs.getFirst().get("from"));
		assertEquals(to, legs.getFirst().get("to"));
		assertEquals("ROAD", legs.getFirst().get("via"));
		assertEquals("Track-specific contact", object(result.get("opportunity")).get("story"));
		assertTrue(((List<?>) result.get("moved")).contains(companion),
				"Companion in the site's other room travels too.");
	}

	private record Prepared(String map, String first, String second) {
	}

	private static Prepared prepareRooms(Engine e, String campaign, String owner, String name) {
		var prep = e.maps().begin(op(), campaign, "SCENE", map("name", name, "location_ref", owner, "room_count", 2,
				"geometry", map("bounds_ft", List.of(0, 0, 40, 20)), "rooms",
				List.of(map("key", "a", "name", "First room", "rect_ft", List.of(0, 0, 20, 20)),
						map("key", "b", "name", "Second room", "rect_ft", List.of(20, 0, 20, 20))),
				"connections", List.of(map("key", "door", "from", "a", "to", "b", "kind", "DOOR", "state", "OPEN"))));
		var published = e.maps().publish(op(), campaign, prep.get("preparation").toString(), null);
		var refs = object(published.get("references"));
		return new Prepared(published.get("map_ref").toString(), refs.get("room:a").toString(),
				refs.get("room:b").toString());
	}

	private static String current(Engine e, long cid) {
		return e.db().read(tx -> "location:" + tx.get("campaign", cid).lng("current_location_id"));
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> object(Object value) {
		return (Map<String, Object>) value;
	}
}
