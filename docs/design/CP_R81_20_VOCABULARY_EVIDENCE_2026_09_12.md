# Check Point R81.20 Vocabulary Evidence — D-V5a, D-V6, D-V7b

Status: **DRAFT** (evidence record, not a contract — decisions are the
Product Owner's per `AGENTS.md` "Authority hierarchy" #2 and #6; nothing
here authorizes a new command or predicate).

Scope: closes evidence gathering for three open `project/QUEUE.md` items —
`D-V5a`, `D-V6`, `D-V7b` — against **official Check Point R81.20**
documentation only. No SK article was fetched (per task instruction: SK
pages are JavaScript-gated and returned empty bodies in an earlier session
today); all evidence below is from the public R81.20 ClusterXL
Administration Guide, which is HTML and fetched successfully with `curl`.

Code inspected before lookup: `utils/failover/preflight_readiness.py`,
`utils/failover/assessment.py` (read only — this build changes neither).

## Fetch log (method, byte counts)

All fetches used `curl -sS -o <file> -w "%{http_code} %{size_download}"`
against `sc1.checkpoint.com`, then the HTML was tag-stripped and
whitespace-collapsed with a local Python one-liner (`re.sub('<[^>]+>', ' ',
...)`) and read directly — no summarizer was used to interpret the page.

| URL | HTTP | Bytes |
|---|---|---|
| `.../CP_R81.20_ClusterXL_AdminGuide/Content/Topics-CXLG/ClusterXL-Monitoring-Commands.htm` | 200 | 90387 |
| `.../CP_R81.20_ClusterXL_AdminGuide/Content/Topics-CXLG/Viewing-Cluster-State.htm` | 200 | 101554 |
| `.../CP_R81.20_ClusterXL_AdminGuide/Content/Topics-CXLG/Viewing-Critical-Devices.htm` | 200 | 117554 |
| `.../CP_R81.20_ClusterXL_AdminGuide/Content/Topics-CXLG/clusterXL_admin.htm` | 200 | 42922 |
| `.../CP_R81.20_ClusterXL_AdminGuide/Content/Topics-CXLG/ClusterXL-Configuration-Commands.htm` | 200 | 89524 |
| `.../CP_R81.20_ClusterXL_AdminGuide/Content/Topics-CXLG/Introduction-to-ClusterXL.htm` | 200 | 57885 |
| `.../CP_R81.20_Gaia_AdminGuide/Content/Topics-GAG/HA-Full-High-Availability-in-Gaia-OS.htm` (older-pattern guess, superseded by the guide actually needed) | 404 | 10 |

Confirms the task's warning: a 404/empty-page attempt returns exactly 10
bytes on this host and must not be read as "topic absent" — the ClusterXL
guide (a *separate* guide from the Gaia Administration Guide) was the
correct target for D-V5a/D-V6, found by search, not guessed from URL
pattern.

Guide identity confirmed on-page: "R81.20 ClusterXL Administration Guide",
dated 23 December 2025 / 13 January 2026 footer stamps (current as fetched
2026-09-12).

---

## D-V5a — ClusterXL failover-statistics command syntax (history-depth option)

**Settled by documentation. Exhaustive** (this is the full command-table
row, not an example).

Source: `ClusterXL-Monitoring-Commands.htm`, table "ClusterXL Monitoring
Commands", row "Show (and reset) cluster failover statistics on the Cluster
Member":

> "show cluster failover [reset {count | history}]" (Gaia Clish)
> "cphaprob [-reset {-c | -h}] [-l < count >] show_failover" (Expert Mode)

The history-depth option is `-l <count>` on `show_failover`; `-reset -h`
resets history, `-reset -c` resets the failover counter. Clish exposes the
same two resets as `reset {count | history}` but the guide's Clish syntax
box does **not** show a Clish equivalent of `-l <count>` (history depth) —
that depth control appears Expert-only.

**Code-vs-docs**: no current code reads `show_failover` history depth —
`utils/failover/preflight_readiness.py` maps `flap_history` to
`cp_failover_count` (a single counter, from `cphaprob stat`'s "Cluster
failover count" field per `Viewing-Cluster-State.htm`), and
`assessment.py`'s `_A9_KNOWN_UNSUPPORTED`/collector-name table documents
`"cphaprob stat uptime / cluster event log (OP.0b)"` for `flap_history`, not
`show_failover -l <count>`. No mismatch — the code has simply not yet
adopted the deeper history command; that is a scope gap, not a
documentation contradiction. D-V5a's flag syntax question is closed;
whether/when to consume `-l <count>` is a Product Owner scope decision, not
answered here.

## D-V6 — `cphaprob -l` / `-i` / `-ia` differentiation, and `cphaprob state` field set

**Settled by documentation. Exhaustive** for both parts.

### `-l` / `-i` / `-ia` / `-e`

Source: `Viewing-Critical-Devices.htm`, "Syntax" table, "Where" rows (exact
quotes):

> "cphaprob -l — Shows the list of all Critical Devices"
> "cphaprob -i list — When there are no issues on the Cluster Member, shows: There are no pnotes in problem state. When a Critical Device reports a problem, prints only the Critical Device that reports its state as "problem""
> "cphaprob -ia list — When there are no issues on the Cluster Member, shows: There are no pnotes in problem state. When a Critical Device reports a problem, prints the Critical Device "Problem Notification" and the Critical Device that reports its state as "problem""
> "cphaprob -e list — ... prints only the Critical Device that reports its state as "problem""

So: `-l` = full inventory (all devices, OK and problem); `-i` = only the
problem device; `-ia` = the problem device **plus** the roll-up "Problem
Notification" pnote; `-e` behaves like `-i` in the guide's own worded
description (both print only the problem device) — the guide does not
state a further distinction between `-i` and `-e` beyond that repeated
sentence, so any assumption that `-e` differs from `-i` in output shape
is **not established** by this page and should stay `UNKNOWN` if load-bearing.
Note also an internal inconsistency in the same guide: the syntax box on
this page prints `cphaprob [-l] [-ia] [-e] list`, while the summary command
list on `ClusterXL-Monitoring-Commands.htm` prints `cphaprob [-l] [-i[a]]
[-e] list` — the second form documents `-i` as valid standalone (matching
the worked description above); this delta is in Check Point's own
documentation, not introduced by us, and is worth flagging rather than
silently picking one.

**Code-vs-docs**: `assessment.py` line 155/`preflight_readiness.py`
`missing_evidence="A3 cphaprob stat + A5 cphaprob -ia list on both
members..."` uses `-ia`, matching the documented "problem device + Problem
Notification" roll-up shape — consistent with docs. No mismatch found.

### `cphaprob state` exact field set

Source: `Viewing-Cluster-State.htm`, "Description of the 'cphaprob state'
command output fields" table and the following "Description of the cluster
states" table (exact quotes, condensed):

Top-level fields: `Cluster Mode`, `ID`, `Unique Address`, `Assigned Load`,
`State`, `Name`, `Active PNOTEs`, `Last member state change event` (with
sub-fields `Event Code`, `State change`, `Reason for state change`, `Event
time`), `Last cluster failover event` (`Transition to new ACTIVE`, `Reason`,
`Event time`), `Cluster failover count` (`Failover counter`, `Time of
counter reset`).

Exhaustive `State` value list per "Table: Description of the cluster
states": `ACTIVE`, `ACTIVE(!)` / `ACTIVE(!F)` / `ACTIVE(!P)` / `ACTIVE(!FP)`,
`DOWN`, `LOST`, `READY`, `STANDBY`, `BACKUP`, `INIT` (`INIT` row continues
past the fetched excerpt but is present as a distinct state, not folded
into `DOWN`).

**Code-vs-docs mismatch (both directions) — this is the significant
finding for D-V6:**

- `preflight_readiness.py`: `_CP_ACTIVE_ROLES = {"ACTIVE", "ACTIVE
  ATTENTION"}` and `_CP_STANDBY_CAPABLE_ROLES = {"STANDBY", "STANDBY READY",
  "READY", "BACKUP"}` (`assessment.py` lines 170-171). The documented
  literal `cphaprob state` token for the attention condition is
  `ACTIVE(!)` (and its `(!F)/(!P)/(!FP)` variants) — **not** the string
  `"ACTIVE ATTENTION"**, and there is **no** `"STANDBY READY"` token in the
  documented exhaustive state list at all (only `STANDBY` and `READY` are
  separate, distinct states). If `ha_local_role` in the collector actually
  carries the raw `cphaprob state` token, these two code constants do not
  match any value the command can emit — they would never match, which is
  the *safe* direction (fails closed to `value_not_established` rather than
  wrongly matching), but it means the code is silently dead/no-op for those
  two tokens rather than doing what its name implies. This needs the
  Product Owner to confirm whether `ha_local_role`/`ha_local_role`-equivalent
  facts are pre-normalized by the collector before reaching this rule set
  (in which case the mismatch is between the collector's normalization
  contract and this file, not between this file and the vendor) or whether
  it is a direct pass-through (in which case the constant names should be
  `"ACTIVE(!)"` and the `STANDBY READY` entry should be dropped/renamed).
  Not adjudicated here — flagged as `MISMATCH` pending that clarification.
- `LOST` and `INIT` are documented, distinct, non-functional cluster states
  that are **absent** from `_CP_RECOGNISED_ROLES` (`ACTIVE ∪
  ACTIVE_ATTENTION ∪ STANDBY ∪ STANDBY_READY ∪ READY ∪ BACKUP ∪ DOWN`). Per
  the file's own comment ("a KNOWN value outside these sets is an
  unrecognised vendor token ... never counted as 'no standby' or 'exactly
  one active'"), a device reporting `LOST` or `INIT` today falls through to
  `value_not_established` (INSUFFICIENT) rather than being recognized and
  handled as the documented non-functional/problem state it is. This is the
  code being **more conservative than the docs require** — a finding, not a
  defect: it fails closed on two real, named states instead of asserting
  meaning for them, which is compliant with the UNKNOWN/fail-closed law,
  but it does mean preflight evidence coverage is narrower than the vendor's
  full state vocabulary.

## D-V7b — Check Point configured-recovery machine-readable read surface

**Documentation does NOT settle this. Remains `UNKNOWN`** — four sessions
running, and this session's guide search does not close it either. Reporting
that plainly, with the negative evidence, is the intended outcome per the
task instructions.

Evidence gathered:

- `ClusterXL-Configuration-Commands.htm` (the guide's complete `cphaconf` /
  `set cluster` command-and-syntax table) contains **no** row for reading or
  setting the cluster's recovery/preemption policy. Checked programmatically
  for the substrings `priorit`, `recovery`, `preempt` (case-insensitive)
  against the full tag-stripped page text — **all three absent**. The table
  covers: local-log ID mode, Pnote register/unregister/report (single, from
  file, unregister-all), CCP encryption on/off + key, and Forwarding-Layer
  configuration — nothing pertaining to failback/recovery mode.
- `Viewing-Cluster-State.htm`'s glossary tooltip text (embedded in the
  "Cluster Mode" field description) is the only place the concept surfaces
  at all: "High Availability (**Primary Up** — ClusterXL in High
  Availability mode that was configured as **Switch to higher priority
  Cluster Member in the cluster object in SmartConsole**...)" and "(**Active
  Up** — ... **Maintain current active Cluster Member in the cluster object
  in SmartConsole**...)". Both quotes explicitly locate the configured
  setting **in the SmartConsole cluster object**, i.e. management-plane
  GUI/policy configuration — the guide never names a Clish `show`/Expert
  `cphaprob`/`cphaconf` command that reads this property back as a discrete,
  machine-readable field.
- `Introduction-to-ClusterXL.htm` does not mention "Primary Up" at all
  (checked directly; the term only appears via the glossary popup embedded
  in the other page), so no independent corroborating command surface was
  found there either.

What is still missing to close D-V7b: a documented CLI/API read of the
cluster object's recovery-method property (SmartConsole `cluster object >
ClusterXL and VRRP page` per the glossary text) — either a Management-API
call the collector is authorized to use, or a device-side reflection of the
policy after installation. Neither R81.20 admin guide fetched here states
one. Per `AGENTS.md` "UNKNOWN / fail-closed law", this stays `UNKNOWN`; no
value list is invented.

**Code-vs-docs**: `assessment.py`/`preflight_readiness.py` already encode
exactly this as `not_evaluable_reason="configured_recovery_not_readable_d_v7b"`
with `missing_evidence="A9 management-plane cluster recovery setting (not
authorized; D-V7b unresolved)"` — this matches the documentation finding
precisely. No mismatch; the code's conservative `NOT_EVALUABLE` posture for
`("checkpoint", "preemption_known")` is corroborated, not contradicted, by
this session's fetch. D-V7b is correctly still open on `project/QUEUE.md`.

---

## Summary table

| Decision | Settled by R81.20 docs? | Exhaustive? | Code match? |
|---|---|---|---|
| D-V5a | Yes — flag syntax closed | Yes (command table row) | No mismatch; deeper history (`-l <count>`) simply unused yet |
| D-V6 (`-l`/`-i`/`-ia`/`-e`) | Yes | Yes, with one noted intra-guide `-i` vs `-i[a]` inconsistency | No mismatch (`-ia` usage matches) |
| D-V6 (`cphaprob state` fields) | Yes | Yes (state list is exhaustive) | **Mismatch found**: `"ACTIVE ATTENTION"`/`"STANDBY READY"` are not documented literal tokens; `LOST`/`INIT` documented states are unrecognised by current code (conservative, not wrong) |
| D-V7b | **No** | N/A | Code's `NOT_EVALUABLE` posture is corroborated by the absence of any CLI read surface in the fetched guide |

No repository privacy-sensitive value (serial, address, credential) was
retrieved or reproduced in this document — all content above is public
Check Point documentation text and this repository's own code identifiers.

## Addendum (verification pass, 2026-09-12) — the `"ACTIVE ATTENTION"` question, narrowed

The finding above is written conditionally ("if `ha_local_role` actually
carries the raw `cphaprob state` token"). That condition was checked, and the
answer narrows the finding rather than confirming it as a defect.

`configuration/checkpoint_config_collector.py` declares its own vocabulary:

```python
CLUSTERXL_RUNTIME_STATES = (
    "ACTIVE ATTENTION", "STANDBY READY", "ACTIVE", "STANDBY", "READY", "DOWN", "BACKUP", "LOST",
)
```

and derives `local_attention` from it (`local_role in {"ACTIVE ATTENTION", "DOWN"}`).
So the two tokens are **not dead code**: the collector expects them, and the
assessment layer consumes what the collector emits, not raw command output.

What remains genuinely unresolved is narrower and should be stated that way:

1. The documented exhaustive list examined above is for **`cphaprob state`**.
   The collector's tokens may come from a different command's output shape
   (`cphaprob stat` is a distinct command with its own formatting) or from
   real-environment observation recorded in an earlier movement. This pass did
   not establish which, and **`UNKNOWN` is the honest answer** rather than
   declaring the constants wrong.
2. `LOST` and `INIT` remain unhandled by `_CP_RECOGNISED_ROLES`. `LOST` is in
   the collector's vocabulary but not the assessment layer's, so it falls
   through to `value_not_established`. That is conservative and fails closed,
   but it means a documented, named state is treated as an unrecognised token
   rather than as the state it is.

Neither is a safety hole: both directions fail closed. Both are worth closing
with real-environment evidence rather than by reading more documentation,
because the open question is which command's output the collector was written
against — and that is answered by a capture, not by a guide.
