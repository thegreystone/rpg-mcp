// Copyright (C) 2026 Marcus Hirt. SPDX-License-Identifier: BSD-3-Clause
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { EventEmitter } from 'node:events';
import * as fs from 'node:fs/promises';
import path from 'node:path';
import { PassThrough, Readable } from 'node:stream';
import test from 'node:test';
import {
  RELEASE_VERSION, RELEASE_ARTIFACT_PREFIX, detectHostArchitecture, ensureBinary, main, parseArgs, platformAsset,
  readConfig, resolveConfiguration, resolvePaths, saveConfig,
} from '../scripts/rpg-mcp.mjs';

const testRoot = path.resolve('target/codex-plugin-tests');
const platforms = [
  ['linux', 'x64', 'linux-x86_64'],
  ['linux', 'arm64', 'linux-aarch64'],
  ['darwin', 'arm64', 'macos-aarch64'],
  ['win32', 'x64', 'windows-x86_64.exe'],
];

async function temporary(t) {
  await fs.mkdir(testRoot, { recursive: true });
  const directory = await fs.mkdtemp(path.join(testRoot, 'case-'));
  t.after(async () => {
    const resolved = await fs.realpath(directory);
    const root = await fs.realpath(testRoot);
    assert.equal(path.dirname(resolved), root, 'Only remove this test-created directory');
    assert.ok(path.basename(resolved).startsWith('case-'));
    await fs.rm(resolved, { recursive: true, force: true });
  });
  return directory;
}

function output() {
  let text = '';
  return { write(value) { text += value; }, get text() { return text; } };
}

function transport(route, requests = []) {
  return (url, options, callback) => {
    const request = new EventEmitter();
    request.setTimeout = () => request;
    request.destroy = error => { queueMicrotask(() => request.emit('error', error)); return request; };
    request.end = () => queueMicrotask(() => {
      try {
        if (options.signal?.aborted) return request.destroy(options.signal.reason);
        requests.push(url.href);
        const reply = route(url);
        const body = Buffer.isBuffer(reply.body) ? reply.body : Buffer.from(reply.body || '');
        const response = Readable.from(body.length ? [body] : []);
        response.statusCode = reply.status || 200;
        response.headers = reply.headers || {};
        callback(response);
      } catch (error) { request.destroy(error); }
    });
    return request;
  };
}

function releaseTransport(asset, body, { digest, redirect, downloadBody = body } = {}) {
  const requests = [];
  const expectedDigest = digest === undefined ? `sha256:${createHash('sha256').update(body).digest('hex')}` : digest;
  const requestImpl = transport(url => {
    if (url.hostname === 'api.github.com') {
      assert.equal(url.pathname, `/repos/thegreystone/rpg-mcp/releases/tags/v${RELEASE_VERSION}`);
      return { body: JSON.stringify({ assets: [{ name: asset, size: body.length, digest: expectedDigest }] }) };
    }
    if (url.hostname === 'github.com') {
      assert.equal(url.pathname, `/thegreystone/rpg-mcp/releases/download/v${RELEASE_VERSION}/${asset}`);
      if (redirect) return { status: 302, headers: { location: redirect } };
      return { body: downloadBody };
    }
    assert.equal(url.hostname, 'release-assets.githubusercontent.com');
    return { body: downloadBody };
  }, requests);
  return { requestImpl, requests };
}

test('selects the exact native release for every supported OS and architecture', () => {
  for (const [platform, arch, suffix] of platforms) {
    assert.equal(platformAsset(platform, arch), `${RELEASE_ARTIFACT_PREFIX}-${RELEASE_VERSION}-${suffix}`);
  }
  for (const [platform, arch] of [['darwin', 'x64'], ['win32', 'arm64'], ['linux', 'ia32'], ['freebsd', 'x64']]) {
    assert.throws(() => platformAsset(platform, arch), error =>
      error.message.includes(`${platform}/${arch}`) && error.message.includes('RPG_MCP_BINARY'));
  }
});

test('detects the native host independently of the Node process architecture', () => {
  for (const [machine, expected] of [['x86_64', 'x64'], ['AMD64', 'x64'], ['aarch64', 'arm64'], ['ARM64', 'arm64']]) {
    assert.equal(detectHostArchitecture({ platform: 'linux', machine, processArch: 'ia32', env: {} }), expected);
  }
  assert.equal(detectHostArchitecture({ platform: 'linux', machine: () => undefined, processArch: 'x64', env: {} }), 'x64');
  assert.equal(detectHostArchitecture({ platform: 'linux', machine() { throw new Error('Machine unavailable'); }, processArch: 'arm64', env: {} }), 'arm64');
  assert.equal(detectHostArchitecture({ platform: 'win32', machine: 'AMD64', processArch: 'ia32', env: { PROCESSOR_ARCHITECTURE: 'x86', PROCESSOR_ARCHITEW6432: 'AMD64' } }), 'x64');
  assert.equal(detectHostArchitecture({ platform: 'win32', machine() { throw new Error('Machine unavailable'); }, processArch: 'ia32', env: { PROCESSOR_ARCHITECTURE: 'x86', PROCESSOR_ARCHITEW6432: 'AMD64' } }), 'x64');
  assert.equal(detectHostArchitecture({ platform: 'win32', machine: 'AMD64', processArch: 'x64', env: { PROCESSOR_ARCHITEW6432: 'ARM64' } }), 'arm64');
  assert.equal(detectHostArchitecture({ platform: 'win32', machine: 'AMD64', processArch: 'x64', env: { PROCESSOR_ARCHITECTURE: 'ARM64' } }), 'arm64');
  for (const affirmativeKey of ['sysctl.proc_translated', 'hw.optional.arm64', undefined]) {
    const queries = [];
    const arch = detectHostArchitecture({ platform: 'darwin', machine: 'x86_64', processArch: 'x64', env: {}, execFileSyncImpl(command, args, options) {
      assert.equal(command, '/usr/sbin/sysctl');
      assert.equal(args[0], '-in');
      assert.equal(options.timeout, 1000);
      queries.push(args[1]);
      return args[1] === affirmativeKey ? '1\n' : '0\n';
    } });
    assert.equal(arch, affirmativeKey ? 'arm64' : 'x64');
    assert.equal(queries.length, affirmativeKey === 'sysctl.proc_translated' ? 1 : 2);
  }
  assert.equal(detectHostArchitecture({ platform: 'darwin', machine: 'x86_64', env: {}, execFileSyncImpl() { throw new Error('No such sysctl'); } }), 'x64');
});

test('downloads the selected asset and keeps distinct platform caches', async t => {
  const cacheDir = await temporary(t);
  const filenames = new Set();
  for (const [platform, arch] of platforms) {
    const asset = platformAsset(platform, arch);
    const body = Buffer.from(`native test fixture: ${asset}`);
    const network = releaseTransport(asset, body);
    const filename = await ensureBinary({ cacheDir, platform, arch, requestImpl: network.requestImpl });
    assert.equal(path.basename(filename), asset);
    assert.equal(path.basename(path.dirname(filename)), RELEASE_VERSION);
    assert.deepEqual(await fs.readFile(filename), body);
    assert.equal(network.requests.length, 2);
    filenames.add(filename);
  }
  assert.equal(filenames.size, platforms.length);
});

test('reuses a verified binary offline and replaces a corrupt cache entry', async t => {
  const cacheDir = await temporary(t);
  const asset = platformAsset('linux', 'x64');
  const body = Buffer.from('verified native fixture');
  const network = releaseTransport(asset, body);
  const options = { cacheDir, platform: 'linux', arch: 'x64' };
  const filename = await ensureBinary({ ...options, requestImpl: network.requestImpl });
  assert.equal(await ensureBinary({ ...options, requestImpl() { throw new Error('Offline: network must not be needed'); } }), filename);
  await fs.writeFile(filename, Buffer.alloc(body.length, 120));
  await ensureBinary({ ...options, requestImpl: network.requestImpl });
  assert.deepEqual(await fs.readFile(filename), body);
  assert.equal(network.requests.length, 4);
});

test('rejects wrong hashes and incomplete downloads without publishing a binary', async t => {
  const cacheDir = await temporary(t);
  const asset = platformAsset('win32', 'x64');
  const body = Buffer.from('complete fixture');
  const options = { cacheDir, platform: 'win32', arch: 'x64' };
  const badHash = releaseTransport(asset, body, { digest: `sha256:${'0'.repeat(64)}` });
  await assert.rejects(ensureBinary({ ...options, requestImpl: badHash.requestImpl }), /SHA-256 verification failed/);
  assert.deepEqual(await fs.readdir(path.join(cacheDir, RELEASE_VERSION)), []);
  const shortDownload = releaseTransport(asset, body, { downloadBody: body.subarray(0, 3) });
  await assert.rejects(ensureBinary({ ...options, requestImpl: shortDownload.requestImpl }), /incomplete/);
  assert.deepEqual(await fs.readdir(path.join(cacheDir, RELEASE_VERSION)), []);
});

test('a missing release asset never falls back to another architecture', async t => {
  const cacheDir = await temporary(t);
  const requested = platformAsset('win32', 'x64');
  const requests = [];
  const requestImpl = transport(url => {
    assert.equal(url.hostname, 'api.github.com', 'Do not download an incompatible release asset');
    return { body: JSON.stringify({ assets: [{ name: platformAsset('linux', 'arm64'), size: 123 }] }) };
  }, requests);
  await assert.rejects(ensureBinary({ cacheDir, platform: 'win32', arch: 'x64', requestImpl }), error =>
    error.message.includes(requested) && error.message.includes('Retry after the release finishes building'));
  assert.equal(requests.length, 1);
  assert.deepEqual(await fs.readdir(path.join(cacheDir, RELEASE_VERSION)), []);
});

test('an unpublished release reports a retryable error without leaving installation files', async t => {
  const cacheDir = await temporary(t);
  const requests = [];
  const requestImpl = transport(() => ({ status: 404 }), requests);
  await assert.rejects(ensureBinary({ cacheDir, platform: 'linux', arch: 'x64', requestImpl }), /HTTP 404.*may still be building/);
  assert.equal(requests.length, 1);
  assert.deepEqual(await fs.readdir(path.join(cacheDir, RELEASE_VERSION)), []);
});

test('cancelling before installation avoids network and cache writes', async t => {
  const directory = await temporary(t);
  const cacheDir = path.join(directory, 'not-created');
  const controller = new AbortController();
  controller.abort(new Error('Cancelled before download'));
  await assert.rejects(ensureBinary({
    cacheDir, platform: 'linux', arch: 'x64', signal: controller.signal,
    requestImpl() { assert.fail('A cancelled installation must not access the network'); },
  }), /Cancelled before download/);
  await assert.rejects(fs.stat(cacheDir), { code: 'ENOENT' });
});

test('cancelling a download removes partial files and releases its installation lock', { timeout: 5000 }, async t => {
  const cacheDir = await temporary(t);
  const asset = platformAsset('linux', 'x64');
  const body = Buffer.from('native executable fixture for an interrupted download');
  const controller = new AbortController();
  const requests = [];
  let downloadStarted;
  const started = new Promise(resolve => { downloadStarted = resolve; });
  const normal = releaseTransport(asset, body);
  const requestImpl = (url, options, callback) => {
    if (url.hostname === 'api.github.com') return normal.requestImpl(url, options, callback);
    requests.push(url.href);
    const request = new EventEmitter();
    request.setTimeout = () => request;
    request.end = () => queueMicrotask(() => {
      const response = new PassThrough();
      response.statusCode = 200;
      response.headers = {};
      callback(response);
      response.write(body.subarray(0, 8));
      downloadStarted();
    });
    return request;
  };
  const installation = ensureBinary({ cacheDir, platform: 'linux', arch: 'x64', signal: controller.signal, requestImpl });
  // Observe rejection before aborting so no asynchronous rejection is left unhandled.
  const rejected = assert.rejects(installation, /aborted|cancelled/i);
  await started;
  let partialWritten = false;
  try {
    for (let attempt = 0; attempt < 100 && !partialWritten; attempt++) {
      const directory = path.join(cacheDir, RELEASE_VERSION);
      const partial = (await fs.readdir(directory)).find(name => name.endsWith('.partial'));
      partialWritten = Boolean(partial && (await fs.stat(path.join(directory, partial))).size > 0);
      if (!partialWritten) await new Promise(resolve => setTimeout(resolve, 5));
    }
  } finally {
    controller.abort(new Error('Cancelled test download'));
  }
  await rejected;
  assert.ok(partialWritten, 'Cancel after bytes have reached a partial executable on disk');
  assert.equal(requests.length, 1);
  assert.deepEqual(await fs.readdir(path.join(cacheDir, RELEASE_VERSION)), []);
  const retry = releaseTransport(asset, body);
  const filename = await ensureBinary({ cacheDir, platform: 'linux', arch: 'x64', requestImpl: retry.requestImpl });
  assert.deepEqual(await fs.readFile(filename), body);
  assert.equal(retry.requests.length, 2, 'A retry can acquire the released lock and finish');
});

test('allows official release redirects and rejects HTTPS downgrades or unrelated hosts', async t => {
  const cacheDir = await temporary(t);
  const asset = platformAsset('linux', 'arm64');
  const body = Buffer.from('redirected fixture');
  const valid = releaseTransport(asset, body, { redirect: 'https://release-assets.githubusercontent.com/asset' });
  await ensureBinary({ cacheDir, platform: 'linux', arch: 'arm64', requestImpl: valid.requestImpl });
  assert.equal(valid.requests.length, 3);
  for (const [index, redirect] of ['http://github.com/asset', 'https://unrelated.example/asset'].entries()) {
    const invalid = releaseTransport(asset, body, { redirect });
    await assert.rejects(ensureBinary({ cacheDir: path.join(cacheDir, `invalid-${index}`), platform: 'linux', arch: 'arm64', requestImpl: invalid.requestImpl }), /official GitHub HTTPS/);
    assert.equal(invalid.requests.length, 2);
  }
});

test('concurrent installations share one complete download', async t => {
  const cacheDir = await temporary(t);
  const asset = platformAsset('linux', 'x64');
  const body = Buffer.from('concurrent fixture');
  const network = releaseTransport(asset, body);
  const options = { cacheDir, platform: 'linux', arch: 'x64', requestImpl: network.requestImpl };
  const [first, second] = await Promise.all([ensureBinary(options), ensureBinary(options)]);
  assert.equal(first, second);
  assert.deepEqual(await fs.readFile(first), body);
  assert.equal(network.requests.length, 2);
  assert.equal((await fs.readdir(path.dirname(first))).some(name => /\.partial$|\.lock$/.test(name)), false);
});

test('default config/cache paths belong to each platform and honor directory overrides', () => {
  const homeDir = path.resolve('target/test-home');
  const windows = resolvePaths({ platform: 'win32', homeDir, env: {} });
  assert.equal(windows.configPath, path.join(homeDir, 'AppData', 'Local', 'rpg-mcp', 'codex', 'config.json'));
  assert.equal(windows.cacheDir, path.join(homeDir, 'AppData', 'Local', 'rpg-mcp', 'cache'));
  const mac = resolvePaths({ platform: 'darwin', homeDir, env: {} });
  assert.equal(mac.configPath, path.join(homeDir, 'Library', 'Application Support', 'rpg-mcp', 'codex', 'config.json'));
  assert.equal(mac.cacheDir, path.join(homeDir, 'Library', 'Caches', 'rpg-mcp'));
  const linux = resolvePaths({ platform: 'linux', homeDir, env: {
    XDG_CONFIG_HOME: path.join(homeDir, 'config'), XDG_CACHE_HOME: path.join(homeDir, 'cache'),
  } });
  assert.equal(linux.configPath, path.join(homeDir, 'config', 'rpg-mcp', 'codex', 'config.json'));
  assert.equal(linux.cacheDir, path.join(homeDir, 'cache', 'rpg-mcp'));
  assert.equal(resolvePaths({ env: { RPG_MCP_CODEX_CONFIG: '~/private.json' }, homeDir }).configPath,
    path.join(homeDir, 'private.json'));
});

test('the data directory defaults to ~/.rpg-mcp, persists outside the plugin, and yields to the environment', async t => {
  const homeDir = await temporary(t);
  const configPath = path.join(homeDir, 'config', 'rpg.json');
  const cacheDir = path.join(homeDir, 'native-cache');
  const env = { RPG_MCP_CODEX_CONFIG: configPath, RPG_MCP_CODEX_CACHE: cacheDir };
  const defaults = await resolveConfiguration({ env, homeDir });
  assert.equal(defaults.dataDir, path.join(homeDir, '.rpg-mcp'));
  assert.equal(defaults.binaryPath, undefined);
  assert.deepEqual(resolvePaths({ env, homeDir }), { configPath, cacheDir });
  await saveConfig(configPath, { dataDir: '~/campaigns', binaryPath: '~/saved-binary' });
  const saved = await resolveConfiguration({ env, homeDir });
  assert.equal(saved.dataDir, path.join(homeDir, 'campaigns'));
  assert.equal(saved.binaryPath, path.join(homeDir, 'saved-binary'));
  const overridden = await resolveConfiguration({ env: { ...env, RPG_DATA_DIR: path.join(homeDir, 'elsewhere'), RPG_MCP_BINARY: '~/override-binary' }, homeDir });
  assert.equal(overridden.dataDir, path.join(homeDir, 'elsewhere'));
  assert.equal(overridden.binaryPath, path.join(homeDir, 'override-binary'));
  assert.equal(overridden.cacheDir, cacheDir);
  assert.equal(overridden.configPath, configPath);
  await assert.rejects(saveConfig(configPath, { dataDir: '/x', apiKey: 'do-not-store' }), /Unknown RPG MCP configuration key/);
  await assert.rejects(saveConfig(configPath, { dataDir: '  ' }), /data directory must not be empty/);
  await assert.rejects(resolveConfiguration({ env: { ...env, RPG_DATA_DIR: '' }, homeDir }), /data directory must not be empty/);
  await fs.writeFile(configPath, '{broken json');
  await assert.rejects(readConfig(configPath), /Cannot read RPG MCP configuration/);
});

test('CLI rejects ambiguous options and configuration changes preserve earlier settings', async t => {
  assert.throws(() => parseArgs(['--install', '--configure']), /only one/);
  assert.throws(() => parseArgs(['--data-dir']), /value is required/);
  assert.throws(() => parseArgs(['--data-dir', '/campaigns']), /require --configure/);
  assert.throws(() => parseArgs(['--unsupported']), /Unknown option/);
  const directory = await temporary(t);
  const env = { RPG_MCP_CODEX_CONFIG: path.join(directory, 'config.json') };
  const stdout = output(), stderr = output();
  assert.equal(await main(['--configure', '--data-dir', path.join(directory, 'campaigns')], { env, homeDir: directory, stdout, stderr }), 0);
  assert.match(stderr.text, /Campaign data directory: .*campaigns/);
  assert.equal(await main(['--configure', '--binary', process.execPath], { env, homeDir: directory, stdout, stderr }), 0);
  const config = await readConfig(env.RPG_MCP_CODEX_CONFIG);
  assert.equal(config.dataDir, path.join(directory, 'campaigns'));
  assert.equal(config.binaryPath, process.execPath);
  assert.equal(stdout.text, '', 'Configuration diagnostics must not pollute MCP stdout');
  assert.equal(await main(['--show-config'], { env: { ...env, OPENAI_API_KEY: 'not-a-real-key' }, homeDir: directory, stdout, stderr }), 0);
  const shown = JSON.parse(stdout.text);
  assert.equal(shown.version, RELEASE_VERSION);
  assert.equal(shown.dataDir, path.join(directory, 'campaigns'));
  assert.equal(stdout.text.includes('not-a-real-key'), false);
});

test('configure without values saves an empty configuration and reports the default directory', async t => {
  const directory = await temporary(t);
  const env = { RPG_MCP_CODEX_CONFIG: path.join(directory, 'config.json') };
  const stdout = output(), stderr = output();
  assert.equal(await main(['--configure'], { env, homeDir: directory, stdout, stderr }), 0);
  assert.deepEqual(await readConfig(env.RPG_MCP_CODEX_CONFIG), {});
  assert.ok(stderr.text.includes(path.join(directory, '.rpg-mcp')));
  assert.equal(stdout.text, '');
});

test('install accepts an existing executable without starting a server', async t => {
  const directory = await temporary(t);
  const stdout = output(), stderr = output();
  assert.equal(await main(['--install'], { env: {
    RPG_MCP_CODEX_CONFIG: path.join(directory, 'absent.json'), RPG_MCP_BINARY: process.execPath,
  }, homeDir: directory, stdout, stderr, spawnImpl() { assert.fail('Install must not start a server'); } }), 0);
  assert.match(stderr.text, /executable ready/);
  assert.equal(stdout.text, '');
});

test('launch forwards MCP bytes, hands over the resolved data directory and shuts down the child on EOF', async t => {
  const directory = await temporary(t);
  const stdin = new PassThrough();
  const stdout = output(), stderr = output();
  const env = {
    RPG_MCP_CODEX_CONFIG: path.join(directory, 'absent.json'), RPG_MCP_BINARY: process.execPath,
    PATH: 'preserved-system-path',
  };
  let spawned, received = '', killed;
  let ready;
  const wasSpawned = new Promise(resolve => { ready = resolve; });
  const result = main([], { env, homeDir: directory, stdin, stdout, stderr, spawnImpl(command, args, options) {
    spawned = { command, args, options };
    const child = new EventEmitter();
    child.exitCode = null;
    child.signalCode = null;
    child.stdin = new PassThrough();
    child.stdin.on('data', bytes => { received += bytes; });
    child.kill = signal => {
      killed = signal;
      child.exitCode = 0;
      queueMicrotask(() => child.emit('exit', 0, null));
      return true;
    };
    ready();
    return child;
  } });
  await wasSpawned;
  const request = '{"jsonrpc":"2.0","id":1,"method":"initialize"}\n';
  stdin.write(request);
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(received, request);
  assert.equal(spawned.command, process.execPath);
  assert.deepEqual(spawned.args, ['-Dquarkus.mcp.server.stdio.enabled=true']);
  assert.equal(spawned.options.shell, false);
  assert.equal(spawned.options.windowsHide, true);
  assert.deepEqual(spawned.options.stdio, ['pipe', 'inherit', 'inherit']);
  assert.equal(spawned.options.env.RPG_DATA_DIR, path.join(directory, '.rpg-mcp'), 'The default data directory is made explicit for the server');
  assert.equal(spawned.options.env.PATH, 'preserved-system-path');
  stdin.end();
  assert.equal(await result, 0);
  assert.equal(killed, 'SIGTERM');
  assert.equal(stdout.text, '');
});

test('an explicit RPG_DATA_DIR reaches the server unchanged apart from home expansion', async t => {
  const directory = await temporary(t);
  const stdin = new PassThrough();
  const stdout = output(), stderr = output();
  const env = { RPG_MCP_CODEX_CONFIG: path.join(directory, 'absent.json'), RPG_MCP_BINARY: process.execPath, RPG_DATA_DIR: '~/campaigns' };
  let spawned;
  const result = main([], { env, homeDir: directory, stdin, stdout, stderr, spawnImpl(command, args, options) {
    spawned = { command, args, options };
    const child = new EventEmitter();
    child.exitCode = null;
    child.signalCode = null;
    child.stdin = new PassThrough();
    child.kill = () => { child.exitCode = 0; queueMicrotask(() => child.emit('exit', 0, null)); return true; };
    queueMicrotask(() => stdin.end());
    return child;
  } });
  assert.equal(await result, 0);
  assert.equal(spawned.options.env.RPG_DATA_DIR, path.join(directory, 'campaigns'));
});

test('closed client stdin cancels startup before a native child is spawned', async t => {
  const directory = await temporary(t);
  const stdin = new PassThrough();
  stdin.destroy();
  const stdout = output(), stderr = output();
  assert.equal(await main([], { env: {
    RPG_MCP_CODEX_CONFIG: path.join(directory, 'absent.json'), RPG_MCP_BINARY: process.execPath,
  }, homeDir: directory, stdin, stdout, stderr, spawnImpl() { assert.fail('Client already disconnected'); } }), 0);
  assert.equal(stdout.text, '');
  assert.equal(stderr.text, '');
});
