import { Router } from 'express';
import { pool } from './db.js';
import { requireAuth } from './auth.js';
import { expireStaleOffers, passSeatToNextWaiter } from './inventory.js';

export const waitlistRouter = Router();

/**
 * POST /events/:id/waitlist — join (or rejoin) the line for a sold-out event.
 * Runs under the event lock so the sold-out check can't race a cancellation.
 */
waitlistRouter.post('/events/:id/waitlist', requireAuth, async (req, res) => {
  const client = await pool.connect();
  try {
    await client.query('BEGIN');
    const { rows: evRows } = await client.query(
      `SELECT id, status, starts_at, capacity, tickets_sold FROM events WHERE id = $1 FOR UPDATE`,
      [req.params.id],
    );
    const event = evRows[0];
    if (!event) {
      await client.query('ROLLBACK');
      return res.status(404).json({ error: 'Event not found' });
    }
    if (event.status !== 'published' || new Date(event.starts_at) <= new Date()) {
      await client.query('ROLLBACK');
      return res.status(409).json({ error: 'This event is not open for a waitlist', code: 'EVENT_UNAVAILABLE' });
    }

    await expireStaleOffers(client, event.id);

    // Re-read counters — the expiry sweep may have freed seats.
    const { rows: fresh } = await client.query(
      'SELECT capacity, tickets_sold FROM events WHERE id = $1',
      [event.id],
    );
    if (fresh[0].tickets_sold < fresh[0].capacity) {
      await client.query('ROLLBACK');
      return res.status(409).json({ error: 'Seats are available — buy a ticket instead', code: 'NOT_SOLD_OUT' });
    }

    const { rows: existing } = await client.query(
      `SELECT id, status FROM waitlist WHERE event_id = $1 AND user_id = $2`,
      [event.id, req.user.id],
    );
    if (existing[0] && ['waiting', 'offered'].includes(existing[0].status)) {
      await client.query('ROLLBACK');
      return res.status(409).json({ error: 'You are already on the waitlist' });
    }

    let entry;
    if (existing[0]) {
      const { rows } = await client.query(
        `UPDATE waitlist SET status = 'waiting', tier_id = NULL, offer_expires_at = NULL, created_at = now()
         WHERE id = $1 RETURNING id, status`,
        [existing[0].id],
      );
      entry = rows[0];
    } else {
      const { rows } = await client.query(
        `INSERT INTO waitlist (event_id, user_id) VALUES ($1, $2) RETURNING id, status`,
        [event.id, req.user.id],
      );
      entry = rows[0];
    }
    await client.query('COMMIT');
    return res.status(201).json({ entry });
  } catch (err) {
    await client.query('ROLLBACK').catch(() => {});
    console.error(err);
    if (!res.headersSent) res.status(500).json({ error: 'Internal server error' });
  } finally {
    client.release();
  }
});

/**
 * DELETE /events/:id/waitlist — leave the line. Leaving while holding an
 * offer cascades the held seat onward under the event lock.
 */
waitlistRouter.delete('/events/:id/waitlist', requireAuth, async (req, res) => {
  const client = await pool.connect();
  try {
    await client.query('BEGIN');
    const { rows: evRows } = await client.query(
      `SELECT id FROM events WHERE id = $1 FOR UPDATE`,
      [req.params.id],
    );
    if (!evRows[0]) {
      await client.query('ROLLBACK');
      return res.status(404).json({ error: 'Event not found' });
    }
    const { rows: existing } = await client.query(
      `SELECT id, status, tier_id FROM waitlist WHERE event_id = $1 AND user_id = $2`,
      [req.params.id, req.user.id],
    );
    const entry = existing[0];
    if (!entry || !['waiting', 'offered'].includes(entry.status)) {
      await client.query('ROLLBACK');
      return res.status(404).json({ error: 'You are not on this waitlist' });
    }
    await client.query(`UPDATE waitlist SET status = 'left' WHERE id = $1`, [entry.id]);
    if (entry.status === 'offered') {
      await passSeatToNextWaiter(client, Number(req.params.id), Number(entry.tier_id));
    }
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

/** GET /me/waitlist — my entries with event summaries. */
waitlistRouter.get('/me/waitlist', requireAuth, async (req, res) => {
  const { rows } = await pool.query(
    `SELECT w.id, w.status, w.tier_id AS "tierId", w.offer_expires_at AS "offerExpiresAt",
            w.created_at AS "joinedAt",
            e.id AS "eventId", e.title AS "eventTitle", e.venue, e.starts_at AS "startsAt",
            e.image_url AS "imageUrl"
     FROM waitlist w JOIN events e ON e.id = w.event_id
     WHERE w.user_id = $1 AND w.status IN ('waiting', 'offered')
     ORDER BY w.created_at DESC`,
    [req.user.id],
  );
  res.json({ entries: rows });
});
