# RPG MCP plugin

A persistent, rules-aware RPG engine (SRD 5.2.1) that turns Claude into a game master for campaigns that
last for years. The plugin pairs the `rpg` skill with the RPG MCP server, which runs on your machine and
keeps every campaign in one SQLite file in a data directory you choose. No account, no network access: the
rules content is embedded in the binary and imported on first run.

This directory holds the Claude Code plugin. It carries no binary. The manifest's `mcpServers` points at the
universal MCP Bundle attached to an RPG MCP release, and Claude Code downloads that bundle, about 35 MB,
into the plugin's cache at the first session start after installation. The bundle contains the macOS Apple
silicon, Windows x86_64 and Linux x86_64 binaries and picks the right one at launch. Linux aarch64 is not
covered: install that binary from the [releases page](https://github.com/thegreystone/rpg-mcp/releases) and
register it as described in the [main README](../README.md#add-it-to-claude-code). Claude Desktop does not
accept a plugin that references its bundle by URL; Desktop users install the per-platform `.mcpb` from the
release page directly.

Install in Claude Code from the [AI Plugins marketplace](https://github.com/thegreystone/ai-plugins):

```
/plugin marketplace add thegreystone/ai-plugins
/plugin install rpg-mcp@hirt-ai-plugins
```

Start a new session, then say `/rpg-mcp:rpg new` to set up a campaign or `/rpg-mcp:rpg continue` to pick
one up. Campaigns go to `~/.rpg-mcp` by default. To keep them elsewhere, open `/plugin`, choose **rpg-mcp**
on the **Installed** tab, choose **Configure**, set the campaign data directory, and start a new session.
That form is the only way to set it: the shell command `claude plugin configure` cannot reach a bundle
fetched from a URL. If you already run RPG MCP from a user-level `claude mcp add` entry, remove that entry
once the plugin works, or the two will serve the same campaigns side by side.

## Updating the bundled version

The bundle URL in `.claude-plugin/plugin.json` pins one release. After publishing a release and running
`mcpb/Sign-Release.ps1`, which re-packs the universal bundle around the signed Windows binary, change the
version in that URL and push. Marketplace users pick the change up with
`claude plugin update rpg-mcp@hirt-ai-plugins`.

The skill is a short entry point; the Game Master guide itself comes from the server through
`get_instructions` ([protocol/guide.md](../src/main/resources/protocol/guide.md)), so the skill rarely
needs to change when the protocol does.
