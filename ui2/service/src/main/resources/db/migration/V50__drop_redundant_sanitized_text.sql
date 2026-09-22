-- V50 -- release the sanitized configuration text nothing reads (2026-09-22).
-- Measured live: 78 effective_running runs held 641 MB of text (up to 23 MB
-- each) that no reader serves -- the screen and compliance read the active
-- read's text -- and older runs of every kind kept copies no reader serves
-- either. Loading them drove the service out of heap. Every artefact stays in
-- the encrypted artefact store; only the redundant in-row text is released.
-- The repository now keeps text on the newest run of each read kind only.
UPDATE device_configuration_run SET sanitized_text = NULL
 WHERE read_kind = 'effective_running' AND sanitized_text IS NOT NULL;

UPDATE device_configuration_run r SET sanitized_text = NULL
 WHERE r.sanitized_text IS NOT NULL
   AND EXISTS (SELECT 1 FROM device_configuration_run n
                WHERE n.device_id = r.device_id AND n.read_kind = r.read_kind
                  AND n.collected_at > r.collected_at);
