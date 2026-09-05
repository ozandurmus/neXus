# Product Control Plane — Architecture (design parent, `PCP.x`)

## Status

**FROZEN — 2026-09-05.** Product Owner reviewed and **approved** the
Product Control Plane product direction and this architecture, conditioned
on exactly two mechanical freeze corrections, both applied by this freezing
session: (1) the registry mutation lock's release step is now instance-safe
(an ownership-token equality check, never a bare path unlink) and the lock
file itself is classified LOCAL-SENSITIVE and kept out of the support
bundle alongside the registry file (§10, §21 "Concurrent CLI write
behavior", AC-5, AC-15); (2) §22's amendment timing is corrected — items 1-3
are applied now, and item 4 (`AI_START_HERE.md`) is moved to the `PCP.1`
close scope because its sentence is only true once `PCP.1` actually ships a
persistent registry. This is a bounded freeze, not a further design review:
no product-direction or architecture content beyond these two corrections
changed. Reconciled against live `main` at `ff700e38` (unchanged since the
draft — `main` has not advanced). The one bounded first implementation
movement (`PCP.1`, §21) carries its own acceptance criteria here so that
freezing this document freezes `PCP.1`'s contract without a second document;
`PCP.1` implementation itself is **not** started by this freezing session
(`AGENTS.md` "Authority hierarchy" item 2, "Contract-status law").

- **Movement:** `ARCHITECTURE` (extended reasoning — new cross-subsystem
  product architecture and a persistence/identity boundary).
- **Design parents preserved unchanged:**
  `docs/design/OPERATOR_CONSOLE_ARCHITECTURE.md` (`CON.0`, FROZEN 2026-08-31),
  `docs/design/FAILOVER_ENGINE_ARCHITECTURE.md` §10/§10.1/§10.2,
  `docs/history/phase/OP_2_0_CONTROLLED_HA_OPERATION_ARCHITECTURE.md`
  (FROZEN 2026-09-04), `docs/design/BACKUP_AND_RECOVERY_ARCHITECTURE.md` +
  `docs/design/BACKUP_RECOVERY_CONTRACTS.md`,
  `docs/design/SERVER_PRODUCTIZATION_AND_MODULARIZATION_ARCHITECTURE.md`,
  `docs/design/COMPLIANCE_ASSIGNMENT_AND_FRAMEWORKS.md` §4b.
- **Reopens:** nothing. No frozen `OP.2`/`CLASS 2`, `CON.x`, `RB.x` or
  evidence law is weakened, and `CLASS 2` stays not implemented and not
  reachable.
- **Authorizes:** no product code, no device command, no schema migration,
  no new credential path, no UI change. Roadmap/state reconciliation only.

---

## 0. How to read this document

This is the **single design parent** for the Product Control Plane. It does
not restate the frozen contracts it builds on; it names them and states how
each control-plane concept sits on top of them. Later `PCP.x` movements get
short phase contracts under `docs/history/phase/PCP_*.md` (or, for `PCP.1`,
§21 here) and bind to this document.

Vocabulary used throughout:

| Term | Meaning here |
| --- | --- |
| **Device Registry** | the persistent set of product objects neXus knows about and may observe/manage |
| **Enrollment** | the explicit act of adding an object to the registry, from a manual entry or a management-plane candidate |
| **Capability** | a product module/action the product can *offer* for a registered object, derived from evidence and implemented vendor support — never a permission |
| **Typed job** | a scheduled or on-demand unit of class 0 (or contract-bound class 1) collection referencing a product capability, never a vendor command |
| **Current projection** | last-known normalized state derived from the latest acceptable evidence, always carrying provenance and freshness |
| **Execution preflight** | fresh evidence gathered inside a controlled-operation workflow (`OP.2.0` P4) — never the projection |

---

## 1. Product direction (Product Owner, promoted 2026-09-05)

neXus moves from a **discovery-driven collection workflow** — every run
rediscovers the estate from the management planes and every product surface
targets whatever the last `unified.json` happened to contain — to a
**persistent product control plane**:

```
SEE → VERIFY → TRACE → RECOVER → OPERATE      (unchanged product axis)

today:      management plane ──discover──► collect everything ──► unified.json ──► report/console
target:     enroll ──► Device Registry ──► capability resolution ──► typed jobs ──► evidence
                                            │                                       │
                                            └──► modules / console  ◄── current projection
```

Discovery becomes an **enrollment source**, not the persistent device
universe. The registry gives the product stable objects to target, schedule,
display and relate. Everything that today already exists — collectors,
evidence stores, readiness authority, recovery plane, console foundations,
vendor adapters — becomes a **control-plane capability** by being *addressed
through the registry*, not by being rewritten (§17).

---

## 2. Product boundary

**The Product Control Plane is:**

- a persistent Device Registry with explicit enrollment and lifecycle;
- a capability-resolution layer that decides which modules and which vendor
  adapter apply to a registered object;
- a typed job plane (definitions, runs, schedules, policies) over registered
  targets;
- current-state projections with honest provenance / freshness / staleness;
- the Operator Console's first-run and per-device experience over all of the
  above.

**The Product Control Plane is not:**

| Not this | Why | Source law |
| --- | --- | --- |
| a generic enterprise CMDB | the registry exists to give neXus targets and relationships, not to be the organisation's asset system of record | PO direction §1; `PROJECT_VISION.md` |
| a security identity authority | a registry record proves that neXus *knows about* an object, never what the object *is right now* | `AGENTS.md` evidence laws: presentation identity != security identity; management-plane observation != direct-device runtime truth |
| a substitute for `OP.2.0` fresh same-workflow preflight | projections are informational; eligibility consumes only the action's own preflight | `OP_2_0` P4, P14 |
| a generic cross-vendor mutation primitive | execution stays behind typed per-vendor adapters | `OP_2_0` P11; `FAILOVER_ENGINE_ARCHITECTURE.md` §10.1 item 9 |
| a browser → device path, a generic REST wrapper, or arbitrary shell | the browser sends typed intent against closed registries only | `CON.0` §3/§4; `SERVER_PRODUCTIZATION…` §1 |
| a reason to raise polling frequency or concurrency | per-device typed jobs still enter through the admission coordinator and its vendor budget of 1 | `AGENTS.md` engineering laws; `CURRENT_STATE.md` |
| a storage-engine decision | the logical stores are defined; the engine choice is recorded as criteria (§10) | PO direction §5 |
| a replacement of `DEPLOY.1` / container migration, or something those may substitute for | `DEPLOY.1` is infrastructure; the control plane is product shape. Neither stands in for the other | PO direction, this session's brief |

---

## 3. Target architecture

```
                 ┌────────────────────────────── Operator Console (CON.x, loopback → CON.6 behind OIDC) ─────┐
                 │  Welcome / Add Devices   ·   Device: Overview · Inventory · Configuration · Backups ·      │
                 │  HA/Failover · Jobs · Diagnostics   ·   (projects server state; decides nothing — P14)    │
                 └───────────────▲────────────────────────────────────────────────────▲───────────────────────┘
        typed intent: job_type/runbook_id + device_id[]     enrollment intent: candidate_id[] | typed manual entry
                                 │                                                     │
┌─────────────────────────────── ▼ ── control plane (vendor-neutral) ───────────────── ▼ ───────────────────────┐
│  Device Registry ◄──── Enrollment providers (manual · Panorama candidates · CP management candidates)          │
│      │  device_id, endpoints, vendor class, credential *reference*, source, lifecycle, tags, relationships     │
│      ▼                                                                                                        │
│  Capability resolution  ──► capability projection per device (inventory · configuration · backup · HA/        │
│      │                       readiness · controlled operations · telemetry · diagnostics) — never authz       │
│      ▼                                                                                                        │
│  Typed job plane  ──► job definition ──► run ──► admission coordinator (per-endpoint lock, vendor budget 1)    │
│      │                                                                                                        │
│      ▼                                                                                                        │
│  Current projections (last-known state · freshness · staleness · UNKNOWN first-class)                         │
└───────────────┬──────────────────────────────────────────────────────────────────────────────────────────────┘
                │ capability/vendor resolution selects exactly one adapter seam
┌───────────────▼──── vendor-specific adapters (existing, unchanged) ──────────────────────────────────────────┐
│ Check Point: cp_runner (MDS/CPRID) · vsx_runner · checkpoint_config_collector · preflight_collector ·          │
│              checkpoint_recovery_collector (RB, class 1) · clusterxl_capability_adapter (OP.2.C, unwired)     │
│ Palo Alto:   panorama_runtime_runner · panorama_config_collector · pan preflight_collector ·                  │
│              panorama_recovery_collector (RB.2)                                                                │
└───────────────┬──────────────────────────────────────────────────────────────────────────────────────────────┘
                ▼
        evidence stores (RunContext manifests · CAS · LKG · recovery store · action records · job records)
```

Three properties of this shape are load-bearing:

1. **Vendor-neutral orchestration above, vendor-specific adapters below.**
   The control plane never learns a vendor command. Capability resolution
   picks an existing collector/adapter; the collector keeps owning its
   transport, identity gate, redaction and command battery.
2. **Nothing existing moves.** The registry, capability layer and job plane
   are *addressing* layers over the collectors, coordinator, evidence
   backends, console and readiness authority that already exist (§17).
3. **The controlled-operation path is untouched.** `utils/operate/` and the
   `OP.2.C` adapter trio remain exactly as frozen; the control plane reaches
   them only as the registry-provided *navigation* to an HA entity whose
   operational identity `OP.2` resolves freshly (§16).

---

## 4. Truth model — four layers that never collapse

| Layer | Says | Owner | May drive | May never |
| --- | --- | --- | --- | --- |
| **ENROLLMENT / REGISTRY** | "neXus should know and manage/observe this resource" | Device Registry | targeting, scheduling, display, relationships | prove identity or runtime state; act as a join key into evidence |
| **EVIDENCE** | immutable, provenanced observations from an actual run | `RunContext` manifests, CAS, recovery manifests, preflight snapshots, action records | everything downstream | be reinterpreted by a projection |
| **CURRENT PROJECTION / LAST-KNOWN STATE** | normalized state derived from the latest *acceptable* evidence, stamped with provenance and freshness | projection builders (today: `snapshot.py` LKG, `*_ui.py` builders, `compute_ha_readiness` over stored telemetry) | dashboards, previews, scheduling heuristics, "stale — recollect" affordances | claim freshness it lacks; feed eligibility, authorization or a mutation |
| **EXECUTION PREFLIGHT** | fresh evidence gathered inside one controlled-operation workflow | `OP.2.0` P4 preflight stage (`checkpoint/clusterxl_preflight_provider.py` for CP) | record creation (acquires the durable operational-entity lock, `OP.2.0` P8 — *before* preflight, not after confirmation) → preflight (inner member admission, held for this device-contact stage only) → eligibility → proposal → confirmation → mutation (inner member admission re-acquired) → verification | be cached, reused across actions, or replaced by a projection |

Rules:

- `UNKNOWN` and `STALE` are first-class projection states, rendered as such.
  A projection with no acceptable evidence is `UNKNOWN`, never empty-looking
  or defaulted. Staleness is *disclosed*, not thresholded here: no numeric
  TTL is invented (the `D-F1` posture of `OP.2.0` P4 extends to the
  control plane — freshness is shown as the observation's own timestamp and
  run provenance; any future "stale after N minutes" display rule is a UI
  policy, never an eligibility input).
- **Cached readiness is informational only.** The existing `OP.0a`
  assessment over stored telemetry — which by construction cannot emit
  `SAFE_TO_FAILOVER` — *is* the control plane's readiness projection.
  Nothing in this document changes that invariant (AC-6 of `OP.0a`).
- A projection may say "last seen `ACTIVE` at run X"; it may never be the
  reason a mutation is permitted.
- **Lock timing corrected to match `OP.2.0` P8 exactly.** The durable
  operational-entity lock (outer) is the action record's own create-time
  uniqueness — acquired at `CREATED`, *before* the preflight stage runs, and
  held until the record is terminal (or, for `OUTCOME_UNKNOWN`,
  acknowledged). Member admission (inner — the existing per-endpoint
  coordinator lease) is a separate, narrower hold taken only for the
  duration of each device-contact stage (preflight; then precondition
  re-observation + submission + verification) and released between them,
  never held across the human confirmation wait. This document does not
  restate P8's full reasoning (crash/quarantine semantics, why a lease
  cannot carry quarantine) — see `OP_2_0_CONTROLLED_HA_OPERATION_
  ARCHITECTURE.md` P8 for that; it only corrects the compressed table cell
  above, which previously implied the lock started after confirmation.

---

## 5. Identity layering — four keys, related, never unified

The repository already carries three identity spaces; the registry adds a
fourth. They are **related by provenanced relationships**, never merged, and
none is derived from another by string manipulation
(`AGENTS.md` "Identity law — identifiers are opaque").

| Key | Space | Produced by | Stable across | Used for |
| --- | --- | --- | --- | --- |
| `device_id` | **registry** (new) | the registry at enrollment — opaque, random, never derived from an endpoint, hostname or serial | the product object's lifetime | targeting, scheduling, display, relationships |
| `canonical_id` | discovery lifecycle / capability profile | the collector (opaque, vendor-scoped, secret-free) — `utils/discovery_lifecycle.py`, `utils/capability_registry.py` | one management-plane view | lifecycle state, capability profile, coordinator lock key |
| `entity_id` (`<device>` / `<device>__vsid_<n>`) | **evidence** | `utils/restore_readiness.resolve_entity_id`, mirroring the CP config collector | one collection convention | inventory/configuration/recovery rows, console job targets today |
| `operational_entity_id` (e.g. ClusterXL `group_id` VIP fingerprint; VSX physical cluster parent) | **operational / HA** | backend topology derivation (`cp_runner.enrich_cluster_topology`, `utils/failover` HA unit derivation) | one topology derivation | readiness units, `OP.2` lock subject |

Consequences:

- A registry record **links to** evidence/operational keys through
  `relationships[]` entries that carry `basis` (`management_discovery`,
  `first_contact_evidence`, `topology_derivation`, `operator_assertion`) and
  the run/provenance that established them. An `operator_assertion` link is
  displayable and schedulable; it is never a security identity and never an
  input to `OP.2` eligibility.
- Manually entered metadata (site, tags, environment, vendor hint) is
  operator annotation. It can filter and group; it cannot classify a device
  as a vendor/platform for adapter selection until evidence confirms it
  (§8).
- Evidence identity stays exactly as today: `presentation identity !=
  security identity`, `evidence identity != operational identity`,
  `management-plane intent != direct-device runtime evidence`. The
  `OP.2.0` identity invariants (host-key trust + hostname match for CP;
  serial gate for PAN; `B₂` NOT ESTABLISHED) are untouched by any registry
  field.

---

## 6. Device Registry

A persistent, RuntimeRoot-resident store of **device records**. Minimum
conceptual shape (field names are illustrative; the *categories* are the
contract):

| Category | Content | Rule |
| --- | --- | --- |
| identity handle | `device_id` (opaque) | never derived; never reused after `RETIRED` |
| management endpoints | one or more management endpoints (management address / FQDN, port, transport class) | LOCAL-SENSITIVE (`PRIVACY_AND_DATA_HANDLING.md` CLASS 2 data); never in the repository, report exports only under existing HMAC tokenization, never in the support bundle |
| vendor / platform classification | `vendor ∈ {checkpoint, paloalto, unknown}`, platform family when established, plus `classification_basis ∈ {operator_hint, management_discovery, first_contact_evidence}` | a hint is never promoted to evidence silently; vendor scope stays CP + PAN (`roadmap.json` architecture_review_notes) |
| credential reference | a **named credential profile reference** resolving, in the engine process only, to the existing `DEV.2.1`/`DEV.2.2` non-interactive credential sources (env vars / mounted files) or the future `credential_profiles` vault abstraction | the secret itself never enters a registry record — enforced by construction (no such field exists), the same pattern `console/jobs.py::JobRecord` uses |
| enrollment source | `manual` · `panorama` · `cp_management`, with the discovery run / candidate reference that produced it | provenance of *why neXus knows this object*, not evidence about it |
| lifecycle / enrollment state | `ENROLLED_UNVERIFIED → CONTACT_VERIFIED → OBSERVED`, side states `DISABLED`, `RETIRED` (exact machine fixed at `PCP.1` contract review; see §21) | distinct from the discovery lifecycle (`DISCOVERED/VALIDATED/STABLE/EXCLUDED/REMOVED`), which describes management-plane trust; the two are related by a relationship, not merged |
| management-plane relationships | which manager (MDS/CMA, Panorama) claims this device; template/device-group or CMA assignment provenance | management-plane observation, kept as intent/provenance |
| site / tags / environment | operator annotations | filters and groups; the *only* registry input the compliance assignment `groups.*.match {"tag": …}` design (`COMPLIANCE_ASSIGNMENT_AND_FRAMEWORKS.md` §4b) will consume — that design's "tagged device registry" is **subsumed by this registry**, not built twice |
| capability projection | resolved by §8, stored as a projection with its basis and run | never authorization |
| last observation metadata | last run id / timestamp / outcome per capability | freshness disclosure, never freshness *guarantee* |
| topology / HA relationships | links to `operational_entity_id`s derived by the backend | display and navigation; `OP.2` resolves the entity freshly regardless |

**Registry does not**: hold secrets, raw evidence, raw configuration,
readiness verdicts (it links to them), or any field the browser could use to
build a device command.

**Registry writes are product-state writes**, a category the repository has
handled once before (`utils/inventory_exclusions.py` add/restore) and gated
behind an authenticated, authorized, audited actor before any HTTP surface.
§13 records how enrollment relates to that precedent.

---

## 7. Enrollment

Default behaviour for every path: **candidate → explicit operator
enrollment**. "Everything the management plane sees becomes authoritative
inventory automatically" is *not* the architecture. A future, opt-in,
trusted-source auto-enrollment policy is possible (open decision
`pcp_auto_enrollment_policy`) but is not the default and is not designed
here.

| Path | Input | Produces | Identity / capability evidence comes from |
| --- | --- | --- | --- |
| **A. Manual** | endpoint (IP/FQDN), credential profile reference, optional vendor hint, optional tags | a record in `ENROLLED_UNVERIFIED` | a **separate, explicit first-contact job** (class 0, existing identity reads only: CP `show hostname` capability handshake, PAN `show system info` serial gate) — never the enrollment write itself |
| **B. Panorama provider** | the *already collected* `show devices all` managed-device list (`panorama_runtime_runner`) | **candidates** (serial-keyed, with Panorama assignment provenance) the operator selects | Panorama = discovery/intent/provenance; direct-firewall identity-gated reads stay the actual/effective evidence (`AGENTS.md` Palo Alto) |
| **C. Check Point management provider** | the *already collected* MDS/CMA `cpmiquerybin` object list (`cp_runner`) | **candidates** (gateway / cluster member / VSX objects, CMA provenance) the operator selects | management-plane discovery; direct-device runtime evidence stays separate (`AGENTS.md` Check Point) |

Rules:

- **No new device command** is needed for B or C: candidates derive from
  artifacts the inventory collectors already produce. Should a provider ever
  want a dedicated discovery read, it goes through the network-device
  command gate first.
- **Using an existing read for a new purpose is a gate item** ("command
  presence in source != command approval"): manual enrollment's first
  contact reuses existing identity reads, so `PCP.2` must record a gate
  entry for that reuse (same command, session, timeout; new purpose) before
  the first-contact job exists.
- **No phantom peer/device creation from unverified metadata.** A member's
  claim about its peer, a Panorama HA-peer field, or a CP cluster object
  produces at most a *candidate* or a *relationship hypothesis*, never an
  enrolled device.
- Candidate selection from the console transmits **candidate ids only**;
  no address leaves the browser (§13).
- Enrollment never contacts a device. First contact is a job, admitted
  through the coordinator like every other collection, and — for production
  — subject to the existing strict trust preflights (`utils/cp_ssh_trust`,
  `utils/pan_tls_trust`) so that a mistyped or hostile endpoint cannot
  receive real credentials silently (see risk §19 / decision
  `pcp_console_registry_write_gate`).

---

## 8. Capability model

A canonical **capability-resolution layer** computes, per registered object,
which product capabilities apply and which vendor adapter serves each:

```
device record  +  established identity/vendor/platform evidence  +  topology evidence
             +  implemented vendor support (which collectors/adapters exist for that vendor/entity kind)
             ─────────────────────────────────────────────────────────────────────────────►  capability projection
```

Capability vocabulary (initial): `inventory`, `configuration_collection`,
`backup`, `ha_readiness`, `controlled_operations`, `telemetry`,
`diagnostics`.

| Rule | Consequence |
| --- | --- |
| Capability is derived from evidence + implemented support, never from a UI label or a user claim | a manually enrolled `unknown`-vendor device exposes **no** vendor capability until first-contact evidence classifies it |
| Capability != readiness != authorization | `ha_readiness` available means "the product can assess this"; `controlled_operations` available means "an adapter exists for this entity kind" — it never means an action is permitted (`OP.2.0` P2/P3, `DenyAllAuthorizer`) |
| Module availability is a projection | the console shows *why* a module is unavailable in words (honest affordance, `CON.0` §9), never a greyed-out button |
| Existing inputs, not new ones | `utils/capability_registry.py` (shell/collection interface profile + `plan_collection`), platform classification from the CP config collector, PAN identity gate outcome, `utils/failover` HA-unit derivation, `utils/action_taxonomy.py` classes and `console/registry.py` job types are the sources; the layer composes them, it does not re-observe |
| `UNSUPPORTED` is explicit and fail-closed | an unresolvable combination yields `UNSUPPORTED(reason)`, mirroring the `OP.2.0` adapter contract |

Worked example (product behaviour, not code):

```
enroll (manual, vendor hint checkpoint)
→ first-contact job: Expert shell confirmed, hostname read, platform = Gaia    (evidence)
→ capability projection: inventory · configuration_collection · backup(RB.3 contract, pilot allowlist) · ha_readiness
→ Inventory / Configuration / Backups / HA modules light up; Diagnostics stays UNSUPPORTED until PCP.8 exists
→ controlled_operations: adapter exists for ClusterXL entity kind → shown as "not permitted: DenyAllAuthorizer" (honest), never as available
```

---

## 9. Job / collection plane

**Enrollment and collection are separate.** Inventory, configuration
collection, backup, telemetry and readiness observation become independent
**typed jobs** with independent schedules and policies.

```
Device Registry ─► job definition {capability, target device_id[], schedule/policy}
              ─► capability/vendor resolution ─► collector / adapter (existing)
              ─► admission coordinator (existing per-endpoint lock, vendor budget 1, coalescing)
              ─► evidence ─► current projection
```

What is preserved verbatim from today's scheduler and console
(`utils/collection_executor.py`, `console/registry.py`, `CON.2`/`CON.5`):

- a **closed job-type vocabulary in source** — a schedule references a typed
  capability/job type, never a vendor command; `ALLOWLISTED_WORKFLOWS` and
  `JOB_REGISTRY` are the seeds the typed vocabulary grows from, reviewed as
  source changes;
- **default-disabled** scheduling, fail-closed policy validation before any
  network access, `interval_minutes >= 10` floor, `--scheduler-once` without
  a loop; the console never becomes the scheduler process (`C5-2`); policy
  editing from a browser stays out until its own gate (`C-D7`);
- **one orchestration path** — every run enters through the same `main()`
  path and the admission coordinator (`CON.2` C2-2/AC-2); no second path;
- **class boundary** — schedulable job types are class 0 and the
  contract-bound class 1 `recovery-pan`; `recovery-cp` stays unscheduled
  (`D3`); `CLASS 2` is never a job type (test-enforced,
  `tests/test_architecture_convergence.py`);
- **read-only scheduling/retry policy is a different universe from `OP.2`
  no-blind-retry** — a job plane retry/backoff policy applies to class 0
  collection only and is never inherited by `utils/operate/`.

What this architecture *adds* (each its own later movement):

- job definitions and runs as durable records keyed by `device_id` targets
  (`PCP.5`); the existing plane-scoped workflows (`cp`, `vsx`, `pan-config`,
  `cp-config`, `recovery-pan`) **retain their existing explicit plane-wide
  scope** — a job invoking one of them still means "collect this whole
  plane," never a silent per-device narrowing;
- **collector target-selection seams** (`PCP.6`): today CP inventory is
  plane-wide (one MDS script over all gateways, exclusions by name); PAN
  runtime is per-serial; recovery collection is already selective. Per-device
  typed jobs need each collector to accept a registry-derived target set.
  This is real work and is recorded as such, not assumed;
- independent cadences for inventory / lightweight health telemetry /
  configuration snapshots / backup / HA-readiness observation /
  compliance-alignment. **No interval is frozen here.**

**Unsupported per-device targeting fails closed, before any device
contact.** A job type that claims per-device targeting against a collector
with no target-selection seam yet is **refused at admission** — the missing
seam is the refusal reason — never silently degraded into "run the whole
plane, then filter the result to the requested `device_id`s." Filtering a
plane-wide result after collection is not targeted execution: it still
contacts every device on the plane (including ones outside the requested
target set, and potentially ones the registry does not even know about),
which is exactly the increased/uncontrolled contact this document's
boundary (§2) forbids and which an audit record naming only the requested
`device_id`s would misrepresent. Until `PCP.6` lands, a per-device job
against a not-yet-seamed collector is `UNSUPPORTED`; the operator retains
the existing explicit plane-wide invocation as a separate, honestly-labeled
option.

Hard constraint carried forward: independent per-device cadences must not
multiply device contact. Aggregate contact frequency per endpoint stays
bounded by the same admission coordinator and the vendor interaction-safety
gate; a job-plane design that would raise it needs that gate's evidence
first (`AGENTS.md` "Do not increase polling/concurrency…").

---

## 10. Persistence architecture

Logical stores the control plane needs (several already exist):

| Logical store | Exists today as | Control-plane status |
| --- | --- | --- |
| Device Registry | — | **new** (`PCP.1`) |
| enrollment / source relationships | discovery lifecycle + capability stores (in-memory per run, serialized to discovery payload) | **new**, linked to the above |
| job definitions | `data/state/scheduler_policy.json` (file policy) | evolves in `PCP.5`; file policy stays a valid input |
| job runs | `RunContext` manifests; `console/jobs.py` job records | reused; runs gain a `device_id[]` target reference |
| immutable evidence / history | CAS (`utils/config_evidence.py`), run manifests, LKG | unchanged |
| current-state projections | `snapshot.py` LKG, `*_ui.py` builders, `data/state/ha_readiness.json` | unchanged mechanism; gains registry keying + freshness fields |
| backup metadata | recovery store manifests (`utils/recovery_manifest.py`), encrypted artifacts (`utils/recovery_store.py`) | unchanged |
| operation / action / audit state | `utils/operate/store.py` action records, `OperationalWriteLedger`, console job records | unchanged |
| capability projections | `utils/capability_registry.py` profiles (per run) | persisted per `device_id` from `PCP.3` |

**Seam:** every durable concern above already flows through
`utils/evidence_backend.py` — seven abstract backends, each with a
filesystem default and an opt-in PostgreSQL implementation. The Device
Registry becomes the **eighth storage concern** of that abstraction. That is
a seam decision, not an engine decision.

**Engine decision deferred** (`pcp_storage_engine`). PostgreSQL is already
an opt-in path in this repository (DEV.3.2/DEV.3.3); that fact does not
decide the control plane's engine. Criteria the eventual decision must
answer, recorded now:

- transactions (enrollment + relationship writes atomically);
- migrations (versioned, deployment-controlled — `DEV.4.6` precondition
  before any production database role; runtime schema creation is already
  flagged as pre-production only);
- concurrent workers (single worker today; `DEV.3.4` deferred);
- uniqueness / locking (one active record per endpoint per vendor; the job
  plane's "one run per definition in flight");
- append/history workloads (job runs, observation history) vs. small
  mutable registry rows;
- retention (job-run history, projection history);
- JSON / structured evidence needs (relationship and capability
  projections are structured; payload blobs stay on the volume — CAS and the
  recovery store never move);
- deployment topology (laptop RuntimeRoot today; container volume;
  `DEPLOY.1` server);
- backup / recovery of neXus's own state (the registry is now product state
  worth restoring — off-host custody joins `recovery_offhost_key_custody`);
- enterprise operation requirements (role separation, TLS DSN, audit
  retention — `PRIVACY_AND_DATA_HANDLING.md` "Distributed evidence store").

Invariants preserved: RuntimeRoot / repository separation
(`utils/runtime_paths`); secrets never in product records — references only;
the registry **and its mutation lock file** are LOCAL-SENSITIVE data and
neither enters the support bundle (which reads only enumerated run
artifacts) or the repository.

---

## 11. Backup product model

Backup becomes **capability-driven** on top of the frozen recovery
contracts, without redesigning them:

| Layer | Already defined by | Control-plane role |
| --- | --- | --- |
| backup capability | `RB.2` (PAN device-state export), `RB.3a/RB.3b` (CP Gaia attestation / backup, D3 pilot allowlist, D4 distinct credential) | resolved per device by §8 from vendor/platform evidence + contract state |
| scheduled / on-demand backup job | `recovery-pan` allowlisted workflow; `recovery-cp` deliberately not (`D3`) | a typed `backup` job whose schedulability is the contract's, not the UI's |
| immutable backup metadata / provenance | recovery manifests (`BACKUP_RECOVERY_CONTRACTS.md` §3) | linked from the device record as last-observation metadata |
| encrypted artifact | recovery store (AES-256-GCM envelope, separate root) | unchanged; never reachable over HTTP (`CON.0` §7.6) |
| validation | `RB.4` V1–V4 | unchanged |
| restore / recovery workflow | `RB.6`, hard-gated at the `OP.2` bar | unchanged; explicitly not designed here |

Action taxonomy preserved: collecting a backup is class 1 under its `RB.x`
ledger contract; it is never permission for a recovery write. The operator
does not choose SSH/API commands for a supported platform today (closed
registry) and will not under the control plane.

---

## 12. Topology and HA operational units

"Device" and "failover unit" stay distinct objects:

- a **registered device** is the persistent product object (§6);
- a **failover / readiness operational unit** is *derived* from topology
  and evidence by the backend, and linked to devices through relationships.

Validated semantics preserved verbatim:

| Vendor / mode | Readiness / operational domain | Source |
| --- | --- | --- |
| Check Point classic ClusterXL | the physical cluster (`group_id` VIP fingerprint) is the HA/readiness domain and the `OP.2` lock subject | `cp_runner.enrich_cluster_topology`; `OP_2_0` P8 |
| Check Point VSX / VSLS | the physical VSX cluster is shared substrate; under VSLS each VSID can be an independent **readiness** domain (`OP_0B_S4A_VSX_PER_VS_FAILOVER_DOMAIN_REVIEW.md`); the **CLASS 2** action target remains the physical cluster parent, VSID is never a lock subject (`FAILOVER_ENGINE_ARCHITECTURE.md` §10.2, `op_aa_vsls_scope`) | unchanged |
| Palo Alto | the HA pair is the current failover/readiness domain; VSYS is subordinate context; management endpoint and HA1/control-link planes are distinct; current read-only pair correspondence (S8-C management-plane `MATCH`) is preserved; stronger CLASS 2 PAN identity (`B₂`, `D-V3a`) remains separately gated at `OP.3` | unchanged |

The console may persist and display **last-known topology projections**
with freshness. A CLASS 2 workflow freshly resolves and validates the
operational entity before mutation; the registry's relationship is the
starting point of navigation, never the resolved target.

---

## 13. Operator Console

The Product Control Plane's console **is** the `CON.x` Operator Console —
the same second delivery surface, the same one UI source tree, the same
intent boundary — extended with the registry-driven experience. Nothing in
`CON.0` §3 ("what this is not"), §4 (intent boundary), §7 (security model)
or §10 (phasing) is relaxed. Completed `CON.1`/`CON.2` work is preserved.

First-run experience (product interaction model, not an immediate build list):

```
Welcome / Add Devices
   ├── Add manually                      (decision-gated, see below)
   ├── Discover from Panorama            (candidates from the existing Panorama collection)
   └── Discover from Check Point Management (candidates from the existing MDS/CMA collection)
Device / resource
   Overview · Inventory · Configuration · Backups · HA / Failover · Jobs · Diagnostics
```

Boundary rules, restated for the new intents:

| Intent | Browser sends | Server does | Boundary |
| --- | --- | --- | --- |
| device job (today's `CON.2`) | `job_type` + `device_id[]` (replacing `entity_id[]` once the registry is the target universe) | resolves ids against the registry (stricter than today's `unified.json` presence check — explicit enrollment), builds argv from a fixed template | unchanged `CON.0` §4 |
| enrollment from candidates | `candidate_id[]` | resolves candidates from the last provider run, **would** write registry records | satisfies `CON.0` §4's *command-construction* wording (no hostname/address crosses the wire) but that is **not** the same question as whether a persistent product-state write may reach the console before `DEPLOY.1A`. A closed candidate id narrows *what* could be written; it does not by itself authorize *that* a write happens pre-`DEPLOY.1A` — the `inventory_exclusions_management_ui_backend` precedent ("write access controls which devices get polled — wait for `DEPLOY.1A`") applies to this intent exactly as it does to manual enrollment. **Gated by `pcp_console_registry_write_gate` (§19), same as manual enrollment below; not decided by this document.** |
| **manual enrollment** | a typed, schema-validated entry: endpoint, credential *profile reference*, vendor hint, tags | validates syntax, **would** write a registry record in `ENROLLED_UNVERIFIED`; **contacts no device in this request** | **contradicts `CON.0` §4's literal wording** ("the browser never transmits … a hostname, an address") and touches the same `inventory_exclusions_management_ui_backend` precedent. **Gated by `pcp_console_registry_write_gate` (§19), covering both enrollment intents in this table** — until decided, both manual and candidate-based enrollment ship CLI-first (`PCP.1`/`PCP.2`) and the console form for either waits |
| HA / failover tab | nothing new | shows the last-known readiness projection with explicit freshness; **"Start failover" begins a NEW `OP.2` workflow with its own authoritative preflight** | `OP_2_0` P4/P14; the UI never computes identity, topology, pairing or readiness |
| Diagnostics | `runbook_id` + `device_id[]` (future, `PCP.8`) | resolves a closed runbook catalog | §15 |

Canonical backend authority remains the only authority. Payload parity
between the exported report and the console (`CON.0` §6 invariant) holds:
if the device experience needs a field, the payload builder gains it for
both surfaces, and the report stays action-free.

---

## 14. Fast telemetry plane (future; SNMPv3 candidate)

Architectural slot only — **no SNMP code, MIB list, interval or credential
handling is designed or authorized here.**

- Purpose: inexpensive periodic observation of availability, uptime,
  interface state/counters, resource health, and vendor MIB facts *whose
  semantics are explicitly established* (vendor semantics law applies to
  every OID exactly as to every CLI field).
- SNMPv3 is **never**: authoritative running configuration, authoritative
  direct-device identity, automatic failover eligibility, or a replacement
  for vendor-specific authoritative preflight evidence.
- Pattern: `telemetry/event detects material change → projection marked
  changed/stale → targeted authoritative collector job` — the same
  "signal is a trigger, never evidence" rule `event_signal_intake` already
  carries; traps/informs are an extension of that intake, not a first-slice
  requirement.
- Diagnostic-path law: SNMPv3 is a **new credential and network path**, so
  it requires its own security review, command/OID gate, and secret handling
  (SNMPv3 auth/priv material is CLASS 3 — never in records, logs, bundles).
- Placement: `PCP.7`, optional, after the typed job plane exists.

---

## 15. Diagnostic Runbooks (future; CLASS 0 only)

Bounded, ordered, read-only diagnostic sequences an operator runs against
selected registered targets. Architectural requirements:

- a **closed runbook catalog in source** (the `JOB_REGISTRY` posture),
  each step a command that has passed the network-device command gate for
  *this* purpose; vendor/context-aware execution over the existing
  transports (Expert/Clish, `vsenv <VSID>`, PAN XML API);
- explicit timeout / total runtime, output size limits, deterministic
  framing, evidence capture with secret redaction, audit record;
- command classification: initial scope is `CLASS_0_READ` only; any step
  that would be class 1+ is rejected by the catalog validator, exactly as
  `CE.1` rejects a remediation block today;
- **never** `browser → arbitrary shell`, never user-authored scripts as the
  first implementation; a later, explicitly gated bounded-script capability
  would be its own contract, and CLASS 2/3/4 mutations continue through
  their own typed contracts regardless.
- Reuse: the `CE.2` "curated read-only command-primitive registry" and the
  runbook step catalog are the **same primitive registry**, not two —
  recorded so the two designs converge rather than fork.
- Placement: `PCP.8`.

---

## 16. Relationship to `OP.2` / `CLASS 2`

Nothing here reopens or weakens `OP.2.0`. Preserved unchanged: typed
intent; the `authorize()` boundary (unconditional `DENY` until `DEPLOY.1A`);
fresh same-workflow preflight (P4); eligibility; digest-bound explicit
confirmation (P5); the operational HA-entity lock as record uniqueness
(P8); the durable mutation boundary (P6); exactly-one submission, no blind
retry (P7); `OUTCOME_UNKNOWN` quarantine (P10); independent post-action
verification; immutable identity-lean audit (P13); reversal/failback as a
new typed CLASS 2 action, no automatic rollback (P12); the CP pilot
readiness-policy amendment (`OP.2.1b`: `preemption_known`/`flap_history`
advisory-exempt, `D-F2` retired); CP ClusterXL first, PAN at `OP.3`.

What the control plane contributes to `OP.2`: **stable enrolled targets and
product UX around it** — the device/HA tab is where `OP.2.D`'s proposal →
confirmation → lifecycle → outcome → acknowledgement flow should live, so a
second console is never built. It contributes **no** identity, no
eligibility input, no authorization and no execution authority. The
remaining `OP.2.C` release gates (`DEPLOY.1A` OIDC + `OPERATE`, production
SSH trust hardening, the signed change-management review, the protected
entry point) are untouched and remain externally blocked on `DEPLOY.1`.

---

## 17. Existing capabilities that become control-plane capabilities (no rewrite)

| Existing capability | Location | Becomes | Change required |
| --- | --- | --- | --- |
| CP inventory (MDS → CPRID) | `checkpoint/cp_runner.py` | `inventory` capability + Check Point management **enrollment provider** input (`cpmiquerybin` object list → candidates) | none for candidates; target-selection seam at `PCP.6` |
| VSX inventory | `checkpoint/vsx_runner.py` | `inventory` for VSX host + VS relationships | none initially |
| PAN runtime inventory | `panorama/panorama_runtime_runner.py` | `inventory` + Panorama **enrollment provider** input (`show devices all` → serial-keyed candidates) | none for candidates |
| CP / PAN configuration collectors | `configuration/` | `configuration_collection` | target-selection seam (`PCP.6`) |
| Discovery lifecycle + capability profiles | `utils/discovery_lifecycle.py`, `utils/capability_registry.py` | inputs to capability resolution and the discovery↔registry relationship; lifecycle `DISCOVERED` records are the natural candidate set | linking only |
| Admission coordinator + scheduler | `utils/collection_executor.py` | the job plane's admission and its seed vocabulary | additive typed job definitions (`PCP.5`) |
| Evidence backends | `utils/evidence_backend.py` | persistence seam; registry = eighth concern | additive backend |
| CAS / history / LKG / verification | `utils/config_evidence.py`, `config_history.py`, `snapshot.py`, `verification.py` | evidence + projection layers | none |
| HA readiness assessment (`OP.0a/0b/0c`) | `utils/failover/`, `checkpoint/preflight_collector.py`, `panorama/preflight_collector.py`, `utils/failover_readiness_ui.py` | `ha_readiness` capability; the readiness **projection** (stored-telemetry assessment) and the fresh preflight remain distinct layers | none; keying by `device_id` relationship at `PCP.3`/`PCP.5` |
| Recovery plane (`RB.0`–`RB.4`) | `utils/recovery_*`, `checkpoint/checkpoint_recovery_*`, `panorama/panorama_recovery_collector.py` | `backup` capability | none |
| Operator Console (`CON.1`/`CON.2`) | `console/`, `static/console_actions.js` | the control-plane console; registry-driven navigation and new modules | additive UI (`PCP.4`) behind the same intent boundary |
| `CLASS 2` foundation + CP ClusterXL adapter trio (`OP.2.A/B/C`, unwired) | `utils/operate/`, `checkpoint/clusterxl_*` | `controlled_operations` capability *presence* (never permission) | none; stays unwired |
| Action taxonomy | `utils/action_taxonomy.py` | the class vocabulary every capability, job type and runbook step declares | none |
| Inventory exclusions | `utils/inventory_exclusions.py` | a registry `DISABLED` state or exclusion relationship; write path stays `DEPLOY.1A`-gated unless `pcp_console_registry_write_gate` decides otherwise for enrollment specifically | reconciliation at `PCP.1` contract review |

Nothing in this table is discarded, duplicated or rewritten.

---

## 18. Reconciliation with current `main` (`ff700e38`)

Each row is classified as **CONTRADICTION** (a frozen law or decision the
direction would violate — reported, never silently reconciled) or
**SUPERSEDED ASSUMPTION** (an implementation-level assumption the product
architecture legitimately replaces).

| Area | Current repository position | Classification | Resolution |
| --- | --- | --- | --- |
| "not a CMDB" | no repository statement; `COMPLIANCE_ASSIGNMENT_AND_FRAMEWORKS.md` §4b already wants a "first-class device registry with operator tags/groups", `DEPLOY.1`-gated | no conflict | this registry subsumes §4b's; backlog `compliance_assignment_ui_and_registry` keeps only its assignment-editor half |
| Discovery / current-inventory assumptions | `unified.json` is the device universe: console targets validated by presence in it (`CON.0` §4, `resolve_entity_id`), recovery targeting and readiness units derive from it | **SUPERSEDED ASSUMPTION** | the registry becomes the target universe; presence-in-`unified.json` is replaced by an equal-or-stricter check (explicit enrollment); evidence identities (`entity_id`) remain the evidence keys and are *related* to `device_id`, never replaced |
| RuntimeRoot / state persistence | `data/state/*` files; `evidence_backend` abstraction; repo↔runtime separation enforced | no conflict | registry lives under RuntimeRoot through the same abstraction |
| Backup architecture | frozen `RB.x` contracts; `recovery-cp` unscheduled (`D3`); restore hard-gated | no conflict | capability-driven addressing only (§11) |
| Console architecture | `CON.0` §4 literal "browser never transmits … a hostname, an address"; `C-D7`; exclusions write path `DEPLOY.1A`-gated | **CONTRADICTION**, covering *both* enrollment intents: manual enrollment against §4's literal wording, and candidate-based enrollment against the exclusions-write precedent (a closed candidate id still authorizes a *persistent product-state write*, which §4's wording does not by itself settle) | open decision `pcp_console_registry_write_gate`, widened to cover both intents; existing device jobs (`job_type` + `device_id[]`, no write to the registry) are unaffected and fit §4 verbatim; both manual and candidate-based enrollment ship CLI-first (`PCP.1`/`PCP.2`) until decided |
| HA identity / topology authority | backend-only derivation; UI heuristics retired (`OP.0b S9`) | no conflict | registry relationships are backend-derived projections; the UI still computes nothing |
| Readiness freshness laws | `OP.0a` cannot emit `SAFE_TO_FAILOVER`; `OP.2.0` P4 same-workflow, no TTL | no conflict | projection layer = informational; no TTL introduced |
| `OP.2` operational identity and lock scope | `OP.2.0` P8/P17; `OP.2.1b` | no conflict | untouched (§16) |
| Scheduler / job assumptions | plane-wide workflow names, file policy, `>= 10 min`, default-off, `--scheduler-once`, `C5-2`, `C-D7` | **SUPERSEDED ASSUMPTION** (workflow = whole plane) with every safety property preserved | typed per-capability jobs over registry targets; collector target-selection seams are recorded as real work (`PCP.6`) |
| Command-execution anti-requirements | no browser → device; closed registries; no arbitrary shell; `CE.1` rejects remediation blocks | no conflict | runbooks = closed catalog, class 0, same primitive registry as `CE.2` |
| Support bundle / privacy / credential rules | HMAC tokenization; secrets never in records; `DEV.2.1/2.2` credential sourcing; `credential_profiles` planned (1.0) | no conflict | credential *references* only; registry never enters the bundle; `credential_profiles` becomes the reference model the registry points at |
| Vendor scope | CP + PAN only | no conflict | registry vendor enum is CP / PAN / unknown |
| Roadmap ordering | `now_next.next` = `op2_c_cp_clusterxl_adapter_scoping` (blocked, external) | ordering change, not a demotion of completed work | `OP.2.C` scoping moves to `upcoming` (still `blocked` on `DEPLOY.1`, notes preserved); `PCP.1` becomes `next`, gated on this document's freeze |

No frozen law was removed or reinterpreted to make the direction fit. The
one genuine contradiction is isolated to a single console enrollment-write
boundary — covering both the manual and the candidate-based enrollment
intents together (§13, §19) — and is left to the Product Owner as an
explicit decision.

---

## 19. Classification

### FROZEN / PRESERVED LAWS (unchanged by this document)

- All `AGENTS.md` evidence, identity, UNKNOWN/fail-closed, vendor-semantics,
  diagnostic-path, raw-evidence and privacy laws.
- `OP.2.0` P1–P18 and the `OP.2.1`/`OP.2.1b` decisions; `CLASS 2` memberless
  and unreachable; `utils/failover/` allowlist; `utils/operate/` convergence
  assertions.
- `CON.0` §3/§4/§6/§7/§10; `CON.1`/`CON.2` as shipped; `C-D1`…`C-D8` as
  recorded; `C5-2`; `C-D7`.
- `RB.x` contracts; `D3`/`D4`; `recovery-cp` unscheduled; `RB.6` hard gate.
- Validated CP ClusterXL / VSX-VSLS / PAN HA semantics (§12).
- Admission coordinator, vendor budget 1, no polling/concurrency increase
  without the interaction-safety gate; `>= 10 min` scheduler floor.
- `DEPLOY.1` gate set; `DEPLOY.1A` as the authorization boundary for
  `OPERATE` and for any HTTP-reachable product-state write not otherwise
  decided.

### PO-APPROVED PRODUCT DIRECTION (made durable here)

- Persistent Device Registry with explicit enrollment; discovery = enrollment
  source; candidate → explicit enrollment as default; no auto-enrollment by
  default; no phantom devices.
- Capability-driven modules; capability != readiness != authorization.
- Four-layer truth model with `UNKNOWN`/`STALE` first-class; cached readiness
  informational only.
- Independent typed jobs per capability over registry targets; closed
  vocabulary; separate from `OP.2` retry semantics.
- Capability-driven backup on the existing recovery contracts.
- Device != failover unit; validated HA semantics preserved.
- Thin first-run onboarding + device experience in the existing console;
  `OP.2.D` UI lives there.
- Future fast telemetry (SNMPv3 candidate) and Diagnostic Runbooks
  (CLASS 0) as architectural slots with the anti-requirements above.
- Sequence and IDs in §20; `PCP.1` as the first bounded movement.

### IMPLEMENTATION-LEVEL DECISIONS DEFERRED (deliberately not frozen)

- Storage engine (`pcp_storage_engine`, criteria in §10); exact schema /
  column names; migration tooling.
- Exact polling intervals and per-capability default cadences.
- Specific SNMP MIBs/OIDs, SNMP library, trap/inform transport.
- UI framework (none — the one-inline-script invariant stands) and exact
  module layout of the device experience.
- Scheduler technology (in-process evaluator vs external timer stays as
  today until `PCP.5`).
- Exact registry lifecycle state names and relationship `basis` vocabulary
  (fixed at `PCP.1` contract review, §21).
- Collector target-selection mechanics per collector (`PCP.6`).

### OPEN DECISIONS (Product Owner; tracked in `project/roadmap.json` `open_decisions`)

| id | Question | Recommendation | Decide by |
| --- | --- | --- | --- |
| `pcp_console_registry_write_gate` | May the loopback console accept *any* enrollment write — manual (endpoint + credential reference) **or** candidate-based (closed `candidate_id[]` from a prior discovery run) — before `DEPLOY.1A`, given `CON.0` §4's wording and the `inventory_exclusions_management_ui_backend` precedent that a persistent product-state write controlling which devices get polled waits for `DEPLOY.1A`? A closed candidate id narrows *what* could be written, not *whether* a pre-`DEPLOY.1A` write is authorized — the two enrollment intents raise the same authorization question and are decided together, not separately. | No pre-decision for either intent. Three options for the `PCP.4` review: (a) neither ships from the console before `DEPLOY.1A` — both stay CLI-first through `PCP.1`/`PCP.2`; (b) candidate-based enrollment only, permitted pre-`DEPLOY.1A` on the strength of the closed candidate id plus typed confirmation + audit record, manual entry still waits; (c) both permitted pre-`DEPLOY.1A` with typed confirmation + audit record + mandatory strict first-contact trust preflight for any resulting device. Recommendation deferred to the security lead's reading of the exclusions precedent; **CLI-only enrollment in `PCP.1`/`PCP.2` does not depend on this decision and proceeds regardless.** | `PCP.4` contract review |
| `pcp_auto_enrollment_policy` | Should a future opt-in "trusted management source auto-enrolls" policy exist, and under what audit/allowlist? | Not in the first slices; design only after `PCP.2` shows real candidate volume; default stays explicit | `PCP.2` closure |
| `pcp_storage_engine` | Which engine backs the registry/job plane in production, against the §10 criteria? | Defer; keep the eighth `evidence_backend` concern filesystem-first; decide with `DEV.4.6` migrations/roles | `PCP.5` contract freeze |
| `pcp_first_contact_trust_policy` | Must the first-contact job for a *manually* enrolled endpoint require strict host-key / CA trust even in the local development profile, to prevent credential exposure to a mistyped or hostile endpoint? | **Yes for any endpoint not corroborated by a management-plane candidate**; compat mode stays available only for candidate-corroborated endpoints in the dev profile | `PCP.2` contract review |

---

## 20. Roadmap reconciliation and movement sequence

**Completed `OP.2` work stays where it is and is not moved backward:**
`OP.2.0` FROZEN; `OP.2.A/B` DONE; `OP.2.1` gate DRAFTED/approved for
`OP.2.C`; `OP.2.1b` amendment IMPLEMENTED; `OP.2.C` adapter, member
session, preflight provider IMPLEMENTED and unwired; the change-management
review package DRAFTED, unsigned. `OP.2.C` wiring / `OP.2.D` pilot stay
`blocked` on `DEPLOY.1` (external) exactly as before — they simply stop
occupying `now_next.next`, which the horizon contract reserves for one
build, because a `blocked`-external row is not an actionable "next".

New roadmap track **`PCP.x — Product Control Plane`** (theme `OPERATE`, the
product-shape work that carries `SEE`…`OPERATE` into a persistent product).
Movements, each independently buildable, testable and mergeable, each to be
written later as a **short goal-oriented prompt with focused tests** — not
expanded here:

| Movement | Scope (one line) | Depends on | Tier |
| --- | --- | --- | --- |
| **`PCP.0`** | this architecture; PO review → FROZEN | — | done by this session (draft) |
| **`PCP.1`** | Persistent Device Registry + manual enrollment foundation (CLI, backend, tests; no UI, no device contact) — **§21** | `PCP.0` frozen | `Sonnet 5, normal` |
| `PCP.2` | Panorama + Check Point management **enrollment providers**: candidates from existing collection artifacts → explicit enrollment; first-contact job over existing identity reads (gate entry for the reuse); `pcp_first_contact_trust_policy` | `PCP.1` | `Sonnet 5, normal`; extended only for the trust-policy decision |
| `PCP.3` | **Capability resolution + module activation**: capability projection per `device_id` from existing evidence inputs; `UNSUPPORTED` explicit | `PCP.1` (+`PCP.2` for evidence-classified devices) | `Sonnet 5, normal` |
| `PCP.4` | **Thin first-run onboarding + device experience** in the console (Add Devices from candidates; per-device tabs as projections over existing payloads; render harness; payload parity); `pcp_console_registry_write_gate` decided here | `PCP.2`, `PCP.3` | `Sonnet 5, normal` (UI); extended for the write-gate decision |
| `PCP.5` | **Typed job plane + current-state projections**: job definitions/runs keyed by `device_id`, independent cadences, freshness/staleness fields, Jobs tab; `pcp_storage_engine` decided | `PCP.1`, `PCP.3` | `Sonnet 5, extended thinking (high)` for the contract; normal to implement |
| `PCP.6` | **Independent inventory / configuration / backup / readiness jobs**: collector target-selection seams, backup capability-driven via `RB.x` | `PCP.5` | `Sonnet 5, normal` per collector |
| `PCP.7` | **Optional fast telemetry plane** (SNMPv3 candidate): own security/command/OID gate first | `PCP.5`; its own gate | extended (new credential/network path) |
| `PCP.8` | **Diagnostic Runbooks** (CLASS 0, closed catalog, shared primitive registry with `CE.2`) | `PCP.3`; per-step gate rows | extended for the catalog contract; normal to implement |
| (`OP.x`, unchanged) | remaining product wiring for the first controlled ClusterXL pilot (`OP.2.C` wiring, `OP.2.D` console flow on the `PCP.4` HA tab) | `DEPLOY.1A`, SSH trust hardening, signed review, `PCP.4` | as recorded in `OP.2.0` |

`PCP.1`–`PCP.4` are **not** blocked on `DEPLOY.1`; they run on the laptop
console/CLI at zero new device risk and give the product its persistent
shape while the `OP.2.C` release gates wait on the server.

---

## 21. First implementation movement — `PCP.1` (bounded contract)

**Status: AUTOMATED_VALIDATED — 2026-09-05.** `utils/device_registry.py`,
`utils/evidence_backend.py::DeviceRegistryBackend`, the three CLI verbs and
`tests/test_pcp1_device_registry.py` exist and implement AC-1a..AC-15
exactly as specified below. This session's sandbox has no `pytest`/`lxml`/
`paramiko` (reported, not bootstrapped, per `CLAUDE.md`); every behavior
was additionally hand-verified directly (opaque/unique `device_id`,
normalization, duplicate detection across vendor hints and lifecycle
states, the closed schema, fail-closed corrupt-data handling, atomic
persistence, lock contention, and the owner-token instance-safe release),
then confirmed by PR #83's fast PR CI `validate` check running the real
suite green on commit `a149f5a264ebd44db005ad7f5bffa4012f8b30dd`. No
real-environment validation is owed (no device contact). See
`project/build_history.json` head record for the exact CI evidence.

**Build id:** `pcp_1_device_registry_manual_enrollment_foundation`
**Movement:** `IMPLEMENTATION` against this section once this document is
`FROZEN`. **Tier:** `Sonnet 5, normal` — deterministic implementation; the
only decision content (state names, `basis` vocabulary, the normalization
and duplicate rules below) is fixed here, at the PO's freeze review of this
section, not during implementation.

**Objective.** Give neXus its first persistent product object: a Device
Registry with manual enrollment via the CLI, stored under RuntimeRoot through
the existing evidence-backend abstraction, with no device contact, no UI
change and no change to any collector, console route, scheduler workflow or
evidence identity.

**In scope**

1. `utils/device_registry.py` — `DeviceRecord` (§6 categories: opaque
   `device_id`; management endpoints; `vendor ∈ {checkpoint, paloalto,
   unknown}` + `classification_basis`; `credential_ref` naming a credential
   profile, resolvable only in the engine process, never a secret field;
   `enrollment_source = manual`; lifecycle state; optional site/tags/
   environment; empty `relationships[]`; `schema_version`), a
   `DeviceRegistry` service (enroll, list, disable — see the deterministic
   contract below for exactly what each does, including the registry
   mutation lock `enroll`/`disable` acquire before touching the file, the
   ownership token they embed in it, and the instance-safe check they run
   before ever releasing it), and the lifecycle transition table (`ENROLLED_UNVERIFIED`, `DISABLED`
   reachable in `PCP.1`; `RETIRED`, `CONTACT_VERIFIED`, `OBSERVED` defined
   but unreachable until a later movement adds their trigger — `RETIRED`
   needs its own CLI verb, not introduced here).
2. `utils/evidence_backend.py` — an eighth storage concern
   `DeviceRegistryBackend` with the **filesystem implementation only**
   (`data/state/device_registry.json`, atomic tmp→replace write, same
   posture as the other `data/state/*` files); the abstract interface is
   shaped for a later PostgreSQL implementation but none is written
   (`pcp_storage_engine` open; schema/storage migration needs explicit
   approval).
3. `main.py` / `application/cli.py` — maintenance-class modes
   `--registry-enroll` (with `--registry-endpoint`, `--registry-vendor-hint`,
   `--registry-credential-profile`, optional `--registry-tag k=v`),
   `--registry-list`, `--registry-disable <device_id>`; mutually exclusive
   with every collection/render/maintenance mode; **no credential
   resolution, no network, no vendor import** (the lazy-import invariant
   `codebase_modularization_backend` AC-3/AC-5 already tests). No fourth
   verb is introduced in `PCP.1`.
4. `tests/test_pcp1_device_registry.py` — the acceptance criteria below.
5. Documentation touch limited to: `AI_START_HERE.md` CLI table + directory
   map row; `docs/ARCHITECTURE.md` new short section; one line in
   `PRIVACY_AND_DATA_HANDLING.md` classifying both the registry file and
   its mutation lock file as CLASS 2 local data; this section's status.

**Deterministic registry contract**

This subsection is the actual specification `utils/device_registry.py`
implements; item 1 above names the module, this names its behavior.
Filesystem-only throughout — no engine decision, no distributed
concurrency framework, no database. The one addition is a single, narrow
cross-process mutation lock ("Concurrent CLI write behavior" below),
introduced specifically to close the read-check-write race atomic
tmp-then-`replace()` alone cannot close; it is not a general locking
framework and guards nothing outside this module.

*Lifecycle transitions (`PCP.1`-reachable subset).*

| From | To | Trigger | Notes |
| --- | --- | --- | --- |
| *(none — no record)* | `ENROLLED_UNVERIFIED` | `--registry-enroll` | fails closed on a duplicate endpoint (below) before a `device_id` is ever generated |
| `ENROLLED_UNVERIFIED` | `DISABLED` | `--registry-disable <device_id>` | terminal-for-`PCP.1`: no CLI verb re-enables a `DISABLED` record in this movement; a future movement adds that trigger explicitly rather than this contract assuming it |
| `DISABLED` | `DISABLED` | `--registry-disable` on an already-`DISABLED` id | idempotent no-op (see "Repeated-operation behavior") |
| any state | `RETIRED` / `CONTACT_VERIFIED` / `OBSERVED` | *(none exists in `PCP.1`)* | structurally unreachable — no code path produces these transitions (AC-3) |

*Endpoint normalization (representation-only, no network resolution).*
Applied identically to the enrollment input and to every existing record
before any duplicate comparison — a proven representation-only
transformation under `AGENTS.md`'s opaque-identifier law, not an identity
guess:

- strip leading/trailing whitespace;
- if the endpoint is a hostname/FQDN (not a bare IP literal), lower-case it
  (DNS names are case-insensitive, RFC 4343) and strip one trailing `.`
  (the DNS root label, RFC-equivalent to its absence);
- an IP literal is compared byte-for-byte after whitespace stripping only —
  no octet reformatting, no leading-zero handling, no v4/v6 canonicalization;
- an explicit port, if given, is compared literally as a separate field, not
  folded into the endpoint string;
- **no DNS resolution, no reverse lookup, ever.** An IP literal and a
  hostname that happen to resolve to the same device are two different
  normalized endpoints and are never unified.

*Duplicate detection.* The duplicate key is the **normalized endpoint
alone** — `vendor_hint` and `classification_basis` are excluded from the
key, because a vendor hint is unverified operator annotation (§6), not
evidence, and must not fork or merge identity. A new `--registry-enroll`
is refused, before any `device_id` is generated, if **any** existing record
(`ENROLLED_UNVERIFIED` or `DISABLED` — the only two states `PCP.1` can
produce) has the same normalized endpoint, regardless of what vendor hint
or lifecycle state that record carries. The refusal names the existing
`device_id` and its current state; it never creates a second record for the
same endpoint. (`RETIRED` is not a duplicate-detection exemption in
`PCP.1` — it is simply unreachable, so this rule needs no carve-out for it
yet; a future movement that makes `RETIRED` reachable must decide then
whether a retired endpoint may be re-enrolled, and is not pre-decided here.)
This check and the write that follows it are exactly the operation the
registry mutation lock (below) makes race-free across concurrent CLI
processes — without it, two simultaneous callers could each pass this
check against the same pre-write state.

*Repeated-operation behavior (idempotency).*

- `--registry-enroll` on a normalized-duplicate endpoint: refused every
  time, deterministically, per "Duplicate detection" above — never
  idempotent-as-success, because enrollment is a creation, not an
  upsert.
- `--registry-disable <device_id>` on an `ENROLLED_UNVERIFIED` record:
  transitions to `DISABLED`, exit 0.
- `--registry-disable <device_id>` on an already-`DISABLED` record:
  idempotent no-op — no state change, no duplicate audit entry, exit 0,
  an informational message distinguishing "already disabled" from a
  fresh disable.
- `--registry-disable <device_id>` on an unknown `device_id`: a distinct
  failure (non-zero exit, "no such device"), never silently treated as a
  no-op.
- `--registry-list`: read-only, trivially idempotent.

*Concurrent CLI write behavior — a single, narrow cross-process mutation
lock (not a general concurrency framework).* The registry is the durable
product-identity source (§6): a normalized endpoint may never back two
records, and atomic tmp-then-`replace()` alone cannot guarantee that under
two concurrent writers — it prevents a torn *file*, not a torn *decision*.
`--registry-enroll` and `--registry-disable` (the only two mutating verbs
in `PCP.1`) therefore acquire an exclusive, file-based **registry mutation
lock** — `data/state/device_registry.lock`, created with an atomic
exclusive-create primitive (`O_CREAT | O_EXCL`, portable across POSIX and
Windows, no third-party library) — **before** the load step, and hold it
across the complete load → validate → duplicate-check-or-transition →
atomic-replace sequence. `--registry-list` is read-only and does not take
the lock — the existing atomic-replace guarantee already makes its reads
safe.

**Lock content and instance-safe release (freeze correction).** The same
exclusive-create call that creates the lock file writes its content before
anything else touches it: a small JSON object carrying `pid`, `hostname`,
`acquired_at_utc` (the existing human-readable diagnostic fields, see
"Crash / stale-lock recovery" below) and one field release logic actually
acts on — `owner_token`, a fresh random opaque value (e.g. a UUID4)
generated at acquisition and never reused. **Release never unconditionally
unlinks the lock path.** On every exit from the held section (success, a
refusal, or a raised `DeviceRegistryError` alike), the releasing process
re-reads whatever is currently at `data/state/device_registry.lock` and
compares its `owner_token` to the token that process itself wrote at
acquisition:

- **token matches** — the file on disk is still the exact lock instance
  this process created; it deletes it.
- **token does not match, or the file is missing** — the instance this
  process created is no longer the one on disk (a human deleted it,
  believing the recorded holder dead, per "Crash / stale-lock recovery"
  below, and a different writer's `--registry-enroll` or
  `--registry-disable` has since created a new instance under the same
  path). The releasing process **must not delete it**: an unconditional
  unlink here would remove a different, currently-active writer's lock out
  from under it, reopening exactly the concurrent-write race this lock
  exists to close. The releasing process leaves the file untouched and
  simply completes its own exit; the file at that path belongs entirely to
  whichever process's `owner_token` is currently inside it.

This `owner_token` equality check is the lock's only instance-safety
mechanism — a single opaque comparison, nothing more. It never inspects
PID liveness, lock age, or any other heuristic; "Crash / stale-lock
recovery" below is unchanged by this correction, and the token is not a
lease, a fencing token, or a renewal mechanism reused anywhere else in the
codebase (see the non-goals list).

**Lock file classification.** The lock file's `pid`/`hostname`/
`owner_token` content is local operational metadata about the RuntimeRoot
host and the mutating process, not registry evidence about a managed
device — but it is still host-identifying information an operator would
not want in a shared artifact. It is classified **LOCAL-SENSITIVE**
(`PRIVACY_AND_DATA_HANDLING.md` CLASS 2 data), the same classification §6
already gives the registry's own management-endpoint field, and is
RuntimeRoot-resident and repository-excluded exactly like
`device_registry.json`. Like the registry file itself, it is never
enumerated into the support bundle (§10, AC-5) — `run_support_bundle`
reads only enumerated `data/runs/*` artifacts and never touches
`data/state/*` at all.

**Contention fails closed immediately: no wait, no retry, no queueing.** If
the exclusive-create fails because the lock file already exists, the
mutation is refused with a distinct `DeviceRegistryLockError`
**before** any load, validate, duplicate-check, or write runs. The entire
serialization boundary is this: one lock file, one exclusive-create
primitive, one immediate fail-closed outcome on contention — not a queue,
not a bounded wait, not a distributed lock, not the admission coordinator,
not a database.

**Crash / stale-lock recovery is explicit and manual, never automatic.**
The lock file records its holder's PID, hostname and UTC acquisition
timestamp for a human to read — never for code to act on. No code path in
`PCP.1` inspects that PID's liveness, computes the lock's age, or applies
any timeout/heuristic to decide the lock is "probably stale" and clear it
automatically: that would only trade one guess (a torn decision) for
another (a wrong liveness/age inference), which is exactly what this
correction removes. A crashed holder therefore leaves the lock file in
place indefinitely, and every subsequent mutation fails closed with the
same contention error until a human (a) confirms, by their own means (the
OS process list, the deployment's own process supervision — never this
tool's guess), that the recorded PID is not a live registry mutation, and
(b) manually deletes `data/state/device_registry.lock`. This is a
deliberate, documented manual procedure, not a gap: an automatic recovery
path is exactly the kind of guess the correction exists to remove. Every
non-crash exit (success, a validation/duplicate refusal, a fail-closed
corrupt-data error) releases the lock itself; only a genuine process kill
leaves it behind.

*Fail-closed handling of corrupt or unsupported persisted data.* Mirrors
`utils/inventory_exclusions.py::load_inventory_exclusions`/
`_load_raw_document` exactly: a missing `data/state/device_registry.json`
is the empty registry (no error). An existing file that is unreadable,
not valid JSON, not a JSON object, missing or mismatched
`schema_version`, or whose `devices` value is not a list raises a typed
`DeviceRegistryError` from every entry point (`enroll`, `list`, `disable`)
— it is never silently treated as empty, never auto-repaired, and never
partially loaded. A malformed individual record inside an otherwise valid
document (wrong types, an unknown lifecycle state, a `credential_ref` that
fails its format check) raises the same typed error rather than being
skipped — a corrupt registry fails closed as a whole, not row-by-row.

**Acceptance criteria**

| AC | Assertion |
| --- | --- |
| AC-1a | `device_id` is opaque: generated (not derived from endpoint, hostname, serial, or any other input), and no two records ever share one. |
| AC-1b | A `--registry-enroll` for a normalized-duplicate endpoint (per "Duplicate detection") is refused **before** a `device_id` is generated — the refused attempt produces zero new records and zero new ids, named against the existing `device_id` it collided with. A differing `vendor_hint` or a `DISABLED` (vs. `ENROLLED_UNVERIFIED`) existing record does not change this outcome. |
| AC-2a | `DeviceRecord`'s field set is closed (a fixed dataclass, not an open dict): no field named or shaped to carry a secret value exists; an unrecognized key present in `--registry-enroll` input or in a persisted JSON record is rejected, never silently merged in or dropped-and-ignored. |
| AC-2b | `credential_ref` is a bounded opaque profile identifier: format-validated (e.g. `^[A-Za-z0-9_.-]{1,64}$`) only to reject obviously malformed input, never as a secret-detection guarantee. `DeviceRecord` defines no separate credential-payload field, and `PCP.1` never resolves `credential_ref` to an actual credential anywhere in this movement (no code path fetches, echoes, or transmits one). An operator who pastes a real secret into `--registry-credential-profile` still has it persisted verbatim as that field's string value — the format check constrains shape, it does not and cannot prove the supplied value is not itself a secret. |
| AC-2c | Free-text fields (`tags`, site, environment) are length-bounded and redaction-registry filtered before being written to any log line or CLI error message, the same bounding `console/jobs.py::JobRecord.error_summary` already applies to its own free-text field. |
| AC-3 | Lifecycle transitions follow exactly the "Lifecycle transitions" table above; `RETIRED`/`CONTACT_VERIFIED`/`OBSERVED` are unreachable from any `PCP.1` entry point (structural, like `OP.0a` AC-6 — no code path, not merely undocumented). |
| AC-4 | Persistence is atomic (tmp-then-`replace()`) and RuntimeRoot-resident; writing to a path equal to or nested with the repository root is refused (`utils/runtime_paths` separation). |
| AC-5 | Neither the registry file nor its mutation lock file is included in the support bundle (asserted against `run_support_bundle`'s enumerated inputs, which read only `data/runs/*` — never `data/state/device_registry.json` or `data/state/device_registry.lock`) and the repository privacy gate is unaffected. |
| AC-6 | The three CLI modes are mutually exclusive with each other and with every existing mode; none imports a vendor/collector module (static + runtime `sys.modules` check); none resolves a credential or opens a socket. |
| AC-7 | `unified.json`, `entity_id` resolution, `console/registry.py`, `ALLOWLISTED_WORKFLOWS`, `utils/operate/`, `utils/failover/` are byte-unchanged — enforced by the existing convergence tests remaining green with no allowlist edit. |
| AC-8 | Registry content never reaches `output/index.html` or any console payload in `PCP.1` (no payload builder change; render harness not triggered). |
| AC-9 | `--registry-list` prints device ids, vendor, state and tag keys — never the management endpoint unless `--show-endpoints` is passed (local operator convenience, LOCAL-SENSITIVE, never in logs beyond existing redaction policy). |
| AC-10 | Two concurrent `--registry-enroll` invocations for the same normalized endpoint, however their timing interleaves, persist **at most one** record and generate **at most one** `device_id` for that endpoint — never two. The non-winning invocation either fails closed on lock contention (AC-13, before load/validate/duplicate-check ever runs) or, if it acquires the lock after the winner's commit, is refused by the ordinary duplicate-detection check (AC-1b); no interleaving produces a second record. |
| AC-11 | Each fail-closed corrupt-data case in "Fail-closed handling" above (unreadable file, invalid JSON, wrong/missing `schema_version`, non-list `devices`, one malformed record inside an otherwise valid document) raises `DeviceRegistryError` from `enroll`, `list`, and `disable` alike — never an empty result, never a partial load. |
| AC-12 | Repeated-operation behavior matches "Repeated-operation behavior" above exactly: duplicate enroll always refused; disable is idempotent on an already-`DISABLED` id (exit 0, no duplicate audit entry) and fails distinctly on an unknown id. |
| AC-13 | A mutating invocation (`--registry-enroll` or `--registry-disable`) that cannot acquire the registry mutation lock fails closed immediately with a distinct `DeviceRegistryLockError` — before any load, validate, duplicate-check, or write — never a silent wait, retry, queue, or fallback to an unprotected write. |
| AC-14 | No code path inspects a lock file's recorded PID, hostname, or age to decide it is stale and clear it automatically; a lock left by a crashed holder blocks every subsequent mutation with the same contention error indefinitely, until a human manually deletes `data/state/device_registry.lock`. Every non-crash exit from the critical section (success, a refusal, a raised `DeviceRegistryError`) releases the lock itself. |
| AC-15 | Lock release is instance-safe: a releasing process deletes `data/state/device_registry.lock` only when the file's current `owner_token` still equals the token that same process wrote at acquisition. If the file is missing or its `owner_token` differs — an externally deleted-and-recreated lock now held by a different writer — the releasing process leaves the file untouched and never deletes another writer's active lock instance. |

**Explicit non-goals**

- No enrollment providers, candidates, first-contact job, or any device
  contact (`PCP.2`).
- No capability projection (`PCP.3`); no console/report UI or payload
  change (`PCP.4`); no job definitions or schedule changes (`PCP.5`).
- No PostgreSQL backend, schema or migration; no engine decision.
- No new CLI verb beyond the three named in item 3 above — in particular,
  no `--registry-enable`/re-enable and no `--registry-retire`; `DISABLED`
  is a one-way transition in `PCP.1`.
- No general-purpose or reusable locking library/module, no distributed
  lock (a Postgres advisory lock or otherwise), no blocking/retry/backoff
  wait behavior, and no automatic stale-lock detection or recovery
  heuristic — the one exclusive-create file lock described in "Concurrent
  CLI write behavior" above is narrowly scoped to `utils/device_registry.py`
  itself, guards only that module's own mutation path, and is not a shared
  concurrency primitive other modules use. Its `owner_token` is a single
  equality check for that module's own release path only — not a lease,
  not a fencing token, not a renewal mechanism, and not reused by any other
  module.
- No HTTP wiring and no admission-coordinator involvement in the lock — it
  is a local, single-process-at-a-time CLI safeguard, unrelated to the
  multi-actor question `pcp_console_registry_write_gate` still governs.
- No change to the console job target vocabulary (`entity_id[]` stays until
  `PCP.4`/`PCP.5` re-key it through the registry).
- No credential storage, vault integration or `credential_profiles`
  implementation — the profile name simply names the existing env-var /
  mounted-file credential set.
- No new identity semantics: `device_id` relates to nothing yet.
- No `OP.2`, `RB.x`, `CON.x` or scheduler change of any kind.
- No `DEPLOY.1`/deployment work of any kind; `PCP.1` runs identically on
  today's local/laptop RuntimeRoot with no server dependency.

**Affected authority files at `PCP.1` close:** `project/roadmap.json`
(`now`/`next` rotation; `pcp_1…` → `automated_validated`),
`project/feature_registry.json` (`device_registry_enrollment_foundation`
criteria), `project/build_history.json` (new head record),
`CURRENT_STATE.md`, `AI_HANDOVER.md`, `AI_START_HERE.md` (CLI table,
directory map, **and the §22 item 4 "What this is" persistent-control-plane
sentence, deferred from the `PCP.0` freeze to here**), `docs/ARCHITECTURE.md`,
`PRIVACY_AND_DATA_HANDLING.md` (registry file **and mutation lock file**
CLASS 2 classification line), `docs/history/INDEX.md` (regenerated), this
document (§21 status line).

**Validation ladder for `PCP.1`:** targeted test file (AC-10/AC-13/AC-14/
AC-15 need a deterministic concurrency-simulation technique — e.g. a
test-only hook that holds the lock open, or two coordinated real
subprocesses, including one that rewrites the lock file with a different
`owner_token` mid-hold to exercise AC-15's non-match path — rather than
timing-based flakiness; the exact technique is an implementation detail
for the test file, not frozen here); subsystem
regression (`tests/test_architecture_convergence.py`,
`tests/test_dev0_3a_runtime_paths.py`, `tests/test_phase0_3_support_bundle.py`,
the CLI mode-matrix tests); one serial full regression before merge (new
storage concern = shared-core trigger); repository privacy gate;
`git diff --check`. No real-environment validation is owed (no device
contact) — `AUTOMATED_VALIDATED` is the ceiling and the honest status.

**`main.py`/UI effect:** backend + CLI only; a normal run produces **no
visible UI change**.

---

## 22. Amendments to existing documents (freeze timing corrected)

Items 1-3 are **applied by this freezing session**: each target document is
itself `FROZEN` and must not point at a draft, so the amendment could not
land before `PCP.0` carried Product Owner approval, and lands now that it
does.

1. `docs/design/OPERATOR_CONSOLE_ARCHITECTURE.md` §12 — appended an
   "Amendment (`PCP.0`)" paragraph: the device experience and first-run
   onboarding are `CON.x` surface work under this document; **neither the
   manual-enrollment intent nor the candidate-id enrollment intent is added
   to §4 as an authorized write** — both are recorded as pending
   `pcp_console_registry_write_gate` (§19), whichever way that decision
   lands. A closed candidate id is a *narrower* input than a free-typed
   endpoint; it is not, by itself, an authorization for a persistent
   product-state write to reach the console before `DEPLOY.1A`. Prohibitions
   unrelaxed.
2. `docs/design/COMPLIANCE_ASSIGNMENT_AND_FRAMEWORKS.md` §4b — appended one
   line: the "tagged device registry" is delivered by the Product Control
   Plane's Device Registry (`PCP.1`+), not built separately; the assignment
   editor half stays `DEPLOY.1A`-gated.
3. `docs/design/BACKUP_AND_RECOVERY_ARCHITECTURE.md` §9 — appended one
   line: typed backup jobs over registry targets are the `PCP.5`/`PCP.6`
   form of the scheduling this section already requires to route through
   the admission coordinator.

Item 4 is different in kind, not only in timing, and stays **explicitly out
of this freeze**:

4. `AI_START_HERE.md` "What this is" — one sentence acknowledging the
   persistent control-plane direction. §1 above already states the correct
   condition: that sentence "becomes true only once `PCP.1` ships" an
   actual persistent registry — today neXus still has none, so writing it
   into the canonical cold-start entry point now would assert, in the one
   document new sessions read first, a capability the product does not yet
   have. This amendment is therefore **moved to the `PCP.1` close scope**
   (§21 "Affected authority files at `PCP.1` close" now names it
   explicitly) and is applied only when `PCP.1` actually ships — never at
   this `PCP.0` freeze.

`AGENTS.md` and `AI_START_HERE.md` governance roles are unchanged; no new
canonical authority competes with them.

---

## 23. Validation / merge gate for this document

- Docs/state movement only: no product code, tests, taxonomy, console
  route, device command or schema changed.
- Required green before merge: `utils.project_plan.build_project_plan_payload()
  ["metadata_warnings"] == []`; `scripts/build_history_index.py --check`;
  `tests/test_architecture_convergence.py` (project-state consistency,
  draft-doc/terminal-record gate, `CURRENT_STATE.md` ≤ 200 lines and naming
  the current build); every `build_history.json` doc link resolves;
  `git diff --check`.
- Fast PR CI (`validate`) is sufficient for a docs/state PR per
  `docs/AI_DEVELOPMENT_PROTOCOL.md` "CI validation policy".
- **Product Owner review completed 2026-09-05**: PCP.0 product direction and
  architecture **APPROVED**, conditioned on exactly the two mechanical
  freeze corrections named in "Status" above (registry mutation lock
  ownership/privacy; §22 timing) — both applied by this freezing session.
  The status line above now reads `FROZEN`. Merge to `main` proceeds once
  the validation ladder above is green.

---

## 24. Next movement / reasoning tier

- **This freeze session** (`Sonnet 5, extended thinking (high)`) applied
  §22 amendments 1-3 (item 4 deferred to the `PCP.1` close, see §22), the
  registry-mutation-lock ownership/privacy correction (AC-5, AC-15),
  flipped this status to `FROZEN`, and rotated `project/roadmap.json`
  (`PCP.0` → done; `PCP.1` stays `next`).
- **Exact next movement:** `PCP.1`
  (`pcp_1_device_registry_manual_enrollment_foundation`), `Sonnet 5,
  normal`, one short prompt pointing at §21 and
  `tests/test_pcp1_device_registry.py`. Not started by this session.
- Escalate to extended thinking only for the four open decisions in §19 when
  their `decide_by` movement is reached.
