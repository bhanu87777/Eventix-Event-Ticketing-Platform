import { Router } from 'express';
import { z } from 'zod';
import { pool } from './db.js';
import { requireAuth, requireOrganizer } from './auth.js';

export const eventsRouter = Router();

const EVENT_SELECT = `
  SELECT e.id, e.title, e.description, e.venue,
         e.starts_at   AS "startsAt",
         e.price_cents AS "priceCents",
         e.currency,
         e.capacity,
         e.tickets_sold AS "ticketsSold",
         e.capacity - e.tickets_sold AS "seatsLeft",
         e.image_url   AS "imageUrl",
         e.organizer_id AS "organizerId",
         u.name        AS "organizerName"
  FROM events e JOIN users u ON u.id = e.organizer_id`;

eventsRouter.get('/', requireAuth, async (req, res) => {
  const search = (req.query.q ?? '').toString().trim();
  const params = [];
  let where = '';
  if (search) {
    params.push(`%${search}%`);
    where = `WHERE e.title ILIKE $1 OR e.venue ILIKE $1`;
  }
  const { rows } = await pool.query(
    `${EVENT_SELECT} ${where} ORDER BY e.starts_at ASC`,
    params,
  );
  res.json({ events: rows });
});

eventsRouter.get('/:id', requireAuth, async (req, res) => {
  const { rows } = await pool.query(`${EVENT_SELECT} WHERE e.id = $1`, [req.params.id]);
  if (!rows[0]) return res.status(404).json({ error: 'Event not found' });
  res.json({ event: rows[0] });
});

const createEventBody = z.object({
  title: z.string().min(1).max(120),
  description: z.string().max(2000).default(''),
  venue: z.string().min(1).max(160),
  startsAt: z.string().datetime({ offset: true }),
  priceCents: z.number().int().min(0),
  capacity: z.number().int().min(1).max(100000),
  imageUrl: z.string().url().or(z.literal('')).default(''),
});

eventsRouter.post('/', requireAuth, requireOrganizer, async (req, res) => {
  const parsed = createEventBody.safeParse(req.body);
  if (!parsed.success) {
    const issue = parsed.error.issues[0];
    return res.status(400).json({ error: `${issue.path.join('.')}: ${issue.message}` });
  }
  const b = parsed.data;
  const { rows } = await pool.query(
    `INSERT INTO events (organizer_id, title, description, venue, starts_at, price_cents, capacity, image_url)
     VALUES ($1, $2, $3, $4, $5, $6, $7, $8) RETURNING id`,
    [req.user.id, b.title, b.description, b.venue, b.startsAt, b.priceCents, b.capacity, b.imageUrl],
  );
  const created = await pool.query(`${EVENT_SELECT} WHERE e.id = $1`, [rows[0].id]);
  res.status(201).json({ event: created.rows[0] });
});
