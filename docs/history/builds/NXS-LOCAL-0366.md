# NXS-LOCAL-0366 — Automation editor, Script Execution and scheduled device-write decisions; vendor and test contracts

status: automated_validated · completed: 2026-09-22 · movement: IMPLEMENTATION

PO decision records: automation steps authored in the product over gated commands; Script Execution module (own pod, chosen actor, 30-day retention, mainstream languages) with its contract FROZEN; scheduled ledgered device writes from a script run under five conditions (AGENTS.md amended); module-per-pod split opened as P1; vendor backup contracts and measurements for the eight new vendors; test contract for the backup and configuration work.

## Authority and evidence

- `docs/design/PO_DECISION_RECORD_2026_09_22_AUTOMATION_EDITOR_AND_SCRIPT_EXECUTION.md`
- `docs/design/SCRIPT_EXECUTION_CONTRACT.md`
- `docs/design/PO_DECISION_RECORD_2026_09_22_SCHEDULED_DEVICE_WRITES_FROM_SCRIPTS.md`
- `docs/design/TASK_EXECUTOR_AUTOMATION_CONTRACT.md`
- `docs/design/VENDOR_BACKUP_CONTRACTS_2026_09_22.md`

## Real-environment note

Deployed to HOST-A the same day (run_build.sh, rollout verified). Screens inspected under the aiview persona; device-facing reads graded automated_validated until the next Bulk Collect confirms them on gateways and VSX members.
