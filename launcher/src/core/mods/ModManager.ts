import { existsSync } from 'node:fs';
import { copyFile, mkdir, readdir, rename, rm, stat } from 'node:fs/promises';
import { basename, extname, join } from 'node:path';
import type { LoaderId } from '../instance/InstanceManager.js';
import { getLogger } from '../logging/Logger.js';
import { CONFLICT_RULES } from '../performance/PerformanceProfiles.js';
import { assertModChangeAllowed, isSnowballInstance, ProtectedModError, protectionFor, SNOWBALL_MOD_ID, type Protection } from '../snowball/protection.js';
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
  /** Mod ids this mod requires (loader and game ids excluded). */
  depends?: string[];
  /** Mod ids this jar makes available: its own, declared "provides" and bundled jar-in-jar mods. */
  provides?: string[];
  /** Set for the Fabric API a Snowball instance needs ("required"); it can be updated but not removed or turned off. */
  protection?: Protection;
  error?: string;
}

export interface MissingDependency {
  id: string;
  name: string;
  /** Modrinth project slug when the dependency is a well-known mod, so it can be installed directly. */
  slug: string | null;
}

export interface ModIssue {
  severity: 'error' | 'warning';
  code: 'duplicate' | 'wrong-loader' | 'minecraft-version' | 'conflict' | 'unreadable' | 'vanilla' | 'missing-dependency';
  message: string;
  files: string[];
  dependency?: MissingDependency;
}

/** Ids of the game, Java and mod loaders; these are never separate mod files. */
const BUILTIN_DEPENDENCIES = new Set(['minecraft', 'java', 'fabricloader', 'fabric-loader', 'quilt_loader', 'forge', 'neoforge', 'javafml', 'lowcodefml']);

const KNOWN_DEPENDENCIES: Record<string, { name: string; slug: string }> = {
  sodium: { name: 'Sodium', slug: 'sodium' },
  iris: { name: 'Iris Shaders', slug: 'iris' },
  'cloth-config': { name: 'Cloth Config API', slug: 'cloth-config' },
  'cloth-config2': { name: 'Cloth Config API', slug: 'cloth-config' },
  cloth_config: { name: 'Cloth Config API', slug: 'cloth-config' },
  'fabric-language-kotlin': { name: 'Fabric Language Kotlin', slug: 'fabric-language-kotlin' },
  architectury: { name: 'Architectury API', slug: 'architectury-api' },
  geckolib: { name: 'GeckoLib', slug: 'geckolib' },
  yet_another_config_lib_v3: { name: 'YetAnotherConfigLib', slug: 'yacl' },
  modmenu: { name: 'Mod Menu', slug: 'modmenu' },
  owo: { name: 'owo-lib', slug: 'owo-lib' },
  forgeconfigapiport: { name: 'Forge Config API Port', slug: 'forge-config-api-port' },
  kotlinforforge: { name: 'Kotlin for Forge', slug: 'kotlin-for-forge' },
  balm: { name: 'Balm', slug: 'balm' },
  'placeholder-api': { name: 'Text Placeholder API', slug: 'placeholder-api' },
};

/** Turns a dependency id into something a player recognises. Fabric API's module ids all map to Fabric API. */
export function describeDependency(id: string): MissingDependency {
  if (id === 'fabric' || id === 'fabric-api' || id === 'fabric-api-base' || /^fabric-[a-z0-9-]+-v\d+$/.test(id)) return { id: 'fabric-api', name: 'Fabric API', slug: 'fabric-api' };
  const known = KNOWN_DEPENDENCIES[id];
  return known ? { id, ...known } : { id, name: id, slug: null };
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
      const depends = j.depends && typeof j.depends === 'object' && !Array.isArray(j.depends) ? requiredIds(Object.keys(j.depends)) : [];
      return { id: str(j.id), name: str(j.name) ?? str(j.id) ?? fallbackName, version: str(j.version), loader: 'fabric', minecraft: j.depends?.minecraft, depends, provides: jarProvides(zip) };
    }
    const quilt = zip.readText('quilt.mod.json');
    if (quilt) {
      const q = (JSON.parse(quilt) as Record<string, any>).quilt_loader ?? {};
      const mcDep = Array.isArray(q.depends) ? q.depends.find((d: any) => d === 'minecraft' || d?.id === 'minecraft') : undefined;
      // Quilt dependencies are ids or { id, optional }; nested arrays mean "any of" and are not checked.
      const depends = requiredIds(asArray(q.depends).flatMap((d: any) => (typeof d === 'string' ? [d] : d && typeof d.id === 'string' && d.optional !== true ? [d.id] : [])));
      return { id: str(q.id), name: str(q.metadata?.name) ?? str(q.id) ?? fallbackName, version: str(q.version), loader: 'quilt', minecraft: typeof mcDep === 'object' ? mcDep.versions : undefined, depends, provides: jarProvides(zip) };
    }
    for (const [entry, loader] of [['META-INF/neoforge.mods.toml', 'neoforge'], ['META-INF/mods.toml', 'forge']] as const) {
      const toml = zip.readText(entry);
      if (!toml) continue;
      const parsed = parseModsToml(toml);
      const mod = parsed.mods[0] ?? {};
      const id = mod.modId ?? null;
      const mc = id ? parsed.dependencies[id]?.find((d) => d.modId === 'minecraft')?.versionRange : undefined;
      // Forge marks required dependencies with mandatory=true, NeoForge with type="required". Server-only ones do not matter here.
      const ownIds = new Set(parsed.mods.map((m) => m.modId));
      const depends = requiredIds(parsed.mods.flatMap((m) => (parsed.dependencies[m.modId] ?? [])
        .filter((d) => (String(d.mandatory).toLowerCase() === 'true' || String(d.type).toLowerCase() === 'required') && String(d.side).toUpperCase() !== 'SERVER')
        .map((d) => d.modId)
        .filter((dep) => !ownIds.has(dep))));
      return { id, name: mod.displayName ?? id ?? fallbackName, version: mod.version ?? null, loader, minecraft: mc, depends, provides: jarProvides(zip) };
    }
    return { id: null, name: fallbackName, version: null, loader: 'unknown', error: 'No Fabric, Quilt or Forge metadata found' };
  } catch (err) {
    return { id: null, name: fallbackName, version: null, loader: 'unknown', error: `Unreadable metadata: ${(err as Error).message}` };
  }
}

function str(v: unknown): string | null {
  return typeof v === 'string' && v.length <= 200 ? v : null;
}

const asArray = (v: unknown): any[] => (Array.isArray(v) ? v : []);

function requiredIds(ids: unknown[]): string[] {
  return [...new Set(ids.filter((id): id is string => typeof id === 'string' && id.length > 0 && id.length <= 100 && !BUILTIN_DEPENDENCIES.has(id)))];
}

/** Every mod id a jar makes available: its own ids, declared "provides" and bundled jar-in-jar mods. */
function jarProvides(zip: ZipReader, depth = 0): string[] {
  const ids: unknown[] = [];
  const nested: unknown[] = [];
  const fabricText = zip.readText('fabric.mod.json');
  if (fabricText) {
    const j = JSON.parse(fabricText.replace(/^﻿/, '')) as Record<string, any>;
    ids.push(j.id, ...asArray(j.provides));
    nested.push(...asArray(j.jars).map((x) => x?.file));
  }
  const quiltText = zip.readText('quilt.mod.json');
  if (quiltText) {
    const q = (JSON.parse(quiltText) as Record<string, any>).quilt_loader ?? {};
    ids.push(q.id, ...asArray(q.provides).map((p) => (typeof p === 'string' ? p : p?.id)));
    nested.push(...asArray(q.jars));
  }
  for (const entry of ['META-INF/neoforge.mods.toml', 'META-INF/mods.toml']) {
    const toml = zip.readText(entry);
    if (toml) ids.push(...parseModsToml(toml).mods.map((m) => m.modId));
  }
  const jarjar = zip.readText('META-INF/jarjar/metadata.json');
  if (jarjar) nested.push(...asArray((JSON.parse(jarjar) as Record<string, any>).jars).map((x) => x?.path));
  if (depth < 2) {
    for (const path of nested.slice(0, 300)) {
      const entry = typeof path === 'string' ? zip.getEntry(path) : undefined;
      if (!entry) continue;
      try {
        ids.push(...jarProvides(ZipReader.fromBuffer(zip.read(entry)), depth + 1));
      } catch {
        // A bundled library that is not a readable mod jar provides no mod id, so skipping it is correct.
      }
    }
  }
  return [...new Set(ids.filter((id): id is string => typeof id === 'string' && id.length > 0 && id.length <= 100))];
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
    const protection = protectionFor(out, isSnowballInstance(gameDir));
    for (const mod of out) {
      const p = protection.get(mod.fileName);
      if (p) mod.protection = p;
    }
    return out.sort((a, b) => a.name.localeCompare(b.name));
  }

  /** Copies a jar into the instance. Refuses non-jars, unreadable jars and name collisions. */
  async install(gameDir: string, sourcePath: string): Promise<ModInfo> {
    if (extname(sourcePath).toLowerCase() !== '.jar') throw new Error('Only .jar mod files can be installed.');
    const meta = await readModMetadata(sourcePath);
    if (meta.error && meta.loader === 'unknown' && meta.error.startsWith('Not a valid jar')) throw new Error(meta.error);
    if (meta.id === SNOWBALL_MOD_ID) throw new ProtectedModError("Snowball Client is built into the launcher and loaded automatically, so it can't be added as a mod file.");
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
    await assertModChangeAllowed(this.modsDir(gameDir), fileName, 'remove');
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
    if (!enabled) await assertModChangeAllowed(dir, fileName, 'disable');
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
    if (loader !== 'vanilla') {
      const available = new Set<string>();
      for (const m of enabled) for (const id of [m.id, ...(m.provides ?? [])]) if (id) available.add(id);
      const disabledProviders = new Map<string, ModInfo>();
      for (const m of mods) {
        if (m.enabled) continue;
        for (const id of [m.id, ...(m.provides ?? [])]) if (id && !disabledProviders.has(id)) disabledProviders.set(id, m);
      }
      for (const m of enabled) {
        const reported = new Set<string>();
        for (const dep of m.depends ?? []) {
          if (available.has(dep)) continue;
          const off = disabledProviders.get(dep);
          const info = describeDependency(dep);
          const key = off ? off.fileName : info.name;
          if (reported.has(key)) continue;
          reported.add(key);
          issues.push(off
            ? { severity: 'warning', code: 'missing-dependency', message: `${m.name} needs ${off.name} to run. Turn ${off.name} on.`, files: [m.fileName, off.fileName] }
            : { severity: 'warning', code: 'missing-dependency', message: `${m.name} needs ${info.name} to run.`, files: [m.fileName], dependency: info });
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
        // When the jar declares the dependency itself, the "needs X to run" message already covers it; make it block launching.
        const declared = issues.find((i) => i.code === 'missing-dependency' && i.dependency?.id === rule.modB && a.some((m) => i.files.includes(m.fileName)));
        if (declared) declared.severity = 'error';
        else issues.push({ severity: 'error', code: 'conflict', message: rule.message, files: a.map((m) => m.fileName) });
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
