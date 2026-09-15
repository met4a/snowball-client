import { existsSync, mkdirSync, writeFileSync } from 'node:fs';
import { basename, join } from 'node:path';
import { describe, expect, it } from 'vitest';
import { InstanceManager, normalizeInstanceConfig } from '../src/core/instance/InstanceManager.js';
import { ModManager } from '../src/core/mods/ModManager.js';
import { ModrinthService, pickVersion, summarizeVersions, type ModrinthVersion } from '../src/core/mods/Modrinth.js';
import { sha1File } from '../src/core/util/fsutil.js';
import { makeZip, tempDir } from './helpers.js';

const API = 'https://api.modrinth.com/v2';

function version(id: string, project: string, number: string, opts: Partial<Pick<ModrinthVersion, 'game_versions' | 'loaders' | 'version_type' | 'date_published' | 'dependencies'>> = {}): ModrinthVersion {
  return {
    id,
    project_id: project,
    version_number: number,
    version_type: opts.version_type ?? 'release',
    game_versions: opts.game_versions ?? ['1.21.1'],
    loaders: opts.loaders ?? ['fabric'],
    date_published: opts.date_published ?? '2026-01-01T00:00:00Z',
    dependencies: opts.dependencies ?? [],
    files: [{ url: `https://cdn.modrinth.com/data/${project}/versions/${id}/${project}-${number}.jar`, filename: `${project}-${number}.jar`, primary: true, size: 1, hashes: { sha1: 'a'.repeat(40) } }],
  };
}

/** A jar whose fabric.mod.json id is the file name before the version ("fabric-api-0.1.jar" -> "fabric-api"). */
function jarFor(fileName: string): Buffer {
  const id = /^(.*?)-\d/.exec(fileName)?.[1] ?? fileName;
  return makeZip({ 'fabric.mod.json': JSON.stringify({ id, name: id, version: '1' }) });
}

function fakeHttp(get: Record<string, unknown>, post: Record<string, (body: any) => unknown> = {}) {
  const calls: string[] = [];
  return {
    calls,
    fetchJson: async <T>(url: string): Promise<T> => {
      calls.push(url);
      const key = url.replace(API, '').split('?')[0];
      if (!(key in get)) throw new Error(`HTTP 404 for ${url}`);
      return get[key] as T;
    },
    postJson: async <T>(url: string, body: unknown): Promise<T> => {
      const key = url.replace(API, '');
      calls.push(`POST ${key}`);
      return (post[key]?.(body) ?? {}) as T;
    },
    downloadAll: async (tasks: Array<{ dest: string }>) => {
      for (const t of tasks) {
        calls.push(`download ${basename(t.dest)}`);
        writeFileSync(t.dest, jarFor(basename(t.dest).replace(/\.part-update$/, '')));
      }
    },
  };
}

function gameDir(): string {
  const dir = tempDir();
  mkdirSync(join(dir, 'mods'), { recursive: true });
  return dir;
}

describe('Modrinth version selection', () => {
  it('needs the exact Minecraft version and a usable loader, and prefers stable releases', () => {
    const versions = [
      version('b', 'x', '2.0-beta', { version_type: 'beta', date_published: '2026-03-01T00:00:00Z' }),
      version('r', 'x', '1.9', { date_published: '2026-02-01T00:00:00Z' }),
      version('new', 'x', '3.0', { game_versions: ['1.21.2'], date_published: '2026-04-01T00:00:00Z' }),
      version('forge', 'x', '1.9-forge', { loaders: ['forge'], date_published: '2026-05-01T00:00:00Z' }),
    ];
    expect(pickVersion(versions, '1.21.1', ['fabric'])?.id).toBe('r');
    expect(pickVersion(versions, '1.21.1', ['forge'])?.id).toBe('forge');
    expect(pickVersion(versions.slice(0, 1), '1.21.1', ['quilt', 'fabric'])?.id).toBe('b');
    expect(pickVersion(versions, '1.21', ['fabric'])).toBeNull();
    const offCdn = version('evil', 'x', '1', {});
    offCdn.files[0].url = 'https://example.com/x.jar';
    expect(pickVersion([offCdn], '1.21.1', ['fabric'])).toBeNull();
  });

  it('summarises supported versions without snapshots', () => {
    expect(summarizeVersions(['1.21.10', '26.2-rc-1', '1.21', '26.2', '1.21.9'])).toBe('1.21 - 26.2');
    expect(summarizeVersions(['1.21.1'])).toBe('1.21.1');
  });
});

describe('Fabric API by default', () => {
  it('marks only new Fabric instances', async () => {
    const manager = new InstanceManager(tempDir());
    expect((await manager.create({ name: 'F', minecraftVersion: '1.21.1', loader: 'fabric' })).pendingFabricApi).toBe(true);
    expect((await manager.create({ name: 'N', minecraftVersion: '1.21.1', loader: 'neoforge' })).pendingFabricApi).toBe(false);
    expect(normalizeInstanceConfig({ minecraftVersion: '1.21.1', loader: 'forge', pendingFabricApi: true }, 'X').pendingFabricApi).toBe(false);
  });

  it('installs the Fabric API built for the exact version and never duplicates it', async () => {
    const dir = gameDir();
    const http = fakeHttp({
      '/project/fabric-api/version': [
        version('new', 'fabric-api', '0.140.0+1.21.4', { game_versions: ['1.21.4'], date_published: '2026-06-01T00:00:00Z' }),
        version('ok', 'fabric-api', '0.116.17+1.21.1', { date_published: '2026-05-01T00:00:00Z' }),
        version('old', 'fabric-api', '0.102.0+1.21.1', { date_published: '2025-01-01T00:00:00Z' }),
      ],
    });
    const service = new ModrinthService(http as never, new ModManager());
    await expect(service.ensureFabricApi(dir, '1.21.1')).resolves.toBe(true);
    expect(existsSync(join(dir, 'mods', 'fabric-api-0.116.17+1.21.1.jar'))).toBe(true);
    await expect(service.ensureFabricApi(dir, '1.21.1')).resolves.toBe(false);
    expect(http.calls.filter((c) => c.startsWith('download'))).toEqual(['download fabric-api-0.116.17+1.21.1.jar']);
  });

  it('respects a disabled Fabric API and reports a missing build', async () => {
    const dir = gameDir();
    writeFileSync(join(dir, 'mods', 'fabric-api-0.1.jar.disabled'), jarFor('fabric-api-0.1.jar'));
    const http = fakeHttp({ '/project/fabric-api/version': [] });
    const service = new ModrinthService(http as never, new ModManager());
    await expect(service.ensureFabricApi(dir, '1.21.1')).resolves.toBe(false);
    await expect(service.ensureFabricApi(gameDir(), '1.21.1')).rejects.toThrow(/no release for Minecraft 1.21.1/);
  });
});

describe('Modrinth installs and updates', () => {
  const sodium = version('sod1', 'sodium', '0.6.0', { dependencies: [] });
  const iris = version('iris1', 'iris', '1.8.0', {
    dependencies: [
      { project_id: 'sodium', version_id: 'sod1', dependency_type: 'required' },
      { project_id: 'modmenu', version_id: null, dependency_type: 'optional' },
    ],
  });

  it('installs required dependencies and skips optional ones', async () => {
    const dir = gameDir();
    const http = fakeHttp({
      '/project/iris': { id: 'iris', slug: 'iris', title: 'Iris' },
      '/project/sodium': { id: 'sodium', slug: 'sodium', title: 'Sodium' },
      '/project/iris/version': [iris],
      '/version/sod1': sodium,
    });
    const result = await new ModrinthService(http as never, new ModManager()).install({ gameDir: dir, minecraftVersion: '1.21.1', loader: 'fabric' }, 'iris');
    expect(result.installed.sort()).toEqual(['Iris', 'Sodium']);
    expect(http.calls.some((c) => c.includes('modmenu'))).toBe(false);
  });

  it('does not reinstall a dependency or the mod itself', async () => {
    const dir = gameDir();
    writeFileSync(join(dir, 'mods', 'sodium-0.5.jar'), jarFor('sodium-0.5.jar'));
    const sodiumHash = await sha1File(join(dir, 'mods', 'sodium-0.5.jar'));
    const http = fakeHttp(
      { '/project/iris': { id: 'iris', slug: 'iris', title: 'Iris' }, '/project/iris/version': [iris] },
      { '/version_files': () => ({ [sodiumHash]: sodium }) },
    );
    const service = new ModrinthService(http as never, new ModManager());
    const target = { gameDir: dir, minecraftVersion: '1.21.1', loader: 'fabric' as const };
    expect((await service.install(target, 'iris')).installed).toEqual(['Iris']);
    await expect(service.install(target, 'sodium')).rejects.toThrow(/already installed/);
  });

  it('refuses before downloading when a required dependency has no compatible version', async () => {
    const dir = gameDir();
    const http = fakeHttp({
      '/project/iris': { id: 'iris', slug: 'iris', title: 'Iris' },
      '/project/sodium': { id: 'sodium', slug: 'sodium', title: 'Sodium' },
      '/project/iris/version': [iris],
      '/project/sodium/version': [version('s2', 'sodium', '0.7', { game_versions: ['26.2'] })],
    });
    const service = new ModrinthService(http as never, new ModManager());
    await expect(service.install({ gameDir: dir, minecraftVersion: '1.21.1', loader: 'fabric' }, 'iris')).rejects.toThrow(/Sodium \(required by Iris\)/);
    expect(http.calls.some((c) => c.startsWith('download'))).toBe(false);
    await expect(service.install({ gameDir: dir, minecraftVersion: '1.21.1', loader: 'vanilla' }, 'iris')).rejects.toThrow(/no mod loader/);
  });

  it('finds updates for the instance and keeps a disabled mod disabled', async () => {
    const dir = gameDir();
    writeFileSync(join(dir, 'mods', 'sodium-0.5.jar.disabled'), jarFor('sodium-0.5.jar'));
    const hash = await sha1File(join(dir, 'mods', 'sodium-0.5.jar.disabled'));
    const current = version('sod0', 'sodium', '0.5', { date_published: '2025-01-01T00:00:00Z' });
    const next = version('sod1', 'sodium', '0.6.0', { date_published: '2026-01-01T00:00:00Z' });
    let requested: any;
    const http = fakeHttp({}, {
      '/version_files': () => ({ [hash]: current }),
      '/version_files/update': (body) => ((requested = body), { [hash]: next }),
    });
    const service = new ModrinthService(http as never, new ModManager());
    const target = { gameDir: dir, minecraftVersion: '1.21.1', loader: 'quilt' as const };
    expect(await service.checkUpdates(target)).toEqual([{ fileName: 'sodium-0.5.jar.disabled', projectId: 'sodium', currentVersion: '0.5', newVersion: '0.6.0' }]);
    expect(requested).toMatchObject({ loaders: ['quilt', 'fabric'], game_versions: ['1.21.1'] });
    const result = await service.update(target, 'sodium-0.5.jar.disabled');
    expect(result.newFile).toBe('sodium-0.6.0.jar.disabled');
    expect(existsSync(join(dir, 'mods', 'sodium-0.5.jar.disabled'))).toBe(false);
    expect(existsSync(join(dir, 'mods', 'sodium-0.6.0.jar.disabled'))).toBe(true);
  });

  it('maps search results and drops icons from other hosts', async () => {
    const http = fakeHttp({
      '/search': { total_hits: 1, hits: [{ project_id: 'AANobbMI', slug: 'sodium', title: 'Sodium', author: 'jellysquid3', description: 'Fast', categories: ['fabric', 'optimization', 'neoforge'], versions: ['1.21', '26.2'], downloads: 5, follows: 2, icon_url: 'https://evil.example/x.png', date_modified: '2026-09-12' }, { bogus: true }] },
    });
    const r = await new ModrinthService(http as never, new ModManager()).search({ query: 'sod', loader: 'fabric', gameVersion: '1.21.1', sort: 'downloads', offset: 0, limit: 20 });
    expect(r.hits).toHaveLength(1);
    expect(r.hits[0]).toMatchObject({ projectId: 'AANobbMI', loaders: ['fabric', 'neoforge'], iconUrl: null, versionRange: '1.21 - 26.2' });
    const url = decodeURIComponent(http.calls[0]);
    expect(url).toContain('index=downloads');
    expect(url).toContain('[["project_type:mod"],["categories:fabric"],["versions:1.21.1"]]');
  });
});
