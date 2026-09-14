import { createHash, randomBytes } from 'node:crypto';
import { createReadStream, existsSync } from 'node:fs';
import { mkdir, readFile, rename, rm, stat, writeFile } from 'node:fs/promises';
import { dirname } from 'node:path';

/** Writes via a temp file + rename so a crash never leaves a half-written JSON file. */
export async function writeFileAtomic(path: string, data: string | Uint8Array): Promise<void> {
  await mkdir(dirname(path), { recursive: true });
  const tmp = `${path}.${randomBytes(4).toString('hex')}.tmp`;
  await writeFile(tmp, data);
  try {
    await rename(tmp, path);
  } catch (err) {
    await rm(tmp, { force: true });
    throw err;
  }
}

export async function writeJsonAtomic(path: string, value: unknown): Promise<void> {
  await writeFileAtomic(path, JSON.stringify(value, null, 2) + '\n');
}

export type JsonReadResult<T> =
  | { ok: true; value: T }
  | { ok: false; reason: 'missing' | 'malformed'; error?: string };

export async function readJson<T = unknown>(path: string): Promise<JsonReadResult<T>> {
  let text: string;
  try {
    text = await readFile(path, 'utf8');
  } catch (err) {
    if ((err as NodeJS.ErrnoException).code === 'ENOENT') return { ok: false, reason: 'missing' };
    throw err;
  }
  try {
    return { ok: true, value: JSON.parse(text.replace(/^﻿/, '')) as T };
  } catch (err) {
    return { ok: false, reason: 'malformed', error: (err as Error).message };
  }
}

export function sha1File(path: string): Promise<string> {
  return hashFile(path, 'sha1');
}

export function hashFile(path: string, algorithm: string): Promise<string> {
  return new Promise((resolve, reject) => {
    const hash = createHash(algorithm);
    createReadStream(path)
      .on('data', (chunk) => hash.update(chunk))
      .on('error', reject)
      .on('end', () => resolve(hash.digest('hex')));
  });
}

export async function fileSize(path: string): Promise<number | null> {
  try {
    return (await stat(path)).size;
  } catch {
    return null;
  }
}

/** True when the file exists and matches the optional size/sha1 expectations. */
export async function isFileValid(path: string, expected: { sha1?: string; size?: number }): Promise<boolean> {
  if (!existsSync(path)) return false;
  if (expected.size !== undefined && (await fileSize(path)) !== expected.size) return false;
  if (expected.sha1 && (await sha1File(path)).toLowerCase() !== expected.sha1.toLowerCase()) return false;
  return true;
}
