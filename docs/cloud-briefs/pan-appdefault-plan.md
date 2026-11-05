Repo github.com/ozandurmus/neXus. Setup: git fetch origin cloud-base/1105 && git checkout -b cloud/pan-appdefault-plan-1105 origin/cloud-base/1105. Read AGENTS.md first (binding: masking law, English, UNKNOWN over guessing). No access to the live system. Small task: finish within about 15 minutes.

TASK (document only): about 2,447 Palo Alto rules are UNKNOWN because 'application default ports are unknown': rules with service application-default, see PolicyHygiene/PolicyNormalization in ui2/service.
Write docs/design/PAN_APPLICATION_DEFAULT_PLAN_2026_11_05.md:
- what application-default means per official Palo Alto documentation (cite URLs);
- whether the per-application default ports are available from data neXus already collects (check the Panorama/firewall collectors and snapshot model), or would need a new read-only command (name it, flagged as needing PO approval);
- a sound fail-closed way to use them only to prove disjointness;
- tests.
Commit (messages end with 'Co-Authored-By: Claude'), push cloud/pan-appdefault-plan-1105, no PR.