package se.hirt.mcp.rpg;

import org.junit.jupiter.api.Test;
import se.hirt.mcp.rpg.maps.SceneService;
import se.hirt.mcp.rpg.persistence.Database;
import se.hirt.mcp.rpg.persistence.Row;
import se.hirt.mcp.rpg.protocol.Ref;
import se.hirt.mcp.rpg.protocol.RpgException;
import se.hirt.mcp.rpg.spatial.SpatialRules;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static se.hirt.mcp.rpg.TestCampaigns.*;

class Run4SceneLifecycleTest {

	@Test
	void explicitCellarRemainsCurrentUntilPeacefulReturnWithoutShadowingPublishedCoverage() throws Exception {
		try (Engine e = engine(tempDb("run4-published-scope"))) {
			String campaign = committedCampaign(e);
			e.sessions().bootstrap(op(), campaign, null);
			String guard = npc(e, campaign, "Tavern clerk");
			String published = publish(e, campaign, opening(e, campaign), "Published taproom");
			Map<String, Object> original = document(e, campaign, published);
			var cellarStart = start(e, campaign, List.of("character:1"), List.of(guard),
					map("scene", map("name", "Cellar sibling", "bounds_ft", List.of(0, 0, 30, 20)), "placements",
							placements(guard, 5, 5, 20, 5)));
			String cellar = board(cellarStart);
			assertNotEquals(published, cellar);
			assertFalse(document(e, campaign, cellar).containsKey("supersedes_map_ref"));
			assertEquals(original, document(e, campaign, published));
			end(e, campaign, cellarStart);
			long cid = Ref.id(campaign, Ref.CAMPAIGN);
			e.db().mutate(Database.Mutation.of("legacy-published-shadow-fixture", cid, op(), "GM", Map.of()), tx -> {
				Row scene = SceneService.scene(tx, cid, cellar);
				var doc = new LinkedHashMap<>(scene.map("document_json"));
				doc.put("supersedes_map_ref", published);
				tx.update("map_scene", scene.id(), Map.of("document_json", doc));
				return Map.of("legacy", true);
			});
			var cellarBoot = e.sessions().bootstrap(op(), campaign, null);
			assertEquals(cellar,
					SceneService.map(SceneService.map(cellarBoot.get("spatial")).get("current_board")).get("map_ref"));
			var resumed = start(e, campaign, List.of("character:1"), List.of(guard), Map.of());
			assertEquals(cellar, board(resumed), "Plain combat preserves the newer cellar's controlled figurine.");
			end(e, campaign, resumed);
			e.scenes().place(op(), campaign, published, placements(guard, 5, 5, 20, 5));
			var returned = start(e, campaign, List.of("character:1"), List.of(guard), Map.of());
			assertEquals(published, board(returned), "A legacy incidental link cannot hide a published preparation.");
			end(e, campaign, returned);
			var boot = e.sessions().bootstrap(op(), campaign, null);
			assertEquals(published,
					SceneService.map(SceneService.map(boot.get("spatial")).get("current_board")).get("map_ref"));
			assertEquals(published,
					SceneService.map(SceneService.map(boot.get("world_maps")).get("current_board")).get("map_ref"));
		}
	}

	@Test
	void onlyMatchingIncidentalScopeCarriesResolvedFurnitureStateAndSupersedesOldCoverage() throws Exception {
		try (Engine e = engine(tempDb("run4-incidental-siblings"))) {
			String campaign = committedCampaign(e);
			e.sessions().bootstrap(op(), campaign, null);
			String guard = npc(e, campaign, "Witness");
			var table = map("key", "table", "rect_ft", List.of(10, 10, 5, 5), "blocks_movement", true);
			var first = start(e, campaign, List.of("character:1"), List.of(guard),
					map("scene", map("name", "Taproom", "scope_key", "taproom", "bounds_ft", List.of(0, 0, 30, 30),
							"objects", List.of(table)), "placements", placements(guard, 5, 5, 20, 5)));
			String old = board(first);
			end(e, campaign, first);
			e.scenes().changeObject(op(), campaign, old, "table", map("state", "DESTROYED"), "The table breaks.");
			var refinement = start(e, campaign, List.of("character:1"), List.of(guard),
					map("scene", map("name", "Expanded taproom", "scope_key", "taproom", "bounds_ft",
							List.of(0, 0, 40, 30), "objects", List.of(table)), "placements",
							placements(guard, 5, 5, 25, 5)));
			String refined = board(refinement);
			assertEquals(old, document(e, campaign, refined).get("supersedes_map_ref"));
			assertEquals("DESTROYED",
					SceneService.maps(document(e, campaign, refined).get("objects")).getFirst().get("state"));
			end(e, campaign, refinement);
			var siblingStart = start(e, campaign, List.of("character:1"), List.of(guard),
					map("scene", map("name", "Cellar", "scope_key", "cellar", "bounds_ft", List.of(0, 0, 30, 30),
							"objects", List.of(table)), "placements", placements(guard, 5, 5, 20, 5)));
			String sibling = board(siblingStart);
			assertFalse(document(e, campaign, sibling).containsKey("supersedes_map_ref"));
			assertNotEquals("DESTROYED",
					SceneService.maps(document(e, campaign, sibling).get("objects")).getFirst().get("state"));
			end(e, campaign, siblingStart);
			e.scenes().place(op(), campaign, refined, placements(guard, 5, 5, 25, 5));
			var restored = start(e, campaign, List.of("character:1"), List.of(guard), Map.of());
			assertEquals(refined, board(restored), "Returning to a sibling is an explicit, peaceful board transition.");
		}
	}

	@Test
	void freshDefaultSidesStartThirtyFeetApartAndSavedPositionsStayAuthoritative() throws Exception {
		try (Engine e = engine(tempDb("run4-default-side-deployment"))) {
			String campaign = committedCampaign(e);
			e.sessions().bootstrap(op(), campaign, null);
			String ally = npc(e, campaign, "Companion"), first = npc(e, campaign, "Scout one"),
					second = npc(e, campaign, "Scout two");
			e.party().updateMembership(op(), campaign, ally, "JOIN", "The companion is with the player.");
			List<String> party = List.of("character:1", ally), foes = List.of(first, second);
			var started = start(e, campaign, party, foes, Map.of());
			for (String foe : foes)
				for (String member : party)
					assertTrue(distance(e, campaign, member, foe) >= 30 * 12);
			var before = List.of(actor(e, campaign, "character:1").asMap(), actor(e, campaign, ally).asMap(),
					actor(e, campaign, first).asMap(), actor(e, campaign, second).asMap());
			end(e, campaign, started);
			start(e, campaign, party, foes, Map.of());
			assertEquals(before, List.of(actor(e, campaign, "character:1").asMap(), actor(e, campaign, ally).asMap(),
					actor(e, campaign, first).asMap(), actor(e, campaign, second).asMap()));
		}
	}

	@Test
	void smallBoardsUseAvailableRangeAndExplicitMeleePlacementRemainsAvailable() throws Exception {
		try (Engine e = engine(tempDb("run4-small-board-deployment"))) {
			String campaign = committedCampaign(e);
			e.sessions().bootstrap(op(), campaign, null);
			String guard = npc(e, campaign, "Scout");
			var first = start(e, campaign, List.of("character:1"), List.of(guard),
					map("scene", map("name", "Small room", "bounds_ft", List.of(0, 0, 20, 20)), "placements",
							List.of(map("character", "character:1", "x_ft", 5, "y_ft", 5))));
			assertEquals(10 * 12, distance(e, campaign, "character:1", guard),
					"Choose the greatest valid range when 30 feet will not fit.");
			end(e, campaign, first);
			var melee = start(e, campaign, List.of("character:1"), List.of(guard),
					map("scene", map("name", "Declared melee ambush", "bounds_ft", List.of(0, 0, 20, 20)), "placements",
							placements(guard, 5, 5, 10, 5)));
			assertEquals(5 * 12, distance(e, campaign, "character:1", guard));
			end(e, campaign, melee);
			start(e, campaign, List.of("character:1"), List.of(guard), map("map_ref", board(melee)));
			assertEquals(5 * 12, distance(e, campaign, "character:1", guard),
					"Established exploration positions do not move to satisfy a default.");
		}
	}

	@Test
	void peacefulPublishedEntryAndNpcLocationOverrideDoNotRequireDuplicateCharactersOrCombat() throws Exception {
		try (Engine e = engine(tempDb("run4-peaceful-board-entry"))) {
			String campaign = committedCampaign(e);
			e.sessions().bootstrap(op(), campaign, null);
			String guard = npc(e, campaign, "Pell");
			var first = start(e, campaign, List.of("character:1"), List.of(guard),
					map("placements", placements(guard, 5, 5, 20, 5)));
			end(e, campaign, first);
			long cid = Ref.id(campaign, Ref.CAMPAIGN), figurine = actor(e, campaign, guard).id();
			String here = opening(e, campaign), published = publish(e, campaign, here, "Published tavern");
			e.scenes().place(op(), campaign, published, placements(guard, 5, 5, 20, 5));
			assertEquals(figurine, actor(e, campaign, guard).id());
			assertEquals(1L,
					e.db().read(tx -> tx.count("SELECT COUNT(*) FROM encounter WHERE campaign_id=?", cid)).longValue());
			String away = e.world().materialize(op(), campaign, null, map("name", "Harrowgate", "kind", "SITE"), "GM")
					.get("ref").toString();
			String awayMap = publish(e, campaign, away, "Harrowgate courtyard");
			var before = e.db().snapshot(cid);
			assertThrows(RpgException.class, () -> e.scenes().place(op(), campaign, awayMap,
					List.of(map("character", guard, "x_ft", 10, "y_ft", 5))));
			assertEquals(before, e.db().snapshot(cid));
			String checkpoint = e.checkpoints().create(op(), campaign, "Before Pell leaves").get("checkpoint")
					.toString();
			e.rest().override(op(), campaign, "SET_LOCATION", guard, map("location", here),
					"Pell remains in this location.", null);
			assertEquals(figurine, actor(e, campaign, guard).id(),
					"A compatible location override preserves physical position.");
			e.rest().override(op(), campaign, "SET_LOCATION", guard, map("location", away),
					"Pell leaves for Harrowgate.", null);
			assertEquals(0L,
					e.db().read(tx -> tx.count("SELECT COUNT(*) FROM map_actor WHERE campaign_id=? AND character_id=?",
							cid, Ref.id(guard, Ref.CHARACTER))).longValue());
			e.scenes().place(op(), campaign, awayMap, List.of(map("character", guard, "x_ft", 10, "y_ft", 5)));
			assertEquals(Ref.id(awayMap, Ref.MAP), actor(e, campaign, guard).lng("scene_id").longValue());
			e.checkpoints().restore(op(), campaign, checkpoint, "Pell has not left yet.");
			assertEquals(figurine, actor(e, campaign, guard).id());
			assertEquals(Ref.id(published, Ref.MAP), actor(e, campaign, guard).lng("scene_id").longValue());
		}
	}

	@Test
	void incompatibleLocationOverrideCannotLeaveAnActiveParticipantWithoutAFigurine() throws Exception {
		try (Engine e = engine(tempDb("run4-active-location-override"))) {
			String campaign = committedCampaign(e);
			e.sessions().bootstrap(op(), campaign, null);
			String guard = npc(e, campaign, "Active opponent");
			String away = e.world().materialize(op(), campaign, null, map("name", "Remote place", "kind", "SITE"), "GM")
					.get("ref").toString();
			start(e, campaign, List.of("character:1"), List.of(guard), Map.of());
			long cid = Ref.id(campaign, Ref.CAMPAIGN);
			var before = e.db().snapshot(cid);
			RpgException refusal = assertThrows(RpgException.class, () -> e.rest().override(op(), campaign,
					"SET_LOCATION", guard, map("location", away), "Relocate an active opponent.", null));
			assertTrue(refusal.getMessage().contains("End the encounter or remove this participant"));
			assertEquals(before, e.db().snapshot(cid));
			assertNotNull(actor(e, campaign, guard));
		}
	}

	private static String publish(Engine e, String campaign, String location, String name) {
		var draft = e.maps().begin(op(), campaign, "SCENE",
				map("name", name, "location_ref", location, "room_count", 1, "geometry",
						map("bounds_ft", List.of(0, 0, 60, 40)), "rooms",
						List.of(map("key", "room", "location_ref", location, "rect_ft", List.of(0, 0, 60, 40)))));
		return e.maps().publish(op(), campaign, draft.get("preparation").toString(), null).get("map_ref").toString();
	}

	private static Map<String, Object> start(
		Engine e, String campaign, List<String> party, List<String> foes, Map<String, Object> options) {
		return e.encounters().start(op(), campaign, map("party", party, "foes", foes), null, null, null, null, null,
				options);
	}

	private static void end(Engine e, String campaign, Map<String, Object> started) {
		e.encounters().end(op(), campaign, started.get("encounter").toString(), "OTHER", "The confrontation ends.");
	}

	private static String board(Map<String, Object> started) {
		return SceneService.map(started.get("grid")).get("map_ref").toString();
	}

	private static String npc(Engine e, String campaign, String name) {
		return e.runtime().materialize(op(), campaign, "Bandit", name, null, null, null, null, false).get("character")
				.toString();
	}

	private static String opening(Engine e, String campaign) {
		return e.db().read(tx -> Ref.of(Ref.LOCATION,
				tx.get("campaign", Ref.id(campaign, Ref.CAMPAIGN)).lng("current_location_id")));
	}

	private static Row actor(Engine e, String campaign, String character) {
		return e.db().read(tx -> SceneService.actor(tx, Ref.id(campaign, Ref.CAMPAIGN), character));
	}

	private static long distance(Engine e, String campaign, String first, String second) {
		return SpatialRules.distance(SceneService.footprint(actor(e, campaign, first)),
				SceneService.footprint(actor(e, campaign, second)));
	}

	private static Map<String, Object> document(Engine e, String campaign, String map) {
		return e.db()
				.read(tx -> SceneService.document(tx, SceneService.scene(tx, Ref.id(campaign, Ref.CAMPAIGN), map)));
	}

	private static List<Map<String, Object>> placements(String foe, int x, int y, int fx, int fy) {
		return List.of(map("character", "character:1", "x_ft", x, "y_ft", y),
				map("character", foe, "x_ft", fx, "y_ft", fy));
	}
}
