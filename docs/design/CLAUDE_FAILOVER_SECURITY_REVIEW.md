# Security & Operational Risk Review — neXus Failover Engine

**Reviewer:** Fable, Enterprise Security Architect
**Movement:** `ARCHITECTURE` (security boundary review)
**Scope caveat:** Per your instruction I ran no tools. Every statement below about repository law is grounded in `AGENTS.md` as supplied in my context. Statements about `OP.2.0`, `utils/action_taxonomy.py` constant names, `tests/test_architecture_convergence.py` assertions, and the status line of `docs/design/UI2_0_FAILOVER_ENGINE_ARCHITECTURE.md` are **unverified** and marked as requiring verification. I have not silently resolved any of them.

---

## VERDICT: [REJECTED]

Rejected **as a single build authorizing CLASS 2 device mutation**. The read-only pre-flight portion (Phase A below) is sound in intent and approvable after the verdict-vocabulary corrections in B4.

The rejection is not a judgment on the engineering quality of the skeleton — the fail-closed instinct, the pre-compiled-reversal-without-auto-rollback rule, and JIT re-check are all correct. It is that the plan asks to implement a traffic-affecting device mutation while missing the three things the constitution makes prerequisites, and while containing four design defects that are individually sufficient to cause an outage or an unauthorized mutation.

To your direct question — *"Are command injection, parameter tampering, or unauthorized privilege escalation risks completely eliminated?"* — **No, and that framing should be retired.** "Completely eliminated" is not a provable property. The defensible claim is "reduced to a named, enumerated, test-enforced residual set." The plan as written does not reach that bar; §1 below enumerates the live surfaces.

---

## Blocking findings

### B1 — Mandatory pre-implementation gates are absent
`AGENTS.md` → *Mandatory build lifecycle*: a FROZEN contract is required before implementation when a task introduces new vendor semantics, new network-device commands, new identity/operational-unit semantics, **CLASS 2+ behavior**, storage/schema migration, or a major security boundary. This plan triggers **all six**. Additionally missing:

- **No network-device command gate entries** for `clusterXL_admin down` / `up`, `request high-availability state suspend` / `functional`, or the verification reads. The gate requires vendor, read/write class, shell/context, timeout, retry, frequency, session reuse, unsupported behavior, secret-output risk, safe telemetry — none are specified.
- **No official vendor documentation research** cited. *Vendor semantics law*: documentation research is mandatory before freezing safety-critical network-command semantics. A demote command is the most safety-critical category the product has.
- **`OP.2.0 §10.2` is cited as authority but its status line is not stated.** Per *Contract-status law*, if `OP.2.0` is `DRAFT` / `DO NOT FREEZE` it cannot authorize the non-rollback rule or anything else. State its status explicitly.
- **`docs/design/UI2_0_FAILOVER_ENGINE_ARCHITECTURE.md` is untracked** in the working tree per the session's git status. An untracked document is not a frozen contract.

### B2 — CLASS 2 execution is not currently authorized by any cited authority, and the "scheduled" reading is ambiguous
`AGENTS.md` → *Network action taxonomy*: *"No automatic (unscheduled-trigger, non-ledgered) network-device write/change operation is permitted at the current maturity; class 1 controlled recovery writes are permitted only through their `RB.x` contracts."*

Two readings exist and **I will not pick one for you**:

- **Permissive:** the parenthetical defines "automatic" as *unscheduled-trigger AND non-ledgered*; a scheduled, ledgered write is therefore outside the prohibition.
- **Restrictive:** the carve-in sentence names CLASS 1 `RB.x` as the *only* permitted device-write form today, so CLASS 2 requires its own new contract form regardless of scheduling.

This is a constitutional ambiguity that a Product Owner or a new FROZEN contract must resolve *in writing* before implementation. Under either reading, CLASS 2 needs an explicit `RB.x`-equivalent contract that does not yet exist.

Separately: the plan asserts the constant `CLASS_2_OPERATIONAL_STATE_CHANGE`. `AGENTS.md` names only the endpoints (`CLASS_0_READ` … `CLASS_4_POLICY_DEPLOYMENT`). **Cite the actual constant from `utils/action_taxonomy.py`; do not introduce a plausible-looking name.**

### B3 — Probable conflict with two test-enforced architectural invariants (report, do not reconcile)
`AGENTS.md` → *Architectural invariants (test-enforced, not merely current)*:

> `utils/failover/` contains only read-only assessment/evidence modules … **The absence of a plan, executor, or vendor adapter is enforced by `tests/test_architecture_convergence.py`, not a current-phase courtesy.**

The plan introduces `FailoverPlanCompiler`, `FailoverJobExecutor`, and per-vendor adapters — in the Java `ui2` tree rather than `utils/failover/`. Whether the invariant is *path-scoped* (Python only) or *product-scoped* (no plan/executor anywhere) is a question the invariant's own wording — "not a current-phase courtesy" — suggests is product-scoped. **Implementing the same forbidden component one language over is the exact shape of a silent reconciliation.** Escalate this to the PO as a named contradiction; do not resolve it inside the build.

### B4 — `READINESS_CONFIRMED` is `SAFE_TO_FAILOVER` under a new name
`AGENTS.md`: *"`OP.0a`'s HA readiness assessment cannot emit `SAFE_TO_FAILOVER` or `DEGRADED_PROCEED_WITH_RISK` — enforced over a generated matrix."* And the evidence law: **"Readiness != authorization. A green readiness assessment is one input to an authorization decision, never the decision itself."**

The plan wires `A3{Readiness Verdict?} -->|READINESS_CONFIRMED| B1[Manual Trigger]` — the readiness verdict directly enables the mutation control. That is semantically identical to the prohibited verdict, and it collapses readiness into authorization at the UI layer. Renaming does not change semantics.

**Required:** readiness may only gate *eligibility to submit a request* (`NO_BLOCKING_CONDITIONS_OBSERVED`). Authorization is a distinct artifact produced by the two-person cycle in B5, and the executor must require the authorization artifact — never the readiness verdict.

### B5 — What is labeled "4-eyes" is one person clicking twice
> *"a confirmation modal requiring an operator reason (≥ 8 chars) is enforced."*

That is single-operator double-confirmation. It provides **zero** two-person integrity. A reason field of ≥8 characters is satisfied by `aaaaaaaa`; it is an audit-quality input, not a control.

**Required for the name to be truthful:**
- Two **distinct authenticated principals**; server-side enforcement that `requesterId != approverId`, including transitively (no shared service account, no delegation-to-self).
- Role separation: `replay_viewer` (the mandated `aiview` inspection role) **must not** be able to request or approve. Define `operator` (request) and `approver` (authorize) as separate roles; `aiview` stays read-only, per the AIView law.
- Reason text is **untrusted input**: bounded length/charset, never interpolated into a device command, log line, or report; stored as a typed field.
- **Execution lease:** single-use, short-TTL, server-issued, bound by signature to the tuple `(planId, clusterOpaqueId, targetMemberOpaqueId, preflightReportHash@T₀, requesterId, approverId, expiry, nonce)`.
- **Replay prevention by server-side atomic consumption** (unique constraint / compare-and-swap on the nonce), **not** by token expiry alone. An expired-but-unconsumed token and a consumed-but-unexpired token must both be rejected.

### B6 — `NoSplitBrainCheck` as specified is structurally incapable of detecting split-brain
> *"`NoSplitBrainCheck.java`: Exactly one active member in cluster."*

Split-brain is **precisely** the condition in which each member believes it is active and cannot observe its peer. A check evaluated against one member's `cphaprob stat` will report "one active, all good" in a live split-brain. This is a check that returns PASS exactly when the danger is greatest.

`AGENTS.md` → *Evidence laws*: **"A member's report about its peer != independent peer observation. One side's claim about the other is one-sided until the other side independently corroborates it in the same evidence-collection pass."**

**Required:** `NoSplitBrainCheck`, `ViableTargetCheck`, `StateSyncCurrentCheck`, and `PolicyParityCheck` must each be evaluated from **both members independently within one collection pass**. If the standby cannot be reached directly, the result is `INSUFFICIENT_EVIDENCE` → **BLOCK**. A peer-derived claim must never satisfy a blocking check.

### B7 — No VSX / virtual-system context, and no HA-mode gate
`AGENTS.md` → *Check Point*: *"VSX actual identity = physical endpoint + VSID; Expert `vsenv <VSID>` is a validated context mechanism."*

`FailoverContext` carries `clusterId`, `vendor`, active/standby node — **no VSID**. On a VSX platform, a `clusterXL_admin down` issued without established VS context acts on whatever context the session landed in. **This is the design defect most likely to cause an unintended production outage on the wrong virtual system.**

Also unaddressed — each must be an explicit `UNSUPPORTED` → BLOCK unless a frozen contract covers it:
- Check Point **VSLS** (per-VS active member; "the active member" is not a cluster-level fact), **Gaia VRRP** clusters (`clusterXL_admin` may not apply), **Maestro / Security Groups** (`asg`-family semantics, entirely different).
- Palo Alto **active/active** HA (suspend semantics differ materially from active/passive).
- **Shell context**: `AGENTS.md` notes validated admin login shell is Expert, that Clish must be invoked explicitly with `clish -c`, and that *"some estate devices land directly in Clish; treat this as a capability, not a platform identity."* `clusterXL_admin` is Expert-context. The executor must **prove** its context before mutating and fail closed otherwise — never infer it.
- **Persistence-across-reboot semantics** of all four commands are safety-critical and must be established from official vendor documentation plus real-environment evidence. Until then they are `UNKNOWN` — I will not supply them from model knowledge, and neither should the implementation.

### B8 — Credentials in `FailoverContext`, handed to every plugin
> *"`FailoverContext` … connection targets, **credentials**, and topology facts."*

Every registered `PreflightCheck` — including SPI-loaded third-party ones — receives credential material and a transport capable of arbitrary commands. This is a credential-boundary violation and conflicts with the *Diagnostic-path law*'s preference for a bounded projection over a new credential surface.

**Required:**
- Checks receive an **already-authenticated, read-only-capability** transport handle. No credential material in the context object, ever.
- Two structurally distinct capability types: `ReadOnlyTransport` (pre-flight; compile-time incapable of mutation) and a mutation capability that exists **only inside `FailoverJobExecutor`**, obtained just-in-time and scoped to the lease.
- A separate, least-privilege device account for mutation, distinct from the read-only collector account.
- `FailoverContext` carries **opaque IDs only** — no hostnames, no management IPs. The AIView pseudonyms (`FW-TANGO-04`) are *presentation* identities and, per the identity law, must never be join keys or reach the executor as a target selector.

### B9 — Command strings as plan data crossing a trust boundary
The plan has `FailoverPlanCompiler` emit `primitiveCommand: "clusterXL_admin down"` and `reversalCommand` as **string fields**, persisted, displayed in the UI, and consumed by a worker in a different module. This is the plan's principal parameter-tampering surface: a DB write, a compromised service, or a tampered `/execute` body becomes an argv fragment on a firewall in Expert shell.

It also sits uncomfortably against the invariant *"no command, argv fragment, path, or API route ever originates in the browser"* — the browser doesn't originate it, but the persisted plan does, and the plan is browser-visible.

**Required:**
- The plan carries an **`operationCode` enum** from a closed, module-level registry. Command text is derived **inside the executor** at issue time from compiled-in constants. Command text never travels as data and is never read back from storage for execution.
- Any human-readable command shown in the UI is a **render-only projection**, structurally incapable of being fed back.
- `/execute` accepts only `{planId, leaseToken, reason}`. The server re-resolves the plan from the ledger and **verifies the plan hash** against the one the approver authorized. A body-supplied plan is rejected outright.
- Add an architecture-convergence assertion: no device-command literal exists outside the closed executor registry.

### B10 — A pluggable registry is a fail-open surface
A check that fails to register cannot fail. A plugin that throws can be silently swallowed. A plugin can shadow a built-in `id()` and return PASS.

**Required:**
- A **per-vendor required-check manifest**. Before emitting *any* verdict, the engine verifies it actually executed every required check for that vendor. Coverage shortfall → `INSUFFICIENT_EVIDENCE` → BLOCK. This is the single most important fail-closed control in the engine.
- **Closed module-level registry in production** (mirroring the existing `job_type` registry invariant). No classpath-dynamic `ServiceLoader` discovery in a production build.
- Built-in check IDs are immutable and non-overridable; duplicate ID → hard startup failure, not last-writer-wins.
- Plugin checks are `WARNING`-only unless allowlisted as `BLOCKING` by a frozen contract.
- Per-check timeout; any exception or timeout maps to `COLLECTION_FAILED`, **never** to PASS or to omission.

### B11 — In-process cluster lock in a multi-replica deployment
> *"Enforces lock on HA cluster."*

`ui2` runs in Kubernetes (`kubectl -n ui2 …` is in the host authorization). A worker is presumptively multi-replica. An in-process lock permits two replicas to demote the same cluster — or to demote both members. **Required:** a durable, DB-backed lease with a **fencing token** validated at command-issue time. Lock scope must be defined for VSX (physical endpoint **and** VS), and must also exclude concurrent backup, config push, and policy-install jobs against either member.

### B12 — No write-ahead intent record
The executor writes the ledger *after* the mutation. If the worker crashes, or the SSH/API call times out, between issuing `clusterXL_admin down` and writing the outcome, the system will later believe no mutation occurred.

**Required:**
- **Intent record written and committed before the command is issued**; outcome record after. Startup reconciliation scans for intents without outcomes and drives them to `OUTCOME_UNKNOWN`.
- **Transport timeout must assume the command was delivered.** Never treat a timeout as "not executed."
- **Retry of a CLASS 2 mutation is prohibited outright.** No idempotency assumption, for either vendor.

---

## Answers to your five review questions

### 1. CLASS 2 classification & command-gate controls
Classification as an operational state change is directionally right, but see B2 (constant must be cited from the taxonomy, and CLASS 2 is not yet carved in) and B1 (no gate entries exist).

**Residual risk surfaces after the recommended fixes — the honest enumeration:**

| Surface | Status after fixes |
|---|---|
| Browser-originated command/argv | Closed by the typed-intent invariant + B9 |
| Plan-data tampering (DB / `/execute` body) | Closed by `operationCode` enum + plan-hash verification (B9) |
| Wrong-target mutation via presentation identity | Closed by opaque IDs + pre-mutation identity verification (R1) |
| Wrong VS on VSX | **Open until B7 is implemented** |
| Plugin-mediated credential access | Closed by B8 |
| Fail-open via missing check | Closed by required-check manifest (B10) |
| Privilege escalation | **Partially open by design** — CP demote requires Expert (root-equivalent). Mitigate by least-privilege separate account, JIT credential retrieval, lease-scoped capability, no caching in context |
| Double execution (multi-replica) | Closed by B11 |
| Device overload from pre-flight bursts | **Open** — see R5 |

**R1 — Identity verification immediately before mutation.** Per *Palo Alto*: *"Direct evidence requires identity verification wherever the product contract defines an identity gate."* Verify the target's identity **inside the same authenticated session**, in the last step before issuing the command. Mismatch → abort, ledger, alert. Also per the same section, the failover command goes **direct to the firewall, never via Panorama** (Panorama is discovery/intent/provenance only). For PAN, add a blocking check that the device has **no pending/in-flight commit**.

**R2 — Align the result vocabulary with the UNKNOWN/fail-closed law.** `CheckResult.status` is `PASS|FAIL|WARNING|INSUFFICIENT_EVIDENCE`. The constitution's vocabulary is richer and the distinctions are load-bearing: add `COLLECTION_FAILED`, `NOT_EVALUABLE`, `UNSUPPORTED`, `RELATIONSHIP_INCONSISTENT`. Rule: on a BLOCKING check, **any non-`PASS` status blocks**, including `UNSUPPORTED`.

### 2. Two-person integrity & authorization leases
See **B5** — this is currently unimplemented under a name that claims otherwise. The lease/nonce model you describe in the consultation prompt is the right one and should be written into the contract; it is absent from the plan document itself.

**R3 — The `aiview` role must be provably incapable of mutation.** The AIView law mandates that all inspection and PO sign-off occur under `role:replay_viewer`. Add a test asserting `replay_viewer` receives `403` on `/plan`, `/execute`, and `/schedule`.

### 3. Fail-closed & the seven canonical stop-conditions
The stated invariant — *"If ANY blocking pre-flight check returns `FAIL` or `INSUFFICIENT_EVIDENCE`, failover execution is impossible"* — is correct and well-phrased. Three gaps prevent it from holding in practice:

1. **B10** — a check that never ran cannot return `FAIL`. Fail-closed on coverage, not just on results.
2. **B6** — one-sided evaluation makes several blocking checks unsound regardless of the gate around them.
3. **The invariant is asserted in prose, not enforced.** **R4:** add a **generated-matrix test** in the style of the existing `OP.0a` invariant: over the full cross-product of check outcomes, assert that no combination containing a non-`PASS` BLOCKING result can produce an executable authorization. Prose invariants decay; matrix tests do not.

### 4. Scheduled failover safety
JIT re-check at T₀ and automatic-abort-only are the right primitives. Missing controls:

- **R5 — Bind authorization to the schedule cryptographically.** A row in `failover_schedules` that the scheduler trusts is a path to unauthorized mutation via a single DB write. HMAC the row against the lease; verify at T₀. Authorization is never re-derived from the row alone. Check a revocation list at T₀ (cancellation must actually cancel).
- **R6 — Hard execution window.** If the scheduler was down at T₀ and wakes at T₀+3h, it must **abort, not execute**. Require start within N minutes of T₀ *and* within the declared maintenance window.
- **R7 — Authorization staleness.** Cap lead time (≤7 days, configurable); longer windows require re-affirmation. An approval given a week ago is not a statement about tonight's cluster.
- **R8 — Time handling.** Store UTC; display with explicit timezone; never treat a client-supplied datetime as authoritative. DST-ambiguous local times in the picker are a genuine operational hazard.
- **R9 — Define "drift" precisely** as a typed comparison between the authorized report hash and the T₀ report, field by field — not a free-form judgment. And abort is the *only* automatic action: never "wait and retry."
- **R10 — Unattended-mutation notification.** Notify on-call at T₀−X and at execution. Notification-delivery failure is recorded and, for execution (not abort), treated as blocking.
- **R11 — Sequence scheduled execution last.** Unattended CLASS 2 mutation is the highest-risk capability in this plan and should not ship until manual execution is `REAL_ENV_VALIDATED`.

### 5. Non-automatic rollback & the pre-compiled reversal model
**The no-auto-rollback rule is correct and I endorse it strongly.** Auto-rollback on a partially-observed mutation failure is the canonical split-brain generator: you cannot distinguish "command did not land" from "command landed, verification read failed," and issuing `clusterXL_admin up` in the second case can produce two active members.

The **pre-compiled reversal plan is not yet secure**, for two reasons:

- **R12 — A pre-compiled reversal must be marked non-executable and must never carry a lease.** As specified it is a stored, displayed object containing a command string; the failure mode is a stressed operator hitting a one-click reversal during an incident, driving a second transition (and with preemption, a third). Compile it for **disclosure only**. Any actual reversal re-compiles at that time, from current state.
- **R13 — Reversal requires its own full authorization cycle and a *different* pre-flight battery.** Restoring a demoted member is not the inverse of demoting it: the correct gate is *"is the demoted member healthy, is sync complete, and will restoring it cause a preemptive failback?"* Reusing the demote battery is a category error.
- **R14 — Define the ambiguous-verification terminal state.** When the post-condition read is inconclusive, the outcome is `VERIFICATION_INCONCLUSIVE` / `OUTCOME_UNKNOWN`, which must: lock the cluster against all further automated operations, raise a critical alert, require explicit human adjudication before any reversal, and **never** retry the mutation. Per the UNKNOWN law, *"Collection failure is not a known-bad state"* — and equally, it is not a known-good one.

---

## Your two open questions

**Q1 — Pre-flight freshness TTL: 5 minutes, or re-run on button press?**

**Neither, as posed — both, with the authority reassigned.** A TTL on a *previous* report is not a safety control; it is a UI staleness hint. Recommended model:

1. The displayed report carries a **5-minute display TTL** — after that the UI marks it stale and greys the request control. This is presentation only.
2. The **authoritative gate is a T₀ re-run of the full blocking battery inside the executor, in the same authenticated session as the mutation**, for **manual as well as scheduled**. Your plan applies JIT only to scheduled; manual failover is triggered by a human under time pressure and deserves the same rigor, not less.
3. The **lease is bound to the hash of the T₀ report**, never the earlier one.
4. **Bound the residual TOCTOU window explicitly**: if more than N seconds (suggest 30) elapse between the final blocking check and command issue, abort and re-run. The window cannot be eliminated — it must be bounded, measured, and disclosed in the ledger.

**Q2 — Which checks should be BLOCKING vs WARNING?**

Your split is close but wrong in three places, and incomplete in seven.

*Reclassify:*

| Check | Your class | Recommended | Rationale |
|---|---|---|---|
| `PreemptionAwarenessCheck` | WARNING | **Split by action** | Both `clusterXL_admin down` and PAN `suspend` hold the member down, so preemption is low-risk on the **demote**. The real hazard is on **reversal**, where preemption decides who becomes active. → advisory-with-disclosure on demote, **BLOCKING on reversal**. |
| `FlapHistoryCheck` | WARNING | **BLOCKING if recent** | ≥2 transitions in the last 24h (configurable) → BLOCK. Failing over into an already-unstable cluster is how a maintenance action becomes an outage. Older history stays advisory. |
| `ResourceHeadroomCheck` | unclassified | **BLOCKING on the standby** | The incoming active is the one that must carry the load. Insufficient headroom there is a direct outage path. |

*Add as BLOCKING (all currently missing):*

1. **HA-mode/platform gate** — VSLS, VRRP, Maestro, PAN active/active → `UNSUPPORTED` → block (B7).
2. **VS-context resolution** (VSID established and verified) for any VSX endpoint (B7).
3. **Shell-context capability** — Expert context proven, not inferred (B7).
4. **Peer independent corroboration** — both members observed in one pass (B6).
5. **Concurrent-operation exclusion** — no backup, config push, or policy install in flight against either member (B11). Policy install during failover is a well-known outage pattern.
6. **PAN pending-commit check** — no in-flight or queued commit on either member.
7. **Maintenance-window / change-record validity at T₀** for scheduled executions.

*Keep advisory:* session-table/connection-count headroom — until its vendor semantics are proven, per *"Field presence != field semantic proof."*

*And one cross-cutting rule:* `INSUFFICIENT_EVIDENCE` on a BLOCKING check blocks; on a WARNING check it degrades to WARNING. State this explicitly in the contract rather than leaving it to implementation.

---

## Additional findings

**R15 — Raw-evidence law vs. persisted reports.** `CheckResult.details` and `failover_preflight_reports` must store a **typed, pseudonymized projection**, not raw `cphaprob stat` / `show high-availability state` output. Per *Raw-evidence law*: parse the minimum semantics in memory, emit safe enums/counters/relationships, discard the raw response. Add a schema-level assertion that no raw-command-output column exists. `remediationSuggestion` should draw from a **closed catalog**, not free-form generated text — deterministic, and incapable of leaking identity values.

**R16 — Ledger immutability needs a mechanism.** "Immutable Audit Ledger" and "cryptographic outcome" are currently aspirations. Specify: append-only enforced at the grant level (no `UPDATE`/`DELETE` for the service role), rows hash-chained (`prev_hash` → `row_hash`), and each row recording `requesterId`, `approverId`, `planHash`, both preflight report hashes (authorized and T₀), `leaseId`/nonce, `operationCode` (**not** raw command text), target opaque ID, vendor, HA mode, VSID where applicable, outcome, verification result, timestamps, and abort reason.

**R17 — Two-member redundancy disclosure.** On a 2-member cluster, a successful demote leaves **zero redundancy** for the duration. `impactDisclosure` must state this explicitly and quantify the exposure window. This is the risk operators most consistently underestimate.

**R18 — Pre-flight burst control.** Ten checks × two members, re-runnable by button press, violates *"Stability is more important than collection speed"* and *"Do not increase polling/concurrency until the relevant vendor interaction-safety gate permits it."* Require single-session reuse across all checks per device, per-device concurrency caps, and rate limiting on `/preflight`. The frequency field is a required command-gate entry anyway (B1).

**R19 — Testing gaps.** The verification plan covers happy paths and some fail-closed behavior. Missing, all required:
- **Adversarial:** tampered `/execute` body, replayed lease, `requester == approver`, expired lease, plan-hash mismatch, DB-modified schedule row, plugin that throws, plugin shadowing a built-in ID, plugin returning `PASS` for an unsupported vendor, check omitted from the registry.
- **Generated-matrix invariant test** (R4).
- **Split-brain fixture** where both members claim Active — assert `FAIL`, not `PASS`.
- **Crash-injection** between intent and outcome; assert reconciliation to `OUTCOME_UNKNOWN` (B12).
- **Concurrency:** two worker replicas, one cluster; assert single execution via fencing token (B11).
- **VSX fixture:** assert mutation is refused when VS context is unresolved.
- **RBAC:** `aiview`/`replay_viewer` receives `403` on all mutation endpoints (R3).
- **Architecture convergence:** update `tests/test_architecture_convergence.py` to reflect whatever the PO decides in B3 — and add the no-command-literals-outside-the-registry assertion (B9).

**R20 — Project-state and process obligations absent from the plan.** Per the *Project-state update rule*: `scripts/project_queue.py` (`add`/`status`/`note`/`decide`) for `project/backlog.json` and `project/roadmap.json` — agents read `project/QUEUE.md`, never the JSON directly — plus `CURRENT_STATE.md`, `project/feature_registry.json`, `project/build_history.json`. Also confirm `V31` is the next free migration ordinal (concurrent-branch collision risk, and Flyway checksums are immutable once applied).

**R21 — Real-environment validation is itself a mutating act.** *"Never mark a network-facing behavior `DONE` from automated tests alone."* But validating a failover **causes a failover**. Sequence it: lab/non-production cluster under its own maintenance window and its own authorization, fully ledgered, before any customer estate. Status cannot advance past `AUTOMATED_VALIDATED` until that lab evidence exists.

**R22 — Pseudonym generation must not be hardcoded.** `CLS-ROMEO-01` / `CLS-TANGO-01` / `FW-TANGO-04` must come from `TopologyNamePseudonymizer` deterministically, not as frontend literals that can drift from the pseudonymizer. Frontend fixtures use synthetic `192.0.2.x` addressing per the AIView law. Run `python3 main.py --repository-privacy-check` over the new SQL, fixtures, and React fixtures — you have it in the gate list, which is correct.

---

## Path to approval

I recommend splitting this into four separately-frozen builds. This ships the operator value early while isolating the mutation boundary behind its own gate.

| Phase | Scope | New device-mutation risk | Gate to enter |
|---|---|---|---|
| **A** | `PreflightCheck` SPI (closed registry), required-check manifest, the 10 + 7 checks, `PreflightReport`, `/preflight`, UI display | **None** | Fix B4 verdict vocabulary, B6 two-sided evaluation, B8 credential boundary, B10 fail-closed coverage, R2 status enum. Command gate entries for the *read* commands. |
| **B** | Authorization model: real 4-eyes, lease/nonce, ledger, plan compilation as **disclosure only**, executor with the command **suppressed** (dry-run proven end-to-end) | **None** | Phase A `DONE`. FROZEN CLASS 2 contract resolving B2. B3 contradiction resolved by the PO. |
| **C** | Manual execution enabled, lab cluster only | **Yes** | Phase B `DONE`. Full command gate (B1). Vendor documentation research complete, including persistence-across-reboot semantics. B7, B9, B11, B12 implemented. R4 matrix test green. |
| **D** | Scheduled / unattended execution | **Yes, unattended** | Phase C `REAL_ENV_VALIDATED`. R5–R11 implemented. |

**Immediate next actions, in order:**

1. Escalate **B3** to the Product Owner as a named authority contradiction. Do not proceed on either reading.
2. State the status line of `OP.2.0` and of `docs/design/UI2_0_FAILOVER_ENGINE_ARCHITECTURE.md`. If either is `DRAFT`, neither authorizes anything (**B1**).
3. Obtain a PO ruling on the **B2** taxonomy ambiguity, and cite the real constant from `utils/action_taxonomy.py`.
4. Produce the network-device command gate entries for all four mutation commands and the verification reads.
5. Complete official vendor documentation research on CP `clusterXL_admin` (incl. VSX/VSLS behavior, `-p` persistence) and PAN-OS HA suspend/functional (incl. reboot persistence and active/active). Mark anything unestablished as `UNKNOWN` rather than filling it in.
6. Re-scope to Phase A and re-submit for freeze.

**Recommended next movement:** `ARCHITECTURE`, high reasoning — this is a new security boundary with an unresolved constitutional contradiction, which is one of the named escalation triggers. Once B1–B3 are resolved by the PO, Phase A drops to `IMPLEMENTATION` at standard tier.

---

**One closing note on framing.** The plan's strongest feature is that it refuses to auto-rollback. That instinct — *prefer a known-ambiguous state under human adjudication over an automated action that might make it worse* — is exactly right, and it should be applied one level up: prefer shipping a read-only pre-flight engine that operators trust over shipping a mutation path whose authorization model is currently a single person typing eight characters.