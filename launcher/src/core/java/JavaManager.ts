import { execFile } from 'node:child_process';
import { existsSync } from 'node:fs';
import { chmod, mkdir, readdir } from 'node:fs/promises';
import { arch, platform, totalmem } from 'node:os';
import { basename, delimiter, dirname, join, resolve } from 'node:path';
import type { DownloadManager, DownloadProgress, DownloadTask } from '../download/DownloadManager.js';
import { getLogger } from '../logging/Logger.js';
import { safeJoin } from '../util/paths.js';

const log = getLogger('java');

export interface JavaInstallation {
  path: string;
  version: string;
  majorVersion: number;
  vendor: string;
  arch: string;
  is64Bit: boolean;
  source: 'managed' | 'system' | 'manual';
}

export interface MemoryRecommendation {
  totalMb: number;
  recommendedMaxMb: number;
  safeUpperLimitMb: number;
  minMb: number;
}

/** Parses Java version strings: "1.8.0_491" -> 8, "21.0.12" -> 21, "25-ea" -> 25. */
export function parseJavaMajor(version: string): number | null {
  const m = /^(\d+)(?:\.(\d+))?/.exec(version.trim());
  if (!m) return null;
  const first = Number(m[1]);
  if (first === 1 && m[2]) return Number(m[2]);
  return first;
}

/** Parses the property dump from `java -XshowSettings:properties -version` (printed to stderr). */
export function parseJavaProperties(output: string): { version?: string; vendor?: string; arch?: string; dataModel?: string } {
  const get = (key: string) => new RegExp(`^\\s*${key.replace(/\./g, '\\.')} = (.*)$`, 'm').exec(output)?.[1]?.trim();
  return { version: get('java.version'), vendor: get('java.vendor'), arch: get('os.arch'), dataModel: get('sun.arch.data.model') };
}

export function recommendMemory(totalBytes: number = totalmem()): MemoryRecommendation {
  const totalMb = Math.floor(totalBytes / 1024 / 1024);
  // Leave headroom for the OS and the launcher; never recommend more than 8 GB by default.
  const safeUpperLimitMb = Math.max(1024, Math.floor(totalMb * 0.75) - 1024);
  const recommendedMaxMb = Math.min(8192, Math.max(2048, Math.floor(totalMb / 4 / 512) * 512), safeUpperLimitMb);
  return { totalMb, recommendedMaxMb, safeUpperLimitMb, minMb: 512 };
}

export interface MemoryValidation {
  ok: boolean;
  errors: string[];
  warnings: string[];
}

export function validateMemory(minMb: number, maxMb: number, totalBytes: number = totalmem()): MemoryValidation {
  const rec = recommendMemory(totalBytes);
  const errors: string[] = [];
  const warnings: string[] = [];
  if (!Number.isInteger(minMb) || !Number.isInteger(maxMb)) errors.push('Memory values must be whole megabytes.');
  if (minMb < 256) errors.push('Minimum memory must be at least 256 MB.');
  if (maxMb < 512) errors.push('Maximum memory must be at least 512 MB.');
  if (minMb > maxMb) errors.push('Minimum memory cannot exceed maximum memory.');
  if (maxMb > rec.safeUpperLimitMb) errors.push(`Maximum memory ${maxMb} MB exceeds the safe limit for this machine (${rec.safeUpperLimitMb} MB).`);
  else if (maxMb > 12288) warnings.push('Allocating more than 12 GB rarely helps and can increase GC pauses.');
  return { ok: errors.length === 0, errors, warnings };
}

/** Mojang ships specific Java runtimes per version; this maps the OS to its manifest key. */
export function mojangRuntimePlatform(): string | null {
  const a = arch();
  switch (platform()) {
    case 'win32':
      return a === 'arm64' ? 'windows-arm64' : a === 'ia32' ? 'windows-x86' : 'windows-x64';
    case 'darwin':
      return a === 'arm64' ? 'mac-os-arm64' : 'mac-os';
    case 'linux':
      return a === 'ia32' ? 'linux-i386' : a === 'x64' ? 'linux' : null;
    default:
      return null;
  }
}

const RUNTIME_INDEX_URL = 'https://launchermeta.mojang.com/v1/products/java-runtime/2ec0cc96c44e5a76b9c8b7c39df7210883d12871/all.json';

type Probe = (javaPath: string) => Promise<string>;

const defaultProbe: Probe = (javaPath) =>
  new Promise((resolvePromise, reject) => {
    execFile(javaPath, ['-XshowSettings:properties', '-version'], { timeout: 15_000, windowsHide: true }, (err, stdout, stderr) => {
      if (err && !stderr) reject(err);
      else resolvePromise(`${stdout}\n${stderr}`);
    });
  });

export class JavaManager {
  private cache = new Map<string, JavaInstallation | null>();

  constructor(
    private readonly runtimesDir: string,
    private readonly downloads?: DownloadManager,
    private readonly probe: Probe = defaultProbe,
  ) {}

  static executableName(): string {
    return platform() === 'win32' ? 'java.exe' : 'java';
  }

  /** Candidate java executables from JAVA_HOME, PATH, common vendor folders and managed runtimes. */
  async candidatePaths(): Promise<Array<{ path: string; source: JavaInstallation['source'] }>> {
    const exe = JavaManager.executableName();
    const found = new Map<string, JavaInstallation['source']>();
    const add = (p: string, source: JavaInstallation['source']) => {
      const full = resolve(p);
      if (existsSync(full) && !found.has(full.toLowerCase())) found.set(full, source);
    };

    for (const home of await this.childDirs(this.runtimesDir)) {
      add(join(home, 'bin', exe), 'managed');
      add(join(home, 'jre.bundle', 'Contents', 'Home', 'bin', exe), 'managed');
    }
    if (process.env.JAVA_HOME) add(join(process.env.JAVA_HOME, 'bin', exe), 'system');
    for (const dir of (process.env.PATH ?? '').split(delimiter)) if (dir) add(join(dir, exe), 'system');

    const roots: string[] = [];
    if (platform() === 'win32') {
      for (const base of [process.env.ProgramFiles, process.env['ProgramFiles(x86)'], process.env.LOCALAPPDATA && join(process.env.LOCALAPPDATA, 'Programs')]) {
        if (!base) continue;
        for (const vendor of ['Java', 'Eclipse Adoptium', 'Eclipse Foundation', 'Zulu', 'Microsoft', 'BellSoft', 'Amazon Corretto', 'Semeru']) roots.push(join(base, vendor));
      }
    } else if (platform() === 'darwin') {
      roots.push('/Library/Java/JavaVirtualMachines', join(process.env.HOME ?? '', 'Library/Java/JavaVirtualMachines'));
    } else {
      roots.push('/usr/lib/jvm', '/usr/java', '/opt/java', '/usr/lib64/jvm', join(process.env.HOME ?? '', '.sdkman/candidates/java'));
    }
    for (const root of roots) {
      for (const home of await this.childDirs(root)) {
        add(join(home, 'bin', exe), 'system');
        add(join(home, 'Contents', 'Home', 'bin', exe), 'system');
      }
    }
    return [...found.entries()].map(([path, source]) => ({ path, source }));
  }

  async inspect(javaPath: string, source: JavaInstallation['source'] = 'manual'): Promise<JavaInstallation | null> {
    const key = resolve(javaPath);
    if (this.cache.has(key)) return this.cache.get(key) ?? null;
    let result: JavaInstallation | null = null;
    try {
      if (!existsSync(key)) throw new Error('file does not exist');
      const props = parseJavaProperties(await this.probe(key));
      const major = props.version ? parseJavaMajor(props.version) : null;
      if (!props.version || major === null) throw new Error('could not determine Java version');
      result = {
        path: key,
        version: props.version,
        majorVersion: major,
        vendor: props.vendor ?? 'unknown',
        arch: props.arch ?? 'unknown',
        is64Bit: props.dataModel ? props.dataModel === '64' : /64/.test(props.arch ?? ''),
        source,
      };
    } catch (err) {
      log.warn(`Ignoring Java candidate ${key}`, { error: (err as Error).message });
    }
    this.cache.set(key, result);
    return result;
  }

  async detect(): Promise<JavaInstallation[]> {
    const candidates = await this.candidatePaths();
    const results = await Promise.all(candidates.map((c) => this.inspect(c.path, c.source)));
    // Same JDK is often reachable via PATH and its install dir (javapath shims); keep one per real home.
    const unique = new Map<string, JavaInstallation>();
    for (const r of results) {
      if (!r) continue;
      const k = `${r.version}|${r.vendor}|${r.arch}`;
      const existing = unique.get(k);
      if (!existing || (existing.source !== 'managed' && r.source === 'managed') || /javapath|Common Files/i.test(existing.path)) unique.set(k, r);
    }
    const list = [...unique.values()].sort((a, b) => b.majorVersion - a.majorVersion);
    log.info(`Detected ${list.length} Java installation(s)`, { versions: list.map((j) => j.version) });
    return list;
  }

  /** Validates an explicit selection against the version's required Java major version. */
  async validateFor(javaPath: string, requiredMajor: number | undefined): Promise<{ ok: boolean; java?: JavaInstallation; message?: string }> {
    const java = await this.inspect(javaPath);
    if (!java) return { ok: false, message: `${javaPath} is not a working Java executable.` };
    if (!java.is64Bit) return { ok: true, java, message: '32-bit Java limits memory to about 1.5 GB.' };
    if (requiredMajor !== undefined) {
      if (java.majorVersion < requiredMajor) return { ok: false, java, message: `This Minecraft version needs Java ${requiredMajor}+, but the selected Java is ${java.majorVersion}.` };
      // Legacy LaunchWrapper versions break on Java 9+ module changes.
      if (requiredMajor <= 8 && java.majorVersion > 8) return { ok: false, java, message: `This Minecraft version requires Java 8; Java ${java.majorVersion} is not compatible.` };
    }
    return { ok: true, java };
  }

  async pickFor(requiredMajor: number | undefined): Promise<JavaInstallation | null> {
    const all = await this.detect();
    const need = requiredMajor ?? 8;
    const exact = all.find((j) => j.majorVersion === need && j.is64Bit);
    if (exact) return exact;
    if (need <= 8) return null;
    return all.filter((j) => j.majorVersion >= need && j.is64Bit).sort((a, b) => a.majorVersion - b.majorVersion)[0] ?? null;
  }

  /** The Mojang runtime that provides a Java version, e.g. 21 -> "java-runtime-delta". Null when there is none. */
  async componentForMajor(major: number, signal?: AbortSignal): Promise<string | null> {
    if (!this.downloads) return null;
    const plat = mojangRuntimePlatform();
    if (!plat) return null;
    type RuntimeIndex = Record<string, Record<string, Array<{ version: { name: string } }>>>;
    const index = await this.downloads.fetchJson<RuntimeIndex>(RUNTIME_INDEX_URL, signal);
    for (const [component, entries] of Object.entries(index[plat] ?? {})) {
      const name = entries[0]?.version?.name;
      if (name && parseJavaMajor(name) === major) return component;
    }
    return null;
  }

  /**
   * Installs the Mojang-distributed runtime named in a version JSON (`javaVersion.component`).
   * Every file is checksum-verified; paths come from Mojang metadata and are traversal-checked.
   */
  async installMojangRuntime(component: string, signal?: AbortSignal, onProgress?: (p: DownloadProgress) => void): Promise<JavaInstallation> {
    if (!this.downloads) throw new Error('Downloads are not available');
    if (!/^[a-z0-9-]+$/.test(component)) throw new Error(`Invalid runtime component: ${component}`);
    const plat = mojangRuntimePlatform();
    if (!plat) throw new Error('Mojang does not publish Java runtimes for this platform; install Java manually.');
    type RuntimeIndex = Record<string, Record<string, Array<{ manifest: { url: string; sha1: string; size: number }; version: { name: string } }>>>;
    const index = await this.downloads.fetchJson<RuntimeIndex>(RUNTIME_INDEX_URL, signal);
    const entry = index[plat]?.[component]?.[0];
    if (!entry) throw new Error(`Mojang has no ${component} runtime for ${plat}.`);
    type FileManifest = { files: Record<string, { type: 'file' | 'directory' | 'link'; executable?: boolean; downloads?: { raw: { url: string; sha1: string; size: number } } }> };
    const manifest = await this.downloads.fetchJson<FileManifest>(entry.manifest.url, signal);
    const home = safeJoin(this.runtimesDir, component);
    const tasks: DownloadTask[] = [];
    const executables: string[] = [];
    for (const [rel, file] of Object.entries(manifest.files ?? {})) {
      const target = safeJoin(home, rel);
      if (file.type === 'directory') await mkdir(target, { recursive: true });
      else if (file.type === 'file' && file.downloads?.raw) {
        tasks.push({ url: file.downloads.raw.url, dest: target, sha1: file.downloads.raw.sha1, size: file.downloads.raw.size, label: rel });
        if (file.executable) executables.push(target);
      }
    }
    await this.downloads.downloadAll(tasks, signal, onProgress);
    if (platform() !== 'win32') for (const f of executables) await chmod(f, 0o755);
    const javaPath = platform() === 'darwin' ? join(home, 'jre.bundle', 'Contents', 'Home', 'bin', 'java') : join(home, 'bin', JavaManager.executableName());
    this.cache.delete(resolve(javaPath));
    const java = await this.inspect(javaPath, 'managed');
    if (!java) throw new Error(`Installed runtime ${component} did not produce a working java executable.`);
    log.info(`Installed Mojang runtime ${component} (${java.version})`);
    return java;
  }

  /** Converts a java executable to the javaw variant on Windows so no console window appears. */
  static windowless(javaPath: string): string {
    if (platform() !== 'win32' || basename(javaPath).toLowerCase() !== 'java.exe') return javaPath;
    const javaw = join(dirname(javaPath), 'javaw.exe');
    return existsSync(javaw) ? javaw : javaPath;
  }

  private async childDirs(dir: string): Promise<string[]> {
    try {
      return (await readdir(dir, { withFileTypes: true })).filter((e) => e.isDirectory()).map((e) => join(dir, e.name));
    } catch {
      return [];
    }
  }
}
