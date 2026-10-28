package se.hirt.mcp.rpg;

import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import io.quarkiverse.mcp.server.ToolResponse;
import jakarta.inject.Inject;
import se.hirt.mcp.rpg.harness.Harness;
import se.hirt.mcp.rpg.maps.SceneService;
import se.hirt.mcp.rpg.persistence.Database;
import se.hirt.mcp.rpg.protocol.Ref;
import se.hirt.mcp.rpg.protocol.RpgException;

import java.nio.file.Path;
import java.util.*;

/** Map authoring, physical queries and optional image delivery over the same campaign state. */
public class MapTools {
	@Inject
	Engine engine;

	@Tool(name = "add_encounter_participants", description = "MUTATING. Join existing physically present actors to an existing encounter side. Server rolls initiative once and preserves the current turn, budgets, HP and exploration. Arrivals ahead of the current initiative wait until next round. Resolve pending choices first; this never spawns or relocates a guard.")
	ToolResponse addParticipants(
		@ToolArg(description = "Unique idempotency key for this mutation. Reuse only when retrying the same request.")
		String operation_id, @ToolArg(description = "Campaign reference, for example campaign:1.")
		String campaign, @ToolArg(description = "Existing encounter reference, for example encounter:3.")
		String encounter,
		@ToolArg(description = "Existing side names mapped to character references, for example {ENEMY:[character:9]}. Actors must already be physically present.")
		Map<String, List<String>> sides) {
		return ToolSupport.run("add_encounter_participants",
				() -> engine.encounters().addParticipants(operation_id, campaign, encounter, sides));
	}

	private se.hirt.mcp.rpg.maps.MapBundleService bundles() {
		return new se.hirt.mcp.rpg.maps.MapBundleService(engine.db(), engine.maps(), engine.mapAssets(),
				engine.db().file().toAbsolutePath().getParent());
	}

	@Tool(name = "export_map_bundle", description = "Read-only canonical state. Export a GM map authoring snapshot and required artwork to a managed .rpgmap.zip. Includes private content; use PLAYER rendering for a player handout. Campaign checkpoints remain authoritative for restoring play.", annotations = @Tool.Annotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false))
	ToolResponse exportBundle(@ToolArg(description = "Campaign reference, for example campaign:1.")
	String campaign, @ToolArg(description = "Saved map reference, for example map:2. Use find_maps to locate it.")
	String map) {
		return ToolSupport.run("export_map_bundle", () -> bundles().exportMap(campaign, map));
	}

	@Tool(name = "import_map_bundle", description = "MUTATING. Import a bounded .rpgmap.zip into a reviewed draft and immutable campaign assets. source_ref_mappings resolves references explicitly. Importing does not publish occupants or grant loot.")
	ToolResponse importBundle(
		@ToolArg(description = "Unique idempotency key for this mutation. Reuse only when retrying the same request.")
		String operation_id, @ToolArg(description = "Campaign reference, for example campaign:1.")
		String campaign,
		@ToolArg(description = "Absolute server-readable source file path. The server copies the source into managed campaign storage.")
		String source_path,
		@ToolArg(description = "Source references mapped to existing destination campaign references. Unresolved external references require review.")
		Optional<Map<String, String>> source_ref_mappings) {
		return ToolSupport.run("import_map_bundle", () -> bundles().importBundle(operation_id, campaign,
				Path.of(source_path), source_ref_mappings.orElse(Map.of())));
	}

	@Tool(name = "use_map_transition", description = "MUTATING exploration movement through a saved staircase, ladder or portal. Requires being within reach and a traversable passage. During combat use MOVE {portal:key} to preserve reactions and movement budgets.")
	ToolResponse transition(
		@ToolArg(description = "Unique idempotency key for this mutation. Reuse only when retrying the same request.")
		String operation_id, @ToolArg(description = "Campaign reference, for example campaign:1.")
		String campaign, @ToolArg(description = "Saved map reference, for example map:2. Use find_maps to locate it.")
		String map,
		@ToolArg(description = "Character reference, for example character:4. This is the actor whose figurine or perception is used.")
		String character,
		@ToolArg(description = "Stable transition key from the saved map, for example cellar-stairs. Read GM map context to find transition keys.")
		String portal) {
		return ToolSupport.run("use_map_transition",
				() -> compact(engine.scenes().transit(operation_id, campaign, map, character, portal)));
	}

	@Tool(name = "update_map_annotations", description = "MUTATING. Add an exact WORLD_POI at point_ft coordinates or a sourced NOTE/REPORT with appropriate PARTY/GM/character scope. Reports may remain approximate; a rumor does not invent a precise castle position. Re-render the same saved map after adding features.")
	ToolResponse annotate(
		@ToolArg(description = "Unique idempotency key for this mutation. Reuse only when retrying the same request.")
		String operation_id, @ToolArg(description = "Campaign reference, for example campaign:1.")
		String campaign, @ToolArg(description = "Saved map reference, for example map:2. Use find_maps to locate it.")
		String map,
		@ToolArg(description = "Revision returned by the previous read. Omit to use the latest revision; a supplied stale revision is rejected.")
		Optional<Long> expected_revision,
		@ToolArg(description = "WORLD_POI: {key,kind:WORLD_POI,name,point_ft:[x,y],location_ref} or create_location:{name,kind}. NOTE/REPORT: {key,kind,text,observer:PARTY|GM|character:N,source}; reports may be approximate.")
		Map<String, Object> annotation) {
		return ToolSupport.run("update_map_annotations",
				() -> engine.maps().annotate(operation_id, campaign, map, expected_revision.orElse(null), annotation));
	}

	@Tool(name = "convert_encounter_to_grid", description = "MUTATING. Explicitly convert a legacy running zone encounter to validated saved grid positions. Preserves initiative, HP, resources and pending choices; options supplies established placements/assumptions. New encounters already use grids.")
	ToolResponse convert(
		@ToolArg(description = "Unique idempotency key for this mutation. Reuse only when retrying the same request.")
		String operation_id, @ToolArg(description = "Campaign reference, for example campaign:1.")
		String campaign, @ToolArg(description = "Existing encounter reference, for example encounter:3.")
		String encounter,
		@ToolArg(description = "Optional documented operation settings. Read the tool description for accepted settings; omitted settings use defaults.")
		Optional<Map<String, Object>> options) {
		return ToolSupport.run("convert_encounter_to_grid",
				() -> engine.encounters().convertToGrid(operation_id, campaign, encounter, options.orElse(Map.of())));
	}

	@Tool(name = "begin_map_preparation", description = "MUTATING. Save a DUNGEON blueprint, SCENE or REGION proposal. Rooms/connections/rewards/features use stable local keys. Dungeons need no geometry until realization. Dungeon illumination defaults DARK; author illumination BRIGHT or DIM when the setting is lit. Locks, secrets and declared TRAP objects receive saved GM challenge guidance with difficulty 15 unless authored otherwise; override challenges per feature to suit the site. Returns gaps and the next operation. Draft contents are not live loot or occupants.")
	ToolResponse begin(
		@ToolArg(description = "Unique idempotency key for this mutation. Reuse only when retrying the same request.")
		String operation_id, @ToolArg(description = "Campaign reference, for example campaign:1.")
		String campaign,
		@ToolArg(description = "Preparation kind: DUNGEON, SCENE or REGION. Set proposal.stage to BLUEPRINT when accepting semantic dungeon commitments before geometry.")
		String kind,
		@ToolArg(description = "Map proposal with name and stable keyed rooms/connections/objects/rewards, or regional features. SCENE geometry uses bounds_ft:[x,y,width,height] and room rect_ft or polygon_ft in feet. Per-feature challenges may contain pick_lock, discover, disarm or force_open check specifications using resolve_check fields: difficulty (whole DC 1-40), kind, ability, optional skill/tool. TRAP objects are concealed by default; known_hazard:true or concealed:false marks an apparent/revealed hazard. These are GM guidance; checks do not automatically reveal, unlock or disarm anything.")
		Map<String, Object> proposal) {
		return ToolSupport.run("begin_map_preparation",
				() -> engine.maps().begin(operation_id, campaign, kind, proposal));
	}

	@Tool(name = "revise_map_preparation", description = "MUTATING. Revise a saved preparation in bounded keyed batches. generate_layout:true requests an optional layout helper. Accepted facts and canonical references survive realization.")
	ToolResponse revise(
		@ToolArg(description = "Unique idempotency key for this mutation. Reuse only when retrying the same request.")
		String operation_id, @ToolArg(description = "Campaign reference, for example campaign:1.")
		String campaign,
		@ToolArg(description = "Saved preparation reference, for example preparation:5, returned by begin_map_preparation.")
		String preparation,
		@ToolArg(description = "Revision returned by the previous read. Omit to use the latest revision; a supplied stale revision is rejected.")
		Optional<Long> expected_revision,
		@ToolArg(description = "Keyed changes to the current document or state. Preserve stable keys and canonical references; see the tool description for supported fields.")
		Map<String, Object> changes) {
		return ToolSupport.run("revise_map_preparation", () -> engine.maps().revise(operation_id, campaign, preparation,
				expected_revision.orElse(null), changes));
	}

	@Tool(name = "validate_map_preparation", description = "Read-only. Validate commitments, geometry, references and publication readiness without creating live content.", annotations = @Tool.Annotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false))
	ToolResponse validate(@ToolArg(description = "Campaign reference, for example campaign:1.")
	String campaign,
		@ToolArg(description = "Saved preparation reference, for example preparation:5, returned by begin_map_preparation.")
		String preparation) {
		return ToolSupport.run("validate_map_preparation", () -> engine.maps().validate(campaign, preparation));
	}

	@Tool(name = "correct_map_geometry", description = "MUTATING audited correction of an existing tactical map. Structural geometry changes require being outside combat. During combat only illumination or rooms:[{key,illumination}] is accepted, recording a DM-adjudicated lighting event without spending an Action or automatically resolving a torch/cantrip. Set illumination BRIGHT|DIM|DARK for the map, or keyed room overrides. Room illumination takes precedence over map ambient light; this does not simulate carried lights or light propagation. Preserves stable rooms/passages, actor positions, loot and object state. Rejects invalid current footprints or transitions. Supply the current map revision and an explanation; use ordinary gameplay tools for damage, locks and doors.")
	ToolResponse correctGeometry(
		@ToolArg(description = "Unique idempotency key. Reuse only to retry this identical correction.")
		String operation_id, @ToolArg(description = "Campaign reference, for example campaign:1.")
		String campaign, @ToolArg(description = "Published tactical map reference, for example map:2.")
		String map, @ToolArg(description = "Current revision from GM map context. Stale corrections are rejected.")
		long expected_revision,
		@ToolArg(description = "Bounded patch: illumination BRIGHT|DIM|DARK, rooms:[{key,illumination}] or room geometry by key, door segments by key, portal endpoints by key, bounds_ft, walls or terrain. Semantic keys, levels, references and live object state cannot be changed.")
		Map<String, Object> patch,
		@ToolArg(description = "Why the geometry was mistaken, or what resolved event changes the ambient illumination. For example: the braziers have been lit.")
		String reason) {
		return ToolSupport.run("correct_map_geometry",
				() -> engine.maps().correctGeometry(operation_id, campaign, map, expected_revision, patch, reason));
	}

	@Tool(name = "publish_map_preparation", description = "MUTATING. After the DM reviews/tunes the draft, atomically accept a semantic blueprint or publish validated playable geometry. Structural readiness does not certify encounter or treasure balance; revise bosses, defenses and keyed rewards before publishing. Resolves local keys once; retries never duplicate loot, rooms or passages. A separate preparation at an already published site returns a duplicate-realization warning; use correct_map_geometry to change existing illumination without creating another copy.")
	ToolResponse publish(
		@ToolArg(description = "Unique idempotency key for this mutation. Reuse only when retrying the same request.")
		String operation_id, @ToolArg(description = "Campaign reference, for example campaign:1.")
		String campaign,
		@ToolArg(description = "Saved preparation reference, for example preparation:5, returned by begin_map_preparation.")
		String preparation,
		@ToolArg(description = "Revision returned by the previous read. Omit to use the latest revision; a supplied stale revision is rejected.")
		Optional<Long> expected_revision) {
		return ToolSupport.run("publish_map_preparation",
				() -> engine.maps().publish(operation_id, campaign, preparation, expected_revision.orElse(null)));
	}

	@Tool(name = "get_map_context", description = "Read-only bounded map/preparation/atlas context. audience PLAYER filters unseen geometry, occupants and private metadata. GM returns authoring facts. Level defaults to zero.", annotations = @Tool.Annotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false))
	ToolResponse context(@ToolArg(description = "Campaign reference, for example campaign:1.")
	String campaign,
		@ToolArg(description = "Saved map:N, preparation:N, atlas:N or signed sheet address atlas:N[x,y]. Preparations and atlas authoring context require audience GM.")
		String reference,
		@ToolArg(description = "PLAYER (default) returns only permitted party knowledge. GM returns private authoring context; never forward GM output as a player handout.")
		Optional<String> audience,
		@ToolArg(description = "Numeric map level. Defaults to 0; use saved room and transition levels, including negative levels where authored.")
		Optional<Integer> level,
		@ToolArg(description = "SUMMARY (default) returns bounded context; FULL includes detailed permitted geometry for the selected level")
		Optional<String> detail) {
		return ToolSupport.run("get_map_context", () -> {
			if (reference.startsWith("map:")) {
				var context = engine.scenes().context(campaign, reference, audience.orElse("PLAYER"), level.orElse(0));
				return "FULL".equalsIgnoreCase(detail.orElse("SUMMARY")) ? context : compact(context);
			}
			if (!"GM".equalsIgnoreCase(audience.orElse("PLAYER")))
				throw RpgException.invalidArgument("Preparations and atlas authoring context require audience GM.");
			return engine.maps().get(campaign, reference);
		});
	}

	@Tool(name = "find_maps", description = "Read-only. Find saved maps and preparations by location, name, kind, atlas or sheet address; does not generate missing coverage. current_board/current_party_board and placed_characters identify actual use; other_published_realizations warns about separate boards of one site.", annotations = @Tool.Annotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false))
	ToolResponse find(@ToolArg(description = "Campaign reference, for example campaign:1.")
	String campaign,
		@ToolArg(description = "Optional filters: text, location_ref, kind, atlas_ref, address, or atlas_ref plus position_ft:[x,y]. Omitting filters lists saved maps and pending preparations.")
		Optional<Map<String, Object>> query) {
		return ToolSupport.run("find_maps", () -> engine.maps().find(campaign, query.orElse(Map.of())));
	}

	@Tool(name = "place_map_actors", description = "MUTATING during exploration. Place character figurines on valid unoccupied 5ft lattice cells, or explicitly move them onto another compatible same-location board without starting combat. Cross-board changes preserve an established authored room; traverse its doors with movement. placements contain character,x_ft,y_ft,level,footprint_ft. Existing positions on the same board require move_map_actor. Preserve established positions when starting combat. Returns bounded context; get_map_context detail FULL supplies permitted geometry.")
	ToolResponse place(
		@ToolArg(description = "Unique idempotency key for this mutation. Reuse only when retrying the same request.")
		String operation_id, @ToolArg(description = "Campaign reference, for example campaign:1.")
		String campaign, @ToolArg(description = "Saved map reference, for example map:2. Use find_maps to locate it.")
		String map,
		@ToolArg(description = "Initial placements: [{character:character:N,x_ft,y_ft,level,footprint_ft}]. x_ft/y_ft are southwest footprint origins on the 5 ft lattice. Established actors must move instead.")
		List<Map<String, Object>> placements) {
		return ToolSupport.run("place_map_actors",
				() -> compact(engine.scenes().place(operation_id, campaign, map, placements)));
	}

	@Tool(name = "observe_map", description = "MUTATING. Save what the party currently sees. Shared reveal is the union of party sight; old explored cells retain last-observed state.")
	ToolResponse observe(
		@ToolArg(description = "Unique idempotency key for this mutation. Reuse only when retrying the same request.")
		String operation_id, @ToolArg(description = "Campaign reference, for example campaign:1.")
		String campaign, @ToolArg(description = "Saved map reference, for example map:2. Use find_maps to locate it.")
		String map) {
		return ToolSupport.run("observe_map", () -> compact(engine.scenes().observe(operation_id, campaign, map)));
	}

	@Tool(name = "update_map_object", description = "MUTATING. Record a resolved gameplay effect or audited correction on a stable object/passage key. changes: state,locked,sealed,hit_points,blocks_movement,blocks_sight,material,label,secret,concealed,known_hazard. Reveal a discovered trap with concealed:false after adjudicating its check; a check alone grants no knowledge. A reason describing the adjudication is required; roll/check mechanics first when applicable. Broken furniture and opened doors immediately affect movement and sight.")
	ToolResponse object(
		@ToolArg(description = "Unique idempotency key for this mutation. Reuse only when retrying the same request.")
		String operation_id, @ToolArg(description = "Campaign reference, for example campaign:1.")
		String campaign, @ToolArg(description = "Saved map reference, for example map:2. Use find_maps to locate it.")
		String map,
		@ToolArg(description = "Canonical key from GM map context, for example object:treasure-chest or office-door. A unique bare object or reward key is also accepted; exact canonical keys take priority. The reply identifies canonical_key.")
		String key,
		@ToolArg(description = "Keyed changes to the current document or state. Preserve stable keys and canonical references; see the tool description for supported fields.")
		Map<String, Object> changes,
		@ToolArg(description = "Explanation of the adjudicated effect or correction, including the resolved check when applicable.")
		String reason) {
		return ToolSupport.run("update_map_object",
				() -> engine.scenes().changeObject(operation_id, campaign, map, key, changes, reason));
	}

	@Tool(name = "get_actor_perception", description = "Read-only GM decision context from this actor's own sight and retained evidence. Hearing gives an approximate direction; last-seen positions never track unseen movement.", annotations = @Tool.Annotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false))
	ToolResponse perception(@ToolArg(description = "Campaign reference, for example campaign:1.")
	String campaign, @ToolArg(description = "Saved map reference, for example map:2. Use find_maps to locate it.")
	String map,
		@ToolArg(description = "Character reference, for example character:4. This is the actor whose figurine or perception is used.")
		String character) {
		return ToolSupport.run("get_actor_perception", () -> engine.scenes().perception(campaign, map, character));
	}

	@Tool(name = "emit_map_sound", description = "MUTATING. Resolve an authored sound cue from an actor's saved marker or explicit x_ft/y_ft. Records approximate hearing evidence for listeners in range on the same level, reduced by occlusion. A cue prompts investigation; it does not expose an unseen target's exact position.")
	ToolResponse sound(
		@ToolArg(description = "Unique idempotency key for this mutation. Reuse only when retrying the same request.")
		String operation_id, @ToolArg(description = "Campaign reference, for example campaign:1.")
		String campaign, @ToolArg(description = "Saved map reference, for example map:2. Use find_maps to locate it.")
		String map,
		@ToolArg(description = "Explicit sound-source east coordinate in feet. Omit with actor; otherwise supply both coordinates.")
		Optional<Double> x_ft,
		@ToolArg(description = "Explicit sound-source north coordinate in feet. Omit with actor; otherwise supply both coordinates.")
		Optional<Double> y_ft,
		@ToolArg(description = "Sound level. Defaults to actor's saved level, or 0 for explicit coordinates.")
		Optional<Integer> level,
		@ToolArg(description = "Maximum sound range in feet before occlusion. Listeners must be on the same level.")
		double range_ft,
		@ToolArg(description = "Audible cue as perceived by listeners, without revealing an unseen source identity or exact position.")
		String description,
		@ToolArg(description = "Optional placed character reference used as the sound source instead of x_ft/y_ft.")
		Optional<String> actor) {
		return ToolSupport.run("emit_map_sound", () -> engine.scenes().sound(operation_id, campaign, map,
				x_ft.orElse(null), y_ft.orElse(null), level.orElse(null), range_ft, description, actor.orElse(null)));
	}

	@Tool(name = "move_map_actor", description = "MUTATING exploration movement on an existing scene. Uses collision, occupancy and terrain paths. x_ft/y_ft target the southwest footprint origin on the 5ft lattice, not the marker center. During combat use perform_encounter_action so budgets/reactions apply.")
	ToolResponse move(
		@ToolArg(description = "Unique idempotency key for this mutation. Reuse only when retrying the same request.")
		String operation_id, @ToolArg(description = "Campaign reference, for example campaign:1.")
		String campaign, @ToolArg(description = "Saved map reference, for example map:2. Use find_maps to locate it.")
		String map,
		@ToolArg(description = "Character reference, for example character:4. This is the actor whose figurine or perception is used.")
		String character, @ToolArg(description = "Destination footprint southwest x in feet; multiple of 5")
		double x_ft, @ToolArg(description = "Destination footprint southwest y in feet; multiple of 5")
		double y_ft) {
		return ToolSupport.run("move_map_actor",
				() -> engine.scenes().move(operation_id, campaign, map, character, x_ft, y_ft));
	}

	@Tool(name = "preview_spatial_action", description = "Read-only movement preview. PLAYER uses only known geometry; unknown cells are not a free probe for secret blockers. Does not spend movement or time.", annotations = @Tool.Annotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false))
	ToolResponse preview(@ToolArg(description = "Campaign reference, for example campaign:1.")
	String campaign, @ToolArg(description = "Saved map reference, for example map:2. Use find_maps to locate it.")
	String map,
		@ToolArg(description = "Character reference, for example character:4. This is the actor whose figurine or perception is used.")
		String character,
		@ToolArg(description = "East coordinate in feet in this map. Movement destinations use southwest footprint origins on the 5 ft lattice.")
		double x_ft,
		@ToolArg(description = "North coordinate in feet in this map. Movement destinations use southwest footprint origins on the 5 ft lattice.")
		double y_ft,
		@ToolArg(description = "PLAYER (default) returns only permitted party knowledge. GM returns private authoring context; never forward GM output as a player handout.")
		Optional<String> audience) {
		return ToolSupport.run("preview_spatial_action",
				() -> engine.scenes().preview(campaign, map, character, x_ft, y_ft, audience.orElse("PLAYER")));
	}

	@Tool(name = "render_map", description = "Read-only. Render saved geometry and permitted knowledge as PNG plus text and structured marker movement targets. audience defaults PLAYER; PLAYER text uses names/directions, GM text includes references/coordinates. options grid_ft 5|10, max_pixels <=2048, style DEFAULT|PARCHMENT|HIGH_CONTRAST, technical_text optional override. include_image:false supports text-only clients. Rendering never generates maps, advances time or reveals hidden state.", annotations = @Tool.Annotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false))
	ToolResponse render(@ToolArg(description = "Campaign reference, for example campaign:1.")
	String campaign, @ToolArg(description = "Saved map reference, for example map:2. Use find_maps to locate it.")
	String map,
		@ToolArg(description = "PLAYER (default) returns only permitted party knowledge. GM returns private authoring context; never forward GM output as a player handout.")
		Optional<String> audience,
		@ToolArg(description = "Numeric map level. Defaults to 0; use saved room and transition levels, including negative levels where authored.")
		Optional<Integer> level,
		@ToolArg(description = "Optional documented operation settings. Read the tool description for accepted settings; omitted settings use defaults.")
		Optional<Map<String, Object>> options,
		@ToolArg(description = "true (default) returns PNG plus text. false returns text and permitted structured context without loading textures or allocating a raster.")
		Optional<Boolean> include_image) {
		if (!include_image.orElse(true))
			return ToolSupport.run("render_map", () -> {
				var context = engine.scenes().context(campaign, map, audience.orElse("PLAYER"), level.orElse(0));
				var result = new LinkedHashMap<>(compact(context));
				result.put("text", engine.mapRenderer().describe(SceneService.map(context.get("view")),
						renderOptions(audience.orElse("PLAYER"), options.orElse(Map.of()))));
				result.put("markers", markers(SceneService.map(context.get("view")), options.orElse(Map.of())));
				return result;
			});
		final byte[][] image = {null};
		java.util.function.Supplier<Map<String, Object>> body = () -> {
			Map<String, Object> context = engine.scenes().context(campaign, map, audience.orElse("PLAYER"),
					level.orElse(0));
			var rendered = engine.mapRenderer().render(SceneService.map(context.get("view")),
					renderOptions(audience.orElse("PLAYER"), options.orElse(Map.of())),
					textures(campaign, SceneService.map(context.get("view")), options.orElse(Map.of())));
			image[0] = rendered.png();
			return Map.of("map", map, "mime_type", "image/png", "width", rendered.width(), "height", rendered.height(),
					"transform", rendered.transform(), "text", rendered.text(), "markers",
					markers(SceneService.map(context.get("view")), options.orElse(Map.of())), "meta",
					context.get("meta"));
		};
		return include_image.orElse(true) ? ToolSupport.image("render_map", body, () -> image[0])
				: ToolSupport.run("render_map", body);
	}

	private static Map<String, Object> renderOptions(String audience, Map<String, Object> options) {
		var resolved = new LinkedHashMap<>(options);
		resolved.putIfAbsent("technical_text", "GM".equalsIgnoreCase(audience));
		return resolved;
	}

	private static List<Map<String, Object>> markers(Map<String, Object> view, Map<String, Object> options) {
		var result = new ArrayList<Map<String, Object>>();
		for (var actor : se.hirt.mcp.rpg.maps.MapRenderer.visibleActors(view, options)) {
			var marker = new LinkedHashMap<String, Object>();
			marker.put("marker", actor.getOrDefault("label", Integer.toString(result.size() + 1)));
			for (String field : List.of("name", "character_ref", "position_ft", "level", "footprint_ft", "width_ft",
					"height_ft", "life_state"))
				if (actor.containsKey(field))
					marker.put(field, actor.get(field));
			if (actor.containsKey("x_ft") && actor.containsKey("y_ft"))
				marker.put("move_origin_ft", List.of(actor.get("x_ft"), actor.get("y_ft")));
			result.add(marker);
		}
		return result;
	}

	private static Map<String, Object> compact(Map<String, Object> context) {
		var result = new LinkedHashMap<>(context);
		var view = new LinkedHashMap<>(SceneService.map(context.get("view")));
		for (String layer : List.of("rooms", "walls", "doors", "objects", "features", "annotations", "actors")) {
			Object value = view.get(layer);
			if (value instanceof List<?> list) {
				view.put(layer + "_count", list.size());
				if (layer.equals("rooms") && "GM".equals(view.get("audience"))) {
					view.put("rooms", SceneService.maps(value).stream().limit(100).map(room -> {
						var summary = new LinkedHashMap<String, Object>();
						for (String key : List.of("key", "name", "location_ref", "level", "illumination"))
							if (room.containsKey(key))
								summary.put(key, room.get(key));
						summary.put("effective_illumination",
								room.getOrDefault("illumination", view.getOrDefault("illumination", "BRIGHT")));
						return summary;
					}).toList());
					if (list.size() > 100)
						view.put("rooms_truncated", true);
				} else if (layer.equals("rooms") || layer.equals("walls"))
					view.remove(layer);
				else
					view.put(layer, list.stream().limit(100).toList());
			}
		}
		var visibility = SceneService.map(view.remove("visibility"));
		if (!visibility.isEmpty())
			view.put("visibility",
					Map.of("visible_cell_count", ((List<?>) visibility.getOrDefault("visible", List.of())).size(),
							"explored_cell_count", ((List<?>) visibility.getOrDefault("explored", List.of())).size()));
		result.put("view", view);
		result.put("detail", "SUMMARY");
		return result;
	}

	private Map<String, se.hirt.mcp.rpg.maps.AssetService.Texture> textures(
		String campaign, Map<String, Object> view, Map<String, Object> options) {
		long cid = Ref.id(campaign, Ref.CAMPAIGN);
		var requested = new LinkedHashMap<String, String>();
		for (String layer : List.of("rooms", "objects", "features"))
			for (var item : SceneService.maps(view.get(layer))) {
				String material = SceneService.string(item, "material", null);
				if (material != null)
					requested.put(material, material);
			}
		for (var override : SceneService.map(options.get("material_overrides")).entrySet())
			requested.put(override.getKey(), String.valueOf(override.getValue()));
		return engine.db().read(tx -> {
			Harness.campaign(tx, campaign);
			var out = new LinkedHashMap<String, se.hirt.mcp.rpg.maps.AssetService.Texture>();
			for (var entry : requested.entrySet()) {
				String ref = entry.getValue();
				if (!ref.startsWith("asset:") && !ref.matches("[0-9a-f]{64}"))
					continue;
				var matches = ref.startsWith("asset:")
						? tx.query("SELECT * FROM map_asset WHERE campaign_id=? AND id=?", cid, Ref.id(ref, Ref.ASSET))
						: tx.query("SELECT * FROM map_asset WHERE campaign_id=? AND content_hash=?", cid, ref);
				if (matches.size() != 1)
					throw RpgException
							.invalidArgument(matches.isEmpty() ? "Texture is not registered in this campaign: " + ref
									: "Texture has multiple physical repeat scales; use its asset:N reference.");
				var asset = matches.getFirst();
				out.put(entry.getKey(), engine.mapAssets().load(cid, asset.str("content_hash"),
						((Number) asset.get("repeat_ft")).doubleValue()));
			}
			return out;
		});
	}

	@Tool(name = "import_map_asset", description = "MUTATING. Import a server-readable PNG texture to immutable campaign storage. source_path is copied; repeat_ft is physical repeat width. The renderer chooses pixel dimensions. Use the returned content_hash as material override.")
	ToolResponse asset(
		@ToolArg(description = "Unique idempotency key for this mutation. Reuse only when retrying the same request.")
		String operation_id, @ToolArg(description = "Campaign reference, for example campaign:1.")
		String campaign,
		@ToolArg(description = "Absolute server-readable source file path. The server copies the source into managed campaign storage.")
		String source_path,
		@ToolArg(description = "Physical texture repeat width in feet, default 5. The renderer chooses pixel dimensions; use the returned asset reference for an unambiguous repeat scale.")
		Optional<Double> repeat_ft) {
		return ToolSupport.run("import_map_asset", () -> {
			long cid = Ref.id(campaign, Ref.CAMPAIGN);
			var mutation = Database.Mutation.of("import_map_asset", cid, operation_id, "GM",
					Map.of("source_path", source_path, "repeat_ft", repeat_ft.orElse(5.0)));
			var replay = engine.db().replay(mutation);
			if (replay.isPresent())
				return replay.get();
			engine.db().read(tx -> {
				Harness.requireMutation(tx, campaign, "import_map_asset");
				return null;
			});
			var a = engine.mapAssets().importPng(cid, Path.of(source_path), repeat_ft.orElse(5.0));
			return engine.db().mutate(mutation, tx -> {
				var c = Harness.requireMutation(tx, campaign, "import_map_asset");
				var existing = tx.queryOne(
						"SELECT * FROM map_asset WHERE campaign_id=? AND content_hash=? AND repeat_ft=?", cid, a.id(),
						a.repeatFeet());
				long id = existing.isPresent() ? existing.get().id()
						: tx.insert("map_asset", Map.of("campaign_id", cid, "content_hash", a.id(), "mime_type",
								"image/png", "width", a.width(), "height", a.height(), "repeat_ft", a.repeatFeet()));
				var metadata = new LinkedHashMap<>(a.metadata());
				metadata.put("ref", Ref.of(Ref.ASSET, id));
				return Map.of("asset", metadata, "meta", Harness.meta(c, List.of()));
			});
		});
	}
}
