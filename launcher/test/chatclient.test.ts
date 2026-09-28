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
});
