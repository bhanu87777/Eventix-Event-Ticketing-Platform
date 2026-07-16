import { Router } from 'express';
import { z } from 'zod';
import { pool } from './db.js';
import { requireAuth, requireOrganizer } from './auth.js';
import { insertNotification } from './notify.js';

export const organizerRouter = Router();
// Guards are per-route (not router.use): this router mounts at '/', so
// router-level middleware would run for every request in the app.
const guard = [requireAuth, requireOrganizer];

/** Loads the event and 404s unless the caller owns it. */
async function ownEvent(db, eventId, organizerId) {
  const { rows } = await db.query(
    `SELECT id, title, venue, starts_at, status FROM events WHERE id = $1 AND organizer_id = $2`,
    [eventId, organizerId],
  );
  return rows[0] ?? null;
}

// ---------------------------------------------------------------- edit event
const patchEventBody = z.object({
  title: z.string().min(1).max(120).optional(),
  description: z.string().max(2000).optional(),
  venue: z.string().min(1).max(160).optional(),
  startsAt: z.string().datetime({ offset: true }).optional(),
  imageUrl: z.string().url().or(z.literal('')).optional(),
  categoryId: z.number().int().positive().optional(),
}).refine((b) => Object.keys(b).length > 0, { message: 'Nothing to update' });

const PATCH_COLS = {
  title: 'title',
  description: 'description',
  venue: 'venue',
  startsAt: 'starts_at',
  imageUrl: 'image_url',
  categoryId: 'category_id',
};

organizerRouter.patch('/events/:id', ...guard, async (req, res) => {
  const parsed = patchEventBody.safeParse(req.body);
  if (!parsed.success) return res.status(400).json({ error: parsed.error.issues[0].message });
  const b = parsed.data;

  const event = await ownEvent(pool, req.params.id, req.user.id);
  if (!event) return res.status(404).json({ error: 'Event not found' });
  if (event.status !== 'published') return res.status(409).json({ error: 'Cancelled events cannot be edited' });

  if (b.categoryId) {
    const { rows } = await pool.query('SELECT id FROM categories WHERE id = $1', [b.categoryId]);
    if (!rows[0]) return res.status(400).json({ error: 'Unknown category' });
  }

  const sets = [];
  const params = [];
  for (const [key, col] of Object.entries(PATCH_COLS)) {
    if (b[key] !== undefined) {
      params.push(b[key]);
      sets.push(`${col} = $${params.length}`);
    }
  }
  params.push(event.id);

  const client = await pool.connect();
  try {
    await client.query('BEGIN');
    await client.query(`UPDATE events SET ${sets.join(', ')} WHERE id = $${params.length}`, params);

    // Venue/time changes matter to ticket holders — tell them, atomically.
    const logistics = b.startsAt !== undefined || b.venue !== undefined;
    if (logistics) {
      const { rows: holders } = await client.query(
        `SELECT DISTINCT user_id FROM tickets WHERE event_id = $1 AND status = 'valid'`,
        [event.id],
      );
      for (const h of holders) {
        await insertNotification(client, {
          userId: Number(h.user_id),
          type: 'event_updated',
          title: 'Event details changed',
          body: `"${b.title ?? event.title}" was updated${b.venue ? ` - new venue: ${b.venue}` : ''}${b.startsAt ? ' - new date/time' : ''}. Check your ticket.`,
          eventId: event.id,
        });
      }
    }
    await client.query('COMMIT');
  } catch (err) {
    await client.query('ROLLBACK').catch(() => {});
    console.error(err);
    if (!res.headersSent) return res.status(500).json({ error: 'Internal server error' });
    return;
  } finally {
    client.release();
  }

  const { rows } = await pool.query('SELECT * FROM events WHERE id = $1', [event.id]);
  res.json({ event: { id: rows[0].id, title: rows[0].title, status: rows[0].status } });
});

// -------------------------------------------------------------- cancel event
organizerRouter.post('/events/:id/cancel', ...guard, async (req, res) => {
  const client = await pool.connect();
  try {
    await client.query('BEGIN');
    const { rows: evRows } = await client.query(
      `SELECT id, title, status FROM events WHERE id = $1 AND organizer_id = $2 FOR UPDATE`,
      [req.params.id, req.user.id],
    );
    const event = evRows[0];
    if (!event) {
      await client.query('ROLLBACK');
      return res.status(404).json({ error: 'Event not found' });
    }
    if (event.status === 'cancelled') {
      await client.query('ROLLBACK');
      return res.status(409).json({ error: 'Event is already cancelled' });
    }

    await client.query(
      `UPDATE events SET status = 'cancelled', cancelled_at = now() WHERE id = $1`,
      [event.id],
    );
    const { rows: holders } = await client.query(
      `SELECT DISTINCT user_id FROM tickets WHERE event_id = $1 AND status = 'valid'`,
      [event.id],
    );
    await client.query(
      `UPDATE tickets SET status = 'void' WHERE event_id = $1 AND status = 'valid'`,
      [event.id],
    );
    await client.query(
      `UPDATE waitlist SET status = 'expired' WHERE event_id = $1 AND status IN ('waiting', 'offered')`,
      [event.id],
    );
    for (const h of holders) {
      await insertNotification(client, {
        userId: Number(h.user_id),
        type: 'event_cancelled',
        title: 'Event cancelled',
        body: `"${event.title}" has been cancelled by the organizer. Your ticket is no longer valid.`,
        eventId: event.id,
      });
    }
    await client.query('COMMIT');
    return res.json({ event: { id: event.id, title: event.title, status: 'cancelled' }, notified: holders.length });
  } catch (err) {
    await client.query('ROLLBACK').catch(() => {});
    console.error(err);
    if (!res.headersSent) res.status(500).json({ error: 'Internal server error' });
  } finally {
    client.release();
  }
});

// ---------------------------------------------------------------- tier CRUD
const tierBody = z.object({
  name: z.string().min(1).max(60),
  priceCents: z.number().int().min(0),
  capacity: z.number().int().min(1).max(100000),
});

organizerRouter.post('/events/:id/tiers', ...guard, async (req, res) => {
  const parsed = tierBody.safeParse(req.body);
  if (!parsed.success) return res.status(400).json({ error: parsed.error.issues[0].message });
  const b = parsed.data;

  const client = await pool.connect();
  try {
    await client.query('BEGIN');
    const { rows: ev } = await client.query(
      `SELECT id, status FROM events WHERE id = $1 AND organizer_id = $2 FOR UPDATE`,
      [req.params.id, req.user.id],
    );
    if (!ev[0]) {
      await client.query('ROLLBACK');
      return res.status(404).json({ error: 'Event not found' });
    }
    const { rows: tier } = await client.query(
      `INSERT INTO ticket_tiers (event_id, name, price_cents, capacity,
         sort_order)
       VALUES ($1, $2, $3, $4,
         (SELECT COALESCE(MAX(sort_order), 0) + 1 FROM ticket_tiers WHERE event_id = $1))
       RETURNING id`,
      [ev[0].id, b.name, b.priceCents, b.capacity],
    );
    await client.query('UPDATE events SET capacity = capacity + $2 WHERE id = $1', [ev[0].id, b.capacity]);
    await client.query('COMMIT');
    return res.status(201).json({ tierId: tier[0].id });
  } catch (err) {
    await client.query('ROLLBACK').catch(() => {});
    if (err.code === '23505') return res.status(409).json({ error: 'A tier with this name already exists' });
    console.error(err);
    if (!res.headersSent) res.status(500).json({ error: 'Internal server error' });
  } finally {
    client.release();
  }
});

const patchTierBody = z.object({
  name: z.string().min(1).max(60).optional(),
  priceCents: z.number().int().min(0).optional(),
  capacity: z.number().int().min(1).max(100000).optional(),
}).refine((b) => Object.keys(b).length > 0, { message: 'Nothing to update' });

organizerRouter.patch('/events/:id/tiers/:tierId', ...guard, async (req, res) => {
  const parsed = patchTierBody.safeParse(req.body);
  if (!parsed.success) return res.status(400).json({ error: parsed.error.issues[0].message });
  const b = parsed.data;

  const client = await pool.connect();
  try {
    await client.query('BEGIN');
    const { rows: ev } = await client.query(
      `SELECT id FROM events WHERE id = $1 AND organizer_id = $2 FOR UPDATE`,
      [req.params.id, req.user.id],
    );
    if (!ev[0]) {
      await client.query('ROLLBACK');
      return res.status(404).json({ error: 'Event not found' });
    }
    const { rows: tRows } = await client.query(
      `SELECT id, capacity, sold FROM ticket_tiers WHERE id = $1 AND event_id = $2`,
      [req.params.tierId, ev[0].id],
    );
    const tier = tRows[0];
    if (!tier) {
      await client.query('ROLLBACK');
      return res.status(404).json({ error: 'Tier not found' });
    }
    if (b.capacity !== undefined && b.capacity < tier.sold) {
      await client.query('ROLLBACK');
      return res.status(409).json({ error: `Capacity can't go below ${tier.sold} already-sold seats` });
    }

    const sets = [];
    const params = [];
    if (b.name !== undefined) { params.push(b.name); sets.push(`name = $${params.length}`); }
    if (b.priceCents !== undefined) { params.push(b.priceCents); sets.push(`price_cents = $${params.length}`); }
    if (b.capacity !== undefined) { params.push(b.capacity); sets.push(`capacity = $${params.length}`); }
    params.push(tier.id);
    await client.query(`UPDATE ticket_tiers SET ${sets.join(', ')} WHERE id = $${params.length}`, params);
    if (b.capacity !== undefined) {
      await client.query('UPDATE events SET capacity = capacity + $2 WHERE id = $1', [ev[0].id, b.capacity - tier.capacity]);
    }
    await client.query('COMMIT');
    return res.json({ ok: true });
  } catch (err) {
    await client.query('ROLLBACK').catch(() => {});
    if (err.code === '23505') return res.status(409).json({ error: 'A tier with this name already exists' });
    console.error(err);
    if (!res.headersSent) res.status(500).json({ error: 'Internal server error' });
  } finally {
    client.release();
  }
});

organizerRouter.delete('/events/:id/tiers/:tierId', ...guard, async (req, res) => {
  const client = await pool.connect();
  try {
    await client.query('BEGIN');
    const { rows: ev } = await client.query(
      `SELECT id FROM events WHERE id = $1 AND organizer_id = $2 FOR UPDATE`,
      [req.params.id, req.user.id],
    );
    if (!ev[0]) {
      await client.query('ROLLBACK');
      return res.status(404).json({ error: 'Event not found' });
    }
    const { rows: tRows } = await client.query(
      `SELECT id, capacity, sold,
              (SELECT count(*)::int FROM ticket_tiers WHERE event_id = $2) AS tier_count
       FROM ticket_tiers WHERE id = $1 AND event_id = $2`,
      [req.params.tierId, ev[0].id],
    );
    const tier = tRows[0];
    if (!tier) {
      await client.query('ROLLBACK');
      return res.status(404).json({ error: 'Tier not found' });
    }
    if (tier.sold > 0) {
      await client.query('ROLLBACK');
      return res.status(409).json({ error: 'Tiers with sold tickets cannot be deleted' });
    }
    if (tier.tier_count <= 1) {
      await client.query('ROLLBACK');
      return res.status(409).json({ error: 'An event needs at least one tier' });
    }
    await client.query('DELETE FROM ticket_tiers WHERE id = $1', [tier.id]);
    await client.query('UPDATE events SET capacity = capacity - $2 WHERE id = $1', [ev[0].id, tier.capacity]);
    await client.query('COMMIT');
    return res.json({ ok: true });
  } catch (err) {
    await client.query('ROLLBACK').catch(() => {});
    console.error(err);
    if (!res.headersSent) res.status(500).json({ error: 'Internal server error' });
  } finally {
    client.release();
  }
});

// -------------------------------------------------------------------- stats
organizerRouter.get('/events/:id/stats', ...guard, async (req, res) => {
  const event = await ownEvent(pool, req.params.id, req.user.id);
  if (!event) return res.status(404).json({ error: 'Event not found' });

  const [totals, byTier, byDay] = await Promise.all([
    pool.query(
      `SELECT
         count(*) FILTER (WHERE t.status IN ('valid', 'checked_in'))::int AS sold,
         count(*) FILTER (WHERE t.status = 'checked_in')::int AS checked_in,
         COALESCE((SELECT SUM(o.total_cents) FROM orders o
                   WHERE o.event_id = $1 AND o.status = 'paid'), 0)::int AS revenue_cents
       FROM tickets t WHERE t.event_id = $1`,
      [event.id],
    ),
    pool.query(
      `SELECT tt.id AS "tierId", tt.name, tt.sold, tt.capacity, tt.price_cents AS "priceCents"
       FROM ticket_tiers tt WHERE tt.event_id = $1 ORDER BY tt.sort_order, tt.id`,
      [event.id],
    ),
    pool.query(
      `SELECT to_char(date_trunc('day', t.created_at), 'YYYY-MM-DD') AS date,
              count(*)::int AS tickets
       FROM tickets t
       WHERE t.event_id = $1 AND t.status <> 'void'
       GROUP BY 1 ORDER BY 1`,
      [event.id],
    ),
  ]);

  const t = totals.rows[0];
  res.json({
    stats: {
      sold: t.sold,
      checkedIn: t.checked_in,
      checkinRate: t.sold > 0 ? Math.round((t.checked_in / t.sold) * 1000) / 10 : 0,
      revenueCents: t.revenue_cents,
      byTier: byTier.rows,
      salesByDay: byDay.rows,
    },
  });
});

// ---------------------------------------------------------------- attendees
const ATTENDEE_SELECT = `
  SELECT t.id AS "ticketId", t.code, t.status,
         t.checked_in_at AS "checkedInAt",
         t.created_at    AS "purchasedAt",
         tt.name         AS "tierName",
         u.name          AS "userName",
         u.email         AS "userEmail"
  FROM tickets t
  JOIN users u ON u.id = t.user_id
  JOIN ticket_tiers tt ON tt.id = t.tier_id
  WHERE t.event_id = $1`;

function attendeeFilters(req) {
  const params = [req.params.id];
  let where = '';
  const q = (req.query.q ?? '').toString().trim();
  if (q) {
    params.push(`%${q}%`);
    where = ` AND (u.name ILIKE $2 OR u.email ILIKE $2 OR t.code ILIKE $2)`;
  }
  return { params, where };
}

organizerRouter.get('/events/:id/attendees', ...guard, async (req, res) => {
  const event = await ownEvent(pool, req.params.id, req.user.id);
  if (!event) return res.status(404).json({ error: 'Event not found' });

  const limit = Math.min(100, Math.max(1, Number(req.query.limit) || 50));
  const offset = Math.max(0, Number(req.query.offset) || 0);
  const { params, where } = attendeeFilters(req);

  const [list, count] = await Promise.all([
    pool.query(`${ATTENDEE_SELECT}${where} ORDER BY t.created_at DESC LIMIT ${limit} OFFSET ${offset}`, params),
    pool.query(
      `SELECT count(*)::int AS total FROM tickets t JOIN users u ON u.id = t.user_id WHERE t.event_id = $1${where}`,
      params,
    ),
  ]);
  res.json({ attendees: list.rows, total: count.rows[0].total });
});

function csvCell(v) {
  const s = String(v ?? '');
  return /[",\n\r]/.test(s) ? `"${s.replace(/"/g, '""')}"` : s;
}

organizerRouter.get('/events/:id/attendees.csv', ...guard, async (req, res) => {
  const event = await ownEvent(pool, req.params.id, req.user.id);
  if (!event) return res.status(404).json({ error: 'Event not found' });

  const { params, where } = attendeeFilters(req);
  const { rows } = await pool.query(
    `${ATTENDEE_SELECT}${where} ORDER BY t.created_at DESC LIMIT 10000`,
    params,
  );
  const lines = [
    'Name,Email,Tier,Status,Ticket code,Purchased at,Checked in at',
    ...rows.map((r) =>
      [r.userName, r.userEmail, r.tierName, r.status, r.code,
       new Date(r.purchasedAt).toISOString(),
       r.checkedInAt ? new Date(r.checkedInAt).toISOString() : '']
        .map(csvCell).join(','),
    ),
  ];
  res.setHeader('Content-Type', 'text/csv; charset=utf-8');
  res.setHeader('Content-Disposition', `attachment; filename="attendees-${event.id}.csv"`);
  res.send(lines.join('\r\n') + '\r\n');
});

// ------------------------------------------------------------------- promos
const promoBody = z.object({
  code: z.string().trim().toUpperCase().regex(/^[A-Z0-9_-]{3,40}$/, 'Code must be 3-40 letters/digits'),
  kind: z.enum(['percent', 'fixed']),
  value: z.number().int().min(1),
  maxUses: z.number().int().min(1).optional(),
  expiresAt: z.string().datetime({ offset: true }).optional(),
}).refine((b) => b.kind !== 'percent' || b.value <= 100, { message: 'Percent value must be 1-100' });

organizerRouter.post('/events/:id/promos', ...guard, async (req, res) => {
  const parsed = promoBody.safeParse(req.body);
  if (!parsed.success) return res.status(400).json({ error: parsed.error.issues[0].message });
  const b = parsed.data;

  const event = await ownEvent(pool, req.params.id, req.user.id);
  if (!event) return res.status(404).json({ error: 'Event not found' });

  try {
    const { rows } = await pool.query(
      `INSERT INTO promo_codes (code, event_id, kind, value, max_uses, expires_at)
       VALUES ($1, $2, $3, $4, $5, $6)
       RETURNING id, code, kind, value, max_uses AS "maxUses", uses, expires_at AS "expiresAt", active`,
      [b.code, event.id, b.kind, b.value, b.maxUses ?? null, b.expiresAt ?? null],
    );
    res.status(201).json({ promo: rows[0] });
  } catch (err) {
    if (err.code === '23505') return res.status(409).json({ error: 'This promo code already exists' });
    console.error(err);
    if (!res.headersSent) res.status(500).json({ error: 'Internal server error' });
  }
});

organizerRouter.get('/events/:id/promos', ...guard, async (req, res) => {
  const event = await ownEvent(pool, req.params.id, req.user.id);
  if (!event) return res.status(404).json({ error: 'Event not found' });
  const { rows } = await pool.query(
    `SELECT id, code, kind, value, max_uses AS "maxUses", uses, expires_at AS "expiresAt", active
     FROM promo_codes WHERE event_id = $1 ORDER BY created_at DESC`,
    [event.id],
  );
  res.json({ promos: rows });
});
