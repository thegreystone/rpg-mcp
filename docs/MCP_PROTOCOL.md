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
`rpg://protocol/capabilities` reports protocol version, optional capabilities, limits and supported features.
`rpg://rulesets` lists installed rulesets and versions without their rules data.

Campaign state MUST NOT be exposed as a static resource; tools keep freshness, visibility and authorization explicit.

### 3.3 Prompts

Optional. Clients MUST be able to operate from the guide resource and tool schemas alone.

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

Clients MUST ignore unknown response fields and MUST NOT assume they understand unknown enum values.

---

## 5. Common Data Conventions

### 5.1 Typed references

Persisted entities use numeric primary keys internally and typed references `<entity-type>:<positive-integer>` at the
boundary: `campaign:1`, `character:4`, `location:7`, `event:42`, `encounter:3`, `checkpoint:2`, `content:5`.
References are opaque; clients MUST NOT infer ordering, ownership or mutability from the number.

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

### 5.6 Revisions

Mutable aggregates expose an integer `revision`. A mutation derived from read state MUST include `expected_revision`;
commands against a pending transaction MUST include its revision. A schema MAY omit `expected_revision` only for a blind,
commutative operation or when the target is created in the same call. A stale revision returns `CONFLICT`.

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

### 8.1 `get_server_state`

The first call of a newly connected AI.

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

### 8.2 Legal operations

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
    optional: false                   # may be skipped; then `default` applies
    note: "..."                       # advisory guidance for the GM
```

Options MAY carry extra fields (`speed`, `hit_die`, `level`, `school`, `ability`, `contents`, …). A decision without
options (a name, an age) has `allow_custom: true`.

- Legal values, labels and descriptions of a closed choice live in exactly one place in the server (an enum or the
  installed content). Tool descriptions MUST NOT enumerate them.
- The list shrinks as answers are recorded and never lists an answered decision. When nothing is outstanding it holds one
  review decision (`character_review`, `campaign_review`) whose options are commit or revise.
- The GM SHOULD ask exactly one decision per turn, the first in the list, presenting every option numbered with its
  description plus a custom answer where `allow_custom` is true, and record the answer before reading the list again.
  Question and option texts are advisory, not required prompts.

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

Creates or resumes a session (`operation_id`, `campaign`, `context_budget`) and returns a bounded package:

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
- applicable story seeds with visibility labels
- pending encounter, transaction or continuation decision; legal next operations
- `budget`: tokens asked for, the character target (three characters a token) and characters used

Default budget 16,000 tokens, at most `maximum_context_budget`. The story section gets a third; relationship profiles and
full inventories stay out; the digest drops its least important lines first. The server MUST prioritize current
canonical state over historical color.

### 11.2 `get_context`

Purpose-built context for one scope, with references, optional natural-language focus and a budget. Visibility and
scope rules always apply.

- `SCENE`
- `CHARACTER`: FULL sheet with biography and (PEGI_18) intimate profile, relationships with profiles, membership
  history, recent events, location
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
  event since the last chapter, with detail, paged at 40,000 characters by `next_cursor`), `events_uncovered`, `voice`
  (tone and style guidance), `target` (characters, ceiling, what a chapter contains), `through.journal_id`
- `SYNOPSIS`: `current_synopsis`, `chapters_since`, `voice`, `target`, `through.chapter_id`

`write_chronicle {operation_id, campaign, kind, title, summary, through}` stores the entry. A chapter closes at
`through` (now when omitted); later events stay uncovered for the next chapter. A stale or future marker is `CONFLICT`,
an entry over the ceiling `INVALID_ARGUMENT`, a chapter with nothing to cover `OPERATION_NOT_ALLOWED`. A synopsis closes
at a chapter id and supersedes the previous one. Each write is a `CHRONICLE_WRITTEN` ledger event (MINOR, GM_ONLY) and
returns the new `due` state.

### 11.7 `find`

`find {campaign, kind, query, limit}` (read-only) looks up characters (`CHARACTER`, default) or places (`LOCATION`) by
name: case-insensitive, exact name first, then prefix, then substring, then a mention in the description. Each hit is
one line: ref, name, and for people membership state, life state and whereabouts; for places kind, materialization,
parent and head count. Use it whenever a name is known and the ref is not; never guess a ref.

---

## 12. Canonical Narrative State

### 12.1 `upsert_narrative_state`

Creates or updates one narrative aggregate by discriminated type: `QUEST`, `STORY_BEAT`, `STORY_SEED`,
`FACTION_STATE`, `WORLD_EVENT`, `LOCATION_DETAIL`, `NPC_AGENDA`. Each kind has its own schema, validation, visibility,
lifecycle and transitions. Provenance MUST be recorded: `GM`, `DIRECTOR`, `MECHANICAL_CONSEQUENCE` or
`ADMINISTRATIVE_OVERRIDE`.

A `QUEST` whose status becomes `COMPLETED` returns `treasure`: the party's level band, the rarities it draws from, how
often a magic item should turn up, and up to three candidates drawn through the roller (§14.4). It is a suggestion; the
GM grants with `grant_loot` or ignores it.

### 12.2 `materialize_location`

Atomically converts a location seed or semantic node into canonical detail: location, connections, known features,
secrets with visibility labels, revision. MUST reject contradictions with committed geography unless performed through
an explicit override.

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
Separated members never move. With `characters`, exactly those travel, and the campaign's current location follows the
player character. Every party view reports each member's `location` and `with_party`.

The destination must be reachable via known connections or an authorized route. Travel advances atomically until
arrival or the first consequence needing input. On arrival it commits elapsed time, locations, automatic consequences and
the movement event. On interruption it commits only elapsed time and movement to the intermediate location, creates a
`TRAVEL_INTERRUPT` pending transaction and returns the interruption context; it MUST NOT assume the rest of the journey.
Moving into an unmaterialized node MAY require `materialize_location` first; the error says so.

**Travel encounters.** `consequences` carries, besides expired timed effects, at most one `TRAVEL_ENCOUNTER_SUGGESTED`
entry (`RULES_ENGINE.md` §9): per four hours of travel one roll through the roller, a 1 on a d6 on wild ground, a 1 on
a d12 on roads, rivers and coasts, never inside settlements or buildings. A hit names a creature from the terrain table
for the tags crossed, a count sized from the SRD 5.2.1 Moderate XP budget for the party's levels, the hour it falls in,
and up to two open Director pressures as hooks. Nothing is written and no encounter starts; the GM plays it with
`materialize_character` and `start_encounter`, folds it into a pressure, or ignores it. `director_trigger.reasons`
carries `TRAVEL_ENCOUNTER_SUGGESTED` when present.

### 12.6 `update_party_membership`

One typed change: `JOIN`, `LEAVE`, `DISMISS`, `SEPARATE`, `REJOIN`, `GUEST_ADD`, `GUEST_REMOVE`. Validated against
current membership state, recorded with its cause in the ledger, returns the updated party. Player control is separate
(`transfer_player_control`).

### 12.7 `update_character`

Canonical narrative and identity changes to a committed character: name, appearance, personality, goals, backstory,
age, presentation, alignment, and two merged aggregates:

- `biography`: `timeline` [{game_time, note}] (clock stamped when omitted), `voice` [verbatim lines], `state`
  [{note, since, until}] (long-running bodily state; no `until` means current, shown in every party list), `marks`,
  and `wants` [{note, with: [character refs], since, status OPEN|DONE|ABANDONED}] (the character's drives; open wants
  show in every party list at every detail level).
- `intimacy`: PEGI_18 only (`POLICY_DENIED` otherwise, never returned below PEGI_18): `body`, `likes`, `dislikes`,
  `limits`, `hard_lines`, `wants` [{note, with, since, status}], `household_terms` [{note, with}] (held by the person
  who set them), `voice_in_bed`. This is the character's own truth; what each partner has learned stays on the pairwise
  relationship profile.

Both merge like relationship profiles (§17.2): lists append without duplicates, an entry with the same `note` replaces
the older one (mark a want DONE, give a state its `until`), a null removes the key it sits under, `{replace: true}`
starts over. The GM SHOULD record facts as they are established in play. The older `goals` list remains for
compatibility; `wants` is the dated, closable form the views show.

Identity is unaffected: renaming changes only the display name. The operation records provenance and requires
`expected_revision`. Mechanical state uses `apply_runtime_change` or a more specific tool; exceptions use
`apply_gm_override`.

---

## 13. Rules and Character Runtime

### 13.1 `get_character_sheet`

Identity, rules features, resources, equipment, conditions, progression and carrying state at detail `SUMMARY`, `PLAY`
or `FULL`.

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
Together"). The result reports `tool`, `tool_proficient` and `advantage_source` when the engine granted or cancelled it.

Legal in `EXPLORATION` and `ENCOUNTER`; it never advances the initiative order.

### 13.3 `apply_runtime_change`

A named, rules-aware non-encounter change (healing, resources, conditions, HP) when no more specific tool exists. Each
kind has a schema; exceptional changes need `apply_gm_override`.

- `USE_ITEM {item, target}`: a consumable with an encoded effect, used outside combat. A Potion of Healing of any
  potency heals by its dice (rolled and journaled, returned as `roll`) and one unit is consumed; an item without an
  encoded effect is `CAPABILITY_UNAVAILABLE` and the GM narrates it.
- `DAMAGE {amount | dice}`: a `dice` expression (`"2d6"`) is rolled and journaled by the server and returned as `roll`.
- `USE_RESOURCE` / `RESTORE_RESOURCE {resource, amount}`: the tracked uses in the sheet's `resources` block (Breath
  Weapon, Heroic Inspiration, `sorcery_points`, `innate_sorcery`).
- `CREATE_SPELL_SLOT {slot_level}` / `CONVERT_SPELL_SLOT {slot_level}`: Font of Magic conversions (points into a slot at
  the table's cost; a slot into points equal to its level), Bonus Actions in the fiction; the result carries the new
  `sorcery_points` and `slot` counts.
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

- `ITEM`: name, type, cost, weight, description, mechanical properties, tags.
- `BACKGROUND`: SRD 5.2.1 shape: `properties.ability_scores` (three abilities), `properties.feat` (an Origin feat),
  `properties.skills` (two), `properties.tool` (`{item}` or `{choice: ARTISANS_TOOLS|GAMING_SET|MUSICAL_INSTRUMENT|TOOL}`),
  optional `feat_choices` presets and `starting_equipment` options (default fifty gold). References are validated
  against installed content; the name must not collide with an installed or campaign background. Usable by name,
  symbolic id or `content:` reference wherever a background is chosen (`create_character_draft` /
  `update_character_draft` with `background`, `background_ability_scores`, `background_tool`; a companion's promotion;
  `get_character_choices` scope `BACKGROUND`; `get_content_definitions` kind `BACKGROUND`, flagged `custom: true`).

Definitions are validated against their kind's schema and recorded with provenance and licensing. Defining is not
granting: putting a custom item into play goes through `trade`, `grant_loot` or `transfer_item`.

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

### 13.9 `cast_spell`, `prepare_spells` and Metamagic

`cast_spell {caster, spell, slot_level?, targets?, options?}` shares its casting core with the encounter `CAST` action:
checks preparation and slots, spends the slot, rolls attacks, saves and damage, applies conditions and concentration,
returns rules text for anything unstructured. `options` carries spell-specific choices: `ritual: true`, `damage_type`
(Chromatic Orb), `against` (a buff aimed at one creature), `condition` (which condition a cure removes).

A sorcerer adds `options.metamagic`, one name or a list (`["Empowered Spell", "Quickened Spell"]`), with the extras an
option needs: `heightened_target` (default first target), `careful` (creatures that succeed automatically),
`damage_type` for Transmuted, `sorcery_incarnate: true` while Innate Sorcery is active at level 7 or above. Options must
be known (`spellcasting.metamagic.known`), applicable and paid in sorcery points before anything is rolled; the result's
`metamagic` block reports what was used, its cost and the points left. Seeking spends its point only when a missed attack
roll is rerolled.

`options.weapon` (a name or `inventory:N`) names the carried weapon a weapon-enchanting spell binds to (Magic Weapon,
Shillelagh; default the wielder's equipped weapon): its bonus, die and damage ride only on attacks with that weapon and
stack with the weapon's own enchantment; the slot raises Magic Weapon to +2 (level 3–5) or +3 (6+). A magical weapon is
refused (`NONMAGICAL`), a wrong kind (`WEAPON_KIND`); each target in the result names the `weapon`. Flame Blade conjures
a weapon: `ATTACK` with `attack: "Flame Blade"` while it lasts (RULES_ENGINE.md §10).

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

### 14.3 `trade`

Purchase, sale or barter, atomically. Price comes from canonical merchant, market and rules state unless an authorized
negotiated price is supplied with provenance. Money and items commit together.

### 14.4 `grant_loot`

Materializes and transfers rewards from an authorized encounter, quest, world source or explicit GM grant, subject to
campaign GM policy and audit.

Magic items are granted by name (`{item: "Ring of Protection"}`). A magic weapon, armor, shield or ammunition entry is a
template made on a base item and is instantiated at grant time as campaign content: `{item: "+1 Longsword"}`,
`{item: "Weapon, +1, +2, or +3", base: "Longsword", bonus: 2}`, or `{item: "Flame Tongue", base: "Longsword", name:
"Ember"}`. The result names the definition (`content:N`) and its rarity; a template without a base, a base of the wrong
kind, or a base that is itself magical is refused. One definition serves every grant of the same name.

An enchantment the SRD does not list (an Arrow of Fire, a frost blade, a lucky charm) is made on a mundane base with
`magic`: `{item: "Arrow", quantity: 10, magic: {name, rarity, text, bonus?, damage_bonus_dice? + damage_type?, ac_bonus?,
save_bonus?, attack_bonus?, speed_bonus?, resistance?, attunement?, slot?, consumable?: {heal}}}`. `name` and `text` are
required; the engine applies the +N, the extra damage dice on hits with that weapon or piece of ammunition, and the worn
modifiers while equipped; the text is the GM's (`text_is_paraphrase: true`). Priced base + rarity value; one definition
per name.

**Treasure.** A completed quest (§12.1), a major encounter (§15.5) and a bootstrap that finds a party of level 3, or
with two completed quests, without a single magic item return `treasure` (or a `meta.warnings` line): `party_level`,
`tier`, `rarities` (weights), `frequency`, `party_magic_items` and `suggestions` drawn through the roller from the
installed magic items (`ref`, `name`, `rarity`, `attunement`, `needs_base` for a template, `summary`). The GM SHOULD
grant something at those moments at about the stated rate; see RULES_ENGINE.md §10 for the bands.

### 14.5 `give_money`

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
first actionable turn. Positioning is zone-level by default (near/far, cover, marked features). With the optional
`tactical_grid` capability an encounter MAY carry x,y positions used for movement and range validation; the grid schema
is deferred.

### 15.2 `get_encounter_state`

Round, turn, participants, sides, visible conditions, positions where a spatial model is active, available actions or
constraints, and revision. Secret opponent information is omitted or marked by visibility.

### 15.3 `perform_encounter_action`

Resolves one action atomically. Kinds: `ATTACK`, `CAST`, `MOVE`, `DASH`, `DISENGAGE`, `DODGE`, `HELP`, `HIDE`, `READY`,
`USE_ITEM`, `INTERACT`, `OTHER_RULES_ACTION`.

Returns the validated interpretation, rolls and modifiers, resource expenditure, damage, healing, conditions, movement
and other consequences, defeated/dying changes, triggered reactions or pending choices, the next turn or required
follow-up, and the new revision. An action needing a player or GM choice creates a typed pending transaction rather
than guessing.

`CAST` takes the `cast_spell` fields flattened into the action (`spell`, `targets` or `target`, `slot_level`,
`metamagic`, `heightened_target`, `careful`, `damage_type`, …; §13.9).

`ATTACK` may name `ammunition` ("+1 Arrow") to fire magic ammunition made on the weapon's own kind; its +N applies to
that attack and damage roll and the result's `ammunition` reports the piece, its `bonus` and what remains. `USE_ITEM
{item, target}` uses a carried consumable: a Potion of Healing of any potency heals by its dice; an item without an
encoded effect is consumed and left to the GM.

### 15.4 `resolve_pending_choice`

Completes a server-created pending choice by transaction reference and one of the legal options returned.

### 15.5 `end_encounter`

Validates the outcome, commits completion, distributes mechanically defined rewards, updates quests and events as
configured, returns level-up eligibility and Director-trigger recommendations. Cannot end while mandatory pending
reactions or choices remain. A major encounter (an XP pool of 200 or more, or a player character's death) also returns
`treasure` (§14.4).

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

Violations and a complete before/after preview.

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

### 18.2 `get_director_context`

A bounded Director-only view: arcs, seeds, pacing history, faction agendas, world changes, unresolved companion
intentions, significant relationships, invalidated plans.

### 18.3 `commit_director_changes`

Validates and atomically commits typed proposals: create/update/supersede story seed, update story beat, advance faction
plan, record world event, schedule pressure, update pacing intention, create/supersede companion introduction intention.
Director output MUST NOT prescribe player choices, relationship outcomes or unavoidable scenes. It MAY commit no changes
and record that a review occurred.

---

## 19. Checkpoints, Death, and Continuation

### 19.1 `create_checkpoint`

Creates a restorable checkpoint when policy and workflow permit. It MUST capture every campaign-owned mutable canonical
aggregate: characters with full runtime state, inventory and money, membership and control, relationships, custom
content, locations and connections, quests, factions, story beats and seeds, world and Director state, encounters,
pending workflows, campaign configuration, the event ledger and the game clock. A new mutable canonical aggregate is
rewindable unless specified otherwise. Audit lineage, idempotency records, checkpoint metadata and server configuration
are outside rewindable state; session records remain as audit history marked as superseded. Under `ENCOUNTER_RETRY` the
server MUST create the retry checkpoint when an encounter starts.

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
11. Tactical-grid representation (zones vs. x,y grid, movement costs, ranges).

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
