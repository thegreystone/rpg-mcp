# RPG MCP — Maps & World Building Guide (topic: MAPS)

Travel uses the saved location graph and geographic shapes; every new combat uses a local grid. Show and narrate
only **`PLAYER`** views — GM authoring, challenges, concealed traps and patrols stay private.

## Staged Authoring (`map_preparation`)

1. Start with `map_preparation:BEGIN` (`kind: DUNGEON|SCENE|REGION`, `proposal`), revise keyed batches with
   `map_preparation:REVISE` (`expected_revision?`, `changes`), check readiness with `map_preparation:VALIDATE`, and
   publish with `map_preparation:PUBLISH`.
2. For dungeons, first accept a semantic blueprint (`proposal.stage: BLUEPRINT`: room counts, levels, altar/secret
   entry/boss/treasure commitments), then realize coherent playable geometry near approach. `generate_layout: true`
   is an optional fitting helper, not a mandatory creative template. Accepted commitments and later damage/looting
   survive realization and fresh conversations. Drafts are never live loot or opponents.
3. **Geometry & lighting:** Default new corridors to 10 ft clear walkable width (5 ft passages and wider halls are
   explicit choices; doorway width is independent so a 5 ft doorway can choke a 10 ft corridor). Keep the 5 ft
   placement lattice and existing saved geometry intact. Author `footprint_ft` explicitly for larger preplaced
   creatures (10 Large, 15 Huge, 20 Gargantuan; default 5 ft). Dungeons default to `DARK`; author `BRIGHT` or `DIM`
   explicitly when lit (descriptions of torches/windows alone do not create mechanical light).
4. **Challenges, traps & balance:** Preparation fills missing lock/discovery/disarm `challenges` with editable DC 15
   guidance; override each feature's `difficulty` and check approach to suit the site (`force_open` is separate).
   Declared `TRAP` objects are concealed until adjudicated discovery — author `known_hazard: true` for apparent
   hazards or reveal with `map_play:UPDATE_OBJECT` (`concealed: false`) after a `roll_check:CHECK`. An authored
   secret entrance owns its discovery check. Structural readiness does not certify balance: review bosses, defenses,
   finite patrols, keys and keyed treasure before publishing; resolve item/creature definitions first.
5. **Final graphics & mood pass (before publishing a SCENE or DUNGEON):** Make a dedicated visual dressing pass so
   the map feels alive and atmospheric rather than bare boxes:
   - Assign fitting room `material` values (`WOOD`, `DARK_WOOD`, `STONE`, `ROUGH_STONE`, `DAMP_STONE`,
     `COBBLESTONE`, `DECORATIVE_TILE`, `CARPET`, `EARTH`, `MUD`, `SAND`, `GRASS`, `FOREST`, `WATER`, `ICE`, `SNOW`).
   - Assign recognizable `prop` names from `rpg://maps/catalog` to blocking/cover `objects` (`bar_counter`,
     `table_rect` with `seating: 'benches'|'chairs'` or `seats: N`, `table_round` with `seats: N`, `hearth`,
     `pillar_round`, `pillar_square`, `chest`, `altar`, `boulder`, `wagon`, `tent`) and add non-blocking `decor`
     (`rug`, `carpet_runner`, `chair`, `stool`, `bookshelf`, `barrel`, `crate`, `brazier`, `torch_sconce`, `lantern`,
     `tree`, `pine_tree`, `bush`, `rubble`, `puddle`, `cracks`, `scorch_mark`, `bloodstain`, `banner`, `campfire`)
     plus mood `lights` (`{key, position_ft, level, preset|color, radius_ft, intensity}`).
   - Call `render_map` on `preparation:N` with `audience: GM` to inspect and refine the draft before publishing.
   - Outside combat, `map_play:CORRECT_GEOMETRY` can also patch `decor`, `lights` and room `material` on an existing
     or incidental board.

## Spatial Play (`map_play` & `render_map`)

- **Retrieval:** Use `map_play:CONTEXT` (defaults to bounded `SUMMARY`; pass `detail: FULL` only when needed;
  preparations/atlas require `audience: GM`) and `map_play:FIND` (marks `current_board` and warns on
  `other_published_realizations`). Incidental sibling rooms never supersede published preparations.
- **Placement & movement:** Coordinates (`x_ft`, `y_ft`) are southwest footprint origins on the 5 ft lattice.
  Bootstrap's `current_board` shows the controlled actor's board; unplaced arrivals into published coverage supply
  `placement_hint` (`placement_required: true`, listing alternatives if ambiguous) — inspect before placing with
  `map_play:PLACE_ACTORS`. Use `map_play:MOVE_ACTOR` and `map_play:USE_TRANSITION` in exploration; use
  `encounter:ACTION` `MOVE` (including `{portal: key}`) in combat. `map_play:PREVIEW_ACTION` costs nothing and uses
  only known geometry for `PLAYER`. Corpses stay marked but do not block movement. An audited same-place
  `apply_gm_override` `SET_LOCATION` with `effect.clear_map_position: true` can release a stale figurine outside
  active combat.
- **Sight & sound:** `map_play:OBSERVE` saves the union of party sight (remembered out-of-sight objects are marked
  `last_known`). Enemy decisions use their own `map_play:PERCEPTION`; hearing cues use `map_play:EMIT_SOUND`
  (`actor` or explicit coordinates) — an encounter roster never grants omniscience.
- **Live changes & geometry patches:** Record gameplay damage, locks, broken furniture or trap reveals with
  `map_play:UPDATE_OBJECT` and a reason (inventory transfers remain authoritative for taking loot). Repair mistaken
  published geometry or visual dressing outside combat with `map_play:CORRECT_GEOMETRY` (`expected_revision`,
  `patch`, `reason`). During combat only ambient `illumination` / keyed `rooms:[{key, illumination}]` patches are
  allowed (writes a `GM_ONLY` `MAP_ILLUMINATION_CHANGED` event without spending an Action).
- **Rendering, textures & bundles:** `render_map` returns PNG + text + `markers.move_origin_ft` (use
  `include_image: false` for text-only clients; `options.technical_text` overrides text presentation). Import custom
  PNG textures with `map_preparation:IMPORT_ASSET` (copied once; use `asset:N` as material or `material_overrides`).
  `map_preparation:EXPORT_BUNDLE` / `IMPORT_BUNDLE` package/stage GM snapshots with explicit `source_ref_mappings`
  (never live loot/occupants, never a player handout or campaign checkpoint).

## World Building & Atlas

- Author `adventure.world_map` during setup or review the staged `AUTHORING_REQUIRED` REGION preparation, supply
  creative winding routes/terrain/coastlines and publish. Ordinary latitude defaults near 43.5° N; climate choices
  can set a fitting latitude. Atlas addresses `atlas:N[x,y]` use east-positive X, north-positive Y and a fixed
  origin; neighboring REGION preparations supply atlas ref/sheet coordinates and inherit saved style and neighbor
  constraints. Restyle saved geography rather than regenerating it.
- Add exact `WORLD_POI` markers or scoped `NOTE`/`REPORT` claims via `map_play:ANNOTATE` (rumors stay approximate).
- On legacy resume, `world_maps` may return a staged preparation: review sourced graph/narrative constraints,
  resolve required gaps, set `review_required: false` and publish without changing party time or knowledge.
