package se.hirt.mcp.rpg;

import org.junit.jupiter.api.Test;
import se.hirt.mcp.rpg.dice.ScriptedRollService;
import se.hirt.mcp.rpg.maps.SceneService;
import se.hirt.mcp.rpg.persistence.Database;
import se.hirt.mcp.rpg.persistence.Row;
import se.hirt.mcp.rpg.protocol.Json;
import se.hirt.mcp.rpg.protocol.Ref;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static se.hirt.mcp.rpg.TestCampaigns.*;

class EncounterDigestAuditTest {

	@Test
	void annotationsWorkWhenCampaignIdsAreOutsideTheBoxedLongCache() throws Exception {
		try (Engine e = engine(tempDb("digest-large-campaign-id"))) {
			String unused = e.campaigns().create(op(), null, "srd5e").get("campaign").toString();
			e.db().mutate(Database.Mutation.of("large-campaign-id-fixture", Ref.id(unused, Ref.CAMPAIGN), op(), "GM",
					Map.of()), tx -> {
						var copy = tx.get("campaign", Ref.id(unused, Ref.CAMPAIGN)).asMap();
						copy.put("id", 128L);
						tx.insert("campaign", copy);
						return Map.of("fixture", true);
					});
			String campaign = committedCampaign(e);
			assertTrue(Ref.id(campaign, Ref.CAMPAIGN) > 127);
			e.sessions().bootstrap(op(), campaign, null);
			String guard = bandit(e, campaign, "Visible guard");
			String encounter = start(e, campaign, List.of(guard), map("placements", positions(guard, 10, 5)))
					.get("encounter").toString();
			recordedHealing(e, campaign, guard);
			var ended = e.encounters().end(op(), campaign, encounter, "OTHER", "The guard recovers.");
			String slot = highlights(ended).getFirst().get("slot").toString();
			var annotated = e.encounters().annotate(op(), campaign, encounter, Map.of(slot, "A breath steadies him."));
			assertEquals(ended.get("resolved_event"), annotated.get("resolved_event"));
			assertEquals("A breath steadies him.", highlights(annotated).getFirst().get("narration"));
		}
	}

	@Test
	void annotationWithAnEscapedHiddenNameStaysInTheGmRecordAndOutOfTheLedger() throws Exception {
		try (Engine e = engine(tempDb("digest-escaped-private-name"))) {
			String campaign = committedCampaign(e);
			e.sessions().bootstrap(op(), campaign, null);
			String visible = bandit(e, campaign, "Visible guard");
			String hiddenName = "Unseen \"Curator\"\\Keeper";
			String hidden = bandit(e, campaign, hiddenName);
			String encounter = start(e, campaign, List.of(visible, hidden),
					map("scene", dividedScene(), "placements",
							List.of(map("character", "character:1", "x_ft", 5, "y_ft", 5),
									map("character", visible, "x_ft", 10, "y_ft", 5),
									map("character", hidden, "x_ft", 30, "y_ft", 5))))
					.get("encounter").toString();
			recordedHealing(e, campaign, visible);
			var ended = e.encounters().end(op(), campaign, encounter, "OTHER", "The visible guard recovers.");
			assertEquals(1, SceneService.maps(publicDigest(e, ended).get("highlights")).size());
			String slot = highlights(ended).getFirst().get("slot").toString();
			String words = "Behind the wall, " + hiddenName + " waits.";
			var annotated = e.encounters().annotate(op(), campaign, encounter, Map.of(slot, words));
			assertEquals(words, highlights(annotated).getFirst().get("narration"));
			assertTrue(SceneService.maps(publicDigest(e, annotated).get("highlights")).isEmpty(),
					"An observed action cannot carry an unseen identity through JSON escaping.");
			assertEquals(2, SceneService.maps(publicDigest(e, annotated).get("final_roster")).size());
		}
	}

	@Test
	void cancelledPortalNarrationCannotAttachToLaterMovementAndSuccessfulResumeKeepsItsWords() throws Exception {
		for (boolean cancel : List.of(false, true)) {
			try (Engine e = engine(tempDb("digest-portal-narration-" + cancel))) {
				String campaign = committedCampaign(e);
				e.sessions().bootstrap(op(), campaign, null);
				String location = e.db().read(tx -> Ref.of(Ref.LOCATION,
						tx.get("campaign", Ref.id(campaign, Ref.CAMPAIGN)).lng("current_location_id")));
				var draft = e.maps().begin(op(), campaign, "SCENE", map("name", "Two linked rooms", "location_ref",
						location, "room_count", 2, "geometry", map("bounds_ft", List.of(0, 0, 60, 20)), "rooms",
						List.of(map("key", "west", "rect_ft", List.of(0, 0, 20, 20)),
								map("key", "east", "rect_ft", List.of(40, 0, 20, 20))),
						"connections",
						List.of(map("key", "mirror", "kind", "PORTAL", "from", "west", "to", "east", "from_position_ft",
								List.of(5, 5), "to_position_ft", List.of(45, 5), "movement_ft", 20))));
				String mapRef = e.maps().publish(op(), campaign, draft.get("preparation").toString(), null)
						.get("map_ref").toString();
				e.scenes().place(op(), campaign, mapRef,
						List.of(map("character", "character:1", "x_ft", 5, "y_ft", 5)));
				String guard = bandit(e, campaign, "Guard at the mirror");
				String encounter = start(e, campaign, List.of(guard),
						map("map_ref", mapRef, "npc_reactions", "ASK", "placements", positions(guard, 10, 5)))
						.get("encounter").toString();
				String narration = "I step into the silver mirror.";
				var paused = e.encounters().perform(op(), campaign, encounter, "character:1",
						map("kind", "MOVE", "portal", "mirror", "narration", narration), false);
				assertEquals(true, paused.get("paused_for_reaction"));
				String choice = SceneService.maps(paused.get("pending_choices")).getFirst().get("transaction")
						.toString();
				if (cancel)
					e.runtime().applyRuntimeChange(op(), campaign, "character:1",
							map("kind", "ADD_CONDITION", "condition", "PRONE", "reason",
									"A separately resolved slip prevents the costly transition."));
				String resolution = op();
				var resumed = e.encounters().resolveChoice(resolution, campaign, choice, map("option", "DECLINE"));
				assertEquals(!cancel, SceneService.map(resumed.get("movement_resumed")).get("completed"));
				assertEquals(true, e.encounters().resolveChoice(resolution, campaign, choice, map("option", "DECLINE"))
						.get("replayed"));
				long encounterId = Ref.id(encounter, Ref.ENCOUNTER);
				List<Row> movements = e.db()
						.read(tx -> tx.query(
								"SELECT * FROM encounter_log WHERE encounter_id=? AND kind='MOVE' ORDER BY id",
								encounterId));
				assertEquals(cancel ? 0 : 1, movements.size());
				if (!cancel)
					assertEquals(narration, movements.getFirst().str("narration"));
				e.encounters().perform(op(), campaign, encounter, "character:1",
						map("kind", "MOVE", "x_ft", cancel ? 5 : 45, "y_ft", 10), false);
				Row later = e.db().read(tx -> tx.queryOne(
						"SELECT * FROM encounter_log WHERE encounter_id=? AND kind='MOVE' ORDER BY id DESC LIMIT 1",
						encounterId).orElseThrow());
				assertNull(later.str("narration"), "The new movement has no narrated words of its own.");
			}
		}
	}

	@Test
	void oldRunningEncounterWithoutABaselineDoesNotInventResourceOrMapChanges() throws Exception {
		try (Engine e = engine(tempDb("digest-missing-baseline"))) {
			String campaign = committedCampaign(e);
			e.sessions().bootstrap(op(), campaign, null);
			String guard = bandit(e, campaign, "Witness");
			var started = start(e, campaign, List.of(guard),
					map("scene",
							map("bounds_ft", List.of(0, 0, 30, 20), "objects",
									List.of(map("key", "table", "rect_ft", List.of(20, 10, 5, 5)))),
							"placements", positions(guard, 10, 5)));
			String encounter = started.get("encounter").toString();
			long cid = Ref.id(campaign, Ref.CAMPAIGN);
			e.db().mutate(Database.Mutation.of("legacy-active-encounter-fixture", cid, op(), "GM", Map.of()), tx -> {
				tx.update("encounter", Ref.id(encounter, Ref.ENCOUNTER), map("record_json", null));
				return Map.of("legacy", true);
			});
			e.runtime().applyRuntimeChange(op(), campaign, "character:1", map("kind", "USE_RESOURCE", "resource",
					"spell_slot:1", "reason", "A separately resolved spell uses a slot."));
			e.scenes().changeObject(op(), campaign, SceneService.map(started.get("grid")).get("map_ref").toString(),
					"table", map("state", "DESTROYED"), "The table breaks.");
			var ended = e.encounters().end(op(), campaign, encounter, "OTHER", "An older fight ends.");
			Map<String, Object> record = SceneService.map(ended.get("record"));
			assertEquals("UNAVAILABLE", record.get("baseline_status"));
			assertFalse(record.containsKey("initial_roster"));
			assertFalse(record.containsKey("resources"));
			assertFalse(record.containsKey("map_changes"));
			assertEquals(2, SceneService.maps(record.get("final_roster")).size());
			assertEquals("UNAVAILABLE", publicDigest(e, ended).get("baseline_status"));
			assertFalse(publicDigest(e, ended).containsKey("resources"));
			assertFalse(publicDigest(e, ended).containsKey("map_changes"));
		}
	}

	@Test
	void anEverKnownEnemyCannotRevealUnseenPotionConsumptionThroughResourceDeltas() throws Exception {
		try (Engine e = engine(tempDb("digest-unseen-enemy-resources"))) {
			String campaign = committedCampaign(e);
			e.sessions().bootstrap(op(), campaign, null);
			String guard = bandit(e, campaign, "Previously visible guard");
			String potion = SceneService.maps(e.inventory()
					.grantLoot(op(), campaign, guard, List.of(map("item", "Potion of Healing")), null, "QUEST", null)
					.get("granted")).getFirst().get("entry").toString();
			String encounter = start(e, campaign, List.of(guard), map("placements", positions(guard, 10, 5)))
					.get("encounter").toString();
			e.runtime().applyRuntimeChange(op(), campaign, "character:1", map("kind", "ADD_CONDITION", "condition",
					"BLINDED", "reason", "The party can no longer observe the guard."));
			e.encounters().perform(op(), campaign, encounter, "character:1", map("kind", "END_TURN"), true);
			((ScriptedRollService) e.roller()).queue(1, 1);
			e.encounters().perform(op(), campaign, encounter, guard, map("kind", "USE_ITEM", "item", potion), false);
			var ended = e.encounters().end(op(), campaign, encounter, "OTHER", "The encounter ends in darkness.");
			var changes = SceneService.maps(SceneService.map(ended.get("record")).get("resources"));
			assertTrue(changes.stream().anyMatch(change -> potion.equals(change.get("key"))));
			Map<String, Object> digest = publicDigest(e, ended);
			assertTrue(SceneService.maps(digest.get("final_roster")).stream().anyMatch(
					person -> guard.equals(person.get("character")) && Boolean.TRUE.equals(person.get("last_known"))));
			assertFalse(Json.write(digest.get("resources")).contains(potion));
			assertTrue(SceneService.maps(digest.get("resources")).stream().allMatch(change -> {
				Map<String, Object> owner = SceneService.map(change.get("after"));
				if (owner.isEmpty())
					owner = SceneService.map(change.get("before"));
				return "character:1".equals(owner.get("character"));
			}));
		}
	}

	private static Map<String, Object> dividedScene() {
		return map("name", "Divided guardrooms", "bounds_ft", List.of(0, 0, 40, 20), "rooms",
				List.of(map("key", "west", "rect_ft", List.of(0, 0, 20, 20)),
						map("key", "east", "rect_ft", List.of(20, 0, 20, 20))));
	}

	private static void recordedHealing(Engine e, String campaign, String character) {
		e.runtime().applyRuntimeChange(op(), campaign, character,
				map("kind", "DAMAGE", "amount", 1, "reason", "A resolved scratch."));
		e.runtime().applyRuntimeChange(op(), campaign, character,
				map("kind", "HEAL", "amount", 1, "reason", "A resolved recovery."));
	}

	private static List<Map<String, Object>> highlights(Map<String, Object> response) {
		return SceneService.maps(SceneService.map(response.get("record")).get("highlights"));
	}

	private static Map<String, Object> publicDigest(Engine e, Map<String, Object> ended) {
		String event = ended.get("resolved_event").toString();
		return e.db().read(
				tx -> SceneService.map(tx.get("event", Ref.id(event, Ref.EVENT)).map("payload_json").get("digest")));
	}

	private static String bandit(Engine e, String campaign, String name) {
		return e.runtime().materialize(op(), campaign, "Bandit", name, null, null, null, null, false).get("character")
				.toString();
	}

	private static Map<String, Object> start(
		Engine e, String campaign, List<String> foes, Map<String, Object> options) {
		((ScriptedRollService) e.roller()).queue(20, 1);
		return e.encounters().start(op(), campaign, map("party", List.of("character:1"), "foes", foes), null, null,
				null, null, null, options);
	}

	private static List<Map<String, Object>> positions(String foe, int x, int y) {
		return List.of(map("character", "character:1", "x_ft", 5, "y_ft", 5),
				map("character", foe, "x_ft", x, "y_ft", y));
	}
}
