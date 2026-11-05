You work in a fresh clone of github.com/ozandurmus/neXus (neXus: an on-prem firewall operations platform, Java 21/Spring/jOOQ/Gradle backend under ui2/, React/TS/MUI frontend in ui2/frontend).
Setup: git fetch origin cloud-base/1105 && git checkout -b cloud/screens-1105 origin/cloud-base/1105. Read AGENTS.md first: it is binding (masking law: fixtures and previews only use masked names like FW-TANGO-04 and 192.0.2.x addresses; never real hostnames or IPs; English repo artifacts; UNKNOWN over guessing).
You have no access to the live system, HOST-A, the VPN or devices; don't try. There are no device commands in this task.
Tests: backend `cd ui2 && ./gradlew :service:test :persistence:test :architecture-tests:test` (plus :worker:test if you touch the worker); frontend `cd ui2/frontend && npm ci && npx vitest run && npx tsc --noEmit`. All must pass; never weaken a test to pass.
Finish: commit on cloud/screens-1105 with English messages ending with "Co-Authored-By: Claude", then `git push origin cloud/screens-1105`. Do NOT open a PR and do NOT merge or push to main. End with a short report: what changed, the test counts, open issues.

TASK: UI review items B2, B3, B5 and C5 from docs/design/UI_REVIEW_2026_11_05_SYNTHESIS.md (read it and the ARGON/FABLE reviews). PO approved all.
- B2, Backups:
  - the backups table above the fold;
  - the 'major configuration changes' list moves into a drawer opened from the deviations card, and stays on Backups;
  - simultaneous identical events are grouped into one line ('N devices, same minute, <change>');
  - a stale badge on the scheduled fleet backup when it is overdue.
- B3: KPI cards and header buttons appear only on the tab they belong to (Operations job KPIs only on Jobs; Administration header buttons only on Device management).
- B5, cluster detail:
  - merge the 'Cluster members' and 'Identity' tabs into 'Members & Identity';
  - 'differ' shown amber;
  - Configuration opens with 'differences only' when differences exist;
  - a compact Logs header (one status line; the truncation list moves to Collection health).
- C5, Devices fleet summary:
  - ranked horizontal bars instead of the donuts, covering all vendors, with a verdict line ('N devices on more than one major version; oldest X');
  - device tree children collapsed by default with count badges;
  - a 'problems only' quick filter.
Masked fixtures only.