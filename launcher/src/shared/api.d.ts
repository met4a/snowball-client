/**
 * Data shapes exchanged between the Electron main process and the renderer. Declared globally
 * (no imports/exports) so the renderer compiles to a plain script with no module loader.
 */
declare namespace Snowball {
  type LoaderId = 'vanilla' | 'fabric' | 'quilt' | 'forge' | 'neoforge';
  type PerformanceProfileId = 'none' | 'balanced' | 'fps-boost' | 'maximum-fps' | 'visual-quality';
  type FolderId = 'root' | 'mods' | 'config' | 'resourcepacks' | 'shaderpacks' | 'saves' | 'screenshots' | 'logs' | 'crash-reports';

  interface Instance {
    id: string;
    name: string;
    minecraftVersion: string;
    loader: LoaderId;
    loaderVersion: string | null;
    javaExecutable: string | null;
    memory: { minMb: number; maxMb: number };
    jvmArgs: string;
    gameArgs: string;
    window: { width: number; height: number; fullscreen: boolean };
    clientProfile: 'snowballclient' | 'none';
    performanceProfile: PerformanceProfileId;
    created: string;
    lastPlayed: string | null;
    totalPlayMs: number;
    running: boolean;
    error?: string;
  }

  interface InstancePatch {
    name?: string;
    minecraftVersion?: string;
    loader?: LoaderId;
    loaderVersion?: string | null;
    javaExecutable?: string | null;
    memory?: { minMb: number; maxMb: number };
    jvmArgs?: string;
    gameArgs?: string;
    window?: { width: number; height: number; fullscreen: boolean };
    clientProfile?: 'snowballclient' | 'none';
  }

  interface CreateInstance {
    name: string;
    minecraftVersion: string;
    loader: LoaderId;
    loaderVersion: string | null;
    clientProfile: 'snowballclient' | 'none';
    performanceProfile: PerformanceProfileId;
  }

  interface Mod {
    fileName: string;
    enabled: boolean;
    name: string;
    id: string | null;
    version: string | null;
    loader: string;
    size: number;
    managed: boolean;
    error?: string;
  }

  interface ModIssue {
    severity: 'error' | 'warning';
    message: string;
    files: string[];
  }

  interface Java {
    path: string;
    version: string;
    majorVersion: number;
    vendor: string;
    arch: string;
    is64Bit: boolean;
    source: 'managed' | 'system' | 'manual';
  }

  interface Account {
    id: string;
    type: 'msa' | 'offline';
    name: string;
    uuid: string;
    signedIn: boolean;
  }

  interface Settings {
    appearance: { reduceMotion: boolean; accent: 'ice' | 'white' };
    downloads: { concurrency: number; retries: number };
    java: { autoDownloadRuntime: boolean; defaultMaxMemoryMb: number | null };
    game: { closeLauncherOnLaunch: boolean; showLogsOnLaunch: boolean };
    accounts: { microsoftClientId: string; selectedAccountId: string | null };
    updates: { channel: 'stable' | 'beta'; checkOnStartup: boolean };
    logs: { retainDays: number; debug: boolean };
    selectedInstanceId: string | null;
  }

  interface PerformanceProfile {
    id: PerformanceProfileId;
    name: string;
    description: string;
    mods: string[];
  }

  interface AppState {
    instances: Instance[];
    accounts: Account[];
    settings: Settings;
    memory: { totalMb: number; recommendedMaxMb: number; safeUpperLimitMb: number };
    profiles: PerformanceProfile[];
    dataRoot: string;
    clientJarAvailable: boolean;
    clientMinecraftVersion: string;
    canAddOffline: boolean;
    microsoftSignInConfigured: boolean;
    launcherVersion: string;
  }

  interface Progress {
    instanceId: string;
    stage: string;
    completed?: number;
    total?: number;
  }

  interface GameLog {
    instanceId: string;
    stream: 'stdout' | 'stderr';
    line: string;
    time: number;
  }

  interface GameExit {
    instanceId: string;
    code: number | null;
    crashed: boolean;
    killedByUser: boolean;
    crashReport: string | null;
    durationMs: number;
  }

  interface LauncherLog {
    time: string;
    level: string;
    scope: string;
    message: string;
  }

  interface MinecraftVersion {
    id: string;
    type: string;
    releaseTime: string;
  }

  interface LoaderVersion {
    version: string;
    stable: boolean;
  }

  type EventName = 'progress' | 'game-log' | 'game-exit' | 'launch-error' | 'launcher-log' | 'state';

  interface Api {
    getState(): Promise<AppState>;
    listMinecraftVersions(includeSnapshots: boolean): Promise<MinecraftVersion[]>;
    listLoaderVersions(loader: LoaderId, minecraftVersion: string): Promise<LoaderVersion[]>;
    createInstance(options: CreateInstance): Promise<Instance>;
    updateInstance(id: string, patch: InstancePatch): Promise<Instance>;
    deleteInstance(id: string): Promise<void>;
    cloneInstance(id: string, name: string): Promise<Instance>;
    openInstanceFolder(id: string, folder: FolderId): Promise<void>;
    listMods(id: string): Promise<{ mods: Mod[]; issues: ModIssue[] }>;
    setModEnabled(id: string, fileName: string, enabled: boolean): Promise<void>;
    removeMod(id: string, fileName: string): Promise<void>;
    addMods(id: string): Promise<{ added: number; errors: string[] }>;
    applyPerformanceProfile(id: string, profile: PerformanceProfileId): Promise<{ installed: string[]; unavailable: string[] }>;
    detectJava(): Promise<Java[]>;
    validateJava(path: string, requiredMajor: number | null): Promise<{ ok: boolean; message?: string; java?: Java }>;
    browseJava(): Promise<string | null>;
    launch(id: string): Promise<void>;
    stop(id: string): Promise<boolean>;
    gameLogs(id: string): Promise<GameLog[]>;
    updateSettings(patch: Partial<Settings>): Promise<Settings>;
    addOfflineAccount(name: string): Promise<Account>;
    removeAccount(id: string): Promise<void>;
    selectAccount(id: string): Promise<void>;
    signInMicrosoft(): Promise<Account>;
    openLauncherFolder(folder: 'root' | 'logs'): Promise<void>;
    on(event: EventName, listener: (payload: any) => void): () => void;
  }
}

interface Window {
  snowball: Snowball.Api;
}
