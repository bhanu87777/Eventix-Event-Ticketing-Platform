/**
 * Concurrency proof for the order-based checkout:
 *   1. 200 parallel create+confirm checkouts against 50 seats → exactly 50 paid.
 *   2. Multi-tier, quantity-2 orders → per-tier caps hold, aggregate stays in
 *      sync with the tier sum, orders are all-or-nothing.
 *   3. A promo with max_uses=5 under 50 concurrent confirms → exactly 5 redeem.
 *   4. Idempotent replays (create and confirm).
 *   5. Double check-in protection + forged QR rejection (unchanged).
 *
 * Run with the server up:  node --env-file=.env loadtest/oversell-test.js
 */
import crypto from 'node:crypto';

const BASE = process.env.API_URL ?? 'http://localhost:3000';
const CAPACITY = 50;
const BUYERS = 200;

async function api(path, { method = 'GET', token, body, headers = {} } = {}) {
  const res = await fetch(`${BASE}${path}`, {
    method,
    headers: {
      'content-type': 'application/json',
      ...(token ? { authorization: `Bearer ${token}` } : {}),
      ...headers,
    },
    body: body ? JSON.stringify(body) : undefined,
  });
  return { status: res.status, body: await res.json() };
}

function assert(cond, message) {
  if (!cond) {
    console.error(`\n  FAIL: ${message}`);
    process.exit(1);
  }
  console.log(`  OK  ${message}`);
}

/** Full checkout: create a qty-`quantity` order on `tierId` and confirm it. */
async function checkout(token, eventId, tierId, quantity, promoCode) {
  const created = await api('/orders', {
    method: 'POST',
    token,
    headers: { 'idempotency-key': crypto.randomUUID() },
    body: { eventId, items: [{ tierId, quantity }], ...(promoCode ? { promoCode } : {}) },
  });
  if (created.status !== 201) return { stage: 'create', ...created };
  const confirmed = await api(`/orders/${created.body.order.id}/confirm`, {
    method: 'POST',
    token,
    body: { payment: { outcome: 'success' } },
  });
  return { stage: 'confirm', order: created.body.order, ...confirmed };
}

console.log(`Oversell test against ${BASE}\n`);

const health = await fetch(`${BASE}/health`).catch(() => null);
if (!health?.ok) {
  console.error('Server is not running. Start it first: npm run dev');
  process.exit(1);
}

// 1. Log in as the seeded organizer and attendee.
const organizer = (await api('/auth/login', {
  method: 'POST',
  body: { email: 'organizer@demo.com', password: 'password123' },
})).body;
const attendee = (await api('/auth/login', {
  method: 'POST',
  body: { email: 'attendee@demo.com', password: 'password123' },
})).body;
assert(organizer.token && attendee.token, 'logged in with seeded demo accounts');

const { body: cats } = await api('/categories', { token: organizer.token });
const categoryId = cats.categories.find((c) => c.name === 'Other')?.id ?? cats.categories[0].id;

// 2. Create a fresh single-tier event with a small capacity.
const { body: created } = await api('/events', {
  method: 'POST',
  token: organizer.token,
  body: {
    title: `Load Test ${crypto.randomUUID().slice(0, 8)}`,
    venue: 'Load Test Arena',
    startsAt: new Date(Date.now() + 86_400_000).toISOString(),
    priceCents: 1000,
    capacity: CAPACITY,
    categoryId,
    description: 'Ephemeral event created by loadtest/oversell-test.js',
  },
});
const eventId = created.event.id;
assert(eventId, `created event #${eventId} with capacity ${CAPACITY}`);
const { body: detail0 } = await api(`/events/${eventId}`, { token: attendee.token });
const gaTier = detail0.event.tiers[0];

// 3. Fire BUYERS full checkouts at the same instant.
console.log(`\nFiring ${BUYERS} concurrent create+confirm checkouts…`);
const t0 = performance.now();
const results = await Promise.all(
  Array.from({ length: BUYERS }, () => checkout(attendee.token, eventId, gaTier.id, 1)),
);
const elapsed = Math.round(performance.now() - t0);

const paid = results.filter((r) => r.status === 200 && r.body.order?.status === 'paid');
const soldOut = results.filter((r) => r.status === 409 && r.body.code === 'SOLD_OUT');
const other = results.filter((r) => !paid.includes(r) && !soldOut.includes(r));

console.log(`Done in ${elapsed}ms → ${paid.length} paid, ${soldOut.length} rejected as sold out, ${other.length} errors\n`);

assert(other.length === 0, 'no unexpected errors');
assert(paid.length === CAPACITY, `exactly ${CAPACITY} checkouts succeeded (no overselling)`);

const { body: after } = await api(`/events/${eventId}`, { token: attendee.token });
assert(after.event.ticketsSold === CAPACITY, `event.ticketsSold === ${CAPACITY} in the database`);
assert(after.event.seatsLeft === 0, 'seatsLeft === 0');
assert(after.event.tiers[0].sold === CAPACITY, 'tier.sold matches the aggregate');

const codes = new Set(paid.flatMap((r) => r.body.tickets.map((t) => t.code)));
assert(codes.size === CAPACITY, `all ${CAPACITY} ticket codes are unique`);

// 4. Multi-tier, quantity-2 orders: per-tier caps + all-or-nothing.
const { body: multi } = await api('/events', {
  method: 'POST',
  token: organizer.token,
  body: {
    title: `Tier Test ${crypto.randomUUID().slice(0, 8)}`,
    venue: 'Load Test Arena',
    startsAt: new Date(Date.now() + 86_400_000).toISOString(),
    categoryId,
    description: '',
    tiers: [
      { name: 'GA', priceCents: 1000, capacity: 30 },
      { name: 'VIP', priceCents: 5000, capacity: 20 },
    ],
  },
});
const multiId = multi.event.id;
const { body: multiDetail } = await api(`/events/${multiId}`, { token: attendee.token });
const [ga, vip] = multiDetail.event.tiers;

console.log(`\nFiring 150 concurrent quantity-2 checkouts across 2 tiers…`);
const multiResults = await Promise.all(
  Array.from({ length: 150 }, (_, i) =>
    checkout(attendee.token, multiId, i % 2 === 0 ? ga.id : vip.id, 2),
  ),
);
const multiPaid = multiResults.filter((r) => r.status === 200 && r.body.order?.status === 'paid');
const { body: multiAfter } = await api(`/events/${multiId}`, { token: attendee.token });
const tGa = multiAfter.event.tiers.find((t) => t.name === 'GA');
const tVip = multiAfter.event.tiers.find((t) => t.name === 'VIP');

assert(tGa.sold <= tGa.capacity && tVip.sold <= tVip.capacity, 'no tier oversold');
assert(tGa.sold % 2 === 0 && tVip.sold % 2 === 0, 'orders were all-or-nothing (even sold counts)');
assert(
  tGa.sold + tVip.sold === multiAfter.event.ticketsSold,
  'sum(tier.sold) === event.ticketsSold (no aggregate drift)',
);
assert(
  multiPaid.reduce((s, r) => s + r.body.tickets.length, 0) === multiAfter.event.ticketsSold,
  'tickets issued match seats sold',
);

// 5. Promo contention: max_uses = 5 under 50 concurrent checkouts.
const { body: promoEvent } = await api('/events', {
  method: 'POST',
  token: organizer.token,
  body: {
    title: `Promo Test ${crypto.randomUUID().slice(0, 8)}`,
    venue: 'Load Test Arena',
    startsAt: new Date(Date.now() + 86_400_000).toISOString(),
    priceCents: 1000,
    capacity: 100,
    categoryId,
    description: '',
  },
});
const promoCode = `LT${crypto.randomUUID().slice(0, 6).toUpperCase()}`;
const promoCreate = await api(`/events/${promoEvent.event.id}/promos`, {
  method: 'POST',
  token: organizer.token,
  body: { code: promoCode, kind: 'percent', value: 50, maxUses: 5 },
});
assert(promoCreate.status === 201, `organizer created promo ${promoCode} (max 5 uses)`);
const { body: promoDetail } = await api(`/events/${promoEvent.event.id}`, { token: attendee.token });

console.log(`\nFiring 50 concurrent checkouts all using promo ${promoCode}…`);
const promoResults = await Promise.all(
  Array.from({ length: 50 }, () =>
    checkout(attendee.token, promoEvent.event.id, promoDetail.event.tiers[0].id, 1, promoCode),
  ),
);
const discounted = promoResults.filter(
  (r) => r.status === 200 && r.body.order?.status === 'paid' && r.body.order.discountCents > 0,
);
const promoRejected = promoResults.filter((r) => r.body.code === 'PROMO_INVALID');
assert(discounted.length === 5, `exactly 5 orders redeemed the promo (got ${discounted.length})`);
assert(promoRejected.length === 45, `the other 45 were rejected with PROMO_INVALID (got ${promoRejected.length})`);

// 6. Idempotency: replaying create and confirm returns the original result.
const key = crypto.randomUUID();
const mk = () =>
  api('/orders', {
    method: 'POST',
    token: attendee.token,
    headers: { 'idempotency-key': key },
    body: { eventId: promoEvent.event.id, items: [{ tierId: promoDetail.event.tiers[0].id, quantity: 1 }] },
  });
const c1 = await mk();
const c2 = await mk();
assert(
  c2.body.replayed === true && c2.body.order.id === c1.body.order.id,
  'replaying an order create returns the original order',
);
const f1 = await api(`/orders/${c1.body.order.id}/confirm`, {
  method: 'POST', token: attendee.token, body: { payment: { outcome: 'success' } },
});
const f2 = await api(`/orders/${c1.body.order.id}/confirm`, {
  method: 'POST', token: attendee.token, body: { payment: { outcome: 'success' } },
});
assert(
  f2.body.replayed === true &&
    f2.body.tickets.map((t) => t.code).join() === f1.body.tickets.map((t) => t.code).join(),
  'replaying a confirm returns the original tickets (no double sale)',
);

// 7. Check-in: first scan succeeds, second is rejected atomically.
const firstTicket = paid[0].body.tickets[0];
const scan1 = await api('/checkin', {
  method: 'POST',
  token: organizer.token,
  body: { qr: firstTicket.qr },
});
const scan2 = await api('/checkin', {
  method: 'POST',
  token: organizer.token,
  body: { qr: firstTicket.qr },
});
assert(scan1.body.result === 'ok', 'first QR scan checks the ticket in');
assert(scan2.status === 409 && scan2.body.result === 'already_checked_in', 'second scan of the same QR is rejected');

const forged = await api('/checkin', {
  method: 'POST',
  token: organizer.token,
  body: { qr: 'ETP1.deadbeefdeadbeefdead.00000000000000000000000000000000' },
});
assert(forged.status === 400 && forged.body.result === 'invalid', 'forged QR signature is rejected');

console.log('\nAll concurrency guarantees hold.');
