# OP.0b.1 — Preflight command-gate package (CP A4–A9 / PAN P3–P5)

## Status

**APPROVED (2026-09-03) — SCOPED PER THE PO OVERRIDES BELOW.** The
product owner recorded explicit approval in §"Approval record" for a
**narrower** set of rows than this gate's own technical assessment
offered, with two binding overrides applied to every newly-approved read:
**(1) no new application-level command retry** (§"Retry — PO override"),
and **(2) `B1` reuses the existing per-member session; no new SSH
transport session is authorized** (§"B1 session — PO clarification").
`CP-A10`, `CP-A11` and `PAN-P3` are technically `OPTIONAL_APPROVED` (that
assessment is preserved, unchanged, below) but the PO explicitly withheld
implementation authorization for the current `S5`/`S6` slice — they are
**not** part of the implementation battery (§"Minimum battery — PO-frozen
for implementation"). `CP-A9` and `PAN-P5` remain `DEFERRED_UNKNOWN`,
unchanged. This document still authorizes no implementation by itself:
`S5`/`S6` may issue only the rows this section marks PO-authorized, under
the retry and session constraints recorded here.

- Design parent: `docs/history/phase/OP_0B_0_VENDOR_FAILOVER_PREFLIGHT_EVIDENCE_SURFACE.md`
  (`FROZEN WITH REAL-ENV VALIDATION GATES`, session 4, 2026-09-03) — the sole
  implementation authority this document interprets. Every command, evidence
  category, decision and caveat below traces to that contract's §24 command
  surface table, §"Minimum Check Point/PAN preflight battery", §"Command / API
  safety contract", §"Open decisions" and §26 bug register; this document
  restates only what is needed to make each gate entry self-contained and
  never overrides it.
- Movement: `SECURITY_GATE → COMMAND_CONTRACT → APPROVAL`. Reasoning tier:
  Sonnet 5, extended thinking (high) — security boundary, per
  `CLAUDE.md`/`AGENTS.md` routing.
- Scope: `OP.0b.0`'s six new-command CP candidates `A4`–`A9` and three new PAN
  candidates `P3`–`P5`, plus two already-frozen CP battery items reused in a
  new execution context (`vsx stat -v`, `A10`/`A11` optional reads) needed to
  make the Check Point batteries in §"Minimum Check Point preflight battery"
  complete. No other row of the frozen §24 table is reopened.
- This build performed **no additional official-vendor-documentation
  fetch**: the three previously-`EGRESS_BLOCKED` vendor domains
  (`pan.dev`, `sc1.checkpoint.com`/`support.checkpoint.com`,
  `docs.paloaltonetworks.com`) were not retried in this session, and no new
  official source was found beyond what `OP.0b.0` sessions 1–4 already
  reached (repository source, recorded real-environment findings, and the
  GitHub-mirror-sourced official snippets already cited in that contract).
  Every decision below is therefore made **on the frozen contract's own
  already-established evidence**, not on new research — where that evidence
  is `PARTIAL`/`STILL_UNKNOWN`, the row is `DEFERRED_UNKNOWN` here rather than
  guessed, per `AGENTS.md` vendor-semantics law and this build's own §28 stop
  conditions.

## Objective

Answer, for each candidate: what exact operation, why needed, what evidence
it produces, whether it is read-only, in what shell/context, against what
target, how often, with what timeout/retry, what sensitive output exists,
what is retained, what remains `UNKNOWN`, and whether it is approved for
future `S5`/`S6` implementation. This package adds **no code, no test that
contacts a device, no schema change, no readiness change, no `CLASS 2`**.

## Action class

Every candidate below is evaluated against `utils/action_taxonomy.py`. All
`APPROVED_FOR_S5` / `APPROVED_FOR_S6` / `OPTIONAL_APPROVED` rows are
`CLASS_0_READ` — no exception. Every mutating primitive the frozen contract's
§24 table already identified (`cphaprob -d ... register/unregister`, `show
cluster failover reset history`, `fw ctl set int vsid <N>`, `clusterXL_admin
down/up`, PAN `request high-availability state suspend/functional`,
`sync-to-remote`) is carried forward here as **REJECTED** and is not
re-litigated — see §"Rejected mutating operations (carried forward,
unchanged)". None is `CLASS_1`+ and none is proposed for reclassification.

## Check Point execution context (repository/environment invariant)

- Validated SSH login shell = **Expert**. Gaia Clish is entered only
  explicitly, `clish -c '<command>'` (never assumed interactive Clish login) —
  same primitive `configuration/checkpoint_config_collector.py`'s
  `_run_gaia_read`/`_detect_gaia_shell` family already uses in production.
- VSX context = the already-validated Expert-shell primitive
  `vsenv <VSID> >/dev/null 2>&1; <command>` on a fresh exec channel — no VSLS
  assumption, no interactive-Clish assumption.
- Direct-Clish-only appliances (`capability_gap` today for `cphaprob`, bug
  register `CP-10`) are **out of scope for this gate**: every CP row below
  requires Expert access; a host that cannot reach Expert stays
  `capability_gap`/`UNSUPPORTED`, exactly as today. `S5` inherits `CP-10`
  unresolved — this gate does not invent a Clish equivalent for any row.
- Every CP row's execution context below is stated exactly (Expert direct /
  explicit `clish -c` / physical vs VS context / VS0-only). No row is
  approved with an ambiguous context.

## Per-command gate records — Check Point

All CP rows below reuse the **one strict-trusted SSH session per physical
member** the frozen contract's "Command / API safety contract" already
specifies — no new session, no new connection, no new credential (`B1`'s
own session note below is an explicit PO clarification of this same rule,
not an exception to it). **Retry — superseded by PO override (2026-09-03):**
the frozen contract's general rule ("at most one bounded retry per read...")
does **not** extend to any row this gate newly approves — see §"Retry — PO
override" below; each per-row record states `NO_RETRY` explicitly. Timeout
for every row is the existing approved CP SSH
transport default — `SECURITYEXPERT_CP_CONFIG_SSH_COMMAND_TIMEOUT_SECONDS`
(default 20 s, bounds 5–120 s) on top of the existing
`SECURITYEXPERT_CP_CONFIG_SSH_CONNECT_TIMEOUT_SECONDS` (default 8 s) — this
gate does not invent a new per-command timeout; §17 of the build task
requires justification for any deviation and none is warranted (every row
below is a `LOW`-cost single-shot read per §24).

---

**ID:** CP-A4
**Vendor:** Check Point
**Purpose:** Determine control-link (CCP) and sync-interface link health —
evidence category F, feeds checks 5a (control link) and 5b (sync link).
**Evidence categories:** F (link health).
**Exact command/API operation:** `cphaprob -a if`
**Action class:** `CLASS_0_READ`
**Execution plane:** device-direct SSH, existing per-member session.
**Shell/context:** Expert direct. Physical/VS0 context required; per-VS
optional (see VSX safety below).
**Target scope:** one physical member of the selected ClusterXL/VSX entity.
**Expected calls per member:** 1 (physical/VS0); +1 optional per VS if the
per-VS reading is later implemented (not part of this gate's required set).
**Concurrency:** within the existing per-member session; no new concurrency
introduced. Governed by the existing product-wide concurrency budget
(`CURRENT_STATE.md`: 1 per vendor pending its own real-environment evidence)
— this gate does not raise it.
**Timeout:** existing CP SSH command timeout (see above).
**Retry:** `NO_RETRY` — **PO override (2026-09-03)**: no new application-
level command retry is authorized for this read; a failure is
`COLLECTION_FAILED` on the first attempt. This supersedes the frozen
contract's general ≤1-bounded-retry allowance for this specific,
newly-approved row (§"Retry — PO override" below).
**Session reuse:** yes — same session as `A1`–`A3`.
**Version/platform:** no version gate found in the frozen contract's vendor-
semantics table; established for R81.10/R80.40-class ClusterXL.
**Read-only evidence:** yes — `cphaprob -a if` = R81.10 "Viewing Critical
Devices" / R80.40 "ClusterXL Monitoring Commands" (ESTABLISHED, frozen
contract §"Vendor semantics established").
**Sensitive output:** interface names, "Bond"/sync-interface identifiers.
**Safe retained fields:** per-interface up/down state token only (enum), no
interface name/IP retained beyond in-memory parse.
**Raw output persistence:** none — parsed in-module, discarded (raw-evidence
law).
**Failure semantics:** timeout/failure → `COLLECTION_FAILED` →
`INSUFFICIENT_EVIDENCE` for 5a/5b (never `KNOWN_BAD`).
**Unsupported semantics:** VS-context Bond reads may show `Down` regardless
of true state (sk93341) — treated as `UNKNOWN`, never `KNOWN_BAD`, if a
per-VS read is ever added (not in this gate's required scope).
**Real-env validation required:** yes (`S8`) — captured header shape unproven.
**Decision:** **APPROVED_FOR_S5**

---

**ID:** CP-A5
**Vendor:** Check Point
**Purpose:** Enumerate configured Critical Devices (pnotes) and their
problem/no-problem state — evidence category J, feeds check 8 (no member
failure state).
**Evidence categories:** J (failure/health state).
**Exact command/API operation:** `cphaprob -ia list`
**Action class:** `CLASS_0_READ`
**Execution plane:** device-direct SSH, existing per-member session.
**Shell/context:** Expert direct. Physical/VS0 context (this is the physical
battery; VSX global pnotes are registrable/unregistrable only from VS0 per
vendor docs — irrelevant here since this is read-only and this gate never
proposes register/unregister).
**Target scope:** one physical member.
**Expected calls per member:** 1.
**Concurrency:** as CP-A4.
**Timeout:** existing CP SSH command timeout.
**Retry:** `NO_RETRY` — **PO override (2026-09-03)**: no new application-
level command retry authorized; a failure is `COLLECTION_FAILED` on the
first attempt (§"Retry — PO override" below).
**Session reuse:** yes.
**Version/platform:** R81.10/R80.40-class ClusterXL.
**Read-only evidence:** yes — confirmed a **complete enumeration** of
configured critical devices, not problem-filtered, per two independent
official-source-adjacent citations (R80.40 "Reporting the State of a Critical
Device"; R81.20 CLI Ref "Viewing Critical Devices" — frozen contract, closed
2026-09-03). `cphaprob -l list` is explicitly **not** used (no official
differentiation from `-ia list` was ever found; only a superseded community
claim) — this gate reaffirms that rejection, unchanged.
**Sensitive output:** device names.
**Safe retained fields:** count of pnotes in `problem` state (integer),
boolean "any problem" derived flag. Device names are **not** retained
(safe-class only, per frozen contract §24 "device names (safe class)").
**Raw output persistence:** none.
**Failure semantics:** read failure → `UNKNOWN` for check 8 (never
`KNOWN_BAD` from absence).
**Unsupported semantics:** none beyond the general capability_gap boundary.
**Real-env validation required:** yes (`S8`).
**Decision:** **APPROVED_FOR_S5** — parser semantic already frozen (D-V6,
`OP.0b.0`): any pnote `problem` → check-8 `KNOWN_BAD` signal; none → healthy;
read failure → `UNKNOWN`. No register/unregister form is part of this gate
entry or ever will be (mutating, permanently out of scope for preflight).

---

**ID:** CP-A6
**Vendor:** Check Point
**Purpose:** Delta-sync / state-synchronization status — evidence category G,
feeds check 2 (state sync current).
**Evidence categories:** G (state/session synchronization).
**Exact command/API operation:** `cphaprob syncstat` (R80.20 and later) **or**
`fw ctl pstat` Sync section (before R80.20) — version selected from the
already-collected `show version all` (`A2`, existing); never both.
**Action class:** `CLASS_0_READ`
**Execution plane:** device-direct SSH, existing per-member session.
**Shell/context:** Expert direct.
**Target scope:** one physical member.
**Expected calls per member:** exactly 1 (one of the two forms, selected by
version — not an additional read on top of the primary form).
**Concurrency:** as CP-A4.
**Timeout:** existing CP SSH command timeout (`fw ctl pstat` is marked
`LOW–MOD` cost in the frozen contract; still within the existing bound, no
new timeout invented).
**Retry:** `NO_RETRY` — **PO override (2026-09-03)**: no new application-
level command retry authorized; a failure is `COLLECTION_FAILED` on the
first attempt. **Also binding:** the `syncstat`/`pstat` form selection is
a **version/capability dispatch decided from already-collected evidence**
(`A2`'s `show version all`) **before** the command is issued — it is never
a failure-driven fallback (i.e. `S5` must not attempt `syncstat`, observe
a failure, and then try `fw ctl pstat` as an alternate-command retry; that
would itself be an unauthorized command-level retry under a different
name).
**Session reuse:** yes.
**Version/platform:** version-conditional dispatch, explicitly documented:
`cphaprob syncstat` R80.20+ (R81.20 "Viewing Delta Synchronization"; sk34475);
`fw ctl pstat` legacy Sync section applies "until R80.10; for R80.20 and
higher refer to sk34475" (sk34476) — i.e. `fw ctl pstat`'s Sync section is
**not** authoritative on R80.20+ and must not be read there.
**Read-only evidence:** yes, both forms.
**Sensitive output:** none identity-bearing (drop/queue/timer counters).
**Safe retained fields:** sync-status enum + drop/queue counters (numeric).
**Raw output persistence:** none.
**Failure semantics:** failure → check 2 `INSUFFICIENT_EVIDENCE`.
**Unsupported semantics:** exact field vocabulary for `syncstat` is
`UNKNOWN` beyond "status/drops/queue/timers exist" — parser must fail closed
on any unrecognized status token (never infer healthy).
**Real-env validation required:** yes (`S8`) — vocabulary unconfirmed.
**Decision:** **APPROVED_FOR_S5**

---

**ID:** CP-A7
**Vendor:** Check Point
**Purpose:** Installed-policy identity for policy-parity comparison —
evidence category H (policy), feeds check 3 (parity).
**Evidence categories:** H (software/policy/content parity).
**Exact command/API operation:** `fw stat`
**Action class:** `CLASS_0_READ`
**Execution plane:** device-direct SSH, existing per-member session.
**Shell/context:** Expert direct.
**Target scope:** one physical member. **VSX scope note:** the frozen
minimum battery runs this in the physical/VS0 context only — a per-VS
installed-policy read is **not** in the frozen battery and is **not**
approved here; it would need its own future gate entry.
**Expected calls per member:** 1.
**Concurrency:** as CP-A4.
**Timeout:** existing CP SSH command timeout.
**Retry:** `NO_RETRY` — **PO override (2026-09-03)**: no new application-
level command retry authorized; a failure is `COLLECTION_FAILED` on the
first attempt (§"Retry — PO override" below).
**Session reuse:** yes.
**Version/platform:** R81 CLI ref `fw stat`; no version gate found.
**Read-only evidence:** yes.
**Sensitive output:** installed policy package name.
**Safe retained fields:** policy name/identifier as an opaque token used only
for equality comparison between members (never displayed raw beyond the
existing sanitized UI conventions already governing policy names elsewhere
in the product).
**Raw output persistence:** none.
**Failure semantics:** failure → check 3 `INSUFFICIENT_EVIDENCE` for the
policy sub-fact (software sub-fact from `A2` is unaffected).
**Unsupported semantics:** exact column layout `UNKNOWN` — parser targets the
policy-name field only, ignores everything else.
**Real-env validation required:** yes (`S8`).
**Decision:** **APPROVED_FOR_S5**

---

**ID:** CP-A8
**Vendor:** Check Point
**Purpose:** Failover count / last-event reason / last-event time — evidence
category K, feeds check 7 (flap history).
**Evidence categories:** K (transition/flap history).
**Exact command/API operation:** the **observation form only** —
`show cluster failover` (Clish, full-Gaia, confirmed R80.20 GA through R82)
as the primary form; `cphaprob show_failover` (Expert, Spark/Gaia Embedded
R81.10.15+) as the platform-specific fallback where the Clish form is
unavailable (`capability_gap`/appliance-class hosts). **Never** the reset
form `show cluster failover reset history` — explicitly excluded, see
§"Rejected mutating operations".
**Action class:** `CLASS_0_READ`
**Execution plane:** device-direct SSH, existing per-member session.
**Shell/context:** primary form via explicit `clish -c 'show cluster
failover'`; fallback form Expert direct (`cphaprob show_failover`).
**Target scope:** one physical member.
**Expected calls per member:** 1 (whichever form applies to that platform —
never both).
**Concurrency:** as CP-A4.
**Timeout:** existing CP SSH command timeout.
**Retry:** `NO_RETRY` — **PO override (2026-09-03)**: no new application-
level command retry authorized; a failure is `COLLECTION_FAILED` on the
first attempt. **Also binding:** the choice between `show cluster
failover` and `cphaprob show_failover` is a **platform/version dispatch
decided from already-collected evidence** before the command is issued —
never a failure-driven fallback (attempting the primary form, observing a
failure, and then trying the other form would itself be an unauthorized
command-level retry under a different name). Default invocation only —
no history-depth flag/option is authorized (unchanged from the technical
assessment above; the PO explicitly reaffirms it, §"Retry — PO override").
**Session reuse:** yes.
**Version/platform:** version/platform-conditional dispatch as stated above.
**Read-only evidence:** yes — both forms are read-only "viewing"/"monitoring"
commands per official pages (frozen contract, R80.20 GA/R80.30/R81/R82 +
sk137472); the reset form is a distinct, separately-documented mutating
command family and is not this row.
**Sensitive output:** none identity-bearing (counters, generic reason
tokens, relative/absolute timestamps).
**Safe retained fields:** failover count (integer), last-event reason as a
**known-safe enum only** (never a free-text pass-through — same discipline
`CP-4`'s `failure_reason` boolean already applies for `cphaprob stat`), last-
event time.
**Raw output persistence:** none. **History depth: minimum necessary only**
— this gate authorizes reading the command's own default output (whatever
bounded recent history it returns unprompted); it does **not** authorize any
flag or option that requests additional history beyond the default. If the
default output includes more than the most recent event (the frozen
contract's evidence column notes "last-20 history" as a possible shape), the
projection retains only the aggregate count and the single most recent
event — earlier entries are read (they cannot be excluded from the device's
own default response) but never parsed into retained fields or persisted.
**Failure semantics:** failure → check 7 `INSUFFICIENT_EVIDENCE`.
**Unsupported semantics:** exact CLI flag / history-depth syntax and full
Clish/Expert schema parity remain `UNKNOWN` (`D-V5a`, `PARTIALLY_CLOSED`) —
`S5` must implement against the default (flagless) invocation of each form
only; any flag beyond the default requires a **new** gate entry, not implied
by this one.
**Real-env validation required:** yes (`S8`).
**Decision:** **APPROVED_FOR_S5** — bounded to the flagless default
invocation of the two named forms; the mutating reset form remains
permanently rejected (see below); no numeric flap/failure threshold is
authorized here (`D-F3`, separate open product-owner decision — check 7
stays fail-closed, not silently `PASS`, until that number is set).

---

**ID:** CP-A9
**Vendor:** Check Point
**Purpose:** Authoritative, management-plane configured recovery/preemption
setting ("Maintain current active" vs. "Switch to higher priority Cluster
Member") — evidence category I, would feed check 6 (preemption known) as the
**authoritative** source (device-local `cphaprob state` Cluster Mode is
proven **not** authoritative for this, sk180184).
**Evidence categories:** I (election/preemption behavior).
**Exact command/API operation:** **none approved.** No machine-readable
attribute name for the cluster object's configured recovery-method setting
has been established by any source reached across four `OP.0b.0` sessions —
the Simple Cluster API is documented as **not** exposing every cluster-object
feature, and the official, actively-maintained
`CheckPointSW/CheckPointAnsibleMgmtCollection` `cp_mgmt_simple_cluster`
module documents no recovery/failback/preemption parameter at all (`D-V7b`,
`STILL_UNKNOWN`). This gate does **not** invent an attribute name on a
generic-object API (`show-generic-object`) whose internal names are not
individually documented as stable — the build task's own §8 explicitly
prohibits exactly this.
**Action class:** N/A — no command approved.
**Execution plane:** N/A.
**Shell/context:** N/A (would be MDS management-API, not a device SSH
session, if it existed).
**Target scope:** N/A.
**Expected calls per member:** 0 (not approved).
**Concurrency:** N/A.
**Timeout:** N/A.
**Retry:** N/A.
**Session reuse:** N/A.
**Version/platform:** N/A.
**Read-only evidence:** would be read-only if a safe surface existed; no
surface is approved.
**Sensitive output:** N/A.
**Safe retained fields:** N/A.
**Raw output persistence:** N/A.
**Failure semantics:** N/A — the fact stays `NOT_COLLECTED`/`UNKNOWN`
permanently until this row is revisited with a confirmed safe read.
**Unsupported semantics:** entire row is `UNKNOWN`.
**Real-env validation required:** N/A — nothing to validate.
**Decision:** **DEFERRED_UNKNOWN.** Check 6 (`preemption_known`) is already,
independently, `NOT_APPLICABLE`-adjacent/non-blocking in the frozen contract
("recorded, non-blocking") — this deferral does not newly block anything;
`configured_preemption` stays `UNKNOWN` on every unit until a later gate
package, with a confirmed safe management-plane attribute, revisits this row.
This remains bug-register `CP-3`, priority **P0 before CLASS 2** — unchanged,
and explicitly not required for this gate or for `S5`/`S6` to proceed.

---

**ID:** CP-B1
**Vendor:** Check Point
**Purpose:** Enumerate Virtual System IDs and per-VS status for the VSX
physical-cluster battery — evidence category B (VS enumeration), required to
scope which VS contexts the preflight even needs to consider.
**Evidence categories:** B (operational HA entity identity — VS
enumeration).
**Exact command/API operation:** `vsx stat -v`
**Action class:** `CLASS_0_READ`
**Execution plane:** device-direct SSH. **Distinct from today's usage:**
`checkpoint/vsx_runner.py` already issues this command, but over a **nested
interactive shell** during inventory collection. This gate approves issuing
the **same, already-approved, read-only command** over the preflight
collector's **own** direct, identity-gated SSH session — per `OP.0b.0`'s
"Current collector reuse decision": *"the preflight collector always
performs its own in-run reads"* rather than consuming a stored inventory
artifact. This is not a new command; it is the same evidence, on a session
already gated by this document's other CP rows.
**B1 session — PO clarification (2026-09-03):** the command invocation is
approved; **a new SSH transport session is NOT approved.** `S5` MUST issue
`vsx stat -v` over the **same, already-open, controlled preflight SSH
session** already established for that physical member by `A1`–`A8` — not
a second connection, even one using the same credential/host-key trust.
If reusing that session for this command proves technically impossible
during `S5` implementation, `S5` MUST **stop and return to the PO for
review** rather than opening a new session as a workaround.
**Shell/context:** Expert direct, physical (VS0) context.
**Target scope:** one physical member of the selected VSX cluster.
**Expected calls per member:** 1.
**Concurrency:** as CP-A4.
**Timeout:** existing CP SSH command timeout.
**Retry:** `NO_RETRY` — **PO override (2026-09-03)**: no new application-
level command retry authorized; a failure is `COLLECTION_FAILED` on the
first attempt (§"Retry — PO override" below).
**Session reuse:** yes — same session as the rest of the physical battery;
no new session, per the PO clarification above.
**Version/platform:** R81 CLI ref `vsx stat`; status may read `Unknown`
(sk178589) — parser must treat `Unknown` as `INSUFFICIENT_EVIDENCE`, never
inferred healthy or unhealthy.
**Read-only evidence:** yes.
**Sensitive output:** VS names.
**Safe retained fields:** VSID (numeric, already a validated identifier
elsewhere in the product) + status enum. VS names are presentation-only, not
retained as identity.
**Raw output persistence:** none.
**Failure semantics:** failure → VS enumeration `INSUFFICIENT_EVIDENCE`; the
physical-cluster battery still proceeds (VS enumeration failing does not
block physical-level checks).
**Unsupported semantics:** none beyond the general capability boundary.
**Real-env validation required:** yes (`S8`).
**Decision:** **APPROVED_FOR_S5** — required for VSX battery B; not a new
command, a new (already-gated) session for an already-approved read.

---

**ID:** CP-A10 (optional)
**Vendor:** Check Point
**Purpose:** Corroboration-only Cluster Mode string + `Active Attention`/
`Down` state, **never authoritative** for preemption (sk180184 — the mode
string does not reliably reflect the configured recovery setting).
**Evidence categories:** J; I (corroboration only, never authority).
**Exact command/API operation:** `cphaprob state`
**Action class:** `CLASS_0_READ`
**Execution plane / shell / session / timeout / retry:** identical to
CP-A4–A8 (same session, same existing timeout/retry).
**Target scope:** one physical member.
**Expected calls per member:** 1.
**Version/platform:** R81 CLI Ref "Viewing Cluster State"; R80.30 CLI Ref
"Monitoring Cluster State" — field detail beyond Cluster Mode/member state
`UNKNOWN`.
**Read-only evidence:** yes.
**Sensitive output:** none beyond state tokens.
**Safe retained fields:** mode string as corroboration flag only (never
overrides A9's authority, because A9 has no approved source — this field
must never be silently promoted to fill that gap); member state token.
**Raw output persistence:** none.
**Failure semantics:** failure → no corroboration available, not blocking.
**Real-env validation required:** yes (`S8`), optional row.
**Decision:** **OPTIONAL_APPROVED** — must be labeled non-authoritative for
preemption in any UI/telemetry surface that ever renders it, per sk180184.
This technical assessment is preserved unchanged. **PO authorization
(2026-09-03): NOT AUTHORIZED for the current `S5` implementation** —
redundant corroboration is not worth another read this slice; not part of
the PO-frozen implementation battery (§"Minimum battery — PO-frozen for
implementation"). Revisit in a future gate entry if a real need for
corroboration emerges.

---

**ID:** CP-A11 (optional)
**Vendor:** Check Point
**Purpose:** Standby-member licence/resource sub-fact for check 1 (viable
standby) — a minor corroborating fact, not load-bearing.
**Evidence categories:** 1 (viable-standby) sub-fact only.
**Exact command/API operation:** `cplic print`, `cpstat os`
**Action class:** `CLASS_0_READ`
**Execution plane / shell / session / timeout / retry:** identical to the
rest of the CP battery.
**Target scope:** one physical member.
**Expected calls per member:** 2 (one per command), optional.
**Version/platform:** draft point 9 (pre-existing candidate list); no version
gate found.
**Read-only evidence:** yes.
**Sensitive output:** **licence strings** (`cplic print`), host identity
(`cpstat os`).
**Safe retained fields:** scalars only — licence validity boolean, resource
percentages. **Raw licence strings and host identity fields are never
retained** — this is the frozen contract's own explicit "draft point 9"
constraint, restated here unchanged.
**Raw output persistence:** none.
**Failure semantics:** failure → sub-fact `INSUFFICIENT_EVIDENCE`, not
blocking check 1's primary evidence (`A3`'s local role).
**Real-env validation required:** yes (`S8`), optional row.
**Decision:** **OPTIONAL_APPROVED** — scalars-only extraction is a hard
condition of the approval, not a preference; a future implementation that
retains a raw licence string or hostname violates this gate, not merely
best practice. This technical assessment is preserved unchanged. **PO
authorization (2026-09-03): NOT AUTHORIZED for the current `S5`
implementation** — non-load-bearing and unnecessarily expands the
sensitive-output surface (licence strings, host identity fields); not
part of the PO-frozen implementation battery (§"Minimum battery —
PO-frozen for implementation").

## VSX safety summary (per candidate)

| ID | Physical VSX applicability | Per-VS applicability | VS0 restriction | Known caveat |
| --- | --- | --- | --- | --- |
| CP-A4 | yes, required | optional, not in required battery | none required for physical read | sk93341: Bond shows `Down` in any VS context — `UNKNOWN`, never `KNOWN_BAD`, if a per-VS read is later added |
| CP-A5 | yes, required | not in scope (pnotes read at physical/VS0 level) | register/unregister (not proposed) restricted to VS0 | none for the read form |
| CP-A6 | yes, required | not in frozen battery | none | version-conditional, not VSX-specific |
| CP-A7 | yes, required (physical policy only) | **not approved** — no per-VS policy read in this gate | none | a per-VS policy parity read would need its own future gate entry |
| CP-A8 | yes, required (physical only) | **not applicable** — `D-V5b` found failover statistics never load-bearing per-VS; dropped from the blocking list | none | none |
| CP-A9 | N/A — not approved at any scope | N/A | N/A | entire row deferred |
| CP-B1 | yes, required (enumerates VSIDs) | N/A (enumeration itself is physical-scope) | none | `Unknown` status is `INSUFFICIENT_EVIDENCE`, never inferred |
| CP-A10 | yes, optional | not evaluated here (existing S3 per-VS `cphaprob stat` capability already carries the sk165432 caveat, unchanged by this gate) | none | never authoritative for preemption at any scope |
| CP-A11 | yes, optional | not applicable | none | scalars-only, unchanged |

Per domain invariant 9 (frozen contract) a Check Point Virtual System is
never a `CLASS 2` execution target in this estate regardless of any row's
outcome — nothing in this gate changes that. Any per-VS read this gate does
approve (none beyond the already-existing S3 capability) follows the
already-frozen rule verbatim: *a contradictory/`Down` result in a non-VS0
context is `UNKNOWN` until real-env validation on this estate's version
proves the read reliable — never `KNOWN_BAD`, never a per-VS action input.*

## Rejected mutating operations (carried forward, unchanged)

None of these is reopened or reclassified by this gate — restated only so
the package is self-contained per the build task's own instruction to
"explicitly preserve rejection of known mutating variants."

| Command | Vendor | Why rejected |
| --- | --- | --- |
| `cphaprob -d <name> -t <sec> -s <state> [-p] register` / `cphaprob -d <name> [-p] unregister` | CP | mutating — registers/unregisters a Critical Device |
| `show cluster failover reset history` | CP | mutating — resets the failover counter/history |
| `fw ctl set int vsid <N>` | CP | mutating kernel-parameter set (existing `vsx_runner.py` defect, `CP-5`) — the read-only `vsenv` exec-channel primitive already works without it |
| `clusterXL_admin down/up` | CP | `CLASS 2` — operational state change, out of scope entirely |
| `cphaprob -l list` | CP | superseded by `-ia list`; no official differentiation ever established, only a since-superseded community claim |
| `request high-availability state suspend/functional`, `sync-to-remote` | PAN | `CLASS 2` / mutating |
| `entry/ha-state` as a runtime source (from `show devices all`) | PAN | not mutating, but rejected **as a runtime source**: a management-plane discovery cache must never short-circuit a preflight's own runtime read (`PAN-5`) |

## Per-command gate records — Palo Alto

All PAN rows below reuse the **same authenticated per-firewall API session**
already established for `P1`/`P2` (existing `keygen` + identity gate) — no
new credential path, no new key-management path, no TLS-policy weakening
(existing `pan_tls_ca`/verify configuration applies unchanged).
**Retry — superseded by PO override (2026-09-03):** same as the CP rows —
no new application-level command retry for any row this gate newly
approves; see §"Retry — PO override" below. Timeout is the existing
approved PAN direct-API
transport default — `SECURITYEXPERT_PAN_DIRECT_TIMEOUT` (default 20 s) — not
the heavier `SECURITYEXPERT_PAN_CONFIG_TIMEOUT` (90 s), because these are the
same lightweight HA-state-family reads as `P2`, not the bulk config fetch.
**Transport (`D-T1`: direct identity-gated API vs. Panorama proxy) remains an
open, non-blocking product-owner/security decision** — this gate does not
resolve it. Every row below must use **whichever transport `S6` selects for
`P2`** (no split transport within one preflight run); approval here does not
itself pick direct vs. proxied.

---

**ID:** PAN-P3
**Vendor:** Palo Alto
**Purpose:** Originally proposed to source `running-sync` (configuration
synchronization status, evidence category H). **Re-justified this session**
per the build task's own §10: `running-sync`/`running-sync-enabled` is
already `CLOSED_BY_DOCS` as sourced from **`P2`** (`show high-availability
state`, already `REQUIRED`, already issued) — not from `show
high-availability all`. `P2`'s own `local-info`/`peer-info` already carry
`ha1-ipaddr`/`ha1-macaddr`/`ha2-ipaddr`/`ha2-macaddr`/`ha1-port`/`ha2-port`
per the official PANW source read in `OP.0b.0` session 3. **No PAN preflight
fact currently in the frozen contract is known to require `show
high-availability all` exclusively.**
**Exact command/API operation:** `show high-availability all`
**Action class:** `CLASS_0_READ`
**Execution plane:** direct API (`D-T1`) or Panorama proxy — same as `P2`.
**Shell/context:** N/A (API, not shell).
**Target scope:** one firewall of the selected PAN HA pair.
**Expected calls per member:** 0 by default; 1 only if enabled as the
fallback below.
**Concurrency:** as `P2` — no new concurrency.
**Timeout:** existing PAN direct-API default (20 s).
**Retry:** contract-approved bounded retry (≤1), if issued at all.
**Session reuse:** yes.
**Version/platform:** CLI ref pages, KB "Out of Sync Peers – Configuration",
11.1 "Reference: HA Synchronization" — link-detail fields (IP/MAC/interface/
link state) established.
**Read-only evidence:** yes.
**Sensitive output:** interface IPs/MACs.
**Safe retained fields:** if ever enabled, the same enum-only treatment as
`P2`'s link fields — IPs/MACs are local-tokenized, never persisted raw.
**Raw output persistence:** none.
**Failure semantics:** failure → `INSUFFICIENT_EVIDENCE` for the specific
fact it was enabled to corroborate (never for `running-sync`, which `P2`
already sources).
**Unsupported semantics:** whether `running-sync` **also** appears inside the
`state` XML (making `all` fully redundant for it) vs. only in `all`'s own
response shape remains formally unconfirmed by an official source — this is
why `P2` is cited as the actual source per `D-V4`'s `CLOSED_BY_DOCS` finding,
not `all`; if a future real-env pass (`S8`) finds a PAN preflight fact that
truly needs `all` and nothing else supplies it, this row converts from
optional to required **as a documentation update to this gate**, not a
silent implementation change.
**Real-env validation required:** only if enabled.
**Decision:** **OPTIONAL_APPROVED** — defense-in-depth fallback only, not
part of the required `S6` battery; `S6` should not issue this command by
default. Smaller battery than the frozen candidate list, per the build
task's minimum-battery principle. This technical assessment is preserved
unchanged. **PO authorization (2026-09-03): NOT AUTHORIZED for the
current `S6` implementation** — the current required battery has no fact
that exclusively requires it; not part of the PO-frozen implementation
battery (§"Minimum battery — PO-frozen for implementation").

---

**ID:** PAN-P4
**Vendor:** Palo Alto
**Purpose:** Enumerate monitored HA path-monitoring groups/paths — evidence
category F/J, corroborates link-health and failure-state checks (5a/5b, 8).
**Evidence categories:** F (link health); J (failure/health state).
**Exact command/API operation:** `show high-availability path-monitoring`
**Action class:** `CLASS_0_READ`
**Execution plane:** direct API (`D-T1`) or Panorama proxy — same as `P2`.
**Shell/context:** N/A (API).
**Target scope:** one firewall of the selected PAN HA pair.
**Expected calls per member:** 1.
**Concurrency:** as `P2`.
**Timeout:** existing PAN direct-API default (20 s).
**Retry:** `NO_RETRY` — **PO override (2026-09-03)**: no new application-
level command retry authorized; a failure is `COLLECTION_FAILED` on the
first attempt (§"Retry — PO override" below).
**Session reuse:** yes.
**Version/platform:** 11.1 "HA Link and Path Monitoring" — concept and
show-command citation both present in the frozen contract without a
`PARTIAL`/"confirm" qualifier (contrast `P5` below).
**Read-only evidence:** yes.
**Sensitive output:** monitored destination IPs.
**Safe retained fields:** monitored-path count + up/down enum per path;
destination IPs are local-only, never persisted raw or surfaced beyond the
existing sanitized conventions.
**Raw output persistence:** none.
**Failure semantics:** failure → `INSUFFICIENT_EVIDENCE` for the path-
monitoring sub-fact (does not degrade `P2`'s own facts).
**Unsupported semantics:** exact response field vocabulary `UNKNOWN` beyond
"monitored paths exist, each has a state" — parser must fail closed on any
unrecognized state token.
**Real-env validation required:** yes (`S8`).
**Decision:** **APPROVED_FOR_S6**

---

**ID:** PAN-P5
**Vendor:** Palo Alto
**Purpose:** Would enumerate monitored HA link-monitoring groups/interfaces —
evidence category F/J, same shape as `P4` for interface-level monitoring
instead of path-level.
**Evidence categories:** F (link health); J (failure/health state).
**Exact command/API operation:** `show high-availability link-monitoring`
— **existence of this exact show-command syntax is itself only `PARTIAL`ly
confirmed** by the frozen contract's own §24 table (concept documented via
10.1 "HA Link and Path Monitoring"/11.1 "Configure HA Clustering"; the
specific show-command citation is marked "show-cmd PARTIAL"). No new official
source was reached this session to close that gap (see §"Status" above — the
vendor domains needed to confirm it remain unretried/`EGRESS_BLOCKED`).
**Action class:** N/A — no command approved yet.
**Execution plane:** N/A.
**Shell/context:** N/A.
**Target scope:** N/A.
**Expected calls per member:** 0
**Concurrency:** N/A.
**Timeout:** N/A.
**Retry:** N/A.
**Session reuse:** N/A.
**Version/platform:** unconfirmed.
**Read-only evidence:** the general HA link/path monitoring family is
documented as observation-only in the source pages cited for `P4`; this
specific command's own existence/output shape is not confirmed to the same
standard.
**Sensitive output / safe retained fields / raw output persistence:** not
evaluable without a confirmed response shape.
**Failure semantics:** N/A.
**Unsupported semantics:** entire row.
**Real-env validation required:** N/A until the command itself is confirmed.
**Decision:** **DEFERRED_UNKNOWN.** Per this build's own §28 stop condition
("a command is only justified by unresolved vendor speculation") this row
does not clear the gate on the evidence available in this session. Closure
path: the same GitHub-mirror-first / human-fetch technique that already
closed `D-V4` and `D-V7a` in `OP.0b.0`, targeted specifically at confirming
`show high-availability link-monitoring`'s exact syntax and response shape —
not a blind repeat of "try an unblocked network." `S6` may ship without this
row; interface-level link monitoring simply stays `NOT_COLLECTED` until a
future gate entry closes it.

## Command → fact matrix

| Command ID | Vendor | Fact categories | Required/Optional | Context | Gate decision (technical) | PO authorization (2026-09-03) |
| --- | --- | --- | --- | --- | --- | --- |
| CP-A4 `cphaprob -a if` | CP | F | Required | Expert, physical/VS0 | APPROVED_FOR_S5 | authorized, `NO_RETRY` |
| CP-A5 `cphaprob -ia list` | CP | J | Required | Expert, physical/VS0 | APPROVED_FOR_S5 | authorized, `NO_RETRY` |
| CP-A6 `cphaprob syncstat` / `fw ctl pstat` | CP | G | Required | Expert, version-dispatched | APPROVED_FOR_S5 | authorized, `NO_RETRY`, no failure-driven dispatch |
| CP-A7 `fw stat` | CP | H (policy) | Required | Expert, physical only | APPROVED_FOR_S5 | authorized, `NO_RETRY` |
| CP-A8 `show cluster failover` / `cphaprob show_failover` | CP | K | Required | Clish (`clish -c`) / Expert, platform-dispatched | APPROVED_FOR_S5 | authorized, `NO_RETRY`, no failure-driven dispatch |
| CP-A9 management-plane recovery attribute | CP | I | — | MDS (undetermined) | DEFERRED_UNKNOWN | not authorized |
| CP-B1 `vsx stat -v` | CP | B (VS enum) | Required (VSX battery) | Expert, physical | APPROVED_FOR_S5 | authorized, `NO_RETRY`, existing session only (no new SSH session) |
| CP-A10 `cphaprob state` | CP | I (corroboration)/J | Optional | Expert, physical/VS0 | OPTIONAL_APPROVED | **NOT AUTHORIZED for current S5** |
| CP-A11 `cplic print`/`cpstat os` | CP | 1 (sub-fact) | Optional | Expert, physical | OPTIONAL_APPROVED | **NOT AUTHORIZED for current S5** |
| PAN-P3 `show high-availability all` | PAN | H/F | Optional | API, `D-T1` | OPTIONAL_APPROVED | **NOT AUTHORIZED for current S6** |
| PAN-P4 `show high-availability path-monitoring` | PAN | F/J | Required | API, `D-T1` | APPROVED_FOR_S6 | authorized, `NO_RETRY` |
| PAN-P5 `show high-availability link-monitoring` | PAN | F/J | — | API, `D-T1` (unconfirmed cmd) | DEFERRED_UNKNOWN | not authorized |

| Required preflight fact | Source command | If unavailable |
| --- | --- | --- |
| CP control/CCP link health (5a) | CP-A4 | `INSUFFICIENT_EVIDENCE` |
| CP sync-interface link health (5b) | CP-A4 | `INSUFFICIENT_EVIDENCE` |
| CP critical-device problem state (check 8) | CP-A5 | `UNKNOWN` |
| CP state sync (check 2) | CP-A6 | `INSUFFICIENT_EVIDENCE` |
| CP policy parity (check 3, policy half) | CP-A7 | `INSUFFICIENT_EVIDENCE` |
| CP flap/failover history (check 7) | CP-A8 | `INSUFFICIENT_EVIDENCE`; PASS unreachable until `D-F3` sets a threshold regardless |
| CP configured preemption (check 6) | **none approved** | `UNKNOWN` — permanently, until `CP-A9` is revisited; non-blocking (check 6 already `NOT_APPLICABLE`-adjacent) |
| CP VS enumeration (VSX battery) | CP-B1 | `INSUFFICIENT_EVIDENCE` for VS-scoped facts; physical-level checks unaffected |
| PAN config/running sync (check 3, config half) | `P2` (existing) — `PAN-P3` not part of the PO-authorized `S6` battery | `INSUFFICIENT_EVIDENCE` |
| PAN path-monitoring health (5a/8 corroboration) | PAN-P4 | `INSUFFICIENT_EVIDENCE` |
| PAN link-monitoring health (5a/8 corroboration) | **none approved** | `NOT_COLLECTED`/`UNKNOWN` until `PAN-P5` is revisited |

## Target / fan-out

Unchanged from the frozen contract, restated as a hard boundary this gate
does not loosen: a future preflight operates on **one explicitly selected HA
entity** — CP: the selected ClusterXL/VSX operational entity, its bounded
physical members, subordinate VS contexts only where a specific check
requires one; PAN: the selected HA pair, bounded to its two members. This
gate does **not** authorize `--only all`, implicit fleet enumeration, or
first-N selection for any row above. Existing deterministic selectors remain
the safety boundary; nothing here adds a new selector.

## Concurrency

No new concurrency is invented. Per-HA-entity collection stays bounded to the
product's existing concurrency budget (`CURRENT_STATE.md`: 1 per vendor,
pending its own real-environment evidence) — this gate neither raises nor
lowers it. Member skew tolerance (`D-F2`) remains an open, non-blocking
product-owner/vendor-guidance decision; this gate does not choose a number.

## Retry — PO override (2026-09-03)

**Superseded.** The technical assessment above (each per-command record)
originally carried forward the frozen `OP.0b.0` contract's general "≤1
bounded retry per read" allowance. The product owner explicitly withheld
authorization for a **new application-level command retry** on every row
this gate approves. Binding, effective immediately:

- **`NO_RETRY`** for every `APPROVED_FOR_S5`/`APPROVED_FOR_S6` row
  (`CP-A4`, `A5`, `A6`, `A7`, `A8`, `B1`; `PAN-P4`): a command failure is
  `COLLECTION_FAILED` on the **first** attempt — `S5`/`S6` must not
  automatically re-issue the same command.
- **No failure-driven alternate-command fallback.** Where a row already
  names two candidate forms selected by version/platform (`CP-A6`:
  `syncstat` vs. `pstat`; `CP-A8`: `show cluster failover` vs.
  `cphaprob show_failover`), the selection is made **before execution**
  from already-collected version/capability evidence (`A2`'s `show
  version all`) — never by attempting one form, observing a failure, and
  then trying the other. That pattern is an unauthorized command-level
  retry under a different name and is explicitly prohibited.
- **Existing transport/session-level retry is unaffected only where all
  three hold:** it already exists (this gate creates none), it produces
  no additional command invocation (e.g. a TCP-level reconnect is not a
  second `cphaprob` invocation), and it preserves provenance. Nothing in
  this override changes transport-level behavior — it constrains only
  application/command-level retry.
- This override supersedes the frozen `OP.0b.0` contract's general retry
  allowance **for the rows this gate approves only**; it does not
  reopen or reinterpret that contract itself.
- No row introduces a retry that could let two attempts' evidence
  masquerade as one coherent snapshot without provenance — moot for the
  approved rows given `NO_RETRY`, but binding on any future gate entry
  that does authorize a retry: `S5`/`S6` must attach the same
  `preflight_run_id`/`collected_at` discipline `S1`'s provenance model
  already defines.

## Timeout

Every row above uses the existing approved transport timeout —
`SECURITYEXPERT_CP_CONFIG_SSH_COMMAND_TIMEOUT_SECONDS` (CP, default 20 s) /
`SECURITYEXPERT_PAN_DIRECT_TIMEOUT` (PAN, default 20 s) — with no
command-specific override invented. A timeout produces
`COLLECTION_FAILED`/`UNKNOWN`, never a known-bad device-state inference, per
the frozen contract's fail-closed table.

## Output / privacy

Raw command/API responses for every approved row: memory only → parse →
safe derived evidence (enum/counter/token) → discard, per `AGENTS.md`
raw-evidence law and the frozen contract's privacy invariants. No row above
persists a raw serial, IP, hostname, raw configuration, credential, or raw
command output. Licence strings (`CP-A11`) and interface/destination IPs
(`CP-A4`, `PAN-P3`, `PAN-P4`) are explicitly scalars/enums/local-tokens only,
never raw. Future `S5`/`S6` outputs feed `PreflightFact` + `Provenance`
(`S1`'s existing model) — this gate does not define a new output shape.

## Minimum battery — PO-frozen for implementation (2026-09-03)

This section is the authoritative implementation battery — it supersedes
the "technical minimum battery" framing below only insofar as `A10`,
`A11` and `P3` are concerned: those remain `OPTIONAL_APPROVED` as a
**technical** assessment (unchanged) but are **not** part of what `S5`/`S6`
may actually implement in this slice.

**Check Point** (physical/VS0, per member): `A1`–`A3` (existing,
unaffected) + `A4`, `A5`, `A6`, `A7`, `A8` (PO-authorized) = 8 required
reads. `B1` (`vsx stat -v`) additionally required **only for the VSX
battery**, reusing the existing per-member session (no new SSH session —
§"B1 session — PO clarification"). `A9` not authorized (`DEFERRED_UNKNOWN`,
unchanged). `A10`/`A11` are **excluded from the implementation battery** —
PO-withheld, not part of `S5`.

**Palo Alto** (per firewall): `P1`, `P2` (existing, unaffected) + `P4`
(PO-authorized) = 3 required reads. `P3` is **excluded from the
implementation battery** — PO-withheld, not part of `S6`, despite being
technically `OPTIONAL_APPROVED` (the condition that would have promoted it
to required does not hold — `running-sync` is already `CLOSED_BY_DOCS` as
sourced from `P2`). `P5` not authorized (`DEFERRED_UNKNOWN`, unchanged).

---

**Technical minimum battery (historical framing, preserved for context —
see the PO-frozen battery above for what `S5`/`S6` may actually
implement):**

**Check Point required battery** (physical/VS0, per member): `A1`–`A3`
(existing, unaffected) + `A4`, `A5`, `A6`, `A7`, `A8` (this gate,
`APPROVED_FOR_S5`) = 8 required reads. `A9` proposed but **not** approved (0
of the frozen candidate's `REQ*` claim actually enters the battery for
preemption). VSX physical battery adds `CP-B1` (required). Optional:
`A10`, `A11`. This is **equal to** the frozen candidate battery's required-row
count minus the one row this gate could not clear (`A9`) — smaller, not
larger, per the build task's minimum-battery principle.

**PAN required battery** (per firewall): `P1`, `P2` (existing, unaffected) +
`P4` (this gate, `APPROVED_FOR_S6`) = 3 required reads. `P3` downgraded from
the frozen contract's own "OPT→REQ if `running-sync` absent from `P2`" to
plain `OPTIONAL_APPROVED` (the condition that would have promoted it does not
hold — `running-sync` is already `CLOSED_BY_DOCS` as sourced from `P2`). `P5`
proposed but **not** approved. This is **smaller** than the frozen candidate
battery (2 of 3 new candidates enter; the third stays optional; a fourth
theoretical `PAN-P5` promotion never happens in this gate).

## S5 / S6 implementation boundary

Unchanged from the frozen contract's own slice table: **S5** = Check Point
dedicated preflight collector (`checkpoint/preflight_collector.py`, not yet
created); **S6** = Palo Alto dedicated preflight collector
(`panorama/preflight_collector.py`, not yet created). Each:

- creates/uses exactly one `preflight_run_id` per invocation,
- performs only the bounded, in-run reads this gate approves (no additional
  command without a new gate entry),
- reuses the already-approved transport/session/identity-gate primitives —
  no new credential path, no new SSH/API session shape,
- parses through the existing `S1` fact/provenance model and the existing
  `S2`/`S3` extraction/projection seams (`_parse_pan_ha_preflight_fields`,
  `project_pan_preflight_facts`, `_parse_clusterxl_stat_preflight_fields`,
  `project_cp_preflight_facts`) — those are dormant capabilities already
  proven correct against synthetic fixtures (`OP.0b.0` §25a/§25b); wiring
  them into a real in-run read is exactly `S5`/`S6`'s job, not this gate's,
- performs **no readiness verdict** — `S7` remains the sole readiness engine.
- issues **no application-level command retry** on any newly-approved read
  (§"Retry — PO override"), and opens **no new SSH transport session** for
  `B1` — it reuses the session `A1`–`A8` already established for that
  physical member (§"B1 session — PO clarification"; if impossible, `S5`
  stops and returns to the PO).
- implements only the **PO-frozen implementation battery**
  (§"Minimum battery — PO-frozen for implementation"): `A10`, `A11` and
  `P3` are out of scope for this slice even though technically
  `OPTIONAL_APPROVED`.

The PO approval is recorded in §"Approval record" below. `S5`/`S6` may
begin implementation strictly within the rows, retry constraint, and
session constraint recorded there — nothing broader.

## PO approval package

These tables record the original technical recommendation (`Retry` column
as first proposed). The PO approval in §"Approval record" below overrides
`Retry` to `NO_RETRY` on every row it authorizes, and withholds
authorization for `A10`, `A11` and `P3` entirely — see the "PO decision"
column added to each table.

### Check Point

| ID | Command | Context | Purpose | Evidence produced | Calls/member | Retry (original recommendation) | Timeout | Sensitive output | Retained output | Decision recommendation | Why safe | Known caveat | PO decision (2026-09-03) |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| CP-A4 | `cphaprob -a if` | Expert, physical/VS0 | link health | F | 1 | ≤1 bounded | existing 20 s | interface names, sync IPs | up/down enum only | **APPROVED_FOR_S5** | read-only "viewing" command, vendor-established | sk93341 (VS-context Bond) not exercised — physical-only in this gate | **AUTHORIZED**, retry overridden to `NO_RETRY` |
| CP-A5 | `cphaprob -ia list` | Expert, physical/VS0 | pnote problem state | J | 1 | ≤1 bounded | existing 20 s | device names | problem count + boolean | **APPROVED_FOR_S5** | confirmed complete enumeration; register/unregister forms permanently excluded | none | **AUTHORIZED**, retry overridden to `NO_RETRY` |
| CP-A6 | `cphaprob syncstat` / `fw ctl pstat` | Expert, version-dispatched | delta sync | G | 1 | ≤1 bounded | existing 20 s | none identity-bearing | sync enum + counters | **APPROVED_FOR_S5** | version-gated, both forms vendor-established | exact status vocabulary unconfirmed — fail closed on unrecognized token | **AUTHORIZED**, retry overridden to `NO_RETRY`, dispatch must be evidence-based not failure-driven |
| CP-A7 | `fw stat` | Expert, physical only | installed policy | H | 1 | ≤1 bounded | existing 20 s | policy name | opaque comparison token | **APPROVED_FOR_S5** | read-only, vendor-established | no per-VS policy read approved | **AUTHORIZED**, retry overridden to `NO_RETRY` |
| CP-A8 | `show cluster failover` / `cphaprob show_failover` | Clish/Expert, platform-dispatched | flap/failover history | K | 1 | ≤1 bounded | existing 20 s | none identity-bearing | count/reason-enum/time | **APPROVED_FOR_S5** | observation form only, reset form excluded; default invocation only | exact flag/history-depth syntax UNKNOWN; no numeric flap threshold set (`D-F3`) | **AUTHORIZED**, retry overridden to `NO_RETRY`, dispatch must be evidence-based not failure-driven, no history-depth option |
| CP-A9 | *(none)* | *(none)* | configured recovery/preemption | I | 0 | — | — | — | — | **DEFERRED_UNKNOWN** | no safe machine-readable source found across 4 sessions | `D-V7b`; `CP-3`, P0 before CLASS 2 | **NOT AUTHORIZED**, unchanged |
| CP-B1 | `vsx stat -v` | Expert, physical | VS enumeration | B | 1 | ≤1 bounded | existing 20 s | VS names | VSID + status enum | **APPROVED_FOR_S5** | already-approved read, new (already-gated) session | `Unknown` status → INSUFFICIENT_EVIDENCE | **AUTHORIZED**, retry overridden to `NO_RETRY`; command invocation approved, a NEW SSH TRANSPORT SESSION is NOT approved — reuse the existing per-member session |
| CP-A10 | `cphaprob state` | Expert, physical/VS0 | corroboration only | I(corrob.)/J | 1 | ≤1 bounded | existing 20 s | none | mode string + state token, non-authoritative | **OPTIONAL_APPROVED** | corroboration, never sole authority | sk180184 — must never back preemption verdicts | **NOT AUTHORIZED for current S5** — redundant corroboration not worth another read now; technical assessment preserved |
| CP-A11 | `cplic print` / `cpstat os` | Expert, physical | licence/resource sub-fact | 1(sub-fact) | 2 | ≤1 bounded | existing 20 s | licence strings, host identity | scalars only | **OPTIONAL_APPROVED** | scalars-only is a hard condition | non-load-bearing sub-fact | **NOT AUTHORIZED for current S5** — non-load-bearing, unnecessarily expands sensitive-output surface; technical assessment preserved |

### Palo Alto

| ID | Command | Context | Purpose | Evidence produced | Calls/member | Retry (original recommendation) | Timeout | Sensitive output | Retained output | Decision recommendation | Why safe | Known caveat | PO decision (2026-09-03) |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| PAN-P3 | `show high-availability all` | API, `D-T1` | link detail fallback | H/F | 0–1 | ≤1 bounded | existing 20 s | interface IPs/MACs | local tokens only | **OPTIONAL_APPROVED** | `running-sync` already sourced from `P2`; no fact currently needs `all` exclusively | not part of required `S6` battery | **NOT AUTHORIZED for current S6** — current required battery has no fact that exclusively requires it; technical assessment preserved |
| PAN-P4 | `show high-availability path-monitoring` | API, `D-T1` | monitored-path health | F/J | 1 | ≤1 bounded | existing 20 s | destination IPs | count + up/down enum | **APPROVED_FOR_S6** | vendor-established without a "confirm" qualifier | field vocabulary beyond up/down UNKNOWN | **AUTHORIZED**, retry overridden to `NO_RETRY` |
| PAN-P5 | `show high-availability link-monitoring` | API, `D-T1` (unconfirmed) | monitored-link health | F/J | 0 | — | — | — | — | **DEFERRED_UNKNOWN** | exact command syntax only `PARTIAL`ly confirmed by frozen contract | needs GitHub-mirror/human-fetch confirmation before approval | **NOT AUTHORIZED**, unchanged |

**TOTAL NEW CP READ OPERATIONS — PO-authorized:** 5 required (`A4`, `A5`,
`A6`, `A7`, `A8`) = **5 rows**, plus `CP-B1` (already-approved command,
authorized in the existing per-member session — not counted as "new").
`A9` = 0 authorized. `A10`/`A11` = 0 authorized (technically
`OPTIONAL_APPROVED`, PO withheld — 2 rows excluded from the implementation
battery).

**TOTAL NEW PAN READ OPERATIONS — PO-authorized:** 1 required (`P4`) = **1
row**. `P5` = 0 authorized. `P3` = 0 authorized (technically
`OPTIONAL_APPROVED`, PO withheld — 1 row excluded from the implementation
battery).

**REJECTED MUTATING OPERATIONS:** 7 — `cphaprob -d ... register/unregister`,
`show cluster failover reset history`, `fw ctl set int vsid <N>`,
`clusterXL_admin down/up`, `cphaprob -l list` (superseded, not mutating but
excluded), PAN `request high-availability state suspend/functional`, PAN
`sync-to-remote`. All carried forward unchanged from `OP.0b.0`'s own §24
table — none reopened.

**DEFERRED / UNKNOWN:** `CP-A9` (management-plane recovery attribute —
`D-V7b`), `PAN-P5` (`show high-availability link-monitoring` exact syntax).
Neither blocks `S5`/`S6` from proceeding on every other authorized row.

**NOT AUTHORIZED FOR CURRENT S5/S6 (technically safe, PO-withheld):**
`CP-A10`, `CP-A11`, `PAN-P3`. Historical technical assessment preserved
verbatim in their per-command records and in the tables above; none is
part of the PO-frozen implementation battery.

**MINIMUM CP BATTERY (PO-authorized for implementation):** `A1`–`A3`
(existing) + `A4`, `A5`, `A6`, `A7`, `A8` (new, required) = 8 required
reads/member; `B1` (VSX enumeration) additionally required **only** for
the VSX battery, in the existing per-member session. `A10`/`A11` excluded.

**MINIMUM PAN BATTERY (PO-authorized for implementation):** `P1`, `P2`
(existing) + `P4` (new, required) = 3 required reads/member. `P3` excluded.

**NETWORK BEHAVIOR IF APPROVED — exact maximum required calls per selected
HA entity (optionals removed from the bound; no command-level retry, per
the PO retry override — so no retry ceiling multiplier):**

- **CP VSX (2 physical members):** 9 required reads/member (`A1`–`A3`
  existing + `A4`–`A8` new + `B1`) × 2 = **18 required device commands
  maximum**, all within the 2 existing per-member preflight SSH sessions —
  no new session, no command-level retry. Per-VS reads (existing `S3`
  capability, unchanged by this gate) are additional and scoped
  separately, not counted here.
- **CP ClusterXL, non-VSX (2 physical members):** 8 required reads/member
  (`A1`–`A3` existing + `A4`–`A8` new; `B1` is VSX-only and does not apply)
  × 2 = **16 required device commands maximum** — lower than the VSX
  maximum because `B1` does not apply.
- **PAN A/P pair (2 members):** 3 required API operations/member (`P1`,
  `P2`, `P4`) × 2 = **6 required API calls maximum**, all within the 2
  existing per-firewall preflight API sessions — no new session, no
  command-level retry.

No fleet-wide, first-N, or `--only all` behavior is authorized at any point
in this network-behavior bound.

## Approval record

**PO approval: YES (2026-09-03).**

- **PO approval date:** 2026-09-03
- **Approved by:** Product owner (session record — see this build's
  `CURRENT_STATE.md`/`project/build_history.json` closure entry for the
  exact session reference)
- **Exact rows approved** (does **not** match the "Decision recommendation"
  column verbatim — the PO narrowed it; overrides recorded here and
  reconciled into every table above):
  - **CP `APPROVED_FOR_S5`, authorized for implementation:** `A4`, `A5`,
    `A6`, `A7`, `A8`, `B1` (VSX only) — all with `NO_RETRY` (no new
    application-level command retry) and, for `A6`/`A8`, evidence-based
    (not failure-driven) form dispatch. `B1` additionally: no new SSH
    transport session — reuse the existing per-member session, or stop
    and return to PO review in `S5` if that proves impossible.
  - **CP not authorized for the current `S5` slice (technical assessment
    preserved, PO withholds implementation authorization):** `A10`
    (redundant corroboration not worth another read now), `A11`
    (non-load-bearing, unnecessarily expands sensitive-output surface).
  - **CP not authorized (unchanged):** `A9` — `DEFERRED_UNKNOWN`, `D-V7b`/
    `CP-3`, CLASS-2 blocker.
  - **PAN `APPROVED_FOR_S6`, authorized for implementation:** `P4` — with
    `NO_RETRY`.
  - **PAN not authorized for the current `S6` slice (technical assessment
    preserved, PO withholds implementation authorization):** `P3` — current
    required battery has no fact that exclusively requires it.
  - **PAN not authorized (unchanged):** `P5` — `DEFERRED_UNKNOWN`, exact
    syntax insufficiently authoritative.
  - **Retry override (binding on every row above):** no new
    application-level command retry authorized — see §"Retry — PO
    override".
  - **Network bound (binding, optionals excluded):** CP VSX ≤18 required
    invocations/pair; CP non-VSX ClusterXL ≤16 required invocations/pair;
    PAN ≤6 required invocations/pair — all within existing sessions, no
    fleet-wide/first-N/`--only all` behavior.
  - **This approval authorizes only future implementation of the rows
    above.** It does **not** authorize: device contact in `S4` itself,
    `CLASS 2`, `A9`, `P5`, PAN serial normalization, PAN pair-identity
    redesign, new credentials, new transport, new retries beyond this
    override, new concurrency policy, or fleet-wide execution.

## Rollback

Documentation only; nothing to roll back. No device was contacted, no code
changed, no schema changed. If superseded, mark this file's status and add
the superseding path; never delete.
