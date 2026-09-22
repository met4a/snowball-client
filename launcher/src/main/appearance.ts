import { dialog, type BrowserWindow } from 'electron';
import { copyFile, readFile, readdir, rm } from 'node:fs/promises';
import { extname, join } from 'node:path';
import { getLogger } from '../core/logging/Logger.js';

const log = getLogger('appearance');

/** Kept generous: a 4K wallpaper is a few megabytes, and this is read once per launch. */
const MAX_BYTES = 24 * 1024 * 1024;

/**
 * What each format starts with. The extension is whatever the file happens to be called, so the
 * bytes are what decides: a .png that is actually something else never reaches the page.
 */
const SIGNATURES: Array<{ type: string; test: (b: Buffer) => boolean }> = [
  { type: 'image/png', test: (b) => b.subarray(0, 8).equals(Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a])) },
  { type: 'image/jpeg', test: (b) => b[0] === 0xff && b[1] === 0xd8 && b[2] === 0xff },
  { type: 'image/gif', test: (b) => b.subarray(0, 6).toString('ascii').startsWith('GIF8') },
  { type: 'image/webp', test: (b) => b.subarray(0, 4).toString('ascii') === 'RIFF' && b.subarray(8, 12).toString('ascii') === 'WEBP' },
];

function sniff(bytes: Buffer): string | null {
  return SIGNATURES.find((s) => s.test(bytes))?.type ?? null;
}

/** Where the chosen picture lives, so it survives a restart and an update. */
const STORED = 'background';

async function storedFile(root: string): Promise<string | null> {
  try {
    const found = (await readdir(root)).find((f) => f.startsWith(`${STORED}.`));
    return found ? join(root, found) : null;
  } catch {
    return null;
  }
}

/**
 * Reads the saved background as a data URL.
 *
 * A data URL rather than a file:// path because the renderer runs under a Content-Security-Policy
 * that allows images from this app and from Modrinth only. Widening that to the whole filesystem
 * to show one wallpaper is a poor trade; handing over the bytes we already validated is not.
 */
export async function backgroundDataUrl(root: string): Promise<string | null> {
  const file = await storedFile(root);
  if (!file) return null;
  try {
    const bytes = await readFile(file);
    const type = sniff(bytes);
    if (!type) {
      log.warn('Stored background is not an image any more; ignoring it', { file });
      return null;
    }
    return `data:${type};base64,${bytes.toString('base64')}`;
  } catch (err) {
    log.warn('Could not read the stored background', { error: String(err) });
    return null;
  }
}

export async function clearBackground(root: string): Promise<void> {
  const file = await storedFile(root);
  if (file) await rm(file, { force: true });
}

/**
 * Asks for a picture and keeps a copy. The copy matters: people move and delete files, and a
 * launcher whose background vanishes because a folder was tidied is a launcher that looks broken.
 */
export async function pickBackground(win: BrowserWindow, root: string): Promise<{ ok: boolean; dataUrl?: string; reason?: string }> {
  const picked = await dialog.showOpenDialog(win, {
    title: 'Choose a background',
    buttonLabel: 'Use this picture',
    properties: ['openFile'],
    filters: [{ name: 'Pictures', extensions: ['png', 'jpg', 'jpeg', 'webp', 'gif'] }],
  });
  if (picked.canceled || !picked.filePaths[0]) return { ok: false };

  const source = picked.filePaths[0];
  let bytes: Buffer;
  try {
    bytes = await readFile(source);
  } catch (err) {
    return { ok: false, reason: `That file could not be read: ${err instanceof Error ? err.message : String(err)}` };
  }
  if (bytes.byteLength > MAX_BYTES) {
    return { ok: false, reason: `That picture is ${(bytes.byteLength / 1024 / 1024).toFixed(0)} MB. Please choose one under 24 MB.` };
  }
  const type = sniff(bytes);
  if (!type) return { ok: false, reason: 'That file is not a picture Snowball can read. Try a PNG, JPG or WEBP.' };

  await clearBackground(root);
  const ext = type === 'image/jpeg' ? '.jpg' : type === 'image/png' ? '.png' : type === 'image/webp' ? '.webp' : '.gif';
  await copyFile(source, join(root, `${STORED}${ext}`));
  log.info('Background set', { from: extname(source), type, bytes: bytes.byteLength });
  return { ok: true, dataUrl: `data:${type};base64,${bytes.toString('base64')}` };
}
