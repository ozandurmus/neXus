# Debug and parser command screen

**Successor direction:** `DEBUG_OPERATIONS_SUCCESSOR_DECISION_2026_09_26.md`
records the PO-approved all-device screen, optional catalog/text authoring, and
human/AI output projections. This document remains the deployed pilot's execution
contract until the successor's implementation boundaries are frozen.

**Status: FROZEN — PRODUCT OWNER APPROVED, amended 2026-09-26.** The Product Owner directed a small Debug/Parser screen: choose a device, type a command, run it as a super administrator, and view a masked result that an AI agent can use for parser work. An agent still needs separate, exact Product Owner approval before it runs an ad hoc device command. One command per target per minute, no retry or device write; absent explicit `Status` means physical link `UNKNOWN`. This contract authorizes implementation, not the first real port5 execution.

## Objective

Add one Operations › Debug area in UI2 Java. A super administrator selects a device, types a read command, sees the exact command preview, runs it, and reads the masked safe result. The first supported form is FortiManager `diagnose fmnetwork interface detail <port>` (V89 gate), using a port from that device's inventory. Other text is refused until its exact read form is gated. The proposed first target is represented under `aiview` as `FW-WHISKEY-02`, port5. A Codex CLI is outside this first build; its earlier WIP is not implementation authority.

## Existing product path

Reuse UI2 `/login`, `/session/status` and CSRF, `SecurityWebMvcConfig`, `JobAdmissionService`, durable jobs, `WorkerClaimLoop`, `FortiManagerExecutor` and trusted SSH transport. The legacy Python `console/` bearer model is outside this build. No parallel credential or direct device path is introduced.

## Closed execution contract

1. The screen accepts typed command text as a local proposal. For the first build, it accepts only the exact FortiManager read form above and extracts one port token. It sends only the fixed `template_id`, opaque enrolled `device_id`, and server-validated inventory port. The typed command text, argv, hostname, address, account, credential, route and transport option never reach the worker. The server renders its own command from the signed-off gate row. Unknown text is refused, never executed.
2. Before Run, the screen shows the server-rendered command, AIView target pseudonym, port, 60-second timeout, no-retry rule, one-command-per-target-per-minute limit and safe output fields. The route uses UI2's existing session/CSRF/RBAC chain and requires `role:security_admin`. A super administrator may submit their own request. An agent needs the Product Owner's exact, prior approval before using the Run action.
3. `JobAdmissionService` refuses before job creation for wrong role, unsigned/unsupported template, unenrolled or mismatched target, stale/non-member port, malformed typed input or rate-limit violation. A matching idempotent repeat returns the existing job; a conflicting repeat is refused. Each accepted request creates one durable typed job and records actor, target, template, port, gate revision and submission outcome.
4. `WorkerClaimLoop` routes only that fixed capability to the existing FortiManager executor and trusted SSH transport. The worker rechecks the job and target, sends exactly one gated read in the approved CLI context, once, with a 60-second timeout and no retry, and records a terminal outcome. Uncertain dispatch is `UNKNOWN`/collection-failed and is never replayed. Verification and troubleshooting never change device configuration or operational state.
5. Raw output stays in memory just long enough to parse safe field-presence booleans, bounded `UP`/`DOWN`/`OTHER`/`ABSENT` token, line count, closed shape ID, and at most 64 structural line labels from a fixed vocabulary (`Status`, `Speed`, `INTERFACE_HEADER`, `ADDRESS_FIELD`, `FLAGS_FIELD`, `MASKED`, `TRUNCATED`). Values and unrecognized lines are masked. Raw lines and device identities never enter job records, logs, UI, CLI, screenshots or repository metadata. No `UP`, `RUNNING` or other field is promoted to physical link without vendor semantic proof and real corroboration. Missing `Status:` displays physical link `UNKNOWN` with no substitute guess.
6. The UI reads the terminal job result. The agent uses only the masked `aiview` projection for visual inspection and parser work; neither UI nor agent receives raw output. A CLI client is deferred.

## First command preview and safe result

```text
Typed intent: template=fmg_interface_detail, target=FW-WHISKEY-02 (opaque ID on wire), port=port5
Server-rendered command: diagnose fmnetwork interface detail port5
Transport: existing neXus FortiManager SSH session; class 0 read; timeout 60 s; retry none
UI result: job=<opaque> state=<terminal enum> status_present=<boolean>
               status_token=<UP|DOWN|OTHER|ABSENT> lines=<bounded count> shape_id=<closed enum>
               masked_output=<bounded structural labels only>
```

The candidate has **not** been executed for port5. The Product Owner's vendor screenshot is a derived comparison observation; agent UI validation remains under `aiview`.

## Build and validation

The first build delivers the existing typed backend with a small Operations › Debug area. No general command platform or CLI client is in scope. Targeted tests prove refusal before contact for wrong role, unknown command text, target, port and rate limit; concurrency and idempotency cannot send two commands for one accepted request. Tests prove no raw output or identity reaches UI/logs and no retry occurs. UI changes require the HTML render harness, full suite and repository privacy gate. Any new SQL migration is dry-run in `BEGIN/ROLLBACK` on the live DB before deployment via `scripts/hosta_deploy.sh`; `ui2-configuration` then uses the service digest. Each deployment and job is watched to terminal state.

Before the agent executes any real diagnostic, the Product Owner reviews the exact code, command, target and safe output and gives a separate `OK` for that command. `NOK` or silence means no execution. Approval of one command does not approve a new port, command, retry or later code revision.
