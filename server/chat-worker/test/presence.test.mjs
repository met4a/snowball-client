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

test('the counts include players in game, each account once, and remember them for all time', async () => {
  const r = await room();
  const player = 'a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1';
  const chatter = 'b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2';
  const res = await call(r, '/presence', await proof(r, player, 'Player'));
  assert.equal(res.status, 200);
  // Someone in chat on two launchers, and the player also in chat, still count once each.
  for (const uuid of [chatter, chatter, player]) r.sockets.set({ send() {} }, { name: 'x', uuid, owner: false, rank: 'snowball', times: [] });
  const stats = r.stats();
  assert.equal(stats.online, 2);
  assert.equal(stats.total, 1);
  assert.equal(stats.today, 1);
  assert.equal(r.people.get(player).name, 'Player');

  // Gone from the game and from chat: no longer online, still counted all time.
  r.inClient.clear();
  r.sockets.clear();
  assert.deepEqual(r.stats(), { online: 0, today: 1, week: 1, total: 1 });
});
