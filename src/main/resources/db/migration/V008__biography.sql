-- ============================================================================
-- V008 — biography and intimacy on the character, and the chronicle
-- (DATABASE.md §3.3, §3.12; MCP_PROTOCOL.md §11.6, §12.7).
-- ============================================================================

-- What a client needs to play a person convincingly after a context reset and
-- has nowhere to keep today: dated life events, verbatim lines, long-running
-- bodily state, permanent marks, and the drives the person is working toward.
-- {"timeline": [{game_time, note}], "voice": [..], "state": [{note, since,
-- until}], "marks": [..], "wants": [{note, with, since, status}]}.
ALTER TABLE character
    ADD COLUMN biography_json TEXT;

-- The character's own intimate profile (PEGI_18 campaigns only; refused and
-- never returned below that): body, likes, dislikes, limits, hard lines,
-- wants [{note, with, since, status}] (the drives that concern intimacy),
-- household_terms [{note, with}], voice_in_bed. Pairwise preferences stay on
-- relationship.profile_json.
ALTER TABLE character
    ADD COLUMN intimacy_json TEXT;

-- Temporary changes to the hit point maximum (a life-drained touch, Aid) are
-- active_effect rows with a signed {"max_hp": n} modifier and a duration
-- ({"until": "LONG_REST"}, {"until": "RESTORED"} or a timed expiry); no
-- column is needed: the effective maximum is max_hp plus the merged modifier.

-- ---------------------------------------------------------------------------
-- 3.12 The chronicle: chapters and the synopsis
-- ---------------------------------------------------------------------------

-- A CHAPTER is an immutable prose summary of a span of the ledger, written by a
-- summarizer from the raw events; a SYNOPSIS is the rolling "story so far",
-- rewritten from the previous synopsis and the chapters since. Both record the
-- game time they cover and the game time they were written at, so a chapter
-- written mid-session closes exactly where its material was cut.
--   from_journal_id / to_journal_id   the ledger span covered (exclusive / inclusive)
--   from_seq / to_seq                 the game-time span covered
--   written_seq                       the game clock when written
--   covers_json                       a synopsis: the chapter ids it was built from
--   superseded                        a synopsis replaced by a newer one
CREATE TABLE chronicle
(
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    campaign_id     INTEGER NOT NULL REFERENCES campaign (id),
    kind            TEXT    NOT NULL CHECK (kind IN ('CHAPTER', 'SYNOPSIS')),
    title           TEXT,
    summary         TEXT    NOT NULL,
    from_journal_id INTEGER NOT NULL,
    to_journal_id   INTEGER NOT NULL,
    from_seq        INTEGER,
    from_time       TEXT,
    to_seq          INTEGER,
    to_time         TEXT,
    written_seq     INTEGER NOT NULL,
    written_time    TEXT    NOT NULL,
    written_at      TEXT    NOT NULL,
    events_covered  INTEGER NOT NULL DEFAULT 0,
    chars_covered   INTEGER NOT NULL DEFAULT 0,
    covers_json     TEXT,
    superseded      INTEGER NOT NULL DEFAULT 0 CHECK (superseded IN (0, 1))
);
CREATE INDEX idx_chronicle_campaign ON chronicle (campaign_id, kind, superseded, id);
