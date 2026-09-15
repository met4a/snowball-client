import { BrowserWindow, dialog, ipcMain, shell } from 'electron';
import { join } from 'node:path';
import { LOADER_IDS, PERFORMANCE_PROFILES as PROFILE_IDS, type InstanceConfig, type InstanceSummary } from '../core/instance/InstanceManager.js';
import { recommendMemory } from '../core/java/JavaManager.js';
import { SNOWBALL_CLIENT_MC, type Launcher } from '../core/Launcher.js';
import { getLogger, logSink } from '../core/logging/Logger.js';
import { SEARCH_SORTS } from '../core/mods/Modrinth.js';
import { PERFORMANCE_PROFILES } from '../core/performance/PerformanceProfiles.js';
import { splitArgs } from '../core/process/LaunchArguments.js';
import { safeJoin } from '../core/util/paths.js';
import { openMicrosoftLogin } from './microsoftLogin.js';

const log = getLogger('ipc');
const MOD_LOADERS = ['fabric', 'quilt', 'forge', 'neoforge'];
const FOLDERS = new Set(['root', 'mods', 'config', 'resourcepacks', 'shaderpacks', 'saves', 'screenshots', 'logs', 'crash-reports']);

function str(value: unknown, name: string, max = 256): string {
  if (typeof value !== 'string' || value.length > max || /[\0\r\n]/.test(value)) throw new Error(`Invalid ${name}`);
  return value;
}

function joinArgs(args: string[]): string {
  return args.map((a) => (/\s/.test(a) ? `"${a}"` : a)).join(' ');
}

function toDto(summary: InstanceSummary, running: boolean): Snowball.Instance {
  const c = summary.config;
  return {
    id: c.id,
    name: c.name,
    minecraftVersion: c.minecraftVersion,
    loader: c.loader,
    loaderVersion: c.loaderVersion,
    javaExecutable: c.java.executable,
    memory: c.memory,
    jvmArgs: joinArgs(c.jvmArgs),
    gameArgs: joinArgs(c.gameArgs),
    window: c.window,
    clientProfile: c.clientProfile,
    performanceProfile: c.performanceProfile,
    created: c.timestamps.created,
    lastPlayed: c.timestamps.lastPlayed,
    totalPlayMs: c.timestamps.totalPlayMs,
    running,
    error: summary.error,
  };
}

export function registerIpc(launcher: Launcher, win: BrowserWindow): void {
  const send = (event: string, payload: unknown) => {
    if (!win.isDestroyed()) win.webContents.send(`evt:${event}`, payload);
  };
  launcher.on('progress', (p) => send('progress', p));
  launcher.processes.on('log', (l) => send('game-log', l));
  launcher.processes.on('started', () => {
    send('state', null);
    if (launcher.settings.get().game.closeLauncherOnLaunch) win.minimize();
  });
  launcher.processes.on('exit', (e) => {
    send('game-exit', e);
    send('state', null);
  });
  logSink.subscribe((r) => send('launcher-log', { time: r.time, level: r.level, scope: r.scope, message: r.message }));

  const handle = (channel: string, fn: (...args: any[]) => unknown) => {
    ipcMain.handle(channel, async (event, ...args) => {
      if (event.sender !== win.webContents) throw new Error('Unauthorized sender');
      try {
        return await fn(...args);
      } catch (err) {
        log.warn(`${channel} failed`, { error: (err as Error).message });
        throw new Error((err as Error).message);
      }
    });
  };

  const instanceId = async (value: unknown): Promise<string> => {
    const id = str(value, 'instance id', 64);
    if (!(await launcher.instances.list()).some((i) => i.config.id === id)) throw new Error('That instance no longer exists.');
    return id;
  };

  const dto = async (id: string) => {
    const summary = (await launcher.instances.list()).find((i) => i.config.id === id);
    if (!summary) throw new Error('Instance not found');
    return toDto(summary, launcher.processes.isRunning(id));
  };

  handle('state:get', async (): Promise<Snowball.AppState> => {
    const rec = recommendMemory();
    const instances = await launcher.instances.list();
    return {
      instances: instances.map((i) => toDto(i, launcher.processes.isRunning(i.config.id))),
      accounts: launcher.auth.list(),
      settings: launcher.settings.get(),
      memory: { totalMb: rec.totalMb, recommendedMaxMb: rec.recommendedMaxMb, safeUpperLimitMb: rec.safeUpperLimitMb },
      profiles: [{ id: 'none', name: 'None', description: 'No optimisation mods are managed by the launcher.', mods: [] }, ...PERFORMANCE_PROFILES.map((p) => ({ id: p.id, name: p.name, description: p.description, mods: p.mods.map((m) => m.name) }))],
      dataRoot: launcher.paths.root,
      clientJarAvailable: launcher.clientJarAvailable,
      clientMinecraftVersion: SNOWBALL_CLIENT_MC,
      canAddOffline: launcher.auth.canAddOffline(),
      microsoftSignInConfigured: launcher.microsoftSignInConfigured,
      launcherVersion: process.env.npm_package_version ?? '1.1.1',
    };
  });

  handle('versions:list', async (includeSnapshots: unknown) =>
    (await launcher.versions.listAvailable(includeSnapshots === true)).map((v) => ({ id: v.id, type: v.type, releaseTime: v.releaseTime })));

  handle('loaders:versions', async (loaderId: unknown, mc: unknown) => {
    const loader = launcher.loaders.get(str(loaderId, 'loader', 16) as InstanceConfig['loader']);
    return loader ? loader.listVersions(str(mc, 'Minecraft version', 64)) : [];
  });

  handle('instances:create', async (raw: Snowball.CreateInstance) => {
    const loader = LOADER_IDS.includes(raw?.loader) ? raw.loader : 'vanilla';
    const profile = PROFILE_IDS.includes(raw?.performanceProfile) ? raw.performanceProfile : 'none';
    const config = await launcher.createInstance({
      name: str(raw?.name, 'name', 64),
      minecraftVersion: str(raw?.minecraftVersion, 'Minecraft version', 64),
      loader,
      loaderVersion: raw?.loaderVersion ? str(raw.loaderVersion, 'loader version', 64) : null,
      clientProfile: raw?.clientProfile === 'snowballclient' ? 'snowballclient' : 'none',
      performanceProfile: profile,
    });
    return dto(config.id);
  });

  handle('instances:update', async (id: unknown, patch: Snowball.InstancePatch) => {
    const iid = await instanceId(id);
    if (launcher.processes.isRunning(iid)) throw new Error('Stop the game before editing this instance.');
    await launcher.instances.update(iid, (c) => {
      if (typeof patch.name === 'string') c.name = str(patch.name, 'name', 64);
      if (typeof patch.minecraftVersion === 'string' && patch.minecraftVersion !== c.minecraftVersion) {
        c.minecraftVersion = str(patch.minecraftVersion, 'Minecraft version', 64);
        c.loaderVersion = null;
      }
      if (patch.loader && LOADER_IDS.includes(patch.loader) && patch.loader !== c.loader) {
        c.loader = patch.loader;
        c.loaderVersion = null;
        c.pendingFabricApi = patch.loader === 'fabric';
      }
      if (patch.loaderVersion !== undefined) c.loaderVersion = patch.loaderVersion ? str(patch.loaderVersion, 'loader version', 64) : null;
      if (patch.javaExecutable !== undefined) c.java.executable = patch.javaExecutable ? str(patch.javaExecutable, 'Java path', 1024) : null;
      if (patch.memory) c.memory = { minMb: Number(patch.memory.minMb), maxMb: Number(patch.memory.maxMb) };
      if (typeof patch.jvmArgs === 'string') c.jvmArgs = splitArgs(str(patch.jvmArgs, 'JVM arguments', 4096));
      if (typeof patch.gameArgs === 'string') c.gameArgs = splitArgs(str(patch.gameArgs, 'game arguments', 4096));
      if (patch.window) c.window = { width: Number(patch.window.width), height: Number(patch.window.height), fullscreen: patch.window.fullscreen === true };
      if (patch.clientProfile) c.clientProfile = patch.clientProfile === 'snowballclient' ? 'snowballclient' : 'none';
    });
    return dto(iid);
  });

  handle('instances:delete', async (id: unknown) => {
    const iid = await instanceId(id);
    if (launcher.processes.isRunning(iid)) throw new Error('Stop the game before deleting this instance.');
    await launcher.instances.delete(iid);
  });

  handle('instances:clone', async (id: unknown, name: unknown) => {
    const copy = await launcher.instances.clone(await instanceId(id), str(name, 'name', 64));
    return dto(copy.id);
  });

  handle('instances:open-folder', async (id: unknown, folder: unknown) => {
    const iid = await instanceId(id);
    const f = str(folder, 'folder', 32);
    if (!FOLDERS.has(f)) throw new Error('Unknown folder');
    const path = f === 'root' ? launcher.instances.instanceDir(iid) : safeJoin(launcher.instances.gameDir(iid), f);
    await import('node:fs/promises').then((fs) => fs.mkdir(path, { recursive: true }));
    const error = await shell.openPath(path);
    if (error) throw new Error(error);
  });

  handle('mods:list', async (id: unknown) => {
    const iid = await instanceId(id);
    const config = await launcher.instances.load(iid);
    const gameDir = launcher.instances.gameDir(iid);
    const mods = await launcher.mods.list(gameDir);
    const managed = new Set(config.managedMods);
    return {
      mods: mods.map((m) => ({ fileName: m.fileName, enabled: m.enabled, name: m.name, id: m.id, version: m.version, loader: m.loader, size: m.size, managed: managed.has(m.fileName.replace(/\.disabled$/, '')), error: m.error })),
      issues: launcher.mods.analyze(mods, config.minecraftVersion, config.loader).map((i) => ({ severity: i.severity, message: i.message, files: i.files, dependency: i.dependency })),
    };
  });

  handle('mods:set-enabled', async (id: unknown, fileName: unknown, enabled: unknown) => {
    const iid = await instanceId(id);
    const gameDir = launcher.instances.gameDir(iid);
    const name = str(fileName, 'file name');
    const mod = (await launcher.mods.list(gameDir)).find((m) => m.fileName === name);
    if (!mod) throw new Error('Mod not found');
    await launcher.mods.setEnabled(gameDir, name, enabled === true);
    if (mod.id) await launcher.mods.recordToggle(gameDir, mod.id, enabled === true);
  });

  handle('mods:remove', async (id: unknown, fileName: unknown) => {
    const iid = await instanceId(id);
    await launcher.mods.remove(launcher.instances.gameDir(iid), str(fileName, 'file name'));
  });

  handle('mods:add', async (id: unknown) => {
    const iid = await instanceId(id);
    const result = await dialog.showOpenDialog(win, { title: 'Add mods', properties: ['openFile', 'multiSelections'], filters: [{ name: 'Minecraft mods', extensions: ['jar'] }] });
    const errors: string[] = [];
    let added = 0;
    for (const file of result.canceled ? [] : result.filePaths) {
      try {
        await launcher.mods.install(launcher.instances.gameDir(iid), file);
        added++;
      } catch (err) {
        errors.push(`${join(file).split(/[\\/]/).pop()}: ${(err as Error).message}`);
      }
    }
    return { added, errors };
  });

  const modTarget = async (iid: string) => {
    const config = await launcher.instances.load(iid);
    return { gameDir: launcher.instances.gameDir(iid), minecraftVersion: config.minecraftVersion, loader: config.loader };
  };

  handle('browse:search', async (raw: Snowball.ModSearchOptions) => {
    const offset = Number.isInteger(raw?.offset) ? Math.min(Math.max(raw.offset, 0), 10_000) : 0;
    return launcher.modrinth.search({
      query: typeof raw?.query === 'string' ? str(raw.query, 'search text', 200) : '',
      loader: typeof raw?.loader === 'string' && MOD_LOADERS.includes(raw.loader) ? raw.loader : null,
      gameVersion: raw?.gameVersion ? str(raw.gameVersion, 'Minecraft version', 64) : null,
      sort: SEARCH_SORTS.includes(raw?.sort) ? raw.sort : 'relevance',
      offset,
      limit: 20,
    });
  });

  handle('browse:install', async (id: unknown, projectId: unknown) => {
    const iid = await instanceId(id);
    if (launcher.processes.isRunning(iid)) throw new Error('Stop the game before installing mods.');
    const project = str(projectId, 'project id', 64);
    if (!/^[A-Za-z0-9_.-]+$/.test(project)) throw new Error('Invalid project id');
    return launcher.modrinth.install(await modTarget(iid), project);
  });

  handle('mods:installed-projects', async (id: unknown) =>
    (await launcher.modrinth.installedProjects(launcher.instances.gameDir(await instanceId(id)))).map((p) => p.projectId));

  handle('mods:check-updates', async (id: unknown) => launcher.modrinth.checkUpdates(await modTarget(await instanceId(id))));

  handle('mods:update', async (id: unknown, fileName: unknown) => {
    const iid = await instanceId(id);
    if (launcher.processes.isRunning(iid)) throw new Error('Stop the game before updating mods.');
    return launcher.updateMod(iid, str(fileName, 'file name'));
  });

  handle('perf:apply', async (id: unknown, profile: unknown) => {
    const iid = await instanceId(id);
    if (!PROFILE_IDS.includes(profile as never)) throw new Error('Unknown performance profile');
    return launcher.applyPerformanceProfile(iid, profile as InstanceConfig['performanceProfile']);
  });

  handle('java:detect', () => launcher.java.detect());
  handle('java:validate', (path: unknown, major: unknown) => launcher.java.validateFor(str(path, 'Java path', 1024), typeof major === 'number' ? major : undefined));
  handle('java:browse', async () => {
    const result = await dialog.showOpenDialog(win, { title: 'Select Java executable', properties: ['openFile'], filters: process.platform === 'win32' ? [{ name: 'Java', extensions: ['exe'] }] : [] });
    return result.canceled ? null : result.filePaths[0] ?? null;
  });

  handle('game:launch', async (id: unknown) => {
    const iid = await instanceId(id);
    // Runs in the background so the window never blocks on downloads or installers.
    launcher.launch(iid).catch((err: Error) => send('launch-error', { instanceId: iid, message: err.message }));
  });
  handle('game:stop', async (id: unknown) => launcher.processes.kill(await instanceId(id)));
  handle('game:logs', async (id: unknown) => launcher.processes.recentLines(await instanceId(id)));

  handle('settings:update', async (patch: Partial<Snowball.Settings>) => {
    const updated = await launcher.settings.update((s) => {
      for (const key of ['appearance', 'downloads', 'java', 'game', 'accounts', 'updates', 'logs'] as const) {
        if (patch && typeof patch[key] === 'object' && patch[key] !== null) Object.assign(s[key], patch[key]);
      }
      if (patch && 'selectedInstanceId' in patch) s.selectedInstanceId = typeof patch.selectedInstanceId === 'string' ? patch.selectedInstanceId : null;
    });
    logSink.configure({ minLevel: updated.logs.debug ? 10 : 20 });
    return updated;
  });

  handle('accounts:add-offline', (name: unknown) => launcher.auth.addOffline(str(name, 'name', 16)));
  handle('accounts:remove', async (id: unknown) => {
    await launcher.auth.remove(str(id, 'account id', 64));
    if (launcher.settings.get().accounts.selectedAccountId === id) await launcher.settings.update((s) => (s.accounts.selectedAccountId = null));
  });
  handle('accounts:select', async (id: unknown) => {
    const aid = str(id, 'account id', 64);
    if (!launcher.auth.list().some((a) => a.id === aid)) throw new Error('Account not found');
    await launcher.settings.update((s) => (s.accounts.selectedAccountId = aid));
  });
  handle('accounts:sign-in-microsoft', async () => {
    const account = await launcher.auth.signInWithMicrosoft((request) => openMicrosoftLogin(win, request));
    await launcher.settings.update((s) => (s.accounts.selectedAccountId = account.id));
    return launcher.auth.list().find((a) => a.id === account.id);
  });

  handle('app:open-folder', async (folder: unknown) => {
    const path = folder === 'logs' ? launcher.paths.logs : launcher.paths.root;
    const error = await shell.openPath(path);
    if (error) throw new Error(error);
  });
}
