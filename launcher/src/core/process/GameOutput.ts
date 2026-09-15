import type { ActivityLevel } from '../logging/Activity.js';

export type GameLogLevel = 'TRACE' | 'DEBUG' | 'INFO' | 'WARN' | 'ERROR' | 'FATAL';

/** One log event from the game, whether it arrived as a Log4j XML event or a plain console line. */
export interface GameRecord {
  level: GameLogLevel;
  logger: string;
  thread: string;
  time: number;
  message: string;
  throwable?: string;
  /** For plain console lines: the line exactly as printed, which is what the technical log shows. */
  raw?: string;
}

const LEVELS: GameLogLevel[] = ['TRACE', 'DEBUG', 'INFO', 'WARN', 'ERROR', 'FATAL'];
const MAX_EVENT_LINES = 5000;
// Minecraft's console format: "[14:27:33] [main/INFO]: message"
const CONSOLE_LINE = /^\[\d{2}:\d{2}:\d{2}\] \[([^\]]+)\/([A-Z]+)\]: ([\s\S]*)$/;

const unescapeXml = (s: string) => s.replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&quot;/g, '"').replace(/&apos;/g, "'").replace(/&amp;/g, '&');

function textOf(content: string): string {
  let out = '';
  const parts = /<!\[CDATA\[([\s\S]*?)\]\]>|([^<]+)/g;
  let m: RegExpExecArray | null;
  while ((m = parts.exec(content))) out += m[1] !== undefined ? m[1] : unescapeXml(m[2]);
  return out;
}

function parseEvent(xml: string): GameRecord | null {
  const open = /<log4j:Event\b([^>]*)>/.exec(xml);
  if (!open) return null;
  const attr = (name: string) => unescapeXml(new RegExp(`\\b${name}="([^"]*)"`).exec(open[1])?.[1] ?? '');
  const element = (name: string) => {
    const m = new RegExp(`<log4j:${name}>([\\s\\S]*?)</log4j:${name}>`).exec(xml);
    return m ? textOf(m[1]) : undefined;
  };
  const level = attr('level').toUpperCase() as GameLogLevel;
  const time = Number(attr('timestamp'));
  return {
    level: LEVELS.includes(level) ? level : 'INFO',
    logger: attr('logger'),
    thread: attr('thread'),
    time: Number.isFinite(time) && time > 0 ? time : Date.now(),
    message: element('Message') ?? '',
    throwable: element('Throwable')?.trim() || undefined,
  };
}

function plainRecord(line: string, stream: 'stdout' | 'stderr', now: number): GameRecord {
  const m = CONSOLE_LINE.exec(line);
  const guessed: GameLogLevel = m && LEVELS.includes(m[2] as GameLogLevel) ? (m[2] as GameLogLevel) : stream === 'stderr' && /exception|error/i.test(line) ? 'ERROR' : 'INFO';
  return { level: guessed, logger: '', thread: m?.[1] ?? '', time: now, message: m ? m[3] : line, raw: line };
}

/**
 * Turns the game's console output into records. Modern Minecraft prints Log4j XML events spanning
 * several lines; they are reassembled here. Anything else passes through as a plain record.
 */
export class GameOutputParser {
  private pending: string[] | null = null;

  push(line: string, stream: 'stdout' | 'stderr', now = Date.now()): GameRecord[] {
    if (this.pending) {
      this.pending.push(line);
      if (line.includes('</log4j:Event>')) return this.flush();
      if (this.pending.length > MAX_EVENT_LINES) {
        const lines = this.pending;
        this.pending = null;
        return lines.map((l) => plainRecord(l, stream, now));
      }
      return [];
    }
    if (!line.trim()) return [];
    if (/<log4j:Event\b/.test(line)) {
      this.pending = [line];
      return line.includes('</log4j:Event>') ? this.flush() : [];
    }
    return [plainRecord(line, stream, now)];
  }

  private flush(): GameRecord[] {
    const xml = this.pending!.join('\n');
    this.pending = null;
    const record = parseEvent(xml);
    return record ? [record] : [];
  }
}

/** Text for the technical log, in Minecraft's own console format. */
export function formatRecord(record: GameRecord): string {
  if (record.raw !== undefined) return record.raw;
  const d = new Date(record.time);
  const pad = (n: number) => String(n).padStart(2, '0');
  const head = `[${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}] [${record.thread}/${record.level}]: `;
  return head + record.message + (record.throwable ? `\n${record.throwable}` : '');
}

export interface ActivityLine {
  level: ActivityLevel;
  message: string;
}

const ERROR_GAP_MS = 5000;
const MAX_ERROR_LINES = 15;

/**
 * Picks out the moments a player cares about ("Loading 56 mods", "Joined the world") and explains
 * problems in plain language. Everything else stays in the technical log.
 */
export class GameActivityInterpreter {
  private readonly seen = new Set<string>();
  private lastErrorAt = -Infinity;
  private errorLines = 0;

  constructor(private readonly context: { minecraftVersion: string; loader: string }) {}

  interpret(record: GameRecord): ActivityLine[] {
    const out: ActivityLine[] = [];
    const once = (key: string, level: ActivityLevel, message: string) => {
      if (this.seen.has(key)) return;
      this.seen.add(key);
      out.push({ level, message });
    };
    const msg = record.message;
    const everything = `${msg}\n${record.throwable ?? ''}`;
    let m: RegExpExecArray | null;

    if ((m = /^Loading Minecraft (\S+) with (Fabric|Quilt) Loader (\S+)/.exec(msg))) once('loading', 'info', `Loading Minecraft ${m[1]} with ${m[2]} Loader ${m[3]}`);
    else if (/^Setting user: /.test(msg)) once('loading', 'info', `Loading Minecraft ${this.context.minecraftVersion}`);
    if ((m = /^Loading (\d+) mods:/.exec(msg))) once('mods', 'info', `Loading ${m[1]} mods`);
    if ((m = /(?:^|\n)\s*- snowballclient (\S+)/.exec(msg))) once('client', 'success', `Snowball Client ${m[1]} loaded`);
    if (/^Reloading ResourceManager:/.test(msg)) once('resources', 'info', 'Loading resources');
    if (/^Sound engine started/.test(msg)) once('ready', 'success', 'Minecraft is ready');
    if (/^Starting integrated minecraft server version/.test(msg)) {
      this.seen.delete('joined');
      out.push({ level: 'info', message: 'Opening singleplayer world' });
    }
    if ((m = /^Connecting to (.+), (\d+)$/.exec(msg))) {
      this.seen.delete('joined');
      out.push({ level: 'info', message: `Connecting to ${m[1]}` });
    }
    if ((m = /^(\S+) joined the game$/.exec(msg)) && record.thread === 'Server thread') once('joined', 'success', `Joined the world as ${m[1]}`);
    if (/^Stopping singleplayer server as player logged out/.test(msg)) out.push({ level: 'info', message: 'Left the world' });
    if (/^Stopping!$/.test(msg)) once('stopping', 'info', 'Closing Minecraft');

    if (/java\.lang\.OutOfMemoryError/.test(everything)) once('oom', 'error', 'Minecraft ran out of memory. Give this instance more memory in Edit > Java & Memory.');
    if (/Incompatible mods? found|incompatible mod set/i.test(msg)) {
      const details = msg.split('\n').map((s) => s.trim()).filter((s) => s.startsWith('- ')).slice(0, 3).map((s) => s.slice(2));
      once('incompatible', 'error', `Some mods can't run together${details.length ? `: ${details.join('; ')}` : '. See the technical log for details.'}`);
    }
    if ((m = /Mixin apply(?:ing)? for mod (\S+) failed/i.exec(msg))) once(`mixin:${m[1]}`, 'error', `The mod "${m[1]}" failed to load`);

    if ((record.level === 'ERROR' || record.level === 'FATAL') && !out.some((o) => o.level === 'error') && this.errorLines < MAX_ERROR_LINES) {
      if (record.time - this.lastErrorAt >= ERROR_GAP_MS) {
        this.lastErrorAt = record.time;
        this.errorLines++;
        const source = record.logger.startsWith('SnowballClient') ? 'Snowball Client' : 'Minecraft';
        const first = msg.split('\n')[0].trim().slice(0, 160) || record.throwable?.split('\n')[0].slice(0, 160) || 'an error';
        out.push({ level: 'warn', message: this.errorLines === MAX_ERROR_LINES ? 'More errors were logged; open the technical log for details' : `${source} reported an error: ${first}` });
      }
    }
    return out;
  }
}
