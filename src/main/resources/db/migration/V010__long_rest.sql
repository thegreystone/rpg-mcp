-- ============================================================================
-- V010 — the game time (clock sequence, minutes) at which a character last
-- finished a Long Rest: "After you finish a Long Rest, you must wait at least
-- 16 hours before starting another one" (SRD 5.2.1 "Long Rest"). NULL means
-- the character has never taken one.
-- ============================================================================

ALTER TABLE character
    ADD COLUMN last_long_rest_seq INTEGER;
