You work in a fresh clone of github.com/ozandurmus/neXus (neXus: an on-prem firewall operations platform, Java 21/Spring/jOOQ/Gradle backend under ui2/, React/TS/MUI frontend in ui2/frontend).
Setup: git fetch origin cloud-base/1105 && git checkout -b cloud/reports-r1-1105 origin/cloud-base/1105. Read AGENTS.md first: it is binding (masking law: fixtures and previews only use masked names like FW-TANGO-04 and 192.0.2.x addresses; never real hostnames or IPs; English repo artifacts; UNKNOWN over guessing).
You have no access to the live system, HOST-A, the VPN or devices; don't try. There are no device commands in this task.
Tests: backend `cd ui2 && ./gradlew :service:test :persistence:test :architecture-tests:test` (plus :worker:test if you touch the worker); frontend `cd ui2/frontend && npm ci && npx vitest run && npx tsc --noEmit`. All must pass; never weaken a test to pass.
Finish: commit on cloud/reports-r1-1105 with English messages ending with "Co-Authored-By: Claude", then `git push origin cloud/reports-r1-1105`. Do NOT open a PR and do NOT merge or push to main. End with a short report: what changed, the test counts, open issues.

TASK: Implement Reports R1 exactly per docs/design/REPORTS_R1_CONTRACT.md (status FROZEN; it wins over docs/design/REPORTS_DESIGN_2026_11_05_ARGON.md, whose section 4.7 SQL is NOT authority). Deliver:
- the four reports RPT-01/02/03/05;
- the report_run table migration (next free V number);
- ArtefactClass report with encrypted storage, 30-day retention and a 25 MiB cap;
- the ActionRegistry actions report_read, report_generate and report_raw_view, with an audit row on every generate and download;
- one masking pass on the structured document before any renderer;
- OpenPDF, POI XLSX and formula-safe CSV renderers;
- the bounded executor;
- the top-level Reports rail entry under Overview, with Catalogue, Run & preview and History tabs.
Write every test the contract lists, including byte-level masking tests on PDF/XLSX/CSV. Study existing patterns first: notification routes, BackupSizeAnomaly, ActionRegistry, ReportProjectionMasker, FileArtefactStore and the packet-analysis ExpertReport (POI).