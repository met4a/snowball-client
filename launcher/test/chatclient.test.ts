import { afterEach, describe, expect, it, vi } from 'vitest';
import { ChatClient, type ChatState } from '../src/core/social/ChatClient.js';

/** A socket that opens straight away and never hears from a room. */
class OpeningSocket extends EventTarget {
  constructor(readonly url: string) {
    super();
    queueMicrotask(() => this.dispatchEvent(new Event('open')));
  }
  send(): void {}
  close(): void {}
}

afterEach(() => vi.unstubAllGlobals());

describe('chat client', () => {
  it('counts whoever just joined as online before the room sends its count', async () => {
    vi.stubGlobal('WebSocket', OpeningSocket);
    const chat = new ChatClient('https://chat.invalid', '');
    (chat as unknown as { proveIdentity: () => Promise<string> }).proveIdentity = async () => 'ticket';
    const shown: string[] = [];
    chat.on('state', (s: ChatState) => shown.push(s.status === 'online' ? `${s.online} online` : s.status));
    await chat.connect({ name: 'Alex', uuid: '0'.repeat(32), accessToken: 'token', type: 'msa' });
    await new Promise((r) => setTimeout(r, 0));
    // It said "0 online" for a moment after joining.
    expect(shown).toEqual(['connecting', '1 online']);
  });

  it('passes cape designs waiting for staff to the admin panel, and gates deciding them', async () => {
    let room: OpeningSocket | null = null;
    const sent: string[] = [];
    class RoomSocket extends OpeningSocket {
      constructor(url: string) {
        super(url);
        room = this;
      }
      override send(data: string): void {
        sent.push(data);
      }
    }
    vi.stubGlobal('WebSocket', RoomSocket);
    const chat = new ChatClient('https://chat.invalid', '');
    (chat as unknown as { proveIdentity: () => Promise<string> }).proveIdentity = async () => 'ticket';
    const capes: unknown[] = [];
    chat.on('capes', (list: unknown[]) => capes.push(...list));
    await chat.connect({ name: 'Alex', uuid: '0'.repeat(32), accessToken: 'token', type: 'msa' });
    await new Promise((r) => setTimeout(r, 0));

    // A plain rank cannot decide capes; staff can.
    expect(chat.admin('cape-review', { uuid: 'a'.repeat(32), approve: true }).ok).toBe(false);
    const design = { uuid: 'a'.repeat(32), name: 'Plus', pixels: '7fcbff'.repeat(160), at: 1, replaces: false };
    room!.dispatchEvent(new MessageEvent('message', { data: JSON.stringify({ type: 'you', rank: 'staff', permissions: ['capes.review'], owner: false, flags: {} }) }));
    room!.dispatchEvent(new MessageEvent('message', { data: JSON.stringify({ type: 'capes', capes: [design] }) }));
    expect(capes).toEqual([design]);
    expect(chat.admin('cape-review', { uuid: 'a'.repeat(32), approve: true }).ok).toBe(true);
    expect(JSON.parse(sent.at(-1)!)).toMatchObject({ type: 'admin', action: 'cape-review', approve: true });
  });

  it('hands the server\'s answer to an admin action to the panel, not to chat', async () => {
    let room: OpeningSocket | null = null;
    class RoomSocket extends OpeningSocket {
      constructor(url: string) {
        super(url);
        room = this;
      }
    }
    vi.stubGlobal('WebSocket', RoomSocket);
    const chat = new ChatClient('https://chat.invalid', '');
    (chat as unknown as { proveIdentity: () => Promise<string> }).proveIdentity = async () => 'ticket';
    const results: unknown[] = [];
    const chatLines: unknown[] = [];
    const ranked: unknown[] = [];
    chat.on('admin-result', (r: unknown) => results.push(r));
    chat.on('message', (m: unknown) => chatLines.push(m));
    chat.on('ranked', (people: unknown[]) => ranked.push(...people));
    await chat.connect({ name: 'Alex', uuid: '0'.repeat(32), accessToken: 'token', type: 'msa' });
    await new Promise((r) => setTimeout(r, 0));

    room!.dispatchEvent(new MessageEvent('message', { data: JSON.stringify({ type: 'admin-result', action: 'rank-set', ok: false, message: 'There is no rank called wizard.' }) }));
    expect(results).toEqual([{ action: 'rank-set', ok: false, message: 'There is no rank called wizard.' }]);
    expect(chatLines).toEqual([]);

    const person = { uuid: 'a'.repeat(32), name: 'Alex', rank: 'bug_hunter', at: 1, byName: 'Met4a' };
    room!.dispatchEvent(new MessageEvent('message', { data: JSON.stringify({ type: 'ranked', people: [person] }) }));
    expect(ranked).toEqual([person]);
    // Seeing who holds a rank needs users.view, like the people list.
    expect(chat.admin('ranked').ok).toBe(false);
  });
});
