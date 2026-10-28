package se.hirt.mcp.rpg;

import org.junit.jupiter.api.Test;
import se.hirt.mcp.rpg.dice.ScriptedRollService;
import se.hirt.mcp.rpg.maps.SceneService;
import se.hirt.mcp.rpg.protocol.Json;
import se.hirt.mcp.rpg.protocol.Ref;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static se.hirt.mcp.rpg.TestCampaigns.*;

class Run4DigestTest {

	@Test
	void zeroActionFightHasNoInventedChangesAndCompactHumanRecall() throws Exception {
		try (Engine e = engine(tempDb("run4-quiet-digest"))) {
			String campaign = committedCampaign(e);
			e.sessions().bootstrap(op(), campaign, null);
			String guard = npc(e, campaign, "Bandit", "Watchful guard");
			((ScriptedRollService) e.roller()).queue(20, 1);
			String encounter = e.encounters()
					.start(op(), campaign, map("party", List.of("character:1"), "foes", List.of(guard)), null, null,
							null, null, null,
							map("scene",
									map("name", "Quiet yard", "bounds_ft", List.of(0, 0, 60, 30), "objects",
											List.of(map("key", "table", "rect_ft", List.of(20, 10, 5, 5), "state",
													"INTACT", "hit_points", 8))),
									"placements", List.of(position("character:1", 5, 5), position(guard, 40, 5))))
					.get("encounter").toString();
			var ended = e.encounters().end(op(), campaign, encounter, "OTHER", "Both lower their weapons.",
					"The company leaves quietly.");
			Map<String, Object> record = SceneService.map(ended.get("record"));
			var positions = SceneService.maps(ended.get("party_positions"));
			assertEquals(1, positions.size());
			assertEquals("character:1", positions.getFirst().get("character"));
			assertEquals(List.of(5.0, 5.0), positions.getFirst().get("move_origin_ft"));
			assertEquals(SceneService.map(ended.get("current_board")).get("map_ref"),
					positions.getFirst().get("map_ref"));
			assertTrue(SceneService.maps(record.get("resources")).isEmpty(),
					"Unchanged carried gear and resources must compare equally after baseline JSON reload.");
			assertTrue(SceneService.maps(record.get("map_changes")).isEmpty());
			assertEquals(2, SceneService.maps(record.get("initial_roster")).size());
			assertTrue(SceneService.maps(record.get("final_roster")).getFirst().containsKey("current_hp"),
					"The complete mechanical roster remains available in the GM record.");
			Map<String, Object> digest = publicDigest(e, ended);
			assertTrue(Json.write(digest).length() < 2000, Json.write(digest));
			assertFalse(digest.containsKey("initial_roster"));
			assertFalse(Json.write(digest).contains("current_hp"));
			assertFalse(Json.write(digest).contains("mechanics"));
			assertEquals(2, SceneService.maps(digest.get("final_roster")).size());
			assertTrue(SceneService.maps(digest.get("resources")).isEmpty());
			assertTrue(SceneService.maps(digest.get("map_changes")).isEmpty());
			Map<String, Object> timeline = resolvedTimeline(e, campaign);
			assertFalse(timeline.containsKey("detail"));
			assertEquals(digest, SceneService.map(timeline.get("encounter_digest")));
			Map<String, Object> pointer = SceneService.map(timeline.get("full_gm_record"));
			assertEquals("get_encounter_state", pointer.get("tool"));
			assertEquals("record", pointer.get("field"));
			assertEquals(encounter, SceneService.map(pointer.get("arguments")).get("encounter"));
			assertEquals("GM", SceneService.map(pointer.get("arguments")).get("audience"));
			String story = resolvedChronicle(e, campaign).get("detail").toString();
			assertTrue(story.startsWith("Encounter other;"), story);
			assertTrue(story.contains("Both lower their weapons."));
			assertTrue(story.contains("The company leaves quietly."));
			assertFalse(story.contains("current_hp"));
			assertFalse(story.contains("\"resources\""));
			assertEquals(story, e.db().read(tx -> tx
					.get("event", Ref.id(ended.get("resolved_event").toString(), Ref.EVENT)).str("episodic_detail")));
		}
	}

	@Test
	void partyBlessIsObservedAtActionTimeAndAnnotationUpdatesTheSameSafeStory() throws Exception {
		try (Engine e = engine(tempDb("run4-bless-digest"))) {
			String campaign = committedCampaign(e);
			e.sessions().bootstrap(op(), campaign, null);
			String priest = npc(e, campaign, "Priest Acolyte", "Sister Meriel");
			e.party().updateMembership(op(), campaign, priest, "JOIN", "A travelling companion.");
			String hidden = npc(e, campaign, "Bandit", "UNSEEN_ORGANIZER_SENTINEL");
			((ScriptedRollService) e.roller()).queue(1, 20, 1);
			String encounter = e.encounters().start(op(), campaign,
					map("party", List.of("character:1", priest), "foes", List.of(hidden)), null, null, null, null, null,
					map("scene",
							map("name", "Divided guardrooms", "bounds_ft", List.of(0, 0, 40, 20), "rooms",
									List.of(map("key", "west", "rect_ft", List.of(0, 0, 20, 20)),
											map("key", "east", "rect_ft", List.of(20, 0, 20, 20)))),
							"placements",
							List.of(position("character:1", 5, 5), position(priest, 10, 5), position(hidden, 30, 5))))
					.get("encounter").toString();
			e.encounters()
					.perform(op(), campaign, encounter, priest, map("kind", "cast", "spell", "Bless", "targets",
							List.of("character:1", priest), "narration", "A steady prayer stills our shaking hands."),
							false);
			assertEquals(true,
					e.db().read(tx -> tx.queryOne(
							"SELECT * FROM encounter_log WHERE encounter_id=? AND kind='CAST' ORDER BY id DESC LIMIT 1",
							Ref.id(encounter, Ref.ENCOUNTER)).orElseThrow().map("payload_json").get("party_observed")),
					"Direct CAST rows need observation metadata while the action is resolving, before end.");
			var ended = e.encounters().end(op(), campaign, encounter, "OTHER", "The prayer prevents a fight.",
					"The company withdraws together.");
			Map<String, Object> record = SceneService.map(ended.get("record"));
			assertEquals(1, SceneService.maps(record.get("spells")).size());
			assertTrue(Json.write(record).contains("mechanics"));
			Map<String, Object> digest = publicDigest(e, ended);
			assertEquals(1, SceneService.maps(digest.get("spells")).size());
			assertEquals(1, SceneService.maps(digest.get("highlights")).size());
			assertFalse(Json.write(digest).contains("mechanics"));
			assertFalse(Json.write(digest).contains("UNSEEN_ORGANIZER_SENTINEL"));
			assertFalse(Json.write(digest).contains("\"" + hidden + "\""));
			assertTrue(Json.write(digest).contains("A steady prayer stills our shaking hands."));
			String slot = SceneService.maps(record.get("highlights")).getFirst().get("slot").toString();
			String annotation = op();
			var annotated = e.encounters().annotate(annotation, campaign, encounter,
					Map.of(slot, "The blessing steadies us."));
			assertEquals(ended.get("resolved_event"), annotated.get("resolved_event"));
			assertEquals(true,
					e.encounters().annotate(annotation, campaign, encounter, Map.of(slot, "The blessing steadies us."))
							.get("replayed"));
			assertEquals(1L,
					e.db().read(tx -> tx.count(
							"SELECT COUNT(*) FROM event WHERE campaign_id=? AND type='ENCOUNTER_RESOLVED'",
							Ref.id(campaign, Ref.CAMPAIGN))).longValue());
			String story = resolvedChronicle(e, campaign).get("detail").toString();
			assertTrue(story.contains("The blessing steadies us."), story);
			assertFalse(story.contains("UNSEEN_ORGANIZER_SENTINEL"));
			assertFalse(story.contains(hidden));
			assertFalse(story.contains("mechanics"));
			assertTrue(Json.write(resolvedTimeline(e, campaign).get("encounter_digest"))
					.contains("The blessing steadies us."));
		}
	}

	private static Map<String, Object> resolvedTimeline(Engine e, String campaign) {
		return SceneService.maps(
				e.ledger().queryTimeline(campaign, null, null, null, List.of("ENCOUNTER_RESOLVED"), null, false, 10)
						.get("events"))
				.getFirst();
	}

	private static Map<String, Object> resolvedChronicle(Engine e, String campaign) {
		return SceneService.maps(e.chronicle().material(campaign, "CHAPTER", null).get("events")).stream()
				.filter(event -> "ENCOUNTER_RESOLVED".equals(event.get("type"))).findFirst().orElseThrow();
	}

	private static Map<String, Object> publicDigest(Engine e, Map<String, Object> ended) {
		return e.db()
				.read(tx -> SceneService.map(tx.get("event", Ref.id(ended.get("resolved_event").toString(), Ref.EVENT))
						.map("payload_json").get("digest")));
	}

	private static Map<String, Object> position(String character, int x, int y) {
		return map("character", character, "x_ft", x, "y_ft", y);
	}

	private static String npc(Engine e, String campaign, String content, String name) {
		return e.runtime().materialize(op(), campaign, content, name, null, null, null, null, false).get("character")
				.toString();
	}
}
