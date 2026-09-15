import { existsSync, mkdirSync, readdirSync, readFileSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';
import { ModManager } from '../src/core/mods/ModManager.js';
import { installProfile } from '../src/core/performance/PerformanceProfiles.js';
import { ProtectedModError } from '../src/core/snowball/protection.js';
import { ClientBuildRegistry, CoreRepairError, SnowballCore } from '../src/core/snowball/SnowballCore.js';
import { makeZip, tempDir } from './helpers.js';

const clientJar = (version = '1.1.0', minecraft = '~26.2') =>
  makeZip({ 'fabric.mod.json': JSON.stringify({ id: 'snowballclient', version, depends: { minecraft, 'fabric-api': '*' } }), 'dev/Client.class': `build ${version}` });
const modJar = (id: string) => makeZip({ 'fabric.mod.json': JSON.stringify({ id, version: '1.0.0' }) });

async function setup(fabricApi?: (gameDir: string) => void) {
  const builds = tempDir();
  writeFileSync(join(builds, 'snowball-client-1.1.0.jar'), clientJar());
  writeFileSync(join(builds, 'snowball-client-1.0.0.jar'), clientJar('1.0.0'));
  writeFileSync(join(builds, 'snowball-client-1.1.0-sources.jar'), makeZip({ 'fabric.mod.json': JSON.stringify({ id: 'snowballclient', version: '${version}', depends: { minecraft: '~26.2' } }) }));
  const registry = await ClientBuildRegistry.discover([builds, join(builds, 'not-there')]);
  const installs: string[] = [];
  const core = new SnowballCore(registry, {
    ensure: async (gameDir) => {
      installs.push(gameDir);
      (fabricApi ?? ((dir) => writeFileSync(join(dir, 'mods', 'fabric-api-0.160.0.jar'), modJar('fabric-api'))))(gameDir);
      return true;
    },
  });
  const gameDir = tempDir();
  const mods = join(gameDir, 'mods');
  mkdirSync(mods, { recursive: true });
  const messages: string[] = [];
  const report = (_level: string, message: string) => void messages.push(message);
  return { registry, core, mods, installs, messages, report, target: { gameDir, minecraftVersion: '26.2', loader: 'fabric' as const } };
}

describe('Snowball Client builds', () => {
  it('discovers real builds only and matches them to Minecraft versions and loaders', async () => {
    const { registry } = await setup();
    expect(registry.builds.map((b) => b.version)).toEqual(['1.1.0', '1.0.0']);
    expect(registry.find('26.2', 'fabric')?.version).toBe('1.1.0');
    expect(registry.find('26.2.1', 'fabric')?.label).toBe('26.2');
    expect(registry.find('1.21.11', 'fabric')).toBeNull();
    expect(registry.find('26.2', 'neoforge')).toBeNull();
  });
});

describe('Snowball core repair', () => {
  it('installs everything a new instance needs, then only verifies', async () => {
    const { core, mods, installs, messages, report, target } = await setup();
    await core.ensure(target, report);
    expect(messages).toEqual(['Checking core files...', 'Snowball Client is missing', 'Repairing Snowball Client...', 'Snowball Client restored', 'Installing Fabric API...', 'Fabric API installed', 'Core files verified']);
    expect(readdirSync(mods).sort()).toEqual(['fabric-api-0.160.0.jar', 'snowball-client.jar']);

    messages.length = 0;
    const second = await core.ensure(target, report);
    expect(messages).toEqual(['Checking core files...', 'Snowball Client 1.1.0 detected', 'Core files verified']);
    expect(second).toMatchObject({ supported: true, client: 'ok', fabricApi: 'ok', problems: [] });
    expect(installs).toHaveLength(1);
  });

  it('replaces a damaged or outdated client with the bundled build', async () => {
    const { core, registry, mods, messages, report, target } = await setup();
    writeFileSync(join(mods, 'fabric-api.jar'), modJar('fabric-api'));
    writeFileSync(join(mods, 'snowball-client.jar'), 'not a jar');
    expect((await core.inspect(target)).client).toBe('damaged');
    await core.ensure(target, report);
    expect(readFileSync(join(mods, 'snowball-client.jar'))).toEqual(readFileSync(registry.builds[0].path));

    writeFileSync(join(mods, 'snowball-client.jar'), clientJar('1.0.0'));
    expect((await core.inspect(target)).problems).toEqual(['Snowball Client 1.0.0 is out of date']);
    messages.length = 0;
    await core.ensure(target, report);
    expect(messages).toContain('Snowball Client updated to 1.1.0');
  });

  it('sets aside duplicate and turned-off copies instead of deleting them', async () => {
    const { core, mods, messages, report, target } = await setup();
    writeFileSync(join(mods, 'fabric-api.jar'), modJar('fabric-api'));
    writeFileSync(join(mods, 'snowball-client.jar.disabled'), clientJar());
    writeFileSync(join(mods, 'my-copy.jar'), clientJar('1.0.0'));
    const before = await core.inspect(target);
    expect(before.client).toBe('disabled');
    expect(before.problems).toEqual(['Snowball Client was turned off outside the launcher', '1 extra copy of Snowball Client found']);

    await core.ensure(target, report);
    expect(readdirSync(mods).sort()).toEqual(['fabric-api.jar', 'my-copy.jar.duplicate', 'snowball-client.jar', 'snowball-client.jar.duplicate']);
    expect(messages).toContain('Set aside 1 extra copy of Snowball Client');
    expect((await core.inspect(target)).client).toBe('ok');
  });

  it('turns Fabric API back on and explains when it cannot be installed', async () => {
    const turnedOff = await setup();
    writeFileSync(join(turnedOff.mods, 'snowball-client.jar'), clientJar());
    writeFileSync(join(turnedOff.mods, 'fabric-api.jar.disabled'), modJar('fabric-api'));
    await turnedOff.core.ensure(turnedOff.target, turnedOff.report);
    expect(existsSync(join(turnedOff.mods, 'fabric-api.jar'))).toBe(true);
    expect(turnedOff.installs).toHaveLength(0);

    const offline = await setup(() => {
      throw new Error('fetch failed');
    });
    await expect(offline.core.ensure(offline.target, offline.report)).rejects.toThrow(CoreRepairError);
    expect(offline.messages.at(-1)).toBe('Snowball Client needs Fabric API, which could not be installed: fetch failed');
  });

  it('sets aside a client copy in an instance whose version has no build', async () => {
    const { core, mods, messages, report, target } = await setup();
    writeFileSync(join(mods, 'snowball-client.jar'), clientJar());
    const result = await core.ensure({ ...target, minecraftVersion: '1.21.11' }, report);
    expect(result.supported).toBe(false);
    expect(readdirSync(mods)).toEqual(['snowball-client.jar.unsupported']);
    expect(messages).toEqual(["Snowball Client isn't available for Minecraft 1.21.11 yet, so it was set aside for this instance"]);
  });
});

describe('protected Snowball components', () => {
  it('refuses to remove or turn off Snowball Client and its Fabric API through mod management', async () => {
    const game = tempDir();
    const mods = join(game, 'mods');
    mkdirSync(mods, { recursive: true });
    writeFileSync(join(mods, 'snowball-client.jar'), clientJar());
    writeFileSync(join(mods, 'fabric-api-0.160.0.jar'), modJar('fabric-api'));
    writeFileSync(join(mods, 'sodium.jar'), modJar('sodium'));
    const manager = new ModManager();

    const list = await manager.list(game);
    expect(Object.fromEntries(list.map((m) => [m.fileName, m.protection ?? null]))).toEqual({ 'fabric-api-0.160.0.jar': 'required', 'snowball-client.jar': 'core', 'sodium.jar': null });
    await expect(manager.remove(game, 'snowball-client.jar')).rejects.toBeInstanceOf(ProtectedModError);
    await expect(manager.setEnabled(game, 'snowball-client.jar', false)).rejects.toThrow(/can't be turned off/);
    await expect(manager.remove(game, 'fabric-api-0.160.0.jar')).rejects.toThrow(/required by Snowball Client/);
    await expect(manager.setEnabled(game, 'fabric-api-0.160.0.jar', false)).rejects.toBeInstanceOf(ProtectedModError);

    await manager.setEnabled(game, 'sodium.jar', false);
    await manager.remove(game, 'sodium.jar.disabled');
    expect(readdirSync(mods).sort()).toEqual(['fabric-api-0.160.0.jar', 'snowball-client.jar']);

    // A renamed copy is still recognised by its mod id, and the client can't be added as a user mod.
    writeFileSync(join(mods, 'renamed.jar'), clientJar());
    await expect(manager.remove(game, 'renamed.jar')).rejects.toBeInstanceOf(ProtectedModError);
    const source = tempDir();
    writeFileSync(join(source, 'snowball-copy.jar'), clientJar());
    await expect(manager.install(tempDir(), join(source, 'snowball-copy.jar'))).rejects.toThrow(/built into the launcher/);
  });

  it('keeps Fabric API removable in instances without Snowball Client', async () => {
    const game = tempDir();
    mkdirSync(join(game, 'mods'), { recursive: true });
    writeFileSync(join(game, 'mods', 'fabric-api.jar'), modJar('fabric-api'));
    const manager = new ModManager();
    expect((await manager.list(game))[0].protection).toBeUndefined();
    await manager.remove(game, 'fabric-api.jar');
    expect(readdirSync(join(game, 'mods'))).toEqual([]);
  });

  it('never lets a performance profile change delete Snowball Client', async () => {
    const game = tempDir();
    const mods = join(game, 'mods');
    mkdirSync(mods, { recursive: true });
    writeFileSync(join(mods, 'snowball-client.jar'), clientJar());
    writeFileSync(join(mods, 'sodium.jar'), modJar('sodium'));
    await installProfile(game, ['snowball-client.jar', 'sodium.jar'], [], { downloadAll: async () => undefined } as never);
    expect(readdirSync(mods)).toEqual(['snowball-client.jar']);
  });
});
