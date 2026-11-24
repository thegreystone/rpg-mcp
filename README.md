<h1 align="center">
  <img src="docs/assets/rpg-mcp-mark-dark.svg" alt="" width="128">
  <br>
  RPG MCP Server
</h1>

[![Build](https://github.com/thegreystone/rpg-mcp/actions/workflows/build.yml/badge.svg)](https://github.com/thegreystone/rpg-mcp/actions/workflows/build.yml)
[![Release](https://img.shields.io/github/v/release/thegreystone/rpg-mcp)](https://github.com/thegreystone/rpg-mcp/releases/latest)
[![Java 25+](https://img.shields.io/badge/Java-25%2B-blue)](https://adoptium.net/)
[![Quarkus](https://img.shields.io/badge/Quarkus-3.38-blueviolet)](https://quarkus.io/)
[![GraalVM Native](https://img.shields.io/badge/GraalVM-native--image-orange)](https://www.graalvm.org/)
[![License: BSD-3](https://img.shields.io/badge/License-BSD--3-green)](https://opensource.org/licenses/BSD-3-Clause)

This is quite frankly the most fun gaming experience I've had in a long time. This MCP server turns your AI
assistant into a fully fledged Dungeon Master, running immense campaigns for you, where everything and anything
is possible.

The MCP server provides a persistent, rules-aware RPG engine. An AI agent acts as game master, narrator and
world-builder; this server owns the truth — HP, money, XP, inventory, time, party, relationships,
events, checkpoints — in one local SQLite file, so a campaign can run for years across many AI
conversations without any chat transcript.

> **The AI improvises the fiction; the engine owns the truth.**

To start playing, [install RPG MCP](#1-install), then [start a campaign](#2-get-a-campaign-started).
Already have an adventure underway? [Continue your campaign](#coming-back-weeks-later).

---

## 1. Install

### The easy way: the MCP Bundle (Claude Desktop)

Get the `.mcpb` for your platform from the [Releases page](https://github.com/thegreystone/rpg-mcp/releases/latest),
go to *Settings → Extensions* in Claude Desktop, and drag the file into *"Drag .MCPB or .DXT files here to
install"*. Pick your campaign data directory when asked, then skip to
[Get a campaign started](#2-get-a-campaign-started).

### The easy way: the plugin marketplace (Claude Code and Codex)

In Claude Code:

```
/plugin marketplace add thegreystone/ai-plugins
/plugin install rpg-mcp@hirt-ai-plugins
```

Start a new session; Claude Code downloads the server bundle on that first start. Then `/rpg-mcp:rpg new`
begins the setup interview and `/rpg-mcp:rpg continue` picks a campaign up. Campaigns go to `~/.rpg-mcp`;
change that under `/plugin` → **Installed** → **rpg-mcp** → **Configure**. The bundle covers macOS Apple
silicon, Windows x64 and Linux x64. See [`plugin/README.md`](plugin/README.md).

In Codex, add the marketplace with `codex plugin marketplace add thegreystone/ai-plugins`, install **RPG
MCP** from the Plugins Directory, run its **setup** skill once (it prefetches the native executable; Node.js
18 or newer is required), and start a new chat. See [`plugin-codex/README.md`](plugin-codex/README.md).

Linux aarch64 users, and anyone who prefers a hand-registered binary, take the manual route that follows.

### Download a binary

Grab the file for your platform from the [Releases page](https://github.com/thegreystone/rpg-mcp/releases/latest).
The examples below use version `0.1.2` — substitute the version you downloaded.

| Platform            | File                                      |
|---------------------|-------------------------------------------|
| Linux x86_64        | `rpg-mcp-server-0.1.2-linux-x86_64`       |
| Linux aarch64       | `rpg-mcp-server-0.1.2-linux-aarch64`      |
| macOS Apple Silicon | `rpg-mcp-server-0.1.2-macos-aarch64`      |
| Windows x86_64      | `rpg-mcp-server-0.1.2-windows-x86_64.exe` |

These are native images — no Java required, and they start in milliseconds.

On Linux and macOS, make it executable:

```bash
chmod +x rpg-mcp-server-0.1.2-*
```

The macOS binary is signed and notarized, so Gatekeeper accepts it as downloaded. The one exception is a
first launch while offline, because Gatekeeper fetches the notarization ticket from Apple; if that
happens, clear the quarantine flag once:

```bash
xattr -d com.apple.quarantine rpg-mcp-server-0.1.2-macos-aarch64
```

> Prefer the JVM? Download `rpg-mcp-server-0.1.2-runner.jar` instead and run it with
> [Java 25+](https://adoptium.net/): the command becomes `java` and the arguments start with
> `-jar /path/to/rpg-mcp-server-0.1.2-runner.jar`. Everything else below is identical.

### Add it to Claude Code

One command, from anywhere:

```bash
claude mcp add rpg --scope user -- /path/to/rpg-mcp-server-0.1.2-linux-x86_64 -Dquarkus.mcp.server.stdio.enabled=true
```

`--scope user` makes the campaign available in every project — you do not want your campaign tied to one
directory. On Windows, use the full path to the `.exe`.

Verify with `/mcp` inside Claude Code: `rpg` should be listed as connected. If you would rather edit
config by hand, the equivalent entry in `~/.claude.json` is:

```json
{
  "mcpServers": {
    "rpg": {
      "command": "/path/to/rpg-mcp-server-0.1.2-linux-x86_64",
      "args": ["-Dquarkus.mcp.server.stdio.enabled=true"],
      "env": { "RPG_DATA_DIR": "/path/to/campaigns" }
    }
  }
}
```

`RPG_DATA_DIR` is optional; it defaults to `~/.rpg-mcp`.

### Add it to Codex CLI

Add the server to `~/.codex/config.toml`:

```toml
[mcp_servers.rpg]
command = "/path/to/rpg-mcp-server-0.1.2-linux-x86_64"
args = ["-Dquarkus.mcp.server.stdio.enabled=true"]

[mcp_servers.rpg.env]
RPG_DATA_DIR = "/path/to/campaigns"
```

Or let the CLI write it for you:

```bash
codex mcp add rpg -- /path/to/rpg-mcp-server-0.1.2-linux-x86_64 -Dquarkus.mcp.server.stdio.enabled=true
```

Then `codex mcp list` should show `rpg`. You are ready to [start a campaign](#2-get-a-campaign-started)
using the same prompt as in any other client.

### Add it to Claude Desktop

The easiest way is the MCP Bundle. Download the `.mcpb` file for macOS or Windows from the
[Releases page](https://github.com/thegreystone/rpg-mcp/releases/latest) (for example
`rpg-mcp-server-0.1.2-windows-x86_64.mcpb`), then either double-click it or open it from
*Settings → Extensions* in Claude Desktop. Campaigns go to `~/.rpg-mcp` by default; the extension's
settings page lets you pick another directory. No config file to edit. The bundle contains the same native
binary as the standalone download: signed and notarized on macOS, Authenticode-signed on Windows. The
bundle file itself carries no signature, so Claude Desktop shows its standard unsigned-extension notice
before installing.

If you prefer the manual route, edit `claude_desktop_config.json` — on Windows at
`C:\Users\<UserName>\AppData\Roaming\Claude\claude_desktop_config.json`, on macOS at
`~/Library/Application Support/Claude/claude_desktop_config.json` — and add:

```json
{
  "mcpServers": {
    "rpg": {
      "command": "C:\\Users\\YourName\\path\\to\\rpg-mcp-server-0.1.2-windows-x86_64.exe",
      "args": ["-Dquarkus.mcp.server.stdio.enabled=true"]
    }
  }
}
```

Restart Claude Desktop afterwards. Claude Desktop on Windows may launch the server with
`C:\WINDOWS\system32` as the working directory; the server detects that and moves its working directory
to the data directory itself, so nothing else is needed.

### Where your campaigns live

Canonical game state is in `rpg.db` under `~/.rpg-mcp` (`%USERPROFILE%\.rpg-mcp` on Windows);
imported map artwork is retained alongside it in campaign asset directories.
Set `RPG_DATA_DIR` or `-Drpg.data-dir=...` to move the data directory. Back up that directory and your campaigns are
safe. Rules content (SRD 5.2.1) is embedded in the binary and imported on first run — nothing else to
install, no network access, no account.

### Check it works

Ask your AI: **"Is RPG MCP ready to play?"**

It should check the connection and tell you whether you have saved campaigns or are ready to create your first.

---

## 2. Get a campaign started

Open a **new conversation** and say:

> **"Start a new campaign using RPG MCP"**

Your AI loads the Game Master instructions and begins setting up your campaign for you.

### The setup interview

Setup is an interview, not a form. The engine hands the AI an ordered list of decisions and it asks you
**one question per turn**, listing every option with what it means, plus a custom answer where one is
allowed. For nearly every creative question, **"surprise me"** is a valid answer.

Roughly in order, you will be asked about:

- **Content profile** — tone, themes, and what stays off the table. The AI asks your age early; only the
  derived content cap is stored, never the age.
- **Experience** — the flavour of fantasy. The default is a Baldur's Gate-style fantasy epic: a
  world-threatening plot, vivid companions with their own agendas, romance that develops naturally, hard
  choices with lasting consequences, and a world where almost anyone can die.
- **Rules** — which ruleset, how strictly to enforce it, whether GM overrides are allowed.
- **Continuation** — what happens when your character dies: checkpoints, a new character, or permadeath.
- **The adventure** — the world, its factions, the opening situation, the seeds the story grows from.
- **Your character** — species, class, background, ability scores (rolled or standard array), skills,
  equipment and spells, built as a draft you can revise.
- **The party** — who travels with you.

You can change your mind about anything until you approve the setup. Once approved, your campaign is saved
and ready to play.

### Playing

Say **"Let's play"**. Your AI picks up the saved campaign and narrates you into the opening scene. From
there it is a normal conversation: you describe what you do, the AI narrates, and RPG MCP tracks the rolls,
purchases, fights and level-ups.

- **You can always ask how you are doing.** *"How am I holding up? How's the party?"* works in every
  state, including mid-combat and after a death — HP, conditions, spell slots, money, inventory, all of it.
- **The dice are not negotiable.** The AI never picks a result. Rule-bending goes through an explicit,
  audited GM override, and you are told when one happens.
- **Checkpoints** can be made before dangerous scenes and restored later; everything after the checkpoint
  is rolled back in one transaction.
- **Maps remember the place.** Travel follows saved roads and geography; fights use local grids with party
  markers and incremental reveal. Ask to inspect a move, show a player map or add a note. Text-only clients
  use the same positions and rules. See the [DM map workflow](docs/MAP_USAGE.md) for authoring and custom textures.
- **End the session** by saying so. Your AI saves a summary for the next session's recap.

### Coming back, weeks later

Open a new conversation in any AI client connected to your saved campaign data and say:

> **"Please continue my campaign using RPG MCP"**

Your AI loads the Game Master instructions, finds your saved campaign and picks up where you left off.
If several campaigns are available, it asks which one you want. Chat transcripts are never required.

### Tips

* In Claude, don't hesitate to make it produce an artifact with the campaign state. It will gladly update it later on request.
* ChatGPT is very good at character portraits. Ask your AI to describe the character and simply ask for a portrait. You can ask Claude to include them in the artifact. 

---

## Troubleshooting

- **The tools do not appear** — make sure `-Dquarkus.mcp.server.stdio.enabled=true` is in the arguments,
  and for the jar that it comes *before* `-jar`. Then reconnect (`/mcp` in Claude Code, restart Claude
  Desktop, `codex mcp list` for Codex).
- **The readiness check fails** — ask the AI to call `get_instructions`, then `get_server_state`.
  Load the guide once per conversation; later state checks stay compact. A fresh install should report
  `CAMPAIGN_SELECTION` and no campaigns. If `get_instructions` is missing, upgrade to version 0.1.8
  or later and reconnect the client.
- **No log output** — normal logs go to `logs/rpg-mcp-server-<runtime-id>.log` under the selected data
  directory (`~/.rpg-mcp` by default). Each server instance has its own rotating log, so concurrent
  Windows instances do not contend over one file. `QUARKUS_LOG_FILE_PATH` explicitly overrides the
  path. Stdout remains reserved for MCP; startup/file-handler failures may still be reported on stderr.
  Default-log maintenance retains up to 32 prior inactive runtime groups within 32 MiB, including backups;
  the current runtime and active sessions are preserved. Explicit log paths are user-managed. Unmarked legacy logs on Unix
  are left alone because their active owner cannot be established safely.
- **The AI improvises mechanics instead of calling tools** — ask it to call `get_instructions` and follow
  the guide, using the RPG tools for rolls and canonical state. Every new conversation should load those
  instructions before starting or continuing a campaign.
- **The AI batches setup questions** — tell it to follow the guide and ask one decision per turn, listing
  every option. `get_setup_state` returns the ordered `decisions` list it should be walking.
- **Starting over** — move the data directory (`~/.rpg-mcp` by default) aside for a clean slate.
  Keep its database and campaign assets together if you might want the campaign back.

## Documentation

- [`docs/BACKLOG.md`](docs/BACKLOG.md) — planned improvements and their acceptance criteria.
- [`docs/MAP_IMPLEMENTATION_PLAN.md`](docs/MAP_IMPLEMENTATION_PLAN.md) — implemented map capabilities, service boundaries,
  deferred limits and final verification gates.
- [`docs/MAP_USAGE.md`](docs/MAP_USAGE.md) — dungeon blueprints, grid play, PNG/text views, custom textures,
  regional authoring, atlas references, living notes, patrols and interrupted journeys.
- [`docs/MAP_PLAYTEST_REPORT.md`](docs/MAP_PLAYTEST_REPORT.md) — map validation evidence and platform-specific results.
- [`docs/DUNGEON_MAPS.md`](docs/DUNGEON_MAPS.md) — dungeon design targets, incremental reveal, default materials
  and custom materials, tool-rendered images, custom geometry, multi-room encounters and party markers.
- [`docs/MAP_FORMAT.md`](docs/MAP_FORMAT.md) — shared map design and broader outdoor cartography targets,
  including city/regional profiles, travel distances, roadside stops, terrain and artwork alignment.
- [`docs/WORLD_MAP_GENERATION.md`](docs/WORLD_MAP_GENERATION.md) — LLM authoring guidance for
  creative, believable regions, varied starting locations, coasts, oceans, islands and atlas expansion.
- [`docs/DEVGUIDE.md`](docs/DEVGUIDE.md) — the implemented tool surface, the persistence architecture,
  building from source and cutting a release.
- [`docs/`](docs/) — the design documents: [`PROJECT_CONTEXT.md`](docs/PROJECT_CONTEXT.md),
  [`DESIGN.md`](docs/DESIGN.md), [`EXECUTION_MODEL.md`](docs/EXECUTION_MODEL.md),
  [`EXECUTION_EXAMPLE.md`](docs/EXECUTION_EXAMPLE.md), [`MCP_PROTOCOL.md`](docs/MCP_PROTOCOL.md),
  [`DOMAIN_MODEL.md`](docs/DOMAIN_MODEL.md), [`DATABASE.md`](docs/DATABASE.md) and
  [`RULES_ENGINE.md`](docs/RULES_ENGINE.md).

## License

BSD-3 (see [`LICENSE`](LICENSE)). Rules content derived from SRD 5.2.1 is CC-BY-4.0; see [`NOTICE`](NOTICE).
