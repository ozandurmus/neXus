# Task Executor / Automation module — contract

## Status

**FROZEN — 2026-09-22, amended the same day by
`PO_DECISION_RECORD_2026_09_22_AUTOMATION_EDITOR_AND_SCRIPT_EXECUTION.md`:**
steps are authored **in the product** (Operations › Automation), each step
choosing a registered, gated command and filling its declared variable
positions; the pack in §3.1 is the stored form of what the editor saves. §3.2
and §4 row 1 read accordingly. Backlog `task_executor_automation_module` (P1).
A separate, firewall-independent Script Execution module is decision 2 of the
same record and has its own contract.

## 1. What this module is, in one paragraph

An operator writes a **task**: an ordered list of commands to run on one or
more firewalls, with fields the operator fills at run time (`%%VAR%%`), per-step
timeouts, "run this step only if…" conditions, and a report at the end. Backbox
calls this Automations; typical uses are "collect these five diagnostic
outputs from every gateway", "check a setting on all firewalls and flag the
ones that differ", "run the pre-change health checklist". It is the module that
turns neXus from "backup and inventory" into "operate".

## 2. What the constitution already fixes

- **No command originates in the browser** (AGENTS.md "Architectural
  invariants", test-enforced). The console submits typed intent against a
  closed registry.
- **Every device command is gated** (vendor, class, shell, timeout, retry,
  frequency, secret-output risk) before it is issued.
- **Secrets only through the credential store**; `%%PASSWORD%%` as a task
  field is prohibited.
- **Class**: a step is CLASS_0 (read) or a write class; class 1 writes exist
  only under their RB.x contracts; class 2+ is not permitted at the current
  maturity.
- **Reuse, not a parallel executor**: job engine (admission, lease with
  heartbeat, step attempts, audit, drain) and the worker transports.

## 3. The design

### 3.1 Tasks are packs

A task is a **pack**: a YAML document, versioned and signed
(COMPLIANCE_CHECK_ENGINE D9), with `fields`, `steps` and one `gate_reference`
per device step. It is loaded into the capability registry exactly like
`cp_gateway_backup.yaml`: every step resolves against a gate row that is
`SIGNED_OFF`, or the pack is refused at import.

```yaml
pack_id: cp_gateway_health_check
version: 3
vendor: check_point
platform_role_scope: cp_gaia_gateway
required_role: role:operator
fields:
  - name: MIN_FREE_MB
    title: Minimum free space (MB)
    type: integer
    default: 2048
    mandatory: true
steps:
  - id: free_space
    kind: exec
    send: "df -P /var/log"
    gate_reference: cp_backup_df_var_log
    timeout_s: 30
    output: artefact
    status:
      fail_if: "output.available_kb < %%MIN_FREE_MB%% * 1024"
      message: "free space below %%MIN_FREE_MB%% MB"
  - id: cluster_state
    kind: exec
    send: "cphaprob stat"
    gate_reference: cp_inventory_cphaprob_stat
    run_if: "device.cluster_member_ref != null"
    output: artefact
```

Step kinds: `exec`, `interactive`, `xml_api_call`, `fetch` (device), `sleep`,
`set_status` (internal). No "local" (runs on the neXus host) step: the host
action boundary has no authorization form for it.

### 3.2 Who authors, and where

Packs are authored **outside the browser** — in the repository (product-owned
packs) or by an administrator's editor — and **imported through an audited
route** (`POST /automation/packs`, `role:security_admin`, CSRF). The screen
lists packs, shows their steps read-only, fills the fields and runs. This
satisfies "no command originates in the browser" as written. An in-screen
editor whose save becomes a pack is explicitly **not** in this contract; it
would need its own decision record.

### 3.3 Variables

`%%VAR%%` substitutes only into **declared positions** of a gated command
template (the same mechanism `sha256sum <name>` uses); a value may fill a
position, never extend or alter the command. A `type: secret` field is a
credential-store reference, never a literal; the worker resolves it and the
value never appears in a step attempt, a report or a log.

### 3.4 Execution

One job per (pack version, device): job type `task_run`, capability id
`automation:<pack_id>@<version>`. Admission checks the pack's gates are
`SIGNED_OFF`, the actor holds `required_role`, the device is `ENROLLED`. The
worker runs the steps in order: `run_if` on field values and previous step
outcomes (`MATCHED` / `EXPECTATION_UNMET` / `SKIPPED`); per-step timeout from
the pack, bounded by the gate row's; `output: discard` (default, raw-evidence
law) or `output: artefact` (envelope-encrypted into the artefact store, class
`task_output`, listed on the report; content is never surfaced in the UI —
it is downloaded through the audited download route like a backup).
`set_status` sets the job's terminal reason and outcome. Lease heartbeat,
drain and reconciliation as for every job.

### 3.5 Report

The job's step attempts (step id, sent-at, outcome, output bytes and lines,
never content) plus its artefacts, on the Jobs screen and on the pack's own
run history; `jobs-export` CSV covers it. Run "against several firewalls" is
one job per device under one `run_group_id`, so the report can be read per
run group.

### 3.6 Slices

1. Pack model, signature check, import route, registry loading, gate
   alignment test, `nexus-cli pack-import / pack-list`.
2. `task_run` executor (read-only steps, artefact outputs, conditions,
   status), Jobs screen visibility, `nexus-cli task-run <pack> <device|all>
   k=v…`.
3. Screen: pack list, run dialog with fields, run-group report.
4. **Write-class steps: not in this contract.** A pack with a write step is
   refused at import until an RB.x-style contract exists for that command.

## 4. Decisions, as frozen here

| # | question | frozen answer | alternative (needs a decision record) |
|---|---|---|---|
| 1 | Where are commands authored? | Packs, imported through an audited route | In-screen editor whose save becomes a pack |
| 2 | Write-class steps? | No; refused at import | Per-command RB.x contract + per-run PO approval |
| 3 | Local/internal steps (run on the neXus host)? | No | none proposed |

## 5. Tests that must exist before slice 2 ships

- A pack whose step names no gate row, or a gate row not `SIGNED_OFF`, is refused at import with the step id.
- A field value cannot escape its position (`"; rm -rf /"` in `%%NAME%%` is refused, not sent).
- A `type: secret` value never appears in step attempts, reports or logs.
- `run_if` on a previous step's `EXPECTATION_UNMET` skips the step and records `SKIPPED`.
- A `task_run` job heartbeats its lease and survives a rollout (drain).
