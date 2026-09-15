import { existsSync } from 'node:fs';
import { readdir } from 'node:fs/promises';
import { dirname, join } from 'node:path';
import { ZipReader } from '../util/zip.js';

/** Mod id of Snowball Client. The launcher loads it from its own folder; it never belongs in a mods folder. */
export const SNOWBALL_MOD_ID = 'snowballclient';
export const SNOWBALL_MOD_FILE = 'snowball-client.jar';
export const FABRIC_API_ID = 'fabric-api';
/** Written into an instance's game folder when Snowball Client is set up for it. */
export const SNOWBALL_MARKER = join('.snowball', 'core.json');

/** "required": a mod Snowball Client cannot run without (Fabric API). */
export type Protection = 'required';

export class ProtectedModError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'ProtectedModError';
  }
}

export function isSnowballInstance(gameDir: string): boolean {
  return existsSync(join(gameDir, SNOWBALL_MARKER));
}

/** The single rule set for protected mods: in a Snowball instance, Fabric API is required. */
export function protectionFor(files: Array<{ fileName: string; id: string | null }>, snowballInstance: boolean): Map<string, Protection> {
  const result = new Map<string, Protection>();
  if (!snowballInstance) return result;
  for (const f of files) if (f.id === FABRIC_API_ID) result.set(f.fileName, 'required');
  return result;
}

/** Reads only the mod id of a jar. */
export async function readModId(path: string): Promise<string | null> {
  try {
    const zip = await ZipReader.open(path);
    const fabric = zip.readText('fabric.mod.json');
    if (fabric) {
      const id = (JSON.parse(fabric.replace(/^﻿/, '')) as { id?: unknown }).id;
      return typeof id === 'string' ? id : null;
    }
    const quilt = zip.readText('quilt.mod.json');
    if (quilt) {
      const id = (JSON.parse(quilt) as { quilt_loader?: { id?: unknown } }).quilt_loader?.id;
      return typeof id === 'string' ? id : null;
    }
    return null;
  } catch {
    // A corrupt or non-jar file has no readable id, so it can't be a protected mod.
    return null;
  }
}

/** Protection of every mod file in a mods folder (the folder's parent is the instance game folder). */
export async function folderProtection(modsDir: string): Promise<Map<string, Protection>> {
  if (!existsSync(modsDir)) return new Map();
  const files: Array<{ fileName: string; id: string | null }> = [];
  for (const entry of await readdir(modsDir, { withFileTypes: true })) {
    if (!entry.isFile() || !/\.jar(\.disabled)?$/i.test(entry.name)) continue;
    files.push({ fileName: entry.name, id: await readModId(join(modsDir, entry.name)) });
  }
  return protectionFor(files, isSnowballInstance(dirname(modsDir)));
}

/**
 * Guards every normal mod-management change. In a Snowball instance Fabric API can be updated
 * (replaced) but not removed or turned off, because Snowball Client needs it.
 */
export async function assertModChangeAllowed(modsDir: string, fileName: string, action: 'remove' | 'disable' | 'replace'): Promise<void> {
  if (action === 'replace') return;
  if ((await folderProtection(modsDir)).get(fileName) === 'required') {
    throw new ProtectedModError(`Fabric API is required by Snowball Client, so it can't be ${action === 'remove' ? 'removed' : 'turned off'} in this instance.`);
  }
}
