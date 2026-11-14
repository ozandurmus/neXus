# Integration suite as CI gate; close the V208 NULL hole

status: in_progress · target: Harness fixed on branch

2026-11-14: branch test/integration-green-1113: harness fixes take origin/main from 9 failures to 1 (247 tests). The remaining one is a real defect: V208 policy_change_evidence admin-name scope check accepts a name when command_class or result_class is NULL (against PO D2); the writer already refuses it, so only the DB second line is open. Needs a new migration, then the integration job as a required check.
