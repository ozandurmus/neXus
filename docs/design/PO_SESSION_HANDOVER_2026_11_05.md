# PO session handover - 2026-11-05 12:30 (PO assistant + orchestrator)

Status: handover record (non-authoritative; AGENTS.md, frozen contracts, `project/*.json` and `CURRENT_STATE.md` win).
The previous PO session (6e99b4d1) grew too long to run cheaply, so this file lets a fresh session take over the PO
assistant and orchestrator role without gaps. The conversation happens in Turkish; every repository artifact is in
English.

## 1. Role, authority, steering
- **Role:** this session is the PO assistant and orchestrator. It briefs lanes, reviews them, tests, ships and
  validates, and may convene `nexus-decision-council` itself (PO 2026-11-03).
- **Devil's advocate:** challenge PO directions with evidence before executing a risky or low-value direction. Do not
  just agree; the PO decides. Example: the analysis-container principle in
  `PO_DECISION_RECORD_2026_11_05_ANALYSIS_CONTAINER_DIRECTION.md`.
- **When to ask:** ask the PO only for irreversible deletes, production DB writes outside the delegation, new device
  commands (exact literal) and genuinely new policy. Never use the AskUserQuestion form, and do not end messages with
  approval prompts. Decide routine matters; the PO delegated model choice and design calls.
- **Cost warnings:** warn the PO first, with a rough cost and a cheaper option, before anything recurring or
  token-heavy. There is no heartbeat or cron status loop (one burned about 50% of the weekly quota on 2026-11-04).
  Report on events or when asked.
- **Delegations (2026-11-04, `PO_DECISION_RECORD_2026_11_04_DEV_MODE_DELEGATIONS.md`):**
  - fast merge: merge after validate, with full regression after the merge;
  - development-mode DB maintenance with an audit fingerprint (deleting backups or data still needs a yes);
  - freeze and backlog authority.
  Blanket read-only device command authority was NOT granted.
- **Model policy (PO 2026-11-05):**
  - Sonnet writes code (well-specified lanes).
  - Opus handles root cause, security, evidence/identity semantics and concurrency.
  - Fable handles design-quality judgement only, on reduced inputs.
  - Haiku handles mechanical edits.
  - Argon (agy) handles architecture and design analysis and reviews (`scripts/consult_argon.py`); it is not the main
    coder.
  - External reviews always go through `scripts/consult_*.py`, with unaltered output in docs/design/.

## 2. Security rules (unchanged, binding)
- **Masking:** masked pseudonyms only (FW-TANGO-04 style). No raw hostnames, IPs or serials in chat, commits or PRs.
  DB queries return counts and opaque ids only. UI inspection is done only as aiview.
- **Device commands:** every new device command needs the PO's exact-command approval. Approval of one command does not
  approve the next.
- **HOST-A:** ask before rm or irreversible actions; never use it as a jump server. Pod restarts are free during
  development.
- **Probes during deploys:** no aiview machine-session probes during a production deploy e2e. Use SSH DB queries
  instead.
- **Unchanged items:** MDS backup stays disabled. Do not touch the rwsafe chat, its pods or its local server (port 8765).
  Never read raw pcaps before anonymizing.
- **Approved device commands so far:**
  - A6/G6 rows 1-5 (TCP only; UDP needs `PATH_ANALYSIS_A6_UDP_PROPOSAL.md` approved first);
  - the CP NAT UID probe literal (2026-11-05, `PO_DECISION_RECORD_2026_11_05_CP_NAT_UID_PROBE.md` on branch
    `fix/cp-nat-uid-probe-1105`, not yet shipped).

## 3. Working method (hard-won)
- **Ship queue:** `~/Codo/ops-scripts/shipq2.sh <out> <wait-file|""> <branch> <title> <notes> <log> [--skip-checks
  reason]`.
  - Always run it with run_in_background. It pretests (`jt.sh`), rebases, ships (preview e2e, PR, checks, merge,
    deploy, prod e2e).
- **Traps:**
  1. Detach worktrees that hold the branch before shipping it.
  2. Never pre-push queued branches.
  3. Never edit shipq2 in place.
  4. Killing a chain leaves its orchestrate child running; kill it explicitly.
  5. Delete a remote branch only after the PR shows MERGED.
  6. Never edit or branch inside `~/Codo/nexus-ship`; use a fresh `git worktree add ... origin/main`.
  7. Never rename or delete a `chain-*.out` that another chain waits on (that stalled the queue for 7.5 h on 11-05).
- **Batch ships:**
  - Integrate branches on one branch, squash onto origin/main (merge commits make the ship rebase re-conflict), test
    once, ship once.
  - If the preview e2e fails at "get job", the e2e Job itself failed. Look for selector changes, as with the R-13
    Readiness summary button count.
- **Migrations:** every new `V*.sql` is dry-run in BEGIN/ROLLBACK on the live ui2 DB before ship.
- **Manifest env changes:** these are not applied by the deploy. Use `kubectl -n ui2 set env deployment/... ` after the
  ship.
- **Quarantine:** a QUARANTINED endpoint_admission left by a failed job can block a policy drain. Prove there is no
  device TCP (`/proc/net/tcp`), then release it with actor `system:release` and `QUARANTINE_RELEASED_TRANSPORT_CLOSED`.
  The fix (ended-job quarantines no longer count) shipped on 11-05, and a follow-up MEDIUM finding is open (§6).

## 4. Shipped 2026-11-04 / 2026-11-05 (main)
- **Path analysis:**
  - A0-A5b, G6 and A6 (validation against the vendors' matchers);
  - the A6 "Verify on devices" UI;
  - the pseudonym fixes: prefixed rendering, and summary-route false ambiguity;
  - the computed-path diagram;
  - VSX cluster-member VS addresses as `cluster_virtual`.
- **Policy:** CP group members resolved from the collected object inventory: unresolved CP objects went from 32,429 to
  670, and definite shadow results from 9,349 to 12,964.
- **Backups:** daily "Backup size deviation" alert covering own-history anomalies, fleet-large backups and firewall logs
  inside backups. 47 of 56 CP backups carried `fw1/log`; the PO fixed FW-TANGO-08 and keeps retention at 7 days.
- **UI night batch (PR 741):**
  - device Collect menu, Collect logs now, fleet log collection;
  - UI review fixes R-04/R-05/R-06/R-13;
  - never-finishing panels fixed at the root;
  - job rows name their target;
  - allow-listed server-side sorting;
  - client-side whole-list sorting (earlier);
  - system-log scheduled collection independent of the development-mode switch. It is enabled on ui2-service; the
    first run collected 97 devices.
- **Security:** H-1 fixed (the aiview job-log CSV leaked target labels after a restart).
- **Sign-in page:** Constellation design with the new hub logo (PR 743, 12:24). The PO chose it from 12 proposals in
  `docs/design/login-proposals/` (gallery index.html).
- **Delivery:** drain barrier ignores ended-job quarantines; CI green again (inventory registration).

## 5. Cloud branches to integrate (all pushed to origin, base `cloud-base/1105`; none merged)
Run all 24 cloud sessions to completion; they can be archived. Review, test and ship each branch, then delete it.

**Docs and designs (low risk; one docs batch):**
- `cloud/analysis-container-design-1105`
- `cloud/reports-r2-contract-1105`
- `cloud/udp-matcher-design-1105`
- `cloud/exec-home-design-spec-1105` (close its §0.1 note: the design-language amendment is in this handover branch)
- `cloud/path-phase-b-architecture-1105`
- `cloud/lb-architecture-1105` (F5 and Citrix, D-9)
- `cloud/pan-appdefault-plan-1105`
- `cloud/topology-conflicts-doc-1105`
- `cloud/release-notes-1105`
- `cloud/notify-raw-names-audit-1105` (may include a masked/raw notification setting; check)
- `cloud/r1-contract-amend-1105`
- `cloud/policy-unknown-analysis-1105` (plan plus optional sound code; review carefully)
- `cloud/natprobe-review-1105` (review of `cloud-src/natprobe-1105`; apply its fixes before shipping the NAT probe)

**Code (one integration batch, merged in this order, testing after each merge):**
1. `cloud/ia-restructure-1105`: C2 Policy with 4 tabs; C3 Trace and unified Changes; C4 Tools and Admin in 4 groups
   plus Product; B6; B7.
2. `cloud/screens-1105`: B2 Backups drawer and grouping; B3; B5 cluster Members & Identity; C5 devices bars and tree.
3. `cloud/exec-home-1105`: B1 freshness scale; B4 honest compliance headline with CIS and NIST first; C1 executive home
   with the HEALTHY / AT RISK / UNKNOWN verdict; C7 plain wording.
4. `cloud/reports-r1-1105`: Reports R1 per `REPORTS_R1_CONTRACT.md` plus the r1-contract-amend corrections (RBAC class
   and masking path). It adds a migration: dry-run it first.
5. Security MEDIUM fixes:
   - `cloud/secfix-drain-health-1105`;
   - `cloud/secfix-aiview-sort-1105`;
   - `cloud/secfix-cache-ui-1105`.
   These are based on `cloud/security-review-1105`, which is already shipped as H-1, so rebase them.
6. `cloud/path-unknown-1105`: engine improvements plus the plan.
7. `cloud/notif-router-skip-1105`.
8. `cloud/pathdiagram-unmask-1105`: the server is the masking authority.
9. `cloud/test-speed-1105`: Clock injection and parallelism; check for flakiness.
10. `cloud/e2e-night-batch-1105`: the full vitest run was never green there because of load timeouts; run it alone.

Leftover routine runs (plan-billed, 2026-11-05 07:10Z) may have pushed duplicates to the same branch names. Check each
branch log before merging.

## 6. Open items and follow-ups
- **PO decisions pending:** none blocking. Two things wait on the PO:
  - approving `PATH_ANALYSIS_A6_UDP_PROPOSAL.md` (new command literals);
  - an APIC read-only account (D-9 remainder).
- **Engineer decisions taken today (record them when shipping the docs):**
  - R2 D1 yes, D2 base URL `https://ui2.nexus.local`, D3 yes, D4 limits accepted;
  - Reports R1 set: RPT-01/02/03/05, OpenPDF, e-mail always masked, Reports top-level under Overview.
- **Next measurements:**
  - topology conflict count after the VSX fix (needs a CP inventory collection plus a rebuild);
  - path queries with masked addresses after the pseudonym fixes;
  - the first A6 device validation on a sample;
  - the NAT UID probe outcome after shipping it and running a CP policy collection.
- **Security MEDIUM findings still open until the secfix branches ship:**
  - drain barrier versus cancelled jobs that may have written;
  - System Health unreadable-file caching;
  - aiview sort/search on real names;
  - pseudonym wrong-host;
  - job-log query cost;
  - CP inventory cache thrash;
  - diagram neighbours from another snapshot;
  - the System Health "scheduled off" wording.
- **Privacy:** notification e-mails and syslog carry raw hostnames today; see the notify-raw-names-audit branch.
- **Biggest policy UNKNOWN:** "earlier rule has unsupported constraints" (CP about 35.7k, PAN about 22k). Start from
  the policy-unknown plan.
- **Analysis container:** measure ui2-service memory, then the design is ready, then an Opus review, then the PO
  go-ahead.

## 7. Ready cloud work (for the next credit or idle capacity)
When cloud credit is available, open all sessions in the first 10 minutes. Use about 10 code lanes in disjoint areas
plus 20-30 analysis, design, review and test lanes. Write each brief self-contained into `docs/cloud-briefs/` first,
so a session is a one-line paste. Routines (RemoteTrigger) are NOT covered by cloud credit.

Candidate briefs to write:
1. Reports R2a: schedules and delivery.
2. Reports R2b: the five new reports.
3. Analysis container implementation, phase 1.
4. LB L1: F5/Citrix inventory and backup (after command approval).
5. Path Phase B: UDP (after approval).
6. Path Phase B: chained NAT.
7. Return-path check.
8. Policy UNKNOWN extensions 1-3, from the plan.
9. PAN application-default (if the data exists).
10. CP NAT full read by UID (after approval).
11. Executive weekly report (C6) on Reports.
12. Notification identity masking setting.
13. Topology override UX.
14. e2e coverage for the new screens.
15. Accessibility audit.
16. Performance audit of heavy endpoints.
17. Packet analysis review.
18. Failover pilot readiness review.
19. Compliance control evidence gaps.
20. Lifecycle/EoL data sources.
21. Job log retention.
22. DB index review.
23. Frontend bundle size.
24. Docs: operator guide.
25. Security review of the integration batch.

## 8. Lessons (2026-11-04/05)
- Routines bill the plan, not the cloud credit. Check eligibility first. About half the credit went unused because the
  briefs were not ready in advance.
- Squash batches before shipping, and keep e2e-sensitive containers (aria-label groups) stable.
- A fresh session per major phase is far cheaper than one long session.
