/*
 * Snowball Client global chat.
 *
 * One Durable Object holds the room: the sockets, the last messages, the announcement and the mutes.
 * Identity is not taken on trust. Mojang blocks requests from workers, so instead of asking it, the
 * launcher proves itself with the key pair Mojang issued to that account and this room checks both
 * signatures offline - see identity.js.
 *
 * Deploy: npx wrangler deploy   (see README.md)
 */

import { verifyPlayerProof } from './identity.js';

const HISTORY = 60;
const MAX_MESSAGE_LENGTH = 240;
const MUTE_DEFAULT_MINUTES = 10;

/** Ranks from least to most. The client displays these; this file is what actually decides them. */
const RANKS = ['snowball', 'plus', 'tester', 'bug_hunter', 'partner', 'staff', 'developer', 'owner'];

/**
 * What each rank may do. A rank keeps everything the rank below it has, so a new ability given to
 * 'staff' reaches developer and owner too without being listed again.
 */
const PERMISSIONS = {
  snowball: ['base', 'bugs.report'],
  plus: ['plus.features'],
  tester: ['beta.access'],
  bug_hunter: ['bugs.triage'],
  partner: ['partner.features'],
  staff: ['chat.moderate', 'users.view'],
  developer: ['dev.tools', 'flags.manage', 'chat.announce'],
  owner: ['ranks.manage', 'everything'],
};

function permissionsOf(rank) {
  const granted = new Set();
  for (const name of RANKS) {
    for (const permission of PERMISSIONS[name]) granted.add(permission);
    if (name === rank) break;
  }
  return [...granted];
}

function can(member, permission) {
  const granted = permissionsOf(member.rank);
  return granted.includes('everything') || granted.includes(permission);
}

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
    this.ranks = new Map();
    this.rankLog = new Map();
    this.people = new Map();
    this.bugs = [];
    this.flags = {};
    this.nonces = new Map();
    this.tickets = new Map();
    this.ready = state.blockConcurrencyWhile(async () => {
      this.history = (await state.storage.get('history')) ?? [];
      this.announcement = (await state.storage.get('announcement')) ?? null;
      this.mutes = new Map((await state.storage.get('mutes')) ?? []);
      this.ranks = new Map((await state.storage.get('ranks')) ?? []);
      this.rankLog = new Map((await state.storage.get('rankLog')) ?? []);
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
      const nonce = crypto.randomUUID().replace(/-/g, '');
      this.nonces.set(nonce, Date.now());
      this.sweepNonces();
      return Response.json({ nonce });
    }
    if (url.pathname.endsWith('/auth') && request.method === 'POST') {
      const proof = await request.json().catch(() => ({}));
      const name = String(proof.name ?? '');
      const uuid = String(proof.uuid ?? '').replace(/-/g, '');
      const failure = await this.verify(name, uuid, String(proof.nonce ?? ''), proof);
      if (typeof failure === 'string') {
        console.log('auth refused:', failure, name);
        return Response.json({ error: failure }, { status: 403 });
      }
      // A ticket the socket presents in its place, good for one connection and one minute.
      const ticket = crypto.randomUUID().replace(/-/g, '');
      this.tickets.set(ticket, { name, uuid, at: Date.now() });
      for (const [id, held] of this.tickets) if (Date.now() - held.at > 60_000) this.tickets.delete(id);
      return Response.json({ ticket });
    }
    if (!url.pathname.endsWith('/ws')) return new Response('Not found', { status: 404 });
    if (request.headers.get('Upgrade') !== 'websocket') return new Response('Expected a WebSocket', { status: 426 });

    const ticket = url.searchParams.get('ticket') ?? '';
    const held = this.tickets.get(ticket);
    this.tickets.delete(ticket);
    if (!held || Date.now() - held.at > 60_000) {
      return new Response('That ticket is not valid any more. Join again.', { status: 403 });
    }
    const name = held.name;
    const uuid = held.uuid;
    const profile = { id: uuid, name };
    console.log('join', JSON.stringify({ name, uuid }));

    const pair = new WebSocketPair();
    const [client, server] = Object.values(pair);
    server.accept();
    // The owner is fixed in the worker's own settings; everyone else holds the rank stored here.
    const owner = uuid === String(this.env.ADMIN_UUID ?? '').replace(/-/g, '');
    const rank = owner ? 'owner' : this.ranks.get(uuid)?.rank ?? 'snowball';
    const member = { name: profile.name, uuid, owner, rank, times: [] };
    this.sockets.set(server, member);
    this.see(member);

    server.send(JSON.stringify({ type: 'you', rank: member.rank, permissions: permissionsOf(member.rank), owner, flags: this.flags }));
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

  /**
   * Who is connecting, proved with the key pair Mojang issued to that account. Mojang blocks
   * requests from workers, so nothing is asked of it here: the signatures are checked offline.
   *
   * @returns the profile when it checks out, or a short reason why it did not
   */
  async verify(name, uuid, nonce, proof) {
    if (!/^[A-Za-z0-9_]{3,16}$/.test(name)) return 'that is not a Minecraft name';
    if (!/^[a-f0-9]{32}$/.test(uuid)) return 'that is not an account id';
    if (!this.nonces.has(nonce)) return 'that one-time id is not one we issued';
    this.nonces.delete(nonce);
    if (!proof || typeof proof !== 'object') return 'the proof was missing';
    const failure = await verifyPlayerProof(this.env, {
      uuid,
      publicKey: proof.publicKey,
      keySignature: proof.keySignature,
      expiresAt: proof.expiresAt,
      nonce,
      nonceSignature: proof.signature,
    });
    if (failure) return failure;
    return { id: uuid, name };
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
    if (payload.type === 'announce') {
      if (!can(member, 'chat.announce')) return this.refuse(socket);
      return this.onAnnounce(payload);
    }
    if (payload.type === 'admin') return this.onAdmin(payload, socket, member);
    this.refuse(socket);
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

    const message = { type: 'msg', id: crypto.randomUUID(), name: member.name, uuid: member.uuid, text: check.text, at: new Date().toISOString(), staff: member.owner, rank: member.rank };
    this.history.push(message);
    if (this.history.length > HISTORY) this.history.splice(0, this.history.length - HISTORY);
    await this.state.storage.put('history', this.history);
    this.broadcast(message);
  }

  refuse(socket) {
    socket.send(JSON.stringify({ type: 'error', message: 'Your rank does not allow that.' }));
  }

  /** Keeps a light record of each account: enough to count players and to list them for the owner. */
  see(member) {
    const known = this.people.get(member.uuid) ?? { first: Date.now() };
    known.name = member.name;
    known.last = Date.now();
    known.rank = member.rank;
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

  async onAdmin(payload, socket, member) {
    const needed = {
      people: 'users.view', bugs: 'bugs.report', 'bug-status': 'bugs.triage', flag: 'flags.manage',
      clear: 'chat.moderate', mute: 'chat.moderate', unmute: 'chat.moderate',
      'rank-set': 'ranks.manage', 'rank-clear': 'ranks.manage', grant: 'ranks.manage', ungrant: 'ranks.manage',
      history: 'users.view', lookup: 'users.view',
    }[payload.action];
    if (!needed || !can(member, needed)) return this.refuse(socket);

    if (payload.action === 'lookup') {
      // The launcher resolved the name with Mojang, which refuses requests from here.
      const wanted = String(payload.name ?? '').trim();
      const id = String(payload.uuid ?? '').replace(/-/g, '');
      if (!/^[a-f0-9]{32}$/.test(id)) return socket.send(JSON.stringify({ type: 'lookup', found: false, name: wanted }));
      const isOwner = id === String(this.env.ADMIN_UUID ?? '').replace(/-/g, '');
      return socket.send(JSON.stringify({
        type: 'lookup',
        found: true,
        name: wanted,
        uuid: id,
        rank: isOwner ? 'owner' : this.ranks.get(id)?.rank ?? 'snowball',
        given: this.ranks.get(id) ?? null,
        history: this.rankLog.get(id) ?? [],
        seen: this.people.get(id) ?? null,
      }));
    }
    if (payload.action === 'history') {
      const id = String(payload.uuid ?? '').replace(/-/g, '');
      return socket.send(JSON.stringify({ type: 'history', uuid: id, history: this.rankLog.get(id) ?? [] }));
    }
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
    if (payload.action === 'rank-set' || payload.action === 'rank-clear' || payload.action === 'grant' || payload.action === 'ungrant') {
      if (uuid === String(this.env.ADMIN_UUID ?? '').replace(/-/g, '')) {
        return socket.send(JSON.stringify({ type: 'error', message: 'The owner account is set in the worker settings, not here.' }));
      }
      let rank = 'snowball';
      if (payload.action === 'rank-set') rank = RANKS.includes(payload.rank) ? payload.rank : 'snowball';
      else if (payload.action === 'grant') rank = 'plus';
      if (rank === 'owner') return socket.send(JSON.stringify({ type: 'error', message: 'Owner cannot be handed out.' }));

      const entry = { rank, at: Date.now(), by: member.uuid, byName: member.name };
      if (rank === 'snowball') this.ranks.delete(uuid);
      else this.ranks.set(uuid, entry);
      const log = this.rankLog.get(uuid) ?? [];
      log.unshift(entry);
      if (log.length > 50) log.length = 50;
      this.rankLog.set(uuid, log);
      await this.state.storage.put('ranks', [...this.ranks]);
      await this.state.storage.put('rankLog', [...this.rankLog]);

      // Anyone connected under that account hears at once, so nothing needs reinstalling.
      for (const [other, person] of this.sockets) {
        if (person.uuid !== uuid) continue;
        person.rank = rank;
        other.send(JSON.stringify({ type: 'you', rank, permissions: permissionsOf(rank), owner: person.owner, flags: this.flags }));
      }
      socket.send(JSON.stringify({ type: 'rank-set', uuid, rank, history: this.rankLog.get(uuid) ?? [] }));
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

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    if (url.pathname === '/' || url.pathname === '/health') return new Response('Snowball chat is up.');
    // One room for everyone, so every launcher sees the same conversation.
    const id = env.CHAT_ROOM.idFromName('global');
    return env.CHAT_ROOM.get(id).fetch(request);
  },
};
