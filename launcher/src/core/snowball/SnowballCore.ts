import { existsSync } from 'node:fs';
import { copyFile, mkdir, readdir, rename, rm } from 'node:fs/promises';
import { basename, join } from 'node:path';
import type { LoaderId } from '../instance/InstanceManager.js';
import type { ActivityLevel } from '../logging/Activity.js';
import { getLogger } from '../logging/Logger.js';
import { compareVersions, matchesFabricPredicate } from '../mods/versionRange.js';
import { sha1File } from '../util/fsutil.js';
import { ZipReader } from '../util/zip.js';
import { FABRIC_API_ID, SNOWBALL_MOD_FILE, SNOWBALL_MOD_ID } from './protection.js';

const log = getLogger('snowball');

/** One Snowball Client jar shipped with the launcher, built for a range of Minecraft versions. */
export interface ClientBuild {
  path: string;
  version: string;
  /** The jar's fabric.mod.json "minecraft" dependency, e.g. "~26.2". */
  minecraft: string | string[];
  /** Minecraft versions as players read them, e.g. "26.2". */
  label: string;
  sha1: string;
}

interface JarIdentity {
  id: string | null;
  version: string | null;
  minecraft: unknown;
}

/** Reads id, version and Minecraft dependency from a Fabric jar; null when it is not a readable Fabric mod. */
async function readIdentity(path: string): Promise<JarIdentity | null> {
  try {
    const text = (await ZipReader.open(path)).readText('fabric.mod.json');
    if (!text) return null;
    const j = JSON.parse(text.replace(/^﻿/, '')) as Record<string, any>;
    return { id: typeof j.id === 'string' ? j.id : null, version: typeof j.version === 'string' ? j.version : null, minecraft: j.depends?.minecraft };
  } catch {
    // Corrupt or non-jar files are reported as damaged by the caller instead of throwing here.
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
        found.push({ path, version: identity.version, minecraft: range, label, sha1: await sha1File(path) });
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

export type ClientState = 'unsupported' | 'ok' | 'missing' | 'damaged' | 'outdated' | 'disabled' | 'duplicate';

export interface CoreReport {
  supported: boolean;
  build: { version: string; minecraft: string } | null;
  client: ClientState;
  fabricApi: 'ok' | 'missing' | 'disabled' | 'not-needed';
  /** Each problem in plain language, most important first. */
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
  version: string | null;
  core: boolean;
}

/**
 * Keeps Snowball Client and the Fabric API it needs present, valid and unique in each instance.
 * inspect() only reads; ensure() repairs and then verifies, so an instance never silently stays broken.
 */
export class SnowballCore {
  constructor(
    readonly registry: ClientBuildRegistry,
    private readonly fabricApi: { ensure(gameDir: string, minecraftVersion: string, signal?: AbortSignal): Promise<boolean> },
  ) {}

  async inspect(target: CoreTarget): Promise<CoreReport> {
    return this.check(target, await this.scan(modsDirOf(target)));
  }

  async ensure(target: CoreTarget, report: CoreReporter = () => undefined, signal?: AbortSignal): Promise<CoreReport> {
    const dir = modsDirOf(target);
    const files = await this.scan(dir);
    const before = await this.check(target, files);
    const build = this.registry.find(target.minecraftVersion, target.loader);

    if (!build) {
      // A client built for another version would stop the game from starting, so it is set aside.
      const stray = files.filter((f) => f.core);
      for (const f of stray) await rename(join(dir, f.fileName), freePath(dir, `${stripDisabled(f.fileName)}.unsupported`));
      if (stray.length) report('warn', `Snowball Client isn't available for Minecraft ${target.minecraftVersion} yet, so it was set aside for this instance`);
      return this.inspect(target);
    }

    report('info', 'Checking core files...');
    await mkdir(dir, { recursive: true });
    const main = files.find((f) => f.fileName.toLowerCase() === SNOWBALL_MOD_FILE);
    const extras = files.filter((f) => f.core && f !== main);
    // Extra copies are renamed rather than deleted, so nothing a player put there is lost.
    for (const f of extras) await rename(join(dir, f.fileName), freePath(dir, `${stripDisabled(f.fileName)}.duplicate`));
    const copies = extras.filter((f) => !(before.client === 'disabled' && f.fileName.toLowerCase() === `${SNOWBALL_MOD_FILE}.disabled`)).length;
    if (copies) report('warn', `Set aside ${copies} extra cop${copies === 1 ? 'y' : 'ies'} of Snowball Client`);

    if (before.client === 'ok' || before.client === 'duplicate') {
      report('info', `Snowball Client ${build.version} detected`);
    } else if (before.client === 'outdated') {
      report('info', before.problems[0]);
      report('info', `Updating Snowball Client to ${build.version}...`);
      await this.installBuild(build, dir);
      report('success', `Snowball Client updated to ${build.version}`);
    } else {
      report('warn', before.problems[0]);
      report('info', 'Repairing Snowball Client...');
      await this.installBuild(build, dir);
      report('success', 'Snowball Client restored');
    }

    if (before.fabricApi === 'disabled') {
      const off = files.find((f) => f.id === FABRIC_API_ID && !f.enabled)!;
      await rename(join(dir, off.fileName), freePath(dir, stripDisabled(off.fileName)));
      report('warn', 'Fabric API was turned off; it was turned back on because Snowball Client needs it');
    } else if (before.fabricApi === 'missing') {
      report('info', 'Installing Fabric API...');
      try {
        await this.fabricApi.ensure(target.gameDir, target.minecraftVersion, signal);
      } catch (err) {
        if (signal?.aborted) throw err;
        const message = `Snowball Client needs Fabric API, which could not be installed: ${(err as Error).message}`;
        report('error', message);
        throw new CoreRepairError(message);
      }
      report('success', 'Fabric API installed');
    }

    const after = await this.inspect(target);
    if (after.client !== 'ok' || after.fabricApi !== 'ok') {
      const message = `Snowball Client could not be verified: ${after.problems.join('; ')}`;
      report('error', message);
      throw new CoreRepairError(message);
    }
    report('success', 'Core files verified');
    return after;
  }

  private async check(target: CoreTarget, files: ModFile[]): Promise<CoreReport> {
    const build = this.registry.find(target.minecraftVersion, target.loader);
    if (!build) {
      const stray = files.some((f) => f.core);
      return { supported: false, build: null, client: 'unsupported', fabricApi: 'not-needed', problems: stray ? [`Snowball Client isn't available for Minecraft ${target.minecraftVersion} yet, but a copy is in the mods folder`] : [] };
    }
    const dir = modsDirOf(target);
    const problems: string[] = [];
    const main = files.find((f) => f.fileName.toLowerCase() === SNOWBALL_MOD_FILE);
    const extras = files.filter((f) => f.core && f !== main);
    let client: ClientState = 'ok';
    if (!main) {
      const disabled = extras.some((f) => f.fileName.toLowerCase() === `${SNOWBALL_MOD_FILE}.disabled`);
      client = disabled ? 'disabled' : 'missing';
      problems.push(disabled ? 'Snowball Client was turned off outside the launcher' : 'Snowball Client is missing');
    } else if ((await sha1File(join(dir, main.fileName))) !== build.sha1) {
      if (main.id === SNOWBALL_MOD_ID && main.version && main.version !== build.version) {
        client = 'outdated';
        problems.push(`Snowball Client ${main.version} is out of date`);
      } else {
        client = 'damaged';
        problems.push('Snowball Client is damaged');
      }
    }
    const copies = extras.filter((f) => !(client === 'disabled' && f.fileName.toLowerCase() === `${SNOWBALL_MOD_FILE}.disabled`)).length;
    if (copies) {
      if (client === 'ok') client = 'duplicate';
      problems.push(`${copies} extra cop${copies === 1 ? 'y' : 'ies'} of Snowball Client found`);
    }
    const apis = files.filter((f) => f.id === FABRIC_API_ID);
    const fabricApi = apis.some((f) => f.enabled) ? 'ok' : apis.length ? 'disabled' : 'missing';
    if (fabricApi === 'disabled') problems.push('Fabric API is turned off');
    if (fabricApi === 'missing') problems.push('Fabric API is missing');
    return { supported: true, build: { version: build.version, minecraft: build.label }, client, fabricApi, problems };
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
      const core = identity?.id === SNOWBALL_MOD_ID || stripDisabled(lower) === SNOWBALL_MOD_FILE;
      files.push({ fileName: entry.name, enabled, id: identity?.id ?? null, version: identity?.version ?? null, core });
    }
    return files;
  }

  /** Copies the bundled jar next to the target, verifies it, then swaps it in. */
  private async installBuild(build: ClientBuild, dir: string): Promise<void> {
    const dest = join(dir, SNOWBALL_MOD_FILE);
    const part = `${dest}.part`;
    await copyFile(build.path, part);
    if ((await sha1File(part)) !== build.sha1) {
      await rm(part, { force: true });
      throw new CoreRepairError('The Snowball Client files bundled with the launcher are damaged. Reinstall the launcher.');
    }
    await rm(dest, { force: true });
    await rename(part, dest);
    log.info(`Installed Snowball Client ${build.version}`, { dir });
  }
}

const modsDirOf = (target: CoreTarget) => join(target.gameDir, 'mods');
const stripDisabled = (fileName: string) => fileName.replace(/\.disabled$/i, '');

function freePath(dir: string, name: string): string {
  let candidate = name;
  for (let i = 2; existsSync(join(dir, candidate)); i++) candidate = `${name}-${i}`;
  return join(dir, candidate);
}
