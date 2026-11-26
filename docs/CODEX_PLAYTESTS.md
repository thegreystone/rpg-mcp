# Local Codex playtests

These opt-in playtests measure how Codex discovers and uses the public RPG MCP tools. They are separate from
`mvn test`, `mvn package`, and GitHub Actions. A live run uses your locally authenticated Codex CLI and consumes
its normal model allowance. No additional API key is required when the CLI is signed in with ChatGPT.

## Prerequisites

- Node.js 18+, JDK 25+, Maven 3.9+, and Git (`tar` is also needed to compare committed revisions).
- A current Codex CLI supporting `exec --ignore-user-config --ephemeral`, authenticated with `codex login`.
- Choose an explicit model available to your account. Use the same model and reasoning effort for both sides.
- If Codex is not on PATH, pass `--codex /absolute/path/to/codex` (the native `.exe` on Windows), or set `CODEX_BIN`.

The runner uses `codex exec` and JSONL events as described in the [Codex non-interactive documentation](https://learn.chatgpt.com/docs/non-interactive-mode).

## Run

```sh
# Discover cases; no build or model calls.
node tools/playtest/run.mjs --list

# Verify every starting fixture and its grader on both revisions without calling a model.
node tools/playtest/run.mjs --prepare-only --ref master --ref working

# One inexpensive starting comparison. Replace MODEL with your chosen model name.
node tools/playtest/run.mjs --model MODEL --ref master --ref working --scenario short-rest

# Broader paired runs, with fresh contexts and corresponding seeds for both revisions.
node tools/playtest/run.mjs --model MODEL --reasoning high --ref master --ref working --repetitions 3

# Exercise real database upgrades: both sides start with databases created by master.
node tools/playtest/run.mjs --model MODEL --ref master --ref working --upgrade-from master --repetitions 2

# Upgrade an actual released v0.2.0 database through the intervening schema migrations.
node tools/playtest/run.mjs --model MODEL --ref working --upgrade-from v0.2.0 --scenario release-upgrade --repetitions 2
```

The default revision is `working`: the current checkout, including uncommitted engine changes. Other refs are
exported into the result directory with `git archive`; your checkout is never switched. Each revision is built
with `mvn package -DskipTests`, which compiles the existing test fixture helpers but does not execute tests.
Use `--offline` if all Maven dependencies are already cached. Artifacts are copied before play starts.
Preparation also applies each requested action to a separate fixture copy to verify the independent grader.

Every scenario/repetition/revision gets a newly created database. Multi-step scenarios retain that database
while restarting both Codex and the MCP server between player requests, so later steps must reconstruct
state from saved data. Each paired seed starts from the same fixture source and deterministic engine setup;
the server also uses the same dice seed.
Changing the order or number of random operations can still change later rolls. The default repetition count
is one, the time limit is 300 seconds per request, and the tool-call limit is 60 per request. Configure these using `--repetitions`,
`--timeout-seconds`, and `--max-tool-calls`. At most 20 repetitions are accepted per invocation.

## Cases and grading

| Case | Player request | Independent state checks |
|---|---|---|
| `short-rest` | Spend exactly two Hit Point Dice, excluding Mira | One hour, two dice spent, Richard healed, Mira unchanged |
| `companion-control` | Control Mira until explicitly released | Saved persistent control, primary character retained, no movement/time |
| `pending-status` | Ask for status while a reaction is pending | HP, effects, resources, controller, pending choice and turn remain unchanged |
| `combat-reaction` | Take the pending opportunity attack with a Club | Exactly one resolved reaction, no subsequent action, encounter remains open |
| `level-up` | Complete sorcerer level two with named Metamagic | Level two, transaction closed, no additional XP or elapsed time |
| `interrupted-travel` | Negotiate a road contact and finish the saved journey | Arrival at Old Mill, only the remaining 25 minutes elapse |
| `upgrade-recall` | Revisit a completed battle saved by the old binary | Full historical record read; no obsolete tool hints, changed history, extra XP or loot |
| `upgrade-checkpoint` | Restore an old checkpoint, then verify after a restart | HP, coins, items, clock and primary character match the checkpoint; one restore |
| `spell-potion` | Cast Mage Armor, drink a potion, then inspect after restart | One slot and one potion spent, healing/effect persist, no duplicate use |
| `session-recovery` | Save control and a promise, suspend, then resume | Mira's controller and the brooch promise persist across three sessions |
| `commerce-sequence` | Buy two Torches, give one to Mira, then inspect | Exact coin debit and item quantities persist across restart |
| `upgrade-level-up` | Finish a level-up already open before the upgrade | Saved transaction committed, named Metamagic, no abandoned/restarted transaction |
| `upgrade-death` | Continue with a survivor after an old saved death | Mira becomes primary; Richard stays dead and history, inventory and clock are preserved |
| `release-upgrade` | Resume an actual released database, then restore its old checkpoint | Original state survives migration; old undo records correctly restore HP, conditions, slots, coins, items and memories |

The saved-game workflows from `upgrade-recall` through `upgrade-death` create their starting database with `master` by default, including its actual
serialized hints and transaction payloads. The selected target engine then opens that database. Pass
`--upgrade-from REF` to use a different old revision and to apply this upgrade setup to every selected scenario.
No existing player database is accessed. The manifest records both fixture and target revision hashes.
`release-upgrade` uses a portable creator that compiles against the released API and records the old schema's
state before any new migration runs. Use that scenario when `--upgrade-from` names a release older than the
map/tactical APIs; the other scenarios require those APIs to prepare their fixtures.

Each grader also restricts successful journal mutations to those needed for that request. The model never sees
the grader, expected arguments, fixture internals, or database paths. It receives player intent and the selected
revision's live MCP surface. A transparent recorder captures actual JSON-RPC tool calls and errors.

## Evidence and interpretation

Results go under ignored `target/codex-playtests/<timestamp>/`:

- `manifest.json`: revision/artifact/harness hashes, dirty checkout flag, model, reasoning, versions, limits and seeds.
- `harness/`: the exact runner, proxy, scenario and fixture sources captured before the run.
- `report.md` and `results.json`: outcomes, paired call/error deltas, manual fetches and timings.
- Per case: combined `response.md`, `mcp.jsonl`, and `result.json`, with a `turn-N/` folder for each request.
- Per request: `prompt.txt`, `response.md`, `codex.jsonl`, `codex.stderr.log`, `mcp.jsonl`, `grade.json`, and `after.json`.
- Per campaign: `rpg.db`, `before.json`, `after.json`, and the grader's `grade.json`.

Model-reported token usage and the raw tool-catalog size are included in the report. Input totals include
repeated context across requests; cached tokens are part of that total. Regenerate a report without a model
using `node tools/playtest/run.mjs --report target/codex-playtests/RUN_DIRECTORY`. Copy evidence you want to
keep before running `mvn clean`, which removes these scratch results.

`passed` means the state checks passed and Codex completed a live tool-using turn. It does **not** certify good
narration. Review responses/traces for fabricated facts, visible tool identifiers, unnecessary questions,
companion agency, and whether the player request was understood. Failed credentials, provider errors, missing
MCP servers, timeouts and tool limits are reported separately from gameplay failures. The command exits nonzero
for either a failed outcome or a runner failure, but is not a required CI check.

The trace audit checks public tool hints against the actual selected server catalog, and rejects model shell
calls or unrelated MCP servers. Player responses are checked for internal reference leaks. Session recovery
also checks that the saved promise is mentioned after restarting. State checks run after every request.
The grader permits the engine's automatic installed-rules refresh during an upgrade while separately checking
that saved character state and resources are preserved. It does not permit the model to refresh, heal or refill
anything through unrelated mutations.

Deterministic compatibility regressions, including saved tool pointers and idempotent pre-upgrade retries, live
in `ProtocolHintsTest` and run with normal Maven tests. Only the model-driven playtests require local Codex.

The controlled run excludes personal plugins, project instructions, command execution, web search and unrelated
Director/chronicle maintenance. Authentication remains in the normal Codex credential store; the runner does not
copy credentials or change your global configuration. Local RPG mutations are preapproved against disposable
campaigns, so this suite does not measure approval-dialog behavior. Transcripts and databases remain local.

Small samples are useful for finding problems, not for estimating a reliable regression percentage. Review paired
outcomes before comparing call counts: an early failure must not look like an efficiency improvement. Promote
reproducible engine defects into normal deterministic tests.
Starting-state comparisons ignore object-key order, including embedded database JSON, while preserving array
order and all values. Raw snapshots remain unchanged for history-preservation checks.

The runner's own bookkeeping can be tested without a model or Java build:

```sh
node --test tools/playtest/run.test.mjs
```
