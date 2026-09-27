import { existsSync } from 'node:fs';
import { readdir, readFile, stat } from 'node:fs/promises';
import { homedir, platform } from 'node:os';
import { basename, isAbsolute, join } from 'node:path';
import { getLogger } from '../logging/Logger.js';
import type { LoaderId } from '../instance/InstanceManager.js';

const log = getLogger('import');

/** A file or folder an import can bring across. */
export type ImportPart = 'mods' | 'config' | 'resourcepacks' | 'shaderpacks' | 'saves' | 'options.txt' | 'servers.dat' | 'optionsof.txt';

export interface FoundInstance {
  /** Stable id for this candidate: the launcher and the folder it came from. */
  id: string;
  launcher: string;
  name: string;
  /** Empty when the folder does not say; the player then picks the version. */
  minecraftVersion: string;
  loader: LoaderId;
  loaderVersion: string | null;
  /** The folder that holds mods/, config/, saves/ - what actually gets copied. */
  gameDir: string;
  /**
   * Where a part lives when it is not inside gameDir, or null when this instance has none. PvP
   * clients keep their mods in a folder of their own and play in the .minecraft folder, so one
   * import can draw on two places.
   */
  sources?: Partial<Record<ImportPart, string | null>>;
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

type Kind = 'modrinth' | 'curseforge' | 'mmc' | 'atlauncher' | 'gdlauncher' | 'vanilla' | 'lunar' | 'feather';

const home = homedir();
const windows = platform() === 'win32';
const mac = platform() === 'darwin';
/** Where applications keep their data: AppData\Roaming, Application Support, or ~/.local/share. */
const appData = windows
  ? process.env.APPDATA ?? join(home, 'AppData', 'Roaming')
  : mac
    ? join(home, 'Library', 'Application Support')
    : process.env.XDG_DATA_HOME ?? join(home, '.local', 'share');
const localAppData = process.env.LOCALAPPDATA ?? join(home, 'AppData', 'Local');

/** The official launcher's game folder on this system. */
export function minecraftFolder(): string {
  if (windows) return join(appData, '.minecraft');
  if (mac) return join(appData, 'minecraft');
  return join(home, '.minecraft');
}

/** Where each launcher keeps its instances, on this computer. */
function searchRoots(): Array<{ launcher: string; dir: string; kind: Kind }> {
  const roots: Array<{ launcher: string; dir: string; kind: Kind }> = [];
  const add = (launcher: string, dir: string, kind: Kind) => {
    if (existsSync(dir) && !roots.some((r) => r.dir === dir)) roots.push({ launcher, dir, kind });
  };
  // The Modrinth App's data folder: its profiles, and the database that describes them.
  add('Modrinth App', join(appData, 'ModrinthApp'), 'modrinth');
  add('Modrinth App', join(appData, 'com.modrinth.theseus'), 'modrinth');
  add('CurseForge', join(home, 'curseforge', 'minecraft', 'Instances'), 'curseforge');
  add('CurseForge', join(home, 'Documents', 'curseforge', 'minecraft', 'Instances'), 'curseforge');
  add('Prism Launcher', join(appData, 'PrismLauncher', 'instances'), 'mmc');
  add('PolyMC', join(appData, 'PolyMC', 'instances'), 'mmc');
  add('MultiMC', join(appData, 'MultiMC', 'instances'), 'mmc');
  add('ATLauncher', join(appData, 'ATLauncher', 'instances'), 'atlauncher');
  add('GDLauncher', join(appData, 'gdlauncher_next', 'instances'), 'gdlauncher');
  add('GDLauncher', windows ? join(localAppData, 'gdlauncher_carbon', 'data', 'instances') : join(appData, 'gdlauncher_carbon', 'data', 'instances'), 'gdlauncher');
  add('Lunar Client', join(home, '.lunarclient'), 'lunar');
  add('Feather Client', join(appData, '.feather'), 'feather');
  // Dawn is Feather Client renamed (2026). Its own folder is read the same way when it has one.
  add('Dawn', join(appData, '.dawn'), 'feather');
  add('Minecraft Launcher', minecraftFolder(), 'vanilla');
  return roots;
}

async function countEntries(dir: string | null, filter?: (name: string) => boolean): Promise<number> {
  if (!dir) return 0;
  try {
    const entries = await readdir(dir);
    return filter ? entries.filter(filter).length : entries.length;
  } catch {
    return 0;
  }
}

const isJar = (f: string) => /\.jar(\.disabled)?$/i.test(f);

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

/** Where a part of an instance is, wherever its launcher keeps it; null when it has none. */
export function sourceOf(found: FoundInstance, part: ImportPart): string | null {
  const source = found.sources?.[part];
  return source === undefined ? join(found.gameDir, part) : source;
}

/** Fills in the counts shown beside each candidate. */
async function described(found: Omit<FoundInstance, 'mods' | 'worlds' | 'resourcePacks'>): Promise<FoundInstance> {
  const f = found as FoundInstance;
  return {
    ...found,
    mods: await countEntries(sourceOf(f, 'mods'), isJar),
    worlds: await countEntries(sourceOf(f, 'saves')),
    resourcePacks: await countEntries(sourceOf(f, 'resourcepacks')),
  };
}

/** Everything the launcher can find on this computer, newest-looking first. */
export async function detectInstances(): Promise<FoundInstance[]> {
  const found: FoundInstance[] = [];
  for (const root of searchRoots()) {
    try {
      if (root.kind === 'vanilla') {
        const instance = await readVanilla(root.launcher, root.dir);
        if (instance) found.push(instance);
      } else if (root.kind === 'lunar') {
        found.push(...(await readLunar(root.dir)));
      } else if (root.kind === 'feather') {
        found.push(...(await readFeather(root.launcher, root.dir)));
      } else if (root.kind === 'modrinth') {
        found.push(...(await readModrinth(root.dir)));
      } else {
        for (const entry of await readdir(root.dir, { withFileTypes: true })) {
          if (!entry.isDirectory()) continue;
          const instance = await readInstance(root.kind, root.launcher, join(root.dir, entry.name));
          if (instance) found.push(instance);
        }
      }
    } catch (err) {
      log.debug('Could not read a launcher folder', { dir: root.dir, error: String(err) });
    }
  }
  return found;
}

interface ProfileMeta {
  name: string;
  minecraftVersion: string;
  loader: LoaderId;
  loaderVersion: string | null;
}

/**
 * The Modrinth App keeps its profiles in a SQLite database (app.db) since version 0.8; the
 * profile.json files older versions wrote beside each profile are gone. Both database layouts are
 * read: the current one, where a profile is an instance whose version is in its applied content set,
 * and the earlier profiles table. The database is opened read-only; nothing else in it is read.
 */
export async function readModrinthDatabase(dataDir: string): Promise<{ profilesDir: string; profiles: Map<string, ProfileMeta> } | null> {
  const file = join(dataDir, 'app.db');
  if (!existsSync(file)) return null;
  let db: import('node:sqlite').DatabaseSync | undefined;
  try {
    const { DatabaseSync } = await import('node:sqlite');
    db = new DatabaseSync(file, { readOnly: true });
    const tables = new Set(db.prepare("select name from sqlite_master where type = 'table'").all().map((r) => String(r.name)));
    const columns = (table: string) => new Set(db!.prepare(`pragma table_info(${table})`).all().map((c) => String(c.name)));
    let rows: Array<Record<string, unknown>> = [];
    if (tables.has('instances') && tables.has('instance_content_sets')) {
      rows = db.prepare(`select i.path as path, i.name as name, s.game_version as game_version, s.loader as loader, s.loader_version as loader_version
        from instances i join instance_content_sets s on s.id = i.applied_content_set_id`).all() as Array<Record<string, unknown>>;
    } else if (tables.has('profiles') && columns('profiles').has('game_version')) {
      rows = db.prepare('select path, name, game_version, mod_loader as loader, mod_loader_version as loader_version from profiles').all() as Array<Record<string, unknown>>;
    }
    let profilesDir = join(dataDir, 'profiles');
    if (tables.has('settings') && columns('settings').has('custom_dir')) {
      const custom = db.prepare('select custom_dir from settings limit 1').get() as { custom_dir?: unknown } | undefined;
      if (typeof custom?.custom_dir === 'string' && custom.custom_dir && existsSync(join(custom.custom_dir, 'profiles'))) profilesDir = join(custom.custom_dir, 'profiles');
    }
    const profiles = new Map<string, ProfileMeta>();
    for (const row of rows) {
      const path = String(row.path ?? '');
      if (!path) continue;
      profiles.set(isAbsolute(path) ? basename(path) : path, {
        name: String(row.name ?? path),
        minecraftVersion: String(row.game_version ?? ''),
        loader: loaderFromName(String(row.loader ?? '')),
        loaderVersion: typeof row.loader_version === 'string' && row.loader_version ? row.loader_version : null,
      });
    }
    return { profilesDir, profiles };
  } catch (err) {
    log.debug('Could not read the Modrinth App database', { file, error: String(err) });
    return null;
  } finally {
    db?.close();
  }
}

async function readModrinth(dataDir: string): Promise<FoundInstance[]> {
  const database = await readModrinthDatabase(dataDir);
  const profilesDir = database?.profilesDir ?? join(dataDir, 'profiles');
  const found: FoundInstance[] = [];
  for (const entry of await readdir(profilesDir, { withFileTypes: true }).catch(() => [])) {
    if (!entry.isDirectory()) continue;
    const dir = join(profilesDir, entry.name);
    const meta = database?.profiles.get(entry.name) ?? (await readModrinthProfileJson(dir));
    if (!meta?.minecraftVersion) continue;
    found.push(await described({ id: `modrinth:${dir}`, launcher: 'Modrinth App', ...meta, gameDir: dir }));
  }
  return found;
}

/** Modrinth App before 0.8 described each profile in a profile.json beside it. */
async function readModrinthProfileJson(dir: string): Promise<ProfileMeta | null> {
  const profile = await readJsonFile(join(dir, 'profile.json'));
  if (!profile) return null;
  const meta = (profile.metadata as Record<string, any>) ?? profile;
  const loaderName = typeof meta.loader === 'string' ? meta.loader : (meta.loader as Record<string, any>)?.type;
  return {
    name: typeof meta.name === 'string' ? meta.name : basename(dir),
    minecraftVersion: String(meta.game_version ?? meta.gameVersion ?? ''),
    loader: loaderFromName(loaderName),
    loaderVersion: typeof meta.loader_version === 'string' ? meta.loader_version : (meta.loader_version as Record<string, any>)?.id ?? null,
  };
}

async function readInstance(kind: Kind, launcher: string, dir: string): Promise<FoundInstance | null> {
  let name = basename(dir);
  let minecraftVersion = '';
  let loader: LoaderId = 'vanilla';
  let loaderVersion: string | null = null;
  let gameDir = dir;

  if (kind === 'modrinth') {
    const meta = await readModrinthProfileJson(dir);
    if (!meta) return null;
    ({ name, minecraftVersion, loader, loaderVersion } = meta);
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
  return described({ id: `${kind}:${dir}`, launcher, name, minecraftVersion, loader, loaderVersion, gameDir });
}

const MINECRAFT_VERSION = /^\d+\.\d+(\.\d+)?$/;

/** Compares release numbers, 1.21.11 after 1.21.4 and 26.2 after both. */
export function compareVersions(a: string, b: string): number {
  const pa = a.split('.').map(Number);
  const pb = b.split('.').map(Number);
  for (let i = 0; i < Math.max(pa.length, pb.length); i++) {
    const d = (pa[i] ?? 0) - (pb[i] ?? 0);
    if (d !== 0) return d;
  }
  return 0;
}

/** Lunar names a profile after a line of versions; the ones people play without mods are these. */
const LUNAR_PROFILE_RELEASE: Record<string, string> = { '1.7': '1.7.10', '1.8': '1.8.9', '1.12': '1.12.2', '1.16': '1.16.5' };

/**
 * Lunar Client keeps one profile per line of versions (~/.lunarclient/profiles/1.21) with its game
 * options and server list, the player's own Fabric mods per exact version under
 * mods/fabric-<version>, and plays in a game folder of the player's choosing (.minecraft unless
 * changed), which is where worlds and most packs are. Each version with mods is offered on its own;
 * a profile without any is offered once, at its newest version, for its options, servers and packs.
 */
async function readLunar(root: string): Promise<FoundInstance[]> {
  const settings = await readJsonFile(join(root, 'settings', 'launcher.json'));
  const configured = settings?.settings?.gameDirectory;
  const gameFolder = typeof configured === 'string' && configured && existsSync(configured) ? configured : minecraftFolder();
  const found: FoundInstance[] = [];
  const profilesDir = join(root, 'profiles');
  for (const entry of await readdir(profilesDir, { withFileTypes: true }).catch(() => [])) {
    if (!entry.isDirectory() || (!MINECRAFT_VERSION.test(entry.name) && !/^\d+$/.test(entry.name))) continue;
    const profile = join(profilesDir, entry.name);
    const modFolders: Array<{ version: string; dir: string; jars: number }> = [];
    for (const folder of await readdir(join(profile, 'mods'), { withFileTypes: true }).catch(() => [])) {
      const match = /^fabric-(\d+\.\d+(?:\.\d+)?)$/.exec(folder.name);
      if (!folder.isDirectory() || !match) continue;
      const dir = join(profile, 'mods', folder.name);
      modFolders.push({ version: match[1], dir, jars: await countEntries(dir, isJar) });
    }
    const ownPacks = join(profile, 'resourcepacks');
    const packs = (await countEntries(ownPacks)) > 0 ? ownPacks : join(gameFolder, 'resourcepacks');
    const shared = (mods: string | null): Partial<Record<ImportPart, string | null>> => ({
      mods,
      config: join(gameFolder, 'config'),
      resourcepacks: packs,
      shaderpacks: join(gameFolder, 'shaderpacks'),
      saves: join(gameFolder, 'saves'),
      'options.txt': existsSync(join(profile, 'options.txt')) ? join(profile, 'options.txt') : join(gameFolder, 'options.txt'),
      'servers.dat': existsSync(join(profile, 'servers.dat')) ? join(profile, 'servers.dat') : join(gameFolder, 'servers.dat'),
      'optionsof.txt': join(profile, 'optionsof.txt'),
    });
    const withMods = modFolders.filter((f) => f.jars > 0).sort((a, b) => compareVersions(b.version, a.version));
    for (const folder of withMods) {
      found.push(await described({
        id: `lunar:${profile}:${folder.version}`, launcher: 'Lunar Client', name: `Lunar Client ${folder.version}`,
        minecraftVersion: folder.version, loader: 'fabric', loaderVersion: null, gameDir: profile, sources: shared(folder.dir),
      }));
    }
    if (withMods.length) continue;
    const newest = modFolders.map((f) => f.version).sort(compareVersions).pop();
    const version = LUNAR_PROFILE_RELEASE[entry.name] ?? newest;
    if (!version) continue;
    found.push(await described({
      id: `lunar:${profile}:${version}`, launcher: 'Lunar Client', name: `Lunar Client ${version}`,
      minecraftVersion: version, loader: 'fabric', loaderVersion: null, gameDir: profile, sources: shared(null),
    }));
  }
  return found;
}

/**
 * Feather Client (and Dawn, which it became) keeps the player's own mods per version under
 * user-mods/<version>-fabric and plays in the game folder named in its settings (.minecraft unless
 * changed). Each version with mods is offered, and the version it last launched is always offered.
 */
async function readFeather(launcher: string, root: string): Promise<FoundInstance[]> {
  const settings = await readJsonFile(join(root, 'settings.json'));
  const configured = settings?.mcPath;
  const gameFolder = typeof configured === 'string' && configured && existsSync(configured) ? configured : minecraftFolder();
  const last = typeof settings?.versionToLaunch === 'string' && MINECRAFT_VERSION.test(settings.versionToLaunch) ? settings.versionToLaunch : null;
  const byVersion = new Map<string, string>();
  for (const folder of await readdir(join(root, 'user-mods'), { withFileTypes: true }).catch(() => [])) {
    const match = /^(\d+\.\d+(?:\.\d+)?)-fabric$/.exec(folder.name);
    if (!folder.isDirectory() || !match) continue;
    const dir = join(root, 'user-mods', folder.name);
    if ((await countEntries(dir, isJar)) > 0 || match[1] === last) byVersion.set(match[1], dir);
  }
  if (last && !byVersion.has(last)) byVersion.set(last, join(root, 'user-mods', `${last}-fabric`));
  const found: FoundInstance[] = [];
  for (const [version, mods] of [...byVersion].sort((a, b) => compareVersions(b[0], a[0]))) {
    found.push(await described({
      id: `feather:${root}:${version}`, launcher, name: `${launcher} ${version}`,
      minecraftVersion: version, loader: 'fabric', loaderVersion: null, gameDir: gameFolder, sources: { mods },
    }));
  }
  return found;
}

export interface ParsedVersionId {
  minecraftVersion: string;
  loader: LoaderId;
  loaderVersion: string | null;
}

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

/** Clients that play straight out of the .minecraft folder rather than keeping instances. */
function clientsSharingMinecraftFolder(dir: string): string[] {
  const clients: string[] = [];
  if (existsSync(join(appData, 'Badlion Client')) || existsSync(join(dir, 'BLClient-Mod-Profiles'))) clients.push('Badlion');
  if (existsSync(join(dir, 'labymod-neo')) || existsSync(join(appData, 'LabyMod'))) clients.push('LabyMod');
  return clients;
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
  const sharing = clientsSharingMinecraftFolder(dir);
  // PvP clients leave no profile behind; the folder is still worth offering, with the version asked.
  if (!version && !sharing.length) return null;
  return described({
    id: `vanilla:${dir}`,
    launcher: sharing.length ? ['Minecraft Launcher', ...sharing].join(' / ') : launcher,
    name: sharing.length ? 'Your .minecraft folder' : 'Minecraft Launcher folder',
    minecraftVersion: version?.minecraftVersion ?? '',
    loader: version?.loader ?? 'fabric',
    loaderVersion: version?.loaderVersion ?? null,
    gameDir: dir,
  });
}

/**
 * A folder the player chose by hand, for a launcher Snowball does not know. Instance folders of the
 * known launchers are recognised by their files; anything else is taken as a game folder, and the
 * version is read from it where it says, or left for the player to pick.
 */
export async function inspectFolder(dir: string): Promise<FoundInstance | null> {
  if (!existsSync(dir)) return null;
  const markers: Array<[string, Kind, string]> = [
    ['mmc-pack.json', 'mmc', 'Prism / MultiMC'],
    ['minecraftinstance.json', 'curseforge', 'CurseForge'],
    ['profile.json', 'modrinth', 'Modrinth App'],
    ['instance.json', 'atlauncher', 'ATLauncher'],
    ['config.json', 'gdlauncher', 'GDLauncher'],
  ];
  for (const [file, kind, launcher] of markers) {
    if (!existsSync(join(dir, file))) continue;
    const instance = await readInstance(kind, launcher, dir);
    if (instance) return { ...instance, id: `folder:${dir}` };
  }
  const vanilla = existsSync(join(dir, 'launcher_profiles.json')) ? await readVanilla('Chosen folder', dir) : null;
  return described({
    id: `folder:${dir}`,
    launcher: 'Chosen folder',
    name: basename(dir) || 'Imported',
    minecraftVersion: vanilla?.minecraftVersion ?? '',
    loader: vanilla?.loader ?? 'fabric',
    loaderVersion: vanilla?.loaderVersion ?? null,
    gameDir: dir,
  });
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
  let total = 0;
  for (const part of partsFor(options)) {
    total += await folderSize(sourceOf(instance, part));
  }
  return total;
}

export function foldersFor(options: ImportOptions): ImportPart[] {
  const folders: ImportPart[] = [];
  if (options.mods) folders.push('mods');
  if (options.config) folders.push('config');
  if (options.resourcePacks) folders.push('resourcepacks');
  if (options.shaderPacks) folders.push('shaderpacks');
  if (options.saves) folders.push('saves');
  return folders;
}

/** Every file and folder an import with these options copies. */
export function partsFor(options: ImportOptions): ImportPart[] {
  return [...foldersFor(options), ...(options.options ? (['options.txt', 'servers.dat', 'optionsof.txt'] as ImportPart[]) : [])];
}

async function folderSize(path: string | null): Promise<number> {
  if (!path) return 0;
  const info = await stat(path).catch(() => null);
  if (!info) return 0;
  if (!info.isDirectory()) return info.size;
  let total = 0;
  for (const entry of await readdir(path, { withFileTypes: true }).catch(() => [])) {
    total += await folderSize(join(path, entry.name));
  }
  return total;
}
