CREATE TABLE inventory_reservation_tasks (
    id BIGSERIAL PRIMARY KEY,
    production_id BIGINT NOT NULL,
    status VARCHAR(30) NOT NULL,
    items_json TEXT NOT NULL,
    attempt_count INT NOT NULL DEFAULT 0,
    last_error TEXT,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT uq_reservation_task_production UNIQUE (production_id)
);

CREATE INDEX idx_res_task_status_updated ON inventory_reservation_tasks(status, updated_at);
