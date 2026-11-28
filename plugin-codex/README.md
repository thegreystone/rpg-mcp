# RPG MCP for Codex

This Codex plugin supplies the RPG MCP server, a setup workflow, the `rpg` skill and an icon. It starts a
local native executable through a dependency-free Node.js launcher; Node.js 18 or newer must be available
to the Codex process. No npm package or JVM is required.

Add the [AI Plugins marketplace](https://github.com/thegreystone/ai-plugins) to your Codex plugin sources,
refresh the directory, and install **RPG MCP**. Its Codex entry selects this `plugin-codex/` directory.
Claude Code uses the separate [`plugin/`](../plugin/) package. The plugin supplies its MCP registration;
keep a single active registration if you previously added the server manually with `codex mcp add`.

Run the plugin's **setup** skill once. It prefetches the native executable so the first start does not
exceed the MCP startup timeout, and lets you choose a campaign data directory. Then start a new chat and
ask "Is RPG MCP ready to play?"; a fresh install reports `CAMPAIGN_SELECTION` and no campaigns. From there,
"Start a new campaign using RPG MCP" begins the setup interview.

## Configuration

Nothing is required. Campaigns go to `~/.rpg-mcp` (`%USERPROFILE%\.rpg-mcp` on Windows): one SQLite file,
`rpg.db`, plus the server log. Back up that directory and your campaigns are safe. Use the actual installed
plugin path for the commands below:

```text
node "<plugin-root>/scripts/rpg-mcp.mjs" --configure --data-dir "/path/to/campaigns"
node "<plugin-root>/scripts/rpg-mcp.mjs" --install
node "<plugin-root>/scripts/rpg-mcp.mjs" --show-config
```

| Option | Environment | Meaning |
|--------|-------------|---------|
| `--data-dir` | `RPG_DATA_DIR` | Campaign data directory; `~/` is expanded |
| `--binary` | `RPG_MCP_BINARY` | Absolute path to an existing native executable; skips the release download |

Environment variables take precedence over saved settings. `RPG_MCP_CODEX_CONFIG` overrides the
configuration file path, and `RPG_MCP_CODEX_CACHE` overrides the binary cache directory. Configuration and
cached binaries are kept outside the installed plugin so an update does not discard them. `--show-config`
reports the paths and effective settings. Repeating `--configure` preserves settings it does not mention.

Default launcher locations are:

| Platform | Configuration file | Binary cache |
|----------|--------------------|--------------|
| Windows | `%LOCALAPPDATA%\rpg-mcp\codex\config.json` | `%LOCALAPPDATA%\rpg-mcp\cache` |
| macOS | `~/Library/Application Support/rpg-mcp/codex/config.json` | `~/Library/Caches/rpg-mcp` |
| Linux | `~/.config/rpg-mcp/codex/config.json` | `~/.cache/rpg-mcp` |

Linux respects `XDG_CONFIG_HOME` and `XDG_CACHE_HOME` when set. Windows falls back to `~/AppData/Local` if
`LOCALAPPDATA` is absent. Each downloaded release gets its own version directory in the cache. The download
must match GitHub's published asset size and SHA-256 digest when the release provides one. The launcher
records a SHA-256 hash with each cached executable and checks it again before use.

The launcher supports Linux x86_64 and ARM64, macOS Apple silicon, and Windows x86_64. It detects the
native host architecture, including Apple silicon under Rosetta. Unsupported platforms produce an explicit
error; use `--binary` or `RPG_MCP_BINARY` for a compatible executable you built yourself.

## Codex package format

The package uses Codex's [compatibility format](https://developers.openai.com/plugins/build/plugins):
`.codex-plugin/plugin.json` explicitly registers `./skills/` and `./.mcp.json`. Codex
0.166.0-alpha.5.1 applies a shared tool-schema budget to portable Agent Plugins, which hides part of the
RPG tool set. Compatibility plugins do not use that budget. Keep the root `plugin.json` absent: Codex
prefers it over the compatibility manifest. Keep the MCP configuration in `.mcp.json`, not `mcp.json`.
The local-executor flag and display metadata belong at the manifest root; the setup entry point remains
in `extensions.com.openai.onboardingSkill`.

If you previously added a standalone MCP registration to work around missing tools, remove that
registration and any override that disables the plugin's `rpg-mcp` server after updating the plugin.
Start a new chat with one active registration.

## Maintaining the release pin

After publishing and signing a native release, update `RELEASE_VERSION` in `scripts/rpg-mcp.mjs`.
Verify that the release has every supported native asset. The launcher installs exactly that version,
rather than following `latest`. Increment `version` in `.codex-plugin/plugin.json` whenever the Codex
package changes. A packaging-only release can advance the plugin version without changing the native
release pin: plugin 0.2.1 still uses native release 0.2.0. The Claude plugin's MCP Bundle URL
in [`plugin/.claude-plugin/plugin.json`](../plugin/.claude-plugin/plugin.json) is a separate pin and must
also be updated for a release intended for both clients.

The `rpg` skill is a short entry point; the Game Master guide itself comes from the server through
`get_instructions`, so the skill rarely needs to change when the protocol does. See
[Tool approvals (allow-listing)](../README.md#tool-approvals-allow-listing) for configuring auto-approval on
the routine gameplay tools while keeping confirmation prompts on `restore_checkpoint`, `complete_campaign` and
`apply_gm_override`.

To check the package and launcher without downloading a release or touching a campaign, run the Node
test suites from the repository root:

```text
node --check plugin-codex/scripts/rpg-mcp.mjs
node --test plugin-codex/tests
```
