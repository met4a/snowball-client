import { randomBytes } from 'node:crypto';
import { existsSync } from 'node:fs';
import { copyFile, mkdir, readFile, readdir, rename, rm, stat, utimes } from 'node:fs/promises';
import { join } from 'node:path';
import { getLogger } from '../logging/Logger.js';
import { readJson, writeFileAtomic, writeJsonAtomic } from '../util/fsutil.js';

const log = getLogger('skins');

/** "classic" has Minecraft's 4-pixel ("wide") arms, "slim" 3-pixel ones. */
export type SkinVariant = 'classic' | 'slim';
/** Where a skin came from. Informational only. */
export type SkinSource = 'username' | 'url' | 'file' | 'current' | 'folder';

/** One skin in the library the launcher and the game share (design/skins.md). */
export interface SkinEntry {
  id: string;
  name: string;
  variant: SkinVariant;
  favourite: boolean;
  /** Milliseconds since 1970. */
  added: number;
  source: string;
  /** The hash Mojang gave this skin, when known. Only used to show which skin is equipped. */
  texture: string | null;
}

/** Pixels with four bytes each, alpha last. Electron gives BGRA; only the alpha is read here. */
export interface DecodedImage {
  width: number;
  height: number;
  pixels: Uint8Array;
}

/** Turns PNG bytes into pixels, or null when it cannot. The launcher uses Electron's nativeImage. */
export type SkinDecoder = (png: Buffer) => DecodedImage | null;

/** Skins are tiny; anything larger is refused before it is decoded. */
export const MAX_SKIN_BYTES = 64 * 1024;
export const SKIN_ID = /^[0-9a-f]{12}$/;
export const SKIN_NAME_MAX = 32;
const TEXTURE = /^[0-9a-f]{1,128}$/i;
const CONTROL = /[\u0000-\u001f\u007f-\u009f]/;
const PNG_SIGNATURE = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]);
/**
 * A picture named like a skin id this recent may be the game adding a skin: it writes the PNG
 * just before listing it. Adopting it in between would list the same skin twice.
 */
const SETTLING_MS = 10_000;

export const NOT_A_SKIN = 'That is not a Minecraft skin. Skins are PNG pictures 64x64 or 64x32 pixels in size.';
export const TOO_BIG = 'That file is too big to be a skin. Skins are at most 64 KB.';
export const SKIN_GONE = 'That skin is no longer in your library.';

/** A problem with a skin, in words a player understands. */
export class SkinError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'SkinError';
  }
}

/**
 * The size from a skin's PNG header, without decoding it: the signature, then an IHDR chunk
 * saying 64 by 64 or 64 by 32. Null for anything else, and for anything over 64 KiB.
 */
export function skinPngSize(bytes: Uint8Array): { width: number; height: number } | null {
  if (bytes.length < 24 || bytes.length > MAX_SKIN_BYTES) return null;
  const b = Buffer.from(bytes.buffer, bytes.byteOffset, bytes.byteLength);
  if (!b.subarray(0, 8).equals(PNG_SIGNATURE) || b.toString('latin1', 12, 16) !== 'IHDR') return null;
  const width = b.readUInt32BE(16);
  const height = b.readUInt32BE(20);
  return width === 64 && (height === 64 || height === 32) ? { width, height } : null;
}

/**
 * Slim or classic from the pixels: a slim skin leaves the columns a fourth arm pixel would use
 * fully transparent. 64x32 skins, and pictures that cannot be decoded, are classic.
 */
export function detectVariant(png: Buffer, decode?: SkinDecoder): SkinVariant {
  if (!decode || skinPngSize(png)?.height !== 64) return 'classic';
  let image: DecodedImage | null = null;
  try {
    image = decode(png);
  } catch {
    image = null;
  }
  if (!image || image.width !== 64 || image.height !== 64 || image.pixels.length < 64 * 64 * 4) return 'classic';
  const pixels = image.pixels;
  const clear = (x0: number, x1: number, y0: number, y1: number) => {
    for (let y = y0; y <= y1; y++) {
      for (let x = x0; x <= x1; x++) if (pixels[(y * 64 + x) * 4 + 3] !== 0) return false;
    }
    return true;
  };
  return clear(50, 51, 16, 19) && clear(54, 55, 20, 31) ? 'slim' : 'classic';
}

/** A name as the library keeps it: no control characters, 1 to 32 characters. */
export function skinName(raw: string, fallback = 'Skin'): string {
  let name = raw.replace(new RegExp(CONTROL.source, 'g'), '').trim();
  // Cut by UTF-16 units, as the game counts them, without splitting a surrogate pair.
  if (name.length > SKIN_NAME_MAX) name = name.slice(0, SKIN_NAME_MAX).replace(/[\ud800-\udbff]$/, '').trim();
  return name || fallback;
}

/** Favourites first, then newest first. */
export function sortSkins(skins: SkinEntry[]): SkinEntry[] {
  return [...skins].sort((a, b) => Number(b.favourite) - Number(a.favourite) || b.added - a.added || a.id.localeCompare(b.id));
}

/** An entry as read from skins.json, or null when it breaks the rules. Unknown fields are ignored. */
function parseEntry(raw: unknown): SkinEntry | null {
  if (!raw || typeof raw !== 'object') return null;
  const e = raw as Record<string, unknown>;
  if (typeof e.id !== 'string' || !SKIN_ID.test(e.id)) return null;
  if (typeof e.name !== 'string' || !e.name.length || e.name.length > SKIN_NAME_MAX || CONTROL.test(e.name)) return null;
  if (e.variant !== 'classic' && e.variant !== 'slim') return null;
  return {
    id: e.id,
    name: e.name,
    variant: e.variant,
    favourite: e.favourite === true,
    added: typeof e.added === 'number' && Number.isFinite(e.added) ? e.added : 0,
    source: typeof e.source === 'string' ? e.source : 'file',
    texture: typeof e.texture === 'string' && TEXTURE.test(e.texture) ? e.texture : null,
  };
}

/** An entry as written to skins.json, in the field order of design/skins.md. */
function toRaw(entry: SkinEntry): Record<string, unknown> {
  const { texture, ...rest } = entry;
  return texture ? { ...rest, texture } : rest;
}

type Document = Record<string, unknown> & { skins?: unknown };

/**
 * The skin library on disk: skins.json and one <id>.png per skin, shared with the game. Every
 * change re-reads the file first and touches only what it meant to, then writes it atomically,
 * so the launcher and the game never lose each other's changes.
 */
export class SkinLibrary {
  readonly file: string;
  /** Changes from this process run one at a time, so two clicks never undo each other. */
  private queue: Promise<unknown> = Promise.resolve();

  constructor(
    readonly dir: string,
    private readonly decode?: SkinDecoder,
    private readonly now: () => number = () => Date.now(),
  ) {
    this.file = join(dir, 'skins.json');
  }

  private exclusive<T>(fn: () => Promise<T>): Promise<T> {
    const run = this.queue.then(fn, fn);
    this.queue = run.catch(() => undefined);
    return run;
  }

  /** skins.json as it is now; a missing or damaged file reads as an empty library. */
  private async read(): Promise<{ doc: Document; damaged: boolean }> {
    const result = await readJson<unknown>(this.file);
    if (result.ok && result.value && typeof result.value === 'object' && !Array.isArray(result.value) && Array.isArray((result.value as Document).skins)) {
      return { doc: result.value as Document, damaged: false };
    }
    return { doc: { schemaVersion: 1, skins: [] }, damaged: result.ok || result.reason === 'malformed' };
  }

  private async entries(): Promise<SkinEntry[]> {
    const { doc } = await this.read();
    return (doc.skins as unknown[]).map(parseEntry).filter((e): e is SkinEntry => e !== null);
  }

  /** Re-reads skins.json, lets `change` edit its list in place, then writes it back. */
  private async change<T>(change: (skins: unknown[]) => T): Promise<T> {
    const { doc, damaged } = await this.read();
    const skins = doc.skins as unknown[];
    const result = change(skins);
    // A damaged file is kept beside the new one rather than silently replaced.
    if (damaged) {
      log.warn('skins.json was damaged; it is kept as skins.json.damaged and a new list started');
      await copyFile(this.file, join(this.dir, 'skins.json.damaged')).catch(() => undefined);
    }
    await writeJsonAtomic(this.file, { ...doc, schemaVersion: typeof doc.schemaVersion === 'number' ? doc.schemaVersion : 1, skins });
    return result;
  }

  private async newId(taken: Set<string>): Promise<string> {
    for (;;) {
      const id = randomBytes(6).toString('hex');
      if (!taken.has(id) && !existsSync(join(this.dir, `${id}.png`))) return id;
    }
  }

  /** Every skin with its picture, favourites first, then newest. Pictures dropped into the folder are listed first. */
  list(): Promise<SkinEntry[]> {
    return this.exclusive(async () => {
      await mkdir(this.dir, { recursive: true });
      const adopted = await this.adopt(new Set((await this.entries()).map((e) => e.id)));
      if (adopted.length) {
        await this.change((skins) => skins.push(...adopted.map(toRaw)));
        log.info(`Added ${adopted.length} skin${adopted.length === 1 ? '' : 's'} dropped into the skins folder`);
      }
      const files = new Set(await readdir(this.dir));
      return sortSkins((await this.entries()).filter((e) => files.has(`${e.id}.png`)));
    });
  }

  /**
   * Gives a PNG dropped into the folder an id and a place in the list, named after the file.
   * Files that are not 64x64 or 64x32 PNGs are left alone.
   */
  private async adopt(listed: Set<string>): Promise<SkinEntry[]> {
    const adopted: SkinEntry[] = [];
    for (const file of await readdir(this.dir)) {
      if (!/\.png$/i.test(file)) continue;
      const base = file.slice(0, -4);
      if (file === `${base}.png` && listed.has(base)) continue;
      const path = join(this.dir, file);
      try {
        const info = await stat(path);
        if (!info.isFile() || info.size > MAX_SKIN_BYTES) continue;
        if (SKIN_ID.test(base) && this.now() - info.mtimeMs < SETTLING_MS) continue;
        const png = await readFile(path);
        if (!skinPngSize(png)) continue;
        const id = await this.newId(listed);
        const target = join(this.dir, `${id}.png`);
        await rename(path, target);
        // Fresh, as the game does: until it is listed the game sees it as just written and leaves it be.
        const now = new Date(this.now());
        await utimes(target, now, now).catch(() => undefined);
        listed.add(id);
        adopted.push({ id, name: skinName(base), variant: detectVariant(png, this.decode), favourite: false, added: this.now(), source: 'folder', texture: null });
      } catch (err) {
        // The game may have adopted it a moment earlier; it is listed there and read next time.
        log.warn('Could not add a skin dropped into the folder', { file, error: (err as Error).message });
      }
    }
    return adopted;
  }

  /**
   * Adds a skin from its PNG. The picture is written before the list, so neither side ever lists
   * a skin without one. The variant is detected from the pixels when not given.
   */
  async add(png: Buffer, info: { name: string; source: SkinSource; variant?: SkinVariant; texture?: string | null }): Promise<SkinEntry> {
    if (png.length > MAX_SKIN_BYTES) throw new SkinError(TOO_BIG);
    if (!skinPngSize(png)) throw new SkinError(NOT_A_SKIN);
    return this.exclusive(async () => {
      await mkdir(this.dir, { recursive: true });
      const entry: SkinEntry = {
        id: await this.newId(new Set((await this.entries()).map((e) => e.id))),
        name: skinName(info.name),
        variant: info.variant ?? detectVariant(png, this.decode),
        favourite: false,
        added: this.now(),
        source: info.source,
        texture: info.texture && TEXTURE.test(info.texture) ? info.texture : null,
      };
      await writeFileAtomic(join(this.dir, `${entry.id}.png`), png);
      await this.change((skins) => skins.push(toRaw(entry)));
      return entry;
    });
  }

  /** Changes a skin's name, arms, star or Mojang hash, and nothing else. */
  update(id: string, patch: Partial<Pick<SkinEntry, 'name' | 'variant' | 'favourite' | 'texture'>>): Promise<SkinEntry> {
    return this.exclusive(() => this.change((skins) => {
      const raw = skins.find((s) => parseEntry(s)?.id === id) as Record<string, unknown> | undefined;
      if (!raw || !existsSync(join(this.dir, `${id}.png`))) throw new SkinError(SKIN_GONE);
      if (patch.name !== undefined) raw.name = skinName(patch.name);
      if (patch.variant === 'classic' || patch.variant === 'slim') raw.variant = patch.variant;
      if (patch.favourite !== undefined) raw.favourite = patch.favourite === true;
      if (patch.texture !== undefined) {
        if (patch.texture && TEXTURE.test(patch.texture)) raw.texture = patch.texture;
        else delete raw.texture;
      }
      return parseEntry(raw)!;
    }));
  }

  /**
   * Deletes a skin. The picture goes first: a listed skin without a picture is skipped, whereas
   * a picture without a listing would be adopted again as a dropped file.
   */
  remove(id: string): Promise<void> {
    if (!SKIN_ID.test(id)) return Promise.reject(new SkinError(SKIN_GONE));
    return this.exclusive(async () => {
      await rm(join(this.dir, `${id}.png`), { force: true });
      await this.change((skins) => {
        for (let i = skins.length - 1; i >= 0; i--) {
          if ((skins[i] as { id?: unknown } | null)?.id === id) skins.splice(i, 1);
        }
      });
    });
  }

  /** A skin's PNG, exactly as Mojang takes it. */
  async png(id: string): Promise<Buffer> {
    if (!SKIN_ID.test(id)) throw new SkinError(SKIN_GONE);
    const bytes = await readFile(join(this.dir, `${id}.png`)).catch(() => null);
    if (!bytes) throw new SkinError(SKIN_GONE);
    return bytes;
  }
}
