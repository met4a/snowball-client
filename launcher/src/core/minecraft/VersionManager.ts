import { existsSync } from 'node:fs';
import { copyFile, mkdir, readdir } from 'node:fs/promises';
import { dirname, join } from 'node:path';
import type { DownloadManager, DownloadProgress, DownloadTask } from '../download/DownloadManager.js';
import { getLogger } from '../logging/Logger.js';
import { isFileValid, readJson, writeJsonAtomic } from '../util/fsutil.js';
import { assertSafeRelativePath, safeJoin, type LauncherPaths } from '../util/paths.js';
import { ZipReader } from '../util/zip.js';
import { defaultRuleContext, libraryKey, mavenPath, rulesAllow, type RuleContext } from './rules.js';
import type { AssetIndex, Library, ManifestVersion, VersionJson, VersionManifest } from './types.js';

const log = getLogger('versions');

export const MOJANG_MANIFEST_URL = 'https://piston-meta.mojang.com/mc/game/version_manifest_v2.json';
const RESOURCES_URL = 'https://resources.download.minecraft.net';
const VERSION_ID_PATTERN = /^[A-Za-z0-9][A-Za-z0-9._+\- ]{0,127}$/;

export class VersionError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'VersionError';
  }
}

export interface ValidationIssue {
  kind: 'missing' | 'corrupt';
  file: string;
}

export function assertVersionId(id: string): string {
  if (!VERSION_ID_PATTERN.test(id) || id.includes('..')) throw new VersionError(`Invalid version id: ${id}`);
  return id;
}

/**
 * Merges a child profile (e.g. Fabric) onto its parent (vanilla). Child libraries replace
 * parent libraries with the same group:artifact(:classifier) so loaders can bump ASM etc.
 */
export function mergeVersionJson(parent: VersionJson, child: VersionJson): VersionJson {
  const childKeys = new Set(child.libraries.map((l) => libraryKey(l.name)));
  const libraries = [...child.libraries, ...parent.libraries.filter((l) => !childKeys.has(libraryKey(l.name)))];
  return {
    ...parent,
    ...child,
    inheritsFrom: undefined,
    jar: child.jar ?? parent.jar ?? parent.id,
    mainClass: child.mainClass ?? parent.mainClass,
    libraries,
    arguments:
      parent.arguments || child.arguments
        ? {
            game: [...(parent.arguments?.game ?? []), ...(child.arguments?.game ?? [])],
            jvm: [...(parent.arguments?.jvm ?? []), ...(child.arguments?.jvm ?? [])],
          }
        : undefined,
    minecraftArguments: child.minecraftArguments ?? parent.minecraftArguments,
    assetIndex: child.assetIndex ?? parent.assetIndex,
    assets: child.assets ?? parent.assets,
    downloads: child.downloads ?? parent.downloads,
    javaVersion: child.javaVersion ?? parent.javaVersion,
    logging: child.logging ?? parent.logging,
  };
}

export interface ResolvedLibrary {
  name: string;
  path: string;
  task?: DownloadTask;
  isNative: boolean;
  extractExclude?: string[];
}

/** Picks the files a version needs on this OS, including legacy per-OS native classifiers. */
export function resolveLibraries(version: VersionJson, librariesDir: string, ctx: RuleContext = defaultRuleContext()): ResolvedLibrary[] {
  const out: ResolvedLibrary[] = [];
  for (const lib of version.libraries) {
    if (!rulesAllow(lib.rules, ctx)) continue;
    const artifact = lib.downloads?.artifact;
    if (artifact || !lib.natives) {
      const rel = artifact?.path ? assertSafeRelativePath(artifact.path) : mavenPath(lib.name);
      const url = artifact?.url || (lib.url ? `${lib.url.replace(/\/?$/, '/')}${rel}` : `https://libraries.minecraft.net/${rel}`);
      const path = safeJoin(librariesDir, rel);
      // Libraries with an empty URL are produced locally by loader installers (Forge) and must already exist.
      const task = url && (artifact?.url !== '') ? { url, dest: path, sha1: artifact?.sha1 ?? lib.sha1, size: artifact?.size ?? lib.size, label: lib.name } : undefined;
      const nativeByName = /:natives-/.test(lib.name);
      out.push({ name: lib.name, path, task, isNative: nativeByName });
    }
    const nativeKey = lib.natives?.[ctx.os];
    if (nativeKey) {
      const classifier = nativeKey.replace('${arch}', ctx.arch.includes('64') ? '64' : '32');
      const nativeArtifact = lib.downloads?.classifiers?.[classifier];
      if (nativeArtifact) {
        const rel = nativeArtifact.path ? assertSafeRelativePath(nativeArtifact.path) : mavenPath(`${lib.name}:${classifier}`);
        const path = safeJoin(librariesDir, rel);
        out.push({
          name: `${lib.name}:${classifier}`,
          path,
          isNative: true,
          extractExclude: lib.extract?.exclude,
          task: { url: nativeArtifact.url, dest: path, sha1: nativeArtifact.sha1, size: nativeArtifact.size, label: `${lib.name} (natives)` },
        });
      }
    }
  }
  return out;
}

export class VersionManager {
  private manifestCache: VersionManifest | null = null;

  constructor(private readonly paths: LauncherPaths, private readonly downloads: DownloadManager) {}

  versionDir(id: string): string {
    return safeJoin(this.paths.versions, assertVersionId(id));
  }

  versionJsonPath(id: string): string {
    return join(this.versionDir(id), `${id}.json`);
  }

  clientJarPath(id: string): string {
    return join(this.versionDir(id), `${id}.jar`);
  }

  /** Fetches the Mojang manifest, falling back to the cached copy when offline. */
  async getManifest(forceRefresh = false): Promise<VersionManifest> {
    if (this.manifestCache && !forceRefresh) return this.manifestCache;
    const cachePath = join(this.paths.cache, 'version_manifest_v2.json');
    try {
      const manifest = await this.downloads.fetchJson<VersionManifest>(MOJANG_MANIFEST_URL);
      if (!manifest || !Array.isArray(manifest.versions)) throw new VersionError('Mojang version manifest has an unexpected format');
      await writeJsonAtomic(cachePath, manifest);
      this.manifestCache = manifest;
      return manifest;
    } catch (err) {
      const cached = await readJson<VersionManifest>(cachePath);
      if (cached.ok && Array.isArray(cached.value.versions)) {
        log.warn('Using cached version manifest (network unavailable)', { error: String(err) });
        this.manifestCache = cached.value;
        return cached.value;
      }
      throw new VersionError(`Could not load the Minecraft version list: ${(err as Error).message}`);
    }
  }

  async listAvailable(includeSnapshots = false): Promise<ManifestVersion[]> {
    const manifest = await this.getManifest();
    return manifest.versions.filter((v) => includeSnapshots || v.type === 'release');
  }

  async listInstalled(): Promise<string[]> {
    if (!existsSync(this.paths.versions)) return [];
    const entries = await readdir(this.paths.versions, { withFileTypes: true });
    return entries.filter((e) => e.isDirectory() && existsSync(join(this.paths.versions, e.name, `${e.name}.json`))).map((e) => e.name);
  }

  async readVersionJson(id: string): Promise<VersionJson> {
    const result = await readJson<VersionJson>(this.versionJsonPath(id));
    if (!result.ok) {
      throw new VersionError(result.reason === 'missing' ? `Version ${id} is not installed` : `Version ${id} metadata is corrupt: ${result.error}`);
    }
    if (!result.value.mainClass || !Array.isArray(result.value.libraries)) {
      throw new VersionError(`Version ${id} metadata is missing required fields`);
    }
    return result.value;
  }

  /** Loads a version and flattens its `inheritsFrom` chain. */
  async resolve(id: string, depth = 0): Promise<VersionJson> {
    if (depth > 5) throw new VersionError(`Version inheritance too deep at ${id}`);
    const json = await this.readVersionJson(id);
    if (!json.inheritsFrom) return json;
    const parent = await this.resolve(json.inheritsFrom, depth + 1);
    return mergeVersionJson(parent, json);
  }

  async installVanilla(id: string, signal?: AbortSignal, onProgress?: (stage: string, p?: DownloadProgress) => void): Promise<VersionJson> {
    assertVersionId(id);
    onProgress?.('metadata');
    const manifest = await this.getManifest();
    const entry = manifest.versions.find((v) => v.id === id);
    const jsonPath = this.versionJsonPath(id);
    if (!entry) {
      if (!existsSync(jsonPath)) throw new VersionError(`Minecraft ${id} does not exist in Mojang's version manifest`);
    } else {
      await this.downloads.download({ url: entry.url, dest: jsonPath, sha1: entry.sha1, label: `${id}.json` }, signal);
    }
    const json = await this.readVersionJson(id);
    await this.installFiles(json, signal, onProgress);
    log.info(`Installed Minecraft ${id}`);
    return json;
  }

  /** Downloads client jar, libraries, asset index/objects and logging config for a resolved version. */
  async installFiles(version: VersionJson, signal?: AbortSignal, onProgress?: (stage: string, p?: DownloadProgress) => void): Promise<void> {
    const baseId = this.jarIdOf(version);
    const tasks: DownloadTask[] = [];
    if (version.downloads?.client) {
      tasks.push({ url: version.downloads.client.url, dest: this.clientJarPath(baseId), sha1: version.downloads.client.sha1, size: version.downloads.client.size, label: `${baseId}.jar` });
    }
    for (const lib of resolveLibraries(version, this.paths.libraries)) if (lib.task) tasks.push(lib.task);
    if (version.logging?.client) {
      const f = version.logging.client.file;
      tasks.push({ url: f.url, dest: safeJoin(this.paths.assets, 'log_configs', f.id), sha1: f.sha1, size: f.size, label: f.id });
    }
    onProgress?.('Downloading libraries');
    await this.downloads.downloadAll(tasks, signal, (p) => onProgress?.('Downloading libraries', p));

    if (version.assetIndex) {
      onProgress?.('Downloading game assets');
      const idx = version.assetIndex;
      const indexPath = safeJoin(this.paths.assets, 'indexes', `${idx.id}.json`);
      await this.downloads.download({ url: idx.url, dest: indexPath, sha1: idx.sha1, size: idx.size, label: `asset index ${idx.id}` }, signal);
      const index = await readJson<AssetIndex>(indexPath);
      if (!index.ok || typeof index.value.objects !== 'object') throw new VersionError(`Asset index ${idx.id} is corrupt`);
      const assetTasks = Object.values(index.value.objects).map((o) => {
        if (!/^[0-9a-f]{40}$/i.test(o.hash)) throw new VersionError(`Invalid asset hash in index ${idx.id}`);
        const sub = `${o.hash.slice(0, 2)}/${o.hash}`;
        return { url: `${RESOURCES_URL}/${sub}`, dest: safeJoin(this.paths.assets, 'objects', sub), sha1: o.hash, size: o.size, label: `asset ${o.hash.slice(0, 8)}` };
      });
      await this.downloads.downloadAll(assetTasks, signal, (p) => onProgress?.('Downloading game assets', p));
      if (index.value.virtual || index.value.map_to_resources) await this.materializeLegacyAssets(idx.id, index.value);
    }
  }

  /** Pre-1.7 versions read assets by name from a virtual directory instead of the object store. */
  private async materializeLegacyAssets(indexId: string, index: AssetIndex): Promise<void> {
    const virtualDir = safeJoin(this.paths.assets, 'virtual', indexId);
    for (const [name, obj] of Object.entries(index.objects)) {
      const target = safeJoin(virtualDir, name);
      if (await isFileValid(target, { size: obj.size })) continue;
      await mkdir(dirname(target), { recursive: true });
      await copyFile(safeJoin(this.paths.assets, 'objects', obj.hash.slice(0, 2), obj.hash), target);
    }
  }

  /** The version id owning the client jar (vanilla id for merged loader profiles). */
  jarIdOf(version: VersionJson): string {
    return version.jar ?? version.id;
  }

  /** Extracts legacy native libraries for a launch into a per-launch directory. */
  async extractNatives(version: VersionJson, nativesDir: string): Promise<void> {
    await mkdir(nativesDir, { recursive: true });
    for (const lib of resolveLibraries(version, this.paths.libraries)) {
      if (!lib.isNative) continue;
      if (!existsSync(lib.path)) throw new VersionError(`Missing native library ${lib.name}`);
      const zip = await ZipReader.open(lib.path);
      const exclude = lib.extractExclude ?? ['META-INF/'];
      await zip.extractAll(nativesDir, (e) => !e.isDirectory && !exclude.some((x) => e.name.startsWith(x)) && /\.(dll|so|dylib|jnilib)$/i.test(e.name));
    }
  }
}

export type { Library };
