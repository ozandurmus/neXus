# Approved read-only diagnostic command screen and shared CLI

status: planned · target: docs/design/FMG_SINGLE_COMMAND_DIAGNOSTIC_CLI.md

2026-09-26: UI2 Java draft contract prepared. Codex Luna 6 architecture movement NXS-LOCAL-0367 was stopped after an out-of-scope feature-branch push; its proposed auth model incorrectly described the legacy Python console. No PR, main merge, device command, or deployment resulted. The main DRAFT was corrected against UI2 login/session/CSRF, JobAdmissionService, WorkerClaimLoop and FortiManager executor. PO decisions remain before freeze; exact code and each device command require separate review/approval.

2026-09-26 PO decisions: existing UI2 super administrators may run permitted read commands without a second product approval; the agent needs exact prior PO approval for each ad hoc device command. Same person may approve and execute. Limit one command per target per minute, no retry. Missing Status means physical link UNKNOWN with no flag-based guess. Contract frozen for implementation; real port5 execution remains separately unapproved.

Feature branch implements one gated FortiManager read with typed command input and safe status/shape summary; automated UI, targeted backend, architecture and render checks pass. Not merged or deployed. Masked line-by-line output remains open; first real device command requires separate exact PO approval.

Feature branch implements one gated FortiManager read with typed command input and a masked structural response of at most 64 fixed line labels. Targeted tests pass; not merged or deployed. Revised V90 requires live BEGIN/ROLLBACK dry-run before deploy; first real device command requires separate exact PO approval.

Feature branch implements one gated FortiManager read with typed command input and a masked structural response of at most 64 fixed line labels. Targeted tests pass; revised V90 passed live BEGIN/ROLLBACK dry-run. Not merged or deployed. First real device command requires separate exact PO approval.

Merged to main and deployed via scripts/hosta_deploy.sh. Schema 90, service/worker/configuration ready 1/1, configuration image digest matches service, diagnostic jobs 0. UI aiview inspection unavailable because the existing browser session is at sign-in. No diagnostic device command was sent; parser and physical link real-environment validation remain pending exact PO command approval.
