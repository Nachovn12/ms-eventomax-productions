ALTER TABLE inventory_reservation_tasks 
    ADD COLUMN IF NOT EXISTS reservation_generation BIGINT;

UPDATE inventory_reservation_tasks 
SET reservation_generation = GREATEST(COALESCE(attempt_count, 1), 1);

ALTER TABLE inventory_reservation_tasks 
    ALTER COLUMN reservation_generation SET DEFAULT 1;
