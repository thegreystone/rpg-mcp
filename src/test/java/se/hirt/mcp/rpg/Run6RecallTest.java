/* Copyright (C) 2026 Marcus Hirt. SPDX-License-Identifier: BSD-3-Clause */
package se.hirt.mcp.rpg;

import io.quarkiverse.mcp.server.ToolResponse;
import org.junit.jupiter.api.Test;
import se.hirt.mcp.rpg.dice.ScriptedRollService;
import se.hirt.mcp.rpg.encounter.EncounterDigest;
import se.hirt.mcp.rpg.ledger.LedgerService;
import se.hirt.mcp.rpg.maps.SceneService;
import se.hirt.mcp.rpg.persistence.Database;
import se.hirt.mcp.rpg.protocol.Json;
import se.hirt.mcp.rpg.protocol.Ref;
import se.hirt.mcp.rpg.protocol.RpgException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static se.hirt.mcp.rpg.TestCampaigns.*;

class Run6RecallTest {

	@Test
	void unicodeRecallUsesTheDeliveredByteBudgetAndRetainsAsManyHighlightsAsFit() throws Exception {
		try (Engine e = engine(tempDb("run6-utf8-recall"))) {
			String campaign = ready(e);
			long cid = Ref.id(campaign, Ref.CAMPAIGN);
			var highlights = new ArrayList<Map<String, Object>>();
			for (int i = 0; i < 5; i++)
				highlights.add(map("slot", "log:" + (i + 1), "round", i + 1, "kind", "ATTACK", "actor", "character:1",
						"summary", "An observed hit.", "narration", "塔".repeat(150)));
			var digest = map("encounter", "encounter:999", "outcome", "OTHER", "rounds", 5, "elapsed_seconds", 30,
					"highlights", highlights, "summary", "The brawl ends.", "narrative", "夜".repeat(200),
					"final_roster", List.of(map("name", "Tam", "character", "character:1", "life_state", "ALIVE")));
			var compact = EncounterDigest.compactPublic(digest);
			assertTrue(Json.write(compact).length() < 2000, "This case distinguishes characters from UTF-8 bytes.");
			assertTrue(LedgerService.serializedBytes(compact) > 2000);
			String original = Json.hash(digest), originalCompact = Json.hash(compact);
			var bounded = LedgerService.boundedRecallDigest(compact);
			assertEquals(true, bounded.get("truncated"));
			var retained = SceneService.maps(bounded.get("highlights"));
			assertFalse(retained.isEmpty(), "Trim individual highlights instead of discarding the whole list.");
			assertTrue(retained.size() < highlights.size());
			assertEquals(highlights.getFirst(), retained.getFirst());
			assertEquals(5, ((Number) bounded.get("highlights_count")).intValue());
			assertTrue(LedgerService.serializedBytes(bounded) <= LedgerService.ENCOUNTER_DIGEST_MAX_BYTES);
			assertEquals(original, Json.hash(digest));
			assertEquals(originalCompact, Json.hash(compact));
			var appended = e.db()
					.mutate(Database.Mutation.of("run6_recall_fixture", cid, op(), "SYSTEM", Map.of()),
							tx -> Map.of("event", LedgerService.append(tx, cid,
									new LedgerService.EventSpec("ENCOUNTER_RESOLVED", "The remembered brawl ends.",
											List.of(1L), "NOTABLE", "PARTY_KNOWN", "MECHANICAL_CONSEQUENCE", null, null,
											"The complete account remains saved.", map("digest", digest)))));
			long event = ((Number) appended.get("event")).longValue();
			var delivered = SceneService.maps(
					e.ledger().queryTimeline(campaign, null, null, null, List.of("ENCOUNTER_RESOLVED"), null, false, 30)
							.get("events"))
					.getFirst();
			assertEquals(Json.hash(bounded), Json.hash(delivered.get("encounter_digest")));
			assertEquals(LedgerService.serializedBytes(bounded),
					((Number) delivered.get("encounter_digest_bytes")).intValue());
			assertEquals(2000, ((Number) delivered.get("encounter_digest_max_bytes")).intValue());
			assertEquals(original, e.db()
					.read(tx -> Json.hash(SceneService.map(tx.get("event", event).map("payload_json").get("digest")))));
			var recalled = SceneService.maps(e.sessions().bootstrap(op(), campaign, null).get("recent_events")).stream()
					.filter(row -> ("event:" + event).equals(row.get("ref"))).findFirst().orElseThrow();
			assertEquals(delivered.get("encounter_digest"), recalled.get("encounter_digest"));
			var small = EncounterDigest
					.compactPublic(map("encounter", "encounter:998", "highlights", List.of(highlights.getFirst())));
			assertFalse(LedgerService.boundedRecallDigest(small).containsKey("truncated"));
		}
	}

	@Test
	void lightingChangesAreRecalledOnceAndLegacyMissingAmbientMeansDark() throws Exception {
		try (Engine e = engine(tempDb("run6-light-recall"))) {
			String campaign = ready(e), scene = dungeon(e, campaign);
			long cid = Ref.id(campaign, Ref.CAMPAIGN), sid = Ref.id(scene, Ref.MAP);
			// Imported/legacy dungeon documents may omit the normalized ambient field.
			e.db().mutate(Database.Mutation.of("legacy_unlit_map_fixture", cid, op(), "SYSTEM", Map.of()), tx -> {
				var row = tx.get("map_scene", sid);
				var doc = row.map("document_json");
				doc.remove("illumination");
				tx.update("map_scene", sid, map("document_json", doc));
				return Map.of();
			});
			long revision = e.db().read(tx -> tx.get("map_scene", sid).lng("revision"));
			String operation = op(), reason = "The miners light the braziers in the entry hall.";
			Map<String, Object> patch = map("illumination", "BRIGHT");
			e.maps().correctGeometry(operation, campaign, scene, revision, patch, reason);
			assertEquals(true,
					e.maps().correctGeometry(operation, campaign, scene, revision, patch, reason).get("replayed"));
			assertEquals(1L, countLighting(e, cid));
			var light = e.db().read(tx -> tx
					.queryOne("SELECT * FROM event WHERE campaign_id=? AND type='MAP_ILLUMINATION_CHANGED'", cid)
					.orElseThrow());
			assertEquals("GM_ONLY", light.str("visibility"));
			assertEquals("DARK", SceneService.map(light.map("payload_json").get("before")).get("illumination"));
			assertEquals("BRIGHT", SceneService.map(light.map("payload_json").get("after")).get("illumination"));
			assertEquals(reason, light.map("payload_json").get("reason"));
			assertTrue(light.map("payload_json").get("journal_id") instanceof Number);
			assertEquals(1, SceneService.maps(e.ledger()
					.queryTimeline(campaign, null, null, null, List.of("MAP_ILLUMINATION_CHANGED"), null, false, 30)
					.get("events")).size());
			assertTrue(SceneService.maps(e.sessions().bootstrap(op(), campaign, null).get("recent_events")).stream()
					.anyMatch(event -> "MAP_ILLUMINATION_CHANGED".equals(event.get("type"))));
			var before = e.db().snapshot(cid);
			assertThrows(RpgException.class, () -> e.maps().correctGeometry(op(), campaign, scene, revision,
					map("illumination", "DIM"), "A stale change."));
			assertEquals(before, e.db().snapshot(cid));
			long freshRevision = e.db().read(tx -> tx.get("map_scene", sid).lng("revision"));
			e.maps().correctGeometry(op(), campaign, scene, freshRevision, patch, "Confirm the existing bright light.");
			assertEquals(1L, countLighting(e, cid), "An unchanged light setting must not create another world event.");
		}
	}

	@Test
	void defaultGmMapSummaryIncludesKeyedRoomLightingWithoutGeometryOrPlayerRoomLeaks() throws Exception {
		try (Engine e = engine(tempDb("run6-room-summary"))) {
			String campaign = ready(e), scene = dungeon(e, campaign);
			MapTools tools = new MapTools();
			tools.engine = e;
			var before = e.db().snapshot(Ref.id(campaign, Ref.CAMPAIGN));
			var summary = result(tools.context(campaign, scene, Optional.of("GM"), Optional.of(0), Optional.empty()));
			var rooms = SceneService.maps(SceneService.map(summary.get("view")).get("rooms"));
			assertEquals(2, rooms.size());
			var entry = rooms.stream().filter(room -> "entry".equals(room.get("key"))).findFirst().orElseThrow();
			var vault = rooms.stream().filter(room -> "vault".equals(room.get("key"))).findFirst().orElseThrow();
			assertEquals("DIM", entry.get("illumination"));
			assertEquals("DIM", entry.get("effective_illumination"));
			assertEquals("DARK", vault.get("effective_illumination"));
			assertTrue(rooms.stream().allMatch(room -> room.containsKey("location_ref")));
			assertTrue(rooms.stream().noneMatch(room -> room.containsKey("rect_ft") || room.containsKey("polygon_ft")));
			var player = result(
					tools.context(campaign, scene, Optional.of("PLAYER"), Optional.of(0), Optional.empty()));
			assertFalse(SceneService.map(player.get("view")).containsKey("rooms"));
			assertFalse(Json.write(player).contains("SECRET_RELIQUARY_ROOM"));
			var full = result(tools.context(campaign, scene, Optional.of("GM"), Optional.of(0), Optional.of("FULL")));
			assertTrue(SceneService.maps(SceneService.map(full.get("view")).get("rooms")).stream()
					.allMatch(room -> room.containsKey("rect_ft")));
			assertEquals(before, e.db().snapshot(Ref.id(campaign, Ref.CAMPAIGN)));
		}
	}

	@Test
	void longFightEndMcpReplyIsCompactWhileTheFullRecordAndReplayRemainAvailable() throws Exception {
		try (Engine e = engine(tempDb("run6-compact-end"))) {
			String campaign = ready(e);
			String foe = e.runtime()
					.materialize(op(), campaign, "Bandit", "Patient brawler", null, null, null, null, false)
					.get("character").toString();
			((ScriptedRollService) e.roller()).queue(20, 1);
			String encounter = e.encounters()
					.start(op(), campaign, map("party", List.of("character:1"), "foes", List.of(foe)), null, null, null,
							null, null, map("placements", List.of(map("character", "character:1", "x_ft", 5, "y_ft", 5),
									map("character", foe, "x_ft", 25, "y_ft", 5))))
					.get("encounter").toString();
			for (int round = 0; round < 10; round++) {
				e.runtime().applyRuntimeChange(op(), campaign, "character:1",
						map("kind", "DAMAGE", "amount", 1, "reason", "A separately resolved scratch."));
				e.runtime().applyRuntimeChange(op(), campaign, "character:1",
						map("kind", "HEAL", "amount", 1, "reason", "A separately resolved recovery."));
				e.encounters().perform(op(), campaign, encounter, "character:1", map("kind", "DODGE"), true);
				e.encounters().perform(op(), campaign, encounter, foe, map("kind", "DODGE"), true);
			}
			RpgTools tools = new RpgTools();
			tools.engine = e;
			String ending = op(), summary = "The lengthy brawl ends in a truce.",
					narrative = "The gathered witnesses remember each exchange. ".repeat(30);
			var delivered = result(tools.endEncounter(ending, campaign, "OTHER", Optional.of(encounter),
					Optional.of(summary), Optional.of(narrative)));
			assertFalse(delivered.containsKey("record"));
			var compact = SceneService.map(delivered.get("party_digest"));
			assertTrue(LedgerService.serializedBytes(compact) <= 2000);
			assertEquals(LedgerService.serializedBytes(compact),
					((Number) delivered.get("party_digest_bytes")).intValue());
			var pointer = SceneService.map(delivered.get("full_gm_record"));
			assertEquals("get_encounter_state", pointer.get("tool"));
			assertEquals("record", pointer.get("field"));
			assertEquals(map("campaign", campaign, "encounter", encounter, "audience", "GM"), pointer.get("arguments"));
			var service = e.encounters().end(ending, campaign, encounter, "OTHER", summary, narrative);
			assertTrue(service.containsKey("record"));
			assertTrue(LedgerService.serializedBytes(service) > LedgerService.serializedBytes(delivered));
			assertFalse(SceneService.maps(SceneService.map(service.get("record")).get("healing_and_stabilization"))
					.isEmpty());
			assertEquals(Json.hash(service.get("record")),
					Json.hash(e.encounters().encounterState(campaign, encounter, 100, "GM").get("record")));
			var slots = SceneService.maps(delivered.get("highlights_needing_narration"));
			assertFalse(slots.isEmpty());
			e.encounters().annotate(op(), campaign, encounter,
					Map.of(slots.getFirst().get("slot").toString(), "A witness remembers that recovery."));
			var replay = result(tools.endEncounter(ending, campaign, "OTHER", Optional.of(encounter),
					Optional.of(summary), Optional.of(narrative)));
			assertEquals(true, replay.get("replayed"));
			assertEquals(compact, replay.get("party_digest"),
					"Retry delivers the original frozen end result even after annotation.");
			assertTrue(((ScriptedRollService) e.roller()).exhausted());
		}
	}

	private static String dungeon(Engine e, String campaign) {
		String site = e.db().read(tx -> Ref.of(Ref.LOCATION,
				tx.get("campaign", Ref.id(campaign, Ref.CAMPAIGN)).lng("current_location_id")));
		var draft = e.maps().begin(op(), campaign, "DUNGEON",
				map("name", "A mine with a private vault", "stage", "SCENE", "location_ref", site, "room_count", 2,
						"geometry", map("bounds_ft", List.of(0, 0, 40, 20)), "rooms",
						List.of(map("key", "entry", "name", "Entry hall", "role", "ENTRY", "illumination", "DIM",
								"rect_ft", List.of(0, 0, 20, 20)),
								map("key", "vault", "name", "SECRET_RELIQUARY_ROOM", "secret", true, "rect_ft",
										List.of(20, 0, 20, 20))),
						"connections", List.of(map("key", "vault-door", "from", "entry", "to", "vault", "state",
								"LOCKED", "from_ft", List.of(20, 5), "to_ft", List.of(20, 10)))));
		return e.maps().publish(op(), campaign, draft.get("preparation").toString(), null).get("map_ref").toString();
	}

	private static long countLighting(Engine e, long cid) {
		return e.db().read(tx -> tx
				.count("SELECT COUNT(*) FROM event WHERE campaign_id=? AND type='MAP_ILLUMINATION_CHANGED'", cid));
	}

	private static String ready(Engine e) {
		String c = committedCampaign(e);
		e.sessions().bootstrap(op(), c, null);
		return c;
	}

	private static Map<String, Object> result(ToolResponse response) {
		assertFalse(response.isError(), response.toString());
		return SceneService.map(Json.readMap(response.firstContent().asText().text()).get("result"));
	}
}
