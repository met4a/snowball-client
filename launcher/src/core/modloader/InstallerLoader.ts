import { execFile } from 'node:child_process';
import { existsSync } from 'node:fs';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { dirname, join } from 'node:path';
import { getLogger } from '../logging/Logger.js';
import { compareVersions } from '../mods/versionRange.js';
import { safeJoin } from '../util/paths.js';
import { ZipReader } from '../util/zip.js';
import { LOADER_VERSION_PATTERN, LoaderError, type IModLoader, type LoaderInstallContext, type LoaderVersion } from './IModLoader.js';

const log = getLogger('loader');

export type InstallerRunner = (javaPath: string, args: string[], cwd: string, signal?: AbortSignal) => Promise<{ code: number; output: string }>;

const defaultRunner: InstallerRunner = (javaPath, args, cwd, signal) =>
  new Promise((resolve) => {
    execFile(javaPath, args, { cwd, windowsHide: true, timeout: 15 * 60_000, maxBuffer: 16 * 1024 * 1024, signal }, (err, stdout, stderr) => {
      const code = err ? (typeof (err as { code?: unknown }).code === 'number' ? (err as { code: number }).code : 1) : 0;
      resolve({ code, output: `${stdout}\n${stderr}` });
    });
  });

type Fetcher = {
  fetchText(url: string, signal?: AbortSignal): Promise<string>;
};

interface InstallerSpec {
  id: 'forge' | 'neoforge';
  displayName: string;
  metadataUrl: string;
  installerUrl: (version: string) => string;
  /** Loader versions for a Minecraft version share this prefix. */
  versionPrefix: (minecraftVersion: string) => string;
  /** Headless client install flag of the official installer. */
  installFlag: string;
}

/**
 * Forge and NeoForge only publish installer jars, which generate their version profile and patch
 * the client. The launcher downloads the official installer from its Maven repository (verified
 * against the published .sha1), runs it headless into the launcher directory, then downloads the
 * libraries named by the generated profile. Nothing else from the download is executed.
 */
export class InstallerLoader implements IModLoader {
  readonly id: 'forge' | 'neoforge';
  readonly displayName: string;

  private constructor(private readonly spec: InstallerSpec, private readonly fetcher: Fetcher, private readonly runner: InstallerRunner) {
    this.id = spec.id;
    this.displayName = spec.displayName;
  }

  nameFor(): string {
    return this.displayName;
  }

  static neoforge(fetcher: Fetcher, runner: InstallerRunner = defaultRunner): InstallerLoader {
    return new InstallerLoader({
      id: 'neoforge',
      displayName: 'NeoForge',
      metadataUrl: 'https://maven.neoforged.net/releases/net/neoforged/neoforge/maven-metadata.xml',
      installerUrl: (v) => `https://maven.neoforged.net/releases/net/neoforged/neoforge/${v}/neoforge-${v}-installer.jar`,
      versionPrefix: (mc) => {
        const parts = mc.split('.');
        // 1.21.1 -> "21.1."; 26.2 -> "26.2.0."; 26.1.2 -> "26.1.2."
        if (parts[0] === '1') return `${parts[1]}.${parts[2] ?? '0'}.`;
        return parts.length === 2 ? `${mc}.0.` : `${mc}.`;
      },
      installFlag: '--install-client',
    }, fetcher, runner);
  }

  static forge(fetcher: Fetcher, runner: InstallerRunner = defaultRunner): InstallerLoader {
    return new InstallerLoader({
      id: 'forge',
      displayName: 'Forge',
      metadataUrl: 'https://maven.minecraftforge.net/net/minecraftforge/forge/maven-metadata.xml',
      installerUrl: (v) => `https://maven.minecraftforge.net/net/minecraftforge/forge/${v}/forge-${v}-installer.jar`,
      versionPrefix: (mc) => `${mc}-`,
      installFlag: '--installClient',
    }, fetcher, runner);
  }

  async listVersions(minecraftVersion: string): Promise<LoaderVersion[]> {
    const xml = await this.fetcher.fetchText(this.spec.metadataUrl);
    const prefix = this.spec.versionPrefix(minecraftVersion);
    // Repositories differ in ordering (Forge newest-first, NeoForge oldest-first), so sort explicitly.
    return [...xml.matchAll(/<version>([^<]+)<\/version>/g)]
      .map((m) => m[1])
      .filter((v) => v.startsWith(prefix) && LOADER_VERSION_PATTERN.test(v))
      .sort((a, b) => compareVersions(b.slice(prefix.length), a.slice(prefix.length)))
      .map((version) => ({ version, stable: !/beta|alpha|rc/i.test(version) }));
  }

  private profileMarker(ctx: Pick<LoaderInstallContext, 'versions'>, mc: string, loader: string): string {
    return join(ctx.versions.versionDir(mc), `.${this.id}-${loader}.id`);
  }

  async findInstalled(mc: string, loader: string, ctx: Pick<LoaderInstallContext, 'versions'>): Promise<string | null> {
    const marker = this.profileMarker(ctx, mc, loader);
    if (!existsSync(marker)) return null;
    const id = (await readFile(marker, 'utf8')).trim();
    return id && existsSync(ctx.versions.versionJsonPath(id)) ? id : null;
  }

  async install(mc: string, loader: string, ctx: LoaderInstallContext): Promise<string> {
    if (!LOADER_VERSION_PATTERN.test(loader)) throw new LoaderError(`Invalid ${this.displayName} version: ${loader}`);
    if (!ctx.javaPath) throw new LoaderError(`${this.displayName} installation needs a Java runtime.`);
    ctx.onProgress?.(`Installing Minecraft ${mc}`);
    await ctx.versions.installVanilla(mc, ctx.signal);

    ctx.onProgress?.(`Downloading ${this.displayName} ${loader} installer`);
    const url = this.spec.installerUrl(loader);
    const sha1 = (await this.fetcher.fetchText(`${url}.sha1`, ctx.signal)).trim().slice(0, 40);
    if (!/^[0-9a-f]{40}$/i.test(sha1)) throw new LoaderError(`${this.displayName} installer checksum is unavailable; refusing to run an unverified installer.`);
    const installerPath = safeJoin(ctx.paths.cache, 'installers', `${this.id}-${loader}-installer.jar`);
    await ctx.downloads.download({ url, dest: installerPath, sha1, label: `${this.displayName} installer` }, ctx.signal);

    const zip = await ZipReader.open(installerPath);
    const versionText = zip.readText('version.json');
    if (!versionText) throw new LoaderError(`${this.displayName} installer does not contain a version profile.`);
    const profileId = String((JSON.parse(versionText) as { id?: unknown }).id ?? '');
    if (!/^[A-Za-z0-9._+ -]{1,128}$/.test(profileId)) throw new LoaderError(`${this.displayName} installer has an invalid profile id.`);

    // The official installers require a launcher_profiles.json in the target directory.
    const profiles = join(ctx.paths.root, 'launcher_profiles.json');
    if (!existsSync(profiles)) await writeFile(profiles, JSON.stringify({ profiles: {} }));

    ctx.onProgress?.(`Running ${this.displayName} installer`);
    const result = await this.runner(ctx.javaPath, ['-jar', installerPath, this.spec.installFlag, ctx.paths.root], ctx.paths.root, ctx.signal);
    if (result.code !== 0 || !existsSync(ctx.versions.versionJsonPath(profileId))) {
      log.error(`${this.displayName} installer failed`, { code: result.code, output: result.output.slice(-4000) });
      throw new LoaderError(`${this.displayName} installer failed (exit ${result.code}). See the launcher log for details.`);
    }
    const marker = this.profileMarker(ctx, mc, loader);
    await mkdir(dirname(marker), { recursive: true });
    await writeFile(marker, profileId);

    ctx.onProgress?.(`Downloading ${this.displayName} libraries`);
    await ctx.versions.installFiles(await ctx.versions.resolve(profileId), ctx.signal);
    log.info(`Installed ${this.displayName} ${loader} for ${mc}`, { profileId });
    return profileId;
  }
}
