# RPG MCP — Combat & Spellcasting Guide (topic: COMBAT)

## Running an Encounter

1. Materialize every opponent first (`character:MATERIALIZE`). Every new combat uses a local grid and established
   figurine placements (`map_play:PLACE_ACTORS` / `encounter:START` `options.placements` or `options.scene`).
   A plain `encounter:START` refuses participants established on different boards — resolve positions first.
2. Call `encounter:START` with `sides`, optional `stances`, `zones`, `environment`, `objectives`, `location` and
   `options` (`map_ref`, `npc_reactions: AUTO|ASK`, `surprised`, `placements`, `scene`). Under non-Ironman policies
   this saves an `ENCOUNTER_RETRY` checkpoint. Use `encounter:ADD_PARTICIPANTS` only for existing physically present
   actors joining mid-fight (never spawns reinforcements). Use `encounter:CONVERT_TO_GRID` only for legacy zone fights.
3. Call `encounter:ACTION` once per action for whoever's turn it is. Action kinds:
   - `ATTACK` `{target, weapon|attack?, ammunition?, thrown?|mode: RANGED?, two_handed?, light_extra_attack?, advantage?, nonlethal?, confirm_hostile_ally?}`
   - `CAST` `{spell, targets|target?, slot_level?, center_ft?|direction_ft?, metamagic?, heightened_target?, careful?, damage_type?}`
   - `MOVE` / `DASH` `{x_ft, y_ft, path_ft?}` or `{portal: key}` for transitions under initiative (Prone crawls)
   - `STAND` (costs half Speed, movement not Action; refused at Speed 0)
   - `DODGE`, `DISENGAGE`, `HELP`, `USE_ITEM` `{item, target?}` (potions cost Bonus Action), `INTERACT` /
     `OTHER_RULES_ACTION` `{description}`, `END_TURN`
   - `HIDE` returns `CAPABILITY_UNAVAILABLE` (automatic concealment is not implemented); adjudicate Stealth via
     `roll_check:CHECK` and explicit GM handling instead of claiming an engine-hidden actor.
4. **Turn economy:** Each turn has one Action; only remaining attacks within that Attack action or a separately paid
   Light Bonus Action permit further attacks. `MOVE`, `STAND` and Bonus Action casts leave the turn open by default;
   other actions default `end_turn: true`. Explicit `end_turn` overrides; `END_TURN` always passes. While open,
   inspect `turn_economy` and `next_step` — ask the player about a leftover only when it genuinely matters and they
   may not know the rule, otherwise `END_TURN` quietly.
5. **Care guidance & nonlethal:** Ordinary attacks on downed same-side members return care guidance; pass
   `confirm_hostile_ally: true` only for explicitly intended hostility. Melee opportunity-attack `TAKE` choices may
   include `nonlethal: true`.
6. **Audience & privacy:** Use `encounter:STATE` with `audience: GM` when running enemy turns (or following
   `gm_handoff`); `PLAYER` is the safe default and filters unseen actors. Read automatic `death_saves` and
   player-death guidance in replies; unseen NPC outcomes remain GM-only.

## Reactions (`pending_choices`)

When an action or `encounter:STATE` lists `pending_choices`, the encounter pauses (`WAITING_CHOICE`) until every
open choice is resolved via `encounter:RESOLVE_CHOICE` (`transaction`, `choice: {option: TAKE|DECLINE|CAST_SHIELD,
weapon?, nonlethal?}`). Under default `npc_reactions: AUTO`, player-controlled characters (including companions
under player tactical control) prompt the player while DM-controlled NPC reactions resolve automatically; under
`ASK`, choose DM-controlled NPC reactions yourself and ask the player for theirs.

## Ending, Digests & Narration

- Call `encounter:END` (`outcome`, optional `encounter`, `summary` headline, `narrative` full account as told to
  the player). It awards the encounter XP pool, retains figures and reports `party_positions` and `current_board`,
  writes one mechanical digest in the ledger, and moves the harness to continuation if the PC died.
- Pass optional `action.narration` only for memorable beats (~300 chars, rarely >8 per fight). `encounter:END`
  returns a bounded `party_digest` (2,000-byte compact UTF-8 budget), a `full_gm_record` pointer (`encounter:STATE`
  `audience: GM`, field `record`), and `highlights_needing_narration` slots to fill in one `encounter:ANNOTATE` call
  (`narrations: {"log:123": "..."}`). Quote the table voice; do not add hidden GM facts. Retrieve `full_gm_record`
  privately when full mechanics are needed; ended reads use saved end-time knowledge from any board.

## Ammunition Recovery

`encounter:END` and `encounter:STATE` may offer `ammunition_recovery` (half total fired pieces rounded down, once
per shooter per encounter). Ask whether the player wants to spend one minute searching and call
`encounter:RECOVER_AMMO` only if requested/agreed (confirm battlefield accessibility; shooter alive and able at
location, outside combat). Omit `selection` for a random half weighted by piece count without replacement; explicit
`selection` supplies `{group, quantity}` summing to `recoverable`. Standard +N hits return mundane pieces; misses
keep magic. Any `recover_as: GM` candidate sets `selection_required: true` — pass explicit `selection` with
`recover_as: ORIGINAL|MUNDANE|LOST` (`LOST` counts toward quota). Never guess custom magic's fate, recover
automatically, or grant replacements by hand.

## Spellcasting & Metamagic

- Look up spells with `rules:DEFINITIONS` (`kind: SPELL`). Prepare with `spellcasting:PREPARE`; cast out of
  combat with `spellcasting:CAST` (`caster`, `spell`, `slot_level?`, `targets?`, `options?: {ritual?, damage_type?,
  against?, condition?, weapon?, metamagic?, heightened_target?, careful?}`) or in combat via `encounter:ACTION`
  `CAST`. On a grid, supply area `center_ft` or `direction_ft` — supported areas use canonical geometry to select
  every affected creature (never omit allies/foes for convenience).
- Self-centred area spells (`Spirit Guardians`, `Thunderwave`) can be cast with no targets; creatures entering
  later save via `roll_check:CHECK` against the returned `save_dc`.
- Casting uses class preparation or creature grants (`character:SHEET` `spellcasting.grants`; e.g. Priest Acolyte's
  Divine Aid shares one saved use among Bless, Healing Word and Sanctuary as a Bonus Action).
- Weapon-enchanting spells (`Magic Weapon`, `Shillelagh`) take `options.weapon` and stack with the weapon's +N;
  `Flame Blade` is attacked with by name. Sorcerer Metamagic is paid automatically via `metamagic`; Font of Magic
  conversions use `character:RUNTIME_CHANGE` (`CREATE_SPELL_SLOT` / `CONVERT_SPELL_SLOT`).
