# RPG MCP Server — Rules Engine

**Status:** Incremental working document. Grows with implementation milestones; each content kind's schema is pinned
just before the milestone that imports it.
**Purpose:** Define which game rules the engine implements as code, which arrive as data, which remain GM judgment — and
the exact formulas the engine hard-codes.
**Source of rules:** **SRD 5.2.1** (CC-BY-4.0). This document deliberately does not restate the SRD; it records the
boundary, the encoded formulas, and citations. The SRD text is authoritative for everything cited.

---

## 1. Verification Protocol

Every rule encoded in the engine cites its SRD 5.2.1 section (and page in the reference PDF). The test that covers the
rule references the same citation. A rule without a citation is a house rule and must be listed in §6.

Sections marked **[verify]** were not yet read against the SRD text during design; they MUST be verified at encoding
time, not assumed from prior-edition knowledge. (The Equipment chapter pp. 89–103, Sample Poisons pp. 197–198, and the
Sorcerer class pp. 64–65 were read directly during design; most other chapters were not.)

---

## 2. The Three-Way Rules Boundary

> If it is arithmetic or state, it is **engine code**. If it is a table, it is **data**. If it is judgment, it is the *
*GM** — with the engine validating what it can.

### 2.1 Engine-coded (deterministic procedures)

- Dice expressions and all authoritative randomness (the roller, §4).
- D20 Tests: ability checks, skill checks, saving throws, attack rolls (§5).
- Damage application, resistance/vulnerability/immunity arithmetic, HP/temp-HP bookkeeping, dropping to 0 HP and death
  saving throws. **[verify: SRD "Damage and Healing", pp. 16–18]**
- Initiative, round/turn order.
- Resource accounting: spell slots, class resources, item charges, ammunition.
- Rest procedures (short/long) and what they restore. **[verify: SRD Rules Glossary "Short Rest"/"Long Rest"]**
- Conditions: applying/removing/stacking and their *mechanical* modifiers (the condition's rules text is data; its
  arithmetic effect on rolls is code). **[verify: SRD Rules Glossary condition entries]**
- Encumbrance and carrying capacity. **[verify: SRD "Carrying Capacity" glossary entry]**
- XP accrual, level thresholds, proficiency bonus progression, level-up validation.
- Money arithmetic in canonical copper; buying/selling (sell at half cost per Equipment ch., p. 89 sidebar); starting
  equipment/gold choices.
- Character creation validation: ability generation methods, legal class/species/background choices. *
  *[verify: SRD "Character Creation", pp. 19–26]**

### 2.1.1 The rules are installed, not remembered

The SRD's **Rules Glossary is seeded as `RULE` content** (`seed/srd5e/rules.json`, 156 entries: 15 conditions,
12 actions, 5 areas of effect, 5 hazards, 3 attitudes and the general glossary) and is searchable through
`search_rules` alongside every spell, item, creature, class, species, background, feat and skill.

The text is **lifted verbatim** from the SRD PDF by `tools/build_rules.py`, not paraphrased — every entry carries
`text_is_paraphrase: false`, and `tools/verify_srd.py` checks each one back against the PDF text (156/156 verbatim
on 2026-09-03). A rules question is therefore answerable with a citation instead of a recollection, which matters
because the alternative has already gone wrong in play: Sleep was asserted from memory to gain radius at higher
levels, the seed was edited to match, and the PDF showed SRD 5.2.1 gives Sleep no higher-level clause at all.

**Adjudicate from `search_rules`, and say so when a rule is not in the SRD rather than supplying one.**

### 2.2 Data-driven (seeded content, `DESIGN.md` §25.1–25.2)

Items, weapons (with properties/masteries), armor, tools, spells, creatures (with CR and XP), conditions,
classes/subclasses, species, backgrounds, feats, XP-by-CR, advancement tables, services/prices, poisons, crafting
tables, trinkets.

The engine interprets the *structured* fields of these definitions mechanically; their prose rules text rides along for
the GM.

### 2.3 GM-interpreted (AI judgment inside engine guardrails)

- Whether a check is warranted, which ability/skill applies, and the DC — within campaign rules policy; the engine
  validates ranges and executes the roll.
- Situational spell semantics not structurally expressible (the architecture explicitly supports both, `DESIGN.md` §13).
- Improvised actions, environmental rulings, social consequences.
- NPC tactics and behavior.
- Anything the GM cannot decide mechanically becomes either a resolved check request or an audited override — never a
  silently invented result.

---

## 3. Hard-coded Formulas

The small set the engine encodes directly (all cited to SRD "Playing the Game" / "The Six Abilities", pp. 5–8 *
*[verify exact wording]**):

```text
ability_modifier = floor((score - 10) / 2)

d20_test = d20 + ability_modifier + (proficiency_bonus if proficient)
           compared against DC (checks/saves) or AC (attacks)

creature-backed characters: a stat block's listed skill or saving-throw
           number ("Perception +5", "Saving Throws Wis +2") is the WHOLE
           bonus — it already contains proficiency and any expertise — and
           replaces ability_modifier + proficiency_bonus for that skill or
           save. Skills the block does not list fall back to the formula
           above. Reported as modifier_source = STAT_BLOCK.
           This applies ONLY while the character has no class levels. The
           moment one is taken the character is built from its own abilities
           and proficiencies, which is the point of classing a companion:
           otherwise a rogue with Expertise would go on rolling the Scout's
           numbers.

advantage / disadvantage:
  roll 2d20, take highest / lowest
  multiple sources never stack; adv + dis cancel to a flat roll

proficiency_bonus: from the advancement table (data), by character level;
                   by CR for creatures

attack: hit if total >= AC
critical hit / automatic outcomes on natural 20 / natural 1:
  encode exactly per SRD 5.2.1 "D20 Tests" — 2024 rules differ from 2014
  on scope; take the SRD text, not prior-edition memory. [verify]
critical hit damage: roll the attack's damage dice twice. [verify]

multiattack: a creature whose stat block says it makes N attacks gets
           N. An ATTACK leaves the turn open while attacks remain (the
           result reports attacks_remaining); END_TURN always ends it.

repeat saves: a spell whose description grants the target another save at
           the end of its turn (Sleep, Hold Person, Blindness/Deafness,
           Hold Monster, Power Word Stun, …) records that instruction on
           the effect, and the engine rolls it when that turn ends. A
           second failure escalates where the spell says it does — Sleep
           to Unconscious. Reported as repeat_saves.

damage pipeline: roll → apply resistance (halve) / vulnerability (double)
                 / immunity (zero) → temp HP absorbs first → current HP.
                 Ordering per SRD "Damage and Healing". [verify]
```

Derived values (AC from equipped armor formula + Dex cap, saves, skill bonuses, carry capacity) are computed on read
from base values + equipment + effects (`DOMAIN_MODEL.md` §5.5); no cache table in the MVP.

---

## 4. The Roller

```text
RollService
  roll(expression, context) -> {expression, dice[], modifier, total, roll_ref}
```

- One interface, two implementations: production (CSPRNG or seeded PRNG per campaign policy) and **test roller** (
  scripted/seeded), injected — the seam exists from the first line of dice code.
- Every authoritative roll is journaled with its breakdown (`MCP_PROTOCOL.md` §7.3).
- Tests assert **sanity invariants** (dice within bounds, arithmetic consistent, resources correctly spent), not golden
  transcripts (`MCP_PROTOCOL.md` §25.5).
- The AI can never supply a desired die result; overrides go through `apply_gm_override`.

---

## 5. Mechanics by Milestone

Maps `DESIGN.md` §33 (MVP lists) to rules work. E = engine code, D = data, G = GM.

| Milestone            | Mechanics                                                                                                                        | Kind |
|----------------------|----------------------------------------------------------------------------------------------------------------------------------|------|
| Vertical slice       | dice, one ability/skill check, modifiers, proficiency                                                                            | E    |
| MVP                  | attacks, damage, HP, death, initiative/rounds/turns                                                                              | E    |
| MVP                  | ability generation (standard array, point buy, 4d6-drop-lowest per campaign config), starting equipment/gold (fixed A/B choices) | E+D  |
| MVP                  | XP award (encounter XP from creature XP values), level thresholds                                                                | E+D  |
| MVP                  | money, basic inventory, buy/sell                                                                                                 | E+D  |
| MVP seed             | items/equipment tables (`DESIGN.md` §25.1)                                                                                       | D    |
| Early follow-up      | conditions/effects, encumbrance, rests, spellcasting + slots, level-up choice validation                                         | E+D  |
| Early follow-up seed | creatures, spells, classes/species/backgrounds/feats (§25.2)                                                                     | D    |
| Later                | mounted/underwater combat, crafting, poisons in play, mastery properties, tactical grid ranges                                   | E+D  |

A mechanic not yet implemented is not silently approximated: operations requiring it return `CAPABILITY_UNAVAILABLE` or
fall to GM interpretation *explicitly*.

---

## 6. Deviations and House Rules

The engine ships with **zero deviations** from SRD 5.2.1. Optional, campaign-configured variants (all off by default):

1. **Rolled starting wealth** — SRD 5.2.1 uses fixed A/B choices; a rolled expression is available only as an explicit
   house rule (`EXECUTION_EXAMPLE.md` §14 note).
2. **GM override policy** — protocol-level, audited (`MCP_PROTOCOL.md` §20); not a rules deviation.
3. **HP progression** (`rules.hp_progression`, default **FIRST_3_MAX**) — replaces the RAW per-level
   average-or-roll choice with a campaign policy: `FIRST_3_MAX` (full hit die through level 3, open server
   roll from level 4), `AVERAGE` (fixed every level) or `ROLL` (open server roll every level). CON and
   species bonuses always apply. Fixed at `begin_level_up`; journaled like any roll.
4. **Encounter XP on any overcome outcome** — the XP pool is fixed at `start_encounter` (sum of hostile
   participants' XP values, stored on the encounter) and pays out in full on `PARTY_VICTORY`, `NEGOTIATED`
   or `ENEMIES_FLED` — killed, captured, or talked down all count as overcoming (SRD: XP rewards
   *overcoming* an encounter). `PARTY_DEFEAT`/`PARTY_FLED` pay nothing. Encounters started before this
   ruling fall back to defeated-only XP.
5. **XP policy** (`rules.xp_policy`, default **SHARED**) — `SHARED` divides every award evenly among the
   active party, which is both SRD 5.2.1 and the classic party-RPG behaviour. `LOCKSTEP` instead pays
   player characters in full and then pulls every companion up to the leading player character's total, so
   party size never changes the player's pace. `PLAYER_ONLY` lets only player characters earn. The policy
   is applied in exactly one place (`PartyXp`) and every XP-granting operation routes through it:
   `end_encounter` and `award_xp` (which awards to the whole party when `characters` is omitted).
6. **Recruits join at the party's experience** — a companion who becomes an active party member is raised
   to the leading player character's XP total, **under every policy**: what `xp_policy` governs is what a
   companion earns from then on, not what they arrive with. Journalled as a `GM_ONLY` `XP_AWARDED` event
   with source `PARTY_JOIN`.
7. **Companion level-up** (`rules.companion_level_up`, default **PLAYER**) — under `PLAYER` a companion who
   reaches a new level is listed in `companions_awaiting_level_up` *together with the complete proposal the
   engine would otherwise have committed* (`proposal`: the hit points `rules.hp_progression` dictates, and
   at an ASI level the improvement it would pick), so the player can accept it in one word or change any
   part of it. Under `ENGINE` the same choices are applied immediately and silently. The ASI heuristic is
   +2 into the class's first `primary_abilities` entry that has room, falling back to +1/+1, then
   CON/DEX/WIS. `begin_level_up` carries the same figure as `ability_score_improvement.recommended`, for
   player characters too. **A companion with no class is never advanced by the engine under either
   setting**: the engine does not invent a class.
8. **A stat-block companion may be promoted to a full character.** Companions materialized from a creature
   definition have no class, so their first `begin_level_up` opens a promotion instead of a hit-point gain:
   `class_choice`, the class's `skill_choice`, and `origin_choice` (species and background, with whatever they
   ask for in turn — `species_skill`, `species_choice`, `origin_feat`, `background_tool`, `feat_choices`).
   Class and skills are required; the origin is optional but a companion without one is not a character a
   player could inherit. Everything is applied at commit, through the same `Origins` code the creation draft
   uses, in the order species → background → class → hit points, because hit points depend on the species
   (Dwarven Toughness) and a background grants an origin feat that may itself grant proficiencies. The result
   is indistinguishable from a character built through the wizard.

   Three deliberate rules inside it:
   - **Hit points are replaced, not added to.** The class's own full hit die + CON + species bonuses supersede
     the stat block's average.
   - **The background's ability-score increase is *not* applied.** That belongs to creation; a companion being
     promoted has already been played with the scores they have, and silently adding +3 would rewrite a
     character the player knows. `apply_gm_override` `SET_ABILITY_SCORE` is the deliberate route.
   - **The stat block's actions, senses and creature identity are kept; its numbers are not** (§3). Granting
     the class and background skills is therefore not bookkeeping: without them a newly classed companion
     would be strictly worse at its own job than the stat block it replaced.

   The class may be recorded on its own first, which is how the concrete skill list is obtained. Later levels
   are ordinary. `get_party` reports `sheet_gaps` for every member — class, species, background, alignment,
   inventory — so the distance to a full character is visible before a player character dies, not after.
9. **Class features are seeded, not coded.** A class definition may carry a `features` array; each entry has an
   `enforcement` (`ENGINE` or `GM`), a `summary`, the SRD `text`, and — for ENGINE features — a `mechanic` block the
   rules engine applies. `ClassFeatures` resolves which features a character has actually reached and hands the
   engine the mechanic with the character's level in that class attached, so scaling is data. Understood mechanic
   kinds so far: **`SNEAK_ATTACK`** (`die`, `dice_per_levels`, `requires_weapon`, `once_per_turn`). Adding another
   feature of a known kind is a seed edit; adding a new *kind* is the only thing that needs Java. Features appear on
   the character sheet under `class_features` with their adjudication.

   **Sneak Attack**, as implemented: on a hit with a Finesse or Ranged weapon, when the roll had Advantage, or when
   an ally is beside the target and the roll did not have Disadvantage. Once per turn, tracked on the participant
   like Savage Attacker. This engine has zones rather than a grid, so *"an ally within 5 feet of the target"* is
   adjudicated as **"a living, non-incapacitated ally shares the target's zone"**. The result reports `qualified_by`.
10. **House rules may be retuned after commit** — `apply_gm_override` kind `SET_CAMPAIGN_RULE {rule, value}`
   rewrites one of `hp_progression`, `xp_policy`, `companion_level_up`, `progression` or
   `gm_override_policy` on a committed campaign. Audited like any other override. Everything else in the
   setup draft stays fixed at commit.

Any future divergence gets an entry here *before* it is coded.

---

## 7. Content Payload Schemas

Pinned per kind before its import milestone (inherited from `DATABASE.md` §10). Resolved so far:

- Filterable columns already fixed by `DATABASE.md` §3.1: `cost_cp`, `weight_g`, `spell_level`, `cr_times_8`,
  `xp_value`, `tags_json`.
- Remaining per-kind JSON shapes: **ITEM first** (vertical slice / MVP), then CREATURE + SPELL together (encounter +
  casting milestone), then CLASS/SPECIES/BACKGROUND/FEAT (creation/level-up milestone).

Still open here: relationship `dimensions_json` shape, `calendar_json` format, tactical-grid schema (
`DATABASE.md` §10 items 3–5).
---

## 8. Character Origins (Species Traits, Backgrounds, Feats)

Encoded from SRD 5.2.1 "Character Origins" (pp. 83–86) and "Feats" (pp. 87–88); seed files
`species.json`, `backgrounds.json`, `feats.json` carry the data with per-trait `enforcement` markers.

- **Backgrounds (engine):** the three-ability +2/+1 or +1/+1/+1 increase (validated against the
  background's abilities, never above 20; base scores stay separately validated against the generation
  method), the Origin feat, the two skills, the tool proficiency (fixed or category choice), and
  equipment option A/B granted at activation alongside the class equipment.
- **Species traits:** `enforcement: ENGINE` traits are fully mechanical (bonus skills, lineage/ancestry
  cantrips and spells with free casts, darkvision senses, damage resistances, Dwarven Toughness HP,
  Powerful Build carrying capacity, Relentless Endurance in the damage pipeline). `MIXED` traits have
  engine-tracked uses (`resource_state`, spent via `apply_runtime_change` USE_RESOURCE) with
  GM-adjudicated triggers (Breath Weapon, Stonecunning, Giant Ancestry, Adrenaline Rush, Heroic
  Inspiration). `GM` traits are rules text surfaced on the sheet with `adjudication: GM` (Halfling Luck,
  Brave, Fey Ancestry, Gnomish Cunning, Trance) — never silently dropped (§2.3).
- **Feats (engine):** Alert (initiative proficiency), Skilled (three skill/tool proficiencies), Magic
  Initiate (cantrips + a level 1 spell with one free cast per Long Rest, cast with the chosen ability),
  Savage Attacker (first weapon hit per turn rolls damage dice twice, higher total kept, both recorded).
  At ASI levels a feat may be taken instead of the improvement; prerequisites (category, level, ability
  minimums, repeatability) are validated. Fighting Style feats and Epic Boons are seeded as data but
  GM-adjudicated until their milestones.
- **Duplicate proficiencies are re-chosen** (background skills are fixed; class/species/feat pickers
  exclude held skills and reject duplicates).
- **Campaign backgrounds (engine):** `define_content` kind `BACKGROUND` stores a background in the seeded
  shape (three abilities, an Origin feat, two skills, a tool, equipment options), validated against installed
  content; `Origins.resolveBackground` looks installed content up first and the campaign's own second, so a
  Noble or a Fen Keeper behaves exactly like an SRD background in drafts, promotions and on the sheet.
- **Tool proficiency in checks (engine):** `resolve_check` takes a `tool`; the actor's proficiency bonus applies
  to an ability check made with a tool they are proficient with, and a skill check made with a tool they are also
  proficient in has advantage, cancelling a GM-imposed disadvantage instead (SRD 5.2.1 "Tools and Skills
  Together"). Checks are legal mid-encounter and never advance the initiative order.
