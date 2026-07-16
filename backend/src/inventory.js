import { insertNotification } from './notify.js';

/**
 * Shared seat-release mechanics. Both functions MUST be called inside a
 * transaction that already holds `SELECT ... FROM events ... FOR UPDATE`
 * for the event — see the invariant comment atop orders.js.
 */

/**
 * A seat that is still counted in the sold aggregates has just been released
 * (ticket cancelled, or an offer expired). If someone is waiting, hand it to
 * them as a held offer (counters untouched — the hold is the fairness
 * guarantee); otherwise decrement the counters so the seat is publicly
 * buyable again.
 */
export async function passSeatToNextWaiter(client, eventId, tierId) {
  const { rows: next } = await client.query(
    `SELECT id, user_id FROM waitlist
     WHERE event_id = $1 AND status = 'waiting'
     ORDER BY created_at LIMIT 1`,
    [eventId],
  );
  if (next[0]) {
    await client.query(
      `UPDATE waitlist SET status = 'offered', tier_id = $2,
              offer_expires_at = now() + interval '24 hours'
       WHERE id = $1`,
      [next[0].id, tierId],
    );
    const { rows: ev } = await client.query('SELECT title FROM events WHERE id = $1', [eventId]);
    // Note: notification text stays ASCII — the embedded dev Postgres may be
    // initialized with a WIN1252 locale that can't store ✓/emoji characters.
    await insertNotification(client, {
      userId: next[0].user_id,
      type: 'waitlist_offer',
      title: 'A seat opened up!',
      body: `A ticket for "${ev[0].title}" is being held for you for 24 hours. Claim it from the event page before it passes to the next person.`,
      eventId,
    });
  } else {
    await client.query('UPDATE ticket_tiers SET sold = sold - 1 WHERE id = $1', [tierId]);
    await client.query('UPDATE events SET tickets_sold = tickets_sold - 1 WHERE id = $1', [eventId]);
  }
}

/**
 * Expires overdue waitlist offers, cascading each held seat to the next
 * waiter (or back to the public). Runs inside every transaction that already
 * holds the event lock and cares about seat availability — no cron needed.
 */
export async function expireStaleOffers(client, eventId) {
  const { rows: stale } = await client.query(
    `SELECT id, tier_id FROM waitlist
     WHERE event_id = $1 AND status = 'offered' AND offer_expires_at < now()`,
    [eventId],
  );
  for (const offer of stale) {
    await client.query(`UPDATE waitlist SET status = 'expired' WHERE id = $1`, [offer.id]);
    await passSeatToNextWaiter(client, eventId, offer.tier_id);
  }
}
