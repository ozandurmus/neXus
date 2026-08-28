# Failover Engine — Architecture (design, not yet buildable)

**Status:** DESIGN / roadmap. Not implemented. No code. Gated — see §10.
**Movement when picked up:** `ARCHITECTURE` (high reasoning) → contract freeze → phased implementation.
**Owner concept:** a controlled-failover capability for admins and Service
Control Center (SCC) personnel — the tool's first `OPERATE` capability.

This document analyses the failover *procedure* per vendor, defines what to
check before touching anything, the safest way to actually fail over, and how it
is verified afterwards; then it lays out the engine and dashboard architecture
and where this sits on the roadmap.

---

## 1. Framing

SecurityExpert today is strictly read-only (`SEE → VERIFY → TRACE → RECOVER →
OPERATE`; the first four must be trustworthy before any write). A failover
engine is the payoff of that discipline and also its hardest test: it is a
**controlled, reversible, single-step change** to a production firewall cluster,
initiated by a human, with the tool doing the pre-assessment, the compilation of
the exact action, the execution, and the verification + rollback.

**Design principle:** the engine never improvises. Every action it can take is
compiled from evidence into an explicit plan that a human authorises. The
read-only assessment is valuable on its own and should ship first (§10).

**Explicitly not this:** a button that runs `clusterXL_admin down` or
`request high-availability state suspend` with no context. Those are *one line*
of a procedure that has ~15 preconditions and ~10 post-conditions.

---

## 2. The real procedure (why it is not one command)

A safe operator failover is five phases:

```
1. PREFLIGHT   read-only. Is it safe? Is there a standby that can actually
               take the traffic? Is state sync current? Any split-brain,
               version drift, resource exhaustion, link-down, flap history?
2. PLAN        compile the exact vendor action for THIS cluster + intent,
               the expected traffic-impact window, whether preemption will
               cause a fallback, and the rollback command.
3. AUTHORISE   human reviews the assessment + plan, types a confirmation,
               (optional) second approver, maintenance-window check.
4. EXECUTE     snapshot -> one action -> poll for the expected transition
               with a timeout -> snapshot -> compare. Auto-rollback on a
               failed transition.
5. VERIFY      read-only. New active is the intended member; sessions/
               connections preserved within tolerance; no split-brain; sync
               resumed; dataplane forwarding; management reachable; alarms
               clear. Emit an immutable audit record.
```

The dangerous failure modes this structure exists to prevent: failing over to a
member that cannot carry traffic (link down, critical device/pnote down,
resource-starved), failing over with incomplete state sync (mass connection
drop), causing or worsening split-brain, and getting stuck (HA gone
"suspended"/"non-functional" and not coming back without manual intervention).

---

## 3. Per-vendor analysis

### 3.1 Check Point ClusterXL

**Modes matter.** *High Availability (New mode)* has one active + standbys —
failover = the standby with the next-highest priority takes the VIPs.
*Load Sharing (Unicast/Multicast)* has **no single standby** — every member
carries traffic; "failing one down" redistributes its share to the others, which
can overload them. VRRP clusters and Gaia clustering behave differently again.
The engine must detect the mode from `cphaprob` evidence and refuse LS
"failover" framing (it is a member-evacuation, not a failover).

**Preflight reads (all read-only, over the validated Expert→clish path):**

| Check | Command / evidence | Blocking if |
| --- | --- | --- |
| Cluster mode + roles | `cphaprob stat` | mode unknown; >1 member ACTIVE (split-brain); only one member up |
| Critical devices / pnotes | `cphaprob -l list`, `cphaprob -ia list` | any critical pnote DOWN on the member that would become active |
| Interface / CCP health | `cphaprob -a if`, `cphaprob show_bond_groups` | a monitored interface DOWN on the standby; CCP problem on the sync network |
| State sync | `cphaprob syncstat`, `fw ctl pstat` | sync not "complete"; large sync delta / drops; sync stuck |
| Policy parity | `fw stat`, `fw ver` per member | different policy name/version installed across members |
| Software / JHF parity | `cpinfo -y all` / `installed_jumbo_take` | major version mismatch |
| Resources on standby | `free -m`, `df -h`, `top -bn1`, `fw ctl pstat` conn table | memory/disk near limit; connection table near capacity |
| Preemption config | `cphaprob stat` / `$FWDIR/conf/cpha_bond_ls_config.conf` / cluster object | (not blocking) — record whether "maintain current active" or "switch to higher priority": determines if rollback triggers a *second* failover |
| Flap history | `cphaprob stat` uptime, `/var/log/messages`, `fw log` cluster events | repeated recent failovers (instability) |
| Licensing / contracts on standby | `cplic print`, `cpstat os` | expired license on the standby |

**Failover action (planned maintenance), least→most disruptive:**

1. `clusterXL_admin down` on the **active** member — it lowers its own priority
   and reports itself down; the standby takes the VIPs. `-p` persists across
   reboot (use for real maintenance). *This is the recommended primitive.*
2. Do **not** use `cpstop` / `cphastop` / reboot as a failover primitive — they
   are heavier, slower to recover, and skip the graceful hand-off.
3. Never manipulate the *target* (standby) to "pull" it active.

**Rollback:** `clusterXL_admin up` on the member that was brought down. With
"maintain current active member" it returns to **standby** (no second flap);
with "switch to higher priority" it will **take over again** — the plan must say
which, and the operator must accept a second brief impact if so.

**VSX:** VSX HA can fail over the whole VSX gateway or, with VSLS (Virtual
System Load Sharing), per Virtual System with independent priorities. The engine
treats a VSX cluster as *N* logical failover units (one per VS) plus the
physical unit, and uses the validated `vsenv <VSID>` context for per-VS reads.
Per-VS failover is `vsx_util` / VS priority manipulation — a separate adapter.

### 3.2 Palo Alto HA

**Modes matter.** *Active/Passive*: one peer forwards, the other is fully
synced and idle (its dataplane interfaces are in `passive` link state).
Failover = passive → active. *Active/Active*: both forward; there is no
"failover" — there are session-owner / session-setup roles and `functional` /
`tentative` / `non-functional` states, and floating-IP ownership. The engine
must detect A/A and present "move floating-IP ownership / set a peer tentative"
semantics, not "fail over".

**Preflight reads (read-only, over the identity-verified direct API):**

| Check | Evidence (op command) | Blocking if |
| --- | --- | --- |
| HA enabled + peer states | `show high-availability all` | peer not "passive"/"active-secondary"; local not "active"; either in "suspended"/"non-functional" |
| Control link (HA1 + HA1-backup) | `show high-availability state` | HA1 down and no backup |
| State sync (HA2 + HA2-backup) | `show high-availability state-synchronization` | session sync not "Synchronization Enabled" / not current; HA2 down (sessions will drop on failover) |
| Config sync | `show high-availability state` (config-sync) | config out-of-sync; uncommitted changes on the peer that will become active |
| Path / link / hello monitoring | `show high-availability path-monitoring`, `... link-monitoring` | a monitored path/link failing on the peer that would become active |
| Content / AV / threat parity | `show system info` per peer | dynamic-content versions materially different |
| Software parity | `show system info` (sw-version) | versions not HA-compatible |
| Dataplane readiness on passive | `show session info`, `show interface all`, `show routing route` | passive dataplane not ready; routes missing; FIB not converged |
| Preemption + priority | `show high-availability state` (preemptive), device priority | (not blocking) — record: with preemption ON, rollback / un-suspend causes an automatic fail-back (second impact) |
| Flap counters | `show high-availability state` (flap count, promotion hold) | near max-flaps (peer will latch "suspended" and need manual `functional`) |
| Passive link state setting | `show high-availability state` | `shutdown` (not `auto`) means the new active's links come up slower — widen the impact window |

**Failover action (planned):**

1. `request high-availability state suspend` on the **active** peer → the
   passive becomes active. *Recommended primitive.*
2. Un-suspend / rollback: `request high-availability state functional` on the
   suspended peer. With preemption ON it fails back automatically; with
   preemption OFF it returns to passive.
3. Do not fail over by changing device priority, rebooting, or pulling links —
   all are more disruptive and some latch bad states.
4. Panorama-managed HA: a config push mid-failover is forbidden by the plan.

**Session continuity:** depends entirely on HA2 being healthy and current. If
HA2 is down, the plan must state "sessions will drop and re-establish" and
require explicit acceptance.

### 3.3 Cross-vendor invariants

- Always act on the **currently-active** device, never the target.
- One action, then re-assess. No scripted multi-cluster sweeps in v1.
- `UNSAFE` preflight = hard stop (not operator-overridable in v1).
- Every action: snapshot-before → act → poll-for-expected-transition(timeout) →
  snapshot-after → compare → auto-rollback on a failed/partial transition.
- Emergency "evacuate a failing member" is a **separate, still-gated** path with
  a reduced-but-not-skipped preflight (it still must confirm a target exists
  that can carry traffic).

---

## 4. What to check first (ordered stop-conditions)

The preflight runs cheap/decisive checks first; the first failure sets the
verdict and the rest still run for the report:

1. **Is there a viable target?** A standby/passive peer that is up, whose
   would-be-active interfaces have link, with no critical device/pnote down and
   without resource exhaustion. *No viable target ⇒ `UNSAFE`.*
2. **Is state/session sync complete and current?** *No ⇒ `UNSAFE` for
   "preserve connections" intent; `DEGRADED` with explicit drop acceptance.*
3. **Version / policy / content parity** between the peers. *Material drift ⇒
   `UNSAFE`.*
4. **No existing split-brain or election instability**; flap counters not near
   their latch limit. *Split-brain now ⇒ `UNSAFE`.*
5. **Control + sync link health** (CP CCP / PAN HA1+HA2 and their backups).
6. **Preemption configuration** — not blocking, but determines whether the
   rollback causes a *second* impact; the plan surfaces it.
7. **Recent history** — repeated failovers, monitored-path flaps, relevant logs.

Verdict enum: `SAFE_TO_FAILOVER` · `DEGRADED_PROCEED_WITH_RISK` (per-risk
acceptance required) · `UNSAFE_DO_NOT_FAILOVER` · `INSUFFICIENT_EVIDENCE`.

---

## 5. Best failover approach

**Graceful, active-initiated, single-step, re-assessed, auto-rollback.**

- CP: `clusterXL_admin down [-p]` on the active. PAN: `request high-availability
  state suspend` on the active.
- Prefer a declared maintenance window; block if none and intent is "planned".
- Snapshot HA state + connection/session counts + a routing sample before and
  after; a post-action session/connection delta beyond a configurable tolerance
  (default: sessions preserved ≥ ~90%, no split-brain) triggers auto-rollback.
- Never touch the target device; never use stop/reboot/priority/link as the
  failover primitive; never chain actions without a fresh assessment.

---

## 6. Post-failover verification battery

Read-only, the VERIFY plane on the new topology:

| Check | Pass condition |
| --- | --- |
| Intended active | the member/peer named in the plan is now active |
| No split-brain | exactly one active (A/P); A/A roles consistent |
| Session/connection continuity | post/pre ratio within tolerance; no spike of brand-new sessions |
| State sync resumed | CP `syncstat` healthy again / PAN HA2 "synchronization enabled" |
| Dataplane forwarding | new active interfaces up; routes/FIB present; a `test routing fib-lookup` / connectivity probe passes |
| Management reachable | the tool can still reach both devices |
| GARP / MAC learned | (best-effort) upstream reachability of the VIP restored |
| Alarms / critical pnotes | none critical on the new active |

`FailoverOutcome`: `SUCCESS` · `SUCCESS_WITH_WARNINGS` · `FAILED_ROLLED_BACK` ·
`FAILED_MANUAL_INTERVENTION_REQUIRED` (paged).

---

## 7. Engine architecture

```
utils/failover/
  assessment.py     PreflightAssessment  — read-only; vendor batteries; verdict + line items
  plan.py           FailoverPlan         — compile action + rollback + impact window + preemption note
  executor.py       FailoverExecutor     — THE ONLY write path; flag+token+window+lock gated; one action
  verification.py   PostFailoverVerification — read-only; FailoverOutcome
  audit.py          FailoverAuditRecord  — immutable who/when/intent/verdict/plan/steps/outcome
  adapters/
    cp_clusterxl.py    reads + the single action + rollback for CP HA/LS
    cp_vsx.py          per-VS failover units
    pan_ha.py          A/P and A/A
```

- **Reuses** the existing collectors' transport, identity gates, secret
  redaction, `RunContext`, and the admission coordinator (extended to a
  **per-cluster lock**: no concurrent failover + collection on one cluster).
- **`FailoverExecutor` is the only component that can write**, and only when:
  a process-level feature flag is on, a per-run authorization token is present,
  the OIDC/RBAC `OPERATE` role is held, a maintenance window is active (for
  "planned" intent), and — configurable — a second approver has signed off.
- **Dry-run** mode compiles and shows the plan and executes nothing; it is the
  default and the only mode until the gate in §10 is cleared.
- All four phases append to the immutable audit record; the shareable summary is
  sanitized (no endpoints, no raw commands with identities, no secrets).

---

## 8. Safety model (hard rules)

1. Default **off**. No failover capability without the feature flag *and* the
   `OPERATE` role *and* a per-action token.
2. `UNSAFE_DO_NOT_FAILOVER` cannot be overridden in v1.
3. Exactly **one** action per authorised run; mandatory re-assessment before any
   next action; **no** multi-cluster scripting.
4. Only the currently-active device is acted on. Only the graceful primitive.
5. Snapshot → act → poll(timeout) → verify → auto-rollback on failure; a failed
   rollback pages and stops.
6. Per-cluster lock via the coordinator; a failover and a collection never run
   on the same cluster at once.
7. Maintenance-window gate for "planned"; emergency path is separate and still
   runs the viable-target check.
8. Every run fully audited; value-free shareable summary.
9. Panorama/MDS config push is forbidden by the plan during a failover.

---

## 9. UI — SCC Failover Dashboard

A seventh app module, **Failover**, cluster-centric:

- **Fleet view:** every HA cluster / pair — vendor, mode (HA / LS / A-P / A-A),
  live role map (which member/peer is active), a **readiness light**
  (green / amber / red from the last `PreflightAssessment`), and the top
  blocking reasons when amber/red. Refresh-on-demand.
- **Prepare failover** (per cluster): run preflight → show the assessment
  line-items and the compiled `FailoverPlan` (exact action, impact window,
  rollback, preemption note) → operator reviews.
- **Authorise:** typed confirmation of the cluster name + intent; optional
  second-approver field; maintenance-window status shown; "controlled write
  operation" banner; Cancel is safe at any pre-execution point.
- **Execute:** a live step timeline (snapshot / action / poll / verify), then
  the `FailoverOutcome` and a link to the audit record.
- **History:** every past failover with its verdict, plan, steps and outcome.
- **RBAC:** without the `OPERATE` role, the module is the readiness dashboard +
  history only — no Prepare/Authorise/Execute.

---

## 10. Roadmap placement and gate

New track: **`OP.x — Controlled Failover`** (the first `OPERATE` work).

**Split for value and de-risking:**

- **`OP.0` — HA Readiness / Failover-Safety Assessment (read-only).** The §4
  preflight battery + the §9 readiness dashboard and history. *No write
  capability.* A **VERIFY-plane** feature — "is this cluster safe to fail over,
  and why not". Needs only a **reachable test CP + PAN cluster** (a laptop run
  is sufficient) and the network-device command gate for the new *read*
  commands — not the full server. Foundation and safety net for everything
  after.
- **`OP.1` — Failover Plan Compiler + Dry-Run.** §3 action/rollback compilation,
  `FailoverPlan`, dry-run only. Still no write; same prerequisites as `OP.0`.
- **`OP.2` — Controlled Failover Execution (gated).** `FailoverExecutor`,
  `PostFailoverVerification`, audit, per-vendor action adapters, the Authorise/
  Execute UI. **This is the gated part.**

**`OP.2` hard prerequisites (the roadmap write-gate the project keeps citing):**

- `SEE` mature (done); `VERIFY` mature (CP + PAN alignment + compliance, 0.6.x /
  0.7.x); `TRACE` mature (cross-vendor change timeline + safe diff, 0.8.x);
  `RECOVER` mature (vendor-native backup + restore-readiness, 0.9.x).
- `DEPLOY.1A` OIDC boundary + an RBAC `OPERATE` role + full audit. (A
  write-capable failover tool must not run without an auth boundary — this,
  not the server hardware itself, is the gate.)
- CP device-interaction-safety audit closed.
- The `network-device command gate` completed for the two write primitives
  (`clusterXL_admin down` / `request high-availability state suspend`) and
  their rollbacks, per `docs/AI_DEVELOPMENT_PROTOCOL.md`.
- A written change-management / safety review signed off with the network-
  security leads. This is not a code gate; it is an organisational one.

Until every `OP.2` prerequisite is met, only `OP.0` and `OP.1` (both
write-free) may be built.

Open decisions for the OP.x approval review are tracked in
`project/roadmap.json` → `open_decisions`; §11 restates them.

---

## 11. Open decisions

1. Track id: `OP.x` vs folding into `1.x` after the `1.0` GOVERN track. (Design
   assumes `OP.x`.)
2. Second-approver (four-eyes): mandatory or configurable for `OP.2`?
3. `DEGRADED_PROCEED_WITH_RISK` — allowed at all in v1, or collapse to
   `UNSAFE` + `SAFE` only until there is field experience?
4. Emergency evacuation path — in scope for `OP.2` or a later `OP.3`?
5. A/A PAN and VSLS VSX — first-class in `OP.2` or deferred (design as if
   deferred; `OP.0` still assesses them).
6. Connection/session continuity tolerance defaults and whether they are
   operator-tunable per run.
