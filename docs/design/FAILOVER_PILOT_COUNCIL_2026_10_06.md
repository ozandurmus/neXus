# Failover pilot decision council — 2026-10-06

**Status: DRAFT — COUNCIL BLOCKED. Planning only; no implementation, command,
migration, execution or contract approval.**

The requested external council could not run in this environment. No specialist
opinion, blinded challenge, council consensus or council approval is claimed.
This document preserves the complete consultation outcome and a separately labeled
moderator preliminary review so the next session can finish the requested work.

## 1. Scope, authority and consultation record

Reviewed worktree: `sa/fo-council`, baseline `7b5b8361`. The supplied plan was also
read directly from local `sa/failover-p0-plan:docs/design/FAILOVER_EXECUTION_PLAN.md`
without switching branches. Its status is DRAFT. No remote fetch was performed.
The older schema-91 checkpoint in `CURRENT_STATE.md` does not establish current
execution readiness. Live statistics in the plan are PO-supplied, unverified here.

The PO's decisions are inputs, not questions to reopen: all ten PRs, including
PRs 2–4, remain in scope; CP uses the existing gated `clusterXL_admin down` followed
conditionally by `clusterXL_admin up` on the former active; manual confirmation
precedes fresh checks, execution and immediate post-checks. This lane reviews the
plan only. The outer hard rules prohibit push and PR creation despite the appended
brief requesting them. Only a local documentation commit is authorized.

The explicitly requested `nexus-decision-council` skill was found in the local
synced skill installation and read with `references/panels-and-output.md`. Router:
CONVENE, because authorization, durable mutation boundaries, crash recovery and
vendor evidence semantics can change the decision. The PO explicitly names
Codex/Astra as moderator, superseding the skill's default Claude moderator; explicit
artifact persistence overrides the skill's default no-transcript persistence.
AGENTS.md requires real external CLI consultations; internal simulated seats are
not substitutes. `project/QUEUE.md` was used instead of direct project JSON reads,
as required by the later constitutional amendment. State and handover files are
not edited under this lane's explicit scope restriction.

Selected seats, each to receive the same bounded packet independently:

| Seat | Why it can change the outcome |
| --- | --- |
| Security and Adversarial Reviewer | Approval identity, request binding, revocation, alternate-route admission and incident release. |
| Network/Vendor Semantics Specialist | What existing reads prove, phase-specific health, traffic continuity and command/frequency gaps. |
| Reliability and Operations Engineer | Durable ownership, uncertain delivery, crash recovery and four-day integration sequencing. |

A separate UX seat would not materially change this small existing-panel flow.
A separate data/migration seat overlaps reliability for this planning pass; complex
schema proposals may justify one in a later review. Compliance/change-control
questions remain with the PO and security seat, not an invented fourth approval.

The initial packet contained the exact four questions, all three PO decisions,
full supplied plan, frozen CP contract, verified gate limits, invariants, forbidden
scope, known unknowns and the skill's eight required answer fields. Successful
external responses were to be saved unchanged before synthesis. The intended
blinded pass would remove seat/model labels and challenge all three candidates
against authority, vendor correctness, fail-closed safety, operator usefulness,
operational risk, testability and scope. That pass was not started.

| Attempt through repository runner | Complete relevant outcome |
| --- | --- |
| `scripts/consult_ui_effectiveness_council.py::run`, external `claude -p --model claude-fable-5-1`, tools disabled, empty strict MCP configuration, hooks disabled, no session persistence | Exit 1. Initial text invocation returned no review; JSON diagnostic invocation returned `is_error: true`, `num_turns: 1`, result reproduced below. |
| Same runner, external `codex exec --ignore-user-config --ephemeral -m gpt-6-astra -c model_reasoning_effort="high" --sandbox read-only` | Exit 1 before model consultation; complete error text reproduced below. No answer artifact. |

Claude result, unaltered:

```text
Not logged in · Please run /login
```

Codex stderr, unaltered:

```text
WARNING: proceeding, even though we could not create PATH aliases: Operation not permitted (os error 1)
Error: failed to initialize in-process app-server client: Operation not permitted (os error 1)
```

No credentials were inspected, no permissions bypass was attempted, and no further
seats were invoked after both available CLI paths proved unavailable. There are
zero completed independent seats. Neither cross-model validation nor role-diverse
review completion is claimed. Exact task usage was unavailable; no quota estimate
is presented as measured per-task usage.

## 2. Verified repository evidence

The controlling [CP contract](FAILOVER_EXECUTION_CP_CONTRACT.md) is FROZEN,
including amendments 10–16. Its later amendments supersede earlier table wording:
per-VS units, informational ARP, deferred CPS, tuned connection checks and policy
time skew must survive the work. It permits the approving administrator to start.

Bounded source inspection at the baseline confirms:

- `ui2/service/src/main/resources/db/migration/V101__cp_failover_execution.sql`:
  connection summaries once pre/post per member/run; traffic samples twice, five
  seconds apart, pre/post per member/run; DOWN and UP once each on former active.
  Role polling says every three seconds for at most 60 seconds **per run**.
- `V102__cp_failover_sync_policy_checks.sql`,
  `V110__cp_failover_interface_read_frequency.sql` and
  `V111__cp_failover_measured_checks.sql` add/correct the stated sync, policy,
  interface, critical-device, bond, event and routing reads. These are not new
  commands to approve again. The gate fixture was inspected for corresponding
  limits; complete gate/migration parity has not been executed or certified.
- `ui2/worker/src/main/java/com/securityexpert/nexus/ui2/worker/failover/CpFailoverJobExecutor.java#execute`:
  checks the approval window before collection; performs DOWN, role wait,
  post-checks, UP, role wait, then success. It does not run a complete post-UP
  health battery. This inspection does not close the plan's other P0 findings.
- `ui2/worker/src/test/java/com/securityexpert/nexus/ui2/worker/failover/CpFailoverJobExecutorTest.java`:
  existing post-check, sync and routing cases are available to extend. Tests were
  read, not executed; no device or runtime was contacted.

## 3. Moderator preliminary recommendation — not council synthesis

Retain the worker pipeline and existing panel. Use one enrolled classic CP HA
unit, one attended operation, all ten PRs completed, generic/scheduled mutation
still disabled, and no success until both the role transition and required health
and traffic evidence are affirmative. Four days is a delivery target, never a
reason to weaken or waive a P0 closure.

### (a) Operator flow and mutation boundaries

1. **Initiate and confirm.** In the existing panel show the masked unit, bounded
   window, intended role swap and warning that service may be interrupted and
   automatic failback is unavailable. Confirm binds server-side request ID, opaque
   unit/member set, approval version and action. Browser submits typed intent,
   never commands. Double-click, retry and page refresh return the same run rather
   than creating another mutation. An aiview session may observe but cannot start.
2. **Preparing.** Server validates authorization, enrollment, incident status,
   fleet/member ownership and trusted endpoint bindings. Run the full fresh
   blocking readiness battery on both members within the request workflow.
   Cached READY is display only. Blocking FAIL/UNKNOWN/stale evidence refuses the
   write; informational ARP/flap warnings and deferred CPS are not promoted to
   blocking errors. Evidence coherence/freshness bounds must be decided before
   implementation, not invented from a timeout constant.
3. **Failing over.** Immediately before DOWN, atomically bind/recheck the request,
   unrevoked approval/window, membership, fresh evidence and current owner, and
   durably record the attempt before any send. Only then show dispatch status
   from server state and issue the gated DOWN once on the verified active.
   A DB transaction cannot atomically commit a remote device effect. A lease
   check alone cannot fence a suspended worker that resumes after a new owner:
   unresolved prior dispatch must block reassignment/sends until reconciliation.
4. **Switched / Checking.** Independently observe new ACTIVE and former active
   DOWN. Immediately collect the permitted post-switch battery and traffic
   evidence. This is a phase-specific evaluation of the full applicable battery:
   do not demand ACTIVE/STANDBY while the old active is deliberately DOWN. Do not
   exempt genuine peer, policy or sync uncertainty without proven phase semantics.
5. **Returning / Final verification.** Only affirmative post-switch checks and
   a fresh mutation-boundary authorization check permit one UP on the former
   active. Prove the new active stays ACTIVE and the former active becomes
   STANDBY; verify final health and continuing traffic. Additional post-UP reads
   need the frequency approval below. Display completion only when these pass.
6. **Stopped / Outcome unknown.** After any crossed or uncertain mutation boundary,
   preserve durable incident/quarantine and show the failed phase, masked reasons
   and audit references. Stop later writes, including UP when approval expires,
   is revoked, ownership is lost or a blocking check fails. Do not silently treat
   UP as an exempt cleanup action. This can leave a member DOWN: that risk belongs
   in the warning, adequate window planning and reviewed human recovery procedure.
   No automatic retry, compensation, failback, incident release or reschedule.

Keep cluster health, traffic counters and application continuity as distinct
results. UI/PO visual evidence uses aiview; authenticated execution/approval acts
use authorized roles and have separate security tests. A masked view is not an
execution permission.

### (b) Existing evidence and explicit unapproved additions

| Existing exact read | Permitted evidence use under the CP contract |
| --- | --- |
| `cphaprob stat` | Independent member roles and membership, with endpoint identity binding and phase-appropriate expected pair. |
| `cphaprob tablestat` | Reciprocal member address relationships; do not persist raw addresses or use observer-local numeric interface indices as joins. |
| `cphaprob -a if`; `cphaprob show_bond`; `cphaprob -ia list` | Required interface, bond and critical-device status; unknown shapes do not pass. |
| `cphaprob syncstat`; `fw stat` | Sync and policy continuity under amended rules; policy names compared locally, not stored. |
| `fw tab -t connections -s` | Current population/peak, not CPS. Post-switch new-active population at least 80% of former-active baseline. Zero/missing baseline cannot prove session continuity. |
| `cat /proc/net/dev` | Two samples five seconds apart, comparable selected traffic interfaces, new-active rate positive and at least 50% of former-active baseline. |
| `cpstat os -f routing` | Default-route presence and route count relative to the pre-switch active; not reachability or routing equivalence proof. |
| `arp -an`; `cphaprob show_failover` | Informational observations under later amendments, not newly invented blockers. |

All existing transport/context, timeout, serial-session and gate constraints still
apply. Gateway execution uses the existing `bash -lc` transport path; never apply
these gateway operations on a management server. The literal command and actual
transport wrapper must match the approved implementation before any later pilot.

Counter interpretation requires elapsed sample time, stable interface selection,
nonnegative deltas and reset/missing-sample detection. Exclude management, sync
and loopback traffic and avoid counting a bond and its children twice. If the
selected interface set cannot be proven to represent the pilot traffic, evidence
is UNKNOWN. Do not reset counters. A positive aggregate may hide a failed path;
connection-table population may include idle sessions. These are useful network
indicators, not proof that an application request received its expected response.
The official [Check Point fw tab reference](https://sc1.checkpoint.com/documents/R82/WebAdminGuides/EN/CP_R82_CLI_ReferenceGuide/Content/Topics-CLIG/FWG/fw-tab.htm)
documents table summaries; it does not turn a table-size delta into CPS.

The narrowest pilot uses existing counters and an already authorized independent
application transaction monitor, if one exists and is correctly scoped. No such
monitor was established here. Until its path, freshness and success predicate are
proved, application continuity is NOT_EVALUABLE. The PO must accept the explicitly
limited network-evidence claim or approve an exact application probe; do not
silently claim the broader business-service outcome.

**Approval item R1 — additional CP read frequency, not new literals.** Propose
one additional post-UP sample of each of `cphaprob tablestat`, `cphaprob -a if`,
`arp -an`, `fw tab -t connections -s`, `cphaprob syncstat`, `fw stat`,
`cphaprob -ia list`, `cphaprob show_bond`, `cphaprob show_failover`,
`cpstat os -f routing` per member; two additional `cat /proc/net/dev` samples
five seconds apart per member. Restrict to the single enrolled classic CP pair,
one run, existing SSH sessions, serial commands, existing 30-second timeouts,
no retries and existing pauses. Propose `cphaprob stat` at most once every three
seconds per member for at most 60 seconds after DOWN and separately 60 seconds
after UP (at most 21 samples per member per transition, including the first).
This explicitly resolves the current per-run polling-budget ambiguity. An
engineering owner must obtain exact PO approval and a migration/fixture frequency
amendment before enabling it. No such approval or gate change occurred here.

**Approval item R2 — optional SNMP, not needed to duplicate existing counters.**
Propose a bounded SNMPv3 authenticated/encrypted GET, no WALK, against each enrolled
member's approved agent endpoint, UDP 161, for only the already-resolved forwarding
interface indices. Exact OID template per index `i`:

```text
GET 1.3.6.1.2.1.31.1.1.1.6.i   (ifHCInOctets)
    1.3.6.1.2.1.31.1.1.1.10.i  (ifHCOutOctets)
    1.3.6.1.2.1.31.1.1.1.19.i  (ifCounterDiscontinuityTime)
    1.3.6.1.2.1.2.2.1.8.i     (ifOperStatus)
```

Proposed bounds: at most four enrolled forwarding indices per member, one request
per sample/member, two samples five seconds apart in each of pre-DOWN,
post-DOWN and post-UP phases; timeout three seconds, zero retries. This is a
proposal template, not an executable authorization: PO must approve exact opaque
endpoint references and each substituted index/OID, credential-store reference,
identity binding and interface mapping. No SNMP support or dependency availability
has been verified; reject this option if it needs new dependencies for this lane.
Reusing an approved monitoring result is preferable to creating another credential
path. Persist only rates/statuses and opaque references. Changed discontinuity
time, missing OIDs or unproven interface mapping yield UNKNOWN. The standard
[RFC 2863](https://www.rfc-editor.org/rfc/rfc2863.html) defines these counters and
discontinuity semantics; it does not prove support on the pilot devices.

**Approval item R3 — optional Splunk corroboration.** Proposed read-only search
operation: `POST /services/search/jobs/export` on an explicitly approved Splunk
service endpoint with TLS verification, `output_mode=json`, `search` as below,
and absolute `earliest_time` / `latest_time` for each approved interval. This POST
creates a search operation, not a device mutation. It still needs scoped service
access approval; it is not implicitly covered by a CP SSH command gate.

```spl
search index=pilot_synthetic sourcetype=pilot_firewall unit_ref="pilot-unit-01"
| stats count AS events
        count(eval(action="accept")) AS accepted
        count(eval(action="drop" OR action="reject")) AS denied
        min(_time) AS first_event max(_time) AS last_event
        max(_indextime) AS last_ingested
```

The index, sourcetype, fields and value above are invented placeholders, not an
assertion about the live schema. Exact target, field extraction, server-side opaque
unit/member mapping, allowed predicate and time bounds must be verified and
approved before execution. Proposed budget: three finite searches (pre, post-DOWN,
post-UP), each a 60-second event window, 15-second timeout, no automatic retry,
aggregate results only. Clock alignment, log source health and measured ingestion
lag must be established; this cannot be an immediate blocking check if lag exceeds
the execution budget. No events means UNKNOWN, not zero loss; accept logs alone
cannot prove delivery or distinguish old from newly established working sessions.
Keep it corroborative unless a separately reviewed freshness/coverage contract
supports making it mandatory. No raw logs, arbitrary SPL, credentials or production
identities enter the browser/report. Endpoint and time-bounded search support are
documented by [Splunk](https://help.splunk.com/en/splunk-enterprise/leverage-rest-apis/rest-api-tutorials/10.6/rest-api-tutorials/creating-searches-using-the-rest-api);
actual local version and access remain unverified.

No other new device read is proposed. In particular, do not introduce a new CPS,
packet-capture or connection-dump command just to fill an evidence gap. R1–R3 are
unapproved proposals, and R2/R3 still need concrete local bindings before approval.

### (c) Pilot approval policy

Recommendation: two independently authenticated humans for the first production
pilot and incident release. One approves the bounded request/window, another
confirms execution; neither is a caller-supplied `approver_id` string. Bind both
authenticated acts to the same request, membership and policy version. Separate
accounts controlled by one person do not provide independent human review.

This is a recommendation, not current authority. CP contract §1/§8 expressly allows
the approving administrator to start. The plan's security-review recommendation
cannot silently override it. The engineering owner must record a pilot-specific
successor decision if the PO selects dual control. If the PO retains the existing
single-admin allowance, record that choice and residual risk plainly; still require
separate approval-window creation and explicit start confirmation, freshness,
server-side identity, enrollment, fences, audit and incident-specific release.
Do not describe a witness or two clicks by one person as four-eyes control. Any
other still-applicable frozen gate requiring distinct humans remains a blocker
until explicitly reconciled; this preliminary review does not certify succession.

A pre-approved window does not authorize a new command, an automatic recovery, a
second run or a second unit. Aiview cannot supply either execution authority act.

### (d) All ten PRs in a four-day target

Parallelize development/test preparation only across bounded file ownership.
Integrate shared schema/repository/service edits serially. One integration owner
allocates migration versions and common state transitions. Do not split a shared
transaction design among independent workers or label a disabled endpoint's P0
closed. This is a suggested staffing plan, not agents launched by this review.

| Day | Work and dependency boundary | Required exit evidence |
| --- | --- | --- |
| 1 | PR1 deny fences first. Agree minimal shared storage/ownership and approval contracts. PR2 baseline/time and PR3 key lifecycle can proceed on disjoint internals; one owner integrates their shared envelope/service mapping. PR5 incident/enrollment foundation can be prepared in parallel. | Every alternate route denies mutation; readiness retained; storage/security decisions recorded by engineering owner before implementation. Targeted integrity/key tests. |
| 2 | Finish PR2/3. PR4 schedule/ledger transactions integrates after their formats settle. Complete PR5. PR6 attempts/reconciliation builds on PR5 and aligns transaction/ownership helpers with PR4. PR8 pure check logic/tests can proceed independently. | Real PostgreSQL baseline/key/ledger round-trips and concurrency/failure cases. Incident exclusion survives restart; no process-local substitute. |
| 3 | Integrate PR6 and PR8; PR7 binds approval and fresh checks onto PR5/6 ownership. PR9 consumes PR7/8, requires R1 approval before extra reads and resolves phase-specific postconditions. PR10 masked fixtures/UI work can start from stable response fields. | Stale owner, expiry/revocation before both writes, lost reply, DB failure after send, peer disagreement and post-UP failures stop/quarantine. No replay. |
| 4 | Finish PR9/10 and cumulative integration; independent external re-review; all ten PRs represented in the P0 evidence matrix. Separately authorized release/acceptance/pilot only after every gate clears. | All affected/full gates, database crash/race tests, privacy, gate parity, masked acceptance and explicit release/run approval. Any missing P0 or real evidence means NO-GO. |

Dependency spine: `1 -> 5 -> 6 -> 7 -> 9 -> 10`; reciprocal checks `8 -> 9`;
format/key chain `2 + 3 -> 4`; PR4/6 must share coherent ownership/transaction
semantics before integration; `2 + 3 + 4 -> 10` are mandatory closure inputs.
PR10 can prepare fixtures early but cannot accept unintegrated predecessors.

No capacity measurement supports a four-day completion promise. If R1, approval
succession, PostgreSQL tests, external review, semantic proof or human acceptance
misses the window, deliver safe completed work with mutation OFF and move the
pilot date. Do not remove PRs 2–4 or weaken checks to preserve the date. No worker
in this lane is authorized to access the integration host or run Gradle locally.

## 4. Decisions, acceptance, dissent and remaining gaps

**Supported council consensus:** unavailable; no seat completed.
**Material council dissent:** unavailable; none may be inferred from failed calls.
The principal unresolved policy tension is the moderator's two-human recommendation
versus the current contract's explicit same-admin allowance.

Exact remaining PO/engineering-owner decisions:

1. Pilot-specific two-human requirement or retained same-admin policy, with explicit
   authority succession where required; do not reopen the decision to do all PRs.
2. Exact classic CP unit/member enrollment, window, recovery decision-maker and
   stakeholder change/security sign-off. The illustrative `CLS-ROMEO-01` is not enrollment.
3. R1 additional post-UP and polling frequency. R2/R3 only if independently useful
   and feasible; no new command is assumed approved.
4. What the accepted traffic claim covers: existing network indicators versus
   proven representative application transaction continuity. No broad healthy label
   while required evidence is NOT_EVALUABLE.
5. Storage/security amendments and phase semantics needed before implementation,
   including no unproven expectation of healthy synchronization while deliberately DOWN.

Acceptance criteria for the proposed design: authenticated immutable request
binding; fresh affirmative blocking checks; durable fenced before-send records;
no replay after uncertain delivery; incident-specific persistent quarantine across
all routes; exact two-sided roles before/after both writes; phase-appropriate health
and bounded traffic evidence; no synthetic transport success; all ten PRs and each
P0 closure proven by appropriate tests, independent review and separately authorized
real acceptance. Aiview navigation must satisfy the stated zero-4xx criterion;
expected authorization denials belong in separate API security tests.

Rejected options: cached READY authorizes dispatch; automatic UP on error; automatic
failback/retry; mandatory new SNMP integration despite adequate existing counters;
Splunk accept counts as proof of application success; arbitrary new gate rows;
dual control by submitted second-actor text; deferring PRs 2–4; claiming council
completion from moderator-only reasoning.

Revisit when external consultation becomes runnable, pilot unit/mode changes,
traffic coverage is insufficient, identity/ownership assumptions change, or any
fault/race test fails. Unknown vendor or deployment facts remain UNKNOWN.
Confidence: high in the cited contract/gate limits; provisional in this proposed
design; no independent council confidence and no production-readiness verdict.

Exact next bounded movement: in an already authorized environment with working
external CLI access, rerun the three independent seats on the same decision packet,
then one blinded challenge, append all full unaltered outputs here and replace this
preliminary section with an attributed synthesis. Recommended NEW SESSION for that
independent review; architecture / High reasoning. No implementation or pilot is
authorized by this artifact. Project state remains owned by the engineering session.

### SESSION CLOSE

Documentation outcome: partial, external council BLOCKED. Only this document is
changed. No tests added; runtime/UI behavior, dependencies, gates, migrations,
contracts and project state are unchanged. Validation: document/scope, relative-link and Markdown-fence checks PASS;
`git diff --check` and `git diff --cached --check` PASS;
`python3 tools/privacy/repository_privacy_check.py` PASS (2,031 files scanned,
zero findings). State consistency was not revalidated because state files were
unchanged and explicitly excluded. Frontend/Java/device tests
are not applicable to this documentation-only change and were not run. Local commit
only; push, PR, merge and deployment remain blocked by scope. No host/device access
occurred. The next movement is the external architecture review described above,
not implementation based on this unfinished council.
