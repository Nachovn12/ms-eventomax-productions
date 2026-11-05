-- V7__create_organizer_contacts_table.sql
-- Gestión segura y persistente de contactos autorizados por organizador

CREATE TABLE organizer_contacts (
    id BIGSERIAL PRIMARY KEY,
    organizer_id VARCHAR(255) NOT NULL UNIQUE,
    email VARCHAR(255) NOT NULL,
    contact_name VARCHAR(150),
    phone VARCHAR(50),
    user_oid VARCHAR(255),
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    created_by VARCHAR(255) NOT NULL,
    updated_by VARCHAR(255) NOT NULL
);

CREATE INDEX idx_organizer_contacts_organizer_id ON organizer_contacts(organizer_id);
CREATE INDEX idx_organizer_contacts_email ON organizer_contacts(email);
CREATE INDEX idx_organizer_contacts_user_oid ON organizer_contacts(user_oid);

