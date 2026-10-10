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
});
