import { existsSync } from 'node:fs';
import { readdir, readFile, stat } from 'node:fs/promises';
import { homedir } from 'node:os';
import { basename, join } from 'node:path';
import { getLogger } from '../logging/Logger.js';
import type { LoaderId } from '../instance/InstanceManager.js';

const log = getLogger('import');

export interface FoundInstance {
  /** Stable id for this candidate: the launcher and the folder it came from. */
  id: string;
  launcher: string;
  name: string;
  minecraftVersion: string;
  loader: LoaderId;
  loaderVersion: string | null;
  /** The folder that holds mods/, config/, saves/ - what actually gets copied. */
  gameDir: string;
  mods: number;
  worlds: number;
  resourcePacks: number;
}

/** What a copy brings across; saves and packs are optional because they can be large. */
export interface ImportOptions {
  mods: boolean;
  config: boolean;
  resourcePacks: boolean;
  shaderPacks: boolean;
  saves: boolean;
  options: boolean;
}

export const DEFAULT_IMPORT: ImportOptions = { mods: true, config: true, resourcePacks: true, shaderPacks: true, saves: true, options: true };

const appData = process.env.APPDATA ?? join(homedir(), 'AppData', 'Roaming');
const localAppData = process.env.LOCALAPPDATA ?? join(homedir(), 'AppData', 'Local');

/** Where each launcher keeps its instances, on this computer. */
function searchRoots(): Array<{ launcher: string; dir: string; kind: 'modrinth' | 'curseforge' | 'mmc' | 'atlauncher' | 'gdlauncher' | 'vanilla' }> {
  const roots: Array<{ launcher: string; dir: string; kind: 'modrinth' | 'curseforge' | 'mmc' | 'atlauncher' | 'gdlauncher' | 'vanilla' }> = [];
  const add = (launcher: string, dir: string, kind: 'modrinth' | 'curseforge' | 'mmc' | 'atlauncher' | 'gdlauncher' | 'vanilla') => {
    if (existsSync(dir)) roots.push({ launcher, dir, kind });
  };
  add('Modrinth App', join(appData, 'com.modrinth.theseus', 'profiles'), 'modrinth');
  add('Modrinth App', join(appData, 'ModrinthApp', 'profiles'), 'modrinth');
  add('CurseForge', join(homedir(), 'curseforge', 'minecraft', 'Instances'), 'curseforge');
  add('CurseForge', join(homedir(), 'Documents', 'curseforge', 'minecraft', 'Instances'), 'curseforge');
  add('Prism Launcher', join(appData, 'PrismLauncher', 'instances'), 'mmc');
  add('PolyMC', join(appData, 'PolyMC', 'instances'), 'mmc');
  add('MultiMC', join(appData, 'MultiMC', 'instances'), 'mmc');
  add('ATLauncher', join(appData, 'ATLauncher', 'instances'), 'atlauncher');
  add('GDLauncher', join(appData, 'gdlauncher_next', 'instances'), 'gdlauncher');
  add('GDLauncher', join(localAppData, 'gdlauncher_carbon', 'data', 'instances'), 'gdlauncher');
  add('Minecraft Launcher', join(appData, '.minecraft'), 'vanilla');
  return roots;
}

async function countEntries(dir: string, filter?: (name: string) => boolean): Promise<number> {
  try {
    const entries = await readdir(dir);
    return filter ? entries.filter(filter).length : entries.length;
  } catch {
    return 0;
  }
}

async function readJsonFile(path: string): Promise<Record<string, any> | null> {
  try {
    return JSON.parse(await readFile(path, 'utf8')) as Record<string, any>;
  } catch {
    return null;
  }
}

function loaderFromName(name: string | undefined | null): LoaderId {
  const lower = (name ?? '').toLowerCase();
  if (lower.includes('neoforge')) return 'neoforge';
  if (lower.includes('forge')) return 'forge';
  if (lower.includes('quilt')) return 'quilt';
  if (lower.includes('fabric')) return 'fabric';
  return 'vanilla';
}

/** Everything the launcher can find on this computer, newest-looking first. */
export async function detectInstances(): Promise<FoundInstance[]> {
  const found: FoundInstance[] = [];
  for (const root of searchRoots()) {
    try {
      if (root.kind === 'vanilla') {
        const instance = await readVanilla(root.launcher, root.dir);
        if (instance) found.push(instance);
        continue;
      }
      for (const entry of await readdir(root.dir, { withFileTypes: true })) {
        if (!entry.isDirectory()) continue;
        const dir = join(root.dir, entry.name);
        const instance = await readInstance(root.kind, root.launcher, dir);
        if (instance) found.push(instance);
      }
    } catch (err) {
      log.debug('Could not read a launcher folder', { dir: root.dir, error: String(err) });
    }
  }
  return found;
}

async function readInstance(kind: string, launcher: string, dir: string): Promise<FoundInstance | null> {
  let name = basename(dir);
  let minecraftVersion = '';
  let loader: LoaderId = 'vanilla';
  let loaderVersion: string | null = null;
  let gameDir = dir;

  if (kind === 'modrinth') {
    const profile = (await readJsonFile(join(dir, 'profile.json'))) ?? {};
    const meta = (profile.metadata as Record<string, any>) ?? profile;
    name = typeof meta.name === 'string' ? meta.name : name;
    minecraftVersion = String(meta.game_version ?? meta.gameVersion ?? '');
    const loaderName = typeof meta.loader === 'string' ? meta.loader : (meta.loader as Record<string, any>)?.type;
    loader = loaderFromName(loaderName);
    loaderVersion = typeof meta.loader_version === 'string' ? meta.loader_version : (meta.loader_version as Record<string, any>)?.id ?? null;
  } else if (kind === 'curseforge') {
    const profile = await readJsonFile(join(dir, 'minecraftinstance.json'));
    if (!profile) return null;
    name = typeof profile.name === 'string' ? profile.name : name;
    minecraftVersion = String(profile.gameVersion ?? profile.baseModLoader?.minecraftVersion ?? '');
    loader = loaderFromName(profile.baseModLoader?.name);
    loaderVersion = typeof profile.baseModLoader?.forgeVersion === 'string' ? profile.baseModLoader.forgeVersion : null;
  } else if (kind === 'mmc') {
    const pack = await readJsonFile(join(dir, 'mmc-pack.json'));
    if (!pack) return null;
    const components = Array.isArray(pack.components) ? (pack.components as Array<Record<string, any>>) : [];
    minecraftVersion = String(components.find((c) => c.uid === 'net.minecraft')?.version ?? '');
    const loaderComponent = components.find((c) => typeof c.uid === 'string' && /fabric-loader|quilt-loader|minecraftforge|neoforge/.test(c.uid));
    loader = loaderFromName(loaderComponent?.uid);
    loaderVersion = loaderComponent ? String(loaderComponent.version ?? '') || null : null;
    name = (await readIniValue(join(dir, 'instance.cfg'), 'name')) ?? name;
    gameDir = existsSync(join(dir, '.minecraft')) ? join(dir, '.minecraft') : join(dir, 'minecraft');
  } else if (kind === 'atlauncher') {
    const profile = await readJsonFile(join(dir, 'instance.json'));
    if (!profile) return null;
    name = typeof profile.launcher?.name === 'string' ? profile.launcher.name : name;
    minecraftVersion = String(profile.id ?? profile.launcher?.version ?? '');
    loader = loaderFromName(profile.launcher?.loaderVersion?.type);
    loaderVersion = typeof profile.launcher?.loaderVersion?.version === 'string' ? profile.launcher.loaderVersion.version : null;
  } else if (kind === 'gdlauncher') {
    const profile = (await readJsonFile(join(dir, 'config.json'))) ?? (await readJsonFile(join(dir, 'instance.json')));
    if (!profile) return null;
    minecraftVersion = String(profile.loader?.mcVersion ?? profile.mcVersion ?? '');
    loader = loaderFromName(profile.loader?.loaderType ?? profile.modloader);
    loaderVersion = typeof profile.loader?.loaderVersion === 'string' ? profile.loader.loaderVersion : null;
  } else {
    return null;
  }

  if (!minecraftVersion || !existsSync(gameDir)) return null;
  return {
    id: `${kind}:${dir}`,
    launcher,
    name,
    minecraftVersion,
    loader,
    loaderVersion,
    gameDir,
    mods: await countEntries(join(gameDir, 'mods'), (f) => /\.jar(\.disabled)?$/i.test(f)),
    worlds: await countEntries(join(gameDir, 'saves')),
    resourcePacks: await countEntries(join(gameDir, 'resourcepacks')),
  };
}

/** The official launcher has no instances, so its .minecraft folder is offered as one. */
async function readVanilla(launcher: string, dir: string): Promise<FoundInstance | null> {
  const profiles = await readJsonFile(join(dir, 'launcher_profiles.json'));
  const all = profiles ? Object.values((profiles.profiles as Record<string, any>) ?? {}) : [];
  const latest = all
    .filter((p) => typeof (p as Record<string, any>).lastVersionId === 'string')
    .sort((a, b) => String((b as Record<string, any>).lastUsed ?? '').localeCompare(String((a as Record<string, any>).lastUsed ?? '')))[0] as Record<string, any> | undefined;
  const versionId = String(latest?.lastVersionId ?? '');
  const minecraftVersion = /^\d+\.\d+(\.\d+)?$/.test(versionId) ? versionId : (versionId.match(/\d+\.\d+(\.\d+)?/)?.[0] ?? '');
  if (!minecraftVersion) return null;
  const mods = await countEntries(join(dir, 'mods'), (f) => /\.jar(\.disabled)?$/i.test(f));
  return {
    id: `vanilla:${dir}`,
    launcher,
    name: 'Minecraft Launcher folder',
    minecraftVersion,
    loader: loaderFromName(versionId),
    loaderVersion: null,
    gameDir: dir,
    mods,
    worlds: await countEntries(join(dir, 'saves')),
    resourcePacks: await countEntries(join(dir, 'resourcepacks')),
  };
}

async function readIniValue(file: string, key: string): Promise<string | null> {
  try {
    for (const line of (await readFile(file, 'utf8')).split(/\r?\n/)) {
      const [name, ...rest] = line.split('=');
      if (name.trim() === key) return rest.join('=').trim() || null;
    }
  } catch {
    // The file is optional; the folder name is used instead.
  }
  return null;
}

/** Rough size of what an import would copy, so the user is told before it starts. */
export async function importSize(instance: FoundInstance, options: ImportOptions): Promise<number> {
  const folders = foldersFor(options);
  let total = 0;
  for (const folder of folders) {
    total += await folderSize(join(instance.gameDir, folder));
  }
  return total;
}

export function foldersFor(options: ImportOptions): string[] {
  const folders: string[] = [];
  if (options.mods) folders.push('mods');
  if (options.config) folders.push('config');
  if (options.resourcePacks) folders.push('resourcepacks');
  if (options.shaderPacks) folders.push('shaderpacks');
  if (options.saves) folders.push('saves');
  return folders;
}

async function folderSize(dir: string): Promise<number> {
  let total = 0;
  let entries: Array<{ name: string; isDirectory(): boolean }> = [];
  try {
    entries = await readdir(dir, { withFileTypes: true });
  } catch {
    return 0;
  }
  for (const entry of entries) {
    const path = join(dir, entry.name);
    if (entry.isDirectory()) total += await folderSize(path);
    else total += (await stat(path).catch(() => ({ size: 0 }))).size;
  }
  return total;
}
