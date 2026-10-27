package se.hirt.mcp.rpg;

import org.junit.jupiter.api.Test;
import se.hirt.mcp.rpg.maps.SceneService;
import se.hirt.mcp.rpg.protocol.Ref;
import se.hirt.mcp.rpg.protocol.RpgException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static se.hirt.mcp.rpg.TestCampaigns.*;

class Run6TransitionTest {
	@Test
	void occupiedLandingNamesTheNpcAndPassageWithoutSpendingTimeThenWorksAfterTheNpcMoves() throws Exception {
		try (Engine e = engine(tempDb("run6-transition-occupant"))) {
			String campaign = committedCampaign(e);
			e.sessions().bootstrap(op(), campaign, null);
			long cid = Ref.id(campaign, Ref.CAMPAIGN);
			String home = e.db().read(tx -> Ref.of(Ref.LOCATION, tx.get("campaign", cid).lng("current_location_id")));
			var draft = e.maps().begin(op(), campaign, "SCENE", map("name", "Ore chute", "location_ref", home,
					"room_count", 2, "levels", List.of(0, -1), "illumination", "BRIGHT", "geometry",
					map("bounds_ft", List.of(0, 0, 60, 20)), "rooms",
					List.of(map("key", "upper", "level", 0, "rect_ft", List.of(0, 0, 20, 20)),
							map("key", "lower", "level", -1, "rect_ft", List.of(40, 0, 20, 20))),
					"connections",
					List.of(map("key", "chute", "kind", "PORTAL", "from", "upper", "to", "lower", "from_position_ft",
							List.of(5, 5), "to_position_ft", List.of(45, 5), "movement_ft", 10)),
					"actors", List.of(map("key", "colm", "room", "lower", "creature_ref", "Bandit", "name", "Colm",
							"x_ft", 45, "y_ft", 5))));
			var published = e.maps().publish(op(), campaign, draft.get("preparation").toString(), null);
			String map = published.get("map_ref").toString(),
					colm = SceneService.map(published.get("references")).get("actor:colm").toString();
			e.scenes().place(op(), campaign, map,
					List.of(map("character", "character:1", "x_ft", 5, "y_ft", 5, "level", 0)));
			long marker = e.db().read(tx -> SceneService.actor(tx, cid, "character:1").id());
			var before = e.db().snapshot(cid);
			RpgException error = assertThrows(RpgException.class,
					() -> e.scenes().transit(op(), campaign, map, "character:1", "chute"));
			assertTrue(error.getMessage().contains("Colm (" + colm + ")"), error.getMessage());
			assertTrue(error.getMessage().contains("chute"));
			assertTrue(error.getMessage().contains(map));
			assertTrue(error.getMessage().contains("level -1"));
			assertEquals(before, e.db().snapshot(cid));
			e.scenes().move(op(), campaign, map, colm, 50, 5);
			var moved = e.scenes().transit(op(), campaign, map, "character:1", "chute");
			assertEquals(false, moved.get("interrupted"));
			assertEquals(marker, e.db().read(tx -> SceneService.actor(tx, cid, "character:1").id()).longValue());
			assertEquals(-1,
					e.db().read(tx -> SceneService.actor(tx, cid, "character:1").intOr("level", 0)).intValue());
		}
	}
}
