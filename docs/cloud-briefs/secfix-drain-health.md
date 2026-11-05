Repo github.com/ozandurmus/neXus. Setup: git fetch origin cloud/security-review-1105 && git checkout -b cloud/secfix-drain-health-1105 origin/cloud/security-review-1105. Read AGENTS.md (binding) and docs/design/SECURITY_REVIEW_2026_11_05.md first. No access to the live system.

TASK: fix the two MEDIUM findings the review ranks first.
(1) Drain barrier (tools/delivery/module_deploy.py barrier_conditions): a QUARANTINED endpoint_admission whose job is CANCELLED must still hold the drain if that job may have started a device write. Decide fail-closed: count CANCELLED quarantines unless provably no write began. Use the evidence the schema has; if it has none, keep CANCELLED counted and release only COMPLETED/FAILED.
(2) System Health: an unreadable file must not be cached forever, and an unreadable restart log must never count as zero restarts. Make it UNKNOWN with bounded retry.
Add tests for both. Run `python3 -m pytest -q tools/tests/test_policy_module_runtime.py tools/tests/test_module_ownership_safety.py` and `cd ui2 && ./gradlew :service:test :architecture-tests:test`.
Commit (messages end with 'Co-Authored-By: Claude'), push cloud/secfix-drain-health-1105, no PR.