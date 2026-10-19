CREATE TABLE production_services (
    id BIGSERIAL PRIMARY KEY,
    production_id BIGINT NOT NULL,
    service_id BIGINT NOT NULL,
    service_name VARCHAR(150) NOT NULL,
    unit_price NUMERIC(10, 2),
    contracted_at TIMESTAMP NOT NULL,
    reservation_generation BIGINT,
    CONSTRAINT fk_production_services_production FOREIGN KEY (production_id) REFERENCES productions(id) ON DELETE CASCADE,
    CONSTRAINT uq_production_service UNIQUE (production_id, service_id)
);

CREATE INDEX idx_prod_services_prod_id ON production_services(production_id);
CREATE INDEX idx_prod_services_service_id ON production_services(service_id);

ALTER TABLE inventory_reservation_tasks
    ADD COLUMN IF NOT EXISTS services_json TEXT;
