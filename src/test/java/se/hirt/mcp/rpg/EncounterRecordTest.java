package se.hirt.mcp.rpg;

import org.junit.jupiter.api.Test;
import se.hirt.mcp.rpg.dice.ScriptedRollService;
import se.hirt.mcp.rpg.maps.SceneService;
import se.hirt.mcp.rpg.protocol.Json;
import se.hirt.mcp.rpg.protocol.Ref;
import se.hirt.mcp.rpg.protocol.RpgException;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static se.hirt.mcp.rpg.TestCampaigns.*;

class EncounterRecordTest {

	@Test
	void decisiveActionNarrationAndOneDigestSurviveReloadAnnotationReplayAndCheckpoint() throws Exception {
		var file = tempDb("encounter-record");
		String campaign, encounter, event, slot, checkpoint;
		String narration = "The arrow pins his shadow to the cellar door. " + "x".repeat(350);
		try (Engine e = engine(file)) {
			campaign = committedCampaign(e);
			e.sessions().bootstrap(op(), campaign, null);
			var bow = e.inventory().trade(op(), campaign, "character:1", "BUY", "Shortbow", null, 1, null, null, null);
			e.inventory().trade(op(), campaign, "character:1", "BUY", "Arrow", null, 2, null, null, null);
			e.inventory().equip(op(), campaign, "character:1", bow.get("entry").toString(), true);
			String guard = bandit(e, campaign, "Cellar guard");
			((ScriptedRollService) e.roller()).queue(20, 1);
			var started = start(e, campaign, guard, map("placements", positions(5, 5, guard, 25, 5)));
			encounter = started.get("encounter").toString();
			((ScriptedRollService) e.roller()).queue(20, 6, 6);
			var shot = e.encounters().perform(op(), campaign, encounter, "character:1",
					map("kind", "ATTACK", "target", guard, "weapon", "Shortbow", "narration", narration), false);
			assertEquals(true, shot.get("critical"));
			assertTrue(shot.containsKey("narration_note"));
			String ending = op();
			var ended = e.encounters().end(ending, campaign, encounter, "PARTY_VICTORY", "The guard falls.",
					"The party finally owns the silence beneath the tavern.");
			Map<String, Object> record = SceneService.map(ended.get("record"));
			assertEquals(1, SceneService.maps(record.get("critical_hits")).size());
			assertEquals(1, SceneService.maps(record.get("deaths")).size());
			assertFalse(SceneService.maps(record.get("resources")).isEmpty(), "The arrow expenditure is recorded.");
			var highlights = SceneService.maps(record.get("highlights"));
			assertEquals(1, highlights.size());
			assertEquals(300, highlights.getFirst().get("narration").toString().length());
			slot = highlights.getFirst().get("slot").toString();
			event = ended.get("resolved_event").toString();
			assertEquals(event, e.encounters().end(ending, campaign, encounter, "PARTY_VICTORY", "The guard falls.",
					"The party finally owns the silence beneath the tavern.").get("resolved_event"));
			checkpoint = e.checkpoints().create(op(), campaign, "Before recalling the decisive shot").get("checkpoint")
					.toString();
			long cid = Ref.id(campaign, Ref.CAMPAIGN);
			long xp = e.db().read(tx -> tx.get("character", 1).lng("xp"));
			String annotation = op();
			var annotated = e.encounters().annotate(annotation, campaign, encounter,
					Map.of(slot, "The cellar falls silent."));
			assertEquals(event, annotated.get("resolved_event"));
			assertEquals(true,
					e.encounters().annotate(annotation, campaign, encounter, Map.of(slot, "The cellar falls silent."))
							.get("replayed"));
			assertEquals(1L, e.db().read(
					tx -> tx.count("SELECT COUNT(*) FROM event WHERE campaign_id=? AND type='ENCOUNTER_RESOLVED'", cid))
					.longValue());
			assertEquals(xp, e.db().read(tx -> tx.get("character", 1).lng("xp")).longValue());
			assertTrue(e.db().read(tx -> tx.get("event", Ref.id(event, Ref.EVENT)).str("episodic_detail"))
					.contains("The cellar falls silent."));
			assertThrows(RpgException.class, () -> e.encounters().annotate(op(), campaign, encounter,
					Map.of("log:999999", "This was never a selected event.")));
			String laterGuard = bandit(e, campaign, "Later guard");
			((ScriptedRollService) e.roller()).queue(20, 1);
			String later = start(e, campaign, laterGuard, map("placements", positions(5, 5, laterGuard, 25, 5)))
					.get("encounter").toString();
			((ScriptedRollService) e.roller()).queue(20, 6, 6);
			e.encounters().perform(op(), campaign, later, "character:1",
					map("kind", "ATTACK", "target", laterGuard, "weapon", "Shortbow"), false);
			var laterEnd = e.encounters().end(op(), campaign, later, "OTHER", "A different fight.");
			String foreignSlot = SceneService.maps(SceneService.map(laterEnd.get("record")).get("highlights"))
					.getFirst().get("slot").toString();
			var beforeForeign = e.db().snapshot(cid);
			assertThrows(RpgException.class, () -> e.encounters().annotate(op(), campaign, encounter,
					Map.of(foreignSlot, "Words for the wrong fight.")));
			assertEquals(beforeForeign, e.db().snapshot(cid));
		}
		try (Engine e = engine(file)) {
			assertTrue(Json.write(e.encounters().encounterState(campaign, encounter, 100, "GM"))
					.contains("The cellar falls silent."));
			e.checkpoints().restore(op(), campaign, checkpoint, "Restore the original remembered words.");
			Map<String, Object> record = SceneService
					.map(e.encounters().encounterState(campaign, encounter, 100, "GM").get("record"));
			assertEquals(narration.trim().substring(0, 300),
					SceneService.maps(record.get("highlights")).getFirst().get("narration"));
		}
	}

	@Test
	void annotationSelectsAtMostEightMechanicalHighlightsAndTrimsFollowupWords() throws Exception {
		try (Engine e = engine(tempDb("encounter-highlight-cap"))) {
			String campaign = committedCampaign(e);
			e.sessions().bootstrap(op(), campaign, null);
			String guard = bandit(e, campaign, "Distant witness");
			((ScriptedRollService) e.roller()).queue(20, 1);
			String encounter = start(e, campaign, guard, map("placements", positions(5, 5, guard, 25, 5)))
					.get("encounter").toString();
			for (int i = 0; i < 10; i++) {
				e.runtime().applyRuntimeChange(op(), campaign, "character:1",
						map("kind", "DAMAGE", "amount", 1, "reason", "A separately resolved scratch."));
				e.runtime().applyRuntimeChange(op(), campaign, "character:1",
						map("kind", "HEAL", "amount", 1, "reason", "A separately resolved recovery."));
			}
			var ended = e.encounters().end(op(), campaign, encounter, "OTHER", "The witness waits.");
			var highlights = SceneService.maps(SceneService.map(ended.get("record")).get("highlights"));
			assertEquals(8, highlights.size());
			String slot = highlights.getFirst().get("slot").toString();
			var annotated = e.encounters().annotate(op(), campaign, encounter, Map.of(slot, "z".repeat(350)));
			assertFalse(((List<?>) annotated.get("notes")).isEmpty());
			assertEquals(300, SceneService.maps(SceneService.map(annotated.get("record")).get("highlights")).getFirst()
					.get("narration").toString().length());
		}
	}

	@Test
	void partyLedgerAndPlayerStateExcludeUnseenRosterSecretChangesAndPrivateNarration() throws Exception {
		try (Engine e = engine(tempDb("encounter-record-privacy"))) {
			String campaign = committedCampaign(e);
			e.sessions().bootstrap(op(), campaign, null);
			String sentinel = bandit(e, campaign, "UNSEEN_SENTINEL_SECRET");
			((ScriptedRollService) e.roller()).queue(20, 1);
			var started = start(e, campaign, sentinel,
					map("scene", map("name", "Divided cellar", "bounds_ft", List.of(0, 0, 40, 20), "rooms",
							List.of(map("key", "west", "rect_ft",
									List.of(0, 0, 20, 20)), map("key", "east", "rect_ft", List.of(20, 0, 20, 20))),
							"objects",
							List.of(map("key", "private-statue", "name", "SECRET_STATUE_SENTINEL", "rect_ft",
									List.of(30, 10, 5, 5), "visibility", "GM_ONLY"))),
							"placements", positions(5, 5, sentinel, 30, 5)));
			String encounter = started.get("encounter").toString();
			String map = SceneService.map(started.get("grid")).get("map_ref").toString();
			e.encounters().perform(op(), campaign, encounter, "character:1",
					map("kind", "DODGE", "narration", "UNSEEN_SENTINEL_SECRET guards SECRET_STATUE_SENTINEL."), false);
			e.scenes().changeObject(op(), campaign, map, "private-statue", map("state", "DESTROYED"),
					"An unseen event resolves.");
			var ended = e.encounters().end(op(), campaign, encounter, "OTHER", "UNSEEN_SENTINEL_SECRET remains hidden.",
					"SECRET_STATUE_SENTINEL has fallen.");
			assertTrue(Json.write(ended.get("record")).contains("UNSEEN_SENTINEL_SECRET"));
			assertFalse(SceneService.maps(SceneService.map(ended.get("record")).get("map_changes")).isEmpty());
			String publicEvent = e.db().read(tx -> Json
					.write(tx.get("event", Ref.id(ended.get("resolved_event").toString(), Ref.EVENT)).asMap()));
			assertFalse(publicEvent.contains("UNSEEN_SENTINEL_SECRET"), publicEvent);
			assertFalse(publicEvent.contains("SECRET_STATUE_SENTINEL"), publicEvent);
			assertFalse(publicEvent.contains(sentinel), publicEvent);
			var player = e.encounters().encounterState(campaign, encounter, 100, "PLAYER");
			assertFalse(player.containsKey("record"));
			assertFalse(Json.write(player).contains("UNSEEN_SENTINEL_SECRET"));
			assertFalse(Json.write(player).contains("SECRET_STATUE_SENTINEL"));
		}
	}

	@Test
	void spellSlotsDropsAndStabilizationComeFromMechanicalResultsWithBoundedAnnotationSlots() throws Exception {
		try (Engine e = engine(tempDb("encounter-record-care"))) {
			String campaign = committedCampaign(e);
			e.sessions().bootstrap(op(), campaign, null);
			e.spells().prepare(op(), campaign, "character:1", null, List.of("Burning Hands"));
			String guard = bandit(e, campaign, "Unharmed witness");
			((ScriptedRollService) e.roller()).queue(20, 1);
			var started = start(e, campaign, guard, map("placements", positions(0, 0, guard, 10, 0)));
			String encounter = started.get("encounter").toString();
			((ScriptedRollService) e.roller()).queue(20, 1, 1, 1);
			e.encounters().perform(op(), campaign, encounter, "character:1", map("kind", "CAST", "spell",
					"Burning Hands", "targets", List.of(guard), "direction_ft", List.of(20, 2.5)), false);
			long hp = e.db().read(tx -> tx.get("character", 1).lng("current_hp"));
			e.runtime().applyRuntimeChange(op(), campaign, "character:1", map("kind", "DAMAGE", "amount", hp,
					"damage_type", "bludgeoning", "reason", "A separately resolved hazard."));
			((ScriptedRollService) e.roller()).queue(1);
			e.runtime().applyRuntimeChange(op(), campaign, "character:1",
					map("kind", "STABILIZE", "reason", "Resolved first aid."));
			var ended = e.encounters().end(op(), campaign, encounter, "OTHER", "The witness helps stop the bleeding.");
			Map<String, Object> record = SceneService.map(ended.get("record"));
			assertEquals(1, SceneService.maps(record.get("spells")).size());
			assertEquals(1, SceneService.maps(record.get("drops_to_zero")).size());
			assertEquals(1, SceneService.maps(record.get("healing_and_stabilization")).size());
			Map<String, Object> slotChange = SceneService.maps(record.get("resources")).stream()
					.filter(change -> "slot:1".equals(SceneService.map(change.get("after")).get("resource")))
					.findFirst().orElseThrow();
			Map<String, Object> slotBefore = SceneService.map(slotChange.get("before"));
			Map<String, Object> slotAfter = SceneService.map(slotChange.get("after"));
			assertEquals("character:1", slotAfter.get("character"));
			assertEquals(((Number) slotBefore.get("current")).longValue() - 1,
					((Number) slotAfter.get("current")).longValue());
			assertEquals(((Number) slotBefore.get("max")).longValue(), ((Number) slotAfter.get("max")).longValue());
			var care = SceneService.maps(record.get("healing_and_stabilization")).getFirst();
			assertNull(care.get("actor"), "The engine must not invent a first-aid provider from the summary.");
			var slots = SceneService.maps(ended.get("highlights_needing_narration"));
			assertTrue(slots.size() >= 3 && slots.size() <= 8);
			assertTrue(slots.stream().allMatch(slot -> "".equals(slot.get("narration"))));
			assertEquals("grant_loot", SceneService.map(record.get("loot")).get("tool"));
		}
	}

	private static String bandit(Engine e, String campaign, String name) {
		return e.runtime().materialize(op(), campaign, "Bandit", name, null, null, null, null, false).get("character")
				.toString();
	}

	private static Map<String, Object> start(Engine e, String campaign, String foe, Map<String, Object> options) {
		return e.encounters().start(op(), campaign, map("party", List.of("character:1"), "foes", List.of(foe)), null,
				null, null, null, null, options);
	}

	private static List<Map<String, Object>> positions(int x, int y, String foe, int fx, int fy) {
		return List.of(map("character", "character:1", "x_ft", x, "y_ft", y),
				map("character", foe, "x_ft", fx, "y_ft", fy));
	}
}
