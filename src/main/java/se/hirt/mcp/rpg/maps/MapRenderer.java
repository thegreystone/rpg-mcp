/*
 * Copyright (C) 2026 Marcus Hirt
 * SPDX-License-Identifier: BSD-3-Clause
 */
package se.hirt.mcp.rpg.maps;

import se.hirt.mcp.rpg.protocol.RpgException;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Deterministic, bounded PNG views of an already audience-filtered scene. This renderer cannot
 * mutate geometry, calculate game visibility or read arbitrary artwork paths. The caller supplies
 * the saved knowledge projection and any registered textures.
 */
public final class MapRenderer {

	public static final int MAX_OUTPUT_DIMENSION = 2048;
	public static final String VERSION = "raster-6";
	private static final int MAX_FEATURES = 65_536;
	private static final int MAX_VERTICES = 8192;
	private static final Map<Character, String> GLYPHS = glyphs();

	public record Rendered(byte[] png, int width, int height, Map<String, Object> transform, String text) {
	}

	public Rendered render(Map<String, Object> scene, Map<String, Object> options) {
		return render(scene, options, Map.of());
	}

	public Rendered render(
		Map<String, Object> scene, Map<String, Object> options, Map<String, AssetService.Texture> textures) {
		if (scene == null) {
			throw RpgException.invalidArgument("A saved, filtered map scene is required.");
		}
		Map<String, Object> opts = options == null ? Map.of() : options;
		double[] bounds = numbers(scene.get("bounds_ft"), 4);
		if (bounds == null || Math.abs(bounds[0]) > 1_000_000_000_000d || Math.abs(bounds[1]) > 1_000_000_000_000d
				|| bounds[2] <= 0 || bounds[3] <= 0 || bounds[2] > 10_000_000 || bounds[3] > 10_000_000) {
			throw RpgException
					.invalidArgument("bounds_ft must contain finite [x,y,width,height] with positive dimensions.");
		}
		int maximum = (int) number(opts.get("max_pixels"), 1024);
		if (maximum < 64 || maximum > MAX_OUTPUT_DIMENSION) {
			throw RpgException.invalidArgument("max_pixels must be between 64 and 2048.");
		}
		double grid = number(opts.get("grid_ft"), number(scene.get("grid_ft"), 5));
		if (grid != 5 && grid != 10) {
			throw RpgException.invalidArgument("grid_ft must be 5 or 10.");
		}
		String style = String.valueOf(opts.getOrDefault("style", "DEFAULT")).toUpperCase(Locale.ROOT);
		if (!Set.of("DEFAULT", "PARCHMENT", "HIGH_CONTRAST").contains(style)) {
			throw RpgException.invalidArgument("style must be DEFAULT, PARCHMENT or HIGH_CONTRAST.");
		}
		Object level = renderLevel(scene, opts);
		List<Map<String, Object>> rooms = entries(scene.get("rooms"));
		var canvas = new Canvas(bounds, maximum, style, textures == null ? Map.of() : textures);
		boolean regional = Set.of("REGION", "SETTLEMENT")
				.contains(String.valueOf(scene.get("kind")).toUpperCase(Locale.ROOT));
		canvas.legendAtTop = regional;
		canvas.avoidLabelOverlaps = true;
		boolean showGrid = Boolean.TRUE.equals(opts.get("show_grid"))
				|| !regional && !Boolean.FALSE.equals(opts.get("show_grid"));
		showGrid = showGrid && grid * canvas.scale >= 3;
		Fog fog = new Fog(object(scene.get("visibility")), bounds);
		List<String> legend = legend(scene, level, fog);
		canvas.reserveLegend(legend);
		var visibleActors = visibleActors(scene, opts);
		for (Map<String, Object> actor : visibleActors)
			canvas.reserveActor(actor);
		for (Map<String, Object> feature : entries(scene.get("features"))) {
			double[] point = numbers(feature.get("point_ft"), 2);
			if (point != null && onLevel(feature, level) && !Boolean.TRUE.equals(feature.get("hidden"))
					&& fog.known(point[0], point[1]))
				canvas.reservePoint(point);
		}
		String background = String.valueOf(scene.getOrDefault("background_material", regional ? "GRASS" : "VOID"));
		canvas.background(background);
		for (Map<String, Object> room : rooms) {
			if (onLevel(room, level)) {
				canvas.area(room, String.valueOf(room.getOrDefault("material", "STONE")));
			}
		}
		for (Map<String, Object> feature : entries(scene.get("features"))) {
			if (onLevel(feature, level)) {
				String kind = String.valueOf(feature.getOrDefault("kind", "GRASS"));
				canvas.area(feature, String.valueOf(feature.getOrDefault("material", kind)));
			}
		}
		if (regional)
			for (Map<String, Object> feature : entries(scene.get("features")))
				if (onLevel(feature, level))
					canvas.terrainSymbols(feature);
		// Paint every road casing before any road core. Observed paths are clipped into cells;
		// interleaved casing/core strokes would draw false end caps across each neighboring piece.
		for (String layer : List.of("features", "roads"))
			for (Map<String, Object> path : entries(scene.get(layer))) {
				String kind = String.valueOf(path.getOrDefault("kind", "ROAD")).toUpperCase(Locale.ROOT);
				if (onLevel(path, level) && !kind.contains("RIVER") && !ridge(kind))
					canvas.path(path, 0xff795632, true);
			}
		for (Map<String, Object> feature : entries(scene.get("features"))) {
			if (onLevel(feature, level)) {
				String kind = String.valueOf(feature.getOrDefault("kind", "ROAD")).toUpperCase(Locale.ROOT);
				canvas.path(feature, kind.contains("RIVER") ? 0xff326f94 : ridge(kind) ? 0xff645c4d : 0xff795632,
						false);
			}
		}
		for (Map<String, Object> path : entries(scene.get("roads"))) {
			if (onLevel(path, level)) {
				canvas.path(path, 0xff795632, false);
			}
		}
		for (Map<String, Object> path : entries(scene.get("rivers"))) {
			if (onLevel(path, level)) {
				canvas.path(path, 0xff326f94, false);
			}
		}
		if (showGrid && grid * canvas.scale >= 3) {
			canvas.grid(grid);
		}
		for (Map<String, Object> wall : entries(scene.get("walls"))) {
			if (onLevel(wall, level) && !"DESTROYED".equalsIgnoreCase(String.valueOf(wall.get("state")))) {
				canvas.segment(wall, canvas.contrast ? 0xff050505 : 0xff302f2b, number(wall.get("width_ft"), .45));
			}
		}
		for (Map<String, Object> door : entries(scene.get("doors"))) {
			if (!onLevel(door, level) || Boolean.TRUE.equals(door.get("hidden"))) {
				continue;
			}
			String state = String.valueOf(door.getOrDefault("state", "CLOSED")).toUpperCase(Locale.ROOT);
			int color = state.equals("OPEN") || state.equals("DESTROYED") ? 0xffaac28a : 0xff895b37;
			canvas.segment(door, color, .8);
			double[] center = position(door);
			if (center != null)
				canvas.symbol(center, passageGlyph(door));
		}
		for (Map<String, Object> portal : entries(scene.get("portals"))) {
			double[] point = portalPosition(portal, level);
			if (point != null && !Boolean.TRUE.equals(portal.get("hidden")) && fog.known(point[0], point[1]))
				canvas.symbol(point, portalGlyph(portal));
		}
		for (Map<String, Object> placed : entries(scene.get("objects"))) {
			if (onLevel(placed, level) && !Boolean.TRUE.equals(placed.get("hidden"))) {
				canvas.placed(placed);
			}
		}
		boolean labels = !Boolean.FALSE.equals(opts.get("show_labels"));
		for (Map<String, Object> feature : entries(scene.get("features")))
			if (onLevel(feature, level) && !Boolean.TRUE.equals(feature.get("hidden"))) {
				double[] point = position(feature);
				if (point == null || !fog.known(point[0], point[1]))
					continue;
				if (numbers(feature.get("point_ft"), 2) != null)
					canvas.featurePoint(feature, labels);
				else if (labels && feature.get("name") != null)
					canvas.label(point[0], point[1], feature.get("name").toString(), 0xff182015);
			}
		if (labels) {
			for (Map<String, Object> room : rooms) {
				if (!onLevel(room, level) || regional && representedRoom(scene, room)) {
					continue;
				}
				double[] center = position(room);
				if (center != null && room.get("name") != null && fog.known(center[0], center[1])) {
					canvas.label(center[0], center[1], String.valueOf(room.get("name")), 0xff181818);
				}
			}
		}
		int marker = 0;
		for (Map<String, Object> actor : visibleActors) {
			marker++;
			if (!Boolean.TRUE.equals(actor.get("party_member")))
				canvas.actor(actor, marker, labels);
		}
		canvas.fog(fog);
		// Party positions are already included by the permitted projection. Knowing where a
		// figurine stands does not illuminate its surroundings or reveal another creature.
		for (int i = 0; i < visibleActors.size(); i++)
			if (Boolean.TRUE.equals(visibleActors.get(i).get("party_member")))
				canvas.actor(visibleActors.get(i), i + 1, labels);
		canvas.legend(legend);
		if (regional)
			canvas.scaleBar();
		var transform = new LinkedHashMap<String, Object>();
		transform.put("origin_ft", List.of(bounds[0], bounds[1]));
		transform.put("bounds_ft", List.of(bounds[0], bounds[1], bounds[2], bounds[3]));
		transform.put("pixels_per_foot", canvas.scale);
		transform.put("pixel_origin", "TOP_LEFT");
		transform.put("world_axes", "X_EAST_Y_NORTH");
		transform.put("world_to_pixel", List.of(canvas.scale, 0, -bounds[0] * canvas.scale, 0, -canvas.scale,
				(bounds[1] + bounds[3]) * canvas.scale));
		transform.put("grid_visible", showGrid);
		if (showGrid)
			transform.put("grid_ft", grid);
		transform.put("renderer_version", VERSION);
		transform.put("style", style);
		if (level != null) {
			transform.put("level", level);
		}
		byte[] png = PngCodec.encode(new PngCodec.Raster(canvas.width, canvas.height, canvas.pixels));
		return new Rendered(png, canvas.width, canvas.height, Map.copyOf(transform), summary(scene, visibleActors,
				bounds, grid, level, fog, regional, showGrid, !Boolean.FALSE.equals(opts.get("technical_text"))));
	}

	/** Text-only clients never need to allocate a raster or load texture files. */
	public String describe(Map<String, Object> scene, Map<String, Object> options) {
		var opts = options == null ? Map.<String, Object> of() : options;
		double[] bounds = numbers(scene.get("bounds_ft"), 4);
		if (bounds == null)
			throw RpgException.invalidArgument("Map bounds are required.");
		Object level = renderLevel(scene, opts);
		Fog fog = new Fog(object(scene.get("visibility")), bounds);
		boolean regional = Set.of("REGION", "SETTLEMENT")
				.contains(String.valueOf(scene.get("kind")).toUpperCase(Locale.ROOT));
		double grid = number(opts.get("grid_ft"), number(scene.get("grid_ft"), 5));
		if (grid != 5 && grid != 10)
			throw RpgException.invalidArgument("grid_ft must be 5 or 10.");
		boolean showGrid = Boolean.TRUE.equals(opts.get("show_grid"))
				|| !regional && !Boolean.FALSE.equals(opts.get("show_grid"));
		return summary(scene, visibleActors(scene, opts), bounds, grid, level, fog, regional, showGrid,
				!Boolean.FALSE.equals(opts.get("technical_text")));
	}

	/** One permitted, ordered marker manifest for PNG, text and tool control metadata. */
	public static List<Map<String, Object>> visibleActors(Map<String, Object> scene, Map<String, Object> options) {
		var opts = options == null ? Map.<String, Object> of() : options;
		Object level = renderLevel(scene, opts);
		double[] bounds = numbers(scene.get("bounds_ft"), 4);
		if (bounds == null)
			throw RpgException.invalidArgument("Map bounds are required.");
		Fog fog = new Fog(object(scene.get("visibility")), bounds);
		return entries(scene.get("actors")).stream()
				.filter(a -> onLevel(a, level) && !Boolean.TRUE.equals(a.get("hidden")) && position(a) != null
						&& (Boolean.TRUE.equals(a.get("party_member")) || fog.visible(position(a)[0], position(a)[1])))
				.toList();
	}

	private static Object renderLevel(Map<String, Object> scene, Map<String, Object> options) {
		Object level = options.getOrDefault("level", scene.get("level"));
		return level != null ? level : entries(scene.get("rooms")).stream().filter(r -> r.containsKey("level"))
				.map(r -> r.get("level")).findFirst().orElse(null);
	}

	private static String summary(
		Map<String, Object> scene, List<Map<String, Object>> actors, double[] bounds, double grid, Object level,
		Fog fog, boolean regional, boolean showGrid, boolean technical) {
		var text = new StringBuilder(String.valueOf(scene.getOrDefault("name", "Map")));
		boolean miles = regional && Math.max(bounds[2], bounds[3]) >= 5280;
		text.append(": ").append(format(bounds[2] / (miles ? 5280 : 1))).append(" by ")
				.append(format(bounds[3] / (miles ? 5280 : 1)));
		text.append(miles ? " mi; north is up; " : " ft; north is up; ");
		text.append(showGrid ? format(grid) + " ft grid."
				: regional ? "ungridded view with a physical scale bar." : "ungridded view.");
		if (technical)
			text.append(" Coordinates are feet east/north.");
		if (technical && !regional)
			text.append(" Movement targets use the southwest footprint origin on the 5 ft lattice.");
		int count = 0;
		double[] observer = !technical && "PLAYER".equalsIgnoreCase(String.valueOf(scene.get("audience")))
				? actors.stream().filter(a -> Boolean.TRUE.equals(a.get("party_member"))).map(MapRenderer::position)
						.findFirst().orElse(null)
				: null;
		String observerName = observer == null ? null
				: actors.stream().filter(a -> Boolean.TRUE.equals(a.get("party_member"))).findFirst().orElseThrow()
						.getOrDefault("name", "the party").toString();
		Set<String> describedRoutes = new HashSet<>();
		Set<String> describedPassages = new HashSet<>();
		for (String layer : List.of("rooms", "doors", "portals", "objects", "features", "roads", "rivers")) {
			for (Map<String, Object> entry : entries(scene.get(layer))) {
				double[] pos = layer.equals("portals") ? portalPosition(entry, level) : position(entry);
				Object named = entry.get("name") != null ? entry.get("name") : entry.get("label");
				String name = named == null
						? layer.equals("doors") ? passageName(entry) : layer.equals("roads") ? "Road"
								: layer.equals("rivers") ? "River" : layer.equals("portals") ? "Stairs or portal" : ""
						: named.toString();
				if (layer.equals("objects"))
					pos = objectPosition(entry);
				if (count >= 40 || pos == null || !onLevel(entry, level) || !knownEntry(entry, pos, fog)
						|| Boolean.TRUE.equals(entry.get("hidden")) || name.isBlank()
						|| regional && layer.equals("rooms") && representedRoom(scene, entry)) {
					continue;
				}
				if (Set.of("roads", "rivers").contains(layer) && !describedRoutes.add(layer + ":" + name))
					continue;
				if (layer.equals("doors") && !describedPassages
						.add(String.valueOf(entry.getOrDefault("key", name + java.util.Arrays.toString(pos)))))
					continue;
				text.append("\n").append(name);
				if (technical)
					text.append(" at (").append(format(pos[0])).append(", ").append(format(pos[1])).append(')');
				else
					text.append(relativePosition(pos, bounds, observer, observerName));
				if (entry.get("state") != null) {
					text.append(": ").append(entry.get("state"));
				}
				if (Boolean.TRUE.equals(entry.get("last_known")))
					text.append(" (last seen)");
				text.append('.');
				count++;
			}
		}
		for (int i = 0; i < Math.min(actors.size(), 40); i++) {
			Map<String, Object> actor = actors.get(i);
			double[] pos = position(actor);
			text.append("\nMarker ").append(actor.getOrDefault("label", Integer.toString(i + 1))).append(": ")
					.append(actor.getOrDefault("name", "Character"));
			if (technical && actor.get("character_ref") != null) {
				text.append(" [").append(actor.get("character_ref")).append(']');
			}
			if (technical)
				text.append(" at (").append(format(pos[0])).append(", ").append(format(pos[1])).append(')');
			else
				text.append(relativePosition(pos, bounds, observer, observerName));
			if (technical && actor.containsKey("x_ft") && actor.containsKey("y_ft"))
				text.append("; move origin (").append(format(number(actor.get("x_ft"), 0))).append(", ")
						.append(format(number(actor.get("y_ft"), 0))).append(')');
			text.append('.');
		}
		List<String> legend = legend(scene, level, fog);
		if (!legend.isEmpty())
			text.append("\nLegend: ").append(String.join("; ", legend)).append('.');
		return text.toString();
	}

	private static boolean representedRoom(Map<String, Object> scene, Map<String, Object> room) {
		return room.get("location_ref") != null && entries(scene.get("features")).stream()
				.anyMatch(f -> room.get("location_ref").equals(f.get("graph_ref")));
	}

	private static String relativePosition(double[] pos, double[] bounds, double[] observer, String name) {
		if (observer == null)
			return " in the " + direction(pos, bounds);
		double dx = pos[0] - observer[0], dy = pos[1] - observer[1];
		if (Math.max(Math.abs(dx), Math.abs(dy)) <= 2.5)
			return " here";
		String vertical = dy < -2.5 ? "south" : dy > 2.5 ? "north" : "",
				horizontal = dx < -2.5 ? "west" : dx > 2.5 ? "east" : "";
		return " " + vertical + (vertical.isEmpty() || horizontal.isEmpty() ? "" : "-") + horizontal + " of " + name;
	}

	private static String doorGlyph(String state) {
		return switch (state) {
		case "OPEN" -> "O";
		case "LOCKED" -> "L";
		case "DESTROYED" -> "D";
		case "SECRET" -> "H";
		case "BLOCKED" -> "B";
		case "BREACHED" -> "R";
		default -> "C";
		};
	}

	private static String portalGlyph(Map<String, Object> portal) {
		return String.valueOf(portal.getOrDefault("kind", "STAIRS")).toUpperCase(Locale.ROOT).contains("PORTAL") ? "P"
				: "S";
	}

	private static double[] portalPosition(Map<String, Object> portal, Object level) {
		for (String side : List.of("from", "to"))
			if (level == null || String.valueOf(level).equals(String.valueOf(portal.get(side + "_level")))) {
				double[] point = numbers(portal.get(side + "_position_ft"), 2);
				if (point != null)
					return point;
			}
		return null;
	}

	private static List<String> legend(Map<String, Object> scene, Object level, Fog fog) {
		var result = new java.util.LinkedHashSet<String>();
		if (Set.of("REGION", "SETTLEMENT").contains(String.valueOf(scene.get("kind")))) {
			result.add("Square=town / ring=city");
			result.add("Roof=inn / diamond=site");
			result.add("Brown=road / blue=water");
			result.add("Gray=ridge / trees=forest");
		}
		for (var door : entries(scene.get("doors"))) {
			double[] pos = position(door);
			if (!onLevel(door, level) || Boolean.TRUE.equals(door.get("hidden")) || pos == null
					|| !knownEntry(door, pos, fog))
				continue;
			String state = String.valueOf(door.getOrDefault("state", "CLOSED")).toUpperCase(Locale.ROOT);
			result.add(passageGlyph(door) + "=" + state.toLowerCase(Locale.ROOT) + " "
					+ passageName(door).toLowerCase(Locale.ROOT));
		}
		for (var portal : entries(scene.get("portals"))) {
			double[] pos = portalPosition(portal, level);
			if (!Boolean.TRUE.equals(portal.get("hidden")) && pos != null && fog.known(pos[0], pos[1]))
				result.add(portalGlyph(portal).equals("P") ? "P=portal" : "S=stairs");
		}
		return List.copyOf(result);
	}

	private static boolean ridge(String kind) {
		return kind.contains("RIDGE") || kind.contains("MOUNTAIN") || kind.contains("HILL");
	}

	private static String passageName(Map<String, Object> door) {
		return "PASSAGE".equalsIgnoreCase(String.valueOf(door.get("kind"))) ? "Passage" : "Door";
	}

	private static String passageGlyph(Map<String, Object> door) {
		String state = String.valueOf(door.getOrDefault("state", "CLOSED")).toUpperCase(Locale.ROOT);
		return "Passage".equals(passageName(door)) && "OPEN".equals(state) ? "A" : doorGlyph(state);
	}

	private static boolean knownEntry(Map<String, Object> entry, double[] pos, Fog fog) {
		return Boolean.TRUE.equals(entry.get("observed")) || fog.known(pos[0], pos[1]);
	}

	private static double[] objectPosition(Map<String, Object> value) {
		double[] explicit = numbers(value.get("position_ft"), 2);
		if (explicit != null)
			return explicit;
		double[] rect = numbers(value.get("rect_ft"), 4);
		if (rect != null)
			return new double[] {rect[0] + rect[2] / 2, rect[1] + rect[3] / 2};
		if (value.containsKey("x_ft") && value.containsKey("y_ft"))
			return new double[] {number(value.get("x_ft"), 0) + number(value.get("width_ft"), 5) / 2,
					number(value.get("y_ft"), 0) + number(value.get("height_ft"), 5) / 2};
		return position(value);
	}

	private static String direction(double[] position, double[] bounds) {
		double east = (position[0] - bounds[0]) / bounds[2], north = (position[1] - bounds[1]) / bounds[3];
		String vertical = north < .33 ? "south" : north > .67 ? "north" : "";
		String horizontal = east < .33 ? "west" : east > .67 ? "east" : "";
		return vertical.isEmpty() && horizontal.isEmpty() ? "center" : vertical + horizontal;
	}

	private static String format(double n) {
		return n == Math.rint(n) ? Long.toString((long) n) : String.format(Locale.ROOT, "%.2f", n);
	}

	private static boolean onLevel(Map<String, Object> value, Object level) {
		return level == null || !value.containsKey("level")
				|| String.valueOf(level).equals(String.valueOf(value.get("level")));
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> object(Object value) {
		return value instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
	}

	private static List<Map<String, Object>> entries(Object value) {
		if (!(value instanceof List<?> list)) {
			return List.of();
		}
		if (list.size() > MAX_FEATURES) {
			throw RpgException.invalidArgument("Render a bounded scene with at most 65536 entries per layer.");
		}
		var result = new ArrayList<Map<String, Object>>();
		for (Object item : list) {
			if (!(item instanceof Map<?, ?>)) {
				throw RpgException.invalidArgument("Map scene layers must contain objects.");
			}
			result.add(object(item));
		}
		return result;
	}

	private static double number(Object value, double fallback) {
		if (!(value instanceof Number n)) {
			return fallback;
		}
		double result = n.doubleValue();
		if (!Double.isFinite(result)) {
			throw RpgException.invalidArgument("Map coordinates and dimensions must be finite.");
		}
		return result;
	}

	private static double[] numbers(Object value, int length) {
		if (!(value instanceof List<?> list) || list.size() != length) {
			return null;
		}
		double[] result = new double[length];
		for (int i = 0; i < length; i++) {
			if (!(list.get(i) instanceof Number)) {
				throw RpgException.invalidArgument("Map coordinates must be numeric.");
			}
			result[i] = number(list.get(i), 0);
		}
		return result;
	}

	private static double[] position(Map<String, Object> value) {
		double[] featurePoint = numbers(value.get("point_ft"), 2);
		if (featurePoint != null)
			return featurePoint;
		double[] point = numbers(value.get("position_ft"), 2);
		if (point != null) {
			return point;
		}
		if (value.containsKey("x_ft") && value.containsKey("y_ft")) {
			return new double[] {number(value.get("x_ft"), 0), number(value.get("y_ft"), 0)};
		}
		double[] rect = numbers(value.get("rect_ft"), 4);
		if (rect != null) {
			return new double[] {rect[0] + rect[2] / 2, rect[1] + rect[3] / 2};
		}
		double[] a = numbers(value.get("from_ft"), 2), b = numbers(value.get("to_ft"), 2);
		if (a != null && b != null) {
			return new double[] {(a[0] + b[0]) / 2, (a[1] + b[1]) / 2};
		}
		List<double[]> polygon = points(value.get("polygon_ft"));
		if (!polygon.isEmpty()) {
			double x = 0, y = 0;
			for (double[] vertex : polygon) {
				x += vertex[0];
				y += vertex[1];
			}
			x /= polygon.size();
			y /= polygon.size();
			if (inside(polygon, x, y))
				return new double[] {x, y};
			return polygon.getFirst();
		}
		List<double[]> path = points(value.getOrDefault("path_ft", value.get("points_ft")));
		if (!path.isEmpty()) {
			double length = 0;
			for (int i = 1; i < path.size(); i++)
				length += Math.hypot(path.get(i)[0] - path.get(i - 1)[0], path.get(i)[1] - path.get(i - 1)[1]);
			double remaining = length / 2;
			for (int i = 1; i < path.size(); i++) {
				double[] from = path.get(i - 1), to = path.get(i);
				double part = Math.hypot(to[0] - from[0], to[1] - from[1]);
				if (part > 0 && remaining <= part)
					return new double[] {from[0] + (to[0] - from[0]) * remaining / part,
							from[1] + (to[1] - from[1]) * remaining / part};
				remaining -= part;
			}
			return path.getFirst();
		}
		return null;
	}

	private static List<double[]> points(Object value) {
		if (!(value instanceof List<?> list)) {
			return List.of();
		}
		if (list.size() > MAX_VERTICES) {
			throw RpgException.invalidArgument("A rendered path/polygon may have at most 8192 vertices.");
		}
		var result = new ArrayList<double[]>();
		for (Object point : list) {
			double[] pair = numbers(point, 2);
			if (pair == null) {
				throw RpgException.invalidArgument("A path/polygon needs [x,y] coordinates.");
			}
			result.add(pair);
		}
		return result;
	}

	private static final class Canvas {
		final int width, height;
		final int[] pixels;
		final double[] bounds;
		final double scale;
		final String style;
		final boolean contrast;
		final Map<String, AssetService.Texture> textures;
		final List<int[]> labelBounds = new ArrayList<>();
		final Map<String, List<double[]>> namedLabels = new LinkedHashMap<>();
		boolean avoidLabelOverlaps;
		boolean legendAtTop;
		long work;

		Canvas(double[] bounds, int maximum, String style, Map<String, AssetService.Texture> textures) {
			this.bounds = bounds;
			this.scale = (maximum - 1d) / Math.max(bounds[2], bounds[3]);
			this.width = Math.min(maximum, Math.max(1, (int) Math.ceil(bounds[2] * scale) + 1));
			this.height = Math.min(maximum, Math.max(1, (int) Math.ceil(bounds[3] * scale) + 1));
			this.pixels = new int[width * height];
			this.style = style;
			this.contrast = style.equals("HIGH_CONTRAST");
			this.textures = textures;
		}

		double px(double x) {
			return (x - bounds[0]) * scale;
		}

		double py(double y) {
			return (bounds[1] + bounds[3] - y) * scale;
		}

		double wx(int x) {
			return bounds[0] + x / scale;
		}

		double wy(int y) {
			return bounds[1] + bounds[3] - y / scale;
		}

		void background(String material) {
			for (int y = 0; y < height; y++) {
				for (int x = 0; x < width; x++) {
					pixels[y * width + x] = material(material, wx(x), wy(y));
				}
			}
		}

		void area(Map<String, Object> area, String material) {
			double[] rect = numbers(area.get("rect_ft"), 4);
			List<double[]> outer = points(area.get("polygon_ft"));
			if (outer.isEmpty() && rect == null) {
				return;
			}
			if (outer.isEmpty()) {
				if (rect[2] <= 0 || rect[3] <= 0) {
					throw RpgException.invalidArgument("Room rectangles need positive dimensions.");
				}
				outer = List.of(new double[] {rect[0], rect[1]}, new double[] {rect[0] + rect[2], rect[1]},
						new double[] {rect[0] + rect[2], rect[1] + rect[3]}, new double[] {rect[0], rect[1] + rect[3]});
			}
			if (outer.size() < 3) {
				throw RpgException.invalidArgument("A room polygon needs at least three vertices.");
			}
			var holes = new ArrayList<List<double[]>>();
			if (area.get("holes_ft") instanceof List<?> rings) {
				if (rings.size() > 128) {
					throw RpgException.invalidArgument("A polygon may have at most 128 holes per render.");
				}
				for (Object ring : rings) {
					List<double[]> hole = points(ring);
					if (hole.size() < 3) {
						throw RpgException.invalidArgument("Polygon holes need at least three vertices.");
					}
					holes.add(hole);
				}
			}
			double minX = Double.POSITIVE_INFINITY, maxX = Double.NEGATIVE_INFINITY;
			double minY = Double.POSITIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY;
			String areaKind = String.valueOf(area.getOrDefault("kind", "")).toUpperCase(Locale.ROOT);
			boolean relief = areaKind.contains("MOUNTAIN") || areaKind.contains("HILL") || areaKind.contains("RIDGE");
			for (double[] p : outer) {
				minX = Math.min(minX, px(p[0]));
				maxX = Math.max(maxX, px(p[0]));
				minY = Math.min(minY, py(p[1]));
				maxY = Math.max(maxY, py(p[1]));
			}
			long vertices = outer.size();
			for (List<double[]> hole : holes) {
				vertices += hole.size();
			}
			work += (long) (clamp(maxX, 0, width - 1) - clamp(minX, 0, width - 1) + 1)
					* (clamp(maxY, 0, height - 1) - clamp(minY, 0, height - 1) + 1) * vertices;
			if (work > 160_000_000) {
				throw RpgException.invalidArgument(
						"This scene is too complex at the requested resolution; use a smaller window or max_pixels.");
			}
			for (int y = clamp(minY, 0, height - 1); y <= clamp(maxY, 0, height - 1); y++) {
				for (int x = clamp(minX, 0, width - 1); x <= clamp(maxX, 0, width - 1); x++) {
					double worldX = wx(x), worldY = wy(y);
					if (!inside(outer, worldX, worldY)) {
						continue;
					}
					boolean hole = false;
					for (List<double[]> ring : holes) {
						if (inside(ring, worldX, worldY)) {
							hole = true;
							break;
						}
					}
					if (!hole) {
						int paint = material(material, worldX, worldY);
						if (relief)
							paint = (paint & 0xffffff) | 0x70000000;
						pixels[y * width + x] = blend(pixels[y * width + x], paint);
					}
				}
			}
		}

		int material(String material, double x, double y) {
			AssetService.Texture texture = textures.get(material);
			if (texture != null) {
				PngCodec.Raster image = texture.raster();
				double factor = image.width() / texture.repeatFeet();
				int tx = Math.floorMod((int) Math.floor(x * factor), image.width());
				int ty = Math.floorMod((int) Math.floor(-y * factor), image.height());
				return image.pixels()[ty * image.width() + tx];
			}
			String name = material.toUpperCase(Locale.ROOT);
			int base = switch (name) {
			case "WOOD", "TIMBER" -> 0xffa1845f;
			case "EARTH", "DIRT" -> 0xff9d8965;
			case "GRASS", "GRASSLAND", "FIELD" -> 0xff9dad78;
			case "FOREST" -> 0xff597c56;
			case "WATER", "SHALLOW_WATER", "RIVER", "LAKE", "OCEAN" -> 0xff6398ad;
			case "ICE" -> 0xffc4e0e6;
			case "TILE", "DECORATIVE_TILE" -> 0xffc4b99c;
			case "MOSS", "DAMP", "DAMP_STONE" -> 0xff8e9b83;
			case "CAVE", "ROCK", "ROUGH_STONE" -> 0xff92948a;
			case "VOID", "PIT" -> style.equals("PARCHMENT") ? 0xff706656 : 0xff303b42;
			default -> 0xffb4b4a7;
			};
			if (contrast) {
				return name.equals("VOID") || name.equals("PIT") ? 0xff202020 : 0xffeeeeea;
			}
			if (style.equals("PARCHMENT")) {
				base = blend(base, 0x69d9c49a);
			}
			int ix = (int) Math.floor(x * 3), iy = (int) Math.floor(y * 3);
			int noise = Math.floorMod(ix * 73428767 ^ iy * 9122713, 11) - 5;
			if ((name.equals("WOOD") || name.equals("TIMBER")) && Math.floorMod(iy, 9) == 0) {
				noise -= 16;
			}
			return shade(base, noise);
		}

		void grid(double spacing) {
			int color = contrast ? 0xff888888 : 0x42606b66;
			double firstX = Math.ceil(bounds[0] / spacing) * spacing;
			double firstY = Math.ceil(bounds[1] / spacing) * spacing;
			for (double x = firstX; x <= bounds[0] + bounds[2]; x += spacing) {
				line(px(x), 0, px(x), height - 1, color, 1);
			}
			for (double y = firstY; y <= bounds[1] + bounds[3]; y += spacing) {
				line(0, py(y), width - 1, py(y), color, 1);
			}
		}

		void segment(Map<String, Object> segment, int color, double feet) {
			double[] a = numbers(segment.get("from_ft"), 2), b = numbers(segment.get("to_ft"), 2);
			if (a != null && b != null) {
				line(px(a[0]), py(a[1]), px(b[0]), py(b[1]), color, Math.max(2, Math.min(128, feet * scale)));
			}
		}

		void path(Map<String, Object> path, int color, boolean casing) {
			List<double[]> vertices = points(path.getOrDefault("path_ft", path.get("points_ft")));
			double widthPixels = Math.max(3, Math.min(128, number(path.get("width_ft"), 2) * scale));
			for (int i = 1; i < vertices.size(); i++) {
				double[] a = vertices.get(i - 1), b = vertices.get(i);
				if (casing)
					line(px(a[0]), py(a[1]), px(b[0]), py(b[1]), 0xffe9dbc0, widthPixels + 2);
				else
					line(px(a[0]), py(a[1]), px(b[0]), py(b[1]), color, widthPixels);
			}
		}

		void featurePoint(Map<String, Object> feature, boolean labels) {
			double[] p = position(feature);
			if (p == null)
				return;
			int x = (int) Math.round(px(p[0])), y = (int) Math.round(py(p[1]));
			if (x < 0 || y < 0 || x >= width || y >= height)
				return;
			String name = String.valueOf(feature.getOrDefault("name", feature.getOrDefault("label", "")));
			String kind = String.valueOf(feature.getOrDefault("symbol", feature.getOrDefault("kind", "LANDMARK")))
					.toUpperCase(Locale.ROOT);
			if (name.toUpperCase(Locale.ROOT).contains("CASTLE"))
				kind = "CASTLE";
			int ink = 0xff302d25, paper = 0xfff1e5cd;
			if (kind.contains("CASTLE") || kind.contains("FORT")) {
				rect(x - 7, y - 3, x + 7, y + 5, paper);
				rect(x - 7, y - 5, x - 3, y + 5, ink);
				rect(x + 3, y - 5, x + 7, y + 5, ink);
				rect(x - 6, y + 1, x + 6, y + 5, ink);
				for (int i = -7; i <= 5; i += 4)
					rect(x + i, y - 7, x + i + 2, y - 4, ink);
				rect(x - 1, y + 1, x + 1, y + 5, paper);
			} else if (kind.contains("INN") || kind.contains("TAVERN")) {
				rect(x - 5, y - 1, x + 5, y + 6, ink);
				rect(x - 3, y + 1, x + 3, y + 5, paper);
				line(x - 7, y, x, y - 7, ink, 2);
				line(x, y - 7, x + 7, y, ink, 2);
				rect(x - 1, y + 3, x + 1, y + 6, ink);
			} else if (kind.contains("CITY") || kind.contains("CAPITAL")) {
				dot(x, y, 8, paper);
				dot(x, y, 6, ink);
				dot(x, y, 3, paper);
			} else if (kind.contains("VILLAGE")) {
				dot(x, y, 5, paper);
				dot(x, y, 3, ink);
			} else if (kind.contains("TOWN") || kind.contains("SETTLEMENT")) {
				rect(x - 5, y - 5, x + 5, y + 5, paper);
				rect(x - 3, y - 3, x + 3, y + 3, ink);
			} else {
				line(x, y - 6, x + 6, y, ink, 2);
				line(x + 6, y, x, y + 6, ink, 2);
				line(x, y + 6, x - 6, y, ink, 2);
				line(x - 6, y, x, y - 6, ink, 2);
				dot(x, y, 2, paper);
			}
			if (labels && !name.isBlank())
				label(p[0], p[1] - 21 / scale, name, ink, p[0], p[1]);
		}

		void dot(int cx, int cy, int radius, int color) {
			for (int y = Math.max(0, cy - radius); y <= Math.min(height - 1, cy + radius); y++)
				for (int x = Math.max(0, cx - radius); x <= Math.min(width - 1, cx + radius); x++)
					if ((x - cx) * (x - cx) + (y - cy) * (y - cy) <= radius * radius)
						pixels[y * width + x] = color;
		}

		void terrainSymbols(Map<String, Object> feature) {
			String kind = String.valueOf(feature.getOrDefault("kind", "")).toUpperCase(Locale.ROOT);
			List<double[]> polygon = points(feature.get("polygon_ft"));
			if (polygon.size() < 3)
				return;
			var holes = new ArrayList<List<double[]>>();
			if (feature.get("holes_ft") instanceof List<?> rings)
				for (Object ring : rings)
					holes.add(points(ring));
			boolean forest = kind.contains("FOREST"), mountain = kind.contains("MOUNTAIN") || kind.contains("RIDGE"),
					hill = kind.contains("HILL");
			if (!forest && !mountain && !hill)
				return;
			int step = forest ? 24 : 32;
			for (int y = 14; y < height - 14; y += step)
				for (int x = 14; x < width - 14; x += step) {
					int shift = (y / step % 2) * step / 2, cx = x + shift;
					if (cx >= width - 10 || !inside(polygon, wx(cx), wy(y)))
						continue;
					boolean hole = false;
					for (List<double[]> ring : holes)
						if (inside(ring, wx(cx), wy(y))) {
							hole = true;
							break;
						}
					if (hole)
						continue;
					int ink = forest ? 0xff37513b : 0xff6d6552;
					if (forest) {
						line(cx - 4, y + 2, cx, y - 6, ink, 1);
						line(cx, y - 6, cx + 4, y + 2, ink, 1);
						line(cx - 5, y + 5, cx, y - 3, ink, 1);
						line(cx, y - 3, cx + 5, y + 5, ink, 1);
						line(cx, y + 3, cx, y + 8, ink, 1);
					} else if (mountain) {
						line(cx - 8, y + 6, cx, y - 8, ink, 1);
						line(cx, y - 8, cx + 8, y + 6, ink, 1);
						line(cx, y - 8, cx + 2, y + 4, 0xffaaa48b, 1);
						line(cx - 2, y - 4, cx + 2, y - 4, 0xfff2ecdb, 1);
					} else {
						line(cx - 7, y + 3, cx - 3, y - 2, ink, 1);
						line(cx - 3, y - 2, cx + 3, y - 2, ink, 1);
						line(cx + 3, y - 2, cx + 7, y + 3, ink, 1);
					}
				}
		}

		void placed(Map<String, Object> item) {
			if (item.get("observed_rects_ft") instanceof List<?> parts) {
				for (Object part : parts) {
					var cell = new LinkedHashMap<>(item);
					cell.remove("observed_rects_ft");
					cell.remove("x_ft");
					cell.remove("y_ft");
					cell.remove("position_ft");
					cell.put("rect_ft", part);
					placed(cell);
				}
				return;
			}
			double[] pos = objectPosition(item);
			if (pos == null) {
				return;
			}
			double[] rect = numbers(item.get("rect_ft"), 4);
			double w = rect == null ? number(item.get("width_ft"), 5) : rect[2];
			double h = rect == null ? number(item.get("height_ft"), 5) : rect[3];
			int left = (int) Math.round(px(pos[0] - w / 2)), right = (int) Math.round(px(pos[0] + w / 2));
			int top = (int) Math.round(py(pos[1] + h / 2)), bottom = (int) Math.round(py(pos[1] - h / 2));
			boolean destroyed = "DESTROYED".equalsIgnoreCase(String.valueOf(item.get("state")));
			rect(left, top, right, bottom, destroyed ? 0xff8e8170 : 0xff946b42);
			line(left, top, right, top, 0xff382f26, 1);
			line(left, bottom, right, bottom, 0xff382f26, 1);
			line(left, top, left, bottom, 0xff382f26, 1);
			line(right, top, right, bottom, 0xff382f26, 1);
			if (destroyed) {
				line(left, top, right, bottom, 0xff382f26, 2);
				line(right, top, left, bottom, 0xff382f26, 2);
			} else if ("LOCKED".equalsIgnoreCase(String.valueOf(item.get("state")))) {
				label(pos[0], pos[1], "L", 0xff111111);
			}
		}

		void reserveActor(Map<String, Object> actor) {
			double[] pos = position(actor);
			int cx = (int) Math.round(px(pos[0])), cy = (int) Math.round(py(pos[1]));
			double widthFt = number(actor.get("width_ft"), 5);
			int rx = Math.max(4, (int) Math.round(widthFt * scale / 2));
			int ry = Math.max(4, (int) Math.round(number(actor.get("height_ft"), widthFt) * scale / 2));
			labelBounds.add(new int[] {cx - rx - 3, cy - ry - 3, cx + rx + 3, cy + ry + 3});
		}

		void reservePoint(double[] point) {
			int x = (int) Math.round(px(point[0])), y = (int) Math.round(py(point[1]));
			labelBounds.add(new int[] {x - 9, y - 9, x + 9, y + 9});
		}

		void symbol(double[] point, String glyph) {
			int x = (int) Math.round(px(point[0])), y = (int) Math.round(py(point[1]));
			rect(x - 4, y - 5, x + 4, y + 5, 0xfff3e8ce);
			text(x - 2, y - 3, glyph, 0xff101010, 1);
		}

		void reserveLegend(List<String> legend) {
			if (!legend.isEmpty())
				labelBounds.add(new int[] {0, legendAtTop ? 0 : height - 3 - 10 * legend.size(),
						Math.min(width, legend.stream().mapToInt(String::length).max().orElse(0) * 6 + 9),
						legendAtTop ? 6 + 10 * legend.size() : height});
		}

		void legend(List<String> legend) {
			if (legend.isEmpty())
				return;
			int top = legendAtTop ? 3 : Math.max(0, height - 3 - legend.size() * 10),
					right = Math.min(width - 1, legend.stream().mapToInt(String::length).max().orElse(0) * 6 + 9);
			rect(0, top, right, Math.min(height - 1, top + legend.size() * 10), 0xeef3e8ce);
			for (int i = 0; i < legend.size(); i++)
				text(4, top + 2 + i * 10, legend.get(i), 0xff101010, 1);
		}

		void actor(Map<String, Object> actor, int index, boolean labels) {
			double[] pos = position(actor);
			double w = number(actor.get("width_ft"), 5), h = number(actor.get("height_ft"), w);
			int cx = (int) Math.round(px(pos[0])), cy = (int) Math.round(py(pos[1]));
			int rx = Math.max(4, (int) Math.round(w * scale / 2)), ry = Math.max(4, (int) Math.round(h * scale / 2));
			String side = String.valueOf(actor.getOrDefault("side", "PARTY")).toUpperCase(Locale.ROOT);
			boolean party = side.equals("PARTY") || side.equals("ALLY")
					|| Boolean.TRUE.equals(actor.get("party_member"));
			boolean dead = "DEAD".equals(actor.get("life_state"));
			int color = dead ? 0xff66645e
					: party ? 0xff2876a8 : Set.of("HOSTILE", "ENEMY").contains(side) ? 0xffb6473a : 0xff7e8790;
			for (int y = Math.max(0, cy - ry); y <= Math.min(height - 1, cy + ry); y++) {
				for (int x = Math.max(0, cx - rx); x <= Math.min(width - 1, cx + rx); x++) {
					double d = (double) (x - cx) * (x - cx) / ((double) rx * rx)
							+ (double) (y - cy) * (y - cy) / ((double) ry * ry);
					if (d <= 1) {
						pixels[y * width + x] = d > .75 ? 0xfff9f7ed : color;
					}
				}
			}
			if (dead) {
				line(cx - rx / 2, cy - ry / 2, cx + rx / 2, cy + ry / 2, 0xff202020, 2);
				line(cx - rx / 2, cy + ry / 2, cx + rx / 2, cy - ry / 2, 0xff202020, 2);
			}
			if (labels) {
				String label = String.valueOf(actor.getOrDefault("label", Integer.toString(index)));
				int size = Math.max(1, Math.min(3, Math.min(rx, ry) / 7));
				text(cx - Math.min(label.length(), 4) * 3 * size, cy - 3 * size,
						label.substring(0, Math.min(label.length(), 4)), 0xffffffff, size);
			}
		}

		void label(double x, double y, String value, int color) {
			label(x, y, value, color, x, y);
		}

		void label(double x, double y, String value, int color, double anchorX, double anchorY) {
			String label = value.toUpperCase(Locale.ROOT);
			List<double[]> sameNames = namedLabels.computeIfAbsent(label, ignored -> new ArrayList<>());
			if (sameNames.stream().anyMatch(point -> Math.hypot(px(anchorX) - point[0], py(anchorY) - point[1]) < 12))
				return;
			if (label.length() > 34) {
				label = label.substring(0, 31) + "...";
			}
			int size = width >= 800 ? 2 : 1;
			int left = clamp(px(x) - label.length() * 3d * size, 1, Math.max(1, width - label.length() * 6 * size - 1));
			int top = clamp(py(y) - 4 * size, 1, Math.max(1, height - 9 * size));
			if (avoidLabelOverlaps) {
				int initialTop = top;
				for (int attempt = 0; attempt < 12; attempt++) {
					int[] candidate = {left - 3, top - 3, left + label.length() * 6 * size + 1, top + 7 * size + 1};
					boolean overlaps = labelBounds.stream()
							.anyMatch(previous -> candidate[0] <= previous[2] && candidate[2] >= previous[0]
									&& candidate[1] <= previous[3] && candidate[3] >= previous[1]);
					if (!overlaps) {
						labelBounds.add(candidate);
						break;
					}
					if (attempt == 11)
						return;
					int displacement = ((attempt / 2) + 1) * (9 * size + 5) * (attempt % 2 == 0 ? 1 : -1);
					top = clamp(initialTop + displacement, 1, Math.max(1, height - 9 * size));
				}
			}
			sameNames.add(new double[] {px(anchorX), py(anchorY)});
			double endX = Math.max(left - 2, Math.min(left + label.length() * 6 * size, px(anchorX))),
					endY = Math.max(top - 2, Math.min(top + 7 * size, py(anchorY)));
			if (Math.hypot(endX - px(anchorX), endY - py(anchorY)) > 24)
				line(px(anchorX), py(anchorY), endX, endY, 0xff655d4d, 1);
			rect(left - 2, top - 2, left + label.length() * 6 * size, top + 7 * size, 0xd6f3e8ce);
			text(left, top, label, color, size);
		}

		void text(int left, int top, String label, int color, int size) {
			for (int i = 0; i < label.length(); i++) {
				String glyph = GLYPHS.getOrDefault(Character.toUpperCase(label.charAt(i)), GLYPHS.get('?'));
				for (int row = 0; row < 7; row++) {
					for (int column = 0; column < 5; column++) {
						if (glyph.charAt(row * 5 + column) == '1') {
							rect(left + (i * 6 + column) * size, top + row * size,
									left + (i * 6 + column + 1) * size - 1, top + (row + 1) * size - 1, color);
						}
					}
				}
			}
		}

		void rect(int left, int top, int right, int bottom, int color) {
			for (int y = Math.max(0, top); y <= Math.min(height - 1, bottom); y++) {
				for (int x = Math.max(0, left); x <= Math.min(width - 1, right); x++) {
					pixels[y * width + x] = blend(pixels[y * width + x], color);
				}
			}
		}

		void line(double x1, double y1, double x2, double y2, int color, double thickness) {
			double dx = x2 - x1, dy = y2 - y1;
			double[] range = {0, 1};
			if (!clip(-dx, x1, range) || !clip(dx, width - 1 - x1, range) || !clip(-dy, y1, range)
					|| !clip(dy, height - 1 - y1, range)) {
				return;
			}
			int ax = (int) Math.round(x1 + dx * range[0]), ay = (int) Math.round(y1 + dy * range[0]);
			int bx = (int) Math.round(x1 + dx * range[1]), by = (int) Math.round(y1 + dy * range[1]);
			int sx = ax < bx ? 1 : -1, sy = ay < by ? 1 : -1;
			int ddx = Math.abs(bx - ax), ddy = -Math.abs(by - ay), err = ddx + ddy;
			int radius = Math.max(0, (int) Math.floor(thickness / 2));
			while (true) {
				rect(ax - radius, ay - radius, ax + radius, ay + radius, color);
				if (ax == bx && ay == by) {
					break;
				}
				int twice = 2 * err;
				if (twice >= ddy) {
					err += ddy;
					ax += sx;
				}
				if (twice <= ddx) {
					err += ddx;
					ay += sy;
				}
			}
		}

		void scaleBar() {
			boolean miles = bounds[2] >= 5280;
			double divisor = miles ? 5280 : 1, desired = bounds[2] * .2 / divisor;
			double power = Math.pow(10, Math.floor(Math.log10(desired))), part = desired / power;
			double length = (part >= 5 ? 5 : part >= 2 ? 2 : 1) * power, feet = length * divisor;
			int left = 12, bottom = height - 16, right = Math.min(width - 12, left + (int) Math.round(feet * scale));
			if (right <= left || bottom < 16)
				return;
			rect(left - 5, bottom - 15, right + 5, bottom + 5, 0xd9f4ecd7);
			line(left, bottom, right, bottom, 0xff151515, 2);
			line(left, bottom - 3, left, bottom + 3, 0xff151515, 1);
			line(right, bottom - 3, right, bottom + 3, 0xff151515, 1);
			text(left, bottom - 12, format(length) + (miles ? " MI" : " FT"), 0xff111111, 1);
		}

		void fog(Fog fog) {
			if (!fog.enabled) {
				return;
			}
			for (int y = 0; y < height; y++) {
				for (int x = 0; x < width; x++) {
					if (!fog.visible(wx(x), wy(y))) {
						pixels[y * width + x] = fog.known(wx(x), wy(y)) ? blend(pixels[y * width + x], 0xaa252b32)
								: 0xff141a20;
					}
				}
			}
		}
	}

	private static final class Fog {
		final boolean enabled;
		final double spacing, originX, originY;
		final Set<Long> visible, explored;

		Fog(Map<String, Object> data, double[] bounds) {
			enabled = !data.isEmpty() && !"ALL".equalsIgnoreCase(String.valueOf(data.get("mode")));
			spacing = number(data.get("cell_ft"), 5);
			if (spacing <= 0) {
				throw RpgException.invalidArgument("Visibility cell_ft must be positive.");
			}
			double[] origin = numbers(data.get("origin_ft"), 2);
			originX = origin == null ? bounds[0] : origin[0];
			originY = origin == null ? bounds[1] : origin[1];
			visible = cells(data.getOrDefault("visible_cells", data.get("visible")));
			explored = cells(data.getOrDefault("explored_cells", data.get("explored")));
		}

		boolean visible(double x, double y) {
			return !enabled || visible.contains(key(x, y));
		}

		boolean known(double x, double y) {
			return visible(x, y) || explored.contains(key(x, y));
		}

		long key(double x, double y) {
			return cell((int) Math.floor((x - originX) / spacing), (int) Math.floor((y - originY) / spacing));
		}

		static Set<Long> cells(Object value) {
			if (!(value instanceof List<?> list)) {
				return Set.of();
			}
			if (list.size() > 262_144) {
				throw RpgException.invalidArgument("Render a bounded visibility window.");
			}
			var result = new HashSet<Long>();
			for (Object entry : list) {
				double[] pair = numbers(entry, 2);
				if (pair == null || pair[0] != Math.rint(pair[0]) || pair[1] != Math.rint(pair[1])) {
					throw RpgException.invalidArgument("Visibility cells must be integer [column,row] pairs.");
				}
				result.add(cell((int) pair[0], (int) pair[1]));
			}
			return result;
		}

		static long cell(int x, int y) {
			return (long) x << 32 | y & 0xffffffffL;
		}
	}

	private static boolean inside(List<double[]> polygon, double x, double y) {
		boolean inside = false;
		for (int i = 0, j = polygon.size() - 1; i < polygon.size(); j = i++) {
			double[] a = polygon.get(i), b = polygon.get(j);
			if ((a[1] > y) != (b[1] > y) && x < (b[0] - a[0]) * (y - a[1]) / (b[1] - a[1]) + a[0]) {
				inside = !inside;
			}
		}
		return inside;
	}

	private static boolean clip(double p, double q, double[] range) {
		if (p == 0) {
			return q >= 0;
		}
		double r = q / p;
		if (p < 0) {
			if (r > range[1]) {
				return false;
			}
			range[0] = Math.max(range[0], r);
		} else {
			if (r < range[0]) {
				return false;
			}
			range[1] = Math.min(range[1], r);
		}
		return true;
	}

	private static int clamp(double value, int low, int high) {
		return (int) Math.max(low, Math.min(high, Math.floor(value)));
	}

	private static int shade(int color, int change) {
		int r = Math.clamp(((color >>> 16) & 255) + change, 0, 255);
		int g = Math.clamp(((color >>> 8) & 255) + change, 0, 255);
		int b = Math.clamp((color & 255) + change, 0, 255);
		return 0xff000000 | r << 16 | g << 8 | b;
	}

	private static int blend(int below, int above) {
		int a = above >>> 24;
		if (a == 255) {
			return above;
		}
		int r = (((above >>> 16) & 255) * a + ((below >>> 16) & 255) * (255 - a)) / 255;
		int g = (((above >>> 8) & 255) * a + ((below >>> 8) & 255) * (255 - a)) / 255;
		int b = ((above & 255) * a + (below & 255) * (255 - a)) / 255;
		return 0xff000000 | r << 16 | g << 8 | b;
	}

	private static Map<Character, String> glyphs() {
		String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789 .,:-+()/?!";
		String[] rows = {"01110/10001/10001/11111/10001/10001/10001", "11110/10001/10001/11110/10001/10001/11110",
				"01111/10000/10000/10000/10000/10000/01111", "11110/10001/10001/10001/10001/10001/11110",
				"11111/10000/10000/11110/10000/10000/11111", "11111/10000/10000/11110/10000/10000/10000",
				"01111/10000/10000/10111/10001/10001/01110", "10001/10001/10001/11111/10001/10001/10001",
				"01110/00100/00100/00100/00100/00100/01110", "00111/00010/00010/00010/10010/10010/01100",
				"10001/10010/10100/11000/10100/10010/10001", "10000/10000/10000/10000/10000/10000/11111",
				"10001/11011/10101/10101/10001/10001/10001", "10001/11001/10101/10011/10001/10001/10001",
				"01110/10001/10001/10001/10001/10001/01110", "11110/10001/10001/11110/10000/10000/10000",
				"01110/10001/10001/10001/10101/10010/01101", "11110/10001/10001/11110/10100/10010/10001",
				"01111/10000/10000/01110/00001/00001/11110", "11111/00100/00100/00100/00100/00100/00100",
				"10001/10001/10001/10001/10001/10001/01110", "10001/10001/10001/10001/10001/01010/00100",
				"10001/10001/10001/10101/10101/10101/01010", "10001/10001/01010/00100/01010/10001/10001",
				"10001/10001/01010/00100/00100/00100/00100", "11111/00001/00010/00100/01000/10000/11111",
				"01110/10001/10011/10101/11001/10001/01110", "00100/01100/00100/00100/00100/00100/01110",
				"01110/10001/00001/00010/00100/01000/11111", "11110/00001/00001/01110/00001/00001/11110",
				"00010/00110/01010/10010/11111/00010/00010", "11111/10000/10000/11110/00001/00001/11110",
				"01110/10000/10000/11110/10001/10001/01110", "11111/00001/00010/00100/01000/01000/01000",
				"01110/10001/10001/01110/10001/10001/01110", "01110/10001/10001/01111/00001/00001/01110",
				"00000/00000/00000/00000/00000/00000/00000", "00000/00000/00000/00000/00000/00100/00100",
				"00000/00000/00000/00000/00100/00100/01000", "00000/00100/00100/00000/00100/00100/00000",
				"00000/00000/00000/11111/00000/00000/00000", "00000/00100/00100/11111/00100/00100/00000",
				"00010/00100/01000/01000/01000/00100/00010", "01000/00100/00010/00010/00010/00100/01000",
				"00001/00010/00010/00100/01000/01000/10000", "01110/10001/00001/00010/00100/00000/00100",
				"00100/00100/00100/00100/00100/00000/00100"};
		var result = new LinkedHashMap<Character, String>();
		for (int i = 0; i < alphabet.length(); i++) {
			result.put(alphabet.charAt(i), rows[i].replace("/", ""));
		}
		String apostrophe = "00100/00100/01000/00000/00000/00000/00000".replace("/", "");
		for (char c : new char[] {'\'', '\u2018', '\u2019'})
			result.put(c, apostrophe);
		for (char c : new char[] {'\u2013', '\u2014', '\u2212'})
			result.put(c, result.get('-'));
		result.put('=', "00000/00000/11111/00000/11111/00000/00000".replace("/", ""));
		return Map.copyOf(result);
	}
}
