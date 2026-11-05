Repo github.com/ozandurmus/neXus. Setup: git fetch origin cloud/security-review-1105 && git checkout -b cloud/secfix-aiview-sort-1105 origin/cloud/security-review-1105. Read AGENTS.md (binding) and docs/design/SECURITY_REVIEW_2026_11_05.md first. No access to the live system.

TASK: fix the MEDIUM findings on the aiview job log and path pseudonyms.
(1) For replay_viewer (aiview), sorting by Target and free-text search must not work on real hostnames: sort and search on the masked label, or disable those for aiview with a clear message.
(2) PathPseudonymMap: a masked address must be refused as AMBIGUOUS rather than resolve to the wrong host, in the case the review describes. Add a reproducing test first.
(3) Job log query cost: avoid per-row target lookups on sort and export (batch them).
Run `cd ui2 && ./gradlew :service:test :persistence:test :architecture-tests:test` and the frontend `npm ci && npx vitest run && npx tsc --noEmit` if it is touched.
Commit (messages end with 'Co-Authored-By: Claude'), push cloud/secfix-aiview-sort-1105, no PR.