import { stat } from 'node:fs/promises';
import { basename } from 'node:path';
import { getLogger } from '../logging/Logger.js';
import { sha1File } from '../util/fsutil.js';
import { ZipReader, type ZipEntry } from '../util/zip.js';

const log = getLogger('scanner');

/** 'known' is a file recognised as a published release, byte for byte; the rest come from reading it. */
export type Verdict = 'known' | 'clean' | 'watch' | 'suspicious' | 'dangerous';

/** Where a known file was published, and as what. */
export interface KnownMod {
  source: 'Modrinth' | 'Snowball';
  project: string;
  version: string;
}

export interface ScanOptions {
  /** SHA-1s of files the launcher put there itself and verified. */
  trusted?: ReadonlySet<string>;
  /** Looks fingerprints up somewhere that can vouch for them. Called once, with every file's SHA-1. */
  recognise?: (sha1s: string[]) => Promise<ReadonlyMap<string, KnownMod>>;
}

export interface ScanReport {
  results: ScanResult[];
  /** False when the lookup could not be made, so nothing could be recognised this time. */
  recognised: boolean;
}

export interface ScanFinding {
  /** Short name of what was found, e.g. "Runs programs on your computer". */
  title: string;
  /** Why it matters, in plain language. */
  detail: string;
  /** Where it was found: a class or file inside the jar. */
  where: string;
  weight: number;
}

export interface ScanResult {
  fileName: string;
  sha1: string;
  sizeBytes: number;
  verdict: Verdict;
  score: number;
  findings: ScanFinding[];
  /** Mod id and name from the jar's own metadata, when it has any. */
  modId: string | null;
  scannedAt: string;
  /** Set when the file is a published release, unchanged. */
  known?: KnownMod;
  error?: string;
}

interface Rule {
  /** Bytes to look for inside class files; class constants are stored as plain text. */
  needles: string[];
  title: string;
  detail: string;
  weight: number;
  /** Rules that only matter when another rule also matched (network + secrets, say). */
  pairsWith?: string;
  id: string;
}

/**
 * What the scanner looks for. Each rule on its own is something a normal mod might do, which is why
 * they carry weights and why the report always says what was found instead of only a verdict.
 */
const RULES: Rule[] = [
  { id: 'exec', needles: ['java/lang/Runtime', 'getRuntime', 'java/lang/ProcessBuilder'], title: 'Runs other programs', detail: 'The mod can start programs on your computer. Normal for a few tools, unusual for a client mod.', weight: 25 },
  { id: 'shell', needles: ['cmd.exe', 'powershell', '/bin/sh', 'cmd /c'], title: 'Uses the command line', detail: 'It builds command line calls, which is how most stealers run their payload.', weight: 30 },
  { id: 'discord-token', needles: ['Local Storage\\leveldb', 'Local Storage/leveldb', 'discord\\Local Storage', 'discordcanary', 'discordptb'], title: 'Reads Discord login data', detail: 'It looks inside Discord\'s own folder, where login tokens are kept. There is no honest reason for a mod to do this.', weight: 60 },
  { id: 'webhook', needles: ['discord.com/api/webhooks', 'discordapp.com/api/webhooks', 'hooks.slack.com'], title: 'Sends data to a webhook', detail: 'A webhook address is built into the jar. Stealers use these to send what they take.', weight: 55 },
  { id: 'session', needles: ['launcher_accounts.json', 'launcher_profiles.json', 'getAccessToken', 'getSessionId', 'MinecraftSessionService'], title: 'Touches your Minecraft account', detail: 'It reads the files or fields that hold your Minecraft session.', weight: 45 },
  { id: 'browser', needles: ['Login Data', 'Cookies\\Default', 'cookies.sqlite', 'Opera Stable', 'Chrome\\User Data'], title: 'Reads browser data', detail: 'It goes looking in browser folders, where saved passwords and cookies live.', weight: 55 },
  { id: 'clipboard', needles: ['getSystemClipboard', 'java/awt/Toolkit'], title: 'Reads the clipboard or screen', detail: 'It can read what you copy or take screenshots.', weight: 15 },
  { id: 'robot', needles: ['java/awt/Robot', 'createScreenCapture'], title: 'Takes screenshots of your desktop', detail: 'It can capture your whole screen, not just the game.', weight: 35 },
  { id: 'remote-class', needles: ['java/net/URLClassLoader', 'defineClass'], title: 'Loads code from somewhere else', detail: 'It can pull in code that is not in this file, so what it does can change at any time.', weight: 40 },
  { id: 'startup', needles: ['CurrentVersion\\Run', 'schtasks', 'reg add', 'Startup\\'], title: 'Makes itself start with Windows', detail: 'It writes to the places Windows uses to start programs at boot.', weight: 50 },
  { id: 'network', needles: ['java/net/HttpURLConnection', 'java/net/URL', 'okhttp3', 'java/net/Socket'], title: 'Talks to the internet', detail: 'Plenty of mods do (updates, APIs); it only matters together with the findings above.', weight: 5 },
  { id: 'obfuscated-strings', needles: ['javax/crypto/Cipher', 'AES/CBC', 'Base64$Decoder'], title: 'Hides text from you', detail: 'Strings are encrypted or encoded, which is how a mod hides what it is really doing.', weight: 20 },
  { id: 'hidden-file', needles: ['setAttribute("dos:hidden"', 'attrib +h'], title: 'Hides files it writes', detail: 'It marks files as hidden after writing them.', weight: 30 },
];

const DANGEROUS = 70;
const SUSPICIOUS = 40;
const WATCH = 20;

/**
 * Scans one jar: the file is read here and compared with the rules above. A file in `known` is a
 * published release, unchanged, and is reported as that rather than read - the rules are about
 * what a file can do, and for a known release that question has already been answered.
 */
export async function scanJar(path: string, trusted: ReadonlySet<string> = new Set(), known: ReadonlyMap<string, KnownMod> = new Map(), sha1Hint?: string): Promise<ScanResult> {
  const fileName = basename(path);
  const scannedAt = new Date().toISOString();
  let sizeBytes = 0;
  let sha1 = '';
  try {
    sizeBytes = (await stat(path)).size;
    sha1 = sha1Hint ?? (await sha1File(path));
    if (trusted.has(sha1)) {
      // A file the launcher put there itself and verified: it is what it says it is.
      return { fileName, sha1, sizeBytes, verdict: 'known', score: 0, findings: [], modId: 'snowballclient', scannedAt, known: { source: 'Snowball', project: 'Snowball Client', version: '' } };
    }
    const release = known.get(sha1);
    if (release) return { fileName, sha1, sizeBytes, verdict: 'known', score: 0, findings: [], modId: null, scannedAt, known: release };
    const zip = await ZipReader.open(path);
    const findings = scanEntries(zip);
    const modId = readModId(zip);
    if (!modId && zip.entries.some((e) => e.name.endsWith('.class'))) {
      findings.push({ title: 'Not a normal mod file', detail: 'The jar has code but no mod description, so no loader would load it on its own.', where: fileName, weight: 25 });
    }
    const score = Math.min(100, findings.reduce((sum, f) => sum + f.weight, 0));
    return { fileName, sha1, sizeBytes, verdict: verdictFor(score), score, findings, modId, scannedAt };
  } catch (err) {
    log.warn('Could not scan a mod', { file: fileName, error: String(err) });
    return { fileName, sha1, sizeBytes, verdict: 'watch', score: 0, findings: [], modId: null, scannedAt, error: (err as Error).message };
  }
}

function verdictFor(score: number): Verdict {
  if (score >= DANGEROUS) return 'dangerous';
  if (score >= SUSPICIOUS) return 'suspicious';
  if (score >= WATCH) return 'watch';
  return 'clean';
}

function scanEntries(zip: ZipReader): ScanFinding[] {
  const hits = new Map<string, ScanFinding>();
  let jarInJar = 0;
  for (const entry of zip.entries) {
    if (entry.isDirectory) continue;
    if (/\.jar$/i.test(entry.name)) jarInJar++;
    if (!/\.(class|json|txt|properties|js)$/i.test(entry.name)) continue;
    if (entry.uncompressedSize > 8 * 1024 * 1024) continue;
    let text: string;
    try {
      text = zip.read(entry).toString('latin1');
    } catch {
      continue;
    }
    for (const rule of RULES) {
      if (hits.has(rule.id)) continue;
      const needle = rule.needles.find((n) => text.includes(n));
      if (!needle) continue;
      hits.set(rule.id, { title: rule.title, detail: rule.detail, where: `${entry.name} (${needle})`, weight: rule.weight });
    }
  }
  const findings = [...hits.values()];
  // A mod that only talks to the internet is ordinary; it is the company it keeps that matters.
  if (hits.size === 1 && hits.has('network')) return [];
  if (jarInJar > 3) {
    findings.push({ title: 'Packs other jars inside', detail: `${jarInJar} jars are bundled inside this one. Usual for big mods, worth knowing about for small ones.`, where: 'inside the jar', weight: 10 });
  }
  return findings;
}

function readModId(zip: ZipReader): string | null {
  for (const name of ['fabric.mod.json', 'quilt.mod.json', 'META-INF/mods.toml', 'META-INF/neoforge.mods.toml', 'mcmod.info']) {
    const text = zip.readText(name);
    if (!text) continue;
    const json = name.endsWith('.json') ? safeParse(text) : null;
    if (json && typeof json.id === 'string') return json.id;
    if (json && typeof (json as Record<string, unknown>).quilt_loader === 'object') {
      const id = ((json as Record<string, any>).quilt_loader as Record<string, unknown>).id;
      if (typeof id === 'string') return id;
    }
    const toml = text.match(/modId\s*=\s*"([^"]+)"/);
    if (toml) return toml[1];
    return name;
  }
  return null;
}

function safeParse(text: string): Record<string, any> | null {
  try {
    return JSON.parse(text) as Record<string, any>;
  } catch {
    return null;
  }
}

/**
 * A whole folder: every jar, the ones worth a look first. Fingerprints are looked up once for the
 * lot; if that fails (offline, say) every file is simply read instead, and the report says so.
 */
export async function scanJars(paths: string[], options: ScanOptions = {}): Promise<ScanReport> {
  const hashes = new Map<string, string>();
  for (const path of paths) {
    try {
      hashes.set(path, await sha1File(path));
    } catch {
      // scanJar reports the file it cannot read.
    }
  }
  let known: ReadonlyMap<string, KnownMod> = new Map();
  let recognised = !options.recognise;
  if (options.recognise && hashes.size) {
    try {
      known = await options.recognise([...new Set(hashes.values())]);
      recognised = true;
    } catch (err) {
      log.info('Could not look up mod fingerprints; every mod is read instead', { error: String(err) });
    }
  }
  const results: ScanResult[] = [];
  for (const path of paths) results.push(await scanJar(path, options.trusted, known, hashes.get(path)));
  const order: Record<Verdict, number> = { dangerous: 0, suspicious: 1, watch: 2, clean: 3, known: 4 };
  return { results: results.sort((a, b) => order[a.verdict] - order[b.verdict] || b.score - a.score || a.fileName.localeCompare(b.fileName)), recognised };
}

export type { ZipEntry };
