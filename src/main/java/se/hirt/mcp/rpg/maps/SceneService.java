package se.hirt.mcp.rpg.maps;

import se.hirt.mcp.rpg.character.CharacterService;
import se.hirt.mcp.rpg.harness.Harness;
import se.hirt.mcp.rpg.persistence.Database;
import se.hirt.mcp.rpg.persistence.Row;
import se.hirt.mcp.rpg.persistence.Tx;
import se.hirt.mcp.rpg.protocol.Json;
import se.hirt.mcp.rpg.protocol.Ref;
import se.hirt.mcp.rpg.protocol.RpgException;
import se.hirt.mcp.rpg.spatial.Geometry.*;
import se.hirt.mcp.rpg.spatial.SpatialRules;

import java.util.*;

/** Saved physical scenes and knowledge-limited projections shared by every presentation. */
public final class SceneService {

	private final Database db;
	private final se.hirt.mcp.rpg.content.RulesData rules;
	private static final SpatialRules SPATIAL = new SpatialRules();

	public SceneService(Database db) {
		this(db, new se.hirt.mcp.rpg.content.RulesData(db));
	}

	public SceneService(Database db, se.hirt.mcp.rpg.content.RulesData rules) {
		this.db = db;
		this.rules = rules;
	}

	public static Row scene(Tx tx, long campaignId, String ref) {
		Row row = tx.get("map_scene", Ref.id(ref, Ref.MAP));
		if (row.lng("campaign_id") != campaignId)
			throw RpgException.invalidArgument("Map belongs to another campaign.");
		return row;
	}

	public Map<String, Object> context(String campaignRef, String mapRef, String audience, int level) {
		return db.read(tx -> {
			Row campaign = Harness.campaign(tx, campaignRef);
			Row row = scene(tx, campaign.id(), mapRef);
			return Map.of("map", mapRef, "map_revision", row.lng("revision"), "level", level, "view",
					projection(tx, row, audience, level), "meta", Harness.meta(campaign, List.of()));
		});
	}

	public Map<String, Object> place(
		String op, String campaignRef, String mapRef, List<Map<String, Object>> placements) {
		long cid = Ref.id(campaignRef, Ref.CAMPAIGN);
		return db.mutate(Database.Mutation.of("place_map_actors", cid, op, "GM",
				Map.of("map", mapRef, "placements", placements)), tx -> {
					Row campaign = Harness.requireMutation(tx, campaignRef, "place_map_actors");
					Row map = scene(tx, cid, mapRef);
					if (placements.isEmpty() || placements.size() > 100)
						throw RpgException.invalidArgument("Supply 1–100 placements.");
					for (Map<String, Object> p : placements) {
						Row c = CharacterService.character(tx, cid, string(p, "character", null));
						Optional<Row> previous = tx.queryOne(
								"SELECT * FROM map_actor WHERE campaign_id=? AND character_id=?", cid, c.id());
						if (!c.isNull("location_id") && !map.isNull("location_id")) {
							long current = c.lng("location_id");
							boolean compatible = false;
							var seen = new HashSet<Long>();
							while (seen.add(current)) {
								if (current == map.lng("location_id")) {
									compatible = true;
									break;
								}
								Row location = tx.get("location", current);
								if (location.isNull("parent_id"))
									break;
								current = location.lng("parent_id");
							}
							if (!compatible)
								compatible = maps(map.map("document_json").get("rooms")).stream()
										.anyMatch(room -> Objects.equals(room.get("location_ref"),
												Ref.of(Ref.LOCATION, c.lng("location_id"))));
							if (!compatible && (previous.isEmpty() || previous.get().lng("scene_id") == map.id()))
								throw RpgException.notAllowed(c.str("name") + " (" + string(p, "character", null)
										+ ") is at another established location (location:" + c.lng("location_id")
										+ ") than " + map.str("name") + " (" + mapRef
										+ "). Travel there or use an explicit resolved transition before placement.");
						}
						int level = number(p, "level", map.intOr("level", 0)).intValue();
						Footprint f = footprint(p);
						List<Footprint> occupied = blockingActors(tx, map.id(), level).stream()
								.filter(a -> a.lng("character_id") != c.id()).map(SceneService::footprint).toList();
						var valid = SPATIAL.placement(geometry(document(tx, map), level), f, occupied);
						if (!valid.valid())
							throw RpgException.invalidArgument("Invalid placement for " + c.str("name") + " ("
									+ string(p, "character", null) + ") on " + map.str("name") + " (" + mapRef + ") at "
									+ feet(f.origin()) + ": " + valid);
						var cols = new LinkedHashMap<String, Object>();
						cols.put("scene_id", map.id());
						cols.put("x_ft", f.origin().x() / 12.0);
						cols.put("y_ft", f.origin().y() / 12.0);
						cols.put("level", level);
						cols.put("footprint_ft", f.columns() * 5);
						cols.put("revision", previous.map(a -> a.lng("revision") + 1).orElse(0L));
						if (previous.isPresent() && (previous.get().lng("scene_id") != map.id()
								|| !footprint(previous.get()).equals(f) || previous.get().intOr("level", 0) != level)) {
							Row oldMap = tx.get("map_scene", previous.get().lng("scene_id"));
							if (oldMap.id() != map.id())
								GridCombat.requireSameLocationTransition(tx, cid, c, oldMap, map, f, level);
							else
								throw RpgException.notAllowed(c.str("name") + " (" + string(p, "character", null)
										+ ") already has an established position on " + oldMap.str("name") + " (map:"
										+ oldMap.id() + "). Use move_map_actor for movement on that saved board.");
						}
						if (previous.isPresent())
							tx.update("map_actor", previous.get().id(), cols);
						else {
							cols.put("campaign_id", cid);
							cols.put("character_id", c.id());
							tx.insert("map_actor", cols);
						}
					}
					for (Map<String, Object> placement : placements)
						syncLocation(tx, map, actor(tx, cid, string(placement, "character", null)));
					remember(tx, map);
					return Map.of("map", mapRef, "view", projection(tx, map, "PLAYER", map.intOr("level", 0)), "meta",
							Harness.meta(tx.get("campaign", cid), List.of()));
				});
	}

	public Map<String, Object> observe(String op, String campaignRef, String mapRef) {
		long cid = Ref.id(campaignRef, Ref.CAMPAIGN);
		return db.mutate(Database.Mutation.of("observe_map", cid, op, "GM", Map.of("map", mapRef)), tx -> {
			Row c = Harness.requireMutation(tx, campaignRef, "observe_map");
			Row s = scene(tx, cid, mapRef);
			remember(tx, s);
			return Map.of("map", mapRef, "view", projection(tx, s, "PLAYER", s.intOr("level", 0)), "meta",
					Harness.meta(c, List.of()));
		});
	}

	/** Explicit adjudicated world effects, retained without reauthoring the scene geometry. */
	public Map<String, Object> changeObject(
		String op, String campaignRef, String mapRef, String key, Map<String, Object> changes, String reason) {
		long cid = Ref.id(campaignRef, Ref.CAMPAIGN);
		if (key == null || key.isBlank())
			throw RpgException.invalidArgument("Supply an object or passage key from GM map context.");
		if (reason == null || reason.isBlank())
			throw RpgException.invalidArgument("A resolved gameplay effect or audited correction reason is required.");
		Set<String> fields = Set.of("state", "locked", "sealed", "blocks_movement", "blocks_sight", "cover",
				"hit_points", "material", "label", "secret", "concealed", "known_hazard");
		if (!fields.containsAll(changes.keySet()))
			throw RpgException.invalidArgument(
					"Unsupported object changes; movement and geometry require a validated preparation.");
		for (String flag : List.of("secret", "concealed", "known_hazard"))
			if (changes.containsKey(flag) && !(changes.get(flag) instanceof Boolean))
				throw RpgException.invalidArgument(flag + " must be true or false.");
		return db.mutate(Database.Mutation.of("update_map_object", cid, op, "GM",
				Map.of("map", mapRef, "key", key, "changes", changes, "reason", reason)), tx -> {
					Row campaign = Harness.requireMutation(tx, campaignRef, "update_map_object");
					Row s = scene(tx, cid, mapRef);
					MapObjectKey target = resolveObjectKey(tx, s, key);
					String canonicalKey = target.key();
					Optional<Row> found = tx.queryOne("SELECT * FROM map_object WHERE scene_id=? AND object_key=?",
							s.id(), canonicalKey);
					if (found.isPresent()) {
						Row o = found.get();
						var properties = new LinkedHashMap<>(o.map("properties_json"));
						properties.putAll(changes);
						properties.put("last_change_reason", reason);
						tx.update("map_object", o.id(),
								Map.of("properties_json", properties, "revision", o.lng("revision") + 1));
					} else {
						Map<String, Object> document = s.map("document_json");
						var doors = maps(document.get("doors")).stream().filter(d -> canonicalKey.equals(d.get("key")))
								.toList();
						if (doors.size() > 1)
							throw RpgException.invalidArgument("Ambiguous object or passage key " + key);
						String ref = doors.isEmpty() ? null : string(doors.getFirst(), "connection_ref", null);
						if (ref == null) {
							changeInlineObject(tx, s, document, canonicalKey, changes, reason);
						} else {
							Row connection = tx.get("location_connection", Ref.id(ref, Ref.CONNECTION));
							if (connection.lng("campaign_id") != cid)
								throw RpgException.invalidArgument("Passage belongs to another campaign.");
							var state = new LinkedHashMap<>(connection.map("state_json"));
							if (changes.containsKey("state") && !se.hirt.mcp.rpg.world.WorldService.CONNECTION_STATES
									.contains(String.valueOf(changes.get("state"))))
								throw RpgException.invalidArgument("Unsupported passage state.");
							state.putAll(changes);
							state.put("last_change_reason", reason);
							tx.update("location_connection", connection.id(), Map.of("state_json", state));
						}
					}
					tx.update("map_scene", s.id(), Map.of("revision", s.lng("revision") + 1));
					remember(tx, tx.get("map_scene", s.id()));
					return Map.of("map", mapRef, "object", key, "canonical_key", canonicalKey, "feature_kind",
							target.kind(), "changes", changes, "meta", Harness.meta(campaign, List.of()));
				});
	}

	private record MapObjectKey(String key, String kind) {
	}

	/** Exact GM keys take precedence over optional, unique authoring-key aliases. */
	private static MapObjectKey resolveObjectKey(Tx tx, Row scene, String requested) {
		var candidates = new ArrayList<MapObjectKey>();
		for (Row object : tx.query("SELECT object_key FROM map_object WHERE scene_id=? ORDER BY id", scene.id()))
			candidates.add(new MapObjectKey(object.str("object_key"), "OBJECT"));
		Map<String, Object> document = scene.map("document_json");
		for (String layer : List.of("objects", "doors"))
			for (var feature : maps(document.get(layer)))
				if (feature.get("key") instanceof String key)
					candidates.add(new MapObjectKey(key, "doors".equals(layer) ? "PASSAGE" : "OBJECT"));
		List<MapObjectKey> exact = candidates.stream().filter(candidate -> requested.equals(candidate.key())).toList();
		if (exact.size() == 1)
			return exact.getFirst();
		if (exact.size() > 1)
			throw RpgException.invalidArgument("Ambiguous exact object or passage key '" + requested
					+ "'. Multiple saved features use this canonical key; correct their duplicate keys before updating.")
					.withDetail("canonical_keys", exact.stream().map(MapObjectKey::key).distinct().toList());
		List<MapObjectKey> aliases = candidates.stream().filter(candidate -> "OBJECT".equals(candidate.kind()))
				.filter(candidate -> (candidate.key().startsWith("object:") || candidate.key().startsWith("reward:"))
						&& requested.equals(candidate.key().substring(candidate.key().indexOf(':') + 1)))
				.toList();
		if (aliases.size() == 1)
			return aliases.getFirst();
		if (aliases.size() > 1)
			throw RpgException
					.invalidArgument("Ambiguous object alias '" + requested + "'. Use an exact canonical GM key: "
							+ String.join(", ", aliases.stream().map(MapObjectKey::key).distinct().toList()) + ".")
					.withDetail("canonical_keys", aliases.stream().map(MapObjectKey::key).distinct().toList());
		throw RpgException.notFound("Object or passage key " + requested);
	}

	/** Physical-only improvised scenery has no canonical inventory or passage row. */
	private static void changeInlineObject(
		Tx tx, Row scene, Map<String, Object> document, String key, Map<String, Object> changes, String reason) {
		String layer = null;
		for (String candidate : List.of("objects", "doors"))
			for (var entry : maps(document.get(candidate)))
				if (key.equals(entry.get("key"))) {
					if (layer != null)
						throw RpgException.invalidArgument("Ambiguous object or passage key " + key);
					if (entry.containsKey("entity_ref"))
						throw RpgException
								.invalidArgument("Canonical inventory must be changed through its map object.");
					layer = candidate;
				}
		if (layer == null)
			throw RpgException.notFound("Object or passage key " + key);
		if ("doors".equals(layer) && changes.containsKey("state")
				&& !se.hirt.mcp.rpg.world.WorldService.CONNECTION_STATES.contains(String.valueOf(changes.get("state"))))
			throw RpgException.invalidArgument("Unsupported passage state.");
		var entries = new ArrayList<Map<String, Object>>();
		for (var entry : maps(document.get(layer))) {
			var copy = new LinkedHashMap<>(entry);
			if (key.equals(copy.get("key"))) {
				copy.putAll(changes);
				copy.put("last_change_reason", reason);
			}
			entries.add(copy);
		}
		var updated = new LinkedHashMap<>(document);
		updated.put(layer, entries);
		tx.update("map_scene", scene.id(), Map.of("document_json", updated));
	}

	public Map<String, Object> perception(String campaignRef, String mapRef, String observerRef) {
		return db.read(tx -> {
			Row c = Harness.campaign(tx, campaignRef);
			Row s = scene(tx, c.id(), mapRef);
			Row a = actor(tx, c.id(), observerRef);
			if (a.lng("scene_id") != s.id())
				throw RpgException.invalidArgument("Observer is on another map.");
			var result = actorSight(tx, s, a);
			Map<String, Object> memory = tx
					.queryOne("SELECT * FROM map_observation WHERE scene_id=? AND observer_key=?", s.id(), observerRef)
					.map(r -> r.map("knowledge_json")).orElse(Map.of());
			return Map.of("observer", observerRef, "currently_seen", result, "evidence", memory, "guidance",
					"Act from currently_seen and retained evidence. Unaware actors continue their duties; hearing grants an approximate cue, not a target lock.");
		});
	}

	public Map<String, Object> sound(
		String op, String campaignRef, String mapRef, double x, double y, int level, double range, String description) {
		return sound(op, campaignRef, mapRef, x, y, level, range, description, null);
	}

	public Map<String, Object> sound(
		String op, String campaignRef, String mapRef, Double x, Double y, Integer level, double range,
		String description, String actorRef) {
		if (!Double.isFinite(range) || range < 0 || range > 1000 || description == null || description.isBlank())
			throw RpgException.invalidArgument("Supply a sound description and range from 0 to 1000ft.");
		if (actorRef == null && (x == null || y == null || !Double.isFinite(x) || !Double.isFinite(y)))
			throw RpgException.invalidArgument("Supply an actor as the sound source, or both x_ft and y_ft.");
		if (actorRef != null && (x != null || y != null))
			throw RpgException.invalidArgument("Choose an actor sound source or explicit coordinates, not both.");
		long cid = Ref.id(campaignRef, Ref.CAMPAIGN);
		var args = new LinkedHashMap<String, Object>();
		args.put("map", mapRef);
		args.put("x", x);
		args.put("y", y);
		args.put("level", level);
		args.put("actor", actorRef);
		args.put("range", range);
		args.put("description", description);
		return db.mutate(Database.Mutation.of("emit_map_sound", cid, op, "GM", args), tx -> {
			Row c = Harness.requireMutation(tx, campaignRef, "emit_map_sound");
			Row s = scene(tx, cid, mapRef);
			int soundLevel = level == null ? 0 : level;
			Point source;
			if (actorRef != null) {
				Row emitter = actor(tx, cid, actorRef);
				if (emitter.lng("scene_id") != s.id())
					throw RpgException.invalidArgument("Sound source " + actorRef + " is on another map.");
				if (level != null && soundLevel != emitter.intOr("level", 0))
					throw RpgException.invalidArgument("Sound level must match the actor's saved level.");
				soundLevel = emitter.intOr("level", 0);
				source = center(emitter);
			} else
				source = point(x, y);
			Scene g = geometry(document(tx, s), soundLevel);
			var recipients = new ArrayList<String>();
			for (Row a : actors(tx, s.id(), soundLevel)) {
				Row listenerCharacter = tx.get("character", a.lng("character_id"));
				if (actorRef != null && Ref.id(actorRef, Ref.CHARACTER) == listenerCharacter.id()
						|| !"ALIVE".equals(listenerCharacter.str("life_state"))
						|| hasCondition(tx, listenerCharacter.id(), "DEAFENED")
						|| hasCondition(tx, listenerCharacter.id(), "UNCONSCIOUS"))
					continue;
				Point listener = center(a);
				double distance = Math.max(Math.abs(source.x() - listener.x()), Math.abs(source.y() - listener.y()))
						/ 12.0;
				boolean occluded = !SpatialRules.lineOfSight(g, listener, source);
				if (distance > range / (occluded ? 2 : 1))
					continue;
				String ref = Ref.of(Ref.CHARACTER, a.lng("character_id"));
				String direction = (source.y() > listener.y() ? "north" : source.y() < listener.y() ? "south" : "")
						+ (source.x() > listener.x() ? "east" : source.x() < listener.x() ? "west" : "");
				mergeObservation(tx, s, ref,
						Map.of("heard",
								Map.of("description", description, "direction",
										direction.isEmpty() ? "nearby" : direction, "approximate", true),
								"awareness", "SUSPICIOUS"));
				recipients.add(ref);
			}
			return Map.of("heard_by", recipients, "meta", Harness.meta(c, List.of()));
		});
	}

	private static List<Map<String, Object>> actorSight(Tx tx, Row s, Row a) {
		int level = a.intOr("level", 0);
		Map<String, Object> doc = document(tx, s);
		Scene g = geometry(doc, level);
		Vision viewer = vision(tx, a);
		var out = new ArrayList<Map<String, Object>>();
		for (Row b : actors(tx, s.id(), level))
			if (b.id() != a.id()
					&& SpatialRules.sightSamples(footprint(b)).stream().anyMatch(point -> perceives(g, viewer, point,
							illuminationAt(doc, level, point), hasCondition(tx, b.lng("character_id"), "INVISIBLE"))))
				out.add(Map.of("character", Ref.of(Ref.CHARACTER, b.lng("character_id")), "position_ft",
						feet(center(b)), "level", level));
		return out;
	}

	private record Vision(Point position, List<Point> origins, long sight, long darkvision, long blindsight,
			long truesight, boolean blind, boolean conscious) {
	}

	private static Vision vision(Tx tx, Row actor) {
		Row c = tx.get("character", actor.lng("character_id"));
		Map<String, Object> senses = senses(c);
		var conditions = se.hirt.mcp.rpg.rules.Combat.conditionsOf(tx, c);
		return new Vision(center(actor), SpatialRules.sightSamples(footprint(actor)),
				number(senses, "sight_range_ft", 120).longValue() * 12,
				number(senses, "darkvision", number(senses, "darkvision_ft", 0)).longValue() * 12,
				number(senses, "blindsight", 0).longValue() * 12, number(senses, "truesight", 0).longValue() * 12,
				se.hirt.mcp.rpg.rules.Combat.has(conditions, "BLINDED"),
				!"DEAD".equals(c.str("life_state")) && !se.hirt.mcp.rpg.rules.Combat.has(conditions, "UNCONSCIOUS"));
	}

	private static boolean hasCondition(Tx tx, long character, String name) {
		return tx.count("SELECT COUNT(*) FROM active_effect WHERE character_id=? AND condition_ref=?", character,
				"srd5e:condition/" + name.toLowerCase(Locale.ROOT)) > 0;
	}

	private static boolean perceives(
		Scene scene, Vision observer, Point target, SpatialRules.Illumination light, boolean invisible) {
		if (!observer.conscious())
			return false;
		long distance = Math.max(Math.abs(target.x() - observer.position().x()),
				Math.abs(target.y() - observer.position().y()));
		if (distance <= observer.blindsight() && observer.blindsight() > 0)
			return observer.origins().stream().anyMatch(origin -> SpatialRules.lineOfSight(scene, origin, target));
		if (!observer.blind() && distance <= observer.truesight() && observer.truesight() > 0)
			return observer.origins().stream().anyMatch(origin -> SpatialRules.lineOfSight(scene, origin, target));
		return !invisible && !observer.blind() && observer.origins().stream()
				.anyMatch(origin -> SpatialRules.perceive(scene,
						new SpatialRules.Observer(origin, observer.sight(), observer.darkvision()), target, light)
						.visible());
	}

	public static boolean canSee(Tx tx, Row scene, Row observer, Row target) {
		if (observer.lng("scene_id").longValue() != target.lng("scene_id").longValue()
				|| observer.intOr("level", 0) != target.intOr("level", 0))
			return false;
		var doc = document(tx, scene);
		Scene physical = geometry(doc, observer.intOr("level", 0));
		Vision viewer = vision(tx, observer);
		boolean invisible = hasCondition(tx, target.lng("character_id"), "INVISIBLE");
		return SpatialRules.sightSamples(footprint(target)).stream().anyMatch(point -> perceives(physical, viewer,
				point, illuminationAt(doc, observer.intOr("level", 0), point), invisible));
	}

	/**
	 * Resolved ambient light at the target, without inventing carried or propagating light sources.
	 */
	public static SpatialRules.Illumination illuminationAt(Map<String, Object> doc, int level, Point target) {
		String ambient = string(doc, "illumination", "BRIGHT");
		for (var room : maps(doc.get("rooms"))) {
			Polygon shape = polygon(room);
			if (room.containsKey("illumination") && number(room, "level", 0).intValue() == level && shape != null
					&& shape.contains(target)) {
				ambient = string(room, "illumination", ambient);
				break;
			}
		}
		return SpatialRules.Illumination.valueOf(ambient.toUpperCase(Locale.ROOT));
	}

	private static void mergeObservation(Tx tx, Row s, String key, Map<String, Object> changes) {
		Optional<Row> row = tx.queryOne("SELECT * FROM map_observation WHERE scene_id=? AND observer_key=?", s.id(),
				key);
		var memory = new LinkedHashMap<String, Object>(row.map(r -> r.map("knowledge_json")).orElse(Map.of()));
		memory.putAll(changes);
		if (row.isPresent())
			tx.update("map_observation", row.get().id(),
					Map.of("knowledge_json", memory, "revision", row.get().lng("revision") + 1));
		else
			tx.insert("map_observation", Map.of("campaign_id", s.lng("campaign_id"), "scene_id", s.id(), "observer_key",
					key, "knowledge_json", memory));
	}

	public Map<String, Object> preview(
		String campaignRef, String mapRef, String actorRef, double x, double y, String audience) {
		return db.read(tx -> {
			Row c = Harness.campaign(tx, campaignRef);
			Row s = scene(tx, c.id(), mapRef);
			Row a = actor(tx, c.id(), actorRef);
			if (a.lng("scene_id") != s.id())
				throw RpgException.invalidArgument("Actor is on another map.");
			int level = a.intOr("level", 0);
			Map<String, Object> doc = "GM".equalsIgnoreCase(audience) ? document(tx, s)
					: projection(tx, s, "PLAYER", level);
			Footprint start = footprint(a), destination = new Footprint(point(x, y), start.columns(), start.rows());
			List<Footprint> occupied = "GM"
					.equalsIgnoreCase(audience)
							? blockingActors(tx, s.id(), level).stream().filter(other -> other.id() != a.id())
									.map(SceneService::footprint).toList()
							: maps(doc.get("actors")).stream()
									.filter(other -> !actorRef.equals(other.get("character_ref"))
											&& !"DEAD".equals(other.get("life_state")))
									.map(SceneService::footprint).toList();
			var path = SPATIAL.path(geometry(doc, level), start, destination.origin(), occupied, 10000, 6000);
			return Map.of("status", path.status().name(), "cost_ft", path.costInches() / 12.0, "path_ft",
					path.origins().stream().map(SceneService::feet).toList(), "read_only", true);
		});
	}

	public Map<String, Object> move(String op, String campaignRef, String mapRef, String actorRef, double x, double y) {
		long cid = Ref.id(campaignRef, Ref.CAMPAIGN);
		return db.mutate(Database.Mutation.of("move_map_actor", cid, op, "GM",
				Map.of("map", mapRef, "actor", actorRef, "x", x, "y", y)), tx -> {
					Row campaign = Harness.requireMutation(tx, campaignRef, "move_map_actor");
					Row s = scene(tx, cid, mapRef);
					Row a = actor(tx, cid, actorRef);
					if (a.lng("scene_id") != s.id())
						throw RpgException.invalidArgument("Actor is on another map.");
					Footprint f = footprint(a);
					int level = a.intOr("level", 0);
					boolean player = party(tx, cid).contains(a.lng("character_id"));
					Map<String, Object> planning = player ? projection(tx, s, "PLAYER", level) : document(tx, s);
					if (player) {
						planning = new LinkedHashMap<>(planning);
						planning.put("rooms", List
								.of(Map.of("rect_ft", planning.get("bounds_ft"), "level", level, "enclosed", false)));
					}
					List<Footprint> knownOccupants = player
							? maps(planning.get("actors")).stream()
									.filter(p -> !actorRef.equals(p.get("character_ref"))
											&& !"DEAD".equals(p.get("life_state")))
									.map(SceneService::footprint).toList()
							: blockingActors(tx, s.id(), level).stream().filter(o -> o.id() != a.id())
									.map(SceneService::footprint).toList();
					var path = SPATIAL.path(geometry(planning, level), f, point(x, y), knownOccupants, 10000, 6000);
					if (!"FOUND".equals(path.status().name()))
						throw RpgException.invalidArgument("Movement has no traversable path: " + path.status());
					Row character = tx.get("character", a.lng("character_id"));
					int speed = se.hirt.mcp.rpg.rules.Exhaustion.speed(tx, rules, character);
					var conditions = se.hirt.mcp.rpg.rules.Combat.conditionsOf(tx, character);
					if (speed <= 0 || se.hirt.mcp.rpg.rules.Combat.hasAny(conditions,
							se.hirt.mcp.rpg.rules.Combat.SPEED_ZERO_CONDITIONS))
						throw RpgException.notAllowed("This actor cannot move at Speed 0.");
					long cost = 0, seconds = 0;
					boolean interrupted = false;
					String stop = "ARRIVED";
					var traversed = new ArrayList<Point>();
					traversed.add(f.origin());
					for (Point next : path.origins()) {
						if (next.equals(f.origin()))
							continue;
						Scene physical = geometry(document(tx, s), level);
						Footprint destination = new Footprint(next, f.columns(), f.rows());
						List<Footprint> occupied = blockingActors(tx, s.id(), level).stream()
								.filter(o -> o.id() != a.id()).map(SceneService::footprint).toList();
						if (!SpatialRules.transition(physical, f, destination, occupied)) {
							interrupted = true;
							stop = "NEW_OBSTRUCTION";
							remember(tx, s);
							break;
						}
						long step = SpatialRules.movementCost(physical, destination),
								targetSeconds = (long) Math.ceil((cost + step) * 6.0 / (speed * 12.0));
						var tick = se.hirt.mcp.rpg.world.WorldTimeline.advanceSeconds(tx, cid, targetSeconds - seconds,
								"LOCAL_MOVEMENT", true);
						seconds += tick.elapsedSeconds();
						if (tick.interrupted()) {
							interrupted = true;
							stop = "WORLD_EVENT";
							remember(tx, s);
							break;
						}
						Row current = tx.get("map_actor", a.id());
						tx.update("map_actor", a.id(), Map.of("x_ft", next.x() / 12.0, "y_ft", next.y() / 12.0,
								"revision", current.lng("revision") + 1));
						cost += step;
						f = destination;
						traversed.add(next);
						syncLocation(tx, s, tx.get("map_actor", a.id()));
						remember(tx, s);
					}
					return Map.of("cost_ft", cost / 12.0, "elapsed_seconds", seconds, "interrupted", interrupted,
							"stop_reason", stop, "path_ft", traversed.stream().map(SceneService::feet).toList(), "view",
							projection(tx, s, "PLAYER", level), "meta",
							Harness.meta(tx.get("campaign", cid), List.of()));
				});
	}

	public static void syncLocation(Tx tx, Row scene, Row actor) {
		Point position = center(actor);
		int level = actor.intOr("level", 0);
		for (var room : maps(scene.map("document_json").get("rooms"))) {
			if (number(room, "level", 0).intValue() != level)
				continue;
			Polygon shape = polygon(room);
			String ref = string(room, "location_ref", null);
			if (shape == null || ref == null || !shape.contains(position))
				continue;
			long location = Ref.id(ref, Ref.LOCATION);
			Row character = tx.get("character", actor.lng("character_id"));
			if (!Objects.equals(character.lng("location_id"), location))
				tx.update("character", character.id(),
						Map.of("location_id", location, "revision", character.lng("revision") + 1));
			if (tx.count(
					"SELECT COUNT(*) FROM player_control_assignment WHERE campaign_id=? AND character_id=? AND active=1",
					scene.lng("campaign_id"), character.id()) > 0) {
				Row c = tx.get("campaign", scene.lng("campaign_id"));
				if (!Objects.equals(c.lng("current_location_id"), location))
					tx.update("campaign", c.id(),
							Map.of("current_location_id", location, "revision", c.lng("revision") + 1));
			}
			return;
		}
	}

	public static Row actor(Tx tx, long cid, String ref) {
		return tx
				.queryOne("SELECT * FROM map_actor WHERE campaign_id=? AND character_id=?", cid,
						Ref.id(ref, Ref.CHARACTER))
				.orElseThrow(() -> RpgException.invalidArgument("Place " + ref + " on a saved map first."));
	}

	public static List<Row> actors(Tx tx, long sceneId, int level) {
		return tx.query("SELECT * FROM map_actor WHERE scene_id=? AND level=? ORDER BY character_id", sceneId, level);
	}

	/** Corpses remain visible map facts, but are not occupying creatures for movement. */
	public static List<Row> blockingActors(Tx tx, long sceneId, int level) {
		return tx.query(
				"SELECT a.* FROM map_actor a JOIN character c ON c.id=a.character_id "
						+ "WHERE a.scene_id=? AND a.level=? AND c.life_state<>'DEAD' ORDER BY a.character_id",
				sceneId, level);
	}

	public static Footprint footprint(Row a) {
		return new Footprint(point(((Number) a.get("x_ft")).doubleValue(), ((Number) a.get("y_ft")).doubleValue()),
				Math.max(1, (int) Math.ceil(((Number) a.get("footprint_ft")).doubleValue() / 5)),
				Math.max(1, (int) Math.ceil(((Number) a.get("footprint_ft")).doubleValue() / 5)));
	}

	private static Footprint footprint(Map<String, Object> a) {
		int n = Math.max(1, (int) Math.ceil(number(a, "footprint_ft", number(a, "width_ft", 5)).doubleValue() / 5));
		return new Footprint(point(number(a, "x_ft", 0).doubleValue(), number(a, "y_ft", 0).doubleValue()), n, n);
	}

	public static Point center(Row a) {
		Footprint f = footprint(a);
		return f.origin().translate(f.columns() * 30L, f.rows() * 30L);
	}

	public static List<Double> feet(Point p) {
		return List.of(p.x() / 12.0, p.y() / 12.0);
	}

	public static Point point(double x, double y) {
		if (!Double.isFinite(x) || !Double.isFinite(y))
			throw RpgException.invalidArgument("Coordinates must be finite.");
		return new Point(Math.round(x * 12), Math.round(y * 12));
	}

	/** Assemble current authoritative passage/object state before applying audience filtering. */
	public static Map<String, Object> document(Tx tx, Row scene) {
		Map<String, Object> doc = new LinkedHashMap<>("REGION".equals(scene.str("kind"))
				? MapAuthoringService.regionalDocument(tx, scene) : scene.map("document_json"));
		doc.putIfAbsent("illumination", "DUNGEON".equals(scene.str("kind")) ? "DARK" : "BRIGHT");
		for (String layer : List.of("doors", "portals")) {
			var openings = new ArrayList<Map<String, Object>>();
			for (var opening : maps(doc.get(layer))) {
				var copy = new LinkedHashMap<>(opening);
				String ref = string(opening, "connection_ref", null);
				if (ref != null) {
					Row edge = tx.get("location_connection", Ref.id(ref, Ref.CONNECTION));
					if (edge.str("kind") != null)
						copy.put("kind", edge.str("kind"));
					copy.putAll(edge.map("state_json"));
				}
				openings.add(copy);
			}
			doc.put(layer, openings);
		}
		var objects = new ArrayList<Map<String, Object>>();
		for (var authored : maps(doc.get("objects"))) {
			var object = new LinkedHashMap<>(authored);
			if (object.containsKey("x_ft") && object.containsKey("y_ft"))
				object.put("position_ft", List.of(
						number(object, "x_ft", 0).doubleValue() + number(object, "width_ft", 5).doubleValue() / 2,
						number(object, "y_ft", 0).doubleValue() + number(object, "height_ft", 5).doubleValue() / 2));
			objects.add(object);
		}
		for (Row o : tx.query("SELECT * FROM map_object WHERE scene_id=? ORDER BY id", scene.id())) {
			var item = new LinkedHashMap<>(o.map("properties_json"));
			item.putAll(o.map("placement_json"));
			item.put("key", o.str("object_key"));
			if (o.str("entity_ref") != null)
				item.put("entity_ref", o.str("entity_ref"));
			if (item.get("rect_ft") instanceof List<?> rect)
				item.put("position_ft", List.of(n(rect, 0) + n(rect, 2) / 2, n(rect, 1) + n(rect, 3) / 2));
			else if (item.containsKey("x_ft"))
				item.put("position_ft", List.of(
						number(item, "x_ft", 0).doubleValue() + number(item, "width_ft", 5).doubleValue() / 2,
						number(item, "y_ft", 0).doubleValue() + number(item, "height_ft", 5).doubleValue() / 2));
			if (o.str("entity_ref") != null && o.str("entity_ref").startsWith("inventory:")) {
				Optional<Row> entry = tx.find("inventory_entry", Ref.id(o.str("entity_ref"), Ref.INVENTORY));
				if (entry.isEmpty())
					continue;
				Row owner = entry.get();
				var seen = new HashSet<Long>();
				while (!owner.isNull("container_entry_id")) {
					if (!seen.add(owner.id()))
						throw RpgException.invalidArgument("Inventory container cycle.");
					owner = tx.get("inventory_entry", owner.lng("container_entry_id"));
					for (Row container : tx.query("SELECT * FROM map_object WHERE entity_ref=?",
							"inventory:" + owner.id())) {
						var props = container.map("properties_json");
						if (Boolean.TRUE.equals(props.get("locked")) || Boolean.TRUE.equals(props.get("sealed"))
								|| !"OPEN".equals(string(props, "state", "CLOSED")))
							item.put("concealed", true);
					}
				}
				if (!owner.isNull("character_id"))
					continue;
				if (!owner.isNull("location_id") && !o.isNull("room_id")
						&& owner.lng("location_id").longValue() != o.lng("room_id").longValue())
					continue;
			}
			objects.add(item);
		}
		doc.put("objects", objects);
		return doc;
	}

	public static Scene geometry(Map<String, Object> doc, int level) {
		List<?> b = (List<?>) doc.getOrDefault("bounds_ft", List.of(0, 0, 100, 100));
		Point origin = point(n(b, 0), n(b, 1));
		Bounds bounds = new Bounds(origin.x(), origin.y(), origin.x() + Math.round(n(b, 2) * 12),
				origin.y() + Math.round(n(b, 3) * 12));
		var floors = new ArrayList<Polygon>();
		var blocks = new ArrayList<Polygon>();
		var sight = new ArrayList<Polygon>();
		var walls = new ArrayList<Segment>();
		var terrain = new ArrayList<Terrain>();
		List<Map<String, Object>> rooms = maps(doc.get("rooms"));
		for (var room : rooms) {
			if (number(room, "level", 0).intValue() != level)
				continue;
			Polygon p = polygon(room);
			if (p == null)
				continue;
			floors.add(p);
			if (number(room, "movement_multiplier", 1).intValue() > 1)
				terrain.add(new Terrain(p, number(room, "movement_multiplier", 1).intValue()));
		}
		if (floors.isEmpty() && rooms.isEmpty())
			floors.add(Polygon.rectangle(bounds.minX(), bounds.minY(), bounds.maxX(), bounds.maxY()));
		List<Map<String, Object>> doors = maps(doc.get("doors")).stream()
				.filter(d -> number(d, "level", 0).intValue() == level).toList();
		for (var w : maps(doc.get("walls"))) {
			if (number(w, "level", 0).intValue() != level)
				continue;
			Segment seg = segment(w);
			if (seg != null)
				walls.addAll(cut(seg, doors));
		}
		if (!Boolean.FALSE.equals(doc.get("enclose_rooms")) && !rooms.isEmpty()) {
			for (var room : rooms) {
				if (number(room, "level", 0).intValue() != level || Boolean.FALSE.equals(room.get("enclosed")))
					continue;
				Polygon p = polygon(room);
				if (p == null)
					continue;
				for (int i = 0; i < p.shell().size(); i++)
					walls.addAll(cut(new Segment(p.shell().get(i), p.shell().get((i + 1) % p.shell().size())), doors));
			}
		}
		for (var d : doors) {
			String state = string(d, "state", "OPEN");
			if (!Set.of("OPEN", "DESTROYED", "BREACHED").contains(state.toUpperCase(Locale.ROOT))) {
				Segment seg = segment(d);
				if (seg != null)
					walls.add(seg);
			}
		}
		for (var o : maps(doc.get("objects"))) {
			if (number(o, "level", 0).intValue() != level
					|| Set.of("DESTROYED", "REMOVED").contains(string(o, "state", "INTACT")))
				continue;
			if (o.get("observed_rects_ft") instanceof List<?> parts) {
				for (Object part : parts) {
					Polygon observed = polygon(Map.of("rect_ft", part));
					if (Boolean.TRUE.equals(o.get("blocks_movement")))
						blocks.add(observed);
					if (Boolean.TRUE.equals(o.get("blocks_sight")) || "TOTAL".equals(o.get("cover")))
						sight.add(observed);
				}
				continue;
			}
			Polygon p = polygon(o);
			if (p == null && o.containsKey("x_ft"))
				p = Polygon.rectangle(Math.round(number(o, "x_ft", 0).doubleValue() * 12),
						Math.round(number(o, "y_ft", 0).doubleValue() * 12),
						Math.round((number(o, "x_ft", 0).doubleValue() + number(o, "width_ft", 5).doubleValue()) * 12),
						Math.round(
								(number(o, "y_ft", 0).doubleValue() + number(o, "height_ft", 5).doubleValue()) * 12));
			if (p != null) {
				if (Boolean.TRUE.equals(o.get("blocks_movement")))
					blocks.add(p);
				if (Boolean.TRUE.equals(o.get("blocks_sight")) || "TOTAL".equals(o.get("cover")))
					sight.add(p);
			}
		}
		for (var area : maps(doc.get("terrain"))) {
			if (number(area, "level", 0).intValue() != level)
				continue;
			Polygon shape = polygon(area);
			int multiplier = number(area, "movement_multiplier", 1).intValue();
			if (shape != null && multiplier > 1)
				terrain.add(new Terrain(shape, multiplier));
		}
		return new Scene(bounds, floors, blocks, sight, walls, terrain);
	}

	private static List<Segment> cut(Segment wall, List<Map<String, Object>> doors) {
		var pieces = new ArrayList<Segment>();
		pieces.add(wall);
		for (var d : doors) {
			Segment door = segment(d);
			if (door == null)
				continue;
			var next = new ArrayList<Segment>();
			for (Segment p : pieces) {
				long dx = p.b().x() - p.a().x(), dy = p.b().y() - p.a().y();
				if (dx * (door.a().y() - p.a().y()) - dy * (door.a().x() - p.a().x()) != 0
						|| dx * (door.b().y() - p.a().y()) - dy * (door.b().x() - p.a().x()) != 0) {
					next.add(p);
					continue;
				}
				double da = dx != 0 ? (double) (door.a().x() - p.a().x()) / dx
						: (double) (door.a().y() - p.a().y()) / dy;
				double db = dx != 0 ? (double) (door.b().x() - p.a().x()) / dx
						: (double) (door.b().y() - p.a().y()) / dy;
				double lo = Math.max(0, Math.min(da, db)), hi = Math.min(1, Math.max(da, db));
				if (lo >= hi) {
					next.add(p);
					continue;
				}
				if (lo > 0) {
					Point end = new Point(Math.round(p.a().x() + dx * lo), Math.round(p.a().y() + dy * lo));
					if (!p.a().equals(end))
						next.add(new Segment(p.a(), end));
				}
				if (hi < 1) {
					Point start = new Point(Math.round(p.a().x() + dx * hi), Math.round(p.a().y() + dy * hi));
					if (!start.equals(p.b()))
						next.add(new Segment(start, p.b()));
				}
			}
			pieces = next;
		}
		return pieces;
	}

	public static Polygon polygon(Map<String, Object> o) {
		if (o.get("polygon_ft") instanceof List<?> list) {
			List<Point> shell = points(list);
			var holes = new ArrayList<List<Point>>();
			if (o.get("holes_ft") instanceof List<?> h)
				for (Object ring : h)
					holes.add(points((List<?>) ring));
			return new Polygon(shell, holes);
		}
		if (o.get("rect_ft") instanceof List<?> r) {
			double x = n(r, 0), y = n(r, 1);
			var shell = Polygon.rectangle(Math.round(x * 12), Math.round(y * 12), Math.round((x + n(r, 2)) * 12),
					Math.round((y + n(r, 3)) * 12)).shell();
			var holes = new ArrayList<List<Point>>();
			if (o.get("holes_ft") instanceof List<?> rings)
				for (Object ring : rings)
					holes.add(points((List<?>) ring));
			return new Polygon(shell, holes);
		}
		return null;
	}

	private static List<Point> points(List<?> list) {
		return list.stream().map(v -> {
			List<?> p = (List<?>) v;
			return point(n(p, 0), n(p, 1));
		}).toList();
	}

	private static Segment segment(Map<String, Object> o) {
		if (o.get("from_ft") instanceof List<?> a && o.get("to_ft") instanceof List<?> b) {
			Point x = point(n(a, 0), n(a, 1)), y = point(n(b, 0), n(b, 1));
			return x.equals(y) ? null : new Segment(x, y);
		}
		return null;
	}

	private static Set<Long> party(Tx tx, long cid) {
		var ids = new HashSet<Long>();
		for (Row r : tx.query(
				"SELECT character_id FROM party_membership WHERE campaign_id=? AND state IN ('ACTIVE','GUEST','SEPARATED')",
				cid))
			ids.add(r.lng("character_id"));
		for (Row r : tx.query("SELECT character_id FROM player_control_assignment WHERE campaign_id=? AND active=1",
				cid))
			ids.add(r.lng("character_id"));
		return ids;
	}

	private static Set<String> visible(Tx tx, Row s, int level, Map<String, Object> doc) {
		Scene geometry = geometry(doc, level);
		Set<Long> party = party(tx, s.lng("campaign_id"));
		var cells = new TreeSet<String>();
		List<Vision> observers = actors(tx, s.id(), level).stream().filter(a -> party.contains(a.lng("character_id")))
				.map(a -> vision(tx, a)).toList();
		long columns = (geometry.bounds().width() + 59) / 60, rows = (geometry.bounds().height() + 59) / 60;
		if (columns * rows > 40000)
			throw RpgException
					.invalidArgument("Tactical visibility coverage exceeds 40,000 cells; request a bounded scene.");
		for (long x = Math.floorDiv(geometry.bounds().minX(), 60) * 60; x < geometry.bounds().maxX(); x += 60)
			for (long y = Math.floorDiv(geometry.bounds().minY(), 60) * 60; y < geometry.bounds().maxY(); y += 60) {
				Point p = new Point(x + 30, y + 30);
				if (geometry.floors().stream().noneMatch(f -> f.contains(p)))
					continue;
				List<Point> samples = SpatialRules.sightSamples(new Footprint(new Point(x, y), 1, 1));
				for (Vision a : observers) {
					if (samples.stream().anyMatch(
							sample -> perceives(geometry, a, sample, illuminationAt(doc, level, sample), false))) {
						cells.add(level + ":" + Math.floorDiv(x, 60) + ":" + Math.floorDiv(y, 60));
						break;
					}
				}
			}
		return cells;
	}

	private static Map<String, Object> cell(String key, Map<String, Object> doc, Scene physical) {
		String[] p = key.split(":");
		int level = Integer.parseInt(p[0]);
		double x = Long.parseLong(p[1]) * 5.0, y = Long.parseLong(p[2]) * 5.0;
		Point center = point(x + 2.5, y + 2.5);
		var out = new LinkedHashMap<String, Object>();
		out.put("rect_ft", List.of(x, y, 5, 5));
		out.put("level", level);
		out.put("enclosed", false);
		for (var room : maps(doc.get("rooms"))) {
			Polygon shape = polygon(room);
			if (number(room, "level", 0).intValue() == level && shape != null && shape.contains(center)) {
				out.put("material", string(room, "material", "STONE"));
				break;
			}
		}
		var walls = new ArrayList<Map<String, Object>>();
		for (Segment wall : physical.walls()) {
			Segment clip = clip(wall, new Bounds(Math.round(x * 12), Math.round(y * 12), Math.round((x + 5) * 12),
					Math.round((y + 5) * 12)));
			if (clip != null)
				walls.add(wallView(clip));
		}
		out.put("observed_walls", walls);
		var doors = new ArrayList<Map<String, Object>>();
		for (var door : maps(doc.get("doors"))) {
			if (number(door, "level", 0).intValue() != level || "SECRET".equals(door.get("state")))
				continue;
			Segment line = segment(door);
			if (line != null && clip(line, new Bounds(Math.round(x * 12), Math.round(y * 12), Math.round((x + 5) * 12),
					Math.round((y + 5) * 12))) != null) {
				var view = publicObject(door);
				view.put("observed", true);
				Segment observed = clip(line, new Bounds(Math.round(x * 12), Math.round(y * 12),
						Math.round((x + 5) * 12), Math.round((y + 5) * 12)));
				view.put("from_ft", feet(observed.a()));
				view.put("to_ft", feet(observed.b()));
				doors.add(view);
			}
		}
		out.put("observed_doors", doors);
		List<Double> cellBounds = List.of(x, y, 5d, 5d);
		for (String layer : List.of("features", "roads", "rivers"))
			out.put("observed_" + layer,
					TacticalGeography.clip(maps(doc.get(layer)).stream()
							.filter(f -> number(f, "level", 0).intValue() == level
									&& !Boolean.TRUE.equals(f.get("secret")) && !Boolean.TRUE.equals(f.get("hidden"))
									&& !Set.of("GM", "GM_ONLY", "DIRECTOR_ONLY")
											.contains(string(f, "visibility", "PARTY_KNOWN").toUpperCase(Locale.ROOT)))
							.map(SceneService::publicFeature).toList(), cellBounds));
		return out;
	}

	private static Map<String, Object> wallView(Segment wall) {
		return Map.of("from_ft", feet(wall.a()), "to_ft", feet(wall.b()));
	}

	private static Segment clip(Segment line, Bounds b) {
		double dx = line.b().x() - line.a().x(), dy = line.b().y() - line.a().y(), lo = 0, hi = 1;
		double[] p = {-dx, dx, -dy, dy}, q = {line.a().x() - b.minX(), b.maxX() - line.a().x(), line.a().y() - b.minY(),
				b.maxY() - line.a().y()};
		for (int i = 0; i < 4; i++) {
			if (p[i] == 0) {
				if (q[i] < 0)
					return null;
			} else {
				double r = q[i] / p[i];
				if (p[i] < 0)
					lo = Math.max(lo, r);
				else
					hi = Math.min(hi, r);
				if (lo >= hi)
					return null;
			}
		}
		Point a = new Point(Math.round(line.a().x() + lo * dx), Math.round(line.a().y() + lo * dy)),
				z = new Point(Math.round(line.a().x() + hi * dx), Math.round(line.a().y() + hi * dy));
		return a.equals(z) ? null : new Segment(a, z);
	}

	public static void remember(Tx tx, Row s) {
		Map<String, Object> doc = document(tx, s);
		var levels = new TreeSet<Integer>();
		for (Row a : tx.query("SELECT * FROM map_actor WHERE scene_id=?", s.id()))
			levels.add(a.intOr("level", 0));
		Optional<Row> previous = tx.queryOne("SELECT * FROM map_observation WHERE scene_id=? AND observer_key='party'",
				s.id());
		var knowledge = new LinkedHashMap<String, Object>(previous.map(r -> r.map("knowledge_json")).orElse(Map.of()));
		var cells = new LinkedHashMap<String, Object>(map(knowledge.get("cells")));
		var seenObjects = new LinkedHashMap<String, Object>(map(knowledge.get("objects")));
		pruneConcealedObjects(seenObjects, doc);
		for (int level : levels) {
			Set<String> visible = visible(tx, s, level, doc);
			Scene physical = geometry(doc, level);
			for (String k : visible)
				cells.put(k, cell(k, doc, physical));
			refreshObjects(seenObjects, doc, level, visible);
		}
		knowledge.put("cells", cells);
		knowledge.put("objects", seenObjects);
		for (Row a : tx.query("SELECT * FROM map_actor WHERE scene_id=?", s.id())) {
			var seen = actorSight(tx, s, a);
			if (!seen.isEmpty())
				mergeObservation(tx, s, Ref.of(Ref.CHARACTER, a.lng("character_id")),
						Map.of("last_seen", seen, "awareness", "AWARE"));
		}
		if (previous.isPresent())
			tx.update("map_observation", previous.get().id(),
					Map.of("knowledge_json", knowledge, "revision", previous.get().lng("revision") + 1));
		else
			tx.insert("map_observation", Map.of("campaign_id", s.lng("campaign_id"), "scene_id", s.id(), "observer_key",
					"party", "knowledge_json", knowledge));
	}

	public static Map<String, Object> projection(Tx tx, Row s, String audience, int level) {
		if (!Set.of("GM", "PLAYER").contains(audience.toUpperCase(Locale.ROOT)))
			throw RpgException.invalidArgument("Audience must be GM or PLAYER.");
		Map<String, Object> doc = document(tx, s);
		List<Row> actors = actors(tx, s.id(), level);
		Set<Long> party = party(tx, s.lng("campaign_id"));
		Set<String> visible = "REGION".equals(s.str("kind")) ? Set.of() : visible(tx, s, level, doc);
		boolean gm = "GM".equalsIgnoreCase(audience);
		Map<String, Object> out;
		if (gm) {
			out = new LinkedHashMap<>(doc);
			out.put("rooms",
					maps(doc.get("rooms")).stream().filter(r -> number(r, "level", 0).intValue() == level).toList());
			if (!"REGION".equals(s.str("kind")))
				out.put("walls", geometry(doc, level).walls().stream().map(SceneService::wallView).toList());
		} else {
			Map<String, Object> knowledge = tx
					.queryOne("SELECT * FROM map_observation WHERE scene_id=? AND observer_key='party'", s.id())
					.map(r -> r.map("knowledge_json")).orElse(Map.of());
			var knownCells = new LinkedHashMap<String, Object>(map(knowledge.get("cells")));
			Scene physical = "REGION".equals(s.str("kind")) ? null : geometry(doc, level);
			for (String key : visible)
				knownCells.put(key, cell(key, doc, physical));
			out = new LinkedHashMap<>();
			out.put("bounds_ft", doc.getOrDefault("bounds_ft", List.of(0, 0, 100, 100)));
			out.put("enclose_rooms", false);
			var levelCells = knownCells.entrySet().stream().filter(e -> e.getKey().startsWith(level + ":"))
					.map(e -> map(e.getValue())).toList();
			out.put("rooms", levelCells.stream().map(v -> {
				var floor = new LinkedHashMap<>(v);
				floor.remove("observed_walls");
				floor.remove("observed_doors");
				for (String layer : List.of("features", "roads", "rivers"))
					floor.remove("observed_" + layer);
				return floor;
			}).toList());
			out.put("walls",
					levelCells.stream().flatMap(v -> maps(v.get("observed_walls")).stream()).distinct().toList());
			out.put("doors", coalesceObservedDoors(
					levelCells.stream().flatMap(v -> maps(v.get("observed_doors")).stream()).distinct().toList()));
			for (String layer : List.of("features", "roads", "rivers"))
				out.put(layer, levelCells.stream().flatMap(v -> maps(v.get("observed_" + layer)).stream()).distinct()
						.toList());
			var objects = new LinkedHashMap<String, Object>(map(knowledge.get("objects")));
			refreshObjects(objects, doc, level, visible);
			out.put("objects", objects.values().stream().map(SceneService::map)
					.filter(o -> number(o, "level", 0).intValue() == level).map(o -> {
						var view = publicObject(o);
						if (!isVisible(o, level, visible))
							view.put("last_known", true);
						return view;
					}).toList());
			out.put("visibility", Map.of("origin_ft", List.of(0, 0), "visible",
					visible.stream().map(SceneService::cellPosition).toList(), "explored", knownCells.keySet().stream()
							.filter(k -> k.startsWith(level + ":")).map(SceneService::cellPosition).toList()));
			if ("REGION".equals(s.str("kind"))) {
				out.remove("visibility");
				out.put("features", maps(doc.get("features")).stream().filter(f -> !Boolean.TRUE.equals(f.get("secret"))
						&& !Set.of("GM", "GM_ONLY", "DIRECTOR_ONLY").contains(string(f, "visibility", "PARTY_KNOWN")))
						.map(SceneService::publicFeature).toList());
			}
		}
		var markers = new ArrayList<Map<String, Object>>();
		Set<Long> seenActors = new HashSet<>(party);
		if (!gm)
			for (Row observer : actors)
				if (party.contains(observer.lng("character_id")))
					for (var seen : actorSight(tx, s, observer))
						seenActors.add(Ref.id((String) seen.get("character"), Ref.CHARACTER));
		for (Row a : actors) {
			Map<String, Object> p = Map.of("x_ft", a.get("x_ft"), "y_ft", a.get("y_ft"), "level", level);
			if (gm || seenActors.contains(a.lng("character_id"))) {
				Row c = tx.get("character", a.lng("character_id"));
				var marker = new LinkedHashMap<String, Object>();
				marker.putAll(p);
				marker.put("character_ref", Ref.of(Ref.CHARACTER, c.id()));
				marker.put("name", c.str("name"));
				marker.put("life_state", c.str("life_state"));
				marker.put("position_ft", feet(center(a)));
				marker.put("width_ft", a.get("footprint_ft"));
				marker.put("height_ft", a.get("footprint_ft"));
				marker.put("party_member", party.contains(c.id()));
				String side = party.contains(c.id()) ? "PARTY" : "NEUTRAL";
				if (!party.contains(c.id()) && !c.isNull("encounter_id")) {
					Row encounter = tx.get("encounter", c.lng("encounter_id"));
					var sides = encounter.map("sides_json");
					var participant = tx.queryOne(
							"SELECT side FROM encounter_participant WHERE encounter_id=? AND character_id=?",
							encounter.id(), c.id());
					if (participant.isPresent() && se.hirt.mcp.rpg.rules.Combat.hostile(map(sides.get("stances")),
							String.valueOf(sides.get("party_side")), participant.get().str("side")))
						side = "HOSTILE";
				}
				marker.put("side", side);
				markers.add(marker);
			}
		}
		if ("REGION".equals(s.str("kind")))
			markers.addAll(MapAuthoringService.regionalPartyMarkers(tx, s));
		out.put("actors", markers);
		if (!gm && doc.containsKey("annotations"))
			out.put("annotations", maps(doc.get("annotations")).stream().filter(a -> "PARTY".equals(a.get("observer")))
					.map(SceneService::publicFeature).toList());
		out.put("name", s.str("name"));
		out.put("kind", s.str("kind"));
		out.put("level", level);
		out.put("audience", audience.toUpperCase(Locale.ROOT));
		return out;
	}

	/** Joins only the observed intervals of one passage; unseen gaps remain separate. */
	private static List<Map<String, Object>> coalesceObservedDoors(List<Map<String, Object>> fragments) {
		var groups = new LinkedHashMap<Map<String, Object>, List<Segment>>();
		var out = new ArrayList<Map<String, Object>>();
		for (var fragment : fragments) {
			Segment line = segment(fragment);
			if (line == null || string(fragment, "key", "").isBlank()) {
				out.add(fragment);
				continue;
			}
			var metadata = new LinkedHashMap<>(fragment);
			metadata.remove("from_ft");
			metadata.remove("to_ft");
			groups.computeIfAbsent(metadata, ignored -> new ArrayList<>()).add(line);
		}
		for (var group : groups.entrySet()) {
			var remaining = new ArrayList<>(group.getValue());
			while (!remaining.isEmpty()) {
				Segment base = remaining.removeFirst();
				long dx = base.b().x() - base.a().x(), dy = base.b().y() - base.a().y();
				boolean horizontal = Math.abs(dx) >= Math.abs(dy);
				var collinear = new ArrayList<Segment>();
				collinear.add(base);
				for (var iterator = remaining.iterator(); iterator.hasNext();) {
					Segment candidate = iterator.next();
					if ((double) dx * (candidate.a().y() - base.a().y()) == (double) dy
							* (candidate.a().x() - base.a().x())
							&& (double) dx * (candidate.b().y() - base.a().y()) == (double) dy
									* (candidate.b().x() - base.a().x())) {
						collinear.add(candidate);
						iterator.remove();
					}
				}
				var ordered = collinear.stream()
						.map(line -> doorCoordinate(line.a(), horizontal) <= doorCoordinate(line.b(), horizontal) ? line
								: new Segment(line.b(), line.a()))
						.sorted(Comparator.comparingLong(line -> doorCoordinate(line.a(), horizontal))).toList();
				Segment merged = ordered.getFirst();
				for (int i = 1; i < ordered.size(); i++) {
					Segment next = ordered.get(i);
					if (doorCoordinate(next.a(), horizontal) <= doorCoordinate(merged.b(), horizontal)) {
						if (doorCoordinate(next.b(), horizontal) > doorCoordinate(merged.b(), horizontal))
							merged = new Segment(merged.a(), next.b());
					} else {
						out.add(observedDoor(group.getKey(), merged));
						merged = next;
					}
				}
				out.add(observedDoor(group.getKey(), merged));
			}
		}
		return out;
	}

	private static long doorCoordinate(Point point, boolean horizontal) {
		return horizontal ? point.x() : point.y();
	}

	private static Map<String, Object> observedDoor(Map<String, Object> metadata, Segment line) {
		var out = new LinkedHashMap<>(metadata);
		out.put("from_ft", feet(line.a()));
		out.put("to_ft", feet(line.b()));
		return out;
	}

	private static Map<String, Object> publicObject(Map<String, Object> o) {
		var p = new LinkedHashMap<String, Object>();
		for (String k : List.of("key", "name", "label", "kind", "rect_ft", "polygon_ft", "x_ft", "y_ft", "width_ft",
				"height_ft", "position_ft", "level", "state", "material", "blocks_movement", "blocks_sight", "cover",
				"observed_rects_ft", "extent_known"))
			if (o.containsKey(k))
				p.put(k, o.get(k));
		return p;
	}

	public record Transition(int level, Point origin, long costInches) {
	}

	public static Transition resolveTransition(Tx tx, Row map, Row actor, String key) {
		Map<String, Object> portal = maps(map.map("document_json").get("portals")).stream()
				.filter(p -> key.equals(p.get("key"))).findFirst()
				.orElseThrow(() -> RpgException.notFound("Portal " + key));
		int current = actor.intOr("level", 0), from = number(portal, "from_level", 0).intValue(),
				to = number(portal, "to_level", 0).intValue();
		boolean forward = current == from;
		if (from == to && current == from) {
			List<?> fromPosition = (List<?>) portal.get("from_position_ft"),
					toPosition = (List<?>) portal.get("to_position_ft");
			if (fromPosition == null || toPosition == null)
				throw RpgException.invalidArgument("Portal needs both endpoint positions.");
			long fromDistance = SpatialRules.distance(footprint(actor),
					new Footprint(point(n(fromPosition, 0), n(fromPosition, 1)), 1, 1));
			long toDistance = SpatialRules.distance(footprint(actor),
					new Footprint(point(n(toPosition, 0), n(toPosition, 1)), 1, 1));
			if (fromDistance == toDistance) {
				String location = Ref.ofNullable(Ref.LOCATION,
						tx.get("character", actor.lng("character_id")).lng("location_id"));
				if (location != null && location.equals(portal.get("to_location_ref")))
					forward = false;
				else if (location != null && location.equals(portal.get("from_location_ref")))
					forward = true;
				else
					throw RpgException.notAllowed("Move closer to one endpoint of this same-level portal first.");
			} else
				forward = fromDistance < toDistance;
		}
		if (!forward && current != to)
			throw RpgException.notAllowed("The actor is on neither side of this transition.");
		List<?> start = (List<?>) portal.get(forward ? "from_position_ft" : "to_position_ft"),
				end = (List<?>) portal.get(forward ? "to_position_ft" : "from_position_ft");
		if (start == null || end == null)
			throw RpgException.invalidArgument("Portal needs both endpoint positions.");
		if (!SpatialRules.canReach(footprint(actor), new Footprint(point(n(start, 0), n(start, 1)), 1, 1), 60))
			throw RpgException.notAllowed("Move within 5ft of the transition first.");
		String ref = string(portal, "connection_ref", null);
		if (ref != null) {
			Row passage = tx.get("location_connection", Ref.id(ref, Ref.CONNECTION));
			if (passage.lng("campaign_id").longValue() != map.lng("campaign_id").longValue())
				throw RpgException.invalidArgument("Portal belongs to another campaign.");
			if (!Set.of("OPEN", "BREACHED", "DESTROYED").contains(string(passage.map("state_json"), "state", "OPEN")))
				throw RpgException.notAllowed("This passage is closed, locked, blocked or undiscovered.");
		}
		Footprint original = footprint(actor);
		Point destination = point(n(end, 0), n(end, 1));
		int level = forward ? to : from;
		Footprint landing = new Footprint(destination, original.columns(), original.rows());
		List<Row> occupants = blockingActors(tx, map.id(), level).stream().filter(a -> a.id() != actor.id()).toList();
		var placement = SpatialRules.placement(geometry(document(tx, map), level), landing,
				occupants.stream().map(SceneService::footprint).toList());
		if (!placement.valid()) {
			var blockers = occupants.stream()
					.filter(other -> landing.bounds().overlapsInterior(footprint(other).bounds()))
					.map(other -> tx.get("character", other.lng("character_id")).str("name") + " ("
							+ Ref.of(Ref.CHARACTER, other.lng("character_id")) + ")")
					.toList();
			throw RpgException.notAllowed("Transition " + key + " landing at " + feet(destination) + ", level " + level
					+ " on " + map.str("name") + " (" + Ref.of(Ref.MAP, map.id()) + ") cannot fit "
					+ tx.get("character", actor.lng("character_id")).str("name") + ": " + placement
					+ (blockers.isEmpty() ? ". Check the landing floor and physical obstructions." : "; occupied by "
							+ String.join(", ", blockers) + ". Move the occupant before using this transition."));
		}
		long cost = Math.round(number(portal, "movement_ft", 10).doubleValue() * 12);
		if (cost < 60 || cost > 12000)
			throw RpgException.invalidArgument("Transition movement_ft must be between 5 and 1000ft.");
		return new Transition(level, destination, cost);
	}

	public Map<String, Object> transit(
		String op, String campaignRef, String mapRef, String actorRef, String portalKey) {
		long cid = Ref.id(campaignRef, Ref.CAMPAIGN);
		return db.mutate(Database.Mutation.of("use_map_transition", cid, op, "GM",
				Map.of("map", mapRef, "actor", actorRef, "portal", portalKey)), tx -> {
					Row c = Harness.requireMutation(tx, campaignRef, "use_map_transition"), s = scene(tx, cid, mapRef),
							a = actor(tx, cid, actorRef);
					if (a.lng("scene_id") != s.id())
						throw RpgException.invalidArgument("Actor is on another map.");
					Transition target = resolveTransition(tx, s, a, portalKey);
					Row character = tx.get("character", a.lng("character_id"));
					int speed = se.hirt.mcp.rpg.rules.Exhaustion.speed(tx, rules, character);
					if (speed <= 0)
						throw RpgException.notAllowed("The actor cannot move at Speed 0.");
					var tick = se.hirt.mcp.rpg.world.WorldTimeline.advanceSeconds(tx, cid,
							(long) Math.ceil(target.costInches() * 6.0 / (speed * 12.0)), "LOCAL_TRANSITION", true);
					if (!tick.interrupted()) {
						target = resolveTransition(tx, s, a, portalKey);
						tx.update("map_actor", a.id(),
								Map.of("x_ft", target.origin().x() / 12.0, "y_ft", target.origin().y() / 12.0, "level",
										target.level(), "revision", a.lng("revision") + 1));
						syncLocation(tx, s, tx.get("map_actor", a.id()));
						remember(tx, s);
					}
					return Map.of("interrupted", tick.interrupted(), "elapsed_seconds", tick.elapsedSeconds(), "view",
							projection(tx, s, "PLAYER", tick.interrupted() ? a.intOr("level", 0) : target.level()),
							"meta", Harness.meta(tx.get("campaign", cid), List.of()));
				});
	}

	private static Map<String, Object> publicFeature(Map<String, Object> feature) {
		var out = new LinkedHashMap<String, Object>();
		for (String field : List.of("key", "name", "label", "kind", "geometry", "path_ft", "polygon_ft", "holes_ft",
				"point_ft", "position_ft", "width_ft", "width_miles", "description", "text", "source", "confidence",
				"precision", "observed_at", "reported_at", "date", "approximate", "scale", "material"))
			if (feature.containsKey(field))
				out.put(field, feature.get(field));
		if (out.get("geometry") instanceof Map<?, ?> raw) {
			var geometry = new LinkedHashMap<String, Object>();
			for (String key : List.of("type", "coordinates", "holes", "width_ft"))
				if (raw.containsKey(key))
					geometry.put(key, raw.get(key));
			out.put("geometry", geometry);
		}
		if (out.get("source") instanceof Map<?, ?> raw) {
			var source = new LinkedHashMap<String, Object>();
			for (String key : List.of("name", "description", "reported_at", "observed_at", "ref"))
				if (raw.containsKey(key))
					source.put(key, raw.get(key));
			out.put("source", source);
		}
		return out;
	}

	private static void refreshObjects(
		Map<String, Object> memory, Map<String, Object> doc, int level, Set<String> visible) {
		pruneConcealedObjects(memory, doc);
		memory.entrySet().removeIf(e -> isVisible(map(e.getValue()), level, visible));
		for (var o : maps(doc.get("objects")))
			if (isVisible(o, level, visible) && !concealedObject(o)) {
				var observed = publicObject(o);
				Polygon shape = polygon(o);
				if (shape == null && o.containsKey("x_ft")) {
					double ox = number(o, "x_ft", 0).doubleValue(), oy = number(o, "y_ft", 0).doubleValue();
					shape = Polygon.rectangle(Math.round(ox * 12), Math.round(oy * 12),
							Math.round((ox + number(o, "width_ft", 5).doubleValue()) * 12),
							Math.round((oy + number(o, "height_ft", 5).doubleValue()) * 12));
				}
				if (shape != null) {
					Bounds bounds = shape.bounds();
					boolean complete = true;
					for (long x = Math.floorDiv(bounds.minX(), 60); x < Math.ceil(bounds.maxX() / 60.0); x++)
						for (long y = Math.floorDiv(bounds.minY(), 60); y < Math.ceil(bounds.maxY() / 60.0); y++)
							if (!visible.contains(level + ":" + x + ":" + y))
								complete = false;
					if (!complete) {
						var parts = new ArrayList<List<Double>>();
						for (String cell : visible) {
							String[] coordinate = cell.split(":");
							double x = Long.parseLong(coordinate[1]) * 5.0, y = Long.parseLong(coordinate[2]) * 5.0;
							if (shape.contains(point(x + 2.5, y + 2.5)))
								parts.add(List.of(Math.max(x, bounds.minX() / 12.0), Math.max(y, bounds.minY() / 12.0),
										Math.min(x + 5, bounds.maxX() / 12.0) - Math.max(x, bounds.minX() / 12.0),
										Math.min(y + 5, bounds.maxY() / 12.0) - Math.max(y, bounds.minY() / 12.0)));
						}
						observed.remove("polygon_ft");
						observed.remove("rect_ft");
						observed.put("width_ft", 5);
						observed.put("height_ft", 5);
						observed.put("extent_known", false);
						observed.put("observed_rects_ft", parts);
					}
				}
				memory.put(string(o, "key", Json.hash(o)), observed);
			}
	}

	/** Discovery is explicit GM adjudication; sight alone never identifies a concealed trap. */
	private static boolean concealedObject(Map<String, Object> object) {
		if (Boolean.TRUE.equals(object.get("secret")) || Boolean.TRUE.equals(object.get("concealed"))
				|| Set.of("GM", "GM_ONLY", "DIRECTOR_ONLY")
						.contains(string(object, "visibility", "PARTY_KNOWN").toUpperCase(Locale.ROOT)))
			return true;
		return "TRAP".equalsIgnoreCase(string(object, "kind", "")) && !Boolean.TRUE.equals(object.get("known_hazard"))
				&& !Boolean.FALSE.equals(object.get("concealed"));
	}

	/**
	 * Also filter old automatically observed traps and explicitly re-concealed state out of memory.
	 */
	private static void pruneConcealedObjects(Map<String, Object> memory, Map<String, Object> doc) {
		for (var object : maps(doc.get("objects")))
			if (concealedObject(object))
				memory.remove(string(object, "key", Json.hash(object)));
	}

	private static List<Double> cellPosition(String key) {
		String[] a = key.split(":");
		return List.of(Double.parseDouble(a[1]), Double.parseDouble(a[2]));
	}

	private static boolean isVisible(Map<String, Object> o, int level, Set<String> cells) {
		return number(o, "level", 0).intValue() == level
				&& cells.contains(level + ":" + (long) Math.floor(number(o, "x_ft", 0).doubleValue() / 5) + ":"
						+ (long) Math.floor(number(o, "y_ft", 0).doubleValue() / 5));
	}

	@SuppressWarnings("unchecked")
	public static Map<String, Object> map(Object v) {
		return v instanceof Map<?, ?> ? (Map<String, Object>) v : Map.of();
	}

	public static Map<String, Object> senses(Row character) {
		String raw = character.str("senses_json");
		if (raw == null || raw.isBlank())
			return Map.of();
		if (raw.stripLeading().startsWith("{"))
			return Json.readMap(raw);
		var out = new LinkedHashMap<String, Object>();
		for (Object entry : Json.readList(raw)) {
			if (entry instanceof Map<?, ?> m) {
				for (var e : m.entrySet())
					out.put(String.valueOf(e.getKey()).toLowerCase(Locale.ROOT), e.getValue());
			} else {
				var matcher = java.util.regex.Pattern.compile("(?i)(darkvision|blindsight|truesight)\\s*(\\d+)")
						.matcher(String.valueOf(entry));
				while (matcher.find())
					out.put(matcher.group(1).toLowerCase(Locale.ROOT), Integer.parseInt(matcher.group(2)));
			}
		}
		return out;
	}

	public static List<Map<String, Object>> maps(Object v) {
		return v instanceof List<?> list ? list.stream().map(SceneService::map).toList() : List.of();
	}

	public static Number number(Map<String, Object> m, String key, Number fallback) {
		Object v = m.get(key);
		return v instanceof Number n ? n : fallback;
	}

	public static String string(Map<String, Object> m, String key, String fallback) {
		Object v = m.get(key);
		return v == null ? fallback : v.toString();
	}

	/**
	 * Shared cover geometry plus explicit low-cover props that leave ordinary sight unobstructed.
	 */
	public static SpatialRules.CoverResult cover(Tx tx, Row scene, Row observer, Row target) {
		if (observer.intOr("level", 0) != target.intOr("level", 0))
			return new SpatialRules.CoverResult(SpatialRules.Cover.TOTAL, 5, 5);
		SpatialRules.CoverResult best = null;
		for (Point origin : SpatialRules.sightSamples(footprint(observer))) {
			SpatialRules.CoverResult candidate = cover(tx, scene, observer.intOr("level", 0), origin,
					footprint(target));
			if (best == null || candidate.cover().ordinal() < best.cover().ordinal()
					|| candidate.cover() == best.cover() && candidate.blockedSamples() < best.blockedSamples())
				best = candidate;
		}
		return best;
	}

	public static SpatialRules.CoverResult cover(Tx tx, Row scene, int level, Point origin, Footprint target) {
		Map<String, Object> doc = document(tx, scene);
		Scene geometry = geometry(doc, level);
		SpatialRules.CoverResult geometric = SpatialRules.cover(geometry, origin, target);
		SpatialRules.Cover result = geometric.cover();
		if (result == SpatialRules.Cover.TOTAL)
			return geometric;
		for (Map<String, Object> object : maps(doc.get("objects"))) {
			if (number(object, "level", 0).intValue() != level || Set.of("DESTROYED", "REMOVED")
					.contains(string(object, "state", "INTACT").toUpperCase(Locale.ROOT)))
				continue;
			String declared = string(object, "cover", "NONE").toUpperCase(Locale.ROOT);
			SpatialRules.Cover cover = switch (declared) {
			case "HALF", "HALF_COVER" -> SpatialRules.Cover.HALF;
			case "THREE_QUARTERS", "THREE_QUARTERS_COVER" -> SpatialRules.Cover.THREE_QUARTERS;
			case "TOTAL", "TOTAL_COVER" -> SpatialRules.Cover.TOTAL;
			default -> SpatialRules.Cover.NONE;
			};
			if (cover.ordinal() <= result.ordinal())
				continue;
			Polygon shape = polygon(object);
			if (shape == null && object.containsKey("x_ft")) {
				double x = number(object, "x_ft", 0).doubleValue(), y = number(object, "y_ft", 0).doubleValue();
				shape = Polygon.rectangle(Math.round(x * 12), Math.round(y * 12),
						Math.round((x + number(object, "width_ft", 5).doubleValue()) * 12),
						Math.round((y + number(object, "height_ft", 5).doubleValue()) * 12));
			}
			if (shape != null) {
				Scene obstacle = new Scene(geometry.bounds(), geometry.floors(), List.of(), List.of(shape), List.of(),
						List.of());
				if (SpatialRules.cover(obstacle, origin, target).blockedSamples() > 0)
					result = cover;
			}
		}
		return new SpatialRules.CoverResult(result, geometric.blockedSamples(), geometric.totalSamples());
	}

	private static double n(List<?> a, int i) {
		return ((Number) a.get(i)).doubleValue();
	}
}
