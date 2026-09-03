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

- Graphical map rendering as a core requirement.
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
- Events and history.

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

1. The configuration is validated.
2. Character and campaign state are persisted.
3. Initial adventure state is generated/persisted.
4. The design conversation is no longer required.
5. Gameplay begins using a fresh runtime context reconstructed from persistent state.

This is a major architectural principle.

The potentially long conversation used to design a character or campaign must not consume runtime context forever.

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

Encounters take place somewhere — a road, a clearing, a dungeon room. For many encounters, zone-level positioning (
near/far, cover, flanks) is enough. For tactically detailed encounters, especially in dungeon rooms, the model should
support optional **grid positions** (x,y coordinates within the encounter area), like placing figurines on a tabletop
map. Grid support is an optional layer over the encounter model, not a prerequisite for it; the exact representation is
an open design question.

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

Maps should initially be **semantic**, not graphical.

### 20.1 World/Region Map

Represent:

- Locations.
- Connections/routes.
- Approximate distances.
- Travel constraints.
- Regions.
- Important landmarks.

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

Generation can be seeded so that once a dungeon exists, it remains stable.

The guiding principle is:

> Generate detail when it becomes relevant, then persist it.

A graphical renderer can be added later without changing the underlying model.

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

Magic items (SRD Magic Items chapter) are seeded as definitions with rarity but no fixed price; monetary value is
rules/GM-determined. The Selling Equipment rule (items sell for half cost; valuables retain full value) belongs with the
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
13. Exact tactical-grid representation for encounter positioning (zones vs. x,y grid, movement costs, ranges).

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
