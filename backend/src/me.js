import { Router } from 'express';
import bcrypt from 'bcryptjs';
import jwt from 'jsonwebtoken';
import { z } from 'zod';
import { pool } from './db.js';
import { requireAuth } from './auth.js';

export const meRouter = Router();
meRouter.use(requireAuth);

function issueToken(user) {
  return jwt.sign(
    { sub: String(user.id), role: user.role, name: user.name, email: user.email },
    process.env.JWT_SECRET,
    { expiresIn: '7d' },
  );
}

// ------------------------------------------------------------------ profile
meRouter.get('/me', async (req, res) => {
  // From the DB, not the JWT — the name may have changed since sign-in.
  const { rows } = await pool.query(
    'SELECT id, email, name, role, created_at AS "createdAt" FROM users WHERE id = $1',
    [req.user.id],
  );
  if (!rows[0]) return res.status(401).json({ error: 'Account no longer exists' });
  res.json({ user: rows[0] });
});

const patchMeBody = z.object({ name: z.string().trim().min(1).max(80) });

meRouter.patch('/me', async (req, res) => {
  const parsed = patchMeBody.safeParse(req.body);
  if (!parsed.success) return res.status(400).json({ error: parsed.error.issues[0].message });
  const { rows } = await pool.query(
    'UPDATE users SET name = $1 WHERE id = $2 RETURNING id, email, name, role',
    [parsed.data.name, req.user.id],
  );
  // The JWT embeds the name — hand back a fresh token so the client can swap it.
  res.json({ user: rows[0], token: issueToken(rows[0]) });
});

const passwordBody = z.object({
  currentPassword: z.string().min(1),
  newPassword: z.string().min(8).max(200),
});

meRouter.post('/me/password', async (req, res) => {
  const parsed = passwordBody.safeParse(req.body);
  if (!parsed.success) return res.status(400).json({ error: 'New password must be at least 8 characters' });
  const { rows } = await pool.query('SELECT password_hash FROM users WHERE id = $1', [req.user.id]);
  if (!rows[0] || !(await bcrypt.compare(parsed.data.currentPassword, rows[0].password_hash))) {
    return res.status(401).json({ error: 'Current password is incorrect' });
  }
  const hash = await bcrypt.hash(parsed.data.newPassword, 10);
  await pool.query('UPDATE users SET password_hash = $1 WHERE id = $2', [hash, req.user.id]);
  res.json({ ok: true });
});

// ---------------------------------------------------------------- favorites
meRouter.post('/events/:id/favorite', async (req, res) => {
  const { rows } = await pool.query('SELECT id FROM events WHERE id = $1', [req.params.id]);
  if (!rows[0]) return res.status(404).json({ error: 'Event not found' });
  await pool.query(
    'INSERT INTO favorites (user_id, event_id) VALUES ($1, $2) ON CONFLICT DO NOTHING',
    [req.user.id, req.params.id],
  );
  res.status(204).end();
});

meRouter.delete('/events/:id/favorite', async (req, res) => {
  await pool.query('DELETE FROM favorites WHERE user_id = $1 AND event_id = $2', [req.user.id, req.params.id]);
  res.status(204).end();
});

// ------------------------------------------------------------ notifications
meRouter.get('/me/notifications', async (req, res) => {
  const limit = Math.min(100, Math.max(1, Number(req.query.limit) || 50));
  const offset = Math.max(0, Number(req.query.offset) || 0);
  const [list, unread] = await Promise.all([
    pool.query(
      `SELECT n.id, n.type, n.title, n.body,
              n.event_id AS "eventId", n.order_id AS "orderId", n.ticket_id AS "ticketId",
              n.is_read AS "read", n.created_at AS "createdAt",
              (SELECT w.id FROM waitlist w
               WHERE n.type = 'waitlist_offer' AND w.event_id = n.event_id
                 AND w.user_id = n.user_id AND w.status = 'offered') AS "offerId"
       FROM notifications n
       WHERE n.user_id = $1
       ORDER BY n.created_at DESC LIMIT ${limit} OFFSET ${offset}`,
      [req.user.id],
    ),
    pool.query(
      'SELECT count(*)::int AS n FROM notifications WHERE user_id = $1 AND NOT is_read',
      [req.user.id],
    ),
  ]);
  res.json({ notifications: list.rows, unreadCount: unread.rows[0].n });
});

meRouter.get('/me/notifications/unread-count', async (req, res) => {
  const { rows } = await pool.query(
    'SELECT count(*)::int AS n FROM notifications WHERE user_id = $1 AND NOT is_read',
    [req.user.id],
  );
  res.json({ unreadCount: rows[0].n });
});

meRouter.post('/me/notifications/read-all', async (req, res) => {
  await pool.query('UPDATE notifications SET is_read = true WHERE user_id = $1 AND NOT is_read', [req.user.id]);
  res.status(204).end();
});

meRouter.post('/notifications/:id/read', async (req, res) => {
  await pool.query('UPDATE notifications SET is_read = true WHERE id = $1 AND user_id = $2', [
    req.params.id, req.user.id,
  ]);
  res.status(204).end();
});

// ------------------------------------------------------------------- orders
meRouter.get('/me/orders', async (req, res) => {
  const limit = Math.min(50, Math.max(1, Number(req.query.limit) || 20));
  const offset = Math.max(0, Number(req.query.offset) || 0);
  const { rows } = await pool.query(
    `SELECT o.id, o.status, o.total_cents AS "totalCents", o.currency,
            o.created_at AS "createdAt", e.title AS "eventTitle", e.id AS "eventId"
     FROM orders o JOIN events e ON e.id = o.event_id
     WHERE o.user_id = $1 ORDER BY o.created_at DESC LIMIT ${limit} OFFSET ${offset}`,
    [req.user.id],
  );
  res.json({ orders: rows });
});
