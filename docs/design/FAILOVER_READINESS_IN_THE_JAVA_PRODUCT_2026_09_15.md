# Failover Readiness in the Java Product

**Status:** DRAFT -- ARCHITECTURE ANALYSIS, NOT AUTHORITY, NOT A CONTRACT.

## Bottom line

Before any open decision is answered, only a read-only, fail-closed readiness shell can ship: it can derive the supported HA unit shapes from facts the Java product already has, preserve provenance, list all seven checks, and return `INSUFFICIENT_EVIDENCE`, `UNSAFE_DO_NOT_FAILOVER`, or `NOT_A_FAILOVER_UNIT`; it cannot honestly produce a positive live-readiness conclusion.

The Python line is close as a deterministic calculation, not as a Java product capability. Its 52 passing tests prove offline derivation against constructed evidence. They do not prove that the Java product can collect the required facts, that unresolved vendor meanings are settled, or that any feature works against a real device. No Java feature has yet reached a real device.

This analysis keeps three boundaries intact:

- readiness is evidence, never authorization;
- the Java assessment must not emit a proceed verdict;
- the assessment layer remains read-only and contains no plan, executor, vendor adapter, credential path, or device I/O.

## What the Python line computes today

The Python implementation accepts inventory/configuration rows, optional stored HA runtime rows, and optional fresh preflight snapshots. It derives operational HA units, evaluates evidence, and emits one sanitized fleet record. It performs no device I/O.

### Inputs and evidence model

The calculation needs:

- inventory identity and topology sufficient to resolve physical entities, CP ClusterXL groups, VSX parents and VSIDs, and PAN HA candidates;
- stored CP role/mode facts or stored PAN enabled/state/mode facts for the limited legacy path;
- for the full path, independently collected facts for every member in one `preflight_run_id`;
- an identity-gate result, opaque physical identity, operational-unit identity, source plane, transport, context, collection outcome, and timestamp for every fact;
- known/unknown/not-applicable/unsupported/collection-failed state separated from the fact value;
- configuration-intent provenance kept distinct from runtime provenance;
- a separately supplied pair-identity grade; one member's claim about a peer is not treated as an independent peer observation.

The coherence calculation confirms that runtime categories share the snapshot run, reports timestamp skew without judging it against a threshold, and reports whether configuration intent predates the preflight. Missing or unparseable evidence remains not evaluable.

### Unit derivation

The Python line derives:

- one CP ClusterXL operational unit from an established topology group;
- a VSX physical parent plus subordinate VSID readiness units without treating a VSID as an execution identity;
- a PAN pair only from the conservative configured relationship, or an explicitly bounded two-member preflight candidate without promoting it to proven pair identity;
- no HA unit for a standalone device without positive HA evidence;
- `NOT_A_FAILOVER_UNIT` for CP load-sharing modes rather than pretending that member evacuation is failover.

### Seven ordered checks

Every unit always carries these checks, in this order:

| Check | CP facts | PAN facts | Result rule |
| --- | --- | --- | --- |
| Viable target | member role, attention state, critical pnote state | member HA state | requires complete independent member coverage and a standby-capable member; an established dangerous member state can fail |
| State sync current | sync status | local state-sync plus the member's own HA2-link observation | only established healthy values pass; explicit established bad values fail; all other values are insufficient |
| Parity | software version and installed-policy token | running sync, build, and content compatibility | requires facts on both members and equality where specified |
| No split brain | member roles | member states | requires exactly one independently observed active member |
| Control/sync link health | cluster-interface state | HA1, HA2, and monitored-path state | established down states fail; unknown values do not |
| Preemption known | no authorized readable CP fact | local preemptive fact | CP is permanently disclosed as not evaluable under `D-V7b`; PAN requires both members' facts |
| Flap history | failover count | non-functional and preempt flap counts | counters are disclosed, but no threshold is invented; the check remains insufficient/advisory |

All checks first enforce unit attribution, identity gates, same-run coherence, supported mode, full member coverage, and pair identity. A collection failure, unsupported read, missing fact, unrecognized token, one-sided peer claim, ambiguous membership, or incoherent snapshot cannot become `PASS` or a fabricated device failure.

### Vocabulary and roll-up

Fact/check vocabulary:

- fact states: `KNOWN`, `UNKNOWN`, `NOT_APPLICABLE`, `UNSUPPORTED`, `COLLECTION_FAILED`;
- check states: `PASS`, `FAIL`, `INSUFFICIENT_EVIDENCE`;
- relationship disclosure: `MATCH`, `MISMATCH`, `MISSING`, `NOT_EVALUABLE`, `AMBIGUOUS`.

The Python schema contains unit verdicts `SAFE_TO_FAILOVER`, `DEGRADED_PROCEED_WITH_RISK`, `UNSAFE_DO_NOT_FAILOVER`, `INSUFFICIENT_EVIDENCE`, and `NOT_A_FAILOVER_UNIT`. `DEGRADED_PROCEED_WITH_RISK` is reserved and never emitted. The stored-telemetry path can evaluate only viable-target and split-brain checks, so it cannot reach `SAFE_TO_FAILOVER`. The fresh-preflight path can currently reach `SAFE_TO_FAILOVER` when every non-advisory check passes.

That last Python behavior must not be copied unchanged: this movement's governing invariant says the Java readiness assessment may not emit a proceed verdict. The Java port may preserve the schema token for compatibility only if emission remains unreachable. Changing that boundary would require a Product Owner decision and a frozen contract; this analysis neither recommends nor designs that change.

## Capability-by-capability portability

"Portable now" means that the deterministic Java behavior can be implemented without deciding an open question. It does not mean the Java product already has the required producer or real-device validation.

| Python capability | Java verdict | Boundary or blocking decision |
| --- | --- | --- |
| Safe fact/provenance envelope with explicit unknowns and opaque identifiers | Portable now | Pure model; no vendor meaning needs to be invented |
| Same-run coherence, attribution checks, timestamp-skew disclosure, and stale-intent presence | Portable now | Records facts only; no `D-F1` age or skew threshold is chosen |
| Seven-check ordered result shape and fail-closed handling | Portable now | Unknown vendor values remain insufficient; no exhaustive vocabulary is assumed |
| CP ClusterXL grouping from an already-established topology group | Portable now | Only if Java inventory exposes the established group identity; labels are not join keys |
| Stored-evidence viable-target and split-brain assessment | Portable now | Useful only where Java already has independent member role/state observations; otherwise it honestly returns insufficient |
| Load-sharing classification as `NOT_A_FAILOVER_UNIT` | Portable now | Applies only after mode is positively established |
| CP fresh viable-target and link/health evaluation | Blocked | `D-V6` blocks the exact `cphaprob` read/field semantics; answering it needs approved real-device evidence |
| CP fresh state-sync evaluation | Portable as logic; no Java producer | The positive and explicit-failure mapping can be copied, but the Java product has no same-run fact producer |
| CP software/policy parity | Portable as logic; no Java producer | Existing configuration collection is intent, not the two-member fresh runtime/content evidence this check requires |
| CP flap-history fact production | Blocked | `D-V5a` blocks exact failover-statistics command syntax; answering it needs approved real-device evidence |
| CP preemption/recovery evaluation | Portable only as `INSUFFICIENT_EVIDENCE` | `D-V7b` records that the configured-recovery read is still unknown/unreadable; the port must not guess |
| CP optional hotfix-parity extension | Blocked but not required for current parity | `D-V8`; it does not block the present seven-check mapping |
| VSLS per-VS readiness interpretation | Blocked beyond conservative unknown handling | `D-V9b` blocks estate applicability of the non-VS0 interpretation and needs real-device/version evidence; `op_aa_vsls_scope` separately blocks making VSLS a controlled-action scope and does not authorize readiness semantics |
| PAN link, sync, and compatibility evaluation for already-established tokens | Portable as fail-closed logic; no Java producer | `D-V1` and `D-V2` leave exhaustive vocabularies open. Known positive/negative tokens may be mapped; every other token must remain insufficient. Closing the vocabularies needs official semantics and real-device evidence |
| PAN pair construction and reciprocal identity correspondence | Blocked | `D-V3a` blocks serial-field semantics and `D-V3b` blocks real reciprocal correspondence/B2. `D-V3b` explicitly needs evidence from an approved real pair |
| PAN Active/Active readiness | Blocked | `op_aa_vsls_scope` leaves the supported scope open; no Active/Active semantics may be inferred |
| Sanitized evidence disclosure, observed counters, summary counts, and unmatched/ambiguous snapshot reporting | Portable now | Pure projection; raw responses and sensitive identities remain excluded |
| `UNSAFE_DO_NOT_FAILOVER`, `INSUFFICIENT_EVIDENCE`, and `NOT_A_FAILOVER_UNIT` roll-up | Portable now | Deterministic and non-authorizing |
| `SAFE_TO_FAILOVER` or any other proceed verdict | Not portable under the governing invariant | This is prohibited by the dispatched Java boundary, not unlocked by any current open decision |

`D-V3b` blocks the most: until real reciprocal PAN pair evidence establishes B2, pair identity fails closed and therefore every cross-member positive PAN readiness conclusion is unavailable.

Real-device requirements for the named blockers are explicit:

- `D-V1`, `D-V2`, `D-V3a`, `D-V3b`, `D-V5a`, `D-V6`, `D-V8`, and `D-V9b` need approved vendor-device evidence before Java may claim the corresponding live semantics; official vendor documentation is also required where the semantic is safety-critical.
- `D-V7b` needs no invented substitute: the current portable result is permanently `INSUFFICIENT_EVIDENCE`. Any future claim that a readable surface exists would need new official semantics and real-device corroboration.
- `op_aa_vsls_scope` is a Product Owner scope choice and does not itself require a device run to answer. Implementing any scope it enables would still require the applicable vendor decisions and real-device validation.

The Product Owner decisions `op_track_id`, `op_four_eyes`, `op_emergency_evac`, and `op_continuity_tolerance` do not block this bounded read-only port. They govern program placement, authorization policy, future emergency behavior, or post-action verification. They must not leak into readiness derivation. `op_aa_vsls_scope` matters only where the proposed surface would claim Active/Active or VSLS support beyond conservative unknown/not-applicable reporting.

## What the Java product must gain

### Facts with an existing producer

The dispatched Java baseline establishes only these producers:

- enrollment/inventory collection can provide the physical subject, vendor/platform classification, and whatever topology is explicitly present in its current inventory contract;
- configuration collection can provide configured intent and its provenance;
- the CP gateway-backup path proves a bounded CP device path exists, but backup success produces no HA-readiness fact.

Those facts are sufficient for a unit-shaped, fail-closed surface only where the current inventory contract already contains an established operational group. Configuration intent may be displayed as intent; it cannot satisfy runtime role, health, identity, or same-run coherence checks.

### Existing collector path, but failover reads are ungated

The CP device collector path exists, but the required failover-specific reads are not approved for this Java use. The planned `cp_cphaprob_command_gate` and `cp_failover_command_gate_batch` work must close the network-device command gate before the collector may produce CP role/mode, interface, pnote, synchronization, parity, or history facts for readiness. Reusing the existing authenticated transport is required; a diagnostic credential path must not be added.

### Facts with no Java producer

No Java producer is established for:

- one same-run preflight identifier and per-fact provenance envelope;
- independent identity-gate results for every contacted member;
- canonical HA operational-unit and member attribution suitable for readiness;
- independently observed member roles/states and a positively established HA mode;
- CP pnote/attention, synchronization, cluster-interface, software/policy parity, or failover-history facts;
- PAN HA state, HA1/HA2 observations, state sync, compatibility, monitored-path, preemption, flap, or reciprocal pair facts;
- configuration-intent age/coherence projection into the same preflight without blending intent and runtime truth;
- subordinate VSID snapshots collected in the same invocation.

The minimum useful implementation therefore needs a pure Java domain model and evaluator first, then vendor-specific producers through the existing controlled device paths after their command/semantic gates. A collector must parse the minimum safe facts in memory and discard raw responses. Automated fixtures validate the parser only; each producer remains short of product validation until an approved real-device run corroborates its output and meaning.

## Surface in the frozen information architecture

The frozen navigation contract already places this capability. The fleet surface belongs under the existing **Operations** root as **HA / Readiness**. The selected logical entity gets an **HA / Readiness** tab. The tab stays visible for every entity type; standalone or unsupported subjects render `NOT_APPLICABLE` or the exact insufficient/unsupported reason rather than disappearing.

The surface must select canonical backend identities and render backend-derived topology; it must not compute pairing, identity, or readiness in the UI. ClusterXL clusters, VSX parents/children, and proven PAN pairs render as logical entities with members nested. An unresolved PAN relationship is shown as `UNKNOWN`, never drawn as a confident pair. No Prepare, Authorise, Execute, plan, or other action affordance belongs on this readiness surface.

Recovery is not the home for failover readiness. It remains a separate product plane and a reserved future global root under `PO-NAV-2`; device Recovery remains contextual. Readiness belongs to Operations even though both capabilities discuss operational safety.

## Actual port cost and sequence

The smallest honest port is four bounded pieces:

1. Port the pure fact/provenance types, operational-unit projection, coherence rules, seven check records, and non-proceed verdict roll-up.
2. Project existing Java inventory/configuration facts into that model without claiming runtime truth; ship only if an always-honest insufficient/not-applicable surface has product value.
3. Add CP and PAN fact producers one vendor/read at a time through the existing transports, only after each command and semantic gate closes; retain no raw response.
4. Validate every producer against approved real devices before calling its network-facing behavior validated, then expose the backend result under Operations and the entity HA tab.

The code translation itself is routine. The cost is establishing producers, command approval, opaque identity and pair attribution, real-output parsers, same-run collection, safe projection, and real-device evidence. Little can ship before the open decisions and the first real-device run: only the fail-closed shell and pure derivation are genuinely close; useful positive live readiness is not.

## Explicit non-scope

This analysis does not design or prepare a failover plan compiler, executor, vendor adapter, action registry member, authorization path, rollback/failback, emergency bypass, or controlled action. Readiness cannot authorize any of those. If the Product Owner wants the Java assessment to emit a proceed verdict, support Active/Active or VSLS as an action scope, or relax the read-only package invariant, that is a separate decision and contract movement.
