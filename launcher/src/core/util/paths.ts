import { homedir, platform } from 'node:os';
import { isAbsolute, join, normalize, relative, resolve, sep } from 'node:path';

export class PathSecurityError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'PathSecurityError';
  }
}

const WINDOWS_RESERVED = /^(con|prn|aux|nul|com[0-9]|lpt[0-9])(\..*)?$/i;

/**
 * Joins untrusted relative segments onto a trusted base and guarantees the result
 * stays inside the base. Rejects absolute segments, drive letters and `..` escapes.
 */
export function safeJoin(base: string, ...segments: string[]): string {
  const root = resolve(base);
  for (const segment of segments) {
    if (typeof segment !== 'string' || segment.includes('\0')) {
      throw new PathSecurityError('Invalid path segment');
    }
    if (isAbsolute(segment) || /^[a-zA-Z]:/.test(segment) || segment.startsWith('\\\\')) {
      throw new PathSecurityError(`Absolute path not allowed: ${segment}`);
    }
  }
  const target = resolve(root, ...segments);
  const rel = relative(root, target);
  if (rel === '' ) return target;
  if (rel.startsWith('..') || isAbsolute(rel) || rel.split(sep).includes('..')) {
    throw new PathSecurityError(`Path escapes base directory: ${segments.join('/')}`);
  }
  return target;
}

export function isInside(base: string, candidate: string): boolean {
  const rel = relative(resolve(base), resolve(candidate));
  return rel === '' || (!rel.startsWith('..') && !isAbsolute(rel));
}

/** Produces a portable, filesystem-safe single path component from a user-facing name. */
export function sanitizeFileName(name: string, fallback = 'unnamed'): string {
  let cleaned = name
    .normalize('NFKC')
    .replace(/[<>:"/\\|?*]/g, '_')
    .split('').map((ch) => (ch.charCodeAt(0) < 32 ? '_' : ch)).join('')
    .replace(/\s+/g, ' ')
    .trim()
    .replace(/^\.+/, '')
    .replace(/[. ]+$/, '');
  if (cleaned.length > 64) cleaned = cleaned.slice(0, 64).trim();
  if (!cleaned || WINDOWS_RESERVED.test(cleaned)) cleaned = cleaned ? `_${cleaned}` : fallback;
  return cleaned;
}

/** Validates a relative path from downloaded metadata (e.g. library paths). */
export function assertSafeRelativePath(p: string): string {
  const norm = normalize(p).replace(/\\/g, '/');
  if (!p || p.includes('\0') || isAbsolute(p) || /^[a-zA-Z]:/.test(p) || norm.split('/').includes('..')) {
    throw new PathSecurityError(`Unsafe relative path in metadata: ${p}`);
  }
  return norm;
}

/** Default per-user data directory for the launcher, following each platform's conventions. */
export function defaultDataRoot(): string {
  const env = process.env.SNOWBALLCLIENT_LAUNCHER_HOME;
  if (env) return resolve(env);
  switch (platform()) {
    case 'win32':
      return join(process.env.APPDATA ?? join(homedir(), 'AppData', 'Roaming'), 'SnowballClientLauncher');
    case 'darwin':
      return join(homedir(), 'Library', 'Application Support', 'SnowballClientLauncher');
    default:
      return join(process.env.XDG_DATA_HOME ?? join(homedir(), '.local', 'share'), 'snowball-client-launcher');
  }
}

export interface LauncherPaths {
  root: string;
  instances: string;
  versions: string;
  libraries: string;
  assets: string;
  runtimes: string;
  cache: string;
  logs: string;
  /** Verified Snowball Client jars the launcher loads into instances (never copied into mods folders). */
  client: string;
  settingsFile: string;
  accountsFile: string;
}

export function createLauncherPaths(root: string): LauncherPaths {
  return {
    root,
    instances: join(root, 'instances'),
    versions: join(root, 'versions'),
    libraries: join(root, 'libraries'),
    assets: join(root, 'assets'),
    runtimes: join(root, 'runtimes'),
    cache: join(root, 'cache'),
    logs: join(root, 'logs'),
    client: join(root, 'client'),
    settingsFile: join(root, 'settings.json'),
    accountsFile: join(root, 'accounts.json'),
  };
}
