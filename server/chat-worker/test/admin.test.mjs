// The admin panel hears back about every action it sends: done or refused, with a reason, in the
// panel rather than as a chat message.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { room, OWNER } from './helpers.mjs';

const ALEX = 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa';
const STAFF = 'bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb';

function panel(r, uuid, rank, owner = false) {
  const socket = { got: [], send(data) { this.got.push(JSON.parse(data)); } };
  r.sockets.set(socket, { name: owner ? 'Owner' : 'Staffer', uuid, owner, rank, times: [] });
  return socket;
}
const result = (socket) => socket.got.filter((m) => m.type === 'admin-result').at(-1);

test('a rank change is confirmed by name, and the ranked list shows it', async () => {
  const r = await room();
  r.people.set(ALEX, { name: 'Alex', last: 1 });
  r.people.set(OWNER, { name: 'Met4a', last: 1 });
  const owner = panel(r, OWNER, 'owner', true);
  await r.onAdmin({ type: 'admin', action: 'rank-set', uuid: ALEX, rank: 'bug_hunter' }, owner, r.sockets.get(owner));
  assert.deepEqual(result(owner), { type: 'admin-result', action: 'rank-set', ok: true, message: 'Alex is now Bug Hunter. They see it straight away.' });

  await r.onAdmin({ type: 'admin', action: 'ranked' }, owner, r.sockets.get(owner));
  const ranked = owner.got.at(-1);
  assert.equal(ranked.type, 'ranked');
  assert.deepEqual(ranked.people.map((p) => [p.name, p.rank]), [['Met4a', 'owner'], ['Alex', 'bug_hunter']]);
});

test('refusals come back to the panel with a reason', async () => {
  const r = await room();
  const owner = panel(r, OWNER, 'owner', true);
  await r.onAdmin({ type: 'admin', action: 'rank-set', uuid: ALEX, rank: 'wizard' }, owner, r.sockets.get(owner));
  assert.equal(result(owner).ok, false);
  assert.match(result(owner).message, /no rank called wizard/);
  assert.equal(r.ranks.has(ALEX), false, 'an unknown rank no longer quietly revokes');

  await r.onAdmin({ type: 'admin', action: 'mute', uuid: 'not-an-id' }, owner, r.sockets.get(owner));
  assert.equal(result(owner).message, 'That is not an account id.');

  const staff = panel(r, STAFF, 'staff');
  await r.onAdmin({ type: 'admin', action: 'rank-set', uuid: ALEX, rank: 'plus' }, staff, r.sockets.get(staff));
  assert.equal(result(staff).ok, false);
  assert.match(result(staff).message, /needs ranks\.manage/);
  assert.equal(staff.got.some((m) => m.type === 'error'), false, 'nothing lands in chat');
});

test('muting says who and for how long', async () => {
  const r = await room();
  r.people.set(ALEX, { name: 'Alex', last: 1 });
  const staff = panel(r, STAFF, 'staff');
  await r.onAdmin({ type: 'admin', action: 'mute', uuid: ALEX, minutes: 5 }, staff, r.sockets.get(staff));
  assert.equal(result(staff).message, 'Alex is muted for 5 minutes.');
  assert.ok(r.mutes.get(ALEX) > Date.now());
  await r.onAdmin({ type: 'admin', action: 'unmute', uuid: ALEX }, staff, r.sockets.get(staff));
  assert.equal(result(staff).message, 'Alex can talk again.');
  assert.equal(r.mutes.has(ALEX), false);
});
