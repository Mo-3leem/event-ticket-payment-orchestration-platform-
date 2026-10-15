-- V4: allow seats of EXPIRED bookings to be booked again, without deleting history.
--
-- The V3 global UNIQUE(event_seat_id) correctly prevents double-booking, but it also
-- permanently blocks any seat that ever belonged to an expired booking. This migration
-- replaces it with a partial unique index covering only live items (released = false):
-- a seat stays exclusive while its booking is active or confirmed, and becomes
-- reusable once its booking expires. Expired booking headers AND items are preserved.

ALTER TABLE booking_items ADD COLUMN released BOOLEAN NOT NULL DEFAULT false;

-- Backfill: items of already-EXPIRED bookings are historical, not live reservations.
UPDATE booking_items bi
SET released = true
FROM bookings b
WHERE bi.booking_id = b.id AND b.status = 'EXPIRED';

ALTER TABLE booking_items DROP CONSTRAINT uq_booking_items_seat;

CREATE UNIQUE INDEX uq_booking_items_active_seat
  ON booking_items (event_seat_id)
  WHERE released = false;
