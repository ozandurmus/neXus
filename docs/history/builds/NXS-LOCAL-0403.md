# NXS-LOCAL-0403 — Script Execution slice 3: run-end syslog and mail notifications (V266)

## Summary

A finished script run sends its requested syslog and mail notification, best effort, with the outcome stored per channel (V266, 8420aaf1). Adds the per-script mail-on-failure-only option.

## Batches

8420aaf1 (2026-11-23, 21:30). V264 renumbered to V266 because V265 shipped first; content unchanged. Adds the append-only script_run_notification table and the per-script mail-on-failure-only option (SCRIPT_EXECUTION_CONTRACT.md section 3.5).

## Open

Not real-environment validated.
