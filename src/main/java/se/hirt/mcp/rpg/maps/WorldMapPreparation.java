package se.hirt.mcp.rpg.maps;

import se.hirt.mcp.rpg.content.RulesData;
import se.hirt.mcp.rpg.persistence.Row;
import se.hirt.mcp.rpg.persistence.Tx;
import se.hirt.mcp.rpg.protocol.Json;

import java.util.*;

/**
 * Initial region defaults and bounded, sourced legacy authoring context. No model provider needed.
 */
public final class WorldMapPreparation {

	private WorldMapPreparation() {
	}

	public static Map<String, Object> initialize(
		Tx tx, RulesData rules, long campaignId, long openingId, Map<String, Object> adventure) {
		Map<String, Object> proposal;
		if (adventure.get("world_map") instanceof Map<?, ?> authored) {
			proposal = object(Json.readMap(Json.write(authored)));
			proposal.putIfAbsent("name", tx.get("location", openingId).str("name") + " region");
			List<Map<String, Object>> rooms = objects(proposal.get("rooms"));
			for (Map<String, Object> room : rooms) {
				if (Boolean.TRUE.equals(room.get("opening"))
						|| tx.get("location", openingId).str("name").equalsIgnoreCase(String.valueOf(room.get("name"))))
					room.put("location_ref", "location:" + openingId);
			}
			proposal.put("rooms", rooms);
		} else {
			proposal = defaultRegion(tx, campaignId, openingId, adventure, true);
			long id = tx.insert("map_preparation",
					map("campaign_id", campaignId, "kind", "REGION", "stage", "REGION", "location_id",
							proposal.get("location_ref") == null ? null
									: se.hirt.mcp.rpg.protocol.Ref.id(proposal.get("location_ref").toString(),
											"location"),
							"name", proposal.get("name"), "payload_json", Json.write(proposal), "source_revisions_json",
							Json.write(MapAuthoringService.sources(tx, campaignId, proposal))));
			tx.touched("preparation:" + id, 0);
			return map("status", "AUTHORING_REQUIRED", "preparation", "preparation:" + id, "next_operation",
					"revise_map_preparation", "requirements", proposal.get("authoring_requirements"), "note",
					"The campaign's established places and routes are staged. Author its actual geography before publishing the initial atlas.");
		}
		Map<String, Object> result = new MapAuthoringService(null, rules).publishRegionInTx(tx, campaignId, proposal);
		return map("map", result.get("map_ref"), "atlas", object(result.get("references")).get("atlas"), "preparation",
				result.get("preparation"), "address", object(object(result.get("map")).get("document")).get("address"));
	}

	/** Mutating resume creates one draft; read-only status never invokes this method. */
	public static Map<String, Object> resume(Tx tx, long campaignId) {
		List<Row> atlases = tx.query("SELECT * FROM map_atlas WHERE campaign_id=? ORDER BY id LIMIT 8", campaignId);
		if (!atlases.isEmpty()) {
			List<Map<String, Object>> index = new ArrayList<>();
			for (Row atlas : atlases)
				index.add(map("atlas", "atlas:" + atlas.id(), "name", atlas.str("name"), "sheet_width_ft",
						atlas.get("sheet_width_ft"), "sheet_height_ft", atlas.get("sheet_height_ft"), "sheets",
						tx.query(
								"SELECT id,name,sheet_x,sheet_y FROM map_scene WHERE campaign_id=? AND atlas_id=? ORDER BY sheet_y DESC,sheet_x LIMIT 16",
								campaignId, atlas.id()).stream()
								.map(s -> map("map", "map:" + s.id(), "name", s.str("name"), "address",
										new AtlasCoordinates.Sheet(s.lng("sheet_x"), s.lng("sheet_y"))
												.address(atlas.id())))
								.toList()));
			return map("status", "AVAILABLE", "atlases", index, "audience", "GM");
		}
		Optional<Row> pending = tx.queryOne(
				"SELECT * FROM map_preparation WHERE campaign_id=? AND kind='REGION' AND status<>'PUBLISHED' ORDER BY id LIMIT 1",
				campaignId);
		Row preparation;
		if (pending.isPresent())
			preparation = pending.get();
		else {
			Row campaign = tx.get("campaign", campaignId);
			Map<String, Object> adventure = campaign.isNull("adventure_json") ? Map.of()
					: campaign.map("adventure_json");
			Long opening = campaign.lng("current_location_id");
			if (opening == null)
				return map("status", "NEEDS_LOCATION", "audience", "GM");
			Map<String, Object> proposal = defaultRegion(tx, campaignId, opening, adventure, false);
			proposal.put("legacy_completion", true);
			proposal.put("authoring_brief", brief());
			List<Map<String, Object>> constraints = new ArrayList<>();
			for (Row connection : tx
					.query("SELECT * FROM location_connection WHERE campaign_id=? ORDER BY id LIMIT 512", campaignId))
				constraints.add(map("connection_ref", "connection:" + connection.id(), "from",
						"location:" + connection.lng("location_a_id"), "to",
						"location:" + connection.lng("location_b_id"), "kind", connection.str("kind"), "distance",
						connection.isNull("distance_json") ? Map.of() : connection.map("distance_json"), "state",
						connection.isNull("state_json") ? Map.of() : connection.map("state_json"), "source",
						"CANONICAL_GRAPH"));
			proposal.put("legacy_constraints", constraints);
			proposal.put("legacy_locations", tx
					.query("SELECT * FROM location WHERE campaign_id=? ORDER BY id LIMIT 256", campaignId).stream()
					.map(l -> map("location_ref", "location:" + l.id(), "name", l.str("name"), "kind", l.str("kind"),
							"parent_ref", l.isNull("parent_id") ? null : "location:" + l.lng("parent_id"),
							"description", bounded(l.str("description"), 1200), "source", "CANONICAL_LOCATION"))
					.toList());
			proposal.put("narrative_evidence", tx.query(
					"SELECT * FROM event WHERE campaign_id=? AND (location_id IS NOT NULL OR type='PARTY_MOVED') ORDER BY id DESC LIMIT 48",
					campaignId).stream()
					.map(e -> map("event_ref", "event:" + e.id(), "summary", bounded(e.str("summary"), 1200), "type",
							e.str("type"), "location_ref",
							e.isNull("location_id") ? null : "location:" + e.lng("location_id"), "fictional_seq",
							e.lng("fictional_seq"), "source", "JOURNAL_EVIDENCE"))
					.toList());
			proposal.put("map_fragments",
					tx.query("SELECT * FROM map_scene WHERE campaign_id=? ORDER BY id LIMIT 32", campaignId).stream()
							.map(s -> map("map_ref", "map:" + s.id(), "name", s.str("name"), "kind", s.str("kind"),
									"location_ref", s.isNull("location_id") ? null : "location:" + s.lng("location_id"),
									"bounds_ft", s.map("document_json").get("bounds_ft")))
							.toList());
			proposal.put("review_required", true);
			long id = tx.insert("map_preparation",
					map("campaign_id", campaignId, "kind", "REGION", "stage", "REGION", "location_id",
							proposal.get("location_ref") == null ? null
									: se.hirt.mcp.rpg.protocol.Ref.id(proposal.get("location_ref").toString(),
											"location"),
							"name", proposal.get("name"), "payload_json", Json.write(proposal), "source_revisions_json",
							Json.write(MapAuthoringService.sources(tx, campaignId, proposal))));
			preparation = tx.get("map_preparation", id);
			tx.touched("preparation:" + id, 0);
		}
		return map("status", "PREPARATION_REQUIRED", "preparation", "preparation:" + preparation.id(), "revision",
				preparation.lng("revision"), "next_operation", "get_map_context", "brief", brief(), "note",
				"Review existing distances, hierarchy and narrative evidence before publishing; no fictional time or discoveries were added.",
				"requirements", preparation.map("payload_json").getOrDefault("authoring_requirements", List.of()),
				"audience", "GM");
	}

	public static Map<String, Object> brief() {
		return map("purpose", "Author coherent geography that supports the campaign and several directions of play.",
				"guidance",
				List.of("Preserve existing names, identities, distances, current position and party knowledge.",
						"Shape related coastlines, watercourses, forests, farmland and relief; give settlements purposes.",
						"Save winding road/river paths, explicit crossings and plausible mountain passes.",
						"Normally let a main road leave the map; sea routes can serve islands.",
						"A coast is a soft preference. Latitude defaults near southern France, around 43.5 degrees north.",
						"A rumor can remain approximate; do not convert travel duration into invented exact distance.",
						"Use direct authored proposals or revise compatible helper output; validate before publication."));
	}

	private static Map<String, Object> defaultRegion(
		Tx tx, long campaignId, long openingId, Map<String, Object> adventure, boolean initialSetup) {
		Row opening = tx.get("location", openingId);
		Map<String, Object> region = object(adventure.get("region"));
		double width = region.get("width_miles") instanceof Number n ? n.doubleValue() * 5280 : 528000;
		double height = region.get("height_miles") instanceof Number n ? n.doubleValue() * 5280 : 528000;
		List<Row> locations = tx.query("SELECT * FROM location WHERE campaign_id=? ORDER BY id LIMIT 256", campaignId);
		Map<Long, Row> byId = new LinkedHashMap<>();
		locations.forEach(l -> byId.put(l.id(), l));
		List<Map<String, Object>> places = new ArrayList<>(), features = new ArrayList<>(),
				connections = new ArrayList<>();
		Map<Long, List<Double>> positions = new LinkedHashMap<>();
		// Draft layout hints are never asserted geographic facts. Children share their parent's anchor.
		positions.put(openingId, List.of(width / 2, height / 2));
		List<Row> edges = tx.query("SELECT * FROM location_connection WHERE campaign_id=? ORDER BY id LIMIT 512",
				campaignId);
		boolean advanced = true;
		int direction = 0;
		while (advanced) {
			advanced = false;
			for (Row edge : edges) {
				long a = edge.lng("location_a_id"), b = edge.lng("location_b_id");
				if (!byId.containsKey(a) || !byId.containsKey(b))
					continue;
				if (positions.containsKey(a) == positions.containsKey(b))
					continue;
				long known = positions.containsKey(a) ? a : b, missing = known == a ? b : a;
				Map<String, Object> distance = edge.map("distance_json");
				double miles = distance.get("distance_miles") instanceof Number n ? n.doubleValue()
						: distance.get("travel_minutes") instanceof Number n ? n.doubleValue() / 20 : 3;
				double angle = Math.toRadians((direction++ % 6) * 60), length = Math.max(30, miles * 5280);
				List<Double> origin = positions.get(known);
				positions.put(missing,
						List.of(origin.get(0) + Math.cos(angle) * length, origin.get(1) + Math.sin(angle) * length));
				advanced = true;
			}
		}
		for (Row location : locations) {
			if ("REGION".equals(location.str("kind")))
				continue;
			Long parent = location.lng("parent_id");
			if (parent != null && positions.containsKey(parent))
				positions.put(location.id(), positions.get(parent));
			positions.putIfAbsent(location.id(), List.of(width / 2, height / 2));
			String key = "existing-" + location.id();
			List<Double> position = positions.get(location.id());
			places.add(map("key", key, "name", location.str("name"), "location_ref", "location:" + location.id(),
					"point_ft", position, "coordinate_precision", "DRAFT_APPROXIMATION", "parent_ref",
					parent == null ? null : "location:" + parent));
			if (parent == null || !byId.containsKey(parent) || "REGION".equals(byId.get(parent).str("kind")))
				features.add(map("key", key, "kind", location.str("kind"), "name", location.str("name"), "point_ft",
						position, "graph_ref", key, "approximate", true, "coordinate_precision", "DRAFT_APPROXIMATION",
						"visibility", "GM_ONLY"));
		}
		for (Row edge : edges) {
			long a = edge.lng("location_a_id"), b = edge.lng("location_b_id");
			if (!byId.containsKey(a) || !byId.containsKey(b) || !positions.containsKey(a) || !positions.containsKey(b)
					|| "REGION".equals(byId.get(a).str("kind")) || "REGION".equals(byId.get(b).str("kind")))
				continue;
			String key = "existing-route-" + edge.id();
			Map<String, Object> distance = edge.map("distance_json");
			Map<String, Object> connection = map("key", key, "from", "existing-" + a, "to", "existing-" + b,
					"connection_ref", "connection:" + edge.id(), "kind", edge.str("kind"));
			connection.putAll(distance);
			connections.add(connection);
			features.add(map("key", key, "kind", "ROUTE", "graph_ref", key, "path_ft",
					List.of(positions.get(a), positions.get(b)), "approximate", true, "coordinate_precision",
					"DRAFT_APPROXIMATION", "distance_source",
					distance.containsKey("distance_miles") ? "CANONICAL_DISTANCE" : "TRAVEL_TIME_ASSUMPTION_3_MPH",
					"visibility", "GM_ONLY"));
		}
		double minX = positions.values().stream().mapToDouble(p -> p.get(0)).min().orElse(0),
				maxX = positions.values().stream().mapToDouble(p -> p.get(0)).max().orElse(width),
				minY = positions.values().stream().mapToDouble(p -> p.get(1)).min().orElse(0),
				maxY = positions.values().stream().mapToDouble(p -> p.get(1)).max().orElse(height);
		double shiftX = Math.max(0, 5280 - minX), shiftY = Math.max(0, 5280 - minY);
		if (shiftX > 0 || shiftY > 0) {
			for (var place : places) {
				List<Double> p = placePoint(places, place.get("key").toString());
				place.put("point_ft", List.of(p.get(0) + shiftX, p.get(1) + shiftY));
			}
			for (var feature : features) {
				if (feature.get("point_ft") instanceof List<?> p)
					feature.put("point_ft", List.of(((Number) p.get(0)).doubleValue() + shiftX,
							((Number) p.get(1)).doubleValue() + shiftY));
				if (feature.get("path_ft") instanceof List<?> path)
					feature.put("path_ft", path.stream().map(v -> {
						List<?> p = (List<?>) v;
						return List.of(((Number) p.get(0)).doubleValue() + shiftX,
								((Number) p.get(1)).doubleValue() + shiftY);
					}).toList());
			}
		}
		width = Math.max(width, maxX + shiftX + 5280);
		height = Math.max(height, maxY + shiftY + 5280);
		Map<String, Object> proposal = map("name", region.getOrDefault("name", opening.str("name") + " region draft"),
				"kind", "REGION", "stage", "REGION", "schema_version", 1, "rooms", places, "connections", connections,
				"features", features, "geometry", map("bounds_ft", List.of(0, 0, width, height)), "atlas",
				map("origin_latitude", startingLatitude(region), "origin_longitude",
						region.getOrDefault("longitude", 0)),
				"story_context", adventure.getOrDefault("premise", ""), "authorship", "SOURCED_GRAPH_DRAFT",
				"review_required", true, "authoring_brief", brief(), "authoring_requirements",
				List.of("Author campaign-specific settlements, terrain, water, relief and road continuations; no generic world facts have been invented.",
						"Review draft coordinates against parent containment and canonical travel distances. Travel-time-derived distance assumes 3 mph and is not established geography.",
						"Replace approximate layout hints with an authored region, preserve existing references, then set review_required:false and publish."));
		if (region.containsKey("climate"))
			proposal.put("climate", region.get("climate"));
		Row parent = opening;
		Set<Long> visited = new HashSet<>();
		while (visited.add(parent.id())) {
			if ("REGION".equals(parent.str("kind"))) {
				proposal.put("location_ref", "location:" + parent.id());
				break;
			}
			if (parent.isNull("parent_id"))
				break;
			parent = tx.get("location", parent.lng("parent_id"));
		}
		return proposal;
	}

	public static List<Map<String, Object>> coverage(Tx tx, long campaignId, long locationId) {
		List<Long> ancestors = se.hirt.mcp.rpg.world.WorldService.lineage(tx, locationId);
		var result = new ArrayList<Map<String, Object>>();
		for (Row scene : tx.query(
				"SELECT * FROM map_scene WHERE campaign_id=? AND kind IN ('DUNGEON','SCENE') ORDER BY id LIMIT 256",
				campaignId))
			if (!scene.isNull("location_id") && ancestors.contains(scene.lng("location_id")))
				result.add(map("map", "map:" + scene.id(), "name", scene.str("name"), "kind", scene.str("kind"),
						"ready_for_entry", true));
		return result;
	}

	public static boolean hasPlayableCoverage(Tx tx, long campaignId, long locationId) {
		return !coverage(tx, campaignId, locationId).isEmpty();
	}

	public static Map<String, Object> pendingEntry(Tx tx, long campaignId, long locationId) {
		List<Long> ancestors = se.hirt.mcp.rpg.world.WorldService.lineage(tx, locationId);
		for (Row prep : tx.query(
				"SELECT * FROM map_preparation WHERE campaign_id=? AND status='ACCEPTED' AND published_map_id IS NULL ORDER BY id",
				campaignId))
			if ((!prep.isNull("location_id") && ancestors.contains(prep.lng("location_id")))
					|| prep.map("reference_map_json").values().contains("location:" + locationId))
				return map("kind", "MAP_PREPARATION_REQUIRED", "preparation", "preparation:" + prep.id(), "revision",
						prep.lng("revision"), "name", prep.str("name"), "ready_for_entry", false, "next_operation",
						"revise_map_preparation", "authoring_choices",
						List.of("Supply authored geometry preserving the accepted commitments.",
								"Optionally request generate_layout for basic fitting assistance."));
		return Map.of();
	}

	/**
	 * Compact cold-resume orientation; actor positions and journey progress outrank a graph
	 * departure node.
	 */
	public static Map<String, Object> currentContext(Tx tx, long campaignId) {
		Row campaign = tx.get("campaign", campaignId);
		var result = new LinkedHashMap<String, Object>();
		Map<String, Object> travel = se.hirt.mcp.rpg.world.TravelJourneyService.stateInTx(tx, campaignId);
		result.put("journey", travel);
		var placements = new ArrayList<Map<String, Object>>();
		var maps = new LinkedHashMap<Long, Map<String, Object>>();
		for (Row actor : tx.query(
				"SELECT a.*,c.name AS character_name FROM map_actor a JOIN character c ON c.id=a.character_id WHERE a.campaign_id=? AND (EXISTS(SELECT 1 FROM player_control_assignment p WHERE p.character_id=a.character_id AND p.active=1) OR EXISTS(SELECT 1 FROM party_membership p WHERE p.character_id=a.character_id AND p.state IN ('ACTIVE','SEPARATED','GUEST'))) ORDER BY a.character_id LIMIT 32",
				campaignId)) {
			Row scene = tx.get("map_scene", actor.lng("scene_id"));
			maps.putIfAbsent(scene.id(), map("map", "map:" + scene.id(), "name", scene.str("name"), "kind",
					scene.str("kind"), "next_operation", "get_map_context"));
			placements.add(map("character", "character:" + actor.lng("character_id"), "name",
					actor.str("character_name"), "map", "map:" + scene.id(), "level", actor.intOr("level", 0), "x_ft",
					actor.get("x_ft"), "y_ft", actor.get("y_ft")));
		}
		if (maps.isEmpty() && !campaign.isNull("current_location_id"))
			for (var scene : coverage(tx, campaignId, campaign.lng("current_location_id")))
				maps.put(se.hirt.mcp.rpg.protocol.Ref.id(scene.get("map").toString(), "map"), scene);
		result.put("maps", new ArrayList<>(maps.values()));
		result.put("party_placements", placements);
		result.put("status", Boolean.TRUE.equals(travel.get("active")) ? "TRAVELING"
				: maps.isEmpty() ? "SEMANTIC_LOCATION" : "LOCAL_MAP_AVAILABLE");
		if (!campaign.isNull("current_location_id")) {
			result.put("location_ref", "location:" + campaign.lng("current_location_id"));
			Map<String, Object> pending = pendingEntry(tx, campaignId, campaign.lng("current_location_id"));
			if (!pending.isEmpty())
				result.put("preparation_required", pending);
		}
		return result;
	}

	private static List<Double> placePoint(List<Map<String, Object>> places, String key) {
		return ((List<?>) places.stream().filter(p -> key.equals(p.get("key"))).findFirst().orElseThrow()
				.get("point_ft")).stream().map(v -> ((Number) v).doubleValue()).toList();
	}

	private static String bounded(String text, int length) {
		return text == null ? null : text.length() > length ? text.substring(0, length) : text;
	}

	private static double startingLatitude(Map<String, Object> region) {
		if (region.get("latitude") instanceof Number n)
			return n.doubleValue();
		Object climate = region.get("climate");
		String baseline = (climate instanceof Map<?, ?> m ? String.valueOf(m.getOrDefault("baseline", null))
				: String.valueOf(climate)).toUpperCase(Locale.ROOT);
		double latitude = baseline.contains("POLAR") || baseline.contains("PERMANENT_ICE") ? 82
				: baseline.contains("TUNDRA") ? 72 : baseline.contains("ARCTIC") ? 65
						: baseline.contains("SUBTROPICAL") ? 30 : baseline.contains("TROPICAL") ? 15 : 43.5;
		return "SOUTH".equalsIgnoreCase(String.valueOf(region.get("hemisphere"))) ? -latitude : latitude;
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> object(Object v) {
		return v instanceof Map<?, ?> ? (Map<String, Object>) v : new LinkedHashMap<>();
	}

	private static List<Map<String, Object>> objects(Object v) {
		List<Map<String, Object>> out = new ArrayList<>();
		if (v instanceof List<?> l)
			for (Object item : l)
				out.add(object(item));
		return out;
	}

	private static Map<String, Object> map(Object ... pairs) {
		Map<String, Object> out = new LinkedHashMap<>();
		for (int i = 0; i < pairs.length; i += 2)
			out.put(pairs[i].toString(), pairs[i + 1]);
		return out;
	}
}
