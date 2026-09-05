# Product Control Plane — Architecture (design parent, `PCP.x`)

## Status

**DRAFT — PENDING PRODUCT OWNER REVIEW, 2026-09-05.** Product-direction
promotion of the previously parked Product Control Plane direction,
reconciled against live `main` at `ff700e38`. Not yet implementation
authority (`AGENTS.md` "Authority hierarchy" item 2, "Contract-status law"):
it may guide investigation and roadmap placement, but no movement below may
be implemented until the Product Owner reviews this document and its status
line is changed to `FROZEN`. The one bounded first implementation movement
(`PCP.1`, §21) carries its own acceptance criteria here so that freezing this
document freezes `PCP.1`'s contract without a second document.

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
| **EXECUTION PREFLIGHT** | fresh evidence gathered inside one controlled-operation workflow | `OP.2.0` P4 preflight stage (`checkpoint/clusterxl_preflight_provider.py` for CP) | eligibility → proposal → confirmation → lock → mutation → verification | be cached, reused across actions, or replaced by a projection |

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
  (`PCP.5`), with the existing plane-scoped workflows (`cp`, `vsx`,
  `pan-config`, `cp-config`, `recovery-pan`) remaining valid as
  registry-filtered targets until a collector gains per-target selection;
- **collector target-selection seams** (`PCP.6`): today CP inventory is
  plane-wide (one MDS script over all gateways, exclusions by name); PAN
  runtime is per-serial; recovery collection is already selective. Per-device
  typed jobs need each collector to accept a registry-derived target set.
  This is real work and is recorded as such, not assumed;
- independent cadences for inventory / lightweight health telemetry /
  configuration snapshots / backup / HA-readiness observation /
  compliance-alignment. **No interval is frozen here.**

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
the registry is LOCAL-SENSITIVE data and never enters the support bundle
(which reads only enumerated run artifacts) or the repository.

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
| enrollment from candidates | `candidate_id[]` | resolves candidates from the last provider run, writes registry records | fits `CON.0` §4 literally: no hostname/address transmitted |
| **manual enrollment** | a typed, schema-validated entry: endpoint, credential *profile reference*, vendor hint, tags | validates syntax, writes a registry record in `ENROLLED_UNVERIFIED`; **contacts no device in this request** | **contradicts `CON.0` §4's literal wording** ("the browser never transmits … a hostname, an address") and touches the `inventory_exclusions_management_ui_backend` precedent ("write access controls which devices get polled — wait for `DEPLOY.1A`"). Raised as open decision `pcp_console_registry_write_gate` (§19); until decided, manual enrollment ships CLI-first (`PCP.1`) and the console form waits |
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
| Console architecture | `CON.0` §4 literal "browser never transmits … a hostname, an address"; `C-D7`; exclusions write path `DEPLOY.1A`-gated | **CONTRADICTION** for *manual* enrollment from the console only | open decision `pcp_console_registry_write_gate`; candidate-based enrollment and all device jobs fit §4 verbatim; manual enrollment ships CLI-first |
| HA identity / topology authority | backend-only derivation; UI heuristics retired (`OP.0b S9`) | no conflict | registry relationships are backend-derived projections; the UI still computes nothing |
| Readiness freshness laws | `OP.0a` cannot emit `SAFE_TO_FAILOVER`; `OP.2.0` P4 same-workflow, no TTL | no conflict | projection layer = informational; no TTL introduced |
| `OP.2` operational identity and lock scope | `OP.2.0` P8/P17; `OP.2.1b` | no conflict | untouched (§16) |
| Scheduler / job assumptions | plane-wide workflow names, file policy, `>= 10 min`, default-off, `--scheduler-once`, `C5-2`, `C-D7` | **SUPERSEDED ASSUMPTION** (workflow = whole plane) with every safety property preserved | typed per-capability jobs over registry targets; collector target-selection seams are recorded as real work (`PCP.6`) |
| Command-execution anti-requirements | no browser → device; closed registries; no arbitrary shell; `CE.1` rejects remediation blocks | no conflict | runbooks = closed catalog, class 0, same primitive registry as `CE.2` |
| Support bundle / privacy / credential rules | HMAC tokenization; secrets never in records; `DEV.2.1/2.2` credential sourcing; `credential_profiles` planned (1.0) | no conflict | credential *references* only; registry never enters the bundle; `credential_profiles` becomes the reference model the registry points at |
| Vendor scope | CP + PAN only | no conflict | registry vendor enum is CP / PAN / unknown |
| Roadmap ordering | `now_next.next` = `op2_c_cp_clusterxl_adapter_scoping` (blocked, external) | ordering change, not a demotion of completed work | `OP.2.C` scoping moves to `upcoming` (still `blocked` on `DEPLOY.1`, notes preserved); `PCP.1` becomes `next`, gated on this document's freeze |

No frozen law was removed or reinterpreted to make the direction fit. The
one genuine contradiction is isolated to a single console intent and is
left to the Product Owner as an explicit decision.

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
| `pcp_console_registry_write_gate` | May the loopback console accept a *manual* enrollment write (endpoint + credential reference, no device contact in-request) before `DEPLOY.1A`, given `CON.0` §4's wording and the exclusions-write precedent? | **Candidate-based enrollment from the console: yes now** (ids only). **Manual entry: CLI-first in `PCP.1`; console form only with** typed confirmation + audit record + mandatory strict trust preflight on the first-contact job, decided at the `PCP.4` contract review — or wait for `DEPLOY.1A` if the security lead prefers the exclusions precedent verbatim | `PCP.4` contract review |
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

**Build id:** `pcp_1_device_registry_manual_enrollment_foundation`
**Movement:** `IMPLEMENTATION` against this section once this document is
`FROZEN`. **Tier:** `Sonnet 5, normal` — deterministic implementation; the
only decision content (state names, `basis` vocabulary) is fixed at the PO's
freeze review of this section, not during implementation.

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
   `DeviceRegistry` service (enroll, list, disable/retire, duplicate-endpoint
   refusal per vendor), and the lifecycle transition table
   (`ENROLLED_UNVERIFIED`, `DISABLED`, `RETIRED` reachable in `PCP.1`;
   `CONTACT_VERIFIED`/`OBSERVED` defined but unreachable until `PCP.2`).
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
   `codebase_modularization_backend` AC-3/AC-5 already tests).
4. `tests/test_pcp1_device_registry.py` — the acceptance criteria below.
5. Documentation touch limited to: `AI_START_HERE.md` CLI table + directory
   map row; `docs/ARCHITECTURE.md` new short section; one line in
   `PRIVACY_AND_DATA_HANDLING.md` classifying the registry file as CLASS 2
   local data; this section's status.

**Acceptance criteria**

| AC | Assertion |
| --- | --- |
| AC-1 | `device_id` is opaque: generated, never derived from endpoint/hostname/serial; two enrollments of identical inputs yield different ids and the second is refused as a duplicate active endpoint for that vendor |
| AC-2 | `DeviceRecord` has no field that can carry a secret; `credential_ref` is a profile *name*; a test enrolling with a password-like value in any accepted field fails schema validation rather than persisting it |
| AC-3 | Lifecycle transitions follow the fixed table; `CONTACT_VERIFIED`/`OBSERVED` are unreachable from any `PCP.1` entry point (structural, like `OP.0a` AC-6) |
| AC-4 | Persistence is atomic and RuntimeRoot-resident; writing to a path equal to or nested with the repository root is refused (`utils/runtime_paths` separation) |
| AC-5 | The registry file is not included in the support bundle (asserted against `run_support_bundle`'s enumerated inputs) and the repository privacy gate is unaffected |
| AC-6 | The three CLI modes are mutually exclusive with each other and with every existing mode; none imports a vendor/collector module (static + runtime `sys.modules` check); none resolves a credential or opens a socket |
| AC-7 | `unified.json`, `entity_id` resolution, `console/registry.py`, `ALLOWLISTED_WORKFLOWS`, `utils/operate/`, `utils/failover/` are byte-unchanged — enforced by the existing convergence tests remaining green with no allowlist edit |
| AC-8 | Registry content never reaches `output/index.html` or any console payload in `PCP.1` (no payload builder change; render harness not triggered) |
| AC-9 | `--registry-list` prints device ids, vendor, state and tag keys — never the management endpoint unless `--show-endpoints` is passed (local operator convenience, LOCAL-SENSITIVE, never in logs beyond existing redaction policy) |

**Explicit non-goals**

- No enrollment providers, candidates, first-contact job, or any device
  contact (`PCP.2`).
- No capability projection (`PCP.3`); no console/report UI or payload
  change (`PCP.4`); no job definitions or schedule changes (`PCP.5`).
- No PostgreSQL backend, schema or migration; no engine decision.
- No change to the console job target vocabulary (`entity_id[]` stays until
  `PCP.4`/`PCP.5` re-key it through the registry).
- No credential storage, vault integration or `credential_profiles`
  implementation — the profile name simply names the existing env-var /
  mounted-file credential set.
- No new identity semantics: `device_id` relates to nothing yet.
- No `OP.2`, `RB.x`, `CON.x` or scheduler change of any kind.

**Affected authority files at `PCP.1` close:** `project/roadmap.json`
(`now`/`next` rotation; `pcp_1…` → `automated_validated`),
`project/feature_registry.json` (`device_registry_enrollment_foundation`
criteria), `project/build_history.json` (new head record),
`CURRENT_STATE.md`, `AI_HANDOVER.md`, `AI_START_HERE.md` (CLI table,
directory map), `docs/ARCHITECTURE.md`, `PRIVACY_AND_DATA_HANDLING.md`,
`docs/history/INDEX.md` (regenerated), this document (§21 status line).

**Validation ladder for `PCP.1`:** targeted test file; subsystem regression
(`tests/test_architecture_convergence.py`, `tests/test_dev0_3a_runtime_paths.py`,
`tests/test_phase0_3_support_bundle.py`, the CLI mode-matrix tests); one
serial full regression before merge (new storage concern = shared-core
trigger); repository privacy gate; `git diff --check`. No real-environment
validation is owed (no device contact) — `AUTOMATED_VALIDATED` is the
ceiling and the honest status.

**`main.py`/UI effect:** backend + CLI only; a normal run produces **no
visible UI change**.

---

## 22. Amendments proposed to existing documents (applied only at freeze)

To avoid pointing frozen documents at a draft, none of the following is
edited by this session. On `PCP.0` freeze, the freezing session applies:

1. `docs/design/OPERATOR_CONSOLE_ARCHITECTURE.md` — append an
   "Amendment (`PCP.0`)" paragraph: the device experience and first-run
   onboarding are `CON.x` surface work under this document; §4 gains the
   candidate-id enrollment intent as a second closed-registry intent; the
   manual-enrollment intent is recorded as decided by
   `pcp_console_registry_write_gate`. Prohibitions unrelaxed.
2. `docs/design/COMPLIANCE_ASSIGNMENT_AND_FRAMEWORKS.md` §4b — one line:
   the "tagged device registry" is delivered by the Product Control Plane's
   Device Registry (`PCP.1`+), not built separately; the assignment editor
   half stays `DEPLOY.1A`-gated.
3. `docs/design/BACKUP_AND_RECOVERY_ARCHITECTURE.md` §9 — one line: typed
   backup jobs over registry targets are the `PCP.5`/`PCP.6` form of the
   scheduling this section already requires to route through the admission
   coordinator.
4. `AI_START_HERE.md` "What this is" — one sentence acknowledging the
   persistent control-plane direction once `PCP.1` ships (not before).

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
- **Merge to `main` is blocked pending Product Owner review** of this
  document (status `DRAFT`). The freezing review either flips this status
  line to `FROZEN` (with any corrections) or returns it with decisions.

---

## 24. Next movement / reasoning tier

- **Next movement:** Product Owner review of this document (`ARCHITECTURE`
  review, human) → on approval, a `Sonnet 5, extended thinking (high)`
  session applies §22 amendments, flips this status to `FROZEN`, and rotates
  `project/roadmap.json` (`PCP.0` → done; `PCP.1` stays `next`).
- **Then:** `PCP.1` implementation, `Sonnet 5, normal`, one short prompt
  pointing at §21 and `tests/test_pcp1_device_registry.py`.
- Escalate to extended thinking only for the four open decisions in §19 when
  their `decide_by` movement is reached.
