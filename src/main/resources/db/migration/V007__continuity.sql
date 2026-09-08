-- ============================================================================
-- V007 — continuity milestone: session-stamped events and a derived recap,
-- house rules, a real calendar, richer relationships, and an economy of
-- accounts and scheduled cash flows (DATABASE.md §3.9, MCP_PROTOCOL.md §16).
-- ============================================================================

-- Every event remembers the session it was written in, so bootstrap can build
-- "since you last played" from the ledger instead of a hand-written summary.
ALTER TABLE event
    ADD COLUMN session_id INTEGER REFERENCES session (id);
CREATE INDEX idx_event_session ON event (campaign_id, session_id);

-- Table rulings that every client must see at bootstrap (a JSON list of
-- strings): "no firearms", "the never-kill rule does not cover monsters".
ALTER TABLE campaign
    ADD COLUMN house_rules_json TEXT;

-- The calendar: a 365-day year (12 months, no leap years, 7-day weeks, four
-- seasons derived from the month). Day 1 of the campaign maps to the epoch
-- date stored here; {"year": 1, "month": 3, "day": 1}. NULL means the plain
-- day clock only.
ALTER TABLE campaign
    ADD COLUMN calendar_json TEXT;

-- Relationships carry more than dimensions and a summary: dated milestones,
-- standing terms between the two, preferences and limits, wants, hard lines.
ALTER TABLE relationship
    ADD COLUMN profile_json TEXT;

-- ---------------------------------------------------------------------------
-- 3.9 Economy: accounts and cash flows
-- ---------------------------------------------------------------------------

-- A money bag that is not a character: an estate or faction treasury.
-- Character purses stay on character.money_cp; a cash flow may name either.
CREATE TABLE account
(
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    campaign_id INTEGER NOT NULL REFERENCES campaign (id),
    name        TEXT    NOT NULL,
    owner_kind  TEXT    NOT NULL CHECK (owner_kind IN ('FACTION', 'ESTATE', 'CHARACTER', 'OTHER')),
    owner_id    INTEGER,
    money_cp    INTEGER NOT NULL DEFAULT 0,
    notes       TEXT,
    revision    INTEGER NOT NULL DEFAULT 0,
    UNIQUE (campaign_id, name)
);
CREATE INDEX idx_account_campaign ON account (campaign_id);

-- A recurring or one-off transfer, or a dated event, evaluated by the
-- scheduler whenever the clock crosses its next due point.
--   kind          MONEY (moves coin) or EVENT (writes a WORLD_EVENT only)
--   from/to       ACCOUNT:n, CHARACTER:n or WORLD (the outside world)
--   amount_json   {"fixed_cp": n} | {"percent": p, "of_rule": cash_flow id}
--                 | {"percent": p, "of_inflows": "ACCOUNT:n"|"CHARACTER:n"}
--   schedule_json {"kind": "ONCE"|"DAILY"|"WEEKLY"|"MONTHLY"|"YEARLY"|"SEASONAL",
--                  "weekday": 1-7, "day": 1-31, "month": 1-12, "season": "AUTUMN", "minute_of_day": 0-1439}
--   season_json   {"SPRING": 1.0, "SUMMER": 1.0, "AUTUMN": 1.0, "WINTER": 0.2} (multipliers, optional)
--   condition_json {"quest": "quest:n", "status": "ACCEPTED"} | {"flag": "..."} (optional)
--   next_due_seq  the next clock value at or after which the flow fires; NULL when finished
CREATE TABLE cash_flow
(
    id             INTEGER PRIMARY KEY AUTOINCREMENT,
    campaign_id    INTEGER NOT NULL REFERENCES campaign (id),
    name           TEXT    NOT NULL,
    kind           TEXT    NOT NULL CHECK (kind IN ('MONEY', 'EVENT')),
    from_ref       TEXT,
    to_ref         TEXT,
    amount_json    TEXT,
    schedule_json  TEXT    NOT NULL,
    season_json    TEXT,
    condition_json TEXT,
    description    TEXT,
    start_seq      INTEGER NOT NULL DEFAULT 0,
    end_seq        INTEGER,
    next_due_seq   INTEGER,
    active         INTEGER NOT NULL DEFAULT 1 CHECK (active IN (0, 1)),
    created_event_id INTEGER REFERENCES event (id),
    revision       INTEGER NOT NULL DEFAULT 0,
    UNIQUE (campaign_id, name)
);
CREATE INDEX idx_cash_flow_due ON cash_flow (campaign_id, active, next_due_seq);

-- One firing of a cash flow: what was due, what moved, and the ledger event.
CREATE TABLE cash_flow_run
(
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    cash_flow_id INTEGER NOT NULL REFERENCES cash_flow (id),
    due_seq      INTEGER NOT NULL,
    amount_cp    INTEGER NOT NULL DEFAULT 0,
    status       TEXT    NOT NULL CHECK (status IN ('PAID', 'UNPAID', 'SKIPPED', 'FIRED')),
    event_id     INTEGER REFERENCES event (id),
    note         TEXT,
    UNIQUE (cash_flow_id, due_seq)
);
