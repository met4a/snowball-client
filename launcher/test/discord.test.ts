import { createServer, type Server, type Socket } from 'node:net';
import { platform } from 'node:os';
import { join } from 'node:path';
import { afterEach, describe, expect, it } from 'vitest';
import { DiscordRpc } from '../src/core/social/DiscordRpc.js';
import { tempDir } from './helpers.js';

const APP = '123456789012345678';
const REJECTED = '999999999999999999';

/** Stands in for the Discord app: its local pipe, its handshake, and what it was asked to show. */
class FakeDiscord {
  readonly activities: Array<Record<string, unknown> | null> = [];
  handshakes = 0;
  private server: Server | null = null;
  private readonly sockets = new Set<Socket>();

  constructor(readonly path: string) {}

  async open(): Promise<void> {
    this.server = createServer((socket) => {
      this.sockets.add(socket);
      socket.on('close', () => this.sockets.delete(socket));
      let buffer = Buffer.alloc(0);
      socket.on('data', (chunk) => {
        buffer = Buffer.concat([buffer, chunk]);
        while (buffer.length >= 8) {
          const op = buffer.readInt32LE(0);
          const length = buffer.readInt32LE(4);
          if (buffer.length < 8 + length) return;
          const payload = JSON.parse(buffer.subarray(8, 8 + length).toString('utf8'));
          buffer = buffer.subarray(8 + length);
          if (op === 0) {
            this.handshakes++;
            if (payload.client_id === REJECTED) this.send(socket, 2, { code: 4000, message: 'Invalid Client ID' });
            else this.send(socket, 1, { cmd: 'DISPATCH', evt: 'READY', data: { v: 1 } });
          } else if (op === 1 && payload.cmd === 'SET_ACTIVITY') {
            this.activities.push(payload.args.activity ?? null);
            this.send(socket, 1, { cmd: 'SET_ACTIVITY', nonce: payload.nonce, data: payload.args.activity ?? null });
          }
        }
      });
    });
    await new Promise<void>((done) => this.server!.listen(this.path, done));
  }

  /** Discord quitting: every connection dropped, the pipe gone. */
  async close(): Promise<void> {
    for (const s of this.sockets) s.destroy();
    await new Promise<void>((done) => (this.server ? this.server.close(() => done()) : done()));
    this.server = null;
  }

  private send(socket: Socket, op: number, payload: unknown): void {
    const body = Buffer.from(JSON.stringify(payload));
    const header = Buffer.alloc(8);
    header.writeInt32LE(op, 0);
    header.writeInt32LE(body.length, 4);
    socket.write(Buffer.concat([header, body]));
  }
}

let counter = 0;
function pipe(): string {
  const name = `snowball-test-discord-${process.pid}-${++counter}`;
  return platform() === 'win32' ? `\\\\?\\pipe\\${name}` : join(tempDir(), name);
}

const until = async (check: () => boolean, ms = 3000) => {
  const end = Date.now() + ms;
  while (!check()) {
    if (Date.now() > end) throw new Error('timed out');
    await new Promise((r) => setTimeout(r, 10));
  }
};

const cleanups: Array<() => Promise<void>> = [];
afterEach(async () => {
  while (cleanups.length) await cleanups.pop()!();
});

function rpc(path: string, app = APP) {
  // Only pipe 0 exists; the others are paths nothing listens on.
  const client = new DiscordRpc(app, { pipePath: (i) => (i === 0 ? path : `${path}-none-${i}`), retryMs: [50], minUpdateMs: 100 });
  cleanups.push(() => client.stop());
  return client;
}

async function discord(path: string) {
  const fake = new FakeDiscord(path);
  await fake.open();
  cleanups.push(() => fake.close());
  return fake;
}

describe('Discord Rich Presence', () => {
  it('connects when Discord is already open and shows the presence', async () => {
    const path = pipe();
    const fake = await discord(path);
    const client = rpc(path);
    client.start();
    client.setPresence({ details: 'Using Snowball Launcher', state: 'Browsing instances' });
    await until(() => fake.activities.length === 1);
    expect(client.status).toBe('connected');
    expect(fake.activities[0]).toMatchObject({ details: 'Using Snowball Launcher', state: 'Browsing instances' });
  });

  it('waits quietly while Discord is closed and connects when it opens', async () => {
    const path = pipe();
    const client = rpc(path);
    client.start();
    client.setPresence({ details: 'Using Snowball Launcher' });
    await until(() => client.status === 'unavailable');
    const fake = await discord(path);
    await until(() => fake.activities.length === 1);
    expect(client.status).toBe('connected');
  });

  it('reconnects after Discord restarts and shows the presence again', async () => {
    const path = pipe();
    let fake = await discord(path);
    const client = rpc(path);
    client.start();
    client.setPresence({ details: 'Playing Minecraft' });
    await until(() => fake.activities.length === 1);
    await fake.close();
    await until(() => client.status !== 'connected');
    fake = await discord(path);
    await until(() => fake.activities.length === 1);
    expect(fake.activities[0]).toMatchObject({ details: 'Playing Minecraft' });
    expect(fake.handshakes).toBe(1);
  });

  it('never sends the same presence twice, and keeps to Discord\'s rate limit', async () => {
    const path = pipe();
    const fake = await discord(path);
    const client = rpc(path);
    client.start();
    client.setPresence({ details: 'Using Snowball Launcher' });
    await until(() => fake.activities.length === 1);
    client.setPresence({ details: 'Using Snowball Launcher' });
    // Three changes in a burst: the last one is what is shown, once, after the wait.
    client.setPresence({ details: 'Preparing Minecraft' });
    client.setPresence({ details: 'Launching Minecraft' });
    client.setPresence({ details: 'Playing Minecraft' });
    await until(() => fake.activities.length === 2);
    await new Promise((r) => setTimeout(r, 250));
    expect(fake.activities.map((a) => a?.details)).toEqual(['Using Snowball Launcher', 'Playing Minecraft']);
  });

  it('stops trying when Discord rejects the application id', async () => {
    const path = pipe();
    const fake = await discord(path);
    const client = rpc(path, REJECTED);
    client.start();
    await until(() => client.status === 'rejected');
    await new Promise((r) => setTimeout(r, 300));
    expect(fake.handshakes).toBe(1);
  });

  it('clears the presence when it stops', async () => {
    const path = pipe();
    const fake = await discord(path);
    const client = rpc(path);
    client.start();
    client.setPresence({ details: 'Using Snowball Launcher' });
    await until(() => fake.activities.length === 1);
    await client.stop();
    await until(() => fake.activities.length === 2);
    expect(fake.activities[1]).toBeNull();
    expect(client.status).toBe('off');
  });

  it('does nothing without an application id', () => {
    const client = new DiscordRpc('');
    client.start();
    expect(client.configured).toBe(false);
    expect(client.status).toBe('off');
  });
});
