-- Demo seed: 1 venue, 10 physical seats (A1..A10), 2 events sharing the hall.
-- Event seats are generated as event x seat (20 rows), proving the same
-- physical seat is independently sellable per event.

INSERT INTO venues (id, name)
VALUES ('00000000-0000-0000-0000-000000000001', 'Grand Hall')
ON CONFLICT (id) DO NOTHING;

INSERT INTO events (id, venue_id, title, starts_at, price_minor, currency)
VALUES
  ('00000000-0000-0000-0000-000000000002', '00000000-0000-0000-0000-000000000001',
   'Spring Concert', now() + INTERVAL '7 days', 2500, 'EGP'),
  ('00000000-0000-0000-0000-000000000003', '00000000-0000-0000-0000-000000000001',
   'Summer Gala', now() + INTERVAL '14 days', 3500, 'EGP')
ON CONFLICT (id) DO NOTHING;

INSERT INTO seats (id, venue_id, seat_code)
SELECT gen_random_uuid(), '00000000-0000-0000-0000-000000000001', 'A' || g
FROM generate_series(1, 10) g
WHERE NOT EXISTS (
  SELECT 1 FROM seats WHERE venue_id = '00000000-0000-0000-0000-000000000001'
);

INSERT INTO event_seats (id, event_id, seat_id, status)
SELECT gen_random_uuid(), e.id, s.id, 'AVAILABLE'
FROM events e
JOIN seats s ON s.venue_id = e.venue_id
WHERE NOT EXISTS (
  SELECT 1 FROM event_seats es WHERE es.event_id = e.id AND es.seat_id = s.id
);
