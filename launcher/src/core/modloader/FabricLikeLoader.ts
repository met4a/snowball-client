import { existsSync } from 'node:fs';
import { getLogger } from '../logging/Logger.js';
import { usesLegacyFabric } from '../minecraft/fabricFamily.js';
import type { VersionJson } from '../minecraft/types.js';
import { writeJsonAtomic } from '../util/fsutil.js';
import { LOADER_VERSION_PATTERN, LoaderError, type IModLoader, type LoaderInstallContext, type LoaderVersion } from './IModLoader.js';

const log = getLogger('loader');

type JsonFetcher = { fetchJson<T>(url: string, signal?: AbortSignal): Promise<T> };

/**
 * Fabric and Quilt publish launcher profiles (a version JSON inheriting from vanilla) through their
 * meta services, so installing is: install vanilla, save the validated profile, download libraries.
 * Fabric instances for Minecraft 1.3 to 1.13.2 use Legacy Fabric's meta, which serves the same Fabric
 * Loader with its own intermediary names.
 */
export class FabricLikeLoader implements IModLoader {
  private constructor(
    readonly id: 'fabric' | 'quilt',
    readonly displayName: string,
    private readonly metaBase: string,
    private readonly idPrefix: string,
    private readonly fetcher: JsonFetcher,
    private readonly legacy?: { displayName: string; metaBase: string },
  ) {}

  static fabric(fetcher: JsonFetcher): FabricLikeLoader {
    return new FabricLikeLoader('fabric', 'Fabric', 'https://meta.fabricmc.net/v2', 'fabric-loader', fetcher, { displayName: 'Legacy Fabric', metaBase: 'https://meta.legacyfabric.net/v2' });
  }

  static quilt(fetcher: JsonFetcher): FabricLikeLoader {
    return new FabricLikeLoader('quilt', 'Quilt', 'https://meta.quiltmc.org/v3', 'quilt-loader', fetcher);
  }

  private versionId(mc: string, loader: string): string {
    return `${this.idPrefix}-${loader}-${mc}`;
  }

  private usesLegacy(mc: string): boolean {
    return this.legacy !== undefined && usesLegacyFabric(mc);
  }

  nameFor(minecraftVersion: string): string {
    return this.usesLegacy(minecraftVersion) ? this.legacy!.displayName : this.displayName;
  }

  private metaFor(mc: string): string {
    return this.usesLegacy(mc) ? this.legacy!.metaBase : this.metaBase;
  }

  async listVersions(minecraftVersion: string): Promise<LoaderVersion[]> {
    type Entry = { loader?: { version?: string; stable?: boolean } };
    const entries = await this.fetcher.fetchJson<Entry[]>(`${this.metaFor(minecraftVersion)}/versions/loader/${encodeURIComponent(minecraftVersion)}`);
    if (!Array.isArray(entries)) throw new LoaderError(`${this.nameFor(minecraftVersion)} meta returned an unexpected response.`);
    return entries
      .map((e) => e.loader)
      .filter((l): l is { version: string; stable?: boolean } => !!l && typeof l.version === 'string' && LOADER_VERSION_PATTERN.test(l.version))
      .map((l) => ({ version: l.version, stable: l.stable ?? !/beta|alpha|pre|rc/i.test(l.version) }));
  }

  async findInstalled(mc: string, loader: string, ctx: Pick<LoaderInstallContext, 'versions'>): Promise<string | null> {
    const id = this.versionId(mc, loader);
    return existsSync(ctx.versions.versionJsonPath(id)) ? id : null;
  }

  async install(mc: string, loader: string, ctx: LoaderInstallContext): Promise<string> {
    const name = this.nameFor(mc);
    if (!LOADER_VERSION_PATTERN.test(loader)) throw new LoaderError(`Invalid ${name} version: ${loader}`);
    ctx.onProgress?.(`Installing Minecraft ${mc}`);
    await ctx.versions.installVanilla(mc, ctx.signal);

    ctx.onProgress?.(`Fetching ${name} ${loader}`);
    const url = `${this.metaFor(mc)}/versions/loader/${encodeURIComponent(mc)}/${encodeURIComponent(loader)}/profile/json`;
    const profile = await this.fetcher.fetchJson<VersionJson>(url, ctx.signal);
    validateProfile(profile, mc, name);
    const id = this.versionId(mc, loader);
    if (profile.id !== id) throw new LoaderError(`${name} profile id "${profile.id}" does not match the requested version.`);
    await writeJsonAtomic(ctx.versions.versionJsonPath(id), profile);

    ctx.onProgress?.(`Downloading ${name} libraries`);
    await ctx.versions.installFiles(await ctx.versions.resolve(id), ctx.signal);
    log.info(`Installed ${name} ${loader} for ${mc}`);
    return id;
  }
}

/** Rejects profiles that do not look like a loader profile for the requested vanilla version. */
export function validateProfile(profile: VersionJson, minecraftVersion: string, name: string): void {
  if (!profile || typeof profile !== 'object') throw new LoaderError(`${name} returned an empty profile.`);
  if (profile.inheritsFrom !== minecraftVersion) throw new LoaderError(`${name} profile targets ${profile.inheritsFrom}, expected ${minecraftVersion}.`);
  if (typeof profile.mainClass !== 'string' || !/^[\w.$]+$/.test(profile.mainClass)) throw new LoaderError(`${name} profile has an invalid main class.`);
  if (!Array.isArray(profile.libraries) || profile.libraries.some((l) => typeof l?.name !== 'string')) throw new LoaderError(`${name} profile has invalid libraries.`);
  for (const lib of profile.libraries) {
    if (lib.url && !/^https:\/\//.test(lib.url)) throw new LoaderError(`${name} profile references a non-HTTPS repository.`);
  }
}
