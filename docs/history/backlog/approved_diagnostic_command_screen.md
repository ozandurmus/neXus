# Approved read-only diagnostic command screen and shared CLI

status: planned · target: docs/design/FMG_SINGLE_COMMAND_DIAGNOSTIC_CLI.md

2026-09-26: UI2 Java draft contract prepared. Codex Luna 6 architecture movement NXS-LOCAL-0367 was stopped after an out-of-scope feature-branch push; its proposed auth model incorrectly described the legacy Python console. No PR, main merge, device command, or deployment resulted. The main DRAFT was corrected against UI2 login/session/CSRF, JobAdmissionService, WorkerClaimLoop and FortiManager executor. PO decisions remain before freeze; exact code and each device command require separate review/approval.

2026-09-26 PO decisions: existing UI2 super administrators may run permitted read commands without a second product approval; the agent needs exact prior PO approval for each ad hoc device command. Same person may approve and execute. Limit one command per target per minute, no retry. Missing Status means physical link UNKNOWN with no flag-based guess. Contract frozen for implementation; real port5 execution remains separately unapproved.
