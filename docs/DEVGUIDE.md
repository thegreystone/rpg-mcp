# Developer Guide

Everything beyond installing and playing: the tool surface, the persistence architecture, building from
source, and cutting a release. Install and first-campaign instructions are in the [README](../README.md).

## Status — vertical slice

The first implementation milestone ([`MCP_PROTOCOL.md`](MCP_PROTOCOL.md) §28: discovery, resumable campaign
setup, commit, session bootstrap, one deterministic check, persistence, suspension, fresh-context resumption)
plus the change journal, checkpoints and the semantic ledger, which define the persistence architecture.

## Tool surface

| Family                 | Tools                                                                                                                                                                                                                                                                  |
|------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Discovery              | `get_server_state`                                                                                                                                                                                                                                                     |
| Campaign setup         | `create_campaign`, `open_campaign`, `get_setup_state`, `update_campaign_setup`, `validate_campaign_setup`, `commit_campaign_setup`, `complete_campaign`                                                                                                                 |
| Character design       | `create_character_draft`, `get_character_choices`, `generate_ability_scores`, `update_character_draft`, `validate_character_draft`, `commit_character_draft`, `update_party_design`                                                                                      |
| Session                | `bootstrap_session`, `suspend_session`, `get_character_sheet`, `get_party` (the last two always available)                                                                                                                                                             |
| Rules                  | `resolve_check` (checks and saves with advantage/disadvantage), `roll_dice` (free journaled roll), `advance_time`, `search_rules`                                                                                                                                       |
| World                  | `materialize_location` (semantic map: containment tree, features with visibility, connections with travel time), `move_party` (route finding, clock advance, travel-encounter suggestions), `find`                                                                       |
| Narrative & Director   | `upsert_narrative_state` (QUEST, STORY_BEAT, STORY_SEED, FACTION_STATE, WORLD_EVENT, LOCATION_DETAIL, NPC_AGENDA), `get_diegetic_information`, `get_director_context`, `commit_director_changes`, `get_context`, `update_character`, `update_house_rules`                |
| Reactions              | Opportunity Attacks (Disengage prevents them), Shield when hit; NPC reactions automatic or `ASK`; player reactions are pending choices (`resolve_pending_choice`) that hold the encounter (I-33); `nonlethal` melee                                                       |
| Spellcasting           | 180-spell SRD 5.2.1 seed; class casting tables; `prepare_spells`, `cast_spell`, encounter `CAST`; slots as rest-restored resources; ritual casting; concentration; timed and round-scoped effects; Metamagic                                                             |
| Rest & overrides       | `perform_rest`, `apply_gm_override` (policy-gated, audited, labeled)                                                                                                                                                                                                   |
| Progression            | `begin_level_up`, `get_level_up_choices`, `update_level_up`, `validate_level_up`, `commit_level_up`, `abandon_transaction`                                                                                                                                              |
| Party & relationships  | `update_party_membership` (membership as history), `get_relationship`, `update_relationship` (dimensions −5..+5, profiles, linked events)                                                                                                                              |
| Creatures & encounters | `materialize_character` (330 SRD stat blocks from `tools` `build-creatures`), `start_encounter`, `get_encounter_state`, `perform_encounter_action`, `end_encounter` (XP split, level-up eligibility), `apply_runtime_change`, `award_xp`, `transfer_player_control`      |
| Content & economy      | `get_content_definitions`, `define_content`, `trade`, `transfer_item`, `give_money`, `equip_item`, `grant_loot` (SRD 5.2.1 equipment seed, starting-equipment bundles, AC, carrying capacity)                                                                           |
| Calendar & estate      | `set_calendar`, `create_account`, `transfer_money`, `get_accounts`, `define_cash_flow`, `update_cash_flow`, `list_cash_flows` (scheduled rules fired whenever `advance_time`, `move_party` or `perform_rest` moves the clock)                                             |
| Memory & chronicle     | `record_memory`, `query_memories`, `query_timeline`, `get_chronicle_material`, `write_chronicle`                                                                                                                                                                        |
| Continuation           | `create_checkpoint`, `get_continuation_options`, `restore_checkpoint`                                                                                                                                                                                                  |
| Resources              | `rpg://protocol/guide`, `rpg://protocol/capabilities`, `rpg://rulesets`                                                                                                                                                                                                |

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
server pom so the release workflow never sees it. Its entry point `SrdTool` has three commands. The SRD 5.2.1
PDF (CC-BY-4.0) downloads from `https://media.dndbeyond.com/compendium-images/srd/5.2/SRD_CC_v5.2.1.pdf`.

```bash
# Extract the PDF to srd.txt next to it (pages delimited by "=== PAGE n ===" markers).
mvn -q -f tools/pom.xml compile exec:java -Dexec.args="extract path/to/SRD_CC_v5.2.1.pdf"

# Diff every seed file against the text; writes srd_report.txt in the current directory.
# Passing the PDF instead of srd.txt extracts first.
mvn -q -f tools/pom.xml compile exec:java -Dexec.args="verify path/to/srd.txt"

# Regenerate seed/srd5e/rules.json (the Rules Glossary, lifted verbatim) after an SRD revision.
mvn -q -f tools/pom.xml compile exec:java -Dexec.args="build-rules path/to/srd.txt"
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
mvn test-compile failsafe:integration-test -Dnative.image.path=target/rpg-mcp-server-<version>-runner.exe
```

The last command runs `NativeImageSanityIT`, which launches the binary over STDIO and drives a real MCP
handshake. It is the only check that catches native-image reflection and resource regressions.

To point an MCP client at a development build, use the release configuration with the path under `target/`,
and set `RPG_DATA_DIR` to a scratch directory so you do not play against your real campaign database.

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

**Marketplace.** The `rpg-mcp` plugin in [`thegreystone/claude-plugins`](https://github.com/thegreystone/claude-plugins)
ships a committed copy of the universal bundle as `rpg-mcp/rpg-mcp-server.mcpb`, referenced by relative path,
because Claude Desktop rejects a URL there ("MCPB URL references are not allowed in plugins because the target
can change after review"). **After every release, once the universal bundle is re-packed, run
`scripts/update-bundle.sh <version>` in the marketplace repo and commit the result**: it replaces the copy and
bumps `version` in `.claude-plugin/marketplace.json` and `rpg-mcp/.claude-plugin/plugin.json`. The plugin also
ships the `/rpg` skill (`rpg-mcp/skills/rpg/SKILL.md`); keep it in step with the protocol guide.

**User-facing docs never send people to the marketplace.** A catalogue install skips the bundle's
`user_config` page, so the data directory can never be chosen. The README sends users to the per-platform
`.mcpb` on the release, installed directly in Claude Desktop. The marketplace entry stays for the `/rpg` skill
and for Claude Code.

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
