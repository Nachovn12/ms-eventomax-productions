-- Add a unique tie-breaker without changing V1/V2 or deleting existing events.
ALTER TABLE outbox_events ADD COLUMN sequence_no BIGINT GENERATED ALWAYS AS IDENTITY;
CREATE UNIQUE INDEX idx_outbox_sequence ON outbox_events(sequence_no);
