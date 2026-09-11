# UI 2.0 — B1-3: Identity & sessions contract

**FROZEN — 2026-09-12, under the Product Owner's written authorization of
2026-09-12. Read as amended by `docs/design/UI2_0_B1_ADJUDICATION_2026_09_12.md`, which adjudicates twelve
cross-contract findings and answers this document's open items.**

This document is the mechanical implementation contract for
`ui2_b1_03_identity_sessions`, workflow §5 Phase B1 row 3: "Identity &
sessions: LDAP bind (UnboundID), single-active-session rule (design §7.4
table as tests), role bindings, `E1`–`E7` chain on the HTTP layer (`S-h`)."
It writes no `ui2/` source and issues no Flyway SQL itself — it fixes what
the implementing movement's classes, migration, and tests must contain, and
how those tests must fail. Everything here restates
`docs/design/UI2_0_C3_IDENTITY_SESSIONS_RBAC_CONTRACT.md` (`C3`, **FROZEN —
PRODUCT OWNER APPROVED, 2026-09-09**) into implementation terms, exactly as
`UI2_0_B1_02_SCHEMA_V1_CONTRACT.md` (`B1-2`) does for `C1`: nothing here
re-decides what `C3` already fixed; every load-bearing choice is cited, not
re-derived.

## 2. Scope and authority

**In scope:** the Flyway migration adding `role_bindings`, `sessions`,
`actor_authz_state`, `authz_decisions` (`C3` §1.3 owns all four end to end);
`ldap-adapter`'s UnboundID bind and re-validation classes; `service`'s
session/RBAC interceptor chain implementing `E1`–`E6`; login and
login-resolve endpoints; the self-grant check on `role_bindings` mutation;
the audit-trigger extension to `role_bindings`/`sessions`.

**Explicitly deferred:** `E7` and `C2`'s six-check pre-execution battery
(`C2` §6 owns it; `C3` §6.2's boundary is restated, not reopened, §7.2); the
capability registry and `action_id` shape (`C4`'s scope, treated here as an
opaque string with a required role token or none, `C3` §1.2);
`devices`/`endpoints`/`credential_references` content (`B1-4b`, `C1` §3.1);
enabling `DIRECTORY-POSTURE` (`D-6`) — the service-account bind and real
group-reference persistence are specified in full (`C3` §4.4) but **stay
disabled** (`directory_posture_enabled` defaults `false`, not settable to
`true` by this document, `C3` §4.4.3; this movement's tests use a test LDAP
directory only); job-lifecycle behaviour at claim time (`C2`'s state
machine; this movement supplies only the semantics `C2`'s check
re-evaluates); device contact of any kind (out of scope for every B0/B1
contract to date, baseline A-1).

## 3. Module placement

Per `UI2_0_B1_01_SKELETON_CI_DOCKER_CONTRACT.md` §2 and `DIR-5` ("LDAP is an
identity adapter only"; forbidden edge `ldap-adapter` → `service`, `worker`,
or `scheduler`):

| Class | Module | Notes |
| --- | --- | --- |
| `LdapOperatorBindPort` | port surface exposed by `ldap-adapter` | consumed by `service` per the allowed `service`→`ldap-adapter` edge |
| `UnboundIdOperatorBindAdapter`, `UnboundIdRevalidationAdapter` | `ldap-adapter`, `.identity.ldap` | `C3` §2.1/§4.4; no web-framework type (`B1-1` §2: "no web controller") |
| `PrincipalFingerprint` (Java port of the existing algorithm) | `platform-core` | `C3` §3.2: pattern only, reference implementation |
| `SessionRepository`, `RoleBindingRepository`, `ActorAuthzStateRepository`, `AuthzDecisionRepository` (jOOQ) | `persistence` | follows `C1`/`B1-2`'s home |
| `V<n>__identity_sessions_rbac.sql` | `service` migration resources | additive to `B1-2`'s `V1`; numbering is §12 item 1 |
| `SessionAuthenticityInterceptor` (`E1`), `ActionRegistryInterceptor` (`E2`), `TaxonomyAdmissibilityInterceptor` (`E3`), `RbacDecisionInterceptor` (`E4`), per-action `E5`/`E6` hooks | `service`, `.service.security` | only module the map allows a controller/interceptor in |
| `LoginController`, `LoginResolveController`, `RoleBindingAdminController` | `service`, `.service.api` | never call UnboundID SDK types directly (`DIR-5`) |

**Dependency edges this movement may add:** `service`→`ldap-adapter`
(already permitted, `B1-1` §2) and `service`/`ldap-adapter`→`platform-core`
(`DIR-1`). **No edge `DIR-5` forbids is added**: `ldap-adapter` gains no
dependency on `service`, `worker`, or `scheduler`, and no class in it
imports a servlet/Spring MVC type. No new edge touches `frontend`, `worker`,
or `scheduler`. The `P-1` architecture test (`B1-1` §5) is extended with one
more forbidden-edge assertion (`ldap-adapter`→`service`), not replaced.

## 4. LDAP bind specification

**Shape** (`C3` §2.1): UnboundID SDK. The **operator bind** is a single
`SimpleBindRequest` per `POST /login`, TLS-verified against the corporate CA
bundle; opened, bind attempted, group set read, connection **closed
immediately** — never pooled across logins. The **re-validation adapter**
(inert while `directory_posture_enabled=false`) uses a small bounded pool
(proposed min 0, max 4), idle connections recycled after 60s, a failed
connection discarded rather than retried.

**Failure behaviour** (`C3` §2.2): unreadable TLS trust material at startup
fails closed (refuses to start); an empty password is refused **before any
LDAP call** (RFC 4513 unauthenticated-bind trap, `AG-2`); a failed bind
returns generic `401 INVALID_CREDENTIALS`, never distinguishing
unknown-identity from wrong-password (`SR-D7`); a directory-unreachable
login bind returns `503 DIRECTORY_UNAVAILABLE` — a claim about the
directory, never the credential, never retried against a cached one (none
exists); an unreachable re-validation cycle leaves that actor's
`actor_authz_state` unrefreshed, so once `valid_until` elapses the actor
evaluates `AUTHZ_NOT_EVALUATED` — never `DENIED`, never a stale `PERMITTED`
(`AG-11`).

**Credential handling** (`C3` §2.3): the password lives in a mutable
`char[]`, never a `String` (architecture-tested), for one bind call; zeroed
immediately after, success or failure; never written to disk, a log line, or
an exception message beyond component/purpose name; never held for a
re-bind. The session cookie is the only credential persisting past login.

**Credential source** (`C1` §6.2 `DEV.2.1`/`DEV.2.2`, `C3` §4.4.2): the
re-validation service-account password resolves from
`SECURITYEXPERT_UI2_LDAP_REVALIDATION_SERVICE_ACCOUNT_PASSWORD_FILE`,
recorded as a `secrets_metadata` row (`component='ldap_revalidation_adapter'`,
`purpose='ldap_service_account'`); re-read every cycle, not cached for
process lifetime, so rotation applies without a restart. Never in a URL or
an exception message — the operator's bind DN is logged only as
`principal_fingerprint`, never the literal DN.

## 5. Session model

`sessions` carries state `ACTIVE`/`SUPERSEDED`/`EXPIRED`/`REVOKED`, a closed
`end_reason` vocabulary, and the partial unique index
`ux_sessions_one_active_per_actor ON sessions(actor_fingerprint) WHERE
state='ACTIVE'` (`C3` §3.1) — this is what makes single-active-session
**structurally** impossible to violate, not merely application-checked. A
takeover's `UPDATE...SET state='SUPERSEDED'` and the new session's `INSERT`
run in one transaction so the index is never transiently absent.

**State transitions** (`C3` §3.3):

| Situation | Transition | Causer |
| --- | --- | --- |
| `I` has no `ACTIVE` row; logs in | `(none)`→`ACTIVE` | login flow |
| `I` has `ACTIVE` `S1`; logs in, resolves **takeover** | `S1`: `ACTIVE`→`SUPERSEDED(by=S2)`; `S2`: `(none)`→`ACTIVE`, one transaction | login-resolve flow |
| `I` has `ACTIVE` `S1`; resolves **refuse**, or never resolves | no change; `409` on the attempt | login-resolve flow / inaction |
| `S1` idle past `idle_deadline_at` | `ACTIVE`→`EXPIRED(idle_timeout)` | reconciler |
| `S1` past `absolute_expires_at` | `ACTIVE`→`EXPIRED(absolute_lifetime)` | reconciler |
| `security_admin` ends `S1` | `ACTIVE`→`REVOKED(revoked_by_admin, ended_by=…)` | human, audited |
| re-validation: `I` no longer in access group | `ACTIVE`→`REVOKED(access_group_lost)` | re-validation adapter (inert while disabled) |
| tab re-opened | no transition; `last_seen_at` only | — |
| service restart | rows survive; next request re-runs `E1` | — |

No other transition exists; `SUPERSEDED`/`EXPIRED`/`REVOKED` are terminal —
a new login always creates a new `session_id`.

**What the first session sees on takeover**: nothing proactive; its next
request fails `E1` with `401 SESSION_SUPERSEDED`. **What the incoming login
sees**: `POST /login` returns `409 LOGIN_CONFLICT {conflict_token,
prior_session}`, then `POST /login/resolve {conflict_token, action}`.
**Default is refuse-by-inaction**: if `/login/resolve` is never called,
`S1` stays untouched — takeover is always an explicit, separate call, never
inferred from silence or a timeout (`C3` §3.4). The token expires after a
short bound (proposed 2 minutes); an expired/unresolved token requires a
fresh `POST /login` with the password re-verified.

**What is audited**: the `sessions` trigger fires `AFTER INSERT`/`AFTER
UPDATE OF state` only — never on a `last_seen_at` heartbeat (`C3` §3.5).
Takeover's audit row attributes to the **new** session's actor; the
reconciler transitions and `access_group_lost` attribute to reserved system
actors (`system:session_reconciler`/`system:revalidation_adapter`), audited
identically to a human action — one row per transition, none per heartbeat.

**Token and expiry** (`C3` §3.6, PO-tunable defaults, §12): `Secure`,
`HttpOnly`, `SameSite=Strict` cookie, opaque 256-bit token; stored as
`SHA-256(raw cookie)` hex, never the raw value; `csrf_secret` minted at
creation, required on state-changing requests; idle timeout 30 min from
`last_seen_at`; absolute lifetime 10 hours from `created_at`, never
extended.

## 6. Role bindings

The **closed role-token vocabulary** (`C3` §4.1, not added to or removed
from here): `role:viewer`, `role:operator`, `role:onboarding_admin`,
`role:backup_admin`, `role:compliance_admin`, `role:security_admin`, each
with the "may/may not" scope `C3` §4.1 tables. No token implies another by
hierarchy. Indirection: `action_id` (`C4`) → role token (repository
-committed) → role binding (`role_bindings` row) → AD group reference
(never in the repository, referenced only by `binding_id`). Every `C4`
action declares exactly one required token, or none.

`role_bindings` DDL (`C3` §4.2): opaque `binding_id`, `role_token`,
`group_reference_encrypted` (`BYTEA`, never plaintext/logged),
`group_reference_key_id`, `created_by_actor_fingerprint`, `created_at`,
`revoked_at`, `revoked_by_actor_fingerprint`. The encryption key follows
`C1` §6.3's envelope pattern, resolved through §4's `<VAR>_FILE` mechanism,
recorded as `secrets_metadata` (`purpose='role_binding_group_ref_key'`).

**Unmapped identity gets no role, never a default** (`C3` §5.1): a bind
result maps to roles strictly through `actor_authz_state`'s resolved group
set matched against active `role_bindings`. An identity matching no
`role_bindings` row for any token holds **no role at all** — every
role-gated action evaluates `AUTHZ_NOT_EVALUATED` or `DENIED`, never
`PERMITTED`; actions declaring no token still evaluate
`NO_APPLICABLE_AUTHORITY`. No fallback role and no code path grants a
default token — symmetric with `C3` §5.1's rule that `AUTHZ_NOT_EVALUATED`
is never downgraded, applied here to forbid the opposite upgrade.

**Self-grant and bootstrap** (`C3` §4.3, `SR-D5`): every `role_bindings`
mutation requires `role:security_admin`; the server refuses `403
SELF_GRANT_REFUSED` when the acting admin's own group set already contains
the group being bound or revoked. The **first** `security_admin` binding is
created by a CLI-only, deployment-controlled action (`C1` §2.4's
`ui2_migrate` posture), attributed to a reserved bootstrap marker, itself
audited.

## 7. The `E1`–`E7` chain on the HTTP layer

| Order | Gate | Precondition | On failure |
| --- | --- | --- | --- |
| 1 | `E1` | Valid hashed session id → `ACTIVE` row, not past `idle_deadline_at`/`absolute_expires_at`; CSRF matches for state-changing methods; origin checked | `401` (`SESSION_INVALID`/`SESSION_SUPERSEDED`/`SESSION_EXPIRED`/`SESSION_REVOKED` per the row's state) |
| 1′ | `E1` actor resolution | session → `actor_authz_state` (group set, `resolved_at`, `valid_until`) | folds into `E4`'s freshness check |
| 2 | `E2` | `action_id` exists in `C4`'s registry | `404 ACTION_UNKNOWN` |
| 3 | `E3` | `ActionClass.consoleSubmittable` — class 1 always refused | `403`, named reason; never re-evaluated inside `E4` (`H5c`) |
| 4 | `E4` | The four `D7` outcomes below | `403 ACTION_REFUSED` for `DENIED`/`AUTHZ_NOT_EVALUATED`; proceeds otherwise |
| 5 | `E5` | Action-declared subject/target integrity (e.g., `device_id` exists, not disabled) | `403`/`404`, action-specific (`C4`) |
| 6 | `E6` | Action-declared prerequisites (e.g., profile `APPROVED`) | `409`/`422`, action-specific (`C4`) |
| 7 | `E7` | **Not this contract's gate** | §7.2 |

**Four `E4` outcomes** (`C3` §5.1): `PERMITTED` (token bound, group set
contains the reference) proceeds; `NO_APPLICABLE_AUTHORITY` (no required
token) proceeds; `DENIED` (bound, actor not a member) refuses; `AUTHZ_NOT_
EVALUATED` (no active binding, or stale/missing `actor_authz_state`)
refuses — never downgraded to `NO_APPLICABLE_AUTHORITY`.

**Refusal envelope** (`C3` §5.2, `AC-1`): `403`
`{"error":"ACTION_REFUSED","action_id","outcome","authority","reason_code","decision_id"}`,
`decision_id` = the `authz_decisions` row; closed `reason_code`:
`actor_not_in_required_group` (`DENIED`), `role_token_unbound` (`AUTHZ_NOT_
EVALUATED`, zero bindings for the token), `actor_group_set_stale` (`AUTHZ_
NOT_EVALUATED`, `valid_until` elapsed).

**Visible-but-refused, HTTP and UI agreeing** (`C3` §5.2/§5.4): a `GET`
rendering affordances runs `E1`–`E6` per action and returns `200` with
`action_affordance[action_id]={outcome, authority?, reason_code?}` — a
refused affordance still renders, never disappears. The front end computes
no visibility decision of its own (`AG-J3`: no role-conditional rendering in
built assets); the same action's `GET`-time affordance and `POST`-time
refusal must report the same `outcome`/`reason_code`.

**Composition boundary with `E7`** (`C3` §6.2): not run on the HTTP layer.
This movement's job ends at `REQUESTED`; `C2`'s six-check battery **is**
`E7`, not re-specified or duplicated here. `C2`'s check 1 (approval
re-checked fresh at claim) **is** this movement's `E4` re-evaluated against
the *current* bindings — `C2` implements the mechanical re-check, this
movement supplies the semantics it evaluates.

## 8. Test specification

Tests marked **[directory]** run against a test LDAP directory
(OpenLDAP/ApacheDS Testcontainer, synthetic groups) — never the corporate
directory — and **cannot run without one**. All others use Testcontainers
PostgreSQL only.

1. **SingleActiveSessionStructurallyEnforced** — direct SQL `INSERT` of a
   second `ACTIVE` row for an already-`ACTIVE` `actor_fingerprint`; fails
   unless the partial index rejects it.
2. **ConcurrentLoginsFromOneIdentity** — two concurrent HTTP logins, one
   identity; fails if both ever hold `ACTIVE` at once, or the loser lacks a
   `409`/`conflict_token`.
3. **TakeoverTransition** — takeover must leave `S1` `SUPERSEDED(by=S2)`/`S2`
   `ACTIVE` and exactly one audit row, one transaction.
4. **RefuseLeavesPriorSessionUntouched** — refuse must leave `S1` unchanged,
   no `S2`, `409 LOGIN_REFUSED_ACTIVE_SESSION`.
5. **RefuseByInactionIsDefault** — `409` returned, `/login/resolve` never
   called; fails if `S1` changes before/after token expiry.
6. **HeartbeatGeneratesNoAuditNoise** — heartbeat → zero audit rows;
   state-column update → exactly one.
7. **UnmappedIdentityReceivesNoRole [directory]** — identity matching no
   `role_bindings` row; fails if any role-gated action ever evaluates
   `PERMITTED`, or a default token is assigned.
8. **UnboundActionRefusalShape** — token with zero active bindings; fails
   unless `403 AUTHZ_NOT_EVALUATED / role_token_unbound` with a matching
   `authz_decisions` row.
9. **BoundButNotMemberRefusalShape [directory]** — token bound to `G`, actor
   not a member; fails unless `403 DENIED / actor_not_in_required_group`.
10. **StaleGroupSetYieldsNotEvaluated** — `valid_until` forced past; fails if
    the outcome is `DENIED`/stale `PERMITTED` rather than `AUTHZ_NOT_
    EVALUATED / actor_group_set_stale`.
11. **ChainRefusesAtEachLinkInOrder** — one crafted single-failure request
    per gate (`E1`–`E6`), every earlier link otherwise passing; fails if a
    later gate's check runs first, or status/reason mismatches the gate
    under test.
12. **E3NeverReevaluatedInsideE4** — a class-1 action whose token would also
    independently fail `E4`; fails unless the response names only `E3`'s
    refusal (`H5c`).
13. **SelfGrantRefused [directory]** — a `security_admin` whose group set
    already contains `G` attempts to bind a token to `G`; fails unless `403
    SELF_GRANT_REFUSED`, no row created.
14. **DirectoryUnreachableLoginFailsClosed [directory]** — directory stopped
    before a login bind; fails unless exactly `503 DIRECTORY_UNAVAILABLE`,
    never `401`, never a cached-credential retry. Cannot run without a
    directory server to stop.
15. **DirectoryUnreachableRevalidationLeavesStale [directory]** —
    re-validation bind fails; fails unless the actor's row stays unrefreshed
    and, once `valid_until` elapses, `E4` returns `AUTHZ_NOT_EVALUATED`,
    never `DENIED`/stale `PERMITTED`.
16. **PasswordNeverInString** — static scan of the login path; fails if any
    `String` constructor/method receives the raw password.
17. **NoRoleConditionalRenderingInFrontend** — grep-level scan of built
    assets for role/group strings (`AG-J3`).

No test proves more than its own assertion: test 1 proves the index rejects
a bypassed `INSERT`, not that the login handler never races (test 2 covers
that); test 6 proves scoping on two update patterns, not general trigger
correctness; test 11 proves order for the six crafted cases, not exhaustive
multi-failure coverage.

## 9. Acceptance criteria

- **AC-1.** The four tables exist as `C3` §3.1/§4.2/§5.3 sketch them,
  including the partial index and both audit triggers with `sessions`'
  `AFTER UPDATE OF state` scoping.
- **AC-2.** Single-active-session holds under SQL bypass and concurrent HTTP
  logins; takeover/refuse transition per §5's table; refuse is the confirmed
  default-by-inaction.
- **AC-3.** An unmapped identity never receives a default role or
  `PERMITTED` for any role-gated action.
- **AC-4.** `E1`–`E6` run as one interceptor chain, in order, each link
  refusing independently before a later link runs; `E3` never re-evaluated
  inside `E4`.
- **AC-5.** The refusal envelope and closed `reason_code` vocabulary match
  exactly for the unbound-token and bound-but-not-member cases, each with an
  `authz_decisions` row.
- **AC-6.** A stale `actor_authz_state` row yields `AUTHZ_NOT_EVALUATED`,
  never `DENIED`/stale `PERMITTED`.
- **AC-7.** Self-grant is refused; the first `security_admin` binding comes
  only through the CLI bootstrap path.
- **AC-8.** A directory-unreachable login fails closed `503`; an unreachable
  re-validation cycle leaves the actor `AUTHZ_NOT_EVALUATED` once
  `valid_until` elapses, never silently extended.
- **AC-9.** No raw password is ever held in a `String`; no LDAP credential,
  DN, or connection URL appears in a log line or exception message beyond
  component/purpose name.
- **AC-10.** No role-conditional rendering exists in built assets; a `GET`
  affordance and a `POST` refusal for the same action/actor/instant agree.
- **AC-11.** No dependency edge `DIR-5` or any other `B1-1` §2.1 rule
  forbids is introduced; the extended `P-1` test passes.
- **AC-12.** `directory_posture_enabled` defaults `false`, is not flipped to
  `true` by this movement's code, and the re-validation pool opens no
  connection while it is `false`.

## 10. Validation plan

```
./ui2/gradlew -p ui2 architectureTest --tests "*Dir5*" --tests "*P1*"
./ui2/gradlew -p ui2 integrationTest --tests "*Session*" --tests "*Login*" --tests "*Rbac*" --tests "*Authz*" --tests "*Ldap*"
./ui2/gradlew -p ui2 check
podman build --file ui2/Containerfile --tag nexus-ui2:local ui2
python3 -m pytest -q -p no:cacheprovider tests/test_architecture_convergence.py tests/test_cold_start_budget.py
python3 scripts/repository_privacy_check.py
```

## 11. Worker route

**Sonnet 5, normal.** This movement is deterministic implementation against
a frozen contract (`C3`): the bind mechanics, session state machine,
role-binding persistence, and gate ordering are all fixed; the work is
literal Java, Flyway SQL, and JUnit tests against that fixed shape — no new
architecture, storage, or security-boundary decision is on the table, so
extended thinking would be more than the step needs.

## 12. Open items for the Product Owner

1. **Flyway version-number allocation across concurrent B1 movements**
   (`B1-2` §11 item 2, restated). No document assigns which movement ships
   which `V<n>` file or order; this movement adds one additive migration.
   Confirm the sequence against `B1-4`/`B1-4b`'s own migrations before
   either lands.
2. **`DIRECTORY-POSTURE`'s practical consequence** (`C3` §9 item 1). Until
   `D-6`'s corporate-policy verification is recorded, no actor in a real
   directory deployment can hold `PERMITTED` for longer than one
   re-validation interval past login. This movement's tests are unaffected
   (test directory only); the operational ceiling is now concrete in code.
3. **`SR-D10` (fingerprint attributability), still open** (`C3` §9 item 5).
   This movement implements the existing unkeyed algorithm and adds no
   attributable-display mechanism; whether one should exist is unchanged.
4. **`M14 U-1` (nested-group membership), still open** (`C3` §9 item 6).
   `E4` is an exact-match set-membership check; it does not attempt
   nested-group expansion.
5. **Proposed numeric defaults are non-load-bearing and PO-tunable**
   (`C3` §3.6/§2.4): idle timeout (30 min), absolute lifetime (10 h),
   re-validation interval (15 min), conflict-token bound (2 min), login
   throttles (10/min per source, 5/5 min per identity), re-validation pool
   (min 0, max 4, 60s idle recycle). Implemented as configuration, not
   hard-coded, so the Product Owner can adjust any without a contract
   amendment.

No contradiction between `C3`, `C1`, `B1-1`, or `B1-2` was found. `C1` §3.1
reserves the four tables for `C3` without sketching them, and `C3` §1.3
accepts and sketches all four — the two agree on ownership. `B1-1` §2 places
the UnboundID adapter in `ldap-adapter` with "no web controller," and `C3`
§2.1/§4.4 describes exactly an adapter (bind + search), never a controller —
no tension. `B1-2` §4 excludes this movement's four tables from `V1` as
"owned by `C3`, delivered with workflow B1 row 3" — consistent with this
document taking that ownership up in §3/§9.
