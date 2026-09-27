import { describe, expect, it } from 'vitest';
import { Launcher, type LaunchPhaseEvent } from '../src/core/Launcher.js';
import { tempDir } from './helpers.js';

const cipher = { isAvailable: () => false, encrypt: (s: string) => s, decrypt: (s: string) => s };

/** A launcher whose preparation waits until the test lets it go, with a signed-in account. */
async function launcherWithSlowPrepare() {
  const launcher = await Launcher.create({ root: tempDir(), cipher, clientBuildDirs: [], launcherVersion: 'test', consoleLogs: false });
  Object.defineProperty(launcher, 'auth', {
    value: { list: () => [{ id: 'a' }], session: async () => ({ name: 'Alex', uuid: '0'.repeat(32), accessToken: 'token', type: 'offline' }) },
  });
  let started = 0;
  (launcher as unknown as { prepare: (id: string, signal: AbortSignal) => Promise<never> }).prepare = (_id, signal) => {
    started++;
    // Never finishes on its own: only a cancel ends it, which is what the tests below need.
    return new Promise((_, reject) => {
      if (signal.aborted) reject(signal.reason);
      else signal.addEventListener('abort', () => reject(signal.reason), { once: true });
    });
  };
  const config = await launcher.instances.create({ name: 'Test', minecraftVersion: '1.21.11', loader: 'vanilla' });
  const phases: Array<LaunchPhaseEvent['phase']> = [];
  launcher.on('phase', (e: LaunchPhaseEvent) => phases.push(e.phase));
  /** Until preparation has actually begun: launching reads the instance first. */
  const preparing = async (count: number) => {
    for (let i = 0; i < 200 && started < count; i++) await new Promise((r) => setTimeout(r, 5));
  };
  return { launcher, id: config.id, phases, started: () => started, preparing };
}

describe('launch phases', () => {
  it('refuses a second launch of the same instance while the first is preparing', async () => {
    const { launcher, id, started, preparing } = await launcherWithSlowPrepare();
    const first = launcher.launch(id);
    await preparing(1);
    expect(launcher.launchPhase(id)).toBe('preparing');
    // Play pressed twice used to prepare the instance twice and open Minecraft twice.
    await expect(launcher.launch(id)).rejects.toThrow(/already starting or running/);
    expect(started()).toBe(1);
    expect(launcher.stop(id)).toBe(true);
    await first;
  });

  it('cancels while preparing, clears the phase, and lets Play start again', async () => {
    const { launcher, id, phases, started, preparing } = await launcherWithSlowPrepare();
    const first = launcher.launch(id);
    await preparing(1);
    launcher.stop(id);
    await expect(first).resolves.toBeUndefined();
    expect(launcher.launchPhase(id)).toBeNull();
    expect(phases).toEqual(['preparing', null]);
    expect(launcher.activity.recent(id).some((e) => e.message === 'Launch cancelled')).toBe(true);

    const again = launcher.launch(id);
    await preparing(2);
    expect(started()).toBe(2);
    launcher.stop(id);
    await again;
  });

  it('has nothing to stop when nothing is starting or running', async () => {
    const { launcher, id } = await launcherWithSlowPrepare();
    expect(launcher.stop(id)).toBe(false);
  });
});
