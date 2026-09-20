# Security & Operational Risk Review — Phase A Failover Pre-Flight Engine

**Reviewer:** Fable (Enterprise Security Architect)
**Scope:** `ui2/job-engine/.../failover/**`, `ui2/service/.../failover/**`, `FailoverPreflightController`, `OperationsScreen.tsx`
**Basis:** The submitted walkthrough and code excerpts only. I did not execute tools in this consultation, so findings against files I was not shown (`PreflightReport.fromResults`, `CheckResult` factories, `ClusterMemberEvidence`, `PolicyParityCheck`, the frontend tests) are stated as *verify*, not as confirmed defects. Everything marked **CRITICAL** is visible in the code you supplied.

---

## Verdict

# [REJECTED]

Not for design quality — the SPI, the verdict vocabulary, the closed registry, and the deliberate absence of a mutation endpoint are correct and, in places, better than the average first cut. It is rejected because **three of the five safety properties the walkthrough claims are not true of the code as submitted**:

1. The service fabricates a fully-healthy evidence snapshot and runs the battery over it, emitting `NO_BLOCKING_CONDITIONS_OBSERVED` from invented data.
2. `TwoSidedSplitBrainCheck` is structurally incapable of ever firing against the evidence the service actually produces — the B6 single-member false-pass risk is not eliminated, it is hardcoded into a pass.
3. The Operations screen renders hardcoded all-`PASS` constants and never calls the API, while telling the operator the evidence is "verified" and "Fresh."

A readiness screen that always says *ready* is worse than no readiness screen, because it manufactures operator confidence. That has to be fixed before this can carry a Phase B authorization decision.

---

## CRITICAL findings

### C-1. `PreflightService.buildSnapshotForCluster` fabricates evidence and the engine cannot tell
**File:** `PreflightService.java`, `buildSnapshotForCluster`, the `// Standard AIView reference baseline` branch.

When fewer than two members are enrolled, the service constructs `defaultA`/`defaultB` with hardcoded `"ACTIVE"`/`"STANDBY"`, `"SYNC_OK"`, zero link errors, identical policy hash `sha256:4f8a2b1c9e3d5f7a`, CPU 28/19, memory 46/38, `Instant.now()` — and hands that to `preflightRegistry.evaluateAll()`. The battery then, correctly and deterministically, returns `NO_BLOCKING_CONDITIONS_OBSERVED` over data that describes no real cluster.

The REST response for that report is byte-for-byte indistinguishable from a real one. There is no `evidence_grade`, no `synthetic: true`, no `INSUFFICIENT_EVIDENCE` anywhere. This is the textbook violation of the UNKNOWN / fail-closed law: *"Absence of evidence is not evidence of absence"* and *"explicit `UNKNOWN` over invented certainty."*

**Required:** a cluster with fewer than two corroborated members must produce a report whose every check is `INSUFFICIENT_EVIDENCE` with its own `defaultPolicy()`, rolling up to `BLOCKING_CONDITIONS_PRESENT`. Demo fixtures belong in `src/test/`, never in a `@Service` on the response path.

### C-2. `extractMemberEvidence(..., boolean assumeActive, ...)` assigns cluster role by list index
**File:** `PreflightService.java`, `extractMemberEvidence`; caller passes `true` for `members.get(0)`, `false` for `members.get(1)`.

`selfState` and `peerState` are *assigned from the parameter*, not observed. Consequences:

- In `TwoSidedSplitBrainCheck.evaluate`, `aActive` is always `true` and `bActive` always `false`. The `aActive && bActive` branch and the `!aActive && !bActive` branch are **dead code on every real cluster**. The check can only return `PASS` or `INSUFFICIENT_EVIDENCE`.
- `peerState` is derived from the same assumption, so the "two-sided corroboration" is one fabricated side asserting about the other — precisely what *"A member's report about its peer != independent peer observation"* forbids.
- Ordering in a repository result set is presentation, not identity. Using it to determine which firewall is carrying production traffic violates *"Presentation identity != security identity."*

This is the single most dangerous line in the change. A genuine split-brain — both members active, traffic blackholing — renders on the operator's screen as a green `PASS` on the check specifically named "Two-Sided Split-Brain Prevention."

Also hardcoded in the same method and equally load-bearing: `"sha256:policysync"` for **both** members (so `PolicyParityCheck` can never detect policy drift), `"SYNC_OK"` whenever any inventory run exists, `"CLUSTER_XL_HA"` regardless of vendor, zero link errors, constant CPU/memory.

### C-3. `bothMembersDirectlyObserved()` is satisfied by a stale database row
`observed = run.isPresent()` — the existence of *any* latest inventory run, of unbounded age. The snapshot then stamps `Instant.now()` on both members. "Same collection window," which is the entire epistemic basis of the two-sided claim, is nowhere enforced. There is no maximum evidence age, and `serializeReport` exposes `generated_at` (evaluation time) but not the evidence observation time, so a consumer cannot recover the distinction.

**Required:** carry `evidence_observed_at` per member into the report, enforce a maximum corroboration skew between the two members, and return `INSUFFICIENT_EVIDENCE` past the threshold.

### C-4. Vendor — and therefore the required-check manifest — is inferred from a name substring
```java
boolean isPan = clusterRef.toUpperCase().contains("PAN") || clusterRef.toUpperCase().contains("TANGO");
```
A display label selects which **security control set** applies. `CLS-PANTHER-02` becomes a Palo Alto. Worse, `TANGO` is a token from your own AIView pseudonym alphabet, so the vendor is being inferred from the masking layer. Identity law, verbatim: a hostname or display label *"is never a join key or an identity gate."* This one is both.

### C-5. `OperationsScreen.tsx` is a mock presented as evidence
`DEMO_CHECKS_CP` / `DEMO_CHECKS_PAN` are module constants. `handleRunBattery` is `setTimeout(600)` that toggles a boolean and changes nothing. The component never imports a client, never calls `/api/v2/failover/...`. Yet it renders:

- `"Evaluated: Just now (Fresh)"` — hardcoded.
- `"All {n} pre-flight checks evaluated cleanly against verified evidence. Zero blocking conditions detected."` — the engine cannot assert "verified evidence."
- In the details drawer, for *every* check including advisory ones: `"Two-sided independent observation corroborated from both active (FW-TANGO-04) and standby (FW-JULIET-06) members."` — a corroboration claim on a static string.

The `npm test` / 106-passing result proves the component renders constants. It is not evidence that the UI reflects engine output. Ship this either wired to the API, or behind an unmistakable `MOCK — NOT LIVE DATA` banner with the mutation-gate copy removed.

### C-6. Privacy fail-open: raw `clusterRef` reaches the UI, and a null pseudonymizer degrades to raw identities
Two paths, both in code you supplied:

- `serializeReport` emits `body.put("cluster_id", report.clusterId())`, and `clusterId` is the raw `@PathVariable clusterRef`. Only `masked_cluster_name` is pseudonymized. The unmasked reference crosses to the browser in every single response.
- `pseudonymizer != null ? pseudonymizer.maskDeviceName(...) : rawHostname` and `... : clusterRef`. These null guards exist only to let tests skip the bean, and the fallback branch is exactly the branch that publishes unmasked customer hostnames. Constructor-injected Spring beans are never null in production; delete the guards and use a real fake in tests.

Per the AIView law, the API contract should carry an opaque UUID plus the pseudonym, and nothing else.

### C-7. Unknown vendor ⇒ empty manifest ⇒ the coverage guard is vacuous (fails open)
`getRequiredCheckIdsForVendor` returns `Set.of()` for any vendor not in `{CHECK_POINT, CHECKPOINT, PALO_ALTO, PAN_OS}`. The manifest loop then iterates nothing and injects nothing. A vendor string of `"Check Point"` (with a space), `"checkpoint-r81"`, or any future vendor produces a report with **zero required-coverage enforcement**. If `appliesTo` also filters most checks out, you get a near-empty `checks[]` array and a green `NO_BLOCKING_CONDITIONS_OBSERVED`.

Fail-closed requires the inverse: an unrecognized vendor is itself a blocking condition.

Secondary: `vendor.toUpperCase()` uses the default locale. None of your current keys contain `i`/`I`, so this is latent rather than live — but on a `tr-TR` host (yours) it will bite the first time a vendor token contains an `i`. Use `Locale.ROOT` throughout.

---

## Direct answers to your five questions

### 1. Complete device mutation suppression — **PASS, with one caveat**
I can find no device-mutation path. The SPI is a pure function over an immutable snapshot with no transport or credential handle. `PreflightRegistry` only iterates. `buildSnapshotForCluster` reads `DeviceRepository` / `DeviceInventoryRepository` — persistence, not devices. No `job_type` is registered, no executor, no vendor adapter. The browser sends no command, argv, path, or route; the architectural invariant *"No Browser → device path"* holds.

The UI's `disabled` buttons are cosmetic and client-side — correctly so. **The real control is that no mutation endpoint exists**, and that control is sound. State it that way in the design doc so nobody later mistakes the tooltip for the gate.

Caveat: `POST /{clusterRef}/preflight` is unauthenticated (see H-1) and unbounded (H-2). Read-only does not mean risk-free.

Corollary you should not skip: because the engine reads *persisted inventory*, the checks are written as if they consume live, same-window, two-sided reads and are in fact fed database rows plus constants. Phase A is safely read-only, but it is not measuring what the check names claim to measure.

### 2. AIView & privacy compliance — **FAIL**
Two concrete leaks (C-6): raw `cluster_id` in every response body, and the null-pseudonymizer fallback to raw hostnames. Beyond those:

- Policy hashes are carried in the snapshot and likely rendered in `PolicyParityCheck`'s summary. Per the sensitive-identity law, emit `MATCH` / `MISMATCH`, never the fingerprint. *Verify `PolicyParityCheck`.*
- No raw CLI strings anywhere — good, and `catch` deliberately logs `ex.getClass().getSimpleName()` rather than `ex.getMessage()`, avoiding hostname/IP leakage through exception text. That is a genuinely good instinct; keep it and comment it so it survives refactoring.
- The privacy gate passing 2,586 files with 0 findings does not cover C-6, because both leaks are runtime data flows, not literals in source. Add a serialization-layer test asserting no response field ever equals the raw `clusterRef`/hostname.

### 3. Two-sided corroboration vs. the B6 false-pass risk — **FAIL**
The *check* is written correctly: it demands `bothMembersDirectlyObserved()`, refuses to rule out split-brain without it, and treats "neither member active" as a failure — that last branch is a good catch many implementations miss. The logic is right.

The *evidence feeding it* defeats it entirely (C-2, C-3). Role is assigned by list index, so both-active is unreachable; observation is satisfied by a stale row, so "same window" is unenforced. B6 is not mitigated. Until the snapshot carries two genuinely independent, timestamped, same-window self-reports, this check should return `INSUFFICIENT_EVIDENCE` unconditionally rather than `PASS` — that would at least be honest.

### 4. Fail-closed manifest & invariant enforcement — **PARTIAL**
Correct: the `catch (Exception)` preserves `check.defaultPolicy()` rather than downgrading to advisory, and adds the id to `executedCheckIds` so the shortfall loop doesn't double-report. Good pattern.

Gaps:
- **Fails open on unknown vendor** (C-7).
- Catches `Exception`, not `Throwable`. A `NoClassDefFoundError` from a missing check class escapes `evaluateAll` entirely — no report rather than a blocking report. That surfaces as HTTP 500, which is *acceptably* fail-closed, but make it deliberate and tested rather than incidental.
- **Unverified and decisive:** does the roll-up in `PreflightReport.fromResults` treat `INSUFFICIENT_EVIDENCE`, `COLLECTION_FAILED`, and `UNSUPPORTED` with `BLOCKING` enforcement as blocking? The field is named `blockingFailureCount`, which suggests it counts `FAIL` only. If so, **every fail-closed path in this engine is fail-open at the verdict** — the injected shortfall result, the exception result, and the mode gate all evaporate. This is the first thing to check, and it needs a generated-matrix test over all 7 × 2 status/enforcement combinations.
- Related: `PlatformAndModeGateCheck` calls `CheckResult.unsupported(id, name, category, msg, remediation)` — **omitting `defaultPolicy()`**, unlike every other factory call in the same file. Whatever enforcement `unsupported()` hardcodes silently overrides the check's declared `BLOCKING`. Verify; if it defaults to advisory, the claim "refuses VSLS/VRRP/Maestro as UNSUPPORTED (BLOCKING)" is false.
- The manifest-shortfall `CheckResult` is built with the 8-arg constructor ending in `null`, while `serializeCheck` calls `check.observedAt().toString()`. If `observedAt` is that `null`, the fail-closed path NPEs into a 500 on exactly the path that must produce a report. Verify.
- `PreflightRegistry` is not `final`; a subclass can override `getRequiredCheckIdsForVendor` to return empty. Make the class `final` and the manifest map an immutable static.
- It is also instantiated with `new PreflightRegistry()` inside the service constructor rather than injected — fine today, but it blocks per-tenant enforcement overrides in Phase B.

### 5. Verdict vocabulary — **PASS**
`PreflightVerdict` carries exactly `NO_BLOCKING_CONDITIONS_OBSERVED` and `BLOCKING_CONDITIONS_PRESENT`. No `SAFE_TO_FAILOVER`, no `READINESS_CONFIRMED`, no `DEGRADED_PROCEED_WITH_RISK`. The phrasing is observational rather than authorizing, which is exactly right and consistent with the `OP.0a` invariant. The seven-value `CheckStatus` set is also well chosen — distinguishing `INSUFFICIENT_EVIDENCE` from `COLLECTION_FAILED` from `NOT_EVALUABLE` from `UNSUPPORTED` is the distinction most implementations collapse.

Two things erode it downstream:

- **The UI banner re-authorizes what the enum refused to.** Green fill, white check mark, "evaluated cleanly against **verified evidence**," "**Zero** blocking conditions detected." The enum says *we observed nothing blocking*; the banner says *you're good to go*. Per *"Readiness != authorization,"* render this neutrally — no green, no check mark — and state the negative claim as a negative: "No blocking conditions were observed in this evaluation. This is not an authorization to fail over."
- **The TS union drops three statuses.** `status: "PASS" | "FAIL" | "WARNING" | "INSUFFICIENT_EVIDENCE"` cannot represent `COLLECTION_FAILED`, `NOT_EVALUABLE`, or `UNSUPPORTED`. And `StatusChip tone={... : "bad"}` collapses everything non-PASS/non-WARNING into red. So `UNSUPPORTED` renders identically to `FAIL`, and `INSUFFICIENT_EVIDENCE` renders as a confirmed failure. The engine's careful separation of *known-bad* from *unknown* is destroyed at the exact layer where the operator reads it. Give unknown states their own neutral tone.

---

## HIGH findings

**H-1. No authorization on the controller.** `FailoverPreflightController` has no `@PreAuthorize`, no role gate, nothing. The class comment says "fully accessible under the aiview inspection persona," but nothing enforces the persona. Cluster HA topology, member health, version/policy parity and resource state is high-value reconnaissance. Gate both verbs on `role:replay_viewer` at minimum.

**H-2. Unbounded, unevicted, untenanted cache.** `reportCache` is keyed on an unvalidated `@PathVariable`. Expiry is checked on read but entries are never removed, so arbitrary `clusterRef` values grow the map without limit — a trivial memory-exhaustion vector against an unauthenticated endpoint. Validate `clusterRef` against a known-cluster allowlist before it ever becomes a key, bound the map, and evict on expiry. The key is also not scoped by tenant or persona; if this becomes multi-tenant, that is a cross-tenant read.

**H-3. `GET` performs evaluation.** `getLatestReport` silently calls `evaluateCluster` on a cache miss, so a GET does repository work and mutates the cache. Either make GET return `404`/`204` with "no current report" and require POST to produce one, or document the side effect and rate-limit it.

**H-4. The mode gate checks mode but never platform.** It is named "Platform & HA Mode Gate" and the walkthrough claims it refuses VSLS, VRRP, Maestro and Active/Active. It only inspects `snapshot.haMode()` against an allowlist. There is no VSX/VSID inspection and no Maestro/SMO detection. A VSX gateway in ClusterXL HA reports a supported mode string and passes the gate, while VSX failover semantics are per-Virtual-System and the physical-endpoint identity is not the operational unit. `"HIGH_AVAILABILITY"` in the allowlist is far too generic a token to bear this weight. The allowlist approach is right; the inputs are insufficient.

**H-5. Thresholds are undocumented magic numbers.** `delta ≤ 100`, CPU `< 80%`, memory `< 85%`, `≥ 2` flaps / 24h. None is traceable to vendor documentation. Worse, the sync delta compares Check Point sync-queue events against Palo Alto unconfirmed sessions using one threshold — different units, one constant. Per vendor semantics law, a safety-critical threshold without documentation is `UNKNOWN`. Extract to named, documented, per-vendor constants.

**H-6. `StandbyResourceHeadroomCheck` measures the wrong quantity.** Standby idle utilization (19% CPU) does not answer whether the standby can absorb the active's *current* load. The snapshot already carries what look like session-count and capacity fields (`18400 / 200000`); headroom should be `active_load vs standby_capacity`, not `standby_idle vs fixed_threshold`.

**H-7. A second verdict roll-up outside the test-enforced boundary.** `tests/test_architecture_convergence.py` enforces "exactly one verdict roll-up" over `utils/failover/` in Python. `PreflightReport.fromResults` is now a second verdict authority, in Java, outside that test's reach. Mirror the generated-matrix invariant into `architecture-tests` so the `SAFE_TO_FAILOVER` prohibition is enforced on both implementations, not just the one the Python test can see.

**H-8. Check Point has no pending-policy-install check.** `PendingCommitCheck` appears only in the PAN manifest. Check Point has directly analogous semantics — uninstalled policy, pending changes on the management side — and failing over to a member with a stale installed policy is exactly the failure mode this engine exists to prevent. Either add a CP equivalent to `cpRequired` or document explicitly why CP is exempt.

**H-9. Reports are ephemeral and unidentified.** In-memory only, no report id, overwritten on re-evaluation. A Phase B four-eyes authorization must cite an immutable, persisted report — otherwise the approver signs off on a report that no longer exists. Add a report id and a persisted audit record before Phase B design freezes.

---

## MEDIUM / governance

- **M-1.** `serializeReport` has no `schema_version`. Add one before any consumer depends on the shape.
- **M-2.** The walkthrough says "Filter Tabs: All Checks (12)" and "12 built-in checks," but `DEMO_CHECKS_CP` has 10 and `DEMO_CHECKS_PAN` has 11 — the per-vendor filtering is correct, the reported count is not. Minor, but it is the kind of number that gets quoted into a PO sign-off.
- **M-3.** AGENTS.md is level-1 authority. This build amends the constitution (the external-consultation law) and then cites compliance with that new law in the same session. Self-ratification. The amendment needs its own PO decision record, referenced from the AGENTS.md text, the way the 2026-09-19 host amendment is. `git status` shows `M AGENTS.md` uncommitted with no accompanying decision record.
- **M-4.** The walkthrough does not state the status line of `docs/design/UI2_0_FAILOVER_ENGINE_ARCHITECTURE.md`. This build introduces new identity/operational-unit semantics and a new verdict authority, so the lifecycle requires a **FROZEN** contract *before* implementation. If that document is `DRAFT`, the implementation preceded its authority.
- **M-5.** Status should be recorded as `AUTOMATED_VALIDATED`, not `DONE`. Phase A does not touch the network, but it makes vendor-semantic claims (what `cphaprob` reports mean, what PAN path-monitoring state implies) that require real-environment corroboration before they can be load-bearing.

---

## What is genuinely good

Worth preserving through the rework, because it is the hard part and you got it right:

- The pure-evaluator SPI with an immutable snapshot and no transport or credential handle. The invariant is stated in the interface Javadoc where it belongs.
- The seven-value `CheckStatus` vocabulary — the `INSUFFICIENT_EVIDENCE` / `COLLECTION_FAILED` / `NOT_EVALUABLE` / `UNSUPPORTED` separation is the distinction most teams collapse, and collapsing it is how fail-open bugs get born.
- `PreflightVerdict` with two observational values and no authorizing value.
- Exception handling that preserves the check's own `defaultPolicy()` instead of downgrading, and that deliberately omits `ex.getMessage()` from the summary.
- The closed registry with a duplicate-id guard and `List.copyOf` on the accessor.
- Allowlisting supported HA modes rather than denylisting unsupported ones.
- The `!aActive && !bActive` branch in the split-brain check.
- Shipping Phase A with no mutation endpoint at all, rather than a gated one.

---

## Required before re-review

Blocking, in dependency order:

1. **Delete the synthetic-snapshot branch** from `PreflightService`. Fewer than two corroborated members ⇒ every check `INSUFFICIENT_EVIDENCE` ⇒ `BLOCKING_CONDITIONS_PRESENT`. Move fixtures to `src/test/`.
2. **Remove `assumeActive`.** `selfState`/`peerState` must come from observed evidence or be absent. If they are absent, the split-brain check returns `INSUFFICIENT_EVIDENCE`. Same for policy hash, sync status, link errors, CPU/memory.
3. **Enforce the corroboration window.** Carry per-member `evidence_observed_at`, enforce a maximum age and a maximum inter-member skew, surface both in the API.
4. **Replace the `isPan` substring heuristic** with vendor from a verified device record; unknown vendor ⇒ blocking.
5. **Prove the roll-up.** Generated-matrix test over all `CheckStatus` × `EnforcementPolicy` combinations asserting that `INSUFFICIENT_EVIDENCE`, `COLLECTION_FAILED`, and `UNSUPPORTED` at `BLOCKING` all produce `BLOCKING_CONDITIONS_PRESENT`. Fix `CheckResult.unsupported()`'s missing policy argument. Confirm no NPE on `observedAt` in the injected shortfall result.
6. **Unknown vendor ⇒ blocking**, not an empty manifest. `Locale.ROOT` on all `toUpperCase`.
7. **Close the privacy leaks.** Drop raw `cluster_id` from the response; delete the null-pseudonymizer fallbacks; add a serialization test asserting no response field equals a raw identity.
8. **Either wire the UI to the API or label it a mock.** Remove "verified evidence," "Just now (Fresh)," and the static corroboration sentence. Neutralize the verdict banner's colour and affect. Extend the TS status union to all seven values with a distinct neutral tone for unknown states.
9. **Add `@PreAuthorize` and validate/bound `clusterRef`** before it becomes a cache key.

Once 1–7 land I expect this to move to [APPROVED WITH RECOMMENDATIONS] with H-4 through H-9 tracked as Phase B entry criteria. The architecture is right; the evidence plumbing is not yet, and in a readiness engine the evidence plumbing *is* the security control.