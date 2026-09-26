# FortiManager approved diagnostic command screen and CLI

**Status: DRAFT — DO NOT FREEZE.** No command in this document is authorized for execution. Product Owner review of the code and individual approval of each diagnostic request are required by `AGENTS.md` (2026-09-26 amendment).

## Objective

Provide a neXus command screen for approved, read-only diagnostic templates, with the same audited backend usable from a Codex CLI. The first candidate is `diagnose fmnetwork interface detail port5` for the enrolled FortiManager represented under `aiview` as `FW-WHISKEY-02`. This is a class 0 read, gated in V89. No device setting, operational state, or credential is changed. The first diagnostic remains unexecuted until the Product Owner reviews the code and approves this exact command.

## Proposed closed path

1. The command screen shows a closed list of signed-off, read-only diagnostic templates. It accepts an opaque enrolled device ID and a port selected from that device's inventory; it does not accept a command string or arbitrary SSH target. It previews the exact command, target pseudonym, maximum duration, and safe output fields before the Product Owner approves a single execution.
2. The existing authenticated neXus service validates the session, CSRF, role, exact approved device/port/command/code revision, and unused approval; it admits one typed diagnostic job and consumes the approval atomically. No approval means no job.
3. The existing worker and SSH transport run exactly `diagnose fmnetwork interface detail <validated-port>` in the device's existing trusted session, once, with a 60-second timeout and no retry. The job records start and terminal state.
4. The worker parses in memory, discards the raw response, and persists only `Status` presence and a bounded `UP` / `DOWN` / `OTHER` / `ABSENT` token, line count, and a masked line shape. It does not infer physical link from `UP` or `RUNNING` flags. The UI and CLI show the fixed command and this sanitized projection after the job reaches a terminal state.
5. A Codex CLI uses the same typed API. It logs in through the existing neXus `/login` endpoint using credentials entered interactively and kept only in process memory, then obtains CSRF from `/session/status`; it never copies the browser's session cookie. It prints the exact command and masked result in the terminal. The CLI contains no device transport code.

## Reviewable command and output contract

```text
neXus CLI request: fmg-interface-detail --device-id <opaque-id> --port port5 --approval <one-use-ref>
device command: diagnose fmnetwork interface detail port5
terminal output: job=<opaque-id> state=<terminal-state> command=<fixed-command>
                 status_token=<UP|DOWN|OTHER|ABSENT> lines=<count> shape=<masked-shape>
```

The CLI never prints a hostname, management address, serial, account, session cookie, raw response, or credential. The approval binds one command to one target and expires without execution. A new port, command, or retry requires a new Product Owner approval.

## Delivery order and acceptance

The first build delivers the shared typed backend, a minimal command screen, and the Codex CLI client. The UI and CLI must not contain device transport code or permit arbitrary commands. The screen renders only under `aiview` masking for agent inspection.

Automated checks must prove refusal when approval is absent, expired, consumed, or mismatched on target/port/command/revision; exactly one job and one SSH command after valid approval; no retry; no raw response in the job record, logs, CLI output, or browser response. The CLI must print the command before submission and follow the job to a terminal state. V89's real port1 output lacked the older example's `Status:` field, so the physical-link verdict remains `UNKNOWN` unless the separately approved port5 read establishes a vendor-supported semantic.

Freezing this design authorizes implementation only. The Product Owner reviews the resulting code and gives a separate `OK` or `NOK` for the exact device command before execution.
