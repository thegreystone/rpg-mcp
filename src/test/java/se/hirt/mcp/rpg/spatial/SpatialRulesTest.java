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

import org.junit.jupiter.api.Test;
import se.hirt.mcp.rpg.spatial.Geometry.Bounds;
import se.hirt.mcp.rpg.spatial.Geometry.Footprint;
import se.hirt.mcp.rpg.spatial.Geometry.Point;
import se.hirt.mcp.rpg.spatial.Geometry.Polygon;
import se.hirt.mcp.rpg.spatial.Geometry.Scene;
import se.hirt.mcp.rpg.spatial.Geometry.Segment;
import se.hirt.mcp.rpg.spatial.Geometry.Terrain;
import se.hirt.mcp.rpg.spatial.SpatialRules.Circle;
import se.hirt.mcp.rpg.spatial.SpatialRules.Cone;
import se.hirt.mcp.rpg.spatial.SpatialRules.Cover;
import se.hirt.mcp.rpg.spatial.SpatialRules.Illumination;
import se.hirt.mcp.rpg.spatial.SpatialRules.Line;
import se.hirt.mcp.rpg.spatial.SpatialRules.Observer;
import se.hirt.mcp.rpg.spatial.SpatialRules.PathStatus;
import se.hirt.mcp.rpg.spatial.SpatialRules.PlacementStatus;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SpatialRulesTest {

	@Test
	void cellDistanceDoesNotTurnFiveFootReachIntoTenFeet() {
		Footprint small = f(0, 0);
		assertEquals(60, SpatialRules.distance(small, f(60, 0)));
		assertEquals(60, SpatialRules.distance(small, f(60, 60)));
		assertEquals(120, SpatialRules.distance(small, f(120, 0)));
		assertFalse(SpatialRules.canReach(small, f(120, 0), 60));
		assertEquals(60, SpatialRules.distance(new Footprint(p(0, 0), 2, 2), f(120, 60)));
		assertEquals(0, SpatialRules.distance(small, small));
	}

	@Test
	void placementUsesFloorUnionAndFindsSmallUnsampledHoles() {
		var adjacent = new Scene(List.of(Polygon.rectangle(0, 0, 60, 60), Polygon.rectangle(60, 0, 120, 60)), List.of(),
				List.of());
		assertTrue(SpatialRules.canPlace(adjacent, new Footprint(p(0, 0), 2, 1), List.of()));
		Polygon perforated = new Polygon(Polygon.rectangle(0, 0, 120, 120).shell(),
				List.of(Polygon.rectangle(10, 10, 11, 11).shell()));
		assertEquals(PlacementStatus.NO_FLOOR,
				SpatialRules.placement(new Scene(List.of(perforated), List.of(), List.of()), f(0, 0), List.of()));
		var gap = new Scene(List.of(Polygon.rectangle(0, 0, 20, 60), Polygon.rectangle(0, 0, 20, 60),
				Polygon.rectangle(21, 0, 60, 60)), List.of(), List.of());
		assertEquals(PlacementStatus.NO_FLOOR, SpatialRules.placement(gap, f(0, 0), List.of()));
	}

	@Test
	void placementAllowsBoundaryContactButNotOverlapOrOccupiedBodies() {
		var scene = scene(List.of(Polygon.rectangle(60, 0, 120, 60)), List.of(), List.of());
		assertEquals(PlacementStatus.VALID, SpatialRules.placement(scene, f(0, 0), List.of()));
		assertEquals(PlacementStatus.BLOCKED, SpatialRules.placement(scene, f(60, 0), List.of()));
		assertEquals(PlacementStatus.OCCUPIED, SpatialRules.placement(scene, f(0, 0), List.of(f(0, 0))));
		assertEquals(PlacementStatus.OUTSIDE_BOUNDS, SpatialRules.placement(scene, f(-60, 0), List.of()));
	}

	@Test
	void diagonalsCannotCutCornersButCanTakeTheLongerValidRoute() {
		var scene = scene(List.of(Polygon.rectangle(60, 0, 120, 60)), List.of(), List.of());
		assertFalse(SpatialRules.transition(scene, f(0, 0), f(60, 60), List.of()));
		var path = SpatialRules.path(scene, f(0, 0), p(60, 60), List.of(), 100, 1000);
		assertEquals(PathStatus.FOUND, path.status());
		assertEquals(List.of(p(0, 0), p(0, 60), p(60, 60)), path.origins());
		assertEquals(120, path.costInches());
		var trapped = scene(List.of(Polygon.rectangle(60, 0, 120, 60), Polygon.rectangle(0, 60, 60, 120)), List.of(),
				List.of());
		assertEquals(PathStatus.NO_PATH, SpatialRules.path(trapped, f(0, 0), p(60, 60), List.of(), 100, 1000).status());
	}

	@Test
	void thinWallsStopSweptMovementAndOpeningsPermitIt() {
		var wall = new Segment(p(60, 0), p(60, 60));
		var corridor = new Scene(new Bounds(0, 0, 180, 60), List.of(Polygon.rectangle(0, 0, 180, 60)), List.of(),
				List.of(), List.of(wall), List.of());
		assertTrue(SpatialRules.canPlace(corridor, f(0, 0), List.of()));
		assertTrue(SpatialRules.canPlace(corridor, f(60, 0), List.of()));
		assertFalse(SpatialRules.transition(corridor, f(0, 0), f(60, 0), List.of()));
		assertEquals(PathStatus.NO_PATH,
				SpatialRules.path(corridor, f(0, 0), p(120, 0), List.of(), 100, 1000).status());
		var doorway = scene(List.of(),
				List.of(new Segment(p(120, 0), p(120, 60)), new Segment(p(120, 120), p(120, 600))), List.of());
		assertTrue(SpatialRules.transition(doorway, f(60, 60), f(120, 60), List.of()));
		assertTrue(SpatialRules.lineOfSight(doorway, p(90, 90), p(150, 90)));
		assertFalse(SpatialRules.lineOfSight(doorway, p(90, 30), p(150, 30)));
	}

	@Test
	void pathChoosesCheaperGroundAndDoesNotMultiplyOverlappingTerrain() {
		Polygon rough = Polygon.rectangle(60, 0, 240, 60);
		var scene = scene(List.of(), List.of(), List.of(new Terrain(rough, 2), new Terrain(rough, 3)));
		assertEquals(180, SpatialRules.movementCost(scene, f(60, 0)));
		var path = SpatialRules.path(scene, f(0, 0), p(240, 0), List.of(), 100, 1000);
		assertTrue(path.found());
		assertEquals(240, path.costInches());
		assertTrue(path.origins().stream().anyMatch(point -> point.y() == 60));
		assertEquals(path, SpatialRules.path(scene, f(0, 0), p(240, 0), List.of(), 100, 1000));
	}

	@Test
	void searchWorkAndMovementBudgetHaveExplicitFailureStatuses() {
		Scene scene = scene(List.of(), List.of(), List.of());
		assertEquals(PathStatus.COST_LIMIT_REACHED,
				SpatialRules.path(scene, f(0, 0), p(180, 0), List.of(), 100, 120).status());
		assertEquals(PathStatus.LIMIT_REACHED,
				SpatialRules.path(scene, f(0, 0), p(180, 0), List.of(), 1, 1000).status());
		assertEquals(PathStatus.INVALID_DESTINATION,
				SpatialRules.path(scene, f(0, 0), p(31, 0), List.of(), 100, 1000).status());
		assertEquals(PathStatus.INVALID_START,
				SpatialRules.path(scene, f(0, 0), p(180, 0), List.of(f(0, 0)), 100, 1000).status());
	}

	@Test
	void largeCreaturesCannotSqueezeThroughSingleCellCorridors() {
		var floors = List.of(Polygon.rectangle(0, 0, 120, 180), Polygon.rectangle(120, 60, 300, 120),
				Polygon.rectangle(300, 0, 420, 180));
		var scene = new Scene(floors, List.of(), List.of());
		assertTrue(SpatialRules.path(scene, f(0, 60), p(300, 60), List.of(), 100, 1000).found());
		assertEquals(PathStatus.NO_PATH,
				SpatialRules.path(scene, new Footprint(p(0, 0), 2, 2), p(300, 0), List.of(), 100, 1000).status());
	}

	@Test
	void negativeCoordinatesUseTheSameLatticeAndAreaBoundaries() {
		var scene = new Scene(List.of(Polygon.rectangle(-180, -180, 180, 180)), List.of(), List.of());
		var path = SpatialRules.path(scene, f(-120, -60), p(0, 0), List.of(), 100, 1000);
		assertTrue(path.found());
		assertEquals(120, path.costInches());
		assertEquals(List.of(p(-90, -90), p(-30, -90), p(-90, -30), p(-30, -30)),
				SpatialRules.areaCells(scene, new SpatialRules.Rectangle(new Bounds(-120, -120, 0, 0)), 100));
	}

	@Test
	void equalCostPathsPreferTheDeclaredStraightRouteAndOccupiedNarrowPassagesRemainBlocked() {
		var path = SpatialRules.path(scene(List.of(), List.of(), List.of()), f(60, 120), p(420, 120), List.of(), 1000,
				1000);
		assertTrue(path.found());
		assertEquals(360, path.costInches());
		assertTrue(path.origins().stream().allMatch(point -> point.y() == 120),
				"Tie breaking should not insert a decorative zigzag.");
		Scene narrow = new Scene(List.of(Polygon.rectangle(0, 0, 360, 60)), List.of(), List.of());
		assertEquals(SpatialRules.PathStatus.NO_PATH,
				SpatialRules.path(narrow, f(0, 0), p(300, 0), List.of(f(180, 0)), 1000, 1000).status());
	}

	@Test
	void savedObjectSupportUsesTheFullRectangleAndTheFloorUnion() {
		var floor = new Polygon(Polygon.rectangle(0, 0, 600, 600).shell(),
				List.of(Polygon.rectangle(100, 100, 110, 110).shell()));
		Scene scene = new Scene(List.of(floor), List.of(Polygon.rectangle(0, 0, 60, 60)), List.of());
		assertTrue(SpatialRules.floorSupports(scene, new Bounds(0, 0, 60, 60)),
				"An object's own collision block is irrelevant to support.");
		assertFalse(SpatialRules.floorSupports(scene, new Bounds(60, 60, 120, 120)),
				"A small interior hole must not be missed by corner sampling.");
		assertFalse(SpatialRules.floorSupports(scene, new Bounds(580, 580, 620, 620)));
		Scene joined = new Scene(List.of(Polygon.rectangle(0, 0, 60, 120), Polygon.rectangle(60, 0, 120, 120)),
				List.of(), List.of());
		assertTrue(SpatialRules.floorSupports(joined, new Bounds(30, 30, 90, 90)));
	}

	@Test
	void sightUsesExplicitOcclusionAndTreatsBoundaryContactAsBlocked() {
		var scene = scene(List.of(), List.of(new Segment(p(120, 120), p(120, 240))), List.of());
		assertFalse(SpatialRules.lineOfSight(scene, p(30, 30), p(210, 210)));
		assertTrue(SpatialRules.lineOfSight(scene, p(30, 30), p(210, 90)));
		var pit = new Polygon(Polygon.rectangle(0, 0, 300, 300).shell(),
				List.of(Polygon.rectangle(60, 60, 120, 120).shell()));
		assertTrue(SpatialRules.lineOfSight(new Scene(List.of(pit), List.of(), List.of()), p(30, 90), p(150, 90)));
		var independent = new Scene(new Bounds(0, 0, 600, 600), List.of(Polygon.rectangle(0, 0, 600, 600)),
				List.of(Polygon.rectangle(60, 0, 120, 60)), List.of(), List.of(), List.of());
		assertTrue(SpatialRules.lineOfSight(independent, p(30, 30), p(150, 30)));
		assertFalse(SpatialRules.canPlace(independent, f(60, 0), List.of()));
	}

	@Test
	void eachObserverUsesItsOwnLightingRangeAndOcclusion() {
		Scene clear = scene(List.of(), List.of(), List.of());
		Observer darkvision = new Observer(p(30, 30), 240, 120);
		Observer ordinary = new Observer(p(30, 30), 240, 0);
		assertEquals(new SpatialRules.Observation(true, Illumination.DIM),
				SpatialRules.perceive(clear, darkvision, p(150, 30), Illumination.DARK));
		assertFalse(SpatialRules.perceive(clear, ordinary, p(150, 30), Illumination.DARK).visible());
		assertFalse(SpatialRules.perceive(clear, darkvision, p(210, 30), Illumination.DARK).visible());
		assertEquals(Illumination.BRIGHT,
				SpatialRules.perceive(clear, darkvision, p(150, 30), Illumination.DIM).apparentIllumination());
		assertFalse(SpatialRules.perceive(clear, darkvision, p(330, 30), Illumination.BRIGHT).visible());
		Scene blocked = scene(List.of(), List.of(new Segment(p(60, 0), p(60, 600))), List.of());
		assertFalse(SpatialRules.perceive(blocked, darkvision, p(150, 30), Illumination.BRIGHT).visible());
	}

	@Test
	void coverSamplesAreDeterministicAndDoNotEquateOneBlockedRayWithTotalCover() {
		Footprint target = f(180, 120);
		Point observer = p(30, 150);
		assertEquals(Cover.NONE, SpatialRules.cover(scene(List.of(), List.of(), List.of()), observer, target).cover());
		var partial = scene(List.of(), List.of(new Segment(p(120, 155), p(120, 180))), List.of());
		assertEquals(Cover.HALF, SpatialRules.cover(partial, observer, target).cover());
		var complete = scene(List.of(), List.of(new Segment(p(120, 0), p(120, 600))), List.of());
		assertEquals(Cover.TOTAL, SpatialRules.cover(complete, observer, target).cover());
	}

	@Test
	void areasIncludeExactCellCenterEdgesAndDistinguishCircularAndGridDistance() {
		Scene scene = scene(List.of(), List.of(), List.of());
		assertEquals(List.of(p(150, 90), p(90, 150), p(150, 150), p(210, 150), p(150, 210)),
				SpatialRules.areaCells(scene, new Circle(p(150, 150), 60), 100));
		assertEquals(List.of(p(30, 30), p(90, 30), p(150, 30), p(210, 30)),
				SpatialRules.areaCells(scene, new Line(p(30, 30), p(210, 30), 60), 100));
		Cone cone = new Cone(p(30, 150), p(150, 150), 120, 90);
		assertTrue(cone.contains(p(90, 210)));
		assertTrue(cone.contains(p(150, 150)));
		assertFalse(cone.contains(p(30, 210)));
		assertFalse(cone.contains(p(150, 270)));
		assertThrows(IllegalArgumentException.class,
				() -> SpatialRules.areaCells(scene, new Circle(p(150, 150), 600), 1));
	}

	@Test
	void malformedGeometryFailsBeforeEnteringRuntimeAndInputsAreImmutable() {
		assertThrows(IllegalArgumentException.class,
				() -> new Polygon(List.of(p(0, 0), p(60, 60), p(0, 60), p(60, 0))));
		assertThrows(IllegalArgumentException.class, () -> new Polygon(Polygon.rectangle(0, 0, 60, 60).shell(),
				List.of(Polygon.rectangle(30, 30, 90, 90).shell())));
		assertThrows(IllegalArgumentException.class, () -> new Footprint(p(1, 0), 1, 1));
		assertThrows(IllegalArgumentException.class, () -> p(Geometry.MAX_COORDINATE + 1, 0));
		var mutable = new ArrayList<>(Polygon.rectangle(0, 0, 60, 60).shell());
		Polygon polygon = new Polygon(mutable);
		mutable.clear();
		assertEquals(4, polygon.shell().size());
		assertThrows(UnsupportedOperationException.class, () -> polygon.shell().clear());
	}

	private static Scene scene(List<Polygon> blocks, List<Segment> walls, List<Terrain> terrain) {
		return new Scene(new Bounds(0, 0, 600, 600), List.of(Polygon.rectangle(0, 0, 600, 600)), blocks, blocks, walls,
				terrain);
	}

	private static Point p(long x, long y) {
		return new Point(x, y);
	}

	private static Footprint f(long x, long y) {
		return new Footprint(p(x, y), 1, 1);
	}
}
