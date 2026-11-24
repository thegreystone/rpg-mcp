package se.hirt.mcp.rpg;

import io.quarkiverse.mcp.server.ToolResponse;
import org.junit.jupiter.api.Test;
import se.hirt.mcp.rpg.encounter.EncounterDigest;
import se.hirt.mcp.rpg.maps.SceneService;
import se.hirt.mcp.rpg.protocol.Json;
import se.hirt.mcp.rpg.protocol.Ref;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static se.hirt.mcp.rpg.TestCampaigns.*;

class Run8RecallTest {

	@Test
	void eightUnicodeNarrationPromptsAreByteBoundedAndKeepExactSlotsWithoutCopyingFullMechanics() throws Exception {
		try (Engine e = engine(tempDb("run8-compact-narration-prompts"))) {
			String campaign = committedCampaign(e);
			e.sessions().bootstrap(op(), campaign, null);
			String name = "Witness \"quoted\" \\ line\n" + "\uD83E\uDDD9".repeat(160);
			String foe = e.runtime().materialize(op(), campaign, "Bandit", name, null, null, null, null, false)
					.get("character").toString();
			String encounter = e.encounters()
					.start(op(), campaign, map("party", List.of("character:1"), "foes", List.of(foe)), null, null, null,
							null, null, map("placements", List.of(map("character", "character:1", "x_ft", 5, "y_ft", 5),
									map("character", foe, "x_ft", 25, "y_ft", 5))))
					.get("encounter").toString();
			for (int i = 0; i < 8; i++) {
				e.runtime().applyRuntimeChange(op(), campaign, foe,
						map("kind", "DAMAGE", "amount", 1, "reason", "A resolved scratch."));
				e.runtime().applyRuntimeChange(op(), campaign, foe,
						map("kind", "HEAL", "amount", 1, "reason", "A resolved recovery."));
			}
			RpgTools tools = new RpgTools();
			tools.engine = e;
			String ending = op();
			var delivered = result(tools.endEncounter(ending, campaign, "OTHER", Optional.of(encounter),
					Optional.of("The test fight ends."), Optional.empty()));
			var prompts = SceneService.maps(delivered.get("highlights_needing_narration"));
			assertEquals(8, prompts.size());
			int bytes = Json.utf8(Json.write(prompts)).length;
			assertTrue(bytes <= EncounterDigest.ANNOTATION_SLOTS_MAX_BYTES,
					"Unicode and JSON escaping count toward the prompt byte budget.");
			assertEquals(bytes, ((Number) delivered.get("highlights_needing_narration_bytes")).intValue());
			assertEquals(2000, ((Number) delivered.get("highlights_needing_narration_max_bytes")).intValue());
			assertTrue(prompts.stream().allMatch(slot -> "".equals(slot.get("narration"))
					&& !slot.containsKey("mechanics") && !slot.containsKey("result")));
			var full = SceneService.map(e.encounters().encounterState(campaign, encounter, 100, "GM").get("record"));
			var highlights = SceneService.maps(full.get("highlights"));
			assertEquals(highlights.stream().map(row -> row.get("slot")).toList(),
					prompts.stream().map(row -> row.get("slot")).toList());
			assertTrue(highlights.stream().allMatch(row -> !SceneService.map(row.get("mechanics")).isEmpty()));
			assertTrue(highlights.stream().anyMatch(row -> row.get("summary").toString().length() > 120));
			assertTrue(Json.utf8(Json.write(highlights)).length > bytes * 2);
			assertEquals("get_encounter_state", SceneService.map(delivered.get("full_gm_record")).get("tool"));
			assertEquals("record", SceneService.map(delivered.get("full_gm_record")).get("field"));
			String saved = Json.hash(full);
			assertEquals(saved, Json.hash(SceneService.map(
					e.encounters().end(ending, campaign, encounter, "OTHER", "The test fight ends.").get("record"))));
			String checkpoint = e.checkpoints().create(op(), campaign, "Before highlight annotations").get("checkpoint")
					.toString();
			var annotations = new LinkedHashMap<String, String>();
			prompts.forEach(
					slot -> annotations.put(slot.get("slot").toString(), "A witness recalls this selected recovery."));
			e.encounters().annotate(op(), campaign, encounter, annotations);
			var annotated = SceneService
					.map(e.encounters().encounterState(campaign, encounter, 100, "GM").get("record"));
			assertTrue(SceneService.maps(annotated.get("highlights")).stream()
					.allMatch(row -> annotations.get(row.get("slot")).equals(row.get("narration"))));
			long cid = Ref.id(campaign, Ref.CAMPAIGN);
			assertEquals(1L, e.db().read(
					tx -> tx.count("SELECT COUNT(*) FROM event WHERE campaign_id=? AND type='ENCOUNTER_RESOLVED'", cid))
					.longValue());
			var replay = result(tools.endEncounter(ending, campaign, "OTHER", Optional.of(encounter),
					Optional.of("The test fight ends."), Optional.empty()));
			assertEquals(true, replay.get("replayed"));
			assertEquals(prompts, replay.get("highlights_needing_narration"));
			e.checkpoints().restore(op(), campaign, checkpoint, "Undo only the later annotations.");
			assertEquals(saved, Json.hash(e.encounters().encounterState(campaign, encounter, 100, "GM").get("record")));
		}
	}

	private static Map<String, Object> result(ToolResponse response) {
		assertFalse(response.isError(), response.toString());
		return SceneService.map(Json.readMap(response.firstContent().asText().text()).get("result"));
	}
}
