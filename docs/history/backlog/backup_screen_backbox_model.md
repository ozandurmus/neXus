# Backup screen on the Backbox model: per-device history, schedule, baseline, run-now, download, compare in one view

status: automated_validated · target: PO directive 2026-09-22 (Backbox standard); download/contents/compare shipped 2026-09-22, history/schedule/baseline remain

2026-09-22 slice 1 shipped: real audited backup_policy row (schedule cron + retention, PUT gated backup_admin), cron-driven fleet backup scheduler in the service (fenced slot claim, 6 h catch-up), per-device History dialog, baseline (backup_baseline, PUT /devices/{id}/backup-baseline) with Compare preselecting it. Remaining: wire RetentionPruningService to the policy (currently null in Ui2WorkerMain), storage quota, weekly snapshot schedule.

## 2026-10-17 -- project JSON compacted (size budget)

Title (full): Backup screen on the Backbox model: per-device history, schedule, baseline, run-now, download, compare in one view

Target (full): PO directive 2026-09-22 (Backbox standard); download/contents/compare shipped 2026-09-22, history/schedule/baseline remain

2026-11-14 backlog truth: still open (P2, size S). Today: Only the per-store storage quota is missing; screen otherwise complete. Evidence: History/schedule/baseline/compare/retention shipped (129abc10, 53afe43a); no storage quota code in ui2

2026-11-15 10:00 backlog refresh: In the ship queue (batches b4..b9, not yet on main). A global artefact-store byte quota is built (see vault_has_no_enforced_quota_on_a_shared_host). The per-store quota this item asks for is not; left open.

## 2026-11-15

Built on lane/storequota-1115 (V225 artefact_store_quota, GET/PUT /api/v2/backups/store-quotas, CLI backup-store-quotas and backup-store-quota-set, Backup screen card): close this item when that lane ships.

2026-11-16 backlog refresh (origin/main db9b2ecd, live through batch 14, schema V242): CLOSED. Commit 'Add a per-store storage quota to the artefact store (V225)' (batch 10) delivered the last open part: GET/PUT /api/v2/backups/store-quotas, CLI backup-store-quotas and backup-store-quota-set, Backup screen card; commit 'Cover the report store and audit every refused artefact write (V227)' adds the refusal audit. Batch 11 'Reports: label STORE_QUOTA_EXCEEDED runs'. AUTOMATED_VALIDATED; no quota-exceeded refusal in the field recorded.
