import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import pg from 'pg';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');

// node-postgres returns BIGINT (int8) as strings by default. Our ids are
// BIGSERIAL and stay far below Number.MAX_SAFE_INTEGER, so parse them.
pg.types.setTypeParser(20, (v) => parseInt(v, 10));

export const pool = new pg.Pool({
  connectionString: process.env.DATABASE_URL,
  max: 20,
});

let embedded = null;

/**
 * Boots the database. With EMBEDDED_PG=1 this starts a real PostgreSQL server
 * managed by npm (no Docker/installation needed) — locking semantics are
 * identical to any other Postgres. Then applies migrations idempotently.
 */
export async function initDb() {
  if (process.env.EMBEDDED_PG === '1') {
    await startEmbeddedPostgres();
  }
  const sql = fs.readFileSync(path.join(ROOT, 'migrations', '001_init.sql'), 'utf8');
  await pool.query(sql);
}

async function startEmbeddedPostgres() {
  const { default: EmbeddedPostgres } = await import('embedded-postgres');
  const dataDir = path.join(ROOT, 'pgdata');
  const url = new URL(process.env.DATABASE_URL);

  embedded = new EmbeddedPostgres({
    databaseDir: dataDir,
    user: url.username,
    password: url.password,
    port: Number(url.port),
    persistent: false, // we manage lifecycle ourselves
  });

  if (!fs.existsSync(path.join(dataDir, 'PG_VERSION'))) {
    await embedded.initialise();
  }
  try {
    await embedded.start();
  } catch (err) {
    // A previous dev-server run may have left Postgres running on this port.
    // If we can connect, reuse it; otherwise surface the real error.
    try {
      await pool.query('SELECT 1');
      embedded = null;
      return;
    } catch {
      throw err;
    }
  }
  try {
    await embedded.createDatabase(url.pathname.slice(1));
  } catch {
    // database already exists
  }
}

export async function shutdownDb() {
  await pool.end().catch(() => {});
  if (embedded) await embedded.stop().catch(() => {});
}
