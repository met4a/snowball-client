import type { DownloadManager } from '../download/DownloadManager.js';
import type { LoaderId } from '../instance/InstanceManager.js';
import type { VersionManager } from '../minecraft/VersionManager.js';
import type { LauncherPaths } from '../util/paths.js';

export interface LoaderVersion {
  version: string;
  stable: boolean;
}

export interface LoaderInstallContext {
  versions: VersionManager;
  downloads: DownloadManager;
  paths: LauncherPaths;
  /** Required by installer-based loaders (Forge, NeoForge). */
  javaPath?: string;
  signal?: AbortSignal;
  onProgress?: (stage: string) => void;
}

/** A mod loader the launcher can list, install and launch through a version profile. */
export interface IModLoader {
  readonly id: Exclude<LoaderId, 'vanilla'>;
  readonly displayName: string;
  /** Newest first. */
  listVersions(minecraftVersion: string): Promise<LoaderVersion[]>;
  /** Version id of an already installed profile, or null when it still needs installing. */
  findInstalled(minecraftVersion: string, loaderVersion: string, ctx: Pick<LoaderInstallContext, 'versions'>): Promise<string | null>;
  /** Installs vanilla + loader files and returns the version id to launch. */
  install(minecraftVersion: string, loaderVersion: string, ctx: LoaderInstallContext): Promise<string>;
}

export class LoaderError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'LoaderError';
  }
}

export const LOADER_VERSION_PATTERN = /^[A-Za-z0-9._+-]{1,64}$/;
