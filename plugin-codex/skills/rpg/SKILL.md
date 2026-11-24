---
name: rpg
description: Start a new RPG campaign or continue an existing one with the RPG MCP Game Master engine. Use when the player asks to play a role-playing game, start an adventure, or pick up their campaign.
---

<!-- Copyright (C) 2026 Marcus Hirt. See LICENSE for license details. -->

# Play a campaign

You are about to be the Game Master. The RPG MCP server holds every canonical fact about the campaign
(characters, HP, money, XP, inventory, time, party, relationships, events, checkpoints); you improvise the
fiction. Nothing in chat history is required: reconstruct everything from the server.

Tool names below are the server names; Codex may add its own namespace. Discover this plugin's RPG MCP tools
instead of assuming an exact prefix. Domain tools take `(action, campaign, operation_id?, params?)` and appear
in `meta.allowed_operations` as `tool:ACTION`.

## Steps

1. **Load the core Game Master guide first.** Call `get_instructions` once (default `CORE`; load topic manuals
   `SETUP`, `COMBAT`, `MAPS`, `CHARACTERS`, `DIRECTOR`, or `ECONOMY` on demand when entering those workflows).
   Then call `session` with `action: "STATE"`: every response carries `harness_state` and `allowed_operations`,
   and the server guides you turn by turn. If the tools are missing, run the plugin's **setup** skill (it
   prefetches the native executable) and ask the player to start a new chat.
2. **Decide what the player wants:**
    - A new campaign ("start", "new campaign"): call `get_instructions(topic="SETUP")` and run the campaign
      setup interview via `session:CREATE`, `campaign_setup` and `character_draft` — one decision per turn,
      every option listed with its meaning, a custom answer where allowed, and "surprise me" as a valid answer
      to nearly every creative question.
    - Continue ("resume", "continue my campaign", or an unspecific request when campaigns exist): offer
      the existing campaigns if there are several, call `session:OPEN` and `session:BOOTSTRAP`. Then
      recap where the party is and what was happening, in a few sentences, and resume play.
    - No campaigns yet: treat it as a new campaign.
    - Anything else: treat it as the player's opening request inside the campaign they last played.
3. **Play** by the guide's rules: never invent mechanical state, use the tools (`roll_check`, `inventory`,
   `rest_time`, `encounter`, `narrative`, `chronicle`) for every check, roll, trade and rest, keep tool
   machinery out of player-facing prose, and end a session with `session:SUSPEND` when the player stops.
4. **Use the saved world.** Load `get_instructions(topic="MAPS")` before authoring or running spatial maps.
   Bootstrap returns atlas/preparation context. Retrieve saved maps with `map_play:CONTEXT`/`map_play:FIND`;
   author accepted dungeon blueprints with `map_preparation` before realizing them near approach. Before
   publishing a tactical map, take a final graphics and mood pass (fitting room materials, catalog props on
   `objects`/`decor`, and mood `lights` from `rpg://maps/catalog`; preview drafts with `render_map` on
   `preparation:N`, `audience: GM`). Every new combat uses a local grid and established figurine placement.
   Show only PLAYER views; GM authoring and patrol information stay private. Image display is optional:
   `render_map` also returns text. Resolve interrupted travel at its actual position (`world_travel`) and retain
   the unfinished journey. Regional timers pause when no party member remains; finite `patrols` do not respawn.
5. **Play the companions by default.** Choose their movement, actions and reactions from their own
   capabilities, perception and personalities. Broad orders such as "Mira, cover the stairs" do not
   hand every subsequent decision to the player. Use `character:TACTICAL_CONTROL` for an explicit "I control
   Mira this fight" (`PLAYER`, `ENCOUNTER`) or "DM, take over" (`DM`); omit `characters` to select all
   current companions, and use `UNTIL_RELEASED` for a lasting preference. Follow the saved controllers
   in party/bootstrap/encounter context. Pause at player-controlled decisions and meaningful shared
   choices; handle routine companion turns yourself. This does not change the primary player character
   or companions' narrative agency, and does not add automatic group movement.

The setup conversation is disposable. Once setup is committed, suggest the player open a fresh conversation
and ask to continue the campaign so play starts with a clean context.
