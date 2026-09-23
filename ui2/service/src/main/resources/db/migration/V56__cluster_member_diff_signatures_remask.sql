-- V56 -- clear the difference signatures written under V55 (2026-09-23). The first measurement showed that in the
-- Users section a setting's last component is the principal name, so V55's rule kept some account names in the
-- signature. The rule now masks principal sections completely and treats any token carrying a digit or one of
-- - _ : . / as an identifier. Clearing makes ClusterDiffTask recompute every row once under the new rule.
SELECT set_config('app.actor_fingerprint', 'migration:V56_cluster_member_diff_signatures_remask', true);
SELECT set_config('app.action_id', 'cluster_diff_signature_remask_by_migration', true);

UPDATE cluster_member_diff SET diff_signatures = '{}'::jsonb WHERE diff_signatures <> '{}'::jsonb;
