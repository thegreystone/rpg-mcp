package se.hirt.mcp.rpg;

import org.junit.jupiter.api.Test;
import se.hirt.mcp.rpg.ledger.LedgerService;
import se.hirt.mcp.rpg.maps.SceneService;
import se.hirt.mcp.rpg.persistence.Database;
import se.hirt.mcp.rpg.protocol.Json;
import se.hirt.mcp.rpg.protocol.Ref;
import se.hirt.mcp.rpg.world.TravelJourneyService;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static se.hirt.mcp.rpg.TestCampaigns.*;

class Run5RecallTest {

	@Test
	void midJourneyBootstrapDescribesTravelInsteadOfTheDepartureRoom() throws Exception {
		try (Engine e = engine(tempDb("run5-journey-recall"))) {
			String campaign = committedCampaign(e);
			e.sessions().bootstrap(op(), campaign, null);
			long cid = Ref.id(campaign, Ref.CAMPAIGN);
			String home = e.db().read(tx -> "location:" + tx.get("campaign", cid).lng("current_location_id"));
			String mine = e.world()
					.materialize(op(), campaign, null,
							map("name", "The old mine", "kind", "SITE", "connections",
									List.of(map("to", home, "kind", "ROAD", "travel_minutes", 50))),
							"GM")
					.get("ref").toString();
			Map<String, Object> published = publishRoom(e, campaign, home);
			e.scenes().place(op(), campaign, published.get("map_ref").toString(),
					List.of(map("character", "character:1", "x_ft", 5, "y_ft", 5)));
			TravelJourneyService travel = new TravelJourneyService(e.db(), e.roller());
			travel.defineRisk(op(), campaign, "DEFAULT", map("frequency_seconds", 3600, "chance_percent", 0,
					"authored_at_fraction", .5, "story", "A scout waits on the road."));
			assertTrue(Boolean.TRUE.equals(
					e.world().move(op(), campaign, mine, null, false, null, "Take the road.").get("interrupted")));
			Map<String, Object> before = travel.state(campaign);
			Map<String, Object> context = e.sessions().bootstrap(op(), campaign, null);
			Map<String, Object> position = SceneService.map(context.get("location"));
			assertEquals("JOURNEY", position.get("kind"));
			assertEquals("Travelling toward The old mine", position.get("name"));
			assertFalse(position.containsKey("ref"),
					"A journey position must not masquerade as the departure room reference.");
			assertEquals(1500L, position.get("progress_seconds"));
			assertEquals(mine, SceneService.map(position.get("destination")).get("ref"));
			assertEquals(before, travel.state(campaign), "Recalling context must not advance or relocate the journey.");
			assertEquals(position.get("name"), SceneService.maps(context.get("party")).getFirst().get("location_name"));
			travel.resolveOpportunity(op(), campaign, "CONTINUE");
			e.world().move(op(), campaign, mine, null, false, null, "Finish the same route.");
			assertEquals(mine,
					SceneService.map(e.sessions().bootstrap(op(), campaign, null).get("location")).get("ref"));
		}
	}

	@Test
	void aFreeRoomToParentMoveClearlyReportsCompletion() throws Exception {
		try (Engine e = engine(tempDb("run5-parent-completion"))) {
			String campaign = committedCampaign(e);
			e.sessions().bootstrap(op(), campaign, null);
			long cid = Ref.id(campaign, Ref.CAMPAIGN);
			String home = e.db().read(tx -> "location:" + tx.get("campaign", cid).lng("current_location_id"));
			var published = publishRoom(e, campaign, home);
			e.scenes().place(op(), campaign, published.get("map_ref").toString(),
					List.of(map("character", "character:1", "x_ft", 5, "y_ft", 5)));
			var moved = e.world().move(op(), campaign, home, null, false, null, "Leave the taproom.");
			assertEquals("COMPLETED", moved.get("status"));
			assertEquals(true, moved.get("within_place"));
			assertEquals(0L, moved.get("travel_minutes"));
			assertEquals(home, moved.get("destination"));
			assertEquals(List.of(), moved.get("route"));
		}
	}

	@Test
	void largeFightRecallIsBoundedWhileSavedDetailAndChronicleStayIntact() throws Exception {
		try (Engine e = engine(tempDb("run5-bounded-recall"))) {
			String campaign = committedCampaign(e);
			e.sessions().bootstrap(op(), campaign, null);
			long cid = Ref.id(campaign, Ref.CAMPAIGN);
			var highlights = new ArrayList<Map<String, Object>>();
			for (int i = 0; i < 8; i++)
				highlights.add(map("slot", i + 1, "round", i + 1, "summary", "An observed exchange. ".repeat(10),
						"narration", "A detailed remembered moment. ".repeat(15)));
			var digest = map("encounter", "encounter:999", "outcome", "OTHER", "rounds", 8, "elapsed_seconds", 48,
					"highlights", highlights, "summary", "The brawl ends.", "narrative", "A long evening. ".repeat(65),
					"final_roster", List.of(map("name", "Tam", "character", "character:1", "life_state", "ALIVE")));
			var fixture = e.db().mutate(Database.Mutation.of("run5_fixture", cid, op(), "GM", Map.of()),
					tx -> Map.of("event",
							LedgerService.append(tx, cid,
									new LedgerService.EventSpec("ENCOUNTER_RESOLVED", "The brawl ends.", List.of(1L),
											"NOTABLE", "PARTY_KNOWN", "MECHANICAL_CONSEQUENCE", null, null,
											"Saved readable detail.", map("digest", digest)))));
			long event = ((Number) fixture.get("event")).longValue();
			var timeline = SceneService.maps(
					e.ledger().queryTimeline(campaign, null, null, null, List.of("ENCOUNTER_RESOLVED"), null, false, 30)
							.get("events"))
					.getFirst();
			var brief = SceneService.map(timeline.get("encounter_digest"));
			assertTrue(Json.write(brief).length() <= 2000, Json.write(brief));
			assertEquals(true, brief.get("truncated"));
			assertEquals("record", SceneService.map(timeline.get("full_gm_record")).get("field"));
			assertEquals(Json.write(digest), e.db().read(
					tx -> Json.write(SceneService.map(tx.get("event", event).map("payload_json").get("digest")))));
			var context = e.sessions().bootstrap(op(), campaign, null);
			var recalled = SceneService.maps(context.get("recent_events")).stream()
					.filter(row -> row.get("ref").equals("event:" + event)).findFirst().orElseThrow();
			assertEquals(brief, recalled.get("encounter_digest"));
			var chronicle = SceneService.maps(e.chronicle().material(campaign, "CHAPTER", null).get("events")).stream()
					.filter(row -> row.get("ref").equals("event:" + event)).findFirst().orElseThrow();
			assertTrue(chronicle.get("detail").toString().contains("A detailed remembered moment."));
		}
	}

	private static Map<String, Object> publishRoom(Engine e, String campaign, String owner) {
		var prep = e.maps().begin(op(), campaign, "SCENE",
				map("name", "The taproom", "location_ref", owner, "room_count", 1, "illumination", "BRIGHT", "geometry",
						map("bounds_ft", List.of(0, 0, 40, 20)), "rooms",
						List.of(map("key", "taproom", "rect_ft", List.of(0, 0, 40, 20)))));
		return e.maps().publish(op(), campaign, prep.get("preparation").toString(), null);
	}
}
