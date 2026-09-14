import { app, BrowserWindow, dialog, Menu, safeStorage } from 'electron';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import type { SecretCipher } from '../core/auth/AuthManager.js';
import { Launcher } from '../core/Launcher.js';
import { getLogger } from '../core/logging/Logger.js';
import { defaultDataRoot } from '../core/util/paths.js';
import { registerIpc } from './ipc.js';

const log = getLogger('main');

/** Refresh tokens are encrypted with the operating system keychain (DPAPI / Keychain / libsecret). */
const cipher: SecretCipher = {
  isAvailable: () => safeStorage.isEncryptionAvailable(),
  encrypt: (plain) => safeStorage.encryptString(plain).toString('base64'),
  decrypt: (encoded) => safeStorage.decryptString(Buffer.from(encoded, 'base64')),
};

function clientJarPath(): string {
  // Packaged builds ship the mod as an extra resource; development uses the Gradle output.
  return app.isPackaged
    ? join(process.resourcesPath, 'client', 'snowball-client.jar')
    : join(app.getAppPath(), '..', 'client', 'build', 'libs', 'snowball-client-1.1.0.jar');
}

/** Build-time configuration shipped inside the app (e.g. the Azure client ID for Microsoft sign-in). */
function microsoftClientIdFromConfig(): string {
  try {
    const config = JSON.parse(readFileSync(join(app.getAppPath(), 'app-config.json'), 'utf8')) as { microsoftClientId?: unknown };
    return typeof config.microsoftClientId === 'string' ? config.microsoftClientId.trim() : '';
  } catch {
    return '';
  }
}

async function createWindow(): Promise<void> {
  const launcher = await Launcher.create({
    root: defaultDataRoot(),
    cipher,
    clientJarPath: clientJarPath(),
    launcherVersion: app.getVersion(),
    defaultMicrosoftClientId: microsoftClientIdFromConfig(),
  });
  const win = new BrowserWindow({
    width: 1200,
    height: 760,
    minWidth: 980,
    minHeight: 620,
    title: 'Snowball Client',
    backgroundColor: '#05070a',
    icon: join(__dirname, '..', 'renderer', 'assets', 'logo.png'),
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
  Menu.setApplicationMenu(null);
  win.webContents.setWindowOpenHandler(() => ({ action: 'deny' }));
  win.webContents.on('will-navigate', (event) => event.preventDefault());
  win.once('ready-to-show', () => win.show());
  registerIpc(launcher, win);
  await win.loadFile(join(__dirname, '..', 'renderer', 'index.html'));
  if (process.env.SNOWBALLCLIENT_CAPTURE_DIR) await captureViews(win, process.env.SNOWBALLCLIENT_CAPTURE_DIR);
}

/** Development aid (env-gated): renders each launcher page to a PNG and exits, for UI review. */
async function captureViews(win: BrowserWindow, dir: string): Promise<void> {
  const { mkdir, writeFile } = await import('node:fs/promises');
  await mkdir(dir, { recursive: true });
  win.show();
  const wait = (ms: number) => new Promise((resolve) => setTimeout(resolve, ms));
  await wait(1500);
  const pages = ['home', 'instances', 'mods', 'java', 'settings'];
  for (let i = 0; i < pages.length; i++) {
    await win.webContents.executeJavaScript(`document.querySelectorAll('.nav-item')[${i}].click()`);
    await wait(1500);
    const image = await win.webContents.capturePage();
    await writeFile(join(dir, `${i}_${pages[i]}.png`), image.toPNG());
  }
  app.quit();
}

if (!app.requestSingleInstanceLock()) {
  app.quit();
} else {
  app.on('second-instance', () => {
    const [win] = BrowserWindow.getAllWindows();
    if (win) {
      if (win.isMinimized()) win.restore();
      win.focus();
    }
  });
  app.whenReady().then(createWindow).catch((err: Error) => {
    log.fatal('Launcher failed to start', { error: err.message, stack: err.stack });
    dialog.showErrorBox('Snowball Client failed to start', err.message);
    app.quit();
  });
  app.on('window-all-closed', () => app.quit());
}
