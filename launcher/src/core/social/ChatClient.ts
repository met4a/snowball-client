import { EventEmitter } from 'node:events';
import { createHash } from 'node:crypto';
import { getLogger } from '../logging/Logger.js';
import { moderate, RateLimit } from './moderation.js';

const log = getLogger('chat');

export interface ChatMessage {
  id: string;
  name: string;
  uuid: string;
  text: string;
  at: string;
  /** Set on messages from the Snowball team. */
  staff?: boolean;
  /** Set on the launcher's own notes, e.g. "you are muted". */
  system?: boolean;
}

export type SnowballTier = 'snowball' | 'plus';

export interface ChatState {
  /** Off when no chat server is configured in this build. */
  configured: boolean;
  status: 'offline' | 'connecting' | 'online' | 'error';
  online: number;
  announcement: string | null;
  admin: boolean;
  /** The edition the backend says this account has. Never decided here. */
  tier: SnowballTier;
  message?: string;
}

interface SessionLike {
  name: string;
  uuid: string;
  accessToken: string;
  type: 'msa' | 'offline';
}

/**
 * The launcher's side of Snowball's global chat. Identity is proven the way a Minecraft server does
 * it: the launcher asks Mojang to vouch for the account, and the chat server checks that with Mojang
 * itself - so the access token never leaves this computer and nobody can claim another player's name.
 */
export class ChatClient extends EventEmitter {
  private socket: WebSocket | null = null;
  private readonly limit = new RateLimit();
  private state: ChatState;
  private closedByUs = false;
  private retryMs = 3000;

  constructor(private readonly baseUrl: string, private readonly adminUuid: string) {
    super();
    this.state = { configured: Boolean(baseUrl), status: 'offline', online: 0, announcement: null, admin: false, tier: 'snowball' };
  }

  current(): ChatState {
    return this.state;
  }

  /** Connects (or reconnects) as this account. Offline profiles cannot be vouched for, so they cannot chat. */
  async connect(session: SessionLike): Promise<ChatState> {
    if (!this.state.configured) return this.state;
    if (session.type !== 'msa') return this.set({ status: 'error', message: 'Global chat needs a Microsoft account, so that names cannot be faked.' });
    if (this.socket && this.state.status === 'online') return this.state;
    this.closedByUs = false;
    this.set({ status: 'connecting', message: undefined });
    try {
      const serverId = await this.proveIdentity(session);
      const url = new URL(this.baseUrl.replace(/^http/, 'ws'));
      url.pathname = `${url.pathname.replace(/\/$/, '')}/ws`;
      url.searchParams.set('name', session.name);
      url.searchParams.set('uuid', session.uuid);
      url.searchParams.set('serverId', serverId);
      this.open(url.toString(), session);
      return this.state;
    } catch (err) {
      const message = err instanceof Error ? err.message : String(err);
      log.warn('Could not join chat', { error: message });
      return this.set({ status: 'error', message });
    }
  }

  disconnect(): void {
    this.closedByUs = true;
    this.socket?.close();
    this.socket = null;
    this.set({ status: 'offline', online: 0 });
  }

  /** Sends a message after the same checks the server makes. */
  send(text: string): { ok: boolean; reason?: string } {
    const check = moderate(text);
    if (!check.ok) return { ok: false, reason: check.reason };
    const wait = this.limit.check();
    if (wait !== null) return { ok: false, reason: `Slow down a moment (${Math.ceil(wait / 1000)}s).` };
    if (!this.socket || this.state.status !== 'online') return { ok: false, reason: 'You are not connected to chat.' };
    this.socket.send(JSON.stringify({ type: 'msg', text: check.text }));
    return { ok: true };
  }

  /** Admin only, and the server checks that too: sets or clears the banner everyone sees. */
  announce(text: string | null): { ok: boolean; reason?: string } {
    if (!this.socket || this.state.status !== 'online') return { ok: false, reason: 'You are not connected to chat.' };
    if (!this.state.admin) return { ok: false, reason: 'Only the Snowball account can post announcements.' };
    this.socket.send(JSON.stringify({ type: 'announce', text: text && text.trim() ? text.trim().slice(0, 300) : null }));
    return { ok: true };
  }

  /** Admin only: give or take away Snowball+ for an account. The server decides whether you may. */
  setPlus(uuid: string, on: boolean): { ok: boolean; reason?: string } {
    if (!this.socket || this.state.status !== 'online') return { ok: false, reason: 'You are not connected to chat.' };
    if (!this.state.admin) return { ok: false, reason: 'Only the Snowball owner can change editions.' };
    this.socket.send(JSON.stringify({ type: 'admin', action: on ? 'grant' : 'ungrant', uuid }));
    return { ok: true };
  }

  /** Admin only: mute a player for a while, or clear the chat for everyone. */
  moderateChat(action: 'mute' | 'unmute' | 'clear', uuid?: string, minutes?: number): { ok: boolean; reason?: string } {
    if (!this.socket || this.state.status !== 'online') return { ok: false, reason: 'You are not connected to chat.' };
    if (!this.state.admin) return { ok: false, reason: 'Only the Snowball account can moderate chat.' };
    this.socket.send(JSON.stringify({ type: 'admin', action, uuid, minutes }));
    return { ok: true };
  }

  /**
   * Asks Mojang to vouch for this account against a one-time id from the chat server - the same
   * handshake a Minecraft server uses. Only the id travels; the token stays here.
   */
  private async proveIdentity(session: SessionLike): Promise<string> {
    const nonce = await fetch(`${this.baseUrl.replace(/\/$/, '')}/nonce`, { method: 'GET' });
    if (!nonce.ok) throw new Error(`The chat server did not answer (${nonce.status}).`);
    const { serverId } = (await nonce.json()) as { serverId?: string };
    if (!serverId || !/^[a-zA-Z0-9-]{8,64}$/.test(serverId)) throw new Error('The chat server sent something unexpected.');
    const hash = createHash('sha1').update(serverId, 'utf8').digest('hex');
    const join = await fetch('https://sessionserver.mojang.com/session/minecraft/join', {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ accessToken: session.accessToken, selectedProfile: session.uuid.replace(/-/g, ''), serverId: hash }),
    });
    if (join.status !== 204) throw new Error('Minecraft would not vouch for this account. Sign in again and retry.');
    return hash;
  }

  private open(url: string, session: SessionLike): void {
    const socket = new WebSocket(url);
    this.socket = socket;
    socket.addEventListener('open', () => {
      this.retryMs = 3000;
      this.set({ status: 'online', admin: session.uuid.replace(/-/g, '') === this.adminUuid.replace(/-/g, ''), message: undefined });
    });
    socket.addEventListener('message', (event: MessageEvent) => this.receive(String(event.data)));
    socket.addEventListener('error', () => this.set({ status: 'error', message: 'Lost the connection to chat.' }));
    socket.addEventListener('close', () => {
      this.socket = null;
      if (this.closedByUs) return;
      this.set({ status: 'offline', online: 0 });
      // Come back quietly: chat is never the reason the launcher looks busy.
      setTimeout(() => void this.connect(session), this.retryMs);
      this.retryMs = Math.min(60_000, this.retryMs * 2);
    });
  }

  private receive(data: string): void {
    let payload: Record<string, any>;
    try {
      payload = JSON.parse(data) as Record<string, any>;
    } catch {
      return;
    }
    switch (payload.type) {
      case 'msg': {
        const message: ChatMessage = {
          id: String(payload.id ?? ''),
          name: String(payload.name ?? '?'),
          uuid: String(payload.uuid ?? ''),
          text: String(payload.text ?? ''),
          at: String(payload.at ?? new Date().toISOString()),
          staff: Boolean(payload.staff),
        };
        this.emit('message', message);
        break;
      }
      case 'history':
        if (Array.isArray(payload.messages)) this.emit('history', payload.messages as ChatMessage[]);
        break;
      case 'you':
        this.set({ tier: payload.tier === 'plus' ? 'plus' : 'snowball', admin: Boolean(payload.owner) });
        this.emit('tier', this.state.tier);
        break;
      case 'presence':
        this.set({ online: Number(payload.online ?? 0) });
        break;
      case 'announce':
        this.set({ announcement: typeof payload.text === 'string' && payload.text ? payload.text : null });
        break;
      case 'error':
        this.emit('message', { id: '', name: 'Snowball', uuid: '', text: String(payload.message ?? 'Something went wrong.'), at: new Date().toISOString(), system: true });
        break;
      default:
        break;
    }
  }

  private set(patch: Partial<ChatState>): ChatState {
    this.state = { ...this.state, ...patch };
    this.emit('state', this.state);
    return this.state;
  }
}
