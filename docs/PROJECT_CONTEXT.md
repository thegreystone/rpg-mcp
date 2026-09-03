# RPG MCP — Project Context

This file is a compact handoff of design intent and conversational context that may not be obvious from the formal
design documents.

## Read First

Before implementing, read:

1. `DESIGN.md`
2. `EXECUTION_MODEL.md`
3. `EXECUTION_EXAMPLE.md`
4. This file for rationale, priorities, and design intent behind those documents.

The formal documents are authoritative where they define concrete behavior. This file explains what we are trying to
achieve and why.

## Core Vision

The goal is an AI-run RPG that feels like playing with a skilled human tabletop GM rather than interacting with a
conventional CRPG interface.

The AI should be free to narrate, improvise, portray characters, adapt plots, and react creatively. The MCP server
provides persistent memory, deterministic rules, exact state, and guardrails.

The guiding separation is:

> The AI improvises the fiction; the engine owns the truth.

The player should mostly interact through natural conversation. MCP mechanics should disappear behind the experience.

## Startup Should Feel Like Designing an Experience

Campaign startup should be an interactive, conversational configuration sequence—somewhat like choosing the parameters
of an experience in *Total Recall*.

The player can influence:

- the player character
- world and setting
- tone and themes
- desired kinds of story
- rules and difficulty
- death/continuation behavior
- companions and party composition
- relationship possibilities
- how much should be randomized or invented by the AI

The player should never be forced to design everything.

For essentially every creative configuration question, **“surprise me”** is a valid answer.

The setup process should therefore work well for both highly creative players who want detailed control and players who
simply want the AI to create an excellent campaign for them.

## Setup Is Disposable; Its Results Are Not

Campaign configuration may involve a long conversation. That conversation must not permanently consume gameplay context.

Once setup is approved:

1. Validate it.
2. Commit all important results to persistent state.
3. Create the initial adventure/world state.
4. Treat setup as complete.
5. Start gameplay with fresh context reconstructed from the committed campaign.

This context boundary is intentional and important.

## Character Creation

Character creation should be highly interactive but flexible.

Supported styles should include:

- guided creation
- fully/randomly rolled creation
- hybrid creation
- concept-first creation with AI assistance

For rolled attributes, `4d6 drop lowest` is an important supported option and intentionally gives somewhat above-average
heroic characters.

Players should also be able to use point-buy/standard-array approaches where the ruleset supports them.

Starting wealth is part of character creation. It should be rolled/resolved by the engine and may be affected by
background or social status—such as minor nobility—but **only through explicit rules or configured campaign modifiers**.
The AI must not fabricate extra money merely because it feels narratively appropriate.

The example player character ultimately becomes **Richard Greystone**.

Names are mutable character data, not identity.

## Companions Matter Enormously

Companions are not primarily combat resources.

A major motivation for this project is the observation that memorable RPGs and films are driven by relationships and
characters, not merely action.

Automatically generated companions should therefore have real personality:

- quirks
- goals
- opinions
- flaws
- humor
- fears
- loyalties
- secrets
- conflicts
- agency

Classic memorable RPG companions are useful inspiration: characters should have enough personality that players remember
them years later.

The AI should deliberately create the *possibility* of relationships such as:

- close friendship
- rivalry
- mentorship
- resentment
- loyalty
- betrayal
- romance

These are **possibilities, not predetermined outcomes**.

If the player wants to define companions explicitly, they can. If they do not, the AI should synthesize a compelling
cast.

## Companion Introductions Should Be Unpredictable

A configured companion does not necessarily start in the party.

The player may begin alone even after describing the kinds of companions they would like.

Potential companions can appear later.

An adventure plan may say that the player should eventually encounter a particular character, but the exact meeting
should not be rigidly scripted.

If player decisions make the planned meeting impossible, the GM/Director should find another plausible opportunity.

This unpredictability is desirable: starting a new campaign should not feel mechanically identical every time.

## Relationships Are Gameplay and Memory

Relationships are central to long-term engagement.

The compact current relationship state should be cheap to include in context, but important shared experiences must
remain retrievable.

Example:

A relationship can say that two characters are married and deeply affectionate while referencing ledger events for:

- first meeting
- first kiss
- proposal
- wedding
- betrayal
- reconciliation
- shared tragedy

If the player asks years later:

> Do you remember when I proposed?

the AI should be able to retrieve a summary of that specific event and answer convincingly in character.

The intended memory pattern is:

> **Compact working memory + references to persistent episodic memory.**

This pattern should be reused elsewhere when appropriate.

## Persistence and Memory Strategy

SQLite is required regardless of future semantic-memory technology because exact state must always be authoritative.

Examples include:

- HP
- XP
- money
- inventory
- weight
- spell resources
- conditions
- relationships
- encounter state
- quests
- world state

Semantic/vector search may eventually be valuable for questions such as:

> Have we ever met anyone who mentioned the black dragon?

But it should be added **late**, only when structured lookup and perhaps SQLite full-text search prove insufficient.

A future vector database is an index/search aid over memory. It is never the canonical source of mechanical truth.

## Entity Identity

Use simple numeric primary keys everywhere in SQL, scoped by table.

Examples:

```text
character.id = 1
location.id = 1
event.id = 1
```

Relationships use ordinary numeric foreign keys.

At the MCP/LLM-context boundary, render them as typed references:

```text
character:1
location:1
event:1
```

This is deliberately simple and human-debuggable.

The fundamental rule is:

> **Identity is stable; name, role, party membership, and life state are mutable.**

Thus `character:1` may begin as an NPC, become a companion, become player-controlled after another PC dies, leave the
party, or die without changing identity.

Do not encode mutable role in the ID.

## Player-Facing Presentation

Players should not normally see YAML, database IDs, tool traces, or MCP implementation details.

Technical examples in documentation may show:

```yaml
ref: character:1
```

but actual gameplay should say:

> Richard Greystone

The ideal experience is conversational and immersive.

## Death and Failure

Different continuation modes should be chosen during startup.

Important initial modes include:

- checkpoints
- encounter retry
- Ironman

In checkpoint modes, a disastrous decision can be rewound to a sensible checkpoint rather than destroying a long-running
campaign.

In Ironman mode, death remains canonical. If suitable companions survive, the player may take control of one of them and
continue playing that existing character.

If everyone is dead and no viable continuation exists, the campaign ends.

More generally:

> **Failure should create content whenever possible.**

A failed roll should often produce complications, suspicion, capture, pursuit, changed relationships, lost
opportunities, etc., rather than simply stopping the story.

## Narrative Director

The Narrative Director is distinct from the moment-to-moment Game Master.

It is a slower narrative-planning role concerned with:

- story arcs
- pacing
- foreshadowing
- unresolved seeds
- NPC agendas
- factions
- off-screen developments
- future opportunities
- adapting plans invalidated by player choices

It should **not run every turn or every MCP tick**.

Director evaluation should be event-driven and occur at meaningful boundaries such as:

- session start
- major quest completion
- significant time passage
- chapter transitions
- major relationship events
- important world changes
- plans becoming impossible
- pacing problems

A Director invocation may correctly decide that nothing needs changing.

The Director generally creates **seeds and pressures, not forced scenes**.

## Diegetic World Information

An especially appealing pattern is for off-screen Director events to reach the player naturally through the world.

For example, arriving in a village may reveal current events through:

- a newspaper boy
- a town crier
- tavern gossip
- refugees
- merchants
- soldiers
- letters
- rumors
- price changes
- environmental evidence

The player should experience a living world, not receive notifications that “the Narrative Director advanced faction
state.”

## NPC and World Autonomy

Important NPCs should have their own agendas, values, fears, secrets, and motivations.

The world should evolve without the player being present, but this should be **simulation-light**, not an attempt to
continuously simulate every inhabitant.

Meaningful off-screen evolution can happen at time advances, Director boundaries, session transitions, and major events.

## GM Flexibility

The design is informed by substantial experience running tabletop RPGs.

The system should preserve the flexibility of a human GM rather than over-formalizing everything.

Explicit GM overrides are therefore desirable, but they must be:

- deliberate
- visible to the engine
- auditable
- distinguishable from ordinary deterministic resolution

The GM can bend rules when campaign configuration permits it; the engine should never falsify a dice result to pretend
that happened naturally.

## Implementation Direction

Current intended stack:

- Java
- Quarkus
- GraalVM Native Image
- SQLite
- MCP
- stdio initially

Keep the first implementation simple and local.

Avoid unnecessary services and infrastructure.

## Documentation/Implementation Strategy

Project documentation should be written in English.

The intended progression is approximately:

```text
DESIGN.md
	↓
EXECUTION_MODEL.md
	↓
EXECUTION_EXAMPLE.md
	↓
MCP_PROTOCOL.md
	↓
domain/database/implementation specifications
	↓
implementation
```

`EXECUTION_EXAMPLE.md` is particularly important.

It should function simultaneously as:

- a player-experience example
- a protocol-design driver
- an implementation guide
- a source of acceptance/integration tests

When designing `MCP_PROTOCOL.md`, every proposed MCP operation should be justifiable by an actual execution flow.

Do not create tools simply because they map conveniently onto database CRUD.

Prefer semantic game operations.

## Overall Product Test

A strong test of the architecture is:

> Could a new AI model in a completely fresh conversation connect to the MCP server and convincingly continue a campaign
> that has been running for years, without access to the old chat transcripts?

It should know exact mechanical state, current narrative state, relationships, and important history—and be able to
retrieve deeper episodic memories only when they become relevant.

If that works while keeping routine context compact, the architecture has succeeded.
