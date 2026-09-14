import { existsSync } from 'node:fs';
import { copyFile, mkdir, readdir, rename, rm, stat } from 'node:fs/promises';
import { basename, extname, join } from 'node:path';
import type { LoaderId } from '../instance/InstanceManager.js';
import { getLogger } from '../logging/Logger.js';
import { CONFLICT_RULES } from '../performance/PerformanceProfiles.js';
import { readJson, writeJsonAtomic } from '../util/fsutil.js';
import { safeJoin, sanitizeFileName } from '../util/paths.js';
import { ZipReader } from '../util/zip.js';
import { matchesFabricPredicate, matchesMavenRange } from './versionRange.js';

const log = getLogger('mods');

export type ModLoaderKind = 'fabric' | 'quilt' | 'forge' | 'neoforge' | 'unknown';

export interface ModInfo {
  fileName: string;
  enabled: boolean;
  size: number;
  id: string | null;
  name: string;
  version: string | null;
  loader: ModLoaderKind;
  minecraft?: unknown;
  error?: string;
}

export interface ModIssue {
  severity: 'error' | 'warning';
  code: 'duplicate' | 'wrong-loader' | 'minecraft-version' | 'conflict' | 'unreadable' | 'vanilla';
  message: string;
  files: string[];
}

const DISABLED_SUFFIX = '.disabled';

/** Minimal TOML reader for mods.toml / neoforge.mods.toml: [[mods]] and [[dependencies.x]] tables of key = value. */
export function parseModsToml(text: string): { mods: Array<Record<string, string>>; dependencies: Record<string, Array<Record<string, string>>> } {
  const mods: Array<Record<string, string>> = [];
  const dependencies: Record<string, Array<Record<string, string>>> = {};
  let current: Record<string, string> | null = null;
  for (const rawLine of text.split(/\r?\n/)) {
    const line = rawLine.replace(/^([^"'#]*(?:"[^"]*"|'[^']*')*[^"'#]*)#.*$/, '$1').trim();
    if (!line) continue;
    let m: RegExpExecArray | null;
    if (line === '[[mods]]') {
      current = {};
      mods.push(current);
    } else if ((m = /^\[\[dependencies\.([\w.-]+)\]\]$/.exec(line))) {
      current = {};
      (dependencies[m[1]] ??= []).push(current);
    } else if (/^\[.*\]$/.test(line)) {
      current = null;
    } else if (current && (m = /^([\w.-]+)\s*=\s*(.+)$/.exec(line))) {
      const v = m[2].trim();
      current[m[1]] = /^"(.*)"$/.test(v) ? v.slice(1, -1) : /^'(.*)'$/.test(v) ? v.slice(1, -1) : v;
    }
  }
  return { mods, dependencies };
}

/** Reads mod identity and compatibility metadata from a jar without extracting it. */
export async function readModMetadata(path: string): Promise<Omit<ModInfo, 'fileName' | 'enabled' | 'size'>> {
  const fallbackName = basename(path).replace(/\.jar(\.disabled)?$/i, '');
  let zip: ZipReader;
  try {
    zip = await ZipReader.open(path);
  } catch (err) {
    return { id: null, name: fallbackName, version: null, loader: 'unknown', error: `Not a valid jar: ${(err as Error).message}` };
  }
  try {
    const fabric = zip.readText('fabric.mod.json');
    if (fabric) {
      const j = JSON.parse(fabric.replace(/^﻿/, '')) as Record<string, any>;
      return { id: str(j.id), name: str(j.name) ?? str(j.id) ?? fallbackName, version: str(j.version), loader: 'fabric', minecraft: j.depends?.minecraft };
    }
    const quilt = zip.readText('quilt.mod.json');
    if (quilt) {
      const q = (JSON.parse(quilt) as Record<string, any>).quilt_loader ?? {};
      const mcDep = Array.isArray(q.depends) ? q.depends.find((d: any) => d === 'minecraft' || d?.id === 'minecraft') : undefined;
      return { id: str(q.id), name: str(q.metadata?.name) ?? str(q.id) ?? fallbackName, version: str(q.version), loader: 'quilt', minecraft: typeof mcDep === 'object' ? mcDep.versions : undefined };
    }
    for (const [entry, loader] of [['META-INF/neoforge.mods.toml', 'neoforge'], ['META-INF/mods.toml', 'forge']] as const) {
      const toml = zip.readText(entry);
      if (!toml) continue;
      const parsed = parseModsToml(toml);
      const mod = parsed.mods[0] ?? {};
      const id = mod.modId ?? null;
      const mc = id ? parsed.dependencies[id]?.find((d) => d.modId === 'minecraft')?.versionRange : undefined;
      return { id, name: mod.displayName ?? id ?? fallbackName, version: mod.version ?? null, loader, minecraft: mc };
    }
    return { id: null, name: fallbackName, version: null, loader: 'unknown', error: 'No Fabric, Quilt or Forge metadata found' };
  } catch (err) {
    return { id: null, name: fallbackName, version: null, loader: 'unknown', error: `Unreadable metadata: ${(err as Error).message}` };
  }
}

function str(v: unknown): string | null {
  return typeof v === 'string' && v.length <= 200 ? v : null;
}

export class ModManager {
  modsDir(gameDir: string): string {
    return join(gameDir, 'mods');
  }

  async list(gameDir: string): Promise<ModInfo[]> {
    const dir = this.modsDir(gameDir);
    if (!existsSync(dir)) return [];
    const out: ModInfo[] = [];
    for (const entry of await readdir(dir, { withFileTypes: true })) {
      if (!entry.isFile()) continue;
      const lower = entry.name.toLowerCase();
      const enabled = lower.endsWith('.jar');
      if (!enabled && !lower.endsWith('.jar' + DISABLED_SUFFIX)) continue;
      const path = join(dir, entry.name);
      const meta = await readModMetadata(path);
      out.push({ fileName: entry.name, enabled, size: (await stat(path)).size, ...meta });
    }
    return out.sort((a, b) => a.name.localeCompare(b.name));
  }

  /** Copies a jar into the instance. Refuses non-jars, unreadable jars and name collisions. */
  async install(gameDir: string, sourcePath: string): Promise<ModInfo> {
    if (extname(sourcePath).toLowerCase() !== '.jar') throw new Error('Only .jar mod files can be installed.');
    const meta = await readModMetadata(sourcePath);
    if (meta.error && meta.loader === 'unknown' && meta.error.startsWith('Not a valid jar')) throw new Error(meta.error);
    const fileName = sanitizeFileName(basename(sourcePath, '.jar'), 'mod') + '.jar';
    const dir = this.modsDir(gameDir);
    await mkdir(dir, { recursive: true });
    const dest = safeJoin(dir, fileName);
    if (existsSync(dest) || existsSync(dest + DISABLED_SUFFIX)) throw new Error(`${fileName} is already installed.`);
    await copyFile(sourcePath, dest);
    log.info(`Installed mod ${fileName}`, { id: meta.id, loader: meta.loader });
    return { fileName, enabled: true, size: (await stat(dest)).size, ...meta };
  }

  async remove(gameDir: string, fileName: string): Promise<void> {
    const path = safeJoin(this.modsDir(gameDir), fileName);
    if (!/\.jar(\.disabled)?$/i.test(fileName)) throw new Error('Not a mod file');
    await rm(path);
    log.info(`Removed mod ${fileName}`);
  }

  /** Enabling/disabling renames the jar; the file content is never modified. */
  async setEnabled(gameDir: string, fileName: string, enabled: boolean): Promise<string> {
    const dir = this.modsDir(gameDir);
    const current = safeJoin(dir, fileName);
    const base = fileName.replace(/\.disabled$/i, '');
    const target = safeJoin(dir, enabled ? base : base + DISABLED_SUFFIX);
    if (current === target) return fileName;
    if (existsSync(target)) throw new Error(`Cannot ${enabled ? 'enable' : 'disable'} ${base}: ${basename(target)} already exists.`);
    await rename(current, target);
    return basename(target);
  }

  analyze(mods: ModInfo[], minecraftVersion: string, loader: LoaderId): ModIssue[] {
    const issues: ModIssue[] = [];
    const enabled = mods.filter((m) => m.enabled);
    if (loader === 'vanilla' && enabled.length > 0) {
      issues.push({ severity: 'warning', code: 'vanilla', message: 'This instance has no mod loader, so mods will not load.', files: enabled.map((m) => m.fileName) });
    }
    const byId = new Map<string, ModInfo[]>();
    for (const m of enabled) {
      if (m.error) issues.push({ severity: 'warning', code: 'unreadable', message: `${m.fileName}: ${m.error}`, files: [m.fileName] });
      if (m.id) byId.set(m.id, [...(byId.get(m.id) ?? []), m]);
      if (loader !== 'vanilla' && m.loader !== 'unknown' && !loaderAccepts(loader, m.loader)) {
        issues.push({ severity: 'error', code: 'wrong-loader', message: `${m.name} is a ${m.loader} mod but this instance uses ${loader}.`, files: [m.fileName] });
      }
      if (m.minecraft !== undefined) {
        const ok = m.loader === 'forge' || m.loader === 'neoforge'
          ? matchesMavenRange(minecraftVersion, typeof m.minecraft === 'string' ? m.minecraft : undefined)
          : matchesFabricPredicate(minecraftVersion, normalizeQuiltVersions(m.minecraft));
        if (!ok) {
          issues.push({ severity: 'error', code: 'minecraft-version', message: `${m.name} does not support Minecraft ${minecraftVersion} (requires ${JSON.stringify(m.minecraft)}).`, files: [m.fileName] });
        }
      }
    }
    for (const [id, list] of byId) {
      if (list.length > 1) issues.push({ severity: 'error', code: 'duplicate', message: `Mod "${id}" is installed ${list.length} times.`, files: list.map((m) => m.fileName) });
    }
    for (const rule of CONFLICT_RULES) {
      const a = byId.get(rule.modA);
      if (!a) continue;
      if (rule.kind === 'conflict') {
        const b = byId.get(rule.modB);
        if (b) issues.push({ severity: 'error', code: 'conflict', message: rule.message, files: [...a, ...b].map((m) => m.fileName) });
      } else if (!byId.has(rule.modB)) {
        issues.push({ severity: 'error', code: 'conflict', message: rule.message, files: a.map((m) => m.fileName) });
      }
    }
    return issues;
  }

  /**
   * Applies the in-game client's mod-requests.json (mods the player toggled in the Snowball menu).
   * Every mod whose id is listed is disabled; listed-then-unlisted mods are re-enabled.
   */
  async applyClientRequests(gameDir: string): Promise<{ changed: string[]; performanceProfile: string | null }> {
    const file = join(gameDir, 'config', 'snowballclient', 'mod-requests.json');
    const result = await readJson<{ disabledMods?: unknown; performanceProfile?: unknown }>(file);
    if (!result.ok) return { changed: [], performanceProfile: null };
    const disabled = new Set(Array.isArray(result.value.disabledMods) ? result.value.disabledMods.filter((x): x is string => typeof x === 'string') : []);
    const changed: string[] = [];
    for (const mod of await this.list(gameDir)) {
      if (!mod.id || ['snowballclient', 'fabric-api', 'fabricloader'].includes(mod.id)) continue;
      const shouldEnable = !disabled.has(mod.id);
      if (mod.enabled !== shouldEnable) {
        await this.setEnabled(gameDir, mod.fileName, shouldEnable);
        changed.push(`${shouldEnable ? 'enabled' : 'disabled'} ${mod.name}`);
      }
    }
    const profile = typeof result.value.performanceProfile === 'string' ? result.value.performanceProfile : null;
    if (changed.length) log.info('Applied in-game mod requests', { changed });
    return { changed, performanceProfile: profile };
  }

  /** Records the applied performance profile so the in-game menu does not request it again. */
  async recordPerformanceProfile(gameDir: string, profileId: string): Promise<void> {
    const file = join(gameDir, 'config', 'snowballclient', 'mod-requests.json');
    const result = await readJson<Record<string, unknown>>(file);
    const data: Record<string, unknown> = result.ok && result.value && typeof result.value === 'object' ? result.value : { schemaVersion: 1 };
    if (profileId === 'none') delete data.performanceProfile;
    else data.performanceProfile = profileId;
    await writeJsonAtomic(file, data);
  }

  /** Records a launcher-side toggle into mod-requests.json so the in-game menu shows the same state. */
  async recordToggle(gameDir: string, modId: string, enabled: boolean): Promise<void> {
    const file = join(gameDir, 'config', 'snowballclient', 'mod-requests.json');
    const result = await readJson<Record<string, unknown>>(file);
    const data: Record<string, unknown> = result.ok && result.value && typeof result.value === 'object' ? result.value : { schemaVersion: 1 };
    const list = new Set(Array.isArray(data.disabledMods) ? (data.disabledMods as unknown[]).filter((x): x is string => typeof x === 'string') : []);
    if (enabled) list.delete(modId);
    else list.add(modId);
    data.disabledMods = [...list].sort();
    await writeJsonAtomic(file, data);
  }
}

function loaderAccepts(instance: LoaderId, mod: ModLoaderKind): boolean {
  switch (instance) {
    case 'fabric':
      return mod === 'fabric';
    case 'quilt':
      return mod === 'quilt' || mod === 'fabric';
    case 'forge':
      return mod === 'forge';
    case 'neoforge':
      return mod === 'neoforge' || mod === 'forge';
    default:
      return false;
  }
}

function normalizeQuiltVersions(v: unknown): unknown {
  if (v && typeof v === 'object' && !Array.isArray(v) && Array.isArray((v as { any?: unknown }).any)) return (v as { any: unknown[] }).any;
  return v;
}
