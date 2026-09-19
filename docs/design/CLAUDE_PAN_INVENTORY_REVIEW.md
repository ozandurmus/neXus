## SESSION START

**Role:** REVIEWER (architectural evaluation, read-only). No repository file was modified.

| Field | Value |
| --- | --- |
| Product baseline | `CURRENT_STATE.md`: Java product; inventory collection persists inventory and exposes cluster view; **field bindings, live discovery, inventory, configuration outcomes remain UNVERIFIED** until their specified real-device runs |
| Engineering baseline | Active builds `NXS-LOCAL-0337/0338` `real_env_validated`; `NXS-LOCAL-0330` (CP+PAN Inventory & Discovery Convergence) `done` |
| Task | Architectural review of `docs/design/PAN_INVENTORY_ARCHITECTURE_AND_EVALUATION.md` (DRAFT) ahead of a 39-firewall / 140-vsys enrollment + inventory campaign |
| Movement type | `ARCHITECTURE` |
| Reasoning tier | High (new transport architecture + vendor-semantic ambiguity + identity boundary) |
| Inspected | `InventoryReadPlan`, `InventoryCapabilityExecutor`, `InventoryJobExecutor`, `PanXmlApiTransport`, `XmlApiSpec`, `ApiTarget`, `WorkerClaimLoop`, `ConfirmCapabilityExecutor`, `ConfirmCapabilities`, `ConfigurationCapabilityExecutor`, `ConfigurationReadPlan`, `PanoramaApiRoutes`, `PanoramaEnumerationAdapter`, `DiscoveryRunService`, `IdentityMismatchEvaluator`, PAN parsers (`Interface`/`Route`/`SystemInfo`/`HaState`), `PaloAltoHaStateParserTest`, `ScriptedPanDeviceTransport` |
| Authorities read | `AGENTS.md`, `AI_START_HERE.md`, `roles/REVIEWER.md`, `14E` (FROZEN), `13F` (FROZEN), `PAN_INVENTORY_API_ROUTE_GATE_ENTRIES.md` (APPROVED), `PAN_HA_SERIAL_IDENTITY_HARDENING_DECISION.md` (NOT FREEZABLE), `PAN_SERIAL_REPRESENTATION_IDENTITY_EVIDENCE_CLOSURE.md` (INCONCLUSIVE, P0 open) |
| Not loaded (deliberate) | `docs/design/CODEX_PAN_INVENTORY_REVIEW.md` — withheld to keep this seat's finding set independent; `docs/history/**`; Python collectors beyond what the authorities cite |
| Git lane | `feature/pan-inventory-transport-hardening`; **merge to `main` blocked** pending the four defects below |
| Deployment direction | `local validation only` until B1–B3 close; the fleet run is `production-gated` |

No management address, serial, or credential value is reproduced below (sensitive identity reporting law).

---

## 1. Enterprise security posture: direct vs Panorama-proxied

The document frames this as a preference. It is not — the current code makes it a structural constraint.

`Ui2WorkerMain.java:165` constructs **one** `PanXmlApiTransport` with **one** `paloAltoTrustRuleRef`, and that instance caches a single `HttpClient` built from a single `TrustResolution` for the worker's lifetime (`PanXmlApiTransport:71,172-193`). `InventoryRequest.paloAlto(...)` passes `trustRuleRef = ""` — **the PAN inventory path carries no per-device trust anchor at all.**

The two trust branches behave very differently under that constraint:

- `TrustResolution.PinnedFingerprint` compares the leaf certificate against **one** expected SHA-256 (`PanXmlApiTransport:251-259`). It can, by construction, authenticate exactly one endpoint. Recent commit `b97b240` wired pinned-fingerprint trust for PAN discovery — i.e. pinned to Panorama.
- `TrustResolution.CaBundlePath` could serve a fleet, but only if every firewall's management certificate chains to that bundle.

So direct contact to 39 firewalls requires a corporate CA that has signed all 39 management certificates. Absent that, direct reads fail TLS — correctly, since the class has no bypass branch. Panorama-proxied reads need **one** trust anchor, **one** credential, **one** keygen, and **one** egress path, all of which are already provisioned and exercised.

**Recommendation — make it a per-device policy, not a global default:**

| Prefer | When | Evidence grade to record |
| --- | --- | --- |
| `pan_direct` | A corporate-CA-signed management certificate exists **and** the worker-to-device path is routable and rule-approved | `DEVICE_DIRECT` |
| `pan_panorama_proxy` | Segmented zone, self-signed device certificate, or no approved worker→device rule — the realistic default for this estate today | `DEVICE_VIA_MANAGER` |

Three conditions on adopting proxying, none optional:

1. **It is authorized in principle but not gated.** `13F` §6(b) lifts the method class ("to Panorama with `target=<serial>`: the same runtime reads where the estate prefers them"), but the same section requires that "every concrete command or API route still needs its own network-device command gate row before it is implemented." `PAN_INVENTORY_API_ROUTE_GATE_ENTRIES.md` contains four rows, all `pan_firewall` / `pan_xml_api`. **Four new `pan_proxy_api` rows must be authored and approved first.**
2. **The document's own URL form is wrong and must not be implemented literally.** §4 writes `https://<panorama>/api/?type=op&cmd=...&target=<serial>`. Beyond the gate's "never in the URL" rule, `target=` carries a **device serial** — a query string puts it in Panorama's access log, every TLS-terminating middlebox, and any log aggregation on the shared host. `target` belongs in the POST form body with everything else.
3. **The evidence grade genuinely changes.** Under direct contact, TLS plus `show system info` binds the response to the device dialed. Under proxying, Panorama's `target=` routing sits inside the identity path. That is not disqualifying, but it must be persisted as a distinct grade, not collapsed into "actual evidence" (`AGENTS.md`: management-plane observation ≠ direct-device runtime truth).

**Measurement required before freeze (PF-3 pattern):** run the four reads once through `target=` against one multi-vsys firewall and confirm the response shape is byte-identical to the direct form. If it is, the existing parsers need no change. Until measured, this is `UNKNOWN` — the Python `op_cmd(..., target=serial)` prior art is know-how under `13F` §8, not a measurement of these four reads.

**Interaction safety:** 39 × 4 reads plus keygen ≈ 195 relayed op commands through one management server. `CURRENT_STATE.md` holds concurrency at one per vendor pending real-environment evidence, and `AGENTS.md` forbids raising it before the vendor interaction-safety gate permits. The document specifies no fleet pacing at all. Add one.

---

## 2. One-box model and the multi-VSYS interface matrix

§3 of the document is a faithful transcription of FROZEN `14E` PM-1..PM-4, and `InventoryCapabilityExecutor:286-323` implements PM-3 exactly as written. **The model is correct; the product consequence of one branch is under-specified.**

When a virtual router is referenced by interfaces in more than one vsys, `routesByContext` copies **the entire route list into every one of those vsys contexts** (`:298-307`). In an estate running a shared `default` VR across many vsys — common, and likely across 140 vsys — every tenant context receives the full route table, with **no marker distinguishing "this route is exclusively this vsys's" from "attributed via a shared virtual router."**

This is not route leakage in the network sense: the shared VR genuinely does serve both vsys, and the derivation is what PM-3 mandates. It is an **evidence-semantics** gap. A derived attribution is being persisted in the same shape as a collected fact, and downstream Alignment/Compliance consumers cannot tell them apart — which is exactly the collapse `AGENTS.md` prohibits between the Configuration and Alignment planes.

**Recommendation:** persist an attribution provenance field per route context row — `EXCLUSIVE` (VR referenced only by this vsys) / `SHARED_VIRTUAL_ROUTER` (VR spans ≥2 vsys) / `UNATTRIBUTED` (no referencing interface → `physical`) — and have the UI label shared entries. This is additive, needs no new read, and no new gate row.

Two adjacent parser defects on the same path:

- `PaloAltoInterfaceParser:67` falls back to `DEFAULT_VSYS = "1"` when the `vsys` leaf is absent. That is a **silent misattribution into a real tenant context**. Under the UNKNOWN/fail-closed law it should resolve to `physical` or an explicit `UNKNOWN` context, never to vsys1 by default.
- Every `tag()` pattern in both PAN parsers (`<x>\s*([^<]*?)\s*</x>`) **cannot match a self-closing element**. PAN-OS routinely emits `<ip/>`, `<zone/>`, `<fwd/>`, `<vsys/>` for empty values. A `<vsys/>` leaf therefore triggers the fallback above rather than being recognised as empty.
- `fwd` values other than `vr:<name>` (e.g. inter-vsys forwarding, `tunnel`, `N/A`) are stored verbatim as virtual-router names by `stripVrPrefix` (`:147-149`), creating phantom VR keys. Harmless for routes today; worth an explicit allowlist.

---

## 3. Identity hardening and the zero-drift principle

**This is the most serious finding in the review.**

`PaloAltoHaStateParser:25-31`:

```java
public static String normalizeSerial(String serial) {
    String stripped = serial.strip();
    return stripped.replaceFirst("^0+(?!$)", "");   // strips leading zeroes
}
```

`PaloAltoHaStateParserTest:55-58` locks the behaviour in (`"0123"` → `"123"`).

This contradicts three independent authorities:

1. **`AGENTS.md` "Identity law — identifiers are opaque"** names *"strip leading zeroes"* verbatim among the transformations an agent MUST NOT apply casually. It is permitted only where "vendor semantics, a FROZEN repository contract, or a proven representation-only transformation" justifies it.
2. **No such contract exists.** `PAN_HA_SERIAL_IDENTITY_HARDENING_DECISION.md` is explicitly *"NOT FREEZABLE AS SCOPED. AWAITING PO DECIDE EPISODE… not implementation authority."*
3. **It invalidates the one control the open P0 investigation relies on.** `PAN_SERIAL_REPRESENTATION_IDENTITY_EVIDENCE_CLOSURE.md` (P0, `AWAITING_PO`, root cause undetermined) rules out representation divergence precisely because *"no case-folding, no numeric coercion, **no padding removal** anywhere in any of the three paths."* The Java port breaks that invariant. Any future MATCH/MISMATCH asymmetry on this fleet will no longer be diagnosable against that finding.

Palo Alto serials commonly carry leading zeros, so this is not a hypothetical transformation — it would mutate a large share of the fleet's identity tokens.

The product is also internally inconsistent about serial equality. Three policies coexist:

| Site | Policy | Assessment |
| --- | --- | --- |
| `IdentityMismatchEvaluator:42` | `.equals()`, exact | Correct |
| `PeerPairingResolver` (discovery) | `equalsTrimmed` — whitespace only | Correct (proven representation-only) |
| `PaloAltoHaStateParser.normalizeSerial` | leading-zero strip | **Violation** |

**Required:** delete `normalizeSerial`'s zero-stripping, reduce it to `.strip()`, and invert the test assertions to prove zeros are preserved.

**On reciprocal HA corroboration — it is not implemented at all.** `PaloAltoHaStateParser.parse` extracts `localSerial` and `peerSerial`, and `InventoryCapabilityExecutor:325-328` then uses only `role()` and `clusterMode()` and **discards both serials**. No peer corroboration occurs on the inventory path. Separately, FROZEN `14E` PP-4 specifies `priority`, `preemptive`, `state-sync`, `mgmt-ip`, `peer-info/state`, `conn-status`, `conn-ha1`/`conn-ha1-backup`/`conn-ha2`, and `running-sync` — **none of these are parsed.** The HA read is issued and its answer is ~85% discarded.

**Contradiction to report, not to reconcile** (`AGENTS.md` authority hierarchy): gate row #2's safe-telemetry column states HA facts are *"not persisted by this movement (14C D-4 names no HA column)"*, while migration V17 / `InventoryHaFact` persists role and mode per context. Two authorities disagree; the Product Owner or the higher authority resolves it. I have not reconciled it.

---

## 4. Fail-closed error diagnostics

§5.2 of the document asks for descriptive terminal reasons. **That part is already built** — `InventoryJobExecutor:150-157` emits `credential_unresolvable:` / `connect_failed:` / `identity_mismatch_refused:` with elapsed time, and the state transitions carry them. Credit where due; the document should stop describing it as pending.

The real gap is the opposite one: **the PAN path fails open.**

`InventoryCapabilityExecutor:619-627`:

```java
private String xmlApiOutput(ApiTarget target, String cmd, Map<String,String> headers) {
    XmlApiResult result = transport.xmlApiCall(...);
    return xmlOutput(result).orElse("");          // XmlApiResult.Failed -> ""
}

private static Optional<String> xmlOutput(XmlApiResult result) {
    return result instanceof XmlApiResult.Completed c ? Optional.of(c.body()) : Optional.empty();
}
```

Two independent fail-open holes:

- A `Failed` transport result (TLS failure, timeout, I/O error) becomes `""`, which every parser reads as "zero interfaces, zero routes."
- A `Completed` result is returned **regardless of `statusCode()`**, and no caller ever checks for `<response status="error">`. A PAN-OS 403 or 400 envelope is handed to the parsers as if it were data.

The Check Point branch guards against exactly this (`:228-234`, `no_interfaces_or_routes_discovered`). **The Palo Alto branch has no equivalent.** So `collectPaloAlto` returns `InventoryResult.Completed` with empty contexts, `InventoryJobExecutor:108` writes outcome token `MATCHED`, the job transitions to `COMPLETED`, and an **empty inventory is persisted as a successful run** — over 39 devices, with a green UI. That is a direct violation of the UNKNOWN / fail-closed law: collection failure recorded as a known state.

The same shape makes `14E` PF-3 dangerous. `PaloAltoSystemInfoParser` parses only `serial`, `sw-version`, `model` — it **does not extract the `advanced-routing` flag**, and nothing branches on it. A PAN-OS 11 firewall with advanced routing enabled has logical routers, not virtual routers; `show routing route` yields nothing usable; routes come back empty; and the fail-open above records that as success. On a 39-device fleet the probability that none is running advanced routing is not one you should assume.

**Finally, `13F` ID-M2 is not satisfied on this path.** ID-M2 requires an identity mismatch be "surfaced as a visible warning… written to the audit trail… **never silent**." On the inventory path, `WARN_AND_CONTINUE` falls through with no log line, no audit row, and no marker (`:272-277`). The class javadoc acknowledges this and defers it to "a successor movement." That successor is now on the critical path: this campaign is the first bulk PAN identity comparison the product will ever perform.

---

## 5. Execution readiness — four defects that stop the campaign on contact

These are not risks. They are defects in the path the campaign will execute, confirmed by reading the code.

**B1 — `type` is never transmitted.** `PanXmlApiTransport.xmlApiCall` encodes **only** `spec.formParams()` (`:106`). The `XmlApiSpec.type()` component is never read by the transport. The confirm, inventory and configuration executors all build op reads as `new XmlApiSpec("GET", "op", "", "direct_firewall", Map.of("cmd", cmd), headers)` and keygen as `Map.of("user", …, "password", …)` — so the POST body is `cmd=<show>…` and `user=…&password=…`, with **no `type=op` / `type=keygen`**. PAN-OS rejects both.

The codebase proves this internally, without needing vendor documentation: `PanoramaApiRoutes:43,52` puts `form.put("type", …)` **into formParams**, and `ConfigurationReadPlan:49-50` does the same (`type=config&action=show&xpath=/config`). Discovery works for exactly that reason. The op/keygen paths were written to the other, non-functioning convention. Symptom on the fleet: every device fails at `palo alto key generation did not return a usable key` — a misleading reason for an HTTP 400.

**B2 — undefined URI scheme, uncaught, with an infinite retry tail.** `DiscoveryRunService:351-353` sets a PAN endpoint's `addressRef` to `candidate.ownAddress()` — a bare IPv4 address. `WorkerClaimLoop:217` passes it straight through as `new ApiTarget(endpointId, addressRef)`, with no normalization anywhere. `PanXmlApiTransport:103` then evaluates `URI.create("<addr>/api/")` → scheme `null` → `HttpRequest.newBuilder` throws `IllegalArgumentException`. That is **unchecked and caught nowhere** — not by the transport's `catch (IOException | InterruptedException)`, not by `CompositeDeviceTransport`, not by `InventoryCapabilityExecutor`, not by `InventoryJobExecutor`, and `WorkerClaimLoop:200` has only a `finally`. Consequence: the attempt row is already written, the lease stays `EXECUTING`, **no `FAILED` transition and no terminal reason are ever produced**, and `JobReconciler`'s `findExpiredAllBoundaryNo` branch requeues the job to `REQUESTED` — a permanent retry loop across 39 devices with zero operator-visible diagnostic. This fires *before* B1. `PanoramaEnumerationAdapter:147-148` already builds `"https://" + host + ":" + port` correctly; the inventory/confirm path simply never inherited it.

**B3 — the leading-zero serial strip** (section 3 above).

**H1 — the PAN fail-open** (section 4 above).

**Why the test suite is green:** `ScriptedPanDeviceTransport` keys responses on `ApiTarget.baseUrl()` and **never inspects the form body or validates the URI**. The fixtures cannot express either B1 or B2. This is a clean instance of `AGENTS.md` "Automated validation != real-environment validation" — and the reason the gate document's own caveat ("parser bindings unverified until the first live run") should be read as covering transport bindings too.

**One governance note on the enrollment step:** `ConfirmCapabilities:54-56` registers `device_confirm_palo_alto` as `CAP_OFFLINE`, with gate reference `"UNKNOWN"`, an empty gate registry (`key -> List.of()`), and connect/disconnect as its only declared steps — while `pan_inventory_collect` is `CAP_VALIDATED` with real gate references. The Registration → Enrolled step this campaign depends on is the **less** gated of the two. Please confirm whether `CAP_OFFLINE` actually blocks admission; if it does not, the C4 maturity state is decorative and that is its own finding.

---

## 6. Answers to the document's four questions

1. **Dual-mode or proxy default?** Dual-mode, with `pan_panorama_proxy` as the realistic default for this estate — forced by the single-trust-anchor architecture, not chosen on preference. Requires four new gate rows, form-body `target`, a distinct `DEVICE_VIA_MANAGER` evidence grade, and one measurement pass.
2. **Does `ifnet/entry/fwd` mapping handle shared VRs cleanly?** The mapping is correct per PM-3; the **presentation** is not clean. Shared-VR routes are duplicated into every vsys with no provenance marker. Add the attribution field. Zones are not involved — nothing in this path reads `zone`.
3. **Is the Java HA identity port adequate?** No. It is worse than the Python original: it introduces a leading-zero strip the Python deliberately avoids, discards both HA serials without corroborating them, and parses ~15% of PP-4's specified fields.
4. **Unreachable device during bulk onboarding?** Retryable, not `FAILED` — but only once failures are classified. Distinguish *transient* (`connect_timeout`, `tls_handshake_failed`, `http_5xx`) → bounded retry with backoff, terminal reason recorded on each attempt; from *terminal* (`auth_failed`, `identity_mismatch_refused`, `http_403`) → `FAILED` immediately, because retrying an auth failure 39 times against a banking estate's device accounts risks lockout. Today neither class is distinguishable, because B2 produces no state transition at all and H1 produces a false success.

---

## Verdict

# [APPROVED WITH RECOMMENDATIONS]

**— subject to a hard execution hold on the 39-device run.**

**Approved as written:** §2 (the four closed reads) and §3 (the one-box model). Both are faithful transcriptions of FROZEN `14E` PF-1..PF-3 / PM-1..PM-4, and `InventoryCapabilityExecutor` implements them accurately. The model is sound and the scope is right.

**Not approved as written:** §4 (transport — understates the trust-anchor constraint, proposes a URL form that leaks serials into logs, and omits that proxying needs its own gate rows) and §5 (mis-scopes the fixes: §5.2 is already built; the actual gaps are fail-open reads, the undefined-scheme exception being uncaught, and the missing `type` parameter).

**Execution hold — the campaign must not dispatch until all four close:**

| | Defect | Fix |
| --- | --- | --- |
| **B1** | `type` never sent on op/keygen | Move `type` into `formParams` for every PAN call, following `PanoramaApiRoutes`; add a closed-set assertion like `isMemberOfClosedSet` over the inventory read plan |
| **B2** | Bare-IP `baseUrl` → uncaught `IllegalArgumentException` → silent retry loop | Normalize to `https://<host>:<port>` at `ApiTarget` construction; catch `RuntimeException` in `xmlApiCall` and return `XmlApiResult.Failed`; add a regression test asserting a terminal `FAILED` reason |
| **B3** | Leading-zero serial strip | Reduce `normalizeSerial` to `.strip()`; invert the test assertions |
| **H1** | Fail-open: failed/errored reads → empty inventory persisted as success | Propagate `XmlApiResult.Failed` as a typed failure; check `statusCode()` and `<response status="error">`; add the PAN equivalent of the CP `no_interfaces_or_routes_discovered` guard |

**Then, before freeze:**

5. Parse `advanced-routing` and fail closed with `UNSUPPORTED_ROUTING_MODE` when it is enabled and no logical-router read is gated (PF-3).
6. Complete PP-4 HA parsing; corroborate `local-info`/`peer-info` serials reciprocally under exact comparison; record `MATCH` / `MISMATCH` / `MISSING` / `NOT_EVALUABLE`, never raw values.
7. Implement ID-M2's visible warning + audit row for `WARN_AND_CONTINUE` on the inventory path.
8. Add route attribution provenance (`EXCLUSIVE` / `SHARED_VIRTUAL_ROUTER` / `UNATTRIBUTED`).
9. Fix the `vsys` fallback (`"1"` → `physical`/`UNKNOWN`) and self-closing-tag matching.
10. Author four `pan_proxy_api` gate rows and run the `target=` response-shape measurement before any proxy code is written.
11. Specify fleet pacing; do not raise concurrency past one per vendor without the interaction-safety gate.
12. Resolve the gate-row-#2 vs. V17 HA-persistence contradiction at PO level — reported, not reconciled.

**Recommended next movement:** `IMPLEMENTATION` at Normal (strong) tier for B1–B3 and H1 — these are deterministic fixes inside already-frozen contracts and need no new one. Items 5–6 and 10 require a `CONTRACT` movement at High tier before implementation, because they introduce new vendor semantics and a new API route class.

**Suggested real-environment validation, one bounded step:** after B1–B3/H1 land, run `pan_inventory_collect` against **one** multi-vsys firewall and report a SAFE SUMMARY — physical port count, per-vsys interface counts, per-VR route counts, HA role, and serial comparison as `MATCH`/`MISMATCH` only. Do not dispatch the fleet until that single device returns non-empty contexts and an explicit identity verdict.
