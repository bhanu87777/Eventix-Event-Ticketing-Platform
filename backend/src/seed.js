import crypto from 'node:crypto';
import bcrypt from 'bcryptjs';

const DEMO_EVENTS = [
  {
    title: 'Neon Nights Music Festival',
    description: 'An open-air electronic music festival featuring three stages, immersive light installations, and headline DJ sets until 2 AM.',
    venue: 'Palace Grounds, Bengaluru',
    daysFromNow: 12,
    category: 'Music',
    imageUrl: 'https://images.unsplash.com/photo-1470229722913-7c0e2dbbafd3?w=1200&q=80',
    tiers: [
      { name: 'General', priceCents: 249900, capacity: 400 },
      { name: 'VIP', priceCents: 599900, capacity: 100 },
    ],
  },
  {
    title: 'TechSprint 2026 — Developer Conference',
    description: 'Two days of talks on distributed systems, AI infrastructure, and mobile engineering. Includes hands-on workshops and a hiring mixer.',
    venue: 'BIEC, Bengaluru',
    daysFromNow: 25,
    category: 'Tech',
    imageUrl: 'https://images.unsplash.com/photo-1540575467063-178a50c2df87?w=1200&q=80',
    tiers: [
      { name: 'Early-bird', priceCents: 399900, capacity: 50 },
      { name: 'Standard', priceCents: 499900, capacity: 250 },
    ],
  },
  {
    title: 'Standup Saturday: Comedy Special',
    description: 'Five of the country’s top comics, one stage, zero mercy. Doors open at 7 PM — seating is first come, first served.',
    venue: 'Good Shepherd Auditorium, Bengaluru',
    daysFromNow: 5,
    category: 'Arts',
    imageUrl: 'https://images.unsplash.com/photo-1527224857830-43a7acc85260?w=1200&q=80',
    tiers: [{ name: 'General', priceCents: 79900, capacity: 150 }],
  },
  {
    title: 'Sunrise Trail Half Marathon',
    description: 'A 21K trail run through Nandi Hills at dawn. Includes hydration stations every 3K, finisher medal, and post-race breakfast.',
    venue: 'Nandi Hills, Bengaluru',
    daysFromNow: 40,
    category: 'Sports',
    imageUrl: 'https://images.unsplash.com/photo-1452626038306-9aae5e071dd3?w=1200&q=80',
    tiers: [{ name: 'General', priceCents: 129900, capacity: 400 }],
  },
  {
    title: 'Indie Film Premiere: “Monsoon Static”',
    description: 'Red-carpet premiere with a director Q&A after the screening. Limited seats — every ticket includes a signed poster.',
    venue: 'PVR Forum Mall, Bengaluru',
    daysFromNow: 8,
    category: 'Arts',
    imageUrl: 'https://images.unsplash.com/photo-1489599849927-2ee91cede3ba?w=1200&q=80',
    tiers: [{ name: 'General', priceCents: 59900, capacity: 120 }],
  },
];

// A tiny sold-out show: demos the waitlist offer chain end-to-end.
const SOLD_OUT_EVENT = {
  title: 'Secret Rooftop Acoustic Set',
  description: 'An unplugged evening with a surprise headliner. Two seats. That’s the whole venue.',
  venue: 'Undisclosed rooftop, Indiranagar, Bengaluru',
  daysFromNow: 15,
  category: 'Music',
  imageUrl: 'https://images.unsplash.com/photo-1510915361894-db8b60106cb1?w=1200&q=80',
  tier: { name: 'General', priceCents: 149900, capacity: 2 },
};

async function insertEvent(pool, organizerId, e, categoryIds) {
  const startsAt = new Date(Date.now() + e.daysFromNow * 86_400_000);
  startsAt.setHours(18, 30, 0, 0);
  const capacity = e.tiers.reduce((s, t) => s + t.capacity, 0);
  const minPrice = Math.min(...e.tiers.map((t) => t.priceCents));
  const { rows } = await pool.query(
    `INSERT INTO events (organizer_id, title, description, venue, starts_at, price_cents, capacity, image_url, category_id)
     VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9) RETURNING id`,
    [organizerId, e.title, e.description, e.venue, startsAt, minPrice, capacity, e.imageUrl, categoryIds.get(e.category)],
  );
  const eventId = rows[0].id;
  const tierIds = [];
  for (let i = 0; i < e.tiers.length; i++) {
    const t = e.tiers[i];
    const { rows: tr } = await pool.query(
      `INSERT INTO ticket_tiers (event_id, name, price_cents, capacity, sort_order)
       VALUES ($1, $2, $3, $4, $5) RETURNING id`,
      [eventId, t.name, t.priceCents, t.capacity, i],
    );
    tierIds.push(tr[0].id);
  }
  return { eventId, tierIds };
}

/** Seeds demo accounts, events, tiers, promos, and a waitlist scenario. */
export async function seedIfEmpty(pool) {
  const { rows } = await pool.query('SELECT count(*)::int AS n FROM users');
  if (rows[0].n > 0) return;

  console.log('Seeding demo data…');
  const hash = await bcrypt.hash('password123', 10);

  const organizer = await pool.query(
    `INSERT INTO users (email, password_hash, name, role)
     VALUES ('organizer@demo.com', $1, 'Demo Organizer', 'organizer') RETURNING id`,
    [hash],
  );
  const organizerId = organizer.rows[0].id;
  const attendee1 = await pool.query(
    `INSERT INTO users (email, password_hash, name, role)
     VALUES ('attendee@demo.com', $1, 'Demo Attendee', 'attendee') RETURNING id`,
    [hash],
  );
  const attendee2 = await pool.query(
    `INSERT INTO users (email, password_hash, name, role)
     VALUES ('attendee2@demo.com', $1, 'Second Attendee', 'attendee') RETURNING id`,
    [hash],
  );

  const { rows: catRows } = await pool.query('SELECT id, name FROM categories');
  const categoryIds = new Map(catRows.map((c) => [c.name, c.id]));

  const created = [];
  for (const e of DEMO_EVENTS) {
    created.push(await insertEvent(pool, organizerId, e, categoryIds));
  }

  // Promo codes: one global, one scoped to the tech conference.
  await pool.query(
    `INSERT INTO promo_codes (code, event_id, kind, value, max_uses, expires_at)
     VALUES ('WELCOME10', NULL, 'percent', 10, 100, NULL),
            ('TECH500', $1, 'fixed', 50000, 50, now() + interval '30 days')`,
    [created[1].eventId],
  );

  // Sold-out event: two seats, both bought by attendee1 through a paid order,
  // attendee2 already on the waitlist — cancelling one ticket demos the offer.
  const so = await insertEvent(pool, organizerId, { ...SOLD_OUT_EVENT, tiers: [SOLD_OUT_EVENT.tier] }, categoryIds);
  const tierId = so.tierIds[0];
  const { rows: orderRows } = await pool.query(
    `INSERT INTO orders (user_id, event_id, status, idempotency_key, subtotal_cents, discount_cents, total_cents, expires_at, paid_at)
     VALUES ($1, $2, 'paid', $3, $4, 0, $4, now(), now()) RETURNING id`,
    [attendee1.rows[0].id, so.eventId, `seed-${crypto.randomUUID()}`, SOLD_OUT_EVENT.tier.priceCents * 2],
  );
  await pool.query(
    `INSERT INTO order_items (order_id, tier_id, quantity, unit_price_cents) VALUES ($1, $2, 2, $3)`,
    [orderRows[0].id, tierId, SOLD_OUT_EVENT.tier.priceCents],
  );
  for (let i = 0; i < 2; i++) {
    await pool.query(
      `INSERT INTO tickets (code, event_id, user_id, tier_id, order_id) VALUES ($1, $2, $3, $4, $5)`,
      [crypto.randomBytes(10).toString('hex'), so.eventId, attendee1.rows[0].id, tierId, orderRows[0].id],
    );
  }
  await pool.query(`UPDATE ticket_tiers SET sold = 2 WHERE id = $1`, [tierId]);
  await pool.query(`UPDATE events SET tickets_sold = 2 WHERE id = $1`, [so.eventId]);
  await pool.query(
    `INSERT INTO waitlist (event_id, user_id) VALUES ($1, $2)`,
    [so.eventId, attendee2.rows[0].id],
  );

  console.log(
    'Seeded: organizer@demo.com / attendee@demo.com / attendee2@demo.com (password: password123)\n' +
    'Promos: WELCOME10 (10% global) · TECH500 (₹500 off TechSprint)\n' +
    `Waitlist demo: "${SOLD_OUT_EVENT.title}" is sold out; attendee2 is waiting.`,
  );
}
