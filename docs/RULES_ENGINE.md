# RPG MCP Server — Rules Engine

**Status:** Working document; each content kind's schema is pinned before the milestone that imports it.
**Purpose:** Which rules are engine code, which are data, which are GM judgment, and the formulas the engine hard-codes.
**Source of rules:** SRD 5.2.1 (CC-BY-4.0). The SRD text is authoritative; this document records the boundary, the
encoded formulas and citations, not the rules themselves.

---

## 1. Verification Protocol

Every encoded rule cites its SRD 5.2.1 section (and page in the reference PDF); the test covering it cites the same.
A rule without a citation is a house rule and must be listed in §6.

Sections marked **[verify]** were not read against the SRD text during design and MUST be verified at encoding time,
never assumed from prior-edition knowledge. Read directly during design: Equipment (pp. 89–103), Sample Poisons
(pp. 197–198), Sorcerer (pp. 64–65).

---

## 2. The Three-Way Rules Boundary

> Arithmetic or state is **engine code**. A table is **data**. Judgment is the **GM**, with the engine validating what
> it can.

### 2.1 Engine-coded (deterministic procedures)

- Dice expressions and all authoritative randomness (§4).
- D20 Tests: ability checks, skill checks, saving throws, attack rolls (§5).
- Damage application, resistance/vulnerability/immunity, HP and temp HP, dropping to 0 HP, death saving throws,
  knock-outs and the hit point maximum (SRD 5.2.1 "Damage and Healing"; the formulas in §3).
- Initiative, round and turn order (one roll per group of identical creatures on a side; ties are left to the table,
  §3), and effect durations counted in rounds, which become clock minutes when the encounter ends (§3).
- Resource accounting: spell slots, class resources, item charges, ammunition.
  Ammunition recovery is an optional one-minute search after combat, requested or accepted by the player
  (`recover_ammunition`, `MCP_PROTOCOL.md` §15.6; SRD 5.2.1 Equipment, "Ammunition"). The engine returns half the
  shooter's recorded fired pieces rounded down across all types, once per shooter per encounter, and journals
  inventory, elapsed time and the search marker together. The GM confirms access to the battlefield. By default the
  engine chooses a random half, weighted by piece counts without replacement; explicit selection overrides the draw
  and must meet the same global quota. Unknown `GM` recovery policies require explicit selection and adjudication
  before any draw or write. The result reports `method: RANDOM|EXPLICIT`; retrying does not reroll.
- Rests and what they restore (SRD 5.2.1 "Short Rest" / "Long Rest"). Either rest needs at least 1 HP: a named 0-HP
  character is refused (`OPERATION_NOT_ALLOWED`); in a party-wide rest that character's entry is
  `{character, name, skipped: <reason>, hp}` and nothing changes for them. A Short Rest heals `max(1, roll + CON)`
  per Hit Point Die spent and ends effects with `until: SHORT_REST` (`effects_ended`). A Long Rest regains all lost
  HP and all spent Hit Point Dice (the pool is `recharge: LONG_REST`), ends both `LONG_REST` and `SHORT_REST`
  effects, clears temporary HP, removes one Exhaustion level, and cannot start within 16 hours of the previous one's
  end (`character.last_long_rest_seq`, migration V010).
- Conditions: applying, removing, stacking, immunities, and their mechanical modifiers (the rules text is data; the
  arithmetic is code; the attack-roll table is in §3).
- Encumbrance and carrying capacity. **[verify: SRD "Carrying Capacity"]**
- XP accrual, level thresholds, proficiency bonus, level-up validation.
- Money in canonical copper; buying and selling (sell at half cost, Equipment ch. p. 89); starting equipment and gold.
- Character creation validation: ability generation, legal class/species/background choices.
  **[verify: SRD "Character Creation", pp. 19–26]**

### 2.1.1 The rules are installed, not remembered

The SRD Rules Glossary is seeded as `RULE` content (`seed/srd5e/rules.json`, 156 entries: 15 conditions, 12 actions,
5 areas of effect, 5 hazards, 3 attitudes and the general glossary), searchable through `search_rules` next to every
spell, item, creature, class, species, background, feat and skill. The text is lifted verbatim from the PDF by the
`build-rules` command of the seed tooling (`text_is_paraphrase: false`) and checked back by `verify` (commands in the
developer guide). Adjudicate from `search_rules`; when a rule is not in the SRD, say so rather than supply one.

### 2.2 Data-driven (seeded content, `DESIGN.md` §25.1–25.2)

Items, weapons (properties, masteries), armor, tools, spells, creatures (CR, XP), conditions, classes/subclasses,
species, backgrounds, feats, XP-by-CR, advancement tables, services/prices, poisons, crafting tables, trinkets. The
engine interprets the structured fields; the prose rules text rides along for the GM.

### 2.3 GM-interpreted (AI judgment inside engine guardrails)

- Whether a check is warranted, which ability/skill applies, and the DC, within campaign rules policy; the engine
  validates ranges and rolls.
- Situational spell semantics not structurally expressible (`DESIGN.md` §13).
- Improvised actions, environmental rulings, social consequences, NPC tactics.
- Whether a character's want has been fulfilled or abandoned. The GM patches its stable `want:N` through
  `update_character`, optionally linking the outcome with `resolved_by: event:N`; the engine checks identity,
  ownership, campaign isolation and PEGI_18 access for intimate wants. A closure does not require repeating the note.
  Want status is narrative state and does not award XP or alter mechanics by itself.
- Anything the GM cannot decide mechanically becomes a resolved check request or an audited override, never a silently
  invented result.

---

## 3. Hard-coded Formulas

All cited to SRD "Playing the Game" / "The Six Abilities", pp. 5–8 **[verify exact wording]**:

```text
ability_modifier = floor((score - 10) / 2)

d20_test = d20 + ability_modifier + (proficiency_bonus if proficient)
           compared against DC (checks/saves) or AC (attacks)

creature-backed characters: a stat block's listed skill or save number
           ("Perception +5", "Saving Throws Wis +2") is the WHOLE bonus and
           replaces ability_modifier + proficiency_bonus for that skill or
           save; unlisted skills use the formula above. Reported as
           modifier_source = STAT_BLOCK. Applies ONLY while the character
           has no class levels; from the first class level the character
           rolls from its own abilities and proficiencies.

advantage / disadvantage: roll 2d20, take highest / lowest;
           sources never stack; any Advantage source plus any Disadvantage
           source cancels to a plain d20, however many of either there are,
           the GM's declaration counted with the engine's sources and the
           actor's conditions (SRD "Advantage and Disadvantage").
           Results carry advantage (ADVANTAGE / DISADVANTAGE / NONE) and
           advantage_reasons; resolve_check adds advantage_reasons when
           its actor's conditions changed the roll.

conditions on attack rolls (Combat.attackRollFactors, one table for
           weapon and spell attacks; SRD Rules Glossary):
             attacker Blinded, Restrained, Prone, Poisoned: Disadvantage
             attacker Grappled: Disadvantage against anyone but the
               recorded grappler (ADD_CONDITION source); no recorded
               grappler, nothing imposed (GM's call)
             attacker Frightened: Disadvantage on attack rolls and ability
               checks while the recorded source of fear is an ACTIVE
               participant of the same encounter (the engine's reading of
               "within line of sight": zones have no sight lines);
               unrecorded source or no encounter, nothing imposed
             attacker Invisible: Advantage; attacks against it have
               Disadvantage; the "can somehow see you" clause is not
               modelled
             target Blinded, Restrained, Stunned, Petrified: Advantage
             target Paralyzed, Unconscious (and Dying): Advantage, and a
               hit from within 5 feet is a critical hit, including ranged
               and spell attacks
             target Prone: Advantage within 5 feet (shared zone, or an
               opportunity attack), Disadvantage beyond
             Incapacitated alone changes no roll
             Petrified also has Resistance to all damage and Immunity to
               the Poisoned condition
           Poisoned gives Disadvantage on ability checks; Restrained gives
           Disadvantage on DEX saves (resolve_check). Dodge gives attackers
           Disadvantage and the dodger Advantage on DEX saves only while
           the dodger is not Incapacitated and its Speed is above 0.
           A shared encounter zone represents 5-foot proximity, as for
           Sneak Attack. Outside encounters, an unextended Touch spell
           establishes it; otherwise unknown distance does not grant an
           automatic critical.

ranged attacks in close combat: a ranged weapon attack or a non-Touch
           spell attack has Disadvantage when a hostile ACTIVE, living
           participant that is not Incapacitated and not Blinded shares
           the attacker's zone, unless the attacker is Invisible.

condition immunities: ADD_CONDITION honours Petrified → Poisoned, the
           immune_conditions of active effects and stat-block condition
           immunities, reporting immune: <CONDITION> and skipped: <why>.
           Gaining Incapacitated, Stunned, Paralyzed, Unconscious or
           Petrified breaks Concentration on every path (spell saves,
           repeat saves, GM ADD_CONDITION, dropping to 0 HP;
           Effects.conditionGained).

armor training / weapon proficiency (rules/Training.java; SRD "Armor
           Training", "Weapons"): a shield adds AC only with SHIELDS
           training; untrained body armor gives Disadvantage on every D20
           Test involving STR or DEX (checks, saves, weapon and unarmed
           attack rolls, initiative) and refuses spellcasting; heavy armor
           whose Strength requirement is unmet reduces Speed by 10 ft.
           Attack rolls add the proficiency bonus only with a proficient
           weapon (a warning otherwise); unarmed strikes always add it.
           Stat-block creatures and class definitions without the keys are
           unrestricted.

proficiency_bonus: advancement table (data) by character level; by CR for
           creatures

initiative: a DEX check, d20 + DEX (+ proficiency with Alert), minus the
           Exhaustion penalty. One roll per group of identical creatures
           on the same side (same stat block), applied to each member
           (initiative_rolls entry shared_roll). Disadvantage when
           Incapacitated, surprised or in untrained armor; Advantage when
           Invisible. NO engine tie-break: tied combatants keep a
           provisional order (the party's side first, then roster order)
           and the result lists ties: [{initiative, participants,
           decided_by: "GM" | "PLAYERS"}] with ties_note, because SRD
           "Initiative" leaves ties to the GM (monsters, or monster vs
           character) and the players (characters).

attack: hit if total >= AC
natural 20 / natural 1: exactly per SRD 5.2.1 "D20 Tests" (2024 scope
           differs from 2014; take the SRD text). [verify]
critical hit damage: roll the attack's damage dice twice. [verify]

multiattack: a stat block that makes N attacks gets N. ATTACK leaves the
           turn open while attacks remain (attacks_remaining); END_TURN
           always ends it.

repeat saves: a spell granting another save at the end of the target's
           turn (Sleep, Hold Person, Blindness/Deafness, Hold Monster,
           Power Word Stun, …) records that on the effect; the engine rolls
           it when the turn ends. Sleep repeats its save once: a second
           failure escalates to Unconscious and no further repeat save is
           rolled; the effect ends when the target takes damage (seed flag
           ends_on_damage; damage results list ended_on_damage).
           Heightened Spell's Disadvantage carries to the repeat saves
           (repeat_save.disadvantage, rolled as 2d20kl1). Reported as
           repeat_saves.

concentration: save DC = max(10, min(30, damage / 2)); the d20 is 2d20kh1
           while the concentrated spell was Extended (SRD "Extended
           Spell").

damage pipeline: roll → combine each damage type within this one hit /
           effect → successful-save reduction, if any → resistance
           (halve, round down) → vulnerability (double) → temp HP first
           → current HP. Immunity zeros its type.
           Separate attacks, rays and darts remain separate damage
           applications. Order per SRD "Damage and Healing".
           Temporary HP never wakes an unconscious creature.

death: at 0 HP a party member is Dying and Unconscious; a death save is
           rolled at the start of each of its turns. Becoming Stable (third
           success) resets successes and failures to 0 (SRD "Death Saving
           Throws"), so damage taken while Stable counts from zero (one
           failure, two on a critical hit); STABILIZE says so. When
           Unconscious ends because the creature regained HP (healing, a
           natural 20, revival) it remains Prone: PRONE is added and the
           result notes prone; STAND on its encounter turn rights it for
           half its Speed (SRD "Prone"), REMOVE_CONDITION records it
           outside one. A hit point maximum of 0 kills by any route,
           a Constitution loss through SET_ABILITY_SCORE included
           (after.life_state DEAD); there is no floor of 1.

knock-out: a nonlethal melee attack that would drop a creature to 0 HP
           leaves it at 1 HP, ALIVE, with no death-save record, and gives
           it UNCONSCIOUS with duration {kind: UNTIL, until: SHORT_REST,
           expires_seq: now + 60 minutes, label}: it ends when the creature
           completes a Short Rest (one hour on the clock), when it regains
           any HP, or by first aid (DC 10 Wisdom (Medicine), GM
           REMOVE_CONDITION). until: SHORT_REST is also accepted from
           ADD_CONDITION. The knocked-out participant stays DEFEATED for
           the rest of the encounter (§6).

durations: "N rounds" inside an encounter expires when that round begins;
           outside one it is ceil(N / 10) minutes. When an encounter ends,
           each round effect's remaining rounds (counted from the final
           round) become clock minutes, ceil(remaining × 6 s / 60) from the
           post-fight clock, stored as {kind: MINUTES, expires_seq,
           converted_from_rounds: n, label}; effects already due end at
           once; end_encounter reports round_effects_converted_to_minutes.
           START_OF_TURN durations (bound to a participant's next turn)
           still end with the encounter: the turn never comes (§6).
```

Derived values (AC from armor formula and Dex cap, saves, skill bonuses, carry capacity) are computed on read from base
values, equipment and effects (`DOMAIN_MODEL.md` §5.5). No cache table in the MVP.

Damage breakdowns contain one row per type, in first-seen order. `rolled` is the combined damage entering defenses;
`applied` is what remains after defenses. A successful save that halves damage also reports `before_save` for the
unreduced combined amount. Single-component rows retain `roll`; multi-component rows retain every original type,
amount (`rolled`, before save reduction) and optional `roll` in `parts`. Combining components never rerolls dice.
This follows [Resistance and Vulnerability](https://www.dndbeyond.com/sources/dnd/br-2024/playing-the-game#ResistanceandVulnerability):
resistance applies to damage of its type, then vulnerability applies to the result.

---

## 4. The Roller

```text
RollService
  roll(expression, context) -> {expression, dice[], modifier, total, roll_ref}
```

- One interface, two implementations: production (CSPRNG or seeded PRNG per campaign policy) and a scripted/seeded test
  roller, injected.
- Every authoritative roll is journaled with its breakdown (`MCP_PROTOCOL.md` §7.3).
- Tests assert sanity invariants (dice in bounds, arithmetic consistent, resources spent), not golden transcripts
  (`MCP_PROTOCOL.md` §25.5).
- The AI never supplies a die result; overrides go through `apply_gm_override`.
- What no semantic tool rolls, the server still rolls: `roll_dice` journals a free roll, and `apply_runtime_change`
  DAMAGE accepts a `dice` expression and rolls it.
- A save spell centred on the caster (Spirit Guardians, Thunderwave) may be cast with no targets: the slot is spent,
  concentration is tracked, and creatures entering later save with `resolve_check` against the returned `save_dc`. A
  concentration save spell that leaves nothing on its targets still marks the caster as concentrating.

---

## 5. Mechanics by Milestone

Exhaustion uses the character's stored level (0–6). Each application adds one; every D20 Test loses twice that level,
including initiative and death saves, while natural 1/20 death-save effects retain their meaning. Walking Speed loses
five feet per level and is clamped at zero; MOVE, DASH and STAND require positive Speed. A Long Rest, Greater
Restoration or resurrection removes one level, and the condition ends at zero. `REMOVE_CONDITION EXHAUSTION` likewise
removes one level unless the change carries `levels: N` (a positive integer); the result reports `removed`, the
remaining `exhaustion` level and `condition_ended`, with a `note` of the penalties still in force while levels remain.
Level six applies the normal death consequences. Legacy unnumbered effects are read as one level and folded into the
count on a later journaled mutation, including when restored from an old checkpoint. Exhaustion survives effect
cleanup on death.

Maps `DESIGN.md` §33 to rules work. E = engine code, D = data, G = GM.

| Milestone            | Mechanics                                                                                                            | Kind |
|----------------------|----------------------------------------------------------------------------------------------------------------------|------|
| Vertical slice       | dice, one ability/skill check, modifiers, proficiency                                                                | E    |
| MVP                  | attacks, damage, HP, death, initiative/rounds/turns                                                                  | E    |
| MVP                  | ability generation (standard array, point buy, 4d6-drop-lowest per campaign config), starting equipment/gold (A/B) | E+D  |
| MVP                  | XP award (encounter XP from creature XP values), level thresholds                                                    | E+D  |
| MVP                  | money, basic inventory, buy/sell                                                                                     | E+D  |
| MVP seed             | items/equipment tables (`DESIGN.md` §25.1)                                                                           | D    |
| Early follow-up      | conditions/effects, encumbrance, rests, spellcasting + slots, level-up choice validation                              | E+D  |
| Early follow-up seed | creatures, spells, classes/species/backgrounds/feats (§25.2)                                                         | D    |
| Later                | mounted/underwater combat, crafting, poisons in play, mastery properties, tactical grid ranges                       | E+D  |

A mechanic not yet implemented is never approximated: operations needing it return `CAPABILITY_UNAVAILABLE` or fall to
GM interpretation explicitly.

The Light property's extra attack is engine-enforced: after an Attack action with a Light weapon, use
`ATTACK {weapon: "inventory:N", target, light_extra_attack: true}` with a different Light weapon. The extra attack
uses the turn's Bonus Action and omits a positive ability modifier from damage unless Two-Weapon Fighting applies;
negative ability modifiers, magic and other damage bonuses remain. Eligibility and expenditure are journaled on the
participant, including misses and attacks awaiting a Shield choice. Bonus-action spells, Quickened Spell, Font of
Magic slot creation and drinking a potion share that Bonus Action: `USE_ITEM` on an item of type `POTION` inside an
encounter spends it and reports `action_cost: "BONUS_ACTION"`, refused with `OPERATION_NOT_ALLOWED` once it is spent
(SRD 5.2.1 "Potions"); converting a slot to sorcery points takes no action. Weapon mastery (including Nick) and
acquiring Fighting Style feats remain under GM adjudication; an already-recorded Two-Weapon Fighting feat is honored.

**One Spell with a Spell Slot per Turn** (SRD 5.2.1 "Casting Spells") is engine-enforced inside an encounter on the
caster's own turn: a second cast that would expend a spell slot is refused with `OPERATION_NOT_ALLOWED` before
anything is spent; cantrips, rituals and feat free casts are exempt, and reactions on other creatures' turns are
unaffected. Shield cast as a reaction during the caster's own turn (an opportunity attack against them) is subject
to the same rule and marks the turn. Quickened Spell is refused when a level 1+ spell was already cast this turn, and
after a Quickened casting no further level 1+ spell can be cast that turn (cantrips allowed). Tracked in
`once_per_turn_json` markers `slot_spent_turn` / `leveled_spell_turn` / `quickened_turn`, keyed `participant:round`
like the Bonus Action.

**The Action** is recorded the same way (`action_turn`) by every action kind but `MOVE`, `STAND`, `INTERACT` and
`END_TURN`, and by `CAST` / `USE_ITEM` only when the spell or item did not take the Bonus Action; `moved_turn` and
`stood_up_turn` record movement. None of them refuses anything: a second Action is the GM's call (Action Surge,
Haste). An open turn's result reports them as `turn_economy` so the GM can tell an incomplete declaration from a
finished turn, and `next_step` names an unspent Action.

**Shield** (`encounter/ShieldSpell.java`) is offered on every hit by an attack roll, including Opportunity Attacks
(inside the `pending_choices` of `MOVE` results), natural 20s and hits beyond AC + 5, because the +5 lasts until the
start of the caster's next turn and protects against later attacks that round; the choice prompt says whether it turns
this hit into a miss, and under the `AUTO` NPC reaction policy an NPC casts it only when it does. Against Magic
Missile, a target in an encounter that can cast Shield gets a `SHIELD_SPELL` pending choice (context
`magic_missile: true`, `spell`, `attacker`, `target`, `darts[]`) before damage: the darts are rolled at cast time
and applied only on `DECLINE` (resolve result `darts[]`); `CAST_SHIELD` gives `shield`, `damage: 0`, `negated`. A
creature whose Shield is already up (effect flag `immune_magic_missile`) is negated automatically (target entry
`negated`, damage 0); NPCs under `AUTO` cast it outright. Target entries carry `rolled` and `pending: "SHIELD_SPELL"`
or `negated`; the cast result has `pending` / `next_step`.

**Heal** restores 70 HP, +10 per slot level above 6 (`upcast.per_level_flat`), and ends Blinded, Deafened and
Poisoned (`cures`); the 2014 "all diseases" clause is not in the SRD 5.2.1 text and is not applied.

---

## 6. Deviations and House Rules

The engine follows SRD 5.2.1 except where this section says otherwise. Campaign-configured variants, off by
default unless the entry says so:

1. **Rolled starting wealth.** SRD 5.2.1 uses fixed A/B choices; a rolled expression is an explicit house rule only
   (`EXECUTION_EXAMPLE.md` §14 note).
2. **GM override policy.** Protocol-level and audited (`MCP_PROTOCOL.md` §20); not a rules deviation.
3. **HP progression** (`rules.hp_progression`, default `FIRST_3_MAX`). **House rule, on by default.** The SRD
   ("Gaining a Level", p. 23) offers a roll or the fixed value each level; `ROLL` and `AVERAGE` are those two.
   `FIRST_3_MAX` gives the full hit die at levels 2 and 3 so that low-level characters survive, then the open
   server roll from level 4. CON and species bonuses always apply. Fixed at `begin_level_up`; journaled like any
   roll. The first recorded hit die for a character and target level is reused after abandoning and restarting the
   level-up, including after reopening the campaign. The current CON and species bonuses determine the new attempt's
   HP gain; abandoning does not apply it to the character.
4. **Encounter XP on any overcome outcome.** The XP pool is fixed at `start_encounter` (sum of hostile participants'
   XP, stored on the encounter) and pays out in full on `PARTY_VICTORY`, `NEGOTIATED` or `ENEMIES_FLED`; `PARTY_DEFEAT`
   and `PARTY_FLED` pay nothing. Encounters started before this ruling fall back to defeated-only XP.
5. **XP policy** (`rules.xp_policy`, default `SHARED`). `SHARED` divides every award evenly among the active party
   (SRD behaviour). `LOCKSTEP` pays player characters in full and pulls every companion up to the leading player
   character's total. `PLAYER_ONLY` lets only player characters earn. Applied in one place (`PartyXp`) by every
   XP-granting operation: `end_encounter` and `award_xp` (whole party when `characters` is omitted).
6. **Recruits join at the party's experience.** A companion becoming an active member is raised to the leading player
   character's XP under every policy; `xp_policy` governs what they earn from then on. Journaled as a `GM_ONLY`
   `XP_AWARDED` event with source `PARTY_JOIN`.
7. **Companion level-up** (`rules.companion_level_up`, default `PLAYER`). Under `PLAYER`, a companion due a level is
   listed in `companions_awaiting_level_up` with the complete `proposal` the engine would commit (hit points per
   `hp_progression`, and at an ASI level the improvement it would pick), to accept in one word or amend. Under
   `ENGINE` the same choices apply immediately. The ASI heuristic: +2 into the class's first `primary_abilities` entry
   with room, else +1/+1, then CON/DEX/WIS; `begin_level_up` reports the same figure as
   `ability_score_improvement.recommended` for every character. A companion with no class is never advanced by the
   engine under either setting.
8. **A stat-block companion may be promoted to a full character.** A companion materialized from a creature
   definition has no class, so its first `begin_level_up` opens a promotion: `class_choice`, the class's
   `skill_choice`, and `origin_choice` (species and background, with `species_skill`, `species_choice`, `origin_feat`,
   `background_tool`, `feat_choices` as they ask). Class and skills are required; the origin is optional but a
   companion without one cannot be inherited by a player. Everything applies at commit through the same `Origins` code
   as the creation draft, in the order species → background → class → hit points. The class may be recorded alone
   first to obtain the concrete skill list; later levels are ordinary. `get_party` reports `sheet_gaps` (class,
   species, background, alignment, inventory) for every member. Three rules inside it:
   - Hit points are replaced, not added to: the class's full hit die + CON + species bonuses supersede the stat block's
     average.
   - The background's ability-score increase is not applied; the companion has been played with its scores.
     `apply_gm_override SET_ABILITY_SCORE` is the deliberate route.
   - The stat block's actions, senses and identity are kept; its listed numbers are not (§3), so the class and
     background skills are granted.
9. **Class features are seeded, not coded.** A class definition may carry `features`; each has `enforcement`
   (`ENGINE` or `GM`), `summary`, the SRD `text`, and for ENGINE features a `mechanic` block. `ClassFeatures` resolves
   which features a character has reached and hands the engine the mechanic with the class level attached, so scaling
   is data. Mechanic kinds: `SNEAK_ATTACK` (`die`, `dice_per_levels`, `requires_weapon`, `once_per_turn`),
   `FONT_OF_MAGIC` (`slot_costs`), `METAMAGIC` (`options_known` by class level, `options` with `id`, `name`, `cost`,
   `summary`), `SORCEROUS_RESTORATION`, `SORCERY_INCARNATE` (marker: two options on one spell while Innate Sorcery is
   active). A feature may carry a `resource` block (`ref`, `max` as a number or `CLASS_LEVEL` / `HALF_CLASS_LEVEL` /
   `PROFICIENCY_BONUS`, `recharge`) that becomes a tracked pool. A new feature of a known kind is a seed edit; a new
   kind needs Java. Features appear on the sheet under `class_features` with their adjudication.

   **Metamagic** (`magic.Metamagic`): known options are `FEATURE` traits (`metamagic:<id>`), chosen at the level-up
   where the count rises (two at 2, one more at 10 and 17; the level-up does not commit without them) or set by the
   audited `SET_METAMAGIC` override. At casting, `options.metamagic` names the options; the plan is validated against
   the spell (Heightened and Careful need a save, Seeking an attack roll, Empowered and Transmuted damage, Quickened a
   1-action casting time, Extended a duration of a minute or more, Distant a range other than Self, Twinned a spell
   that gains a target from a higher slot) and paid before any die is rolled; one option shapes a spell, Empowered and
   Seeking may join it. The sheet reports `options_known_limit` (two at level 2, four at 10, six at 17). Empowered
   rerolls "up to" the Charisma modifier's worth of dice (minimum one), only dice below their maximum, lowest first,
   sharing that one allowance across damage parts, darts and attacks; each die is rerolled at most once, and the
   point is charged when the first die is rerolled, so a casting that rerolls nothing (every die at its maximum, or
   a missed attack spell) spends no point (`points_spent` only when a die was rerolled). Seeking rerolls a missed
   attack roll and spends its point only then; Heightened gives the target's save Disadvantage, carried to the
   spell's repeat saves; Careful marks the named creatures as automatic successes taking no damage; Transmuted swaps
   an elemental type; Extended doubles a minute-or-longer duration to at most 24 hours and gives the caster Advantage
   on Concentration saves for that spell (recorded as `extended_spell` on the concentration effect; the save's
   `roll.expression` is `2d20kh1+N`). Quickened spends the shared Bonus Action during encounters and is bound by the
   one-slot-per-turn rule (§5). Distant, Subtle and Twinned are paid and reported; the engine does not otherwise
   track general Action economy, range or components.

   Simultaneous saving-throw targets share one raw spell damage roll, including Empowered rerolls; each target
   independently applies its save, Careful protection and defenses ([2024 damage rules](https://www.dndbeyond.com/sources/dnd/br-2024/playing-the-game#DamageagainstMultipleTargets)).
   Spell attacks and Magic Missile darts keep their independent rolls. Empowered carries unused rerolls forward
   within its one allowance ([Sage Advice](https://www.dndbeyond.com/sources/dnd/sae/sage-advice-compendium#Sorcerer));
   it selects the lowest dice in each arriving roll rather than comparing future rolls.

   **Sneak Attack**: on a hit with a Finesse or Ranged weapon, when the roll had Advantage, or when an ally is beside
   the target and the roll did not have Disadvantage. Once per turn, tracked on the participant. With zones instead
   of a grid, "an ally within 5 feet" is adjudicated as "a living, non-incapacitated ally shares the target's zone".
   The result reports `qualified_by`.
10. **House rules may be retuned after commit.** `apply_gm_override` kind `SET_CAMPAIGN_RULE {rule, value}` rewrites
    one of `hp_progression`, `xp_policy`, `companion_level_up`, `progression` or `gm_override_policy` on a committed
    campaign, audited. Everything else in the setup draft is fixed at commit.
11. **Font of Magic creates slots of a level the character can already cast.** The SRD allows any slot of level 1–5;
    the engine only creates a slot whose pool exists, because the casting core refuses slots whose pool maximum is 0.
    The created slot grows the pool's maximum and survives level-up, alongside newly gained class slots. Spent slots
    stay spent, and a Short Rest preserves the pool. The next Long Rest resizes every pool back to the current class
    table, so the created slot vanishes as the rule says. Existing campaigns use the same stored pool counts and
    need no migration.
12. **A knocked-out creature is out of the fight.** The SRD only reduces it to 1 HP and makes it Unconscious; the
    engine also marks the participant `DEFEATED`, and it cannot be attacked for the rest of the encounter.
13. **Start-of-turn durations end with the encounter.** An effect bound to a participant's next turn
    (`until: START_OF_TURN`) is cleared at `end_encounter` because that turn never comes; round durations are
    converted to minutes instead (§3).
14. **The minute clock rounds converted round durations up.** Six seconds per round, `ceil` to whole minutes, both
    for the time an encounter takes and for the rounds an effect has left when it ends; a one-round remainder
    becomes a full minute.
15. **Frightened "within line of sight" and Invisible "can see".** Zones have no sight lines: the source of fear is
    in sight while it is an `ACTIVE` participant of the same encounter; an unrecorded source, or no encounter,
    imposes nothing. An Invisible creature always has Advantage on its attacks and imposes Disadvantage on attacks
    against it; the "if a creature can somehow see you" exception is not modelled. A Blinded adjacent enemy does not
    impose the close-combat ranged penalty.
16. **Grappled with no recorded grappler imposes nothing.** The Disadvantage against targets other than the grappler
    needs `ADD_CONDITION source`; without it the GM adjudicates.

Any future divergence gets an entry here before it is coded.

---

## 7. Content Payload Schemas

Pinned per kind before its import milestone (`DATABASE.md` §10). Filterable columns are fixed by `DATABASE.md` §3.1:
`cost_cp`, `weight_g`, `spell_level`, `cr_times_8`, `xp_value`, `tags_json`. Per-kind JSON shapes: ITEM first, then
CREATURE + SPELL, then CLASS/SPECIES/BACKGROUND/FEAT.

Keys added by the SRD 5.2.1 rule fixes:

- **CLASS:** `armor_training` (`LIGHT`, `MEDIUM`, `HEAVY`, `SHIELDS`) and `weapon_proficiencies` (`SIMPLE`, `MARTIAL`,
  `MARTIAL_LIGHT` = Martial weapons with the Light property, Monk; `MARTIAL_FINESSE` = Martial weapons with the
  Finesse property, Rogue), from the SRD class tables: Barbarian L/M/Shields + Simple/Martial; Bard Light + Simple;
  Cleric L/M/Shields + Simple; Druid L/Shields + Simple; Fighter L/M/H/Shields + Simple/Martial; Monk none +
  Simple/Martial-Light; Paladin L/M/H/Shields + Simple/Martial; Ranger L/M/Shields + Simple/Martial; Rogue Light +
  Simple/Martial-Finesse/Martial-Light; Sorcerer none + Simple; Warlock Light + Simple; Wizard none + Simple. A
  definition without the keys leaves the character unrestricted (`rules/Training.java`). The former class `ritual`
  flags are gone (§8).
- **SPELL:** `mechanics.ends_on_damage` (Sleep: the effect ends when the target takes damage); `upcast.per_level_flat`
  is honoured for `HEAL` (Heal: +10 per slot level above 6) as it already was for `TEMP_HP`.

Still open: relationship `dimensions_json` shape and the tactical-grid schema (`DATABASE.md` §10 items 3 and 5).

---

## 8. Character Origins (Species Traits, Backgrounds, Feats)

Encoded from SRD 5.2.1 "Character Origins" (pp. 83–86) and "Feats" (pp. 87–88); `species.json`, `backgrounds.json`
and `feats.json` carry the data with per-trait `enforcement` markers.

- **Backgrounds (engine):** the +2/+1 or +1/+1/+1 increase (validated against the background's abilities, never above
  20; base scores stay validated against the generation method), the Origin feat, two skills, the tool proficiency
  (fixed or category choice), and equipment option A/B granted at activation with the class equipment.
- **Species traits:** `ENGINE` traits are fully mechanical (bonus skills, lineage/ancestry cantrips and spells with
  free casts, darkvision, resistances, Dwarven Toughness, Powerful Build, Relentless Endurance). `MIXED` traits have
  engine-tracked uses (`resource_state`, spent via `apply_runtime_change` USE_RESOURCE) and GM-adjudicated triggers
  (Breath Weapon, Stonecunning, Giant Ancestry, Adrenaline Rush, Heroic Inspiration). `GM` traits are rules text on
  the sheet with `adjudication: GM` (Halfling Luck, Brave, Fey Ancestry, Gnomish Cunning, Trance), never dropped.
- **Feats (engine):** Alert (initiative proficiency), Skilled (three skill/tool proficiencies), Magic Initiate
  (cantrips + a level 1 spell with one free cast per Long Rest, cast with the chosen ability), Savage Attacker (first
  weapon hit per turn rolls damage dice twice, higher kept, both recorded). At ASI levels a feat may replace the
  improvement; prerequisites (category, level, ability minimums, repeatability) are validated. Fighting Style feats
  and Epic Boons are seeded but GM-adjudicated until their milestones.
- **Duplicate proficiencies are re-chosen:** background skills are fixed; class/species/feat pickers exclude held
  skills and reject duplicates.
- **Campaign backgrounds (engine):** `define_content` kind `BACKGROUND` stores a background in the seeded shape,
  validated against installed content; `Origins.resolveBackground` looks installed content up first and the campaign's
  own second, so a custom background behaves like an SRD one in drafts, promotions and on the sheet.
- **Tool proficiency in checks (engine):** `resolve_check` takes a `tool`; proficiency adds the bonus to an ability
  check made with it, and a skill check made with a tool the actor is also proficient in has advantage, cancelling a
  GM-imposed disadvantage instead (SRD 5.2.1 "Tools and Skills Together"). Checks are legal mid-encounter and never
  advance the initiative order.
- **Ritual casting (engine):** `cast_spell` with `options.ritual = true` casts a Ritual-tagged spell the caster has
  prepared (or knows / always has prepared) without a slot (SRD 5.2.1 "Rituals"); any caster may, there is no class
  gate. The casting takes ten minutes longer, `slot_level` is refused, and a spell without the tag is
  `VALIDATION_FAILED`. The Wizard's Ritual Adept (ritual-casting an unprepared spellbook spell) is not modelled
  because there is no spellbook concept; the sheet's `spellcasting.ritual_casting` is an explanatory string. The
  encounter `CAST` action never casts rituals.
- **Coin between characters (engine):** `give_money` moves an amount between two characters atomically and writes one
  `MONEY_GIVEN` event; it never creates coin (`grant_loot`) and never prices anything (`trade`).

---

## 9. Travel Encounters

The SRD 5.2.1 has no random-encounter tables, so the engine's are its own and are suggestions only: `move_party` may
add one `TRAVEL_ENCOUNTER_SUGGESTED` entry to its `consequences`; nothing else happens until the GM acts on it.

- **Chance.** One roll per four hours of travel, through the roller: a 1 on a d6 on wild ground, a 1 on a d12 where
  the only wild tags on the route are roads, rivers, coasts or fords, no roll when nothing on the route is tagged wild
  or road (streets, districts, quays, buildings, untagged nodes). Journeys under four hours never roll.
- **Ground.** The tags (`tags_json`) and kinds of every location on the route and the destination, plus connection
  kinds, pick a terrain table in this order: dungeon/cave/crypt, ruin/barrow, marsh/fen/swamp, mountain,
  hills/chalk/downs, forest/wood, coast/sea/salt, river/estuary/ford, road; anything else is wilderness.
- **Creature.** Each table lists SRD stat-block slugs; only those installed in the campaign's rulesets are candidates,
  one is drawn with a die of the pool's size, and the wilderness table is the fallback.
- **Size.** The party's Moderate XP budget (SRD 5.2.1 Gameplay Toolbox, "XP Budget per Character", summed over the
  travellers by level) divided by the creature's XP value, clamped to 1..8.
- **Hooks.** Up to two open Director `PRESSURE` seeds are quoted so the GM can fold the encounter into the campaign's
  standing worries.

## 10. Magic Items and Treasure

The SRD 5.2.1 "Magic Items A–Z" (pp. 209–253) is seeded verbatim as `ITEM` content (`magic-items.json`, 260 entries,
built by `SrdTool build-magic-items`; the Potions of Healing entry becomes one item per potency). Every entry carries a
`magic` block: `category`, `rarity` (`COMMON`, `UNCOMMON`, `RARE`, `VERY_RARE`, `LEGENDARY`, `ARTIFACT`, or `VARIES`),
`attunement` (with `attunement_by` when restricted) and `enforcement` (`ENGINE` or `GM`). `cost_cp` is the rarity's
value from "Magic Item Rarities and Values" (p. 206), halved for consumables other than Spell Scrolls. Worn wondrous
items carry a `slot` from their name (`BOOTS`, `CLOAK`, `BELT`, `NECK`, `HANDS`, `WRISTS`, `HEAD`, `EYES`; `BODY` for
robes); rings are `RING`; rods, staffs and wands are held (`ONE_HAND`); the rest is carried and used from the pack.

- **Templates.** Magic weapons, armor, shields and ammunition describe any base item of their kind (`template: true`,
  `applies_to`). `grant_loot` instantiates one on a base as campaign content ("+1 Longsword", "Flame Tongue (Longsword)"):
  the base's mechanics plus the template's magic and text, priced base + rarity value, one definition per name. A `+N`
  template carries `bonus_by_rarity`; the requested bonus fixes the rarity. Templates cannot be bought or granted as
  they are (`MagicItems`).
- **Ammunition values.** `cost_cp` prices a purchase bundle; inventory and loot quantities count individual pieces.
  The +1/+2/+3 ammunition template carries `value_quantity: 10`: ten pieces have the magic value of a potion of the
  same rarity (half the rarity table value). Other ammunition, including Ammunition of Slaying and GM-made
  enchantments, uses the general consumable value per piece. The magic value is scaled to the base's bundle size,
  then the mundane bundle cost is added once. For example, twenty +1 Arrows cost 401 GP (400 GP magic + 1 GP arrows);
  selling one at half value yields 10 GP 2 CP after rounding down. Existing campaign definitions retain their stored
  prices; the calculation applies when a definition is first created.
- **GM-made enchantments.** The SRD has no Arrow of Fire; `grant_loot` makes one on a mundane base from an inline
  `magic` spec (`name`, `rarity`, `text`, and the mechanics: `bonus`, `damage_bonus_dice` + `damage_type`, `ac_bonus`,
  `save_bonus`, `attack_bonus`, `speed_bonus`, `resistance`, `attunement`, `slot`, `consumable: {heal}`). Extra damage
  dice on a weapon or ammunition ride only on attacks with that item (`Combat.weapon`, `Combat.withAmmunition`), never on
  the wearer's other attacks; the worn modifiers apply while equipped (`MagicItems.enchant`).
- **Ammunition recovery.** Standard +1/+2/+3 ammunition loses its magic on a hit and is recovered on its mundane base;
  a missed piece retains its magic. Other magical or custom ammunition is resolved from its own text by the GM,
  choosing `ORIGINAL`, `MUNDANE` or `LOST` for its selected recovery group. `LOST` counts against the recovery quota
  and returns no item. The engine never assumes every magical shot loses its magic. Recovery offers in
  `end_encounter` and `get_encounter_state` set `selection_required` only for `GM` policies; otherwise omitted
  selection draws randomly from all fired pieces. Offers are optional and do not block play or change ammunition
  automatically.
- **Engine-enforced.** The +N of a weapon (attack and damage rolls), of armor and shields (AC) and of ammunition (the
  shot it is fired with: `ATTACK` names it as `ammunition`, with any extra damage dice of its own); Ring and Cloak of Protection (`modifiers: {ac_bonus,
  save_bonus}`, folded into `Effects.modifiers` while equipped); Bracers of Defense (`ac_bonus_unarmored`, only without
  armor and shield); Potions of Healing (`consumable: {heal}`, rolled by `USE_ITEM` in and out of combat). Everything
  else is GM-adjudicated from its text, which the sheet shows under `magic_items` with `adjudication: GM`.
- **Attunement (engine).** An equipped item that requires attunement is attuned; at most three at a time (SRD
  "Attunement"). Slots: one body armor, one shield, two rings, one of each worn slot, two hands. A shield
  uses one hand; equipping another shield is refused. If an older campaign already has several equipped,
  only the strongest shield's AC bonus applies.
- **Weapon buffs (engine).** A spell whose mechanic has `targets: WEAPON` binds its effect to one carried weapon
  (`options.weapon`, else the wielder's equipped weapon): the effect's `attack_bonus`, flat `damage_bonus` and damage
  dice ride only on attacks with that weapon (`Effects.weaponModifiers`, `Combat.withBuff`), never on a fist or a second
  blade, and stack with the weapon's own enchantment. Magic Weapon takes a nonmagical weapon (`requires_nonmagical`)
  and its `upcast` map raises the bonus by slot (+2 at 3–5, +3 at 6+, SRD 5.2.1). Shillelagh (`weapon_kinds` Club or
  Quarterstaff, `weapon_die` with cantrip scaling, `use_ability: SPELLCASTING`, Force at the caster's choice) and Flame
  Blade (`conjured_weapon`: a melee spell attack by that name while the effect lasts, dice scaled by the slot) are
  seeded verbatim. Actor-wide buffs (Bless, Divine Favor) apply to every attack as before; a flat `damage_bonus` on an
  actor-wide effect adds to the first damage part. Re-casting a weapon buff replaces the previous one (stacking key).
  Named weapons resolve equipped copies first, then by entry ID, matching attacks; an explicit `inventory:N` binds
  that exact carried copy.
- **Treasure guidance (the engine's own; the SRD has no treasure tables).** Suggestions only, by the party's highest
  level: levels 1–4 draw COMMON 45 / UNCOMMON 45 / RARE 10, a permanent item every second or third completed quest (a
  Ring of Protection is a stroke of luck); 5–10 UNCOMMON 40 / RARE 45 / VERY_RARE 15, about one per completed quest or
  major encounter; 11–16 RARE 35 / VERY_RARE 50 / LEGENDARY 15, one or two per quest; 17–20 VERY_RARE 40 / LEGENDARY 55 /
  ARTIFACT 5. A completed quest, a major encounter (XP pool of 200 or more) and a `bootstrap_session` that finds a
  level-3 party, or one with two completed quests, without a single magic item report `treasure` (or a warning) with
  candidates drawn through the roller: 1d100 for the rarity, 1dN for the item (`Treasure`). Nothing is granted by itself.
