/*
 * Snowball Client global chat.
 *
 * One Durable Object holds the room: the sockets, the last messages, the announcement and the mutes.
 * Identity is not taken on trust - a player joins by asking Mojang to vouch for them, exactly as they
 * would for a Minecraft server, and this worker checks that with Mojang before letting them speak.
 *
 * Deploy: npx wrangler deploy   (see README.md)
 */

const HISTORY = 60;
const MAX_MESSAGE_LENGTH = 240;
const MUTE_DEFAULT_MINUTES = 10;

const BANNED = [
  'nigger', 'nigga', 'faggot', 'fag', 'retard', 'retarded', 'kys', 'killyourself', 'rape', 'rapist',
  'cunt', 'whore', 'slut', 'bitch', 'bastard', 'dick', 'cock', 'pussy', 'penis', 'vagina', 'porn',
  'pornhub', 'nsfw', 'sex', 'anal', 'blowjob', 'cum', 'fuck', 'fucking', 'fucker', 'motherfucker',
  'shit', 'bullshit', 'asshole', 'arsehole', 'wanker', 'twat', 'nazi', 'hitler', 'suicide',
];
const LEET = { '0': 'o', '1': 'i', '!': 'i', '3': 'e', '4': 'a', '@': 'a', '5': 's', '$': 's', '7': 't', '8': 'b', '+': 't', '|': 'i' };
const LINK = /(https?:\/\/|www\.|discord\.gg|discord\.com\/invite|[a-z0-9-]+\.(com|net|org|gg|io|xyz|me|tv|co|ru|link|shop|store|app|dev|site|online|club)(\/|\b))/i;
const IP = /\b\d{1,3}(?:\.\d{1,3}){3}\b(?::\d{1,5})?/;
const IMAGE = /(data:image\/|base64,|\.(png|jpe?g|gif|webp|bmp|svg)\b)/i;
const EVERYONE = /@(everyone|here)\b/i;
const INVISIBLE = new RegExp('[\\u0000-\\u001f\\u007f-\\u009f\\u200b-\\u200f\\u2028\\u2029\\u202a-\\u202e\\ufeff]', 'g');

function normalize(text) {
  return text
    .normalize('NFKD')
    .replace(/[̀-ͯ]/g, '')
    .toLowerCase()
    .split('')
    .map((ch) => LEET[ch] ?? ch)
    .join('')
    .replace(/[^a-z]/g, '');
}

/** The same rules the launcher applies, applied again where they cannot be edited away. */
function moderate(raw) {
  let text = String(raw ?? '').replace(INVISIBLE, '').replace(/\s+/g, ' ').trim();
  if (!text) return { ok: false, reason: 'Empty message.' };
  if (text.length > MAX_MESSAGE_LENGTH) return { ok: false, reason: `Keep it under ${MAX_MESSAGE_LENGTH} characters.` };
  if (LINK.test(text) || IP.test(text)) return { ok: false, reason: 'Links and addresses are not allowed in global chat.' };
  if (IMAGE.test(text)) return { ok: false, reason: 'Images cannot be posted in global chat.' };
  if (EVERYONE.test(text)) return { ok: false, reason: 'You cannot ping everyone.' };
  const squashed = normalize(text);
  if (BANNED.some((word) => squashed.includes(word))) return { ok: false, reason: 'That word is not allowed here.' };
  const letters = text.replace(/[^a-zA-Z]/g, '');
  if (letters.length > 6 && letters.replace(/[^A-Z]/g, '').length / letters.length > 0.7) {
    text = text.charAt(0) + text.slice(1).toLowerCase();
  }
  return { ok: true, text: text.replace(/(.)\1{4,}/g, '$1$1$1') };
}

export class ChatRoom {
  constructor(state, env) {
    this.state = state;
    this.env = env;
    this.sockets = new Map();
    this.history = [];
    this.announcement = null;
    this.mutes = new Map();
    this.tiers = new Map();
    this.people = new Map();
    this.bugs = [];
    this.flags = {};
    this.nonces = new Map();
    this.ready = state.blockConcurrencyWhile(async () => {
      this.history = (await state.storage.get('history')) ?? [];
      this.announcement = (await state.storage.get('announcement')) ?? null;
      this.mutes = new Map((await state.storage.get('mutes')) ?? []);
      this.tiers = new Map((await state.storage.get('tiers')) ?? []);
      this.people = new Map((await state.storage.get('people')) ?? []);
      this.bugs = (await state.storage.get('bugs')) ?? [];
      this.flags = (await state.storage.get('flags')) ?? {};
    });
  }

  async fetch(request) {
    await this.ready;
    const url = new URL(request.url);
    if (url.pathname.endsWith('/stats')) return Response.json(this.stats(), { headers: { 'cache-control': 'no-store' } });
    if (url.pathname.endsWith('/flags')) return Response.json(this.flags, { headers: { 'cache-control': 'no-store' } });
    if (url.pathname.endsWith('/nonce')) {
      const serverId = crypto.randomUUID().replace(/-/g, '');
      this.nonces.set(serverId, Date.now());
      this.sweepNonces();
      return Response.json({ serverId });
    }
    if (!url.pathname.endsWith('/ws')) return new Response('Not found', { status: 404 });
    if (request.headers.get('Upgrade') !== 'websocket') return new Response('Expected a WebSocket', { status: 426 });

    const name = url.searchParams.get('name') ?? '';
    const uuid = (url.searchParams.get('uuid') ?? '').replace(/-/g, '');
    const serverId = url.searchParams.get('serverId') ?? '';
    const profile = await this.verify(name, serverId);
    if (!profile || profile.id.replace(/-/g, '') !== uuid) {
      return new Response('Minecraft did not vouch for that account.', { status: 403 });
    }

    const pair = new WebSocketPair();
    const [client, server] = Object.values(pair);
    server.accept();
    const admin = uuid === String(this.env.ADMIN_UUID ?? '').replace(/-/g, '');
    // The edition comes from here and nowhere else: a changed client cannot grant itself Snowball+.
    const tier = admin ? 'plus' : this.tiers.get(uuid) ?? 'snowball';
    const member = { name: profile.name, uuid, admin, tier, times: [] };
    this.sockets.set(server, member);
    this.see(member);

    server.send(JSON.stringify({ type: 'you', tier: member.tier, owner: admin, flags: this.flags }));
    server.send(JSON.stringify({ type: 'history', messages: this.history }));
    server.send(JSON.stringify({ type: 'announce', text: this.announcement }));
    this.broadcastPresence();

    server.addEventListener('message', (event) => this.onMessage(server, member, event.data));
    server.addEventListener('close', () => {
      this.sockets.delete(server);
      this.broadcastPresence();
    });
    return new Response(null, { status: 101, webSocket: client });
  }

  /** Mojang's own answer to "is this really them?" - valid only for the id this room just handed out. */
  async verify(name, serverId) {
    if (!/^[A-Za-z0-9_]{3,16}$/.test(name) || !/^[a-f0-9]{40}$/.test(serverId)) return null;
    const issued = [...this.nonces.keys()];
    // The launcher sends the SHA-1 of the id, so every outstanding id is tried.
    let matched = false;
    for (const id of issued) {
      const hash = await sha1(id);
      if (hash === serverId) {
        matched = true;
        this.nonces.delete(id);
        break;
      }
    }
    if (!matched) return null;
    const response = await fetch(`https://sessionserver.mojang.com/session/minecraft/hasJoined?username=${encodeURIComponent(name)}&serverId=${serverId}`);
    if (response.status !== 200) return null;
    return response.json();
  }

  sweepNonces() {
    const cutoff = Date.now() - 120_000;
    for (const [id, at] of this.nonces) if (at < cutoff) this.nonces.delete(id);
  }

  async onMessage(socket, member, data) {
    let payload;
    try {
      payload = JSON.parse(String(data));
    } catch {
      return;
    }
    if (payload.type === 'msg') return this.onChat(socket, member, payload);
    if (payload.type === 'bug') return this.onBug(socket, member, payload);
    if (payload.type === 'announce' && member.admin) return this.onAnnounce(payload);
    if (payload.type === 'admin' && member.admin) return this.onAdmin(payload, socket);
    socket.send(JSON.stringify({ type: 'error', message: 'Only the Snowball account can do that.' }));
  }

  async onChat(socket, member, payload) {
    const muted = this.mutes.get(member.uuid);
    if (muted && muted > Date.now()) {
      const minutes = Math.ceil((muted - Date.now()) / 60_000);
      return socket.send(JSON.stringify({ type: 'error', message: `You are muted for another ${minutes} minute${minutes === 1 ? '' : 's'}.` }));
    }
    const now = Date.now();
    member.times = member.times.filter((t) => now - t < 60_000);
    if (member.times.length >= 12 || (member.times.length && now - member.times[member.times.length - 1] < 1200)) {
      return socket.send(JSON.stringify({ type: 'error', message: 'Slow down a moment.' }));
    }
    member.times.push(now);

    const check = moderate(payload.text);
    if (!check.ok) return socket.send(JSON.stringify({ type: 'error', message: check.reason }));

    const message = { type: 'msg', id: crypto.randomUUID(), name: member.name, uuid: member.uuid, text: check.text, at: new Date().toISOString(), staff: member.admin, tier: member.tier };
    this.history.push(message);
    if (this.history.length > HISTORY) this.history.splice(0, this.history.length - HISTORY);
    await this.state.storage.put('history', this.history);
    this.broadcast(message);
  }

  /** Keeps a light record of each account: enough to count players and to list them for the owner. */
  see(member) {
    const known = this.people.get(member.uuid) ?? { first: Date.now() };
    known.name = member.name;
    known.last = Date.now();
    known.tier = member.tier;
    this.people.set(member.uuid, known);
    void this.state.storage.put('people', [...this.people]);
  }

  stats() {
    const now = Date.now();
    const day = 24 * 60 * 60_000;
    let today = 0;
    let week = 0;
    for (const person of this.people.values()) {
      if (now - person.last < day) today++;
      if (now - person.last < 7 * day) week++;
    }
    return { online: this.sockets.size, today, week, total: this.people.size };
  }

  /** A bug report from a player. The account is taken from the connection, never from the message. */
  async onBug(socket, member, payload) {
    const bug = {
      id: crypto.randomUUID(),
      title: String(payload.title ?? '').trim().slice(0, 120),
      detail: String(payload.detail ?? '').trim().slice(0, 4000),
      steps: String(payload.steps ?? '').trim().slice(0, 1000),
      minecraft: String(payload.minecraft ?? '').trim().slice(0, 40),
      snowball: String(payload.snowball ?? '').trim().slice(0, 40),
      loader: String(payload.loader ?? '').trim().slice(0, 40),
      logs: String(payload.logs ?? '').trim().slice(0, 20000),
      by: member.name,
      uuid: member.uuid,
      at: new Date().toISOString(),
      status: 'open',
    };
    if (!bug.title || !bug.detail) {
      return socket.send(JSON.stringify({ type: 'error', message: 'A bug report needs a title and a description.' }));
    }
    const mine = this.bugs.filter((b) => b.uuid === member.uuid && Date.now() - Date.parse(b.at) < 60 * 60_000);
    if (mine.length >= 5) {
      return socket.send(JSON.stringify({ type: 'error', message: 'That is five reports in an hour; give it a rest.' }));
    }
    this.bugs.unshift(bug);
    if (this.bugs.length > 500) this.bugs.length = 500;
    await this.state.storage.put('bugs', this.bugs);
    socket.send(JSON.stringify({ type: 'bug-filed', id: bug.id }));
    // The owner sees it arrive without asking.
    for (const [other, person] of this.sockets) {
      if (person.admin) other.send(JSON.stringify({ type: 'bugs', bugs: this.bugs.slice(0, 50) }));
    }
  }

  async onAnnounce(payload) {
    this.announcement = typeof payload.text === 'string' && payload.text.trim() ? payload.text.trim().slice(0, 300) : null;
    await this.state.storage.put('announcement', this.announcement);
    this.broadcast({ type: 'announce', text: this.announcement });
  }

  async onAdmin(payload, socket) {
    if (payload.action === 'people') {
      const people = [...this.people].map(([uuid, person]) => ({ uuid, ...person, muted: (this.mutes.get(uuid) ?? 0) > Date.now() }));
      people.sort((a, b) => b.last - a.last);
      return socket.send(JSON.stringify({ type: 'people', people: people.slice(0, 200), stats: this.stats() }));
    }
    if (payload.action === 'bugs') {
      return socket.send(JSON.stringify({ type: 'bugs', bugs: this.bugs.slice(0, 50) }));
    }
    if (payload.action === 'bug-status') {
      const bug = this.bugs.find((b) => b.id === payload.id);
      const allowed = ['open', 'investigating', 'fixed', 'duplicate', 'invalid'];
      if (bug && allowed.includes(payload.status)) {
        bug.status = payload.status;
        await this.state.storage.put('bugs', this.bugs);
      }
      return socket.send(JSON.stringify({ type: 'bugs', bugs: this.bugs.slice(0, 50) }));
    }
    if (payload.action === 'flag') {
      const key = String(payload.key ?? '').trim().slice(0, 40);
      if (!key) return;
      if (payload.value === null) delete this.flags[key];
      else this.flags[key] = payload.value === true || payload.value === 'on';
      await this.state.storage.put('flags', this.flags);
      // Everyone hears about a feature being switched on or off straight away.
      return this.broadcast({ type: 'flags', flags: this.flags });
    }
    if (payload.action === 'clear') {
      this.history = [];
      await this.state.storage.put('history', this.history);
      return this.broadcast({ type: 'history', messages: [] });
    }
    const uuid = String(payload.uuid ?? '').replace(/-/g, '');
    if (!/^[a-f0-9]{32}$/.test(uuid)) return;
    if (payload.action === 'grant' || payload.action === 'ungrant') {
      if (payload.action === 'grant') this.tiers.set(uuid, 'plus');
      else this.tiers.delete(uuid);
      await this.state.storage.put('tiers', [...this.tiers]);
      // Anyone already connected under that account is told straight away.
      for (const [socket, member] of this.sockets) {
        if (member.uuid !== uuid) continue;
        member.tier = payload.action === 'grant' ? 'plus' : 'snowball';
        socket.send(JSON.stringify({ type: 'you', tier: member.tier, owner: member.admin }));
      }
      return;
    }
    if (payload.action === 'unmute') {
      this.mutes.delete(uuid);
    } else if (payload.action === 'mute') {
      const minutes = Number.isFinite(payload.minutes) ? Math.min(1440, Math.max(1, Math.round(payload.minutes))) : MUTE_DEFAULT_MINUTES;
      this.mutes.set(uuid, Date.now() + minutes * 60_000);
    }
    await this.state.storage.put('mutes', [...this.mutes]);
  }

  broadcast(payload) {
    const data = JSON.stringify(payload);
    for (const socket of this.sockets.keys()) {
      try {
        socket.send(data);
      } catch {
        this.sockets.delete(socket);
      }
    }
  }

  broadcastPresence() {
    this.broadcast({ type: 'presence', online: this.sockets.size });
  }
}

async function sha1(text) {
  const digest = await crypto.subtle.digest('SHA-1', new TextEncoder().encode(text));
  return [...new Uint8Array(digest)].map((b) => b.toString(16).padStart(2, '0')).join('');
}

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    if (url.pathname === '/' || url.pathname === '/health') return new Response('Snowball chat is up.');
    // One room for everyone, so every launcher sees the same conversation.
    const id = env.CHAT_ROOM.idFromName('global');
    return env.CHAT_ROOM.get(id).fetch(request);
  },
};
