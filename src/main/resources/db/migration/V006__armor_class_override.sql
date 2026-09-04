-- ============================================================================
-- V006 — a fixed Armor Class recorded by audited GM override (apply_gm_override
-- kind SET_ARMOR_CLASS): subclass features the engine does not model yet
-- (Draconic Resilience, Unarmored Defense) and boons. NULL means "derive from
-- equipment as usual"; effect bonuses and floors still apply on top.
-- ============================================================================

ALTER TABLE character
    ADD COLUMN armor_class_override INTEGER;
