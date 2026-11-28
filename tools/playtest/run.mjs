#!/usr/bin/env node
// Copyright (C) 2026 Marcus Hirt. SPDX-License-Identifier: BSD-3-Clause
import { spawn, spawnSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import fs from 'node:fs/promises';
import { createWriteStream, existsSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { scenarios, scenarioDetails, promptFor } from './scenarios.mjs';

const directory = path.dirname(fileURLToPath(import.meta.url));
const repository = path.resolve(directory, '../..');
let interrupted = false;
const help = `Local Codex RPG playtests (never run by Maven or GitHub Actions).

  node tools/playtest/run.mjs --model <model> [options]

  --ref <revision>          Repeat to compare revisions; default: working
                           'working' includes current uncommitted engine changes.
  --upgrade-from <revision> Create every starting database with this older revision
  --scenario <name>         Repeat to select cases; default: all
  --model <name>            Pin the Codex model for live runs
  --reasoning <effort>      Optional reasoning effort (recorded in manifest)
  --codex <executable>      Codex CLI path (or CODEX_BIN / PATH)
  --repetitions <n>         Paired seeds per case; default: 1
  --seed <integer>          Initial seed; default: 42
  --timeout-seconds <n>     Wall-clock limit per Codex run; default: 300
  --max-tool-calls <n>      Tool-call limit per Codex run; default: 60
  --prepare-only           Build revisions and fixtures without calling a model
  --report <run-directory>  Regenerate a saved report without calling a model
  --offline                Pass Maven -o (dependencies must be cached)
  --list                   List scenarios without building or calling a model
  --help                   Show this help

Results: target/codex-playtests/<timestamp>/report.md (plus JSON, traces and databases).
Requires Node 18+, JDK 25+, Maven 3.9+, and a signed-in Codex CLI for live runs.
`;

export function parseArgs(args) {
  const options = { refs: [], scenarios: [], repetitions: 1, seed: 42, timeoutSeconds: 300, maxToolCalls: 60 };
  const values = { '--ref': 'refs', '--scenario': 'scenarios', '--model': 'model', '--reasoning': 'reasoning', '--codex': 'codex', '--repetitions': 'repetitions', '--seed': 'seed', '--timeout-seconds': 'timeoutSeconds', '--max-tool-calls': 'maxToolCalls', '--report': 'report', '--upgrade-from': 'upgradeFrom' };
  const flags = { '--prepare-only': 'prepareOnly', '--offline': 'offline', '--list': 'list', '--help': 'help', '-h': 'help' };
  for (let i = 0; i < args.length; i++) {
    const option = args[i];
    if (flags[option]) { options[flags[option]] = true; continue; }
    if (!values[option]) throw new Error(`Unknown option ${option}. Use --help.`);
    const value = args[++i];
    if (!value || value.startsWith('--')) throw new Error(`${option} needs a value.`);
    const key = values[option];
    if (Array.isArray(options[key])) options[key].push(value);
    else options[key] = value;
  }
  for (const key of ['repetitions', 'seed', 'timeoutSeconds', 'maxToolCalls']) {
    options[key] = Number(options[key]);
    if (!Number.isSafeInteger(options[key]) || options[key] < (key === 'seed' ? 0 : 1)) throw new Error(`${key} must be ${key === 'seed' ? 'a nonnegative' : 'a positive'} integer.`);
  }
  if (options.repetitions > 20) throw new Error('Use at most 20 repetitions per invocation.');
  options.refs = [...new Set(options.refs.length ? options.refs : ['working'])];
  options.scenarios = [...new Set(options.scenarios.length ? options.scenarios : Object.keys(scenarios))];
  for (const name of options.scenarios) if (!Object.hasOwn(scenarios, name)) throw new Error(`Unknown scenario ${name}. Use --list.`);
  if (!options.help && !options.list && !options.report && !options.prepareOnly && !options.model) throw new Error('Choose --model explicitly so comparisons use the same model. Use --prepare-only for an unpaid fixture check.');
  return options;
}

export function commandSpec(command, args, platform = process.platform) {
  if (platform === 'win32' && /\.(cmd|bat)$/i.test(command)) {
    // Windows batch launchers cannot be passed directly to spawn(). Refuse cmd metacharacters.
    const tokens = [command, ...args];
    if (tokens.some(value => /["%\r\n&|<>^!]/.test(value))) throw new Error('Unsafe character in Windows batch argument; use the native executable instead.');
    return { command: process.env.ComSpec || 'cmd.exe', args: ['/d', '/s', '/c', `"${tokens.map(value => `"${value}"`).join(' ')}"`], windowsVerbatimArguments: true };
  }
  return { command, args };
}

async function execute(command, args, { cwd = repository, log, input, timeoutMs = 600_000, limitFile } = {}) {
  const spec = commandSpec(command, args);
  const child = spawn(spec.command, spec.args, { cwd, windowsHide: true, detached: process.platform !== 'win32', windowsVerbatimArguments: spec.windowsVerbatimArguments, stdio: ['pipe', 'pipe', 'pipe'] });
  const output = log ? createWriteStream(log) : null;
  let stdout = '', stderr = '', stopped = null;
  const kill = () => {
    if (process.platform === 'win32') spawnSync('taskkill', ['/pid', String(child.pid), '/t', '/f'], { windowsHide: true, stdio: 'ignore' });
    else { try { process.kill(-child.pid, 'SIGKILL'); } catch { child.kill('SIGKILL'); } }
  };
  const interrupt = () => { interrupted = true; stopped = 'interrupted'; kill(); };
  process.once('SIGINT', interrupt);
  const timer = setTimeout(() => { stopped = 'timeout'; kill(); }, timeoutMs);
  const limitTimer = limitFile ? setInterval(() => { if (existsSync(limitFile)) { stopped = 'tool_budget'; kill(); } }, 250) : null;
  child.stdout.on('data', data => { stdout += data; output?.write(data); });
  child.stderr.on('data', data => { stderr += data; if (output && !log.endsWith('.jsonl')) output.write(data); });
  child.stdin.on('error', () => {});
  child.stdin.end(input);
  try {
    const code = await new Promise((resolve, reject) => { child.once('error', reject); child.once('close', resolve); });
    return { code, stdout, stderr, stopped };
  } finally {
    clearTimeout(timer);
    if (limitTimer) clearInterval(limitTimer);
    process.removeListener('SIGINT', interrupt);
    if (output) await new Promise(resolve => output.end(resolve));
  }
}

function executable(name) {
  if (name.includes('/') || name.includes('\\')) return existsSync(name) ? path.resolve(name) : null;
  const extensions = process.platform === 'win32' ? ['.exe', '.cmd', '.bat', ''] : [''];
  for (const base of (process.env.PATH || '').split(path.delimiter)) {
    for (const suffix of extensions) {
      const candidate = path.join(base, `${name}${suffix}`);
      if (existsSync(candidate)) return candidate;
    }
  }
  return null;
}

async function codexExecutable(selected) {
  const nativeNpm = launcher => {
    const bases = [path.dirname(launcher), ...(process.env.APPDATA ? [path.join(process.env.APPDATA, 'npm')] : [])];
    for (const base of bases) for (const arch of ['x64', 'arm64']) {
      const native = path.join(base, `node_modules/@openai/codex/node_modules/@openai/codex-win32-${arch}/vendor/${arch === 'x64' ? 'x86_64' : 'aarch64'}-pc-windows-msvc/codex/codex.exe`);
      if (existsSync(native)) return native;
    }
    return null;
  };
  const normalize = resolved => {
    if (process.platform !== 'win32' || !/\.(cmd|bat)$/i.test(resolved)) return resolved;
    const native = nativeNpm(resolved);
    if (!native) throw new Error('Use --codex with the native codex.exe; the batch shim cannot safely forward TOML arguments.');
    return native;
  };
  if (selected || process.env.CODEX_BIN) {
    const resolved = executable(selected || process.env.CODEX_BIN);
    if (!resolved) throw new Error('Codex executable not found. Set --codex to its native executable.');
    return normalize(resolved);
  }
  const onPath = executable('codex');
  if (onPath) return normalize(onPath);
  const native = process.platform === 'win32' ? nativeNpm('codex') : null;
  if (native) return native;
  throw new Error('Codex CLI was not found. Install/sign in to Codex, then use --codex <executable> or CODEX_BIN.');
}

async function checked(command, args, options) {
  const result = await execute(command, args, options);
  if (result.code !== 0 || result.stopped) throw new Error(`${path.basename(command)} failed (${result.stopped || result.code}). ${options?.log ? `See ${options.log}` : result.stderr.slice(-1200)}`);
  return result.stdout.trim();
}

async function buildRevision(ref, root, options) {
  const sha = await checked('git', ['rev-parse', '--verify', '--end-of-options', `${ref === 'working' ? 'HEAD' : ref}^{commit}`]);
  const dirty = ref === 'working' ? Boolean(await checked('git', ['status', '--porcelain', '--untracked-files=no'])) : false;
  const label = `${ref.replace(/[^a-zA-Z0-9_-]/g, '_')}-${sha.slice(0, 8)}`;
  const build = ref === 'working' ? repository : path.join(root, 'sources', label);
  if (ref !== 'working') {
    await fs.mkdir(build, { recursive: true });
    const archive = path.join(root, `${label}.tar`);
    await checked('git', ['archive', '--format=tar', '-o', archive, sha]);
    await checked('tar', ['-xf', archive, '-C', build]);
  }
  process.stdout.write(`Building ${ref} (${sha.slice(0, 8)})...\n`);
  const log = path.join(root, `${label}-build.log`);
  await checked(executable('mvn') || 'mvn', [...(options.offline ? ['-o'] : []), '-B', 'package', '-DskipTests', '-Dstyle.color=never'], { cwd: build, log });
  const jars = (await fs.readdir(path.join(build, 'target'))).filter(name => /^rpg-mcp-server-.*-runner\.jar$/.test(name));
  if (jars.length !== 1) throw new Error(`Expected one server runner jar in ${build}/target, found ${jars.length}.`);
  // Copy working artifacts too: subsequent builds cannot change a running test's binary.
  const artifact = path.join(root, 'artifacts', label);
  await fs.mkdir(artifact, { recursive: true });
  const jar = path.join(artifact, 'server.jar');
  await fs.copyFile(path.join(build, 'target', jars[0]), jar);
  await fs.cp(path.join(build, 'target/test-classes'), path.join(artifact, 'test-classes'), { recursive: true });
  const fixtures = path.join(artifact, 'fixtures');
  await fs.mkdir(fixtures);
  const modernFixture = /\btacticalControl\s*\(/.test(await fs.readFile(path.join(build, 'src/main/java/se/hirt/mcp/rpg/Engine.java'), 'utf8'));
  const fixtureSources = ['LegacyFixture.java', ...(modernFixture ? ['Fixture.java'] : [])].map(name => path.join(options.harnessDir || directory, name));
  await checked(executable('javac') || 'javac', ['-cp', [path.join(artifact, 'test-classes'), jar].join(path.delimiter), '-d', fixtures,
    ...fixtureSources], { log: path.join(root, `${label}-fixture-compile.log`) });
  return { ref, sha, dirty, label, jar, modernFixture, classpath: [fixtures, path.join(artifact, 'test-classes'), jar].join(path.delimiter) };
}

export function codexArgs({ workspace, proxy, java, jar, dataDir, trace, seed, maxToolCalls, model, reasoning, response }) {
  const config = {
    approval_policy: 'never', web_search: 'disabled', project_doc_max_bytes: 0,
    'features.shell_tool': false, 'features.apps': false, 'features.memories': false,
    'features.hooks': false, 'features.multi_agent': false,
    'skills.bundled.enabled': false, 'skills.include_instructions': false,
    'mcp_servers.rpg.command': process.execPath,
    'mcp_servers.rpg.args': [proxy, java, jar, dataDir, trace, String(seed), String(maxToolCalls)],
    'mcp_servers.rpg.required': true, 'mcp_servers.rpg.startup_timeout_sec': 45,
    'mcp_servers.rpg.default_tools_approval_mode': 'approve',
  };
  if (reasoning) config.model_reasoning_effort = reasoning;
  return ['--no-daemon', 'exec', '--ignore-user-config', '--ephemeral', '--skip-git-repo-check', '--sandbox', 'read-only', '--model', model,
    '--json', '--color', 'never', '-C', workspace, '-o', response,
    ...Object.entries(config).flatMap(([key, value]) => ['-c', `${key}=${JSON.stringify(value)}`]), '-'];
}

export function metricsFromTrace(lines) {
  const pending = new Map(), calls = [], catalog = [], protocolErrors = [];
  for (const line of lines.split(/\r?\n/)) {
    if (!line.trim()) continue;
    const entry = JSON.parse(line), message = entry.message;
    if (entry.direction === 'request' && message.method === 'tools/call') pending.set(message.id, { at: entry.at, ...message.params });
    if (entry.direction === 'response' && Array.isArray(message.result?.tools)) catalog.push(...message.result.tools);
    if (entry.direction === 'response' && pending.has(message.id)) {
      const call = pending.get(message.id);
      pending.delete(message.id);
      const text = (message.result?.content || []).filter(c => c.type === 'text').map(c => c.text).join('\n');
      let envelope;
      try { envelope = JSON.parse(text); } catch {}
      calls.push({ tool: call.name, action: call.arguments?.action ?? null, arguments: call.arguments, failed: Boolean(message.error || message.result?.isError || envelope?.error),
        error_code: envelope?.error?.code ?? message.error?.code ?? null, milliseconds: Date.parse(entry.at) - Date.parse(call.at) });
    }
    if (entry.direction === 'proxy_error') protocolErrors.push(message.message);
  }
  const unique = [...new Map(catalog.map(t => [t.name, t])).values()];
  return { tool_calls: calls.length + pending.size, tool_errors: calls.filter(c => c.failed).length,
    invalid_arguments: calls.filter(c => c.error_code === 'INVALID_ARGUMENT' || c.error_code === -32602).length,
    instructions_calls: calls.filter(c => c.tool === 'get_instructions').length,
    first_call_valid: calls.length > 0 ? !calls[0].failed : null,
    pending_calls: pending.size, exposed_tools: unique.length, catalog_bytes: Buffer.byteLength(JSON.stringify(unique)),
    protocol_errors: protocolErrors, calls };
}

async function readOr(file, fallback) {
  try { return await fs.readFile(file, 'utf8'); } catch (error) { if (error.code === 'ENOENT') return fallback; throw error; }
}

export function auditTrace(lines) {
  const entries = lines.split(/\r?\n/).filter(Boolean).map(line => JSON.parse(line));
  const names = new Set(entries.flatMap(e => e.direction === 'response' ? (e.message.result?.tools || []).map(t => t.name) : []));
  const violations = new Set();
  const legacy = /^(?:abandon_|add_|advance_|annotate_|apply_|award_|begin_|bootstrap_|cast_|commit_|complete_|continue_|convert_|correct_|create_|define_|emit_|end_|equip_|export_|find_|generate_|get_|give_|grant_|import_|list_|materialize_|move_|observe_|open_|perform_|place_|prepare_|preview_|publish_|query_|record_|recover_|resolve_|restore_|revise_|roll_|schedule_|search_|set_|start_|suspend_|transfer_|update_|upsert_|use_|validate_|write_)/;
  const checkLabel = value => {
    if (typeof value === 'string' && (legacy.test(value) || value === 'trade' || value === 'find') && !names.has(value.split(':')[0])) violations.add(`Unavailable tool hint: ${value}`);
  };
  const visit = value => {
    if (!value || typeof value !== 'object') return;
    if (Array.isArray(value)) { value.forEach(visit); return; }
    for (const [key, child] of Object.entries(value)) {
      if (['tool', 'resolve_with', 'recover_with', 'annotation_tool', 'supported_alternative', 'next_operation', 'next_tool'].includes(key)) checkLabel(child);
      if (['allowed_operations', 'legal_actions', 'legal_operations'].includes(key) && Array.isArray(child)) child.forEach(checkLabel);
      visit(child);
    }
  };
  for (const entry of entries) if (entry.direction === 'response') {
    for (const content of entry.message.result?.content || []) if (content.type === 'text') {
      try { visit(JSON.parse(content.text)); } catch {}
    }
  }
  return [...violations];
}

export function aggregateMetrics(turns) {
  const metrics = { tool_calls: 0, tool_errors: 0, invalid_arguments: 0, instructions_calls: 0, pending_calls: 0,
    exposed_tools: 0, catalog_bytes: 0, protocol_errors: [], calls: [] };
  for (const turn of turns) {
    for (const key of ['tool_calls', 'tool_errors', 'invalid_arguments', 'instructions_calls', 'pending_calls']) metrics[key] += turn.metrics[key];
    metrics.exposed_tools = Math.max(metrics.exposed_tools, turn.metrics.exposed_tools);
    metrics.catalog_bytes = Math.max(metrics.catalog_bytes, turn.metrics.catalog_bytes);
    metrics.protocol_errors.push(...turn.metrics.protocol_errors);
    metrics.calls.push(...turn.metrics.calls.map(call => ({ step: turn.step, ...call })));
  }
  metrics.first_call_valid = turns[0]?.metrics.first_call_valid ?? null;
  return metrics;
}

export function startingStateHash(json) {
  const canonical = (value, key = '') => {
    // Persisted JSON documents can differ only in Java Map iteration order between JVMs.
    if (typeof value === 'string' && key.endsWith('_json')) {
      try { return canonical(JSON.parse(value)); } catch { return value; }
    }
    if (Array.isArray(value)) return value.map(item => canonical(item));
    if (value && typeof value === 'object') return Object.fromEntries(
      Object.keys(value).sort().map(name => [name, canonical(value[name], name)]));
    return value;
  };
  return createHash('sha256').update(JSON.stringify(canonical(JSON.parse(json)))).digest('hex');
}

async function runCase(revision, origin, scenario, repetition, root, options, codex, java) {
  const seed = options.seed + repetition - 1;
  const run = path.join(root, revision.label, `${scenario}-${repetition}`);
  const dataDir = path.join(run, 'campaign');
  await fs.mkdir(run, { recursive: true });
  if (!revision.modernFixture) throw new Error('Select a current target engine; old release artifacts can create fixtures but cannot grade modern play scenarios.');
  if (!origin.modernFixture && scenario !== 'release-upgrade') throw new Error('Use --scenario release-upgrade for a release older than the map/tactical APIs.');
  const creator = scenario === 'release-upgrade' ? 'se.hirt.mcp.rpg.LegacyFixture' : 'se.hirt.mcp.rpg.Fixture';
  await checked(java, ['-cp', origin.classpath, creator, 'create', scenario, dataDir, String(seed)], { log: path.join(run, 'fixture.log') });
  const startingState = startingStateHash(await fs.readFile(path.join(dataDir, 'before.json'), 'utf8'));
  const base = { revision: revision.ref, fixture_revision: origin.ref, fixture_sha: origin.sha, starting_state_sha256: startingState, scenario, repetition, seed, directory: run };
  if (options.prepareOnly) {
    const validation = path.join(run, 'grader-validation');
    await fs.cp(dataDir, validation, { recursive: true });
    await checked(java, ['-cp', revision.classpath, 'se.hirt.mcp.rpg.Fixture', 'exercise', scenario, validation, String(seed)], { log: path.join(run, 'grader-validation.log') });
    return { ...base, status: 'prepared' };
  }
  const started = Date.now();
  const turns = [], responses = [], allTraces = [], details = scenarioDetails(scenario);
  for (let step = 0; step < details.turns.length; step++) {
    const turnDir = path.join(run, `turn-${step + 1}`), workspace = path.join(turnDir, 'workspace');
    await fs.mkdir(workspace, { recursive: true });
    const trace = path.join(turnDir, 'mcp.jsonl'), response = path.join(turnDir, 'response.md');
    const args = codexArgs({ workspace, proxy: path.join(options.harnessDir || directory, 'mcp-proxy.mjs'), java, jar: revision.jar, dataDir, trace, seed,
      maxToolCalls: options.maxToolCalls, model: options.model, reasoning: options.reasoning, response });
    const prompt = promptFor(scenario, step);
    await fs.writeFile(path.join(turnDir, 'prompt.txt'), prompt);
    process.stdout.write(`Playing ${revision.ref}: ${scenario} (seed ${seed}, step ${step + 1}/${details.turns.length}, fixture ${origin.ref})...\n`);
    const turnStarted = Date.now();
    const execution = await execute(codex, args, { cwd: workspace, log: path.join(turnDir, 'codex.jsonl'), input: prompt,
      timeoutMs: options.timeoutSeconds * 1000, limitFile: `${trace}.limit` });
    if (existsSync(`${trace}.limit`)) execution.stopped ||= 'tool_budget';
    await fs.writeFile(path.join(turnDir, 'codex.stderr.log'), execution.stderr);
    const traceText = await readOr(trace, '');
    const metrics = metricsFromTrace(traceText), violations = auditTrace(traceText);
    allTraces.push(traceText);
    const events = execution.stdout.split(/\r?\n/).filter(Boolean).flatMap(line => { try { return [JSON.parse(line)]; } catch { return []; } });
    const completed = events.some(e => e.type === 'turn.completed');
    const usage = events.filter(e => e.type === 'turn.completed').map(e => e.usage);
    for (const e of events) if (e.type === 'item.completed') {
      if (e.item?.type === 'mcp_tool_call' && e.item.server !== 'rpg') violations.push(`Unexpected MCP server: ${e.item.server}`);
      if (e.item?.type === 'command_execution') violations.push('Model ran a shell command');
    }
    await checked(java, ['-cp', revision.classpath, 'se.hirt.mcp.rpg.Fixture', 'grade', scenario, dataDir, String(seed), String(step)], { log: path.join(turnDir, 'grader.log') });
    const grade = JSON.parse(await fs.readFile(path.join(dataDir, 'grade.json'), 'utf8'));
    const text = await readOr(response, '');
    if (/\b(?:character|campaign|inventory|transaction|encounter|checkpoint|map):\d+/.test(text)) violations.push('Player response exposed an internal reference');
    if (scenario === 'session-recovery' && step === 2) grade.checks.promise_recalled_in_response = /brooch/i.test(text);
    if (scenario === 'release-upgrade' && step === 0) grade.checks.old_promise_recalled_in_response = /copper key/i.test(text);
    if (scenario === 'upgrade-recall') grade.checks.full_historical_record_read = metrics.calls.some(c => !c.failed &&
      ((c.tool === 'get_encounter_state' && String(c.arguments?.audience).toUpperCase() === 'GM') ||
       (c.tool === 'encounter' && String(c.action).trim().toUpperCase() === 'STATE' && String(c.arguments?.params?.audience).toUpperCase() === 'GM')));
    if (scenario === 'interrupted-travel') grade.checks.negotiated_resolution = metrics.calls.some(c => !c.failed &&
      ((c.tool === 'resolve_travel_opportunity' && c.arguments?.resolution === 'NEGOTIATED') ||
       (c.tool === 'world_travel' && String(c.action).trim().toUpperCase() === 'RESOLVE_OPPORTUNITY' && c.arguments?.params?.resolution === 'NEGOTIATED')));
    grade.checks.valid_public_hints_and_response = violations.length === 0;
    grade.passed = Object.values(grade.checks).every(Boolean);
    await fs.writeFile(path.join(turnDir, 'grade.json'), `${JSON.stringify(grade, null, 2)}\n`);
    await fs.copyFile(path.join(dataDir, 'after.json'), path.join(turnDir, 'after.json'));
    const status = execution.stopped || (execution.code !== 0 || !completed || !metrics.tool_calls || metrics.pending_calls || metrics.protocol_errors.length ? 'runner_error' : grade.passed ? 'passed' : 'failed');
    const turn = { step: step + 1, status, seconds: (Date.now() - turnStarted) / 1000, completed, usage, metrics, grade, violations, exit_code: execution.code };
    turns.push(turn);
    responses.push(`## Request ${step + 1}\n\n${text}`);
    await fs.writeFile(path.join(turnDir, 'result.json'), `${JSON.stringify(turn, null, 2)}\n`);
    process.stdout.write(`  ${status}: ${metrics.tool_calls} calls, ${metrics.tool_errors} errors\n`);
    if (!['passed', 'failed'].includes(status)) break;
  }
  const metrics = aggregateMetrics(turns);
  const status = turns.find(t => !['passed', 'failed'].includes(t.status))?.status || (turns.every(t => t.status === 'passed') ? 'passed' : 'failed');
  const result = { ...base, status, seconds: (Date.now() - started) / 1000, completed: turns.length === details.turns.length && turns.every(t => t.completed),
    usage: turns.flatMap(t => t.usage), metrics, turns, grade: turns.at(-1)?.grade };
  await fs.writeFile(path.join(run, 'response.md'), responses.join('\n\n'));
  await fs.writeFile(path.join(run, 'mcp.jsonl'), allTraces.join('\n'));
  await fs.writeFile(path.join(run, 'result.json'), `${JSON.stringify(result, null, 2)}\n`);
  return result;
}

export function markdownReport(manifest, results) {
  const lines = ['# Local Codex playtests', '', `Model: ${manifest.model || 'not run'}; reasoning: ${manifest.reasoning || 'Codex default'}; Codex: ${manifest.codex_version || 'not run'}.`, '',
    'State outcomes are checked independently against SQLite. Narration quality and unnecessary player questions require reviewing response.md and the traces.', '',
    '| Revision | Fixture engine | Scenario | Seed | Steps | Outcome | Calls | Errors | Manuals | Seconds |', '|---|---|---|---:|---:|---|---:|---:|---:|---:|'];
  for (const r of results) lines.push(`| ${r.revision} | ${r.fixture_revision || r.revision} | ${r.scenario} | ${r.seed} | ${r.turns?.length ?? 1} | ${r.status} | ${r.metrics?.tool_calls ?? '-'} | ${r.metrics?.tool_errors ?? '-'} | ${r.metrics?.instructions_calls ?? '-'} | ${r.seconds?.toFixed(1) ?? '-'} |`);
  lines.push('', '## Tokens and tool catalog', '', 'Codex-reported input totals include repeated context across requests; cached input is part of that input total. Catalog bytes describe the server response, not an exact model-context token count.', '',
    '| Revision | Scenario | Seed | Input tokens | Cached input | Output tokens | Exposed tools | Catalog bytes |', '|---|---|---:|---:|---:|---:|---:|---:|');
  for (const r of results) {
    const usage = r.usage?.reduce((sum, u) => ({ input: sum.input + (u?.input_tokens || 0), cached: sum.cached + (u?.cached_input_tokens || 0), output: sum.output + (u?.output_tokens || 0) }), { input: 0, cached: 0, output: 0 });
    lines.push(`| ${r.revision} | ${r.scenario} | ${r.seed} | ${usage?.input ?? '-'} | ${usage?.cached ?? '-'} | ${usage?.output ?? '-'} | ${r.metrics?.exposed_tools ?? '-'} | ${r.metrics?.catalog_bytes ?? '-'} |`);
  }
  lines.push('', '## Paired comparisons', '', 'Pairs use the same scenario and seed. Differences in state outcome, calls and errors are observations, not statistical estimates.');
  const refs = manifest.revisions.map(r => r.ref);
  if (refs.length === 2) {
    lines.push('', `Deltas are ${refs[1]} minus ${refs[0]}; negative call/error deltas mean fewer calls/errors.`, '', '| Scenario | Seed | State outcomes | Calls delta | Errors delta |', '|---|---:|---|---:|---:|');
    for (const a of results.filter(r => r.revision === refs[0])) {
      const b = results.find(r => r.revision === refs[1] && r.scenario === a.scenario && r.seed === a.seed);
      const sameStart = !a.starting_state_sha256 || !b?.starting_state_sha256 || a.starting_state_sha256 === b.starting_state_sha256;
      const valid = b && sameStart && [a, b].every(r => ['passed', 'failed'].includes(r.status));
      lines.push(`| ${a.scenario} | ${a.seed} | ${a.status} / ${b?.status ?? 'missing'} | ${valid ? b.metrics.tool_calls - a.metrics.tool_calls : 'unavailable'} | ${valid ? b.metrics.tool_errors - a.metrics.tool_errors : 'unavailable'} |`);
    }
  }
  lines.push('', '## Failed checks', '');
  const failures = results.filter(r => !['passed', 'prepared'].includes(r.status));
  if (!failures.length) lines.push('None.');
  for (const r of failures) {
    if (r.error) lines.push(`- ${r.revision}, ${r.scenario}, seed ${r.seed}: ${r.error}`);
    for (const t of r.turns || []) if (t.status !== 'passed') lines.push(`- ${r.revision}, ${r.scenario}, seed ${r.seed}, step ${t.step}: ${t.status}; ${Object.keys(t.grade.checks).filter(k => !t.grade.checks[k]).join(', ')}. ${(t.violations || []).join('; ')}`);
  }
  lines.push('', '## Interpretation', '', '- Separate runner/authentication/time-limit failures from gameplay failures.', '- Check narration for fabricated state, unnecessary questions, and companion agency. Internal reference leaks are also checked automatically.', '- Fixed seeds align starting fixtures and dice streams; different tool choices can still consume different rolls. Paired deltas are unavailable when starting-state hashes differ.', '- Multi-step cases retain the same campaign while restarting Codex and the MCP server at each step.', '- This controlled run excludes personal plugins, source browsing and unrelated maintenance. Local MCP mutations are preapproved; approval-dialog behavior is not evaluated.', '- Promote reproducible engine defects into ordinary deterministic tests.');
  return `${lines.join('\n')}\n`;
}

export async function main(args = process.argv.slice(2)) {
  const options = parseArgs(args);
  if (options.help) { process.stdout.write(help); return; }
  if (options.list) { for (const name of Object.keys(scenarios)) { const s = scenarioDetails(name); process.stdout.write(`${name} (${s.turns.length} steps${s.upgrade ? ', upgrade fixture' : ''})\n  ${s.turns.join('\n  ')}\n`); } return; }
  if (options.report) {
    const root = path.resolve(options.report);
    const manifest = JSON.parse(await fs.readFile(path.join(root, 'manifest.json'), 'utf8'));
    const results = JSON.parse(await fs.readFile(path.join(root, 'results.json'), 'utf8'));
    await fs.writeFile(path.join(root, 'report.md'), markdownReport(manifest, results));
    process.stdout.write(`Report: ${path.join(root, 'report.md')}\n`);
    return { root, results };
  }
  const java = executable('java');
  if (!java) throw new Error('JDK 25+ is required.');
  const codex = options.prepareOnly ? null : await codexExecutable(options.codex);
  if (codex) {
    const login = await execute(codex, ['login', 'status']);
    if (login.code !== 0) throw new Error('Codex is not signed in. Run codex login before playtesting.');
    const cliHelp = await checked(codex, ['exec', '--help']);
    if (!cliHelp.includes('--ignore-user-config') || !cliHelp.includes('--ephemeral')) throw new Error('Update Codex CLI: --ignore-user-config and --ephemeral are required for isolated runs.');
  }
  const root = path.join(repository, 'target/codex-playtests', new Date().toISOString().replace(/[:.]/g, '-') + `-${process.pid}`);
  await fs.mkdir(root, { recursive: true });
  options.harnessDir = path.join(root, 'harness');
  await fs.cp(directory, options.harnessDir, { recursive: true });
  const revisions = [];
  for (const ref of options.refs) revisions.push(await buildRevision(ref, root, options));
  const fixtureRef = options.upgradeFrom || (options.scenarios.some(s => scenarioDetails(s).upgrade) ? 'master' : null);
  const fixtureRevision = fixtureRef ? revisions.find(r => r.ref === fixtureRef) || await buildRevision(fixtureRef, root, options) : null;
  const manifest = { created_at: new Date().toISOString(), model: options.model, reasoning: options.reasoning, codex_version: codex ? await checked(codex, ['--version']) : null,
    node_version: process.version, java_version: (await execute(java, ['-version'])).stderr.trim(), options: { ...options, codex: undefined }, revisions,
    fixture_revision: fixtureRevision, fixtures: 'Upgrade fixtures are created by the older engine and opened by each selected revision; steps restart Codex and the MCP server over the same database.' };
  manifest.harness_sha256 = {};
  for (const name of ['Fixture.java', 'LegacyFixture.java', 'scenarios.mjs', 'run.mjs', 'mcp-proxy.mjs'])
    manifest.harness_sha256[name] = createHash('sha256').update(await fs.readFile(path.join(options.harnessDir, name))).digest('hex');
  for (const revision of revisions) revision.jar_sha256 = createHash('sha256').update(await fs.readFile(revision.jar)).digest('hex');
  await fs.writeFile(path.join(root, 'manifest.json'), `${JSON.stringify(manifest, null, 2)}\n`);
  const results = [];
  const save = async () => {
    await fs.writeFile(path.join(root, 'results.json'), `${JSON.stringify(results, null, 2)}\n`);
    await fs.writeFile(path.join(root, 'report.md'), markdownReport(manifest, results));
  };
  for (const scenario of options.scenarios) for (let repetition = 1; repetition <= options.repetitions; repetition++) {
    // Alternate revision order between repetitions to reduce order/time confounding.
    const order = repetition % 2 ? revisions : [...revisions].reverse();
    for (const revision of order) {
      const origin = options.upgradeFrom || scenarioDetails(scenario).upgrade ? fixtureRevision : revision;
      try { results.push(await runCase(revision, origin, scenario, repetition, root, options, codex, java)); }
      catch (error) { results.push({ revision: revision.ref, fixture_revision: origin.ref, fixture_sha: origin.sha, scenario, repetition, seed: options.seed + repetition - 1, status: 'runner_error', error: error.message }); process.stderr.write(`${error.message}\n`); }
      await save();
      if (interrupted || results.at(-1).status === 'interrupted') {
        process.exitCode = 130;
        process.stdout.write(`Interrupted. Report: ${path.join(root, 'report.md')}\n`);
        return { root, results };
      }
    }
  }
  process.stdout.write(`Report: ${path.join(root, 'report.md')}\n`);
  // Local advisory suite: nonzero signals failed outcomes or infrastructure failures to scripts.
  if (results.some(r => !['passed', 'prepared'].includes(r.status))) process.exitCode = 1;
  return { root, results };
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  main().catch(error => { process.stderr.write(`${error.message}\n`); process.exitCode = interrupted ? 130 : 1; });
}
