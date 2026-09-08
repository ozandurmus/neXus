# External Change Signal Intake & Bounded Evidence Trigger

## Status

**FROZEN — PRODUCT OWNER APPROVED, 2026-09-09 — Slice 1 only.** The Product
Owner's `SESSION_START` for this movement (`relay/NXS-LOCAL-0032`) explicitly
dispatched "design and, as far as feasible ... implement" `event_signal_intake`
as one bounded `IMPLEMENTATION` movement — the same precedent
`docs/design/PRIVATE_REPLAY_ARCHITECTURE.md` ("Phase A only") used. This
document is implementation authority **only** for the scope Slice 1 actually
ships (below). A later slice (new event types beyond the two allowlisted here,
PAN per-device targeting, real network exposure, mTLS/OIDC ingress) requires
its own explicit Product Owner decision and, if it changes load-bearing
semantics here, its own amendment to this document — it is not authorized by
this freeze.

## Gate verification (AC-1)

Backlog `event_signal_intake` (`project/backlog.json`) names three gates.
Verified against source, not assumed:

1. **Collection coordinator — EXISTS AND WORKS.** `utils/collection_executor.py`
   `CollectionCoordinator` / `execute_admitted_collection`, the single admission
   boundary for every collection path (`0.6.1C`/`DEV.3.2`). Its own docstring
   already reserves the trigger vocabulary this movement fills in: *"Job
   provenance is `manual`, `scheduled` or `console` (CON.2). `event` is a
   reserved schema value only; no webhook/event trigger is implemented here."*
   `tests/test_phase0_6_1c_collection_executor.py`: 30 passed locally.
2. **Safe diff (0.6.3) — EXISTS AND WORKS, PAN-scoped.** `utils/config_history.py`
   (`configuration_history_ux`, `project/feature_registry.json`: `status: "done"`).
   CP diff stays `INSUFFICIENT_EVIDENCE` by design (no raw Gaia text diff) —
   unchanged by this movement.
3. **Cross-vendor timeline (0.8.x) — MISSING.** `cross_vendor_change_timeline`
   (`project/feature_registry.json`): `status: "planned"`, criterion `"timeline"`
   `state: "pending"`. No implementation module exists.

**Scope decision from finding 3:** gate 3 is not satisfied. Per this
movement's own requirement ("do not build on a gating prerequisite AC-1 finds
missing — scope down and say so"), Slice 1 does **not** attempt to link a
triggered collection into any cross-vendor change trace or timeline — there is
nothing to link into yet. The `event_signal_intake` feature's own five
acceptance criteria (`project/feature_registry.json`: authenticated ingress,
signal validation, identity resolution, coordinator queue, explicit outcome)
do not themselves require gate 3 — they describe the intake boundary and
trigger, not the downstream investigation surface — so Slice 1 builds those
five while gate 3 stays open for whichever later movement builds the
cross-vendor timeline itself.

A fourth, previously unstated constraint surfaced during design and is
recorded here rather than silently worked around: **no per-device target
seam exists for PAN configuration collection today**
(`utils/collection_executor.py::_TARGET_SEAM_WORKFLOWS` contains `cp-config`,
`recovery-pan`, `recovery-cp` — not `pan-config`; `workflow_argv("pan-config",
targets=[...])` raises `UnsupportedTargetSelectionError`). A signal naming a
PAN device therefore cannot trigger a *bounded* per-device PAN collection
without either running the whole PAN estate (not bounded — rejected) or
extending the PAN collector's target-selection seam (a separate, undispatched
movement). **Slice 1 is Check Point only** for the actual trigger step; a PAN
(or other vendor) `device_reference` still resolves identity honestly but the
signal outcome reports `unsupported_vendor_target_seam` rather than silently
doing nothing or running plane-wide.

## Design

### Principle: signal is trigger-only, never evidence (structural, not documented)

The intake boundary never calls a collector, never writes evidence, and never
constructs collector argv. It does exactly one privileged thing on a fully
validated, identity-resolved, non-duplicate signal: submit a job record to
the **existing, unmodified** `console.jobs.ConsoleJobStore` /
`console.runner.ConsoleJobRunner` job engine (`CON.2`), using the **existing**
`config_refresh_cp` job type from `console/registry.py`'s closed
`JOB_REGISTRY` — the same `device_ids`-targeted, `CLASS_0_READ` job type the
operator console itself submits. This is a deliberate reuse, not new
plumbing: every invariant `CON.2` already proves (single-worker FIFO
execution, coordinator admission via `main.main()`, `M6`/`M7`/`M8.4`
`device_id` → `entity_id` translation and its fail-closed refusal codes,
`console_refusal` taxonomy gating, crash-orphan sweep) applies to a
signal-triggered job identically, with zero duplicated logic. A signal cannot
reach a collector, a credential, or a device by any path this module adds —
the only new capability this movement introduces is *deciding whether to
call `ConsoleJobStore.submit()` at all*, on the same terms the console UI
already does.

The one deliberate, additive, backward-compatible change to that reused
engine: `ConsoleJobRunner` gains an optional `provenance` constructor
parameter (default `Provenance.CONSOLE`, byte-identical to today), and
`utils.coordinator_backend.Provenance` gains the `EVENT = "event"` member its
own comment already reserved. A signal-submitted job is tagged `provenance =
"event"` in every manifest/audit record it produces — distinguishable from a
console-submitted job, never conflated with one. No other line of
`console/runner.py`, `console/jobs.py` or `console/registry.py` changes.

### Request boundary (`signal_intake/app.py`)

One route, `POST /events`. FastAPI, mirroring `console/app.py`'s own
structure (closed route table, strict schema, `HTTPException` for refusal) —
a distinct app/process from the operator console (different caller, different
auth model), never the same ASGI app.

**Authentication — HMAC-SHA256 shared secret**, not the console's
browser-bound bearer-token-in-URL-fragment pattern (`console/auth.py`'s C1-5
model has no meaning for a server-to-server caller with no browser and no
same-origin concept). The secret is pre-shared out of band with the external
system (a Splunk correlation action, in the backlog's own example) —
`SECURITYEXPERT_EVENT_SIGNAL_HMAC_SECRET`, required, no fallback generation
(unlike the console's per-launch token, silently generating this one would
desynchronize from whatever the external system was configured with and fail
closed anyway — so it fails closed explicitly, at first use, with a named
reason instead).

Required headers:

| Header | Meaning |
| --- | --- |
| `X-Signal-Timestamp` | sender's unix-epoch seconds at signing time |
| `X-Signal-Nonce` | sender-generated opaque token, unique per request, ≤128 chars |
| `X-Signal-Signature` | `sha256=<hex hmac>` over `f"{timestamp}.{nonce}.".encode() + raw_body` |

Binding the timestamp and nonce *into* the signed material (not just sending
them as unauthenticated sibling headers) is the one part of this design that
is easy to get wrong and silently lose the replay guarantee — a signature
that only covers the body would let an attacker replay a captured valid
`(body, signature)` pair under a fresh timestamp/nonce pair the server never
validated against the signer's intent. `utils.event_signal_intake.
verify_signature` covers this deliberately.

**Replay protection** (`AC-2`, "nonce/timestamp-window or equivalent"): two
independent checks, both required —

1. `abs(now - timestamp) <= REPLAY_WINDOW_SECONDS` (300s) — a stale or
   future-dated signature is refused regardless of nonce.
2. the nonce has not been seen before, within the same window — a durable,
   fail-closed `state/event_signal_intake_state.json` store
   (`utils.evidence_backend._write_json_atomic`, the same atomic-write helper
   `utils/snapshot.py` already reuses cross-module) records seen nonces with
   an expiry and prunes expired entries on every check. A corrupt state file
   fails the request closed (`SignalIntakeError`), never silently treated as
   "no nonce seen yet."

**Schema allowlist** (`AC-2`): exactly five fields, everything else a 400 —
`signal_id` (caller idempotency key), `event_type` (closed allowlist
`{"policy_install", "config_change"}` — both map to the same `config_refresh_cp`
trigger in Slice 1; a third value is a 400, not a silent no-op), 
`device_reference` (the external device identifier — endpoint/hostname/IP,
normalized the same way enrollment already normalizes one), `vendor_hint`
(optional, must be one of `utils.device_registry.VENDOR_VALUES` if present —
informational/cross-check only, never authoritative over the registry's own
recorded vendor), `observed_at` (optional, informational only — never
consulted for replay/ordering, which is `timestamp`/`nonce`'s job alone).

**Dedup/cooldown** (`AC-2`, distinct from replay protection): even a fresh,
validly-signed, non-replayed signal for the same `(device_id, event_type)`
within `COOLDOWN_SECONDS` (600s — the same 10-minute floor
`utils.collection_executor._MIN_INTERVAL_MINUTES` already enforces for
scheduled polling, deliberately reused rather than inventing a second
polling-frequency policy) does not enqueue a second job — outcome
`ignored_cooldown`, still `200`, never an error the sender should retry.
Replay protection defends against a captured request being resent; cooldown
defends against the sender legitimately firing distinct signals faster than
a bounded evidence-collection plane should be re-triggered.

### Canonical identity resolution (`AC-3`)

`utils.event_signal_intake.resolve_device_id(device_reference, data_root=...)`
reuses `utils.device_registry.normalize_endpoint` (the exact function
`DeviceRegistry.enroll()` and the console's enrollment routes already use) to
normalize the inbound reference, then matches it against
`DeviceRegistry(data_root).list()` by `(endpoint, port)` — the identical
comparison `enroll()`'s own duplicate-detection already performs. No new
identity model, no fuzzy/hostname-similarity matching, no vendor-hint-based
inference (Identity law: opaque identifiers, no invented equivalence). Zero
matches → `unknown_identity`. More than one match is structurally impossible
(the registry's own duplicate-endpoint refusal on `enroll()` prevents it) but
is treated as a fail-closed registry-corruption error, never a guessed pick,
if it is ever observed.

Once a `device_id` is resolved, `console.registry_targets.
resolve_registry_targets` — the exact function `console/app.py`'s own
`POST /api/jobs` calls for `config_refresh_cp` — performs the remaining
eligibility/`M8.4` identity-translation admission check, unmodified. A signal
naming a known-but-ineligible or not-yet-identity-proven device refuses with
the same named reason (`DEVICE_NOT_ELIGIBLE`, `IDENTITY_TRANSLATION_REQUIRED`,
etc.) the console UI already surfaces for the identical condition — one
refusal vocabulary, two callers.

### Trigger (`AC-4`) — coordinator-queued, never a direct write

```
signal validated + identity resolved + admission-eligible + not cooling down
  -> ConsoleJobStore.submit(job_type="config_refresh_cp", targets=[device_id],
                            idempotency_key=f"event-signal:{signal_id}")
  -> ConsoleJobRunner.enqueue(job_id)   # provenance="event"
```

Everything after `enqueue` is unmodified `CON.2`: the single-worker runner
picks the job up asynchronously, re-checks eligibility immediately before
execution (`M6`), substitutes the resolved `entity_id` (`M7`), calls
`main.main()`, which admits through `CollectionCoordinator` before any device
contact. The webhook handler itself returns as soon as the job record is
durable (`queued`) — it never waits for, and cannot itself perform, a device
collection. `signal_id` doubles as the job engine's own idempotency key, so a
sender's own retry of an already-accepted signal returns the original job
record rather than creating a second one — `CON.2`'s existing `C2-9`
contract, inherited for free.

### Explicit signal outcome (`feature_registry.json`'s own fifth criterion)

`POST /events` always returns `200` with a typed outcome for any
authenticated, schema-valid, non-replayed request (never a generic success
flag): `triggered` (job queued, `job_id` included), `ignored_cooldown`,
`unknown_identity`, `unsupported_vendor_target_seam`, or one of
`resolve_registry_targets`'s own named refusal reasons. Authentication,
replay and schema failures are `401`/`400` before any outcome is computed —
a signal that never proves it is who it claims to be, or never parses,
produces no outcome at all, just a refusal.

## Explicitly out of scope for Slice 1

- Any real Splunk (or other) caller — every test uses synthetic, in-process
  (`fastapi.testclient`) payloads only.
- Network exposure beyond an in-process `TestClient` — no `main.py` CLI flag,
  no `signal_intake/server.py`, no bind address of any kind is added in this
  slice. A real deployment needs its own network-exposure decision (this
  surface is reachable by an external system by design, unlike the
  loopback-only console) plus, per `CURRENT_STATE.md`'s standing
  `inventory_exclusions_management_ui_backend` precedent, should not go
  HTTP-reachable ahead of `DEPLOY.1A`'s OIDC/RBAC boundary without an
  explicit Product Owner decision saying otherwise.
- mTLS / OIDC-client ingress (the feature registry's own aspirational
  `authenticated_ingress` wording) — HMAC shared-secret is the mechanism
  Slice 1 actually ships; upgrading it is a later, separate decision, not a
  gap silently left unbuilt.
- PAN (or any non-Check-Point vendor) as an actual trigger target — see gate
  verification above.
- Linking a triggered collection's resulting evidence into a cross-vendor
  change trace — gate 3 is not built.
