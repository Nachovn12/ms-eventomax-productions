CREATE TABLE productions (
    id BIGSERIAL PRIMARY KEY,
    organizer_id VARCHAR(255) NOT NULL,
    name VARCHAR(150) NOT NULL,
    scheduled_at TIMESTAMP NOT NULL,
    location VARCHAR(255) NOT NULL,
    status VARCHAR(30) NOT NULL DEFAULT 'SOLICITADO',
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);
