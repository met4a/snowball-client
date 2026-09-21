import { EventEmitter } from 'node:events';
import { createSign } from 'node:crypto';
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

/** The ranks the backend can hand out, from least to most. */
export const RANKS = ['snowball', 'plus', 'tester', 'bug_hunter', 'partner', 'staff', 'developer', 'owner'] as const;
export type SnowballRank = (typeof RANKS)[number];

export interface SnowballStats {
  online: number;
  today: number;
  week: number;
  total: number;
  /** The banner everyone sees. Served here too, so it shows before anyone joins chat. */
  announcement?: string | null;
}

export interface BugReport {
  id: string;
  title: string;
  detail: string;
  steps?: string;
  minecraft?: string;
  snowball?: string;
  loader?: string;
  by: string;
  uuid: string;
  at: string;
  status: 'open' | 'investigating' | 'fixed' | 'duplicate' | 'invalid';
}

export interface SnowballPerson {
  uuid: string;
  name: string;
  tier: 'snowball' | 'plus';
  first: number;
  last: number;
  muted: boolean;
}

export interface ChatState {
  /** Off when no chat server is configured in this build. */
  configured: boolean;
  status: 'offline' | 'connecting' | 'online' | 'error';
  online: number;
  announcement: string | null;
  admin: boolean;
  /** The rank the backend says this account holds. Never decided here. */
  rank: SnowballRank;
  /** What that rank is allowed to do, as the backend spelled it out. */
  permissions: string[];
  /** Features the owner has switched on or off for everyone. */
  flags: Record<string, boolean>;
  message?: string;
}

interface SessionLike {
  name: string;
  uuid: string;
  accessToken: string;
  type: 'msa' | 'offline';
}


/** Turns Mojang's refusal into a sentence that says what to do about it. */
function describeJoinFailure(status: number, body: string): string {
  const text = body.toLowerCase();
  if (text.includes('insufficientprivileges') || text.includes('multiplayer')) {
    return 'This Microsoft account is not allowed to play multiplayer, so Minecraft will not vouch for it. Turn multiplayer on in the Xbox privacy settings for the account and try again.';
  }
  if (text.includes('userbanned')) return 'This account is banned from Minecraft multiplayer, so it cannot join Snowball chat.';
  if (status === 401 || text.includes('invalid token') || text.includes('forbiddenoperation')) {
    return 'Minecraft did not accept the sign-in. Sign out of the account in Settings and sign in again.';
  }
  return `Minecraft would not vouch for this account (${status}). Sign in again and retry.`;
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
  /** A connect already in flight. Without this, two callers open two sockets. */
  private connecting: Promise<ChatState> | null = null;
  /** The pending automatic reconnect, so joining by hand can cancel it. */
  private retryTimer: ReturnType<typeof setTimeout> | null = null;

  constructor(private readonly baseUrl: string, private readonly adminUuid: string) {
    super();
    this.state = { configured: Boolean(baseUrl), status: 'offline', online: 0, announcement: null, admin: false, rank: 'snowball', permissions: [], flags: {} };
  }

  current(): ChatState {
    return this.state;
  }

  /** Connects (or reconnects) as this account. Offline profiles cannot be vouched for, so they cannot chat. */
  async connect(session: SessionLike): Promise<ChatState> {
    if (!this.state.configured) return this.state;
    if (session.type !== 'msa') return this.set({ status: 'error', message: 'Global chat needs a Microsoft account, so that names cannot be faked.' });
    if (this.socket && this.state.status === 'online') return this.state;
    // Joining by hand while a reconnect is pending, or twice in quick succession, used to open a
    // second socket and deliver every message twice. One attempt at a time, always.
    if (this.connecting) return this.connecting;
    this.connecting = this.runConnect(session).finally(() => {
      this.connecting = null;
    });
    return this.connecting;
  }

  private async runConnect(session: SessionLike): Promise<ChatState> {
    this.clearRetry();
    this.closedByUs = false;
    this.set({ status: 'connecting', message: undefined });
    try {
      const ticket = await this.proveIdentity(session);
      const url = new URL(this.baseUrl.replace(/^http/, 'ws'));
      url.pathname = `${url.pathname.replace(/\/$/, '')}/ws`;
      url.searchParams.set('ticket', ticket);
      this.open(url.toString(), session);
      return this.state;
    } catch (err) {
      const message = err instanceof Error ? err.message : String(err);
      log.warn('Could not join chat', { error: message });
      return this.set({ status: 'error', message });
    }
  }

  private clearRetry(): void {
    if (this.retryTimer) clearTimeout(this.retryTimer);
    this.retryTimer = null;
  }

  disconnect(): void {
    this.closedByUs = true;
    this.clearRetry();
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
    if (!this.can('chat.announce')) return { ok: false, reason: 'Your rank cannot post announcements.' };
    this.socket.send(JSON.stringify({ type: 'announce', text: text && text.trim() ? text.trim().slice(0, 300) : null }));
    return { ok: true };
  }

  /**
   * How many people are using Snowball. Public, so the launcher can show it before anyone signs in;
   * the numbers come from the server's own record of who connected, never from this computer.
   */
  async stats(): Promise<SnowballStats | null> {
    if (!this.state.configured) return null;
    try {
      const response = await fetch(`${this.baseUrl.replace(/\/$/, '')}/stats`);
      if (!response.ok) return null;
      const body = (await response.json()) as Partial<SnowballStats>;
      return {
        online: Number(body.online) || 0,
        today: Number(body.today) || 0,
        week: Number(body.week) || 0,
        total: Number(body.total) || 0,
        // Carried here so the announcement can be shown without joining chat first.
        announcement: typeof body.announcement === 'string' && body.announcement ? body.announcement : null,
      };
    } catch {
      return null;
    }
  }

  /** Files a bug report under the signed-in account; the server ignores any name sent with it. */
  reportBug(report: Record<string, string>): { ok: boolean; reason?: string } {
    if (!this.socket || this.state.status !== 'online') return { ok: false, reason: 'Join chat first so the report can be signed with your account.' };
    if (!report.title?.trim() || !report.detail?.trim()) return { ok: false, reason: 'A title and a description are needed.' };
    this.socket.send(JSON.stringify({ type: 'bug', ...report }));
    return { ok: true };
  }

  /**
   * Turns a Minecraft name into an account id. This runs in the launcher because Mojang refuses
   * requests from the chat server's host, then asks the server what it knows about that account.
   */
  async lookup(name: string): Promise<{ ok: boolean; reason?: string }> {
    const wanted = name.trim();
    if (!/^[A-Za-z0-9_]{3,16}$/.test(wanted)) return { ok: false, reason: 'That is not a Minecraft name.' };
    try {
      const response = await fetch(`https://api.mojang.com/users/profiles/minecraft/${encodeURIComponent(wanted)}`);
      if (response.status !== 200) {
        this.emit('lookup', { found: false, name: wanted });
        return { ok: true };
      }
      const profile = (await response.json()) as { id?: string; name?: string };
      return this.admin('lookup', { uuid: String(profile.id ?? ''), name: profile.name ?? wanted });
    } catch (err) {
      return { ok: false, reason: err instanceof Error ? err.message : String(err) };
    }
  }

  /**
   * The permission each admin action needs. It mirrors the map the chat server checks, so the
   * launcher refuses what would be refused anyway instead of sending it and showing an error.
   * The server remains the authority; this is only there to keep the panel honest.
   */
  private static readonly ADMIN_NEEDS: Record<string, string> = {
    people: 'users.view',
    lookup: 'users.view',
    history: 'users.view',
    bugs: 'bugs.triage',
    'bug-status': 'bugs.triage',
    flag: 'flags.manage',
    clear: 'chat.moderate',
    mute: 'chat.moderate',
    unmute: 'chat.moderate',
    'rank-set': 'ranks.manage',
    'rank-clear': 'ranks.manage',
    grant: 'ranks.manage',
    ungrant: 'ranks.manage',
  };

  /** Asks for the people list, the bug list, or changes a bug's state or a feature flag. */
  admin(action: string, extra: Record<string, unknown> = {}): { ok: boolean; reason?: string } {
    if (!this.socket || this.state.status !== 'online') return { ok: false, reason: 'You are not connected to chat.' };
    const needed = ChatClient.ADMIN_NEEDS[action];
    if (!needed) return { ok: false, reason: 'That is not something the panel can do.' };
    if (!this.can(needed)) return { ok: false, reason: `Your rank cannot do that (needs ${needed}).` };
    this.socket.send(JSON.stringify({ type: 'admin', action, ...extra }));
    return { ok: true };
  }

  /** True when this account's rank includes a permission. The backend checks it again anyway. */
  can(permission: string): boolean {
    return this.state.permissions.includes('everything') || this.state.permissions.includes(permission);
  }

  /** Gives an account a rank, or takes it back to plain Snowball. Refused unless the rank allows it. */
  setRank(uuid: string, rank: SnowballRank): { ok: boolean; reason?: string } {
    if (!this.socket || this.state.status !== 'online') return { ok: false, reason: 'You are not connected to chat.' };
    if (!this.can('ranks.manage')) return { ok: false, reason: 'Your rank cannot change other people\u2019s ranks.' };
    this.socket.send(JSON.stringify({ type: 'admin', action: 'rank-set', uuid, rank }));
    return { ok: true };
  }

  /** Snowball+ is a rank like any other; this keeps the older wording working. */
  setPlus(uuid: string, on: boolean): { ok: boolean; reason?: string } {
    return this.setRank(uuid, on ? 'plus' : 'snowball');
  }

  /** Mutes a player for a while, or clears the chat for everyone. */
  moderateChat(action: 'mute' | 'unmute' | 'clear', uuid?: string, minutes?: number): { ok: boolean; reason?: string } {
    if (!this.socket || this.state.status !== 'online') return { ok: false, reason: 'You are not connected to chat.' };
    if (!this.can('chat.moderate')) return { ok: false, reason: 'Your rank cannot moderate chat.' };
    this.socket.send(JSON.stringify({ type: 'admin', action, uuid, minutes }));
    return { ok: true };
  }

  /**
   * Proves which account this is, using the key pair Mojang issues for signed chat. Mojang blocks
   * requests coming from Cloudflare, so the chat server cannot ask it anything; instead it checks
   * Mojang's signature over this account's public key, and our signature over its one-time id.
   * The Minecraft token is only ever sent to Mojang, never to Snowball.
   *
   * @returns a ticket the socket presents in place of all of this
   */
  private async proveIdentity(session: SessionLike): Promise<string> {
    const base = this.baseUrl.replace(/\/$/, '');
    const answer = await fetch(`${base}/nonce`);
    if (!answer.ok) throw new Error(`The chat server did not answer (${answer.status}).`);
    const { nonce } = (await answer.json()) as { nonce?: string };
    if (!nonce || !/^[a-f0-9]{16,64}$/.test(nonce)) throw new Error('The chat server sent something unexpected.');

    const certificates = await fetch('https://api.minecraftservices.com/player/certificates', {
      method: 'POST',
      headers: { authorization: `Bearer ${session.accessToken}`, accept: 'application/json' },
    });
    if (!certificates.ok) {
      const body = await certificates.text().catch(() => '');
      log.warn('Minecraft would not issue a chat key', { status: certificates.status, body: body.slice(0, 200) });
      throw new Error(describeJoinFailure(certificates.status, body));
    }
    const certificate = (await certificates.json()) as {
      keyPair?: { privateKey?: string; publicKey?: string };
      publicKeySignatureV2?: string;
      expiresAt?: string;
    };
    const privateKey = certificate.keyPair?.privateKey;
    const publicKey = certificate.keyPair?.publicKey;
    if (!privateKey || !publicKey || !certificate.publicKeySignatureV2 || !certificate.expiresAt) {
      throw new Error('Minecraft did not return a usable chat key.');
    }

    const signature = createSign('RSA-SHA256').update(nonce).sign(privateKey, 'base64');
    const ticketed = await fetch(`${base}/auth`, {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({
        name: session.name,
        uuid: session.uuid.replace(/-/g, ''),
        nonce,
        publicKey,
        keySignature: certificate.publicKeySignatureV2,
        expiresAt: Date.parse(certificate.expiresAt),
        signature,
      }),
    });
    if (!ticketed.ok) {
      const body = (await ticketed.json().catch(() => ({}))) as { error?: string };
      throw new Error(`Snowball could not confirm the account: ${body.error ?? ticketed.status}.`);
    }
    const { ticket } = (await ticketed.json()) as { ticket?: string };
    if (!ticket) throw new Error('The chat server did not hand out a ticket.');
    return ticket;
  }

  private open(url: string, session: SessionLike): void {
    // Never leave an old socket behind: it would keep its listeners and keep delivering every
    // broadcast alongside the new one.
    if (this.socket) {
      const stale = this.socket;
      this.socket = null;
      try {
        stale.close();
      } catch {
        /* already closing */
      }
    }
    const socket = new WebSocket(url);
    this.socket = socket;
    socket.addEventListener('open', () => {
      if (this.socket !== socket) return;
      this.retryMs = 3000;
      this.set({ status: 'online', admin: session.uuid.replace(/-/g, '') === this.adminUuid.replace(/-/g, ''), message: undefined });
    });
    // Only the current socket is listened to, so a socket being replaced cannot deliver anything.
    socket.addEventListener('message', (event: MessageEvent) => {
      if (this.socket !== socket) return;
      this.receive(String(event.data));
    });
    socket.addEventListener('error', () => {
      if (this.socket !== socket) return;
      this.set({ status: 'error', message: 'Lost the connection to chat.' });
    });
    socket.addEventListener('close', () => {
      // A stale socket closing is expected and must not schedule a reconnect of its own.
      if (this.socket !== socket) return;
      this.socket = null;
      if (this.closedByUs) return;
      this.set({ status: 'offline', online: 0 });
      // Come back quietly: chat is never the reason the launcher looks busy.
      this.clearRetry();
      this.retryTimer = setTimeout(() => void this.connect(session), this.retryMs);
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
        // The server uses one name for two things: chat history carries `messages`, a rank
        // history carries `uuid`. They are told apart here because the second `case 'history'`
        // that used to handle ranks was unreachable, so rank history never arrived at all.
        if (Array.isArray(payload.messages)) this.emit('history', payload.messages as ChatMessage[]);
        else if (payload.uuid) this.emit('rank-history', payload);
        break;
      case 'you': {
        const rank = (RANKS as readonly string[]).includes(payload.rank) ? (payload.rank as SnowballRank) : 'snowball';
        this.set({ rank, permissions: payload.permissions ?? [], admin: Boolean(payload.owner), flags: payload.flags ?? {} });
        this.emit('rank', rank);
        break;
      }
      case 'lookup':
        this.emit('lookup', payload);
        break;
      case 'rank-set':
        this.emit('rank-set', payload);
        break;

      case 'flags':
        this.set({ flags: payload.flags ?? {} });
        break;
      case 'people':
        this.emit('people', payload.people ?? []);
        break;
      case 'bugs':
        this.emit('bugs', payload.bugs ?? []);
        break;
      case 'bug-filed':
        this.emit('bug-filed', String(payload.id ?? ''));
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
