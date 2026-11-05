Repo github.com/ozandurmus/neXus, branch: git fetch origin cloud-base/1105 && git checkout -b cloud/security-review-1105 origin/cloud-base/1105. Read AGENTS.md first (binding). No access to the live system.

TASK: a security and correctness review of `git diff ed068bbb..origin/cloud-base/1105`. In scope:
- the night batch: Collect menu, system-log collect start, server-side sort allow-list, job target labels, stuck-loader fixes, path diagram, independent system-log scheduler;
- recent main changes to PathPseudonymMap, CheckPointPolicyMapper/CpInventoryObjects, V189 backup alerts and the module_deploy drain barrier.
Look for SQL injection, RBAC gaps, aiview masking leaks, raw-evidence retention, races, fail-open logic, resource leaks and test gaps.
Write docs/design/SECURITY_REVIEW_2026_11_05.md, with findings ranked by severity, each with file:line, scenario and fix. Fix the clear CRITICAL and HIGH findings, with tests. Run `cd ui2 && ./gradlew :service:test :persistence:test :worker:test :architecture-tests:test` (and the frontend vitest/tsc if touched).
Commit (messages end with 'Co-Authored-By: Claude'), git push origin cloud/security-review-1105, no PR.