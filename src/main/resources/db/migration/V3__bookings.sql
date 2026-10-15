-- Bookings: a booking groups event seats held by one user for one event.
-- Status lifecycle: PENDING_PAYMENT -> CONFIRMED | EXPIRED.
-- CANCELLED is reserved for future user-cancel/refund flows; no Day 2 writer sets it.
-- Money follows the events convention: BIGINT minor units + VARCHAR currency.

CREATE TABLE bookings (
  id UUID PRIMARY KEY,
  user_id UUID NOT NULL REFERENCES users (id),
  event_id UUID NOT NULL REFERENCES events (id),
  status VARCHAR(20) NOT NULL DEFAULT 'PENDING_PAYMENT'
    CHECK (status IN ('PENDING_PAYMENT', 'CONFIRMED', 'EXPIRED', 'CANCELLED')),
  total_minor BIGINT NOT NULL CHECK (total_minor >= 0),
  currency VARCHAR(3) NOT NULL,
  reference VARCHAR(20) NOT NULL UNIQUE,
  expires_at TIMESTAMPTZ NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_bookings_user ON bookings (user_id);
CREATE INDEX idx_bookings_expiry ON bookings (status, expires_at)
  WHERE status = 'PENDING_PAYMENT';

-- Booking items: each event seat belongs to at most one booking, ever.
-- The UNIQUE(event_seat_id) constraint is the last line of defense against
-- concurrent double-booking: the loser's INSERT fails atomically.
CREATE TABLE booking_items (
  id UUID PRIMARY KEY,
  booking_id UUID NOT NULL REFERENCES bookings (id) ON DELETE CASCADE,
  event_seat_id UUID NOT NULL REFERENCES event_seats (id),
  CONSTRAINT uq_booking_items_seat UNIQUE (event_seat_id)
);
CREATE INDEX idx_booking_items_booking ON booking_items (booking_id);
