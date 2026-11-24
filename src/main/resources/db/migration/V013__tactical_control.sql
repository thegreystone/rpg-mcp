-- Tactical decision control is independent from the identity of the player's primary character.
CREATE TABLE tactical_control (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    campaign_id INTEGER NOT NULL REFERENCES campaign(id),
    character_id INTEGER REFERENCES character(id),
    encounter_id INTEGER REFERENCES encounter(id),
    controller TEXT NOT NULL CHECK(controller IN ('DM','PLAYER')),
    scope TEXT NOT NULL CHECK(scope IN ('ENCOUNTER','UNTIL_RELEASED')),
    reason TEXT,
    revision INTEGER NOT NULL DEFAULT 0,
    CHECK((scope='ENCOUNTER' AND encounter_id IS NOT NULL) OR (scope='UNTIL_RELEASED' AND encounter_id IS NULL))
);
-- NULL character is a policy for all current and future companions in this scope.
CREATE UNIQUE INDEX idx_tactical_control_target ON tactical_control(campaign_id,COALESCE(encounter_id,0),COALESCE(character_id,0));
CREATE INDEX idx_tactical_control_encounter ON tactical_control(campaign_id,encounter_id);
