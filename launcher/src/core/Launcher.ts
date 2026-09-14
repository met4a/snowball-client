import { EventEmitter } from 'node:events';
import { existsSync } from 'node:fs';
import { copyFile, mkdir, rm } from 'node:fs/promises';
import { join } from 'node:path';
import { AuthManager, type SecretCipher } from './auth/AuthManager.js';
import { DownloadManager, type DownloadProgress } from './download/DownloadManager.js';
import { InstanceManager, type CreateInstanceOptions, type InstanceConfig, type PerformanceProfileId } from './instance/InstanceManager.js';
import { JavaManager, recommendMemory } from './java/JavaManager.js';
import { getLogger, logSink, LogLevel } from './logging/Logger.js';
import type { VersionJson } from './minecraft/types.js';
import { VersionManager } from './minecraft/VersionManager.js';
import { ModLoaderRegistry } from './modloader/ModLoaderRegistry.js';
import { ModManager } from './mods/ModManager.js';
import { getProfile, installProfile, PERFORMANCE_PROFILES, resolveProfile } from './performance/PerformanceProfiles.js';
import { buildLaunchPlan } from './process/LaunchArguments.js';
import { ProcessManager, type GameExit } from './process/ProcessManager.js';
import { SettingsStore } from './settings/LauncherSettings.js';
import { sha1File } from './util/fsutil.js';
import { createLauncherPaths, safeJoin, sanitizeFileName, type LauncherPaths } from './util/paths.js';

const log = getLogger('launcher');

/** Minecraft version the bundled Snowball Client mod is built for. */
export const SNOWBALL_CLIENT_MC = '26.2';
export const SNOWBALL_CLIENT_MOD_FILE = 'snowball-client.jar';

export interface LaunchProgress {
  instanceId: string;
  stage: string;
  completed?: number;
  total?: number;
}

export class LaunchBlockedError extends Error {
  constructor(readonly issues: string[]) {
    super(`This instance cannot start:\n${issues.map((i) => `- ${i}`).join('\n')}`);
    this.name = 'LaunchBlockedError';
  }
}

export interface LauncherOptions {
  root: string;
  cipher: SecretCipher;
  /** Path to the Snowball Client mod jar shipped with the launcher (null when unavailable). */
  clientJarPath: string | null;
  launcherVersion: string;
  consoleLogs?: boolean;
  /** Azure application (client) ID shipped with this build (app-config.json); Settings can override it. */
  defaultMicrosoftClientId?: string;
}

/**
 * Application facade wiring the launcher services together. The Electron main process (and the
 * headless end-to-end script) talk to this class only; it contains no UI code.
 */
export class Launcher extends EventEmitter {
  private constructor(
    readonly paths: LauncherPaths,
    readonly settings: SettingsStore,
    readonly downloads: DownloadManager,
    readonly versions: VersionManager,
    readonly java: JavaManager,
    readonly instances: InstanceManager,
    readonly mods: ModManager,
    readonly loaders: ModLoaderRegistry,
    readonly auth: AuthManager,
    readonly processes: ProcessManager,
    private readonly options: LauncherOptions,
  ) {
    super();
  }

  static async create(options: LauncherOptions): Promise<Launcher> {
    const paths = createLauncherPaths(options.root);
    for (const dir of [paths.root, paths.instances, paths.versions, paths.libraries, paths.assets, paths.runtimes, paths.cache, paths.logs]) {
      await mkdir(dir, { recursive: true });
    }
    logSink.configure({ directory: paths.logs, console: options.consoleLogs ?? true });
    const settings = new SettingsStore(paths.settingsFile);
    const s = await settings.load();
    logSink.configure({ minLevel: s.logs.debug ? LogLevel.DEBUG : LogLevel.INFO, retainFiles: s.logs.retainDays });

    const downloads = new DownloadManager({ concurrency: s.downloads.concurrency, retries: s.downloads.retries, userAgent: `SnowballClientLauncher/${options.launcherVersion}` });
    const versions = new VersionManager(paths, downloads);
    const java = new JavaManager(paths.runtimes, downloads);
    const instances = new InstanceManager(paths.instances);
    const mods = new ModManager();
    const loaders = new ModLoaderRegistry(downloads);
    const auth = new AuthManager(paths.accountsFile, options.cipher, () => settings.get().accounts.microsoftClientId || options.defaultMicrosoftClientId || '');
    await auth.load();
    const processes = new ProcessManager();

    const launcher = new Launcher(paths, settings, downloads, versions, java, instances, mods, loaders, auth, processes, options);
    processes.on('exit', (exit: GameExit) => {
      instances.markPlayed(exit.instanceId, exit.durationMs).catch((err) => log.warn('Could not record play time', { error: String(err) }));
    });
    log.info('Launcher started', { root: paths.root, version: options.launcherVersion });
    return launcher;
  }

  private progress(instanceId: string, stage: string, p?: DownloadProgress): void {
    const event: LaunchProgress = { instanceId, stage, completed: p?.completed, total: p?.total };
    this.emit('progress', event);
  }

  /** True when an Azure client ID is available, so Microsoft sign-in can be offered. */
  get microsoftSignInConfigured(): boolean {
    return /^[0-9a-f-]{36}$/i.test(this.settings.get().accounts.microsoftClientId || this.options.defaultMicrosoftClientId || '');
  }

  get clientJarAvailable(): boolean {
    return !!this.options.clientJarPath && existsSync(this.options.clientJarPath);
  }

  async createInstance(options: CreateInstanceOptions): Promise<InstanceConfig> {
    const rec = recommendMemory();
    const maxMb = this.settings.get().java.defaultMaxMemoryMb ?? rec.recommendedMaxMb;
    return this.instances.create({ memory: { minMb: Math.min(1024, maxMb), maxMb }, ...options });
  }

  /** Installs/validates everything an instance needs and returns what to launch with. */
  async prepare(instanceId: string, signal?: AbortSignal): Promise<{ config: InstanceConfig; version: VersionJson; javaPath: string }> {
    let config = await this.instances.load(instanceId);
    await this.instances.ensureLayout(instanceId);
    const gameDir = this.instances.gameDir(instanceId);
    const mc = config.minecraftVersion;
    const stage = (name: string) => this.progress(instanceId, name);
    const downloadStage = (name: string, p?: DownloadProgress) => this.progress(instanceId, name, p);

    let versionId = mc;
    if (config.loader === 'vanilla') {
      stage('Checking Minecraft files');
      await this.versions.installVanilla(mc, signal, downloadStage);
    } else {
      const loader = this.loaders.get(config.loader);
      if (!loader) throw new Error(`Unsupported mod loader: ${config.loader}`);
      let loaderVersion = config.loaderVersion;
      if (!loaderVersion) {
        stage(`Finding ${loader.displayName} version`);
        const list = await loader.listVersions(mc);
        loaderVersion = (list.find((v) => v.stable) ?? list[0])?.version ?? null;
        if (!loaderVersion) throw new Error(`${loader.displayName} has no release for Minecraft ${mc}.`);
        const chosen = loaderVersion;
        config = await this.instances.update(instanceId, (c) => {
          c.loaderVersion = chosen;
        });
      }
      const installed = await loader.findInstalled(mc, loaderVersion, { versions: this.versions });
      if (installed) {
        stage('Checking game files');
        await this.versions.installFiles(await this.versions.resolve(installed), signal, downloadStage);
        versionId = installed;
      } else {
        const installerJava = loader.id === 'forge' || loader.id === 'neoforge' ? (await this.java.pickFor(21))?.path : undefined;
        versionId = await loader.install(mc, loaderVersion, { versions: this.versions, downloads: this.downloads, paths: this.paths, javaPath: installerJava, signal, onProgress: stage });
      }
    }
    const version = await this.versions.resolve(versionId);

    const required = version.javaVersion?.majorVersion;
    let javaPath: string | null = null;
    if (config.java.executable) {
      const check = await this.java.validateFor(config.java.executable, required);
      if (!check.ok || !check.java) throw new Error(check.message ?? 'The selected Java is not usable.');
      javaPath = check.java.path;
    } else {
      javaPath = (await this.java.pickFor(required))?.path ?? null;
      if (!javaPath && version.javaVersion?.component && this.settings.get().java.autoDownloadRuntime) {
        stage(`Downloading Java ${required}`);
        javaPath = (await this.java.installMojangRuntime(version.javaVersion.component, signal, (p) => downloadStage(`Downloading Java ${required}`, p))).path;
      }
      if (!javaPath) throw new Error(`Java ${required ?? 8} is required. Install it or enable automatic Java downloads in Settings.`);
    }

    if (config.loader === 'fabric' || config.loader === 'quilt') {
      const requests = await this.mods.applyClientRequests(gameDir);
      // A profile chosen at creation (or in-game) is installed on the first launch that needs it.
      const wanted = requests.performanceProfile ?? (config.performanceProfile !== 'none' && config.managedMods.length === 0 ? config.performanceProfile : null);
      const needsInstall = wanted !== null && (wanted !== config.performanceProfile || config.managedMods.length === 0);
      if (needsInstall && PERFORMANCE_PROFILES.some((p) => p.id === wanted)) {
        stage('Applying performance profile');
        await this.applyPerformanceProfile(instanceId, wanted as PerformanceProfileId, signal);
      }
      if (config.clientProfile === 'snowballclient') {
        stage('Installing Snowball Client');
        await this.installSnowballClient(config, gameDir, signal);
      }
    }

    const issues = this.mods.analyze(await this.mods.list(gameDir), mc, config.loader).filter((i) => i.severity === 'error');
    if (issues.length) throw new LaunchBlockedError(issues.map((i) => i.message));
    return { config: await this.instances.load(instanceId), version, javaPath };
  }

  private async installSnowballClient(config: InstanceConfig, gameDir: string, signal?: AbortSignal): Promise<void> {
    if (config.loader !== 'fabric' || config.minecraftVersion !== SNOWBALL_CLIENT_MC) {
      throw new Error(`Snowball Client currently supports Fabric on Minecraft ${SNOWBALL_CLIENT_MC}. Change the instance or turn off the client profile.`);
    }
    const source = this.options.clientJarPath;
    if (!source || !existsSync(source)) {
      throw new Error('The bundled Snowball Client mod is missing. Build the client (client: gradlew build) or reinstall the launcher.');
    }
    const modsDir = join(gameDir, 'mods');
    const dest = join(modsDir, SNOWBALL_CLIENT_MOD_FILE);
    if (!existsSync(dest) || (await sha1File(dest)) !== (await sha1File(source))) await copyFile(source, dest);

    const installed = await this.mods.list(gameDir);
    if (installed.some((m) => m.id === 'fabric-api')) return;
    type ModrinthVersion = { files?: Array<{ url: string; filename: string; primary: boolean; size: number; hashes?: { sha1?: string } }> };
    const query = `game_versions=${encodeURIComponent(JSON.stringify([config.minecraftVersion]))}&loaders=${encodeURIComponent(JSON.stringify(['fabric']))}`;
    const versions = await this.downloads.fetchJson<ModrinthVersion[]>(`https://api.modrinth.com/v2/project/fabric-api/version?${query}`, signal);
    const file = versions?.[0]?.files?.find((f) => f.primary) ?? versions?.[0]?.files?.[0];
    if (!file || !/^https:\/\/cdn\.modrinth\.com\//.test(file.url) || !/\.jar$/i.test(file.filename)) {
      throw new Error('Could not find Fabric API (required by Snowball Client) for this Minecraft version.');
    }
    const name = sanitizeFileName(file.filename.replace(/\.jar$/i, ''), 'fabric-api') + '.jar';
    await this.downloads.download({ url: file.url, dest: safeJoin(modsDir, name), sha1: file.hashes?.sha1, size: file.size, label: 'Fabric API' }, signal);
  }

  /** Installs (or removes, with 'none') the optimisation stack of a performance profile. */
  async applyPerformanceProfile(instanceId: string, profileId: PerformanceProfileId, signal?: AbortSignal): Promise<{ installed: string[]; unavailable: string[] }> {
    const config = await this.instances.load(instanceId);
    const gameDir = this.instances.gameDir(instanceId);
    if (profileId === 'none') {
      await installProfile(gameDir, config.managedMods, [], this.downloads, signal);
      await this.instances.update(instanceId, (c) => {
        c.performanceProfile = 'none';
        c.managedMods = [];
      });
      await this.mods.recordPerformanceProfile(gameDir, 'none');
      return { installed: [], unavailable: [] };
    }
    if (config.loader !== 'fabric' && config.loader !== 'quilt') {
      throw new Error('Performance profiles install Fabric optimisation mods. Switch this instance to Fabric or Quilt first.');
    }
    const profile = getProfile(profileId);
    if (!profile) throw new Error(`Unknown performance profile: ${profileId}`);
    const resolved = await resolveProfile(profile, config.minecraftVersion, config.loader, this.downloads);
    const managed = await installProfile(gameDir, config.managedMods, resolved.files, this.downloads, signal);
    await this.instances.update(instanceId, (c) => {
      c.performanceProfile = profileId;
      c.managedMods = managed;
    });
    await this.mods.recordPerformanceProfile(gameDir, profileId);
    return { installed: resolved.files.map((f) => f.mod.name), unavailable: resolved.unavailable.map((m) => m.name) };
  }

  /** Prepares the instance, signs in the selected account and starts the game process. */
  async launch(instanceId: string, signal?: AbortSignal): Promise<void> {
    if (this.processes.isRunning(instanceId)) throw new Error('This instance is already running.');
    const accounts = this.auth.list();
    const accountId = this.settings.get().accounts.selectedAccountId ?? accounts[0]?.id;
    if (!accountId || !accounts.some((a) => a.id === accountId)) throw new Error('Add an account in Settings before playing.');

    this.progress(instanceId, 'Preparing');
    const prep = await this.prepare(instanceId, signal);
    const session = await this.auth.session(accountId);
    const nativesDir = join(this.instances.instanceDir(instanceId), 'natives');
    await rm(nativesDir, { recursive: true, force: true });
    await this.versions.extractNatives(prep.version, nativesDir);

    const plan = buildLaunchPlan({
      instance: prep.config,
      gameDir: this.instances.gameDir(instanceId),
      version: prep.version,
      paths: this.paths,
      account: session,
      nativesDir,
      launcherName: 'snowball-client-launcher',
      launcherVersion: this.options.launcherVersion,
    });
    this.processes.launch(instanceId, JavaManager.windowless(prep.javaPath), plan.args, this.instances.gameDir(instanceId));
    this.progress(instanceId, 'Running');
  }
}
