# RPG MCP — Narrative Director Guide (topic: DIRECTOR)

Invoke the slow Narrative Director loop only at meaningful boundaries (session start, after major quests, big time
jumps, broken plans, or when a `director_trigger` fires) — never every turn.

## Running a Director Review

1. Read `narrative:DIRECTOR_CONTEXT` (`scope: INITIAL_CAMPAIGN|SESSION_START|CAMPAIGN_REVIEW`). This is the
   `DIRECTOR_ONLY` view carrying premise, background truth, open seeds, story beats (including blocked/invalidated
   plans), quests, factions, world events, party, relationships, NPC agendas, recent major events,
   `companion_wants` and pacing.
2. Commit atomic proposals via `narrative:COMMIT_DIRECTOR` (`changes`: list of `{kind, ref?, ...fields}`, optional
   `review_note`). Legal kinds: `STORY_SEED`, `PRESSURE`, `PACING_INTENT`, `COMPANION_INTRO`, `STORY_BEAT`, `QUEST`,
   `FACTION_STATE`, `WORLD_EVENT`, `LOCATION_DETAIL`, `NPC_AGENDA`.
   - Seeds and pressures create possibilities; world events create facts with diegetic channels (`NEWSPAPER`,
     `RUMOR`, `TAVERN`, `TOWN_CRIER`, `REFUGEES`, `MERCHANT`, `PRICES`, `SOLDIERS`, `LETTER`, `WITNESS`,
     `ENVIRONMENT`). Nothing may prescribe a player choice or relationship outcome.
   - An empty `changes: []` review is valid when existing published guidance still works — preserve valid guidance
     and record the review note.
3. **Companion wants & milestones:** A `director_trigger` with `WANT_CLOSED`, `INTIMATE_MILESTONE` or
   `RELATIONSHIP_MILESTONE` means a person's drives moved. Inspect `companion_wants` (open wants and wants closed
   since the last review) and propose a next want in character (`character:UPDATE` with `origin: director`) so no
   present companion sits with all wants `DONE`, and keep `NPC_AGENDA` plans in step. Closed wants retain `done_at`.

## Director-to-GM Handoff (`STORY_BEAT`)

- When the GM needs actionable direction, publish `STORY_BEAT` proposals with `visibility: GM_ONLY`.
- Write a concise string `gm_brief` (hook + plausible approaches) and fill in the beat's full details: `location`,
  `actors` / intentions, `conditions`, discoverable `clues`, `approaches`, `consequences`, and what develops
  `if_ignored`.
- Mark a beat `AVAILABLE` only when it is usable in the current fiction; conditions are evaluated manually.
  Update or supersede beats when events change the situation.
- Keep long-term seeds and private possibilities `DIRECTOR_ONLY`. A secret the GM needs may be `GM_ONLY`; promote
  to `PARTY_KNOWN` only after the party actually learns it.
- **After every Director review and before narrating:** refresh with `narrative:CONTEXT` (`scope: SCENE`), then
  retrieve relevant full beats via `narrative:CONTEXT` (`scope: STORY_BEAT, ref: story_beat:N`). Bootstrap returns
  the same compact `story_beats` guidance in a fresh conversation (`gm_brief_truncated: true` means the full text
  should be fetched by ref). Play NPCs toward their intentions, offer discoverable hooks, accept unexpected
  approaches or ignored hooks, record what changes (`narrative:UPSERT`), and feed it into the next review.

## Narrative Aggregate Fields (`narrative:UPSERT`)

Pass `ref` (`quest:N`, `story_beat:N`, `seed:N`, `faction:N`, `world_event:N`, `location:N`, `character:N`) to update
an existing record; omit to create (except `LOCATION_DETAIL` and `NPC_AGENDA`, which require an existing ref):
- `QUEST`: `{title, objective, status?: OFFERED|ACCEPTED|COMPLETED|FAILED|ABANDONED, issuer?, rewards?, deadline?,
  hidden_objectives?, visibility?}` (completing returns `treasure` suggestions for `inventory:GRANT_LOOT`)
- `STORY_BEAT`: `{title, description, gm_brief?, state?: PLANNED|AVAILABLE|BLOCKED|SUPERSEDED|COMPLETED|ABANDONED,
  superseded_by?, visibility?, location?, actors?, conditions?, clues?, approaches?, consequences?, if_ignored?}`
- `STORY_SEED`: `{seed_kind?: STORY_SEED|COMPANION_INTRO|PRESSURE|PACING_INTENT, intention, hooks?, conditions?,
  state?, superseded_by?, materialized_character?}`
- `FACTION_STATE`: `{name, goals?, standing?, agenda?, members?, secrets?}`
- `WORLD_EVENT`: `{title, description, channels?: [...], locations?: [location:N], affected?, game_time?,
  visibility?}` (omitted/null preserves groups; `[]` clears that group)
- `LOCATION_DETAIL`: `{description?, tags?, features?}` (create new places via `world_travel:MATERIALIZE_LOCATION`)
- `NPC_AGENDA`: `{goals?, fears?, secrets?, current_plan?}`
