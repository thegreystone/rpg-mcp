/* Copyright (C) 2026 Marcus Hirt. SPDX-License-Identifier: BSD-3-Clause */
package se.hirt.mcp.rpg;

import org.junit.jupiter.api.Test;
import se.hirt.mcp.rpg.dice.ScriptedRollService;
import se.hirt.mcp.rpg.maps.SceneService;
import se.hirt.mcp.rpg.protocol.Ref;
import se.hirt.mcp.rpg.protocol.RpgException;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static se.hirt.mcp.rpg.TestCampaigns.*;

class Run4ScenePerceptionTest {

	@Test
	void aLivingGuardHoldsTheFiveFootCorridorButItsSavedCorpseDoesNotBlockEitherMovementService() throws Exception {
		try (Engine e = engine(tempDb("run4-corpse-choke"))) {
			String campaign = ready(e);
			Map<String, Object> published = publish(e, campaign,
					map("name", "Five-foot corridor", "location_ref", opening(e, campaign), "room_count", 1,
							"illumination", "BRIGHT", "geometry", map("bounds_ft", List.of(0, 0, 40, 5)), "rooms",
							List.of(map("key", "corridor", "rect_ft", List.of(0, 0, 40, 5))), "actors",
							List.of(map("key", "guard", "room", "corridor", "creature_ref", "Bandit", "name",
									"Door guard", "x_ft", 10, "y_ft", 0))));
			String mapRef = published.get("map_ref").toString();
			String guard = SceneService.map(published.get("references")).get("actor:guard").toString();
			e.scenes().place(op(), campaign, mapRef, List.of(position("character:1", 0, 0, 0)));
			assertEquals("NO_PATH", e.scenes().preview(campaign, mapRef, "character:1", 20, 0, "GM").get("status"));
			((ScriptedRollService) e.roller()).queue(20, 1);
			String encounter = e.encounters()
					.start(op(), campaign, map("party", List.of("character:1"), "guards", List.of(guard)), null, null,
							null, null, null, map("map_ref", mapRef))
					.get("encounter").toString();
			long cid = Ref.id(campaign, Ref.CAMPAIGN), gid = Ref.id(guard, Ref.CHARACTER);
			var blocked = e.encounters().perform(op(), campaign, encounter, "character:1",
					map("kind", "MOVE", "x_ft", 20, "y_ft", 0), false);
			assertEquals(false, blocked.get("completed"));
			assertTrue(blocked.containsKey("interruption"));
			assertEquals(List.of(5.0, 0.0), blocked.get("position_ft"),
					"Movement may approach the guard but must stop before its occupied choke cell.");
			assertEquals(5.0, ((Number) blocked.get("movement_cost_ft")).doubleValue());
			assertFalse(((List<?>) blocked.get("path_ft")).contains(List.of(10.0, 0.0)));
			assertEquals(List.of(10.0, 0.0), e.db().read(tx -> {
				var guardPlacement = SceneService.actor(tx, cid, guard);
				return List.of(guardPlacement.get("x_ft"), guardPlacement.get("y_ft"));
			}));
			long markerId = e.db().read(tx -> SceneService.actor(tx, cid, guard).id());
			long hp = e.db().read(tx -> tx.get("character", gid).lng("current_hp"));
			e.runtime().applyRuntimeChange(op(), campaign, guard,
					map("kind", "DAMAGE", "amount", hp * 2, "reason", "A separately resolved fatal hazard."));
			assertEquals("DEAD", e.db().read(tx -> tx.get("character", gid).str("life_state")));
			assertEquals(markerId, e.db().read(tx -> SceneService.actor(tx, cid, guard).id()).longValue());
			assertTrue(SceneService.maps(view(e, campaign, mapRef, "GM", 0).get("actors")).stream()
					.anyMatch(actor -> guard.equals(actor.get("character_ref"))), "The corpse marker is retained.");
			assertEquals("FOUND", e.scenes().preview(campaign, mapRef, "character:1", 20, 0, "GM").get("status"));
			var crossed = e.encounters().perform(op(), campaign, encounter, "character:1",
					map("kind", "MOVE", "x_ft", 20, "y_ft", 0), false);
			assertEquals(List.of(20.0, 0.0), crossed.get("position_ft"));
			assertTrue(((List<?>) crossed.get("path_ft")).contains(List.of(10.0, 0.0)));
			e.encounters().end(op(), campaign, encounter, "OTHER", "The corridor is clear.");
			var walkedBack = e.scenes().move(op(), campaign, mapRef, "character:1", 0, 0);
			assertTrue(((List<?>) walkedBack.get("path_ft")).contains(List.of(10.0, 0.0)));
			assertEquals(markerId, e.db().read(tx -> SceneService.actor(tx, cid, guard).id()).longValue());
		}
	}

	@Test
	void actorSoundReachesOnlyOtherLivingConsciousHearingListeners() throws Exception {
		try (Engine e = engine(tempDb("run4-sound-listeners"))) {
			String campaign = ready(e);
			Map<String, Object> published = publish(e, campaign, map("name", "Bright assembly hall", "location_ref",
					opening(e, campaign), "room_count", 1, "illumination", "BRIGHT", "geometry",
					map("bounds_ft", List.of(0, 0, 60, 20)), "rooms",
					List.of(map("key", "hall", "rect_ft", List.of(0, 0, 60, 20))), "actors",
					List.of(guard("listener", 10), guard("deaf", 20), guard("unconscious", 30), guard("dead", 40))));
			String mapRef = published.get("map_ref").toString();
			Map<String, Object> refs = SceneService.map(published.get("references"));
			String listener = refs.get("actor:listener").toString(), deaf = refs.get("actor:deaf").toString(),
					unconscious = refs.get("actor:unconscious").toString(), dead = refs.get("actor:dead").toString();
			e.scenes().place(op(), campaign, mapRef, List.of(position("character:1", 0, 0, 0)));
			e.runtime().applyRuntimeChange(op(), campaign, deaf, map("kind", "ADD_CONDITION", "condition", "DEAFENED",
					"reason", "An established deafening effect."));
			e.runtime().applyRuntimeChange(op(), campaign, unconscious,
					map("kind", "ADD_CONDITION", "condition", "UNCONSCIOUS", "reason", "An established sleep effect."));
			long deadId = Ref.id(dead, Ref.CHARACTER);
			long hp = e.db().read(tx -> tx.get("character", deadId).lng("current_hp"));
			e.runtime().applyRuntimeChange(op(), campaign, dead,
					map("kind", "DAMAGE", "amount", hp * 2, "reason", "The old guard is dead."));
			Map<String, Object> sound = e.scenes().sound(op(), campaign, mapRef, null, null, null, 120,
					"A sharp warning at the door.", "character:1");
			assertEquals(Set.of(listener), Set.copyOf((List<?>) sound.get("heard_by")));
			Map<String, Object> heard = SceneService
					.map(e.scenes().perception(campaign, mapRef, listener).get("evidence"));
			assertEquals("SUSPICIOUS", heard.get("awareness"));
			assertEquals("A sharp warning at the door.", SceneService.map(heard.get("heard")).get("description"));
			for (String excluded : List.of("character:1", deaf, unconscious, dead)) {
				var evidence = SceneService.map(e.scenes().perception(campaign, mapRef, excluded).get("evidence"));
				assertFalse(evidence.containsKey("heard"), excluded + " must not receive this sound cue.");
				assertNotEquals("SUSPICIOUS", evidence.get("awareness"));
			}
		}
	}

	@Test
	void thePartyCanPeacefullyEnterAPublishedBoardWithoutReplacingItsCharacterOrOldMemory() throws Exception {
		try (Engine e = engine(tempDb("run4-peaceful-board"))) {
			String campaign = ready(e), opening = opening(e, campaign);
			String guard = e.runtime()
					.materialize(op(), campaign, "Bandit", "Passing guest", null, null, null, null, false)
					.get("character").toString();
			e.runtime().applyRuntimeChange(op(), campaign, "character:1",
					map("kind", "DAMAGE", "amount", 1, "reason", "An established scratch."));
			e.runtime().applyRuntimeChange(op(), campaign, "character:1",
					map("kind", "ADD_CONDITION", "condition", "POISONED", "reason", "An established poison."));
			((ScriptedRollService) e.roller()).queue(20, 1);
			var started = e.encounters().start(op(), campaign,
					map("party", List.of("character:1"), "guests", List.of(guard)), null, null, null, null, null,
					map("scene",
							map("name", "Improvised tavern", "bounds_ft", List.of(0, 0, 40, 20), "illumination",
									"BRIGHT"),
							"placements", List.of(position("character:1", 5, 5, 0), position(guard, 25, 5, 0))));
			String oldMap = SceneService.map(started.get("grid")).get("map_ref").toString();
			e.encounters().end(op(), campaign, started.get("encounter").toString(), "NEGOTIATED",
					"The guests settle their differences.");
			long cid = Ref.id(campaign, Ref.CAMPAIGN), oldMapId = Ref.id(oldMap, Ref.MAP);
			long marker = e.db().read(tx -> SceneService.actor(tx, cid, "character:1").id());
			long hp = e.db().read(tx -> tx.get("character", 1).lng("current_hp"));
			var effects = e.db().read(tx -> tx.query("SELECT * FROM active_effect WHERE character_id=1").stream()
					.map(row -> row.asMap()).toList());
			var oldMemory = e.db().read(tx -> tx
					.queryOne("SELECT * FROM map_observation WHERE scene_id=? AND observer_key='party'", oldMapId)
					.orElseThrow().map("knowledge_json"));
			Map<String, Object> published = publish(e, campaign,
					map("name", "The proper taproom", "location_ref", opening, "room_count", 1, "illumination",
							"BRIGHT", "geometry", map("bounds_ft", List.of(0, 0, 40, 20)), "rooms",
							List.of(map("key", "taproom", "rect_ft", List.of(0, 0, 40, 20)))));
			String newMap = published.get("map_ref").toString();
			e.scenes().place(op(), campaign, newMap, List.of(position("character:1", 5, 5, 0)));
			assertEquals(0L, e.db().read(tx -> tx.count(
					"SELECT COUNT(*) FROM encounter WHERE campaign_id=? AND status IN ('RUNNING','WAITING_CHOICE')",
					cid)).longValue());
			assertEquals(marker, e.db().read(tx -> SceneService.actor(tx, cid, "character:1").id()).longValue());
			assertEquals(Ref.id(newMap, Ref.MAP),
					e.db().read(tx -> SceneService.actor(tx, cid, "character:1").lng("scene_id")).longValue());
			assertEquals(hp, e.db().read(tx -> tx.get("character", 1).lng("current_hp")).longValue());
			assertEquals(effects, e.db().read(tx -> tx.query("SELECT * FROM active_effect WHERE character_id=1")
					.stream().map(row -> row.asMap()).toList()));
			assertEquals(oldMemory, e.db().read(tx -> tx
					.queryOne("SELECT * FROM map_observation WHERE scene_id=? AND observer_key='party'", oldMapId)
					.orElseThrow().map("knowledge_json")));
			var beforeReposition = e.db().snapshot(cid);
			assertThrows(RpgException.class,
					() -> e.scenes().place(op(), campaign, newMap, List.of(position("character:1", 10, 5, 0))));
			assertEquals(beforeReposition, e.db().snapshot(cid));
			e.scenes().move(op(), campaign, newMap, "character:1", 10, 5);
		}
	}

	@Test
	void changingBoardsCannotMoveThePartyOutOfALockedCanonicalRoom() throws Exception {
		try (Engine e = engine(tempDb("run4-rebind-locked-room"))) {
			String campaign = ready(e), site = opening(e, campaign);
			Map<String, Object> first = publish(e, campaign, vaultLayout(site, null, null));
			String firstMap = first.get("map_ref").toString();
			Map<String, Object> refs = SceneService.map(first.get("references"));
			String entrance = refs.get("room:entrance").toString(), vault = refs.get("room:vault").toString();
			e.scenes().place(op(), campaign, firstMap, List.of(position("character:1", 25, 5, 0)));
			Map<String, Object> second = publish(e, campaign, vaultLayout(site, entrance, vault));
			String secondMap = second.get("map_ref").toString();
			String guard = e.runtime()
					.materialize(op(), campaign, "Bandit", "Vault guard", null, null, null, null, false)
					.get("character").toString();
			long cid = Ref.id(campaign, Ref.CAMPAIGN), vaultId = Ref.id(vault, Ref.LOCATION);
			long marker = e.db().read(tx -> SceneService.actor(tx, cid, "character:1").id());
			assertEquals(vaultId, e.db().read(tx -> tx.get("character", 1).lng("location_id")).longValue());
			var before = e.db().snapshot(cid);
			RpgException placement = assertThrows(RpgException.class,
					() -> e.scenes().place(op(), campaign, secondMap, List.of(position("character:1", 5, 5, 0))));
			assertTrue(placement.getMessage().contains("same canonical room"));
			assertEquals(before, e.db().snapshot(cid));
			RpgException combat = assertThrows(RpgException.class,
					() -> e.encounters().start(op(), campaign,
							map("party", List.of("character:1"), "guards", List.of(guard)), null, null, null, null,
							null, map("map_ref", secondMap, "placements",
									List.of(position("character:1", 5, 5, 0), position(guard, 30, 5, 0)))));
			assertTrue(combat.getMessage().contains("same canonical room"));
			assertEquals(before, e.db().snapshot(cid), "Starting a fight cannot bypass the locked passage either.");
			e.scenes().place(op(), campaign, secondMap, List.of(position("character:1", 25, 5, 0)));
			assertEquals(vaultId, e.db().read(tx -> tx.get("character", 1).lng("location_id")).longValue());
			assertEquals(marker, e.db().read(tx -> SceneService.actor(tx, cid, "character:1").id()).longValue());
			assertEquals(Ref.id(secondMap, Ref.MAP),
					e.db().read(tx -> SceneService.actor(tx, cid, "character:1").lng("scene_id")).longValue());
			assertEquals("NO_PATH", e.scenes().preview(campaign, secondMap, "character:1", 5, 5, "GM").get("status"));
		}
	}

	@Test
	void objectMemoryStaysOnItsOwnLevelAndMarksOutOfSightObjectsAsLastKnown() throws Exception {
		try (Engine e = engine(tempDb("run4-object-memory-levels"))) {
			String campaign = ready(e);
			Map<String, Object> published = publish(e, campaign,
					map("name", "Two bright floors", "location_ref", opening(e, campaign), "room_count", 3, "levels",
							List.of(-1, 0), "illumination", "BRIGHT", "geometry",
							map("bounds_ft", List.of(0, 0, 40, 20)), "rooms",
							List.of(map("key", "lower", "level", -1, "rect_ft", List.of(0, 0, 20, 20)),
									map("key", "upper-west", "level", 0, "rect_ft", List.of(0, 0, 20, 20)),
									map("key", "upper-east", "level", 0, "rect_ft", List.of(20, 0, 20, 20))),
							"connections",
							List.of(map("key", "stairs", "kind", "STAIRS", "from", "lower", "to", "upper-west",
									"from_position_ft", List.of(5, 5), "to_position_ft", List.of(5, 5), "movement_ft",
									5),
									map("key", "upper-door", "from", "upper-west", "to", "upper-east", "from_ft",
											List.of(20, 5), "to_ft", List.of(20, 10), "state", "OPEN")),
							"objects",
							List.of(map("key", "lower-gong", "name", "The lower bronze gong", "room", "lower", "level",
									-1, "x_ft", 10, "y_ft", 10),
									map("key", "upper-idol", "name", "The upper marble idol", "room", "upper-east",
											"level", 0, "x_ft", 25, "y_ft", 10))));
			String mapRef = published.get("map_ref").toString();
			e.scenes().place(op(), campaign, mapRef, List.of(position("character:1", 5, 5, -1)));
			assertNotNull(object(view(e, campaign, mapRef, "PLAYER", -1), "lower-gong"));
			e.scenes().transit(op(), campaign, mapRef, "character:1", "stairs");
			Map<String, Object> upper = view(e, campaign, mapRef, "PLAYER", 0);
			assertFalse(object(upper, "upper-idol").containsKey("last_known"));
			assertTrue(SceneService.maps(upper.get("objects")).stream()
					.noneMatch(row -> row.get("key").toString().endsWith("lower-gong")));
			e.scenes().changeObject(op(), campaign, mapRef, "upper-door", map("state", "CLOSED"),
					"The door closes behind the party.");
			assertEquals(true, object(view(e, campaign, mapRef, "PLAYER", 0), "upper-idol").get("last_known"));
			assertEquals(true, object(view(e, campaign, mapRef, "PLAYER", -1), "lower-gong").get("last_known"));
			assertTrue(SceneService.maps(view(e, campaign, mapRef, "PLAYER", 0).get("objects")).stream()
					.allMatch(row -> ((Number) row.getOrDefault("level", 0)).intValue() == 0));
		}
	}

	private static String ready(Engine e) {
		String campaign = committedCampaign(e);
		e.sessions().bootstrap(op(), campaign, null);
		return campaign;
	}

	private static Map<String, Object> vaultLayout(String site, String entranceRef, String vaultRef) {
		Map<String, Object> entrance = map("key", "entrance", "rect_ft", List.of(0, 0, 20, 20));
		Map<String, Object> vault = map("key", "vault", "rect_ft", List.of(20, 0, 20, 20));
		if (entranceRef != null)
			entrance.put("location_ref", entranceRef);
		if (vaultRef != null)
			vault.put("location_ref", vaultRef);
		return map("name", "The locked vault", "location_ref", site, "room_count", 2, "illumination", "BRIGHT",
				"geometry", map("bounds_ft", List.of(0, 0, 40, 20)), "rooms", List.of(entrance, vault), "connections",
				List.of(map("key", "vault-door", "from", "entrance", "to", "vault", "from_ft", List.of(20, 5), "to_ft",
						List.of(20, 10), "state", "LOCKED")));
	}

	private static String opening(Engine e, String campaign) {
		return e.db().read(tx -> Ref.of(Ref.LOCATION,
				tx.get("campaign", Ref.id(campaign, Ref.CAMPAIGN)).lng("current_location_id")));
	}

	private static Map<String, Object> publish(Engine e, String campaign, Map<String, Object> proposal) {
		String draft = e.maps().begin(op(), campaign, "SCENE", proposal).get("preparation").toString();
		return e.maps().publish(op(), campaign, draft, null);
	}

	private static Map<String, Object> position(String character, int x, int y, int level) {
		return map("character", character, "x_ft", x, "y_ft", y, "level", level);
	}

	private static Map<String, Object> guard(String key, int x) {
		return map("key", key, "room", "hall", "creature_ref", "Bandit", "name", key, "x_ft", x, "y_ft", 0);
	}

	private static Map<String, Object> view(Engine e, String campaign, String mapRef, String audience, int level) {
		return SceneService.map(e.scenes().context(campaign, mapRef, audience, level).get("view"));
	}

	private static Map<String, Object> object(Map<String, Object> view, String suffix) {
		return SceneService.maps(view.get("objects")).stream().filter(row -> row.get("key").toString().endsWith(suffix))
				.findFirst().orElseThrow();
	}
}
