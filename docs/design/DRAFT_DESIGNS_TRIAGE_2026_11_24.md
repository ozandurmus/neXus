# Draft designs triage, 2026-11-24

Status: RECORD. Decides nothing; no contract, command or migration is approved here.

Scope: every `docs/design/` document whose status line carries DRAFT / DO NOT FREEZE / DO NOT IMPLEMENT, excluding review, brief, measurement and decision-record files, the log engine design and the log anomaly agent design (handled elsewhere). Method: one reader per ~11 documents, each checking source, `project/QUEUE.md` and git history on this branch. Classifications are engineering judgement from that evidence; items marked uncertain in a section were not fully verified. Order: DONE-STALE, NOT-DRAFT (grep matched incidentally), SUPERSEDED, then S, M, L within the rest.

| Doc | Class | Size | Open PO decisions | Next step |
|---|---|---|---|---|
| BACKUP_MAJOR_DEVIATIONS_2026_10_23.md | DONE-STALE | S | 1 | PO reviews the definition; then change the status line to ratified or record the amendments. |
| CP_FAILOVER_ONE_SHELL_SESSION_2026_10_22.md | DONE-STALE | S | 2 | PO (or an authorised session) reads the pilot cluster audit-log count for one readiness pass, writes it into section 6 step 4, and sets the ... |
| EXEC_HOME_AND_TRACE_DESIGN_SPEC_2026_11_05.md | DONE-STALE | S | 1 | Status-line fix; optionally verify the screen against the spec. |
| HOST_A_HARDENING_DRAFT.md | DONE-STALE | S for residual items | 3 | Set status to done-with-residuals; list residuals (SIEM, HSTS, off-host backup, kaniko executor accepted risk) as queue items. |
| LIFECYCLE_SECURITY_CHECKS_DESIGN_2026_11_10.md | DONE-STALE | S | 1 | Edit status line to point at LIFECYCLE_LICENSE_STORAGE_CONTRACT (FROZEN 2026-11-10) and LIFECYCLE_READS_GROUP_A/B_COMMAND_GATE_ENTRIES ... |
| OVERVIEW_ATTENTION_LIST_DESIGN_2026_11_13.md | DONE-STALE | S | 1 | Doc edit: PO ratifies/records amendment C and status flips; source comment in attentionRules.ts also cites DRAFT. |
| PACKET_ANALYSIS_ENGINE_IMAGE.md | DONE-STALE | S | 1 | Flip status to FROZEN/IMPLEMENTED citing EP-1a (B1_01C amendment 2026-10-15) and the deployed image; keep the licensing line as the one ... |
| PCP_STORAGE_ENGINE_DECISION.md | DONE-STALE | S | 1 | Check project/archive/backlog_terminal_2026.json (item pcp_storage_engine_decision is already in the terminal archive, note points to ... |
| TEST_SPEED_2026_11_05.md | DONE-STALE | S | 1 | Relabel as an engineering record (not a DRAFT contract) or leave; no code work. |
| UI2_0_B1_05_CP_INVENTORY_EXTRACTION_CONTRACT.md | DONE-STALE | S | 1 | Status-line update; confirm the channel-drain quirk UNKNOWN is carried in current cp_inventory_collect.yaml notes (not checked). |
| UI2_0_CUSTOM_RBAC_ROLES.md | DONE-STALE | S | 0 | Replace the "(DRAFT)" title with an implemented/historical note pointing at UI2_0_C3_IDENTITY_SESSIONS_RBAC_CONTRACT.md (FROZEN). |
| UI2_0_LDAP_CONFIGURATION.md | DONE-STALE | S | 0 | Mark it implemented and point at the C3C LDAP contract and PO_DECISION_RECORD_2026_11_11_LDAP_SERVICE_BIND.md. |
| UI2_CONFIGURATION_MICROSERVICE_ARCHITECTURE.md | DONE-STALE | S | 0 | Change the status from CONSENSUS DRAFT to implemented, and check that the cited ConfigurationPrivacyMaskingTest and port 8084 still match. |
| UI2_JAVA_SUITE_FIRST_LOCAL_RUN_BASELINE.md | DONE-STALE | S | 1 | Set the status to HISTORICAL/SUPERSEDED, or archive it to docs/history. |
| VSX_CHASSIS_FAILOVER_UNIT_2026_10_23.md | DONE-STALE | S | 0 | Rewrite the status line to IMPLEMENTED/RATIFIED, citing PO_DECISION_RECORD_2026_10_23_VSX_CHASSIS_READINESS.md and the 2026-11-19 chassis ... |
| CP_DISCOVERY_MEASURED_METHOD_AND_COMMAND_HANDOVER_2026_09_13.md | DONE-STALE | n/a | 1 | Edit status line to HISTORICAL and add one line pointing to the gate entries doc and commit 9c51759b. |
| DISCOVERY_VS_COLLECTION_PYTHON_KNOW_HOW_AUDIT_2026_09_12.md | DONE-STALE | n/a | 1 | Status-line edit only; optionally move to history. |
| FAILOVER_PILOT_STATUS_2026_10_20.md | DONE-STALE | n/a | 1 | Status-line edit only. |
| FAILOVER_READINESS_IN_THE_JAVA_PRODUCT_2026_09_15.md | DONE-STALE | n/a | 1 | Status-line edit only. |
| FAILOVER_RECIPROCAL_EVIDENCE.md | DONE-STALE | n/a | 1 | Status-line edit only. |
| SHIP_SPEED_PRETEST_2026_11_20.md | DONE-STALE | n/a | 0 | Optionally mark it a closed record (status "RECORD") ; recommendation 3 (explicit scope "none" in pretest_scope.py) is the only in-repo ... |
| PAN_DISCOVERY_PYTHON_STEP_MAP_2026_09_13.md | NOT-DRAFT | S | 1 | Confirm each section-9 UNKNOWN is covered by a Java-side contract/test, then relabel. |
| INCIDENT_2026_11_11_MDS_FORK_STORM.md | NOT-DRAFT | n/a | 0 | None for this doc. |
| PACKET_ANALYSIS_HOP_VERDICT_PROPOSAL.md | NOT-DRAFT | n/a | 0 | None; optional rename to drop "PROPOSAL" is not needed. |
| UI2_0_ARCHITECTURE_CONTRACT.md | NOT-DRAFT | n/a | 0 | None. |
| UI2_0_B1_01A_PLATFORM_SKELETON_CONTRACT.md | NOT-DRAFT | n/a | 1 | None beyond the audit's PO review. |
| UI2_0_B1_07_JOBS_SCREEN_CONTRACT.md | NOT-DRAFT | n/a | 0 | None. |
| UI2_0_ARCHITECTURE_REQUIREMENTS.md | SUPERSEDED | S | 1 | Status-line edit only after confirming the section 9 contradictions C-1..C-5 are dispositioned in the frozen contract (not checked item by ... |
| SNMP_MONITORING_DRAFT.md | SUPERSEDED | M for remaining | 2 | Edit status line to SUPERSEDED pointing to the frozen design; carry on M1 (verify each OID against one real answer) under the new design. |
| FAILOVER_EXECUTION_PLAN.md | SUPERSEDED | n/a | 1 | Status-line edit only. |
| FAILOVER_PILOT_COUNCIL_2026_10_06.md | SUPERSEDED | n/a | 1 | Status-line edit only (docs lane). |
| HA_1_AGENT_SERVER_ACCESS_DRAFT.md | SUPERSEDED | n/a | 1 | Status-line edit; optionally extract the "denial tests" into HOST_A hardening backlog. |
| OVERVIEW_EXCEPTION_SCREEN_CONTRACT_FABLE_DRAFT.md | SUPERSEDED | n/a | 0 | Set status to SUPERSEDED pointing at OVERVIEW_EXCEPTION_SCREEN_CONTRACT.md (or delete). |
| PAN_INVENTORY_ARCHITECTURE_AND_EVALUATION.md | SUPERSEDED | n/a | 1 | Set status SUPERSEDED, link the 14E record and the two review docs. |
| PATH_ANALYSIS_DESIGN_ARGON_ARTIFACT.md | SUPERSEDED | n/a | 0 | Set status SUPERSEDED/INPUT-ARCHIVED pointing at PATH_ANALYSIS_DESIGN.md; do not edit the body (kept unaltered by design). |
| PATH_ANALYSIS_DESIGN_FABLE.md | SUPERSEDED | n/a | 1 | Edit the status line only (keep the "unaltered Fable output" comment intact) to SUPERSEDED by PATH_ANALYSIS_DESIGN.md. |
| UI2_DISCOVERY_SSH_TRUST_ENROLLMENT_CONTRACT.md | SUPERSEDED | n/a | 0 | None. |
| ASA_FORTIGATE_POLICY_DESIGN_2026_11_14.md | PARTLY-DONE | S | 3 | Update the status line to say steps 0-9 are shipped; run the real-environment lookup validation as the remaining gate. |
| CP_R81_20_VOCABULARY_EVIDENCE_2026_09_12.md | OPEN | S | 2 | PO closes D-V5a/D-V6 on QUEUE; separately decide whether to fix the documented-token mismatch in the failover state parsing (ACTIVE ... |
| FORTIGATE_POLICY_LOOKUP_VALIDATION_READ_PROPOSAL_2026_11_23.md | OPEN | S after approval | 1 | PO literal decision; then an attended run and a validation record. |
| LOCAL_RELAY_PROTOCOL.md | PARTLY-DONE | S | 2 | Either ratify as-is after a REVIEW round or leave DRAFT; no engineering needed. |
| MDS_FORK_STORM_FIX_CONTRACT_2026_11_11.md | PARTLY-DONE | S | 2 | Update status to "A-D implemented; E not built; F pending PO run"; status ratified by PO_DECISION_RECORD_2026_11_11_MDS_FORK_STORM_FIXES. |
| PACKET_ANALYSIS_PORT_CONTRACT.md | PARTLY-DONE | S | 2 | Check ui2/packet-analysis/README.md and the decision records for per-phase freezes; then update the status line to point at them. |
| PAN_OS_11_VOCABULARY_EVIDENCE_2026_09_12.md | OPEN | S per decision, but ... | 3 | PO decides D-V1/D-V2/D-V3a in project/QUEUE.md using this evidence; D-V3a needs measurement on the approved real pair. |
| PATH_ANALYSIS_A6_UDP_PROPOSAL.md | OPEN | S-M | 2 | PO row-by-row approval, then one lane: gate rows + migration (next free number after V267), PathValidationCommand, planner and tests; ... |
| PATH_UNKNOWN_REDUCTION_PLAN_2026_11_05.md | PARTLY-DONE | S for closing the docs; M for ... | 2 | Re-run the nine aiview queries (state-only) per section 7 and record verdict/reason counts; add the missing pathWords.ts words in a small ... |
| SCRIPT_EXECUTION_REAL_ENV_VALIDATION_CHECKLIST_2026_11_23.md | OPEN | S | 1 | Verify the doc's known gap (does a script run send syslog/mail at its end). |
| SPRING_SECURITY_MIGRATION_PLAN_2026_11_15.md | PARTLY-DONE | S | 1 | Confirm per-step completion against backlog item `spring_boot_4_spring_security_migration` (commit 8c24f25f says steps 1-5 closed), then ... |
| TOPOLOGY_CONFLICTS_GUIDE_2026_11_05.md | OPEN | S | 1 | Optionally re-check the ten ConflictKind values against current TopologyGraph source, then relabel; per-kind split of ~250 conflicts stays ... |
| UI2_0_AGENT_FROZEN_CONTRACT_AUDIT.md | OPEN | S | 3 | PO reviews the verdict table; then an amendment lane fixes the C3 defect and marks the audit closed. |
| UI2_PROJECT_PLAN_RECONCILIATION.md | PARTLY-DONE | S to M | 1 | Run the Java and frontend ProjectPlan tests, which were UNVERIFIED in that movement. |
| AIVIEW_MASKED_READONLY_ADMIN_DRAFT_2026_11_20.md | PARTLY-DONE | M | 5 | Treat as SUPERSEDED-INTO-ONE with the 11-14 doc; PO answers the five questions once, then the freeze candidate is cut. |
| BACKUP_LINE_IMPLEMENTATION_AUDIT_2026_09_15.md | PARTLY-DONE | M | 2 | Re-run the clause check against current main (a read-only audit lane), update the verdict table, then close the doc. |
| COMPLIANCE_DATA_GAP_COMMANDS_2026_11_20.md | PARTLY-DONE | M | 4 | Measure real per-control data-gap counts on the fleet with an aiview count query, then put the NEW literals to the PO one by one. |
| CP_REMOTE_SHELL_NORMALIZATION_CONTRACT_2026_11_11.md | PARTLY-DONE | M | 3 | Mark phase 1 as built; run M2 lab read after PO per-literal approval; then rewrite sections 6 and Appendix 1 for phases 2-3. |
| CP_VSX_CONFIG_VS_CONTEXT_FINDINGS_2026_11_20.md | OPEN | M | 3 | PO decision on the command gate proposal; then gate rows plus ConfigurationCapabilityExecutor change. |
| DATABASE_ROLES_AND_DATA_OWNERSHIP_DESIGN_2026_11_14.md | PARTLY-DONE | M | 4 | Provision per-module DB secrets on the host, then Step 2 cutover (ui2-packet-analysis), module by module. |
| ESTATE_REWIND_AS_OF_DRAFT_2026_11_20.md | PARTLY-DONE | M | 4 | First slice exists (EstateAsOfController/Service/Response). |
| EXEC_OVERVIEW_DESIGN_2026_09_25_ASTRA.md | OPEN | M | 2 | Add an explicit status line; extract the per-control-target snapshot idea into backlog if wanted. |
| FAILOVER_FORTIGATE_ASA_DESIGN_DRAFT.md | PARTLY-DONE | M | 3 | Update status line to point to the ASA contract draft; finish the ASA contract's open Q3, then implement the switch lane. |
| FAILOVER_READINESS_CHECKS_CP_PAN_DRAFT.md | PARTLY-DONE | M | 3 | Reconcile against shipped CpReadinessScheduler/PreflightService (queue failover_readiness_assessment, P1) and rewrite the status line to ... |
| M14_LOCAL_LDAP_AUTHORIZATION_ARCHITECTURE.md | PARTLY-DONE / possibly SUPERSEDED | M | 2 | Reconcile §7 (frozen M14 row) against ui2 security (GateChain, ldap-adapter, SamlSettingsService); then mark SUPERSEDED or re-scope. |
| MDS_LOG_EXPORTER_HEALTH_PROPOSAL_2026_10_22.md | OPEN | M | 3 | PO decision on M0 measurement; no code until literals approved. |
| OPERATIONS_STATIC_ROUTE_CHANGE_CONTRACT_DRAFT_2026_11_17.md | PARTLY-DONE | M-L | 3 | Status refresh now; continue QUEUE static_route_add_delete (in_progress; R0 read gate and CLI exist). |
| PAN_CHANGE_ACTOR_FROM_PANORAMA_CONFIG_LOG_2026_11_11.md | PARTLY-DONE | M | 2 | Read the new counts-only path-kind and commit-shape telemetry on the trial Panorama, then fix the commit anchor (backlog item ... |
| PAN_OS12_COMMAND_ALTERNATIVES_2026_10_21.md | PARTLY-DONE | M | 3 | Mark done rows in a table, close the doc as SUPERSEDED by the 2026-10-21 and 2026-10-24 records for the approved rows, park the rest ... |
| POLICY_UNKNOWN_REDUCTION_PLAN_2026_11_05.md | PARTLY-DONE | M | 2 | Re-measure the shadow UNKNOWN share via aiview; ship the next ranked item from section 3 inside frozen contracts (recent commit 659787cb ... |
| SPIKE_LISTING_CHUNKING_PROPOSAL.md | PARTLY-DONE | M | 3 | Record count/size class of the first successful 1 MiB listing; only if growth recurs, request PO review of the `-t` run and C4t literal. |
| ACI_SEGMENTATION_SUPPORT_DESIGN_2026_11_13.md | PARTLY-DONE | L | 3 | Mark section 7 rows 1, 1v-prep and 2a as shipped in the status line; keep the doc DRAFT for steps 3-5 and hand them to the topology-sources ... |
| AIVIEW_MASKED_ADMIN_READ_DESIGN_2026_11_14.md | PARTLY-DONE | L | 4 | Merge this doc with AIVIEW_MASKED_READONLY_ADMIN_DRAFT_2026_11_20.md into one candidate for freeze (PO decides which is canonical), then ... |
| ANALYSIS_CONTAINER_DESIGN_2026_11_05.md | OPEN | L | 5 | Run the live measurements M1-M11 that are still open (M2-M5, M9 follow-up, M11) on HOST-A under the existing authorizations, then PO ... |
| ASA_FAILOVER_Q3_COUNCIL_PACKET_2026_11_23.md | OPEN | L for the build behind it | 3 | A nexus-po episode convenes the council on this packet and writes the verdict; then the code-review plugin on the implementing diff; then ... |
| DEVICE_SYSTEM_LOG_PROGRAMME_CONTRACT.md | PARTLY-DONE | L | 3 | Check what of phase A measurements (M5 recorded 2026-10-16, R1 2026-10-17) closed, freeze CP read set, then continue phase B. |
| FAILOVER_EXECUTION_ASA_CONTRACT_DRAFT_2026_11_18.md | OPEN | L | 2 | Run Q3 decision council and code review (ASA_FAILOVER_Q3_COUNCIL_PACKET_2026_11_23.md), then PO freeze, then gate row for the write literal. |
| LOAD_BALANCER_ARCHITECTURE_2026_11_05.md | OPEN | L | 3 | PO picks first vendor and slice; engineering drafts gate entries for approval. |
| MDS_SYSTEM_LOG_COLLECTION_DESIGN_2026_11_11.md | OPEN | L | 3 | Wait for fork-storm acceptance (§F), then PO measurement run; then freeze and slice. |
| OPERATIONS_POLICY_INSTALL_CONTRACT_DRAFT_2026_11_17.md | PARTLY-DONE | L | 3 | P1 done; QUEUE has policy_install_p2_p4 (P2/planned). |
| PAN_APPLICATION_DEFAULT_PLAN_2026_11_05.md | PARTLY-DONE | L | 3 | PO ruling on the command and pre-identification risk; otherwise leave parked. |
| PATH_ANALYSIS_PHASE_B_ARCHITECTURE_2026_11_05.md | OPEN | L | 6 | Take P-B1/P-B5, then split a first lane B0/B1 (UDP/ICMP) that needs only a frozen contract and no new command. |
| RECOVERY_OFFHOST_AND_KEY_CUSTODY_DESIGN_2026_11_14.md | OPEN | L | 14 | Send the section 8 questionnaire to the bank; in parallel finish S1 host roll (already merged: platform backup script step S1) and draft ... |
| REPORTS_R2_CONTRACT.md | OPEN | L | 1 | PO freeze, then R2a (schema, scheduler, run-now). |
| RULE_RECERTIFICATION_CONTRACT.md | OPEN | L | 8 | Run the three-seat decision council (PO session only, not engineering), then PO freeze, then implementation. |
| TOPOLOGY_SOURCES_DESIGN_2026_11_14.md | PARTLY-DONE | L | 3 | Freeze T1 section 4, then request per-literal approval for the next source read; ACI step 1 (impact analysis backend) already in progress. |
| UI2_BACKUP_PLANE_SEPARATION_CONTRACT.md | PARTLY-DONE | L | 5 | PO answers D-B1..D-B5 (a decision council is a candidate). |
| FIREWALL_POLICY_VIEW_DRAFT.md | PARTLY-DONE | n/a for the doc; any ... | 2 | A docs lane to map Q1-Q10 to the answering decision records and set the status line; no code. |
| UI2_0_FAILOVER_ENGINE_ARCHITECTURE.md | PARTLY-DONE | n/a for this doc | 1 | Update the status line. |

## Per-document notes

### BACKUP_MAJOR_DEVIATIONS_2026_10_23.md
- class: DONE-STALE (implemented and on main; only the PO review of the MAJOR definition is open)
- size: S
- po_decisions: 1
  - Accept the MAJOR versus MINOR category definition and the V176 kind widening (recommended: accept; pro: card now shows real counts instead of UNKNOWN; con: category list may need tuning after PO views real data)
- next_step: PO reviews the definition; then change the status line to ratified or record the amendments. No code change expected.
- new_device_command: no ; migration: no (V176 already shipped)
- evidence: commit 838b2fe0 (Backups: active major deviations from stored configuration runs); V176__configuration_deviation_modified_kind.sql; commit a1e75a42
- summary: Replaces the stub /backups/deviations endpoint with a read over stored configuration runs. It is implemented and merged with V176; only the status line claiming "awaiting PO review" remains. Read-only, no device command.

### CP_FAILOVER_ONE_SHELL_SESSION_2026_10_22.md
- class: DONE-STALE (implemented; pilot real-environment evidence open)
- size: S (the pilot run and a count readout)
- po_decisions: 2
  - Record the measured Log In count from the pilot cluster's audit log and decide fleet rollout (recommended: roll to the full list only after one-per-member is confirmed; pro: removes the 30-40 log-ins per run; con: a shell-path bug would hit every cluster)
  - Extend the pattern to inventory, configuration, backup and system-log collection (recommended: later, each with its own gate review)
- next_step: PO (or an authorised session) reads the pilot cluster audit-log count for one readiness pass, writes it into section 6 step 4, and sets the status line to implemented / pilot validated. No code change.
- new_device_command: no (L1-L5 approved, V175 seeded SIGNED_OFF) ; migration: no (V175 already shipped)
- evidence: V175__cp_failover_shell_framing_gate.sql (SIGNED_OFF); commits d8a0a3de, a04c880f, 8a8394f5
- summary: One framed SSH shell per member per readiness pass, off by default and allow-listed per cluster. Literals approved, amendment A1 confirmed, gate row signed off, manifests pinned. Status line still says amendment pending and DRAFT; the missing piece is the real-environment count.

### EXEC_HOME_AND_TRACE_DESIGN_SPEC_2026_11_05.md
- class: DONE-STALE (PO decisions B1, B4, C1, C3 resolved 2026-11-05; code batch landed)
- size: S
- po_decisions: 1
  - Retitle status to approved/implemented and record whether the +24h delta stays deferred (recommended: stays absent until a daily snapshot exists)
- next_step: Status-line fix; optionally verify the screen against the spec. Not verified in detail that every section (Trace modes, copy table) shipped.
- new_device_command: no ; migration: no (daily snapshot for the delta would be one)
- evidence: commits b582f9f5 (executive home, UI review IA), 65f28c0a (design-language amendment C1), 064dddd4 (Path screen)
- summary: Spec for the executive home and Trace screens. Status line still says awaiting PO decision, but section 0.2 says all were approved 2026-11-05 and the code batch shipped.

### HOST_A_HARDENING_DRAFT.md
- class: DONE-STALE (its section 5/6 record H1-H4, M1, M2, kernel, banner, AIDE as done on 2026-09-27)
- size: S for residual items
- po_decisions: 3
  - Name the off-host backup target for the 117 GB artefact store (recommended: name it; con: needs infrastructure owner).
  - SIEM target and protocol (M4, deferred) (recommended: defer until the PO names a SIEM).
  - HSTS and the corporate certificate once a host name is registered (recommended: do when the corporate CA is available).
- next_step: Set status to done-with-residuals; list residuals (SIEM, HSTS, off-host backup, kaniko executor accepted risk) as queue items. Note the 2026-09-25 reinstall may have invalidated the measured state; re-measurement is not verified.
- new_device_command: no ; migration: no
- evidence: commit 821fa487; docs/design/HOST_A_HARDENING_DRAFT.md sections 5-6; deploy/hosta/
- summary: Hardening measurements and proposal from 2026-09-27; nearly all findings were fixed the same day per the doc's own progress record. Only status line and a few residual decisions remain. Uncertain whether the later host reinstall preserved these settings.

### LIFECYCLE_SECURITY_CHECKS_DESIGN_2026_11_10.md
- class: DONE-STALE (mostly; PARTLY-DONE for vulnerability matching/Live Patch UI -- uncertain)
- size: S (status/pointer change); remaining vuln-matching slices unverified
- po_decisions: 1
  - Mark this doc SUPERSEDED/"design record; implementation in LIFECYCLE_* FROZEN contracts" (yes: points readers to the frozen/ratified successors; con: loses the DRAFT "open slices" signal)
- next_step: Edit status line to point at LIFECYCLE_LICENSE_STORAGE_CONTRACT (FROZEN 2026-11-10) and LIFECYCLE_READS_GROUP_A/B_COMMAND_GATE_ENTRIES (RATIFIED); confirm which §9 slices (vuln matching) remain and list them in QUEUE.
- new_device_command: no ; migration: no
- evidence: ui2/service/.../lifecycle/LifecycleProjection.java + advisory/AdvisoryEvaluator.java; docs/design/LIFECYCLE_LICENSE_STORAGE_CONTRACT.md (FROZEN); ui2/frontend/src/screens/LifecycleScreen.tsx
- summary: The DRAFT proposal was split into frozen storage and ratified gate-entry documents, and a Lifecycle screen, matcher and advisory evaluator exist in source. Only the draft status line is stale; whether every §9 slice (e.g. full CVE matching) shipped was not verified.

### OVERVIEW_ATTENTION_LIST_DESIGN_2026_11_13.md
- class: DONE-STALE (uncertain: partial PO approval)
- size: S
- po_decisions: 1
  - Approve remainder of design as amendment C of OVERVIEW_EXCEPTION_SCREEN_CONTRACT (yes: it is shipped and tested; con: only 3 points formally approved)
- next_step: Doc edit: PO ratifies/records amendment C and status flips; source comment in attentionRules.ts also cites DRAFT.
- new_device_command: no ; migration: no
- evidence: ui2/frontend/src/screens/overview/attentionRules.ts; tests attentionRules.test.ts; commits 596feed0, 46050da0
- summary: The attention list rules (license-expired, backup-stale 7 days) are implemented and hardened in the frontend. Only three points were PO approved; the rest awaits ratification as amendment C.

### PACKET_ANALYSIS_ENGINE_IMAGE.md
- class: DONE-STALE
- size: S (docs only)
- po_decisions: 1
  - tshark licensing / repository visibility note (line ~71 "PO / legal decision, pending"): recommend recording a one-line accept-or-defer in a decision record; pro: closes the only open item, con: needs legal input outside the repo
- next_step: Flip status to FROZEN/IMPLEMENTED citing EP-1a (B1_01C amendment 2026-10-15) and the deployed image; keep the licensing line as the one open note.
- new_device_command: no ; migration: no
- evidence: ui2/Containerfile.packet-analysis; commits c4e28001 (roll engine image), baef9889; UI2_0_B1_01C ... CONTRACT.md "Amendment 2026-10-15 EP-1a"
- summary: The doc's own "contradiction to resolve" (EP-1 one image) was resolved by EP-1a in the frozen container contract. The Containerfile exists and the image is rolled to ui2-packet-analysis. Only the status line and the tshark licensing note are stale.

### PCP_STORAGE_ENGINE_DECISION.md
- class: DONE-STALE (de facto): PostgreSQL with Flyway migrations (V211) is the shipped engine, matching its recommendation
- size: S (docs only)
- po_decisions: 1
  - Record the PO decision formally (PostgreSQL production engine) and close pcp_storage_engine_decision, or mark this doc SUPERSEDED by the later UI2_0_C_POSTGRESQL_NETWORKPOLICY_CONTRACT / platform schema contract (recommended: mark as historical; pro: no ceremony for an already-lived decision; con: loses a formal PO act, which the doc says is PO-only)
- next_step: Check project/archive/backlog_terminal_2026.json (item pcp_storage_engine_decision is already in the terminal archive, note points to docs/history/backlog) and set this doc status to SUPERSEDED/HISTORICAL. Gap #9 (key custody) was handed to the recovery_offhost doc.
- new_device_command: no ; migration: no
- evidence: ui2/service/src/main/resources/db/migration (V1..V211); project/archive/backlog_terminal_2026.json line ~920
- summary: A DRAFT evaluation recommending PostgreSQL, which the product has since adopted in practice. The backlog item is already in the terminal archive, so only the status line is stale. Uncertainty: I did not find a PO act that formally freezes it.

### TEST_SPEED_2026_11_05.md
- class: DONE-STALE (engineering record; its changes are committed)
- size: S
- po_decisions: 1
  - Re-measure on the slow host with `-Pui2.test.forks=1` vs default: optional, defer (recommended); pro: no cost now, con: the 58-minute claim stays unverified
- next_step: Relabel as an engineering record (not a DRAFT contract) or leave; no code work.
- new_device_command: no ; migration: no
- evidence: ui2/build.gradle.kts:53-59 (ui2.test.forks, SerialGC)
- summary: A measurement note, not a contract. The fork and GC changes it describes are in ui2/build.gradle.kts. The only open item is an optional re-measure on a slow host.

### UI2_0_B1_05_CP_INVENTORY_EXTRACTION_CONTRACT.md
- class: DONE-STALE (extract step consumed; the Java capability exists)
- size: S
- po_decisions: 1
  - Mark the DRAFT as executed/SUPERSEDED by the shipped capability spec: yes; pro: removes a DRAFT whose output exists, con: PO may want the channel-drain UNKNOWN kept as a record
- next_step: Status-line update; confirm the channel-drain quirk UNKNOWN is carried in current cp_inventory_collect.yaml notes (not checked).
- new_device_command: no ; migration: no
- evidence: ui2/capability-registry/src/main/resources/capabilities/cp_inventory_collect.yaml; commit 489dfc7b (contract), many later inventory commits
- summary: A Phase B1 "Extract" contract still labelled DRAFT-for-freeze from 2026-09-11. Check Point inventory collection now runs in ui2, so the extract/implement rows are long past. Only the status line is stale.

### UI2_0_CUSTOM_RBAC_ROLES.md
- class: DONE-STALE (probable; no status line, title says DRAFT)
- size: S (status/title fix only; confirm endpoints match first)
- po_decisions: 0
- next_step: Replace the "(DRAFT)" title with an implemented/historical note pointing at UI2_0_C3_IDENTITY_SESSIONS_RBAC_CONTRACT.md (FROZEN). A reconcile of /api/v2/roles against the code is needed before claiming full parity (not verified).
- new_device_command: no ; migration: no
- evidence: ui2/service/src/main/resources/db/migration/V27__custom_rbac_roles.sql; git 3e377420, c237e1c1 "Freeze LDAP and Custom RBAC contracts"; stray UI2_0_CUSTOM_RBAC_ROLES.md.orig/.rej sit beside it in docs/design
- summary: A 30-line sketch of DB-backed roles and permissions. A migration (V27) and later freeze commits exist, so the schema part is built. Whether the exact endpoint list and permission-matrix UI match is UNVERIFIED. The .orig and .rej files beside it are patch leftovers that should be deleted.

### UI2_0_LDAP_CONFIGURATION.md
- class: DONE-STALE (probable)
- size: S (status fix)
- po_decisions: 0
- next_step: Mark it implemented and point at the C3C LDAP contract and PO_DECISION_RECORD_2026_11_11_LDAP_SERVICE_BIND.md. The open QUEUE item P1 ldap_tls_trust_store_pin_and_format is separate work.
- new_device_command: no ; migration: no
- evidence: ui2/frontend/src/components/settings/DirectorySettingsPanel.tsx; V26__directory_profiles.sql, V204__ldap_service_bind_settings.sql; git c237e1c1
- summary: A 21-line sketch for DB-backed LDAP settings with a connection test. A directory settings panel, directory profile migrations and the service-bind design all exist. The exact endpoint paths were not verified.

### UI2_CONFIGURATION_MICROSERVICE_ARCHITECTURE.md
- class: DONE-STALE
- size: S
- po_decisions: 0
- next_step: Change the status from CONSENSUS DRAFT to implemented, and check that the cited ConfigurationPrivacyMaskingTest and port 8084 still match.
- new_device_command: no ; migration: no
- evidence: deploy/ui2/54-configuration-deployment.yaml; git fbd25f2d "implement ui2-configuration microservice"; git 16c8c80d
- summary: This describes the stateless ui2-configuration parser. The manifest and the implementation commit exist and it is rolled with every deploy. Only the status line is stale.

### UI2_JAVA_SUITE_FIRST_LOCAL_RUN_BASELINE.md
- class: DONE-STALE (a point-in-time report whose status was never closed)
- size: S
- po_decisions: 1
  - Mark it as a historical record, or delete it. Recommend a historical mark. Pro: keeps the evidence. Con: it adds clutter.
- next_step: Set the status to HISTORICAL/SUPERSEDED, or archive it to docs/history. It records a one-off run (eleven modules, no container runtime) and the module list has since grown (packet-analysis, sanitizer, frontend and others).
- new_device_command: no ; migration: no
- evidence: doc body (eleven modules); ui2 directory listing shows more modules
- summary: A read-only report of the first local Java suite run in movement NXS-LOCAL-0123. Its module list is out of date. It is not a contract and was never meant to be frozen.

### VSX_CHASSIS_FAILOVER_UNIT_2026_10_23.md
- class: DONE-STALE (design implemented; the status line still reads DRAFT)
- size: S
- po_decisions: 0
- next_step: Rewrite the status line to IMPLEMENTED/RATIFIED, citing PO_DECISION_RECORD_2026_10_23_VSX_CHASSIS_READINESS.md and the 2026-11-19 chassis failover record. The doc already says its section 3 and 5 text records the state before those decisions.
- new_device_command: no ; migration: no
- evidence: doc "Amendment 2026-11-19" (V255/V256 seeded); V177; git b0848e12, 2d8fd52e
- summary: Readiness reads are approved and seeded, and the chassis failover was ratified on 2026-11-19. The VS0-as-own-unit direction was also implemented per commit b0848e12. Only the DRAFT header remains stale.

### CP_DISCOVERY_MEASURED_METHOD_AND_COMMAND_HANDOVER_2026_09_13.md
- class: DONE-STALE (measurement handover absorbed by the approved gate-entries doc and merged discovery code; uncertainty: section 7 open verification items not re-checked)
- size: n/a (S if the PO wants the doc closed out with a measurement-closed note)
- po_decisions: 1
  - Mark as historical (measurement record) and point readers to CP_DISCOVERY_MANAGEMENT_COMMAND_GATE_ENTRIES.md (APPROVED 2026-09-14) (recommended: yes; pro: removes a DRAFT that looks like it authorises nothing but is mistaken for open work; con: none)
- next_step: Edit status line to HISTORICAL and add one line pointing to the gate entries doc and commit 9c51759b. Confirm the field bindings are no longer UNVERIFIED from the first live run report (not found in this check).
- new_device_command: no ; migration: no
- evidence: CP_DISCOVERY_MANAGEMENT_COMMAND_GATE_ENTRIES.md (APPROVED 2026-09-14); commit 9c51759b (measured shell commands replace guessed mgmt_cli set); merge d9d5811a (discovery domain core)
- summary: States what Check Point discovery takes and how each item was measured, and is explicitly not command approval. The command gate was approved the next day and the measured commands are in the code. Backlog item cp_discovery_java_implementation still says planned, which is itself stale (not verified).

### DISCOVERY_VS_COLLECTION_PYTHON_KNOW_HOW_AUDIT_2026_09_12.md
- class: DONE-STALE (read-only behavior map; its purpose, Java discovery built from it, was served)
- size: n/a
- po_decisions: 1
  - Leave as DRAFT historical know-how or mark SUPERSEDED/HISTORICAL (recommended: mark historical; pro: stops it being read as authority, con: none)
- next_step: Status-line edit only; optionally move to history.
- new_device_command: no ; migration: no
- evidence: doc status ("know-how only, never ported"); Java discovery exists in ui2/ (discovery modules; not individually verified)
- summary: 1080-line map of existing Python discovery vs collection behavior, written to inform the Java build. It decides nothing, so it has no open work. Only the status wording is a cleanup.

### FAILOVER_PILOT_STATUS_2026_10_20.md
- class: DONE-STALE (point-in-time status review; pilot completed 2026-10-22)
- size: n/a
- po_decisions: 1
  - Mark SUPERSEDED/historical (recommended; con: none).
- next_step: Status-line edit only.
- new_device_command: no ; migration: no
- evidence: commit d76d814e; docs/design/PO_DECISION_RECORD_2026_10_20_FAILOVER_PILOT_P0.md
- summary: A dated snapshot listing five P0 pilot blockers. The pilot has since run successfully per the project plan correction, so the P0 list is stale and only its PAN/VSX gap findings may still matter (tracked elsewhere in the queue). Uncertain whether each P0 item was individually closed; not verified.

### FAILOVER_READINESS_IN_THE_JAVA_PRODUCT_2026_09_15.md
- class: DONE-STALE (architecture analysis whose recommended shell has long shipped)
- size: n/a
- po_decisions: 1
  - Mark SUPERSEDED by FAILOVER_ENGINE_ARCHITECTURE.md / execution contracts (recommended; con: none).
- next_step: Status-line edit only.
- new_device_command: no ; migration: no
- evidence: ui2/service/.../failover/PreflightService.java; docs/design/FAILOVER_ENGINE_ARCHITECTURE.md
- summary: A 2026-09-15 analysis of how to port Python readiness to Java. The Java readiness service, preflight registry and failover execution now exist, so the document is historical.

### FAILOVER_RECIPROCAL_EVIDENCE.md
- class: DONE-STALE (implementation evidence note for PR 8)
- size: n/a
- po_decisions: 1
  - Mark SUPERSEDED/historical record of PR 8 (recommended; con: none).
- next_step: Status-line edit only.
- new_device_command: no ; migration: no (the note itself says none needed)
- evidence: commit cb828b84 "bind reciprocal roles to verified member observations"; PanFailoverChecks.java
- summary: Describes the PR 8 reciprocal-role binding for CP and PAN. The matching commit is on this branch, so only the DRAFT status is stale.

### SHIP_SPEED_PRETEST_2026_11_20.md
- class: DONE-STALE (engineering record; changes implemented, recommendations are outside the repo)
- size: n/a
- po_decisions: 0
- next_step: Optionally mark it a closed record (status "RECORD") ; recommendation 3 (explicit scope "none" in pretest_scope.py) is the only in-repo follow-up and is S (tools only). Recs 1-2 belong to the ops wrapper jt.sh.
- new_device_command: no ; migration: no
- evidence: commits 77639410 (ship speed fast path), 024ab2d6 (batch r1 ship-speed pretest inputs), 290e23e6; tools/delivery/pretest_scope.py
- summary: An engineering measurement record, not a contract, that never needed freezing. Its in-repo changes (cacheable frontendBuild, declared architecture-test inputs) are merged. Only the DRAFT label is a convention mismatch.

### PAN_DISCOVERY_PYTHON_STEP_MAP_2026_09_13.md
- class: NOT-DRAFT (evidence/know-how audit by design; purpose fulfilled, Java discovery exists) - suggest relabel HISTORICAL EVIDENCE
- size: S (relabel) ; remaining UNKNOWN register items n/a
- po_decisions: 1
  - Relabel as historical evidence record (recommend yes): pro: stops it reading as a pending draft, con: UNKNOWN register entries lose a visible home unless copied to the Java discovery contract
- next_step: Confirm each section-9 UNKNOWN is covered by a Java-side contract/test, then relabel.
- new_device_command: no ; migration: no
- evidence: ui2/service/.../discovery; commit 01cd8933 (Panorama discovery in worker); decision record 2026_09_13B
- summary: A read-only audit of the old Python collectors, explicitly not authority. Java discovery has since replaced it, so it is a historical record. Whether every UNKNOWN was carried forward is not verified.

### INCIDENT_2026_11_11_MDS_FORK_STORM.md
- class: NOT-DRAFT (status line is RECORDED; incident postmortem, not a contract)
- size: n/a
- po_decisions: 0
- next_step: None for this doc. Its fixes live in MDS_FORK_STORM_FIX_CONTRACT_2026_11_11.md (DRAFT) and CP_REMOTE_SHELL_NORMALIZATION_CONTRACT_2026_11_11.md; part A phase 1, no schedule and 45 min ceiling shipped in 47b4ec8f; queue item verify_first_scheduled_mds_export_and_asa_saved_state is related.
- new_device_command: no ; migration: no
- evidence: commit 47b4ec8f; docs/design/PO_DECISION_RECORD_2026_11_11_MDS_FORK_STORM_FIXES.md
- summary: Postmortem of the MDS export fork storm; a valid RECORDED document that matched the triage grep incidentally. The follow-on fix contracts are the ones worth triaging (parts D credential alert and E remote kill not verified).

### PACKET_ANALYSIS_HOP_VERDICT_PROPOSAL.md
- class: NOT-DRAFT (status line already says APPROVED 2026-10-18; matched on the quoted "Original status: DRAFT" line)
- size: n/a
- po_decisions: 0
- next_step: None; optional rename to drop "PROPOSAL" is not needed.
- new_device_command: no ; migration: no
- evidence: PO_DECISION_RECORD_2026_10_18_PACKET_ANALYSIS_HOP_VERDICT.md; ui2/packet-analysis export/HopVerdict (per doc)
- summary: Approved by PO and implemented in the expert report. Status line is current; the DRAFT text is the preserved original status.

### UI2_0_ARCHITECTURE_CONTRACT.md
- class: NOT-DRAFT (FROZEN 2026-09-12; match was incidental, "DRAFT" appears in its history text)
- size: n/a
- po_decisions: 0
- next_step: None.
- new_device_command: no ; migration: no
- evidence: docs/design/UI2_0_ARCHITECTURE_CONTRACT.md "## Status: FROZEN"
- summary: Frozen successor of UI2_0_ARCHITECTURE_DESIGN.md; mentions DRAFT only when describing the predecessor. Nothing to triage.

### UI2_0_B1_01A_PLATFORM_SKELETON_CONTRACT.md
- class: NOT-DRAFT (FROZEN 2026-09-12)
- size: n/a
- po_decisions: 1
  - Review of the agent-applied freeze (audit marks it NEEDS_PO_REVIEW): ratify; covered under the audit doc's decisions
- next_step: None beyond the audit's PO review.
- new_device_command: no ; migration: no
- evidence: Status line FROZEN; ui2/ modules exist (platform-core, persistence, job-engine)
- summary: Frozen skeleton contract describing artifacts that exist. Match was incidental.

### UI2_0_B1_07_JOBS_SCREEN_CONTRACT.md
- class: NOT-DRAFT (FROZEN 2026-09-12)
- size: n/a
- po_decisions: 0
- next_step: None.
- new_device_command: no ; migration: no
- evidence: Status line FROZEN; ui2/frontend/tests/JobsPillsAndDeepLink.test.tsx
- summary: Frozen Jobs screen contract; the DRAFT mentions are history. Jobs UI is implemented.

### UI2_0_ARCHITECTURE_REQUIREMENTS.md
- class: SUPERSEDED (by UI2_0_ARCHITECTURE_CONTRACT.md, which resolved the scope; inferred, status line not updated)
- size: S
- po_decisions: 1
  - Mark SUPERSEDED by UI2_0_ARCHITECTURE_CONTRACT.md and UI2_0_DEVELOPMENT_WORKFLOW.md: yes; pro: removes a DRAFT with open section-9 contradictions already resolved by frozen successors, con: none material
- next_step: Status-line edit only after confirming the section 9 contradictions C-1..C-5 are dispositioned in the frozen contract (not checked item by item; UNCERTAIN).
- new_device_command: no ; migration: no
- evidence: UI2_0_ARCHITECTURE_CONTRACT.md (FROZEN, 2026-09-12); ui2/ tree exists and runs the console
- summary: Requirements-gathering DRAFT from 2026-09-08/09 that deferred the scope choice; the Java console was built under the frozen contract. Status line is stale.

### SNMP_MONITORING_DRAFT.md
- class: SUPERSEDED (by SNMP_DEVICE_METRICS_DESIGN_2026_11_24.md, FROZEN; SNMP_M0_MEASUREMENT_RECORD_2026_11_24.md) / PARTLY-DONE
- size: M for remaining (fast telemetry plane snmpv3_fast_telemetry_plane P2; M1 OID verification)
- po_decisions: 2
  - Mark the 2026-09-28 draft SUPERSEDED by the 2026-11-24 frozen design (recommended; pro: one authority; con: traps/interface-counter ideas in the draft need a home in the backlog)
  - Confirm SNMP trap receiver stays P3/out of scope (recommended: yes; pro: no inbound listener; con: no push events)
- next_step: Edit status line to SUPERSEDED pointing to the frozen design; carry on M1 (verify each OID against one real answer) under the new design. Poll commands need the command gate (read-only SNMP GET).
- new_device_command: yes (SNMP GET OIDs, per the frozen design M1) ; migration: unconfirmed (V114__snmp_credentials.sql exists, metrics storage unknown)
- evidence: docs/design/SNMP_DEVICE_METRICS_DESIGN_2026_11_24.md (FROZEN), commit 989b0420 (SNMP design CP memActiveReal64 label fix), V114__snmp_credentials.sql, project/QUEUE.md line 127
- summary: The original open-ended draft, now overtaken by the PO-frozen device-metrics design of 2026-11-24 that cites its own M0 measurement. Only the status line and a cross-reference are stale. Remaining work is tracked under the frozen design.

### FAILOVER_EXECUTION_PLAN.md
- class: SUPERSEDED (CP execution by FAILOVER_EXECUTION_CP_CONTRACT.md FROZEN 2026-09-27 and PO_DECISION_RECORD_2026_10_06_FAILOVER_EXECUTION_ESTATE.md RATIFIED)
- size: n/a
- po_decisions: 1
  - Mark SUPERSEDED, with a pointer to the CP contract and 2026-10-06 record (recommended; pro: avoids it being read as a plan, con: none). Its six section-5 questions: assumed answered by the 2026-10-06 record, not verified one by one.
- next_step: Status-line edit only.
- new_device_command: no ; migration: no
- evidence: docs/design/FAILOVER_EXECUTION_CP_CONTRACT.md (FROZEN); commits d4df7ee5, c13d4c84; be2caa1f
- summary: 2026-10-06 planning document for the first controlled CP failover pilot. The decisions it lists were ratified the same day and the CP contract is frozen, so the plan is historical.

### FAILOVER_PILOT_COUNCIL_2026_10_06.md
- class: SUPERSEDED (by PO_DECISION_RECORD_2026_10_20_FAILOVER_PILOT_P0.md and the frozen FAILOVER_EXECUTION_CP/PAN contracts; pilot succeeded 2026-10-22)
- size: n/a
- po_decisions: 1
  - Mark SUPERSEDED/historical; no council needed (recommended; con: none, the council was blocked and the pilot already ran).
- next_step: Status-line edit only (docs lane).
- new_device_command: no ; migration: no
- evidence: commit d76d814e (failover pilot succeeded 2026-10-22); docs/design/PO_DECISION_RECORD_2026_10_20_FAILOVER_PILOT_P0.md
- summary: A blocked-council planning record for the CP failover pilot. The pilot decisions it proposed were later ratified and executed, so the document is historical only.

### HA_1_AGENT_SERVER_ACCESS_DRAFT.md
- class: SUPERSEDED (by PO_DECISION_RECORD_2026_09_15A_THE_DEVELOPMENT_HOST_AND_WHAT_AN_AGENT_MAY_DO_ON_IT.md, HOST_REGISTER.md, and the 2026-09-24 HOST-A owned-by-neXus record)
- size: n/a
- po_decisions: 1
  - Mark SUPERSEDED; the placement question is decided (HOST-A owned by neXus, agent sudo) (recommended; con: its denial-test ideas are lost unless extracted).
- next_step: Status-line edit; optionally extract the "denial tests" into HOST_A hardening backlog.
- new_device_command: no ; migration: no
- evidence: docs/design/PO_DECISION_RECORD_2026_09_24_HOST_A_OWNED_BY_NEXUS_AGENT_SUDO.md; AGENTS.md Host action boundary
- summary: A 2026-09-15 proposal for bounded agent server access, written before the host was reinstalled for neXus alone. The PO decisions of 09-15A, 09-19 and 09-24 settled access, so this is historical.

### OVERVIEW_EXCEPTION_SCREEN_CONTRACT_FABLE_DRAFT.md
- class: SUPERSEDED (by OVERVIEW_EXCEPTION_SCREEN_CONTRACT.md, FROZEN 2026-09-23)
- size: n/a
- po_decisions: 0
- next_step: Set status to SUPERSEDED pointing at OVERVIEW_EXCEPTION_SCREEN_CONTRACT.md (or delete).
- new_device_command: no ; migration: no
- evidence: docs/design/OVERVIEW_EXCEPTION_SCREEN_CONTRACT.md:7 (FROZEN, PO accepted 2026-09-23)
- summary: Early Fable draft of the first-release Overview contract; the FROZEN contract with the same scope replaced it. Only the status line is stale.

### PAN_INVENTORY_ARCHITECTURE_AND_EVALUATION.md
- class: SUPERSEDED (by PO_DECISION_RECORD_2026_09_14E_PAN_INVENTORY_MEASURED_FORMS.md FROZEN and PAN_INVENTORY_API_ROUTE_GATE_ENTRIES.md; reviews in CLAUDE_/CODEX_PAN_INVENTORY_REVIEW.md)
- size: n/a
- po_decisions: 1
  - Mark SUPERSEDED/HISTORICAL (recommend yes): pro: the four review questions were answered by the record, con: Q1 (direct vs Panorama proxy) answer not re-verified here
- next_step: Set status SUPERSEDED, link the 14E record and the two review docs.
- new_device_command: no ; migration: no
- evidence: PO_DECISION_RECORD_2026_09_14E...; commit 1f47c89e (inventory M-4), c9915429
- summary: An evaluation prompt for Codex/Fable review; the reviews exist and the frozen 14E record plus route gate entries carry the decisions. Inventory collection has shipped. Only the status line is stale.

### PATH_ANALYSIS_DESIGN_ARGON_ARTIFACT.md
- class: SUPERSEDED (by PATH_ANALYSIS_DESIGN.md, FROZEN 2026-11-04, the merged design, which lists this as an input)
- size: n/a
- po_decisions: 0
- next_step: Set status SUPERSEDED/INPUT-ARCHIVED pointing at PATH_ANALYSIS_DESIGN.md; do not edit the body (kept unaltered by design).
- new_device_command: no ; migration: no
- evidence: PATH_ANALYSIS_DESIGN.md header "Inputs (all unaltered...)"; PO_DECISION_RECORD_2026_11_03_PATH_ANALYSIS_PHASE_A.md
- summary: One of two independent designs merged into the frozen path analysis design. It remains a historical input. Only the status line needs updating.

### PATH_ANALYSIS_DESIGN_FABLE.md
- class: SUPERSEDED (by PATH_ANALYSIS_DESIGN.md, FROZEN 2026-11-04, plus PO_DECISION_RECORD_2026_11_03_PATH_ANALYSIS_PHASE_A.md D-1..D-9) / Phase A DONE-STALE
- size: n/a
- po_decisions: 1
  - Mark the doc SUPERSEDED/historical with a pointer to PATH_ANALYSIS_DESIGN.md (recommended; pro: removes a DRAFT that reads like authority; con: none, it is a consultation record kept unaltered, so a header-only edit is needed)
- next_step: Edit the status line only (keep the "unaltered Fable output" comment intact) to SUPERSEDED by PATH_ANALYSIS_DESIGN.md.
- new_device_command: no ; migration: no
- evidence: ui2/service/.../service/pathanalysis/{engine,query,topology,validation}; commits 064dddd4 (A5b Path screen), 5d7096a6 (A6 validate API), a3021e84 (A6 Verify on devices)
- summary: Fable's Phase A design input; its content was folded into the frozen PATH_ANALYSIS_DESIGN.md and Phase A (A0-A6) is implemented in the pathanalysis package and Path screen. Only the status line is stale. Phase B/C/D plug-in points live on in the Phase B architecture doc.

### UI2_DISCOVERY_SSH_TRUST_ENROLLMENT_CONTRACT.md
- class: SUPERSEDED (by UI2_0_C10_DISCOVERY_SSH_TRUST_ENROLLMENT_CONTRACT.md, FROZEN)
- size: n/a
- po_decisions: 0
- next_step: None. The status line is already correct; the grep matched a DRAFT mention in the body.
- new_device_command: no ; migration: no
- evidence: status line of the doc; docs/design/UI2_0_C10_DISCOVERY_SSH_TRUST_ENROLLMENT_CONTRACT.md exists
- summary: The status line already reads SUPERSEDED, historical only, with movement NXS-LOCAL-0234 freezing the C10 contract as its successor. Keep it as the trace-to-source record.

### ASA_FORTIGATE_POLICY_DESIGN_2026_11_14.md
- class: PARTLY-DONE (nearly all steps built; real-environment validation open)
- size: S (validation lane) plus M if the FortiGate lookup validation read is approved and built
- po_decisions: 3
  - Approve (or reject) the DRAFT FortiGate policy-lookup validation read proposal literal (recommended: review it separately; pro: gives a device-side check like ASA packet-tracer; con: new device command)
  - Pick the 20 masked lookups per vendor for the real-environment comparison (recommended: PO picks; pro: PO controls sampling)
  - Q6 hit counts R1 and device corroboration R2/R3 (recommended: keep as Later, decided after the validation run)
- next_step: Update the status line to say steps 0-9 are shipped; run the real-environment lookup validation as the remaining gate. Keep AUTOMATED_VALIDATED until then.
- new_device_command: yes (only if the FortiGate validation read is approved; none otherwise) ; migration: no
- evidence: docs/history/backlog/asa_fortigate_policy_model.md (steps 0-9, HA_POLICY_DIVERGENT, FortiGate Trace on main); commits 1588f36f, 3ea02652, 0afbe487
- summary: The Phase A design for ASA and FortiGate policy collection and model. Parsers, mappers, lookup, hygiene, Trace, HA divergence and ACI consumption are all merged. Only live validation and the optional device corroboration reads remain, so the DRAFT line is stale.

### CP_R81_20_VOCABULARY_EVIDENCE_2026_09_12.md
- class: OPEN (evidence record; DRAFT by nature, never a contract)
- size: S
- po_decisions: 2
  - Close D-V5a and D-V6 on QUEUE as settled by official R81.20 docs (recommended; pro: removes stale blockers, con: docs-only evidence, no real-env corroboration)
  - Keep D-V7b open as NOT_EVALUABLE (recommended; pro: docs show no CLI read surface, con: a later release may add one)
- next_step: PO closes D-V5a/D-V6 on QUEUE; separately decide whether to fix the documented-token mismatch in the failover state parsing (ACTIVE ATTENTION / STANDBY READY literals, LOST/INIT unrecognised). Uncertain whether that mismatch was already fixed (addendum narrowed it; not re-verified).
- new_device_command: no ; migration: no
- evidence: project/QUEUE.md lines 147-149 (D-V5a/D-V6/D-V7b open); doc summary table
- summary: Official-docs evidence record for three open CP ClusterXL vocabulary items. Two are settled by docs, one (D-V7b) is not. No code change is authorized by it.

### FORTIGATE_POLICY_LOOKUP_VALIDATION_READ_PROPOSAL_2026_11_23.md
- class: OPEN (fresh proposal; deliberately DRAFT awaiting PO literal approval)
- size: S after approval (one attended read plus parser validation); no code before
- po_decisions: 1
  - Approve or reject the exact `diagnose firewall` policy-lookup literal for one named test scope (recommended: approve for one FortiOS 7.4.1+ unit; con: older FortiOS form is undocumented so some units return UNKNOWN).
- next_step: PO literal decision; then an attended run and a validation record. Keep AUTOMATED_VALIDATED until then.
- new_device_command: yes ; migration: no
- evidence: commit 0afbe487; project/QUEUE.md NXS-LOCAL-0413 and asa_fortigate_policy_model
- summary: Research record from official Fortinet documentation proposing one read to validate FortiGate policy lookup. It approves nothing and is the newest doc (2026-11-23); it is correctly DRAFT.

### LOCAL_RELAY_PROTOCOL.md
- class: PARTLY-DONE
- size: S
- po_decisions: 2
  - Keep DRAFT and record it as "implemented, non-default" (yes: matches reality; con: perpetual draft)
  - Run recommended decision-council REVIEW before promotion to FROZEN/ratified (defer unless local relay becomes default)
- next_step: Either ratify as-is after a REVIEW round or leave DRAFT; no engineering needed.
- new_device_command: no ; migration: no
- evidence: tools/delivery/local_relay.py exists; DECISION_RECORD_SUCCESSOR_INDEX.md:170 lists as standalone; referenced by GOV_PO_2 / GOV_ORCH_1
- summary: Mechanism is implemented (tools/delivery/local_relay.py) and used by the orchestrator, but the doc self-declares DRAFT/not ratified and promotion is an explicit future DECIDE. Referenced as precedent by FROZEN governance docs, which is a latent authority tension (uncertain).

### MDS_FORK_STORM_FIX_CONTRACT_2026_11_11.md
- class: PARTLY-DONE
- size: S (remaining F acceptance run + E decision)
- po_decisions: 2
  - Leave §E remote kill unbuilt, mark contract IMPLEMENTED A-D (yes: PO declined session-id; con: operator must kill by hand)
  - PO schedule of §F acceptance run before re-enabling MDS (QUEUE verify_first_scheduled_mds_export is related)
- next_step: Update status to "A-D implemented; E not built; F pending PO run"; status ratified by PO_DECISION_RECORD_2026_11_11_MDS_FORK_STORM_FIXES.
- new_device_command: no (literals already approved per record) ; migration: no (V206, V207 exist)
- evidence: commits 47b4ec8f, 4656aca4; V206__cp_mds_export_no_login_shell.sql, V207__credential_failure_alert.sql
- summary: Parts B, C and D shipped (no login shell, 45 min ceiling, no schedule, credential failure alert/canary) and the PO decision record is RATIFIED. Status line still reads DRAFT; remote kill (E) deliberately not built.

### PACKET_ANALYSIS_PORT_CONTRACT.md
- class: PARTLY-DONE (status stale; engine, shell, image, exports, hop verdict all shipped)
- size: S (status/ledger reconciliation), uncertain: later-phase freezes not found as separate docs
- po_decisions: 2
  - Freeze the contract as FROZEN-with-phases-done (or mark HISTORICAL/SUPERSEDED by the decision record + READMEs): recommend freeze as-is; pro: removes DRAFT authority ambiguity, con: some later phases (UI port, correlation) may lack frozen text - UNKNOWN
  - Confirm the real-case parity gate (same verdict on three real cases, Mac) is closed: recommend record result; pro: closes DONE gate, con: needs operator evidence
- next_step: Check ui2/packet-analysis/README.md and the decision records for per-phase freezes; then update the status line to point at them.
- new_device_command: no ; migration: no
- evidence: commit 45d85275 (Java engine S17); ui2/packet-analysis; PO_DECISION_RECORD_2026_10_18_AIVIEW_PACKET_ANALYSIS_RUN.md
- summary: Authority for phase 0, numeric layer and shell; the Java engine, workspace, exports, report and correlation have since shipped. The status line was never advanced. Which later phases got their own freeze is not established here.

### PAN_OS_11_VOCABULARY_EVIDENCE_2026_09_12.md
- class: OPEN (evidence only; D-V1, D-V2, D-V3a still in project/QUEUE.md and cited as open by FAILOVER_PILOT_STATUS_2026_10_20.md)
- size: S per decision, but resolution needs real-environment evidence (M overall)
- po_decisions: 3
  - D-V1 conn-* value vocabulary: recommend keep UNKNOWN-for-unlisted and approve documented values only; pro: fail-closed, con: some states stay UNKNOWN
  - D-V2 preemptive/state-sync vocabulary: recommend same approach, resolve before P0-2 of the failover pilot
  - D-V3a serial-num semantics in HA state: recommend keep STILL_UNKNOWN until a real pair measurement (D-V3b)
- next_step: PO decides D-V1/D-V2/D-V3a in project/QUEUE.md using this evidence; D-V3a needs measurement on the approved real pair.
- new_device_command: no (existing HA-state read) ; migration: no
- evidence: project/QUEUE.md lines 143-146; FAILOVER_PILOT_STATUS_2026_10_20.md line 59; PAN_SERIAL_REPRESENTATION_IDENTITY_EVIDENCE_CLOSURE.md
- summary: Documentation evidence for three open vocabulary decisions that still block failover readiness. It is deliberately not a contract. Remains useful as the cited evidence until the decisions are made.

### PATH_ANALYSIS_A6_UDP_PROPOSAL.md
- class: OPEN
- size: S-M (two literals, one gate migration, tests; real-env UDP sample separate gate)
- po_decisions: 2
  - Approve rows 6 and 7 (UDP protocol 17 literals) exactly as written: recommend yes, by row number; pro: closes the TCP-only gap on PAN hops, con: new device command, answer-shape UNKNOWN until real sample
  - Reject the single-literal PROTO placeholder alternative (section 2.1): recommend two fixed literals; pro: no edit to signed-off rows 4/5, con: two more gate rows
- next_step: PO row-by-row approval, then one lane: gate rows + migration (next free number after V267), PathValidationCommand, planner and tests; real-env UDP sample later.
- new_device_command: yes ; migration: yes
- evidence: no protocol-17 literal in ui2 main sources (grep); PathValidationGateTest; latest migration V267
- summary: Path validation sends TCP-only policy-match tests on Palo Alto hops, so UDP queries get FIB checks only. The proposal adds two fixed UDP literals mirroring the approved TCP rows. Nothing is implemented.

### PATH_UNKNOWN_REDUCTION_PLAN_2026_11_05.md
- class: PARTLY-DONE
- size: S for closing the docs; M for remaining "proposed" items
- po_decisions: 2
  - Convert the doc to a record (status "implemented items = amendment of PATH_ANALYSIS_DESIGN section 4"), and move the "proposed" items into backlog entries (recommended; pro: drops "DO NOT FREEZE" ambiguity; con: edit of a mixed doc)
  - Authorize the "proposed" items only per-item after live re-run evidence (recommended; pro: evidence first; con: slower)
- next_step: Re-run the nine aiview queries (state-only) per section 7 and record verdict/reason counts; add the missing pathWords.ts words in a small UI lane (no command, no migration).
- new_device_command: no ; migration: no
- evidence: pathanalysis/topology/NextHopResolver.java; commit b582f9f5 (code batch 2026-11-05, path and policy UNKNOWN work); doc section 6 "Tests (implemented)"
- summary: Mix of implemented deterministic engine choices (already recorded as an amendment of the frozen design) and proposed ones that need evidence first. Status stays AUTOMATED_VALIDATED until the live re-run. The follow-up UI words are still listed as a separate change; I did not verify whether they were added.

### SCRIPT_EXECUTION_REAL_ENV_VALIDATION_CHECKLIST_2026_11_23.md
- class: OPEN (a checklist for a PO-run real-environment validation, not a contract; correctly DRAFT)
- size: S (PO run; engineering side only fixes gaps found)
- po_decisions: 1
  - Run the checklist personally once slice 3 is live and record results; then advance script_execution_module to REAL_ENV_VALIDATED (recommended per the 2026-11-23 PO decision; pro: only real-env evidence can close it; con: PO time)
- next_step: Verify the doc's known gap (does a script run send syslog/mail at its end). ScriptExecutionService validates notify modes syslog/smtp/both/none but I did not find the sender in service/script; confirm before the PO runs P1. Then PO executes the run.
- new_device_command: no (harmless non-device script, doc address only) ; migration: no
- evidence: ui2/service/.../script/ScriptExecutionService.java (NOTIFY_MODES); project/QUEUE.md line 14 (script_execution_module in_progress)
- summary: A runnable checklist for slices 1-3 of Script Execution, deliberately DRAFT and decision-free. It stays useful until the PO has run it. Uncertainty: whether slice 3 notification sending exists on this branch.

### SPRING_SECURITY_MIGRATION_PLAN_2026_11_15.md
- class: PARTLY-DONE (status line stale; steps 1-5 reported shipped; verify any remaining)
- size: S (mostly status/closure; unknown remainder, UNCERTAIN)
- po_decisions: 1
  - Freeze or mark the plan as executed/historical now that steps 1-5 merged: mark executed (recommended); pro: removes a DRAFT that is already implemented, con: needs PO sign-off on a retrospective status
- next_step: Confirm per-step completion against backlog item `spring_boot_4_spring_security_migration` (commit 8c24f25f says steps 1-5 closed), then update the status line; no new work expected.
- new_device_command: no ; migration: no
- evidence: commits 7039c667 (step 2), 9e38b355 (step 5), 8c24f25f (backlog closes steps 1-5); no open Spring item in project/QUEUE.md
- summary: The plan is a DRAFT but git history shows Steps 1-5 (foundation, session bridge, break-glass, SAML/ADFS, authorization map) landed. Only the status line and a closure note remain. I did not verify step 3/4 details file by file (uncertain).

### TOPOLOGY_CONFLICTS_GUIDE_2026_11_05.md
- class: OPEN (explanatory guide, DRAFT by design; not a build item)
- size: S
- po_decisions: 1
  - Promote to a reference doc (docs/reference) with a non-contract status: yes; pro: correct home and status, con: a small move/links update
- next_step: Optionally re-check the ten ConflictKind values against current TopologyGraph source, then relabel; per-kind split of ~250 conflicts stays UNKNOWN until read via aiview projection.
- new_device_command: no ; migration: no
- evidence: ui2/service/src/test/java/.../pathanalysis/topology/TopologyBuilderTest.java; commit f612ba36 (root cause of conflict rise)
- summary: An operator/engineering explanation derived from source, explicitly not a contract. The guide's kinds still exist in the topology code. It only needs a drift check and a better status label.

### UI2_0_AGENT_FROZEN_CONTRACT_AUDIT.md
- class: OPEN (level-6 audit report awaiting PO review; DRAFT by design)
- size: S
- po_decisions: 3
  - Review/ratify the self-applied FROZEN acts flagged NEEDS_PO_REVIEW (B1-1a, B1-2A, D1): ratify; pro: closes PO section 6 requirement, con: needs PO read time
  - Correct C3 mismatch (audit found DEFECTIVE: Correction C-1 description and "two audit rows" vs "exactly one"): fix the C3 text; pro: removes a false cross-reference, con: touches a FROZEN doc via amendment
  - Disposition of the report itself (close as SUPERSEDED/historical once PO rules): close after the above
- next_step: PO reviews the verdict table; then an amendment lane fixes the C3 defect and marks the audit closed. No code.
- new_device_command: no ; migration: no
- evidence: docs/design/UI2_0_AGENT_FROZEN_CONTRACT_AUDIT.md section 1-2 verdicts; DECISION_RECORD_SUCCESSOR_INDEX.md lists it as "amends AGENTS.md" (UNCERTAIN what that means)
- summary: An agent-written audit of agent-frozen contracts; applies no status. No evidence in git log that the PO has ruled on it. Worth a short PO review pass.

### UI2_PROJECT_PLAN_RECONCILIATION.md
- class: PARTLY-DONE
- size: S to M (validation tests plus deployment wiring)
- po_decisions: 1
  - Approve or reject the proposed snapshot publication, which is a separate deployment change. Recommend deferring it until the Java ProjectPlan tests pass. Pro: cheap. Con: the project plan screen is not fresh until then.
- next_step: Run the Java and frontend ProjectPlan tests, which were UNVERIFIED in that movement. Then either close the record or open a deployment lane. The work does not need a device command.
- new_device_command: no ; migration: no
- evidence: doc "Validation and next action" section; the project/java_product_plan.json mentioned
- summary: The implementation record says the code is done but the tests could not run in that sandbox and the source-publication proposal was not deployed. Whether the later commits closed this is UNVERIFIED. The deployment step needs separate authorisation.

### AIVIEW_MASKED_READONLY_ADMIN_DRAFT_2026_11_20.md
- class: PARTLY-DONE (same backlog item as above; this is the shorter route-inventory note, slice 1 shipped)
- size: M
- po_decisions: 5
  - Q1 credentials rows: masked posture rows (see above for pro/con)
  - Q2 LDAP and SAML: mask URLs through TopologyNamePseudonymizer and replace PEM bodies with a SHA-256 stub (pro: no key material; con: cannot verify cert contents)
  - Q3 ssh-trust fingerprints to FP- tokens and withhold failover free-text reasons (pro: closes known raw fields; con: loses reason text for review)
  - Q4 keep /jobs/*/transcript at 403 for aiview (pro: raw transcript stays closed; con: none)
  - Q5 seeded accounts and the shared HMAC key (same as Q2/Q3 of the 11-14 doc)
- next_step: Treat as SUPERSEDED-INTO-ONE with the 11-14 doc; PO answers the five questions once, then the freeze candidate is cut. Slice 2 is the route split plus the canary leak test.
- new_device_command: no ; migration: no
- evidence: commit 26330958; AiviewReadonlyAdminFlagTest.java; docs/history/backlog/aiview_masked_readonly_admin.md
- summary: Route-by-route inventory of what aiview cannot see or sees unmasked on Administration and Operations, with the proof-test plan. It duplicates the questions of the 11-14 design and should be merged with it. Slice 1 is live behind a default-off switch.

### BACKUP_LINE_IMPLEMENTATION_AUDIT_2026_09_15.md
- class: PARTLY-DONE (point-in-time audit, 55 clauses; Tier 1 findings fixed since)
- size: M (remaining NOT_IMPLEMENTED clauses: retention compiler C7-6, key rotation C7-12, consistency groups C7-13, profiles C7-1)
- po_decisions: 2
  - Re-badge as a historical record (HISTORICAL/AUDIT-SNAPSHOT) rather than keep DRAFT (recommended: yes; pro: it authorises nothing; con: loses the DRAFT marker that triggers triage)
  - Order the remaining 20 NOT_IMPLEMENTED clauses against the planned backup_plane_separate_service item (recommended: fold into that plan; pro: one backup roadmap; con: delays the small ones)
- next_step: Re-run the clause check against current main (a read-only audit lane), update the verdict table, then close the doc. The three headline findings (BK-14/OR-1, BK-12/BW-4, BW-3/C7-2) have backlog items marked done for the first two; BW-3 not verified.
- new_device_command: no ; migration: possible (retention ledger, key rotation) - UNKNOWN until re-audit
- evidence: docs/history/backlog/backup_artefact_id_opaque_not_storage_path.md (done); backup_retrieval_audit_fail_closed_and_reason_persists.md (done); backup_plane_separate_service.md (planned)
- summary: A 2026-09-15 audit of backup clauses against source, with 19 clauses implemented and tested and 20 not implemented. Its top two findings are closed; the document itself is two months old and its table is stale. Uncertain which of the other 18 gaps still hold.

### COMPLIANCE_DATA_GAP_COMMANDS_2026_11_20.md
- class: PARTLY-DONE
- size: M (per NEW literal: ASA logging and http reads, Fortinet ntp and logging, optional Check Point ciphers; ASA failover and mode reads)
- po_decisions: 4
  - Approve the two NEW Cisco ASA read literals (logging, http) as gate rows (recommended: approve one at a time; pro: closes 9 ASA controls partly; con: new device commands)
  - Approve the Fortinet ntp and remote-logging reads (recommended: check first whether a parse-scope extension of the existing show covers them; pro: no new command; con: needs a doc check)
  - ASA failover and mode: decide a second read result per device input contract (recommended: defer; pro: avoids evaluator contract change; con: asa_failover_link stays UNKNOWN)
  - Check Point ciphers 2.5.4 and unused interfaces (recommended: defer; pro: needs definition work first)
- next_step: Measure real per-control data-gap counts on the fleet with an aiview count query, then put the NEW literals to the PO one by one. Items 2 and 5 are already closed in code (lane compliance-wiring-1120).
- new_device_command: yes (NEW literals); no for already-wired items ; migration: yes per gate row (seed migration for each approved literal)
- evidence: section 0 of the doc; commit a1e8be58; CheckPointComplianceCatalog and PaloAltoComplianceCatalog versions bumped
- summary: Catalogue of read literals that would close compliance data gaps. Check Point add-lines and Palo Alto SNMPv3, interface and DDNS evidence are wired. The ASA failover and mode reads and the Fortinet reads remain NEW literals awaiting per-literal PO approval.

### CP_REMOTE_SHELL_NORMALIZATION_CONTRACT_2026_11_11.md
- class: PARTLY-DONE
- size: M
- po_decisions: 3
  - Approve the phase 1 literals (three MDS export rows) per literal (recommended; pro: already shipped in 47b4ec8f/642eb4ce, so this just records them; con: none)
  - Approve lab read M2 to learn where vsenv/mdsenv are defined (recommended; pro: unblocks phases 2-3; con: one more device read needing exact-command OK)
  - Re-open phase 2/3 only after lab check, in a revised contract (recommended; pro: Argon verdict was REWORK on the full list; con: R82 upgrade postponed so low urgency)
- next_step: Mark phase 1 as built; run M2 lab read after PO per-literal approval; then rewrite sections 6 and Appendix 1 for phases 2-3.
- new_device_command: yes (M2 read, and every phase 2/3 gate row) ; migration: yes (gate-row migrations)
- evidence: git 47b4ec8f "MDS export: no login shell (phase 1)"; 8e029ada (doc added); 642eb4ce
- summary: Contract to stop wrapping Check Point commands in login shells ahead of R82. Phase 1 (MDS export) is already shipped; phases 2-3 are withdrawn from approval pending a lab check and transport settlement.

### CP_VSX_CONFIG_VS_CONTEXT_FINDINGS_2026_11_20.md
- class: OPEN
- size: M
- po_decisions: 3
  - Approve vsenv 0 / vsenv <VSID> context-switch reads for CP configuration collection, literal by literal (recommended; pro: removes wrong-context evidence on VSX, con: new gate rows and a re-measure)
  - Meanwhile mark VSX configuration evidence UNKNOWN/NOT_EVALUABLE rather than guess the landing context (recommended; pro: fail-closed, con: temporary evidence gap)
  - Reuse the inventory-side bash -lc vsenv wrapping pattern versus the normalization contract's no-shell form (recommended: follow the normalization contract; pro: no double work, con: couples to M2 lab result)
- next_step: PO decision on the command gate proposal; then gate rows plus ConfigurationCapabilityExecutor change. Backlog item cp_vsx_config_collected_without_vs_context is still P3/planned.
- new_device_command: yes ; migration: yes (gate rows)
- evidence: project/QUEUE.md line 58 (cp_vsx_config_collected_without_vs_context planned); docs gate registry has no config vsenv rows per doc
- summary: Findings that all four CP configuration reads run without a VS context on VSX, so evidence may belong to the wrong VS. Proposes gate rows; none exist yet, and nothing is implemented.

### DATABASE_ROLES_AND_DATA_OWNERSHIP_DESIGN_2026_11_14.md
- class: PARTLY-DONE (status lines in the doc are per-step IMPLEMENTED; no overall status)
- size: M
- po_decisions: 4
  - Q3 ui2_packet_analysis keeps SELECT,INSERT on pseudonym_registry (recommended; pro: matches current worker behavior, con: widens one append-only grant)
  - Q4 single-role rollout restart on rotation first, dual-slot later (recommended; pro: simpler, con: brief restart per rotation)
  - Decide the remaining open questions in section 5 (Q1/Q2 not re-read; uncertain)
  - Schedule Step 2 cutover per module, starting with packet-analysis (recommended; pro: smallest blast radius, con: needs HOST-A secret first)
- next_step: Provision per-module DB secrets on the host, then Step 2 cutover (ui2-packet-analysis), module by module. Needs ops work, not new code for step 2.
- new_device_command: no ; migration: no (Step 0 V261 and V238/V242 already shipped; later steps may add small ones)
- evidence: commits 605f48b0 (V238), 0d5115a0 (V242); doc section 6.6 (V261); QUEUE P2/in_progress ui2_database_roles_and_data_ownership
- summary: Multi-step design for per-module Postgres roles. Steps 0 and 1 are built and automated-validated; cutover steps and four PO questions remain. The 2026-11-20 doc is the live tracker, not stale.

### ESTATE_REWIND_AS_OF_DRAFT_2026_11_20.md
- class: PARTLY-DONE
- size: M
- po_decisions: 4
  - Approve compliance_evaluation_history table (recommended; pro: exact past compliance snapshots, con: a migration and storage growth)
  - Keep 24h staleness for parity, surface older_than_7d separately (recommended; pro: matches Failover/Backup reports, con: two notions)
  - Scope to currently enrolled units plus a deleted-units counter (recommended; pro: simple and honest, con: no ghost tiles)
  - Set a retention horizon for inventory/configuration child rows, 180 days (recommended; pro: bounds growth, con: loses older route/interface history)
- next_step: First slice exists (EstateAsOfController/Service/Response). Next: freeze the doc with the four answers, then add Rewind wall and compliance history.
- new_device_command: no ; migration: yes (compliance_evaluation_history, retention)
- evidence: ui2/service/.../api/EstateAsOfController.java, estate/EstateAsOfService.java; commit 5a894b9f mentions Rewind tests
- summary: Design for time-travel views of the estate. The read-only as-of backend slice is built; full Rewind is blocked on four PO decisions and a history table.

### EXEC_OVERVIEW_DESIGN_2026_09_25_ASTRA.md
- class: OPEN (design proposal, partly superseded by the 2026-11-05 executive home; no status header)
- size: M
- po_decisions: 2
  - Mark SUPERSEDED by EXEC_HOME_AND_TRACE_DESIGN_SPEC_2026_11_05 for the home screen (recommended; pro: one authority, con: loses trend/exception ideas)
  - Decide on the new data it needs (owners, due dates, exceptions, daily per-control snapshots) (recommended: defer; pro: avoids new collectors, con: no trend view)
- next_step: Add an explicit status line; extract the per-control-target snapshot idea into backlog if wanted.
- new_device_command: no ; migration: yes (snapshots/register, if pursued)
- evidence: doc says DRAFT design proposal and no state updated; commit b582f9f5 shipped a different spec
- summary: Four-perspective design proposal for an executive Overview. The later 2026-11-05 spec appears to have replaced it. Uncertain which of its trend ideas were taken.

### FAILOVER_FORTIGATE_ASA_DESIGN_DRAFT.md
- class: PARTLY-DONE (Phase 0 and ASA/FortiGate worker code landed; ASA execution still gated by its own DRAFT contract)
- size: M (ASA switch execution) ; FortiGate part L (blocked on per-member HA mgmt addresses)
- po_decisions: 3
  - Mark this doc SUPERSEDED/historical and let FAILOVER_EXECUTION_ASA_CONTRACT_DRAFT_2026_11_18.md carry the live questions (recommended; one authority, con: loses the FortiGate F1 text unless copied over).
  - Q2: can the network team supply each FortiGate member's own HA management address (recommended: ask now; con: FortiGate failover stays dropped until answered).
  - Remaining ASA Q3 (council packet ASA_FAILOVER_Q3_COUNCIL_PACKET_2026_11_23.md) literal approval for the switch command (recommended: run the council, then approve; con: delays).
- next_step: Update status line to point to the ASA contract draft; finish the ASA contract's open Q3, then implement the switch lane. No code is needed for the doc itself. (ASA switch needs a new device write command via the gate.)
- new_device_command: yes (ASA switch literal; FortiGate F1) ; migration: unknown (Phase 0 migration for job modules may already exist; Session-drop delivery value `SESSION_DROPPED_AFTER_SEND` not verified)
- evidence: ui2/worker/.../failover/{AsaFailoverJobExecutor,FgtFailoverJobExecutor}.java; commit 5170995f; docs/design/FAILOVER_EXECUTION_ASA_CONTRACT_DRAFT_2026_11_18.md
- summary: The design's Phase 0/1 work (ASA and FortiGate readiness checks and executors, PO answers Q1-Q7 in the 2026-11-13/14 records) is largely implemented in the Java worker. What remains is ASA/FortiGate switch execution, tracked in the ASA contract draft (DRAFT, DO NOT IMPLEMENT) and queue item asa_failover_execution. Uncertain: exact migration state not checked.

### FAILOVER_READINESS_CHECKS_CP_PAN_DRAFT.md
- class: PARTLY-DONE
- size: M
- po_decisions: 3
  - Q2: which new CP/PAN reads enter the command gate (recommended: only the reads needed for CP-01..CP-07, one at a time; con: slower coverage).
  - Q6: what vendor evidence closes the open field semantics (recommended: official docs plus one attended measurement per read; con: cost).
  - Q5: PAN A/A excluded from the A/P battery (recommended: yes, keep fail-closed).
- next_step: Reconcile against shipped CpReadinessScheduler/PreflightService (queue failover_readiness_assessment, P1) and rewrite the status line to PARTLY-DONE or point it to the readiness contract; remaining new reads each need gate approval.
- new_device_command: yes (any new CP/PAN reads) ; migration: unknown (durable check-result store may already exist; not verified)
- evidence: ui2/service/.../failover/CpReadinessScheduler.java; commits 2004ac57, b0848e12; project/QUEUE.md failover_readiness_assessment
- summary: PO answered the cadence (4h plus fresh pre-failover), all-blocking and VSX questions on 2026-09-27, and a CP readiness scheduler plus VSX/PAN readiness rules shipped. Open items are exact new read approvals and field-semantics evidence. Uncertain: how much of the battery table is implemented.

### M14_LOCAL_LDAP_AUTHORIZATION_ARCHITECTURE.md
- class: PARTLY-DONE / possibly SUPERSEDED (uncertain)
- size: M
- po_decisions: 2
  - Confirm whether ui2 LDAP/SAML/local-account work (migration plan Steps 2-4, LDAP_SERVICE_BIND_DESIGN FROZEN, ldap-adapter module) supersedes this draft; if yes mark SUPERSEDED (yes: Java ui2 auth replaced the Line-1 design; con: D7 group-membership producer semantics may still be unique)
  - If not superseded, freeze the 11 named unknowns in §8 before slice 1
- next_step: Reconcile §7 (frozen M14 row) against ui2 security (GateChain, ldap-adapter, SamlSettingsService); then mark SUPERSEDED or re-scope.
- new_device_command: no ; migration: maybe (§11 DB amendment)
- evidence: ui2/ldap-adapter; docs/design/LDAP_SERVICE_BIND_DESIGN_2026_11_11.md (FROZEN); commits c3c053d6, 815cf700
- summary: 712-line Line-1-era draft. The ui2 product line has since shipped LDAP adapters, SAML and local-account scope under newer frozen designs. No dedicated D7 LDAP group authorization producer was found by grep (uncertain).

### MDS_LOG_EXPORTER_HEALTH_PROPOSAL_2026_10_22.md
- class: OPEN (possibly PARTLY-DONE -- uncertain)
- size: M
- po_decisions: 3
  - Approve the M0 PO-run measurement of cp_log_export status literals (yes: unblocks freeze; con: touches production MDS, post fork storm caution)
  - Decide whether to fold into MDS_SYSTEM_LOG_COLLECTION design (yes: shares MDS-scoped gate rows and measurement)
  - Defer until MDS re-enabled after fork storm acceptance
- next_step: PO decision on M0 measurement; no code until literals approved.
- new_device_command: yes ; migration: yes (health storage, likely)
- evidence: no cp_log_export hits in ui2 source; git log 4f0edefb "Propose MDS Log Exporter health detection"
- summary: Proposal only; no cp_log_export commands in source. Gated behind the MDS fork-storm acceptance and a measurement step.

### OPERATIONS_STATIC_ROUTE_CHANGE_CONTRACT_DRAFT_2026_11_17.md
- class: PARTLY-DONE
- size: M-L (R1/R2 writes)
- po_decisions: 3
  - Update status: decision record ratified 2026-11-18 (status line still says PROPOSED/awaiting) (yes)
  - Resolve remaining §6.2 open decisions
  - Per-stage literal sign-off R1 then R2
- next_step: Status refresh now; continue QUEUE static_route_add_delete (in_progress; R0 read gate and CLI exist). R1/R2 need literal approvals.
- new_device_command: yes (R1/R2 writes) ; migration: probably yes
- evidence: PO_DECISION_RECORD_2026_11_18_STATIC_ROUTE_CONFIGURATION_WRITES.md (RATIFIED); ui2/cli/.../StaticRouteCli.java; ui2/service/.../staticroute/; StaticRouteR0GateTest
- summary: Companion decision record is RATIFIED and R0 read machinery is in source, so this draft's header (awaiting ratification) is stale, while write phases remain open. Work is in progress per QUEUE.

### PAN_CHANGE_ACTOR_FROM_PANORAMA_CONFIG_LOG_2026_11_11.md
- class: PARTLY-DONE (phase 1 built and measured; D1-D7 ratified; join gap open)
- size: M
- po_decisions: 2
  - D8 narrower literal (section 7.4): recommend do not ask now; pro: no new command, con: volume unknown until telemetry read
  - Whether to freeze the design after the second measurement settles section 8 UNKNOWNs (Submitted vs Succeeded commit anchor, rule-path form): recommend wait for telemetry
- next_step: Read the new counts-only path-kind and commit-shape telemetry on the trial Panorama, then fix the commit anchor (backlog item pan_change_actor_submitted_join_gap). Phase 2 (direct firewalls) only after.
- new_device_command: no (uses three signed-off literals) ; migration: possibly (anchor change; V213 already exists)
- evidence: commits f71d264e, 42ad066e, 00cc9ccb, 1d139923; project/QUEUE.md pan_change_actor_submitted_join_gap
- summary: Phase 1 shipped under decision record 2026-11-12. The first measurement showed 0 matched changes because commits log as Submitted and no path matched the rule form. The design correctly stays DRAFT until section 8 is settled.

### PAN_OS12_COMMAND_ALTERNATIVES_2026_10_21.md
- class: PARTLY-DONE (GR-1, check-7 HA source, GR-7 and GR-4 approved and implemented; GR-2/3/5/6/8 and Q1 open)
- size: M
- po_decisions: 3
  - Q1 source of the PAN-OS 12 defect claim: recommend ask for the TAC/PAN-ID; pro: decides whether remaining rows are needed, con: PO must find it
  - GR-3 Panorama show devices all for uptime/family, and GR-5/6/8 conditional rows: recommend leave unapproved (not needed now); pro: no new commands, con: uptime/family stay UNKNOWN on 12+
  - Q6 check 6 policy if session meter unusable: recommend UNKNOWN-blocks stays; pro: fail-closed, con: failover blocked on 12+
- next_step: Mark done rows in a table, close the doc as SUPERSEDED by the 2026-10-21 and 2026-10-24 records for the approved rows, park the rest pending Q1.
- new_device_command: no for the approved rows; yes for GR-3/5/6/8 if ever approved ; migration: no for parking
- evidence: PO_DECISION_RECORD_2026_10_21_PAN_TYPE_VERSION_AND_CHECK7.md; PO_DECISION_RECORD_2026_10_24_PAN_OS12_REPLACEMENT_READS.md; V179
- summary: The header already records the approvals and implementation of four of the candidates. The remaining candidates are explicitly unapproved and conditional on measurements. Status stays DRAFT only for those.

### POLICY_UNKNOWN_REDUCTION_PLAN_2026_11_05.md
- class: PARTLY-DONE
- size: M (remaining per-class items N1, P2, S1 etc.)
- po_decisions: 2
  - Read the CP/PAN official pages in full before freezing N1/P2/S1 (recommended; pro: meets the vendor-semantics law; con: doc work)
  - Accept the live X0 split re-measure (counts only, aiview) before choosing the next extension (recommended; pro: evidence-ranked; con: needs live run)
- next_step: Re-measure the shadow UNKNOWN share via aiview; ship the next ranked item from section 3 inside frozen contracts (recent commit 659787cb already did negated cells, PAN source ports, CP ICMP codes).
- new_device_command: no ; migration: no
- evidence: commit 659787cb (Shadowing: negated address cells, PAN source ports, CP ICMP codes resolve); PolicyHygiene.java; doc line 100 "IMPLEMENTED (pending live measurement)"
- summary: Analysis plus a ranked plan; several extensions are implemented and the order-dependence open issue is marked resolved 2026-11-06. Remaining items need full-text vendor reading before freeze. Status line is partly stale.

### SPIKE_LISTING_CHUNKING_PROPOSAL.md
- class: PARTLY-DONE (option A implemented; option B still DRAFT/direction only; C rejected)
- size: M (option B: collector change plus contract amendment plus gate string; one real-env check first)
- po_decisions: 3
  - Approve C4t string (`ls -t` newest-first, bounded prefix read) for gate: yes, once the Gaia `-t` check is done; pro: durable fix, con: new command literal needs the 2026-09-26 literal approval
  - Approve amending the "never processed partially" rule for this row only: yes with a completeness watermark; pro: removes growth cliff, con: weakens a fail-closed rule
  - Schedule B at all now or wait for directory growth to re-trigger UNKNOWN: wait (recommended); pro: no device work now, con: baseline stays fragile past ~1 MiB
- next_step: Record count/size class of the first successful 1 MiB listing; only if growth recurs, request PO review of the `-t` run and C4t literal.
- new_device_command: yes (for B only) ; migration: yes (gate row update, for B)
- evidence: commit 9beef3b2 (V173 1 MiB bound); PO_DECISION_RECORD_2026_10_21_SPIKE_LISTING_BOUND.md
- summary: Option A (1 MiB bound) is live via V173 and the status line says so. Option B is approved as direction only and blocked on a real-environment `-t` check and PO literal approval. Doc is a partly-done proposal, not stale.

### ACI_SEGMENTATION_SUPPORT_DESIGN_2026_11_13.md
- class: PARTLY-DONE
- size: L (steps 3-5: APIC reads, redirect overlay, contracts-based scope; step 2b optional)
- po_decisions: 3
  - Approve the APIC read literals R0-R13 one by one, only after the MIM check and measurement records (recommended: defer until the topology-sources lane asks; pro: keeps device-command law intact; con: blocks steps 3-5)
  - Decide counter reads (step 2b) after the first real cutover (recommended: yes, wait; pro: avoids a new literal per vendor; con: slower first-hours view)
  - Re-label status: split into a FROZEN step-1 record plus a DRAFT remainder (recommended: yes; pro: stops a DRAFT line hiding shipped code; con: one more doc edit)
- next_step: Mark section 7 rows 1, 1v-prep and 2a as shipped in the status line; keep the doc DRAFT for steps 3-5 and hand them to the topology-sources lane (queue: aci_segmentation_step1, topology_sources_apic_citrix_f5).
- new_device_command: yes (steps 3 and 2b only) ; migration: yes (steps 3-5 storage)
- evidence: section 0 and section 7 of the doc (step 1 on V212, /api/v2/cutover/**, Q9 Trace caveat done); commit 3ea02652; project/QUEUE.md aci_segmentation_step1 in_progress
- summary: Capability 1 (pre-cutover impact analysis) and the Q9 Trace caveat are implemented, and ASA/FortiGate targets now go through the same lookup. Capabilities 2-3 (cutover watch beyond 2a, APIC read job, redirect overlay) are not built and need per-literal PO approval. The status line still reads plain DRAFT.

### AIVIEW_MASKED_ADMIN_READ_DESIGN_2026_11_14.md
- class: PARTLY-DONE (slice 1 of the later 11-20 note shipped; the full design is not) and overlaps with the next doc
- size: L (about 16 Administration tabs plus Operations double-mask fixes, canary leak suite)
- po_decisions: 4
  - Q1 credentials: masked posture rows, not counts-only (pro: agents can verify the table; con: more fields to leak-test)
  - Q2 principal HMAC: reuse the shared IdentityTokens key (pro: USR- tokens join across screens; con: key change would re-token everywhere)
  - Q3 seeded accounts: mask all human accounts including the root admin, keep system:/migration:/bootstrap: verbatim (pro: no name leakage; con: aiview personas not recognisable)
  - Q4 delivery-plan tab: unlock for aiview with the rest of Administration (pro: backend already open; con: none noted)
- next_step: Merge this doc with AIVIEW_MASKED_READONLY_ADMIN_DRAFT_2026_11_20.md into one candidate for freeze (PO decides which is canonical), then slice 2: split SESSION_READ and ROLE_BINDING_READ from the write actions and add AdminMaskedResponse. No device command.
- new_device_command: no ; migration: no (UNKNOWN: a new read-only role grant could need a migration if grants are seeded; not checked)
- evidence: commit 26330958 (slice 1, UI2_AIVIEW_READONLY_ADMIN switch, default off); ActionRegistry.java; project/QUEUE.md aiview_masked_readonly_admin in_progress
- summary: Target architecture for a masked read-only Administration projection for aiview. Only slice 1 (three settings reads behind an off-by-default switch) is shipped. The four PO questions and the freeze are still open.

### ANALYSIS_CONTAINER_DESIGN_2026_11_05.md
- class: OPEN
- size: L
- po_decisions: 5
  - Q1 name: keep ui2-analysis as directed (pro: matches the decision record; con: clashes with Packet Analysis wording, R13)
  - Q2 LocalFirewallPolicyService: option (a) move it with a read-only PVC mount, revisit after costing (c) (pro: smallest change; con: second pod holds the artefact key)
  - Q3 narrower DB role ui2_analysis: do it after phase 5 (pro: fewer migrations early; con: wider grants meanwhile)
  - Q4 shadow phase: skip the 2 percent double computation and rely on the offline parity test (pro: no extra load on the shared node; con: less real-traffic evidence)
  - Q5 process: treat this draft as input to the Argon design step, not a replacement (pro: honours the decision record order; con: one more review round)
- next_step: Run the live measurements M1-M11 that are still open (M2-M5, M9 follow-up, M11) on HOST-A under the existing authorizations, then PO answers Q1-Q5 and the doc goes to Argon review. Nothing is built; no deploy/ui2/59-analysis manifest exists.
- new_device_command: no ; migration: yes (ui2_analysis DB role grant, if Q3 is taken early)
- evidence: project/QUEUE.md analysis_container planned; no analysis deployment manifest in deploy/ui2 (only packet-analysis); commits 79e9e0bb, fb5fc3f6
- summary: Architecture proposal to split policy and path analysis out of ui2-service into its own pod with one masking authority. Marked DRAFT-DO NOT IMPLEMENT, queue item still planned, and measurements are partial. It is genuinely open.

### ASA_FAILOVER_Q3_COUNCIL_PACKET_2026_11_23.md
- class: OPEN (council input; correctly DRAFT)
- size: L for the build behind it (S for the council step itself)
- po_decisions: 3
  - Convene the nexus-decision-council from a nexus-po episode on D1-D9 (recommended: yes; pro: required by decision record item 6; con: one more gate before the pilot)
  - Accept Option A (new SESSION_DROPPED_AFTER_SEND delivery state plus two-sided serial-pair confirmation) as the council's starting proposal (recommended: yes, already approved in principle 2026-11-23)
  - Pilot pair and window (Q7) (recommended: later, not a freeze blocker)
- next_step: A nexus-po episode convenes the council on this packet and writes the verdict; then the code-review plugin on the implementing diff; then freeze FAILOVER_EXECUTION_ASA_CONTRACT_DRAFT_2026_11_18.md. An engineering session must not run the council.
- new_device_command: yes (asa_failover_inactive_switch write, class 2, is already in the contract draft) ; migration: yes (failover_dispatch_intent delivery enum widening)
- evidence: FailoverVendor.java CISCO_ASA available=false (cited F14); commit 5170995f; project/QUEUE.md asa_failover_execution planned
- summary: Prepares the council on how a session-dropping ASA write is recorded and confirmed. No ASA write path exists yet and the ASA vendor is still unavailable in code. Needs a PO-side council episode, not engineering work.

### DEVICE_SYSTEM_LOG_PROGRAMME_CONTRACT.md
- class: PARTLY-DONE
- size: L
- po_decisions: 3
  - Approve each frozen read literal in section 3 after measurements M1/R1 (recommended; pro: required by gate, con: one approval per literal per vendor)
  - Freeze the contract after phase A and record the status line honestly (recommended; pro: removes DRAFT ambiguity, con: needs measurement record complete)
  - Per-vendor next step (FortiGate/ASA sources researched from public docs) (recommended: after CP and PAN land)
- next_step: Check what of phase A measurements (M5 recorded 2026-10-16, R1 2026-10-17) closed, freeze CP read set, then continue phase B. Retention (30d/180d), 6h interval and PAN config logs already answered.
- new_device_command: yes ; migration: yes (log pool/index storage)
- evidence: ui2/job-engine/src/test/java/com/securityexpert/nexus/ui2/jobs/systemlog/CpSystemLogCollectTest.java; doc section 4.1/4.2 measurement records
- summary: Programme to pool per-device system logs. CP collection code and tests exist, so the DRAFT status is partly stale; PAN and later vendors and the freeze are open. Uncertain how much of PAN is built (not checked).

### FAILOVER_EXECUTION_ASA_CONTRACT_DRAFT_2026_11_18.md
- class: OPEN (all but Q3 council, review and freeze answered)
- size: L
- po_decisions: 2
  - Freeze contract after Q3 council and code review complete (recommended; pro: unlocks implementation, con: CLASS_2 write needs the full gate)
  - Choose the pilot pair and window later (recommended: not a freeze blocker; blocks only first production run)
- next_step: Run Q3 decision council and code review (ASA_FAILOVER_Q3_COUNCIL_PACKET_2026_11_23.md), then PO freeze, then gate row for the write literal.
- new_device_command: yes (write literal "no failover active", CLASS_2) ; migration: yes (gate row, SESSION_DROPPED_AFTER_SEND state)
- evidence: commit 5170995f (Q3/Q4/Q6/Q7 recorded); project/QUEUE.md line 27 (asa_failover_execution planned)
- summary: Contract for an ASA active-unit switchover. Q1, Q2, Q4, Q5, Q6 are answered; the contract stays DRAFT until the council and code review finish and the PO freezes it. Nothing is implemented.

### LOAD_BALANCER_ARCHITECTURE_2026_11_05.md
- class: OPEN
- size: L
- po_decisions: 3
  - Keep DRAFT until a first F5 or Citrix read slice is chosen (yes: no code exists; con: doc ages)
  - Pick vendor order, F5 first (yes: REST read API simpler; con: Citrix may be the larger estate share -- UNKNOWN)
  - Approve exact read literals per vendor via command gate (required before any code)
- next_step: PO picks first vendor and slice; engineering drafts gate entries for approval. Note topology_sources_apic_citrix_f5 (QUEUE, in_progress) is related topology work, not this architecture.
- new_device_command: yes ; migration: yes (LB pseudonym kind, tables)
- evidence: git grep for BigIp/NetScaler in ui2 main finds nothing; project/QUEUE.md topology_sources_apic_citrix_f5
- summary: Architecture proposal with no implementation: no BIG-IP/Citrix collectors or LB pseudonym kind found in ui2 source. Everything needs gate approvals and migrations first.

### MDS_SYSTEM_LOG_COLLECTION_DESIGN_2026_11_11.md
- class: OPEN
- size: L
- po_decisions: 3
  - Approve one-MDS measurement of §11 literals (yes: needed before freeze; con: MDS just had incident)
  - Approve MDS-scoped gate rows (per literal)
  - Decide the PO switch enabling MDS targets (DEVICE_SYSTEM_LOG_PHASE_B_CONTRACT §2.3; default off)
- next_step: Wait for fork-storm acceptance (§F), then PO measurement run; then freeze and slice.
- new_device_command: yes ; migration: yes
- evidence: SystemLogCollectionService.java:70 (management servers never listed); docs/design/ARGON_MDS_SYSTEM_LOG_COLLECTION_REVIEW_2026_11_11.md
- summary: SystemLogCollectionService still targets gateways only and explicitly excludes MDS/Panorama. Design was Argon-reviewed but remains DRAFT with new commands unapproved.

### OPERATIONS_POLICY_INSTALL_CONTRACT_DRAFT_2026_11_17.md
- class: PARTLY-DONE
- size: L (P2-P4)
- po_decisions: 3
  - Keep doc as P2-P4 draft, P1 pointer already in place (yes)
  - Amend AGENTS.md class 4 policy-deployment boundary for P2-P4 (needs PO constitutional amendment; high risk)
  - Sequence: P2 verify-before-install before any write
- next_step: P1 done; QUEUE has policy_install_p2_p4 (P2/planned). Next is PO decision on class-4 amendment plus a P2 frozen contract.
- new_device_command: yes (P2-P4) ; migration: yes
- evidence: OPERATIONS_POLICY_INSTALL_P1_MONITORING_CONTRACT_2026_11_18.md (FROZEN); ui2/frontend/src/estate/EstatePolicyInstalls.tsx; project/QUEUE.md:45
- summary: P1 monitoring is frozen and shipped (Estate policy installs screen); status line already says P2-P4 remain DRAFT. This is accurate, not stale.

### PAN_APPLICATION_DEFAULT_PLAN_2026_11_05.md
- class: PARTLY-DONE (opaque-token handling shipped; disjointness proof via predefined-application table not built)
- size: L (new read command, parser, storage, gate row, contract freeze, real-env count)
- po_decisions: 3
  - Approve or refuse the predefined-application read command (section 2): recommend defer until PO wants the ~2,447 rules out of UNKNOWN; pro: new vendor read, con: needs gate and freeze
  - Pre-identification question (rule can match before App-ID): recommend refuse the feature unless the vendor docs prove it; pro: fail-closed, con: feature may stay off
  - Freeze contract after the two above: recommend yes only if both resolve favourably
- next_step: PO ruling on the command and pre-identification risk; otherwise leave parked.
- new_device_command: yes ; migration: yes (table storage, gate row)
- evidence: commits cf70b966, 4193fd44; PolicyHygieneApplicationDefaultTest.java
- summary: Today application-default is an opaque token yielding DEFAULT_PORTS_UNKNOWN. The plan to prove disjointness needs a new read of the content app table, which needs a PO command approval and a freeze. Nothing in the proof path is built.

### PATH_ANALYSIS_PHASE_B_ARCHITECTURE_2026_11_05.md
- class: OPEN (backlog path_analysis_phase_b, P2/planned)
- size: L (nine items B1-B9; B1 UDP/ICMP partly proposed in PATH_ANALYSIS_A6_UDP_PROPOSAL.md)
- po_decisions: 6
  - P-B1 record D-9 (F5/Citrix) by amending the 2026-11-03 decision record (recommended; pro: closes a citation gap; con: tiny doc edit)
  - P-B2 batch audit one authz row per batch with per-row verdict codes (recommended; pro: bounded audit volume; con: departs from D-2 as written)
  - P-B3 allow owner-scoped, masked persisted flow sets (recommended; pro: needed for B7 reports; con: new storage)
  - P-B4 no aiview in What-if, no export in B (recommended; pro: keeps the raw-value boundary; con: less demo-able)
  - P-B5 freeze return-gateway-candidate rule as written in B2 (recommended; pro: unblocks B2; con: vendor semantics still partly UNKNOWN)
  - P-B6 command gate for CP out-of-state read, PAN ICMP matcher literal, LB reads, each literal separately after B0 (recommended; pro: follows the gate; con: slow)
- next_step: Take P-B1/P-B5, then split a first lane B0/B1 (UDP/ICMP) that needs only a frozen contract and no new command. B2-B8 later.
- new_device_command: yes (B1 ICMP/UDP, B2, B5 LB reads need gate entries) ; migration: yes (B3 persisted flow sets, B7)
- evidence: project/QUEUE.md line 42 (path_analysis_phase_b planned); pathanalysis/ has engine/query/topology/validation only, no batch/what-if
- summary: A real Phase B plan, still unfrozen, with six PO questions listed in section 4 and only reserved node kinds in code. Nothing in B1-B9 appears implemented; sizing is by item and the whole is L. Uncertainty: B1 UDP proposal doc exists and may be partly gated.

### RECOVERY_OFFHOST_AND_KEY_CUSTODY_DESIGN_2026_11_14.md
- class: OPEN (backlog recovery_offhost_key_custody, P1, "Next" in QUEUE) with PARTLY-DONE step S1 (backup script)
- size: L (new credential boundary, key custody, host services; steps S0..S2+)
- po_decisions: 14
  - Q1 internal S3-compatible store with Object Lock vs SFTP fallback (recommended: S3 with Object Lock; pro: immutability; con: needs bank storage team)
  - Q2 bucket owner and chargeback (recommended: name a storage-team contact; pro: unblocks Q8)
  - Q3 target in a different failure domain from HOST-A hypervisor (recommended: yes required)
  - Q4 compliance-mode retention with lock-extension rights separated (recommended: separate retention account; con: extra account)
  - Q5 SFTP fallback snapshot regime and who controls deletion
  - Q6 initial capacity ~3 TB (recommended: accept, revise after S0)
  - Q7 retention 35 daily / 12 month-end / lock 35 d (recommended: accept unless bank policy longer)
  - Q8 firewall rule HOST-A to target (443/22) incl. replacement host and restore workstation
  - Q9 reachability without proxy and signing CA
  - Q10 age (X25519) accepted by bank crypto standard vs FIPS/HSM (recommended: ask now; con: could force redesign)
  - Q11 custodian roles R-OPS x2, R-ESC x3 and whether a bank HSM/PAM vault holds R-ESC
  - Q12 passphrase identity vs hardware token for R-OPS
  - Q13 bank-approved 2-of-3 split tool
  - Q14 may the agent destroy the HOST-A age identity after the first two-recipient drill (recommended: yes after drill; con: irreversible, ask first)
  (section 8 has further Operations questions beyond Q14 that I did not count; count is a lower bound)
- next_step: Send the section 8 questionnaire to the bank; in parallel finish S1 host roll (already merged: platform backup script step S1) and draft the FROZEN contract after answers. Needs a frozen contract before any off-host code.
- new_device_command: no ; migration: possibly (custody/recovery metadata; unconfirmed)
- evidence: project/QUEUE.md lines 7 and 97; commit 3557d707 (backup script S1: archive incl. globals, secrets, host keys); commit 8c24f25f (closes superseded off-host item, adds cyberark custody)
- summary: Architecture proposal; blocked on bank answers, not on engineering. Local encrypted archive (S1) shipped, off-host copy and escrow custody are not. A cyberark_archive_key_custody item (P1 planned) overlaps and may supersede parts of the custody model.

### REPORTS_R2_CONTRACT.md
- class: OPEN (backlog reports_r2, P2/planned); PARTLY-NOT-DRAFT note: section 11.1 records engineer decisions D1-D4 made, but status still DRAFT
- size: L (schedules, e-mail delivery, 5 reports RPT-06..10, phased R2a-c)
- po_decisions: 1
  - Freeze the contract (change status to FROZEN) so R2a may start, with D1-D4 as already decided by engineer delegation (recommended; pro: unblocks a fully specified plan; con: commits SMTP multipart and a scheduler bean, which is a new delivery boundary)
- next_step: PO freeze, then R2a (schema, scheduler, run-now). ReportTemplate.java still lists only RPT-01/02/03/05, confirming nothing of R2 is built.
- new_device_command: no ; migration: yes (new schedule/run tables, section 3.1 SQL)
- evidence: ui2/service/.../report/ReportTemplate.java (R1 catalogue only); V190__reports_r1_report_run.sql is the last reports migration; project/QUEUE.md line 47
- summary: Fully specified R2 contract, explicitly "DO NOT IMPLEMENT until FROZEN". No implementation exists on this branch. Only the PO freeze is outstanding, since design decisions D1-D4 were taken by the engineer.

### RULE_RECERTIFICATION_CONTRACT.md
- class: OPEN (backlog rule_recertification_campaigns P1 in_progress; recertification_council P1 planned)
- size: L
- po_decisions: 8
  - Q2 owner authority: new narrow directory-group role vs decisions by existing admin roles (council question; recommend the narrow role scoped to own items; con: new role plus group-set validity handling)
  - Q3 four-eyes scope and whether one approver must be compliance_admin (recommend yes, mirroring zone matrix publishing)
  - Q7 owner liveness signal for directory principals (recommend "not signed in for N days" with escalation, not auto-close)
  - Q8 shared rules: one decision per policy source vs per rule uid occurrence (recommend per policy source)
  - Q9 which rule field changes invalidate a decision / objectsHash scope (recommend widening-only changes; con: harder to compute)
  - Q20 definition of internet-facing
  - Q21 Cisco ASA and FortiGate scope in v1
  - plus remaining 14.3 questions and two items awaiting bank input (14.2); count is a lower bound because I read only part of section 14.3
- next_step: Run the three-seat decision council (PO session only, not engineering), then PO freeze, then implementation. No Java/SQL for recertification exists in ui2 (grep found none).
- new_device_command: no ; migration: yes (campaign/decision/evidence tables)
- evidence: commits 3775de2e, b37415b7, 529776a9 (contract drafts and PO answers only); project/QUEUE.md lines 13 and 18; no recertif hits in ui2 Java/SQL
- summary: Revision 2.1 contract waiting for the decision council and a PO freeze; the PO answers of 2026-11-14 and 2026-11-23 are folded in. Nothing is implemented. The backlog "in_progress" label reflects design work only.

### TOPOLOGY_SOURCES_DESIGN_2026_11_14.md
- class: PARTLY-DONE (PO answers recorded; implementation in progress; section 4 freeze T1 pending)
- size: L
- po_decisions: 3
  - Freeze section 4 path semantics (T1) and amend PATH_ANALYSIS_DESIGN.md: freeze after one more review (recommended); pro: unblocks ACI/F5/ADC caveats, con: freezes semantics with UNKNOWNs still carried as caveats
  - Approve each exact read literal for APIC, Citrix ADC NITRO and F5 tmsh individually: per-literal approval (required by 2026-09-26 amendment); pro: safe, con: serial PO load
  - Order of sources (ACI first, then F5, then ADC): ACI first; pro: step 1 already merged, con: F5/ADC wait
- next_step: Freeze T1 section 4, then request per-literal approval for the next source read; ACI step 1 (impact analysis backend) already in progress.
- new_device_command: yes ; migration: yes (likely, for stored topology-source facts; UNCERTAIN)
- evidence: project/QUEUE.md:24 (topology_sources_apic_citrix_f5 in_progress); commit 43543003 (decisions recorded); b900d622 (ACI step 1)
- summary: Design-only doc whose open questions were answered by a 2026-11-14 PO decision record; the backlog item is in progress. It stays DRAFT until T1. Large because it adds three vendor read sources.

### UI2_BACKUP_PLANE_SEPARATION_CONTRACT.md
- class: PARTLY-DONE (UNCERTAIN: the contract is still DRAFT but a backup pod is deployed)
- size: L (the freeze and contract tail), n/a for the pod
- po_decisions: 5
  - D-B1: backup owns its manifest tables via its own DB role. Pro: strongest boundary. Con: larger change.
  - D-B2: quota enforcement. Recommend a filesystem or storage-class quota. Pro: bounds consumption. Con: host/cluster scope, outside agent authorisation.
  - D-B3: a separate PAN-OS backup account, with the inventory account denied export device-state. Pro: least privilege. Con: needs a new credential.
  - D-B4: forwarding shape. Recommend the ui2-service proxy. Pro: one authenticated edge. Con: an extra hop.
  - D-B5: migration of existing artefacts from the shared store. Open as written.
- next_step: PO answers D-B1..D-B5 (a decision council is a candidate). Then freeze and reconcile with the 2026-11-23 backup module pod cutover (deploy/ui2/53-backup-deployment.yaml, MODULE_PER_POD_PLAN.md), which may already supersede parts of this. QUEUE has P2 backup_plane_separate_service.
- new_device_command: no ; migration: yes (manifest ownership and artefact move)
- evidence: deploy/ui2/53-backup-deployment.yaml; project/QUEUE.md:30; git 71f29df2
- summary: The draft contract was written for a separate ui2-backup service and lists five open decisions. A backup module pod now exists under the module-per-pod plan, so the draft's section 2 baseline "no ui2-backup workload" is stale. Whether the decisions were resolved elsewhere is UNVERIFIED.

### FIREWALL_POLICY_VIEW_DRAFT.md
- class: PARTLY-DONE (largely superseded in practice by shipped Policy & Objects/policy browser and collection plans)
- size: n/a for the doc; any remainder L
- po_decisions: 2
  - Mark SUPERSEDED by the later policy contracts (PO_DECISION_RECORD_2026_10_01_CP/PAN_POLICY_READS, 2026_10_18_POLICY_COMPLETENESS, 2026_11_24_POLICY_INSIGHTS) and close its 10 open questions as answered-by-successors (recommended; con: need to confirm each of Q1-Q10 is truly answered).
  - Q10 Radware policy scope and ASA multi-context deferral (recommended: keep deferred; con: coverage gap).
- next_step: A docs lane to map Q1-Q10 to the answering decision records and set the status line; no code.
- new_device_command: no ; migration: no
- evidence: ui2/frontend/src/estate/estatePolicyBrowserModel.ts; docs/design/PO_DECISION_RECORD_2026_10_01_CP_POLICY_READS.md; commit 1588f36f
- summary: The 2026-10-01 design for a read-only Policy & Objects view. CP/PAN policy collection, ASA/FortiGate policy model and a policy browser have shipped under later records. Uncertain which of its 10 questions remain unanswered; not verified line by line.

### UI2_0_FAILOVER_ENGINE_ARCHITECTURE.md
- class: PARTLY-DONE (Phase A implemented and ratified; B/C/D roadmap)
- size: n/a for this doc (the later phases live in newer failover contracts)
- po_decisions: 1
  - Split the status line into per-phase states and point at FAILOVER_EXECUTION_CP_CONTRACT.md. Con: touches a ratified header.
- next_step: Update the status line. Phases B-D are already carried by later contracts (CP failover executors exist in ui2/worker/.../failover/, VSX chassis ratified 2026-11-19). This doc is historical context only.
- new_device_command: no ; migration: no
- evidence: ui2/worker/src/main/java/com/securityexpert/nexus/ui2/worker/failover/CpFailoverJobExecutor.java and AsaFailoverJobExecutor.java; PO_DECISION_RECORD_2026_11_19_VSX_CHASSIS_FAILOVER.md
- summary: Status line says Phase A implemented and ratified, with B-D as DRAFT/roadmap. The code has since gone far past B-D, so the roadmap wording is stale. Likely a DRAFT-matched-incidentally doc that needs a header refresh.

