import { EventEmitter } from 'node:events';
import { connect, type Socket } from 'node:net';
import { platform } from 'node:os';
import { join } from 'node:path';
import { getLogger } from '../logging/Logger.js';

const log = getLogger('discord');

/** What Discord shows under "Playing Snowball Client". Nothing private: no paths, no addresses. */
export interface Presence {
  details: string;
  state?: string;
  /** Seconds since 1970; Discord counts up from it. */
  startTimestamp?: number;
  largeImageKey?: string;
  largeImageText?: string;
}

export type DiscordStatus = 'off' | 'connecting' | 'connected' | 'unavailable' | 'rejected';

export interface DiscordRpcOptions {
  /** Where Discord's local pipe number i lives; replaceable so tests can stand in for Discord. */
  pipePath?: (index: number) => string;
  /** Waits between attempts while Discord is not running, growing to the last value. */
  retryMs?: number[];
  /** Discord accepts about five presence changes in twenty seconds; changes closer than this wait. */
  minUpdateMs?: number;
}

const OP_HANDSHAKE = 0;
const OP_FRAME = 1;
const OP_CLOSE = 2;
/** Discord numbers its pipes when more than one Discord (stable, PTB, Canary) is running. */
const PIPES = 10;
/** Close code Discord sends for an application id it does not know. */
const INVALID_CLIENT_ID = 4000;

/** Where Discord listens on this computer. */
export function discordPipePath(index: number): string {
  if (platform() === 'win32') return `\\\\?\\pipe\\discord-ipc-${index}`;
  const dir = process.env.XDG_RUNTIME_DIR || process.env.TMPDIR || process.env.TMP || process.env.TEMP || '/tmp';
  return join(dir, `discord-ipc-${index}`);
}

/**
 * Discord Rich Presence over Discord's local pipe, the protocol Discord's own libraries speak.
 *
 * It runs in the main process and never waits on the window: it connects when Discord is there,
 * keeps trying quietly when it is not, reconnects after Discord restarts, and only sends a presence
 * when it has changed - at most as often as Discord allows. Every state change is logged once.
 */
export class DiscordRpc extends EventEmitter {
  private readonly pipePath: (index: number) => string;
  private readonly retryMs: number[];
  private readonly minUpdateMs: number;
  private socket: Socket | null = null;
  private buffer = Buffer.alloc(0);
  private statusValue: DiscordStatus = 'off';
  private running = false;
  private attempt = 0;
  private retryTimer: NodeJS.Timeout | null = null;
  private flushTimer: NodeJS.Timeout | null = null;
  private wanted: Presence | null = null;
  /** What Discord was last told, as JSON, so an unchanged presence is never sent twice. */
  private sent: string | undefined;
  private lastSentAt = 0;
  private nonce = 0;

  constructor(private readonly appId: string, options: DiscordRpcOptions = {}) {
    super();
    this.pipePath = options.pipePath ?? discordPipePath;
    this.retryMs = options.retryMs ?? [5_000, 15_000, 30_000, 60_000];
    this.minUpdateMs = options.minUpdateMs ?? 4_000;
  }

  get status(): DiscordStatus {
    return this.statusValue;
  }

  /** Whether this build has a Discord application to show a presence under at all. */
  get configured(): boolean {
    return /^\d{15,25}$/.test(this.appId);
  }

  start(): void {
    if (this.running) return;
    if (!this.configured) {
      log.info('[Discord RPC] Not set up: this build has no Discord application id');
      return;
    }
    this.running = true;
    void this.connect();
  }

  /** The presence to show from now on; null clears it. Cheap to call as often as state changes. */
  setPresence(presence: Presence | null): void {
    this.wanted = presence;
    this.scheduleFlush();
  }

  /** Clears the presence and closes the connection, e.g. when the launcher quits. */
  async stop(): Promise<void> {
    this.running = false;
    this.clearTimers();
    const socket = this.socket;
    if (socket && this.statusValue === 'connected') {
      await new Promise<void>((done) => {
        this.write(OP_FRAME, { cmd: 'SET_ACTIVITY', args: { pid: process.pid }, nonce: String(++this.nonce) });
        socket.end(() => done());
        setTimeout(done, 500).unref();
      });
    }
    this.dropSocket();
    this.setStatus('off');
  }

  private setStatus(status: DiscordStatus): void {
    if (this.statusValue === status) return;
    this.statusValue = status;
    const lines: Record<DiscordStatus, string> = {
      off: '[Discord RPC] Stopped',
      connecting: '[Discord RPC] Connecting...',
      connected: '[Discord RPC] Connected',
      unavailable: '[Discord RPC] Discord unavailable; will keep trying quietly',
      rejected: '[Discord RPC] Discord rejected the application id; not trying again until restart',
    };
    log.info(lines[status]);
    this.emit('status', status);
  }

  private async connect(): Promise<void> {
    if (!this.running) return;
    if (this.attempt > 0 && this.statusValue !== 'unavailable') log.info('[Discord RPC] Reconnecting...');
    this.setStatus(this.statusValue === 'unavailable' ? 'unavailable' : 'connecting');
    for (let index = 0; index < PIPES && this.running; index++) {
      const socket = await this.open(this.pipePath(index));
      if (!socket) continue;
      this.adopt(socket);
      return;
    }
    this.setStatus('unavailable');
    this.scheduleRetry();
  }

  private open(path: string): Promise<Socket | null> {
    return new Promise((resolve) => {
      const socket = connect(path);
      const fail = () => {
        socket.destroy();
        resolve(null);
      };
      socket.once('error', fail);
      socket.once('connect', () => {
        socket.off('error', fail);
        resolve(socket);
      });
    });
  }

  private adopt(socket: Socket): void {
    this.socket = socket;
    this.buffer = Buffer.alloc(0);
    socket.on('data', (chunk) => this.receive(Buffer.isBuffer(chunk) ? chunk : Buffer.from(chunk)));
    socket.on('error', () => undefined);
    socket.on('close', () => {
      if (this.socket !== socket) return;
      this.dropSocket();
      if (!this.running || this.statusValue === 'rejected') return;
      // Discord closed or restarted: start again, the presence is sent again once it is back.
      this.sent = undefined;
      this.setStatus('connecting');
      this.scheduleRetry();
    });
    this.write(OP_HANDSHAKE, { v: 1, client_id: this.appId });
  }

  private receive(chunk: Buffer): void {
    this.buffer = Buffer.concat([this.buffer, chunk]);
    while (this.buffer.length >= 8) {
      const op = this.buffer.readInt32LE(0);
      const length = this.buffer.readInt32LE(4);
      if (length < 0 || length > 1 << 20) {
        this.socket?.destroy();
        return;
      }
      if (this.buffer.length < 8 + length) return;
      const body = this.buffer.subarray(8, 8 + length).toString('utf8');
      this.buffer = this.buffer.subarray(8 + length);
      let payload: Record<string, unknown> = {};
      try {
        payload = JSON.parse(body) as Record<string, unknown>;
      } catch {
        // A frame that is not JSON carries nothing to act on.
      }
      this.handle(op, payload);
    }
  }

  private handle(op: number, payload: Record<string, unknown>): void {
    if (op === OP_CLOSE) {
      if (payload.code === INVALID_CLIENT_ID) {
        this.running = false;
        this.clearTimers();
        this.setStatus('rejected');
      }
      this.socket?.destroy();
      return;
    }
    if (op !== OP_FRAME) return;
    if (payload.evt === 'READY') {
      this.attempt = 0;
      this.setStatus('connected');
      this.sent = undefined;
      this.scheduleFlush(true);
    } else if (payload.evt === 'ERROR') {
      const data = payload.data as { message?: unknown } | undefined;
      log.warn('[Discord RPC] Discord refused the presence', { message: String(data?.message ?? '') });
    }
  }

  private scheduleRetry(): void {
    if (!this.running || this.retryTimer) return;
    const wait = this.retryMs[Math.min(this.attempt, this.retryMs.length - 1)];
    this.attempt++;
    this.retryTimer = setTimeout(() => {
      this.retryTimer = null;
      void this.connect();
    }, wait);
    this.retryTimer.unref();
  }

  private scheduleFlush(now = false): void {
    if (this.statusValue !== 'connected' || this.flushTimer) return;
    const wait = now ? 0 : Math.max(0, this.lastSentAt + this.minUpdateMs - Date.now());
    this.flushTimer = setTimeout(() => {
      this.flushTimer = null;
      this.flush();
    }, wait);
    this.flushTimer.unref();
  }

  private flush(): void {
    if (this.statusValue !== 'connected') return;
    const json = JSON.stringify(this.wanted);
    if (json === this.sent) return;
    const p = this.wanted;
    const activity = p
      ? {
          details: p.details.slice(0, 128),
          ...(p.state ? { state: p.state.slice(0, 128) } : {}),
          ...(p.startTimestamp ? { timestamps: { start: p.startTimestamp } } : {}),
          ...(p.largeImageKey ? { assets: { large_image: p.largeImageKey, ...(p.largeImageText ? { large_text: p.largeImageText.slice(0, 128) } : {}) } } : {}),
        }
      : undefined;
    this.write(OP_FRAME, { cmd: 'SET_ACTIVITY', args: { pid: process.pid, ...(activity ? { activity } : {}) }, nonce: String(++this.nonce) });
    this.sent = json;
    this.lastSentAt = Date.now();
    log.info(`[Discord RPC] Activity updated: ${p ? p.details : 'cleared'}`);
  }

  private write(op: number, payload: unknown): void {
    const body = Buffer.from(JSON.stringify(payload), 'utf8');
    const header = Buffer.alloc(8);
    header.writeInt32LE(op, 0);
    header.writeInt32LE(body.length, 4);
    this.socket?.write(Buffer.concat([header, body]));
  }

  private dropSocket(): void {
    const socket = this.socket;
    this.socket = null;
    if (socket && !socket.destroyed) socket.destroy();
  }

  private clearTimers(): void {
    if (this.retryTimer) clearTimeout(this.retryTimer);
    if (this.flushTimer) clearTimeout(this.flushTimer);
    this.retryTimer = null;
    this.flushTimer = null;
  }
}
