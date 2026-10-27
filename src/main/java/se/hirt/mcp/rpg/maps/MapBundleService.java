/* Copyright (C) 2026 Marcus Hirt. SPDX-License-Identifier: BSD-3-Clause */
package se.hirt.mcp.rpg.maps;

import se.hirt.mcp.rpg.harness.Harness;
import se.hirt.mcp.rpg.persistence.Database;
import se.hirt.mcp.rpg.persistence.Row;
import se.hirt.mcp.rpg.protocol.Json;
import se.hirt.mcp.rpg.protocol.Ref;
import se.hirt.mcp.rpg.protocol.RpgException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Portable GM map snapshots. Imports create reviewable drafts and never activate copied campaign
 * entities.
 */
public final class MapBundleService {

	public static final int VERSION = 1;
	private static final int MAX_JSON = 4_000_000, MAX_BUNDLE = 64 * 1024 * 1024, MAX_ENTRIES = 256;
	private final Database db;
	private final MapAuthoringService maps;
	private final AssetService assets;
	private final Path dataDirectory;

	public MapBundleService(Database db, MapAuthoringService maps, AssetService assets, Path dataDirectory) {
		this.db = db;
		this.maps = maps;
		this.assets = assets;
		this.dataDirectory = dataDirectory.toAbsolutePath().normalize();
	}

	/** Export current GM geometry/state and only artwork actually referenced by the scene. */
	public Map<String, Object> exportMap(String campaignRef, String mapRef) {
		long cid = Ref.id(campaignRef, Ref.CAMPAIGN);
		Map<String, Object> snapshot = maps.get(campaignRef, mapRef);
		Map<String, Object> document = SceneService.map(snapshot.get("document"));
		if (document.isEmpty())
			throw RpgException.invalidArgument("Export a published map reference.");
		snapshot = new LinkedHashMap<>(snapshot);
		snapshot.put("live_records", db.read(tx -> {
			var inventory = new ArrayList<Map<String, Object>>();
			var custom = new ArrayList<Map<String, Object>>();
			var characters = new ArrayList<Map<String, Object>>();
			var effects = new ArrayList<Map<String, Object>>();
			var rooms = new ArrayList<Map<String, Object>>();
			var seenInventory = new HashSet<Long>();
			var seenCustom = new HashSet<Long>();
			var queue = new java.util.ArrayDeque<Long>();
			for (Map<String, Object> object : SceneService.maps(document.get("objects")))
				if (object.get("entity_ref") instanceof String ref && ref.startsWith("inventory:"))
					queue.add(Ref.id(ref, "inventory"));
			while (!queue.isEmpty()) {
				long id = queue.removeFirst();
				if (!seenInventory.add(id))
					continue;
				if (seenInventory.size() > 2000)
					throw RpgException
							.invalidArgument("Export smaller linked areas; inventory snapshot exceeds 2000 entries.");
				Row entry = tx.find("inventory_entry", id).orElse(null);
				if (entry == null || entry.lng("campaign_id") != cid || !entry.isNull("character_id"))
					continue;
				inventory.add(entry.asMap());
				for (Row child : tx.query("SELECT id FROM inventory_entry WHERE campaign_id=? AND container_entry_id=?",
						cid, id))
					queue.add(child.id());
				if (!entry.isNull("custom_content_id") && seenCustom.add(entry.lng("custom_content_id")))
					custom.add(tx.get("custom_content", entry.lng("custom_content_id")).asMap());
			}
			for (Map<String, Object> actor : SceneService.maps(document.get("actors")))
				if (actor.get("character_ref") instanceof String ref) {
					Row row = tx.get("character", Ref.id(ref, "character"));
					if (row.lng("campaign_id") == cid) {
						characters.add(row.asMap());
						for (Row effect : tx.query("SELECT * FROM active_effect WHERE campaign_id=? AND character_id=?",
								cid, row.id()))
							effects.add(effect.asMap());
					}
				}
			for (Map<String, Object> room : SceneService.maps(document.get("rooms")))
				if (room.get("location_ref") instanceof String ref) {
					Row row = tx.get("location", Ref.id(ref, "location"));
					if (row.lng("campaign_id") == cid)
						rooms.add(row.asMap());
				}
			return Map.of("inventory", inventory, "custom_content", custom, "characters", characters, "active_effects",
					effects, "rooms", rooms, "observations",
					tx.query("SELECT * FROM map_observation WHERE campaign_id=? AND scene_id=?", cid,
							Ref.id(mapRef, "map")).stream().map(Row::asMap).toList());
		}));
		var hashes = new HashSet<String>();
		collectHashes(document, hashes);
		var assetRefs = new HashSet<String>();
		collectAssetRefs(document, assetRefs);
		Map<String, Object> manifest = db.read(tx -> {
			Row campaign = Harness.campaign(tx, campaignRef);
			Row scene = SceneService.scene(tx, cid, mapRef);
			var records = new ArrayList<Map<String, Object>>();
			for (Row asset : tx.query("SELECT * FROM map_asset WHERE campaign_id=? ORDER BY id", cid))
				if (hashes.contains(asset.str("content_hash")) || assetRefs.contains("asset:" + asset.id()))
					records.add(Map.of("source_ref", "asset:" + asset.id(), "content_hash", asset.str("content_hash"),
							"repeat_ft", asset.get("repeat_ft"), "width", asset.get("width"), "height",
							asset.get("height")));
			var meta = new LinkedHashMap<String, Object>();
			meta.put("format", "rpgmap");
			meta.put("version", VERSION);
			meta.put("audience", "GM");
			meta.put("source_campaign", campaignRef);
			meta.put("source_map", mapRef);
			meta.put("layout_revision", scene.lng("layout_revision"));
			meta.put("ruleset", campaign.str("ruleset_namespace") + ":" + campaign.str("ruleset_version"));
			meta.put("assets", records);
			return meta;
		});
		byte[] json = Json.write(snapshot).getBytes(StandardCharsets.UTF_8);
		if (json.length > MAX_JSON)
			throw RpgException.invalidArgument("Export smaller linked map areas; this snapshot exceeds 4 MB.");
		try {
			var archive = new ByteArrayOutputStream();
			try (var zip = new ZipOutputStream(archive, StandardCharsets.UTF_8)) {
				entry(zip, "manifest.json", Json.write(manifest).getBytes(StandardCharsets.UTF_8));
				entry(zip, "map.json", json);
				var written = new HashSet<String>();
				for (Map<String, Object> asset : SceneService.maps(manifest.get("assets"))) {
					String hash = asset.get("content_hash").toString();
					if (!written.add(hash))
						continue;
					assets.load(cid, hash, ((Number) asset.get("repeat_ft")).doubleValue());
					entry(zip, "assets/" + hash + ".png", Files.readAllBytes(assets.assetPath(cid, hash)));
					if (archive.size() > MAX_BUNDLE)
						throw RpgException.invalidArgument("Bundle artwork exceeds 64 MiB; export a smaller area.");
				}
			}
			Path directory = dataDirectory.resolve("campaigns").resolve(Long.toString(cid)).resolve("exports")
					.normalize();
			if (!directory.startsWith(dataDirectory))
				throw RpgException.invalidArgument("Invalid export directory.");
			Files.createDirectories(directory);
			Path target = directory.resolve(
					"map-" + Ref.id(mapRef, "map") + "-" + Json.hash(snapshot).substring(0, 16) + ".rpgmap.zip");
			Files.write(target, archive.toByteArray());
			return Map.of("path", target.toString(), "mime_type", "application/zip", "bytes", archive.size(),
					"format_version", VERSION, "map", mapRef, "audience", "GM", "assets", manifest.get("assets"));
		} catch (IOException e) {
			throw RpgException.invalidArgument("Cannot export map bundle: " + e.getMessage());
		}
	}

	/**
	 * All external paths stay in memory; only validated immutable PNG hashes become asset paths.
	 */
	public Map<String, Object> importBundle(String op, String campaignRef, Path source, Map<String, String> mappings) {
		long cid = Ref.id(campaignRef, Ref.CAMPAIGN);
		Map<String, byte[]> archive = readArchive(source);
		Map<String, Object> manifest = readJson(archive, "manifest.json"), snapshot = readJson(archive, "map.json");
		if (!"rpgmap".equals(manifest.get("format")) || !(manifest.get("version") instanceof Number version)
				|| version.intValue() != VERSION)
			throw RpgException.invalidArgument("Unsupported map bundle format/version.");
		Map<String, Object> document = SceneService.map(snapshot.get("document"));
		if (!(document.get("bounds_ft") instanceof List<?> bounds) || bounds.size() != 4)
			throw RpgException.invalidArgument("Bundle needs saved physical bounds.");
		Map<String, String> requested = mappings == null ? Map.of() : mappings;
		var review = new ArrayList<Map<String, Object>>();
		Map<String, Object> proposal = proposal(snapshot, document, requested, review);
		Path retained = retainBundle(cid, archive,
				Json.hash(Map.of("manifest", manifest, "snapshot_hash", Json.hash(snapshot))));
		proposal.put("import_provenance", Map.of("bundle_path", retained.toString(), "source_map_label",
				"source:" + manifest.getOrDefault("source_map", "UNKNOWN"), "format_version", VERSION));
		var imported = new ArrayList<AssetService.Asset>();
		var needed = new HashSet<String>();
		collectHashes(document, needed);
		var neededRefs = new HashSet<String>();
		collectAssetRefs(document, neededRefs);
		for (Map<String, Object> asset : SceneService.maps(manifest.get("assets"))) {
			String hash = String.valueOf(asset.get("content_hash"));
			if (!hash.matches("[a-f0-9]{64}")
					|| !(needed.contains(hash) || neededRefs.contains(String.valueOf(asset.get("source_ref")))))
				throw RpgException.invalidArgument("Bundle contains an invalid or unreferenced artwork record.");
			byte[] bytes = archive.get("assets/" + hash + ".png");
			if (bytes == null)
				throw RpgException.invalidArgument("Bundle is missing referenced artwork: " + hash);
			double repeat = SceneService.number(asset, "repeat_ft", 5).doubleValue();
			AssetService.Asset importedAsset = assets.importPng(cid, bytes, repeat);
			if (!hash.equals(importedAsset.id()))
				throw RpgException.invalidArgument("Bundle artwork checksum does not match its manifest.");
			imported.add(importedAsset);
		}
		Map<String, Object> result = db.mutate(
				Database.Mutation.of("import_map_bundle", cid, op, "GM",
						Map.of("manifest", manifest, "snapshot_hash", Json.hash(snapshot), "mappings", requested)),
				tx -> {
					Row campaign = Harness.requireMutation(tx, campaignRef, "import_map_bundle");
					for (AssetService.Asset asset : imported)
						if (tx.queryOne(
								"SELECT id FROM map_asset WHERE campaign_id=? AND content_hash=? AND repeat_ft=?", cid,
								asset.id(), asset.repeatFeet()).isEmpty())
							tx.insert("map_asset",
									Map.of("campaign_id", cid, "content_hash", asset.id(), "mime_type", "image/png",
											"width", asset.width(), "height", asset.height(), "repeat_ft",
											asset.repeatFeet()));
					var assetMappings = new LinkedHashMap<String, String>();
					for (Map<String, Object> asset : SceneService.maps(manifest.get("assets")))
						if (asset.get("source_ref") != null) {
							long id = tx.queryOne(
									"SELECT id FROM map_asset WHERE campaign_id=? AND content_hash=? AND repeat_ft=?",
									cid, asset.get("content_hash"), asset.get("repeat_ft")).orElseThrow().id();
							assetMappings.put(asset.get("source_ref").toString(), "asset:" + id);
						}
					Map<String, Object> importedProposal = SceneService.map(neutralizeReferences(
							replaceStrings(proposal, assetMappings), new HashSet<>(requested.values())));
					String kind = String.valueOf(importedProposal.get("kind")),
							stage = kind.equals("REGION") ? "REGION" : "SCENE";
					long id = tx.insert("map_preparation",
							Map.of("campaign_id", cid, "kind", kind, "stage", stage, "name",
									importedProposal.get("name"), "payload_json", Json.write(importedProposal),
									"source_revisions_json",
									Json.write(MapAuthoringService.sources(tx, cid, importedProposal))));
					return Map.of("preparation", "preparation:" + id, "published", false, "reference_review", review,
							"assets_imported", imported.size(), "meta", Harness.meta(campaign, null));
				});
		var response = new LinkedHashMap<>(result);
		response.put("validation", maps.validate(campaignRef, result.get("preparation").toString()));
		response.put("source_bundle", retained.toString());
		response.put("source_snapshot_summary",
				Map.of("name", snapshot.getOrDefault("name", "Imported map"), "rooms",
						SceneService.maps(document.get("rooms")).size(), "actors",
						SceneService.maps(document.get("actors")).size(), "inventory_entries",
						SceneService.maps(SceneService.map(snapshot.get("live_records")).get("inventory")).size()));
		return response;
	}

	private static Map<String, Object> proposal(
		Map<String, Object> snapshot, Map<String, Object> document, Map<String, String> mappings,
		List<Map<String, Object>> review) {
		var proposal = new LinkedHashMap<String, Object>();
		String kind = "REGION".equals(snapshot.get("kind")) ? "REGION" : "SCENE";
		proposal.put("kind", kind);
		proposal.put("name", snapshot.getOrDefault("name", "Imported map"));
		proposal.put("geometry", copy(document));
		var rooms = new ArrayList<Map<String, Object>>();
		var roomKeys = new LinkedHashMap<String, String>();
		var levels = new LinkedHashSet<Integer>();
		int index = 0;
		for (Map<String, Object> source : SceneService.maps(document.get("rooms"))) {
			var room = copy(source);
			String key = String.valueOf(source.getOrDefault("key", "room-" + (++index)));
			room.put("key", key);
			Object old = room.remove("location_ref");
			if (old != null) {
				roomKeys.put(old.toString(), key);
				review.add(Map.of("source_ref", old, "draft_key", "room:" + key, "action",
						mappings.containsKey(old.toString()) ? "MAP_EXISTING" : "CREATE_ON_PUBLICATION"));
				if (mappings.containsKey(old.toString()))
					room.put("location_ref", mappings.get(old.toString()));
			}
			levels.add(SceneService.number(room, "level", 0).intValue());
			rooms.add(room);
		}
		proposal.put("rooms", rooms);
		proposal.put("room_count", rooms.size());
		proposal.put("levels", levels.isEmpty() ? List.of(0) : new ArrayList<>(levels));
		var connections = new ArrayList<Map<String, Object>>();
		index = 0;
		for (Map<String, Object> source : SceneService.maps(document.get("doors"))) {
			var edge = copy(source);
			edge.putIfAbsent("key", "door-" + (++index));
			Object old = edge.remove("connection_ref");
			String from = String.valueOf(edge.getOrDefault("from_location_ref", edge.getOrDefault("from", ""))),
					to = String.valueOf(edge.getOrDefault("to_location_ref", edge.getOrDefault("to", "")));
			edge.put("from", roomKeys.getOrDefault(from, mappings.getOrDefault(from, "UNRESOLVED:" + from)));
			edge.put("to", roomKeys.getOrDefault(to, mappings.getOrDefault(to, "UNRESOLVED:" + to)));
			edge.remove("from_location_ref");
			edge.remove("to_location_ref");
			if (old != null && mappings.containsKey(old.toString()))
				edge.put("connection_ref", mappings.get(old.toString()));
			connections.add(edge);
		}
		proposal.put("connections", connections);
		var objects = new ArrayList<Map<String, Object>>();
		var rewards = new ArrayList<Map<String, Object>>();
		index = 0;
		for (Map<String, Object> source : SceneService.maps(document.get("objects"))) {
			var item = copy(source);
			item.putIfAbsent("key", "object-" + (++index));
			Object room = item.remove("room_ref");
			if (room != null)
				item.put("room", roomKeys.getOrDefault(room.toString(),
						mappings.getOrDefault(room.toString(), "UNRESOLVED:" + room)));
			Object entity = item.remove("entity_ref");
			item.remove("object_ref");
			item.remove("revision");
			if (entity != null) {
				review.add(Map.of("source_ref", entity, "draft_key", "object:" + item.get("key"), "action",
						"REVIEW_INVENTORY_BINDING"));
				item.put("source_entity_ref", entity);
				if (entity.toString().startsWith("inventory:")) {
					var reward = new LinkedHashMap<String, Object>();
					reward.put("key", "import-loot-" + rewards.size());
					reward.put("item_ref", "UNRESOLVED SOURCE INVENTORY " + entity);
					reward.put("quantity", item.getOrDefault("quantity", 1));
					if (item.get("room") != null)
						reward.put("room", item.get("room"));
					rewards.add(reward);
				}
			}
			objects.add(item);
		}
		proposal.put("objects", objects);
		proposal.put("rewards", rewards);
		var actors = new ArrayList<Map<String, Object>>();
		index = 0;
		for (Map<String, Object> source : SceneService.maps(document.get("actors"))) {
			var actor = copy(source);
			String old = String.valueOf(actor.getOrDefault("character_ref", actor.getOrDefault("character", "")));
			actor.put("key", "actor-" + (++index));
			actor.remove("character");
			actor.remove("revision");
			actor.put("character_ref", mappings.getOrDefault(old, "unresolved:" + index));
			review.add(Map.of("source_ref", old, "draft_key", "actor:" + actor.get("key"), "action",
					mappings.containsKey(old) ? "MAP_EXISTING" : "REQUIRES_CHARACTER_MAPPING"));
			actors.add(actor);
		}
		proposal.put("actors", actors);
		var features = new ArrayList<Map<String, Object>>();
		for (Map<String, Object> source : SceneService.maps(document.get("features"))) {
			var feature = copy(source);
			Object ref = feature.remove("graph_ref");
			if (ref != null) {
				review.add(Map.of("source_ref", ref, "draft_key", "feature:" + feature.getOrDefault("key", "feature"),
						"action", mappings.containsKey(ref.toString()) ? "MAP_EXISTING" : "REVIEW_GRAPH_BINDING"));
				if (mappings.containsKey(ref.toString()))
					feature.put("graph_ref", mappings.get(ref.toString()));
			}
			features.add(feature);
		}
		proposal.put("features", features);
		// Historic IDs stay in the review snapshot; they cannot become live world references by accident.
		for (String field : List.of("location_ref", "atlas_ref", "address", "journey_ref", "route_anchor", "actors",
				"objects", "doors", "portals", "rooms", "features", "annotations", "visibility", "world_origin_ft"))
			SceneService.map(proposal.get("geometry")).remove(field);
		return proposal;
	}

	private static Map<String, byte[]> readArchive(Path source) {
		try {
			if (source == null || !Files.isRegularFile(source) || Files.size(source) > MAX_BUNDLE)
				throw RpgException.invalidArgument("Map bundle must be an existing ZIP no larger than 64 MiB.");
			var entries = new LinkedHashMap<String, byte[]>();
			int total = 0;
			try (var zip = new ZipInputStream(Files.newInputStream(source), StandardCharsets.UTF_8)) {
				ZipEntry entry;
				while ((entry = zip.getNextEntry()) != null) {
					String name = entry.getName();
					if (entries.size() >= MAX_ENTRIES || entry.isDirectory() || !(name.equals("manifest.json")
							|| name.equals("map.json") || name.matches("assets/[a-f0-9]{64}\\.png"))
							|| entries.containsKey(name))
						throw RpgException
								.invalidArgument("Bundle has an unsupported, duplicate or unsafe entry name.");
					int limit = name.endsWith(".json") ? MAX_JSON : PngCodec.MAX_FILE_BYTES;
					byte[] bytes = zip.readNBytes(Math.min(limit, MAX_BUNDLE - total) + 1);
					total += bytes.length;
					if (bytes.length > limit || total > MAX_BUNDLE)
						throw RpgException.invalidArgument("Map bundle exceeds its decoded size limits.");
					entries.put(name, bytes);
					zip.closeEntry();
				}
			}
			if (!entries.containsKey("manifest.json") || !entries.containsKey("map.json"))
				throw RpgException.invalidArgument("Bundle must include manifest.json and map.json.");
			return entries;
		} catch (IOException e) {
			throw RpgException.invalidArgument("Cannot read map bundle: " + e.getMessage());
		}
	}

	private static Map<String, Object> readJson(Map<String, byte[]> archive, String name) {
		return Json.readMap(new String(archive.get(name), StandardCharsets.UTF_8));
	}

	private Path retainBundle(long cid, Map<String, byte[]> archive, String hash) {
		try {
			Path directory = dataDirectory.resolve("campaigns").resolve(Long.toString(cid)).resolve("imports")
					.normalize();
			if (!directory.startsWith(dataDirectory))
				throw RpgException.invalidArgument("Invalid import directory.");
			Files.createDirectories(directory);
			Path target = directory.resolve(hash + ".rpgmap.zip");
			if (!Files.exists(target)) {
				Path temporary = Files.createTempFile(directory, "bundle-", ".tmp");
				try {
					try (var zip = new ZipOutputStream(Files.newOutputStream(temporary), StandardCharsets.UTF_8)) {
						for (var item : archive.entrySet())
							entry(zip, item.getKey(), item.getValue());
					}
					try {
						Files.move(temporary, target);
					} catch (java.nio.file.FileAlreadyExistsException ignored) {
					}
				} finally {
					Files.deleteIfExists(temporary);
				}
			}
			return target;
		} catch (IOException e) {
			throw RpgException.invalidArgument("Cannot retain imported bundle: " + e.getMessage());
		}
	}

	private static void entry(ZipOutputStream zip, String name, byte[] bytes) throws IOException {
		ZipEntry entry = new ZipEntry(name);
		entry.setTime(0);
		zip.putNextEntry(entry);
		zip.write(bytes);
		zip.closeEntry();
	}

	private static void collectHashes(Object value, Set<String> hashes) {
		if (value instanceof String s && s.matches("[a-f0-9]{64}"))
			hashes.add(s);
		else if (value instanceof Map<?, ?> m)
			for (Object item : m.values())
				collectHashes(item, hashes);
		else if (value instanceof List<?> l)
			for (Object item : l)
				collectHashes(item, hashes);
	}

	private static void collectAssetRefs(Object value, Set<String> refs) {
		if (value instanceof String s && s.matches("asset:[1-9][0-9]*"))
			refs.add(s);
		else if (value instanceof Map<?, ?> m)
			for (Object item : m.values())
				collectAssetRefs(item, refs);
		else if (value instanceof List<?> l)
			for (Object item : l)
				collectAssetRefs(item, refs);
	}

	private static Object replaceStrings(Object value, Map<String, String> replacements) {
		if (value instanceof String s)
			return replacements.getOrDefault(s, s);
		if (value instanceof Map<?, ?> source) {
			var result = new LinkedHashMap<String, Object>();
			for (var entry : source.entrySet())
				result.put(String.valueOf(entry.getKey()), replaceStrings(entry.getValue(), replacements));
			return result;
		}
		if (value instanceof List<?> source) {
			var result = new ArrayList<Object>();
			for (Object item : source)
				result.add(replaceStrings(item, replacements));
			return result;
		}
		return value;
	}

	private static Object neutralizeReferences(Object value, Set<String> allowed) {
		if (value instanceof String s && s.matches("(location|character|map|atlas|connection|inventory):[1-9][0-9]*"))
			return allowed.contains(s) ? s : "SOURCE_REF(" + s + ")";
		if (value instanceof Map<?, ?> source) {
			var result = new LinkedHashMap<String, Object>();
			for (var entry : source.entrySet())
				result.put(String.valueOf(entry.getKey()), neutralizeReferences(entry.getValue(), allowed));
			return result;
		}
		if (value instanceof List<?> source) {
			var result = new ArrayList<Object>();
			for (Object item : source)
				result.add(neutralizeReferences(item, allowed));
			return result;
		}
		return value;
	}

	private static Map<String, Object> copy(Map<String, Object> map) {
		return Json.readMap(Json.write(map));
	}
}
