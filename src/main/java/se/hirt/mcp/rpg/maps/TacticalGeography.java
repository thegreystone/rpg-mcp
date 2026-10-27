package se.hirt.mcp.rpg.maps;

import java.util.*;

/** Bounded local views of saved geography. Full world paths remain private provenance. */
public final class TacticalGeography {
	private TacticalGeography() {
	}

	public static List<Map<String, Object>> clip(List<Map<String, Object>> features, List<Double> bounds) {
		var result = new ArrayList<Map<String, Object>>();
		for (var feature : features) {
			if (feature.get("path_ft") instanceof List<?> path) {
				for (int i = 1; i < path.size(); i++) {
					double[] a = pair(path.get(i - 1)), b = pair(path.get(i));
					if (a == null || b == null)
						continue;
					List<List<Double>> part = segment(a, b, bounds);
					if (part.isEmpty())
						continue;
					var copy = new LinkedHashMap<>(feature);
					copy.put("path_ft", part);
					copy.remove("geometry");
					result.add(copy);
				}
			} else if (feature.get("polygon_ft") instanceof List<?> polygon) {
				var points = new ArrayList<List<Double>>();
				for (Object item : polygon) {
					double[] p = pair(item);
					if (p != null)
						points.add(List.of(p[0], p[1]));
				}
				List<List<Double>> clipped = polygon(points, bounds);
				if (clipped.size() < 3)
					continue;
				var copy = new LinkedHashMap<>(feature);
				copy.put("polygon_ft", clipped);
				copy.remove("geometry");
				if (feature.get("holes_ft") instanceof List<?> holes) {
					var kept = new ArrayList<List<List<Double>>>();
					for (Object raw : holes) {
						var ring = new ArrayList<List<Double>>();
						if (raw instanceof List<?> list)
							for (Object item : list) {
								double[] p = pair(item);
								if (p != null)
									ring.add(List.of(p[0], p[1]));
							}
						var hole = polygon(ring, bounds);
						if (hole.size() >= 3)
							kept.add(hole);
					}
					copy.put("holes_ft", kept);
				}
				result.add(copy);
			} else {
				double[] p = pair(feature.getOrDefault("point_ft", feature.get("position_ft")));
				if (p != null && inside(p[0], p[1], bounds))
					result.add(new LinkedHashMap<>(feature));
			}
		}
		return result;
	}

	private static List<List<Double>> segment(double[] a, double[] b, List<Double> r) {
		double dx = b[0] - a[0], dy = b[1] - a[1], lo = 0, hi = 1;
		double[] p = {-dx, dx, -dy, dy},
				q = {a[0] - r.get(0), r.get(0) + r.get(2) - a[0], a[1] - r.get(1), r.get(1) + r.get(3) - a[1]};
		for (int i = 0; i < 4; i++) {
			if (p[i] == 0) {
				if (q[i] < 0)
					return List.of();
			} else {
				double value = q[i] / p[i];
				if (p[i] < 0)
					lo = Math.max(lo, value);
				else
					hi = Math.min(hi, value);
				if (lo >= hi)
					return List.of();
			}
		}
		return List.of(List.of(inches(a[0] + lo * dx), inches(a[1] + lo * dy)),
				List.of(inches(a[0] + hi * dx), inches(a[1] + hi * dy)));
	}

	private static List<List<Double>> polygon(List<List<Double>> points, List<Double> r) {
		List<List<Double>> current = points;
		for (int edge = 0; edge < 4 && !current.isEmpty(); edge++) {
			var next = new ArrayList<List<Double>>();
			List<Double> previous = current.getLast();
			boolean previousInside = side(previous, r, edge);
			for (var point : current) {
				boolean pointInside = side(point, r, edge);
				if (pointInside != previousInside) {
					int axis = edge < 2 ? 0 : 1;
					double value = r.get(axis) + (edge % 2 == 1 ? r.get(axis + 2) : 0),
							t = (value - previous.get(axis)) / (point.get(axis) - previous.get(axis));
					next.add(List.of(inches(previous.get(0) + t * (point.get(0) - previous.get(0))),
							inches(previous.get(1) + t * (point.get(1) - previous.get(1)))));
				}
				if (pointInside)
					next.add(List.of(inches(point.get(0)), inches(point.get(1))));
				previous = point;
				previousInside = pointInside;
			}
			current = next;
		}
		var result = new ArrayList<List<Double>>();
		for (var point : current)
			if (result.isEmpty() || !point.equals(result.getLast()))
				result.add(point);
		if (result.size() > 1 && result.getFirst().equals(result.getLast()))
			result.removeLast();
		return result;
	}

	private static boolean side(List<Double> p, List<Double> r, int edge) {
		int axis = edge < 2 ? 0 : 1;
		return edge % 2 == 0 ? p.get(axis) >= r.get(axis) : p.get(axis) <= r.get(axis) + r.get(axis + 2);
	}

	private static boolean inside(double x, double y, List<Double> r) {
		return x >= r.get(0) && y >= r.get(1) && x <= r.get(0) + r.get(2) && y <= r.get(1) + r.get(3);
	}

	private static double inches(double value) {
		return Math.round(value * 12) / 12.0;
	}

	private static double[] pair(Object item) {
		if (item instanceof List<?> p && p.size() >= 2 && p.get(0) instanceof Number x && p.get(1) instanceof Number y
				&& Double.isFinite(x.doubleValue()) && Double.isFinite(y.doubleValue()))
			return new double[] {x.doubleValue(), y.doubleValue()};
		return null;
	}
}
