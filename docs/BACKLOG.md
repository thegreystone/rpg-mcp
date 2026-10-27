# Backlog

The persistent map, grid combat, regional atlas, finite patrol and interruptible travel baseline is
implemented in this worktree. See the [delivery plan](MAP_IMPLEMENTATION_PLAN.md),
[tool workflow](MAP_USAGE.md) and [verification report](MAP_PLAYTEST_REPORT.md). The broad items below
retain longer-term experience targets; their unchecked state refers to remaining extensions.

## Dungeon maps

- [ ] Infer published actor footprints from creature size, with the same floor/occupancy validation as
  explicit footprints. Published actors currently default to 5 ft, while fresh encounter deployment
  derives creature size. Authors can set `footprint_ft` explicitly; the Run7 Ogre history test uses 10 ft.

- [ ] **Dungeon map experience extensions** (baseline implemented; remaining extensions unscheduled).

  Add campaign/location-owned dungeon geometry, 5 ft or 10 ft grid rendering, reusable default pieces,
  and a shared tactical scene model required for all combat, including roads, inn steps and streets.
  Travel stays graph-based; local scenes inherit/persist physical anchors and surroundings, expand
  without restarting combat, and return to actual final route/off-road positions afterward. Support
  custom pieces and rearrangement. Reveal only currently visible or previously explored space, with
  separate discovery for secrets and filtered player resources. Ship default materials and room themes;
  allow the model to generate or derive custom textures that are saved as reusable campaign assets.
  Layout, exploration and object state participate in checkpoint restoration and fresh-context recall.
  Save semantic dungeon blueprints/plot constraints early; lay out most playable tiles and meaningful
  contents near approach before entry, with assisted generation or complete model-authored proposals.
  Preserve room counts, levels, attributes, secret connections and loot relationships when resolving
  room/object grid positions. Scale from a fully prepared gang house to linked districts/levels
  of an underground city, retaining coherent story, inhabitants and routes. Persist battle damage,
  destroyed/moved furnishings, breaches and repairs as normal world changes across all map views.
  Prepare placed loot/containers, access conditions, guard posts, patrol routes/schedules and alarm or
  reinforcement responses. Tie activity to campaign time and existing inventory/actor identities;
  persist discoveries, depletion and resolved outcomes across searches, return visits and reloads.
  Encounters can span multiple rooms and include unseen monsters/NPCs. Support conversational placement
  of the player and directed party members, with character markers in graphical views when available;
  encounter participation and marker placement respect the player view's knowledge and visibility.
  The tooling renders actual map images, chooses texture/output pixel sizes, imports path-based tile
  overrides, and supports custom geometry that clips/cuts groups of pieces. Rendering, movement and
  visibility share the resulting spatial structure, including explicit wall-breach and floor-void semantics.

  The [dungeon map plan](DUNGEON_MAPS.md) defines the proposed model, delivery increments and acceptance
  scenarios. Tactical encounters and later freeform settlement maps build on the same location links.

## Regional maps

- [ ] Connect displaced regional labels to their symbols, or otherwise make their association clear.
  Run6 visual review found a mine label could look attached to the neighboring site while canonical
  and rendered marker coordinates were correct. Include crowded settlements and party overlays in QA.

- [ ] **Regional map experience extensions** (baseline implemented; remaining extensions unscheduled).

  The Campaign/World Engine creatively generates the initial regional map during campaign setup,
  covering the opening location and a few key cities/villages with routes and major terrain. Validate
  and commit the accepted region with initial adventure state, then render it for the start of play.
  Expand into equally sized adjoining regional sheets when the player leaves the area, sharing the
  initial atlas style and continuing roads, waterways and terrain across every established boundary.
  Persist each region and its atlas coordinates for return visits and combined views.
  Supply the LLM with the [creative authoring brief](WORLD_MAP_GENERATION.md), including coast/ocean/island
  options, geography-driven settlements/travel and opening positions suited to the campaign. Expansion
  supports any direction and land/water journeys; neighboring regions can vary in content and density.
  Prefer a coastal initial region where appropriate, while allowing inland openings; persist the chosen
  geographic/climate context for coherent narration and further world building.
  Anchor regional atlases to a roughly Earth-sized world and latitude-aware climate, defaulting an
  unspecified ordinary opening to southern-France latitude (about 43.5 degrees north). Preserve local
  sheet addresses, climate continuity and established legacy facts as world coverage expands.
  Establish opening cities/towns/villages and key roads during setup, supporting the narrative and
  normally extending the main road beyond one or more land edges (or linking outward by sea for a
  wholly enclosed island). Hills, ridges and mountains constrain routes; use sensible bypasses or
  justified passes/crossings, with geometry, travel properties and illustration in agreement.
  On legacy-campaign resume, analyze the location graph and saved geographic evidence, complete missing
  maps and persist the accepted atlas. Reuse known identities/geometry, retain uncertainty, preserve
  current gameplay/checkpoints, and make completion resumable and idempotent.
  Persist winding paths, terrain boundaries and semantic topology independently of artwork, so style
  and display-detail changes reuse the same geography. Start with vector geometry; optional aligned
  terrain/elevation rasters keep land cover, relief and water properties independently expressible.
  Prioritize distances between settlements, roadside taverns/inns and other stops, connected route legs,
  and structural forest/lake/river geography. Keep road distance separate from direct distance; crossings,
  terrain stretches and journey progress support route queries and consistent travel. Render the saved
  geography in text and illustrated views. Support player-chosen cross-country journeys toward named
  geographic features, with persistent progress and local detail generated within established regional
  geography. Maintain a living map of new sites, sourced reports, approximate positions and later
  discoveries; keep party knowledge distinct from current world state and retain location identities
  as information is refined. Add/edit POIs at specified map coordinates and re-render saved geography
  with the updated markers/labels. Add story/world-aware travel encounter risk, authored or random
  placement at actual route positions, persistent checks/exposure and grid-based spatial interruptions.
  See the [regional travel structure](MAP_FORMAT.md#regional-travel-structure)
  and its acceptance examples.

## Narrative direction

- [x] **Minimum Director-to-GM/DM handoff** (recorded and implemented 2026-10-24 for 0.2.0).

  Director reviews publish actionable `GM_ONLY` opportunities through existing story beats. Bootstrap and
  `get_context SCENE` include a bounded `gm_brief` and stable references; `get_context STORY_BEAT` retrieves
  full details without exposing `DIRECTOR_ONLY` planning. Optional briefing fields preserve existing campaigns.
  The guide and tool descriptions make publication, refresh, manual condition checks and open player choices explicit.

  Implemented minimum:

  - A Director review can persist a hook, location, actors and intentions, availability conditions, clues,
    illustrative approaches, consequences and developments if ignored as ordinary story-beat payload fields.
  - Compact briefings have per-beat and shared text limits, retain every reference, and flag clipped guidance.
    Legacy string summaries or descriptions supply a fallback; full payloads remain available on demand.
  - Fresh-context and visibility regression coverage verifies usable published guidance, scoped retrieval,
    private planning isolation and compatibility with existing beats.

- [ ] **Broader narrative opportunity lifecycle** (deferred; unscheduled).

  The minimum handoff supplies guidance; the GM still interprets conditions and changes beats explicitly.
  Automatic condition evaluation, opportunity ranking, scheduling and private-plan promotion remain deferred.
  Broader behavioral coverage should exercise unexpected approaches and ignored hooks through later Director
  reviews, and confirm that changed circumstances produce appropriate revised or superseded opportunities.

  Related: [Director protocol](MCP_PROTOCOL.md#18-narrative-director),
  [GM guide](../src/main/resources/protocol/guide.md),
  [visibility regression tests](../src/test/java/se/hirt/mcp/rpg/NarrativeVisibilityTest.java).
