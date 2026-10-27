package se.hirt.mcp.rpg;

import org.junit.jupiter.api.Test;
import se.hirt.mcp.rpg.dice.ScriptedRollService;
import se.hirt.mcp.rpg.maps.SceneService;
import se.hirt.mcp.rpg.protocol.Ref;
import se.hirt.mcp.rpg.protocol.RpgException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static se.hirt.mcp.rpg.TestCampaigns.*;

class IncidentalSceneStateTest {

	@Test
	void improvisedDoorsAndFurnitureChangeSightAndMovementAndSurviveReloadAndCheckpoint() throws Exception {
		var file = tempDb("incidental-scene-state");
		String campaign, mapRef, guard, checkpoint;
		long revision;
		try (Engine e = engine(file)) {
			campaign = committedCampaign(e);
			e.sessions().bootstrap(op(), campaign, null);
			guard = e.runtime().materialize(op(), campaign, "Bandit", "Porch guard", null, null, null, null, false)
					.get("character").toString();
			((ScriptedRollService) e.roller()).queue(20, 1);
			var started = e.encounters().start(op(), campaign,
					map("party", List.of("character:1"), "guards", List.of(guard)), null, null, null, null, null,
					map("scene", map("bounds_ft", List.of(0, 0, 40, 20), "rooms",
							List.of(map("key", "taproom", "rect_ft",
									List.of(0, 0, 20, 20)), map("key", "porch", "rect_ft", List.of(20, 0, 20, 20))),
							"doors",
							List.of(map("key", "front-door", "from_ft", List.of(20, 5), "to_ft", List.of(20, 10),
									"state", "CLOSED")),
							"objects",
							List.of(map("key", "oak-table", "rect_ft", List.of(10, 15, 5, 5), "blocks_movement", true),
									map("key", "duplicate", "label", "First decoration"),
									map("key", "duplicate", "label", "Second decoration"))),
							"placements", List.of(map("character", "character:1", "x_ft", 5, "y_ft", 5),
									map("character", guard, "x_ft", 30, "y_ft", 5))));
			mapRef = SceneService.map(started.get("grid")).get("map_ref").toString();
			e.encounters().end(op(), campaign, started.get("encounter").toString(), "OTHER", "The dispute ends.");
			assertFalse(canSee(e, campaign, mapRef, guard));
			assertEquals("NO_PATH", e.scenes().preview(campaign, mapRef, "character:1", 25, 5, "GM").get("status"));
			assertEquals("INVALID_DESTINATION",
					e.scenes().preview(campaign, mapRef, "character:1", 10, 15, "GM").get("status"));
			var before = e.db().snapshot(Ref.id(campaign, Ref.CAMPAIGN));
			assertThrows(RpgException.class, () -> e.scenes().changeObject(op(), campaign, mapRef, "front-door",
					map("state", "UNKNOWN"), "Invalid state rejected."));
			assertThrows(RpgException.class, () -> e.scenes().changeObject(op(), campaign, mapRef, "oak-table",
					map("rect_ft", List.of(0, 0, 5, 5)), "Geometry is not a state update."));
			assertThrows(RpgException.class, () -> e.scenes().changeObject(op(), campaign, mapRef, "missing",
					map("state", "DESTROYED"), "Unknown scenery rejected."));
			assertThrows(RpgException.class, () -> e.scenes().changeObject(op(), campaign, mapRef, "duplicate",
					map("state", "DESTROYED"), "Ambiguous scenery rejected."));
			assertEquals(before.get("map_scene"), e.db().snapshot(Ref.id(campaign, Ref.CAMPAIGN)).get("map_scene"));
			e.scenes().changeObject(op(), campaign, mapRef, "front-door", map("state", "OPEN"),
					"The guard opens the door.");
			assertTrue(canSee(e, campaign, mapRef, guard));
			assertEquals("FOUND", e.scenes().preview(campaign, mapRef, "character:1", 25, 5, "GM").get("status"));
			checkpoint = e.checkpoints().create(op(), campaign, "Before the table breaks").get("checkpoint").toString();
			e.scenes().changeObject(op(), campaign, mapRef, "oak-table", map("state", "DESTROYED"),
					"Resolved damage shatters the table.");
			assertEquals("FOUND", e.scenes().preview(campaign, mapRef, "character:1", 10, 15, "GM").get("status"));
			revision = ((Number) e.scenes().context(campaign, mapRef, "GM", 0).get("map_revision")).longValue();
		}
		try (Engine e = engine(file)) {
			var recalled = e.scenes().context(campaign, mapRef, "GM", 0);
			assertEquals(revision, ((Number) recalled.get("map_revision")).longValue());
			var document = SceneService.map(recalled.get("view"));
			assertEquals("DESTROYED", SceneService.maps(document.get("objects")).stream()
					.filter(o -> "oak-table".equals(o.get("key"))).findFirst().orElseThrow().get("state"));
			assertEquals("OPEN", SceneService.maps(document.get("doors")).getFirst().get("state"));
			assertTrue(canSee(e, campaign, mapRef, guard));
			assertEquals("FOUND", e.scenes().preview(campaign, mapRef, "character:1", 10, 15, "GM").get("status"));
			e.checkpoints().restore(op(), campaign, checkpoint, "Rewind the table's destruction.");
			assertEquals("INVALID_DESTINATION",
					e.scenes().preview(campaign, mapRef, "character:1", 10, 15, "GM").get("status"));
			e.scenes().changeObject(op(), campaign, mapRef, "front-door", map("state", "CLOSED"),
					"The guard closes the door.");
			assertFalse(canSee(e, campaign, mapRef, guard));
			assertEquals("NO_PATH", e.scenes().preview(campaign, mapRef, "character:1", 25, 5, "GM").get("status"));
		}
	}

	private static boolean canSee(Engine e, String campaign, String mapRef, String guard) {
		long cid = Ref.id(campaign, Ref.CAMPAIGN);
		return e.db().read(tx -> SceneService.canSee(tx, SceneService.scene(tx, cid, mapRef),
				SceneService.actor(tx, cid, "character:1"), SceneService.actor(tx, cid, guard)));
	}
}
