import { existsSync } from 'node:fs';
import { copyFile, mkdir, readdir, rename, rm } from 'node:fs/promises';
import { basename, dirname, join } from 'node:path';
import type { LoaderId } from '../instance/InstanceManager.js';
import type { ActivityLevel } from '../logging/Activity.js';
import { getLogger } from '../logging/Logger.js';
import { fabricApiFor } from '../minecraft/fabricFamily.js';
import { compareVersions, matchesFabricPredicate } from '../mods/versionRange.js';
import { sha1File, writeJsonAtomic } from '../util/fsutil.js';
import { sanitizeFileName } from '../util/paths.js';
import { ZipReader } from '../util/zip.js';
import { SNOWBALL_MARKER, SNOWBALL_MOD_FILE, SNOWBALL_MOD_ID } from './protection.js';

const log = getLogger('snowball');

/** One Snowball Client jar shipped with the launcher, built for a range of Minecraft versions. */
export interface ClientBuild {
  path: string;
  version: string;
  /** The jar's fabric.mod.json "minecraft" dependency, e.g. "~26.2". */
  minecraft: string | string[];
  /** Minecraft versions as players read them, e.g. "26.2". */
  label: string;
  /** Java version this build needs, from its "java" dependency; null when it does not ask for one. */
  javaMajor: number | null;
  sha1: string;
}

interface JarIdentity {
  id: string | null;
  version: string | null;
  minecraft: unknown;
  java: unknown;
}

/** Reads id, version and Minecraft dependency from a Fabric jar; null when it is not a readable Fabric mod. */
async function readIdentity(path: string): Promise<JarIdentity | null> {
  try {
    const text = (await ZipReader.open(path)).readText('fabric.mod.json');
    if (!text) return null;
    const j = JSON.parse(text.replace(/^﻿/, '')) as Record<string, any>;
    return { id: typeof j.id === 'string' ? j.id : null, version: typeof j.version === 'string' ? j.version : null, minecraft: j.depends?.minecraft, java: j.depends?.java };
  } catch {
    // Corrupt or non-jar files simply aren't client builds or client copies.
    return null;
  }
}

/**
 * The Snowball Client builds this launcher ships. Builds are discovered from their own metadata, so
 * supporting another Minecraft version means adding its jar to the client folder, not changing code.
 */
export class ClientBuildRegistry {
  constructor(readonly builds: ClientBuild[]) {}

  static async discover(dirs: string[]): Promise<ClientBuildRegistry> {
    const found: ClientBuild[] = [];
    for (const dir of dirs) {
      if (!existsSync(dir)) continue;
      for (const name of await readdir(dir)) {
        if (!/\.jar$/i.test(name) || /-(sources|dev|javadoc)\.jar$/i.test(name)) continue;
        const path = join(dir, name);
        const identity = await readIdentity(path);
        const minecraft = identity?.minecraft;
        const validRange = typeof minecraft === 'string' || (Array.isArray(minecraft) && minecraft.length > 0 && minecraft.every((v) => typeof v === 'string'));
        // Unprocessed jars (e.g. a sources jar with "${version}") are not real builds.
        if (identity?.id !== SNOWBALL_MOD_ID || !identity.version || identity.version.includes('${') || !validRange) continue;
        const range = minecraft as string | string[];
        const label = [range].flat().map((p) => p.replace(/^[~^>=<\s]+/, '')).join(', ');
        const javaMajor = typeof identity.java === 'string' ? Number(/(\d+)/.exec(identity.java)?.[1]) : NaN;
        found.push({ path, version: identity.version, minecraft: range, label, javaMajor: Number.isFinite(javaMajor) ? javaMajor : null, sha1: await sha1File(path) });
      }
    }
    const unique = [...new Map(found.map((b) => [b.sha1, b])).values()].sort((a, b) => compareVersions(b.version, a.version));
    log.info('Snowball Client builds', { builds: unique.map((b) => `${b.version} for Minecraft ${b.label} (${basename(b.path)})`) });
    return new ClientBuildRegistry(unique);
  }

  /** Newest build that runs on this Minecraft version and loader, or null when Snowball Client is not available. */
  find(minecraftVersion: string, loader: LoaderId): ClientBuild | null {
    if (loader !== 'fabric') return null;
    return this.builds.find((b) => matchesFabricPredicate(minecraftVersion, b.minecraft)) ?? null;
  }
}

export type ClientState = 'unsupported' | 'ok' | 'missing' | 'damaged';

export interface CoreReport {
  supported: boolean;
  build: { version: string; minecraft: string } | null;
  client: ClientState;
  /** The verified jar the launcher loads into the game, when the client is ok. */
  clientJar: string | null;
  fabricApi: 'ok' | 'missing' | 'disabled' | 'not-needed';
  /** Each problem in plain language, most important first. Empty when everything is in order. */
  problems: string[];
}

export interface CoreTarget {
  gameDir: string;
  minecraftVersion: string;
  loader: LoaderId;
}

export type CoreReporter = (level: ActivityLevel, message: string) => void;

export class CoreRepairError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'CoreRepairError';
  }
}

interface ModFile {
  fileName: string;
  enabled: boolean;
  id: string | null;
  client: boolean;
}

/**
 * Snowball Client is not a mod in the instance: the launcher keeps a verified copy of each build in its
 * own folder and loads it into the game at launch (Fabric Loader's fabric.addMods). This class keeps that
 * copy valid, keeps copies out of mods folders (they would load twice) and makes sure the Fabric API the
 * client needs is present. inspect() only reads; ensure() repairs and then verifies.
 */
export class SnowballCore {
  constructor(
    readonly registry: ClientBuildRegistry,
    private readonly fabricApi: { ensure(gameDir: string, minecraftVersion: string, signal?: AbortSignal): Promise<boolean> },
    private readonly storeDir: string,
  ) {}

  /** Where the launcher keeps its verified copy of a build. Keyed by content, so builds never overwrite each other. */
  storePath(build: ClientBuild): string {
    return join(this.storeDir, `${sanitizeFileName(build.version, 'build')}-${build.sha1.slice(0, 12)}`, SNOWBALL_MOD_FILE);
  }

  async inspect(target: CoreTarget): Promise<CoreReport> {
    return this.check(target, await this.scan(modsDirOf(target)));
  }

  async ensure(target: CoreTarget, report: CoreReporter = () => undefined, signal?: AbortSignal): Promise<CoreReport> {
    const dir = modsDirOf(target);
    const files = await this.scan(dir);
    const before = await this.check(target, files);
    const build = this.registry.find(target.minecraftVersion, target.loader);
    const strays = files.filter((f) => f.client);

    if (!build) {
      // A client built for another version would stop the game from starting, so it is set aside.
      for (const f of strays) await rename(join(dir, f.fileName), freePath(dir, `${stripDisabled(f.fileName)}.unsupported`));
      if (strays.length) report('warn', `Snowball Client isn't available for Minecraft ${target.minecraftVersion} yet, so the copy in the mods folder was set aside`);
      await rm(join(target.gameDir, SNOWBALL_MARKER), { force: true });
      return this.inspect(target);
    }

    report('info', 'Checking core files...');
    // Copies in the mods folder would load a second time next to the launcher's copy. They are renamed, not deleted.
    for (const f of strays) await rename(join(dir, f.fileName), freePath(dir, `${stripDisabled(f.fileName)}.duplicate`));
    if (strays.length) {
      report('warn', `Moved ${strays.length === 1 ? 'an old copy' : `${strays.length} old copies`} of Snowball Client out of the mods folder; it now loads from the launcher`);
    }

    if (before.client === 'ok') {
      report('info', `Snowball Client ${build.version} detected`);
    } else {
      report('warn', before.problems[0]);
      report('info', 'Repairing Snowball Client...');
      await this.installBuild(build);
      report('success', 'Snowball Client restored');
    }
    await writeJsonAtomic(join(target.gameDir, SNOWBALL_MARKER), { schemaVersion: 1, client: build.version, minecraft: build.label });

    const api = fabricApiFor(target.minecraftVersion);
    if (before.fabricApi === 'disabled') {
      await mkdir(dir, { recursive: true });
      const off = files.find((f) => f.id === api.id && !f.enabled)!;
      await rename(join(dir, off.fileName), freePath(dir, stripDisabled(off.fileName)));
      report('warn', `${api.name} was turned off; it was turned back on because Snowball Client needs it`);
    } else if (before.fabricApi === 'missing') {
      report('info', `Installing ${api.name}...`);
      try {
        await this.fabricApi.ensure(target.gameDir, target.minecraftVersion, signal);
      } catch (err) {
        if (signal?.aborted) throw err;
        const message = `Snowball Client needs ${api.name}, which could not be installed: ${(err as Error).message}`;
        report('error', message);
        throw new CoreRepairError(message);
      }
      report('success', `${api.name} installed`);
    }

    const after = await this.inspect(target);
    if (after.problems.length) {
      const message = `Snowball Client could not be verified: ${after.problems.join('; ')}`;
      report('error', message);
      throw new CoreRepairError(message);
    }
    report('success', 'Core files verified');
    return after;
  }

  private async check(target: CoreTarget, files: ModFile[]): Promise<CoreReport> {
    const build = this.registry.find(target.minecraftVersion, target.loader);
    const strays = files.filter((f) => f.client).length;
    if (!build) {
      return {
        supported: false,
        build: null,
        client: 'unsupported',
        clientJar: null,
        fabricApi: 'not-needed',
        problems: strays ? [`Snowball Client isn't available for Minecraft ${target.minecraftVersion} yet, but a copy is in the mods folder`] : [],
      };
    }
    const problems: string[] = [];
    const jar = this.storePath(build);
    let client: ClientState = 'ok';
    if (!existsSync(jar)) {
      client = 'missing';
      problems.push('Snowball Client is missing');
    } else if ((await sha1File(jar)) !== build.sha1) {
      client = 'damaged';
      problems.push('Snowball Client is damaged');
    }
    if (strays) problems.push(`${strays === 1 ? 'An old copy' : `${strays} old copies`} of Snowball Client ${strays === 1 ? 'is' : 'are'} in the mods folder`);
    const api = fabricApiFor(target.minecraftVersion);
    const apis = files.filter((f) => f.id === api.id);
    const fabricApi = apis.some((f) => f.enabled) ? 'ok' : apis.length ? 'disabled' : 'missing';
    if (fabricApi === 'disabled') problems.push(`${api.name} is turned off`);
    if (fabricApi === 'missing') problems.push(`${api.name} is missing`);
    return { supported: true, build: { version: build.version, minecraft: build.label }, client, clientJar: client === 'ok' ? jar : null, fabricApi, problems };
  }

  private async scan(dir: string): Promise<ModFile[]> {
    if (!existsSync(dir)) return [];
    const files: ModFile[] = [];
    for (const entry of await readdir(dir, { withFileTypes: true })) {
      if (!entry.isFile()) continue;
      const lower = entry.name.toLowerCase();
      const enabled = lower.endsWith('.jar');
      if (!enabled && !lower.endsWith('.jar.disabled')) continue;
      const identity = await readIdentity(join(dir, entry.name));
      const client = identity?.id === SNOWBALL_MOD_ID || stripDisabled(lower) === SNOWBALL_MOD_FILE;
      files.push({ fileName: entry.name, enabled, id: identity?.id ?? null, client });
    }
    return files;
  }

  /** Copies the bundled jar into the launcher's store, verifies it, then swaps it in. */
  private async installBuild(build: ClientBuild): Promise<void> {
    const dest = this.storePath(build);
    const part = `${dest}.part`;
    await mkdir(dirname(dest), { recursive: true });
    await copyFile(build.path, part);
    if ((await sha1File(part)) !== build.sha1) {
      await rm(part, { force: true });
      throw new CoreRepairError('The Snowball Client files bundled with the launcher are damaged. Reinstall the launcher.');
    }
    await rm(dest, { force: true });
    await rename(part, dest);
    log.info(`Installed Snowball Client ${build.version}`, { path: dest });
  }
}

const modsDirOf = (target: CoreTarget) => join(target.gameDir, 'mods');
const stripDisabled = (fileName: string) => fileName.replace(/\.disabled$/i, '');

function freePath(dir: string, name: string): string {
  let candidate = name;
  for (let i = 2; existsSync(join(dir, candidate)); i++) candidate = `${name}-${i}`;
  return join(dir, candidate);
}
