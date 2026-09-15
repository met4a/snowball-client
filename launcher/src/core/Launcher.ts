import { EventEmitter } from 'node:events';
import { mkdir, readFile, rm } from 'node:fs/promises';
import { join } from 'node:path';
import { AuthManager, type SecretCipher } from './auth/AuthManager.js';
import { DownloadManager, type DownloadProgress } from './download/DownloadManager.js';
import { InstanceManager, type CreateInstanceOptions, type InstanceConfig, type LoaderId, type PerformanceProfileId } from './instance/InstanceManager.js';
import { JavaManager, recommendMemory } from './java/JavaManager.js';
import { ActivityLog } from './logging/Activity.js';
import { getLogger, logSink, LogLevel } from './logging/Logger.js';
import type { VersionJson } from './minecraft/types.js';
import { VersionManager } from './minecraft/VersionManager.js';
import { ModLoaderRegistry } from './modloader/ModLoaderRegistry.js';
import { ModManager } from './mods/ModManager.js';
import { ModrinthService } from './mods/Modrinth.js';
import { getProfile, installProfile, PERFORMANCE_PROFILES, resolveProfile } from './performance/PerformanceProfiles.js';
import { GameActivityInterpreter } from './process/GameOutput.js';
import { buildLaunchPlan } from './process/LaunchArguments.js';
import { ProcessManager, type GameExit, type GameRecordEvent } from './process/ProcessManager.js';
import { SettingsStore } from './settings/LauncherSettings.js';
import { ClientBuildRegistry, SnowballCore, type CoreReport, type CoreTarget } from './snowball/SnowballCore.js';
import { createLauncherPaths, type LauncherPaths } from './util/paths.js';

const log = getLogger('launcher');

/** Progress stages that are not worth a line in the activity timeline. */
const QUIET_STAGES = new Set(['Preparing', 'Running']);

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
  /** Folders holding the Snowball Client jars shipped with the launcher (missing folders are ignored). */
  clientBuildDirs: string[];
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
  /** Readable per-instance timeline, shown as the Activity view. */
  readonly activity = new ActivityLog();
  private readonly interpreters = new Map<string, GameActivityInterpreter>();
  private readonly lastStage = new Map<string, string>();

  private constructor(
    readonly paths: LauncherPaths,
    readonly settings: SettingsStore,
    readonly downloads: DownloadManager,
    readonly versions: VersionManager,
    readonly java: JavaManager,
    readonly instances: InstanceManager,
    readonly mods: ModManager,
    readonly modrinth: ModrinthService,
    readonly core: SnowballCore,
    readonly loaders: ModLoaderRegistry,
    readonly auth: AuthManager,
    readonly processes: ProcessManager,
    private readonly options: LauncherOptions,
  ) {
    super();
  }

  static async create(options: LauncherOptions): Promise<Launcher> {
    const paths = createLauncherPaths(options.root);
    for (const dir of [paths.root, paths.instances, paths.versions, paths.libraries, paths.assets, paths.runtimes, paths.cache, paths.logs, paths.client]) {
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
    const modrinth = new ModrinthService(downloads, mods);
    const core = new SnowballCore(
      await ClientBuildRegistry.discover(options.clientBuildDirs),
      { ensure: (gameDir, minecraftVersion, signal) => modrinth.ensureFabricApi(gameDir, minecraftVersion, signal) },
      paths.client,
    );
    const loaders = new ModLoaderRegistry(downloads);
    const auth = new AuthManager(paths.accountsFile, options.cipher, () => settings.get().accounts.microsoftClientId || options.defaultMicrosoftClientId || '');
    await auth.load();
    const processes = new ProcessManager();

    const launcher = new Launcher(paths, settings, downloads, versions, java, instances, mods, modrinth, core, loaders, auth, processes, options);
    processes.on('record', ({ instanceId, record }: GameRecordEvent) => {
      for (const line of launcher.interpreters.get(instanceId)?.interpret(record) ?? []) launcher.activity.add(instanceId, line.level, line.message);
    });
    processes.on('exit', (exit: GameExit) => {
      instances.markPlayed(exit.instanceId, exit.durationMs).catch((err) => log.warn('Could not record play time', { error: String(err) }));
      launcher.interpreters.delete(exit.instanceId);
      launcher.lastStage.delete(exit.instanceId);
      void launcher.reportExit(exit);
    });
    log.info('Launcher started', { root: paths.root, version: options.launcherVersion });
    return launcher;
  }

  private progress(instanceId: string, stage: string, p?: DownloadProgress): void {
    const event: LaunchProgress = { instanceId, stage, completed: p?.completed, total: p?.total };
    this.emit('progress', event);
    // A new stage is also a readable activity line; download counters for the same stage are not repeated.
    if (!QUIET_STAGES.has(stage) && this.lastStage.get(instanceId) !== stage) this.activity.add(instanceId, 'info', stage);
    this.lastStage.set(instanceId, stage);
  }

  /** True when an Azure client ID is available, so Microsoft sign-in can be offered. */
  get microsoftSignInConfigured(): boolean {
    return /^[0-9a-f-]{36}$/i.test(this.settings.get().accounts.microsoftClientId || this.options.defaultMicrosoftClientId || '');
  }

  /** Whether this launcher has a Snowball Client build for a Minecraft version and loader. */
  snowballSupport(minecraftVersion: string, loader: LoaderId): { supported: boolean; version: string | null } {
    const build = this.core.registry.find(minecraftVersion, loader);
    return { supported: build !== null, version: build?.version ?? null };
  }

  private coreTarget(instanceId: string, config: InstanceConfig): CoreTarget {
    return { gameDir: this.instances.gameDir(instanceId), minecraftVersion: config.minecraftVersion, loader: config.loader };
  }

  async inspectCore(instanceId: string): Promise<CoreReport> {
    return this.core.inspect(this.coreTarget(instanceId, await this.instances.load(instanceId)));
  }

  async repairCore(instanceId: string, signal?: AbortSignal): Promise<CoreReport> {
    return this.core.ensure(this.coreTarget(instanceId, await this.instances.load(instanceId)), this.activity.reporter(instanceId), signal);
  }

  /**
   * Creates an instance ready to play: the loader version is chosen, Fabric API and Snowball Client are
   * installed and verified. Steps that need the internet and fail (e.g. offline) finish on the next Play.
   */
  async createInstance(options: CreateInstanceOptions): Promise<InstanceConfig> {
    const rec = recommendMemory();
    const maxMb = this.settings.get().java.defaultMaxMemoryMb ?? rec.recommendedMaxMb;
    let config = await this.instances.create({ memory: { minMb: Math.min(1024, maxMb), maxMb }, ...options });
    const id = config.id;
    const say = this.activity.reporter(id);
    say('info', `Created ${config.name} for Minecraft ${config.minecraftVersion}`);

    if (config.loader !== 'vanilla' && !config.loaderVersion) {
      try {
        config = await this.pinLoaderVersion(id, config);
      } catch (err) {
        say('warn', `Could not choose a loader version yet (${(err as Error).message}). The launcher will try again when you press Play.`);
      }
    }
    if (config.pendingFabricApi) await this.installPendingFabricApi(id);
    if (this.core.registry.find(config.minecraftVersion, config.loader)) {
      try {
        await this.core.ensure(this.coreTarget(id, config), say);
        say('success', `${config.name} is ready to play`);
      } catch (err) {
        say('warn', `Snowball Client setup will finish when you press Play (${(err as Error).message})`);
      }
    }
    return this.instances.load(id);
  }

  /** Stores the newest stable loader version for the instance's Minecraft version. */
  private async pinLoaderVersion(instanceId: string, config: InstanceConfig): Promise<InstanceConfig> {
    const loader = this.loaders.get(config.loader);
    if (!loader) throw new Error(`Unsupported mod loader: ${config.loader}`);
    const list = await loader.listVersions(config.minecraftVersion);
    const chosen = (list.find((v) => v.stable) ?? list[0])?.version;
    if (!chosen) throw new Error(`${loader.displayName} has no release for Minecraft ${config.minecraftVersion}.`);
    this.activity.add(instanceId, 'info', `Using ${loader.displayName} ${chosen}`);
    return this.instances.update(instanceId, (c) => {
      c.loaderVersion = chosen;
    });
  }

  /**
   * Adds Fabric API once to a new Fabric instance. When it cannot be installed yet (offline, or no
   * build for this Minecraft version) it stays pending and is retried on the next launch.
   */
  private async installPendingFabricApi(instanceId: string, signal?: AbortSignal): Promise<void> {
    const config = await this.instances.load(instanceId);
    if (!config.pendingFabricApi) return;
    try {
      if (await this.modrinth.ensureFabricApi(this.instances.gameDir(instanceId), config.minecraftVersion, signal)) {
        this.activity.add(instanceId, 'success', 'Fabric API installed');
      }
      await this.instances.update(instanceId, (c) => {
        c.pendingFabricApi = false;
      });
    } catch (err) {
      if (signal?.aborted) throw err;
      log.warn('Fabric API could not be installed yet', { instanceId, error: (err as Error).message });
    }
  }

  /** Updates a Modrinth mod and keeps the performance profile's file list pointing at the new jar. */
  async updateMod(instanceId: string, fileName: string, signal?: AbortSignal): Promise<{ oldFile: string; newFile: string; version: string }> {
    const config = await this.instances.load(instanceId);
    const result = await this.modrinth.update({ gameDir: this.instances.gameDir(instanceId), minecraftVersion: config.minecraftVersion, loader: config.loader }, fileName, signal);
    const oldBase = result.oldFile.replace(/\.disabled$/i, '');
    if (config.managedMods.includes(oldBase)) {
      const newBase = result.newFile.replace(/\.disabled$/i, '');
      await this.instances.update(instanceId, (c) => {
        c.managedMods = c.managedMods.map((m) => (m === oldBase ? newBase : m));
      });
    }
    return result;
  }

  /** Installs/validates everything an instance needs and returns what to launch with. */
  async prepare(instanceId: string, signal?: AbortSignal): Promise<{ config: InstanceConfig; version: VersionJson; javaPath: string; clientJar: string | null }> {
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
      if (!config.loaderVersion) {
        stage(`Finding ${loader.displayName} version`);
        config = await this.pinLoaderVersion(instanceId, config);
      }
      const loaderVersion = config.loaderVersion!;
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
      if (config.pendingFabricApi) {
        stage('Installing Fabric API');
        await this.installPendingFabricApi(instanceId, signal);
      }
      const requests = await this.mods.applyClientRequests(gameDir);
      // A profile chosen at creation (or in-game) is installed on the first launch that needs it.
      const wanted = requests.performanceProfile ?? (config.performanceProfile !== 'none' && config.managedMods.length === 0 ? config.performanceProfile : null);
      const needsInstall = wanted !== null && (wanted !== config.performanceProfile || config.managedMods.length === 0);
      if (needsInstall && PERFORMANCE_PROFILES.some((p) => p.id === wanted)) {
        stage('Applying performance profile');
        await this.applyPerformanceProfile(instanceId, wanted as PerformanceProfileId, signal);
      }
    }

    // Snowball Client is verified and repaired before every launch; on versions without a build, a
    // stray copy is set aside so it cannot stop the game from starting.
    if (this.core.registry.find(mc, config.loader)) stage('Checking core files');
    const core = await this.core.ensure(this.coreTarget(instanceId, config), this.activity.reporter(instanceId), signal);

    const issues = this.mods.analyze(await this.mods.list(gameDir), mc, config.loader).filter((i) => i.severity === 'error');
    if (issues.length) throw new LaunchBlockedError(issues.map((i) => i.message));
    return { config: await this.instances.load(instanceId), version, javaPath, clientJar: core.clientJar };
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
    const say = this.activity.reporter(instanceId);
    try {
      if (this.processes.isRunning(instanceId)) throw new Error('This instance is already running.');
      const accounts = this.auth.list();
      const accountId = this.settings.get().accounts.selectedAccountId ?? accounts[0]?.id;
      if (!accountId || !accounts.some((a) => a.id === accountId)) throw new Error('Add an account in Settings before playing.');

      this.lastStage.delete(instanceId);
      this.progress(instanceId, 'Preparing');
      say('info', `Preparing ${(await this.instances.load(instanceId)).name}`);
      const prep = await this.prepare(instanceId, signal);
      const session = await this.auth.session(accountId);
      say('info', `Signed in as ${session.name}`);
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
        // Snowball Client is loaded straight from the launcher's folder, so it is never a file in the instance.
        extraJvmArgs: prep.clientJar ? [`-Dfabric.addMods=${prep.clientJar}`] : [],
      });
      say('info', 'Starting Minecraft...');
      this.interpreters.set(instanceId, new GameActivityInterpreter({ minecraftVersion: prep.config.minecraftVersion, loader: prep.config.loader }));
      this.processes.launch(instanceId, JavaManager.windowless(prep.javaPath), plan.args, this.instances.gameDir(instanceId));
      this.progress(instanceId, 'Running');
    } catch (err) {
      say('error', `Could not start: ${(err as Error).message}`);
      throw err;
    }
  }

  private async reportExit(exit: GameExit): Promise<void> {
    const duration = formatDuration(exit.durationMs);
    if (exit.killedByUser) {
      this.activity.add(exit.instanceId, 'info', 'Minecraft was stopped');
    } else if (exit.crashed) {
      const reason = exit.crashReport ? await crashDescription(exit.crashReport) : null;
      this.activity.add(exit.instanceId, 'error', `Minecraft crashed after ${duration}${reason ? `: ${reason}` : ''}`);
    } else {
      this.activity.add(exit.instanceId, 'success', `Minecraft closed after ${duration}`);
    }
  }
}

function formatDuration(ms: number): string {
  const seconds = Math.max(0, Math.round(ms / 1000));
  if (seconds < 60) return `${seconds}s`;
  const minutes = Math.floor(seconds / 60);
  return minutes < 60 ? `${minutes}m` : `${Math.floor(minutes / 60)}h ${minutes % 60}m`;
}

/** The "Description:" line of a crash report, e.g. "Rendering overlay". */
async function crashDescription(path: string): Promise<string | null> {
  try {
    return /^Description: (.+)$/m.exec(await readFile(path, 'utf8'))?.[1].trim().slice(0, 200) ?? null;
  } catch (err) {
    log.warn('Could not read crash report', { path, error: (err as Error).message });
    return null;
  }
}
