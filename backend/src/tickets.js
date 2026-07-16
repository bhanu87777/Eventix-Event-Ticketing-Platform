import crypto from 'node:crypto';
import { Router } from 'express';
import { pool } from './db.js';
import { requireAuth, requireOrganizer } from './auth.js';
import { passSeatToNextWaiter } from './inventory.js';

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
         e.image_url     AS "imageUrl",
         tt.name         AS "tierName",
         tt.price_cents  AS "priceCents"
  FROM tickets t
  JOIN events e ON e.id = t.event_id
  JOIN ticket_tiers tt ON tt.id = t.tier_id`;

function ticketResponse(row) {
  return { ...row, qr: qrPayload(row.code) };
}

// Ticket purchasing lives in orders.js (create → confirm); the legacy
// POST /events/:id/purchase endpoint was replaced by that flow.

ticketsRouter.get('/me/tickets', requireAuth, async (req, res) => {
  const { rows } = await pool.query(
    `${TICKET_SELECT} WHERE t.user_id = $1 ORDER BY t.created_at DESC`,
    [req.user.id],
  );
  res.json({ tickets: rows.map(ticketResponse) });
});

/**
 * POST /tickets/:id/cancel — attendee releases a seat.
 * Runs under the event-row lock (see the invariant in orders.js): the freed
 * seat is either held for the next waitlisted user or returned to the public.
 */
ticketsRouter.post('/tickets/:id/cancel', requireAuth, async (req, res) => {
  const client = await pool.connect();
  try {
    await client.query('BEGIN');

    const { rows: tRows } = await client.query(
      `SELECT t.id, t.user_id, t.status, t.tier_id, t.event_id,
              e.status AS event_status, e.starts_at
       FROM tickets t JOIN events e ON e.id = t.event_id
       WHERE t.id = $1
       FOR UPDATE OF e`,
      [req.params.id],
    );
    const ticket = tRows[0];
    if (!ticket || Number(ticket.user_id) !== req.user.id) {
      await client.query('ROLLBACK');
      return res.status(404).json({ error: 'Ticket not found' });
    }
    if (ticket.status !== 'valid') {
      await client.query('ROLLBACK');
      return res.status(409).json({ error: `This ticket is ${ticket.status.replace('_', ' ')} and can't be cancelled` });
    }
    if (ticket.event_status !== 'published' || new Date(ticket.starts_at) <= new Date()) {
      await client.query('ROLLBACK');
      return res.status(409).json({ error: 'Tickets can only be cancelled before the event starts' });
    }

    const { rowCount } = await client.query(
      `UPDATE tickets SET status = 'cancelled' WHERE id = $1 AND status = 'valid'`,
      [ticket.id],
    );
    if (rowCount === 0) {
      await client.query('ROLLBACK');
      return res.status(409).json({ error: 'Ticket was already updated' });
    }

    await passSeatToNextWaiter(client, Number(ticket.event_id), Number(ticket.tier_id));

    await client.query('COMMIT');
    const { rows } = await client.query(`${TICKET_SELECT} WHERE t.id = $1`, [ticket.id]);
    return res.json({ ticket: ticketResponse(rows[0]) });
  } catch (err) {
    await client.query('ROLLBACK').catch(() => {});
    // Express 4 does not catch async throws — respond here or crash the process.
    console.error(err);
    if (!res.headersSent) res.status(500).json({ error: 'Internal server error' });
  } finally {
    client.release();
  }
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
  if (existing[0].status === 'cancelled' || existing[0].status === 'void') {
    return res.status(409).json({
      result: 'not_valid',
      error: existing[0].status === 'cancelled' ? 'Ticket was cancelled by the attendee' : 'Ticket was voided (event cancelled)',
    });
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
