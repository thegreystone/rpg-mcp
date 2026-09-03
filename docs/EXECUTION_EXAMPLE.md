# RPG MCP Server — Execution Example

**Status:** Design example  
**Purpose:** Demonstrate the complete intended player experience and MCP interaction model, from first launch through
campaign setup, context reset, gameplay, encounters, persistent memories, Narrative Director intervention, session
suspension, and later resumption.

This document is deliberately concrete. Parts I–IX (setup through the context boundary) use the real tool names and
response shapes of the current server, trimmed for readability; later parts are illustrative. `MCP_PROTOCOL.md` defines
the authoritative protocol.

---

## Reference Convention Used in This Example

The database uses numeric primary keys scoped by table. MCP traces render them as typed references so the AI can see
what kind of entity an ID denotes:

```text
character:1
location:3
event:17
```

Thus `character:1` means row `1` in the character table. The string itself is an MCP/context notation, not the database
primary-key value.

Names and roles are mutable. A character can be renamed, die, leave the party, or become player-controlled while
retaining the same reference.

Player-facing dialogue never exposes these technical references unless there is a specific debugging/admin reason. The
YAML blocks below represent **technical MCP responses**, while prose under `GM → Player` represents what the player
actually experiences.

## 1. Scenario

A player launches a fresh RPG MCP server and connects an AI Game Master.

The player wants a fantasy campaign but does not want to design everything manually. They enjoy magic, character
relationships, exploration, humor, and a serious underlying plot.

The example deliberately exercises:

- age/content-profile setup
- campaign preferences
- rules configuration
- continuation policy
- character creation
- companion preferences
- AI-generated adventure setup
- setup commit
- context reset
- exploration
- relationship development
- combat
- XP
- Narrative Director intervention
- world events delivered diegetically
- episodic memory
- session persistence
- resumption in a new AI conversation

---

# PART I — FIRST CONTACT

## The Interview Pattern

Everything in Parts I–VIII follows one conversational shape, and it matters more than any individual tool call:

1. **One question per turn.** The harness walks setup through a fixed sequence of states (
   `SETUP_CONTENT_PROFILE → SETUP_EXPERIENCE → SETUP_RULES → SETUP_CONTINUATION → CHARACTER_CONCEPT → CHARACTER_RULES → CHARACTER_PERSONALITY → CHARACTER_EQUIPMENT → CHARACTER_REVIEW → PARTY_DESIGN → ADVENTURE_INITIALIZATION → CAMPAIGN_REVIEW`).
   The GM asks about the current state and nothing else. Never front-load the whole questionnaire.
2. **All the alternatives, each with what it means.** Every option the server allows is listed, numbered, with a
   one-line meaning — never a pruned "sensible subset", never an invented option. The server hands them over ready-made:
   every setup and draft response carries `decisions`, each with its `options` (value, label, description, sometimes a
   `recommended` flag). Where one choice is the obvious default it is marked *(recommended)*. When the legal set is
   genuinely large (a full spell list) it is shown in compact groups, not skipped.
3. **Always a custom answer.** Every question ends with an invitation to answer in the player's own words. For creative
   questions, **"surprise me"** is an explicit option and is stored as `SURPRISE_ME`.
4. **Persist before asking the next question.** Each answer becomes one `update_campaign_setup` /
   `update_character_draft` call. Sections merge, so recording `experience.tone` now and `experience.relationship_focus`
   two questions later is fine.

Clients with a native choice widget (Claude Code's `AskUserQuestion`, for instance) render exactly this: the numbered
options as buttons plus an "Other" free-text field. In a plain chat the GM writes the numbered list. The pattern is the
same either way.

The `outstanding` list the server returns is the **AI's checklist**, not a form to show the player.

---

## 2. Server Connection

The AI connects to the MCP server.

### AI → MCP

```text
get_server_state()
```

### MCP → AI

```yaml
protocol_version: "0.1.0"
harness_state: CAMPAIGN_SELECTION
active_campaign: null
pending_transaction: null
campaigns: [ ]
allowed_operations: [ get_server_state, create_campaign, open_campaign ]
policy_summary:
  available_profiles: [ PEGI_3, PEGI_7, PEGI_12, PEGI_16, PEGI_18 ]
  provider_policy: externally_enforced
rulesets: [ "srd5e:5.2.1" ]
```

The AI now knows that no campaign exists. It reserves one before asking anything, so every answer has somewhere to live.

### AI → MCP

```text
create_campaign(operation_id="01J...", ruleset="srd5e")
```

### MCP → AI

```yaml
campaign: campaign:1
status: SETUP
setup_status: OPEN
setup: { }
outstanding:
  - "content_profile.profile — choose a PEGI content profile (optionally record player_age first)"
  - "experience — tone, themes, authorship preferences (SURPRISE_ME is a valid answer)"
  - "rules — ability_generation, progression, gm_override_policy (defaults apply if left empty)"
  - "continuation.policy — CHECKPOINT, ENCOUNTER_RETRY or IRONMAN"
  - "player_character — create_character_draft for the player's character"
  - "party — companion preferences via update_party_design (may be delegated)"
  - "adventure — premise, opening_location, immediate_goal (the GM authors these)"
constraints:
  profiles: [ PEGI_3, PEGI_7, PEGI_12, PEGI_16, PEGI_18 ]
  player_profile_cap: PEGI_18
  ability_generation: [ STANDARD_ARRAY, POINT_BUY, ROLL_4D6_DROP_LOWEST ]
  progression: [ XP, MILESTONE ]
  gm_override_policy: [ DISABLED, EXPLICIT_AUDITED ]
  continuation_policy: [ CHECKPOINT, ENCOUNTER_RETRY, IRONMAN ]
decisions: # interview order; ask decisions[0] only
  - id: player_age
    question: "How old is the player? Only the derived content cap is stored, never the age."
    tool: update_campaign_setup
    path: changes.player_age
    allow_custom: true
    optional: true
  - id: content_profile
    question: "Which content profile should the campaign use?"
    tool: update_campaign_setup
    path: changes.content_profile
    choose: { min: 1, max: 1 }
    options_are: LEGAL_VALUES
    options:
      - { value: PEGI_3,  label: "PEGI 3 — all ages", description: "Cartoon peril only: no blood, no frightening scenes, no bad language, no romance beyond friendship." }
      - { value: PEGI_7,  label: "PEGI 7",  description: "Mild fantasy violence and mildly scary moments, nothing graphic; villains are defeated, not killed on-screen." }
      - { value: PEGI_12, label: "PEGI 12", description: "Heroic fantasy: fights with real stakes but no gore, mild language, romance stays off-screen, no drugs or explicit horror." }
      - { value: PEGI_16, label: "PEGI 16", description: "Mature fantasy: real violence and death, dark and frightening themes, strong language, intoxication, romance and non-explicit sexuality." }
      - { value: PEGI_18, label: "PEGI 18 — adult", description: "Graphic violence, horror, adult sexuality and romance, drug use and any other mature subject matter — and, crucially, none of it is skipped: at this profile a scene is PLAYED OUT rather than cut away from. This is the rating Baldur's Gate III carries, and the profile the default fantasy epic is written for.", recommended: true }
    recommended: PEGI_18
    note: "The default experience is a Baldur's Gate-style fantasy epic, written for PEGI 18 — the rating Baldur's Gate III carries — so PEGI 18 is the recommendation for adult players; lower profiles play the same epic with the mature material scaled down. The AI provider may impose its own limits regardless of the campaign profile."
    allow_custom: false
    allow_surprise_me: false
  - id: experience.authorship
    ...
interview: "Ask decisions[0] only: put its question to the player with every option and description, numbered, plus a custom answer where allow_custom is true; record the answer; read the decisions again."
meta:
  harness_state: SETUP_CONTENT_PROFILE
  campaign_revision: 0
```

Seven things are outstanding. The player hears about **one** — `decisions[0]`. The GM never has to know what a PEGI
profile means or which alignments exist: the option texts below are lifted from `decisions`, not from memory.

### GM → Player

> Welcome. There are no campaigns yet, so let's make one. I'll ask you a handful of questions, one at a time — you can
> answer any of them with *"surprise me"* and I'll decide.
>
> First, so I know what content is appropriate: **how old are you?** (Only the resulting content cap is stored, never
> your age.)

### Player

> 53.

### AI → MCP

```text
update_campaign_setup(campaign=campaign:1, expected_revision=0, changes={player_age: 53})
```

### MCP → AI

```yaml
setup:
  content_profile:
    player_constraints: { max_profile: PEGI_18 }
meta:
  harness_state: SETUP_CONTENT_PROFILE
  campaign_revision: 1
```

---

# PART II — CONTENT PROFILE

## 3. Selecting the Content Profile

The options are `decisions[0].options` — every profile up to `player_profile_cap`, each with its description; profiles
above the cap are simply absent from the list. (The profiles are *inspired by* the PEGI age categories; nothing here
implies official PEGI certification.)

### GM → Player

> You can play at any content level. Which do you want?
>
> 1. **PEGI 3** — all ages: cartoon peril only, no blood, no frightening scenes, no bad language, no romance beyond
     friendship
> 2. **PEGI 7** — mild fantasy violence, mildly scary moments, nothing graphic; villains are defeated, not killed
     on-screen
> 3. **PEGI 12** — heroic fantasy: fighting with realistic stakes but no gore, mild language, romance stays off-screen,
     no drugs or explicit horror
> 4. **PEGI 16** — mature fantasy: real violence and death, darker and frightening themes, strong language, alcohol and
     intoxication, romance and non-explicit sexuality
> 5. **PEGI 18** — adult: graphic violence, horror, adult sexuality and romance, drug use, and any other mature
     subject matter, none of it cut away from *(recommended for adult players)*
>
> The default experience is a Baldur's Gate-style fantasy epic, and it is written for PEGI 18 — the rating Baldur's
> Gate III carries. A lower profile plays the same epic with the mature material scaled down. Pick a number, or tell
> me in your own words. (Your AI provider may still impose its own limits regardless of the campaign setting.)

### Player

> 5. Explicit adult sexuality and romance are fine — don't sanitize the world.

### AI → MCP

```text
update_campaign_setup(campaign=campaign:1, expected_revision=1, changes={content_profile: "PEGI_18"})
```

### MCP → AI

```yaml
setup:
  content_profile: { profile: PEGI_18, player_constraints: { max_profile: PEGI_18 } }
meta:
  harness_state: SETUP_EXPERIENCE
  campaign_revision: 2
```

The server stores the campaign intent independently of provider restrictions.

---

# PART III — CAMPAIGN EXPERIENCE

## 4. How Much the Player Wants to Author

### GM → Player

> How much do you want to design yourself?
>
> 1. **Ask me a few questions, then surprise me with the actual story** *(recommended)*
> 2. **Surprise me with everything** — skip straight to character creation
> 3. **I have a premise** — I'll describe the world and story I want
>
> Or something in between — just say.

### Player

> 1.

### AI → MCP

```text
update_campaign_setup(campaign=campaign:1, expected_revision=2,
	changes={experience: {authorship: "GUIDED — a few questions, GM authors the story"}})
```

Had the player chosen 2, the AI would have stored `experience: "SURPRISE_ME"` and jumped to Part IV.

---

## 5. Tone and Themes

Three questions, three calls. The `experience` section accumulates.

### GM → Player

> What flavour of fantasy? The default is a Baldur's Gate-style fantasy epic — if you skip this or say "surprise me",
> that is what you get.
>
> 1. **Fantasy epic** *(the default)* — a world-threatening plot, vivid companions with their own pasts, secrets and
     agendas, romance that develops naturally, hard moral choices with lasting consequences, and a world where almost
     anyone can die
> 2. **Classic heroic** — dungeons, wilderness, a serious plot with room for humour
> 3. **Grim and gritty** — low magic, moral compromise, scarce resources
> 4. **Intrigue and mystery** — courts, secrets, investigation over combat
> 5. **Lighthearted romp** — jokes first, stakes second
>
> Or describe your own mix.

### Player

> Mostly 2, but with a real mystery underneath — I like exploration and puzzling things out. Keep the main story
> serious.

A custom answer that blends options is normal. The AI maps it onto the schema rather than asking the player to.

### AI → MCP

```text
update_campaign_setup(campaign=campaign:1, expected_revision=3,
	changes={experience: {
		fantasy_style: "Classic high fantasy",
		tone: "Serious core with humorous moments",
		themes: ["mystery", "exploration", "character drama"]}})
```

### GM → Player

> How important are relationships between characters?
>
> 1. **Central** — friendship, rivalry and romance are a main reason to play *(the fantasy epic's default)*
> 2. **Present** — they matter, but the plot leads
> 3. **Comradeship only** — keep romance out of it
>
> Or tell me what you have in mind.

### Player

> 1. But I don't want to know in advance what will happen.

### AI → MCP

```text
update_campaign_setup(campaign=campaign:1, expected_revision=4,
	changes={experience: {
		relationship_focus: "HIGH — friendship, rivalry and romance welcome, including explicit adult romance; outcomes emergent, never announced in advance"}})
```

### GM → Player

> Anything you specifically **don't** want in this story?
>
> 1. **Nothing** — surprise me
> 2. **Yes** — I'll list them

### Player

> 1.

### AI → MCP

```text
update_campaign_setup(campaign=campaign:1, expected_revision=5,
	changes={experience: {excluded_themes: []}})
```

### MCP → AI

```yaml
meta:
  harness_state: SETUP_RULES
  campaign_revision: 6
```

---

# PART IV — RULES CONFIGURATION

## 6. Ability Score Generation

Options are `constraints.ability_generation`, verbatim.

### GM → Player

> How should we generate your ability scores?
>
> 1. **Point buy** — 27 points, every score between 8 and 15; full control
> 2. **Standard array** — place 15, 14, 13, 12, 10, 8
> 3. **Roll 4d6, drop the lowest** — the server rolls in the open; no rerolls
>
> Which one?

### Player

> 1.

### AI → MCP

```text
update_campaign_setup(campaign=campaign:1, expected_revision=6,
	changes={rules: {ability_generation: "POINT_BUY", allow_reroll: false}})
```

---

## 7. Progression and GM Overrides

Two questions, asked separately, both landing in `rules`.

### GM → Player

> Character progression:
>
> 1. **XP** — you earn experience from encounters and quests and level when the number says so
> 2. **Milestone** — you level at story beats

### Player

> 1. I like seeing the number go up.

### AI → MCP

```text
update_campaign_setup(campaign=campaign:1, expected_revision=7,
	changes={rules: {progression: "XP"}})
```

### GM → Player

> Last rules question. May I make explicit GM overrides in exceptional cases for story reasons? Every override is
> logged; the engine never pretends one was a normal dice result.
>
> 1. **Allowed, audited** *(recommended)*
> 2. **Disabled** — the rules are the rules

### Player

> 1, but don't cheat constantly to protect me from my own mistakes.

The mechanical part goes into `rules`; the instruction about *how* to use it is a GM note, recorded once the adventure
section exists (Part VII).

### AI → MCP

```text
update_campaign_setup(campaign=campaign:1, expected_revision=8,
	changes={rules: {gm_override_policy: "EXPLICIT_AUDITED"}})
```

### MCP → AI

```yaml
meta:
  harness_state: SETUP_CONTINUATION
  campaign_revision: 9
```

---

## 8. Character Death and Failure

### GM → Player

> How unforgiving should death be?
>
> 1. **Checkpoint** — return to the last major checkpoint, including to undo a decision you regret
> 2. **Encounter retry** — restart a fight you lost; nothing else can be undone
> 3. **Ironman** — no rollback. If your character dies and a companion survives, you may take over the companion; if
     everyone dies, the campaign ends.

### Player

> 1. I don't want fifty hours of story disappearing because I did something stupid.

### AI → MCP

```text
update_campaign_setup(campaign=campaign:1, expected_revision=9, changes={continuation: "CHECKPOINT"})
```

### MCP → AI

```yaml
setup:
  continuation: { policy: CHECKPOINT }
meta:
  harness_state: CHARACTER_CONCEPT
  campaign_revision: 10
```

---

# PART V — CHARACTER CREATION

## 9. Character Concept

The `player_character` decision has no closed option set — a concept is free text — but the AI fetches the legal species
and classes so the alternatives it mentions are real. (`get_character_choices` returns every entry with a one-line
`description`; the glosses below are those.)

### AI → MCP

```text
get_character_choices(campaign=campaign:1, scope=ALL)
```

### MCP → AI

```yaml
ability_generation:
  campaign_method: POINT_BUY
  point_buy: { min: 8, budget: 27, max: 15 }
species: [ Dragonborn, Dwarf, Elf, Gnome, Goliath, Halfling, Human, Orc, Tiefling ]   # with ids, speed, size
classes:
  - { id: "srd5e:class/sorcerer", name: Sorcerer, hit_die: 6, primary_abilities: [ CHA ],
      saving_throws: [ CON, CHA ], skill_choices: { count: 2, options: [ arcana, deception, insight, intimidation, persuasion, religion ] },
      starting_gold_gp: 50 }
  - ...
alignments: [ LAWFUL_GOOD, NEUTRAL_GOOD, CHAOTIC_GOOD, ... ]
```

### GM → Player

> Now, who are you?
>
> **Species:** Dragonborn (draconic ancestry, breath weapon) · Dwarf (tough, darkvision, stonecraft) · Elf (keen senses,
> trance instead of sleep, a little magic) · Gnome (small, clever, tinkering or illusion) · Goliath (big, strong, giant
> blood) · Halfling (small, lucky, brave) · Human (adaptable, an extra skill) · Orc (strong, relentless, darkvision) ·
> Tiefling (infernal heritage, fire resistance, innate magic)
>
> **Classes:** Barbarian (rage, huge hit points) · Bard (music, charm, jack of all trades) · Cleric (divine magic,
> healing, armour) · Druid (nature magic, shapeshifting) · Fighter (weapons, armour, the most attacks) · Monk (unarmed
> martial arts, speed) · Paladin (holy warrior, smites, oaths) · Ranger (wilderness, tracking, bow) · Rogue (stealth,
> skills, sneak attack) · Sorcerer (innate magic from your bloodline, Charisma-driven) · Warlock (magic from a pact with
> a
> patron) · Wizard (learned magic, the biggest spellbook)
>
> 1. **Describe them** — a name, a species and class, a sentence of concept
> 2. **Pick an archetype** and I'll flesh it out: the reluctant noble, the veteran, the hedge-witch, the street thief…
> 3. **Surprise me**

### Player

> 1. Richard Greystone, human sorcerer. Intelligent, charismatic, a little too confident. Fundamentally decent but
     capable of questionable decisions. Younger son of minor nobility.

### AI → MCP

```text
create_character_draft(campaign=campaign:1, player_controlled=true,
	initial={name: "Richard Greystone", species: "Human", class: "Sorcerer", presentation: "male",
	         personality: "Intelligent and charismatic; highly self-confident, fundamentally decent, willing to make morally questionable pragmatic choices."})
```

### MCP → AI

```yaml
character: character:1
lifecycle: DRAFT
player_controlled: true
revision: 1
sheet:
  classes: [ "Sorcerer 1" ]
  hp: { max: 6 }            # hit die; CON modifier applied once scores exist
  spellcasting: { ability: CHA, cantrips_known_limit: 4, prepared_limit: 2 }
  starting_equipment: { option: B, chosen_explicitly: false, available_options: [ A, B ] }
next_steps:
  - "assign ability_scores (POINT_BUY, 27 points)"
decisions: # the draft's own interview, in creation order
  - id: ability_scores
    question: "How are the six ability scores placed? (Point buy)"
    tool: update_character_draft
    path: changes.ability_scores
    allow_custom: true
    method: POINT_BUY
    primary_abilities: [ CHA ]
    point_buy: { budget: 27, min: 8, max: 15, cost: { "8": 0, "9": 1, "10": 2, "11": 3, "12": 4, "13": 5, "14": 7, "15": 9 } }
    note: "Do the arithmetic for the player: offer two or three complete spreads within the budget, plus placing them themselves."
  - id: cantrips
    question: "Which 4 cantrips does the character know?"
    choose: { min: 4, max: 4 }
    options_are: LEGAL_VALUES
    options: [ { value: "srd5e:spell/acid-splash", label: "Acid Splash", description: "A bubble of acid bursts …", level: 0, school: Evocation }, ... ]
  - id: spells
    question: "Which 2 spells does the character prepare?"
    choose: { min: 2, max: 2 }
    options: [ ... ]
  - id: starting_equipment
    options:
      - { value: A, label: "Option A — equipment kit", description: "Spear, Dagger ×2, Arcane Focus (Crystal), Dungeoneer's Pack, 28 gp." }
      - { value: B, label: "Option B — money only", description: "50 gp to buy your own equipment in play." }
    optional: true
    default: B
  - id: personality
    ...
  - id: alignment
    options: [ { value: LAWFUL_GOOD, label: "Lawful Good", description: "Honour, duty and compassion; …" }, ... ]
    optional: true
meta:
  harness_state: CHARACTER_RULES
```

Skills appear once scores exist (the decision list is derived from what the draft still lacks). Every draft response —
`create_character_draft`, `update_character_draft`, `get_character_choices` — carries the same list.

The first player-controlled draft automatically becomes the campaign's `player_character`. Its reference never changes,
whatever gets renamed later.

---

## 10. Ability Scores

The AI does the arithmetic and offers spreads; the player is never asked to compute point costs.

### GM → Player

> Point buy: 27 points, every score starts at 8; raising a score to 9–13 costs one point per step, 14 costs 7 in total
> and 15 costs 9. Charisma drives your magic; Constitution keeps you alive; Dexterity is your armour.
>
> 1. **Classic caster** *(recommended)* — CHA 15, CON 14, DEX 13, INT 12, WIS 10, STR 8
> 2. **Sturdier** — CHA 15, CON 15, DEX 12, INT 10, WIS 10, STR 8
> 3. **Balanced** — CHA 14, CON 14, DEX 14, INT 12, WIS 10, STR 8
> 4. **Place them yourself** — tell me six numbers between 8 and 15 and I'll check the cost

### Player

> 1.

### AI → MCP

```text
update_character_draft(campaign=campaign:1, character=character:1, expected_revision=1,
	changes={ability_scores: {STR: 8, DEX: 13, CON: 14, INT: 12, WIS: 10, CHA: 15}})
```

### MCP → AI

```yaml
revision: 2
sheet:
  abilities: { STR: { score: 8, modifier: -1 }, DEX: { score: 13, modifier: 1 }, CON: { score: 14, modifier: 2 },
               INT: { score: 12, modifier: 1 }, WIS: { score: 10, modifier: 0 }, CHA: { score: 15, modifier: 2 } }
  hp: { max: 8 }
  spellcasting: { save_dc: 12, attack_bonus: 4 }
```

An illegal spread (28 points, a 16) fails immediately with `INVALID_ARGUMENT`; the AI fixes it and never shows the
player a rules error.

---

## 11. Skills

Options are `decisions[0].options` — the class's six skills, each with its ability and description;
`choose: {min: 2, max: 2}`.

### GM → Player

> You get two skills from the sorcerer list:
>
> 1. **Arcana** (INT) — recognising spells, magical effects and lore *(recommended)*
> 2. **Deception** (CHA) — lying convincingly
> 3. **Insight** (WIS) — reading people's intentions
> 4. **Intimidation** (CHA) — threats and menace
> 5. **Persuasion** (CHA) — winning people over honestly *(recommended)*
> 6. **Religion** (INT) — gods, rites and cults
>
> Name two, or say "recommended".

### Player

> Recommended.

### AI → MCP

```text
update_character_draft(campaign=campaign:1, character=character:1, expected_revision=2,
	changes={skills: ["srd5e:skill/arcana", "srd5e:skill/persuasion"]})
```

### MCP → AI

```yaml
revision: 3
meta:
  harness_state: CHARACTER_PERSONALITY
```

---

## 12. Appearance, Backstory and Goals

One question covers the whole narrative block; the player chooses how much to write.

### GM → Player

> Appearance and background:
>
> 1. **Let me propose something** from what you've told me — you edit
> 2. **You write it**
> 3. **Surprise me** entirely

### Player

> 1. Tall, dark hair, green eyes — you do the rest. And give me a reason he's adventuring instead of sitting comfortably
     at home.

### GM → Player

> How about this: you are the younger son of a minor noble house. Your sister inherited the responsibilities you were
> expected to share. Your magic manifested publicly during a diplomatic banquet and caused a scandal — not because
> anyone
> was hurt, but because something impossible appeared in the flames and spoke your childhood name. You've spent the last
> two years trying to discover what that meant.
>
> Keep it, change it, or start over?

### Player

> Keep it.

### AI → MCP

```text
update_character_draft(campaign=campaign:1, character=character:1, expected_revision=3,
	changes={
		age: 24,
		appearance: "Tall, dark-haired, green-eyed; good clothes gone slightly shabby from travel.",
		backstory: "Younger son of a minor noble house. Innate magic manifested publicly during a diplomatic banquet; an unexplained entity appeared in the flames and addressed him by his childhood name. He has spent two years seeking the meaning of the event.",
		goals: ["Learn what spoke to him from the flames", "Prove his magic is a gift, not a scandal"]})
```

This backstory also becomes an adventure-design seed for Part VII.

---

## 13. Alignment

### GM → Player

> Alignment — how he weighs law against freedom and good against self-interest:
>
> 1. **Lawful Good** — honour, duty and compassion; keeps his word even when it costs him
> 2. **Neutral Good** — does the decent thing without much regard for rules either way
> 3. **Chaotic Good** — compassionate, suspicious of rigid authority, breaks rules when the outcome justifies it *(my
     suggestion, from what you've told me)*
> 4. **Lawful Neutral** — order, tradition and procedure above personal feeling
> 5. **Neutral** — pragmatic balance; avoids extremes
> 6. **Chaotic Neutral** — personal freedom first; unpredictable
> 7. **Lawful Evil** — ambition pursued through structure, hierarchy and contracts
> 8. **Neutral Evil** — whatever serves him, no loyalty beyond convenience
> 9. **Chaotic Evil** — cruelty and appetite without restraint

### Player

> 3.

### AI → MCP

```text
update_character_draft(campaign=campaign:1, character=character:1, expected_revision=4,
	changes={alignment: "CHAOTIC_GOOD"})
```

---

## 14. Spells

The AI never types spell names from memory. The `cantrips` and `spells` decisions already list every spell a 1st-level
sorcerer may take, each with its level, school and a one-line summary; `get_content_definitions` is the fallback for
rules questions about a particular spell.

### AI → MCP

```text
get_content_definitions(campaign=campaign:1, kind=SPELL, text="sorcerer", limit=100)
```

### MCP → AI

```yaml
kind: SPELL
items:
  - { id: "srd5e:spell/acid-splash", name: "Acid Splash", kind: SPELL, level: 0, school: Evocation, classes: [ sorcerer, wizard ], summary: "A bubble of acid bursts at a point; …" }
  - { id: "srd5e:spell/magic-missile", name: "Magic Missile", kind: SPELL, level: 1, school: Evocation, classes: [ sorcerer, wizard ], summary: "Three glowing darts strike creatures you can see …" }
  - { id: "srd5e:spell/shield", name: "Shield", kind: SPELL, level: 1, school: Abjuration, classes: [ sorcerer, wizard ], summary: "…" }
  - ...
```

The summary carries the level, so the AI can show the player *all* of what a 1st-level character can take — never a
pruned subset — without fetching `FULL` payloads.

### GM → Player

> You know **four cantrips** (unlimited use) and prepare **two 1st-level spells** (two slots a day).
>
> **Cantrips:**
> 1. Acid Splash — acid damage to one or two adjacent targets
> 2. Chill Touch — necrotic damage, stops healing for a moment
> 3. Dancing Lights — floating lights, hands-free illumination
> 4. Fire Bolt — the strongest single-target damage cantrip *(recommended)*
> 5. Light — makes an object glow
> 6. Mage Hand — an invisible hand that fetches, opens and pulls levers at a distance *(recommended)*
> 7. Mending — repairs small breaks
> 8. Message — whisper to someone across a room
> 9. Minor Illusion — a small sound or image; endlessly useful *(recommended)*
> 10. Poison Spray — heavy poison damage, very short range
> 11. Prestidigitation — a bag of small tricks: clean, flavour, light a candle, a noble's party piece *(recommended)*
> 12. Ray of Frost — cold damage and slows the target
> 13. Shocking Grasp — lightning damage, lets you get away after touching someone
>
> **1st-level spells:**
> 1. Burning Hands — a cone of fire, hits several enemies
> 2. Charm Person — one person treats you as a friend for an hour
> 3. Chromatic Orb — a hard-hitting bolt of the damage type you choose
> 4. Color Spray — blinds weak creatures
> 5. Comprehend Languages — understand any spoken or written language
> 6. Detect Magic — sense magic nearby
> 7. Disguise Self — look like someone else
> 8. False Life — a buffer of temporary hit points
> 9. Feather Fall — survive a fall
> 10. Fog Cloud — a sphere of obscuring mist
> 11. Grease — a slick patch that knocks people over
> 12. Jump — triple jumping distance
> 13. Mage Armor — better armour class all day without wearing armour
> 14. Magic Missile — three darts that never miss *(recommended)*
> 15. Ray of Sickness — poison damage and nausea
> 16. Shield — a reaction that turns a hit into a miss *(recommended)*
> 17. Silent Image — a larger, silent illusion you can move
> 18. Sleep — drops a group of weak creatures
> 19. Thunderwave — a blast that shoves everyone near you away
>
> Choose four and two, say "recommended", or ask me about any of them.

### Player

> Recommended.

### AI → MCP

```text
update_character_draft(campaign=campaign:1, character=character:1, expected_revision=5,
	changes={cantrips: ["srd5e:spell/fire-bolt", "srd5e:spell/mage-hand", "srd5e:spell/minor-illusion", "srd5e:spell/prestidigitation"],
	         spells:   ["srd5e:spell/magic-missile", "srd5e:spell/shield"]})
```

### MCP → AI

```yaml
revision: 6
sheet:
  spellcasting:
    cantrips: [ "Fire Bolt", "Mage Hand", "Minor Illusion", "Prestidigitation" ]
    prepared: [ "Magic Missile (1)", "Shield (1)" ]
meta:
  harness_state: CHARACTER_EQUIPMENT
```

Over the limit, or a spell not on the class list, fails at once.

---

## 15. Equipment and Starting Wealth

Starting wealth belongs to the rules engine. In SRD 5.2.1 it is a fixed per-class choice: an equipment bundle plus a
little gold, or a flat gold amount. The options came back with the class definition (
`get_content_definitions kind=CLASS`). The GM does not invent extra gold because "a noble ought to be richer".

### GM → Player

> Starting gear:
>
> 1. **The kit** — spear, two daggers, a crystal arcane focus, a dungeoneer's pack, and 28 gp
> 2. **Just the money** — 50 gp to spend in town yourself

### Player

> 1.

### AI → MCP

```text
update_character_draft(campaign=campaign:1, character=character:1, expected_revision=6,
	changes={starting_equipment: {option: "A"}})
```

### MCP → AI

```yaml
revision: 7
sheet:
  starting_equipment:
    option: A
    chosen_explicitly: true
    contents:
      items: [ { item: "srd5e:item/spear", quantity: 1 }, { item: "srd5e:item/dagger", quantity: 2 },
               { item: "srd5e:item/arcane-focus-crystal", quantity: 1 }, { item: "srd5e:item/dungeoneers-pack", quantity: 1 } ]
      gold_gp: 28
  money: { display: "0 cp" }   # staged; becomes real at campaign commit
meta:
  harness_state: CHARACTER_REVIEW
```

---

## 16. Character Review

### AI → MCP

```text
validate_character_draft(campaign=campaign:1, character=character:1)
```

### MCP → AI

```yaml
valid: true
violations: [ ]
warnings: [ ]
review: { ...full sheet... }
```

The GM reads the review back as prose, not as a sheet.

### GM → Player

> **Richard Greystone**, human sorcerer, younger son of a minor house. Tall, dark-haired, green-eyed, charming and
> slightly too confident. Two years ago your uncontrolled magic produced an apparition that knew a private childhood
> name;
> you have been chasing that mystery since. Chaotic Good. Not physically imposing (STR 8) but quick, resilient and very
> charismatic. Fire Bolt and Magic Missile for trouble, Shield for when trouble finds you. 8 hit points, 28 gold, a
> spear,
> two daggers and a pack.
>
> 1. **Perfect** — lock him in
> 2. **Change something** — tell me what

### Player

> 2. Change his age to 22.

### AI → MCP

```text
update_character_draft(campaign=campaign:1, character=character:1, expected_revision=7, changes={age: 22})
```

### GM → Player

> Done — 22. Lock him in?

### Player

> Yes.

### AI → MCP

```text
commit_character_draft(campaign=campaign:1, character=character:1, expected_revision=8)
```

### MCP → AI

```yaml
character: character:1
lifecycle: FINALIZED_DRAFT
revision: 9
meta:
  harness_state: PARTY_DESIGN
```

The character-design conversation is now disposable. The character is not active gameplay truth yet: HP, money and
inventory become real only at campaign commit.

---

# PART VI — PARTY DESIGN

## 17. Companion Preferences

### GM → Player

> You can shape the people who may travel with you.
>
> 1. **Surprise me** — I'll introduce companions as the story allows
> 2. **Broad preferences** — roles, personalities, whether romance is on the table
> 3. **Define them** — describe specific companions you want

### Player

> 2. I'd like a rogue because I'm terrible at that stuff. Someone who can handle themselves in a fight. Romance
     somewhere, but don't tell me who — let it emerge. And make them memorable; I don't want three obedient henchmen.

### AI → MCP

```text
update_party_design(campaign=campaign:1,
	preferences={
		authorship: "GM-authored from broad preferences",
		desired_roles: ["rogue / scout", "frontline fighter"],
		relationship_seeds: [{kind: "romance", target: "SURPRISE_ME", note: "Emergent; never announced"}],
		companion_agency: "HIGH — companions have opinions, secrets and lines they will not cross",
		personality_distinctiveness: "HIGH",
		notes: "No obedient henchmen."})
```

### MCP → AI

```yaml
outstanding: [ ]
meta:
  harness_state: ADVENTURE_INITIALIZATION
  campaign_revision: 11
```

Intentions are Director planning state, never guarantees. Nothing here decides who the romance is with.

---

# PART VII — ADVENTURE INITIALIZATION

## 18. The GM Authors the Adventure

The player chose *guided surprise*, so the adventure is authored, not asked for — with one exception: the GM may offer a
single framing choice.

### GM → Player

> Last thing before we begin. Where do we open?
>
> 1. **A town** — you arrive somewhere with people, rumours and a lead to follow
> 2. **On the road** — already travelling, trouble finds you
> 3. **Surprise me**

### Player

> 3.

The AI writes the premise, the GM-only truth, the opening location, the immediate goal and the player's earlier
instruction about overrides — all in one `adventure` section.

### AI → MCP

```text
update_campaign_setup(campaign=campaign:1, expected_revision=11,
	changes={adventure: {
		premise: "Richard Greystone follows the last clue left by Professor Aldren, a scholar who wrote to him about the apparition in the flames and died before they could meet. The letter names only a town, Bellhaven, and an inn: the Copper Kettle.",
		background_truth: "The apparition is connected to an ancient magical boundary that is beginning to fail. Several factions have noticed impossible magical events; one of them is deliberately accelerating the failures. Aldren was killed for what he knew.",
		opening_location: {name: "Bellhaven", kind: "SETTLEMENT",
			description: "A river-trade town in the kingdom of Avarra: slate roofs tumbling downhill to a barge-crowded silver river, a south gate, a market that packs up at dusk."},
		immediate_goal: "Find the late Professor Aldren's contact at the Copper Kettle.",
		start_time: "Day 1, 16:40",
		seeds: [
			{intention: "A rogue companion is in Bellhaven with her own reasons to be interested in Aldren's death", hooks: ["the Copper Kettle", "the broken wagon at the gate"]},
			{intention: "Northern caravans report impossible scorch marks", hooks: ["broadsheet", "teamsters"]}],
		gm_notes: "Player asked not to be protected from their own mistakes: overrides only for genuine rules or bookkeeping corrections, never to rescue the player. Romance target must emerge; never announce it."}})
```

### MCP → AI

```yaml
outstanding: [ ]
meta:
  harness_state: CAMPAIGN_REVIEW
  campaign_revision: 12
```

Had the player chosen 3 for authorship in Part III, this is where they would have dictated the premise instead, and the
AI would have stored their words.

---

# PART VIII — CAMPAIGN REVIEW AND COMMIT

## 19. Final Setup Review

### AI → MCP

```text
validate_campaign_setup(campaign=campaign:1)
```

### MCP → AI

```yaml
valid: true
violations: [ ]
warnings: [ ]
delegated: [ ]
review:
  content_profile: PEGI_18
  rules: { ability_generation: POINT_BUY, progression: XP, gm_override_policy: EXPLICIT_AUDITED, starting_wealth: FIXED }
  continuation_policy: CHECKPOINT
  player_character: { ref: character:1, name: "Richard Greystone", lifecycle: FINALIZED_DRAFT }
  party: { ...preferences... }
  adventure: { ...premise, opening_location, immediate_goal... }   # background_truth is GM-only and is not read back
```

### GM → Player

> We're ready.
>
> You begin as Richard Greystone in Bellhaven, a river town in Avarra, following the last clue left by a scholar who may
> have known something about the apparition that changed your life. Exploration, mystery and the people you meet will
> carry the story; the main plot is serious, with room to laugh. Companions arrive naturally. XP progression, checkpoint
> deaths, audited GM overrides, PEGI 18.
>
> 1. **Start the campaign**
> 2. **Change something first**

### Player

> 1.

### AI → MCP

```text
commit_campaign_setup(campaign=campaign:1, expected_revision=12)
```

### MCP → AI

```yaml
campaign: campaign:1
title: "The Apparition in the Flames"      # generated from the premise when no title was given
status: READY_TO_PLAY
player_character: character:1
activated_characters: [ character:1 ]
starting_equipment:
  character:1:
    option: A
    money_cp: 2800
    items: [ Spear, Dagger ×2, Arcane Focus (Crystal), Backpack, Caltrops, Crowbar, Oil ×2, Rations ×10, Rope, Tinderbox, Torch ×10, Waterskin ]
opening_location: location:1
immediate_goal: story_beat:1
checkpoint_created: checkpoint:1
initial_narrative_state: { seeds: [ seed:1, seed:2 ], ... }
setup_locked: true
next_state: SESSION_BOOTSTRAP
requires_context_reset: true
```

One transaction: the setup freezes, the draft becomes an `ACTIVE` character with real HP, money and inventory, the
opening location and clock exist, the immediate goal is a story beat, and (because the policy is CHECKPOINT, not
IRONMAN) `checkpoint:1` marks the campaign start.

---

# PART IX — THE CONTEXT BOUNDARY

## 20. Setup Context Is Discarded

This is a deliberate architectural boundary. The AI behaves as though a fresh conversation is starting and asks the
server for everything.

### AI → MCP

```text
bootstrap_session(campaign=campaign:1)
```

### MCP → AI

```yaml
harness_state: EXPLORATION
game_time: { instant: "Day 1, 16:40" }
location: { ref: location:1, name: Bellhaven, kind: SETTLEMENT, materialization: SEMANTIC }
player_character:
  ref: character:1
  lifecycle: ACTIVE
  hp: { current: 8, max: 8 }
  armor_class: { value: 11, basis: "Unarmored (10 + DEX)" }
  spellcasting: { slots: { "1": { max: 2, current: 2 } }, cantrips: [ ... ], prepared: [ ... ] }
  money: { display: "28 gp" }
  inventory: [ ...12 entries with inventory:N refs... ]
party: [ { ref: character:1, name: "Richard Greystone", membership: ACTIVE, player_controlled: true } ]
story_beats: [ { ref: story_beat:1, title: "Find the late Professor Aldren's contact at the Copper Kettle.", state: AVAILABLE } ]
adventure:
  premise: "..."
  immediate_goal: "..."
  gm_only: { background_truth: "...", gm_notes: "..." }
recent_events: [ { ref: event:1, type: CAMPAIGN_STARTED, summary: "Richard Greystone begins the campaign ... at Bellhaven." } ]
director_seeds: [ seed:1, seed:2 ]
session: session:1
allowed_operations: [ resolve_check, record_memory, advance_time, create_checkpoint, trade, grant_loot,
                      materialize_character, materialize_location, start_encounter, cast_spell,
                      commit_director_changes, suspend_session, ... ]
```

The entire wizard conversation is unnecessary from this point onward.

### The opening Director pass

Before the first line of narration the AI makes the world solid enough to play in. This is the slow narrative loop's
first turn, and it happens in a handful of calls:

```text
get_director_context(campaign=campaign:1, scope=INITIAL_CAMPAIGN)

materialize_location(campaign=campaign:1, location=location:1,
	spec={features: [{name: "South Gate", ...}, {name: "The Copper Kettle", ...}],
	      children: [{name: "The Copper Kettle", kind: BUILDING, ...}],
	      secrets: [{name: "Aldren's contact", description: "..."}]})            # GM_ONLY

materialize_character(campaign=campaign:1, source="Scout", name="...", personality="...")   # the rogue, from an installed stat block

commit_director_changes(campaign=campaign:1, review_note="Opening review",
	changes=[
		{kind: WORLD_EVENT, title: "Impossible scorch marks on the northern caravan road", channels: [NEWSPAPER, RUMOR, MERCHANT], locations: [location:1]},
		{kind: COMPANION_INTRO, intention: "...", hooks: [...], materialized_character: character:2},
		{kind: QUEST, title: "Aldren's last letter", objective: "...", status: OFFERED}])
```

Two things worth knowing from doing this for real:

- `materialize_character` takes an **installed creature definition** (`Scout`, `Commoner`, `srd5e:creature/wolf`); there
  is no `Noble` stat block — use `Commoner` for ordinary people and describe the rest.
- Fields that name a person (`issuer`, `materialized_character`) want a `character:N` reference, not a name. Put "Reeve
  Hollowell" in the objective text instead.

Now the camera can roll.

---

# PART X — GAMEPLAY BEGINS

## 21. Opening Scene

### GM → Player

> Bellhaven smells of rain, river water and wood smoke.
>
> Beyond the south gate, slate roofs tumble downhill toward a broad silver river crowded with barges. Merchants are
> packing away afternoon stalls while lamplighters begin their rounds.
>
> Somewhere in this town, Professor Aldren expected you to find a person he trusted.
>
> His letter gave you only a name for the meeting place:
>
> **The Copper Kettle.**
>
> A boy beside the gate is loudly selling the evening broadsheet. Across the street, two city watchmen are arguing with
> a wagon driver whose axle has broken spectacularly in the mud.
>
> What do you do?

### Player

> I buy a newspaper. Maybe there's something interesting going on.

No mechanical check is required.

The GM needs current money and the price of a broadsheet if that matters mechanically.

### AI → MCP

```text
purchase_item(
	character=character:1,
	item=BELLHAVEN_BROADSHEET,
	quantity=1
)
```

The engine updates money.

The GM asks for locally available news.

```text
get_diegetic_information(
	location=bellhaven,
	channel=NEWSPAPER,
	time=CURRENT
)
```

A previously committed Director seed can now surface naturally.

### MCP → AI

```yaml
headlines:
  - title: "THIRD NORTH ROAD CARAVAN LOST"
    summary: "Merchants demand action after another caravan failed to arrive. Survivors from an earlier attack described burned wagons but disagree about the cause."
  - title: "HARVEST FESTIVAL OPENS SATURDAY"
    summary: "Bellhaven prepares for its annual river festival."
```

### GM → Player

> The boy takes your coin and hands you a damp sheet of cheap paper.
>
> The main headline reads:
>
> **THIRD NORTH ROAD CARAVAN LOST**
>
> Apparently this is the third disappearance in six weeks. The odd part is a short paragraph near the bottom: a survivor
> from an earlier caravan insists the wagons were found burned without any ordinary sign of fire.
>
> The city editor clearly thinks he's drunk.
>
> The second-largest story concerns Saturday's harvest festival and an apparently existential dispute about whether
> geese should be allowed in the parade.

The player has encountered a world event without being told, "The Narrative Director generated a plot hook."

---

# PART XI — MEETING A COMPANION

## 22. Unexpected Player Choice

Instead of going to the inn:

### Player

> Forget the Copper Kettle for a moment. I want to find that survivor.

This deviates from the expected opening.

The GM does not force the inn scene.

It queries the relevant world information and may request the Director to reconsider the early plan if the deviation
becomes substantial.

The player eventually finds the survivor in a riverside tavern.

During the investigation, another character independently attempts to steal the survivor's notebook.

This creates a plausible opportunity to introduce Mara, the planned rogue companion, without forcing the originally
imagined meeting.

### GM → Player

> Your hand closes over the notebook at exactly the same moment as someone else's.
>
> The other hand is smaller than yours and wearing a black leather glove.
>
> You look up.
>
> A woman perhaps a few years younger than you is sitting on the windowsill despite the fact that you are quite certain
> she wasn't there three seconds ago.
>
> She looks at your hand.
>
> Then at you.
>
> "This is awkward," she says.

The new NPC is now materialized and persisted.

### AI → MCP

```text
materialize_character(seed=seed:1, scene_context=...)
```

Materialization creates an ordinary character-table row with its own stable numeric identity — here `character:2`. The
seed remains planning state; the resulting canonical NPC contains stable identity, appearance, goals, secrets,
personality and rules state.

A significant event is recorded:

```text
record_event(
	type=IMPORTANT_FIRST_MEETING,
	actors=[character:1, character:2],
	summary="Richard first met Mara while both attempted to take a missing caravan survivor's notebook in a riverside tavern."
)
```

The initial relationship references this event.

---

# PART XII — RELATIONSHIPS AND EPISODIC MEMORY

## 23. Relationship Development

Over several sessions, Richard and Mara travel together.

Their compact relationship state might eventually become:

```yaml
relationship:
  from: character:1
  to: character:2
  affection: high
  trust: high
  respect: high
  attraction: moderate
  tension: playful
  summary: >
    Close companions with strong mutual trust and frequent teasing.
    Mara admires Richard's courage but thinks his confidence regularly
    exceeds his judgment.
  significant_events:
    - first_meeting_event
    - bridge_rescue_event
    - mara_confession_event
```

Routine prompts need only the compact state.

The full bridge-rescue story is not loaded every turn.

---

## 24. A Major Relationship Event

Much later, suppose Richard proposes to an NPC named Elara.

The scene is narratively important.

The GM records a richer event:

```text
record_significant_memory(
	type=RELATIONSHIP_MILESTONE,
	actors=[character:1, character:4],
	title="Richard proposes to Elara",
	game_time="Year 2, Autumn 18, 22:14",
	location="Observatory roof, Caelus",
	summary="After the siege of Caelus, Richard took Elara to the restored observatory where they had first spoken honestly about their fears. He proposed using his mother's ring. Elara initially thought he was making another inappropriate joke, then realized he was serious and accepted while laughing and crying.",
	importance=CRITICAL
)
```

The relationship state references this event.

Years of real-world time can pass.

A completely new AI conversation can later receive:

### Player

> Elara, do you remember when I proposed to you?

The current prompt does not contain the proposal.

The GM sees that the relationship has significant memories and retrieves them.

```text
get_relationship_memories(
	character_a=character:1,
	character_b=character:4,
	query="proposal"
)
```

### MCP → AI

```yaml
matches:
  - event_id: proposal_event
    title: "Richard proposes to Elara"
    summary: "After the siege of Caelus ... accepted while laughing and crying."
    importance: CRITICAL
```

The GM can now answer in character:

> Elara looks at you for several seconds, then gives you the expression she reserves for questions she considers
> spectacularly stupid.
>
> "You mean the night you took me onto a freezing observatory roof, used your mother's ring, and chose the exact moment
> I was drinking wine to ask whether I'd spend my life with you?"
>
> Her mouth twitches.
>
> "No. Completely forgotten."

This is the intended memory experience.

The system conserved tokens for years without losing the event.

---

# PART XIII — COMBAT EXAMPLE

## 25. Encounter Creation

Later, the party is ambushed.

The GM decides the fiction and asks the engine to create the encounter.

```text
start_encounter(
	participants=[richard, mara, bandit_1, bandit_2, bandit_mage],
	sides={
		party:[richard,mara],
		raiders:[bandit_1,bandit_2,bandit_mage]
	},
	context=...
)
```

The engine rolls initiative and returns authoritative encounter state.

### Player

> I cast Fire Bolt at the mage.

The GM does not roll mentally.

```text
perform_attack(
	actor=richard,
	action=FIRE_BOLT,
	target=bandit_mage
)
```

### MCP → AI

```yaml
attack_roll:
  d20: 17
  modifier: 5
  total: 22
hit: true

damage:
  dice: [ 8 ]
  total: 8

target:
  hp_before: 11
  hp_after: 3

next_turn: mara
```

### GM → Player

> Fire snaps from your fingertips and catches the mage squarely in the chest. He staggers backward into the wagon, robes
> smoking, but somehow stays upright.
>
> Mara glances at you.
>
> "Three seconds in and you've already set someone on fire. Excellent. We're doing subtle today."

The narrative is free; the outcome is not.

---

# PART XIV — XP AND LEVEL-UP

## 26. Encounter Completion

The engine ends the encounter and awards mechanically defined XP.

```text
end_encounter(encounter_id=...)
```

### MCP → AI

```yaml
outcome: PARTY_VICTORY
xp_awarded:
  richard: 225
  mara: 225
level_up_eligible:
  richard: true
```

The harness enters `LEVEL_UP` at an appropriate safe boundary.

### GM → Player

> That puts Richard over the threshold for level 4.
>
> Before we continue, he can level up. You have a player-facing choice to make...

The AI retrieves legal choices and guides the player through them.

Nothing is committed until the player confirms.

```text
begin_level_up(character=character:1)
get_level_up_choices(...)
propose_level_up(...)
validate_level_up(...)
commit_level_up(...)
```

The completed level-up is a ledger event.

---

# PART XV — NARRATIVE DIRECTOR INTERVENTION

## 27. Director Trigger

The encounter completed a major quest and three in-world weeks have passed since the Director last reviewed the
campaign.

The server reports:

```yaml
director_trigger:
  recommended: true
  reasons:
    - MAJOR_QUEST_COMPLETED
    - SIGNIFICANT_TIME_PASSED
```

The GM requests Director context.

```text
get_director_context(scope=CAMPAIGN_REVIEW)
```

The Director sees:

- the caravan mystery has advanced
- the player ignored a court invitation
- a faction has had three weeks to act
- recent gameplay has been combat-heavy
- Mara's relationship with Richard has deepened
- an unresolved companion introduction remains
- the northern dragon rumors have not yet been resolved

The Director does **not** schedule another immediate fight.

It proposes/commits:

```yaml
changes:
  - faction_event:
      "The court faction acquires a magical artifact while Richard is absent."

  - story_seed:
      "A formal invitation arrives from Richard's estranged sister."

  - companion_opportunity:
      "Create a non-combat opportunity to encounter Sir Cedric."

  - pacing_intent:
      "Favor dialogue, investigation, and relationship development before the next major combat."

  - world_event:
      "Refugees from the north begin arriving in Bellhaven."
```

These become persistent plans/world facts as appropriate.

The GM remains responsible for how they enter play.

---

# PART XVI — FAILING FORWARD

## 28. Failed Check

Richard attempts to bluff a suspicious magistrate.

The GM requests the relevant check.

```text
resolve_check(
	character=character:1,
	ability=CHARISMA,
	skill=DECEPTION,
	difficulty=17
)
```

### MCP → AI

```yaml
roll:
  d20: 4
  modifier: 5
  total: 9
success: false
```

A poor design would say:

> The magistrate doesn't believe you.

and stop.

Instead:

### GM → Player

> The magistrate listens without interrupting.
>
> When you finish, she folds her hands.
>
> "Lord Greystone, that was an excellent story."
>
> You have just enough time to feel pleased.
>
> "Unfortunately, I received a letter from your sister this morning."
>
> She rings a small brass bell.
>
> The door behind you locks.
>
> "So perhaps we should start again."

Failure has created a new situation.

The failed roll remains mechanically real.

---

# PART XVII — CHECKPOINT AND DEATH

## 29. Checkpoint

Before a major dungeon encounter:

```text
create_checkpoint(reason=MAJOR_ENCOUNTER)
```

Richard later makes a catastrophically poor decision and dies.

The engine resolves death normally.

### MCP → AI

```yaml
player_character_status: DEAD
continuation_policy: CHECKPOINT
available_actions:
  - CONTINUE_WITH_SURVIVOR
  - RESTORE_CHECKPOINT
```

### GM → Player

> Richard is dead.
>
> Mara survived, barely.
>
> Because this campaign uses checkpoints, you have two choices: continue the story by taking control of Mara, accepting
> Richard's death as canonical—or return to the checkpoint before entering the crypt.

### Player

> Yeah, no. Rewind. That was an idiotic decision.

### AI → MCP

```text
restore_checkpoint(checkpoint_id=...)
```

The canonical state rolls back.

The old chat transcript has no authority over restored state.

The GM rebuilds context and resumes.

---

# PART XVIII — SESSION END

## 30. Player Stops Playing

### Player

> This is a good place to stop for tonight.

The GM ensures pending state is committed.

```text
suspend_session()
```

### MCP → AI

```yaml
session_closed: true
game_time: "Day 12, 23:18"
location: "The Copper Kettle — upstairs room"
session_summary:
  - "Richard and Mara returned to Bellhaven after..."
important_events_written: 4
director_state_saved: true
```

The AI can say goodnight.

Nothing important depends on the chat remaining available.

---

# PART XIX — THREE MONTHS LATER

## 31. Fresh AI Conversation

Assume the original conversation no longer exists.

The player connects to the MCP server.

### AI → MCP

```text
get_server_state()
```

### MCP → AI

```yaml
state: CAMPAIGN_SELECTION
campaigns:
  - ref: campaign:1
    title: "..."
    player_character: "Richard Greystone"
    status: ACTIVE
    last_played: ...
```

### GM → Player

> I found your existing Richard Greystone campaign. Continue that one?

### Player

> Yep.

The AI opens it.

```text
open_campaign(campaign:1)
bootstrap_session(campaign:1)
```

The Context Builder retrieves:

- current canonical state
- compact campaign summary
- current party
- current location/time
- active quests
- recent significant events
- relationship summaries
- relevant Director seeds

It does not retrieve every prior scene.

### GM → Player

> Last time, you and Mara had made it back to Bellhaven with evidence that the caravan attacks were connected to the
> same kind of magic that appeared at your family's banquet.
>
> You're upstairs at the Copper Kettle. It's late. Mara is asleep in the chair because she insisted she was "only
> resting her eyes."
>
> Downstairs, someone has just knocked on the inn's locked front door for the third time.
>
> Shall we continue?

The campaign has survived the loss of the original AI context.

---

# PART XX — WHAT THE EXAMPLE IMPLIES FOR THE MCP API

## 32. Tool Categories Exercised

This example requires operations in roughly these categories:

```text
Harness
	Campaign selection
	Wizard state
	Allowed operations

Campaign Setup
	Player/content profile
	Experience preferences
	Rules configuration
	Continuation policy
	GM policy
	Commit

Character Designer
	Draft creation
	Ability generation
	Choice discovery
	Validation
	Commit

Party Designer
	Preferences
	Companion seeds
	Introduction intentions

Narrative Director
	Context retrieval
	Story seeds
	Story beats
	World events
	Faction progression
	Pacing intentions

Session
	Bootstrap
	Suspend
	Resume

Context
	Scene context
	Relevant memories
	Director context

World
	Locations
	Materialization
	Diegetic information
	Time advancement

Character Runtime
	Character sheet
	HP/resources
	Conditions

Inventory/Economy
	Purchases
	Transfers
	Equipment
	Encumbrance

Dice/Rules
	Checks
	Saves
	Attacks
	Damage
	Healing

Encounter
	Start
	State
	Action
	Turn/round
	End

Progression
	XP
	Level-up transaction

Relationships
	Current relationship state
	Relationship changes
	Significant event references
	Relationship memory retrieval

Ledger/Memory
	Record event
	Record significant memory
	Query timeline
	Query memories

Checkpoint
	Create
	List continuation options
	Restore

GM Override
	Explicit exceptional state mutation
	Audit record
```

This list informs `MCP_PROTOCOL.md`; it does not automatically become one MCP tool per line.

---

# PART XXI — IMPORTANT BEHAVIORAL OBSERVATIONS

## 33. The AI Does Not Need to Call a Tool for Every Sentence

A conversation with Mara can proceed naturally for many exchanges if nothing canonical changes.

A tool becomes necessary when the AI needs:

- authoritative information
- deterministic resolution
- canonical mutation
- persistent memory
- deeper historical context

This keeps play conversational rather than turning every utterance into database traffic.

---

## 34. Canonicalization Boundary

A useful mental rule for the GM is:

> **If forgetting this fact next week would break the story, consider persisting it.**

Not every detail deserves a ledger event.

A bartender's joke probably does not.

A promise to return and rescue the bartender's daughter probably does.

---

## 35. Memory Has Layers

Normal runtime context:

```text
Elara:
	Richard's wife.
	Deep affection and trust.
	Scholar and adventurer.
	Relationship has survived several major crises.
```

On-demand episodic context:

```text
Proposal:
	Observatory roof after siege of Caelus...
```

Raw conversational transcript:

```text
Not required.
```

This is how the system can remain token-efficient without producing characters with amnesia.

---

## 36. The Director Does Not Control the Player

The Director can create:

- pressure
- opportunities
- consequences
- off-screen actions
- information
- unresolved intentions

It cannot require:

```text
Player goes to castle next.
Player falls in love with Elara.
Player accepts quest.
Mara forgives player.
```

Those outcomes depend on play.

---

## 37. The World Exists Beyond the Camera

The newspaper example is important.

The Director may commit an event long before the player encounters it.

The event can later manifest through:

- news
- rumors
- refugees
- changed prices
- military movement
- damaged locations
- NPC behavior

The player experiences consequences rather than a debug notification saying that the simulation advanced.

---

# PART XXII — ACCEPTANCE SCENARIOS DERIVED FROM THIS EXAMPLE

The implementation should eventually support automated or semi-automated tests corresponding to this walkthrough.

### Scenario A — Fresh campaign

Given an empty database, an AI can discover that no campaign exists and begin setup.

### Scenario B — Age/profile enforcement

Given a player age below a profile threshold, the server prevents selecting a higher campaign profile.

### Scenario C — Setup resumption

Given an interrupted Character Designer flow, a new AI can discover the current wizard state and continue it.

### Scenario D — Context reset

Given a committed campaign, gameplay can begin without access to setup conversation.

### Scenario E — Deterministic combat

Given fixed/random-seeded rules state, attack results and resulting HP are reproducible/auditable.

### Scenario F — Episodic relationship memory

Given a marriage relationship referencing a proposal event, a new AI session can retrieve the proposal when asked
without loading it during normal turns.

### Scenario G — Adaptive companion introduction

Given a planned companion introduction made impossible by player action, the Director can supersede the original plan
while preserving the intention.

### Scenario H — World event delivery

Given a committed off-screen event, the GM can discover and expose it through an appropriate diegetic channel.

### Scenario I — Checkpoint restoration

Given PC death in CHECKPOINT mode, restoring the checkpoint returns all canonical state to the checkpoint version.

### Scenario J — Provider replacement

Given the same persisted campaign, a different compatible AI agent can bootstrap and continue play without changing
canonical facts.

### Scenario K — Long hiatus

Given months without play and no prior transcript, session bootstrap plus memory retrieval is sufficient for convincing
continuation.

---

# PART XXIII — NEXT DOCUMENT

`MCP_PROTOCOL.md` takes this example and designs the smallest coherent MCP surface capable of executing it.

For every proposed tool, we should be able to answer:

1. Which step in `EXECUTION_MODEL.md` requires it?
2. Which concrete interaction in this document exercises it?
3. Why can the behavior not be handled safely by an existing tool?
4. Is the operation read-only, deterministic mutation, narrative mutation, or administrative override?
5. What invariants must the server enforce?
6. What should be returned so the AI can continue without unnecessary follow-up calls?

If a proposed MCP tool cannot be justified by an execution flow, it probably does not belong in the MVP.
