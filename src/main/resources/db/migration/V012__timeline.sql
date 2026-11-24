-- Preserve the legacy minute sequence, with an additive precise fraction for tactical/world events.
ALTER TABLE game_clock ADD COLUMN second_offset INTEGER NOT NULL DEFAULT 0 CHECK(second_offset BETWEEN 0 AND 59);
-- Historical effects retain minute expiry. Newly written timed effects preserve their second offset.
ALTER TABLE active_effect ADD COLUMN end_seconds INTEGER CHECK(end_seconds IS NULL OR end_seconds >= 0);

CREATE TABLE timeline_event (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    campaign_id INTEGER NOT NULL REFERENCES campaign(id),
    due_seconds INTEGER NOT NULL CHECK(due_seconds >= 0),
    kind TEXT NOT NULL,
    payload_json TEXT NOT NULL DEFAULT '{}',
    interrupting INTEGER NOT NULL DEFAULT 1 CHECK(interrupting IN (0,1)),
    visibility TEXT NOT NULL DEFAULT 'PARTY_KNOWN',
    region_id INTEGER REFERENCES location(id),
    remaining_seconds INTEGER,
    timer_active INTEGER NOT NULL DEFAULT 1 CHECK(timer_active IN (0,1)),
    status TEXT NOT NULL DEFAULT 'OPEN' CHECK(status IN ('OPEN','FIRED','CANCELLED')),
    revision INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX idx_timeline_event_due ON timeline_event(campaign_id,status,due_seconds,id);

CREATE TABLE action_progress (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    campaign_id INTEGER NOT NULL REFERENCES campaign(id),
    action_key TEXT NOT NULL,
    kind TEXT NOT NULL,
    required_seconds INTEGER NOT NULL CHECK(required_seconds > 0),
    completed_seconds INTEGER NOT NULL DEFAULT 0 CHECK(completed_seconds >= 0),
    parameters_json TEXT NOT NULL DEFAULT '{}',
    status TEXT NOT NULL DEFAULT 'OPEN' CHECK(status IN ('OPEN','PAUSED','COMPLETED','CANCELLED')),
    revision INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX idx_action_progress_open ON action_progress(campaign_id,action_key,status);

CREATE TABLE patrol_plan (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    campaign_id INTEGER NOT NULL REFERENCES campaign(id),
    name TEXT NOT NULL,
    actors_json TEXT NOT NULL,
    route_json TEXT NOT NULL,
    properties_json TEXT NOT NULL DEFAULT '{}',
    region_id INTEGER REFERENCES location(id),
    remaining_seconds INTEGER,
    timer_active INTEGER NOT NULL DEFAULT 1 CHECK(timer_active IN (0,1)),
    waypoint_index INTEGER NOT NULL DEFAULT 0,
    next_due_seconds INTEGER NOT NULL CHECK(next_due_seconds >= 0),
    status TEXT NOT NULL DEFAULT 'ACTIVE' CHECK(status IN ('ACTIVE','PAUSED','DONE')),
    revision INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX idx_patrol_plan_due ON patrol_plan(campaign_id,status,next_due_seconds);

CREATE TABLE travel_risk (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    campaign_id INTEGER NOT NULL REFERENCES campaign(id),
    scope_ref TEXT NOT NULL,
    policy_json TEXT NOT NULL,
    revision INTEGER NOT NULL DEFAULT 0,
    UNIQUE(campaign_id,scope_ref)
);

CREATE TABLE travel_journey (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    campaign_id INTEGER NOT NULL REFERENCES campaign(id),
    from_location_id INTEGER REFERENCES location(id),
    to_location_id INTEGER NOT NULL REFERENCES location(id),
    route_json TEXT NOT NULL,
    movers_json TEXT NOT NULL,
    total_seconds INTEGER NOT NULL CHECK(total_seconds > 0),
    elapsed_seconds INTEGER NOT NULL DEFAULT 0,
    exposure_seconds INTEGER NOT NULL DEFAULT 0,
    exposure_origin_seconds INTEGER NOT NULL DEFAULT 0,
    next_check_seconds INTEGER NOT NULL DEFAULT 14400,
    policy_json TEXT NOT NULL DEFAULT '{}',
    opportunity_json TEXT NOT NULL DEFAULT '{}',
    tactical_anchor_json TEXT NOT NULL DEFAULT '{}',
    status TEXT NOT NULL DEFAULT 'ACTIVE' CHECK(status IN ('ACTIVE','INTERRUPTED','COMPLETED','CANCELLED')),
    revision INTEGER NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX idx_travel_journey_active ON travel_journey(campaign_id) WHERE status IN ('ACTIVE','INTERRUPTED');

CREATE TABLE travel_exposure (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    campaign_id INTEGER NOT NULL REFERENCES campaign(id),
    scope_ref TEXT NOT NULL,
    exposure_seconds INTEGER NOT NULL DEFAULT 0,
    check_block_seconds INTEGER,
    outcome_json TEXT NOT NULL DEFAULT '{}',
    revision INTEGER NOT NULL DEFAULT 0,
    UNIQUE(campaign_id,scope_ref)
);

CREATE TABLE travel_check (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    campaign_id INTEGER NOT NULL REFERENCES campaign(id),
    journey_id INTEGER NOT NULL REFERENCES travel_journey(id),
    exposure_seconds INTEGER NOT NULL,
    outcome_json TEXT NOT NULL,
    UNIQUE(journey_id,exposure_seconds)
);
