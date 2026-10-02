-- Cooperative cancellation; the existing jobs audit trigger captures this flag.
ALTER TABLE jobs ADD COLUMN cancel_requested BOOLEAN NOT NULL DEFAULT false;
