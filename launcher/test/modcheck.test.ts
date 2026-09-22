import { writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';
import { parseVersionId } from '../src/core/import/LauncherImport.js';
import { ModManager } from '../src/core/mods/ModManager.js';
import { ModrinthService } from '../src/core/mods/Modrinth.js';
import { scanJars } from '../src/core/security/ModScanner.js';
import { sha1File } from '../src/core/util/fsutil.js';
import { makeZip, tempDir } from './helpers.js';

describe("reading the official launcher's version ids", () => {
  it("takes Minecraft's version from a Fabric profile, not Fabric's own", () => {
    // The bug: the first thing shaped like a version was 0.19.5, and that became the Minecraft version.
    expect(parseVersionId('fabric-loader-0.19.5-1.21.11')).toEqual({ minecraftVersion: '1.21.11', loader: 'fabric', loaderVersion: '0.19.5' });
    expect(parseVersionId('fabric-loader-0.19.5-26.2')).toEqual({ minecraftVersion: '26.2', loader: 'fabric', loaderVersion: '0.19.5' });
    expect(parseVersionId('quilt-loader-0.26.4-1.21.1')).toEqual({ minecraftVersion: '1.21.1', loader: 'quilt', loaderVersion: '0.26.4' });
  });

  it('reads releases, Forge, NeoForge and OptiFine', () => {
    expect(parseVersionId('1.21.11')).toEqual({ minecraftVersion: '1.21.11', loader: 'vanilla', loaderVersion: null });
    expect(parseVersionId('1.20.1-forge-47.3.0')).toEqual({ minecraftVersion: '1.20.1', loader: 'forge', loaderVersion: '47.3.0' });
    expect(parseVersionId('1.8.9-forge1.8.9-11.15.1.2318-1.8.9')).toEqual({ minecraftVersion: '1.8.9', loader: 'forge', loaderVersion: '11.15.1.2318' });
    expect(parseVersionId('neoforge-21.1.77')).toEqual({ minecraftVersion: '1.21.1', loader: 'neoforge', loaderVersion: '21.1.77' });
    expect(parseVersionId('neoforge-21.0.167')?.minecraftVersion).toBe('1.21');
    expect(parseVersionId('1.21.1-OptiFine_HD_U_J1')?.minecraftVersion).toBe('1.21.1');
  });

  it('gives up on ids it cannot read rather than guessing', () => {
    expect(parseVersionId('latest-release')).toBeNull();
    expect(parseVersionId('fabric-loader-0.16.0-24w14a')).toBeNull();
  });
});

describe('the mod check', () => {
  // Does what a stealer does, and calls itself Fabric API.
  const stealer = makeZip({ 'fabric.mod.json': '{"id":"fabric-api"}', 'x/Payload.class': 'discord.com/api/webhooks java/lang/ProcessBuilder cmd.exe' });
  // A real, popular mod that happens to do several of the things the rules look for.
  const busy = makeZip({ 'fabric.mod.json': '{"id":"flashback"}', 'x/Replay.class': 'java/lang/Runtime java/net/URLClassLoader MinecraftSessionService' });

  function folder() {
    const dir = tempDir();
    const stealerPath = join(dir, 'fabric-api-0.141.6+1.21.11.jar');
    const busyPath = join(dir, 'Flashback-0.39.8-for-MC1.21.11.jar');
    writeFileSync(stealerPath, stealer);
    writeFileSync(busyPath, busy);
    return { stealerPath, busyPath };
  }

  it('sets aside a file Modrinth published unchanged, and still reads a lookalike', async () => {
    const { stealerPath, busyPath } = folder();
    const published = await sha1File(busyPath);
    let asked: string[] = [];
    const report = await scanJars([stealerPath, busyPath], {
      recognise: async (hashes) => {
        asked = hashes;
        return new Map([[published, { source: 'Modrinth' as const, project: 'Flashback', version: '0.39.8' }]]);
      },
    });
    expect(report.recognised).toBe(true);
    expect(asked).toHaveLength(2);
    const byName = Object.fromEntries(report.results.map((r) => [r.fileName, r]));
    expect(byName['Flashback-0.39.8-for-MC1.21.11.jar']).toMatchObject({ verdict: 'known', findings: [], known: { project: 'Flashback' } });
    // Named like Fabric API, but not the file Modrinth published: it is read, and it shows.
    expect(byName['fabric-api-0.141.6+1.21.11.jar'].verdict).toBe('dangerous');
    // Anything worth a look comes before the known ones.
    expect(report.results[0].fileName).toBe('fabric-api-0.141.6+1.21.11.jar');
  });

  it('reads every file when the lookup fails, and says nothing was recognised', async () => {
    const { stealerPath, busyPath } = folder();
    const report = await scanJars([stealerPath, busyPath], { recognise: async () => { throw new Error('offline'); } });
    expect(report.recognised).toBe(false);
    expect(report.results.every((r) => r.verdict !== 'known')).toBe(true);
  });

  it("counts a file as known only when Modrinth's record carries that exact hash", async () => {
    const [a, b, stray] = ['a'.repeat(40), 'b'.repeat(40), 'c'.repeat(40)];
    const http = {
      postJson: async () => ({
        [a]: { project_id: 'P1', version_number: '0.141.6+1.21.11', files: [{ hashes: { sha1: a } }] },
        [b]: { project_id: 'P2', version_number: '1.0', files: [{ hashes: { sha1: 'something else' } }] },
        [stray]: { project_id: 'P3', version_number: '1.0', files: [{ hashes: { sha1: stray } }] },
      }),
      fetchJson: async () => [{ id: 'P1', title: 'Fabric API' }],
    };
    const service = new ModrinthService(http as never, new ModManager());
    const known = await service.recognise([a, b]);
    expect([...known.keys()]).toEqual([a]);
    expect(known.get(a)).toEqual({ source: 'Modrinth', project: 'Fabric API', version: '0.141.6+1.21.11' });
  });
});
