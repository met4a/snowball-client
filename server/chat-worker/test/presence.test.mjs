// Who gets a Snowball badge: only players whose game is running Snowball Client right now.
//   node --test test/
// The room runs in plain Node. A stand-in "Mojang" key is added through MOJANG_KEYS, so the proof a
// game sends is checked for real - both signatures - rather than mocked away.
import assert from 'node:assert/strict';
import { test } from 'node:test';
import { OWNER, call, proof, room } from './helpers.mjs';

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
