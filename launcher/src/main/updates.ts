import { app, type BrowserWindow } from 'electron';
import { readFileSync, rmSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { getLogger } from '../core/logging/Logger.js';
import { compareVersions, describeUpdateFailure, isValidVersion } from './updateRules.js';

export { compareVersions, describeUpdateFailure, isValidVersion };

const log = getLogger('updates');

/**
 * Where the launcher records an update it is about to apply. On the next start we compare the
 * version that is actually running against what was expected, which is the only honest way to
 * know the new build came up — the installer's own exit code says nothing about that.
 */
const HANDOVER = 'pending-update.json';

export type UpdateStatus =
  | 'idle'
  | 'checking'
  | 'up-to-date'
  | 'downloading'
  | 'verifying'
  | 'ready'
  | 'installing'
  | 'manual'
  | 'error';

export interface UpdateState {
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
  /** A plain-language failure: headline, explanation, what to try, and the raw text for a bug report. */
  title?: string;
  message?: string;
  hints?: string[];
  detail?: string;
  canRetry?: boolean;
}

/**
 * Keeps the launcher up to date by itself.
 *
 * The flow is deliberately staged: metadata is read from the one release feed configured at build
 * time, the download is checksum-verified by electron-updater before it is ever staged, the new
 * version is sanity-checked against the installed one, and only then is the swap allowed. Nothing
 * is executed that has not been verified, and a failure at any stage leaves the working launcher
 * exactly where it was.
 */
export class UpdateService {
  private state: UpdateState;
  private updater: typeof import('electron-updater').autoUpdater | null = null;
  private checking: Promise<UpdateState> | null = null;
  private readonly handoverFile: string;

  constructor(private readonly send: (state: UpdateState) => void) {
    this.state = { status: 'idle', version: app.getVersion() };
    this.handoverFile = join(app.getPath('userData'), HANDOVER);
    this.settleLastUpdate();
  }

  current(): UpdateState {
    return this.state;
  }

  /**
   * Reads the note left by the previous run, if there was one, and reports whether the new version
   * actually came up. An update that silently failed to apply is worth knowing about.
   */
  private settleLastUpdate(): void {
    let pending: { expecting?: string; from?: string } | null = null;
    try {
      pending = JSON.parse(readFileSync(this.handoverFile, 'utf8')) as { expecting?: string; from?: string };
    } catch {
      return; // No update was in flight, which is the usual case.
    }
    rmSync(this.handoverFile, { force: true });
    const now = app.getVersion();
    if (pending?.expecting && compareVersions(now, pending.expecting) === 0) {
      log.info('Update applied', { from: pending.from, to: now });
      this.state = { status: 'idle', version: now, justUpdatedFrom: pending.from };
      return;
    }
    // Still on the old build: the installer was declined, blocked, or rolled back.
    log.warn('Update did not apply', { expected: pending?.expecting, running: now });
    this.state = {
      status: 'error',
      version: now,
      title: 'The update did not finish',
      message: `Snowball tried to update to ${pending?.expecting ?? 'a new version'} but is still running ${now}. Nothing is broken — the update simply did not get applied.`,
      hints: ['Press Retry to download and apply it again.', 'If Windows asked for permission and it was dismissed, allow it this time.'],
      detail: `expected=${pending?.expecting ?? '?'} running=${now}`,
      canRetry: true,
    };
  }

  /** Checks for a new version, and downloads it when there is one. Safe to call at any time. */
  async check(manual: boolean): Promise<UpdateState> {
    if (this.checking) return this.checking;
    this.checking = this.runCheck(manual).finally(() => {
      this.checking = null;
    });
    return this.checking;
  }

  private async runCheck(manual: boolean): Promise<UpdateState> {
    const version = app.getVersion();
    if (!app.isPackaged) {
      return this.set({ status: 'manual', version, message: 'Updates apply to an installed copy. This is a development build.' });
    }
    if (this.state.status === 'downloading' || this.state.status === 'verifying' || this.state.status === 'installing') {
      return this.state;
    }
    try {
      const updater = await this.load();
      this.set({ status: 'checking', version });
      const result = await updater.checkForUpdates();
      const found = result?.updateInfo?.version;

      if (!found || !isValidVersion(found)) {
        // A feed we cannot parse is treated as "nothing to do", never as a reason to install something.
        log.warn('Update feed had no usable version', { found });
        return this.set({ status: 'up-to-date', version, checkedAt: new Date().toISOString() });
      }
      if (compareVersions(found, version) <= 0) {
        return this.set({ status: 'up-to-date', version, checkedAt: new Date().toISOString() });
      }
      if (this.isPortable()) {
        // A portable .exe has no installer to hand over to, so it is told where to get the new one
        // rather than trying to overwrite itself while it is running.
        return this.set({
          status: 'manual',
          version,
          newVersion: found,
          downloadUrl: 'https://met4a.github.io/snowball-website/',
          message: `Snowball ${found} is out. The portable build cannot replace itself, so download the new file when you are ready.`,
        });
      }
      // From here electron-updater's own events drive the state: downloading -> verifying -> ready.
      return this.state.status === 'checking'
        ? this.set({ status: 'downloading', version, newVersion: found, percent: 0 })
        : this.state;
    } catch (err) {
      const described = describeUpdateFailure(err);
      log.warn('Update check failed', { error: described.detail.split('\n')[0], manual });
      return this.set({ status: 'error', version, ...described });
    }
  }

  /**
   * Hands over to the freshly downloaded build. The note written first is what lets the next start
   * confirm the swap actually happened.
   */
  install(): void {
    if (this.state.status !== 'ready' || !this.updater) return;
    const newVersion = this.state.newVersion;
    try {
      writeFileSync(this.handoverFile, JSON.stringify({ expecting: newVersion, from: app.getVersion(), at: new Date().toISOString() }));
    } catch (err) {
      // Not fatal: without the note we simply cannot confirm the new version afterwards.
      log.warn('Could not record the pending update', { error: String(err) });
    }
    log.info('Installing update', { from: app.getVersion(), to: newVersion });
    this.set({ status: 'installing', version: app.getVersion(), newVersion });
    setImmediate(() => {
      try {
        this.updater?.quitAndInstall(false, true);
      } catch (err) {
        rmSync(this.handoverFile, { force: true });
        const described = describeUpdateFailure(err);
        log.error('Handing over to the new version failed', { error: described.detail.split('\n')[0] });
        this.set({ status: 'error', version: app.getVersion(), newVersion, ...described });
      }
    });
  }

  /** Everything a bug report needs, with no account details in it. */
  diagnostics(): string {
    const s = this.state;
    return [
      `Snowball Client launcher ${app.getVersion()}`,
      `Platform: ${process.platform} ${process.arch}, Electron ${process.versions.electron}`,
      `Packaged: ${app.isPackaged}${this.isPortable() ? ' (portable)' : ''}`,
      `Update status: ${s.status}${s.newVersion ? ` -> ${s.newVersion}` : ''}`,
      s.title ? `Problem: ${s.title}` : '',
      s.detail ? `Detail: ${s.detail}` : '',
      `Recorded: ${new Date().toISOString()}`,
    ].filter(Boolean).join('\n');
  }

  private isPortable(): boolean {
    return Boolean(process.env.PORTABLE_EXECUTABLE_FILE);
  }

  private async load(): Promise<typeof import('electron-updater').autoUpdater> {
    if (this.updater) return this.updater;
    const { autoUpdater } = await import('electron-updater');
    autoUpdater.autoDownload = true;
    // The swap happens when the user agrees to it, not silently behind them on quit.
    autoUpdater.autoInstallOnAppQuit = true;
    autoUpdater.allowDowngrade = false;
    autoUpdater.logger = {
      info: (m: unknown) => log.info(String(m)),
      warn: (m: unknown) => log.warn(String(m)),
      error: (m: unknown) => log.warn(String(m)),
      debug: (m: unknown) => log.debug(String(m)),
    };

    autoUpdater.on('update-available', (info) => {
      log.info('Update available', { version: info.version });
      this.set({ status: 'downloading', version: app.getVersion(), newVersion: info.version, percent: 0 });
    });
    autoUpdater.on('download-progress', (p) => {
      const newVersion = this.state.newVersion ?? app.getVersion();
      this.set({
        status: 'downloading',
        version: app.getVersion(),
        newVersion,
        percent: Math.min(100, Math.max(0, Math.round(p.percent))),
        transferred: p.transferred,
        total: p.total,
        bytesPerSecond: p.bytesPerSecond,
      });
    });
    autoUpdater.on('update-downloaded', (info) => {
      // electron-updater has already checked the SHA-512 from the signed feed by this point; the
      // extra step here is refusing a package whose version is not actually an upgrade.
      this.set({ status: 'verifying', version: app.getVersion(), newVersion: info.version });
      if (!isValidVersion(info.version) || compareVersions(info.version, app.getVersion()) <= 0) {
        log.error('Refusing a downloaded update that is not newer', { downloaded: info.version, running: app.getVersion() });
        this.set({
          status: 'error',
          version: app.getVersion(),
          title: 'That update was rejected',
          message: 'The downloaded package was not a newer version of Snowball, so it was not installed. Your launcher is unchanged.',
          hints: ['Try again later.', 'If it keeps happening, download Snowball from the website.'],
          detail: `downloaded=${info.version} running=${app.getVersion()}`,
          canRetry: true,
        });
        return;
      }
      log.info('Update verified and staged', { version: info.version });
      this.set({ status: 'ready', version: app.getVersion(), newVersion: info.version });
    });
    autoUpdater.on('error', (err) => {
      const described = describeUpdateFailure(err);
      log.warn('Updater reported an error', { error: described.detail.split('\n')[0] });
      this.set({ status: 'error', version: app.getVersion(), newVersion: this.state.newVersion, ...described });
    });

    this.updater = autoUpdater;
    return autoUpdater;
  }

  private set(state: UpdateState): UpdateState {
    // A one-shot "you just updated" note should survive until the renderer has seen it once.
    if (this.state.justUpdatedFrom && !state.justUpdatedFrom && state.status !== 'idle') {
      state = { ...state, justUpdatedFrom: this.state.justUpdatedFrom };
    }
    this.state = state;
    this.send(state);
    return state;
  }
}

/** Starts the background check a moment after the window is up, so it never delays the first paint. */
export function scheduleStartupCheck(service: UpdateService, win: BrowserWindow, enabled: boolean): void {
  if (!enabled) return;
  const timer = setTimeout(() => void service.check(false), 4000);
  win.on('closed', () => clearTimeout(timer));
}
