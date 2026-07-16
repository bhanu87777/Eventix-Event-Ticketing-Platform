import express from 'express';
import { initDb, shutdownDb, pool } from './db.js';
import { authRouter } from './auth.js';
import { eventsRouter, categoriesRouter } from './events.js';
import { ticketsRouter } from './tickets.js';
import { ordersRouter } from './orders.js';
import { organizerRouter } from './organizer.js';
import { waitlistRouter } from './waitlist.js';
import { meRouter } from './me.js';
import { seedIfEmpty } from './seed.js';

const app = express();
app.use(express.json());

app.get('/health', (_req, res) => res.json({ ok: true }));
app.use('/auth', authRouter);
app.use('/categories', categoriesRouter);
app.use('/orders', ordersRouter);
// Organizer routes must mount before the public events router so
// PATCH /events/:id and friends resolve here (methods don't collide today,
// but explicit ordering keeps it safe).
app.use('/', organizerRouter);
app.use('/events', eventsRouter);
app.use('/', waitlistRouter);
app.use('/', meRouter);
app.use('/', ticketsRouter);

// eslint-disable-next-line no-unused-vars
app.use((err, _req, res, _next) => {
  console.error(err);
  res.status(500).json({ error: 'Internal server error' });
});

// Last-resort guard: an async route error that escapes its handler must not
// take the whole API down (Express 4 doesn't route async rejections).
process.on('unhandledRejection', (err) => {
  console.error('Unhandled rejection:', err);
});

const port = Number(process.env.PORT ?? 3000);

await initDb();
await seedIfEmpty(pool);

const server = app.listen(port, () => {
  console.log(`Event Ticketing API listening on http://localhost:${port}`);
});

for (const signal of ['SIGINT', 'SIGTERM']) {
  process.on(signal, async () => {
    server.close();
    await shutdownDb();
    process.exit(0);
  });
}
