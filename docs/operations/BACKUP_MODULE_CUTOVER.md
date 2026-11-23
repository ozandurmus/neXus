# Backup module cutover (general fallback -> ui2-backup)

Status: PREPARED, NOT PERFORMED. Deploy-freeze Phase 1 (PO approval 2026-11-23). The cutover itself needs a
separate PO authorization reference and runs only through `tools/delivery/module_deploy.py`.
Authority: `docs/design/MODULE_POD_RUNTIME_CONTRACT.md` (FROZEN) §1 (fallback flag, audited compare-and-set
generation change), §3 (drain barrier), §6 step 2 (backup acceptance).

## Why

While `backup` runs on the general worker, every `ui2-worker` release waits behind non-yieldable backup work (Gaia
backups, `cp_mds_export`, controller backups). Moving backup to its own pod makes worker releases wait only for
read jobs, which yield at unit boundaries (`EndpointRuntime.yieldAtUnitBoundary`) and resume through the
window-preserving requeue (V263).

## What the cutover changes, and what it does not

| Item | Before | After | Who changes it |
| --- | --- | --- | --- |
| `module_runtime_control` row `backup` | `effective_owner='general'`, `fallback_enabled=true` | `effective_owner='backup'`, `fallback_enabled=false`, `generation+1`, `last_authorization_ref`, `last_reason='ready backup cutover'`, `last_snapshot_id` | `handover_backup` (one audited transaction under advisory lock 294611, fenced on drain generation and a live backup heartbeat) |
| Claims of the nine backup job types | general worker | `ui2-backup` only (claim SQL requires `effective_owner = split_part(worker id,'-',1)`) | follows from the row |
| `BACKUP_MODULE_TASKS` (`OrphanArtefactPurger.runOnce`, `RetentionPruningService.prune`) | general worker | backup pod (`ModuleRuntimeRepository.taskModule` + `effective_owner`) | follows from the row |
| `ui2-backup` Deployment replicas | 0 (committed) | 1 (`kubectl scale` by the tool; later applies keep the live count via `render_manifest(..., live_replicas)`) | `module_deploy.py` |
| Backup schedulers (`BackupScheduler`, `MdsExportScheduler`, `CyberControllerBackupScheduler`) | unchanged | unchanged (they submit; the owner claims) | none |
| General worker JVM flag `ui2.worker.claim-backup-fallback` | default true | unchanged (it only matters when the row says `general`) | none |
| Default ship targets | `backup` listed | routine releases now replace `ui2-backup` without touching ownership | already committed |

A routine release never changes ownership: `ownership_preflight` skips a module still on the fallback unless
`--ownership-transfer` is given, and a module that owns itself is replaced by drain -> replace -> lift drain
(`resume_dedicated`, no generation change). A failed routine replacement fences the module and keeps its pod and
owner; it never hands backup back to general.

## Preconditions (all must hold; counts and booleans only, never identities)

Run from the release operator's session on the host. `Q` stands for
`kubectl -n ui2 exec ui2-db-0 -- sh -c 'psql -U "${POSTGRES_USER:-$POSTGRESQL_USER}" -d ui2 -Atc "<sql>"'`.

1. PO authorization reference recorded (opaque token, `[A-Za-z0-9_.:/-]{1,160}`).
2. Time: outside 02:00-04:59 (scheduled backup window and its resume grace, capped at window end + 2 h), outside the protected ship windows
   (00/06/12/18 and from :20 of the hour before), no ship chain or nightly e2e running.
3. Schema: the live database has V262 (chunk GC) and V263 (`select max(version::int) from flyway_schema_history where success`), and the
   live service/worker image is the build that contains this change (ownership preflight, routine replace).
4. Ownership row as expected:
   `select effective_owner, fallback_enabled, generation, drain_requested, owner_instance is not null from module_runtime_control where module in ('backup','general') order by module;`
   expect `general|t|<g>|f|f` for backup (or `|t` if a stale owner, still fine) and a live general row.
   General heartbeat: `select owner_heartbeat_at > now() - interval '60 seconds' from module_runtime_control where module='general';` = `t`.
5. No backup work in flight, nothing uncertain:
   - `select count(*) from jobs where state in ('CLAIMED','EXECUTING') and ui2_job_module(capability_id)='backup';` = 0
   - `select count(*) from jobs where state='OUTCOME_UNKNOWN' and ui2_job_module(capability_id)='backup';` = 0
   - `select count(*) from endpoint_admission where state in ('LEASED','QUARANTINED') and (owner_role='backup' or purpose_class='BACKUP');` = 0
   - `select count(*) from runtime_task_lease where state in ('LEASED','QUARANTINED') and task_key in ('OrphanArtefactPurger.runOnce','RetentionPruningService.prune');` = 0
   (The tool's barrier re-checks all of these; checking first avoids a long drain.)
6. Cluster objects present: `kubectl -n ui2 get pvc ui2-artefact-store ui2-report-store` (both Bound; ReadWriteOnce on
   the single node, shared with the general worker and service as today), secrets `ui2-db`,
   `ui2-credential-store-key`, `ui2-artefact-store-key`, configmaps `ui2-config`, `ui2-cc-receiver`, `corp-ca`, and the
   controller push inbox host directory. `ensure_mounted_claims` refuses the apply if a claim is missing.
7. DB connection headroom for one more pool (backup pod: maximum 4):
   `select current_setting('max_connections')::int - count(*) from pg_stat_activity;` >= 20.
8. Fresh release snapshot: `bash tools/delivery/release_snapshot.sh --commit <deployed sha>`; note the snapshot id.

## Cutover (one command)

Use the digest the service and worker run now, so the backup pod runs the same code:

```
LIVE=$(kubectl -n ui2 get deployment/ui2-worker -o jsonpath='{.spec.template.spec.containers[0].image}')
python3 tools/delivery/module_deploy.py --targets backup --ownership-transfer \
  --image "$LIVE" --snapshot "$HOME/release-snapshots/<snapshot id>" --commit <deployed sha> \
  --authorization-ref <PO reference> --wait-limit 3600
```

What it does, in order: verifies the snapshot checksums; `ownership_preflight` confirms backup is the only target
and still on the fallback; requires the live general heartbeat; creates the Deployment at 0 replicas if absent;
diffs the manifest; requests the backup drain (general stops claiming backup types); waits for the barrier (ack +
zero jobs/permits/task leases; on timeout it stops with nothing changed and lifts the drain only if no earlier
fence or quarantine exists, see DEPLOY_FREEZE_PHASE1_ROLLOUT.md); applies the
manifest keeping 0 replicas; scales to 1; waits for rollout and a live backup heartbeat; runs `handover_backup`;
runs the masked e2e. Watch it to the end; it prints `TIMING module_backup_*` lines and one JSON result line.

On failure after the handover the tool drains the backup pod (up to 900 s), stops it only if it drained, and
returns ownership to general (`reset_to_general`, audited, zero-work fenced). If the pod cannot drain it is left
running and fenced: report, do not force.

## Verification after the cutover (counts only)

1. Row: `select effective_owner, fallback_enabled, generation, drain_requested, owner_heartbeat_at > now() - interval '60 seconds', last_reason from module_runtime_control where module='backup';`
   expect `backup|f|<g+1>|f|t|ready backup cutover`.
2. No general claims of backup types since the cutover:
   `select count(*) from jobs where ui2_job_module(capability_id)='backup' and state in ('CLAIMED','EXECUTING') and split_part(lease_worker_id,'-',1) <> 'backup';` = 0.
3. Audit present: `select count(*) from audit_log where action_id='module_release' and occurred_at > now() - interval '1 hour';` >= 1
   (column names as in the live `audit_log`; adjust the time column if it differs).
4. Masked aiview e2e passed (tool output `"e2e": "PASS"`).
5. Next 02:00 window (morning check): by owner and state, counts only:
   `select split_part(lease_worker_id,'-',1), state, count(*) from jobs where ui2_job_module(capability_id)='backup' and submitted_at > now() - interval '12 hours' group by 1,2 order by 1,2;`
   expect only `backup` as owner, no `OUTCOME_UNKNOWN`; and `cp_mds_export` completed.
6. Task leases ran on the backup owner: `select owner_role, count(*) from runtime_task_lease where task_key in ('OrphanArtefactPurger.runOnce','RetentionPruningService.prune') group by 1;`
   (rows exist only while a task runs; zero rows is normal between runs).
7. The next routine `ui2-worker` release: its drain wait no longer includes backup jobs
   (`TIMING module_worker_drain` small; barrier lines show `busy_jobs=0` quickly).

## Rollback (return backup to the general worker)

```
python3 tools/delivery/module_deploy.py --targets backup --return-backup-to-general \
  --snapshot "$HOME/release-snapshots/<snapshot id>" --authorization-ref <PO reference>
```

Requires a live general owner without `-Dui2.worker.claim-backup-fallback=false`. It drains backup, scales
`ui2-backup` to 0, waits for the pod to go, then `reset_to_general` (audited, generation+1; refuses while any
backup job, permit or task lease is active or uncertain). Verify with query 1 above: `general|t|<g+2>|f`.

## Follow-ups after a successful cutover

- Commit `replicas: 1` in `deploy/ui2/53-backup-deployment.yaml` (and update its header comment) so a hand apply of
  the committed manifest can never stop the backup owner; the tool already keeps the live count.
- Record the cutover in project state (`tools/delivery/project_queue.py`) and `CURRENT_STATE.md`.
- After three releases, measure worker and policy drain durations (`TIMING module_*_drain`) before any decision on
  the overlapping-handover draft, which stays unapproved.
