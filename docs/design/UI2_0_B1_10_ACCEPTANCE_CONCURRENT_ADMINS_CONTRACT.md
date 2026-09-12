# UI 2.0 — B1-10: acceptance scenario A — concurrent admins contract

## Status

**FROZEN — 2026-09-12**, under the Product Owner's standing written
authorization to approve, revise or cancel UI 2.0 B1 contracts.

This is the acceptance contract for `ui2_b1_10_acceptance_scenario_
concurrent_admins`, `UI2_0_DEVELOPMENT_WORKFLOW.md` §5 B1-10: "acceptance
scenario A: three admins on one device, single-session takeover/refuse,
correlation visible." It fixes one end-to-end run, its passing evidence,
and what that run does not prove. It writes no `ui2/` source and issues no
Flyway SQL. It restates nothing `UI2_0_B1_03_IDENTITY_SESSIONS_CONTRACT.md`
(`B1-3`) already owns; every mechanism cited here is `B1-3`'s, including its
**Correction C-1** (a takeover writes exactly **two** correlated audit
rows, one per affected session, not one — this contract asserts the
corrected behaviour, not the withdrawn one).

## 1. Why an acceptance scenario, not another unit test

`B1-3` §8 already proves the single-active-session invariant under SQL
bypass (test 1), under two concurrent HTTP logins (test 2), the takeover
transition (test 3, as corrected), refuse-leaves-prior-untouched (test 4),
and refuse-by-inaction (test 5) — each in isolation, each against one or
two sessions. No `B1-3` test runs **three** identities against **one**
device concept end-to-end through the actual HTTP surface a real operator
uses (`POST /login` → `409` → `POST /login/resolve`), and no `B1-3` test
asserts that the correlation id tying one logical takeover event together
is the *same* value across all three of `sessions`, `authz_decisions` and
`audit_log` for both affected rows. A defect this scenario catches that no
`B1-3` unit test would: the takeover and refuse code paths individually
pass their own unit tests but, run against each other under real
concurrency with a third uninvolved admin also active, produce audit rows
that are correlated *within* one table but not *across* tables (e.g. the
trigger's `audit_log.correlation_run_id` matches between the two `sessions`-row audit
entries but the `authz_decisions` row created by the third admin's
unrelated action is accidentally stamped with the same id by a shared
request-scoped context leaking across concurrent requests). That
cross-table correlation, and its absence under concurrent unrelated
traffic, is what this scenario exists to catch.

## 2. Scenario

**Actors.** Three identities bound to `role:security_admin` via
`role_bindings` (`B1-3` §6): `A1`, `A2`, `A3`. "One device" here means one
identity's session lifecycle (`B1-3` §5 governs session *per actor
fingerprint*, not per device — `B1-4b`'s device rows are out of scope,
`B1-3` §1 explicit deferral); the scenario's "one device" is the single
shared identity `I` whose session slot the three admins contend for, which
is what `ux_sessions_one_active_per_actor` actually keys on. `A3` is the
uninvolved, concurrently-active control actor proving isolation, not a
party to `I`'s session contention.

**Starting state.** `I` has no `ACTIVE` session row. `A3` already holds an
`ACTIVE` session under its own `actor_fingerprint` and is issuing unrelated
`CLASS_0_READ`-gated requests throughout the scenario (its own `authz_
decisions` rows), never touching `I`'s identity.

**Ordered/concurrent steps:**

1. `A1` logs in as `I` — `POST /login` succeeds, `S1` created `ACTIVE`.
   *(ordered: must complete before step 2 begins, so `S1` exists to
   contend over.)*
2. **Concurrent pair:** `A2` submits `POST /login` as `I` while `A3`'s
   unrelated read request (step "A3-noise", repeated throughout) is
   in flight. `A2`'s login observes `S1` `ACTIVE` and receives `409
   LOGIN_CONFLICT {conflict_token, prior_session}`. This pair is concurrent
   because the scenario's question is whether `A3`'s unrelated traffic ever
   contaminates `A2`'s conflict resolution or its resulting audit rows —
   an ordered-only run could not expose cross-request state leakage.
3. `A2` calls `POST /login/resolve {conflict_token, action=takeover}` —
   ordered after step 2 (the token from step 2 is required input). This
   transitions `S1`→`SUPERSEDED(by=S2)`, `S2`→`ACTIVE`, one transaction
   (`B1-3` §5 table).
4. **Concurrent with step 3's transaction commit:** `A3`'s noise request N3
   is issued so its commit window overlaps `S1`/`S2`'s transition — this is
   the case the correlation assertion must survive.
5. `A1`'s original session `S1` issues one more request (using its now-
   superseded cookie) — ordered after step 3 completes. It must fail `E1`
   with `401 SESSION_SUPERSEDED` (`B1-3` §7).
6. A fresh login attempt by `A1` as `I` — ordered after step 5 — receives
   `409 LOGIN_CONFLICT` against `S2` (now `A2`'s active session) and this
   time `A1` calls `POST /login/resolve {action=refuse}`. Per `B1-3` §5,
   refuse leaves `S2` unchanged; no `S3` is created; `409
   LOGIN_REFUSED_ACTIVE_SESSION` on the resolve call.
7. `A2`'s session `S2` issues a request immediately after step 6 — it must
   still be `ACTIVE` and succeed at `E1`, proving the refuse in step 6 left
   it untouched.

**The single question this run answers:** when three admins act on one
identity's session slot under real concurrency, with unrelated third-party
traffic overlapping the takeover transaction, does the correlation
evidence tying one logical takeover together stay correctly scoped to only
the two rows the takeover actually touched — never merging with, or being
merged into, the concurrently-running noise?

## 3. Passing evidence — named, queryable assertions

All assertions are direct SQL against the running PostgreSQL 16 instance
(per the carrier rule, §5) after the scenario completes; no assertion is
"the UI looked right."

- **PA-1 (structural invariant holds throughout).** At every point during
  the run, `SELECT count(*) FROM sessions WHERE actor_fingerprint =
  :I_fingerprint AND state = 'ACTIVE'` never exceeds 1 — sampled
  immediately before and after each step. (This is `B1-3` test 1/2's own
  guarantee, re-confirmed here as a precondition the rest of the scenario
  depends on, not re-proved as new.)
- **PA-2 (takeover row count and shape, C-1 corrected).** After step 3:
  exactly two rows in `audit_log` reference `session_id IN (S1, S2)` for
  this transition, both sharing one non-null `audit_log.correlation_run_id` value, both
  sharing `actor_fingerprint = I`'s new-session actor attribution and
  `action = 'session_takeover'` (or the equivalent closed action label
  `B1-3` §5 assigns), one row's `session_id = S1` with the update to
  `SUPERSEDED`, the other's `session_id = S2` with the `INSERT` of
  `ACTIVE`. Exactly two — not one (the withdrawn clause), not three or
  more (contamination).
- **PA-3 (correlation does not leak into concurrent noise).** Every
  `audit_log` row generated by `A3`'s noise requests (N-series) in the
  window overlapping step 3/4 carries a `audit_log.correlation_run_id` distinct from
  PA-2's value, and every `authz_decisions` row `A3` generates in that
  window carries `actor_fingerprint = A3` — never `I`'s. Fails if any N-row
  shares PA-2's `audit_log.correlation_run_id`, or if any `I`-attributed row appears
  under `A3`'s fingerprint or vice versa.
- **PA-4 (refused login leaves the active session provably untouched).**
  After step 6: `sessions` row for `S2` is byte-identical on every column
  except `last_seen_at`/`updated_at`-style bookkeeping columns to its state
  immediately after step 3 — specifically `state = 'ACTIVE'`,
  `superseded_by_session_id IS NULL`, `end_reason IS NULL`,
  `ended_by_actor_fingerprint IS NULL`. No `audit_log` row exists for `S2`
  between step 3's and step 7's, i.e. the refusal in step 6 generated zero
  rows against `S2` (it may generate its own row against the refused
  *attempt*, but never a `state`-column mutation row for `S2`, since none
  occurred).
- **PA-5 (superseded session fails closed).** Step 5's request using `S1`'s
  cookie returns exactly `401 SESSION_SUPERSEDED`, never `200`, never a
  different `401` reason code.
- **PA-6 (post-refuse session still serves).** Step 7's request against
  `S2` returns `200` (or whatever success status the exercised endpoint
  defines) at `E1`, proving `S2` was never invalidated by step 6's refused
  attempt.
- **PA-7 (correlation is queryable across all three tables named in the
  workflow line).** A single query joining `sessions.session_id ∈ (S1,
  S2)` → the two `audit_log` rows (PA-2) → any `authz_decisions` row
  produced by `A2`'s or `A1`'s own actions inside this scenario window
  resolves to `A2`/`A1`'s own `actor_fingerprint` exclusively — i.e. the
  scenario proves the correlation surface is queryable end-to-end, per the
  workflow line's "correlation visible" wording, not merely present inside
  one table.

## 4. What this scenario does not prove

- It does not re-prove the structural impossibility of two `ACTIVE` rows —
  that is `B1-3` test 1 (direct SQL bypass), a stronger and narrower proof
  this scenario does not repeat.
- It does not prove performance or throughput under load; three admins and
  one noise actor is a correctness fixture, not a concurrency-stress test.
- It does not exercise `role_bindings` self-grant refusal, LDAP bind
  failure modes, or any `E2`–`E6` gate beyond `E1` — those remain `B1-3`'s
  own test-15/16/1-13 scope.
- It does not touch a real device, a real LDAP directory, or `B1-4`'s job
  engine — "one device" in this scenario's title is the shared identity's
  session slot, never a `devices` row (`B1-4b`, explicitly out of scope
  here as it is for `B1-3`).
- It does not prove the correlation id's cryptographic or uniqueness
  properties beyond "distinct value, matching where it should match" — the
  generation mechanism (sequence, UUID, trigger-local) is `B1-3`'s
  implementation choice, unexamined here.

## 5. What the scenario adds beyond the structural index

`ux_sessions_one_active_per_actor` (a partial unique index) already
guarantees, by database construction, that `I` never holds two `ACTIVE`
rows — no scenario is needed to prove that; `B1-3` test 1 proves it more
directly (a bypass attempt) than any HTTP-level scenario could. What the
index does **not** guarantee, and what this scenario is the first
specification to demand evidence for, is that the **audit correlation
surface built on top of the index-enforced state machine stays correctly
scoped under real concurrent multi-actor traffic** — specifically that (a)
the corrected two-row takeover shape (C-1) holds under contention, not just
in a two-party unit test, (b) a third, unrelated, concurrently-active
admin's own audit/authz rows never share or corrupt the takeover's
correlation id, and (c) a refused takeover leaves the winning session's
row set provably byte-identical, not merely "still `ACTIVE`" (a weaker
claim `B1-3` test 4 already makes for the two-party case). If PA-3 and
PA-7 held trivially from the index alone, this document would say so and
recommend the row be dropped or downgraded to a restatement of `B1-3` test
2; they do not, because the index says nothing about `audit_log`/`authz_
decisions` correlation at all — that surface is application code (`B1-3`
§5's trigger scoping, §7's `decision_id` linkage), not a database
constraint, and is exactly where cross-actor contamination could occur
without the index ever being violated.

## 6. Carrier rule

Per `UI2_0_B1_01A_PLATFORM_SKELETON_CONTRACT.md` §5: this scenario is an
`integrationTest`-tier test acquiring its database through the same
fixture — `UI2_TEST_JDBC_URL` naming a real PostgreSQL 16 server, else a
Testcontainers `PostgreSQL` container. If neither carrier exists the test
**fails**, never skips. No container runtime is otherwise required: no
`ssh_exec`, no directory server, no device-adjacent transport is exercised
by this scenario (§4), so it needs nothing beyond the same PostgreSQL 16
carrier every `B1-3` integration test already needs. The three concurrent
actors are three application-level HTTP clients (or in-process request
simulations) against one `service` instance under test — no additional
container for "three admins."

## 7. Test specification

1. **`ThreeAdminsOneSlotCorrelationScenarioTest`** [needs PostgreSQL 16
   carrier, per §6] — executes §2's ordered/concurrent step sequence
   exactly, with `A3`'s noise thread running continuously from before step
   2 through after step 4; fails on any of PA-1 through PA-7.

No other new test is authored by this contract; the scenario is one test
class exercising real HTTP endpoints already specified by `B1-3`.

## 8. Acceptance criteria

- **AC-1.** PA-1 through PA-7 all hold in one continuous run of the
  ordered/concurrent step sequence in §2.
- **AC-2.** The scenario runs against the shared carrier fixture per §6,
  never against a mocked session store or a stubbed audit trigger.
- **AC-3.** No assertion in this document duplicates a `B1-3` §8 test;
  each PA-item names a cross-actor or cross-table property no single
  `B1-3` test asserts.

## 9. Worker route

**Sonnet 5, normal.** This is deterministic test authorship against two
already-frozen contracts (`B1-3`'s session/audit mechanics, C-1's
correction); no new architecture, schema, or security-boundary decision is
introduced.

## 10. Open items for the Product Owner

1. **The correlation column is not unknown; its per-request value is.**
   Corrected 2026-09-12: an earlier draft of this section left the whole
   mechanism `UNKNOWN` and named a `correlation_id` column that does not
   exist. `V1__initial_schema.sql` creates
   `audit_log.correlation_run_id`, indexes it
   (`idx_audit_log_correlation_run_id`), and `fn_audit_capture` populates it
   from `current_setting('app.correlation_run_id', true)` — the same
   transaction-local mechanism as `app.actor_fingerprint` and
   `app.action_id`. So `PA-2`/`PA-3`/`PA-7` bind to a real, indexed column
   and need no guessing.

   What is genuinely **UNKNOWN** is narrower: what value the service sets
   for `app.correlation_run_id`, and whether one `/login/resolve` call is one
   run id. `fn_audit_capture` reads the setting without requiring it — unlike
   actor and action, a missing `correlation_run_id` does **not** fail closed,
   so a takeover whose transaction never set it would write two audit rows
   both carrying `NULL`. `PA-2` therefore asserts a **non-null** shared value
   and fails on the null pair; that is the assertion that turns this open item
   into a detected defect rather than a silent pass. The value's format and
   the service code that sets it are `B1-3`'s implementing movement to
   settle.
2. **Whether "one device" should instead require a real `devices` row**
   once `B1-4b` lands is left open; this contract's reading (one shared
   identity's session slot) matches `B1-3`'s own session model, which is
   per-actor, not per-device row, and is the only reading the current
   schema supports.

No contradiction between this contract, `B1-3`, or `B1-3`'s Correction C-1
was found; PA-2 is written to the corrected two-row shape throughout.
