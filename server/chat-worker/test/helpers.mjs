// What the tests share: a room running in plain Node, and a game's proof of its account.
// A stand-in "Mojang" key is added through MOJANG_KEYS, so the proof a game sends is checked for
// real - both signatures - rather than mocked away.
import { createSign, generateKeyPairSync } from 'node:crypto';
import { ChatRoom } from '../src/worker.js';

export const OWNER = '85c4ac3a0c284c20be4bee8a346ba51e';
const mojang = generateKeyPairSync('rsa', { modulusLength: 2048 });
const mojangPublic = mojang.publicKey.export({ type: 'spki', format: 'der' }).toString('base64');

export async function room(env = {}) {
  const stored = new Map();
  const state = {
    blockConcurrencyWhile: (fn) => fn(),
    storage: { get: async (k) => stored.get(k), put: async (k, v) => stored.set(k, v) },
  };
  const r = new ChatRoom(state, { ADMIN_UUID: OWNER, MOJANG_KEYS: mojangPublic, ...env });
  await r.ready;
  return r;
}

export const call = (r, path, body, method = 'POST') =>
  r.fetch(new Request(`https://chat.test${path}`, { method, headers: { 'content-type': 'application/json' }, body: body === undefined ? undefined : JSON.stringify(body) }));

/** What a game sends: its key as Mojang issued it, and the room's one-time id signed with it. */
export async function proof(r, uuid, name) {
  const { nonce } = await (await call(r, '/nonce', undefined, 'GET')).json();
  const player = generateKeyPairSync('rsa', { modulusLength: 2048 });
  const der = player.publicKey.export({ type: 'spki', format: 'der' });
  const expiresAt = Date.now() + 48 * 3600_000;
  const halves = Buffer.alloc(24);
  halves.writeBigInt64BE(BigInt.asIntN(64, BigInt('0x' + uuid.slice(0, 16))), 0);
  halves.writeBigInt64BE(BigInt.asIntN(64, BigInt('0x' + uuid.slice(16))), 8);
  halves.writeBigInt64BE(BigInt(expiresAt), 16);
  const keySignature = createSign('RSA-SHA1').update(Buffer.concat([halves, der])).sign(mojang.privateKey, 'base64');
  return {
    name, uuid, nonce, expiresAt, keySignature,
    publicKey: player.publicKey.export({ type: 'spki', format: 'pem' }),
    signature: createSign('RSA-SHA256').update(nonce).sign(player.privateKey, 'base64'),
  };
}

