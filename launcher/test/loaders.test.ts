import { existsSync, mkdirSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';
import { fabricApiFor, usesLegacyFabric } from '../src/core/minecraft/fabricFamily.js';
import { FabricLikeLoader, validateProfile } from '../src/core/modloader/FabricLikeLoader.js';
import { InstallerLoader } from '../src/core/modloader/InstallerLoader.js';
import { ModLoaderRegistry } from '../src/core/modloader/ModLoaderRegistry.js';
import type { VersionJson } from '../src/core/minecraft/types.js';
import { createLauncherPaths } from '../src/core/util/paths.js';
import { makeZip, tempDir } from './helpers.js';

function fakeVersions(root: string) {
  const calls: string[] = [];
  return {
    calls,
    versionDir: (id: string) => join(root, 'versions', id),
    versionJsonPath: (id: string) => join(root, 'versions', id, `${id}.json`),
    installVanilla: async (id: string) => {
      calls.push(`vanilla:${id}`);
      return {} as VersionJson;
    },
    resolve: async (id: string) => {
      calls.push(`resolve:${id}`);
      return { id, mainClass: 'x', libraries: [] } as VersionJson;
    },
    installFiles: async () => {
      calls.push('files');
    },
  };
}

describe('Fabric/Quilt loaders', () => {
  const profile = { id: 'fabric-loader-0.19.5-26.2', inheritsFrom: '26.2', mainClass: 'net.fabricmc.loader.impl.launch.knot.KnotClient', libraries: [{ name: 'org.ow2.asm:asm:9.10.1', url: 'https://maven.fabricmc.net/' }] };

  it('lists versions from meta', async () => {
    const loader = FabricLikeLoader.fabric({ fetchJson: async <T>() => [{ loader: { version: '0.19.5', stable: true } }, { loader: { version: '0.19.4', stable: false } }, { loader: { version: '../bad' } }] as T });
    expect(await loader.listVersions('26.2')).toEqual([{ version: '0.19.5', stable: true }, { version: '0.19.4', stable: false }]);
  });

  it('installs vanilla, stores the validated profile and downloads libraries', async () => {
    const root = tempDir();
    const versions = fakeVersions(root);
    const loader = FabricLikeLoader.fabric({ fetchJson: async <T>() => profile as T });
    const id = await loader.install('26.2', '0.19.5', { versions: versions as never, downloads: {} as never, paths: createLauncherPaths(root) });
    expect(id).toBe('fabric-loader-0.19.5-26.2');
    expect(existsSync(versions.versionJsonPath(id))).toBe(true);
    expect(versions.calls).toEqual(['vanilla:26.2', `resolve:${id}`, 'files']);
    expect(await loader.findInstalled('26.2', '0.19.5', { versions: versions as never })).toBe(id);
  });

  it('rejects profiles for the wrong game version or with unsafe repositories', () => {
    expect(() => validateProfile({ ...profile, inheritsFrom: '1.21' } as VersionJson, '26.2', 'Fabric')).toThrow(/expected 26.2/);
    expect(() => validateProfile({ ...profile, libraries: [{ name: 'a:b:1', url: 'http://evil/' }] } as VersionJson, '26.2', 'Fabric')).toThrow(/non-HTTPS/);
  });

  it('uses Legacy Fabric for Fabric instances on Minecraft 1.13.2 and older', async () => {
    expect(['1.8.9', '1.12.2', '1.13.2', '1.3.2'].every(usesLegacyFabric)).toBe(true);
    expect(['1.14', '1.21.11', '26.2', '1.2.5', '18w43b', '1.8.9-pre1'].some(usesLegacyFabric)).toBe(false);
    expect(fabricApiFor('1.8.9').slug).toBe('legacy-fabric-api');
    expect(fabricApiFor('26.2').slug).toBe('fabric-api');

    const urls: string[] = [];
    const legacyProfile = { id: 'fabric-loader-0.19.3-1.8.9', inheritsFrom: '1.8.9', mainClass: 'net.fabricmc.loader.impl.launch.knot.KnotClient', libraries: [{ name: 'net.legacyfabric:intermediary:1.8.9', url: 'https://maven.legacyfabric.net/' }] };
    const loader = FabricLikeLoader.fabric({
      fetchJson: async <T>(url: string) => {
        urls.push(url);
        return (url.endsWith('/profile/json') ? legacyProfile : [{ loader: { version: '0.19.3', stable: true } }]) as T;
      },
    });
    expect(loader.nameFor('1.8.9')).toBe('Legacy Fabric');
    expect(loader.nameFor('26.2')).toBe('Fabric');
    expect(await loader.listVersions('1.8.9')).toEqual([{ version: '0.19.3', stable: true }]);
    const root = tempDir();
    expect(await loader.install('1.8.9', '0.19.3', { versions: fakeVersions(root) as never, downloads: {} as never, paths: createLauncherPaths(root) })).toBe('fabric-loader-0.19.3-1.8.9');
    expect(urls).toEqual(['https://meta.legacyfabric.net/v2/versions/loader/1.8.9', 'https://meta.legacyfabric.net/v2/versions/loader/1.8.9/0.19.3/profile/json']);
    expect(FabricLikeLoader.quilt({ fetchJson: async <T>() => [] as T }).nameFor('1.8.9')).toBe('Quilt');
  });
});

describe('installer loaders', () => {
  it('maps Minecraft versions to NeoForge and Forge version lists', async () => {
    const neoXml = '<versions><version>21.1.50</version><version>26.2.0.85</version><version>26.2.0.88</version><version>26.1.2.109</version></versions>';
    const neo = InstallerLoader.neoforge({ fetchText: async () => neoXml });
    expect((await neo.listVersions('26.2')).map((v) => v.version)).toEqual(['26.2.0.88', '26.2.0.85']);
    expect((await neo.listVersions('1.21.1')).map((v) => v.version)).toEqual(['21.1.50']);
    const forge = InstallerLoader.forge({ fetchText: async () => '<version>26.2-65.1.3</version><version>26.2-65.1.0</version><version>1.21-51.0.33</version>' });
    expect((await forge.listVersions('26.2')).map((v) => v.version)).toEqual(['26.2-65.1.3', '26.2-65.1.0']);
  });

  it('refuses to run an installer without a published checksum', async () => {
    const root = tempDir();
    const neo = InstallerLoader.neoforge({ fetchText: async () => 'not-a-hash' }, async () => ({ code: 0, output: '' }));
    await expect(neo.install('26.2', '26.2.0.88', { versions: fakeVersions(root) as never, downloads: {} as never, paths: createLauncherPaths(root), javaPath: 'java' })).rejects.toThrow(/unverified/);
  });

  it('runs the verified installer headless and resolves the generated profile', async () => {
    const root = tempDir();
    const paths = createLauncherPaths(root);
    const versions = fakeVersions(root);
    const profileId = 'neoforge-26.2.0.88';
    let ranWith: string[] = [];
    const downloads = {
      download: async (task: { dest: string }) => {
        mkdirSync(join(task.dest, '..'), { recursive: true });
        writeFileSync(task.dest, makeZip({ 'version.json': JSON.stringify({ id: profileId }) }));
        return 'downloaded';
      },
    };
    const neo = InstallerLoader.neoforge({ fetchText: async () => 'a'.repeat(40) }, async (_java, args) => {
      ranWith = args;
      mkdirSync(versions.versionDir(profileId), { recursive: true });
      writeFileSync(versions.versionJsonPath(profileId), '{}');
      return { code: 0, output: 'ok' };
    });
    const id = await neo.install('26.2', '26.2.0.88', { versions: versions as never, downloads: downloads as never, paths, javaPath: 'java' });
    expect(id).toBe(profileId);
    expect(ranWith).toEqual(['-jar', expect.stringContaining('neoforge-26.2.0.88-installer.jar'), '--install-client', root]);
    expect(existsSync(join(root, 'launcher_profiles.json'))).toBe(true);
    expect(await neo.findInstalled('26.2', '26.2.0.88', { versions: versions as never })).toBe(profileId);
  });

  it('exposes all four loaders through the registry', () => {
    const registry = new ModLoaderRegistry({ fetchJson: async () => [] as never, fetchText: async () => '' });
    expect(registry.all().map((l) => l.id).sort()).toEqual(['fabric', 'forge', 'neoforge', 'quilt']);
    expect(registry.get('vanilla')).toBeNull();
  });
});
