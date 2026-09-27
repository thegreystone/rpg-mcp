# RPG MCP Server — Database

**Purpose:** The SQLite physical model: storage decisions, schema conventions, tables, the change-journal undo
representation, checkpoint rollback, migrations, and integrity enforcement.
**Scope:** Implements `DOMAIN_MODEL.md` (invariants `I-nn` point there). Per-kind content JSON schemas and derived
values belong to `RULES_ENGINE.md`.

---

## 1. Storage Decisions

- **One SQLite file** per installation (`rpg.db` in the data directory). Campaigns are rows. Installed ruleset content
  is shared; campaign isolation is by `campaign_id` scoping, and cross-campaign references are rejected at the protocol
  layer.
- **Journal-based checkpoints** (`DESIGN.md` §29): every canonical mutation appends to the change journal; a checkpoint
  is a marker; restore rolls back by insertion ID (I-48…I-52).
- **Single writer.** One tool invocation is one transaction (I-69). The application serializes mutations; SQLite's
  writer lock is a backstop.
- **Runtime settings:**

```text
PRAGMA journal_mode = WAL;
PRAGMA foreign_keys = ON;
PRAGMA synchronous = NORMAL;
PRAGMA busy_timeout = 5000;
```

`foreign_keys` must be set per connection.

- **Seed data** is imported from JSON classpath resources on startup when `installed_ruleset` lacks the
  ruleset+version (I-66), one transaction per ruleset version.

---

## 2. Schema Conventions

- `snake_case` names; table names singular (`character`, not `characters`).
- Every table: `id INTEGER PRIMARY KEY AUTOINCREMENT`. IDs are never reused after deletion (I-1, I-45, I-49).
- Campaign-scoped tables carry an indexed `campaign_id INTEGER NOT NULL REFERENCES campaign(id)`; all queries filter
  by it.
- Mutable aggregate roots carry `revision INTEGER NOT NULL DEFAULT 0` (domain model §2.5).
- Enums are `TEXT` with `CHECK (col IN (...))`.
- Real timestamps are RFC 3339 UTC `TEXT` (`created_at`, `updated_at` where useful).
- Game time is the pair `game_time TEXT` (display instant) + `game_seq INTEGER` (ordering). Ordering always uses
  `game_seq` (I-42, I-46).
- Flexible payloads are JSON in `TEXT` columns with a `_json` suffix; validity is the application's responsibility.
  Filtered or sorted fields get real columns.
- `provenance TEXT CHECK (provenance IN ('PLAYER','GM','DIRECTOR','MECHANICAL_CONSEQUENCE','ADMINISTRATIVE_OVERRIDE'))`.
- `visibility TEXT` with the six values of domain model §17; `CHARACTER_KNOWN`/`FACTION_KNOWN` targets live in a
  companion `_json` list column.

---

## 3. Table Catalog

Grouped as in `DOMAIN_MODEL.md`. Column lists state intent; migrations are the source of truth for exact DDL.

### 3.1 Infrastructure

**schema_version** — `id`, `version INTEGER UNIQUE`, `applied_at`, `description`, `checksum TEXT` (§7).

**installed_ruleset** — `id`, `namespace TEXT` (e.g. `srd5e`), `version TEXT`, `imported_at`, `license TEXT`,
`attribution TEXT`. Unique `(namespace, version)`. Backs `rpg://rulesets` and attribution (`DESIGN.md` §23).

**installed_content** — installed definitions, immutable at runtime (I-65):
`id`, `content_id TEXT UNIQUE` (e.g. `srd5e:item/longsword`), `ruleset_id → installed_ruleset`,
`kind TEXT` (`ITEM, WEAPON, ARMOR, TOOL, SPELL, CREATURE, CONDITION, CLASS, SUBCLASS, SPECIES, BACKGROUND, FEAT, TABLE, …`),
`name TEXT`, `payload_json`.
Nullable filterable columns, populated per kind: `cost_cp INTEGER`, `weight_g INTEGER`, `spell_level INTEGER`,
`cr_times_8 INTEGER` (CR ×8 so ⅛/¼/½ are integers), `xp_value INTEGER`, `tags_json`.

**custom_content** — campaign-owned definitions (`content:N`, I-67):
`id`, `campaign_id`, `kind`, `symbolic_id TEXT NULL` (e.g. `custom:item/bellhaven-broadsheet`), `name`, `payload_json`,
`cost_cp`, `weight_g`, `tags_json`, `license_json NULL`, `provenance`, `revision`, `created_at`. Unique
`(campaign_id, symbolic_id)` where not null (I-5).

A content reference elsewhere is `content_ref_kind TEXT CHECK (IN ('INSTALLED','CUSTOM'))` plus either
`content_ref TEXT` (the `content_id`) or `custom_content_id INTEGER`, with a CHECK that exactly one is set.

### 3.2 Campaign and policy

**campaign** — `id`, `title TEXT NULL` (generated at commit if null), `ruleset_namespace`, `ruleset_version`,
`status TEXT CHECK (IN ('SETUP','READY_TO_PLAY','ACTIVE','SUSPENDED','COMPLETED','FAILED','ABANDONED'))`,
`harness_state TEXT`, `continuation_policy TEXT`, `gm_override_policy_json`, `preferences_json`,
`current_location_id NULL → location`, `active_session_id NULL → session`, `house_rules_json NULL` (list of strings,
V007), `calendar_json NULL` (V007, §3.11), `revision`, `created_at`.

**policy_state** — 1:1 with campaign: `id`, `campaign_id UNIQUE`, `content_profile TEXT` (PEGI values),
`player_constraints_json` (least-specific sufficient form, I-58/I-59), `revision`.

**game_clock** — 1:1 with campaign: `id`, `campaign_id UNIQUE`, `calendar_json`, `instant TEXT`,
`seq INTEGER NOT NULL` (I-42).

**campaign_setup_draft** — `id`, `campaign_id UNIQUE`, `payload_json` (all wizard dimensions, explicit `SURPRISE_ME`
markers, proposed control assignment), `status TEXT CHECK (IN ('OPEN','CONSUMED','ABANDONED'))`, `revision`. Consumed
drafts are retained but unreferenced (I-9); retention policy may prune the payload.

### 3.3 Character

**character** — one row per creature (domain model §2.2, §5):
`id`, `campaign_id`, `lifecycle TEXT CHECK (IN ('DRAFT','FINALIZED_DRAFT','ACTIVE','ARCHIVED'))`,
`life_state TEXT CHECK (IN ('ALIVE','DYING','DEAD'))`;
narrative: `name`, `description`, `appearance`, `personality`, `backstory`, `goals_json`, `alignment`, `age`,
`presentation`, `biography_json NULL` (V008: `{timeline, voice, state, marks, wants}`, MCP_PROTOCOL.md §12.7),
`intimacy_json NULL` (V008: the character's own intimate profile; written and returned only under PEGI_18);
rules identity: `species_ref`, `background_ref`, `str/dex/con/int_/wis/cha INTEGER` (final scores; the pre-background
base is `creation_json.base_scores`), `max_hp INTEGER`, `armor_class_override INTEGER` (V006: set by `apply_gm_override`
`SET_ARMOR_CLASS`; NULL derives AC from equipment), `speed INTEGER`, `senses_json`, `origin_content_ref` columns
(source creature definition, nullable), `origin_seed_id NULL`;
runtime: `current_hp INTEGER`, `temp_hp INTEGER DEFAULT 0`, `exhaustion INTEGER DEFAULT 0`,
`money_cp INTEGER NOT NULL DEFAULT 0 CHECK (money_cp >= 0)` (I-14), `location_id NULL → location`,
`encounter_id NULL → encounter` (I-15), `xp INTEGER NOT NULL DEFAULT 0 CHECK (xp >= 0)`;
`revision`, `created_at`. CHECK `current_hp BETWEEN 0 AND max_hp` (I-13).

A temporary change to the maximum (Aid, Life Drain) is an `active_effect` with a signed `{"max_hp": n}` modifier,
applied to `max_hp` when it begins and taken back when it ends (`Effects.end`), so the column is always the maximum in
force. `Effects.removeAll` ends every effect on death or revival by fiat.

Staged (pre-`ACTIVE`) rules state, inventory and money live in the same columns and tables as active state; every
gameplay query gates on `lifecycle = 'ACTIVE'` (I-6).

**character_class** — `id`, `character_id`, `class_ref`, `subclass_ref NULL`, `level INTEGER CHECK (level >= 1)`. Unique
`(character_id, class_ref)`.

**character_trait** — `id`, `character_id`, `kind TEXT` (`PROFICIENCY, SKILL, SAVE, LANGUAGE, SPELL_KNOWN,
SPELL_PREPARED, FEAT, FEATURE, MASTERY`, a CHECK), content ref columns, `payload_json NULL`. Unique
`(character_id, kind, content_ref)`. A sorcerer's known Metamagic options are `FEATURE` rows with ref `metamagic:<id>`.

**player_control_assignment** — `id`, `campaign_id`, `seat TEXT NOT NULL DEFAULT 'player-1'`,
`character_id → character`, `since_journal_id`, `active INTEGER CHECK (IN (0,1))`. Partial unique index: one active row
per `(campaign_id, seat)`. History rows record former control (I-10).

### 3.4 Party, relationships

**party_membership** — `id`, `campaign_id`, `character_id`,
`state TEXT CHECK (IN ('ACTIVE','SEPARATED','GUEST','LEFT','DISMISSED','DEAD','ENDED'))`, `joined_time/joined_seq`,
`left_time/left_seq NULL`, `cause_event_id NULL → event` (I-18), `notes`. Partial unique index: one non-terminal row per
character (I-17).

**relationship** — `id`, `campaign_id`, `from_character_id`, `to_character_id`, `dimensions_json`, `summary TEXT`,
`profile_json NULL` (milestones, terms, preferences, wants, hard_lines; V007), `revision`.
Unique `(campaign_id, from_character_id, to_character_id)`.

**relationship_event** — `relationship_id`, `event_id`, PK on the pair (I-21).

### 3.5 Inventory, effects, resources

**inventory_entry** — `id`, `campaign_id`, owner columns (`character_id NULL`, `location_id NULL`,
`container_entry_id NULL`; exactly one set, CHECK), content ref columns (I-24),
`quantity INTEGER CHECK (quantity > 0)` (I-23), `equipped INTEGER`, `slot TEXT NULL`, `charges_json NULL`.

**active_effect** — `id`, `campaign_id`, `character_id`, source columns (character/content/inline + provenance),
condition/effect content ref or `modifier_json`, `start_seq`, `start_journal_id`, `duration_json`,
`concentration_character_id NULL` (I-29), `stacking_key TEXT NULL`.

**resource_state** — `id`, `character_id`, `resource_ref TEXT` (e.g. `slot:3`, `sorcery_points`, `innate_sorcery`;
pools come from a class feature's, species trait's or feat's `resource` block), `current INTEGER`, `max INTEGER`,
CHECK `0 <= current AND current <= max` (I-30), `recharge TEXT`.

### 3.6 Encounter

**encounter** — `id`, `campaign_id`, `status TEXT CHECK (IN ('CREATED','RUNNING','WAITING_CHOICE','ENDED'))`,
`round INTEGER`, `turn_participant_id NULL`, `sides_json` (side names + inter-side stance), `environment_json`,
`objectives_json`, `spatial_model TEXT CHECK (IN ('ZONES','GRID'))`, `retry_checkpoint_id NULL → checkpoint` (I-35),
`location_id NULL`, `revision`.

**encounter_participant** — `id`, `encounter_id`, `character_id`, `side TEXT`, `initiative INTEGER`,
`initiative_tiebreak INTEGER`, `position_zone TEXT NULL`, `position_x/position_y INTEGER NULL` (zone or x,y per
`spatial_model`), `status TEXT CHECK (IN ('ACTIVE','DEFEATED','FLED','REMOVED'))`. Unique `(encounter_id, character_id)`.

Turn order is `ORDER BY initiative DESC, initiative_tiebreak`, total by construction (I-31).

### 3.7 Narrative state

Five sibling tables with common columns `id`, `campaign_id`, `visibility` (+ `visibility_targets_json`), `provenance`,
`revision`, `payload_json`, `created_at`, `game_time/game_seq`, plus:

- **quest** — `title`, `status CHECK (IN ('OFFERED','ACCEPTED','COMPLETED','FAILED','ABANDONED'))`,
  `issuer_character_id NULL`, `deadline_seq NULL`.
- **faction** — `name`, `standing_json`, `agenda_json`.
- **story_beat** — `title`, `state CHECK (IN ('PLANNED','AVAILABLE','BLOCKED','SUPERSEDED','COMPLETED','ABANDONED'))`,
  `superseded_by_id NULL → story_beat` (I-36).
- **director_seed** — `kind CHECK (IN ('STORY_SEED','COMPANION_INTRO','PRESSURE','PACING_INTENT'))`,
  `state CHECK (IN ('OPEN','MATERIALIZED','SUPERSEDED','EXPIRED'))`, `superseded_by_id NULL`,
  `materialized_character_id NULL → character`.
- **world_event** — `title`, `channels_json` (diegetic delivery channels, I-38), `affected_refs_json`.

### 3.8 Locations

**location** — `id`, `campaign_id`, `kind CHECK (IN ('REGION','SETTLEMENT','DISTRICT','SITE','BUILDING','AREA'))`,
`parent_id NULL → location`, `name`, `description`, `materialization TEXT CHECK (IN ('SEMANTIC','MATERIALIZED'))`
(I-39), `generation_seed INTEGER NULL`, `features_json` (visibility-labeled entries), `revision`.

**location_connection** — `id`, `campaign_id`, `location_a_id`, `location_b_id`, `kind`, `state_json`
(open/locked/blocked/secret + visibility), `distance_json` (travel constraints/time), CHECK
`location_a_id < location_b_id` (one row per undirected edge).

### 3.9 Sessions and transactions

**session** — `id`, `campaign_id`, `started_at/ended_at`, `start_game_seq/end_game_seq`,
`start_journal_id/end_journal_id`, `summary TEXT NULL` (kept for the record; the recap does not read it),
`events_written INTEGER`.

**chronicle** (V008) — the story so far, written by a delegated summarizer (MCP_PROTOCOL.md §11.4, §11.6): `id`,
`campaign_id`, `kind CHECK (IN ('CHAPTER','SYNOPSIS'))`, `title NULL`, `summary TEXT NOT NULL`,
`from_journal_id/to_journal_id` (ledger span, exclusive/inclusive), `from_seq/from_time`, `to_seq/to_time` (game-time
span), `written_seq/written_time` (game clock when written), `written_at` (wall time), `events_covered`,
`chars_covered`, `covers_json NULL` (a synopsis: the chapter ids it was built from), `superseded INTEGER CHECK (IN (0,1))`.
Index `(campaign_id, kind, superseded, id)`. Rewindable. A chapter is due when uncovered non-minor ledger text exceeds
20,000 characters; a synopsis after four chapters or a closed quest.

**pending_transaction** — `id`, `campaign_id`,
`kind CHECK (IN ('CHARACTER_CREATION','CAMPAIGN_COMMIT','LEVEL_UP','ENCOUNTER_CHOICE','LOCATION_MATERIALIZATION','TRAVEL_INTERRUPT','REST_INTERRUPT'))`,
`status CHECK (IN ('OPEN','COMMITTED','ABANDONED','EXPIRED'))`, `controlled_refs_json` (I-55, enforced at open time),
`payload_json`, `expires_at NULL`, `revision`.

### 3.10 The three logs

**event** (semantic ledger) — `id AUTOINCREMENT` (I-45), `campaign_id`, `type TEXT`, `summary TEXT`, `payload_json`,
`importance CHECK (IN ('MINOR','NOTABLE','MAJOR','CRITICAL'))`, `visibility` (+ targets),
`fictional_time/fictional_seq` (may precede recording, I-46), `recorded_time/recorded_seq`,
`recorded_journal_id → journal_entry`, `location_id NULL`, `episodic_detail TEXT NULL`,
`session_id NULL → session` (V007; the derived recap groups by it).

**event_actor** — `event_id`, `character_id`, PK pair. Backs participant queries (`query_memories`).

**event_causal** — `event_id`, `caused_by_event_id`, PK pair.

**journal_entry** (change journal) — `id AUTOINCREMENT` (I-49), `campaign_id NULL` (null only for pre-campaign
operations), `operation TEXT` (tool name), `operation_id TEXT`, `provenance`, `recorded_at`, `game_seq`,
`touched_json` (aggregate refs + resulting revisions), `undo_json` (§4), `result_json` (idempotent replay result, I-60),
`is_checkpoint_marker INTEGER DEFAULT 0`. Unique `(campaign_id, operation_id)`; unique `(operation_id)` where
`campaign_id IS NULL`.

**checkpoint** — `id`, `campaign_id`, `journal_id → journal_entry` (the marker), `reason TEXT`, `game_time/game_seq`,
`created_at`, `status CHECK (IN ('ACTIVE','RESTORED_TO','INVALIDATED'))`. A checkpoint whose marker precedes a restored
marker may be restored again; checkpoints after a restored marker are `INVALIDATED` (their journal tail is gone).

**audit_record** — `id`, `campaign_id NULL`, `kind TEXT` (`GM_OVERRIDE, DISCRETIONARY_XP, CHECKPOINT_RESTORE,
ADMIN_EDIT, POLICY_CHANGE, DISCARDED_BRANCH`), `actor TEXT`, `provenance`, `reason TEXT`, `before_json/after_json`,
`recorded_at`. Never rolled back (I-53); references into rewindable tables are plain ref strings, not foreign keys, so
rollback cannot cascade into audit.

### 3.11 Economy (V007)

**account** — `id`, `campaign_id`, `name` (unique per campaign),
`owner_kind CHECK (IN ('FACTION','ESTATE','CHARACTER','OTHER'))`, `owner_id NULL` (per kind), `money_cp`, `notes`,
`revision`. A treasury that is not a character's purse; purses stay on `character.money_cp`. A money reference is
`account:n`, `character:n` or `WORLD`.

**cash_flow** — `id`, `campaign_id`, `name` (unique per campaign), `kind CHECK (IN ('MONEY','EVENT'))`,
`from_ref`/`to_ref` (money references, NULL for EVENT), `amount_json` (`{"fixed_cp": n}` |
`{"percent": p, "of_rule": cashFlowId}` | `{"percent": p, "of_inflows": ref}`, plus optional `"total_cp"` cap for a
debt), `schedule_json` (`{"kind": ONCE|DAILY|WEEKLY|MONTHLY|YEARLY|SEASONAL, "weekday", "day", "month", "season",
"minute_of_day"}`), `season_json` (multipliers per season, optional), `condition_json`
(`{"quest": "quest:n", "status": ...}`, optional), `description`, `start_seq`, `end_seq NULL`, `next_due_seq NULL`
(NULL = finished), `active`, `created_event_id → event`, `revision`. Index `(campaign_id, active, next_due_seq)` is the
scheduler's queue.

**cash_flow_run** — `id`, `cash_flow_id`, `due_seq`, `amount_cp`, `status CHECK (IN ('PAID','UNPAID','SKIPPED','FIRED'))`,
`event_id NULL → event`, `note`. Unique `(cash_flow_id, due_seq)`, so a due point fires once. The sum of PAID runs is
what a capped flow has repaid. All three tables are rewindable.

**campaign.calendar_json** — the epoch `{"year", "month", "day"}` Day 1 falls on in a 365-day year; NULL means
1 March of year 1. `game_clock.seq` remains the only ordering key; dates are derived.

---

## 4. Journal Undo Representation

The journal stores **before-images**, not forward deltas. `undo_json` is an ordered list of row-level inverses:

```json
[
  {"op": "restore", "table": "character", "pk": 3, "before": { ...full row... }},
  {"op": "delete",  "table": "inventory_entry", "pk": 41},
  {"op": "insert",  "table": "active_effect", "row": { ...full row... }}
]
```

- `INSERT` journals `delete`; `UPDATE` journals `restore` with the full before-row; `DELETE` journals `insert` with
  the full deleted row.
- The data-access layer emits undo entries for every write to a rewindable table. This enforces I-48 structurally:
  the journaling API is the only write path available to service code. Raw connection access is confined to the
  importer and the migration runner.

**Rewindable tables:** everything campaign-owned except `audit_record`, `journal_entry`, `checkpoint`,
`installed_ruleset`, `installed_content` and `schema_version` (I-50/I-51).

---

## 5. Checkpoint Rollback Algorithm

Restore to checkpoint C (marker journal ID M), in one transaction:

1. Validate C is restorable (`ACTIVE`, campaign matches, no open exclusive transaction forbids it).
2. Select the campaign's `journal_entry` rows with `id > M`, ordered descending.
3. Apply each row's `undo_json` in that order.
4. Delete those journal rows. The `event` rows they created are already covered by their undo entries (I-52).
5. Mark checkpoints with marker `> M` as `INVALIDATED`; mark C `RESTORED_TO`.
6. Write one `audit_record` of kind `CHECKPOINT_RESTORE` and one of kind `DISCARDED_BRANCH` (journal ID span, entry
   count, game-time span) (I-53).
7. Append the restoration as a new journal entry (not undoable past itself).

**Tests (I-51):** create checkpoint, run a mutation battery across every rewindable table, restore, and assert exact
equality with a pre-mutation `SELECT *` dump of the rewindable tables, ordered by pk. Property-style variants shuffle
the battery.

Rolled-back journal rows take their `operation_id`s with them, so replaying such an operation re-executes it. That is
correct: its effects were deliberately undone.

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

**Full-text search** (optional capability, off in MVP): a contentless FTS5 table over `event.summary` and
`event.episodic_detail`, maintained by triggers, added by a later migration. Memory queries work without it.

---

## 7. Migrations

- Numbered SQL files (`V001__baseline.sql`, …) embedded as classpath resources, applied by the server's own
  `Migrations` class over plain `sqlite-jdbc`. No Flyway; nothing has to be taught to work in a native image.
  `schema_version` is the history table.
- Forward-only. Recovery is restore-from-backup.
- **Applied migrations are fingerprinted, not byte-hashed.** `schema_version.checksum` is the SHA-256 of the statements
  the file executes: comments stripped, whitespace runs collapsed to one space, spaces around `( ) , ;` removed. Editing
  an applied migration is refused; reformatting, rewrapping, changing a comment or line endings is not a change.
  Pinned by `MigrationsTest`. The fingerprint cannot see whitespace inside a string literal adjacent to those
  characters; no shipped migration contains such a literal and a future one must not rely on it.
- Repairing a database whose checksums predate a fingerprint change is a deliberate one-off: prove the live schema and
  a freshly migrated one are identical (compare `sqlite_master` with whitespace removed), back up, then re-record
  `schema_version.checksum`.
- The seed importer runs after migrations, guarded by `installed_ruleset` (I-66). Dataset versions are data, never
  encoded in migrations.
- V008 adds nullable columns to `character` and a new rewindable table; older before-images restore the new columns as
  NULL, so pre-existing checkpoints stay valid.
- A schema change to a rewindable table must state its effect on stored `undo_json`: a migration that renames or
  retypes a column must rewrite stored before-images, or the release notes must invalidate pre-existing checkpoints
  explicitly. A test restores a fixture checkpoint created on the previous schema version.

---

## 8. Integrity Enforcement Map

| Enforcement                                              | Where                                                                                  |
|----------------------------------------------------------|----------------------------------------------------------------------------------------|
| ID stability, no reuse                                   | `AUTOINCREMENT` everywhere (I-1, I-45, I-49)                                           |
| Value ranges (HP, money, quantity, resources)            | SQL `CHECK` (I-13, I-14, I-23, I-30)                                                   |
| Enum validity                                            | SQL `CHECK`                                                                            |
| One active membership / control assignment / setup draft | partial unique indexes (I-17)                                                          |
| Referential integrity within campaign                    | FKs + application campaign-scope validation (I-3)                                      |
| Lifecycle gating (`DRAFT` excluded from gameplay)        | queries filter `lifecycle='ACTIVE'` (I-6); a test sweep asserts every gameplay query   |
| No out-of-band writes                                    | the journaling data-access layer is the only write path (I-48)                         |
| Atomicity                                                | one transaction per tool invocation (I-69)                                             |
| Custom-content delete protection                         | application check before delete (I-68)                                                 |
| Visibility filtering                                     | read layer (I-63); filtered columns are never shipped to the client                    |

---

## 9. Backup

Not an MVP feature. Operationally: stop-the-world file copy, or SQLite's online backup API later.
`wal_checkpoint(TRUNCATE)` runs on clean shutdown so the `.db` file alone is a consistent copy.

---

## 10. Open Items for `RULES_ENGINE.md`

1. Per-kind `payload_json` schemas for installed and custom content (the seed JSON schemas mirror these).
2. Which derived values, if any, justify a cache table (default: none; compute on read, I-4/§5.5).
3. `dimensions_json` shape for relationships (numeric vs. qualitative, `DESIGN.md` §36.3).
4. Grid schema when the `tactical_grid` capability is designed; the participant position columns accept either model.

`calendar_json` is resolved in §3.11.
