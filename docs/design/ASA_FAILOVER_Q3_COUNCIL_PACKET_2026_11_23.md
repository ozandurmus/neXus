# ASA failover Q3 -- decision-council packet: recording and confirming a session-dropping write (2026-11-23)

**Status: DRAFT -- COUNCIL INPUT ONLY. Not implementation authority.** No code, database migration, gate row or device
command is authorized by this packet. It prepares the decision council and code review that
`PO_DECISION_RECORD_2026_11_13_FAILOVER_ASA_FORTIGATE_PHASE0.md` item 6 requires before the session-drop dispatch state
is built. The PO approved the direction in principle on 2026-11-23
(`FAILOVER_EXECUTION_ASA_CONTRACT_DRAFT_2026_11_18.md` §11.1); the council tests it, it does not reopen Q1/Q2/Q4/Q5/Q6.

Who runs it: the council is convened from a `nexus-po` episode (`GOV_PO_ROLE_MIGRATION.md` §7, `CLAUDE.md`); an
engineering session never invokes it. The code-review plugin runs on the implementing diff afterwards. Examples use
masked names only (`CLS-TANGO-01`, `FW-TANGO-04` / `FW-TANGO-05`, `192.0.2.10` / `192.0.2.11`).

## 1. The decision

On Cisco ASA, `no failover active` sent on the verified Active unit moves the Active management address to the peer
chassis (contract §4.1-§4.2). The SSH session that carried the write may therefore end without returning a prompt.
Today every such ending is recorded `delivery = 'UNKNOWN'` and the run stops `OUTCOME_UNKNOWN` with an incident before
any observation (§3 below), so a successful ASA switch would routinely end as an incident.

**Question for the council:** how is a `no failover active` write whose session drops after the command bytes were
written recorded in `failover_dispatch_intent`, and what -- and only what -- may confirm it?

**Proposal approved in principle by the PO (Option A below):**

1. New delivery value `SESSION_DROPPED_AFTER_SEND`, set once from `MAY_HAVE_BEEN_SENT` only when the literal was
   written and flushed to the channel and the prompt did not come back (timeout, EOF, reset).
2. Scoped to vendors whose descriptor says the write may drop the session (ASA now; FortiGate only by its own later
   decision). Check Point and Palo Alto behaviour stays byte-identical.
3. The run does **not** stop at dispatch; it enters the §4.3 switch-confirmation poll (every 3 s, at most 40 polls,
   120 s, PO Q6) over new trusted SSH sessions to both endpoints.
4. Only a two-sided, serial-verified observation in one poll pass confirms it: both observers answer from the two
   distinct units recorded in `ha_pair_identity` (exact string equality), the unit with `preStandbySerial` reports
   `Active` and the unit with `preActiveSerial` reports `Standby Ready`, and the two views mirror each other. A
   one-sided report never confirms.
5. Budget exhausted, unexpected roles, identity mismatch, owner loss or crash -> exactly today's `unknown(...)` path:
   `observation = 'OUTCOME_UNKNOWN'`, run `STOPPED` / `OUTCOME_UNKNOWN`, job `OUTCOME_UNKNOWN`, incident, estate fence.
   Never a retry, never a second write.

### Sub-decisions the council must settle

| # | Sub-decision | Proposed answer (for challenge) |
| --- | --- | --- |
| D1 | Representation | A new `delivery` value (CHECK change, one migration) rather than reusing `REPLY_RECEIVED` or leaving `MAY_HAVE_BEEN_SENT`. |
| D2 | Which transport endings qualify | Only "written and flushed, prompt not back" (`ExecResult.TimedOut` today, §3 F12). A write/flush `IOException` (`ExecResult.ChannelFailed("command not sent")`) stays `UNKNOWN` + immediate stop: a failed flush does not prove zero bytes reached the device, and it is not the expected address-swap shape. A clean prompt stays `REPLY_RECEIVED`. A pre-transport refusal (mutation switch off) stays `NOT_SENT`. |
| D3 | Vendor scoping | A per-vendor descriptor flag (`FailoverVendor`, e.g. `writeMayDropSession`); the repository accepts the new value only for a run whose vendor carries the flag. CP/PAN code paths and SQL unchanged. |
| D4 | Confirmation predicate | `confirmDispatch` accepts `delivery IN ('REPLY_RECEIVED','SESSION_DROPPED_AFTER_SEND')` only for a flagged vendor; for CP/PAN it stays `delivery = 'REPLY_RECEIVED'`. A dropped session alone never confirms; observation does. |
| D5 | `job_step_attempt.outcome` while observing | Contract §4.2 proposes `SENT_AWAITING_OBSERVATION`, `error_class` null, `matched_expectation = false`; set to the final value when observation confirms or `unknown(...)` runs. |
| D6 | When the 120 s budget starts | Proposed: when `dispatch()` returns. Worst case from send to `FAILOVER_TIMEOUT` is then the write timeout (30 s, §3 F13) + 120 s. Alternative: start at send and shorten the poll window. |
| D7 | Owner loss / crash during observation | No new path: the intent is `delivery <> 'NOT_SENT'` and `observation = 'NOT_OBSERVED'`, so reconciliation and the estate fence already cover it (§3 F7-F9). The council confirms that no predicate needs the new value spelled out. |
| D8 | Hard-coded `delivery = 'REPLY_RECEIVED'` readers | The resume-return queries (§3 F10) are Check Point-only and must **not** accept the new value. Any other reader added later must state its choice. |
| D9 | Audit / UI wording | The run view shows "session dropped after send; confirmed by observation" or "...; not confirmed" -- never "replied" and never OK for an unconfirmed intent. Counts only; no raw transcript. |

## 2. Options

| Option | What it does | For | Against |
| --- | --- | --- | --- |
| **A (PO-approved in principle)** | New `SESSION_DROPPED_AFTER_SEND`, vendor-gated, confirmed only by two-sided serial observation (§1). | Distinguishes "no reply because the address moved" from a reply; keeps the observation-only confirmation rule; failure path unchanged. | Changes the mutation boundary: DB CHECK, `dispatch()`, `confirmDispatch()`; needs one migration and careful CP/PAN non-regression. |
| B | Keep today's semantics: drop -> `UNKNOWN` -> immediate `OUTCOME_UNKNOWN` + incident. | Zero code change; maximally fail-closed. | If the drop is the normal ASA shape (UNKNOWN until the pilot, §5 U1), every successful switch ends as an incident needing `security_admin` release; the feature is unusable in practice. |
| B-then-A | Ship ASA execution with B for the attended pilot, measure whether the prompt returns, then build A only if needed (design draft §10 item 1 last sentence). | Builds the boundary change only on evidence. | The first pilot run likely ends in an incident by design; the measurement itself requires the write. |
| C | Record the drop as `REPLY_RECEIVED`. | No CHECK change. | Rejected: records a reply that never came (evidence law "collection success != semantic correctness"); loses the audit distinction; would let CP/PAN-only readers (§3 F10) mistake it for a reply. |
| D | Leave `delivery = 'MAY_HAVE_BEEN_SENT'` while observing. | No CHECK change. | `MAY_HAVE_BEEN_SENT` today means "transport on the stack"; leaving it after `dispatch()` returns blurs that, and `unknown(...)` would later rewrite it to `UNKNOWN`, erasing that the bytes were written. |
| E | Avoid the drop by sending `failover active` on the Standby. | -- | Rejected by the PO (2026-11-13 item 2) and by contract §3.1: the Standby address moves too, so its session drops as well. |

## 3. Verified repository facts (read 2026-11-23 on this branch)

How Check Point and Palo Alto dispatch intents record delivery today:

- **F1 -- schema.** `ui2/service/src/main/resources/db/migration/V138__failover_dispatch_intents.sql:14-17`:
  `delivery` CHECK `('MAY_HAVE_BEEN_SENT','REPLY_RECEIVED','UNKNOWN')` default `MAY_HAVE_BEEN_SENT`; `observation` CHECK
  `('NOT_OBSERVED','CONFIRMED','OUTCOME_UNKNOWN')`. `:19` `UNIQUE(run_id,step)` -- one intent per step.
  `V140__failover_not_sent.sql:2-6` adds `NOT_SENT` and requires `observation = 'NOT_OBSERVED'` for it. Option A
  therefore needs a new migration replacing `failover_dispatch_intent_delivery_check`.
- **F2 -- immutability guard.** `V138...sql:25-38`, line `:33`: `delivery` may change only while it is
  `MAY_HAVE_BEEN_SENT`; `observation` only while `NOT_OBSERVED`; no delete. A single
  `MAY_HAVE_BEEN_SENT -> SESSION_DROPPED_AFTER_SEND` step and a later `observation` update fit the guard unchanged.
- **F3 -- reservation.** `ui2/persistence/src/main/java/com/securityexpert/nexus/ui2/persistence/JooqCpFailoverRepository.java:1069-1100`
  (`prepareDispatch`): one transaction inserts `job_step_attempt` (`mutation_boundary_crossed = true`, `sent_at = now()`),
  the intent (lease epoch, owner generation/instance, member, gate) and sets `failover_run.mutation_possible = true`.
- **F4 -- send.** `JooqCpFailoverRepository.java:1114-1166` (`dispatch`): claim once in its own transaction
  (`:1117-1122`, `dispatch_claimed = true`); then, under the dispatch lock, re-check owner, approval window, step and
  target (`:1124-1139`) and call the vendor's `send` supplier once (`:1141`). `NotSent` -> `delivery = 'NOT_SENT'`,
  run `STOPPED` / `MUTATION_DISABLED_BEFORE_SEND`, job `FAILED` (`:1142-1153`). Any other exception -> `replied = false`
  (`:1154`). Then `delivery = REPLY_RECEIVED | UNKNOWN` (`:1155-1156`), `job_step_attempt.outcome =
  REPLY_RECEIVED | OUTCOME_UNKNOWN`, `error_class = DELIVERY_UNCERTAIN` (`:1157-1159`), and when not replied
  `unknown(..., "DELIVERY_UNCERTAIN")` in the same transaction (`:1160`). This is the line Option A changes.
- **F5 -- confirmation.** `JooqCpFailoverRepository.java:1169-1177` (`confirmDispatch`): sets `observation =
  'CONFIRMED'` only where `delivery = 'REPLY_RECEIVED' and observation = 'NOT_OBSERVED'` (`:1174`) for the owning
  lease epoch. Javadoc `:1168`: "Independent role observations, never the write reply, confirm the requested effect."
- **F6 -- terminal paths.** `workerState` (`:1180-1203`) refuses `DONE` while any intent of the run is not `CONFIRMED`
  (`:1193-1194`) and routes a `STOPPED` with `mutation_possible` to `unknown(...)`. `unknown` (`:1205-1220`) sets
  `observation = 'OUTCOME_UNKNOWN'` for every intent with `delivery <> 'NOT_SENT'` and `NOT_OBSERVED`, rewriting only
  `MAY_HAVE_BEEN_SENT` to `UNKNOWN` (`:1212-1214`), so a `SESSION_DROPPED_AFTER_SEND` value would be kept.
- **F7 -- incident.** `V169__failover_pilot_p0_release_and_recovery_mode.sql:48-56`: a `FAILOVER` run turning
  `STOPPED` with `mutation_possible` opens a `failover_quarantine` row unless the only intents are `NOT_SENT` with outcome
  `MUTATION_DISABLED_BEFORE_SEND`. Keyed on `delivery <> 'NOT_SENT'`, so it covers the new value without change.
- **F8 -- estate fence.** `V138...sql:45-66` (later redefined in `V140...sql:8-` with the `NOT_SENT` exclusion) refuses
  any new `FAILOVER` run with `UNRESOLVED_DISPATCH` while another run has an intent `NOT_OBSERVED`, or
  `OUTCOME_UNKNOWN` with an active incident. Repository mirror: `UNRESOLVED_SENDS` `JooqCpFailoverRepository.java:1266-1268`;
  `openIncidents` `:1271-`.
- **F9 -- owner loss.** `reconcileDispatches` `JooqCpFailoverRepository.java:1225-1243`: an intent with
  `delivery <> 'NOT_SENT'` that is `NOT_OBSERVED` (or whose run is not terminal) and whose job lease has expired goes
  to `unknown(..., "OWNER_LOST")`. A live, unexpired lease is skipped. The 120 s observation window is inside the lease.
- **F10 -- `REPLY_RECEIVED`-only readers.** Resume-return eligibility `JooqCpFailoverRepository.java:1461-1468` (vendor
  must be `check_point`, `:1461`) and `resumeFormerActive` `:1515-1519` require `delivery = 'REPLY_RECEIVED' and
  observation = 'CONFIRMED'`. `FailoverVendor.CISCO_ASA` has `resume = false`
  (`ui2/service/src/main/java/com/securityexpert/nexus/ui2/service/failover/FailoverVendor.java:14`, `:41`).
- **F11 -- vendor workers.** Check Point `ui2/worker/src/main/java/com/securityexpert/nexus/ui2/worker/failover/CpFailoverJobExecutor.java`:
  the `DOWN`/`UP` write (`:680-700`) supplies `send` that returns `true` only for `ExecResult.Completed` with exit 0,
  bounded output and no "command unavailable" text, `false` on any transport exception (`:689-697`); not replied ->
  `Stop("OUTCOME_UNCERTAIN")` (`:699`). Flow `:257-261`: write, `waitFor` (3 s interval `:34`, at most 21 polls `:35`),
  `FAILOVER_TIMEOUT`, then `confirmDispatch()` (`:434-436`, stop `OUTCOME_UNCERTAIN` if the update matched no row).
  Palo Alto `PanFailoverJobExecutor.java`: XML API `<suspend/>` write `:360-371` with the same shape (HTTP 200 + success
  pattern = replied), flow `:167-170`, `confirmDispatch` `:214-216`. Neither vendor has a "session dropped" notion.
- **F12 -- ASA transport shape.** `AsaFailoverJobExecutor` is readiness-only today and uses
  `ssh.execInteractive` (`AsaFailoverJobExecutor.java:323`, `:327`, `:343`). In
  `ui2/worker/src/main/java/com/securityexpert/nexus/ui2/worker/transport/ssh/InteractiveShellSession.java:105-118`
  the command is written and flushed (`:112-113`); an `IOException` there returns `NOT_SENT` (`:116-117`). In the read
  loop an `IOException` is "treated as no data this iteration; the deadline still governs" (`:165-166`), and the
  non-streaming loop does not check whether the channel is still connected, so a dropped session surfaces as
  `TIMED_OUT` at the deadline (`:243`). `SshExecTransport.java:545-549` maps `TIMED_OUT` to
  `ExecResult.TimedOut` (and closes the shell); `:562-563` maps `NOT_SENT` to `ExecResult.ChannelFailed("command not
  sent")`. So today the worker can tell "flush failed" from "written, no prompt by the deadline", but not EOF from a
  slow prompt.
- **F13 -- write timeout.** The proposed gate `asa_failover_inactive_switch` has a 30 s timeout (contract §3.3, PO Q1
  2026-11-18). By F12, a dropped session is detected only when those 30 s end.
- **F14 -- vendor availability.** `FailoverVendor.CISCO_ASA` is `available = false` (`FailoverVendor.java:14`, `:39-40`):
  every ASA execution entry point refuses today; no ASA write path exists.
- **F15 -- attempt outcome column.** `job_step_attempt.outcome` is plain `TEXT`
  (`V4__collection_engine_core.sql:123`); no CHECK constraint on it was found in the migrations, so D5 needs no
  constraint change (verify in review).

## 4. Controlling clauses

- `AGENTS.md`: "UNKNOWN / fail-closed law" (absence of a reply is not evidence of failure or success); "Evidence laws"
  (a member's report about its peer is not independent observation; collection success is not semantic correctness);
  "Identity law" (serials opaque, exact equality); "Mandatory build lifecycle" (storage/schema migration and CLASS 2
  behaviour need a frozen contract first); "Network action taxonomy" (CLASS 2 failover; no automatic write);
  "Architectural invariants" (failover authority is the Java worker/service and their tests).
- `PO_DECISION_RECORD_2026_11_13_FAILOVER_ASA_FORTIGATE_PHASE0.md` item 6 and "Not authorized by this record": the
  session-drop state goes through the decision council and the code-review plugin before it is built.
- `FAILOVER_EXECUTION_ASA_CONTRACT_DRAFT_2026_11_18.md` (DRAFT): §4.2 item 4, §4.3, §6.3, §7 item 1 (point of no
  return), §11 (PO answers Q1/Q2/Q5 2026-11-18) and §11.1 (Q3 in principle, Q4, Q6, Q7 2026-11-23).
- `FAILOVER_FORTIGATE_ASA_DESIGN_DRAFT.md` §10 item 1 (adopted after the Argon review) and
  `ARGON_FAILOVER_FORTIGATE_ASA_REVIEW.md` §1a (the finding that today's `dispatch()` stops at once on a drop).
- `FAILOVER_EXECUTION_CP_CONTRACT.md` and `FAILOVER_EXECUTION_PAN_CONTRACT.md` (FROZEN): their dispatch and observation
  behaviour must not change.
- `PO_DECISION_RECORD_2026_10_20_FAILOVER_PILOT_P0.md` item 1: only a `security_admin` releases a failover incident.

## 5. Unknowns (explicit, not to be filled from general knowledge)

- **U1** Whether ASA returns the `#` prompt (or any line) before the Active address moves after `no failover active`
  (contract §3.2 UNKNOWN 3). Measurable only by the attended pilot write. If the prompt reliably returns, the new value
  may never be set on ASA; Option A still bounds the case where it does not.
- **U2** Whether the drop shows as TCP reset, EOF or silence. By F12 all three surface as `TIMED_OUT` today, so the
  record cannot distinguish them; the council decides whether that distinction matters (proposed: no).
- **U3** How long the ASA pair takes to answer two-sided `show failover` with the swapped roles after the write (`Just
  Active`, `Active Drain`, `Active Applying Config` states). The 120 s budget (PO Q6) is a design value, not a measurement.
- **U4** Whether `show failover history` reason text after `no failover active` matches the measured `failover active`
  text (contract §3.2 UNKNOWN 2). No confirmation rule may depend on it.
- **U5** FortiGate's `diagnose sys ha reset-uptime` session behaviour: unmeasured; FortiGate gets the flag only by its
  own decision.

## 6. Non-goals

- No code, migration, gate row, `max_frequency` change or device command from this packet or the council.
- No change to Check Point or Palo Alto dispatch, observation, resume or incident behaviour.
- No automatic retry, cancel, reverse or second write after the point of no return; no failback automation.
- No relaxation of the confirmation rule (one-sided, single-poll-pass-incomplete, host-key or address-based
  confirmation are all excluded; identity is the serial pair only).
- Multiple context mode, Active/Active failover groups, ASA clustering, FTD (out of scope per PO Q2 and §1.2).
- Pilot pair and window selection (PO Q7, later, not a freeze blocker).

## 7. What the council should return

A verdict per sub-decision D1-D9 (accept / change / reject with reason), any additional invariant the implementing
diff must test (proposed minimum: CP/PAN byte-identical SQL and outcomes; ASA flush failure stays `UNKNOWN` + immediate
stop; ASA timeout after flush -> `SESSION_DROPPED_AFTER_SEND` + observation; confirmation only by the two-sided serial
pass; budget exhausted -> `OUTCOME_UNKNOWN` + incident + estate fence; owner loss mid-observation -> `OWNER_LOST`;
guard rejects a second `delivery` change), and whether the contract may then be proposed to the PO for freeze.
