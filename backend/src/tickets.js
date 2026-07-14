import crypto from 'node:crypto';
import { Router } from 'express';
import { pool } from './db.js';
import { requireAuth, requireOrganizer } from './auth.js';

export const ticketsRouter = Router();

/**
 * QR payload format: ETP1.<code>.<signature>
 * The signature is an HMAC over the ticket code with a server-side secret,
 * so a QR code cannot be forged or guessed — only issued by this server.
 */
function signCode(code) {
  return crypto
    .createHmac('sha256', process.env.TICKET_SECRET)
    .update(code)
    .digest('hex')
    .slice(0, 32);
}

export function qrPayload(code) {
  return `ETP1.${code}.${signCode(code)}`;
}

function verifyQr(payload) {
  const parts = String(payload).split('.');
  if (parts.length !== 3 || parts[0] !== 'ETP1') return null;
  const [, code, sig] = parts;
  const expected = signCode(code);
  const a = Buffer.from(sig);
  const b = Buffer.from(expected);
  if (a.length !== b.length || !crypto.timingSafeEqual(a, b)) return null;
  return code;
}

const TICKET_SELECT = `
  SELECT t.id, t.code, t.status,
         t.checked_in_at AS "checkedInAt",
         t.created_at    AS "purchasedAt",
         e.id            AS "eventId",
         e.title         AS "eventTitle",
         e.venue,
         e.starts_at     AS "startsAt",
         e.image_url     AS "imageUrl"
  FROM tickets t JOIN events e ON e.id = t.event_id`;

function ticketResponse(row) {
  return { ...row, qr: qrPayload(row.code) };
}

/**
 * POST /events/:id/purchase — the race-condition-safe seat sale.
 *
 * Concurrency strategy (pessimistic locking):
 *   1. BEGIN a transaction.
 *   2. SELECT ... FOR UPDATE the event row. Every concurrent purchase for the
 *      same event queues on this row lock — Postgres serializes them for us.
 *   3. Inside the lock: check remaining capacity, insert the ticket, and
 *      increment tickets_sold. No other transaction can interleave between
 *      the check and the write, which is exactly the check-then-act race
 *      that causes overselling.
 *   4. COMMIT releases the lock for the next buyer in line.
 *
 * Idempotency: clients send an Idempotency-Key header. Retrying the same key
 * (e.g. after a network timeout) returns the original ticket instead of
 * issuing a second one. The UNIQUE constraint on purchases.idempotency_key
 * makes this safe even if two retries race each other.
 */
ticketsRouter.post('/events/:id/purchase', requireAuth, async (req, res) => {
  const idempotencyKey = req.headers['idempotency-key'];
  if (!idempotencyKey || typeof idempotencyKey !== 'string') {
    return res.status(400).json({ error: 'Idempotency-Key header is required' });
  }

  const existing = await findPurchase(idempotencyKey);
  if (existing) return res.json({ ticket: existing, replayed: true });

  const client = await pool.connect();
  try {
    await client.query('BEGIN');

    const { rows: eventRows } = await client.query(
      'SELECT id, capacity, tickets_sold FROM events WHERE id = $1 FOR UPDATE',
      [req.params.id],
    );
    const event = eventRows[0];
    if (!event) {
      await client.query('ROLLBACK');
      return res.status(404).json({ error: 'Event not found' });
    }
    if (event.tickets_sold >= event.capacity) {
      await client.query('ROLLBACK');
      return res.status(409).json({ error: 'Sold out', code: 'SOLD_OUT' });
    }

    const code = crypto.randomBytes(10).toString('hex');
    const { rows: ticketRows } = await client.query(
      `INSERT INTO tickets (code, event_id, user_id) VALUES ($1, $2, $3) RETURNING id`,
      [code, event.id, req.user.id],
    );
    await client.query('UPDATE events SET tickets_sold = tickets_sold + 1 WHERE id = $1', [
      event.id,
    ]);
    await client.query(
      `INSERT INTO purchases (idempotency_key, user_id, ticket_id) VALUES ($1, $2, $3)`,
      [idempotencyKey, req.user.id, ticketRows[0].id],
    );

    await client.query('COMMIT');

    // Reuse the client we already hold: fetching a second connection from the
    // pool here would deadlock under load (every pooled client busy in this
    // handler, each waiting for one more).
    const { rows } = await client.query(`${TICKET_SELECT} WHERE t.id = $1`, [ticketRows[0].id]);
    return res.status(201).json({ ticket: ticketResponse(rows[0]) });
  } catch (err) {
    await client.query('ROLLBACK').catch(() => {});
    if (err.code === '23505') {
      // Two requests with the same idempotency key raced; the other one won.
      const winner = await findPurchase(idempotencyKey);
      if (winner) return res.json({ ticket: winner, replayed: true });
    }
    throw err;
  } finally {
    client.release();
  }
});

async function findPurchase(idempotencyKey) {
  const { rows } = await pool.query(
    `${TICKET_SELECT}
     JOIN purchases p ON p.ticket_id = t.id
     WHERE p.idempotency_key = $1`,
    [idempotencyKey],
  );
  return rows[0] ? ticketResponse(rows[0]) : null;
}

ticketsRouter.get('/me/tickets', requireAuth, async (req, res) => {
  const { rows } = await pool.query(
    `${TICKET_SELECT} WHERE t.user_id = $1 ORDER BY t.created_at DESC`,
    [req.user.id],
  );
  res.json({ tickets: rows.map(ticketResponse) });
});

/**
 * POST /checkin — the second race condition: two staff scanning the same
 * ticket at two gates simultaneously. A single conditional UPDATE is atomic:
 * exactly one scanner flips status from 'valid' to 'checked_in'; the other
 * matches zero rows and gets "already checked in".
 */
ticketsRouter.post('/checkin', requireAuth, requireOrganizer, async (req, res) => {
  const code = verifyQr(req.body?.qr);
  if (!code) {
    return res.status(400).json({ result: 'invalid', error: 'Invalid or forged QR code' });
  }

  const { rows: updated } = await pool.query(
    `UPDATE tickets t
     SET status = 'checked_in', checked_in_at = now()
     FROM events e
     WHERE t.code = $1 AND t.event_id = e.id AND e.organizer_id = $2
       AND t.status = 'valid'
     RETURNING t.id`,
    [code, req.user.id],
  );

  if (updated[0]) {
    const info = await checkinInfo(code);
    return res.json({ result: 'ok', ...info });
  }

  // Zero rows updated — figure out why for a helpful scanner message.
  const { rows: existing } = await pool.query(
    `SELECT t.status, t.checked_in_at, e.organizer_id
     FROM tickets t JOIN events e ON e.id = t.event_id
     WHERE t.code = $1`,
    [code],
  );
  if (!existing[0]) {
    return res.status(404).json({ result: 'not_found', error: 'Ticket not found' });
  }
  if (existing[0].organizer_id !== req.user.id) {
    return res.status(403).json({ result: 'wrong_event', error: 'Ticket belongs to another organizer’s event' });
  }
  const info = await checkinInfo(code);
  return res.status(409).json({ result: 'already_checked_in', error: 'Ticket already checked in', ...info });
});

async function checkinInfo(code) {
  const { rows } = await pool.query(
    `SELECT u.name AS "attendeeName", e.title AS "eventTitle",
            t.checked_in_at AS "checkedInAt"
     FROM tickets t
     JOIN users u ON u.id = t.user_id
     JOIN events e ON e.id = t.event_id
     WHERE t.code = $1`,
    [code],
  );
  return rows[0] ?? {};
}
