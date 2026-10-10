// What players wear and the emotes they play reach the other Snowball players who can see them.
//   node --test test/
import assert from 'node:assert/strict';
import { test } from 'node:test';
import { call, proof, room } from './helpers.mjs';

const A = 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa';
const B = 'bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb';
const C = 'cccccccccccccccccccccccccccccccc';

const looks = async (r, uuids) => (await (await call(r, '/ranks', { uuids })).json()).looks;

/** A game's live socket, as the room sees it: whatever the room sends lands in `got`. */
function listen(r, uuid) {
  const socket = { got: [], send(data) { this.got.push(JSON.parse(data)); } };
  r.live.set(socket, { uuid, watching: new Set(), times: [] });
  return socket;
}

test('a cape travels with the heartbeat and shows to whoever asks about the player', async () => {
  const r = await room();
  const { ticket } = await (await call(r, '/presence', { ...(await proof(r, A, 'Alice')), looks: { cape: 'aurora' } })).json();
  assert.deepEqual(await looks(r, [A]), { [A]: { cape: 'aurora' } });

  await call(r, '/presence', { ticket, looks: { cape: 'frost' } });
  assert.deepEqual(await looks(r, [A]), { [A]: { cape: 'frost' } });

  // Taking it off, or closing the game, takes it away.
  await call(r, '/presence', { ticket });
  assert.deepEqual(await looks(r, [A]), {});
  await call(r, '/presence', { ticket, looks: { cape: 'ember' } });
  await call(r, '/presence', { ticket }, 'DELETE');
  assert.deepEqual(await looks(r, [A]), {});
});

test('only capes the client ships are passed on', async () => {
  const r = await room();
  await call(r, '/presence', { ...(await proof(r, A, 'Alice')), looks: { cape: '../../evil' } });
  assert.deepEqual(await looks(r, [A]), {});
  // The ranks reader in older clients still finds only the ranks.
  const body = await (await call(r, '/ranks', { uuids: [A] })).text();
  assert.ok(!/"[0-9a-f]{32}"\s*:\s*"(?!snowball)/.test(body));
});

test('emotes reach only the games watching that player, never the sender', async () => {
  const r = await room();
  const alice = listen(r, A);
  const bob = listen(r, B);
  const carol = listen(r, C);
  r.onLive(bob, JSON.stringify({ type: 'watch', uuids: [A] }));
  r.onLive(carol, JSON.stringify({ type: 'watch', uuids: [B] }));

  r.onLive(alice, JSON.stringify({ type: 'emote', id: 'wave' }));
  assert.equal(bob.got.length, 1);
  assert.equal(bob.got[0].type, 'emote');
  assert.equal(bob.got[0].uuid, A);
  assert.equal(bob.got[0].id, 'wave');
  assert.equal(carol.got.length, 0, 'Carol is not watching Alice');
  assert.equal(alice.got.length, 0, 'Alice does not hear herself');

  r.onLive(alice, JSON.stringify({ type: 'stop' }));
  assert.equal(bob.got[1].type, 'stop');
});

test('made-up emotes and spamming are dropped', async () => {
  const r = await room();
  const alice = listen(r, A);
  const bob = listen(r, B);
  r.onLive(bob, JSON.stringify({ type: 'watch', uuids: [A] }));
  r.onLive(alice, JSON.stringify({ type: 'emote', id: 'something_rude' }));
  r.onLive(alice, 'not json');
  assert.equal(bob.got.length, 0);
  // Two at once: the second is too soon.
  r.onLive(alice, JSON.stringify({ type: 'emote', id: 'clap' }));
  r.onLive(alice, JSON.stringify({ type: 'emote', id: 'clap' }));
  assert.equal(bob.got.length, 1);
});

test('a new cape is told to watchers straight away', async () => {
  const r = await room();
  const bob = listen(r, B);
  r.onLive(bob, JSON.stringify({ type: 'watch', uuids: [A] }));
  const { ticket } = await (await call(r, '/presence', await proof(r, A, 'Alice'))).json();
  await call(r, '/presence', { ticket, looks: { cape: 'sakura' } });
  assert.deepEqual(bob.got.at(-1), { type: 'looks', uuid: A, looks: { cape: 'sakura' } });
});

test('the live channel needs a ticket from a game that proved its account', async () => {
  const r = await room();
  const refused = await r.fetch(new Request('https://chat.test/live?ticket=' + 'f'.repeat(32), { headers: { Upgrade: 'websocket' } }));
  assert.equal(refused.status, 403);
});
