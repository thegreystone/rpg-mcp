# RPG MCP — Setup Guide (topic: SETUP)

```text
session:STATE → session:CREATE
  campaign_setup:UPDATE (player_age, content_profile, experience, rules, continuation, adventure)
  character_draft:CREATE → character_draft:GENERATE_ABILITIES → character_draft:UPDATE → character_draft:COMMIT
  campaign_setup:PARTY_DESIGN → campaign_setup:VALIDATE → campaign_setup:COMMIT
  ── context boundary: the setup conversation is disposable; open a fresh chat and call session:BOOTSTRAP ──
```

## Interview Rules

- Setup is an interview, not a form. Every setup response (`session:CREATE`, `campaign_setup:GET`,
  `campaign_setup:UPDATE`) and character-draft response (`character_draft:CREATE`, `character_draft:UPDATE`,
  `character_draft:CHOICES` with a draft) carries `decisions` in interview order.
- Ask **`decisions[0]` only** — one question per turn — listing **every** option numbered with its description,
  plus a custom answer where `allow_custom` is true; record the answer at the decision's `tool`, `action` and
  `path`; then read the list again. Never invent or prune options, and never batch questions.
- For nearly every creative question, **"surprise me"** is valid (store `SURPRISE_ME`).
- Ask the player's age early and pass it at `params.changes.player_age` — only the derived content cap is stored,
  never the age.
- For optional questions recorded by `campaign_setup:UPDATE`, send JSON `null` at the answer's path to accept its
  default; omitted fields remain unanswered. An empty `experience: {}` or `rules: {}` object accepts that
  section's remaining optional defaults without replacing explicit choices.
- Mention that the default experience is a **Baldur's Gate-style fantasy epic** (`fantasy_style: EPIC`), written
  for PEGI 18 (recommended for adult players); skipping or "surprise me" selects it.

## Character Creation Drafts

- Use `character_draft:CREATE` (`initial`, `player_controlled: true` for PC, `false` for authored companions).
- For rolled ability methods (`ROLL_4D6_DROP_LOWEST`), call `character_draft:GENERATE_ABILITIES` first so the
  server rolls openly and returns every die; then assign with `character_draft:UPDATE`.
- `character_draft:UPDATE` accepts `name`, `description`, `appearance`, `personality`, `backstory`, `goals`,
  `alignment`, `age`, `presentation`, `species`, `class`, `background`, `background_ability_scores`,
  `ability_scores`, `skills`, `species_skill`, `species_choice`, `origin_feat`, `feat_choices`,
  `background_tool`, `starting_equipment`, `background_equipment`, `cantrips`, `spells`.
- Validate with `character_draft:VALIDATE`, read the sheet back in prose, and lock it in with
  `character_draft:COMMIT`.

## Party Design, Adventure & World Map

- Record companion preferences with `campaign_setup:PARTY_DESIGN` (`preferences` or `{"authorship": "SURPRISE_ME"}`).
- Author `adventure` via `campaign_setup:UPDATE` (`premise`, `background_truth` (GM_ONLY), `opening_location`,
  `immediate_goal`, optional `start_time`, `gm_notes`, and `world_map` from the premise, opening settlements,
  key roads and geography). If `adventure.world_map` is omitted, commit stages a sourced REGION preparation with
  `AUTHORING_REQUIRED` — review and author its geography before publishing (see topic `MAPS`).
- Validate with `campaign_setup:VALIDATE`, read the compact review back to the player, then commit atomically
  with `campaign_setup:COMMIT`. Afterward suggest starting a fresh conversation so play starts with a clean context.
