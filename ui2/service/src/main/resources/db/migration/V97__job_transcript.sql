SELECT set_config('app.actor_fingerprint', 'migration:V97', true);
SELECT set_config('app.action_id', 'job_transcript_schema', true);
ALTER TABLE jobs ADD COLUMN transcript_artefact_ref TEXT;
ALTER TABLE jobs ADD COLUMN transcript_artefact_key BYTEA;
INSERT INTO audit_redaction_policy(table_name,column_name,tier,reason) VALUES
 ('jobs','transcript_artefact_ref',2,'server-only raw backup transcript location'),
 ('jobs','transcript_artefact_key',1,'wrapped transcript data key');
