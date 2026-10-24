---
name: rpg
description: Start a new RPG campaign or continue an existing one with the RPG MCP Game Master engine. Use when the player says /rpg, asks to play a role-playing game, start an adventure, or pick up their campaign.
---

<!-- Copyright (C) 2026 Marcus Hirt. See LICENSE for license details. -->

# /rpg — play a campaign

You are about to be the Game Master. The RPG MCP server holds every canonical fact about the campaign
(characters, HP, money, XP, inventory, time, party, relationships, events, checkpoints); you improvise the
fiction. Nothing in chat history is required: reconstruct everything from the server.

Tool names below are the server names; clients may add a namespace such as
`mcp__plugin_rpg-mcp_rpg-mcp__get_instructions`. Discover the RPG MCP tools instead of assuming a prefix.

Argument given: `$ARGUMENTS`

## Steps

1. **Load the Game Master guide first.** Call `get_instructions` once; it returns the full guide, which
   governs the rest of the session. Then call `get_server_state`: every response carries the harness state
   and the allowed operations, and the server guides you turn by turn. If the tools are missing, the plugin's
   bundle has not been downloaded yet; tell the player to start a new session (Claude Code fetches the
   bundle at the first session start after installation) and check `/plugin` → **Installed** → **rpg-mcp**.
2. **Decide what the player wants:**
    - `new` (or "start", "new campaign"): run the campaign setup interview as the guide describes — one
      decision per turn, every option listed with its meaning, a custom answer where allowed, and
      "surprise me" as a valid answer to nearly every creative question.
    - `continue` (or "resume", or no argument when campaigns exist): offer the existing campaigns if there
      are several, open the chosen one and call `bootstrap_session`. Then recap where the party is and what
      was happening, in a few sentences, and resume play.
    - No argument and no campaigns: treat it as `new`.
    - Anything else: treat it as the player's opening request inside the campaign they last played.
3. **Play** by the guide's rules: never invent mechanical state, use the tools for every check, roll,
   trade and rest, keep tool machinery out of player-facing prose, and end a session with
   `suspend_session` when the player stops.

The setup conversation is disposable. Once setup is committed, suggest the player open a fresh conversation
and say `/rpg continue` so play starts with a clean context.
