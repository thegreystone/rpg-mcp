package se.hirt.mcp.rpg.maps;

import se.hirt.mcp.rpg.character.RuntimeService;
import se.hirt.mcp.rpg.content.RulesData;
import se.hirt.mcp.rpg.magic.Metamagic;
import se.hirt.mcp.rpg.persistence.Row;
import se.hirt.mcp.rpg.persistence.Tx;
import se.hirt.mcp.rpg.protocol.Json;
import se.hirt.mcp.rpg.protocol.Ref;
import se.hirt.mcp.rpg.protocol.RpgException;
import se.hirt.mcp.rpg.rules.Combat;
import se.hirt.mcp.rpg.rules.Exhaustion;
import se.hirt.mcp.rpg.rules.TurnEconomy;
import se.hirt.mcp.rpg.session.GameTime;
import se.hirt.mcp.rpg.spatial.Geometry.Bounds;
import se.hirt.mcp.rpg.spatial.Geometry.Footprint;
import se.hirt.mcp.rpg.spatial.Geometry.Point;
import se.hirt.mcp.rpg.spatial.Geometry.Polygon;
import se.hirt.mcp.rpg.spatial.Geometry.Scene;
import se.hirt.mcp.rpg.spatial.SpatialRules;
import se.hirt.mcp.rpg.world.TravelJourneyService;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.Matcher;

/** Integrates saved physical scenes with encounters; map_actor is the sole position authority. */
public final class GridCombat {

	private static final Pattern FEET = Pattern.compile("(\\d+)\\s*(?:feet|foot|ft)", Pattern.CASE_INSENSITIVE);

	private GridCombat() {
	}

	/**
	 * Reuse prepared coverage or lay out an incidental board before initiative. Existing placements
	 * on the selected map are preserved; explicit placement requests cannot teleport those actors.
	 */
	public static Map<String, Object> ensureEncounterScene(
		Tx tx, RulesData rules, long campaignId, Long locationId, List<Row> characters, Map<String, Object> zones,
		Map<String, Object> options) {
		Map<String, Object> opts = options == null ? Map.of() : options;
		Map<String, Object> authored = SceneService.map(opts.get("scene"));
		String explicit = SceneService.string(opts, "map_ref", SceneService.string(opts, "map", null));
		List<Long> ancestry = ancestry(tx, campaignId, locationId);
		Map<String, Object> journeyContext = encounterJourney(tx, campaignId, characters,
				opts.get("deployment_party_refs") instanceof Collection<?> party ? party : List.of())
						? TravelJourneyService.encounterSceneDocument(tx, campaignId, locationId) : Map.of();
		if (!journeyContext.isEmpty()) {
			locationId = TravelJourneyService.effectiveLocation(tx, campaignId, null);
			ancestry = ancestry(tx, campaignId, locationId);
		}
		Collection<?> journeyMovers = !journeyContext.isEmpty()
				&& TravelJourneyService.stateInTx(tx, campaignId).get("movers") instanceof Collection<?> movers ? movers
						: List.of();
		Row scene = explicit == null ? null : SceneService.scene(tx, campaignId, explicit);
		List<Row> coverage = tx.query(
				"SELECT * FROM map_scene WHERE campaign_id=? "
						+ "AND kind IN ('DUNGEON','SCENE') ORDER BY (preparation_id IS NOT NULL) DESC, id DESC",
				campaignId);
		Set<String> superseded = new HashSet<>();
		for (Row candidate : coverage) {
			Object previous = candidate.map("document_json").get("supersedes_map_ref");
			if (previous != null)
				for (Row original : coverage)
					if (Ref.of(Ref.MAP, original.id()).equals(previous.toString()) && incidental(candidate)
							&& incidental(original) && sameIncidentalScope(candidate, original))
						superseded.add(previous.toString());
		}
		if (scene != null && Set.of("REGION", "SETTLEMENT").contains(scene.str("kind"))) {
			throw RpgException.invalidArgument("Combat needs a local tactical map, rather than an overview.");
		}
		if (scene != null && !journeyContext.isEmpty() && !coversJourney(scene.map("document_json"), journeyContext))
			throw RpgException.notAllowed(
					"The requested map does not cover the current journey contact. Prepare local coverage at the saved anchor.");
		if (scene != null && journeyContext.isEmpty() && !coversLocation(scene, ancestry))
			throw RpgException.notAllowed(mapLabel(scene) + " does not cover encounter "
					+ Ref.ofNullable(Ref.LOCATION, locationId) + ". Travel to that location before selecting its map.");
		if (scene == null && !journeyContext.isEmpty()) {
			for (Row candidate : coverage) {
				if (!superseded.contains(Ref.of(Ref.MAP, candidate.id()))
						&& coversJourney(candidate.map("document_json"), journeyContext)) {
					scene = candidate;
					break;
				}
			}
		}
		// Exploration's controlled figurine remains the current board when combat begins.
		if (scene == null && journeyContext.isEmpty() && authored.isEmpty()) {
			Optional<Row> controlled = tx.queryOne(
					"SELECT s.*, a.character_id AS controlled_character_id FROM map_scene s "
							+ "JOIN map_actor a ON a.scene_id=s.id "
							+ "JOIN player_control_assignment p ON p.character_id=a.character_id AND p.active=1 "
							+ "WHERE p.campaign_id=? AND s.kind IN ('SCENE','DUNGEON') ORDER BY p.id LIMIT 1",
					campaignId);
			if (controlled.isPresent()) {
				Row current = controlled.get();
				if (characters.stream().anyMatch(character -> character.id() == current.lng("controlled_character_id"))
						&& !superseded.contains(Ref.of(Ref.MAP, current.id())) && coversLocation(current, ancestry)) {
					scene = current;
					if (incidental(current))
						for (Row candidate : coverage)
							if (publishedUpgrade(current, candidate)) {
								scene = candidate;
								break;
							}
				}
			}
		}
		// A published room supersedes an older provisional grid when no current party board was retained.
		if (scene == null && journeyContext.isEmpty()) {
			for (long ancestor : ancestry) {
				for (Row candidate : coverage) {
					if (!candidate.isNull("preparation_id") && !superseded.contains(Ref.of(Ref.MAP, candidate.id()))
							&& coversLocation(candidate, List.of(ancestor))) {
						scene = candidate;
						break;
					}
				}
				if (scene != null)
					break;
			}
		}
		if (scene == null && journeyContext.isEmpty()) {
			for (Row character : characters) {
				Optional<Row> placed = tx.queryOne("SELECT * FROM map_actor WHERE campaign_id=? AND character_id=?",
						campaignId, character.id());
				if (placed.isPresent()) {
					Row candidate = tx.get("map_scene", placed.get().lng("scene_id"));
					if (!superseded.contains(Ref.of(Ref.MAP, candidate.id())) && coversLocation(candidate, ancestry)) {
						scene = candidate;
						break;
					}
				}
			}
		}
		if (scene == null && journeyContext.isEmpty()) {
			for (long ancestor : ancestry) {
				for (Row candidate : coverage)
					if (!superseded.contains(Ref.of(Ref.MAP, candidate.id()))
							&& coversLocation(candidate, List.of(ancestor))) {
						scene = candidate;
						break;
					}
				if (scene != null) {
					break;
				}
			}
		}
		if (explicit == null && !authored.isEmpty())
			for (Row candidate : coverage)
				if (incidental(candidate) && Objects.equals(candidate.lng("location_id"), locationId)
						&& scope(candidate).equals(scope(authored, locationId))
						&& !superseded.contains(Ref.of(Ref.MAP, candidate.id())) && (journeyContext.isEmpty()
								|| coversJourney(candidate.map("document_json"), journeyContext))) {
					scene = candidate;
					break;
				}
		Row replaced = null;
		if (scene != null && !authored.isEmpty()
				&& !Json.readMap(Json.write(authored)).equals(scene.map("document_json").get("authoring_input"))) {
			if (explicit != null && !scene.isNull("preparation_id"))
				throw RpgException.invalidArgument("options.scene cannot edit published " + mapLabel(scene)
						+ "; publish a revised preparation or omit map_ref to author a separate scene.");
			if (incidental(scene) && Objects.equals(scene.lng("location_id"), locationId)
					&& scope(scene).equals(scope(authored, locationId)))
				replaced = scene;
			scene = null;
		}
		if (scene == null) {
			for (long ancestor : ancestry) {
				if (tx.count("SELECT COUNT(*) FROM map_preparation WHERE campaign_id=? AND location_id=? "
						+ "AND kind='DUNGEON' AND status='ACCEPTED'", campaignId, ancestor) > 0) {
					throw RpgException
							.notAllowed("Materialize and publish the accepted dungeon before entering combat.");
				}
			}
			var document = new LinkedHashMap<String, Object>();
			document.put("bounds_ft", List.of(0, 0, 100, 100));
			document.put("enclose_rooms", false);
			document.put("illumination", "BRIGHT");
			document.put("rooms", List.of(Map.of("key", "encounter-ground", "rect_ft", List.of(0, 0, 100, 100),
					"enclosed", false, "material", "EARTH")));
			document.put("source", "INCIDENTAL_ENCOUNTER");
			document.put("scene_scope_key", scope(authored, locationId));
			document.putAll(journeyContext);
			if (!SceneService.maps(authored.get("rooms")).isEmpty())
				document.put("enclose_rooms", authored.getOrDefault("enclose_rooms", true));
			for (String key : List.of("bounds_ft", "rooms", "walls", "doors", "features", "roads", "rivers", "objects",
					"illumination", "enclose_rooms"))
				if (authored.containsKey(key))
					document.put(key, authored.get(key));
			if (authored.containsKey("bounds_ft") && !authored.containsKey("rooms"))
				document.put("rooms",
						List.of(Map.of("rect_ft", authored.get("bounds_ft"), "enclosed", false, "material", "EARTH")));
			if (!authored.isEmpty())
				document.put("authoring_input", authored);
			if (replaced != null) {
				document.put("supersedes_map_ref", Ref.of(Ref.MAP, replaced.id()));
				if (replaced.isNull("preparation_id"))
					carryInlineState(replaced.map("document_json"), document);
			}
			String environment = SceneService.string(opts, "environment_description", null);
			if (environment != null && !environment.isBlank())
				document.put("environment_description", environment);
			document.put("detail_status", authored.isEmpty()
					? (journeyContext.isEmpty() ? "AUTHORING_REQUIRED" : "REGIONAL_CONTEXT_REFINED") : "AUTHORED");
			if (authored.isEmpty() && journeyContext.isEmpty())
				document.put("authoring_requirement",
						"Supply a prepared map or options.scene for the room's walls, entrances, furnishings and cover. Environment prose is retained as the brief; the provisional grid does not assert those missing details.");
			Scene physical = SceneService.geometry(document, SceneService.number(opts, "level", 0).intValue());
			if (physical.floors().stream().anyMatch(f -> !physical.bounds().contains(f.bounds())))
				throw RpgException.invalidArgument("Authored scene floors must fit their declared bounds.");
			for (var room : SceneService.maps(document.get("rooms")))
				if (room.get("location_ref") != null
						&& tx.get("location", Ref.id(room.get("location_ref").toString(), Ref.LOCATION))
								.lng("campaign_id") != campaignId)
					throw RpgException.invalidArgument("Scene room belongs to another campaign.");
			for (var door : SceneService.maps(document.get("doors")))
				if (door.get("connection_ref") != null
						&& tx.get("location_connection", Ref.id(door.get("connection_ref").toString(), Ref.CONNECTION))
								.lng("campaign_id") != campaignId)
					throw RpgException.invalidArgument("Scene passage belongs to another campaign.");
			for (var object : SceneService.maps(document.get("objects")))
				if (object.containsKey("entity_ref"))
					throw RpgException.invalidArgument(
							"Bind canonical inventory through a map preparation; incidental scene objects describe physical furnishings only.");
			var columns = new LinkedHashMap<String, Object>();
			columns.put("campaign_id", campaignId);
			columns.put("location_id", locationId);
			columns.put("name",
					authored.getOrDefault("name",
							locationId == null ? "Provisional encounter board"
									: tx.get("location", locationId).str("name")
											+ (journeyContext.isEmpty() ? " encounter board" : "")));
			columns.put("kind", "SCENE");
			columns.put("document_json", Json.write(document));
			scene = tx.get("map_scene", tx.insert("map_scene", columns));
		}
		Map<String, Object> document = SceneService.document(tx, scene);
		if (explicit == null && authored.isEmpty() && journeyContext.isEmpty()) {
			var standingBoards = new LinkedHashMap<Long, List<String>>();
			for (Row character : characters)
				tx.queryOne("SELECT * FROM map_actor WHERE campaign_id=? AND character_id=?", campaignId,
						character.id())
						.ifPresent(actor -> standingBoards
								.computeIfAbsent(actor.lng("scene_id"), ignored -> new ArrayList<>())
								.add(actorLabel(character)));
			Row selected = scene;
			boolean realizingProvisional = standingBoards.keySet().stream()
					.allMatch(id -> id == selected.id() || publishedUpgrade(tx.get("map_scene", id), selected));
			if (standingBoards.size() > 1 && !realizingProvisional)
				throw RpgException
						.notAllowed(
								"Encounter participants stand on different boards: "
										+ String.join("; ",
												standingBoards.entrySet().stream().map(entry -> mapLabel(
														tx.get("map_scene", entry.getKey())) + ": "
														+ String.join(", ", entry.getValue())).toList())
										+ ". Use place_map_actors to gather them in the same canonical room, or explicitly select options.map_ref and placements for the resolved scene transition.");
		}
		Map<String, Map<String, Object>> requested = new LinkedHashMap<>();
		for (Map<String, Object> placement : SceneService.maps(opts.get("placements"))) {
			String ref = SceneService.string(placement, "character", null);
			if (ref == null || requested.put(ref, placement) != null) {
				throw RpgException.invalidArgument("Each explicit deployment needs one distinct character reference.");
			}
		}
		for (String ref : requested.keySet()) {
			if (characters.stream().noneMatch(c -> Ref.of(Ref.CHARACTER, c.id()).equals(ref))) {
				throw RpgException.invalidArgument("Deployment references a character outside the encounter.");
			}
		}
		Collection<?> partyRefs = opts.get("deployment_party_refs") instanceof Collection<?> refs ? refs : List.of();
		var deploymentOrder = new ArrayList<>(characters);
		deploymentOrder.sort(Comparator
				.comparingInt(character -> partyRefs.contains(Ref.of(Ref.CHARACTER, character.id())) ? 0 : 1));
		for (Row character : deploymentOrder) {
			String ref = Ref.of(Ref.CHARACTER, character.id());
			Map<String, Object> requestedPlacement = requested.get(ref);
			Optional<Row> previous = tx.queryOne("SELECT * FROM map_actor WHERE campaign_id=? AND character_id=?",
					campaignId, character.id());
			if (previous.isPresent() && previous.get().lng("scene_id") == scene.id()) {
				if (requestedPlacement != null && (!SceneService
						.point(SceneService.number(requestedPlacement, "x_ft", 0).doubleValue(),
								SceneService.number(requestedPlacement, "y_ft", 0).doubleValue())
						.equals(SceneService.footprint(previous.get()).origin())
						|| SceneService.number(requestedPlacement, "level", previous.get().intOr("level", 0))
								.intValue() != previous.get().intOr("level", 0))) {
					throw RpgException.notAllowed(actorLabel(character) + " already stands at ("
							+ previous.get().get("x_ft") + ", " + previous.get().get("y_ft") + ") ft on "
							+ mapLabel(scene)
							+ "; existing exploration positions carry into combat. Use movement or a scene transition.");
				}
				continue;
			}
			if (previous.isPresent()) {
				Row previousMap = tx.get("map_scene", previous.get().lng("scene_id"));
				if (!coversLocation(previousMap, ancestry)
						&& !samePlace(tx, campaignId, previousMap.lng("location_id"), locationId)
						&& (journeyContext.isEmpty()
								|| !coversJourney(previousMap.map("document_json"), journeyContext)))
					throw RpgException.notAllowed(actorLabel(character) + " has an established position on "
							+ mapLabel(previousMap) + ", outside " + mapLabel(scene)
							+ " coverage. Resolve travel or a scene transition before deployment.");
			}
			if (!character.isNull("location_id") && !ancestry.contains(character.lng("location_id"))
					&& !coversLocation(scene, List.of(character.lng("location_id"))) && journeyContext.isEmpty())
				throw RpgException.notAllowed(
						actorLabel(character) + " is at " + Ref.of(Ref.LOCATION, character.lng("location_id"))
								+ ", outside " + mapLabel(scene) + ". Resolve travel before deployment.");
			int level = SceneService.number(opts, "level", scene.intOr("level", 0)).intValue();
			Long deploymentPlace = journeyMovers.contains(ref) ? locationId : character.lng("location_id");
			Point preferred = legacyAnchor(zones == null ? null : zones.get(ref));
			if ((zones == null || zones.get(ref) == null) && document.containsKey("journey_entry_origin_ft"))
				preferred = coordinatePair(document.get("journey_entry_origin_ft"));
			if ((zones == null || zones.get(ref) == null)
					&& Boolean.TRUE.equals(journeyContext.get("route_geometry_known"))
					&& document.containsKey("world_origin_ft")) {
				Point world = coordinatePair(journeyContext.get("journey_world_anchor_ft"));
				Point origin = coordinatePair(document.get("world_origin_ft"));
				preferred = snapped(world.x() - origin.x(), world.y() - origin.y());
			}
			for (Map<String, Object> room : SceneService.maps(document.get("rooms"))) {
				if (deploymentPlace != null
						&& Objects.equals(room.get("location_ref"), Ref.ofNullable(Ref.LOCATION, deploymentPlace))) {
					Polygon shape = SceneService.polygon(room);
					if (shape != null) {
						Bounds b = shape.bounds();
						preferred = snapped((b.minX() + b.maxX()) / 2, (b.minY() + b.maxY()) / 2);
						level = SceneService.number(room, "level", level).intValue();
						break;
					}
				}
			}
			int cells = footprintCells(rules, character);
			if (previous.isPresent() && requestedPlacement == null) {
				preferred = SceneService.footprint(previous.get()).origin();
				level = previous.get().intOr("level", level);
			}
			Scene geometry = SceneService.geometry(document, level);
			List<Footprint> occupied = new ArrayList<>(occupied(tx, scene.id(), level, character.id()));
			for (Row reserved : characters) {
				Map<String, Object> request = requested.get(Ref.of(Ref.CHARACTER, reserved.id()));
				if (reserved.id() != character.id() && request != null
						&& SceneService.number(request, "level", level).intValue() == level)
					occupied.add(new Footprint(
							SceneService.point(SceneService.number(request, "x_ft", 0).doubleValue(),
									SceneService.number(request, "y_ft", 0).doubleValue()),
							footprintCells(rules, reserved), footprintCells(rules, reserved)));
			}
			Footprint placement;
			if (requestedPlacement != null) {
				level = SceneService.number(requestedPlacement, "level", level).intValue();
				geometry = SceneService.geometry(document, level);
				occupied = occupied(tx, scene.id(), level, character.id());
				placement = new Footprint(
						SceneService.point(SceneService.number(requestedPlacement, "x_ft", 0).doubleValue(),
								SceneService.number(requestedPlacement, "y_ft", 0).doubleValue()),
						cells, cells);
				if (!SpatialRules.canPlace(geometry, placement, occupied)) {
					throw RpgException.invalidArgument(
							deploymentError(tx, scene, character, document, geometry, placement, occupied, level));
				}
			} else {
				try {
					List<Footprint> oppositePositions = new ArrayList<>(SceneService
							.blockingActors(tx, scene.id(), level).stream()
							.filter(actor -> characters.stream()
									.anyMatch(other -> other.id() == actor.lng("character_id")))
							.filter(actor -> partyRefs.contains(
									Ref.of(Ref.CHARACTER, actor.lng("character_id"))) != partyRefs.contains(ref))
							.map(SceneService::footprint).toList());
					// Explicit opponents can be deployed later in this loop; their reserved cells already
					// determine the actual starting separation for a fresh automatic placement.
					for (Row opponent : characters) {
						String opponentRef = Ref.of(Ref.CHARACTER, opponent.id());
						Map<String, Object> request = requested.get(opponentRef);
						if (partyRefs.contains(opponentRef) != partyRefs.contains(ref) && request != null
								&& SceneService.number(request, "level", level).intValue() == level)
							oppositePositions.add(new Footprint(
									SceneService.point(SceneService.number(request, "x_ft", 0).doubleValue(),
											SceneService.number(request, "y_ft", 0).doubleValue()),
									footprintCells(rules, opponent), footprintCells(rules, opponent)));
					}
					boolean freshDefault = previous.isEmpty() && (zones == null || zones.get(ref) == null)
							&& !partyRefs.isEmpty();
					Scene deploymentGeometry = automaticDeploymentGeometry(tx, campaignId, deploymentPlace, scene,
							document, geometry, level);
					placement = nearestPlacement(deploymentGeometry, preferred, cells, occupied,
							freshDefault ? oppositePositions : List.of());
				} catch (RpgException noSpace) {
					throw RpgException.notAllowed("Cannot deploy " + actorLabel(character) + " on " + mapLabel(scene)
							+ " at level " + level + ": " + noSpace.getMessage()
							+ (journeyMovers.contains(ref)
									? " This is the active journey contact, not the departure room." : "")
							+ " Read GM map context and resolve entry or supply explicit options.placements; automatic spacing never changes place or crosses a closed passage.");
				}
			}
			if (previous.isPresent() && previous.get().lng("scene_id") != scene.id() && journeyContext.isEmpty())
				requireSameLocationTransition(tx, campaignId, character,
						tx.get("map_scene", previous.get().lng("scene_id")), scene, placement, level);
			var columns = new LinkedHashMap<String, Object>();
			columns.put("scene_id", scene.id());
			columns.put("x_ft", placement.origin().x() / 12.0);
			columns.put("y_ft", placement.origin().y() / 12.0);
			columns.put("level", level);
			columns.put("footprint_ft", cells * 5);
			columns.put("revision", previous.map(a -> a.lng("revision") + 1).orElse(0L));
			if (previous.isPresent()) {
				tx.update("map_actor", previous.get().id(), columns);
			} else {
				columns.put("campaign_id", campaignId);
				columns.put("character_id", character.id());
				tx.insert("map_actor", columns);
			}
		}
		if (!journeyContext.isEmpty() && document.containsKey("journey_ref")) {
			var saved = new LinkedHashMap<>(scene.map("document_json"));
			for (String key : List.of("journey_ref", "route_anchor", "journey_world_anchor_ft", "route_reverse"))
				if (journeyContext.containsKey(key))
					saved.put(key, journeyContext.get(key));
			Optional<Row> pc = tx.queryOne(
					"SELECT a.* FROM map_actor a JOIN player_control_assignment p "
							+ "ON p.character_id=a.character_id AND p.active=1 WHERE a.scene_id=? AND p.campaign_id=?",
					scene.id(), campaignId);
			if (pc.isPresent()) {
				saved.put("journey_entry_origin_ft", List.of(pc.get().get("x_ft"), pc.get().get("y_ft")));
				saved.put("journey_entry_recorded", true);
			}
			tx.update("map_scene", scene.id(),
					Map.of("document_json", Json.write(saved), "revision", scene.lng("revision") + 1));
			scene = tx.get("map_scene", scene.id());
		}
		for (Row character : characters)
			SceneService.syncLocation(tx, scene,
					SceneService.actor(tx, campaignId, Ref.of(Ref.CHARACTER, character.id())));
		SceneService.remember(tx, scene);
		var binding = new LinkedHashMap<String, Object>();
		binding.putAll(Map.of("map_ref", Ref.of(Ref.MAP, scene.id()), "profile", SpatialRules.PROFILE, "level",
				SceneService.number(opts, "level", scene.intOr("level", 0)), "started_seconds",
				currentSeconds(tx, campaignId)));
		for (String key : List.of("detail_status", "authoring_requirement", "environment_description"))
			if (document.containsKey(key))
				binding.put(key, document.get(key));
		var deployedParty = new ArrayList<Map<String, Object>>();
		for (Row character : characters)
			if (partyRefs.contains(Ref.of(Ref.CHARACTER, character.id()))) {
				Row actor = SceneService.actor(tx, campaignId, Ref.of(Ref.CHARACTER, character.id()));
				var rooms = new ArrayList<Map<String, Object>>();
				for (var room : SceneService.maps(document.get("rooms"))) {
					Polygon shape = SceneService.polygon(room);
					if (shape != null && SceneService.number(room, "level", 0).intValue() == actor.intOr("level", 0)
							&& shape.contains(SceneService.footprint(actor).center())) {
						var chosen = new LinkedHashMap<String, Object>();
						chosen.put("name",
								SceneService.string(room, "name", SceneService.string(room, "key", scene.str("name"))));
						for (String field : List.of("key", "location_ref"))
							if (room.get(field) != null)
								chosen.put(field, room.get(field));
						rooms.add(chosen);
					}
				}
				deployedParty.add(Map.of("character", Ref.of(Ref.CHARACTER, character.id()), "name",
						character.str("name"), "rooms", rooms, "level", actor.intOr("level", 0), "move_origin_ft",
						List.of(actor.get("x_ft"), actor.get("y_ft"))));
			}
		binding.put("party_deployment", deployedParty);
		return binding;
	}

	public static boolean isGrid(Row encounter) {
		return "GRID".equals(encounter.str("spatial_model"));
	}

	public static Map<String, Object> binding(Row encounter) {
		return SceneService.map(encounter.map("environment_json").get("grid"));
	}

	public static Row scene(Tx tx, Row encounter) {
		Object ref = binding(encounter).get("map_ref");
		if (!isGrid(encounter) || ref == null) {
			throw RpgException
					.notAllowed("Convert the legacy encounter to a saved tactical grid before further actions.");
		}
		return SceneService.scene(tx, encounter.lng("campaign_id"), ref.toString());
	}

	public static Row actor(Tx tx, Row encounter, long characterId) {
		Row map = scene(tx, encounter);
		Row placement = SceneService.actor(tx, encounter.lng("campaign_id"), Ref.of(Ref.CHARACTER, characterId));
		if (placement.lng("scene_id") != map.id()) {
			throw RpgException.notAllowed(actorLabel(tx.get("character", characterId)) + " is placed on "
					+ mapLabel(tx.get("map_scene", placement.lng("scene_id"))) + ", outside encounter " + mapLabel(map)
					+ ". Resolve its scene transition before acting.");
		}
		return placement;
	}

	public static long distance(Tx tx, Row encounter, long a, long b) {
		Row first = actor(tx, encounter, a);
		Row second = actor(tx, encounter, b);
		return first.intOr("level", 0) == second.intOr("level", 0)
				? SpatialRules.distance(SceneService.footprint(first), SceneService.footprint(second)) : Long.MAX_VALUE;
	}

	public static List<Footprint> occupied(Tx tx, long sceneId, int level, long exceptCharacter) {
		return SceneService.blockingActors(tx, sceneId, level).stream()
				.filter(a -> a.lng("character_id") != exceptCharacter).map(SceneService::footprint).toList();
	}

	public static SpatialRules.CoverResult cover(Tx tx, Row encounter, long observerId, long targetId) {
		Row map = scene(tx, encounter);
		Row observer = actor(tx, encounter, observerId);
		Row target = actor(tx, encounter, targetId);
		if (observer.intOr("level", 0) != target.intOr("level", 0)) {
			return new SpatialRules.CoverResult(SpatialRules.Cover.TOTAL, 5, 5);
		}
		return SceneService.cover(tx, map, observer, target);
	}

	public static boolean canSee(Tx tx, Row encounter, long observerId, long targetId) {
		Row observer = actor(tx, encounter, observerId);
		Row target = actor(tx, encounter, targetId);
		return SceneService.canSee(tx, scene(tx, encounter), observer, target);
	}

	public static Set<Long> knownToParty(Tx tx, Row encounter) {
		List<Row> participants = tx.query("SELECT * FROM encounter_participant WHERE encounter_id=?", encounter.id());
		String partySide = String.valueOf(encounter.map("sides_json").get("party_side"));
		List<Long> party = participants.stream().filter(p -> partySide.equals(p.str("side")))
				.map(p -> p.lng("character_id")).toList();
		var known = new HashSet<Long>(party);
		if (!isGrid(encounter)) {
			participants.forEach(p -> known.add(p.lng("character_id")));
			return known;
		}
		for (Row participant : participants) {
			long id = participant.lng("character_id");
			for (long observer : party) {
				Row c = tx.get("character", observer);
				if ("ALIVE".equals(c.str("life_state")) && canSee(tx, encounter, observer, id)) {
					known.add(id);
					break;
				}
			}
		}
		return known;
	}

	/**
	 * Previously observed identities may remain on an initiative roster without exposing live
	 * positions.
	 */
	public static boolean nameKnownToParty(Tx tx, Row encounter, long characterId) {
		if (knownToParty(tx, encounter).contains(characterId))
			return true;
		if (!isGrid(encounter))
			return true;
		String partySide = String.valueOf(encounter.map("sides_json").get("party_side"));
		String target = Ref.of(Ref.CHARACTER, characterId);
		for (Row participant : tx.query("SELECT * FROM encounter_participant WHERE encounter_id=? AND side=?",
				encounter.id(), partySide)) {
			String observer = Ref.of(Ref.CHARACTER, participant.lng("character_id"));
			Optional<Row> memory = tx.queryOne("SELECT * FROM map_observation WHERE scene_id=? AND observer_key=?",
					scene(tx, encounter).id(), observer);
			if (memory.isPresent() && SceneService.maps(memory.get().map("knowledge_json").get("last_seen")).stream()
					.anyMatch(sighting -> target.equals(sighting.get("character"))))
				return true;
		}
		return false;
	}

	public record AttackGeometry(long distanceInches, int coverBonus, boolean longRange) {
	}

	/** Validate geometry before ammunition, rolls or other attack effects are committed. */
	public static AttackGeometry validateAttack(
		Tx tx, Row encounter, long attacker, long target, Combat.AttackProfile profile) {
		validateTargetKnowledge(tx, encounter, attacker, target);
		long distance = distance(tx, encounter, attacker, target);
		long normal = number(profile.range(), "normal", 0);
		long maximum = profile.ranged() ? number(profile.range(), "long", normal > 0 ? normal : 60)
				: number(profile.range(), "reach", 5);
		if (distance > maximum * 12) {
			throw RpgException.notAllowed("The target is beyond this attack's range or reach on the grid.");
		}
		SpatialRules.Cover covered = cover(tx, encounter, attacker, target).cover();
		if (covered == SpatialRules.Cover.TOTAL) {
			throw RpgException.notAllowed("The attack has no unobstructed path to the target.");
		}
		return new AttackGeometry(distance,
				covered == SpatialRules.Cover.HALF ? 2 : covered == SpatialRules.Cover.THREE_QUARTERS ? 5 : 0,
				profile.ranged() && normal > 0 && distance > normal * 12);
	}

	public static long remainingMovement(Tx tx, RulesData rules, Row encounter, Row participant, Row character) {
		Map<String, Object> movement = movement(tx, encounter, participant);
		return Math.max(0, speed(tx, rules, character) * 12L + number(movement, "dash_inches", 0)
				- number(movement, "spent_inches", 0));
	}

	/** Resolve mechanically supported spell targets from the same saved board as weapon actions. */
	public static List<Row> spellTargets(
		Tx tx, Row encounter, Row caster, Map<String, Object> payload, Map<String, Object> mechanics,
		List<Row> requested, Map<String, Object> options) {
		if (encounter == null || !isGrid(encounter))
			return requested;
		Row map = scene(tx, encounter);
		Row placement = actor(tx, encounter, caster.id());
		Map<String, Object> document = SceneService.document(tx, map);
		Scene geometry = SceneService.geometry(document, placement.intOr("level", 0));
		String range = SceneService.string(payload, "range", "");
		boolean self = range.toLowerCase().startsWith("self");
		boolean distant = Metamagic.requested(options, "distant");
		long maximum = "Touch".equalsIgnoreCase(range) ? distant ? 360 : 60 : feet(range, -1) * (distant ? 24 : 12);
		String description = SceneService.string(mechanics, "area", "").toLowerCase();
		if (!description.isBlank() && "SAVE".equals(mechanics.get("kind"))) {
			Point origin = optionalPoint(options, "center_ft", optionalPoint(options, "origin_ft", null));
			if (self || description.contains("emanation"))
				origin = SceneService.center(placement);
			if (origin == null && !requested.isEmpty())
				origin = SceneService.center(actor(tx, encounter, requested.getFirst().id()));
			if (origin == null)
				throw RpgException.invalidArgument("Supply the spell area's center_ft or direction_ft on the grid.");
			var areas = new ArrayList<SpatialRules.Area>();
			var origins = new ArrayList<Point>();
			long extent = firstNumber(description) * 12;
			boolean directional = description.contains("cone") || description.contains("line")
					|| self && description.contains("cube");
			Point aim = optionalPoint(options, "direction_ft",
					requested.isEmpty() ? null : SceneService.center(actor(tx, encounter, requested.getFirst().id())));
			if (description.startsWith("four ")) {
				if (!(options.get("centers_ft") instanceof List<?> centers) || centers.size() != 4)
					throw RpgException.invalidArgument("This spell needs four centers_ft positions.");
				for (Object center : centers) {
					Point point = coordinatePair(center);
					areas.add(new SpatialRules.Circle(point, extent));
					origins.add(point);
				}
			} else if (directional) {
				origin = SceneService.center(placement);
				if (aim == null || aim.equals(origin))
					throw RpgException
							.invalidArgument("A cone/line/cube needs a distinct direction_ft or aimed-at target.");
				double dx = aim.x() - origin.x(), dy = aim.y() - origin.y(), length = Math.hypot(dx, dy);
				double ux = dx / length, uy = dy / length;
				Point end = new Point(Math.round(origin.x() + ux * extent), Math.round(origin.y() + uy * extent));
				if (description.contains("line")) {
					Matcher width = Pattern.compile("(\\d+)\\s*feet wide").matcher(description);
					areas.add(new SpatialRules.Line(origin, end,
							(width.find() ? Long.parseLong(width.group(1)) : 5) * 12));
				} else if (description.contains("cone")) {
					// SRD cone width equals its axial length; this is a triangle, rather than a 90-degree sector.
					areas.add(new SpatialRules.PolygonArea(new Polygon(List.of(origin,
							new Point(Math.round(end.x() - uy * extent / 2), Math.round(end.y() + ux * extent / 2)),
							new Point(Math.round(end.x() + uy * extent / 2), Math.round(end.y() - ux * extent / 2))))));
				} else {
					areas.add(new SpatialRules.PolygonArea(new Polygon(List.of(
							new Point(Math.round(origin.x() - uy * extent / 2),
									Math.round(origin.y() + ux * extent / 2)),
							new Point(Math.round(origin.x() + uy * extent / 2),
									Math.round(origin.y() - ux * extent / 2)),
							new Point(Math.round(end.x() + uy * extent / 2), Math.round(end.y() - ux * extent / 2)),
							new Point(Math.round(end.x() - uy * extent / 2), Math.round(end.y() + ux * extent / 2))))));
				}
				origins.add(origin);
			} else {
				if (description.contains("cube") || description.contains("square")) {
					areas.add(new SpatialRules.Rectangle(new Bounds(origin.x() - extent / 2, origin.y() - extent / 2,
							origin.x() + extent / 2, origin.y() + extent / 2)));
				} else
					areas.add(new SpatialRules.Circle(origin, description.contains("diameter") ? extent / 2 : extent));
				origins.add(origin);
			}
			for (Point point : origins) {
				if (!self && maximum >= 0 && pointRange(SceneService.center(placement), point) > maximum)
					throw RpgException.notAllowed("The area's point of origin is outside the spell's range.");
				if (!SpatialRules.lineOfSight(geometry, SceneService.center(placement), point))
					throw RpgException.notAllowed("The spell has no clear path to its point of origin.");
			}
			Set<Long> wanted = new HashSet<>(requested.stream().map(Row::id).toList());
			boolean selected = description.contains("up to ");
			var result = new ArrayList<Row>();
			for (Row candidate : SceneService.actors(tx, map.id(), placement.intOr("level", 0))) {
				long id = candidate.lng("character_id");
				if ((directional || description.contains("emanation")) && id == caster.id()
						&& !Boolean.TRUE.equals(options.get("include_caster")))
					continue;
				boolean affected = false;
				for (int i = 0; i < areas.size() && !affected; i++)
					for (Point cell : SceneService.footprint(candidate).cellCenters()) {
						if (areas.get(i).contains(cell) && SpatialRules.lineOfSight(geometry, origins.get(i), cell)) {
							affected = true;
							break;
						}
					}
				if (affected && (!selected || wanted.contains(id)))
					result.add(tx.get("character", id));
			}
			if (selected && result.size() != wanted.size())
				throw RpgException.notAllowed("A selected target is outside the spell's area.");
			return result;
		}
		for (Row target : requested) {
			validateTargetKnowledge(tx, encounter, caster.id(), target.id());
			long distance = distance(tx, encounter, caster.id(), target.id());
			if (self && caster.id() != target.id())
				throw RpgException.notAllowed("A Self spell targets its caster.");
			if (maximum >= 0 && distance > maximum)
				throw RpgException.notAllowed("The target is outside this spell's grid range.");
			if (cover(tx, encounter, caster.id(), target.id()).cover() == SpatialRules.Cover.TOTAL)
				throw RpgException.notAllowed("The spell has no clear path to the target.");
			if (SceneService.string(payload, "text", "").toLowerCase().contains("you can see")
					&& !canSee(tx, encounter, caster.id(), target.id()))
				throw RpgException.notAllowed("This spell requires the caster's own sight of its target.");
		}
		return requested;
	}

	/** An enemy cannot lock onto the GM's exact current coordinates without its own evidence. */
	public static void validateTargetKnowledge(Tx tx, Row encounter, long observer, long target) {
		if (observer == target || canSee(tx, encounter, observer, target))
			return;
		String partySide = String.valueOf(encounter.map("sides_json").get("party_side"));
		if (tx.count("SELECT COUNT(*) FROM encounter_participant WHERE encounter_id=? AND character_id=? AND side=?",
				encounter.id(), observer, partySide) > 0)
			return;
		Optional<Row> memory = tx.queryOne("SELECT * FROM map_observation WHERE scene_id=? AND observer_key=?",
				scene(tx, encounter).id(), Ref.of(Ref.CHARACTER, observer));
		Point actual = SceneService.center(actor(tx, encounter, target));
		if (memory.isPresent())
			for (Map<String, Object> sighting : SceneService
					.maps(memory.get().map("knowledge_json").get("last_seen"))) {
				if (Ref.of(Ref.CHARACTER, target).equals(sighting.get("character"))
						&& sighting.get("position_ft") != null
						&& coordinatePair(sighting.get("position_ft")).equals(actual))
					return;
			}
		throw RpgException.notAllowed(
				"The acting creature has no perceived or last-known target at that position; investigate its available evidence first.");
	}

	public static int coverBonus(Tx tx, Row encounter, long caster, long target) {
		return switch (cover(tx, encounter, caster, target).cover()) {
		case HALF -> 2;
		case THREE_QUARTERS -> 5;
		default -> 0;
		};
	}

	public static int spellCoverBonus(Tx tx, Row encounter, long caster, long target, Map<String, Object> options) {
		Point origin = optionalPoint(options, "center_ft", optionalPoint(options, "origin_ft", null));
		if (origin == null)
			return coverBonus(tx, encounter, caster, target);
		Row actor = actor(tx, encounter, target);
		return switch (SceneService
				.cover(tx, scene(tx, encounter), actor.intOr("level", 0), origin, SceneService.footprint(actor))
				.cover()) {
		case HALF -> 2;
		case THREE_QUARTERS -> 5;
		default -> 0;
		};
	}

	private static long pointRange(Point a, Point b) {
		return Math.max(Math.abs(a.x() - b.x()), Math.abs(a.y() - b.y()));
	}

	private static Point optionalPoint(Map<String, Object> options, String key, Point fallback) {
		return options.get(key) == null ? fallback : coordinatePair(options.get(key));
	}

	private static Point coordinatePair(Object value) {
		if (value instanceof List<?> pair && pair.size() == 2 && pair.get(0) instanceof Number x
				&& pair.get(1) instanceof Number y)
			return SceneService.point(x.doubleValue(), y.doubleValue());
		throw RpgException.invalidArgument("Grid points require [x,y] coordinates in feet.");
	}

	private static long feet(String text, long fallback) {
		Matcher matcher = FEET.matcher(text);
		return matcher.find() ? Long.parseLong(matcher.group(1)) : fallback;
	}

	private static long firstNumber(String text) {
		Matcher matcher = Pattern.compile("\\d+").matcher(text);
		if (!matcher.find())
			throw RpgException.invalidArgument("The spell's area needs explicit supported geometry.");
		return Long.parseLong(matcher.group());
	}

	public static void dash(Tx tx, RulesData rules, Row encounter, Row participant, Row character) {
		Map<String, Object> movement = movement(tx, encounter, participant);
		if (Boolean.TRUE.equals(movement.get("dash"))) {
			throw RpgException.notAllowed("Dash has already supplied movement on this turn.");
		}
		// EncounterService pays for the Action before applying its effect in this same transaction.
		if (!TurnEconomy.markedThisTurn(tx, encounter, character.id(), TurnEconomy.ACTION)) {
			throw RpgException.notAllowed("Dash movement requires an Action paid through perform_encounter_action.");
		}
		movement.put("dash", true);
		movement.put("dash_inches", speed(tx, rules, character) * 12L);
		saveMovement(tx, participant.id(), movement);
	}

	public static void spendMovement(
		Tx tx, RulesData rules, Row encounter, Row participant, Row character, long inches) {
		if (inches < 0 || inches > remainingMovement(tx, rules, encounter, participant, character)) {
			throw RpgException.notAllowed("The movement exceeds this turn's remaining movement budget.");
		}
		Map<String, Object> movement = movement(tx, encounter, participant);
		movement.put("spent_inches", number(movement, "spent_inches", 0) + inches);
		saveMovement(tx, participant.id(), movement);
	}

	public static Map<String, Object> movementView(
		Tx tx, RulesData rules, Row encounter, Row participant, Row character) {
		Map<String, Object> movement = movement(tx, encounter, participant);
		return Map.of("remaining_ft", remainingMovement(tx, rules, encounter, participant, character) / 12.0,
				"spent_ft", number(movement, "spent_inches", 0) / 12.0, "dash_ft",
				number(movement, "dash_inches", 0) / 12.0);
	}

	public static Point destination(Map<String, Object> action) {
		if (action.get("x_ft") instanceof Number x && action.get("y_ft") instanceof Number y) {
			return SceneService.point(x.doubleValue(), y.doubleValue());
		}
		if (action.get("destination_ft") instanceof List<?> p && p.size() == 2 && p.get(0) instanceof Number x
				&& p.get(1) instanceof Number y) {
			return SceneService.point(x.doubleValue(), y.doubleValue());
		}
		if (action.get("zone") != null) {
			return legacyAnchor(action.get("zone"));
		}
		throw RpgException.invalidArgument("Movement requires x_ft/y_ft or destination_ft on the tactical grid.");
	}

	/**
	 * Extend incidental ground in the same frame; authored dungeons require published
	 * passages/coverage.
	 */
	public static void extendIncidentalCoverage(Tx tx, Row encounter, Footprint destination) {
		Row map = scene(tx, encounter);
		Map<String, Object> document = new LinkedHashMap<>(map.map("document_json"));
		Scene geometry = SceneService.geometry(SceneService.document(tx, map),
				actor(tx, encounter,
						tx.get("encounter_participant", encounter.lng("turn_participant_id")).lng("character_id"))
						.intOr("level", 0));
		Bounds old = geometry.bounds(), target = destination.bounds();
		if (old.contains(target))
			return;
		if (!"INCIDENTAL_ENCOUNTER".equals(document.get("source"))
				&& !Boolean.TRUE.equals(document.get("extendible_ground")))
			return;
		long minX = Math.min(old.minX(), target.minX() - 300), minY = Math.min(old.minY(), target.minY() - 300);
		long maxX = Math.max(old.maxX(), target.maxX() + 300), maxY = Math.max(old.maxY(), target.maxY() + 300);
		minX = Math.floorDiv(minX, 60) * 60;
		minY = Math.floorDiv(minY, 60) * 60;
		maxX = -Math.floorDiv(-maxX, 60) * 60;
		maxY = -Math.floorDiv(-maxY, 60) * 60;
		if ((maxX - minX) / 60 * ((maxY - minY) / 60) > 40_000)
			throw RpgException.notAllowed(
					"Prepare linked tactical coverage for this longer movement; the current view's work limit is reached.");
		var rooms = new ArrayList<>(SceneService.maps(document.get("rooms")));
		int level = actor(tx, encounter,
				tx.get("encounter_participant", encounter.lng("turn_participant_id")).lng("character_id"))
				.intOr("level", 0);
		String material = rooms.isEmpty() ? "EARTH" : SceneService.string(rooms.getFirst(), "material", "EARTH");
		long revision = map.lng("layout_revision") + 1;
		addExtension(rooms, minX, minY, maxX, old.minY(), level, material, revision, "south");
		addExtension(rooms, minX, old.maxY(), maxX, maxY, level, material, revision, "north");
		addExtension(rooms, minX, old.minY(), old.minX(), old.maxY(), level, material, revision, "west");
		addExtension(rooms, old.maxX(), old.minY(), maxX, old.maxY(), level, material, revision, "east");
		document.put("bounds_ft", List.of(minX / 12.0, minY / 12.0, (maxX - minX) / 12.0, (maxY - minY) / 12.0));
		document.put("rooms", rooms);
		tx.update("map_scene", map.id(), Map.of("document_json", Json.write(document), "layout_revision", revision,
				"revision", map.lng("revision") + 1));
	}

	private static void addExtension(
		List<Map<String, Object>> rooms, long minX, long minY, long maxX, long maxY, int level, String material,
		long revision, String side) {
		if (maxX <= minX || maxY <= minY)
			return;
		rooms.add(Map.of("key", "coverage-" + revision + "-" + side, "level", level, "enclosed", false, "rect_ft",
				List.of(minX / 12.0, minY / 12.0, (maxX - minX) / 12.0, (maxY - minY) / 12.0), "material", material));
	}

	public static void moveActor(Tx tx, Row encounter, Row placement, Point point) {
		tx.update("map_actor", placement.id(), Map.of("x_ft", point.x() / 12.0, "y_ft", point.y() / 12.0, "revision",
				tx.get("map_actor", placement.id()).lng("revision") + 1));
		Row map = scene(tx, encounter);
		for (Map<String, Object> room : SceneService.maps(SceneService.document(tx, map).get("rooms"))) {
			Polygon shape = SceneService.polygon(room);
			if (shape != null && room.get("location_ref") instanceof String ref
					&& SceneService.number(room, "level", 0).intValue() == placement.intOr("level", 0)
					&& shape.contains(SceneService.footprint(placement).at(point).center())) {
				Row location = tx.get("location", Ref.id(ref, Ref.LOCATION));
				if (location.lng("campaign_id") != encounter.lng("campaign_id")) {
					throw RpgException.invalidArgument("Room placement belongs to another campaign.");
				}
				Row character = tx.get("character", placement.lng("character_id"));
				tx.update("character", character.id(),
						Map.of("location_id", location.id(), "revision", character.lng("revision") + 1));
				if (tx.count(
						"SELECT COUNT(*) FROM player_control_assignment WHERE campaign_id=? AND character_id=? AND active=1",
						encounter.lng("campaign_id"), character.id()) > 0) {
					Row campaign = tx.get("campaign", encounter.lng("campaign_id"));
					tx.update("campaign", campaign.id(),
							Map.of("current_location_id", location.id(), "revision", campaign.lng("revision") + 1));
				}
				break;
			}
		}
	}

	private static Map<String, Object> movement(Tx tx, Row encounter, Row participant) {
		Row live = tx.get("encounter_participant", participant.id());
		Map<String, Object> stored = SceneService.map(live.map("once_per_turn_json").get("grid_movement"));
		String key = TurnEconomy.turnKey(encounter);
		return key.equals(stored.get("turn")) ? new LinkedHashMap<>(stored)
				: new LinkedHashMap<>(Map.of("turn", key, "spent_inches", 0L, "dash_inches", 0L));
	}

	private static void saveMovement(Tx tx, long participantId, Map<String, Object> movement) {
		var flags = new LinkedHashMap<>(tx.get("encounter_participant", participantId).map("once_per_turn_json"));
		flags.put("grid_movement", movement);
		tx.update("encounter_participant", participantId, Map.of("once_per_turn_json", Json.write(flags)));
	}

	private static int speed(Tx tx, RulesData rules, Row character) {
		if (hasCondition(tx, character.id(), "GRAPPLED") || hasCondition(tx, character.id(), "RESTRAINED")) {
			return 0;
		}
		return Exhaustion.speed(tx, rules, character);
	}

	private static boolean hasCondition(Tx tx, long character, String condition) {
		return tx.count("SELECT COUNT(*) FROM active_effect WHERE character_id=? AND condition_ref=?", character,
				RuntimeService.conditionRef(condition)) > 0;
	}

	private static int footprintCells(RulesData rules, Row character) {
		String size = character.isNull("origin_content_ref") ? "MEDIUM"
				: rules.find(character.str("origin_content_ref"))
						.map(d -> String.valueOf(d.payload().getOrDefault("size", "MEDIUM"))).orElse("MEDIUM");
		return switch (size.toUpperCase()) {
		case "LARGE" -> 2;
		case "HUGE" -> 3;
		case "GARGANTUAN" -> 4;
		default -> 1;
		};
	}

	private static Footprint nearestPlacement(
		Scene scene, Point preferred, int cells, Collection<Footprint> occupied, List<Footprint> separatedFrom) {
		var candidates = new ArrayList<Point>();
		Bounds bounds = scene.bounds();
		long columns = bounds.width() / 60;
		long rows = bounds.height() / 60;
		if (columns * rows > 40_000) {
			throw RpgException.invalidArgument("Choose a bounded tactical deployment area for this scene.");
		}
		for (long x = Math.floorDiv(bounds.minX() + 59, 60) * 60; x + cells * 60 <= bounds.maxX(); x += 60) {
			for (long y = Math.floorDiv(bounds.minY() + 59, 60) * 60; y + cells * 60 <= bounds.maxY(); y += 60) {
				candidates.add(new Point(x, y));
			}
		}
		candidates.sort(Comparator
				.<Point> comparingLong(p -> Math.max(Math.abs(p.x() - preferred.x()), Math.abs(p.y() - preferred.y())))
				.thenComparingLong(Point::x).thenComparingLong(Point::y));
		Footprint farthest = null;
		long farthestDistance = -1;
		for (Point point : candidates) {
			Footprint footprint = new Footprint(point, cells, cells);
			if (SpatialRules.canPlace(scene, footprint, occupied)) {
				long distance = separatedFrom.stream().mapToLong(party -> SpatialRules.distance(party, footprint)).min()
						.orElse(Long.MAX_VALUE);
				if (distance >= 30 * 12)
					return footprint;
				if (distance > farthestDistance) {
					farthest = footprint;
					farthestDistance = distance;
				}
			}
		}
		if (farthest != null)
			return farthest;
		throw RpgException.notAllowed("The prepared scene has no valid deployment space; extend its coverage first.");
	}

	private static Point legacyAnchor(Object label) {
		return switch (label == null ? "near" : label.toString().toLowerCase()) {
		case "far" -> SceneService.point(45, 25);
		case "distant" -> SceneService.point(75, 25);
		default -> SceneService.point(25, 25);
		};
	}

	private static boolean coversLocation(Row scene, List<Long> locations) {
		if (locations.contains(scene.lng("location_id")))
			return true;
		return SceneService.maps(scene.map("document_json").get("rooms")).stream().anyMatch(room -> locations.stream()
				.anyMatch(location -> Ref.of(Ref.LOCATION, location).equals(room.get("location_ref"))));
	}

	private static boolean incidental(Row scene) {
		return scene.isNull("preparation_id")
				&& "INCIDENTAL_ENCOUNTER".equals(scene.map("document_json").get("source"));
	}

	private static String scope(Row scene) {
		return SceneService.string(scene.map("document_json"), "scene_scope_key",
				scope(SceneService.map(scene.map("document_json").get("authoring_input")), scene.lng("location_id")));
	}

	private static String scope(Map<String, Object> authored, Long locationId) {
		String key = SceneService.string(authored, "scope_key",
				SceneService.string(authored, "location_ref", SceneService.string(authored, "name", "location")));
		return Ref.ofNullable(Ref.LOCATION, locationId) + "/" + key.trim().toLowerCase(Locale.ROOT);
	}

	private static boolean sameIncidentalScope(Row first, Row second) {
		return Objects.equals(first.lng("location_id"), second.lng("location_id"))
				&& scope(first).equals(scope(second));
	}

	private static boolean publishedUpgrade(Row provisional, Row prepared) {
		return incidental(provisional) && !prepared.isNull("preparation_id") && prepared.id() > provisional.id()
				&& Objects.equals(prepared.lng("location_id"), provisional.lng("location_id"))
				&& (scope(provisional).equals(scope(prepared))
						|| scope(provisional).equals(scope(Map.of(), provisional.lng("location_id")))
						|| provisional.str("name").equalsIgnoreCase(prepared.str("name")));
	}

	private static Long knownCanonicalRoom(Tx tx, long campaignId, Long locationId) {
		for (long id : ancestry(tx, campaignId, locationId))
			if ("AREA".equals(tx.get("location", id).str("kind"))
					&& tx.count(
							"SELECT COUNT(*) FROM map_scene s,json_each(s.document_json,'$.rooms') r "
									+ "WHERE s.campaign_id=? AND s.kind IN ('SCENE','DUNGEON') "
									+ "AND json_extract(r.value,'$.location_ref')=?",
							campaignId, Ref.of(Ref.LOCATION, id)) > 0)
				return id;
		return null;
	}

	private static Scene automaticDeploymentGeometry(
		Tx tx, long campaignId, Long place, Row map, Map<String, Object> document, Scene geometry, int level) {
		List<Map<String, Object>> authored = SceneService.maps(document.get("rooms"));
		if (authored.stream().noneMatch(room -> room.get("location_ref") != null)) {
			if (place == null || map.isNull("location_id")
					|| ancestry(tx, campaignId, map.lng("location_id")).contains(place))
				return geometry;
			throw RpgException.notAllowed(
					"This board does not represent " + Ref.of(Ref.LOCATION, place) + " at level " + level + ".");
		}
		var legal = new ArrayList<Map<String, Object>>();
		for (var room : authored)
			if (room.get("location_ref") instanceof String ref
					&& SceneService.number(room, "level", 0).intValue() == level && SceneService.polygon(room) != null
					&& (place == null || ancestry(tx, campaignId, Ref.id(ref, Ref.LOCATION)).contains(place)))
				legal.add(room);
		if (legal.isEmpty())
			throw RpgException
					.notAllowed("No room at level " + level + " belongs to " + Ref.ofNullable(Ref.LOCATION, place)
							+ " or one of its descendants; sibling and ancestor rooms are excluded.");
		Set<String> legalRefs = new LinkedHashSet<>();
		legal.forEach(room -> legalRefs.add(room.get("location_ref").toString()));
		Map<String, Object> seed = legal.stream()
				.filter(room -> Objects.equals(room.get("location_ref"), Ref.ofNullable(Ref.LOCATION, place)))
				.findFirst()
				.orElseGet(() -> legal.stream()
						.filter(room -> Boolean.TRUE.equals(room.get("entry"))
								|| "ENTRY".equalsIgnoreCase(SceneService.string(room, "role", "")))
						.findFirst()
						.orElseGet(() -> legal.stream()
								.filter(room -> SceneService.maps(document.get("doors")).stream().anyMatch(
										door -> Objects.equals(room.get("location_ref"), door.get("from_location_ref"))
												&& !legalRefs.contains(door.get("to_location_ref"))
												|| Objects.equals(room.get("location_ref"), door.get("to_location_ref"))
														&& !legalRefs.contains(door.get("from_location_ref"))))
								.findFirst().orElse(legal.getFirst())));
		Set<String> connected = new LinkedHashSet<>();
		connected.add(seed.get("location_ref").toString());
		boolean grew;
		do {
			grew = false;
			for (var door : SceneService.maps(document.get("doors"))) {
				if (SceneService.number(door, "level", 0).intValue() != level || !Set
						.of("OPEN", "DESTROYED", "BREACHED").contains(SceneService.string(door, "state", "OPEN")))
					continue;
				String from = SceneService.string(door, "from_location_ref", ""),
						to = SceneService.string(door, "to_location_ref", "");
				if (connected.contains(from) && legalRefs.contains(to))
					grew |= connected.add(to);
				if (connected.contains(to) && legalRefs.contains(from))
					grew |= connected.add(from);
			}
		} while (grew);
		List<Polygon> floors = legal.stream().filter(room -> connected.contains(room.get("location_ref")))
				.map(SceneService::polygon).toList();
		return new Scene(geometry.bounds(), floors, geometry.movementBlocks(), geometry.sightBlocks(), geometry.walls(),
				geometry.terrain());
	}

	/**
	 * Validate a peaceful rebind; the caller still validates destination geometry and occupancy.
	 */
	public static void requireSameLocationTransition(
		Tx tx, long campaignId, Row character, Row from, Row to, Footprint destination, int level) {
		for (Row scene : List.of(from, to))
			if (scene.lng("campaign_id") != campaignId || !Set.of("SCENE", "DUNGEON").contains(scene.str("kind")))
				throw RpgException.invalidArgument("A scene transition needs tactical maps from this campaign.");
		List<Long> here = ancestry(tx, campaignId, character.lng("location_id"));
		if (here.isEmpty()
				|| !coversLocation(from, here)
						&& !samePlace(tx, campaignId, from.lng("location_id"), character.lng("location_id"))
				|| !coversLocation(to, here)
						&& !samePlace(tx, campaignId, to.lng("location_id"), character.lng("location_id")))
			throw RpgException.notAllowed(actorLabel(character) + " cannot move peacefully from " + mapLabel(from)
					+ " to " + mapLabel(to) + " while at " + Ref.ofNullable(Ref.LOCATION, character.lng("location_id"))
					+ ". Resolve travel to the destination first.");
		// An initial site can gain its first room layout. Once a character is in an authored room, a
		// different board must represent that same room, rather than turn re-placement into movement
		// through its locked doors or between dungeon levels.
		Long currentRoom = knownCanonicalRoom(tx, campaignId, character.lng("location_id"));
		if (currentRoom == null) {
			Optional<Row> source = tx.queryOne(
					"SELECT * FROM map_actor WHERE campaign_id=? AND character_id=? AND scene_id=?", campaignId,
					character.id(), from.id());
			if (source.isPresent()) {
				for (var room : SceneService.maps(from.map("document_json").get("rooms"))) {
					Polygon shape = SceneService.polygon(room);
					if (room.get("location_ref") instanceof String ref && shape != null
							&& SceneService.number(room, "level", 0).intValue() == source.get().intOr("level", 0)
							&& shape.contains(SceneService.footprint(source.get()).center())) {
						long physicalRoom = Ref.id(ref, Ref.LOCATION);
						if ("AREA".equals(tx.get("location", physicalRoom).str("kind"))) {
							currentRoom = physicalRoom;
							break;
						}
					}
				}
				if (currentRoom == null && !from.isNull("location_id")
						&& "AREA".equals(tx.get("location", from.lng("location_id")).str("kind")))
					currentRoom = from.lng("location_id");
			}
		}
		if (currentRoom == null)
			return;
		Set<Long> endpointRooms = new LinkedHashSet<>();
		for (var room : SceneService.maps(to.map("document_json").get("rooms"))) {
			Polygon shape = SceneService.polygon(room);
			if (room.get("location_ref") instanceof String ref && shape != null
					&& SceneService.number(room, "level", 0).intValue() == level
					&& shape.contains(destination.center()))
				endpointRooms.add(Ref.id(ref, Ref.LOCATION));
		}
		if (endpointRooms.isEmpty() && Objects.equals(to.lng("location_id"), currentRoom))
			return;
		if (endpointRooms.size() != 1 || !endpointRooms.contains(currentRoom))
			throw RpgException.notAllowed(actorLabel(character) + " is in " + Ref.of(Ref.LOCATION, currentRoom)
					+ "; a board rebind must keep the same canonical room. Use movement or the existing passage to reach another room before changing boards.")
					.withDetail("current_room", Ref.of(Ref.LOCATION, currentRoom)).withDetail("destination_rooms",
							endpointRooms.stream().map(id -> Ref.of(Ref.LOCATION, id)).toList());
	}

	private static boolean samePlace(Tx tx, long campaignId, Long first, Long second) {
		return first != null && second != null && se.hirt.mcp.rpg.world.WorldService.travelAnchor(tx, campaignId, first)
				.id() == se.hirt.mcp.rpg.world.WorldService.travelAnchor(tx, campaignId, second).id();
	}

	/** An explicit canonical relocation invalidates that actor's old physical position. */
	public static boolean clearPlacementForLocationOverride(Tx tx, long campaignId, Row character, long destination) {
		return clearPlacementForLocationOverride(tx, campaignId, character, destination, false);
	}

	public static boolean clearPlacementForLocationOverride(
		Tx tx, long campaignId, Row character, long destination, boolean clearSamePlace) {
		if (Objects.equals(character.lng("location_id"), destination) && !clearSamePlace)
			return false;
		Optional<Row> previous = tx.queryOne("SELECT * FROM map_actor WHERE campaign_id=? AND character_id=?",
				campaignId, character.id());
		if (previous.isEmpty())
			return false;
		Row scene = tx.get("map_scene", previous.get().lng("scene_id"));
		boolean compatible = coversLocation(scene, ancestry(tx, campaignId, destination));
		if (compatible)
			for (var room : SceneService.maps(scene.map("document_json").get("rooms"))) {
				Polygon shape = SceneService.polygon(room);
				if (room.get("location_ref") != null && shape != null
						&& SceneService.number(room, "level", 0).intValue() == previous.get().intOr("level", 0)
						&& shape.contains(SceneService.footprint(previous.get()).center())) {
					compatible = ancestry(tx, campaignId, Ref.id(room.get("location_ref").toString(), Ref.LOCATION))
							.contains(destination);
					break;
				}
			}
		if (compatible && !clearSamePlace)
			return false;
		if (!character.isNull("encounter_id") && tx.find("encounter", character.lng("encounter_id"))
				.filter(encounter -> Set.of("RUNNING", "WAITING_CHOICE").contains(encounter.str("status"))).isPresent())
			throw RpgException.notAllowed(actorLabel(character) + " is still in active "
					+ Ref.of(Ref.ENCOUNTER, character.lng("encounter_id"))
					+ ". End the encounter or remove this participant before relocating it outside its tactical coverage.");
		tx.delete("map_actor", previous.get().id());
		return true;
	}

	/**
	 * The current board is defined by the controlled actor's saved figurine, rather than coverage
	 * availability.
	 */
	public static Map<String, Object> currentBoard(Tx tx, long campaignId) {
		return tx
				.queryOne("SELECT s.* FROM map_scene s JOIN map_actor a ON a.scene_id=s.id "
						+ "JOIN player_control_assignment p ON p.character_id=a.character_id AND p.active=1 "
						+ "WHERE p.campaign_id=? ORDER BY p.id LIMIT 1", campaignId)
				.<Map<String, Object>> map(scene -> Map.of("status", "PLACED", "map_ref", Ref.of(Ref.MAP, scene.id()),
						"name", scene.str("name"), "kind", scene.str("kind"), "basis", "CONTROLLED_ACTOR_FIGURINE"))
				.orElse(Map.of("status", "NO_CURRENT_BOARD"));
	}

	/** Resolve a contact only for the selected traveling party, never for a party left at home. */
	public static Long encounterLocation(
		Tx tx, long campaignId, Long requested, List<Row> characters, Collection<?> partyRefs) {
		return encounterLocation(tx, campaignId, requested, characters, partyRefs, false);
	}

	public static Long encounterLocation(
		Tx tx, long campaignId, Long requested, List<Row> characters, Collection<?> partyRefs,
		boolean explicitLocation) {
		if (!encounterJourney(tx, campaignId, characters, partyRefs))
			return requested;
		Long contact = TravelJourneyService.effectiveLocation(tx, campaignId, null);
		Map<String, Object> journey = TravelJourneyService.stateInTx(tx, campaignId);
		Long from = journey.get("from") instanceof String ref ? Ref.id(ref, Ref.LOCATION) : null;
		Long destination = journey.get("destination") instanceof String ref ? Ref.id(ref, Ref.LOCATION) : null;
		boolean departureContext = explicitLocation && from != null && !Objects.equals(requested, destination)
				&& (Objects.equals(requested, from) || Objects.equals(requested,
						se.hirt.mcp.rpg.world.WorldService.travelAnchor(tx, campaignId, from).id()));
		if (explicitLocation && !Objects.equals(requested, contact) && !departureContext)
			throw RpgException.notAllowed("The selected saved travelers are at " + Ref.ofNullable(Ref.LOCATION, contact)
					+ ", not the requested encounter location " + Ref.ofNullable(Ref.LOCATION, requested)
					+ ". Use the journey's contact_location for this encounter or complete the saved journey before fighting elsewhere.");
		return contact;
	}

	private static boolean encounterJourney(Tx tx, long campaignId, List<Row> characters, Collection<?> partyRefs) {
		Map<String, Object> journey = TravelJourneyService.stateInTx(tx, campaignId);
		if (!Boolean.TRUE.equals(journey.get("active")) || !(journey.get("movers") instanceof Collection<?> movers))
			return false;
		Collection<?> selected = partyRefs.isEmpty()
				? characters.stream().map(c -> Ref.of(Ref.CHARACTER, c.id())).toList() : partyRefs;
		boolean atContact = selected.stream().anyMatch(movers::contains);
		if (!atContact) {
			var travelingParticipants = characters.stream().filter(c -> movers.contains(Ref.of(Ref.CHARACTER, c.id())))
					.map(GridCombat::actorLabel).toList();
			if (!travelingParticipants.isEmpty())
				throw RpgException.notAllowed(String.join(", ", travelingParticipants)
						+ " are on the saved journey while the selected party is elsewhere. Select participants at the same place or reunite them before starting this encounter.");
			return false;
		}
		var elsewhere = new LinkedHashSet<String>();
		for (Row character : characters) {
			String ref = Ref.of(Ref.CHARACTER, character.id());
			boolean controlled = tx.count(
					"SELECT COUNT(*) FROM player_control_assignment WHERE campaign_id=? AND character_id=? AND active=1",
					campaignId, character.id()) > 0;
			if ((selected.contains(ref) || controlled) && !movers.contains(ref))
				elsewhere.add(actorLabel(character));
		}
		if (!elsewhere.isEmpty())
			throw RpgException.notAllowed("The selected party is split between the saved journey and another place: "
					+ String.join(", ", elsewhere)
					+ " are not saved travelers. Select participants at the same place or reunite them before starting this encounter.");
		return true;
	}

	/**
	 * Published coverage for the controlled character's exact semantic room is discoverable even
	 * before a figurine is placed. This is a pointer only: it neither chooses a board nor reveals
	 * any unobserved geometry, map title, objects or sibling rooms.
	 */
	public static Map<String, Object> placementHint(Tx tx, long campaignId) {
		Optional<Row> controlled = tx
				.queryOne("SELECT c.* FROM character c JOIN player_control_assignment p ON p.character_id=c.id "
						+ "WHERE p.campaign_id=? AND p.active=1 ORDER BY p.id LIMIT 1", campaignId);
		if (controlled.isPresent() && tx.count("SELECT COUNT(*) FROM map_actor WHERE campaign_id=? AND character_id=?",
				campaignId, controlled.get().id()) > 0)
			return Map.of();
		Map<String, Object> journey = TravelJourneyService.stateInTx(tx, campaignId);
		if (Boolean.TRUE.equals(journey.get("active"))
				&& (controlled.isEmpty() || journey.get("movers") instanceof Collection<?> movers
						&& movers.contains(Ref.of(Ref.CHARACTER, controlled.get().id()))))
			return Map.of();
		Long locationId = controlled.filter(c -> !c.isNull("location_id")).map(c -> c.lng("location_id"))
				.orElseGet(() -> tx.get("campaign", campaignId).lng("current_location_id"));
		if (locationId == null)
			return Map.of();
		String locationRef = Ref.of(Ref.LOCATION, locationId);
		var coverage = new ArrayList<Map<String, Object>>();
		for (Row scene : tx.query("SELECT * FROM map_scene WHERE campaign_id=? AND preparation_id IS NOT NULL "
				+ "AND kind IN ('SCENE','DUNGEON') ORDER BY id", campaignId)) {
			var levels = new java.util.TreeSet<Integer>();
			for (var room : SceneService.maps(scene.map("document_json").get("rooms")))
				if (locationRef.equals(room.get("location_ref")))
					levels.add(SceneService.number(room, "level", scene.intOr("level", 0)).intValue());
			for (int level : levels)
				coverage.add(Map.of("map_ref", Ref.of(Ref.MAP, scene.id()), "kind", scene.str("kind"), "level", level));
		}
		if (coverage.isEmpty())
			return Map.of();
		var hint = new LinkedHashMap<String, Object>();
		hint.put("status", "UNPLACED");
		hint.put("location_ref", locationRef);
		hint.put("coverage_status", coverage.size() == 1 ? "AVAILABLE" : "AMBIGUOUS");
		hint.put("coverage", coverage);
		hint.put("placement_required", true);
		hint.put("next_operation", "get_map_context");
		if (coverage.size() == 1) {
			hint.put("map_ref", coverage.getFirst().get("map_ref"));
			hint.put("level", coverage.getFirst().get("level"));
		}
		return hint;
	}

	/** Carry resolved physical state only when a keyed furnishing/passage keeps its geometry. */
	private static void carryInlineState(Map<String, Object> previous, Map<String, Object> next) {
		for (String layer : List.of("objects", "doors")) {
			var updated = new ArrayList<Map<String, Object>>();
			for (var entry : SceneService.maps(next.get(layer))) {
				var copy = new LinkedHashMap<>(entry);
				var matches = SceneService.maps(previous.get(layer)).stream()
						.filter(old -> entry.get("key") != null && entry.get("key").equals(old.get("key")))
						.filter(old -> List.of("rect_ft", "polygon_ft", "from_ft", "to_ft", "x_ft", "y_ft", "level")
								.stream()
								.allMatch(key -> Objects.equals(Json.write(old.get(key)), Json.write(entry.get(key)))))
						.toList();
				if (matches.size() == 1)
					for (String key : List.of("state", "hp", "hit_points", "locked", "sealed", "concealed",
							"last_change_reason"))
						if (matches.getFirst().containsKey(key))
							copy.put(key, matches.getFirst().get(key));
				updated.add(copy);
			}
			if (next.containsKey(layer))
				next.put(layer, updated);
		}
	}

	private static String actorLabel(Row character) {
		return character.str("name") + " (" + Ref.of(Ref.CHARACTER, character.id()) + ")";
	}

	private static String mapLabel(Row scene) {
		return scene.str("name") + " (" + Ref.of(Ref.MAP, scene.id()) + ")";
	}

	private static String deploymentError(
		Tx tx, Row scene, Row character, Map<String, Object> document, Scene geometry, Footprint placement,
		Collection<Footprint> occupied, int level) {
		SpatialRules.PlacementStatus status = SpatialRules.placement(geometry, placement, occupied);
		var blockers = new ArrayList<String>();
		if (status == SpatialRules.PlacementStatus.OCCUPIED)
			for (Row other : SceneService.blockingActors(tx, scene.id(), level))
				if (other.lng("character_id") != character.id()
						&& placement.bounds().overlapsInterior(SceneService.footprint(other).bounds()))
					blockers.add(actorLabel(tx.get("character", other.lng("character_id"))));
		if (status == SpatialRules.PlacementStatus.BLOCKED) {
			for (String layer : List.of("objects", "doors", "walls")) {
				for (var entry : SceneService.maps(document.get(layer))) {
					var isolated = new LinkedHashMap<>(document);
					isolated.put("enclose_rooms", false);
					for (String other : List.of("objects", "doors", "walls"))
						isolated.put(other, other.equals(layer) ? List.of(entry) : List.of());
					if (SpatialRules.placement(SceneService.geometry(isolated, level), placement,
							List.of()) == SpatialRules.PlacementStatus.BLOCKED)
						blockers.add(SceneService.string(entry, "name", SceneService.string(entry, "label", layer))
								+ " [" + SceneService.string(entry, "key", "unkeyed") + "]");
				}
			}
		}
		return "Cannot deploy " + actorLabel(character) + " at (" + placement.origin().x() / 12.0 + ", "
				+ placement.origin().y() / 12.0 + ") ft, level " + level + " on " + mapLabel(scene) + ": " + status
				+ (blockers.isEmpty() ? ". Check saved floor coverage, room boundaries and passages."
						: "; blocked by " + String.join(", ", blockers) + ".");
	}

	private static boolean coversJourney(Map<String, Object> saved, Map<String, Object> current) {
		if (!Objects.equals(SceneService.map(saved.get("route_anchor")).get("connection_ref"),
				SceneService.map(current.get("route_anchor")).get("connection_ref"))
				|| !saved.containsKey("journey_scene_key"))
			return false;
		if (Objects.equals(saved.get("journey_scene_key"), current.get("journey_scene_key")))
			return true;
		if (!Boolean.TRUE.equals(saved.get("route_geometry_known"))
				|| !Boolean.TRUE.equals(current.get("route_geometry_known")))
			return false;
		Point anchor = coordinatePair(current.get("journey_world_anchor_ft"));
		Point origin = coordinatePair(saved.get("world_origin_ft"));
		List<?> bounds = (List<?>) saved.get("bounds_ft");
		double x = (anchor.x() - origin.x()) / 12.0, y = (anchor.y() - origin.y()) / 12.0;
		double minX = ((Number) bounds.get(0)).doubleValue(), minY = ((Number) bounds.get(1)).doubleValue();
		return x >= minX + 10 && y >= minY + 10 && x <= minX + ((Number) bounds.get(2)).doubleValue() - 10
				&& y <= minY + ((Number) bounds.get(3)).doubleValue() - 10;
	}

	private static Point snapped(long x, long y) {
		return new Point(Math.floorDiv(x, 60) * 60, Math.floorDiv(y, 60) * 60);
	}

	private static List<Long> ancestry(Tx tx, long campaignId, Long locationId) {
		var result = new ArrayList<Long>();
		while (locationId != null && !result.contains(locationId) && result.size() < 100) {
			Row location = tx.get("location", locationId);
			if (location.lng("campaign_id") != campaignId) {
				throw RpgException.invalidArgument("Encounter location belongs to another campaign.");
			}
			result.add(locationId);
			locationId = location.lng("parent_id");
		}
		return result;
	}

	private static long number(Map<String, Object> map, String key, long fallback) {
		return map != null && map.get(key) instanceof Number n ? n.longValue() : fallback;
	}

	private static long currentSeconds(Tx tx, long campaignId) {
		Row clock = GameTime.clock(tx, campaignId);
		return clock.lng("seq") * 60 + clock.intOr("second_offset", 0);
	}
}
