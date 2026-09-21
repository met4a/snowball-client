import { describe, expect, it } from 'vitest';
import { loadAutoUpdater } from '../src/main/updates.js';

/**
 * electron-updater is CommonJS, and how a dynamic import() presents it depends on whether Node
 * could statically read its named exports. Packed into an asar it cannot, so everything arrives
 * under `default` and the named `autoUpdater` is undefined.
 *
 * That is not theoretical: it is what shipped. Every installed build threw
 * "Cannot set properties of undefined (setting 'autoDownload')" the moment it checked for an
 * update, while development worked, so auto-update had never once run for a real user.
 *
 * These cover both shapes, because only one of them appears in a packaged build and it is not the
 * one you see while developing.
 */
describe('loadAutoUpdater', () => {
  const updater = { autoDownload: false } as never;

  it('accepts named exports, which is what development gives', async () => {
    await expect(loadAutoUpdater(async () => ({ autoUpdater: updater }))).resolves.toBe(updater);
  });

  it('accepts the default wrapper, which is what a packaged build gives', async () => {
    await expect(loadAutoUpdater(async () => ({ default: { autoUpdater: updater } }))).resolves.toBe(updater);
  });

  it('prefers the named export when a module somehow has both', async () => {
    const other = { autoDownload: true } as never;
    await expect(loadAutoUpdater(async () => ({ autoUpdater: updater, default: { autoUpdater: other } }))).resolves.toBe(updater);
  });

  it('explains itself instead of throwing a TypeError further down', async () => {
    // The failure that shipped was a TypeError on the next line, which said nothing useful.
    await expect(loadAutoUpdater(async () => ({}))).rejects.toThrow(/update component could not be loaded/i);
    await expect(loadAutoUpdater(async () => ({ default: {} }))).rejects.toThrow(/update component could not be loaded/i);
    await expect(loadAutoUpdater(async () => undefined)).rejects.toThrow(/update component could not be loaded/i);
  });
});
