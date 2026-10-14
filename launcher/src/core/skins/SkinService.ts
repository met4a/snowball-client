import { readFile, stat } from 'node:fs/promises';
import { basename } from 'node:path';
import { AuthError } from '../auth/AuthManager.js';
import { getLogger } from '../logging/Logger.js';
import { MAX_SKIN_BYTES, NOT_A_SKIN, SKIN_GONE, SkinError, skinPngSize, TOO_BIG, type SkinEntry, type SkinLibrary, type SkinVariant } from './SkinLibrary.js';

const log = getLogger('skins');

const SERVICES = 'https://api.minecraftservices.com';
const PROFILE = `${SERVICES}/minecraft/profile`;
const UPLOAD = `${SERVICES}/minecraft/profile/skins`;
const NAME_LOOKUP = 'https://api.mojang.com/users/profiles/minecraft/';
const SESSION_PROFILE = 'https://sessionserver.mojang.com/session/minecraft/profile/';
const TEXTURES_HOST = 'textures.minecraft.net';
const USERNAME = /^[A-Za-z0-9_]{3,16}$/;
const HASH = /^[0-9a-f]{1,128}$/i;
const TIMEOUT_MS = 15_000;
/** A link may move a couple of times (a short link, then a CDN) but no more. */
const MAX_REDIRECTS = 3;

/** What players are told, word for word as design/skins.md has it, so the launcher and the game agree. */
export const SKIN_MESSAGES = {
  signIn: 'Sign in with a Microsoft account to change your skin.',
  expired: 'Your Minecraft login has expired. Restart the launcher (or the game) and try again.',
  limited: 'Mojang is limiting skin changes right now. Try again in a minute.',
  refused: 'Mojang refused this skin.',
  unreachable: 'Could not reach Mojang. Check your connection and try again.',
} as const;

type FetchLike = (
  url: string,
  init?: { method?: string; headers?: Record<string, string>; body?: FormData; redirect?: 'error' | 'follow' | 'manual'; signal?: AbortSignal },
) => Promise<Response>;

/** What skins need from AuthManager: an account's session, and a way to drop one Mojang refused. */
export interface SkinSessions {
  session(accountId: string): Promise<{ type: 'msa' | 'offline'; accessToken: string }>;
  invalidateSession(accountId: string): Promise<void>;
}

/** The skin an account wears: Mojang's hash for the picture, and the arms it is worn with. */
export interface WornSkin {
  texture: string;
  variant: SkinVariant;
}

export interface MinecraftProfile {
  id: string;
  name: string;
  /** The skin being worn; null when the profile has none (the default skin). */
  active: (WornSkin & { url: string }) | null;
}

/** The last path segment of a skin's textures.minecraft.net URL: the hash Mojang knows it by. */
export function textureHash(url: string): string | null {
  try {
    const hash = new URL(url).pathname.split('/').pop() ?? '';
    return HASH.test(hash) ? hash : null;
  } catch {
    return null;
  }
}

/** The account's id, name and worn skin from a GET /minecraft/profile answer. */
export function parseProfile(body: unknown): MinecraftProfile | null {
  const p = body as { id?: unknown; name?: unknown; skins?: unknown } | null;
  if (!p || typeof p.id !== 'string' || typeof p.name !== 'string') return null;
  const worn = (Array.isArray(p.skins) ? p.skins : []).find((s) => s?.state === 'ACTIVE') as { url?: unknown; variant?: unknown } | undefined;
  const url = typeof worn?.url === 'string' ? worn.url.replace(/^http:\/\//i, 'https://') : '';
  const texture = textureHash(url);
  return {
    id: p.id,
    name: p.name,
    active: texture ? { texture, url, variant: String(worn?.variant).toUpperCase() === 'SLIM' ? 'slim' : 'classic' } : null,
  };
}

/** The SKIN texture of a sessionserver profile: its https URL, hash and arm model. */
export function parseTextures(body: unknown): { url: string; texture: string; variant: SkinVariant } | null {
  const properties = (body as { properties?: unknown } | null)?.properties;
  const property = (Array.isArray(properties) ? properties : []).find((p) => p?.name === 'textures') as { value?: unknown } | undefined;
  if (typeof property?.value !== 'string') return null;
  try {
    const skin = JSON.parse(Buffer.from(property.value, 'base64').toString('utf8'))?.textures?.SKIN;
    if (typeof skin?.url !== 'string') return null;
    const url = skin.url.replace(/^http:\/\//i, 'https://');
    const texture = textureHash(url);
    return texture ? { url, texture, variant: skin.metadata?.model === 'slim' ? 'slim' : 'classic' } : null;
  } catch {
    return null;
  }
}

/** Offline accounts carry "0"; a real Minecraft token is far longer than 20 characters. */
const usableToken = (token: string | undefined): token is string => !!token && token !== '0' && token.length >= 20;

/** The body, or null as soon as it passes `max` bytes: it is counted as it arrives, not after. */
async function readCapped(res: Response, max: number): Promise<Buffer | null> {
  const declared = Number(res.headers.get('content-length') ?? NaN);
  if (Number.isFinite(declared) && declared > max) {
    await res.body?.cancel().catch(() => undefined);
    return null;
  }
  if (!res.body) return Buffer.alloc(0);
  const reader = res.body.getReader();
  const chunks: Uint8Array[] = [];
  let size = 0;
  for (;;) {
    const { done, value } = await reader.read();
    if (done) return Buffer.concat(chunks);
    size += value.byteLength;
    if (size > max) {
      await reader.cancel().catch(() => undefined);
      return null;
    }
    chunks.push(value);
  }
}

/**
 * Adds skins from Mojang and changes the account's real skin through Mojang's API (design/skins.md).
 * The Minecraft token only ever goes to api.minecraftservices.com; it is never logged or written down.
 */
export class SkinService {
  /** The skin each account wears, as Mojang last said. Memory only. */
  private readonly worn = new Map<string, WornSkin | null>();
  private readonly syncing = new Map<string, Promise<{ equipped: string | null; added: SkinEntry | null }>>();
  private equipping = false;

  constructor(
    readonly library: SkinLibrary,
    private readonly sessions: SkinSessions,
    private readonly fetchImpl: FetchLike = (url, init) => fetch(url, init),
  ) {}

  /** The worn skin from the last time Mojang was asked, or null (not asked yet, or a default skin). */
  equippedFor(accountId: string): WornSkin | null {
    return this.worn.get(accountId) ?? null;
  }

  /** Any request, cut off after a while; failing to connect becomes the one sentence players see. */
  private async request(url: string, init: Parameters<FetchLike>[1] = {}): Promise<Response> {
    try {
      return await this.fetchImpl(url, { ...init, signal: AbortSignal.timeout(TIMEOUT_MS) });
    } catch (err) {
      log.warn('A skin request failed', { host: new URL(url).host, error: (err as Error).message });
      throw new SkinError(SKIN_MESSAGES.unreachable);
    }
  }

  /** A call to Minecraft's services with the account's token. It never follows a redirect anywhere else. */
  private authorised(url: string, token: string, init: { method?: string; body?: FormData } = {}): Promise<Response> {
    if (!url.startsWith(`${SERVICES}/`)) throw new Error('The Minecraft token only goes to api.minecraftservices.com.');
    return this.request(url, { ...init, headers: { Authorization: `Bearer ${token}`, Accept: 'application/json' }, redirect: 'error' });
  }

  private async token(accountId: string | null): Promise<string> {
    if (!accountId) throw new SkinError(SKIN_MESSAGES.signIn);
    let session: Awaited<ReturnType<SkinSessions['session']>>;
    try {
      session = await this.sessions.session(accountId);
    } catch (err) {
      // Sign-in problems say what to do; a refresh that could not reach Microsoft says what players see.
      if (err instanceof AuthError || err instanceof SkinError) throw err;
      log.warn('Could not renew the Minecraft session for a skin change', { error: (err as Error).message });
      throw new SkinError(SKIN_MESSAGES.unreachable);
    }
    if (session.type !== 'msa' || !usableToken(session.accessToken)) throw new SkinError(SKIN_MESSAGES.signIn);
    return session.accessToken;
  }

  /**
   * A 401: Minecraft refused the token. The session is dropped so the next try signs in afresh,
   * and the player is told; the request itself is not sent again from here.
   */
  private async refused(accountId: string, res: Response): Promise<never> {
    await res.body?.cancel().catch(() => undefined);
    await this.sessions.invalidateSession(accountId).catch(() => undefined);
    throw new SkinError(SKIN_MESSAGES.expired);
  }

  /** The account's Minecraft profile, including the skin it is wearing. */
  async profile(accountId: string | null): Promise<MinecraftProfile> {
    let res = await this.authorised(PROFILE, await this.token(accountId));
    // Reading the profile changes nothing, so it alone is asked again once with a renewed session.
    if (res.status === 401) {
      await res.body?.cancel().catch(() => undefined);
      await this.sessions.invalidateSession(accountId!);
      res = await this.authorised(PROFILE, await this.token(accountId));
      if (res.status === 401) await this.refused(accountId!, res);
    }
    if (res.status === 404) throw new SkinError('This Microsoft account does not own Minecraft: Java Edition.');
    if (res.status === 429) throw new SkinError(SKIN_MESSAGES.limited);
    const profile = res.status === 200 ? parseProfile(await res.json().catch(() => null)) : null;
    if (!profile) throw new SkinError(SKIN_MESSAGES.unreachable);
    this.worn.set(accountId!, profile.active ? { texture: profile.active.texture, variant: profile.active.variant } : null);
    return profile;
  }

  /**
   * Asks Mojang what the account is wearing, and adds that skin to the library as "<Name>'s skin"
   * when no listed skin has it, so changing skin never loses the old one.
   */
  sync(accountId: string): Promise<{ equipped: string | null; added: SkinEntry | null }> {
    // Opening the page twice quickly must not add the same skin twice.
    const running = this.syncing.get(accountId);
    if (running) return running;
    const run = (async () => {
      const profile = await this.profile(accountId);
      const worn = profile.active;
      if (!worn || (await this.library.list()).some((s) => s.texture === worn.texture)) return { equipped: worn?.texture ?? null, added: null };
      const png = await this.download(worn.url, true);
      const added = await this.library.add(png, { name: `${profile.name}'s skin`, source: 'current', variant: worn.variant, texture: worn.texture });
      log.info('Added the skin this account is wearing to the library');
      return { equipped: worn.texture, added };
    })().finally(() => this.syncing.delete(accountId));
    this.syncing.set(accountId, run);
    return run;
  }

  /**
   * Downloads a skin PNG: https only, every redirect included, at most 64 KiB, and it must pass the
   * PNG check. Mojang's own textures must also come from textures.minecraft.net.
   */
  private async download(url: string, fromMojang: boolean): Promise<Buffer> {
    let target = new URL(url);
    for (let hop = 0; ; hop++) {
      if (target.protocol !== 'https:') throw new SkinError('Only https:// links can be added.');
      if (fromMojang && target.hostname !== TEXTURES_HOST) throw new SkinError('Mojang named a skin picture somewhere other than its own servers, so it was not downloaded.');
      const res = await this.request(target.href, { redirect: 'manual' });
      const location = res.headers.get('location');
      if (res.status >= 300 && res.status < 400 && location && hop < MAX_REDIRECTS) {
        await res.body?.cancel().catch(() => undefined);
        target = new URL(location, target);
        continue;
      }
      if (res.status === 429) throw new SkinError(SKIN_MESSAGES.limited);
      if (res.status !== 200) {
        await res.body?.cancel().catch(() => undefined);
        throw new SkinError(fromMojang ? SKIN_MESSAGES.unreachable : `That link did not give a skin (the server answered ${res.status}).`);
      }
      let png: Buffer | null;
      try {
        png = await readCapped(res, MAX_SKIN_BYTES);
      } catch {
        throw new SkinError(SKIN_MESSAGES.unreachable);
      }
      if (!png) throw new SkinError(TOO_BIG);
      if (!skinPngSize(png)) throw new SkinError(NOT_A_SKIN);
      return png;
    }
  }

  /** Adds the skin a player is wearing, found by their Minecraft name. A skin already listed is returned instead. */
  async addByUsername(name: string): Promise<{ skin: SkinEntry; existed: boolean }> {
    if (!USERNAME.test(name)) throw new SkinError('Minecraft names are 3 to 16 letters, numbers or underscores.');
    const lookup = await this.request(`${NAME_LOOKUP}${encodeURIComponent(name)}`);
    if (lookup.status === 429) throw new SkinError(SKIN_MESSAGES.limited);
    if (lookup.status === 404 || lookup.status === 204) throw new SkinError(`Mojang has no player called ${name}.`);
    const player = lookup.status === 200 ? ((await lookup.json().catch(() => null)) as { id?: unknown; name?: unknown } | null) : null;
    if (!player) throw new SkinError(SKIN_MESSAGES.unreachable);
    if (typeof player.id !== 'string' || !/^[0-9a-f]{32}$/i.test(player.id)) throw new SkinError(`Mojang has no player called ${name}.`);
    const shownName = typeof player.name === 'string' && USERNAME.test(player.name) ? player.name : name;

    const profile = await this.request(`${SESSION_PROFILE}${player.id}`);
    if (profile.status === 429) throw new SkinError(SKIN_MESSAGES.limited);
    if (profile.status !== 200) throw new SkinError(SKIN_MESSAGES.unreachable);
    const textures = parseTextures(await profile.json().catch(() => null));
    if (!textures) throw new SkinError(`${shownName} wears one of Minecraft's default skins, so there is nothing to add.`);

    const existing = (await this.library.list()).find((s) => s.texture === textures.texture);
    if (existing) return { skin: existing, existed: true };
    const png = await this.download(textures.url, true);
    const skin = await this.library.add(png, { name: `${shownName}'s skin`, source: 'username', variant: textures.variant, texture: textures.texture });
    return { skin, existed: false };
  }

  /** Adds a skin from an https link, named after the link's last part. */
  async addByUrl(url: string): Promise<{ skin: SkinEntry; existed: boolean }> {
    let link: URL;
    try {
      link = new URL(url);
    } catch {
      throw new SkinError('That is not a link. Paste the whole address, starting with https://.');
    }
    if (link.protocol !== 'https:') throw new SkinError('Only https:// links can be added.');
    // A link straight to Mojang's textures carries the hash, so Equipped can be shown for it.
    const texture = link.hostname === TEXTURES_HOST ? textureHash(link.href) : null;
    const existing = texture ? (await this.library.list()).find((s) => s.texture === texture) : undefined;
    if (existing) return { skin: existing, existed: true };
    const png = await this.download(link.href, false);
    let last = link.pathname.split('/').pop() ?? '';
    try {
      last = decodeURIComponent(last);
    } catch {
      // Kept as it was written.
    }
    const skin = await this.library.add(png, { name: last.replace(/\.png$/i, '') || 'Skin', source: 'url', texture });
    return { skin, existed: false };
  }

  /** Adds a PNG file the player picked in the launcher's own file dialog. */
  async addFile(path: string): Promise<SkinEntry> {
    const size = (await stat(path)).size;
    if (size > MAX_SKIN_BYTES) throw new SkinError(TOO_BIG);
    return this.library.add(await readFile(path), { name: basename(path).replace(/\.png$/i, ''), source: 'file' });
  }

  /**
   * Makes a library skin the account's real skin: a multipart POST of its PNG and arm model.
   * Mojang answers with the new profile, and the worn skin's hash is stored on the entry, which
   * is how the launcher and the game both know it is the one equipped.
   */
  async equip(accountId: string | null, skinId: string): Promise<{ equipped: WornSkin | null }> {
    const token = await this.token(accountId);
    if (this.equipping) throw new SkinError('A skin is already being equipped.');
    this.equipping = true;
    try {
      const skin = (await this.library.list()).find((s) => s.id === skinId);
      if (!skin) throw new SkinError(SKIN_GONE);
      const png = await this.library.png(skin.id);
      const form = new FormData();
      form.append('variant', skin.variant);
      form.append('file', new Blob([new Uint8Array(png)], { type: 'image/png' }), 'skin.png');
      // Sent once and never again by itself (design/skins.md): after a timeout or an error the
      // skin may already have changed, and only the player decides whether to press Equip again.
      const res = await this.authorised(UPLOAD, token, { method: 'POST', body: form });
      if (res.status === 401) await this.refused(accountId!, res);
      if (res.status === 429) throw new SkinError(SKIN_MESSAGES.limited);
      if (res.status === 400) {
        const body = (await res.json().catch(() => null)) as { errorMessage?: unknown } | null;
        const said = typeof body?.errorMessage === 'string' ? body.errorMessage.trim().slice(0, 200) : '';
        throw new SkinError(said || SKIN_MESSAGES.refused);
      }
      if (res.status !== 200) throw new SkinError(SKIN_MESSAGES.unreachable);
      let active = parseProfile(await res.json().catch(() => null))?.active ?? null;
      // The answer should be the new profile; if it ever is not, the profile says what is worn now.
      if (!active) active = (await this.profile(accountId).catch(() => null))?.active ?? null;
      const equipped = active ? { texture: active.texture, variant: active.variant } : null;
      this.worn.set(accountId!, equipped);
      // Deleted while it was being equipped: Mojang has it, the library no longer does.
      if (equipped) await this.library.update(skin.id, { texture: equipped.texture }).catch(() => undefined);
      log.info('Equipped a skin', { variant: skin.variant });
      return { equipped };
    } finally {
      this.equipping = false;
    }
  }
}
