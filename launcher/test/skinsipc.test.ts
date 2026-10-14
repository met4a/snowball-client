import { EventEmitter } from 'node:events';
import { existsSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { Launcher } from '../src/core/Launcher.js';
import type { VersionJson } from '../src/core/minecraft/types.js';
import { SkinLibrary } from '../src/core/skins/SkinLibrary.js';
import { SKIN_MESSAGES, SkinService, type SkinSessions } from '../src/core/skins/SkinService.js';
import { registerIpc } from '../src/main/ipc.js';
import { makePng, tempDir } from './helpers.js';

/** Electron, as far as the skin handlers use it: the handlers are kept to be called, the file dialog answers as told. */
const electron = vi.hoisted(() => ({
  handlers: new Map<string, (event: unknown, ...args: unknown[]) => Promise<unknown>>(),
  dialog: { canceled: false, filePaths: [] as string[] },
  opened: [] as string[],
}));

vi.mock('electron', () => ({
  app: { getVersion: () => 'test', getAppPath: () => '' },
  BrowserWindow: class {},
  dialog: { showOpenDialog: async () => electron.dialog },
  ipcMain: { handle: (channel: string, fn: (event: unknown, ...args: unknown[]) => Promise<unknown>) => electron.handlers.set(channel, fn) },
  shell: {
    openPath: async (path: string) => {
      electron.opened.push(path);
      return '';
    },
    openExternal: async () => undefined,
  },
}));

const TOKEN = 'eyJ'.padEnd(64, 'y');
const skinPng = (slim = false) => makePng(64, 64, (x, y) => (slim && x >= 50 && x <= 55 && y >= 16 && y <= 31 ? [0, 0, 0, 0] : [200, 80, 40, 255]));
const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json' } });
const profile = (texture: string) => ({ id: '0123456789abcdef0123456789abcdef', name: 'Snowy', skins: [{ id: 's', state: 'ACTIVE', url: `http://textures.minecraft.net/texture/${texture}`, variant: 'CLASSIC' }], capes: [] });

type Account = { id: string; name: string; type: 'msa' | 'offline' };

/**
 * The skin handlers over a real library and service, with a pretend Mojang and the parts of the
 * launcher they read. `call` sends from the launcher's own window, as the page does.
 */
function setup(options: { accounts?: Account[]; mojang?: (url: string, method: string) => Response | Promise<Response> } = {}) {
  electron.handlers.clear();
  const root = tempDir();
  const skinsDir = join(root, 'skins');
  const accounts = options.accounts ?? [{ id: 'msa-1', name: 'Snowy', type: 'msa' }];
  const sessions: SkinSessions = {
    session: async (id) => ({ type: accounts.find((a) => a.id === id)?.type ?? 'offline', accessToken: accounts.find((a) => a.id === id)?.type === 'msa' ? TOKEN : '0' }),
    invalidateSession: async () => undefined,
  };
  const requests: Array<{ url: string; method: string }> = [];
  const fetchImpl = async (url: string, init: { method?: string } = {}) => {
    requests.push({ url, method: init.method ?? 'GET' });
    return (options.mojang ?? (() => json({}, 503)))(url, init.method ?? 'GET');
  };
  const skins = new SkinService(new SkinLibrary(skinsDir), sessions, fetchImpl);
  const launcher = Object.assign(new EventEmitter(), {
    activity: new EventEmitter(),
    processes: new EventEmitter(),
    paths: { root, skins: skinsDir, logs: join(root, 'logs') },
    auth: { list: () => accounts },
    settings: { get: () => ({ accounts: { selectedAccountId: accounts[0]?.id } }) },
    skins,
  });
  const webContents = { send: () => undefined };
  registerIpc(launcher as unknown as Launcher, { webContents, isDestroyed: () => false } as never);
  const call = <T>(channel: string, ...args: unknown[]) => {
    const handler = electron.handlers.get(channel);
    if (!handler) throw new Error(`No handler for ${channel}`);
    return handler({ sender: webContents }, ...args) as Promise<T>;
  };
  return { call, handlers: electron.handlers, skins, skinsDir, requests, webContents };
}

beforeEach(() => {
  electron.dialog = { canceled: false, filePaths: [] };
  electron.opened.length = 0;
});

describe('the Skins page handlers', () => {
  it('list the library with each picture as a data URL, and the account skins are changed for', async () => {
    const { call, skins } = setup();
    const added = await skins.library.add(skinPng(), { name: 'Mine', source: 'file' });
    const library = await call<Snowball.SkinLibrary>('skins:list');
    expect(library.skins).toHaveLength(1);
    expect(library.skins[0]).toMatchObject({ id: added.id, name: 'Mine', variant: 'classic', favourite: false, texture: null });
    expect(library.skins[0].image).toBe(`data:image/png;base64,${skinPng().toString('base64')}`);
    expect(library).toMatchObject({ equipped: null, equippedVariant: null, account: { name: 'Snowy', canEquip: true } });
  });

  it('answer only the launcher window', async () => {
    const { handlers } = setup();
    await expect(handlers.get('skins:list')!({ sender: {} })).rejects.toThrow('Unauthorized sender');
  });

  it('rename, star, switch arms and delete, refusing ids and values the page should never send', async () => {
    const { call, skins } = setup();
    const { id } = await skins.library.add(skinPng(), { name: 'Mine', source: 'file' });

    expect((await call<Snowball.SkinLibrary>('skins:rename', id, '  Winter coat ')).skins[0].name).toBe('Winter coat');
    expect((await call<Snowball.SkinLibrary>('skins:set-favourite', id, true)).skins[0].favourite).toBe(true);
    expect((await call<Snowball.SkinLibrary>('skins:set-variant', id, 'slim')).skins[0].variant).toBe('slim');

    for (const bad of ['../../accounts', 'ABCDEF123456', 42, null]) await expect(call('skins:delete', bad)).rejects.toThrow(/Invalid skin id/);
    await expect(call('skins:rename', id, '')).rejects.toThrow('Skin names are 1 to 32 characters.');
    await expect(call('skins:rename', id, 'x'.repeat(33))).rejects.toThrow();
    await expect(call('skins:set-favourite', id, 'yes')).rejects.toThrow('Invalid favourite');
    await expect(call('skins:set-variant', id, 'wide')).rejects.toThrow('Invalid arm model');
    await expect(call('skins:rename', 'abcdef123456', 'Nobody')).rejects.toThrow('That skin is no longer in your library.');

    expect((await call<Snowball.SkinLibrary>('skins:delete', id)).skins).toEqual([]);
  });

  it('add from a file the player picks in the dialog, never a path the page names', async () => {
    const { call, skinsDir } = setup();
    const picked = join(tempDir(), 'Frost Knight.png');
    writeFileSync(picked, skinPng());

    electron.dialog = { canceled: true, filePaths: [] };
    expect(await call('skins:import-file', '/etc/passwd')).toBeNull();

    electron.dialog = { canceled: false, filePaths: [picked] };
    const result = await call<Snowball.SkinAdded>('skins:import-file', '/etc/passwd');
    expect(result.existed).toBe(false);
    expect(result.library.skins.find((s) => s.id === result.id)).toMatchObject({ name: 'Frost Knight', source: 'file' });
    expect(existsSync(join(skinsDir, `${result.id}.png`))).toBe(true);

    const notASkin = join(tempDir(), 'photo.png');
    writeFileSync(notASkin, makePng(32, 32, () => [1, 2, 3, 255]));
    electron.dialog = { canceled: false, filePaths: [notASkin] };
    await expect(call('skins:import-file')).rejects.toThrow('That is not a Minecraft skin.');
  });

  it('check names and links before asking anyone', async () => {
    const { call, requests } = setup();
    await expect(call('skins:add-username', 'no spaces')).rejects.toThrow('Minecraft names are 3 to 16 letters, numbers or underscores.');
    await expect(call('skins:add-username', 'x'.repeat(17))).rejects.toThrow();
    await expect(call('skins:add-url', 'http://example.com/skin.png')).rejects.toThrow('Only https:// links can be added.');
    await expect(call('skins:add-url', 'file:///C:/Windows/win.ini')).rejects.toThrow('Only https:// links can be added.');
    expect(requests).toEqual([]);
  });

  it('equip for the chosen Microsoft account and say which skin is worn', async () => {
    const { call, skins, requests } = setup({ mojang: (_url, method) => (method === 'POST' ? json(profile('abc123')) : json({}, 503)) });
    const { id } = await skins.library.add(skinPng(), { name: 'Mine', source: 'file' });
    const library = await call<Snowball.SkinLibrary>('skins:equip', id);
    expect(requests).toEqual([{ url: 'https://api.minecraftservices.com/minecraft/profile/skins', method: 'POST' }]);
    expect(library.equipped).toBe('abc123');
    expect(library.skins[0].texture).toBe('abc123');
  });

  it("pass Mojang's refusals on in the design's words, and send nothing again", async () => {
    for (const [status, message] of [[401, SKIN_MESSAGES.expired], [429, SKIN_MESSAGES.limited], [400, SKIN_MESSAGES.refused], [500, SKIN_MESSAGES.unreachable]] as const) {
      const { call, skins, requests } = setup({ mojang: () => json({}, status) });
      const { id } = await skins.library.add(skinPng(), { name: 'Mine', source: 'file' });
      const failed = await call('skins:equip', id).then(() => null, (err: Error) => err.message);
      expect(failed).toBe(message);
      expect(failed).not.toContain(TOKEN);
      expect(requests).toHaveLength(1);
    }
  });

  it('tell an offline account to sign in, and never ask Mojang for it', async () => {
    const { call, skins, requests } = setup({ accounts: [{ id: 'off', name: 'Steve', type: 'offline' }], mojang: () => json(profile('abc123')) });
    const { id } = await skins.library.add(skinPng(), { name: 'Mine', source: 'file' });
    expect((await call<Snowball.SkinLibrary>('skins:list')).account).toEqual({ name: 'Steve', canEquip: false });
    await expect(call('skins:equip', id)).rejects.toThrow(SKIN_MESSAGES.signIn);
    expect(await call<{ imported: string | null }>('skins:sync')).toMatchObject({ imported: null });
    expect(requests).toEqual([]);
  });

  it('with no account at all, keep the library and refuse to equip', async () => {
    const { call, skins } = setup({ accounts: [] });
    const { id } = await skins.library.add(skinPng(), { name: 'Mine', source: 'file' });
    expect((await call<Snowball.SkinLibrary>('skins:list')).account).toBeNull();
    await expect(call('skins:equip', id)).rejects.toThrow(SKIN_MESSAGES.signIn);
  });

  it('add the skin the account wears when the page opens', async () => {
    const worn = skinPng(true);
    const { call } = setup({ mojang: (url) => (url.endsWith('/minecraft/profile') ? json(profile('feed01')) : new Response(worn)) });
    const result = await call<{ library: Snowball.SkinLibrary; imported: string | null }>('skins:sync');
    expect(result.imported).not.toBeNull();
    expect(result.library.equipped).toBe('feed01');
    expect(result.library.skins).toEqual([expect.objectContaining({ id: result.imported, name: "Snowy's skin", source: 'current', texture: 'feed01' })]);
  });

  it('open the skins folder, and only that folder', async () => {
    const { call, skinsDir } = setup();
    await call('skins:open-folder', '/somewhere/else');
    expect(electron.opened).toEqual([skinsDir]);
  });
});

describe('launching the game', () => {
  it('tells it where the skin library is, so the game and the launcher share one', async () => {
    const root = tempDir();
    const cipher = { isAvailable: () => false, encrypt: (s: string) => s, decrypt: (s: string) => s };
    const launcher = await Launcher.create({ root, cipher, clientBuildDirs: [], launcherVersion: 'test', consoleLogs: false });
    expect(existsSync(join(root, 'skins'))).toBe(true);
    Object.defineProperty(launcher, 'auth', {
      value: { list: () => [{ id: 'a' }], session: async () => ({ name: 'Alex', uuid: '0'.repeat(32), accessToken: '0', type: 'offline' }) },
    });
    const config = await launcher.instances.create({ name: 'Test', minecraftVersion: '1.21.11', loader: 'vanilla' });
    const version: VersionJson = { id: '1.21.11', mainClass: 'net.minecraft.client.main.Main', libraries: [], arguments: { jvm: ['-cp', '${classpath}'], game: [] } };
    (launcher as unknown as { prepare: () => Promise<unknown> }).prepare = async () => ({ config: await launcher.instances.load(config.id), version, javaPath: 'java', clientJar: null });
    launcher.versions.prepareNatives = async () => undefined;
    let args: string[] = [];
    launcher.processes.launch = ((_id: string, _java: string, launchArgs: string[]) => {
      args = launchArgs;
    }) as never;

    await launcher.launch(config.id);
    expect(args).toContain(`-Dsnowball.skinsDir=${join(root, 'skins')}`);
    // A JVM option, so it comes before the main class.
    expect(args.indexOf(`-Dsnowball.skinsDir=${join(root, 'skins')}`)).toBeLessThan(args.indexOf(version.mainClass));
  });
});
