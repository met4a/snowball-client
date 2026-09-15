import { existsSync } from 'node:fs';
import { readdir } from 'node:fs/promises';
import { join } from 'node:path';
import { ZipReader } from '../util/zip.js';

/** Mod id of Snowball Client and the file name it always has inside an instance's mods folder. */
export const SNOWBALL_MOD_ID = 'snowballclient';
export const SNOWBALL_MOD_FILE = 'snowball-client.jar';
export const FABRIC_API_ID = 'fabric-api';

/** "core": Snowball Client itself. "required": a mod Snowball Client cannot run without (Fabric API). */
export type Protection = 'core' | 'required';

export class ProtectedModError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'ProtectedModError';
  }
}

const withoutDisabled = (fileName: string) => fileName.replace(/\.disabled$/i, '');

/**
 * The single rule set for protected mods, applied from mod ids and file names so every caller agrees:
 * Snowball Client is core wherever it is present, and Fabric API is required whenever Snowball Client is.
 */
export function protectionFor(files: Array<{ fileName: string; id: string | null }>): Map<string, Protection> {
  const isCore = (f: { fileName: string; id: string | null }) => f.id === SNOWBALL_MOD_ID || withoutDisabled(f.fileName).toLowerCase() === SNOWBALL_MOD_FILE;
  const hasCore = files.some(isCore);
  const result = new Map<string, Protection>();
  for (const f of files) {
    if (isCore(f)) result.set(f.fileName, 'core');
    else if (hasCore && f.id === FABRIC_API_ID) result.set(f.fileName, 'required');
  }
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
    // A corrupt or non-jar file has no readable id; the file-name rule still protects snowball-client.jar.
    return null;
  }
}

/** Protection of every mod file in a mods folder. */
export async function folderProtection(modsDir: string): Promise<Map<string, Protection>> {
  if (!existsSync(modsDir)) return new Map();
  const files: Array<{ fileName: string; id: string | null }> = [];
  for (const entry of await readdir(modsDir, { withFileTypes: true })) {
    if (!entry.isFile() || !/\.jar(\.disabled)?$/i.test(entry.name)) continue;
    files.push({ fileName: entry.name, id: await readModId(join(modsDir, entry.name)) });
  }
  return protectionFor(files);
}

/**
 * Guards every normal mod-management change. Snowball Client can't be removed, turned off or replaced;
 * Fabric API can be updated (replaced) but not removed or turned off while Snowball Client needs it.
 */
export async function assertModChangeAllowed(modsDir: string, fileName: string, action: 'remove' | 'disable' | 'replace'): Promise<void> {
  const protection = (await folderProtection(modsDir)).get(fileName);
  const verb = { remove: 'removed', disable: 'turned off', replace: 'replaced' }[action];
  if (protection === 'core') {
    throw new ProtectedModError(`Snowball Client is built into this instance and can't be ${verb}. If it is broken, use Repair on the MODS page.`);
  }
  if (protection === 'required' && action !== 'replace') {
    throw new ProtectedModError(`Fabric API is required by Snowball Client, so it can't be ${verb} in this instance.`);
  }
}
