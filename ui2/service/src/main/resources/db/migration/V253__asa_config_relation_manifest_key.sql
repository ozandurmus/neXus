-- V253 -- ASA saved-state relation: key it by the manifest artefact id and add the differing-keyword breakdown.
--
-- Defect found live 2026-11-19: V250 rows were keyed by the storage reference of the stored bundle (the value the
-- manifest keeps in backup_artefact.recovery_volume_path), while the policy installation view joins
-- backup_artefact.artefact_id, a different (random) id. None of the 12 rows joined, so every ASA read "not measured".
-- From this release the worker re-keys the row to the manifest's artefact id once the manifest row is written.
-- The existing rows are DELETED, not re-keyed: they were computed with the V250 positional comparison, which counts
-- every line after one inserted line as different (7 of 12 read MISMATCH with 2040-20775 differing lines). Re-keying
-- them would show those ASA units as UNSAVED on a broken count. They are derived data (verdict + counts, no backup);
-- the next ASA backup recomputes each one with the Myers diff.
--
-- differing_keywords: command keyword -> number of differing lines, top 10, counts only (never a line or a value).
-- Additive and nullable; V250 is not edited. Dry-run in BEGIN/ROLLBACK on the live database before rollout.
ALTER TABLE asa_config_relation ADD COLUMN differing_keywords JSONB;

DELETE FROM asa_config_relation;
