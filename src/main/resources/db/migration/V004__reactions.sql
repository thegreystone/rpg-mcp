-- Reactions and pending encounter choices (MCP_PROTOCOL.md §15.3/§15.4, DOMAIN_MODEL.md I-31/I-33).
ALTER TABLE encounter_participant
    ADD COLUMN reaction_used INTEGER NOT NULL DEFAULT 0;
ALTER TABLE encounter
    ADD COLUMN npc_reactions TEXT NOT NULL DEFAULT 'AUTO';
