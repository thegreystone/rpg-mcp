/* Copyright (C) 2026 Marcus Hirt. SPDX-License-Identifier: BSD-3-Clause */
package se.hirt.mcp.rpg;

import org.junit.jupiter.api.Test;
import se.hirt.mcp.rpg.maps.MapBundleService;
import se.hirt.mcp.rpg.maps.PngCodec;
import se.hirt.mcp.rpg.persistence.Database;
import se.hirt.mcp.rpg.protocol.Ref;
import se.hirt.mcp.rpg.protocol.RpgException;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;
import static se.hirt.mcp.rpg.TestCampaigns.*;

class MapBundleTest {

	@Test
	void exportsOnlyUsedArtworkAndImportsAReviewableDraftWithNewRoomIdentity() throws Exception {
		Path database = tempDb("portable-map");
		try (Engine e = engine(database)) {
			String source = committedCampaign(e);
			e.sessions().bootstrap(op(), source, null);
			long cid = Ref.id(source, "campaign");
			String asset = register(e, cid, 0xffaa5533), unused = register(e, cid, 0xff33aa55);
			var authored = e.maps().begin(op(), source, "SCENE",
					map("name", "Ruined lodge", "geometry", map("bounds_ft", List.of(0, 0, 30, 20)), "rooms",
							List.of(map("key", "hall", "name", "The hall", "rect_ft", List.of(0, 0, 30, 20), "material",
									asset)),
							"objects", List.of(map("key", "table", "room", "hall", "name", "Broken table", "x_ft", 10,
									"y_ft", 10, "width_ft", 5, "height_ft", 5, "state", "DESTROYED"))));
			assertEquals(true, authored.get("ready"), authored.toString());
			String mapRef = (String) e.maps().publish(op(), source, (String) authored.get("preparation"), null)
					.get("map_ref");
			var bundles = new MapBundleService(e.db(), e.maps(), e.mapAssets(), database.getParent());
			Map<String, Object> exported = bundles.exportMap(source, mapRef);
			Path archive = Path.of(exported.get("path").toString());
			assertTrue(Files.exists(archive));
			assertTrue(archive.startsWith(
					database.getParent().resolve("campaigns").resolve(Long.toString(cid)).resolve("exports")));
			try (ZipFile zip = new ZipFile(archive.toFile(), StandardCharsets.UTF_8)) {
				assertEquals(3, zip.size());
				String manifest = new String(zip.getInputStream(zip.getEntry("manifest.json")).readAllBytes(),
						StandardCharsets.UTF_8);
				assertTrue(manifest.contains(asset));
				assertFalse(manifest.contains(unused));
			}
			String target = committedCampaign(e);
			e.sessions().bootstrap(op(), target, null);
			long tid = Ref.id(target, "campaign");
			long before = e.db().read(tx -> tx.count("SELECT COUNT(*) FROM map_scene WHERE campaign_id=?", tid));
			String operation = op();
			Map<String, Object> imported = bundles.importBundle(operation, target, archive, Map.of());
			assertEquals(false, imported.get("published"));
			assertEquals(before,
					e.db().read(tx -> tx.count("SELECT COUNT(*) FROM map_scene WHERE campaign_id=?", tid)).longValue(),
					"import creates no playable world");
			Map<?, ?> validation = (Map<?, ?>) imported.get("validation");
			assertEquals(true, validation.get("ready"), validation.toString());
			assertEquals(imported.get("preparation"),
					bundles.importBundle(operation, target, archive, Map.of()).get("preparation"));
			Map<String, Object> draft = e.db()
					.read(tx -> tx.get("map_preparation", Ref.id(imported.get("preparation").toString(), "preparation"))
							.map("payload_json"));
			Map<?, ?> room = (Map<?, ?>) ((List<?>) draft.get("rooms")).getFirst();
			assertFalse(room.containsKey("location_ref"));
			assertTrue(room.get("material").toString().startsWith("asset:"));
			assertNotEquals(asset, room.get("material"));
			assertTrue(draft.toString().contains("DESTROYED"));
		}
	}

	@Test
	void copiedActorsRequireMappingsAndCannotReappearAsNewLiveOccupants() throws Exception {
		Path database = tempDb("bundle-actor-review");
		try (Engine e = engine(database)) {
			String source = committedCampaign(e);
			e.sessions().bootstrap(op(), source, null);
			var draft = e.maps().begin(op(), source, "SCENE", map("name", "Guard house", "geometry",
					map("bounds_ft", List.of(0, 0, 20, 20)), "rooms",
					List.of(map("key", "hall", "rect_ft", List.of(0, 0, 20, 20))), "actors",
					List.of(map("key", "guard", "room", "hall", "creature_ref", "Bandit", "x_ft", 5, "y_ft", 5))));
			String mapRef = (String) e.maps().publish(op(), source, draft.get("preparation").toString(), null)
					.get("map_ref");
			var bundles = new MapBundleService(e.db(), e.maps(), e.mapAssets(), database.getParent());
			Path archive = Path.of(bundles.exportMap(source, mapRef).get("path").toString());
			String target = committedCampaign(e);
			e.sessions().bootstrap(op(), target, null);
			long cid = Ref.id(target, "campaign"),
					before = e.db().read(tx -> tx.count("SELECT COUNT(*) FROM character WHERE campaign_id=?", cid));
			Map<String, Object> imported = bundles.importBundle(op(), target, archive, Map.of());
			assertEquals(false, ((Map<?, ?>) imported.get("validation")).get("ready"));
			assertTrue(imported.get("reference_review").toString().contains("REQUIRES_CHARACTER_MAPPING"));
			assertEquals(before,
					e.db().read(tx -> tx.count("SELECT COUNT(*) FROM character WHERE campaign_id=?", cid)).longValue());
		}
	}

	@Test
	void rejectsUnsafeOrUnexpectedArchiveEntriesWithoutCreatingDrafts() throws Exception {
		Path database = tempDb("invalid-map-bundle");
		try (Engine e = engine(database)) {
			String c = committedCampaign(e);
			e.sessions().bootstrap(op(), c, null);
			long cid = Ref.id(c, "campaign");
			long before = e.db().read(tx -> tx.count("SELECT COUNT(*) FROM map_preparation WHERE campaign_id=?", cid));
			for (String name : List.of("../escape.txt", "/absolute.png", "assets/bad.png", "manifest.json/child")) {
				Path file = database.getParent().resolve("unsafe.zip");
				try (var zip = new ZipOutputStream(Files.newOutputStream(file))) {
					zip.putNextEntry(new ZipEntry(name));
					zip.write(new byte[] {1});
					zip.closeEntry();
				}
				assertThrows(RpgException.class,
						() -> new MapBundleService(e.db(), e.maps(), e.mapAssets(), database.getParent())
								.importBundle(op(), c, file, Map.of()));
			}
			assertEquals(before, e.db()
					.read(tx -> tx.count("SELECT COUNT(*) FROM map_preparation WHERE campaign_id=?", cid)).longValue());
			assertFalse(Files.exists(database.getParent().getParent().resolve("escape.txt")));
		}
	}

	private static String register(Engine e, long cid, int color) {
		var imported = e.mapAssets().importPng(cid, PngCodec.encode(new PngCodec.Raster(1, 1, new int[] {color})), 5);
		return e.db().mutate(Database.Mutation.of("asset-fixture", cid, op(), "GM", null), tx -> {
			long id = tx.insert("map_asset", map("campaign_id", cid, "content_hash", imported.id(), "mime_type",
					"image/png", "width", 1, "height", 1, "repeat_ft", 5));
			return Map.of("asset", "asset:" + id);
		}).get("asset").toString();
	}
}
