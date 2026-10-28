package se.hirt.mcp.rpg;

import org.junit.jupiter.api.Test;
import se.hirt.mcp.rpg.maps.SceneService;
import se.hirt.mcp.rpg.persistence.Row;
import se.hirt.mcp.rpg.protocol.Ref;
import se.hirt.mcp.rpg.protocol.RpgException;
import se.hirt.mcp.rpg.spatial.SpatialRules;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static se.hirt.mcp.rpg.TestCampaigns.*;

class Run5SceneLifecycleTest {

	@Test
	void automaticFreshDeploymentStaysInTheKnownRoomBeforeSeekingDistanceFromAFoe() throws Exception {
		try (Engine e = engine(tempDb("run5-known-room-deployment"))) {
			String campaign = ready(e), site = opening(e, campaign);
			var proposal = lockedTavern(site);
			proposal.put("actors", List.of(map("key", "clerk", "room", "taproom", "creature_ref", "Bandit", "name",
					"Clerk", "x_ft", 5, "y_ft", 5)));
			var published = publish(e, campaign, proposal);
			String tavern = published.get("map_ref").toString();
			String clerk = SceneService.map(published.get("references")).get("actor:clerk").toString();
			e.scenes().place(op(), campaign, tavern, List.of(position("character:1", 0, 0)));
			String taproom = location(e, "character:1");
			e.rest().override(op(), campaign, "SET_LOCATION", "character:1",
					map("location", taproom, "clear_map_position", true),
					"The party remains in the taproom without a deployed marker.", null);
			Map<String, Object> clerkBefore = actor(e, campaign, clerk).asMap();
			var fight = start(e, campaign, List.of("character:1"), List.of(clerk), Map.of());
			assertEquals(tavern, board(fight));
			var footprint = SceneService.footprint(actor(e, campaign, "character:1"));
			assertTrue(footprint.bounds().maxX() <= 20 * 12,
					"Distance preference must never select the locked vault floor.");
			assertEquals(10 * 12, SpatialRules.distance(footprint, SceneService.footprint(actor(e, campaign, clerk))),
					"Use the greatest valid separation inside the known 20ft taproom.");
			assertEquals(taproom, location(e, "character:1"));
			assertEquals(clerkBefore, actor(e, campaign, clerk).asMap());
		}
	}

	@Test
	void newerPublishedRealizationCanGatherProvisionalParticipantsWithItsExistingAuthoredNpcs() throws Exception {
		try (Engine e = engine(tempDb("run5-published-upgrade-split"))) {
			String campaign = ready(e), oldFoe = npc(e, campaign, "Earlier guest");
			var old = start(e, campaign, List.of("character:1"), List.of(oldFoe), Map.of());
			end(e, campaign, old);
			long playerMarker = actor(e, campaign, "character:1").id(), guestMarker = actor(e, campaign, oldFoe).id();
			var published = publish(e, campaign,
					map("name", "Realized tavern", "location_ref", opening(e, campaign), "room_count", 1, "geometry",
							map("bounds_ft", List.of(0, 0, 60, 40)), "rooms",
							List.of(map("key", "taproom", "rect_ft", List.of(0, 0, 60, 40))), "actors",
							List.of(map("key", "clerk", "room", "taproom", "creature_ref", "Bandit", "name",
									"Authored clerk", "x_ft", 5, "y_ft", 5))));
			String tavern = published.get("map_ref").toString();
			String clerk = SceneService.map(published.get("references")).get("actor:clerk").toString();
			Map<String, Object> clerkBefore = actor(e, campaign, clerk).asMap();
			long cid = Ref.id(campaign, Ref.CAMPAIGN);
			long loot = e.db().read(tx -> tx.count("SELECT COUNT(*) FROM inventory_entry WHERE campaign_id=?", cid));
			var realized = start(e, campaign, List.of("character:1"), List.of(oldFoe, clerk), Map.of());
			assertEquals(tavern, board(realized));
			assertEquals(playerMarker, actor(e, campaign, "character:1").id());
			assertEquals(guestMarker, actor(e, campaign, oldFoe).id());
			assertEquals(clerkBefore, actor(e, campaign, clerk).asMap());
			assertEquals(Ref.id(tavern, Ref.MAP), actor(e, campaign, oldFoe).lng("scene_id"));
			assertEquals(loot, e.db()
					.read(tx -> tx.count("SELECT COUNT(*) FROM inventory_entry WHERE campaign_id=?", cid)).longValue());
			end(e, campaign, realized);
			assertEquals(tavern, board(start(e, campaign, List.of("character:1"), List.of(oldFoe, clerk), Map.of())));
			assertEquals(2L,
					e.db().read(tx -> tx.count(
							"SELECT COUNT(*) FROM map_scene WHERE campaign_id=? AND kind IN ('SCENE','DUNGEON')", cid))
							.longValue());
		}
	}

	@Test
	void npcKeepsItsRefinedCanonicalRoomWhenRebindingToAGenericSiblingAndBackWithoutEnteringTheVault()
			throws Exception {
		try (Engine e = engine(tempDb("run5-npc-sibling-both-directions"))) {
			String campaign = ready(e), site = opening(e, campaign), pell = npc(e, campaign, "Pell"),
					other = npc(e, campaign, "Cellar witness");
			var published = publish(e, campaign, lockedTavern(site));
			String tavern = published.get("map_ref").toString();
			String taproom = SceneService.map(published.get("references")).get("room:taproom").toString();
			e.scenes().place(op(), campaign, tavern, List.of(position("character:1", 5, 5)));
			var initial = start(e, campaign, List.of("character:1"), List.of(pell), map("map_ref", tavern, "placements",
					List.of(position("character:1", 5, 5), position(pell, 10, 5))));
			end(e, campaign, initial);
			assertEquals(taproom, location(e, pell), "Actual deployment refines the NPC into its authored room.");
			long marker = actor(e, campaign, pell).id();
			var cellar = start(e, campaign, List.of("character:1"), List.of(other),
					map("scene", map("name", "Taproom representation", "bounds_ft", List.of(0, 0, 30, 20)),
							"placements", List.of(position("character:1", 5, 5), position(other, 25, 5))));
			end(e, campaign, cellar);
			e.scenes().place(op(), campaign, board(cellar), List.of(position(pell, 15, 5)));
			assertEquals(marker, actor(e, campaign, pell).id());
			assertEquals(taproom, location(e, pell), "Changing a representation preserves the actual canonical room.");
			var before = e.db().snapshot(Ref.id(campaign, Ref.CAMPAIGN));
			assertThrows(RpgException.class,
					() -> e.scenes().place(op(), campaign, tavern, List.of(position(pell, 30, 5))));
			assertEquals(before, e.db().snapshot(Ref.id(campaign, Ref.CAMPAIGN)));
			e.scenes().place(op(), campaign, tavern, List.of(position(pell, 10, 5)));
			assertEquals(marker, actor(e, campaign, pell).id());
			assertEquals(Ref.id(tavern, Ref.MAP), actor(e, campaign, pell).lng("scene_id"));
			assertEquals(taproom, location(e, pell));
		}
	}

	@Test
	void npcWithParentCanonicalLocationCanReturnFromSiblingButCannotCrossTheLockedRoom() throws Exception {
		try (Engine e = engine(tempDb("run5-npc-sibling"))) {
			String campaign = ready(e), site = opening(e, campaign), npc = npc(e, campaign, "Pell");
			Map<String, Object> published = publish(e, campaign,
					map("name", "Tavern", "location_ref", site, "room_count", 2, "illumination", "BRIGHT", "geometry",
							map("bounds_ft", List.of(0, 0, 40, 20)), "rooms",
							List.of(map("key", "taproom", "rect_ft", List.of(0, 0, 20, 20)),
									map("key", "vault", "rect_ft", List.of(20, 0, 20, 20))),
							"connections",
							List.of(map("key", "vault-door", "from", "taproom", "to", "vault", "kind", "DOOR", "state",
									"LOCKED", "geometry", map("from_ft", List.of(20, 5), "to_ft", List.of(20, 10))))));
			String tavern = published.get("map_ref").toString();
			e.scenes().place(op(), campaign, tavern, List.of(position("character:1", 5, 5)));
			String room = location(e, "character:1");
			var cellar = start(e, campaign, List.of("character:1"), List.of(npc),
					map("scene", map("name", "Taproom cellar representation", "bounds_ft", List.of(0, 0, 30, 20)),
							"placements", List.of(position("character:1", 5, 5), position(npc, 20, 5))));
			String cellarRef = board(cellar);
			end(e, campaign, cellar);
			assertEquals(site, location(e, npc),
					"Reproduce the NPC whose canonical location still names the parent site.");
			long marker = actor(e, campaign, npc).id();
			var before = e.db().snapshot(Ref.id(campaign, Ref.CAMPAIGN));
			RpgException blocked = assertThrows(RpgException.class,
					() -> e.scenes().place(op(), campaign, tavern, List.of(position(npc, 30, 5))));
			assertTrue(blocked.getMessage().contains("same canonical room"), blocked.getMessage());
			assertEquals(before, e.db().snapshot(Ref.id(campaign, Ref.CAMPAIGN)));
			e.scenes().place(op(), campaign, tavern, List.of(position(npc, 10, 5)));
			assertEquals(marker, actor(e, campaign, npc).id());
			assertEquals(Ref.id(tavern, Ref.MAP), actor(e, campaign, npc).lng("scene_id"));
			assertEquals(room, location(e, npc));
			assertNotEquals(Ref.id(cellarRef, Ref.MAP), actor(e, campaign, npc).lng("scene_id"));
		}
	}

	@Test
	void plainStartRefusesToSilentlyGatherAPartySplitAcrossSiblingBoards() throws Exception {
		try (Engine e = engine(tempDb("run5-split-siblings"))) {
			String campaign = ready(e), ally = npc(e, campaign, "Oddny"), foe = npc(e, campaign, "Clerk");
			e.party().updateMembership(op(), campaign, ally, "JOIN", "A companion in the taproom.");
			String tavern = publish(e, campaign,
					map("name", "Tavern", "location_ref", opening(e, campaign), "room_count", 1, "geometry",
							map("bounds_ft", List.of(0, 0, 60, 30)), "rooms",
							List.of(map("key", "taproom", "rect_ft", List.of(0, 0, 60, 30)))))
					.get("map_ref").toString();
			e.scenes().place(op(), campaign, tavern,
					List.of(position("character:1", 5, 5), position(ally, 10, 5), position(foe, 30, 5)));
			var cellar = start(e, campaign, List.of("character:1"), List.of(foe),
					map("scene", map("name", "Cellar sibling", "bounds_ft", List.of(0, 0, 40, 20)), "placements",
							List.of(position("character:1", 5, 5), position(foe, 25, 5))));
			end(e, campaign, cellar);
			var before = e.db().snapshot(Ref.id(campaign, Ref.CAMPAIGN));
			RpgException failure = assertThrows(RpgException.class,
					() -> start(e, campaign, List.of("character:1", ally), List.of(foe), Map.of()));
			assertTrue(failure.getMessage().contains("different boards"));
			assertTrue(failure.getMessage().contains(tavern));
			assertTrue(failure.getMessage().contains(board(cellar)));
			assertTrue(failure.getMessage().contains("Oddny"));
			assertEquals(before, e.db().snapshot(Ref.id(campaign, Ref.CAMPAIGN)));
			e.scenes().place(op(), campaign, board(cellar), List.of(position(ally, 10, 5)));
			assertEquals(board(cellar),
					board(start(e, campaign, List.of("character:1", ally), List.of(foe), Map.of())));
		}
	}

	@Test
	void auditedSamePlaceReleasePreservesLocationAndCheckpointAndRefusesAnActiveParticipant() throws Exception {
		try (Engine e = engine(tempDb("run5-same-place-release"))) {
			String campaign = ready(e), foe = npc(e, campaign, "Pell");
			var started = start(e, campaign, List.of("character:1"), List.of(foe), Map.of());
			String here = location(e, foe), map = board(started);
			var before = e.db().snapshot(Ref.id(campaign, Ref.CAMPAIGN));
			assertThrows(RpgException.class, () -> e.rest().override(op(), campaign, "SET_LOCATION", foe,
					map("location", here, "clear_map_position", true), "Release a stale same-site marker.", null));
			assertEquals(before, e.db().snapshot(Ref.id(campaign, Ref.CAMPAIGN)));
			end(e, campaign, started);
			long marker = actor(e, campaign, foe).id();
			e.rest().override(op(), campaign, "SET_LOCATION", foe, map("location", here), "Confirm the same location.",
					null);
			assertEquals(marker, actor(e, campaign, foe).id());
			String checkpoint = e.checkpoints().create(op(), campaign, "Before releasing Pell's marker")
					.get("checkpoint").toString();
			var released = e.rest().override(op(), campaign, "SET_LOCATION", foe,
					map("location", here, "clear_map_position", true),
					"Pell is here; discard only the stale board binding.", null);
			assertTrue(released.toString().contains("cleared_stale_map_position"));
			assertEquals(here, location(e, foe));
			assertEquals(0L, e.db().read(
					tx -> tx.count("SELECT COUNT(*) FROM map_actor WHERE character_id=?", Ref.id(foe, Ref.CHARACTER)))
					.longValue());
			e.checkpoints().restore(op(), campaign, checkpoint, "Restore the original same-site marker.");
			assertEquals(marker, actor(e, campaign, foe).id());
			assertEquals(Ref.id(map, Ref.MAP), actor(e, campaign, foe).lng("scene_id"));
		}
	}

	@Test
	void freshPartyDeploymentSeparatesFromSavedFoesAndPreservesTheirAuthoredCells() throws Exception {
		for (int width : List.of(40, 60)) {
			try (Engine e = engine(tempDb("run5-symmetric-deployment-" + width))) {
				String campaign = ready(e), ally = npc(e, campaign, "Companion");
				e.party().updateMembership(op(), campaign, ally, "JOIN", "Arrives with the player.");
				var published = publish(e, campaign,
						map("name", "Clerk's hall", "location_ref", opening(e, campaign), "room_count", 1, "geometry",
								map("bounds_ft", List.of(0, 0, width, 30)), "rooms",
								List.of(map("key", "hall", "rect_ft", List.of(0, 0, width, 30))), "actors",
								List.of(map("key", "clerk", "room", "hall", "creature_ref", "Bandit", "name", "Clerk",
										"x_ft", 15, "y_ft", 15))));
				String foe = SceneService.map(published.get("references")).get("actor:clerk").toString();
				Map<String, Object> original = actor(e, campaign, foe).asMap();
				var fight = start(e, campaign, List.of("character:1", ally), List.of(foe), Map.of());
				for (String member : List.of("character:1", ally))
					assertEquals((width == 40 ? 20 : 30) * 12,
							SpatialRules.distance(SceneService.footprint(actor(e, campaign, member)),
									SceneService.footprint(actor(e, campaign, foe))));
				assertEquals(original, actor(e, campaign, foe).asMap());
				end(e, campaign, fight);
				var saved = actor(e, campaign, "character:1").asMap();
				start(e, campaign, List.of("character:1", ally), List.of(foe), Map.of());
				assertEquals(saved, actor(e, campaign, "character:1").asMap());
			}
		}
	}

	private static String ready(Engine e) {
		String c = committedCampaign(e);
		e.sessions().bootstrap(op(), c, null);
		return c;
	}

	private static Map<String, Object> lockedTavern(String site) {
		return map("name", "Tavern", "location_ref", site, "room_count", 2, "illumination", "BRIGHT", "geometry",
				map("bounds_ft", List.of(0, 0, 40, 20)), "rooms",
				List.of(map("key", "taproom", "rect_ft", List.of(0, 0, 20, 20)),
						map("key", "vault", "rect_ft", List.of(20, 0, 20, 20))),
				"connections", List.of(map("key", "vault-door", "from", "taproom", "to", "vault", "kind", "DOOR",
						"state", "LOCKED", "geometry", map("from_ft", List.of(20, 5), "to_ft", List.of(20, 10)))));
	}

	private static String opening(Engine e, String campaign) {
		return e.db().read(tx -> Ref.of(Ref.LOCATION,
				tx.get("campaign", Ref.id(campaign, Ref.CAMPAIGN)).lng("current_location_id")));
	}

	private static String location(Engine e, String actor) {
		return e.db()
				.read(tx -> Ref.of(Ref.LOCATION, tx.get("character", Ref.id(actor, Ref.CHARACTER)).lng("location_id")));
	}

	private static Row actor(Engine e, String campaign, String actor) {
		return e.db().read(tx -> SceneService.actor(tx, Ref.id(campaign, Ref.CAMPAIGN), actor));
	}

	private static Map<String, Object> position(String actor, int x, int y) {
		return map("character", actor, "x_ft", x, "y_ft", y);
	}

	private static String npc(Engine e, String campaign, String name) {
		return e.runtime().materialize(op(), campaign, "Bandit", name, null, null, null, null, false).get("character")
				.toString();
	}

	private static Map<String, Object> publish(Engine e, String campaign, Map<String, Object> proposal) {
		var draft = e.maps().begin(op(), campaign, "SCENE", proposal);
		return e.maps().publish(op(), campaign, draft.get("preparation").toString(), null);
	}

	private static Map<String, Object> start(
		Engine e, String campaign, List<String> party, List<String> foes, Map<String, Object> options) {
		return e.encounters().start(op(), campaign, map("party", party, "foes", foes), null, null, null, null, null,
				options);
	}

	private static String board(Map<String, Object> started) {
		return SceneService.map(started.get("grid")).get("map_ref").toString();
	}

	private static void end(Engine e, String campaign, Map<String, Object> started) {
		e.encounters().end(op(), campaign, started.get("encounter").toString(), "OTHER", "The scene ends.");
	}
}
