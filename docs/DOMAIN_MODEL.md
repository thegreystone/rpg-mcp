# RPG MCP Server — Domain Model

**Status:** Initial domain model
**Purpose:** Define the entities, aggregates, state machines, invariants, and transaction boundaries that implement
`DESIGN.md` and `MCP_PROTOCOL.md`.
**Scope:** Conceptual data model and lifecycle rules. Physical schema, migrations, and storage details belong to
`DATABASE.md`; deterministic mechanics belong to `RULES_ENGINE.md`.

The governing rule remains:

> **The AI improvises the fiction; the engine owns the truth.**

This document defines what "the truth" is made of.

---

## 1. Normative Language

**MUST**, **MUST NOT**, **SHOULD**, and **MAY** are used as in `MCP_PROTOCOL.md`.

Invariants are numbered `I-1`, `I-2`, … and are intended to become automated tests. Tests assert these invariants as
sanity properties; only checkpoint restoration is verified by exact equality.

---

## 2. Modeling Principles

### 2.1 Identity

Every persisted domain entity has a table-scoped, monotonically increasing numeric primary key. Each new character
receives the next character-table ID regardless of whether it is being created as a prospective player avatar,
companion, innkeeper, or monster. At the protocol boundary the ID is rendered as a typed reference (`character:1`,
`location:7`, `event:42`).

> **Identity is stable; name, role, control, membership, and life state are mutable.**

- **I-1** An entity's numeric ID never changes and is never reused within its table; character IDs are allocated from
  one monotonically increasing character sequence, and an allocated character row is archived rather than deleted.
- **I-2** No mutable attribute (name, role, control, life state, party membership) participates in any primary key.
- **I-3** All cross-entity references are numeric foreign keys to stable IDs — never names, display strings, or role
  labels.

### 2.2 One character table

Every participating creature is a row in the character table: player character, companion, hireling, innkeeper, bandit,
dragon. There are no separate NPC or monster instance tables.

- Player control is a mutable campaign assignment referencing a character row; it is not encoded in the character ID or
  permanent character type.
- Being "a companion" or "an NPC" is a derived description (control + party membership + prominence), not stored
  identity.
- A creature instantiated from a content definition (e.g. `srd5e:creature/bandit`) is an ordinary character row whose
  provenance records the source definition.

### 2.3 Definitions vs. instances

**Installed content definitions** (items, spells, creatures, conditions, classes, …) are read-mostly data identified by
namespaced string IDs such as `srd5e:item/longsword`. Campaign-scoped custom definitions are ordinary numeric entities
rendered as `content:N`; they may additionally have a campaign-local symbolic ID such as
`custom:item/bellhaven-broadsheet`. Definitions are imported from embedded JSON seed data or created by
`define_content`.

**Instances** are campaign state referencing definitions: an inventory entry references an item definition; a character
row may reference the creature definition it was materialized from; an active effect references a condition definition.

- **I-4** Campaign state never copies definition data it can reference, except where a snapshot is explicitly required (
  see §14 checkpoints and `RULES_ENGINE.md` derived-value rules).
- **I-5** `custom:` definitions are scoped to one campaign and are invisible to every other campaign.

### 2.4 Planning state vs. canonical state

Campaign-setup drafts (`draft:N`) and Director seeds (`seed:N`) are planning state. They become active gameplay truth
only through an explicit commit/materialize operation.

Characters are the deliberate exception to row creation at commit: character creation reserves a normal character row
and incrementing ID immediately, with lifecycle state `DRAFT`. Campaign commit promotes that same row; it never replaces
it with another identity.

- **I-6** A `DRAFT` character may be referenced only by its owning creation/setup workflow and staged character state.
  It is excluded from ledger events, relationships, active inventory, party membership, world queries, and encounters
  until promoted to `ACTIVE`.

### 2.5 Revisions and provenance

Mutable aggregates carry an integer `revision`, incremented on every committed mutation of the aggregate. Mutations that
operate on previously read state compare `expected_revision` and fail with `CONFLICT` on mismatch.

Every mutation records **provenance**:

- `PLAYER` — direct player decision (level-up choices, purchases)
- `GM` — GM narrative/mechanical initiation
- `DIRECTOR` — committed Director change
- `MECHANICAL_CONSEQUENCE` — automatic engine consequence (damage, XP, expiry)
- `ADMINISTRATIVE_OVERRIDE` — explicit audited override

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

The top-level aggregate root. All campaign-scoped entities carry `campaign_id`.

**Attributes:** title (may be generated at commit), ruleset + version, creation date, status, content profile
configuration, rules options, continuation policy, GM override policy, authorship/experience preferences, current
location ref, active session ref, campaign revision.

### 4.1 Campaign status state machine

```text
SETUP ──> READY_TO_PLAY ──> ACTIVE ──> SUSPENDED ──> ACTIVE
                              │
                              ├──> COMPLETED
                              ├──> FAILED
                              └──> ABANDONED
```

- `SETUP` — created, not committed. Setup draft exists; no gameplay operations are legal.
- `READY_TO_PLAY` — committed, no session yet started.
- `ACTIVE` / `SUSPENDED` — normal play alternation via `bootstrap_session` / `suspend_session`.
- Terminal states are set only by `complete_campaign` with an explicit reason.

- **I-7** Rules configuration and continuation policy are immutable after campaign commit (changes, if ever supported,
  are explicitly versioned — not silent edits).
- **I-8** Gameplay tools are illegal while status is `SETUP`; setup tools are illegal after commit. The harness state (
  §16) enforces this.

### 4.2 Campaign setup draft

A single resumable draft aggregate per campaign in `SETUP`, holding: content profile answers, player constraints (stored
in least-specific sufficient form), experience preferences, rules choices, continuation policy, party design
preferences, references to `DRAFT` character rows, and the proposed player-control assignment. Creative fields may hold
the explicit value `SURPRISE_ME` (delegated ≠ missing).

`commit_campaign_setup` atomically: validates the whole graph, creates canonical campaign configuration, promotes
finalized character rows to `ACTIVE`, creates the player-control assignment, initial party memberships, relationships,
adventure/Director state, and initial world, writes the setup ledger event, sets status `READY_TO_PLAY`, and marks the
setup draft consumed.

- **I-9** After commit, no canonical entity references the setup draft.

---

## 5. Character

The most important aggregate. One row per creature, in three attribute groups:

### 5.1 Identity and narrative (mutable, non-mechanical)

name, description, appearance, personality summary, backstory summary, goals, alignment, age, sex/presentation. Changed
by `update_character` (canonical) or draft operations (pre-commit).

### 5.2 Rules identity (slow-changing, rules-validated)

species (with its special traits and lineage/ancestry choice), background (SRD origin), class(es) and subclass(es),
level per class, ability scores, proficiencies, known/prepared spells, feats, max HP, speed, senses. Changed only by
rules-governed transactions (level-up, materialization) or audited override.

### 5.3 Runtime state (fast-changing)

current HP, temporary HP, life state, conditions/effects (via ActiveEffect), resource pools (spell slots, class
resources — via ResourceState), exhaustion, current location ref, encounter participation ref, and money (canonical
copper).

### 5.4 Lifecycle, player control, and life state

```text
character_lifecycle: DRAFT | FINALIZED_DRAFT | ACTIVE | ARCHIVED
life_state:  ALIVE | DYING | DEAD
```

- Player control is represented by `PlayerControlAssignment`, which links a campaign/player seat to one active
  character. The MVP has one human-player seat; this model permits later multiplayer without changing character
  identity.
- `transfer_player_control` changes that assignment; nothing about either character's identity changes (**I-10**).
- Death sets `life_state = DEAD` and ends party activity via membership state; the row, its relationships, and its
  history remain (**I-11**).
- Resurrection, if the ruleset supports it, restores `life_state` on the same row (**I-12** — no new identity).
- Draft/finalized characters are persisted but cannot participate in gameplay. `ARCHIVED` is terminal for a draft that
  was abandoned without ever becoming active.

### 5.5 Derived values

Base values are stored; derived values (AC, save bonuses, carry capacity, encumbrance) are computed by the rules engine
on read and are never independently stored, except inside checkpoint/audit snapshots.

- **I-13** `0 ≤ current_hp ≤ max_hp` (+ temporary HP tracked separately); every HP change is journaled with its cause.
- **I-14** Money is a single non-negative integer in canonical copper. Denominations exist only at
  presentation/exchange.
- **I-15** A character has at most one current location and at most one active encounter participation.

### 5.6 Character creation state

Character creation writes to the reserved character row plus campaign-owned staged rules state. Completeness/validation
state and generated-but-not-active values, such as rolled ability scores, are tied to the character ID. Rolled
generation results cannot be silently rerolled (**I-16**).

`commit_character_draft` changes `DRAFT` to `FINALIZED_DRAFT`; it does not allocate a new ID. The enclosing
campaign/runtime transaction later promotes the same row to `ACTIVE` and activates starting inventory, money, and
resources.

---

## 6. Party Membership

Party composition is history, not a set: one row per membership episode.

**Attributes:** character ref, state, joined/left game time, cause (event ref), role notes.

```text
ACTIVE ──> SEPARATED ──> ACTIVE      (temporary separation)
ACTIVE ──> LEFT | DISMISSED | DEAD   (episode ends)
GUEST  ──> ended                     (temporary ally)
```

A new episode (rejoin) is a new row referencing the same character.

- **I-17** A character has at most one membership row in a non-terminal state at any time.
- **I-18** Every membership transition writes a ledger event; the membership row references it.
- **I-19** The party is a query over membership state — there is no stored party list to drift out of sync.

---

## 7. Relationship

One aggregate per ordered character pair with recorded state (directional dimensions where they differ; a mutual summary
where they do not).

**Attributes:** from/to character refs, dimension values (affection, trust, respect, attraction, fear, resentment,
loyalty — numeric or qualitative per `RULES_ENGINE.md` decision), compact current summary, significant event refs (
selective, identity-defining only), revision.

- Created on first recorded development (upsert semantics).
- **I-20** Relationship state changes are caused by events/behavior: every committed change carries provenance and a
  cause (event ref or reason).
- **I-21** Significant-event references point at ledger events that exist and involve both characters.
- **I-22** Relationship *seeds* (possibility of romance/rivalry) live in Director planning state, never as predetermined
  values in the relationship aggregate.

---

## 8. Inventory and Items

**InventoryEntry:** owner (character or container or location or loot source), item definition ref, quantity, equipped
flag/slot, charges/state, container nesting ref.

- **I-23** Quantity is positive; zero-quantity entries are deleted.
- **I-24** An entry's item definition must exist and be visible to the campaign (ruleset namespace or own `custom:`).
- **I-25** Transfers, trades, and loot grants are atomic: money and item movements commit together or not at all; the
  engine performs all accounting.
- **I-26** Equipped state is validated against the item's slot rules; equipment changes recompute derived values (AC,
  encumbrance) on next read.
- **I-27** Carry weight is derived from entries + canonical coin weight; never stored.

---

## 9. Effects, Conditions, and Resources

**ActiveEffect:** target character ref, source (character/item/spell/environment + provenance), condition/effect
definition ref or inline modifier spec, start (game time + journal ID), duration/expiry condition, concentration link,
stacking key.

- **I-28** Expiry is evaluated on time advancement and encounter round transitions; expired effects are removed as
  `MECHANICAL_CONSEQUENCE` journal entries.
- **I-29** Breaking concentration removes all effects linked to it, atomically with the cause.

**ResourceState:** character ref, resource definition ref (spell slot level, class resource), current/max, recharge rule
ref.

- **I-30** `0 ≤ current ≤ max`; spending below zero is a validation failure, never a clamp.

---

## 10. Encounter

A first-class state machine aggregate.

**Attributes:** status, participants, sides and side relationships (ALLIED, HOSTILE, NEUTRAL, TEMPORARILY_COOPERATIVE,
UNKNOWN), initiative order, current round, current turn, environment/terrain metadata, objectives, spatial model,
encounter revision, retry checkpoint ref (when policy is ENCOUNTER_RETRY).

**Participant:** character ref, side, initiative value, position (zone label; x,y when the tactical grid capability is
active), participation status (ACTIVE, DEFEATED, FLED, REMOVED).

```text
CREATED ──> RUNNING ──> ENDED
              │  ▲
              └─ WAITING_CHOICE (typed pending transaction)
```

- **I-31** Turn order is total and owned by the engine; `perform_encounter_action` is legal only for the character whose
  turn it is (or a legal reaction resolved via pending choice).
- **I-32** One action resolves atomically: roll, resource expenditure, damage/healing, conditions, defeat transitions,
  encounter log entry, and next-turn state commit together.
- **I-33** An encounter cannot end while mandatory pending choices remain unresolved.
- **I-34** Encounter completion commits final state, mechanically defined XP, and its ledger event in one transaction;
  characters' runtime state after `end_encounter` equals their in-encounter final state.
- **I-35** Under ENCOUNTER_RETRY policy, the retry checkpoint exists before the first round begins.

---

## 11. Narrative State

All narrative aggregates are campaign-scoped, carry revision, provenance, and visibility, and are mutated only through
`upsert_narrative_state` or Director commits.

**Quest:** objective, issuer ref, status (`OFFERED, ACCEPTED, COMPLETED, FAILED, ABANDONED`), participants, rewards,
deadlines (game time), dependencies, hidden objectives (visibility-scoped), related story beat refs.

**Faction:** goals, relationships to other factions, member refs, player standing, resources/influence,
knowledge/secrets (visibility-scoped).

**StoryBeat:** description, state (`PLANNED, AVAILABLE, BLOCKED, SUPERSEDED, COMPLETED, ABANDONED`), preconditions,
related refs.

**Seed** (Director planning): kind (`STORY_SEED, COMPANION_INTRO, PRESSURE, PACING_INTENT`), payload, state (
`OPEN, MATERIALIZED, SUPERSEDED, EXPIRED`), superseded-by ref.

**WorldEventState:** committed off-screen facts with game time, affected refs, and the diegetic channels through which
they may surface.

- **I-36** A superseded plan retains its history; superseding preserves the underlying intention via the successor's
  reference (adaptive companion introduction relies on this).
- **I-37** Director output constrains the world, never the player: no narrative aggregate may encode a required player
  choice or predetermined relationship outcome.
- **I-38** `get_diegetic_information` only reads committed WorldEventState; it never creates facts.

---

## 12. Location and Movement

**Location:** kind (`REGION, SETTLEMENT, DISTRICT, SITE, BUILDING, ROOM/AREA`), parent ref (containment tree), name,
description, materialization state (`SEMANTIC, MATERIALIZED`), stable generation seed, secrets/features (
visibility-scoped).

**Connection:** location A ref, location B ref, kind (road, river, door, passage…), travel constraints, approximate
distance/time, state (open/locked/blocked/secret with visibility).

Dungeons are location subtrees of ROOM/AREA nodes joined by connections; the same model serves world, settlement, and
dungeon maps.

- **I-39** Materialization is one-way and stable: once MATERIALIZED, generated detail persists and later reads agree (
  `generate lazily, persist eagerly`).
- **I-40** `move_party` requires a known traversable connection path (or explicit authorized route), updates every moved
  character's location, advances the clock, and commits atomically.
- **I-41** Materialization may not contradict committed geography except through audited override.

---

## 13. Time

**GameClock** (one per campaign): calendar definition ref, current instant, monotonic `sequence`.

- **I-42** In normal operation the clock never moves backward and `sequence` strictly increases with every advancement.
  Checkpoint restoration is the sole exception: it returns the clock to the checkpoint's value as part of rolling back
  the journal.
- **I-43** Every ledger event and journal entry records the game time at which it was recorded; retroactive events
  additionally carry their fictional game time, which may be earlier.
- **I-44** Time advancement processes, in order: effect expiry, scheduled world events, rest resolution where
  applicable, Director trigger evaluation — each committed as `MECHANICAL_CONSEQUENCE`.

---

## 14. Ledger, Journal, Audit, and Checkpoints

Three append-oriented logs with distinct rollback behavior:

### 14.1 Event ledger (semantic memory)

Significant campaign events: type, actors (character refs), location ref, fictional game time, recording game time,
compact summary, structured payload, importance (`MINOR, NOTABLE, MAJOR, CRITICAL`), visibility, causal event refs,
optional rich episodic summary.

- **I-45** Event IDs are insertion-ordered and never reused (`AUTOINCREMENT` or equivalent).
- **I-46** Fictional order and insertion order are independent; retroactive recording (later ID, earlier game time) is
  valid.
- **I-47** Mechanical operations write their own ledger events; the AI does not duplicate them.

### 14.2 Change journal (canonical mutations)

Every canonical mutation appends a journal entry: insertion ID, operation, affected aggregate refs + revisions,
provenance, undo information (or state delta sufficient for rollback), and the `operation_id` for idempotency.

**Checkpoints are marker entries in this journal.** A checkpoint row references its marker journal ID plus metadata (
reason, game time, created by).

- **I-48** Every canonical mutation flows through the journal — there is no out-of-band write path. (This is what makes
  checkpoints correct.)
- **I-49** Journal IDs are insertion-ordered and never reused.
- **I-50** Restoring a checkpoint rewinds every campaign-owned mutable canonical aggregate to the marker by insertion
  ID, not game date. This includes all character and staged/runtime state, player-control assignments, inventory/money,
  custom content, locations/connections, party membership, relationships, quests, factions, story/Director state,
  encounters, pending gameplay workflows, versioned campaign configuration, the semantic ledger, and the game clock. New
  mutable canonical aggregates are rewindable unless explicitly specified otherwise.
- **I-51** Checkpoint round-tripping (create → mutate → restore) yields exact equality for rewindable canonical state.
  Immutable audit lineage, idempotency records, physical checkpoint metadata, installation configuration, and session
  audit records are excluded from equality. This is the one exact-match state test in the suite.
- **I-52** Ledger events recorded after the marker become non-canonical on restoration because recording them is itself
  journaled. Their IDs are never reused, and immutable audit lineage can still establish that the discarded branch and
  restoration occurred.

### 14.3 Audit log (survives rollback)

Overrides, discretionary XP, checkpoint restorations, administrative edits, policy changes: actor, provenance, reason,
before/after values, real timestamp.

- **I-53** The audit log is never rolled back. A restoration writes an audit record that survives the restoration it
  describes.
- **I-54** An override result is always labeled as an override; it is never presented as a rules-derived or random
  result.

---

## 15. Sessions, Pending Transactions, and Policy

**Session:** start/end (real + game time), starting/ending journal ID refs, compact summary, events-written count.

**PendingTransaction:** kind (
`CHARACTER_CREATION, CAMPAIGN_COMMIT, LEVEL_UP, ENCOUNTER_CHOICE, LOCATION_MATERIALIZATION, TRAVEL_INTERRUPT, REST_INTERRUPT`),
status (`OPEN, COMMITTED, ABANDONED, EXPIRED`), controlled aggregate refs, draft payload, legal operations, revision.

- **I-55** At most one open transaction exclusively controls a given aggregate.
- **I-56** Abandoning or expiring a transaction leaves canonical state untouched.
- **I-57** Level-up commits atomically: rules validation, character rules-identity changes, resource maxima, ledger
  event.

**PolicyState:** campaign content profile, player constraints (least-specific sufficient form), GM override policy,
effective-policy computation inputs. Provider policy is never persisted as campaign truth (**I-58**).

- **I-59** The server rejects profile selections exceeding the player constraint cap; enforcement is server-side, not
  narration-side.

**Idempotency:** processed `operation_id`s are retained with their results per campaign.

- **I-60** Replaying an `operation_id` with equivalent arguments returns the original result without re-applying;
  different arguments yield `IDEMPOTENCY_CONFLICT`.

---

## 16. Harness State Machine

Protocol-visible states (from `EXECUTION_MODEL.md` §25, plus `READY_TO_PLAY`):

```text
CAMPAIGN_SELECTION
SETUP_CONTENT_PROFILE → SETUP_EXPERIENCE → SETUP_RULES → SETUP_CONTINUATION
CHARACTER_CONCEPT → CHARACTER_RULES → CHARACTER_PERSONALITY → CHARACTER_EQUIPMENT → CHARACTER_REVIEW
PARTY_DESIGN → ADVENTURE_INITIALIZATION → CAMPAIGN_REVIEW → CAMPAIGN_COMMIT → READY_TO_PLAY
SESSION_BOOTSTRAP → EXPLORATION ⇄ ENCOUNTER
LEVEL_UP, CHECKPOINT_DECISION, PLAYER_CHARACTER_TRANSFER, SESSION_SUSPEND
CAMPAIGN_COMPLETED, CAMPAIGN_FAILED, CAMPAIGN_ABANDONED
```

The harness answers "which operations are legal now." Setup states may be revisited freely before commit; the exact
order may vary where dependencies allow. Internal substates are permitted but the protocol-visible set is stable within
a protocol major version.

- **I-61** Every tool invocation is validated against the harness state; `allowed_operations` in responses is advisory,
  server validation is not.
- **I-62** Harness state is itself canonical (journaled, checkpointed, restored).

---

## 17. Visibility and Knowledge

Visibility labels on events, secrets, hidden objectives, seeds, and location features:

```text
PLAYER_KNOWN | PARTY_KNOWN | CHARACTER_KNOWN(refs) | FACTION_KNOWN(ref) | GM_ONLY | DIRECTOR_ONLY
```

- **I-63** Visibility filtering happens server-side at read time; secret fields are omitted, not merely flagged.
- **I-64** Knowledge transitions (a secret becoming PARTY_KNOWN) are journaled mutations with a cause.

---

## 18. Content Definitions

Installed read-mostly tables per kind (item, weapon detail, armor detail, spell, creature, condition, class/feature,
background, …), keyed by namespaced string ID, hold structured mechanics plus rules text and are imported from embedded
JSON seed data (SRD 5.2.1 tables per `DESIGN.md` §25.1–25.2). Campaign custom definitions use incrementing numeric IDs/
`content:` references plus optional campaign-local symbolic IDs.

- **I-65** Ruleset content is immutable at runtime; corrections arrive as new dataset versions via the importer.
- **I-66** The importer is idempotent per ruleset+version and runs when a database lacks that version.
- **I-67** `define_content` creates campaign-owned `content:` entities and may assign them `custom:` symbolic IDs; the
  `srd5e:` (and any installed ruleset) namespace is closed to runtime mutation.
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

Cross-aggregate operations (an encounter action damaging a character; a trade moving money and items; movement advancing
the clock) commit in **one database transaction** touching multiple aggregates, incrementing each touched aggregate's
revision, and appending one journal entry describing the whole operation.

- **I-69** A tool invocation is one transaction: partial mutation is never observable.

---

## 20. Open Items for Downstream Documents

1. Physical schema, indexes, and journal undo representation — `DATABASE.md`.
2. Numeric vs. qualitative relationship dimensions — `RULES_ENGINE.md` (with `DESIGN.md` §36.3).
3. Derived-value computation rules and caching — `RULES_ENGINE.md`.
4. Tactical-grid schema (zones vs. x,y, movement costs, ranges) — deferred; the Participant position field is designed
   to accept either.
5. Calendar definition format beyond instant + sequence — `DATABASE.md` / `RULES_ENGINE.md`.
6. Seed-data JSON schemas per content kind — `RULES_ENGINE.md` + importer spec.
7. Context Builder selection/ranking heuristics — implementation detail behind `bootstrap_session`/`get_context`, not
   domain state.
