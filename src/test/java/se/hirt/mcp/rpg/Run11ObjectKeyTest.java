/* Copyright (C) 2026 Marcus Hirt. SPDX-License-Identifier: BSD-3-Clause */
package se.hirt.mcp.rpg;

import org.junit.jupiter.api.Test;
import se.hirt.mcp.rpg.maps.SceneService;
import se.hirt.mcp.rpg.protocol.ErrorCode;
import se.hirt.mcp.rpg.protocol.Json;
import se.hirt.mcp.rpg.protocol.Ref;
import se.hirt.mcp.rpg.protocol.RpgException;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static se.hirt.mcp.rpg.TestCampaigns.*;

class Run11ObjectKeyTest {

	@Test
	void publishedBareAndCanonicalKeysPreserveDoorIdentityInventoryPrivacyAndReplayAcrossRestore() throws Exception {
		var file = tempDb("run11-object-keys");
		String campaign, board, checkpoint, identities, inventory;
		try (Engine e = engine(file)) {
			campaign = ready(e);
			board = publish(e, campaign);
			boolean inlineObjects = e.db().read(
					tx -> tx.get("map_scene", Ref.id(board, Ref.MAP)).map("document_json").containsKey("objects"));
			assertFalse(inlineObjects,
					"Published objects live once in map_object, rather than twice in alias candidates.");
			identities = identities(e, board);
			inventory = inventory(e, campaign);
			e.scenes().place(op(), campaign, board, List.of(map("character", "character:1", "x_ft", 5, "y_ft", 5)));
			assertFalse(Json.write(view(e, campaign, board, "PLAYER")).contains("Private covered pit"));
			String operation = op();
			String reason = "The adjudicated discovery reveals the covered pit.";
			var revealed = e.scenes().changeObject(operation, campaign, board, "pit", map("concealed", false), reason);
			assertEquals("pit", revealed.get("object"));
			assertEquals("object:pit", revealed.get("canonical_key"));
			assertEquals("OBJECT", revealed.get("feature_kind"));
			var player = view(e, campaign, board, "PLAYER");
			assertEquals("Private covered pit", part(player, "objects", "object:pit").get("name"));
			assertFalse(Json.write(player).contains("challenges"));
			assertFalse(e.mapRenderer().render(player, Map.of("max_pixels", 128)).text().contains("difficulty"));
			var afterReveal = e.db().snapshot(Ref.id(campaign, Ref.CAMPAIGN));
			assertEquals(true, e.scenes()
					.changeObject(operation, campaign, board, "pit", map("concealed", false), reason).get("replayed"));
			assertEquals(afterReveal, e.db().snapshot(Ref.id(campaign, Ref.CAMPAIGN)));
			RpgException changedPayload = assertThrows(RpgException.class, () -> e.scenes().changeObject(operation,
					campaign, board, "object:pit", map("concealed", false), reason));
			assertEquals(ErrorCode.IDEMPOTENCY_CONFLICT, changedPayload.code());
			assertEquals(afterReveal, e.db().snapshot(Ref.id(campaign, Ref.CAMPAIGN)));
			e.scenes().changeObject(op(), campaign, board, "chest", map("locked", false, "state", "OPEN"),
					"The adjudicated check opens the chest.");
			var door = e.scenes().changeObject(op(), campaign, board, "latch", map("state", "OPEN"),
					"The door is opened, not the object sharing its authoring key.");
			assertEquals("latch", door.get("canonical_key"));
			assertEquals("PASSAGE", door.get("feature_kind"));
			assertEquals("CLOSED", part(view(e, campaign, board, "GM"), "objects", "object:latch").get("state"));
			e.scenes().changeObject(op(), campaign, board, "object:latch", map("hit_points", 3),
					"The separate physical latch takes damage.");
			assertEquals("OPEN", part(view(e, campaign, board, "GM"), "doors", "latch").get("state"));
			assertEquals(3, ((Number) part(view(e, campaign, board, "GM"), "objects", "object:latch").get("hit_points"))
					.intValue());
			assertEquals(identities, identities(e, board));
			assertEquals(inventory, inventory(e, campaign));
			checkpoint = e.checkpoints().create(op(), campaign, "The pit is known and the latch remains distinct")
					.get("checkpoint").toString();
			e.scenes().changeObject(op(), campaign, board, "object:pit", map("concealed", true),
					"A deliberate correction conceals the hazard again.");
			assertFalse(Json.write(view(e, campaign, board, "PLAYER")).contains("Private covered pit"));
			String memory = e.db()
					.read(tx -> Json.write(
							tx.queryOne("SELECT * FROM map_observation WHERE scene_id=? AND observer_key='party'",
									Ref.id(board, Ref.MAP)).orElseThrow().map("knowledge_json")));
			assertFalse(memory.contains("Private covered pit"));
		}
		try (Engine e = engine(file)) {
			assertFalse(Json.write(view(e, campaign, board, "PLAYER")).contains("Private covered pit"));
			e.checkpoints().restore(op(), campaign, checkpoint,
					"Restore the resolved discovery without creating any loot.");
			assertEquals("Private covered pit",
					part(view(e, campaign, board, "PLAYER"), "objects", "object:pit").get("name"));
			assertEquals("OPEN", part(view(e, campaign, board, "GM"), "doors", "latch").get("state"));
			assertEquals(identities, identities(e, board));
			assertEquals(inventory, inventory(e, campaign));
		}
	}

	@Test
	void sharedObjectAndRewardAliasRefusesAtomicallyWhileBothExactKeysRemainUsable() throws Exception {
		try (Engine e = engine(tempDb("run11-ambiguous-object-alias"))) {
			String campaign = ready(e), board = publish(e, campaign);
			String identities = identities(e, board), inventory = inventory(e, campaign);
			var before = e.db().snapshot(Ref.id(campaign, Ref.CAMPAIGN));
			RpgException ambiguous = assertThrows(RpgException.class, () -> e.scenes().changeObject(op(), campaign,
					board, "cache", map("state", "DESTROYED"), "The DM must select the intended cache."));
			assertEquals(ErrorCode.INVALID_ARGUMENT, ambiguous.code());
			assertTrue(ambiguous.getMessage().contains("exact canonical GM key"));
			assertEquals(Set.of("object:cache", "reward:cache"),
					Set.copyOf((List<?>) ambiguous.details().get("canonical_keys")));
			assertEquals(before, e.db().snapshot(Ref.id(campaign, Ref.CAMPAIGN)));
			assertEquals("object:cache", e.scenes().changeObject(op(), campaign, board, "object:cache",
					map("state", "DESTROYED"), "The empty cache is broken.").get("canonical_key"));
			assertEquals("reward:cache",
					e.scenes()
							.changeObject(op(), campaign, board, "reward:cache",
									map("label", "Identified ceremonial staff"), "Identify the separate reward.")
							.get("canonical_key"));
			assertEquals(identities, identities(e, board));
			assertEquals(inventory, inventory(e, campaign),
					"An object-state alias never creates, transfers or duplicates loot.");
			var beforeMissing = e.db().snapshot(Ref.id(campaign, Ref.CAMPAIGN));
			assertEquals(ErrorCode.NOT_FOUND, assertThrows(RpgException.class, () -> e.scenes().changeObject(op(),
					campaign, board, "missing", map("state", "OPEN"), "No such object exists.")).code());
			assertEquals(beforeMissing, e.db().snapshot(Ref.id(campaign, Ref.CAMPAIGN)));
		}
	}

	@Test
	void improvisedInlineObjectsAndPassagesRetainExactKeysAndRefuseDuplicateExactMatches() throws Exception {
		try (Engine e = engine(tempDb("run11-inline-object-keys"))) {
			String campaign = ready(e);
			String guard = e.runtime()
					.materialize(op(), campaign, "Bandit", "Alias guard", null, null, null, null, false)
					.get("character").toString();
			var started = e.encounters().start(op(), campaign,
					map("party", List.of("character:1"), "guards", List.of(guard)), null, null, null, null, null,
					map("scene",
							map("name", "Improvised gate", "bounds_ft", List.of(0, 0, 40, 20), "objects",
									List.of(map("key", "bench", "rect_ft", List.of(10, 10, 5, 5), "state",
											"INTACT"), map("key", "duplicate", "rect_ft", List.of(25, 10, 5, 5))),
									"doors",
									List.of(map("key", "exit", "from_ft", List.of(20, 0), "to_ft", List.of(20, 5),
											"state", "CLOSED"),
											map("key", "duplicate", "from_ft", List.of(20, 15), "to_ft",
													List.of(20, 20), "state", "CLOSED"))),
							"placements", List.of(map("character", "character:1", "x_ft", 5, "y_ft", 5),
									map("character", guard, "x_ft", 30, "y_ft", 5))));
			String board = SceneService.map(started.get("grid")).get("map_ref").toString();
			e.encounters().end(op(), campaign, started.get("encounter").toString(), "OTHER", "The guard stands down.");
			assertEquals("OBJECT", e.scenes().changeObject(op(), campaign, board, "bench", map("state", "DESTROYED"),
					"The improvised bench breaks.").get("feature_kind"));
			assertEquals("PASSAGE", e.scenes()
					.changeObject(op(), campaign, board, "exit", map("state", "OPEN"), "Open the improvised gate.")
					.get("feature_kind"));
			assertEquals("DESTROYED", part(view(e, campaign, board, "GM"), "objects", "bench").get("state"));
			assertEquals("OPEN", part(view(e, campaign, board, "GM"), "doors", "exit").get("state"));
			var before = e.db().snapshot(Ref.id(campaign, Ref.CAMPAIGN));
			RpgException duplicate = assertThrows(RpgException.class, () -> e.scenes().changeObject(op(), campaign,
					board, "duplicate", map("state", "DESTROYED"), "A duplicate saved key cannot be guessed."));
			assertEquals(ErrorCode.INVALID_ARGUMENT, duplicate.code());
			assertTrue(duplicate.getMessage().contains("Ambiguous exact"));
			assertEquals(before, e.db().snapshot(Ref.id(campaign, Ref.CAMPAIGN)));
		}
	}

	private static String ready(Engine e) {
		String campaign = committedCampaign(e);
		e.sessions().bootstrap(op(), campaign, null);
		return campaign;
	}

	private static String publish(Engine e, String campaign) {
		String home = e.db().read(tx -> Ref.of(Ref.LOCATION,
				tx.get("campaign", Ref.id(campaign, Ref.CAMPAIGN)).lng("current_location_id")));
		var proposal = map("name", "Canonical key lodge", "location_ref", home, "illumination", "BRIGHT", "room_count",
				2, "geometry", map("bounds_ft", List.of(0, 0, 40, 20)), "rooms",
				List.of(map("key", "entry", "rect_ft", List.of(0, 0, 20, 20)),
						map("key", "store", "rect_ft", List.of(20, 0, 20, 20))),
				"connections",
				List.of(map("key", "latch", "from", "entry", "to", "store", "state", "LOCKED", "from_ft",
						List.of(20, 5), "to_ft", List.of(20, 10))),
				"objects",
				List.of(map("key", "pit", "name", "Private covered pit", "kind", "TRAP", "room", "entry", "x_ft", 10,
						"y_ft", 10, "challenges",
						map("discover", map("difficulty", 12), "disarm", map("difficulty", 13))),
						map("key", "latch", "name", "Physical latch", "room", "entry", "x_ft", 10, "y_ft", 5, "state",
								"CLOSED"),
						map("key", "chest", "room", "entry", "item_ref", "Chest", "x_ft", 15, "y_ft", 15, "locked",
								true),
						map("key", "cache", "room", "entry", "x_ft", 5, "y_ft", 15)),
				"rewards", List.of(map("key", "cache", "room", "entry", "item_ref", "Quarterstaff", "container",
						"chest", "x_ft", 15, "y_ft", 15)));
		var prepared = e.maps().begin(op(), campaign, "SCENE", proposal);
		assertEquals(true, prepared.get("ready"), prepared.toString());
		var published = e.maps().publish(op(), campaign, prepared.get("preparation").toString(), null);
		assertNotNull(published.get("map_ref"), published.toString());
		return published.get("map_ref").toString();
	}

	private static String identities(Engine e, String board) {
		return e.db().read(tx -> Json.write(tx.query(
				"SELECT id,object_key,entity_ref,room_id,placement_json FROM map_object WHERE scene_id=? ORDER BY id",
				Ref.id(board, Ref.MAP)).stream().map(r -> r.asMap()).toList()));
	}

	private static String inventory(Engine e, String campaign) {
		return e.db().read(tx -> Json.write(tx
				.query("SELECT * FROM inventory_entry WHERE campaign_id=? ORDER BY id", Ref.id(campaign, Ref.CAMPAIGN))
				.stream().map(r -> r.asMap()).toList()));
	}

	private static Map<String, Object> view(Engine e, String campaign, String board, String audience) {
		return SceneService.map(e.scenes().context(campaign, board, audience, 0).get("view"));
	}

	private static Map<String, Object> part(Map<String, Object> view, String collection, String key) {
		return SceneService.maps(view.get(collection)).stream().filter(p -> key.equals(p.get("key"))).findFirst()
				.orElseThrow(() -> new AssertionError("Missing " + key + " in " + view));
	}
}
