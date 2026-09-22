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

export interface ParsedVersionId {
  minecraftVersion: string;
  loader: LoaderId;
  loaderVersion: string | null;
}

const MINECRAFT_VERSION = /^\d+\.\d+(\.\d+)?$/;

/**
 * Reads a version id from the official launcher's versions folder.
 *
 * Loader ids carry two version numbers and the loader's comes first - "fabric-loader-0.19.5-1.21.11"
 * - so taking the first number that looks like a version picks Fabric's, not Minecraft's. Each
 * loader's naming is read on its own terms instead.
 */
export function parseVersionId(id: string): ParsedVersionId | null {
  const versionId = id.trim();
  if (MINECRAFT_VERSION.test(versionId)) return { minecraftVersion: versionId, loader: 'vanilla', loaderVersion: null };

  const fabricLike = /^(fabric|quilt)-loader-([^-]+)-(.+)$/.exec(versionId);
  if (fabricLike && MINECRAFT_VERSION.test(fabricLike[3])) {
    return { minecraftVersion: fabricLike[3], loader: fabricLike[1] === 'quilt' ? 'quilt' : 'fabric', loaderVersion: fabricLike[2] };
  }

  // NeoForge numbers itself after Minecraft without the leading "1.": 21.1.77 is for 1.21.1.
  const neoforge = /^neoforge-(\d+)\.(\d+)\.([\w.+-]+)$/.exec(versionId);
  if (neoforge) {
    const [major, minor] = [Number(neoforge[1]), Number(neoforge[2])];
    const minecraftVersion = major >= 26 ? `${major}.${minor}` : minor === 0 ? `1.${major}` : `1.${major}.${minor}`;
    return { minecraftVersion, loader: 'neoforge', loaderVersion: `${neoforge[1]}.${neoforge[2]}.${neoforge[3]}` };
  }

  // Forge puts Minecraft first: 1.20.1-forge-47.3.0, or 1.8.9-forge1.8.9-11.15.1.2318-1.8.9.
  const forge = /^(\d+\.\d+(?:\.\d+)?)-forge-?(?:\1-)?([\w.]+)/.exec(versionId);
  if (forge) return { minecraftVersion: forge[1], loader: 'forge', loaderVersion: forge[2] };

  // Anything else built on a release (OptiFine and the like) still names that release first.
  const leading = /^(\d+\.\d+(?:\.\d+)?)-/.exec(versionId);
  if (leading) return { minecraftVersion: leading[1], loader: loaderFromName(versionId), loaderVersion: null };
  return null;
}

/**
 * The version a profile really runs. A modded version's own json names the release it is built on
 * (inheritsFrom), which is more reliable than any reading of its name, so that is asked first.
 */
async function resolveVersionId(dir: string, versionId: string): Promise<ParsedVersionId | null> {
  const parsed = parseVersionId(versionId);
  const json = await readJsonFile(join(dir, 'versions', versionId, `${versionId}.json`));
  const base = typeof json?.inheritsFrom === 'string' ? json.inheritsFrom.trim() : '';
  if (MINECRAFT_VERSION.test(base)) {
    return { minecraftVersion: base, loader: parsed?.loader ?? loaderFromName(versionId), loaderVersion: parsed?.loaderVersion ?? null };
  }
  return parsed;
}

/** Just the official launcher's folder, without walking every other launcher's instances. */
export async function detectOfficialLauncher(): Promise<FoundInstance | null> {
  const root = searchRoots().find((r) => r.kind === 'vanilla');
  return root ? readVanilla(root.launcher, root.dir).catch(() => null) : null;
}

/** The official launcher has no instances, so its .minecraft folder is offered as one. */
async function readVanilla(launcher: string, dir: string): Promise<FoundInstance | null> {
  const profiles = await readJsonFile(join(dir, 'launcher_profiles.json'));
  const all = (profiles ? Object.values((profiles.profiles as Record<string, any>) ?? {}) : []) as Array<Record<string, any>>;
  // The profile played most recently that names a version this launcher can read.
  const byLastUsed = all
    .filter((p) => typeof p.lastVersionId === 'string')
    .sort((a, b) => String(b.lastUsed ?? '').localeCompare(String(a.lastUsed ?? '')));
  let version: ParsedVersionId | null = null;
  for (const profile of byLastUsed) {
    version = await resolveVersionId(dir, String(profile.lastVersionId));
    if (version) break;
  }
  if (!version) return null;
  const mods = await countEntries(join(dir, 'mods'), (f) => /\.jar(\.disabled)?$/i.test(f));
  return {
    id: `vanilla:${dir}`,
    launcher,
    name: 'Minecraft Launcher folder',
    minecraftVersion: version.minecraftVersion,
    loader: version.loader,
    loaderVersion: version.loaderVersion,
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
