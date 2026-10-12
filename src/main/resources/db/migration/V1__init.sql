CREATE EXTENSION IF NOT EXISTS "pgcrypto";

-- Users: minimal identity for Day 1. Passwords are BCrypt hashes, never plain text.
CREATE TABLE users (
  id UUID PRIMARY KEY,
  email VARCHAR(255) NOT NULL UNIQUE,
  password_hash TEXT NOT NULL,
  role VARCHAR(20) NOT NULL CHECK (role IN ('USER', 'ADMIN')),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_users_email ON users (email);

-- Venues: physical locations.
CREATE TABLE venues (
  id UUID PRIMARY KEY,
  name TEXT NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Seats: physical seats. UNIQUE(venue_id, seat_code) models the hall layout,
-- NOT sellable inventory. The same seat_code exists once per venue.
CREATE TABLE seats (
  id UUID PRIMARY KEY,
  venue_id UUID NOT NULL REFERENCES venues (id),
  seat_code VARCHAR(20) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT uq_seats_venue_code UNIQUE (venue_id, seat_code)
);
CREATE INDEX idx_seats_venue ON seats (venue_id);

-- Events: business occasions at a venue. Money uses minor units + currency.
CREATE TABLE events (
  id UUID PRIMARY KEY,
  venue_id UUID NOT NULL REFERENCES venues (id),
  title TEXT NOT NULL,
  starts_at TIMESTAMPTZ NOT NULL,
  price_minor BIGINT NOT NULL CHECK (price_minor >= 0),
  currency VARCHAR(3) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_events_venue ON events (venue_id);

-- Event seats: sellable inventory. One row per (event x physical seat).
-- This is the concurrency guard: holds lock THIS row with SELECT ... FOR UPDATE.
-- UNIQUE(event_id, seat_id) lets the same physical seat sell independently
-- for different events.
CREATE TABLE event_seats (
  id UUID PRIMARY KEY,
  event_id UUID NOT NULL REFERENCES events (id),
  seat_id UUID NOT NULL REFERENCES seats (id),
  status VARCHAR(20) NOT NULL DEFAULT 'AVAILABLE'
    CHECK (status IN ('AVAILABLE', 'HELD', 'SOLD')),
  held_by_user_id UUID NULL REFERENCES users (id),
  hold_expires_at TIMESTAMPTZ NULL,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT uq_event_seats_event_seat UNIQUE (event_id, seat_id),
  CONSTRAINT chk_hold_fields CHECK (
    (status = 'HELD' AND held_by_user_id IS NOT NULL AND hold_expires_at IS NOT NULL)
    OR (status IN ('AVAILABLE', 'SOLD'))
  )
);
CREATE INDEX idx_event_seats_event ON event_seats (event_id);
CREATE INDEX idx_event_seats_event_status ON event_seats (event_id, status);
CREATE INDEX idx_event_seats_expiry ON event_seats (status, hold_expires_at)
  WHERE status = 'HELD';
