# NXS-LOCAL-0363 — Backup download with RBAC, archive listing and compare, streamed artefact cipher, jobs screen

status: automated_validated · completed: 2026-09-22 · movement: IMPLEMENTATION

Browser download of backups under BACKUP_ADMIN (PO decision record), archive content listing and member compare, framed AES-GCM artefact store v2 with worker drain and lease heartbeat, backup policy scheduler and retention pruning, PAN bundle with set-format config, Jobs screen with server-side filters, pagination and CSV export, nexus-cli in the image, ingress by IP. Deployed to HOST-A and verified live.

## Authority and evidence

- `docs/design/PO_DECISION_RECORD_2026_09_22_BACKUP_HTTP_DOWNLOAD_WITH_RBAC.md`
- `docs/design/BACKUP_ARCHIVE_CONTENT_LISTING_AND_COMPARE.md`
- `docs/design/NEXUS_CLI.md`
- `docs/design/PAN_BACKUP_COMMAND_GATE_ENTRIES.md`

## Real-environment note

Deployed to HOST-A the same day (run_build.sh, rollout verified). Screens inspected under the aiview persona; device-facing reads graded automated_validated until the next Bulk Collect confirms them on gateways and VSX members.
