// Snowball+ from the Stripe store: given while the subscription is paid for, taken back when it ends.
//   node --test test/*.test.mjs
import assert from 'node:assert/strict';
import { createHmac } from 'node:crypto';
import { test } from 'node:test';
import { OWNER, room } from './helpers.mjs';

const SECRET = 'whsec_test';
const BUYER = '0123456789abcdef0123456789abcdef';
const FRIEND = 'fedcba9876543210fedcba9876543210';
const STAFF = 'efefefefefefefefefefefefefefefef';
const NOW = Math.floor(Date.now() / 1000);

const store = () => room({ STRIPE_WEBHOOK_SECRET: SECRET });

/** Sends an event the way Stripe does: Stripe-Signature t=<time>,v1=HMAC-SHA256("<time>.<body>"). */
function send(r, event, { secret = SECRET, signedAt = NOW } = {}) {
  const body = JSON.stringify(event);
  const v1 = createHmac('sha256', secret).update(`${signedAt}.${body}`).digest('hex');
  return r.fetch(new Request('https://chat.test/store/stripe', { method: 'POST', headers: { 'content-type': 'application/json', 'stripe-signature': `t=${signedAt},v1=${v1}` }, body }));
}

/** The checkout the store page starts: the account and name ride in client_reference_id. */
function checkout({ uuid = BUYER, name = 'Buyer', sub = 'sub_1', created = NOW, paid = 'paid', type = 'checkout.session.completed' } = {}) {
  return { id: `evt_${type}_${created}`, type, created, data: { object: { id: 'cs_1', object: 'checkout.session', mode: 'subscription', client_reference_id: `${uuid}_${name}`, subscription: sub, payment_status: paid, status: 'complete' } } };
}

function subscription(type, { sub = 'sub_1', status = 'active', created = NOW + 60 } = {}) {
  return { id: `evt_${type}_${created}`, type, created, data: { object: { id: sub, object: 'subscription', status } } };
}

/** A launcher signed in to this account, as the room sees it. */
function launcher(r, uuid, rank = 'snowball') {
  const socket = { got: [], send(data) { this.got.push(JSON.parse(data)); } };
  r.sockets.set(socket, { name: 'Someone', uuid, owner: false, rank, times: [] });
  return socket;
}

test('nothing is given without the store set up and a fresh, real signature', async () => {
  const unset = await room();
  assert.equal((await send(unset, checkout())).status, 503);
  const r = await store();
  assert.equal((await send(r, checkout(), { secret: 'whsec_wrong' })).status, 403);
  assert.equal((await send(r, checkout(), { signedAt: NOW - 600 })).status, 403);
  const forged = await r.fetch(new Request('https://chat.test/store/stripe', { method: 'POST', body: '{}', headers: { 'stripe-signature': 't=1,v1=nope' } }));
  assert.equal(forged.status, 403);
  assert.equal(r.ranks.size, 0);
});

test('a paid checkout gives Snowball+ at once, and cancelling takes it back', async () => {
  const r = await store();
  const open = launcher(r, BUYER);
  assert.equal((await send(r, checkout())).status, 200);
  assert.equal(r.rankOf(BUYER), 'plus');
  assert.equal(r.ranks.get(BUYER).byName, 'Store');
  assert.equal(r.ranks.get(BUYER).name, 'Buyer');
  assert.equal(open.got.at(-1).type, 'you');
  assert.ok(open.got.at(-1).permissions.includes('plus.features'));

  // A failed renewal Stripe is still retrying keeps it.
  await send(r, subscription('customer.subscription.updated', { status: 'past_due', created: NOW + 100 }));
  await send(r, subscription('customer.subscription.updated', { status: 'active', created: NOW + 200 }));
  assert.equal(r.rankOf(BUYER), 'plus');

  await send(r, subscription('customer.subscription.deleted', { status: 'canceled', created: NOW + 300 }));
  assert.equal(r.rankOf(BUYER), 'snowball');
  assert.equal(open.got.at(-1).rank, 'snowball');
  assert.equal(r.rankLog.get(BUYER).length, 2);
});

test('a card that keeps failing ends it', async () => {
  const r = await store();
  await send(r, checkout());
  await send(r, subscription('customer.subscription.updated', { status: 'unpaid' }));
  assert.equal(r.rankOf(BUYER), 'snowball');
});

test('a checkout paid later, by bank transfer and the like, gives it when the money arrives', async () => {
  const r = await store();
  await send(r, checkout({ paid: 'unpaid' }));
  assert.equal(r.rankOf(BUYER), 'snowball');
  await send(r, checkout({ type: 'checkout.session.async_payment_succeeded', created: NOW + 60 }));
  assert.equal(r.rankOf(BUYER), 'plus');
});

test('a custom cape comes off when the subscription ends, and the design is kept', async () => {
  const r = await store();
  await send(r, checkout());
  r.capes.set(BUYER, { name: 'Buyer', approved: { pixels: '7fcbff'.repeat(160), version: 1 }, request: null });
  r.inClient.set(BUYER, Date.now());
  r.setLooks(BUYER, { cape: 'custom' });
  assert.deepEqual(r.looks.get(BUYER), { cape: 'custom', v: 1 });

  await send(r, subscription('customer.subscription.deleted', { status: 'canceled' }));
  assert.equal(r.looks.get(BUYER), undefined);
  // The game keeps asking for it on its heartbeat; it stays off until Snowball+ is back.
  r.setLooks(BUYER, { cape: 'custom' });
  assert.equal(r.looks.get(BUYER), undefined);
  assert.equal(r.capes.get(BUYER).approved.version, 1);

  await send(r, checkout({ sub: 'sub_2', created: NOW + 120 }));
  r.setLooks(BUYER, { cape: 'custom' });
  assert.deepEqual(r.looks.get(BUYER), { cape: 'custom', v: 1 });
});

test('a late copy of an old event does not bring Snowball+ back', async () => {
  const r = await store();
  await send(r, checkout());
  await send(r, subscription('customer.subscription.deleted', { status: 'canceled', created: NOW + 300 }));
  await send(r, subscription('customer.subscription.updated', { status: 'active', created: NOW + 100 }));
  await send(r, checkout());
  assert.equal(r.rankOf(BUYER), 'snowball');
});

test('a gift goes to the account named at checkout', async () => {
  const r = await store();
  await send(r, checkout({ uuid: FRIEND, name: 'Friend' }));
  assert.equal(r.rankOf(FRIEND), 'plus');
  assert.equal(r.rankOf(BUYER), 'snowball');
});

test('the store never touches a rank staff gave', async () => {
  const r = await store();
  r.ranks.set(STAFF, { rank: 'staff', by: OWNER, byName: 'Met4a' });
  r.ranks.set(FRIEND, { rank: 'plus', by: OWNER, byName: 'Met4a' });
  await send(r, checkout({ uuid: STAFF, name: 'Staffer', sub: 'sub_a' }));
  await send(r, checkout({ uuid: FRIEND, name: 'Friend', sub: 'sub_b' }));
  assert.equal(r.rankOf(STAFF), 'staff');
  await send(r, subscription('customer.subscription.deleted', { sub: 'sub_a', status: 'canceled' }));
  await send(r, subscription('customer.subscription.deleted', { sub: 'sub_b', status: 'canceled' }));
  assert.equal(r.rankOf(STAFF), 'staff');
  assert.equal(r.rankOf(FRIEND), 'plus');
  assert.equal(r.ranks.get(FRIEND).byName, 'Met4a');
});

test('with two subscriptions, Snowball+ stays until the last one ends', async () => {
  const r = await store();
  await send(r, checkout({ sub: 'sub_a' }));
  await send(r, checkout({ sub: 'sub_b' }));
  await send(r, subscription('customer.subscription.deleted', { sub: 'sub_a', status: 'canceled' }));
  assert.equal(r.rankOf(BUYER), 'plus');
  await send(r, subscription('customer.subscription.deleted', { sub: 'sub_b', status: 'canceled' }));
  assert.equal(r.rankOf(BUYER), 'snowball');
});

test('checkouts and subscriptions that are not Snowball+ are ignored', async () => {
  const r = await store();
  const other = checkout();
  other.data.object.client_reference_id = null;
  assert.equal((await send(r, other)).status, 200);
  const oneOff = checkout();
  oneOff.data.object.mode = 'payment';
  assert.equal((await send(r, oneOff)).status, 200);
  assert.equal((await send(r, subscription('customer.subscription.deleted', { sub: 'sub_unknown' }))).status, 200);
  assert.equal((await send(r, { id: 'evt_x', type: 'invoice.paid', created: NOW, data: { object: {} } })).status, 200);
  assert.equal(r.ranks.size, 0);
  assert.equal(r.subs.size, 0);
});
