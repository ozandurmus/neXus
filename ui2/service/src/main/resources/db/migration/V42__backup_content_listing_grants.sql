-- V42 -- grants V41 forgot. Measured live 2026-09-22 minutes after V41 rolled
-- out: every GET /backups/{id}/entries answered 500 "permission denied for
-- table backup_artefact_content_listing" -- the tables existed, the app role
-- could not read them. V41 is applied and append-only, so the grants come here.
-- The worker replaces a listing (DELETE + INSERT) and upserts the state row
-- (INSERT ... ON CONFLICT DO UPDATE); the service reads and, for "List now",
-- writes the same way.
GRANT SELECT, INSERT, UPDATE, DELETE ON backup_artefact_content_listing TO ui2_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON backup_artefact_entry TO ui2_app;
