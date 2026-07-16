import { Router } from 'express';
import { z } from 'zod';
import { pool } from './db.js';
import { requireAuth, requireOrganizer } from './auth.js';

export const eventsRouter = Router();
export const categoriesRouter = Router();

// priceFromCents derives from tiers (source of truth); events.price_cents is
// frozen legacy data kept only for schema compatibility.
const EVENT_SELECT = `
  SELECT e.id, e.title, e.description, e.venue,
         e.starts_at   AS "startsAt",
         e.status,
         e.currency,
         e.capacity,
         e.tickets_sold AS "ticketsSold",
         e.capacity - e.tickets_sold AS "seatsLeft",
         e.image_url   AS "imageUrl",
         e.organizer_id AS "organizerId",
         u.name        AS "organizerName",
         e.category_id AS "categoryId",
         c.name        AS "categoryName",
         COALESCE((SELECT MIN(tt.price_cents) FROM ticket_tiers tt WHERE tt.event_id = e.id),
                  e.price_cents) AS "priceFromCents"
  FROM events e
  JOIN users u ON u.id = e.organizer_id
  LEFT JOIN categories c ON c.id = e.category_id`;

const SORTS = {
  date_asc: 'e.starts_at ASC',
  date_desc: 'e.starts_at DESC',
  price_asc: '"priceFromCents" ASC, e.starts_at ASC',
  price_desc: '"priceFromCents" DESC, e.starts_at ASC',
};

categoriesRouter.get('/', requireAuth, async (_req, res) => {
  const { rows } = await pool.query('SELECT id, name FROM categories ORDER BY sort_order');
  res.json({ categories: rows });
});

eventsRouter.get('/', requireAuth, async (req, res) => {
  const search = (req.query.q ?? '').toString().trim();
  const categoryId = Number(req.query.categoryId) || null;
  const sort = SORTS[req.query.sort] ?? SORTS.date_asc;
  const limit = Math.min(50, Math.max(1, Number(req.query.limit) || 20));
  const offset = Math.max(0, Number(req.query.offset) || 0);
  const favoritesOnly = req.query.favorites === 'true';
  const mine = req.query.mine === 'true'; // organizer's own events, any status/date

  const params = [];
  const conds = [];
  if (mine) {
    params.push(req.user.id);
    conds.push(`e.organizer_id = $${params.length}`);
  } else {
    conds.push(`e.status = 'published'`, `e.starts_at > now()`);
  }
  if (search) {
    params.push(`%${search}%`);
    conds.push(`(e.title ILIKE $${params.length} OR e.venue ILIKE $${params.length})`);
  }
  if (categoryId) {
    params.push(categoryId);
    conds.push(`e.category_id = $${params.length}`);
  }
  if (favoritesOnly) {
    params.push(req.user.id);
    conds.push(`EXISTS (SELECT 1 FROM favorites f WHERE f.event_id = e.id AND f.user_id = $${params.length})`);
  }
  const where = `WHERE ${conds.join(' AND ')}`;

  params.push(req.user.id);
  const favParam = params.length;
  const listSql = `${EVENT_SELECT.replace(
    'FROM events e',
    `, EXISTS (SELECT 1 FROM favorites f WHERE f.event_id = e.id AND f.user_id = $${favParam}) AS "isFavorite"
  FROM events e`,
  )} ${where} ORDER BY ${sort} LIMIT ${limit} OFFSET ${offset}`;

  const [list, count] = await Promise.all([
    pool.query(listSql, params),
    pool.query(`SELECT count(*)::int AS total FROM events e ${where}`, params.slice(0, favParam - 1)),
  ]);

  const total = count.rows[0].total;
  res.json({ events: list.rows, total, hasMore: offset + list.rows.length < total });
});

eventsRouter.get('/:id', requireAuth, async (req, res) => {
  const { rows } = await pool.query(`${EVENT_SELECT} WHERE e.id = $1`, [req.params.id]);
  const event = rows[0];
  if (!event) return res.status(404).json({ error: 'Event not found' });

  const [tiers, fav, wl] = await Promise.all([
    pool.query(
      `SELECT id, name, price_cents AS "priceCents", capacity, sold,
              capacity - sold AS "seatsLeft"
       FROM ticket_tiers WHERE event_id = $1 ORDER BY sort_order, id`,
      [event.id],
    ),
    pool.query('SELECT 1 FROM favorites WHERE user_id = $1 AND event_id = $2', [req.user.id, event.id]),
    pool.query(
      `SELECT id, status, tier_id AS "tierId", offer_expires_at AS "offerExpiresAt"
       FROM waitlist WHERE user_id = $1 AND event_id = $2`,
      [req.user.id, event.id],
    ),
  ]);

  event.tiers = tiers.rows;
  event.isFavorite = Boolean(fav.rows[0]);
  event.myWaitlist = wl.rows[0] ?? null;
  res.json({ event });
});

const tierInput = z.object({
  name: z.string().min(1).max(60),
  priceCents: z.number().int().min(0),
  capacity: z.number().int().min(1).max(100000),
});

const createEventBody = z.object({
  title: z.string().min(1).max(120),
  description: z.string().max(2000).default(''),
  venue: z.string().min(1).max(160),
  startsAt: z.string().datetime({ offset: true }),
  categoryId: z.number().int().positive(),
  imageUrl: z.string().url().or(z.literal('')).default(''),
  // Either explicit tiers, or legacy single price+capacity (becomes 'General').
  tiers: z.array(tierInput).min(1).max(10).optional(),
  priceCents: z.number().int().min(0).optional(),
  capacity: z.number().int().min(1).max(100000).optional(),
}).refine((b) => b.tiers?.length || (b.priceCents !== undefined && b.capacity !== undefined), {
  message: 'Provide tiers, or priceCents + capacity',
});

eventsRouter.post('/', requireAuth, requireOrganizer, async (req, res) => {
  const parsed = createEventBody.safeParse(req.body);
  if (!parsed.success) {
    const issue = parsed.error.issues[0];
    return res.status(400).json({ error: `${issue.path.join('.')}: ${issue.message}` });
  }
  const b = parsed.data;
  const tiers = b.tiers ?? [{ name: 'General', priceCents: b.priceCents, capacity: b.capacity }];
  const names = new Set(tiers.map((t) => t.name.toLowerCase()));
  if (names.size !== tiers.length) {
    return res.status(400).json({ error: 'Tier names must be unique' });
  }
  const capacity = tiers.reduce((s, t) => s + t.capacity, 0);
  const minPrice = Math.min(...tiers.map((t) => t.priceCents));

  const { rows: cat } = await pool.query('SELECT id FROM categories WHERE id = $1', [b.categoryId]);
  if (!cat[0]) return res.status(400).json({ error: 'Unknown category' });

  const client = await pool.connect();
  try {
    await client.query('BEGIN');
    const { rows } = await client.query(
      `INSERT INTO events (organizer_id, title, description, venue, starts_at, price_cents, capacity, image_url, category_id)
       VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9) RETURNING id`,
      [req.user.id, b.title, b.description, b.venue, b.startsAt, minPrice, capacity, b.imageUrl, b.categoryId],
    );
    for (let i = 0; i < tiers.length; i++) {
      await client.query(
        `INSERT INTO ticket_tiers (event_id, name, price_cents, capacity, sort_order)
         VALUES ($1, $2, $3, $4, $5)`,
        [rows[0].id, tiers[i].name, tiers[i].priceCents, tiers[i].capacity, i],
      );
    }
    await client.query('COMMIT');
    const created = await client.query(`${EVENT_SELECT} WHERE e.id = $1`, [rows[0].id]);
    res.status(201).json({ event: created.rows[0] });
  } catch (err) {
    await client.query('ROLLBACK').catch(() => {});
    // Express 4 does not catch async throws — respond here or crash the process.
    console.error(err);
    if (!res.headersSent) res.status(500).json({ error: 'Internal server error' });
  } finally {
    client.release();
  }
});
