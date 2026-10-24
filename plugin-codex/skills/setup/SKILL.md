---
name: setup
description: Set up the RPG MCP plugin's local game engine after installing the Codex plugin, or change its campaign data directory or native binary.
---

<!-- Copyright (C) 2026 Marcus Hirt. See LICENSE for license details. -->

# Set up RPG MCP

This workflow configures the MCP server already supplied by this plugin. Keep the plugin's MCP registration;
do not create another entry with `codex mcp add` or edit global MCP configuration to register it twice.

RPG MCP needs no configuration to run: campaigns go to `~/.rpg-mcp` and the pinned native release is
downloaded on first start. Setup exists to prefetch that download, which can take longer than the MCP
startup timeout, and to choose another campaign data directory when the player wants one.

1. Locate the plugin root from this skill's installed path: two directories above this skill's directory.
   The launcher is `scripts/rpg-mcp.mjs` inside that root. Use its actual absolute path in shell commands;
   `${PLUGIN_ROOT}` in `mcp.json` is expanded by the plugin loader, not by the shell.
2. Check that `node` is available and version 18 or newer. The launcher uses only built-in Node modules;
   there is no npm package to install. If Node is absent, explain that it must be installed and made
   available to the Codex process, then continue setup after that prerequisite is met.
3. Run `node "<plugin-root>/scripts/rpg-mcp.mjs" --show-config` to see the effective data directory,
   configuration file and binary cache. Ask whether the player wants campaigns somewhere other than the
   default; a shared directory lets another MCP client (Claude Code, Claude Desktop) play the same
   campaigns. Do not move or pick an existing `rpg.db` without the player's instruction.
4. If the player chose a directory or has an existing native executable, save it:

   ```text
   node "<plugin-root>/scripts/rpg-mcp.mjs" --configure --data-dir "/path/to/campaigns"
   node "<plugin-root>/scripts/rpg-mcp.mjs" --configure --binary "/path/to/rpg-mcp-server"
   ```

   Configuration is stored outside the plugin installation so plugin updates preserve it. `RPG_DATA_DIR`
   in the environment overrides the saved directory and `RPG_MCP_BINARY` selects an executable the player
   installed themselves; `RPG_MCP_CODEX_CONFIG` and `RPG_MCP_CODEX_CACHE` select another configuration
   file or binary cache. Do not change those variables silently.
5. Run `node "<plugin-root>/scripts/rpg-mcp.mjs" --install` to download and verify the pinned native
   release, or check the configured existing binary. Do this before restarting: a first download can take
   longer than the MCP startup timeout. Report a download, checksum or unsupported-platform error clearly;
   `--install` prepares the binary without starting the server.
6. Tell the player to start a new Codex chat or restart the client so its plugin MCP server reconnects. In
   that conversation call the plugin's `get_instructions` tool, then `get_server_state`, and confirm the
   server answers with `CAMPAIGN_SELECTION` (and no campaigns on a fresh data directory). Tool names may
   include a client-assigned namespace: discover the RPG MCP tools instead of assuming an exact prefix. If
   the tools are still absent, check that the plugin is enabled, Node is on the Codex process's PATH, and
   `--install` completed successfully.

If a separately configured RPG MCP server already exists (for example from `codex mcp add rpg`), explain
that both registrations would expose the same tools. Keep one active registration. Do not remove an
existing registration until the player has chosen the plugin and its server state has been verified.
