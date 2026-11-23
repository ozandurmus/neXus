# How to review and close OUTCOME_UNKNOWN backup jobs

Status: operations note (not a contract). Read-only research; no code changed.

## 1. Is there a supported operator action? Yes.

`OUTCOME_UNKNOWN` is a terminal-but-reconcilable state; its only legal exit is `RECONCILED`
(`ui2/job-engine/.../jobs/JobState.java:43`, test `JobTest.java:51`).

| Aspect | Fact | Where |
| --- | --- | --- |
| Endpoint | `POST /api/v2/jobs/{id}/reconcile`, body `{ "outcome", "evidence" }` only | `ui2/service/.../api/JobReconciliationController.java:23`; route gate `SecurityWebMvcConfig.java:332` |
| Roles | primary `security_admin`; `backup_admin` is also allowed | `ActionRegistry.java:530` (`JOB_RECONCILE = "job_reconcile_record"`, line 209) |
| Outcomes | `RECONCILED_SUCCESS`, `RECONCILED_FAILURE`, `RECONCILED_STILL_UNKNOWN` | `JobReconciliationRepository.java:16`, DB CHECK in `V4__collection_engine_core.sql:151` |
| Evidence | required, non-blank free text, max 2000 chars (stripped); no machine check against artefacts | `JobReconciliationRepository.java:20,29-33` |
| Result | one audited transaction: inserts `job_reconciliation(job_id, evidence, recorded_by, reconciled_outcome, recorded_at)`, sets `jobs.state='RECONCILED'`, `jobs.reconciliation_ref`. Never contacts a device, never makes the job claimable again | `JobReconciliationRepository.java:34-48` |
| Audit | `AuditedTransactionBoundary`, action `job_reconcile_record`, actor = session fingerprint | same file |
| Refusals | 404 `JOB_NOT_FOUND`; 409 `JOB_NOT_OUTCOME_UNKNOWN`; 409 `JOB_BELONGS_TO_FAILOVER_RUN`; 400 `JOB_RECONCILIATION_INVALID` | controller + repository |
| UI | Jobs screen: Reconcile dialog, offered when `isReconcilable` (state OUTCOME_UNKNOWN and not a failover job) and session holds admin+operations planes | `frontend/src/screens/JobReconcileDialog.tsx`, `jobs/jobReconcile.ts:34`, `shell/actionAccess.ts:43-53`, `adminApi.ts:1883` |

Notes for the PO:
- Backup jobs (`cp_gateway_backup`, `cp_mds_export`) are not failover jobs, so they are reconcilable.
- `RECONCILED_STILL_UNKNOWN` also moves the job to `RECONCILED`; it records "checked, cannot tell" and is the honest choice when no artefact evidence exists either way.
- Estate views already treat a night with only an OUTCOME_UNKNOWN job and no archive as UNKNOWN, never Fine (`estateSlice2Model.ts:1802,1868`); reconciling does not by itself create a backup.
- The call is per job (42 calls); there is no bulk endpoint. Do not script it with a shared credential; use the UI or an authenticated security_admin/backup_admin session so the audit actor is a real person.

## 2. Evidence that a backup completed

`backup_artefact` (`V17__backup_artefact_manifest.sql:33`) holds one manifest row per stored artefact:
`artefact_id`, `device_id`, `artefact_class` (`configuration`|`backup`), `plaintext_sha256`/`plaintext_bytes`,
`ciphertext_sha256`/`ciphertext_bytes`, `key_id`, `validation` (jsonb, `level_reached` V1..V4), `retention_tier`,
`expires_at`, `created_at`. `recovery_volume_path` is server-side only and must never be returned or reported.
Index: `idx_backup_artefact_device_id (device_id, created_at)`.

A reviewer decides per job:
- artefact with `artefact_class='backup'` for the same device created in the job window, non-zero `ciphertext_bytes`, a
  `ciphertext_sha256` present -> evidence that a backup exists (candidate `RECONCILED_SUCCESS`; check `validation` level
  separately if restore proof matters).
- no such artefact -> no stored backup (candidate `RECONCILED_FAILURE`), or `RECONCILED_STILL_UNKNOWN` if the device side
  could have produced a file that never reached the store.
- artefact exists but only on another night -> that is not evidence for this job.

Read-only SQL. Counts, booleans and opaque ids only; no jsonb expansion, no paths, no hostnames:

```sql
-- One row per OUTCOME_UNKNOWN backup/export job, with artefact presence in a [submitted-1h, submitted+36h] window.
SELECT j.job_id,
       j.capability_id,
       j.job_type,
       date_trunc('day', j.submitted_at)::date             AS submitted_day,
       j.target_device_id,                                  -- opaque id
       COUNT(a.artefact_id)                                 AS artefact_rows,
       COALESCE(bool_or(a.ciphertext_bytes > 0
                        AND a.ciphertext_sha256 <> ''), false) AS artefact_present,
       MAX(a.created_at)                                    AS newest_artefact_at
FROM jobs j
LEFT JOIN backup_artefact a
       ON a.device_id = j.target_device_id
      AND a.artefact_class = 'backup'
      AND a.created_at >= j.submitted_at - interval '1 hour'
      AND a.created_at <  j.submitted_at + interval '36 hours'
WHERE j.state = 'OUTCOME_UNKNOWN'
  AND (j.capability_id ILIKE '%backup%' OR j.job_type ILIKE '%backup%' OR j.job_type ILIKE '%export%')
GROUP BY j.job_id, j.capability_id, j.job_type, j.submitted_at, j.target_device_id
ORDER BY j.submitted_at;
```

Cross-checks (counts only):

```sql
-- Totals by outcome of the presence test (should add up to the 42 jobs).
SELECT j.job_type, COUNT(*) AS jobs,
       COUNT(*) FILTER (WHERE EXISTS (SELECT 1 FROM backup_artefact a
              WHERE a.device_id = j.target_device_id AND a.artefact_class = 'backup'
                AND a.created_at >= j.submitted_at - interval '1 hour'
                AND a.created_at <  j.submitted_at + interval '36 hours')) AS with_artefact
FROM jobs j WHERE j.state = 'OUTCOME_UNKNOWN' GROUP BY j.job_type;

-- Which step boundary was crossed (a write that may have reached the device): attempt rows per job.
SELECT job_id, COUNT(*) AS attempts, COUNT(*) FILTER (WHERE error_class IS NOT NULL) AS with_error
FROM job_step_attempt WHERE job_id IN (SELECT job_id FROM jobs WHERE state = 'OUTCOME_UNKNOWN')
GROUP BY job_id;
```

The window is a reviewer heuristic, not a schema fact (the table has no `job_id` link; V17 binds artefacts to a device,
not to a job). Verify the column names `jobs.capability_id`/`submitted_at` and `job_step_attempt.error_class` against the
live schema before running (V1, V4 migrations). Run under the aiview/masked path; share only the result relationship
(job_id, day, present yes/no), never device identity.

After the decision, verify closure: `SELECT reconciled_outcome, COUNT(*) FROM job_reconciliation GROUP BY 1;` and
`SELECT COUNT(*) FROM jobs WHERE state='OUTCOME_UNKNOWN';`.

## 3. Smallest safe design if bulk closure is wanted

Not required: the per-job action in section 1 already is audited, role-gated and requires a reason. If 42 single calls
are judged too heavy, the smallest addition is a thin batch wrapper, not a new state path:

- `POST /api/v2/jobs/reconcile-batch`, `security_admin` only (not `backup_admin`), body
  `{ outcome, evidence, jobIds[] }` with `jobIds` capped (e.g. 50) and evidence required (<= 2000).
- Each job is processed through the existing `JobReconciliationRepository.record` in its own audited transaction, so
  every job gets its own `job_reconciliation` row and audit entry with the real actor; a refusal for one job does not
  roll back the others and is returned per job.
- Refuses failover jobs and non-OUTCOME_UNKNOWN jobs exactly as today; never contacts a device; no auto-"success" derived
  from artefact presence (readiness and artefact evidence are inputs to the human decision, not the decision).
- New `ActionRegistry` entry and `SecurityWebMvcConfig` route mapping, covered by the existing gate/role tests; UI is a
  multi-select on the Jobs screen reusing `JobReconcileDialog`.
