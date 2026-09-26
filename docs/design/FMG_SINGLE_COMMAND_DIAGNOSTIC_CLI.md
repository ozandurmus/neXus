# Approved diagnostic command screen and CLI

**Status: DRAFT — DO NOT FREEZE.** This document permits design work only. Implementation needs a Product Owner freeze; each real diagnostic command separately needs the Product Owner's review of exact code, command, masked target and safe output, then an explicit `OK`. `NOK` or silence means no device contact.

## Objective

Add one neXus UI2 command screen for approved read-only diagnostic templates, with a Codex CLI client of the same typed API. The first proposed template is FortiManager `diagnose fmnetwork interface detail <port>` (V89 gate). The first proposed target is the enrolled FortiManager shown as `FW-WHISKEY-02` under `aiview`, port5. V89's real port1 read did not contain the older vendor example's `Status:` field; physical link remains `UNKNOWN`.

## Existing UI2 path

UI2 Java already has `/login` (session cookie), `/session/status` (CSRF), `SecurityWebMvcConfig` (closed route/action mapping), `JobAdmissionService` (typed admission), a durable job store, `WorkerClaimLoop`, and the FortiManager executor using the trusted SSH transport. Use those seams. The legacy Python `console/` has a different bearer-token model and is outside this build.

## Closed flow

1. The screen lists server-owned, signed-off read-only templates. A request contains only `template_id`, opaque enrolled `device_id`, one server-derived inventory port, and a one-use approval reference. It contains no command, argv, hostname, address, account, credential, route or transport option. The server validates port membership and a strict token grammar.
2. The screen previews the exact server-rendered command, target pseudonym, port, timeout, retry/frequency limits and safe output fields. A PO-specific approver role approves one exact command after reviewing the implementation. Approval binds approver, permitted actor, target, port, template/gate revision, code revision and expiry; it is consumed atomically with admission of one durable job. The execution actor cannot create their own approval through submission.
3. A dedicated UI2 API route is mapped in `SecurityWebMvcConfig`, uses the existing session and CSRF chain, and admits a fixed diagnostic capability through `JobAdmissionService`. Missing, stale, consumed or mismatched approval is refused before a job or device contact. A matching idempotent repeat returns the same job; it does not send the command twice.
4. `WorkerClaimLoop` routes that one capability to the existing FortiManager executor and trusted SSH transport. It sends only the registered command to the approved target/port, once, with a 60-second timeout and no retry. Unknown dispatch outcome is `UNKNOWN`, never automatically replayed. Verification and troubleshooting never change device configuration or operational state.
5. The worker parses in memory, discards the raw response, and stores only fixed safe enums, field-presence booleans, bounded counts and a closed shape ID. No raw lines, hostnames, addresses, serials, accounts or credentials enter jobs, logs, CLI, UI or repository metadata. `Status` is not promoted to physical link until vendor semantics and real output prove its meaning.
6. The screen and Codex CLI read the same job result. The CLI authenticates through UI2's existing `/login` and `/session/status`, with credentials and cookie kept only in process memory. It prints the exact command preview before submission and the sanitized result after the job reaches a terminal state. It contains no SSH or device transport code. The CLI must not reuse the Edge browser cookie.

## Reviewable first command

```text
UI/CLI intent: template=fmg_interface_detail, target=FW-WHISKEY-02 (opaque ID on wire), port=port5
Server-rendered device command: diagnose fmnetwork interface detail port5
Transport: existing neXus FortiManager SSH session; class 0 read; timeout 60 s; retry none
Terminal/UI result: job=<opaque> state=<terminal enum> status_present=<boolean>
                    status_token=<UP|DOWN|OTHER|ABSENT> lines=<bounded count> shape_id=<closed enum>
```

The candidate has **not** been approved or executed for port5. The screenshot supplied by the Product Owner is a derived comparison observation; agent UI validation remains exclusively under `aiview`.

## Delivery and checks

Deliver the shared typed backend, minimal command screen and thin CLI in one bounded build. The browser sends typed intent only. Targeted tests must prove refusal before contact for missing/wrong/expired/consumed approval, wrong target or port, unsigned template, stale inventory and unauthorized actor; concurrent submissions must consume approval at most once. One approved job yields at most one SSH command, with no retry. Tests must prove raw output is discarded and only the safe projection reaches UI/CLI. UI changes require the HTML render harness, full suite and repository privacy gate. Any new SQL migration is dry-run inside `BEGIN/ROLLBACK` on the live DB before `scripts/hosta_deploy.sh`.

## Product Owner decisions before freeze

1. Which existing or new UI2 role identifies the Product Owner approver? Recommended: a dedicated `diagnostic_approver` role, separate from the execution actor. Do not infer authority from the `aiview` persona or the `operator` role.
2. How long does one approval live, and may the approver and execution actor be the same person? Recommended: 15 minutes and distinct identities for the first pilot.
3. What is the maximum rate across separately approved reads per endpoint? Recommended: one diagnostic per endpoint per 15 minutes, enforced by admission, with no retry.
4. Does a missing `Status:` field report only `ABSENT/UNKNOWN`, or should the UI also show `UP/RUNNING` as non-link diagnostic flags? Recommended: `ABSENT/UNKNOWN` only for the first build.

Freezing this contract authorizes implementation, not any real command. After implementation, the Product Owner reviews the exact diff and separately approves or rejects the first port5 execution.
