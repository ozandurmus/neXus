You work in a fresh clone of github.com/ozandurmus/neXus (neXus: an on-prem firewall operations platform, Java 21/Spring/jOOQ/Gradle backend under ui2/, React/TS/MUI frontend in ui2/frontend).
Setup: git fetch origin cloud-base/1105 && git checkout -b cloud/ia-restructure-1105 origin/cloud-base/1105. Read AGENTS.md first: it is binding (masking law: fixtures and previews only use masked names like FW-TANGO-04 and 192.0.2.x addresses; never real hostnames or IPs; English repo artifacts; UNKNOWN over guessing).
You have no access to the live system, HOST-A, the VPN or devices; don't try. There are no device commands in this task.
Tests: backend `cd ui2 && ./gradlew :service:test :persistence:test :architecture-tests:test` (plus :worker:test if you touch the worker); frontend `cd ui2/frontend && npm ci && npx vitest run && npx tsc --noEmit`. All must pass; never weaken a test to pass.
Finish: commit on cloud/ia-restructure-1105 with English messages ending with "Co-Authored-By: Claude", then `git push origin cloud/ia-restructure-1105`. Do NOT open a PR and do NOT merge or push to main. End with a short report: what changed, the test counts, open issues.

TASK: UI review items C2, C3, C4, B6 and B7 from docs/design/UI_REVIEW_2026_11_05_SYNTHESIS.md (read it and the ARGON/FABLE reviews). PO approved all.
- C2: Policy from 7 tabs to 4:
  - 'Rules & Containers', with installation shown as a column and status;
  - 'Objects', with cross-container search;
  - 'Hygiene', with a fleet summary when no container is selected;
  - 'Changes';
  - keep a 'search in this policy' deep link.
- C3: a rail section 'Trace' with:
  - one 'Trace' screen with two modes: topology path (the existing Path analysis screen) and single-policy rule lookup (the existing Policy > Lookup), advanced fields collapsed;
  - one unified 'Changes' entry merging Operations > Change feed and Policy > Changes.
- C4:
  - move Diagnostics and Offline analysis under Tools;
  - Administration from 14 pages to 4 groups: Device Registry, Access & Identity, Platform, Audit;
  - plus a 'Product' heading keeping Delivery plan, visible to PO/engineer roles;
  - restricted pages render as locked (a 'restricted' state panel), never hidden from aiview.
- B6: Job Logs becomes a redirect to Operations > Jobs; Failed defaults to 7 days with the top 5 failure reasons.
- B7: the aiview role label is correct (not 'Compliance Admin'); the build stamp and time zone move to the user menu or footer.
Keep old URLs working through redirects. Update the e2e selectors and route tests you break (grep tests for paths).