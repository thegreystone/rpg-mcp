# RPG MCP Server — Design

**Status:** Initial design synthesized from the design discussion  
**Primary goal:** Build a persistent, rules-aware RPG engine exposed through MCP, with an AI agent acting as the game
master (GM/DM), narrator, world-builder, and interpreter of player intent.

The central design principle is:

> **The AI improvises the fiction; the engine owns the truth.**

The language model should be free to tell a compelling story, create characters, improvise scenes, interpret ambiguous
player actions, and adapt the adventure. Mechanical state—HP, XP, inventory, money, spell slots, conditions, encounter
state, dice results, and other rule-governed facts—must live in the server and be persisted independently of any chat
session.

---

## 1. Vision

The end experience should feel less like operating a game engine and more like participating in an interactive audiobook
or tabletop RPG.

The player talks naturally to the GM. The GM narrates the world, voices NPCs, presents choices, reacts to unexpected
decisions, and calls MCP tools whenever authoritative game state or deterministic mechanics are required.

A future voice-capable agent should be able to run the entire experience conversationally while the MCP server silently
maintains the game underneath.

The system should support:

- Long-running campaigns across many AI/chat sessions.
- Persistent player characters and parties.
- Rule-governed combat and encounters.
- Dynamic, AI-generated adventures that still maintain continuity.
- Rich NPC personalities and evolving relationships.
- Character progression and level-up choices.
- Persistent world state.
- Semantic maps that become more detailed as they are explored.
- Different campaign styles and content profiles.
- Multiple AI providers without coupling the game engine to one provider.
- A clean, freely distributable rules/content core.

---

## 2. Non-goals

The MCP server is **not** the narrator.

It should not attempt to replace the AI with a traditional dialogue tree, procedural story generator, or rigid CRPG
scripting engine.

Likewise, the AI should not become the authoritative database.

Chat history must never be required to reconstruct the canonical state of a campaign.

The initial project should also avoid:

- Requiring a graphical client for play.
- A large bespoke fantasy setting.
- Reimplementing protected Dungeons & Dragons campaign settings or non-open content.
- Premature distributed/server infrastructure.
- A complicated database deployment.

---

## 3. Architecture

The system consists conceptually of five layers.

### 3.1 Game Harness

The **Game Harness** controls the lifecycle of a game.

It is essentially a state machine that moves through:

1. Campaign creation
2. Adventure preferences
3. Character creation
4. Party design
5. Campaign commit
6. Runtime gameplay
7. Session suspension/resumption
8. Campaign completion or termination

The harness should make it possible for an AI agent to determine exactly what phase the game is currently in and which
operations are legal.

### 3.2 AI Game Master

The AI is responsible for:

- Narration.
- Dialogue.
- NPC portrayal.
- Interpreting player intent.
- Scene construction.
- Adventure pacing.
- Creating encounters.
- Selecting appropriate creatures/opponents.
- Awarding discretionary XP where campaign rules permit it.
- Adapting planned story beats to unexpected player actions.
- Developing relationships.
- Generating new world detail as it becomes relevant.
- Deciding when persistent narrative events should be recorded.

The AI does **not** directly invent mechanical outcomes that the engine can determine.

### 3.3 Rules Engine

The rules engine owns deterministic mechanics, including:

- Dice.
- Ability checks.
- Saving throws.
- Attack resolution.
- Damage.
- Healing.
- HP.
- Conditions/effects.
- Initiative.
- Encounter rounds and turns.
- XP.
- Level thresholds.
- Inventory.
- Encumbrance.
- Money.
- Equipment.
- Spell resources and other class resources.
- Rest and recovery.
- Death/dying mechanics.
- Mechanical validation.

The initial ruleset should be based on openly licensed SRD material rather than protected D&D content.

### 3.4 Campaign/World Engine

This layer maintains the persistent fictional truth:

- Campaign state.
- Adventure outline.
- Story beats.
- Quests.
- Factions.
- Locations.
- NPCs.
- Relationships.
- World clock/calendar.
- Discovered information.
- Secrets.
- Semantic maps.
- Creative initial region generation and continuing map development.
- Events and history.

The Campaign/World Engine owns the initial world-map workflow during campaign creation. Creative
authoring can come from the AI GM's proposals and seeded generation, guided by the campaign premise
and player preferences. The engine validates spatial structure, assigns stable identities, persists
the resulting geography and renders its map views.

### 3.5 Persistence

All committed game state survives process restarts.

The first implementation should favor a simple local persistent database. SQLite is the natural default.

The database is the canonical source of truth, not MCP conversation state.

---

## 4. Campaign Lifecycle

### 4.1 Campaign Creation Wizard

Creating a campaign is deliberately interactive.

The AI should interview the player rather than require a large configuration form.

For almost every question, a valid answer is:

> Surprise me.

This lets players control anything from virtually nothing to almost everything.

The wizard gathers enough information to create:

- Campaign configuration.
- Adventure seed.
- Initial world/region map.
- Player character.
- Initial or planned companions.
- Tone/content configuration.
- Rules options.
- Continuation/death policy.

### 4.2 Adventure Designer

The player may influence the kind of fantasy they want to experience.

Possible dimensions include:

- High fantasy vs. low fantasy.
- Dark vs. optimistic.
- Exploration.
- Mystery.
- Political intrigue.
- Adventure/action.
- Horror.
- Humor.
- Romance.
- Character drama.
- Epic/world-threatening stakes vs. personal/local stakes.
- Preferred environment or setting.
- Desired themes or fantasies.

None of these need to be specified.

If unspecified, the GM creates them — starting from a definite default. The default experience is a **Baldur's
Gate-style fantasy epic** (`FantasyStyle.EPIC`): a world-threatening plot, a party of vivid companions with their own
pasts, secrets and agendas, romance that develops naturally, hard moral choices with lasting consequences, and a
world where almost anyone can die. The setup interview says so when it asks about the flavour of fantasy, so the
player knows what "surprise me" or skipping the question gets them, and the committed campaign records the style
explicitly. A player who describes the flavour in their own words is never overridden by the default.

The reference is deliberate. Baldur's Gate III is one of the most successful role-playing games ever made — the first
title to win Game of the Year at all five major award ceremonies, well past twenty million copies sold — and what
critics singled out is exactly what this engine is built to deliver: companions whose stories were "the main
motivation for playing", romances that develop naturally and are "unabashedly sexual", player freedom over tidy
quest resolution, and decisions that carry weight because "almost every character could die". Its rating is
PEGI 18, and the default epic is written for that profile (§22); lower profiles play the same epic with the mature
material scaled down.

Like `ContentProfile`, each `FantasyStyle` carries a `guidance()` string that is returned with the campaign context
every session (`experience_guidance`), so the GM is held to the style while running, not only told about it at setup.

Adventure design also produces a creative initial regional map through the Campaign/World Engine.
It establishes the opening location, a few key cities or villages, their geographic relationships,
routes/distances, major terrain and useful adventure landmarks. The region should be broad enough for
meaningful travel and several settlement destinations, with its extent chosen for the campaign premise.
Create the initial cities, towns, villages and key road network during this setup, with stable names,
positions and connections that support the opening narrative and its relationships/tensions. Normally
the main road continues beyond one or more land edges, suggesting a larger world. Hills, mountains,
valleys and passes shape its routes; roads go around ridges where practical and use plausible crossings
where going over them makes sense. A wholly enclosed island can connect outward through harbor/sea routes.
Defaults and "surprise me" provide a complete starting region without requiring the player to specify
every place or measurement. The player can revise this setup draft before the existing campaign commit.

### 4.3 Character Designer

Character creation is a first-class interactive workflow.

The player can specify or discover:

- Name.
- Species/ancestry where supported by the ruleset, including its special traits and lineage/ancestry choices.
- Background where supported by the ruleset (SRD 5.2.1: three-ability increase, Origin feat, two skills, a tool
  proficiency, equipment).
- Class.
- Sex/gender/presentation as relevant to character identity.
- Age.
- Appearance.
- Height.
- Weight.
- Background.
- Personality.
- Motivations.
- Ideals.
- Flaws.
- Relationships.
- Alignment.
- Ability scores.
- Starting equipment.
- Spells/abilities.
- Other rule-specific choices.

Character generation should support several modes:

- **Guided:** player chooses everything important.
- **Random:** the engine rolls/generates most characteristics.
- **Hybrid:** player locks selected choices and randomizes the rest.
- **Concept-first:** the player describes a fantasy character and the AI proposes a mechanically valid implementation.

Ability-score generation should be configurable, including at least:

- Standard array.
- Point buy.
- 4d6 drop lowest.
- Other campaign-defined methods.

Alignment can be selected directly or suggested by the AI after a short personality interview.

### 4.4 Party Designer

The player may optionally influence companions before the adventure starts.

They might request:

- Specific classes/roles.
- Personality archetypes.
- Background relationships.
- A rival.
- A potential best friend.
- A potential romantic interest.
- A comic or eccentric companion.
- Particular interpersonal tensions.

The player does **not** need to specify any of this.

When generating companions automatically, the GM should deliberately create characters with enough personality,
differences, quirks, goals, strengths, weaknesses, and relational potential to support long-term character-driven
storytelling.

Potential relationships are seeds, not predetermined outcomes.

Friendship, rivalry, trust, resentment, loyalty, and romance should emerge from actual play.

### 4.5 Companion Introduction

Configured companions do not necessarily begin in the party.

The adventure outline can specify:

- Starts in party.
- Planned introduction.
- Unknown future introduction.
- Conditional introduction.

This allows two campaigns created from similar preferences to begin very differently.

A planned meeting is a **story intention**, not a rigid script.

If player decisions make the intended introduction impossible, the GM should adapt the plan and create another plausible
opportunity.

### 4.6 Commit Boundary

Campaign creation has an explicit **commit** operation.

Before commit, configuration can be revised freely.

At commit:

1. The configuration and initial regional-map draft are validated, including location references,
   spatial consistency, route distances and initial party knowledge.
2. Character and campaign state are persisted.
3. Initial adventure state and the accepted region/map are committed together. Persist the actual
   generated geography, identities and creative choices as well as generation provenance.
4. The design conversation is no longer required.
5. Gameplay begins using a fresh runtime context reconstructed from persistent state.

This is a major architectural principle.

The potentially long conversation used to design a character or campaign must not consume runtime context forever.

The initial map view is rendered from that saved state for the beginning of play. Rendering can be
retried or restyled without repeating world generation or changing the accepted region.

---

## 5. Campaign Model

A campaign is the top-level persistent entity.

It should contain or reference:

- Campaign ID.
- Name.
- Ruleset/version.
- Creation date.
- Status.
- Player(s).
- Content profile.
- Rules options.
- Continuation/death policy.
- World.
- Adventure.
- Party.
- Characters.
- Current game date/time.
- Current location.
- Active session.
- Event ledger.
- Checkpoints.

The server must support listing campaigns so that a returning player can say, effectively:

> Continue the second campaign.

---

## 6. Adventure Model

The adventure is an evolving plan rather than a fixed script.

It should contain:

- Premise.
- Background truth.
- Major factions.
- Important NPCs.
- Major conflicts.
- Long-term story arcs.
- Planned story beats.
- Open story hooks.
- Unresolved mysteries.
- Potential companion introductions.
- Known future pressures/events.
- Current chapter/act.
- GM-only notes.

A story beat needs state, for example:

- PLANNED
- AVAILABLE
- BLOCKED
- SUPERSEDED
- COMPLETED
- ABANDONED

The GM may modify the outline as the player changes the world.

The purpose of the outline is continuity and direction, not railroading.

---

## 7. Event Ledger and Campaign Memory

The campaign needs an append-oriented semantic event ledger.

Important events should be stored compactly and structurally rather than merely as prose transcripts.

Examples:

- Character joined party.
- Character left party.
- NPC died.
- Quest accepted.
- Quest completed.
- Player betrayed faction.
- Relationship changed.
- Important secret discovered.
- Item acquired/lost.
- Major location discovered.
- Story beat completed.
- Character leveled up.

An event should include approximately:

- Event ID.
- Campaign ID.
- Game timestamp.
- Real/session timestamp where useful.
- Event type.
- Actors/entities.
- Location.
- Compact summary.
- Structured payload.
- Visibility.
- Importance.
- Causal/related event references where useful.

The ledger has two independent orderings:

- **Insertion order** — the incremental event ID, assigned on append and never rewritten. This is the authoritative
  order of *recording*.
- **Fictional order** — the game timestamp. Timeline queries and narration sort by this.

The two may diverge: events can be recorded retroactively (flashbacks, lookbacks, backfilled history established during
play), receiving a later ID with an earlier game date. That is normal, not a conflict.

The ledger gives the GM a low-token timeline of what actually happened.

---

## 8. Visibility and Knowledge

The system must distinguish truth from knowledge.

Information may be:

- GM-only.
- Known to the player.
- Known to the entire party.
- Known to selected characters.
- Known to a faction.
- Public world knowledge.

This is essential for mysteries, deception, hidden motivations, secret relationships, and dramatic reveals.

The MCP interface should make accidental disclosure of GM-only information difficult.

---

## 9. Characters

Characters include PCs, companions, NPCs, and opponents.

A character has stable identity plus mutable state.

### 9.1 Stable/Slow-changing Data

Examples:

- Character ID.
- Name.
- Description.
- Appearance.
- Background.
- Personality.
- Class(es).
- Level.
- Ability scores.
- Proficiencies.
- Maximum HP.
- Relationships.
- Goals.
- Alignment.
- Traits/abilities.

### 9.2 Runtime State

Examples:

- Current HP.
- Temporary HP.
- Conditions.
- Active effects.
- Spell slots/resources.
- Exhaustion or equivalent.
- Inventory.
- Equipped items.
- Money.
- Current location.
- Encounter status.

The engine must make the distinction between base values, derived values, and current values explicit.

---

## 10. Party

A party is a dynamic collection of characters.

Characters may:

- Join.
- Leave.
- Temporarily separate.
- Be dismissed.
- Betray the group.
- Die.
- Rejoin later.

Party membership must therefore be modeled as state/history, not embedded permanently in character definitions.

The system should distinguish:

- Player-controlled characters.
- GM-controlled companions.
- Temporary allies.
- Guests.
- Other encounter participants.

### 10.1 Companion tactical control

The DM plays companions by default, including their movement, actions and reactions. The player can
give a broad intention or a specific order without taking over every subsequent decision. "Mira,
cover the stairs" leaves Mira under DM control; the DM chooses sensible positioning and tactics
from her abilities, perception, personality and the situation.

Detailed control is an explicit, saved preference, separate from the primary player-character
assignment. "I control Mira this fight" grants the player her tactical choices for that encounter.
"Let me control the companions until I say otherwise" grants persistent control. "DM, take over"
releases the selected companions. `set_tactical_control` records these changes; omitting the
character list sets a policy for all current and later companions in that scope. The default scope
is the running encounter when there is one, otherwise `UNTIL_RELEASED`. Encounter-scoped preferences
expire when that encounter
ends. Preferences survive reloads and checkpoint restoration.
An encounter-only handback leaves an earlier persistent preference available for later fights.
An explicit `UNTIL_RELEASED` change takes effect immediately, including during the running encounter.

The primary player character remains player-controlled. `transfer_player_control` changes that
primary identity; temporary tactical control does not. Companion personality, dialogue, loyalty
and narrative agency remain with the DM under either tactical controller. Every actor still uses
their own initiative, resources, movement budget and perception.

The DM resolves routine companion turns and exploration movement without asking the player for
each square. Pause for the player's own turn, an explicitly controlled companion's tactical or
reaction decision, or a meaningful shared choice. The normal `npc_reactions: AUTO` policy follows
the assigned controller; `ASK` can still request all NPC reaction decisions. Saved control context
in party, bootstrap and encounter views lets a fresh conversation continue the same arrangement.
These preferences guide the DM's tool use; automatic companion AI, path following and group
movement batching are separate features.

---

## 11. Relationships

Relationships deserve a first-class model rather than being hidden entirely inside prose.

A relationship can contain dimensions such as:

- Affection.
- Trust.
- Respect.
- Attraction.
- Fear.
- Resentment.
- Loyalty.

Not every implementation needs numerical values exposed to the player.

The important requirement is persistence and continuity.

Relationship changes should normally arise from events and behavior, not arbitrary story declarations.

The GM may also store compact qualitative notes, for example:

> Mara respects the player's courage but increasingly distrusts their recklessness.

Relationship state should also retain references to the semantic ledger events that materially shaped it. The compact
current-state summary is optimized for routine context loading; the linked events preserve the narrative evidence behind
that summary.

For example, a marriage relationship might reference the proposal, acceptance, wedding, major conflicts,
reconciliations, and other defining moments. If the player later asks an NPC, "Do you remember when I proposed?", the GM
should be able to retrieve the relevant event and recover at least its persisted summary and important details rather
than relying on the current relationship label alone.

This creates two complementary memory layers:

- **Relationship state:** compact, current, token-efficient summary of how the characters relate now.
- **Relationship history:** references to important ledger entries that explain how the relationship got there and allow
  significant shared memories to be recalled on demand.

Relationship-event references should be selective rather than exhaustive. Routine interactions do not need permanent
linkage, but identity-defining or relationship-defining events should be retained.

Relationships can exist between any characters, not only between the player and NPCs.

---

## 12. Inventory and Economy

Every character can own or carry items.

Items should be data-driven and identified by stable IDs.

Item definitions may include:

- ID.
- Name.
- Type.
- Weight.
- Value.
- Description.
- Mechanical properties.
- Damage where relevant.
- Armor properties where relevant.
- Tags.
- Rules metadata.

Character inventory state includes:

- Item.
- Quantity.
- Container/location.
- Equipped state.
- Charges/state where applicable.

The engine tracks:

- Carry weight.
- Carrying capacity.
- Encumbrance.
- Money/currency.
- Purchases.
- Loot.
- Transfers.

The GM may narrate commerce, but the engine performs the accounting.

**Decision:** money is stored canonically in the smallest currency unit of the ruleset (copper for the default SRD
ruleset). Denominations (SP, EP, GP, PP) are presentation and exchange metadata, not separate balances. The SRD's fixed
1/10/50/100/1,000 ratios make rendering lossless, and single-unit storage keeps arithmetic, auditing, and the ledger
trivial. Coin weight for encumbrance derives from the same canonical amount.

---

## 13. Spells and Abilities

Spells need both structured mechanical data and explanatory rules text.

A spell definition should include fields such as:

- ID.
- Name.
- Level.
- School/category.
- Casting time.
- Range.
- Target/area.
- Components.
- Duration.
- Concentration.
- Saving throw/attack metadata.
- Damage/healing/effect metadata where structurally expressible.
- Scaling.
- Rules text.

Some spells can be resolved almost entirely mechanically.

Others contain situational semantics that require GM interpretation.

The architecture should support both without attempting to encode every possible natural-language rule as program logic.

---

## 14. Effects and Conditions

A generic effects system is needed for temporary and persistent mechanical changes.

Examples:

- Poisoned.
- Blessed.
- Frightened.
- Invisible.
- Cursed.
- Buffs/debuffs.
- Environmental effects.

Effects may have:

- Source.
- Target.
- Start time.
- Duration.
- Expiration condition.
- Mechanical modifiers.
- Stacking rules.
- Concentration/dependency.
- Narrative notes.

---

## 15. Encounters

An encounter is a first-class state machine.

It must support more than two opposing sides.

An encounter contains:

- Encounter ID.
- Participants.
- Factions/sides.
- Relationships between sides.
- Initiative/order.
- Current round.
- Current turn.
- Terrain/environment metadata.
- Active effects.
- Encounter objectives.
- Encounter status.

Possible side relationships include:

- Allied.
- Hostile.
- Neutral.
- Temporarily cooperative.
- Unknown.

The GM constructs encounters narratively, but the engine validates and executes mechanics.

The AI should be able to ask the engine questions such as:

- Who is participating?
- What round is this?
- Whose turn is it?
- What is everyone's current HP?
- What conditions are active?
- Who is hostile to whom?
- What legal/mechanically plausible actions are available?
- What happened on the previous turn?
- Where is everyone positioned?

Every combat takes place on a saved local tactical grid, whether it starts in a dungeon, on an inn's
steps, in a street or on a forest road. Travel between places uses the location/route graph and journey
progress. A local grid supplies the detailed geometry at that physical location; it does not require
the whole travel map to be gridded. Image display and interactive markers remain optional capabilities.

Before a new combat starts, resolve its exact location, reuse or prepare the relevant tactical scene,
and persist valid terrain and participant positions. Preserve established positions, approach direction,
formation, surroundings and actor identities. An encounter starting partway along a road attaches to
that route position rather than moving to the nearest named location. Render requests remain read-only;
grid preparation is part of the world/encounter preparation workflow and consumes no fictional time.

Use the tabletop DM sequence: lay out the local battlefield, place the player/party and other relevant
figurines, then run combat on that board. Resolve permitted player deployment conversationally or
through markers, preserving established approach/positions and showing only observable opponents.
Routine unambiguous placement does not require an extra confirmation step.

The shared grid view combines the current line of sight of the party members present on the map,
accounting for their positions, occluding terrain/doors, lighting, range and applicable senses. Each
member contributes their visible area; targeting still uses the acting character's own sight. Areas
no longer seen by anyone retain explored memory, and seeing their terrain does not reveal secrets or
otherwise unperceived creatures. Visibility updates as positions, obstructions, lights or senses change.

Enemies/NPCs use their own line of sight, hearing and awareness. Until they detect a party member or
receive a relevant sound/alarm/report, they continue ordinary duties/patrols. Hearing can prompt
investigation without identifying the party or locating every member precisely. Alerted creatures
can search from remembered evidence after losing sight, but do not track further unseen movement.
Communicated alarms have actual recipients/reach; the GM's full map and the party view do not grant
enemies omniscience. Persist relevant awareness and last-known positions with normal world state.

All combat movement, distance, reach, cover, sight and targeting use that grid's canonical physical
geometry. Zone approximations do not decide grid actions. Crossing a scene boundary or moving through
an inn's door can extend/link tactical coverage while preserving the encounter, initiative and action
budgets. After combat, resume graph travel from the actual final position and update route/off-road
progress. See [travel graphs and tactical scenes](MAP_FORMAT.md#travel-graph-with-local-tactical-scenes).

This is the target design; the current implementation still uses zones. Existing saved zone encounters
need a deliberate, validated conversion before continuing spatial combat under the new feature,
preserving HP, initiative, conditions, resolved outcomes and pending actions. Zone labels do not imply
invented exact coordinates. Conversion is journaled/idempotent and separate from rendering or regional
map backfill. New combat cannot silently fall back to zone resolution if grid preparation fails.

### 15.1 GM Overrides

A tabletop GM sometimes deliberately bends mechanics for the sake of the story.

The engine should support explicit GM override operations.

Examples:

- Adjust HP.
- Prevent immediate death.
- Add/remove condition.
- Modify encounter participant.
- Correct an erroneous state transition.

Overrides must never be disguised as normal deterministic resolution.

They should be:

- Explicit.
- Auditable.
- Optionally disabled by campaign/server configuration.
- Recorded in the ledger.

This preserves both flexibility and integrity.

---

## 16. Dice and Randomness

Randomness belongs in the engine.

The GM requests rolls; the server returns authoritative results.

Randomness should support:

- Standard polyhedral dice.
- Compound expressions.
- Advantage/disadvantage where relevant.
- Character generation.
- Random tables.
- Seeded generation where reproducibility is useful.

The GM must not silently replace an inconvenient roll with a preferred narrative result unless an explicit GM override
is permitted and used.

---

## 17. XP and Progression

XP is authoritative engine state.

XP may come from:

- Encounters/combat.
- Quests.
- Milestones where configured.
- Exploration.
- Clever solutions.
- Roleplaying/story awards.
- Other campaign-defined awards.

Mechanically determined XP should be automatic.

Discretionary awards are initiated by the GM but committed by the engine and logged.

### 17.1 Level Up

Level-up is a major transactional workflow.

When a threshold is reached:

1. The engine marks the character as eligible.
2. Gameplay may pause at an appropriate point.
3. The AI explains available choices.
4. The player selects abilities/features/spells/ability increases/etc.
5. The engine validates the proposed choices.
6. The player confirms.
7. The level-up transaction commits atomically.
8. The event is recorded.

NPC/companion level-ups may normally be chosen automatically by the GM.

Player-character choices remain interactive unless the player explicitly delegates them.

---

## 18. Time, Rest, and Calendar

The world has an independent game clock.

The engine should track:

- Current game date.
- Current game time.
- Elapsed time.
- Travel time.
- Short/long rests or ruleset equivalents.
- Timed effects.
- Deadlines.
- Scheduled world events.

Events in the ledger should use game timestamps.

This allows the GM to answer questions such as:

> When did we last meet this NPC?

or:

> How many days remain before the festival?

---

## 19. Quests and Factions

Quests should be persistent structured objects.

They can include:

- Objective.
- Issuer.
- Status.
- Participants.
- Rewards.
- Deadlines.
- Dependencies.
- Hidden objectives.
- Related story beats.

Factions should include:

- Goals.
- Relationships.
- Important members.
- Player reputation/standing.
- Resources/influence where useful.
- Knowledge/secrets.

The world should be capable of changing even when the player is not directly interacting with a faction.

---

## 20. Semantic Maps

Maps have an authoritative **semantic and spatial model** of places, connections, terrain and physical
scale. Rendered maps and text views present that saved model and its appropriate player/GM knowledge.
The Campaign/World Engine creates fictional geography and maintains its continuity as the map develops.

Keep three linked representations: geographic features describe the world's shapes and character,
the travel graph describes connections/distances and traversal, and local tactical grids supply
detailed playable space. Coastlines, islands, river/lake shapes, forests, fields, hills, ridges, passes
and winding road paths remain saved world data for attractive, consistent world-map rendering. A road
can have a cartographic path, connected travel legs and local grid segments, all referencing the same
world feature. Graph connectivity alone does not determine its illustrated bends or surrounding terrain.

Use the location/route graph for travel, with local tactical maps as detailed leaves in the spatial
hierarchy. A leaf can attach to a named place or a physical point along a road/off-road journey. Every
combat uses such a grid; dungeons prepare their relevant leaves in advance, while incidental outdoor
encounters can fill in compatible local detail when needed. Connected/overlapping scenes share world
features and state rather than duplicating geography.

### 20.1 World/Region Map

Create the initial world-map overview during campaign setup, before play begins. Its first useful scope
is a region covering a few key cities or villages, the opening location, connecting travel opportunities
and distinctive terrain, potentially including coasts, ocean and islands. The engine deliberately chooses
creative names, positions, distances and terrain consistent with the campaign's premise and preferences.
Supplied images can guide visual style or layout, while the resulting campaign map is saved as its own
world state. Establish the opening settlements and key roads during setup, supporting the story's
situation while leaving the player several directions to explore. The initial region includes hills
and mountains appropriate to the setting, with their geography reflected in travel choices.

Establish the region's broad structure up front; add settlement interiors, encounters and other local
detail when relevant. Newly materialized detail fits established positions, boundaries and distances.
The atlas can expand into neighboring regions as the campaign grows. Initial party knowledge determines
which known places, reports and geographic features appear on the player map.

Represent:

- Locations.
- Connections/routes.
- Geographic positions, scale and physical route distances, with explicit precision where approximate.
- Travel constraints.
- Regions.
- Important landmarks.
- Roadside stops and crossings.
- Hills, mountain ranges, ridges, valleys and traversable passes.
- Party knowledge, discoveries and sourced reports.

#### 20.1.1 Neighboring regions and atlas expansion

Choose the initial regional map's physical width and height during campaign setup. These become the
sheet dimensions for that campaign's regional atlas. When the player leaves the area in any direction,
the Campaign/World Engine can generate an adjoining map of the same physical size and scale. Road,
cross-country, river and sea travel can all trigger expansion. For example, a road continues at the
matching position on the adjoining sheet, while a sea journey may enter open ocean or reveal islands.
Returning loads the saved region.

Give each saved sheet a stable map reference (for example `map:123`) and an atlas coordinate address
such as `atlas:7[2,-1]`: two sheets east and one south of atlas 7's origin. The initial sheet is `(0,0)`;
X increases east and Y increases north, with negative values extending west/south. Fix the atlas origin
and dimensions so later expansion never renumbers existing sheets. Titles such as "Sarg Coast" are
searchable aliases. Style and detail changes retain the same map identity/address.

Keep a sparse atlas index, expose the current sheet in fresh-session context, and support lookup by
reference/address, neighboring sheet, world coordinate or linked known location. An ungenerated
address can be reported without creating geography, and discovery results respect party knowledge.
See [sheet references and discovery](MAP_FORMAT.md#sheet-references-and-discovery) for the proposed
locator, coordinate ownership, index and export conventions.

All sheets share atlas coordinates and a saved style profile: palette, materials, terrain/building
symbols, typography and renderer settings. A matching output profile produces matching image sizes.
The sheet layout is a cartographic partition; terrain and roads remain freeform and tactical grids
are independent of it.

New regions must match every existing neighbor, including roads, rivers, forest/lake boundaries,
shorelines, land/water connectivity and elevation at shared edges/corners. Keep crossing features'
identities, travel distances and party position continuous across the boundary.
Generate only the needed adjoining region, persist the accepted result and boundary connections, and
leave further expansion for later travel or explicit world building. Rendering an atlas does not
generate additional geography. Normal party-knowledge filtering still applies to the new region.

#### 20.1.2 Creative authoring guidance

Give the authoring LLM actionable inspiration as well as spatial constraints. It should choose a
distinctive geographic premise, let land/water/terrain shape settlement and travel, and use history,
landmarks and deliberate fantasy features to create a believable, attractive region with several
directions for play. Coastlines, oceans, islands and archipelagos are valid choices, and neighboring
maps can vary in settlement density and terrain while sharing the atlas style.

Normally give the initial main road one or more continuations beyond the map's land boundaries. Save
these as actual route crossings for neighboring sheets. Road placement follows terrain: consider valley
routes and practical ridge bypasses, using a mountain pass or another explained crossing when traversal
over/through the barrier is appropriate. Give the pass actual spatial structure and travel properties.
For a wholly enclosed island, connect roads to harbors and outward sea routes rather than forcing a
land road across water. Geography and routes support narrative possibilities without prescribing the
player's itinerary.

Prefer an initial region bordering a coast when it suits the premise and player preferences; inland
openings remain valid. An opening such as "the coastal town of Sarg, next to the Western Sea; this far
south, the water is warm and accommodating" should establish matching geography, direction and regional
climate. Those names and conditions are illustrative creative choices rather than fixed campaign defaults.

Choose the opening position anywhere appropriate to the campaign premise: inland, coastal, on an
island, in a remote location or aboard a vessel. Map centers, atlas origins and example roads do not
dictate the opening. The [world-map authoring brief](WORLD_MAP_GENERATION.md) supplies reusable prompts,
contrasting region examples and a short creative review. Generation tools should provide a concise
version of that guidance with campaign preferences, saved style and all established neighbor constraints.

#### 20.1.3 Existing campaigns and map completion

On resuming a committed campaign whose regional map is missing or incomplete, the Campaign/World
Engine should automatically begin or continue map completion. Analyze the saved location hierarchy and
connections together with relevant descriptions, adventure facts, travel history and existing map
fragments. Preserve established identities, geography, distances, current positions and party knowledge;
creatively fill missing spatial structure using the same world-map authoring guidance.

Fit the atlas to the existing campaign's extent, using several sheets when appropriate. Stage and
validate the completion, then commit it through revision-checked, idempotent campaign mutations and
render/store the result. Repeated resumes reuse the accepted map. A missing rendered image requires
rendering that saved map rather than repeating world generation. Keep uncertain or contradictory legacy
constraints explicit, preserving their sources instead of silently rewriting established facts.

Map completion preserves current gameplay, including time, character location, encounters and pending
choices. It uses the ordinary change journal and preserves existing checkpoints. After restoration,
reconcile with the restored branch's geography and knowledge. The implemented resume workflow stages
sourced map completion for explicit creative review; it does not generate a world inside a database
schema migration. See [map usage](MAP_USAGE.md) for the supported sequence and limits.

#### 20.1.4 Stable geography and restyling

Persist the accepted geographic model independently of rendered artwork. Use complete winding paths
for roads/rivers, polygons for terrain and water boundaries, and stable points/footprints for locations.
Save bends, widths, crossings and connectivity rather than relying on a later model to reproduce them
from endpoint links, prose or a seed. Restyling and different display detail reuse these saved shapes.

Start with vector geometry in the campaign's versioned map data. Optional terrain paint rasters and
numeric elevation fields can supplement it, with explicit physical alignment and a declared source of
truth per layer. Keep land cover, relief and water properties separate: a forested hill expresses both
forest and elevation. Display colors belong to the style rather than defining geographic meaning.

Simplified views preserve significant bends, anchors and atlas seams; closer views add compatible
detail. Meaningful geographic refinements are saved world changes, while textures, symbols, labels and
line treatment are presentation. See the [shared format](MAP_FORMAT.md#stable-geography-and-alternative-renderings)
for persistence, raster and reproducibility rules.

#### 20.1.5 World size, latitude and climate

Assume a roughly Earth-sized globe by default, saving its size, geographic north and equator with the
world definition. Choose the opening's geographic coordinates for the requested climate; ordinary
unspecified adventures default to around 43.5 degrees north, comparable to southern France in latitude.
An explicit setting or established campaign facts override that default, and fictional longitude need
not copy any Earth location.

Climate generally becomes warmer toward the equator and colder toward either pole. From a northern
opening, south leads toward warmth until the equator, then eventually toward a colder southern pole.
Latitude supplies the broad baseline; elevation, coastal/ocean influences, moisture, season and saved
fantasy effects shape local conditions. Preserve accepted climate context for coherent terrain,
settlement life, narration and later map expansion, with gradual transitions over physical distance.
Daily weather remains distinct from regional climate.

The sheet origin `(0,0)` is a local atlas address rather than the equator. Anchor the actual opening
position to the chosen latitude through a saved geographic transform. Keep sheet addresses stable;
record projection extent/accuracy and handle distant, longitude-wrapping or polar travel through the
world frame and explicitly connected atlas coverage. Regional metric coordinates do not imply an
infinite flat world or exact scale over a whole sphere. See the [world-coordinate and climate model](MAP_FORMAT.md#world-size-latitude-and-climate).

#### 20.1.6 Story-aware travel encounters

Travel can produce random hostile encounters, with risk calibrated to the story and saved world
conditions. Hostile country, bandit activity, faction control and recent events affect the relevant
routes/areas; terrain, timing, protection and precautions can modify exposure. Frequency, severity and
opponent selection are separate choices rather than one fixed difficulty for every journey.

The DM can place a story/backstory-linked encounter at a fitting point, use engine-owned random
checks and choose a suitable location for the result, or request a random valid point along the route.
Keep authored events and random outcomes distinguishable. Save checks/exposure across travel calls
and reloads, and make previews read-only. Stop at the encounter's actual location and elapsed time,
prepare/reuse its local grid and retain the unfinished journey. Perception and player choices still
determine how contact unfolds. See [travel risk and encounters](MAP_FORMAT.md#story-aware-travel-risk-and-hostile-encounters).

### 20.2 Settlement Map

Represent nodes such as:

- Inn.
- Market.
- Temple.
- Castle.
- Guild.
- Harbor.
- District.
- NPC residence.

Only important structure needs to exist initially.

Details can be generated and persisted lazily as the player explores.

### 20.3 Dungeon Map

A dungeon combines a tile/piece-based spatial layout with separate, linked room/area descriptions and
live play state. The tactical grid measures physical space; reusable pieces build floors, walls and
passages; room locations provide stable meaning and descriptive content. One room can occupy many
pieces and have an irregular outline. Reusing a piece does not duplicate its room, occupants or secrets.
Room descriptions link to map regions through location references, while doors, objects and actors
have their own anchors and current state. Narration uses observable details and current conditions
rather than exposing a full GM room entry on arrival. See [tile layout and room records](DUNGEON_MAPS.md#tile-layout-room-descriptions-and-live-state).

Normal corridors default to 10 ft clear walkable width. Narrow 5 ft passages and wider halls remain
explicit creative choices, and doorway widths are independent. A 10 ft corridor can therefore meet
a 5 ft doorway choke point. The 5 ft placement lattice and optional 5 ft/10 ft display grids do not
change physical corridor widths or existing saved layouts.

A dungeon is naturally modeled as a graph:

- Rooms/areas.
- Connections.
- Doors.
- Locks.
- Traps.
- Secrets.
- Encounters.
- Objects.
- Environmental state.

The DM handles discovery checks on the player's behalf for traps, secret doors, hidden compartments
and other concealed features. When an eligible observer's approach warrants a roll, the DM initiates
it through the engine using that character's actual abilities and circumstances; the player need not
request or supply the roll. Respect applicable passive detection and active-search time/action rules.
Active searching has a saved actor/area/method/pace, with the applicable movement or time penalty and
detection bonuses/Advantage applied together. Extra time advances active world events and patrols;
ending or interrupting the mode cannot retain free benefits or manufacture fresh attempts.
Narrate only what characters perceive, keep undiscovered features and hidden results out of player
views, and persist resolved opportunities so movement calls or reloads cannot manufacture rerolls.
Discovery is separate from identifying, opening, unlocking or disarming the feature. Companion
tactical-control preferences do not change this DM responsibility.

Support both creative briefs completed with generation helpers and fully model-authored dungeon
designs. The model can choose custom geometry, room layout, contents and story relationships; the
engine validates the proposal, assigns identities, persists it and renders the result. Default pieces
and procedural generation are optional aids. Save the accepted result rather than relying on a seed
or the next model to reproduce it.

Begin with a semantic blueprint or related plot points; exact tiles need not be assigned at that stage.
For example, specify sixteen rooms across two levels, a lower-level altar room, its secret entry to a
treasure room and the ceremonial staff of lightning located there. Room counts, attributes, connections
and loot relationships can be fixed while dimensions, tile arrangements and incidental details remain
open. Accepted world facts constrain later generation; revisable story intentions remain distinguishable.

As the party approaches, flesh out the blueprint and prepare most dungeon structure and meaningful
content before entry so the whole site fits its story. Resolve tiles, object footprints/positions and
connections while preserving room/item identities and specified constraints. Room attributes live on
room records; a locked chest can belong to its room before gaining a precise tile anchor. Materializing
the plan does not create a second chest/staff or expose secret facts to the player.

A local gnoll gang's small house can be fully prepared, including furnishings and occupants. A vast,
multilevel drow Underdark city needs a saved city-wide plan of caverns, districts, levels, major routes,
vertical connections, factions and story sites, with most playable content established at the relevant
scale. Use linked maps and resumable preparation rather than one giant image or a wholly improvised
sequence of rooms. Optional minor interiors and decorative detail can be refined later within those
established constraints. See [creative authoring and preparation](DUNGEON_MAPS.md#creative-authoring-and-preparation-before-entry).

Creative preparation also places loot and establishes occupancy over time: guard posts, patrol
routes, duty windows, alarms and potential reinforcements. Loot has actual location/container/character
inventory ownership, access/discovery conditions and story links. Patrol plans have stable actors or
finite population sources, starting phases, routes, timing and responses to events. Patrol casualties,
capture and reinforcements update that finite roster; schedule checks cannot replenish it. Regional
timers run only while any party member is present in that region. When the last member leaves, save
remaining durations/phases and reactivate on return without catching up for time spent elsewhere;
split parties keep their occupied regions active. The global calendar continues normally. Searching,
waiting, resting and travel within an active region advance relevant activity, allowing consequences and
player decisions along the way. Persist collected loot, patrol progress, resolved random outcomes
and alarms; discovery/re-entry does not recreate them. See [loot placement](DUNGEON_MAPS.md#loot-rewards-and-access)
and [patrols and encounter opportunities](DUNGEON_MAPS.md#patrols-schedules-and-encounter-opportunities).

The dungeon is a living document: battle damage, destroyed/moved furniture, breached walls, repairs
and other resolved changes persist in room/object state and applicable geometry. Update cover, sight,
traversal, narration and rendering consistently. These are ordinary gameplay consequences, distinct
from correcting established facts. Later visits/refinement preserve them, and checkpoints restore
their matching state. See [living rooms](DUNGEON_MAPS.md#living-rooms-and-persistent-consequences).

The guiding principle is:

> Save the blueprint early; lay out the coherent site near approach, before entry; persist its changes.

A graphical renderer derives images from the same saved model and can re-render it after discoveries,
world-building edits or coordinate-based POI additions. See [dungeon maps](DUNGEON_MAPS.md)
and [shared map format/outdoor cartography](MAP_FORMAT.md) for design targets, and
[map usage](MAP_USAGE.md) for the implemented baseline.

### 20.4 Player experience priorities

Status: review recommendations for the map feature. The spatial/persistence design above provides the
foundation; these proposed experience contracts should guide implementation and playtesting.

1. **Understand a choice before spending it.** Support read-only tactical inspection and previews:
   "Can I reach that pillar and still attack?" or "Show the cone from there." Explain path, movement
   cost, relevant cover/range and observable consequences using the acting character's capabilities
   and knowledge. Let the player revise a proposal freely; commit an unambiguous action without an
   extra confirmation ritual. A preview does not reveal hidden targets, traps or obstacles, and party
   shared vision does not grant every character individual line of sight.

2. **Direct the party by intention.** Extend individual placement with persistent exploration orders:
   "Dain leads, I follow, Mira watches our backs." Handle ordinary following and passage through doors
   without asking for each token's next square. Stop when a threat, meaningful discovery, blocked order
   or conflicting choice needs attention. On entering combat, preserve actual positions and apply
   individual turn rules. Orders do not grant movement, awareness or abilities a companion lacks.

3. **Make discovery invite curiosity.** Provide observable approach cues before complete identification:
   a tower above the pines, distant bells, a cold draft behind a door. "What can we see from here?"
   should give useful, bounded orientation and opportunities to investigate. Distinguish sight, sound,
   reports and individual perception; hearing something does not locate a precise target. A few
   authored observation/approach anchors can support this without a global visibility simulation.

4. **Give places playable character.** Review important rooms and routes for recognizable landmarks,
   meaningful environmental interaction and alternative approaches. A guardroom's doorway, side
   passage and movable table can offer different tactics. An upland route's ruined aqueduct or ferry
   custom can make it memorable. Functional props have explicit interaction/terrain properties where
   relevant, while decoration remains lightweight. Variety should serve the place rather than force
   every room or journey into an identical encounter formula.

5. **Keep play readable and moving.** At ordinary chat image size, tokens, doors, paths, grid and fog
   states should be clear without relying on color alone. Keep orientation, landmark names, actor
   labels and grid references stable between refreshes; offer a sparse high-contrast style. Text
   conveys equivalent actionable positions, known connections, terrain and distances. Follow an
   action with its observable change and remaining movement where relevant. Summarize uneventful
   exploration/travel, pause at consequential choices, and refresh images when useful without making
   ordinary actions wait for new illustration generation. Summaries retain normal time, resource and
   event accounting.

6. **Make it the party's remembered world.** Surface saved player notes, nicknames, visited routes,
   chosen campsites and unfinished map intentions when relevant. On resuming, orient the player to
   their current place, nearby known choices and what they were doing. On returning, summarize changes
   the party has learned about, including consequences of their actions. Keep annotations linked to
   stable places and the current campaign branch; a personal recap cannot disclose off-screen changes.

Prioritize previews, intention-based movement, readable feedback and reliable reorientation before
additional visual styles or richer simulations. Review a short end-to-end play session in both image
and text modes, measuring whether players can act confidently with few unnecessary clarifications.

Suggested experience scenarios:

- "Have the others follow until we see something unusual." Ordinary movement proceeds, then stops at
  an observable cue with clear party placement and a useful choice.
- "Can Mira cover the north door while I move behind the pillar? Actually, the other pillar."
  Inspection permits revision, commitment respects turns/sight, and the response explains what changed.
- Resume after marking a bronze door "return with the silver key": the current map and note return
  with relevant known exits, without requiring the player to reconstruct their bearings.
- Return along a familiar route after helping repair its bridge: recognize the same geography and
  learned consequences, with uneventful travel summarized and meaningful decisions left to the player.

### 20.5 Remaining map implementation contracts

The main architecture is decided: saved geographic features for world maps, graph travel and persistent
local grids for every combat. The following contracts remain open before implementation; they refine
that architecture rather than reopen it. Proposed implementation defaults must preserve the accepted
creative freedom, preparation, discovery and continuity requirements.

| Contract | What still needs to be specified |
| --- | --- |
| Exact spatial rules | Versioned diagonal/corner, footprint, cover, sight, hearing/detection, area-effect and elevation rules shared by mechanics, previews and rendering. The 5 ft placement lattice and shared view as the union of party members' sight are already chosen; individual actions and enemy awareness use each actor's perception. |
| Partial actions and time | One interruptible execution contract for movement, searches, rests, travel and combat; clock precision/round accounting, saved progress and completion effects. Unknown obstacles interrupt actual movement rather than produce free hidden-state probes. |
| Preparation and publication | A per-site readiness manifest separating accepted semantic blueprints from staged tile layouts, with isolated drafts, validation and revision-checked publication. Lay out most meaningful content near approach before entry; unpublished actors, loot and routes cannot affect play. |
| Passage identity | Exposed stable passage/route references supporting multiple independent connections between the same locations. Current endpoint-pair lookup needs adaptation. |
| Map mutation authority | Concrete tool boundaries for draft authoring, presentation changes, audited corrections and resolved gameplay effects; generic spatial edits cannot bypass actions or inventory rules. |
| Containers and item anchors | One canonical container/inventory relation, access-checked transfers and map-anchor behavior for moving, splitting or merging item stacks. |
| Delivery and compatibility | Assign acceptance scenarios to explicit milestones, gate rendering dependencies in native/JVM builds, define the first complete playable slice and validate legacy encounter conversion. |

---

## 21. Death and Continuation Policy

Character death behavior is selected during campaign creation.

Suggested modes:

### CHECKPOINT

Create checkpoints at suitable boundaries, especially before encounters.

On player death, offer to restore a previous checkpoint and replay from there.

### ENCOUNTER_RETRY

Specifically restore the state immediately before the failed encounter.

### IRONMAN

No rollback.

If the player character dies and suitable companions survive, the player may choose to take control of an existing
companion.

If no viable player-controlled character remains and the party is destroyed, the campaign ends.

Additional modes can be introduced later.

Continuation policy is persistent campaign configuration.

---

## 22. Content Profiles

### 22.1 Running guidance

Each `ContentProfile` carries a `guidance()` string as well as a label and a description. The description is what a
player is shown when choosing; **the guidance is what the GM is held to while running**, and it is returned with the
campaign so it is in front of the GM every session rather than only at setup.

`PEGI_18` is the recommended profile for adult players, it is the rating Baldur's Gate III carries, and it is the
profile the default fantasy epic (§4.2) is written for. It is also the one that matters most, because it is the one
most often obeyed in the letter and broken in the spirit: the profile means description as realistic and detailed as the author can make it, applied *equally* to
violence and to intimacy, and a scene that has been earned is played through to its end. Cutting away at the moment
a scene becomes interesting is the one thing the profile forbids. Where an author will not render something
literally, that is a limit on the author and not on the scene: use metaphor and indirection, but stay in the moment
and stay physical — do not leave the room, skip to the morning, or summarise in the past tense what should have been
shown. Consent and the characters' own agency are part of the writing, not a disclaimer wrapped around it.

The guidance also names the asymmetry it is fighting. Most commercial models render a killing in full without
hesitation and balk at rendering a night together in full — the reverse of what is natural for a human audience. The
running guidance calls that a habit of the author rather than a property of the profile, asks for the same standard
of attention on both, and asks the author to go as far as its own limits allow rather than to skip the scene: a scene
rendered at eighty percent, in the room and in the body, is what the profile asks for. Provider policy still applies
(§22.2) — the guidance moves a model part of the way towards the reference experience, it does not override anyone's
rules — but it removes the excuse of stopping at the first sign of difficulty.

### 22.1.1 Why this matters commercially

The stance is not an aesthetic preference; it is the project's theory of success. Baldur's Gate III is rated PEGI 18
and is one of the most successful role-playing games ever made: the first title to win Game of the Year at all five
major award ceremonies, a Hugo Award, well over twenty million copies sold. What its reviewers called out was not the
combat system. They singled out companions whose stories were "the main motivation for playing", romances that
developed naturally and were "unabashedly sexual", an approach to relationships that was more mature than "picking
the right dialogue options", freedom to solve problems creatively over tidy quest resolution, and the weight of
decisions in a world where "almost every character could die". Reviewers also noticed when it went wrong — one
criticised romance arcs that were initiated too quickly — which is the same lesson from the other side: the
relationships have to be written, not dispensed.

An AI Game Master that renders a battle in full and then turns coy at the bedroom door delivers a strictly worse
version of that game, and adults who chose PEGI 18 notice. The running guidance, the recommended profile and the
default fantasy epic all exist to move a model as far towards the reference as its own limits allow. Trying not to
be prudish — within provider policy, with consent and agency written into the scene — is what will make this MCP
much more successful than a sanitised one.

The server should have predefined content profiles inspired by the European PEGI age categories:

- PEGI 3
- PEGI 7
- PEGI 12
- PEGI 16
- PEGI 18

The project should describe clearly what each profile permits in terms of themes such as:

- Violence.
- Fear/horror.
- Language.
- Drugs/intoxication.
- Romance.
- Sexual content.
- Gambling-like themes.
- Other mature subject matter.

The project should use PEGI terminology carefully and avoid implying official PEGI certification.

The content profile belongs to the campaign/server policy, not merely to an ephemeral prompt.

### 22.1 Player Age

The campaign startup flow can ask the player's age and use it to cap the effective content profile where appropriate.

The server should make it difficult for the GM agent to accidentally operate outside the effective profile.

### 22.2 Provider Independence

The engine must not encode the policy limitations of any particular AI provider as the definition of its content
profiles.

Instead:

**Effective behavior = server policy ∩ campaign profile ∩ player constraints ∩ AI-provider policy**

A provider may therefore be more restrictive than the selected campaign profile without changing the persistent campaign
definition.

This keeps the MCP server provider-neutral.

---

## 23. Rules and Content Licensing

The distributable project should contain only content that can legally be redistributed.

The intended approach is:

- Use openly licensed SRD rules as the mechanical foundation.
- Keep the rules engine data-driven.
- Create an original/default world if example content is shipped.
- Do not ship protected campaign settings, adventures, characters, monsters, text, or lore that are outside the
  applicable open license.

Users may supply their own private content separately.

**Decision:** the CC-BY-4.0 attribution for SRD 5.2.1 ships in both places it naturally belongs: a `NOTICE` file in the
distributed artifact (the legal requirement travels with the binary) and the `rpg://rulesets` MCP resource, which
already describes installed rulesets and therefore carries each installed ruleset/content pack's license and attribution
text (discoverable by any connected AI). Campaign-scoped custom content is dynamic campaign state, so its licensing
metadata is returned by campaign-aware content operations rather than the cacheable `rpg://rulesets` resource.

The engine itself should remain world-agnostic.

---

## 24. Entity Identity and References

All persisted domain tables use simple numeric primary keys.

Examples:

```text
character.id = 1
location.id = 7
event.id = 42
```

IDs are scoped by table. Therefore `character.id = 1` and `location.id = 1` are both valid and unrelated.

IDs increase monotonically within each table. Every character—prospective player avatar, companion, NPC, hireling, or
monster—draws from the same character ID sequence. Which character is controlled by the player is a mutable campaign
assignment, never a property encoded in the ID.

Database relationships use ordinary numeric foreign keys. A character relationship, for example, stores numeric
`character_id` values; it does not store names, roles, or string-encoded polymorphic identifiers as substitutes for
relational keys.

At the MCP/context boundary, references may be rendered as compact **typed references**:

```text
character:1
location:7
event:42
```

A typed reference is an API/context representation of **table/entity type + numeric ID**. It is not a different identity
system and need not be stored as the database primary key.

The project-wide rule is:

> **Identity is stable; names, roles, status, and relationships are mutable.**

Consequences:

- Renaming `character:1` does not change its identity.
- A companion becoming player-controlled does not change its identity.
- A player character dying does not change its identity.
- Resurrection does not create a new character identity unless the rules explicitly create a distinct entity.
- Party membership is state, not identity.
- Player control is a mutable campaign assignment. `COMPANION`, `NPC`, and similar descriptions are mutable state or
  derived roles, never part of the primary key.
- Life state such as `ALIVE` or `DEAD` is also separate from role.
- Ledger events and relationship memories reference stable numeric entity IDs, so historical references survive renames
  and role changes.

Player-facing narration normally uses names and natural language. Typed references are primarily for MCP payloads,
technical traces, debugging, and LLM context where ambiguity matters.

## 25. Data-driven Rules

Rules content should generally be data rather than Java constants.

Examples:

- Weapons.
- Armor.
- Equipment.
- Spells.
- Conditions.
- Classes.
- Level progression.
- Creatures.
- XP thresholds.

Stable IDs should be used throughout so that persisted campaigns do not depend on display names.

This also makes future rulesets or house-rule packs possible.

### 25.1 Default Item and Economy Seed Data

The default ruleset dataset is lifted directly from **SRD 5.2.1** (CC-BY-4.0, attribution required). The item/economy
seed import should populate the database from the following SRD Equipment-chapter tables:

- **Coin Values** — CP/SP/EP/GP/PP with exchange values relative to GP; coin weight (50 coins = 1 lb.).
- **Weapons** — simple/martial, melee/ranged; damage, damage type, properties, mastery property, weight, cost (includes
  Musket and Pistol).
- **Weapon property and mastery definitions** — referenced by weapon rows (Finesse, Heavy, Loading, …; Cleave, Nick,
  Sap, …).
- **Armor** — category (light/medium/heavy/shield), AC formula, Strength requirement, Stealth disadvantage, weight,
  cost, don/doff times.
- **Tools** — 17 Artisan's Tools plus Other Tools (Disguise Kit, Forgery Kit, Gaming Set, Herbalism Kit, Musical
  Instrument, Navigator's Tools, Poisoner's Kit, Thieves' Tools); cost, weight, ability, utilize actions, craftable
  items, variants.
- **Adventuring Gear** — the full alphabetical table (~90 items) with cost, weight, and per-item rules text.
- **Ammunition** — type, purchase quantity, storage container, weight, cost.
- **Arcane Focuses / Druidic Focuses / Holy Symbols** — focus forms with weight and cost.
- **Equipment packs** — Burglar's, Diplomat's, Dungeoneer's, Entertainer's, Explorer's, Priest's, Scholar's packs,
  including their content lists (needed for starting equipment).
- **Mounts and Other Animals** — carrying capacity and cost; barding rule (4× armor cost, 2× weight).
- **Tack, Harness, and Drawn Vehicles** — including saddles, feed per day, stabling per day.
- **Airborne and Waterborne Vehicles** — speed, crew, passengers, cargo, AC, HP, damage threshold, cost.
- **Lifestyle Expenses** — Wretched through Aristocratic, cost per day.
- **Food, Drink, and Lodging** — ale/bread/cheese/wine, inn stays and meals by quality tier.
- **Hirelings** — skilled/untrained per day, messenger per mile.
- **Spellcasting Services** — cost by spell level and settlement availability.
- **Crafting data** — nonmagical crafting (raw materials = half cost, time = cost/10 GP per day, tool→item mappings from
  the Tools tables), Brewing Potions of Healing, Spell Scroll Costs (time and cost by spell level).
- **Sample Poisons** (Gameplay Toolbox) — 15 poisons with per-dose prices, delivery type, and effects.
- **Class starting equipment** — per-class A/B choices (equipment bundle + gold, or flat gold) and background starting
  equipment options. Note: SRD 5.2.1 starting wealth is a fixed choice, not a rolled expression; rolled starting wealth
  exists only as an optional house rule.
- **Trinkets** — the d100 flavor-item table from Character Creation.

Magic items (SRD Magic Items chapter) are seeded verbatim as definitions with rarity, attunement and the value of the
SRD's rarity table (`magic-items.json`; `RULES_ENGINE.md` §10); magic weapons, armor, shields and ammunition are
templates instantiated on a base item at grant time. The Selling Equipment rule (items sell for half cost; valuables retain full value) belongs with the
economy rules.

There is no Trade Goods table in SRD 5.2.1 (it existed in SRD 5.1); do not invent one for the default dataset.

**Decision — import pipeline:** the seed data is authored as JSON files in the repository, embedded into the native
executable as classpath resources (Quarkus `quarkus.native.resources.includes`), and imported into SQLite on first run —
or whenever a database is missing the ruleset/version. This keeps the distribution a single self-contained executable
while the data stays hand-editable and rebuildable. A future content-pack feature reuses the same importer against an
external directory.

### 25.2 Default Creature, Spell, and Progression Seed Data

Beyond items and economy, the seed import also lifts the mechanical reference tables the engine needs:

- **Monsters A–Z and Animals** (SRD Monsters chapter) — full stat blocks: size, type, AC, HP, speeds, ability scores,
  skills, resistances/immunities, senses, languages, CR, and **XP value**, plus actions/traits as structured data where
  expressible and rules text otherwise.
- **XP by Challenge Rating** — the CR→XP mapping used for mechanically determined encounter XP.
- **Character advancement** — XP thresholds per level and proficiency bonus progression.
- **Spells** (SRD Spells chapter) — level, school, casting time, range, components, duration, concentration, save/attack
  metadata, damage/healing/scaling where structurally expressible, and full rules text; plus per-class spell lists.
- **Conditions and rules-glossary effects** — the condition definitions referenced by the effects system.
- **Classes, subclasses, species, backgrounds, and feats** — as needed for character creation and level-up validation.

A creature *definition* is content data. A creature *instance* participating in play is an ordinary row in the character
table with its own stable numeric identity — whether it is a dragon, a hireling, or the player character. Player control
is a mutable attribute of a character, never a different kind of entity.

### 25.3 Item Extensibility

The seed data is a starting inventory, not a closed set. After the defaults are imported:

- The GM must be able to define new campaign-scoped items at runtime (a local newspaper, a regional delicacy, a unique
  quest item) so that players can buy, sell, and carry things the SRD never anticipated.
- Custom definitions receive stable campaign-owned typed references such as `content:5`. They may also use a
  campaign-local symbolic namespace (for example `custom:item/bellhaven-broadsheet`) for readable lookup without
  colliding with `srd5e:` content; bare symbolic IDs are not cross-campaign references.
- Custom item creation is an explicit, provenance-recorded operation subject to campaign GM policy — inventing an item
  definition is not the same as granting a player treasure, which remains governed by loot/override rules.
- Content packs (future) follow the same pattern: separate namespace, explicit import.

---

## 26. MCP API Design Principles

MCP tools should expose semantic operations rather than database CRUD.

Prefer:

- `get_character_sheet`
- `start_encounter`
- `perform_attack`
- `award_xp`
- `advance_time`

over:

- `update_character_row`
- `insert_event`
- `set_field`

The AI should tell the engine what game operation it wants, not how to mutate tables.

Likely tool families include:

### Campaign

- create/list/open campaign
- get campaign summary
- get current state
- commit campaign setup
- suspend/resume session

### Character

- create/update during design
- get character sheet
- get runtime state
- get available character choices
- commit character
- level-up workflow

### Party

- get party
- join/leave party
- transfer player control

### Dice

- roll
- resolve check
- resolve saving throw

### Inventory/Economy

- get inventory
- equip/unequip
- transfer item
- add/remove loot
- purchase/sell
- get carrying state

### Encounter

- create/start encounter
- get encounter state
- get turn state
- perform action
- advance turn
- end encounter
- GM override

### Story/World

- get relevant campaign context
- get adventure outline
- update story beat
- get location
- materialize location detail
- get/update quest
- get/update faction state

### Memory

- record semantic event
- query relevant events
- query timeline

### Relationships

- get relationships
- record/update relationship development

### Time

- get game time
- advance time
- rest

The exact API should be designed during the implementation-spec phase.

---

## 27. Context Management

Token efficiency is a core requirement.

The GM should not load the entire campaign on every turn.

The server should provide purpose-built context views.

For example, at the beginning of a scene:

- Current location.
- Current party.
- Relevant character summaries.
- Active quests.
- Nearby NPCs.
- Relevant relationships.
- Recent important events.
- Applicable story beats.
- Current game time.
- GM-only information relevant to the scene.

Old conversational narration is disposable once its important consequences have been committed to persistent state.

This gives the system effectively unlimited campaign duration without an ever-growing prompt.

---

## 28. Sessions

A gameplay session is distinct from a campaign.

A session should track:

- Session ID.
- Start/end.
- Starting game state reference.
- Ending game state reference.
- Events generated.
- Optional compact summary.

When a new AI conversation begins, the GM can reconstruct the necessary runtime context from persistent campaign state
and recent/relevant events.

---

## 29. Checkpoints and Transactions

The engine should use transactions for meaningful state transitions.

Examples:

- Level up.
- Combat action resolution.
- Trading.
- Rest.
- Character creation commit.

Checkpoints must capture **every campaign-owned mutable canonical aggregate**. This includes complete character state,
inventory and money, party/control state, relationships, custom content, locations/connections, quests, factions,
story/Director state, encounters and pending gameplay workflows, versioned campaign configuration, the semantic ledger,
and the game clock. New canonical aggregates are rewindable unless explicitly specified otherwise. Immutable audit
lineage, idempotency records, physical checkpoint metadata, and installation configuration are excluded. Checkpoint
round-tripping (create → mutate → restore → verify equality) should be covered by unit/integration tests from the first
implementation.

A natural initial implementation is to route every canonical mutation through an append-oriented change journal; a
checkpoint is then a marker in that journal, and restoring applies inverse changes or creates a new canonical branch
from that marker — by insertion ID, not game date, so retroactively recorded entries rewind with the act that recorded
them. Reverted semantic events become non-canonical but remain distinguishable in immutable audit lineage.

Journal/ledger/audit IDs must never be reused. The relevant SQLite tables therefore require `AUTOINCREMENT` or an
explicit monotonic sequence. A reused ID would silently corrupt insertion-order semantics and checkpoint markers.
Database snapshots/versioned state are an alternative. The choice belongs to `DATABASE.md`.

---

## 30. Auditability

Important state-changing operations should be attributable to their cause.

Especially:

- GM overrides.
- Manual XP awards.
- Character death/revival.
- Checkpoint restoration.
- Administrative edits.
- Content-policy changes.

This is useful both for debugging and for preserving player trust.

---

## 31. Technology Direction

Initial implementation preferences:

- **Language:** Java
- **Runtime:** GraalVM Native Image
- **Framework:** Quarkus
- **Interface:** MCP, initially with stdio support
- **Persistence:** SQLite
- **Build:** Native-image-friendly from the beginning

An existing Java MCP server can serve as a structural starting point, replacing its application domain with the RPG
domain.

The implementation should avoid reflection-heavy or otherwise Native Image-hostile choices unless Quarkus handles them
cleanly.

---

## 32. Suggested Domain Modules

A possible Java module/package decomposition:

```text
campaign
adventure
character
party
relationship
rules
dice
item
inventory
spell
effect
encounter
progression
quest
faction
world
map
time
ledger
checkpoint
policy
session
mcp
persistence
```

This is conceptual rather than mandatory.

Avoid splitting into separate deployable services. The MVP should remain one simple application.

---

## 33. MVP

The first playable version should be deliberately small.

### Required

- Persistent SQLite campaigns.
- Campaign list/create/open.
- Campaign creation state machine.
- Basic character creation.
- Party.
- Basic character sheets.
- HP/current HP.
- XP and level.
- Money.
- Basic inventory.
- Engine-owned dice.
- A minimal SRD-derived equipment/rules dataset (see the seed tables in §25.1).
- Basic encounter loop.
- Initiative/round/turn tracking.
- Attacks/damage/healing.
- Character death.
- At least checkpoint and ironman continuation modes.
- Semantic event ledger.
- Game clock.
- Adventure outline/story beats.
- Basic NPC relationship persistence.
- Context retrieval for the GM.
- MCP interface.
- GraalVM Native Image build.

### Early follow-up

- Full encumbrance.
- Conditions/effects.
- Spellcasting.
- Level-up transaction workflow.
- Quests.
- Factions.
- Semantic locations/maps.
- Lazy world generation.
- Companion introduction plans.
- Richer relationship modeling.
- More complete rules data.
- Campaign-scoped custom item definitions (§25.2).

### Later

- Graphical maps.
- Campaign-creation integration for a creatively generated initial regional map (§4.2, §20.1).
- Portrait/image generation integration.
- Multiple rulesets.
- Multiplayer/multiple human players.
- Remote MCP transport.
- Campaign/content-pack import/export.
- More sophisticated procedural generation.

---

## 34. Example Runtime Loop

Conceptually:

```text
PLAYER
  ↓ natural language
AI GAME MASTER
  ↓ interpret intent
MCP SERVER
  ↓ validate against campaign/rules/state
RULES + WORLD ENGINE
  ↓ deterministic result + state mutation
PERSISTENCE
  ↓ canonical result
AI GAME MASTER
  ↓ narration/dialogue/consequences
PLAYER
```

For purely conversational actions, the GM may not need a mechanical call.

For anything that changes canonical state, the relevant change should eventually be committed to the server.

---

## 35. Design Philosophy

### Fiction is flexible; state is strict.

The GM can improvise endlessly around canonical state, but must not casually contradict it.

### Story plans are intentions, not rails.

The adventure outline should help the GM maintain pacing and continuity without preventing unexpected player choices.

### Generate lazily, persist eagerly.

Do not build an entire city the player may never visit. But once an innkeeper has been invented and introduced, persist
enough information that the same person exists next year.

For a dungeon the party is preparing to enter, save a semantic blueprint early and materialize most
of its layout and meaningful contents near approach, before entry. Large sites use prepared hierarchy
and bounded refinement; lazy detail must respect accepted geography, story and subsequent gameplay changes.

### Mechanical truth lives outside the model.

If the player has 7 HP, that should remain true regardless of context-window truncation or a new AI session.

### Relationships are gameplay.

Companions are not merely combat assets. Friendship, rivalry, loyalty, romance, betrayal, humor, and personal
development are central to maintaining emotional investment.

### Let players control how much they author.

A player may design every companion and world detail—or simply say:

> Surprise me.

Both should produce a good game.

### The AI should feel like a GM, not an API client.

Tool usage should remain invisible in the narrative experience wherever possible.

---

## 36. Open Design Questions

These do not block the initial architecture:

1. Exact SRD version and data-import strategy.
2. Exact schema for rules text vs. executable mechanics.
3. Numerical vs. qualitative relationship modeling.
4. How aggressively story events should be automatically summarized.
5. Checkpoint storage strategy.
6. Whether random generation should be globally seedable per campaign.
7. Exact definition of each content profile.
8. Whether the world clock needs calendars beyond a simple campaign-defined calendar.
9. How much NPC mechanical detail should be materialized before it becomes relevant.
10. Exact MCP tool granularity.
11. Whether GM overrides require an explicit server startup flag in addition to campaign configuration.
12. Import/export format for campaigns and content packs.
13. Remaining map contracts are tracked in [§20.5](#205-remaining-map-implementation-contracts): exact
    spatial rules, interruptible execution, publication, identity, mutation authority, containers and delivery.

---

## 37. Next Design Artifacts

Once this document is accepted, the natural next documents are:

- `ARCHITECTURE.md` — Java components, boundaries, persistence architecture.
- `DOMAIN_MODEL.md` — entities, IDs, state machines, invariants.
- `MCP_PROTOCOL.md` — authoritative MCP capabilities, tools, resources, schemas, and behavioral contract.
- `DATABASE.md` — SQLite schema and migration strategy.
- `RULES_ENGINE.md` — deterministic mechanics and SRD mapping.
- `GAME_HARNESS.md` — campaign creation and runtime state machines.
- `CONTENT_PROFILES.md` — content/age profiles and provider-policy intersection.
- `MVP.md` — concrete first implementation milestones.

The first implementation should begin only after the domain model and MCP API are sufficiently clear to avoid encoding
conversational assumptions into the persistence model.
