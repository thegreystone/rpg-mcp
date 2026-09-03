-- ============================================================================
-- V002 — encounter milestone.
--   * death saving throws are runtime character state
--   * retry checkpoints must rewind the very journal entry that created the encounter
--   * the encounter log answers "what happened on the previous turn?" (DESIGN.md §15)
-- ============================================================================

ALTER TABLE character
    ADD COLUMN death_saves_json TEXT;

ALTER TABLE checkpoint
    ADD COLUMN includes_marker INTEGER NOT NULL DEFAULT 0 CHECK (includes_marker IN (0, 1));

CREATE TABLE encounter_log
(
    id                 INTEGER PRIMARY KEY AUTOINCREMENT,
    campaign_id        INTEGER NOT NULL REFERENCES campaign (id),
    encounter_id       INTEGER NOT NULL REFERENCES encounter (id),
    round              INTEGER NOT NULL,
    actor_character_id INTEGER REFERENCES character (id),
    kind               TEXT    NOT NULL,
    summary            TEXT    NOT NULL,
    payload_json       TEXT,
    journal_id         INTEGER
);
CREATE INDEX idx_encounter_log_encounter ON encounter_log (encounter_id, id);
