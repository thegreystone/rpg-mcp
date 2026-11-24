// Copyright (C) 2026 Marcus Hirt. SPDX-License-Identifier: BSD-3-Clause
import assert from 'node:assert/strict';
import * as fs from 'node:fs/promises';
import path from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';

const pluginRoot = fileURLToPath(new URL('../', import.meta.url));
const readJson = async relative => JSON.parse(await fs.readFile(path.join(pluginRoot, relative), 'utf8'));

test('package selects the Codex compatibility manifest with explicit MCP registration', async () => {
  for (const portableFile of ['plugin.json', 'mcp.json']) {
    await assert.rejects(fs.access(path.join(pluginRoot, portableFile)), { code: 'ENOENT' });
  }

  const manifest = await readJson('.codex-plugin/plugin.json');
  assert.equal(manifest.name, 'rpg-mcp');
  assert.match(manifest.version, /^\d+\.\d+\.\d+$/);
  assert.equal(manifest.$schema, undefined);
  assert.equal(manifest.skills, './skills/');
  assert.equal(manifest.mcpServers, './.mcp.json');
  assert.equal(manifest.requires_local_executor, true);

  const configuration = await readJson(manifest.mcpServers);
  assert.deepEqual(Object.keys(configuration.mcpServers), ['rpg-mcp']);
  const server = configuration.mcpServers['rpg-mcp'];
  assert.equal(server.command, 'node');
  assert.deepEqual(server.args, ['${PLUGIN_ROOT}/scripts/rpg-mcp.mjs']);
  // Resolve the same plugin-root placeholder as Codex; no shell or home expansion is needed.
  const launcher = server.args[0].replace('${PLUGIN_ROOT}', pluginRoot);
  assert.ok((await fs.stat(launcher)).isFile());
});

test('compatibility discovery preserves onboarding, both skills and marketplace artwork', async () => {
  const manifest = await readJson('.codex-plugin/plugin.json');
  assert.equal(manifest.extensions['com.openai'].onboardingSkill, './skills/setup/SKILL.md');
  for (const skill of ['rpg', 'setup']) {
    const instructions = await fs.readFile(path.join(pluginRoot, manifest.skills, skill, 'SKILL.md'), 'utf8');
    assert.match(instructions, /^---\r?\nname: /);
  }

  assert.equal(manifest.interface.displayName, 'RPG MCP');
  for (const asset of [manifest.interface.composerIcon, manifest.interface.logo]) {
    assert.ok((await fs.stat(path.join(pluginRoot, asset))).isFile());
  }
});
