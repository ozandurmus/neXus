# UI 2.0 — B1-4: Collection engine core contract

**FROZEN — 2026-09-12, under the Product Owner's written authorization of
2026-09-12. Read as amended by `docs/design/UI2_0_B1_ADJUDICATION_2026_09_12.md`, which adjudicates twelve
cross-contract findings and answers this document's open items.**

This is the mechanical implementation contract for
`ui2_b1_04_collection_engine_core`, `UI2_0_DEVELOPMENT_WORKFLOW.md` §5
Phase B1 row 4: "Collection engine core: capability registry, step
executor with the `C2` crash-safe job record, parser framework, evidence
writer; only the transport the first capability needs is implemented (SSH
exec); PAN XML API and SFTP adapters are defined as interfaces and built in
their slices (Astra 5.3)." It writes no `ui2/` source and issues no DDL; it
fixes what the implementing movement builds and how its tests must fail.
Rows 4b/5/6 and the B1 exit criterion ("an operator can log in, pick a
device, run a read collection, watch it, see the evidence, and see the
audit trail — all in Java, no Line-1 process anywhere") consume this
document; none of their own scope is re-decided here.

## 1. Scope and authority

This movement contacts **no real device**. Every test exercising the SSH
adapter runs against a container-hosted SSH endpoint or an in-process test
double. No capability spec is extracted or filled (`B1-5`/`C6`); no
`gate_registry` row is authored (`C4` §1.2/§3.2); no device is enrolled
(`B1-4b`). The only transport implemented is `ssh_exec`; PAN XML API and
SFTP/SCP are Java interfaces with zero implementation, per workflow §5 row
4 — a capability declaring either compiles and stays `CAP-SPEC`/
`CAP-OFFLINE` (`C4` §3.5) but never reaches `EXECUTING`, because no adapter
is wired for it (§6). Nothing here authorizes device execution beyond a
container-hosted double (`UI2_0_BASELINE_CONTRACT.md` A-1). Every action
this movement's own tests trigger is `CLASS_0_READ`
(`utils/action_taxonomy.py`); no code path here is browser-reachable
(`AGENTS.md` "No Browser → device path").

**Deferred, not re-decided:** spec content/gate authorship/fixtures (`C6`,
`B1-5`); device/credential rows and enrollment (`B1-4b`); schedules, Run
Now, `E1`–`E6` (`B1-3`, `C2` §7); backup/artefact model (`C7`); the
PAN/SFTP adapter implementations (later slices).

## 2. Module placement

Per `UI2_0_B1_01A_PLATFORM_SKELETON_CONTRACT.md` §2 (module map) and §3's
`DIR-3`/`DIR-6` rules:

| Component | Module/package | Rule kept |
|---|---|---|
| Registry model, gate resolution, step-kind enum | `capability-registry` (`.capability`) | `DIR-6`: no dependency on `service`/`worker`/`scheduler`/LDAP/transport |
| `C2` job record, state machine, leasing/fencing, precheck interface | `job-engine` (`.jobs`) | `DIR-3`: no dependency on `worker`/`service`/`scheduler`/transport |
| **Transport port** (`DeviceTransport`) | `job-engine` (`.jobs.transport`) | port lives with the consumer, keeping `job-engine` transport-free |
| **`ssh_exec` adapter** (only implementation shipped) | `worker` (`.worker.transport.ssh`) | `worker`→`job-engine` allowed; reverse forbidden (`DIR-3`) |
| Parser framework | `job-engine` (`.jobs.parsing`) | pure function over step output; no transport, no persistence |
| Evidence writer | port in `.jobs.evidence`, jOOQ impl in `persistence` | `DIR-7`: domain never depends on jOOQ/Flyway/JDBC directly |
| Step executor | orchestration in `.jobs.executor`; wiring in `worker` | `worker` wires the concrete adapter per `transport.kind`; executor logic is transport-agnostic |

**Why the port sits in `job-engine`.** `C2` §1.2 needs only "a kind, a gate
reference, a timeout, an expect/validation result"; `C4` §1.2 states the
registry "never authorizes execution... on a device." The port lets the
executor run any adapter without knowing which one; `worker` alone may both
depend on `job-engine` and instantiate a concrete adapter — the seam
`DIR-3` protects. A future PAN/SFTP adapter implements the same port;
`job-engine` never changes.

## 3. The capability registry

At startup the loader reads every committed capability spec and, per
capability: (1) validates every step's `kind` against `C4` §2.3's closed
nine-member set, failing load with `STEP_KIND_NOT_IN_CLOSED_SET` otherwise
(`C4` §7-1); (2) resolves every `gate_reference` by `C4` §3.3's exact-match
algorithm — zero rows → `UNKNOWN: requires gate entry`; more than one row
on the same key → hard load-time error (`C4` §3.3-6, §7-4); exactly one
`SIGNED_OFF` row → `KNOWN`, with `action_class` taken from the gate row,
never the spec author's declaration, a mismatch being a load-time failure
(`C4` §3.3-5); (3) computes an **execution-eligible** boolean = AND over
every step (`finally` included) of `gate ∈ {KNOWN, NOT_APPLICABLE}` (`C4`
§3.5); (4) rejects `sftp_put` unconditionally and `restore_push` whose
`action_class` is not `controlled-restore-write` (`C4` §2.3, §3.3-7); (5)
rejects `ssh_interactive` specs lacking the shell-state-dependency evidence
`C4` §5.2/§7-9 requires.

A capability loads, compiles and runs its parser against fixtures
regardless of its execution-eligible flag (`C4` §3.5); only the flag gates
whether the claim query's eligible `job_type` set includes it. **A
capability whose gate is unresolved** registers successfully, its flag is
`false`, and a job created directly against it (bypassing normal admission,
to test the boundary) stays `REQUESTED` forever — no new `C2` §6 check is
added; `C4` §3.5 already states it: the capability "is not a member of the
set `C2` claim-time admission is allowed to dispatch, by construction."

## 4. The step executor

**Lease acquisition** is exactly `C2` §4.1's one atomic
`UPDATE ... WHERE ... RETURNING` (`FOR UPDATE SKIP LOCKED`), restricted to
the execution-eligible `job_type` set; no read-then-write claim path exists
anywhere (test-enforced, §8-1). **Heartbeat/fencing**: while `EXECUTING` the
executor extends its lease every 20s against a 60s lease (`C2` §4.3's
proposed defaults, unchanged here — tuning is `C2`'s own open item); every
write after claim carries `WHERE lease_epoch = :claimed_epoch` (`C2` §4.2),
and a zero-row write is the executor's sole signal to stop — it never
inspects `lease_expires_at` directly.

**State machine**, restated as trigger → persisted effect for
implementability:

| From | Trigger | Effect |
|---|---|---|
| `REQUESTED` | claim query selects row | `CLAIMED`; lease fields set atomically |
| `REQUESTED` | operator cancel, pre-claim | `CANCELLED`, audited |
| `CLAIMED` | all `C2` §6 checks pass | `EXECUTING`; `precheck_results` appended in order; no contact yet |
| `CLAIMED` | any `C2` §6 check fails | `REJECTED`; list truncated at the failing check |
| `CLAIMED` | cancel, still legal | `CANCELLED` |
| `CLAIMED` | lease expiry, no attempt row | reconciler → `REQUESTED`, fresh epoch (`C2` §4.4-1) |
| `EXECUTING` | attempt row committed, boundary `NO`, before send | row committed; state unchanged (`C2` §5.1) |
| `EXECUTING` | boundary flipped `YES` in same transaction as send | row committed — the durable fact the crash matrix depends on |
| `EXECUTING` | expectation matched | outcome set; advance to next step/`finally`/`COMPLETED` |
| `EXECUTING` | definite non-ambiguous failure | `FAILED`, `terminal_reason` named |
| `EXECUTING` | expiry, every attempt boundary `NO` | reconciler → `REQUESTED`; restarts from `connect`, no mid-sequence resume (`C2` §4.4-2) |
| `EXECUTING` | expiry/crash, some attempt `YES` unconfirmed | reconciler → `OUTCOME_UNKNOWN`; never reclaimed (`C2` §4.4-3, §8 pts 3–4) |
| `EXECUTING` | class-0 pre-response failure, retry budget remaining | new attempt row, same `step_index`, `attempt_number+1`; stays `EXECUTING` (`C2` §5.3) |
| `OUTCOME_UNKNOWN` | `job_reconciliation` row written | `RECONCILED` — only legal exit; never re-contacts the device (`C2` §3.5) |

Every other `(from, to)` pair is illegal; each state-affecting `UPDATE`
carries `WHERE state = <expected>`, so an illegal transition affects zero
rows rather than silently succeeding (`C2` §9-1) — the executor never issues
an unconditional state `UPDATE`.

**Duplicate suppression** operates at two layers: claim-level
(`SKIP LOCKED` + fencing make double-claim and zombie-writes structurally
impossible, `C2` §4.1–§4.2) and request-level (server-generated idempotency
key at admission, out of scope here but depended upon — `C2` §9-12). The
executor's claim/execute loop is indifferent to how many `REQUESTED` rows
exist; preventing duplicate rows for "the same operation" is an
admission-time property this movement consumes, not re-derives.

**`OUTCOME_UNKNOWN` and no automatic retry.** Once a job is
`OUTCOME_UNKNOWN`, the claim query's `WHERE state = 'REQUESTED'` predicate
excludes it forever — no code path here writes `REQUESTED` onto it. **No
component this movement ships — executor, reconciler, scheduled job, or
admin action — ever issues a second device contact for that `job_id`.**
Resolution is `C2` §3.5's `job_reconciliation` mechanism (a human decision,
never a worker action) or a brand-new job with its own identity and, if the
old one is still open, an explicit `supersedes_job_id` — both out of scope
here, but the "no automatic second write" guarantee is a database predicate
this movement's claim statement enforces, not a policy merely documented
elsewhere.

## 5. The transport port and the SSH exec adapter

```java
package com.securityexpert.nexus.ui2.jobs.transport;
public interface DeviceTransport {
    ConnectResult connect(ConnectionTarget target, ConnectSpec spec, Duration timeout);
    ExecResult exec(TransportSession session, ExecSpec spec, Duration timeout);
    FetchResult fetch(TransportSession session, FetchSpec spec, Duration timeout);   // sftp_get/scp_get
    XmlApiResult xmlApiCall(ApiTarget target, XmlApiSpec spec, Duration timeout);    // PAN XML API
    void disconnect(TransportSession session);
}
```
(`fetch`/`xmlApiCall` declared, not implemented, at this movement.)

Every method returns a result value, never throws for an expected
device-side outcome (non-match, refusal) — an exception is reserved for
what the transport itself cannot classify. `fetch`/`xmlApiCall` are declared
now so a capability using them compiles from day one; both throw
`TransportNotImplementedException`, and the worker's startup wiring fails
fast for any execution-eligible capability whose transport has no
registered adapter — never a per-job surprise (§8-12).

**What `ssh_exec` does:** one authenticated connection per `connect`; each
`exec` opens its own one-shot `exec_command` channel, no persistent shell,
no shared mutable state (`C4` §5.1, §2.3). It authenticates via the
resolved `execution_credential_ref` (opaque, never a raw secret, `C1` §6);
verifies the host key against the capability's `trust_rule_ref` before any
command is sent — a mismatch is a definite `ConnectResult` failure, never
`OUTCOME_UNKNOWN`, since no mutation boundary was approached (`C2` §8 pt
2); evaluates `connect`'s expectation as authentication plus optional
banner match only, **never** a shell-prompt regex (`C4` §5.1, §7-10); may
reuse one connection across `exec` steps without ever depending on shell
state left by a prior step (`C4` §5.1).

**What it does not do — a transport never decides policy:** no knowledge of
`action_class`, retry rules, or job state; it reports what happened and the
executor/registry decide the rest, so a future adapter changes no executor
logic. It does not retry (the executor issues a fresh call after a new
attempt row commits); it does not persist anything (the executor writes the
pre-contact row first, `C2` §5.1); it does not interpret output beyond a
supplied regex (§6 derives facts). **Host key trust:** production SSH
requires a trusted host key (`AGENTS.md` "Check Point"); the adapter never
defaults to accept-any or trust-on-first-use. **Timeouts:** every call is
bounded by the registry's resolved `timeout_s` (from the gate row, `C4`
§6) or, for `connect` (gate-`NOT_APPLICABLE`), an adapter default named as
an open item (§11-4); a timeout is never success (`C2` §5.2), a
deterministic send-time boolean, never inferred after the fact.

## 6. The parser framework

The framework is a pure boundary: `(step output, capability's parser
semantics) -> ParsedStepResult`, invoked after a transport call returns and
before `job_step_attempt.outcome` is written. It supplies the
expectation-gate mechanics common to every capability (anchored matching,
fail-closed on ambiguity, timeout-never-success, captured-variable
validation, `C4`/design §6.3) once; a capability's own parser supplies only
its regex/field-extraction semantics.

**Where `UNKNOWN`/`NOT_APPLICABLE` come from**, per `AGENTS.md`'s
UNKNOWN/fail-closed law ("prefer `UNKNOWN`... over fabricated certainty")
and vendor-semantics law ("a command returning output does not prove the
parser understands it"): `UNKNOWN` when the output does not let the parser
establish a fact with confidence — absent pattern, empty output, or a
vendor quirk whose effect on this subset is itself `UNKNOWN` at the spec
level (`C6` §4.1's channel-drain example) — persisted as `NULL`, never a
sentinel (`C1` §3.4). `NOT_APPLICABLE` only when a fact is structurally
excluded (a version that never emits it, a step kind that cannot produce
it); an unsure parser must report `UNKNOWN`, never guess `NOT_APPLICABLE`.

**A parse failure is never silently an empty result.** The framework
type-distinguishes three outcomes: `matched(facts)` (expectation met,
facts derived, each independently stated); `expectationUnmet(...)` — a
definite failure (`FAILED`, `STEP_EXPECTATION_UNMET`), proving a negative,
not nothing; `ambiguous(reason)` — the parser cannot tell
(`STEP_AMBIGUOUS_RESPONSE`, contributing to `OUTCOME_UNKNOWN` past the
mutation boundary). An uncaught parser exception is `ambiguous`
(`STEP_PARSE_ERROR`), never coerced into `matched(emptyList())` — no code
path collapses these three into one outcome (§8-9).

## 7. The evidence writer

**Persisted**, in one transaction with the step's terminal attempt write:
one `provenance_records` row (`run_id`, `step_id`, `parser_version`,
`capability_version`, `capture_artifact_id` when fixture-sourced,
`source_location` as a locator never raw text, `sanitized_fragment` only
when permitted, `fingerprint_sha256` of the discarded raw response — `C1`
§5); the capability's own projection row with `provenance_id` `NOT NULL`
(`C1` §3.4: no projection row without one), every derived fact column
nullable, `UNKNOWN` as `NULL`; and the `job_step_attempt`
outcome/byte/line/fingerprint/captured-variable fields (`C2` §5.1).

**Sanitized:** `sanitized_fragment` is populated only when the
`evidence_shape_ref` explicitly permits one (`C1` §4); by default none is
written, and when present it is bounded/redaction-filtered, never a byte
slice of the raw response.

**Never persisted:** the raw response beyond that bound (`discard_raw:
true`, `C4` §2.2; `C1` §3.3) — discarded from memory once the parser and
evidence writer have run, per the raw-evidence law; any credential/secret
(only an opaque `execution_credential_ref` is stored, `C2` §5.4/`C1` §6);
any raw hostname/serial/address outside its own tokenized column (`C1` §7,
DLP-gate enforced at commit).

**Addressing:** evidence is addressed by `provenance_id`, never a raw
device identifier — the read chain is `job_id → job_step_attempt →
provenance_id → provenance_records → (optionally) the projection row`,
matching `C1` §5: a UI may show a fingerprint and that raw content was
discarded, never a "view raw" affordance or an implication it is
retrievable.

## 8. Test specification

1. **`ClaimIsAtomicNoReadThenWriteTest`** — fails if any code path issues a
   `SELECT` then a separate `UPDATE` on `jobs.state`.
2. **`MultiWorkerClaimSafetyTest`** [**container**] — N seeded jobs, M
   racing workers; fails on any double claim or non-increasing `lease_epoch`.
3. **`FencingTokenRejectsZombieWriterTest`** [**container**] — a re-claimed
   job's original worker attempts a stale-epoch write; fails if it
   succeeds or the worker keeps running afterward.
4. **`LeaseExpiryBranchesByMutationBoundaryTest`** [**container**] — three
   expiry scenarios (no attempt row / all boundary `NO` / one `YES`
   unconfirmed); fails if any resolves to the wrong state.
5. **`WorkerKilledMidStepLandsInOutcomeUnknownNoSecondContactTest`**
   [**container**; SSH adapter against a **container-hosted SSH endpoint**,
   never a real device] — a test double commits the boundary, the worker
   is killed before recording an outcome; fails unless the job settles
   `OUTCOME_UNKNOWN`, is never reclaimed, and the double is invoked exactly
   once.
6. **`LeaseExpiryIsNotDoubleClaimedTest`** [**container**] — two workers
   race a naturally expiring lease; fails if both claim the same `job_id`.
7. **`StepAttemptCommittedBeforeTransportInvocationTest`** — fuzzed failure
   injection; fails if any run's transport call precedes its boundary
   commit.
8. **`ReadClassBoundedRetryTest`** — two transient failures then success;
   fails unless three same-`step_index` attempt rows exist and a fourth
   failure yields `FAILED`, never `OUTCOME_UNKNOWN`.
9. **`ParseFailureDistinctFromEmptyResultTest`** — `expectationUnmet`,
   `matched(emptyList())`, an uncaught exception; fails if any two share an
   `outcome`/`error_class`.
10. **`GateUnresolvedCapabilityNeverExecutesTest`** — a capability with one
    `UNKNOWN` gate; fails if it does not load/pass parser tests, or is ever
    claimed across 1000 polls.
11. **`SshExecTrustRuleRejectsUntrustedHostKeyTest`** [**container**,
    untrusted key on a container-hosted endpoint] — fails if `connect`
    succeeds or the outcome is ambiguous rather than definite.
12. **`UnimplementedTransportFailsAtStartupNotPerJobTest`** — a capability
    declaring `xml_api_call`/`sftp_get`; fails if it reaches `CLAIMED`
    before the startup wiring check fails.
13. **`NoRawOutputPersistedTest`** — fails if any persisted column carries
    a raw-response slice beyond the declared bound.
14. **`ConnectStepExpectationRejectsPromptRegexUnderSshExecTest`** — fails
    if a shell-prompt `connect` regex validates under `ssh_exec`.

**Need a container runtime:** tests 2, 3, 4, 5, 6, 11 (Testcontainers
PostgreSQL; a container-hosted SSH endpoint for the adapter tests). **Need a
real device:** none — the PO validation session (workflow §5 row 6,
`CAP-VALIDATED`) is the first point that does, out of this movement's scope.

## 9. Acceptance criteria

- **AC-1.** `DeviceTransport` is declared only in `job-engine`; exactly one
  implementation (`ssh_exec`) exists, in `worker`.
- **AC-2.** Neither `job-engine` nor `capability-registry` depends on
  `worker`/`service`/`scheduler`/LDAP/any transport implementation
  (`DIR-3`/`DIR-6`, extended `Ui2ArchitectureTest`).
- **AC-3.** The claim statement is `C2` §4.1's single atomic
  `UPDATE ... RETURNING`; no alternate claim path exists (test 1).
- **AC-4.** State transitions match §4's table; every other pair is refused
  at the persistence layer (`C2` §9-1).
- **AC-5.** A worker killed mid-step lands the job in `OUTCOME_UNKNOWN` with
  no second contact, ever (test 5).
- **AC-6.** A racing lease is claimed by exactly one worker (test 6); a
  zombie worker's fenced write affects zero rows (test 3).
- **AC-7.** A parse failure is type-distinguishable from a correctly parsed
  empty result at every persisted layer (test 9).
- **AC-8.** A capability with an unresolved gate is offline-buildable and
  execution-ineligible, proved together (test 10).
- **AC-9.** No raw device byte beyond a capability's declared bound is
  ever persisted (test 13).
- **AC-10.** `ssh_exec`'s `connect` expectation is auth-plus-banner only; a
  shell-prompt regex fails validation (test 14).
- **AC-11.** An unimplemented transport fails at worker startup, never
  per-job (test 12).
- **AC-12.** Every mutation this movement's container-runtime tests perform
  is captured by `C1`'s audit trigger under the executor's own actor/action
  context — no test bypasses it for convenience.

## 10. Validation plan

```
./ui2/gradlew -p ui2 unitTest
./ui2/gradlew -p ui2 integrationTest --tests "*Claim*" --tests "*Lease*" --tests "*Fencing*" --tests "*OutcomeUnknown*" --tests "*SshExec*"
./ui2/gradlew -p ui2 architectureTest
./ui2/gradlew -p ui2 check
podman build --file ui2/Containerfile --tag nexus-ui2:local ui2
python3 -m pytest -q -p no:cacheprovider tests/test_architecture_convergence.py tests/test_cold_start_budget.py
python3 scripts/repository_privacy_check.py
```

## 11. Worker route and effort

**Sonnet 5, extended thinking (high).** This movement designs a new
execution engine and a transport-security boundary reconciling two frozen
contracts (`C2`, `C4`) into one implementable Java shape — new architecture
and a security boundary, per `CLAUDE.md`'s routing rule. A later, purely
mechanical follow-up (e.g. wiring the PAN adapter) would be `Sonnet 5,
normal`, implementing against this document's frozen port, not designing it.

**Open items for the Product Owner:**

1. **Capability spec file format** (§3) is this document's own placeholder
   (a committed format such as YAML) — neither `C4` nor `C6` fixes a
   serialization, only the schema; confirm or redirect before `B1-5`.
2. **Lease/heartbeat/retry-budget defaults** are `C2`'s own open items
   (`C2` §10-1/2), first compiled into code here; not re-litigated.
3. **Adapter module placement for the second transport** (inside `worker`
   or a dedicated `transport-adapters` module) is left to that slice's own
   movement; `job-engine`'s port-only stance is unaffected either way.
4. **`connect` timeout default under `ssh_exec`** has no gate-sourced value
   (`connect` is gate-`NOT_APPLICABLE`); a default is the PO's to set or
   delegate to `B1-4b`'s device-registration defaults.
5. **Where the `Ui2ArchitectureTest` extension is documented** — a formal
   amendment to `B1-1a` (the frozen `B1-1` successor), or simply extended in
   place — is left open;
   the assertions (AC-1–AC-3) are fixed regardless.

## 12. Cross-references

`UI2_0_DEVELOPMENT_WORKFLOW.md` §5 B1 rows 4/4b/5/6, exit criterion;
`UI2_0_C2_JOB_EXECUTION_CONTRACT.md` (FROZEN); `UI2_0_C4_CAPABILITY_
REGISTRY_GATE_RESOLUTION_CONTRACT.md` (FROZEN); `UI2_0_C6_CAPABILITY_
EXTRACTION_CONTRACT.md` (FROZEN, spec template only); `UI2_0_C1_PLATFORM_
SCHEMA_CONTRACT.md` (DRAFT, same slice) §3.3–§3.5, §5; `UI2_0_B1_01_
SKELETON_CI_DOCKER_CONTRACT.md` §2 (module map, `DIR-3`/`DIR-6`);
`UI2_0_B1_02_SCHEMA_V1_CONTRACT.md` (`V1`, whose `V<n>` successor
numbering is inherited as an open item); `UI2_0_B1_03_IDENTITY_SESSIONS_
CONTRACT.md` §7 (`E7`/`C2`'s six-check battery is this contract's scope);
`AGENTS.md` (action taxonomy, command gate, UNKNOWN/fail-closed and vendor
semantics laws, raw-evidence law, "No Browser → device path," host-key
note); `utils/action_taxonomy.py`.
