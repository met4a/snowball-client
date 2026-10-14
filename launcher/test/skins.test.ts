import { existsSync, readdirSync, readFileSync, statSync, utimesSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { inflateSync } from 'node:zlib';
import { describe, expect, it, vi } from 'vitest';
import { AuthError } from '../src/core/auth/AuthManager.js';
import { logSink } from '../src/core/logging/Logger.js';
import { detectVariant, MAX_SKIN_BYTES, NOT_A_SKIN, SKIN_GONE, SkinLibrary, skinPngSize, TOO_BIG, type SkinDecoder } from '../src/core/skins/SkinLibrary.js';
import { parseProfile, SKIN_MESSAGES, SkinService, type SkinSessions } from '../src/core/skins/SkinService.js';
import { makePng, tempDir } from './helpers.js';

type Rgba = [number, number, number, number];

/** A 64x64 (or 64x32) skin: opaque everywhere, or with the columns a slim skin leaves clear. */
function skinPng(options: { height?: number; slim?: boolean; colour?: Rgba } = {}): Buffer {
  const { height = 64, slim = false, colour = [90, 140, 200, 255] } = options;
  const clear = (x: number, y: number) => slim && ((x >= 50 && x <= 51 && y >= 16 && y <= 19) || (x >= 54 && x <= 55 && y >= 20 && y <= 31));
  return makePng(64, height, (x, y) => (clear(x, y) ? [0, 0, 0, 0] : colour));
}

/** Reads back the PNGs makePng writes (no filtering), standing in for Electron's nativeImage. */
const decode: SkinDecoder = (png) => {
  const width = png.readUInt32BE(16);
  const height = png.readUInt32BE(20);
  const idat: Buffer[] = [];
  for (let at = 8; at < png.length;) {
    const length = png.readUInt32BE(at);
    if (png.toString('latin1', at + 4, at + 8) === 'IDAT') idat.push(png.subarray(at + 8, at + 8 + length));
    at += 12 + length;
  }
  const raw = inflateSync(Buffer.concat(idat));
  const pixels = new Uint8Array(width * height * 4);
  for (let y = 0; y < height; y++) pixels.set(raw.subarray(y * (width * 4 + 1) + 1, (y + 1) * (width * 4 + 1)), y * width * 4);
  return { width, height, pixels };
};

/** A clock that moves on a second every time it is read, so every skin is newer than the last. */
function clock(start = 1_700_000_000_000) {
  let now = start;
  return () => (now += 1000);
}

const readList = (dir: string) => JSON.parse(readFileSync(join(dir, 'skins.json'), 'utf8')) as { schemaVersion: number; skins: Array<Record<string, unknown>> };

describe('the skin library', () => {
  it('writes skins.json and <id>.png as design/skins.md has them, and reads them back', async () => {
    const dir = tempDir();
    const library = new SkinLibrary(dir, decode, clock());
    const png = skinPng();
    const added = await library.add(png, { name: "Patrol's skin", source: 'username', texture: 'ab12cd' });

    expect(added.id).toMatch(/^[0-9a-f]{12}$/);
    expect(readFileSync(join(dir, `${added.id}.png`)).equals(png)).toBe(true);
    const file = readList(dir);
    expect(file.schemaVersion).toBe(1);
    expect(file.skins).toEqual([{ id: added.id, name: "Patrol's skin", variant: 'classic', favourite: false, added: added.added, source: 'username', texture: 'ab12cd' }]);

    // Another reader (the game, or the launcher's next run) sees the same skin.
    expect(await new SkinLibrary(dir).list()).toEqual([added]);
    expect((await library.png(added.id)).equals(png)).toBe(true);
  });

  it('puts favourites first, then the newest', async () => {
    const library = new SkinLibrary(tempDir(), decode, clock());
    const oldest = await library.add(skinPng(), { name: 'Oldest', source: 'file' });
    const middle = await library.add(skinPng(), { name: 'Middle', source: 'file' });
    const newest = await library.add(skinPng(), { name: 'Newest', source: 'file' });
    expect((await library.list()).map((s) => s.name)).toEqual(['Newest', 'Middle', 'Oldest']);

    await library.update(oldest.id, { favourite: true });
    expect((await library.list()).map((s) => s.name)).toEqual(['Oldest', 'Newest', 'Middle']);
    await library.update(middle.id, { favourite: true });
    expect((await library.list()).map((s) => s.name)).toEqual(['Middle', 'Oldest', 'Newest']);
    expect(newest.favourite).toBe(false);
  });

  it('renames, stars, changes arms and deletes, touching nothing else', async () => {
    const dir = tempDir();
    const library = new SkinLibrary(dir, decode, clock());
    const keep = await library.add(skinPng(), { name: 'Keep', source: 'file' });
    const skin = await library.add(skinPng(), { name: 'Before', source: 'url' });

    // The game wrote fields and entries this version does not know; they must survive.
    const file = readList(dir);
    (file as Record<string, unknown>).gameNote = 'kept';
    file.skins[0].worn = 3;
    file.skins.push({ id: 'not-an-id', name: 'From the future' });
    writeFileSync(join(dir, 'skins.json'), JSON.stringify(file));

    const renamed = await library.update(skin.id, { name: '  A name\u0007 far longer than thirty-two characters  ' });
    expect(renamed.name).toBe('A name far longer than thirty-tw');
    expect((await library.update(skin.id, { favourite: true })).favourite).toBe(true);
    expect((await library.update(skin.id, { variant: 'slim' })).variant).toBe('slim');

    const after = readList(dir) as unknown as Record<string, unknown> & { skins: Array<Record<string, unknown>> };
    expect(after.gameNote).toBe('kept');
    expect(after.skins.find((s) => s.id === keep.id)).toMatchObject({ name: 'Keep', worn: 3 });
    expect(after.skins.find((s) => s.id === 'not-an-id')).toEqual({ id: 'not-an-id', name: 'From the future' });
    expect(after.skins.find((s) => s.id === skin.id)).toMatchObject({ name: 'A name far longer than thirty-tw', favourite: true, variant: 'slim', source: 'url' });

    await library.remove(skin.id);
    expect(existsSync(join(dir, `${skin.id}.png`))).toBe(false);
    expect((await library.list()).map((s) => s.id)).toEqual([keep.id]);
    await expect(library.update(skin.id, { name: 'Gone' })).rejects.toThrow(SKIN_GONE);
  });

  it('carries on from a damaged skins.json, keeping the damaged file aside', async () => {
    const dir = tempDir();
    writeFileSync(join(dir, 'skins.json'), '{ "schemaVersion": 1, "skins": [ broken');
    const library = new SkinLibrary(dir, decode, clock());
    expect(await library.list()).toEqual([]);

    const skin = await library.add(skinPng(), { name: 'Fresh', source: 'file' });
    expect((await library.list()).map((s) => s.id)).toEqual([skin.id]);
    expect(readFileSync(join(dir, 'skins.json.damaged'), 'utf8')).toContain('broken');
  });

  it('skips entries that break the rules or have lost their picture', async () => {
    const dir = tempDir();
    const library = new SkinLibrary(dir, decode, clock());
    const good = await library.add(skinPng(), { name: 'Good', source: 'file' });
    const lost = await library.add(skinPng(), { name: 'Lost', source: 'file' });
    const file = readList(dir);
    file.skins.push(
      { id: 'ABCDEF123456', name: 'Upper case', variant: 'classic' },
      { id: 'abcdef123456', name: '', variant: 'classic' },
      { id: 'abcdef654321', name: 'Odd arms', variant: 'wide' },
    );
    writeFileSync(join(dir, 'skins.json'), JSON.stringify(file));
    for (const id of ['abcdef123456', 'abcdef654321']) writeFileSync(join(dir, `${id}.png`), skinPng());
    writeFileSync(join(dir, `${lost.id}.png`), Buffer.alloc(0));
    const { rmSync } = await import('node:fs');
    rmSync(join(dir, `${lost.id}.png`));
    // Backdate the stray pictures, which the library would otherwise leave while they settle.
    for (const id of ['abcdef123456', 'abcdef654321']) utimesSync(join(dir, `${id}.png`), new Date(0), new Date(0));

    const listed = await new SkinLibrary(dir, decode, clock(2_000_000_000_000)).list();
    // The two pictures whose entries were invalid are taken in again as dropped files; nothing else.
    expect(listed.map((s) => s.name).sort()).toEqual(['Good', 'abcdef123456', 'abcdef654321']);
    expect(listed.find((s) => s.id === good.id)?.name).toBe('Good');
  });

  it('takes in PNGs dropped into the folder, and leaves anything else alone', async () => {
    const dir = tempDir();
    writeFileSync(join(dir, 'Frost Knight.png'), skinPng({ slim: true }));
    writeFileSync(join(dir, 'Old Style.png'), skinPng({ height: 32 }));
    writeFileSync(join(dir, `${'A very long file name for a skin that goes on'}.png`), skinPng());
    writeFileSync(join(dir, 'notes.png'), 'not a picture');
    writeFileSync(join(dir, 'huge.png'), makePng(64, 128, () => [1, 2, 3, 255]));

    const library = new SkinLibrary(dir, decode, clock());
    const skins = await library.list();
    expect(skins.map((s) => [s.name, s.variant, s.source]).sort()).toEqual([
      ['A very long file name for a skin', 'classic', 'folder'],
      ['Frost Knight', 'slim', 'folder'],
      ['Old Style', 'classic', 'folder'],
    ]);
    const files = readdirSync(dir);
    expect(files).toEqual(expect.arrayContaining(['notes.png', 'huge.png', ...skins.map((s) => `${s.id}.png`)]));
    expect(files).not.toContain('Frost Knight.png');
    // Read again: nothing is taken in twice.
    expect(await library.list()).toHaveLength(3);
  });

  it('leaves a just-written picture named like a skin, which the game may be about to list', async () => {
    const dir = tempDir();
    writeFileSync(join(dir, 'abcdef012345.png'), skinPng());
    const library = new SkinLibrary(dir, decode, () => Date.now());
    expect(await library.list()).toEqual([]);
    utimesSync(join(dir, 'abcdef012345.png'), new Date(0), new Date(0));
    expect(await library.list()).toHaveLength(1);
  });

  it('writes through a temporary file and a rename, so a reader never sees half a list', async () => {
    const dir = tempDir();
    const library = new SkinLibrary(dir, decode, clock());
    const skin = await library.add(skinPng(), { name: 'First', source: 'file' });
    const list = statSync(join(dir, 'skins.json')).ino;
    const picture = statSync(join(dir, `${skin.id}.png`)).ino;

    await library.update(skin.id, { favourite: true });
    await library.add(skinPng(), { name: 'Second', source: 'file' });
    // A whole new file took the old one's place; the old one was never written over where it stood.
    expect(statSync(join(dir, 'skins.json')).ino).not.toBe(list);
    expect(statSync(join(dir, `${skin.id}.png`)).ino).toBe(picture);
    // Nothing is left lying about, and nothing half-written is named like a skin the other side would take in.
    expect(readdirSync(dir).filter((f) => !/^([0-9a-f]{12}\.png|skins\.json)$/.test(f))).toEqual([]);
  });

  it('keeps every change when the launcher and the game both write, and when clicks come together', async () => {
    const dir = tempDir();
    const launcherSide = new SkinLibrary(dir, decode, clock());
    const gameSide = new SkinLibrary(dir, decode, clock(1_800_000_000_000));
    const mine = await launcherSide.add(skinPng(), { name: 'Launcher', source: 'file' });
    const theirs = await gameSide.add(skinPng(), { name: 'Game', source: 'url' });
    await launcherSide.update(theirs.id, { favourite: true });
    await gameSide.update(mine.id, { name: 'Renamed in game' });
    expect((await launcherSide.list()).map((s) => [s.name, s.favourite])).toEqual([['Game', true], ['Renamed in game', false]]);

    // Five adds at once from one side: each one re-reads what the one before it wrote.
    await Promise.all([1, 2, 3, 4, 5].map((n) => launcherSide.add(skinPng(), { name: `Quick ${n}`, source: 'file' })));
    expect(await gameSide.list()).toHaveLength(7);
  });

  it('checks a PNG by its header: 64x64 or 64x32, at most 64 KiB', async () => {
    expect(skinPngSize(skinPng())).toEqual({ width: 64, height: 64 });
    expect(skinPngSize(skinPng({ height: 32 }))).toEqual({ width: 64, height: 32 });
    expect(skinPngSize(makePng(32, 32, () => [0, 0, 0, 255]))).toBeNull();
    expect(skinPngSize(makePng(64, 48, () => [0, 0, 0, 255]))).toBeNull();
    expect(skinPngSize(Buffer.from('GIF89a, which is not a PNG at all'))).toBeNull();
    const padded = Buffer.concat([skinPng(), Buffer.alloc(MAX_SKIN_BYTES)]);
    expect(skinPngSize(padded)).toBeNull();

    const library = new SkinLibrary(tempDir(), decode, clock());
    await expect(library.add(padded, { name: 'Big', source: 'file' })).rejects.toThrow(TOO_BIG);
    await expect(library.add(makePng(16, 16, () => [0, 0, 0, 255]), { name: 'Small', source: 'file' })).rejects.toThrow(NOT_A_SKIN);
  });

  it('tells slim arms from classic ones by the pixels', () => {
    expect(detectVariant(skinPng({ slim: true }), decode)).toBe('slim');
    expect(detectVariant(skinPng(), decode)).toBe('classic');
    // Old 64x32 skins are always classic, and without a decoder nothing can be told.
    expect(detectVariant(skinPng({ height: 32, colour: [0, 0, 0, 0] }), decode)).toBe('classic');
    expect(detectVariant(skinPng({ slim: true }))).toBe('classic');
    expect(detectVariant(skinPng({ slim: true }), () => { throw new Error('cannot decode'); })).toBe('classic');
  });
});

// ---------- Mojang ----------

const TOKEN = 'eyJ'.padEnd(64, 'x');
const SERVICES = 'api.minecraftservices.com';

interface Call {
  url: string;
  method: string;
  headers: Record<string, string>;
  body?: FormData;
  redirect?: string;
}

/** A pretend Mojang: `route` answers each request; every request is kept to look at afterwards. */
function mojang(route: (call: Call, index: number) => Response | Promise<Response>) {
  const calls: Call[] = [];
  const fetchImpl = async (url: string, init: { method?: string; headers?: Record<string, string>; body?: FormData; redirect?: string } = {}) => {
    const call: Call = { url, method: init.method ?? 'GET', headers: init.headers ?? {}, body: init.body, redirect: init.redirect };
    calls.push(call);
    return route(call, calls.length - 1);
  };
  return { calls, fetchImpl };
}

/** Sessions as AuthManager gives them: each refused token is dropped and the next one is new. */
function sessions(type: 'msa' | 'offline' = 'msa', token = TOKEN) {
  const dropped: string[] = [];
  const store: SkinSessions & { dropped: string[] } = {
    dropped,
    session: async () => ({ type, accessToken: type === 'offline' ? '0' : `${token}-${dropped.length}` }),
    invalidateSession: async (id: string) => {
      dropped.push(id);
    },
  };
  return store;
}

const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json' } });
const profileBody = (texture: string, variant = 'CLASSIC') => ({
  id: '0123456789abcdef0123456789abcdef',
  name: 'Snowy',
  skins: [
    { id: 'old', state: 'INACTIVE', url: 'http://textures.minecraft.net/texture/0000aaaa', variant: 'CLASSIC' },
    { id: 'now', state: 'ACTIVE', url: `http://textures.minecraft.net/texture/${texture}`, variant },
  ],
  capes: [],
});
const texturesProperty = (url: string, model?: string) => ({
  id: 'fedcba9876543210fedcba9876543210',
  name: 'Patrol',
  properties: [{ name: 'textures', value: Buffer.from(JSON.stringify({ textures: { SKIN: { url, ...(model ? { metadata: { model } } : {}) } } })).toString('base64') }],
});

async function service(route: Parameters<typeof mojang>[0], store = sessions()) {
  const dir = tempDir();
  const library = new SkinLibrary(dir, decode, clock());
  const { calls, fetchImpl } = mojang(route);
  return { dir, library, calls, store, skins: new SkinService(library, store, fetchImpl) };
}

describe("Mojang's skin API", () => {
  it("reads the account's profile and the skin it wears", () => {
    expect(parseProfile(profileBody('beef01', 'SLIM'))).toEqual({
      id: '0123456789abcdef0123456789abcdef',
      name: 'Snowy',
      active: { texture: 'beef01', url: 'https://textures.minecraft.net/texture/beef01', variant: 'slim' },
    });
    expect(parseProfile({ id: 'x', name: 'Bare', skins: [] })?.active).toBeNull();
    expect(parseProfile({ error: 'nope' })).toBeNull();
  });

  it('adds the skin the account wears when the library does not have it, once', async () => {
    const worn = skinPng({ slim: true });
    const { skins, library, calls } = await service((call) =>
      call.url.endsWith('/minecraft/profile') ? json(profileBody('beef01', 'SLIM')) : new Response(worn));

    const first = await skins.sync('account');
    expect(first.added).toMatchObject({ name: "Snowy's skin", source: 'current', variant: 'slim', texture: 'beef01' });
    expect(first.equipped).toBe('beef01');
    expect(skins.equippedFor('account')).toEqual({ texture: 'beef01', variant: 'slim' });
    expect((await library.png(first.added!.id)).equals(worn)).toBe(true);
    expect(calls.map((c) => c.url)).toEqual([`https://${SERVICES}/minecraft/profile`, 'https://textures.minecraft.net/texture/beef01']);

    const again = await skins.sync('account');
    expect(again.added).toBeNull();
    expect(await library.list()).toHaveLength(1);
  });

  it('equips with a multipart POST of the arms and the picture, and remembers the new hash', async () => {
    const { skins, library, calls } = await service((call) => (call.method === 'POST' ? json(profileBody('c0ffee', 'SLIM')) : json({}, 500)));
    const png = skinPng({ slim: true });
    const skin = await library.add(png, { name: 'Mine', source: 'file' });

    expect(await skins.equip('account', skin.id)).toEqual({ equipped: { texture: 'c0ffee', variant: 'slim' } });
    expect(calls).toHaveLength(1);
    const [post] = calls;
    expect(post.url).toBe(`https://${SERVICES}/minecraft/profile/skins`);
    expect(post.method).toBe('POST');
    expect(post.headers.Authorization).toBe(`Bearer ${TOKEN}-0`);
    // fetch sets the multipart boundary itself; a Content-Type written here would break it.
    expect(Object.keys(post.headers).map((k) => k.toLowerCase())).not.toContain('content-type');
    expect(post.body).toBeInstanceOf(FormData);
    expect(post.body!.get('variant')).toBe('slim');
    const file = post.body!.get('file') as File;
    expect(file.name).toBe('skin.png');
    expect(file.type).toBe('image/png');
    expect(Buffer.from(await file.arrayBuffer()).equals(png)).toBe(true);
    // And on the wire, as Mojang reads it: two parts, the picture's bytes untouched.
    const wire = new Request('https://example.invalid/', { method: 'POST', body: post.body });
    expect(wire.headers.get('content-type')).toMatch(/^multipart\/form-data; boundary=/);
    const sent = Buffer.from(await wire.arrayBuffer());
    expect(sent.toString('latin1')).toMatch(/Content-Disposition: form-data; name="variant"\r\n\r\nslim\r\n/);
    expect(sent.toString('latin1')).toContain('Content-Disposition: form-data; name="file"; filename="skin.png"\r\nContent-Type: image/png\r\n\r\n');
    expect(sent.includes(png)).toBe(true);

    expect((await library.list())[0].texture).toBe('c0ffee');
    expect(skins.equippedFor('account')).toEqual({ texture: 'c0ffee', variant: 'slim' });
  });

  it('after a 401 says the login has expired, drops the session, and does not send the skin again', async () => {
    const { skins, library, calls, store } = await service(() => json({}, 401));
    const skin = await library.add(skinPng(), { name: 'Mine', source: 'file' });

    await expect(skins.equip('account', skin.id)).rejects.toThrow(SKIN_MESSAGES.expired);
    expect(calls.map((c) => c.method)).toEqual(['POST']);
    // The next press of Equip signs in afresh rather than sending the refused token again.
    expect(store.dropped).toEqual(['account']);
    expect((await library.list())[0].texture).toBeNull();
  });

  it('reads the profile once more with a renewed session after a 401, since reading changes nothing', async () => {
    const { skins, calls, store } = await service((_call, i) => (i === 0 ? json({}, 401) : json(profileBody('aa11'))));
    expect((await skins.profile('account')).active?.texture).toBe('aa11');
    expect(store.dropped).toEqual(['account']);
    expect(calls.map((c) => [c.method, c.headers.Authorization])).toEqual([['GET', `Bearer ${TOKEN}-0`], ['GET', `Bearer ${TOKEN}-1`]]);
  });

  it('says the login has expired when the renewed session is refused too, and stops there', async () => {
    const { skins, calls } = await service(() => json({}, 401));
    await expect(skins.profile('account')).rejects.toThrow(SKIN_MESSAGES.expired);
    await expect(skins.sync('account')).rejects.toThrow(SKIN_MESSAGES.expired);
    expect(calls).toHaveLength(4);
  });

  it.each([
    ['429', () => json({}, 429), SKIN_MESSAGES.limited],
    ["400 with Mojang's words", () => json({ errorMessage: 'Content is not an image' }, 400), 'Content is not an image'],
    ['400 without them', () => json({}, 400), SKIN_MESSAGES.refused],
    ['a server error', () => json({}, 503), SKIN_MESSAGES.unreachable],
    ['no connection', () => Promise.reject(new TypeError('fetch failed')), SKIN_MESSAGES.unreachable],
  ])('turns %s into words a player understands, without trying again', async (_name, answer, message) => {
    const { skins, library, calls } = await service(answer);
    const skin = await library.add(skinPng(), { name: 'Mine', source: 'file' });
    await expect(skins.equip('account', skin.id)).rejects.toThrow(message);
    expect(calls).toHaveLength(1);
  });

  it('will not equip for an offline account, or without one, and asks nobody', async () => {
    // Offline accounts carry "0"; anything under 20 characters is no Minecraft token either.
    for (const [store, account] of [[sessions('offline'), 'offline'], [sessions('msa', 'short'), 'short'], [sessions(), null]] as const) {
      const { skins, library, calls } = await service(() => json(profileBody('aa11')), store);
      const skin = await library.add(skinPng(), { name: 'Mine', source: 'file' });
      await expect(skins.equip(account, skin.id)).rejects.toThrow(SKIN_MESSAGES.signIn);
      if (account) await expect(skins.sync(account)).rejects.toThrow(SKIN_MESSAGES.signIn);
      expect(calls).toEqual([]);
    }
  });

  it('passes sign-in problems on as they are, and a refresh that cannot connect as Mojang being unreachable', async () => {
    const signedOut: SkinSessions = { session: async () => { throw new AuthError('Your Microsoft session expired. Please sign in again.'); }, invalidateSession: async () => undefined };
    const offline: SkinSessions = { session: async () => { throw new TypeError('fetch failed'); }, invalidateSession: async () => undefined };
    const skins = (store: SkinSessions) => new SkinService(new SkinLibrary(tempDir(), decode), store, async () => json({}));
    await expect(skins(signedOut).profile('account')).rejects.toThrow('Your Microsoft session expired. Please sign in again.');
    await expect(skins(offline).profile('account')).rejects.toThrow(SKIN_MESSAGES.unreachable);
  });

  it('adds a player\'s skin by their name, through the name lookup and the session server', async () => {
    const { skins, calls } = await service((call) => {
      if (call.url.startsWith('https://api.mojang.com/users/profiles/minecraft/')) return json({ id: 'fedcba9876543210fedcba9876543210', name: 'Patrol' });
      if (call.url.startsWith('https://sessionserver.mojang.com/session/minecraft/profile/')) return json(texturesProperty('http://textures.minecraft.net/texture/9a9a9a', 'slim'));
      return new Response(skinPng({ slim: true }));
    });
    const { skin, existed } = await skins.addByUsername('patrol');
    expect(existed).toBe(false);
    expect(skin).toMatchObject({ name: "Patrol's skin", source: 'username', variant: 'slim', texture: '9a9a9a' });
    expect(calls.map((c) => c.url)).toEqual([
      'https://api.mojang.com/users/profiles/minecraft/patrol',
      'https://sessionserver.mojang.com/session/minecraft/profile/fedcba9876543210fedcba9876543210',
      'https://textures.minecraft.net/texture/9a9a9a',
    ]);
    // The same skin again is found, not added twice.
    expect((await skins.addByUsername('Patrol')).existed).toBe(true);
  });

  it('says so when there is no such player, and checks the name before asking', async () => {
    const { skins, calls } = await service(() => new Response(null, { status: 404 }));
    await expect(skins.addByUsername('Nobody_Here')).rejects.toThrow('Mojang has no player called Nobody_Here.');
    await expect(skins.addByUsername('no spaces!')).rejects.toThrow('Minecraft names are 3 to 16 letters, numbers or underscores.');
    expect(calls).toHaveLength(1);
  });

  it("only downloads Mojang's skins from Mojang's texture server", async () => {
    const { skins, calls } = await service((call) => {
      if (call.url.includes('api.mojang.com')) return json({ id: 'fedcba9876543210fedcba9876543210', name: 'Patrol' });
      return json(texturesProperty('https://evil.example/texture/9a9a9a'));
    });
    await expect(skins.addByUsername('Patrol')).rejects.toThrow();
    expect(calls.some((c) => c.url.includes('evil.example'))).toBe(false);
  });

  it('adds a skin from an https link, and refuses links that are not https, too big or not skins', async () => {
    const png = skinPng();
    const { skins, calls } = await service((call) => {
      if (call.url.endsWith('/big.png')) return new Response(Buffer.alloc(MAX_SKIN_BYTES + 1));
      if (call.url.endsWith('/text.png')) return new Response('hello');
      if (call.url.endsWith('/moved.png')) return new Response(null, { status: 302, headers: { location: 'http://example.com/plain.png' } });
      return new Response(png);
    });
    const { skin } = await skins.addByUrl('https://example.com/skins/Cool%20Cape%20Guy.png');
    expect(skin).toMatchObject({ name: 'Cool Cape Guy', source: 'url', texture: null });

    await expect(skins.addByUrl('http://example.com/skin.png')).rejects.toThrow('Only https:// links can be added.');
    await expect(skins.addByUrl('https://example.com/big.png')).rejects.toThrow(TOO_BIG);
    await expect(skins.addByUrl('https://example.com/text.png')).rejects.toThrow(NOT_A_SKIN);
    await expect(skins.addByUrl('https://example.com/moved.png')).rejects.toThrow('Only https:// links can be added.');
    expect(calls.some((c) => c.url.startsWith('http:'))).toBe(false);
  });

  it('sends the Minecraft token to api.minecraftservices.com and nowhere else', async () => {
    const { skins, library, calls, dir } = await service((call) => {
      if (call.url.includes(SERVICES)) return json(profileBody(call.method === 'POST' ? 'b0b0b0' : 'a0a0a0'));
      if (call.url.includes('api.mojang.com')) return json({ id: 'fedcba9876543210fedcba9876543210', name: 'Patrol' });
      if (call.url.includes('sessionserver')) return json(texturesProperty('https://textures.minecraft.net/texture/9a9a9a'));
      return new Response(skinPng());
    });
    await skins.sync('account');
    const { skin } = await skins.addByUsername('Patrol');
    await skins.addByUrl('https://example.com/skin.png');
    await skins.equip('account', skin.id);
    await library.list();

    expect(new Set(calls.map((c) => new URL(c.url).host))).toEqual(new Set([SERVICES, 'textures.minecraft.net', 'api.mojang.com', 'sessionserver.mojang.com', 'example.com']));
    for (const call of calls) {
      const sent = JSON.stringify(call.headers);
      if (new URL(call.url).host === SERVICES) {
        expect(call.headers.Authorization).toMatch(/^Bearer /);
        // Never follows a redirect, which would carry the token somewhere else.
        expect(call.redirect).toBe('error');
      } else {
        expect(sent).not.toContain(TOKEN);
      }
    }
    // Nor is it written down with the skins.
    for (const file of readdirSync(dir)) expect(readFileSync(join(dir, file)).toString('latin1')).not.toContain(TOKEN);
  });

  it('never puts the token in an error or a log line, however a request ends', async () => {
    const logged: string[] = [];
    // Before redaction: what the code hands the log, not what redaction leaves of it.
    const spy = vi.spyOn(logSink, 'write').mockImplementation((_level, scope, message, data) => {
      logged.push(`${scope} ${message} ${JSON.stringify(data ?? {})}`);
    });
    const said: string[] = [];
    try {
      const endings: Array<() => Response | Promise<Response>> = [
        () => json({}, 401),
        () => json({ errorMessage: 'Invalid skin' }, 400),
        () => json({}, 429),
        () => json({}, 503),
        () => Promise.reject(new TypeError('fetch failed')),
        () => json(profileBody('d00d')),
      ];
      for (const ending of endings) {
        const { skins, library } = await service(ending);
        const skin = await library.add(skinPng(), { name: 'Mine', source: 'file' });
        for (const attempt of [() => skins.equip('account', skin.id), () => skins.profile('account')]) {
          await attempt().then(() => said.push('ok'), (err: Error) => said.push(`${err.message}\n${err.stack ?? ''}`));
        }
      }
    } finally {
      spy.mockRestore();
    }
    // Each ending was met, and some of them were logged, so the checks below look at something.
    expect(said.filter((s) => s === 'ok')).toHaveLength(2);
    expect(said).toEqual(expect.arrayContaining([expect.stringContaining(SKIN_MESSAGES.expired), expect.stringContaining('Invalid skin'), expect.stringContaining(SKIN_MESSAGES.limited), expect.stringContaining(SKIN_MESSAGES.unreachable)]));
    expect(logged.length).toBeGreaterThan(0);
    expect(said.join('\n')).not.toContain(TOKEN);
    expect(logged.join('\n')).not.toContain(TOKEN);
  });

  it('equips one skin at a time', async () => {
    let answer!: (res: Response) => void;
    const { skins, library, calls } = await service(() => new Promise<Response>((resolve) => (answer = resolve)));
    const skin = await library.add(skinPng(), { name: 'Mine', source: 'file' });
    const first = skins.equip('account', skin.id);
    for (let i = 0; i < 50 && !calls.length; i++) await new Promise((r) => setTimeout(r, 2));
    await expect(skins.equip('account', skin.id)).rejects.toThrow('A skin is already being equipped.');
    answer(json(profileBody('f00d')));
    expect((await first).equipped?.texture).toBe('f00d');
    expect(calls).toHaveLength(1);
  });
});
