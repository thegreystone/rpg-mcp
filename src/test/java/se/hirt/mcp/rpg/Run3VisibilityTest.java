package se.hirt.mcp.rpg;

import org.junit.jupiter.api.Test;
import se.hirt.mcp.rpg.maps.SceneService;
import se.hirt.mcp.rpg.spatial.Geometry.Footprint;
import se.hirt.mcp.rpg.spatial.SpatialRules;
import java.util.List;
import se.hirt.mcp.rpg.protocol.Ref;
import static se.hirt.mcp.rpg.TestCampaigns.*;
import static org.junit.jupiter.api.Assertions.*;
import static se.hirt.mcp.rpg.TestCampaigns.map;

class Run3VisibilityTest {
	@Test
	void sideOpeningInTheMineMustNotCreateOneWayGeometricSight() {
		var document = map("bounds_ft", List.of(0, 0, 120, 70), "rooms",
				List.of(map("key", "drift", "rect_ft", List.of(20, 0, 60, 10)),
						map("key", "gallery", "rect_ft", List.of(30, 10, 30, 25))),
				"doors", List.of(map("key", "gallery-opening", "from_ft", List.of(40, 10), "to_ft", List.of(45, 10),
						"state", "OPEN")));
		var physical = SceneService.geometry(document, 0);
		var tam = new Footprint(SceneService.point(35, 5), 1, 1);
		var overseer = new Footprint(SceneService.point(40, 20), 1, 1);
		var outward = SpatialRules.cover(physical, tam, overseer);
		var inward = SpatialRules.cover(physical, overseer, tam);
		assertNotEquals(SpatialRules.Cover.TOTAL, outward.cover());
		assertEquals(outward.cover() == SpatialRules.Cover.TOTAL, inward.cover() == SpatialRules.Cover.TOTAL,
				"The same mine opening cannot hide only one equally sighted creature: " + outward + " versus "
						+ inward);
	}

	@Test
	void actualMineActorsSeeEachOtherThroughSideOpeningButKeepIndividualConditions() throws Exception {
		try (Engine e = engine(tempDb("run3-mine-visibility"))) {
			String campaign = committedCampaign(e);
			e.sessions().bootstrap(op(), campaign, null);
			String opening = e.db().read(
					tx -> "location:" + tx.get("campaign", Ref.id(campaign, Ref.CAMPAIGN)).lng("current_location_id"));
			String companion = e.runtime()
					.materialize(op(), campaign, "Priest Acolyte", "Ansel", null, null, null, null, false)
					.get("character").toString();
			var prep = e.maps().begin(op(), campaign, "SCENE", map("name", "Mine side gallery", "location_ref", opening,
					"geometry", map("bounds_ft", List.of(0, 0, 120, 70)), "illumination", "DIM", "rooms",
					List.of(map("key", "drift", "rect_ft",
							List.of(20, 0, 60, 10)), map("key", "gallery", "rect_ft", List.of(30, 10, 30, 25))),
					"connections",
					List.of(map("key", "opening", "from", "drift", "to", "gallery", "kind", "PASSAGE", "state", "OPEN",
							"from_ft", List.of(40, 10), "to_ft", List.of(45, 10))),
					"actors",
					List.of(map("key", "overseer", "room", "gallery", "creature_ref", "Bandit", "x_ft", 40, "y_ft", 20),
							map("key", "whip", "room", "gallery", "creature_ref", "Bandit", "x_ft", 55, "y_ft", 20))));
			var published = e.maps().publish(op(), campaign, prep.get("preparation").toString(), null);
			String mapRef = published.get("map_ref").toString();
			var refs = SceneService.map(published.get("references"));
			String overseer = refs.get("actor:overseer").toString(), whip = refs.get("actor:whip").toString();
			e.scenes().place(op(), campaign, mapRef, List.of(map("character", "character:1", "x_ft", 35, "y_ft", 5),
					map("character", companion, "x_ft", 30, "y_ft", 5)));
			long cid = Ref.id(campaign, Ref.CAMPAIGN);
			for (var pair : List.of(List.of("character:1", overseer), List.of(companion, whip))) {
				for (var direction : List.of(pair, List.of(pair.get(1), pair.get(0)))) {
					assertEquals(Boolean.TRUE,
							e.db().read(tx -> SceneService.canSee(tx, SceneService.scene(tx, cid, mapRef),
									SceneService.actor(tx, cid, direction.get(0)),
									SceneService.actor(tx, cid, direction.get(1)))));
					assertNotEquals(SpatialRules.Cover.TOTAL,
							e.db().read(tx -> SceneService.cover(tx, SceneService.scene(tx, cid, mapRef),
									SceneService.actor(tx, cid, direction.get(0)),
									SceneService.actor(tx, cid, direction.get(1))).cover()));
				}
			}
			e.runtime().applyRuntimeChange(op(), campaign, "character:1",
					map("kind", "ADD_CONDITION", "condition", "BLINDED", "reason", "Mine dust blinds Tam."));
			assertEquals(Boolean.FALSE, e.db().read(tx -> SceneService.canSee(tx, SceneService.scene(tx, cid, mapRef),
					SceneService.actor(tx, cid, "character:1"), SceneService.actor(tx, cid, overseer))));
			assertEquals(Boolean.TRUE, e.db().read(tx -> SceneService.canSee(tx, SceneService.scene(tx, cid, mapRef),
					SceneService.actor(tx, cid, overseer), SceneService.actor(tx, cid, "character:1"))));
			e.scenes().changeObject(op(), campaign, mapRef, "opening", map("state", "CLOSED"),
					"A shutter seals the gallery opening.");
			assertEquals(Boolean.FALSE, e.db().read(tx -> SceneService.canSee(tx, SceneService.scene(tx, cid, mapRef),
					SceneService.actor(tx, cid, overseer), SceneService.actor(tx, cid, "character:1"))));
			String soundOp = op();
			var heard = e.scenes().sound(soundOp, campaign, mapRef, null, null, null, 100, "A hammer strikes stone.",
					whip);
			assertTrue(((List<?>) heard.get("heard_by")).contains("character:1"));
			var replay = e.scenes().sound(soundOp, campaign, mapRef, null, null, null, 100, "A hammer strikes stone.",
					whip);
			assertEquals(heard.get("heard_by"), replay.get("heard_by"));
			assertEquals(true, replay.get("replayed"));
			var evidence = SceneService.map(e.scenes().perception(campaign, mapRef, "character:1").get("evidence"));
			assertEquals("northeast", SceneService.map(evidence.get("heard")).get("direction"));
			assertEquals(true, SceneService.map(evidence.get("heard")).get("approximate"));
		}
	}
}
