-- 002: ticket tiers, orders + simulated checkout, promo codes, waitlist,
-- categories, favorites, in-app notifications, event lifecycle.
-- Idempotent by construction — this file re-runs on every boot, like 001.

-- ===== Categories ============================================================
CREATE TABLE IF NOT EXISTS categories (
  id         BIGSERIAL PRIMARY KEY,
  name       TEXT NOT NULL UNIQUE,
  sort_order INT  NOT NULL DEFAULT 0
);

INSERT INTO categories (name, sort_order) VALUES
  ('Music', 1), ('Tech', 2), ('Sports', 3), ('Arts', 4), ('Food', 5), ('Other', 6)
ON CONFLICT (name) DO NOTHING;

-- ===== Events: lifecycle status + category ==================================
ALTER TABLE events ADD COLUMN IF NOT EXISTS status TEXT NOT NULL DEFAULT 'published';
ALTER TABLE events DROP CONSTRAINT IF EXISTS events_status_check;
ALTER TABLE events ADD CONSTRAINT events_status_check CHECK (status IN ('published', 'cancelled'));
ALTER TABLE events ADD COLUMN IF NOT EXISTS cancelled_at TIMESTAMPTZ;
ALTER TABLE events ADD COLUMN IF NOT EXISTS category_id BIGINT REFERENCES categories(id);

-- ===== Ticket tiers — the source of truth for inventory =====================
-- events.capacity / events.tickets_sold remain live aggregates, updated in the
-- same transaction as tier counters (see orders.js); events.price_cents is
-- frozen legacy data — reads derive price from MIN(tier price).
CREATE TABLE IF NOT EXISTS ticket_tiers (
  id          BIGSERIAL PRIMARY KEY,
  event_id    BIGINT NOT NULL REFERENCES events(id) ON DELETE CASCADE,
  name        TEXT NOT NULL,
  price_cents INT NOT NULL CHECK (price_cents >= 0),
  capacity    INT NOT NULL CHECK (capacity > 0),
  sold        INT NOT NULL DEFAULT 0,
  sort_order  INT NOT NULL DEFAULT 0,
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT tier_no_oversell CHECK (sold <= capacity),
  CONSTRAINT tier_name_per_event UNIQUE (event_id, name)
);
CREATE INDEX IF NOT EXISTS idx_tiers_event ON ticket_tiers(event_id);

-- Backfill: every pre-tier event gets one 'General' tier mirroring aggregates.
INSERT INTO ticket_tiers (event_id, name, price_cents, capacity, sold)
SELECT e.id, 'General', e.price_cents, e.capacity, e.tickets_sold
FROM events e
WHERE NOT EXISTS (SELECT 1 FROM ticket_tiers t WHERE t.event_id = e.id);

-- ===== Waitlist (before orders — orders references it) =======================
CREATE TABLE IF NOT EXISTS waitlist (
  id               BIGSERIAL PRIMARY KEY,
  event_id         BIGINT NOT NULL REFERENCES events(id),
  user_id          BIGINT NOT NULL REFERENCES users(id),
  tier_id          BIGINT REFERENCES ticket_tiers(id), -- pinned when offered
  status           TEXT NOT NULL DEFAULT 'waiting'
                   CHECK (status IN ('waiting', 'offered', 'claimed', 'expired', 'left')),
  offer_expires_at TIMESTAMPTZ,
  created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (event_id, user_id) -- rejoin = UPDATE back to 'waiting'
);
CREATE INDEX IF NOT EXISTS idx_waitlist_event ON waitlist(event_id, status, created_at);

-- ===== Promo codes ===========================================================
CREATE TABLE IF NOT EXISTS promo_codes (
  id         BIGSERIAL PRIMARY KEY,
  code       TEXT NOT NULL UNIQUE, -- stored UPPERCASE
  event_id   BIGINT REFERENCES events(id), -- NULL = global
  kind       TEXT NOT NULL CHECK (kind IN ('percent', 'fixed')),
  value      INT NOT NULL CHECK (value > 0), -- percent 1..100, or paise
  max_uses   INT, -- NULL = unlimited
  uses       INT NOT NULL DEFAULT 0,
  expires_at TIMESTAMPTZ,
  active     BOOLEAN NOT NULL DEFAULT true,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT promo_percent_range CHECK (kind <> 'percent' OR value <= 100),
  CONSTRAINT promo_not_overused  CHECK (max_uses IS NULL OR uses <= max_uses)
);

-- ===== Orders (simulated checkout) ==========================================
CREATE TABLE IF NOT EXISTS orders (
  id                BIGSERIAL PRIMARY KEY,
  user_id           BIGINT NOT NULL REFERENCES users(id),
  event_id          BIGINT NOT NULL REFERENCES events(id),
  status            TEXT NOT NULL DEFAULT 'pending'
                    CHECK (status IN ('pending', 'paid', 'failed', 'expired')),
  idempotency_key   TEXT NOT NULL UNIQUE,
  promo_code_id     BIGINT REFERENCES promo_codes(id),
  waitlist_offer_id BIGINT REFERENCES waitlist(id),
  subtotal_cents    INT NOT NULL CHECK (subtotal_cents >= 0),
  discount_cents    INT NOT NULL DEFAULT 0 CHECK (discount_cents >= 0),
  total_cents       INT NOT NULL CHECK (total_cents >= 0),
  currency          TEXT NOT NULL DEFAULT 'INR',
  expires_at        TIMESTAMPTZ NOT NULL,
  paid_at           TIMESTAMPTZ,
  created_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_orders_user ON orders(user_id, created_at DESC);

CREATE TABLE IF NOT EXISTS order_items (
  id               BIGSERIAL PRIMARY KEY,
  order_id         BIGINT NOT NULL REFERENCES orders(id) ON DELETE CASCADE,
  tier_id          BIGINT NOT NULL REFERENCES ticket_tiers(id),
  quantity         INT NOT NULL CHECK (quantity BETWEEN 1 AND 10),
  unit_price_cents INT NOT NULL,
  UNIQUE (order_id, tier_id)
);

-- One redemption per order (audit trail; promo_codes.uses is the counter).
CREATE TABLE IF NOT EXISTS promo_redemptions (
  id         BIGSERIAL PRIMARY KEY,
  promo_id   BIGINT NOT NULL REFERENCES promo_codes(id),
  order_id   BIGINT NOT NULL UNIQUE REFERENCES orders(id),
  user_id    BIGINT NOT NULL REFERENCES users(id),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ===== Tickets: tier + order links, wider status enum =======================
ALTER TABLE tickets ADD COLUMN IF NOT EXISTS tier_id BIGINT REFERENCES ticket_tiers(id);
UPDATE tickets t SET tier_id = tt.id
FROM ticket_tiers tt
WHERE t.tier_id IS NULL AND tt.event_id = t.event_id AND tt.name = 'General';
ALTER TABLE tickets ALTER COLUMN tier_id SET NOT NULL;
ALTER TABLE tickets ADD COLUMN IF NOT EXISTS order_id BIGINT REFERENCES orders(id);
ALTER TABLE tickets DROP CONSTRAINT IF EXISTS tickets_status_check;
ALTER TABLE tickets ADD CONSTRAINT tickets_status_check
  CHECK (status IN ('valid', 'checked_in', 'cancelled', 'void'));

-- ===== Favorites =============================================================
CREATE TABLE IF NOT EXISTS favorites (
  user_id    BIGINT NOT NULL REFERENCES users(id),
  event_id   BIGINT NOT NULL REFERENCES events(id),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (user_id, event_id)
);

-- ===== In-app notifications ==================================================
CREATE TABLE IF NOT EXISTS notifications (
  id         BIGSERIAL PRIMARY KEY,
  user_id    BIGINT NOT NULL REFERENCES users(id),
  type       TEXT NOT NULL, -- order_paid|event_updated|event_cancelled|waitlist_offer|ticket_void
  title      TEXT NOT NULL,
  body       TEXT NOT NULL DEFAULT '',
  event_id   BIGINT REFERENCES events(id),
  order_id   BIGINT REFERENCES orders(id),
  ticket_id  BIGINT REFERENCES tickets(id),
  is_read    BOOLEAN NOT NULL DEFAULT false,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_notifications_user ON notifications(user_id, is_read, created_at DESC);
