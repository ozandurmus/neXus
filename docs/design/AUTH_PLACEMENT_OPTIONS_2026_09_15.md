# Authentication placement options after service separation

## Status

**DRAFT -- OPTIONS ANALYSIS FOR PRODUCT OWNER DECISION, NOT AUTHORITY, NOT A RECOMMENDATION**

## 1. Question and boundary of this document

`PO_DECISION_RECORD_2026_09_13D_INDEPENDENTLY_DEPLOYABLE_SERVICES.md`
§3 calls `AUTH-PLACEMENT` "the largest open question created by DS-1" and
states that it blocks any second service reaching an authenticated surface.
Its §4 consequence remains in force: until the Product Owner decides this
question, the existing `service` is the only authenticated surface. Backup and
failover are authenticated surfaces, so neither can become a second service
before that decision.

This document describes three placements and chooses none. It does not decide
which capability becomes a service (`13D` DS-3), `DATA-OWNERSHIP`,
`INTER-SERVICE-BOUNDARY`, or any other open item in `13D` §3. Readiness of an
option is not authorization to implement it.

## 2. The complete boundary today

The current boundary is more than login or a cookie. A placement is complete
only if it preserves every input, state dependency, ordered check, side effect,
and fail-closed outcome below.

### 2.1 Inputs and state

The current `GateChain` consumes these request facts:

- the raw session-cookie value;
- whether the HTTP method is state-changing;
- the CSRF header and Origin header;
- the closed `action_id` and opaque `target_ref`; and
- the evaluation time.

It depends on these server-side facts and controls:

- `sessions`: hashed `session_id`, `actor_fingerprint`, per-session
  `csrf_secret`, `state`, `created_at`, `last_seen_at`, `idle_deadline_at`,
  `absolute_expires_at`, takeover linkage, ending actor, and ending reason;
- the `sessions` partial unique index that permits at most one `ACTIVE` row per
  `actor_fingerprint`, plus the single-transaction takeover that supersedes the
  prior row before inserting the replacement;
- session lifecycle writers: login and login-resolution, the idle/absolute
  reconciler, security-administrator revocation, and, when enabled,
  access-group-loss revalidation;
- `actor_authz_state`: the actor's resolved group set, `resolved_at`, and
  `valid_until`; stale or missing state is not permission;
- the closed action registry: action existence, required role token,
  taxonomy/console-submittable classification, and any action-specific E5/E6
  checks;
- active `role_bindings`, including their opaque encrypted group reference and
  revocation state;
- the four-valued RBAC evaluator and the append-only `authz_decisions` store;
- the optional local-credential lookup and the
  `enforcePasswordChangeOnFirstLogin` posture; and
- the `directory_posture_enabled` posture and revalidation result. While
  revalidation is disabled or unavailable, authorization fails closed after
  `actor_authz_state.valid_until`.

The browser-held session credential is presently one `Secure`, `HttpOnly`,
`SameSite=Strict` opaque cookie. Only its SHA-256 hash is stored. The CSRF
secret is separate state stored on the session row; it is not the cookie.

### 2.2 Ordered checks and effects

The implementation executes this order:

1. **E1 session authenticity.** Require a session cookie; hash its raw value;
   find the corresponding session row; require `ACTIVE`; require the current
   time to be before both idle and absolute deadlines. For a state-changing
   method, require a CSRF header exactly equal to that session's CSRF secret,
   then require an Origin header. The current `GateChain` checks Origin
   presence only; it does not compare the value with an allowed origin.
2. **E1 success effect.** Heartbeat the session, updating activity with the
   session's existing idle-window duration, then resolve the
   `actor_fingerprint` from that session. Heartbeats do not create session
   audit rows.
3. **Password-change gate.** When its posture is enabled and a local identity
   still has its seeded password, refuse every registered action with
   `PASSWORD_CHANGE_REQUIRED`. Password change, session status, and sign-out
   do not enter this registered-action chain.
4. **E2 action identity.** Require `action_id` to exist in the closed action
   registry; otherwise return `404 ACTION_UNKNOWN`.
5. **E3 taxonomy admissibility.** Require the action to be console-submittable;
   otherwise return the class-1 refusal. E4 does not replace this result.
6. **E4 authorization.** Evaluate the actor, action's required role token, and
   current time against active bindings and fresh actor authorization state.
   The only outcomes that proceed are `PERMITTED` and
   `NO_APPLICABLE_AUTHORITY`; `DENIED` and `AUTHZ_NOT_EVALUATED` fail closed.
   Insert an `authz_decisions` row carrying session, actor, action, target,
   outcome, authority, reason, and binding linkage before returning the E4
   result.
7. **E5 subject/target integrity and E6 prerequisites.** These are mandatory
   extension points in the frozen chain. The current implementation registers
   no action-specific E5/E6 check, so no additional check executes today.
8. **Proceed.** Pass the hashed session id and actor fingerprint to the
   handler. A failed gate never reaches a mutating handler.

`E1` through `E6` presently run as one ordered, route-enforced interceptor
chain. `E7` is separate: it runs at job claim time and includes a fresh E4
re-evaluation for the recorded owner where required. Render requests also run
the declared gates per action and return authorization affordances rather than
asking the browser to calculate roles.

## 3. Option A -- every authenticated service carries the gate chain

### 3.1 What has to be built

Each service that exposes an authenticated route needs the E1-PWD-E6 chain,
route-coverage enforcement, session hashing and lookup, heartbeat, the action
registry entries it serves, taxonomy checks, the RBAC evaluator, E5/E6 hooks,
the refusal envelopes, and authorization-decision recording. Login and session
lifecycle mutation still need one authoritative implementation. Shared chain
code can reduce source duplication, but every independently released service
still becomes an enforcement point whose version and configuration can drift.

### 3.2 Shared or replicated state; learning that a session ended

Every service needs current session state, CSRF state, action metadata,
bindings, and actor authorization freshness. Direct reads from one
authoritative store make session termination visible on the next request but
make that store and schema a shared runtime dependency. Replicated stores or
caches require an ordered invalidation stream for `SUPERSEDED`, `EXPIRED`, and
`REVOKED`, plus a bounded freshness contract. A replica that cannot prove its
view is current must fail closed; otherwise a terminated session remains
usable there. Choosing shared versus replicated storage is a
`DATA-OWNERSHIP` and `INTER-SERVICE-BOUNDARY` dependency, not a decision made
here.

### 3.3 Keeping one active session per identity

All login and takeover writes must converge on the one structural uniqueness
constraint for `actor_fingerprint`. No service may mint its own independent
session row or revive a terminal row. If session stores are separated, an
equivalent cross-service serialization and uniqueness authority must be
frozen before this option can preserve the guarantee; application-level
"check then insert" is insufficient.

### 3.4 CSRF and Origin

On one browser origin with path routing, the same Strict cookie can reach each
service only if cookie scope and trusted routing are defined consistently;
each service must compare the CSRF header with the selected session row and
perform the frozen Origin check. With multiple browser origins, Strict-cookie
delivery and per-origin cookie/CSRF acquisition change, so the allowed-origin,
credential-delivery, and preflight policy must be frozen rather than inferred.
The current presence-only Origin behavior must not be described as value
validation.

### 3.5 Partial outage or lag

An unreachable service removes only its capability, but an unreachable shared
session/RBAC store removes every authenticated capability that depends on it.
A lagging service or replica must refuse when session state, binding state, or
actor authorization freshness is not provably current. Heartbeats arriving
through different services also need one authoritative ordering so lag cannot
extend an expired idle deadline.

### 3.6 Compromise blast radius

A compromised service has the authority to evaluate sessions and RBAC for its
routes and may receive the browser's session cookie and CSRF header. If it has
direct write access to shared session, binding, or decision tables, compromise
can extend beyond its capability into session destruction, authorization-state
tampering, or audit damage. Least-privilege database grants and per-service
action-registry scope are therefore part of this option's security cost; code
reuse alone does not contain the blast radius.

### 3.7 What must be true before a second service ships

The Product Owner must settle this placement and the state/transport
dependencies. The second service must prove route coverage, exact E1-PWD-E6
ordering, structural single-session enforcement, prompt fail-closed session
termination, CSRF and Origin behavior for its browser topology, append-only
decision recording, version-skew behavior, and least-privilege access to shared
state.

### 3.8 Frozen clauses requiring amendment

- `PO_DECISION_RECORD_2026_09_13D_INDEPENDENTLY_DEPLOYABLE_SERVICES.md` §3
  `AUTH-PLACEMENT` and §4 must receive a Product Owner successor amendment that
  records the chosen placement and lifts the one-authenticated-surface block.
- `UI2_0_C3_IDENTITY_SESSIONS_RBAC_CONTRACT.md` §6.1 must be amended from one
  interceptor chain to a per-authenticated-service chain while preserving its
  order, outcomes, route coverage, render behavior, and no-browser-RBAC rule.

No amendment to C3 §3.1 or §3.3 is inherent if every service uses the existing
single authoritative session-write constraint. Separating or replicating that
authority would require an additional successor after `DATA-OWNERSHIP` is
decided; this option does not assume it.

### 3.9 Cost to reverse

Reversal means removing gate code, credentials, state access, and route tests
from every service; moving action-aware enforcement and decision recording to
the replacement boundary; changing browser routing/cookie scope; and operating
a mixed-version migration without opening an ungated route. The longer service
teams evolve their copies independently, the more contract and behavior drift
must be reconciled during reversal.

## 4. Option B -- a front component authenticates and passes established identity

### 4.1 What has to be built

A new front component terminates the browser session, performs E1 and the
password-change gate, heartbeats the authoritative session, and sends a
cryptographically protected, audience-bound identity assertion over an
authenticated channel to the selected service. The downstream service must
reject direct traffic and forged or replayed identity headers. The downstream
service runs action-aware E2-E6 from the established identity. The frozen order
and one decision record per E4 evaluation must hold across that split.

### 4.2 Shared or replicated state; learning that a session ended

The front component owns current session/CSRF state and session lifecycle.
Downstream services need either a request-bound assertion that cannot outlive
the front's completed E1 evaluation or a revocable identity context with an
explicit freshness bound. They still need current action, binding, and actor
authorization state wherever E4 runs. No downstream component may accept a
cached identity after the front has reported termination. Shared versus
separate authorization data remains a `DATA-OWNERSHIP` dependency.

### 4.3 Keeping one active session per identity

All login, takeover, and revocation writes remain behind the front component
and the existing database uniqueness constraint. An identity assertion is not
a second session and must be minted only from the single `ACTIVE` row. Direct
service access must be impossible, or it would create an alternate session
boundary outside that constraint.

### 4.4 CSRF and Origin

With one public origin and path routing, the front component receives the
Strict cookie, checks the session's separate CSRF secret on state-changing
requests, and checks Origin before it creates an identity assertion. A service
must not treat an internal identity header as proof that CSRF and Origin were
checked unless the protected channel authenticates the front component and the
assertion binds the method, route, action, and request freshness. With several
public origins, cookie delivery, CSRF-secret delivery, allowed origins, CORS,
and preflight behavior require an explicit frozen contract; adding the front
component does not make those browser rules disappear.

### 4.5 Partial outage or lag

The front component is a new single point of failure in front of every
authenticated service. Multiple replicas can reduce instance failure but do
not remove the shared logical dependency or failures in its session store,
routing, keys, or policy. A downstream outage can remain capability-local. A
front replica with stale session or authorization state must fail closed; a
downstream service that cannot validate the assertion or reach the state needed
for E2-E6 must also fail closed.

### 4.6 Compromise blast radius

Compromise of the front component can impersonate any authenticated identity
to every downstream service within the assertion's audiences and can observe
browser session cookies and CSRF values. Compromise of one downstream service
should be capability-local only if assertions are audience-bound, the service
cannot reach sibling services directly, and it has no broad session/binding
write access. Otherwise a downstream compromise can replay identity or pivot
through shared state.

### 4.7 What must be true before a second service ships

The Product Owner must settle this placement and the split point in E1-PWD-E6.
The front component must have an availability and key-rotation contract,
trusted service identity, protected assertion format, replay bounds, audience
rules, route bypass prevention, structural single-session proof, CSRF/Origin
proof, exact gate ordering across the hop, authorization-decision linkage, and
fail-closed version-skew behavior.

### 4.8 Frozen clauses requiring amendment

- `PO_DECISION_RECORD_2026_09_13D_INDEPENDENTLY_DEPLOYABLE_SERVICES.md` §3
  `AUTH-PLACEMENT` and §4 must receive a Product Owner successor amendment that
  records the chosen placement and lifts the one-authenticated-surface block.
- `UI2_0_C3_IDENTITY_SESSIONS_RBAC_CONTRACT.md` §6.1 must be amended because
  E1-PWD-E6 no longer execute in one in-service interceptor chain; the successor
  must fix the split, ordering, identity assertion, route coverage, and failure
  semantics.
- C3 §5.3 and §6.2 must be amended because E4 and the mutation/job boundary
  occur behind the component that established the identity; the successor must
  preserve decision linkage and require the request to reach `REQUESTED` with
  the resolved actor and D7 result.

C3 §3.1 and §3.3 can remain unchanged if session writes retain their present
single authority. Any database split is contingent on the unresolved
`DATA-OWNERSHIP` decision and is not assumed here.

### 4.9 Cost to reverse

Reversal means relocating session termination, identity establishment, and any
centralized gates; changing every protected service to trust the replacement;
rotating assertion keys and service identities; removing the front routing
dependency; and migrating browser cookie/origin behavior without permitting a
direct bypass. Services coupled to front-specific headers or policy APIs raise
that cost.

## 5. Option C -- the existing authentication service issues a derived token

### 5.1 What has to be built

Authentication and the authoritative session row remain in the existing
service. It issues a signed, audience-bound, short-lived token derived from one
specific active session. Each other service needs token verification, replay
and audience checks, route coverage, E2-E6 enforcement, and decision recording.
The system also needs issuance-key rotation and a fail-closed revocation or
introspection mechanism; signature validity alone cannot report that the
underlying session was just superseded, expired, or revoked.

### 5.2 Shared or replicated state; learning that a session ended

The authentication service retains session and CSRF state. Other services may
avoid direct session-table reads only if they can prove token freshness and
session continuity. Online introspection learns termination immediately but
makes the authentication service a per-request dependency. Push revocation or
replicated status reduces that dependency but introduces ordered delivery,
lag, restart recovery, and a fail-closed freshness bound. Expiry-only learning
leaves a terminated session usable until token expiry and therefore does not
preserve the current guarantee. Action, binding, actor-authorization, and
decision-store placement remains a `DATA-OWNERSHIP` and
`INTER-SERVICE-BOUNDARY` dependency.

### 5.3 Keeping one active session per identity

Only the authentication service may create sessions, using the existing
partial unique index and takeover transaction. Every derived token must name
one underlying session and must become unacceptable when that row ceases to be
`ACTIVE`. Multiple unexpired tokens may represent one session, but they must
not behave as independent active sessions. That requires online status proof
or a revocation/freshness protocol that fails closed during lag; token expiry
by itself is insufficient.

### 5.4 CSRF and Origin

If the derived token is stored in a cookie, every receiving service still
needs the session-bound CSRF check and Origin policy; copying the session's
CSRF secret broadens sensitive state, while minting per-service CSRF state adds
lifecycle synchronization. If JavaScript sends the token as a bearer across
origins, that changes the present `HttpOnly` credential posture and increases
exposure to browser script compromise. In either shape, allowed origins, CORS,
preflight, token audience, and refresh/issuance endpoints must be frozen for
each public origin or path. The option cannot merge token, CSRF, and Origin
into one control.

### 5.5 Partial outage or lag

If issuance is unavailable, existing service tokens can continue only within
their proven freshness bounds and no new token can be obtained. If online
introspection is required, authentication-service outage blocks all dependent
services. With replicated revocation, a lagging or partitioned service must
reject tokens whose underlying session status is not provably current. A
single downstream outage remains capability-local, while key-distribution or
revocation failure can affect every token-accepting service.

### 5.6 Compromise blast radius

Compromise of the authentication service or issuance signing key permits
impersonation across every token audience and exposes the session boundary.
Compromise of one downstream service exposes tokens presented to that audience
and may permit replay there; audience binding and per-service keys prevent that
token from automatically authorizing sibling services. Broad introspection,
session-table, binding, or signing-key access would erase that containment.

### 5.7 What must be true before a second service ships

The Product Owner must settle this placement and the token transport. Before
shipping, the contract must freeze token claims and opacity, audience, lifetime,
issuance and refresh, key custody and rotation, revocation propagation or
introspection, fail-closed freshness, browser storage, CSRF/Origin/CORS rules,
route coverage, E2-E6 placement, decision recording, structural
single-session proof, and version-skew behavior.

### 5.8 Frozen clauses requiring amendment

- `PO_DECISION_RECORD_2026_09_13D_INDEPENDENTLY_DEPLOYABLE_SERVICES.md` §3
  `AUTH-PLACEMENT` and §4 must receive a Product Owner successor amendment that
  records the chosen placement and lifts the one-authenticated-surface block.
- `UI2_0_C3_IDENTITY_SESSIONS_RBAC_CONTRACT.md` §2.3, §3.3, and §3.6 must be
  amended to define the derived credential, how it remains subordinate to one
  session, and how it becomes invalid on every terminal session transition.
  Section 2.3 currently makes the session cookie the only credential
  persisting after login.
- C3 §6.1 must be amended because session authenticity/token verification and
  E2-E6 no longer run as the current one in-service interceptor chain.
- C3 §5.3 and §6.2 must be amended because token-consuming services record E4
  and may create jobs outside the existing request path; decision-to-session
  linkage and the resolved-actor/D7 handoff must remain exact.

The existing C3 §3.1 unique index can remain the structural session authority.
Changing the database topology is contingent on `DATA-OWNERSHIP` and is not
assumed by this option.

### 5.9 Cost to reverse

Reversal means withdrawing accepted token types from every service, migrating
live browser credentials, moving token consumers to a replacement identity
channel, rotating or retiring signing keys, removing revocation/introspection
infrastructure, and preserving audit linkage while old tokens expire or are
invalidated. Public token claims and long compatibility windows increase this
cost because independently released services may continue to depend on them.

## 6. Decision record required after Product Owner choice

Whichever placement is chosen requires a Product Owner successor contract; this
draft cannot lift `13D` §4. That successor must also resolve or explicitly
defer each contingent `DATA-OWNERSHIP`, `INTER-SERVICE-BOUNDARY`, action-registry,
and audit-store dependency exposed by the chosen placement. No option permits a
second authenticated service before those prerequisites and its named frozen
amendments are complete.
