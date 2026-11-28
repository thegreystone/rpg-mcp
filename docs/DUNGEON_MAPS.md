# Dungeon maps

Status: design and implementation targets. The core dungeon/scene authoring, PNG/text rendering,
incremental reveal and tactical grid support are implemented. See [supported map workflows](MAP_USAGE.md)
and the [backlog](BACKLOG.md) for the delivered surface and remaining extensions.
The shared tactical scene model also covers incidental street, inn-entrance and roadside combat without
requiring a complete settlement map. Detailed settlement authoring helpers remain a later extension.
The [shared map format and outdoor cartography design](MAP_FORMAT.md) covers regional support and broader
settlement targets, including visual guidance from the supplied city and regional map references.
The [player experience review](DESIGN.md#204-player-experience-priorities) proposes play-loop priorities
and scenarios to assess alongside these technical requirements.

## Scope

- All combat uses canonical tactical geometry with a 5 ft or 10 ft displayed grid and figurine-style
  token placement. Travel between detailed scenes uses the location/route graph and journey progress.
- An encounter can involve monsters and NPCs from multiple rooms, including unseen rooms. Players can
  describe their own placement and direct party members; graphical views support character markers
  when rendering is available.
- The tools produce actual map images from saved state, with consistent rendering across models.
  The renderer chooses texture/output pixel sizes and accepts imported texture overrides by path.
- Dungeons have reusable default pieces and room textures. The DM can create custom pieces, rearrange
  placed pieces, cut groups of pieces with custom geometry, and ask the model to generate or derive
  custom textures.
- Player maps reveal incrementally: only previously explored or currently visible areas appear.
- Maps and exploration persist within a campaign and load again when the party enters that location,
  including after a restart or a fresh conversation.
- Prepare most dungeon structure/content before entry, scaled to the site's size and story; support
  both assisted generation and complete model-authored designs. Rooms remain living places whose
  furnishings, structures and descriptions reflect persistent consequences of play.

The existing location tree and connection graph remain the semantic foundation. See
[`DOMAIN_MODEL.md` §12](DOMAIN_MODEL.md#12-location-and-movement) and
[`DESIGN.md` §20](DESIGN.md#20-semantic-maps). This plan extends their semantic maps with geometry,
appearance, exploration and encounter placement.

## Spatial model and editing

Each map belongs to a campaign and an owning location. A dungeon floor may contain several room
locations; stairs link floors. Room regions and doors reference stable location and connection IDs.
Opening a door updates the same state used by travel and tactical movement.

Store physical coordinates and dimensions independently of grid display. Changing between 5 ft and
10 ft overlays preserves scale, token footprints and distances; a 10 ft overlay still permits 5 ft
placement. Dungeon geometry normally snaps to this finer tactical lattice.

Reusable pieces describe walkable regions, walls, openings and connection sockets. Start with rooms,
straight corridors, corners, junctions, doorways and stairs. Placed instances have their own stable
identity, transform and overrides; editing one instance does not change every use of its definition.
Support custom geometry as well as custom artwork. Artwork has explicit physical scale and matching
geometry when it depicts functional boundaries.

Use 10 ft of clear walkable width as the normal corridor default: two five-foot positions across.
Wall thickness sits outside that clear width. Support deliberately narrow 5 ft passages and wider
authored halls as explicit variants. Doorway width is independent; a 10 ft corridor can narrow to
an occupied 5 ft doorway that forms a strategic choke point. Display grid spacing and the underlying
5 ft placement lattice remain separate from these physical widths. Apply the default to new
authored corridors and future corridor prefabs; retain existing saved geometry.

Generation establishes entrance, rooms, connections, alternate routes and stairs before arranging
pieces and decorating them. Validate connectivity, openings, passage widths, overlap and required exits.
Persist the resulting layout along with its seed and generator/definition versions.

Draft layouts can be freely rearranged. Authoring corrections to committed geography follow the
existing explicit, audited correction policy. Legitimate gameplay consequences, including destruction,
use ordinary action/world mutations. Both preserve referenced room/object identities and validate or
resolve effects on connections and character positions. Conversational commands and a future visual
editor use the same underlying spatial operations. The viewing/editing UI remains an implementation choice.

## Creative authoring and preparation before entry

Offer two compatible authoring paths through the same structured dungeon draft:

- **Assisted generation:** the model supplies creative requirements and asks helpers to propose a
  room graph, fit pieces, furnish rooms or fill selected gaps. Constraints can include inhabitants,
  history, purpose, scale, levels, entrances, story links and intended atmosphere.
- **Direct model authoring:** the model supplies the complete creative design, including custom
  geometry/layout, rooms, descriptions, inhabitants, objects, secrets and connections. Default pieces
  and random generation are conveniences rather than mandatory creative templates. Helpers can still
  handle selected mechanical tasks without replacing authored decisions.

The engine assigns stable references, resolves assets, validates geometry/connectivity and existing
campaign facts, persists the accepted design and renders it. Return specific validation issues for
repair rather than silently substituting a different story or layout. Creative freedom operates within
campaign preferences and established state; committed gameplay outcomes still use the normal rules.

### Blueprint first, tile layout near approach

A dungeon can begin as a semantic blueprint or related plot points, without a detailed tile layout.
Save specified facts and relationships: intended scale, room count, levels, selected room roles,
attributes, important connections, inhabitants, rewards and story dependencies. Unspecified dimensions,
coordinates, room purposes and incidental furnishings can remain open. Distinguish revisable story
intentions from an accepted blueprint's binding world facts; neither implies party knowledge.

For example, an accepted blueprint can establish:

- Sixteen rooms in total across two levels, including the altar and treasure rooms.
- The altar room is on the lower level.
- A secret entry connects the altar room to the treasure room.
- The ceremonial staff of lightning is located in the treasure room.

It need not assign a width, tile coordinate or furnishing to every room, or specify how many rooms
are on each level. The secret room counts toward the accepted total rather than becoming a seventeenth
room during layout generation. The staff's named identity/story role does not invent its mechanics;
resolve its item definition through the normal content rules before mechanical use.

When the party approaches the dungeon, flesh out and lay out its coherent playable space from that
blueprint before entry. Materialize room shapes, tile arrangements, corridors, stairs, door/secret-entry
anchors and relevant object/actor placements; complete unspecified content compatibly. Validate the
accepted count, level assignments, connections, reward locations and other constraints. Preserve
existing semantic identities when adding geometry rather than creating duplicate rooms or treasure.
Full model-authored layouts can also be supplied earlier when desired.

Before the party enters, establish and persist most of the site's playable geography and meaningful
room content so its spaces, inhabitants, clues and story fit together. This preparation includes its
purpose/history, levels, entrances/exits, alternate routes, room attributes, important furnishings,
inhabitants or population plans, loot, patrol/duty plans, factions, secrets and cross-room dependencies.
Save accepted geometry and placements once materialized; a blueprint does not require every tile to
exist when its early plot commitments are first saved. Preparation and player discovery are separate:
an authored dungeon can remain entirely concealed from the party.

Scale preparation to the actual place:

| Site | Preparation before entry |
| --- | --- |
| A small house used by a local gnoll gang | Normally prepare the whole house: rooms, doors, furnishings, occupants, escape route and story-relevant contents. |
| A substantial dungeon or fortress | Prepare the connected levels, room inventory/layout, meaningful contents and relationships across the site; load/render only the needed portions. |
| A widespread, multilevel drow Underdark city | Prepare the city-wide cavern/district/level structure, major buildings and routes, vertical connections, factions and narrative sites, plus most playable area content at appropriate scale. Use linked district, level and room maps rather than one enormous tactical image. |

For enormous sites, fine interiors of minor buildings, incidental residents, decorative dressing and
other nonessential detail can be filled later inside saved footprints and connection constraints.
Prepare the approaching area's playable detail before it is entered. This refinement cannot invent a
new essential route, move a story-critical place or adjust hidden contents merely to follow the party's
chosen path. The initial city plan must contain real spatial/story structure across its extent rather
than just its entrance neighborhood. Population plans do not require simulating every resident.

Build large sites in resumable, revision-checked, idempotent batches under a saved site plan. Track
preparation completeness and unresolved optional detail explicitly, validate the connected result and
publish coherent playable maps before entry. A partial failed draft is not an accepted dungeon. Reuse
the accepted site on later visits; normal rendering and read-only lookup do not regenerate it. Load
bounded relevant context rather than sending the whole underground city on every turn. The general
lazy-detail principle applies to compatible refinement after this preparation, not essential dungeon
structure invented one room at a time during exploration.

### DM tuning before publication

Generation supplies an editable draft. The DM reviews the site's threats, defenses, routes and
rewards before explicitly publishing it; structural validation does not certify encounter balance.
Use one keyed `revise_map_preparation` batch to tune several rooms, connections, actors and reward
sources together. A dragon's lair can deliberately have a much harder boss, stronger protections,
larger monetary hoard and specifically chosen magic items than a local gang's hideout. The engine
does not replace those choices with a routine budget or infer treasure from the party's level.

Pin important boss identities, support creatures, treasure, keys and access alternatives in the
blueprint before exact geometry is known. Later detail preserves accepted commitments. Keep the
boss and exceptional rewards explicit rather than applying one high difficulty to every room;
ordinary corridors and incidental locks may remain simple. Publication materializes the authored
sources, not an immediate grant of their loot to the party.

Challenge guidance uses a simple **DC 15 fallback**, chosen during preparation, without party-level
scaling. Locked connections/objects receive `challenges.pick_lock`; secret features receive
`challenges.discover`; declared `kind:TRAP` objects receive discovery and disarm guidance. These
checks are distinct. No trap is created merely because a chest is locked, and force-open difficulty
must be authored separately for the actual door/material and approach. Override each check to fit
the fiction, for example a crude lock at 10 or a secure hoard lock at 25.

Each check specification uses `resolve_check` fields: `kind`, `ability`, optional `skill`/`tool`,
and `difficulty` (whole DC 1–40). For example:

```json
{
  "key": "hoard-chest",
  "locked": true,
  "challenges": {
    "pick_lock": {
      "kind": "SKILL_CHECK",
      "ability": "DEX",
      "skill": "Sleight of Hand",
      "tool": "Thieves' Tools",
      "difficulty": 25
    },
    "force_open": {"kind": "ABILITY_CHECK", "ability": "STR", "difficulty": 25}
  }
}
```

The ordinary pick-lock fallback follows the seeded SRD Lock's DC 15 Dexterity (Sleight of Hand)
check with Thieves' Tools. Discovery/disarm defaults are editable DM guidance, not universal SRD
values. Saved guidance stays GM-only, remains stable across reloads and restyling, and never
overwrites authored values. It does not automatically roll, unlock, discover or disarm anything:
the DM adjudicates access/tools/time, uses the saved specification with `resolve_check`, and records
the resolved state with the existing gameplay tools. The separate trap/discovery evaluator remains
planned below.

### Loot, rewards and access

Placing loot is part of creative site preparation. Author its relationship to the inhabitants, history,
risks and story: stolen valuables in a gang's strongbox, a guard carrying a useful key, supplies in a
storeroom or a distinctive relic in a shrine. Rewards can include equipment, currency, consumables,
documents, clues and other setting-appropriate items; not every room needs treasure.

Persist actual items/quantities in canonical inventories owned by a specific location, container or
character. A blueprint can establish semantic placement such as "locked chest in the treasure room"
before its exact tile is chosen. Materialization adds the physical anchor to that same source/item;
it does not grant or duplicate the loot. Keep ownership, concealment, locks/traps and access conditions
with their canonical records. Record significant keys, clues and reward/story dependencies in the site plan. Finding a
chest, opening it, discovering a compartment and taking its contents are separate changes; visible
artwork does not grant knowledge of hidden contents or unidentified properties.

Transfers and loot grants use the existing inventory/accounting rules. A carried, stolen, moved,
destroyed or collected item cannot also remain available at its original source. Empty containers,
remaining contents and known discoveries persist; later room refinement or re-entry does not refill
them. Restocking or relocation requires a recorded world event. Use registered item definitions and
the campaign's custom-item policy for newly authored items rather than defining mechanics only in prose.

### Patrols, schedules and encounter opportunities

Prepare how the site is occupied over time as well as its initial snapshot. Relevant guard posts,
patrols, shifts and possible reinforcements draw from a finite saved actor/group roster or explicitly
bounded population source. A dead, captured or absent member is not replaced merely because a patrol
check or schedule runs. Reinforcements consume actual available members; any later recruitment or
restocking is a separate authored world event. A patrol plan records its starting phase, route waypoints
and connections across rooms/levels, travel/dwell durations, active duty windows and applicable
perception/behavior. Include responses to alarms, suspicious sounds, blocked routes and missing members
where relevant. A schedule describes intended behavior; combat, death, capture or other resolved
interruptions preserve actual state rather than resetting actors to their planned route.

Before tile layout exists, a patrol route can name rooms/areas and timing constraints. When it is
materialized, bind those waypoints to reachable geometry and validate distances/timing against the
accepted plan; keep its roster and relationships rather than rolling a replacement patrol.

Encounter opportunities specify when and under what conditions the party might meet inhabitants:
route proximity, time windows, sight/sound, searches, alarms and other world events. An authored
possibility is not a guaranteed appearance or discovery. Existing patrols move through the saved
world; crossing a room threshold neither spawns a fresh copy nor reveals its hidden occupants.
Reinforcements have an origin, trigger and plausible route/delay, and join through their stable actors.

Regional timers advance only while at least one party member is physically present in that region.
When the last member leaves, save the remaining durations and patrol/event phase; reactivate them on
return without catching up for elapsed time elsewhere. A split party keeps every occupied region active.
This applies to all regional timers, including patrols, alarms, reinforcements and local scheduled
events; the global calendar continues normally. Within an active region, searching, waiting, resting
and traveling advance relevant activity. Process consequential intermediate arrivals or perceptible cues so a long
action can stop for a player decision instead of skipping directly to the final patrol positions.
Persist patrol progress, alarm state, resolved deviations and reinforcement status with normal world
changes. Support bounded/event-based updates without requiring every city resident to be simulated
continuously. Schedule lookup, render requests and fresh-session reads do not advance time.

If wandering encounters, loot variations or patrol deviations use randomness, author their tables,
conditions and sources, resolve rolls through the engine at the applicable world event, and save the
accepted outcome once. Important placed loot and story-critical contents are prepared before entry;
optional random outcomes do not permit repeated searches, re-renders or reloads to reroll them.
Returns/checkpoints recover the appropriate roster, inventory, patrol phase and event state. Player
views reveal only learned schedules and observable actors/cues, not hidden routes or future rolls.

### Tile layout, room descriptions and live state

Keep three linked parts of a dungeon, using existing campaign/location references:

| Part | Saved information |
| --- | --- |
| Spatial layout | Placed floor/wall/corridor pieces, room-region shapes, doors, stairs, physical coordinates and material references. |
| Room/area records | Stable location identity, name, purpose, descriptive/sensory details, linked features, connections and visibility-scoped secrets. |
| Live state and observation | Character/item placement, door/trap state, changes, exploration and what each observer/party knows. |

Room records carry attributes such as level, purpose, theme and relevant environment properties.
Placed things such as a locked treasure chest belong to a room and occupy concrete grid space once
materialized, with a physical position/footprint and optional tile/piece anchor. Derive occupied cells
from that placement; a large object can span several cells. Changing artwork or the displayed grid
does not redefine its identity, ownership or physical position.

The tactical grid is the measurement/placement lattice; reusable tiles/pieces are building blocks;
rooms are meaningful places. These need not have a one-to-one relationship. One room can span many
pieces, a piece can be clipped into an irregular room outline, and a corridor can be its own named
area. A prefab can be larger than one grid square. Room records link to their map regions; doors and
features link to concrete anchors as well as their semantic references.

For example, `location:42` might be the Hall of Echoes: a room region assembled from stone-floor
pieces, with a bronze door and a cracked fountain at saved positions. Its room record describes the
hall's character and observable sensory details. The fountain's hidden compartment is a separate
visibility-scoped feature; guards and dropped items use their current character/item records. Reusing
the same floor pieces elsewhere does not copy the hall's identity, inhabitants or secrets.

Store canonical descriptions with the location/feature records rather than copying them into every
tile or render. Narration combines relevant authored details with current observable state. Entering
one corner does not disclose the entire room description, undiscovered features or out-of-sight actors;
an old descriptive template cannot restore a looted item or claim a now-open door is closed. Room
summaries remain useful in text-only play and resolve to the same locations as graphical markers.

### Living rooms and persistent consequences

Rooms and their descriptions represent changing world state. Battle can smash or move furniture,
break a door, breach a wall, leave rubble or burn a surface. Other resolved events can repair, clean,
restock or repurpose a room. Keep affected objects/features' stable identities and changed condition;
destroying a table does not simply delete its identity or restore its prefab on the next visit. Record
debris, remains or surviving contents when relevant, without requiring detailed physical simulation.

Commit related consequences through the normal action/world journal: object condition/placement,
affected geometry, cover, collision, sight, lighting and traversal properties as applicable. Recompute
derived visibility/navigation and refresh affected renders from that state. A collapsed passage may
legitimately become blocked; a collapse affecting a character requires resolving its consequences,
rather than rejecting it solely because the old authoring placement checks no longer hold. Ordinary
destruction is a gameplay outcome, while retconning the undamaged room is an authoring correction.

Narration combines the room's enduring identity with its current observable condition. Reloads,
return visits, later content refinement and re-renders retain the broken table, missing door and other
committed changes. Current off-screen changes do not overwrite the party's last-observed room view;
normal observation updates it when appropriate. Checkpoint restoration restores the matching room,
objects, geometry and knowledge together. A repair/restoration is another recorded world change.

### Custom geometry and damaged rooms

Accept polygons with holes and multiple disconnected regions in physical map coordinates. A custom
outline can clip a selected group of placed pieces, add an irregular region, or subtract damaged areas
across piece boundaries. This supports jagged room outlines, razed corners, wall breaches and holes in
floors. Persist the resulting geometry and material assignments, preserving piece/object identities and
the reusable source definitions.

These operations change canonical spatial structure as well as the rendered silhouette. Specify the
affected surfaces and boundary meaning explicitly: removing a floor may leave a pit or void with open
sight; removing a wall can open a passage and line of sight; a rubble region can have a separate terrain
cost. Clipping a floor does not automatically erect walls around every new edge.

Rendering clips textures to those shapes using a shared material origin and physical repeat scale, so
cuts across adjacent pieces preserve pattern alignment. Movement boundaries, sight occlusion and
placement validation use the same committed structure. Validate geometry, affected door openings and
connections, intended routes, and existing character footprints before committing an edit. Update
spatial state and invalidate derived rendering/navigation/visibility data together.

## Tool-owned image rendering

Provide a headless map renderer in the first dungeon increment. It composes saved geometry, materials,
observable objects, grid, exploration and character markers into actual PNG images. The model supplies
structured authoring decisions and asset references; the renderer owns composition, clipping, pixel
dimensions, labels and styling. Saved state, assets, renderer version and output profile determine the
result, supporting consistent visuals across models and conversations.

Return image content when supported by the client/transport, together with a resource/export reference
and a compact textual summary. A client may display a static image without supporting interactive
editing. Text-only clients still use the same maps and placement operations. Rendering is read-only
with respect to campaign state: it does not regenerate geography, reveal areas or advance play.

Render player images from the filtered player representation, including last-observed structure in
explored areas. Cache keys include renderer/profile version, normalized assets, audience, viewport,
grid settings and relevant map, exploration and play-state revisions. Bound image dimensions and render
cost; use a selected viewport or bounded overview for large maps. Output metadata includes physical
bounds and the coordinate transform needed to align optional interactive markers.

Pixel sizing is a renderer policy. Versioned quality profiles choose pixels per foot and output limits;
material roles/presets define physical repeat scale. The tooling chooses normalized texture dimensions,
resampling and output resolution from those settings. Models do not calculate per-tile pixel sizes.
Changing zoom, output quality or the 5 ft/10 ft grid preserves physical dimensions and material repeat
scale. Repeating materials and fitted decals have distinct mapping rules that preserve aspect ratio.

The renderer and image decoder must work headlessly in the supported JVM and GraalVM native builds.
Prototype and verify that compatibility before selecting dependencies; packaged users should retain
the existing standalone installation experience.

Proposed tool responsibilities (names are provisional):

| Operation | Responsibility |
| --- | --- |
| `generate_dungeon_map` | Prepare and persist a structured site draft using creative constraints/helpers or a complete model-authored proposal; support linked levels and resumable large-site preparation. |
| `import_map_texture` | Import a server-readable image path, normalize it under renderer policy, and return an immutable campaign asset reference. |
| `update_map` | Apply placements, coordinate-based POI additions/edits, material overrides and geometry operations with revisions, idempotency and spatial validation; optionally return a refreshed render. |
| `render_map` | Render a saved map/view to an image with the requested audience, viewport, grid and output profile. |

## Incremental reveal

Track exploration separately from current visibility, using three player-facing states:

| State | Player view |
| --- | --- |
| Unexplored and out of sight | Covered; hidden geometry, contents and labels are omitted. |
| Previously explored, currently out of sight | Dimmed memory of last-observed terrain and static features. |
| Currently visible | Current terrain and observable objects/tokens. |

Seeing an area records it as explored, even before entering it. Entering a room does not reveal its
entire interior automatically: corners, closed doors and obstructions may hide parts of it. Current
visibility depends on observer position, walls, door state, sight range, lighting and applicable senses.
The shared player-map view is the union of the current line-of-sight/perception results of party
members on that map, calculated separately from each eligible observer's actual position and abilities.
Sight regions need not connect: separated companions can reveal different parts of the same floor.
A member on another floor contributes to that floor's view rather than projecting sight onto this one.
Shared map visibility does not give every character individual sight; targeting and other actions use
the acting character's own perception and the normal rules. Visible terrain alone does not expose a
concealed or invisible actor, secret feature or undiscovered contents.

Memory of an explored area does not disclose off-screen changes. Moving creatures appear only when
currently observable; historical sightings can remain in narrative history. Secret doors, traps and
hidden objects have discovery state independent of whether their surrounding floor is visible.

The engine computes visibility from saved geometry and play state. A GM can explicitly reveal, conceal
or correct exploration through journaled operations. Exploration changes persist and participate in
checkpoint restoration; current visibility is recomputed from restored positions and world state.
Recompute affected sight after movement, door/obstruction changes, lighting changes and changes to an
observer's capabilities. If no party observer can still see an area, its view becomes explored memory
rather than continuing to show live off-screen state. Observation updates follow journaled play;
rendering/querying an existing view does not itself move actors, advance time or reveal new secrets.

Filter player resources and images before delivery. Hidden geometry, notes, objects and metadata must
not be embedded in player payloads or covered only by a removable visual overlay. GM views are separate.
Any render cache must distinguish audience and all relevant map, exploration and visibility state.

## Multi-room encounters and character placement

Every combat references a saved tactical scene, including outdoor fights. Reuse prepared dungeon
maps; incidental encounters prepare compatible local detail at their location or journey anchor before
combat starts. The [travel-graph/tactical-scene contract](MAP_FORMAT.md#travel-graph-with-local-tactical-scenes)
defines parent anchors, scene reuse, entry placement, coverage expansion and return to graph travel.

An encounter can span several rooms and connecting passages on the saved dungeon map. Monsters and
NPCs may participate while unseen or join later as reinforcements. Crossing a room boundary preserves
the encounter, participant identities, initiative and positions. Existing characters join through their
stable references, including when first becoming visible; they are not recreated for the displayed room.

Encounter membership, an actor's awareness and player visibility are independent. Being included in
an encounter neither reveals that actor to the player nor gives it knowledge of the party. The GM and
engine adjudicate involvement and responses to alarms or other events. This does not require simulating
every creature in the dungeon continuously.

Each enemy/NPC calculates its own perception and awareness from its position, line of sight, hearing,
range, lighting, senses and applicable detection rules. It can react to a party member it perceives,
or investigate a sound it hears; the shared party map and the GM's knowledge do not supply enemy
knowledge. Unaware enemies continue their normal duties/patrols and do not start targeting or hunting
the party merely because combat has begun elsewhere. Seeing a target or hearing its noise still uses
the relevant stealth, concealment and other perception rules.

Sound conveys only the evidence justified by that stimulus, such as a noise, direction or approximate
source. It need not identify the party or reveal exact actor coordinates. Alarms, shouts and reports
can alert other enemies through explicit communication with plausible reach, delay and content;
one guard's detection does not silently alert every member of its faction.

Persist relevant alert state, evidence and last-known positions. An alerted enemy can search or pursue
from what it learned after losing sight, while further hidden party movement does not update its
knowledge unless it perceives another cue or receives a report. Apply this policy to NPC decisions
and action validation as well as visible markers; reloads/checkpoints preserve the matching awareness.

Unseen participants remain in canonical encounter state. Player map resources, marker lists, encounter
summaries and initiative views follow the same knowledge policy: undiscovered actors' names, counts,
positions and turn metadata must not leak through those views. Perceptible sound or other clues may
communicate limited information without revealing an otherwise unknown creature's exact placement.

Players can describe their own position and direct companions through conversation, using known
landmarks, grid coordinates or relative positions. For example: "I stand just inside the doorway;
Mira stays five feet behind me, and Dain takes the west pillar." Resolve the request to concrete
character placements using known spatial context, clarifying consequential ambiguity before committing.
Return accepted positions in a compact textual form even when graphical rendering is unavailable.

Distinguish initial deployment from movement after play starts. Validate map bounds, physical
footprints, occupancy, walls, doors, terrain and reachable paths. During combat, apply the normal turn,
movement and reaction rules to both the player's movement and directed party members. A requested
destination does not grant extra movement or bypass turn order. Invalid requests preserve valid state
and explain observable constraints without disclosing hidden obstacles or actors.

Each character has one canonical map placement, referenced by exploration, encounters and rendered
markers. Crossing into another room updates the spatial placement and character location together.
Positions persist across encounters, sessions and checkpoint restoration. Own-character placement is
available to its controller; party and other markers follow the applicable shared-knowledge and
observation policy.

Graphical renderers draw character markers as a separate layer with stable character references,
distinguishable labels or icons, and correctly scaled footprints. Observable monsters/NPCs receive
markers according to the same visibility/discovery policy. If interactive marker placement is supported,
clicks or drags invoke the same validated mutations as conversational placement; graphics remain an
optional view of authoritative state. Moving or revealing a character updates both its marker and
the applicable visibility without changing the background artwork.

## Proposed extension: logical pieces and placed traps

**Status: proposed, not implemented.** The current engine supports authored geometry, materials,
placed objects, secret filtering, resolved object changes and timed searches. It does not yet supply
a reusable logical-piece catalog or automatically discover, trigger or disarm placed traps. The
tile-oriented experience suggested by Neverwinter Nights is inspiration for convenient authoring;
the rules and authoritative geometry remain those of this campaign's ruleset.

### Reusable logical pieces

A material answers "what does this surface look like?" A logical piece also supplies explicit
geometry, connection points and optional object templates. Add a small versioned catalog that
models can inspect and place, rotate or adapt through the existing preparation workflow:

| Piece family | Useful initial examples | Explicit mechanical data |
| --- | --- | --- |
| Corridors | Straight, corner, T junction, crossing, widened alcove | Default 10 ft clear width, floor polygon, walls, openings and connection points |
| Thresholds | Open arch, single/double doorway, barred threshold | Passage/object anchor, width, closed-state collision and sight |
| Vertical links | Stair flight, landing, ladder opening | Linked levels, legal arrival footprints and traversal cost |
| Crossings and gaps | Narrow bridge, broken bridge, pit or floor opening | Walkable floor, gap boundary and separately authored hazard consequences |
| Furnishings and storage | Table, shelves, bed, chest, cage | Footprint, collision, cover and optional canonical inventory/container link |
| Ruins | Breached wall, collapsed corner, rubble pile, broken floor | Retained and removed geometry, rubble cost and explicit sight/cover properties |

Definitions are reusable authoring inputs; placed instances receive their own stable keys and live
state. Reusing a chest piece must not duplicate another chest's inventory, trap, identity or damage.
Moving or rotating a draft instance transforms its geometry, connection points and attached objects
together, followed by normal geometry/placement validation. Custom polygons and geometric cuts
remain available; a piece library must not restrict direct model authorship.

Separate mechanical definitions from optional artwork bindings. A bridge texture alone cannot
make a gap traversable, a cracked-floor texture does not declare a pit, and a drawn wire does not
create a trap. Texture overrides, changed style and 5 ft/10 ft display grids preserve the same saved
physical shape, passage/object identities and effects. Models supply creative choices and suitable
materials; the engine validates dimensions, connections and the supported deterministic mechanics.

### Trap identity, placement and knowledge

Represent a trap as a stable placed feature, initially reusing a keyed map object where practical.
Save its authored cause, trigger and consequences separately from its visual representation. It
may occupy a polygon/footprint spanning several grid cells, attach to a stable object key such as
`object:strongbox`, or attach to a passage reference. An attachment follows the canonical object or
passage rather than a texture, label or inventory item name. A pressure plate or wire can be a
separate visible prop without making the whole mechanism known.

Persist independent categories of state:

- **Mechanism:** armed, triggered, disarmed or spent, remaining finite charges and any explicit
  reset/rearm conditions. A one-shot trap stays spent on return; repeated checks, restyling and
  reloads cannot replenish it. Rearming requires the authored resources, time and a credible actor.
- **Knowledge:** hidden, noticed, identified or understood, scoped to an observer and any actual
  reports to the party. Seeing the floor does not identify a trap. Finding it does not disarm it;
  disarming it does not silently tell an absent character what happened. Shared map presentation
  can show communicated party knowledge while individual checks still use the acting observer.
- **Physical effect:** the wire, plate, bolt launcher, affected area, attached container and current
  obstruction/debris state. A triggered pit can remain dangerous after its release mechanism is spent.

The following is an illustrative authoring shape, **not an implemented tool payload**:

```yaml
key: east-corridor-wire
kind: TRAP
level: 0
trigger:
  event: CROSS_AREA
  polygon_ft: [[20, 0], [20.5, 0], [20.5, 10], [20, 10]]
mechanism: {state: ARMED, charges: 1}
discovery: {cue: "A fine line catches the torchlight near the floor.", profile: tripwire}
effect: {profile: corridor_dart, direction: east}
```

The effect and discovery profiles must resolve to supported rules data or an explicitly reviewed
DM adjudication. Invented profile names in an authored proposal are not executable mechanics.
Trigger eligibility describes credible physical facts, such as crossing the wire, sufficient weight
on a plate or opening the lid. Party/enemy allegiance is not automatic immunity: enemies can trigger
their own mechanisms unless they know and actually use a safe route, key or bypass. Do not assume
a creature can jump, fly, squeeze or avoid a wire without the applicable rules and actual movement.

### Discovery, disarming and interrupted execution

The DM handles discovery checks on the player's behalf. This applies to traps, secret doors,
hidden compartments and other concealed features. When exploration creates a legitimate detection
opportunity, the DM initiates the appropriate check through the engine using the eligible observer's
actual abilities, proficiencies, position, senses, conditions and circumstances. The player need
not request a detection roll or supply a die result. Detailed tactical control of a companion does
not transfer responsibility for these discovery checks to the player.

Use passive detection where the applicable rules call for it. Automatic notice does not silently
spend an action or begin a deliberate search. The player can choose to search an area or examine an
object; the DM resolves that declared approach, including its engine-rolled checks and required
time/action cost. Check before a dangerous step when detection is possible in time; a useful warning
can pause the approach for a decision instead of moving the observer into the trap first.

Active searching is a declared, persisted exploration mode with a real cost and benefit. Save who is
searching, their area or route, method and pace. The rules profile supplies the movement/time penalty
and relevant detection bonuses or Advantage; apply both together only while the character actually
uses that approach. Apply the benefits to checks relevant to that search method. In combat, charge
the required Search action and account for movement according to the combat rules/profile.

The player can say "We proceed slowly, checking the floor and walls" and the DM resolves the mode,
pace and checks without asking for each die roll. Apply the chosen pace to the actual searchers;
companions keeping together must respect that pace, while a separate scout can have a different
assignment. Extra elapsed time advances the shared timeline and active regional patrols/events.
Persist interrupted search progress and modifiers across reloads. Completed search benefits apply
to the area/method actually covered; unfinished work does not grant the complete result.

A deliberate search after ordinary observation can be a meaningful new approach when its rules and
costs permit. Toggling search mode, splitting movement or repeating the same unchanged request does
not create free additional rolls or retain search bonuses after returning to normal movement.

Keep undiscovered features, detection thresholds and hidden results out of player-facing prompts,
maps and narration. Do not announce a detection roll merely because a trap or secret door is present.
Narrate what the observer actually notices; failure is not proof that an area is safe. Noticing a
seam or pressure plate may precede understanding its mechanism. Finding a secret door does not open
or unlock it, just as finding a trap does not disarm it. The future shared discovery evaluator must
retain private opportunity/result records for all these feature kinds, rather than implementing
trap-specific checks that secret doors can bypass.

Passive discovery evaluates the appropriate observer's supported senses, sight, approach and rules
thresholds. An active search uses the existing timed-action progression and an appropriate skill/tool
check. Describe observable cues before exposing mechanism details. Record what the observer learned,
the search area/method and resolved check so retry, reload or splitting one search into calls cannot
produce free repeated rolls. A changed approach, new evidence or later attempt follows the rules/DM
adjudication; it does not automatically reroll an unchanged search. Another movement call, revisiting
the same unchanged area or changing a companion's tactical controller does not create a new attempt.

Disarming is a specific interaction with a discovered or otherwise physically accessible mechanism,
subject to reach, tools, time, checks and any combat action cost. The DM chooses a credible approach
and stakes; the engine rolls and commits the supported consequences. Authored outcomes distinguish
success, harmless failure, partial progress and triggering where applicable. They must not imply that
every failed check necessarily fires the trap. Improvised attempts can use an explicit DM ruling
without silently changing the mechanism's accepted facts.

Use one shared trap evaluator from exploration and combat movement, portal arrivals and relevant
object/passage interactions. Evaluate swept physical movement/footprints, including diagonal paths
and large creatures, rather than checking only the final cell. Respect explicit trigger conditions:
merely standing near a wire is different from crossing it. Before opening a trapped chest, evaluate
the opening interaction before changing the lid state or granting access to its contents.

Stop a multi-step move or interaction at its first relevant discovery/trigger decision, return actual
position/progress and charge only the work completed under the chosen action rules. A just-noticed
wire can invite a stop-and-consider decision before crossing when the observer has the opportunity;
a wire already crossed produces its consequence instead. Resolve saving throws, damage, conditions,
sounds and ensuing awareness through the existing rules and timeline. Any required pending choice
holds the action; saved continuation must not retrigger a one-shot effect or spend its charge twice.

Player previews use known geometry and discovered danger only. An undiscovered trap cannot change
the preview's path, warning text, counts or cost in a way that acts as an oracle. Normal pathfinding
may avoid a known trap when the chosen route permits it, but must not silently choose a safe route
using the DM's hidden trap data. Text and PNG views follow the same discovery policy; GM authoring
details, check thresholds and concealed mechanisms remain absent from player metadata.

### Two first examples

**A corridor tripwire:** place a one-shot mechanism across a normal 10 ft corridor. A cautious approach may
notice its cue; the player can investigate, attempt a disarm or deliberately cross. An unaware guard
can trigger it too. After activation, retain the spent mechanism, actual damage and any surviving
debris. Returning to the corridor does not create a replacement trap.

**A trapped chest:** attach the mechanism to the stable chest object and trigger it on opening rather
than mere adjacency. Searching its lock, unlocking it, disarming the trap, opening it and taking its
contents are distinct operations or explicit combined actions. A successful lock check does not
implicitly disarm the trap. After disarming, the same canonical contents remain; after triggering,
apply only the authored consequences and never refill or duplicate the chest's loot.

### Staged delivery and acceptance playtests

Start with a small logical library of straight/corner/T/cross corridor pieces, a threshold and a
chest, plus the two trap examples above. First specify and test known/unknown state, one-shot finite
charges, checks and interruption/resumption in both exploration and combat. Add stairs/bridges/ruins
as catalog presets after that shared execution contract works. Repeating/rearming traps, complex
linked mechanisms, environmental chains and broad prefab-pack authoring can follow separately.

Before advertising the first iteration, play through these cases using real campaign state:

- [ ] A hidden corridor wire does not appear in player text, PNG, context or a read-only preview;
  revealed floor and party sight alone do not discover it.
- [ ] Normal corridor pieces provide 10 ft clear width through straight sections and junctions;
  explicit 5 ft passages and independent doorway widths retain their intended tactical clearance.
- [ ] Passive notice and a completed active search produce appropriate cues and observer knowledge;
  interruption, retries and reload preserve search progress and previously resolved checks.
- [ ] The DM initiates warranted discovery checks for traps and secret doors without a player roll
  prompt. Use the actual eligible observer; reveal only perceived cues, charge active searches their
  required time/actions, and preserve failed/unchanged opportunities across control changes and reloads.
- [ ] Compare normal passage, cautious searching along a corridor and a focused chest/wall search.
  Resolve the applicable pace/action costs and check modifiers together; extra time can bring a patrol,
  and interrupted/reloaded searches preserve work without free bonuses, repeated rolls or early findings.
- [ ] Cross the wire through exploration movement and combat movement, including a diagonal path,
  a large footprint and a destination beyond it: stop/resolve at the same physical trigger point.
- [ ] Arrive through a portal into a trigger area and interact with a trapped chest: both use the
  shared evaluator without bypassing reach, budgets, pending reactions or locked-container access.
- [ ] Notice a wire in time to stop, fail to notice it, and fail a disarm under different authored
  stakes: outcomes preserve actual progress, legal choices and only supported consequences.
- [ ] Let an unaware enemy cross, and let an informed enemy use an established safe route. Neither
  player knowledge nor GM omniscience grants creatures unexplained avoidance or immunity.
- [ ] Trigger a one-shot trap, retry its interrupted operation, leave/reload/return and restore a
  checkpoint: charges, damage, knowledge, loot and continuation remain consistent without duplicates.
- [ ] Disarm/open/loot the chest, then re-render in another style and reuse its prefab elsewhere:
  the original remains disarmed and depleted; the new object has independent identity and contents.

## Default textures and room themes

Ship a coherent default library of reusable material textures and room-theme presets. Initial materials
should cover worked stone, rough cave rock, earth, timber, decorative tile, damp/mossy surfaces, ice and
shallow-water surfaces. Initial room themes should include crypts, caves, prison cells, barracks,
storerooms, shrines and flooded chambers. Themes select materials and optional separately placed props.

Floor textures use a consistent top-down style, calibrated physical repeat size and seamless edges
where they repeat. Variants can reduce obvious repetition while preserving scale and visual consistency.
Walls, doors, objects, grid lines and fog are separate layers. Functional objects and undiscovered clues
must remain individually controllable rather than being baked into a generic floor texture.

Appearance is independent of gameplay geometry. A wet-stone texture does not implicitly make terrain
difficult or slippery; those properties are explicit map data. Reusing a material at either displayed
grid size preserves its physical texture scale.

## Model-generated and derived textures

The model can choose a built-in material, request a new texture from a description, or derive a variant
from an existing texture/reference. For example: "derive frost-covered stone from this crypt floor."

1. Obtain a texture specification from the tooling, including recommended source dimensions and
   repeat/style requirements, using the material role and renderer profile. Supply that specification
   and intended material/reference to an image-capable client or configured generation provider.
2. Inspect the result for repeat seams, scale, unwanted perspective and baked-in objects or clues.
3. Import the accepted image as a versioned campaign asset, recording its dimensions, scale, prompt,
   source asset/reference and available generation provenance.
4. Assign that asset to selected pieces or register it as a reusable custom material/theme.

Generated images are saved and reused on later visits. Deriving a variant creates a new asset and
preserves its source. Existing map instances retain their selected asset/definition version until
explicitly changed. Replacing artwork preserves geometry; an intentional structural edit is separate.

The engine owns asset registration and persistence. Image generation is an optional capability supplied
by the client or a configured provider. Built-in materials keep ordinary map creation available without
an image-generation service, and imported images can use the same registration path. Rendering saved
maps to images is an engine responsibility and is available with the bundled materials.

### Texture overrides from paths

The model can supply an image path as an override for a material, placed piece or selected region.
Paths resolve on the server host. Import supported image files, validate their format/dimensions, and
normalize them under the renderer's sizing policy. Store the result as an immutable campaign asset
and return its stable reference. Map updates can resolve supplied override paths through this same
import process; later renders use saved asset references.

Copy imported artwork into campaign storage, retaining the original source/provenance as metadata.
Moving, deleting or modifying the external source file must not change a saved map. Reimporting a
changed file creates a new asset version. Missing or unsupported sources produce an actionable error
without partially applying the requested map edit. If the client and server do not share a filesystem,
use an uploaded/resource-backed asset through the same registration and normalization process.

## Persistence and recall

Keep authoritative layouts, piece instances, object references/state, exploration and encounter
positions in the existing SQLite database, scoped by campaign and location. All mutable map state goes
through the campaign journal and checkpoint machinery.

Large imported/generated assets may live under `campaigns/<campaign-id>/assets/` in the configured data
directory, with immutable content references in SQLite. Keep assets needed by current state and
restorable checkpoints. Exports and rendered caches are separate from authoritative data; backing up
the data directory includes the database and custom artwork.

Location arrival and session bootstrap return a bounded map summary/reference. Full map resources load
on demand, with an appropriate player/GM view. Resolve a room to its containing floor map. Encounters
reference that saved map, while durable changes such as opened doors and looted objects outlive combat.

## Delivery increments

1. Persistent dungeon layouts, default pieces/materials, basic custom pieces/assets, generation,
   rearrangement and custom geometric cuts; headless PNG rendering with tool-owned sizing and path
   overrides; conversational exploration placement, character markers, incremental reveal, filtered
   resources, recall and checkpoint support. Support preparation before entry, direct/assisted authoring
   and persistent room/object changes. Image display depends on client capabilities.
2. Multi-room tactical encounters, including unseen participants and reinforcements; player and party
   movement using those maps, with footprints, movement budgets, terrain, walls, doors, distance, reach
   and opportunity attacks. Specify diagonal movement, corner blocking, sight and area geometry before
   claiming support for those mechanics.
3. Richer visual authoring, texture-generation integrations, reusable custom libraries and portable
   import/export. Client-supplied generated images can already enter through the basic asset path.
4. Later, freeform scaled settlements with tagged locations and links to interior/encounter maps.

## Acceptance scenarios

- Save the sixteen-room, two-level blueprint with a lower-level altar, secret treasure-room entry and
  ceremonial staff, without tile coordinates. Reload preserves those facts without inventing an
  eight/eight split or revealing them to the party. On approach, generate a connected tiled layout
  satisfying the same identities/count, connections and item placement; no extra treasure room or
  duplicate staff appears. Later damage/looting remains authoritative over the initial blueprint.
- Prepare a guarded storeroom with a placed strongbox, concealed contents and a key carried by an
  existing guard. Opening/taking items updates the same inventories; a second visit or reload does
  not duplicate the key, reroll the contents or refill the chest.
- Search that storeroom while an existing patrol approaches on its saved schedule. Elapsed search
  time can bring audible footsteps and a decision before contact; watching/waiting can establish
  learned timing without automatically revealing unseen actors. Reload retains the same patrol phase.
- Sound an alarm, block a patrol route or defeat one of its guards: actual group state and plausible
  reinforcement timing change. Room re-entry does not restore dead guards or create duplicate groups;
  checkpoint restoration recovers the matching earlier loot, patrol and alarm state.
- Exhaust a finite patrol roster: subsequent checks cannot spawn replacement guards. Leave the region
  with a reinforcement due in three minutes, spend a day elsewhere and return: three active minutes
  still remain. Leaving a companion in the region keeps its timers running; leaving with the final
  companion pauses them. Reload and checkpoint restoration preserve both remaining time and roster.
- Prepare a gnoll gang's house through assisted generation and another through a complete custom
  proposal: both retain the authored room relationships, story content and furnishings before entry,
  with specific validation feedback rather than mandatory prefab or random-layout substitution.
- Prepare a large multilevel underground city: major districts, routes, vertical links, inhabitants'
  relationships and story sites fit across its extent. Resume interrupted preparation without
  duplicates, load bounded context and add a minor interior inside its reserved footprint without
  moving established geography or generating essential structure around the party's chosen route.
- Smash a table and breach a wall through resolved gameplay. Cover, sight, traversal, narration and
  rendered state agree; leaving/reloading retains both changes. Repairing the table is a new world
  event, and restoring a prior checkpoint recovers the appropriate earlier room state.
- Assemble a room from several reusable pieces and link it to one location description. Change its
  textures or reshape its draft region: the room identity and linked descriptions/features survive.
  A second room using the same pieces has independent description, inhabitants and discovery state.
- Describe only the visible part of the Hall of Echoes: its hidden compartment and unseen guard stay
  undisclosed. After opening its bronze door or looting an item, the next description and rendered
  state agree rather than repeating the initial template.
- Generate a crypt, rearrange a corridor, add a custom piece and save it. A fresh conversation loads
  the same geometry, stable references and chosen textures.
- Approach a corner or closed door: the hidden area remains covered. Opening the door reveals only
  newly observable space. Walking away retains explored terrain in a dimmed view.
- Mira rounds a corner while Dain remains elsewhere on the same floor: the shared view includes both
  sight regions, but Dain cannot make a sight-dependent attack around Mira's corner. Hidden contents
  stay undiscovered. Once all observers lose sight, the passage shows last-observed memory; a member
  on another level contributes to that level's view only.
- A creature moves or an object changes outside current sight: the explored view does not reveal the
  change. A visible floor does not automatically expose a secret door or trap.
- Inspect player map resources and renders: hidden contents, GM annotations and secret markers are absent.
- Start an encounter with a guard in an unseen adjacent room. Its identity, placement and revealing
  encounter metadata remain hidden. When it enters sight, its existing marker appears at the correct
  position without duplicating the character or starting a separate encounter.
- Keep the party silent behind an occluding wall: an unaware guard continues its patrol. A sound it
  can hear can prompt investigation without revealing exact party positions; an alarm alerts only
  recipients who receive it. After losing contact, the guard searches from its last-known evidence,
  including after reload, without following unseen movement from the GM's full world state.
- Describe placement for the player and two companions relative to a known doorway and pillar. Accepted
  positions, room locations and markers agree after a reload; moving between rooms preserves initiative.
- Request an occupied or unreachable destination, or movement beyond a character's available turn
  budget. Valid state is preserved and the response explains only observable constraints.
- Where graphical interaction is supported, place a character through text and through its marker:
  equivalent requests produce the same positions, visibility updates and rule validation. Text-only
  clients can describe and direct placement without a graphical view.
- Derive a moss-covered crypt material, assign it to one room and reload. The saved appearance returns
  with identical dimensions and movement boundaries; other rooms and the source material are unaffected.
- Display the same map with 5 ft and 10 ft grids: its dimensions, material scale and token positions agree.
- Restore a checkpoint after exploration, door changes, map edits and texture replacement: geometry,
  knowledge, object state and positions return together, and referenced artwork remains available.
- Generate and render a dungeon using only the bundled library when image generation is unavailable.
- Render the same saved state with the same assets and renderer/profile version through different
  model clients: physical geometry, appearance, reveal and marker placement agree without regenerating art.
- Import differently sized texture overrides; the tooling normalizes their resolution and mapping.
  Delete the external source files and reload: saved appearances and physical repeat scales survive.
- Clip an irregular outline and subtract an interior hole across several pieces. Textures align across
  their edges, and movement, sight, doors and token footprints agree with the resulting structure.
- Remove a wall and separately cut a floor: the wall breach opens the intended sight/path, while the
  floor cut retains its explicit pit/void semantics. Occupied or invalid edits are handled atomically.
- Damage a room outside current player sight: the player image preserves its last-observed structure
  until the change becomes observable.
- Verify decoding and bounded image rendering in supported headless JVM and native distributions.
