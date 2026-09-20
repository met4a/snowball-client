import { existsSync, mkdirSync, readdirSync, readFileSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';
import { ModManager } from '../src/core/mods/ModManager.js';
import { installProfile } from '../src/core/performance/PerformanceProfiles.js';
import { assertModChangeAllowed, ProtectedModError } from '../src/core/snowball/protection.js';
import { ClientBuildRegistry, CoreRepairError, SnowballCore } from '../src/core/snowball/SnowballCore.js';
import { makeZip, tempDir } from './helpers.js';

const clientJar = (version = '1.1.0', minecraft = '~26.2', java?: string) =>
  makeZip({ 'fabric.mod.json': JSON.stringify({ id: 'snowballclient', version, depends: { minecraft, 'fabric-api': '*', ...(java ? { java } : {}) } }), 'dev/Client.class': `build ${version}` });
const modJar = (id: string) => makeZip({ 'fabric.mod.json': JSON.stringify({ id, version: '1.0.0' }) });

async function setup(fabricApi?: (gameDir: string) => void) {
  const builds = tempDir();
  writeFileSync(join(builds, 'snowball-client-1.1.0.jar'), clientJar());
  writeFileSync(join(builds, 'snowball-client-1.0.0.jar'), clientJar('1.0.0'));
  writeFileSync(join(builds, 'snowball-client-1.1.0-sources.jar'), makeZip({ 'fabric.mod.json': JSON.stringify({ id: 'snowballclient', version: '${version}', depends: { minecraft: '~26.2' } }) }));
  const registry = await ClientBuildRegistry.discover([builds, join(builds, 'not-there')]);
  const installs: string[] = [];
  const store = tempDir();
  const core = new SnowballCore(registry, {
    ensure: async (gameDir) => {
      installs.push(gameDir);
      (fabricApi ?? ((dir) => writeFileSync(join(dir, 'mods', 'fabric-api-0.160.0.jar'), modJar('fabric-api'))))(gameDir);
      return true;
    },
  }, store);
  const newInstance = () => {
    const gameDir = tempDir();
    mkdirSync(join(gameDir, 'mods'), { recursive: true });
    return { gameDir, mods: join(gameDir, 'mods'), target: { gameDir, minecraftVersion: '26.2', loader: 'fabric' as const } };
  };
  const messages: string[] = [];
  const report = (_level: string, message: string) => void messages.push(message);
  return { registry, core, installs, messages, report, newInstance, ...newInstance() };
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

describe('Snowball Client on Legacy Fabric', () => {
  it('sets up and protects Legacy Fabric API for a Minecraft 1.8.9 build', async () => {
    const builds = tempDir();
    writeFileSync(join(builds, 'snowball-client-1.3.0+1.8.9.jar'), clientJar('1.3.0', '1.8.9', '>=21'));
    const registry = await ClientBuildRegistry.discover([builds]);
    expect(registry.find('1.8.9', 'fabric')?.version).toBe('1.3.0');
    expect(registry.find('1.8.9', 'fabric')?.javaMajor).toBe(21);
    expect(registry.find('1.8.8', 'fabric')).toBeNull();
    const core = new SnowballCore(registry, {
      ensure: async (gameDir, minecraftVersion) => {
        writeFileSync(join(gameDir, 'mods', 'legacy-fabric-api-1.13.5.jar'), modJar('legacy-fabric-api'));
        return minecraftVersion === '1.8.9';
      },
    }, tempDir());
    const gameDir = tempDir();
    const mods = join(gameDir, 'mods');
    mkdirSync(mods);
    const messages: string[] = [];
    const report = await core.ensure({ gameDir, minecraftVersion: '1.8.9', loader: 'fabric' }, (_level, message) => void messages.push(message));
    expect(messages).toContain('Installing Legacy Fabric API...');
    expect(report.problems).toEqual([]);
    expect(report.fabricApi).toBe('ok');
    await expect(assertModChangeAllowed(mods, 'legacy-fabric-api-1.13.5.jar', 'remove')).rejects.toThrow("Legacy Fabric API is required by Snowball Client, so it can't be removed in this instance.");
    await expect(assertModChangeAllowed(mods, 'legacy-fabric-api-1.13.5.jar', 'replace')).resolves.toBeUndefined();
  });
});

describe('Snowball core', () => {
  it('keeps the client in the launcher folder, not the mods folder, and only verifies once set up', async () => {
    const { core, registry, gameDir, mods, installs, messages, report, target, newInstance } = await setup();
    const first = await core.ensure(target, report);
    expect(messages).toEqual(['Checking core files...', 'Snowball Client is missing', 'Repairing Snowball Client...', 'Snowball Client restored', 'Installing Fabric API...', 'Fabric API installed', 'Core files verified']);
    expect(readdirSync(mods)).toEqual(['fabric-api-0.160.0.jar']);
    expect(first.clientJar).toBe(core.storePath(registry.builds[0]));
    expect(readFileSync(first.clientJar!)).toEqual(readFileSync(registry.builds[0].path));
    expect(existsSync(join(gameDir, '.snowball', 'core.json'))).toBe(true);

    messages.length = 0;
    expect(await core.ensure(target, report)).toMatchObject({ client: 'ok', fabricApi: 'ok', problems: [] });
    expect(messages).toEqual(['Checking core files...', 'Snowball Client 1.1.0 detected', 'Core files verified']);

    // The launcher's copy is shared, so another instance only needs its Fabric API.
    messages.length = 0;
    await core.ensure(newInstance().target, report);
    expect(messages).toEqual(['Checking core files...', 'Snowball Client 1.1.0 detected', 'Installing Fabric API...', 'Fabric API installed', 'Core files verified']);
    expect(installs).toHaveLength(2);
  });

  it('restores a deleted or damaged launcher copy', async () => {
    const { core, registry, mods, messages, report, target } = await setup();
    writeFileSync(join(mods, 'fabric-api.jar'), modJar('fabric-api'));
    const jar = (await core.ensure(target)).clientJar!;

    writeFileSync(jar, 'corrupted');
    expect(await core.inspect(target)).toMatchObject({ client: 'damaged', clientJar: null, problems: ['Snowball Client is damaged'] });
    await core.ensure(target, report);
    expect(readFileSync(jar)).toEqual(readFileSync(registry.builds[0].path));
    expect(messages).toEqual(['Checking core files...', 'Snowball Client is damaged', 'Repairing Snowball Client...', 'Snowball Client restored', 'Core files verified']);
  });

  it('moves old copies out of the mods folder so the client never loads twice', async () => {
    const { core, mods, messages, report, target } = await setup();
    writeFileSync(join(mods, 'fabric-api.jar'), modJar('fabric-api'));
    writeFileSync(join(mods, 'snowball-client.jar'), clientJar());
    writeFileSync(join(mods, 'my-copy.jar.disabled'), clientJar('1.0.0'));
    expect((await core.inspect(target)).problems).toEqual(['Snowball Client is missing', '2 old copies of Snowball Client are in the mods folder']);

    await core.ensure(target, report);
    expect(readdirSync(mods).sort()).toEqual(['fabric-api.jar', 'my-copy.jar.duplicate', 'snowball-client.jar.duplicate']);
    expect(messages[1]).toBe('Moved 2 old copies of Snowball Client out of the mods folder; it now loads from the launcher');
    expect((await core.inspect(target)).problems).toEqual([]);
  });

  it('turns Fabric API back on and explains when it cannot be installed', async () => {
    const turnedOff = await setup();
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

  it('sets aside a client copy and drops the Snowball marker when the version has no build', async () => {
    const { core, gameDir, mods, messages, report, target } = await setup();
    writeFileSync(join(mods, 'fabric-api.jar'), modJar('fabric-api'));
    await core.ensure(target);
    writeFileSync(join(mods, 'snowball-client.jar'), clientJar());

    const result = await core.ensure({ ...target, minecraftVersion: '1.21.11' }, report);
    expect(result).toMatchObject({ supported: false, clientJar: null, problems: [] });
    expect(readdirSync(mods).sort()).toEqual(['fabric-api.jar', 'snowball-client.jar.unsupported']);
    expect(existsSync(join(gameDir, '.snowball', 'core.json'))).toBe(false);
    expect(messages).toEqual(["Snowball Client isn't available for Minecraft 1.21.11 yet, so the copy in the mods folder was set aside"]);
  });
});

describe('protected Snowball components', () => {
  it('keeps the Fabric API of a Snowball instance from being removed or turned off, but allows updates', async () => {
    const { core, gameDir, mods, target } = await setup();
    writeFileSync(join(mods, 'fabric-api-0.160.0.jar'), modJar('fabric-api'));
    writeFileSync(join(mods, 'sodium.jar'), modJar('sodium'));
    const manager = new ModManager();
    expect((await manager.list(gameDir)).map((m) => m.protection ?? null)).toEqual([null, null]);

    await core.ensure(target);
    const list = await manager.list(gameDir);
    expect(Object.fromEntries(list.map((m) => [m.fileName, m.protection ?? null]))).toEqual({ 'fabric-api-0.160.0.jar': 'required', 'sodium.jar': null });
    await expect(manager.remove(gameDir, 'fabric-api-0.160.0.jar')).rejects.toThrow(/required by Snowball Client/);
    await expect(manager.setEnabled(gameDir, 'fabric-api-0.160.0.jar', false)).rejects.toBeInstanceOf(ProtectedModError);
    await expect(assertModChangeAllowed(mods, 'fabric-api-0.160.0.jar', 'replace')).resolves.toBeUndefined();

    await manager.setEnabled(gameDir, 'sodium.jar', false);
    await manager.remove(gameDir, 'sodium.jar.disabled');
    expect(readdirSync(mods)).toEqual(['fabric-api-0.160.0.jar']);

    const source = tempDir();
    writeFileSync(join(source, 'snowball-copy.jar'), clientJar());
    await expect(manager.install(gameDir, join(source, 'snowball-copy.jar'))).rejects.toThrow(/built into the launcher/);
  });

  it('keeps Fabric API removable in instances without Snowball Client', async () => {
    const game = tempDir();
    mkdirSync(join(game, 'mods'), { recursive: true });
    writeFileSync(join(game, 'mods', 'fabric-api.jar'), modJar('fabric-api'));
    const manager = new ModManager();
    await manager.remove(game, 'fabric-api.jar');
    expect(readdirSync(join(game, 'mods'))).toEqual([]);
  });

  it('never lets a performance profile change delete the Fabric API Snowball Client needs', async () => {
    const { core, mods, target } = await setup();
    writeFileSync(join(mods, 'fabric-api.jar'), modJar('fabric-api'));
    writeFileSync(join(mods, 'sodium.jar'), modJar('sodium'));
    await core.ensure(target);
    await installProfile(target.gameDir, ['fabric-api.jar', 'sodium.jar'], [], { downloadAll: async () => undefined } as never);
    expect(readdirSync(mods)).toEqual(['fabric-api.jar']);
  });
});
