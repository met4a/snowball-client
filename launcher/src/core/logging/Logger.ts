import { appendFileSync, mkdirSync, readdirSync, rmSync, statSync } from 'node:fs';
import { join } from 'node:path';

export enum LogLevel {
  DEBUG = 10,
  INFO = 20,
  WARN = 30,
  ERROR = 40,
  FATAL = 50,
}

export interface LogRecord {
  time: string;
  level: keyof typeof LogLevel;
  scope: string;
  message: string;
  data?: Record<string, unknown>;
}

type Listener = (record: LogRecord) => void;

const SENSITIVE_KEY = /(token|password|secret|authorization|refresh|cookie|xuid|clientsecret)/i;
// Covers "--accessToken <value>" style launch arguments and bearer headers inside free text.
const SENSITIVE_TEXT: Array<[RegExp, string]> = [
  [/(--accessToken\s+)\S+/gi, '$1<redacted>'],
  [/(Bearer\s+)[A-Za-z0-9._\-]+/g, '$1<redacted>'],
  [/("?(?:access_?token|refresh_?token|id_token)"?\s*[:=]\s*"?)[^"\s,}]+/gi, '$1<redacted>'],
];

export function redactText(text: string): string {
  let out = text;
  for (const [pattern, replacement] of SENSITIVE_TEXT) out = out.replace(pattern, replacement);
  return out;
}

export function redactData(value: unknown, depth = 0): unknown {
  if (depth > 6) return '<depth-limit>';
  if (typeof value === 'string') return redactText(value);
  if (Array.isArray(value)) return value.map((v) => redactData(v, depth + 1));
  if (value instanceof Error) return { name: value.name, message: redactText(value.message), stack: value.stack ? redactText(value.stack) : undefined };
  if (value && typeof value === 'object') {
    const out: Record<string, unknown> = {};
    for (const [k, v] of Object.entries(value)) {
      out[k] = SENSITIVE_KEY.test(k) ? '<redacted>' : redactData(v, depth + 1);
    }
    return out;
  }
  return value;
}

/**
 * Structured logger. Writes JSON lines to a daily file (when a directory is configured),
 * mirrors to the console and fans out to listeners such as the launcher's log view.
 * All messages and data pass through redaction so auth material never reaches disk.
 */
export class LogSink {
  private listeners = new Set<Listener>();
  private logDir: string | null = null;
  minLevel: LogLevel = LogLevel.DEBUG;
  consoleOutput = true;

  configure(options: { directory?: string; minLevel?: LogLevel; console?: boolean; retainFiles?: number }): void {
    if (options.minLevel !== undefined) this.minLevel = options.minLevel;
    if (options.console !== undefined) this.consoleOutput = options.console;
    if (options.directory) {
      mkdirSync(options.directory, { recursive: true });
      this.logDir = options.directory;
      this.prune(options.retainFiles ?? 14);
    }
  }

  subscribe(listener: Listener): () => void {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  }

  write(level: LogLevel, scope: string, message: string, data?: Record<string, unknown>): void {
    if (level < this.minLevel) return;
    const record: LogRecord = {
      time: new Date().toISOString(),
      level: LogLevel[level] as keyof typeof LogLevel,
      scope,
      message: redactText(message),
      data: data ? (redactData(data) as Record<string, unknown>) : undefined,
    };
    if (this.consoleOutput) {
      const line = `[${record.time}] ${record.level.padEnd(5)} ${scope}: ${record.message}`;
      if (level >= LogLevel.ERROR) console.error(line, record.data ?? '');
      else console.log(line, record.data ?? '');
    }
    if (this.logDir) {
      try {
        appendFileSync(join(this.logDir, `launcher-${record.time.slice(0, 10)}.log`), JSON.stringify(record) + '\n');
      } catch {
        // Logging must never take the launcher down; the console copy still exists.
      }
    }
    for (const listener of this.listeners) {
      try {
        listener(record);
      } catch {
        /* listener faults are isolated */
      }
    }
  }

  private prune(retain: number): void {
    if (!this.logDir) return;
    const dir = this.logDir;
    const files = readdirSync(dir)
      .filter((f) => /^launcher-\d{4}-\d{2}-\d{2}\.log$/.test(f))
      .map((f) => ({ f, t: statSync(join(dir, f)).mtimeMs }))
      .sort((a, b) => b.t - a.t);
    for (const { f } of files.slice(retain)) rmSync(join(dir, f), { force: true });
  }
}

export const logSink = new LogSink();

export class Logger {
  constructor(private readonly scope: string, private readonly sink: LogSink = logSink) {}

  child(scope: string): Logger {
    return new Logger(`${this.scope}/${scope}`, this.sink);
  }

  debug(message: string, data?: Record<string, unknown>): void {
    this.sink.write(LogLevel.DEBUG, this.scope, message, data);
  }
  info(message: string, data?: Record<string, unknown>): void {
    this.sink.write(LogLevel.INFO, this.scope, message, data);
  }
  warn(message: string, data?: Record<string, unknown>): void {
    this.sink.write(LogLevel.WARN, this.scope, message, data);
  }
  error(message: string, data?: Record<string, unknown>): void {
    this.sink.write(LogLevel.ERROR, this.scope, message, data);
  }
  fatal(message: string, data?: Record<string, unknown>): void {
    this.sink.write(LogLevel.FATAL, this.scope, message, data);
  }
}

export function getLogger(scope: string): Logger {
  return new Logger(scope);
}
