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
package se.hirt.mcp.rpg.spatial;

import se.hirt.mcp.rpg.spatial.Geometry.Bounds;
import se.hirt.mcp.rpg.spatial.Geometry.Footprint;
import se.hirt.mcp.rpg.spatial.Geometry.Point;
import se.hirt.mcp.rpg.spatial.Geometry.Polygon;
import se.hirt.mcp.rpg.spatial.Geometry.Scene;
import se.hirt.mcp.rpg.spatial.Geometry.Segment;
import se.hirt.mcp.rpg.spatial.Geometry.Terrain;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.TreeSet;

/**
 * Pure, deterministic 2D tactical profile. No method mutates campaign state, makes a roll, knows an
 * entity's identity or grants discovery. Callers supply the permitted knowledge for previews and
 * authoritative geometry for execution. Levels, vertical mechanics and portals are service
 * concerns.
 */
public final class SpatialRules {

	public static final String PROFILE = "grid5-v1";
	public static final long CELL_INCHES = Geometry.CELL_INCHES;
	public static final int MAX_SEARCH_NODES = 100_000;
	private static final double EPSILON = Geometry.EPSILON;
	private static final int[][] NEIGHBORS = {{0, 1}, {1, 0}, {0, -1}, {-1, 0}, {1, 1}, {1, -1}, {-1, -1}, {-1, 1}};

	public SpatialRules() {
	}

	public enum PlacementStatus {
		VALID, OUTSIDE_BOUNDS, NO_FLOOR, BLOCKED, OCCUPIED;

		public boolean valid() {
			return this == VALID;
		}
	}

	public enum PathStatus {
		FOUND, NO_PATH, INVALID_START, INVALID_DESTINATION, LIMIT_REACHED, COST_LIMIT_REACHED
	}

	/** Origins include the starting cell and destination; costs are physical inches of movement. */
	public record PathResult(PathStatus status, List<Point> origins, long costInches, int visited) {
		public PathResult {
			origins = List.copyOf(origins);
		}

		public boolean found() {
			return status == PathStatus.FOUND;
		}
	}

	public enum Cover {
		NONE, HALF, THREE_QUARTERS, TOTAL
	}

	/**
	 * Five geometric samples; explicit height-based/adjudicated cover belongs to the service layer.
	 */
	public record CoverResult(Cover cover, int blockedSamples, int totalSamples) {
	}

	public enum Illumination {
		BRIGHT, DIM, DARK
	}

	/** Darkvision cannot extend the observer's overall sight limit. All ranges are inches. */
	public record Observer(Point position, long sightRangeInches, long darkvisionRangeInches) {
		public Observer {
			Objects.requireNonNull(position, "position");
			if (sightRangeInches < 0 || darkvisionRangeInches < 0) {
				throw new IllegalArgumentException("Sight ranges cannot be negative.");
			}
		}
	}

	public record Observation(boolean visible, Illumination apparentIllumination) {
	}

	public sealed interface Area permits Circle, Rectangle, Cone, Line, PolygonArea {
		Bounds bounds();

		boolean contains(Point point);
	}

	/** Exact authored area, including a planar cone or a rotated cube footprint. */
	public record PolygonArea(Polygon polygon) implements Area {
		public PolygonArea {
			Objects.requireNonNull(polygon, "polygon");
		}

		@Override
		public Bounds bounds() {
			return polygon.bounds();
		}

		@Override
		public boolean contains(Point point) {
			return polygon.contains(point);
		}
	}

	/**
	 * Circular areas use Euclidean geometry; diagonal movement does not turn circles into squares.
	 */
	public record Circle(Point center, long radiusInches) implements Area {
		public Circle {
			Objects.requireNonNull(center, "center");
			radius(radiusInches);
		}

		@Override
		public Bounds bounds() {
			return expanded(center, radiusInches);
		}

		@Override
		public boolean contains(Point point) {
			double dx = point.x() - center.x();
			double dy = point.y() - center.y();
			return dx * dx + dy * dy <= (double) radiusInches * radiusInches + EPSILON;
		}
	}

	public record Rectangle(Bounds bounds) implements Area {
		public Rectangle {
			Objects.requireNonNull(bounds, "bounds");
		}

		@Override
		public boolean contains(Point point) {
			return bounds.contains(point);
		}
	}

	/** Direction is a point aimed at from origin; angle is the full opening angle in degrees. */
	public record Cone(Point origin, Point direction, long radiusInches, double angleDegrees) implements Area {
		public Cone {
			Objects.requireNonNull(origin, "origin");
			Objects.requireNonNull(direction, "direction");
			radius(radiusInches);
			if (origin.equals(direction) || !Double.isFinite(angleDegrees) || angleDegrees <= 0 || angleDegrees > 360) {
				throw new IllegalArgumentException("A cone needs a direction and an angle in (0, 360].");
			}
		}

		@Override
		public Bounds bounds() {
			return expanded(origin, radiusInches);
		}

		@Override
		public boolean contains(Point point) {
			double dx = point.x() - origin.x();
			double dy = point.y() - origin.y();
			double distance = Math.hypot(dx, dy);
			if (distance > radiusInches + EPSILON) {
				return false;
			}
			if (distance == 0) {
				return true;
			}
			double aimX = direction.x() - origin.x();
			double aimY = direction.y() - origin.y();
			double cosine = (dx * aimX + dy * aimY) / (distance * Math.hypot(aimX, aimY));
			return cosine + EPSILON >= Math.cos(Math.toRadians(angleDegrees / 2));
		}
	}

	/** A rectangular strip with flat ends; width is physical width, not a drawing stroke. */
	public record Line(Point from, Point to, long widthInches) implements Area {
		public Line {
			Objects.requireNonNull(from, "from");
			Objects.requireNonNull(to, "to");
			if (from.equals(to) || widthInches <= 0 || widthInches > 2 * Geometry.MAX_COORDINATE) {
				throw new IllegalArgumentException("A line area needs distinct endpoints and positive bounded width.");
			}
		}

		@Override
		public Bounds bounds() {
			long half = (widthInches + 1) / 2;
			return new Bounds(clamp(Math.min(from.x(), to.x()) - half), clamp(Math.min(from.y(), to.y()) - half),
					clamp(Math.max(from.x(), to.x()) + half), clamp(Math.max(from.y(), to.y()) + half));
		}

		@Override
		public boolean contains(Point point) {
			double dx = to.x() - from.x();
			double dy = to.y() - from.y();
			double px = point.x() - from.x();
			double py = point.y() - from.y();
			double lengthSquared = dx * dx + dy * dy;
			double t = (px * dx + py * dy) / lengthSquared;
			return t >= -EPSILON && t <= 1 + EPSILON
					&& Math.abs(dx * py - dy * px) <= widthInches * Math.sqrt(lengthSquared) / 2 + EPSILON;
		}
	}

	/** Occupied excludes the acting footprint; dynamic creatures block movement but not sight. */
	public static PlacementStatus placement(Scene scene, Footprint footprint, Collection<Footprint> occupied) {
		if (!scene.bounds().contains(footprint.bounds())) {
			return PlacementStatus.OUTSIDE_BOUNDS;
		}
		List<Point> body = footprint.bounds().corners();
		if (!floorCovers(scene, body)) {
			return PlacementStatus.NO_FLOOR;
		}
		if (blocked(scene, body)) {
			return PlacementStatus.BLOCKED;
		}
		for (Footprint other : occupied) {
			if (footprint.bounds().overlapsInterior(other.bounds())) {
				return PlacementStatus.OCCUPIED;
			}
		}
		return PlacementStatus.VALID;
	}

	public static boolean canPlace(Scene scene, Footprint footprint, Collection<Footprint> occupied) {
		return placement(scene, footprint, occupied) == PlacementStatus.VALID;
	}

	/**
	 * Exact floor support for a saved object's rectangle, independent of its own collision block.
	 */
	public static boolean floorSupports(Scene scene, Bounds bounds) {
		return bounds.width() > 0 && bounds.height() > 0 && scene.bounds().contains(bounds)
				&& floorCovers(scene, bounds.corners());
	}

	/** Minimum Chebyshev distance between occupied cell centers, not physical boundary gaps. */
	public static long distance(Footprint a, Footprint b) {
		long aMaxX = a.origin().x() + (a.columns() - 1) * CELL_INCHES;
		long aMaxY = a.origin().y() + (a.rows() - 1) * CELL_INCHES;
		long bMaxX = b.origin().x() + (b.columns() - 1) * CELL_INCHES;
		long bMaxY = b.origin().y() + (b.rows() - 1) * CELL_INCHES;
		long dx = Math.max(0, Math.max(a.origin().x() - bMaxX, b.origin().x() - aMaxX));
		long dy = Math.max(0, Math.max(a.origin().y() - bMaxY, b.origin().y() - aMaxY));
		return Math.max(dx, dy);
	}

	public static boolean canReach(Footprint a, Footprint b, long reachInches) {
		if (reachInches < 0) {
			throw new IllegalArgumentException("Reach cannot be negative.");
		}
		return distance(a, b) <= reachInches;
	}

	/**
	 * Sight-block boundaries, endpoints and exact wall-corner contact occlude a ray. Floors do not
	 * imply walls: an open pit permits sight, and a dungeon adapter must supply its actual walls.
	 */
	public static boolean lineOfSight(Scene scene, Point from, Point to) {
		if (!scene.bounds().contains(from) || !scene.bounds().contains(to)) {
			return false;
		}
		for (Polygon block : scene.sightBlocks()) {
			if (block.contains(from) || block.contains(to)) {
				return false;
			}
		}
		if (from.equals(to)) {
			return scene.walls().stream().noneMatch(wall -> pointOn(wall, from));
		}
		Segment ray = new Segment(from, to);
		for (Segment wall : scene.walls()) {
			if (Geometry.intersects(ray, wall)) {
				return false;
			}
		}
		for (Polygon block : scene.sightBlocks()) {
			if (block.bounds().intersects(ray.bounds())) {
				for (Segment edge : block.boundaries()) {
					if (Geometry.intersects(ray, edge)) {
						return false;
					}
				}
			}
		}
		return true;
	}

	/**
	 * Sample the target's center and four corners inset by one inch. One/two blocked samples grant
	 * half cover, three/four three-quarters, and all five total cover in this versioned 2D profile.
	 */
	public static CoverResult cover(Scene scene, Point observer, Footprint target) {
		List<Point> samples = sightSamples(target);
		int blocked = 0;
		for (Point sample : samples) {
			if (!lineOfSight(scene, observer, sample)) {
				blocked++;
			}
		}
		Cover cover = switch (blocked) {
		case 0 -> Cover.NONE;
		case 1, 2 -> Cover.HALF;
		case 3, 4 -> Cover.THREE_QUARTERS;
		default -> Cover.TOTAL;
		};
		return new CoverResult(cover, blocked, samples.size());
	}

	/** A creature can look/aim from its occupied extent, not only its center. */
	public static CoverResult cover(Scene scene, Footprint observer, Footprint target) {
		CoverResult best = null;
		for (Point origin : sightSamples(observer)) {
			CoverResult candidate = cover(scene, origin, target);
			if (best == null || candidate.blockedSamples() < best.blockedSamples())
				best = candidate;
		}
		return best;
	}

	/**
	 * Shared target extent for perception and cover, with inset corners avoiding false wall
	 * contact.
	 */
	public static List<Point> sightSamples(Footprint target) {
		Bounds b = target.bounds();
		return List.of(target.center(), new Point(b.minX() + 1, b.minY() + 1), new Point(b.maxX() - 1, b.minY() + 1),
				new Point(b.maxX() - 1, b.maxY() - 1), new Point(b.minX() + 1, b.maxY() - 1));
	}

	/**
	 * Lighting is an authored/resolved input, not inferred from textures. This does not discover
	 * hidden actors, traps or secrets and does not include blindsight/tremorsense adjudication.
	 */
	public static Observation perceive(Scene scene, Observer observer, Point target, Illumination illumination) {
		Objects.requireNonNull(illumination, "illumination");
		long range = chebyshev(observer.position(), target);
		if (range > observer.sightRangeInches() || !lineOfSight(scene, observer.position(), target)) {
			return new Observation(false, Illumination.DARK);
		}
		boolean darkvision = range <= observer.darkvisionRangeInches() && observer.darkvisionRangeInches() > 0;
		if (illumination == Illumination.DARK) {
			return new Observation(darkvision, darkvision ? Illumination.DIM : Illumination.DARK);
		}
		return new Observation(true,
				illumination == Illumination.DIM && darkvision ? Illumination.BRIGHT : illumination);
	}

	/**
	 * Deterministic, bounded A*. Diagonals cost one cell; either obstructed orthogonal step
	 * prevents a diagonal. Every transition checks its swept body, so thin walls cannot be jumped
	 * between cells.
	 */
	public static PathResult path(
		Scene scene, Footprint start, Point destination, Collection<Footprint> occupied, int maxVisited,
		long maxCostInches) {
		if (maxVisited < 1 || maxVisited > MAX_SEARCH_NODES || maxCostInches < 0) {
			throw new IllegalArgumentException(
					"Path search needs a positive bounded node limit and nonnegative budget.");
		}
		if (!canPlace(scene, start, occupied)) {
			return failed(PathStatus.INVALID_START, 0);
		}
		Footprint goal;
		try {
			goal = start.at(destination);
		} catch (IllegalArgumentException e) {
			return failed(PathStatus.INVALID_DESTINATION, 0);
		}
		if (!canPlace(scene, goal, occupied)) {
			return failed(PathStatus.INVALID_DESTINATION, 0);
		}
		var open = new PriorityQueue<Node>(Comparator.comparingLong(Node::estimate).thenComparingLong(Node::remaining)
				.thenComparingLong(node -> lineDeviation(start.origin(), destination, node.point()))
				.thenComparingLong(node -> node.point.x()).thenComparingLong(node -> node.point.y()));
		var costs = new HashMap<Point, Long>();
		var previous = new HashMap<Point, Point>();
		var closed = new HashSet<Point>();
		costs.put(start.origin(), 0L);
		open.add(new Node(start.origin(), 0, chebyshev(start.origin(), destination)));
		int visited = 0;
		boolean costLimited = false;
		while (!open.isEmpty()) {
			Node current = open.poll();
			if (closed.contains(current.point) || current.cost != costs.getOrDefault(current.point, Long.MAX_VALUE)) {
				continue;
			}
			if (visited >= maxVisited) {
				return failed(PathStatus.LIMIT_REACHED, visited);
			}
			visited++;
			if (current.point.equals(destination)) {
				var origins = new ArrayList<Point>();
				for (Point cursor = destination; cursor != null; cursor = previous.get(cursor)) {
					origins.add(cursor);
				}
				Collections.reverse(origins);
				return new PathResult(PathStatus.FOUND, origins, current.cost, visited);
			}
			closed.add(current.point);
			Footprint from = start.at(current.point);
			for (int[] delta : NEIGHBORS) {
				long x = current.point.x() + delta[0] * CELL_INCHES;
				long y = current.point.y() + delta[1] * CELL_INCHES;
				if (x < scene.bounds().minX() || y < scene.bounds().minY()
						|| x + start.columns() * CELL_INCHES > scene.bounds().maxX()
						|| y + start.rows() * CELL_INCHES > scene.bounds().maxY()) {
					continue;
				}
				Point point = new Point(x, y);
				if (closed.contains(point)) {
					continue;
				}
				Footprint to = start.at(point);
				if (!transition(scene, from, to, occupied)) {
					continue;
				}
				long step = movementCost(scene, to);
				if (step > maxCostInches - current.cost) {
					costLimited = true;
					continue;
				}
				long nextCost = current.cost + step;
				if (nextCost < costs.getOrDefault(point, Long.MAX_VALUE)) {
					costs.put(point, nextCost);
					previous.put(point, current.point);
					open.add(new Node(point, nextCost, chebyshev(point, destination)));
				}
			}
		}
		return failed(costLimited ? PathStatus.COST_LIMIT_REACHED : PathStatus.NO_PATH, visited);
	}

	/** Validate exactly one 5 ft step; does not search a replacement route or spend movement. */
	public static boolean transition(Scene scene, Footprint from, Footprint to, Collection<Footprint> occupied) {
		long dx = to.origin().x() - from.origin().x();
		long dy = to.origin().y() - from.origin().y();
		if (from.columns() != to.columns() || from.rows() != to.rows() || Math.abs(dx) > CELL_INCHES
				|| Math.abs(dy) > CELL_INCHES || !canPlace(scene, to, occupied)) {
			return false;
		}
		if (dx != 0 && dy != 0) {
			if (!straightTransition(scene, from, from.at(new Point(to.origin().x(), from.origin().y())), occupied)
					|| !straightTransition(scene, from, from.at(new Point(from.origin().x(), to.origin().y())),
							occupied)) {
				return false;
			}
		}
		return straightTransition(scene, from, to, occupied);
	}

	/** Entry cost for one step, including the maximum terrain cost touched by the arriving body. */
	public static long movementCost(Scene scene, Footprint destination) {
		int multiplier = 1;
		for (Terrain terrain : scene.terrain()) {
			if (terrain.shape().bounds().overlapsInterior(destination.bounds())
					&& intersectionArea(terrain.shape(), destination.bounds().corners()) > EPSILON) {
				multiplier = Math.max(multiplier, terrain.movementMultiplier());
			}
		}
		return CELL_INCHES * multiplier;
	}

	/**
	 * Include a cell when its center lies in the area and on a floor, including exact area edges.
	 * Limit bounds the candidate cells examined, not just the number of hits. It fails explicitly
	 * rather than truncating a mechanically meaningful affected area.
	 */
	public static List<Point> areaCells(Scene scene, Area area, int maxCells) {
		if (maxCells < 1 || maxCells > 1_000_000) {
			throw new IllegalArgumentException("Area enumeration needs a positive bounded cell limit.");
		}
		Bounds b = area.bounds();
		long minX = Math.max(b.minX(), scene.bounds().minX());
		long minY = Math.max(b.minY(), scene.bounds().minY());
		long maxX = Math.min(b.maxX(), scene.bounds().maxX());
		long maxY = Math.min(b.maxY(), scene.bounds().maxY());
		if (minX > maxX || minY > maxY) {
			return List.of();
		}
		long firstX = ceilDiv(minX - CELL_INCHES / 2, CELL_INCHES);
		long firstY = ceilDiv(minY - CELL_INCHES / 2, CELL_INCHES);
		long lastX = Math.floorDiv(maxX - CELL_INCHES / 2, CELL_INCHES);
		long lastY = Math.floorDiv(maxY - CELL_INCHES / 2, CELL_INCHES);
		long columns = Math.max(0, lastX - firstX + 1);
		long rows = Math.max(0, lastY - firstY + 1);
		if (columns * rows > maxCells) {
			throw new IllegalArgumentException("Affected area exceeds the requested cell work limit.");
		}
		var result = new ArrayList<Point>();
		for (long y = firstY; y <= lastY; y++) {
			for (long x = firstX; x <= lastX; x++) {
				Point center = new Point(x * CELL_INCHES + CELL_INCHES / 2, y * CELL_INCHES + CELL_INCHES / 2);
				if (area.contains(center) && scene.floors().stream().anyMatch(floor -> floor.contains(center))) {
					result.add(center);
				}
			}
		}
		return List.copyOf(result);
	}

	private record Node(Point point, long cost, long remaining) {
		long estimate() {
			return cost + remaining;
		}
	}

	/**
	 * Break equal-cost ties toward the declared route without changing distance or collision rules.
	 */
	private static long lineDeviation(Point from, Point to, Point candidate) {
		return Math.abs(
				(to.x() - from.x()) * (candidate.y() - from.y()) - (to.y() - from.y()) * (candidate.x() - from.x()));
	}

	private record DPoint(double x, double y) {
	}

	private record Interval(double low, double high) {
	}

	private static PathResult failed(PathStatus status, int visited) {
		return new PathResult(status, List.of(), 0, visited);
	}

	private static boolean straightTransition(
		Scene scene, Footprint from, Footprint to, Collection<Footprint> occupied) {
		if (!canPlace(scene, to, occupied)) {
			return false;
		}
		var corners = new ArrayList<>(from.bounds().corners());
		corners.addAll(to.bounds().corners());
		List<Point> sweep = convexHull(corners);
		if (!floorCovers(scene, sweep) || blocked(scene, sweep)) {
			return false;
		}
		for (Footprint other : occupied) {
			if (intersectionArea(new Polygon(other.bounds().corners()), sweep) > EPSILON) {
				return false;
			}
		}
		return true;
	}

	private static boolean blocked(Scene scene, List<Point> body) {
		Bounds bounds = pointBounds(body);
		for (Polygon obstacle : scene.movementBlocks()) {
			if (bounds.overlapsInterior(obstacle.bounds()) && intersectionArea(obstacle, body) > EPSILON) {
				return true;
			}
		}
		for (Segment wall : scene.walls()) {
			if (bounds.intersects(wall.bounds()) && segmentEntersInterior(wall, body)) {
				return true;
			}
		}
		return false;
	}

	/** Sweep every arrangement slab: corner/center sampling would miss a thin hole or floor gap. */
	private static boolean floorCovers(Scene scene, List<Point> body) {
		Bounds bounds = pointBounds(body);
		List<Polygon> floors = scene.floors().stream().filter(floor -> bounds.overlapsInterior(floor.bounds()))
				.toList();
		if (floors.isEmpty()) {
			return false;
		}
		var segments = new ArrayList<>(Geometry.edges(body));
		floors.forEach(floor -> segments.addAll(floor.boundaries()));
		var cuts = new TreeSet<Double>();
		cuts.add((double) bounds.minX());
		cuts.add((double) bounds.maxX());
		for (Segment segment : segments) {
			if (segment.a().x() > bounds.minX() && segment.a().x() < bounds.maxX()) {
				cuts.add((double) segment.a().x());
			}
			if (segment.b().x() > bounds.minX() && segment.b().x() < bounds.maxX()) {
				cuts.add((double) segment.b().x());
			}
		}
		for (int i = 0; i < segments.size(); i++) {
			for (int j = i + 1; j < segments.size(); j++) {
				Segment a = segments.get(i);
				Segment b = segments.get(j);
				if (!Geometry.intersects(a, b)) {
					continue;
				}
				double dx = a.b().x() - a.a().x();
				double dy = a.b().y() - a.a().y();
				double ex = b.b().x() - b.a().x();
				double ey = b.b().y() - b.a().y();
				double determinant = dx * ey - dy * ex;
				if (determinant == 0) {
					continue;
				}
				double t = ((b.a().x() - a.a().x()) * ey - (b.a().y() - a.a().y()) * ex) / determinant;
				double x = a.a().x() + t * dx;
				if (x > bounds.minX() && x < bounds.maxX()) {
					cuts.add(x);
				}
			}
		}
		Double previous = null;
		for (double next : cuts) {
			if (previous != null && next - previous > EPSILON) {
				double x = (previous + next) / 2;
				var available = new ArrayList<Interval>();
				for (Polygon floor : floors) {
					available.addAll(verticalIntervals(floor, x));
				}
				available.sort(Comparator.comparingDouble(Interval::low));
				for (Interval required : verticalIntervals(new Polygon(body), x)) {
					double cursor = required.low;
					for (Interval interval : available) {
						if (interval.high < cursor) {
							continue;
						}
						if (interval.low > cursor + EPSILON) {
							break;
						}
						cursor = Math.max(cursor, interval.high);
					}
					if (cursor < required.high - EPSILON) {
						return false;
					}
				}
			}
			previous = next;
		}
		return true;
	}

	private static List<Interval> verticalIntervals(Polygon polygon, double x) {
		var crossings = new ArrayList<Double>();
		for (Segment edge : polygon.boundaries()) {
			Point a = edge.a();
			Point b = edge.b();
			if ((a.x() < x && b.x() > x) || (b.x() < x && a.x() > x)) {
				crossings.add(a.y() + (x - a.x()) * (b.y() - a.y()) / (b.x() - a.x()));
			}
		}
		crossings.sort(Double::compare);
		var result = new ArrayList<Interval>();
		for (int i = 0; i + 1 < crossings.size(); i += 2) {
			result.add(new Interval(crossings.get(i), crossings.get(i + 1)));
		}
		return result;
	}

	private static boolean segmentEntersInterior(Segment segment, List<Point> convex) {
		double low = 0;
		double high = 1;
		for (int i = 0; i < convex.size(); i++) {
			Point a = convex.get(i);
			Point b = convex.get((i + 1) % convex.size());
			double start = cross(a, b, new DPoint(segment.a().x(), segment.a().y()));
			double end = cross(a, b, new DPoint(segment.b().x(), segment.b().y()));
			double change = end - start;
			if (Math.abs(change) <= EPSILON) {
				if (start <= EPSILON) {
					return false;
				}
			} else if (change > 0) {
				low = Math.max(low, -start / change);
			} else {
				high = Math.min(high, -start / change);
			}
			if (low >= high) {
				return false;
			}
		}
		return high - low > EPSILON;
	}

	private static double intersectionArea(Polygon polygon, List<Point> convex) {
		double result = clippedArea(polygon.shell(), convex);
		for (List<Point> hole : polygon.holes()) {
			result -= clippedArea(hole, convex);
		}
		return Math.max(0, result);
	}

	private static double clippedArea(List<Point> ring, List<Point> convex) {
		List<DPoint> output = ring.stream().map(point -> new DPoint(point.x(), point.y())).toList();
		for (int i = 0; i < convex.size() && !output.isEmpty(); i++) {
			Point a = convex.get(i);
			Point b = convex.get((i + 1) % convex.size());
			List<DPoint> input = output;
			output = new ArrayList<>();
			DPoint previous = input.getLast();
			double previousSide = cross(a, b, previous);
			for (DPoint next : input) {
				double nextSide = cross(a, b, next);
				boolean previousInside = previousSide >= -EPSILON;
				boolean nextInside = nextSide >= -EPSILON;
				if (previousInside != nextInside) {
					double t = previousSide / (previousSide - nextSide);
					output.add(
							new DPoint(previous.x + t * (next.x - previous.x), previous.y + t * (next.y - previous.y)));
				}
				if (nextInside) {
					output.add(next);
				}
				previous = next;
				previousSide = nextSide;
			}
		}
		if (output.size() < 3) {
			return 0;
		}
		DPoint origin = output.getFirst();
		double twiceArea = 0;
		for (int i = 1; i < output.size() - 1; i++) {
			DPoint a = output.get(i);
			DPoint b = output.get(i + 1);
			twiceArea += (a.x - origin.x) * (b.y - origin.y) - (a.y - origin.y) * (b.x - origin.x);
		}
		return Math.abs(twiceArea) / 2;
	}

	private static double cross(Point a, Point b, DPoint p) {
		return (b.x() - a.x()) * (p.y - a.y()) - (b.y() - a.y()) * (p.x - a.x());
	}

	private static List<Point> convexHull(List<Point> supplied) {
		List<Point> points = supplied.stream().distinct()
				.sorted(Comparator.comparingLong(Point::x).thenComparingLong(Point::y)).toList();
		var result = new ArrayList<Point>();
		for (Point point : points) {
			while (result.size() >= 2 && Geometry.cross(result.get(result.size() - 2), result.getLast(), point) <= 0) {
				result.removeLast();
			}
			result.add(point);
		}
		int lowerSize = result.size();
		for (int i = points.size() - 2; i >= 0; i--) {
			Point point = points.get(i);
			while (result.size() > lowerSize
					&& Geometry.cross(result.get(result.size() - 2), result.getLast(), point) <= 0) {
				result.removeLast();
			}
			result.add(point);
		}
		result.removeLast();
		return result;
	}

	private static Bounds pointBounds(List<Point> points) {
		return new Bounds(points.stream().mapToLong(Point::x).min().orElseThrow(),
				points.stream().mapToLong(Point::y).min().orElseThrow(),
				points.stream().mapToLong(Point::x).max().orElseThrow(),
				points.stream().mapToLong(Point::y).max().orElseThrow());
	}

	private static long chebyshev(Point a, Point b) {
		return Math.max(Math.abs(a.x() - b.x()), Math.abs(a.y() - b.y()));
	}

	private static boolean pointOn(Segment segment, Point point) {
		return Geometry.cross(segment.a(), segment.b(), point) == 0 && segment.bounds().contains(point);
	}

	private static Bounds expanded(Point center, long radius) {
		return new Bounds(clamp(center.x() - radius), clamp(center.y() - radius), clamp(center.x() + radius),
				clamp(center.y() + radius));
	}

	private static long clamp(long value) {
		return Math.max(-Geometry.MAX_COORDINATE, Math.min(Geometry.MAX_COORDINATE, value));
	}

	private static long ceilDiv(long value, long divisor) {
		return -Math.floorDiv(-value, divisor);
	}

	private static void radius(long value) {
		if (value < 0 || value > 2 * Geometry.MAX_COORDINATE) {
			throw new IllegalArgumentException(
					"Area radius must be nonnegative and within the supported physical frame.");
		}
	}
}
