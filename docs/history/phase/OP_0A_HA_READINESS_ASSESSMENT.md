# OP.0a — HA readiness assessment on existing evidence (zero new device commands)

## Status

**CONTRACT FROZEN 2026-09-01 — cleared for implementation.**

**IMPLEMENTED / AUTOMATED_VALIDATED 2026-09-01** (`Sonnet 5, normal`).
`utils/failover/{__init__,assessment}.py`,
`application/workflows/failover.py`, `main.py --ha-readiness-check`,
`configuration/checkpoint_config_collector.py`'s additive P2 mode parse
(`_parse_clusterxl_cluster_mode`, both the per-endpoint and per-VS
`cphaprob stat` call sites), the P6 allowlist comment and the
`ha-readiness-check` prerequisite entry. AC-1…AC-13 covered by
`tests/test_op0a_ha_readiness.py` (38 tests). Two implementation deviations
(D1, D2) recorded below; D2 was a real defect found by the smoke run.
**No new device command was issued or added** — the defining property of this
slice held through implementation. The `OP.0b` gate draft below remains
**un-approved**, and `OP.0c` (the §9 UI module) is untouched.

Movement that produced it: `ARCHITECTURE` (`Sonnet 5, normal` — see "Next
movement / model" for why extended thinking was not required). No source was
touched in the freezing session.

- Design parent: `docs/design/FAILOVER_ENGINE_ARCHITECTURE.md` (`OP.x` track,
  §4 stop-conditions, §9 dashboard, §10 placement). That document is the spec
  for the *engine*; this contract is the spec for its **first write-free
  slice** and does not restate it.
- Gate: **no network-device command gate is required for this build.** That is
  the defining property of the slice — see design decision P1 and the
  "Command surface" section. The gate draft that `OP.0b` needs is written in
  this document's "OP.0b command gate — DRAFT FOR REVIEW, NOT AN APPROVAL"
  section, so the product-owner/security review has something concrete to act
  on, but nothing in *this* contract depends on that review landing.
- `OP.2` (execution) remains hard-gated by the design doc §10 prerequisite
  list. Nothing here moves that gate, and this build adds **no** write path.

## Why this slice exists at all (the audit finding that shaped it)

`FAILOVER_ENGINE_ARCHITECTURE.md` §10 describes `OP.0` as "the §4 preflight
battery + the §9 readiness dashboard", needing "the network-device command
gate for the new *read* commands". Taken literally, `OP.0` is one build that
introduces roughly **nineteen** new device commands (§3.1's ten CP rows,
§3.2's nine PAN rows), a new assessment engine, a new payload builder and a
new seventh UI module. That is several times the `docs/AI_DEVELOPMENT_PROTOCOL.md`
"Build size" default, and every part of it would sit behind one human approval
that has not been asked for yet.

A source audit of what the platform *already* collects changes the picture:

| Evidence | Where it already comes from | Status |
| --- | --- | --- |
| CP local member ClusterXL role (`ACTIVE`/`STANDBY`/…) | `configuration/checkpoint_config_collector.py` runs **`cphaprob stat`** in the authenticated session for `clusterxl_member` / `vsx_host` targets; `_parse_clusterxl_runtime_role` extracts the local role into `ha_role` / `ha_role_source` | **already gated, already implemented** |
| CP per-VS ClusterXL role | same command under the validated `vsenv <VSID>` context (`ha_runtime_status_per_vs`) | **already gated, already implemented** |
| CP direct-Clish capability boundary | `ha_runtime_error_class = "cphaprob_unavailable_in_direct_clish"` | already implemented |
| CP cluster membership / virtual interfaces | `cluster`, `cluster_topology.members`, `cluster_topology.virtual_interfaces` in the unified inventory | already implemented |
| PAN HA enablement, local state, mode, **peer state**, **state-sync** | `configuration/panorama_config_collector.get_target_ha_runtime_state` runs **`show high-availability state`** and parses `enabled` / `state` / `mode` / `peer_state` / `state_sync` | **already gated, already implemented** |

So a meaningful part of §4's stop-condition set is answerable **today**, from
commands that were gated years of builds ago, with **no new device
interaction whatsoever**. That is what this build ships. The remaining checks
are not silently dropped — they are reported as `INSUFFICIENT_EVIDENCE`
against a named missing command, which is precisely the artifact the `OP.0b`
gate review needs in order to approve a command list on evidence rather than
on a design document's speculation.

## Objective

Produce, for every HA unit in the fleet, an honest, fail-closed **readiness
assessment** derived entirely from evidence the platform already holds — and
make the un-answerable parts of §4 explicit rather than invisible.

This build answers "what do we actually know about this cluster's failover
readiness, and what would we still have to ask a device?" It does **not**
answer "is it safe to fail over" — by construction it cannot, and design
decision **P4** makes that impossible to misread.

## Scope

### In scope

1. **`utils/failover/__init__.py`** and **`utils/failover/assessment.py`** —
   the new package from design §7, containing `assessment.py` **only**.
   `plan.py`, `executor.py`, `verification.py`, `audit.py` and `adapters/`
   are deliberately absent (P5).
2. **`HaUnit` derivation** — grouping fleet entities into failover units
   (CP ClusterXL cluster, CP VSX host, PAN HA pair) from the unified
   inventory and existing configuration evidence, with `UNPAIRED` /
   `UNKNOWN_MODE` as first-class honest outcomes.
3. **`PreflightAssessment`** — the §4 check list evaluated over available
   evidence, each check carrying its own status, and a unit-level verdict
   constrained by P4.
4. **An additive parse of the already-executed `cphaprob stat` output** to
   capture **cluster mode** (High Availability New mode vs Load Sharing
   Unicast/Multicast) alongside the role that is parsed today (P2). Same
   command, same session, same timeout, same frequency — no gate change.
5. **`data/state/ha_readiness.json`** — a new evidence-plane state file
   (`securityexpert-ha-readiness-v1`), written whole on each run.
6. **`main.py --ha-readiness-check`** — a thin offline maintenance-class
   dispatch (no device contact, no credential), mirroring
   `--restore-readiness-check`.
7. Project metadata updates per `AGENTS.md` "Project-state update rule".

### Explicitly out of scope

- **Every new device command.** The entire §3.1/§3.2 preflight battery
  (`cphaprob -l list`, `cphaprob syncstat`, `fw ctl pstat`, `cplic print`,
  `show high-availability all`, `show high-availability
  state-synchronization`, `show session info`, …) is `OP.0b`, behind the
  drafted gate below.
- **The §9 Failover UI module.** Deferred to `OP.0c` — same precedent as
  `RB.3a` deferring its UI to `RB.5`. This build therefore touches no
  `templates/`, no `static/`, and no payload builder, so it does not trigger
  the render harness (the full suite still runs it).
- **`FailoverPlan` / action compilation** — that is `OP.1`.
- **Any write path, token, feature flag or `OPERATE` role** — `OP.2`, hard-gated.
- **Scheduling.** No `ALLOWLISTED_WORKFLOWS` entry (P6).
- Changing `ha_role`, `ha_runtime_status`, or any existing collector
  behaviour beyond the additive mode parse in P2.

## Design decisions

### P1 — `OP.0` splits; the zero-new-command slice ships first

`OP.0` as written in the design doc bundles a nineteen-command gate ask, an
engine and a UI module. This contract splits it:

- **`OP.0a`** (this build) — assessment engine over existing evidence. No new
  command, therefore **no approval blocks it**.
- **`OP.0b`** — the §3.1/§3.2 preflight command battery, behind the gate
  drafted below.
- **`OP.0c`** — the §9 readiness dashboard UI module.

This is the same sequencing the repository already used twice: `RB.3a` split
the `read`-class commands out of `RB.3b`'s `operational-write` ask so the
cheaper gate could be granted independently, and `CON.1` shipped a read-only
surface before `CON.2` added actions. The split is not merely administrative
— `OP.0a`'s `INSUFFICIENT_EVIDENCE` output is the input the `OP.0b` review
needs.

### P2 — Cluster mode is decisive, and it is an additive parse, not a new command

Design §3.1 is emphatic: *"The engine must detect the mode from `cphaprob`
evidence and refuse LS 'failover' framing (it is a member-evacuation, not a
failover)."* Mode is therefore not optional colour — a readiness assessment
that cannot distinguish High Availability New mode from Load Sharing is
unsafe at the level of vocabulary, before any command runs.

`checkpoint_config_collector.py` already executes `cphaprob stat` and already
has its stdout in hand, but `_parse_clusterxl_runtime_role` extracts only the
local member's role, and the very next lines discard the text
(`ha_result["stdout"] = ha_result["stderr"] = ""`). Capturing the mode from
that same buffer is a **parse-scope extension, not a command addition**:
identical command string, identical session, identical timeout, identical
per-endpoint frequency, identical output-handling lifetime.

Per `docs/AI_DEVELOPMENT_PROTOCOL.md` the gate governs "adding/changing a
device command". Nothing about the device interaction changes here, so no
gate entry is required — but the extension is recorded explicitly in this
contract rather than absorbed silently, because widening what is parsed out
of device output is exactly the kind of change that should be visible in a
diff and in a review.

New fields, alongside today's `ha_role`: `ha_cluster_mode` (enum below) and
`ha_cluster_mode_source`. Fail-closed: unrecognised output yields
`ha_cluster_mode = "unknown"`, never a guess.

```
ha_cluster_mode ∈ { "ha_new_mode", "load_sharing_unicast",
                    "load_sharing_multicast", "vrrp", "unknown" }
```

The raw text is discarded exactly as it is today. No member hostname, no
interface name and no IP enters `ha_cluster_mode`.

### P3 — A Load Sharing cluster is not a failover unit, and is never described as one

When `ha_cluster_mode` is a load-sharing variant, the unit's verdict is
`NOT_A_FAILOVER_UNIT` with reason `load_sharing_member_evacuation_not_failover`.
It is not `SAFE`, not `DEGRADED`, and not `UNSAFE` — all three would imply
that "fail this cluster over" is a coherent request. Design §3.1 says it is
not; bringing an LS member down redistributes its share onto peers that may
not absorb it, which is a capacity question, not a failover question.

`OP.1`'s plan compiler must refuse to compile an action for such a unit. This
contract freezes the vocabulary that makes that refusal expressible.

### P4 — `OP.0a` can never emit `SAFE_TO_FAILOVER`. This is a hard invariant, not a limitation.

This is the most important decision in the contract.

Design §4 defines seven ordered stop-conditions. `OP.0a` has evidence for at
most conditions 1 (partially — a peer exists and reports a role), 4
(partially — more than one member reporting `ACTIVE` is visible split-brain)
and the mode determination. It has **no** evidence for state/session sync
currency, policy/version/content parity, control-link and sync-link health,
preemption configuration, or flap history.

A verdict of `SAFE_TO_FAILOVER` computed from two-and-a-bit of seven
conditions would be actively dangerous — it is precisely the "failing over
with incomplete state sync (mass connection drop)" failure mode §2 exists to
prevent, dressed as a green light. `AGENTS.md`: *"Evidence over assumptions;
explicit `UNKNOWN` over invented certainty."*

Therefore the assessment is **fail-closed by construction**:

- Every §4 condition the build cannot evaluate is emitted as a check with
  status `INSUFFICIENT_EVIDENCE` and a `missing_evidence` string naming the
  command that would answer it (e.g. `"cphaprob syncstat (OP.0b)"`).
- A unit-level verdict of `SAFE_TO_FAILOVER` requires **every** §4 condition
  to have been positively evaluated. Since `OP.0a` structurally cannot
  satisfy that, `SAFE_TO_FAILOVER` is unreachable in this build — and the
  test suite asserts it (AC-6), so a later build cannot make it reachable by
  accident.
- `UNSAFE_DO_NOT_FAILOVER` **is** reachable, and correctly so: observed
  split-brain, an absent viable target, or a load-sharing/unknown mode are
  decisive on the evidence available. A build that can only ever say "no" or
  "I don't know enough" is the honest shape for this slice.

Verdict enum, frozen (design §4 plus P3's addition):

```
SAFE_TO_FAILOVER            # unreachable in OP.0a, by AC-6
DEGRADED_PROCEED_WITH_RISK  # unreachable in OP.0a (see design open decision 3)
UNSAFE_DO_NOT_FAILOVER
INSUFFICIENT_EVIDENCE
NOT_A_FAILOVER_UNIT         # P3
```

Design §11 open decision 3 asks whether `DEGRADED_PROCEED_WITH_RISK` should
exist in v1 at all. **This build does not resolve it and does not need it
resolved:** the value is reserved in the enum but unreachable here. The
decision is owed before `OP.1` compiles a plan against it.

### P5 — `utils/failover/` is created with one module, and the emptiness is the point

Design §7 lists six modules and an adapters package. Creating the package
skeleton with empty or stubbed `executor.py` / `plan.py` files would put a
named write path into the repository ahead of its gate. `utils/cleanup.py` —
deleted by `remove_dormant_remote_cleanup` precisely because dormant
write-capable code is a standing liability even when unreferenced — is the
precedent this avoids repeating.

`utils/failover/` therefore contains `__init__.py` and `assessment.py` and
nothing else. A test asserts the package exposes no executor/plan symbol
(AC-9), so the absence is enforced rather than merely current.

### P6 — No scheduler allowlist entry

`"ha-readiness"` does not join `utils.collection_executor.ALLOWLISTED_WORKFLOWS`.

This build performs no device I/O, so the `RB.3a` design-decision-A9 concern
(scheduling fleet-wide SSH) does not literally apply — but the allowlist
comment block is the repository's record of *why* each name is or is not
present, and an offline derivation has no reason to be a scheduled workflow
at all: it recomputes from state that only changes when a collection runs.
The allowlist gains a comment recording this, in the established style.

### P7 — PAN HA pairs cannot be assembled from the unified inventory today

A concrete gap found while grounding this contract, recorded rather than
assumed away. In `tests/fixtures/uitest/unified.json`, CP cluster members
carry `cluster` and `cluster_topology.members`, so a CP ClusterXL unit
assembles cleanly. **PAN entities carry neither** — `pan-ha-01` and
`pan-ha-02` both have `cluster: None` and no peer reference, even though the
fixture models them as an HA pair.

The peer relationship does exist in evidence the platform already holds:
`get_target_ha_runtime_state` returns `peer_state`, and PAN configuration
evidence carries `high-availability/group/peer-ip` (referenced by
`configuration/pan_semantic_policy.py`). So a PAN HA unit is assembled by
matching a device's configured `peer-ip` against another PAN entity's
management address.

Fail-closed rules:

- A PAN device whose `peer-ip` resolves to exactly one other in-scope PAN
  entity forms a two-member unit.
- A `peer-ip` that resolves to zero entities, or to more than one, yields a
  **single-member unit** with verdict `INSUFFICIENT_EVIDENCE` and reason
  `pan_ha_peer_unresolved`. It is never guessed, and never silently merged.
- A PAN device with HA disabled or no `peer-ip` is not an HA unit and is
  omitted, not reported as broken.

Making PAN peer identity a first-class inventory field is a real improvement
but belongs to the inventory/discovery plane, not to this build — it is
recorded in "Risks / follow-ups" rather than smuggled in here.

### P8 — Assessment is derived, offline, and recomputable; it stores no identity it did not already publish

`--ha-readiness-check` reads the existing unified inventory and configuration
evidence and writes `ha_readiness.json`. It opens no session and needs no
credential — the same offline maintenance-class posture as
`--restore-readiness-check` and `--compliance-trend-reconstruct`.

`ha_readiness.json` carries `entity_id`, unit id, vendor, mode, per-check
status and reason codes. It carries **no** management address, no raw device
output and no command string beyond the fixed `missing_evidence` labels. A
corrupt or unreadable file degrades the next run to "no prior readiness",
never to an error — the same fail-safe posture as `utils/compliance_history.py`.

## Command surface

**This build issues no device command, new or existing.** It is offline
derivation over stored evidence.

The one device-adjacent change is P2's additive parse of output already
retrieved by the already-gated `cphaprob stat`. For the avoidance of doubt,
against the ten gate points: the command string, vendor, platform, shell,
context, timeout, retry policy, maximum per-endpoint frequency, session reuse
and output-discard lifetime are **all unchanged**. Only the set of fields
parsed out of the buffer before it is discarded grows, and it grows to
structured enums that carry no identity.

## OP.0b command gate — DRAFT FOR REVIEW, NOT AN APPROVAL

Written here so the `OP.0b` review has a concrete artifact. **Nothing in this
contract depends on it.** Same standing as the `RB.3b` gate drafts: a draft
for review, not an approval. Commands are grouped where all ten points are
genuinely identical; per-command deltas are called out.

### Group CP-HA — Check Point cluster preflight reads

1. **Why required:** design §4 stop-conditions 1–7 for ClusterXL. Without
   them `OP.0a` reports `INSUFFICIENT_EVIDENCE` for viable-target capacity,
   sync currency, parity, link health, preemption and flap history.
2. **Read-only vs write:** `read`. None alters device state, installs policy,
   or changes cluster membership or priority.
3. **Vendor / platform / shell / context:** Check Point Gaia;
   Expert-level (`cphaprob`, `fw`, `cplic`, `cpstat` are Expert/bash
   commands, not Clish — the existing
   `cphaprob_unavailable_in_direct_clish` capability boundary applies
   unchanged); per-VS reads under the validated `vsenv <VSID>` context.
   Spark / Gaia Embedded is `UNSUPPORTED` and receives **no** command,
   determined from the discovery-lifecycle platform classification and never
   from direct-Clish shell behaviour (`AGENTS.md`).
4. **Timeout:** 60 s per command.
5. **Retry:** 1.
6. **Maximum frequency per endpoint:** 1 assessment per endpoint per hour.
7. **Existing-session reuse:** mandatory — one authenticated session per
   physical endpoint runs the whole battery, as `RB.3a` established.
8. **Unsupported behaviour:** fail-closed per command. An unparseable or
   errored response yields `INSUFFICIENT_EVIDENCE` for that check only; it
   never fails the unit and never yields a permissive verdict.
9. **Secret-bearing output risk:** `cplic print` returns licence
   strings and `cpstat os` returns host identity — both must be parsed to
   scalars and discarded in-module, names never reaching state files
   (the `RB.3a` decision-A5 pattern). The others return operational status,
   but member hostnames and interface names appear throughout and are
   operational identities.
10. **Safe telemetry:** structured enums and booleans only; reason codes, not
    device text.

| Command | Answers | Delta |
| --- | --- | --- |
| `cphaprob -l list` | critical device / pnote state | — |
| `cphaprob -ia list` | interface-aware pnote detail | — |
| `cphaprob -a if` | monitored interface / CCP health | — |
| `cphaprob syncstat` | state-sync currency, delta, drops | decisive for stop-condition 2 |
| `fw ctl pstat` | connection table headroom, sync buffers | — |
| `fw stat` | installed policy name/version parity | — |
| `cplic print` | licence validity on the standby | point 9 applies |
| `cpstat os` | resource / uptime | point 9 applies |

Deliberately **excluded** from the draft: `free -m`, `df -h`, `top -bn1`
(design §3.1 lists them, but `cpstat os` and `fw ctl pstat` cover the same
questions through the supported CP interface rather than raw shell), and
`/var/log/messages` / `fw log` scraping for flap history (unbounded output,
high secret-bearing risk, and a log-retrieval design question of its own).

### Group PAN-HA — Palo Alto HA preflight reads

Points 1–2 as above for PAN A/P and A/A. **3.** PAN-OS op commands over the
existing identity-verified direct API / Panorama `target=<serial>` path — no
new transport, no new credential. **4.** 30 s. **5.** 1. **6.** 1 per
endpoint per hour. **7.** the existing API key/session is reused; no new
session type. **8.** fail-closed per command as above. **9.** `show system
info` returns serials and hostnames; `show interface all` and `show routing
route` return topology — all parsed to scalars/counters in-module.
**10.** enums and counters only.

| Command | Answers | Delta |
| --- | --- | --- |
| `show high-availability all` | full HA view incl. links, monitoring, flap counters | supersedes several rows below where supported |
| `show high-availability state-synchronization` | HA2 session-sync currency | decisive for stop-condition 2 |
| `show high-availability path-monitoring` | monitored path health on the would-be-active peer | — |
| `show high-availability link-monitoring` | monitored link health | — |
| `show session info` | dataplane readiness, session counts | — |
| `show interface all` | link state on the passive peer | — |
| `show routing route` | FIB convergence on the passive peer | large output; parse to counters only |
| `show system info` | sw / content / threat version parity | point 9 applies |

Note: `show high-availability state` is **already gated and implemented**
(`get_target_ha_runtime_state`) and is not part of this ask.

## Implementation deviations

Recorded explicitly rather than silently absorbed (the `RB.3a` / `DEV.3.3`
amendment pattern).

- **D1 — split-brain outranks `no_viable_target` as the reported reason.**
  Architecture §4 says "the first failure sets the verdict" and orders
  viable-target ahead of split-brain. Found at implementation time: a
  split-brained cluster (two members `ACTIVE`) *also* has no standby, so the
  literal §4 order diagnoses it as `no_viable_target` — true, but the symptom
  rather than the cause, and it points the operator at the wrong remedy
  (find a standby, instead of resolve the split). `_verdict_for` therefore
  reports `split_brain_observed` whenever it is observed; every other failure
  keeps §4's order. Covered by AC-7.
- **D2 — a healthy PAN A/P pair was misreported as split-brain.** Found by the
  smoke run against the `tests/fixtures/uitest` bundle, **not** by the unit
  tests, which had paired only same-shaped records. Each PAN peer reports both
  its own `state` and its view of the peer's (`peer_state`); collecting both
  from every member double-counts a resolved pair, so a correct
  active/passive pair (01: active/peer=passive, 02: passive/peer=active)
  produced two `active` observations and failed `no_split_brain`. A false
  split-brain alarm on a healthy pair is the worst direction for this build to
  be wrong in — it would send an operator to investigate a non-existent
  outage. `_pan_states` now trusts each member's own `state` when the unit has
  direct evidence for more than one member, and falls back to `peer_state`
  only for the P7 single-member case where it is the only peer evidence
  available. Two regression tests pin both halves (healthy pair passes, a
  genuinely split-brained pair is still caught).

## Correctness contract

1. `--ha-readiness-check` opens no network connection and requires no
   credential. A run with every device unreachable produces an identical file
   to a run with every device reachable, given the same stored evidence.
2. No unit is ever assigned `SAFE_TO_FAILOVER` or
   `DEGRADED_PROCEED_WITH_RISK` by this build (P4).
3. A load-sharing or unknown-mode CP cluster is `NOT_A_FAILOVER_UNIT` or
   `INSUFFICIENT_EVIDENCE` respectively — never `UNSAFE`, which would imply
   the question was coherent (P3).
4. More than one member of a unit reporting `ACTIVE` yields
   `UNSAFE_DO_NOT_FAILOVER` with reason `split_brain_observed`.
5. A unit with no peer reporting a usable standby/passive role yields
   `UNSAFE_DO_NOT_FAILOVER` with reason `no_viable_target`.
6. Every §4 condition not evaluable from stored evidence appears as an
   explicit check with status `INSUFFICIENT_EVIDENCE` and a non-empty
   `missing_evidence` label. Silent omission is a defect.
7. A VSX host and each of its virtual systems are distinct units; a VS never
   inherits the physical host's verdict (the `RB.3a` decision-A3 principle).
8. A corrupt `ha_readiness.json` degrades the next run to "no prior
   readiness", exit 0.
9. `ha_cluster_mode` is `"unknown"` whenever the parse is not unambiguous.

## Privacy and safety invariants

- `ha_readiness.json` contains no management address, no raw device output,
  no licence string and no command string other than the fixed
  `missing_evidence` labels.
- No member hostname or interface name enters `ha_cluster_mode` or any check
  reason code.
- The repository privacy gate stays **PASS / 0**.
- No new credential, no new authentication transport, no new network access
  pattern — this build has none at all.
- No write-capable symbol is introduced anywhere under `utils/failover/` (P5,
  AC-9).

## Implementation plan

1. `configuration/checkpoint_config_collector.py`: P2's additive mode parse
   (`_parse_clusterxl_cluster_mode`) at the existing `cphaprob stat` call
   site, before the existing stdout discard. Both the per-endpoint and the
   per-VS call sites.
2. `utils/failover/__init__.py`, `utils/failover/assessment.py`: `HaUnit`
   derivation (CP cluster / VSX host / VSX VS / PAN pair per P7), the check
   list, the verdict function with P4's constraint expressed as a guard, not
   a convention.
3. `main.py` / `application/` dispatch: `--ha-readiness-check`, offline
   maintenance class, cross-guarded against the other maintenance modes the
   same way `--recovery-validate` and `--compliance-trend-reconstruct` are.
4. `utils/collection_executor.py`: P6 allowlist comment only, no set change.
5. Tests (below).
6. `CURRENT_STATE.md`, `project/roadmap.json`, `project/backlog.json`,
   `project/feature_registry.json`, `project/build_history.json`.

Expected footprint: ~4 source files + 1 test file — within the protocol's
default build size.

## Acceptance criteria

- **AC-1** Mode parse: fixture `cphaprob stat` outputs covering HA New mode,
  Load Sharing Unicast, Load Sharing Multicast, VRRP and an unrecognised
  format → correct `ha_cluster_mode`, `"unknown"` for the last, and the
  local-member role parse is **byte-identical to today** for every fixture
  (the existing behaviour is not disturbed).
- **AC-2** Offline: a full `--ha-readiness-check` run with all network access
  patched to raise produces a complete `ha_readiness.json`, exit 0.
- **AC-3** CP unit assembly: the uitest fixture's `cp-core` cluster forms one
  two-member unit; `cp-edge-01` (no cluster) forms none.
- **AC-4** VSX: `vsx-gw-01` and each of its virtual systems are distinct
  units; no VS carries the host's verdict (correctness rule 7).
- **AC-5** PAN pairing (P7): a `peer-ip` resolving to exactly one entity
  pairs; resolving to zero or to several yields a single-member unit with
  `pan_ha_peer_unresolved`; never a guessed pair.
- **AC-6** **`SAFE_TO_FAILOVER` is unreachable** — over an exhaustive
  generated matrix of unit shapes and evidence combinations, no input
  produces `SAFE_TO_FAILOVER` or `DEGRADED_PROCEED_WITH_RISK`. This is the
  test that keeps P4 true after this build.
- **AC-7** Split-brain: two members reporting `ACTIVE` →
  `UNSAFE_DO_NOT_FAILOVER` / `split_brain_observed`.
- **AC-8** Load sharing: an LS-mode cluster → `NOT_A_FAILOVER_UNIT`, and
  specifically **not** `UNSAFE` (P3).
- **AC-9** No write path: `utils.failover` exposes no symbol matching
  executor/plan/action/rollback, and the package contains only
  `__init__.py` and `assessment.py`.
- **AC-10** Insufficient-evidence completeness: every §4 condition appears in
  every unit's check list, and each unevaluated one carries a non-empty
  `missing_evidence` label (correctness rule 6).
- **AC-11** Privacy: no management address, hostname, licence string or raw
  device text appears in `ha_readiness.json` across the full fixture fleet.
- **AC-12** Fail-safe: a corrupt `ha_readiness.json` degrades to "no prior
  readiness", exit 0.
- **AC-13** `"ha-readiness"` is not allowlisted: a scheduler policy naming it
  raises `SchedulerPolicyError` at load time.

## Validation and merge gate

- Full suite one-shot, file-backed: `py -m pytest -q > pytest_result.log 2>&1`.
  Baseline to beat: **933 passed / 27 skipped / 2 failed**. The 2 are the
  documented pre-existing order-pollution failures; zero new failures.
- Repository privacy gate **PASS / 0** (delete gitignored `data/` and `logs/`
  first).
- Render harness: **not triggered** — this build touches no `templates/`,
  `static/` or payload builder. It must still be green in the full suite.
- **Real-environment validation:** this build performs no device I/O, so it
  has no real-environment gate of its own and can reach `AUTOMATED_VALIDATED`
  on automated evidence alone. **One caveat that must not be lost:** P2's
  mode parse consumes real `cphaprob stat` output, and its fixtures are
  constructed, not captured. The first real-device run that exercises a CP
  cluster should confirm `ha_cluster_mode` resolves rather than falling back
  to `"unknown"` — a fixture-drift check, not a safety gate. Record it on
  `on_hardware_real_env_validation` as a low-severity confirmation item.

## Risks

- **Mode-parse drift across Check Point releases.** `cphaprob stat`'s header
  format is not a stable contract. Mitigated fail-closed (P2): unrecognised
  output yields `"unknown"`, which yields `INSUFFICIENT_EVIDENCE`, never a
  wrong mode. The real-device confirmation above will likely add a format.
- **A readiness assessment that can only say "no" or "not enough" may read as
  a broken feature.** It is not — it is the honest state of the evidence, and
  P4 makes it structural. This must be stated plainly when the first numbers
  are reported, exactly as `RB.3a` had to state that a VSX-heavy estate would
  look *worse* after that build. If it is presented as "the failover
  readiness feature" without that framing, it will be misread.
- **`INSUFFICIENT_EVIDENCE` everywhere creates pressure to approve the
  `OP.0b` gate quickly.** That pressure is the intended effect of making the
  gap visible, but the gate is still a security review and should not be
  rushed because a dashboard looks empty.
- **P7's PAN pairing by `peer-ip` is inference over configuration evidence,
  not a discovered relationship.** It is fail-closed and never guesses, but a
  device whose HA config points at an address the platform does not inventory
  will report `pan_ha_peer_unresolved` even though the pair is real and
  healthy. The durable fix is a discovery-plane peer field; noted as a
  follow-up, not attempted here.

## Rollback

Delete `utils/failover/`, the `--ha-readiness-check` flag and its dispatch
branch, and the `_parse_clusterxl_cluster_mode` call and function. No stored
evidence, collector behaviour or payload changes, so nothing migrates.
`data/state/ha_readiness.json` is runtime state and may be deleted.

## Definition of done

1. AC-1 … AC-13 green.
2. Full suite at or above the 933 / 27 / 2 baseline; privacy gate PASS / 0.
3. No new device command issued anywhere in the diff (reviewable as the
   absence of any new command string).
4. Project metadata updated (`CURRENT_STATE.md`, roadmap, backlog,
   feature registry, build history).
5. Status recorded as `AUTOMATED_VALIDATED`; the P2 mode-parse fixture-drift
   confirmation listed on `on_hardware_real_env_validation`.
6. `OP.0b`'s gate draft carried forward for product-owner / security review
   as a distinct, un-approved item.

## Next movement / model

`IMPLEMENTATION` at **`Sonnet 5, normal`**.

Extended thinking is **not** warranted for the implementation. The genuinely
hard calls — the `OP.0` split (P1), the fail-closed verdict invariant (P4),
the load-sharing vocabulary (P3), the dormant-write-path refusal (P5) and the
PAN pairing gap (P7) — are all decided above, and each is pinned by an
acceptance criterion rather than left to implementation judgement. What
remains is a bounded parser, a derivation over an existing in-memory fleet
structure, one offline CLI flag and a test file.

The one place to slow down is AC-6: it must be an exhaustive generated matrix
over unit shapes and evidence combinations, not a handful of hand-written
cases. It is the only thing standing between this build and a future edit
that quietly makes a green light reachable.
