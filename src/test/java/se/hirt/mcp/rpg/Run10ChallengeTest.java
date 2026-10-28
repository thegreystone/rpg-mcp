/* Copyright (C) 2026 Marcus Hirt. SPDX-License-Identifier: BSD-3-Clause */
package se.hirt.mcp.rpg;

import io.quarkiverse.mcp.server.ToolResponse;
import org.junit.jupiter.api.Test;
import se.hirt.mcp.rpg.dice.ScriptedRollService;
import se.hirt.mcp.rpg.maps.PngCodec;
import se.hirt.mcp.rpg.maps.SceneService;
import se.hirt.mcp.rpg.persistence.Database;
import se.hirt.mcp.rpg.protocol.Json;
import se.hirt.mcp.rpg.protocol.Ref;
import se.hirt.mcp.rpg.protocol.RpgException;
import se.hirt.mcp.rpg.session.GameTime;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static se.hirt.mcp.rpg.TestCampaigns.*;

class Run10ChallengeTest {

	@Test
	void acceptedBlueprintAllowsRoundTrippedProvenanceWhileKeepingItsActualDifficultyPinned() throws Exception {
		try (Engine e = engine(tempDb("run10-accepted-challenge-provenance"))) {
			String campaign = ready(e);
			var draft = e.maps().begin(op(), campaign, "DUNGEON",
					map("name", "Accepted secret entrance", "room_count", 2, "rooms",
							List.of(map("key", "entry"), map("key", "treasure", "secret", true)), "connections",
							List.of(map("key", "secret-entry", "from", "entry", "to", "treasure", "secret", true))));
			String prep = draft.get("preparation").toString();
			var accepted = e.maps().publish(op(), campaign, prep, null);
			assertEquals("ACCEPTED", accepted.get("status"));
			var roundTrip = SceneService.maps(SceneService.map(accepted.get("proposal")).get("connections"));
			var realized = e.maps().revise(op(), campaign, prep, null,
					map("generate_layout", true, "connections", roundTrip));
			assertEquals(15,
					dc(part(SceneService.map(realized.get("proposal")), "connections", "secret-entry"), "discover"));
			var before = e.db().snapshot(Ref.id(campaign, Ref.CAMPAIGN));
			assertThrows(RpgException.class, () -> e.maps().revise(op(), campaign, prep, null, map("connections",
					List.of(map("key", "secret-entry", "challenges", map("discover", map("difficulty", 14)))))));
			assertEquals(before, e.db().snapshot(Ref.id(campaign, Ref.CAMPAIGN)));
		}
	}

	@Test
	void undiscoveredTrapIsAbsentFromCurrentPlayerContextMemoryAndBothRenderDeliveries() throws Exception {
		try (Engine e = engine(tempDb("run10-trap-projection"))) {
			String campaign = ready(e), board = traps(e, campaign);
			e.scenes().place(op(), campaign, board, List.of(map("character", "character:1", "x_ft", 5, "y_ft", 5)));
			e.scenes().observe(op(), campaign, board);
			var gm = view(e, campaign, board, "GM");
			assertEquals(12, dc(part(gm, "objects", "object:pit"), "discover"));
			assertEquals(13, dc(part(gm, "objects", "object:pit"), "disarm"));
			var before = e.db().snapshot(Ref.id(campaign, Ref.CAMPAIGN));
			((ScriptedRollService) e.roller()).queue(4);
			MapTools tools = new MapTools();
			tools.engine = e;
			for (String detail : List.of("FULL", "SUMMARY"))
				assertTrapPrivacy(result(
						tools.context(campaign, board, Optional.of("PLAYER"), Optional.of(0), Optional.of(detail))));
			for (boolean image : List.of(false, true)) {
				ToolResponse rendered = tools.render(campaign, board, Optional.of("PLAYER"), Optional.of(0),
						Optional.of(Map.of("max_pixels", 128)), Optional.of(image));
				var body = result(rendered);
				assertTrapPrivacy(body);
				assertFalse(body.get("text").toString().contains("Covered pit"));
				assertTrue(body.get("text").toString().contains("Open fissure"));
				if (image) {
					assertEquals("image/png", rendered.content().get(1).asImage().mimeType());
					assertEquals(128, PngCodec
							.decode(Base64.getDecoder().decode(rendered.content().get(1).asImage().data())).width());
				}
			}
			assertEquals(before, e.db().snapshot(Ref.id(campaign, Ref.CAMPAIGN)),
					"Reading or rendering cannot discover a trap.");
			assertFalse(Json.write(partyMemory(e, board)).contains("Covered pit"));
			assertEquals(4, e.roller().roll("1d6").total(), "No automatic Perception check consumed the queued die.");
		}
	}

	@Test
	void legacyLeakedMemoryIsFilteredWhileExplicitRevealConcealReplayReloadAndCheckpointRemainCoherent()
			throws Exception {
		var file = tempDb("run10-trap-memory");
		String campaign, board, checkpoint;
		try (Engine e = engine(file)) {
			campaign = ready(e);
			board = traps(e, campaign);
			e.scenes().place(op(), campaign, board, List.of(map("character", "character:1", "x_ft", 5, "y_ft", 5)));
			seedLegacyTrapMemory(e, campaign, board);
			blind(e, campaign, true);
			var beforeRead = e.db().snapshot(Ref.id(campaign, Ref.CAMPAIGN));
			assertTrapPrivacy(Map.of("view", view(e, campaign, board, "PLAYER")));
			assertEquals(beforeRead, e.db().snapshot(Ref.id(campaign, Ref.CAMPAIGN)),
					"Legacy filtering must be a read-only projection.");
			assertTrue(Json.write(partyMemory(e, board)).contains("Covered pit"));
			e.scenes().observe(op(), campaign, board);
			assertFalse(Json.write(partyMemory(e, board)).contains("Covered pit"),
					"Observation persists the corrected cache even without sight.");
			blind(e, campaign, false);
			e.scenes().changeObject(op(), campaign, board, "object:pit", map("concealed", false),
					"The DM reveals the pit after adjudicated discovery.");
			assertEquals("Covered pit", part(view(e, campaign, board, "PLAYER"), "objects", "object:pit").get("name"));
			blind(e, campaign, true);
			assertEquals(true, part(view(e, campaign, board, "PLAYER"), "objects", "object:pit").get("last_known"));
			checkpoint = e.checkpoints().create(op(), campaign, "The party knows the pit").get("checkpoint").toString();
			String operation = op();
			long clock = e.db().read(tx -> GameTime.currentSeconds(tx, Ref.id(campaign, Ref.CAMPAIGN)));
			e.scenes().changeObject(operation, campaign, board, "object:pit", map("concealed", true),
					"An explicit correction restores concealed trap state.");
			assertTrapPrivacy(Map.of("view", view(e, campaign, board, "PLAYER")));
			assertFalse(Json.write(partyMemory(e, board)).contains("Covered pit"));
			var saved = e.db().snapshot(Ref.id(campaign, Ref.CAMPAIGN));
			assertEquals(true, e.scenes().changeObject(operation, campaign, board, "object:pit", map("concealed", true),
					"An explicit correction restores concealed trap state.").get("replayed"));
			assertEquals(saved, e.db().snapshot(Ref.id(campaign, Ref.CAMPAIGN)));
			assertEquals(clock,
					e.db().read(tx -> GameTime.currentSeconds(tx, Ref.id(campaign, Ref.CAMPAIGN))).longValue());
		}
		try (Engine e = engine(file)) {
			assertTrapPrivacy(Map.of("view", view(e, campaign, board, "PLAYER")));
			assertEquals(12, dc(part(view(e, campaign, board, "GM"), "objects", "object:pit"), "discover"));
			e.checkpoints().restore(op(), campaign, checkpoint,
					"Restore the explicitly revealed hazard and its knowledge.");
			assertEquals(true, part(view(e, campaign, board, "PLAYER"), "objects", "object:pit").get("last_known"));
		}
	}

	@Test
	void secretPassageOwnsDiscoveryWhileAnExplicitIndependentRoomCheckSurvivesPublicationAndReload() throws Exception {
		var file = tempDb("run10-secret-room-check");
		String campaign, board;
		try (Engine e = engine(file)) {
			campaign = ready(e);
			var proposal = secretRooms(e, campaign);
			var draft = e.maps().begin(op(), campaign, "DUNGEON", proposal);
			var prepared = SceneService.map(draft.get("proposal"));
			assertFalse(SceneService.map(part(prepared, "rooms", "shrine").get("challenges")).containsKey("discover"));
			assertEquals(15, dc(part(prepared, "rooms", "inner"), "discover"),
					"An explicitly authored DC 15 remains independent.");
			var published = e.maps().publish(op(), campaign, draft.get("preparation").toString(), null);
			board = published.get("map_ref").toString();
			assertSecretChecks(view(e, campaign, board, "GM"));
		}
		try (Engine e = engine(file)) {
			assertSecretChecks(view(e, campaign, board, "GM"));
		}
	}

	@Test
	void addingASecretEntranceRemovesOnlyMarkedDefaultsAndPreservesExplicitOrAmbiguousLegacyFifteen() throws Exception {
		for (String mode : List.of("GENERATED", "AUTHORED", "LEGACY"))
			try (Engine e = engine(tempDb("run10-secret-revision-" + mode))) {
				String campaign = ready(e);
				var proposal = secretRooms(e, campaign);
				var entrance = part(proposal, "connections", "secret-shrine");
				entrance.put("state", "OPEN");
				entrance.remove("challenges");
				part(proposal, "connections", "inner-door").put("state", "OPEN");
				var draft = e.maps().begin(op(), campaign, "DUNGEON", proposal);
				String prep = draft.get("preparation").toString();
				assertEquals(15, dc(part(SceneService.map(draft.get("proposal")), "rooms", "shrine"), "discover"));
				if (mode.equals("LEGACY"))
					e.db().mutate(Database.Mutation.of("run10_legacy_room_fixture", Ref.id(campaign, Ref.CAMPAIGN),
							op(), "GM", Map.of()), tx -> {
								var row = tx.get("map_preparation", Ref.id(prep, "preparation"));
								var payload = row.map("payload_json");
								part(payload, "rooms", "shrine").remove("generated_challenges");
								tx.update("map_preparation", row.id(), map("payload_json", Json.write(payload)));
								return Map.of("ok", true);
							});
				var patch = map("connections", List.of(map("key", "secret-shrine", "state", "SECRET", "challenges",
						map("discover", map("difficulty", 14)))));
				if (mode.equals("AUTHORED"))
					patch.put("rooms",
							List.of(map("key", "shrine", "challenges", map("discover", map("difficulty", 15)))));
				var revised = e.maps().revise(op(), campaign, prep, null, patch);
				var shrine = part(SceneService.map(revised.get("proposal")), "rooms", "shrine");
				assertEquals(!mode.equals("GENERATED"),
						SceneService.map(shrine.get("challenges")).containsKey("discover"), mode);
				if (!mode.equals("GENERATED"))
					assertEquals(15, dc(shrine, "discover"));
				assertEquals(14, dc(part(SceneService.map(revised.get("proposal")), "connections", "secret-shrine"),
						"discover"));
			}
	}

	private static void assertSecretChecks(Map<String, Object> gm) {
		assertEquals(14, dc(part(gm, "doors", "secret-shrine"), "discover"));
		assertFalse(SceneService.map(part(gm, "rooms", "shrine").get("challenges")).containsKey("discover"));
		assertEquals(15, dc(part(gm, "rooms", "inner"), "discover"));
	}

	private static Map<String, Object> secretRooms(Engine e, String campaign) {
		return map("name", "Secret entrance checks", "stage", "SCENE", "location_ref", home(e, campaign),
				"illumination", "BRIGHT", "geometry", map("bounds_ft", List.of(0, 0, 60, 20)), "rooms",
				List.of(map("key", "entry", "rect_ft", List.of(0, 0, 20, 20)),
						map("key", "shrine", "secret", true, "rect_ft", List.of(20, 0, 20, 20)),
						map("key", "inner", "secret", true, "rect_ft", List.of(40, 0, 20, 20), "challenges",
								map("discover", map("difficulty", 15)))),
				"connections",
				List.of(map("key", "secret-shrine", "from", "entry", "to", "shrine", "kind", "DOOR", "state", "SECRET",
						"from_ft", List.of(20, 5), "to_ft", List.of(20, 10), "challenges",
						map("discover", map("difficulty", 14))),
						map("key", "inner-door", "from", "shrine", "to", "inner", "kind", "DOOR", "state", "SECRET",
								"from_ft", List.of(40, 5), "to_ft", List.of(40, 10), "challenges",
								map("discover", map("difficulty", 14)))));
	}

	private static String traps(Engine e, String campaign) {
		var draft = e.maps()
				.begin(op(), campaign, "DUNGEON",
						map("name", "Trap privacy", "stage", "SCENE", "location_ref", home(e, campaign), "illumination",
								"BRIGHT", "geometry", map("bounds_ft", List.of(0, 0, 40, 20)), "rooms",
								List.of(map("key", "entry", "rect_ft", List.of(0, 0, 40, 20))), "objects", List.of(
										map("key", "pit", "room", "entry", "kind", "TRAP", "name", "Covered pit",
												"x_ft", 10, "y_ft", 5, "width_ft", 5, "height_ft", 5, "challenges",
												map("discover", map("difficulty", 12), "disarm",
														map("difficulty", 13))),
										map("key", "known", "room", "entry", "kind", "TRAP", "name", "Open fissure",
												"known_hazard", true, "x_ft", 25, "y_ft", 5, "width_ft", 5, "height_ft",
												5))));
		var published = e.maps().publish(op(), campaign, draft.get("preparation").toString(), null);
		assertNotNull(published.get("map_ref"), published.toString());
		return published.get("map_ref").toString();
	}

	private static void seedLegacyTrapMemory(Engine e, String campaign, String board) {
		e.db().mutate(
				Database.Mutation.of("run10_legacy_trap_memory", Ref.id(campaign, Ref.CAMPAIGN), op(), "GM", Map.of()),
				tx -> {
					var row = tx.queryOne("SELECT * FROM map_observation WHERE scene_id=? AND observer_key='party'",
							Ref.id(board, Ref.MAP)).orElseThrow();
					var knowledge = row.map("knowledge_json");
					var objects = new LinkedHashMap<>(SceneService.map(knowledge.get("objects")));
					objects.put("object:pit", map("key", "object:pit", "name", "Covered pit", "kind", "TRAP", "x_ft",
							10, "y_ft", 5, "width_ft", 5, "height_ft", 5, "level", 0));
					knowledge.put("objects", objects);
					tx.update("map_observation", row.id(), map("knowledge_json", knowledge));
					return Map.of("ok", true);
				});
	}

	private static void assertTrapPrivacy(Map<String, Object> response) {
		String json = Json.write(response);
		assertFalse(json.contains("Covered pit"), json);
		assertFalse(json.contains("object:pit"), json);
		assertFalse(json.contains("challenges"), json);
		assertFalse(json.contains("difficulty"), json);
		assertFalse(json.contains("generated_challenges"), json);
	}

	private static Map<String, Object> partyMemory(Engine e, String board) {
		return e.db().read(tx -> tx.queryOne("SELECT * FROM map_observation WHERE scene_id=? AND observer_key='party'",
				Ref.id(board, Ref.MAP)).orElseThrow().map("knowledge_json"));
	}

	private static void blind(Engine e, String campaign, boolean blind) {
		e.runtime().applyRuntimeChange(op(), campaign, "character:1",
				map("kind", blind ? "ADD_CONDITION" : "REMOVE_CONDITION", "condition", "BLINDED", "reason",
						"The fixture observer's eyes are " + (blind ? "covered." : "uncovered.")));
	}

	private static Map<String, Object> view(Engine e, String campaign, String board, String audience) {
		return SceneService.map(e.scenes().context(campaign, board, audience, 0).get("view"));
	}

	private static Map<String, Object> part(Map<String, Object> value, String collection, String key) {
		return SceneService.maps(value.get(collection)).stream().filter(p -> key.equals(p.get("key"))).findFirst()
				.orElseThrow(() -> new AssertionError("Missing " + key + " in " + value));
	}

	private static int dc(Map<String, Object> part, String challenge) {
		return ((Number) SceneService.map(SceneService.map(part.get("challenges")).get(challenge)).get("difficulty"))
				.intValue();
	}

	private static Map<String, Object> result(ToolResponse response) {
		assertFalse(response.isError(), response.toString());
		return SceneService.map(Json.readMap(response.firstContent().asText().text()).get("result"));
	}

	private static String home(Engine e, String campaign) {
		return e.db().read(tx -> Ref.of(Ref.LOCATION,
				tx.get("campaign", Ref.id(campaign, Ref.CAMPAIGN)).lng("current_location_id")));
	}

	private static String ready(Engine e) {
		String campaign = committedCampaign(e);
		e.sessions().bootstrap(op(), campaign, null);
		return campaign;
	}
}
