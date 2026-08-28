# PHASE 0.6.1C — Real-Environment Validation Closure

**Status:** REAL_ENV_VALIDATED — 24/24 (2026-08-27)
**Date started:** 2026-08-26
**Product baseline:** 0.6.1D — Check Point Management Intent ↔ Actual Alignment (REAL_ENV_VALIDATED)
**Feature under validation:** 0.6.1C — Discovery Lifecycle + Capability Profile Foundation (AUTOMATED_VALIDATED)
**Engineering baseline:** DEV.1 — Corporate Git + Copilot (ACTIVE)

---

## 1. Objective

Close the human real-environment gate for the already automated-validated
0.6.1C foundation without increasing device interaction, collection frequency,
polling or concurrency.

The validation must demonstrate, through value-free evidence, that:

1. no RuntimeRoot scheduler policy means no scheduled job and no network access,
2. an explicit opt-in policy can launch one bounded allowlisted read-only job,
3. the admitted job records `provenance=scheduled`,
4. overlapping requests for the same physical endpoint coalesce inside the same
   process and do not open a second device connection,
5. the existing collector result remains semantically unchanged,
6. fixed concurrency budgets and existing cooldown behavior remain unchanged,
7. lifecycle/capability/coordinator/scheduler observability remains privacy-safe.

This build validates only behavior that is connected to the normal production
runtime path. Unit-only use of the coordinator or a mock-only validation harness
does not satisfy the real-environment gate.

---

## 2. Evidence-Based Preflight Finding and Closure

The contract-preparation audit originally found a production-wiring gap:

- `CollectionCoordinator`, `load_scheduler_policy()` and `is_workflow_due()`
  are implemented and unit-tested in `utils/collection_executor.py`.
- `RunContext` supports job metadata.
- Discovery UI projection accepts coordinator and scheduler objects.
- Repository-wide Python usage shows no normal runtime/collector call site that
  instantiates the shared coordinator, dispatches scheduled work, or calls
  coordinator admission before a network session.
- `utils/discovery_capability_ui.py` still states that real collectors are not
  wired and safely renders an empty state.
- `main.py` has no scheduler/coordinator runtime entrypoint.

At that checkpoint, the previously documented real-environment command could
not prove scheduled execution or coalescing and network validation was
correctly blocked.

### Consequence

The gap was confirmed and closed by the bounded implementation sub-build:

`0.6.1C.1 — Runtime Admission + Scheduler-One-Shot Wiring Closure`.

Section 5 now passes 10/10 and automated revalidation passes 4/4. The build has
returned to the human real-environment gate; network execution still requires
explicit human approval and the bounded R01–R08 procedure.

---

## 3. Frozen Scope

### In scope

- Read-only source audit of production admission and scheduler call paths.
- A single process-lifetime `CollectionCoordinator` shared by manual and
  scheduled collection paths.
- Coordinator admission before every in-scope network-session opener.
- RuntimeRoot-only scheduler policy loading.
- A deterministic, non-recurring `--scheduler-once` runtime entrypoint if the
  production-wiring audit confirms that no safe equivalent exists.
- Existing allowlisted read-only workflows only.
- Safe `RunContext` provenance/coordinator metadata.
- Same-process physical-endpoint coalescing.
- Value-free acceptance report and privacy checks.
- Targeted, impacted and full regression validation after any wiring change.

### Out of scope

- New device commands or command-semantic changes.
- Device write/change automation.
- Recurring daemon scheduling, polling loops or event/webhook intake.
- New vendors, collectors or credential sources.
- Increased concurrency budgets, shorter collection intervals or reduced
  cooldowns.
- Cross-process/distributed locks, queues, leases or HA scheduling.
- CAS/history/storage migration.
- CP SSH trust or PAN TLS trust provisioning.
- Raw configuration, transport transcripts or operational identities in
  repository evidence.

---

## 4. Non-Negotiable Invariants

1. **Default disabled:** policy absent, disabled or empty means zero scheduled
   job and zero scheduler-originated network session.
2. **Fail before network:** malformed/unsupported policy exits before any
   collector or transport opener is called.
3. **One physical lock key:** VSID/VSYS is context, not a separate physical
   admission key.
4. **One process:** coalescing evidence must come from overlapping requests
   sharing the same coordinator instance. Two independent processes do not
   prove this contract.
5. **No second connection:** a coalesced request must not call a collector or
   transport opener.
6. **Conservative capacity:** existing budgets remain `1` per declared
   vendor/context key. No budget override is introduced in this build.
7. **No recurring activation:** `--scheduler-once` evaluates due work once and
   exits. It does not create a loop, service or background poller.
8. **Collector preservation:** accepted scheduled execution invokes the same
   existing read-only workflow and preserves its result/status semantics.
9. **Explicit uncertainty:** missing identity/capability remains deferred or
   `UNKNOWN`; it is not guessed to gain coverage.
10. **Privacy:** normal manifests/UI/reports contain no credential, raw config,
    target address, hostname, serial, internal domain or transcript.
11. **No raw runtime evidence in Git:** only the value-free report may be
    committed.
12. **Human gate:** network access requires explicit human approval and is
    executed only in the controlled environment.

---

## 5. Production-Wiring Preflight Gate — 10/10 Required

All checks are fail-closed. Any failure blocks network validation.

| ID | Required evidence |
|---|---|
| P01 | Normal process entrypoint creates or receives one shared coordinator instance. |
| P02 | A production scheduler one-shot dispatcher exists; no recurring loop is activated. |
| P03 | Every in-scope CP/PAN network path reaches coordinator admission before opening a session. |
| P04 | Admission uses the known physical management-plane orchestration endpoint; VSID/VSYS stays context-only. |
| P05 | `COALESCED` returns without collector/transport invocation. |
| P06 | Success, exception, cancellation and interruption release/fail the admission exactly once. |
| P07 | Missing/disabled policy produces no job and no network access. |
| P08 | Malformed policy fails before collector/transport invocation. |
| P09 | Scheduled admission writes value-free `RunContext` provenance and decision metadata. |
| P10 | Runtime UI/export receives real lifecycle/capability/coordinator/policy objects rather than unconditional empty defaults. |

### Current preflight result

`PASS — 10/10` (2026-08-26): `main.py` owns shared process-lifetime runtime
services, all in-scope orchestration modes pass admission before collector
invocation, `--scheduler-once` is explicit/default-disabled/fail-closed,
coalesced requests skip the collector, terminal paths release admission, safe
job metadata is written and runtime observability objects reach HTML export.

The lock key is the known management-plane orchestration endpoint (CP
management or Panorama) at this single-process boundary. Collector-internal
downstream per-device and distributed admission are not claimed and remain
deferred to DEPLOY.1. This conservative boundary serializes more work rather
than increasing device interaction.

---

## 6. Bounded 0.6.1C.1 Wiring Contract (Conditional)

Apply this section only if the preflight confirms the current gap.

### Required runtime behavior

- Introduce one shared coordinator at the orchestration boundary; do not create
  one coordinator per collector or per request.
- Route existing manual and scheduled workflows through one admission adapter.
- Resolve the canonical physical endpoint before admission without placing it
  in manifests, UI job rows or logs.
- On `ADMITTED`, execute the unchanged existing workflow and close admission in
  `finally`-safe lifecycle handling.
- On `COALESCED` or rejection, do not invoke the collector/transport.
- Load scheduler policy only from the selected RuntimeRoot.
- Prefer a one-shot CLI contract:
  `py -B main.py --runtime-root <approved-path> --scheduler-once`.
- `--scheduler-once` behavior:
  - absent policy: safe no-op, exit success, no network,
  - disabled/empty policy: safe no-op, exit success, no network,
  - malformed policy: controlled non-zero exit before network,
  - enabled policy: evaluate due allowlisted workflows once, submit bounded
    scheduled jobs, wait for terminal state, then exit,
  - no interval loop, daemon thread or automatic normal-run activation.
- Normal `main.py` behavior without `--scheduler-once` remains unchanged.

### Required tests

- Production entrypoint uses one shared coordinator.
- Each in-scope network opener is unreachable before admission.
- Coalesced/rejected requests never call the opener.
- All terminal and exceptional paths release exactly once.
- Absent/disabled/malformed policy calls no workflow.
- `--scheduler-once` runs due allowlisted work once and exits.
- Provenance and UI receive real runtime objects without identity leakage.
- Existing partial modes and collector result schemas remain unchanged.

No dependency addition, collector command change or network trust relaxation is
permitted.

### Implementation result

`0.6.1C.1` is IMPLEMENTED and AUTOMATED_VALIDATED (2026-08-26):

- one shared `RuntimeCollectionServices` instance for manual/scheduled paths,
- admission wrapper around CP, VSX, CP config probe/collection, PAN runtime and
  PAN config orchestration,
- one-shot scheduler policy/state under RuntimeRoot with no loop,
- bounded coalesced-job terminal wait,
- value-free `RunContext` fields and real runtime Discovery UI inputs,
- unchanged collector commands, worker settings and result schemas.

---

## 7. Automated Revalidation Gate — 4/4 Required

| ID | Required evidence |
|---|---|
| A01 | Existing 0.6.1C targeted suite passes with known skip/xfail posture preserved. |
| A02 | New production-wiring and scheduler-one-shot tests pass if 0.6.1C.1 is required. |
| A03 | Full regression passes after source wiring changes. |
| A04 | Repository privacy gate passes with zero findings. |

Targeted tests first. Full regression is required because orchestration and
shared runtime paths are cross-subsystem boundaries.

Current automated result (2026-08-26): **PASS — 4/4**.

```text
A01/A02 targeted: 102 passed, 1 skipped
A03 full regression: 362 passed, 3 skipped, 2 known xfailed
A04 repository privacy: PASS, 0 findings, 239 files scanned
```

An earlier full-run attempt hit the known intermittent local immutable-store
`PermissionError`; the node passed alone and the final post-fix full regression
passed cleanly. Test-created runtime directories were quarantined outside the
repository without content inspection before the final privacy PASS.

---

## 8. Human Real-Environment Gate — 8/8 Required

Only begin after Sections 5 and 7 are fully green and explicit human approval
is recorded.

| ID | Required observation |
|---|---|
| R01 | Policy absent: one-shot invocation produces zero scheduled jobs and zero network sessions. |
| R02 | Policy present but disabled/empty: zero scheduled jobs and zero network sessions. |
| R03 | Sanitized malformed policy: controlled failure before any network session. |
| R04 | Minimal opt-in allowlisted read-only policy: exactly one bounded scheduled job reaches terminal state. |
| R05 | Accepted job records `provenance=scheduled` and an admitted coordinator decision. |
| R06 | Same-process overlapping request for the same physical endpoint coalesces; transport/session count does not increase. |
| R07 | Scheduled collector result/status matches the equivalent existing manual workflow contract. |
| R08 | Observed concurrency, frequency and cooldown are not higher/faster than the pre-build baseline. |

### Representative scope

Use the smallest already-approved read-only workflow and the minimum endpoint
set that proves the mechanism. This is a mechanism validation, not a fleet
coverage run. Do not select new commands or broaden platform coverage.

### Preferred command rule

No network command is authorized while preflight is blocked. After 0.6.1C.1 is
automated-validated, the preferred command becomes the single one-shot runtime
entrypoint defined in Section 6. The human supplies the approved RuntimeRoot
locally; its value is never copied into chat or repository metadata.

---

## 9. Privacy Gate — 2/2 Required

| ID | Required evidence |
|---|---|
| D01 | Manifest, safe summary, UI/export and committed report contain no secret, raw config, endpoint, hostname, serial, internal domain or transcript. |
| D02 | Runtime policy, logs, output, data and support artifacts remain outside Git; only the value-free report is retained. |

If sensitive local evidence must be inspected, read the minimum field set,
derive boolean/count evidence and do not reproduce the source value.

---

## 10. Acceptance Formula and Status Rules

Promotion is strict:

- Preflight: `10/10`
- Automated: `4/4`
- Real environment: `8/8`
- Privacy: `2/2`
- Total: `24/24` and `100%`

Any `false`, `unknown`, missing check or privacy finding blocks promotion.

Status progression:

`CONTRACT_READY → PREFLIGHT_BLOCKED | PREFLIGHT_PASS → AUTOMATED_VALIDATED → REAL_ENV_VALIDATED → DONE`

- Current status: `REAL_ENV_VALIDATED` (`24/24`; all checks pass).
- `main` merge: **approved**.
- `REAL_ENV_VALIDATED` requires human evidence.
- `DONE` additionally requires durable state update and release handover.

---

## 11. Value-Free Evidence Contract

Use `project/validation/0_6_1c_real_env_report_template.json`.

Allowed fields:

- build/status/date,
- check IDs and booleans,
- aggregate job/session/collector counts,
- provenance/decision/status enums,
- test pass/skip/xfail counts,
- privacy finding count,
- reviewer role and approval boolean,
- sanitized reason codes.

Forbidden fields:

- RuntimeRoot path,
- device/management address or hostname,
- canonical identity if reversible,
- username, credential or auth material,
- raw policy contents,
- raw configuration or transcript,
- full log lines or support artifact payloads.

The report validator must calculate `24/24`; a manually written status string
cannot override a failed or absent check.

---

## 12. Definition of Done

The build is DONE only when:

1. production wiring is evidenced and all preflight checks pass,
2. conditional 0.6.1C.1 wiring is completed if required,
3. targeted/full/privacy automation passes,
4. human-approved real-environment observations pass 8/8,
5. the value-free report passes 24/24,
6. no runtime-sensitive artifact enters Git,
7. current state, roadmap, backlog, feature registry, build history and this
   document are updated consistently,
8. merge decision is explicitly recorded.

---

## 13. Handover Routing

### Immediate next movement

`VALIDATION`: execute only the remaining human-approved R06 same-process
overlap using the smallest already-approved read-only CP workflow. The default
synthetic probe is automated preflight only; R06 requires `--real-env` and one
actual first collector operation. Do not broaden fleet scope.

### Recommended model and reasoning

- **Human gate execution/interpretation:** GPT-5.6 Sol, normal reasoning; elevate only if
  device/session behavior contradicts the frozen contract.

### Git lane

`build/0.6.1c-real-env-validation` with PR target `main` after 24/24 closure.
No push or merge is implied by this contract.
