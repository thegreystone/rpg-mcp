# RPG MCP Server

[![Build](https://github.com/thegreystone/rpg-mcp/actions/workflows/build.yml/badge.svg)](https://github.com/thegreystone/rpg-mcp/actions/workflows/build.yml)
[![Release](https://img.shields.io/github/v/release/thegreystone/rpg-mcp)](https://github.com/thegreystone/rpg-mcp/releases/latest)
[![Java 21+](https://img.shields.io/badge/Java-21%2B-blue)](https://adoptium.net/)
[![Quarkus](https://img.shields.io/badge/Quarkus-3.38-blueviolet)](https://quarkus.io/)
[![GraalVM Native](https://img.shields.io/badge/GraalVM-native--image-orange)](https://www.graalvm.org/)
[![License: BSD-3](https://img.shields.io/badge/License-BSD--3-green)](https://opensource.org/licenses/BSD-3-Clause)

This is quite frankly the most fun gaming experience I've had in a long time. This MCP server transform your AI harness to 
into a fully fledged Dungeon Master, running fully fledged campaigns for you, where everything and anything is 
possible. 

The mcp server provides a persistent, rules-aware RPG engine. An AI agent acts as game master, narrator and
world-builder; this server owns the truth — HP, money, XP, inventory, time, party, relationships,
events, checkpoints — in one local SQLite file, so a campaign can run for years across many AI
conversations without any chat transcript.

> **The AI improvises the fiction; the engine owns the truth.**

There are just two steps to start playing: **install the binary**, then **tell your AI to read the guide and start a 
campaign**.

---

## 1. Install

### Download a binary

Grab the file for your platform from the [Releases page](https://github.com/thegreystone/rpg-mcp/releases/latest).
The examples below use version `0.1.2` — substitute the version you downloaded.

| Platform            | File                                      |
|---------------------|-------------------------------------------|
| Linux x86_64        | `rpg-mcp-server-0.1.2-linux-x86_64`       |
| Linux aarch64       | `rpg-mcp-server-0.1.2-linux-aarch64`      |
| macOS Apple Silicon | `rpg-mcp-server-0.1.2-macos-aarch64`      |
| Windows x86_64      | `rpg-mcp-server-0.1.2-windows-x86_64.exe` |

These are native images — no Java required, and they start in milliseconds, which matters because your
MCP client launches the server on every conversation.

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
> [Java 21+](https://adoptium.net/): the command becomes `java` and the arguments start with
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

Then `codex mcp list` should show `rpg`. If your Codex version surfaces MCP tools but not MCP *resources*,
it cannot read `rpg://protocol/guide` directly — start a campaign with *"Start a new RPG campaign — call
`get_server_state` first and follow the instructions it returns"* instead. Every response carries the
harness state and the allowed operations, so the model is guided turn by turn either way.

### Add it to Claude Desktop

The easiest way is the MCP Bundle. Download the `.mcpb` file for your platform from the
[Releases page](https://github.com/thegreystone/rpg-mcp/releases/latest) (for example
`rpg-mcp-server-0.1.2-windows-x86_64.mcpb`), then either double-click it or open it from
*Settings → Extensions* in Claude Desktop. The installer asks for the campaign data directory (default
`~/.rpg-mcp`) and takes care of the rest — no config file to edit. The bundle contains the same native
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

All state is a single SQLite file, `rpg.db`, under `~/.rpg-mcp` (`%USERPROFILE%\.rpg-mcp` on Windows).
Set `RPG_DATA_DIR` or `-Drpg.data-dir=...` to move it. Back up that directory and your campaigns are
safe. Rules content (SRD 5.2.1) is embedded in the binary and imported on first run — nothing else to
install, no network access, no account.

### Check it works

Ask your AI: *"Call `get_server_state` and tell me what it says."* You should get back a protocol
version, a harness state of `NO_CAMPAIGN` on a fresh install, and an empty campaign list.

---

## 2. Get a campaign started

Open a **new conversation** and say:

> **"Read rpg://protocol/guide, then start a new campaign for me."**

That one sentence is the entire bootstrap. The guide resource teaches the AI how to be the Game Master —
tool-use rules, the role split, the setup → play → suspend lifecycle — so you never have to explain any
of it. (In Codex, say *"Start a new RPG campaign, beginning with `get_server_state`"* instead.)

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

Nothing is canonical until the setup is committed, so you can change your mind about anything up to that
point. After the commit the setup conversation is disposable — the campaign lives in the database.

### Playing

Say **"Let's play"**. The AI calls `bootstrap_session`, gets the full state back and narrates you into
the opening scene. From there it is a normal conversation: you describe what you do, the AI narrates, and
it calls tools whenever a fact has to be authoritative — a die roll, a purchase, a fight, a level-up.

- **You can always ask how you are doing.** *"How am I holding up? How's the party?"* works in every
  state, including mid-combat and after a death — HP, conditions, spell slots, money, inventory, all of it.
- **The dice are not negotiable.** The AI never picks a result. Rule-bending goes through an explicit,
  audited GM override, and you are told when one happens.
- **Checkpoints** can be made before dangerous scenes and restored later; everything after the checkpoint
  is rolled back in one transaction.
- **End the session** by saying so. The AI calls `suspend_session` with a summary, which becomes the
  recap the next session opens with.

### Coming back, weeks later

Open any new conversation — different machine, different client, no chat history — and say:

> **"Read rpg://protocol/guide and continue my campaign."**

The engine reconstructs everything. Chat transcripts are never required.

---

## Troubleshooting

- **The tools do not appear** — make sure `-Dquarkus.mcp.server.stdio.enabled=true` is in the arguments,
  and for the jar that it comes *before* `-jar`. Then reconnect (`/mcp` in Claude Code, restart Claude
  Desktop, `codex mcp list` for Codex).
- **No log output** — logs go to `rpg-mcp-server.log` in the data directory (`~/.rpg-mcp` by default),
  never to stdout/stderr, so they cannot corrupt the STDIO transport. Check that file first.
- **The AI improvises mechanics instead of calling tools** — it did not read the guide. Say
  *"Read rpg://protocol/guide"* and start the session again; every session should begin with it.
- **The AI batches setup questions** — tell it to follow the guide and ask one decision per turn, listing
  every option. `get_setup_state` returns the ordered `decisions` list it should be walking.
- **Starting over** — `~/.rpg-mcp/rpg.db` holds everything. Move it aside for a clean slate (and keep it
  if you might want the campaign back).

## Documentation

- [`docs/DEVGUIDE.md`](docs/DEVGUIDE.md) — the implemented tool surface, the persistence architecture,
  building from source and cutting a release.
- [`docs/`](docs/) — the design documents: [`PROJECT_CONTEXT.md`](docs/PROJECT_CONTEXT.md),
  [`DESIGN.md`](docs/DESIGN.md), [`EXECUTION_MODEL.md`](docs/EXECUTION_MODEL.md),
  [`EXECUTION_EXAMPLE.md`](docs/EXECUTION_EXAMPLE.md), [`MCP_PROTOCOL.md`](docs/MCP_PROTOCOL.md),
  [`DOMAIN_MODEL.md`](docs/DOMAIN_MODEL.md), [`DATABASE.md`](docs/DATABASE.md) and
  [`RULES_ENGINE.md`](docs/RULES_ENGINE.md).

## License

BSD-3 (see [`LICENSE`](LICENSE)). Rules content derived from SRD 5.2.1 is CC-BY-4.0; see [`NOTICE`](NOTICE).
