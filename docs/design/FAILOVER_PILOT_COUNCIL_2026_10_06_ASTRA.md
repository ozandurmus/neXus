# Failover decision council — 2026-10-06

**Recommendation:** retain the existing CP/PAN worker pipeline, finish all ten PRs, expose the feature estate-wide, and bind each execution to the unit selected at runtime. Preserve automatic progression from explicit confirmation through fresh readiness, failover, verification and conditional return.

**PR 8 can proceed with transport-bound identity, provided the local member row is unambiguously bound to that transport.** Two trusted sessions and matching peer reports alone are insufficient.

**Disclosure:** this is one author applying three separate review lenses, as requested. No subagents or external reviewers ran. The challenge pass below removes seat attribution from propositions, but is not independently blinded. The earlier failed consultations remain failed consultations.

**SESSION START:** REVIEWER / ARCHITECTURE. Inspected checkout: `86e20208`; supplied plan baseline: `7b5b8361`. The older schema-91 state documents do not establish present execution readiness. Scope: the named contracts, plan, earlier review, bounded parser/executor/gate evidence and official vendor documentation. No implementation, device contact or repository write occurred.

## 1. Security and Adversarial Reviewer

**Protected concern:** only the authenticated, authorized operation can cross a mutation boundary, against exactly its approved target.

| Position | Assessment | Required evidence or resolution |
|---|---|---|
| Consent | A single `admin` may approve and start. This is the PO’s chosen policy. | Record the successor role policy; enforce it server-side. Do not silently reinstate universal four-eyes. |
| Consent | Estate-wide availability is compatible with safe execution. | Runtime selection resolves to an immutable unit/member set, authorization window and evidence revision. No hardcoded pilot identity. |
| Dissent S1 | A second submitted username or `approver_id` does not implement four-eyes. | Two authenticated acts from distinct canonical human identities, bound to the same request revision. Two sessions belonging to one person do not qualify. |
| Dissent S2 | Matching cluster output cannot repair an untrusted or incorrectly enrolled endpoint. | Bind each observation to its actual authenticated transport and enrolled endpoint reference. Reject observer swaps, duplicate targets, ambiguous local rows and changed bindings. |
| Dissent S3 | Approval at request time leaves an expiry/revocation race during readiness. | Revalidate authority, membership, ownership, quarantine and evidence freshness at each durable write boundary, including UP/FUNCTIONAL. |
| Dissent S4 | Stopping a run is insufficient if another request can immediately execute against the same incident. | Persistent incident quarantine must survive STOPPED, restart and alternate-route admission. Release must name the exact incident and use concurrency-safe state comparison. |

**Residual dissent:** single-admin operation has less protection against account compromise and operator error than independent approval. That risk remains visible; it does not override the settled PO decision.

**PO questions:** none about scope or the selected approval policy. Exact `cpview -p` measurement authorization remains pending as already specified by the PO.

## 2. Network/Vendor Semantics Specialist

**Protected concern:** observations establish the claimed device state and forwarding evidence without inferring unsupported semantics.

| Position | Assessment | Required evidence or resolution |
|---|---|---|
| Consent | CP DOWN followed conditionally by UP on the former active is the selected method. | Preserve the V101 commands, contexts, one-send limits and conditional return. |
| Conditional consent N1 | A verified endpoint session plus its uniquely identified local row can establish the run-local member binding. | Verify the existing command’s local-row semantics on supported output shapes; require reciprocal membership and role agreement from the other endpoint. A new serial-number command is not inherently necessary. |
| Dissent N2 | `cpstat fw -f policy` is not a replacement for connection-table evidence. | Use it for measured policy information. Do not manufacture session-survival, CPS or throughput claims from it. |
| Dissent N3 | Community documentation does not establish a safe production contract for `cpview -p`. | Obtain the exact-command measurement approval, measure its behavior, and resolve field scope, units, freshness and averaging semantics before using it as a blocking criterion. |
| Dissent N4 | The measured VSX mode label and an actual load-sharing topology must remain distinct. | Use one explicit mode-admission rule throughout precheck, polling and postcheck. Do not accept ambiguous modes before DOWN and reject them only afterward. |
| Dissent N5 | UP/FUNCTIONAL does not universally imply “returns as standby/passive.” | Establish return/preemption behavior before the first write. Refuse an operation whose selected return would predictably violate the required final roles. |

Two concrete source findings belong in PR 8:

- [`CpFailoverChecks.java:39`](/Users/OzanDur/Codo/nexus-standalone/fo-council/ui2/worker/src/main/java/com/securityexpert/nexus/ui2/worker/failover/CpFailoverChecks.java:39) admits `BACKUP` under its `VSLS` classification and maps the local role to `STANDBY`. Official documentation describes `BACKUP` as a different VSX state associated with third and subsequent members. The existing conversion is not adequate semantic proof for this two-member operation. [Check Point cluster-state reference](https://sc1.checkpoint.com/documents/R82/WebAdminGuides/EN/CP_R82_CLI_ReferenceGuide/Content/Topics-CLIG/CXLG/Viewing-Cluster-State.htm)
- [`CpFailoverJobExecutor.java:451`](/Users/OzanDur/Codo/nexus-standalone/fo-council/ui2/worker/src/main/java/com/securityexpert/nexus/ui2/worker/failover/CpFailoverJobExecutor.java:451) requires `HA` in its transition-role predicate, while readiness can accept the measured `VSLS` label. Consequently, an admitted VS-context run can reach DOWN and subsequently fail its role predicate. Resolve this before enabling that execution path.

Return behavior also needs affirmative evidence. CP documents different recovery behavior for **Primary Up** and **Active Up**; PAN explicitly documents possible preemption after making a suspended firewall functional. A successful UP/FUNCTIONAL response therefore cannot prove that traffic remained on the new active. [CP recovery-mode semantics](https://sc1.checkpoint.com/documents/R81.20/WebAdminGuides/EN/CP_R81.20_ClusterXL_AdminGuide/Content/Topics-CXLG/Viewing-Cluster-State.htm), [PAN failover verification](https://docs.paloaltonetworks.com/ngfw/administration/high-availability/set-up-activepassive-ha/verify-failover/verify-failover-pan-os)

**Residual dissent:** aggregate traffic counters support a bounded forwarding observation. They cannot certify preservation of established sessions or successful application transactions.

## 3. Reliability and Operations Engineer

**Protected concern:** crashes, delays and ambiguous delivery cannot produce duplicate writes, misleading completion or unattended recovery.

| Position | Assessment | Required evidence or resolution |
|---|---|---|
| Consent | Reuse the workers, durable job records and existing panel. | Extend their common boundaries; avoid another executor or recovery framework. |
| Dissent R1 | A database transaction cannot atomically include the device’s state change. | Persist the attempt before sending; separately record delivery certainty and observed outcome. Uncertain delivery is never permission to retry. |
| Dissent R2 | A lease expiry alone cannot fence a paused process that later resumes. | Never hand an unresolved dispatch to another sender. Demonstrate that a stale owner cannot send after reassignment; unresolved ownership blocks new mutation. |
| Dissent R3 | A green role poll after UP is insufficient completion evidence. | Add a bounded final health/traffic verification phase and its permitted read frequency. |
| Dissent R4 | Repeating the ordinary readiness predicate while one member is deliberately DOWN can make successful completion impossible. | Define phase-specific applicability and expected conditions before the live run. Unknown semantics cannot be replaced by a blanket exception. |
| Dissent R5 | Four days is a delivery target, not evidence that all dependencies will fit. | Put measurement, identity semantics and database fault tests on the first two days’ critical path. Preserve every P0 closure. |

**Operational dissent:** stopping without UP can leave the unit operating without redundancy. Nevertheless, automatically returning a member after failed checks or revoked authority would violate the selected contract. Reduce this exposure through preflight validation, sufficient authorization time and a prepared human recovery procedure; do not hide an automatic UP in cleanup code.

## 4. Blinded challenge pass

The propositions below omit their originating seat. Because the same author has seen all positions, this is an attribution-blinded self-challenge, not independent validation.

| Proposition | Adversarial challenge | Disposition |
|---|---|---|
| “A trusted SSH connection proves the correct active member.” | What if the output lacks a local marker, both connections reach one member, or the observer labels are swapped? | **Rejected as stated.** Require transport provenance, one local row per observation, distinct local member bindings and reciprocal claims. |
| “Two matching role maps prove reciprocal membership.” | Unrelated clusters can both contain member IDs `1` and `2` with identical roles. | **Rejected as stated.** Preserve enrolled pair binding and reciprocal address relationships; role-map equality alone is insufficient. |
| “Approved `cpstat` can replace the rejected connection check.” | Policy presence says nothing about whether established sessions survived. | **Rejected.** Remove the rejected check explicitly and disclose the reduced evidence coverage. |
| “Nonzero CPView throughput proves traffic survived.” | It could be management traffic, stale data, another VS or unrelated flows. | **Rejected as a broad claim.** Require scope/freshness proof and report only observed forwarding activity. |
| “UP is harmless cleanup.” | It is a second mutation and can cause preemption. Approval may also have expired. | **Rejected.** UP has its own authorization boundary and affirmative preconditions. |
| “All checks must have identical PASS predicates in every phase.” | ADMIN_DOWN and reduced redundancy are intentional between DOWN and UP. | **Rejected.** Reuse the battery with explicit phase semantics; never label an inapplicable check PASS. |
| “Fleet-wide enablement requires removing admission restrictions.” | That would admit unsupported modes, missing identities and quarantined units. | **Rejected.** Remove pilot targeting restrictions; retain capability, evidence and authorization restrictions. |
| “Generic scheduling is disabled, so PRs 2–4 can be declared closed.” | Disabled execution does not establish integrity, key lifecycle or transaction correctness. | **Rejected.** Complete and test those PRs while keeping unused mutation routes disabled. |

**Dissent retained:** security prefers independent approval as a risk reduction; the PO permits single-admin operation. Operations would prefer restoring redundancy promptly; the contract forbids automatic recovery after failure. Network review refuses to equate aggregate activity with session or application continuity. None of these differences is resolved by claiming consensus.

## 5. Synthesis

### (a) Minimal operator flow and state machine

Use the existing persisted states with explicit substeps. A separate durable incident record carries quarantine; it need not become an elaborate second workflow.

| State / substep | UI | Required behavior and stop point |
|---|---|---|
| Selection | Masked unit, vendor/mode, readiness age, authorization status | All estate units are discoverable. Runtime selection resolves server-side to the exact operational unit and members. Unsupported or unverifiable units show their refusal reason. |
| Approval pending | “Awaiting second approval,” where applicable | Required only for the operation-admin policy. No mutation. Aiview can observe status but cannot supply approval. |
| Confirmation | Warning and explicit **OK / Start** | Show target, intended role swap, bounded window, possible interruption and possibility of stopping with one member DOWN/suspended. Bind confirmation to the request revision. |
| `PRECHECK` | “Checking readiness,” with individual results | Run the complete applicable fresh battery on both members. A blocking FAIL/UNKNOWN stops before mutation. Informational rows remain informational. |
| `FAILING_OVER` | “Failing over” | Revalidate authority and ownership; durably reserve/record the attempt; send DOWN once to the verified current active. Browser state follows server state. |
| `SWITCHED` | “Role change confirmed” | Independently establish new ACTIVE and former active DOWN. A missing reply, timeout, conflicting roles or identity change stops subsequent writes and creates quarantine. |
| `POSTCHECK` | “Checking traffic and cluster after switch” | Immediately collect the phase-appropriate post-switch battery. All required results must pass before return. |
| `RETURNING` | “Returning former active to standby” | Revalidate authority and safety; record and send UP once to the original former active. Confirm new ACTIVE / former STANDBY. |
| `POSTCHECK`, final substep | “Verifying final cluster health” | Run the approved post-return verification. Confirm expected roles, restored health and continuing traffic observations. |
| `DONE` | “Failover verified,” with evidence coverage | Only after every required final result passes. Show application/session continuity separately when not evaluated. |
| `STOPPED` | “Stopped before failover” or “Stopped after mutation — intervention required” | Identify phase, last confirmed roles, certainty, failed/unknown check and incident reference. No automatic retry, failback, UP or quarantine release. |

The explicit OK starts the whole authorized workflow. **Do not introduce another confirmation after clean readiness.** Approval/window creation may be incorporated into the same admin interaction, but its authenticated authorization record must remain explicit.

A browser refresh, duplicate click or HTTP retry returns the same run. It must not create another mutation. A UI disconnect does not itself change execution policy.

Before mutation, cancellation can stop the run. After a possible send, “cancel” cannot promise reversal: it stops further mutation and enters the appropriate incident handling.

Retain approval windows from the frozen contracts. Window expiry or revocation before UP blocks UP; plan the window to cover both transitions and all bounded checks. Define the dispatch authorization’s linearization point so revocation races have deterministic outcomes—never promise that revocation can undo an already sent command.

### (b) Verification: evidence, thresholds and unknowns

The current [CP contract](FAILOVER_EXECUTION_CP_CONTRACT.md) must receive an explicit successor amendment for the PO’s rejection of connection-table collection. Remove `fw tab -t connections`, including the existing `-s` collection, from this workflow. Do not silently treat removal as a passing connection-survival check.

#### Existing reads

| Read | Supported claim | Important limit |
|---|---|---|
| `cphaprob stat` | Observer-local and peer roles; phase-specific two-member role agreement | Requires transport/local-row binding. A role report alone is not forwarding proof. |
| `cphaprob tablestat` | Both observers describe the same per-member address relationships | Numeric interface indices remain observer-local. Compare sensitive values in memory; persist relationships only. |
| `cphaprob -a if` | Required interface/CCP status | Interface UP does not establish application reachability. |
| `cphaprob show_bond` | Required bond/link state | Preserve existing `UP!` and minimum-link handling; do not infer health from partial link presence. |
| `cphaprob -ia list` | Critical-device status | An intentional ADMIN_DOWN condition needs an explicit phase rule, not a blanket exemption for all problems. |
| `cphaprob syncstat` | The contracted sync status/counters | Preserve the frozen rule unless amended. A cumulative historical error cannot silently become a windowed delta check. |
| `fw stat` | Existing installed-policy parity/continuity | Preserve name comparison and the amended ≤600-second installation-time skew rule. Policy names stay local. |
| `cpstat os -f routing` | Default-route presence and route-count continuity | Neither route equivalence nor end-to-end reachability is proven. |
| `cat /proc/net/dev` | Scoped interface-byte rate using two samples five seconds apart | Proves activity on selected interfaces, subject to correct selection and counter continuity. |
| `arp -an`; `cphaprob show_failover` | Informational ARP and failover-history observations | Do not promote them to new blockers. |

The policy, role and health checks remain necessary after rejecting the connection table. The rejection changes **what can be claimed**, not the need to verify the switch.

#### `cpstat fw -f policy`

The command is approved by the PO. Official documentation permits parameter ordering in either form, but the existing repository gate uses:

```text
bash -lc 'cpstat -f policy fw'
```

That gate is currently for an inventory session, once per device per run—not the proposed failover phases. Amend the purpose, contexts and frequency explicitly; reuse the existing command/parser where possible. Semantic equivalence does not bypass exact command-key matching. [Official `cpstat` syntax](https://sc1.checkpoint.com/documents/R81/WebAdminGuides/EN/CP_R81_CLI_ReferenceGuide/Topics-CLIG/FWG/cpstat.htm)

Use the measured projection for:

- policy presence;
- locally compared policy equality across members;
- continuity with the pre-switch policy;
- installation-time validity and the existing skew predicate.

The official reference’s illustrated policy/interface output uses the **default** flavor. It does not prove that the exact approved **policy** flavor supplies traffic counters. Do not assume `Total`, `Accept`, `Deny`, bytes or CPS exist in that output.

Replace the existing `fw stat` adapter only after equivalent required policy evidence is demonstrated. Until then, `cpstat` must not weaken the existing policy check.

#### `cpview -p`: measurement before production reliance

The official CPView page describes an interactive statistics utility, but does not establish the candidate `-p` batch-output schema, averaging interval or VS scope. Those remain unverified. [Official CPView reference](https://sc1.checkpoint.com/documents/R82/WebAdminGuides/EN/CP_R82_CLI_ReferenceGuide/Content/Topics-CLIG/cpview.htm)

Prepare the one-off measurement through the existing controlled transport:

- exact command and wrapper: `cpview -p`, with no extra flags, pipes or redirection;
- one explicitly selected enrolled endpoint/context;
- existing trusted SSH path, no new credential path;
- bounded timeout and output size, no retry, no interactive session;
- parse in memory; retain field names, types, units, scope/freshness status and permitted numeric projections only;
- verify termination, side effects, refresh behavior and privacy before recurring use.

The PO must review the exact code, target and projection and give the specified exact-command OK. A plain-device measurement does not validate a VS-context invocation.

**Candidate projection, only where measured and semantically established:**

| Metric | Treatment |
|---|---|
| Forwarding throughput, in/out or documented aggregate | Primary traffic observation; retain units and scope. |
| Forwarding packets/s | Corroborates traffic activity; not proof of successful transactions. |
| Drop/error rate | Diagnostic warning initially; no invented universal blocking threshold. |
| Concurrent connections | Optional occupancy information if available; not established-session identity or survival. |
| New connections/s | Optional only if explicitly defined as a rate. Never derive it from occupancy differences. |
| CPU/resource values | Optional operational context, not new failover blockers. |

If CPView exposes only host-wide totals, its output cannot prove traffic for a selected VS. Keep that VS-level claim UNKNOWN.

#### Sampling and thresholds

Use three bounded windows:

| Window | Timing | Comparison |
|---|---|---|
| Baseline | Finish immediately before the first mutation, after readiness | Former active’s scoped traffic rate |
| Post-switch | Start immediately after both endpoints confirm the switched roles | New active against baseline |
| Post-return | Start after both endpoints confirm the final roles | New active against baseline, with restored cluster checks |

For the existing `/proc/net/dev` measure, retain **two samples five seconds apart** per window, calculating elapsed time from a monotonic clock. The third window is a frequency extension requiring a matching gate amendment.

For cumulative counters:

```text
rate = (second_value - first_value) / measured_elapsed_seconds
```

Calculate each member’s rate locally, then compare rates. Never subtract a former-active counter from a new-active counter.

For already computed CPView rates, use their documented/measured interval; do not apply the cumulative-counter formula. Two snapshots five seconds apart are a proposed acquisition schedule, not proof that CPView’s internal averaging window is five seconds.

For the existing contracted traffic measure:

```text
PASS requires:
  valid, fresh, comparable samples
  baseline_rate > 0
  post_rate > 0
  post_rate / baseline_rate >= 0.50
```

The **50% threshold is an existing repository tolerance**, not a vendor guarantee or an application availability SLA. Applying it to a different CPView metric requires demonstrated comparability and a recorded amendment.

Counter reset, regression, missing sample, stale timestamp, uncertain scope or zero baseline produces UNKNOWN/NOT_EVALUABLE as appropriate. A valid post rate below the agreed threshold produces FAIL. Do not keep sampling until a favorable result appears.

Exclude management, synchronization and loopback traffic; avoid summing both bonds and their children or overlapping aggregate/per-interface counters.

**What stays UNKNOWN without additional evidence:**

- survival of established sessions;
- application transaction success and outage duration;
- loss-free forwarding;
- health of every path hidden inside an aggregate;
- per-VS traffic when only chassis counters are available;
- unsupported CPView fields or averaging semantics.

SNMP and Splunk remain parked.

#### Phase-specific health

“Full battery” means every applicable check is accounted for, with a defined predicate for its phase.

Before DOWN, require ordinary two-member readiness. Between DOWN and UP, require the intentional role pair and the contracted new-active health/continuity evidence. After UP, require restored two-member health.

Do not require the deliberately down member to appear ordinarily healthy, and do not excuse unrelated faults. Check Point documents that `clusterXL_admin down` registers an `admin_down` critical condition; that supports a narrowly identified expected condition, not suppression of every pnote or sync issue. [Official script description](https://sc1.checkpoint.com/documents/R80.30/WebAdminGuides/EN/CP_R80.30_ClusterXL_AdminGuide/208585.htm)

For PAN, preserve its own [frozen battery](FAILOVER_EXECUTION_PAN_CONTRACT.md): reciprocal HA relationships, links, configuration synchronization, measured session-sync counters, session-count continuity and version parity. Do not substitute CP traffic evidence or infer passive-state behavior while a peer is deliberately suspended.

### (c) Approval policy

| Authenticated actor policy | Required acts |
|---|---|
| `admin` | One authenticated administrator can approve and explicitly start the same operation. |
| Operation-admin | Initiator confirmation plus approval by a second authorized, distinct human identity. |
| `aiview` / `replay_viewer` | View masked status and permitted read-only actions; no approval, execution or scheduling. |

Use the application’s canonical authenticated principal mapping. Do not trust browser-supplied actor names, and do not normalize opaque identity-provider subject IDs as if they were display names. Different accounts require identity-governance controls if the product is to claim two different **humans**.

Bind authorization to:

```text
request ID and revision
unit reference and member-set revision
operation and policy version
approval window
authenticated actor identities
warning confirmation
execution nonce
```

The fresh evidence record is subsequently bound to that same request/run revision.

A target/member/policy change invalidates prior confirmation and approvals. Duplicate submissions cannot consume the authorization twice. Roles are checked from authenticated server-side authority; a request field cannot select the single-admin policy.

Record how existing `security_admin` permissions map to the new named policy. This is implementation of the settled decision, not a reason to ask the PO to choose again.

Incident release remains a distinct action. An approval to start does not automatically acknowledge a later incident or authorize another failover.

### (d) PR 8 identity decision and four-day sequencing

#### PR 8: sufficient binding

**Yes, conditionally:** use the verified transport as the endpoint identity and the command’s local marker as the link from that endpoint to its current cluster-member row.

Require all of the following:

1. Each session resolves to the intended enrolled endpoint with its trusted host-key binding.
2. Each response carries server-assigned provenance: run, endpoint, session, command, context and observation time.
3. Each response identifies exactly one local member row.
4. The two local bindings are distinct and belong to the same approved operational unit/context.
5. Each observer’s peer membership and role agree with the other endpoint’s independent local observation.
6. Per-member address relationships agree across observers; no management-IP/cluster-address equality is assumed.
7. Bindings remain unchanged through the run. Reconnect or membership/context change invalidates affected evidence.

Check Point documents that the displayed HA ID represents priority, and the displayed unique address is commonly a synchronization-interface address. Neither must equal an inventory UUID or management address. The vendor’s published script itself selects the `local` row from `cphaprob stat`. This supports the proposed transport/local-row binding; applicability to each deployed output shape still needs measured evidence. [Cluster-state fields](https://sc1.checkpoint.com/documents/R82/WebAdminGuides/EN/CP_R82_CLI_ReferenceGuide/Content/Topics-CLIG/CXLG/Viewing-Cluster-State.htm), [Published script](https://sc1.checkpoint.com/documents/R80.30/WebAdminGuides/EN/CP_R80.30_ClusterXL_AdminGuide/208585.htm)

**No, if the local association is absent or ambiguous.** Reciprocal reports cannot identify which row belongs to the session merely by elimination or naming convention.

PR 8 should therefore continue implementation and synthetic negative tests now. Live execution remains blocked for any unit whose binding is unproven. Do not invent another identity command merely because the output lacks a serial number.

#### Four-day integration plan

Parallelize bounded development and test preparation. Keep shared schema, state-machine and transaction integration under one owner. No agents are launched by this schedule.

| Day | Work | Exit evidence |
|---|---|---|
| **1** | PR1 alternate-route fences. Start PR5 durable incidents/runtime-selected admission. PR2 baseline/time and PR3 key lifecycle proceed on separately owned internals. Start PR8 identity/mode tests and the exact CPView measurement preparation immediately. Record successor policy/check semantics. | No synthetic or alternate mutation bypass. Shared request/attempt/incident shapes settled. Vendor evidence gaps identified early. |
| **2** | Finish PR2/3; integrate PR4 ledger/booking transactions. Finish PR5. Build PR6 durable attempts and reconciliation against the shared ownership design. Complete PR8 parser/provenance rules. | PostgreSQL round trips, key-loss cases, ledger concurrency, persistent quarantine and stale-owner tests. Identity/mode semantics resolved for eligible execution paths. |
| **3** | Integrate PR6, then PR7 authenticated policy and boundary rechecks. Integrate PR9 postconditions with PR8. PR10 UI/fixtures proceed against stable response fields. | Both writes reject expired/revoked authority; lost reply, restart, stale owner, peer disagreement and post-return failure stop safely. Gate changes cover exact phases and frequencies. |
| **4** | Finish PR9/10, cumulative regression, privacy, state consistency, gate parity and required release review. Deploy only through the authorized release path; perform masked acceptance and the attended runtime-selected failover. | All ten PRs and every P0 item have concrete evidence. No required UNKNOWN remains for the selected operation. |

Dependency spine:

```text
PR1 → PR5 → PR6 → PR7 → PR9 → PR10
                    PR8 ────┘

PR2 + PR3 → PR4 → final PR10 closure
```

PR4 and PR6 share transaction/ownership invariants; independent file edits do not make those designs independent.

Required plan corrections:

- **PR5:** remove constructor-seeded pilot selection and the proposed pilot-only enrollment restriction. Reuse ordinary enrolled device/unit identities and enforce runtime-selected request binding.
- **PR7:** implement the settled two-policy approval rule.
- **PR8:** resolve local binding, `BACKUP` handling and the readiness/polling mode mismatch.
- **PR9:** remove connection-table collection; add the approved policy projection and validated traffic/post-return semantics.
- **PR10:** acceptance covers estate-wide discovery and dynamic unit selection, with capability-based refusals. Generic manual/scheduled mutation paths may remain disabled as planned.

One fleet mutation at a time is a reasonable first-release admission limit and does not impose a hardcoded pilot. It must be durably enforced. Different VS runs sharing physical members must not create overlapping device command streams.

All ten PRs remain in scope. If evidence is incomplete on day four, report the specific unfinished gate and keep the affected mutation path disabled. Do not claim a completed real pilot from implementation or synthetic tests.

## 6. Concrete engineering instructions

1. **Record the 2026-10-06 successor decisions** against the plan and CP/PAN contracts: estate-wide runtime selection, approval policies, rejected CP connection read, approved `cpstat`, and conditional CPView measurement. Preserve existing command safety boundaries.

2. **Complete PR1 first:** fence every alternate mutation path, remove production synthetic-success defaults and preserve read-only readiness.

3. **Replace pilot-specific admission in PR5** with ordinary enrolled unit/member resolution, durable incident exclusion and immutable per-request targeting.

4. **Implement authenticated approval in PR7:** admin single; operation-admin two distinct authenticated humans. Reject forged second actors, self-approval through another session, changed targets and reused execution nonces.

5. **Unblock PR8 with transport/local-row binding.** Carry observation provenance through parsing; test swapped observers, duplicate endpoints, missing/duplicate local markers, opaque ID differences and changed VS context.

6. **Unify mode admission before and after mutation.** Remove unsupported `BACKUP → STANDBY` equivalence and resolve the current readiness-versus-polling mode mismatch. Unknown return/preemption behavior must block before DOWN.

7. **Implement PR6’s durable mutation boundary.** Persist intent before send, distinguish delivery certainty from observed success, fence stale owners and never replay uncertain writes.

8. **Complete PR2–4 fully:** recomputed baseline binding, canonical timestamps, explicit key initialization/loss handling, key/version persistence, framed ledger encoding and database-backed booking/chain serialization.

9. **Remove the rejected connection-table read from this workflow.** Retain an explicit “session continuity not evaluated” result instead of substituting policy or aggregate-traffic evidence.

10. **Integrate `cpstat` through the existing gate/parser infrastructure.** Extend its failover purpose/context/frequency and validate the exact policy projection before replacing existing policy checks.

11. **Prepare the bounded `cpview -p` measurement packet.** Execute only after the already-required exact-command OK. Do not infer fields, VS scope or safe batch behavior from community examples.

12. **Implement PR9’s three-phase verification.** Preserve informational checks, explicitly model intentional DOWN/suspended conditions, and add final post-return health/traffic checks with matching frequency gates. Resolve CP’s current per-run polling budget before using separate DOWN and UP polling windows.

13. **Test failure boundaries in PostgreSQL and the workers:** duplicate start, approval expiry/revocation, database failure before/after send, lost response, worker pause/restart, competing replica, stale incident release, asymmetric roles, preemption and final-check failure. A mocked happy path does not close these items.

14. **Close PR10 with evidence:** affected and cumulative Java/frontend checks, privacy, state consistency, migration/fixture parity, masked aiview acceptance, required stakeholder change sign-off and the attended selected-unit run. Record actual outcomes without raw identities. Independently review only through real reviewers/tooling if that remains a release requirement; this single-author exercise cannot be labeled independent review.

**SESSION CLOSE:** the requested analytical review is complete. Files, Git, deployment, devices and project state were unchanged; no tests or live measurements were run. Production readiness remains unverified. Next movement: bounded ARCHITECTURE / High to record the remaining identity, phase and measurement semantics, then IMPLEMENTATION / Normal (strong) against those decisions.
