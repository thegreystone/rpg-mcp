# RPG MCP Server — Protocol

**Status:** Initial normative protocol design  
**Purpose:** Define the authoritative contract between an AI Game Master and the RPG MCP server.  
**Scope:** MCP capabilities, semantic operations, payload conventions, workflow constraints, canonical state, context
retrieval, errors, atomicity, idempotency, and audit behavior.

This document translates `DESIGN.md`, `EXECUTION_MODEL.md`, and `EXECUTION_EXAMPLE.md` into an implementation-facing
protocol. Those documents define product intent and execution rationale. This document defines how a compatible AI
client interacts with the server.

The core rule is:

> **Conversation proposes; protocol operations resolve and commit; narration explains.**

The AI owns interpretation and narration. The server owns workflow legality, canonical facts, deterministic mechanics,
persistence, and auditable exceptional mutations.

---

## 1. Normative Language

The words **MUST**, **MUST NOT**, **SHOULD**, **SHOULD NOT**, and **MAY** are normative.

- **MUST/MUST NOT** define compatibility requirements.
- **SHOULD/SHOULD NOT** define strong defaults that may be departed from for a documented reason.
- **MAY** identifies optional behavior.

Examples use YAML-like notation for readability. Concrete MCP transport encoding uses the MCP SDK's supported
JSON-compatible values.

This document is the normative **semantic** contract: tool names, operation meaning, invariants, atomicity, and required
behavior are normative unless explicitly marked as illustrative or future work. Example payloads show intended shapes
but are not complete wire schemas. Versioned machine-readable schemas will define exact required fields, discriminated
unions, and response shapes; a server MUST NOT claim complete protocol `0.1` conformance until it implements those
schemas.

---

## 2. Protocol Design Principles

### 2.1 Semantic operations

Tools express game intent rather than storage operations.

Prefer:

```text
perform_action
transfer_item
advance_time
record_memory
```

Do not expose generic operations such as `update_row`, `set_field`, or arbitrary SQL.

### 2.2 Server-authoritative truth

The server MUST be authoritative for:

- harness and transaction state
- entity identity
- dice and deterministic resolution
- HP, resources, conditions, inventory, money, and progression
- encounter state and turn order
- committed campaign, world, quest, faction, and relationship state
- checkpoints and restoration
- visibility and knowledge boundaries
- the event and audit ledgers

An AI statement does not become canonical merely because it appeared in narration.

### 2.3 Compact normal context, deep retrieval on demand

Routine operations SHOULD return enough information for the AI to continue without immediately issuing several lookup
calls. They MUST NOT return an unbounded campaign history.

Detailed episodic history is retrieved only when relevant.

### 2.4 Discoverability

A newly connected compatible AI MUST be able to determine:

- server and protocol version
- available capabilities
- current harness state
- active campaign and pending transaction, if any
- legal next operations
- applicable policy constraints

No previous chat transcript may be required.

### 2.5 Invisible machinery

Technical references, tool traces, internal workflow names, and policy implementation details SHOULD remain absent from
player-facing narration unless explanation is useful or the user explicitly requests diagnostics.

---

## 3. MCP Surface

The server exposes three kinds of MCP surface.

### 3.1 Tools

Tools perform parameterized queries, deterministic resolution, or canonical mutations. They are the primary interface.

### 3.2 Resources

Resources expose stable, read-only protocol guidance and compact metadata that benefit from caching.

Required resources:

```text
rpg://protocol/guide
rpg://protocol/capabilities
rpg://rulesets
```

`rpg://protocol/guide` contains concise instructions for an AI Game Master, including tool-use rules and the distinction
between the Harness, GM, and Narrative Director. The guide is authored as a versioned markdown artifact in the
repository — derived from `EXECUTION_MODEL.md` section 24 — embedded into the executable like other resources, and
maintained alongside this protocol document.

`rpg://protocol/capabilities` identifies protocol version, optional capabilities, limits, and supported content/rules
features.

`rpg://rulesets` lists installed rulesets and versions without returning their complete rules data.

Campaign state MUST NOT be exposed as a cacheable static resource. It is retrieved through tools so freshness,
visibility, and authorization are explicit.

### 3.3 Prompts

MCP prompts are optional. The protocol does not depend on them. A server MAY provide reusable setup, bootstrap, or
Director prompts, but clients MUST be able to operate from the guide resource and tool schemas alone.

---

## 4. Versioning and Capabilities

The protocol uses semantic versions.

```yaml
protocol:
  name: rpg-mcp
  version: 0.1.0
server:
  name: rpg-mcp-server
  version: 0.1.0
capabilities:
  rulesets: true
  setup: true
  encounters: true
  checkpoints: true
  director: true
  relationship_memory: true
optional_capabilities:
  full_text_memory_search: false
  semantic_memory_search: false
  tactical_grid: false
  remote_transport: false
limits:
  maximum_page_size: 100
  maximum_context_budget: 32000
```

A breaking change increments the protocol major version. Additive tools, fields, enum values, and capabilities increment
the minor version.

Clients MUST ignore unknown response fields. Clients MUST NOT assume they understand unknown enum values; they should
preserve them when possible and request supported alternatives when necessary.

---

## 5. Common Data Conventions

### 5.1 Typed references

Persisted entities use table-scoped numeric primary keys internally and typed references at the protocol boundary:

```text
campaign:1
character:4
location:7
event:42
encounter:3
checkpoint:2
content:5
```

A typed reference has the grammar:

```text
<entity-type>:<positive-integer>
```

References are opaque to clients. Clients MUST NOT infer ordering, ownership, or mutability from the numeric portion.

Within each entity table, numeric IDs are allocated monotonically. All characters share one character sequence:
`character:1` may be a prospective player avatar, `character:2` an innkeeper, and `character:3` a dragon. Which
character a player controls is a mutable campaign assignment returned by context/state operations; it is never encoded
in the ID.

Besides persisted entities, the protocol uses typed references for workflow objects:

```text
draft:3        non-character draft aggregate under construction
seed:1         Director planning entity (e.g. a planned companion)
transaction:9  pending multi-step transaction
roll:184       auditable random result
```

A seed or non-character draft is planning state, never a canonical gameplay entity. Characters are the deliberate
exception: character creation immediately reserves an ordinary character-table row and stable `character:` reference
with lifecycle state `DRAFT`. Every creature — player character, companion, hireling, or monster — uses that same table
and identity model; lifecycle state and player control are mutable state, not different entity kinds.

Names, roles, party membership, control, and life state are not identity.

### 5.2 Stable content identifiers

Rules and content definitions use stable namespaced identifiers:

```text
srd5e:spell/fire-bolt
srd5e:item/longsword
srd5e:condition/poisoned
```

Display names are localized mutable metadata and MUST NOT be used as identifiers.

Campaign-defined custom content receives a campaign-owned typed reference such as `content:5`. It MAY also have a
human-readable symbolic identifier in the reserved `custom:` namespace, for example `custom:item/bellhaven-broadsheet`.
That symbolic identifier is unique only within its owning campaign and MUST NOT be used by itself as a cross-campaign
protocol reference. Protocol operations identify custom content by its `content:` reference, or by the compound pair
`(campaign_ref, symbolic_id)` when resolving a name. Installed definitions continue to use globally stable ruleset
identifiers such as `srd5e:item/longsword`.

### 5.3 Time

Real timestamps use RFC 3339 UTC strings. Campaign time uses a structured game-time value:

```yaml
game_time:
  calendar: campaign:default
  instant: "Year 1, Spring 12, 18:30"
  sequence: 4812
```

`sequence` is a monotonically increasing campaign ordering value. It permits reliable ordering even when a fictional
calendar is imprecise.

Fictional order is independent of insertion order. Ledger entries carry incremental numeric IDs assigned on append; an
event recorded retroactively (a flashback, backfilled history) receives a later ID with an earlier game time. Insertion
order is authoritative for journal/checkpoint semantics; game time is authoritative for fictional chronology.

### 5.4 Pagination

Potentially large reads use opaque cursor pagination:

```yaml
page:
  items: []
  next_cursor: opaque-or-null
```

Clients MUST NOT parse cursors.

### 5.5 Visibility

Information returned to the AI is labeled when visibility is relevant:

```yaml
visibility: GM_ONLY
```

Initial visibility values are:

- `PLAYER_KNOWN`
- `PARTY_KNOWN`
- `CHARACTER_KNOWN`
- `FACTION_KNOWN`
- `GM_ONLY`
- `DIRECTOR_ONLY`

The AI MUST NOT reveal non-player-known information merely because it was returned in GM or Director context.

### 5.6 Revisions

Mutable aggregates expose an integer `revision`. A mutation derived from previously read aggregate state MUST include
`expected_revision`. Commands against a pending transaction MUST include that transaction's revision. A schema MAY omit
`expected_revision` only for an operation whose semantics are explicitly blind and commutative, or whose target revision
is created within the same atomic call.

If the aggregate changed, the server returns `CONFLICT` rather than silently applying a stale decision.

---

## 6. Common Response and Error Model

Successful tool responses return the operation-specific result plus common metadata where relevant:

```yaml
result: { ... }
meta:
  campaign: campaign:1
  harness_state: EXPLORATION
  campaign_revision: 83
  warnings: []
  suggested_next_operations: []
```

Failures return a structured MCP tool error:

```yaml
error:
  code: OPERATION_NOT_ALLOWED
  message: "An encounter cannot start while character creation is pending."
  retryable: false
  details:
    harness_state: CHARACTER_REVIEW
    allowed_operations:
      - validate_character_draft
      - commit_character_draft
      - update_character_draft
```

Required error codes:

- `INVALID_ARGUMENT`
- `NOT_FOUND`
- `OPERATION_NOT_ALLOWED`
- `VALIDATION_FAILED`
- `CONFLICT`
- `INSUFFICIENT_RESOURCE`
- `VISIBILITY_DENIED`
- `POLICY_DENIED`
- `CAPABILITY_UNAVAILABLE`
- `TRANSACTION_REQUIRED`
- `TRANSACTION_EXPIRED`
- `IDEMPOTENCY_CONFLICT`
- `INTERNAL_ERROR`

Validation failures SHOULD identify every independently actionable issue in one response.

```yaml
error:
  code: VALIDATION_FAILED
  details:
    violations:
      - path: ability_scores.strength
        rule: POINT_BUY_TOTAL
        message: "The proposed scores exceed the available point budget."
```

Errors MUST NOT partially mutate state unless a tool explicitly documents partial progress. MVP tools do not permit
partial mutation.

---

## 7. Idempotency, Atomicity, and Randomness

### 7.1 Idempotency

Every mutating tool MUST accept an `operation_id`, generated by the client and unique within the campaign (or unique
server-wide for operations issued before a campaign exists, such as `create_campaign`):

```yaml
operation_id: "01J8..."
```

Repeating the same tool with the same `operation_id` and equivalent arguments returns the original result without
applying the mutation twice.

Reusing an `operation_id` with different arguments returns `IDEMPOTENCY_CONFLICT`.

### 7.2 Atomicity

A tool invocation is atomic. Mechanical resolution and all consequences owned by that operation commit together.

For example, `perform_encounter_action` commits the roll, resource expenditure, damage, conditions, defeat transitions,
encounter log entry, and next-turn state in one transaction.

### 7.3 Randomness

All authoritative random results are generated by the server and returned with an auditable breakdown.

```yaml
roll:
  expression: 1d20+5
  dice: [17]
  modifier: 5
  total: 22
  roll_ref: roll:184
```

The AI MUST NOT supply a desired die result to an ordinary resolution tool.

Test environments MAY support an explicit deterministic seed or scripted roller. Production override behavior uses the
audited override protocol, never hidden manipulation.

---

## 8. Harness Discovery

### 8.1 `get_server_state`

This is the first tool a newly connected AI calls. It replaces separate initial calls for harness state and campaign
listing.

Input:

```yaml
include_campaigns: true
campaign_limit: 20
campaign_cursor: null
```

Output:

```yaml
protocol_version: 0.1.0
harness_state: CAMPAIGN_SELECTION
active_campaign: null
pending_transaction: null
campaigns:
  - ref: campaign:1
    title: The Ashen Road
    status: ACTIVE
    player_character: Richard Greystone
    last_played_at: "2026-08-31T19:14:00Z"
next_campaign_cursor: opaque-or-null
allowed_operations:
  - create_campaign
  - open_campaign
policy_summary: { ... }
```

This aggregate bootstrap operation answers the execution model's initial discovery needs in one round trip. When
`next_campaign_cursor` is non-null, the client retrieves the next page by repeating the call with that value as
`campaign_cursor`. Campaign ordering remains stable for the lifetime of a cursor; clients MUST restart pagination after
a cursor expires.

### 8.2 Legal operations

Every workflow-sensitive response SHOULD include `harness_state` and `allowed_operations` when either may have changed.

The list is advisory for client guidance. The server MUST still validate every request.

Initial harness states are defined by `EXECUTION_MODEL.md` section 25. The implementation MAY use internal substates,
but protocol-visible states MUST remain stable within a protocol major version.

---

## 9. Campaign Selection and Setup

### 9.1 `create_campaign`

Creates a resumable setup draft, not a playable campaign.

Input:

```yaml
operation_id: "..."
title: null
ruleset: srd5e:2024
```

Output includes the campaign reference, setup state, outstanding requirements, and the ordered `decisions` list
described in §9.3.1.

A title MAY be supplied at creation or later through `update_campaign_setup`. If a campaign is committed without one,
the server generates a title from the committed premise so campaign listings are never unnamed.

### 9.2 `open_campaign`

Selects an existing campaign and returns whether it should resume setup, bootstrap gameplay, resolve a checkpoint
decision, or continue another pending workflow.

Opening a campaign MUST NOT silently discard pending transactions.

### 9.3 `get_setup_state`

Returns:

- committed and draft setup values
- outstanding required decisions (`outstanding`, prose) and the structured `decisions` list (§9.3.1)
- allowed operations
- applicable constraints and legal choices

#### 9.3.1 Decisions

Every setting the player must decide on is returned as a **decision**: a self-describing question with every legal
option and a one-line description of each, so the GM can put it to the player without inventing or paraphrasing
alternatives. Setup responses (`create_campaign`, `get_setup_state`, `update_campaign_setup`) carry the campaign's
decisions in interview order; character responses (`create_character_draft`, `update_character_draft`,
`get_character_choices` with a draft) carry the draft's. The first entry is the one to ask now.

```yaml
decisions:
  - id: content_profile               # stable identifier of the decision
    owner: PLAYER                     # PLAYER, or GM for sections the GM authors
    question: "Which content profile should the campaign use?"
    tool: update_campaign_setup       # the tool that records the answer
    path: changes.content_profile     # the argument field, as a dotted path
    choose: {min: 1, max: 1}          # how many options to pick
    options_are: LEGAL_VALUES         # LEGAL_VALUES (closed set) or SUGGESTIONS (free text equally valid)
    options:
      - {value: PEGI_3,  label: "PEGI 3 — all ages", description: "Cartoon peril only: …"}
      - {value: PEGI_16, label: "PEGI 16", description: "Mature fantasy: …"}
      - {value: PEGI_18, label: "PEGI 18 — adult", description: "Graphic violence, horror, adult sexuality …", recommended: true}
    recommended: PEGI_18              # the option flagged recommended, if any
    allow_custom: false               # a free-text answer is acceptable
    allow_surprise_me: false          # SURPRISE_ME is acceptable
    optional: false                   # may be skipped; then `default` applies
    note: "..."                       # advisory guidance for the GM
```

Options MAY carry extra structured fields (`speed`, `hit_die`, `level`, `school`, `ability`, `contents` …) that the
GM may quote. A decision without options (a name, an age, a personality) has no `options` and `allow_custom: true`.

Rules:

- The legal values, labels and descriptions of every closed choice live in exactly one place in the server
  (an enum, or the installed rules content). Tool descriptions MUST NOT enumerate them; they point at `decisions`.
- The list is derived from what the draft still lacks, so it shrinks as answers are recorded and never lists an
  answered decision. When nothing is outstanding it holds a single review decision (`character_review`,
  `campaign_review`) whose options are to commit or to revise.
- The GM SHOULD ask exactly one decision per turn — the first in the list — presenting every option with its
  description, numbered, plus a custom answer where `allow_custom` is true, and record the answer before reading the
  list again. Question and option texts are advisory; they MUST NOT be required verbatim prompts.

### 9.4 `update_campaign_setup`

Updates one or more setup dimensions atomically:

```yaml
operation_id: "..."
campaign: campaign:1
expected_revision: 4
changes:
  experience:
    tone: hopeful-with-serious-undercurrent
    preferences: [magic, exploration, relationships, humor]
  rules:
    continuation_policy: CHECKPOINT
    gm_override_policy: EXPLICIT_AUDITED
    hp_progression: FIRST_3_MAX
    xp_policy: SHARED
    companion_level_up: PLAYER
```

Rule keys are fixed at commit; `apply_gm_override` kind `SET_CAMPAIGN_RULE` retunes the subset that a campaign in play
is allowed to change (§20.1).

Creative fields MAY contain an explicit `SURPRISE_ME` value. The server stores that the choice is delegated; it does not
confuse delegation with missing required input.

`experience.fantasy_style` has a definite default: when neither `fantasy_style` nor `tone` was recorded — the section
was delegated, or the question skipped — the committed preferences carry `fantasy_style: EPIC`, a Baldur's Gate-style
fantasy epic (DESIGN.md §4.2). The `experience.fantasy_style` decision names that default in its question and carries
it as `default`, so the GM tells the player what "surprise me" means before they say it. The legal style names are in
`constraints.fantasy_style`; free text remains equally valid and is never replaced by the default.

### 9.5 `validate_campaign_setup`

Validates the complete setup graph without committing it. It returns violations, warnings, unresolved delegated choices,
and a compact review.

### 9.6 `commit_campaign_setup`

Commits validated setup and initial world/adventure state in one transaction.

The request MUST include the expected campaign revision and `operation_id`. On success it:

- freezes the setup result as canonical configuration
- promotes every finalized setup character from `DRAFT` to `ACTIVE`, activating its staged rules state, inventory, and
  money
- commits the initial party/companion intentions
- commits the initial adventure and Director state
- writes a setup ledger event
- advances to `SESSION_BOOTSTRAP`
- returns a compact committed summary

It MUST NOT return the complete wizard conversation.

Setup characters and their proposed mechanical state are persisted before this transaction, but they are not active
gameplay truth while their lifecycle state is `DRAFT`. Failure leaves the resumable campaign draft unchanged. Abandoning
an uncommitted campaign marks its character rows `ARCHIVED`; character rows and allocated character IDs are never
deleted or reused. Non-identity planning payload may then be removed according to retention policy.

---

## 10. Character and Party Design

### 10.1 `create_character_draft`

Creates an ordinary character-table row with a stable `character:` reference and lifecycle state `DRAFT` within the
active setup or another authorized creation workflow. The response carries the sheet and the draft's outstanding
`decisions` (§9.3.1), as does every `update_character_draft`.

The reference is reserved permanently for that character if creation completes. Draft state is persisted and resumable,
but it is excluded from active gameplay context, party eligibility, encounters, and ordinary world queries. During
campaign setup the row is owned by the campaign draft until `commit_campaign_setup` succeeds.

### 10.2 `get_character_choices`

Returns legal choices for a named decision scope — `ALL`, `ABILITY_GENERATION`, `SPECIES`, `CLASS`, `BACKGROUND`,
`FEAT`, `SKILLS`, `ALIGNMENT`, `SPELLS`, `EQUIPMENT` — every option with a one-line description drawn from the installed
content or the server's described enums (§9.3.1). Class-dependent scopes (the class skill list, spells, starting
equipment) take a `character`; with a draft character the response also carries that draft's ordered `decisions`.

### 10.3 `generate_ability_scores`

The server performs the selected method, including `STANDARD_ARRAY`, `POINT_BUY`, and `ROLL_4D6_DROP_LOWEST` when
supported by the ruleset.

Rolled results include every die and dropped value. Generated scores belong to the draft and cannot be silently rerolled
unless campaign rules permit it.

### 10.4 `update_character_draft`

Applies concept, identity, mechanical choices, appearance, personality, backstory, equipment, and other draft changes —
including the SRD background (its ability increase, Origin feat, skills, tool and equipment), species trait choices (
bonus skill, lineage/ancestry, origin feat) and feat choices.

The server validates locally checkable constraints immediately and defers whole-character constraints to validation.

### 10.5 `validate_character_draft`

Returns a complete validation result and a player-facing review summary. It does not finalize or activate the character.

### 10.6 `commit_character_draft`

Validates and changes the existing character row from `DRAFT` to `FINALIZED_DRAFT`, so it can no longer be edited
without explicitly reopening it. Its proposed rules state, inventory, and money remain staged and unavailable to
gameplay.

During initial setup, `commit_campaign_setup` atomically promotes finalized characters to `ACTIVE` and activates their
staged state. In a later authorized creation workflow, the enclosing semantic operation performs the same promotion. The
`character:` reference never changes across these lifecycle transitions.

Starting wealth MUST be resolved from explicit rules and configured modifiers. Narrative background text alone cannot
grant mechanical wealth.

### 10.7 `update_party_design`

Stores desired party composition, companion preferences, authored companion drafts, relationship seeds, and introduction
intentions.

An introduction intention is not a guarantee that a scene occurs. It is Director planning state.

### 10.8 `materialize_character`

Converts a template into a canonical character when it becomes relevant. Accepted sources are a Director companion
seed (`seed:<n>`) or an installed content definition such as `srd5e:creature/bandit` — the path by which encounter
opponents, hirelings, and ad-hoc NPCs enter play. Authored characters already have stable character rows and are
activated through their enclosing creation workflow rather than rematerialized.

The operation validates rules state, creates an ordinary character-table row, assigns a new stable `character:`
reference, records provenance (which seed/definition it came from), and MAY create initial relationships. It MUST NOT
predetermine emergent relationship outcomes.

---

## 11. Session and Context

### 11.1 `bootstrap_session`

Creates or resumes a gameplay session and returns a bounded context package sufficient to continue play.

Input:

```yaml
operation_id: "..."
campaign: campaign:1
context_budget: 12000
```

Output includes:

- campaign and session references
- harness state
- current time and location
- player-controlled character and party summaries
- current mechanical state
- active quests and immediate objectives
- nearby/relevant entities
- compact relationship summaries
- recent significant events
- applicable story seeds and visibility labels
- pending encounter, transaction, or continuation decision
- legal next operations

The server chooses content within the requested budget. Context budgets (including `maximum_context_budget`) are
expressed in approximate LLM tokens and MAY be treated as advisory targets. The server MUST prioritize canonical current
state over historical color.

### 11.2 `get_context`

Returns purpose-built context for one scope:

- `SCENE`
- `CHARACTER`
- `RELATIONSHIP`
- `LOCATION`
- `QUEST`
- `ENCOUNTER`
- `DIRECTOR`

Input includes the scope, relevant references, optional natural-language focus, and context budget.

This is not unrestricted database search. Visibility and scope rules always apply.

### 11.3 `suspend_session`

Closes the current session atomically after validating that no prohibited transaction is left open.

It records a compact session summary, current location/time, important written events, and unresolved runtime state.
Mid-encounter suspension MAY be supported as a capability; otherwise the tool returns `OPERATION_NOT_ALLOWED` with safe
alternatives.

---

## 12. Canonical Narrative State

### 12.1 `upsert_narrative_state`

Creates or updates a bounded narrative aggregate using a discriminated request type:

- `QUEST`
- `STORY_BEAT`
- `STORY_SEED`
- `FACTION_STATE`
- `WORLD_EVENT`
- `LOCATION_DETAIL`
- `NPC_AGENDA`

This consolidates closely related narrative mutations without becoming generic CRUD. Each kind has its own schema,
validation, visibility, lifecycle, and allowed transitions.

The tool MUST record provenance: `GM`, `DIRECTOR`, `MECHANICAL_CONSEQUENCE`, or `ADMINISTRATIVE_OVERRIDE`.

### 12.2 `materialize_location`

Atomically converts a location seed or unexplored semantic node into canonical detail. It returns the created location,
connections, known features, secrets with visibility labels, and revision.

The operation MUST reject contradictions with already committed geography unless performed through an explicit override.

### 12.3 `advance_time`

Advances the world clock and commits rules-governed consequences such as resource durations, scheduled events, rest
effects, and trigger recommendations.

The result distinguishes automatic canonical consequences from Director recommendations.

### 12.4 `get_diegetic_information`

Returns information currently available through a fictional channel such as newspaper, rumor, letter, town crier,
witness, market price, or environmental evidence.

The server filters by location, time, visibility, and previously committed world events. The operation does not create a
world fact merely because the AI asks for an interesting rumor.

### 12.5 `move_party`

Moves the party, or a named subset of characters, to a target location — a world location, a settlement node, or a
room/area node within a dungeon graph.

The server validates that the destination is reachable from the current position via known connections or an authorized
route. It advances atomically until either the party arrives or the first consequence requiring player/GM input
interrupts travel.

On arrival, the operation commits elapsed time, canonical locations, automatic consequences, and the movement event. On
interruption, it commits only elapsed time and movement up to the precise intermediate location, creates a
`TRAVEL_INTERRUPT` pending transaction, and returns the interruption context and legal next operations; it MUST NOT
assume the remainder of the journey occurs. After resolving the interruption, the client may resume the transaction
toward the original destination. Moving into an unmaterialized semantic node MAY require `materialize_location` first;
the error identifies this explicitly.

### 12.6 `update_party_membership`

Applies one typed party-membership change:

- `JOIN`
- `LEAVE`
- `DISMISS`
- `SEPARATE`
- `REJOIN`
- `GUEST_ADD`
- `GUEST_REMOVE`

The server validates the transition against current membership state, records the change and its cause in the ledger,
and returns updated party composition. Membership is state/history on the character, never identity. Transfer of player
control remains a separate operation (`transfer_player_control`).

### 12.7 `update_character`

Applies canonical narrative/identity changes to a committed character: name, appearance, personality summary, goals,
backstory summary, and similar non-mechanical fields.

Identity is unaffected — renaming `character:1` changes only its display name, and all ledger events, relationships, and
references continue to resolve. The operation records provenance and requires `expected_revision`. Mechanical state
changes use `apply_runtime_change` or more specific tools; exceptional mutations use `apply_gm_override`.

---

## 13. Rules and Character Runtime

### 13.1 `get_character_sheet`

Returns stable identity, relevant rules features, current resources, equipment, conditions, progression, and carrying
state. Optional detail levels are `SUMMARY`, `PLAY`, and `FULL`.

### 13.2 `resolve_check`

Resolves an ability check, skill check, or saving throw using authoritative current state.

```yaml
operation_id: "..."
actor: character:1
kind: SKILL_CHECK
ability: CHARISMA
skill: DECEPTION
difficulty: 17
context:
  reason: Bluffing the magistrate
```

The server validates modifiers, advantage/disadvantage, effects, and resource use. The AI supplies the fictional intent
and any GM-set difficulty permitted by the rules policy.

### 13.3 `apply_runtime_change`

Applies a named, rules-aware non-encounter operation such as healing, spending a resource, applying/removing a
condition, or adjusting HP when no more specific semantic operation exists.

This is not arbitrary mutation. Each change kind has a schema and validation rules. Exceptional changes require
`apply_gm_override`.

### 13.4 `perform_rest`

Validates rest eligibility and advances atomically until the rest completes or the first interruption requiring input
occurs. A completed rest advances time, processes effects, restores only the resources allowed by the rules, and records
the result.

On interruption, the operation commits elapsed time and automatic effects only up to that instant, creates a
`REST_INTERRUPT` pending transaction, and returns whether the attempted rest qualifies for any recovery. It MUST NOT
grant completion-only recovery or resolve an encounter/player decision implicitly. The transaction records whether and
under what rules the rest may resume.

### 13.5 `award_xp`

Awards XP from an explicit source. Mechanically generated encounter XP SHOULD be awarded automatically by encounter
completion rather than through this tool.

`characters` is OPTIONAL. Omitted, the award follows the campaign's `rules.xp_policy` (`RULES_ENGINE.md` §6) and reaches
the whole active party — that is the correct call for quests, discoveries, roleplay and clever solutions, and it is the
only way to guarantee that party experience does not drift apart because a companion was forgotten in one call. Naming
characters explicitly stays available for awards that genuinely belong to one person.

The response reports the `xp_policy` in force, every `awarded` entry (including companions raised under `LOCKSTEP`),
and — from `rules.companion_level_up` — either `companion_level_ups` (what the engine advanced) or
`companions_awaiting_level_up`. Under the default `PLAYER` policy each waiting entry carries a `proposal`: the hit
points and ability improvement the engine would have chosen, so the GM can offer the player a complete, concrete
level-up to accept or amend rather than an open-ended set of questions. `end_encounter` reports the same three fields.

Discretionary awards require a reason and campaign-policy authorization.

### 13.6 `search_rules`

Ranked free-text search across every installed rules definition and the campaign's custom content. Returns each hit's
`ref`, `kind`, `name`, optional `tag` and a `snippet` of the actual rules text, so a hit can be judged without a second
call. `kind` narrows the search (`RULE`, `SPELL`, `ITEM`, `CREATURE`, `FEAT`, …); omitting the campaign searches the
installed rules only.

The SRD's Rules Glossary is installed as `RULE` content, so conditions, actions, hazards, areas of effect, cover,
resting, movement and carrying capacity are searchable next to the spells and items they interact with. The text is
verbatim SRD wording, not a paraphrase.

The GM SHOULD call this **before answering a rules question or adjudicating an unfamiliar situation**, and cite the
returned `ref`. When nothing matches, the correct answer is that the rule is not in the SRD — not a rule recalled from
memory. It is read-only and allowed in every harness state.

### 13.7 `get_content_definitions`

Returns installed rules-content definitions for a requested kind:

- `ITEM` (including weapons, armor, gear, tools, mounts, vehicles, poisons)
- `SPELL`
- `CREATURE`
- `CONDITION`
- `CLASS_FEATURE`
- other ruleset-defined kinds

Filters include tags, text search, spell level/class, creature CR range, and price range; results use cursor pagination.
Definitions include structured mechanical data (cost, weight, damage, AC, CR, XP value, and so on) plus rules text.
`SUMMARY` detail is presentable on its own: items carry their type and price, spells their level, school, classes and a
one-line summary, and species, classes and skills a one-line `summary` — enough for a GM to list alternatives. This is
how the GM prices purchases, selects appropriate opponents, and answers rules questions without inventing content.

### 13.7 `define_content`

Creates a campaign-scoped custom content definition with a stable `content:` reference and an optional campaign-local
symbolic identifier in the `custom:` namespace. The MVP kind is `ITEM` (name, type, cost, weight, description,
mechanical properties, tags); other kinds MAY follow the same pattern.

The definition is validated against its kind's schema, recorded with provenance and licensing metadata, and immediately
usable by `trade`, inventory, and encounter operations through its `content:` reference. The result returns that
reference. Defining content is subject to campaign GM policy and is not the same as granting it to anyone: putting a
custom item into play still goes through `trade`, `grant_loot`, or `transfer_item`.

---

## 14. Inventory and Economy

### 14.1 `transfer_item`

Moves an item or quantity between characters, containers, locations, or loot sources while enforcing ownership,
quantity, capacity, and visibility.

### 14.2 `equip_item`

Equips or unequips an item and returns all resulting mechanical changes.

### 14.3 `trade`

Performs purchase, sale, or barter atomically. The server determines price from canonical merchant, market, and rules
state unless an authorized negotiated price is supplied with provenance.

The operation commits money and item transfers together. It replaces separate illustrative `purchase_item` and
`sell_item` calls.

### 14.4 `grant_loot`

Materializes and transfers rewards from an authorized encounter, quest, world source, or explicit GM grant. Arbitrary
treasure creation is subject to campaign GM policy and audit requirements.

---

## 15. Encounters

### 15.1 `start_encounter`

Creates an encounter, validates participants and sides, snapshots relevant starting state, rolls initiative where
required, and returns the first actionable turn.

Encounters default to zone-level positioning (near/far, cover, marked features). When the optional `tactical_grid`
capability is enabled, an encounter MAY instead carry a grid model in which each participant has x,y coordinates within
the encounter area — dungeon rooms especially — and movement and range validation use those positions, like figurines on
a tabletop map. The exact grid schema is a deferred decision.

### 15.2 `get_encounter_state`

Returns a bounded authoritative view containing round, turn, participants, sides, visible conditions, positions where a
spatial model is active, available actions or action constraints, and encounter revision.

Secret opponent information is omitted or marked according to visibility.

### 15.3 `perform_encounter_action`

Resolves one semantic encounter action atomically.

Action kinds initially include:

- `ATTACK`
- `CAST`
- `MOVE`
- `DASH`
- `DISENGAGE`
- `DODGE`
- `HELP`
- `HIDE`
- `READY`
- `USE_ITEM`
- `INTERACT`
- `OTHER_RULES_ACTION`

The server returns:

- validated interpretation of the action
- rolls and modifiers
- resource expenditure
- damage, healing, conditions, movement, and other consequences
- defeated/dying state changes
- triggered reactions or pending choices
- next turn or required follow-up decision
- updated encounter revision

If an action requires a player or GM choice before resolution can finish, the server creates a typed pending transaction
rather than guessing.

### 15.4 `resolve_pending_choice`

Completes a server-created pending choice using its transaction reference and one of the legal options returned by the
server.

### 15.5 `end_encounter`

Validates the outcome, commits encounter completion, distributes mechanically defined rewards, updates quests/events as
configured, and returns level-up eligibility and Director-trigger recommendations.

An encounter cannot be ended while mandatory pending reactions or choices remain unresolved.

---

## 16. Progression

### 16.1 `begin_level_up`

Creates a pending level-up transaction based on authoritative XP and rules state.

### 16.2 `get_level_up_choices`

Returns legal remaining choices and a preview of automatic changes.

### 16.3 `update_level_up`

Adds or revises selections without changing the live character.

### 16.4 `validate_level_up`

Returns violations and a complete before/after preview.

### 16.5 `commit_level_up`

Atomically applies a valid level-up and writes a progression ledger event.

Abandoning or expiring a level-up transaction leaves the live character unchanged.

Companion and NPC level-ups use the same transaction. `rules.companion_level_up` decides whether the engine completes
them without player interaction (`ENGINE`) or proposes them for the player to accept or amend (`PLAYER`, the default).
`get_level_up_choices` returns `ability_score_improvement.recommended` at every ASI level, for player characters as well
as companions: the GM SHOULD offer it as a default rather than asking an open question.

A companion materialized from a creature definition has no class. Their first `begin_level_up` therefore opens a
*promotion*: `class_choice` (answered as `choices.class`), `skill_choice` (`choices.skills`), and `origin_choice`
(`choices.species` and `choices.background`, plus `species_skill`, `species_choice`, `origin_feat`,
`background_tool` and `feat_choices` as those ask for them). Class and skills are required to commit; the origin is
optional, but a companion without one is not a character a player could inherit. Recording the class alone is legal
and is how the concrete skill list is obtained. Nothing touches the live character until commit, which applies the
whole promotion atomically. The engine never chooses a class, under either `companion_level_up` policy. The stat
block's actions and senses are kept, but its listed skill and save numbers stop applying: a classed character rolls
from its own abilities and proficiencies.

`get_party` reports `sheet_gaps` on any member who is not yet a full character (`class`, `species`, `background`,
`alignment`, `inventory`), and `materialize_character` takes an `alignment` so an NPC authored as a person carries one
from the moment they exist. `transfer_player_control` accepts any living party member — including one whose sheet is
still a stat block — so the gaps are worth closing before they are needed.

---

## 17. Relationships and Memory

### 17.1 `get_relationship`

Returns compact current relationship state and references to significant shared events. Directional and mutual
dimensions are distinguished where the model requires it.

### 17.2 `update_relationship`

Commits a meaningful relationship development with:

- participants
- changed dimensions or qualitative state
- concise current summary
- cause or supporting event
- provenance

If no relationship exists between the participants, the operation creates it; semantics are upsert.

The tool MUST NOT silently convert a relationship seed into a predetermined outcome.

### 17.3 `record_memory`

Records a significant canonical event or episodic memory.

Required fields include type, participants, campaign time, location when known, concise summary, importance, visibility,
and provenance. Rich details are optional and bounded.

Mechanical operations SHOULD generate their own ledger events automatically. The AI SHOULD NOT duplicate those events
through `record_memory`.

### 17.4 `query_memories`

Retrieves relevant events by structured filters and optional natural-language focus.

```yaml
campaign: campaign:1
participants: [character:1, character:4]
types: [RELATIONSHIP_MILESTONE]
focus: proposal
limit: 10
```

The canonical query baseline uses structured lookup and SQLite full-text search when available. Semantic/vector search
is an optional capability and never the source of truth.

### 17.5 `query_timeline`

Returns ordered events for a bounded time range, entity set, event types, and importance threshold. Ordering is by game
time (fictional chronology) by default; ordering by insertion ID MAY be requested for audit-style views.

---

## 18. Narrative Director

### 18.1 Trigger representation

Operations that reach meaningful boundaries MAY return:

```yaml
director_trigger:
  recommended: true
  reasons:
    - MAJOR_QUEST_COMPLETED
    - SIGNIFICANT_TIME_PASSED
  urgency: NORMAL
```

The trigger is a recommendation to invoke the Director. It does not itself mutate narrative plans.

### 18.2 `get_director_context`

Returns a bounded Director-only view containing relevant arcs, seeds, pacing history, faction agendas, world changes,
unresolved companion intentions, significant relationships, and invalidated plans.

### 18.3 `commit_director_changes`

Validates and atomically commits a set of Director proposals using typed change schemas:

- create/update/supersede story seed
- update story beat
- advance faction plan
- record world event
- schedule pressure
- update pacing intention
- create/supersede companion introduction intention

Director output MUST NOT prescribe player choices, relationship outcomes, or unavoidable scenes.

The operation MAY validly commit no changes and record that a review occurred.

---

## 19. Checkpoints, Death, and Continuation

### 19.1 `create_checkpoint`

Creates a restorable canonical checkpoint when campaign policy and current workflow permit it.

A checkpoint MUST capture **every campaign-owned mutable canonical aggregate**, including characters and their complete
runtime state, inventory and money, party membership and control, relationships, custom content definitions, locations
and connections, quests, factions, story beats and seeds, world and Director state, encounters, pending gameplay
workflows, campaign configuration/version state, the semantic event ledger, and the game clock. The list is illustrative
rather than limiting: a new mutable canonical aggregate is rewindable unless its specification explicitly says
otherwise.

The immutable audit lineage, idempotency records, physical checkpoint metadata, and server/installation configuration
are explicitly outside the rewindable state. Session records remain as audit history but must identify that their former
ending state was superseded. Under the `ENCOUNTER_RETRY` continuation policy, the server MUST create the retry
checkpoint automatically when an encounter starts.

### 19.2 `get_continuation_options`

Returns legal options after player-character death or another terminal event, including the restorable checkpoints
themselves. Options may include checkpoint restoration, encounter retry, transfer to a surviving character, or campaign
completion.

Where campaign policy permits voluntary rewinding, the tool MAY also be called outside terminal events — a player
regretting a disastrous decision in `CHECKPOINT` mode is a supported flow, not an error.

### 19.3 `restore_checkpoint`

Restores the complete canonical campaign state represented by a checkpoint in one transaction and records restoration in
an audit lineage that survives the rollback.

Ordinary campaign history after the checkpoint ceases to be canonical. The audit layer preserves that a restoration
occurred without presenting reverted events as current world truth.

### 19.4 `transfer_player_control`

Transfers control to an eligible existing character without changing that character's identity. The server validates
life state, campaign policy, and eligibility.

### 19.5 `complete_campaign`

Transitions the campaign to `COMPLETED`, `FAILED`, or `ABANDONED` with an explicit reason and final summary. Reopening
behavior is administrative and outside the MVP gameplay protocol.

---

## 20. Explicit GM Overrides

### 20.1 `apply_gm_override`

This is the only general exceptional mutation operation. Two kinds are campaign-level rather than character-level:

- `SET_MAX_HP {amount|set_to}` — the only way to record a maximum granted outside the level-up path (a subclass
  feature, a boon, a permanent injury). Raising the maximum raises current hit points by the same amount.
- `SET_CAMPAIGN_RULE {rule, value}` — retunes one house rule on a committed campaign (`hp_progression`, `xp_policy`,
  `companion_level_up`, `progression`, `gm_override_policy`); takes no target. Everything else in the setup draft is
  fixed at commit.

It requires:

- campaign policy permitting the override
- a typed override kind
- exact target and requested effect
- human-readable reason
- expected revisions
- `operation_id`

The response clearly labels the result as an override. It writes an immutable audit record containing before/after
values, actor/provenance, reason, and time.

The server MUST NOT present an override as an ordinary random or rules-derived result.

An implementation MAY additionally require a startup flag or administrator authorization. That deployment decision does
not change the protocol semantics.

---

## 21. Pending Transactions

Multi-step workflows use explicit pending transactions:

```yaml
pending_transaction:
  ref: transaction:9
  kind: LEVEL_UP
  revision: 3
  status: OPEN
  legal_operations:
    - update_level_up
    - validate_level_up
    - commit_level_up
    - abandon_transaction
```

Initial transaction kinds include:

- `CHARACTER_CREATION`
- `CAMPAIGN_COMMIT`
- `LEVEL_UP`
- `ENCOUNTER_CHOICE`
- `LOCATION_MATERIALIZATION`
- `TRAVEL_INTERRUPT`
- `REST_INTERRUPT`

Only one transaction that exclusively controls the same aggregate may be open at once.

`abandon_transaction` discards draft state and leaves canonical state unchanged unless the transaction explicitly
represents an already-committed interruptible workflow.

---

## 22. Policy and Content Profiles

The effective operating constraint is the intersection of:

```text
server policy
∩ campaign content profile
∩ player constraints
∩ AI provider policy
```

The server stores server, campaign, and player-side configuration. Provider policy remains a client responsibility and
MUST NOT silently rewrite persisted campaign preferences.

`get_server_state`, `get_setup_state`, and relevant validation errors return a compact effective policy summary.

The protocol MUST NOT expose private or unnecessary personal information merely to communicate a policy decision.
Age-derived constraints SHOULD be stored and returned in the least specific form sufficient for enforcement.

---

## 23. Minimal Tool Inventory

The initial coherent protocol surface consists of:

```text
Discovery
  get_server_state

Campaign setup
  create_campaign
  open_campaign
  get_setup_state
  update_campaign_setup
  validate_campaign_setup
  commit_campaign_setup

Character and party design
  create_character_draft
  get_character_choices
  generate_ability_scores
  update_character_draft
  validate_character_draft
  commit_character_draft
  update_party_design
  materialize_character

Session and context
  bootstrap_session
  get_context
  suspend_session

Narrative and world
  upsert_narrative_state
  materialize_location
  advance_time
  get_diegetic_information
  move_party
  update_party_membership
  update_character

Rules and runtime
  get_character_sheet
  resolve_check
  apply_runtime_change
  perform_rest
  award_xp
  get_content_definitions
  define_content

Inventory and economy
  transfer_item
  equip_item
  trade
  grant_loot

Encounter
  start_encounter
  get_encounter_state
  perform_encounter_action
  resolve_pending_choice
  end_encounter

Progression
  begin_level_up
  get_level_up_choices
  update_level_up
  validate_level_up
  commit_level_up

Relationship and memory
  get_relationship
  update_relationship
  record_memory
  query_memories
  query_timeline

Director
  get_director_context
  commit_director_changes

Continuation
  create_checkpoint
  get_continuation_options
  restore_checkpoint
  transfer_player_control
  complete_campaign

Exceptional and transactional
  apply_gm_override
  abandon_transaction
```

This is a semantic inventory, not a requirement that every tool ship in the first implementation milestone. Capability
discovery identifies which optional families are available. A server claiming complete protocol `0.1` gameplay support
MUST implement the versioned machine-readable schemas and the lifecycle exercised by the acceptance mapping below. A
server operating with the `director` capability disabled remains conformant for the other families; acceptance
requirements 25.7 and 25.8 apply only when that capability is enabled.

---

## 24. Execution Example Mapping

The illustrative calls in `EXECUTION_EXAMPLE.md` map to the authoritative protocol as follows:

| Example operation                                   | Protocol operation                                                              |
|-----------------------------------------------------|---------------------------------------------------------------------------------|
| `get_harness_state`                                 | `get_server_state`                                                              |
| `set_player_age`                                    | `update_campaign_setup`                                                         |
| `set_content_profile`, preference and rules setters | `update_campaign_setup`                                                         |
| `generate_starting_wealth`                          | character draft operations (`get_character_choices` + `update_character_draft`) |
| character proposal setters                          | character draft operations                                                      |
| `commit_character`                                  | `commit_character_draft`                                                        |
| companion preference setters                        | `update_party_design`                                                           |
| initial Director calls                              | `get_director_context` + `commit_director_changes`                              |
| `commit_campaign`                                   | `commit_campaign_setup`                                                         |
| `purchase_item`                                     | `trade`                                                                         |
| newspaper/diegetic queries                          | `get_diegetic_information`                                                      |
| `materialize_character`                             | `materialize_character`                                                         |
| `record_event`, `record_significant_memory`         | `record_memory`                                                                 |
| `get_relationship_memories`                         | `query_memories`                                                                |
| `perform_attack`                                    | `perform_encounter_action`                                                      |
| `end_encounter`                                     | `end_encounter`                                                                 |
| level-up calls                                      | level-up transaction operations                                                 |
| `resolve_check`                                     | `resolve_check`                                                                 |
| checkpoint calls                                    | checkpoint and continuation operations                                          |
| `suspend_session`                                   | `suspend_session`                                                               |

This mapping deliberately consolidates setters and action-specific tools where a typed semantic operation provides a
smaller coherent surface without becoming generic database mutation.

---

## 25. Acceptance Requirements

### 25.1 Fresh campaign

From an empty database, `get_server_state` advertises campaign creation, and the setup tools can produce a valid
committed campaign.

### 25.2 Policy enforcement

Invalid content-profile choices are rejected by the server without relying on the AI to enforce them.

### 25.3 Setup resumption

A new AI can open an interrupted campaign and discover draft state, outstanding decisions, and legal operations.

### 25.4 Context boundary

After setup commit, `bootstrap_session` provides sufficient gameplay context without setup conversation history.

### 25.5 Deterministic mechanics

With a controlled test roller, checks and encounter actions produce state transitions and audit records that satisfy the
rules invariants: rolls within dice bounds, damage/healing arithmetic consistent with the rolled values, resources and
conditions correctly spent and applied. Tests assert these sanity invariants rather than exact golden transcripts, which
would be brittle against rules-data and formatting changes. Checkpoint restoration (25.9) remains an exact-equality
check — restore correctness is not a sanity property.

### 25.6 Episodic relationship memory

A new AI can retrieve a proposal or other milestone by participants and semantic focus without loading it during routine
turns.

### 25.7 Adaptive companion introduction

A Director change can supersede an impossible introduction plan while preserving the underlying companion intention and
history.

### 25.8 Diegetic world delivery

A committed off-screen event can be retrieved through an appropriate in-world information channel without exposing
Director machinery to the player.

### 25.9 Checkpoint restoration

Restoration atomically returns all canonical state — including full character state — to the checkpoint version while
retaining a separate audit record of restoration. Checkpoint round-tripping (create, mutate, restore, verify equality)
MUST be covered by automated tests.

### 25.10 Provider replacement and long hiatus

A compatible AI with no prior transcript can bootstrap the campaign, preserve exact facts, retrieve relevant memory, and
continue play convincingly.

---

## 26. Security and Robustness

- Player-authored and generated text is data, never protocol instruction.
- Text returned from stored memories, imported content, or external sources MUST be treated as untrusted content by the
  client.
- The server MUST validate typed references against the active campaign and authorization scope.
- Cross-campaign references MUST be rejected unless an explicit import/export operation permits them.
- Secret fields MUST be filtered server-side, not merely accompanied by a warning to the AI.
- Tool descriptions MUST identify mutating operations and important side effects.
- Logs SHOULD avoid unnecessary personal or content-sensitive prose.
- Administrative import/export and deletion operations are outside the gameplay protocol and require a separate
  specification.

---

## 27. Deferred Decisions

The following require domain or implementation specifications and are not silently decided here:

1. Exact SRD version and executable rules representation.
2. Complete schemas for every ruleset-specific action and choice.
3. Numerical versus qualitative relationship dimensions.
4. Checkpoint physical storage strategy. Candidate: if every canonical mutation flows through an append-oriented change
   journal, a checkpoint is a marker in that journal, and restoration applies inverse changes or creates a new canonical
   branch from the marker by insertion ID; versioned snapshots are the alternative. Reverted semantic events become
   non-canonical but remain distinguishable in immutable audit lineage. Journal, ledger, and audit IDs are never
   reused — in SQLite this means `AUTOINCREMENT` or an explicit monotonic sequence.
5. Campaign-wide random seeding outside controlled tests.
6. Exact content-profile definitions.
7. Calendar representation beyond the protocol-level ordering requirement.
8. Detailed context-ranking and summarization algorithms.
9. Whether full-text memory search ships in the first milestone.
10. Deployment authorization required for GM overrides.
11. Tactical-grid representation for encounter positioning (zones vs. x,y grid, movement costs, ranges).

These decisions may refine payload schemas without weakening the invariants defined here.

---

## 28. Next Specifications

This protocol should drive, in order:

1. `DOMAIN_MODEL.md` — aggregates, entities, state machines, invariants, and transaction boundaries.
2. `DATABASE.md` — SQLite schema, migrations, revisions, ledgers, and checkpoint storage.
3. `RULES_ENGINE.md` — deterministic resolution, ruleset integration, and the SRD 5.2.1 seed-data import (see
   `DESIGN.md` §25.1–25.2 for the source tables).
4. `GAME_HARNESS.md` — campaign creation and runtime state machines.
5. `CONTENT_PROFILES.md` — content/age profile definitions and provider-policy intersection.
6. `ARCHITECTURE.md` — Quarkus components, MCP adapters, services, and Native Image constraints.
7. `MVP.md` — concrete first implementation milestones.
8. Versioned machine-readable tool schemas, published alongside this document, and protocol integration tests derived
   from section 25.

Implementation should begin with a thin vertical slice: discovery, resumable campaign setup, commit, session bootstrap,
one deterministic check, persistence, suspension, and fresh-context resumption.
