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

/** How often a game running Snowball Client says so, and how long that counts for after it stops. */
const PRESENCE_EVERY_S = 120;
const PRESENCE_TTL_MS = 5 * 60_000;
/** How long a proved game may keep saying so on its ticket before proving the account again. */
const PRESENCE_TICKET_MS = 12 * 60 * 60_000;

/**
 * Cosmetics and emotes the client ships. Only these ids are passed between players: the worker
 * never forwards anything a modified client makes up.
 */
const CAPES = ['snowball', 'aurora', 'northern_lights', 'frost', 'ember', 'sakura', 'midnight', 'void'];
const EMOTES = ['wave', 'clap', 'cheer', 'dab', 'salute', 'facepalm', 'bow', 'tpose', 'dance', 'spin', 'think', 'point', 'zombie', 'star_jump', 'shrug', 'headbang'];
/** An emote at most this often, and this many a minute. */
const EMOTE_GAP_MS = 700;
const EMOTES_PER_MINUTE = 24;
/** How many players one game may watch for emotes. */
const WATCH_MAX = 200;

/** A custom cape's outer face: 10x16 colours as RRGGBB, 960 hex digits. */
const CUSTOM_CAPE = /^[0-9a-f]{960}$/;
/** A Snowball+ player may send a new design this often. */
const CAPE_SUBMIT_GAP_MS = 60_000;

/**
 * Snowball+ sold as a monthly subscription through a Stripe Payment Link. Stripe signs every
 * webhook with the endpoint's secret (STRIPE_WEBHOOK_SECRET, set with `wrangler secret put`). A
 * subscription in these states is still paid for; past_due means Stripe is retrying the card.
 */
const STRIPE_PAID = ['active', 'trialing', 'past_due'];
/** How old a signed webhook may be, the same five minutes Stripe's own libraries allow. */
const STRIPE_TOLERANCE_S = 300;
/** Who the rank history says gave Snowball+ when the store did. */
const STORE = { uuid: 'store', name: 'Store' };

/**
 * Whether Stripe signed this body. The Stripe-Signature header holds the time it was signed (t)
 * and one or more v1 signatures: HMAC-SHA256, keyed with the secret, of "t.body".
 */
async function stripeSigned(body, header, secret, now = Date.now()) {
  const parts = header.split(',').map((part) => part.trim().split('='));
  const t = Number(parts.find(([k]) => k === 't')?.[1]);
  const signatures = parts.filter(([k, v]) => k === 'v1' && /^[0-9a-f]{64}$/.test(v ?? '')).map(([, v]) => v);
  if (!Number.isInteger(t) || !signatures.length || Math.abs(now / 1000 - t) > STRIPE_TOLERANCE_S) return false;
  const encoder = new TextEncoder();
  const key = await crypto.subtle.importKey('raw', encoder.encode(secret), { name: 'HMAC', hash: 'SHA-256' }, false, ['verify']);
  const prefix = encoder.encode(`${t}.`);
  const signed = new Uint8Array(prefix.length + body.length);
  signed.set(prefix);
  signed.set(body, prefix.length);
  for (const signature of signatures) {
    const given = Uint8Array.from(signature.match(/../g), (pair) => parseInt(pair, 16));
    if (await crypto.subtle.verify('HMAC', key, given, signed)) return true;
  }
  return false;
}

/**
 * The Minecraft account a checkout bought Snowball+ for. The store page looks the name up and sends
 * the buyer to the Payment Link with client_reference_id "<account id>_<name>"; any other checkout
 * on the Stripe account is not a Snowball+ one.
 */
function plusAccount(reference) {
  const match = /^([0-9a-f]{32})_([A-Za-z0-9_]{1,16})$/.exec(String(reference ?? ''));
  return match ? { uuid: match[1], name: match[2] } : null;
}

/**
 * The looks a game asked for, kept to what the client ships. "custom" only stands for a design
 * staff approved, and carries its version so games fetch the new one when it changes.
 */
function looksOf(raw, approved) {
  if (!raw || typeof raw !== 'object') return null;
  const cape = String(raw.cape ?? '');
  if (cape === 'custom') return approved ? { cape: 'custom', v: approved.version } : null;
  return CAPES.includes(cape) ? { cape } : null;
}

const HISTORY = 60;
const MAX_MESSAGE_LENGTH = 240;
const MUTE_DEFAULT_MINUTES = 10;

/** Ranks from least to most. The client displays these; this file is what actually decides them. */
const RANKS = ['snowball', 'plus', 'tester', 'bug_hunter', 'partner', 'staff', 'developer', 'owner'];
const RANK_NAMES = { plus: 'Snowball+', tester: 'Tester', bug_hunter: 'Bug Hunter', partner: 'Partner', staff: 'Staff', developer: 'Developer', owner: 'Owner' };

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
  staff: ['chat.moderate', 'users.view', 'capes.review'],
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
    // Who is playing with Snowball Client right now: account id -> when the game last said so.
    // Held in memory on purpose: it only has to outlive the next heartbeat, never a restart.
    this.inClient = new Map();
    // Handed to a game after it proved its account, so its heartbeats need not prove it again.
    this.presenceTickets = new Map();
    // What each player in the client wears (account id -> { cape }), sent with every heartbeat.
    this.looks = new Map();
    // Games listening for emotes: socket -> { uuid, watching, times }.
    this.live = new Map();
    // Custom capes: account id -> { name, approved: { pixels, version, at, by } | null,
    // request: { pixels, at, status: 'pending' | 'denied', reason } | null }.
    this.capes = new Map();
    // Snowball+ subscriptions from the store: Stripe subscription id -> { uuid, name, paid, at }, at
    // being when Stripe created the latest event about it, in seconds.
    this.subs = new Map();
    this.ready = state.blockConcurrencyWhile(async () => {
      this.history = (await state.storage.get('history')) ?? [];
      this.announcement = (await state.storage.get('announcement')) ?? null;
      this.mutes = new Map((await state.storage.get('mutes')) ?? []);
      this.ranks = new Map((await state.storage.get('ranks')) ?? []);
      this.rankLog = new Map((await state.storage.get('rankLog')) ?? []);
      this.people = new Map((await state.storage.get('people')) ?? []);
      this.bugs = (await state.storage.get('bugs')) ?? [];
      this.flags = (await state.storage.get('flags')) ?? {};
      this.capes = new Map((await state.storage.get('capes')) ?? []);
      this.subs = new Map((await state.storage.get('subs')) ?? []);
    });
  }

  async fetch(request) {
    await this.ready;
    const url = new URL(request.url);
    if (url.pathname.endsWith('/stats')) {
      return Response.json({ ...this.stats(), announcement: this.announcement ?? null }, { headers: { 'cache-control': 'no-store' } });
    }
    if (url.pathname.endsWith('/flags')) return Response.json(this.flags, { headers: { 'cache-control': 'no-store' } });
    // A game running Snowball Client saying so, every couple of minutes while it runs. The first
    // time it proves the account with the key Mojang issued (the same proof the launcher's chat
    // uses) and gets a ticket; after that the ticket is enough. Nothing else about the player is
    // kept, and it is forgotten a few minutes after the game stops saying so.
    if (url.pathname.endsWith('/presence')) {
      const body = await request.json().catch(() => ({}));
      const now = Date.now();
      for (const [id, held] of this.presenceTickets) if (held.until < now) this.presenceTickets.delete(id);
      if (request.method === 'DELETE') {
        const held = this.presenceTickets.get(String(body.ticket ?? ''));
        if (held) {
          this.inClient.delete(held.uuid);
          this.looks.delete(held.uuid);
          this.presenceTickets.delete(String(body.ticket));
          this.liveSend(held.uuid, { type: 'gone', uuid: held.uuid });
        }
        return Response.json({ ok: true });
      }
      if (request.method !== 'POST') return new Response('Method not allowed', { status: 405 });
      if (body.ticket) {
        const held = this.presenceTickets.get(String(body.ticket));
        if (!held) return Response.json({ error: 'that ticket is not valid any more' }, { status: 403 });
        const was = this.playingNow(held.uuid);
        this.inClient.set(held.uuid, now);
        if (!was) this.liveSend(held.uuid, { type: 'rank', uuid: held.uuid, rank: this.rankOf(held.uuid) });
        this.setLooks(held.uuid, body.looks);
        return Response.json({ ok: true, ticket: String(body.ticket), every: PRESENCE_EVERY_S });
      }
      const name = String(body.name ?? '');
      const uuid = String(body.uuid ?? '').replace(/-/g, '').toLowerCase();
      const failure = await this.verify(name, uuid, String(body.nonce ?? ''), body);
      if (typeof failure === 'string') return Response.json({ error: failure }, { status: 403 });
      const ticket = crypto.randomUUID().replace(/-/g, '');
      this.presenceTickets.set(ticket, { uuid, name, until: now + PRESENCE_TICKET_MS });
      const was = this.playingNow(uuid);
      this.inClient.set(uuid, now);
      // Games that can see this player hear at once that they play Snowball.
      if (!was) this.liveSend(uuid, { type: 'rank', uuid, rank: this.rankOf(uuid) });
      this.setLooks(uuid, body.looks);
      return Response.json({ ok: true, ticket, every: PRESENCE_EVERY_S });
    }
    // Which of these accounts are playing with Snowball Client right now, and what they hold. The
    // game asks about the players it can see so it can draw their badge. Only a game that is
    // running Snowball Client counts: somebody who used it once and now plays on another client
    // gets no badge, and neither does the owner.
    if (url.pathname.endsWith('/ranks') && request.method === 'POST') {
      const body = await request.json().catch(() => ({}));
      const asked = Array.isArray(body.uuids) ? body.uuids : [];
      const owner = String(this.env.ADMIN_UUID ?? '').replace(/-/g, '');
      const out = {};
      const looks = {};
      for (const raw of asked.slice(0, 200)) {
        const id = String(raw ?? '').replace(/-/g, '').toLowerCase();
        if (!/^[a-f0-9]{32}$/.test(id)) continue;
        if (!this.playingNow(id)) continue;
        const worn = this.looks.get(id);
        if (worn) looks[id] = worn;
        if (id === owner) { out[id] = 'owner'; continue; }
        out[id] = this.ranks.get(id)?.rank ?? 'snowball';
      }
      return Response.json({ ranks: out, looks }, { headers: { 'cache-control': 'no-store' } });
    }
    // Custom capes. A Snowball+ player sends a design; it shows on nobody until staff approve it.
    if (url.pathname.endsWith('/capes/submit') && request.method === 'POST') {
      const body = await request.json().catch(() => ({}));
      const held = this.presenceTickets.get(String(body.ticket ?? ''));
      if (!held || held.until < Date.now()) return Response.json({ error: 'Join a world first, so Snowball knows it is you' }, { status: 403 });
      if (!can({ rank: this.rankOf(held.uuid) }, 'plus.features')) return Response.json({ error: 'Custom capes come with Snowball+' }, { status: 403 });
      const pixels = String(body.pixels ?? '');
      if (!CUSTOM_CAPE.test(pixels)) return Response.json({ error: 'That is not a cape design' }, { status: 400 });
      const entry = this.capes.get(held.uuid) ?? { approved: null, request: null };
      if (entry.request && Date.now() - entry.request.at < CAPE_SUBMIT_GAP_MS) return Response.json({ error: 'Wait a minute before sending another design' }, { status: 429 });
      entry.name = held.name ?? this.people.get(held.uuid)?.name ?? '';
      entry.request = { pixels, at: Date.now(), status: 'pending', reason: '' };
      this.capes.set(held.uuid, entry);
      await this.state.storage.put('capes', [...this.capes]);
      this.tellReviewers();
      return Response.json({ ok: true, status: 'pending' });
    }
    if (url.pathname.endsWith('/capes/status')) {
      const held = this.presenceTickets.get(url.searchParams.get('ticket') ?? '');
      if (!held) return Response.json({ error: 'that ticket is not valid any more' }, { status: 403 });
      const entry = this.capes.get(held.uuid);
      const request = entry?.request ? { status: entry.request.status, reason: entry.request.reason ?? '', at: entry.request.at } : null;
      return Response.json({ approved: entry?.approved?.version ?? null, request }, { headers: { 'cache-control': 'no-store' } });
    }
    const capeFile = url.pathname.match(/\/capes\/([0-9a-f]{32})$/);
    if (capeFile) {
      // Approved designs are public: they are on show on the player's back anyway.
      const approved = this.capes.get(capeFile[1])?.approved;
      if (!approved) return new Response('Not found', { status: 404 });
      return Response.json({ pixels: approved.pixels, version: approved.version });
    }
    // Snowball+ bought on the website: Stripe takes the payment and tells this room.
    if (url.pathname.endsWith('/store/stripe') && request.method === 'POST') return this.onStripe(request);
    // Emotes travel live: a game opens this with the ticket its heartbeat holds, says whose emotes it
    // wants (the players it can see), and hears theirs as they happen.
    if (url.pathname.endsWith('/live')) {
      if (request.headers.get('Upgrade') !== 'websocket') return new Response('Expected a WebSocket', { status: 426 });
      const held = this.presenceTickets.get(url.searchParams.get('ticket') ?? '');
      if (!held || held.until < Date.now()) return new Response('That ticket is not valid any more.', { status: 403 });
      const pair = new WebSocketPair();
      const [client, server] = Object.values(pair);
      server.accept();
      this.live.set(server, { uuid: held.uuid, watching: new Set(), times: [] });
      server.addEventListener('message', (event) => this.onLive(server, event.data));
      server.addEventListener('close', () => this.live.delete(server));
      server.addEventListener('error', () => this.live.delete(server));
      return new Response(null, { status: 101, webSocket: client });
    }
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

  /** The rank an account holds, as this room decides it. */
  rankOf(uuid) {
    if (uuid === String(this.env.ADMIN_UUID ?? '').replace(/-/g, '')) return 'owner';
    return this.ranks.get(uuid)?.rank ?? 'snowball';
  }

  /**
   * A webhook from Stripe. Snowball+ is given when the checkout for it is paid, kept while the
   * subscription is paid for, and taken back when it is cancelled or the card keeps failing. The
   * store only ever takes back the Snowball+ it gave: a rank staff handed out, Snowball+ or higher,
   * stays.
   */
  async onStripe(request) {
    const secret = String(this.env.STRIPE_WEBHOOK_SECRET ?? '');
    if (!secret) return new Response('The store is not set up yet.', { status: 503 });
    const body = new Uint8Array(await request.arrayBuffer());
    if (!(await stripeSigned(body, request.headers.get('stripe-signature') ?? '', secret))) return new Response('Bad signature', { status: 403 });
    let event;
    try {
      event = JSON.parse(new TextDecoder().decode(body));
    } catch {
      return new Response('Not JSON', { status: 400 });
    }

    const object = event.data?.object ?? {};
    let id;
    let buyer;
    let paid;
    if (event.type === 'checkout.session.completed' || event.type === 'checkout.session.async_payment_succeeded') {
      buyer = plusAccount(object.client_reference_id);
      if (!buyer || object.mode !== 'subscription' || !object.subscription) return Response.json({ ok: true, ignored: 'not a Snowball+ checkout' });
      id = String(object.subscription);
      paid = object.payment_status === 'paid' || object.payment_status === 'no_payment_required';
    } else if (event.type === 'customer.subscription.updated' || event.type === 'customer.subscription.deleted') {
      id = String(object.id ?? '');
      buyer = this.subs.get(id);
      if (!buyer) return Response.json({ ok: true, ignored: 'not a Snowball+ subscription' });
      paid = event.type === 'customer.subscription.updated' && STRIPE_PAID.includes(object.status);
    } else {
      return Response.json({ ok: true, ignored: event.type });
    }

    // Stripe sends a webhook again when it did not hear back, and in no set order, so an old one
    // can land after a newer one.
    const at = Number(event.created) || Math.floor(Date.now() / 1000);
    const known = this.subs.get(id);
    if (known && known.at > at) return Response.json({ ok: true, ignored: 'older than the last one' });
    this.subs.set(id, { uuid: buyer.uuid, name: buyer.name, paid, at });
    await this.state.storage.put('subs', [...this.subs]);

    if (buyer.uuid === String(this.env.ADMIN_UUID ?? '').replace(/-/g, '')) return Response.json({ ok: true });
    // Somebody given two subscriptions keeps Snowball+ until the last one ends.
    const stillPaid = [...this.subs.values()].some((sub) => sub.uuid === buyer.uuid && sub.paid);
    const held = this.ranks.get(buyer.uuid);
    if (stillPaid && !held) await this.setRank(buyer.uuid, 'plus', STORE, { name: buyer.name });
    else if (!stillPaid && held?.rank === 'plus' && held.by === STORE.uuid) await this.setRank(buyer.uuid, 'snowball', STORE, { name: buyer.name });
    return Response.json({ ok: true });
  }

  /**
   * Gives an account a rank ('snowball' takes it away), keeps the record, and tells the launchers
   * signed in to it and the games that can see it.
   */
  async setRank(uuid, rank, by, extra = {}) {
    const entry = { rank, at: Date.now(), by: by.uuid, byName: by.name, ...extra };
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
    // Players who can see them in game get the new badge now.
    if (this.playingNow(uuid)) this.liveSend(uuid, { type: 'rank', uuid, rank });
    if (this.looks.get(uuid)?.cape === 'custom') this.setLooks(uuid, { cape: 'custom' });
  }

  /** The designs waiting for staff, oldest first. */
  pendingCapes() {
    const out = [];
    for (const [uuid, entry] of this.capes) {
      if (entry.request?.status === 'pending') out.push({ uuid, name: entry.name ?? '', pixels: entry.request.pixels, at: entry.request.at, replaces: Boolean(entry.approved) });
    }
    return out.sort((a, b) => a.at - b.at).slice(0, 50);
  }

  /** Staff with the panel open see a new design straight away. */
  tellReviewers() {
    const data = JSON.stringify({ type: 'capes', capes: this.pendingCapes() });
    for (const [socket, person] of this.sockets) {
      if (!can(person, 'capes.review')) continue;
      try {
        socket.send(data);
      } catch {
        this.sockets.delete(socket);
      }
    }
  }

  /** Stores what a player wears and tells the games watching them straight away when it changes. */
  setLooks(uuid, raw) {
    // A custom cape is a Snowball+ feature, so it comes off when Snowball+ ends; the design is kept.
    const approved = can({ rank: this.rankOf(uuid) }, 'plus.features') ? this.capes.get(uuid)?.approved : null;
    const looks = looksOf(raw, approved);
    const before = JSON.stringify(this.looks.get(uuid) ?? null);
    if (looks) this.looks.set(uuid, looks);
    else this.looks.delete(uuid);
    if (before !== JSON.stringify(looks)) this.liveSend(uuid, { type: 'looks', uuid, looks });
  }

  /** One message from a game on the live channel. */
  onLive(socket, data) {
    const member = this.live.get(socket);
    if (!member) return;
    let payload;
    try {
      payload = JSON.parse(String(data));
    } catch {
      return;
    }
    if (payload.type === 'watch') {
      const ids = Array.isArray(payload.uuids) ? payload.uuids : [];
      const before = member.watching ?? new Set();
      member.watching = new Set(ids.slice(0, WATCH_MAX).map((raw) => String(raw ?? '').replace(/-/g, '').toLowerCase()).filter((id) => /^[a-f0-9]{32}$/.test(id)));
      // Badges for players just come into view, straight away rather than at the game's next
      // /ranks round.
      for (const id of member.watching) {
        if (before.has(id) || id === member.uuid || !this.playingNow(id)) continue;
        socket.send(JSON.stringify({ type: 'rank', uuid: id, rank: this.rankOf(id) }));
      }
      return;
    }
    if (payload.type === 'emote') {
      const id = String(payload.id ?? '');
      if (!EMOTES.includes(id)) return;
      const now = Date.now();
      member.times = member.times.filter((t) => now - t < 60_000);
      if (member.times.length >= EMOTES_PER_MINUTE || (member.times.length && now - member.times[member.times.length - 1] < EMOTE_GAP_MS)) return;
      member.times.push(now);
      this.liveSend(member.uuid, { type: 'emote', uuid: member.uuid, id, at: now });
      return;
    }
    if (payload.type === 'stop') this.liveSend(member.uuid, { type: 'stop', uuid: member.uuid });
  }

  /** Sends to every game watching this player, never back to the player's own games. */
  liveSend(uuid, payload) {
    const data = JSON.stringify(payload);
    for (const [socket, member] of this.live) {
      if (member.uuid === uuid || !member.watching.has(uuid)) continue;
      try {
        socket.send(data);
      } catch {
        this.live.delete(socket);
      }
    }
  }

  /** Whether this account's game said it runs Snowball Client within the last few minutes. */
  playingNow(uuid) {
    const at = this.inClient.get(uuid);
    if (at === undefined) return false;
    if (Date.now() - at <= PRESENCE_TTL_MS) return true;
    this.inClient.delete(uuid);
    return false;
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
      if (can(person, 'bugs.triage')) other.send(JSON.stringify({ type: 'bugs', bugs: this.bugs.slice(0, 50) }));
    }
  }

  async onAnnounce(payload) {
    this.announcement = typeof payload.text === 'string' && payload.text.trim() ? payload.text.trim().slice(0, 300) : null;
    await this.state.storage.put('announcement', this.announcement);
    this.broadcast({ type: 'announce', text: this.announcement });
  }

  async onAdmin(payload, socket, member) {
    const needed = {
      people: 'users.view', bugs: 'bugs.triage', 'bug-status': 'bugs.triage', flag: 'flags.manage',
      clear: 'chat.moderate', mute: 'chat.moderate', unmute: 'chat.moderate',
      'rank-set': 'ranks.manage', 'rank-clear': 'ranks.manage', grant: 'ranks.manage', ungrant: 'ranks.manage',
      history: 'users.view', lookup: 'users.view', ranked: 'users.view',
      capes: 'capes.review', 'cape-review': 'capes.review',
    }[payload.action];
    // Every action is answered in the panel, success or not, so a refusal never lands in chat
    // while the panel says it worked.
    const reply = (ok, message) => socket.send(JSON.stringify({ type: 'admin-result', action: String(payload.action ?? ''), ok, message }));
    const nameOf = (id) => this.people.get(id)?.name ?? 'That player';
    if (!needed) return reply(false, 'The panel asked for something this server does not know. Update the worker.');
    if (!can(member, needed)) return reply(false, `Your rank cannot do that (it needs ${needed}).`);

    if (payload.action === 'ranked') {
      // Everyone holding a rank above plain Snowball, with who gave it, for the Ranks overview.
      const owner = String(this.env.ADMIN_UUID ?? '').replace(/-/g, '');
      const people = [...this.ranks].map(([uuid, given]) => ({ uuid, name: this.people.get(uuid)?.name ?? given.name ?? '', rank: given.rank, at: given.at ?? 0, byName: given.byName ?? '' }));
      if (owner && !this.ranks.has(owner)) people.push({ uuid: owner, name: this.people.get(owner)?.name ?? '', rank: 'owner', at: 0, byName: '' });
      people.sort((a, b) => RANKS.indexOf(b.rank) - RANKS.indexOf(a.rank) || a.name.localeCompare(b.name));
      return socket.send(JSON.stringify({ type: 'ranked', people }));
    }

    if (payload.action === 'capes') return socket.send(JSON.stringify({ type: 'capes', capes: this.pendingCapes() }));
    if (payload.action === 'cape-review') {
      const id = String(payload.uuid ?? '').replace(/-/g, '');
      const entry = this.capes.get(id);
      if (!entry?.request || entry.request.status !== 'pending') {
        reply(false, 'Somebody already reviewed that design.');
        return socket.send(JSON.stringify({ type: 'capes', capes: this.pendingCapes() }));
      }
      const approved = payload.approve === true;
      if (approved) {
        entry.approved = { pixels: entry.request.pixels, version: (entry.approved?.version ?? 0) + 1, at: Date.now(), by: member.uuid };
        entry.request = null;
        // Someone already wearing their design sees the new one straight away.
        const worn = this.looks.get(id);
        if (worn?.cape === 'custom') this.setLooks(id, { cape: 'custom' });
      } else {
        entry.request.status = 'denied';
        entry.request.reason = String(payload.reason ?? '').replace(INVISIBLE, '').trim().slice(0, 140);
      }
      entry.reviewedBy = member.name;
      await this.state.storage.put('capes', [...this.capes]);
      reply(true, approved ? `${entry.name || nameOf(id)}'s cape is approved and showing.` : `${entry.name || nameOf(id)}'s cape is denied; they will see why.`);
      this.tellReviewers();
      return;
    }

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
        reply(true, `Marked ${payload.status}.`);
      } else {
        reply(false, bug ? 'That is not a state a report can be in.' : 'That report is gone.');
      }
      return socket.send(JSON.stringify({ type: 'bugs', bugs: this.bugs.slice(0, 50) }));
    }
    if (payload.action === 'flag') {
      const key = String(payload.key ?? '').trim().slice(0, 40);
      if (!key) return reply(false, 'A flag needs a name.');
      if (payload.value === null) delete this.flags[key];
      else this.flags[key] = payload.value === true || payload.value === 'on';
      await this.state.storage.put('flags', this.flags);
      reply(true, payload.value === null ? `${key} is back to its default.` : `${key} is ${this.flags[key] ? 'on' : 'off'} for everyone.`);
      // Everyone hears about a feature being switched on or off straight away.
      return this.broadcast({ type: 'flags', flags: this.flags });
    }
    if (payload.action === 'clear') {
      this.history = [];
      await this.state.storage.put('history', this.history);
      reply(true, 'Chat cleared for everyone.');
      return this.broadcast({ type: 'history', messages: [] });
    }
    const uuid = String(payload.uuid ?? '').replace(/-/g, '');
    if (!/^[a-f0-9]{32}$/.test(uuid)) return reply(false, 'That is not an account id.');
    if (payload.action === 'rank-set' || payload.action === 'rank-clear' || payload.action === 'grant' || payload.action === 'ungrant') {
      if (uuid === String(this.env.ADMIN_UUID ?? '').replace(/-/g, '')) {
        return reply(false, 'The owner account is set in the worker settings, not here.');
      }
      if (payload.action === 'rank-set' && !RANKS.includes(payload.rank)) return reply(false, `There is no rank called ${String(payload.rank ?? '')}.`);
      let rank = 'snowball';
      if (payload.action === 'rank-set') rank = payload.rank;
      else if (payload.action === 'grant') rank = 'plus';
      if (rank === 'owner') return reply(false, 'Owner cannot be handed out.');

      await this.setRank(uuid, rank, member);
      socket.send(JSON.stringify({ type: 'rank-set', uuid, rank, history: this.rankLog.get(uuid) ?? [] }));
      reply(true, rank === 'snowball' ? `${nameOf(uuid)} is back to plain Snowball.` : `${nameOf(uuid)} is now ${RANK_NAMES[rank] ?? rank}. They see it straight away.`);
      return;
    }
    if (payload.action === 'unmute') {
      this.mutes.delete(uuid);
      await this.state.storage.put('mutes', [...this.mutes]);
      return reply(true, `${nameOf(uuid)} can talk again.`);
    }
    if (payload.action === 'mute') {
      const minutes = Number.isFinite(payload.minutes) ? Math.min(1440, Math.max(1, Math.round(payload.minutes))) : MUTE_DEFAULT_MINUTES;
      this.mutes.set(uuid, Date.now() + minutes * 60_000);
      await this.state.storage.put('mutes', [...this.mutes]);
      return reply(true, `${nameOf(uuid)} is muted for ${minutes} minute${minutes === 1 ? '' : 's'}.`);
    }
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
