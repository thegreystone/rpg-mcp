# Developer Guide

Everything beyond installing and playing: the tool surface, the persistence architecture, building from
source, and cutting a release. Install and first-campaign instructions are in the [README](../README.md).

## Status — vertical slice

The first implementation milestone ([`MCP_PROTOCOL.md`](MCP_PROTOCOL.md) §28: discovery, resumable campaign
setup, commit, session bootstrap, one deterministic check, persistence, suspension, fresh-context resumption)
plus the change journal, checkpoints and the semantic ledger, which define the persistence architecture.

## Tool surface (protocol 0.2.0 — 22 domain tools)

All 112 semantic engine operations are consolidated into **22 MCP tools** (3 standalone + 19 action-dispatched domain
tools taking `(action, campaign, operation_id?, params?)`) so the server stays well under multi-MCP host tool limits:

| Tool | Actions / Role |
|---|---|
| `get_instructions` | Standalone read-only GM guide loader (`topic`: `CORE` default, `SETUP`, `COMBAT`, `MAPS`, `CHARACTERS`, `DIRECTOR`, `ECONOMY`, `ALL`) |
| `session` | `STATE`, `CREATE`, `OPEN`, `BOOTSTRAP`, `SUSPEND`, `COMPLETE` |
| `campaign_setup` | `GET`, `UPDATE`, `VALIDATE`, `COMMIT`, `PARTY_DESIGN`, `HOUSE_RULES` |
| `character_draft` | `CREATE`, `CHOICES`, `GENERATE_ABILITIES`, `UPDATE`, `VALIDATE`, `COMMIT` |
| `level_up` | `BEGIN`, `CHOICES`, `UPDATE`, `VALIDATE`, `COMMIT`, `ABANDON` |
| `character` | `SHEET`, `PARTY`, `UPDATE`, `MATERIALIZE`, `RUNTIME_CHANGE`, `AWARD_XP`, `MEMBERSHIP`, `TACTICAL_CONTROL`, `TRANSFER_CONTROL` |
| `roll_check` | `CHECK` (ability/skill/save checks with tool proficiency & advantage), `DICE` (free journaled rolls) |
| `encounter` | `START`, `STATE`, `ACTION`, `RESOLVE_CHOICE`, `END`, `ANNOTATE`, `ADD_PARTICIPANTS`, `RECOVER_AMMO`, `CONVERT_TO_GRID` |
| `spellcasting` | `PREPARE`, `CAST` (180-spell SRD 5.2.1 seed, rituals, concentration, metamagic) |
| `rest_time` | `REST` (`SHORT`/`LONG`), `ADVANCE`, `TIMED_ACTION` (`SEARCH`/`WAIT`/`WORK`) |
| `inventory` | `TRANSFER_ITEM`, `EQUIP`, `GIVE_MONEY`, `TRADE`, `GRANT_LOOT` (SRD equipment + 260 magic items, attunement, level-appropriate `treasure` suggestions) |
| `economy` | `SET_CALENDAR`, `CREATE_ACCOUNT`, `TRANSFER_MONEY`, `GET_ACCOUNTS`, `DEFINE_CASH_FLOW`, `UPDATE_CASH_FLOW`, `LIST_CASH_FLOWS` |
| `world_travel` | `MATERIALIZE_LOCATION`, `MOVE_PARTY`, `TRAVEL_STATE`, `CONTINUE_JOURNEY`, `RESOLVE_OPPORTUNITY`, `DEFINE_TRAVEL_RISK`, `SCHEDULE_EVENT` |
| `patrols` | `DEFINE`, `UPDATE`, `STATE` (finite authored regional patrols) |
| `narrative` | `CONTEXT`, `UPSERT`, `DIEGETIC`, `DIRECTOR_CONTEXT`, `COMMIT_DIRECTOR`, `GET_RELATIONSHIP`, `UPDATE_RELATIONSHIP` |
| `chronicle` | `RECORD_MEMORY`, `QUERY_MEMORIES`, `QUERY_TIMELINE`, `FIND`, `GET_MATERIAL`, `WRITE` |
| `checkpoints` | `CREATE`, `OPTIONS`, `RESTORE` (full transactional rewind) |
| `rules` | `SEARCH` (SRD Rules Glossary & content search), `DEFINITIONS`, `DEFINE` (custom `ITEM`/`BACKGROUND`) |
| `map_preparation` | `BEGIN`, `REVISE`, `VALIDATE`, `PUBLISH`, `IMPORT_ASSET`, `EXPORT_BUNDLE`, `IMPORT_BUNDLE` |
| `map_play` | `CONTEXT`, `FIND`, `PLACE_ACTORS`, `MOVE_ACTOR`, `OBSERVE`, `PREVIEW_ACTION`, `USE_TRANSITION`, `UPDATE_OBJECT`, `CORRECT_GEOMETRY`, `PERCEPTION`, `EMIT_SOUND`, `ANNOTATE` |
| `render_map` | Standalone read-only PNG + text + marker renderer for `map:N` or GM draft `preparation:N` |
| `apply_gm_override` | Standalone audited override boundary (`destructiveHint = true`) |
| **Resources** | `rpg://protocol/guide`, `rpg://protocol/guide/full`, `rpg://maps/catalog`, `rpg://protocol/capabilities`, `rpg://rulesets` |

Each new conversation starts with `get_instructions` (default topic `CORE`), which loads the lean Game Master guide
and topic index into context, followed by `session:STATE`, which returns compact state and `allowed_operations`
(formatted as `tool:ACTION`). Specialized manuals (`SETUP`, `COMBAT`, `MAPS`, `CHARACTERS`, `DIRECTOR`, `ECONOMY`)
are pulled on demand. Startup requires no MCP resource reader or client-specific prompt.

Not yet implemented (`CAPABILITY_UNAVAILABLE`, or `false` in `rpg://protocol/capabilities`): Counterspell, Ready
and other reactions; travel and rest interruptions (travel encounters are suggested, never forced); subclasses
and most class features (Sneak Attack, Font of Magic, Metamagic and Sorcerous Restoration are data-driven, the
rest are GM-adjudicated from the sheet); creature spellcasting; area-of-effect geometry (the GM names the
creatures in the area). The design documents describe the target surface.

## Architecture in one paragraph

Every tool invocation is one SQLite transaction. All writes to campaign-owned tables go through a journaling
data-access layer (`persistence/Tx`) that stores a full before-image of every mutated row in
`journal_entry.undo_json`. A checkpoint is a marker in that journal; `restore_checkpoint` applies the inverse
of every later entry in reverse order, in one transaction, leaving audit records that survive the rollback.
Client-supplied `operation_id`s make every mutation idempotent. Rules content (SRD 5.2.1, CC-BY-4.0) ships as
embedded JSON seed files imported on first run; each file's `verify` field records what was checked against
the SRD PDF and what remains paraphrased.

## Seed tooling

[`tools/`](../tools) is a standalone Maven project (Java 25, PDFBox, Jackson), deliberately not a module of the
server pom so the release workflow never sees it. Its entry point `SrdTool` has five commands. The SRD 5.2.1
PDF (CC-BY-4.0) downloads from `https://media.dndbeyond.com/compendium-images/srd/5.2/SRD_CC_v5.2.1.pdf`.

```bash
# Extract the PDF to srd.txt next to it (pages delimited by "=== PAGE n ===" markers).
mvn -q -f tools/pom.xml compile exec:java -Dexec.args="extract path/to/SRD_CC_v5.2.1.pdf"

# Diff every seed file against the text; writes srd_report.txt in the current directory.
# Passing the PDF instead of srd.txt extracts first.
mvn -q -f tools/pom.xml compile exec:java -Dexec.args="verify path/to/srd.txt"

# Regenerate seed/srd5e/rules.json (the Rules Glossary, lifted verbatim) after an SRD revision.
mvn -q -f tools/pom.xml compile exec:java -Dexec.args="build-rules path/to/srd.txt"

# Regenerate seed/srd5e/creatures.json (the stat blocks) and seed/srd5e/magic-items.json (Magic Items A-Z, verbatim,
# with the engine-applied mechanics overlay kept in BuildMagicItems).
mvn -q -f tools/pom.xml compile exec:java -Dexec.args="build-creatures path/to/srd.txt"
mvn -q -f tools/pom.xml compile exec:java -Dexec.args="build-magic-items path/to/srd.txt"
```

Run them from the repository root; the seed directory is resolved relative to the working directory. In
`srd_report.txt`, categories without a suffix (`weapons`, `creatures`, `spells`, ...) are hard discrepancies;
`*-info`, `*-text`, `*-upcast`, `*-table`, `*-equipment` and `*-traits` are context for manual review. Verify
after every seed edit; the SRD is the reference, not recollection.

## Design documents

Read in this order:

1. [`PROJECT_CONTEXT.md`](PROJECT_CONTEXT.md) — what this is and why.
2. [`DESIGN.md`](DESIGN.md) — the architecture in full.
3. [`EXECUTION_MODEL.md`](EXECUTION_MODEL.md) — the harness, the roles, the lifecycle.
4. [`EXECUTION_EXAMPLE.md`](EXECUTION_EXAMPLE.md) — a worked session, end to end.
5. [`MCP_PROTOCOL.md`](MCP_PROTOCOL.md) — every tool, argument and error code.
6. [`DOMAIN_MODEL.md`](DOMAIN_MODEL.md) — the entities and their relationships.
7. [`DATABASE.md`](DATABASE.md) — the schema, the journal and the ledger.
8. [`RULES_ENGINE.md`](RULES_ENGINE.md) — how rules content is modelled and resolved.

## Building from source

**Prerequisites:** JDK 25+ and Maven 3.9+. For the native image, [GraalVM 25](https://www.graalvm.org/downloads/)
with `native-image`; on Windows also Visual Studio 2022 with the "Desktop development with C++" workload.

```bash
mvn package                      # uber-jar: target/rpg-mcp-server-<version>-runner.jar
mvn package -Dnative -DskipTests # native executable: target/rpg-mcp-server-<version>-runner[.exe]
mvn test-compile failsafe:integration-test failsafe:verify -Dnative.image.path=target/rpg-mcp-server-<version>-runner.exe
```

The last command runs `NativeImageSanityIT`, which launches the binary over STDIO and drives a real MCP
handshake, then verifies the results so a test failure stops the build. It is the only check that catches
native-image reflection and resource regressions.

To point an MCP client at a development build, use the release configuration with the path under `target/`,
and set `RPG_DATA_DIR` to a scratch directory so you do not play against your real campaign database.

### Local Codex playtests

The opt-in [local Codex playtests](CODEX_PLAYTESTS.md) run fresh model sessions against disposable campaigns,
capture public MCP calls, and compare independently checked outcomes across revisions. They use your local
Codex sign-in and are separate from Maven tests and GitHub Actions. Start with
`node tools/playtest/run.mjs --list` or validate fixtures without a model using
`node tools/playtest/run.mjs --prepare-only --ref master --ref working`.

### Native image size budget

The universal bundle (see *Cutting a release*) carries three native binaries and must stay under 50 MB, so each
binary gets roughly 12 MB compressed (about 30 MB on disk). The image stays that small by leaving out
everything network-shaped, which this server never uses:

- `application.properties` turns off the `http` URL handler.
- It excludes the sqlite-jdbc `native-image.properties` (its `--enable-url-protocols=jar` pulls in the jar
  verifier, X.509, PKCS#7 and every JCA provider) and registers the driver's feature by hand.
- `se.hirt.mcp.rpg.graal` substitutes the JBoss LogManager syslog/socket handlers' SSL socket factories so
  that JSSE is unreachable.

When the binary grows, GraalVM's build report shows where:

```bash
mvn package -Dnative -DskipTests -Dquarkus.native.additional-build-args=-Os,--emit=build-report
```

`target/*-native-image-source-jar/*-build-report.html` breaks code and heap down by package;
`-H:PrintAnalysisCallTreeType=CSV` additionally dumps the call graph.

## Code formatting

[Spotless](https://github.com/diffplug/spotless) enforces the Eclipse formatter profile in
`config/formatter/rpg-mcp-formatting.xml` (tabs, 120 columns). The `check` goal runs in the `validate` phase of
both the server pom and `tools/pom.xml`, so a build fails on unformatted code. To fix:

```bash
mvn spotless:apply                  # server
mvn -f tools/pom.xml spotless:apply # seed tooling
```

IntelliJ: Settings > Editor > Code Style > Java > Import Scheme > Eclipse XML Profile. Eclipse: Preferences >
Java > Code Style > Formatter > Import.

## Cutting a release

Push a `v`-prefixed tag:

```bash
git tag v0.1.1 && git push origin v0.1.1
```

[`.github/workflows/release.yml`](../.github/workflows/release.yml) sets the Maven version from the tag
(`versions:set -DnewVersion=${TAG#v}`), builds the uber-jar and native images for linux-x86_64, linux-aarch64,
macos-aarch64 and windows-x86_64, runs the native sanity test on each, and publishes everything to a GitHub
release with generated notes. `pom.xml` stays at `-SNAPSHOT` on the branch; the tag is the single source of
truth for a release version.

**Per-platform bundles.** The macOS and Windows jobs also pack an [MCP Bundle](https://github.com/anthropics/mcpb)
(`.mcpb`), the one-click install for Claude Desktop; Linux users take the bare binary. A bundle is the binary
under `server/` plus a `manifest.json` from [`mcpb/manifest.json`](../mcpb/manifest.json) (`__VERSION__`,
`__BINARY__`, `__PLATFORM__` substituted), packed by [`mcpb/pack.sh`](../mcpb/pack.sh) on the runner that
built the binary so the macOS executable bit survives. The manifest declares the data directory as a
`user_config` folder picker mapped to `RPG_DATA_DIR`. The manual **Bundles** workflow
([`.github/workflows/bundle.yml`](../.github/workflows/bundle.yml)) re-packs bundles for a published release:
run it from the Actions tab with the version, *macos-only* once the signing script has produced the Windows
bundle.

**Universal bundle.** The release job also packs `rpg-mcp-server-<version>-universal.mcpb`: the macOS, Windows
and Linux x86_64 binaries with a `platform_overrides` manifest
([`mcpb/manifest-universal.json`](../mcpb/manifest-universal.json)) and, on Linux,
[`mcpb/linux-launcher.sh`](../mcpb/linux-launcher.sh), which runs the x86_64 build and sends other
architectures to the bare binary. Linux aarch64 is left out to keep it under 50 MB. It exists only because a
marketplace entry can reference one bundle for every platform; direct downloads use the per-platform bundles.
[`mcpb/pack-universal.sh`](../mcpb/pack-universal.sh) packs it on Linux only (Windows cannot set Unix
executable bits); the Bundles workflow's *universal* choice re-packs it, and the Windows signing script
dispatches that automatically.

**Marketplace distribution.** Use [`ai-plugins`](https://github.com/thegreystone/ai-plugins) for Claude Code
and Codex, following Mnemic's separate packages. Keep plugin source, skills and icons alongside the server
code; publish native executables and `.mcpb` archives as GitHub Release assets. Do not commit executables or
bundle archives to either repository.

The packages follow Mnemic's layout and both `ai-plugins` catalogs point at them with `git-subdir` sources on
`master`:

- [`plugin/`](../plugin/): the Claude Code plugin. Its manifest's `mcpServers` is the URL of the release's
  universal bundle (so Linux x86_64 is covered, unlike Mnemic), plus the `rpg` skill and the icon. No
  `version` field: Claude Code versions a `git-subdir` plugin by commit. Claude Desktop rejects URL-backed
  bundles, so Desktop keeps the per-platform `.mcpb`.
- [`plugin-codex/`](../plugin-codex/): the Codex plugin. A `.codex-plugin/plugin.json` compatibility manifest,
  `.mcp.json`, the `rpg` and `setup` skills, and
  [`scripts/rpg-mcp.mjs`](../plugin-codex/scripts/rpg-mcp.mjs), a dependency-free Node.js
  18+ launcher that downloads the pinned standalone binary for the host platform into a cache outside the
  plugin, verifies GitHub's size and SHA-256 digest, and starts it with `RPG_DATA_DIR` set. Optional
  `--configure --data-dir` and `--binary` settings live outside the plugin too.

Keep the Codex package in compatibility format: Codex 0.166.0-alpha.5.1 limits the combined tool-schema
size of portable Agent Plugins and silently hides part of RPG MCP's tools. A root `plugin.json` would
take precedence and restore that limit. The compatibility manifest explicitly registers the skills and
MCP configuration. See [`plugin-codex/README.md`](../plugin-codex/README.md) for the layout and migration
from a standalone MCP workaround. The `ai-plugins` catalog already selects `plugin-codex/`; its source
entry does not change.

**Updating the pins** after a release has been published *and* `Sign-Release.ps1` has re-packed the universal
bundle: change the version in the bundle URL in `plugin/.claude-plugin/plugin.json`, and `RELEASE_VERSION`
in `plugin-codex/scripts/rpg-mcp.mjs`. Increment `version` in `plugin-codex/.codex-plugin/plugin.json` for
each Codex package change; packaging-only releases can keep the existing native release pin (plugin
0.2.1 uses native 0.2.0). Run `claude plugin validate ./plugin` and
`node --test plugin-codex/tests`, push, and users pick
the change up with `claude plugin update rpg-mcp@hirt-ai-plugins` or a Codex marketplace refresh. Both skills
only point the model at `get_instructions`; the guide itself stays in the server, so a protocol change rarely
touches them. Keep `plugin/assets/icon.png` and `plugin-codex/assets/icon.png` in step with
[`docs/assets/rpg-mcp-icon.png`](assets/rpg-mcp-icon.png).

**Claude Desktop installation.** The README sends Desktop users to the per-platform `.mcpb` on the release,
installed directly in Claude Desktop, where the bundle's `user_config` form lets them choose a campaign data
directory. Keep this direct bundle installation path separate from the Claude Code and Codex marketplace
packages.

To try a bundle locally (Git Bash on Windows):

```bash
bash mcpb/pack.sh 0.0.0 windows-x86_64 win32 target/rpg-mcp-server-*-runner.exe target/rpg-mcp-server-dev.mcpb
```

### Signing the macOS binary

The macOS job signs the binary with the Apple Developer ID certificate (hardened runtime, timestamped) and
notarizes it before packing the bundle, as in [thegreystone/diskspace](https://github.com/thegreystone/diskspace).
A bare executable cannot be stapled, so Gatekeeper fetches the ticket on first run; the README keeps the
`xattr` note for offline first runs. The job fails, and no release is created, without these secrets:

| Secret                    | Value                                                            |
|---------------------------|------------------------------------------------------------------|
| `MACOS_CERTIFICATE`       | Developer ID Application certificate + key as a base64-encoded `.p12` |
| `MACOS_CERTIFICATE_PWD`   | Password of that `.p12`                                          |
| `MACOS_SIGNING_IDENTITY`  | `Developer ID Application: <name> (<team id>)`                   |
| `NOTARIZATION_APPLE_ID`   | Apple ID used for notarization                                   |
| `NOTARIZATION_PASSWORD`   | App-specific password for that Apple ID                          |
| `NOTARIZATION_TEAM_ID`    | The 10-character team ID                                         |

There is no `.app` or DMG. No entitlements are needed (a native image does not JIT and loads no unsigned
dylibs); if a signed build dies at launch with a code-signing error, check that first.

### Signing the Windows binary

The Windows binary is Authenticode-signed with a Certum code-signing certificate through SimplySign Desktop.
SimplySign needs an interactive login (OTP from the mobile app), so signing is a manual step after the
workflow has published the release. With SimplySign Desktop running and logged in, and `gh` authenticated:

```powershell
.\mcpb\Sign-Release.ps1 -Version 0.1.2            # add -NoUpload to inspect target\signed first
```

[`mcpb/Sign-Release.ps1`](../mcpb/Sign-Release.ps1) downloads the Windows binary from the release, signs it
(SHA-256, Certum RFC 3161 timestamp), verifies it, re-packs the Windows `.mcpb` around it (the manifest has no
file hashes) and replaces both assets on the release. It reads the certificate thumbprint from
`$env:CERTUM_SIGN_THUMBPRINT` (or `$env:DISKSPACE_SIGN_THUMBPRINT`, the same certificate), so renewal is an
edit in `$PROFILE`. Always pin by thumbprint; `signtool /a` can pick the wrong certificate silently.

Not signed: the `.mcpb` container (`mcpb sign` needs the private key as a PEM file, which neither SimplySign
nor the Apple keychain exposes usefully), the Linux binaries (no signing convention), and the `-runner.jar`
(`java -jar` neither checks nor displays JAR signatures).

[`.github/workflows/build.yml`](../.github/workflows/build.yml) runs `mvn -B package` on every push and pull
request to `main`/`master` and keeps the uber-jar as a 14-day artifact.
