import { randomUUID } from 'node:crypto';
import { existsSync } from 'node:fs';
import { cp, mkdir, readdir, rename, rm } from 'node:fs/promises';
import { join } from 'node:path';
import { getLogger } from '../logging/Logger.js';
import { readJson, writeJsonAtomic } from '../util/fsutil.js';
import { safeJoin, sanitizeFileName } from '../util/paths.js';

const log = getLogger('instances');

export const INSTANCE_SCHEMA_VERSION = 1;
export const LOADER_IDS = ['vanilla', 'fabric', 'quilt', 'forge', 'neoforge'] as const;
export type LoaderId = (typeof LOADER_IDS)[number];
export const PERFORMANCE_PROFILES = ['none', 'balanced', 'fps-boost', 'maximum-fps', 'visual-quality'] as const;
export type PerformanceProfileId = (typeof PERFORMANCE_PROFILES)[number];

/** Folders created inside each instance's isolated game directory. */
export const GAME_SUBDIRS = ['mods', 'config', 'resourcepacks', 'shaderpacks', 'saves', 'screenshots', 'logs'] as const;

export interface InstanceConfig {
  schemaVersion: number;
  id: string;
  name: string;
  icon: string;
  minecraftVersion: string;
  loader: LoaderId;
  loaderVersion: string | null;
  java: {
    /** null = pick automatically from the version's required Java. */
    executable: string | null;
    requiredMajor: number | null;
  };
  memory: { minMb: number; maxMb: number };
  jvmArgs: string[];
  gameArgs: string[];
  window: { width: number; height: number; fullscreen: boolean };
  clientProfile: 'snowballclient' | 'none';
  performanceProfile: PerformanceProfileId;
  /** Filenames managed by the launcher (e.g. installed by a performance profile). */
  managedMods: string[];
  /** True until Fabric API has been added once to a new Fabric instance; removing it afterwards is respected. */
  pendingFabricApi: boolean;
  timestamps: { created: string; lastPlayed: string | null; totalPlayMs: number };
}

export interface InstanceSummary {
  config: InstanceConfig;
  directory: string;
  gameDir: string;
  /** Present when instance.json could not be read; the instance is shown but not launchable. */
  error?: string;
}

export interface CreateInstanceOptions {
  name: string;
  minecraftVersion: string;
  loader?: LoaderId;
  loaderVersion?: string | null;
  icon?: string;
  memory?: { minMb: number; maxMb: number };
  clientProfile?: InstanceConfig['clientProfile'];
  performanceProfile?: PerformanceProfileId;
}

export class InstanceError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'InstanceError';
  }
}

function defaults(): Omit<InstanceConfig, 'id' | 'name' | 'minecraftVersion'> {
  return {
    schemaVersion: INSTANCE_SCHEMA_VERSION,
    icon: 'crystal',
    loader: 'vanilla',
    loaderVersion: null,
    java: { executable: null, requiredMajor: null },
    memory: { minMb: 1024, maxMb: 4096 },
    jvmArgs: [],
    gameArgs: [],
    window: { width: 1280, height: 720, fullscreen: false },
    clientProfile: 'none',
    performanceProfile: 'none',
    managedMods: [],
    pendingFabricApi: false,
    timestamps: { created: new Date().toISOString(), lastPlayed: null, totalPlayMs: 0 },
  };
}

const clampInt = (v: unknown, min: number, max: number, fallback: number): number =>
  typeof v === 'number' && Number.isFinite(v) ? Math.min(max, Math.max(min, Math.round(v))) : fallback;

const stringArray = (v: unknown): string[] =>
  Array.isArray(v) ? v.filter((x): x is string => typeof x === 'string' && !/[\0\r\n]/.test(x)).slice(0, 200) : [];

/**
 * Normalises untrusted instance.json content into a valid config. Unknown fields are dropped,
 * invalid values fall back to defaults, and older schema versions are migrated forward.
 */
export function normalizeInstanceConfig(raw: unknown, fallbackId: string): InstanceConfig {
  if (!raw || typeof raw !== 'object' || Array.isArray(raw)) throw new InstanceError('instance.json must contain a JSON object');
  const r = raw as Record<string, any>;
  const d = defaults();
  const version = typeof r.minecraftVersion === 'string' ? r.minecraftVersion : typeof r.version === 'string' ? r.version : null;
  if (!version || !/^[A-Za-z0-9._+\- ]{1,64}$/.test(version)) throw new InstanceError('instance.json has no valid minecraftVersion');

  const loader: LoaderId = LOADER_IDS.includes(r.loader) ? r.loader : 'vanilla';
  const memMax = clampInt(r.memory?.maxMb, 512, 262144, d.memory.maxMb);
  const memMin = Math.min(clampInt(r.memory?.minMb, 256, 262144, d.memory.minMb), memMax);
  return {
    schemaVersion: INSTANCE_SCHEMA_VERSION,
    id: typeof r.id === 'string' && /^[A-Za-z0-9._\- ]{1,64}$/.test(r.id) ? r.id : fallbackId,
    name: typeof r.name === 'string' && r.name.trim() ? r.name.trim().slice(0, 64) : fallbackId,
    icon: typeof r.icon === 'string' && /^[a-z0-9_-]{1,32}$/.test(r.icon) ? r.icon : d.icon,
    minecraftVersion: version,
    loader,
    loaderVersion: loader !== 'vanilla' && typeof r.loaderVersion === 'string' && /^[A-Za-z0-9._+\-]{1,64}$/.test(r.loaderVersion) ? r.loaderVersion : null,
    java: {
      executable: typeof r.java?.executable === 'string' && r.java.executable ? r.java.executable : null,
      requiredMajor: typeof r.java?.requiredMajor === 'number' ? clampInt(r.java.requiredMajor, 5, 99, 21) : null,
    },
    memory: { minMb: memMin, maxMb: memMax },
    jvmArgs: stringArray(r.jvmArgs),
    gameArgs: stringArray(r.gameArgs),
    window: {
      width: clampInt(r.window?.width, 320, 16384, d.window.width),
      height: clampInt(r.window?.height, 240, 16384, d.window.height),
      fullscreen: r.window?.fullscreen === true,
    },
    clientProfile: r.clientProfile === 'snowballclient' ? 'snowballclient' : 'none',
    performanceProfile: PERFORMANCE_PROFILES.includes(r.performanceProfile) ? r.performanceProfile : 'none',
    managedMods: stringArray(r.managedMods),
    pendingFabricApi: loader === 'fabric' && r.pendingFabricApi === true,
    timestamps: {
      created: typeof r.timestamps?.created === 'string' ? r.timestamps.created : d.timestamps.created,
      lastPlayed: typeof r.timestamps?.lastPlayed === 'string' ? r.timestamps.lastPlayed : null,
      totalPlayMs: clampInt(r.timestamps?.totalPlayMs, 0, Number.MAX_SAFE_INTEGER, 0),
    },
  };
}

export class InstanceManager {
  constructor(private readonly instancesDir: string) {}

  instanceDir(id: string): string {
    return safeJoin(this.instancesDir, id);
  }

  gameDir(id: string): string {
    return join(this.instanceDir(id), 'minecraft');
  }

  async list(): Promise<InstanceSummary[]> {
    if (!existsSync(this.instancesDir)) return [];
    const entries = await readdir(this.instancesDir, { withFileTypes: true });
    const out: InstanceSummary[] = [];
    for (const entry of entries) {
      if (!entry.isDirectory() || entry.name.startsWith('.')) continue;
      out.push(await this.loadSummary(entry.name));
    }
    return out.sort((a, b) => (b.config.timestamps.lastPlayed ?? '').localeCompare(a.config.timestamps.lastPlayed ?? '') || a.config.name.localeCompare(b.config.name));
  }

  private async loadSummary(id: string): Promise<InstanceSummary> {
    const directory = this.instanceDir(id);
    try {
      return { config: await this.load(id), directory, gameDir: this.gameDir(id) };
    } catch (err) {
      log.warn(`Instance ${id} could not be loaded`, { error: (err as Error).message });
      const placeholder: InstanceConfig = { ...defaults(), id, name: id, minecraftVersion: 'unknown' };
      return { config: placeholder, directory, gameDir: this.gameDir(id), error: (err as Error).message };
    }
  }

  async load(id: string): Promise<InstanceConfig> {
    const result = await readJson(join(this.instanceDir(id), 'instance.json'));
    if (!result.ok) {
      throw new InstanceError(result.reason === 'missing' ? `Instance ${id} has no instance.json` : `instance.json for ${id} is malformed: ${result.error}`);
    }
    const config = normalizeInstanceConfig(result.value, id);
    // The folder name is authoritative so a copied instance cannot alias another.
    config.id = id;
    return config;
  }

  async save(config: InstanceConfig): Promise<void> {
    const normalized = normalizeInstanceConfig(config, config.id);
    const dir = this.instanceDir(normalized.id);
    if (!existsSync(dir)) throw new InstanceError(`Instance ${normalized.id} does not exist`);
    await writeJsonAtomic(join(dir, 'instance.json'), normalized);
  }

  async update(id: string, patch: (config: InstanceConfig) => void): Promise<InstanceConfig> {
    const config = await this.load(id);
    patch(config);
    config.id = id;
    await this.save(config);
    return this.load(id);
  }

  private async uniqueId(name: string): Promise<string> {
    const base = sanitizeFileName(name, 'Instance');
    let id = base;
    for (let i = 2; existsSync(safeJoin(this.instancesDir, id)); i++) id = `${base} (${i})`;
    return id;
  }

  async create(options: CreateInstanceOptions): Promise<InstanceConfig> {
    if (!options.name?.trim()) throw new InstanceError('Instance name is required');
    await mkdir(this.instancesDir, { recursive: true });
    const id = await this.uniqueId(options.name);
    const dir = this.instanceDir(id);
    const config = normalizeInstanceConfig(
      {
        ...defaults(),
        id,
        name: options.name,
        minecraftVersion: options.minecraftVersion,
        loader: options.loader ?? 'vanilla',
        loaderVersion: options.loaderVersion ?? null,
        icon: options.icon,
        memory: options.memory,
        clientProfile: options.clientProfile ?? 'none',
        performanceProfile: options.performanceProfile ?? 'none',
        pendingFabricApi: options.loader === 'fabric',
      },
      id,
    );
    await mkdir(dir, { recursive: true });
    for (const sub of GAME_SUBDIRS) await mkdir(join(dir, 'minecraft', sub), { recursive: true });
    await writeJsonAtomic(join(dir, 'instance.json'), config);
    log.info(`Created instance ${id}`, { version: config.minecraftVersion, loader: config.loader });
    return config;
  }

  async clone(id: string, newName: string): Promise<InstanceConfig> {
    const source = await this.load(id);
    const newId = await this.uniqueId(newName);
    await cp(this.instanceDir(id), this.instanceDir(newId), { recursive: true, errorOnExist: true });
    return this.update(newId, (c) => {
      c.name = newName;
      c.timestamps = { created: new Date().toISOString(), lastPlayed: null, totalPlayMs: 0 };
      void source;
    });
  }

  /**
   * Deletes an instance. The folder is first renamed to a hidden tombstone so a partially
   * failed delete never leaves a half-valid instance behind.
   */
  async delete(id: string): Promise<void> {
    const dir = this.instanceDir(id);
    if (!existsSync(dir)) throw new InstanceError(`Instance ${id} does not exist`);
    const tomb = safeJoin(this.instancesDir, `.deleting-${randomUUID()}`);
    await rename(dir, tomb);
    await rm(tomb, { recursive: true, force: true });
    log.info(`Deleted instance ${id}`);
  }

  async markPlayed(id: string, sessionMs: number): Promise<void> {
    await this.update(id, (c) => {
      c.timestamps.lastPlayed = new Date().toISOString();
      c.timestamps.totalPlayMs += Math.max(0, Math.round(sessionMs));
    });
  }

  /** Ensures the isolated directory layout exists (repairs instances copied in by hand). */
  async ensureLayout(id: string): Promise<void> {
    for (const sub of GAME_SUBDIRS) await mkdir(join(this.gameDir(id), sub), { recursive: true });
  }
}
