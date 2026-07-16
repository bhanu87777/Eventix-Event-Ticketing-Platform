import crypto from 'node:crypto';
import { Router } from 'express';
import { z } from 'zod';
import { pool } from './db.js';
import { requireAuth } from './auth.js';
import { insertNotification } from './notify.js';
import { expireStaleOffers } from './inventory.js';
import { qrPayload } from './tickets.js';

export const ordersRouter = Router();

/**
 * THE INVENTORY INVARIANT
 * -----------------------
 * Every mutation of seat counters (ticket_tiers.sold, events.tickets_sold)
 * happens inside a transaction that FIRST takes `SELECT ... FROM events
 * WHERE id = $1 FOR UPDATE`. The event row is the single serialization point
 * — concurrent buyers, cancellations, and waitlist cascades queue on it, so
 * check-then-act races are impossible. The tier-level and event-level CHECK
 * constraints remain as defense in depth.
 *
 * Call sites that hold the lock: order confirm (here), ticket cancel
 * (tickets.js), waitlist join/leave (waitlist.js), tier edits (organizer.js).
 * Pending orders hold NO inventory — availability is checked only at confirm.
 */

const ORDER_TTL_MINUTES = 15;

const orderItemsInput = z.array(z.object({
  tierId: z.number().int().positive(),
  quantity: z.number().int().min(1).max(10),
})).min(1).max(5);

const quoteBody = z.object({
  eventId: z.number().int().positive(),
  items: orderItemsInput,
  promoCode: z.string().trim().toUpperCase().max(40).optional(),
});

const createOrderBody = quoteBody.extend({
  waitlistOfferId: z.number().int().positive().optional(),
}).refine((b) => b.items.reduce((s, i) => s + i.quantity, 0) <= 10, {
  message: 'Max 10 tickets per order',
});

const confirmBody = z.object({
  payment: z.object({ outcome: z.enum(['success', 'failure']) }),
});

/** Prices items against DB tier prices and soft-validates the promo. */
async function priceOrder(db, eventId, items, promoCode) {
  const { rows: tiers } = await db.query(
    `SELECT id, name, price_cents AS "priceCents", capacity, sold
     FROM ticket_tiers WHERE event_id = $1`,
    [eventId],
  );
  const tierById = new Map(tiers.map((t) => [t.id, t]));

  const lineItems = [];
  let subtotal = 0;
  for (const item of items) {
    const tier = tierById.get(item.tierId);
    if (!tier) return { error: 'Unknown ticket tier for this event' };
    lineItems.push({
      tierId: tier.id,
      tierName: tier.name,
      quantity: item.quantity,
      unitPriceCents: tier.priceCents,
      totalCents: tier.priceCents * item.quantity,
    });
    subtotal += tier.priceCents * item.quantity;
  }

  let promo = null;
  let discount = 0;
  let promoValid = null;
  let promoMessage = null;
  if (promoCode) {
    const { rows } = await db.query('SELECT * FROM promo_codes WHERE code = $1', [promoCode]);
    const p = rows[0];
    if (!p) promoMessage = 'Unknown promo code';
    else if (!p.active) promoMessage = 'This code is no longer active';
    else if (p.expires_at && new Date(p.expires_at) < new Date()) promoMessage = 'This code has expired';
    else if (p.event_id && Number(p.event_id) !== Number(eventId)) promoMessage = 'This code is for a different event';
    else if (p.max_uses !== null && p.uses >= p.max_uses) promoMessage = 'This code has been fully redeemed';
    else {
      promo = p;
      discount = p.kind === 'percent' ? Math.floor((subtotal * p.value) / 100) : Math.min(p.value, subtotal);
    }
    promoValid = Boolean(promo);
  }

  return {
    lineItems,
    subtotalCents: subtotal,
    discountCents: discount,
    totalCents: subtotal - discount,
    promo,
    promoValid,
    promoMessage,
    tierById,
  };
}

async function loadOrder(db, orderId) {
  const { rows } = await db.query(
    `SELECT o.id, o.user_id AS "userId", o.event_id AS "eventId", o.status,
            o.subtotal_cents AS "subtotalCents", o.discount_cents AS "discountCents",
            o.total_cents AS "totalCents", o.currency,
            o.waitlist_offer_id AS "waitlistOfferId", o.promo_code_id AS "promoCodeId",
            o.expires_at AS "expiresAt", o.created_at AS "createdAt",
            e.title AS "eventTitle",
            p.code AS "promoCode"
     FROM orders o
     JOIN events e ON e.id = o.event_id
     LEFT JOIN promo_codes p ON p.id = o.promo_code_id
     WHERE o.id = $1`,
    [orderId],
  );
  const order = rows[0];
  if (!order) return null;
  const { rows: items } = await db.query(
    `SELECT oi.tier_id AS "tierId", tt.name AS "tierName", oi.quantity,
            oi.unit_price_cents AS "unitPriceCents",
            oi.quantity * oi.unit_price_cents AS "totalCents"
     FROM order_items oi JOIN ticket_tiers tt ON tt.id = oi.tier_id
     WHERE oi.order_id = $1 ORDER BY oi.id`,
    [orderId],
  );
  order.items = items;
  return order;
}

function publicOrder(order) {
  const { userId, promoCodeId, waitlistOfferId, ...rest } = order;
  return rest;
}

async function orderTickets(db, orderId) {
  const { rows } = await db.query(
    `SELECT t.id, t.code, t.status,
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
     JOIN ticket_tiers tt ON tt.id = t.tier_id
     WHERE t.order_id = $1 ORDER BY t.id`,
    [orderId],
  );
  return rows.map((r) => ({ ...r, qr: qrPayload(r.code) }));
}

/** POST /orders/quote — stateless pricing + promo preview. No writes. */
ordersRouter.post('/quote', requireAuth, async (req, res) => {
  const parsed = quoteBody.safeParse(req.body);
  if (!parsed.success) {
    return res.status(400).json({ error: parsed.error.issues[0].message });
  }
  const { eventId, items, promoCode } = parsed.data;
  const quote = await priceOrder(pool, eventId, items, promoCode);
  if (quote.error) return res.status(400).json({ error: quote.error });
  const { lineItems, subtotalCents, discountCents, totalCents, promoValid, promoMessage } = quote;
  res.json({ quote: { lineItems, subtotalCents, discountCents, totalCents, promoValid, promoMessage } });
});

/** POST /orders — create a pending order (a priced quote; holds no seats). */
ordersRouter.post('/', requireAuth, async (req, res) => {
  const idempotencyKey = req.headers['idempotency-key'];
  if (!idempotencyKey || typeof idempotencyKey !== 'string') {
    return res.status(400).json({ error: 'Idempotency-Key header is required' });
  }
  const parsed = createOrderBody.safeParse(req.body);
  if (!parsed.success) {
    return res.status(400).json({ error: parsed.error.issues[0].message });
  }
  const { eventId, items, promoCode, waitlistOfferId } = parsed.data;

  const { rows: evRows } = await pool.query(
    `SELECT id, status, starts_at FROM events WHERE id = $1`,
    [eventId],
  );
  const event = evRows[0];
  if (!event) return res.status(404).json({ error: 'Event not found' });
  if (event.status !== 'published') return res.status(409).json({ error: 'This event has been cancelled', code: 'EVENT_UNAVAILABLE' });
  if (new Date(event.starts_at) <= new Date()) {
    return res.status(409).json({ error: 'This event has already started', code: 'EVENT_UNAVAILABLE' });
  }

  // Waitlist claim: the order must be exactly the offered seat.
  if (waitlistOfferId) {
    const { rows: wl } = await pool.query(
      `SELECT id, tier_id, status, offer_expires_at FROM waitlist
       WHERE id = $1 AND user_id = $2 AND event_id = $3`,
      [waitlistOfferId, req.user.id, eventId],
    );
    const offer = wl[0];
    if (!offer || offer.status !== 'offered') {
      return res.status(409).json({ error: 'No active seat offer to claim', code: 'OFFER_INVALID' });
    }
    if (new Date(offer.offer_expires_at) < new Date()) {
      return res.status(410).json({ error: 'Your seat offer has expired', code: 'OFFER_EXPIRED' });
    }
    if (items.length !== 1 || items[0].quantity !== 1 || items[0].tierId !== Number(offer.tier_id)) {
      return res.status(400).json({ error: 'A waitlist claim is for exactly the one offered seat' });
    }
  }

  const quote = await priceOrder(pool, eventId, items, promoCode);
  if (quote.error) return res.status(400).json({ error: quote.error });
  if (promoCode && !quote.promoValid) {
    return res.status(400).json({ error: quote.promoMessage, code: 'PROMO_INVALID' });
  }

  // Fail-fast availability hint (authoritative check happens at confirm).
  for (const item of quote.lineItems) {
    const tier = quote.tierById.get(item.tierId);
    const credit = waitlistOfferId && item.tierId === items[0].tierId ? 1 : 0;
    if (tier.capacity - tier.sold + credit < item.quantity) {
      return res.status(409).json({ error: `Not enough seats left in ${tier.name}`, code: 'SOLD_OUT' });
    }
  }

  try {
    const { rows: orderRows } = await pool.query(
      `INSERT INTO orders (user_id, event_id, idempotency_key, promo_code_id, waitlist_offer_id,
                           subtotal_cents, discount_cents, total_cents,
                           expires_at)
       VALUES ($1, $2, $3, $4, $5, $6, $7, $8, now() + interval '${ORDER_TTL_MINUTES} minutes')
       RETURNING id`,
      [req.user.id, eventId, idempotencyKey, quote.promo?.id ?? null, waitlistOfferId ?? null,
       quote.subtotalCents, quote.discountCents, quote.totalCents],
    );
    for (const item of quote.lineItems) {
      await pool.query(
        `INSERT INTO order_items (order_id, tier_id, quantity, unit_price_cents)
         VALUES ($1, $2, $3, $4)`,
        [orderRows[0].id, item.tierId, item.quantity, item.unitPriceCents],
      );
    }
    const order = await loadOrder(pool, orderRows[0].id);
    return res.status(201).json({ order: publicOrder(order) });
  } catch (err) {
    if (err.code === '23505') {
      const { rows } = await pool.query('SELECT id FROM orders WHERE idempotency_key = $1', [idempotencyKey]);
      if (rows[0]) {
        const order = await loadOrder(pool, rows[0].id);
        if (order.userId === req.user.id) {
          return res.json({ order: publicOrder(order), replayed: true });
        }
      }
    }
    console.error(err);
    if (!res.headersSent) res.status(500).json({ error: 'Internal server error' });
  }
});

/**
 * POST /orders/:id/confirm — apply the (simulated) payment outcome.
 * Success runs the extended race-safe issue path under the event-row lock.
 */
ordersRouter.post('/:id/confirm', requireAuth, async (req, res) => {
  const parsed = confirmBody.safeParse(req.body);
  if (!parsed.success) {
    return res.status(400).json({ error: parsed.error.issues[0].message });
  }
  const outcome = parsed.data.payment.outcome;

  const client = await pool.connect();
  try {
    await client.query('BEGIN');

    // Lock the order row: double-confirms queue here and replay the result.
    const { rows: orderRows } = await client.query(
      `SELECT * FROM orders WHERE id = $1 AND user_id = $2 FOR UPDATE`,
      [req.params.id, req.user.id],
    );
    const order = orderRows[0];
    if (!order) {
      await client.query('ROLLBACK');
      return res.status(404).json({ error: 'Order not found' });
    }

    if (order.status === 'paid') {
      await client.query('COMMIT');
      const full = await loadOrder(pool, order.id);
      return res.json({ order: publicOrder(full), tickets: await orderTickets(pool, order.id), replayed: true });
    }
    if (order.status === 'failed' || order.status === 'expired') {
      await client.query('ROLLBACK');
      return res.status(order.status === 'failed' ? 402 : 410).json({
        error: order.status === 'failed' ? 'Payment already failed for this order' : 'This order has expired',
        code: order.status === 'failed' ? 'PAYMENT_FAILED' : 'ORDER_EXPIRED',
      });
    }
    if (new Date(order.expires_at) < new Date()) {
      await client.query(`UPDATE orders SET status = 'expired' WHERE id = $1`, [order.id]);
      await client.query('COMMIT');
      return res.status(410).json({ error: 'This order has expired — start a new checkout', code: 'ORDER_EXPIRED' });
    }

    if (outcome === 'failure') {
      await client.query(`UPDATE orders SET status = 'failed' WHERE id = $1`, [order.id]);
      await client.query('COMMIT');
      return res.status(402).json({ error: 'Payment failed', code: 'PAYMENT_FAILED' });
    }

    // ---- success: take THE lock and issue seats atomically ----
    const { rows: evRows } = await client.query(
      `SELECT id, title, status, starts_at FROM events WHERE id = $1 FOR UPDATE`,
      [order.event_id],
    );
    const event = evRows[0];
    if (!event || event.status !== 'published' || new Date(event.starts_at) <= new Date()) {
      await client.query('ROLLBACK');
      return res.status(409).json({ error: 'This event is no longer available', code: 'EVENT_UNAVAILABLE' });
    }

    await expireStaleOffers(client, event.id);

    // Waitlist claim: verify the held seat is still ours (post-expiry sweep).
    let heldTierId = null;
    if (order.waitlist_offer_id) {
      const { rows: wl } = await client.query(
        `SELECT tier_id FROM waitlist
         WHERE id = $1 AND user_id = $2 AND status = 'offered' AND offer_expires_at >= now()`,
        [order.waitlist_offer_id, req.user.id],
      );
      if (!wl[0]) {
        await client.query('ROLLBACK');
        return res.status(410).json({ error: 'Your seat offer has expired', code: 'OFFER_EXPIRED' });
      }
      heldTierId = Number(wl[0].tier_id);
    }

    const { rows: items } = await client.query(
      `SELECT oi.tier_id, oi.quantity, tt.capacity, tt.sold, tt.name
       FROM order_items oi JOIN ticket_tiers tt ON tt.id = oi.tier_id
       WHERE oi.order_id = $1`,
      [order.id],
    );
    for (const item of items) {
      const credit = heldTierId === Number(item.tier_id) ? 1 : 0;
      if (item.capacity - item.sold + credit < item.quantity) {
        await client.query('ROLLBACK');
        return res.status(409).json({ error: `Not enough seats left in ${item.name}`, code: 'SOLD_OUT' });
      }
    }

    // Hard promo redemption: the conditional UPDATE is the concurrency guard.
    if (order.promo_code_id) {
      const { rowCount } = await client.query(
        `UPDATE promo_codes SET uses = uses + 1
         WHERE id = $1 AND active
           AND (expires_at IS NULL OR expires_at > now())
           AND (max_uses IS NULL OR uses < max_uses)`,
        [order.promo_code_id],
      );
      if (rowCount === 0) {
        await client.query('ROLLBACK');
        return res.status(409).json({ error: 'This promo code is no longer valid', code: 'PROMO_INVALID' });
      }
      await client.query(
        `INSERT INTO promo_redemptions (promo_id, order_id, user_id) VALUES ($1, $2, $3)`,
        [order.promo_code_id, order.id, req.user.id],
      );
    }

    let totalIssued = 0;
    for (const item of items) {
      for (let i = 0; i < item.quantity; i++) {
        await client.query(
          `INSERT INTO tickets (code, event_id, user_id, tier_id, order_id)
           VALUES ($1, $2, $3, $4, $5)`,
          [crypto.randomBytes(10).toString('hex'), event.id, req.user.id, item.tier_id, order.id],
        );
      }
      const inc = item.quantity - (heldTierId === Number(item.tier_id) ? 1 : 0);
      if (inc > 0) {
        await client.query('UPDATE ticket_tiers SET sold = sold + $2 WHERE id = $1', [item.tier_id, inc]);
      }
      totalIssued += item.quantity;
    }
    const aggregateInc = totalIssued - (heldTierId !== null ? 1 : 0);
    if (aggregateInc > 0) {
      await client.query('UPDATE events SET tickets_sold = tickets_sold + $2 WHERE id = $1', [event.id, aggregateInc]);
    }
    if (order.waitlist_offer_id) {
      await client.query(`UPDATE waitlist SET status = 'claimed' WHERE id = $1`, [order.waitlist_offer_id]);
    }

    await client.query(
      `UPDATE orders SET status = 'paid', paid_at = now() WHERE id = $1`,
      [order.id],
    );
    await insertNotification(client, {
      userId: req.user.id,
      type: 'order_paid',
      title: 'Tickets confirmed',
      body: `${totalIssued} ticket${totalIssued === 1 ? '' : 's'} for "${event.title}" - now in your wallet.`,
      eventId: event.id,
      orderId: order.id,
    });

    await client.query('COMMIT');

    // Reuse the held client for the read-back (pool may be saturated under load).
    const full = await loadOrder(client, order.id);
    const tickets = await orderTickets(client, order.id);
    return res.json({ order: publicOrder(full), tickets });
  } catch (err) {
    await client.query('ROLLBACK').catch(() => {});
    // Express 4 does not catch async throws — respond here or crash the process.
    console.error(err);
    if (!res.headersSent) res.status(500).json({ error: 'Internal server error' });
  } finally {
    client.release();
  }
});

/** GET /orders/:id — lazy-expires stale pending orders on read. */
ordersRouter.get('/:id', requireAuth, async (req, res) => {
  const order = await loadOrder(pool, req.params.id);
  if (!order || order.userId !== req.user.id) return res.status(404).json({ error: 'Order not found' });
  if (order.status === 'pending' && new Date(order.expiresAt) < new Date()) {
    await pool.query(`UPDATE orders SET status = 'expired' WHERE id = $1 AND status = 'pending'`, [order.id]);
    order.status = 'expired';
  }
  const tickets = order.status === 'paid' ? await orderTickets(pool, order.id) : [];
  res.json({ order: publicOrder(order), tickets });
});
