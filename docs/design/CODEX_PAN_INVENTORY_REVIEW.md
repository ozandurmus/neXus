# Architectural verdict: [REJECTED]

The frozen One-Box model is sound, but the current Java implementation is not safe for bulk enrollment or fleet inventory execution. Live execution was not performed.

## Blocking findings

1. **Enrollment currently fails open.** After successful key generation, failed identity or HA reads become `null`/empty strings. Confirmation can then establish an empty serial as the baseline, treat failed HA evidence as standalone, and move the device to `ENROLLED`. See [ConfirmCapabilityExecutor.java](/Users/OzanDur/Codo/nexus/ui2/worker/src/main/java/com/securityexpert/nexus/ui2/worker/confirm/ConfirmCapabilityExecutor.java:142) and [PaloAltoConfirmReadParser.java](/Users/OzanDur/Codo/nexus/ui2/worker/src/main/java/com/securityexpert/nexus/ui2/worker/confirm/PaloAltoConfirmReadParser.java:31).

2. **Inventory operational failures can become successful empty runs.** `XmlApiResult.Failed` is collapsed to an empty string; empty interfaces/routes are parsed as valid empty collections, after which a `Completed` inventory run is persisted. HTTP status and PAN XML `status="error"` are also not validated. See [InventoryCapabilityExecutor.java](/Users/OzanDur/Codo/nexus/ui2/worker/src/main/java/com/securityexpert/nexus/ui2/worker/inventory/InventoryCapabilityExecutor.java:268) and [InventoryCapabilityExecutor.java](/Users/OzanDur/Codo/nexus/ui2/worker/src/main/java/com/securityexpert/nexus/ui2/worker/inventory/InventoryCapabilityExecutor.java:619).

3. **Opaque identifier law is violated.** Java deliberately strips leading zeroes from HA serials, and tests require that behavior. Python OP.0b correctly uses exact string equality after representation-only whitespace handling. Zero-padded or hyphenated serials must produce `MISMATCH`/`NOT_EVALUABLE`, never invented equivalence. See [PaloAltoHaStateParser.java](/Users/OzanDur/Codo/nexus/ui2/worker/src/main/java/com/securityexpert/nexus/ui2/worker/inventory/pan/PaloAltoHaStateParser.java:25), [PaloAltoHaStateParserTest.java](/Users/OzanDur/Codo/nexus/ui2/worker/src/test/java/com/securityexpert/nexus/ui2/worker/inventory/pan/PaloAltoHaStateParserTest.java:42), and [preflight_collector.py](/Users/OzanDur/Codo/nexus/panorama/preflight_collector.py:174).

4. **Enrollment HA parsing uses the wrong measured shape.** Confirmation expects `<local><state>` and `<peer><serial>`, while the frozen measurement establishes `local-info/state`, `peer-info/serial-num`, `peer-info/mgmt-ip`, and related fields. Its tests encode the obsolete fixture shape. The authoritative paths are in [14E](/Users/OzanDur/Codo/nexus/docs/design/PO_DECISION_RECORD_2026_09_14E_PAN_INVENTORY_MEASURED_FORMS.md:62).

5. **URI construction is unsafe and can bypass terminal transitions.** `WorkerClaimLoop` passes bare `addressRef` into an unvalidated `ApiTarget`; the transport concatenates `"/api/"` and does not catch `IllegalArgumentException`. A bare address can therefore terminate execution without a controlled `FAILED` transition. See [ApiTarget.java](/Users/OzanDur/Codo/nexus/ui2/job-engine/src/main/java/com/securityexpert/nexus/ui2/jobs/transport/ApiTarget.java:4), [WorkerClaimLoop.java](/Users/OzanDur/Codo/nexus/ui2/worker/src/main/java/com/securityexpert/nexus/ui2/worker/confirm/WorkerClaimLoop.java:208), and [PanXmlApiTransport.java](/Users/OzanDur/Codo/nexus/ui2/worker/src/main/java/com/securityexpert/nexus/ui2/worker/transport/xmlapi/PanXmlApiTransport.java:95).

6. **Virtual systems must not become separately contacted device rows.** Frozen authority says VSYS is a target/context modifier, never a registry row. If the reported 140 VSYS entries are actual device rows rather than discovery child candidates, bulk execution would violate PM-1 and could contact physical firewalls repeatedly. See [13F](/Users/OzanDur/Codo/nexus/docs/design/PO_DECISION_RECORD_2026_09_13F_COLLECTION_TRANSPORT_AND_IDENTITY_DECISIONS.md:150) and [14E](/Users/OzanDur/Codo/nexus/docs/design/PO_DECISION_RECORD_2026_09_14E_PAN_INVENTORY_MEASURED_FORMS.md:27).

7. **Current fleet concurrency exceeds the recorded safety posture.** The worker starts ten loops and the claim SQL allows ten concurrent inventory jobs, while current project posture says concurrency remains one per vendor pending real-environment evidence. See [ClaimStatementText.java](/Users/OzanDur/Codo/nexus/ui2/job-engine/src/main/java/com/securityexpert/nexus/ui2/jobs/lease/ClaimStatementText.java:17).

8. **The requested “full” projection is incomplete.** Current parsers/storage omit measured interface fields such as `id`, `zone`, speed, duplex, MAC, type and mode; route metric and the distinct `route-table` field are also lost. HA persistence contains only role and mode, not PP-4’s connectivity/synchronization facts. Compare [14E PP-1..PP-4](/Users/OzanDur/Codo/nexus/docs/design/PO_DECISION_RECORD_2026_09_14E_PAN_INVENTORY_MEASURED_FORMS.md:45) with [PaloAltoInterfaceParser.java](/Users/OzanDur/Codo/nexus/ui2/worker/src/main/java/com/securityexpert/nexus/ui2/worker/inventory/pan/PaloAltoInterfaceParser.java:55) and [PaloAltoRouteParser.java](/Users/OzanDur/Codo/nexus/ui2/worker/src/main/java/com/securityexpert/nexus/ui2/worker/inventory/pan/PaloAltoRouteParser.java:48).

9. **Advanced-routing devices are unsupported.** The frozen contract covers virtual routers only and requires `advanced-routing` to select a later logical-router plan. Java neither extracts that field nor prevents the virtual-router plan from running on such devices. See [14E PF-3](/Users/OzanDur/Codo/nexus/docs/design/PO_DECISION_RECORD_2026_09_14E_PAN_INVENTORY_MEASURED_FORMS.md:23).

## PM-1..PM-4 assessment

- **PM-1: Approved conceptually.** Exactly one inventory job/contact pass per physical firewall.
- **PM-2: Approved with correction.** `hw` belongs to `physical`; `ifnet` belongs to its exact observed VSYS. Missing VSYS must not silently default to `"1"`.
- **PM-3: Approved with correction.** Build `virtual-router → set<vsys>` directly from each interface row. Do not join through interface-name-only maps, which can collapse duplicate names. Security zones do not participate in this attribution.
- **PM-4: Approved conceptually.** Persist one physical context plus exact observed VSYS contexts. Missing virtual-router evidence and an unreferenced named virtual router are different conditions and must not be conflated.

## Direct versus Panorama proxy

Support both modes through the existing XML API transport; a second transport abstraction is unnecessary.

| Mode | Decision |
|---|---|
| Direct firewall | Preferred for direct runtime evidence. Requires routing plus trustworthy TLS for every endpoint. Port-open evidence alone does not prove API or TLS readiness. |
| Panorama proxy | Useful for segregated management networks and reduces egress/trust endpoints, but adds Panorama dependency and broader credential impact. Every result must carry proxy provenance and exact target-serial identity comparison. |

The mode must be explicitly selected and persisted; do not automatically fall back from direct to proxy after failure. Automatic fallback changes the evidence plane and can hide trust or routing defects.

Although 13F authorizes the proxy method class, the current inventory gate rows describe direct `pan_firewall` requests and do not include `target=<serial>`. Proxy inventory therefore needs measured response-shape evidence and its own approved gate entries before use. Existing Python proxy code is know-how, not implementation authority. See [13F](/Users/OzanDur/Codo/nexus/docs/design/PO_DECISION_RECORD_2026_09_13F_COLLECTION_TRANSPORT_AND_IDENTITY_DECISIONS.md:134) and [PAN inventory gate](/Users/OzanDur/Codo/nexus/docs/design/PAN_INVENTORY_API_ROUTE_GATE_ENTRIES.md:33).

## Required refinements

1. Make `ApiTarget` the single URI owner: prefix bare host/address with `https://`, reject explicit non-HTTPS schemes, user-info, query, fragment and unexpected paths, and expose a resolved `/api/` URI. Do not normalize independently in three classes.
2. Return structured outcomes for every XML read: transport failure, HTTP failure, PAN error envelope, parse failure, valid empty result, and valid populated result.
3. Require a non-empty exact serial before enrollment; reuse the measured `local-info`/`peer-info` parser rather than maintaining a second HA parser.
4. Remove all serial numeric normalization and wire the strict-refusal posture instead of hard-coding `false`.
5. Keep only physical firewalls as contacted registry devices; retain VSYS records as child/context metadata.
6. Cap the first live run at one physical canary, then one independently observed HA pair, before any bounded fleet batch.
7. For normal reachability failures, transition to `FAILED` with a safe stable reason such as `connect_failed: timeout`; keep the device `DRAFT` and permit a new confirm job. `CLAIMED`/`REQUESTED` requeue is reserved for worker loss or missing terminal outcome.
8. Restore the one-per-vendor concurrency gate until real-environment evidence explicitly permits an increase.
9. Mark advanced-routing devices `UNSUPPORTED`/`PARTIAL` for routes until the logical-router contract and gate exist.

## SESSION CLOSE

- Completed: read-only architecture, contract, source, and focused-test review.
- Changes: none. No source, project state, Git, deployment, device, credential, or production data was changed.
- Validation: source/test inspection only; tests and live collection were not run.
- Privacy: no raw device identity or credential was retrieved or reproduced.
- Existing workspace state: three pre-existing untracked files were observed and left untouched.
- Next movement: `CONTRACT`, High reasoning—freeze the failure model, exact identity semantics, registry cardinality, URI ownership, proxy gate, and advanced-routing boundary; then implement at Normal (strong).
- Deployment/merge: blocked. No Git dispatch commands are recommended until the P0 findings above are corrected and targeted tests plus one bounded real-device canary are green.
- UI effect: none from this review.