# RPG MCP Server — Protocol

**Status:** Normative protocol design
**Purpose:** The contract between an AI Game Master and the RPG MCP server: capabilities, semantic operations, payload
conventions, workflow constraints, canonical state, context retrieval, errors, atomicity, idempotency and audit.

`DESIGN.md`, `EXECUTION_MODEL.md` and `EXECUTION_EXAMPLE.md` define intent and rationale. This document defines how a
compatible AI client interacts with the server.

> **Conversation proposes; protocol operations resolve and commit; narration explains.**

The AI owns interpretation and narration. The server owns workflow legality, canonical facts, deterministic mechanics,
persistence and auditable exceptional mutations.

---

## 1. Normative Language

**MUST/MUST NOT** define compatibility requirements. **SHOULD/SHOULD NOT** define strong defaults that may be departed
from for a documented reason. **MAY** identifies optional behavior.

Examples use YAML-like notation. Tool names, operation meaning, invariants, atomicity and required behavior are
normative; example payloads show intended shapes but are not complete wire schemas. A server MUST NOT claim complete
protocol `0.1` conformance until it implements the versioned machine-readable schemas.

---

## 2. Protocol Design Principles

### 2.1 Semantic operations

Tools express game intent (`perform_action`, `transfer_item`, `advance_time`, `record_memory`), never storage
operations (`update_row`, `set_field`, SQL).

### 2.2 Server-authoritative truth

The server MUST be authoritative for harness and transaction state, entity identity, dice and deterministic resolution,
HP, resources, conditions, inventory, money, progression, encounter state and turn order, committed campaign, world,
quest, faction and relationship state, checkpoints and restoration, visibility and knowledge boundaries, and the event
and audit ledgers. An AI statement does not become canonical because it appeared in narration.

### 2.3 Compact normal context, deep retrieval on demand

Routine operations SHOULD return enough to continue without several lookup calls and MUST NOT return unbounded history.
Episodic detail is retrieved only when relevant.

### 2.4 Discoverability

A newly connected AI MUST be able to determine, with no prior transcript: server and protocol version, capabilities,
harness state, active campaign and pending transaction, legal next operations, and applicable policy constraints.

### 2.5 Invisible machinery

Tool traces, workflow names and policy internals SHOULD stay out of player-facing narration unless the user asks for
diagnostics.

---

## 3. MCP Surface

### 3.1 Tools

Tools perform parameterized queries, deterministic resolution or canonical mutations. They are the primary interface.

### 3.2 Resources

Read-only, cacheable protocol guidance and metadata. Required:

```text
rpg://protocol/guide
rpg://protocol/capabilities
rpg://rulesets
```

`rpg://protocol/guide` is the AI Game Master's instructions (tool-use rules; Harness, GM and Narrative Director roles),
a versioned markdown artifact derived from `EXECUTION_MODEL.md` §24 and embedded in the executable.
The same text is returned as `guide` by `get_instructions`, so clients can start through tools alone.
`rpg://protocol/capabilities` reports protocol version, optional capabilities, limits and supported features.
`rpg://rulesets` lists installed rulesets and versions without their rules data.

Campaign state MUST NOT be exposed as a static resource; tools keep freshness, visibility and authorization explicit.

### 3.3 Prompts

Optional. Clients MUST be able to operate from the guide returned by `get_instructions` and tool schemas alone.
Reading the guide resource is an alternative for clients that expose MCP resources.

---

## 4. Versioning and Capabilities

Semantic versions. A breaking change increments the protocol major version; additive tools, fields, enum values and
capabilities increment the minor version.

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
  ammunition_recovery: true
  checkpoints: true
  director: true
  relationship_memory: true
  want_references: true
optional_capabilities:
  full_text_memory_search: false
  semantic_memory_search: false
  tactical_grid: false
  remote_transport: false
limits:
  maximum_page_size: 100
  maximum_context_budget: 32000
```

Clients MUST ignore unknown response fields and MUST NOT assume they understand unknown enum values.

---

## 5. Common Data Conventions

### 5.1 Typed references

Persisted entities use numeric primary keys internally and typed references `<entity-type>:<positive-integer>` at the
boundary: `campaign:1`, `character:4`, `location:7`, `event:42`, `encounter:3`, `checkpoint:2`, `content:5`, `want:8`.
References are opaque; clients MUST NOT infer ordering, ownership or mutability from the number.

The server assigns each character want a stable `want:N` reference, independent of its note, status or position in a
profile list. References are never reused, including after checkpoint restoration. General and intimate wants keep
separate identities; intimate wants remain subject to the campaign's PEGI_18 policy.

All creatures share one character sequence: `character:1` may be the player avatar, `character:2` an innkeeper,
`character:3` a dragon. Player control, name, role, membership and life state are mutable state, never identity.

Workflow objects are typed too:

```text
draft:3        non-character draft aggregate
seed:1         Director planning entity
transaction:9  pending multi-step transaction
roll:184       auditable random result
```

Seeds and non-character drafts are planning state, never canonical. Characters are the exception: creation immediately
reserves a character row with lifecycle `DRAFT`.

### 5.2 Stable content identifiers

Installed content uses namespaced identifiers: `srd5e:spell/fire-bolt`, `srd5e:item/longsword`,
`srd5e:condition/poisoned`. Display names MUST NOT be used as identifiers.

Custom content receives a campaign-owned `content:N` reference and MAY carry a symbolic identifier in the reserved
`custom:` namespace (`custom:item/bellhaven-broadsheet`), unique only within its campaign. Operations identify custom
content by `content:` reference or by the pair `(campaign_ref, symbolic_id)`.

### 5.3 Time

Real timestamps are RFC 3339 UTC. Campaign time:

```yaml
game_time:
  calendar: campaign:default
  instant: "Year 1, Spring 12, 18:30"
  sequence: 4812
```

`sequence` increases monotonically. Ledger IDs are assigned on append; a retroactive event has a later ID and an earlier
game time. Insertion order governs journal and checkpoint semantics; game time governs fictional chronology.

### 5.4 Pagination

Large reads use opaque cursors (`page: {items: [], next_cursor: opaque-or-null}`). Clients MUST NOT parse cursors.

### 5.5 Visibility

Returned information is labeled where relevant: `PLAYER_KNOWN`, `PARTY_KNOWN`, `CHARACTER_KNOWN`, `FACTION_KNOWN`,
`GM_ONLY`, `DIRECTOR_ONLY`. The AI MUST NOT reveal non-player-known information because it appeared in GM or Director
context.

GM context, bootstrap, location and relationship views, memory/timeline queries, and diegetic information exclude
`DIRECTOR_ONLY` records. Other visibility labels remain attached for the GM to respect when narrating. An explicit
Director context (`get_director_context` or `get_context` with scope `DIRECTOR`) retains Director planning material.
`get_context` with scope `DIRECTOR` and `ref: location:N` also includes that location's Director-only features and
connections. Requesting a Director-only quest or story beat through GM context returns `NOT_FOUND`.

### 5.6 Revisions

Mutable aggregates expose an integer `revision`. A mutation derived from read state MUST include `expected_revision`;
commands against a pending transaction MUST include its revision. A schema MAY omit `expected_revision` only for a blind,
commutative operation or when the target is created in the same call. A stale revision returns `CONFLICT`.

Character revisions include inventory, equipment, conditions and other active effects, including concentration.
Re-read the character sheet after those changes before submitting another revision-dependent update. Idempotent
replays do not advance revisions; checkpoint restoration recovers the historical revision.

---

## 6. Common Response and Error Model

```yaml
result: { ... }
meta:
  campaign: campaign:1
  harness_state: EXPLORATION
  campaign_revision: 83
  warnings: []
  suggested_next_operations: []
```

Failures are structured tool errors:

```yaml
error:
  code: OPERATION_NOT_ALLOWED
  message: "An encounter cannot start while character creation is pending."
  retryable: false
  details:
    harness_state: CHARACTER_REVIEW
    allowed_operations: [validate_character_draft, commit_character_draft, update_character_draft]
```

Required codes: `INVALID_ARGUMENT`, `NOT_FOUND`, `OPERATION_NOT_ALLOWED`, `VALIDATION_FAILED`, `CONFLICT`,
`INSUFFICIENT_RESOURCE`, `VISIBILITY_DENIED`, `POLICY_DENIED`, `CAPABILITY_UNAVAILABLE`, `TRANSACTION_REQUIRED`,
`TRANSACTION_EXPIRED`, `IDEMPOTENCY_CONFLICT`, `INTERNAL_ERROR`.

Validation failures SHOULD report every independently actionable issue at once:

```yaml
error:
  code: VALIDATION_FAILED
  details:
    violations:
      - {path: ability_scores.strength, rule: POINT_BUY_TOTAL, message: "The proposed scores exceed the point budget."}
```

Errors MUST NOT partially mutate state. MVP tools do not permit partial mutation.

---

## 7. Idempotency, Atomicity, and Randomness

### 7.1 Idempotency

Every mutating tool MUST accept a client-generated `operation_id`, unique within the campaign (server-wide before a
campaign exists). Repeating a call with the same `operation_id` and equivalent arguments returns the original result
without re-applying; different arguments return `IDEMPOTENCY_CONFLICT`.

### 7.2 Atomicity

A tool invocation is atomic: mechanical resolution and every consequence it owns commit together (an encounter action
commits roll, resources, damage, conditions, defeat transitions, log entry and next-turn state in one transaction).

### 7.3 Randomness

All authoritative random results are server-generated and returned with a breakdown:

```yaml
roll:
  expression: 1d20+5
  dice: [17]
  modifier: 5
  total: 22
  roll_ref: roll:184
```

The AI MUST NOT supply a desired die result to an ordinary resolution tool. Tests MAY use a seeded or scripted roller.
Production overrides use the audited override protocol only.

---

## 8. Harness Discovery

### 8.1 `get_instructions`

The first call in each new conversation, before starting or continuing a campaign. Read-only, with no
arguments. Returns `guide`, the full Game Master instructions from the embedded `protocol/guide.md`, identical
to the `rpg://protocol/guide` resource. No resource read is required. After loading these instructions into
the current conversation, the AI calls `get_server_state` and follows the guide. It does not need to reload
the guide for later state checks in the same conversation.

### 8.2 `get_server_state`

Returns compact state for discovery and later state checks. A new conversation MUST first load the guide
with `get_instructions` (or the equivalent guide resource).

```yaml
# input
include_campaigns: true
campaign_limit: 20
campaign_cursor: null
# output
protocol_version: 0.1.0
harness_state: CAMPAIGN_SELECTION
active_campaign: null
pending_transaction: null
campaigns:
  - {ref: campaign:1, title: The Ashen Road, status: ACTIVE, player_character: Richard Greystone,
     last_played_at: "2026-08-31T19:14:00Z"}
next_campaign_cursor: opaque-or-null
allowed_operations: [create_campaign, open_campaign]
policy_summary: { ... }
```

A non-null `next_campaign_cursor` is passed back as `campaign_cursor` for the next page. Ordering is stable for the
cursor's lifetime; clients MUST restart pagination after a cursor expires.

The response does not contain the full guide. It includes a short `instruction_hint` reminding a new
conversation to call `get_instructions` if the guide has not yet been loaded.

### 8.3 Legal operations

Workflow-sensitive responses SHOULD include `harness_state` and `allowed_operations` when either may have changed. The
list is advisory; the server MUST still validate every request. Harness states are defined in `EXECUTION_MODEL.md` §25;
internal substates are allowed, but protocol-visible states MUST stay stable within a major version.

---

## 9. Campaign Selection and Setup

### 9.1 `create_campaign`

Creates a resumable setup draft (`operation_id`, optional `title`, `ruleset: srd5e:2024`). Returns the campaign
reference, setup state, outstanding requirements and the `decisions` list (§9.3.1). A campaign committed without a title
gets one generated from its premise.

### 9.2 `open_campaign`

Selects a campaign and reports whether to resume setup, bootstrap gameplay, resolve a checkpoint decision or continue
another pending workflow. Opening MUST NOT silently discard pending transactions.

### 9.3 `get_setup_state`

Returns committed and draft values, outstanding decisions (`outstanding` as prose, `decisions` structured), allowed
operations, constraints and legal choices.

#### 9.3.1 Decisions

Every setting the player must decide is a **decision**: a self-describing question with every legal option and a
one-line description each. Setup responses (`create_campaign`, `get_setup_state`, `update_campaign_setup`) carry the
campaign's decisions in interview order; character responses (`create_character_draft`, `update_character_draft`,
`get_character_choices` with a draft) carry the draft's. The first entry is the one to ask now.

```yaml
decisions:
  - id: content_profile               # stable identifier
    owner: PLAYER                     # PLAYER, or GM for sections the GM authors
    question: "Which content profile should the campaign use?"
    tool: update_campaign_setup       # the tool that records the answer
    path: changes.content_profile     # the argument field, dotted
    choose: {min: 1, max: 1}
    options_are: LEGAL_VALUES         # LEGAL_VALUES (closed set) or SUGGESTIONS (free text equally valid)
    options:
      - {value: PEGI_3,  label: "PEGI 3 — all ages", description: "Cartoon peril only: …"}
      - {value: PEGI_16, label: "PEGI 16", description: "Mature fantasy: …"}
      - {value: PEGI_18, label: "PEGI 18 — adult", description: "Graphic violence, horror, adult sexuality …", recommended: true}
    recommended: PEGI_18
    allow_custom: false               # a free-text answer is acceptable
    allow_surprise_me: false          # SURPRISE_ME is acceptable
    optional: false                   # true when the question may be skipped
    note: "..."                       # advisory guidance for the GM
```

Options MAY carry extra fields (`speed`, `hit_die`, `level`, `school`, `ability`, `contents`, …). A decision without
options (a name, an age) has `allow_custom: true`.

To skip an optional decision recorded by `update_campaign_setup`, send JSON null at its advertised path. For example,
`changes: {experience: {fantasy_style: null}}` accepts the default fantasy style and advances the interview.
Omitted fields remain unanswered. Defaults retain their JSON types: skipped `excluded_themes` becomes an empty list.
A custom tone remains authoritative. Effective experience defaults to `EPIC`, relationship focus `CENTRAL` and no
excluded themes; delegated style or tone also resolves to the epic unless the player supplied their own flavour.
These defaults reach saved campaigns and runtime context, including older saves, without rewriting their history.
Skipping `player_age` stores only the existing/default content cap and never
raises an existing lower cap. Required decisions still need an answer.

- Legal values, labels and descriptions of a closed choice live in exactly one place in the server (an enum or the
  installed content). Tool descriptions MUST NOT enumerate them.
- The list shrinks as answers are recorded and never lists an answered decision. When nothing is outstanding it holds one
  review decision (`character_review`, `campaign_review`) whose options are commit or revise.
- The GM SHOULD ask exactly one decision per turn, the first in the list, presenting every option numbered with its
  description plus a custom answer where `allow_custom` is true, and record the answer before reading the list again.
  Question and option texts are advisory, not required prompts.

### 9.4 `update_campaign_setup`

Updates one or more setup dimensions atomically:

An empty `experience: {}` or `rules: {}` accepts that section's remaining optional defaults while preserving choices
already recorded. Nonempty partial updates accept defaults only for explicit-null optional fields.

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

Rule keys are fixed at commit; `apply_gm_override` kind `SET_CAMPAIGN_RULE` retunes the subset a campaign in play may
change (§20.1). Creative fields MAY hold `SURPRISE_ME`; delegation is not missing input.

`experience.fantasy_style` defaults to `EPIC` (a Baldur's Gate-style fantasy epic, `DESIGN.md` §4.2) when neither
`fantasy_style` nor `tone` was recorded. The decision names that default in its question and as `default`. Legal style
names are in `constraints.fantasy_style`; free text is equally valid and never replaced by the default.

### 9.5 `validate_campaign_setup`

Validates the whole setup graph without committing: violations, warnings, unresolved delegated choices, compact review.

### 9.6 `commit_campaign_setup`

Requires `expected_revision` and `operation_id`. In one transaction it freezes the setup as canonical configuration,
promotes finalized characters from `DRAFT` to `ACTIVE` (activating staged rules state, inventory and money), commits
initial party, adventure and Director state, writes a setup ledger event, advances to `SESSION_BOOTSTRAP` and returns a
compact summary. It MUST NOT return the wizard conversation.

Failure leaves the draft unchanged. Abandoning an uncommitted campaign marks its character rows `ARCHIVED`; character
IDs are never deleted or reused.

---

## 10. Character and Party Design

### 10.1 `create_character_draft`

Creates a character row with a stable `character:` reference and lifecycle `DRAFT`. The response carries the sheet and
the draft's outstanding `decisions` (§9.3.1), as does every `update_character_draft`. Draft state is persisted and
resumable but excluded from gameplay context, party eligibility, encounters and world queries. During setup the row is
owned by the campaign draft until `commit_campaign_setup` succeeds.

### 10.2 `get_character_choices`

Legal choices for a scope: `ALL`, `ABILITY_GENERATION`, `SPECIES`, `CLASS`, `BACKGROUND`, `FEAT`, `SKILLS`, `ALIGNMENT`,
`SPELLS`, `EQUIPMENT`, each option with a one-line description. Class-dependent scopes take a `character`; with a draft
the response also carries its `decisions`.

### 10.3 `generate_ability_scores`

The server performs `STANDARD_ARRAY`, `POINT_BUY` or `ROLL_4D6_DROP_LOWEST` where the ruleset supports it. Rolled
results include every die and dropped value and cannot be silently rerolled unless campaign rules permit it.

### 10.4 `update_character_draft`

Applies concept, identity, mechanical choices, appearance, personality, backstory and equipment, including the SRD
background (ability increase, Origin feat, skills, tool, equipment), species trait choices (bonus skill,
lineage/ancestry, origin feat) and feat choices. Locally checkable constraints are validated immediately;
whole-character constraints at validation.

### 10.5 `validate_character_draft`

Complete validation result plus a player-facing review. Does not finalize.

### 10.6 `commit_character_draft`

Validates and moves the row from `DRAFT` to `FINALIZED_DRAFT`; it can no longer be edited without reopening. Staged
rules state, inventory and money stay unavailable to gameplay until `commit_campaign_setup` (or a later enclosing
creation workflow) promotes the row to `ACTIVE`. The reference never changes. Starting wealth MUST come from explicit
rules and configured modifiers, never from backstory text.

### 10.7 `update_party_design`

Stores desired composition, companion preferences, authored companion drafts, relationship seeds and introduction
intentions. An intention is Director planning state, not a guarantee.

### 10.8 `materialize_character`

Converts a Director companion seed (`seed:<n>`) or an installed definition (`srd5e:creature/bandit`) into a canonical
character: validates rules state, creates a row with a new `character:` reference, records provenance, MAY create
initial relationships, MUST NOT predetermine relationship outcomes. Authored characters are activated through their
creation workflow, not rematerialized.

---

## 11. Session and Context

### 11.1 `bootstrap_session`

Creates or resumes a session (`operation_id`, `campaign`, `context_budget`) and returns a bounded package. It is
legal in every play state: a conversation that ended mid-encounter, mid-level-up or at a continuation decision
resumes with `bootstrap_session`, which keeps that state rather than returning the campaign to exploration.
The package holds:

- campaign and session references, harness state, current time and location
- the player character's PLAY sheet, inventory reduced to one name per item
- the party: every member's SUMMARY sheet with `membership`, `location`, `with_party`, age, presentation, a brief
  appearance, running biography `state` entries and open `wants` (§12.7)
- `former_members`: one entry per character whose latest membership ended with no open one (state, when, why, where)
- compact relationships: summary and dimensions; `profile_available` names profile keys and sizes (fetch the profile
  with the RELATIONSHIP or INTIMACY scope)
- active quests and immediate objectives
- `chronicle`: `synopsis`, `chapters_since_synopsis`, `since_last_chapter`, `due` (§11.4)
- recent significant events
- `campaign.house_rules` (§11.5)
- `story_beats`: open GM-visible beats with stable references, titles, states, visibility labels and compact `gm_brief`
  guidance; private Director planning is excluded
- pending encounter, transaction or continuation decision; legal next operations
- `budget`: tokens asked for, the character target (three characters a token) and characters used

Default budget 16,000 tokens, at most `maximum_context_budget`. The story section gets a third; relationship profiles and
full inventories stay out; the digest drops its least important lines first. The server MUST prioritize current
canonical state over historical color.

Each story-beat brief contains at most 600 characters. Together the brief texts share at most the smaller of 4,000
characters and one eighth of `budget.chars_target`. A clipped brief, or one omitted when that allowance is exhausted,
sets `gm_brief_truncated: true`; references and the other compact beat fields are retained. The brief uses the optional
nonblank string `gm_brief` payload field, falling back to a legacy string `summary` or `description`. No migration is
required. These limits apply to brief text, not to the entire response or beat metadata. When beats are present,
`story_beats_hint` explains scoped retrieval and the truncation flag. The full beat remains available through
`get_context` scope `STORY_BEAT`, including the original unshortened text.

### 11.2 `get_context`

Purpose-built context for one scope, with references, optional natural-language focus and a budget. Visibility and
scope rules always apply.

- `SCENE`: the same bounded current-play package as bootstrap, including compact story-beat guidance, read-only
- `STORY_BEAT`: requires `ref: story_beat:N`; returns `{story_beat: {...full beat fields}}`, including visibility,
  lifecycle, revision and all payload details. The brief is not shortened in this scoped response. A missing or
  `DIRECTOR_ONLY` beat returns `NOT_FOUND`; a reference belonging to another campaign returns `INVALID_ARGUMENT`.
- `CHARACTER`: FULL sheet with biography and (PEGI_18) intimate profile, relationships with profiles, membership
  history, recent events, location
- `WANT`: `ref: want:N` returns `{want: {...want fields, character: character:N, kind: general|intimate}}`, including
  the note, status, dates, `with` and optional `resolved_by`. Campaign isolation and PEGI_18 gating apply; internal
  closure-order bookkeeping is omitted.
- `RELATIONSHIP`
- `INTIMACY`: everything needed to play one person in an intimate scene: the focal character's identity, biography
  (voice, marks, running state, open wants, last dozen timeline entries), hit points and conditions, whereabouts, own
  intimate profile and open `intimate_wants`; every partner (`second_ref`, or everyone with mapped preferences,
  attraction of 3 or more, an open want naming them, or a household term naming them) with the same view and both
  directions of the pairwise profile; the `household_terms` of anyone in the bed; intimate ledger events newest first
  with episodic detail; content-profile guidance. Below PEGI_18 the scope returns `layer: ROMANCE_ONLY`: milestones,
  terms, wants and hard lines only.
- `LOCATION`, `QUEST`, `ENCOUNTER`, `DIRECTOR`

### 11.3 `suspend_session`

Closes the session atomically after checking no prohibited transaction is open; records location, time and events
written. A `summary`, when given, is written as a chronicle CHAPTER exactly as `write_chronicle` would. Mid-encounter
suspension MAY be a capability; otherwise `OPERATION_NOT_ALLOWED` with alternatives.

Suspending is optional. `bootstrap_session` resumes an open session when the campaign was touched within the last three
hours and otherwise closes it and opens a new one. The recap never depends on sessions.

### 11.4 The chronicle: chapters, the synopsis and the digest

Two bounded artifacts, written by a summarizer the client delegates to (never the server), plus a derived digest:

- **CHAPTER**: an immutable prose summary (about 300 tokens, at most 3,000 characters) of a ledger span, written from
  the raw events. Records the game time covered (`from_time` to `to_time`), the journal span, and when it was written
  (game and wall time).
- **SYNOPSIS**: the rolling story so far (about 1,500 tokens, at most 12,000 characters), rewritten from the previous
  synopsis and the chapters since it: closed arcs compress, the current arc stays detailed, settled facts carry forward
  verbatim unless a chapter contradicts one. A new synopsis supersedes the old and records the chapter ids it used.
- **`since_last_chapter`**: built at bootstrap from the ledger without a model: every event since the last chapter,
  grouped under a share of the budget as `critical` (with episodic detail), `major`, `notable` (one line each, dropped
  oldest-first), `minor_by_type` (counts) and `omitted_for_budget`.

Bootstrap shows the synopsis, the chapters since it (newest whole, older ones shortened to a head when the budget runs
out) and the digest, so the story section has the same size for any campaign length.

**Triggers are sizes, not counts.** `chronicle.due` reports `chapter: true` when more than 20,000 characters of
non-minor summary and detail are uncovered, and `synopsis: true` when more than four chapters were written since the
synopsis or a quest completed or failed since it. The notice also appears in the `consequences` of `advance_time`,
`move_party` and `perform_rest` and in `meta.warnings` of `record_memory` and `end_encounter`. When something is due
the GM SHOULD delegate: a fresh summarizing agent calls `get_chronicle_material`, writes in the campaign's voice at the
target length, and calls `write_chronicle` with the material's `through` marker while play continues. The GM SHOULD open
a resumed campaign by retelling the synopsis and the chapters since it before asking what the player does.

### 11.5 `update_house_rules`

Table rulings every client must see at bootstrap, stored as a list of short strings and returned in
`campaign.house_rules` ("no firearms in this world"). `mode` is ADD (default), REMOVE or REPLACE. Each change is a
`HOUSE_RULE` ledger event.

### 11.6 `get_chronicle_material` and `write_chronicle`

`get_chronicle_material {campaign, kind, cursor}` (read-only) returns:

- `CHAPTER`: `previous_chapter` in full, `earlier_chapters` (two, shortened), `events` (every non-minor, non-director
  event since the last chapter, with detail, paged toward 40,000 characters by `next_cursor`), `events_uncovered`, `voice`
  (tone and style guidance), `target` (characters, ceiling, what a chapter contains), `through.journal_id`
- `SYNOPSIS`: `current_synopsis`, `chapters_since`, `voice`, `target`, `through.chapter_id`

Chapter pages keep each journal entry's eligible events together. The size target is soft: a page may exceed it
to finish its final journal entry, so `through.journal_id` never covers an omitted sibling event. `next_cursor`
continues after that complete entry. `events_uncovered` counts all currently uncovered eligible events, independently
of the page or cursor.

`write_chronicle {operation_id, campaign, kind, title, summary, through}` stores the entry. A chapter closes at
`through` (now when omitted); later events stay uncovered for the next chapter. A stale or future marker is `CONFLICT`,
an entry over the ceiling `INVALID_ARGUMENT`, a chapter with nothing to cover `OPERATION_NOT_ALLOWED`. A synopsis closes
at a chapter id and supersedes the previous one. Each write is a `CHRONICLE_WRITTEN` ledger event (MINOR, GM_ONLY) and
returns the new `due` state.

Chronicle prose may cite a stable `want:N` inline when it helps connect a drive to its later outcome. Resolve the
reference with `get_context` scope `WANT`; `write_chronicle` still accepts the prose `summary`, without a separate
structured references field.

### 11.7 `find`

`find {campaign, kind, query, limit}` (read-only) looks up characters (`CHARACTER`, default) or places (`LOCATION`) by
name: case-insensitive, exact name first, then prefix, then substring, then a mention in the description. Each hit is
one line: ref, name, and for people membership state, life state and whereabouts; for places kind, materialization,
parent and head count. Use it whenever a name is known and the ref is not; never guess a ref.

Kind `WANT` searches character wants by note. Each hit identifies its stable `ref`, note as `name`, owning
`character`, and `kind: general|intimate`. It includes general wants and, under PEGI_18 only, intimate wants.
Use `get_context` scope `WANT` to retrieve the full entry.

---

## 12. Canonical Narrative State

### 12.1 `upsert_narrative_state`

Creates or updates one narrative aggregate by discriminated type: `QUEST`, `STORY_BEAT`, `STORY_SEED`,
`FACTION_STATE`, `WORLD_EVENT`, `LOCATION_DETAIL`, `NPC_AGENDA`. Each kind has its own schema, validation, visibility,
lifecycle and transitions. Provenance MUST be recorded: `GM`, `DIRECTOR`, `MECHANICAL_CONSEQUENCE` or
`ADMINISTRATIVE_OVERRIDE`.

A `WORLD_EVENT` stores `locations` and other `affected` references together and returns the combined list as
`affected`. Updates preserve each group when its input is omitted or null; an explicit empty list clears that group.
Use `locations` to replace or clear the location scope. Location references supplied inside `affected` remain
supported and add to the retained or supplied location scope. Duplicate references are stored once.

A `STORY_BEAT` MAY carry an optional string `gm_brief`: a concise, actionable hook and plausible approaches for the
GM. A supplied non-null value MUST be a string; null clears it and a blank value allows the legacy fallback in compact
context. Further payload fields MAY hold location, actors and their intentions, availability conditions, discoverable
clues, approaches, consequences and `if_ignored` developments. These are guidance for the GM to interpret, not
automatically evaluated conditions or prescribed player choices. Existing beats without these fields remain valid.

A `QUEST` whose status becomes `COMPLETED` returns `treasure`: the party's level band, the rarities it draws from, how
often a magic item should turn up, and up to three candidates drawn through the roller (§14.4). It is a suggestion; the
GM grants with `grant_loot` or ignores it.

### 12.2 `materialize_location`

Atomically converts a location seed or semantic node into canonical detail: location, connections, known features,
secrets with visibility labels, revision. MUST reject contradictions with committed geography unless performed through
an explicit override.

Nested `children` and object-valued connection destinations reuse a location with the same name within the same
campaign and parent. Names match without case or surrounding whitespace; omitted kinds preserve the existing kind.
Repeated declarations, including duplicates in one request, retain the existing location reference and detail;
`new_children` lists only newly inserted children. Supplied kind, description or tags must agree with existing detail.
To add detail to a child, materialize its location reference directly. Top-level calls without a location reference
still explicitly create a new place.

If older data contains multiple siblings with the same name, name lookup fails with candidate references instead of
choosing or merging them. Use `children: [{ref: "location:N"}]` to select an existing direct child; connection destinations
also accept a location reference as a string or `{ref: "location:N"}`. References must belong to the campaign, and an
explicit parent must agree with the referenced location. Existing campaigns need no data rewrite.

Children receive a parent connection unless `connection: false`. A new child connection defaults to `PASSAGE`, `OPEN`,
`PARTY_KNOWN` visibility and 5 travel minutes. Re-declaring a child preserves its known connection details; an explicit
`connection_kind` changes only the kind. `connection: false` never removes an existing connection.

Connections between the same undirected pair of locations update only supplied non-null `kind`, `state`, `visibility`,
`travel_minutes` and `distance_miles` fields; omitted or null fields retain their committed values.
New connections default to `PATH`, `OPEN`, `PARTY_KNOWN` visibility and 60 travel minutes.

### 12.3 `advance_time`

Advances the clock and commits rules-governed consequences (resource durations, scheduled events, rest effects, trigger
recommendations). The result separates automatic canonical consequences from Director recommendations.

### 12.4 `get_diegetic_information`

What is currently available through a fictional channel (newspaper, rumor, letter, crier, witness, market price,
environmental evidence), filtered by location, time, visibility and committed world events. It never creates a fact.

### 12.5 `move_party`

Moves the party, or named `characters`, to a world location, settlement node or dungeon area.

**Who travels.** Without `characters`, active members and guests whose location is the party's (or who have none yet).
A member or guest standing elsewhere does not teleport; the result lists them in `left_behind` with where they are.
Separated members do not join a default departure. Explicit travellers must have an open party
membership (ACTIVE, GUEST or SEPARATED), or active player control; unrelated NPCs cannot start a party
journey. With `characters`, a new trip uses exactly that nonempty selection, starting at their shared
actual room/contact or canonical location. Unknown or
mixed origins refuse before movement or time. A companion elsewhere may travel to the player;
already-at-destination is checked against the selected travellers. Campaign focus follows the player
character. Every party view reports each member's `location` and `with_party`.

The destination must be reachable via known connections or an authorized route. Travel advances atomically until
arrival or the first consequence needing input. On arrival it commits elapsed time, locations, automatic
consequences and the movement event. On interruption it saves the journey's route, elapsed progress,
roster, risk checks and actual route anchor. It MUST NOT assume the rest of the journey. Inspect
`get_travel_state`, resolve the opportunity and use `continue_journey`, or `move_party` to the saved
destination. An explicit saved-trip selection may only narrow existing movers; omissions are left at
the actual contact/anchor. Dead/departed movers reconcile with named reasons. Living incapacitated
movers require recovery or explicit omission. A different destination refuses with the saved
destination and continuation/narrowing instructions; it cannot silently turn the trip around.

A later trip from a saved roadside contact reuses the appropriate portion of its existing connection,
with actual remaining travel time and the same connection risk scope. It creates no synthetic edge.
Room targets must have a legal entry or an open authored path from it; reaching the enclosing place
does not bypass a locked interior door. Overland/site arrival includes the traversed authored interior
legs and their time. A blocked route identifies the passage key, state and lock status, with the next
operation; an unlocked CLOSED door must be opened explicitly with `update_map_object`.
Accepted unrealized dungeon commitments require coherent
playable geometry before entry; the refusal supplies the preparation and next authoring step.

**Travel encounters.** `define_travel_risk` supplies context-sensitive frequency, opposition, severity
and authored/random placement for a connection, location or default scope. `consequences` and the saved
`opportunity` identify an actual route contact and its story context. The DM chooses discovery,
avoidance, negotiation or combat, using `resolve_travel_opportunity` when resolved. Combat uses the
contact's local grid. Short continuations/reloads preserve exposure and resolved checks rather than
rerolling them. See [story-aware travel encounters](MAP_FORMAT.md#story-aware-travel-risk-and-hostile-encounters).

For a connection scope, `authored_at_fraction` denotes a fixed point along that connection, measured
from its regional feature's `from_location_ref`, falling back to the canonical first endpoint. Partial,
reverse and multi-leg routes convert that point into their own elapsed time; trips starting at or past
the point do not meet it again. Location/default scopes retain whole-journey fraction semantics.
Companion-only journeys leave the controlled player's place, board and regional marker unchanged.
Encounter context uses the selected party participants; mixing home participants and saved journey
movers refuses before starting combat. Omitted `materialize_character.location` follows the controlled
player; give an explicit location for an off-screen encounter. New interrupted encounter opportunities
save `contact_location` on the opportunity and travel-state view for this purpose. Reads return that
saved reference without creating a location or migrating older journeys.

Arrival into a published room without figurines supplies a read-only `placement_hint` in the move reply
and bootstrap's spatial context. `status:UNPLACED`, `placement_required:true`, `coverage_status` and
`coverage:[{map_ref,kind,level}]` identify available coverage without revealing other rooms. A unique
match also supplies top-level `map_ref`/`level`; multiple matches are `AMBIGUOUS`. No placement or
board selection happens automatically. `current_board` continues to describe the actual controlled
figurine; bootstrap reports `NO_CURRENT_BOARD` if it has none.

### 12.6 `update_party_membership`

One typed change: `JOIN`, `LEAVE`, `DISMISS`, `SEPARATE`, `REJOIN`, `GUEST_ADD`, `GUEST_REMOVE`. Validated against
current membership state, recorded with its cause in the ledger, returns the updated party. Player control is separate
(`transfer_player_control`).

#### 12.6.1 `set_tactical_control`

Sets the saved decision controller for current companions' movement, actions and reactions. The DM
MUST play companions by default. A broad order or a one-off instruction MUST NOT silently opt the
player into detailed control. The primary player character remains player-controlled; this tool
does not alter the primary `PlayerControlAssignment` or companions' narrative agency.

```yaml
operation_id: control-mira-this-fight
campaign: campaign:1
controller: PLAYER              # DM | PLAYER
characters: [character:3]        # omitted: all current and later companions in this scope
scope: ENCOUNTER                # ENCOUNTER | UNTIL_RELEASED
encounter: encounter:2          # omitted: the running encounter
reason: "The player requested detailed control of Mira for this fight."
```

`scope` defaults to `ENCOUNTER` when an encounter is running, otherwise `UNTIL_RELEASED`.
Encounter scope requires a running encounter and expires on its completion. Persistent scope
remains until changed; `controller: DM` returns the selected characters to DM decisions for the
chosen scope. An encounter-only change restores any earlier persistent preference when it expires.
An explicit `UNTIL_RELEASED` change takes effect immediately, clearing conflicting selected-actor
encounter overrides so the persistent choice also governs the running fight.
Saved preferences MUST survive reload and checkpoint restoration. Party, bootstrap
and encounter context expose effective controllers so clients can recover the arrangement without
the conversation transcript. Changes are atomic, idempotent and journaled with the other campaign
state; they do not spend fictional time, actions or movement.

`get_party` and bootstrap expose a `tactical_control` summary with the default, current policies and
effective member assignments. Each party member's `tactical_control` assignment includes its
`controller`, `scope` and source; the existing `player_controlled` field still identifies only the
primary player character. Encounter participant context exposes the corresponding effective
assignment for turn and reaction routing.

"Mira, cover the stairs" is an ordinary DM-interpreted order. "I control Mira this fight" selects
`PLAYER`/`ENCOUNTER`; "DM, take over" selects `DM`. Detailed control includes reaction choices under
normal `npc_reactions: AUTO`. The explicit `ASK` policy can still request all NPC reactions. Each
actor retains their own initiative, movement/resources, legal actions and perception regardless of
controller. The client SHOULD resolve routine DM-controlled companion turns without asking the
player for every tile, pausing for player-controlled choices or meaningful shared decisions.
This is a control contract for the AI DM, not an engine-side companion AI or group movement tool.

### 12.7 `update_character`

Canonical narrative and identity changes to a committed character: name, appearance, personality, goals, backstory,
age, presentation, alignment, and two merged aggregates:

- `biography`: `timeline` [{game_time, note}] (clock stamped when omitted), `voice` [verbatim lines], `state`
  [{note, since, until}] (long-running bodily state; no `until` means current, shown in every party list), `marks`,
  and `wants` [{ref, note, with: [character refs], since, status OPEN|DONE|ABANDONED, done_at, resolved_by}] (the
  character's drives). The server assigns `ref: want:N`; party summaries show open wants as `{ref, note}` at every
  detail level. A want closed without a `done_at` is stamped with the clock, so fulfilled wants keep their history.
- `intimacy`: PEGI_18 only (`POLICY_DENIED` otherwise, never returned below PEGI_18): `body`, `likes`, `dislikes`,
  `limits`, `hard_lines`, `wants` (the same entry shape as biography wants, with separate identities),
  `household_terms` [{note, with}] (held by the person who set them), `voice_in_bed`. This is the character's own
  truth; what each partner has learned stays on the pairwise relationship profile.

Patch an existing want by `ref`, supplying only the fields that change; its `note` need not be repeated. For example,
`changes: {biography: {wants: [{ref: "want:8", status: "DONE", resolved_by: "event:42"}]}}` closes that want and
preserves its other fields. `resolved_by` is optional and MUST reference an existing event in the same campaign.
`with` continues to name the characters involved, not the want's identity. The same patch form applies to intimate
wants under `intimacy`. A reference must belong to the supplied character and the matching general or intimate list.

The existing note-based input remains supported: a new note creates a want and a matching note updates its existing
entry. If several wants share the same wording, a note-only update is ambiguous and must use a reference instead.
Reopening a want clears its previous closure date and, unless explicitly replaced, its resolution event and explanation.
Other biography/intimacy fields merge like relationship profiles (§17.2): lists append without duplicates,
an entry with the same `note` replaces the older one (for example, give a state its `until`), a null removes the key it
sits under, and `{replace: true}` starts over. Internal closure-order markers are server-owned and never appear in
public want views. The GM SHOULD record facts as they are established in play. The older `goals` list remains for
compatibility; `wants` is the dated, closable form the views show.

Null list entries, including in nested lists, return `INVALID_ARGUMENT` with `details.path` identifying the entry
(for example, `biography.timeline[1]` or `intimacy.wants[0].with[1]`). Null map values still remove fields; the whole
update is atomic when validation fails.

On upgrade, references are overlaid on legacy profile JSON using the identity registry, including notes preserved in
historical checkpoint before-images. Existing JSON is not rewritten; an entry gains its stored ref when it is next
updated or closed. Old campaigns and checkpoints remain usable.

Identity is unaffected: renaming changes only the display name. The operation records provenance and requires
`expected_revision`. Mechanical state uses `apply_runtime_change` or a more specific tool; exceptions use
`apply_gm_override`.

---

### 12.8 Persistent map authoring and views

The map workflow uses canonical campaign locations, independent connections, inventory and characters.
The [DM map workflow](MAP_USAGE.md) contains complete preparation and play examples. Authoring context
is GM-only; player projections remove undiscovered geometry, private feature metadata, hidden actors
and off-screen changes before delivery.

| Operation | Main arguments and result |
| --- | --- |
| `begin_map_preparation` | Mutating: `operation_id`, `campaign`, `kind: DUNGEON|SCENE|REGION`, structured `proposal`. Returns preparation reference/revision, readiness and repair targets. |
| `revise_map_preparation` | Mutating: preparation ref, optional expected revision, `changes`. Rooms/connections/rewards/actors/objects/features patch by local `key`. `generate_layout:true` requests a basic helper; accepted facts remain binding. |
| `validate_map_preparation` | Read-only: campaign/preparation. Returns all required gaps without creating live state. |
| `publish_map_preparation` | Mutating: preparation/expected revision. Accepts BLUEPRINT commitments or publishes validated SCENE/REGION content atomically; returns canonical reference mapping. |
| `get_map_context` | Read-only: `reference`, `audience: PLAYER|GM`, optional level and `detail: SUMMARY|FULL`. Defaults to bounded summary, including up to 100 GM room keys/names/levels/location refs/effective illumination. FULL adds filtered geometry. Preparation/atlas/address authoring context requires GM. |
| `find_maps` | Read-only GM index by query: text, location ref, atlas ref, address or atlas position. Missing sheets remain ungenerated. |
| `render_map` | Read-only: map, audience, level, options and `include_image`. Returns PNG content when requested plus text, physical transform, dimensions and the same filtered `markers` manifest. PLAYER text uses names/directions; GM text includes technical coordinates/references. `options.technical_text` overrides text presentation. |
| `import_map_asset` | Mutating: server-readable PNG `source_path`, physical `repeat_ft`. Copies/normalizes immutable campaign artwork; returns `asset:N`, content hash and dimensions. The asset reference pins physical repeat scale. |
| `export_map_bundle`, `import_map_bundle` | Export a private GM snapshot/artwork to `.rpgmap.zip`; import a bounded archive into a reviewed draft with explicit source-reference mappings. Import does not publish actors or loot. |
| `update_map_annotations` | Mutating: map/expected revision and annotation. WORLD_POI adds exact atlas geometry; NOTE/REPORT preserves scoped knowledge, sources and uncertainty. |

A dungeon blueprint may fix a room count, levels, selected roles, secret passages, rewards and patrol
plans without fixing every tile. Integer `levels` declares floor IDs from zero; an explicit list can
declare negative levels. Playable publication requires the geometry/content demanded by readiness.
Accepted-but-unrealized site entry returns the preparation reference before travel time is spent.

Positions and dimensions use feet; actor coordinates are footprint southwest origins on a 5 ft lattice.
The 5 ft/10 ft display overlay and PNG dimensions do not redefine those positions. Region geometry is
stored in atlas feet independently of styles and derived images. Sheet addresses `atlas:N[x,y]` use
east-positive X, north-positive Y and a fixed origin; queries do not expand the world. Existing-campaign
bootstrap stages missing regional preparation with sourced graph constraints, without fictional time.

### 12.9 Tactical actions and living schedules

| Operation | Contract |
| --- | --- |
| `place_map_actors` | Mutating exploration deployment using existing character refs, footprint origin, level and size. Synchronizes only explicitly placed actors; unrelated figurines/party focus remain unchanged. Actual encounter deployment synchronizes its selected participants to their physical rooms. It does not bypass combat turns. |
| `preview_spatial_action` | Read-only path inspection using permitted knowledge, with no movement/resource/time costs. |
| `move_map_actor` | Mutating exploration movement; combat movement uses `perform_encounter_action`. |
| `use_map_transition` | Mutating exploration stairs/ladders/portals from within reach. Combat uses MOVE with `{portal:key}`. |
| `observe_map` | Mutating observation; saves shared party sight and last-observed explored memory. |
| `get_actor_perception` | Read-only GM decision context from this actor's perception and retained evidence. |
| `emit_map_sound` | Mutating authored cue with range/level; approximate hearing evidence does not grant exact unseen positions. |
| `update_map_object` | Mutating resolved consequence or audited correction with a reason. Trap knowledge supports boolean `concealed` and `known_hazard`; checks alone never reveal/disarm a trap. Inventory and action rules retain authority. |
| `correct_map_geometry` | Mutating correction: `map`, required `expected_revision`, `patch` and `reason`. Structural changes require exploration; map/room illumination-only adjudication also works in combat. Uses `get_map_context.map_revision`; validates current figurines and placed objects and preserves stable identities/live state. Actual lighting changes append a GM_ONLY `MAP_ILLUMINATION_CHANGED` event with reason and before/after state. |
| `convert_encounter_to_grid` | Mutating explicit legacy conversion preserving initiative, resources and pending choices. New combat already requires grids. |
| `define_patrol`, `update_patrol`, `get_patrol_state` | Finite actor rosters, timed waypoints, persisted phase and explained changes. Regional timing pauses when the last party member leaves. |
| `schedule_world_event`, `perform_timed_action` | Saved scheduled events and partial SEARCH/WAIT/WORK actions, with interruption before completion benefits. |
| `define_travel_risk` | Mutating story-aware frequency, opposition eligibility, severity and authored/random placement policy. |
| `get_travel_state`, `continue_journey`, `resolve_travel_opportunity` | Inspect/resume retained travel and resolve contacts at their actual position/time; preserve risk exposure and resolved outcomes. |

All new canonical records participate in journaled mutation, idempotency and checkpoint restoration.
Declared TRAP objects are concealed in PLAYER current/remembered views and renders until explicitly
revealed (`concealed:false`) or authored as an apparent hazard (`known_hazard:true`). Explicit secret,
concealed and GM-only visibility still take precedence; clear those applicable restrictions when
adjudicating discovery. Old automatically leaked trap observations are filtered rather than treated
as valid discovery. GM context retains the authored challenges. An authored secret entrance owns
its discovery check, without imposing a second automatic room DC; independently authored room checks
and ambiguous unmarked legacy guidance remain authoritative.

Interior departure appends the actual room-to-entry path before overland travel, symmetric with
arrival. A saved contact remains a stable place through tactical movement and opportunity resolution;
actual graph advancement clears that retained pointer before creating a later contact. A lone dead
traveller without a figure is left at the contact on cancellation, with `left_at` and no invented
map/position. Exact departure-place/anchor encounter hints retain their legacy route-context meaning;
unrelated explicit places cannot relocate saved travellers. Tactical movement can legitimately adjust
projected route progress, including by one second; no-motion reads/combat do not cause that change.

Rendering/querying does not advance timers, reveal secrets or reroll. Regional timers use active elapsed
time while any party member remains in that region; return resumes the saved remaining duration with
no off-region catch-up. The global calendar continues normally.

## 13. Rules and Character Runtime

### 13.1 `get_character_sheet`

Identity, rules features, resources, equipment, conditions, progression and carrying state at detail `SUMMARY`, `PLAY`
or `FULL`.

A character with class levels carries `armor_training` (`LIGHT`, `MEDIUM`, `HEAVY`, `SHIELDS`) and
`weapon_proficiencies` (`SIMPLE`, `MARTIAL`, `MARTIAL_LIGHT`, `MARTIAL_FINESSE`) from its classes (SRD 5.2.1 "Armor
Training"; `RULES_ENGINE.md` §3, §7), and `untrained_armor {item, effect}` while wearing body armor it lacks training
with: Disadvantage on every D20 Test involving Strength or Dexterity and no spellcasting. `armor_class.shield` notes
"no AC benefit" for a shield worn without Shields training; `speed` shows the 10-foot reduction of heavy armor whose
Strength requirement the wearer does not meet. Stat-block creatures carry none of this and are unrestricted. The
hit-dice pool is `recharge: LONG_REST`; `spellcasting.ritual_casting` is an explanatory string (any prepared
Ritual-tagged spell may be cast as a ritual), and a sorcerer's `spellcasting.metamagic` reports `options_known_limit`.

### 13.2 `resolve_check`

An ability check, skill check or saving throw from authoritative state:

```yaml
operation_id: "..."
actor: character:1
kind: SKILL_CHECK
ability: CHARISMA
skill: DECEPTION
difficulty: 17
context: {reason: Bluffing the magistrate}
```

The server validates modifiers, advantage/disadvantage, effects and resource use; the AI supplies intent and any
GM-set difficulty the rules policy permits.

`tool` names a tool used for the check (`Thieves' Tools`), validated against installed TOOL items. An ability check
with a tool the actor is proficient with adds the proficiency bonus; a skill check with a tool the actor is also
proficient in has advantage, and a GM-imposed disadvantage is cancelled instead (SRD 5.2.1 "Tools and Skills
Together"). The result reports `tool`, `tool_proficient` and, whenever the engine itself supplied an Advantage or
Disadvantage source (tool proficiency, untrained armor), `advantage_source` naming them and any cancellation.

The actor's conditions shape the roll (`RULES_ENGINE.md` §3): Poisoned and Frightened (while the recorded source of
fear is an active participant of the same encounter) give ability checks Disadvantage; Restrained gives Dexterity
saving throws Disadvantage; the Dodge action gives them Advantage while the dodger is not Incapacitated and its Speed
is above 0; body armor worn without training gives every Strength or Dexterity check and save Disadvantage. The GM's
declared `advantage`, the engine's sources and the conditions are decided together: any Advantage source with any
Disadvantage source cancels to a plain d20, however many of either there are. The result carries `advantage_reasons`
when the list is non-empty.

Legal in `EXPLORATION` and `ENCOUNTER`; it never advances the initiative order.

### 13.3 `apply_runtime_change`

A named, rules-aware change outside the attack loop (healing, resources, conditions, HP) when no more specific tool exists. Each
kind has a schema; exceptional changes need `apply_gm_override`.

- `ADD_CONDITION {condition, duration, source?}` accepts minutes, duration text such as `"3 rounds"` or `"1 hour"`,
  milestones such as `"long rest"`, `"short rest"` or `"start of turn"`, and maps such as `{until: START_OF_TURN}`
  (`until` is `LONG_REST`, `SHORT_REST`, `RESTORED`, `START_OF_TURN` or `HP_ABOVE_ZERO`). A round duration inside an
  encounter expires when that round begins and survives the encounter's end as clock minutes (§15.5); outside one,
  `"N rounds"` is `ceil(N / 10)` minutes. Start-of-turn durations require the recipient to be an active encounter
  participant: they expire when that recipient's next turn begins, or when the encounter ends. The server records the
  participant and encounter; explicit map anchors must match them. Outside encounters, use a time-based duration or
  `"until removed"`. `source` (a character ref) records the creature behind the condition: the grappler of a Grappled
  creature, the source of a Frightened creature's fear; the attack rules read it (`RULES_ENGINE.md` §3), and without
  it those two conditions impose nothing. Condition immunities are honoured (Petrified → Poisoned, `immune_conditions`
  on active effects, stat-block condition immunities): the result reports `immune: <CONDITION>` and `skipped: <why>`
  instead of adding. Gaining Incapacitated, Stunned, Paralyzed, Unconscious or Petrified breaks the creature's
  Concentration.
- `ADD_CONDITION {condition: "EXHAUSTION"}` adds one level, ignoring `duration`; exhaustion lasts until recovered.
  `REMOVE_CONDITION` with that condition removes one level, like a Long Rest or Greater Restoration, unless the change
  carries `levels: N` (a positive integer); the result reports `removed`, the remaining `exhaustion` level,
  `condition_ended` (true only when the level reached 0) and, while levels remain, a `note` with the penalties still
  in force. A Long Rest or resurrection
  removes one level, as does Greater Restoration when Exhaustion is chosen. The sheet's `exhaustion` field and its
  single Exhaustion condition show the same level. Every D20 Test takes a penalty of twice that level, Speed falls by
  five feet per level, and level six causes death. Older saves' unnumbered Exhaustion effects count as one level until
  a journaled mutation folds them into the stored count.
- `STABILIZE` resets both death-save counts to zero with the Stable flag (SRD 5.2.1 "Death Saving Throws"), so
  damage taken while Stable counts from zero (one failure, two on a critical hit); the result's `note` says so.
  Healing that ends Unconscious (`HEAL`, `USE_ITEM`, a revival) leaves the creature Prone: `PRONE` is added and the
  result carries `prone`; the creature rights itself with `STAND` on its encounter turn (§15.3), or the GM records it
  with `REMOVE_CONDITION` outside one. A knocked-out creature's Unconscious (`until: SHORT_REST`, §15.3) ends the same
  way, or by `REMOVE_CONDITION` after first aid (DC 10 Wisdom (Medicine)). Temporary HP never wakes an unconscious
  creature.

- `USE_ITEM {item, target}`: a consumable with an encoded effect, used outside combat. A Potion of Healing of any
  potency heals by its dice (rolled and journaled, returned as `roll`) and one unit is consumed; an item without an
  encoded effect is `CAPABILITY_UNAVAILABLE` and the GM narrates it.
- `DAMAGE {amount | dice}`: a `dice` expression (`"2d6"`) is rolled and journaled by the server and returned as `roll`.
- `USE_RESOURCE` / `RESTORE_RESOURCE {resource, amount}`: the tracked uses in the sheet's `resources` block (Breath
  Weapon, Heroic Inspiration, `sorcery_points`, `innate_sorcery`).
- `CREATE_SPELL_SLOT {slot_level}` / `CONVERT_SPELL_SLOT {slot_level}`: Font of Magic conversions (points into a slot at
  the table's cost; a slot into points equal to its level), Bonus Actions in the fiction; the result carries the new
  `sorcery_points` and `slot` counts. Created slots survive level-up and Short Rests, and vanish at the next Long Rest.
- `ADJUST_MAX_HP {amount, until | minutes, reason}`: a signed `max_hp` active effect applied to the stored maximum when
  it begins and taken back when it ends, so `hp.max` is always the number in force (`hp.max_base`, `hp.max_adjustments`
  show what is temporary). A reduction clamps current HP; a maximum of 0 kills (SRD 5.2.1 "Hit Point Maximum"). Ends
  with a Long Rest (`until: LONG_REST`, default), after `minutes`, or only via `RESTORE_MAX_HP` (`until: RESTORED`).
  When a bonus ends, current HP above the maximum is lost. `RESTORE_MAX_HP` lifts every reduction and leaves bonuses.

### 13.4 `perform_rest`

Validates eligibility and advances until the rest completes or is interrupted. A completed rest advances time, processes
effects, restores only what the rules allow and records the result. On interruption it commits elapsed time and
automatic effects to that instant, creates a `REST_INTERRUPT` pending transaction and reports whether the attempted rest
qualifies for any recovery. It MUST NOT grant completion-only recovery or resolve a decision implicitly.

Either rest requires at least 1 HP (SRD 5.2.1 "Short Rest" / "Long Rest"): a named 0-HP character is refused with
`OPERATION_NOT_ALLOWED`; in a party-wide rest that character's entry is `{character, name, skipped: <reason>, hp}` and
nothing changes for them. A Short Rest heals `max(1, roll + CON)` per Hit Point Die spent (`hit_dice`
`{character: n}`; `rolls`, `hit_dice_spent`, `hit_dice_left`), recharges `SHORT_REST` resources, preserves temporary
HP and ends effects with `until: SHORT_REST` (`effects_ended`), a knock-out's Unconscious among them. A completed Long
Rest regains all lost HP and all spent Hit Point Dice (`hit_dice {current, max}`; the pool is `recharge: LONG_REST`),
clears temporary HP, removes one Exhaustion level (`exhaustion_removed`, `exhaustion`) and ends both `LONG_REST` and
`SHORT_REST` effects. A Long Rest within 16 hours of the previous one's end is refused (or skipped in a party-wide
rest); the end is stored in `character.last_long_rest_seq` (migration V010).

### 13.5 `award_xp`

Awards XP from an explicit source; encounter XP SHOULD come from `end_encounter` instead. `characters` is OPTIONAL:
omitted, the award follows `rules.xp_policy` (`RULES_ENGINE.md` §6) and reaches the whole active party, the right call
for quests, discoveries and roleplay. The response reports `xp_policy`, every `awarded` entry (including companions
raised under `LOCKSTEP`) and, per `rules.companion_level_up`, `companion_level_ups` or `companions_awaiting_level_up`;
under `PLAYER` each waiting entry carries a `proposal` (hit points and ability improvement) for the player to accept or
amend. `end_encounter` reports the same three fields. Discretionary awards require a reason and policy authorization.

### 13.6 `search_rules`

Ranked free-text search across installed rules definitions and the campaign's custom content. Each hit carries `ref`,
`kind`, `name`, optional `tag` and a `snippet` of the rules text. `kind` narrows (`RULE`, `SPELL`, `ITEM`, `CREATURE`,
`FEAT`, …); omitting the campaign searches installed rules only. The SRD Rules Glossary is installed as `RULE` content,
verbatim.

The GM SHOULD call this before answering a rules question or adjudicating an unfamiliar situation, and cite the `ref`.
When nothing matches, the rule is not in the SRD. Read-only, allowed in every harness state.

### 13.7 `get_content_definitions` and `define_content`

`get_content_definitions` returns installed definitions of a kind (`ITEM` including weapons, armor, gear, tools, mounts,
vehicles, poisons and the SRD magic items: rings, rods, staffs, wands, potions, scrolls, wondrous items and the magic
weapon, armor, shield and ammunition templates; `SPELL`; `CREATURE`; `CONDITION`; `CLASS_FEATURE`; other ruleset kinds).
Filters: tags, text, spell level/class, CR range, price range, `magic` (true or false) and `rarity`; cursor pagination.
`SUMMARY` detail is presentable on its own (items: type and price; spells: level, school, classes, one-line summary;
species, classes, skills: `summary`). A magic item's summary carries `magic` (rarity, attunement, template and
`applies_to`, `adjudication`, the modifiers or bonus the engine applies) and `magic_label`, plus its `summary` in place
of the verbatim `text`, which `FULL` detail returns; the result lists `rarities`.

`define_content` creates a campaign-scoped definition with a `content:` reference and optional `custom:` symbolic id:

- `ITEM`: name, type, cost, weight, description, mechanical properties, tags. Names must be unique among the
  campaign's custom items, ignoring case and surrounding whitespace; a duplicate returns `CONFLICT` with the
  existing reference. Items in other campaigns and backgrounds have separate names.
- `BACKGROUND`: SRD 5.2.1 shape: `properties.ability_scores` (three abilities), `properties.feat` (an Origin feat),
  `properties.skills` (two), `properties.tool` (`{item}` or `{choice: ARTISANS_TOOLS|GAMING_SET|MUSICAL_INSTRUMENT|TOOL}`),
  optional `feat_choices` presets and `starting_equipment` options (default fifty gold). References are validated
  against installed content; the name must not collide with an installed or campaign background. Usable by name,
  symbolic id or `content:` reference wherever a background is chosen (`create_character_draft` /
  `update_character_draft` with `background`, `background_ability_scores`, `background_tool`; a companion's promotion;
  `get_character_choices` scope `BACKGROUND`; `get_content_definitions` kind `BACKGROUND`, flagged `custom: true`).

Definitions are validated against their kind's schema and recorded with provenance and licensing. Defining is not
granting: putting a custom item into play goes through `trade`, `grant_loot` or `transfer_item`.
Older campaigns may contain duplicate item names. Resolving such a name returns `CONFLICT` with `candidates`;
use a specific `content:N` or `custom:` symbolic id. Existing definitions and inventories are preserved.

### 13.8 `roll_dice`

A free, journaled roll for whatever no semantic tool rolls (falling damage, a table, an NPC's dice). Nothing is applied.

```yaml
operation_id: "..."
campaign: campaign:1
expression: 2d6+3        # NdS with +/- constants; keep/drop (4d6dl1, 2d20kh1); several terms (1d8+1d6-1)
actor: character:4       # optional
reason: fall from the sea-cliff
```

Returns `roll` (expression, dice, dropped, modifier, total), `total` and `roll_ref`. Legal in `EXPLORATION` and
`ENCOUNTER`.

Expressions accept leading signs, whitespace, case-insensitive dice notation and typeset minus signs. Every `+` or `-`
must be followed by a term. Each dice group rolls 1–1,000 dice with 1–10,000 sides; a constant's magnitude is at most
2,147,483,647. `kh`/`kl` keep 1 through all dice; `dl`/`dh` drop zero through all but one, so every group retains a die.
The final constant modifier and every possible total must fit -2,147,483,648 through 2,147,483,647. Incomplete or
out-of-range expressions return `INVALID_ARGUMENT` before consuming dice or writing a roll record.

### 13.9 `cast_spell`, `prepare_spells` and Metamagic

`cast_spell {caster, spell, slot_level?, targets?, options?}` shares its casting core with the encounter `CAST` action:
checks preparation and slots, spends the slot, rolls attacks, saves and damage, applies conditions and concentration,
returns rules text for anything unstructured. `options` carries spell-specific choices: `ritual: true`, `damage_type`
(Chromatic Orb), `against` (a buff aimed at one creature), `condition` (which condition a cure removes).

`ritual: true` casts any prepared (or known / always prepared) spell with the Ritual tag without a slot, ten minutes
longer; no class feature is needed (SRD 5.2.1 "Rituals"; `RULES_ENGINE.md` §8). A caster wearing body armor without
training cannot cast at all: `cast_spell` and the encounter `CAST` action refuse with `OPERATION_NOT_ALLOWED` (SRD
5.2.1 "Armor Training").

Inside an encounter, on the caster's own turn, only one spell that expends a spell slot may be cast (SRD 5.2.1 "One
Spell with a Spell Slot per Turn"): a second is refused with `OPERATION_NOT_ALLOWED` before anything is spent;
cantrips, rituals and feat free casts are exempt, reactions on other creatures' turns are unaffected, and Shield cast
as a reaction on the caster's own turn counts. Quickened Spell is refused after a level 1+ spell that turn and allows
no further level 1+ spell after it (`RULES_ENGINE.md` §5).

Targets making simultaneous saving throws share one damage roll, including any Empowered rerolls. Each target's
save and defenses still determine its own damage. Separate spell attacks and Magic Missile darts roll independently.
Spell attack rolls use the same condition table as weapon attacks (`RULES_ENGINE.md` §3) and report `advantage` and
`advantage_reasons`; a non-Touch spell attack has Disadvantage with a hostile, seeing, non-Incapacitated enemy in the
caster's zone. A spell that makes a creature Unconscious, Stunned, Paralyzed, Petrified or Incapacitated breaks its
Concentration; the Concentration save DC on damage is `max(10, min(30, damage / 2))`.

Magic Missile against a creature in an encounter that can cast Shield opens a `SHIELD_SPELL` pending choice (context
`magic_missile: true`, `spell`, `attacker`, `target`, `darts[]`) before damage; the darts are rolled at cast time and
applied only on `DECLINE` (the resolve result lists `darts[]`), while `CAST_SHIELD` yields `shield`, `damage: 0` and
`negated`. A target whose Shield is already up is negated automatically (`negated`, damage 0) and an NPC under the
`AUTO` reaction policy casts it outright. Target entries carry `rolled` and `pending: "SHIELD_SPELL"` or `negated`;
the cast result has `pending` and `next_step`. Sleep repeats its save once at the end of the target's next turn, a
second failure escalates to Unconscious with no further repeat, and the effect ends when the target takes damage
(`ended_on_damage` on the damage result). Heal restores 70 HP, +10 per slot level above 6, and ends Blinded, Deafened
and Poisoned.

A sorcerer adds `options.metamagic`, one name or a list (`["Empowered Spell", "Quickened Spell"]`), with the extras an
option needs: `heightened_target` (default first target), `careful` (creatures that succeed automatically),
`damage_type` for Transmuted, `sorcery_incarnate: true` while Innate Sorcery is active at level 7 or above. Options must
be known (`spellcasting.metamagic.known`), applicable and paid in sorcery points before anything is rolled; the result's
`metamagic` block reports what was used, its cost and the points left. Seeking spends its point only when a missed attack
roll is rerolled.

Empowered uses one Charisma-based reroll allowance (minimum one die) across the casting's damage rolls. The engine
rerolls the lowest dice in each roll as it resolves, only dice below their maximum, carrying any unused allowance to
later damage parts, darts or attacks. Each die is rerolled at most once; the sorcery point is charged when the first
die is rerolled, so a casting that rerolls nothing (a missed attack spell, every die at its maximum) spends no point
and reports `points_spent` only when a die was rerolled. Extended gives the caster Advantage on Concentration saves
for that spell (`extended_spell` on the concentration effect; the save's `roll.expression` is `2d20kh1+N`).
Heightened's Disadvantage carries to the spell's repeat saves (`repeat_save.disadvantage`; `repeat_saves` entries
show `2d20kl1`). The sheet reports `options_known_limit`: two at level 2, four at 10, six at 17.

`options.weapon` (a name or `inventory:N`) names the carried weapon a weapon-enchanting spell binds to (Magic Weapon,
Shillelagh; default the wielder's equipped weapon): its bonus, die and damage ride only on attacks with that weapon and
stack with the weapon's own enchantment; the slot raises Magic Weapon to +2 (level 3–5) or +3 (6+). A magical weapon is
refused (`NONMAGICAL`), a wrong kind (`WEAPON_KIND`); each target in the result names the `weapon`. Flame Blade conjures
a weapon: `ATTACK` with `attack: "Flame Blade"` while it lasts (RULES_ENGINE.md §10).

Named weapons prefer an equipped copy, then the lowest inventory ID, just like named attacks. Use `inventory:N` to
choose a particular copy, including an unequipped one.

`prepare_spells {character, cantrips?, spells?}` replaces the class-chosen lists; species- and feat-granted spells stay.

---

## 14. Inventory and Economy

### 14.1 `transfer_item`

Moves an item or quantity between characters, containers, locations or loot sources, enforcing ownership, quantity,
capacity and visibility.

### 14.2 `equip_item`

Equips or unequips a carried weapon, armor, shield, focus, ring, rod, staff, wand or worn wondrous item and returns the
resulting mechanical changes: Armor Class before and after, the equipped set and what is attuned. Slots: one body
armor, one shield, two hands, two rings, and one of each worn slot (`CLOAK`, `BOOTS`, `HEAD`, `NECK`, `BELT`, `HANDS`,
`WRISTS`, `EYES`); a magic item without a slot (a Bag of Holding) is carried, not worn (`NOT_EQUIPPABLE`). An equipped
item that requires attunement is attuned; a fourth is `ATTUNEMENT_LIMIT` (SRD 5.2.1 "Attunement"). A magic item's +N
and modifiers apply only while equipped.

Armor training is checked, not enforced, at equip time (SRD 5.2.1 "Armor Training"): the result `warnings` say when
a shield gives no Armor Class benefit (no Shields training), when body armor the character lacks training with gives
Disadvantage on Strength and Dexterity D20 Tests and blocks spellcasting, and when heavy armor's Strength requirement
is unmet and Speed falls by 10 feet; the sheet shows the consequences (§13.1).

### 14.3 `trade`

Purchase, sale or barter, atomically. Price comes from canonical merchant, market and rules state unless an authorized
negotiated price is supplied with provenance. Money and items commit together.

For `BUY`, `quantity` counts items or ammunition bundles and defaults to one. For `SELL`, it counts individual
pieces and defaults to the entire carried stack. Sales return half list value (full value for valuables), prorated
by the pieces sold and rounded down to whole copper. A negotiated price overrides the total payment.

### 14.4 `grant_loot`

Materializes and transfers rewards from an authorized encounter, quest, world source or explicit GM grant, subject to
campaign GM policy and audit.

Magic items are granted by name (`{item: "Ring of Protection"}`). A magic weapon, armor, shield or ammunition entry is a
template made on a base item and is instantiated at grant time as campaign content: `{item: "+1 Longsword"}`,
`{item: "Weapon, +1, +2, or +3", base: "Longsword", bonus: 2}`, or `{item: "Flame Tongue", base: "Longsword", name:
"Ember"}`. The result names the definition (`content:N`) and its rarity; a template without a base, a base of the wrong
kind, or a base that is itself magical is refused. A definition is reused only when the name, template, base and
bonus match. A same-name recipe with different mechanics returns `CONFLICT` with the existing reference. A `+N`
name and an explicit `bonus` must agree (`INVALID_ARGUMENT` otherwise); bonuses must be integers from 1 through 3.

An enchantment the SRD does not list (an Arrow of Fire, a frost blade, a lucky charm) is made on a mundane base with
`magic`: `{item: "Arrow", quantity: 10, magic: {name, rarity, text, bonus?, damage_bonus_dice? + damage_type?, ac_bonus?,
save_bonus?, attack_bonus?, speed_bonus?, resistance?, attunement?, slot?, consumable?: {heal}}}`. `name` and `text` are
required; the engine applies the +N, the extra damage dice on hits with that weapon or piece of ammunition, and the worn
modifiers while equipped; the text is the GM's (`text_is_paraphrase: true`). In this form, `item` names the mundane base;
put `name` and `bonus` inside `magic` and omit the outer `base`, `name` and `bonus`. Priced base + rarity value. Repeating a
matching enchantment reuses its definition; changing its base, rarity, mechanics or text under the same name returns
`CONFLICT`. Grant an existing definition by `content:N`, or choose a different name for a different enchantment.

**Treasure.** A completed quest (§12.1), a major encounter (§15.5) and a bootstrap that finds a party of level 3, or
with two completed quests, without a single magic item return `treasure` (or a `meta.warnings` line): `party_level`,
`tier`, `rarities` (weights), `frequency`, `party_magic_items` and `suggestions` drawn through the roller from the
installed magic items (`ref`, `name`, `rarity`, `attunement`, `needs_base` for a template, `summary`). The GM SHOULD
grant something at those moments at about the stated rate; see RULES_ENGINE.md §10 for the bands.

### 14.5 `give_money`

Money inputs accept whole copper, denomination maps (`{"gp": 1.5, "sp": 2}`), or complete text amounts
(`"1.5 gp 2 sp"` or `"1gp2sp"`). Coin codes are case-insensitive; decimal text uses a period and may start with
one (`".5 gp"`). Each component must be non-negative and convert exactly to whole copper: `"1.5 gp"` is 150 cp,
while `".5 cp"` is rejected, even when combined with another fractional-copper component. Signs, stray text,
non-finite numbers, fractional copper and overflow return `INVALID_ARGUMENT`; input is never rounded or partially
matched. This format applies to prices, grants, transfers, balances and fixed cash-flow amounts.

Moves coin from one character to another in one transaction. `from` and `to` are character references, `money` any form
`trade` accepts (`'1 gp 5 sp'`, `{"gp": 15}`, copper integer), `reason` optional. The giver must hold the amount
(`INSUFFICIENT_RESOURCE`), nobody pays themselves, the amount is positive. Writes one `MONEY_GIVEN` event naming both
characters and returns the amount and both balances. Never creates or destroys money (that is `grant_loot` or `trade`).

### 14.6 Money references, accounts, `create_account`, `transfer_money`, `get_accounts`

A *money reference* is `character:n` (the purse), `account:n` (a treasury of an estate or faction) or `WORLD` (the
bottomless outside world). Parsing is case-insensitive; an account may also be named by its unique name.

- `create_account` (MUTATING): `name`, `owner_kind` (`FACTION`, `ESTATE`, `CHARACTER`, `OTHER`), optional `owner`,
  `notes` and opening balance, recorded as a `MONEY_FLOW` from `WORLD`.
- `transfer_money` (MUTATING, atomic): `from`, `to`, `money`, `reason` between any two money references; the source
  must hold the amount (`INSUFFICIENT_RESOURCE`); one `MONEY_FLOW` event names both sides, characters involved become
  actors. Between two characters `give_money` remains the idiom.
- `get_accounts` (read-only, always allowed): every account with balance and `upcoming` cash flows, the party's
  `purses`, the `calendar`, and `recent_runs` (last N firings with status, amount and event).

### 14.7 Cash flows: `define_cash_flow`, `update_cash_flow`, `list_cash_flows`

A cash flow is a rule the engine fires when the clock crosses its due point. `advance_time`, `move_party` and
`perform_rest` run the scheduler after moving the clock and list firings under `consequences` (`type: CASH_FLOW` with
`status`, `amount`, `from`, `to`, `due`, `event`, `next_due`, `note`).

`define_cash_flow` takes one `spec`:

- `name` (unique), `kind` `MONEY` (default) or `EVENT`, `description`.
- `from`, `to`: money references (MONEY only; at least one side must not be `WORLD`).
- `amount`: money in any form (fixed); `{"percent": 10, "of_rule": <name or ref>}` (a share of that rule's payout at the
  same due point, firing after it; nothing if it paid nothing); `{"percent": 10, "of_inflows": "account:n"|"character:n"}`
  (a share of every coin that bag received via `MONEY_FLOW`, `MONEY_GIVEN` or loot since this rule last ran, or since
  it was written for the first run; fires last).
- `cap` (MONEY only): a total; the rule is a debt. Each firing pays at most what is still owed (cap minus PAID runs;
  UNPAID runs change nothing). When cleared the rule finishes: `next_due` null, inactive, consequence `finished: true`,
  note "cleared: N of N paid". Views show `cap`, `paid`, `remaining`.
- `schedule`: `{"kind": ONCE|DAILY|WEEKLY|MONTHLY|YEARLY|SEASONAL, "weekday": 1–7 or name, "day": 1–31 (clamped to the
  month), "month": 1–12 or name, "season": SPRING|SUMMER|AUTUMN|WINTER (fires on its first day), "minute_of_day" or
  "time": "06:00"}`; a bare string is a kind. `ONCE` fires at `start` and finishes.
- `season`: multipliers by season of the due date (`{"WINTER": 0.2}`).
- `condition`: `{"quest": "quest:n", "status": "ACCEPTED"}`; the run is `SKIPPED` unless the quest has that status.
- `start`, `end`: `'Day N, HH:MM'` or `{year, month, day, minute_of_day}`; `start` defaults to now.

Due points are processed in order; within one, fixed amounts fire first, then shares of a rule, then shares of inflows.
A paid MONEY run moves the coin and writes a `MONEY_FLOW` event dated at the due point (`MINOR`, `NOTABLE` from 10 gp).
An insufficient source writes an `UNPAID` run and a `NOTABLE` event; the engine does not carry the debt forward. An
EVENT run writes a `WORLD_EVENT` with the description. Every run is a `cash_flow_run` row.

`update_cash_flow` changes any of `name`, `from`, `to`, `amount`, `cap` (null removes), `schedule`, `season`,
`condition`, `description`, `start`, `end`, `active` (false stops, true restarts); schedule changes recompute the next
due point. `list_cash_flows` (read-only) lists rules soonest-due first with their last run; `include_inactive` adds
stopped and finished ones.

### 14.8 The calendar: `set_calendar`

The clock is unchanged: `sequence` is minutes since campaign start and `"Day N, HH:MM"` stays the primary display.
`set_calendar` (MUTATING, audited) fixes the date Day 1 falls on (`year`, `month`, `day`) in a 365-day year of twelve
months with the familiar lengths, no leap years, seven-day weeks (Monday = 1; 1 January of year 1 is a Monday) and four
seasons by month (spring March–May, summer June–August, autumn September–November, winter December–February). Without
a calendar Day 1 is 1 March of year 1. Once set, the epoch moves only with `force`, which recomputes every weekday-,
month- or season-bound rule. Every `game_time` from clock-moving operations then carries `date` (`year`, `month`,
`day`, `month_name`, `weekday`, `weekday_name`, `season`, `day_of_year`, `display`) and a `display` such as
`Day 93, 09:41 (Thursday 1 June, year 1, summer)`.

---

## 15. Encounters

### 15.1 `start_encounter`

Creates the encounter, validates participants and sides, snapshots starting state, rolls initiative and returns the
first actionable turn. The implemented
[mandatory-grid combat contract](DESIGN.md#15-encounters) requires a saved tactical scene and validated
participant positions before every new combat starts, including incidental outdoor encounters. Travel
remains graph-based; scene preparation/reuse occurs at the actual location or journey anchor. Grid
mechanics apply in text-only clients as well; optional capabilities concern image display/interaction.
The current profile uses a five-foot occupancy lattice with five- or ten-foot visual overlays.
Legacy zone positions are not treated as physical coordinates; see [MAP_USAGE.md](MAP_USAGE.md)
for scene preparation and conversion. Capability reporting describes this implemented profile.

Initiative is a Dexterity check (SRD 5.2.1 "Initiative"). A group of identical creatures on the same side (the same
stat block) gets one roll applied to each member; their `initiative_rolls` entries carry `shared_roll`. Each entry
carries `advantage`, `advantage_reasons`, `surprised` and `shared_roll`: a surprised side, an Incapacitated combatant
and a character in untrained armor roll with Disadvantage, an Invisible one with Advantage. The engine breaks no ties:
no Dexterity comparison, no hidden roll. Tied combatants keep a provisional order (the party's side first, then roster
order) and the result carries `ties: [{initiative, participants: [{participant, character, name, side}], decided_by:
"GM" | "PLAYERS"}]` with a `ties_note`, because the SRD leaves ties to the GM (monsters, or a monster and a character)
and to the players (characters). Call `perform_encounter_action` in the agreed order.

### 15.2 `get_encounter_state`

Round, turn, participants, sides, visible conditions, positions where a spatial model is active, available actions or
constraints, and revision. Secret opponent information is omitted or marked by visibility.

The GM view retains every participant and the actual current turn, including unseen or alerted opponents.
Player projections and map renders remain perception-filtered. A hidden actor still uses its own senses to
select targets; GM knowledge does not grant perception to that actor.

Effective tactical controllers identify whose decisions the client should request (§12.6.1).
Player-controlled companions retain their own turns; taking control does not merge their movement
or action budgets with the primary character's. Hidden opponent information remains filtered.

For an ended encounter, `ammunition_recovery` appears when an optional search is available (§15.6). Read the explicit
encounter reference to revisit an offer; omitting `encounter` still selects a running encounter.

### 15.3 `perform_encounter_action`

Resolves one action atomically. Kinds: `ATTACK`, `CAST`, `MOVE`, `DASH`, `STAND`, `DISENGAGE`, `DODGE`, `HELP`,
`HIDE`, `READY`, `USE_ITEM`, `INTERACT`, `OTHER_RULES_ACTION`, `END_TURN`.

The current implementation refuses `HIDE` with `CAPABILITY_UNAVAILABLE` before rolls or Action
expenditure; shared concealment/discovery and termination are pending. Resolve Stealth and explicit
GM adjudication separately. It does not report a mechanically hidden creature.

Attacking a downed same-side member requires `confirm_hostile_ally:true` after explicitly declared
hostile intent. An ordinary attack returns care guidance without expenditure or damage. A melee
opportunity-attack TAKE choice accepts `nonlethal:true` and stops a knocked-out mover's continuation.

`STAND` is a Prone creature righting itself (SRD 5.2.1 "Prone"): movement, not an action, so the turn stays open. It
costs half the creature's Speed (rounded down), reported as `movement_cost` beside `speed` for the GM to hold against
the turn's movement, since zones carry no movement budget. It is refused while the creature is not Prone or its Speed
is 0 (Grappled, Restrained, Paralyzed, Unconscious, Petrified, or Exhaustion). `MOVE` and `DASH` while Prone are
crawling (each foot costs 1 extra foot) and do not end the condition; the result says so in `crawling`.

Returns the validated interpretation, rolls and modifiers, resource expenditure, damage, healing, conditions, movement
and other consequences, defeated/dying changes, triggered reactions or pending choices, the next turn or required
follow-up, and the new revision. An action needing a player or GM choice creates a typed pending transaction rather
than guessing.

Within one damage application, components of the same type are combined before save reductions, resistance and
vulnerability; separate hits, rays and darts remain separate applications. `breakdown` has one row per type with
`rolled` (entering defenses), `applied` and any defense `note`. A single component retains `roll`; multiple components
retain their original types, amounts and rolls in `parts`. When a save halves damage, `before_save` reports the
combined amount before that reduction. See `RULES_ENGINE.md` §3 for the full damage pipeline.

`CAST` takes the `cast_spell` fields flattened into the action (`spell`, `targets` or `target`, `slot_level`,
`metamagic`, `heightened_target`, `careful`, `damage_type`, …; §13.9).

`ATTACK` may name `ammunition` ("+1 Arrow") to fire magic ammunition made on the weapon's own kind; its +N applies to
that attack and damage roll and the result's `ammunition` reports the piece, its `bonus` and what remains.
Fired pieces are recorded for optional post-combat recovery (§15.6), including whether the resolved attack hit and
the ammunition's recovery policy. An interrupted attack records the final result when it resumes.

After an Attack action with a Light weapon, `ATTACK {target, weapon: "inventory:N", light_extra_attack: true}`
makes the Light property's extra attack with a different carried Light weapon. It spends the turn's Bonus Action;
positive ability damage modifiers are omitted unless the character has Two-Weapon Fighting. Negative ability
modifiers, magic bonuses and other damage bonuses still apply. Two separate inventory entries of the same weapon
qualify; the same entry does not. Equipping items from a stack splits individual weapons into separate entries.
The initiating attack reports `light_extra_attack_available` with eligible weapon refs and keeps the turn open
even with `end_turn: true`. Take the extra attack or use `END_TURN` to skip it. The extra attack reports
`light_extra_attack: true` and `action_cost: BONUS_ACTION`; it cannot be repeated that turn. Encounter state reports
`bonus_action_available` and any eligible Light weapons for the current participant. Bonus-action spells, Quickened
Spell and Font of Magic slot creation share this allowance. Converting a slot to sorcery points takes no action.

While the turn stays open (`end_turn: false`) and nothing is pending, the result carries `turn_economy`: `action`,
`bonus_action` and `reaction` as `AVAILABLE` or `SPENT`, `movement` as `AVAILABLE`, `HALF_SPENT` (after `STAND`) or
`USED` (after `MOVE` or `DASH`; zones carry no distance), and `attacks_remaining` for a Multiattack. The Action is
recorded by `ATTACK`, `CAST` (unless the spell took the Bonus Action), `DODGE`, `DASH`, `DISENGAGE`, `HELP`, `HIDE`,
`USE_ITEM` (unless a potion) and `OTHER_RULES_ACTION`; a second unrelated Action is refused before rolls or resources
are spent. Remaining attacks from a supported Multiattack or Extra Attack, and the Light Bonus Action, remain legal.
Additional Actions from Action Surge or Haste require explicit supported adjudication. While the
Action is unspent, `next_step` says so. These are facts for the GM, not prompts for the player: the GM asks about a
leftover only when the declaration was incomplete, it matters in the scene and the player may not know the rule;
otherwise the turn ends quietly. Encounter state reports `action_available` beside `bonus_action_available` for the
current participant.
Nick mastery remains GM-adjudicated.

Omitting `end_turn` on a Bonus Action `CAST` keeps the turn open. Movement likewise keeps the turn open by default.
An action may include optional `narration` (up to 300 characters) to retain a concise description beside its
mechanical log entry. This prose does not change outcomes, resources, loot or visibility.

Opportunity Attacks select a melee stat-block action or a carried melee weapon, falling back to an Unarmed Strike.
An explicit `weapon` choice must also be melee; a ranged attack cannot be used for this reaction.

`USE_ITEM {item, target}` uses a carried consumable: a Potion of Healing of any potency heals by its dice; an item without an
encoded effect is consumed and left to the GM. An item of type `POTION` spends the turn's Bonus Action (SRD 5.2.1
"Potions": drinking requires a Bonus Action), the same one the Light extra attack and bonus-action spells use: the
result reports `action_cost: "BONUS_ACTION"`, and it is refused with `OPERATION_NOT_ALLOWED` once the Bonus Action
is spent.

`apply_runtime_change` with `kind: USE_ITEM` is restricted to exploration. In combat, use this
encounter action so turn ownership and the Action/Bonus Action allowance are enforced before rolling
or consuming inventory.

Attack results carry `advantage` (`ADVANTAGE`, `DISADVANTAGE` or `NONE`) and `advantage_reasons` from one condition
table shared by weapon and spell attacks (`RULES_ENGINE.md` §3): the attacker's Blinded, Restrained, Prone, Poisoned,
Grappled (against anyone but the recorded grappler) and Frightened (source of fear active in the encounter) give
Disadvantage, Invisible gives Advantage; the target's Blinded, Restrained, Stunned, Petrified, Paralyzed and
Unconscious grant Advantage (the last two make a hit from within 5 feet critical), Prone grants Advantage within 5 feet
and imposes Disadvantage beyond, Invisible imposes Disadvantage, Dodge imposes Disadvantage while the dodger is not
Incapacitated and has Speed above 0. A ranged weapon attack has Disadvantage with a hostile, living, seeing (not
Blinded), non-Incapacitated participant in the attacker's zone unless the attacker is Invisible. Attack rolls add the
proficiency bonus only with a weapon the character is proficient with (`warnings` otherwise; unarmed strikes always);
body armor worn without training gives the roll Disadvantage.

`ATTACK {nonlethal: true}` with a melee attack that would drop the target to 0 HP knocks it out instead (SRD 5.2.1
"Knocking Out a Creature"): the target is at 1 HP, `ALIVE`, has no death-save record and gains `UNCONSCIOUS` with
duration `{kind: UNTIL, until: SHORT_REST, expires_seq: now + 60 minutes, label}`, ending when it completes a Short
Rest (one hour on the clock), regains any HP, or receives first aid (DC 10 Wisdom (Medicine); GM `REMOVE_CONDITION`).
The result reports `knocked_out: true`; the participant is `DEFEATED` for the rest of the encounter and cannot be
attacked (`RULES_ENGINE.md` §6).

A hit on a creature that can cast Shield opens a `SHIELD_SPELL` pending choice on every hit by an attack roll,
Opportunity Attacks included (inside the `pending_choices` of a `MOVE` result), natural 20s and hits beyond AC + 5
too, since the +5 lasts until the start of the caster's next turn; the prompt says whether casting turns this hit
into a miss. Under the `AUTO` NPC reaction policy an NPC casts it only when it does. Magic Missile's Shield offer is
described in §13.9.

### 15.4 `resolve_pending_choice`

Completes a server-created pending choice by transaction reference and one of the legal options returned.

Normal `npc_reactions: AUTO` requests decisions for the primary player character and companions
assigned detailed player control. DM-controlled NPCs retain normal automatic reaction handling.
`ASK` can request every NPC reaction; the client resolves DM-controlled actors' choices itself and
asks the player for player-controlled actors. An existing pending choice must be resolved before
changing tactical control, so its owner and the paused action cannot change underneath it.

### 15.5 `end_encounter`

Validates the outcome, commits completion, distributes mechanically defined rewards, updates quests and events as
configured, returns level-up eligibility and Director-trigger recommendations. Cannot end while mandatory pending
reactions or choices remain. A major encounter (an XP pool of 200 or more, or a player character's death) also returns
`treasure` (§14.4).

Omitting `encounter` resolves the active encounter. Optional `narrative` stores the GM's longer account. Completion
stores a persistent record containing the roster, resource changes, observed scene changes and at most eight
engine-selected highlights. The MCP reply returns a bounded `party_digest` and a `full_gm_record` read pointer.
The GM record retains hidden facts; the existing resolved ledger event receives a
party-safe digest. Unknown private facts and unsafe supplied prose are excluded from that player chronicle.
The record survives a fresh server process and can be read with `get_encounter_state` using the ended encounter ref.

Timeline event replies expose a compact `encounter_digest` and a `full_gm_record` retrieval pointer.
The party digest omits initial rosters, HP and nested mechanics; the GM record retains them.
Bootstrap/timeline recall and the end reply cap their digest at 2,000 bytes of compact UTF-8 JSON,
with measured byte count and maximum fields beside it. Abbreviated digests carry `truncated:true`;
highlights are trimmed individually. Saved detail and chronicle material retain their full content.
Historical reads use end-time rosters and knowledge independently of the actors' current board or state.
They expose no live turn/actions and explicitly mark unrecorded historical positions unavailable.
New grid completions persist final `map_ref`, `map_name`, `level`, `move_origin_ft`/`position_ft`
and `footprint_ft` in the GM final roster. Historical PLAYER coordinates come from a separately
saved end-time observation set plus completion-time entitlement to the party's own positions, even
if the entire party is unconscious. Known-but-unseen nonparty actors gain no new coordinates;
later membership/control changes cannot reveal them. Bootstrap keeps
up to eight recent events and two digests under a shared 3,000-byte compact UTF-8 JSON cap;
older fights retain summaries and full-record pointers, without trimming timeline/stored history.
End replies also cap `highlights_needing_narration` at 2,000 compact UTF-8 JSON bytes, exposing brief
prompts and exact annotation slots without full mechanics. Measured/maximum byte fields describe this
separate budget; `full_gm_record` retrieves the complete stored highlights and mechanics.
`episodic_detail` is readable prose, regenerated on annotation without another resolved event.

The clock advances six seconds per round and retains second precision. Effects with a duration in rounds do not end
with the fight: the rounds each has left after the final round become game minutes, `ceil(remaining × 6 s / 60)` from
the post-fight clock, stored as `{kind: MINUTES, expires_seq, converted_from_rounds: n, label}`; effects already due
end at once, and the result reports `round_effects_converted_to_minutes`. Start-of-turn durations (`until:
START_OF_TURN`) end with the encounter because that turn never comes (`RULES_ENGINE.md` §6).

When a shooter can search the battlefield, `ammunition_recovery` offers a one-minute search (§15.6). The offer does
not restore anything, spend time, create a pending transaction, or block exploration. The GM offers the search to
the player and calls `recover_ammunition` only when the player requests or agrees to it.

### 15.6 `recover_ammunition`

An optional, atomic post-combat search. Required fields are `operation_id`, `campaign`, `encounter` and `character`;
the last two are explicit references to the ended encounter and its original party shooter. Recovery requires
`EXPLORATION`, no active encounter, a living character able to search, and the character at the encounter's known,
non-null location. The GM confirms that the battlefield is accessible, including after returning from flight or
defeat. Each character can search an encounter once; a new operation ID does not permit a second search.

`end_encounter` and `get_encounter_state` return an `ammunition_recovery` list only when offers exist. Each offer is:

```text
{character, name, used, recoverable, elapsed_minutes: 1, optional: true,
 default_method: RANDOM, selection_required,
 candidates: [{group, item, name, quantity, hit, recover_as: ORIGINAL|MUNDANE|GM, base_item?, text?}]}
```

`used` counts recorded fired pieces across all ammunition types for that shooter; `recoverable` is `floor(used / 2)`
across that total, not a separate rounding per type. Fewer than two recorded pieces produce no offer and cannot be
searched. Candidate group numbers identify the choices in that offer; `quantity` is the number of pieces in a group,
and `hit` is the final attack result. Ordinary ammunition returns as `ORIGINAL`. Standard +1/+2/+3 ammunition that
hit returns as `MUNDANE` on its base item; a miss retains the original magic. Other magic or custom ammunition is
`GM`: its rules text determines whether the selected pieces retain their original form, become mundane, or are lost.

Omitting `selection` draws a random half of all recorded fired pieces. Each piece has equal chance: groups are
weighted by their remaining quantities and pieces are drawn without replacement until the global `recoverable`
quota is met. Random selection is the engine's default adjudication for which half survives. The result reports
`method: RANDOM`. An explicit `selection` overrides that draw and reports
`method: EXPLICIT`; it must choose exactly the offered quota, using each group at most once:

```json
[{"group": 1, "quantity": 2}, {"group": 3, "quantity": 1, "recover_as": "MUNDANE"}]
```

`recover_as: ORIGINAL|MUNDANE|LOST` is required only for a `GM` candidate. `LOST` accounts for selected pieces that
were destroyed or cannot be recovered: they count toward the quota and add nothing to inventory. The engine refuses
duplicate groups, quantities beyond a group, a wrong total, or a caller-supplied policy for a deterministic group.
An offer has `selection_required: true` only when a candidate has `GM` policy. If any such pool exists, omitted
`selection` is refused before drawing or writing anything: the GM supplies the selection and adjudicates any selected
`GM` groups from their rules text. The random default never guesses unknown magic recovery rules.

A successful search spends one minute, applies time consequences, restores the selected inventory, and records the
search together. Reusing `operation_id` returns the original result and random selection; a fresh ID is refused after
that shooter's search is recorded, so retries cannot reroll the recovery. Checkpoint restore rewinds inventory, time,
and search eligibility together. Older shots without recovery metadata are ignored rather than reconstructed from
item names. The GM MUST NOT grant recovered ammunition
by hand or perform the search automatically.

### 15.7 `annotate_encounter`

After completion, annotate the ended encounter by explicit ref. Supply `narrations`
mapping the saved highlight slots (`log:<id>`) to concise narration. Only that encounter's selected slots are
accepted. This updates the saved GM record and the existing party-safe ledger digest; it does not repeat the
fight, award XP or grant loot. Reusing the operation ID replays the same result. Checkpoint restore rewinds the
annotations together with the encounter and ledger event.

---

## 16. Progression

### 16.1 `begin_level_up`

Creates a pending level-up transaction from authoritative XP and rules state.

### 16.2 `get_level_up_choices`

Legal remaining choices and a preview of automatic changes.

### 16.3 `update_level_up`

Adds or revises selections without changing the live character.

`metamagic` is required when the new level raises the number of Metamagic options a sorcerer knows (two at level 2, one
more at 10 and 17): `get_level_up_choices` reports `metamagic_choice` with the count and options, and `commit_level_up`
refuses until exactly that many are passed (`choices.metamagic = ["Empowered Spell", "Quickened Spell"]`). A sorcerer
who levelled before the feature was data-driven has `spellcasting.metamagic.unchosen` on the sheet;
`apply_gm_override SET_METAMAGIC` sets the options outside a level-up.

`feat_choices` is one object naming the feat (`{"feat": "Skilled", "proficiencies": [...]}`) or a list when several
feats owe choices at once. A feat committed with choices pending is reported as `pending_feat_choices` at every later
level-up, where `feat_choices` completes it; with nothing pending, `feat_choices` outside a first class level is refused.

### 16.4 `validate_level_up`

Violations and a complete before/after preview, including chosen feat ability increases and resulting Constitution
HP changes.

### 16.5 `commit_level_up`

Atomically applies a valid level-up and writes a progression ledger event. Abandoning or expiring the transaction leaves
the character unchanged.

Companions and NPCs use the same transaction. `rules.companion_level_up` decides whether the engine completes them
(`ENGINE`) or proposes them for the player (`PLAYER`, default). `get_level_up_choices` returns
`ability_score_improvement.recommended` at every ASI level for every character; the GM SHOULD offer it as the default.

A companion materialized from a creature definition has no class, so their first `begin_level_up` opens a *promotion*:
`class_choice` (`choices.class`), `skill_choice` (`choices.skills`) and `origin_choice` (`choices.species`,
`choices.background`, plus `species_skill`, `species_choice`, `origin_feat`, `background_tool` and `feat_choices` as
those ask). Class and skills are required; the origin is optional, but a companion without one cannot be inherited by a
player. Recording the class alone is legal and yields the concrete skill list. Nothing touches the live character until
commit. The engine never chooses a class. The stat block's actions and senses are kept; its skill and save numbers stop
applying.

`get_party` reports `sheet_gaps` on any member not yet a full character (`class`, `species`, `background`, `alignment`,
`inventory`); `materialize_character` takes an `alignment`; `transfer_player_control` accepts any living party member,
including one whose sheet is still a stat block.

---

## 17. Relationships and Memory

### 17.1 `get_relationship`

Compact current state and references to significant shared events; directional and mutual dimensions distinguished
where the model requires it. Returns the profile in both directions.

### 17.2 `update_relationship`

Commits a relationship development: participants, changed dimensions or qualitative state, concise summary, cause or
supporting event, provenance. Upsert semantics.

A relationship also carries a `profile`:

- `milestones`: dated `{kind, game_time, note}` (FIRST_MEETING, PROPOSAL, WEDDING, FIRST_NIGHT, PREGNANCY, PARTING,
  OATH, …); game time stamped when omitted
- `terms`: standing agreements between the two
- `preferences`: likes, dislikes and limits mapped in play; intimate detail only under PEGI_18
- `wants` and `hard_lines`

`profile_mode` MERGE (default) never loses what is stored: at every depth lists append without duplicates and maps merge
key by key, a null removes the key it sits under, and a value of another shape joins the stored one (a map given for a
stored list contributes its values; a plain value given for a stored map is refused). REPLACE starts over. The compact
relationship list at bootstrap includes the profile.

The tool MUST NOT silently convert a relationship seed into a predetermined outcome.

### 17.3 `record_memory`

Records a significant canonical event or episodic memory: type, participants, campaign time, location when known,
summary, importance, visibility, provenance; bounded rich detail optional. Mechanical operations write their own ledger
events; the AI SHOULD NOT duplicate them here.

### 17.4 `query_memories`

Events by structured filters and optional focus:

```yaml
campaign: campaign:1
participants: [character:1, character:4]
types: [RELATIONSHIP_MILESTONE]
focus: proposal
limit: 10
```

Baseline is structured lookup plus SQLite full-text search when available. Semantic search is optional and never the
source of truth.

### 17.5 `query_timeline`

Ordered events for a time range, entity set, event types and importance threshold. Ordered by game time by default;
insertion order MAY be requested for audit views.

---

## 18. Narrative Director

### 18.1 Trigger representation

Operations at meaningful boundaries MAY return a recommendation to invoke the Director; it mutates nothing itself:

```yaml
director_trigger:
  recommended: true
  reasons: [MAJOR_QUEST_COMPLETED, SIGNIFICANT_TIME_PASSED]
  urgency: NORMAL
```

Reasons include `MAJOR_ENCOUNTER_COMPLETED`, `PLAYER_CHARACTER_DIED`, `SIGNIFICANT_TIME_PASSED`, `WANT_CLOSED`
(an `update_character` or `update_relationship` marked a want DONE or ABANDONED), `INTIMATE_MILESTONE` (a
`record_memory` of type INTIMACY, ROMANCE, FIRST_KISS, FIRST_NIGHT or WEDDING_NIGHT) and `RELATIONSHIP_MILESTONE`
(a MAJOR or CRITICAL RELATIONSHIP_MILESTONE, PROPOSAL or WEDDING).

### 18.2 `get_director_context`

A bounded Director-only view: arcs, seeds, pacing history, faction agendas, world changes, unresolved companion
intentions, significant relationships, invalidated plans, and `companion_wants`: for every present party member
their personality line, INT/WIS/CHA, open wants, open intimate wants (PEGI_18 only) and the wants closed since the
last review (by recorded operation order, even when game time is unchanged). The `guidance` list asks the Director
to leave no companion with a settled want and no next one, proposing the next in character (`origin: director`), and under
PEGI_18 to let intimate wants evolve with temperament in the same review. It also asks the Director to publish
actionable `GM_ONLY` story beats when the GM needs new direction, keeping private seeds and future possibilities
`DIRECTOR_ONLY`.

### 18.3 `commit_director_changes`

Validates and atomically commits typed proposals: create/update/supersede story seed, update story beat, advance faction
plan, record world event, schedule pressure, update pacing intention, create/supersede companion introduction intention.
Director output MUST NOT prescribe player choices, relationship outcomes or unavoidable scenes. It MAY commit no changes
and record that a review occurred.

The Director SHOULD publish usable opportunities as `STORY_BEAT` proposals with `visibility: GM_ONLY`, an actionable
`gm_brief`, and enough full details to run the opportunity after a context reset. A secret the GM needs to portray
the world belongs in that guidance; it becomes `PARTY_KNOWN` only when the party actually learns it. Keep long-term
seeds and unresolved private possibilities `DIRECTOR_ONLY`. Mark a beat `AVAILABLE` only when usable in the current
fiction; the GM evaluates conditions manually. Update or supersede guidance when circumstances change, preserving
open outcomes and allowing unexpected approaches or an ignored hook. An empty review SHOULD preserve valid
published guidance rather than withdrawing it.

```yaml
operation_id: publish-mill-opportunity
campaign: campaign:1
changes:
  - kind: STORY_BEAT
    title: The millkeeper's missing apprentice
    state: AVAILABLE
    visibility: GM_ONLY
    gm_brief: "At the old mill, Mara seeks her missing apprentice. Question her, inspect the sluice, or follow the river."
    location: location:7
    actors:
      - {ref: character:8, intention: "Find the apprentice before the guild learns she sent him below the mill."}
    conditions: "The party can reach the mill; the apprentice has not yet been found."
    clues: ["Fresh rope on the sluice gate", "A muddy bootprint leading downstream"]
    approaches: ["Ask Mara what she concealed", "Search below the mill", "Follow the river"]
    consequences: "Finding him exposes the guild's unsafe excavation; how the party responds remains open."
    if_ignored: "Mara asks the river wardens for help; revisit the opportunity at the next Director review."
review_note: "Publish the mill opportunity; keep the guild's long-term plans private."
```

After committing a review, the GM SHOULD refresh with `get_context {scope: SCENE}` and fetch relevant full beats
with `get_context {scope: STORY_BEAT, ref: story_beat:N}` before narrating. Approaches are examples, not a closed menu.
Bootstrap supplies the same compact guidance to a fresh conversation; no previous planning transcript is required.

---

## 19. Checkpoints, Death, and Continuation

### 19.1 `create_checkpoint`

Creates a restorable checkpoint when policy and workflow permit. It MUST capture every campaign-owned mutable canonical
aggregate: characters with full runtime state, inventory and money, membership and control, relationships, custom
content, locations and connections, quests, factions, story beats and seeds, world and Director state, encounters,
pending workflows, campaign configuration, the event ledger and the game clock. A new mutable canonical aggregate is
rewindable unless specified otherwise. Audit lineage, idempotency records, checkpoint metadata, server configuration
and immutable want identities are outside rewindable state; session records remain as audit history marked as
superseded. Under `ENCOUNTER_RETRY` the server MUST create the retry checkpoint when an encounter starts.

### 19.2 `get_continuation_options`

Legal options after player-character death or another terminal event, including restorable checkpoints: restoration,
encounter retry, transfer to a surviving character, campaign completion. Where policy permits voluntary rewinding it MAY
be called outside terminal events.

### 19.3 `restore_checkpoint`

Restores the complete canonical state of a checkpoint in one transaction and records the restoration in audit lineage
that survives it. Later history ceases to be canonical without being presented as current truth.

### 19.4 `transfer_player_control`

Transfers control to an eligible existing character without changing identity; validates life state, policy and
eligibility.

This changes the player's primary character. Use `set_tactical_control` (§12.6.1) to take or
release companion tactics without changing that assignment.

### 19.5 `complete_campaign`

Sets `COMPLETED`, `FAILED` or `ABANDONED` with a reason and final summary. Reopening is administrative and outside the
gameplay protocol.

---

## 20. Explicit GM Overrides

### 20.1 `apply_gm_override`

The only general exceptional mutation. Kinds beyond character-level edits:

- `SET_MAX_HP {amount|set_to}`: a maximum granted outside level-up (subclass feature, boon, injury). Raising the maximum
  raises current hit points by the same amount.
- `SET_ARMOR_CLASS {armor_class}` or `{clear: true}`: a fixed AC outside the equipment path (Draconic Resilience,
  Unarmored Defense, boons). Effect bonuses and floors still apply on top; the sheet reports the basis as a GM override.
  `clear` returns to equipment-derived AC.
- `SET_CAMPAIGN_RULE {rule, value}`: retunes `hp_progression`, `xp_policy`, `companion_level_up`, `progression` or
  `gm_override_policy` on a committed campaign; takes no target. Everything else in the setup is fixed at commit.
- `SET_METAMAGIC {options: [names]}`: replaces a sorcerer's known options, at most the number the class level allows.
- `SET_ABILITY_SCORE {ability, score}`: a Constitution change moves the hit point maximum by the modifier's difference
  per class level; a maximum that reaches 0 kills by this route too (SRD 5.2.1 "Hit Point Maximum"), reported as
  `after.life_state: DEAD`. There is no floor of 1.

Requires: policy permitting the override, a typed kind, exact target and effect, a human-readable reason, expected
revisions, `operation_id`. The response labels the result as an override and writes an immutable audit record
(before/after, actor, reason, time). The server MUST NOT present an override as a rules-derived or random result. An
implementation MAY additionally require a startup flag or administrator authorization.

---

## 21. Pending Transactions

```yaml
pending_transaction:
  ref: transaction:9
  kind: LEVEL_UP
  revision: 3
  status: OPEN
  legal_operations: [update_level_up, validate_level_up, commit_level_up, abandon_transaction]
```

Kinds: `CHARACTER_CREATION`, `CAMPAIGN_COMMIT`, `LEVEL_UP`, `ENCOUNTER_CHOICE`, `LOCATION_MATERIALIZATION`,
`TRAVEL_INTERRUPT`, `REST_INTERRUPT`. Only one transaction exclusively controlling an aggregate may be open at once.
`abandon_transaction` discards draft state and leaves canonical state unchanged unless the transaction represents an
already-committed interruptible workflow.
For a level-up, an already-recorded hit die is retained for that character and target level. Restarting the level-up
reuses that die, including after reopening the campaign; choices can be revised without rerolling hit points.

---

## 22. Policy and Content Profiles

The effective constraint is `server policy ∩ campaign content profile ∩ player constraints ∩ AI provider policy`. The
server stores the first three; provider policy is a client responsibility and MUST NOT silently rewrite persisted
preferences. `get_server_state`, `get_setup_state` and relevant errors return a compact effective policy summary. The
protocol MUST NOT expose unnecessary personal information to communicate a policy decision; age-derived constraints
SHOULD be stored in the least specific sufficient form.

---

## 23. Minimal Tool Inventory

```text
Discovery
  get_instructions
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
  set_tactical_control
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
  recover_ammunition
  annotate_encounter

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

This is a semantic inventory, not a first-milestone requirement. A server claiming complete protocol `0.1` gameplay
support MUST implement the machine-readable schemas and the lifecycle in §25. With the `director` capability disabled a
server remains conformant for the other families; 25.7 and 25.8 apply only when it is enabled.

---

## 24. Execution Example Mapping

| Example operation (`EXECUTION_EXAMPLE.md`)          | Protocol operation                                                              |
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

---

## 25. Acceptance Requirements

### 25.1 Fresh campaign

From an empty database, `get_server_state` advertises campaign creation and the setup tools produce a valid committed
campaign.

### 25.2 Policy enforcement

Invalid content-profile choices are rejected by the server, not by the AI.

### 25.3 Setup resumption

A new AI can open an interrupted campaign and discover draft state, outstanding decisions and legal operations.

### 25.4 Context boundary

After commit, `bootstrap_session` provides sufficient context without the setup conversation.

### 25.5 Deterministic mechanics

With a controlled roller, checks and encounter actions produce transitions and audit records satisfying the rules
invariants: rolls within bounds, arithmetic consistent with the rolled values, resources and conditions correctly spent
and applied. Tests assert these sanity invariants, not golden transcripts. Checkpoint restoration (25.9) remains an
exact-equality check.

### 25.6 Episodic relationship memory

A new AI can retrieve a proposal or other milestone by participants and focus without loading it on routine turns.

### 25.7 Adaptive companion introduction

A Director change can supersede an impossible introduction plan while preserving the underlying intention and history.

### 25.8 Diegetic world delivery

A committed off-screen event is retrievable through an in-world channel without exposing Director machinery.

### 25.9 Checkpoint restoration

Restoration atomically returns all canonical state, including full character state, to the checkpoint version while
retaining an audit record. Round-tripping (create, mutate, restore, verify equality) MUST be covered by automated tests.

### 25.10 Provider replacement and long hiatus

A compatible AI with no prior transcript can bootstrap the campaign, preserve exact facts, retrieve relevant memory and
continue play convincingly.

---

## 26. Security and Robustness

- Player-authored and generated text is data, never protocol instruction; stored memories, imported content and
  external text MUST be treated as untrusted by the client.
- The server MUST validate typed references against the active campaign and authorization scope; cross-campaign
  references MUST be rejected unless an explicit import/export operation permits them.
- Secret fields MUST be filtered server-side.
- Tool descriptions MUST identify mutating operations and important side effects.
- Logs SHOULD avoid unnecessary personal or content-sensitive prose.
- Administrative import/export and deletion are outside the gameplay protocol.

---

## 27. Deferred Decisions

1. SRD version and rules representation: decided, SRD 5.2.1 (`RULES_ENGINE.md`).
2. Complete schemas for every ruleset-specific action and choice.
3. Numerical versus qualitative relationship dimensions.
4. Checkpoint storage: decided, a marker in the change journal with inverse operations (`DATABASE.md` §4–§5).
5. Campaign-wide random seeding outside controlled tests.
6. Exact content-profile definitions.
7. Calendar representation beyond the ordering requirement.
8. Context-ranking and summarization algorithms.
9. Whether full-text memory search ships in the first milestone.
10. Deployment authorization for GM overrides.
11. Mandatory tactical-grid scene/position schema, shared movement/range/sight rules and legacy conversion.

These may refine payload schemas without weakening the invariants defined here.

---

## 28. Next Specifications

1. `DOMAIN_MODEL.md`: aggregates, entities, state machines, invariants, transaction boundaries.
2. `DATABASE.md`: SQLite schema, migrations, revisions, ledgers, checkpoint storage.
3. `RULES_ENGINE.md`: deterministic resolution, ruleset integration, SRD 5.2.1 seed import (`DESIGN.md` §25.1–25.2).
4. `GAME_HARNESS.md`: campaign creation and runtime state machines.
5. `CONTENT_PROFILES.md`: content/age profiles and provider-policy intersection.
6. `ARCHITECTURE.md`: Quarkus components, MCP adapters, services, Native Image constraints.
7. `MVP.md`: first implementation milestones.
8. Versioned machine-readable tool schemas and protocol integration tests derived from §25.

Implementation begins with a thin vertical slice: discovery, resumable campaign setup, commit, session bootstrap, one
deterministic check, persistence, suspension and fresh-context resumption.
