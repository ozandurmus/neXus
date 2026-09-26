# FortiManager approved diagnostic command screen and CLI

**Status: DRAFT — DO NOT FREEZE.** This proposal authorizes no device command or execution. The exact implementation, command, target and safe output projection require Product Owner review; every execution additionally requires its own exact Product Owner approval under the 2026-09-26 command-approval rule.

## 1. Objective and scope

Provide a small command screen and CLI client for one closed, read-only FortiManager diagnostic template, backed by the same neXus typed-job service. The candidate is `diagnose fmnetwork interface detail <port>` against the enrolled FortiManager represented in AIView as `FW-WHISKEY-02`. V89 records the command as a bounded candidate; it does not authorize execution. In particular, `port5` remains unmeasured and unexecuted until the Product Owner reviews the exact code, command, target scope and sanitized output projection, then separately approves that exact command.

Configured interface state and physical link state remain separate. Neither `UP` nor `RUNNING` proves physical carrier. The physical-link result stays `UNKNOWN` unless official vendor semantics and measured output jointly establish a safe interpretation.

## 2. Fixed boundaries

| Boundary | Contract |
|---|---|
| Command catalog | Server-owned closed template ID and signed-off command-gate row. No command text, shell fragment, host, address, username, credential, argv or path comes from the UI or CLI. Unknown or unsigned templates are refused. |
| Target and port | The caller supplies only an opaque enrolled-device reference and a port selected from that device's server-derived inventory. The server resolves the endpoint and validates the port against the closed port grammar and target inventory. No inferred identity joins. |
| Browser | The screen displays the masked target, exact rendered command, gate limits and output allowlist before approval. It submits typed intent only. It never connects to a device. |
| API/job | UI and CLI call the same authenticated neXus typed backend and admission function. The proposed dedicated route is `POST /api/diagnostic-jobs`; it creates one durable job only after all admission and approval checks pass. This route is not implemented. |
| Approval | A server-side, one-use approval record binds the approver, permitted actor, opaque target, template and gate revision, validated port, code revision, expiry and one job. Approval is consumed atomically with durable job admission. No approval, no job and no device contact. |
| Worker/transport | The worker revalidates the job and approval at claim time, resolves the server-owned command, then sends exactly one command through the existing trusted device transport/session. No new credential or diagnostic network path. No retry. |
| Persistence | Persist only job lifecycle and the allowlisted safe projection. Raw device response exists only in memory for parsing and is then discarded; it is excluded from job records, logs, traces, browser responses and CLI output. |
| AIView | UI evidence and agent review use only the pseudonymized projection (for this candidate, `FW-WHISKEY-02`), safe enums, counts and fixed shape IDs. Opaque/raw identities remain server-side and never enter reports or repository metadata. |
| Readiness | A green preview, ready target, valid gate or available approval is not authorization. Only the exact, current, unused Product Owner approval authorizes admission of its one job. |

### Existing seam and authentication constraint

The current operator console has a module-level closed job registry, authenticated `/api/jobs` submission, a durable job store and a worker runner. Its local authentication is a per-launch bearer token; API routes also check `Origin` and `Sec-Fetch-Site`. It has no `/login`, CSRF-token flow, authenticated role/actor identity or Product Owner approval service. The generic `/api/jobs` body does not carry diagnostic parameters or a one-use approval and is not sufficient for this feature. The proposed typed route must reuse the existing admission, job-store and worker seams while adding explicit refusal checks; it must not claim that the current bearer token proves Product Owner identity or substitutes for approval.

The CLI must call the same typed route and use the same authorization boundary. It must not copy a browser cookie, invent `/login` or CSRF endpoints, or contain device transport code. How the CLI securely obtains the current API bearer and how approver/actor identities are authenticated remain Product Owner decisions (see §7).

## 3. Proposed typed request and safe result

```text
CLI request: fmg-interface-detail --device-ref <opaque-ref> --port <inventory-port> --approval-ref <one-use-ref>
Typed API body: {"template_id":"fmg_interface_detail","device_ref":"<opaque-ref>","port":"<inventory-port>","approval_ref":"<one-use-ref>"}
Server command: diagnose fmnetwork interface detail <validated-port>
Safe terminal result: job=<opaque-job-ref> state=<enum> target=FW-WHISKEY-02
                    command=diagnose fmnetwork interface detail <validated-port>
                    status_present=<bool> status=<UP|DOWN|OTHER|ABSENT>
                    line_count=<bounded-count> shape_id=<closed-enum>
```

`template_id`, target, port and approval reference are typed fields, never command fragments. The command is rendered from the server-owned template only after validation. The UI and CLI show the same preview and terminal projection. The CLI prints the preview before submitting, follows the job to a terminal state, and prints only the safe result or a fixed refusal code. Neither surface displays raw response lines or raw identity values.

The parser may report presence of the `Status` field, a bounded token, line count and a closed structural `shape_id`; it must not preserve a line sample or derive link state from undocumented values. Parse failure yields a safe failure/unknown result, never a guessed status.

## 4. Admission, execution and refusal behavior

1. Authenticated clients read the closed diagnostic template and target/port choices. The server returns only masked target identity and safe preview metadata.
2. The screen or CLI presents the exact command, masked target, port, gate version, timeout, frequency/retry policy and safe output fields. An approval is issued only through the PO-approved mechanism after the required code review and exact command/target/projection review.
3. The typed submission carries the template ID, opaque target reference, validated port, approval reference and idempotency key. It contains no command string, endpoint, credential or transport option.
4. Admission rechecks caller authorization, template/gate status, target enrollment and eligibility, port membership, code revision, approval binding and expiry. It atomically consumes the approval and durably records exactly one queued job before the worker can run.
5. At claim, the worker rechecks current target/template eligibility and the bound job. It invokes the existing trusted transport once, using the exact gate-approved context and timeout. It does not retry. If the result is ambiguous after dispatch or the worker loses certainty whether dispatch occurred, the job becomes `UNKNOWN`/collection-failed and is not replayed under that approval.
6. The worker parses in memory, discards raw output and stores only the approved projection. UI and CLI retrieve the same terminal result through authenticated job reads.

Refuse before job creation and device contact for missing, expired, consumed, revoked or mismatched approval; wrong actor, target, template, gate/code revision or port; unregistered target; stale inventory; unsigned/unsupported template; malformed typed input; failed authentication; or failed authorization. Return a bounded error code without echoing submitted identity or raw device data. An idempotent repeat may return the existing job only when its complete typed request matches; it must never enqueue a second job. A conflicting repeat is refused.

## 5. Command-gate prerequisites

Before implementation or execution, the exact gate row must establish the FortiManager vendor/platform and Expert CLI context, command template, CLASS_0_READ classification, timeout, maximum frequency per endpoint, existing-session reuse, unsupported behavior, secret-output risk and safe telemetry. The worker uses no more than one invocation per approval and no retry. A separate limit across repeated approvals remains to be decided in §7. The V89 measurement is not a standing authorization, and approval of one port/command does not authorize another port, a retry or a future code revision.

## 6. Acceptance criteria

- Unknown templates, arbitrary command/host/argv fields, invalid or stale ports, unauthorized callers, and missing/expired/consumed/revoked/mismatched approvals are refused before any job or device contact.
- Approval issuance and consumption are attributable and auditable; one approval binds one actor, target, template/gate revision, port, code revision, expiry and one job. Concurrent submissions cannot consume it twice.
- UI and CLI use the same typed backend, admission logic, job record and result projection. The browser sends typed intent only; neither client contains device transport code.
- The preview shows the exact server-rendered command, AIView target pseudonym, gate limits and safe output fields before approval. The CLI prints the same preview before submission.
- A valid approval creates one durable job and at most one transport invocation. A matching idempotent retry returns that job without re-enqueue; a mismatch is refused. No failure path retries the device command.
- Job records, logs, traces, browser responses and CLI output contain no raw response, hostname, management address, serial, credential, bearer token or unmasked topology identity. Tests prove the raw response is discarded and only the safe projection is persisted.
- The safe result reports only the allowlisted status presence/token, bounded line count and closed shape ID. It does not claim physical link from `UP`/`RUNNING`; physical-link state remains `UNKNOWN` absent vendor-semantic proof.
- V89's `port5` measurement remains unexecuted until separate review of the exact code, command, target scope and sanitized projection and a separate exact Product Owner approval. Measurement alone cannot establish field semantics.

## 7. Product Owner decisions required before freeze

1. **Approver and actor identity:** What existing identity source proves the Product Owner approver and permitted execution actor? The current local bearer token identifies only possession of a per-launch token and has no role model. Specify whether actor and approver may be the same person and the audit fields retained.
2. **Approval issuance and revocation:** Which PO-controlled UI/API or existing ledger records exact approval and issues/revokes the one-use reference? Define expiry, persistence, atomic consumption and behavior across restart. Ordinary job submission must not mint its own approval.
3. **CLI token handoff:** How does the CLI obtain the current local bearer without `/login`, cookie copying or token persistence? Specify the approved local channel and whether it supports separate attribution from the UI.
4. **Gate frequency:** What maximum frequency applies across multiple separately approved invocations per endpoint? V89 does not supply that value in the cited contract section.
5. **Port validation and result vocabulary:** Confirm the accepted port grammar and whether `Status` tokens are case-normalized by proven vendor semantics. Until decided and evidenced, preserve raw token distinctions only as a closed safe enum or return `OTHER`; never infer physical link.

## 8. Bounded implementation sequence

1. Resolve §7; amend the exact FortiManager gate row and freeze this contract. No device execution.
2. Implement the typed approval record/consumption and dedicated API atop existing authentication, admission, job store and runner seams; prove all refusals occur before job creation.
3. Implement the single gated worker template, one-shot transport and in-memory parser with raw-output discard; add focused privacy and one-use tests.
4. Add the thin UI preview and CLI client over the same API/job/result projection. Validate masked UI rendering and CLI output without device contact.
5. Product Owner reviews the exact implementation and sanitized projection, then separately approves the exact `port5` command/target for any real measurement. Keep physical-link status `UNKNOWN` unless vendor documentation and measured output establish its semantics.
