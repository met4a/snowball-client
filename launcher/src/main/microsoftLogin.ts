import { BrowserWindow } from 'electron';
import type { AuthorizationRequest } from '../core/auth/AuthManager.js';

/**
 * Opens Microsoft's sign-in page in its own window. The user types their email and password into
 * Microsoft's page; the launcher injects nothing, reads no form data and only watches for the final
 * redirect that carries the login code. Each attempt uses a fresh in-memory session.
 */
export function openMicrosoftLogin(parent: BrowserWindow, request: AuthorizationRequest): Promise<string> {
  return new Promise((resolve, reject) => {
    const win = new BrowserWindow({
      parent,
      modal: true,
      width: 1000,
      height: 720,
      title: 'Sign in to Snowball Client',
      autoHideMenuBar: true,
      backgroundColor: '#ffffff',
      webPreferences: {
        // Not "persist:" -> cookies live only for this window, nothing is shared with the launcher.
        partition: `microsoft-login-${Date.now()}`,
        sandbox: true,
        contextIsolation: true,
        nodeIntegration: false,
      },
    });

    let settled = false;
    const finish = (url: string | null) => {
      if (settled) return;
      settled = true;
      if (!win.isDestroyed()) win.close();
      if (url) resolve(url);
      else reject(new Error('Sign-in was cancelled.'));
    };
    const onNavigate = (event: { preventDefault(): void }, url: string) => {
      if (url.startsWith(request.redirectUri)) {
        event.preventDefault();
        finish(url);
      } else if (!url.startsWith('https://')) {
        event.preventDefault();
      }
    };

    win.webContents.on('will-redirect', onNavigate);
    win.webContents.on('will-navigate', onNavigate);
    win.webContents.setWindowOpenHandler(({ url }) => {
      if (url.startsWith('https://')) void win.loadURL(url);
      return { action: 'deny' };
    });
    win.on('closed', () => finish(null));
    win.loadURL(request.url).catch(() => {
      // Aborted loads (e.g. the redirect we intercept) are expected; real failures show in the window.
    });
  });
}
