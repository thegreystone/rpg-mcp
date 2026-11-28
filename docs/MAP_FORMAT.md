# Map format and outdoor cartography

Status: shared design and broader format targets. Dungeon/scene authoring and regional atlas support
are implemented; this document also describes extensions beyond that baseline. See
[MAP_USAGE.md](MAP_USAGE.md) for the accepted tool payloads and [BACKLOG.md](BACKLOG.md) for remaining
extensions. Regional publication integrates
with campaign setup and sourced legacy completion. Detailed settlement helpers remain a later extension.
See the [player experience review](DESIGN.md#204-player-experience-priorities) for proposed priorities
around discovery, journey pacing, readable interaction and the party's remembered places.

## Representations

Use a versioned JSON map document combining the location/route graph with a spatial scene as the
interchange representation of maps stored in campaign SQLite. The document carries map/campaign/location
references, schema version, revision, coordinate conventions, levels, material/definition references,
routes and geometric objects. References remain stable across views.

The world-map layer retains explicit geographic features alongside the graph: coastlines, rivers,
lakes, forest/field boundaries, hills/mountains, passes and winding roads have saved positions, shapes
and relevant properties. The model can creatively author that landscape for a useful and attractive
world map. Travel connections reference its features, while local grids refine the same terrain at
encounter scale. One road identity can link its rendered curve, graph legs and tactical segments;
rendering does not have to infer the world's landscape from graph connectivity alone.

Render PNG images for ordinary image-capable clients. Optional SVG exports can support vector viewing
and printing. A portable `.rpgmap.zip` bundle contains a manifest, map JSON, required custom definitions
and artwork, and optional previews. Imports resolve/remap campaign-owned references; saved maps use
registered asset references rather than depending on external source paths.

Keep authored layout, changing play state and party knowledge distinct but connected through stable
references. A reusable map template includes its initial setup; a campaign snapshot additionally carries
live character/object state and exploration. Player representations are filtered before delivery.

Dungeon preparation can first save a semantic blueprint with room counts/levels, attributes, important
connections and loot relationships, leaving tile coordinates unresolved. Near approach, materialize
the coherent playable layout from those constraints while preserving semantic identities; see
[blueprints and tile layout](DUNGEON_MAPS.md#blueprint-first-tile-layout-near-approach). A room/object's
logical placement can precede its physical grid anchor. Subsequent live changes survive refinement.

## Stable geography and alternative renderings

Use a vector-first hybrid model: persist accepted geographic shapes and their meaning separately from
their illustration. Restyling, changing image resolution or requesting another level of visual detail
reads this saved geography rather than asking a model to recreate it. A seed and generation prompt
are useful provenance, but cannot replace the resolved map; generator/model versions can change.

| Feature | Canonical representation |
| --- | --- |
| Roads, streets and rivers | Complete winding paths, with physical widths and explicit junctions, crossings and flow where relevant. |
| Lakes, coasts, islands and terrain boundaries | Polygons/multipolygons with holes, in shared physical coordinates. |
| Forest, grassland, cultivated land and other cover | Initially labeled areas; optional categorical terrain rasters for painting or larger datasets. |
| Hills, mountains, ridges, valleys and passes | Relief areas and structural paths/anchors; optional numeric elevation fields for finer terrain. |
| Settlements and landmarks | Stable points or footprints linked to campaign locations. |
| Colors, textures, symbols, labels and line treatment | Separate versioned style profiles and registered artwork. |

The renderer draws structural features from their saved geometry. Generated textures and symbols are
aligned/clipped to that geometry, so changing the artwork source does not relocate a river or road.

### Winding paths and topology

Save each road or river's accepted bends, not just its endpoints or a description such as "a winding
road." The first implementation can use detailed polylines: a sequence of physical coordinates is
enough to preserve a natural route. If authoring generates curves procedurally, save the resolved
path. Explicit curve control points are an alternative when their interpolation rules are defined and
versioned. Do not fit a new unconstrained curve on each render: smoothing can overshoot a bank, cross
a ridge or invent a junction.

Keep feature identities and connectivity alongside geometry. A confluence, ford, bridge, junction or
pass has an explicit shared anchor and appropriate properties. Overlapping drawn lines alone do not
establish connectivity. Distinguish physical widths and any width profile from the ink stroke chosen
to keep a river or road legible at a given scale. Style changes do not alter traversal or established
route distances; existing approximate distance records retain their stated precision.

Atlas sheets are views/clips of shared world geometry. Crossing paths and terrain retain one feature
identity and agree in position, shape and direction at sheet seams. Regional-to-local detail inherits
the parent's anchors and geometry constraints rather than independently redrawing a road or shoreline.

### Terrain paint layers and elevation

A lossless bitmap can be a useful terrain-authoring/control layer, but a single exclusive RGB palette
is insufficient as the whole map. A forest can cover hills, and a field can sit high above sea level.
Keep land cover, relief/elevation and water properties independently expressible. Freshwater/saltwater
and other meaningful water properties belong to water features or fields, not to their display color.

Optional rasters store categorical IDs or numeric values, with a versioned category definition and
explicit physical origin, axes, bounds, cell size, dimensions and unknown/no-data meaning. Display
palettes are separate. Categorical resizing uses class-aware sampling such as nearest neighbor or
mode, rather than blending category IDs; numeric elevation can use suitable continuous interpolation.
Choose resolution for the layer's purpose, independently of output pixels and tactical grid spacing.
Share aligned samples/boundary rules between neighboring sheets.

Declare one authoritative representation per geographic layer. A raster derived from saved polygons
is a disposable cache. If painted terrain is authoritative, persist and version that raster and derive
its outlines/queries from it. Do not keep independently editable raster and vector copies of the same
boundary. Roads, rivers, crossings and POIs still need their semantic identities and topology.

### Detail, reproducibility and persistence

Lower-detail views may simplify paths within a physical tolerance while preserving important bends,
endpoints, junctions, crossings and sheet seams. Higher-detail views can add vegetation symbols,
shading, textures and labels without moving those features. Save meaningful new geographic detail as
an explicit refinement, compatible with established scale and precision; rendering alone does not
create tributaries, roads or other gameplay geography.

Make procedural decoration repeatable using saved feature/world-coordinate keys, seeds and versioned
rules. Save unique accepted decorative arrangements when they must survive renderer changes. Exact
pixel reproduction additionally depends on pinned renderer, style, fonts and assets; retaining a
rendered image preserves that particular illustration. Geographic continuity does not depend on
identical pixels.

The MVP needs the existing campaign SQLite store and JSON geometry, not a separate GIS server. Store
optional terrain/elevation assets through campaign-owned, versioned asset references and include them
in portable map bundles. Journal geographic changes with ordinary map revisions. Rendered PNGs and
derived navigation/terrain caches can be rebuilt from the saved model and its dependencies.

## Map profiles

| Suggested profile | Main objects | Presentation and movement |
| --- | --- | --- |
| Dungeon | Rooms, floor surfaces, walls, doors, stairs, props | Tactical geometry with a 5 ft or 10 ft grid overlay. |
| Settlement | Building footprints, streets, districts, gates, docks, landmarks | Freeform layout, normally without a visible grid; linked interiors and local encounters. |
| Region | Terrain areas, settlements, landmarks, roads, rivers and routes | Cartographic overview and travel connections; distances and travel constraints. |
| Outdoor encounter | Ground, paths, vegetation, water, obstacles, structures | The same tactical geometry and grid capabilities as a dungeon. |

Generated maps use local physical coordinates, initially feet, with defined precision, axes and levels.
Displayed units and scale bars may use feet or miles as appropriate. Pixel resolution, zoom and grid
spacing are independent of physical geometry. Use sparse geometric objects and reusable pieces;
derive occupancy/navigation structures as needed.

Geometry includes points, paths, polygons with holes and multiple regions. Material mapping and explicit
wall/terrain properties support irregular shapes without forcing settlements into rectangular tiles.
Road crossings need explicit connectivity and elevation: a bridge crossing another route is not
automatically a junction.

## Travel graph with local tactical scenes

Represent travel with the location/route graph, continuous journey progress and regional geography.
Attach detailed local tactical maps as leaves in the spatial hierarchy. Every combat uses a grid at
its actual physical location, including inn steps, city streets, wilderness clearings and roads with
forest on either side. Ordinary travel need not generate a continuous grid over every mile. A tactical
leaf is a level of detail, not a requirement that every fight occur at a pre-existing named graph node.

A scene records its stable map identity, parent location/area or route leg, distance along that leg
and any off-route offset, plus its physical frame, bounds, orientation, levels and relevant world/parent
transform. Use available precision; an absent regional atlas does not prevent anchoring a grid to an
established location or journey. A roadside fight at mile 7.3 remains at that route position. The grid's
5 ft placement lattice can carry a 5 ft or 10 ft displayed overlay, independent of output pixels.

Prepare or reuse the scene before starting a new combat:

1. Resolve the physical anchor and look up existing accepted local detail covering it.
2. Reuse/extend that detail, or author a bounded scene from the parent geography and known facts.
   Preserve road bends/width, terrain, shorelines, buildings, doors, stairs, landmarks and prior changes.
   The inn's entrance scene includes its steps and adjoining street with a matching interior threshold.
3. Map established character positions into the scene. Resolve previously unspecified placements from
   approach, formation, travel progress and narrative constraints; do not relocate established actors or
   alter known terrain to force a preferred combat outcome. Patrols use their actual current progress.
   Follow the tabletop setup: lay out the battlefield and place figurines. Present the appropriate
   player view and allow valid deployment choices through conversation/markers where the scenario
   permits them; an ambush or an already established position does not grant free repositioning.
4. Validate/persist the accepted terrain, references and placements before rolling initiative. Stage
   incomplete preparation separately from live combat; retries recover one accepted scene/encounter.
   A preparation failure consumes no turn or fictional time and does not trigger zone-only combat.
5. Render the filtered view if supported. Text-only clients use the same grid coordinates, physical
   distances and spatial rules. A failed/unsupported image display does not require another rules model.

Current shared tactical visibility combines the individual line-of-sight/perception results of the
party members in that scene/level, using canonical positions, occlusion, lighting and their abilities.
Individual actions retain the acting character's own sight requirements. Explored memory and hidden
feature discovery follow the [incremental reveal contract](DUNGEON_MAPS.md#incremental-reveal).
Enemies/NPCs use their own perception, received cues/reports and remembered evidence when deciding
whether/how to react. Routine patrols continue while unaware; being on the shared map or encounter
roster does not grant knowledge of the party's hidden positions.

Generation fills unspecified local detail; it does not reset the place for each encounter. Scene
identity follows saved geography/coverage, not the encounter ID. A second fight nearby reuses the same
inn door, trees, damage and remaining loot. Overlapping coverage references one authoritative geometry
and object state; scene boundaries and shared anchors agree. Include the accepted battlefield in an
encounter-retry checkpoint so retries do not reshuffle the scene.

Scene bounds describe available coverage rather than invisible walls. Extend or link tactical maps
when movement, pursuit, reinforcements or relevant sight/range require adjoining detail. Preserve
positions in their common/linked coordinate frames, actor identities, initiative, action budgets and
fog state across doors, levels or viewports. Loading coverage does not create a new encounter or grant
free movement. Relevant rules use canonical geometry rather than treating distant figures as adjacent
because an old zone label matches.

When combat ends, reconcile final tactical positions with graph travel, route progress or the actual
off-road position. A pursuit or retreat can move the party beyond its original encounter anchor. Keep
the pending journey's destination and remaining intent where applicable, account for elapsed time once,
and preserve the tactical scene and its changes for return visits. Noncombat interaction/exploration
may also use these maps; a grid need not be discarded when initiative ends.

Existing running zone encounters require an explicit validated conversion at a safe action boundary,
preserving their established state and resolving consequential placement ambiguity. This compatibility
work does not make zones an alternative mode for new combat, and normal region backfill/read-only views
must not silently perform the conversion. See [encounter policy](DESIGN.md#15-encounters).

## World size, latitude and climate

Default to a roughly Earth-sized spherical world, with a saved radius of about 6,371 km (roughly
40,000 km circumference), a geographic north, equator and arbitrary prime meridian. These parameters
describe a finite world without requiring its geography to be generated in advance. Campaign settings
can deliberately choose different world assumptions; retain the selected parameters with the world.

Choose geographic placement to support the requested climate. For ordinary unspecified adventures,
place the actual starting location around 43.5 degrees north, using southern France as the latitude
reference. This is a default rather than Earth's actual geography, a compulsory Mediterranean biome
or a requirement that every campaign begin at that latitude. Tropical, northern, southern-hemisphere
and other explicit premises take precedence.

Use absolute latitude as a broad climate baseline: generally warmer toward the equator and colder
toward either pole. From the default northern opening, traveling south tends toward warmer latitudes
until the equator; continuing south eventually leads toward the colder southern pole. Elevation,
maritime/continental influence, currents, prevailing winds, rain shadows and season can modify local
conditions. Keep temperature, moisture and vegetation independently expressible. Climate is not the
same as today's weather, and Earth-like seasons have opposite timing in the two hemispheres.

Give authoring tools the latitude range and saved climate context of the requested region. Persist
accepted regional climate, modifiers and their rationale with geography; later sheets and local detail
reuse it. Let transitions reflect physical distance and regional influences rather than changing the
climate abruptly at sheet edges. Fantasy exceptions have an explicit setting explanation. Narrative
descriptions, vegetation, agriculture, snow, water conditions and seasonal travel should agree with
this context without requiring a full atmospheric simulation.

### Geographic coordinates and regional atlas frames

Keep planetary latitude/longitude distinct from local sheet coordinates. Each regional atlas records
its world, a saved local anchor mapped to geographic latitude/longitude, orientation, units and a
versioned forward/inverse projection with its usable extent/accuracy. Choose the anchor so the actual
opening position has the intended latitude, wherever it lies inside sheet `(0,0)`. The southwest atlas
origin is not implicitly the equator, and sheet `(0,0)` does not mean latitude zero.

Regional sheets retain their chosen dimensions and normal output scale within that atlas frame.
Local tactical geometry keeps its physical units. One flat metric grid cannot preserve true scale
over the entire globe: geographic extent and world-scale measurements use the saved globe/transform,
not an assumption that projected X/Y remain globally accurate. When expansion would exceed a frame's
declared usable extent, continue through explicitly connected, suitably anchored atlas coverage.
Preserve old sheet references and shared world-feature identities across that transition. Longitude
wrapping resolves existing places rather than generating duplicate geography; latitude ends at the
poles, with explicit geographic/orientation handling for polar travel rather than indefinite northing.
The initial implementation can support a bounded regional frame while reporting unsupported global
transitions explicitly; it must not silently invent a flat or infinitely repeating world.

For legacy map completion, choose missing geographic anchors and climate to fit established world
facts. Apply the starting-latitude default only to unspecified choices; preserve existing climate,
journeys, positions and distances rather than relocating the campaign to satisfy the default.

Reference background: [NASA's Earth dimensions](https://nssdc.gsfc.nasa.gov/planetary/factsheet/earthfact.html),
[NOAA's latitude explanation](https://oceanservice.noaa.gov/facts/latitude.html),
[NOAA's climate factors](https://gml.noaa.gov/outreach/lesson_plans/Teacher%20Background%20Information-%20Earth%27s%20Climate.pdf)
and [USGS's projection overview](https://www.usgs.gov/faqs/how-are-different-map-projections-used).

## Initial campaign region and creative generation

The Campaign/World Engine creates the initial world/region map at the beginning of a campaign as part
of adventure setup. The map is a creative campaign artifact: authoring deliberately invents geographic
positions, distances, names and terrain from the campaign premise, style and player preferences.
Supplied illustrations can serve as references; initial creation generates and saves the campaign's
own regional geography.

The starting region is broad enough to contain a few key cities or villages, the opening location,
connecting routes and travel choices, roadside stops, major forest/lake/river areas and useful adventure
landmarks. Its extent follows those needs and the campaign premise rather than a mandatory fixed size.
Generate a useful regional overview without requiring an exhaustive world or every settlement interior.

Create all initially relevant cities, towns, villages and key roads as part of the setup draft and commit.
Their names, positions, connections and distances support the opening story, settlement relationships
and travel choices. Normally the main road exits the initial map at one or more land boundaries, with
saved continuations into the larger world. A wholly enclosed island can instead connect its roads to
harbors and outgoing sea routes. Include hills/mountains and their valleys/passes in the opening region
as appropriate to the setting; local buildings and other finer detail can remain for later generation.

The engine owns this workflow, using AI-authored creative proposals and/or seeded generation where
appropriate. It validates references, positions, route/terrain consistency and scale; assigns stable
identities; persists the result; and renders the accepted world. Ordinary defaults and "surprise me"
fill unspecified creative decisions. The player may supply constraints or revise the draft through
the existing setup process.

Use the [creative world-map authoring brief](WORLD_MAP_GENERATION.md) as generation guidance. The initial
region can be inland, coastal or island-based; choose a suitable opening position within its geography
rather than defaulting to the map center. Ocean, coastlines, islands and maritime routes are valid
creative features. Shared atlas style permits varied terrain, character and settlement density.
An initial coastal region is preferred when suitable, with inland alternatives remaining valid. Save
chosen environmental context, such as a warm southern coast, alongside the geography so narrative
descriptions and later local detail remain consistent.

1. Generate and retain the regional draft during adventure setup, linked to the opening scenario.
2. Revise it as setup decisions change, then validate its spatial structure and initial party knowledge.
3. Commit the actual accepted region/map together with initial adventure state at campaign setup commit.
4. Render the saved map for the start of play and return its references in fresh-session context.

Save geometry, routes, names, chosen materials/style and other established creative decisions alongside
generation provenance. A later conversation or render retry uses this accepted result. Image generation
is an optional source of artwork; baseline map creation/rendering uses the bundled library.

The full world state and the initial player view are separate. A creatively established place may
remain hidden, appear as a report or be known precisely, according to starting party knowledge.
Uncertainty about a report or an imported illustration does not prevent the authoring workflow from
deliberately establishing exact fictional geography. Once committed, that geography constrains later
detail, travel and atlas expansion; explicit corrections and world events use normal campaign mutations.

## Existing campaigns and map completion

For an existing committed campaign, the resume/bootstrap workflow detects a missing or incomplete
authored atlas and automatically starts or continues map completion. Opening/status queries can report
that work is needed; read-only queries do not perform creative world mutations. Unfinished setup uses
the normal initial-region workflow. A complete saved map with a missing/stale image needs rendering,
rather than another authoring pass.

Analyze the saved location hierarchy and connections, relevant descriptions, adventure/world facts,
travel history and any existing map fragments. Distinguish regions/settlements from their interiors
and dungeon rooms. Reuse existing location, route and map identities rather than replacing them with
newly generated equivalents. Established coordinates, terrain, measured route lengths, relative
positions and journeys constrain the draft. Travel duration alone does not establish physical distance;
portals or disconnected graph components do not imply ordinary roads or geographic proximity.

Extract supported constraints with their source references, then creatively author missing geography
and layout around them. Record the distinction between inherited facts, inferred constraints and new
creative choices. Additional places can be introduced as compatible world-building detail without
duplicating known places or forcing a new-campaign template over the established story. Keep genuinely
uncertain relationships explicit. Contradictory records retain their evidence and unresolved status;
unaffected areas can be completed without falsely reporting the whole atlas as spatially consistent.

Use existing atlas dimensions, style and artwork alignment where available. Otherwise choose suitable
sheet dimensions, origin and style for the campaign's established extent; several equal-sized sheets
may be needed immediately. Preserve partially authored geometry. Apply the same narrative, relief,
route-continuation and rendering guidance to the missing structure, constrained by prior facts.

Persist resumable staging and commit the accepted map through revision-checked, idempotent campaign
mutations. Recheck source revisions before publishing, so intervening play cannot make a draft stale.
Retries/concurrent resumes resolve one canonical atlas and one sheet per index. Save accepted layout,
source provenance and completion state independently of disposable rendered images, then render the
appropriate views. Both map content and its completion status participate in rollback together.

Preserve the current clock, character/journey positions, resources, encounter state and pending choices.
Regional backfill is outside fictional elapsed time and does not convert a running zone encounter into
a grid encounter or discover hidden places for the party. Known/visited/reported locations retain their
knowledge status; a complete GM atlas can have a partially known player view.

Use the ordinary campaign journal rather than a schema migration or replacement checkpoint. Existing
checkpoints remain restorable. If restoration removes the completed map or staged work, the next resume
re-evaluates completion against that branch's facts and knowledge, without reusing discarded discoveries
or constraints from later play. Once complete, ordinary resumes load the persisted atlas.

## Neighboring regional sheets and atlas expansion

The initial region establishes a campaign-owned regional atlas with fixed physical sheet dimensions.
Choose its width/height to suit the opening campaign, then give adjoining regional maps that same
extent and scale. Regional documents record their atlas reference, sheet index, local-to-world origin,
physical bounds and atlas style reference. Shared atlas coordinates provide continuity between maps.

With X east and Y north, the initial sheet is `(0, 0)` and its northern neighbor `(0, 1)`. For sheet
width W and height H, a sheet's southwest origin is the atlas origin plus `(i * W, j * H)`. Keep this
origin fixed as the atlas expands, including west and south into negative indices. This partition does
not impose a visible grid or rectangular terrain/street layouts, or put the opening location at the
center of its sheet.

### Sheet references and discovery

Use ordinary stable numeric references for saved entities, plus a simple coordinate address for
alignment and lookup. The following numbers are illustrative, not reserved identifiers:

| Reference | Meaning |
| --- | --- |
| `campaign:42` | Campaign owning the atlas and maps. |
| `atlas:7` | One regional coordinate frame with fixed sheet dimensions and origin. |
| `map:123` | Stable identity of a saved regional sheet. |
| `atlas:7[2,-1]` | Spatial address: two sheets east and one south of that atlas's origin. |
| `Sarg Coast` | Optional editable title/alias, useful for search rather than identity. |

The bracket form is a proposed human-readable locator, not a new compound entity ID. Structured tool
arguments carry the atlas reference and signed integer `sheet_x`/`sheet_y`; a successful lookup returns
the canonical map reference. Within the owning campaign, enforce uniqueness on `(atlas_id, sheet_x,
sheet_y)`. An address can describe an ungenerated sheet without allocating a map entity. Resolve
references inside their campaign; imports into another campaign remap saved identities as usual.

For any sheet `(i, j)`, north is `(i, j + 1)`, east `(i + 1, j)`, south `(i, j - 1)` and west
`(i - 1, j)`. Diagonals change both indices. The immediate neighborhood of the initial sheet is:

```text
                       North (+Y)
           (-1, 1)      (0, 1)       (1, 1)
           (-1, 0)      (0, 0)       (1, 0)    East (+X)
           (-1,-1)      (0,-1)       (1,-1)
```

Keep a sparse, queryable atlas index in campaign SQLite. Fresh-session context includes the current
atlas, map reference and sheet address. Read operations support lookup by address or map reference,
neighbors of a sheet, a world coordinate, and linked known locations; titles/aliases can be searched
without silently choosing between duplicate names. Index entries include title, bounds, revision and
available knowledge/generation status. A GM lookup of an ungenerated address reports that status
without creating geography. Player indexes/search results expose only appropriate knowledge, including
unknown status where the underlying existence of a sheet or feature is hidden.

Style, display detail, zoom and rotation do not change sheet identity or address. Settlement/dungeon
maps link to their parent location and atlas position; they are separate maps rather than more regional
sheet coordinates. Optional portable export names can follow
`campaign-42/atlas-7/sheet_2_-1.rpgmap.zip`; embedded references and the atlas index carry identity even
if a file is renamed. Render caches remain separate from the geographic sheet address.

### Coordinate ownership and shared edges

For world coordinates `(x, y)` and atlas origin `(ox, oy)`, locate a sheet with
`i = floor((x - ox) / W)` and `j = floor((y - oy) / H)`. Use mathematical floor, including negative
coordinates, rather than truncation toward zero. Each sheet owns its west/south edges and excludes
its east/north edges; fixed-precision coordinates make boundary/corner lookup unambiguous. A point just
west of the origin belongs to `(-1, 0)` when its Y lies within the initial sheet's vertical extent.
Shared features have one identity even when their clipped geometry appears in several sheet views.

### Matching views and expansion

Persist an atlas style/profile version chosen for the initial map: color palette, paper/background,
materials, line treatment, roof/vegetation/terrain symbols, typography and renderer defaults. New sheets
reuse those references and physical texture scales. The same output profile yields the same map-content
image dimensions; zoom and optional decorative margins remain presentation choices. Individual sheet
views and a combined atlas overview render the same saved world data.

Maintain shared boundary contracts in atlas coordinates. Record crossing roads/paths and rivers with
their stable identities, position, width, direction/tangent and relevant elevation/flow. Preserve
forest/lake extents, shorelines and other terrain continuity along the edge and at corners. A northbound
road exiting one sheet enters the next at the matching point on its southern edge. Its traveled and
remaining distances, and the party's journey position, remain continuous.

When movement will enter an ungenerated neighboring sheet, the Campaign/World Engine generates and
validates that sheet under the normal world/travel mutation workflow. Apply constraints from every
already generated neighbor and established nearby world facts, not just the edge the party crossed.
Publish the accepted geometry and boundary bindings once, under a unique atlas/sheet-index identity;
retries or simultaneous requests must not create competing regions. Commit and persist the result
before travel enters it, then render the appropriate view. Returning uses the saved sheet.

Road, river, cross-country and sea travel use the same boundary mechanism in any direction. The
northern-road example is illustrative, not a required route or preferred expansion direction. New
sheets may contain coasts, islands or mostly ocean; shoreline and connected-water geometry continue
across their boundaries. A diagonal/corner crossing can
enter the corresponding sheet without inventing a road or generating unentered side sheets. Explicit
world building can extend the atlas ahead of travel; read-only rendering or route previews do not
materialize missing sheets. Planning beyond generated geography reports its unresolved stretches.

Generate only the required sheet: an outgoing road/river can reserve a boundary continuation for later
without recursively generating the next region. Roads can reach destinations or end within a sheet.
New world generation remains separate from what the party sees; apply exploration and known/reported
location rules to every new view. Atlas additions, boundary bindings and journey changes use the same
campaign persistence/checkpoint machinery.

## Regional travel structure

The primary structural information for outdoor/regional maps is the distance between settlements and
roadside stops, the routes joining them, and the geographic extent of forests, lakes, rivers and other
terrain. Build and persist that travel structure first; cartography presents the same geography.

Represent it through the existing campaign location/connection model, extended with spatial detail:

- **Places and waypoints:** cities, villages, road junctions, taverns/inns, bridges, fords, ferries and
  other useful stops. Established places have stable location references; position may initially be
  approximate or unresolved. Reports about an unidentified place can have a stable knowledge marker
  until they can be linked to a location.
- **Route legs:** connected, ordered road/path/water-route segments with physical length, travel mode,
  surface, access constraints and current traversability. Keep route distance distinct from straight-line
  distance. Store distance precision/provenance where only approximate measurements are available.
- **Roadside placement:** anchor an inn or other stop at a measured point along its road, with an access
  spur where needed. Splitting a route leg to insert a stop preserves the original total distance and
  existing identities. A marker's decorative label position does not define its travel position.
- **Geographic features:** forests and lakes are areas with boundaries; rivers have paths, widths and
  associated banks/crossings. Route legs identify relevant terrain stretches. Road quality and off-road
  terrain are separate properties, so a maintained road through a forest can differ from walking through
  the forest itself.
- **Relief:** hills, mountain areas, ridgelines, valleys and pass/corridor anchors have spatial shape
  and relative elevation or available height data. Roads identify where they bypass or cross these
  barriers, including the relevant pass/tunnel/corridor and its travel constraints.
- **Crossings and barriers:** explicit bridges, fords, ferries and passes determine viable connections.
  Route queries account for lakes and rivers, crossing state and supported travel mode. A visible
  intersection or apparent shortcut on the illustration does not create a traversable route.

Use physical world units for authored distances and geometry, with readable miles/feet in responses.
On calibrated maps validate route lengths, positions and terrain intersections for consistency. On
approximate illustrations retain authored travel distances and scale accuracy separately from drawing
positions; resizing/restyling the illustration cannot change how far apart its places are.

Persist a journey's selected route, current road/off-road segment and progress along it, including
stops and remaining distance. Local encounters start at that journey position; resume travel from
the actual final tactical position afterward, updating route progress or off-road position as needed.
Route progress and character location must agree rather than snapping the party to its old anchor
or the nearest settlement.

Travel-time estimates state their assumptions about mode, pace, terrain and breaks. Estimating a trip is
read-only; actual travel commits progress and elapsed campaign time together. The tools should answer
queries such as "how far to the next inn?", "how much of this road crosses forest?" and "where is the
nearest usable river crossing?" through known geography in both text and graphical clients.

### Story-aware travel risk and hostile encounters

Travel carries the possibility of hostile encounters, with risk calibrated to the campaign's story
and established world state. Hostile territory, bandit activity, faction control, war, recent events,
terrain, time and the chosen route can affect that risk. Patrol protection and the party's relevant
precautions can change exposure. Ordinary travel need not produce a fight every time. Keep encounter
frequency, possible severity and eligible opponents separate; party capability can inform guidance
without silently rescaling already established enemies.

Give regions/route stretches saved risk context and encounter opportunities linked to relevant facts,
factions, pressures or story/backstory material. The DM chooses what fits the developing fiction.
An encounter may advance a plot or simply reflect believable local dangers; story alignment does not
require every hostile creature to be part of the central conspiracy. Learned dangers can inform
player route previews, while hidden threats and exact GM risk data remain subject to knowledge rules.

Support both authored and random placement, including their combination:

- The DM can author an encounter and place it at a suitable point on the intended journey, such as
  bandits at a ruined tollhouse tied to the region's history.
- The engine can roll for occurrence/content/location using a saved story-aware risk profile and
  eligible tables/route stretches. The DM can weave the resulting opportunity into the story and
  select a fitting location, or request a random valid point along the remaining route.

The DM retains creative control of placement, consistent with the actual route, terrain, elapsed
progress and established actor positions. Record encounter origin and placement method; an authored
event is distinguishable from a random result. Engine-owned rolls retain their resolved outcomes
rather than being silently replaced with more convenient ones.

Resolve checks as part of actual travel/time progression. Save resolved outcomes, including uneventful
checks, and carry forward accumulated exposure between calls. The applicable cadence/exposure rule is
versioned with the risk profile; short travel actions, restarts and atlas seams do not erase exposure
or grant repeated rolls. Previewing a route or rendering a map does not roll encounters. Changes to
world conditions affect future opportunities rather than rewriting resolved journeys.

When an encounter is placed, advance only to its actual route/off-road anchor and elapsed time, and
retain the unfinished journey. Prepare/reuse its tactical scene from saved geography and place its
actual participants; all combat uses that grid. Detection, avoidance, investigation or negotiation can
still determine whether hostile contact becomes a fight. Afterward, resume from the party's actual
final position and retain the scene, damage, bodies, loot and discoveries as applicable. Do not finish
the entire journey and then move the party backward to narrate an earlier attack.

The implemented baseline now saves travel exposure, opportunities and physical interruption positions;
see [travel encounter rules](RULES_ENGINE.md#9-travel-encounters) and [tool usage](MAP_USAGE.md).
Broader narrative-policy targets in this section do not imply autonomous generation or adjudication.

### Relief and mountain roads

Lay out relief before finalizing the road network. Prefer sensible gradients, valleys and routes around
obstructing ridges where a practical bypass exists. Where crossing the mountain barrier is justified,
route through an actual pass, valley corridor, tunnel or other explained connection. Distance, terrain,
access and the route's purpose can explain choosing a difficult crossing over a long detour.

Terrain geometry, route geometry and the illustration must agree. A pass is a real traversable opening
or lower crossing in the ridge, not just a name drawn next to an otherwise impassable barrier. Where
elevation data exists, validate relevant route gradients; with coarse relief, record qualitative/relative
constraints and review plausibility without claiming precise slope calculations. Cross-country travel
uses the same barriers and the party's travel capabilities. Established fantasy crossings retain an
explicit in-world explanation and corresponding traversal properties.

## Cross-country travel and progressive world building

The generated regional map is a persistent spatial reference for further planning, world building and
player-directed exploration. Its saved geography supports requests such as "I want to travel through
the forest straight to Lake Avaner." Roads are one way to travel; a journey may also cross continuous
terrain through player-chosen off-road segments. The location graph and terrain geometry work together.

Named geographic features have stable references and area/path geometry. Resolve the forest and Lake
Avaner from those records and turn the request into an intended route, destination and arrival anchor.
For walking to a lake, arrival normally means an appropriate shoreline point. Choose a reasonable
anchor from the request and known geography; clarify when different shores would materially change
the journey. The lake's label or geometric center is not its default walking destination.

Preserve intent such as direct travel, passing through a named forest, following a river or avoiding
a particular area. A route can combine roads, off-road paths, crossings and water travel. Report known
barriers and relevant alternatives rather than silently replacing a direct forest route with a road
detour. A chosen off-road path is a journey trace, not an automatically created road or maintained trail.

Preview from player-known geography, with explicit estimates and unknown stretches. Route planning is
read-only and does not reveal hidden locations or obstacles. Actual travel uses canonical world state,
commits position/time together, and stops at a discovery, obstacle or encounter that warrants a new
decision. Persist the party's physical position between named places and progress along the selected
route so partial journeys, interruptions, reloads and continuation agree.

Generate finer detail when useful, either during advance world building or as the party approaches:

- A forest can acquire clearings, streams, paths, ruins and individual encounter locations.
- A lakeshore can acquire a beach, landing, campsite or nearby structure.
- Every local combat acquires or reuses tactical geometry, terrain, objects and character placement
  before it starts; noncombat scenes can also use the same detailed map.

Each detailed map records its parent area and spatial anchor, scale, orientation and relevant boundary
connections. It inherits the regional forest/lake boundaries, shorelines, roads, river crossings and
committed distances. New detail fits those constraints and previously established neighboring detail;
generation does not move the lake or rewrite traveled geography. Explicitly approximate/unresolved
detail can be refined while preserving established facts. Save newly committed locations, geometry,
discoveries and artwork so subsequent visits and fresh conversations return to the same place.

The model can use the rendered map as a visual reference and query its structured geography for exact
anchors, distances and constraints. Adding local detail or changing map scale remains part of the same
persistent world model rather than generating an unrelated scene.

## Living maps, reports and discoveries

The travel map evolves as the world is developed and the party learns about it. A new site can begin
as a name and coarse spatial constraints, acquire a position and approach as those become established,
and later gain an interior or tactical map. New canonical detail fits committed geography and retains
stable references. Discovering an existing hidden site updates party knowledge of that same place.

For example, at an inn the party hears: "There is an infamous castle at the eastern hillside near
Travaris, Castle Nefaria." Record the report and add an appropriate party-known map annotation:

- The reported name, identity/aliases if resolved, and original description.
- Who or what supplied the information, where it was heard, game time and relevant event references.
- The reported spatial relation: near Travaris, on an eastern hillside, with precision and unresolved
  wording preserved. Qualitative directions do not imply an invented bearing, distance or search radius.
- Evidence for the report, its party visibility and later corroborating or contradictory claims.

Keep canonical geography, party knowledge and rendered annotations separate. Hearing a report
establishes that the party received it; existence, reputation, exact position, route knowledge and
whether the party has visited are separate questions. A guide may establish a reliable location before
a visit, while a castle's reputation may remain disputed. A single confidence value must not conflate
those different facts.

Render an approximate area, directional annotation or map note according to the known anchors. Use a
marker/label treatment and textual status that communicate uncertainty. If only the relation to
Travaris is known, a decorative castle icon does not become an authoritative coordinate or exact
route endpoint. Planning can target the reported area and continue with searching or new directions.

Later information refines the same knowledge entry and can resolve it to a canonical location without
creating duplicate castles. Preserve the source/history of superseded or conflicting reports. Resolve
names and aliases using context; similar names alone do not establish that two places are identical.
Correcting a mistaken report updates knowledge rather than moving established world geometry.

Distinguish physical world events from learning about them: a bridge can collapse or a castle become
occupied before the party hears the news. Persist event time and learned time where known. The player
map shows the party's available information, including outdated observations, while the GM/world view
can carry the current state. Confirmed changes update traversal and local maps through ordinary
campaign mutations; learning about those changes updates the relevant knowledge views.

Journal new reports, discoveries, knowledge corrections, identity resolution and committed world
changes. Refresh affected labels/markers and render revisions after a knowledge update. Fresh sessions
recover both the known map and its uncertainty; checkpoint restoration recovers the knowledge and
geography appropriate to that branch. Expanding the atlas does not regenerate established areas.

## Coordinate-based points of interest and re-rendering

Support adding, updating and moving points of interest at specified map coordinates throughout play
and world building. A POI has a stable map-object reference, label/type, position, optional location
reference, description/tags and visibility. A new site can start with this minimal record and acquire
more detail later; an annotation can also link to an existing place without duplicating it.

Coordinates identify their frame: physical map coordinates and level, grid placement, or image/view
coordinates. Image clicks or pixel coordinates include the render/view reference so the engine can
apply the correct rotation, crop and scale transform. Calibrated views resolve to physical coordinates;
uncalibrated artwork can retain an explicit image anchor and unknown geographic precision. Exact pixel
placement does not invent a physical distance. The map coordinate is independent of label layout.

Distinguish a canonical site placement from a party annotation or reported position. Exact world
placements, approximate reports and personal notes can coexist with explicit precision and audience.
New/changed world sites follow normal geography validation; party annotations do not silently relocate
established world locations. Points can anchor route stops or off-road destinations through the shared
spatial model without automatically creating connecting roads.

Use revision-checked, idempotent map mutations to save POI additions and edits. Invalidate affected
render data and support returning a refreshed PNG/resource alongside the updated map reference and
revision. The client can also request `render_map` separately. Rendering uses the committed revision;
retrying a render does not add the POI again. Existing terrain, routes, discoveries and unrelated
locations retain their saved geometry and identities.

The renderer redraws POI symbols and resolves label layout for the requested view, applying normal
player/GM filtering and uncertainty styling. Fresh conversations recover the saved points, and
checkpoint restoration includes their positions, notes and discovery state.

## Observations from the supplied references

The references were supplied as visual guidance for outdoor maps:

- The Kalaman city image shows individual building footprints, irregular streets and blocks, walls,
  gates, docks, water and trees, plus a decorative border, compass and a printed scale bar.
- The regional Neverwinter image shows settlement markers, routes, rivers, forests and mountain/hill
  symbols at a much broader scale, without individual buildings.
- The detailed Neverwinter city image combines building/roof detail, districts, streets, waterways,
  landmarks and labels. Its compass places north to the right of the image.
- The parchment Neverwinter image presents the city at a different orientation and with different
  styling and label layout.

These suggest a shared semantic model with several cartographic presentations. Proposed renderer
support includes organic road/block geometry, reusable roof/vegetation/terrain symbols, configurable
palettes and paper textures, water/shoreline treatments, borders, compass roses and scale bars.
Repeated detail can be procedurally placed and persisted, while prominent landmarks use distinct art.

Store generated labels separately from artwork. Label placement, priority, visibility and scale range
let an overview show districts and major landmarks, while a closer view shows streets and buildings.
Styling, label layout and view rotation can vary without changing location identities or gameplay state.

## Semantic objects and detail

Location annotations can use points, building footprints, district/terrain areas and route paths.
They link to existing campaign locations and connections. Selecting a settlement opens its city map;
selecting a building entrance opens its interior; choosing a local encounter opens the relevant tactical
map. Links include entrance anchors and orientation/scale alignment where spatial correspondence matters.

Illustrated background detail does not require a full location record for every drawn house or tree.
Persist decorative layout and give interactable features stable map-object identities. Materialize
additional location detail when relevant while respecting committed geography. Explicit routes and
terrain data determine travel, rather than estimating it from the illustration.

## Artwork alignment and scale

Separate the map's physical coordinate frame from its presentation frame. Each artwork/view can carry
translation, rotation, scale and cropping information. Apply the same transform to geometry, markers,
labels and input coordinates; preserve north orientation and scale when resizing or rotating a view.

Support imported artwork as a registered background with semantic overlays. Calibration can use two
image points and a known physical distance, plus an orientation anchor. Record scale confidence as
precise, approximate or unknown. A stylized illustration may support approximate overview navigation
without providing accurate tactical measurements; a missing scale must not become an invented distance.
Detailed encounter geometry can have a calibrated local relationship to an approximate overview.

Imported images can contain baked-in labels and symbols. Record that limitation: their text and
placement cannot be controlled individually like generated label objects. A renderer can use the same
registered geometry/locations with alternative backgrounds or fully generated cartography.

## Acceptance examples for later outdoor support

- Travel through bandit-ravaged or hostile territory versus a protected route: encounter risk and
  eligible opposition reflect saved world conditions, with learned danger available for player planning.
  A safer/stealthier choice can change exposure without silently relocating an authored ambush.
- Let the DM place an encounter at a story-linked tollhouse, then use a random valid route point for
  another encounter: each uses its saved physical anchor and compatible tactical scene. Travel stops
  there, retains its remaining intent and resumes from the actual post-encounter position.
- Split a journey into short moves, reload between them and cross an atlas boundary: accumulated
  exposure and resolved checks, including no-encounter results, remain consistent. Read-only previews
  do not roll, spawn hostiles or disclose unknown threats.
- Start combat on an inn's steps: prepare/reuse a grid with the established entrance, street and
  interior threshold. Enter the inn during the fight without changing actor identities or initiative.
- Interrupt road travel at mile 7.3 with forest on both sides: resolve the battle on a local grid
  that agrees with the road/terrain and actual patrol/party positions, including in a text-only client.
  Re-rendering is read-only; generation retries do not duplicate the battlefield or encounter.
- Flee along the road and into nearby woodland: extend/link tactical coverage without invisible
  scene-edge walls or a combat reset. Resume travel from the resulting route/off-road position, with
  elapsed time counted once; a later nearby encounter reuses the saved damaged/looted surroundings.
- Create an unspecified ordinary opening: its actual starting location lies around 43.5 degrees north
  on the saved Earth-sized world, independently of its position inside sheet `(0,0)`. A tropical or
  southern-hemisphere premise overrides that default without changing the sheet addressing scheme.
- Generate neighboring sheets north/south of an established region: latitude and climate continuity
  follow the world transform and physical distance, with plausible local elevation/coastal variation.
  Southern-hemisphere travel toward the south pole trends colder rather than becoming ever warmer.
- Reload or extend a legacy warm coast: retain its established climate and compatible geographic
  anchor. A high mountain at the same latitude can have colder conditions without relocating it.
- Cross the prime-meridian opposite seam or a regional frame boundary: resolve the same world places
  and feature identities. Unsupported polar/global transitions are explicit rather than duplicated
  terrain or silently inaccurate planar travel.
- Resolve `atlas:7[2,-1]`, its map reference and a known location within it: each returns the same
  saved sheet. Renaming its title, restyling it or adding western/southern neighbors preserves its
  identity and coordinate address; duplicate titles produce explicit search candidates.
- Look up north/east/diagonal neighbors and a point just west of the atlas origin: signed indices and
  half-open boundaries resolve the intended sheet. An ungenerated-address query creates no map, and
  player indexes do not expose hidden sheet or location metadata.
- Render one saved region as parchment, a colored illustration and a sparse travel diagram: road
  bends, river courses, coastlines, crossings, terrain boundaries, POIs and travel distances agree.
- Request a lower-detail overview and a closer view: simplification respects anchors/seams, and
  added symbols do not create or move geographic features. Reloading uses the accepted paths even
  after a generator update; exact old artwork remains available when its image was retained.
- Paint forest over a hill: both land cover and relief survive saving, reloading and restyling.
  Raster resizing does not invent terrain classes, and derived outlines have one declared source.
- Resume an old campaign with saved settlements/routes but no map: analyze its graph and established
  facts, commit a renderable atlas and recover it after a second resume. Existing references, distances,
  current location and game time agree; retries do not duplicate places or sheets.
- Complete a partial legacy atlas: retain its authored geometry/style, add compatible missing detail
  and preserve unresolved conflicting evidence. A render retry uses the accepted saved map.
- Resume mid-encounter while preparing a regional map: initiative, HP, zones, hidden participants and
  pending choices are preserved. Restore a pre-completion checkpoint and resume using only facts and
  knowledge on the restored branch.
- Commit the campaign and start a fresh session: initial settlements, key road connections, distances
  and outward main-road continuations already exist. Their locations and roles support the opening
  narrative while alternative player journeys remain available.
- Place a ridge between towns: road routes use a practical valley/bypass or a justified pass/crossing.
  The visible pass, recorded route and terrain traversal agree. A later adjoining sheet continues an
  outgoing main road at its saved boundary anchor.
- With unspecified geography, prefer a suitable coastal opening; an explicit inland premise is honored.
  Example names and warm-water details are selected only when appropriate to the particular campaign.
- Generate contrasting inland, coastal and island-based openings using suitable starts in each region:
  settlement/travel relationships, visual style and creative rationale fit the campaign premise.
- Expand by sea or across a non-northern boundary: coastlines, islands, navigable water and travel
  progress remain continuous. New sheets can be mostly ocean or wilderness without settlement quotas.
- Leave the initial map by its northern road: generate one northern sheet with equal physical extent,
  matching style and a road entering its southern edge at the shared world position. Road identity,
  distance and party progress continue across the seam. Returning/reloading recovers both saved sheets.
- Generate a sheet adjoining two existing regions: roads, waterways and terrain agree at both shared
  edges and their corner. A repeated request resolves the same atlas/sheet-index entry.
- Leave off-road or across a corner: preserve physical position and terrain continuity, generate only
  the entered sheet, and apply player-knowledge filtering. Rendering an overview creates no extra sheets.
- Finish campaign setup with unspecified world details or "surprise me": the Campaign/World Engine
  creates a coherent starting region containing several key settlements, routes/distances and major
  terrain. Start a fresh conversation and recover the same region, opening location and player view.
- Revise the map draft before setup commit, then render/restyle the accepted map and later add a local
  encounter: the committed settlement positions, travel distances and geographic boundaries agree.
- Add two POIs at specified physical coordinates, update one by reference, and re-render: both appear
  at the intended positions, with stable identities and updated labels. Saved terrain/routes agree with
  their previous geometry; reload and checkpoint restoration recover the appropriate POI state.
- Place a POI through a rotated/cropped image view and through equivalent physical coordinates: both
  resolve to the same map position. An approximate rumor marker retains its uncertainty, and player
  renders exclude GM-only additions.
- Hear the Castle Nefaria report at the inn, then reload: recover the same sourced annotation near
  Travaris, retaining its uncertainty without fabricating coordinates, exact distance or a known route.
- Receive conflicting directions, then obtain a reliable position or visit the castle: preserve the
  reports and resolve/refine the same place rather than adding a duplicate or changing committed terrain.
- Develop the castle's approach/interior later: it fits the established site and surrounding geography.
  A later change in occupation/condition appears on the player map when the party learns about it.
- Restore a checkpoint before the inn conversation: the later report and subsequent discoveries are
  restored away consistently with the rest of that campaign branch.
- Request direct travel through the forest to Lake Avaner. The preview resolves a shoreline arrival,
  preserves off-road intent and estimates known terrain/distance without starting travel. A newly
  discovered stream can stop progress for a decision without having appeared in the player preview.
- Open a tactical map at that stream or on reaching the lake: position, scale, terrain and shoreline
  agree with the parent region. Resume from the same journey position and reload/return to recover the
  committed local detail. World building ahead of travel follows the same spatial constraints.
- Cities A and B are 18 miles apart directly, with a 24-mile road. An inn lies at road mile 9, a bridge
  at mile 12, and forest spans miles 6 through 16. The tools report 9 miles from A to the inn and 15
  from the inn to B. At road mile 14 the party is in the forest, has passed the bridge, and has 10 miles
  remaining. Save/reload and a local encounter preserve that journey position.
- Add a roadside stop to an existing route or change the map's illustration: total route length and
  committed travel distances agree. A closed crossing changes route availability and any alternatives.
- A lake or river intersecting a proposed walking shortcut requires a suitable route/crossing or travel
  mode. Known terrain constraints and distances are queryable without viewing an image.
- Show a city as a colored illustration and a rotated parchment view: tagged locations, marker positions,
  entrance links, north direction and calibrated distances remain consistent.
- Zoom from a regional settlement marker into its city, then an inn and its interior: each view resolves
  the same saved location hierarchy and returns to the correct parent position.
- Generate irregular streets, a harbor and city walls without imposing a visible tactical grid. A local
  street encounter uses the shared tactical geometry and explicit connection to its settlement map.
- Calibrate imported artwork from a known distance; resizing the image leaves physical measurements
  unchanged. An uncalibrated illustration reports unknown scale instead of fabricated travel distances.
- Change a generated label layout or art style without regenerating world geography, semantic identities
  or exploration state.
