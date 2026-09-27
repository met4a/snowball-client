// Who gets a Snowball badge: only players whose game is running Snowball Client right now.
//   node --test test/
// The room runs in plain Node. A stand-in "Mojang" key is added through MOJANG_KEYS, so the proof a
// game sends is checked for real - both signatures - rather than mocked away.
import { createSign, generateKeyPairSync } from 'node:crypto';
import assert from 'node:assert/strict';
import { test } from 'node:test';
import { ChatRoom } from '../src/worker.js';

const OWNER = '85c4ac3a0c284c20be4bee8a346ba51e';
const mojang = generateKeyPairSync('rsa', { modulusLength: 2048 });
const mojangPublic = mojang.publicKey.export({ type: 'spki', format: 'der' }).toString('base64');

async function room() {
  const stored = new Map();
  const state = {
    blockConcurrencyWhile: (fn) => fn(),
    storage: { get: async (k) => stored.get(k), put: async (k, v) => stored.set(k, v) },
  };
  const r = new ChatRoom(state, { ADMIN_UUID: OWNER, MOJANG_KEYS: mojangPublic });
  await r.ready;
  return r;
}

const call = (r, path, body, method = 'POST') =>
  r.fetch(new Request(`https://chat.test${path}`, { method, headers: { 'content-type': 'application/json' }, body: body === undefined ? undefined : JSON.stringify(body) }));

/** What a game sends: its key as Mojang issued it, and the room's one-time id signed with it. */
async function proof(r, uuid, name) {
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

const ranks = async (r, uuids) => (await (await call(r, '/ranks', { uuids })).json()).ranks;

test('nobody has a badge until their game says it runs Snowball Client - not even the owner', async () => {
  const r = await room();
  r.people.set('11111111111111111111111111111111', { first: Date.now() });
  assert.deepEqual(await ranks(r, [OWNER, '11111111111111111111111111111111']), {});
});

test('a game that proves its account shows the badge, and keeps it with its ticket', async () => {
  const r = await room();
  const answer = await call(r, '/presence', await proof(r, OWNER, 'Met4a'));
  assert.equal(answer.status, 200);
  const { ticket, every } = await answer.json();
  assert.match(ticket, /^[0-9a-f]{32}$/);
  assert.equal(every, 120);
  assert.deepEqual(await ranks(r, [OWNER]), { [OWNER]: 'owner' });

  assert.equal((await call(r, '/presence', { ticket })).status, 200);
  assert.equal((await call(r, '/presence', { ticket: 'f'.repeat(32) })).status, 403);
});

test('the badge goes when the game stops or goes quiet', async () => {
  const r = await room();
  const player = 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa';
  r.ranks.set(player, { rank: 'staff' });
  const { ticket } = await (await call(r, '/presence', await proof(r, player, 'Someone'))).json();
  assert.deepEqual(await ranks(r, [player]), { [player]: 'staff' });

  // Closing the game takes it down at once.
  await call(r, '/presence', { ticket }, 'DELETE');
  assert.deepEqual(await ranks(r, [player]), {});

  // A game that simply stops saying so drops out after five minutes.
  const again = await (await call(r, '/presence', await proof(r, player, 'Someone'))).json();
  assert.ok(again.ticket);
  r.inClient.set(player, Date.now() - 6 * 60_000);
  assert.deepEqual(await ranks(r, [player]), {});
});

test('a proof for another account, or with a forged key, is refused', async () => {
  const r = await room();
  const mine = await proof(r, 'bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb', 'Mine');
  // Claiming somebody else's account with my key: Mojang never signed my key for them.
  const claimed = await call(r, '/presence', { ...mine, uuid: OWNER });
  assert.equal(claimed.status, 403);
  // A one-time id can only be used once.
  const reused = await call(r, '/presence', mine);
  assert.equal(reused.status, 403);
  assert.deepEqual(await ranks(r, [OWNER, 'bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb']), {});
});
