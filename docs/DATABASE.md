# RPG MCP Server — Database

**Status:** Initial database design
**Purpose:** Define the SQLite physical model: storage decisions, schema conventions, tables, the change-journal undo
representation, checkpoint rollback, migrations, and integrity enforcement.
**Scope:** Implements `DOMAIN_MODEL.md`. Per-kind content JSON schemas and derived-value computation belong to
`RULES_ENGINE.md`.

Invariant references (`I-nn`) point into `DOMAIN_MODEL.md`.

---

## 1. Storage Decisions

- **One SQLite database file** per server installation (`rpg.db` in the configured data directory). Campaigns are rows,
  not files. Installed ruleset content is shared across campaigns in the same file; campaign isolation is by
  `campaign_id` scoping and server-side validation (cross-campaign references are rejected at the protocol layer).
- **Journal-based checkpoints** (per `DESIGN.md` §29): every canonical mutation appends to the change journal; a
  checkpoint is a marker; restore rolls back by insertion ID (I-48…I-52).
- **Single-writer execution.** One tool invocation is one database transaction (I-69). Mutations are serialized by the
  application; SQLite's writer lock is not the concurrency model, just a backstop.
- **SQLite runtime settings:**

```text
PRAGMA journal_mode = WAL;
PRAGMA foreign_keys = ON;
PRAGMA synchronous = NORMAL;
PRAGMA busy_timeout = 5000;
```

WAL gives readers-don't-block-writers and fast commits; `foreign_keys` must be set per connection.

- **Seed data** is imported from JSON classpath resources embedded in the native executable, on startup when
  `installed_ruleset` lacks the ruleset+version (I-66). The importer runs inside one transaction per ruleset version.

---

## 2. Schema Conventions

- Table and column names are `snake_case`, table names singular (`character`, not `characters`), matching the
  documentation convention `character.id = 1`.
- Every entity table: `id INTEGER PRIMARY KEY AUTOINCREMENT`. `AUTOINCREMENT` is mandatory on every table whose IDs are
  protocol-visible or journal-referenced — IDs are never reused after deletion (I-1, I-45, I-49). This applies to *all*
  domain tables for uniformity.
- Campaign-scoped tables carry `campaign_id INTEGER NOT NULL REFERENCES campaign(id)` with an index. All queries filter
  by it.
- Mutable aggregate roots carry `revision INTEGER NOT NULL DEFAULT 0` (§2.5 of the domain model).
- Enums are stored as `TEXT` with `CHECK (col IN (...))` constraints — readable in a debugger, cheap in SQLite.
- Real timestamps: `TEXT` RFC 3339 UTC (`created_at`, `updated_at` where useful).
- Game time is stored as the pair `game_time TEXT` (calendar instant, display-oriented) + `game_seq INTEGER` (monotonic
  ordering value). Ordering always uses `game_seq`; the instant is presentation (I-42, I-46).
- Flexible payloads are `TEXT` containing JSON (`_json` suffix). SQLite JSON1 functions may be used in queries, but JSON
  payload validity is the application's responsibility. Fields that are filtered or sorted on get real columns;
  everything else may live in the payload.
- Provenance columns:
  `provenance TEXT CHECK (provenance IN ('PLAYER','GM','DIRECTOR','MECHANICAL_CONSEQUENCE','ADMINISTRATIVE_OVERRIDE'))`.
- Visibility columns: `visibility TEXT` with the six values of domain model §17; `CHARACTER_KNOWN`/`FACTION_KNOWN`
  targets live in a companion `_json` list column.

---

## 3. Table Catalog

Grouped as in `DOMAIN_MODEL.md`. Column lists are normative for intent; migrations are the source of truth for exact
DDL.

### 3.1 Infrastructure

**schema_version** — `id`, `version INTEGER UNIQUE`, `applied_at`, `description`, `checksum TEXT` (§7).

**installed_ruleset** — `id`, `namespace TEXT` (e.g. `srd5e`), `version TEXT`, `imported_at`, `license TEXT`,
`attribution TEXT`. Unique `(namespace, version)`. Backs `rpg://rulesets` and the attribution decision (`DESIGN.md`
§23).

**installed_content** — installed definitions, immutable at runtime (I-65):
`id`, `content_id TEXT UNIQUE` (namespaced, e.g. `srd5e:item/longsword`), `ruleset_id → installed_ruleset`,
`kind TEXT` (
`ITEM, WEAPON, ARMOR, TOOL, SPELL, CREATURE, CONDITION, CLASS, SUBCLASS, SPECIES, BACKGROUND, FEAT, TABLE, …`),
`name TEXT`, `payload_json`.
Generic filterable columns (nullable, populated per kind): `cost_cp INTEGER`, `weight_g INTEGER`, `spell_level INTEGER`,
`cr_times_8 INTEGER` (CR stored ×8 so ⅛/¼/½ are integers), `xp_value INTEGER`, `tags_json`.

**custom_content** — campaign-owned definitions (`content:N`, I-67):
`id`, `campaign_id`, `kind`, `symbolic_id TEXT NULL` (e.g. `custom:item/bellhaven-broadsheet`), `name`, `payload_json`,
`cost_cp`, `weight_g`, `tags_json`, `license_json NULL`, `provenance`, `revision`, `created_at`. Unique
`(campaign_id, symbolic_id)` where `symbolic_id` is not null (I-5).

Content references elsewhere in the schema are the pair `content_ref_kind TEXT CHECK (IN ('INSTALLED','CUSTOM'))` +
`content_ref TEXT` (the `content_id` string) or `custom_content_id INTEGER` — stored as two nullable columns with a
CHECK that exactly one is set.

### 3.2 Campaign and policy

**campaign** — `id`, `title TEXT NULL` (generated at commit if null), `ruleset_namespace`, `ruleset_version`,
`status TEXT CHECK (IN ('SETUP','READY_TO_PLAY','ACTIVE','SUSPENDED','COMPLETED','FAILED','ABANDONED'))`,
`harness_state TEXT`, `continuation_policy TEXT`, `gm_override_policy_json`, `preferences_json`,
`current_location_id NULL → location`, `active_session_id NULL → session`, `revision`, `created_at`.

**policy_state** — 1:1 with campaign: `id`, `campaign_id UNIQUE`, `content_profile TEXT` (PEGI values),
`player_constraints_json` (least-specific sufficient form, I-58/I-59), `revision`.

**game_clock** — 1:1 with campaign: `id`, `campaign_id UNIQUE`, `calendar_json`, `instant TEXT`,
`seq INTEGER NOT NULL` (I-42).

**campaign_setup_draft** — `id`, `campaign_id UNIQUE`, `payload_json` (all wizard dimensions incl. explicit
`SURPRISE_ME` markers and proposed control assignment), `status TEXT CHECK (IN ('OPEN','CONSUMED','ABANDONED'))`,
`revision`. (I-9: consumed drafts are retained but nothing canonical references them; retention policy may prune
payload.)

### 3.3 Character

**character** — one row per creature (domain model §2.2, §5):
`id`, `campaign_id`, `lifecycle TEXT CHECK (IN ('DRAFT','FINALIZED_DRAFT','ACTIVE','ARCHIVED'))`,
`life_state TEXT CHECK (IN ('ALIVE','DYING','DEAD'))`,
narrative: `name`, `description`, `appearance`, `personality`, `backstory`, `goals_json`, `alignment`, `age`,
`presentation`,
rules identity: `species_ref`, `background_ref` (SRD background), `str/dex/con/int_/wis/cha INTEGER` (final scores; the
pre-background base lives in `creation_json.base_scores`), `max_hp INTEGER`, `armor_class_override INTEGER` (V006: a fixed AC recorded by `apply_gm_override` `SET_ARMOR_CLASS`;
NULL derives AC from equipment), `speed INTEGER`, `senses_json`,
`origin_content_ref` columns (creature definition it was materialized from, nullable) + `origin_seed_id NULL`,
runtime: `current_hp INTEGER`, `temp_hp INTEGER DEFAULT 0`, `exhaustion INTEGER DEFAULT 0`,
`money_cp INTEGER NOT NULL DEFAULT 0 CHECK (money_cp >= 0)` (I-14), `location_id NULL → location`,
`encounter_id NULL → encounter` (I-15),
`revision`, `created_at`.
CHECK `current_hp BETWEEN 0 AND max_hp` (I-13).

Staged (pre-`ACTIVE`) rules state, inventory, and money live in the same columns/tables as active state; gameplay
eligibility is gated on `lifecycle = 'ACTIVE'` in every query (I-6). No separate staging tables.

**character_class** — `id`, `character_id`, `class_ref`, `subclass_ref NULL`, `level INTEGER CHECK (level >= 1)`. Unique
`(character_id, class_ref)`.

**character_trait** — generic rules linkage: `id`, `character_id`, `kind TEXT` (
`PROFICIENCY, SKILL, SAVE, LANGUAGE, SPELL_KNOWN, SPELL_PREPARED, FEAT, FEATURE, MASTERY`), content ref columns,
`payload_json NULL`. Unique `(character_id, kind, content_ref)`.

**character_xp** — folded into `character` as `xp INTEGER NOT NULL DEFAULT 0 CHECK (xp >= 0)`.

**player_control_assignment** — `id`, `campaign_id`, `seat TEXT NOT NULL DEFAULT 'player-1'`,
`character_id → character`, `since_journal_id`, `active INTEGER CHECK (IN (0,1))`. At most one active row per
`(campaign_id, seat)` (partial unique index). History rows record former control (I-10).

### 3.4 Party, relationships

**party_membership** — `id`, `campaign_id`, `character_id`,
`state TEXT CHECK (IN ('ACTIVE','SEPARATED','GUEST','LEFT','DISMISSED','DEAD','ENDED'))`, `joined_time/joined_seq`,
`left_time/left_seq NULL`, `cause_event_id NULL → event` (I-18), `notes`. Partial unique index: one non-terminal row per
character (I-17).

**relationship** — `id`, `campaign_id`, `from_character_id`, `to_character_id`, `dimensions_json`, `summary TEXT`,
`revision`. Unique `(campaign_id, from_character_id, to_character_id)`.

**relationship_event** — `relationship_id`, `event_id`, PK on the pair (I-21).

### 3.5 Inventory, effects, resources

**inventory_entry** — `id`, `campaign_id`, owner columns (`character_id NULL`, `location_id NULL`,
`container_entry_id NULL` — exactly one set, CHECK), content ref columns (I-24),
`quantity INTEGER CHECK (quantity > 0)` (I-23), `equipped INTEGER`, `slot TEXT NULL`, `charges_json NULL`.

**active_effect** — `id`, `campaign_id`, `character_id`, source columns (source character/content/inline + provenance),
condition/effect content ref or `modifier_json`, `start_seq`, `start_journal_id`, `duration_json`,
`concentration_character_id NULL` (I-29), `stacking_key TEXT NULL`.

**resource_state** — `id`, `character_id`, `resource_ref TEXT` (e.g. `slot:3`, `srd5e:resource/sorcery-points`),
`current INTEGER`, `max INTEGER`, CHECK `0 <= current AND current <= max` (I-30), `recharge TEXT`.

### 3.6 Encounter

**encounter** — `id`, `campaign_id`, `status TEXT CHECK (IN ('CREATED','RUNNING','WAITING_CHOICE','ENDED'))`,
`round INTEGER`, `turn_participant_id NULL`, `sides_json` (side names + inter-side stance), `environment_json`,
`objectives_json`, `spatial_model TEXT CHECK (IN ('ZONES','GRID'))`, `retry_checkpoint_id NULL → checkpoint` (I-35),
`location_id NULL`, `revision`.

**encounter_participant** — `id`, `encounter_id`, `character_id`, `side TEXT`, `initiative INTEGER`,
`initiative_tiebreak INTEGER`, `position_zone TEXT NULL`, `position_x/position_y INTEGER NULL` (grid capability; either
zone or x,y per `spatial_model`), `status TEXT CHECK (IN ('ACTIVE','DEFEATED','FLED','REMOVED'))`. Unique
`(encounter_id, character_id)`.

Turn order is `ORDER BY initiative DESC, initiative_tiebreak` — total by construction (I-31).

### 3.7 Narrative state

Five sibling tables, common columns first: `id`, `campaign_id`, `visibility` (+`visibility_targets_json`), `provenance`,
`revision`, `payload_json`, `created_at`, `game_time/game_seq`.

**quest** — + `title`, `status CHECK (IN ('OFFERED','ACCEPTED','COMPLETED','FAILED','ABANDONED'))`,
`issuer_character_id NULL`, `deadline_seq NULL`.
**faction** — + `name`, `standing_json`, `agenda_json`.
**story_beat** — + `title`, `state CHECK (IN ('PLANNED','AVAILABLE','BLOCKED','SUPERSEDED','COMPLETED','ABANDONED'))`,
`superseded_by_id NULL → story_beat` (I-36).
**director_seed** — + `kind CHECK (IN ('STORY_SEED','COMPANION_INTRO','PRESSURE','PACING_INTENT'))`,
`state CHECK (IN ('OPEN','MATERIALIZED','SUPERSEDED','EXPIRED'))`, `superseded_by_id NULL`,
`materialized_character_id NULL → character`.
**world_event** — + `title`, `channels_json` (diegetic delivery channels, I-38), `affected_refs_json`.

### 3.8 Locations

**location** — `id`, `campaign_id`, `kind CHECK (IN ('REGION','SETTLEMENT','DISTRICT','SITE','BUILDING','AREA'))`,
`parent_id NULL → location`, `name`, `description`, `materialization TEXT CHECK (IN ('SEMANTIC','MATERIALIZED'))` (
I-39), `generation_seed INTEGER NULL`, `features_json` (visibility-labeled entries), `revision`.

**location_connection** — `id`, `campaign_id`, `location_a_id`, `location_b_id`, `kind`, `state_json` (
open/locked/blocked/secret + visibility), `distance_json` (travel constraints/time), CHECK
`location_a_id < location_b_id` (one row per undirected edge).

### 3.9 Sessions and transactions

**session** — `id`, `campaign_id`, `started_at/ended_at`, `start_game_seq/end_game_seq`,
`start_journal_id/end_journal_id`, `summary TEXT NULL`, `events_written INTEGER`.

**pending_transaction** — `id`, `campaign_id`,
`kind CHECK (IN ('CHARACTER_CREATION','CAMPAIGN_COMMIT','LEVEL_UP','ENCOUNTER_CHOICE','LOCATION_MATERIALIZATION','TRAVEL_INTERRUPT','REST_INTERRUPT'))`,
`status CHECK (IN ('OPEN','COMMITTED','ABANDONED','EXPIRED'))`, `controlled_refs_json` (I-55 enforced at open time),
`payload_json`, `expires_at NULL`, `revision`.

### 3.10 The three logs

**event** (semantic ledger) — `id AUTOINCREMENT` (I-45), `campaign_id`, `type TEXT`, `summary TEXT`, `payload_json`,
`importance CHECK (IN ('MINOR','NOTABLE','MAJOR','CRITICAL'))`, `visibility` (+ targets),
`fictional_time/fictional_seq` (may be earlier than recording, I-46), `recorded_time/recorded_seq`,
`recorded_journal_id → journal_entry`, `location_id NULL`, `episodic_detail TEXT NULL`.

**event_actor** — `event_id`, `character_id`, PK pair. Backs participant queries (`query_memories`).

**event_causal** — `event_id`, `caused_by_event_id`, PK pair.

**journal_entry** (change journal) — `id AUTOINCREMENT` (I-49), `campaign_id NULL` (null only for pre-campaign
operations), `operation TEXT` (tool name), `operation_id TEXT`, `provenance`, `recorded_at`, `game_seq`,
`touched_json` (aggregate refs + resulting revisions), `undo_json` (§4), `result_json` (idempotent replay result, I-60),
`is_checkpoint_marker INTEGER DEFAULT 0`.
Unique index `(campaign_id, operation_id)`; unique `(operation_id)` where `campaign_id IS NULL`.

**checkpoint** — `id`, `campaign_id`, `journal_id → journal_entry` (the marker), `reason TEXT`, `game_time/game_seq`,
`created_at`, `status CHECK (IN ('ACTIVE','RESTORED_TO','INVALIDATED'))`. A checkpoint whose marker precedes another
restore's marker may be restored again; checkpoints *after* a restored marker are `INVALIDATED` (their journal tail no
longer exists).

**audit_record** — `id`, `campaign_id NULL`, `kind TEXT` (
`GM_OVERRIDE, DISCRETIONARY_XP, CHECKPOINT_RESTORE, ADMIN_EDIT, POLICY_CHANGE, DISCARDED_BRANCH`), `actor TEXT`,
`provenance`, `reason TEXT`, `before_json/after_json`, `recorded_at`. **Never rolled back** (I-53); no foreign keys into
rewindable tables (references are recorded as plain ref strings so rollback cannot cascade into audit).

---

## 4. Journal Undo Representation

**Decision:** the journal stores **before-images**, not event-sourced forward deltas.

`undo_json` is an ordered list of row-level inverse operations:

```json
[
  {"op": "restore", "table": "character", "pk": 3, "before": { ...full row... }},
  {"op": "delete",  "table": "inventory_entry", "pk": 41},
  {"op": "insert",  "table": "active_effect", "row": { ...full row... }}
]
```

- An application-level `INSERT` journals a `delete` inverse; an `UPDATE` journals `restore` with the full before-row; a
  `DELETE` journals `insert` with the full deleted row.
- Full before-rows (not column diffs): simpler, exactly reversible, and cheap at this scale.
- The data-access layer emits undo entries automatically for every write to a rewindable table — this is how **I-48 (no
  out-of-band writes)** is enforced structurally: the only write API available to service code is the journaling one.
  Raw connection access is confined to the importer (installed content is not rewindable) and the migration runner.

**Rewindable tables:** everything campaign-owned except `audit_record`, `journal_entry` itself, `checkpoint` metadata,
`installed_ruleset`, `installed_content`, and `schema_version` (I-50/I-51 exclusions).

---

## 5. Checkpoint Rollback Algorithm

Restore to checkpoint C (marker journal ID M), in **one transaction**:

1. Validate C is restorable (`ACTIVE`, campaign matches, no open exclusive transaction forbids it).
2. Select all `journal_entry` rows of the campaign with `id > M`, ordered **descending**.
3. Apply each row's `undo_json` in order (reverse chronology ⇒ inverse operations compose correctly).
4. Delete those journal rows and any `event` rows they created (I-52 — recording was journaled, so the undo entries
   already cover `event`/`event_actor` deletions; this step is the consequence, not an extra pass).
5. Mark checkpoints with marker `> M` as `INVALIDATED`; mark C `RESTORED_TO`.
6. Write one `audit_record` of kind `CHECKPOINT_RESTORE` and one of kind `DISCARDED_BRANCH` summarizing the removed
   range (journal ID span, entry count, game-time span) — audit survives (I-53).
7. Append the restoration itself as a new journal entry (not undoable past itself) so the journal remains a complete
   account.

**Tests (I-51):** create checkpoint → run a battery of mutations across every rewindable table → restore → assert exact
equality with a full pre-mutation snapshot dump of rewindable tables (`SELECT *` ordered by pk, compared table by
table). Property-style variants shuffle the mutation battery.

Note on idempotency after restore: rolled-back journal rows take their `operation_id`s with them. Replaying such an
operation re-executes it — correct, because its effects were deliberately undone.

---

## 6. Indexes

Beyond primary keys and stated unique constraints:

```text
character(campaign_id, lifecycle)
inventory_entry(campaign_id, character_id)
party_membership(campaign_id, state)
event(campaign_id, fictional_seq)
event(campaign_id, type, importance)
event_actor(character_id, event_id)
journal_entry(campaign_id, id)
active_effect(character_id)
location(campaign_id, parent_id)
location_connection(campaign_id, location_a_id), (campaign_id, location_b_id)
pending_transaction(campaign_id, status)
custom_content(campaign_id, kind)
installed_content(kind, spell_level), (kind, cr_times_8), (kind, cost_cp)
```

**Full-text search** (optional capability, off in MVP): a contentless FTS5 table over `event.summary` +
`event.episodic_detail`, maintained by triggers, added by a later migration when the capability ships. The domain
model's memory queries work without it.

---

## 7. Migrations

- Plain numbered SQL files (`V001__baseline.sql`, …) embedded as classpath resources and applied by the server's own
  `Migrations` class over plain `sqlite-jdbc` — no Flyway, so nothing has to be taught to work in a native image.
  `schema_version` above is its history table.
- Migrations are forward-only. Down-migrations are not written; recovery is restore-from-backup.
- **An applied migration is fingerprinted, not byte-hashed.** `schema_version.checksum` is the SHA-256 of the
  statements the file actually executes: comments stripped, runs of whitespace collapsed to one space, and spaces
  around `( ) , ;` removed. Editing a migration that has already been applied is refused — but *reformatting* one,
  rewrapping it, rewriting a comment or checking it out with different line endings is not a change and must not lock
  a database out of its own schema. Hashing the raw bytes did exactly that on 2026-09-02: running a SQL formatter over
  the migration files made every existing campaign database refuse to open with "V001__baseline.sql was modified after
  being applied", although all 58 of its schema objects were identical once whitespace was ignored. Pinned by
  `MigrationsTest`. The one thing the fingerprint cannot see is whitespace inside a string literal next to one of
  those characters; no shipped migration contains such a literal, and a future one must not rely on it.
- Repairing a database whose recorded checksums predate a fingerprint change is a deliberate, one-off act: prove the
  live schema and a freshly migrated one are identical (compare `sqlite_master` with whitespace removed), take a
  backup, then re-record `schema_version.checksum`.
- The seed importer runs after migrations, guarded by `installed_ruleset` (I-66) — dataset versions are data, not
  schema, and are never encoded in migrations.
- A schema change to a rewindable table must state its effect on existing `undo_json` payloads (a migration that
  renames/retypes a column must also rewrite stored before-images, or the release notes must invalidate pre-existing
  checkpoints explicitly). This is a release checklist item, enforced by a test that restores a fixture checkpoint
  created on the previous schema version.

---

## 8. Integrity Enforcement Map

| Enforcement                                              | Where                                                                                                                                 |
|----------------------------------------------------------|---------------------------------------------------------------------------------------------------------------------------------------|
| ID stability, no reuse                                   | `AUTOINCREMENT` everywhere (I-1, I-45, I-49)                                                                                          |
| Value ranges (HP, money, quantity, resources)            | SQL `CHECK` (I-13, I-14, I-23, I-30)                                                                                                  |
| Enum validity                                            | SQL `CHECK`                                                                                                                           |
| One active membership / control assignment / setup draft | partial unique indexes (I-17)                                                                                                         |
| Referential integrity within campaign                    | FKs + application campaign-scope validation (I-3; cross-campaign rejection is app-level)                                              |
| Lifecycle gating (`DRAFT` exclusion from gameplay)       | application queries filter `lifecycle='ACTIVE'` (I-6) — plus a test-suite sweep asserting every gameplay query site filters lifecycle |
| No out-of-band writes                                    | journaling data-access layer is the only write path (I-48)                                                                            |
| Atomicity                                                | one transaction per tool invocation (I-69)                                                                                            |
| Custom-content delete protection                         | application check before delete (I-68)                                                                                                |
| Visibility filtering                                     | application/read layer (I-63) — never by shipping filtered columns to the client                                                      |

---

## 9. Backup

Out of MVP scope as a feature; operationally: stop-the-world file copy, or SQLite's online backup API later. WAL
checkpointing (`wal_checkpoint(TRUNCATE)`) runs on clean shutdown so the `.db` file alone is a consistent copy.

---

## 10. Open Items for `RULES_ENGINE.md`

1. Per-kind `payload_json` schemas for installed and custom content (the seed JSON schemas mirror these).
2. Which derived values, if any, justify a cache table (default: none — compute on read, I-4/§5.5).
3. `dimensions_json` shape for relationships (numeric vs. qualitative — `DESIGN.md` §36.3).
4. Calendar `calendar_json` format and instant formatting rules.
5. Grid schema details when the `tactical_grid` capability is designed (the participant position columns are ready for
   either model).
