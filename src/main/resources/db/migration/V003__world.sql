-- ============================================================================
-- V003 — world & narrative milestone.
--   * NPC agendas are GM-only character state (goals, fears, secrets, current plan)
--   * hit dice pools live in resource_state (no schema change); locations gain a "kind of place" tag list
-- ============================================================================

ALTER TABLE character
    ADD COLUMN agenda_json TEXT;

ALTER TABLE location
    ADD COLUMN tags_json TEXT;
