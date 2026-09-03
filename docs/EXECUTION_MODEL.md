# RPG MCP Server — Execution Model

**Status:** Initial execution-flow design  
**Purpose:** Define how an AI Game Master uses the RPG MCP server from first contact with a player through campaign
creation, gameplay, persistence, resumption, and campaign termination.

This document complements `DESIGN.md`. `DESIGN.md` describes what the system is; this document describes how it is used.

---

## 1. Core Execution Principle

The AI Game Master owns conversation, interpretation, improvisation, pacing, and narration.

The MCP server owns canonical state and deterministic mechanics.

A useful rule is:

> **Conversation proposes; tools resolve and commit; narration explains.**

The AI should never depend on chat history as the only record of something that matters to the campaign.

---

## 2. Top-Level Lifecycle

A campaign moves through these major phases:

```text
NO_CAMPAIGN
	↓
CAMPAIGN_SETUP
	├─ content/player profile
	├─ campaign preferences
	├─ adventure design
	├─ rules/options
	├─ continuation policy
	├─ character design
	└─ party/companion design
	↓
SETUP_REVIEW
	↓
COMMIT
	↓
CONTEXT_BOUNDARY
	↓
READY_TO_PLAY
	↓
GAMEPLAY_SESSION
	↕
SUSPENDED
	↓
CAMPAIGN_COMPLETED / CAMPAIGN_FAILED / CAMPAIGN_ABANDONED
```

Setup state and runtime state are deliberately separated.

---

## 3. First Contact

When the MCP server is available, the AI should first determine whether the player wants to:

1. Continue an existing campaign.
2. Create a new campaign.
3. Inspect/manage campaigns.

The server should expose enough information to list campaigns using compact summaries without loading their full state.

For an existing campaign, the AI opens it and enters the resumption flow.

For a new campaign, it starts the Campaign Creation Wizard.

---

## 4. Campaign Creation Wizard

The Campaign Creation Wizard is an orchestrated workflow, not a single giant tool call.

The harness owns wizard state so that setup can itself be interrupted and resumed.

Every optional creative question should accept an equivalent of:

> **Surprise me.**

The AI can ask questions conversationally and need not expose the internal wizard structure.

### 4.1 Player and Content Profile

Establish the information required to determine the effective content profile.

The server stores the configured campaign profile and any applicable player constraints separately from AI-provider
restrictions.

The effective behavior is the intersection of:

```text
server policy
∩ campaign content profile
∩ player constraints
∩ AI provider policy
```

Provider restrictions must not alter the persisted definition of the campaign.

### 4.2 Campaign Experience Preferences

The AI asks only enough questions to establish how much authorship the player wants.

Possible questions concern:

- Tone.
- High/low fantasy.
- Exploration.
- Mystery.
- Political intrigue.
- Horror.
- Humor.
- Romance.
- Character drama.
- Epic vs. personal stakes.
- Preferred environments.
- Themes the player wants.
- Themes the player wants excluded.
- Degree of surprise.

The player can answer individual questions or delegate everything.

These preferences become design inputs, not rigid promises about scenes.

### 4.3 Rules Configuration

Select the ruleset and campaign options.

Examples:

- Ruleset/version.
- Ability-score generation.
- XP/progression policy.
- Encumbrance options.
- Rest variants if supported.
- GM override policy.
- Random-generation policy.
- Continuation/death mode.

Rules configuration is persisted and becomes immutable or explicitly versioned after campaign commit.

### 4.4 Continuation Policy

The wizard asks how failure and player-character death should work.

Initial choices:

- CHECKPOINT
- ENCOUNTER_RETRY
- IRONMAN

For IRONMAN, surviving companions may become playable after PC death.

The player should understand this choice before commit.

---

## 5. Character Designer Sub-flow

Character creation is a resumable sub-state-machine.

A suggested flow:

```text
CONCEPT
	↓
IDENTITY
	↓
CLASS / RULES IDENTITY
	↓
ABILITY GENERATION
	↓
BACKGROUND
	↓
PERSONALITY
	↓
APPEARANCE
	↓
ALIGNMENT
	↓
EQUIPMENT
	↓
SPELLS / FEATURES
	↓
REVIEW
	↓
CHARACTER COMMIT
```

The exact order may vary when dependencies require it.

### 5.1 Concept First

The AI can begin with:

> What kind of person do you want to play?

A player can answer mechanically ("a sorcerer") or narratively ("an arrogant young noble who discovered dangerous
magic").

The AI maps the concept onto legal rules choices.

### 5.2 Ability Generation

The server performs all random rolls.

For point-buy/standard-array modes, it validates assignments.

The AI explains consequences and helps optimize if requested, but the engine validates the final character.

### 5.3 Personality and Alignment

The player may choose directly or use an interview.

The AI can infer and propose alignment from answers, but the player can override it.

Personality information should be stored as compact canonical character information rather than preserving the creation
conversation.

### 5.4 Character Draft Commit

Before commit, the server validates the character.

Character creation reserves a stable `character:` identity immediately. During campaign setup, commit changes that
persisted row from `DRAFT` to `FINALIZED_DRAFT`; it does not yet make the character or staged mechanical state active in
gameplay. The character-design conversation can then be discarded because all important results are persisted.

The atomic campaign commit later promotes the same character identity to `ACTIVE` together with its starting sheet,
inventory, and money. Character creation outside initial campaign setup follows the same lifecycle through its
authorized runtime workflow.

---

## 6. Party and Companion Designer

The player chooses how much control they want over the initial cast.

Possible inputs include:

- Desired party roles/classes.
- Specific companion concepts.
- Existing relationships.
- Potential friendship.
- Potential rivalry.
- Potential romance.
- Personality preferences.
- Characters the player explicitly does not want.

The player can delegate all companion creation.

The AI should generate companions with:

- Individual goals.
- Values.
- Fears.
- Secrets.
- Flaws.
- Quirks.
- Conflicting interests.
- Independent opinions.
- Relationship potential.

Companions should not exist merely to agree with or serve the player.

### 6.1 Introduction Strategy

A configured companion may be:

- Already in the party.
- Introduced early.
- Introduced through a planned story opportunity.
- Introduced only if conditions arise.

The Narrative Director can maintain an unresolved intention such as:

```text
Introduce NPC X to the player.
```

It should not require one exact scene.

If the original meeting becomes impossible, the Director/GM can re-plan another plausible meeting.

---

## 7. Adventure Initialization

Once preferences and principal characters exist, the Narrative Director produces the initial adventure structure.

It creates persistent high-level material such as:

- Premise.
- Background truth.
- Factions.
- Major conflicts.
- Important NPCs.
- Initial locations.
- Long-term arcs.
- Story seeds.
- Mysteries.
- Companion introduction intentions.
- Potential future pressures.

The Director should not pre-write the entire campaign.

The objective is enough structure to create direction, foreshadowing, and continuity while preserving freedom.

---

## 8. Narrative Director

The Narrative Director is a **slow narrative control loop**, distinct from moment-to-moment GM behavior.

It is conceptually a role rather than necessarily a separate model.

It should be invoked when useful, not on every player turn.

### 8.1 Director Responsibilities

The Director considers:

- Long-term story arcs.
- Dramatic pacing.
- Unresolved story seeds.
- NPC agendas.
- Faction activity.
- World events.
- Foreshadowing.
- Companion introduction.
- Consequences of previous player decisions.
- Whether planned beats are still plausible.
- Whether the story has become repetitive or directionless.

### 8.2 Director Triggers

Possible triggers include:

- Beginning a new session.
- End of a chapter/major scene.
- Quest completion.
- Major relationship event.
- Significant travel/time passage.
- Level-up.
- Major faction/world change.
- A planned story beat becoming impossible.
- The GM explicitly detecting a pacing problem.
- A configurable amount of in-world time passing.

No trigger guarantees that something dramatic happens.

A Director invocation may legitimately conclude:

> No narrative intervention is currently needed.

### 8.3 Narrative Seeds

The Director should usually influence play by creating or updating **seeds**, pressures, and world facts rather than
scripting scenes.

Example:

```text
World event:
Rumors of a black dragon attacking northern caravans are spreading south.

Potential delivery channels:
- newspaper headline
- tavern rumor
- frightened refugees
- merchant price increases
- military patrol
```

The runtime GM chooses a natural way to expose this information—or may not expose it yet.

This allows the world to feel alive without making every Director decision a forced encounter.

---

## 9. Campaign Commit and Context Boundary

After setup, the AI presents a compact review.

The player confirms.

The server then atomically commits:

- Campaign configuration.
- Rules.
- Player character.
- Companion definitions/plans.
- Initial relationships.
- Adventure outline.
- Initial world.
- Content profile.
- Continuation policy.

After commit there is a deliberate **context boundary**.

The AI should stop relying on the campaign-creation conversation.

A new runtime context is built from canonical persisted state.

Conceptually:

```text
DESIGN CONVERSATION
	↓ commit
PERSISTENT CAMPAIGN
	↓ reconstruct
FRESH GAMEPLAY CONTEXT
```

This is essential for token conservation and reproducibility.

---

## 10. Beginning a Gameplay Session

When starting or resuming play:

1. Open campaign.
2. Ask server for a runtime/session bootstrap.
3. Retrieve current location and game time.
4. Retrieve current party.
5. Retrieve current player-character state.
6. Retrieve active quests/story pressures.
7. Retrieve relevant NPC/relationship summaries.
8. Retrieve recent important events.
9. Retrieve unresolved/relevant Director seeds.
10. Optionally run the Narrative Director if a trigger warrants it.
11. Build the initial scene context.
12. Narrate the opening/recap.
13. Wait for player input.

The bootstrap response should be compact enough for routine use.

---

## 11. Context Builder

The Context Builder is responsible for assembling the smallest sufficient context for the AI.

It does not replace canonical storage.

It selects relevant information.

Possible context components:

- Current scene/location.
- Party summaries.
- Character runtime state.
- Active encounter state.
- Relevant NPC summaries.
- Relationship summaries.
- Relevant relationship memories.
- Recent ledger events.
- Active quests.
- Applicable story beats.
- Director seeds.
- Local world facts.
- Relevant secrets/GM-only facts.
- Current time.
- Applicable rules references.

The Context Builder should favor compact summaries and retrieve deeper history only when needed.

---

## Entity References Across Persistence, MCP, and Context

Persistent entities use numeric table-local primary keys and ordinary numeric foreign keys.

The MCP/context representation may qualify an ID with its entity/table type:

```text
character:1
location:7
event:42
```

This typed form exists to make references unambiguous to the AI. The database itself still stores the corresponding
numeric IDs in the appropriate table/foreign-key columns.

The Context Builder is responsible for translating canonical records into compact LLM-facing representations. It should
preserve typed references when the AI may need to refer back to an entity through MCP, while presenting ordinary names
and prose to the player.

Roles are not identifiers. For example:

```yaml
character:
  ref: character:1
  name: Richard Greystone
  role: PLAYER
  life_state: ALIVE
```

If Richard dies and the player takes control of Mara, the references remain stable while role/life state change:

```yaml
characters:
  - ref: character:1
    name: Richard Greystone
    role: FORMER_PLAYER
    life_state: DEAD
  - ref: character:2
    name: Mara
    role: PLAYER
    life_state: ALIVE
```

Historical events, relationships, quests, and memories continue to reference the same stable entities regardless of
later name, role, party-membership, or life-state changes.

## 12. Memory Architecture

### 12.1 Canonical Memory

SQLite remains the canonical source of truth for structured and exact state.

Examples:

- HP.
- XP.
- Inventory.
- Money.
- Relationships.
- Quests.
- Locations.
- Events.
- Encounter state.

### 12.2 Episodic Memory

Important narrative experiences are represented by ledger events.

Relationship state should reference the events that explain important changes.

Example:

```text
relationship:
	player ↔ Elara
	status: married
	summary: deeply affectionate; strong mutual trust
	significant_events:
		- proposal_event_id
		- wedding_event_id
```

If the player asks:

> Do you remember when I proposed?

the Context Builder follows `proposal_event_id` and retrieves a compact summary of the proposal.

The proposal does not need to occupy the normal runtime prompt forever, but it remains retrievable.

### 12.3 Search Evolution

The MVP should use SQLite and explicit relationships/references first.

SQLite full-text search may be added where useful.

The architecture should permit a future semantic-memory provider/vector index without making it authoritative.

A vector store is an **index over memory**, not the source of mechanical truth.

Possible future abstraction:

```text
MemorySearchProvider
	├─ SqliteMemorySearch
	└─ VectorMemorySearch
```

Add semantic/vector search only when real campaign behavior demonstrates that structured lookup and full-text search are
insufficient.

---

## 13. The Normal Player Interaction Loop

A normal turn is not necessarily a combat round.

It is one conversational player action and the consequences that follow.

### Step 1 — Player speaks

Example:

> I ask Elara whether she still has the ring I gave her when I proposed.

### Step 2 — GM interprets intent

The AI determines that this requires:

- Current relationship state.
- Potentially episodic relationship memory.
- Possibly inventory knowledge.

### Step 3 — Retrieve required state

The GM asks MCP for only the relevant information.

The relationship response may identify the proposal as significant.

The GM retrieves the proposal summary if needed.

### Step 4 — Determine whether mechanics are required

If no deterministic mechanic is involved, the GM may continue narratively.

If a check, inventory mutation, time advance, encounter action, etc. is involved, the GM invokes the corresponding
engine operation.

### Step 5 — Resolve and commit

The engine:

- Validates.
- Rolls where required.
- Mutates canonical state.
- Produces structured outcomes.
- Records important state changes/events.

### Step 6 — Consider narrative consequences

The GM determines whether the action:

- Changes a relationship.
- Advances a quest.
- Reveals information.
- Creates a significant memory.
- Requires world-state changes.
- Creates a Director trigger.

If so, those changes are committed.

### Step 7 — Director, if warranted

The Director is invoked only when a trigger indicates that long-term narrative planning should be reconsidered.

### Step 8 — Narrate

The AI translates canonical results into natural storytelling and dialogue.

### Step 9 — Await next player action

The loop repeats.

---

## 14. Exploration Example

Player:

> We leave the road and head toward the ruined tower.

Possible flow:

1. GM identifies destination.
2. MCP checks whether the tower already exists/materialized.
3. If only a semantic location exists, the GM may request materialization.
4. Server persists generated stable details.
5. Travel time is resolved.
6. World clock advances.
7. Timed effects/world events are processed.
8. Director trigger may occur due to travel/time.
9. GM retrieves arrival context.
10. GM narrates the approach.

Anything invented that must remain stable is persisted once introduced.

---

## 15. Encounter Flow

### 15.1 Encounter Creation

The GM decides narratively that an encounter exists.

It supplies participants, sides, environment, objectives, and any hidden information needed by the engine.

The engine validates and starts the encounter.

### 15.2 Initiative

The engine determines initiative according to the rules.

The server owns:

- Round.
- Turn.
- Participants.
- Current HP.
- Conditions.
- Effects.
- Resources.

### 15.3 Player Turn

Player states intent naturally.

The GM maps it to a legal game action.

If clarification is genuinely required, the GM asks.

Otherwise it invokes the mechanical operation.

The engine returns authoritative results.

The GM narrates them.

### 15.4 NPC Turns

The GM chooses NPC tactics based on:

- Intelligence.
- Goals.
- Personality.
- Knowledge.
- Morale.
- Current encounter state.

The engine resolves mechanics identically.

### 15.5 End of Round

The engine:

- Advances round.
- Applies/updates timed effects.
- Checks encounter termination conditions.

A Director invocation is normally unnecessary per round.

### 15.6 Encounter End

The engine commits:

- Final HP/resources.
- Deaths.
- Loot where appropriate.
- Mechanically determined XP.
- Conditions.
- Time.

The GM/engine records significant narrative events.

Discretionary XP may be proposed and committed.

A major encounter may trigger the Director.

---

## 16. Failure as Content

Failure should usually create new state rather than simply halt the game.

Examples:

- Failed persuasion creates suspicion.
- Failed stealth starts a chase.
- Losing a fight may lead to capture.
- Missing a deadline changes faction power.
- A companion leaving creates a future reconciliation or rivalry possibility.

The engine determines mechanical failure.

The GM and Director turn that failure into continuing fiction.

Death remains governed by the configured continuation policy.

---

## 17. NPC Autonomy and World Simulation

Important NPCs and factions have agendas independent of the player.

The system does not need to simulate every inhabitant continuously.

Instead, relevant world evolution is evaluated at meaningful boundaries:

- Time advancement.
- Session start.
- Director invocation.
- Major player actions.
- Story milestones.

The Director can decide that an off-screen actor has acted.

That action becomes canonical only when committed to world state/ledger.

Consequences may surface naturally later.

Example:

```text
Director decision:
The northern duke mobilizes troops after repeated dragon attacks.

Committed world effects:
- faction readiness increased
- road patrols increased
- grain prices begin rising
- event logged

Possible later player observations:
- newspaper
- soldiers on road
- merchant dialogue
- rumors
```

---

## 18. Dramatic Pacing

Pacing is primarily a Director concern.

The Director should consider recent play composition:

- Combat.
- Exploration.
- Dialogue.
- Relationship development.
- Mystery/investigation.
- Downtime.
- Travel.
- Major reveals.

It should avoid simplistic fixed formulas.

The purpose is to detect patterns such as:

- Too many consecutive combats.
- Long periods without meaningful character interaction.
- Unresolved hooks accumulating indefinitely.
- Major arcs receiving no reinforcement.
- Constant high intensity with no contrast.

The Director responds with opportunities, not forced scenes.

---

## 19. Significant Events

Not every spoken sentence belongs in the ledger.

Events should be recorded when forgetting them would materially damage continuity.

Examples:

- Proposal.
- Wedding.
- Betrayal.
- Death.
- First meeting with an important NPC.
- Major promise.
- Relationship turning point.
- Discovery of a major secret.
- Major victory/defeat.
- Faction allegiance change.
- Character joins/leaves party.
- Level-up.
- Important item acquired/lost.
- Major story revelation.

Events should contain concise summaries plus enough metadata to retrieve them later.

Particularly important events can additionally have a richer episodic summary.

---

## 20. Session End

A player may stop at essentially any safe conversational boundary.

The AI should ensure that important consequences from the final interaction have been committed.

The server then:

1. Finalizes pending transactions.
2. Records session end.
3. Produces/updates a compact session summary.
4. Records current location/time.
5. Preserves unresolved encounter state if stopping mid-encounter is supported.
6. Updates any retrieval indexes.

Chat history is not required after this point.

---

## 21. Campaign Resumption

A later AI conversation should be able to resume without access to the previous chat.

Flow:

```text
list campaigns
	↓
select/open campaign
	↓
load compact campaign bootstrap
	↓
retrieve recent/significant context
	↓
retrieve current relationships and relevant memories
	↓
check Director trigger
	↓
build runtime context
	↓
give player a natural recap/opening
	↓
continue play
```

If the player asks about older history, the server retrieves it on demand.

---

## 22. Checkpoint Restoration

For CHECKPOINT or ENCOUNTER_RETRY:

1. Player dies/failure reaches retry condition.
2. Engine reports available continuation choices.
3. GM explains them.
4. Player chooses restoration.
5. Server restores canonical state.
6. Restoration itself is audit-logged outside/restoration-aware history as appropriate.
7. AI rebuilds context from restored state.
8. Play resumes.

The previous chat narration must not override restored canonical state.

---

## 23. Ironman Character Transfer

If a player character dies in IRONMAN mode:

1. Resolve death normally.
2. Determine surviving eligible companions.
3. Present candidates.
4. Player chooses a companion.
5. Server transfers player-control ownership.
6. Character's existing personality/history/relationships remain intact.
7. The former NPC becomes the new PC.
8. The story continues from the consequences of the death.

If no playable party member survives, the campaign can terminate.

---

## 24. Tool-Use Rules for the AI

The eventual MCP instructions should tell an AI Game Master:

1. Never invent canonical mechanical state.
2. Query state when uncertainty matters.
3. Use semantic game operations rather than attempting raw storage mutations.
4. Persist newly introduced facts that must remain stable.
5. Record significant narrative events.
6. Do not record trivial conversation as events.
7. Retrieve episodic memories when old details become relevant.
8. Respect visibility boundaries.
9. Use explicit GM override operations rather than pretending an override was a normal roll.
10. Invoke the Narrative Director at meaningful boundaries, not every turn.
11. Treat Director output as planning/pressure unless it has been committed as world truth.
12. After context reset/resumption, trust server state over remembered conversation.
13. Keep tool mechanics invisible in player-facing narration unless explanation is useful.

---

## 25. Proposed Harness States

An initial state model could be:

```text
CAMPAIGN_SELECTION

SETUP_CONTENT_PROFILE
SETUP_EXPERIENCE
SETUP_RULES
SETUP_CONTINUATION

CHARACTER_CONCEPT
CHARACTER_RULES
CHARACTER_PERSONALITY
CHARACTER_EQUIPMENT
CHARACTER_REVIEW

PARTY_DESIGN
ADVENTURE_INITIALIZATION
CAMPAIGN_REVIEW
CAMPAIGN_COMMIT
READY_TO_PLAY

SESSION_BOOTSTRAP
EXPLORATION
ENCOUNTER
LEVEL_UP
CHECKPOINT_DECISION
PLAYER_CHARACTER_TRANSFER
SESSION_SUSPEND

CAMPAIGN_COMPLETED
CAMPAIGN_FAILED
CAMPAIGN_ABANDONED
```

Not every narrative interaction needs to change harness state.

The harness state represents workflow constraints, not the story itself.

---

## 26. Important Distinction: Harness vs. Director vs. GM

These responsibilities must not blur.

### Harness

Answers:

> What workflow are we currently in, and what operations are valid?

It is deterministic application control.

### Narrative Director

Answers:

> Given everything that has happened, where might the larger story profitably go next?

It is slow narrative planning.

### Game Master

Answers:

> What happens right now, and how does the player experience it?

It is real-time interpretation and storytelling.

The same LLM may perform both GM and Director roles, but they are separate logical responsibilities and should receive
different context.

---

## 27. Director Interaction With MCP State

The Director should operate through explicit server calls.

Conceptually:

```text
DIRECTOR TRIGGER
	↓
server builds Director context
	↓
AI evaluates long-term narrative
	↓
AI proposes changes
	↓
server validates/commits accepted changes
	↓
new seeds/world events/story-beat states persist
```

The Director does not directly mutate arbitrary database rows.

Typical Director operations might eventually include:

- create/update story seed
- update story beat
- advance faction plan
- record world event
- schedule future pressure
- supersede impossible plan
- propose companion introduction opportunity

The exact tools belong in `MCP_PROTOCOL.md`.

---

## 28. Why the Director Is Not a Tick

A conventional simulation loop suggests updating every subsystem every turn.

That is undesirable here.

Most narrative state does not need reconsideration because the player opened a door or asked an NPC a mundane question.

Instead, use **event-driven narrative evaluation**.

This:

- Reduces token use.
- Reduces unnecessary AI calls.
- Avoids over-plotting.
- Allows quiet scenes to remain quiet.
- Makes major narrative decisions correspond to meaningful changes.
- Keeps the Director focused on long-term structure.

Time-driven triggers can still exist for world simulation, but they should occur at meaningful game-time boundaries
rather than every conversational exchange.

---

## 29. News, Rumors, and Diegetic Delivery

World events should normally reach the player through the fiction rather than system notifications.

Possible delivery mechanisms:

- Newspaper.
- Town crier.
- Newspaper seller.
- Tavern gossip.
- Refugees.
- Travelers.
- Merchants.
- Military patrols.
- Letters.
- Rumors.
- Environmental evidence.
- Price changes.
- Faction behavior.

The Director creates facts, pressures, and information availability.

The GM decides how and when the player encounters evidence of them.

This creates the impression of a world continuing beyond the player's immediate view.

---

## 30. Open Questions for MCP_PROTOCOL.md

The execution model exposes several questions that the tool design must answer:

1. Should campaign bootstrap be one aggregate MCP operation or several composable operations?
2. How does the server advertise the next legal wizard operations?
3. Should wizard state contain suggested questions/prompts for the AI?
4. How are pending multi-step transactions represented?
5. How does the AI ask for context at different scopes: scene, NPC, relationship, Director?
6. How are significant-event references represented compactly?
7. How does a Director trigger become visible to the AI?
8. Should the server automatically identify some Director triggers?
9. Which narrative mutations require explicit tools?
10. Which event types are generated automatically by mechanical operations?
11. How are GM-created entities validated before becoming canonical?
12. How does location materialization work transactionally?
13. How are checkpoints exposed without leaking implementation details?
14. How does provider/content policy information appear to the GM?
15. What is the smallest tool surface that still lets the AI run the complete lifecycle?

These should drive the next design artifact rather than being prematurely decided here.

---

## 31. Design Test

A useful acceptance test for the architecture is:

> Could a completely new AI conversation, given only access to the MCP server and its instructions, select an existing
> campaign and convincingly continue it without access to any previous chat transcript?

A stronger test is:

> Could it remember why an NPC loves, distrusts, fears, or resents the player—and retrieve the important shared
> experiences behind that relationship—without carrying those experiences in every prompt?

And the mechanical test is:

> Could the AI be replaced mid-campaign by another compatible AI provider without changing a single canonical game fact?

If all three answers are yes, the separation between narrative intelligence, memory, and game state is working.
