import { app, BrowserWindow, dialog, Menu, safeStorage } from 'electron';
import { appendFileSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import type { SecretCipher } from '../core/auth/AuthManager.js';
import { Launcher } from '../core/Launcher.js';
import { getLogger } from '../core/logging/Logger.js';
import { defaultDataRoot } from '../core/util/paths.js';
import { registerIpc } from './ipc.js';
import { ChatClient } from '../core/social/ChatClient.js';
import { scheduleStartupCheck, UpdateService } from './updates.js';

const log = getLogger('main');

/** Refresh tokens are encrypted with the operating system keychain (DPAPI / Keychain / libsecret). */
const cipher: SecretCipher = {
  isAvailable: () => safeStorage.isEncryptionAvailable(),
  encrypt: (plain) => safeStorage.encryptString(plain).toString('base64'),
  decrypt: (encoded) => safeStorage.decryptString(Buffer.from(encoded, 'base64')),
};

function clientBuildDirs(): string[] {
  // Packaged builds ship the client jars as extra resources; development uses the Gradle output.
  return app.isPackaged ? [join(process.resourcesPath, 'client')] : [join(app.getAppPath(), '..', 'client', 'build', 'libs', 'launcher')];
}

/** Build-time configuration shipped inside the app (e.g. the Azure client ID for Microsoft sign-in). */
function appConfig(): { microsoftClientId?: unknown; chatUrl?: unknown; adminUuid?: unknown; discordAppId?: unknown } {
  try {
    return JSON.parse(readFileSync(join(app.getAppPath(), 'app-config.json'), 'utf8')) as Record<string, unknown>;
  } catch {
    return {};
  }
}

function microsoftClientIdFromConfig(): string {
  const value = appConfig().microsoftClientId;
  return typeof value === 'string' ? value.trim() : '';
}

async function createWindow(): Promise<void> {
  const launcher = await Launcher.create({
    root: defaultDataRoot(),
    cipher,
    clientBuildDirs: clientBuildDirs(),
    launcherVersion: app.getVersion(),
    defaultMicrosoftClientId: microsoftClientIdFromConfig(),
    discordAppId: typeof appConfig().discordAppId === "string" ? String(appConfig().discordAppId) : "",
    chatUrl: typeof appConfig().chatUrl === "string" ? String(appConfig().chatUrl).trim() : "",
  });
  const win = new BrowserWindow({
    width: 1200,
    height: 760,
    // Small enough for a 1280x720 screen at 150% scaling (853x480, less the taskbar); every page
    // is checked for overflow down to this size.
    minWidth: 800,
    minHeight: 460,
    title: 'Snowball Client',
    backgroundColor: '#05070a',
    // The tile icon, so the window matches the .exe. The bare snowball stays the mark used
    // inside the interface.
    icon: join(__dirname, '..', 'renderer', 'assets', 'app-icon.png'),
    autoHideMenuBar: true,
    show: false,
    webPreferences: {
      preload: join(__dirname, 'preload.js'),
      contextIsolation: true,
      sandbox: true,
      nodeIntegration: false,
      spellcheck: false,
    },
  });
  const updates = new UpdateService((state) => {
    if (!win.isDestroyed()) win.webContents.send('evt:update', state);
  });
  const config = appConfig();
  const chat = new ChatClient(typeof config.chatUrl === 'string' ? config.chatUrl.trim() : '', typeof config.adminUuid === 'string' ? config.adminUuid.trim() : '');
  Menu.setApplicationMenu(null);
  win.webContents.setWindowOpenHandler(() => ({ action: 'deny' }));
  win.webContents.on('will-navigate', (event) => event.preventDefault());
  win.once('ready-to-show', () => win.show());
  registerIpc(launcher, win, updates, chat);
  await win.loadFile(join(__dirname, '..', 'renderer', 'index.html'));
  // Restarting is fine while somebody is browsing mods; it is not fine while they are playing.
  updates.onlyRestartWhen(() => launcher.processes.runningIds().length === 0);
  scheduleStartupCheck(updates, win, launcher.settings.get().updates.automatic);
  if (process.env.SNOWBALLCLIENT_CAPTURE_DIR) await captureViews(win, process.env.SNOWBALLCLIENT_CAPTURE_DIR);
  // A development hook for checking the chat handshake end to end without clicking through the UI.
  if (process.env.SNOWBALLCLIENT_CHAT_CHECK) {
    const note = (line: string) => appendFileSync(process.env.SNOWBALLCLIENT_CHAT_CHECK!, `${new Date().toISOString()} ${line}
`);
    try {
      const accounts = launcher.auth.list();
      const id = launcher.settings.get().accounts.selectedAccountId ?? accounts[0]?.id;
      const session = id ? await launcher.auth.session(id) : null;
      note(`account ${session ? session.name + " " + session.uuid : "none"}`);
      if (session) {
        const who = { name: session.name, uuid: session.uuid, accessToken: session.accessToken, type: "msa" as const };
        let received = 0;
        chat.on("message", () => (received += 1));
        // Two connects at once, which is what a manual join racing the automatic reconnect used to
        // do. It opened a second socket and every message then arrived twice.
        const [a, b] = await Promise.all([chat.connect(who), chat.connect(who)]);
        note(`connect x2 returned ${a.status} / ${b.status}`);
        await new Promise((done) => setTimeout(done, 8000));
        note(`state now ${JSON.stringify(chat.current())}`);
        // The server counts sockets, so a second connection would show up here as an extra player.
        const stats = await chat.stats();
        note(`stats ${JSON.stringify(stats)}`);
        note(`messages received since connecting: ${received}`);
      }
    } catch (err) {
      note(`failed: ${err instanceof Error ? err.message : String(err)}`);
    }
    app.quit();
  }
}

/**
 * Development aid (env-gated): renders each launcher page to a PNG and exits, for UI review.
 * SNOWBALLCLIENT_CAPTURE_SIZE ("1280x720,1920x1080") sweeps window sizes so layout regressions
 * at the resolutions people actually use show up as pictures rather than as guesses.
 */
async function captureViews(win: BrowserWindow, dir: string): Promise<void> {
  const { mkdir, writeFile } = await import('node:fs/promises');
  await mkdir(dir, { recursive: true });
  win.show();
  const wait = (ms: number) => new Promise((resolve) => setTimeout(resolve, ms));
  const sizes = (process.env.SNOWBALLCLIENT_CAPTURE_SIZE ?? '')
    .split(',')
    .map((s) => s.trim().match(/^(\d+)x(\d+)$/))
    .filter((m): m is RegExpMatchArray => m !== null)
    .map((m) => ({ width: Number(m[1]), height: Number(m[2]) }));
  if (!sizes.length) sizes.push({ width: 1200, height: 760 });

  const only = process.env.SNOWBALLCLIENT_CAPTURE_PAGES?.split(',').map((p) => p.trim()).filter(Boolean);
  const pages = ['home', 'instances', 'mods', 'browse', 'chat', 'java', 'settings'];
  await wait(1500);
  for (const size of sizes) {
    win.setContentSize(size.width, size.height);
    const zoom = Number(process.env.SNOWBALLCLIENT_CAPTURE_ZOOM ?? '1');
    if (Number.isFinite(zoom) && zoom > 0 && zoom !== 1) win.webContents.setZoomFactor(zoom);
    await wait(500);
    const tag = sizes.length > 1 ? `${size.width}x${size.height}_` : '';
    for (let i = 0; i < pages.length; i++) {
      if (only && !only.includes(pages[i])) continue;
      await win.webContents.executeJavaScript(`document.querySelectorAll('.nav-item')[${i}].click()`);
      await wait(900);
      // SNOWBALLCLIENT_CAPTURE_CLICK is a selector clicked once the page is up, so dialogs and
      // other states can be reviewed as pictures too rather than only the bare pages.
      const click = process.env.SNOWBALLCLIENT_CAPTURE_CLICK;
      if (click) {
        await win.webContents.executeJavaScript(
          `(() => { const el = [...document.querySelectorAll('button')].find((b) => b.textContent.trim() === ${JSON.stringify(click)}); if (el) el.click(); return !!el; })()`,
        );
        await wait(700);
      }
      const image = await win.webContents.capturePage();
      await writeFile(join(dir, `${tag}${i}_${pages[i]}.png`), image.toPNG());
    }
  }
  app.quit();
}

/**
 * A fault in the main process used to end the launcher with no explanation. It is logged with a
 * stack for whoever reads the file, and the window stays up: most of these are recoverable, and
 * killing a running game because a background task threw would be worse than carrying on.
 */
function installCrashHandlers(): void {
  process.on('uncaughtException', (err) => {
    log.error('Uncaught exception in the main process', { error: err.message, stack: err.stack });
  });
  process.on('unhandledRejection', (reason) => {
    const err = reason instanceof Error ? reason : new Error(String(reason));
    log.error('Unhandled promise rejection in the main process', { error: err.message, stack: err.stack });
  });
}

if (!app.requestSingleInstanceLock()) {
  app.quit();
} else {
  installCrashHandlers();
  app.on('second-instance', () => {
    const [win] = BrowserWindow.getAllWindows();
    if (win) {
      if (win.isMinimized()) win.restore();
      win.focus();
    }
  });
  app.whenReady().then(createWindow).catch((err: Error) => {
    log.fatal('Launcher failed to start', { error: err.message, stack: err.stack });
    // The only failure with no UI to report it in, so the dialog has to carry the whole
    // explanation: what happened, the likely cause, and where the details are.
    const code = (err as NodeJS.ErrnoException).code;
    const cause =
      code === 'EACCES' || code === 'EPERM'
        ? 'Windows refused access to the Snowball folder. Antivirus software or folder permissions are the usual reason.'
        : code === 'ENOSPC'
          ? 'There is no free space left on the drive Snowball stores its files on.'
          : 'A file Snowball needs could not be read or created.';
    dialog.showErrorBox(
      'Snowball Client could not start',
      `${cause}\n\nWhat you can try:\n` +
        `  1. Restart Snowball.\n` +
        `  2. Restart your computer, in case a file is still in use.\n` +
        `  3. Reinstall Snowball from the website — your instances and worlds are kept.\n\n` +
        `The full details are in:\n${join(defaultDataRoot(), 'logs')}\n\n` +
        `Technical detail: ${err.message}`,
    );
    app.quit();
  });
  app.on('window-all-closed', () => app.quit());
}
