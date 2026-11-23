# Deploy-freeze Phase 1 rollout

Status: IMPLEMENTED in source, not deployed. PO approval 2026-11-23 (Phase 1 only; the overlapping-handover draft
`docs/design/MODULE_OVERLAPPING_HANDOVER_CONTRACT_DRAFT_2026_11_23.md` stays unapproved and unimplemented).
Authority: `docs/design/MODULE_POD_RUNTIME_CONTRACT.md` (FROZEN) §2 (admission wait, checkpoint before yield) and §3
(drain, bounded wait, audited drain release).

## What changes

1. Window-preserving requeue (V263, additive): a job claimed inside its window and then deferred by a drain or
   admission contention stays claimable after the window ends until
   `least(greatest(window end, defer time) + 30 min, window end + 2 h)`: a scheduled backup (02:00-02:59) is never
   claimed after 05:00, a polling job never reaches the next six-hour slot. Never-claimed jobs keep the window rule.
   Only a claim through an active grace keeps the recorded window end; any other claim records the current one.
2. Yield hardening: a Check Point domain that published a change between a drain yield and the resume is read
   again (`RESUMED_SIGNAL_CHANGED_RECOLLECT`) instead of failing the run. Inventory (per VSX virtual system),
   discovery (per management domain) and configuration (before the long read, per FortiGate section) jobs yield
   at those boundaries while their module drains. Backups, failover, writes, diagnostics and scripts never yield.
3. `module_deploy.py`: a module that owns itself gets a routine replacement (drain, replace, lift the drain; no
   generation, owner or fallback change; on failure it is fenced, never returned to general). Ownership transfer
   needs `--ownership-transfer --authorization-ref`; otherwise a module still on the fallback is skipped.
   Drain-wait expiry: see "Drain timeout and mixed images" below. Worker and policy drains wait at most
   `--yield-wait-limit` (default 900 s, `NEXUS_YIELD_WAIT_LIMIT_S`) counted from the moment only yield-capable work
   holds the barrier: at least one job in flight, all of yield-capable types, drain acknowledged, owner live, no task
   lease and nothing quarantined. Any other blocker restarts that timer and the long `--wait-limit` applies.
4. `backup` is in the default ship targets (skipped while it is on the fallback). Cutover steps:
   `docs/operations/BACKUP_MODULE_CUTOVER.md`.

## Drain timeout and mixed images

On drain-wait expiry the module keeps its old image and the step reports BLOCKED (continuable). The drain is lifted
automatically, so the old owner claims again at once, ONLY when all of these hold: a forward release (never
`--rollback`), the drain was not already requested before this release (an earlier fence from a failed routine
release or an operator stays), and no quarantined admission or task lease holds the barrier. Otherwise the drain is
kept, as before Phase 1, and the operator decides.

Consequence of a lift: until the next release that module runs the OLD image beside the newer service, schema and
later modules (mixed images). That is safe only because every release's migrations are additive (the
`MigrationAdditiveFenceTest` rule). A release with a non-additive change, or one whose newer modules need the newer
worker/policy code, must be shipped with `NEXUS_YIELD_WAIT_LIMIT_S=0` and watched; on a drain timeout retry the
module (or roll the whole release back) instead of leaving the mix running.

## Sequencing

V262 (artefact chunk GC) ships before this change; Flyway runs without `outOfOrder`, so V263 must not reach a
database that lacks V262. If the chunk-GC lane slips, renumber this migration above the highest applied version.

## Operator rollout, in order

1. Migration dry-run of V263 on the live database inside `BEGIN; ... ROLLBACK;` (two `ADD COLUMN IF NOT EXISTS`, one
   `CREATE INDEX IF NOT EXISTS`). Check: no error; `jobs` row count unchanged. Note the index build takes a short
   write lock on `jobs`; ship outside a job window.
2. Ship through the normal PR and ship runner (outside the protected windows). The first release of this build
   still drains the old binaries, which do not yield reads at unit boundaries; if a long read could exceed 900 s,
   run that one release with `NEXUS_YIELD_WAIT_LIMIT_S=0 tools/delivery/hosta_deploy.sh ...` (passed to the host;
   long limit throughout).
   A short-limit expiry is harmless anyway: the drain is lifted, the module keeps its old digest, the step reports
   BLOCKED (continuable) and the next release retries it.
3. After the release: `select count(*) from flyway_schema_history where version='263' and success;` = 1.
4. Ownership unchanged by the routine release:
   `select module, effective_owner, fallback_enabled, generation from module_runtime_control order by module;`
   compare with the value before step 2: `policy` generation equal (it used to grow by one every release).
5. Watch the next two releases' `TIMING module_worker_drain` and `TIMING module_policy_drain` lines and the
   `Module waiting: ... barrier ...` lines (counts only). Expected: policy drains in seconds to about two minutes.
6. Requeue observation after a release that hit running work (counts only):
   `select count(*) from jobs where resume_eligible_until is not null and state='REQUESTED';` should fall to 0
   within a few minutes after the new pod is up; `select count(*) from jobs where state='REQUESTED' and resume_eligible_until < now();` = 0
   means nothing was stranded past its grace (a non-zero count waits for the next window as before).
7. Backup cutover only with a separate PO authorization reference: `docs/operations/BACKUP_MODULE_CUTOVER.md`.

## Rollback

Code: ship the previous commit (routine replacement path of the new tool, or `module_deploy.py --rollback` with the
release snapshot). Schema: none needed; V263 columns are nullable and unread by older binaries.

## Known follow-up (not in this change)

Review plan item 2c: `EndpointRuntime.acquire()` still spins up to 30 s on a draining module, and a multi-session
write job (backup, failover execution, configuration write) that already crossed a mutation boundary can hit
`AdmissionWait` at its next transport open; `defer()` then refuses the requeue (mutation crossed) and the job waits
for lease recovery. Fail-fast for reads and "never abort a crossed write at acquire()" stay a separate change. The
backup half disappears with the backup cutover; failover execution on the general worker remains exposed.
