import { app, type BrowserWindow } from 'electron';
import { getLogger } from '../core/logging/Logger.js';

const log = getLogger('updates');

export type UpdateState =
  | { status: 'idle'; version: string }
  | { status: 'checking'; version: string }
  | { status: 'downloading'; version: string; newVersion: string; percent: number }
  | { status: 'ready'; version: string; newVersion: string }
  | { status: 'up-to-date'; version: string; checkedAt: string }
  | { status: 'unsupported'; version: string; reason: string }
  | { status: 'error'; version: string; message: string };

/**
 * Keeps the launcher up to date by itself: a new version is downloaded in the background and put in
 * place the next time the launcher is closed, so nobody has to run an installer again. The portable
 * build has no installer to update, so it is told where to get the new file instead.
 */
export class UpdateService {
  private state: UpdateState;
  private updater: typeof import('electron-updater').autoUpdater | null = null;

  constructor(private readonly send: (state: UpdateState) => void) {
    this.state = { status: 'idle', version: app.getVersion() };
  }

  current(): UpdateState {
    return this.state;
  }

  /** Checks for a new version; downloads it when there is one. Safe to call at any time. */
  async check(manual: boolean): Promise<UpdateState> {
    const version = app.getVersion();
    if (!app.isPackaged) return this.set({ status: 'unsupported', version, reason: 'Updates only apply to an installed copy.' });
    if (process.env.PORTABLE_EXECUTABLE_FILE) {
      return this.set({ status: 'unsupported', version, reason: 'The portable build cannot update itself; download the new file from the website.' });
    }
    if (this.state.status === 'checking' || this.state.status === 'downloading') return this.state;
    try {
      const updater = await this.load();
      this.set({ status: 'checking', version });
      const result = await updater.checkForUpdates();
      if (!result || !result.updateInfo || result.updateInfo.version === version) {
        return this.set({ status: 'up-to-date', version, checkedAt: new Date().toISOString() });
      }
      return this.state;
    } catch (err) {
      const message = err instanceof Error ? err.message : String(err);
      log.warn('Update check failed', { error: message, manual });
      return this.set({ status: 'error', version, message });
    }
  }

  /** Restarts into the new version. Only valid once an update has been downloaded. */
  install(): void {
    if (this.state.status !== 'ready' || !this.updater) return;
    log.info('Installing update', { version: this.state.newVersion });
    setImmediate(() => this.updater?.quitAndInstall(false, true));
  }

  private async load(): Promise<typeof import('electron-updater').autoUpdater> {
    if (this.updater) return this.updater;
    const { autoUpdater } = await import('electron-updater');
    autoUpdater.autoDownload = true;
    autoUpdater.autoInstallOnAppQuit = true;
    autoUpdater.logger = {
      info: (m: unknown) => log.info(String(m)),
      warn: (m: unknown) => log.warn(String(m)),
      error: (m: unknown) => log.warn(String(m)),
      debug: (m: unknown) => log.debug(String(m)),
    };
    autoUpdater.on('update-available', (info) => this.set({ status: 'downloading', version: app.getVersion(), newVersion: info.version, percent: 0 }));
    autoUpdater.on('download-progress', (progress) => {
      const newVersion = this.state.status === 'downloading' ? this.state.newVersion : app.getVersion();
      this.set({ status: 'downloading', version: app.getVersion(), newVersion, percent: Math.round(progress.percent) });
    });
    autoUpdater.on('update-downloaded', (info) => this.set({ status: 'ready', version: app.getVersion(), newVersion: info.version }));
    autoUpdater.on('error', (err) => this.set({ status: 'error', version: app.getVersion(), message: err.message }));
    this.updater = autoUpdater;
    return autoUpdater;
  }

  private set(state: UpdateState): UpdateState {
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
