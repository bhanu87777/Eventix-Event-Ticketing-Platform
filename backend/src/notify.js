/**
 * In-app notifications. insertNotification always takes a client (never the
 * pool) so the write joins the caller's transaction — a notification about a
 * purchase/cancellation must not survive if the transaction rolls back.
 */
export async function insertNotification(client, { userId, type, title, body = '', eventId = null, orderId = null, ticketId = null }) {
  await client.query(
    `INSERT INTO notifications (user_id, type, title, body, event_id, order_id, ticket_id)
     VALUES ($1, $2, $3, $4, $5, $6, $7)`,
    [userId, type, title, body, eventId, orderId, ticketId],
  );
}
