-- Phase 1 diagnostic history, stored through the existing encrypted artefact store.
SELECT set_config('app.actor_fingerprint', 'migration:V91', true);
SELECT set_config('app.action_id', 'diagnostic_history_schema', true);
ALTER TABLE jobs ADD COLUMN diagnostic_command TEXT;
ALTER TABLE jobs ADD COLUMN diagnostic_output_ref TEXT;
ALTER TABLE jobs ADD COLUMN diagnostic_output_key BYTEA;
ALTER TABLE jobs ADD COLUMN diagnostic_exit_status INTEGER;
ALTER TABLE jobs ADD COLUMN diagnostic_viewed_at TIMESTAMPTZ;
ALTER TABLE jobs ADD COLUMN diagnostic_viewed_by TEXT;
ALTER TABLE jobs ADD CONSTRAINT chk_diagnostic_command_length CHECK (length(diagnostic_command) <= 512);
INSERT INTO audit_redaction_policy(table_name,column_name,tier,reason) VALUES
 ('jobs','diagnostic_output_ref',2,'server-only diagnostic artefact location'),
 ('jobs','diagnostic_output_key',1,'wrapped diagnostic output key'),
 ('jobs','diagnostic_command',2,'operator command may include operational identifiers');
CREATE INDEX idx_jobs_diagnostic_history ON jobs(submitted_at DESC,job_id)
 WHERE capability_id IN ('diagnostic_read','fmg_interface_detail');
UPDATE gate_registry SET source_document_pointer = source_document_pointer || '; DEBUG_OPERATIONS_SUCCESSOR_DECISION_2026_09_26.md: diagnostic output/history',
 max_frequency = 'one diagnostic command per endpoint per minute; original collection frequency unchanged'
 WHERE gate_id IN ('fmg_ssh_get_system_interface','fmg_ssh_diagnose_hardware_info_nic',
 'fmg_ssh_diagnose_system_print_interface','fmg_ssh_fmnetwork_interface_detail',
 'fgt_get_system_status','asa_show_version','asa_show_mode','asa_show_ip_address',
 'asa_show_interface_ip_brief','asa_show_route');
