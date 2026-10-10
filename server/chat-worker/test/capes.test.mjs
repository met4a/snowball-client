// Snowball+ custom capes: sent from the game, approved by staff in the launcher, then worn.
//   node --test test/*.test.mjs
import assert from 'node:assert/strict';
import { test } from 'node:test';
import { OWNER, call, proof, room } from './helpers.mjs';

const PLUS = 'abababababababababababababababab';
const FREE = 'cdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcd';
const STAFF = 'efefefefefefefefefefefefefefefef';
const DESIGN = '7fcbff'.repeat(150) + 'ffffff'.repeat(10);

async function ticketFor(r, uuid, name) {
  return (await (await call(r, '/presence', await proof(r, uuid, name))).json()).ticket;
}

/** A launcher's chat socket, as the room sees it. */
function panel(r, uuid, rank) {
  const socket = { got: [], send(data) { this.got.push(JSON.parse(data)); } };
  r.sockets.set(socket, { name: 'Staffer', uuid, owner: false, rank, times: [] });
  return socket;
}

test('only Snowball+ can send a design, and only a real one', async () => {
  const r = await room();
  r.ranks.set(PLUS, { rank: 'plus' });
  const free = await ticketFor(r, FREE, 'Free');
  const plus = await ticketFor(r, PLUS, 'Plus');
  assert.equal((await call(r, '/capes/submit', { ticket: free, pixels: DESIGN })).status, 403);
  assert.equal((await call(r, '/capes/submit', { ticket: plus, pixels: 'nope' })).status, 400);
  assert.equal((await call(r, '/capes/submit', { ticket: 'f'.repeat(32), pixels: DESIGN })).status, 403);
  assert.equal((await call(r, '/capes/submit', { ticket: plus, pixels: DESIGN })).status, 200);
  // And not again straight away.
  assert.equal((await call(r, '/capes/submit', { ticket: plus, pixels: DESIGN })).status, 429);
});

test('a design shows on nobody until staff approve it', async () => {
  const r = await room();
  r.ranks.set(PLUS, { rank: 'plus' });
  const plus = await ticketFor(r, PLUS, 'Plus');
  const staff = panel(r, STAFF, 'staff');
  await call(r, '/capes/submit', { ticket: plus, pixels: DESIGN });

  // Staff hear about it at once, with the picture to look at.
  const told = staff.got.at(-1);
  assert.equal(told.type, 'capes');
  assert.equal(told.capes[0].uuid, PLUS);
  assert.equal(told.capes[0].pixels, DESIGN);

  // Wearing it before approval wears nothing; nor can anyone fetch it.
  await call(r, '/presence', { ticket: plus, looks: { cape: 'custom' } });
  const before = await (await call(r, '/ranks', { uuids: [PLUS] })).json();
  assert.deepEqual(before.looks, {});
  assert.equal((await r.fetch(new Request(`https://chat.test/capes/${PLUS}`))).status, 404);
  const pending = await (await r.fetch(new Request(`https://chat.test/capes/status?ticket=${plus}`))).json();
  assert.equal(pending.request.status, 'pending');
  assert.equal(pending.approved, null);

  await r.onAdmin({ type: 'admin', action: 'cape-review', uuid: PLUS, approve: true }, staff, r.sockets.get(staff));
  assert.deepEqual(staff.got.at(-1), { type: 'capes', capes: [] });

  await call(r, '/presence', { ticket: plus, looks: { cape: 'custom' } });
  const after = await (await call(r, '/ranks', { uuids: [PLUS] })).json();
  assert.deepEqual(after.looks, { [PLUS]: { cape: 'custom', v: 1 } });
  const file = await (await r.fetch(new Request(`https://chat.test/capes/${PLUS}`))).json();
  assert.deepEqual(file, { pixels: DESIGN, version: 1 });
});

test('a denied design comes back with the reason, and the approved one stays', async () => {
  const r = await room();
  r.ranks.set(PLUS, { rank: 'plus' });
  const plus = await ticketFor(r, PLUS, 'Plus');
  const staff = panel(r, STAFF, 'staff');
  await call(r, '/capes/submit', { ticket: plus, pixels: DESIGN });
  await r.onAdmin({ type: 'admin', action: 'cape-review', uuid: PLUS, approve: true }, staff, r.sockets.get(staff));

  r.capes.get(PLUS).request = null;
  const second = '000000'.repeat(160);
  r.capes.get(PLUS).request = { pixels: second, at: 0, status: 'pending', reason: '' };
  await r.onAdmin({ type: 'admin', action: 'cape-review', uuid: PLUS, approve: false, reason: 'Too dark to see' }, staff, r.sockets.get(staff));
  const status = await (await r.fetch(new Request(`https://chat.test/capes/status?ticket=${plus}`))).json();
  assert.equal(status.request.status, 'denied');
  assert.equal(status.request.reason, 'Too dark to see');
  assert.equal(status.approved, 1, 'the approved design is still theirs');
});

test('only ranks that review capes can see or decide them', async () => {
  const r = await room();
  r.ranks.set(PLUS, { rank: 'plus' });
  const plus = await ticketFor(r, PLUS, 'Plus');
  await call(r, '/capes/submit', { ticket: plus, pixels: DESIGN });
  const tester = panel(r, FREE, 'tester');
  await r.onAdmin({ type: 'admin', action: 'cape-review', uuid: PLUS, approve: true }, tester, r.sockets.get(tester));
  assert.equal(tester.got.at(-1).type, 'admin-result');
  assert.equal(tester.got.at(-1).ok, false);
  assert.equal(r.capes.get(PLUS).approved, null);
  // The owner always can.
  const owner = panel(r, OWNER, 'owner');
  await r.onAdmin({ type: 'admin', action: 'capes' }, owner, r.sockets.get(owner));
  assert.equal(owner.got.at(-1).capes.length, 1);
});
