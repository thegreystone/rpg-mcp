/*
 * Copyright (C) 2026 Marcus Hirt
 * SPDX-License-Identifier: BSD-3-Clause
 */
package se.hirt.mcp.rpg.maps;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Curated catalog of general tabletop props, materials and lighting presets shared by authoring
 * guidance, validation and deterministic top-down rendering.
 */
public final class MapPropCatalog {

	public record PropSpec(String id, String category, double defaultWidthFt, double defaultHeightFt, boolean flat,
			boolean decorativeDefault, double lightRadiusFt, int lightColor, String description, List<String> aliases) {
	}

	public record LightPreset(String id, double defaultRadiusFt, int color, double intensity, String description) {
	}

	private static final Map<String, PropSpec> PROPS = buildProps();
	private static final Map<String, String> ALIASES = buildAliases(PROPS);
	private static final Map<String, LightPreset> LIGHTS = buildLights();
	private static final List<String> MATERIALS = List.of("STONE", "ROUGH_STONE", "DAMP_STONE", "COBBLESTONE",
			"DECORATIVE_TILE", "WOOD", "DARK_WOOD", "CARPET", "EARTH", "MUD", "SAND", "GRASS", "FOREST", "WATER", "ICE",
			"SNOW", "VOID");

	private MapPropCatalog() {
	}

	public static PropSpec prop(String id) {
		if (id == null || id.isBlank()) {
			return null;
		}
		String normalized = normalize(id);
		PropSpec direct = PROPS.get(normalized);
		if (direct != null) {
			return direct;
		}
		String aliased = ALIASES.get(normalized);
		return aliased == null ? null : PROPS.get(aliased);
	}

	/**
	 * Resolve a prop specification from explicit visual fields or natural naming fallbacks.
	 */
	public static PropSpec resolve(Map<String, Object> item) {
		if (item == null) {
			return null;
		}
		for (String field : List.of("prop", "kind")) {
			Object raw = item.get(field);
			if (raw != null) {
				PropSpec match = prop(raw.toString());
				if (match != null) {
					return match;
				}
			}
		}
		StringBuilder hints = new StringBuilder();
		for (String field : List.of("prop", "kind", "name", "label", "key")) {
			Object raw = item.get(field);
			if (raw != null) {
				hints.append(' ').append(raw.toString().toLowerCase(Locale.ROOT));
			}
		}
		String text = hints.toString();
		if (text.isBlank()) {
			return null;
		}
		if (text.contains("trap") || text.contains("pressure plate") || text.contains("tripwire")) {
			return PROPS.get("trap_plate");
		}
		if (text.contains("round") && text.contains("table")) {
			return PROPS.get("table_round");
		}
		if (text.contains("bar") && !text.contains("barrel") && !text.contains("barricade")
				|| text.contains("counter")) {
			return PROPS.get("bar_counter");
		}
		if (text.contains("table") || text.contains("desk") || text.contains("workbench")) {
			return PROPS.get("table_rect");
		}
		if (text.contains("stool")) {
			return PROPS.get("stool");
		}
		if (text.contains("bench") || text.contains("pew")) {
			return PROPS.get("bench");
		}
		if (text.contains("chair") || text.contains("throne")) {
			return PROPS.get("chair");
		}
		if (text.contains("hearth") || text.contains("fireplace")) {
			return PROPS.get("hearth");
		}
		if (text.contains("campfire") || text.contains("bonfire") || text.contains("firepit")) {
			return PROPS.get("campfire");
		}
		if (text.contains("brazier") || text.contains("fire bowl")) {
			return PROPS.get("brazier");
		}
		if (text.contains("torch") || text.contains("sconce")) {
			return PROPS.get("torch_sconce");
		}
		if (text.contains("lantern") || text.contains("candle") || text.contains("lamp")) {
			return PROPS.get("lantern");
		}
		if (text.contains("chest") || text.contains("strongbox") || text.contains("coffer")) {
			return PROPS.get("chest");
		}
		if (text.contains("barrel") || text.contains("cask") || text.contains("keg")) {
			return PROPS.get("barrel");
		}
		if (text.contains("crate") || text.contains("box")) {
			return PROPS.get("crate");
		}
		if (text.contains("bookshelf") || text.contains("bookcase") || text.contains("shelf")
				|| text.contains("cabinet") || text.contains("wardrobe")) {
			return PROPS.get("bookshelf");
		}
		if (text.contains("bed") || text.contains("cot") || text.contains("bunk")) {
			return PROPS.get("bed");
		}
		if (text.contains("runner")) {
			return PROPS.get("carpet_runner");
		}
		if (text.contains("rug") || text.contains("carpet") || text.contains("tapestry")) {
			return PROPS.get("rug");
		}
		if (text.contains("altar") || text.contains("shrine") || text.contains("dais")) {
			return PROPS.get("altar");
		}
		if (text.contains("sarcophagus") || text.contains("tomb") || text.contains("coffin")) {
			return PROPS.get("sarcophagus");
		}
		if (text.contains("fountain") || text.contains("well") || text.contains("basin")) {
			return PROPS.get("fountain");
		}
		if (text.contains("statue") || text.contains("idol")) {
			return PROPS.get("statue");
		}
		if (text.contains("column") || text.contains("pillar")) {
			return text.contains("square") ? PROPS.get("pillar_square") : PROPS.get("pillar_round");
		}
		if (text.contains("rubble") || text.contains("debris") || text.contains("rocks")) {
			return PROPS.get("rubble");
		}
		if (text.contains("pit") || text.contains("chasm") || text.contains("shaft")) {
			return PROPS.get("pit");
		}
		if (text.contains("weapon") || text.contains("rack") || text.contains("armory")) {
			return PROPS.get("weapon_rack");
		}
		if (text.contains("anvil") || text.contains("forge")) {
			return PROPS.get("anvil");
		}
		if (text.contains("cauldron") || text.contains("kettle")) {
			return PROPS.get("cauldron");
		}
		if (text.contains("pine") || text.contains("spruce") || text.contains("fir") || text.contains("conifer")) {
			return PROPS.get("pine_tree");
		}
		if (text.contains("stump")) {
			return PROPS.get("stump");
		}
		if (text.contains("tree") || text.contains("oak") || text.contains("willow")) {
			return PROPS.get("tree");
		}
		if (text.contains("bush") || text.contains("shrub") || text.contains("hedge") || text.contains("bramble")) {
			return PROPS.get("bush");
		}
		if (text.contains("boulder") || text.contains("rock") || text.contains("stone pile")) {
			return PROPS.get("boulder");
		}
		if (text.contains("wagon") || text.contains("cart") || text.contains("carriage")) {
			return PROPS.get("wagon");
		}
		if (text.contains("tent") || text.contains("pavilion")) {
			return PROPS.get("tent");
		}
		if (text.contains("puddle") || text.contains("pool")) {
			return PROPS.get("puddle");
		}
		if (text.contains("crack") || text.contains("fissure")) {
			return PROPS.get("cracks");
		}
		if (text.contains("scorch") || text.contains("burn") || text.contains("ash")) {
			return PROPS.get("scorch_mark");
		}
		if (text.contains("blood")) {
			return PROPS.get("bloodstain");
		}
		if (text.contains("banner") || text.contains("flag")) {
			return PROPS.get("banner");
		}
		return null;
	}

	public static LightPreset lightPreset(String name) {
		if (name == null || name.isBlank()) {
			return LIGHTS.get("TORCH");
		}
		return LIGHTS.getOrDefault(name.trim().toUpperCase(Locale.ROOT), LIGHTS.get("TORCH"));
	}

	public static boolean isLightEmitting(Map<String, Object> item) {
		if (item == null) {
			return false;
		}
		String state = String.valueOf(item.getOrDefault("state", "INTACT")).toUpperCase(Locale.ROOT);
		if ("DESTROYED".equals(state) || "REMOVED".equals(state) || "EXTINGUISHED".equals(state)
				|| "OFF".equals(state)) {
			return false;
		}
		if (item.get("light_radius_ft") instanceof Number n && n.doubleValue() > 0) {
			return true;
		}
		PropSpec spec = resolve(item);
		return spec != null && spec.lightRadiusFt() > 0;
	}

	public static Integer parseColor(Object value) {
		if (value == null) {
			return null;
		}
		String raw = value.toString().trim();
		if (raw.isEmpty()) {
			return null;
		}
		String upper = raw.toUpperCase(Locale.ROOT);
		Integer named = switch (upper) {
		case "OAK", "WOOD", "WARM_WOOD" -> 0xff9a6e42;
		case "DARK_WOOD", "MAHOGANY", "EBONY" -> 0xff5c3b23;
		case "PINE", "PALE_WOOD", "BIRCH" -> 0xffc29b67;
		case "STONE", "GREY", "GRAY" -> 0xff8d8d84;
		case "DARK_STONE", "SLATE" -> 0xff5a5d60;
		case "MARBLE", "IVORY", "CREAM", "WHITE" -> 0xffdfd8c8;
		case "IRON", "STEEL" -> 0xff585e66;
		case "BRASS", "GOLD", "AMBER" -> 0xffcfa042;
		case "COPPER", "BRONZE" -> 0xffb36c3e;
		case "CRIMSON", "RED", "BURGUNDY" -> 0xff963434;
		case "BLUE", "NAVY", "INDIGO" -> 0xff355780;
		case "GREEN", "EMERALD", "FOREST" -> 0xff3e6b43;
		case "PURPLE", "VIOLET", "ARCANE" -> 0xff7b52ab;
		case "TEAL", "CYAN", "ICE" -> 0xff4b97a8;
		case "WARM", "TORCH", "FIRE", "ORANGE" -> 0xffffa94d;
		case "COOL", "MOONLIGHT" -> 0xff8cb4e8;
		case "SUNLIGHT", "DAYLIGHT" -> 0xffffefc4;
		default -> null;
		};
		if (named != null) {
			return named;
		}
		String hex = raw.startsWith("#") ? raw.substring(1)
				: raw.startsWith("0x") || raw.startsWith("0X") ? raw.substring(2) : raw;
		if (hex.matches("[0-9a-fA-F]{6}")) {
			return 0xff000000 | Integer.parseInt(hex, 16);
		}
		return null;
	}

	/** Compact catalog surfaced through rpg://maps/catalog for AI authoring. */
	public static Map<String, Object> catalog() {
		var propsByCategory = new LinkedHashMap<String, List<Map<String, Object>>>();
		for (PropSpec spec : PROPS.values()) {
			var entry = new LinkedHashMap<String, Object>();
			entry.put("prop", spec.id());
			entry.put("default_size_ft", List.of(spec.defaultWidthFt(), spec.defaultHeightFt()));
			if (spec.flat()) {
				entry.put("flat", true);
			}
			if (spec.lightRadiusFt() > 0) {
				entry.put("light_radius_ft", spec.lightRadiusFt());
			}
			entry.put("description", spec.description());
			propsByCategory.computeIfAbsent(spec.category(), ignored -> new ArrayList<>()).add(entry);
		}
		var lightList = new ArrayList<Map<String, Object>>();
		for (LightPreset preset : LIGHTS.values()) {
			lightList.add(Map.of("preset", preset.id(), "default_radius_ft", preset.defaultRadiusFt(), "description",
					preset.description()));
		}
		var result = new LinkedHashMap<String, Object>();
		result.put("materials", MATERIALS);
		result.put("props", propsByCategory);
		result.put("light_presets", lightList);
		result.put("prop_options", Map.of("rotation_deg", "Orientation in degrees (0-360, clockwise from north).",
				"tint", "Optional named tint (OAK, DARK_WOOD, CRIMSON, BLUE, GREEN, GOLD, MARBLE, ARCANE) or #RRGGBB.",
				"seats", "Optional integer chair count on table_round or table_rect (auto-places tucked chairs).",
				"seating", "Optional 'chairs' or 'benches' on table_rect.", "clutter",
				"Optional true to draw small tabletop accents (mugs/plates/parchment)."));
		result.put("layer_guidance", Map.of("objects",
				"Tactical/interactive furnishings with collision, cover, state or canonical loot (bar counters, heavy tables, pillars, chests, boulders).",
				"decor",
				"Non-blocking visual set dressing that never impedes movement or clutters combat summaries (rugs, tucked chairs, wall sconces, floor cracks, bushes, banners).",
				"lights",
				"Purely visual mood light pools ({key, position_ft:[x,y], level, preset|color, radius_ft, intensity}). Mechanical vision still uses map/room illumination."));
		return result;
	}

	/** Concise tactical brief injected into SCENE/DUNGEON preparations. */
	public static Map<String, Object> tacticalBrief() {
		return Map.of("purpose",
				"Author playable geometry first, then run a final graphics and mood pass before publishing so the map reads clearly and feels alive.",
				"workflow",
				List.of("1. Establish room shapes, doors, lighting level (BRIGHT/DIM/DARK), tactical cover/objects, challenges and loot.",
						"2. Final graphics pass: pick fitting room materials (WOOD, STONE, ROUGH_STONE, DAMP_STONE, COBBLESTONE, DECORATIVE_TILE, CARPET, EARTH, GRASS), assign recognizable prop names to furnishings, add non-blocking decor (rugs, chairs, rubble, bushes, floor wear) and mood lights (hearth, brazier, torch_sconce, campfire, lantern or explicit lights).",
						"3. Optionally call render_map on this preparation:N with audience GM to inspect the visual layout before calling publish_map_preparation."),
				"common_props",
				Map.of("interior",
						List.of("table_rect (supports seating:'chairs'|'benches', seats:N, clutter:true)",
								"table_round (supports seats:N, clutter:true)", "chair", "stool", "bench",
								"bar_counter", "hearth", "bed", "bookshelf", "rug", "crate", "barrel", "chest"),
						"dungeon",
						List.of("pillar_round", "pillar_square", "altar", "brazier", "torch_sconce", "lantern",
								"statue", "fountain", "sarcophagus", "rubble", "pit", "weapon_rack", "anvil",
								"cauldron"),
						"outdoor",
						List.of("tree", "pine_tree", "bush", "boulder", "stump", "campfire", "tent", "wagon"),
						"ground_detail",
						List.of("carpet_runner", "puddle", "cracks", "scorch_mark", "bloodstain", "banner")),
				"catalog_resource", "rpg://maps/catalog");
	}

	public static List<String> materials() {
		return MATERIALS;
	}

	private static String normalize(String raw) {
		return raw.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
	}

	private static Map<String, PropSpec> buildProps() {
		var map = new LinkedHashMap<String, PropSpec>();
		add(map, new PropSpec("table_rect", "interior", 6.0, 3.0, false, false, 0, 0,
				"Rectangular wooden table; supports seats:N, seating:'chairs'|'benches', clutter:true.",
				List.of("rectangular_wooden_table", "rectangular_table", "wooden_table", "table", "banquet_table",
						"dining_table", "desk", "workbench")));
		add(map, new PropSpec("table_round", "interior", 4.0, 4.0, false, false, 0, 0,
				"Round wooden table; supports seats:N and clutter:true.",
				List.of("round_wooden_table", "round_table", "tavern_table", "circular_table")));
		add(map, new PropSpec("chair", "interior", 2.0, 2.0, false, true, 0, 0,
				"Wooden chair with backrest (orient with rotation_deg).",
				List.of("wooden_chair", "armchair", "throne")));
		add(map, new PropSpec("stool", "interior", 1.5, 1.5, false, true, 0, 0, "Round wooden bar/tavern stool.",
				List.of("wooden_stool", "bar_stool", "barstool")));
		add(map, new PropSpec("bench", "interior", 5.0, 1.5, false, false, 0, 0, "Long wooden bench or pew.",
				List.of("wooden_bench", "pew")));
		add(map, new PropSpec("bar_counter", "interior", 10.0, 3.0, false, false, 0, 0,
				"Heavy timber tavern bar or shop counter.", List.of("bar", "counter", "tavern_bar", "shop_counter")));
		add(map, new PropSpec("hearth", "interior", 6.0, 3.0, false, false, 18.0, 0xffff9e44,
				"Stone hearth/fireplace with glowing fire and warm ambient pool.",
				List.of("fireplace", "stone_hearth", "forge_hearth")));
		add(map, new PropSpec("bed", "interior", 3.5, 6.5, false, false, 0, 0,
				"Wooden bed with pillow and tinted blanket.", List.of("cot", "bunk", "double_bed")));
		add(map, new PropSpec("bookshelf", "interior", 5.0, 1.5, false, false, 0, 0,
				"Timber shelf lined with colored books.", List.of("bookcase", "shelf", "shelves", "cabinet")));
		add(map, new PropSpec("rug", "interior", 8.0, 5.0, true, true, 0, 0,
				"Woven floor rug with patterned border and fringe.", List.of("carpet", "woven_rug", "floor_rug")));
		add(map, new PropSpec("crate", "interior", 3.0, 3.0, false, false, 0, 0, "Cross-braced wooden cargo crate.",
				List.of("wooden_crate", "box", "supply_crate")));
		add(map, new PropSpec("barrel", "interior", 2.5, 2.5, false, false, 0, 0, "Iron-hooped oak barrel/cask.",
				List.of("cask", "keg", "wooden_barrel")));
		add(map, new PropSpec("chest", "interior", 3.0, 2.0, false, false, 0, 0,
				"Iron-banded treasure chest (shows OPEN/LOCKED/DESTROYED state).",
				List.of("treasure_chest", "strongbox", "coffer", "wooden_chest")));

		add(map, new PropSpec("pillar_round", "dungeon", 3.0, 3.0, false, false, 0, 0,
				"Carved cylindrical stone column with drop shadow.", List.of("column", "round_pillar", "pillar")));
		add(map, new PropSpec("pillar_square", "dungeon", 3.0, 3.0, false, false, 0, 0,
				"Hewn square stone pillar with chamfered bevels.", List.of("square_pillar", "stone_pillar")));
		add(map, new PropSpec("altar", "dungeon", 6.0, 3.0, false, false, 0, 0,
				"Stepped stone altar with draped cloth.", List.of("shrine", "stone_altar")));
		add(map, new PropSpec("brazier", "dungeon", 2.5, 2.5, false, false, 16.0, 0xffff9638,
				"Iron fire brazier with embers and warm glow.", List.of("fire_brazier", "iron_brazier")));
		add(map, new PropSpec("torch_sconce", "dungeon", 1.2, 1.2, false, true, 14.0, 0xffffa648,
				"Mounted torch sconce casting warm firelight.", List.of("torch", "sconce", "wall_torch")));
		add(map, new PropSpec("lantern", "dungeon", 1.2, 1.2, false, true, 12.0, 0xffffcc66,
				"Brass hanging or standing lantern with golden glow.", List.of("lamp", "candle", "candelabra")));
		add(map, new PropSpec("statue", "dungeon", 3.5, 3.5, false, false, 0, 0,
				"Carved stone statue on a square plinth.", List.of("stone_statue", "idol")));
		add(map, new PropSpec("fountain", "dungeon", 6.0, 6.0, false, false, 0, 0,
				"Circular stone water fountain with central tier.", List.of("stone_fountain", "well", "water_basin")));
		add(map, new PropSpec("sarcophagus", "dungeon", 3.5, 7.0, false, false, 0, 0,
				"Carved stone tomb/sarcophagus lid.", List.of("tomb", "coffin")));
		add(map, new PropSpec("rubble", "dungeon", 4.5, 4.0, true, false, 0, 0,
				"Cluster of angular fallen masonry and dust.", List.of("debris", "fallen_stone", "rock_pile")));
		add(map, new PropSpec("pit", "dungeon", 5.0, 5.0, true, false, 0, 0,
				"Recessed dark floor shaft with crumbled stone lip.", List.of("pit_trap", "floor_pit", "shaft")));
		add(map, new PropSpec("weapon_rack", "dungeon", 4.0, 1.5, false, false, 0, 0,
				"Timber rack holding blades and polearms.", List.of("armor_rack", "weapons_rack")));
		add(map, new PropSpec("anvil", "dungeon", 3.0, 2.0, false, false, 0, 0,
				"Iron smithing anvil mounted on a timber block.", List.of("smith_anvil", "forge_anvil")));
		add(map, new PropSpec("cauldron", "dungeon", 2.5, 2.5, false, false, 0, 0,
				"Cast-iron cauldron with tinted bubbling brew.", List.of("kettle", "alchemical_vat")));

		add(map, new PropSpec("tree", "outdoor", 9.0, 9.0, false, false, 0, 0,
				"Broadleaf tree canopy with dappled foliage and ground shadow.",
				List.of("broadleaf_tree", "oak_tree", "forest_tree")));
		add(map, new PropSpec("pine_tree", "outdoor", 7.5, 7.5, false, false, 0, 0,
				"Layered evergreen conifer canopy with ground shadow.", List.of("pine", "evergreen", "conifer")));
		add(map, new PropSpec("bush", "outdoor", 4.0, 4.0, false, true, 0, 0,
				"Leafy undergrowth shrub with soft shadow.", List.of("shrub", "hedge", "bramble", "foliage")));
		add(map, new PropSpec("boulder", "outdoor", 4.5, 4.0, false, false, 0, 0,
				"Faceted natural boulder with sunlit ridge and shadow.", List.of("rock", "large_rock", "stone")));
		add(map, new PropSpec("stump", "outdoor", 2.5, 2.5, false, true, 0, 0,
				"Cut tree stump showing bark and growth rings.", List.of("tree_stump", "log")));
		add(map, new PropSpec("campfire", "outdoor", 3.5, 3.5, false, false, 18.0, 0xffff9638,
				"Stone-ringed campfire with charred logs and warm firelight.", List.of("firepit", "bonfire")));
		add(map, new PropSpec("tent", "outdoor", 7.0, 5.0, false, false, 0, 0, "Pitched canvas ridge tent (tintable).",
				List.of("camp_tent", "pavilion")));
		add(map, new PropSpec("wagon", "outdoor", 10.0, 5.0, false, false, 0, 0,
				"Wooden supply wagon/cart with spoked wheels and shafts.",
				List.of("cart", "carriage", "covered_wagon")));

		add(map, new PropSpec("carpet_runner", "ground_detail", 10.0, 3.5, true, true, 0, 0,
				"Long woven hall runner with gold edge trim.", List.of("runner", "hall_runner")));
		add(map, new PropSpec("puddle", "ground_detail", 4.0, 3.0, true, true, 0, 0,
				"Irregular reflective ground puddle.", List.of("water_puddle", "spill")));
		add(map, new PropSpec("cracks", "ground_detail", 4.0, 4.0, true, true, 0, 0,
				"Branching floor cracks for aged stone or ruins.", List.of("floor_cracks", "fissure")));
		add(map, new PropSpec("scorch_mark", "ground_detail", 5.0, 5.0, true, true, 0, 0,
				"Charred soot radial mark on floor or ground.", List.of("scorch", "burn_mark", "soot")));
		add(map, new PropSpec("bloodstain", "ground_detail", 3.0, 3.0, true, true, 0, 0, "Organic crimson floor stain.",
				List.of("blood", "splatter")));
		add(map, new PropSpec("banner", "ground_detail", 3.5, 1.5, true, true, 0, 0,
				"Tinted wall or floor heraldic banner.", List.of("tapestry", "flag")));
		add(map, new PropSpec("trap_plate", "ground_detail", 4.0, 4.0, true, false, 0, 0,
				"Visible mechanical pressure plate or floor trap.", List.of("trap", "pressure_plate", "tripwire")));
		return Map.copyOf(map);
	}

	private static void add(Map<String, PropSpec> map, PropSpec spec) {
		map.put(spec.id(), spec);
	}

	private static Map<String, String> buildAliases(Map<String, PropSpec> props) {
		var aliases = new LinkedHashMap<String, String>();
		for (PropSpec spec : props.values()) {
			aliases.put(spec.id(), spec.id());
			for (String alias : spec.aliases()) {
				aliases.put(normalize(alias), spec.id());
			}
		}
		return Map.copyOf(aliases);
	}

	private static Map<String, LightPreset> buildLights() {
		var map = new LinkedHashMap<String, LightPreset>();
		map.put("TORCH", new LightPreset("TORCH", 16.0, 0xffffa648, 0.72, "Warm flickering torchlight."));
		map.put("BRAZIER", new LightPreset("BRAZIER", 18.0, 0xffff9238, 0.78, "Deep orange-amber coal glow."));
		map.put("HEARTH", new LightPreset("HEARTH", 22.0, 0xffff9d42, 0.80, "Broad cozy tavern fireplace warmth."));
		map.put("CAMPFIRE", new LightPreset("CAMPFIRE", 20.0, 0xffff9236, 0.80, "Outdoor warm orange firelight."));
		map.put("LANTERN", new LightPreset("LANTERN", 14.0, 0xffffcf70, 0.65, "Steady golden brass-lantern pool."));
		map.put("CANDLE", new LightPreset("CANDLE", 7.0, 0xffffd88a, 0.50, "Soft intimate candlelight."));
		map.put("ARCANE", new LightPreset("ARCANE", 14.0, 0xff9b78ff, 0.68, "Eerie violet-blue magical glow."));
		map.put("SUNLIGHT", new LightPreset("SUNLIGHT", 18.0, 0xfffff2cc, 0.55, "Bright window or skylight shaft."));
		map.put("MOONLIGHT", new LightPreset("MOONLIGHT", 18.0, 0xff8cb8ef, 0.45, "Cool silver-blue moonlight wash."));
		return Map.copyOf(map);
	}
}
