# UI 2.0 — B0/C3 identity, sessions and RBAC contract

## Status

**FROZEN — PRODUCT OWNER APPROVED, 2026-09-09** (platform contract freeze (C1–C6 + baseline directory), per `UI2_0_BASELINE_CONTRACT.md` §2 `FREEZE-SLICING`). Open items listed in this document's own open-items section are deferred to the movements they name; they do not reopen this freeze. Previous status: DRAFT — FOR PRODUCT OWNER FREEZE. Written under
`docs/design/UI2_0_BASELINE_CONTRACT.md` (FROZEN — PRODUCT OWNER APPROVED,
2026-09-09), turning `UI2_0_ARCHITECTURE_CONTRACT.md` §3 (RBAC is `D1`/`D7`,
never menu-hiding) and §7 (concurrent multi-admin, single active session per
identity) into a testable specification, amended by the baseline's
`DIRECTORY-POSTURE` (D-6) ruling. Runs concurrently with `docs/design/UI2_0_C1_PLATFORM_SCHEMA_CONTRACT.md`
(`C1`, MERGED as DRAFT — FOR PO FREEZE) and `docs/design/UI2_0_C2_JOB_EXECUTION_CONTRACT.md`
(`C2`, MERGED as DRAFT — FOR PO FREEZE). Nothing in this document is
implementation authority until its own status line reads `FROZEN`. This is a
**contract, not code**: no `ui2/` source, no Flyway migration file is written
or authorized here.

---

## 1. Scope, authority chain, and what this contract does not own

### 1.1 What this contract owns

LDAP bind (authentication); the single-active-session-per-identity rule and
its takeover/refuse semantics; role tokens → bindings → AD group references,
their persistence and encryption; the `DIRECTORY-POSTURE`-conditional
read-only directory service account, specified in full even though disabled;
RBAC enforcement (the visible-but-refused shape, the refusal HTTP contract,
audit linkage); the `E1`–`E7` gate chain as it runs on the HTTP layer, and
its composition boundary with `C2`'s pre-execution checks; and how `C2`'s
owner/approver/execution-identity fields resolve from this contract's
session/role model.

### 1.2 What this contract does not own

| Not owned here | Owner | This document's relationship to it |
|---|---|---|
| `jobs`, `job_steps`, `audit_log`, `devices`, `endpoints`, `credential_references`, `secrets_metadata`, `provenance_records`, `cp_inventory_projection` — table identity and column definitions | `C1` | `C1` §3.3 already names `jobs.submitted_by_actor_fingerprint` as "opaque fingerprint, **C3's actor identity shape**" and `audit_log.actor_fingerprint` as "from the session-scoped `app.actor_fingerprint` setting." This contract supplies that shape (§3.2 below: `principal_fingerprint`, an existing, already-established convention — not redefined here, only adopted) and the mechanism by which the Java transaction interceptor sets `app.actor_fingerprint`/`app.action_id` before a mutation (§6). It does not add, remove or retype a single column C1 has already sketched |
| Job record, state machine, leasing, `OUTCOME_UNKNOWN`, step execution, schedule optimistic concurrency | `C2` | `C2` §1.2 states its own scope begins at `E7`; "the request already carries a resolved actor and a `D7` outcome by the time a job row exists." This contract supplies the resolved actor and the `D7` outcome `C2` assumes; it does not touch a job row, a lease, or a step-attempt record |
| The capability registry, gate-record resolution, closed step-kind vocabulary | `C4` | Where this document must reference "a capability the RBAC layer gates access to," it names only the interface — an opaque `action_id`/`capability_id` with a required role token — and defers the capability's own shape to `C4` (`UI2_0_BASELINE_CONTRACT.md` sequencing note) |
| Backup-profile object model, approval workflow content beyond the actor identities involved, artefact store, restore execution | `C7` | This document supplies the actors (`§7`); `C7` supplies the profile/backup domain objects those actors act on |
| Device contact of any kind | Out of scope for every B0 contract | Nothing here authorizes execution of any capability on a device (`UI2_0_BASELINE_CONTRACT.md` acceptance sentence A-1) |

### 1.3 Tables this contract fully owns (DDL-level sketch)

`C1` §3.1 explicitly reserves and declines to sketch four tables: "Tables
this document deliberately does not sketch: `role_bindings`, `sessions`,
`actor_authz_state`, `authz_decisions` (`C3`)." This contract owns all four,
end to end — identity columns, lifecycle columns, and semantics — following
the same sketch discipline `C1` established for its own tables (§3 below):
illustrative DDL that fixes the load-bearing decisions; `B1-3` writes the
literal Flyway SQL and may add non-load-bearing columns without a
`C3`-successor movement, but may not remove, retype, or reassign ownership of
anything fixed here without one.

### 1.4 Authority chain (highest first)

1. `AGENTS.md` — durable constitution: identity law (opaque identifiers,
   never a corporate identity in application code), evidence laws,
   UNKNOWN/fail-closed law, sensitive-identity reporting law, privacy/DLP.
2. `docs/design/UI2_0_BASELINE_CONTRACT.md` (FROZEN) — binding direction and
   Phase 0 decisions this document must not reopen: `DIRECTORY-POSTURE`
   (D-6, conditional), acceptance sentence A-1.
3. This document, once its own status line reads `FROZEN`.
4. `docs/design/UI2_0_ARCHITECTURE_CONTRACT.md` (FROZEN 2026-09-12) §3
   (RBAC, role model, evaluation order, storage/audit) and §4
   (authentication and sessions); §4.5 carries the `M14 U-4` disposition
   §6 of this contract reads. This contract is the testable specification the
   baseline directs it to become.

   Repointed 2026-09-12 from `UI2_0_ARCHITECTURE_DESIGN.md`, now SUPERSEDED
   and historical only, never authority. Its status line said `DRAFT — NOT
   implementation authority`, and a DRAFT cannot stand in a FROZEN contract's
   authority chain (`AGENTS.md` "Authority hierarchy" item 2). Correction C-1
   below recorded the defect before it was fixed.
5. `docs/design/UI2_0_DEVELOPMENT_WORKFLOW.md` §5 B0-3 (this movement's own
   scope line) and B1 step 3 (the Java implementation this contract
   precedes).
6. `docs/design/UI2_0_COUNCIL_REVIEW_AND_SECOND_OPINION_BRIEF.md` — `SR-D5`,
   `SR-D6` (resolved by `C2` §7.2), `SR-D7`, `SR-D10`, `UX-D3`: the identity/
   session/authorization findings this document answers or explicitly
   leaves open (§9).
7. `utils/logger.py::principal_fingerprint` — the existing, unkeyed 12-hex
   SHA-256-prefix correlator this document adopts, not redefines (§3.2).
8. `docs/design/UI2_0_C1_PLATFORM_SCHEMA_CONTRACT.md` (concurrent movement,
   `C1`) — schema ownership rules (§2), the `secrets_metadata`/
   `credential_references` mechanism (§6.1/§6.2) this document's service
   account reuses, and the `audit_log` mechanism (`C1-1`) this document's
   own tables extend where stated (§3.5 below).
9. `docs/design/UI2_0_C2_JOB_EXECUTION_CONTRACT.md` (concurrent movement,
   `C2`) — `E7`/pre-execution checks (§6 below), owner/approver/execution
   identity (§7 below).

**Evidence and precedent consulted — not authority** (Correction C-1,
2026-09-12). `docs/design/M14_LOCAL_LDAP_AUTHORIZATION_ARCHITECTURE.md`
(DRAFT — discussion proposal, "not implementation authority" by its own status
line) is read here as the loopback-console **precedent** this contract amends
for a multi-admin server, one decision at a time, exactly as design §7.7
tabulates. It is **not authority**: it authorizes nothing in this contract and
settles no identity model, authorization outcome, command, schema or security
boundary for it. Where this contract cites an `LD-n` or `AG-n` id (§2.1, §2.2,
§2.3, §4.4, §5.1), the cited id names where the behaviour came from; the
normative clause is the one stated in this contract's own text, and where the
two disagree this contract wins. `M14`'s own open unknowns `U-1`…`U-4` stay
open as unknowns here and are not read as decided — §9 item 6 keeps `U-1`
explicitly open, and `U-4` is decided for UI 2.0 by design §7.7 (chain item
4), not by `M14`.

---

## 2. LDAP bind

### 2.1 Library and connection shape

**UnboundID LDAP SDK** (design §7.7/§8.3), one distinct usage per identity in
play:

- **Operator bind (login)** — a single, one-shot `SimpleBindRequest` per
  `POST /login`, over a TLS connection verified against the corporate CA
  bundle (`LD-6`, unchanged: hard failure before any network call on an
  unreadable trust-material bundle — mirrors `C1` §6.2's own preflight
  discipline for a secret file). The connection is opened, the bind is
  attempted, the group set is read (§2.3), and the connection is **closed
  immediately** — never pooled, never reused across logins, never held open
  between requests. Pooling a per-operator bind would mean holding a live
  authenticated directory connection per administrator indefinitely, which
  is exactly the resource/lifetime problem design §7.6 states a server
  cannot afford ("a server cannot keep one bound connection per
  administrator open for hours").
- **Service-account connection (re-validation adapter, `DIRECTORY-POSTURE`,
  §4.3)** — a small, bounded connection pool (proposed: min 0, max 4;
  tunable, non-load-bearing), because this connection performs the same
  narrow bind + search repeatedly on the re-validation interval (proposed
  15 minutes, `LD-3`) rather than once per human action. Idle connections
  are recycled after 60 seconds; a connection that fails a bind or a search
  is discarded, never retried on the same handle. **This pool exists in the
  contract but is inert while `DIRECTORY-POSTURE` stays disabled (§4.3):**
  no code path opens it until the corporate-policy verification is
  recorded.

### 2.2 Failure behaviour

| Failure | Behaviour |
|---|---|
| TLS trust-material bundle missing/unreadable at startup | fails closed: the component refuses to start (mirrors `C1` §6.2's `DEV.2.2` preflight pattern) |
| Empty password submitted at login | refused **before any LDAP call** — the RFC 4513 unauthenticated-bind trap (`AG-2`): most directories treat a simple bind with a non-empty DN and an empty password as an anonymous bind that "succeeds" without authenticating anyone. This contract's login path checks the password field is non-empty first and returns the same generic invalid-credentials response as a real bind failure (§2.4) if it is not |
| Directory bind fails (bad credentials, account disabled) | login refused; generic `401 INVALID_CREDENTIALS` (§2.4) — never distinguishes "unknown user" from "wrong password" (username-enumeration resistance, `SR-D7`) |
| Directory unreachable / TLS handshake fails during a login bind | login refused; `503 DIRECTORY_UNAVAILABLE` — distinct from `401`, because this is not a claim about the operator's credentials, it is a claim about the directory. Never silently retried against a cached credential (none exists) |
| Directory unreachable / service-account bind fails during periodic re-validation | that actor's `actor_authz_state` row is not refreshed; once its `valid_until` elapses, every action for that actor evaluates `AUTHZ_NOT_EVALUATED` (§5) — never `DENIED`, never a fall-through to the last-known-good answer past `valid_until` (`AG-11`) |
| Directory reachable, empty result / actor not found on re-validation | treated identically to a bind/search failure above — an empty group set on re-validation is not distinguished from a directory outage at the authorization-outcome level (both starve `actor_authz_state`'s freshness); the distinction, where the adapter can tell them apart, is recorded only in the outcome's `reason_code` for operability, never surfaced as a different authorization outcome |

### 2.3 No local password storage

The operator's password exists in the Java service's memory for the
duration of exactly one bind call:

- held in a mutable `char[]`, never a `String` (a `String`'s immutability
  means its backing memory cannot be deterministically zeroed and may be
  interned or copied by the JVM; `AG-J1`-style tests assert no `String`
  constructor or method receives the raw password anywhere in the login
  path);
- zeroed immediately after the bind call returns, whether it succeeded or
  failed;
- never written to disk, never included in a log line or an exception
  message (component/purpose name only — the same rule `C1` §6.2 states for
  every other secret), never held for a re-bind (`LD-1` carried over: "the
  password is used for exactly one bind and then cleared");
- the **session cookie** is the only credential that persists past the
  login request (§3.2) — never the directory password, in memory or at
  rest, at any point after the login response is sent.

### 2.4 Rate limiting, lockout, and the `SR-D7` disposition

A per-source and per-identity throttle runs **before** any LDAP call
(design §7.3 step 1), closing `SR-D7` ("binding as the operator from a
network-reachable login form is a failed-bind amplifier: AD lockout,
username enumeration"):

- **Per-source throttle**: a bounded number of login attempts per source
  class per minute (proposed: 10/minute; tunable), fail-closed under abuse
  — exceeding it returns `429 LOGIN_RATE_LIMITED` without attempting a bind.
- **Per-identity throttle**: a bounded number of failed attempts per
  identity per window (proposed: 5 per 5 minutes) before this
  application's own layer refuses further attempts for that identity with
  the same `429`, **not a permanent application-side lockout** — the
  throttle window expires and normal attempts resume. This application
  introduces no lockout beyond a bounded, self-expiring throttle; it never
  replicates or amplifies the directory's own account-lockout policy.
- **Uniform error surface**: every login failure — unknown identity, wrong
  password, empty password, directory-side lockout, this application's own
  throttle exhausted for the *identity* dimension — returns the same
  `401 INVALID_CREDENTIALS` body with no field distinguishing the cause,
  except the rate-limit case, which is `429` (a distinct, non-identity
  -revealing signal: a `429` says "too many attempts from somewhere," never
  "this specific account is locked"). No response body, header, or timing
  characteristic this contract specifies is allowed to differ based on
  whether the submitted identity exists in the directory.
- **AD lockout interaction, documented, not solved**: if the corporate
  directory locks the account after its own threshold, the bind fails and
  this application surfaces the identical `401 INVALID_CREDENTIALS` a wrong
  password would produce — the operator cannot tell, from this
  application's response, that they are AD-locked rather than mistyped.
  This is accepted as the correct behaviour (revealing "you are AD-locked"
  would itself be a username/state-enumeration signal); operators are
  expected to resolve an AD lockout through the corporate directory's own
  channel, unchanged by this contract.

---

## 3. Session model

### 3.1 `sessions` — DDL sketch

```sql
CREATE TABLE sessions (
    session_id          TEXT        PRIMARY KEY,   -- SHA-256(raw 256-bit cookie value), hex-encoded; the raw value itself is never persisted
    actor_fingerprint    TEXT        NOT NULL,       -- principal_fingerprint(bind_dn) -- see §3.2, existing convention, not redefined here
    csrf_secret          TEXT        NOT NULL,       -- opaque, minted at session creation, never logged
    state                TEXT        NOT NULL CHECK (state IN ('ACTIVE','SUPERSEDED','EXPIRED','REVOKED')),
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_seen_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    idle_deadline_at     TIMESTAMPTZ NOT NULL,        -- recomputed on every request that passes E1 (§3.4)
    absolute_expires_at  TIMESTAMPTZ NOT NULL,        -- fixed at creation, never extended (§3.4)
    superseded_by_session_id TEXT   REFERENCES sessions(session_id),  -- set only on takeover
    ended_by_actor_fingerprint TEXT,                   -- set only on REVOKED-by-security_admin
    end_reason           TEXT                          -- closed vocabulary: 'login_elsewhere' | 'idle_timeout' | 'absolute_lifetime' | 'revoked_by_admin' | 'access_group_lost'
);

-- The structural enforcement of "one identity, at most one active session":
CREATE UNIQUE INDEX ux_sessions_one_active_per_actor
    ON sessions(actor_fingerprint) WHERE state = 'ACTIVE';
```

The partial unique index is what makes the single-active-session rule
**structurally impossible to violate**, not merely application-checked, in
the same spirit as `C1-1`'s audit trigger and `C2`'s fencing token: even a
buggy or racing application path cannot commit two `ACTIVE` rows for the
same `actor_fingerprint` — the second `INSERT` fails the constraint. A
takeover's `UPDATE ... SET state = 'SUPERSEDED' ...` followed by
`INSERT ... state = 'ACTIVE'` for the new session runs in one transaction so
the index is never transiently violated or transiently absent.

### 3.2 `actor_fingerprint` — adopted, not redefined

`principal_fingerprint` (`utils/logger.py`) is the existing, established
correlator: a 12-hex-character, **unkeyed** SHA-256 prefix of the bind DN.
This contract adopts the identical algorithm in Java (the pattern, not the
Python code — `RUNTIME-DIRECTION` P-4, reference only) so that
`sessions.actor_fingerprint`, `role_bindings.created_by_actor_fingerprint`,
`jobs.submitted_by_actor_fingerprint` (`C1`) and `audit_log.actor_fingerprint`
(`C1-1`) all speak the same value for the same directory identity. **This
contract does not change the algorithm** — `SR-D10`'s finding that an
unkeyed fingerprint is reversible from a DN list stays an open item for the
Product Owner (§9), not something this document decides by quietly keying
or re-deriving it.

### 3.3 The single-session rule, precisely (design §7.4, restated as a test matrix)

| Situation | Transition | Causer |
|---|---|---|
| identity `I` has no `ACTIVE` row; logs in | `(none)` → `ACTIVE` | login flow |
| `I` has `ACTIVE` `S1`; logs in, server detects the conflict, operator resolves **takeover** (§3.4) | `S1`: `ACTIVE` → `SUPERSEDED(by=S2)`; `S2`: `(none)` → `ACTIVE`, in one transaction | login-resolve flow |
| `I` has `ACTIVE` `S1`; logs in, operator resolves **refuse**, or never resolves the conflict at all | no state change; `409` on the original attempt | login-resolve flow / inaction (§3.4 — refuse is the default) |
| `S1` (`ACTIVE`) idle past `idle_deadline_at` | `S1`: `ACTIVE` → `EXPIRED(end_reason='idle_timeout')` | reconciler (system actor, §3.5) |
| `S1` (`ACTIVE`) past `absolute_expires_at` | `S1`: `ACTIVE` → `EXPIRED(end_reason='absolute_lifetime')` | reconciler (system actor) |
| `role:security_admin` ends `S1` | `S1`: `ACTIVE` → `REVOKED(end_reason='revoked_by_admin', ended_by=<admin's fingerprint>)` | human, audited |
| re-validation (§4.3) finds `I` no longer in the access group | `S1`: `ACTIVE` → `REVOKED(end_reason='access_group_lost')` | re-validation adapter (system actor; **inert while `DIRECTORY-POSTURE` is disabled**, §4.3) |
| same browser re-opens a tab | no transition — `S1` continues; a "session" is the server row, `last_seen_at` update only |
| service restarts | sessions survive (database rows); the next request against any `ACTIVE` row triggers `E1`'s normal validity check, not a fresh login |

No other transition exists. `SUPERSEDED`, `EXPIRED`, and `REVOKED` are
terminal: no code path moves a row out of any of them (a new login for the
same identity always creates a **new** `session_id`, never revives an old
one) — the same closed-transitions-graph discipline `C2` §3.2 uses for jobs.

### 3.4 Login-resolve flow: takeover vs. refuse, and which is default

Refined from design §7.3's up-front form, per the council's `UX-D3` finding
("ask only after a 409 conflict; a background fetch losing a half-typed
choice is a real UX failure") — a disclosed elaboration of the design's
sequencing, not a reopening of anything it decided (the form's fields are
unchanged; only *when* the choice is asked changes, §9):

```
POST /login {username, password}                         -- no takeover/refuse field yet
  ├─ bind fails / rate-limited → §2.2/§2.4 responses
  └─ bind succeeds, group set resolved, access group present:
       ├─ no ACTIVE session for this identity → session created, 200, cookie set
       └─ ACTIVE session S1 exists for this identity →
            409 LOGIN_CONFLICT { conflict_token, prior_session: {created_at, last_seen_at} }
            -- no address, no source detail beyond what §7.3 step 7 already permits (source class)

POST /login/resolve {conflict_token, action: "takeover" | "refuse"}
  ├─ "refuse"                → 409 LOGIN_REFUSED_ACTIVE_SESSION; S1 unaffected; no new session
  └─ "takeover"              → S1 → SUPERSEDED(by=S2); S2 created; 200, cookie set
  -- conflict_token expires after a short bound (proposed 2 minutes); an expired or
     unresolved token requires a fresh POST /login (the password is re-verified,
     never cached across the two calls)
```

**Default: refuse-by-inaction.** If the operator never calls
`/login/resolve` — closes the tab, walks away, the 409 is simply not acted
on — nothing happens: no session is created, `S1` is untouched. Takeover is
always an **explicit, separate, deliberate** call; it is never inferred from
a remembered browser preference, a timeout, or silence. This directly
answers `AC-2`'s "states which is default": refuse is the behaviour that
results from doing nothing further; takeover requires one additional,
unambiguous action.

**Audit record for a takeover** (and for every other §3.3 transition): the
Java transaction interceptor issues `SET LOCAL app.actor_fingerprint` and
`SET LOCAL app.action_id` before the state-changing `UPDATE`/`INSERT` on
`sessions`, exactly as `C1-1`'s mechanism requires (§3.5 below) —
`app.actor_fingerprint` is the **new** session's actor for a takeover (the
person who chose to take over), and a reserved, non-directory-derived value
(`system:session_reconciler`) for the two reconciler-caused transitions
(idle/absolute expiry) and (once enabled) `system:revalidation_adapter` for
an `access_group_lost` revocation — audited identically to a human action by
the trigger, distinguishable by actor name.

### 3.5 Audit trigger scope on `sessions` — a stated, deliberate exception

`C1-1`'s mechanism (a row trigger on every mutation-bearing table, §3.5 of
`C1`) is extended to `role_bindings` and `sessions` by this contract, with
one named, tested exception: **`sessions`'s trigger fires `AFTER INSERT` and
`AFTER UPDATE OF state` only** —

```sql
CREATE TRIGGER trg_audit_sessions
    AFTER INSERT OR UPDATE OF state ON sessions
    FOR EACH ROW EXECUTE FUNCTION fn_audit_capture('session_id');
```

— never on a bare `last_seen_at`/`idle_deadline_at` heartbeat touch. A
heartbeat happens on nearly every authenticated request; auditing every one
would flood `audit_log` with rows carrying no security-relevant fact (the
session's *state* did not change) and would defeat `audit_log`'s purpose as
a reviewable mutation trail. Every fact `AC-2` and `§3.3`'s test matrix
require — session creation, takeover, refusal (which creates no row and so
needs no audit row beyond the login attempt itself, §7.3 step 7), expiry,
revocation — **is** a `state`-column change and **is** captured. This is the
same kind of scoped, `WHEN`-qualified trigger Postgres already supports
(`AFTER UPDATE OF state` is a column-list trigger, not a business-logic
predicate) and is test-enforced (`§8` below): a test performs a
heartbeat-only update and asserts zero new `audit_log` rows; a state-change
update asserts exactly one.

`role_bindings` and `authz_decisions` do not need this exception:
`role_bindings` mutates only on administrative bind/revoke actions (low
frequency, every mutation security-relevant); `authz_decisions` is
insert-only by construction (§5.3) and needs no `UPDATE`/`DELETE` trigger
branch at all. `actor_authz_state` is explicitly **not** wired to
`fn_audit_capture()` (§4.4): it is a derived, ephemeral cache of a directory
answer, rewritten on every re-validation cycle and deleted when its owning
actor has no active session, carrying no decision of its own to record —
the decision it feeds (`E4`, §5) is what `authz_decisions` already audits.

### 3.6 Session token shape and expiry

- **Cookie**: `Secure`, `HttpOnly`, `SameSite=Strict`; value is an opaque,
  cryptographically random 256-bit token, base64url-encoded.
- **Stored value**: `sessions.session_id = SHA-256(raw cookie value)`,
  hex-encoded — a database read alone (backup, replication lag, a
  misconfigured read replica) can never be replayed as a valid cookie,
  matching design §7.5's "hash of the cookie value, never the value."
- **CSRF**: `csrf_secret`, minted at session creation, never logged;
  required on every state-changing request per design §7.3's CSRF-checked
  `E1` step.
- **Expiry** (proposed defaults, PO-tunable, §9 — mirroring `C2`'s own
  practice of naming default numbers as open, non-load-bearing items):
  idle timeout 30 minutes from `last_seen_at`, recomputed into
  `idle_deadline_at` on every request that passes `E1`; absolute lifetime
  10 hours from `created_at`, fixed at creation and never extended by
  activity. Whichever deadline is reached first ends the session (§3.3).

---

## 4. Role tokens → bindings → AD group references

### 4.1 The three levels of indirection (design §5.2, fixed here)

```
action_id  ──►  role token  ──►  role binding  ──►  AD group reference
(C4's        (repository-      (role_bindings row,   (corporate directory;
 registry)    committed,        admin-edited,          never in the repository,
              reviewable,       audited)               referenced by opaque
              no corporate                              binding_id in every
              identity)                                 log/audit row)
```

**Closed role-token vocabulary** (design §5.2, restated — this contract does
not add or remove a token, only fixes that the set is closed and
repository-committed):

| Role token | May (examples) | May not |
|---|---|---|
| `role:viewer` | read every projection UI 2.0 ships; read own audit rows | submit any job or intent |
| `role:operator` | `viewer` plus class-0 typed jobs, retry | enrollment confirm; schedule edit; any class 1; policy writes |
| `role:onboarding_admin` | `operator` plus enrollment preview/confirm, registry disable, credential-profile *reference* assignment | schedules; class 1; profiles |
| `role:backup_admin` | `operator` plus schedule-edit intent, target-set edit, profile selection/assignment, profile approval where author ≠ approver | authoring an executable profile in the browser (Phase 1); direct class-1 submission (never, any role) |
| `role:compliance_admin` | `operator` plus benchmark assignment, waiver edit, check-pack enable/disable, advisory ↔ enforced | class 1; schedules outside compliance evaluation cadence |
| `role:security_admin` | role-binding administration, session administration (§3.3), audit read across all actors | any device-facing action (separation of duties: the actor who administers authorization does not also execute) |

No token implies another by hierarchy; the "plus" wording is a reading aid.
Every `C4` action registry entry declares **exactly one** required role
token, or none (`NO_APPLICABLE_AUTHORITY`, §5.1).

### 4.2 `role_bindings` — DDL sketch, persistence and encryption

```sql
CREATE TABLE role_bindings (
    binding_id                    TEXT        PRIMARY KEY,   -- opaque, application-generated (identity law)
    role_token                    TEXT        NOT NULL,       -- closed vocabulary, §4.1
    group_reference_encrypted     BYTEA       NOT NULL,       -- the directory's own opaque group identifier, encrypted -- never plaintext, never logged
    group_reference_key_id        TEXT        NOT NULL,       -- which key wrapped this value (rotation support, mirrors C1 §6.3's key_id pattern)
    created_by_actor_fingerprint  TEXT        NOT NULL,
    created_at                    TIMESTAMPTZ NOT NULL DEFAULT now(),
    revoked_at                    TIMESTAMPTZ,
    revoked_by_actor_fingerprint  TEXT
);

CREATE TRIGGER trg_audit_role_bindings
    AFTER INSERT OR UPDATE OR DELETE ON role_bindings
    FOR EACH ROW EXECUTE FUNCTION fn_audit_capture('binding_id');
```

- **Encryption key source**: `C1` §6 secrets — this document does not
  redefine where the key lives. Concretely, the encryption follows `C1`
  §6.3's envelope pattern (a wrapping key resolved through the same
  `DEV.2.1`/`DEV.2.2` `<VAR>_FILE` mechanism §6.2 describes, recorded as a
  `secrets_metadata` row with `purpose = 'role_binding_group_ref_key'`,
  `backend_kind = 'env_file'` at B1 scope, identical posture to every other
  component secret C1 §6.1 tables). No corporate group name ever appears in
  application code, a log line, an API response, or a projection — every
  reference outside this one encrypted column is `binding_id` only.
- **Display**: the role-binding administration screen shows the directory's
  own display attribute (resolved live, on read, from the directory —
  never persisted as plaintext) only to `role:security_admin`, and the
  screen is marked `LOCAL_OPERATOR_SENSITIVE`.

### 4.3 `SR-D5` — self-grant prevention and bootstrap

Closing the council's finding ("`security_admin` can self-grant by binding
a token to a group they belong to; no bootstrap path for the first
binding"):

- **Four-eyes on binding create and revoke**: every `role_bindings` mutation
  requires `role:security_admin`, and the server refuses when the acting
  `security_admin`'s own resolved group set (`actor_authz_state`) already
  contains the `group_reference` being bound or revoked —
  `403 SELF_GRANT_REFUSED` — mirroring the `created_by ≠ approver`
  enforcement design §6.5 already uses for backup-profile approval.
- **Bootstrap**: before any `role_bindings` row for `role:security_admin`
  exists, no HTTP path can create one (there is no authenticated
  `security_admin` session to call it, and the self-grant refusal would
  block the very first admin's own group in any case). The **first**
  `role:security_admin` binding is created by a CLI-only, deployment
  -controlled operator action (equivalent posture to `C1` §2.4's
  `ui2_migrate` role: reachable only from a deployment-controlled step,
  never a running service, never the browser) — writing directly to
  `role_bindings` with `created_by_actor_fingerprint` set to a reserved
  bootstrap marker, itself audited by the same trigger. Every subsequent
  binding, including a second `security_admin` binding, goes through the
  normal HTTP path and its four-eyes/self-grant check.

### 4.4 `DIRECTORY-POSTURE` (D-6) — full specification, function disabled

Baseline `D-6`: *"Persisted encrypted group references + read-only directory
service account. CONDITIONAL: acceptable subject to corporate-policy
verification this conversation does not provide... The related function
(group-reference persistence, service-account bind) is not enabled until
the verification is recorded."* This section specifies the function in
full, per the baseline's own instruction that "'conditional' means the spec
is ready, not that the spec is skipped" — and states precisely what stays
off.

**What is gated together, as one function, per the baseline's own wording**
("the related function (group-reference persistence, service-account
bind)"): (a) writing a **real corporate** AD group reference into
`role_bindings.group_reference_encrypted`, and (b) the read-only
service-account bind used for periodic re-validation (§4.4.2). Both stay
disabled together, behind one named control (§4.4.3).

**What is *not* gated** — because it uses the operator's own bind (`LD-1`,
carried over, never a service account), not the disabled mechanism: the
**initial** group-set resolution performed at login (design §7.3 step 4,
"group set resolved from the directory for this actor... access group
required to open a session at all") and the resulting first write to
`actor_authz_state`. Login and initial session admission function normally
regardless of `D-6`'s status.

#### 4.4.1 Credential scope

- **Account type**: a dedicated, read-only directory service account,
  distinct from every operator's own credential and from every other
  component secret (`C1` §6.1's "no shared 'app' secret" invariant).
- **Directory rights**: the minimum needed to bind and to search for group
  membership of a named identity — bind right on the service account's own
  DN, and search right scoped to the base DN(s) that contain the groups
  `role_bindings` can reference, returning only `member`/`memberOf`-class
  attributes. No write right of any kind on the directory. No right to read
  password hashes, arbitrary user attributes, or any OU outside the
  configured base DN(s).
- **Never used for authentication**: the service account never appears on
  the login path (§2.1); a compromise of this credential grants read-only
  group-membership visibility, never the ability to authenticate as any
  operator.

#### 4.4.2 Storage and rotation

- **Storage**: identical mechanism to every other component secret —
  `C1` §6.2's `<COMPONENT>_<PURPOSE>_FILE` convention, resolved as
  `SECURITYEXPERT_UI2_LDAP_REVALIDATION_SERVICE_ACCOUNT_PASSWORD_FILE`;
  recorded as a `secrets_metadata` row (`C1` §3.6) with
  `component = 'ldap_revalidation_adapter'`, `purpose = 'ldap_service_account'`,
  matching `C1` §6.1's own table row for this exact component.
- **Departure from the boot-preflight pattern, stated explicitly**: unlike
  a DB DSN (resolved once at startup and held for the process lifetime,
  `C1` §6.2), this credential is **re-read from its `_FILE` mount on every
  re-validation cycle** (proposed 15-minute interval), not cached across
  cycles. This is a deliberate, narrow exception: the credential's usage
  pattern (infrequent, periodic) makes a fresh read cheap, and rotating it
  must take effect **without a service restart** — caching it for the
  process lifetime would mean a rotated credential silently fails to apply
  until the next deployment, defeating the point of "rotatable
  independently of the operator's own bind credential" (`C1` §6.1).
- **Rotation**: operator-driven (a human replaces the mounted file
  contents), independent of the DB DSN's rotation cadence and of the
  operator's own bind credential (which is never stored at all, §2.3).

#### 4.4.3 The disable switch and outage behaviour

- **Named control**: a single boolean configuration value,
  `directory_posture_enabled` (illustrative name; `C1`/deployment
  configuration finalizes the concrete variable), read once at service
  startup, **defaulting to `false`** and **not settable to `true` by this
  contract's own text** — flipping it is the corporate-policy-verification
  event itself, a Product Owner / deployment action outside this document's
  authority, per `D-6`. While `false`: the re-validation adapter's
  scheduled job does not run at all (no connection pool is opened, §2.1); no
  code path writes a value into `role_bindings.group_reference_encrypted`
  other than the test-scope path named below.
- **Test-scope exception, explicit and bounded**: `B1-3`'s own acceptance
  testing (§8) needs to exercise the RBAC mechanism — the state machine,
  the encryption, the audit trigger, the `E4` evaluation — without a real
  corporate directory or the disabled service account. This is done against
  a **test LDAP directory** (an OpenLDAP/ApacheDS Testcontainer, seeded with
  synthetic groups), never the corporate directory, with
  `group_reference_encrypted` values that never resolve to a real corporate
  group. This is not an exception to `D-6` — a test directory is not the
  directory `D-6` conditions access to — and does not require
  `directory_posture_enabled = true`.
- **Outage behaviour while disabled (the practical, load-bearing
  consequence, restated for the record — see also §9)**: `actor_authz_state`
  rows are written once, at login, from the operator's own bind, and are
  **never refreshed** while the adapter is off. Once a row's `valid_until`
  elapses (the same 15-minute interval), every `E4` evaluation for that
  actor against a group-bound token returns `AUTHZ_NOT_EVALUATED`
  (§5.1) — never `DENIED`, never a stale `PERMITTED`. In a real corporate
  -directory deployment, this means no actor can hold a `PERMITTED`
  authorization outcome for any role-token-gated action for more than one
  re-validation interval past login, until `D-6`'s verification is
  recorded and the switch is flipped. This is the fail-closed, honest cost
  of the function staying disabled — never silently bypassed by extending
  `valid_until`, never worked around by trusting the login-time resolution
  indefinitely.

---

## 5. RBAC enforcement — visible-but-refused

### 5.1 The four outcomes, precisely

| Outcome | Meaning | Proceeds? |
|---|---|---|
| `PERMITTED` | token bound, actor's resolved group set contains the bound `group_reference` | yes |
| `NO_APPLICABLE_AUTHORITY` | the action declares no required role token at all — open to any authenticated session (subject to `E1`–`E3`, `E5`, `E6`) | yes |
| `DENIED(authority, reason_code)` | token bound to at least one `role_bindings` row, actor's resolved group set does not contain it | **no** |
| `AUTHZ_NOT_EVALUATED(authority)` | token declared but cannot currently be evaluated: no active binding exists for it (`role_token_unbound`), or the actor's `actor_authz_state` is stale/missing (`actor_group_set_stale`) | **no** |

`AUTHZ_NOT_EVALUATED` is never downgraded to `NO_APPLICABLE_AUTHORITY`
(`LD-2`'s banned transition, `AG-4`): a configured authority that cannot be
evaluated must say so, never silently present as "no gate here."

### 5.2 The refusal HTTP contract (`AC-1`)

For a mutating or job-submission request, `E4` evaluates before the request
does anything else. `PERMITTED` and `NO_APPLICABLE_AUTHORITY` proceed to the
next gate (§6); `DENIED` and `AUTHZ_NOT_EVALUATED` both return
**`HTTP 403`** (never `200`, never a different status per outcome — the
distinguishing information is in the body, matching design §5.1's "the
UI's job is to say why, never to make the option disappear," applied at the
HTTP layer) with this envelope:

```json
{
  "error": "ACTION_REFUSED",
  "action_id": "backup_schedule_edit",
  "outcome": "DENIED",
  "authority": "ui2_ldap",
  "reason_code": "actor_not_in_required_group",
  "decision_id": 48213
}
```

`decision_id` is `authz_decisions.decision_id` (§5.3) — the exact audit row
a `B1-3` test or an operator can point at. The `reason_code` vocabulary,
closed:

| `reason_code` | Outcome it belongs to | When |
|---|---|---|
| `actor_not_in_required_group` | `DENIED` | a `role_bindings` row exists for the action's token; the actor's resolved group set does not contain it — **the canonical "visible but refused" case**: the action renders, the operator sees exactly why they may not use it |
| `role_token_unbound` | `AUTHZ_NOT_EVALUATED` | the action's required role token has **zero** active `role_bindings` rows — **the "unbound action" case `AC-1` names**: no `security_admin` has bound this token to any group yet |
| `actor_group_set_stale` | `AUTHZ_NOT_EVALUATED` | the actor's `actor_authz_state.valid_until` has elapsed and no fresher re-read exists (§4.4.3's disabled-adapter case, or a transient re-validation failure, §2.2) |

For a `GET` request rendering affordances (§5.4), the same four-valued
outcome is embedded per `action_id` in the response body — the request
itself still returns `200`; rendering the refused state is not an HTTP
error, it is the product showing its own authorization posture (design
§5.1).

### 5.3 `authz_decisions` — DDL sketch and audit linkage

```sql
CREATE TABLE authz_decisions (
    decision_id       BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,  -- internal ordering key only, not a product identifier (C1 §7 pattern)
    decided_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    session_id        TEXT        NOT NULL REFERENCES sessions(session_id),
    actor_fingerprint TEXT        NOT NULL,
    action_id         TEXT        NOT NULL,
    target_ref        TEXT,                            -- opaque device_id/entity_id; nullable for actions with no single target
    outcome           TEXT        NOT NULL CHECK (outcome IN ('PERMITTED','DENIED','AUTHZ_NOT_EVALUATED','NO_APPLICABLE_AUTHORITY')),
    authority         TEXT,                             -- e.g. 'ui2_ldap'; NULL when outcome = 'NO_APPLICABLE_AUTHORITY'
    reason_code       TEXT,
    binding_id        TEXT        REFERENCES role_bindings(binding_id)      -- the binding that produced PERMITTED/DENIED, where one exists
);

REVOKE INSERT, UPDATE, DELETE ON authz_decisions FROM ui2_app;
GRANT SELECT, INSERT ON authz_decisions TO ui2_app;   -- append-only from the app's own request path; never UPDATE/DELETE
```

`authz_decisions` is a separate table from `C1`'s `audit_log` — it records
**every** authorization evaluation (including every `GET` that renders
affordances, a much higher-frequency event than a mutation), not only the
ones that gate an actual mutation; folding it into `audit_log` would drown
the mutation trail in read-time noise. It needs no `fn_audit_capture()`
trigger of its own: like `audit_log`, it is append-only by construction and
grant (no `UPDATE`/`DELETE` privilege on `ui2_app`), so nothing "mutates" it
that a trigger would need to catch.

**Linkage to `audit_log`** ("audit linkage to `C1`'s `audit_log`" per this
contract's output requirement): when a `PERMITTED` decision gates a
mutation that proceeds (e.g., a `role_bindings` create, a session
revocation), the resulting `audit_log` row (via `C1-1`'s trigger) carries
the same `actor_fingerprint` and `action_id` that the `authz_decisions` row
recorded, within the same database transaction. This document does not add
a foreign-key column to `C1`'s `audit_log` (out of scope, `§1.2`); the
correlation is `(actor_fingerprint, action_id, occurred_at ≈ decided_at)`
within one transaction — the same reference-not-redefine relationship `C1`
`§3.5`'s `correlation_run_id` uses for job-caused mutations, applied here
without needing a new column because both tables already share the closed
`action_id`/`actor_fingerprint` vocabulary.

### 5.4 Render path

`GET` requests that return affordances run `E1`–`E6` per declared action and
emit `action_affordance[action_id] = {outcome, authority?, reason_code?}`
exactly as design §5.3 specifies. The front end renders whatever it is
given; it holds no role concept and computes no visibility decision of its
own (`AG-J3`: a grep-level test on the built front-end assets finds no
role-conditional rendering).

---

## 6. The `E1`–`E7` gate chain on the HTTP layer

### 6.1 The chain, in order, each gate's exact check

| Gate | Check | On failure |
|---|---|---|
| `E1` | Session authenticity: a valid, hashed session id maps to an `ACTIVE` `sessions` row not past `idle_deadline_at`/`absolute_expires_at`; CSRF token present and matches `sessions.csrf_secret` for a state-changing method; request origin checked | `401` (`SESSION_INVALID` / `SESSION_SUPERSEDED` / `SESSION_EXPIRED` / `SESSION_REVOKED` — the specific `end_reason` when the row exists but is non-`ACTIVE`, per §3.3) |
| `E1` (actor resolution, `A1'`) | session → `actor_authz_state` row: resolved group set, `resolved_at`, `valid_until` | folds into `E4`'s freshness check below; not a separate failure mode of its own |
| `E2` | Action identity: `action_id` exists in `C4`'s closed Java action registry | `404 ACTION_UNKNOWN` |
| `E3` | Taxonomy admissibility: the action's `ActionClass.consoleSubmittable` (class 1: refused here, always, every role — `recovery_write_not_console_submittable`, `AG-J10`) | `403`, named reason; `E3` is never re-evaluated inside `E4` (`H5c`, both reasons recorded when both would fire) |
| `E4` | `D7` evaluation — the four outcomes, §5.1 | `403 ACTION_REFUSED` for `DENIED`/`AUTHZ_NOT_EVALUATED` (§5.2); proceeds for `PERMITTED`/`NO_APPLICABLE_AUTHORITY` |
| `E5` | Subject/target integrity the action's own contract requires (e.g., the referenced `device_id` exists and is not disabled) | `403`/`404`, action-specific, defined by the action's own registry entry (`C4`), not by this contract |
| `E6` | The action's declared prerequisites (e.g., a profile must be `APPROVED` before it can be assigned) | `409`/`422`, action-specific, defined by the action's own registry entry (`C4`) |
| `E7` | Execution-phase admission + immediately-before-execution checks | **not this contract's gate** — see §6.2 |

`E1`–`E6` run in **one interceptor chain no route can bypass** (test
-enforced, `AG-J2`: a test enumerates every route and asserts the chain is
applied). A render (`GET`) request runs `E1`–`E6` per declared action and
emits the affordance (§5.4) instead of failing the request; a mutating
request that fails any gate never reaches the handler that would perform
the mutation.

### 6.2 Composition boundary with `C2`'s pre-execution checks — no duplication

`E7` is **not** run by this contract, and is not run on the HTTP layer at
all. `C2` §6 states it precisely: pre-execution checks "run at claim time,
inside the `CLAIMED` state, strictly before the first device contact... They
are the `E7` step of the gate chain... plus the additional battery this
contract's own scope requires." Concretely, the boundary is:

- **This contract's job ends at `REQUESTED`.** By the time a job row exists
  (`C2` §3.1: `REQUESTED` — "admission-time checks (`E1`–`E6`, taxonomy
  admissibility) already passed at creation"), this contract's work is
  done: the request carried a resolved actor and a `PERMITTED`/
  `NO_APPLICABLE_AUTHORITY` `D7` outcome, or it never became a job at all.
- **`C2`'s six-check battery (its own §6, checks 1–6: approval re-check,
  connectivity precondition, Line-1/Java coordination window, `RB.x`
  ledger, registry/allowlist, credential resolution) *is* `E7`.** This
  contract does not re-specify any of those six checks, does not reorder
  them, and does not add a seventh — `E7` is `C2`'s to define and this
  document names it only as the point in the job lifecycle where the gate
  chain's final step runs.
- **The one piece of `C3` semantics `E7` must call back into**: `C2`'s
  pre-execution check 1 ("Approval state, re-checked fresh... for a Run
  Now, the `D7` role binding and mandatory reason are still valid for the
  owning actor") *is* this contract's `E4` re-evaluated at claim time,
  against the *current* `role_bindings`/`actor_authz_state`, not the
  request-time snapshot — the same rule design §7.7's `M14 U-4` disposition
  states ("`E4` is re-evaluated at execution time for any action whose
  submission-to-execution gap can exceed the re-validation interval...
  against the actor recorded on the submission"). `C2` implements the
  re-check mechanically (its own claim-time query); this contract supplies
  the authorization semantics that check evaluates — a **shared boundary
  fact**, stated once, in both documents, deliberately, rather than left
  implicit: `C2` §7.2's `FAILED(schedule_authorization_lapsed)` reason is
  what an `E4`-family authorization failure looks like when it surfaces
  inside `C2`'s own precheck vocabulary rather than this contract's `403`
  envelope (§5.2) — the same underlying fact (a lapsed or revoked binding),
  reported through whichever layer detected it.
- **Never duplicated**: this contract does not re-read the `RB.x` ledger,
  the coordination window, the device registry, or a credential reference —
  those five of `C2`'s six checks have nothing to do with identity, session,
  or role, and this document does not touch them.

---

## 7. How `C2`'s owner/approver/execution-identity resolve from sessions and roles

`C2` §7.2 defines three distinct fields and states their sources in the
abstract ("the actor who submitted the request," "the profile version's own
`approved_by`," "resolved by the worker at claim time"). This section
supplies the concrete resolution against this contract's own tables, with a
worked example (`AC-6`).

| `C2` field | Resolves from (this contract) |
|---|---|
| `owner` (Run Now) | `origin.session_id` (`C2` §2.1) → `sessions.actor_fingerprint` for that `session_id`, read at request time |
| `owner` (scheduled) | the schedule's `created_by` — itself an `actor_fingerprint` captured from the `sessions.actor_fingerprint` of whichever session created the schedule, at that time (`C7`'s table, out of this document's scope; the fingerprint's *shape* is this contract's, §3.2) |
| `approvers` | the profile version's `approved_by` / the schedule's `authorized_by` — each itself an `actor_fingerprint` captured the same way, from the approving/authorizing actor's own session at the moment of approval/authorization (never re-derived from the requester's session) |
| `execution_credential_ref` | **not** resolved from any actor identity at all — `C2` §6 check 6, the profile's/capability's declared `credential_profile_ref`, independent of `owner`/`approvers`/the claiming worker |

**Worked example (`AC-6`).** Operator **Alice** (`actor_fingerprint`
`af3a9c1e2b7d`, resolved at her login per §3.2) holds a `role_bindings` row
binding `role:backup_admin` to her AD group (`binding_id = b-001`). She
submits a Run Now for backup profile `cp_gaia_r81_20_backup` version 3
against device `dev-042`:

1. **At request time**: `E1`–`E6` run against Alice's session. `E4`
   evaluates `role:backup_admin` against her `actor_authz_state`:
   `PERMITTED(authority='ui2_ldap', binding_id='b-001')`, recorded as an
   `authz_decisions` row. The job is created: `job.owner = 'af3a9c1e2b7d'`
   (from `origin.session_id` → her session's `actor_fingerprint`);
   `job.approvers` = the profile version's own `approved_by` — say **Bob**
   (`actor_fingerprint` `af771...`), who approved v3 under `created_by ≠
   approver` (design §6.5) — **never Alice**, since she submitted the
   request, not the approval.
2. **At claim time** (`C2` §6 check 1 / this contract's `E4` re-check,
   §6.2): the worker re-reads `role_bindings`/`actor_authz_state` for
   Alice's `actor_fingerprint` fresh. If binding `b-001` is still active and
   her group set still contains it, the check passes (`PASSED`); if
   `security_admin` revoked `b-001` between request and claim, the check
   fails `FAILED(schedule_authorization_lapsed)` and the job transitions
   `CLAIMED → REJECTED` — no device is contacted.
3. **`execution_credential_ref`** resolves separately (`C2` §6 check 6)
   from the profile's own `credential_profile_ref` — never from Alice's
   identity, Bob's identity, or the worker's own identity. A static test
   (`C2` `AC-9`, criterion 11) forbids any code path assigning
   `execution_credential_ref` from `owner` or `approvers`.

Owner (Alice), approver (Bob), and execution identity (the profile's own
credential reference) are three distinct values throughout, sourced from
three distinct places, exactly as `C2` §7.2 requires and this contract's
session/role model supplies.

---

## 8. Acceptance criteria for `B1-3` and acceptance scenario `B1-10`

At least ten, each independently testable, mirroring the Testcontainers
discipline `C1`/`C2` already establish (a real PostgreSQL instance and, for
the directory-facing criteria, a test LDAP container — never the corporate
directory, §4.4.3):

1. **Single-active-session is structurally enforced.** A test connects
   directly to PostgreSQL (bypassing the Java service) and attempts to
   `INSERT` a second `sessions` row with `state = 'ACTIVE'` for an
   `actor_fingerprint` that already has one: the unique partial index
   (§3.1) rejects it, proving the invariant holds even against a bypassed
   application layer, not only against the login handler.
2. **Takeover, precisely.** Actor `I` has `ACTIVE` session `S1`; a second
   login for `I` with `action: "takeover"` results in `S1.state =
   'SUPERSEDED'`, `S1.superseded_by_session_id = S2.session_id`, `S2.state =
   'ACTIVE'`, and exactly one new `audit_log` row for the `sessions` table
   (via `trg_audit_sessions`, §3.5) recording the transition — all in one
   transaction (`AC-2`).
3. **Refuse leaves the prior session untouched.** Same setup, `action:
   "refuse"`: `S1` remains `ACTIVE` with no field changed, no `S2` is
   created, the response is `409 LOGIN_REFUSED_ACTIVE_SESSION` (`AC-2`).
4. **Refuse-by-inaction is the default.** A `409 LOGIN_CONFLICT` is
   returned and `/login/resolve` is never called (simulating operator
   inaction or an expired `conflict_token`): after the token's bound
   expires, `S1` remains `ACTIVE`, unchanged, and a fresh `POST /login` is
   required to try again (§3.4).
5. **Heartbeat generates no audit noise.** A `last_seen_at`-only update to
   an `ACTIVE` session produces zero new `audit_log` rows; a `state`-column
   update on the same row produces exactly one (§3.5, proving the trigger's
   `AFTER UPDATE OF state` scoping is real, not merely documented).
6. **Unbound-action refusal shape (`AC-1`, the canonical case named in this
   contract's own acceptance criteria).** An action's role token has zero
   active `role_bindings` rows; a request against it returns exactly
   `403 {"error":"ACTION_REFUSED","outcome":"AUTHZ_NOT_EVALUATED","reason_code":"role_token_unbound",...}`,
   and one `authz_decisions` row with the matching `outcome`/`reason_code`
   exists, referencing the request's `session_id`/`action_id`.
7. **Bound-but-not-a-member refusal shape.** An action's role token is
   bound to group `G`; the acting actor's resolved group set does not
   contain `G`: the request returns
   `403 {"outcome":"DENIED","reason_code":"actor_not_in_required_group",...}`
   with the corresponding `authz_decisions` row.
8. **Stale group set yields `AUTHZ_NOT_EVALUATED`, never `DENIED`.** An
   actor's `actor_authz_state.valid_until` is forced into the past (no
   re-validation available, §4.4.3's disabled-adapter state): a request
   against a group-bound action returns `AUTHZ_NOT_EVALUATED` with
   `reason_code = 'actor_group_set_stale'`, never `DENIED` and never a
   stale `PERMITTED` — the constraint that must hold with
   `directory_posture_enabled = false` at B1 scope.
9. **`E3` is never re-evaluated inside `E4`.** For a class-1 action, both
   the `E3` taxonomy refusal and, independently, whatever `E4` outcome
   would have applied are recorded (`H5c`); the response names `E3`'s
   refusal (`recovery_write_not_console_submittable`) and the request never
   reaches an `E4` check that could produce a different, conflicting
   refusal reason for the same request.
10. **Self-grant is refused (`SR-D5`).** A `security_admin` whose own
    resolved group set already contains group `G` attempts to bind a role
    token to `G`: the request is refused `403 SELF_GRANT_REFUSED`; no
    `role_bindings` row is created.
11. **`E4` re-check at claim time catches a mid-flight revocation
    (composition boundary, `§6.2`).** A Run Now job is created while its
    submitter's `role:backup_admin` binding is active; before any worker
    claims it, `security_admin` revokes that binding; the claim's `C2` §6
    check 1 fails, the job transitions to `REJECTED`, and no
    `job_step_attempt` row is ever created — proving `E4` is genuinely
    re-evaluated at claim time, not only checked once at submission.
12. **Owner/approver/execution-identity distinctness resolves correctly
    (`AC-6`).** Using the §7 worked example's shape: for a job created by
    actor `A` against a profile approved by actor `B` (`A ≠ B`,
    structurally enforced by design §6.5's `created_by ≠ approver` rule),
    `job.owner` resolves to `A`'s `actor_fingerprint`, `job.approvers`
    contains `B`'s, and no code path sets `job.execution_credential_ref`
    from either (an architectural test, mirroring `C2` `AC-9` criterion 11).

**Acceptance scenario `B1-10`** (three admins on one device concurrently,
single-session takeover/refuse, correct state throughout — the scenario
`C2` §9 explicitly names as primarily this contract's responsibility): three
distinct `actor_fingerprint`s each hold their own `ACTIVE` session and their
own `role_bindings`; each independently submits a Run Now against the same
device; a fourth login attempt for one of the three identities triggers the
conflict flow (criteria 2–4 above); throughout, `E1`–`E6` evaluate
correctly and independently per session (no cross-actor state leakage
— `sessions`/`actor_authz_state` are keyed strictly by `actor_fingerprint`);
`C2`'s own criterion 12 (concurrent Run Now de-duplication) is what proves
the concurrent submissions do not cause duplicate device contact — this
contract's contribution to `B1-10` is criteria 1–4 and 11–12 above:
correct, isolated, audited session and authorization state across all three
identities while that concurrency plays out.

---

## 9. Contradictions and open items for the Product Owner

**Contradictions with FROZEN authority: none found.** Documents checked
while writing this contract: `AGENTS.md` (identity law, evidence laws,
UNKNOWN/fail-closed law, sensitive-identity reporting law, privacy/DLP),
`docs/design/UI2_0_BASELINE_CONTRACT.md` (FROZEN), `docs/design/UI2_0_DEVELOPMENT_WORKFLOW.md`
(BASELINE rev 2), `docs/design/UI2_0_ARCHITECTURE_CONTRACT.md` §3/§4/§9/§10
(DRAFT, amended), `docs/design/UI2_0_C1_PLATFORM_SCHEMA_CONTRACT.md`,
`docs/design/UI2_0_C2_JOB_EXECUTION_CONTRACT.md`,
`docs/design/UI2_0_COUNCIL_REVIEW_AND_SECOND_OPINION_BRIEF.md`,
`docs/design/OPERATOR_CONSOLE_ARCHITECTURE.md` (`CON.0`, FROZEN, checked
specifically against `SR-D4` below),
`docs/design/M14_LOCAL_LDAP_AUTHORIZATION_ARCHITECTURE.md`
(DRAFT, not authority — §1.4), `utils/logger.py`. This document reopens no PO-reserved decision:
`DIRECTORY-POSTURE` is implemented exactly as conditional and disabled
(§4.4), not enabled or reinterpreted as permission to enable it; `RAW
-RETENTION` is not engaged by this document's scope at all (no raw device
output ever reaches an identity/session/RBAC table).

**Open items, not contradictions:**

1. **`DIRECTORY-POSTURE`'s practical consequence, stated plainly (not new,
   but load-bearing enough to restate here).** Until `D-6`'s corporate
   -policy verification is recorded and `directory_posture_enabled` is set,
   no actor in a real corporate-directory deployment can hold a
   `PERMITTED` authorization outcome for longer than one re-validation
   interval past login (§4.4.3) — every action degrades to
   `AUTHZ_NOT_EVALUATED` after that. `B1-3`'s own acceptance testing is
   unaffected (it uses a test directory, never the corporate one, §4.4.3),
   but the Product Owner should be aware this is a real, near-term
   operational ceiling on RBAC's usefulness against a live directory, not
   merely a documentation gap.
2. **`UX-D3` sequencing refinement, adopted.** This contract moves the
   takeover/refuse choice from design §7.3's up-front login form to a
   post-409 `/login/resolve` call (§3.4), per the council's `UX-D3`
   finding. The login form's own fields are unchanged; only the sequencing
   changes. This is presented as this contract's disclosed elaboration of
   the design's own mechanism, not a reopening of anything the baseline or
   the design froze — no PO ruling named the up-front sequencing as
   load-bearing, and `UX-D3` was an open council finding, not a decision
   this contract overrides.
3. **`SR-D5` (self-grant, bootstrap), adopted as this contract's answer**
   (§4.3) — the same pattern `C2` used for `SR-D6`: an explicitly open
   council item resolved by this contract's own construction, not a PO
   ruling being revisited.
4. **`SR-D4`, checked, resolution already on record.** The council flagged
   "server-mode enrollment (`role:onboarding_admin`) contradicts frozen
   `CON.0` §4.1/§7 rule 11 ('server mode remains blocked until `DEPLOY.1A`
   supplies real OIDC/RBAC')" and asked for `UA-9` or a PO ruling. Design
   §11's `UA-8` already reports and resolves the broader contradiction this
   sits inside ("network-exposed TLS listener with sessions" vs. `CON.0`'s
   loopback posture), disposed as "the `DEPLOY.1A`-class decision those
   rows reserved; §7" — i.e., this contract (turning design §7 into a
   frozen specification) **is** that `DEPLOY.1A`-class decision, using
   LDAP/AD-group RBAC in place of OIDC. This document treats `SR-D4` as
   answered by `UA-8`'s existing disposition rather than opening a new
   `UA-9`; the Product Owner may want to confirm explicitly, at freeze,
   that `UA-8`'s resolution is understood to cover `SR-D4`'s specific
   "OIDC vs. LDAP-group RBAC" wording concern.
5. **`SR-D10`, genuinely open, not decided here.** `principal_fingerprint`
   is an unkeyed 12-hex SHA-256 prefix (§3.2) — reversible from a DN list
   by anyone who can enumerate likely DNs, as the council found. This
   contract adopts the existing algorithm unchanged (it is not this
   contract's to redefine) and does not decide the council's actual
   question — whether fingerprints should be **attributable** (a display
   name resolvable at read time, by `security_admin`, from `role_bindings`'
   own encrypted-at-rest linkage) or stay **pseudonymous** as today. Left
   for the Product Owner.
6. **`M14 U-1` (nested-group membership semantics), still open.** Design
   §12 already carries this as "must close before any authorization
   freeze." This contract's `E4` evaluation (§5.1, §6.1) is an exact-match
   set-membership check against `actor_authz_state`'s resolved group
   references; it does not attempt nested-group expansion, and cannot,
   until `U-1` closes with Microsoft documentation. Unchanged by this
   contract; restated here because it directly bounds what "the actor's
   resolved group set" in §4.4.1/§5.1 can mean today.

---

## 10. Cross-references

- `docs/design/UI2_0_BASELINE_CONTRACT.md` (FROZEN — PRODUCT OWNER APPROVED,
  2026-09-09) — `DIRECTORY-POSTURE` (D-6), acceptance sentence A-1.
- `docs/design/UI2_0_DEVELOPMENT_WORKFLOW.md` §5 B0-3 (this movement's scope
  line), B1 step 3 (Identity & sessions), B1 step 9 (RBAC visible-but
  -refused proof), B1 step 10 (acceptance scenario A / `B1-10`).
- `docs/design/UI2_0_ARCHITECTURE_CONTRACT.md` §3 (RBAC, role model,
  evaluation, storage/audit — this document's primary specification
  target), §7 (authentication and sessions — the same), §11 (`UA-8`,
  disposed against `SR-D4` in §9 above), §12 (`M14 U-1`, still open).
- `docs/design/UI2_0_COUNCIL_REVIEW_AND_SECOND_OPINION_BRIEF.md` — `SR-D5`,
  `SR-D6` (resolved by `C2` §7.2), `SR-D7`, `SR-D10`, `UX-D3`.
- `docs/design/UI2_0_C1_PLATFORM_SCHEMA_CONTRACT.md` (concurrent movement,
  `C1`) — §2.4 (roles), §3.1 (the four tables `C1` reserves for this
  document), §3.3 ("C3's actor identity shape"), §3.5 (`C1-1` audit
  mechanism, extended here to `role_bindings`/`sessions`), §6 (secrets,
  key custody — this document's own secrets follow the identical
  mechanism).
- `docs/design/UI2_0_C2_JOB_EXECUTION_CONTRACT.md` (concurrent movement,
  `C2`) — §1.2 (its own scope beginning at `E7`), §6 (pre-execution checks
  — `E7` itself), §7.2 (owner/approver/execution identity — resolved
  concretely here, §7).
- `docs/design/M14_LOCAL_LDAP_AUTHORIZATION_ARCHITECTURE.md`
  (DRAFT, not authority — §1.4) — `LD-1`…`LD-7`, the loopback-console precedent amended
  one decision at a time (design §7.7); `utils/logger.py::principal_fingerprint` — the existing
  correlator adopted, not redefined (§3.2).
- `docs/design/OPERATOR_CONSOLE_ARCHITECTURE.md` (`CON.0`, ARCHITECTURE
  FROZEN 2026-08-31) — §4.1/§7 rule 11 (server-mode/`DEPLOY.1A` gate,
  checked against `SR-D4` in §9).
- `AGENTS.md` — identity law, evidence laws, UNKNOWN/fail-closed law,
  sensitive-identity reporting law, privacy/DLP.
- `docs/AI_DEVELOPMENT_PROTOCOL.md` — approval boundaries; unaffected by
  this document (contract only, no source, no migration).

---

## Correction C-1 (2026-09-12) — a DRAFT document stood in §1.4's authority chain

§1.4's authority chain listed `docs/design/M14_LOCAL_LDAP_AUTHORIZATION_ARCHITECTURE.md`
as item 7. That is a defect in this contract, not in the cited document, which
is DRAFT and therefore not authority here.

**Evidence.** `AGENTS.md` "Authority hierarchy" item 2 and "Contract-status
law" both forbid a document whose status line says `DRAFT` from being treated
as implementation authority or cited as approving a command, schema or
identity model. `M14_LOCAL_LDAP_AUTHORIZATION_ARCHITECTURE.md`'s own status
line reads "**DRAFT — discussion proposal. Not implementation authority**" and
cites those same two rules against itself. Listing it in this FROZEN
contract's authority chain — an identity/authorization contract, the exact
subject matter the rule names — asserted the opposite. The same defect class
was adjudicated for the B1 family by
`docs/design/UI2_0_B1_01A_PLATFORM_SKELETON_CONTRACT.md` ("Why a successor
instead of an amendment") and is now machine-checked by
`tests/test_contract_authority_status.py`. The cited document is precedent and
evidence, not authority, for anything in this contract.

**Adjudication: reclassification, not a successor contract.** Item 7 bundled
two unlike things: a DRAFT design document and `utils/logger.py::principal_fingerprint`,
which is source (`AGENTS.md` authority hierarchy item 5) and legitimately
belongs in the chain. Every use of the DRAFT was precedent, never a clause
that settles something:

- item 7 cited it as "the loopback-console precedent this document amends for
  a multi-admin server, one decision at a time" — precedent, and a precedent
  this contract amends rather than obeys.
- §2.1, §2.2, §2.3, §4.4 and §5.1 cite `LD-1`, `LD-2`, `LD-3`, `LD-6` and the
  `AG-n` acceptance ids alongside the full normative sentence stated in this
  contract's own words ("carried over", "unchanged", "mirrors") — provenance
  labels on clauses this contract states itself.
- §6 cites `M14 U-4`, but for the **disposition** recorded in design §7.7
  ("decided for UI 2.0"), which is chain item 4. `M14` leaves `U-4` open; this
  contract does not read it as closed by `M14`.
- §9 item 6 and §10 keep `M14 U-1` (nested-group membership semantics)
  explicitly open pending Microsoft documentation, per `AGENTS.md` vendor
  -semantics law.

No normative clause of this contract depends on the DRAFT, so nothing this
contract requires changes here. Item 7 now carries only the source citation;
the DRAFT is moved into the labelled "Evidence and precedent consulted — not
authority" block in §1.4, and every remaining reference is marked `DRAFT, not
authority`.

**Closed 2026-09-12, after this correction was written.** The item-4 defect
reported below was resolved the same day: the cited document was superseded by
`docs/design/UI2_0_ARCHITECTURE_CONTRACT.md` (FROZEN), §1.4 item 4 now cites
the successor, and §4.5 there carries the `M14 U-4` disposition §6 of this
contract reads. The report below is kept as the record of how it was found.

**Not reconciled here, reported instead.** §1.4 item 4 places
`docs/design/UI2_0_ARCHITECTURE_DESIGN.md` in this same authority chain, and
that document's own status line reads "DRAFT — design resolved, NOT frozen,
NOT implementation authority". That is the identical defect, but larger: item
4 is this contract's primary specification target (design §5/§7) and supplies
the `U-4` disposition §6 relies on, so reclassifying it could change what this
contract requires. Under `AGENTS.md` "Authority hierarchy" that is a
contradiction to report, not to reconcile locally. It is left standing and
flagged for the contract owner, together with the detector gap that hides it:
`tests/test_contract_authority_status.py::_classify` matches the token `FROZEN`
inside the phrase "NOT frozen" and therefore classifies that document as
frozen, so no citation of it is currently flagged in either direction.

## Correction C-2 (2026-09-12) — authentication is not LDAP-only

**Product Owner ruling, 2026-09-12.** Phase 1 carries **LDAP and local**
authentication; RADIUS and TACACS follow later.

This contract, as frozen, defines exactly one authentication mechanism: an
UnboundID LDAP bind (§2). The words "local user", "fallback", "RADIUS" and
"TACACS" do not appear in it anywhere. That is not a wrong claim — it is an
incomplete one, and the difference matters: an implementation reading §2 as
the whole identity model would wire login to LDAP alone and have to be
unpicked when the second mechanism lands.

The Product Owner's statement of the product rule: every comparable product
offers local plus directory options, **and the fallback is always a local
user**. A deployment whose directory is unreachable, misconfigured, or not yet
integrated must still be administrable.

### What this correction settles

1. Authentication is a **pluggable set of mechanisms**, not one mechanism.
   `LDAP` and `local` are in scope for Phase 1; `RADIUS` and `TACACS` are named
   as later additions and nothing may be implemented for them yet.
2. **Local is always present.** It is not a configuration option that can be
   switched off, because it is the fallback that keeps the product
   administrable when every external mechanism fails.
3. Everything §3-§7 fixes about **sessions, RBAC and audit is mechanism-
   independent** and unchanged: one active session per identity, the partial
   unique index that makes it structural, takeover/refuse, the per-request gate
   chain, `authz_decisions`, and the two-audit-row takeover shape of Correction
   C-1. A local identity is an identity; it does not get a second session model.

### What this correction deliberately does NOT settle

Naming a mechanism is not designing it. None of the following is decided here,
and **nothing may be implemented against a guess**:

- How a local credential is stored and verified — the algorithm, its
  parameters, and where the material lives relative to `C1` §6's secret-file
  discipline. A password hash is credential material and `AGENTS.md`'s
  privacy law applies to it.
- Lockout, rate limiting and failed-attempt recording, and whether a failed
  local bind is audited identically to a failed directory bind.
- How a local identity acquires `role_bindings`, given §3's model binds roles
  to a directory-resolved group reference. A local user has no group to
  resolve; this is the sharpest open question and it is an authorization
  question, not a login question.
- Whether the first local administrator is seeded, and by what authority — a
  bootstrap identity is a security boundary of its own.
- The order mechanisms are tried, and whether "fallback" means *after a
  directory failure* or *always available in parallel*. The Product Owner's
  wording is "the fallback is always the local user"; the precise trigger is
  not fixed here.

These belong to a successor contract for local authentication, which must
freeze before any local login path is built. Phase 1's shell (composition root,
empty UI, menus) does not depend on any of them and is not blocked by this.

### Standing

`UI2_0_C3_IDENTITY_SESSIONS_RBAC_CONTRACT.md` remains FROZEN. This correction
widens its scope statement and records a ruling; it removes no clause and
weakens no gate. The LDAP mechanism of §2 and §4.4 is unchanged, including the
trust-store gap reported in
`docs/design/LDAP_TLS_TRUST_STORE_PIN_GAP_2026_09_12.md`, which now blocks one
of two Phase 1 mechanisms rather than the only one.
