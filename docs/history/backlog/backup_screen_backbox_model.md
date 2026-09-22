# Backup screen on the Backbox model: per-device history, schedule, baseline, run-now, download, compare in one view

status: in_progress · target: PO directive 2026-09-22 (Backbox standard); download/contents/compare shipped 2026-09-22, history/schedule/baseline remain

2026-09-22 slice 1 shipped: real audited backup_policy row (schedule cron + retention, PUT gated backup_admin), cron-driven fleet backup scheduler in the service (fenced slot claim, 6 h catch-up), per-device History dialog, baseline (backup_baseline, PUT /devices/{id}/backup-baseline) with Compare preselecting it. Remaining: wire RetentionPruningService to the policy (currently null in Ui2WorkerMain), storage quota, weekly snapshot schedule.
