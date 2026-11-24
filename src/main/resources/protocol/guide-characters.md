# RPG MCP — Characters, Relationships & Chronicle Guide (topic: CHARACTERS)

## People Are More Than Sheets (`character:UPDATE`)

- Record who people are as facts emerge in play via `character:UPDATE`: `name`, `description`, `appearance`,
  `personality`, `backstory`, `goals`, `age`, `presentation`, `alignment`, plus two deep-merged aggregates:
  - `biography`: `timeline` (`[{game_time?, note}]`), `voice` (verbatim lines), `state` (`[{note, since?, until?}]`
    — e.g. pregnancy or an arm in a sling; add `until` to end it), `marks`, and `wants`:
    `[{ref?, note?, with?: [refs], status: OPEN|DONE|ABANDONED, resolved_by?: event:N}]`.
  - `intimacy` (**`PEGI_18` only**): `body`, `likes`, `dislikes`, `limits`, `hard_lines`, `wants` (same referenced
    entries), `household_terms` (`[{note, with: [refs]}]`), `voice_in_bed`.
- Open wants ride in every party list as `{ref, note}` (`want:N`) — play every NPC toward theirs in every scene.
  Retrieve full want details with `narrative:CONTEXT` (`scope: WANT, ref: want:N`) or locate a ref by note with
  `chronicle:FIND` (`kind: WANT`). Patch an existing want by `ref` with only the changed fields (`{ref: 'want:8',
  status: 'DONE', resolved_by: 'event:42'}`).
- Lists append without duplicates and maps merge key by key; `null` map values remove keys; `{replace: true}`
  starts over; null list entries are rejected with `INVALID_ARGUMENT`. Mechanical state is never changed here.
- **Intimate scenes:** Before playing an intimate scene, call `narrative:CONTEXT` (`scope: INTIMACY, ref:
  character:N`, optional `second_ref`) to pull biography, voice, state, open wants, pairwise relationship profiles,
  household terms and intimate ledger events so the scene flows from established truth.
- Never guess refs — look up characters, locations or wants by name/note with `chronicle:FIND`.

## Relationships (`narrative:GET_RELATIONSHIP` / `UPDATE_RELATIONSHIP`)

- Relationships are gameplay: after a meaningful moment, record it with `chronicle:RECORD_MEMORY`, then call
  `narrative:UPDATE_RELATIONSHIP` with `cause_event` so it becomes a retrievable shared memory.
- Dimensions (`affection`, `trust`, `respect`, `attraction`, `fear`, `resentment`, `loyalty`, -5..+5) accept signed
  string deltas (`"+1"`) or absolute numbers. Pass `mutual: true` to update both directions.
- Keep `summary` and `profile` current (`milestones`, `terms`, `preferences: {likes, dislikes, limits}`, `wants`,
  `hard_lines`; `profile_mode: MERGE` default or `REPLACE`). Never turn a Director seed into a forced outcome.

## Out-of-Combat Mechanical Changes (`character:RUNTIME_CHANGE`)

Use `character:RUNTIME_CHANGE` (`character`, `change`) for named, rules-aware changes outside combat:
`HEAL {amount}`, `USE_ITEM {item, target?}`, `DAMAGE {amount|dice, damage_type?}` (applies death rules),
`SET_TEMP_HP {amount}`, `ADD_CONDITION` / `REMOVE_CONDITION` (`condition`, `duration` — minutes, `'3 rounds'`,
`'1 minute'`, `'1 hour'`, `'long rest'`, `'start of turn'`, or `{rounds|minutes|hours|until}`, optional `source`,
`EXHAUSTION` accumulates/removes 1 level or `{levels: N}`), `STABILIZE`, `STAND` (outside combat), `USE_RESOURCE` /
`RESTORE_RESOURCE` (`spell_slot:<level>` or `pact_slot`), `CREATE_SPELL_SLOT` / `CONVERT_SPELL_SLOT`,
`ADJUST_MAX_HP {amount, until?: LONG_REST|RESTORED, minutes?}`, `RESTORE_MAX_HP`. Always include `reason`.

## Party Membership, XP & Level-Up Transactions

- Change membership via `character:MEMBERSHIP` (`JOIN|LEAVE|DISMISS|SEPARATE|REJOIN|GUEST_ADD|GUEST_REMOVE`).
  `former_members` tracks whereabouts of departed members; `with_party` governs default `world_travel:MOVE_PARTY`.
- Award non-combat XP via `character:AWARD_XP` (omit `characters` to follow `rules.xp_policy`; encounter XP is
  automatic on `encounter:END`; check `companion_level_ups` / `companions_awaiting_level_up`).
- Level-ups are transactional: `level_up:BEGIN` → `level_up:CHOICES` → present choices → `level_up:UPDATE` →
  `level_up:VALIDATE` → `level_up:COMMIT` (or `level_up:ABANDON`). Hit points are fixed by `rules.hp_progression`
  at `BEGIN` (abandoning and restarting retains the original hit die). A stat-block companion's first level-up is
  their class promotion (`choices.class`, plus species/background/skills/feat follow-ups).

## Chronicle Chapters & Synopses

When `chronicle.due` (or `CHAPTER_DUE` / `SYNOPSIS_DUE` in `consequences` or `meta.warnings`) indicates a summary
is owed, do not write it inline during narration — delegate a fresh summarizing agent that calls
`chronicle:GET_MATERIAL` (`kind: CHAPTER|SYNOPSIS`, paging via `cursor`), writes strictly from the ledger in the
campaign's voice at target length (~300 tokens / <=3,000 chars for `CHAPTER`; ~1,500 tokens / <=12,000 chars for
`SYNOPSIS`), and commits with `chronicle:WRITE` using the returned `through` marker while play continues. Inline
prose may cite `want:N` references. `session:SUSPEND` summaries also write a `CHAPTER`.
