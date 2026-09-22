# Task Executor / Automation module — contract

## Status

**DRAFT — DO NOT FREEZE.** Backlog `task_executor_automation_module` (Product
Owner P1, 2026-09-22, Backbox "Automations" as the reference shape). This
document guides investigation only; the three decisions in §4 are the Product
Owner's and must be recorded before any implementation of the load-bearing
semantics (AGENTS.md "Contract-status law", "Network action taxonomy").

## 1. What the Product Owner asked for (Backbox reference)

An in-product applet to author and run an ordered script of commands against
chosen firewalls: dynamic fields (title, mandatory, type, default, variable
name, needs-encryption) substituted as `%%VAR%%`; task commands as ordered rows
(type internal|remote|local, command text, timeout, sleep, run-if conditions
with AND/OR, hide output, save output to file, set status success/fail); a
task report; export / import / clone of a task.

## 2. What the constitution already fixes

- **No command originates in the browser** (AGENTS.md "Architectural
  invariants", test-enforced): the console submits typed intent against a
  closed registry. A free-text command editor in the screen contradicts this
  as written.
- **Every device command goes through the network-device command gate**
  before it is issued (vendor, class, shell, timeout, retry, frequency,
  secret-output risk). A command typed at run time has no gate row.
- **Secrets only through the credential store**: `%%PASSWORD%%` as a task
  field is prohibited; a "needs-encryption" field is at most a reference into
  the store.
- **Class**: a task step is CLASS_0 (read) or a write class. Class 1 writes
  exist only through their RB.x contracts; class 2+ is not permitted at the
  current maturity. So a first slice is read-only by law, not by choice.
- **Reuse, not a parallel executor**: the job engine (admission, lease with
  heartbeat, step attempts, audit, drain) and the worker transports
  (exec, interactive shell, fetch, XML API) are the execution path.

## 3. Shape that satisfies §2 (proposed)

**Task packs, not free text.** A task is a repository-owned or admin-authored
*pack*: a YAML document with fields, steps and per-step `gate_reference`,
loaded into the capability registry exactly like `cp_gateway_backup.yaml`. The
screen selects a pack and fills its fields; it never sends a command. Packs
are versioned, signed (COMPLIANCE_CHECK_ENGINE D9) and imported through an
audited admin route — the "author" moment is the pack import, where every step
resolves against a gate row or the import is refused.

**Steps**: `exec` (SSH exec), `interactive` (prompt-synchronised shell),
`xml_api_call`, `fetch`; `sleep`; `set_status`. Conditions: `run_if` on a
field value or a previous step's outcome (`MATCHED` / `EXPECTATION_UNMET`).
Outputs: `discard` (default, raw-evidence law), `artefact` (envelope-encrypted
into the artefact store, listed on the task report), never a device-side file
write in slice 1.

**Variables**: `%%VAR%%` substituted into gated command *templates* whose gate
row names the substitutable positions (as `sha256sum <name>` already does);
a value may only fill a declared position, never extend the command.

**Runs**: one job per (pack version, device); admission checks the pack's
gates are SIGNED_OFF and the actor holds the pack's declared role; the job
executor runs the steps in order with the pack's timeouts; the report is the
job's step attempts plus its artefacts.

## 4. Decisions the Product Owner owns

1. **Authoring surface.** (a) Packs only, imported through an audited route
   (satisfies the invariant as written); or (b) an in-screen editor whose
   saved task becomes a pack on save — this changes "no command originates in
   the browser" and needs a decision record like the download one.
2. **Write steps.** Slice 1 is read-only. Does the Product Owner want a
   class-1/2 slice, and under which approval (per-run PO approval, RB.x-style
   contract per command, or a signed pack whose gate rows are SIGNED_OFF)?
3. **Local steps.** Backbox's "local" (run on the Backbox server) and "internal"
   steps have no counterpart under the host action boundary; propose none in
   slice 1.

## 5. Slice plan (after the decisions)

1. Pack model + import route + registry loading + gate alignment test.
2. Executor (read-only steps, artefact outputs, conditions) + job type
   `task_run` + Jobs screen visibility + CLI (`task-run <pack> <device> k=v…`).
3. Screen: pack list, run dialog with fields, report view.
4. Write-class steps, if decided.
