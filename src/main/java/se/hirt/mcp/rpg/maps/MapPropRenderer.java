/*
 * Copyright (C) 2026 Marcus Hirt
 * SPDX-License-Identifier: BSD-3-Clause
 */
package se.hirt.mcp.rpg.maps;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Pure-Java procedural surface, prop, architectural and atmospheric lighting renderer for tactical
 * maps. Strictly world-coordinate-stable, deterministic and GraalVM native-image safe.
 */
final class MapPropRenderer {

	private MapPropRenderer() {
	}

	record Light(double x, double y, double radiusFt, int color, double intensity) {
	}

	record WallSegment(double ax, double ay, double bx, double by) {
	}

	@FunctionalInterface
	interface PixelShader {
		int shade(double lx, double ly, double halfW, double halfH, double wx, double wy);
	}

	// --- Deterministic world-space noise ---

	static int hash2(int x, int y) {
		int h = x * 374761393 + y * 668265263;
		h = (h ^ (h >>> 13)) * 1274126177;
		return h ^ (h >>> 16);
	}

	static double hash01(int x, int y) {
		return (hash2(x, y) & 0x7fffffff) / (double) 0x7fffffff;
	}

	static double valueNoise(double x, double y) {
		int ix = (int) Math.floor(x), iy = (int) Math.floor(y);
		double fx = x - ix, fy = y - iy;
		double ux = fx * fx * (3 - 2 * fx), uy = fy * fy * (3 - 2 * fy);
		double n00 = hash01(ix, iy), n10 = hash01(ix + 1, iy);
		double n01 = hash01(ix, iy + 1), n11 = hash01(ix + 1, iy + 1);
		return lerp(lerp(n00, n10, ux), lerp(n01, n11, ux), uy);
	}

	static double fbm(double x, double y) {
		return valueNoise(x, y) * 0.65 + valueNoise(x * 2.13 + 17.3, y * 2.13 - 9.7) * 0.35;
	}

	// --- Rich world-space procedural floor & terrain materials ---

	static int tacticalMaterial(String material, String style, double x, double y) {
		String name = material.toUpperCase(Locale.ROOT);
		boolean parchment = "PARCHMENT".equals(style);
		int color = switch (name) {
		case "WOOD", "TIMBER" -> woodPlanks(x, y, 0xffa68259, 14);
		case "DARK_WOOD" -> woodPlanks(x, y, 0xff6e4f35, 11);
		case "STONE", "FLAGSTONE" -> flagstones(x, y, 0xffaba99d, false);
		case "DAMP_STONE", "MOSS", "DAMP" -> flagstones(x, y, 0xff8f9984, true);
		case "ROUGH_STONE", "CAVE", "ROCK" -> roughStone(x, y);
		case "COBBLESTONE", "COBBLE" -> cobblestone(x, y);
		case "TILE", "DECORATIVE_TILE" -> decorativeTile(x, y);
		case "CARPET" -> carpetFloor(x, y);
		case "GRASS", "GRASSLAND", "FIELD" -> grassFloor(x, y);
		case "FOREST" -> forestFloor(x, y);
		case "EARTH", "DIRT" -> earthFloor(x, y, 0xff9a825c);
		case "MUD" -> earthFloor(x, y, 0xff6d583e);
		case "SAND" -> sandFloor(x, y);
		case "WATER", "SHALLOW_WATER", "RIVER", "LAKE", "OCEAN" -> waterFloor(x, y);
		case "ICE" -> iceFloor(x, y);
		case "SNOW" -> snowFloor(x, y);
		case "VOID", "PIT" -> parchment ? 0xff706656 : 0xff222a30;
		default -> flagstones(x, y, 0xffaba99d, false);
		};
		if (parchment && !name.equals("VOID") && !name.equals("PIT")) {
			color = blend(color, 0x52dcc69c);
		}
		return color;
	}

	private static int woodPlanks(double x, double y, int base, int variance) {
		double plankH = 0.85, plankL = 7.0;
		int row = (int) Math.floor(y / plankH);
		double rowFrac = y / plankH - row;
		double stagger = hash01(row, 19) * plankL;
		double sx = x + stagger;
		int col = (int) Math.floor(sx / plankL);
		double colFrac = sx / plankL - col;
		int plankTone = (int) Math.round((hash01(col * 31 + row, row * 17) - 0.5) * 2 * variance);
		double grain = (fbm(sx * 1.15, y * 10.5 + row * 3.1) - 0.5) * 14.0;
		int c = shade(base, plankTone + (int) Math.round(grain));
		if (rowFrac < 0.065 || rowFrac > 0.945) {
			c = shade(c, -26);
		} else if (rowFrac < 0.13) {
			c = shade(c, 5);
		}
		if (colFrac < 0.012 || colFrac > 0.988) {
			c = shade(c, -22);
		} else if ((colFrac < 0.032 || colFrac > 0.968) && Math.abs(rowFrac - 0.5) > 0.20
				&& Math.abs(rowFrac - 0.5) < 0.30) {
			c = shade(c, -18);
		}
		return c;
	}

	private static int flagstones(double x, double y, int base, boolean mossy) {
		double tileW = 2.5, tileH = 2.0;
		int row = (int) Math.floor(y / tileH);
		double rowFrac = y / tileH - row;
		double offset = (Math.floorMod(row, 2) == 0 ? 0.0 : tileW * 0.5) + (hash01(row, 71) - 0.5) * 0.35;
		double sx = x + offset;
		int col = (int) Math.floor(sx / tileW);
		double colFrac = sx / tileW - col;
		int stoneTone = (int) Math.round((hash01(col * 13 + 5, row * 29 + 3) - 0.5) * 18.0);
		double grit = (fbm(x * 3.2, y * 3.2) - 0.5) * 12.0;
		int c = shade(base, stoneTone + (int) Math.round(grit));
		boolean mortar = rowFrac < 0.045 || rowFrac > 0.955 || colFrac < 0.036 || colFrac > 0.964;
		if (mortar) {
			c = shade(c, -28);
		} else if (rowFrac < 0.09 || colFrac < 0.075) {
			c = shade(c, 6);
		} else if (rowFrac > 0.91 || colFrac > 0.925) {
			c = shade(c, -7);
		}
		if (mossy) {
			double moss = fbm(x * 0.55 + 4.1, y * 0.55 - 2.7);
			if (moss > 0.58 || (mortar && moss > 0.45)) {
				double strength = Math.min(1.0, (moss - (mortar ? 0.45 : 0.58)) * 3.0);
				c = mixColor(c, 0xff5a744a, strength * 0.55);
			}
		}
		return c;
	}

	private static int roughStone(double x, double y) {
		double broad = fbm(x * 0.45, y * 0.45);
		double fine = fbm(x * 2.8 + 11.1, y * 2.8 - 5.3);
		int base = mixColor(0xff777870, 0xff9b9a8e, broad);
		int c = shade(base, (int) Math.round((fine - 0.5) * 26.0));
		double crack = Math.abs(valueNoise(x * 0.9, y * 0.9) - 0.5);
		if (crack < 0.025) {
			c = shade(c, -22);
		}
		return c;
	}

	private static int cobblestone(double x, double y) {
		double w = 0.85, h = 0.7;
		int row = (int) Math.floor(y / h);
		double sx = x + (Math.floorMod(row, 2) == 0 ? 0.0 : w * 0.5);
		int col = (int) Math.floor(sx / w);
		double fx = (sx / w - col) * 2 - 1, fy = (y / h - row) * 2 - 1;
		double d = Math.hypot(fx * 0.92, fy * 1.05);
		if (d > 0.88) {
			return 0xff626058;
		}
		int tone = (int) Math.round((hash01(col * 19, row * 37) - 0.5) * 22);
		int dome = (int) Math.round((1.0 - d) * 14 - (fx * 4 - fy * 4));
		return shade(0xff9c9a90, tone + dome);
	}

	private static int decorativeTile(double x, double y) {
		double size = 2.5;
		int ix = (int) Math.floor(x / size), iy = (int) Math.floor(y / size);
		double fx = x / size - ix, fy = y / size - iy;
		boolean light = Math.floorMod(ix + iy, 2) == 0;
		int base = light ? 0xffd5cbb2 : 0xff9e9888;
		double vein = Math.abs(Math.sin((x + y * 0.7) * 2.2 + fbm(x * 1.5, y * 1.5) * 3.0));
		int c = shade(base, vein < 0.08 ? -10 : (int) Math.round((fbm(x * 2.5, y * 2.5) - 0.5) * 8));
		if (fx < 0.035 || fx > 0.965 || fy < 0.035 || fy > 0.965) {
			c = shade(c, -22);
		}
		return c;
	}

	private static int carpetFloor(double x, double y) {
		int base = 0xff7c3335;
		double weave = ((int) Math.floor(x * 12) + (int) Math.floor(y * 12)) % 2 == 0 ? 4 : -4;
		double sheen = (fbm(x * 0.8, y * 0.8) - 0.5) * 14.0;
		return shade(base, (int) Math.round(weave + sheen));
	}

	private static int grassFloor(double x, double y) {
		double meadow = fbm(x * 0.14, y * 0.14);
		double tuft = fbm(x * 1.8 + 5.2, y * 1.8 - 3.9);
		double blade = valueNoise(x * 7.5, y * 7.5);
		int c = mixColor(0xff778f58, 0xff99ad6d, meadow);
		c = shade(c, (int) Math.round((tuft - 0.5) * 16 + (blade - 0.5) * 10));
		return c;
	}

	private static int forestFloor(double x, double y) {
		double canopy = fbm(x * 0.18 + 9.4, y * 0.18 + 3.1);
		double litter = fbm(x * 1.5, y * 1.5);
		int c = mixColor(0xff4c6b47, 0xff6b7a4c, canopy);
		if (litter > 0.67) {
			c = mixColor(c, 0xff6e5e44, (litter - 0.67) * 1.8);
		}
		return shade(c, (int) Math.round((valueNoise(x * 6.0, y * 6.0) - 0.5) * 10));
	}

	private static int earthFloor(double x, double y, int base) {
		double patch = fbm(x * 0.35, y * 0.35);
		double pebble = valueNoise(x * 5.5, y * 5.5);
		int c = shade(base, (int) Math.round((patch - 0.5) * 20 + (pebble - 0.5) * 14));
		if (pebble > 0.84) {
			c = shade(c, 12);
		}
		return c;
	}

	private static int sandFloor(double x, double y) {
		double ripple = Math.sin(x * 1.3 + y * 0.4 + fbm(x * 0.5, y * 0.5) * 2.5);
		return shade(0xffd2be8d, (int) Math.round(ripple * 7 + (valueNoise(x * 6.0, y * 6.0) - 0.5) * 8));
	}

	private static int waterFloor(double x, double y) {
		double depth = fbm(x * 0.2, y * 0.2);
		double wave = Math.sin(y * 2.6 + fbm(x * 0.9, y * 0.9) * 4.2);
		int c = mixColor(0xff427a92, 0xff679eb2, depth);
		return shade(c, wave > 0.72 ? 16 : (int) Math.round(wave * 6));
	}

	private static int iceFloor(double x, double y) {
		double sheen = fbm(x * 0.4, y * 0.4);
		double streak = Math.abs(Math.sin((x - y) * 1.8 + sheen * 3.0));
		int c = mixColor(0xffafd4de, 0xffd7edf2, sheen);
		return streak < 0.07 ? shade(c, 18) : c;
	}

	private static int snowFloor(double x, double y) {
		double drift = fbm(x * 0.25, y * 0.25);
		return mixColor(0xffd8e0e6, 0xfff2f5f7, drift);
	}

	/**
	 * World-space tactical road shoulder & core colors (opaque so clipped segments stay seamless).
	 */
	static int tacticalRoadShoulder(String style, double x, double y) {
		int base = "PARCHMENT".equals(style) ? 0xffa69670 : 0xff89825c;
		double n = fbm(x * 1.2, y * 1.2);
		return shade(base, (int) Math.round((n - 0.5) * 18));
	}

	static int tacticalRoadCore(String style, double x, double y) {
		int base = "PARCHMENT".equals(style) ? 0xff997a54 : 0xff8b6b45;
		double n = fbm(x * 0.9, y * 0.9);
		double grit = valueNoise(x * 5.2, y * 5.2);
		int c = shade(base, (int) Math.round((n - 0.5) * 18 + (grit - 0.5) * 12));
		if (grit > 0.84) {
			c = shade(c, 14);
		}
		return c;
	}

	// --- Architectural walls, doors & stairs ---

	static void drawWallShadow(
		int[] pixels, int width, int height, double[] bounds, double scale, double ax, double ay, double bx, double by,
		double wallWidthFt) {
		double shadowOffX = 0.55, shadowOffY = -0.55;
		double x1 = px(bounds, scale, ax + shadowOffX), y1 = py(bounds, scale, ay + shadowOffY);
		double x2 = px(bounds, scale, bx + shadowOffX), y2 = py(bounds, scale, by + shadowOffY);
		double radiusPx = Math.max(2.0, Math.min(48.0, (wallWidthFt * 0.65 + 0.35) * scale));
		int minX = Math.max(0, (int) Math.floor(Math.min(x1, x2) - radiusPx - 1));
		int maxX = Math.min(width - 1, (int) Math.ceil(Math.max(x1, x2) + radiusPx + 1));
		int minY = Math.max(0, (int) Math.floor(Math.min(y1, y2) - radiusPx - 1));
		int maxY = Math.min(height - 1, (int) Math.ceil(Math.max(y1, y2) + radiusPx + 1));
		for (int y = minY; y <= maxY; y++) {
			for (int x = minX; x <= maxX; x++) {
				double d = distToSegment(x, y, x1, y1, x2, y2);
				if (d < radiusPx) {
					double alpha = (1.0 - d / radiusPx) * 0.24;
					int a = (int) Math.round(alpha * 255);
					if (a > 0) {
						pixels[y * width + x] = blend(pixels[y * width + x], a << 24 | 0x0c0e12);
					}
				}
			}
		}
	}

	static void drawWall(
		int[] pixels, int width, int height, double[] bounds, double scale, String style, boolean contrast, double ax,
		double ay, double bx, double by, double widthFt) {
		double x1 = px(bounds, scale, ax), y1 = py(bounds, scale, ay);
		double x2 = px(bounds, scale, bx), y2 = py(bounds, scale, by);
		double halfThick = Math.max(1.5, Math.min(64.0, widthFt * scale * 0.55));
		int minX = Math.max(0, (int) Math.floor(Math.min(x1, x2) - halfThick - 2));
		int maxX = Math.min(width - 1, (int) Math.ceil(Math.max(x1, x2) + halfThick + 2));
		int minY = Math.max(0, (int) Math.floor(Math.min(y1, y2) - halfThick - 2));
		int maxY = Math.min(height - 1, (int) Math.ceil(Math.max(y1, y2) + halfThick + 2));
		int outer = contrast ? 0xff050505 : "PARCHMENT".equals(style) ? 0xff3b3328 : 0xff222426;
		int core = contrast ? 0xff050505 : "PARCHMENT".equals(style) ? 0xff6b5e4d : 0xff4b4d4a;
		int highlight = contrast ? 0xff050505 : "PARCHMENT".equals(style) ? 0xff82735f : 0xff646762;
		for (int y = minY; y <= maxY; y++) {
			for (int x = minX; x <= maxX; x++) {
				double d = distToSegment(x, y, x1, y1, x2, y2);
				if (d > halfThick + 0.8) {
					continue;
				}
				double coverage = clamp01(halfThick + 0.6 - d);
				int color;
				if (contrast || halfThick < 2.2) {
					color = outer;
				} else if (d > halfThick - Math.max(1.0, halfThick * 0.3)) {
					color = outer;
				} else {
					double wx = bounds[0] + x / scale, wy = bounds[1] + bounds[3] - y / scale;
					int n = (int) Math.round((fbm(wx * 2.5, wy * 2.5) - 0.5) * 14);
					color = shade(d < halfThick * 0.35 ? highlight : core, n);
				}
				int a = (int) Math.round(coverage * 255);
				pixels[y * width + x] = blend(pixels[y * width + x], (a << 24) | (color & 0xffffff));
			}
		}
	}

	static void drawDoor(
		int[] pixels, int width, int height, double[] bounds, double scale, String style, boolean contrast,
		Map<String, Object> door, double ax, double ay, double bx, double by) {
		String state = String.valueOf(door.getOrDefault("state", "CLOSED")).toUpperCase(Locale.ROOT);
		boolean passage = "PASSAGE".equalsIgnoreCase(String.valueOf(door.get("kind")));
		boolean open = "OPEN".equals(state) || "DESTROYED".equals(state) || "BREACHED".equals(state);
		double dx = bx - ax, dy = by - ay, len = Math.hypot(dx, dy);
		if (len < 1e-6) {
			return;
		}
		double ux = dx / len, uy = dy / len;
		double nx = -uy, ny = ux;
		double cx = (ax + bx) * 0.5, cy = (ay + by) * 0.5;
		double rotDeg = Math.toDegrees(Math.atan2(-uy, ux));

		// Threshold sill
		if (!contrast) {
			int sill = passage ? 0x66807c70 : 0x886b5944;
			drawSegmentStroke(pixels, width, height, bounds, scale, ax, ay, bx, by, 0.75, sill);
		}
		// Door jamb posts at both endpoints
		int jambColor = contrast ? 0xff111111 : 0xff3b2919;
		double jambR = Math.max(2.0, Math.min(8.0, 0.28 * scale));
		fillCircleAA(pixels, width, height, px(bounds, scale, ax), py(bounds, scale, ay), jambR, jambColor);
		fillCircleAA(pixels, width, height, px(bounds, scale, bx), py(bounds, scale, by), jambR, jambColor);

		if (open) {
			if (!passage && "OPEN".equals(state)) {
				// Draw swung-open door leaf from endpoint A and a subtle swing arc
				double leafLen = Math.min(len * 0.85, 4.2);
				double lx = ax + nx * leafLen, ly = ay + ny * leafLen;
				if (!contrast) {
					double arcPx = leafLen * scale;
					int steps = Math.max(10, (int) Math.round(arcPx * 0.6));
					double startA = Math.atan2(uy, ux), endA = Math.atan2(ny, nx);
					double diff = endA - startA;
					while (diff > Math.PI)
						diff -= 2 * Math.PI;
					while (diff < -Math.PI)
						diff += 2 * Math.PI;
					for (int i = 0; i <= steps; i += 2) {
						double a = startA + diff * i / steps;
						double pxA = px(bounds, scale, ax + Math.cos(a) * leafLen);
						double pyA = py(bounds, scale, ay + Math.sin(a) * leafLen);
						fillCircleAA(pixels, width, height, pxA, pyA, 0.85, 0x885c4a35);
					}
				}
				drawSegmentStroke(pixels, width, height, bounds, scale, ax, ay, lx, ly, 0.42,
						contrast ? 0xff447744 : 0xff7a5230);
				drawSegmentStroke(pixels, width, height, bounds, scale, ax, ay, lx, ly, 0.18,
						contrast ? 0xffaac28a : 0xff9f6f42);
			} else if (contrast) {
				drawSegmentStroke(pixels, width, height, bounds, scale, ax, ay, bx, by, 0.7, 0xffaac28a);
			}
			return;
		}

		// Closed / Locked / Blocked / Secret door leaf
		int leafDark = contrast ? 0xff222222 : "SECRET".equals(state) ? 0xff5a554c : 0xff4a2f1a;
		int leafMid = contrast ? 0xff895b37 : "SECRET".equals(state) ? 0xff7f796c : 0xff8d5d36;
		int leafHi = contrast ? 0xff895b37 : "SECRET".equals(state) ? 0xff928c7e : 0xffa87346;
		drawSegmentStroke(pixels, width, height, bounds, scale, ax, ay, bx, by, 0.68, leafDark);
		drawSegmentStroke(pixels, width, height, bounds, scale, ax, ay, bx, by, 0.44, leafMid);
		if (!contrast && !"SECRET".equals(state)) {
			// Subtle bevel highlight & iron hinge bands
			drawSegmentStroke(pixels, width, height, bounds, scale, ax + nx * 0.08, ay + ny * 0.08, bx + nx * 0.08,
					by + ny * 0.08, 0.12, leafHi);
			for (double frac : new double[] {0.22, 0.78}) {
				double hx = ax + dx * frac, hy = ay + dy * frac;
				drawSegmentStroke(pixels, width, height, bounds, scale, hx - nx * 0.30, hy - ny * 0.30, hx + nx * 0.30,
						hy + ny * 0.30, 0.16, 0xff2f3238);
			}
		}
		if ("BLOCKED".equals(state)) {
			// Crossed timber barricade
			drawSegmentStroke(pixels, width, height, bounds, scale, cx - ux * 1.4 - nx * 0.7, cy - uy * 1.4 - ny * 0.7,
					cx + ux * 1.4 + nx * 0.7, cy + uy * 1.4 + ny * 0.7, 0.25, 0xff3b2615);
			drawSegmentStroke(pixels, width, height, bounds, scale, cx - ux * 1.4 + nx * 0.7, cy - uy * 1.4 + ny * 0.7,
					cx + ux * 1.4 - nx * 0.7, cy + uy * 1.4 - ny * 0.7, 0.25, 0xff3b2615);
		} else if ("LOCKED".equals(state) && !contrast) {
			// Subtle brass lock plate at center
			fillCircleAA(pixels, width, height, px(bounds, scale, cx), py(bounds, scale, cy),
					Math.max(3.0, 0.35 * scale), 0xffd4a948);
		}
		// Keep rotDeg available if needed later
		if (Double.isNaN(rotDeg)) {
			return;
		}
	}

	static void drawPortal(
		int[] pixels, int width, int height, double[] bounds, double scale, boolean contrast,
		Map<String, Object> portal, double x, double y) {
		String kind = String.valueOf(portal.getOrDefault("kind", "STAIRS")).toUpperCase(Locale.ROOT);
		if (contrast) {
			return;
		}
		if (kind.contains("PORTAL")) {
			double cx = px(bounds, scale, x), cy = py(bounds, scale, y);
			double r = Math.max(6.0, 1.8 * scale);
			fillCircleAA(pixels, width, height, cx, cy, r * 1.15, 0x557b52ff);
			fillCircleAA(pixels, width, height, cx, cy, r, 0xaa473688);
			fillCircleAA(pixels, width, height, cx, cy, r * 0.72, 0xcc8b72ff);
			fillCircleAA(pixels, width, height, cx, cy, r * 0.38, 0xffe4ddff);
			return;
		}
		if (kind.contains("LADDER")) {
			double halfH = 1.6, halfW = 0.65;
			drawSegmentStroke(pixels, width, height, bounds, scale, x - halfW, y - halfH, x - halfW, y + halfH, 0.2,
					0xff52361c);
			drawSegmentStroke(pixels, width, height, bounds, scale, x + halfW, y - halfH, x + halfW, y + halfH, 0.2,
					0xff52361c);
			for (int i = -2; i <= 2; i++) {
				double ry = y + i * 0.6;
				drawSegmentStroke(pixels, width, height, bounds, scale, x - halfW, ry, x + halfW, ry, 0.16, 0xff8a623c);
			}
			return;
		}
		// Stone/timber staircase treads
		double w = 3.8, h = 3.8;
		shadeLocal(pixels, width, height, bounds, scale, x, y, w, h, 0, null, (lx, ly, hw, hh, wx, wy) -> {
			double box = sdBox(lx, ly, hw, hh);
			if (box > 0.08) {
				return 0;
			}
			if (box > -0.14) {
				return 0xff3b3933;
			}
			// Side stringers
			if (Math.abs(lx) > hw - 0.36) {
				return 0xff6e6a60;
			}
			double t = (ly + hh) / (2 * hh); // 0 bottom to 1 top
			int stepCount = 6;
			double stepPos = t * stepCount;
			int stepIndex = Math.min(stepCount - 1, Math.max(0, (int) Math.floor(stepPos)));
			double stepFrac = stepPos - stepIndex;
			int shadeBase = mixColor(0xff727068, 0xffbdbab0, stepIndex / (double) (stepCount - 1));
			if (stepFrac < 0.12) {
				return shade(shadeBase, -34);
			}
			if (stepFrac > 0.85) {
				return shade(shadeBase, 10);
			}
			return shade(shadeBase, (int) Math.round((valueNoise(wx * 4, wy * 4) - 0.5) * 10));
		});
	}

	// --- Parametric SDF Prop Rendering ---

	static void drawPlaced(
		int[] pixels, int width, int height, double[] bounds, double scale, String style, boolean contrast,
		Map<String, AssetService.Texture> textures, Map<String, Object> item, double cx, double cy, double w, double h,
		double rotDeg, List<double[]> clipRects, boolean flatPass) {
		MapPropCatalog.PropSpec spec = MapPropCatalog.resolve(item);
		boolean isFlat = spec != null && spec.flat();
		if (isFlat != flatPass) {
			return;
		}
		String propId = spec == null ? "generic" : spec.id();
		String state = String.valueOf(item.getOrDefault("state", "INTACT")).toUpperCase(Locale.ROOT);
		boolean destroyed = "DESTROYED".equals(state);
		if (contrast) {
			int fill = destroyed ? 0xffaaaaaa : isFlat ? 0xffd5d5d0 : 0xff777777;
			shadeLocal(pixels, width, height, bounds, scale, cx, cy, w, h, rotDeg, clipRects,
					(lx, ly, hw, hh, wx, wy) -> {
						double d = sdBox(lx, ly, hw, hh);
						if (d > 0)
							return 0;
						return d > -Math.max(0.08, 1.5 / scale) ? 0xff101010 : fill;
					});
			return;
		}
		if (destroyed) {
			drawDestroyedDebris(pixels, width, height, bounds, scale, cx, cy, w, h, rotDeg, clipRects);
			return;
		}
		Integer tint = MapPropCatalog.parseColor(item.get("tint"));
		if (tint == null && item.get("material") != null) {
			tint = MapPropCatalog.parseColor(item.get("material"));
		}
		AssetService.Texture customTex = item.get("material") == null ? null
				: textures.get(String.valueOf(item.get("material")));

		// Composite seating around tables (drawn underneath tabletop)
		if ("table_round".equals(propId)) {
			int seats = seatCount(item);
			if (seats > 0) {
				drawRoundTableSeats(pixels, width, height, bounds, scale, cx, cy, w, h, rotDeg, seats, tint, clipRects);
			}
		} else if ("table_rect".equals(propId)) {
			drawRectTableSeats(pixels, width, height, bounds, scale, item, cx, cy, w, h, rotDeg, tint, clipRects);
		}

		// Elevated props cast a soft SE drop shadow first
		if (!isFlat) {
			boolean tall = propId.startsWith("pillar") || propId.equals("tree") || propId.equals("pine_tree")
					|| propId.equals("statue") || propId.equals("tent");
			double offX = tall ? 0.55 : 0.30, offY = tall ? -0.55 : -0.30;
			double shadowAlpha = tall ? 0.34 : 0.25;
			boolean roundShape = SetOfRoundProps.contains(propId);
			drawPropShadow(pixels, width, height, bounds, scale, cx + offX, cy + offY, w, h, rotDeg, roundShape,
					shadowAlpha, clipRects);
		}

		switch (propId) {
		case "table_rect" ->
			drawTableRect(pixels, width, height, bounds, scale, item, cx, cy, w, h, rotDeg, tint, clipRects);
		case "table_round" ->
			drawTableRound(pixels, width, height, bounds, scale, item, cx, cy, w, h, rotDeg, tint, clipRects);
		case "chair" -> drawChair(pixels, width, height, bounds, scale, cx, cy, w, h, rotDeg, tint, clipRects);
		case "stool" -> drawStool(pixels, width, height, bounds, scale, cx, cy, w, h, tint, clipRects);
		case "bench" -> drawBench(pixels, width, height, bounds, scale, cx, cy, w, h, rotDeg, tint, clipRects);
		case "bar_counter" ->
			drawBarCounter(pixels, width, height, bounds, scale, cx, cy, w, h, rotDeg, tint, clipRects);
		case "hearth" -> drawHearth(pixels, width, height, bounds, scale, cx, cy, w, h, rotDeg, state, clipRects);
		case "bed" -> drawBed(pixels, width, height, bounds, scale, cx, cy, w, h, rotDeg, tint, clipRects);
		case "bookshelf" -> drawBookshelf(pixels, width, height, bounds, scale, cx, cy, w, h, rotDeg, tint, clipRects);
		case "rug", "carpet_runner" -> drawRug(pixels, width, height, bounds, scale, cx, cy, w, h, rotDeg, tint,
				"carpet_runner".equals(propId), clipRects);
		case "crate" -> drawCrate(pixels, width, height, bounds, scale, cx, cy, w, h, rotDeg, tint, clipRects);
		case "barrel" -> drawBarrel(pixels, width, height, bounds, scale, cx, cy, w, h, tint, clipRects);
		case "chest" -> drawChest(pixels, width, height, bounds, scale, cx, cy, w, h, rotDeg, state, tint, clipRects);
		case "pillar_round" -> drawPillarRound(pixels, width, height, bounds, scale, cx, cy, w, h, tint, clipRects);
		case "pillar_square" ->
			drawPillarSquare(pixels, width, height, bounds, scale, cx, cy, w, h, rotDeg, tint, clipRects);
		case "altar" -> drawAltar(pixels, width, height, bounds, scale, cx, cy, w, h, rotDeg, tint, clipRects);
		case "brazier" -> drawBrazier(pixels, width, height, bounds, scale, cx, cy, w, h, state, clipRects);
		case "torch_sconce" -> drawTorch(pixels, width, height, bounds, scale, cx, cy, w, h, state, clipRects);
		case "lantern" -> drawLantern(pixels, width, height, bounds, scale, cx, cy, w, h, state, clipRects);
		case "statue" -> drawStatue(pixels, width, height, bounds, scale, cx, cy, w, h, rotDeg, tint, clipRects);
		case "fountain" -> drawFountain(pixels, width, height, bounds, scale, cx, cy, w, h, clipRects);
		case "sarcophagus" ->
			drawSarcophagus(pixels, width, height, bounds, scale, cx, cy, w, h, rotDeg, tint, clipRects);
		case "rubble" -> drawRubble(pixels, width, height, bounds, scale, cx, cy, w, h, clipRects);
		case "pit" -> drawPit(pixels, width, height, bounds, scale, cx, cy, w, h, clipRects);
		case "weapon_rack" -> drawWeaponRack(pixels, width, height, bounds, scale, cx, cy, w, h, rotDeg, clipRects);
		case "anvil" -> drawAnvil(pixels, width, height, bounds, scale, cx, cy, w, h, rotDeg, clipRects);
		case "cauldron" -> drawCauldron(pixels, width, height, bounds, scale, cx, cy, w, h, tint, clipRects);
		case "tree" -> drawTree(pixels, width, height, bounds, scale, cx, cy, w, h, tint, clipRects);
		case "pine_tree" -> drawPineTree(pixels, width, height, bounds, scale, cx, cy, w, h, tint, clipRects);
		case "bush" -> drawBush(pixels, width, height, bounds, scale, cx, cy, w, h, tint, clipRects);
		case "boulder" -> drawBoulder(pixels, width, height, bounds, scale, cx, cy, w, h, tint, clipRects);
		case "stump" -> drawStump(pixels, width, height, bounds, scale, cx, cy, w, h, clipRects);
		case "campfire" -> drawCampfire(pixels, width, height, bounds, scale, cx, cy, w, h, state, clipRects);
		case "tent" -> drawTent(pixels, width, height, bounds, scale, cx, cy, w, h, rotDeg, tint, clipRects);
		case "wagon" -> drawWagon(pixels, width, height, bounds, scale, cx, cy, w, h, rotDeg, tint, clipRects);
		case "puddle" -> drawPuddle(pixels, width, height, bounds, scale, cx, cy, w, h, clipRects);
		case "cracks" -> drawCracks(pixels, width, height, bounds, scale, cx, cy, w, h, clipRects);
		case "scorch_mark" -> drawScorch(pixels, width, height, bounds, scale, cx, cy, w, h, clipRects);
		case "bloodstain" -> drawBloodstain(pixels, width, height, bounds, scale, cx, cy, w, h, clipRects);
		case "banner" -> drawBanner(pixels, width, height, bounds, scale, cx, cy, w, h, rotDeg, tint, clipRects);
		case "trap_plate" -> drawTrapPlate(pixels, width, height, bounds, scale, cx, cy, w, h, rotDeg, clipRects);
		default ->
			drawGenericObject(pixels, width, height, bounds, scale, cx, cy, w, h, rotDeg, tint, customTex, clipRects);
		}
	}

	private static final java.util.Set<String> SetOfRoundProps = java.util.Set.of("table_round", "stool", "barrel",
			"pillar_round", "brazier", "torch_sconce", "lantern", "fountain", "cauldron", "tree", "pine_tree", "bush",
			"boulder", "stump", "campfire");

	private static int seatCount(Map<String, Object> item) {
		if (item.get("seats") instanceof Number n) {
			return Math.max(0, Math.min(12, n.intValue()));
		}
		return 0;
	}

	private static void drawRoundTableSeats(
		int[] pixels, int width, int height, double[] bounds, double scale, double cx, double cy, double w, double h,
		double rotDeg, int seats, Integer tint, List<double[]> clipRects) {
		double r = Math.min(w, h) * 0.5;
		double orbit = r + 0.58;
		double baseRad = Math.toRadians(rotDeg);
		for (int i = 0; i < seats; i++) {
			double angle = baseRad + 2 * Math.PI * i / seats;
			double sx = cx + Math.cos(angle) * orbit;
			double sy = cy + Math.sin(angle) * orbit;
			// Backrest faces outward (away from table center)
			double chairDeg = Math.toDegrees(Math.atan2(Math.cos(angle), Math.sin(angle)));
			drawPropShadow(pixels, width, height, bounds, scale, sx + 0.22, sy - 0.22, 1.85, 1.85, chairDeg, false,
					0.22, clipRects);
			drawChair(pixels, width, height, bounds, scale, sx, sy, 1.85, 1.85, chairDeg, tint, clipRects);
		}
	}

	private static void drawRectTableSeats(
		int[] pixels, int width, int height, double[] bounds, double scale, Map<String, Object> item, double cx,
		double cy, double w, double h, double rotDeg, Integer tint, List<double[]> clipRects) {
		String seating = item.get("seating") == null ? "" : item.get("seating").toString().toLowerCase(Locale.ROOT);
		int seats = seatCount(item);
		if (seating.isEmpty() && seats <= 0) {
			return;
		}
		double rad = Math.toRadians(rotDeg);
		double cos = Math.cos(rad), sin = Math.sin(rad);
		boolean horiz = w >= h;
		double longLen = horiz ? w : h, shortLen = horiz ? h : w;
		if (seating.contains("bench")) {
			double benchLen = Math.max(2.0, longLen - 0.8);
			double off = shortLen * 0.5 + 0.85;
			for (double side : new double[] {-1, 1}) {
				double lx = horiz ? 0 : side * off, ly = horiz ? side * off : 0;
				double wx = cx + lx * cos + ly * sin, wy = cy - lx * sin + ly * cos;
				double bw = horiz ? benchLen : 1.25, bh = horiz ? 1.25 : benchLen;
				drawPropShadow(pixels, width, height, bounds, scale, wx + 0.22, wy - 0.22, bw, bh, rotDeg, false, 0.22,
						clipRects);
				drawBench(pixels, width, height, bounds, scale, wx, wy, bw, bh, rotDeg, tint, clipRects);
			}
			return;
		}
		int perSide = seats > 0 ? Math.max(1, (seats + 1) / 2) : Math.max(1, (int) Math.floor(longLen / 2.6));
		double off = shortLen * 0.5 + 0.65;
		int placed = 0;
		for (int s = 0; s < 2; s++) {
			double side = s == 0 ? 1 : -1;
			int count = seats > 0 ? Math.min(perSide, seats - placed) : perSide;
			for (int i = 0; i < count; i++) {
				double span = count == 1 ? 0 : ((i + 0.5) / count - 0.5) * Math.max(1.0, longLen - 0.9);
				double lx = horiz ? span : side * off;
				double ly = horiz ? side * off : span;
				double wx = cx + lx * cos + ly * sin, wy = cy - lx * sin + ly * cos;
				double chairDeg = rotDeg + (horiz ? (side > 0 ? 0 : 180) : (side > 0 ? 90 : 270));
				drawPropShadow(pixels, width, height, bounds, scale, wx + 0.20, wy - 0.20, 1.85, 1.85, chairDeg, false,
						0.22, clipRects);
				drawChair(pixels, width, height, bounds, scale, wx, wy, 1.85, 1.85, chairDeg, tint, clipRects);
				placed++;
			}
		}
	}

	private static void drawTableRect(
		int[] pixels, int width, int height, double[] bounds, double scale, Map<String, Object> item, double cx,
		double cy, double w, double h, double rotDeg, Integer tint, List<double[]> clipRects) {
		int wood = tint != null ? tint : 0xff8d6037;
		int rim = shade(wood, -44);
		boolean clutter = Boolean.TRUE.equals(item.get("clutter"));
		double corner = Math.min(0.28, Math.min(w, h) * 0.12);
		double aa = Math.max(0.03, 1.0 / scale);
		boolean horiz = w >= h;
		shadeLocal(pixels, width, height, bounds, scale, cx, cy, w, h, rotDeg, clipRects, (lx, ly, hw, hh, wx, wy) -> {
			double d = sdRoundedBox(lx, ly, hw, hh, corner);
			if (d > aa)
				return 0;
			int alpha = (int) Math.round(clamp01((aa - d) / (2 * aa)) * 255);
			double rimW = Math.min(0.20, Math.min(hw, hh) * 0.14);
			int c;
			if (d > -rimW * 0.45) {
				c = rim;
			} else {
				double across = horiz ? ly : lx, along = horiz ? lx : ly;
				int plank = (int) Math.floor((across + (horiz ? hh : hw)) / 0.72);
				double frac = ((across + (horiz ? hh : hw)) / 0.72) - plank;
				double grain = (fbm(along * 1.4 + plank * 3.7, across * 9.0) - 0.5) * 14.0;
				int tone = (int) Math.round((hash01(plank, 11) - 0.5) * 12);
				c = shade(wood, tone + (int) Math.round(grain));
				if (frac < 0.07 || frac > 0.93) {
					c = shade(c, -22);
				}
				// Subtle NW sunlit bevel & SE edge shadow
				if (d > -rimW * 1.35) {
					c = shade(c, (lx - ly) < 0 ? 14 : -14);
				}
				if (clutter) {
					c = tableClutter(c, lx, ly, hw, hh);
				}
			}
			return (alpha << 24) | (c & 0xffffff);
		});
	}

	private static void drawTableRound(
		int[] pixels, int width, int height, double[] bounds, double scale, Map<String, Object> item, double cx,
		double cy, double w, double h, double rotDeg, Integer tint, List<double[]> clipRects) {
		int wood = tint != null ? tint : 0xff93653b;
		int rim = shade(wood, -44);
		boolean clutter = Boolean.TRUE.equals(item.get("clutter"));
		double aa = Math.max(0.03, 1.0 / scale);
		shadeLocal(pixels, width, height, bounds, scale, cx, cy, w, h, rotDeg, clipRects, (lx, ly, hw, hh, wx, wy) -> {
			double r = Math.min(hw, hh);
			double dist = Math.hypot(lx, ly);
			double d = dist - r;
			if (d > aa)
				return 0;
			int alpha = (int) Math.round(clamp01((aa - d) / (2 * aa)) * 255);
			int c;
			if (d > -Math.min(0.14, r * 0.08)) {
				c = rim;
			} else {
				int plank = (int) Math.floor((ly + r) / 0.68);
				double frac = (ly + r) / 0.68 - plank;
				double grain = (fbm(lx * 1.5 + plank * 2.3, ly * 8.5) - 0.5) * 14.0;
				c = shade(wood, (int) Math.round((hash01(plank, 23) - 0.5) * 12 + grain));
				if (frac < 0.07 || frac > 0.93) {
					c = shade(c, -20);
				}
				if (d > -Math.min(0.32, r * 0.18)) {
					c = shade(c, (lx - ly) < 0 ? 15 : -14);
				}
				if (clutter) {
					c = tableClutter(c, lx, ly, hw * 0.72, hh * 0.72);
				}
			}
			return (alpha << 24) | (c & 0xffffff);
		});
	}

	private static int tableClutter(int c, double lx, double ly, double hw, double hh) {
		// Small ceramic plate / tankard / parchment accents
		double dPlate = Math.hypot(lx - hw * 0.35, ly - hh * 0.25);
		if (dPlate < 0.34) {
			return dPlate > 0.24 ? 0xffb7aa92 : 0xffded5c3;
		}
		double dMug = Math.hypot(lx + hw * 0.38, ly + hh * 0.18);
		if (dMug < 0.21) {
			return dMug > 0.13 ? 0xff5a5e66 : 0xffc68d3a;
		}
		if (Math.abs(lx + hw * 0.08) < 0.38 && Math.abs(ly - hh * 0.10) < 0.28) {
			return 0xffe8dcc0;
		}
		return c;
	}

	private static void drawChair(
		int[] pixels, int width, int height, double[] bounds, double scale, double cx, double cy, double w, double h,
		double rotDeg, Integer tint, List<double[]> clipRects) {
		int wood = tint != null ? tint : 0xff7f5430;
		int dark = shade(wood, -38);
		int seatTone = shade(wood, 12);
		double aa = Math.max(0.03, 1.0 / scale);
		shadeLocal(pixels, width, height, bounds, scale, cx, cy, w, h, rotDeg, clipRects, (lx, ly, hw, hh, wx, wy) -> {
			double dSeat = sdRoundedBox(lx, ly - hh * 0.08, hw * 0.78, hh * 0.74, 0.16);
			double dBack = sdRoundedBox(lx, ly - hh * 0.68, hw * 0.88, hh * 0.24, 0.12);
			double d = Math.min(dSeat, dBack);
			if (d > aa)
				return 0;
			int alpha = (int) Math.round(clamp01((aa - d) / (2 * aa)) * 255);
			int c;
			if (dBack <= 0) {
				c = dBack > -0.09 ? shade(dark, -10) : shade(wood, -20 + (int) Math.round((lx / hw) * -8));
			} else {
				c = dSeat > -0.09 ? dark : shade(seatTone, (int) Math.round((fbm(lx * 4, ly * 4) - 0.5) * 14 - ly * 8));
			}
			return (alpha << 24) | (c & 0xffffff);
		});
	}

	private static void drawStool(
		int[] pixels, int width, int height, double[] bounds, double scale, double cx, double cy, double w, double h,
		Integer tint, List<double[]> clipRects) {
		int wood = tint != null ? tint : 0xff8d6039;
		double aa = Math.max(0.03, 1.0 / scale);
		shadeLocal(pixels, width, height, bounds, scale, cx, cy, w, h, 0, clipRects, (lx, ly, hw, hh, wx, wy) -> {
			double r = Math.min(hw, hh) * 0.88;
			double d = Math.hypot(lx, ly) - r;
			if (d > aa)
				return 0;
			int alpha = (int) Math.round(clamp01((aa - d) / (2 * aa)) * 255);
			int c = d > -0.10 ? shade(wood, -38)
					: shade(wood, (int) Math.round((1 - Math.hypot(lx, ly) / r) * 16 + (lx - ly) * -6));
			return (alpha << 24) | (c & 0xffffff);
		});
	}

	private static void drawBench(
		int[] pixels, int width, int height, double[] bounds, double scale, double cx, double cy, double w, double h,
		double rotDeg, Integer tint, List<double[]> clipRects) {
		int wood = tint != null ? tint : 0xff835733;
		double aa = Math.max(0.03, 1.0 / scale);
		boolean horiz = w >= h;
		shadeLocal(pixels, width, height, bounds, scale, cx, cy, w, h, rotDeg, clipRects, (lx, ly, hw, hh, wx, wy) -> {
			double d = sdRoundedBox(lx, ly, hw * 0.96, hh * 0.90, 0.12);
			if (d > aa)
				return 0;
			int alpha = (int) Math.round(clamp01((aa - d) / (2 * aa)) * 255);
			if (d > -0.09)
				return (alpha << 24) | (shade(wood, -40) & 0xffffff);
			double across = horiz ? ly : lx;
			int c = shade(wood, (int) Math.round((fbm(lx * 3, ly * 3) - 0.5) * 12));
			if (Math.abs(across) < 0.06)
				c = shade(c, -18);
			return (alpha << 24) | (c & 0xffffff);
		});
	}

	private static void drawBarCounter(
		int[] pixels, int width, int height, double[] bounds, double scale, double cx, double cy, double w, double h,
		double rotDeg, Integer tint, List<double[]> clipRects) {
		int wood = tint != null ? tint : 0xff6b4426;
		int rim = shade(wood, -36);
		double aa = Math.max(0.03, 1.0 / scale);
		boolean horiz = w >= h;
		shadeLocal(pixels, width, height, bounds, scale, cx, cy, w, h, rotDeg, clipRects, (lx, ly, hw, hh, wx, wy) -> {
			double d = sdRoundedBox(lx, ly, hw, hh, 0.22);
			if (d > aa)
				return 0;
			int alpha = (int) Math.round(clamp01((aa - d) / (2 * aa)) * 255);
			int c;
			if (d > -0.14) {
				c = rim;
			} else {
				double across = horiz ? ly : lx, along = horiz ? lx : ly;
				double halfAcross = horiz ? hh : hw;
				double grain = (fbm(along * 1.2, across * 7.5) - 0.5) * 14.0;
				c = shade(wood, (int) Math.round(grain));
				// Subtle brass trim strip along the outer customer edge
				if (Math.abs(across + halfAcross * 0.72) < 0.08) {
					c = 0xffc69a44;
				} else if (d > -0.34) {
					c = shade(c, (lx - ly) < 0 ? 14 : -12);
				}
				// Periodic subtle round mugs/bottles along bartender shelf edge
				double spacing = 3.2;
				double slot = Math.IEEEremainder(along, spacing);
				double dVessel = Math.hypot(slot, across - halfAcross * 0.42);
				if (dVessel < 0.26) {
					boolean bottle = Math.floorMod((int) Math.floor((along + 50) / spacing), 2) == 0;
					c = dVessel > 0.18 ? 0xff362d24 : bottle ? 0xff466e4a : 0xffd2a44a;
				}
			}
			return (alpha << 24) | (c & 0xffffff);
		});
	}

	private static void drawHearth(
		int[] pixels, int width, int height, double[] bounds, double scale, double cx, double cy, double w, double h,
		double rotDeg, String state, List<double[]> clipRects) {
		boolean lit = !"EXTINGUISHED".equals(state) && !"OFF".equals(state);
		double aa = Math.max(0.03, 1.0 / scale);
		shadeLocal(pixels, width, height, bounds, scale, cx, cy, w, h, rotDeg, clipRects, (lx, ly, hw, hh, wx, wy) -> {
			double d = sdRoundedBox(lx, ly, hw, hh, 0.18);
			if (d > aa)
				return 0;
			int alpha = (int) Math.round(clamp01((aa - d) / (2 * aa)) * 255);
			// Masonry surround around back and sides, firebox recessed in front-center
			boolean firebox = Math.abs(lx) < hw * 0.62 && ly < hh * 0.42 && ly > -hh * 0.78;
			int c;
			if (d > -0.14) {
				c = 0xff3b3b37;
			} else if (!firebox) {
				c = flagstones(wx * 1.4, wy * 1.4, 0xff7b7972, false);
			} else {
				double fireDist = Math.hypot(lx / Math.max(0.5, hw * 0.48),
						(ly + hh * 0.18) / Math.max(0.4, hh * 0.45));
				boolean log = Math.abs(lx) < hw * 0.44 && Math.abs((ly + hh * 0.18) - lx * 0.18) < 0.18
						&& fireDist > 0.34;
				if (!lit) {
					c = log ? 0xff3b2b1e : fireDist < 0.75 ? 0xff2b2724 : 0xff46413c;
				} else if (fireDist < 0.32) {
					c = 0xffffef84;
				} else if (log) {
					c = 0xff4a2d1b;
				} else if (fireDist < 0.68) {
					c = mixColor(0xffff8a28, 0xffd9411e, (fireDist - 0.32) / 0.36);
				} else if (fireDist < 0.95) {
					c = mixColor(0xff7a2a16, 0xff2c2420, (fireDist - 0.68) / 0.27);
				} else {
					c = 0xff2c2420;
				}
			}
			return (alpha << 24) | (c & 0xffffff);
		});
	}

	private static void drawBed(
		int[] pixels, int width, int height, double[] bounds, double scale, double cx, double cy, double w, double h,
		double rotDeg, Integer tint, List<double[]> clipRects) {
		int blanket = tint != null ? tint : 0xff8c3f3b;
		int frame = 0xff5e3e24;
		double aa = Math.max(0.03, 1.0 / scale);
		shadeLocal(pixels, width, height, bounds, scale, cx, cy, w, h, rotDeg, clipRects, (lx, ly, hw, hh, wx, wy) -> {
			double d = sdRoundedBox(lx, ly, hw, hh, 0.16);
			if (d > aa)
				return 0;
			int alpha = (int) Math.round(clamp01((aa - d) / (2 * aa)) * 255);
			int c;
			if (d > -0.18 || ly > hh - 0.35 || ly < -hh + 0.28) {
				c = d > -0.07 ? 0xff3b2514 : frame;
			} else if (ly > hh * 0.44) {
				// Pillow(s) at the head of the bed
				double dPillow = sdRoundedBox(lx, ly - hh * 0.66, hw * 0.68, hh * 0.18, 0.18);
				c = dPillow < 0 ? shade(0xffeae2d0, (int) Math.round(-dPillow * 35)) : 0xffd2cab8;
			} else if (ly > hh * 0.26) {
				// Turned-down pale sheet
				c = shade(0xffe4dcc9, (int) Math.round((ly - hh * 0.35) * 20));
			} else {
				// Folded quilt/blanket
				double fold = Math.sin(ly * 3.8 + lx * 0.5) * 8;
				c = shade(blanket, (int) Math.round(fold + (fbm(lx * 3, ly * 3) - 0.5) * 12));
			}
			return (alpha << 24) | (c & 0xffffff);
		});
	}

	private static void drawBookshelf(
		int[] pixels, int width, int height, double[] bounds, double scale, double cx, double cy, double w, double h,
		double rotDeg, Integer tint, List<double[]> clipRects) {
		int wood = tint != null ? tint : 0xff5f3e24;
		int[] bookPalette = {0xff8e3632, 0xff365475, 0xff3f6b47, 0xff9f7a38, 0xff694270, 0xffb39e74};
		double aa = Math.max(0.03, 1.0 / scale);
		boolean horiz = w >= h;
		shadeLocal(pixels, width, height, bounds, scale, cx, cy, w, h, rotDeg, clipRects, (lx, ly, hw, hh, wx, wy) -> {
			double d = sdBox(lx, ly, hw, hh);
			if (d > aa)
				return 0;
			int alpha = (int) Math.round(clamp01((aa - d) / (2 * aa)) * 255);
			double margin = Math.min(0.22, Math.min(hw, hh) * 0.25);
			if (d > -margin) {
				return (alpha << 24) | (shade(wood, d > -0.07 ? -26 : 0) & 0xffffff);
			}
			double along = horiz ? lx : ly;
			int bookIdx = (int) Math.floor((along + 50) / 0.28);
			double bookFrac = ((along + 50) / 0.28) - bookIdx;
			int c = bookPalette[Math.floorMod(hash2(bookIdx, 13), bookPalette.length)];
			if (bookFrac < 0.12)
				c = shade(c, -28);
			return (alpha << 24) | (c & 0xffffff);
		});
	}

	private static void drawRug(
		int[] pixels, int width, int height, double[] bounds, double scale, double cx, double cy, double w, double h,
		double rotDeg, Integer tint, boolean runner, List<double[]> clipRects) {
		int field = tint != null ? tint : 0xff8a2f34;
		int border = 0xffc9a252;
		double aa = Math.max(0.03, 1.0 / scale);
		boolean horiz = w >= h;
		shadeLocal(pixels, width, height, bounds, scale, cx, cy, w, h, rotDeg, clipRects, (lx, ly, hw, hh, wx, wy) -> {
			double d = sdBox(lx, ly, hw, hh);
			if (d > aa)
				return 0;
			double along = Math.abs(horiz ? lx : ly), halfAlong = horiz ? hw : hh;
			double across = Math.abs(horiz ? ly : lx), halfAcross = horiz ? hh : hw;
			// Fringe along short ends
			if (along > halfAlong - 0.22) {
				boolean tassel = Math.floorMod((int) Math.floor((across + 10) * 7), 2) == 0;
				return tassel ? 0xd8dfd1b0 : 0;
			}
			double edge = Math.min(halfAlong - 0.22 - along, halfAcross - across);
			int c;
			if (edge < 0.14) {
				c = shade(field, -30);
			} else if (edge < 0.34) {
				c = border;
			} else if (edge < 0.46) {
				c = shade(field, -18);
			} else if (!runner
					&& (Math.abs(lx) / Math.max(0.5, hw * 0.45) + Math.abs(ly) / Math.max(0.5, hh * 0.45)) < 1.0) {
				double md = Math.abs(lx) / Math.max(0.5, hw * 0.45) + Math.abs(ly) / Math.max(0.5, hh * 0.45);
				c = md > 0.82 ? border : shade(field, 18);
			} else {
				c = shade(field, ((int) Math.floor(lx * 10) + (int) Math.floor(ly * 10)) % 2 == 0 ? 5 : -5);
			}
			return 0xeb000000 | (c & 0xffffff);
		});
	}

	private static void drawCrate(
		int[] pixels, int width, int height, double[] bounds, double scale, double cx, double cy, double w, double h,
		double rotDeg, Integer tint, List<double[]> clipRects) {
		int wood = tint != null ? tint : 0xffa07846;
		int rim = shade(wood, -32);
		double aa = Math.max(0.03, 1.0 / scale);
		shadeLocal(pixels, width, height, bounds, scale, cx, cy, w, h, rotDeg, clipRects, (lx, ly, hw, hh, wx, wy) -> {
			double d = sdBox(lx, ly, hw, hh);
			if (d > aa)
				return 0;
			int alpha = (int) Math.round(clamp01((aa - d) / (2 * aa)) * 255);
			int c;
			if (d > -0.10) {
				c = 0xff3e2b18;
			} else if (d > -Math.min(0.32, Math.min(hw, hh) * 0.24)) {
				c = rim;
			} else {
				double diag = Math.abs(lx / hw - ly / hh);
				if (diag < 0.20) {
					c = shade(wood, -14);
				} else {
					int slat = (int) Math.floor((ly + hh) / 0.55);
					double f = (ly + hh) / 0.55 - slat;
					c = shade(wood, f < 0.10 ? -24 : (int) Math.round((hash01(slat, 3) - 0.5) * 14));
				}
			}
			return (alpha << 24) | (c & 0xffffff);
		});
	}

	private static void drawBarrel(
		int[] pixels, int width, int height, double[] bounds, double scale, double cx, double cy, double w, double h,
		Integer tint, List<double[]> clipRects) {
		int wood = tint != null ? tint : 0xff8a5d35;
		double aa = Math.max(0.03, 1.0 / scale);
		shadeLocal(pixels, width, height, bounds, scale, cx, cy, w, h, 0, clipRects, (lx, ly, hw, hh, wx, wy) -> {
			double r = Math.min(hw, hh) * 0.92;
			double dist = Math.hypot(lx, ly);
			double d = dist - r;
			if (d > aa)
				return 0;
			int alpha = (int) Math.round(clamp01((aa - d) / (2 * aa)) * 255);
			int c;
			if (d > -0.15) {
				c = 0xff3f4349; // iron hoop
			} else if (d > -0.26) {
				c = shade(wood, -28);
			} else {
				int stave = (int) Math.floor((lx + r) / 0.42);
				double f = (lx + r) / 0.42 - stave;
				c = shade(wood, (f < 0.10 ? -22 : 0) + (int) Math.round((lx - ly) * -6));
				if (Math.hypot(lx - r * 0.3, ly + r * 0.2) < 0.14) {
					c = 0xff3b2615; // bung
				}
			}
			return (alpha << 24) | (c & 0xffffff);
		});
	}

	private static void drawChest(
		int[] pixels, int width, int height, double[] bounds, double scale, double cx, double cy, double w, double h,
		double rotDeg, String state, Integer tint, List<double[]> clipRects) {
		int wood = tint != null ? tint : 0xff7b4d28;
		boolean open = "OPEN".equals(state);
		boolean locked = "LOCKED".equals(state);
		double aa = Math.max(0.03, 1.0 / scale);
		shadeLocal(pixels, width, height, bounds, scale, cx, cy, w, h, rotDeg, clipRects, (lx, ly, hw, hh, wx, wy) -> {
			double d = sdRoundedBox(lx, ly, hw, hh, 0.14);
			if (d > aa)
				return 0;
			int alpha = (int) Math.round(clamp01((aa - d) / (2 * aa)) * 255);
			int c;
			if (d > -0.11) {
				c = 0xff322419;
			} else if (open && ly < hh * 0.45 && Math.abs(lx) < hw * 0.80) {
				c = 0xff281f18; // dark open interior
			} else {
				c = shade(wood, (int) Math.round(-Math.abs(ly / hh) * 18 + 10));
				// Iron straps
				if (Math.abs(Math.abs(lx) - hw * 0.52) < Math.max(0.14, hw * 0.12)) {
					c = 0xff4c5158;
				}
				// Center brass lockplate
				if (Math.abs(lx) < Math.max(0.22, hw * 0.16) && Math.abs(ly + hh * 0.55) < Math.max(0.20, hh * 0.28)) {
					c = locked ? 0xffe4b644 : 0xffb89242;
				}
			}
			return (alpha << 24) | (c & 0xffffff);
		});
	}

	private static void drawPillarRound(
		int[] pixels, int width, int height, double[] bounds, double scale, double cx, double cy, double w, double h,
		Integer tint, List<double[]> clipRects) {
		int stone = tint != null ? tint : 0xff9d9c92;
		double aa = Math.max(0.03, 1.0 / scale);
		shadeLocal(pixels, width, height, bounds, scale, cx, cy, w, h, 0, clipRects, (lx, ly, hw, hh, wx, wy) -> {
			double r = Math.min(hw, hh);
			double dist = Math.hypot(lx, ly);
			double d = dist - r;
			if (d > aa)
				return 0;
			int alpha = (int) Math.round(clamp01((aa - d) / (2 * aa)) * 255);
			int c;
			if (d > -0.12) {
				c = 0xff3b3b36;
			} else {
				double light = (-lx + ly) / Math.max(0.4, r) * 20;
				double ring = dist > r * 0.78 ? -16 : dist > r * 0.66 ? 12 : 0;
				c = shade(stone, (int) Math.round(light + ring + (fbm(wx * 4, wy * 4) - 0.5) * 10));
			}
			return (alpha << 24) | (c & 0xffffff);
		});
	}

	private static void drawPillarSquare(
		int[] pixels, int width, int height, double[] bounds, double scale, double cx, double cy, double w, double h,
		double rotDeg, Integer tint, List<double[]> clipRects) {
		int stone = tint != null ? tint : 0xff9a988e;
		double aa = Math.max(0.03, 1.0 / scale);
		shadeLocal(pixels, width, height, bounds, scale, cx, cy, w, h, rotDeg, clipRects, (lx, ly, hw, hh, wx, wy) -> {
			double d = sdRoundedBox(lx, ly, hw, hh, 0.14);
			if (d > aa)
				return 0;
			int alpha = (int) Math.round(clamp01((aa - d) / (2 * aa)) * 255);
			int c;
			if (d > -0.12) {
				c = 0xff393934;
			} else if (d > -Math.min(0.42, Math.min(hw, hh) * 0.30)) {
				c = shade(stone, (lx - ly) < 0 ? 18 : -20);
			} else {
				c = shade(stone, 8 + (int) Math.round((fbm(wx * 4, wy * 4) - 0.5) * 10));
			}
			return (alpha << 24) | (c & 0xffffff);
		});
	}

	private static void drawAltar(
		int[] pixels, int width, int height, double[] bounds, double scale, double cx, double cy, double w, double h,
		double rotDeg, Integer tint, List<double[]> clipRects) {
		int cloth = tint != null ? tint : 0xff8a2b34;
		double aa = Math.max(0.03, 1.0 / scale);
		shadeLocal(pixels, width, height, bounds, scale, cx, cy, w, h, rotDeg, clipRects, (lx, ly, hw, hh, wx, wy) -> {
			double d = sdBox(lx, ly, hw, hh);
			if (d > aa)
				return 0;
			int alpha = (int) Math.round(clamp01((aa - d) / (2 * aa)) * 255);
			int c;
			if (d > -0.12) {
				c = 0xff3b3a35;
			} else if (d > -Math.min(0.38, Math.min(hw, hh) * 0.25)) {
				c = shade(0xffb4b0a2, (lx - ly) < 0 ? 14 : -14);
			} else if (Math.abs(lx) < hw * 0.26) {
				c = Math.abs(lx) > hw * 0.20 ? 0xffcfa64a : cloth;
			} else {
				c = shade(0xffcfc9b8, (int) Math.round((fbm(wx * 3, wy * 3) - 0.5) * 10));
				// Subtle brass candles near ends
				if (Math.hypot(Math.abs(lx) - hw * 0.65, ly) < 0.22) {
					c = 0xfff5d46e;
				}
			}
			return (alpha << 24) | (c & 0xffffff);
		});
	}

	private static void drawBrazier(
		int[] pixels, int width, int height, double[] bounds, double scale, double cx, double cy, double w, double h,
		String state, List<double[]> clipRects) {
		boolean lit = !"EXTINGUISHED".equals(state) && !"OFF".equals(state);
		double aa = Math.max(0.03, 1.0 / scale);
		shadeLocal(pixels, width, height, bounds, scale, cx, cy, w, h, 0, clipRects, (lx, ly, hw, hh, wx, wy) -> {
			double r = Math.min(hw, hh) * 0.90;
			double dist = Math.hypot(lx, ly);
			double d = dist - r;
			if (d > aa)
				return 0;
			int alpha = (int) Math.round(clamp01((aa - d) / (2 * aa)) * 255);
			int c;
			if (dist > r * 0.72) {
				c = dist > r * 0.88 ? 0xff282b30 : 0xff4e535c;
			} else if (!lit) {
				c = 0xff322e2b;
			} else if (dist < r * 0.30) {
				c = 0xffffef88;
			} else if (dist < r * 0.56) {
				c = 0xffff8d28;
			} else {
				c = 0xffb3371a;
			}
			return (alpha << 24) | (c & 0xffffff);
		});
	}

	private static void drawTorch(
		int[] pixels, int width, int height, double[] bounds, double scale, double cx, double cy, double w, double h,
		String state, List<double[]> clipRects) {
		boolean lit = !"EXTINGUISHED".equals(state) && !"OFF".equals(state);
		double aa = Math.max(0.03, 1.0 / scale);
		shadeLocal(pixels, width, height, bounds, scale, cx, cy, Math.max(w, 1.1), Math.max(h, 1.1), 0, clipRects,
				(lx, ly, hw, hh, wx, wy) -> {
					double r = Math.min(hw, hh) * 0.85;
					double dist = Math.hypot(lx, ly);
					double d = dist - r;
					if (d > aa)
						return 0;
					int alpha = (int) Math.round(clamp01((aa - d) / (2 * aa)) * 255);
					int c = dist > r * 0.70 ? 0xff3a3530
							: !lit ? 0xff5a4632 : dist < r * 0.35 ? 0xfffff4a8 : 0xffff9834;
					return (alpha << 24) | (c & 0xffffff);
				});
	}

	private static void drawLantern(
		int[] pixels, int width, int height, double[] bounds, double scale, double cx, double cy, double w, double h,
		String state, List<double[]> clipRects) {
		boolean lit = !"EXTINGUISHED".equals(state) && !"OFF".equals(state);
		double aa = Math.max(0.03, 1.0 / scale);
		shadeLocal(pixels, width, height, bounds, scale, cx, cy, Math.max(w, 1.1), Math.max(h, 1.1), 0, clipRects,
				(lx, ly, hw, hh, wx, wy) -> {
					double r = Math.min(hw, hh) * 0.85;
					double dist = Math.hypot(lx, ly);
					double d = dist - r;
					if (d > aa)
						return 0;
					int alpha = (int) Math.round(clamp01((aa - d) / (2 * aa)) * 255);
					int c = dist > r * 0.68 ? 0xffa68236
							: !lit ? 0xff62676c : dist < r * 0.42 ? 0xfffff7be : 0xffffcf5c;
					return (alpha << 24) | (c & 0xffffff);
				});
	}

	private static void drawStatue(
		int[] pixels, int width, int height, double[] bounds, double scale, double cx, double cy, double w, double h,
		double rotDeg, Integer tint, List<double[]> clipRects) {
		int stone = tint != null ? tint : 0xffa7a59a;
		double aa = Math.max(0.03, 1.0 / scale);
		shadeLocal(pixels, width, height, bounds, scale, cx, cy, w, h, rotDeg, clipRects, (lx, ly, hw, hh, wx, wy) -> {
			double dPlinth = sdRoundedBox(lx, ly, hw * 0.92, hh * 0.92, 0.15);
			if (dPlinth > aa)
				return 0;
			int alpha = (int) Math.round(clamp01((aa - dPlinth) / (2 * aa)) * 255);
			double dShoulders = sdRoundedBox(lx, ly, hw * 0.56, hh * 0.30, hh * 0.28);
			double dHead = Math.hypot(lx, ly) - Math.min(hw, hh) * 0.28;
			int c;
			if (dHead <= 0) {
				c = shade(stone, 22 + (int) Math.round((-lx + ly) * 20));
			} else if (dShoulders <= 0) {
				c = shade(stone, 8 + (int) Math.round((-lx + ly) * 14));
			} else if (dPlinth > -0.14) {
				c = 0xff3b3b36;
			} else {
				c = shade(stone, -16 + ((lx - ly) < 0 ? 8 : -8));
			}
			return (alpha << 24) | (c & 0xffffff);
		});
	}

	private static void drawFountain(
		int[] pixels, int width, int height, double[] bounds, double scale, double cx, double cy, double w, double h,
		List<double[]> clipRects) {
		double aa = Math.max(0.03, 1.0 / scale);
		shadeLocal(pixels, width, height, bounds, scale, cx, cy, w, h, 0, clipRects, (lx, ly, hw, hh, wx, wy) -> {
			double r = Math.min(hw, hh);
			double dist = Math.hypot(lx, ly);
			double d = dist - r;
			if (d > aa)
				return 0;
			int alpha = (int) Math.round(clamp01((aa - d) / (2 * aa)) * 255);
			int c;
			if (dist > r * 0.93) {
				c = 0xff42423c;
			} else if (dist > r * 0.78) {
				c = shade(0xffb4b0a2, (lx - ly) < 0 ? 12 : -12);
			} else if (dist < r * 0.14) {
				c = 0xffe4f7fa;
			} else if (dist < r * 0.30) {
				c = shade(0xffa7a396, (lx - ly) < 0 ? 14 : -14);
			} else {
				double ripple = Math.sin(dist * 6.5) * 10;
				c = shade(0xff5298ad, (int) Math.round(ripple));
			}
			return (alpha << 24) | (c & 0xffffff);
		});
	}

	private static void drawSarcophagus(
		int[] pixels, int width, int height, double[] bounds, double scale, double cx, double cy, double w, double h,
		double rotDeg, Integer tint, List<double[]> clipRects) {
		int stone = tint != null ? tint : 0xff9a968a;
		double aa = Math.max(0.03, 1.0 / scale);
		shadeLocal(pixels, width, height, bounds, scale, cx, cy, w, h, rotDeg, clipRects, (lx, ly, hw, hh, wx, wy) -> {
			double d = sdRoundedBox(lx, ly, hw * 0.94, hh * 0.96, 0.25);
			if (d > aa)
				return 0;
			int alpha = (int) Math.round(clamp01((aa - d) / (2 * aa)) * 255);
			int c;
			if (d > -0.14) {
				c = 0xff3b3a34;
			} else if (d > -0.35) {
				c = shade(stone, (lx - ly) < 0 ? 10 : -14);
			} else {
				double ridge = Math.abs(lx) < 0.14 ? 14 : 0;
				double cross = Math.abs(ly - hh * 0.22) < 0.14 && Math.abs(lx) < hw * 0.45 ? 14 : 0;
				c = shade(stone, (int) Math.round(Math.max(ridge, cross) + (fbm(wx * 3, wy * 3) - 0.5) * 10));
			}
			return (alpha << 24) | (c & 0xffffff);
		});
	}

	private static void drawRubble(
		int[] pixels, int width, int height, double[] bounds, double scale, double cx, double cy, double w, double h,
		List<double[]> clipRects) {
		int seed = hash2((int) Math.round(cx * 3), (int) Math.round(cy * 3));
		shadeLocal(pixels, width, height, bounds, scale, cx, cy, w, h, 0, clipRects, (lx, ly, hw, hh, wx, wy) -> {
			double dEnv = Math.hypot(lx / hw, ly / hh);
			if (dEnv > 1.0)
				return 0;
			int bestColor = 0;
			for (int i = 0; i < 8; i++) {
				double ox = (hash01(seed + i * 7, 1) - 0.5) * hw * 1.25;
				double oy = (hash01(seed + i * 11, 2) - 0.5) * hh * 1.25;
				double rad = (0.20 + hash01(seed + i * 13, 3) * 0.24) * Math.min(hw, hh);
				double dist = Math.hypot(lx - ox, ly - oy);
				double ang = Math.atan2(ly - oy, lx - ox);
				double bumpy = rad * (1.0 + 0.18 * Math.sin(ang * 3 + i) + 0.12 * Math.cos(ang * 5 - i));
				if (dist < bumpy) {
					int rock = shade(0xff8a887e, (int) Math.round((hash01(seed + i, 9) - 0.5) * 24));
					bestColor = dist > bumpy * 0.80 ? shade(rock, -32)
							: shade(rock, (lx - ox) - (ly - oy) < 0 ? 12 : -10);
				}
			}
			if (bestColor != 0)
				return 0xff000000 | (bestColor & 0xffffff);
			// Dusty ground under stones
			double dust = (1.0 - dEnv) * fbm(wx * 2.5, wy * 2.5);
			int a = (int) Math.round(clamp01(dust * 0.55) * 255);
			return a > 8 ? (a << 24) | 0x6f6c62 : 0;
		});
	}

	private static void drawPit(
		int[] pixels, int width, int height, double[] bounds, double scale, double cx, double cy, double w, double h,
		List<double[]> clipRects) {
		double aa = Math.max(0.03, 1.0 / scale);
		shadeLocal(pixels, width, height, bounds, scale, cx, cy, w, h, 0, clipRects, (lx, ly, hw, hh, wx, wy) -> {
			double jag = (fbm(wx * 1.8, wy * 1.8) - 0.5) * 0.28;
			double d = sdRoundedBox(lx, ly, hw * 0.92, hh * 0.92, 0.35) + jag;
			if (d > aa)
				return 0;
			int alpha = (int) Math.round(clamp01((aa - d) / (2 * aa)) * 255);
			double depth = clamp01(-d / Math.max(0.6, Math.min(hw, hh) * 0.65));
			int c = mixColor(0xff5a5850, 0xff0c0e11, Math.pow(depth, 0.65));
			return (alpha << 24) | (c & 0xffffff);
		});
	}

	private static void drawWeaponRack(
		int[] pixels, int width, int height, double[] bounds, double scale, double cx, double cy, double w, double h,
		double rotDeg, List<double[]> clipRects) {
		double aa = Math.max(0.03, 1.0 / scale);
		shadeLocal(pixels, width, height, bounds, scale, cx, cy, w, h, rotDeg, clipRects, (lx, ly, hw, hh, wx, wy) -> {
			double d = sdBox(lx, ly, hw, hh);
			if (d > aa)
				return 0;
			int alpha = (int) Math.round(clamp01((aa - d) / (2 * aa)) * 255);
			if (Math.abs(ly) < hh * 0.30 || Math.abs(lx) > hw - 0.25) {
				return (alpha << 24) | 0x6b4728;
			}
			// Steel blades lined across the rack
			double slot = Math.abs(Math.IEEEremainder(lx, 0.72));
			if (slot < 0.14) {
				return (alpha << 24) | (ly > 0 ? 0xffc6ccd4 : 0xff7e5c34);
			}
			return 0;
		});
	}

	private static void drawAnvil(
		int[] pixels, int width, int height, double[] bounds, double scale, double cx, double cy, double w, double h,
		double rotDeg, List<double[]> clipRects) {
		double aa = Math.max(0.03, 1.0 / scale);
		shadeLocal(pixels, width, height, bounds, scale, cx, cy, w, h, rotDeg, clipRects, (lx, ly, hw, hh, wx, wy) -> {
			double dBlock = sdRoundedBox(lx, ly, hw * 0.90, hh * 0.88, 0.18);
			if (dBlock > aa)
				return 0;
			int alpha = (int) Math.round(clamp01((aa - dBlock) / (2 * aa)) * 255);
			double horn = sdRoundedBox(lx - hw * 0.28, ly, hw * 0.50, hh * 0.26 * clamp01(1.1 - lx / hw), 0.10);
			double body = sdRoundedBox(lx + hw * 0.12, ly, hw * 0.48, hh * 0.42, 0.10);
			if (Math.min(horn, body) <= 0) {
				return (alpha << 24) | (shade(0xff4c5158, (lx - ly) < 0 ? 16 : -12) & 0xffffff);
			}
			return (alpha << 24) | (dBlock > -0.10 ? 0xff3b2716 : 0xff7a5533);
		});
	}

	private static void drawCauldron(
		int[] pixels, int width, int height, double[] bounds, double scale, double cx, double cy, double w, double h,
		Integer tint, List<double[]> clipRects) {
		int brew = tint != null ? tint : 0xff469a5c;
		double aa = Math.max(0.03, 1.0 / scale);
		shadeLocal(pixels, width, height, bounds, scale, cx, cy, w, h, 0, clipRects, (lx, ly, hw, hh, wx, wy) -> {
			double r = Math.min(hw, hh) * 0.90;
			double dist = Math.hypot(lx, ly);
			double d = dist - r;
			if (d > aa)
				return 0;
			int alpha = (int) Math.round(clamp01((aa - d) / (2 * aa)) * 255);
			int c;
			if (dist > r * 0.72) {
				c = dist > r * 0.88 ? 0xff222428 : 0xff3f434a;
			} else {
				c = shade(brew, (int) Math.round((1 - dist / r) * 18));
				if (Math.hypot(lx - r * 0.22, ly - r * 0.18) < r * 0.14
						|| Math.hypot(lx + r * 0.26, ly + r * 0.12) < r * 0.10) {
					c = shade(brew, 42);
				}
			}
			return (alpha << 24) | (c & 0xffffff);
		});
	}

	private static void drawTree(
		int[] pixels, int width, int height, double[] bounds, double scale, double cx, double cy, double w, double h,
		Integer tint, List<double[]> clipRects) {
		int leafMid = tint != null ? tint : 0xff4a7a42;
		int leafDark = shade(leafMid, -34);
		int leafLight = shade(leafMid, 28);
		int seed = hash2((int) Math.round(cx * 2), (int) Math.round(cy * 2));
		double aa = Math.max(0.04, 1.0 / scale);
		shadeLocal(pixels, width, height, bounds, scale, cx, cy, w, h, 0, clipRects, (lx, ly, hw, hh, wx, wy) -> {
			double r = Math.min(hw, hh);
			double minD = Math.hypot(lx, ly) - r * 0.56;
			for (int i = 0; i < 6; i++) {
				double ang = (2 * Math.PI * i / 6.0) + (hash01(seed + i, 3) - 0.5) * 0.45;
				double lr = r * (0.38 + hash01(seed + i, 5) * 0.16);
				double dist = r * (0.42 + hash01(seed + i, 7) * 0.12);
				minD = Math.min(minD, Math.hypot(lx - Math.cos(ang) * dist, ly - Math.sin(ang) * dist) - lr);
			}
			if (minD > aa)
				return 0;
			int alpha = (int) Math.round(clamp01((aa - minD) / (2 * aa)) * 255);
			double dapple = fbm(wx * 1.4, wy * 1.4);
			double sun = clamp01(0.5 + (-lx + ly) / (r * 1.8));
			int c;
			if (minD > -Math.max(0.16, r * 0.06)) {
				c = shade(leafDark, -14);
			} else {
				c = mixColor(leafDark, leafLight, clamp01(sun * 0.72 + dapple * 0.42 - 0.12));
				if (dapple > 0.68 && sun > 0.45) {
					c = shade(c, 16);
				}
			}
			return (alpha << 24) | (c & 0xffffff);
		});
	}

	private static void drawPineTree(
		int[] pixels, int width, int height, double[] bounds, double scale, double cx, double cy, double w, double h,
		Integer tint, List<double[]> clipRects) {
		int base = tint != null ? tint : 0xff355f3b;
		double aa = Math.max(0.04, 1.0 / scale);
		shadeLocal(pixels, width, height, bounds, scale, cx, cy, w, h, 0, clipRects, (lx, ly, hw, hh, wx, wy) -> {
			double r = Math.min(hw, hh);
			double dist = Math.hypot(lx, ly);
			double ang = Math.atan2(ly, lx);
			double outer = r * (0.80 + 0.13 * Math.cos(ang * 9.0) + 0.07 * Math.cos(ang * 18.0 + 0.4));
			double d = dist - outer;
			if (d > aa)
				return 0;
			int alpha = (int) Math.round(clamp01((aa - d) / (2 * aa)) * 255);
			double mid = r * (0.56 + 0.12 * Math.cos(ang * 9.0 + 0.35) + 0.05 * Math.cos(ang * 18.0));
			double top = r * (0.31 + 0.08 * Math.cos(ang * 7.0 + 0.8));
			int tier = dist < top ? 26 : dist < mid ? 10 : -12;
			int sun = (int) Math.round((-lx + ly) / Math.max(0.5, r) * 16);
			int c = d > -0.14 ? shade(base, -34)
					: shade(base, tier + sun + (int) Math.round((fbm(wx * 2.2, wy * 2.2) - 0.5) * 12));
			return (alpha << 24) | (c & 0xffffff);
		});
	}

	private static void drawBush(
		int[] pixels, int width, int height, double[] bounds, double scale, double cx, double cy, double w, double h,
		Integer tint, List<double[]> clipRects) {
		int leaf = tint != null ? tint : 0xff558246;
		int seed = hash2((int) Math.round(cx * 5), (int) Math.round(cy * 5));
		double aa = Math.max(0.03, 1.0 / scale);
		shadeLocal(pixels, width, height, bounds, scale, cx, cy, w, h, 0, clipRects, (lx, ly, hw, hh, wx, wy) -> {
			double r = Math.min(hw, hh);
			double minD = Math.hypot(lx, ly) - r * 0.48;
			for (int i = 0; i < 5; i++) {
				double ang = 2 * Math.PI * i / 5.0 + hash01(seed + i, 1) * 0.5;
				double ox = Math.cos(ang) * r * 0.36, oy = Math.sin(ang) * r * 0.36;
				minD = Math.min(minD, Math.hypot(lx - ox, ly - oy) - r * 0.44);
			}
			if (minD > aa)
				return 0;
			int alpha = (int) Math.round(clamp01((aa - minD) / (2 * aa)) * 255);
			double n = fbm(wx * 2.4, wy * 2.4);
			int c = minD > -0.12 ? shade(leaf, -34)
					: shade(leaf, (int) Math.round((-lx + ly) / Math.max(0.5, r) * 18 + (n - 0.5) * 20));
			return (alpha << 24) | (c & 0xffffff);
		});
	}

	private static void drawBoulder(
		int[] pixels, int width, int height, double[] bounds, double scale, double cx, double cy, double w, double h,
		Integer tint, List<double[]> clipRects) {
		int rock = tint != null ? tint : 0xff8b8b82;
		int seed = hash2((int) Math.round(cx * 3), (int) Math.round(cy * 3));
		double phase = hash01(seed, 4) * Math.PI * 2;
		double aa = Math.max(0.03, 1.0 / scale);
		shadeLocal(pixels, width, height, bounds, scale, cx, cy, w, h, 0, clipRects, (lx, ly, hw, hh, wx, wy) -> {
			double nx = lx / hw, ny = ly / hh;
			double ang = Math.atan2(ny, nx);
			double radius = 0.84 + 0.11 * Math.sin(ang * 3 + phase) + 0.06 * Math.cos(ang * 5 - phase);
			double d = (Math.hypot(nx, ny) - radius) * Math.min(hw, hh);
			if (d > aa)
				return 0;
			int alpha = (int) Math.round(clamp01((aa - d) / (2 * aa)) * 255);
			if (d > -0.12)
				return (alpha << 24) | 0xff3b3b37;
			double facet = (-nx + ny) * 24 + (fbm(wx * 2.6, wy * 2.6) - 0.5) * 16;
			int c = shade(rock, (int) Math.round(facet));
			// Ridge line across the rock top
			if (Math.abs(nx * 0.6 + ny * 0.8 - 0.08) < 0.05) {
				c = shade(c, 16);
			}
			return (alpha << 24) | (c & 0xffffff);
		});
	}

	private static void drawStump(
		int[] pixels, int width, int height, double[] bounds, double scale, double cx, double cy, double w, double h,
		List<double[]> clipRects) {
		double aa = Math.max(0.03, 1.0 / scale);
		shadeLocal(pixels, width, height, bounds, scale, cx, cy, w, h, 0, clipRects, (lx, ly, hw, hh, wx, wy) -> {
			double r = Math.min(hw, hh) * 0.90;
			double ang = Math.atan2(ly, lx);
			double edge = r * (0.94 + 0.06 * Math.sin(ang * 5));
			double dist = Math.hypot(lx, ly);
			double d = dist - edge;
			if (d > aa)
				return 0;
			int alpha = (int) Math.round(clamp01((aa - d) / (2 * aa)) * 255);
			int c;
			if (dist > edge * 0.78) {
				c = 0xff4c3621; // bark
			} else {
				double ring = Math.sin(dist * 10.5);
				c = shade(0xffc9a675, ring > 0.65 ? -16 : 0);
			}
			return (alpha << 24) | (c & 0xffffff);
		});
	}

	private static void drawCampfire(
		int[] pixels, int width, int height, double[] bounds, double scale, double cx, double cy, double w, double h,
		String state, List<double[]> clipRects) {
		boolean lit = !"EXTINGUISHED".equals(state) && !"OFF".equals(state);
		double aa = Math.max(0.03, 1.0 / scale);
		shadeLocal(pixels, width, height, bounds, scale, cx, cy, w, h, 0, clipRects, (lx, ly, hw, hh, wx, wy) -> {
			double r = Math.min(hw, hh);
			double dist = Math.hypot(lx, ly);
			if (dist > r + aa)
				return 0;
			// Ring of river stones
			for (int i = 0; i < 8; i++) {
				double ang = 2 * Math.PI * i / 8.0;
				double sx = Math.cos(ang) * r * 0.76, sy = Math.sin(ang) * r * 0.76;
				double ds = Math.hypot(lx - sx, ly - sy) - r * 0.22;
				if (ds <= 0) {
					return 0xff000000 | (ds > -0.08 ? 0xff3b3a36 : shade(0xff8c8a80, (i % 3 - 1) * 12));
				}
			}
			if (dist > r * 0.76)
				return 0;
			// Crossed charred logs
			if ((Math.abs(lx - ly) < r * 0.14 || Math.abs(lx + ly) < r * 0.14) && dist < r * 0.62 && dist > r * 0.30) {
				return 0xff463120;
			}
			if (!lit) {
				return dist < r * 0.55 ? 0xff35302c : 0;
			}
			if (dist < r * 0.24)
				return 0xfffff28c;
			if (dist < r * 0.48)
				return mixColor(0xffff962e, 0xffd9411b, (dist - r * 0.24) / (r * 0.24));
			return mixColor(0xff6b2616, 0xaa2e2520, (dist - r * 0.48) / (r * 0.28));
		});
	}

	private static void drawTent(
		int[] pixels, int width, int height, double[] bounds, double scale, double cx, double cy, double w, double h,
		double rotDeg, Integer tint, List<double[]> clipRects) {
		int canvas = tint != null ? tint : 0xffcbb994;
		double aa = Math.max(0.03, 1.0 / scale);
		boolean horiz = w >= h;
		shadeLocal(pixels, width, height, bounds, scale, cx, cy, w, h, rotDeg, clipRects, (lx, ly, hw, hh, wx, wy) -> {
			double d = sdRoundedBox(lx, ly, hw * 0.94, hh * 0.94, 0.18);
			if (d > aa)
				return 0;
			int alpha = (int) Math.round(clamp01((aa - d) / (2 * aa)) * 255);
			if (d > -0.11)
				return (alpha << 24) | (shade(canvas, -48) & 0xffffff);
			double across = horiz ? ly : lx;
			int slope = across > 0 ? 14 : -14;
			int c = shade(canvas, slope + (int) Math.round((fbm(lx * 2, ly * 2) - 0.5) * 10));
			if (Math.abs(across) < 0.12)
				c = shade(canvas, -30); // ridge pole
			return (alpha << 24) | (c & 0xffffff);
		});
	}

	private static void drawWagon(
		int[] pixels, int width, int height, double[] bounds, double scale, double cx, double cy, double w, double h,
		double rotDeg, Integer tint, List<double[]> clipRects) {
		int wood = tint != null ? tint : 0xff7f5631;
		double aa = Math.max(0.03, 1.0 / scale);
		boolean horiz = w >= h;
		shadeLocal(pixels, width, height, bounds, scale, cx, cy, w, h, rotDeg, clipRects, (lx, ly, hw, hh, wx, wy) -> {
			double along = horiz ? lx : ly, across = horiz ? ly : lx;
			double halfL = horiz ? hw : hh, halfW = horiz ? hh : hw;
			// Wheels peeking out on sides
			double wheelL = Math.abs(Math.abs(along) - halfL * 0.55);
			if (wheelL < halfL * 0.18 && Math.abs(across) > halfW * 0.78 && Math.abs(across) <= halfW) {
				return 0xff3b2716;
			}
			// Main wagon bed
			double d = sdRoundedBox(along, across, halfL * 0.88, halfW * 0.80, 0.15);
			if (d > aa)
				return 0;
			int alpha = (int) Math.round(clamp01((aa - d) / (2 * aa)) * 255);
			if (d > -0.14)
				return (alpha << 24) | (shade(wood, -38) & 0xffffff);
			int plank = (int) Math.floor((across + halfW) / 0.55);
			double f = (across + halfW) / 0.55 - plank;
			int c = shade(wood, (f < 0.10 ? -20 : 0) + (int) Math.round((fbm(along * 1.5, across * 5) - 0.5) * 12));
			return (alpha << 24) | (c & 0xffffff);
		});
	}

	private static void drawPuddle(
		int[] pixels, int width, int height, double[] bounds, double scale, double cx, double cy, double w, double h,
		List<double[]> clipRects) {
		shadeLocal(pixels, width, height, bounds, scale, cx, cy, w, h, 0, clipRects, (lx, ly, hw, hh, wx, wy) -> {
			double nx = lx / hw, ny = ly / hh;
			double ang = Math.atan2(ny, nx);
			double r = 0.76 + 0.16 * Math.sin(ang * 2 + 0.7) + 0.10 * Math.cos(ang * 3 - 0.5);
			double dist = Math.hypot(nx, ny);
			if (dist > r)
				return 0;
			double fade = clamp01((r - dist) * 4.0);
			double glint = clamp01(1.0 - Math.hypot((nx + 0.22) * 2.4, (ny - 0.18) * 3.8));
			int c = mixColor(0xff467285, 0xffa8d2e2, glint * glint * 0.65);
			return ((int) Math.round(fade * 170) << 24) | (c & 0xffffff);
		});
	}

	private static void drawCracks(
		int[] pixels, int width, int height, double[] bounds, double scale, double cx, double cy, double w, double h,
		List<double[]> clipRects) {
		shadeLocal(pixels, width, height, bounds, scale, cx, cy, w, h, 0, clipRects, (lx, ly, hw, hh, wx, wy) -> {
			double env = Math.hypot(lx / hw, ly / hh);
			if (env > 0.95)
				return 0;
			double n = Math.abs(fbm(wx * 1.4, wy * 1.4) - 0.5);
			if (n < 0.022 * (1.1 - env)) {
				return 0xb824231f;
			}
			return 0;
		});
	}

	private static void drawScorch(
		int[] pixels, int width, int height, double[] bounds, double scale, double cx, double cy, double w, double h,
		List<double[]> clipRects) {
		shadeLocal(pixels, width, height, bounds, scale, cx, cy, w, h, 0, clipRects, (lx, ly, hw, hh, wx, wy) -> {
			double dist = Math.hypot(lx / hw, ly / hh) + (fbm(wx * 1.5, wy * 1.5) - 0.5) * 0.28;
			if (dist > 0.95)
				return 0;
			double alpha = clamp01(1.0 - dist / 0.95) * 0.72;
			int c = dist < 0.30 ? 0xff3b3835 : 0xff191817;
			return ((int) Math.round(alpha * 255) << 24) | (c & 0xffffff);
		});
	}

	private static void drawBloodstain(
		int[] pixels, int width, int height, double[] bounds, double scale, double cx, double cy, double w, double h,
		List<double[]> clipRects) {
		shadeLocal(pixels, width, height, bounds, scale, cx, cy, w, h, 0, clipRects, (lx, ly, hw, hh, wx, wy) -> {
			double nx = lx / hw, ny = ly / hh;
			double dist = Math.hypot(nx, ny) + (fbm(wx * 2.2, wy * 2.2) - 0.5) * 0.32;
			if (dist > 0.72)
				return 0;
			double fade = clamp01((0.72 - dist) * 5.0);
			int c = dist < 0.35 ? 0xff5a1214 : 0xff781c1e;
			return ((int) Math.round(fade * 195) << 24) | (c & 0xffffff);
		});
	}

	private static void drawBanner(
		int[] pixels, int width, int height, double[] bounds, double scale, double cx, double cy, double w, double h,
		double rotDeg, Integer tint, List<double[]> clipRects) {
		int color = tint != null ? tint : 0xff8d2c32;
		double aa = Math.max(0.03, 1.0 / scale);
		shadeLocal(pixels, width, height, bounds, scale, cx, cy, w, h, rotDeg, clipRects, (lx, ly, hw, hh, wx, wy) -> {
			double d = sdBox(lx, ly, hw, hh);
			if (d > aa)
				return 0;
			int c = d > -0.10 ? 0xffcba24a : Math.abs(lx) < hw * 0.18 ? 0xffcba24a : color;
			return 0xe8000000 | (c & 0xffffff);
		});
	}

	private static void drawTrapPlate(
		int[] pixels, int width, int height, double[] bounds, double scale, double cx, double cy, double w, double h,
		double rotDeg, List<double[]> clipRects) {
		double aa = Math.max(0.03, 1.0 / scale);
		shadeLocal(pixels, width, height, bounds, scale, cx, cy, w, h, rotDeg, clipRects, (lx, ly, hw, hh, wx, wy) -> {
			double d = sdBox(lx, ly, hw * 0.88, hh * 0.88);
			if (d > aa)
				return 0;
			int c;
			if (d > -0.14) {
				c = 0xff46423c;
			} else if (Math.abs(Math.abs(lx) - hw * 0.62) < 0.14 && Math.abs(Math.abs(ly) - hh * 0.62) < 0.14) {
				c = 0xff2b2926; // corner bolts
			} else if (Math.abs(lx - ly) < 0.12 || Math.abs(lx + ly) < 0.12) {
				c = 0xffb36c2e; // amber hazard seam
			} else {
				c = 0xff77746b;
			}
			return 0xe0000000 | (c & 0xffffff);
		});
	}

	private static void drawDestroyedDebris(
		int[] pixels, int width, int height, double[] bounds, double scale, double cx, double cy, double w, double h,
		double rotDeg, List<double[]> clipRects) {
		shadeLocal(pixels, width, height, bounds, scale, cx, cy, w, h, rotDeg, clipRects, (lx, ly, hw, hh, wx, wy) -> {
			double d = sdBox(lx, ly, hw * 0.92, hh * 0.92);
			if (d > 0)
				return 0;
			double splinter1 = Math.abs(lx * 0.55 - ly * 0.82);
			double splinter2 = Math.abs(lx * 0.78 + ly * 0.62 - 0.25);
			if (splinter1 < 0.22 || splinter2 < 0.20) {
				return 0xff7e6549;
			}
			double n = fbm(wx * 3.0, wy * 3.0);
			return n > 0.58 ? 0xaa645749 : 0;
		});
	}

	private static void drawGenericObject(
		int[] pixels, int width, int height, double[] bounds, double scale, double cx, double cy, double w, double h,
		double rotDeg, Integer tint, AssetService.Texture customTex, List<double[]> clipRects) {
		int base = tint != null ? tint : 0xff946b42;
		double aa = Math.max(0.03, 1.0 / scale);
		shadeLocal(pixels, width, height, bounds, scale, cx, cy, w, h, rotDeg, clipRects, (lx, ly, hw, hh, wx, wy) -> {
			double d = sdRoundedBox(lx, ly, hw, hh, Math.min(0.16, Math.min(hw, hh) * 0.12));
			if (d > aa)
				return 0;
			int alpha = (int) Math.round(clamp01((aa - d) / (2 * aa)) * 255);
			int c;
			if (d > -0.10) {
				c = shade(base, -46);
			} else if (customTex != null) {
				PngCodec.Raster image = customTex.raster();
				double factor = image.width() / customTex.repeatFeet();
				int tx = Math.floorMod((int) Math.floor(wx * factor), image.width());
				int ty = Math.floorMod((int) Math.floor(-wy * factor), image.height());
				c = image.pixels()[ty * image.width() + tx];
			} else {
				double grain = (fbm(wx * 2.2, wy * 5.0) - 0.5) * 14.0;
				int bevel = d > -0.26 ? ((lx - ly) < 0 ? 12 : -12) : 0;
				c = shade(base, bevel + (int) Math.round(grain));
			}
			return (alpha << 24) | (c & 0xffffff);
		});
	}

	private static void drawPropShadow(
		int[] pixels, int width, int height, double[] bounds, double scale, double cx, double cy, double w, double h,
		double rotDeg, boolean roundShape, double maxAlpha, List<double[]> clipRects) {
		double pad = 0.25;
		shadeLocal(pixels, width, height, bounds, scale, cx, cy, w + pad * 2, h + pad * 2, rotDeg, clipRects,
				(lx, ly, hw, hh, wx, wy) -> {
					double iw = hw - pad, ih = hh - pad;
					double d = roundShape
							? Math.hypot(lx / Math.max(0.1, iw), ly / Math.max(0.1, ih)) * Math.min(iw, ih)
									- Math.min(iw, ih)
							: sdRoundedBox(lx, ly, iw, ih, 0.18);
					if (d > pad)
						return 0;
					double t = d <= 0 ? 1.0 : clamp01(1.0 - d / pad);
					int a = (int) Math.round(t * t * maxAlpha * 255);
					return a > 0 ? (a << 24) | 0x0e1014 : 0;
				});
	}

	// --- Atmospheric Lighting & Mood Pass ---

	static void applyLighting(
		int[] pixels, int width, int height, double[] bounds, double scale, Map<String, Object> scene,
		List<Map<String, Object>> rooms, List<Light> lights, List<WallSegment> blockers, boolean fogEnabled,
		java.util.function.BiPredicate<Double, Double> knownCheck) {
		String mapAmbient = String.valueOf(scene.getOrDefault("illumination", "BRIGHT")).toUpperCase(Locale.ROOT);
		boolean hasDimOrDark = !"BRIGHT".equals(mapAmbient);
		var roomLights = new ArrayList<RoomLight>();
		for (Map<String, Object> room : rooms) {
			Object raw = room.get("illumination");
			if (raw == null) {
				continue;
			}
			String level = raw.toString().toUpperCase(Locale.ROOT);
			if (!"BRIGHT".equals(level)) {
				hasDimOrDark = true;
			}
			double[] rect = numbers(room.get("rect_ft"), 4);
			List<double[]> poly = points(room.get("polygon_ft"));
			if (rect != null || poly.size() >= 3) {
				roomLights.add(new RoomLight(rect, poly, level));
			}
		}
		if (!hasDimOrDark && lights.isEmpty()) {
			return;
		}
		// 1. Subtle ambient mood tint for DIM / DARK areas
		if (hasDimOrDark) {
			for (int y = 0; y < height; y++) {
				double wy = bounds[1] + bounds[3] - y / scale;
				for (int x = 0; x < width; x++) {
					double wx = bounds[0] + x / scale;
					if (fogEnabled && !knownCheck.test(wx, wy)) {
						continue;
					}
					String effective = mapAmbient;
					for (RoomLight rl : roomLights) {
						if (rl.contains(wx, wy)) {
							effective = rl.level();
							break;
						}
					}
					if ("DIM".equals(effective)) {
						pixels[y * width + x] = blend(pixels[y * width + x], 0x3a141c2b);
					} else if ("DARK".equals(effective)) {
						pixels[y * width + x] = blend(pixels[y * width + x], 0x5c0e1624);
					}
				}
			}
		}
		// 2. Warm/cool radial light pools (occluded by walls/closed doors so light stays inside rooms)
		for (Light light : lights) {
			if (light.radiusFt() <= 0 || light.intensity() <= 0) {
				continue;
			}
			double r = light.radiusFt();
			var nearby = new ArrayList<WallSegment>();
			for (WallSegment seg : blockers) {
				double sMinX = Math.min(seg.ax(), seg.bx()), sMaxX = Math.max(seg.ax(), seg.bx());
				double sMinY = Math.min(seg.ay(), seg.by()), sMaxY = Math.max(seg.ay(), seg.by());
				if (sMaxX >= light.x() - r && sMinX <= light.x() + r && sMaxY >= light.y() - r
						&& sMinY <= light.y() + r) {
					nearby.add(seg);
				}
			}
			int minX = Math.max(0, (int) Math.floor(px(bounds, scale, light.x() - r)));
			int maxX = Math.min(width - 1, (int) Math.ceil(px(bounds, scale, light.x() + r)));
			int minY = Math.max(0, (int) Math.floor(py(bounds, scale, light.y() + r)));
			int maxY = Math.min(height - 1, (int) Math.ceil(py(bounds, scale, light.y() - r)));
			int lr = (light.color() >>> 16) & 255;
			int lg = (light.color() >>> 8) & 255;
			int lb = light.color() & 255;
			double strength = Math.min(1.0, light.intensity()) * 0.30;
			for (int y = minY; y <= maxY; y++) {
				double wy = bounds[1] + bounds[3] - y / scale;
				for (int x = minX; x <= maxX; x++) {
					double wx = bounds[0] + x / scale;
					double dist = Math.hypot(wx - light.x(), wy - light.y());
					if (dist >= r) {
						continue;
					}
					if (fogEnabled && !knownCheck.test(wx, wy)) {
						continue;
					}
					if (!nearby.isEmpty() && blocked(light.x(), light.y(), wx, wy, nearby)) {
						continue;
					}
					double falloff = 1.0 - dist / r;
					double lift = falloff * falloff * strength;
					int base = pixels[y * width + x];
					int br = (base >>> 16) & 255, bg = (base >>> 8) & 255, bb = base & 255;
					// Warm screen-like lift that brightens dim rooms without blowing out highlights
					int nr = Math.min(255, (int) Math.round(br + (lr - br * 0.35) * lift));
					int ng = Math.min(255, (int) Math.round(bg + (lg - bg * 0.35) * lift));
					int nb = Math.min(255, (int) Math.round(bb + (lb - bb * 0.35) * lift));
					pixels[y * width + x] = 0xff000000 | (nr << 16) | (ng << 8) | nb;
				}
			}
		}
	}

	private record RoomLight(double[] rect, List<double[]> poly, String level) {
		boolean contains(double x, double y) {
			if (rect != null) {
				return x >= rect[0] && x <= rect[0] + rect[2] && y >= rect[1] && y <= rect[1] + rect[3];
			}
			return inside(poly, x, y);
		}
	}

	private static boolean blocked(double ax, double ay, double bx, double by, List<WallSegment> walls) {
		for (WallSegment w : walls) {
			if (segmentsStrictlyCross(ax, ay, bx, by, w.ax(), w.ay(), w.bx(), w.by())) {
				return true;
			}
		}
		return false;
	}

	private static boolean segmentsStrictlyCross(
		double ax, double ay, double bx, double by, double cx, double cy, double dx, double dy) {
		double d1 = cross(ax, ay, bx, by, cx, cy);
		double d2 = cross(ax, ay, bx, by, dx, dy);
		if ((d1 >= -1e-6 && d2 >= -1e-6) || (d1 <= 1e-6 && d2 <= 1e-6)) {
			return false;
		}
		double d3 = cross(cx, cy, dx, dy, ax, ay);
		double d4 = cross(cx, cy, dx, dy, bx, by);
		return (d3 < -1e-6 && d4 > 1e-6) || (d3 > 1e-6 && d4 < -1e-6);
	}

	private static double cross(double ax, double ay, double bx, double by, double px, double py) {
		return (bx - ax) * (py - ay) - (by - ay) * (px - ax);
	}

	// --- Shared local-space & geometry helpers ---

	static void shadeLocal(
		int[] pixels, int width, int height, double[] bounds, double scale, double cx, double cy, double w, double h,
		double rotDeg, List<double[]> clipRects, PixelShader shader) {
		double hw = w * 0.5, hh = h * 0.5;
		double rad = Math.toRadians(rotDeg);
		double cos = Math.cos(rad), sin = Math.sin(rad);
		double extX = Math.abs(hw * cos) + Math.abs(hh * sin) + 0.15;
		double extY = Math.abs(hw * sin) + Math.abs(hh * cos) + 0.15;
		int minX = Math.max(0, (int) Math.floor(px(bounds, scale, cx - extX)));
		int maxX = Math.min(width - 1, (int) Math.ceil(px(bounds, scale, cx + extX)));
		int minY = Math.max(0, (int) Math.floor(py(bounds, scale, cy + extY)));
		int maxY = Math.min(height - 1, (int) Math.ceil(py(bounds, scale, cy - extY)));
		for (int y = minY; y <= maxY; y++) {
			double wy = bounds[1] + bounds[3] - y / scale;
			for (int x = minX; x <= maxX; x++) {
				double wx = bounds[0] + x / scale;
				if (clipRects != null && !insideAnyRect(clipRects, wx, wy)) {
					continue;
				}
				double dx = wx - cx, dy = wy - cy;
				double lx = dx * cos - dy * sin;
				double ly = dx * sin + dy * cos;
				int color = shader.shade(lx, ly, hw, hh, wx, wy);
				if ((color >>> 24) != 0) {
					pixels[y * width + x] = blend(pixels[y * width + x], color);
				}
			}
		}
	}

	private static boolean insideAnyRect(List<double[]> rects, double x, double y) {
		for (double[] r : rects) {
			if (x >= r[0] && x <= r[0] + r[2] && y >= r[1] && y <= r[1] + r[3]) {
				return true;
			}
		}
		return false;
	}

	static void drawSegmentStroke(
		int[] pixels, int width, int height, double[] bounds, double scale, double ax, double ay, double bx, double by,
		double widthFt, int color) {
		double x1 = px(bounds, scale, ax), y1 = py(bounds, scale, ay);
		double x2 = px(bounds, scale, bx), y2 = py(bounds, scale, by);
		double halfThick = Math.max(0.8, widthFt * scale * 0.5);
		int minX = Math.max(0, (int) Math.floor(Math.min(x1, x2) - halfThick - 1));
		int maxX = Math.min(width - 1, (int) Math.ceil(Math.max(x1, x2) + halfThick + 1));
		int minY = Math.max(0, (int) Math.floor(Math.min(y1, y2) - halfThick - 1));
		int maxY = Math.min(height - 1, (int) Math.ceil(Math.max(y1, y2) + halfThick + 1));
		int baseAlpha = color >>> 24;
		for (int y = minY; y <= maxY; y++) {
			for (int x = minX; x <= maxX; x++) {
				double d = distToSegment(x, y, x1, y1, x2, y2);
				if (d <= halfThick + 0.6) {
					double cov = clamp01(halfThick + 0.5 - d);
					int a = (int) Math.round(baseAlpha * cov);
					if (a > 0) {
						pixels[y * width + x] = blend(pixels[y * width + x], (a << 24) | (color & 0xffffff));
					}
				}
			}
		}
	}

	static void fillCircleAA(int[] pixels, int width, int height, double cx, double cy, double radius, int color) {
		int minX = Math.max(0, (int) Math.floor(cx - radius - 1));
		int maxX = Math.min(width - 1, (int) Math.ceil(cx + radius + 1));
		int minY = Math.max(0, (int) Math.floor(cy - radius - 1));
		int maxY = Math.min(height - 1, (int) Math.ceil(cy + radius + 1));
		int baseAlpha = color >>> 24;
		for (int y = minY; y <= maxY; y++) {
			for (int x = minX; x <= maxX; x++) {
				double d = Math.hypot(x - cx, y - cy);
				if (d <= radius + 0.7) {
					double cov = clamp01(radius + 0.5 - d);
					int a = (int) Math.round(baseAlpha * cov);
					if (a > 0) {
						pixels[y * width + x] = blend(pixels[y * width + x], (a << 24) | (color & 0xffffff));
					}
				}
			}
		}
	}

	static double distToSegment(double px, double py, double ax, double ay, double bx, double by) {
		double dx = bx - ax, dy = by - ay;
		double lenSq = dx * dx + dy * dy;
		if (lenSq < 1e-9) {
			return Math.hypot(px - ax, py - ay);
		}
		double t = Math.max(0, Math.min(1, ((px - ax) * dx + (py - ay) * dy) / lenSq));
		return Math.hypot(px - (ax + t * dx), py - (ay + t * dy));
	}

	static double sdBox(double x, double y, double hw, double hh) {
		double dx = Math.abs(x) - hw, dy = Math.abs(y) - hh;
		return Math.hypot(Math.max(dx, 0), Math.max(dy, 0)) + Math.min(Math.max(dx, dy), 0);
	}

	static double sdRoundedBox(double x, double y, double hw, double hh, double r) {
		double rr = Math.min(r, Math.min(hw, hh));
		return sdBox(x, y, hw - rr, hh - rr) - rr;
	}

	private static double px(double[] bounds, double scale, double x) {
		return (x - bounds[0]) * scale;
	}

	private static double py(double[] bounds, double scale, double y) {
		return (bounds[1] + bounds[3] - y) * scale;
	}

	private static double lerp(double a, double b, double t) {
		return a + (b - a) * t;
	}

	static double clamp01(double v) {
		return Math.max(0.0, Math.min(1.0, v));
	}

	static int shade(int color, int change) {
		int r = Math.clamp(((color >>> 16) & 255) + change, 0, 255);
		int g = Math.clamp(((color >>> 8) & 255) + change, 0, 255);
		int b = Math.clamp((color & 255) + change, 0, 255);
		return (color & 0xff000000) | (r << 16) | (g << 8) | b;
	}

	static int mixColor(int a, int b, double t) {
		double k = clamp01(t);
		int r = (int) Math.round(((a >>> 16) & 255) * (1 - k) + ((b >>> 16) & 255) * k);
		int g = (int) Math.round(((a >>> 8) & 255) * (1 - k) + ((b >>> 8) & 255) * k);
		int bl = (int) Math.round((a & 255) * (1 - k) + (b & 255) * k);
		return 0xff000000 | (r << 16) | (g << 8) | bl;
	}

	static int blend(int below, int above) {
		int a = above >>> 24;
		if (a == 255) {
			return above;
		}
		if (a == 0) {
			return below;
		}
		int r = (((above >>> 16) & 255) * a + ((below >>> 16) & 255) * (255 - a)) / 255;
		int g = (((above >>> 8) & 255) * a + ((below >>> 8) & 255) * (255 - a)) / 255;
		int b = ((above & 255) * a + (below & 255) * (255 - a)) / 255;
		return 0xff000000 | (r << 16) | (g << 8) | b;
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

	private static double[] numbers(Object value, int length) {
		if (!(value instanceof List<?> list) || list.size() != length) {
			return null;
		}
		double[] result = new double[length];
		for (int i = 0; i < length; i++) {
			if (!(list.get(i) instanceof Number n) || !Double.isFinite(n.doubleValue())) {
				return null;
			}
			result[i] = n.doubleValue();
		}
		return result;
	}

	private static List<double[]> points(Object value) {
		if (!(value instanceof List<?> list)) {
			return List.of();
		}
		var result = new ArrayList<double[]>();
		for (Object item : list) {
			double[] pair = numbers(item, 2);
			if (pair != null) {
				result.add(pair);
			}
		}
		return result;
	}
}
