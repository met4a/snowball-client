import { existsSync } from 'node:fs';
import { rename, rm, stat } from 'node:fs/promises';
import { join } from 'node:path';
import type { DownloadManager } from '../download/DownloadManager.js';
import type { LoaderId } from '../instance/InstanceManager.js';
import { getLogger } from '../logging/Logger.js';
import { sha1File } from '../util/fsutil.js';
import { safeJoin, sanitizeFileName } from '../util/paths.js';
import { readModMetadata, type ModInfo, type ModManager } from './ModManager.js';
import { compareVersions } from './versionRange.js';

const log = getLogger('modrinth');

const API = 'https://api.modrinth.com/v2';
const CDN = /^https:\/\/cdn\.modrinth\.com\//;
const MOD_LOADERS = ['fabric', 'quilt', 'forge', 'neoforge'];
const LOADER_NAMES: Record<string, string> = { fabric: 'Fabric', quilt: 'Quilt', forge: 'Forge', neoforge: 'NeoForge' };
/** Stops a broken dependency graph from downloading half of Modrinth. */
const MAX_PLANNED_FILES = 40;

export const SEARCH_SORTS = ['relevance', 'downloads', 'follows', 'updated', 'newest'] as const;
export type SearchSort = (typeof SEARCH_SORTS)[number];

export interface ModrinthFile {
  url: string;
  filename: string;
  primary: boolean;
  size: number;
  hashes: { sha1?: string };
}

export interface ModrinthDependency {
  project_id: string | null;
  version_id: string | null;
  dependency_type: 'required' | 'optional' | 'incompatible' | 'embedded';
}

export interface ModrinthVersion {
  id: string;
  project_id: string;
  version_number: string;
  version_type: 'release' | 'beta' | 'alpha';
  game_versions: string[];
  loaders: string[];
  date_published: string;
  dependencies?: ModrinthDependency[];
  files: ModrinthFile[];
}

export interface SearchOptions {
  query: string;
  /** Modrinth loader category, or null for any loader. */
  loader: string | null;
  gameVersion: string | null;
  sort: SearchSort;
  offset: number;
  limit: number;
}

export interface SearchHit {
  projectId: string;
  slug: string;
  title: string;
  author: string;
  description: string;
  iconUrl: string | null;
  downloads: number;
  follows: number;
  updated: string;
  loaders: string[];
  gameVersions: string[];
  versionRange: string;
}

export interface InstanceTarget {
  gameDir: string;
  minecraftVersion: string;
  loader: LoaderId;
}

export interface InstalledProject {
  fileName: string;
  enabled: boolean;
  sha1: string;
  projectId: string;
  versionId: string;
  versionNumber: string;
  datePublished: string;
}

export interface ModUpdate {
  fileName: string;
  projectId: string;
  currentVersion: string;
  newVersion: string;
}

export class ModInstallError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'ModInstallError';
  }
}

type Http = Pick<DownloadManager, 'fetchJson' | 'postJson' | 'downloadAll'>;

/** Modrinth loader names an instance can load. Quilt runs Fabric mods; modern NeoForge does not run Forge mods. */
export function modrinthLoaders(loader: LoaderId): string[] {
  switch (loader) {
    case 'fabric':
      return ['fabric'];
    case 'quilt':
      return ['quilt', 'fabric'];
    case 'forge':
      return ['forge'];
    case 'neoforge':
      return ['neoforge'];
    default:
      return [];
  }
}

function primaryFile(version: ModrinthVersion): ModrinthFile | null {
  const file = version.files?.find((f) => f.primary) ?? version.files?.[0];
  if (!file || !CDN.test(file.url) || !/\.jar$/i.test(file.filename) || !file.hashes?.sha1) return null;
  return file;
}

const TYPE_RANK: Record<string, number> = { release: 0, beta: 1, alpha: 2 };

/**
 * Picks the version to install: it must list the exact Minecraft version and a loader the instance
 * can run. Stable releases win over betas and alphas; within a channel the newest wins.
 */
export function pickVersion(versions: ModrinthVersion[], minecraftVersion: string, loaders: string[]): ModrinthVersion | null {
  const candidates = (Array.isArray(versions) ? versions : []).filter(
    (v) => Array.isArray(v?.game_versions) && v.game_versions.includes(minecraftVersion) && Array.isArray(v.loaders) && v.loaders.some((l) => loaders.includes(l)) && primaryFile(v),
  );
  candidates.sort((a, b) => (TYPE_RANK[a.version_type] ?? 3) - (TYPE_RANK[b.version_type] ?? 3) || String(b.date_published).localeCompare(String(a.date_published)));
  return candidates[0] ?? null;
}

/** "1.16.5 - 26.2" from a project's game version list, ignoring snapshots and release candidates. */
export function summarizeVersions(versions: string[]): string {
  const releases = versions.filter((v) => /^\d+(\.\d+)+$/.test(v)).sort(compareVersions);
  if (releases.length === 0) return versions[versions.length - 1] ?? '';
  return releases.length === 1 ? releases[0] : `${releases[0]} - ${releases[releases.length - 1]}`;
}

const text = (v: unknown, max = 500): string => (typeof v === 'string' ? v.slice(0, max) : '');
const count = (v: unknown): number => (typeof v === 'number' && Number.isFinite(v) ? v : 0);

function toHit(raw: Record<string, unknown>): SearchHit | null {
  const projectId = text(raw.project_id, 64);
  if (!/^[A-Za-z0-9]+$/.test(projectId)) return null;
  const categories = Array.isArray(raw.categories) ? raw.categories.filter((c): c is string => typeof c === 'string') : [];
  const gameVersions = Array.isArray(raw.versions) ? raw.versions.filter((v): v is string => typeof v === 'string').slice(-400) : [];
  const icon = text(raw.icon_url, 1000);
  return {
    projectId,
    slug: text(raw.slug, 64),
    title: text(raw.title, 120) || projectId,
    author: text(raw.author, 64),
    description: text(raw.description, 300),
    iconUrl: CDN.test(icon) ? icon : null,
    downloads: count(raw.downloads),
    follows: count(raw.follows),
    updated: text(raw.date_modified, 40),
    loaders: MOD_LOADERS.filter((l) => categories.includes(l)),
    gameVersions,
    versionRange: summarizeVersions(gameVersions),
  };
}

/**
 * Mod browser backend on Modrinth's public API: search, one-click installs with dependency
 * resolution, and updates. Installed files are recognised by SHA-1, so mods added by hand or by a
 * performance profile are covered too. Every operation works on a single instance's mods folder.
 */
export class ModrinthService {
  private readonly hashCache = new Map<string, { size: number; mtimeMs: number; sha1: string }>();
  private readonly locks = new Map<string, Promise<unknown>>();

  constructor(private readonly http: Http, private readonly mods: ModManager) {}

  async search(options: SearchOptions): Promise<{ hits: SearchHit[]; total: number; offset: number }> {
    const facets: string[][] = [['project_type:mod']];
    if (options.loader) facets.push([`categories:${options.loader}`]);
    if (options.gameVersion) facets.push([`versions:${options.gameVersion}`]);
    const params = new URLSearchParams({ query: options.query, index: options.sort, offset: String(options.offset), limit: String(options.limit), facets: JSON.stringify(facets) });
    const raw = await this.http.fetchJson<{ hits?: unknown; total_hits?: unknown }>(`${API}/search?${params}`);
    if (!raw || !Array.isArray(raw.hits)) throw new ModInstallError('Modrinth returned an unexpected search response. Try again in a moment.');
    const hits = raw.hits.map((h) => (h && typeof h === 'object' ? toHit(h as Record<string, unknown>) : null)).filter((h): h is SearchHit => h !== null);
    return { hits, total: count(raw.total_hits), offset: options.offset };
  }

  /** Mods in the instance that Modrinth recognises, with the project and version they came from. */
  async installedProjects(gameDir: string): Promise<InstalledProject[]> {
    return (await this.scan(gameDir)).installed;
  }

  /** Installs a project and its required dependencies into one instance. Returns the titles installed. */
  install(target: InstanceTarget, projectId: string, signal?: AbortSignal): Promise<{ installed: string[] }> {
    return this.locked(target.gameDir, () => this.doInstall(target, projectId, signal));
  }

  async checkUpdates(target: InstanceTarget): Promise<ModUpdate[]> {
    const loaders = modrinthLoaders(target.loader);
    const { installed } = await this.scan(target.gameDir);
    if (!loaders.length || !installed.length) return [];
    const latest = await this.latestVersions(installed.map((i) => i.sha1), target.minecraftVersion, loaders);
    const updates: ModUpdate[] = [];
    for (const item of installed) {
      const next = latest[item.sha1];
      if (!isNewerCompatible(next, item, target.minecraftVersion, loaders)) continue;
      updates.push({ fileName: item.fileName, projectId: item.projectId, currentVersion: item.versionNumber, newVersion: next.version_number });
    }
    return updates;
  }

  /** Replaces one installed mod with its newest compatible version, keeping it enabled or disabled as before. */
  update(target: InstanceTarget, fileName: string, signal?: AbortSignal): Promise<{ oldFile: string; newFile: string; version: string }> {
    return this.locked(target.gameDir, async () => {
      const loaders = modrinthLoaders(target.loader);
      const item = (await this.scan(target.gameDir)).installed.find((i) => i.fileName === fileName);
      if (!item) throw new ModInstallError(`${fileName} is not a Modrinth mod, so it cannot be updated here.`);
      const next = (await this.latestVersions([item.sha1], target.minecraftVersion, loaders))[item.sha1];
      if (!isNewerCompatible(next, item, target.minecraftVersion, loaders)) throw new ModInstallError(`${fileName} is already up to date.`);
      const file = primaryFile(next)!;
      const modsDir = this.mods.modsDir(target.gameDir);
      const jarName = sanitizeFileName(file.filename.replace(/\.jar$/i, ''), item.projectId) + '.jar';
      const newFile = item.enabled ? jarName : `${jarName}.disabled`;
      if (newFile !== fileName && (existsSync(safeJoin(modsDir, jarName)) || existsSync(safeJoin(modsDir, `${jarName}.disabled`)))) {
        throw new ModInstallError(`Cannot update: ${jarName} already exists in this instance.`);
      }
      await this.http.downloadAll([{ url: file.url, dest: safeJoin(modsDir, `${jarName}.part-update`), sha1: file.hashes.sha1, size: file.size, label: jarName }], signal);
      await rm(safeJoin(modsDir, fileName));
      await rename(safeJoin(modsDir, `${jarName}.part-update`), safeJoin(modsDir, newFile));
      log.info(`Updated ${fileName} to ${newFile}`);
      return { oldFile: fileName, newFile, version: next.version_number };
    });
  }

  /**
   * Adds the newest Fabric API built for this exact Minecraft version, unless a Fabric API jar
   * (enabled or disabled) is already present. Returns false when nothing was installed.
   */
  ensureFabricApi(gameDir: string, minecraftVersion: string, signal?: AbortSignal): Promise<boolean> {
    return this.locked(gameDir, async () => {
      if ((await this.mods.list(gameDir)).some((m) => m.id === 'fabric-api')) return false;
      const versions = await this.projectVersions('fabric-api', minecraftVersion, ['fabric'], signal);
      const version = pickVersion(versions, minecraftVersion, ['fabric']);
      if (!version) throw new ModInstallError(`Fabric API has no release for Minecraft ${minecraftVersion} yet.`);
      const file = primaryFile(version)!;
      const name = sanitizeFileName(file.filename.replace(/\.jar$/i, ''), 'fabric-api') + '.jar';
      await this.http.downloadAll([{ url: file.url, dest: safeJoin(this.mods.modsDir(gameDir), name), sha1: file.hashes.sha1, size: file.size, label: 'Fabric API' }], signal);
      log.info(`Installed Fabric API ${version.version_number}`, { minecraftVersion });
      return true;
    });
  }

  private async doInstall(target: InstanceTarget, rootProject: string, signal?: AbortSignal): Promise<{ installed: string[] }> {
    const mc = target.minecraftVersion;
    const loaders = modrinthLoaders(target.loader);
    if (!loaders.length) throw new ModInstallError('This instance has no mod loader. Choose Fabric, Quilt, Forge or NeoForge in the instance editor first.');
    const loaderName = LOADER_NAMES[target.loader] ?? target.loader;

    const { files, installed } = await this.scan(target.gameDir);
    const installedProjects = new Set(installed.map((i) => i.projectId));
    const installedModIds = new Set(files.map((f) => f.id).filter((id): id is string => !!id));

    const plan = new Map<string, { title: string; version: ModrinthVersion; root: boolean }>();
    const missing: string[] = [];
    const conflicts: string[] = [];

    const visit = async (idOrSlug: string, pinnedVersionId: string | null, requiredBy: string | null): Promise<void> => {
      if (plan.has(idOrSlug)) return;
      if (installedProjects.has(idOrSlug)) {
        if (!requiredBy) throw new ModInstallError('This mod is already installed in this instance.');
        return;
      }
      const project = await this.http.fetchJson<{ id?: string; slug?: string; title?: string }>(`${API}/project/${encodeURIComponent(idOrSlug)}`, signal);
      const id = text(project?.id, 64);
      if (!id) throw new ModInstallError('Modrinth returned an unexpected project response.');
      const title = text(project.title, 120) || id;
      if (installedProjects.has(id) || plan.has(id)) {
        if (!requiredBy) throw new ModInstallError(`${title} is already installed in this instance.`);
        return;
      }
      // A dependency added by hand from another site counts as installed when its mod id matches the slug.
      if (requiredBy && project.slug && installedModIds.has(project.slug)) return;

      let version: ModrinthVersion | null = null;
      if (pinnedVersionId) {
        const pinned = await this.http.fetchJson<ModrinthVersion>(`${API}/version/${encodeURIComponent(pinnedVersionId)}`, signal).catch(() => null);
        version = pinned ? pickVersion([pinned], mc, loaders) : null;
      }
      version ??= pickVersion(await this.projectVersions(id, mc, loaders, signal), mc, loaders);
      if (!version) {
        if (!requiredBy) throw new ModInstallError(`${title} has no ${loaderName} version for Minecraft ${mc}.`);
        missing.push(`${title} (required by ${requiredBy})`);
        return;
      }
      plan.set(id, { title, version, root: !requiredBy });
      if (plan.size > MAX_PLANNED_FILES) throw new ModInstallError(`${title} pulls in more than ${MAX_PLANNED_FILES} dependencies; install them one at a time.`);

      for (const dep of version.dependencies ?? []) {
        if (dep.dependency_type === 'incompatible') {
          if (dep.project_id && installedProjects.has(dep.project_id)) {
            const other = installed.find((i) => i.projectId === dep.project_id);
            conflicts.push(`${title} is incompatible with ${other?.fileName ?? dep.project_id}, which is installed.`);
          }
          continue;
        }
        if (dep.dependency_type !== 'required') continue;
        let depProject = dep.project_id;
        if (!depProject && dep.version_id) {
          const v = await this.http.fetchJson<ModrinthVersion>(`${API}/version/${encodeURIComponent(dep.version_id)}`, signal).catch(() => null);
          depProject = v?.project_id ?? null;
        }
        if (depProject) await visit(depProject, dep.version_id, title);
      }
    };
    await visit(rootProject, null, null);

    if (conflicts.length) throw new ModInstallError(conflicts.join('\n'));
    if (missing.length) {
      const root = [...plan.values()].find((p) => p.root)?.title ?? 'This mod';
      throw new ModInstallError(`${root} cannot be installed because a required mod has no ${loaderName} version for Minecraft ${mc}:\n${missing.map((m) => `- ${m}`).join('\n')}`);
    }

    const modsDir = this.mods.modsDir(target.gameDir);
    const tasks: Array<{ url: string; dest: string; sha1?: string; size: number; label: string; root: boolean }> = [];
    for (const [projectId, entry] of plan) {
      const file = primaryFile(entry.version)!;
      const name = sanitizeFileName(file.filename.replace(/\.jar$/i, ''), projectId) + '.jar';
      const dest = safeJoin(modsDir, name);
      if (existsSync(dest) || existsSync(`${dest}.disabled`)) {
        if (entry.root) throw new ModInstallError(`${name} is already in this instance's mods folder.`);
        plan.delete(projectId);
        continue;
      }
      tasks.push({ url: file.url, dest, sha1: file.hashes.sha1, size: file.size, label: entry.title, root: entry.root });
    }
    await this.http.downloadAll(tasks.map(({ root: _root, ...task }) => task), signal);

    // Last line of defence against duplicates: a jar whose mod id is already present is removed again.
    const added: string[] = [];
    for (const task of tasks) {
      const meta = await readModMetadata(task.dest);
      if (meta.id && installedModIds.has(meta.id)) {
        await rm(task.dest, { force: true });
        if (task.root) throw new ModInstallError(`${task.label} is already installed in this instance (mod id "${meta.id}").`);
        continue;
      }
      if (meta.id) installedModIds.add(meta.id);
      added.push(task.label);
    }
    log.info('Installed mods from Modrinth', { project: rootProject, added });
    return { installed: added };
  }

  private projectVersions(project: string, mc: string, loaders: string[], signal?: AbortSignal): Promise<ModrinthVersion[]> {
    const query = `loaders=${encodeURIComponent(JSON.stringify(loaders))}&game_versions=${encodeURIComponent(JSON.stringify([mc]))}`;
    return this.http.fetchJson<ModrinthVersion[]>(`${API}/project/${encodeURIComponent(project)}/version?${query}`, signal);
  }

  private async latestVersions(hashes: string[], mc: string, loaders: string[]): Promise<Record<string, ModrinthVersion>> {
    const result = await this.http.postJson<Record<string, ModrinthVersion>>(`${API}/version_files/update`, { hashes, algorithm: 'sha1', loaders, game_versions: [mc] });
    return result && typeof result === 'object' ? result : {};
  }

  private async scan(gameDir: string): Promise<{ files: ModInfo[]; installed: InstalledProject[] }> {
    const files = await this.mods.list(gameDir);
    if (!files.length) return { files, installed: [] };
    const byHash = new Map<string, ModInfo>();
    for (const f of files) byHash.set(await this.sha1(join(this.mods.modsDir(gameDir), f.fileName)), f);
    const found = await this.http.postJson<Record<string, ModrinthVersion>>(`${API}/version_files`, { hashes: [...byHash.keys()], algorithm: 'sha1' });
    const installed: InstalledProject[] = [];
    for (const [sha1, f] of byHash) {
      const v = found && typeof found === 'object' ? found[sha1] : undefined;
      if (!v?.project_id) continue;
      installed.push({ fileName: f.fileName, enabled: f.enabled, sha1, projectId: v.project_id, versionId: v.id, versionNumber: v.version_number, datePublished: v.date_published });
    }
    return { files, installed };
  }

  private async sha1(path: string): Promise<string> {
    const info = await stat(path);
    const cached = this.hashCache.get(path);
    if (cached && cached.size === info.size && cached.mtimeMs === info.mtimeMs) return cached.sha1;
    const sha1 = await sha1File(path);
    this.hashCache.set(path, { size: info.size, mtimeMs: info.mtimeMs, sha1 });
    return sha1;
  }

  /** Serialises changes per instance so two installs never race on the same mods folder. */
  private locked<T>(gameDir: string, fn: () => Promise<T>): Promise<T> {
    const previous = this.locks.get(gameDir) ?? Promise.resolve();
    const run = previous.catch(() => undefined).then(fn);
    this.locks.set(gameDir, run);
    return run.finally(() => {
      if (this.locks.get(gameDir) === run) this.locks.delete(gameDir);
    });
  }
}

function isNewerCompatible(next: ModrinthVersion | undefined, current: InstalledProject, mc: string, loaders: string[]): next is ModrinthVersion {
  return !!next && next.id !== current.versionId && String(next.date_published) > String(current.datePublished) && pickVersion([next], mc, loaders) !== null;
}
