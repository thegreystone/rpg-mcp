# RPG MCP Server — Domain Model

**Purpose:** The entities, aggregates, state machines, invariants and transaction boundaries behind `DESIGN.md` and
`MCP_PROTOCOL.md`. Physical schema is `DATABASE.md`; mechanics are `RULES_ENGINE.md`.

> **The AI improvises the fiction; the engine owns the truth.** This document defines what the truth is made of.

---

## 1. Normative Language

**MUST**, **MUST NOT**, **SHOULD** and **MAY** are used as in `MCP_PROTOCOL.md`.

Invariants are numbered `I-1`, `I-2`, … and are meant to become tests. Tests assert them as sanity properties; only
checkpoint restoration is verified by exact equality.

---

## 2. Modeling Principles

### 2.1 Identity

Every persisted entity has a table-scoped, monotonically increasing numeric primary key, rendered at the protocol
boundary as a typed reference (`character:1`, `location:7`, `event:42`). Identity is stable; name, role, control,
membership and life state are mutable.

- **I-1** An entity's numeric ID never changes and is never reused within its table. Character IDs come from one
  sequence; a character row is archived, never deleted.
- **I-2** No mutable attribute (name, role, control, life state, party membership) is part of any primary key.
- **I-3** Cross-entity references are numeric foreign keys to stable IDs, never names, display strings or role labels.

### 2.2 One character table

Every creature is a row in the character table: player character, companion, innkeeper, dragon. There are no NPC or
monster tables. Player control is a mutable assignment referencing a row; "companion" and "NPC" are derived from
control, membership and prominence. A creature instantiated from a definition (`srd5e:creature/bandit`) is an ordinary
row whose provenance records the definition.

### 2.3 Definitions vs. instances

**Installed content definitions** (items, spells, creatures, conditions, classes, …) are read-mostly data with
namespaced string IDs such as `srd5e:item/longsword`. Campaign-scoped custom definitions are numeric entities rendered
as `content:N`, optionally with a campaign-local symbolic ID such as `custom:item/bellhaven-broadsheet`. Definitions
come from embedded seed data or `define_content`.

**Instances** are campaign state that references definitions: an inventory entry, a character materialized from a
creature, an active effect from a condition.

- **I-4** Campaign state never copies definition data it can reference, except where a snapshot is explicitly required
  (§14 checkpoints, `RULES_ENGINE.md` derived values).
- **I-5** `custom:` definitions belong to one campaign and are invisible to every other.

### 2.4 Planning state vs. canonical state

Setup drafts (`draft:N`) and Director seeds (`seed:N`) are planning state and become truth only through an explicit
commit or materialize operation. Characters are the exception: creation reserves a normal row and ID at once, with
lifecycle `DRAFT`, and commit promotes that same row.

- **I-6** A `DRAFT` character may be referenced only by its creation/setup workflow and staged state. It is excluded
  from ledger events, relationships, active inventory, party membership, world queries and encounters until `ACTIVE`.

### 2.5 Revisions and provenance

Mutable aggregates carry an integer `revision`, incremented on every committed mutation. Mutations based on previously
read state pass `expected_revision` and fail with `CONFLICT` on mismatch.

Every mutation records provenance:

- `PLAYER` — a direct player decision
- `GM` — GM narrative or mechanical initiation
- `DIRECTOR` — a committed Director change
- `MECHANICAL_CONSEQUENCE` — an automatic engine consequence (damage, XP, expiry)
- `ADMINISTRATIVE_OVERRIDE` — an explicit audited override

---

## 3. Entity Overview

```text
Campaign 1─┬─ CampaignSetupDraft (planning)
           ├─ Character *──┬─ InventoryEntry *
           │               ├─ ActiveEffect *
           │               ├─ ResourceState *
           │               └─ PartyMembership * (history)
           ├─ PlayerControlAssignment * ──> Character
           ├─ Relationship * ──> significant Event refs
           ├─ Encounter * ──> Participant * ──> Character
           ├─ Quest * / Faction * / StoryBeat * / Seed * / WorldEventState *
           ├─ Location * ──> Connection *
           ├─ GameClock 1
           ├─ Session *
           ├─ Event * (semantic ledger)
           ├─ JournalEntry * (change journal, incl. checkpoint markers)
           ├─ AuditRecord * (survives rollback)
           ├─ PendingTransaction *
           └─ PolicyState 1

InstalledContentDefinition * (ruleset-scoped, globally stable string ID)
CustomContentDefinition * (campaign-owned numeric ID / content:N)
```

---

## 4. Campaign

The top-level aggregate root. Every campaign-scoped entity carries `campaign_id`.

**Attributes:** title (generated at commit when absent), ruleset + version, status, content profile, rules options,
continuation policy, GM override policy, experience preferences, current location ref, active session ref, revision.

### 4.1 Campaign status state machine

```text
SETUP ──> READY_TO_PLAY ──> ACTIVE ──> SUSPENDED ──> ACTIVE
                              │
                              ├──> COMPLETED
                              ├──> FAILED
                              └──> ABANDONED
```

- `SETUP` — created, not committed; no gameplay operation is legal.
- `READY_TO_PLAY` — committed, no session yet.
- `ACTIVE` / `SUSPENDED` — alternate via `bootstrap_session` / `suspend_session`.
- Terminal states are set only by `complete_campaign` with a reason.

- **I-7** Rules configuration and continuation policy are immutable after commit; any change is explicitly versioned.
- **I-8** Gameplay tools are illegal in `SETUP`; setup tools are illegal after commit. The harness state (§16) enforces
  this.

### 4.2 Campaign setup draft

One resumable draft per campaign in `SETUP`: content profile, player constraints (least-specific sufficient form),
experience preferences, rules choices, continuation policy, party design, `DRAFT` character refs and the proposed
control assignment. Creative fields may hold the explicit value `SURPRISE_ME` (delegated is not missing).

`commit_campaign_setup` atomically validates the graph, writes configuration, promotes finalized characters to `ACTIVE`,
creates the control assignment, memberships, relationships, adventure/Director state and world, writes the setup ledger
event, sets `READY_TO_PLAY` and marks the draft consumed.

- **I-9** After commit, no canonical entity references the setup draft.

---

## 5. Character

One row per creature, in three attribute groups.

### 5.1 Identity and narrative (mutable, non-mechanical)

name, description, appearance, personality, backstory, goals, alignment, age, sex/presentation. Changed by
`update_character` or, before commit, draft operations.

Two merged aggregates ride with them so a client can play the person after a context reset: the **biography** (dated
timeline, verbatim voice lines, bodily state with a since and an until, marks, and **wants**: dated, closable drives
shown open in every party list) and, in PEGI_18 campaigns only, the **intimate profile** (body, likes, dislikes,
limits, hard lines, intimate wants and with whom, household terms, voice in bed). The profile is the character's own
truth; what a partner has learned stays on the relationship profile (§7). Neither is returned below PEGI_18.

### 5.2 Rules identity (slow-changing, rules-validated)

species (traits, lineage/ancestry choice), background, class(es) and subclass(es), level per class, ability scores,
proficiencies, known/prepared spells, feats, max HP, speed, senses. Changed only by rules-governed transactions
(level-up, materialization) or audited override.

### 5.3 Runtime state (fast-changing)

current HP, temporary HP, life state, effects (ActiveEffect), resource pools (ResourceState), exhaustion, location ref,
encounter participation ref, money in canonical copper.

### 5.4 Lifecycle, player control, and life state

```text
character_lifecycle: DRAFT | FINALIZED_DRAFT | ACTIVE | ARCHIVED
life_state:  ALIVE | DYING | DEAD
```

- `PlayerControlAssignment` links a player seat to one active character (one seat in the MVP).
  `transfer_player_control` changes the assignment; neither character's identity changes (**I-10**).
- Death sets `life_state = DEAD` and ends party activity through membership state; the row, its relationships and its
  history remain (**I-11**).
- Resurrection restores `life_state` on the same row; there is never a new identity (**I-12**).
- Draft and finalized characters are persisted but cannot play. `ARCHIVED` is terminal for a draft abandoned before it
  became active.

### 5.5 Derived values

Base values are stored. Derived values (AC, save bonuses, carry capacity, encumbrance) are computed on read and never
stored except inside checkpoint or audit snapshots.

- **I-13** `0 ≤ current_hp ≤ max_hp`, temporary HP tracked separately; every HP change is journaled with its cause.
- **I-14** Money is one non-negative integer in canonical copper. Denominations exist only at presentation and
  exchange.
- **I-15** A character has at most one current location and at most one active encounter participation.

### 5.6 Character creation state

Creation writes to the reserved row plus staged rules state. Rolled generation results cannot be silently rerolled
(**I-16**). `commit_character_draft` moves `DRAFT` to `FINALIZED_DRAFT` on the same row; the enclosing transaction
later promotes it to `ACTIVE` and activates starting inventory, money and resources.

---

## 6. Party Membership

Party composition is history, not a set: one row per membership episode (character ref, state, joined/left game time,
cause event ref, notes).

```text
ACTIVE ──> SEPARATED ──> ACTIVE      (temporary separation)
ACTIVE ──> LEFT | DISMISSED | DEAD   (episode ends)
GUEST  ──> ended                     (temporary ally)
```

- **I-17** A character has at most one membership row in a non-terminal state.
- **I-18** Every membership transition writes a ledger event that the row references.
- **I-19** The party is a query over membership state; there is no stored party list.
- **I-19a** Membership says who belongs; location says who is present. A default `move_party` takes the active members
  and guests standing where the party stands and leaves the rest where they are; separated members never move. Views
  report `former_members` as one entry per character (latest ended episode, no open one) and the episode history on the
  character.

---

## 7. Relationship

One aggregate per ordered character pair, created on first recorded development (upsert): dimension values (affection,
trust, respect, attraction, fear, resentment, loyalty), compact summary, selected significant event refs, revision.

- **I-20** Every committed change carries provenance and a cause (event ref or reason).
- **I-21** Significant-event references point at ledger events that exist and involve both characters.
- **I-22** Relationship *seeds* (a possible romance or rivalry) live in Director planning state, never as predetermined
  values in the aggregate.

---

## 8. Inventory and Items

**InventoryEntry:** owner (character, container, location or loot source), item definition ref, quantity, equipped
flag/slot, charges/state, container nesting ref.

- **I-23** Quantity is positive; zero-quantity entries are deleted.
- **I-24** The item definition must exist and be visible to the campaign (ruleset namespace or own `custom:`).
- **I-25** Transfers, trades and loot grants are atomic: money and items move together or not at all; the engine does
  all accounting.
- **I-26** Equipped state is validated against the item's slot rules; changes recompute derived values on next read.
- **I-27** Carry weight is derived from entries plus coin weight; never stored.

---

## 9. Effects, Conditions, and Resources

**ActiveEffect:** target ref, source (character/item/spell/environment + provenance), condition or effect definition
ref or inline modifier, start (game time + journal ID), duration/expiry, concentration link, stacking key.

- **I-28** Expiry is evaluated on time advancement and round transitions; expired effects are removed as
  `MECHANICAL_CONSEQUENCE` journal entries.
- **I-29** Breaking concentration removes every effect linked to it, atomically with the cause.

**ResourceState:** character ref, resource ref (spell slot level, class resource), current/max, recharge rule.

- **I-30** `0 ≤ current ≤ max`; spending below zero is a validation failure, never a clamp.

---

## 10. Encounter

**Attributes:** status, participants, sides and side stances (ALLIED, HOSTILE, NEUTRAL, TEMPORARILY_COOPERATIVE,
UNKNOWN), initiative order, round, turn, environment, objectives, spatial model, revision, retry checkpoint ref (under
ENCOUNTER_RETRY).

**Participant:** character ref, side, initiative, position (zone; x,y with the tactical grid capability), status
(ACTIVE, DEFEATED, FLED, REMOVED).

```text
CREATED ──> RUNNING ──> ENDED
              │  ▲
              └─ WAITING_CHOICE (typed pending transaction)
```

- **I-31** Turn order is total and engine-owned; `perform_encounter_action` is legal only for the character whose turn
  it is, or a legal reaction resolved through a pending choice.
- **I-32** One action resolves atomically: roll, resource expenditure, damage/healing, conditions, defeat transitions,
  log entry and next-turn state.
- **I-33** An encounter cannot end while mandatory pending choices remain.
- **I-34** Completion commits final state, mechanically defined XP and the ledger event in one transaction; runtime
  state after `end_encounter` equals the in-encounter final state.
- **I-35** Under ENCOUNTER_RETRY, the retry checkpoint exists before the first round.

---

## 11. Narrative State

Narrative aggregates are campaign-scoped, carry revision, provenance and visibility, and are mutated only through
`upsert_narrative_state` or Director commits.

**Quest:** objective, issuer ref, status (`OFFERED, ACCEPTED, COMPLETED, FAILED, ABANDONED`), participants, rewards,
deadlines, dependencies, hidden objectives (visibility-scoped), related beat refs.

**Faction:** goals, relations to other factions, member refs, player standing, resources, knowledge/secrets.

**StoryBeat:** description, state (`PLANNED, AVAILABLE, BLOCKED, SUPERSEDED, COMPLETED, ABANDONED`), preconditions,
related refs.

**Seed** (Director planning): kind (`STORY_SEED, COMPANION_INTRO, PRESSURE, PACING_INTENT`), payload, state (`OPEN,
MATERIALIZED, SUPERSEDED, EXPIRED`), superseded-by ref.

**WorldEventState:** committed off-screen facts with game time, affected refs and the diegetic channels through which
they may surface.

- **I-36** A superseded plan keeps its history; the successor's reference preserves the underlying intention.
- **I-37** Director output constrains the world, never the player: no aggregate encodes a required player choice or a
  predetermined relationship outcome.
- **I-38** `get_diegetic_information` only reads committed WorldEventState; it never creates facts.

---

## 12. Location and Movement

**Location:** kind (`REGION, SETTLEMENT, DISTRICT, SITE, BUILDING, ROOM/AREA`), parent ref (containment tree), name,
description, materialization (`SEMANTIC, MATERIALIZED`), generation seed, features/secrets (visibility-scoped).

**Connection:** location A/B refs, kind (road, river, door, passage…), travel constraints, distance/time, state
(open/locked/blocked/secret with visibility).

Dungeons are subtrees of ROOM/AREA nodes joined by connections; one model serves world, settlement and dungeon maps.

- **I-39** Materialization is one-way and stable: generated detail persists and later reads agree.
- **I-40** `move_party` requires a known traversable path or an explicit authorized route, updates every moved
  character's location, advances the clock and commits atomically.
- **I-41** Materialization may not contradict committed geography except through audited override.

---

## 13. Time

**GameClock** (one per campaign): calendar ref, current instant, monotonic `sequence`.

- **I-42** The clock never moves backward and `sequence` strictly increases with every advancement. Checkpoint
  restoration is the sole exception.
- **I-43** Every ledger event and journal entry records the game time of recording; retroactive events also carry
  their fictional time, which may be earlier.
- **I-44** Time advancement processes, in order: effect expiry, scheduled world events, rest resolution, Director
  trigger evaluation, each committed as `MECHANICAL_CONSEQUENCE`.

---

## 14. Ledger, Journal, Audit, and Checkpoints

### 14.1 Event ledger (semantic memory)

Significant events: type, actors, location, fictional and recording game time, summary, payload, importance (`MINOR,
NOTABLE, MAJOR, CRITICAL`), visibility, causal refs, optional episodic detail.

- **I-45** Event IDs are insertion-ordered and never reused.
- **I-46** Fictional order and insertion order are independent; retroactive recording is valid.
- **I-47** Mechanical operations write their own ledger events; the AI does not duplicate them.

### 14.2 Change journal (canonical mutations)

Every canonical mutation appends an entry: insertion ID, operation, touched aggregate refs and revisions, provenance,
undo information and the `operation_id`. **Checkpoints are marker entries in this journal.**

- **I-48** Every canonical mutation flows through the journal; there is no out-of-band write path.
- **I-49** Journal IDs are insertion-ordered and never reused.
- **I-50** Restoring a checkpoint rewinds every campaign-owned mutable canonical aggregate to the marker by insertion
  ID: character and staged/runtime state, control assignments, inventory and money, custom content, locations and
  connections, membership, relationships, quests, factions, story and Director state, encounters, pending workflows,
  versioned configuration, the semantic ledger and the clock. New mutable aggregates are rewindable unless specified
  otherwise.
- **I-51** Checkpoint round-tripping (create → mutate → restore) yields exact equality for rewindable state. Audit
  lineage, idempotency records, checkpoint metadata, installation configuration and session records are excluded. This
  is the one exact-match state test in the suite.
- **I-52** Ledger events recorded after the marker become non-canonical on restoration because recording them was
  journaled. Their IDs are never reused; audit lineage can still show that the branch existed.

### 14.3 Audit log (survives rollback)

Overrides, discretionary XP, restorations, administrative edits, policy changes: actor, provenance, reason,
before/after values, real timestamp.

- **I-53** The audit log is never rolled back; a restoration writes an audit record that survives it.
- **I-54** An override result is always labeled as an override, never presented as a rules-derived or random result.

---

## 15. Sessions, Pending Transactions, and Policy

**Session:** start/end (real and game time), starting/ending journal IDs, summary, events-written count.

**Chronicle:** the story so far, independent of sessions. A CHAPTER is an immutable prose summary of a ledger span; the
SYNOPSIS is the rolling story, rewritten from the previous synopsis and the chapters since it. Both are written by a
summarizer the client delegates to, from material the server cuts at a `through` marker, so play continues meanwhile.
Bootstrap shows synopsis, chapters since it and a digest of the uncovered ledger: constant size for any campaign
length. Due-ness is by size (20,000 uncovered characters) and by closed quests, reported in the results of play.

**PendingTransaction:** kind (`CHARACTER_CREATION, CAMPAIGN_COMMIT, LEVEL_UP, ENCOUNTER_CHOICE,
LOCATION_MATERIALIZATION, TRAVEL_INTERRUPT, REST_INTERRUPT`), status (`OPEN, COMMITTED, ABANDONED, EXPIRED`),
controlled aggregate refs, draft payload, legal operations, revision.

- **I-55** At most one open transaction exclusively controls a given aggregate.
- **I-56** Abandoning or expiring a transaction leaves canonical state untouched.
- **I-57** Level-up commits atomically: validation, rules-identity changes, resource maxima, ledger event.

**PolicyState:** content profile, player constraints (least-specific sufficient form), GM override policy. Provider
policy is never persisted as campaign truth (**I-58**).

- **I-59** The server rejects profile selections above the player constraint cap; enforcement is server-side.

**Idempotency:** processed `operation_id`s are kept with their results per campaign.

- **I-60** Replaying an `operation_id` with equivalent arguments returns the original result without re-applying;
  different arguments yield `IDEMPOTENCY_CONFLICT`.

---

## 16. Harness State Machine

Protocol-visible states (`EXECUTION_MODEL.md` §25, plus `READY_TO_PLAY`):

```text
CAMPAIGN_SELECTION
SETUP_CONTENT_PROFILE → SETUP_EXPERIENCE → SETUP_RULES → SETUP_CONTINUATION
CHARACTER_CONCEPT → CHARACTER_RULES → CHARACTER_PERSONALITY → CHARACTER_EQUIPMENT → CHARACTER_REVIEW
PARTY_DESIGN → ADVENTURE_INITIALIZATION → CAMPAIGN_REVIEW → CAMPAIGN_COMMIT → READY_TO_PLAY
SESSION_BOOTSTRAP → EXPLORATION ⇄ ENCOUNTER
LEVEL_UP, CHECKPOINT_DECISION, PLAYER_CHARACTER_TRANSFER, SESSION_SUSPEND
CAMPAIGN_COMPLETED, CAMPAIGN_FAILED, CAMPAIGN_ABANDONED
```

The harness answers "which operations are legal now". Setup states may be revisited before commit. Internal substates
are allowed; the visible set is stable within a protocol major version.

- **I-61** Every tool invocation is validated against the harness state; `allowed_operations` is advisory, server
  validation is not.
- **I-62** Harness state is canonical: journaled, checkpointed, restored.

---

## 17. Visibility and Knowledge

Visibility labels on events, secrets, hidden objectives, seeds and location features:

```text
PLAYER_KNOWN | PARTY_KNOWN | CHARACTER_KNOWN(refs) | FACTION_KNOWN(ref) | GM_ONLY | DIRECTOR_ONLY
```

- **I-63** Filtering happens server-side at read time; secret fields are omitted, not flagged.
- **I-64** Knowledge transitions (a secret becoming PARTY_KNOWN) are journaled mutations with a cause.

---

## 18. Content Definitions

Installed tables per kind, keyed by namespaced string ID, hold structured mechanics plus rules text, imported from
embedded seed data (SRD 5.2.1, `DESIGN.md` §25.1–25.2). Custom definitions use `content:` references plus optional
symbolic IDs.

- **I-65** Ruleset content is immutable at runtime; corrections arrive as new dataset versions.
- **I-66** The importer is idempotent per ruleset+version and runs when a database lacks that version.
- **I-67** `define_content` creates campaign-owned `content:` entities, optionally with `custom:` symbolic IDs;
  installed namespaces are closed to runtime mutation.
- **I-68** Deleting a custom definition referenced by any inventory entry or character is a validation failure.

---

## 19. Aggregate and Transaction Boundary Summary

| Aggregate       | Root                                         | Includes                                            | Typical atomic operations                             |
|-----------------|----------------------------------------------|-----------------------------------------------------|-------------------------------------------------------|
| Campaign config | Campaign                                     | policy, rules options, clock                        | setup commit, complete                                |
| Setup draft     | CampaignSetupDraft                           | preferences, refs to draft-lifecycle characters     | update, validate, commit                              |
| Character       | Character                                    | staged/runtime state, inventory, effects, resources | finalize, activate, runtime change, rest, materialize |
| Player control  | PlayerControlAssignment                      | player seat → character                             | assign, transfer                                      |
| Party           | PartyMembership rows                         | —                                                   | membership change                                     |
| Relationship    | Relationship (pair)                          | event refs                                          | update relationship                                   |
| Encounter       | Encounter                                    | participants, order, pending choices                | action, turn, end                                     |
| Narrative       | Quest/Faction/StoryBeat/Seed/WorldEventState | —                                                   | upsert, Director commit                               |
| World map       | Location                                     | connections, features                               | materialize, move                                     |
| Logs            | Event/Journal/Audit                          | checkpoint markers                                  | append only                                           |

Cross-aggregate operations (an action damaging a character, a trade, a move advancing the clock) commit in one
transaction, increment each touched revision and append one journal entry.

- **I-69** A tool invocation is one transaction: partial mutation is never observable.

---

## 20. Open Items for Downstream Documents

1. Numeric vs. qualitative relationship dimensions — `RULES_ENGINE.md` §7.
2. Tactical-grid schema (zones vs. x,y, movement costs, ranges) — deferred; the participant position accepts either.
3. Seed-data JSON schemas per remaining content kind — `RULES_ENGINE.md` §7.
