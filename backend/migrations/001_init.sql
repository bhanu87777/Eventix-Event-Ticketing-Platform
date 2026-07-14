CREATE TABLE IF NOT EXISTS users (
  id            BIGSERIAL PRIMARY KEY,
  email         TEXT NOT NULL UNIQUE,
  password_hash TEXT NOT NULL,
  name          TEXT NOT NULL,
  role          TEXT NOT NULL DEFAULT 'attendee' CHECK (role IN ('attendee', 'organizer')),
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS events (
  id            BIGSERIAL PRIMARY KEY,
  organizer_id  BIGINT NOT NULL REFERENCES users(id),
  title         TEXT NOT NULL,
  description   TEXT NOT NULL DEFAULT '',
  venue         TEXT NOT NULL,
  starts_at     TIMESTAMPTZ NOT NULL,
  price_cents   INT NOT NULL DEFAULT 0 CHECK (price_cents >= 0),
  currency      TEXT NOT NULL DEFAULT 'INR',
  capacity      INT NOT NULL CHECK (capacity > 0),
  tickets_sold  INT NOT NULL DEFAULT 0,
  image_url     TEXT NOT NULL DEFAULT '',
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  -- Defense in depth: even if application-level locking were bypassed,
  -- the database itself refuses to oversell.
  CONSTRAINT no_oversell CHECK (tickets_sold <= capacity)
);

CREATE TABLE IF NOT EXISTS tickets (
  id            BIGSERIAL PRIMARY KEY,
  code          TEXT NOT NULL UNIQUE,
  event_id      BIGINT NOT NULL REFERENCES events(id),
  user_id       BIGINT NOT NULL REFERENCES users(id),
  status        TEXT NOT NULL DEFAULT 'valid' CHECK (status IN ('valid', 'checked_in')),
  checked_in_at TIMESTAMPTZ,
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- One row per purchase attempt; the UNIQUE key makes retries idempotent.
CREATE TABLE IF NOT EXISTS purchases (
  id              BIGSERIAL PRIMARY KEY,
  idempotency_key TEXT NOT NULL UNIQUE,
  user_id         BIGINT NOT NULL REFERENCES users(id),
  ticket_id       BIGINT NOT NULL REFERENCES tickets(id),
  created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_tickets_user  ON tickets(user_id);
CREATE INDEX IF NOT EXISTS idx_tickets_event ON tickets(event_id);
