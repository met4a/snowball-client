import { describe, expect, it } from 'vitest';
import { normalizeSettings } from '../src/core/settings/LauncherSettings.js';

describe('launcher behaviour settings', () => {
  it('minimise when Minecraft starts, by default', () => {
    const s = normalizeSettings({});
    expect(s.game.minimizeOnLaunch).toBe(true);
    expect(s.game.closeLauncherOnLaunch).toBe(false);
  });

  it('keep what an old config meant: its only switch minimised the window', () => {
    // Before 1.8.0 "Minimize when the game starts" was stored as closeLauncherOnLaunch.
    const turnedOn = normalizeSettings({ game: { closeLauncherOnLaunch: true } });
    expect(turnedOn.game).toMatchObject({ minimizeOnLaunch: true, closeLauncherOnLaunch: false });
    const turnedOff = normalizeSettings({ game: { closeLauncherOnLaunch: false } });
    expect(turnedOff.game).toMatchObject({ minimizeOnLaunch: true, closeLauncherOnLaunch: false });
  });

  it('respect both switches once they have been saved separately', () => {
    const s = normalizeSettings({ game: { closeLauncherOnLaunch: true, minimizeOnLaunch: false } });
    expect(s.game).toMatchObject({ minimizeOnLaunch: false, closeLauncherOnLaunch: true });
  });
});
