/* Copyright (C) 2026 Marcus Hirt. SPDX-License-Identifier: BSD-3-Clause */
package se.hirt.mcp.rpg;

import org.junit.jupiter.api.Test;
import se.hirt.mcp.rpg.maps.SceneService;
import se.hirt.mcp.rpg.persistence.Database;
import se.hirt.mcp.rpg.protocol.Json;
import se.hirt.mcp.rpg.protocol.Ref;
import se.hirt.mcp.rpg.protocol.RpgException;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static se.hirt.mcp.rpg.TestCampaigns.*;

class MapChallengeAuthoringTest {
	@Test
	void defaultsAndAuthoredBossTreasureAndOppositionSurviveAcceptanceGeometryReloadAndCheckpoint() throws Exception {
		var file = tempDb("saved-map-challenges");
		String campaign, prep, board, bossChest, dragon;
		try (Engine e = engine(file)) {
			campaign = ready(e);
			var draft = e.maps().begin(op(), campaign, "DUNGEON", map("name", "Dragon treasury", "rooms",
					List.of(map("key", "hoard", "name", "Dragon's hoard")), "objects",
					List.of(map("key", "normal", "kind", "CHEST", "room", "hoard", "locked", true, "item_ref", "Chest"),
							map("key", "boss", "kind", "CHEST", "room", "hoard", "locked", true, "item_ref", "Chest",
									"coins", map("gp", 5000), "challenges",
									map("pick_lock", map("difficulty", 25), "force_open", map("difficulty", 30))),
							map("key", "wire", "kind", "TRAP", "room", "hoard", "concealed", true)),
					"rewards",
					List.of(map("key", "staffs", "room", "hoard", "container", "boss", "item_ref", "Quarterstaff",
							"quantity", 7)),
					"actors", List.of(map("key", "dragon", "room", "hoard", "name", "Treasury dragon", "creature_ref",
							"Adult Red Dragon"))));
			prep = draft.get("preparation").toString();
			var proposal = SceneService.map(draft.get("proposal"));
			assertEquals(15, difficulty(part(proposal, "objects", "normal"), "pick_lock"));
			assertFalse(
					SceneService.map(part(proposal, "objects", "normal").get("challenges")).containsKey("force_open"));
			assertEquals(25, difficulty(part(proposal, "objects", "boss"), "pick_lock"));
			assertEquals(30, difficulty(part(proposal, "objects", "boss"), "force_open"));
			assertEquals(15, difficulty(part(proposal, "objects", "wire"), "discover"));
			assertEquals(15, difficulty(part(proposal, "objects", "wire"), "disarm"));
			assertTrue(draft.get("authoring_guidance").toString().contains("boss/opposition"));
			var accepted = e.maps().publish(op(), campaign, prep, null);
			bossChest = SceneService.map(accepted.get("references")).get("object:boss").toString();
			assertEquals("ACCEPTED", accepted.get("status"));
			var before = e.db().snapshot(Ref.id(campaign, Ref.CAMPAIGN));
			assertThrows(RpgException.class, () -> e.maps().revise(op(), campaign, prep, null, map("objects",
					List.of(map("key", "boss", "challenges", map("pick_lock", map("difficulty", 15)))))));
			assertEquals(before, e.db().snapshot(Ref.id(campaign, Ref.CAMPAIGN)));
			var realized = e.maps().revise(op(), campaign, prep, null, map("stage", "SCENE", "geometry",
					map("bounds_ft", List.of(0, 0, 80, 80)), "rooms",
					List.of(map("key", "hoard", "rect_ft", List.of(0, 0, 80, 80))), "objects",
					List.of(map("key", "normal", "x_ft", 5, "y_ft", 5), map("key", "boss", "x_ft", 10, "y_ft", 5),
							map("key", "wire", "x_ft", 15, "y_ft", 5)),
					"actors", List.of(map("key", "dragon", "x_ft", 30, "y_ft", 30))));
			assertEquals(true, realized.get("ready"), realized.get("violations").toString());
			var published = e.maps().publish(op(), campaign, prep, null);
			board = published.get("map_ref").toString();
			dragon = SceneService.map(published.get("references")).get("actor:dragon").toString();
			assertEquals(bossChest, SceneService.map(published.get("references")).get("object:boss"));
			assertTuning(e, campaign, board, dragon);
			String checkpoint = e.checkpoints().create(op(), campaign, "Published tuned treasury").get("checkpoint")
					.toString();
			e.scenes().changeObject(op(), campaign, board, "object:boss", map("locked", false),
					"The resolved lock check opened this chest.");
			e.checkpoints().restore(op(), campaign, checkpoint, "Restore the original locked treasury.");
			assertTuning(e, campaign, board, dragon);
		}
		try (Engine e = engine(file)) {
			assertTuning(e, campaign, board, dragon);
			assertEquals(25,
					difficulty(part(SceneService.map(e.maps().get(campaign, prep).get("proposal")), "objects", "boss"),
							"pick_lock"));
		}
	}

	@Test
	void bindingAnExistingPassagePreservesItsDifficultyAndLiveStateAndRejectsAConflictingDifficulty() throws Exception {
		try (Engine e = engine(tempDb("challenge-passage-binding"))) {
			String campaign = ready(e);
			var first = e.maps().begin(op(), campaign, "SCENE", scene(map("pick_lock", map("difficulty", 22))));
			var published = e.maps().publish(op(), campaign, first.get("preparation").toString(), null);
			String board = published.get("map_ref").toString();
			var refs = SceneService.map(published.get("references"));
			String connection = refs.get("connection:door").toString();
			e.scenes().changeObject(op(), campaign, board, "door", map("state", "OPEN"),
					"A resolved successful unlock.");
			e.db().mutate(Database.Mutation.of("legacy_partial_challenge_fixture", Ref.id(campaign, Ref.CAMPAIGN), op(),
					"GM", Map.of()), tx -> {
						var passage = tx.get("location_connection", Ref.id(connection, Ref.CONNECTION));
						var state = passage.map("state_json");
						state.put("challenges", map("pick_lock", map("difficulty", 22)));
						tx.update("location_connection", passage.id(), map("state_json", Json.write(state)));
						return map("ok", true);
					});
			var bound = scene(Map.of());
			bound.put("location_ref", refs.get("site"));
			SceneService.maps(bound.get("rooms")).get(0).put("location_ref", refs.get("room:a"));
			SceneService.maps(bound.get("rooms")).get(1).put("location_ref", refs.get("room:b"));
			SceneService.maps(bound.get("connections")).getFirst().put("connection_ref", connection);
			var second = e.maps().begin(op(), campaign, "SCENE", bound);
			assertEquals(22,
					difficulty(part(SceneService.map(second.get("proposal")), "connections", "door"), "pick_lock"));
			e.maps().publish(op(), campaign, second.get("preparation").toString(), null);
			var saved = e.db()
					.read(tx -> tx.get("location_connection", Ref.id(connection, Ref.CONNECTION)).map("state_json"));
			assertEquals("OPEN", saved.get("state"));
			assertEquals(22, difficulty(saved, "pick_lock"));
			assertEquals("SKILL_CHECK",
					SceneService.map(SceneService.map(saved.get("challenges")).get("pick_lock")).get("kind"));
			assertEquals("Thieves' Tools",
					SceneService.map(SceneService.map(saved.get("challenges")).get("pick_lock")).get("tool"));
			SceneService.maps(bound.get("connections")).getFirst().put("challenges",
					map("pick_lock", map("difficulty", 12)));
			var conflicting = e.maps().begin(op(), campaign, "SCENE", bound);
			assertEquals(false, conflicting.get("ready"));
			assertTrue(SceneService.maps(conflicting.get("violations")).stream()
					.anyMatch(v -> "CANONICAL_CHALLENGE_CHANGED".equals(v.get("rule"))));
			var before = e.db().snapshot(Ref.id(campaign, Ref.CAMPAIGN));
			assertThrows(RpgException.class,
					() -> e.maps().publish(op(), campaign, conflicting.get("preparation").toString(), null));
			assertEquals(before, e.db().snapshot(Ref.id(campaign, Ref.CAMPAIGN)));
		}
	}

	@Test
	void malformedCheckDeclarationsStayInvalidRatherThanBeingReplacedByDefaults() throws Exception {
		try (Engine e = engine(tempDb("challenge-validation"))) {
			String campaign = ready(e);
			for (Map<String, Object> malformed : List.of(map("difficulty", 0), map("difficulty", 41),
					map("difficulty", 12.5), map("difficulty", "15"), map("difficulty", null), map("kind", null),
					map("ability", null), map("dc", 15), map("kind", "UNSUPPORTED"),
					map("kind", "SAVING_THROW", "tool", "Thieves' Tools"), map("tool", "Quarterstaff"),
					map("skill", "Invented skill"))) {
				var draft = e.maps().begin(op(), campaign, "DUNGEON", map("rooms", List.of(map("key", "room")),
						"objects",
						List.of(map("key", "lock", "locked", true, "challenges", map("pick_lock", malformed)))));
				assertEquals(false, draft.get("ready"), malformed.toString());
				assertTrue(SceneService.maps(draft.get("violations")).stream()
						.anyMatch(v -> v.get("path").toString().startsWith("objects[lock].challenges.pick_lock")));
				var before = e.db().snapshot(Ref.id(campaign, Ref.CAMPAIGN));
				assertThrows(RpgException.class,
						() -> e.maps().publish(op(), campaign, draft.get("preparation").toString(), null));
				assertEquals(before, e.db().snapshot(Ref.id(campaign, Ref.CAMPAIGN)));
			}
			var nullSpec = e.maps().begin(op(), campaign, "DUNGEON", map("rooms", List.of(map("key", "room")),
					"objects", List.of(map("key", "lock", "locked", true, "challenges", map("pick_lock", null)))));
			assertEquals(false, nullSpec.get("ready"));
			assertNull(SceneService
					.map(part(SceneService.map(nullSpec.get("proposal")), "objects", "lock").get("challenges"))
					.get("pick_lock"));
		}
	}

	@Test
	void publishingALegacyDraftMaterializesMissingGuidanceIntoPayloadAndCanonicalStateAtomically() throws Exception {
		try (Engine e = engine(tempDb("legacy-challenge-draft"))) {
			String campaign = ready(e);
			var draft = e.maps().begin(op(), campaign, "SCENE", scene(Map.of()));
			String prep = draft.get("preparation").toString();
			e.db().mutate(
					Database.Mutation.of("legacy_draft_fixture", Ref.id(campaign, Ref.CAMPAIGN), op(), "GM", Map.of()),
					tx -> {
						var row = tx.get("map_preparation", Ref.id(prep, "preparation"));
						var payload = row.map("payload_json");
						SceneService.maps(payload.get("connections")).getFirst().remove("challenges");
						tx.update("map_preparation", row.id(), map("payload_json", Json.write(payload)));
						return map("ok", true);
					});
			var published = e.maps().publish(op(), campaign, prep, null);
			assertEquals(15,
					difficulty(part(SceneService.map(published.get("proposal")), "connections", "door"), "pick_lock"));
			String ref = SceneService.map(published.get("references")).get("connection:door").toString();
			assertEquals(15,
					e.db().read(tx -> difficulty(
							tx.get("location_connection", Ref.id(ref, Ref.CONNECTION)).map("state_json"), "pick_lock"))
							.intValue());
		}
	}

	private static void assertTuning(Engine e, String campaign, String board, String dragon) {
		var document = SceneService.map(e.maps().get(campaign, board).get("document"));
		assertEquals(15, difficulty(part(document, "objects", "normal"), "pick_lock"));
		var chest = part(document, "objects", "boss");
		assertEquals(25, difficulty(chest, "pick_lock"));
		assertEquals(5000, ((Number) SceneService.map(chest.get("coins")).get("gp")).intValue());
		var reward = part(document, "objects", "staffs");
		assertEquals(7, ((Number) reward.get("quantity")).intValue());
		assertEquals(7,
				e.db().read(tx -> tx.get("inventory_entry", Ref.id(reward.get("entity_ref").toString(), Ref.INVENTORY))
						.intOr("quantity", 0)).intValue());
		assertEquals("srd5e:creature/adult-red-dragon",
				e.db().read(tx -> tx.get("character", Ref.id(dragon, Ref.CHARACTER)).str("origin_content_ref")));
	}

	private static Map<String, Object> scene(Map<String, Object> challenges) {
		return map("name", "Two rooms", "geometry", map("bounds_ft", List.of(0, 0, 40, 20)), "rooms",
				List.of(map("key", "a", "rect_ft", List.of(0, 0, 20, 20)),
						map("key", "b", "rect_ft", List.of(20, 0, 20, 20))),
				"connections", List.of(map("key", "door", "from", "a", "to", "b", "kind", "DOOR", "state", "LOCKED",
						"from_ft", List.of(20, 5), "to_ft", List.of(20, 10), "challenges", challenges)));
	}

	private static Map<String, Object> part(Map<String, Object> proposal, String list, String key) {
		return SceneService.maps(proposal.get(list)).stream()
				.filter(p -> key.equals(p.get("key"))
						|| (list.equals("objects") && ("object:" + key).equals(p.get("key")))
						|| ("reward:" + key).equals(p.get("key")))
				.findFirst().orElseThrow();
	}

	private static int difficulty(Map<String, Object> part, String key) {
		return ((Number) SceneService.map(SceneService.map(part.get("challenges")).get(key)).get("difficulty"))
				.intValue();
	}

	private static String ready(Engine e) {
		String c = committedCampaign(e);
		e.sessions().bootstrap(op(), c, null);
		return c;
	}
}
