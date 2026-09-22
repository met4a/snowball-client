import { readJson, writeJsonAtomic } from '../util/fsutil.js';
import { getLogger } from '../logging/Logger.js';

const log = getLogger('settings');

export interface LauncherSettings {
  schemaVersion: 1;
  appearance: {
    reduceMotion: boolean;
    /** The interface colour, as #rrggbb. Everything accented follows it. */
    accent: string;
    /** 'compact' tightens the spacing throughout, for smaller screens. */
    density: 'cosy' | 'compact';
    /** A picture behind the launcher. The file itself lives in the data folder. */
    background: { enabled: boolean; opacity: number; blur: number };
  };
  downloads: { concurrency: number; retries: number };
  java: { autoDownloadRuntime: boolean; defaultMaxMemoryMb: number | null };
  game: { closeLauncherOnLaunch: boolean; showLogsOnLaunch: boolean };
  /** rank is written only from what the Snowball backend reports for the signed-in account. */
  accounts: { microsoftClientId: string; selectedAccountId: string | null; rank: string };
  updates: { channel: 'stable' | 'beta'; automatic: boolean };
  /** installMods: let a performance profile download its optimisation mods. Off by default: profiles then
   *  only use what the instance already has. */
  performance: { installMods: boolean };
  logs: { retainDays: number; debug: boolean };
  selectedInstanceId: string | null;
}

export function defaultSettings(): LauncherSettings {
  return {
    schemaVersion: 1,
    appearance: {
      reduceMotion: false,
      accent: '#7fcbff',
      density: 'cosy',
      background: { enabled: false, opacity: 35, blur: 0 },
    },
    downloads: { concurrency: 8, retries: 3 },
    java: { autoDownloadRuntime: true, defaultMaxMemoryMb: null },
    game: { closeLauncherOnLaunch: false, showLogsOnLaunch: false },
    accounts: { microsoftClientId: '', selectedAccountId: null, rank: 'snowball' },
    updates: { channel: 'stable', automatic: true },
    performance: { installMods: false },
    logs: { retainDays: 14, debug: false },
    selectedInstanceId: null,
  };
}

const int = (v: unknown, min: number, max: number, d: number) => (typeof v === 'number' && Number.isFinite(v) ? Math.min(max, Math.max(min, Math.round(v))) : d);
const bool = (v: unknown, d: boolean) => (typeof v === 'boolean' ? v : d);

/** Validates untrusted settings.json content field by field; anything invalid falls back to defaults. */
export function normalizeSettings(raw: unknown): LauncherSettings {
  const d = defaultSettings();
  if (!raw || typeof raw !== 'object') return d;
  const r = raw as Record<string, any>;
  return {
    schemaVersion: 1,
    appearance: {
      reduceMotion: bool(r.appearance?.reduceMotion, d.appearance.reduceMotion),
      // Anything that is not a colour falls back, so a hand-edited file cannot inject CSS.
      accent: /^#[0-9a-fA-F]{6}$/.test(String(r.appearance?.accent)) ? String(r.appearance?.accent).toLowerCase() : d.appearance.accent,
      density: r.appearance?.density === 'compact' ? 'compact' : 'cosy',
      background: {
        enabled: bool(r.appearance?.background?.enabled, d.appearance.background.enabled),
        opacity: int(r.appearance?.background?.opacity, 0, 100, d.appearance.background.opacity),
        blur: int(r.appearance?.background?.blur, 0, 40, d.appearance.background.blur),
      },
    },
    downloads: { concurrency: int(r.downloads?.concurrency, 1, 32, d.downloads.concurrency), retries: int(r.downloads?.retries, 0, 10, d.downloads.retries) },
    java: {
      autoDownloadRuntime: bool(r.java?.autoDownloadRuntime, d.java.autoDownloadRuntime),
      defaultMaxMemoryMb: r.java?.defaultMaxMemoryMb === null || r.java?.defaultMaxMemoryMb === undefined ? null : int(r.java.defaultMaxMemoryMb, 512, 262144, 4096),
    },
    game: { closeLauncherOnLaunch: bool(r.game?.closeLauncherOnLaunch, false), showLogsOnLaunch: bool(r.game?.showLogsOnLaunch, false) },
    accounts: {
      microsoftClientId: typeof r.accounts?.microsoftClientId === 'string' && /^[0-9a-fA-F-]{0,36}$/.test(r.accounts.microsoftClientId) ? r.accounts.microsoftClientId : '',
      selectedAccountId: typeof r.accounts?.selectedAccountId === 'string' ? r.accounts.selectedAccountId : null,
      rank: typeof r.accounts?.rank === 'string' ? r.accounts.rank : 'snowball',
    },
    // `automatic` replaced `checkOnStartup`, which only decided whether to look. The old value
    // is deliberately not carried over: it answered a different question.
    updates: { channel: r.updates?.channel === 'beta' ? 'beta' : 'stable', automatic: bool(r.updates?.automatic, d.updates.automatic) },
    performance: { installMods: bool(r.performance?.installMods, d.performance.installMods) },
    logs: { retainDays: int(r.logs?.retainDays, 1, 365, d.logs.retainDays), debug: bool(r.logs?.debug, false) },
    selectedInstanceId: typeof r.selectedInstanceId === 'string' ? r.selectedInstanceId : null,
  };
}

export class SettingsStore {
  private current: LauncherSettings = defaultSettings();

  constructor(private readonly file: string) {}

  async load(): Promise<LauncherSettings> {
    const result = await readJson(this.file);
    if (!result.ok && result.reason === 'malformed') log.warn('settings.json was malformed; defaults will be used', { error: result.error });
    this.current = normalizeSettings(result.ok ? result.value : undefined);
    return this.current;
  }

  get(): LauncherSettings {
    return this.current;
  }

  async update(patch: (s: LauncherSettings) => void): Promise<LauncherSettings> {
    const draft = structuredClone(this.current);
    patch(draft);
    this.current = normalizeSettings(draft);
    await writeJsonAtomic(this.file, this.current);
    return this.current;
  }
}
