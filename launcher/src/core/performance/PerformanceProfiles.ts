import { existsSync } from 'node:fs';
import { rm } from 'node:fs/promises';
import { join } from 'node:path';
import type { DownloadManager } from '../download/DownloadManager.js';
import type { PerformanceProfileId } from '../instance/InstanceManager.js';
import { getLogger } from '../logging/Logger.js';
import { folderProtection } from '../snowball/protection.js';
import { safeJoin, sanitizeFileName } from '../util/paths.js';

const log = getLogger('performance');

export interface OptimizationMod {
  /** Modrinth project slug. */
  slug: string;
  /** Mod id inside the jar (fabric.mod.json). */
  modId: string;
  name: string;
}

export interface PerformanceProfile {
  id: Exclude<PerformanceProfileId, 'none'>;
  name: string;
  description: string;
  mods: OptimizationMod[];
}

const SODIUM: OptimizationMod = { slug: 'sodium', modId: 'sodium', name: 'Sodium' };
const LITHIUM: OptimizationMod = { slug: 'lithium', modId: 'lithium', name: 'Lithium' };
const FERRITE: OptimizationMod = { slug: 'ferrite-core', modId: 'ferritecore', name: 'FerriteCore' };
const IMMEDIATELY_FAST: OptimizationMod = { slug: 'immediatelyfast', modId: 'immediatelyfast', name: 'ImmediatelyFast' };
const ENTITY_CULLING: OptimizationMod = { slug: 'entityculling', modId: 'entityculling', name: 'Entity Culling' };
const MODERNFIX: OptimizationMod = { slug: 'modernfix', modId: 'modernfix', name: 'ModernFix' };
const IRIS: OptimizationMod = { slug: 'iris', modId: 'iris', name: 'Iris Shaders' };

/** Fabric optimisation stacks. Every stack is Sodium-based, so none of them can collide with OptiFine. */
export const PERFORMANCE_PROFILES: PerformanceProfile[] = [
  { id: 'balanced', name: 'Balanced', description: 'Rendering, logic and memory optimisations with no visual changes.', mods: [SODIUM, LITHIUM, FERRITE] },
  { id: 'fps-boost', name: 'FPS Boost', description: 'Balanced plus faster HUD rendering and entity culling.', mods: [SODIUM, LITHIUM, FERRITE, IMMEDIATELY_FAST, ENTITY_CULLING] },
  { id: 'maximum-fps', name: 'Maximum FPS', description: 'Everything in FPS Boost plus load-time fixes where available.', mods: [SODIUM, LITHIUM, FERRITE, IMMEDIATELY_FAST, ENTITY_CULLING, MODERNFIX] },
  { id: 'visual-quality', name: 'Visual Quality', description: 'Sodium with Iris for shader packs, plus memory savings.', mods: [SODIUM, IRIS, FERRITE, IMMEDIATELY_FAST] },
];

export interface ConflictRule {
  kind: 'conflict' | 'requires';
  modA: string;
  modB: string;
  message: string;
}

/** Known incompatible rendering stacks, checked for every instance and before launch. */
export const CONFLICT_RULES: ConflictRule[] = [
  { kind: 'conflict', modA: 'sodium', modB: 'optifabric', message: 'OptiFine (OptiFabric) and Sodium both replace the renderer and cannot be loaded together.' },
  { kind: 'conflict', modA: 'sodium', modB: 'embeddium', message: 'Embeddium is a Sodium fork; install only one of them.' },
  { kind: 'conflict', modA: 'iris', modB: 'optifabric', message: 'Iris and OptiFine both provide shaders and cannot be loaded together.' },
  { kind: 'requires', modA: 'iris', modB: 'sodium', message: 'Iris requires Sodium.' },
];

export function getProfile(id: PerformanceProfileId): PerformanceProfile | null {
  return PERFORMANCE_PROFILES.find((p) => p.id === id) ?? null;
}

export interface ResolvedFile {
  mod: OptimizationMod;
  url: string;
  fileName: string;
  sha1?: string;
  size?: number;
}

interface ModrinthVersion {
  version_number: string;
  files: Array<{ url: string; filename: string; primary: boolean; size: number; hashes: { sha1?: string } }>;
}

const MODRINTH_API = 'https://api.modrinth.com/v2';

/** Looks up the newest compatible file for each mod of a profile on Modrinth. */
export async function resolveProfile(profile: PerformanceProfile, minecraftVersion: string, loader: 'fabric' | 'quilt', downloads: Pick<DownloadManager, 'fetchJson'>): Promise<{ files: ResolvedFile[]; unavailable: OptimizationMod[] }> {
  const files: ResolvedFile[] = [];
  const unavailable: OptimizationMod[] = [];
  const loaders = loader === 'quilt' ? ['quilt', 'fabric'] : ['fabric'];
  for (const mod of profile.mods) {
    const url = `${MODRINTH_API}/project/${encodeURIComponent(mod.slug)}/version?game_versions=${encodeURIComponent(JSON.stringify([minecraftVersion]))}&loaders=${encodeURIComponent(JSON.stringify(loaders))}`;
    let versions: ModrinthVersion[];
    try {
      versions = await downloads.fetchJson<ModrinthVersion[]>(url);
    } catch (err) {
      log.warn(`Could not query Modrinth for ${mod.slug}`, { error: String(err) });
      unavailable.push(mod);
      continue;
    }
    const version = Array.isArray(versions) ? versions[0] : undefined;
    const file = version?.files?.find((f) => f.primary) ?? version?.files?.[0];
    if (!file || !/^https:\/\/cdn\.modrinth\.com\//.test(file.url) || !/\.jar$/i.test(file.filename)) {
      unavailable.push(mod);
      continue;
    }
    files.push({ mod, url: file.url, fileName: sanitizeFileName(file.filename.replace(/\.jar$/i, ''), mod.slug) + '.jar', sha1: file.hashes?.sha1, size: file.size });
  }
  return { files, unavailable };
}

/**
 * Installs a profile into an instance's mods folder. Only files the launcher previously installed
 * (tracked in managedMods) are removed, so the player's own mods are never touched.
 */
export async function installProfile(gameDir: string, previouslyManaged: string[], resolved: ResolvedFile[], downloads: Pick<DownloadManager, 'downloadAll'>, signal?: AbortSignal): Promise<string[]> {
  const modsDir = join(gameDir, 'mods');
  const keep = new Set(resolved.map((r) => r.fileName));
  const protectedFiles = await folderProtection(modsDir);
  for (const old of previouslyManaged) {
    if (keep.has(old)) continue;
    for (const candidate of [old, old + '.disabled']) {
      // Snowball Client and the Fabric API it needs are never removed by a profile change.
      if (protectedFiles.has(candidate)) continue;
      const path = safeJoin(modsDir, candidate);
      if (existsSync(path)) await rm(path);
    }
  }
  await downloads.downloadAll(
    resolved.map((r) => ({ url: r.url, dest: safeJoin(modsDir, r.fileName), sha1: r.sha1, size: r.size, label: r.mod.name })),
    signal,
  );
  log.info('Installed performance profile', { files: [...keep] });
  return [...keep];
}
