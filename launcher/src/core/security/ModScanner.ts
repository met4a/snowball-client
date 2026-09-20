import { stat } from 'node:fs/promises';
import { basename } from 'node:path';
import { getLogger } from '../logging/Logger.js';
import { sha1File } from '../util/fsutil.js';
import { ZipReader, type ZipEntry } from '../util/zip.js';

const log = getLogger('scanner');

export type Verdict = 'clean' | 'watch' | 'suspicious' | 'dangerous';

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

/** Scans one jar. Nothing leaves this computer: the file is only read and compared with the rules above. */
export async function scanJar(path: string, trusted: ReadonlySet<string> = new Set()): Promise<ScanResult> {
  const fileName = basename(path);
  const scannedAt = new Date().toISOString();
  let sizeBytes = 0;
  let sha1 = '';
  try {
    sizeBytes = (await stat(path)).size;
    sha1 = await sha1File(path);
    if (trusted.has(sha1)) {
      // A file the launcher put there itself and verified: it is what it says it is.
      return { fileName, sha1, sizeBytes, verdict: 'clean', score: 0, findings: [], modId: 'snowballclient', scannedAt };
    }
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

/** Convenience for a whole folder: every jar, newest result first. */
export async function scanJars(paths: string[], trusted: ReadonlySet<string> = new Set()): Promise<ScanResult[]> {
  const results: ScanResult[] = [];
  for (const path of paths) results.push(await scanJar(path, trusted));
  const order: Record<Verdict, number> = { dangerous: 0, suspicious: 1, watch: 2, clean: 3 };
  return results.sort((a, b) => order[a.verdict] - order[b.verdict] || b.score - a.score);
}

export type { ZipEntry };
