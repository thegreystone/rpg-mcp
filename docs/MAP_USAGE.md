# Maps for the DM and world builder

Maps are saved campaign state. The model authors the place and its story; tools validate its geometry,
keep the accepted facts and draw the current view. Travel uses the location/route graph. Every new
combat has a local grid, including a roadside fight or a confrontation on an inn's steps.

Load `get_instructions` and use the operations advertised by the current server. Reference numbers
below are illustrative; use the references returned for the actual campaign.

## Prepare a dungeon

Start with `begin_map_preparation` using `kind: DUNGEON`. A blueprint can establish the scale and key
story commitments before tile coordinates exist:

```json
{
  "name": "The forgotten altar",
  "room_count": 16,
  "levels": 2,
  "rooms": [
    {"key": "altar", "name": "Altar room", "level": 1, "role": "ALTAR"},
    {"key": "treasure", "name": "Treasure room", "level": 1}
  ],
  "connections": [
    {"key": "secret-entry", "from": "altar", "to": "treasure", "secret": true}
  ],
  "rewards": [
    {"key": "staff", "room": "treasure", "name": "Ceremonial staff of lightning", "quantity": 1}
  ]
}
```

An integer `levels` declares floor IDs `0` through `levels - 1`. An explicit list such as `[-1,0,1]`
supports basements. Secret rooms count toward the total. The blueprint does not require an equal
number of rooms per floor, and the staff's story name does not invent its rules mechanics.

Review and tune the draft before accepting it: bosses/support creatures, room defenses, finite patrols,
keys/bypasses and treasure are DM decisions. A dragon's lair can have exceptional threats and a
specifically chosen hoard; structural readiness does not certify balance. Change many keyed entries
in one `revise_map_preparation` call. Pin important boss/reward commitments early; later realization
preserves them, and publishing does not grant their loot to the party.

Locks, secrets and explicitly declared `kind:TRAP` objects receive saved GM check guidance using a
DC 15 fallback. Override per feature before acceptance, for example
`challenges: {pick_lock: {difficulty: 25}, force_open: {difficulty: 20}}` on a hoard chest. Check specs
use `resolve_check` fields (`difficulty`, `kind`, `ability`, optional `skill`/`tool`). Finding,
unlocking, forcing and disarming are separate approaches. Force-open guidance is only added when
authored; a locked chest does not invent a trap. Party level does not change saved difficulties.
The defaults fill missing guidance without replacing authored values, bosses, loot or quantities.

These specifications stay in GM context. Use them for `resolve_check` after adjudicating tools,
access and time, then record the outcome with gameplay tools. They do not automatically execute
traps, discover secrets or unlock objects. See [DM tuning and examples](DUNGEON_MAPS.md#dm-tuning-before-publication).

`validate_map_preparation` reports required gaps, optional detail and repair paths. Publish the
blueprint with `publish_map_preparation` to accept its commitments. Its returned room/passage/source
references remain stable when geometry is added later. Draft actors and rewards are not live opponents
or inventory. Accepting the semantic blueprint creates the specified places and planned reward sources;
playable publication resolves actual actors and items once.

Near approach, use `revise_map_preparation` to supply the detailed layout and contents. Keyed arrays
patch their matching `key`; they do not require resubmitting the entire site. Supply any reward's
`item_ref` through installed or registered custom content before playable publication. For example,
use the reference returned by `define_content` for the ceremonial staff rather than guessing one.

Two authoring approaches share the same draft and validator:

- Supply the complete design: room descriptions/attributes, levels, `rect_ft` or `polygon_ft`/`holes_ft`,
  doors, physical object placements, existing character references or installed creature definitions.
- Request `generate_layout: true` as an optional basic fitting helper, then revise the result. It may
  fill unspecified rooms and geometry for authored connections; it must not invent semantic passages
  or stairs to change the site's topology. Missing connections remain authoring gaps. Complex authored
  connections need explicit opening/corridor geometry; validation identifies those repairs.

Playable proposals have `stage: SCENE` and physical `geometry.bounds_ft: [x,y,width,height]`. Room
records remain linked to their canonical locations. Most meaningful content should be prepared before
entry. Very large sites use linked prepared areas; avoid one enormous image or a single huge proposal.

Local dungeon and scene geometry uses a one-inch precision profile. Beginning or revising a draft rounds
physical coordinate and dimension fields to that precision and returns the resolved proposal. Rendering
and spatial rules use those same saved coordinates. Cuts that collapse after rounding fail readiness;
regional geography keeps its original precision.
Entry into an accepted but unrealized blueprint returns the preparation reference instead of quietly
replacing the promised dungeon with a generic battlefield.

Use fresh operation IDs for new mutations, retain an ID for retries of the identical operation, and
pass the latest `expected_revision` when revising/publishing. A stale source reports a conflict; inspect
the changed facts before explicitly rebasing with `rebase_sources: true`.

## Play on the saved grid

Find the scene with `find_maps` or `get_map_context`. Use `audience: GM` for preparation and authoring
context. Map context defaults to a bounded `SUMMARY`; request `detail: FULL` when the full filtered
geometry is needed. Player-facing narration and images use `audience: PLAYER`.

- `place_map_actors` establishes legal figurine placement during exploration. `x_ft`/`y_ft` identify
  the footprint's southwest origin on the 5 ft placement lattice. The displayed 5 ft or 10 ft grid
  does not change the physical positions. Give existing character references, never newly invented IDs.
- `preview_spatial_action` inspects a proposed move without spending time or movement. Player previews
  use known geometry; actual movement can stop at a newly discovered obstacle or event.
- `move_map_actor` performs exploration movement. During initiative use `perform_encounter_action` so
  movement budgets, reactions, range and cover apply. `start_encounter` reuses prepared detail or
  prepares compatible local coverage at the actual location.
- `use_map_transition` follows a saved stair/ladder/portal during exploration, from within reach.
  During combat use `MOVE` with `{portal: key}` so turn costs and reactions remain authoritative.
- `observe_map` saves party observations. Shared reveal combines party members' sight; attacks still
  use the acting character's own perception. Enemy decision context comes from `get_actor_perception`.
  Use `emit_map_sound` for authored sound cues; it conveys appropriate hearing evidence.
- `update_map_object` records a resolved consequence with its reason: an opened door, smashed table,
  breached wall or repair. Resolve applicable actions/checks first. A generic edit is not a replacement
  for inventory, action costs or an explicit audited correction.
  Use the canonical GM key, such as `object:treasure-chest` or `office-door`. Unique bare object/reward
  keys also work; exact canonical keys take priority and ambiguous aliases require an explicit key.
  The response's `canonical_key` identifies the affected feature.
- `correct_map_geometry` repairs a published structural mistake outside a running encounter, with the current map revision and
  a reason. It preserves map/room/passage identities, actors, live object state, loot and discovery;
  corrections that invalidate occupied footprints are rejected. This is distinct from republishing
  a preparation or undoing play with a checkpoint.

Rooms retain damage and depleted loot. Use canonical inventory transfers to take items; do not grant
another copy merely because an item appears on a map. Returning, re-rendering and fresh conversations
reuse the same world. Checkpoints restore the corresponding geometry, placements and knowledge.

`render_map` defaults to PLAYER-safe names and directions in its text, with numbered character markers.
Structured `markers` supply references and exact `move_origin_ft` coordinates for tool calls. GM text
includes those technical details; `options.technical_text` explicitly chooses either text format.
Use `include_image:false` for text-only clients. Technical metadata is control data, not narration.

For a correction, first call `get_map_context` with `audience: GM` and retain its `map_revision`.
Then supply the existing keys and only the geometry that was wrong:

```json
{
  "operation_id": "repair-office-door",
  "campaign": "campaign:1",
  "map": "map:2",
  "expected_revision": 7,
  "patch": {"doors": [{"key": "office-door", "from_ft": [20, 5], "to_ft": [20, 10]}]},
  "reason": "Correct the doorway onto the shared wall and placement lattice."
}
```

The patch can correct room shapes, door segments, portal endpoints/costs, bounds, walls or terrain.
It cannot change semantic identities, levels, loot ownership, HP, locks or actor positions. Unsupported
geometry, stale revisions and corrections that strand an actor or an intact placed object are rejected
atomically. Re-read and fix the proposal instead of restoring a checkpoint just to edit the map.

`perform_encounter_action` with MOVE, STAND or a Bonus Action cast keeps the turn open when `end_turn` is omitted. End it
explicitly when the actor is done. If a PLAYER response hides the current turn, follow its `gm_handoff`
query with `audience: GM`; use that private state to run the enemy and its own perception to choose
targets. Player knowledge must not determine which enemies the DM can run.

An Action is spent once per turn. Continue only the remaining attacks of that same Multiattack/Extra
Attack, or request a legal paid Light Bonus Action attack. Holding a Light weapon does not authorize
another ordinary Attack action. Non-Ironman encounters save an `ENCOUNTER_RETRY` checkpoint.

New published preparations take priority over older provisional coverage of the same place.
`start_encounter.options.map_ref` explicitly selects compatible coverage at the current location and
migrates participating figures, including NPCs. Supplying changed `options.scene` authors/refines an
incidental scene; only the same incidental room scope can be superseded. A named cellar sibling does
not replace a published tavern. Historical boards remain saved. An unrelated location cannot be reached this way.
Use `correct_map_geometry` for an existing published map. Do not travel away merely to release figures.

During exploration, `place_map_actors` can explicitly enter another compatible same-location board
peacefully, preserving the current authored room. Use movement through doors and passages to enter
another room. Bootstrap's `current_board` identifies the controlled figure's actual board. An audited
NPC SET_LOCATION clears an incompatible old figure; an active combatant must first leave the encounter.
Use `effect.clear_map_position:true` with an audited same-place SET_LOCATION when the intention is
to release a stale figurine explicitly. Compatible same-room NPC board changes preserve their
physical room even if their broader location still names its parent. A plain encounter start
refuses participants already placed on different boards; resolve the intended placement first.
Combat completion reports retained `party_positions` and `current_board`.
Corpses remain marked but do not block creature movement. Living figurines still hold narrow doorways.
Fresh opposing deployments prefer 30ft separation where the map permits; explicit/saved placements win.

Published room AREA locations join travel at their containing site, retaining the authored route,
duration and risk policy. Returning to the site's parent is a free local hop where no explicit
passage constrains it. `authorized_route:true` does not replace an existing authored road or its locks.

`end_encounter` saves one mechanical digest and returns up to eight `highlights_needing_narration`.
Their brief summaries and annotation keys share a 2,000-byte compact UTF-8 JSON budget, reported in
`highlights_needing_narration_bytes`/`highlights_needing_narration_max_bytes`. Full mechanics remain
in the saved GM record, available through the reply's `full_gm_record` pointer.
Fill memorable slots once with `annotate_encounter`, for example
`narrations: {"log:123": "The captain's blade rings against the altar."}`. Optional `action.narration`
can capture a beat as it happens; keep it to about 300 characters and a handful per fight. Optional
`end_encounter.narrative` preserves the account of the fight; `summary` provides a concise account.
Annotation updates the same record/ledger event and does not repeat XP or combat. Use GM encounter
context to recall the full record and keep hidden facts out of player narration.

`query_timeline` returns a compact `encounter_digest` and `full_gm_record` retrieval pointer.
Chronicle detail is readable prose. Public digests omit initial rosters, HP and nested mechanics;
resource changes reflect actual differences. Full GM records retain the mechanical detail.

Ordinary attacks on downed same-side members return care guidance before spending dice/resources.
Only explicitly intended hostility should use `confirm_hostile_ally:true`. A melee opportunity
attack's TAKE choice supports `nonlethal:true`. Automatic HIDE concealment is unavailable; resolve
Stealth and exceptional GM adjudication explicitly rather than relying on a false hidden flag.

An encounter already running before encounter records were introduced has no initial snapshot. Its
record marks that baseline unavailable and omits resource/map deltas that cannot be established.

Sound cues accept `emit_map_sound` with a placed `actor` instead of coordinates; its saved center and
level are used atomically. Sources do not hear themselves; dead, deafened and unconscious listeners
are excluded. Place/observe/transition replies are bounded summaries. Retrieve detailed permitted
geometry with `get_map_context` and `detail: FULL` when needed.

Stabilization now saves one recovery roll and deadline. Advancing the campaign clock can restore 1 HP
after that delay without rerolling on reload; intervening damage, healing or death changes eligibility.
An old campaign's already-stable character with no saved recovery deadline needs one explicit STABILIZE
to establish it, because the earlier implementation retained neither roll nor deadline. Rest still
requires positive HP. Recovery leaves the character Prone until they stand; it does not erase conditions
merely because time passed.

Outside combat, use `apply_runtime_change` with `kind: STAND` to get up. It uses the shared timed-action
clock, requires movement to be possible, and removes Prone only when that action completes. During
combat use `perform_encounter_action` STAND so the actor's movement budget applies.

During combat, item use likewise goes through `perform_encounter_action` USE_ITEM, which spends
the appropriate Action or Bonus Action. Runtime USE_ITEM is for exploration and cannot bypass a turn.

Dungeons default to DARK. Author `illumination: BRIGHT` or `DIM` for lit scenes; prose descriptions
of light sources alone do not generate illumination. After publication, use audited
`correct_map_geometry` with the current revision, a reason and either
`{illumination:"BRIGHT"}` or `{rooms:[{key:"altar",illumination:"DIM"}]}`. Lighting-only corrections
are permitted during combat; structural corrections remain outside combat. This adjudicates ambient
light and does not automatically simulate a torch, cast a spell, or spend an Action. Room light overrides
the map default. A second published realization warns about existing maps; `find_maps` identifies
the current party board, its figures and the other realizations.

Remembered objects outside current sight are
marked `last_known`; object memory remains level-filtered. Regional current season follows the
campaign calendar and retains a conflicting authored season as provenance.

During travel, bootstrap reports a JOURNEY position with origin, destination, anchor and progress,
instead of presenting the departure room as the current place. A free room-to-parent move reports
COMPLETED with zero time while preserving compatible figurines and canonical rooms; the requested parent
is a change of focus, not a physical exit. Movement between sibling rooms still requires a passage.
Unaided travel refuses incapacitated or immobile movers before spending time. Heal or resolve their
condition, then resume the saved journey.
Dead/departed saved travellers are removed with named reasons in `travel_roster` and
`removed_travelers`; their contact figures remain. Resume the same destination with explicit
`move_party.characters` to narrow its saved travellers without resetting route, progress or risk.
Living incapacitated travellers still require recovery unless explicitly omitted. A truly empty
eligible roster closes the journey CANCELLED without spending time. Party focus follows the
controlled character, including companion-only trips. `get_party` shares bootstrap's journey view;
movers have `location:null`, JOURNEY `location_context` and provenance in `departure_location`.

For a new trip, an explicit `characters` selection starts at those characters' shared actual location.
This allows a companion elsewhere to travel to the player without moving the player. Mixed origins
are refused; the selection must already be together. A saved roadside contact can depart along the
appropriate portion of its existing road, paying the actual travel time. Room passages and road locks
still constrain departure. A new origin is distinct from narrowing an already saved trip.

Automatic encounter deployment stays in the character's place or descendants and its traversable entry
component; separation preferences cannot cross a closed boundary. The reply names rooms in
`party_deployment`. Saved journey movers use the contact location rather than the departure room.

Bootstrap, timeline and MCP `end_encounter` abbreviate encounter digests above 2,000 bytes of compact
UTF-8 JSON, mark abbreviation `truncated`, and report the measured bytes and limit beside the digest.
Highlights are trimmed individually. The end reply includes a `full_gm_record` read pointer instead of
the full mechanical record. Historical reads work from any board and use saved end-time knowledge;
new completions save final map, level, origin and footprint. PLAYER history retains the party's own
final positions even when everyone is unconscious, plus other positions actually seen at that time.
Later membership or control changes do not reveal old unseen positions. Older missing coordinates
remain explicitly unavailable. Saved detail and
chronicle prose remain available. Bootstrap keeps up to eight recent events and at most two
encounter digests, sharing 3,000 compact UTF-8 JSON bytes; other fights retain summaries and read pointers.

GM SUMMARY map context includes bounded room keys, names, levels, location references and effective
illumination; FULL adds detailed geometry. Actual lighting corrections create a GM_ONLY
`MAP_ILLUMINATION_CHANGED` ledger event with the reason and before/after state.
Timeline summaries name resulting map/room illumination and expose a bounded correction reason.
For published actors, explicitly author `footprint_ft` for larger creatures (10 Large, 15 Huge,
20 Gargantuan); preplaced actors currently default to 5 ft.

### Let companions act, or take detailed control

The DM controls companions' movement, actions and reactions by default. Handle their ordinary turns
and sensible exploration movement without asking the player to choose every tile. Broad orders
such as "Mira, cover the stairs; everyone else follow me" guide the DM's decisions without changing
who controls the characters. Continue using each actor's legal movement/actions and own perception.
Holding an occupied five-foot doorway is an intentional tactical choke point. Enemies and allies
must pass through the available space one at a time. A companion ordered to hold it does not
automatically step aside; yielding or changing formation is a deliberate movement decision.
Save standing orders in existing NPC agenda/memory state when they should survive a session boundary.

Use `set_tactical_control` only when the player explicitly takes or releases detailed control:

```yaml
# "I want to control Mira for this fight."
operation_id: control-mira-this-fight
campaign: campaign:1
controller: PLAYER
characters: [character:3]
scope: ENCOUNTER
# encounter may be omitted to select the running encounter
```

Omit `characters` to set a policy for all current and later companions in that scope. Omit `scope`
to select `ENCOUNTER` during a running encounter or `UNTIL_RELEASED` otherwise. Explicit
`UNTIL_RELEASED` preserves the preference
across encounters. "DM, take over Mira again" uses `controller: DM` for the same character and scope.
An encounter-scoped change expires when the encounter ends, restoring any earlier persistent
preference. To hand back persistent control immediately, including in the running fight, explicitly
use `controller: DM, scope: UNTIL_RELEASED`. Check the effective controller in `get_party`, bootstrap
or encounter context after resuming or restoring a checkpoint.

Pause for player-controlled turns and reactions and meaningful shared choices. Under the normal
`npc_reactions: AUTO` policy, player-controlled companions' reaction choices go to the player;
DM-controlled companions retain normal NPC reaction handling. `ASK` still requests all NPC reaction
decisions. Tactical control leaves companion dialogue, personality and loyalties with the DM and
does not change the player's primary character (`transfer_player_control`). The DM still issues
individual movement/action calls; this feature does not add an automatic follower or group move.

## Draw a view and customize its materials

`render_map` returns PNG content, a text alternative and a physical/image transform. Set
`include_image: false` in a text-only client; this requests the text description without PNG/asset I/O.
Image display depends on the harness; graphical marker
dragging and an interactive map editor are not required for play.

Renderer options include `grid_ft: 5|10`, `max_pixels` up to 2048 and styles `DEFAULT`, `PARCHMENT` or
`HIGH_CONTRAST`. The tooling chooses image dimensions and texture normalization. Supported bundled
material names include `STONE`, `ROUGH_STONE`, `DAMP_STONE`, `COBBLESTONE`, `DECORATIVE_TILE`, `WOOD`,
`DARK_WOOD`, `CARPET`, `EARTH`, `MUD`, `SAND`, `GRASS`, `FOREST`, `WATER`, `ICE`, `SNOW` and `VOID`.

### Final graphics pass, prop catalog and mood lighting

Read `rpg://maps/catalog` for the built-in general props, options and lighting presets. Tactical
furnishings with cover, collision, locks or inventory belong in `objects`; non-blocking visual set
dressing belongs in `decor` (rugs, tucked chairs, wall sconces, floor cracks, bushes, banners); purely
visual mood light pools belong in `lights` (`{key, position_ft:[x,y], level, preset|color, radius_ft,
intensity}` — mechanical vision still uses map/room `illumination`).

Props support `prop` (for example `table_rect` with `seating:"chairs"|"benches"` or `seats:4`,
`table_round` with `seats:4`, `chair`, `stool`, `bench`, `bar_counter`, `hearth`, `bed`, `bookshelf`,
`rug`, `crate`, `barrel`, `chest`, `pillar_round`, `pillar_square`, `altar`, `brazier`, `torch_sconce`,
`lantern`, `statue`, `fountain`, `sarcophagus`, `rubble`, `pit`, `tree`, `pine_tree`, `bush`, `boulder`,
`campfire`, `tent`, `wagon`), `rotation_deg`, `tint` and `clutter:true`. Before publishing a realized
`SCENE`/`DUNGEON` preparation, inspect it with `render_map` on `preparation:N` (`audience: GM`) and use
`revise_map_preparation` to add the details and lighting that establish mood. Outside combat,
`correct_map_geometry` can also patch `decor`, `lights` and room `material` on an existing map.

For a custom material, call `import_map_asset` with a server-readable PNG path and `repeat_ft`. Assign
the returned `asset.ref` (for example `asset:12`) as the material on the selected draft room/shape.
This pins its physical repeat scale. A raw content hash is accepted only when unambiguous. A render
can also use `options.material_overrides: {"STONE": "asset:12"}` without changing the saved geometry.
The imported asset is
copied into campaign storage; deleting the original file does not change the saved appearance.
Image generation can be supplied by the client or another provider, followed by the same import.
Ordinary map creation/rendering needs no external image service. This tool imports PNG files; a remote
client must make its source available on the server before import.

`export_map_bundle` writes a managed `.rpgmap.zip` containing the GM authoring snapshot and required
artwork. It can contain private content; use a PLAYER rendering for a player handout. Use
`import_map_bundle` with explicit `source_ref_mappings` where existing references must be resolved.
Import stages a reviewed draft and immutable assets; validate and publish separately. It does not
grant inventory, create live occupants or replace campaign checkpoint restoration.

## Build and extend the regional world

Campaign setup accepts a complete authored `adventure.world_map`. An authored place marked
`opening: true` reuses the actual opening location. If no proposal is supplied, setup stages a sourced
regional draft with `AUTHORING_REQUIRED`. It retains established locations and connections; the DM
must supply missing terrain, settlement placement and road geometry before publishing. It does not
invent a fixed set of towns for every campaign. The default latitude guidance is approximately 43.5
degrees north. A structured `adventure.region` can supply `latitude`, `longitude`, `width_miles`,
`height_miles`, `coastal` and `climate`; a complete proposal provides fuller creative control.

The atlas index uses stable numeric map references and readable addresses:

```text
atlas:7[-1,1]   atlas:7[0,1]   atlas:7[1,1]
atlas:7[-1,0]   atlas:7[0,0]   atlas:7[1,0]
```

East increases X; north increases Y. Looking up an absent sheet reports that it has not been generated.
Create its `REGION` preparation with `atlas_ref`, `sheet_x` and `sheet_y`. The response supplies fixed
bounds, saved style and every existing neighbor's geography. Author the new region compatibly and
publish once; read-only lookup does not expand the world. Geographic features use points, winding
paths and polygons in atlas feet. Forest cover, water and relief remain independent properties.

Road/river continuation preserves the same feature key and saved vertices. An explicit
`extend_feature: true` can extend a saved path into the new sheet while keeping its previous geometry.
New sheets validate incoming crossings and widths; water coverage must fit existing coastal edges.
Restyling reuses those accepted paths and never rerolls the geography.

`update_map_annotations` supports:

- `WORLD_POI`: exact `position_ft` (also accepted as `point_ft`), a stable key/name, optional existing
  `location_ref` or `create_location`, and appropriate visibility. Add the castle and re-render the
  saved map instead of requesting a newly invented region.
- `REPORT`: a sourced claim that can retain approximate coordinates or a qualitative description.
  Its position is the party's report, not an automatic correction to canonical geography.
- `NOTE`: party/GM/individual annotations and nicknames with their saved scope.

For old campaigns without an atlas, mutating session bootstrap stages one regional preparation with
existing location/connection evidence and creative guidance. Review the sourced constraints, retain
contradictions/uncertain distances, set `review_required: false` after that review and publish through
the same workflow. Repeated bootstrap resumes the draft rather than duplicating it. This preparation
does not advance fictional time, move the party or convert an active legacy encounter.

## Patrols, risk and interruptions

Use `define_patrol` for existing actors and their timed waypoints. The roster is finite: dead, captured
or unavailable actors are not regenerated by another patrol check. `update_patrol` records a pause,
completion or explained resumption, and `get_patrol_state` supplies GM duty context.
Temporary immobilization or incapacitation pauses the existing duty with its remaining delay. After
recovery, use an explained `update_patrol` resumption; it retains the survivors and creates no replacements.

Regional timers run only while any party member is present. Leaving with the final member pauses
remaining time; returning resumes without catching up for time elsewhere. Split parties keep each
occupied region active. The global calendar still progresses.

`define_travel_risk` associates story-aware danger with a place/route. Frequency, eligible opposition,
severity and authored/random placement are separate choices. `move_party`/`continue_journey` stop at
actual events or contacts. Inspect `get_travel_state`, resolve the opportunity through discovery,
avoidance, negotiation or grid combat, then use `resolve_travel_opportunity` and continue the retained
journey. Short calls and reloads preserve exposure and resolved checks.

`perform_timed_action` saves partial searches/waits/work; retain its action key to resume. Patrols or
scheduled events can interrupt before completion benefits. Treat returned elapsed time and outcomes
as authoritative instead of narrating the party at its destination before an interrupted journey ends.

## Current boundaries

The first implementation uses headless PNGs, conversation-driven tools, bounded map proposals and a
regional geographic frame. It does not supply a visual editor, automatic image-provider setup,
a full globe/polar projection or a general 3D physics simulation.
Native packaging/rendering validation is tracked separately from JVM service tests; consult release
validation rather than inferring native results from this guide.
