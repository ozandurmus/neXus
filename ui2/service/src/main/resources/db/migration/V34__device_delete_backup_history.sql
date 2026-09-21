-- Device deletion may retain backup artefacts, and the append-only retention
-- ledger keeps its opaque artefact identifier after an artefact is removed.
ALTER TABLE backup_artefact
    DROP CONSTRAINT backup_artefact_device_id_fkey;

ALTER TABLE artefact_retention_ledger
    DROP CONSTRAINT artefact_retention_ledger_artefact_id_fkey;

ALTER TABLE backup_artefact_retrieval
    DROP CONSTRAINT backup_artefact_retrieval_artefact_id_fkey;

ALTER TABLE backup_deviation_record
    DROP CONSTRAINT backup_deviation_record_device_id_fkey,
    DROP CONSTRAINT backup_deviation_record_artefact_id_fkey,
    DROP CONSTRAINT backup_deviation_record_previous_artefact_id_fkey;
