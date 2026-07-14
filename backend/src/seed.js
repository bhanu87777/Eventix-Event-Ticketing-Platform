import bcrypt from 'bcryptjs';

const DEMO_EVENTS = [
  {
    title: 'Neon Nights Music Festival',
    description: 'An open-air electronic music festival featuring three stages, immersive light installations, and headline DJ sets until 2 AM.',
    venue: 'Palace Grounds, Bengaluru',
    daysFromNow: 12,
    priceCents: 249900,
    capacity: 500,
    imageUrl: 'https://images.unsplash.com/photo-1470229722913-7c0e2dbbafd3?w=1200&q=80',
  },
  {
    title: 'TechSprint 2026 — Developer Conference',
    description: 'Two days of talks on distributed systems, AI infrastructure, and mobile engineering. Includes hands-on workshops and a hiring mixer.',
    venue: 'BIEC, Bengaluru',
    daysFromNow: 25,
    priceCents: 499900,
    capacity: 300,
    imageUrl: 'https://images.unsplash.com/photo-1540575467063-178a50c2df87?w=1200&q=80',
  },
  {
    title: 'Standup Saturday: Comedy Special',
    description: 'Five of the country’s top comics, one stage, zero mercy. Doors open at 7 PM — seating is first come, first served.',
    venue: 'Good Shepherd Auditorium, Bengaluru',
    daysFromNow: 5,
    priceCents: 79900,
    capacity: 150,
    imageUrl: 'https://images.unsplash.com/photo-1527224857830-43a7acc85260?w=1200&q=80',
  },
  {
    title: 'Sunrise Trail Half Marathon',
    description: 'A 21K trail run through Nandi Hills at dawn. Includes hydration stations every 3K, finisher medal, and post-race breakfast.',
    venue: 'Nandi Hills, Bengaluru',
    daysFromNow: 40,
    priceCents: 129900,
    capacity: 400,
    imageUrl: 'https://images.unsplash.com/photo-1452626038306-9aae5e071dd3?w=1200&q=80',
  },
  {
    title: 'Indie Film Premiere: “Monsoon Static”',
    description: 'Red-carpet premiere with a director Q&A after the screening. Limited seats — every ticket includes a signed poster.',
    venue: 'PVR Forum Mall, Bengaluru',
    daysFromNow: 8,
    priceCents: 59900,
    capacity: 120,
    imageUrl: 'https://images.unsplash.com/photo-1489599849927-2ee91cede3ba?w=1200&q=80',
  },
];

/** Seeds demo accounts and events on first boot so the app isn't empty. */
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
  await pool.query(
    `INSERT INTO users (email, password_hash, name, role)
     VALUES ('attendee@demo.com', $1, 'Demo Attendee', 'attendee')`,
    [hash],
  );

  for (const e of DEMO_EVENTS) {
    const startsAt = new Date(Date.now() + e.daysFromNow * 86_400_000);
    startsAt.setHours(18, 30, 0, 0);
    await pool.query(
      `INSERT INTO events (organizer_id, title, description, venue, starts_at, price_cents, capacity, image_url)
       VALUES ($1, $2, $3, $4, $5, $6, $7, $8)`,
      [organizer.rows[0].id, e.title, e.description, e.venue, startsAt, e.priceCents, e.capacity, e.imageUrl],
    );
  }
  console.log('Seeded: organizer@demo.com / attendee@demo.com (password: password123)');
}
