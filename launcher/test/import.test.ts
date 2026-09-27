import { mkdirSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { DatabaseSync } from 'node:sqlite';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { tempDir } from './helpers.js';

type ImportModule = typeof import('../src/core/import/LauncherImport.js');

const saved = { APPDATA: process.env.APPDATA, USERPROFILE: process.env.USERPROFILE, HOME: process.env.HOME, XDG_DATA_HOME: process.env.XDG_DATA_HOME, LOCALAPPDATA: process.env.LOCALAPPDATA };

function appDataOf(home: string): string {
  if (process.platform === 'win32') return join(home, 'AppData', 'Roaming');
  if (process.platform === 'darwin') return join(home, 'Library', 'Application Support');
  return join(home, '.local', 'share');
}

/** A fresh copy of the importer that sees `home` as the user's folder, with nothing else installed. */
async function importerFor(home: string): Promise<ImportModule> {
  const appData = appDataOf(home);
  mkdirSync(appData, { recursive: true });
  Object.assign(process.env, { APPDATA: appData, USERPROFILE: home, HOME: home, XDG_DATA_HOME: appData, LOCALAPPDATA: join(home, 'AppData', 'Local') });
  vi.resetModules();
  return import('../src/core/import/LauncherImport.js');
}

function file(path: string, content = ''): void {
  mkdirSync(join(path, '..'), { recursive: true });
  writeFileSync(path, content);
}

afterEach(() => {
  for (const [key, value] of Object.entries(saved)) {
    if (value === undefined) delete process.env[key];
    else process.env[key] = value;
  }
});

describe('importing from other launchers', () => {
  it('reads Modrinth App profiles from its database, not the profile.json it no longer writes', async () => {
    const home = tempDir();
    const data = join(appDataOf(home), 'ModrinthApp');
    file(join(data, 'profiles', 'Cpvp', 'mods', 'sodium.jar'));
    mkdirSync(join(data, 'profiles', 'Cpvp', 'saves', 'World'), { recursive: true });
    const db = new DatabaseSync(join(data, 'app.db'));
    db.exec(`create table instances (id text primary key, path text, applied_content_set_id text, name text);
      create table instance_content_sets (id text primary key, instance_id text, game_version text, loader text, loader_version text);
      insert into instances values ('legacy:Cpvp', 'Cpvp', 'legacy:Cpvp:default', 'Crystal PvP');
      insert into instance_content_sets values ('legacy:Cpvp:default', 'legacy:Cpvp', '1.21.11', 'fabric', '0.19.5');`);
    db.close();

    const importer = await importerFor(home);
    const found = (await importer.detectInstances()).filter((f) => f.launcher === 'Modrinth App');
    expect(found).toHaveLength(1);
    expect(found[0]).toMatchObject({ name: 'Crystal PvP', minecraftVersion: '1.21.11', loader: 'fabric', loaderVersion: '0.19.5', mods: 1, worlds: 1 });
  });

  it('still reads the older Modrinth profiles table and profile.json', async () => {
    const home = tempDir();
    const data = join(appDataOf(home), 'ModrinthApp');
    mkdirSync(join(data, 'profiles', 'Old'), { recursive: true });
    file(join(data, 'profiles', 'Older', 'profile.json'), JSON.stringify({ metadata: { name: 'Older', game_version: '1.20.1', loader: 'quilt' } }));
    const db = new DatabaseSync(join(data, 'app.db'));
    db.exec(`create table profiles (path text, name text, game_version text, mod_loader text, mod_loader_version text);
      insert into profiles values ('Old', 'Old one', '1.21.4', 'fabric', null);`);
    db.close();

    const found = await (await importerFor(home)).detectInstances();
    expect(found.map((f) => [f.name, f.minecraftVersion, f.loader])).toEqual(expect.arrayContaining([['Old one', '1.21.4', 'fabric'], ['Older', '1.20.1', 'quilt']]));
  });

  it("offers each Lunar Client version with mods, taking worlds from Lunar's game folder", async () => {
    const home = tempDir();
    const lunar = join(home, '.lunarclient');
    const game = join(home, 'games', 'minecraft');
    mkdirSync(join(game, 'saves', 'Survival'), { recursive: true });
    file(join(game, 'resourcepacks', 'pvp.zip'));
    file(join(lunar, 'settings', 'launcher.json'), JSON.stringify({ settings: { gameDirectory: game } }));
    file(join(lunar, 'profiles', '1.21', 'options.txt'), 'key_key.attack:key.mouse.left');
    file(join(lunar, 'profiles', '1.21', 'mods', 'fabric-1.21.11', 'sodium.jar'));
    file(join(lunar, 'profiles', '1.21', 'mods', 'fabric-1.21.11', 'iris.jar'));
    mkdirSync(join(lunar, 'profiles', '1.21', 'mods', 'fabric-1.21.4'), { recursive: true });
    file(join(lunar, 'profiles', '1.8', 'options.txt'));

    const importer = await importerFor(home);
    const found = (await importer.detectInstances()).filter((f) => f.launcher === 'Lunar Client');
    expect(found.map((f) => f.minecraftVersion).sort()).toEqual(['1.21.11', '1.8.9']);
    const modern = found.find((f) => f.minecraftVersion === '1.21.11')!;
    expect(modern).toMatchObject({ loader: 'fabric', mods: 2, worlds: 1, resourcePacks: 1 });
    expect(importer.sourceOf(modern, 'saves')).toBe(join(game, 'saves'));
    expect(importer.sourceOf(modern, 'options.txt')).toBe(join(lunar, 'profiles', '1.21', 'options.txt'));
    // The 1.8 profile has no mods of its own: its options, servers and packs are still worth bringing.
    const pvp = found.find((f) => f.minecraftVersion === '1.8.9')!;
    expect(pvp.mods).toBe(0);
    expect(importer.sourceOf(pvp, 'mods')).toBeNull();
  });

  it('offers Feather Client versions with mods and the one it last launched', async () => {
    const home = tempDir();
    const feather = join(appDataOf(home), '.feather');
    const game = join(home, 'mc');
    mkdirSync(join(game, 'saves'), { recursive: true });
    file(join(feather, 'settings.json'), JSON.stringify({ mcPath: game, versionToLaunch: '1.21.4' }));
    file(join(feather, 'user-mods', '1.21.11-fabric', 'a.jar'));
    mkdirSync(join(feather, 'user-mods', '1.20.6-fabric'), { recursive: true });

    const found = (await (await importerFor(home)).detectInstances()).filter((f) => f.launcher === 'Feather Client');
    expect(found.map((f) => [f.minecraftVersion, f.mods])).toEqual([['1.21.11', 1], ['1.21.4', 0]]);
    expect(found[0].gameDir).toBe(game);
  });

  it('offers a .minecraft folder that PvP clients play from even without a version, and asks for one', async () => {
    const home = tempDir();
    const minecraft = process.platform === 'win32' ? join(appDataOf(home), '.minecraft') : process.platform === 'darwin' ? join(appDataOf(home), 'minecraft') : join(home, '.minecraft');
    mkdirSync(join(minecraft, 'BLClient-Mod-Profiles'), { recursive: true });
    mkdirSync(join(minecraft, 'saves', 'Hub'), { recursive: true });
    const found = (await (await importerFor(home)).detectInstances()).filter((f) => f.id.startsWith('vanilla:'));
    expect(found).toHaveLength(1);
    expect(found[0]).toMatchObject({ minecraftVersion: '', launcher: 'Minecraft Launcher / Badlion', worlds: 1 });
  });

  it('recognises a chosen folder from its files, or takes it as a game folder', async () => {
    const home = tempDir();
    const importer = await importerFor(home);
    const prism = join(home, 'prism-instance');
    file(join(prism, 'mmc-pack.json'), JSON.stringify({ components: [{ uid: 'net.minecraft', version: '1.20.1' }, { uid: 'net.fabricmc.fabric-loader', version: '0.16.0' }] }));
    mkdirSync(join(prism, 'minecraft'), { recursive: true });
    expect(await importer.inspectFolder(prism)).toMatchObject({ id: `folder:${prism}`, minecraftVersion: '1.20.1', loader: 'fabric' });

    const plain = join(home, 'some-client');
    file(join(plain, 'mods', 'x.jar'));
    expect(await importer.inspectFolder(plain)).toMatchObject({ launcher: 'Chosen folder', minecraftVersion: '', mods: 1 });
    expect(await importer.inspectFolder(join(home, 'missing'))).toBeNull();
  });

  it('orders versions as releases, not as text', async () => {
    const { compareVersions } = await importerFor(tempDir());
    expect(['1.21.4', '26.2', '1.8.9', '1.21.11'].sort(compareVersions)).toEqual(['1.8.9', '1.21.4', '1.21.11', '26.2']);
  });
});
