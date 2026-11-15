# Integration suite as CI gate; close the V208 NULL hole

status: in_progress · target: Harness fixed on branch

2026-11-14: branch test/integration-green-1113: harness fixes take origin/main from 9 failures to 1 (247 tests). The remaining one is a real defect: V208 policy_change_evidence admin-name scope check accepts a name when command_class or result_class is NULL (against PO D2); the writer already refuses it, so only the DB second line is open. Needs a new migration, then the integration job as a required check.

2026-11-14: in ship batch 1 (V213).

2026-11-15 10:00 backlog refresh: Shipped on main (batch 2/3). Commits 'V213: refuse an administrator name when the command or result class is NULL' (V213__policy_change_evidence_admin_name_scope_not_null.sql) and 'Integration suite green on main and gated before the merge' (bf76d965: 248 tests, 0 failures; new integration job in validation.yml, not in POST_MERGE_CHECKS). Still to confirm outside the repo: the GitHub required-check setting and the V213 live pre-deploy count/dry run.
