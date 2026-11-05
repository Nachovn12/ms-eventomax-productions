-- V4__add_crew_assignment_to_productions.sql
-- Agrega soporte para asignación de cuadrilla técnica a la producción

ALTER TABLE productions ADD COLUMN IF NOT EXISTS crew_id VARCHAR(100);
ALTER TABLE productions ADD COLUMN IF NOT EXISTS crew_name VARCHAR(150);

