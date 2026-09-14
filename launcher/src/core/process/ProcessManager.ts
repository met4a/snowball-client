import { spawn, type ChildProcess } from 'node:child_process';
import { EventEmitter } from 'node:events';
import { existsSync } from 'node:fs';
import { readdir, stat } from 'node:fs/promises';
import { platform } from 'node:os';
import { join } from 'node:path';
import { getLogger, redactText } from '../logging/Logger.js';

const log = getLogger('process');
const MAX_BUFFERED_LINES = 5000;
const CRASH_MARKERS = [/---- Minecraft Crash Report ----/, /#@!@# Game crashed!/, /A fatal error has been detected by the Java Runtime/];

export interface GameLogLine {
  instanceId: string;
  stream: 'stdout' | 'stderr';
  line: string;
  time: number;
}

export interface GameExit {
  instanceId: string;
  code: number | null;
  signal: NodeJS.Signals | null;
  crashed: boolean;
  killedByUser: boolean;
  crashReport: string | null;
  durationMs: number;
}

interface RunningGame {
  child: ChildProcess;
  startedAt: number;
  gameDir: string;
  killedByUser: boolean;
  sawCrashMarker: boolean;
  lines: GameLogLine[];
}

export type Spawner = typeof spawn;

/**
 * Starts and supervises game processes: one per instance, stdout/stderr captured line by line,
 * crash detection from exit code, log markers and new crash reports, and tree-kill on stop.
 */
export class ProcessManager extends EventEmitter {
  private readonly running = new Map<string, RunningGame>();

  constructor(private readonly spawner: Spawner = spawn) {
    super();
  }

  isRunning(instanceId: string): boolean {
    return this.running.has(instanceId);
  }

  runningIds(): string[] {
    return [...this.running.keys()];
  }

  recentLines(instanceId: string): GameLogLine[] {
    return [...(this.running.get(instanceId)?.lines ?? [])];
  }

  launch(instanceId: string, javaPath: string, args: string[], gameDir: string): void {
    if (this.running.has(instanceId)) throw new Error('This instance is already running.');
    log.info(`Launching instance ${instanceId}`, { java: javaPath, args: args.map(redactText).join(' ') });
    const child = this.spawner(javaPath, args, { cwd: gameDir, windowsHide: true, stdio: ['ignore', 'pipe', 'pipe'], shell: false, env: { ...process.env } });
    const game: RunningGame = { child, startedAt: Date.now(), gameDir, killedByUser: false, sawCrashMarker: false, lines: [] };
    this.running.set(instanceId, game);

    const attach = (stream: NodeJS.ReadableStream | null, name: 'stdout' | 'stderr') => {
      if (!stream) return;
      let buffer = '';
      stream.setEncoding('utf8');
      stream.on('data', (chunk: string) => {
        buffer += chunk;
        let nl: number;
        while ((nl = buffer.indexOf('\n')) >= 0) {
          this.pushLine(instanceId, game, name, buffer.slice(0, nl).replace(/\r$/, ''));
          buffer = buffer.slice(nl + 1);
        }
      });
      stream.on('end', () => {
        if (buffer) this.pushLine(instanceId, game, name, buffer);
      });
    };
    attach(child.stdout, 'stdout');
    attach(child.stderr, 'stderr');

    child.on('error', (err) => {
      log.error(`Failed to start instance ${instanceId}`, { error: err.message });
      this.pushLine(instanceId, game, 'stderr', `Launcher: could not start Java (${err.message})`);
    });
    child.on('close', (code, signal) => void this.finish(instanceId, game, code, signal));
    this.emit('started', { instanceId, pid: child.pid });
  }

  private pushLine(instanceId: string, game: RunningGame, stream: 'stdout' | 'stderr', raw: string): void {
    const line = redactText(raw);
    if (CRASH_MARKERS.some((m) => m.test(line))) game.sawCrashMarker = true;
    const entry: GameLogLine = { instanceId, stream, line, time: Date.now() };
    game.lines.push(entry);
    if (game.lines.length > MAX_BUFFERED_LINES) game.lines.splice(0, game.lines.length - MAX_BUFFERED_LINES);
    this.emit('log', entry);
  }

  private async finish(instanceId: string, game: RunningGame, code: number | null, signal: NodeJS.Signals | null): Promise<void> {
    this.running.delete(instanceId);
    const crashReport = await newestCrashReport(game.gameDir, game.startedAt);
    const crashed = !game.killedByUser && (game.sawCrashMarker || crashReport !== null || (code !== 0 && code !== null));
    const exit: GameExit = { instanceId, code, signal, crashed, killedByUser: game.killedByUser, crashReport, durationMs: Date.now() - game.startedAt };
    if (crashed) log.error(`Instance ${instanceId} crashed`, { code, crashReport });
    else log.info(`Instance ${instanceId} exited`, { code });
    this.emit('exit', exit);
  }

  /** Stops the game and any child processes it started. */
  kill(instanceId: string): boolean {
    const game = this.running.get(instanceId);
    if (!game || game.child.pid === undefined) return false;
    game.killedByUser = true;
    if (platform() === 'win32') {
      this.spawner('taskkill', ['/PID', String(game.child.pid), '/T', '/F'], { windowsHide: true, stdio: 'ignore', shell: false });
    } else {
      game.child.kill('SIGTERM');
      setTimeout(() => {
        if (this.running.get(instanceId) === game) game.child.kill('SIGKILL');
      }, 10_000).unref();
    }
    return true;
  }
}

async function newestCrashReport(gameDir: string, since: number): Promise<string | null> {
  const dir = join(gameDir, 'crash-reports');
  if (!existsSync(dir)) return null;
  let newest: { path: string; time: number } | null = null;
  for (const name of await readdir(dir)) {
    if (!name.endsWith('.txt')) continue;
    const path = join(dir, name);
    const time = (await stat(path)).mtimeMs;
    if (time >= since && (!newest || time > newest.time)) newest = { path, time };
  }
  return newest?.path ?? null;
}
