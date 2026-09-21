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
    /** Loader name for this instance's version ("Legacy Fabric" for Fabric on 1.13.2 and older). */
    loaderName: string;
    loaderVersion: string | null;
    /** Modrinth loader tags this instance can install mods for. */
    modLoaders: string[];
    javaExecutable: string | null;
    memory: { minMb: number; maxMb: number };
    jvmArgs: string;
    gameArgs: string;
    window: { width: number; height: number; fullscreen: boolean };
    /** Snowball Client build for this instance's version and loader; supported is false when none exists yet. */
    snowball: { supported: boolean; version: string | null };
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
  }

  interface CreateInstance {
    name: string;
    minecraftVersion: string;
    loader: LoaderId;
    loaderVersion: string | null;
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
    /** The Fabric API a Snowball instance needs ("required") can be updated but not removed or turned off. */
    protection: 'required' | null;
    error?: string;
  }

  interface CoreReport {
    supported: boolean;
    build: { version: string; minecraft: string } | null;
    client: 'unsupported' | 'ok' | 'missing' | 'damaged';
    clientJar: string | null;
    fabricApi: 'ok' | 'missing' | 'disabled' | 'not-needed';
    problems: string[];
  }

  interface ActivityEvent {
    instanceId: string;
    time: number;
    level: 'info' | 'success' | 'warn' | 'error';
    message: string;
  }

  type ModSort = 'relevance' | 'downloads' | 'follows' | 'updated' | 'newest';

  interface ModSearchOptions {
    query: string;
    loader: string | null;
    gameVersion: string | null;
    sort: ModSort;
    offset: number;
  }

  interface ModSearchHit {
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

  interface ModUpdate {
    fileName: string;
    projectId: string;
    currentVersion: string;
    newVersion: string;
  }

  interface ModIssue {
    severity: 'error' | 'warning';
    message: string;
    files: string[];
    /** Set when a mod needs another mod; slug is present when it can be installed straight from Modrinth. */
    dependency?: { id: string; name: string; slug: string | null };
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
    accounts: { microsoftClientId: string; selectedAccountId: string | null; rank: string };
    updates: { channel: 'stable' | 'beta'; automatic: boolean };
    performance: { installMods: boolean };
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
    /** Snowball Client builds shipped with this launcher, newest first. */
    snowballBuilds: Array<{ version: string; minecraft: string }>;
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
    level?: string;
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

  type ScanVerdict = 'clean' | 'watch' | 'suspicious' | 'dangerous';

  interface ScanFinding {
    title: string;
    detail: string;
    where: string;
    weight: number;
  }

  interface ScanResult {
    fileName: string;
    sha1: string;
    sizeBytes: number;
    verdict: ScanVerdict;
    score: number;
    findings: ScanFinding[];
    modId: string | null;
    scannedAt: string;
    error?: string;
  }

  interface FoundInstance {
    id: string;
    launcher: string;
    name: string;
    minecraftVersion: string;
    loader: LoaderId;
    loaderVersion: string | null;
    gameDir: string;
    mods: number;
    worlds: number;
    resourcePacks: number;
  }

  interface ImportOptions {
    mods: boolean;
    config: boolean;
    resourcePacks: boolean;
    shaderPacks: boolean;
    saves: boolean;
    options: boolean;
  }

  interface ChatMessage {
    id: string;
    name: string;
    uuid: string;
    text: string;
    at: string;
    staff?: boolean;
    system?: boolean;
    rank?: string;
  }

  interface RankLookup {
    found: boolean;
    name: string;
    uuid?: string;
    rank?: string;
    given?: { rank: string; at: number; by: string; byName: string } | null;
    history?: { rank: string; at: number; by: string; byName: string }[];
    seen?: { name: string; first: number; last: number; rank: string } | null;
  }

  interface SnowballStats {
    /** The banner everyone sees, served without needing a chat connection. */
    announcement?: string | null;
    online: number;
    today: number;
    week: number;
    total: number;
  }

  interface BugReport {
    id: string;
    title: string;
    detail: string;
    steps?: string;
    minecraft?: string;
    snowball?: string;
    loader?: string;
    by: string;
    uuid: string;
    at: string;
    status: 'open' | 'investigating' | 'fixed' | 'duplicate' | 'invalid';
  }

  interface SnowballPerson {
    uuid: string;
    name: string;
    rank: string;
    first: number;
    last: number;
    muted: boolean;
  }

  interface ChatState {
    configured: boolean;
    status: 'offline' | 'connecting' | 'online' | 'error';
    online: number;
    announcement: string | null;
    admin: boolean;
    rank: string;
    permissions: string[];
    flags: Record<string, boolean>;
    message?: string;
  }

  interface ReleaseNote {
    version: string;
    sections: Array<{ heading: string; items: string[] }>;
  }

  type UpdateStatus = 'idle' | 'checking' | 'up-to-date' | 'downloading' | 'verifying' | 'ready' | 'installing' | 'manual' | 'error';

  interface UpdateState {
    status: UpdateStatus;
    /** The version running right now. */
    version: string;
    newVersion?: string;
    percent?: number;
    transferred?: number;
    total?: number;
    bytesPerSecond?: number;
    checkedAt?: string;
    /** Set once after an update lands, so the launcher can say what changed. */
    justUpdatedFrom?: string;
    /** Where to get the build by hand, for the portable copy that cannot replace itself. */
    downloadUrl?: string;
    /** A failure in plain language, with the raw text kept separately for bug reports. */
    title?: string;
    message?: string;
    hints?: string[];
    detail?: string;
    canRetry?: boolean;
  }

  type EventName = 'progress' | 'game-log' | 'game-exit' | 'launch-error' | 'launcher-log' | 'state' | 'activity' | 'update' | 'chat-state' | 'chat-message' | 'chat-history' | 'chat-people' | 'chat-bugs' | 'chat-bug-filed' | 'chat-lookup' | 'chat-rank-set' | 'chat-history-ranks';

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
    searchMods(options: ModSearchOptions): Promise<{ hits: ModSearchHit[]; total: number; offset: number }>;
    installMod(id: string, projectId: string): Promise<{ installed: string[] }>;
    installedModProjects(id: string): Promise<string[]>;
    checkModUpdates(id: string): Promise<ModUpdate[]>;
    updateMod(id: string, fileName: string): Promise<{ oldFile: string; newFile: string; version: string }>;
    applyPerformanceProfile(id: string, profile: PerformanceProfileId): Promise<{ installed: string[]; unavailable: string[] }>;
    detectJava(): Promise<Java[]>;
    validateJava(path: string, requiredMajor: number | null): Promise<{ ok: boolean; message?: string; java?: Java }>;
    browseJava(): Promise<string | null>;
    launch(id: string): Promise<void>;
    stop(id: string): Promise<boolean>;
    gameLogs(id: string): Promise<GameLog[]>;
    gameActivity(id: string): Promise<ActivityEvent[]>;
    coreStatus(id: string): Promise<CoreReport>;
    repairCore(id: string): Promise<CoreReport>;
    /** Re-checks every Minecraft file of an instance and downloads anything missing or damaged. */
    verifyGameFiles(id: string): Promise<{ verified: true }>;
    snowballSupport(minecraftVersion: string, loader: LoaderId): Promise<{ supported: boolean; version: string | null; fabricApi: string; performanceProfiles: boolean }>;
    updateSettings(patch: Partial<Settings>): Promise<Settings>;
    addOfflineAccount(name: string): Promise<Account>;
    removeAccount(id: string): Promise<void>;
    selectAccount(id: string): Promise<void>;
    signInMicrosoft(): Promise<Account>;
    openLauncherFolder(folder: 'root' | 'logs'): Promise<void>;
    scanMods(instanceId: string): Promise<ScanResult[]>;
    findOtherLaunchers(): Promise<FoundInstance[]>;
    importFromLauncher(instance: FoundInstance, options: Partial<ImportOptions>): Promise<Instance>;
    chatState(): Promise<ChatState>;
    joinChat(): Promise<ChatState>;
    leaveChat(): Promise<void>;
    sendChat(text: string): Promise<{ ok: boolean; reason?: string }>;
    announce(text: string | null): Promise<{ ok: boolean; reason?: string }>;
    moderateChat(action: 'mute' | 'unmute' | 'clear', uuid?: string, minutes?: number): Promise<{ ok: boolean; reason?: string }>;
    setSnowballPlus(uuid: string, on: boolean): Promise<{ ok: boolean; reason?: string }>;
    setRank(uuid: string, rank: string): Promise<{ ok: boolean; reason?: string }>;
    lookupPlayer(name: string): Promise<{ ok: boolean; reason?: string }>;
    snowballStats(): Promise<SnowballStats | null>;
    reportBug(report: Record<string, string>): Promise<{ ok: boolean; reason?: string }>;
    chatAdmin(action: string, extra?: Record<string, unknown>): Promise<{ ok: boolean; reason?: string }>;
    appVersion(): Promise<string>;
    updateState(): Promise<UpdateState>;
    checkForUpdates(): Promise<UpdateState | null>;
    installUpdate(): Promise<void>;
    updateDiagnostics(): Promise<string>;
    releaseNotes(version?: string): Promise<ReleaseNote | ReleaseNote[] | null>;
    copyLogs(): Promise<string>;
    openLogFolder(): Promise<void>;
    on(event: EventName, listener: (payload: any) => void): () => void;
  }
}

interface Window {
  snowball: Snowball.Api;
}
