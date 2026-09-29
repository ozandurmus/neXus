SELECT set_config('app.actor_fingerprint', 'migration:V106', true);
SELECT set_config('app.action_id', 'diagnostic_gate_audit_schema', true);
ALTER TABLE jobs ADD COLUMN diagnostic_gate_id TEXT;
