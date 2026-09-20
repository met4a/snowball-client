import { stat } from 'node:fs/promises';
import { resolve } from 'node:path';
import { readJson, writeJsonAtomic } from '../util/fsutil.js';

interface Entry {
  size: number;
  mtimeMs: number;
  sha1: string;
}

/**
 * Remembers which files were checksum-verified. Minecraft's libraries and assets never change once
 * downloaded, so hashing thousands of them before every launch only costs time: a file whose size and
 * modification time still match the ones recorded when it was hashed counts as verified, and anything
 * else is hashed again. Deleting this file makes the launcher re-check everything.
 */
export class VerifiedFiles {
  private readonly entries = new Map<string, Entry>();
  private dirty = false;

  constructor(private readonly path: string, private readonly limit = 20_000) {}

  async load(): Promise<void> {
    const result = await readJson<{ files?: Record<string, Entry> }>(this.path);
    if (!result.ok || !result.value?.files || typeof result.value.files !== 'object') return;
    for (const [file, entry] of Object.entries(result.value.files)) {
      if (entry && typeof entry.size === 'number' && typeof entry.mtimeMs === 'number' && typeof entry.sha1 === 'string') {
        this.entries.set(file, entry);
      }
    }
  }

  /** True when this file was hashed before and has not been written to since. */
  async isVerified(path: string, sha1: string): Promise<boolean> {
    const key = resolve(path);
    const entry = this.entries.get(key);
    if (!entry || entry.sha1 !== sha1.toLowerCase()) return false;
    try {
      const info = await stat(path);
      // Rounded to whole milliseconds: file systems store timestamps at different resolutions.
      if (info.size === entry.size && Math.round(info.mtimeMs) === entry.mtimeMs) return true;
    } catch {
      // Gone or unreadable: it has to be downloaded again anyway.
    }
    this.entries.delete(key);
    this.dirty = true;
    return false;
  }

  /** Records a file whose checksum was just confirmed. */
  async record(path: string, sha1: string): Promise<void> {
    try {
      const info = await stat(path);
      const key = resolve(path);
      // Oldest entries go first when the list grows past the limit (Map keeps insertion order).
      for (const oldest of this.entries.keys()) {
        if (this.entries.size < this.limit) break;
        this.entries.delete(oldest);
      }
      this.entries.delete(key);
      this.entries.set(key, { size: info.size, mtimeMs: Math.round(info.mtimeMs), sha1: sha1.toLowerCase() });
      this.dirty = true;
    } catch {
      // A file that cannot be read cannot be remembered as verified.
    }
  }

  /** Forgets every record, so the next check hashes the files again. */
  clear(): void {
    this.entries.clear();
    this.dirty = true;
  }

  async save(): Promise<void> {
    if (!this.dirty) return;
    this.dirty = false;
    await writeJsonAtomic(this.path, { schemaVersion: 1, files: Object.fromEntries(this.entries) });
  }
}
