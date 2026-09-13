# Developer Guide

Everything beyond installing and playing: the implemented tool surface, the persistence architecture,
how to build from source, and how a release is cut. For install and first-campaign instructions, see the
[README](../README.md).

## Status — vertical slice

This is the first implementation milestone ([`MCP_PROTOCOL.md`](MCP_PROTOCOL.md) §28: *"a thin vertical
slice: discovery, resumable campaign setup, commit, session bootstrap, one deterministic check,
persistence, suspension, and fresh-context resumption"*), plus the change journal, checkpoints and the
semantic ledger, because they define the persistence architecture and are cheapest to get right first.

## Tool surface

| Family                 | Tools                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                              |
|------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Discovery              | `get_server_state`                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                 |
| Campaign setup         | `create_campaign`, `open_campaign`, `get_setup_state`, `update_campaign_setup`, `validate_campaign_setup`, `commit_campaign_setup`, `complete_campaign`                                                                                                                                                                                                                                                                                                                                                             |
| Character design       | `create_character_draft`, `get_character_choices`, `generate_ability_scores`, `update_character_draft`, `validate_character_draft`, `commit_character_draft`, `update_party_design`                                                                                                                                                                                                                                                                                                                                  |
| Session                | `bootstrap_session`, `suspend_session`, `get_character_sheet`, `get_party` (always available: full state of the player and every companion)                                                                                                                                                                                                                                                                                                                                                                         |
| Rules                  | `resolve_check` (ability/skill checks, saving throws with advantage/disadvantage), `roll_dice` (free journaled roll), `advance_time`                                                                                                                                                                                                                                                                                                                                                                                                                   |
| World                  | `materialize_location` (semantic map: containment tree, features/secrets with visibility, connections with travel time; dungeons as AREA graphs), `move_party` (route finding over known connections, clock advance, authorized routes, travel-encounter suggestions from the ground crossed and the party's XP budget)                                                                                                                                                                                                                                                                              |
| Narrative & Director   | `upsert_narrative_state` (QUEST, STORY_BEAT, STORY_SEED, FACTION_STATE, WORLD_EVENT, LOCATION_DETAIL, NPC_AGENDA), `get_diegetic_information` (committed world events by channel and place), `get_director_context`, `commit_director_changes` (player-agency guard), `get_context` (SCENE/CHARACTER/RELATIONSHIP/LOCATION/QUEST/ENCOUNTER/DIRECTOR); setup-time `adventure.seeds/story_beats/factions/world_events/locations/quests` become canonical at commit                                                       |
| Reactions              | Reactions per participant per round; Opportunity Attacks when leaving a zone with hostiles (Disengage prevents them), Shield as a reaction when hit; NPC reactions resolved automatically (or `ASK` per encounter), player-controlled reactions become typed pending choices (`resolve_pending_choice`) that hold the encounter (I-33) and resume the interrupted action and turn; knock-out-instead-of-kill via `nonlethal` melee attacks                                                                             |
| Spellcasting           | 180-spell SRD 5.2.1 seed (all cantrips, most of levels 1–3, iconic 4–9) with structured mechanics; class casting tables (ability, cantrips/prepared limits, full/half/pact slot progressions); spell choice at creation, `prepare_spells`, `cast_spell` and encounter `CAST` (spell attacks, saves, healing, Magic Missile, temp HP, buffs, cures, resurrection); slots as resources restored by rests; ritual casting (`options.ritual`, no slot); concentration with CON saves on damage; timed and round-scoped effects feeding AC, attacks, saves and checks   |
| Rest & overrides       | `perform_rest` (short rest Hit Point Dice, long rest recovery), `apply_gm_override` (policy-gated, audited, always labeled)                                                                                                                                                                                                                                                                                                                                                                                         |
| Progression            | `begin_level_up`, `get_level_up_choices`, `update_level_up`, `validate_level_up`, `commit_level_up`, `abandon_transaction` — pending transaction, HP average/roll, Ability Score Improvements, atomic commit                                                                                                                                                                                                                                                                                                         |
| Party & relationships  | `update_party_membership` (membership as history with ledger causes), `get_relationship`, `update_relationship` (dimensions −5..+5, summaries, linked significant events → episodic recall)                                                                                                                                                                                                                                                                                                                          |
| Creatures & encounters | `materialize_character` (330 SRD stat blocks, generated by `tools` `build-creatures`), `start_encounter`, `get_encounter_state`, `perform_encounter_action` (attacks with real weapons/ammunition, crits, resistances, temp HP, Dodge, items), `end_encounter` (XP split, level-up eligibility), `apply_runtime_change`, `award_xp`, `transfer_player_control`; death saves and massive damage; ENCOUNTER_RETRY checkpoints; post-death continuation states                                                                                                     |
| Content & economy      | `get_content_definitions`, `define_content`, `trade`, `transfer_item`, `give_money`, `equip_item`, `grant_loot` — SRD 5.2.1 weapons/armor/gear/tools/packs/ammunition/mounts seed, class starting-equipment bundles, AC and carrying capacity                                                                                                                                                                                                                                                                                      |
| Calendar & estate      | `set_calendar` (365-day year, weekdays, seasons derived from the clock), `create_account`, `transfer_money`, `get_accounts` (treasuries that are not a purse; `account:n` / `character:n` / `WORLD` money references), `define_cash_flow`, `update_cash_flow`, `list_cash_flows` — scheduled rules (ONCE/DAILY/WEEKLY/MONTHLY/YEARLY/SEASONAL, fixed or percent-of amounts, season multipliers, quest conditions, repayment caps, dated events) fired by the `economy` scheduler whenever `advance_time`, `move_party` or `perform_rest` moves the clock |
| Memory                 | `record_memory`, `query_memories`, `query_timeline`                                                                                                                                                                                                                                                                                                                                                                                                                                                                |
| Continuation           | `create_checkpoint`, `get_continuation_options`, `restore_checkpoint`                                                                                                                                                                                                                                                                                                                                                                                                                                              |
| Resources              | `rpg://protocol/guide`, `rpg://protocol/capabilities`, `rpg://rulesets`                                                                                                                                                                                                                                                                                                                                                                                                                                            |

Not yet implemented (reported as `CAPABILITY_UNAVAILABLE` / `false` in `rpg://protocol/capabilities`):
Counterspell/Ready/other reactions, travel/rest interruptions (travel encounters are suggested, never forced), subclasses and most class features (Sneak Attack, Font of Magic, Metamagic and Sorcerous Restoration are data-driven; the rest are GM-adjudicated from the sheet), creature
spellcasting, areas-of-effect geometry (you name the creatures in the area). See the design documents for
the target surface.

## Architecture in one paragraph

Every tool invocation is one SQLite transaction. All writes to campaign-owned tables go through a
journaling data-access layer (`persistence/Tx`) that records a full before-image of every mutated row
into `journal_entry.undo_json`. A checkpoint is a marker in that journal; `restore_checkpoint` applies
the inverse operations of every later entry in reverse order, in one transaction, leaving audit records
that survive the rollback. Client-supplied `operation_id`s make every mutation idempotent. Rules content
(SRD 5.2.1, CC-BY-4.0) ships as JSON seed files embedded in the executable and is imported on first run. The seed files
were diffed against the SRD 5.2.1 PDF text with the seed tooling in [`tools/`](../tools) (weapons, armor, gear,
packs, mounts, class tables, creature stat blocks, spell headers and stated mechanics); each file's `verify` field
records what was checked and what remains paraphrased.

## Seed tooling

[`tools/`](../tools) is a standalone Maven project (Java 25, PDFBox, Jackson) that is deliberately *not* a module of
the server pom, so the release workflow never sees it. It has one entry point, `SrdTool`, with three commands. The
SRD 5.2.1 PDF (CC-BY-4.0) downloads from
`https://media.dndbeyond.com/compendium-images/srd/5.2/SRD_CC_v5.2.1.pdf`.

```bash
# Extract the PDF to srd.txt next to it (pages delimited by "=== PAGE n ===" markers).
mvn -q -f tools/pom.xml compile exec:java -Dexec.args="extract path/to/SRD_CC_v5.2.1.pdf"

# Diff every seed file against the text; writes srd_report.txt in the current directory.
# Passing the PDF instead of srd.txt extracts first.
mvn -q -f tools/pom.xml compile exec:java -Dexec.args="verify path/to/srd.txt"

# Regenerate seed/srd5e/rules.json (the Rules Glossary, lifted verbatim) after an SRD revision.
mvn -q -f tools/pom.xml compile exec:java -Dexec.args="build-rules path/to/srd.txt"
```

Run the commands from the repository root: the seed directory is located relative to the working directory.
In `srd_report.txt`, categories without a suffix (`weapons`, `creatures`, `spells`, ...) are hard discrepancies;
the `*-info`, `*-text`, `*-upcast`, `*-table`, `*-equipment` and `*-traits` categories are context for manual
review. Verify after every seed edit; the SRD is the reference, not recollection.

## Design documents

The design is documented in depth in this directory. Read them in this order:

1. [`PROJECT_CONTEXT.md`](PROJECT_CONTEXT.md) — what this is and why it exists.
2. [`DESIGN.md`](DESIGN.md) — the architecture in full.
3. [`EXECUTION_MODEL.md`](EXECUTION_MODEL.md) — the harness, the roles, the lifecycle.
4. [`EXECUTION_EXAMPLE.md`](EXECUTION_EXAMPLE.md) — a worked session, end to end.
5. [`MCP_PROTOCOL.md`](MCP_PROTOCOL.md) — every tool, argument and error code.
6. [`DOMAIN_MODEL.md`](DOMAIN_MODEL.md) — the entities and their relationships.
7. [`DATABASE.md`](DATABASE.md) — the schema, the journal and the ledger.
8. [`RULES_ENGINE.md`](RULES_ENGINE.md) — how rules content is modelled and resolved.

## Building from source

**Prerequisites:** JDK 21+ and Maven 3.9+. For the native image, [GraalVM 25](https://www.graalvm.org/downloads/)
with `native-image`; on Windows also Visual Studio 2022 with the "Desktop development with C++" workload.

```bash
mvn package                      # uber-jar: target/rpg-mcp-server-<version>-runner.jar
mvn package -Dnative -DskipTests # native executable: target/rpg-mcp-server-<version>-runner[.exe]
mvn test-compile failsafe:integration-test -Dnative.image.path=target/rpg-mcp-server-<version>-runner.exe
```

The last command is `NativeImageSanityIT`: it launches the built binary over STDIO and drives a real MCP
handshake against it, which is the only check that catches native-image reflection and resource
regressions.

To point an MCP client at a development build, use the same configuration as a release binary but with
the path under `target/`, and set `RPG_DATA_DIR` to a scratch directory so you do not play against your
real campaign database.

## Code formatting

Formatting is enforced by [Spotless](https://github.com/diffplug/spotless) using the Eclipse formatter
profile in `config/formatter/rpg-mcp-formatting.xml` (tabs, 120 columns). The `check` goal runs in the
`validate` phase of both the server pom and `tools/pom.xml`, so a build fails on unformatted code. To fix:

```bash
mvn spotless:apply                  # server
mvn -f tools/pom.xml spotless:apply # seed tooling
```

IntelliJ users can import the same profile via Settings > Editor > Code Style > Java > Import Scheme >
Eclipse XML Profile; Eclipse users via Preferences > Java > Code Style > Formatter > Import.

## Cutting a release

Push a `v`-prefixed tag:

```bash
git tag v0.1.1 && git push origin v0.1.1
```

[`.github/workflows/release.yml`](../.github/workflows/release.yml) then sets the Maven version from the
tag (`versions:set -DnewVersion=${TAG#v}`), builds the uber-jar plus native images for linux-x86_64,
linux-aarch64, macos-aarch64 and windows-x86_64, runs the native sanity test on each, and publishes all
artifacts to a GitHub release with generated notes. The version in `pom.xml` stays at `-SNAPSHOT` on the
branch — the tag is the single source of truth for a release version.

The macOS and Windows jobs also pack an [MCP Bundle](https://github.com/anthropics/mcpb) (`.mcpb`), the
one-click install for Claude Desktop, which only exists on those two platforms (Linux users are Claude Code
or Codex users and take the bare binary). A bundle is the binary under `server/` plus a `manifest.json`
filled in from the template in [`mcpb/manifest.json`](../mcpb/manifest.json) (`__VERSION__`, `__BINARY__`
and `__PLATFORM__` are substituted). Packing happens on the runner that built the binary so the executable
bit survives on macOS. The
manifest declares the data directory as a `user_config` entry (a folder picker at install time, mapped to
`RPG_DATA_DIR`) and the standard stdio argument. The packing itself is [`mcpb/pack.sh`](../mcpb/pack.sh),
shared by the release workflow and by the manual **Bundles** workflow
([`.github/workflows/bundle.yml`](../.github/workflows/bundle.yml)), which builds the bundles for an already
published release from its binaries and attaches them: run it from the Actions tab with the version, and
choose *macos-only* when the Windows bundle has already been produced by the signing script.

The release job additionally packs a **universal** bundle, `rpg-mcp-server-<version>-universal.mcpb`, with
all four native binaries and a manifest whose `platform_overrides` pick one per operating system
([`mcpb/manifest-universal.json`](../mcpb/manifest-universal.json); on Linux a small launcher script,
[`mcpb/linux-launcher.sh`](../mcpb/linux-launcher.sh), picks the architecture). It is about 56 MB and exists for
one purpose: a Claude plugin marketplace entry can reference only one bundle for every platform. Direct
downloads should keep using the per-platform bundles. It is packed by [`mcpb/pack-universal.sh`](../mcpb/pack-universal.sh)
on Linux only, since a pack done on Windows cannot set the executable bits of the Unix binaries; the Bundles
workflow's *universal* choice re-packs it for an existing release, and the Windows signing script dispatches
that automatically after replacing the Windows binary.

The universal bundle is what the `rpg-mcp` plugin in the
[`thegreystone/claude-plugins`](https://github.com/thegreystone/claude-plugins) marketplace points at: its
`plugin.json` sets `mcpServers` to the bundle's release URL, and both Claude Code and Claude Desktop install
from that marketplace. **After every release, bump that URL and the `version` fields in the marketplace repo
(`.claude-plugin/marketplace.json` and `rpg-mcp/.claude-plugin/plugin.json`)**, once the Windows signing script
has re-packed the universal bundle. The plugin also ships the `/rpg` skill (`rpg-mcp/skills/rpg/SKILL.md`),
which is the user-facing bootstrap; keep it in step with the protocol guide.

To try a bundle locally (Git Bash on Windows):

```bash
bash mcpb/pack.sh 0.0.0 windows-x86_64 win32 target/rpg-mcp-server-*-runner.exe target/rpg-mcp-server-dev.mcpb
```

### Signing the macOS binary

The macOS job signs the binary with the Apple Developer ID certificate (hardened runtime, timestamped) and
submits it to Apple's notary service before packing the bundle, following the same recipe as
[thegreystone/diskspace](https://github.com/thegreystone/diskspace). A bare executable cannot be stapled,
so Gatekeeper fetches the notarization ticket from Apple on first run; the README keeps the `xattr` note for
offline first runs. The job fails, and no release is created, if the secrets are missing. They are the same
six as in diskspace and can be copied from there:

| Secret                    | Value                                                            |
|---------------------------|------------------------------------------------------------------|
| `MACOS_CERTIFICATE`       | Developer ID Application certificate + key as a base64-encoded `.p12` |
| `MACOS_CERTIFICATE_PWD`   | Password of that `.p12`                                          |
| `MACOS_SIGNING_IDENTITY`  | `Developer ID Application: <name> (<team id>)`                   |
| `NOTARIZATION_APPLE_ID`   | Apple ID used for notarization                                   |
| `NOTARIZATION_PASSWORD`   | App-specific password for that Apple ID                          |
| `NOTARIZATION_TEAM_ID`    | The 10-character team ID                                         |

Unlike diskspace there is no `.app` or DMG: the artifact is a command-line binary, which the bundle and the
manual install both use as-is. No entitlements are needed (a native image does not JIT and loads no
unsigned dylibs); if a signed build ever dies at launch with a code-signing error, that is the first thing
to look at.

### Signing the Windows binary

The Windows binary is Authenticode-signed with a Certum code-signing certificate through SimplySign Desktop.
SimplySign needs an interactive login (OTP from the mobile app), so signing is a manual step *after* the
workflow has published the release. With SimplySign Desktop running and logged in, and `gh` authenticated:

```powershell
.\mcpb\Sign-Release.ps1 -Version 0.1.2            # add -NoUpload to inspect target\signed first
```

[`mcpb/Sign-Release.ps1`](../mcpb/Sign-Release.ps1) downloads the Windows binary from the release, signs it
(SHA-256, Certum RFC 3161 timestamp), verifies signature and timestamp, re-packs the Windows `.mcpb` around
the signed binary and replaces both assets on the release. The bundle manifest carries no file hashes, so a
re-pack around a signed binary is a valid bundle. The script reads the certificate thumbprint from
`$env:CERTUM_SIGN_THUMBPRINT` (or `$env:DISKSPACE_SIGN_THUMBPRINT`, the same certificate) so that renewal is
a one-line edit in `$PROFILE`, not a commit. Always pin by thumbprint; `signtool /a` can pick the wrong
certificate silently.

What is *not* signed, and why: the `.mcpb` container itself (`mcpb sign` needs the private key as a PEM
file, which neither a SimplySign cloud certificate nor the Apple keychain identity exposes in a form worth
the trouble); and the Linux binaries, which have no signing convention. The `-runner.jar` could be signed
with `jarsigner` through Certum's PKCS#11 provider, but `java -jar` neither checks nor displays JAR
signatures, so it is not worth the step.

[`.github/workflows/build.yml`](../.github/workflows/build.yml) runs `mvn -B package` on every push and
pull request to `main`/`master` and keeps the uber-jar as a 14-day artifact.
