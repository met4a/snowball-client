import { mkdirSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { DownloadManager } from '../src/core/download/DownloadManager.js';
import { Launcher, type LaunchProgress } from '../src/core/Launcher.js';
import { VersionManager } from '../src/core/minecraft/VersionManager.js';
import type { VersionJson } from '../src/core/minecraft/types.js';
import { createLauncherPaths } from '../src/core/util/paths.js';
import { tempDir } from './helpers.js';

const cipher = { isAvailable: () => false, encrypt: (s: string) => s, decrypt: (s: string) => s };

afterEach(() => vi.restoreAllMocks());

describe('launch progress', () => {
  it('sends a stage\'s file count a few times a second, not once per file', async () => {
    const launcher = await Launcher.create({ root: tempDir(), cipher, clientBuildDirs: [], launcherVersion: 'test', consoleLogs: false });
    const sent: LaunchProgress[] = [];
    launcher.on('progress', (e: LaunchProgress) => sent.push(e));
    const progress = (stage: string, completed?: number, total?: number) =>
      (launcher as unknown as { progress: (id: string, s: string, p?: object) => void }).progress('i', stage, completed === undefined ? undefined : { completed, total, bytes: 0 });

    // 4591 assets checked over one second, with Java reporting alongside: before, every one of
    // them reached the window, which redrew the page and read every mod jar each time.
    let now = 1_000_000;
    vi.spyOn(Date, 'now').mockImplementation(() => now);
    progress('Checking game assets');
    for (let i = 1; i <= 4591; i++) {
      now += 1000 / 4591;
      progress('Checking game assets', i, 4591);
      if (i % 500 === 0) progress('Downloading Java 21', i / 500, 20);
    }
    const counts = sent.filter((e) => e.stage === 'Checking game assets' && e.total);
    expect(counts.length).toBeGreaterThanOrEqual(10);
    expect(counts.length).toBeLessThanOrEqual(12);
    expect(counts[0].completed).toBe(1);
    expect(counts.at(-1)!.completed).toBe(4591);
    // A stage without counts, and every count of a slower stage, still get through.
    expect(sent.filter((e) => e.stage === 'Checking game assets' && !e.total)).toHaveLength(1);
    expect(sent.filter((e) => e.stage === 'Downloading Java 21')).toHaveLength(9);
    // The activity view names each stage once, however the two took turns.
    expect(launcher.activity.recent('i').map((e) => e.message)).toEqual(['Checking game assets', 'Downloading Java 21']);
  });

  it('says it is checking game files, and only says downloading when something is', async () => {
    const root = tempDir();
    const paths = createLauncherPaths(root);
    const body = Buffer.from('hello world');
    let fetches = 0;
    const downloads = new DownloadManager({ fetchImpl: async () => (fetches++, new Response(body)) });
    const versions = new VersionManager(paths, downloads);
    const sha1 = '2aae6c35c94fcfb415dbe95f408b9ce91ee846ed';
    const index = JSON.stringify({ objects: { 'a.ogg': { hash: sha1, size: 11 } } });
    const indexSha = (await import('node:crypto')).createHash('sha1').update(index).digest('hex');
    mkdirSync(join(paths.assets, 'indexes'), { recursive: true });
    writeFileSync(join(paths.assets, 'indexes', 'x.json'), index);
    const version = { id: 'x', mainClass: 'M', libraries: [], assetIndex: { id: 'x', url: 'https://example.invalid/x.json', sha1: indexSha, size: index.length } } as unknown as VersionJson;

    const stages = async () => {
      const seen: string[] = [];
      await versions.installFiles(version, undefined, (s) => {
        if (seen.at(-1) !== s) seen.push(s);
      });
      return seen;
    };
    expect(await stages()).toEqual(['Checking libraries', 'Checking game assets', 'Downloading game assets']);
    expect(fetches).toBe(1);
    expect(await stages()).toEqual(['Checking libraries', 'Checking game assets']);
    expect(fetches).toBe(1);
  });
});
