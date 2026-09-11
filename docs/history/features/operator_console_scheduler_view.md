# operator_console_scheduler_view — Operator Console - scheduler surface (read-only)

## summary

Read-only view of the RuntimeRoot scheduler policy, due-ness and last/next run, including which workflows cannot be scheduled at all and why.

## why

'recovery-cp' is absent from ALLOWLISTED_WORKFLOWS because D3 approved the CP backup command and explicitly not unattended fleet backup. An operator who cannot see that assumes the opposite. Contract: docs/history/phase/CON_5_CONSOLE_SCHEDULER_SURFACE.md.
