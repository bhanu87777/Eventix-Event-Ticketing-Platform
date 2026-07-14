/**
 * Concurrency proof: fires 200 parallel purchase requests at an event with
 * 50 seats and asserts exactly 50 tickets are sold — no overselling, ever.
 * Also verifies idempotency replay and double check-in protection.
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
    console.error(`\n  ❌ FAIL: ${message}`);
    process.exit(1);
  }
  console.log(`  ✅ ${message}`);
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

// 2. Create a fresh event with a small capacity.
const { body: created } = await api('/events', {
  method: 'POST',
  token: organizer.token,
  body: {
    title: `Load Test ${crypto.randomUUID().slice(0, 8)}`,
    venue: 'Load Test Arena',
    startsAt: new Date(Date.now() + 86_400_000).toISOString(),
    priceCents: 1000,
    capacity: CAPACITY,
    description: 'Ephemeral event created by loadtest/oversell-test.js',
  },
});
const eventId = created.event.id;
assert(eventId, `created event #${eventId} with capacity ${CAPACITY}`);

// 3. Fire BUYERS purchases at the same instant.
console.log(`\nFiring ${BUYERS} concurrent purchase requests…`);
const t0 = performance.now();
const results = await Promise.all(
  Array.from({ length: BUYERS }, () =>
    api(`/events/${eventId}/purchase`, {
      method: 'POST',
      token: attendee.token,
      headers: { 'idempotency-key': crypto.randomUUID() },
    }),
  ),
);
const elapsed = Math.round(performance.now() - t0);

const succeeded = results.filter((r) => r.status === 201);
const soldOut = results.filter((r) => r.status === 409 && r.body.code === 'SOLD_OUT');
const other = results.filter((r) => r.status !== 201 && r.body.code !== 'SOLD_OUT');

console.log(`Done in ${elapsed}ms → ${succeeded.length} sold, ${soldOut.length} rejected as sold out, ${other.length} errors\n`);

assert(other.length === 0, 'no unexpected errors');
assert(succeeded.length === CAPACITY, `exactly ${CAPACITY} purchases succeeded (no overselling)`);

const { body: after } = await api(`/events/${eventId}`, { token: attendee.token });
assert(after.event.ticketsSold === CAPACITY, `event.ticketsSold === ${CAPACITY} in the database`);
assert(after.event.seatsLeft === 0, 'seatsLeft === 0');

const codes = new Set(succeeded.map((r) => r.body.ticket.code));
assert(codes.size === CAPACITY, `all ${CAPACITY} ticket codes are unique`);

// 4. Idempotency: replaying a used key must return the ORIGINAL ticket, not a new one.
const replayKey = crypto.randomUUID();
const soldOutReplayEventFirst = await api(`/events/${eventId}/purchase`, {
  method: 'POST',
  token: attendee.token,
  headers: { 'idempotency-key': replayKey },
});
assert(soldOutReplayEventFirst.status === 409, 'sold-out event rejects a new purchase');

// Use a key that succeeded earlier and replay it.
// (Replay must not be blocked by the sold-out state — the purchase already happened.)
const firstTicket = succeeded[0].body.ticket;
// We didn't retain per-request keys above, so test replay on a fresh event instead.
const { body: created2 } = await api('/events', {
  method: 'POST',
  token: organizer.token,
  body: {
    title: `Replay Test ${crypto.randomUUID().slice(0, 8)}`,
    venue: 'Load Test Arena',
    startsAt: new Date(Date.now() + 86_400_000).toISOString(),
    priceCents: 1000,
    capacity: 5,
    description: '',
  },
});
const key = crypto.randomUUID();
const buy1 = await api(`/events/${created2.event.id}/purchase`, {
  method: 'POST',
  token: attendee.token,
  headers: { 'idempotency-key': key },
});
const buy2 = await api(`/events/${created2.event.id}/purchase`, {
  method: 'POST',
  token: attendee.token,
  headers: { 'idempotency-key': key },
});
assert(
  buy2.body.replayed === true && buy2.body.ticket.code === buy1.body.ticket.code,
  'replaying an idempotency key returns the original ticket (no double sale)',
);

// 5. Check-in: first scan succeeds, second is rejected atomically.
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

console.log('\n🎉 All concurrency guarantees hold.');
