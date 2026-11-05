Repo github.com/ozandurmus/neXus. Setup: git fetch origin cloud/security-review-1105 && git checkout -b cloud/secfix-cache-ui-1105 origin/cloud/security-review-1105. Read AGENTS.md (binding) and docs/design/SECURITY_REVIEW_2026_11_05.md first. No access to the live system.

TASK: fix the remaining MEDIUM findings.
(1) CP inventory cache (CpInventoryObjects): a customer domain and Global must not evict each other repeatedly. Pin Global, or size the LRU so both fit, within the memory cap.
(2) Path diagram: draw neighbours only from the same topology snapshot the path was computed on (snapshot id match), otherwise hide them with a note.
(3) System Health UI: say 'scheduled computation is off' only when the API reports it; otherwise show the actual state or error.
Add tests. Run `cd ui2 && ./gradlew :worker:test :service:test :architecture-tests:test` and `cd ui2/frontend && npm ci && npx vitest run && npx tsc --noEmit`.
Commit (messages end with 'Co-Authored-By: Claude'), push cloud/secfix-cache-ui-1105, no PR.