# Java Project Plan reconciliation — NXS-LOCAL-0242

Status: **DRAFT — implementation record; source-publication proposal not deployed.**

## Authority and observed disagreement

The FROZEN `UI2_0_BASELINE_CONTRACT.md` §1 makes Java the sole development
line and keeps Python as reference/maintenance. The FROZEN
`UI2_0_ARCHITECTURE_CONTRACT.md` distinguishes settled/built, settled/unbuilt
and open decisions; freezing a contract is not delivery evidence.

The old screen calculated completion across Python tracks, displayed M12 as
NEXT, and mixed agent-operation builds into product history. The Java umbrella
feature still said planned although current build history records Java
implementation. This movement corrects that umbrella to in_progress and adds
explicit Java criteria. It preserves every earlier outcome and the historical
track definitions. No legacy completion percentage becomes Java completion.

The dispatch's NetworkPolicy baseline sentence is unrelated to its objective;
it supplies no deployment work authorization. The explicit dispatch instruction
to open a PR overrides its copied “PO opens” wording; merge remains orchestrator-owned.

## Reconciliation and ownership

- `project/java_product_plan.json`: Java track membership, explicit active
  backlog classifications, product-build selection, converted-lesson provenance,
  review date/build and Java sequencing. This is product-view metadata, not a
  replacement for delivery status authorities or a database migration.
- `project/feature_registry.json`: each Java feature's delivery status and
  criteria. Five existing Java areas have recorded automated delivery; their
  separate acceptance criteria remain pending. This projection itself remains
  in_progress until its checks run. Restore is blocked; scheduling deferred;
  REL-CHECKS and REL-FAILOVER-READINESS retain planned Java delivery.
- `project/backlog.json` and its terminal archive: original statuses, priorities
  and titles remain authoritative. Classifications select ten Java feature
  items and 29 Java technical-debt/security/release gates. Seven Line-1 reference
  items and seven agent-operation items are excluded. Governance-labelled
  product guards (contract shape and device-role consistency) remain product
  debt because they affect product delivery. No classification marks a debt fixed.
- Three converted Python lessons retain source IDs, source statuses and Java
  application/target: opaque identity, credentials outside URLs, and off-host
  recovery custody. Their historical outcomes never become Java acceptance.
- `project/build_history.json` and `roadmap.current_build`: current recorded
  movement. The newest explicitly selected Java build is also shown. Neither
  identifies the deployed binary; that version remains UNKNOWN.
- The original `roadmap.tracks`, `current_track` and legacy NEXT are retained as
  historical Line-1 planning. They do not drive the Java product projection.
  Java NEXT is unassigned pending PO sequencing; no new scheduling decision is made.
- `CURRENT_STATE.md`: concise projection of these records. Queue/current-build
  writes go through `scripts/project_queue.py`, including generated history/index.

Unclassified new active backlog rows are excluded and warned about. Terminal
rows default to historical reference unless explicitly selected. Missing or
invalid product metadata yields an empty product selection with warnings,
never a fallback to Python completion. Missing referenced sources are warnings.
Broad B-series queue rows remain at their recorded status even where later
narrower builds exist: their acceptance closure requires a separate review.

## Freshness and controlled source updates

Implemented: each request rereads the configured directory; a SHA-256 content
revision covers the six source files and displayed archive count. HTTP responses
are no-store and the screen offers Refresh. The source review build is compared
with the current recorded movement: mismatch is STALE, missing/invalid metadata
is UNAVAILABLE, otherwise freshness is UNKNOWN because local agreement cannot
prove upstream freshness. Response generation time is never source freshness.

**Proposed publication procedure, not executed or approved by this record:**

1. Review one complete project snapshot from an approved repository revision,
   including java_product_plan.json; run queue, projection and privacy gates.
   Do not publish an arbitrary checkout, credential files or runtime evidence.
2. Use the existing `ui2.project-plan.directory` configuration to select an
   immutable, read-only snapshot mounted to the service. An immutable versioned
   ConfigMap is suitable only if the full snapshot fits the platform size limit;
   otherwise use a reviewed immutable data artifact through the approved build
   path. Never patch source files one at a time in a running service.
3. A separately authorized deployment change selects the snapshot and rolls the
   service, then verifies the returned content revision. Rollback selects the
   previous snapshot. Snapshot refresh does not require changing application code.

This reuses non-secret configuration and read-only mounts under the FROZEN
`UI2_0_B1_01C_CONTAINER_IMAGE_AND_KUBERNETES_DEPLOYMENT_CONTRACT.md` §3/§5.
It adds no Git polling, API write route, host tooling, credential or runtime
write permission. Actual manifest wiring, publisher custody, rollout and mounted
snapshot validation remain pending deployment work. Image-baked sources remain
possible and are explicitly not advertised as fresh.

## Validation and next action

- PASS: project queue consistency; repository privacy gate (zero findings);
  whitespace diff check and JSON provenance/selection checks.
- UNVERIFIED: Java ProjectPlan tests could not start: Gradle wrapper lock in
  the user cache was denied by the filesystem sandbox.
- UNVERIFIED: frontend ProjectPlan tests could not start: vitest is absent from
  this worktree's frontend dependencies. No runtime bootstrap was performed.
- Added checks cover Java-only selection, unknown classifications, missing or
  malformed product metadata, source revision changes, stale review metadata,
  no-store responses, historical lesson labels, refresh, and validated-but-open debt.
- Full regression, HTML render harness and real-environment validation: NOT RUN.
  This movement makes no deployment, host/device access or production change.

Next movement: VALIDATION, standard implementation/debugging reasoning. Run the
Java ProjectPlan tests and frontend ProjectPlan tests/build in the approved
runtime, then applicable regression and privacy gates. Only then advance this
movement to automated_validated. The orchestrator owns merge verification.
