import express from 'express';
import { initDb, shutdownDb, pool } from './db.js';
import { authRouter } from './auth.js';
import { eventsRouter } from './events.js';
import { ticketsRouter } from './tickets.js';
import { seedIfEmpty } from './seed.js';

const app = express();
app.use(express.json());

app.get('/health', (_req, res) => res.json({ ok: true }));
app.use('/auth', authRouter);
app.use('/events', eventsRouter);
app.use('/', ticketsRouter);

// eslint-disable-next-line no-unused-vars
app.use((err, _req, res, _next) => {
  console.error(err);
  res.status(500).json({ error: 'Internal server error' });
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
