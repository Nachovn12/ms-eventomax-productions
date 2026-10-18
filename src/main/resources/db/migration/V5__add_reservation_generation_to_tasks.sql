ALTER TABLE inventory_reservation_tasks 
    ADD COLUMN IF NOT EXISTS reservation_generation BIGINT DEFAULT 1;

UPDATE inventory_reservation_tasks 
SET reservation_generation = 1 
WHERE reservation_generation IS NULL;

