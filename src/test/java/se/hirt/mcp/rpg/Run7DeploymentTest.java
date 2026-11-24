package se.hirt.mcp.rpg;

import org.junit.jupiter.api.Test;
import se.hirt.mcp.rpg.maps.SceneService;
import se.hirt.mcp.rpg.protocol.Ref;
import se.hirt.mcp.rpg.spatial.Geometry.Footprint;
import se.hirt.mcp.rpg.spatial.SpatialRules;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static se.hirt.mcp.rpg.TestCampaigns.*;

class Run7DeploymentTest {

	@Test
	void freshPartyMaximizesFeasibleSeparationFromAnOpponentExplicitlyPlacedInTheSameStart() throws Exception {
		try (Engine e = engine(tempDb("run7-best-feasible-spacing"))) {
			String campaign = committedCampaign(e);
			e.sessions().bootstrap(op(), campaign, null);
			String ally = npc(e, campaign, "Oddny"), clerk = npc(e, campaign, "The clerk");
			e.party().updateMembership(op(), campaign, ally, "JOIN", "An established companion.");
			String opening = e.db().read(tx -> Ref.of(Ref.LOCATION,
					tx.get("campaign", Ref.id(campaign, Ref.CAMPAIGN)).lng("current_location_id")));
			var draft = e.maps().begin(op(), campaign, "SCENE",
					map("name", "Published tavern", "location_ref", opening, "room_count", 1, "geometry",
							map("bounds_ft", List.of(0, 0, 40, 40)), "rooms", List.of(map("key", "taproom", "name",
									"Taproom", "role", "ENTRY", "rect_ft", List.of(0, 0, 40, 40)))));
			String board = e.maps().publish(op(), campaign, draft.get("preparation").toString(), null).get("map_ref")
					.toString();
			var sides = map("party", List.of("character:1", ally), "foes", List.of(clerk));
			var started = e.encounters().start(op(), campaign, sides, null, null, null, null, null,
					map("placements", List.of(map("character", clerk, "x_ft", 15, "y_ft", 15))));
			var clerkFigure = figure(e, campaign, clerk);
			assertEquals(List.of(15.0, 15.0), SceneService.feet(clerkFigure.origin()));
			for (String member : List.of("character:1", ally)) {
				var placed = figure(e, campaign, member);
				assertEquals(20 * 12, SpatialRules.distance(clerkFigure, placed),
						"Thirty feet cannot fit in this room; twenty is feasible and must beat the nearer anchor.");
				assertTrue(placed.bounds().minX() >= 0 && placed.bounds().maxX() <= 40 * 12);
				assertTrue(placed.bounds().minY() >= 0 && placed.bounds().maxY() <= 40 * 12);
			}
			assertEquals(board, SceneService.map(started.get("grid")).get("map_ref"));
			assertTrue(SceneService.maps(SceneService.map(started.get("grid")).get("party_deployment")).stream()
					.allMatch(person -> SceneService.maps(person.get("rooms")).stream()
							.anyMatch(room -> "Taproom".equals(room.get("name")))));
			var established = List.of(figure(e, campaign, "character:1"), figure(e, campaign, ally), clerkFigure);
			e.encounters().end(op(), campaign, started.get("encounter").toString(), "OTHER", "The sides stand down.");
			e.encounters().start(op(), campaign, sides, null, null, null, null, null);
			assertEquals(established,
					List.of(figure(e, campaign, "character:1"), figure(e, campaign, ally), figure(e, campaign, clerk)),
					"Subsequent starts retain all established cells.");
		}
	}

	@Test
	void explicitClosePartyPlacementWinsWhileTheFreshAllyStillUsesBestFeasibleSeparation() throws Exception {
		try (Engine e = engine(tempDb("run7-explicit-spacing-wins"))) {
			String campaign = committedCampaign(e);
			e.sessions().bootstrap(op(), campaign, null);
			String ally = npc(e, campaign, "Oddny"), clerk = npc(e, campaign, "The clerk");
			e.party().updateMembership(op(), campaign, ally, "JOIN", "An established companion.");
			e.encounters().start(op(), campaign, map("party", List.of("character:1", ally), "foes", List.of(clerk)),
					null, null, null, null, null,
					map("scene", map("name", "Small tavern", "bounds_ft", List.of(0, 0, 40, 40)), "placements",
							List.of(map("character", "character:1", "x_ft", 10, "y_ft", 15),
									map("character", clerk, "x_ft", 15, "y_ft", 15))));
			assertEquals(List.of(10.0, 15.0), SceneService.feet(figure(e, campaign, "character:1").origin()));
			assertEquals(20 * 12, SpatialRules.distance(figure(e, campaign, clerk), figure(e, campaign, ally)));
		}
	}

	private static String npc(Engine e, String campaign, String name) {
		return e.runtime().materialize(op(), campaign, "Bandit", name, null, null, null, null, false).get("character")
				.toString();
	}

	private static Footprint figure(Engine e, String campaign, String character) {
		return e.db()
				.read(tx -> SceneService.footprint(SceneService.actor(tx, Ref.id(campaign, Ref.CAMPAIGN), character)));
	}
}
