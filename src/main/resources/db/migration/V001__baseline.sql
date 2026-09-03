-- ============================================================================
-- V001 — baseline schema. Implements DATABASE.md §3 (table catalog).
--
-- Conventions (DATABASE.md §2):
--   * snake_case, singular table names
--   * every table: id INTEGER PRIMARY KEY AUTOINCREMENT — IDs are never reused
--   * campaign-scoped tables carry campaign_id
--   * enums are TEXT + CHECK; flexible payloads are *_json TEXT
--   * real timestamps are RFC 3339 UTC TEXT; game time is (game_time TEXT, game_seq INTEGER)
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 3.1 Infrastructure (never rewound by checkpoint restore)
-- ---------------------------------------------------------------------------

CREATE TABLE installed_ruleset
(
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    namespace   TEXT NOT NULL,
    version     TEXT NOT NULL,
    imported_at TEXT NOT NULL,
    license     TEXT,
    attribution TEXT,
    UNIQUE (namespace, version)
);

CREATE TABLE installed_content
(
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    content_id   TEXT    NOT NULL UNIQUE,
    ruleset_id   INTEGER NOT NULL REFERENCES installed_ruleset (id),
    kind         TEXT    NOT NULL,
    name         TEXT    NOT NULL,
    payload_json TEXT    NOT NULL,
    cost_cp      INTEGER,
    weight_g     INTEGER,
    spell_level  INTEGER,
    cr_times_8   INTEGER,
    xp_value     INTEGER,
    tags_json    TEXT
);
CREATE INDEX idx_installed_content_kind_spell_level ON installed_content (kind, spell_level);
CREATE INDEX idx_installed_content_kind_cr ON installed_content (kind, cr_times_8);
CREATE INDEX idx_installed_content_kind_cost ON installed_content (kind, cost_cp);

-- ---------------------------------------------------------------------------
-- 3.2 Campaign and policy
-- ---------------------------------------------------------------------------

CREATE TABLE campaign
(
    id                      INTEGER PRIMARY KEY AUTOINCREMENT,
    title                   TEXT,
    ruleset_namespace       TEXT    NOT NULL,
    ruleset_version         TEXT    NOT NULL,
    status                  TEXT    NOT NULL CHECK (status IN
                                                    ('SETUP', 'READY_TO_PLAY', 'ACTIVE', 'SUSPENDED', 'COMPLETED',
                                                     'FAILED', 'ABANDONED')),
    harness_state           TEXT    NOT NULL,
    continuation_policy     TEXT CHECK (continuation_policy IN ('CHECKPOINT', 'ENCOUNTER_RETRY', 'IRONMAN')),
    gm_override_policy_json TEXT,
    preferences_json        TEXT,
    adventure_json          TEXT,
    current_location_id     INTEGER REFERENCES location (id),
    active_session_id       INTEGER REFERENCES session (id),
    revision                INTEGER NOT NULL DEFAULT 0,
    created_at              TEXT    NOT NULL,
    last_played_at          TEXT
);

CREATE TABLE policy_state
(
    id                      INTEGER PRIMARY KEY AUTOINCREMENT,
    campaign_id             INTEGER NOT NULL UNIQUE REFERENCES campaign (id),
    content_profile         TEXT CHECK (content_profile IN ('PEGI_3', 'PEGI_7', 'PEGI_12', 'PEGI_16', 'PEGI_18')),
    player_constraints_json TEXT,
    revision                INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE game_clock
(
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    campaign_id   INTEGER NOT NULL UNIQUE REFERENCES campaign (id),
    calendar_json TEXT    NOT NULL,
    instant       TEXT    NOT NULL,
    seq           INTEGER NOT NULL
);

CREATE TABLE campaign_setup_draft
(
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    campaign_id  INTEGER NOT NULL UNIQUE REFERENCES campaign (id),
    payload_json TEXT    NOT NULL,
    status       TEXT    NOT NULL CHECK (status IN ('OPEN', 'CONSUMED', 'ABANDONED')),
    revision     INTEGER NOT NULL DEFAULT 0
);

-- ---------------------------------------------------------------------------
-- 3.3 Character
-- ---------------------------------------------------------------------------

CREATE TABLE character
(
    id                 INTEGER PRIMARY KEY AUTOINCREMENT,
    campaign_id        INTEGER NOT NULL REFERENCES campaign (id),
    lifecycle          TEXT    NOT NULL CHECK (lifecycle IN ('DRAFT', 'FINALIZED_DRAFT', 'ACTIVE', 'ARCHIVED')),
    life_state         TEXT    NOT NULL CHECK (life_state IN ('ALIVE', 'DYING', 'DEAD')),
    -- narrative identity
    name               TEXT,
    description        TEXT,
    appearance         TEXT,
    personality        TEXT,
    background         TEXT,
    goals_json         TEXT,
    alignment          TEXT,
    age                INTEGER,
    presentation       TEXT,
    -- rules identity
    species_ref        TEXT,
    str_score          INTEGER,
    dex_score          INTEGER,
    con_score          INTEGER,
    int_score          INTEGER,
    wis_score          INTEGER,
    cha_score          INTEGER,
    max_hp             INTEGER,
    speed              INTEGER,
    senses_json        TEXT,
    origin_content_ref TEXT,
    origin_seed_id     INTEGER REFERENCES director_seed (id),
    creation_json      TEXT,
    -- runtime state
    current_hp         INTEGER,
    temp_hp            INTEGER NOT NULL DEFAULT 0,
    exhaustion         INTEGER NOT NULL DEFAULT 0,
    xp                 INTEGER NOT NULL DEFAULT 0 CHECK (xp >= 0),
    money_cp           INTEGER NOT NULL DEFAULT 0 CHECK (money_cp >= 0),
    location_id        INTEGER REFERENCES location (id),
    encounter_id       INTEGER REFERENCES encounter (id),
    revision           INTEGER NOT NULL DEFAULT 0,
    created_at         TEXT    NOT NULL,
    CHECK (current_hp IS NULL OR max_hp IS NULL OR (current_hp >= 0 AND current_hp <= max_hp))
);
CREATE INDEX idx_character_campaign_lifecycle ON character (campaign_id, lifecycle);

CREATE TABLE character_class
(
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    character_id INTEGER NOT NULL REFERENCES character (id),
    class_ref    TEXT    NOT NULL,
    subclass_ref TEXT,
    level        INTEGER NOT NULL CHECK (level >= 1),
    UNIQUE (character_id, class_ref)
);

CREATE TABLE character_trait
(
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    character_id INTEGER NOT NULL REFERENCES character (id),
    kind         TEXT    NOT NULL CHECK (kind IN
                                         ('PROFICIENCY', 'SKILL', 'SAVE', 'LANGUAGE', 'SPELL_KNOWN', 'SPELL_PREPARED',
                                          'FEAT', 'FEATURE', 'MASTERY')),
    content_ref  TEXT    NOT NULL,
    payload_json TEXT,
    UNIQUE (character_id, kind, content_ref)
);

CREATE TABLE player_control_assignment
(
    id               INTEGER PRIMARY KEY AUTOINCREMENT,
    campaign_id      INTEGER NOT NULL REFERENCES campaign (id),
    seat             TEXT    NOT NULL DEFAULT 'player-1',
    character_id     INTEGER NOT NULL REFERENCES character (id),
    since_journal_id INTEGER,
    active           INTEGER NOT NULL CHECK (active IN (0, 1))
);
CREATE UNIQUE INDEX idx_player_control_active ON player_control_assignment (campaign_id, seat) WHERE active = 1;

-- ---------------------------------------------------------------------------
-- 3.4 Party, relationships
-- ---------------------------------------------------------------------------

CREATE TABLE party_membership
(
    id             INTEGER PRIMARY KEY AUTOINCREMENT,
    campaign_id    INTEGER NOT NULL REFERENCES campaign (id),
    character_id   INTEGER NOT NULL REFERENCES character (id),
    state          TEXT    NOT NULL CHECK (state IN
                                           ('ACTIVE', 'SEPARATED', 'GUEST', 'LEFT', 'DISMISSED', 'DEAD', 'ENDED')),
    joined_time    TEXT,
    joined_seq     INTEGER,
    left_time      TEXT,
    left_seq       INTEGER,
    cause_event_id INTEGER REFERENCES event (id),
    notes          TEXT
);
CREATE INDEX idx_party_membership_campaign_state ON party_membership (campaign_id, state);
CREATE UNIQUE INDEX idx_party_membership_open ON party_membership (character_id) WHERE state IN ('ACTIVE','SEPARATED','GUEST');

CREATE TABLE relationship
(
    id                INTEGER PRIMARY KEY AUTOINCREMENT,
    campaign_id       INTEGER NOT NULL REFERENCES campaign (id),
    from_character_id INTEGER NOT NULL REFERENCES character (id),
    to_character_id   INTEGER NOT NULL REFERENCES character (id),
    dimensions_json   TEXT,
    summary           TEXT,
    revision          INTEGER NOT NULL DEFAULT 0,
    UNIQUE (campaign_id, from_character_id, to_character_id)
);

CREATE TABLE relationship_event
(
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    relationship_id INTEGER NOT NULL REFERENCES relationship (id),
    event_id        INTEGER NOT NULL REFERENCES event (id),
    UNIQUE (relationship_id, event_id)
);

-- ---------------------------------------------------------------------------
-- 3.5 Inventory, effects, resources
-- ---------------------------------------------------------------------------

CREATE TABLE inventory_entry
(
    id                 INTEGER PRIMARY KEY AUTOINCREMENT,
    campaign_id        INTEGER NOT NULL REFERENCES campaign (id),
    character_id       INTEGER REFERENCES character (id),
    location_id        INTEGER REFERENCES location (id),
    container_entry_id INTEGER REFERENCES inventory_entry (id),
    content_ref_kind   TEXT    NOT NULL CHECK (content_ref_kind IN ('INSTALLED', 'CUSTOM')),
    content_ref        TEXT,
    custom_content_id  INTEGER REFERENCES custom_content (id),
    quantity           INTEGER NOT NULL CHECK (quantity > 0),
    equipped           INTEGER NOT NULL DEFAULT 0 CHECK (equipped IN (0, 1)),
    slot               TEXT,
    charges_json       TEXT,
    CHECK ((character_id IS NOT NULL) + (location_id IS NOT NULL) + (container_entry_id IS NOT NULL) = 1),
    CHECK ((content_ref IS NOT NULL) + (custom_content_id IS NOT NULL) = 1)
);
CREATE INDEX idx_inventory_entry_owner ON inventory_entry (campaign_id, character_id);

CREATE TABLE active_effect
(
    id                         INTEGER PRIMARY KEY AUTOINCREMENT,
    campaign_id                INTEGER NOT NULL REFERENCES campaign (id),
    character_id               INTEGER NOT NULL REFERENCES character (id),
    source_character_id        INTEGER REFERENCES character (id),
    source_content_ref         TEXT,
    source_description         TEXT,
    provenance                 TEXT    NOT NULL CHECK (provenance IN
                                                       ('PLAYER', 'GM', 'DIRECTOR', 'MECHANICAL_CONSEQUENCE',
                                                        'ADMINISTRATIVE_OVERRIDE')),
    condition_ref              TEXT,
    modifier_json              TEXT,
    start_seq                  INTEGER NOT NULL,
    start_journal_id           INTEGER,
    duration_json              TEXT,
    concentration_character_id INTEGER REFERENCES character (id),
    stacking_key               TEXT
);
CREATE INDEX idx_active_effect_character ON active_effect (character_id);

CREATE TABLE resource_state
(
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    character_id INTEGER NOT NULL REFERENCES character (id),
    resource_ref TEXT    NOT NULL,
    current      INTEGER NOT NULL,
    max          INTEGER NOT NULL,
    recharge     TEXT,
    UNIQUE (character_id, resource_ref),
    CHECK (current >= 0 AND current <= max
)
    );

-- ---------------------------------------------------------------------------
-- 3.6 Encounter
-- ---------------------------------------------------------------------------

CREATE TABLE encounter
(
    id                  INTEGER PRIMARY KEY AUTOINCREMENT,
    campaign_id         INTEGER NOT NULL REFERENCES campaign (id),
    status              TEXT    NOT NULL CHECK (status IN ('CREATED', 'RUNNING', 'WAITING_CHOICE', 'ENDED')),
    round               INTEGER NOT NULL DEFAULT 0,
    turn_participant_id INTEGER,
    sides_json          TEXT,
    environment_json    TEXT,
    objectives_json     TEXT,
    spatial_model       TEXT    NOT NULL DEFAULT 'ZONES' CHECK (spatial_model IN ('ZONES', 'GRID')),
    retry_checkpoint_id INTEGER,
    location_id         INTEGER REFERENCES location (id),
    revision            INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE encounter_participant
(
    id                  INTEGER PRIMARY KEY AUTOINCREMENT,
    encounter_id        INTEGER NOT NULL REFERENCES encounter (id),
    character_id        INTEGER NOT NULL REFERENCES character (id),
    side                TEXT    NOT NULL,
    initiative          INTEGER,
    initiative_tiebreak INTEGER,
    position_zone       TEXT,
    position_x          INTEGER,
    position_y          INTEGER,
    status              TEXT    NOT NULL CHECK (status IN ('ACTIVE', 'DEFEATED', 'FLED', 'REMOVED')),
    UNIQUE (encounter_id, character_id)
);

-- ---------------------------------------------------------------------------
-- 3.7 Narrative state (five sibling tables)
-- ---------------------------------------------------------------------------

CREATE TABLE quest
(
    id                      INTEGER PRIMARY KEY AUTOINCREMENT,
    campaign_id             INTEGER NOT NULL REFERENCES campaign (id),
    visibility              TEXT    NOT NULL,
    visibility_targets_json TEXT,
    provenance              TEXT    NOT NULL,
    revision                INTEGER NOT NULL DEFAULT 0,
    payload_json            TEXT,
    created_at              TEXT    NOT NULL,
    game_time               TEXT,
    game_seq                INTEGER,
    title                   TEXT    NOT NULL,
    status                  TEXT    NOT NULL CHECK (status IN ('OFFERED', 'ACCEPTED', 'COMPLETED', 'FAILED', 'ABANDONED')),
    issuer_character_id     INTEGER REFERENCES character (id),
    deadline_seq            INTEGER
);

CREATE TABLE faction
(
    id                      INTEGER PRIMARY KEY AUTOINCREMENT,
    campaign_id             INTEGER NOT NULL REFERENCES campaign (id),
    visibility              TEXT    NOT NULL,
    visibility_targets_json TEXT,
    provenance              TEXT    NOT NULL,
    revision                INTEGER NOT NULL DEFAULT 0,
    payload_json            TEXT,
    created_at              TEXT    NOT NULL,
    game_time               TEXT,
    game_seq                INTEGER,
    name                    TEXT    NOT NULL,
    standing_json           TEXT,
    agenda_json             TEXT
);

CREATE TABLE story_beat
(
    id                      INTEGER PRIMARY KEY AUTOINCREMENT,
    campaign_id             INTEGER NOT NULL REFERENCES campaign (id),
    visibility              TEXT    NOT NULL,
    visibility_targets_json TEXT,
    provenance              TEXT    NOT NULL,
    revision                INTEGER NOT NULL DEFAULT 0,
    payload_json            TEXT,
    created_at              TEXT    NOT NULL,
    game_time               TEXT,
    game_seq                INTEGER,
    title                   TEXT    NOT NULL,
    state                   TEXT    NOT NULL CHECK (state IN
                                                    ('PLANNED', 'AVAILABLE', 'BLOCKED', 'SUPERSEDED', 'COMPLETED',
                                                     'ABANDONED')),
    superseded_by_id        INTEGER REFERENCES story_beat (id)
);

CREATE TABLE director_seed
(
    id                        INTEGER PRIMARY KEY AUTOINCREMENT,
    campaign_id               INTEGER NOT NULL REFERENCES campaign (id),
    visibility                TEXT    NOT NULL,
    visibility_targets_json   TEXT,
    provenance                TEXT    NOT NULL,
    revision                  INTEGER NOT NULL DEFAULT 0,
    payload_json              TEXT,
    created_at                TEXT    NOT NULL,
    game_time                 TEXT,
    game_seq                  INTEGER,
    kind                      TEXT    NOT NULL CHECK (kind IN ('STORY_SEED', 'COMPANION_INTRO', 'PRESSURE', 'PACING_INTENT')),
    state                     TEXT    NOT NULL CHECK (state IN ('OPEN', 'MATERIALIZED', 'SUPERSEDED', 'EXPIRED')),
    superseded_by_id          INTEGER REFERENCES director_seed (id),
    materialized_character_id INTEGER REFERENCES character (id)
);

CREATE TABLE world_event
(
    id                      INTEGER PRIMARY KEY AUTOINCREMENT,
    campaign_id             INTEGER NOT NULL REFERENCES campaign (id),
    visibility              TEXT    NOT NULL,
    visibility_targets_json TEXT,
    provenance              TEXT    NOT NULL,
    revision                INTEGER NOT NULL DEFAULT 0,
    payload_json            TEXT,
    created_at              TEXT    NOT NULL,
    game_time               TEXT,
    game_seq                INTEGER,
    title                   TEXT    NOT NULL,
    channels_json           TEXT,
    affected_refs_json      TEXT
);

-- ---------------------------------------------------------------------------
-- 3.8 Locations
-- ---------------------------------------------------------------------------

CREATE TABLE location
(
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    campaign_id     INTEGER NOT NULL REFERENCES campaign (id),
    kind            TEXT    NOT NULL CHECK (kind IN ('REGION', 'SETTLEMENT', 'DISTRICT', 'SITE', 'BUILDING', 'AREA')),
    parent_id       INTEGER REFERENCES location (id),
    name            TEXT    NOT NULL,
    description     TEXT,
    materialization TEXT    NOT NULL CHECK (materialization IN ('SEMANTIC', 'MATERIALIZED')),
    generation_seed INTEGER,
    features_json   TEXT,
    revision        INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX idx_location_campaign_parent ON location (campaign_id, parent_id);

CREATE TABLE location_connection
(
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    campaign_id   INTEGER NOT NULL REFERENCES campaign (id),
    location_a_id INTEGER NOT NULL REFERENCES location (id),
    location_b_id INTEGER NOT NULL REFERENCES location (id),
    kind          TEXT,
    state_json    TEXT,
    distance_json TEXT,
    CHECK (location_a_id < location_b_id)
);
CREATE INDEX idx_location_connection_a ON location_connection (campaign_id, location_a_id);
CREATE INDEX idx_location_connection_b ON location_connection (campaign_id, location_b_id);

-- ---------------------------------------------------------------------------
-- 3.9 Sessions and transactions
-- ---------------------------------------------------------------------------

-- Sessions are audit history (not rewound); see MCP_PROTOCOL.md §19.1.
CREATE TABLE session
(
    id               INTEGER PRIMARY KEY AUTOINCREMENT,
    campaign_id      INTEGER NOT NULL REFERENCES campaign (id),
    started_at       TEXT    NOT NULL,
    ended_at         TEXT,
    start_game_seq   INTEGER,
    end_game_seq     INTEGER,
    start_journal_id INTEGER,
    end_journal_id   INTEGER,
    summary          TEXT,
    events_written   INTEGER NOT NULL DEFAULT 0,
    superseded       INTEGER NOT NULL DEFAULT 0 CHECK (superseded IN (0, 1))
);

CREATE TABLE pending_transaction
(
    id                   INTEGER PRIMARY KEY AUTOINCREMENT,
    campaign_id          INTEGER NOT NULL REFERENCES campaign (id),
    kind                 TEXT    NOT NULL CHECK (kind IN ('CHARACTER_CREATION', 'CAMPAIGN_COMMIT', 'LEVEL_UP',
                                                          'ENCOUNTER_CHOICE', 'LOCATION_MATERIALIZATION',
                                                          'TRAVEL_INTERRUPT', 'REST_INTERRUPT')),
    status               TEXT    NOT NULL CHECK (status IN ('OPEN', 'COMMITTED', 'ABANDONED', 'EXPIRED')),
    controlled_refs_json TEXT,
    payload_json         TEXT,
    expires_at           TEXT,
    revision             INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX idx_pending_transaction_campaign_status ON pending_transaction (campaign_id, status);

-- ---------------------------------------------------------------------------
-- 3.10 The three logs
-- ---------------------------------------------------------------------------

-- Semantic ledger (rewindable: recording an event is itself journaled).
CREATE TABLE event
(
    id                      INTEGER PRIMARY KEY AUTOINCREMENT,
    campaign_id             INTEGER NOT NULL REFERENCES campaign (id),
    type                    TEXT    NOT NULL,
    summary                 TEXT    NOT NULL,
    payload_json            TEXT,
    importance              TEXT    NOT NULL CHECK (importance IN ('MINOR', 'NOTABLE', 'MAJOR', 'CRITICAL')),
    visibility              TEXT    NOT NULL,
    visibility_targets_json TEXT,
    provenance              TEXT    NOT NULL,
    fictional_time          TEXT,
    fictional_seq           INTEGER NOT NULL,
    recorded_time           TEXT,
    recorded_seq            INTEGER NOT NULL,
    recorded_journal_id     INTEGER,
    location_id             INTEGER REFERENCES location (id),
    episodic_detail         TEXT
);
CREATE INDEX idx_event_campaign_fictional ON event (campaign_id, fictional_seq);
CREATE INDEX idx_event_campaign_type_importance ON event (campaign_id, type, importance);

CREATE TABLE event_actor
(
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    event_id     INTEGER NOT NULL REFERENCES event (id),
    character_id INTEGER NOT NULL REFERENCES character (id),
    UNIQUE (event_id, character_id)
);
CREATE INDEX idx_event_actor_character ON event_actor (character_id, event_id);

CREATE TABLE event_causal
(
    id                 INTEGER PRIMARY KEY AUTOINCREMENT,
    event_id           INTEGER NOT NULL REFERENCES event (id),
    caused_by_event_id INTEGER NOT NULL REFERENCES event (id),
    UNIQUE (event_id, caused_by_event_id)
);

-- Authoritative random results (rewindable, tied to the journal entry that produced them).
CREATE TABLE roll
(
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    campaign_id  INTEGER REFERENCES campaign (id),
    journal_id   INTEGER NOT NULL,
    purpose      TEXT,
    expression   TEXT    NOT NULL,
    dice_json    TEXT    NOT NULL,
    dropped_json TEXT,
    modifier     INTEGER NOT NULL DEFAULT 0,
    total        INTEGER NOT NULL
);

-- Change journal (never rewound; rows after a restored marker are removed by the restore itself).
CREATE TABLE journal_entry
(
    id                   INTEGER PRIMARY KEY AUTOINCREMENT,
    campaign_id          INTEGER,
    operation            TEXT    NOT NULL,
    operation_id         TEXT,
    args_hash            TEXT,
    provenance           TEXT    NOT NULL,
    recorded_at          TEXT    NOT NULL,
    game_seq             INTEGER,
    touched_json         TEXT,
    undo_json            TEXT    NOT NULL DEFAULT '[]',
    result_json          TEXT,
    is_checkpoint_marker INTEGER NOT NULL DEFAULT 0 CHECK (is_checkpoint_marker IN (0, 1))
);
CREATE INDEX idx_journal_entry_campaign ON journal_entry (campaign_id, id);
CREATE UNIQUE INDEX idx_journal_entry_operation_id ON journal_entry (campaign_id, operation_id) WHERE operation_id IS NOT NULL;

CREATE TABLE checkpoint
(
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    campaign_id INTEGER NOT NULL REFERENCES campaign (id),
    journal_id  INTEGER NOT NULL, -- marker id; no FK so invalidated checkpoints survive journal truncation
    reason      TEXT,
    game_time   TEXT,
    game_seq    INTEGER,
    created_at  TEXT    NOT NULL,
    status      TEXT    NOT NULL CHECK (status IN ('ACTIVE', 'RESTORED_TO', 'INVALIDATED'))
);

-- Audit lineage. Never rolled back; no foreign keys into rewindable tables.
CREATE TABLE audit_record
(
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    campaign_id INTEGER,
    kind        TEXT NOT NULL,
    actor       TEXT,
    provenance  TEXT NOT NULL,
    reason      TEXT,
    before_json TEXT,
    after_json  TEXT,
    recorded_at TEXT NOT NULL
);

-- Campaign-owned custom content definitions (content:N).
CREATE TABLE custom_content
(
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    campaign_id  INTEGER NOT NULL REFERENCES campaign (id),
    kind         TEXT    NOT NULL,
    symbolic_id  TEXT,
    name         TEXT    NOT NULL,
    payload_json TEXT    NOT NULL,
    cost_cp      INTEGER,
    weight_g     INTEGER,
    tags_json    TEXT,
    license_json TEXT,
    provenance   TEXT    NOT NULL,
    revision     INTEGER NOT NULL DEFAULT 0,
    created_at   TEXT    NOT NULL
);
CREATE INDEX idx_custom_content_campaign_kind ON custom_content (campaign_id, kind);
CREATE UNIQUE INDEX idx_custom_content_symbolic ON custom_content (campaign_id, symbolic_id) WHERE symbolic_id IS NOT NULL;
