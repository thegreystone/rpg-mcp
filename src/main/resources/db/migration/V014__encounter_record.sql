-- Memorable DM narration accompanies the immutable mechanical action record.
ALTER TABLE encounter_log ADD COLUMN narration TEXT;
-- The ended fight and its one ledger digest can be recalled/annotated without replaying raw calls.
ALTER TABLE encounter ADD COLUMN record_json TEXT;
