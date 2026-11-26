// Copyright (C) 2026 Marcus Hirt. SPDX-License-Identifier: BSD-3-Clause
import test from 'node:test';
import assert from 'node:assert/strict';
import { parseArgs, commandSpec, codexArgs, metricsFromTrace, markdownReport, aggregateMetrics, auditTrace, startingStateHash } from './run.mjs';
import { scenarioDetails, promptFor } from './scenarios.mjs';

test('live runs require a pinned model; preparation/listing never needs authentication', () => {
  assert.throws(() => parseArgs([]), /--model/);
  assert.equal(parseArgs(['--prepare-only']).prepareOnly, true);
  assert.equal(parseArgs(['--list']).list, true);
  assert.equal(parseArgs(['--report', '/previous/run']).report, '/previous/run');
  assert.equal(parseArgs(['--prepare-only', '--upgrade-from', 'master']).upgradeFrom, 'master');
  assert.deepEqual(parseArgs(['--model', 'example', '--ref', 'master', '--ref', 'working']).refs, ['master', 'working']);
  for (const args of [['--model'], ['--model', 'x', '--scenario', 'typo'], ['--repetitions', '0'], ['--timeout-seconds', 'NaN'], ['--unknown']]) {
    assert.throws(() => parseArgs(args));
  }
});

test('upgrade flows use distinct player requests without leaking later steps or grading expectations', () => {
  assert.equal(scenarioDetails('session-recovery').turns.length, 3);
  assert.equal(scenarioDetails('session-recovery').upgrade, true);
  assert.match(promptFor('session-recovery', 1), /End this session/);
  assert.doesNotMatch(promptFor('session-recovery', 1), /Remind me what Richard promised|grade\.json|rpg\.db/);
  assert.throws(() => promptFor('session-recovery', 3), /Invalid step/);
});

test('old hints are checked against the actual selected server catalog, without scanning story prose', () => {
  const trace = tools => [
    { direction: 'response', message: { result: { tools: tools.map(name => ({ name })) } } },
    { direction: 'response', message: { result: { content: [{ type: 'text', text: JSON.stringify({ result: {
      record: { loot: { tool: 'grant_loot' } }, summary: 'grant_loot', operation: 'grant_loot', tool: "Thieves' Tools",
    } }) }] } } },
  ].map(e => JSON.stringify(e)).join('\n');
  assert.deepEqual(auditTrace(trace(['get_instructions', 'inventory'])), ['Unavailable tool hint: grant_loot']);
  assert.deepEqual(auditTrace(trace(['get_instructions', 'grant_loot'])), []);
});

test('separate sessions cannot accidentally resolve each other\'s reused JSON-RPC request IDs', () => {
  const entries = messages => messages.map(message => JSON.stringify(message)).join('\n');
  const a = metricsFromTrace(entries([{ direction: 'request', at: '2026-01-01T00:00:00Z', message: { id: 1, method: 'tools/call', params: { name: 'get_instructions' } } }]));
  const b = metricsFromTrace(entries([
    { direction: 'request', at: '2026-01-01T00:00:00Z', message: { id: 1, method: 'tools/call', params: { name: 'get_instructions' } } },
    { direction: 'response', at: '2026-01-01T00:00:01Z', message: { id: 1, result: { content: [] } } },
  ]));
  const result = aggregateMetrics([{ step: 1, metrics: a }, { step: 2, metrics: b }]);
  assert.equal(result.tool_calls, 2);
  assert.equal(result.pending_calls, 1);
  assert.equal(result.calls[0].step, 2);
});

test('different starting states prevent misleading efficiency comparisons', () => {
  const report = markdownReport({ revisions: [{ ref: 'master' }, { ref: 'working' }] }, [
    { revision: 'master', scenario: 'upgrade-recall', seed: 42, status: 'passed', starting_state_sha256: 'a', metrics: { tool_calls: 5, tool_errors: 0 } },
    { revision: 'working', scenario: 'upgrade-recall', seed: 42, status: 'passed', starting_state_sha256: 'b', metrics: { tool_calls: 2, tool_errors: 0 } },
  ]);
  assert.match(report, /passed \/ passed \| unavailable \| unavailable/);
});

test('starting-state comparison ignores JSON object order while preserving values and array order', () => {
  const state = record => JSON.stringify({ encounter_records: [{ record_json: record }], hp: 7 });
  const original = state('{"xp":12,"loot":{"tool":"grant_loot","automatic":false},"turns":[1,2]}');
  const reordered = JSON.stringify({ hp: 7, encounter_records: [{ record_json: '{"turns":[1,2],"loot":{"automatic":false,"tool":"grant_loot"},"xp":12}' }] });
  assert.equal(startingStateHash(original), startingStateHash(reordered));
  assert.notEqual(startingStateHash(original), startingStateHash(original.replace('"hp":7', '"hp":6')));
  assert.notEqual(startingStateHash(original), startingStateHash(state('{"xp":12,"loot":{"tool":"grant_loot","automatic":true},"turns":[1,2]}')));
  assert.notEqual(startingStateHash(original), startingStateHash(state('{"xp":12,"loot":{"tool":"grant_loot","automatic":false},"turns":[2,1]}')));
});

test('Windows Maven batch launchers are quoted and reject shell syntax', () => {
  const spec = commandSpec('C:\\Program Files\\Maven\\mvn.cmd', ['-B', 'package', '-DskipTests'], 'win32');
  assert.match(spec.args.at(-1), /"C:\\Program Files\\Maven\\mvn.cmd"/);
  assert.equal(spec.windowsVerbatimArguments, true);
  assert.throws(() => commandSpec('mvn.cmd', ['safe&unsafe'], 'win32'), /Unsafe/);
  assert.deepEqual(commandSpec('java', ['-jar', 'a b.jar'], 'linux'), { command: 'java', args: ['-jar', 'a b.jar'] });
});

test('Codex uses only explicitly supplied server config and preserves authentication home', () => {
  const args = codexArgs({ workspace: '/scratch/work', proxy: '/proxy.mjs', java: 'java', jar: '/server.jar', dataDir: '/scratch/campaign', trace: '/scratch/mcp.jsonl', seed: 42, maxToolCalls: 20, model: 'example', response: '/scratch/response.md' });
  assert.ok(args.includes('--ignore-user-config'));
  assert.ok(args.includes('--ephemeral'));
  assert.ok(args.includes('features.shell_tool=false'));
  assert.ok(args.includes('mcp_servers.rpg.required=true'));
  assert.ok(args.includes('read-only'));
  assert.ok(!args.some(s => /CODEX_HOME|auth\.json|dangerously|--trust/.test(s)));
  assert.ok(args.some(s => s.startsWith('mcp_servers.rpg.args=') && s.includes('/scratch/campaign')));
});

test('metrics correlate JSON-RPC IDs, structured tool errors, pages, and unfinished calls', () => {
  const line = (direction, message, second = 0) => JSON.stringify({ at: `2026-01-01T00:00:0${second}.000Z`, direction, message });
  const trace = [
    line('response', { id: 1, result: { tools: [{ name: 'session' }] } }),
    line('request', { id: 2, method: 'tools/call', params: { name: 'session', arguments: { action: 'STATE' } } }),
    line('response', { id: 2, result: { content: [{ type: 'text', text: '{"result":{}}' }] } }, 1),
    line('request', { id: 3, method: 'tools/call', params: { name: 'get_instructions', arguments: {} } }),
    line('response', { id: 3, result: { isError: true, content: [{ type: 'text', text: '{"error":{"code":"INVALID_ARGUMENT"}}' }] } }, 2),
    line('response', { id: 4, result: { tools: [{ name: 'session' }, { name: 'get_instructions' }] } }),
    line('request', { id: 5, method: 'tools/call', params: { name: 'session', arguments: {} } }),
  ].join('\n');
  const metrics = metricsFromTrace(trace);
  assert.equal(metrics.tool_calls, 3);
  assert.equal(metrics.tool_errors, 1);
  assert.equal(metrics.invalid_arguments, 1);
  assert.equal(metrics.instructions_calls, 1);
  assert.equal(metrics.pending_calls, 1);
  assert.equal(metrics.first_call_valid, true);
  assert.equal(metrics.exposed_tools, 2);
  assert.equal(metrics.calls[0].milliseconds, 1000);
});

test('a provider or timeout failure cannot produce a misleading paired improvement', () => {
  const report = markdownReport({ revisions: [{ ref: 'master' }, { ref: 'working' }] }, [
    { revision: 'master', scenario: 'short-rest', seed: 42, status: 'passed', metrics: { tool_calls: 5, tool_errors: 0 } },
    { revision: 'working', scenario: 'short-rest', seed: 42, status: 'timeout', metrics: { tool_calls: 1, tool_errors: 0 } },
  ]);
  assert.match(report, /passed \/ timeout \| unavailable \| unavailable/);
});

test('usage distinguishes cached context and totals repeated requests', () => {
  const report = markdownReport({ revisions: [{ ref: 'working' }] }, [
    { revision: 'working', scenario: 'short-rest', seed: 42, status: 'passed', usage: [
      { input_tokens: 100, cached_input_tokens: 60, output_tokens: 10 },
      { input_tokens: 200, cached_input_tokens: 180, output_tokens: 5 },
    ], metrics: { exposed_tools: 22, catalog_bytes: 1234 } },
  ]);
  assert.match(report, /\| 300 \| 240 \| 15 \| 22 \| 1234 \|/);
});
