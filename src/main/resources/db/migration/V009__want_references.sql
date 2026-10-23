-- Want references have their own immutable identity. The registry is deliberately
-- not rewindable and has no foreign keys into rewindable state: a restored-away
-- want must never lend its reference to a different want on the new branch.
-- The character JSON remains canonical for the want's text and current state.
CREATE TABLE want_identity
(
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    campaign_id  INTEGER NOT NULL,
    character_id INTEGER NOT NULL,
    kind         TEXT    NOT NULL CHECK (kind IN ('general', 'intimate')),
    legacy_note  TEXT COLLATE NOCASE,
    UNIQUE (character_id, kind, legacy_note)
);
CREATE INDEX idx_want_identity_owner ON want_identity (campaign_id, character_id, kind);

-- Existing campaigns keep their original JSON, including checkpoint before-images.
-- Immutable note mappings supply references when those older entries are read.
-- A legacy entry acquires a stored ref only when the entry is next updated.
WITH legacy(character_id, campaign_id, kind, profile_json) AS
         (SELECT id, campaign_id, 'general', biography_json FROM character
          UNION ALL
          SELECT id, campaign_id, 'intimate', intimacy_json FROM character
          UNION ALL
          SELECT COALESCE(json_extract(u.value, '$.row.id'), json_extract(u.value, '$.pk')),
                 COALESCE(json_extract(u.value, '$.row.campaign_id'), j.campaign_id),
                 'general', json_extract(u.value, '$.row.biography_json')
          FROM journal_entry j, json_each(j.undo_json) u
          WHERE json_extract(u.value, '$.table') = 'character'
          UNION ALL
          SELECT COALESCE(json_extract(u.value, '$.row.id'), json_extract(u.value, '$.pk')),
                 COALESCE(json_extract(u.value, '$.row.campaign_id'), j.campaign_id),
                 'intimate', json_extract(u.value, '$.row.intimacy_json')
          FROM journal_entry j, json_each(j.undo_json) u
          WHERE json_extract(u.value, '$.table') = 'character'),
     wants AS (SELECT l.character_id, l.campaign_id, l.kind,
                      trim(CASE WHEN w.type = 'text' THEN w.value
                                WHEN w.type = 'object' THEN json_extract(w.value, '$.note') END) AS note
               FROM legacy l, json_each(l.profile_json, '$.wants') w)
INSERT OR IGNORE INTO want_identity (character_id, campaign_id, kind, legacy_note)
SELECT character_id, campaign_id, kind, note
FROM wants
WHERE character_id IS NOT NULL AND campaign_id IS NOT NULL AND note IS NOT NULL AND note <> ''
ORDER BY character_id, kind, note;
