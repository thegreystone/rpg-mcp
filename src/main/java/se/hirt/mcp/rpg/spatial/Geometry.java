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

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Immutable physical geometry for one tactical level. Coordinates are integer inches, x east and y
 * north. Bounds and polygon boundaries are closed; physical footprints may touch a blocking
 * boundary but may not occupy its interior. The coordinate limit keeps integer orientation tests
 * exact and bounds malformed input. No presentation pixels or persistent entity state live here.
 */
public final class Geometry {

	public static final long CELL_INCHES = 60;
	public static final long MAX_COORDINATE = 1_000_000_000;
	private static final int MAX_RING_VERTICES = 4096;
	static final double EPSILON = 0.0000001;

	private Geometry() {
	}

	public record Point(long x, long y) {
		public Point {
			coordinate(x);
			coordinate(y);
		}

		public Point translate(long dx, long dy) {
			return new Point(Math.addExact(x, dx), Math.addExact(y, dy));
		}
	}

	public record Segment(Point a, Point b) {
		public Segment {
			Objects.requireNonNull(a, "a");
			Objects.requireNonNull(b, "b");
			if (a.equals(b)) {
				throw new IllegalArgumentException("A segment needs distinct endpoints.");
			}
		}

		public Bounds bounds() {
			return new Bounds(Math.min(a.x, b.x), Math.min(a.y, b.y), Math.max(a.x, b.x), Math.max(a.y, b.y));
		}
	}

	/** Zero-width bounds are permitted for lines and area envelopes. */
	public record Bounds(long minX, long minY, long maxX, long maxY) {
		public Bounds {
			coordinate(minX);
			coordinate(minY);
			coordinate(maxX);
			coordinate(maxY);
			if (maxX < minX || maxY < minY) {
				throw new IllegalArgumentException("Bounds must have ordered coordinates.");
			}
		}

		public long width() {
			return maxX - minX;
		}

		public long height() {
			return maxY - minY;
		}

		public boolean contains(Point point) {
			return point.x >= minX && point.x <= maxX && point.y >= minY && point.y <= maxY;
		}

		public boolean contains(Bounds other) {
			return minX <= other.minX && minY <= other.minY && maxX >= other.maxX && maxY >= other.maxY;
		}

		public boolean intersects(Bounds other) {
			return maxX >= other.minX && other.maxX >= minX && maxY >= other.minY && other.maxY >= minY;
		}

		public boolean overlapsInterior(Bounds other) {
			return maxX > other.minX && other.maxX > minX && maxY > other.minY && other.maxY > minY;
		}

		public List<Point> corners() {
			return List.of(new Point(minX, minY), new Point(maxX, minY), new Point(maxX, maxY), new Point(minX, maxY));
		}
	}

	/** A simple shell and disjoint simple holes; either winding order is accepted. */
	public record Polygon(List<Point> shell, List<List<Point>> holes) {
		public Polygon {
			shell = ring(shell);
			var copiedHoles = new ArrayList<List<Point>>();
			for (List<Point> hole : Objects.requireNonNull(holes, "holes")) {
				List<Point> normalized = ring(hole);
				if (ringRelation(shell, normalized.getFirst().x, normalized.getFirst().y) != 1
						|| ringsIntersect(shell, normalized)) {
					throw new IllegalArgumentException("A hole must be strictly inside its shell.");
				}
				for (List<Point> existing : copiedHoles) {
					if (ringsIntersect(existing, normalized)
							|| ringRelation(existing, normalized.getFirst().x, normalized.getFirst().y) >= 0
							|| ringRelation(normalized, existing.getFirst().x, existing.getFirst().y) >= 0) {
						throw new IllegalArgumentException("Polygon holes must be disjoint.");
					}
				}
				copiedHoles.add(normalized);
			}
			holes = List.copyOf(copiedHoles);
		}

		public Polygon(List<Point> shell) {
			this(shell, List.of());
		}

		public static Polygon rectangle(long minX, long minY, long maxX, long maxY) {
			if (maxX <= minX || maxY <= minY) {
				throw new IllegalArgumentException("A rectangle must have positive area.");
			}
			return new Polygon(new Bounds(minX, minY, maxX, maxY).corners());
		}

		public Bounds bounds() {
			long minX = shell.stream().mapToLong(Point::x).min().orElseThrow();
			long minY = shell.stream().mapToLong(Point::y).min().orElseThrow();
			long maxX = shell.stream().mapToLong(Point::x).max().orElseThrow();
			long maxY = shell.stream().mapToLong(Point::y).max().orElseThrow();
			return new Bounds(minX, minY, maxX, maxY);
		}

		public boolean contains(Point point) {
			return relation(point.x, point.y) >= 0;
		}

		public boolean containsInterior(Point point) {
			return relation(point.x, point.y) > 0;
		}

		int relation(double x, double y) {
			int outer = ringRelation(shell, x, y);
			if (outer < 1) {
				return outer;
			}
			for (List<Point> hole : holes) {
				int inner = ringRelation(hole, x, y);
				if (inner == 0) {
					return 0;
				}
				if (inner == 1) {
					return -1;
				}
			}
			return 1;
		}

		public List<Segment> boundaries() {
			var segments = new ArrayList<>(edges(shell));
			holes.forEach(hole -> segments.addAll(edges(hole)));
			return List.copyOf(segments);
		}
	}

	/** Origin is the southwest corner on the 5 ft lattice; dimensions count occupied cells. */
	public record Footprint(Point origin, int columns, int rows) {
		public Footprint {
			Objects.requireNonNull(origin, "origin");
			if (Math.floorMod(origin.x, CELL_INCHES) != 0 || Math.floorMod(origin.y, CELL_INCHES) != 0) {
				throw new IllegalArgumentException("A footprint origin must lie on the 5 ft lattice.");
			}
			if (columns <= 0 || rows <= 0 || columns > 100 || rows > 100) {
				throw new IllegalArgumentException("A footprint must occupy 1 through 100 cells on each axis.");
			}
			coordinate(origin.x + columns * CELL_INCHES);
			coordinate(origin.y + rows * CELL_INCHES);
		}

		public Bounds bounds() {
			return new Bounds(origin.x, origin.y, origin.x + columns * CELL_INCHES, origin.y + rows * CELL_INCHES);
		}

		public Footprint at(Point nextOrigin) {
			return new Footprint(nextOrigin, columns, rows);
		}

		public Point center() {
			return new Point(origin.x + columns * CELL_INCHES / 2, origin.y + rows * CELL_INCHES / 2);
		}

		public List<Point> cellCenters() {
			var result = new ArrayList<Point>(columns * rows);
			for (int y = 0; y < rows; y++) {
				for (int x = 0; x < columns; x++) {
					result.add(new Point(origin.x + x * CELL_INCHES + CELL_INCHES / 2,
							origin.y + y * CELL_INCHES + CELL_INCHES / 2));
				}
			}
			return List.copyOf(result);
		}
	}

	/** Overlapping costs take their maximum rather than multiplying the same ground twice. */
	public record Terrain(Polygon shape, int movementMultiplier) {
		public Terrain {
			Objects.requireNonNull(shape, "shape");
			if (movementMultiplier < 1 || movementMultiplier > 1000) {
				throw new IllegalArgumentException("Terrain movement multiplier must be from 1 through 1000.");
			}
		}
	}

	/**
	 * One level's physical rules inputs. Floors can overlap or meet at shared edges. Floor holes do
	 * not implicitly block sight. Movement/sight blocks are independent, while walls block both.
	 * Dynamic occupied footprints are supplied separately, so moving an actor needs no scene
	 * rebuild.
	 */
	public record Scene(Bounds bounds, List<Polygon> floors, List<Polygon> movementBlocks, List<Polygon> sightBlocks,
			List<Segment> walls, List<Terrain> terrain) {
		public Scene {
			Objects.requireNonNull(bounds, "bounds");
			floors = List.copyOf(floors);
			movementBlocks = List.copyOf(movementBlocks);
			sightBlocks = List.copyOf(sightBlocks);
			walls = List.copyOf(walls);
			terrain = List.copyOf(terrain);
			if (bounds.width() == 0 || bounds.height() == 0 || floors.isEmpty()) {
				throw new IllegalArgumentException("A scene needs positive bounds and at least one floor region.");
			}
		}

		public Scene(List<Polygon> floors, List<Polygon> blocks, List<Terrain> terrain) {
			this(envelope(floors), floors, blocks, blocks, List.of(), terrain);
		}
	}

	static List<Segment> edges(List<Point> ring) {
		var result = new ArrayList<Segment>(ring.size());
		for (int i = 0; i < ring.size(); i++) {
			result.add(new Segment(ring.get(i), ring.get((i + 1) % ring.size())));
		}
		return result;
	}

	static long cross(Point a, Point b, Point c) {
		return (b.x - a.x) * (c.y - a.y) - (b.y - a.y) * (c.x - a.x);
	}

	static boolean intersects(Segment a, Segment b) {
		if (!a.bounds().intersects(b.bounds())) {
			return false;
		}
		long aa = cross(a.a, a.b, b.a);
		long ab = cross(a.a, a.b, b.b);
		long ba = cross(b.a, b.b, a.a);
		long bb = cross(b.a, b.b, a.b);
		return (aa == 0 && onSegment(a, b.a)) || (ab == 0 && onSegment(a, b.b)) || (ba == 0 && onSegment(b, a.a))
				|| (bb == 0 && onSegment(b, a.b))
				|| (Long.signum(aa) != Long.signum(ab) && Long.signum(ba) != Long.signum(bb));
	}

	private static boolean onSegment(Segment segment, Point point) {
		return segment.bounds().contains(point);
	}

	static int ringRelation(List<Point> ring, double x, double y) {
		boolean inside = false;
		for (int i = 0, j = ring.size() - 1; i < ring.size(); j = i++) {
			Point a = ring.get(j);
			Point b = ring.get(i);
			double cross = (b.x - a.x) * (y - a.y) - (b.y - a.y) * (x - a.x);
			if (Math.abs(cross) <= EPSILON && x >= Math.min(a.x, b.x) && x <= Math.max(a.x, b.x)
					&& y >= Math.min(a.y, b.y) && y <= Math.max(a.y, b.y)) {
				return 0;
			}
			if ((a.y > y) != (b.y > y) && x < (double) (b.x - a.x) * (y - a.y) / (b.y - a.y) + a.x) {
				inside = !inside;
			}
		}
		return inside ? 1 : -1;
	}

	private static List<Point> ring(List<Point> supplied) {
		var points = new ArrayList<>(Objects.requireNonNull(supplied, "ring"));
		if (points.size() > 1 && points.getFirst().equals(points.getLast())) {
			points.removeLast();
		}
		if (points.size() < 3 || points.size() > MAX_RING_VERTICES) {
			throw new IllegalArgumentException("A polygon ring needs 3 through " + MAX_RING_VERTICES + " vertices.");
		}
		points.forEach(p -> Objects.requireNonNull(p, "ring vertex"));
		var segments = edges(points);
		for (int i = 0; i < segments.size(); i++) {
			for (int j = i + 1; j < segments.size(); j++) {
				if (j == i + 1 || (i == 0 && j == segments.size() - 1)) {
					continue;
				}
				if (intersects(segments.get(i), segments.get(j))) {
					throw new IllegalArgumentException("A polygon ring cannot intersect itself.");
				}
			}
		}
		double area = 0;
		Point origin = points.getFirst();
		for (int i = 1; i < points.size() - 1; i++) {
			area += cross(origin, points.get(i), points.get(i + 1));
		}
		if (area == 0) {
			throw new IllegalArgumentException("A polygon ring must have positive area.");
		}
		return List.copyOf(points);
	}

	private static boolean ringsIntersect(List<Point> a, List<Point> b) {
		for (Segment first : edges(a)) {
			for (Segment second : edges(b)) {
				if (intersects(first, second)) {
					return true;
				}
			}
		}
		return false;
	}

	private static Bounds envelope(List<Polygon> polygons) {
		if (polygons == null || polygons.isEmpty()) {
			throw new IllegalArgumentException("A scene needs at least one floor region.");
		}
		long minX = Long.MAX_VALUE;
		long minY = Long.MAX_VALUE;
		long maxX = Long.MIN_VALUE;
		long maxY = Long.MIN_VALUE;
		for (Polygon polygon : polygons) {
			Bounds b = polygon.bounds();
			minX = Math.min(minX, b.minX);
			minY = Math.min(minY, b.minY);
			maxX = Math.max(maxX, b.maxX);
			maxY = Math.max(maxY, b.maxY);
		}
		return new Bounds(minX, minY, maxX, maxY);
	}

	private static void coordinate(long value) {
		if (value < -MAX_COORDINATE || value > MAX_COORDINATE) {
			throw new IllegalArgumentException("Tactical coordinates exceed the supported physical frame.");
		}
	}
}
