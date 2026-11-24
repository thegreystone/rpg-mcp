package se.hirt.mcp.rpg;

import org.junit.jupiter.api.Test;
import se.hirt.mcp.rpg.maps.SceneService;
import se.hirt.mcp.rpg.protocol.Json;
import se.hirt.mcp.rpg.protocol.Ref;
import se.hirt.mcp.rpg.protocol.RpgException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static se.hirt.mcp.rpg.TestCampaigns.*;

/** Scripted player/GM campaign sessions using the real public services, persistence and PNGs. */
class MapCampaignPlaytestTest {
	@Test
	void worldBuilderAddsReportsAndPlacesAndRestylesTheSameGeography() throws Exception {
		try (Engine e = engine(tempDb("sarg-world-campaign"))) {
			String c = committedCampaign(e);
			e.sessions().bootstrap(op(), c, null);
			String mapRef = publishRegionalFixture(e, c);
			Map<String, Object> initial = m(e.scenes().context(c, mapRef, "PLAYER", 0).get("view"));
			var paths = SceneService.maps(initial.get("features")).stream().filter(f -> f.containsKey("path_ft"))
					.map(f -> Json.write(f.get("path_ft"))).toList();
			assertFalse(paths.isEmpty());
			e.maps().annotate(op(), c, mapRef, null,
					map("kind", "REPORT", "key", "nefarian-rumor", "text",
							"At the inn you hear of Castle Nefaria, in the eastern hills near Travaris.", "observer",
							"PARTY", "source", "Innkeeper", "precision", "APPROXIMATE"));
			var castle = e.maps().annotate(op(), c, mapRef, null,
					map("kind", "WORLD_POI", "key", "castle-nefaria", "name", "Castle Nefaria", "position_ft",
							List.of(350000, 300000), "create_location", map("name", "Castle Nefaria", "kind", "SITE"),
							"visibility", "PARTY_KNOWN"));
			assertNotNull(castle.get("location_ref"), "The discovered castle must have a canonical location.");
			var changed = m(e.scenes().context(c, mapRef, "PLAYER", 0).get("view"));
			assertEquals(paths, SceneService.maps(changed.get("features")).stream()
					.filter(f -> f.containsKey("path_ft")).map(f -> Json.write(f.get("path_ft"))).toList());
			assertTrue(Json.write(changed).contains("Castle Nefaria"));
			Path out = Path.of("target/map-playtests");
			Files.createDirectories(out);
			Files.write(out.resolve("04-opening-region.png"),
					e.mapRenderer().render(changed, Map.of("max_pixels", 1200, "style", "DEFAULT")).png());
			var parchment = e.mapRenderer().render(changed, Map.of("max_pixels", 1200, "style", "PARCHMENT"));
			Files.write(out.resolve("05-opening-region-parchment.png"), parchment.png());
			assertEquals(false, parchment.transform().get("grid_visible"));
			assertTrue(parchment.text().contains("mi;"));
			Files.writeString(out.resolve("world-building-session.txt"),
					"Created the opening region with settlements, winding roads, coast, forest, river and mountain ridge.\nRecorded an approximate innkeeper report without inventing coordinates.\nAdded Castle Nefaria at an exact chosen point and linked its canonical location.\nRendered two styles; every saved road and river bend remained identical.\n");
		}
	}

	@Test
	void exploreTheBanditLodgeLootBreakFurnitureAndReturn() throws Exception {
		Path db = tempDb("lodge-campaign");
		String campaign, mapRef, hero, guard, staff, chest;
		Map<String, Object> lastView;
		var transcript = new ArrayList<String>();
		try (Engine e = engine(db)) {
			campaign = committedCampaign(e);
			e.sessions().bootstrap(op(), campaign, null);
			hero = e.db()
					.read(tx -> "character:" + tx.queryOne(
							"SELECT character_id FROM player_control_assignment WHERE campaign_id=? AND active=1",
							Ref.id(campaign, "campaign")).orElseThrow().lng("character_id"));
			String opening = e.db().read(
					tx -> "location:" + tx.get("campaign", Ref.id(campaign, "campaign")).lng("current_location_id"));
			Map<String, Object> proposal = map("name", "The Foxglove Lodge", "location_ref", opening, "room_count", 2,
					"levels", 1, "geometry", map("bounds_ft", List.of(0, 0, 60, 30)), "rooms",
					List.of(map("key", "entry", "name", "Entry hall", "level", 0, "rect_ft", List.of(0, 0, 30, 30),
							"material", "STONE"),
							map("key", "store", "name", "Bandit storeroom", "level", 0, "rect_ft",
									List.of(30, 0, 30, 30), "material", "WOOD")),
					"connections",
					List.of(map("key", "oak-door", "from", "entry", "to", "store", "state", "LOCKED", "from_ft",
							List.of(30, 10), "to_ft", List.of(30, 15))),
					"objects",
					List.of(map("key", "chest", "room", "store", "name", "Ironbound chest", "item_ref", "Chest", "x_ft",
							50, "y_ft", 20, "locked", true),
							map("key", "table", "room", "store", "name", "Oak table", "x_ft", 40, "y_ft", 20,
									"width_ft", 5, "height_ft", 5, "blocks_movement", true)),
					"rewards",
					List.of(map("key", "staff", "room", "store", "name", "A ceremonial quarterstaff", "item_ref",
							"Quarterstaff", "container", "chest", "x_ft", 50, "y_ft", 20)),
					"actors", List.of(map("key", "lookout", "room", "store", "name", "Rusk the lookout", "creature_ref",
							"Bandit", "x_ft", 45, "y_ft", 10)));
			var draft = e.maps().begin(op(), campaign, "SCENE", proposal);
			String prep = (String) draft.get("preparation");
			assertEquals(true, draft.get("ready"), draft.toString());
			var published = e.maps().publish(op(), campaign, prep, null);
			mapRef = (String) published.get("map_ref");
			var refs = m(published.get("references"));
			guard = (String) refs.get("actor:lookout");
			staff = (String) refs.get("inventory:staff");
			chest = (String) refs.get("inventory:chest");
			transcript.add("GM: A locked oak door separates the hall from the bandit storeroom. Published " + mapRef
					+ " with stable occupants and loot.");
			e.scenes().place(op(), campaign, mapRef, List.of(map("character", hero, "x_ft", 20, "y_ft", 10)));
			Map<String, Object> closed = view(e, campaign, mapRef);
			assertFalse(Json.write(closed).contains("Rusk"));
			assertFalse(Json.write(closed).contains("ceremonial"));
			assertFalse(
					Json.write(e.scenes().perception(campaign, mapRef, guard).get("currently_seen")).contains(hero));
			savePng(e, closed, "01-lodge-closed.png");
			transcript.add(
					"Player: I stand just west of the door. Player PNG and text hide Rusk and the chest; Rusk cannot see me either.");
			e.scenes().sound(op(), campaign, mapRef, 22.5, 12.5, 0, 60, "Someone tests the door handle.");
			assertEquals("SUSPICIOUS",
					m(e.scenes().perception(campaign, mapRef, guard).get("evidence")).get("awareness"));
			assertFalse(
					Json.write(e.scenes().perception(campaign, mapRef, guard).get("evidence")).contains("position_ft"));
			transcript.add(
					"GM: Rusk hears a muffled handle rattle and may investigate an approximate direction; no exact player position is disclosed.");
			e.scenes().changeObject(op(), campaign, mapRef, "oak-door", map("state", "OPEN"),
					"Resolved lock-picking check opens the door.");
			Map<String, Object> open = view(e, campaign, mapRef);
			assertTrue(Json.write(open).contains("Rusk"));
			assertFalse(Json.write(open).contains("ceremonial"),
					"A locked chest conceals its contents even when the room is visible.");
			savePng(e, open, "02-lodge-open.png");
			transcript
					.add("Player: I open the door. Rusk and the visible portion of the storeroom appear immediately.");
			String c = campaign, s = staff, h = hero;
			assertThrows(RpgException.class, () -> e.inventory().transfer(op(), c, s, h, null, "take the staff"));
			e.scenes().changeObject(op(), campaign, mapRef, "object:chest", map("locked", false, "state", "OPEN"),
					"Resolved chest lock-picking check succeeds.");
			e.scenes().move(op(), campaign, mapRef, hero, 45, 15);
			e.inventory().transfer(op(), campaign, staff, hero, null,
					"Take the ceremonial staff from the opened chest.");
			e.maps().publish(op(), campaign, prep, null);
			assertEquals(1L,
					e.db().read(tx -> tx.count("SELECT COUNT(*) FROM inventory_entry WHERE id=? AND character_id=?",
							Ref.id(s, "inventory"), Ref.id(h, "character"))).longValue());
			assertEquals(0L,
					e.db().read(tx -> tx.count("SELECT COUNT(*) FROM inventory_entry WHERE container_entry_id=?",
							Ref.id(chest, "inventory"))).longValue());
			transcript.add(
					"Player: I unlock the chest and take the staff. A locked-container attempt was rejected; the successful transfer leaves exactly one canonical staff.");
			e.scenes().changeObject(op(), campaign, mapRef, "object:table",
					map("state", "DESTROYED", "blocks_movement", false),
					"The resolved battle damage shatters the table.");
			e.scenes().move(op(), campaign, mapRef, hero, 40, 20);
			assertEquals(40.0, e.db()
					.read(tx -> ((Number) SceneService.actor(tx, Ref.id(c, "campaign"), h).get("x_ft")).doubleValue()));
			e.scenes().move(op(), campaign, mapRef, hero, 20, 10);
			e.scenes().changeObject(op(), campaign, mapRef, "oak-door", map("state", "LOCKED"),
					"Rusk closes and relocks the door after the party withdraws.");
			Map<String, Object> hidden = view(e, campaign, mapRef);
			assertFalse(Json.write(hidden).contains("Rusk"));
			assertTrue(Json.write(hidden).contains("DESTROYED"));
			transcript.add(
					"Player: I cross the space where the table stood, withdraw and lose sight of Rusk. The explored room remembers the broken table.");
			lastView = hidden;
		}
		try (Engine e = engine(db)) {
			assertEquals(lastView, view(e, campaign, mapRef));
			assertEquals(1L,
					e.db().read(tx -> tx.count("SELECT COUNT(*) FROM inventory_entry WHERE id=? AND character_id=?",
							Ref.id(staff, "inventory"), Ref.id(hero, "character"))).longValue());
			savePng(e, view(e, campaign, mapRef), "03-lodge-return.png");
			transcript.add(
					"Fresh server: explored geometry, closed door, remembered destruction and the staff's new owner survive reload.");
		}
		Path out = Path.of("target/map-playtests");
		Files.createDirectories(out);
		Files.write(out.resolve("lodge-session.txt"), transcript);
	}

	@Test
	void sharedSightDoesNotGiveAnIndividualAnAttackThroughTheWall() throws Exception {
		try (Engine e = engine(tempDb("split-sight"))) {
			String c = committedCampaign(e);
			e.sessions().bootstrap(op(), c, null);
			String hero = e.db()
					.read(tx -> "character:" + tx.queryOne(
							"SELECT character_id FROM player_control_assignment WHERE campaign_id=? AND active=1",
							Ref.id(c, "campaign")).orElseThrow().lng("character_id"));
			String opening = e.db()
					.read(tx -> "location:" + tx.get("campaign", Ref.id(c, "campaign")).lng("current_location_id"));
			var draft = e.maps().begin(op(), c, "SCENE",
					map("name", "Corner patrol", "location_ref", opening, "room_count", 2, "geometry",
							map("bounds_ft", List.of(0, 0, 40, 20)), "rooms",
							List.of(map("key", "west", "rect_ft",
									List.of(0, 0, 20, 20)), map("key", "east", "rect_ft", List.of(20, 0, 20, 20))),
							"connections",
							List.of(map("key", "door", "from", "west", "to", "east", "state", "LOCKED", "from_ft",
									List.of(20, 5), "to_ft", List.of(20, 10))),
							"actors",
							List.of(map("key", "scout", "room", "east", "creature_ref", "Scout", "name", "Mira", "x_ft",
									25, "y_ft", 5),
									map("key", "guard", "room", "east", "creature_ref", "Bandit", "name",
											"Eastern guard", "x_ft", 30, "y_ft", 5))));
			var p = e.maps().publish(op(), c, (String) draft.get("preparation"), null);
			String scene = (String) p.get("map_ref"), scout = (String) m(p.get("references")).get("actor:scout");
			e.party().updateMembership(op(), c, scout, "JOIN", "Mira travels with the player.");
			e.scenes().place(op(), c, scene, List.of(map("character", hero, "x_ft", 5, "y_ft", 5)));
			e.scenes().observe(op(), c, scene);
			assertTrue(Json.write(view(e, c, scene)).contains("Eastern guard"));
			assertFalse(
					Json.write(e.scenes().perception(c, scene, hero).get("currently_seen")).contains("position_ft"));
			assertTrue(
					Json.write(e.scenes().perception(c, scene, scout).get("currently_seen")).contains("position_ft"));
		}
	}

	private static Map<String, Object> view(Engine e, String c, String s) {
		return m(e.scenes().context(c, s, "PLAYER", 0).get("view"));
	}

	private static void savePng(Engine e, Map<String, Object> view, String name) throws Exception {
		Path dir = Path.of("target/map-playtests");
		Files.createDirectories(dir);
		Files.write(dir.resolve(name),
				e.mapRenderer().render(view, Map.of("max_pixels", 960, "style", "HIGH_CONTRAST")).png());
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> m(Object v) {
		return (Map<String, Object>) v;
	}
}
