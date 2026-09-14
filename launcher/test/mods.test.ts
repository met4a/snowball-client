import { existsSync, mkdirSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';
import { ModManager, parseModsToml } from '../src/core/mods/ModManager.js';
import { compareVersions, matchesFabricPredicate, matchesMavenRange } from '../src/core/mods/versionRange.js';
import { makeZip, tempDir } from './helpers.js';

describe('version ranges', () => {
  it('compares releases, snapshots and build metadata', () => {
    expect(compareVersions('26.2', '26.1.2')).toBe(1);
    expect(compareVersions('26.3-rc-2', '26.3')).toBe(-1);
    expect(compareVersions('1.21.4', '1.21.4+build.7')).toBe(0);
    expect(compareVersions('0.19.10', '0.19.9')).toBe(1);
  });

  it('matches Fabric predicates', () => {
    expect(matchesFabricPredicate('26.2', '~26.2')).toBe(true);
    expect(matchesFabricPredicate('26.3', '~26.2')).toBe(false);
    expect(matchesFabricPredicate('1.21.4', '>=1.21 <1.22')).toBe(true);
    expect(matchesFabricPredicate('1.22', '>=1.21 <1.22')).toBe(false);
    expect(matchesFabricPredicate('1.21.4', '1.21.x')).toBe(true);
    expect(matchesFabricPredicate('1.20.6', ['1.21.x', '1.20.6'])).toBe(true);
    expect(matchesFabricPredicate('26.2', '*')).toBe(true);
    expect(matchesFabricPredicate('2.5.0', '^2.1')).toBe(true);
    expect(matchesFabricPredicate('3.0.0', '^2.1')).toBe(false);
  });

  it('matches Maven ranges', () => {
    expect(matchesMavenRange('1.21.1', '[1.21,1.22)')).toBe(true);
    expect(matchesMavenRange('1.22', '[1.21,1.22)')).toBe(false);
    expect(matchesMavenRange('1.21.1', '[1.21.1]')).toBe(true);
    expect(matchesMavenRange('1.20', '(,1.20]')).toBe(true);
    expect(matchesMavenRange('1.21.1', '1.21')).toBe(true);
    expect(matchesMavenRange('1.19', '[1.18,1.19),[1.20,)')).toBe(false);
  });
});

describe('mods.toml parsing', () => {
  it('reads mods and minecraft dependency ranges', () => {
    const toml = [
      'modLoader="javafml" # comment',
      '[[mods]]',
      'modId="examplemod"',
      'version="1.2.3"',
      "displayName='Example Mod'",
      '[[dependencies.examplemod]]',
      'modId="minecraft"',
      'versionRange="[1.21.1,1.22)"',
    ].join('\n');
    const parsed = parseModsToml(toml);
    expect(parsed.mods[0]).toMatchObject({ modId: 'examplemod', version: '1.2.3', displayName: 'Example Mod' });
    expect(parsed.dependencies.examplemod[0].versionRange).toBe('[1.21.1,1.22)');
  });
});

describe('ModManager', () => {
  function fabricJar(id: string, mc: string) {
    return makeZip({ 'fabric.mod.json': JSON.stringify({ id, name: id.toUpperCase(), version: '1.0.0', depends: { minecraft: mc } }) });
  }

  it('lists, toggles, detects duplicates, loader and version problems', async () => {
    const game = tempDir();
    const mods = join(game, 'mods');
    mkdirSync(mods, { recursive: true });
    writeFileSync(join(mods, 'sodium-a.jar'), fabricJar('sodium', '~26.2'));
    writeFileSync(join(mods, 'sodium-b.jar'), fabricJar('sodium', '~26.2'));
    writeFileSync(join(mods, 'old.jar'), fabricJar('oldmod', '1.20.x'));
    writeFileSync(join(mods, 'forge.jar'), makeZip({ 'META-INF/mods.toml': '[[mods]]\nmodId="forgemod"\nversion="1"' }));
    writeFileSync(join(mods, 'notes.txt'), 'not a mod');
    writeFileSync(join(mods, 'broken.jar'), 'garbage');

    const manager = new ModManager();
    const list = await manager.list(game);
    expect(list.map((m) => m.fileName).sort()).toEqual(['broken.jar', 'forge.jar', 'old.jar', 'sodium-a.jar', 'sodium-b.jar']);
    expect(list.find((m) => m.fileName === 'broken.jar')?.error).toBeTruthy();

    const issues = manager.analyze(list, '26.2', 'fabric');
    const codes = issues.map((i) => i.code);
    expect(codes).toContain('duplicate');
    expect(codes).toContain('wrong-loader');
    expect(codes).toContain('minecraft-version');

    const disabled = await manager.setEnabled(game, 'sodium-b.jar', false);
    expect(disabled).toBe('sodium-b.jar.disabled');
    expect(existsSync(join(mods, 'sodium-b.jar.disabled'))).toBe(true);
    const after = manager.analyze(await manager.list(game), '26.2', 'fabric');
    expect(after.map((i) => i.code)).not.toContain('duplicate');
  });

  it('detects known rendering conflicts', async () => {
    const game = tempDir();
    mkdirSync(join(game, 'mods'), { recursive: true });
    writeFileSync(join(game, 'mods', 'iris.jar'), fabricJar('iris', '*'));
    const issues = new ModManager().analyze(await new ModManager().list(game), '26.2', 'fabric');
    expect(issues.some((i) => i.message.includes('Iris requires Sodium'))).toBe(true);
  });

  it('rejects unsafe installs and applies in-game mod requests', async () => {
    const game = tempDir();
    const src = tempDir();
    writeFileSync(join(src, 'lithium.jar'), fabricJar('lithium', '*'));
    writeFileSync(join(src, 'readme.txt'), 'x');
    const manager = new ModManager();
    await expect(manager.install(game, join(src, 'readme.txt'))).rejects.toThrow();
    await manager.install(game, join(src, 'lithium.jar'));
    await expect(manager.install(game, join(src, 'lithium.jar'))).rejects.toThrow(/already installed/);
    await expect(manager.remove(game, '../../etc.jar')).rejects.toThrow();

    await manager.recordToggle(game, 'lithium', false);
    const applied = await manager.applyClientRequests(game);
    expect(applied.changed).toEqual(['disabled LITHIUM']);
    expect(existsSync(join(game, 'mods', 'lithium.jar.disabled'))).toBe(true);
    await manager.recordToggle(game, 'lithium', true);
    await manager.applyClientRequests(game);
    expect(existsSync(join(game, 'mods', 'lithium.jar'))).toBe(true);
  });
});
