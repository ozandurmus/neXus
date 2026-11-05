You work in a fresh clone of github.com/ozandurmus/neXus (neXus: an on-prem firewall operations platform, Java 21/Spring/jOOQ/Gradle backend under ui2/, React/TS/MUI frontend in ui2/frontend).
Setup: git fetch origin cloud-base/1105 && git checkout -b cloud/exec-home-1105 origin/cloud-base/1105. Read AGENTS.md first: it is binding (masking law: fixtures and previews only use masked names like FW-TANGO-04 and 192.0.2.x addresses; never real hostnames or IPs; English repo artifacts; UNKNOWN over guessing).
You have no access to the live system, HOST-A, the VPN or devices; don't try. There are no device commands in this task.
Tests: backend `cd ui2 && ./gradlew :service:test :persistence:test :architecture-tests:test` (plus :worker:test if you touch the worker); frontend `cd ui2/frontend && npm ci && npx vitest run && npx tsc --noEmit`. All must pass; never weaken a test to pass.
Finish: commit on cloud/exec-home-1105 with English messages ending with "Co-Authored-By: Claude", then `git push origin cloud/exec-home-1105`. Do NOT open a PR and do NOT merge or push to main. End with a short report: what changed, the test counts, open issues.

TASK: UI review items B1, B4, C1 and C7 from docs/design/UI_REVIEW_2026_11_05_SYNTHESIS.md (read it and UI_REVIEW_2026_11_05_ARGON.md section 3 and FABLE.md). PO approved all; primary frameworks are CIS and NIST.
- B1: one product-wide freshness scale (a single threshold module used by every freshness pill), plus a stale-evidence banner on Overview and Compliance when under 80% of active devices were read in 24 h.
- B4: the compliance headline reads 'X% of evidenced controls pass, Y% have no evidence', instead of counting unevidenced as failed; CIS and NIST first.
- C1: replace Overview with the executive home:
  - a verdict word (HEALTHY / AT RISK / UNKNOWN) plus a freshness sentence;
  - 4 pillars: security posture, backup coverage, HA readiness, evidence freshness;
  - at most 5 'needs attention' lines with age, each deep-linking to the filtered problem;
  - framework bars and the 24 h change pulse below the fold.
  Use only data the existing APIs return. If a figure needs a new API field, add it server-side with tests and masking.
- C7: plain wording on executive screens ('data freshness', 'backup coverage', 'policies').
Update the preview and fixtures (masked only).