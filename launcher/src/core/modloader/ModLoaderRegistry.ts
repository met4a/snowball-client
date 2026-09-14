import type { DownloadManager } from '../download/DownloadManager.js';
import type { LoaderId } from '../instance/InstanceManager.js';
import { FabricLikeLoader } from './FabricLikeLoader.js';
import type { IModLoader } from './IModLoader.js';
import { InstallerLoader, type InstallerRunner } from './InstallerLoader.js';

/** Looks up loader implementations by id; the UI and launch code only see IModLoader. */
export class ModLoaderRegistry {
  private readonly loaders = new Map<string, IModLoader>();

  constructor(downloads: Pick<DownloadManager, 'fetchJson' | 'fetchText'>, runner?: InstallerRunner) {
    for (const loader of [FabricLikeLoader.fabric(downloads), FabricLikeLoader.quilt(downloads), InstallerLoader.forge(downloads, runner), InstallerLoader.neoforge(downloads, runner)]) {
      this.loaders.set(loader.id, loader);
    }
  }

  get(id: LoaderId): IModLoader | null {
    return this.loaders.get(id) ?? null;
  }

  all(): IModLoader[] {
    return [...this.loaders.values()];
  }
}
