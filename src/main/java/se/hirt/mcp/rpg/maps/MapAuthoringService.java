package se.hirt.mcp.rpg.maps;

import se.hirt.mcp.rpg.character.CharacterService;
import se.hirt.mcp.rpg.content.ContentService;
import se.hirt.mcp.rpg.content.ContentService.Item;
import se.hirt.mcp.rpg.content.RulesData;
import se.hirt.mcp.rpg.harness.Harness;
import se.hirt.mcp.rpg.ledger.LedgerService;
import se.hirt.mcp.rpg.persistence.Database;
import se.hirt.mcp.rpg.persistence.Row;
import se.hirt.mcp.rpg.persistence.Tx;
import se.hirt.mcp.rpg.protocol.Json;
import se.hirt.mcp.rpg.protocol.Ref;
import se.hirt.mcp.rpg.protocol.RpgException;
import se.hirt.mcp.rpg.protocol.Violation;
import se.hirt.mcp.rpg.spatial.Geometry;
import se.hirt.mcp.rpg.spatial.SpatialRules;
import se.hirt.mcp.rpg.world.WorldService;

import java.time.Instant;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Model-neutral, journaled authoring. Drafts never create gameplay actors or inventory. */
public final class MapAuthoringService {

	public static final int SCHEMA_VERSION = 1;
	private static final Set<String> KINDS = Set.of("DUNGEON", "SCENE", "REGION");
	private static final Set<String> STAGES = Set.of("BLUEPRINT", "SCENE", "REGION");
	private static final Set<String> KEYED_LISTS = Set.of("rooms", "connections", "rewards", "objects", "actors",
			"features");
	private static final Pattern ADDRESS = Pattern.compile("^atlas:([1-9][0-9]*)\\[(-?[0-9]+),\\s*(-?[0-9]+)]$");
	private static final int MAX_PARTS = 2048;
	private final Database db;
	private final RulesData rules;

	public MapAuthoringService(Database db, RulesData rules) {
		this.db = db;
		this.rules = rules;
	}

	public Map<String, Object> begin(
		String operationId, String campaignRef, String kind, Map<String, Object> proposal) {
		long campaignId = Ref.id(campaignRef, Ref.CAMPAIGN);
		Map<String, Object> p = copy(proposal == null ? Map.of() : proposal);
		String selected = text(kind == null ? p.getOrDefault("kind", "DUNGEON") : kind).toUpperCase(Locale.ROOT);
		if (!KINDS.contains(selected))
			throw RpgException.invalidArgument("kind must be DUNGEON, SCENE or REGION.");
		p.put("kind", selected);
		p.putIfAbsent("stage",
				selected.equals("DUNGEON") ? "BLUEPRINT" : selected.equals("REGION") ? "REGION" : "SCENE");
		p.putIfAbsent("name", selected.equals("REGION") ? "Opening region" : "Prepared site");
		p.putIfAbsent("schema_version", SCHEMA_VERSION);
		if (Boolean.TRUE.equals(p.remove("generate_layout")))
			generateLayout(p);
		quantizeLocal(p);
		bounded(p);
		Map<String, Object> args = map("campaign", campaignRef, "kind", selected, "proposal", p);
		return db.mutate(Database.Mutation.of("begin_map_preparation", campaignId, operationId, "GM", args), tx -> {
			Row campaign = Harness.requireMutation(tx, campaignRef, "begin_map_preparation");
			Map<String, Object> effective = copy(p);
			normalizeChallenges(tx, campaignId, effective, Map.of(), null);
			bounded(effective);
			if (stage(effective).equals("REGION"))
				enrichAtlas(tx, campaignId, effective);
			Long location = effective.get("location_ref") == null ? null
					: scoped(tx, campaignId, "location", text(effective.get("location_ref")), "location").id();
			long id = tx.insert("map_preparation",
					map("campaign_id", campaignId, "location_id", location, "kind", selected, "stage", stage(effective),
							"status", "DRAFT", "name", effective.get("name"), "payload_json", Json.write(effective),
							"source_revisions_json", Json.write(sources(tx, campaignId, effective))));
			tx.touched("preparation:" + id, 0);
			return report(tx, campaign, tx.get("map_preparation", id));
		});
	}

	/** Keyed arrays patch by key; other arrays replace. Accepted commitments cannot be removed. */
	public Map<String, Object> revise(
		String operationId, String campaignRef, String preparationRef, Long expectedRevision,
		Map<String, Object> changes) {
		long campaignId = Ref.id(campaignRef, Ref.CAMPAIGN);
		Map<String, Object> incoming = copy(changes == null ? Map.of() : changes);
		return db.mutate(Database.Mutation.of("revise_map_preparation", campaignId, operationId, "GM",
				map("campaign", campaignRef, "preparation", preparationRef, "expected_revision", expectedRevision,
						"changes", incoming)),
				tx -> {
					Row campaign = Harness.requireMutation(tx, campaignRef, "revise_map_preparation");
					Row prep = preparation(tx, campaignId, preparationRef);
					Harness.requireRevision(prep, preparationRef, expectedRevision);
					if (prep.str("status").equals("PUBLISHED")) {
						throw RpgException.notAllowed(
								"This preparation is published. Use gameplay actions for living changes or an explicit audited correction.");
					}
					Map<String, Object> p = merge(prep.map("payload_json"), incoming);
					markAuthoredChallenges(p, incoming);
					normalizeChallenges(tx, campaignId, p, prep.map("reference_map_json"), prep.id());
					if (Boolean.TRUE.equals(p.remove("generate_layout")))
						generateLayout(p);
					quantizeLocal(p);
					boolean rebase = Boolean.TRUE.equals(p.remove("rebase_sources"));
					if (!Objects.equals(prep.str("kind"), p.get("kind")))
						throw RpgException.invalidArgument("The preparation kind is fixed.");
					List<Violation> contradictions = new ArrayList<>();
					preserves(prep.map("commitments_json"), p, "", contradictions);
					if (!contradictions.isEmpty())
						throw RpgException.validation(contradictions);
					bounded(p);
					Map<String, Object> sourceRevisions = rebase ? new LinkedHashMap<>()
							: new LinkedHashMap<>(prep.map("source_revisions_json"));
					sources(tx, campaignId, p).forEach(sourceRevisions::putIfAbsent);
					tx.update("map_preparation", prep.id(),
							map("payload_json", Json.write(p), "stage", stage(p), "name", p.get("name"),
									"source_revisions_json", Json.write(sourceRevisions), "revision",
									prep.lng("revision") + 1));
					tx.touched(preparationRef, prep.lng("revision") + 1);
					return report(tx, campaign, tx.get("map_preparation", prep.id()));
				});
	}

	public Map<String, Object> validate(String campaignRef, String preparationRef) {
		return db.read(tx -> report(tx, Harness.campaign(tx, campaignRef),
				preparation(tx, Ref.id(campaignRef, Ref.CAMPAIGN), preparationRef)));
	}

	public Map<String, Object> publish(
		String operationId, String campaignRef, String preparationRef, Long expectedRevision) {
		long campaignId = Ref.id(campaignRef, Ref.CAMPAIGN);
		return db.mutate(Database.Mutation.of("publish_map_preparation", campaignId, operationId, "GM",
				map("campaign", campaignRef, "preparation", preparationRef, "expected_revision", expectedRevision)),
				tx -> {
					Row campaign = Harness.requireMutation(tx, campaignRef, "publish_map_preparation");
					Row prep = preparation(tx, campaignId, preparationRef);
					Harness.requireRevision(prep, preparationRef, expectedRevision);
					if (prep.str("status").equals("PUBLISHED"))
						return report(tx, campaign, prep);
					return publishInTx(tx, campaign, prep);
				});
	}

	/** Accepted story commitments must be realized before entry can spend travel time. */
	public static void requireEntryReady(Tx tx, long campaignId, Row destination) {
		Set<Long> ancestors = new HashSet<>();
		Row current = destination;
		while (ancestors.add(current.id()) && !current.isNull("parent_id"))
			current = tx.get("location", current.lng("parent_id"));
		for (Row prep : tx.query(
				"SELECT * FROM map_preparation WHERE campaign_id=? AND status='ACCEPTED' AND published_map_id IS NULL ORDER BY id",
				campaignId)) {
			boolean matches = !prep.isNull("location_id") && ancestors.contains(prep.lng("location_id"));
			if (!matches)
				matches = prep.map("reference_map_json").values().stream()
						.anyMatch(v -> Objects.equals(v, "location:" + destination.id()));
			if (!matches)
				continue;
			Map<String, Object> payload = prep.map("payload_json");
			throw RpgException.notAllowed("The accepted blueprint for " + prep.str("name")
					+ " needs coherent playable geometry before entry. Realize and publish it, then continue the journey.")
					.withDetail("preparation", "preparation:" + prep.id()).withDetail("revision", prep.lng("revision"))
					.withDetail("readiness",
							map("stage", prep.str("stage"), "ready_for_entry", false, "room_count",
									payload.get("room_count"), "levels", payload.get("levels")))
					.withDetail("next_operation", "revise_map_preparation")
					.withDetail("suggested_changes", Map.of("generate_layout", true));
		}
	}

	/** Used by the campaign setup transaction so the region and adventure commit together. */
	public Map<String, Object> publishRegionInTx(Tx tx, long campaignId, Map<String, Object> proposal) {
		Map<String, Object> p = copy(proposal);
		p.put("kind", "REGION");
		p.put("stage", "REGION");
		p.putIfAbsent("schema_version", SCHEMA_VERSION);
		p.putIfAbsent("name", "Opening region");
		bounded(p);
		long id = tx.insert("map_preparation", map("campaign_id", campaignId, "kind", "REGION", "stage", "REGION",
				"name", p.get("name"), "location_id",
				p.get("location_ref") == null ? null
						: scoped(tx, campaignId, "location", text(p.get("location_ref")), "location").id(),
				"payload_json", Json.write(p), "source_revisions_json", Json.write(sources(tx, campaignId, p))));
		return publishInTx(tx, tx.get("campaign", campaignId), tx.get("map_preparation", id));
	}

	private Map<String, Object> publishInTx(Tx tx, Row campaign, Row prep) {
		long campaignId = campaign.id();
		String preparationRef = "preparation:" + prep.id();
		Map<String, Object> p = prep.map("payload_json");
		normalizeChallenges(tx, campaignId, p, prep.map("reference_map_json"), prep.id());
		bounded(p);
		List<Violation> issues = issues(tx, campaignId, prep, p);
		if (!issues.isEmpty())
			throw RpgException.validation(issues);
		checkSources(tx, campaignId, prep.map("source_revisions_json"));
		Map<String, Object> refs = new LinkedHashMap<>(prep.map("reference_map_json"));
		long owner = publishLocations(tx, campaignId, prep, p, refs);
		publishConnections(tx, campaignId, p, refs);
		boolean blueprint = stage(p).equals("BLUEPRINT");
		Long mapId = blueprint ? null : publishScene(tx, campaignId, prep, p, owner, refs);
		publishObjects(tx, campaignId, prep, p, refs, mapId);
		if (!blueprint)
			publishActors(tx, campaignId, prep, p, refs, mapId);
		Map<String, Object> commitments = prep.map("commitments_json");
		if (commitments.isEmpty())
			commitments = commitments(p);
		tx.update("map_preparation", prep.id(),
				map("payload_json", Json.write(p), "location_id", owner, "status", blueprint ? "ACCEPTED" : "PUBLISHED",
						"commitments_json", Json.write(commitments), "reference_map_json", Json.write(refs),
						"published_map_id", mapId, "source_revisions_json", Json.write(sources(tx, campaignId, p)),
						"revision", prep.lng("revision") + 1));
		tx.touched(preparationRef, prep.lng("revision") + 1);
		Map<String, Object> result = report(tx, campaign, tx.get("map_preparation", prep.id()));
		if (mapId != null)
			result.put("map", sceneView(tx, campaignId, tx.get("map_scene", mapId)));
		return result;
	}

	/**
	 * Adds world POIs or knowledge-scoped notes/reports without regenerating the saved geography.
	 */
	public Map<String, Object> annotate(
		String operationId, String campaignRef, String mapRef, Long expectedRevision, Map<String, Object> annotation) {
		long campaignId = Ref.id(campaignRef, "campaign");
		Map<String, Object> input = copy(annotation == null ? Map.of() : annotation);
		return db.mutate(
				Database.Mutation.of("update_map_annotations", campaignId, operationId, "GM", map("campaign",
						campaignRef, "map", mapRef, "expected_revision", expectedRevision, "annotation", input)),
				tx -> {
					Harness.requireMutation(tx, campaignRef, "update_map_annotations");
					Row scene = scoped(tx, campaignId, "map_scene", mapRef, "map");
					Harness.requireRevision(scene, mapRef, expectedRevision);
					String key = text(input.get("key"));
					if (key.isBlank() || key.length() > 120)
						throw RpgException
								.invalidArgument("An annotation needs a stable local key of at most 120 characters.");
					String kind = text(input.getOrDefault("kind", "NOTE")).toUpperCase(Locale.ROOT);
					Map<String, Object> result;
					if (kind.equals("WORLD_POI")) {
						if (scene.isNull("atlas_id"))
							throw RpgException.invalidArgument("WORLD_POI requires a regional atlas map.");
						List<Double> xy = point(input.getOrDefault("position_ft", input.get("point_ft")));
						if (xy == null)
							throw RpgException.invalidArgument(
									"An exact world POI needs position_ft:[east,north] in atlas feet.");
						List<Double> bounds = rectangle(scene.map("document_json").get("bounds_ft"));
						if (bounds == null || !containsPoint(bounds, xy))
							throw RpgException.invalidArgument(
									"POI lies outside this sheet; address the containing sheet instead.");
						Optional<Row> existing = tx.queryOne(
								"SELECT * FROM map_feature WHERE atlas_id=? AND feature_key=?", scene.lng("atlas_id"),
								key);
						Map<String, Object> geometry = map("type", "POINT", "coordinates", xy);
						if (existing.isPresent()) {
							if (!Json.hash(existing.get().map("geometry_json")).equals(Json.hash(geometry)))
								throw RpgException.conflict(
										"That feature already has a different saved position; use an audited correction to move it.");
							result = map("feature_ref", "map_feature:" + existing.get().id(), "created", false);
						} else {
							String locationRef = input.get("location_ref") == null ? null
									: text(input.get("location_ref"));
							if (locationRef != null)
								scoped(tx, campaignId, "location", locationRef, "location");
							if (input.get("create_location") instanceof Map<?, ?> create) {
								if (locationRef != null)
									throw RpgException
											.invalidArgument("Choose an existing location_ref or create_location.");
								Map<String, Object> spec = object(create);
								locationRef = "location:" + createLocation(tx, campaignId, scene.lng("location_id"),
										text(spec.getOrDefault("name", input.getOrDefault("name", key))),
										spec.get("description"), text(spec.getOrDefault("kind", "SITE")), false,
										spec.get("tags"));
							}
							Map<String, Object> props = copy(input);
							props.remove("position_ft");
							props.remove("create_location");
							props.putIfAbsent("visibility", "GM_ONLY");
							long id = tx.insert("map_feature",
									map("campaign_id", campaignId, "atlas_id", scene.lng("atlas_id"), "map_id",
											scene.id(), "feature_key", key, "kind",
											input.getOrDefault("feature_kind", "LANDMARK"), "geometry_json",
											Json.write(geometry), "properties_json", Json.write(props), "graph_ref",
											locationRef));
							Row atlas = tx.get("map_atlas", scene.lng("atlas_id"));
							tx.update("map_atlas", atlas.id(), map("revision", atlas.lng("revision") + 1));
							result = map("feature_ref", "map_feature:" + id, "location_ref", locationRef, "created",
									true);
						}
					} else if (kind.equals("NOTE") || kind.equals("REPORT")) {
						String observer = text(input.getOrDefault("observer", "PARTY"));
						if (!observer.equals("PARTY") && !observer.equals("GM"))
							scoped(tx, campaignId, "character", observer, "character");
						String observerKey = "annotations:" + observer;
						Optional<Row> saved = tx.queryOne(
								"SELECT * FROM map_observation WHERE scene_id=? AND observer_key=?", scene.id(),
								observerKey);
						Map<String, Object> knowledge = saved.map(r -> r.map("knowledge_json"))
								.orElseGet(LinkedHashMap::new);
						List<Map<String, Object>> notes = new ArrayList<>(maps(knowledge.get("annotations")));
						Map<String, Object> old = findRoom(notes, key);
						if (old != null)
							notes.remove(old);
						if (notes.size() >= 512)
							throw RpgException.invalidArgument(
									"This annotation collection is full; use a bounded linked note collection.");
						input.put("kind", kind);
						notes.add(input);
						knowledge.put("annotations", notes);
						if (saved.isPresent())
							tx.update("map_observation", saved.get().id(), map("knowledge_json", Json.write(knowledge),
									"revision", saved.get().lng("revision") + 1));
						else
							tx.insert("map_observation", map("campaign_id", campaignId, "scene_id", scene.id(),
									"observer_key", observerKey, "knowledge_json", Json.write(knowledge)));
						result = map("annotation_key", key, "observer", observer, "created", old == null);
					} else
						throw RpgException.invalidArgument("Annotation kind must be WORLD_POI, NOTE or REPORT.");
					tx.update("map_scene", scene.id(), map("revision", scene.lng("revision") + 1));
					tx.touched(mapRef, scene.lng("revision") + 1);
					result.put("map", sceneView(tx, campaignId, tx.get("map_scene", scene.id())));
					return result;
				});
	}

	/** Authoring context is explicitly GM-only. Player views use KnowledgeProjection. */
	public Map<String, Object> get(String campaignRef, String reference) {
		long campaignId = Ref.id(campaignRef, Ref.CAMPAIGN);
		return db.read(tx -> {
			Row campaign = Harness.campaign(tx, campaignRef);
			Matcher address = ADDRESS.matcher(reference == null ? "" : reference);
			if (address.matches()) {
				long atlasId = Long.parseLong(address.group(1));
				scoped(tx, campaignId, "map_atlas", "atlas:" + atlasId, "atlas");
				Optional<Row> row = tx.queryOne(
						"SELECT * FROM map_scene WHERE campaign_id=? AND atlas_id=? AND sheet_x=? AND sheet_y=?",
						campaignId, atlasId, Long.parseLong(address.group(2)), Long.parseLong(address.group(3)));
				return row.isPresent() ? sceneView(tx, campaignId, row.get())
						: map("address", reference, "generated", false, "audience", "GM");
			}
			Ref ref = Ref.parse(reference);
			return switch (ref.type()) {
			case "preparation" -> report(tx, campaign, preparation(tx, campaignId, reference));
			case "map" -> sceneView(tx, campaignId, scoped(tx, campaignId, "map_scene", reference, "map"));
			case "atlas" -> atlasView(tx, campaignId, scoped(tx, campaignId, "map_atlas", reference, "atlas"));
			case "location" -> {
				Row location = scoped(tx, campaignId, "location", reference, "location");
				Optional<Row> scene = tx.queryOne(
						"SELECT * FROM map_scene WHERE campaign_id=? AND location_id=? ORDER BY id DESC LIMIT 1",
						campaignId, location.id());
				if (scene.isEmpty())
					scene = tx.queryOne(
							"SELECT s.* FROM map_scene s JOIN map_preparation p ON p.id=s.preparation_id WHERE s.campaign_id=? AND EXISTS(SELECT 1 FROM json_each(p.reference_map_json) WHERE value=?) ORDER BY s.id DESC LIMIT 1",
							campaignId, reference);
				if (scene.isEmpty())
					scene = tx.queryOne(
							"SELECT s.* FROM map_scene s JOIN map_feature f ON f.map_id=s.id WHERE s.campaign_id=? AND f.graph_ref=? ORDER BY s.id DESC LIMIT 1",
							campaignId, reference);
				yield scene.isPresent() ? sceneView(tx, campaignId, scene.get())
						: map("location", reference, "generated", false, "audience", "GM");
			}
			default ->
				throw RpgException.invalidArgument("Use a preparation, map, atlas, location or atlas sheet address.");
			};
		});
	}

	public Map<String, Object> find(String campaignRef, Map<String, Object> query) {
		long campaignId = Ref.id(campaignRef, Ref.CAMPAIGN);
		Map<String, Object> q = query == null ? Map.of() : query;
		if (q.get("address") != null)
			return get(campaignRef, text(q.get("address")));
		return db.read(tx -> {
			Harness.campaign(tx, campaignRef);
			List<Map<String, Object>> found = new ArrayList<>();
			String needle = text(q.getOrDefault("text", "")).toLowerCase(Locale.ROOT);
			Long locationId = q.get("location_ref") == null ? null
					: scoped(tx, campaignId, "location", text(q.get("location_ref")), "location").id();
			Long atlasId = q.get("atlas_ref") == null ? null
					: scoped(tx, campaignId, "map_atlas", text(q.get("atlas_ref")), "atlas").id();
			for (Row r : tx.query("SELECT * FROM map_scene WHERE campaign_id=? ORDER BY id LIMIT 1000", campaignId)) {
				if (locationId != null && !Objects.equals(locationId, r.lng("location_id"))
						&& tx.count("SELECT COUNT(*) FROM map_feature WHERE campaign_id=? AND map_id=? AND graph_ref=?",
								campaignId, r.id(), "location:" + locationId) == 0
						&& (r.isNull("preparation_id") || !tx.get("map_preparation", r.lng("preparation_id"))
								.map("reference_map_json").containsValue("location:" + locationId)))
					continue;
				if (atlasId != null && !Objects.equals(atlasId, r.lng("atlas_id")))
					continue;
				if (q.get("kind") != null && !r.str("kind").equalsIgnoreCase(text(q.get("kind"))))
					continue;
				if (!r.str("name").toLowerCase(Locale.ROOT).contains(needle) && tx.count(
						"SELECT COUNT(*) FROM map_feature WHERE campaign_id=? AND map_id=? AND LOWER(COALESCE(json_extract(properties_json,'$.name'),'')) LIKE ?",
						campaignId, r.id(), "%" + needle + "%") == 0)
					continue;
				Map<String, Object> summary = sceneSummary(r);
				summary.put("current_party_board", Objects
						.equals(GridCombat.currentBoard(tx, campaignId).get("map_ref"), Ref.of(Ref.MAP, r.id())));
				summary.put("placed_characters", tx.query(
						"SELECT c.id,c.name FROM map_actor a JOIN character c ON c.id=a.character_id WHERE a.scene_id=? ORDER BY a.id",
						r.id()).stream()
						.map(character -> map("character", Ref.of(Ref.CHARACTER, character.id()), "name",
								character.str("name")))
						.toList());
				List<Map<String, Object>> siblings = publishedRealizations(tx, campaignId, r.lng("location_id"),
						r.id());
				if (!r.isNull("preparation_id") && !siblings.isEmpty()
						&& Set.of("SCENE", "DUNGEON").contains(r.str("kind"))) {
					summary.put("other_published_realizations", siblings);
					summary.put("note",
							"Multiple published boards cover this site. current_party_board and placed_characters show actual use; use correct_map_geometry for illumination or repairs without creating another realization.");
				}
				found.add(summary);
				if (found.size() >= 100)
					break;
			}
			if (q.get("position_ft") instanceof List<?> xy && xy.size() == 2 && atlasId != null) {
				Row atlas = tx.get("map_atlas", atlasId);
				AtlasCoordinates.Sheet sheet = AtlasCoordinates.containing(number(xy.get(0)), number(xy.get(1)),
						number(atlas.get("sheet_width_ft")), number(atlas.get("sheet_height_ft")));
				return map("address", sheet.address(atlasId), "maps",
						found.stream().filter(m -> Objects.equals(m.get("address"), sheet.address(atlasId))).toList(),
						"current_board", GridCombat.currentBoard(tx, campaignId), "audience", "GM");
			}
			return map("maps", found, "preparations", tx.query(
					"SELECT * FROM map_preparation WHERE campaign_id=? AND status<>'PUBLISHED' ORDER BY id LIMIT 100",
					campaignId).stream()
					.map(r -> map("preparation", "preparation:" + r.id(), "name", r.str("name"), "stage",
							r.str("stage"), "status", r.str("status"), "revision", r.lng("revision")))
					.toList(), "current_board", GridCombat.currentBoard(tx, campaignId), "audience", "GM");
		});
	}

	private Map<String, Object> report(Tx tx, Row campaign, Row prep) {
		Map<String, Object> p = prep.map("payload_json");
		List<Violation> issues = issues(tx, campaign.id(), prep, p);
		List<String> optional = new ArrayList<>();
		if (stage(p).equals("BLUEPRINT"))
			optional.add("Tile geometry and incidental detail may be supplied near approach.");
		if (list(p.get("patrols")).isEmpty())
			optional.add("No patrol plan is authored.");
		if ("DUNGEON".equals(p.get("kind")) && !p.containsKey("illumination")
				&& !geometry(p).containsKey("illumination"))
			optional.add("Unlit dungeon space defaults to DARK; author illumination and light sources where present.");
		Map<String, Object> result = map("preparation", "preparation:" + prep.id(), "kind", prep.str("kind"), "stage",
				prep.str("stage"), "status", prep.str("status"), "name", prep.str("name"), "revision",
				prep.lng("revision"), "proposal", p, "commitments", prep.map("commitments_json"), "references",
				prep.map("reference_map_json"), "ready", issues.isEmpty(), "violations",
				issues.stream().map(Violation::toMap).toList(), "required_gaps",
				issues.stream().map(Violation::message).toList(), "optional_gaps", optional, "next_operation",
				issues.isEmpty() ? "publish_map_preparation" : "revise_map_preparation", "audience", "GM", "meta",
				Harness.meta(campaign, null));
		if (Set.of("DRAFT", "ACCEPTED").contains(prep.str("status")))
			result.put("authoring_guidance",
					"Ready means structurally valid, not reviewed or automatically published. Review room challenges, boss/opposition, finite patrols, keys or alternatives, and treasure before explicit publication. Missing challenge difficulties use editable DC 15 guidance; authored values win. Accepted blueprint commitments are pinned. Checks and trap consequences remain DM-adjudicated; these declarations do not trigger or disarm traps.");
		if (!prep.isNull("published_map_id"))
			result.put("map_ref", "map:" + prep.lng("published_map_id"));
		if (Set.of("SCENE", "DUNGEON").contains(prep.str("kind"))) {
			List<Map<String, Object>> existing = publishedRealizations(tx, campaign.id(), prep.lng("location_id"),
					prep.isNull("published_map_id") ? null : prep.lng("published_map_id"));
			if (!existing.isEmpty()) {
				result.put("existing_published_realizations", existing);
				result.put("warnings", List.of(
						"This site already has published tactical coverage. A separate preparation creates separate rooms and occupants unless explicitly bound to existing references. Use correct_map_geometry with illumination or keyed room illumination to light the existing map without duplicating it."));
			}
			result.put("current_board", GridCombat.currentBoard(tx, campaign.id()));
		}
		if (p.containsKey("geometry_precision"))
			result.putAll(map("geometry_precision", p.get("geometry_precision"), "notes", List.of(
					"Local geometry is resolved to a one-inch quantum before validation; proposal coordinates are the accepted values.")));
		return result;
	}

	private static List<Map<String, Object>> publishedRealizations(
		Tx tx, long campaignId, Long locationId, Long exceptMap) {
		if (locationId == null)
			return List.of();
		return tx
				.query("SELECT * FROM map_scene WHERE campaign_id=? AND location_id=? AND preparation_id IS NOT NULL "
						+ "AND kind IN ('SCENE','DUNGEON') ORDER BY id DESC", campaignId, locationId)
				.stream().filter(scene -> exceptMap == null || scene.id() != exceptMap)
				.map(MapAuthoringService::sceneSummary).toList();
	}

	private List<Violation> issues(Tx tx, long campaignId, Row prep, Map<String, Object> p) {
		List<Violation> errors = new ArrayList<>();
		if (Boolean.TRUE.equals(p.get("review_required")))
			errors.add(new Violation("review_required", "LEGACY_REVIEW_REQUIRED",
					"Review sourced legacy graph/narrative constraints and set review_required:false before publication."));
		if (text(p.get("name")).isBlank())
			errors.add(new Violation("name", "REQUIRED", "Name the site or region."));
		String stage = stage(p);
		if (!STAGES.contains(stage))
			errors.add(new Violation("stage", "INVALID", "stage must be BLUEPRINT, SCENE or REGION."));
		boolean blueprint = stage.equals("BLUEPRINT"), region = stage.equals("REGION");
		List<Map<String, Object>> rooms = maps(p.get("rooms"));
		Set<String> keys = unique(rooms, "rooms", errors);
		List<Integer> levels = levels(p);
		int count = integer(p.getOrDefault("room_count", rooms.size()));
		if (!region && (count < 1 || count > MAX_PARTS))
			errors.add(new Violation("room_count", "RANGE", "Specify between 1 and " + MAX_PARTS + " rooms."));
		if (rooms.size() > count)
			errors.add(new Violation("rooms", "ROOM_COUNT",
					"Declared rooms exceed the accepted total; secret rooms count toward that total."));
		if (!blueprint && !region && rooms.size() != count)
			errors.add(new Violation("rooms", "INCOMPLETE",
					"Realize every required room before publishing playable coverage."));
		List<Double> bounds = rectangle(geometry(p).get("bounds_ft"));
		if (!blueprint && bounds == null)
			errors.add(new Violation("geometry.bounds_ft", "REQUIRED",
					"Supply positive physical bounds [x,y,width,height] in feet."));
		for (Map<String, Object> room : rooms) {
			String path = "rooms[" + room.get("key") + "]";
			MapChallenges.validate(room, path, rules, errors);
			if (room.get("level") != null && !levels.contains(integer(room.get("level"))))
				errors.add(new Violation(path + ".level", "UNKNOWN_LEVEL",
						"Room level is absent from the declared floor IDs."));
			if (room.get("location_ref") != null)
				validateScoped(tx, campaignId, "location", room.get("location_ref"), "location", path + ".location_ref",
						errors);
			if (room.get("parent_ref") != null && !keys.contains(text(room.get("parent_ref"))))
				validateScoped(tx, campaignId, "location", room.get("parent_ref"), "location", path + ".parent_ref",
						errors);
			if (!blueprint && !region) {
				Map<String, Object> shape = roomGeometry(p, room);
				List<Double> rect = rectangle(shape.get("rect_ft"));
				List<List<Double>> polygon = points(shape.get("polygon_ft"));
				if (rect == null && polygon.size() < 3)
					errors.add(new Violation(path, "GEOMETRY_REQUIRED",
							"Supply rect_ft or a polygon_ft with at least three points."));
				if (rect != null && bounds != null && !contains(bounds, rect))
					errors.add(new Violation(path + ".rect_ft", "OUT_OF_BOUNDS", "Room extends outside map bounds."));
				if (polygon.size() >= 3)
					validatePolygon(polygon, path + ".polygon_ft", bounds, errors);
				for (Object hole : list(shape.get("holes_ft"))) {
					List<List<Double>> ring = points(hole);
					validatePolygon(ring, path + ".holes_ft", bounds, errors);
					if (ring.stream().anyMatch(xy -> rect != null ? !containsPoint(rect, xy) : !inside(polygon, xy)))
						errors.add(new Violation(path + ".holes_ft", "HOLE_OUTSIDE",
								"A hole must remain inside its room outline."));
				}
			}
		}
		if (!blueprint && !region) {
			for (int i = 0; i < rooms.size(); i++)
				for (int j = i + 1; j < rooms.size(); j++) {
					Map<String, Object> a = rooms.get(i), b = rooms.get(j);
					if (integer(a.getOrDefault("level", levels.get(0))) != integer(
							b.getOrDefault("level", levels.get(0))))
						continue;
					List<Double> ra = rectangle(roomGeometry(p, a).get("rect_ft")),
							rb = rectangle(roomGeometry(p, b).get("rect_ft"));
					if (ra != null && rb != null && overlaps(ra, rb))
						errors.add(new Violation("rooms[" + b.get("key") + "]", "OVERLAP",
								"Room overlaps " + a.get("key") + " on the same floor."));
				}
		}
		List<Map<String, Object>> connections = maps(p.get("connections"));
		unique(connections, "connections", errors);
		for (Map<String, Object> edge : connections) {
			String path = "connections[" + edge.get("key") + "]";
			MapChallenges.validate(edge, path, rules, errors);
			String bound = text(edge.getOrDefault("connection_ref",
					prep.map("reference_map_json").get("connection:" + edge.get("key"))));
			if (!bound.isBlank()) {
				try {
					MapChallenges.validateCanonical(edge,
							scoped(tx, campaignId, "location_connection", bound, "connection").map("state_json"), path,
							errors);
				} catch (RpgException ignored) {
					// Scoped reference validation below reports invalid bindings.
				}
			}
			if (edge.containsKey("travel_minutes") && (!(edge.get("travel_minutes") instanceof Number minutes)
					|| !Double.isFinite(minutes.doubleValue()) || minutes.doubleValue() < 1
					|| minutes.doubleValue() > 525600 || minutes.doubleValue() != Math.rint(minutes.doubleValue())))
				errors.add(new Violation(path + ".travel_minutes", "DURATION_INVALID",
						"travel_minutes must be a whole number from 1 to 525600."));
			String passageState = Boolean.TRUE.equals(edge.get("secret")) ? "SECRET"
					: text(edge.getOrDefault("state", "OPEN")).toUpperCase(Locale.ROOT);
			if (!WorldService.CONNECTION_STATES.contains(passageState))
				errors.add(new Violation(path + ".state", "STATE_INVALID",
						"Passage state must be one of " + WorldService.CONNECTION_STATES + "."));
			if (edge.get("kind") != null
					&& !WorldService.CONNECTION_KINDS.contains(text(edge.get("kind")).toUpperCase(Locale.ROOT)))
				errors.add(new Violation(path + ".kind", "KIND_INVALID",
						"Passage kind must be one of " + WorldService.CONNECTION_KINDS + "."));
			for (String end : List.of("from", "to")) {
				String endpoint = text(edge.get(end));
				if (!keys.contains(endpoint)) {
					if (endpoint.startsWith("location:"))
						validateScoped(tx, campaignId, "location", endpoint, "location", path + "." + end, errors);
					else
						errors.add(new Violation(path + "." + end, "UNKNOWN_ROOM",
								"Reference a declared room key or existing location."));
				}
			}
			if (Objects.equals(edge.get("from"), edge.get("to")))
				errors.add(new Violation(path, "SAME_ENDPOINT", "A passage must connect different rooms."));
			if (edge.get("connection_ref") != null)
				validateScoped(tx, campaignId, "location_connection", edge.get("connection_ref"), "connection",
						path + ".connection_ref", errors);
			if (edge.containsKey("distance_miles")) {
				if (!(edge.get("distance_miles") instanceof Number miles) || !Double.isFinite(miles.doubleValue())
						|| miles.doubleValue() <= 0)
					errors.add(new Violation(path + ".distance_miles", "DISTANCE_INVALID",
							"distance_miles must be a positive finite number."));
				else if (edge.get("connection_ref") != null) {
					try {
						Object established = scoped(tx, campaignId, "location_connection",
								text(edge.get("connection_ref")), "connection").map("distance_json")
								.get("distance_miles");
						if (established instanceof Number old && !close(old.doubleValue(), miles.doubleValue()))
							errors.add(new Violation(path + ".distance_miles", "DISTANCE_CONTRADICTION",
									"Preserve the saved route distance of " + old
											+ " miles; use an audited correction to change established geography."));
					} catch (RpgException ignored) {
						// The scoped reference violation above already explains this invalid edge.
					}
				}
			}
			if (!blueprint && !region && !hasConnectionGeometry(p, edge))
				errors.add(new Violation(path, "OPENING_REQUIRED",
						"Supply a physical doorway/portal or corridor route compatible with the connected rooms."));
		}
		if (!blueprint && !region && errors.isEmpty())
			validatePhysicalConnections(p, errors);
		if (!blueprint && !region && rooms.size() > 1) {
			Set<String> reached = new HashSet<>();
			reached.add(text(rooms.get(0).get("key")));
			boolean changed;
			do {
				changed = false;
				for (Map<String, Object> edge : connections) {
					String a = text(edge.get("from")), b = text(edge.get("to"));
					if (reached.contains(a))
						changed |= reached.add(b);
					if (reached.contains(b))
						changed |= reached.add(a);
				}
			} while (changed);
			if (!reached.containsAll(keys))
				errors.add(new Violation("connections", "DISCONNECTED",
						"Every declared room needs a route through the prepared site (secret/locked routes count)."));
		}
		for (String collection : List.of("rewards", "objects", "actors")) {
			List<Map<String, Object>> parts = maps(p.get(collection));
			unique(parts, collection, errors);
			for (Map<String, Object> part : parts) {
				String path = collection + "[" + part.get("key") + "]";
				if (!collection.equals("actors")) {
					MapChallenges.validate(part, path, rules, errors);
					tx.queryOne("SELECT properties_json FROM map_object WHERE preparation_id=? AND object_key=?",
							prep.id(), (collection.equals("rewards") ? "reward:" : "object:") + part.get("key"))
							.ifPresent(existing -> MapChallenges.validateCanonical(part,
									existing.map("properties_json"), path, errors));
				}
				if (part.get("room") != null && !keys.contains(text(part.get("room")))
						&& !text(part.get("room")).startsWith("location:"))
					errors.add(new Violation(path + ".room", "UNKNOWN_ROOM",
							"Place this in a declared room or existing location."));
				if (collection.equals("rewards") && integer(part.getOrDefault("quantity", 1)) <= 0)
					errors.add(new Violation(path + ".quantity", "RANGE", "Reward quantity must be positive."));
				if (!blueprint && collection.equals("rewards") && part.get("item_ref") == null
						&& part.get("entity_ref") == null)
					errors.add(new Violation(path + ".item_ref", "ITEM_REQUIRED",
							"Resolve the reward's item mechanics through installed/custom content before playable publication."));
				if (part.get("item_ref") != null) {
					try {
						ContentService.resolveItem(tx, rules, campaignId, text(part.get("item_ref")));
					} catch (RpgException e) {
						errors.add(new Violation(path + ".item_ref", "ITEM_INVALID", e.getMessage()));
					}
				}
				if (part.get("character_ref") != null)
					validateScoped(tx, campaignId, "character", part.get("character_ref"), "character",
							path + ".character_ref", errors);
				if (!blueprint && collection.equals("actors") && part.get("character_ref") == null
						&& rules.resolve("CREATURE", text(part.get("creature_ref"))).isEmpty())
					errors.add(new Violation(path + ".creature_ref", "CREATURE_REQUIRED",
							"Resolve this actor to an existing character or installed creature definition."));
				if (!blueprint && bounds != null && !collection.equals("rewards")) {
					List<Double> xy = position(p, part);
					if (xy == null || !containsPoint(bounds, xy))
						errors.add(new Violation(path + ".position_ft", "PLACEMENT_REQUIRED",
								"Supply a valid position or a room with realized geometry."));
				}
			}
		}
		preserves(prep.map("commitments_json"), p, "", errors);
		if (region)
			validateRegion(tx, campaignId, p, errors);
		return errors;
	}

	private long publishLocations(Tx tx, long campaignId, Row prep, Map<String, Object> p, Map<String, Object> refs) {
		long owner;
		if (refs.get("site") != null)
			owner = Ref.id(text(refs.get("site")), "location");
		else if (!prep.isNull("location_id"))
			owner = prep.lng("location_id");
		else {
			Long parent = p.get("parent_ref") == null ? null
					: scoped(tx, campaignId, "location", text(p.get("parent_ref")), "location").id();
			owner = createLocation(tx, campaignId, parent, text(p.get("name")), p.get("description"),
					stage(p).equals("REGION") ? "REGION" : "SITE", false, p.get("attributes"));
		}
		refs.put("site", "location:" + owner);
		boolean detailed = stage(p).equals("SCENE");
		for (Map<String, Object> room : maps(p.get("rooms"))) {
			String key = text(room.get("key")), refKey = "room:" + key;
			long id;
			if (refs.get(refKey) != null)
				id = scoped(tx, campaignId, "location", text(refs.get(refKey)), "location").id();
			else if (room.get("location_ref") != null)
				id = scoped(tx, campaignId, "location", text(room.get("location_ref")), "location").id();
			else
				id = createLocation(tx, campaignId, owner, text(room.getOrDefault("name", key)),
						room.get("description"), text(room.getOrDefault("location_kind", "AREA")), detailed,
						room.get("attributes"));
			if (stage(p).equals("REGION")) {
				Row row = tx.get("location", id);
				if (row.isNull("parent_id") && id != owner) {
					tx.update("location", id, map("parent_id", owner, "revision", row.lng("revision") + 1));
					tx.touched("location:" + id, row.lng("revision") + 1);
				}
			}
			if (detailed) {
				Row row = tx.get("location", id);
				if (!row.str("materialization").equals("MATERIALIZED")) {
					tx.update("location", id,
							map("materialization", "MATERIALIZED", "revision", row.lng("revision") + 1));
					tx.touched("location:" + id, row.lng("revision") + 1);
				}
			}
			refs.put(refKey, "location:" + id);
		}
		// Resolve parents after all room identities exist, so a child can precede its parent.
		for (Map<String, Object> room : maps(p.get("rooms"))) {
			if (room.get("parent_ref") == null)
				continue;
			long id = roomId(tx, campaignId, refs, text(room.get("key"))),
					parent = roomId(tx, campaignId, refs, text(room.get("parent_ref")));
			Row row = tx.get("location", id);
			if (Objects.equals(row.lng("parent_id"), parent))
				continue;
			if (WorldService.lineage(tx, parent).contains(id))
				throw RpgException.invalidArgument("Parent of " + row.str("name") + " would create a location cycle.");
			Long previous = row.lng("parent_id");
			if (previous != null && previous != owner && !"REGION".equals(tx.get("location", previous).str("kind")))
				throw RpgException.conflict("The authored parent contradicts the established parent of "
						+ row.str("name") + "; use an audited geography correction.");
			if (previous != null && !WorldService.lineage(tx, parent).contains(previous))
				throw RpgException.conflict("The authored parent moves " + row.str("name")
						+ " outside its established region; use an audited geography correction.");
			tx.update("location", id, map("parent_id", parent, "revision", row.lng("revision") + 1));
			tx.touched("location:" + id, row.lng("revision") + 1);
		}
		return owner;
	}

	private static long createLocation(
		Tx tx, long campaignId, Long parent, String name, Object description, String kind, boolean detailed,
		Object attributes) {
		Set<String> kinds = Set.of("REGION", "SETTLEMENT", "DISTRICT", "SITE", "BUILDING", "AREA");
		if (!kinds.contains(kind))
			throw RpgException.invalidArgument("Unsupported location_kind " + kind);
		long id = tx.insert("location",
				map("campaign_id", campaignId, "parent_id", parent, "kind", kind, "name", name, "description",
						description, "materialization", detailed ? "MATERIALIZED" : "SEMANTIC", "features_json", "[]",
						"tags_json", Json.write(attributes instanceof List<?> ? attributes : List.of())));
		tx.touched("location:" + id, 0);
		return id;
	}

	private void publishConnections(Tx tx, long campaignId, Map<String, Object> p, Map<String, Object> refs) {
		for (Map<String, Object> edge : maps(p.get("connections"))) {
			String key = "connection:" + edge.get("key");
			long a = roomId(tx, campaignId, refs, text(edge.get("from"))),
					b = roomId(tx, campaignId, refs, text(edge.get("to")));
			if (refs.containsKey(key)) {
				persistChallenges(tx, scoped(tx, campaignId, "location_connection", text(refs.get(key)), "connection"),
						edge);
				continue;
			}
			if (edge.get("connection_ref") != null) {
				Row existing = scoped(tx, campaignId, "location_connection", text(edge.get("connection_ref")),
						"connection");
				if (existing.lng("location_a_id") != Math.min(a, b) || existing.lng("location_b_id") != Math.max(a, b))
					throw RpgException.invalidArgument("Existing passage endpoints do not match " + edge.get("key"));
				refs.put(key, "connection:" + existing.id());
				persistChallenges(tx, existing, edge);
				Map<String, Object> distance = new LinkedHashMap<>(existing.map("distance_json"));
				if (edge.containsKey("travel_minutes"))
					distance.put("travel_minutes", edge.get("travel_minutes"));
				if (edge.containsKey("distance_miles"))
					distance.putIfAbsent("distance_miles", edge.get("distance_miles"));
				if (!Json.hash(distance).equals(Json.hash(existing.map("distance_json"))))
					tx.update("location_connection", existing.id(), map("distance_json", Json.write(distance)));
				continue;
			}
			String state = Boolean.TRUE.equals(edge.get("secret")) ? "SECRET"
					: text(edge.getOrDefault("state", "OPEN")).toUpperCase(Locale.ROOT);
			if (!WorldService.CONNECTION_STATES.contains(state))
				throw RpgException.invalidArgument("Unsupported passage state " + state);
			Map<String, Object> distance = map("travel_minutes", edge.getOrDefault("travel_minutes", 1));
			if (edge.get("distance_miles") != null)
				distance.put("distance_miles", edge.get("distance_miles"));
			Map<String, Object> stateProperties = map("state", state, "visibility",
					edge.getOrDefault("visibility", "GM_ONLY"));
			if (edge.containsKey("challenges"))
				stateProperties.put("challenges", edge.get("challenges"));
			long id = tx.insert("location_connection",
					map("campaign_id", campaignId, "location_a_id", Math.min(a, b), "location_b_id", Math.max(a, b),
							"kind", edge.getOrDefault("kind", "PASSAGE"), "state_json", Json.write(stateProperties),
							"distance_json", Json.write(distance)));
			refs.put(key, "connection:" + id);
		}
	}

	private static void persistChallenges(Tx tx, Row connection, Map<String, Object> edge) {
		if (!edge.containsKey("challenges"))
			return;
		Map<String, Object> state = new LinkedHashMap<>(connection.map("state_json"));
		if (Json.hash(state.get("challenges")).equals(Json.hash(edge.get("challenges"))))
			return;
		state.put("challenges", edge.get("challenges"));
		tx.update("location_connection", connection.id(), map("state_json", Json.write(state)));
	}

	private static void normalizeChallenges(
		Tx tx, long campaignId, Map<String, Object> proposal, Map<String, Object> refs, Long preparationId) {
		for (Map<String, Object> edge : maps(proposal.get("connections"))) {
			Map<String, Object> canonical = Map.of();
			String bound = text(edge.getOrDefault("connection_ref", refs.get("connection:" + edge.get("key"))));
			if (!bound.isBlank()) {
				Row existing = scoped(tx, campaignId, "location_connection", bound, "connection");
				canonical = existing.map("state_json");
			}
			MapChallenges.normalize(edge, canonical);
		}
		for (Map<String, Object> room : maps(proposal.get("rooms"))) {
			var identities = new HashSet<Object>();
			identities.add(room.get("key"));
			if (room.get("location_ref") != null)
				identities.add(room.get("location_ref"));
			if (refs.get("room:" + room.get("key")) != null)
				identities.add(refs.get("room:" + room.get("key")));
			boolean entranceDiscovery = maps(proposal.get("connections")).stream()
					.filter(edge -> identities.contains(edge.get("from")) || identities.contains(edge.get("to")))
					.anyMatch(edge -> (Boolean.TRUE.equals(edge.get("secret"))
							|| "SECRET".equalsIgnoreCase(text(edge.get("state"))))
							&& object(edge.get("challenges")).containsKey("discover"));
			MapChallenges.normalize(room, Map.of(), !entranceDiscovery);
		}
		for (String collection : List.of("objects", "rewards"))
			for (Map<String, Object> part : maps(proposal.get(collection))) {
				Map<String, Object> canonical = preparationId == null ? Map.of()
						: tx.queryOne("SELECT properties_json FROM map_object WHERE preparation_id=? AND object_key=?",
								preparationId, (collection.equals("rewards") ? "reward:" : "object:") + part.get("key"))
								.map(existing -> existing.map("properties_json")).orElse(Map.of());
				MapChallenges.normalize(part, canonical);
			}
	}

	private static void markAuthoredChallenges(Map<String, Object> proposal, Map<String, Object> incoming) {
		for (String collection : List.of("rooms", "connections", "objects", "rewards"))
			for (Map<String, Object> patch : maps(incoming.get(collection)))
				if (patch.get("challenges") instanceof Map<?, ?>) {
					var authored = new HashSet<>(object(patch.get("challenges")).keySet());
					if (patch.get("generated_challenges") instanceof List<?> generated)
						generated.forEach(key -> authored.remove(String.valueOf(key)));
					maps(proposal.get(collection)).stream()
							.filter(part -> Objects.equals(part.get("key"), patch.get("key")))
							.forEach(part -> MapChallenges.markAuthored(part, authored));
				}
	}

	private long publishScene(
		Tx tx, long campaignId, Row prep, Map<String, Object> p, long owner, Map<String, Object> refs) {
		Map<String, Object> document = document(p, refs);
		Long atlasId = null;
		Integer sheetX = null, sheetY = null;
		if (stage(p).equals("REGION")) {
			atlasId = publishAtlas(tx, campaignId, p);
			sheetX = integer(p.getOrDefault("sheet_x", 0));
			sheetY = integer(p.getOrDefault("sheet_y", 0));
			if (tx.queryOne("SELECT id FROM map_scene WHERE atlas_id=? AND sheet_x=? AND sheet_y=?", atlasId, sheetX,
					sheetY).isPresent())
				throw RpgException
						.conflict("That atlas sheet is already published; reuse it rather than regenerating it.");
			Row atlas = tx.get("map_atlas", atlasId);
			document.put("bounds_ft", AtlasCoordinates.bounds(sheetX, sheetY, number(atlas.get("sheet_width_ft")),
					number(atlas.get("sheet_height_ft"))));
			document.put("atlas_ref", "atlas:" + atlasId);
			document.put("address", new AtlasCoordinates.Sheet(sheetX, sheetY).address(atlasId));
			document.put("style", atlas.map("style_json"));
			document.put("climate", currentClimate(tx, campaignId,
					asObject(p.getOrDefault("climate", atlas.map("climate_json")), "baseline")));
			document.put("geographic_origin",
					AtlasCoordinates.geographic(number(((List<?>) document.get("bounds_ft")).get(0)),
							number(((List<?>) document.get("bounds_ft")).get(1)), number(atlas.get("origin_latitude")),
							number(atlas.get("origin_longitude")), number(atlas.get("world_radius_m"))));
			refs.put("atlas", "atlas:" + atlasId);
		}
		long id = tx.insert("map_scene",
				map("campaign_id", campaignId, "location_id", owner, "preparation_id", prep.id(), "atlas_id", atlasId,
						"sheet_x", sheetX, "sheet_y", sheetY, "name", p.get("name"), "kind", prep.str("kind"), "level",
						p.getOrDefault("level", levels(p).get(0)), "document_json", Json.write(document)));
		refs.put("map", "map:" + id);
		if (atlasId != null)
			publishFeatures(tx, campaignId, atlasId, id, p, refs);
		tx.touched("map:" + id, 0);
		return id;
	}

	private long publishAtlas(Tx tx, long campaignId, Map<String, Object> p) {
		if (p.get("atlas_ref") != null)
			return scoped(tx, campaignId, "map_atlas", text(p.get("atlas_ref")), "atlas").id();
		Map<String, Object> atlas = object(p.get("atlas"));
		List<Double> bounds = rectangle(geometry(p).get("bounds_ft"));
		double width = number(atlas.getOrDefault("sheet_width_ft", bounds == null ? 528000 : bounds.get(2)));
		double height = number(atlas.getOrDefault("sheet_height_ft", bounds == null ? 528000 : bounds.get(3)));
		long id = tx
				.insert("map_atlas",
						map("campaign_id", campaignId, "name", atlas.getOrDefault("name", p.get("name")),
								"sheet_width_ft", width, "sheet_height_ft", height, "origin_latitude",
								atlas.getOrDefault("origin_latitude", 43.5), "origin_longitude",
								atlas.getOrDefault("origin_longitude", 0), "world_radius_m",
								atlas.getOrDefault("world_radius_m", 6371000), "style_json",
								Json.write(asObject(
										p.getOrDefault("style",
												atlas.getOrDefault("style", Map.of("profile", "PARCHMENT"))),
										"profile")),
								"climate_json",
								Json.write(asObject(
										p.getOrDefault("climate",
												atlas.getOrDefault("climate", Map.of("baseline", "TEMPERATE"))),
										"baseline"))));
		tx.touched("atlas:" + id, 0);
		return id;
	}

	private void publishFeatures(
		Tx tx, long campaignId, long atlasId, long mapId, Map<String, Object> p, Map<String, Object> refs) {
		List<Map<String, Object>> authored = new ArrayList<>(
				maps(p.get("features")).stream().filter(f -> !draftGeometry(f, p)).toList());
		Set<String> represented = new HashSet<>();
		for (var feature : authored)
			represented.add(graphRef(feature, refs));
		for (var room : maps(p.get("rooms"))) {
			String location = text(refs.get("room:" + room.get("key")));
			List<Double> xy = point(roomGeometry(p, room).get("point_ft"));
			if (xy == null)
				xy = center(p, room);
			if (draftGeometry(room, p) || xy == null || !represented.add(location))
				continue;
			Map<String, Object> feature = copy(room);
			for (String field : List.of("geometry", "polygon_ft", "path_ft", "rect_ft"))
				feature.remove(field);
			feature.putAll(map("key", "place-" + Ref.id(location, "location"), "graph_ref", location, "point_ft", xy,
					"kind",
					room.getOrDefault("location_kind", tx.get("location", Ref.id(location, "location")).str("kind"))));
			feature.putIfAbsent("name", tx.get("location", Ref.id(location, "location")).str("name"));
			authored.add(feature);
		}
		for (Map<String, Object> feature : authored) {
			String key = text(feature.get("key"));
			Map<String, Object> geometry = featureGeometry(feature);
			String graphRef = inferredGraphRef(tx, campaignId, p, feature, refs);
			Map<String, Object> properties = copy(feature);
			for (String structural : List.of("key", "kind", "geometry", "path_ft", "polygon_ft", "point_ft",
					"graph_ref", "extend_feature"))
				properties.remove(structural);
			for (Map<String, Object> edge : maps(p.get("connections"))) {
				if (Objects.equals(refs.get("connection:" + edge.get("key")), graphRef)) {
					Object from = refs.getOrDefault("room:" + edge.get("from"), edge.get("from")),
							to = refs.getOrDefault("room:" + edge.get("to"), edge.get("to"));
					if (feature.get("graph_ref") == null) {
						properties.put("graph_association_source", refs.containsKey("connection:" + key)
								? "MATCHING_LOCAL_CONNECTION_KEY" : "UNAMBIGUOUS_AUTHORED_ENDPOINTS");
						List<List<Double>> points = points(geometry.get("coordinates"));
						if (!points.isEmpty()
								&& routeEndpoint(tx, p, refs, Ref.id(text(to), "location"), points.getFirst())
								&& !routeEndpoint(tx, p, refs, Ref.id(text(from), "location"), points.getFirst())) {
							Object swap = from;
							from = to;
							to = swap;
						}
					}
					properties.put("from_location_ref", from);
					properties.put("to_location_ref", to);
				}
			}
			Optional<Row> existing = tx.queryOne("SELECT * FROM map_feature WHERE atlas_id=? AND feature_key=?",
					atlasId, key);
			if (existing.isPresent()) {
				if (!Json.hash(existing.get().map("geometry_json")).equals(Json.hash(geometry))) {
					if (!Boolean.TRUE.equals(feature.get("extend_feature"))
							|| !validPathExtension(existing.get().map("geometry_json"), geometry,
									rectangle(geometry(p).get("bounds_ft"))))
						throw RpgException.conflict("Feature " + key
								+ " already exists with different saved geometry. An explicit path extension must preserve every old vertex and add detail only within the new sheet.");
					tx.update("map_feature", existing.get().id(),
							map("geometry_json", Json.write(geometry), "revision", existing.get().lng("revision") + 1));
					for (Row affected : tx.query("SELECT id,revision FROM map_scene WHERE campaign_id=? AND atlas_id=?",
							campaignId, atlasId))
						tx.update("map_scene", affected.id(), map("revision", affected.lng("revision") + 1));
				}
				refs.put("feature:" + key, "map_feature:" + existing.get().id());
				continue;
			}
			long id = tx.insert("map_feature",
					map("campaign_id", campaignId, "atlas_id", atlasId, "map_id", mapId, "feature_key", key, "kind",
							feature.getOrDefault("kind", "LANDMARK"), "geometry_json", Json.write(geometry),
							"properties_json", Json.write(properties), "graph_ref", graphRef));
			refs.put("feature:" + key, "map_feature:" + id);
		}
		Row atlas = tx.get("map_atlas", atlasId);
		tx.update("map_atlas", atlasId, map("revision", atlas.lng("revision") + 1));
	}

	private static String graphRef(Map<String, Object> feature, Map<String, Object> refs) {
		String ref = feature.get("graph_ref") == null ? null : text(feature.get("graph_ref"));
		if (ref != null && refs.containsKey("room:" + ref))
			return text(refs.get("room:" + ref));
		return ref != null && refs.containsKey("connection:" + ref) ? text(refs.get("connection:" + ref)) : ref;
	}

	/**
	 * Bind an authored road only when its declared key or endpoint places identify one saved route.
	 */
	private static String inferredGraphRef(
		Tx tx, long cid, Map<String, Object> proposal, Map<String, Object> feature, Map<String, Object> refs) {
		String explicit = graphRef(feature, refs);
		if (explicit != null || !Set.of("ROAD", "ROUTE", "PATH", "STREET", "TRAIL", "SEA_ROUTE")
				.contains(text(feature.get("kind")).toUpperCase(Locale.ROOT)))
			return explicit;
		List<List<Double>> path = points(featureGeometry(feature).get("coordinates"));
		if (path.size() < 2)
			return null;
		if (refs.containsKey("connection:" + feature.get("key")))
			return text(refs.get("connection:" + feature.get("key")));
		Set<String> matches = new LinkedHashSet<>();
		for (Map<String, Object> edge : maps(proposal.get("connections"))) {
			long from = roomId(tx, cid, refs, text(edge.get("from"))), to = roomId(tx, cid, refs, text(edge.get("to")));
			if (routeEndpoint(tx, proposal, refs, from, path.getFirst())
					&& routeEndpoint(tx, proposal, refs, to, path.getLast())
					|| routeEndpoint(tx, proposal, refs, to, path.getFirst())
							&& routeEndpoint(tx, proposal, refs, from, path.getLast()))
				matches.add(text(refs.get("connection:" + edge.get("key"))));
		}
		return matches.size() == 1 ? matches.iterator().next() : null;
	}

	private static boolean routeEndpoint(
		Tx tx, Map<String, Object> proposal, Map<String, Object> refs, long location, List<Double> endpoint) {
		List<Long> ancestors = WorldService.lineage(tx, location);
		for (Map<String, Object> room : maps(proposal.get("rooms"))) {
			if (draftGeometry(room, proposal))
				continue;
			List<Double> point = point(roomGeometry(proposal, room).get("point_ft"));
			String ref = text(refs.get("room:" + room.get("key")));
			if (point != null && sameNumbers(point, endpoint) && ancestors.contains(Ref.id(ref, "location")))
				return true;
		}
		return false;
	}

	/** Source-only setup positions are authoring hints, never published world geometry. */
	private static boolean draftGeometry(Map<String, Object> item, Map<String, Object> proposal) {
		String precision = text(item.get("coordinate_precision"));
		if (!precision.isBlank())
			return "DRAFT_APPROXIMATION".equalsIgnoreCase(precision);
		if (!Boolean.TRUE.equals(item.get("approximate")))
			return false;
		if ("SOURCED_GRAPH_DRAFT".equals(proposal.get("authorship"))
				&& text(item.get("key")).matches("existing-(route-)?[0-9]+"))
			return true;
		Map<String, Object> source = findRoom(maps(proposal.get("rooms")), text(item.get("graph_ref")));
		return source != null && "DRAFT_APPROXIMATION".equalsIgnoreCase(text(source.get("coordinate_precision")));
	}

	/** Prefer authored geometry and never route play through setup's provisional coordinates. */
	public static Optional<Row> regionalFeature(Tx tx, long campaignId, String graphRef) {
		return tx
				.query("SELECT * FROM map_feature WHERE campaign_id=? AND graph_ref=? ORDER BY id", campaignId,
						graphRef)
				.stream()
				.filter(f -> !graphRef.startsWith("connection:") || "PATH".equals(f.map("geometry_json").get("type")))
				.filter(f -> !graphRef.startsWith("location:") || "POINT".equals(f.map("geometry_json").get("type")))
				.filter(f -> !draftFeature(f))
				.min(Comparator.comparing(f -> Boolean.TRUE.equals(f.map("properties_json").get("approximate"))));
	}

	private static boolean draftFeature(Row feature) {
		Map<String, Object> properties = feature.map("properties_json");
		return draftGeometry(properties, Map.of()) || text(properties.get("coordinate_precision")).isBlank()
				&& Boolean.TRUE.equals(properties.get("approximate"))
				&& feature.str("feature_key").matches("existing-(route-)?[0-9]+");
	}

	/** Read-only bridge from semantic travel to saved geographic shapes. */
	public static Map<String, Object> regionalAnchor(
		Tx tx, long campaignId, String locationRef, String routeRef, String fromLocationRef,
		Double distanceAlongMiles) {
		Optional<Row> found = routeRef == null ? Optional.empty() : regionalFeature(tx, campaignId, routeRef);
		List<Double> position = null, tangent = null;
		String precision = "GEOMETRIC";
		if (found.isPresent()) {
			Row feature = found.get();
			List<List<Double>> path = points(feature.map("geometry_json").get("coordinates"));
			if (path.size() >= 2) {
				double feet = 0;
				for (int i = 1; i < path.size(); i++)
					feet += Math.hypot(path.get(i).get(0) - path.get(i - 1).get(0),
							path.get(i).get(1) - path.get(i - 1).get(1));
				Row edge = scoped(tx, campaignId, "location_connection", routeRef, "connection");
				Map<String, Object> distance = edge.isNull("distance_json") ? Map.of() : edge.map("distance_json");
				double miles = distance.get("distance_miles") instanceof Number n && n.doubleValue() > 0
						? n.doubleValue() : feet / 5280;
				double fraction = distanceAlongMiles == null ? .5
						: Math.max(0, Math.min(1, distanceAlongMiles / miles));
				Map<String, Object> props = feature.map("properties_json");
				String pathFrom = text(
						props.getOrDefault("from_location_ref", "location:" + edge.lng("location_a_id")));
				if (fromLocationRef != null && !fromLocationRef.equals(pathFrom))
					fraction = 1 - fraction;
				double remaining = feet * fraction;
				for (int i = 1; i < path.size(); i++) {
					List<Double> a = path.get(i - 1), b = path.get(i);
					double dx = b.get(0) - a.get(0), dy = b.get(1) - a.get(1), length = Math.hypot(dx, dy);
					if (length == 0)
						continue;
					if (remaining <= length || i == path.size() - 1) {
						double portion = Math.min(1, remaining / length);
						position = List.of(a.get(0) + dx * portion, a.get(1) + dy * portion);
						tangent = List.of(dx / length, dy / length);
						break;
					}
					remaining -= length;
				}
				precision = text(props.getOrDefault("coordinate_precision", "GEOMETRIC"));
			}
		}
		if (position == null && locationRef != null) {
			Row location = scoped(tx, campaignId, "location", locationRef, "location");
			Set<Long> visited = new HashSet<>();
			while (visited.add(location.id())) {
				found = regionalFeature(tx, campaignId, "location:" + location.id());
				if (found.isPresent()
						&& (position = point(found.get().map("geometry_json").get("coordinates"))) != null) {
					precision = locationRef.equals("location:" + location.id()) ? "GEOMETRIC" : "PARENT_AREA";
					break;
				}
				if (location.isNull("parent_id"))
					break;
				location = tx.get("location", location.lng("parent_id"));
			}
		}
		if (position == null || found.isEmpty())
			return Map.of();
		Row source = found.get(), atlas = tx.get("map_atlas", source.lng("atlas_id"));
		AtlasCoordinates.Sheet sheet = AtlasCoordinates.containing(position.get(0), position.get(1),
				number(atlas.get("sheet_width_ft")), number(atlas.get("sheet_height_ft")));
		List<String> terrain = new ArrayList<>();
		for (Row feature : tx.query(
				"SELECT * FROM map_feature WHERE campaign_id=? AND atlas_id=? ORDER BY id LIMIT 10000", campaignId,
				atlas.id())) {
			Map<String, Object> shape = feature.map("geometry_json");
			if (text(shape.get("type")).equalsIgnoreCase("POLYGON")
					&& inside(points(shape.get("coordinates")), position))
				terrain.add(feature.str("kind"));
		}
		Map<String, Object> result = map("atlas_ref", "atlas:" + atlas.id(), "feature_ref",
				"map_feature:" + source.id(), "world_position_ft", position, "world_origin_ft",
				List.of(position.get(0) - 40, position.get(1) - 40), "address", sheet.address(atlas.id()), "precision",
				precision, "terrain", terrain, "climate", currentClimate(tx, campaignId, atlas.map("climate_json")),
				"geographic_position",
				AtlasCoordinates.geographic(position.get(0), position.get(1), number(atlas.get("origin_latitude")),
						number(atlas.get("origin_longitude")), number(atlas.get("world_radius_m"))));
		if (tangent != null)
			result.put("route_tangent", tangent);
		if (routeRef != null)
			result.put("route_ref", routeRef);
		if (source.map("properties_json").get("width_ft") != null)
			result.put("route_width_ft", source.map("properties_json").get("width_ft"));
		return result;
	}

	private static boolean validPathExtension(
		Map<String, Object> oldGeometry, Map<String, Object> newGeometry, List<Double> newBounds) {
		if (!text(oldGeometry.get("type")).equalsIgnoreCase("PATH")
				|| !text(newGeometry.get("type")).equalsIgnoreCase("PATH") || newBounds == null)
			return false;
		List<List<Double>> before = points(oldGeometry.get("coordinates")),
				after = points(newGeometry.get("coordinates"));
		if (before.size() < 2 || after.size() <= before.size())
			return false;
		for (int offset = 0; offset <= after.size() - before.size(); offset++) {
			boolean same = true;
			for (int i = 0; i < before.size(); i++)
				same &= sameNumbers(before.get(i), after.get(offset + i));
			if (!same)
				continue;
			boolean valid = true;
			for (int i = 0; i < after.size(); i++)
				if (i < offset || i >= offset + before.size())
					valid &= containsPoint(newBounds, after.get(i));
			if (valid)
				return true;
		}
		return false;
	}

	private void publishObjects(
		Tx tx, long campaignId, Row prep, Map<String, Object> p, Map<String, Object> refs, Long sceneId) {
		for (String collection : List.of("objects", "rewards"))
			for (Map<String, Object> part : maps(p.get(collection))) {
				String key = (collection.equals("rewards") ? "reward:" : "object:") + part.get("key");
				Long roomId = part.get("room") == null ? Ref.id(text(refs.get("site")), "location")
						: roomId(tx, campaignId, refs, text(part.get("room")));
				Optional<Row> existing = tx.queryOne("SELECT * FROM map_object WHERE preparation_id=? AND object_key=?",
						prep.id(), key);
				if (existing.isPresent() && !existing.get().isNull("scene_id")) {
					refs.put(key, "map_object:" + existing.get().id());
					if (!existing.get().isNull("entity_ref"))
						refs.put("inventory:" + part.get("key"), existing.get().str("entity_ref"));
					continue; // A live source retains damage, locks, ownership and depletion.
				}
				String entity = existing.map(r -> r.str("entity_ref")).orElse(null);
				if (entity == null && part.get("entity_ref") != null) {
					entity = text(part.get("entity_ref"));
					if (Ref.parse(entity).type().equals("inventory"))
						scoped(tx, campaignId, "inventory_entry", entity, "inventory");
					else
						throw RpgException
								.invalidArgument("Object entity_ref must reference a canonical inventory entry.");
				}
				if (sceneId != null && entity == null && part.get("item_ref") != null) {
					Item item = ContentService.resolveItem(tx, rules, campaignId, text(part.get("item_ref")));
					Long container = null;
					if (part.get("container") != null) {
						String containerRef = text(refs.get("inventory:" + part.get("container")));
						if (containerRef.isBlank())
							throw RpgException
									.invalidArgument("Publish a container object with item_ref before its contents: "
											+ part.get("container"));
						container = Ref.id(containerRef, "inventory");
					}
					Map<String, Object> columns = map("campaign_id", campaignId, "location_id",
							container == null ? roomId : null, "container_entry_id", container, "content_ref_kind",
							item.custom() ? "CUSTOM" : "INSTALLED", "content_ref",
							item.custom() ? null : item.contentRef(), "custom_content_id",
							item.custom() ? item.customId() : null, "quantity", part.getOrDefault("quantity", 1),
							"equipped", 0);
					if (item.payload().get("charges") instanceof Number n)
						columns.put("charges_json", Json.write(map("current", n, "max", n)));
					entity = "inventory:" + tx.insert("inventory_entry", columns);
				}
				Map<String, Object> placement = new LinkedHashMap<>();
				List<Double> xy = position(p, part);
				if (xy != null)
					placement.putAll(map("x_ft", xy.get(0), "y_ft", xy.get(1), "level",
							part.getOrDefault("level", roomLevel(p, part))));
				if (part.get("rect_ft") != null)
					placement.put("rect_ft", part.get("rect_ft"));
				Map<String, Object> properties = copy(part);
				for (String field : List.of("x_ft", "y_ft", "position_ft", "rect_ft", "room", "entity_ref"))
					properties.remove(field);
				properties.put("planned", sceneId == null);
				Map<String, Object> columns = map("campaign_id", campaignId, "preparation_id", prep.id(), "scene_id",
						sceneId, "object_key", key, "entity_ref", entity, "room_id", roomId, "placement_json",
						Json.write(placement), "properties_json", Json.write(properties));
				long id;
				if (existing.isPresent()) {
					id = existing.get().id();
					columns.put("revision", existing.get().lng("revision") + 1);
					tx.update("map_object", id, columns);
				} else
					id = tx.insert("map_object", columns);
				refs.put(key, "map_object:" + id);
				if (entity != null)
					refs.put("inventory:" + part.get("key"), entity);
			}
	}

	private void publishActors(
		Tx tx, long campaignId, Row prep, Map<String, Object> p, Map<String, Object> refs, long sceneId) {
		for (Map<String, Object> actor : maps(p.get("actors"))) {
			if (refs.containsKey("actor:" + actor.get("key")))
				continue;
			long roomId = actor.get("room") == null ? Ref.id(text(refs.get("site")), "location")
					: roomId(tx, campaignId, refs, text(actor.get("room")));
			long characterId = actor.get("character_ref") == null ? createActor(tx, campaignId, roomId, actor)
					: scoped(tx, campaignId, "character", text(actor.get("character_ref")), "character").id();
			Row character = tx.get("character", characterId);
			if (!Objects.equals(character.lng("location_id"), roomId)) {
				Long oldLocation = character.lng("location_id");
				Row finer = tx.get("location", roomId);
				Set<Long> visited = new HashSet<>();
				while (oldLocation != null && finer.id() != oldLocation && !finer.isNull("parent_id")
						&& visited.add(finer.id()))
					finer = tx.get("location", finer.lng("parent_id"));
				if (oldLocation != null && finer.id() != oldLocation)
					throw RpgException.conflict(
							"Authoring cannot move an established character to a different place; resolve its movement first.");
				tx.update("character", characterId,
						map("location_id", roomId, "revision", character.lng("revision") + 1));
				tx.touched("character:" + characterId, character.lng("revision") + 1);
			}
			if (tx.queryOne("SELECT id FROM map_actor WHERE campaign_id=? AND character_id=?", campaignId, characterId)
					.isPresent())
				throw RpgException.conflict(
						"That character already has a canonical map placement; move it through normal play instead.");
			List<Double> xy = position(p, actor);
			tx.insert("map_actor",
					map("campaign_id", campaignId, "scene_id", sceneId, "character_id", characterId, "x_ft", xy.get(0),
							"y_ft", xy.get(1), "level", actor.getOrDefault("level", roomLevel(p, actor)),
							"footprint_ft", actor.getOrDefault("footprint_ft", 5)));
			refs.put("actor:" + actor.get("key"), "character:" + characterId);
		}
	}

	private long createActor(Tx tx, long campaignId, long roomId, Map<String, Object> actor) {
		RulesData.Definition def = rules.resolve("CREATURE", text(actor.get("creature_ref")))
				.orElseThrow(() -> RpgException.invalidArgument("Unknown creature definition."));
		Map<String, Object> payload = def.payload(), hp = object(payload.get("hp")),
				abilities = object(payload.get("abilities"));
		int hitPoints = integer(hp.getOrDefault("average", 1));
		Map<String, Object> columns = map("campaign_id", campaignId, "lifecycle", "ACTIVE", "life_state", "ALIVE",
				"name", actor.getOrDefault("name", def.name()), "description", actor.get("description"), "personality",
				actor.get("personality"), "max_hp", hitPoints, "current_hp", hitPoints, "speed",
				object(payload.get("speed")).getOrDefault("walk", 30), "senses_json",
				Json.write(payload.getOrDefault("senses", List.of())), "origin_content_ref", def.id(), "creation_json",
				"{}", "location_id", roomId, "created_at", Instant.now().toString());
		for (String ability : List.of("STR", "DEX", "CON", "INT", "WIS", "CHA"))
			columns.put(ability.toLowerCase(Locale.ROOT) + "_score", abilities.getOrDefault(ability, 10));
		if (actor.get("alignment") != null) {
			String alignment = text(actor.get("alignment")).toUpperCase(Locale.ROOT).replace(' ', '_');
			if (!CharacterService.ALIGNMENTS.contains(alignment))
				throw RpgException.invalidArgument("Unknown actor alignment.");
			columns.put("alignment", alignment);
		}
		long id = tx.insert("character", columns);
		tx.touched("character:" + id, 0);
		return id;
	}

	private Map<String, Object> sceneView(Tx tx, long campaignId, Row scene) {
		Map<String, Object> result = sceneSummary(scene);
		Map<String, Object> document = copy(scene.map("document_json"));
		if (!scene.isNull("atlas_id")) {
			document.put("features", regionFeatures(tx, scene));
			document.put("climate", currentClimate(tx, campaignId, object(document.get("climate"))));
		}
		List<Map<String, Object>> annotations = new ArrayList<>();
		for (Row observation : tx.query(
				"SELECT * FROM map_observation WHERE campaign_id=? AND scene_id=? AND observer_key LIKE 'annotations:%' ORDER BY id",
				campaignId, scene.id())) {
			for (Map<String, Object> note : maps(observation.map("knowledge_json").get("annotations"))) {
				Map<String, Object> projected = copy(note);
				projected.put("observer", observation.str("observer_key").substring("annotations:".length()));
				annotations.add(projected);
			}
		}
		document.put("annotations", annotations);
		List<Map<String, Object>> actors = new ArrayList<>();
		for (Row placement : tx.query(
				"SELECT p.*,c.name,c.life_state FROM map_actor p JOIN character c ON c.id=p.character_id WHERE p.campaign_id=? AND p.scene_id=? ORDER BY p.id",
				campaignId, scene.id())) {
			actors.add(map("character_ref", "character:" + placement.lng("character_id"), "name", placement.str("name"),
					"life_state", placement.str("life_state"), "x_ft", placement.get("x_ft"), "y_ft",
					placement.get("y_ft"), "level", placement.get("level"), "footprint_ft",
					placement.get("footprint_ft"), "revision", placement.lng("revision")));
		}
		List<Map<String, Object>> objects = new ArrayList<>();
		for (Row object : tx.query("SELECT * FROM map_object WHERE campaign_id=? AND scene_id=? ORDER BY id",
				campaignId, scene.id())) {
			Map<String, Object> view = object.map("properties_json");
			if (!object.isNull("entity_ref") && object.str("entity_ref").startsWith("inventory:")) {
				Optional<Row> item = tx.find("inventory_entry", Ref.id(object.str("entity_ref"), "inventory"));
				if (item.isEmpty() || !item.get().isNull("character_id"))
					continue;
				view.put("quantity", item.get().lng("quantity"));
			}
			view.putAll(object.map("placement_json"));
			view.putAll(map("object_ref", "map_object:" + object.id(), "entity_ref", object.str("entity_ref"),
					"room_ref", Ref.ofNullable("location", object.lng("room_id")), "revision", object.lng("revision")));
			objects.add(view);
		}
		document.put("actors", actors);
		document.put("objects", objects);
		for (String layer : List.of("doors", "portals"))
			for (Map<String, Object> door : maps(document.get(layer))) {
				if (door.get("connection_ref") == null)
					continue;
				Row passage = scoped(tx, campaignId, "location_connection", text(door.get("connection_ref")),
						"connection");
				door.putAll(passage.map("state_json"));
			}
		result.put("document", document);
		result.put("audience", "GM");
		return result;
	}

	/** Canonical GM geography input; callers still apply their audience projection. */
	public static Map<String, Object> regionalDocument(Tx tx, Row scene) {
		Map<String, Object> document = copy(scene.map("document_json"));
		if (!scene.isNull("atlas_id")) {
			document.put("features", regionFeatures(tx, scene));
			document.put("climate", currentClimate(tx, scene.lng("campaign_id"), object(document.get("climate"))));
		}
		List<Map<String, Object>> annotations = new ArrayList<>();
		for (Row observation : tx.query(
				"SELECT * FROM map_observation WHERE campaign_id=? AND scene_id=? AND observer_key LIKE 'annotations:%' ORDER BY id",
				scene.lng("campaign_id"), scene.id())) {
			for (Map<String, Object> note : maps(observation.map("knowledge_json").get("annotations"))) {
				Map<String, Object> item = copy(note);
				item.put("observer", observation.str("observer_key").substring("annotations:".length()));
				annotations.add(item);
			}
		}
		document.put("annotations", annotations);
		return document;
	}

	/** A regional party symbol derives from graph travel/location, never a tactical placement. */
	public static List<Map<String, Object>> regionalPartyMarkers(Tx tx, Row scene) {
		if (!"REGION".equals(scene.str("kind")) || scene.isNull("atlas_id"))
			return List.of();
		long cid = scene.lng("campaign_id");
		Map<String, Object> position;
		Map<String, Object> journey = se.hirt.mcp.rpg.world.TravelJourneyService.stateInTx(tx, cid);
		if (Boolean.TRUE.equals(journey.get("active"))
				&& se.hirt.mcp.rpg.world.TravelJourneyService.controlledTravelling(tx, cid)) {
			Map<String, Object> contact = se.hirt.mcp.rpg.world.TravelJourneyService.encounterSceneDocument(tx, cid,
					null);
			Map<String, Object> anchor = object(contact.get("route_anchor"));
			Optional<Row> route = anchor.get("connection_ref") == null ? Optional.empty()
					: regionalFeature(tx, cid, text(anchor.get("connection_ref")));
			if (route.isEmpty() || !Objects.equals(route.get().lng("atlas_id"), scene.lng("atlas_id"))
					|| !Boolean.TRUE.equals(contact.get("route_geometry_known")))
				return List.of();
			position = map("world_position_ft", contact.get("journey_world_anchor_ft"));
		} else {
			Long location = tx.get("campaign", cid).lng("current_location_id");
			if (location == null)
				return List.of();
			position = regionalAnchor(tx, cid, "location:" + location, null, null, null);
			if (!Objects.equals(position.get("atlas_ref"), "atlas:" + scene.lng("atlas_id")))
				return List.of();
		}
		List<Double> xy = point(position.get("world_position_ft")),
				bounds = rectangle(scene.map("document_json").get("bounds_ft"));
		if (xy == null || bounds == null || !containsPoint(bounds, xy))
			return List.of();
		return List.of(map("name", "Party", "label", "P", "position_ft", xy, "side", "PARTY", "party_member", true,
				"regional", true));
	}

	private static List<Map<String, Object>> regionFeatures(Tx tx, Row scene) {
		List<Map<String, Object>> features = new ArrayList<>();
		List<Double> bounds = rectangle(scene.map("document_json").get("bounds_ft"));
		for (Row feature : tx.query(
				"SELECT * FROM map_feature WHERE campaign_id=? AND atlas_id=? ORDER BY id LIMIT 10000",
				scene.lng("campaign_id"), scene.lng("atlas_id"))) {
			if (draftFeature(feature))
				continue;
			Map<String, Object> geometry = feature.map("geometry_json");
			List<List<Double>> coords = text(geometry.get("type")).equalsIgnoreCase("POINT")
					? List.of(point(geometry.get("coordinates"))) : points(geometry.get("coordinates"));
			if (!coords.isEmpty() && bounds != null) {
				double minX = coords.stream().mapToDouble(v -> v.get(0)).min().orElse(0),
						maxX = coords.stream().mapToDouble(v -> v.get(0)).max().orElse(0),
						minY = coords.stream().mapToDouble(v -> v.get(1)).min().orElse(0),
						maxY = coords.stream().mapToDouble(v -> v.get(1)).max().orElse(0);
				if (maxX < bounds.get(0) || minX > bounds.get(0) + bounds.get(2) || maxY < bounds.get(1)
						|| minY > bounds.get(1) + bounds.get(3))
					continue;
			}
			Map<String, Object> view = feature.map("properties_json");
			view.putAll(map("key", feature.str("feature_key"), "feature_ref", "map_feature:" + feature.id(), "kind",
					feature.str("kind"), "geometry", geometry, "graph_ref", feature.str("graph_ref")));
			String field = switch (text(geometry.get("type")).toUpperCase(Locale.ROOT)) {
			case "POINT" -> "point_ft";
			case "PATH" -> "path_ft";
			case "POLYGON" -> "polygon_ft";
			default -> null;
			};
			if (field != null)
				view.put(field, geometry.get("coordinates"));
			features.add(view);
		}
		return features;
	}

	private static Map<String, Object> sceneSummary(Row scene) {
		Map<String, Object> result = map("map", "map:" + scene.id(), "name", scene.str("name"), "kind",
				scene.str("kind"), "level", scene.integer("level"), "location_ref",
				Ref.ofNullable("location", scene.lng("location_id")), "revision", scene.lng("revision"),
				"layout_revision", scene.lng("layout_revision"), "generated", true);
		if (!scene.isNull("atlas_id"))
			result.putAll(map("atlas_ref", "atlas:" + scene.lng("atlas_id"), "sheet_x", scene.lng("sheet_x"), "sheet_y",
					scene.lng("sheet_y"), "address",
					new AtlasCoordinates.Sheet(scene.lng("sheet_x"), scene.lng("sheet_y"))
							.address(scene.lng("atlas_id"))));
		return result;
	}

	private Map<String, Object> atlasView(Tx tx, long campaignId, Row atlas) {
		return map("atlas", "atlas:" + atlas.id(), "name", atlas.str("name"), "sheet_width_ft",
				atlas.get("sheet_width_ft"), "sheet_height_ft", atlas.get("sheet_height_ft"), "origin_latitude",
				atlas.get("origin_latitude"), "origin_longitude", atlas.get("origin_longitude"), "world_radius_m",
				atlas.get("world_radius_m"), "style", atlas.map("style_json"), "climate",
				currentClimate(tx, campaignId, atlas.map("climate_json")), "revision", atlas.lng("revision"), "sheets",
				tx.query("SELECT * FROM map_scene WHERE campaign_id=? AND atlas_id=? ORDER BY sheet_y DESC,sheet_x",
						campaignId, atlas.id()).stream().map(MapAuthoringService::sceneSummary).toList(),
				"audience", "GM");
	}

	/** Climate geography is saved; its current season follows the campaign's living calendar. */
	private static Map<String, Object> currentClimate(Tx tx, long campaignId, Map<String, Object> saved) {
		Map<String, Object> climate = copy(saved);
		long sequence = tx.queryOne("SELECT seq FROM game_clock WHERE campaign_id=?", campaignId)
				.map(clock -> clock.lng("seq")).orElse(0L);
		Object season = se.hirt.mcp.rpg.session.Calendar.forCampaign(tx, campaignId).dateOf(sequence).get("season");
		if (climate.get("season") != null && !Objects.equals(climate.get("season"), season))
			climate.putIfAbsent("authored_season", climate.get("season"));
		climate.put("season", season);
		climate.put("season_source", "CAMPAIGN_CALENDAR");
		return climate;
	}

	private static Map<String, Object> document(Map<String, Object> p, Map<String, Object> refs) {
		Map<String, Object> result = copy(geometry(p));
		result.put("schema_version", SCHEMA_VERSION);
		result.put("name", p.get("name"));
		result.put("kind", p.get("kind"));
		result.put("grid_ft", stage(p).equals("REGION") ? null : 5);
		result.put("levels", levels(p));
		List<Map<String, Object>> rooms = new ArrayList<>();
		for (Map<String, Object> room : maps(p.get("rooms"))) {
			if (stage(p).equals("REGION") && draftGeometry(room, p))
				continue;
			Map<String, Object> view = copy(room);
			view.putAll(roomGeometry(p, room));
			view.put("location_ref", refs.get("room:" + room.get("key")));
			view.putIfAbsent("level", levels(p).get(0));
			view.putIfAbsent("material", "STONE");
			rooms.add(view);
		}
		result.put("rooms", rooms);
		List<Map<String, Object>> doors = new ArrayList<>(maps(result.get("doors")));
		List<Map<String, Object>> portals = new ArrayList<>(maps(result.get("portals")));
		for (Map<String, Object> edge : maps(p.get("connections"))) {
			Map<String, Object> opening = connectionGeometry(p, edge);
			if (opening.isEmpty())
				continue;
			opening.put("key", edge.get("key"));
			opening.putIfAbsent("kind", edge.getOrDefault("kind", "PASSAGE"));
			if (edge.get("name") != null)
				opening.put("name", edge.get("name"));
			opening.put("connection_ref", refs.get("connection:" + edge.get("key")));
			opening.put("from_location_ref", refs.getOrDefault("room:" + edge.get("from"), edge.get("from")));
			opening.put("to_location_ref", refs.getOrDefault("room:" + edge.get("to"), edge.get("to")));
			opening.put("state",
					Boolean.TRUE.equals(edge.get("secret")) ? "SECRET" : edge.getOrDefault("state", "OPEN"));
			opening.put("secret", Boolean.TRUE.equals(edge.get("secret")));
			if (edge.containsKey("challenges"))
				opening.put("challenges", copy(object(edge.get("challenges"))));
			if (opening.containsKey("from_level"))
				portals.add(opening);
			else
				doors.add(opening);
		}
		result.put("doors", doors);
		result.put("portals", portals);
		for (String field : List.of("walls", "terrain", "features", "landmarks", "illumination", "lighting", "style",
				"anchor"))
			if (p.containsKey(field))
				result.put(field, p.get(field));
		if ("DUNGEON".equals(p.get("kind")))
			result.putIfAbsent("illumination", "DARK");
		if (stage(p).equals("REGION"))
			result.put("features", maps(p.get("features")).stream().filter(f -> !draftGeometry(f, p)).toList());
		result.remove("actors");
		result.remove("objects");
		return result;
	}

	/** A modest fitting helper; existing authored facts/geometry always win. */
	private static void generateLayout(Map<String, Object> p) {
		List<Map<String, Object>> rooms = maps(p.get("rooms"));
		int count = integer(p.getOrDefault("room_count", Math.max(1, rooms.size())));
		if (count < rooms.size() || count > MAX_PARTS || count < 1)
			throw RpgException.invalidArgument("Invalid room_count for layout helper.");
		List<Integer> levels = levels(p);
		Set<String> used = new HashSet<>();
		rooms.forEach(r -> used.add(text(r.get("key"))));
		int serial = 1;
		while (rooms.size() < count) {
			String key;
			do {
				key = "room-" + serial++;
			} while (used.contains(key));
			used.add(key);
			rooms.add(map("key", key, "name", "Room " + (rooms.size() + 1), "helper_generated", true));
		}
		for (int i = 0; i < rooms.size(); i++)
			rooms.get(i).putIfAbsent("level", levels.get(i % levels.size()));
		List<Map<String, Object>> connections = maps(p.get("connections"));
		double minX = 0, minY = 0, maxX = 20, maxY = 20;
		Map<Integer, List<Map<String, Object>>> floors = new LinkedHashMap<>();
		for (int level : levels) {
			List<Map<String, Object>> floor = new ArrayList<>(
					rooms.stream().filter(r -> integer(r.get("level")) == level).toList());
			floor.sort(Comparator.comparing(r -> !Boolean.TRUE.equals(r.get("helper_generated"))));
			double x = 0;
			for (Map<String, Object> room : floor) {
				Map<String, Object> existing = roomGeometry(p, room);
				List<Double> rect = rectangle(existing.get("rect_ft"));
				if (rect != null)
					x = Math.max(x, rect.get(0) + rect.get(2));
			}
			for (Map<String, Object> room : floor) {
				Map<String, Object> existing = roomGeometry(p, room);
				if (rectangle(existing.get("rect_ft")) == null && points(existing.get("polygon_ft")).isEmpty()) {
					room.put("rect_ft", List.of(x, 0, 20, 20));
					x += 20;
				}
				List<Double> rect = rectangle(roomGeometry(p, room).get("rect_ft"));
				if (rect != null) {
					minX = Math.min(minX, rect.get(0));
					minY = Math.min(minY, rect.get(1));
					maxX = Math.max(maxX, rect.get(0) + rect.get(2));
					maxY = Math.max(maxY, rect.get(1) + rect.get(3));
				}
			}
			floors.put(level, floor);
		}
		p.put("rooms", rooms);
		p.put("connections", connections);
		p.put("room_count", count);
		p.put("stage", "SCENE");
		Map<String, Object> geometry = new LinkedHashMap<>(geometry(p));
		geometry.putIfAbsent("bounds_ft", List.of(minX, minY, maxX - minX, maxY - minY));
		p.put("geometry", geometry);
	}

	private static boolean hasConnectionGeometry(Map<String, Object> p, Map<String, Object> edge) {
		return !connectionGeometry(p, edge).isEmpty();
	}

	/**
	 * Audited physical correction in place: never republishes semantic entities or resets live
	 * state.
	 */
	public Map<String, Object> correctGeometry(
		String operationId, String campaignRef, String mapRef, Long expectedRevision, Map<String, Object> patch,
		String reason) {
		if (expectedRevision == null || reason == null || reason.isBlank() || reason.length() > 4000)
			throw RpgException.invalidArgument(
					"A current map revision and a nonempty correction reason of at most 4000 characters are required.");
		if (patch == null || patch.isEmpty()
				|| !Set.of("bounds_ft", "walls", "terrain", "rooms", "doors", "portals", "illumination")
						.containsAll(patch.keySet()))
			throw RpgException.invalidArgument(
					"Correct only bounds_ft, walls, terrain, keyed room/door/portal geometry, or map/room illumination.");
		long cid = Ref.id(campaignRef, Ref.CAMPAIGN);
		return db.mutate(
				Database.Mutation.of("correct_map_geometry", cid, operationId, "GM",
						map("map", mapRef, "expected_revision", expectedRevision, "patch", patch, "reason", reason)),
				tx -> {
					Row campaign = Harness.requireMutation(tx, campaignRef, "correct_map_geometry");
					if ("ENCOUNTER".equals(campaign.str("harness_state")) && !illuminationOnly(patch))
						throw RpgException.notAllowed(
								"During combat correct_map_geometry accepts only audited illumination or rooms:[{key,illumination}]. Structural geometry must wait until the encounter ends; this adjudication does not spend an Action or simulate a torch or spell.");
					Row scene = scoped(tx, cid, "map_scene", mapRef, Ref.MAP);
					if (scene.str("kind").equals("REGION"))
						throw RpgException.invalidArgument("This correction applies to local tactical maps only.");
					Harness.requireRevision(scene, mapRef, expectedRevision);
					Map<String, Object> candidate = copy(scene.map("document_json"));
					for (var change : patch.entrySet()) {
						String field = change.getKey();
						if (Set.of("rooms", "doors", "portals").contains(field)) {
							List<Map<String, Object>> corrections = correctionArray(change.getValue(), field, false);
							List<Map<String, Object>> parts = new ArrayList<>(maps(candidate.get(field)));
							Set<String> seen = new HashSet<>();
							Set<String> allowed = field.equals("rooms")
									? Set.of("key", "rect_ft", "polygon_ft", "holes_ft", "illumination")
									: field.equals("doors") ? Set.of("key", "from_ft", "to_ft")
											: Set.of("key", "from_position_ft", "to_position_ft", "movement_ft");
							for (Map<String, Object> correction : corrections) {
								String key = text(correction.get("key"));
								if (!(correction.get("key") instanceof String) || key.isBlank() || !seen.add(key)
										|| !allowed.containsAll(correction.keySet()) || correction.size() < 2)
									throw RpgException.invalidArgument("Use distinct existing " + field
											+ " keys and geometry/room illumination fields only; levels, identities, states and semantic references are protected.");
								Map<String, Object> original = findRoom(parts, key);
								if (original == null)
									throw RpgException.invalidArgument("Unknown existing " + field + " key: " + key);
								validateKeyedCorrection(field, correction);
								Map<String, Object> replacement = copy(original);
								if (correction.containsKey("polygon_ft"))
									replacement.remove("rect_ft");
								if (correction.containsKey("rect_ft"))
									replacement.remove("polygon_ft");
								replacement.putAll(correction);
								if (field.equals("rooms"))
									validateCorrectionShape(replacement, field + "[" + key + "]");
								parts.set(parts.indexOf(original), replacement);
							}
							candidate.put(field, parts);
						} else {
							if (field.equals("bounds_ft")) {
								if (rectangle(change.getValue()) == null)
									throw RpgException.invalidArgument(
											"bounds_ft must be [x,y,width,height] with positive dimensions.");
							} else if (field.equals("illumination"))
								validateIllumination(change.getValue());
							else
								validateCorrectionLayers(field, change.getValue(), levels(candidate));
							candidate.put(field, change.getValue());
						}
					}
					candidate = object(quantized(candidate, false));
					bounded(candidate);
					List<Violation> issues = sceneGeometryIssues(candidate);
					if (!issues.isEmpty())
						throw RpgException.validation(issues);
					candidate.put("layout_revision", integer(candidate.getOrDefault("layout_revision", 0)) + 1);
					candidate.put("last_geometry_correction", map("reason", reason, "journal_id", tx.journalId()));
					tx.update("map_scene", scene.id(),
							map("document_json", Json.write(candidate), "revision", scene.lng("revision") + 1));
					Row updated = tx.get("map_scene", scene.id());
					Map<String, Object> physical = SceneService.document(tx, updated);
					for (Row actor : tx.query("SELECT * FROM map_actor WHERE scene_id=?", scene.id())) {
						List<Geometry.Footprint> occupied = "DEAD"
								.equals(tx.get("character", actor.lng("character_id")).str("life_state")) ? List.of()
										: GridCombat.occupied(tx, scene.id(), actor.intOr("level", 0),
												actor.lng("character_id"));
						if (!SpatialRules.canPlace(SceneService.geometry(physical, actor.intOr("level", 0)),
								SceneService.footprint(actor), occupied))
							throw RpgException.validation(List.of(new Violation("patch", "ACTOR_WOULD_BE_INVALID",
									"This correction would obstruct or displace "
											+ Ref.of(Ref.CHARACTER, actor.lng("character_id"))
											+ "; existing figurines are never moved by a geometry correction.")));
					}
					validateObjectSupport(physical);
					Map<String, Object> beforeLight = lightingState(SceneService.document(tx, scene));
					Map<String, Object> afterLight = lightingState(physical);
					if (!beforeLight.equals(afterLight))
						LedgerService.append(tx, cid,
								new LedgerService.EventSpec("MAP_ILLUMINATION_CHANGED",
										lightingSummary(scene.str("name"), beforeLight, afterLight), List.of(),
										"NOTABLE", "GM_ONLY", "MECHANICAL_CONSEQUENCE", null,
										scene.isNull("location_id") ? null : scene.lng("location_id"), reason,
										map("map", mapRef, "before", beforeLight, "after", afterLight, "reason", reason,
												"journal_id", tx.journalId())));
					SceneService.remember(tx, updated);
					tx.touched(mapRef, scene.lng("revision") + 1);
					return map("map", mapRef, "map_revision", scene.lng("revision") + 1, "corrected", true, "reason",
							reason, "view", SceneService.projection(tx, updated, "PLAYER", updated.intOr("level", 0)),
							"meta", Harness.meta(campaign, List.of()));
				});
	}

	private static Map<String, Object> lightingState(Map<String, Object> document) {
		return map("illumination", document.getOrDefault("illumination", "BRIGHT"), "rooms", maps(document.get("rooms"))
				.stream()
				.map(room -> map("key", room.get("key"), "illumination", room.getOrDefault("illumination", "INHERIT")))
				.toList());
	}

	private static String lightingSummary(String name, Map<String, Object> before, Map<String, Object> after) {
		var changes = new ArrayList<String>();
		if (!Objects.equals(before.get("illumination"), after.get("illumination")))
			changes.add("map " + after.get("illumination"));
		var oldRooms = new HashMap<String, Object>();
		for (var room : maps(before.get("rooms")))
			oldRooms.put(text(room.get("key")), room.get("illumination"));
		int roomChanges = 0;
		for (var room : maps(after.get("rooms"))) {
			String key = text(room.get("key"));
			if (!Objects.equals(oldRooms.get(key), room.get("illumination"))) {
				roomChanges++;
				if (roomChanges <= 8)
					changes.add(key + " " + room.get("illumination"));
			}
		}
		if (roomChanges > 8)
			changes.add("and " + (roomChanges - 8) + " more rooms");
		return "Illumination changed on " + name + ": " + String.join("; ", changes) + ".";
	}

	private static List<Map<String, Object>> correctionArray(Object value, String field, boolean allowEmpty) {
		if (!(value instanceof List<?> list) || list.size() > MAX_PARTS || !allowEmpty && list.isEmpty())
			throw RpgException.invalidArgument(
					field + " must be a bounded " + (allowEmpty ? "" : "nonempty ") + "array of geometry objects.");
		return maps(value);
	}

	private static void validateKeyedCorrection(String field, Map<String, Object> correction) {
		if (field.equals("rooms")) {
			if (correction.containsKey("illumination"))
				validateIllumination(correction.get("illumination"));
			validateShapeArrays(correction, field);
			return;
		}
		for (String coordinate : field.equals("doors") ? List.of("from_ft", "to_ft")
				: List.of("from_position_ft", "to_position_ft"))
			if (correction.containsKey(coordinate) && point(correction.get(coordinate)) == null)
				throw RpgException.invalidArgument(coordinate + " must contain exactly two finite coordinates.");
		if (correction.containsKey("movement_ft")) {
			double cost = number(correction.get("movement_ft"));
			if (cost < 5 || cost > 1000)
				throw RpgException.invalidArgument("movement_ft must be between 5 and 1000.");
		}
	}

	private static boolean illuminationOnly(Map<String, Object> patch) {
		if (!Set.of("illumination", "rooms").containsAll(patch.keySet()))
			return false;
		if (!patch.containsKey("rooms"))
			return patch.containsKey("illumination");
		if (!(patch.get("rooms") instanceof List<?> rooms) || rooms.isEmpty() || rooms.size() > MAX_PARTS)
			return false;
		return rooms.stream().allMatch(
				room -> room instanceof Map<?, ?> entry && entry.keySet().equals(Set.of("key", "illumination")));
	}

	private static void validateIllumination(Object value) {
		if (!(value instanceof String text) || !Set.of("BRIGHT", "DIM", "DARK").contains(text))
			throw RpgException.invalidArgument("illumination must be BRIGHT, DIM or DARK.");
	}

	private static void validateShapeArrays(Map<String, Object> shape, String path) {
		if (shape.containsKey("rect_ft") && shape.containsKey("polygon_ft"))
			throw RpgException.invalidArgument(path + " must use exactly one of rect_ft and polygon_ft.");
		if (shape.containsKey("rect_ft") && rectangle(shape.get("rect_ft")) == null)
			throw RpgException
					.invalidArgument(path + ".rect_ft must contain [x,y,width,height] with positive dimensions.");
		if (shape.containsKey("polygon_ft"))
			validateCorrectionRing(shape.get("polygon_ft"), path + ".polygon_ft");
		if (shape.containsKey("holes_ft")) {
			if (!(shape.get("holes_ft") instanceof List<?> holes) || holes.size() > MAX_PARTS)
				throw RpgException.invalidArgument(path + ".holes_ft must be a bounded array of polygon rings.");
			for (Object hole : holes)
				validateCorrectionRing(hole, path + ".holes_ft");
		}
	}

	private static void validateCorrectionRing(Object value, String path) {
		if (!(value instanceof List<?> ring) || ring.size() < 3 || ring.size() > 4097
				|| ring.stream().anyMatch(vertex -> point(vertex) == null))
			throw RpgException.invalidArgument(
					path + " needs 3 through 4096 finite coordinate pairs (and an optional closing vertex).");
	}

	private static void validateCorrectionShape(Map<String, Object> shape, String path) {
		validateShapeArrays(shape, path);
		try {
			if (SceneService.polygon(shape) == null)
				throw RpgException.invalidArgument(path + " needs rect_ft or polygon_ft geometry.");
		} catch (IllegalArgumentException e) {
			throw RpgException.invalidArgument(path + ": " + e.getMessage());
		}
	}

	private static void validateCorrectionLayers(String field, Object value, List<Integer> levels) {
		Set<String> allowed = field.equals("walls") ? Set.of("key", "level", "from_ft", "to_ft")
				: Set.of("key", "level", "rect_ft", "polygon_ft", "holes_ft", "movement_multiplier");
		for (Map<String, Object> part : correctionArray(value, field, true)) {
			if (!allowed.containsAll(part.keySet()))
				throw RpgException.invalidArgument(field
						+ " corrections contain only physical geometry, level and movement cost; live object state is protected.");
			if (!levels.contains(integer(part.getOrDefault("level", 0))))
				throw RpgException
						.invalidArgument("Each corrected " + field + " entry must use an existing declared level.");
			if (field.equals("walls") && (point(part.get("from_ft")) == null || point(part.get("to_ft")) == null
					|| sameNumbers(point(part.get("from_ft")), point(part.get("to_ft")))))
				throw RpgException.invalidArgument("Each corrected wall needs two distinct finite endpoints.");
			if (field.equals("terrain")) {
				validateCorrectionShape(part, field);
				int multiplier = integer(part.getOrDefault("movement_multiplier", 1));
				if (multiplier < 1 || multiplier > 1000)
					throw RpgException
							.invalidArgument("Terrain movement_multiplier must be an integer from 1 to 1000.");
			}
		}
	}

	private static void validateObjectSupport(Map<String, Object> physical) {
		Map<Integer, Geometry.Scene> floors = new HashMap<>();
		for (Map<String, Object> object : maps(physical.get("objects"))) {
			String state = text(object.getOrDefault("state", "INTACT")).toUpperCase(Locale.ROOT);
			if (Set.of("DESTROYED", "REMOVED").contains(state)
					|| object.get("hit_points") instanceof Number hp && hp.doubleValue() <= 0)
				continue;
			List<Double> rect = rectangle(object.get("rect_ft"));
			if (rect == null && object.get("x_ft") instanceof Number && object.get("y_ft") instanceof Number)
				rect = List.of(number(object.get("x_ft")), number(object.get("y_ft")),
						number(object.getOrDefault("width_ft", 5)), number(object.getOrDefault("height_ft", 5)));
			if (rect == null)
				continue;
			Geometry.Point origin = SceneService.point(rect.get(0), rect.get(1)),
					end = SceneService.point(rect.get(0) + rect.get(2), rect.get(1) + rect.get(3));
			int level = integer(object.getOrDefault("level", 0));
			Geometry.Scene floor = floors.computeIfAbsent(level, key -> SceneService.geometry(physical, key));
			if (!SpatialRules.floorSupports(floor, new Geometry.Bounds(origin.x(), origin.y(), end.x(), end.y())))
				throw RpgException.validation(List.of(new Violation("patch", "OBJECT_WOULD_BE_INVALID",
						"This correction would remove the floor supporting " + text(object.get("key"))
								+ "; intact objects and placed loot are never moved by a geometry correction.")));
		}
	}

	private static List<Violation> sceneGeometryIssues(Map<String, Object> doc) {
		var errors = new ArrayList<Violation>();
		List<Double> bounds = rectangle(doc.get("bounds_ft"));
		if (bounds == null) {
			errors.add(new Violation("bounds_ft", "REQUIRED", "Supply positive bounds [x,y,width,height]."));
			return errors;
		}
		for (Map<String, Object> room : maps(doc.get("rooms"))) {
			Geometry.Polygon shape;
			try {
				shape = SceneService.polygon(room);
			} catch (IllegalArgumentException e) {
				errors.add(new Violation("rooms[" + room.get("key") + "]", "PHYSICAL_GEOMETRY", e.getMessage()));
				continue;
			}
			if (shape == null || shape.shell().stream()
					.anyMatch(v -> !containsPoint(bounds, List.of(v.x() / 12.0, v.y() / 12.0))))
				errors.add(new Violation("rooms[" + room.get("key") + "]", "OUT_OF_BOUNDS",
						"Room geometry must fit within the map bounds."));
		}
		var proposal = copy(doc);
		Map<String, String> keys = new HashMap<>();
		for (Map<String, Object> room : maps(doc.get("rooms")))
			if (room.get("location_ref") != null)
				keys.put(text(room.get("location_ref")), text(room.get("key")));
		var connections = new ArrayList<Map<String, Object>>();
		for (String field : List.of("doors", "portals"))
			for (Map<String, Object> opening : maps(doc.get(field))) {
				Map<String, Object> edge = copy(opening);
				String from = keys.get(text(opening.get("from_location_ref"))),
						to = keys.get(text(opening.get("to_location_ref")));
				if (opening.get("from_location_ref") == null && opening.get("to_location_ref") == null) {
					// Incidental boards may contain physical room keys without semantic graph entities.
					if (findRoom(maps(doc.get("rooms")), text(opening.get("from"))) != null
							&& findRoom(maps(doc.get("rooms")), text(opening.get("to"))) != null) {
						from = text(opening.get("from"));
						to = text(opening.get("to"));
					} else if (field.equals("doors")) {
						List<Map<String, Object>> adjacent = physicalDoorRooms(doc, opening);
						if (adjacent.size() == 2) {
							from = text(adjacent.get(0).get("key"));
							to = text(adjacent.get(1).get("key"));
						}
					}
				}
				edge.put("from", from);
				edge.put("to", to);
				connections.add(edge);
			}
		proposal.put("connections", connections);
		if (errors.isEmpty())
			validatePhysicalConnections(proposal, errors);
		if (errors.isEmpty())
			try {
				for (int level : levels(doc))
					SceneService.geometry(doc, level);
			} catch (IllegalArgumentException ex) {
				errors.add(new Violation("geometry", "PHYSICAL_GEOMETRY", ex.getMessage()));
			}
		return errors;
	}

	/**
	 * Infer only the existing physical pair at a complete shared aperture; never invent a route.
	 */
	private static List<Map<String, Object>> physicalDoorRooms(Map<String, Object> doc, Map<String, Object> opening) {
		List<Double> from = point(opening.get("from_ft")), to = point(opening.get("to_ft"));
		if (from == null || to == null || sameNumbers(from, to))
			return List.of();
		try {
			Geometry.Segment aperture = new Geometry.Segment(SceneService.point(from.get(0), from.get(1)),
					SceneService.point(to.get(0), to.get(1)));
			int level = integer(opening.getOrDefault("level", 0));
			return maps(doc.get("rooms")).stream().filter(room -> integer(room.getOrDefault("level", 0)) == level
					&& room.get("key") instanceof String && !text(room.get("key")).isBlank()).filter(room -> {
						Geometry.Polygon shape = SceneService.polygon(room);
						return shape != null && onBoundary(shape, aperture);
					}).toList();
		} catch (IllegalArgumentException ex) {
			return List.of();
		}
	}

	/**
	 * Validate the physical route that will actually be published, independently of passage state.
	 */
	private static void validatePhysicalConnections(Map<String, Object> p, List<Violation> errors) {
		Map<String, Object> doc;
		try {
			doc = document(p, Map.of());
			for (Map<String, Object> door : maps(doc.get("doors")))
				door.put("state", "OPEN");
			for (int level : levels(p))
				SceneService.geometry(doc, level);
		} catch (IllegalArgumentException ex) {
			errors.add(new Violation("geometry", "PHYSICAL_GEOMETRY", ex.getMessage()));
			return;
		}
		for (Map<String, Object> edge : maps(p.get("connections"))) {
			String path = "connections[" + edge.get("key") + "]";
			try {
				Map<String, Object> opening = connectionGeometry(p, edge);
				Map<String, Object> from = findRoom(maps(p.get("rooms")), text(edge.get("from"))),
						to = findRoom(maps(p.get("rooms")), text(edge.get("to")));
				if (opening.containsKey("from_level")) {
					validatePortalEndpoint(p, doc, opening, "from", from, path, errors);
					validatePortalEndpoint(p, doc, opening, "to", to, path, errors);
					continue;
				}
				if (from == null || to == null) {
					errors.add(new Violation(path, "ROOM_GEOMETRY_REQUIRED",
							"A local doorway must connect rooms represented on this map; use room keys and location_ref to retain existing identities."));
					continue;
				}
				Geometry.Polygon a = SceneService.polygon(roomGeometry(p, from)),
						b = SceneService.polygon(roomGeometry(p, to));
				List<Double> start = point(opening.get("from_ft")), end = point(opening.get("to_ft"));
				if (a == null || b == null || start == null || end == null || sameNumbers(start, end)) {
					errors.add(new Violation(path, "OPENING_REQUIRED",
							"A doorway needs two distinct endpoints on the shared room boundary."));
					continue;
				}
				Geometry.Segment segment = new Geometry.Segment(SceneService.point(start.get(0), start.get(1)),
						SceneService.point(end.get(0), end.get(1)));
				if (!onBoundary(a, segment) || !onBoundary(b, segment)) {
					errors.add(new Violation(path, "OPENING_OFF_SHARED_WALL",
							"The complete opening segment must lie on both connected room boundaries. Separate rooms need an authored corridor or an explicit portal."));
					continue;
				}
				Geometry.Scene scene = SceneService.geometry(doc, integer(opening.getOrDefault("level", 0)));
				if (!crossable(scene, a, b, segment))
					errors.add(new Violation(path, "OPENING_NOT_TRAVERSABLE",
							"This opening admits no adjacent 5ft grid crossing. Align its aperture with a traversable cell or widen it; ordinary corridors should be at least 10ft wide."));
			} catch (IllegalArgumentException ex) {
				errors.add(new Violation(path, "PHYSICAL_GEOMETRY", ex.getMessage()));
			}
		}
	}

	private static void validatePortalEndpoint(
		Map<String, Object> p, Map<String, Object> doc, Map<String, Object> portal, String side,
		Map<String, Object> room, String path, List<Violation> errors) {
		List<Double> xy = point(portal.get(side + "_position_ft"));
		if (xy == null) {
			errors.add(new Violation(path + "." + side + "_position_ft", "PORTAL_ENDPOINT",
					"Supply a 5ft grid footprint origin for this portal endpoint."));
			return;
		}
		Geometry.Footprint footprint = new Geometry.Footprint(SceneService.point(xy.get(0), xy.get(1)), 1, 1);
		Geometry.Scene scene = SceneService.geometry(doc, integer(portal.get(side + "_level")));
		Geometry.Polygon shape = room == null ? null : SceneService.polygon(roomGeometry(p, room));
		if (!SpatialRules.canPlace(scene, footprint, List.of()) || shape != null && !SpatialRules
				.canPlace(new Geometry.Scene(List.of(shape), List.of(), List.of()), footprint, List.of()))
			errors.add(new Violation(path + "." + side + "_position_ft", "PORTAL_ENDPOINT",
					"The endpoint must fit a 5ft figurine inside the connected room on its declared level."));
		double cost = number(portal.getOrDefault("movement_ft", 10));
		if (cost < 5 || cost > 1000)
			errors.add(new Violation(path + ".movement_ft", "PORTAL_COST",
					"Transition movement cost must be between 5 and 1000ft."));
	}

	private static boolean onBoundary(Geometry.Polygon polygon, Geometry.Segment opening) {
		return polygon.boundaries().stream()
				.anyMatch(edge -> onSegment(edge, opening.a()) && onSegment(edge, opening.b()));
	}

	private static boolean onSegment(Geometry.Segment edge, Geometry.Point p) {
		return cross(edge.a(), edge.b(), p) == 0 && edge.bounds().contains(p);
	}

	private static long cross(Geometry.Point a, Geometry.Point b, Geometry.Point p) {
		return (b.x() - a.x()) * (p.y() - a.y()) - (b.y() - a.y()) * (p.x() - a.x());
	}

	private static boolean intersects(Geometry.Segment a, Geometry.Segment b) {
		long ab1 = cross(a.a(), a.b(), b.a()), ab2 = cross(a.a(), a.b(), b.b()), ba1 = cross(b.a(), b.b(), a.a()),
				ba2 = cross(b.a(), b.b(), a.b());
		return ab1 == 0 && onSegment(a, b.a()) || ab2 == 0 && onSegment(a, b.b()) || ba1 == 0 && onSegment(b, a.a())
				|| ba2 == 0 && onSegment(b, a.b())
				|| Long.signum(ab1) != Long.signum(ab2) && Long.signum(ba1) != Long.signum(ba2);
	}

	private static boolean crossable(
		Geometry.Scene scene, Geometry.Polygon a, Geometry.Polygon b, Geometry.Segment opening) {
		Geometry.Bounds box = opening.bounds();
		long minX = Math.floorDiv(box.minX(), 60) * 60 - 60, maxX = Math.floorDiv(box.maxX(), 60) * 60 + 60,
				minY = Math.floorDiv(box.minY(), 60) * 60 - 60, maxY = Math.floorDiv(box.maxY(), 60) * 60 + 60;
		if ((maxX - minX) / 60 * ((maxY - minY) / 60) > 10000)
			return false;
		for (long x = minX; x <= maxX; x += 60)
			for (long y = minY; y <= maxY; y += 60) {
				Geometry.Footprint from = new Geometry.Footprint(new Geometry.Point(x, y), 1, 1);
				if (!a.containsInterior(from.center()) || !SpatialRules.canPlace(scene, from, List.of()))
					continue;
				for (int dx = -1; dx <= 1; dx++)
					for (int dy = -1; dy <= 1; dy++) {
						if (dx == 0 && dy == 0)
							continue;
						Geometry.Footprint to = from.at(new Geometry.Point(x + dx * 60, y + dy * 60));
						if (!b.containsInterior(to.center())
								|| !intersects(new Geometry.Segment(from.center(), to.center()), opening))
							continue;
						if (SpatialRules.transition(scene, from, to, List.of()))
							return true;
					}
			}
		return false;
	}

	private static Map<String, Object> connectionGeometry(Map<String, Object> p, Map<String, Object> edge) {
		Map<String, Object> a = findRoom(maps(p.get("rooms")), text(edge.get("from"))),
				b = findRoom(maps(p.get("rooms")), text(edge.get("to")));
		if (a == null || b == null)
			return object(edge.get("geometry"));
		int al = integer(a.getOrDefault("level", levels(p).get(0))),
				bl = integer(b.getOrDefault("level", levels(p).get(0)));
		if (al != bl || "PORTAL".equalsIgnoreCase(text(edge.get("kind"))) || edge.containsKey("from_position_ft")
				|| edge.containsKey("to_position_ft")) {
			List<Double> ac = center(p, a), bc = center(p, b);
			if (ac == null || bc == null)
				return Map.of();
			Map<String, Object> portal = map("from_level", al, "to_level", bl, "from_position_ft",
					edge.getOrDefault("from_position_ft", ac), "to_position_ft",
					edge.getOrDefault("to_position_ft", bc), "kind", edge.getOrDefault("kind", "STAIRS"));
			if (edge.containsKey("movement_ft"))
				portal.put("movement_ft", edge.get("movement_ft"));
			return portal;
		}
		if (points(List.of(edge.getOrDefault("from_ft", List.of()), edge.getOrDefault("to_ft", List.of()))).size() == 2)
			return map("from_ft", edge.get("from_ft"), "to_ft", edge.get("to_ft"), "level", al);
		Map<String, Object> authored = object(edge.get("geometry"));
		if (authored.containsKey("from_ft") && authored.containsKey("to_ft")) {
			authored.putIfAbsent("level", al);
			return authored;
		}
		List<Double> ar = rectangle(roomGeometry(p, a).get("rect_ft")),
				br = rectangle(roomGeometry(p, b).get("rect_ft"));
		if (ar == null || br == null)
			return Map.of();
		double lowY = Math.max(ar.get(1), br.get(1)), highY = Math.min(ar.get(1) + ar.get(3), br.get(1) + br.get(3));
		if (highY - lowY >= 5 && (close(ar.get(0) + ar.get(2), br.get(0)) || close(br.get(0) + br.get(2), ar.get(0)))) {
			double x = close(ar.get(0) + ar.get(2), br.get(0)) ? br.get(0) : ar.get(0), y = openingStart(lowY, highY);
			if (!Double.isFinite(y))
				return Map.of();
			return map("from_ft", List.of(x, y), "to_ft", List.of(x, y + 5), "level", al);
		}
		double lowX = Math.max(ar.get(0), br.get(0)), highX = Math.min(ar.get(0) + ar.get(2), br.get(0) + br.get(2));
		if (highX - lowX >= 5 && (close(ar.get(1) + ar.get(3), br.get(1)) || close(br.get(1) + br.get(3), ar.get(1)))) {
			double y = close(ar.get(1) + ar.get(3), br.get(1)) ? br.get(1) : ar.get(1), x = openingStart(lowX, highX);
			if (!Double.isFinite(x))
				return Map.of();
			return map("from_ft", List.of(x, y), "to_ft", List.of(x + 5, y), "level", al);
		}
		return Map.of();
	}

	private static double openingStart(double low, double high) {
		double min = Math.ceil(low / 5) * 5, max = Math.floor((high - 5) / 5) * 5;
		return max < min ? Double.NaN : Math.max(min, Math.min(max, Math.floor((low + high) / 10) * 5));
	}

	private static void quantizeLocal(Map<String, Object> proposal) {
		if ("REGION".equals(proposal.get("kind")) || "REGION".equals(stage(proposal)))
			return;
		for (String field : List.of("geometry", "rooms", "connections", "objects", "actors", "walls", "terrain",
				"bounds_ft"))
			if (proposal.containsKey(field))
				proposal.put(field, quantized(proposal.get(field), field.endsWith("_ft")));
		proposal.put("geometry_precision", Map.of("unit", "INCH", "quantum_ft", 1.0 / 12, "profile_version", 1));
	}

	private static Object quantized(Object value, boolean coordinate) {
		if (value instanceof Number n && coordinate)
			return Math.round(number(n) * 12) / 12.0;
		if (value instanceof List<?> list) {
			List<Object> result = new ArrayList<>();
			for (Object item : list)
				result.add(quantized(item, coordinate));
			return result;
		}
		if (value instanceof Map<?, ?> map) {
			Map<String, Object> result = new LinkedHashMap<>();
			for (var field : map.entrySet()) {
				String key = text(field.getKey());
				result.put(key,
						Set.of("attributes", "properties", "metadata", "mechanics", "effects", "tags").contains(key)
								? field.getValue() : quantized(field.getValue(), key.endsWith("_ft")));
			}
			return result;
		}
		return value;
	}

	private void validateRegion(Tx tx, long campaignId, Map<String, Object> p, List<Violation> errors) {
		Map<String, Object> atlas = object(p.get("atlas"));
		List<Double> bounds = rectangle(geometry(p).get("bounds_ft"));
		if (p.get("atlas_ref") != null) {
			try {
				Row saved = scoped(tx, campaignId, "map_atlas", text(p.get("atlas_ref")), "atlas");
				List<Double> expected = AtlasCoordinates.bounds(integer(p.getOrDefault("sheet_x", 0)),
						integer(p.getOrDefault("sheet_y", 0)), number(saved.get("sheet_width_ft")),
						number(saved.get("sheet_height_ft")));
				if (bounds != null && !sameNumbers(bounds, expected))
					errors.add(new Violation("geometry.bounds_ft", "SHEET_BOUNDS",
							"Bounds must match the existing atlas sheet address: " + expected));
				AtlasCoordinates.geographic(expected.get(0), expected.get(1), number(saved.get("origin_latitude")),
						number(saved.get("origin_longitude")), number(saved.get("world_radius_m")));
			} catch (RpgException e) {
				errors.add(new Violation("atlas_ref", "ATLAS_INVALID", e.getMessage()));
			}
		} else {
			try {
				AtlasCoordinates.geographic(0, 0, number(atlas.getOrDefault("origin_latitude", 43.5)),
						number(atlas.getOrDefault("origin_longitude", 0)),
						number(atlas.getOrDefault("world_radius_m", 6371000)));
			} catch (RpgException e) {
				errors.add(new Violation("atlas", "GEOGRAPHIC_FRAME", e.getMessage()));
			}
			if (integer(p.getOrDefault("sheet_x", 0)) != 0 || integer(p.getOrDefault("sheet_y", 0)) != 0)
				errors.add(new Violation("sheet_x", "ORIGIN_REQUIRED",
						"Create a new atlas at sheet (0,0), then reference it for expansion."));
			if (bounds != null && (!close(bounds.get(0), 0) || !close(bounds.get(1), 0)))
				errors.add(new Violation("geometry.bounds_ft", "ORIGIN_REQUIRED",
						"Initial sheet bounds start at the stable local atlas origin (0,0)."));
		}
		List<Map<String, Object>> features = maps(p.get("features"));
		unique(features, "features", errors);
		for (Map<String, Object> feature : features) {
			Map<String, Object> shape = featureGeometry(feature);
			String type = text(shape.get("type")).toUpperCase(Locale.ROOT),
					path = "features[" + feature.get("key") + "]";
			if (!Set.of("POINT", "PATH", "POLYGON", "MULTIPOLYGON").contains(type))
				errors.add(new Violation(path + ".geometry", "GEOMETRY_REQUIRED",
						"Save a POINT, PATH, POLYGON or MULTIPOLYGON, including the actual road/river bends."));
			if (type.equals("PATH") && points(shape.get("coordinates")).size() < 2)
				errors.add(new Violation(path, "PATH_REQUIRED",
						"A path needs at least two finite world-coordinate points."));
			if (type.equals("POINT") && point(shape.get("coordinates")) == null)
				errors.add(new Violation(path, "POINT_REQUIRED", "A point needs two finite coordinates."));
			if (type.equals("POLYGON"))
				validatePolygon(points(shape.get("coordinates")), path, null, errors);
		}
		if (errors.isEmpty())
			validateContinuity(tx, campaignId, p, errors);
	}

	private static void validateContinuity(Tx tx, long campaignId, Map<String, Object> p, List<Violation> errors) {
		if (p.get("atlas_ref") == null)
			return;
		Row atlas = scoped(tx, campaignId, "map_atlas", text(p.get("atlas_ref")), "atlas");
		int x = integer(p.getOrDefault("sheet_x", 0)), y = integer(p.getOrDefault("sheet_y", 0));
		List<Double> bounds = rectangle(geometry(p).get("bounds_ft"));
		if (bounds == null)
			return;
		List<Map<String, Object>> candidates = maps(p.get("features"));
		for (Row neighbor : tx.query(
				"SELECT * FROM map_scene WHERE campaign_id=? AND atlas_id=? AND ABS(sheet_x-?)+ABS(sheet_y-?)=1",
				campaignId, atlas.id(), x, y)) {
			int dx = neighbor.integer("sheet_x") - x, dy = neighbor.integer("sheet_y") - y;
			int axis = dx == 0 ? 1 : 0;
			double line = axis == 0 ? bounds.get(0) + (dx > 0 ? bounds.get(2) : 0)
					: bounds.get(1) + (dy > 0 ? bounds.get(3) : 0);
			double start = bounds.get(1 - axis), length = bounds.get(3 - axis);
			List<Map<String, Object>> known = regionFeatures(tx, neighbor);
			for (Map<String, Object> feature : known) {
				if (!Set.of("ROAD", "RIVER", "ROUTE", "COASTLINE")
						.contains(text(feature.get("kind")).toUpperCase(Locale.ROOT)))
					continue;
				List<Double> crossings = crossings(points(featureGeometry(feature).get("coordinates")), axis, line,
						start, start + length);
				for (double crossing : crossings) {
					Map<String, Object> candidate = findRoom(candidates, text(feature.get("key")));
					boolean match = candidate != null
							&& crossings(points(featureGeometry(candidate).get("coordinates")), axis, line, start,
									start + length).stream().anyMatch(v -> close(v, crossing));
					if (match)
						match = points(featureGeometry(candidate).get("coordinates")).stream()
								.anyMatch(v -> containsPoint(bounds, v) && !close(v.get(axis), line));
					if (!match)
						errors.add(new Violation("features[" + feature.get("key") + "]", "BOUNDARY_CONTINUITY",
								"Continue the saved " + feature.get("kind") + " at the shared edge with map:"
										+ neighbor.id() + " ("
										+ (axis == 0 ? line + "," + crossing : crossing + "," + line)
										+ "), preserving its feature key."));
					else if (feature.get("width_ft") instanceof Number oldWidth
							&& candidate.get("width_ft") instanceof Number newWidth
							&& !close(oldWidth.doubleValue(), newWidth.doubleValue()))
						errors.add(new Violation("features[" + feature.get("key") + "].width_ft", "BOUNDARY_WIDTH",
								"Preserve the existing feature width at the shared boundary."));
				}
			}
			for (double fraction : List.of(.125, .375, .625, .875)) {
				double along = start + fraction * length, epsilon = Math.min(bounds.get(2), bounds.get(3)) * 0.0000001;
				List<Double> oldPoint = axis == 0 ? List.of(line + Math.signum(dx) * epsilon, along)
						: List.of(along, line + Math.signum(dy) * epsilon);
				List<Double> newPoint = axis == 0 ? List.of(line - Math.signum(dx) * epsilon, along)
						: List.of(along, line - Math.signum(dy) * epsilon);
				if (waterAt(known, oldPoint) != waterAt(candidates, newPoint)) {
					errors.add(new Violation("features", "COAST_CONTINUITY",
							"Land/water coverage must agree across the shared edge with map:" + neighbor.id() + "."));
					break;
				}
			}
		}
	}

	private static boolean waterAt(List<Map<String, Object>> features, List<Double> point) {
		return features.stream().filter(
				f -> Set.of("OCEAN", "SEA", "LAKE", "WATER").contains(text(f.get("kind")).toUpperCase(Locale.ROOT)))
				.anyMatch(f -> inside(points(featureGeometry(f).get("coordinates")), point));
	}

	private static List<Double> crossings(List<List<Double>> path, int axis, double line, double min, double max) {
		List<Double> result = new ArrayList<>();
		for (int i = 1; i < path.size(); i++) {
			List<Double> a = path.get(i - 1), b = path.get(i);
			double da = a.get(axis) - line, db = b.get(axis) - line;
			if (close(da, db) || da * db > 0)
				continue;
			double along = a.get(1 - axis) + (b.get(1 - axis) - a.get(1 - axis)) * (-da / (db - da));
			if (along >= min && along <= max && result.stream().noneMatch(v -> close(v, along)))
				result.add(along);
		}
		return result;
	}

	private static void enrichAtlas(Tx tx, long campaignId, Map<String, Object> p) {
		p.putIfAbsent("authoring_brief", WorldMapPreparation.brief());
		if (p.get("atlas_ref") == null)
			return;
		Row atlas = scoped(tx, campaignId, "map_atlas", text(p.get("atlas_ref")), "atlas");
		int x = integer(p.getOrDefault("sheet_x", 0)), y = integer(p.getOrDefault("sheet_y", 0));
		Map<String, Object> geometry = geometry(p);
		geometry.putIfAbsent("bounds_ft", AtlasCoordinates.bounds(x, y, number(atlas.get("sheet_width_ft")),
				number(atlas.get("sheet_height_ft"))));
		p.put("geometry", geometry);
		p.put("style", atlas.map("style_json"));
		if (!p.containsKey("climate")) {
			List<Double> bounds = rectangle(geometry.get("bounds_ft"));
			Map<String, Object> climate = new LinkedHashMap<>(atlas.map("climate_json"));
			Map<String, Object> geographic = AtlasCoordinates.geographic(bounds.get(0) + bounds.get(2) / 2,
					bounds.get(1) + bounds.get(3) / 2, number(atlas.get("origin_latitude")),
					number(atlas.get("origin_longitude")), number(atlas.get("world_radius_m")));
			climate.put("baseline", geographic.get("latitude_band"));
			climate.put("latitude", geographic.get("latitude"));
			climate.put("source", "LATITUDE_TENDENCY_WITH_SAVED_MODIFIERS");
			p.put("climate", climate);
		}
		List<Map<String, Object>> neighbors = new ArrayList<>();
		for (Row neighbor : tx.query(
				"SELECT * FROM map_scene WHERE campaign_id=? AND atlas_id=? AND sheet_x BETWEEN ? AND ? AND sheet_y BETWEEN ? AND ? ORDER BY id",
				campaignId, atlas.id(), x - 1, x + 1, y - 1, y + 1)) {
			neighbors.add(map("map_ref", "map:" + neighbor.id(), "sheet_x", neighbor.lng("sheet_x"), "sheet_y",
					neighbor.lng("sheet_y"), "revision", neighbor.lng("revision"), "geography",
					neighbor.map("document_json").getOrDefault("features", List.of())));
		}
		p.put("source_neighbors", neighbors);
		p.put("continuity_note",
				"Preserve all adjoining road/river/coast crossings, feature identities, width, flow, relief and climate. Source revisions are checked on publication.");
	}

	private static Map<String, Object> commitments(Map<String, Object> p) {
		Map<String, Object> accepted = new LinkedHashMap<>();
		for (String field : List.of("name", "kind", "location_ref", "room_count", "levels", "rooms", "connections",
				"rewards", "actors", "objects", "patrols"))
			if (p.containsKey(field))
				accepted.put(field, p.get(field));
		return copy(accepted);
	}

	private static void preserves(Object accepted, Object candidate, String path, List<Violation> errors) {
		if (accepted instanceof Map<?, ?> a) {
			Map<String, Object> b = object(candidate);
			for (var entry : a.entrySet())
				if (!"generated_challenges".equals(entry.getKey()))
					preserves(entry.getValue(), b.get(text(entry.getKey())),
							path.isEmpty() ? text(entry.getKey()) : path + "." + entry.getKey(), errors);
		} else if (accepted instanceof List<?> a) {
			List<Object> b = list(candidate);
			boolean keyed = a.stream().allMatch(v -> v instanceof Map<?, ?> m && m.get("key") != null);
			if (keyed) {
				for (Object item : a) {
					Map<String, Object> old = object(item);
					Object newer = b.stream()
							.filter(v -> v instanceof Map<?, ?> m && Objects.equals(m.get("key"), old.get("key")))
							.findFirst().orElse(null);
					preserves(old, newer, path + "[" + old.get("key") + "]", errors);
				}
			} else if (!Json.hash(a).equals(Json.hash(b)))
				errors.add(new Violation(path, "COMMITMENT_CHANGED", "Preserve this accepted blueprint commitment."));
		} else if (accepted instanceof Number a && candidate instanceof Number b) {
			if (!close(a.doubleValue(), b.doubleValue()))
				errors.add(new Violation(path, "COMMITMENT_CHANGED", "Preserve the accepted value " + a + "."));
		} else if (!Objects.equals(accepted, candidate))
			errors.add(new Violation(path, "COMMITMENT_CHANGED",
					"Preserve the accepted blueprint value " + accepted + "."));
	}

	private static Map<String, Object> merge(Map<String, Object> current, Map<String, Object> changes) {
		Map<String, Object> result = copy(current);
		for (var change : changes.entrySet()) {
			String key = change.getKey();
			Object value = change.getValue();
			if (value instanceof Map<?, ?> m)
				result.put(key, merge(object(result.get(key)), object(m)));
			else if (value instanceof List<?> && KEYED_LISTS.contains(key)) {
				List<Map<String, Object>> parts = new ArrayList<>(maps(result.get(key)));
				for (Map<String, Object> patch : maps(value)) {
					Map<String, Object> before = findRoom(parts, text(patch.get("key")));
					if (Boolean.TRUE.equals(patch.get("remove"))) {
						if (patch.size() != 2 || !patch.containsKey("key"))
							throw RpgException.invalidArgument("A keyed deletion contains only key and remove:true.");
						if (before == null)
							throw RpgException
									.invalidArgument("Cannot remove an unknown " + key + " key: " + patch.get("key"));
						parts.remove(before);
					} else if (before == null)
						parts.add(copy(patch));
					else
						parts.set(parts.indexOf(before), merge(before, patch));
				}
				result.put(key, parts);
			} else
				result.put(key, value);
		}
		return result;
	}

	static Map<String, Object> sources(Tx tx, long campaignId, Object value) {
		Map<String, Object> result = new LinkedHashMap<>();
		collectSources(tx, campaignId, value, result);
		return result;
	}

	private static void collectSources(Tx tx, long campaignId, Object value, Map<String, Object> result) {
		if (value instanceof Map<?, ?> m)
			m.values().forEach(v -> collectSources(tx, campaignId, v, result));
		else if (value instanceof List<?> list)
			list.forEach(v -> collectSources(tx, campaignId, v, result));
		else if (value instanceof String s
				&& s.matches("(location|character|map|atlas|connection|inventory):[1-9][0-9]*")) {
			Ref ref = Ref.parse(s);
			String table = switch (ref.type()) {
			case "map" -> "map_scene";
			case "atlas" -> "map_atlas";
			case "connection" -> "location_connection";
			case "inventory" -> "inventory_entry";
			default -> ref.type();
			};
			Row row = scoped(tx, campaignId, table, s, ref.type());
			result.put(s, row.has("revision") ? row.lng("revision") : Json.hash(row.asMap()));
		}
	}

	private static void checkSources(Tx tx, long campaignId, Map<String, Object> expected) {
		for (var entry : expected.entrySet()) {
			Ref ref = Ref.parse(entry.getKey());
			String table = switch (ref.type()) {
			case "map" -> "map_scene";
			case "atlas" -> "map_atlas";
			case "connection" -> "location_connection";
			case "inventory" -> "inventory_entry";
			default -> ref.type();
			};
			Row row = scoped(tx, campaignId, table, entry.getKey(), ref.type());
			if (entry.getValue() instanceof String hash ? !hash.equals(Json.hash(row.asMap()))
					: row.lng("revision") != ((Number) entry.getValue()).longValue())
				throw RpgException
						.conflict("Preparation source " + entry.getKey()
								+ " changed; inspect and explicitly rebase the proposal.")
						.withDetail("reference", entry.getKey()).withDetail("actual_revision", row.lng("revision"));
		}
	}

	private static Row preparation(Tx tx, long campaignId, String ref) {
		return scoped(tx, campaignId, "map_preparation", ref, "preparation");
	}

	private static Row scoped(Tx tx, long campaignId, String table, String ref, String type) {
		Row row = tx.get(table, Ref.id(ref, type));
		if (row.lng("campaign_id") != campaignId)
			throw RpgException.invalidArgument(ref + " belongs to another campaign.");
		return row;
	}

	private static void validateScoped(
		Tx tx, long campaignId, String table, Object ref, String type, String path, List<Violation> errors) {
		try {
			scoped(tx, campaignId, table, text(ref), type);
		} catch (RpgException e) {
			errors.add(new Violation(path, "REFERENCE_INVALID", e.getMessage()));
		}
	}

	private static long roomId(Tx tx, long campaignId, Map<String, Object> refs, String key) {
		String ref = key.startsWith("location:") ? key : text(refs.get("room:" + key));
		return scoped(tx, campaignId, "location", ref, "location").id();
	}

	private static Map<String, Object> geometry(Map<String, Object> p) {
		Map<String, Object> result = new LinkedHashMap<>(object(p.get("geometry")));
		if (!result.containsKey("bounds_ft") && p.containsKey("bounds_ft"))
			result.put("bounds_ft", p.get("bounds_ft"));
		return result;
	}

	private static Map<String, Object> roomGeometry(Map<String, Object> p, Map<String, Object> room) {
		Map<String, Object> result = new LinkedHashMap<>(room);
		for (Map<String, Object> shape : maps(geometry(p).get("rooms")))
			if (Objects.equals(shape.get("key"), room.get("key")))
				result.putAll(shape);
		return result;
	}

	private static Map<String, Object> featureGeometry(Map<String, Object> feature) {
		Map<String, Object> geometry = object(feature.get("geometry"));
		if (!geometry.isEmpty())
			return geometry;
		for (String field : List.of("path_ft", "polygon_ft", "point_ft"))
			if (feature.containsKey(field))
				return map("type", field.equals("path_ft") ? "PATH" : field.equals("polygon_ft") ? "POLYGON" : "POINT",
						"coordinates", feature.get(field));
		return Map.of();
	}

	private static Map<String, Object> findRoom(List<Map<String, Object>> rooms, String key) {
		return rooms.stream().filter(r -> Objects.equals(text(r.get("key")), key)).findFirst().orElse(null);
	}

	private static List<Double> position(Map<String, Object> p, Map<String, Object> thing) {
		List<Double> xy = point(thing.get("position_ft"));
		if (xy != null)
			return xy;
		if (thing.get("x_ft") instanceof Number && thing.get("y_ft") instanceof Number)
			return List.of(number(thing.get("x_ft")), number(thing.get("y_ft")));
		List<Double> rect = rectangle(thing.get("rect_ft"));
		if (rect != null)
			return List.of(rect.get(0) + rect.get(2) / 2, rect.get(1) + rect.get(3) / 2);
		Map<String, Object> room = findRoom(maps(p.get("rooms")), text(thing.get("room")));
		return room == null ? null : center(p, room);
	}

	private static List<Double> center(Map<String, Object> p, Map<String, Object> room) {
		Map<String, Object> shape = roomGeometry(p, room);
		List<Double> rect = rectangle(shape.get("rect_ft"));
		if (rect != null)
			return List.of(rect.get(0) + rect.get(2) / 2, rect.get(1) + rect.get(3) / 2);
		List<List<Double>> points = points(shape.get("polygon_ft"));
		if (points.isEmpty())
			return null;
		List<Double> centroid = List.of(points.stream().mapToDouble(v -> v.get(0)).average().orElse(0),
				points.stream().mapToDouble(v -> v.get(1)).average().orElse(0));
		return inside(points, centroid) ? centroid : points.get(0);
	}

	private static int roomLevel(Map<String, Object> p, Map<String, Object> thing) {
		Map<String, Object> room = findRoom(maps(p.get("rooms")), text(thing.get("room")));
		return room == null ? levels(p).get(0) : integer(room.getOrDefault("level", levels(p).get(0)));
	}

	private static List<Integer> levels(Map<String, Object> p) {
		Object raw = p.getOrDefault("levels", 1);
		List<Integer> result = new ArrayList<>();
		if (raw instanceof List<?> explicit) {
			for (Object v : explicit)
				result.add(integer(v));
		} else {
			int count = integer(raw);
			if (count < 1 || count > 64)
				throw RpgException.invalidArgument("levels must be a count from 1 to 64 or explicit floor IDs.");
			for (int i = 0; i < count; i++)
				result.add(i);
		}
		if (result.isEmpty() || result.size() > 64 || new HashSet<>(result).size() != result.size())
			throw RpgException.invalidArgument("Declare distinct floor IDs, at least one and at most 64.");
		return result;
	}

	private static String stage(Map<String, Object> p) {
		return text(p.getOrDefault("stage", "BLUEPRINT")).toUpperCase(Locale.ROOT);
	}

	private static Set<String> unique(List<Map<String, Object>> items, String path, List<Violation> errors) {
		Set<String> keys = new LinkedHashSet<>();
		for (Map<String, Object> item : items) {
			String key = text(item.get("key"));
			if (key.isBlank() || key.length() > 120)
				errors.add(new Violation(path + ".key", "KEY_REQUIRED",
						"Every item needs a nonempty local key of at most 120 characters."));
			else if (!keys.add(key))
				errors.add(new Violation(path + "[" + key + "]", "DUPLICATE_KEY",
						"Draft keys must be unique within their collection."));
		}
		return keys;
	}

	private static void bounded(Map<String, Object> p) {
		if (Json.write(p).length() > 4_000_000)
			throw RpgException
					.invalidArgument("Preparation exceeds the bounded payload size; author smaller linked areas.");
		for (String field : KEYED_LISTS)
			if (list(p.get(field)).size() > MAX_PARTS)
				throw RpgException.invalidArgument(field + " exceeds the per-batch limit " + MAX_PARTS + ".");
	}

	private static List<Double> rectangle(Object value) {
		if (!(value instanceof List<?> list) || list.size() != 4 || list.stream().anyMatch(v -> !(v instanceof Number)))
			return null;
		List<Double> rect = list.stream().map(MapAuthoringService::number).toList();
		return rect.get(2) > 0 && rect.get(3) > 0 ? rect : null;
	}

	private static List<Double> point(Object value) {
		if (!(value instanceof List<?> list) || list.size() != 2 || list.stream().anyMatch(v -> !(v instanceof Number)))
			return null;
		return List.of(number(list.get(0)), number(list.get(1)));
	}

	private static List<List<Double>> points(Object value) {
		List<List<Double>> result = new ArrayList<>();
		for (Object item : list(value)) {
			List<Double> point = point(item);
			if (point == null)
				return List.of();
			result.add(point);
		}
		return result;
	}

	private static boolean contains(List<Double> outer, List<Double> inner) {
		return inner.get(0) >= outer.get(0) && inner.get(1) >= outer.get(1)
				&& inner.get(0) + inner.get(2) <= outer.get(0) + outer.get(2)
				&& inner.get(1) + inner.get(3) <= outer.get(1) + outer.get(3);
	}

	private static boolean containsPoint(List<Double> rect, List<Double> p) {
		return p.get(0) >= rect.get(0) && p.get(1) >= rect.get(1) && p.get(0) <= rect.get(0) + rect.get(2)
				&& p.get(1) <= rect.get(1) + rect.get(3);
	}

	private static boolean overlaps(List<Double> a, List<Double> b) {
		return a.get(0) < b.get(0) + b.get(2) && b.get(0) < a.get(0) + a.get(2) && a.get(1) < b.get(1) + b.get(3)
				&& b.get(1) < a.get(1) + a.get(3);
	}

	private static boolean inside(List<List<Double>> polygon, List<Double> point) {
		boolean inside = false;
		for (int i = 0, j = polygon.size() - 1; i < polygon.size(); j = i++) {
			List<Double> a = polygon.get(i), b = polygon.get(j);
			if ((a.get(1) > point.get(1)) != (b.get(1) > point.get(1)) && point
					.get(0) < (b.get(0) - a.get(0)) * (point.get(1) - a.get(1)) / (b.get(1) - a.get(1)) + a.get(0))
				inside = !inside;
		}
		return inside;
	}

	private static void validatePolygon(
		List<List<Double>> points, String path, List<Double> bounds, List<Violation> errors) {
		List<List<Double>> ring = new ArrayList<>(points);
		if (ring.size() > 1 && sameNumbers(ring.get(0), ring.get(ring.size() - 1)))
			ring.remove(ring.size() - 1);
		if (ring.size() < 3) {
			errors.add(new Violation(path, "POLYGON_REQUIRED", "A polygon needs at least three finite vertices."));
			return;
		}
		double area = 0;
		for (int i = 0; i < ring.size(); i++) {
			List<Double> a = ring.get(i), b = ring.get((i + 1) % ring.size());
			area += a.get(0) * b.get(1) - b.get(0) * a.get(1);
			if (bounds != null && !containsPoint(bounds, a))
				errors.add(new Violation(path, "OUT_OF_BOUNDS",
						"Polygon vertices must lie within the physical map bounds."));
			for (int j = i + 2; j < ring.size(); j++) {
				if (i == 0 && j == ring.size() - 1)
					continue;
				if (crosses(a, b, ring.get(j), ring.get((j + 1) % ring.size())))
					errors.add(new Violation(path, "SELF_INTERSECTION", "Polygon edges cannot cross."));
			}
		}
		if (Math.abs(area) < 0.000001)
			errors.add(new Violation(path, "ZERO_AREA", "A polygon must enclose nonzero area."));
	}

	private static boolean crosses(List<Double> a, List<Double> b, List<Double> c, List<Double> d) {
		double abC = cross(a, b, c), abD = cross(a, b, d), cdA = cross(c, d, a), cdB = cross(c, d, b);
		return abC * abD < -0.000001 && cdA * cdB < -0.000001;
	}

	private static double cross(List<Double> a, List<Double> b, List<Double> c) {
		return (b.get(0) - a.get(0)) * (c.get(1) - a.get(1)) - (b.get(1) - a.get(1)) * (c.get(0) - a.get(0));
	}

	private static boolean sameNumbers(List<Double> a, List<Double> b) {
		if (a.size() != b.size())
			return false;
		for (int i = 0; i < a.size(); i++)
			if (!close(a.get(i), b.get(i)))
				return false;
		return true;
	}

	private static boolean close(double a, double b) {
		return Math.abs(a - b) < 0.000001;
	}

	private static String pair(String a, String b) {
		return a.compareTo(b) < 0 ? a + "\u0000" + b : b + "\u0000" + a;
	}

	private static String text(Object value) {
		return value == null ? "" : value.toString();
	}

	private static Map<String, Object> asObject(Object value, String property) {
		return value instanceof Map<?, ?> ? object(value) : map(property, text(value));
	}

	private static double number(Object value) {
		if (!(value instanceof Number n) || !Double.isFinite(n.doubleValue()))
			throw RpgException.invalidArgument("Coordinates and dimensions must be finite numbers.");
		return n.doubleValue();
	}

	private static int integer(Object value) {
		double n = number(value);
		if (n != Math.rint(n) || n < Integer.MIN_VALUE || n > Integer.MAX_VALUE)
			throw RpgException.invalidArgument("An integer is required.");
		return (int) n;
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> object(Object value) {
		return value instanceof Map<?, ?> ? (Map<String, Object>) value : new LinkedHashMap<>();
	}

	@SuppressWarnings("unchecked")
	private static List<Object> list(Object value) {
		return value instanceof List<?> ? (List<Object>) value : List.of();
	}

	private static List<Map<String, Object>> maps(Object value) {
		List<Map<String, Object>> result = new ArrayList<>();
		for (Object element : list(value)) {
			if (!(element instanceof Map<?, ?>))
				throw RpgException.invalidArgument("Expected an array of structured objects.");
			result.add(object(element));
		}
		return result;
	}

	private static Map<String, Object> copy(Map<String, Object> value) {
		return Json.readMap(Json.write(value));
	}

	private static Map<String, Object> map(Object ... pairs) {
		Map<String, Object> result = new LinkedHashMap<>();
		for (int i = 0; i < pairs.length; i += 2)
			result.put(text(pairs[i]), pairs[i + 1]);
		return result;
	}
}
