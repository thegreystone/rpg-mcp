/*
 * Copyright (C) 2026 Marcus Hirt
 *
 * This software is free:
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions
 * are met:
 *
 * 1. Redistributions of source code must retain the above copyright
 *    notice, this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright
 *    notice, this list of conditions and the following disclaimer in the
 *    documentation and/or other materials provided with the distribution.
 * 3. The name of the author may not be used to endorse or promote products
 *    derived from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE AUTHOR ``AS IS'' AND ANY EXPRESSED OR
 * IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES
 * OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED.
 * IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR ANY DIRECT, INDIRECT,
 * INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT
 * NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
 * THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF
 * THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
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

/** Consolidated map authoring, spatial play and PNG/text map rendering tools. */
public class MapTools {
	@Inject
	Engine engine;

	private se.hirt.mcp.rpg.maps.MapBundleService bundles() {
		return new se.hirt.mcp.rpg.maps.MapBundleService(engine.db(), engine.maps(), engine.mapAssets(),
				engine.db().file().toAbsolutePath().getParent());
	}

	@Tool(name = "map_preparation", description = "Staged DUNGEON/SCENE/REGION map authoring, texture assets and portable bundles (GM private; see get_instructions topic MAPS and rpg://maps/catalog). Actions: "
			+ "BEGIN (mut) {kind: DUNGEON|SCENE|REGION, proposal}; "
			+ "REVISE (mut) {preparation, changes, expected_revision?}; " + "VALIDATE (read) {preparation}; "
			+ "PUBLISH (mut) {preparation, expected_revision?}; " + "IMPORT_ASSET (mut) {source_path, repeat_ft?}; "
			+ "EXPORT_BUNDLE (read) {map}; "
			+ "IMPORT_BUNDLE (mut) {source_path, source_ref_mappings?}.", annotations = @Tool.Annotations(destructiveHint = false, openWorldHint = false))
	ToolResponse mapPreparation(
		@ToolArg(description = "BEGIN | REVISE | VALIDATE | PUBLISH | IMPORT_ASSET | EXPORT_BUNDLE | IMPORT_BUNDLE")
		String action, @ToolArg(description = "campaign:N")
		String campaign, @ToolArg(description = "Idempotency key (required for mutating actions)", required = false)
		String operation_id, @ToolArg(description = "Action parameters (see description)", required = false)
		Map<String, Object> params) {
		return ToolSupport.run("map_preparation", () -> {
			String act = ToolArgs.action("map_preparation", action, List.of("BEGIN", "REVISE", "VALIDATE", "PUBLISH",
					"IMPORT_ASSET", "EXPORT_BUNDLE", "IMPORT_BUNDLE"));
			String c = ToolArgs.reqCampaign("map_preparation", act, campaign);
			return switch (act) {
			case "BEGIN" -> {
				String op = ToolArgs.reqOp("map_preparation", act, operation_id);
				ToolArgs a = ToolArgs.of("map_preparation", act, "MAPS", params, "kind, proposal", "kind", "proposal");
				yield engine.maps().begin(op, c, a.reqStr("kind"), a.reqMap("proposal"));
			}
			case "REVISE" -> {
				String op = ToolArgs.reqOp("map_preparation", act, operation_id);
				ToolArgs a = ToolArgs.of("map_preparation", act, "MAPS", params,
						"preparation, changes, expected_revision?", "preparation", "changes", "expected_revision");
				yield engine.maps().revise(op, c, a.reqStr("preparation"), a.optLong("expected_revision"),
						a.reqMap("changes"));
			}
			case "VALIDATE" -> {
				ToolArgs a = ToolArgs.of("map_preparation", act, "MAPS", params, "preparation", "preparation");
				yield engine.maps().validate(c, a.reqStr("preparation"));
			}
			case "PUBLISH" -> {
				String op = ToolArgs.reqOp("map_preparation", act, operation_id);
				ToolArgs a = ToolArgs.of("map_preparation", act, "MAPS", params, "preparation, expected_revision?",
						"preparation", "expected_revision");
				yield engine.maps().publish(op, c, a.reqStr("preparation"), a.optLong("expected_revision"));
			}
			case "IMPORT_ASSET" -> {
				String op = ToolArgs.reqOp("map_preparation", act, operation_id);
				ToolArgs a = ToolArgs.of("map_preparation", act, "MAPS", params, "source_path, repeat_ft?",
						"source_path", "repeat_ft");
				yield importAsset(op, c, a.reqStr("source_path"), a.optDouble("repeat_ft", 5.0));
			}
			case "EXPORT_BUNDLE" -> {
				ToolArgs a = ToolArgs.of("map_preparation", act, "MAPS", params, "map", "map");
				yield bundles().exportMap(c, a.reqStr("map"));
			}
			case "IMPORT_BUNDLE" -> {
				String op = ToolArgs.reqOp("map_preparation", act, operation_id);
				ToolArgs a = ToolArgs.of("map_preparation", act, "MAPS", params, "source_path, source_ref_mappings?",
						"source_path", "source_ref_mappings");
				Map<String, String> mappings = a.optStringMap("source_ref_mappings");
				yield bundles().importBundle(op, c, Path.of(a.reqStr("source_path")),
						mappings == null ? Map.of() : mappings);
			}
			default -> throw new IllegalStateException(act);
			};
		});
	}

	@Tool(name = "map_play", description = "Tactical/world map queries, figurine placement, exploration movement, sight/sound, objects, annotations and geometry/lighting patches (see get_instructions topic MAPS). Actions: "
			+ "CONTEXT (read) {reference, audience?: PLAYER|GM, level?, detail?: SUMMARY|FULL}; "
			+ "FIND (read) {query?}; " + "PLACE_ACTORS (mut) {map, placements}; "
			+ "MOVE_ACTOR (mut) {map, character, x_ft, y_ft}; " + "OBSERVE (mut) {map}; "
			+ "PREVIEW_ACTION (read) {map, character, x_ft, y_ft, audience?: PLAYER|GM}; "
			+ "USE_TRANSITION (mut) {map, character, portal}; " + "UPDATE_OBJECT (mut) {map, key, changes, reason}; "
			+ "CORRECT_GEOMETRY (mut) {map, expected_revision, patch, reason}; "
			+ "PERCEPTION (read, GM) {map, character}; "
			+ "EMIT_SOUND (mut) {map, range_ft, description, x_ft?, y_ft?, level?, actor?}; "
			+ "ANNOTATE (mut) {map, annotation, expected_revision?}.", annotations = @Tool.Annotations(destructiveHint = false, openWorldHint = false))
	ToolResponse mapPlay(
		@ToolArg(description = "CONTEXT | FIND | PLACE_ACTORS | MOVE_ACTOR | OBSERVE | PREVIEW_ACTION | USE_TRANSITION | UPDATE_OBJECT | CORRECT_GEOMETRY | PERCEPTION | EMIT_SOUND | ANNOTATE")
		String action, @ToolArg(description = "campaign:N")
		String campaign, @ToolArg(description = "Idempotency key (required for mutating actions)", required = false)
		String operation_id, @ToolArg(description = "Action parameters (see description)", required = false)
		Map<String, Object> params) {
		return ToolSupport.run("map_play", () -> {
			String act = ToolArgs.action("map_play", action,
					List.of("CONTEXT", "FIND", "PLACE_ACTORS", "MOVE_ACTOR", "OBSERVE", "PREVIEW_ACTION",
							"USE_TRANSITION", "UPDATE_OBJECT", "CORRECT_GEOMETRY", "PERCEPTION", "EMIT_SOUND",
							"ANNOTATE"));
			String c = ToolArgs.reqCampaign("map_play", act, campaign);
			return switch (act) {
			case "CONTEXT" -> {
				ToolArgs a = ToolArgs.of("map_play", act, "MAPS", params, "reference, audience?, level?, detail?",
						"reference", "audience", "level", "detail");
				String reference = a.reqStr("reference");
				String audience = a.optStr("audience", "PLAYER");
				if (reference.startsWith("map:")) {
					var context = engine.scenes().context(c, reference, audience, a.optInt("level", 0));
					yield "FULL".equalsIgnoreCase(a.optStr("detail", "SUMMARY")) ? context : compact(context);
				}
				if (!"GM".equalsIgnoreCase(audience))
					throw RpgException.invalidArgument("Preparations and atlas authoring context require audience GM.");
				yield engine.maps().get(c, reference);
			}
			case "FIND" -> {
				ToolArgs a = ToolArgs.of("map_play", act, "MAPS", params, "query?", "query");
				yield engine.maps().find(c, a.optMapOrEmpty("query"));
			}
			case "PLACE_ACTORS" -> {
				String op = ToolArgs.reqOp("map_play", act, operation_id);
				ToolArgs a = ToolArgs.of("map_play", act, "MAPS", params, "map, placements", "map", "placements");
				yield compact(engine.scenes().place(op, c, a.reqStr("map"), a.reqMapList("placements")));
			}
			case "MOVE_ACTOR" -> {
				String op = ToolArgs.reqOp("map_play", act, operation_id);
				ToolArgs a = ToolArgs.of("map_play", act, "MAPS", params, "map, character, x_ft, y_ft", "map",
						"character", "x_ft", "y_ft");
				yield engine.scenes().move(op, c, a.reqStr("map"), a.reqStr("character"), a.reqDouble("x_ft"),
						a.reqDouble("y_ft"));
			}
			case "OBSERVE" -> {
				String op = ToolArgs.reqOp("map_play", act, operation_id);
				ToolArgs a = ToolArgs.of("map_play", act, "MAPS", params, "map", "map");
				yield compact(engine.scenes().observe(op, c, a.reqStr("map")));
			}
			case "PREVIEW_ACTION" -> {
				ToolArgs a = ToolArgs.of("map_play", act, "MAPS", params, "map, character, x_ft, y_ft, audience?",
						"map", "character", "x_ft", "y_ft", "audience");
				yield engine.scenes().preview(c, a.reqStr("map"), a.reqStr("character"), a.reqDouble("x_ft"),
						a.reqDouble("y_ft"), a.optStr("audience", "PLAYER"));
			}
			case "USE_TRANSITION" -> {
				String op = ToolArgs.reqOp("map_play", act, operation_id);
				ToolArgs a = ToolArgs.of("map_play", act, "MAPS", params, "map, character, portal", "map", "character",
						"portal");
				yield compact(
						engine.scenes().transit(op, c, a.reqStr("map"), a.reqStr("character"), a.reqStr("portal")));
			}
			case "UPDATE_OBJECT" -> {
				String op = ToolArgs.reqOp("map_play", act, operation_id);
				ToolArgs a = ToolArgs.of("map_play", act, "MAPS", params, "map, key, changes, reason", "map", "key",
						"changes", "reason");
				yield engine.scenes().changeObject(op, c, a.reqStr("map"), a.reqStr("key"), a.reqMap("changes"),
						a.reqStr("reason"));
			}
			case "CORRECT_GEOMETRY" -> {
				String op = ToolArgs.reqOp("map_play", act, operation_id);
				ToolArgs a = ToolArgs.of("map_play", act, "MAPS", params, "map, expected_revision, patch, reason",
						"map", "expected_revision", "patch", "reason");
				yield engine.maps().correctGeometry(op, c, a.reqStr("map"), a.reqLong("expected_revision"),
						a.reqMap("patch"), a.reqStr("reason"));
			}
			case "PERCEPTION" -> {
				ToolArgs a = ToolArgs.of("map_play", act, "MAPS", params, "map, character", "map", "character");
				yield engine.scenes().perception(c, a.reqStr("map"), a.reqStr("character"));
			}
			case "EMIT_SOUND" -> {
				String op = ToolArgs.reqOp("map_play", act, operation_id);
				ToolArgs a = ToolArgs.of("map_play", act, "MAPS", params,
						"map, range_ft, description, x_ft?, y_ft?, level?, actor?", "map", "range_ft", "description",
						"x_ft", "y_ft", "level", "actor");
				yield engine.scenes().sound(op, c, a.reqStr("map"), a.optDouble("x_ft"), a.optDouble("y_ft"),
						a.optInt("level"), a.reqDouble("range_ft"), a.reqStr("description"), a.optStr("actor"));
			}
			case "ANNOTATE" -> {
				String op = ToolArgs.reqOp("map_play", act, operation_id);
				ToolArgs a = ToolArgs.of("map_play", act, "MAPS", params, "map, annotation, expected_revision?", "map",
						"annotation", "expected_revision");
				yield engine.maps().annotate(op, c, a.reqStr("map"), a.optLong("expected_revision"),
						a.reqMap("annotation"));
			}
			default -> throw new IllegalStateException(act);
			};
		});
	}

	@Tool(name = "render_map", description = "READ-ONLY. Render map:N (PLAYER or GM) or draft preview preparation:N (requires audience GM) as PNG plus text and marker targets. "
			+ "options: grid_ft 5|10, max_pixels <=2048, style DEFAULT|PARCHMENT|HIGH_CONTRAST, technical_text, material_overrides. include_image:false returns text only.", annotations = @Tool.Annotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false))
	ToolResponse render(@ToolArg(description = "campaign:N")
	String campaign, @ToolArg(description = "map:N or preparation:N (draft preview requires audience GM)")
	String map, @ToolArg(description = "PLAYER (default) or GM")
	Optional<String> audience, @ToolArg(description = "Map level (default 0)")
	Optional<Integer> level,
		@ToolArg(description = "Render options (grid_ft, max_pixels, style, technical_text, material_overrides)")
		Optional<Map<String, Object>> options,
		@ToolArg(description = "true (default) for PNG + text; false for text only")
		Optional<Boolean> include_image) {
		String selectedAudience = audience.orElse("PLAYER");
		int selectedLevel = level.orElse(0);
		if (!include_image.orElse(true))
			return ToolSupport.run("render_map", () -> {
				var context = renderContext(campaign, map, selectedAudience, selectedLevel);
				var result = new LinkedHashMap<>(compact(context));
				result.put("text", engine.mapRenderer().describe(SceneService.map(context.get("view")),
						renderOptions(selectedAudience, options.orElse(Map.of()))));
				result.put("markers", markers(SceneService.map(context.get("view")), options.orElse(Map.of())));
				return result;
			});
		final byte[][] image = {null};
		java.util.function.Supplier<Map<String, Object>> body = () -> {
			Map<String, Object> context = renderContext(campaign, map, selectedAudience, selectedLevel);
			var rendered = engine.mapRenderer().render(SceneService.map(context.get("view")),
					renderOptions(selectedAudience, options.orElse(Map.of())),
					textures(campaign, SceneService.map(context.get("view")), options.orElse(Map.of())));
			image[0] = rendered.png();
			return Map.of("map", map, "mime_type", "image/png", "width", rendered.width(), "height", rendered.height(),
					"transform", rendered.transform(), "text", rendered.text(), "markers",
					markers(SceneService.map(context.get("view")), options.orElse(Map.of())), "meta",
					context.get("meta"));
		};
		return ToolSupport.image("render_map", body, () -> image[0]);
	}

	private Map<String, Object> importAsset(String operationId, String campaign, String sourcePath, double repeatFt) {
		long cid = Ref.id(campaign, Ref.CAMPAIGN);
		var mutation = Database.Mutation.of("import_map_asset", cid, operationId, "GM",
				Map.of("source_path", sourcePath, "repeat_ft", repeatFt));
		var replay = engine.db().replay(mutation);
		if (replay.isPresent())
			return replay.get();
		engine.db().read(tx -> {
			Harness.requireMutation(tx, campaign, "import_map_asset");
			return null;
		});
		var a = engine.mapAssets().importPng(cid, Path.of(sourcePath), repeatFt);
		return engine.db().mutate(mutation, tx -> {
			var c = Harness.requireMutation(tx, campaign, "import_map_asset");
			var existing = tx.queryOne("SELECT * FROM map_asset WHERE campaign_id=? AND content_hash=? AND repeat_ft=?",
					cid, a.id(), a.repeatFeet());
			long id = existing.isPresent() ? existing.get().id()
					: tx.insert("map_asset", Map.of("campaign_id", cid, "content_hash", a.id(), "mime_type",
							"image/png", "width", a.width(), "height", a.height(), "repeat_ft", a.repeatFeet()));
			var metadata = new LinkedHashMap<>(a.metadata());
			metadata.put("ref", Ref.of(Ref.ASSET, id));
			return Map.of("asset", metadata, "meta", Harness.meta(c, List.of()));
		});
	}

	private Map<String, Object> renderContext(String campaign, String ref, String audience, int level) {
		if (ref != null && ref.startsWith("preparation:")) {
			if (!"GM".equalsIgnoreCase(audience))
				throw RpgException.invalidArgument("Previewing a draft preparation requires audience GM.");
			return engine.maps().previewDocument(campaign, ref, level);
		}
		return engine.scenes().context(campaign, ref, audience, level);
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
		for (String layer : List.of("rooms", "walls", "doors", "objects", "decor", "lights", "features", "annotations",
				"actors")) {
			Object value = view.get(layer);
			if (value instanceof List<?> list) {
				view.put(layer + "_count", list.size());
				if (layer.equals("rooms") && "GM".equals(view.get("audience"))) {
					view.put("rooms", SceneService.maps(value).stream().limit(100).map(room -> {
						var summary = new LinkedHashMap<String, Object>();
						for (String key : List.of("key", "name", "location_ref", "level", "illumination", "material"))
							if (room.containsKey(key))
								summary.put(key, room.get(key));
						summary.put("effective_illumination",
								room.getOrDefault("illumination", view.getOrDefault("illumination", "BRIGHT")));
						return summary;
					}).toList());
					if (list.size() > 100)
						view.put("rooms_truncated", true);
				} else if (layer.equals("rooms") || layer.equals("walls") || layer.equals("decor")
						|| layer.equals("lights"))
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
		for (String layer : List.of("rooms", "objects", "decor", "features"))
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
}
