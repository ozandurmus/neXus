# Approved diagnostic command screen and CLI

**Status: FROZEN — PRODUCT OWNER APPROVED, 2026-09-26.** The Product Owner decided: any existing UI2 super administrator may run a permitted read from the product screen without a second product approval; the agent requires the Product Owner's separate, exact approval before each ad hoc device command; no second person is required; one command per target per minute; absent explicit `Status` means physical link `UNKNOWN`. This contract authorizes implementation, not the first real port5 execution.

## Objective

Add an Operations › Diagnostics area in UI2 Java for signed-off, read-only device command templates, with a Codex CLI client of the same typed API. The first template is FortiManager `diagnose fmnetwork interface detail <port>` (V89 gate). The first proposed target is the enrolled FortiManager represented under `aiview` as `FW-WHISKEY-02`, port5. Its real execution still needs exact code/command/target/safe-output review and a separate Product Owner `OK` when the agent runs it.

## Existing product path

Reuse UI2 `/login`, `/session/status` and CSRF, `SecurityWebMvcConfig`, `JobAdmissionService`, durable jobs, `WorkerClaimLoop`, `FortiManagerExecutor` and trusted SSH transport. The legacy Python `console/` bearer model is outside this build. No parallel credential or direct device path is introduced.

## Closed execution contract

1. The screen lists only server-owned, gate-signed-off class 0 read templates. It sends `template_id`, an opaque enrolled `device_id`, and a port selected from that device's server-derived inventory. It sends no command string, argv, hostname, address, account, credential, route or transport option. The server validates port membership and a strict token grammar, then renders the exact command.
2. Before Run, UI and CLI show the server-rendered command, AIView target pseudonym, port, 60-second timeout, no-retry rule, one-command-per-target-per-minute limit and safe output fields. The dedicated route is mapped through UI2's existing session/CSRF/RBAC chain and requires the existing `role:security_admin` (the product's super-admin role). A super admin may submit their own request. The agent also needs the Product Owner's exact, prior approval to submit an ad hoc command; that procedural authorization is recorded in the engineering handover/job reason, not minted by the Run action itself.
3. `JobAdmissionService` refuses before job creation for wrong role, unsigned/unsupported template, unenrolled or mismatched target, stale/non-member port, malformed typed input or rate-limit violation. A matching idempotent repeat returns the existing job; a conflicting repeat is refused. Each accepted request creates one durable typed job and records actor, target, template, port, gate revision and submission outcome.
4. `WorkerClaimLoop` routes only that fixed capability to the existing FortiManager executor and trusted SSH transport. The worker rechecks the job and target, sends exactly one gated read in the approved CLI context, once, with a 60-second timeout and no retry, and records a terminal outcome. Uncertain dispatch is `UNKNOWN`/collection-failed and is never replayed. Verification and troubleshooting never change device configuration or operational state.
5. Raw output stays in memory just long enough to parse safe field-presence booleans, bounded `UP`/`DOWN`/`OTHER`/`ABSENT` token, line count and a closed shape ID. Raw lines and device identities never enter job records, logs, UI, CLI, screenshots or repository metadata. No `UP`, `RUNNING` or other field is promoted to physical link without vendor semantic proof and real corroboration. Missing `Status:` displays physical link `UNKNOWN` with no substitute guess.
6. The UI and Codex CLI read the same job result. The CLI uses UI2's existing login and CSRF flow; credentials and session cookie stay only in process memory. It prints the exact command preview in the terminal before submission and only the sanitized terminal result afterward. It contains no SSH or device transport code and never copies an Edge browser cookie. It follows its job to a terminal state in the same turn.

## First command preview and safe result

```text
Typed intent: template=fmg_interface_detail, target=FW-WHISKEY-02 (opaque ID on wire), port=port5
Server-rendered command: diagnose fmnetwork interface detail port5
Transport: existing neXus FortiManager SSH session; class 0 read; timeout 60 s; retry none
UI/CLI result: job=<opaque> state=<terminal enum> status_present=<boolean>
               status_token=<UP|DOWN|OTHER|ABSENT> lines=<bounded count> shape_id=<closed enum>
```

The candidate has **not** been executed for port5. The Product Owner's vendor screenshot is a derived comparison observation; agent UI validation remains under `aiview`.

## Build and validation

The first build delivers the shared typed backend, a minimal Operations › Diagnostics area and a thin CLI. No arbitrary command box is in scope. Targeted tests prove refusal before contact for wrong role, template, target, port and rate limit; concurrency and idempotency cannot send two commands for one accepted request. Tests prove no raw output or identity reaches UI/CLI/logs and no retry occurs. UI changes require the HTML render harness, full suite and repository privacy gate. Any new SQL migration is dry-run in `BEGIN/ROLLBACK` on the live DB before deployment via `scripts/hosta_deploy.sh`; `ui2-configuration` then uses the service digest. Each deployment and job is watched to terminal state.

Before the agent executes any real diagnostic, the Product Owner reviews the exact code, command, target and safe output and gives a separate `OK` for that command. `NOK` or silence means no execution. Approval of one command does not approve a new port, command, retry or later code revision.
