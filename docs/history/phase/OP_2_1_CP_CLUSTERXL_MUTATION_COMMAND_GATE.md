# OP.2.1 — Check Point ClusterXL mutation command gate

## Status

**APPROVED 2026-09-05 — TECHNICAL GATE COMPLETE, docs only.** No code, no
schema change, no taxonomy member, no device contact. This document
individually adjudicates, per
`docs/AI_DEVELOPMENT_PROTOCOL.md` "Network-device command gate", the exact
minimum officially-supported CP ClusterXL mutation primitive for **one**
controlled failover and its explicit reversal/failback — the two write
primitives `FAILOVER_ENGINE_ARCHITECTURE.md` §10 and the frozen `OP.2.0`
contract's implementation plan name as the `OP.2.1` prerequisite for
`OP.2.C`. Approving a candidate here changes **nothing reachable**:
`utils.action_taxonomy.CLASS_2_OPERATIONAL_STATE_CHANGE` keeps no member,
`DenyAllAuthorizer` still denies unconditionally at `create_action`, and no
vendor adapter exists anywhere in the repository (`OP_2_A_B_EXECUTION_
FOUNDATION.md`). This gate only fixes *which* primitive `OP.2.C` — whenever
its own, separate, much longer prerequisite list clears — is authorized to
implement, and records the two safety findings the build task asked for
(§"D-V7b / D-F3 — do they block the first pilot").

- Design parent: `docs/design/FAILOVER_ENGINE_ARCHITECTURE.md` §3.1, §5, §8,
  §10/§10.1 (the two named write primitives: `clusterXL_admin down` /
  `request high-availability state suspend`, and their rollbacks).
- Contract parent: `docs/history/phase/OP_2_0_CONTROLLED_HA_OPERATION_
  ARCHITECTURE.md` (FROZEN 2026-09-04) — P11 (typed adapter boundary, no
  generic mutation primitive), P12 (reversal is a new typed action, no
  automatic rollback), P16 (CP ClusterXL first, one vendor), P17 (identity
  law), §"Vendor-adapter contract" (`execute_once` semantics), §"Implementation
  plan" (`OP.2.1` row), §"Explicit blockers" C (vendor-evidence blockers).
  This document interprets that contract; it does not reopen it.
- Structural precedent: `docs/history/phase/OP_0B_1_COMMAND_GATE_PACKAGE.md`
  (the CLASS 0 read gate `OP.2.0`'s own text names as "the gate-package shape
  `OP.2.1` must mirror"). This document mirrors its per-candidate record
  shape, decision vocabulary discipline and rejected-operations carry-forward
  — adapted from *reads* to the two *mutation* primitives.
- Movement: `SECURITY_GATE → COMMAND_CONTRACT → APPROVAL`, extended reasoning
  (security boundary), per `CLAUDE.md`/`AGENTS.md` routing.
- Scope: **Check Point ClusterXL only** — non-VSX, non-Load-Sharing, High
  Availability (New) mode. PAN, VSX, PAN A/A and Load Sharing are explicitly
  out of scope (`OP.2.0` P16, Scope — out): PAN is not an initial CLASS 2
  target while `B₂` is `NOT ESTABLISHED`; VSX and Load Sharing have no
  standby/no single failover unit in the frozen model. This build does not
  pull in `DEPLOY.1A`/OIDC, production SSH host-key hardening, PAN, VSX, or
  general deployment hardening — none of them is technically required to
  name the CP command-gate rows this document adjudicates.

## Objective

For each mutation-primitive candidate, answer the ten `AI_DEVELOPMENT_
PROTOCOL.md` gate points plus the six items the build task named explicitly:
exact command/context and supported semantics; intended effect and target
scope; whether it is safe for one-shot `execute_once`; expected observable
postcondition; no-blind-retry behaviour; and confirmation that reversal is a
separate typed action, never an automatic rollback. No implementation, no
test that contacts a device, no schema change, no readiness change, no
`CLASS_2` taxonomy member.

## Evidence tier and its limits

Every `sc1.checkpoint.com` page cited below returned `EGRESS_BLOCKED` on a
direct fetch attempt this session (same domain, same failure mode already
recorded for `D-V7a`/`D-V7b`: "page bodies `EGRESS_BLOCKED`; titles +
snippets via `WebSearch`" — `OP_0B_0_VENDOR_FAILOVER_PREFLIGHT_EVIDENCE_
SURFACE.md` §"Vendor semantics established"). No GitHub mirror of the
official ClusterXL Admin Guide or CLI Reference Guide exists (unlike the PAN
side, where `PaloAltoNetworks/pan-os-upgrade-assurance` closed `D-V4`). Every
citation below is therefore **titles + WebSearch-synthesized snippets of the
named official page**, the same evidence tier the frozen `OP.0b.0` contract
already used to reach `D-V7a = CLOSED_BY_DOCS`, not a full page-body read.
Where two independently-titled official pages (a CLI Reference Guide entry
and an Administration Guide section, or two different code-stream editions
of the Administration Guide) corroborate the same fact, that is noted as
**CORROBORATED**; a single-page snippet is **SINGLE_SOURCE**. Nothing below
is community-only: every semantic claim traces to at least one exact,
named, official Check Point page title, matching this repository's existing
evidence-tier discipline (`AGENTS.md` vendor-semantics law) — community pages
(51sec.org, CheckMates, itsecworks) are cited only as corroboration of a
claim an official page title independently supports, never as the sole
source of a safety-relevant fact.

## Action class

Both candidates below are `CLASS_2_OPERATIONAL_STATE_CHANGE`
(`utils/action_taxonomy.py`). Documenting them here adds **no member** to
that class — the class's own `why` text ("No member exists yet. Blocked
until every `OP.2` prerequisite... including the network-device command gate
for the two write primitives and their rollbacks") is answered, not
overridden. `OP.2.C` is the only place a member is ever added, and it stays
blocked on everything this gate does **not** touch: `DEPLOY.1A` OIDC +
`OPERATE`, `cp_production_ssh_host_key_trust_hardening`, the signed
change-management review, and — per §"D-V7b / D-F3" below — `D-V7b` and
`D-F3` themselves.

## Check Point execution context (unchanged repository invariant)

Reuses, never redefines: validated SSH login shell = **Expert** (same
primitive `CP-A4`–`A8`/`B1` already use); one strict-trusted SSH session per
physical member; existing CP transport timeouts
(`SECURITYEXPERT_CP_CONFIG_SSH_COMMAND_TIMEOUT_SECONDS`,
`SECURITYEXPERT_CP_CONFIG_SSH_CONNECT_TIMEOUT_SECONDS`) — no new timeout is
invented for a mutation submission; per `OP.2.0` P8 the submission uses the
coordinator's stage-2 member admission (precondition re-observation +
submission + verification), never the collection-only lease held across the
human wait. **Only the Gaia Expert-shell script form of the primitive is
gated here.** The Clish-equivalent syntax (`set cluster member admin
{down|up} [permanent]`) is a **separately-named, distinct** command surface
with at least one documented community reliability report against the `up`
direction (CheckMates: `"Set cluster member admin up" doesn't work`) that no
official source in this build's evidence set resolves — it is **not**
approved by this gate and is carried forward as its own future row if a
future gate ever needs it, exactly the discipline `OP.0b.1` already applies
to `cphaprob -l list` vs `-ia list` (two distinct commands are never treated
as interchangeable because they "do the same thing").

---

## Per-command gate records — Check Point ClusterXL

**ID:** CP-M1
**Vendor:** Check Point
**Purpose:** The single approved mutation primitive for one controlled,
planned ClusterXL failover — `FAILOVER_ENGINE_ARCHITECTURE.md` §3.1's own
"recommended primitive", now gated per `OP.2.1`.
**Exact command/context:** `clusterXL_admin down` (**no `-p`** — see
§"Persistence (`-p`) — deferred, not part of this battery" below), issued on
a fresh Expert-shell execute channel over the **existing, already
identity-gated per-member SSH session/transport** the product's collectors
already validate — no new credential path, no new transport (`AGENTS.md`
diagnostic-path law; `OP.2.0` vendor-adapter contract "Transport" row).
**Action class:** `CLASS_2_OPERATIONAL_STATE_CHANGE` (no member exists;
this row documents what *would* be approved for `OP.2.C`, nothing more).
**Supported semantics (official):** the command **registers a Critical
Device named `admin_down`** on the target member and reports that device's
state as `problem`; a Critical Device reporting `problem` is the same
mechanism `CP-A5` (`cphaprob -ia list`, already `APPROVED_FOR_S5`) already
reads for hardware/software failures — this is the vendor's own designed
"graceful manual failover" mechanism, not a side channel this gate invents
("The `clusterXL_admin` Script", R81 CLI Reference Guide — Topics-CLIG/CXLG;
"Registering a Critical Device", R81.10 ClusterXL Admin Guide —
`CORROBORATED`, two independently-titled official pages describing the same
registration mechanism). The peer member observes the new `problem` device
on its next CCP update and takes over as active; this is a **peer-driven**
transition — the command itself only registers the device and reports the
local member `DOWN`, it does not manipulate the peer.
**Intended effect and target scope:** exactly **one** physical member of
one CP ClusterXL cluster (`operational_entity_id` = `group_id`, `OP.2.0`
identity invariants) — the member the adapter's fresh precondition
re-observation confirms is currently `ACTIVE` (`OP.2.0` P6, correctness
contract item 4; the design parent's cross-vendor invariant "always act on
the currently-active device, never the target"). **Never** the standby/
target member — official guidance and this repository's own design text
agree the primitive is only ever issued against the active side
(`FAILOVER_ENGINE_ARCHITECTURE.md` §3.1: "Never manipulate the *target*
(standby) to 'pull' it active."). Scope excludes VSX (a VSID is never a
`CLASS_2` target, `OP.2.0` identity invariants, unchanged) and Load Sharing
mode (`_verdict_for` already returns `NOT_A_FAILOVER_UNIT` for it — there is
no single standby to fail over to, so this primitive is never proposed
against an LS-mode entity; the adapter's `capability()` must return
`UNSUPPORTED` for any non-HA-mode entity, not attempt the primitive anyway).
**Safe for one-shot `execute_once`:** **yes, as a single self-contained
operation** — every official source describes `clusterXL_admin down` as one
command producing one state change (register `admin_down` = `problem`), not
a multi-step procedure needing a companion command to "complete" the
transition. This satisfies `OP.2.0` P6/P11: `execute_once` submits exactly
this one command and returns; there is no second command the adapter must
chain to finish the mutation. **No official source found in this evidence
set documents the effect of invoking the command a second time against a
member already in the `admin_down` state** (i.e., true idempotency is
`UNKNOWN`) — this is immaterial to the architecture, which never invokes it
twice regardless (P7: exactly one submission per `action_id`, no retry, no
resend, under any circumstance), but is recorded here so a future reader
does not assume idempotency as a safety property this gate established.
**Expected observable postcondition:** **reuses the already-approved class 0
read battery — no new read command is introduced by this gate.** On the
acted-upon member: `cphaprob -ia list` (`CP-A5`) shows a new Critical Device
`admin_down` in `problem` state; the existing local-role read (`A3`) reports
that member's state as `DOWN`. On the peer member: the local-role read
reports `ACTIVE`. This gives post-action verification (`OP.2.0` P9) **two
independent corroborating signals** — the intended role transition (already
the primary postcondition) and the `admin_down` pnote's appearance (a
positive marker specific to *this* primitive, not a generic "role changed"
inference) — from reads this repository has already validated
(`REAL_ENV_VALIDATED 2026-09-04`, `CURRENT_STATE.md`). **How long after
submission this postcondition is stably observable
(`settle_observation`) is not established by any source in this evidence
set** and stays `UNKNOWN` — per `OP.2.0` §"Post-action verification", this
means `FAILED_NO_CHANGE` stays unreachable until a real-environment pilot
measures it; only `SUCCEEDED` (postcondition positively observed) or
`OUTCOME_UNKNOWN` are reachable outcomes for `OP.2.C`'s first pilot. This
gate does not invent a settle timer.
**No-blind-retry behaviour:** governed entirely by the already-frozen
`OP.2.0` P7/P6 machinery, which this gate adds nothing to and narrows
nothing from: exactly one `execute_once` submission per `action_id`; a
transport failure the adapter can positively prove happened **before** the
command reached the device (e.g. the Expert-shell session was never
established) maps to `SUBMISSION_NOT_SENT` → `ABORTED_PRE_MUTATION` — the
one and only pre-boundary escape; every other failure mode (timeout after
send, connection drop mid-command, ambiguous/missing shell response) is
`SUBMISSION_OUTCOME_UNKNOWN` → `OUTCOME_UNKNOWN` and quarantines the entity.
**Never** does a failure of `clusterXL_admin down` cause the adapter to try
`-p`, `cphastop`, `cpstop`, a reboot, or the command a second time as an
"alternate primitive" — `OP.2.0` P11 (no generic cross-vendor mutation
primitive, no "try the closest thing") and P7 (no blind retry) both already
forbid this; this gate reaffirms it for this specific primitive rather than
leaving it to inference.
**Session/transport reuse:** yes — the same per-member identity-gated
transport the precondition re-observation (class 0) just used in the same
coordinator admission (`OP.2.0` P6, "immediately before the commit... the
adapter re-observes the plan's subject precondition... with the
already-approved class 0 battery"); no new SSH session, no new credential.
**Version/platform:** no version gate found in any source reached; the
command is documented current through the R82 CLI Reference Guide line
(same family the search results returned for R80.20SP Maestro's
`g_clusterXL_admin`, R80.30, R81, R82). This gate approves the **non-Maestro,
non-Scalable-Platform** form only (`g_clusterXL_admin` is a distinct
Maestro/Security-Group primitive with its own official page and is out of
scope — this estate's approved ClusterXL entities are standard two/N-member
clusters, not Maestro Security Groups; a future Maestro estate needs its own
gate row).
**Sensitive output:** none beyond what `CP-A3`/`CP-A5` already read (member
role token, pnote name). This primitive's own invocation produces no
response body this product parses — the postcondition is read back
separately, through the existing class 0 battery, never through the
mutation channel's own output.
**Safe retained fields:** none new — the audit record carries
`capability_id`/`adapter_version` and the typed `intended_postcondition`,
never the command text (`OP.2.0` P18; this repository never has the command
string above the adapter boundary to begin with).
**Real-env validation required:** yes (`OP.2.D`'s pilot) — `settle_
observation` and whether the `admin_down` pnote and role-flip are always
observed together (vs. either lagging the other under real CCP timing) are
both unmeasured. This is exactly the kind of fact `OP.2.0`'s own text says
"the first pilot's job is to measure and record... not to assume."
**Decision:** **APPROVED_FOR_OP2C.** Documentation-only approval: names the
one primitive `OP.2.C`'s CP adapter is authorized to implement for the
graceful-failover `action_type`, once `OP.2.C`'s own separate prerequisite
list (`DEPLOY.1A`, SSH trust hardening, change-management review, `D-V7b`,
`D-F3` — §"D-V7b / D-F3" below) is independently satisfied. Approving this
row does **not** satisfy any of those; it only removes "no gate row exists"
from `OP.2.C`'s blocker list for the mutation primitive itself.

---

**ID:** CP-M1-R
**Vendor:** Check Point
**Purpose:** The explicit reversal/failback primitive for `CP-M1` — a
**separate typed class 2 action**, never an automatic rollback (`OP.2.0`
P12; `FAILOVER_ENGINE_ARCHITECTURE.md` §10.2 records the supersession of the
design parent's earlier auto-rollback text). This row exists precisely
*because* P12 requires reversal to be its own gated action with its own
`action_id`, own authorization, own fresh preflight, own confirmation, own
lock acquisition, own single submission, own independent verification and
own audit record — `reverses_action_id` is the only link to `CP-M1`'s
record. Nothing about `CP-M1-R`'s approval here or its eventual
implementation ever causes it to fire automatically after `CP-M1`.
**Exact command/context:** `clusterXL_admin up` (**no `-p`** — same
deferral as `CP-M1`; up-without-`-p` is the correct reversal of a
down-without-`-p` action: neither ever touched `$FWDIR/conf/cphaprob.conf`,
so neither needs the other to undo a persisted change), same Expert-shell
execute channel and session-reuse discipline as `CP-M1`.
**Action class:** `CLASS_2_OPERATIONAL_STATE_CHANGE` (no member; same
caveat as `CP-M1`).
**Supported semantics (official):** unregisters/clears the `admin_down`
Critical Device on the member that was brought down, so it no longer
reports `problem` for that device — the same registration mechanism
`CP-M1` uses, run in reverse ("The `clusterXL_admin` Script", R81 CLI
Reference Guide; "Registering a Critical Device", R81.10 ClusterXL Admin
Guide — `CORROBORATED`, same two pages as `CP-M1`, which document the
script as the down/up pair together, not two unrelated commands).
**What happens next depends on the cluster's configured recovery
setting, and this is where the reversal's disclosure requirement and
`D-V7b` meet (below):**
- **"Maintain current active Cluster Member"** — the member that ran `up`
  returns to **standby**; the member that took over during `CP-M1` stays
  active. **No second impact.**
- **"Switch to higher priority Cluster Member"** — if the reversed member
  has higher configured priority, it **takes over again** immediately on
  `up`. **A second brief impact is caused by the reversal itself.**
Both branches are already `CLOSED_BY_DOCS` at the behavioral-semantics level
(`D-V7a`, `OP_0B_0_VENDOR_FAILOVER_PREFLIGHT_EVIDENCE_SURFACE.md`
§"Final semantic blocker closure", citing R80.40 "Cluster Failover" /
R81.20 "High Availability Mode"; independently reconfirmed this session —
`CORROBORATED` — via "High Availability and Load Sharing Modes in
ClusterXL", R80.40 ClusterXL Admin Guide, and "Cluster Failover", R80.20
GA/R80.30/R82 ClusterXL Admin Guide, all describing the same two named
recovery methods identically). **What is not established is which mode a
given cluster is currently configured with, machine-readably** — that is
`D-V7b`, unchanged by this gate (§"D-V7b / D-F3" below).
**Intended effect and target scope:** exactly one physical member — the
same member `CP-M1` acted on, identified by its opaque `subject_member_
token`, never re-resolved from a hostname or address (`OP.2.0` P17).
**Safe for one-shot `execute_once`:** yes, same reasoning as `CP-M1` — one
command, one registration-removal operation, no chained second command.
**Expected observable postcondition:** the `admin_down` pnote disappears
from `cphaprob -ia list` (`CP-A5`) on the reversed member; that member's
local-role read (`A3`) shows either `STANDBY` (maintain-current-active) or
`ACTIVE` again (switch-to-higher-priority) — **both are valid `SUCCEEDED`
postconditions for this action type**, distinguished only by which the
`ActionPlan`'s `intended_postcondition` declared, which itself can only be
declared confidently once `D-V7b` supplies the configured setting (until
then it is `UNKNOWN` and the plan discloses it as such, per the next
paragraph). `settle_observation`: `UNKNOWN`, same as `CP-M1`.
**No-blind-retry behaviour:** identical to `CP-M1` — one submission, no
retry, `SUBMISSION_NOT_SENT` is the only pre-boundary escape, everything
else is `OUTCOME_UNKNOWN`.
**Reversal preemption disclosure (`OP.2.0` P12):** the `ActionPlan` for
`CP-M1-R` **must** disclose, before confirmation, whether this reversal
will itself cause a second impact. Today that disclosure can only say
`UNKNOWN`: the only device-local corroboration this repository has approved
for the Cluster Mode string (`CP-A10`, `cphaprob state`) is explicitly
**non-authoritative** for this exact question (sk180184, already
`ESTABLISHED` in the frozen contract — "the mode string does not reliably
reflect the configured recovery setting"), and no safe machine-readable
management-plane read exists (`CP-A9`, `DEFERRED_UNKNOWN`, `D-V7b`). P12
itself tolerates this: *"Where it is `UNKNOWN`, the plan says `UNKNOWN` and
the operator decides."* **This disclosure gap alone would not block a
pilot** — but see §"D-V7b / D-F3" below for the separate, harder blocker
this same gap causes at the *readiness* layer, which does.
**Decision:** **APPROVED_FOR_OP2C**, same documentation-only scope and same
outstanding-prerequisite caveat as `CP-M1`.

---

## Persistence (`-p`) — deferred, not part of this battery

`clusterXL_admin down -p` / `up -p` writes the `admin_down` Critical Device
into `$FWDIR/conf/cphaprob.conf` so the state survives a reboot ("The
`clusterXL_admin` Script", R81 CLI Reference Guide — `-p` = "permanent";
independently corroborated by "Configuring the Cluster State
(`g_clusterXL_admin`)", R80.20SP Maestro Admin Guide, describing the same
`-p`/permanent semantics for the sibling Maestro command —
`CORROBORATED` for the flag's general meaning, though the Maestro command
itself is out of scope per `CP-M1`'s version/platform note).
`FAILOVER_ENGINE_ARCHITECTURE.md` §3.1 already frames `-p` as "use for real
maintenance" — a broader-scoped, config-file-writing variant appropriate to
a planned maintenance window, not to "**one** controlled ClusterXL failover
and explicit reversal" (the build task's own framing, which describes a
single bounded test, not a maintenance-window operation). Deferring it here
is the same discipline `OP.0b.1` already applied to `CP-A10`/`CP-A11`
(technically fine, withheld from the current battery):
**DEFERRED_NOT_IN_INITIAL_BATTERY** — a future gate revisits it only if a real maintenance-
window use case is scoped, and only after confirming (a) that `-p`'s config-
file write does not collide with any read this product's collectors already
parse from `$FWDIR/conf/`, and (b) that its own reversal (`up -p`, which
must remove the same config entry, not merely re-run `up`) is separately
verified. No source in this evidence set characterizes `-p`'s failure modes
distinctly from the non-`-p` form, so nothing is lost by deferring it now.

## Rejected mutating alternatives (carried forward / newly rejected)

None of these is reopened by this gate. The first three restate
`OP_0B_1_COMMAND_GATE_PACKAGE.md`'s existing rejections in the execution
context (not merely the preflight context) they were originally listed for;
the rest are new to this gate because `OP_0B_1` only needed to reject them
from *preflight* — this gate rejects them as **failover primitives**.

| Command / action | Why rejected as the CP-M1 primitive |
| --- | --- |
| `cphastop` | stops session synchronization and the ClusterXL module itself; the design parent's own comparison (§3.1: "Do **not** use `cpstop`/`cphastop`/reboot as a failover primitive") and this session's corroborating official-support-KB title (sk55081, "Best Practices - Manual fail-over in ClusterXL") agree it is heavier and requires full re-sync afterward — `clusterXL_admin down` does not disable synchronization and needs no full re-sync after `up`, which is exactly why it, not `cphastop`, is the vendor's own recommended graceful primitive |
| `cpstop` | stops all processes on the member — strictly more disruptive than `cphastop`; never a failover primitive |
| Reboot | slowest recovery path, no graceful hand-off, out of scope entirely |
| Priority reordering / cluster-object edits to force a role change | a `CLASS_3_CONFIGURATION_WRITE` action, not `CLASS_2` — this repository does not have and will not gain a management-plane configuration-write capability via this gate |
| Interface/link manipulation ("pull the link") to force the standby to notice a peer failure | more disruptive, imprecise (affects real network state, not just cluster role), explicitly excluded by the design parent's cross-vendor invariants |
| Acting on the standby/target member with any command | `OP.2.0` correctness contract item 4 ("only the vendor-designated safe subject is acted on") and the design parent's explicit "never manipulate the target" rule both forbid this regardless of which command is used |
| `set cluster member admin {down\|up} [permanent]` (Gaia Clish form) | a distinct command surface from the Expert-shell script this gate approves, with a documented community reliability report against the `up` direction unresolved by any official source reached — not approved, see §"Check Point execution context" above |
| `g_clusterXL_admin` (Maestro / Scalable Platform) | a different primitive for a different platform family (Security Groups, not standalone ClusterXL members) with its own official page; out of scope for this estate |
| Management-plane write of the recovery-method/preemption setting | never proposed at any point in this build — even the **read** of this setting (`CP-A9`) stays `DEFERRED_UNKNOWN`; a write is not considered |

---

## Command → fact matrix

| Command ID | Vendor | `action_type` (proposed label, not implemented) | Reverses | Context | Gate decision |
| --- | --- | --- | --- | --- | --- |
| CP-M1 `clusterXL_admin down` | CP | `cp_clusterxl_ha_graceful_failover` | — | Expert, physical, HA mode only | APPROVED_FOR_OP2C |
| CP-M1-R `clusterXL_admin up` | CP | `cp_clusterxl_ha_graceful_failback` | CP-M1 | Expert, physical, HA mode only | APPROVED_FOR_OP2C |
| CP-M1 `-p` / CP-M1-R `-p` | CP | — | — | Expert, physical | DEFERRED_NOT_IN_INITIAL_BATTERY |

The `action_type` labels above are this document's proposal for `OP.2.C`'s
own registry, exactly as typed, vendor-neutral labels — never command text
(`OP.2.0` P18); `OP.2.C` is free to rename them, and doing so does not
require reopening this gate.

---

## D-V7b / D-F3 — do they block the first pilot?

The build task asked this directly: *"decide whether `D-V7b` and `D-F3`
truly need closure before the FIRST bounded human-controlled pilot, or
whether they may remain explicit non-green/acknowledged evidence without
weakening the frozen safety model. Do not invent policy just to make `SAFE`
reachable."* Answer, worked from what is already frozen and already
implemented — no new policy is introduced below:

**Yes, both must close. There is no acknowledged-but-open path, for either,
under the model as it stands today.** This is not a new restriction this
gate adds — it is what the existing code already, structurally, does:

1. `OP.2.0`'s correctness contract, item 6, requires *"The canonical
   readiness authority returned a **positive** verdict for this entity from
   that generation"* as one of eight **simultaneous** eligibility
   conditions. There is no partial-credit path in that contract: a
   non-positive verdict is `NOT_ELIGIBLE`, full stop.
2. `utils/failover/assessment.py::_verdict_for` (the one canonical readiness
   authority `OP.2.0` P3 says eligibility must consume "as a projection... no
   second verdict engine") returns `VERDICT_SAFE` **only if every one of the
   seven `STOP_CONDITIONS` reports `PASS`** — `all(c.get("status") ==
   CHECK_PASS for c in checks) and len(checks) == len(STOP_CONDITIONS)` — and
   additionally only if no `UNRESOLVED_POLICY_DECISIONS` (`D-F1`, `D-F2`,
   `D-F3`) applies to the evidence. This is not a "some checks are advisory"
   model; it is implemented as all-or-nothing.
3. `preemption_known` (check 6, the check `D-V7b` feeds) is **structurally
   forced to `INSUFFICIENT_EVIDENCE`** while `D-V7b` is open —
   `utils/failover/preflight_readiness.py`'s own comment says so verbatim:
   *"`D-V7b` (Check Point configured recovery) stays unreadable — `CP-A9`
   was not authorized by the command gate — so `preemption_known` stays
   [`INSUFFICIENT_EVIDENCE`]"*. Because `_verdict_for` requires **all seven**
   checks `PASS`, this alone makes `SAFE` unreachable for CP regardless of
   every other check's outcome.
4. `flap_history` (check 7, the check `D-F3` feeds) can, by the same
   `_verdict_for` docstring, *"never `PASS` while `D-F3` is open"* — an
   independent, second reason `SAFE` is unreachable, not merely a
   duplicate of point 3.
5. `OP.2.0`'s safety contract, item 2, is explicit and absolute: *"A
   non-positive readiness verdict is not operator-overridable."* There is no
   acknowledgement mechanism for a non-positive **readiness** verdict
   anywhere in the frozen contract — the one acknowledgement path that does
   exist (`acknowledge_unknown_outcome`, P10) is for a **post-action**
   `OUTCOME_UNKNOWN`, a completely different gate at a completely different
   point in the lifecycle. Inventing a pre-action "acknowledge and proceed
   with `D-V7b`/`D-F3` still open" path would be a **new** override
   mechanism the frozen contract does not have — exactly the "invent policy
   to make `SAFE` reachable" the build task forbids.

**Therefore:** under the architecture as frozen and as implemented, `D-V7b`
and `D-F3` are not merely items on a long prerequisite list alongside
`DEPLOY.1A` and SSH trust hardening — they are two independent, sufficient,
already-coded reasons eligibility item 6 can never pass for a CP entity
until each is actually decided (`D-F3`: a product-owner numeric threshold)
or actually closed with a confirmed safe read (`D-V7b`), not merely
"acknowledged." This is true for **any** pilot, bounded/local or not: the
correctness contract draws no distinction between a "bounded human-
controlled pilot" and a production run — both consume the same eligibility
gate, item 6, verbatim.

**One genuine, pre-existing tension is worth naming without resolving it
here (out of `OP.2.1`'s scope — this is `OP.0a`/`OP.1` readiness-layer
territory, not a command-gate decision):** `FAILOVER_ENGINE_ARCHITECTURE.md`
§4 originally described the preemption check as *"(not blocking)"* — record
whether preemption is configured, but do not gate the verdict on it — yet
the implemented `_verdict_for` treats it identically to every other stop
condition (all-or-nothing). This gate does not decide which text is right;
it only observes that `D-V7b`'s practical severity today is exactly as
described above (fully blocking, not "advisory-only" as the original design
prose suggested) **because of** that implementation choice, and flags it as
a fact for whoever next revisits `_verdict_for` (`op_degraded_verdict`,
already an open `OP.1`-gated decision) — not something `OP.2.1` has the
authority or the mandate to change. Resolving that tension in either
direction would not, on its own, touch `D-F3`, which has no such "not
blocking" text anywhere and is unambiguously a hard stop-condition already.

**`D-F3` (flap threshold) has no comparable tension**: the design parent's
§4 lists flap/split-brain instability as a genuine `UNSAFE`-triggering
stop-condition ("Split-brain now ⇒ `UNSAFE`"; "repeated recent failovers ⇒
instability"), consistent with `_verdict_for` treating it as fully blocking.
Nothing here suggests `D-F3` was ever meant to be advisory.

## What this build does not change

- `D-V7b` and `D-F3` are not closed by this document. Closing `D-V7b` needs
  a confirmed-safe machine-readable read (the `OP.0b.0`
  `op0b_0_close_d_v3a_d_v7b_pre_class2` GitHub-mirror/human-fetch technique,
  already the recorded plan) or an explicit, separately-argued product-owner
  decision that the current non-authoritative corroboration
  (`CP-A10`/sk180184) is acceptable for a bounded pilot with fully disclosed
  risk — the latter is a genuine `PO safety decision` this gate does not
  make on the build task's own instruction not to invent policy. Closing
  `D-F3` needs a product-owner numeric threshold decision
  (`d_f3_flap_failover_threshold_decision`, already an open roadmap item).
- `DEPLOY.1A` OIDC + `OPERATE`, `cp_production_ssh_host_key_trust_
  hardening`, and the signed change-management review are **not** touched
  or reasoned about further here. They are genuinely separate concerns from
  the two safety-model blockers above: `D-V7b`/`D-F3` gate whether the
  readiness *verdict* can ever be positive (a safety-model property, true
  for a local pilot exactly as much as for production); `DEPLOY.1A` and SSH
  trust hardening gate whether *anyone* is authorized to submit a `CLASS 2`
  action at all and whether the transport is production-trustworthy (an
  authorization/operational-hardening property). The build task's framing —
  "local controlled real-environment pilot readiness and production
  deployment readiness are separate concerns" — is correct for that second
  group, but does **not** extend to `D-V7b`/`D-F3`: those are eligibility-
  gate facts, not deployment-hardening facts, and a local pilot consumes the
  identical `_verdict_for` code path production would. Note additionally,
  for completeness and precisely because the task asked this build not to
  invent a shortcut: `OP.2.0` P2's authorization boundary text is itself
  unconditional and admits no "local pilot" exemption ("no CLI exemption...
  no test mode, no runtime selector... a `PERMIT`-returning implementation
  may exist only under `tests/`") — so even a bounded local pilot has no
  currently-designed path past `DEPLOY.1A` either. This gate does not
  attempt to design one; it only records the fact so a future session does
  not assume a "local-only" authorization shortcut exists.
- No taxonomy member, no adapter, no console job type, no CLI/argv entry
  point. `tests/test_architecture_convergence.py`'s existing assertions
  (`test_no_console_job_type_is_class_2_or_above`, the `utils/operate/`
  convergence set, `utils/failover/`'s tested absence of an executor) are
  unaffected — this gate touches none of the modules they assert over.

## Validation / merge gate

Documentation + one new deterministic text-level test file only:

- `tests/test_op2_1_cp_clusterxl_command_gate.py` (new) — mirrors
  `tests/test_op0b_s4_command_gate.py`'s discipline: the gate doc exists,
  every `**Decision:**` token uses the fixed vocabulary
  (`APPROVED_FOR_OP2C`, `DEFERRED_NOT_IN_INITIAL_BATTERY`, `REJECTED`), both
  approved rows declare `CLASS_2_OPERATIONAL_STATE_CHANGE` (never `CLASS_0_
  READ` — the inverse of `OP.0b.1`'s check, since this gate is mutations,
  not reads), every already-known-mutating alternative command stays listed
  as rejected, and `utils.action_taxonomy.CLASS_2_OPERATIONAL_STATE_CHANGE`
  still has no member and `DenyAllAuthorizer` is still the only production
  authorizer (source-scanned, same technique `test_op2_a_b_execution_
  foundation.py` already uses).
- `tests/test_architecture_convergence.py` — unaffected, re-run to confirm.
- Repository privacy gate (`py .\main.py --repository-privacy-check` /
  `python3 main.py --repository-privacy-check`).
- `git diff --check`.
- Full regression **not required** — no product code changes.

## Next movement / reasoning tier

`OP.2.1` is now drafted for CP ClusterXL. The next actionable, non-deployment
item this build's own findings point to is `D-F3` (product-owner numeric
threshold — `Sonnet 5, normal`, a deterministic policy-input decision, not
an architecture question) and, in parallel, resuming
`op0b_0_close_d_v3a_d_v7b_pre_class2`'s `D-V7b` half specifically (the
GitHub-mirror/human-fetch technique that already closed `D-V4`/`D-V7a` —
`Sonnet 5, extended thinking (high)`, vendor-semantics research). Neither is
started by this build. `OP.2.C` itself remains correctly blocked on its full
prerequisite list (this gate, `D-V7b`, `D-F3`, `DEPLOY.1A`, SSH trust
hardening, change-management review) — completing this gate closes exactly
one of six, by design.
