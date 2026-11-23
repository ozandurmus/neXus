# Old unmerged branch review — 2026-11-23

**Status: REVIEW RECORD — Product Owner decides deletion.** Nothing was deleted or pushed. 42 unmerged
`docs/`, `feature/`, `fix/` and `ui/` branches were compared with `origin/main` (commit log, diff stat, grep of
main for the behaviour). Evidence also drawn from `docs/operations/OPEN_PR_TRIAGE_2026_09_23.md`.

Classes: **ON_MAIN** main already has the behaviour or document (often re-implemented, branch is stale);
**OBSOLETE** superseded, retired line (legacy Python product, Workbench tooling) or no longer meaningful;
**WANTED** still missing on main and meaningful — tracked in `project/backlog.json`.

Result: 23 ON_MAIN, 17 OBSOLETE, 2 WANTED (one new backlog item, one already tracked).

| Branch | Class | Reason |
|---|---|---|
| docs/ha1-agent-host-access | OBSOLETE | Phase A ledger lines for the pre-reinstall HOST-A; main's ledger is far longer after the 2026-09-25 reinstall. |
| docs/pan-change-actor-design-1111 | ON_MAIN | Design and PO decision record exist on main (main versions are the later ones). |
| docs/path-analysis-a0-vendor-semantics-1103 | ON_MAIN | Path analysis design and vendor-semantics documents exist on main, extended by later amendments. |
| feature/a-failed-job-says-why | ON_MAIN | Main persists a terminal reason on the fenced transition and matched-expectation per step attempt. |
| feature/approved-diagnostic-command-contract | OBSOLETE | DRAFT FortiManager diagnostic proposal; main carries the FROZEN debug-screen contract and its successor decision. |
| feature/audit-screen | ON_MAIN | Audit log panels and controller exist on main in a later form. |
| feature/auth-placement-options | OBSOLETE | Options analysis for an authentication decision since taken; never authority (PR #358 triage: close). |
| feature/backup-artefact-opaque-id | ON_MAIN | Main records a random UUID as artefact id, decoupled from the storage path. |
| feature/backup-claim-authorization | WANTED | Main re-checks only device enrollment at claim time; no persisted admission authorization, role or allowlist re-check (audit BK-12/BW-4 still NOT_IMPLEMENTED). Item `backup_claim_time_authorization_recheck`. |
| feature/backup-opaque-id-retrieval-locator | ON_MAIN | Superseded by artefact store v2; main keeps `recovery_volume_path` server-side only. |
| feature/discovery-failure-visible | ON_MAIN | Add device dialog maps the safe failure class (`DISCOVERY_FAILURE_COPY`). |
| feature/discovery-ssh-trust-contract | OBSOLETE | Marked SUPERSEDED on main by the FROZEN C10 trust-enrollment contract. |
| feature/gov-po-3-ac5-demo-1 | OBSOLETE | Disposable orchestrator pipeline demo file. |
| feature/gov-po-3-ci-privacy-gate-baseline-scoping | ON_MAIN | CI privacy-baseline fetch and its test are on main (`validation.yml`). |
| feature/jobs-screen-v1 | ON_MAIN | Operations › Jobs tab with filters and detail exists on main. |
| feature/ldap-config-ui | OBSOLETE | Legacy Python console; Administration › LDAP settings exists in the Java UI. |
| feature/op0b-s7-s6-test-order-isolation | OBSOLETE | Test-order fix for retired Python OP.0a tests that no longer exist on main. |
| feature/pan-auth-transport-convergence-audit | ON_MAIN | Audit document identical on main; backlog item already in the terminal archive. |
| feature/pan-ha-serial-identity-hardening-decision | ON_MAIN | Decision document identical on main; backlog item already in the terminal archive. |
| feature/production-replay-role-design | OBSOLETE | Design draft superseded by the aiview persona and masked projection. |
| feature/rb5-recovery-readiness-projection-and-ui-module | OBSOLETE | Self-described "not for merge" Python HTML checkpoint, parked under UI 2.0 D-5. |
| feature/replay-viewer-v1 | OBSOLETE | Exclusive replay sessions replaced by aiview and machine sessions (PO decision 2026-10-04). |
| feature/role-display | ON_MAIN | Main has `roleLabel.ts` with readable role labels in the top bar. |
| feature/select-all | ON_MAIN | Add device dialog has Select all for discovery candidates; branch targeted the retired Python UI. |
| feature/session-conflict-takeover | ON_MAIN | Login screen on main handles the session conflict token and takeover. |
| feature/ssh-tofu-and-ldap-config | OBSOLETE | 48 commits, 231 files over a stale base; TOFU host keys and LDAP settings exist on main in later form. |
| feature/ui2-0-architecture-design-fable-high | OBSOLETE | Main's copy is SUPERSEDED by the FROZEN architecture contract; branch is an earlier draft. |
| feature/ui2-b0-extraction-tooling | ON_MAIN | `scripts/ui2_extract_fixtures.py` is on main. |
| feature/ui2-b1-1m-topology | ON_MAIN | V1 scope and service topology brief identical on main. |
| feature/ui2-db-networkpolicy | ON_MAIN | `deploy/ui2/46-database-networkpolicy.yaml` is on main. |
| feature/ui2-db-roles-ownership | WANTED | No database ownership-boundary test or role split on main; already tracked by existing item `ui2_database_roles_and_data_ownership` (note added, no duplicate item). Re-implement, do not merge. |
| feature/ui2-mockup-palace-design | OBSOLETE | Design draft superseded by the aiview persona (PR #391 triage). |
| feature/wordmark-one-geometry | ON_MAIN | Main's wordmark already draws path geometry, independent of fonts. |
| feature/workbench-companion-current-coverage | OBSOLETE | Workbench Companion tooling script no longer on main; not product. |
| feature/workbench-companion-launchagent | OBSOLETE | Workbench tooling, not product. |
| feature/workbench-reliable-board | OBSOLETE | Orchestrator dashboard script no longer on main; not product. |
| feature/workbench-selected-queue-contract-0271 | OBSOLETE | Workbench draft contract, not product (PR #419 triage: close). |
| fix/policy-cp-empty-sections-1104 | ON_MAIN | Empty Check Point sections skipped in `PolicyNormalization`. |
| fix/policy-credibility-quick-1103 | ON_MAIN | Hit level aggregation and PAN hit levels exist on main. |
| fix/policy-pan-negate-no-1104 | ON_MAIN | PAN `target/negate = no` handled as untargeted. |
| fix/policy-shadow-budget-1103 | ON_MAIN | `RULE_WORK`/`TOTAL_WORK` per-rule shadow budget in `PolicyHygiene`. |
| ui/policy-credibility-1103 | ON_MAIN | Snapshot freshness label and hygiene coverage on the policy screens. |

## Notes

- ON_MAIN verdicts for code rest on a grep of main for the behaviour, not a line-by-line diff; the branches predate
  the repository restructure, so a plain patch no longer applies.
- Deleting every ON_MAIN and OBSOLETE branch loses nothing the product still wants. The two WANTED branches should be
  kept until their items are re-implemented, or deleted once the PO accepts the backlog items as the record.
