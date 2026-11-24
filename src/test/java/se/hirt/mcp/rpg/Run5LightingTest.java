package se.hirt.mcp.rpg;

import org.junit.jupiter.api.Test;
import se.hirt.mcp.rpg.dice.ScriptedRollService;
import se.hirt.mcp.rpg.maps.SceneService;
import se.hirt.mcp.rpg.protocol.Ref;
import se.hirt.mcp.rpg.protocol.RpgException;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static se.hirt.mcp.rpg.TestCampaigns.*;

class Run5LightingTest {

	@Test
	void combatLightingAdjudicationRevealsTheFoeWithoutChangingTurnBudgetsOrGeometry() throws Exception {
		try (Engine e = engine(tempDb("run5-combat-light"))) {
			String campaign = ready(e);
			var published = publish(e, campaign, proposal(opening(e, campaign)));
			String dungeon = published.get("map_ref").toString();
			String foe = SceneService.map(published.get("references")).get("actor:east-guard").toString();
			e.scenes().place(op(), campaign, dungeon, List.of(map("character", "character:1", "x_ft", 5, "y_ft", 5)));
			((ScriptedRollService) e.roller()).queue(20, 1);
			String encounter = e.encounters().start(op(), campaign,
					map("party", List.of("character:1"), "foes", List.of(foe)), null, null, null, null, null)
					.get("encounter").toString();
			assertFalse(seen(e, campaign, dungeon, foe));
			long cid = Ref.id(campaign, Ref.CAMPAIGN), eid = Ref.id(encounter, Ref.ENCOUNTER);
			var encounterBefore = e.db().read(tx -> tx.get("encounter", eid).asMap());
			var turnsBefore = e.db()
					.read(tx -> tx.query("SELECT * FROM encounter_participant WHERE encounter_id=? ORDER BY id", eid)
							.stream().map(row -> row.asMap()).toList());
			var actorsBefore = e.db()
					.read(tx -> tx
							.query("SELECT * FROM map_actor WHERE scene_id=? ORDER BY id", Ref.id(dungeon, Ref.MAP))
							.stream().map(row -> row.asMap()).toList());
			var hpBefore = e.db()
					.read(tx -> tx.query(
							"SELECT id,current_hp,temp_hp,life_state FROM character WHERE campaign_id=? ORDER BY id",
							cid).stream().map(row -> row.asMap()).toList());
			Map<String, Long> countsBefore = entityCounts(e, campaign);
			e.maps().correctGeometry(op(), campaign, dungeon, revision(e, campaign, dungeon),
					map("rooms", List.of(map("key", "east", "illumination", "BRIGHT"))),
					"The DM has resolved lighting the east braziers.");
			assertTrue(seen(e, campaign, dungeon, foe));
			assertEquals(encounterBefore, e.db().read(tx -> tx.get("encounter", eid).asMap()));
			assertEquals(turnsBefore,
					e.db().read(
							tx -> tx.query("SELECT * FROM encounter_participant WHERE encounter_id=? ORDER BY id", eid)
									.stream().map(row -> row.asMap()).toList()));
			assertEquals(actorsBefore,
					e.db().read(tx -> tx
							.query("SELECT * FROM map_actor WHERE scene_id=? ORDER BY id", Ref.id(dungeon, Ref.MAP))
							.stream().map(row -> row.asMap()).toList()));
			assertEquals(hpBefore,
					e.db().read(tx -> tx.query(
							"SELECT id,current_hp,temp_hp,life_state FROM character WHERE campaign_id=? ORDER BY id",
							cid).stream().map(row -> row.asMap()).toList()));
			assertEquals(countsBefore, entityCounts(e, campaign));
			for (Map<String, Object> patch : List.of(map("illumination", "BRIGHT", "bounds_ft", List.of(0, 0, 70, 20)),
					map("rooms",
							List.of(map("key", "east", "illumination", "DIM", "rect_ft", List.of(30, 0, 30, 20)))))) {
				var before = e.db().snapshot(cid);
				RpgException refused = assertThrows(RpgException.class,
						() -> e.maps().correctGeometry(op(), campaign, dungeon, revision(e, campaign, dungeon), patch,
								"Lighting cannot smuggle a structural combat correction."));
				assertTrue(refused.getMessage().contains("Structural geometry must wait"));
				assertEquals(before, e.db().snapshot(cid));
			}
			e.encounters().perform(op(), campaign, encounter, "character:1", map("kind", "DODGE"), false);
		}
	}

	@Test
	void publishedDarkDungeonCanBeLitInPlacePerRoomAndAsAMapWithReloadAndCheckpoint() throws Exception {
		var file = tempDb("run5-published-light");
		String campaign, dungeon, east, west, checkpoint;
		try (Engine e = engine(file)) {
			campaign = ready(e);
			var published = publish(e, campaign, proposal(opening(e, campaign)));
			dungeon = published.get("map_ref").toString();
			Map<String, Object> refs = SceneService.map(published.get("references"));
			east = refs.get("actor:east-guard").toString();
			west = refs.get("actor:west-guard").toString();
			e.scenes().place(op(), campaign, dungeon, List.of(map("character", "character:1", "x_ft", 5, "y_ft", 5)));
			assertFalse(seen(e, campaign, dungeon, east));
			assertFalse(seen(e, campaign, dungeon, west));
			Map<String, Long> counts = entityCounts(e, campaign);
			var markers = e.db()
					.read(tx -> tx
							.query("SELECT * FROM map_actor WHERE scene_id=? ORDER BY id", Ref.id(dungeon, Ref.MAP))
							.stream().map(row -> row.asMap()).toList());
			checkpoint = e.checkpoints().create(op(), campaign, "Before lighting the east room").get("checkpoint")
					.toString();
			long revision = revision(e, campaign, dungeon);
			String operation = op();
			var lit = e.maps().correctGeometry(operation, campaign, dungeon, revision,
					map("rooms", List.of(map("key", "east", "illumination", "BRIGHT"))), "The east braziers are lit.");
			assertEquals(dungeon, lit.get("map"));
			assertEquals(true,
					e.maps().correctGeometry(operation, campaign, dungeon, revision,
							map("rooms", List.of(map("key", "east", "illumination", "BRIGHT"))),
							"The east braziers are lit.").get("replayed"));
			assertTrue(seen(e, campaign, dungeon, east), "A human in darkness can see a target in the lit east room.");
			assertFalse(seen(e, campaign, dungeon, west), "An unlit room does not inherit another room's override.");
			assertTrue(Boolean.TRUE.equals(e.db()
					.read(tx -> SceneService.canSee(tx, SceneService.scene(tx, Ref.id(campaign, Ref.CAMPAIGN), dungeon),
							SceneService.actor(tx, Ref.id(campaign, Ref.CAMPAIGN), "character:1"),
							SceneService.actor(tx, Ref.id(campaign, Ref.CAMPAIGN), east)))));
			e.maps().correctGeometry(op(), campaign, dungeon, revision(e, campaign, dungeon),
					map("illumination", "BRIGHT", "rooms", List.of(map("key", "east", "illumination", "DARK"))),
					"The west hall is lit, while the east braziers go out.");
			assertTrue(seen(e, campaign, dungeon, west));
			assertFalse(seen(e, campaign, dungeon, east), "A room override takes precedence over map ambient light.");
			assertEquals(counts, entityCounts(e, campaign));
			assertEquals(markers,
					e.db().read(tx -> tx
							.query("SELECT * FROM map_actor WHERE scene_id=? ORDER BY id", Ref.id(dungeon, Ref.MAP))
							.stream().map(row -> row.asMap()).toList()));
		}
		try (Engine e = engine(file)) {
			assertTrue(seen(e, campaign, dungeon, west));
			assertFalse(seen(e, campaign, dungeon, east));
			e.checkpoints().restore(op(), campaign, checkpoint, "Restore the dungeon before either room was lit.");
			assertFalse(seen(e, campaign, dungeon, west));
			assertFalse(seen(e, campaign, dungeon, east));
		}
	}

	@Test
	void invalidOrStaleIlluminationCorrectionsRejectWithoutChangingTheMap() throws Exception {
		try (Engine e = engine(tempDb("run5-light-validation"))) {
			String campaign = ready(e),
					dungeon = publish(e, campaign, proposal(opening(e, campaign))).get("map_ref").toString();
			long revision = revision(e, campaign, dungeon), cid = Ref.id(campaign, Ref.CAMPAIGN);
			for (Map<String, Object> patch : List.of(map("illumination", "SUNLIGHT"),
					map("rooms", List.of(map("key", "missing", "illumination", "BRIGHT"))),
					map("rooms", List.of(map("key", "west", "illumination", "DIM", "location_ref", "location:999"))))) {
				var before = e.db().snapshot(cid);
				assertThrows(RpgException.class, () -> e.maps().correctGeometry(op(), campaign, dungeon, revision,
						patch, "A resolved lighting change."));
				assertEquals(before, e.db().snapshot(cid));
			}
			e.maps().correctGeometry(op(), campaign, dungeon, revision, map("illumination", "DIM"),
					"The hall is dimly lit.");
			var before = e.db().snapshot(cid);
			assertThrows(RpgException.class, () -> e.maps().correctGeometry(op(), campaign, dungeon, revision,
					map("illumination", "BRIGHT"), "A stale correction cannot overwrite the new state."));
			assertEquals(before, e.db().snapshot(cid));
		}
	}

	@Test
	void separateRealizationWarnsBeforePublicationAndDiscoveryIdentifiesActualBoardUse() throws Exception {
		try (Engine e = engine(tempDb("run5-realization-warning"))) {
			String campaign = ready(e), site = opening(e, campaign);
			String first = publish(e, campaign, proposal(site)).get("map_ref").toString();
			e.scenes().place(op(), campaign, first, List.of(map("character", "character:1", "x_ft", 5, "y_ft", 5)));
			var secondDraft = e.maps().begin(op(), campaign, "DUNGEON", proposal(site));
			assertTrue(secondDraft.toString().contains("correct_map_geometry"));
			assertEquals(first,
					SceneService.maps(secondDraft.get("existing_published_realizations")).getFirst().get("map"));
			var secondPublished = e.maps().publish(op(), campaign, secondDraft.get("preparation").toString(), null);
			String second = secondPublished.get("map_ref").toString();
			assertNotEquals(first, second);
			assertFalse(((List<?>) secondPublished.get("warnings")).isEmpty());
			var found = e.maps().find(campaign, map("location_ref", site, "kind", "DUNGEON"));
			assertEquals(first, SceneService.map(found.get("current_board")).get("map_ref"));
			Map<String, Object> old = SceneService.maps(found.get("maps")).stream()
					.filter(scene -> first.equals(scene.get("map"))).findFirst().orElseThrow();
			Map<String, Object> other = SceneService.maps(found.get("maps")).stream()
					.filter(scene -> second.equals(scene.get("map"))).findFirst().orElseThrow();
			assertEquals(true, old.get("current_party_board"));
			assertEquals(false, other.get("current_party_board"));
			assertTrue(SceneService.maps(old.get("placed_characters")).stream()
					.anyMatch(actor -> "character:1".equals(actor.get("character"))));
			assertEquals(second, SceneService.maps(old.get("other_published_realizations")).getFirst().get("map"));
		}
	}

	private static Map<String, Object> proposal(String site) {
		return map("name", "Unlit mine", "stage", "SCENE", "location_ref", site, "room_count", 2, "geometry",
				map("bounds_ft", List.of(0, 0, 60, 20)), "rooms",
				List.of(map("key", "west", "rect_ft", List.of(0, 0, 30, 20)),
						map("key", "east", "rect_ft", List.of(30, 0, 30, 20))),
				"connections",
				List.of(map("key", "open-arch", "from", "west", "to", "east", "kind", "DOOR", "state", "OPEN",
						"geometry", map("from_ft", List.of(30, 5), "to_ft", List.of(30, 15)))),
				"actors",
				List.of(map("key", "west-guard", "room", "west", "creature_ref", "Bandit", "name", "West guard", "x_ft",
						15, "y_ft", 5),
						map("key", "east-guard", "room", "east", "creature_ref", "Bandit", "name", "East guard", "x_ft",
								40, "y_ft", 5)));
	}

	private static boolean seen(Engine e, String campaign, String map, String target) {
		return SceneService.maps(e.scenes().perception(campaign, map, "character:1").get("currently_seen")).stream()
				.anyMatch(actor -> target.equals(actor.get("character")));
	}

	private static String ready(Engine e) {
		String c = committedCampaign(e);
		e.sessions().bootstrap(op(), c, null);
		return c;
	}

	private static String opening(Engine e, String campaign) {
		return e.db().read(tx -> Ref.of(Ref.LOCATION,
				tx.get("campaign", Ref.id(campaign, Ref.CAMPAIGN)).lng("current_location_id")));
	}

	private static long revision(Engine e, String campaign, String map) {
		return e.db().read(tx -> SceneService.scene(tx, Ref.id(campaign, Ref.CAMPAIGN), map).lng("revision"));
	}

	private static Map<String, Object> publish(Engine e, String campaign, Map<String, Object> proposal) {
		var draft = e.maps().begin(op(), campaign, "DUNGEON", proposal);
		return e.maps().publish(op(), campaign, draft.get("preparation").toString(), null);
	}

	private static Map<String, Long> entityCounts(Engine e, String campaign) {
		return e.db().read(tx -> {
			long id = Ref.id(campaign, Ref.CAMPAIGN);
			return Map.of("maps", tx.count("SELECT COUNT(*) FROM map_scene WHERE campaign_id=?", id), "rooms",
					tx.count("SELECT COUNT(*) FROM location WHERE campaign_id=?", id), "actors",
					tx.count("SELECT COUNT(*) FROM character WHERE campaign_id=?", id), "inventory",
					tx.count("SELECT COUNT(*) FROM inventory_entry WHERE campaign_id=?", id), "objects",
					tx.count("SELECT COUNT(*) FROM map_object WHERE campaign_id=?", id));
		});
	}
}
