-- ============================================================================
-- V005 — character origins milestone (SRD 5.2.1 backgrounds, species traits, feats).
--   * the free-text biography column is renamed: `background` now refers to the
--     mechanical SRD background; the prose lives in `backstory`
--   * `background_ref` holds the chosen background definition (installed content id)
--   * `once_per_turn_json` tracks once-per-turn feat usage (e.g. Savage Attacker)
-- ============================================================================

ALTER TABLE character RENAME COLUMN background TO backstory;
ALTER TABLE character
    ADD COLUMN background_ref TEXT;

ALTER TABLE encounter_participant
    ADD COLUMN once_per_turn_json TEXT;
