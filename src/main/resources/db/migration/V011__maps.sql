-- Canonical campaign maps, staged authoring and atlas identity.
-- Small live placements/observations are independent from large immutable layout documents.

CREATE TABLE map_preparation (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    campaign_id INTEGER NOT NULL REFERENCES campaign(id),
    location_id INTEGER REFERENCES location(id),
    kind TEXT NOT NULL CHECK(kind IN ('DUNGEON','SCENE','REGION')),
    stage TEXT NOT NULL CHECK(stage IN ('BLUEPRINT','SCENE','REGION')),
    status TEXT NOT NULL DEFAULT 'DRAFT' CHECK(status IN ('DRAFT','ACCEPTED','PUBLISHED')),
    name TEXT NOT NULL,
    payload_json TEXT NOT NULL,
    commitments_json TEXT NOT NULL DEFAULT '{}',
    source_revisions_json TEXT NOT NULL DEFAULT '{}',
    reference_map_json TEXT NOT NULL DEFAULT '{}',
    published_map_id INTEGER,
    revision INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX idx_map_preparation_campaign ON map_preparation(campaign_id,status);

CREATE TABLE map_atlas (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    campaign_id INTEGER NOT NULL REFERENCES campaign(id),
    name TEXT NOT NULL,
    sheet_width_ft REAL NOT NULL CHECK(sheet_width_ft > 0),
    sheet_height_ft REAL NOT NULL CHECK(sheet_height_ft > 0),
    origin_latitude REAL NOT NULL DEFAULT 43.5,
    origin_longitude REAL NOT NULL DEFAULT 0,
    world_radius_m REAL NOT NULL DEFAULT 6371000 CHECK(world_radius_m > 0),
    style_json TEXT NOT NULL DEFAULT '{}',
    climate_json TEXT NOT NULL DEFAULT '{}',
    revision INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX idx_map_atlas_campaign ON map_atlas(campaign_id);

CREATE TABLE map_scene (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    campaign_id INTEGER NOT NULL REFERENCES campaign(id),
    location_id INTEGER REFERENCES location(id),
    preparation_id INTEGER REFERENCES map_preparation(id),
    atlas_id INTEGER REFERENCES map_atlas(id),
    sheet_x INTEGER,
    sheet_y INTEGER,
    name TEXT NOT NULL,
    kind TEXT NOT NULL CHECK(kind IN ('DUNGEON','SCENE','REGION','SETTLEMENT')),
    level INTEGER NOT NULL DEFAULT 0,
    document_json TEXT NOT NULL,
    layout_revision INTEGER NOT NULL DEFAULT 0,
    revision INTEGER NOT NULL DEFAULT 0,
    UNIQUE(atlas_id,sheet_x,sheet_y)
);
CREATE INDEX idx_map_scene_location ON map_scene(campaign_id,location_id);
CREATE INDEX idx_map_scene_preparation ON map_scene(campaign_id,preparation_id);

CREATE TABLE map_actor (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    campaign_id INTEGER NOT NULL REFERENCES campaign(id),
    scene_id INTEGER NOT NULL REFERENCES map_scene(id),
    character_id INTEGER NOT NULL REFERENCES character(id),
    x_ft REAL NOT NULL,
    y_ft REAL NOT NULL,
    level INTEGER NOT NULL DEFAULT 0,
    footprint_ft REAL NOT NULL DEFAULT 5 CHECK(footprint_ft > 0),
    revision INTEGER NOT NULL DEFAULT 0,
    UNIQUE(campaign_id,character_id)
);
CREATE INDEX idx_map_actor_scene ON map_actor(campaign_id,scene_id);

CREATE TABLE map_object (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    campaign_id INTEGER NOT NULL REFERENCES campaign(id),
    preparation_id INTEGER REFERENCES map_preparation(id),
    scene_id INTEGER REFERENCES map_scene(id),
    object_key TEXT NOT NULL,
    entity_ref TEXT,
    room_id INTEGER REFERENCES location(id),
    placement_json TEXT NOT NULL DEFAULT '{}',
    properties_json TEXT NOT NULL DEFAULT '{}',
    revision INTEGER NOT NULL DEFAULT 0,
    UNIQUE(preparation_id,object_key)
);
CREATE INDEX idx_map_object_scene ON map_object(campaign_id,scene_id);

CREATE TABLE map_observation (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    campaign_id INTEGER NOT NULL REFERENCES campaign(id),
    scene_id INTEGER NOT NULL REFERENCES map_scene(id),
    observer_key TEXT NOT NULL,
    knowledge_json TEXT NOT NULL DEFAULT '{}',
    revision INTEGER NOT NULL DEFAULT 0,
    UNIQUE(scene_id,observer_key)
);
CREATE INDEX idx_map_observation_campaign ON map_observation(campaign_id,scene_id);

CREATE TABLE map_feature (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    campaign_id INTEGER NOT NULL REFERENCES campaign(id),
    atlas_id INTEGER NOT NULL REFERENCES map_atlas(id),
    map_id INTEGER NOT NULL REFERENCES map_scene(id),
    feature_key TEXT NOT NULL,
    kind TEXT NOT NULL,
    geometry_json TEXT NOT NULL,
    properties_json TEXT NOT NULL DEFAULT '{}',
    graph_ref TEXT,
    revision INTEGER NOT NULL DEFAULT 0,
    UNIQUE(atlas_id,feature_key)
);
CREATE INDEX idx_map_feature_map ON map_feature(campaign_id,map_id);

CREATE TABLE map_asset (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    campaign_id INTEGER NOT NULL REFERENCES campaign(id),
    content_hash TEXT NOT NULL,
    mime_type TEXT NOT NULL,
    width INTEGER NOT NULL CHECK(width > 0),
    height INTEGER NOT NULL CHECK(height > 0),
    repeat_ft REAL NOT NULL DEFAULT 5 CHECK(repeat_ft > 0),
    revision INTEGER NOT NULL DEFAULT 0,
    UNIQUE(campaign_id,content_hash,repeat_ft)
);
