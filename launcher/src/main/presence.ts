import { readFile } from 'node:fs/promises';
import { join } from 'node:path';
import type { Launcher, LaunchPhaseEvent } from '../core/Launcher.js';
import type { GameExit } from '../core/process/ProcessManager.js';
import { DiscordRpc, type Presence } from '../core/social/DiscordRpc.js';

const ICON = { largeImageKey: 'snowball', largeImageText: 'Snowball Client' };

/**
 * What Discord shows while the launcher runs, worked out from what the launcher is doing: browsing,
 * preparing, launching, playing. Only names the launcher already shows - an instance, a Minecraft
 * version - never an account, a path or a server address.
 *
 * While the game runs with Snowball Client's own Discord module switched on, the game shows the
 * presence (it knows the world you are in) and the launcher's is cleared, so only one appears.
 */
export class LauncherPresence {
  private readonly started = new Map<string, number>();

  constructor(private readonly launcher: Launcher, readonly rpc: DiscordRpc) {}

  attach(): void {
    this.launcher.on('phase', (e: LaunchPhaseEvent) => {
      if (e.phase === 'launching') this.started.set(e.instanceId, Math.floor(Date.now() / 1000));
      void this.update();
    });
    this.launcher.processes.on('exit', (exit: GameExit) => {
      this.started.delete(exit.instanceId);
      void this.update();
    });
    if (this.launcher.settings.get().discord.enabled) this.rpc.start();
    void this.update();
  }

  /** Called when the Discord setting changes. */
  async setEnabled(enabled: boolean): Promise<void> {
    if (enabled) {
      this.rpc.start();
      await this.update();
    } else {
      await this.rpc.stop();
    }
  }

  async update(): Promise<void> {
    this.rpc.setPresence(await this.presence());
  }

  private async presence(): Promise<Presence | null> {
    const instances = await this.launcher.instances.list();
    const active = instances
      .map((s) => ({ config: s.config, phase: this.launcher.launchPhase(s.config.id) ?? (this.launcher.processes.isRunning(s.config.id) ? 'running' : null) }))
      .filter((a) => a.phase !== null);
    const current = active.find((a) => a.phase === 'running') ?? active[0];
    if (!current) return { details: 'Using Snowball Launcher', state: 'Browsing instances', ...ICON };

    const { config, phase } = current;
    const where = `${config.name} - Minecraft ${config.minecraftVersion}`;
    if (phase === 'preparing') return { details: 'Preparing Minecraft', state: where, ...ICON };
    if (phase === 'launching') return { details: 'Launching Minecraft', state: where, ...ICON };
    if (await this.gameShowsItsOwn(config.id)) return null;
    const snowball = this.launcher.snowballSupport(config.minecraftVersion, config.loader).supported;
    return {
      details: `Playing Minecraft ${config.minecraftVersion}`,
      state: snowball ? `${config.name} - Snowball Client` : config.name,
      startTimestamp: this.started.get(config.id),
      ...ICON,
    };
  }

  /** Whether Snowball Client's Discord module is on in this instance: then the game owns the presence. */
  private async gameShowsItsOwn(instanceId: string): Promise<boolean> {
    try {
      const file = join(this.launcher.instances.gameDir(instanceId), 'config', 'snowballclient', 'modules.json');
      const modules = JSON.parse(await readFile(file, 'utf8')) as Record<string, { enabled?: unknown }>;
      return modules.discord_presence?.enabled === true;
    } catch {
      return false;
    }
  }
}
