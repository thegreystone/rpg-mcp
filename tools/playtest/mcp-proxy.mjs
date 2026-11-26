#!/usr/bin/env node
// Copyright (C) 2026 Marcus Hirt. SPDX-License-Identifier: BSD-3-Clause
// Transparent stdio recorder. It never changes tool names, schemas, arguments or responses.
import { spawn } from 'node:child_process';
import { appendFileSync, writeFileSync } from 'node:fs';
import readline from 'node:readline';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

export function decodeLine(line) {
  try { return JSON.parse(line); } catch { return null; }
}

export function startProxy({ java, jar, dataDir, trace, seed, maxCalls }) {
  const record = (direction, message) => appendFileSync(trace, `${JSON.stringify({ at: new Date().toISOString(), direction, message })}\n`);
  const server = spawn(java, [`-Drpg.data-dir=${dataDir}`, `-Drpg.roll-seed=${seed}`, '-jar', jar], {
    stdio: ['pipe', 'pipe', 'pipe'], windowsHide: true,
  });
  let calls = 0;
  server.stderr.on('data', chunk => process.stderr.write(chunk));
  server.on('error', error => { record('proxy_error', { message: error.message }); process.stderr.write(`${error.message}\n`); process.exitCode = 1; });
  const forward = (input, output, direction) => {
    const lines = readline.createInterface({ input, crlfDelay: Infinity });
    lines.on('line', line => {
      const message = decodeLine(line);
      if (message) record(direction, message);
      if (direction === 'request' && message?.method === 'tools/call' && ++calls > maxCalls) {
        record('budget_exceeded', { maxCalls });
        // The runner watches this sentinel and terminates the whole Codex process tree.
        writeFileSync(`${trace}.limit`, 'Tool-call budget exceeded.\n');
        output.end();
        server.kill();
        return;
      }
      if (!output.destroyed && !output.writableEnded) output.write(`${line}\n`);
    });
    return lines;
  };
  const requests = forward(process.stdin, server.stdin, 'request');
  forward(server.stdout, process.stdout, 'response');
  process.stdin.on('end', () => server.stdin.end());
  server.stdin.on('error', () => {});
  server.on('close', code => { requests.close(); process.stdin.pause(); process.exitCode = code || 0; });
  for (const signal of ['SIGINT', 'SIGTERM']) process.on(signal, () => { server.kill(); process.exitCode = 1; });
  return server;
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const [java, jar, dataDir, trace, seed, maxCalls] = process.argv.slice(2);
  if (!java || !jar || !dataDir || !trace || !seed || !maxCalls) throw new Error('Proxy is launched by run.mjs, not directly.');
  startProxy({ java, jar, dataDir, trace, seed, maxCalls: Number(maxCalls) });
}
