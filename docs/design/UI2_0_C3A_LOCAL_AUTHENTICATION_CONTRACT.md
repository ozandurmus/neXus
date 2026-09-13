# UI 2.0 — C3A local authentication contract

## Status

**DRAFT — NOT implementation authority.** This document authorizes nothing:
no `ui2/` source, no Flyway migration, no screen, and no configuration value
may be built from it while its status line reads `DRAFT`. Applying a status
(`FROZEN` or otherwise) is the Product Owner's act, not this movement's — see
`AGENTS.md` "Contract-status law." Written as the successor
`docs/design/UI2_0_C3_IDENTITY_SESSIONS_RBAC_CONTRACT.md` (`C3`, FROZEN —
PRODUCT OWNER APPROVED, 2026-09-09) Correction C-2 (2026-09-12) requires:
Correction C-2 §"What this correction deliberately does NOT settle" lists
credential storage, lockout, local role-binding acquisition and bootstrap
identity as unresolved and states "these belong to a successor contract for
local authentication, which must freeze before any local login path is
built." This is that successor.

---

## 1. Scope, authority chain, and what this contract does not own

### 1.1 What this contract owns

The **local** authentication mechanism only: how a local credential is
stored and verified, the two bootstrap accounts, local login/lockout
behaviour, the minimum storable-password rule, how a local identity acquires
a role binding, and the mechanism-registry shape that makes local one member
of a pluggable set rather than the only mechanism.

### 1.2 What this contract does not own

| Not owned here | Owner | This document's relationship to it |
|---|---|---|
| Sessions: the single-active-session-per-identity rule, session tokens, takeover/refuse, session expiry | `C3` §3 | Unchanged. A local identity is an identity; it authenticates through the mechanism this contract defines and then enters `C3`'s existing session model exactly as an LDAP-authenticated identity does — no second session model, no local-only session table |
| RBAC enforcement, the four outcomes, the `E1`–`E7` gate chain, `role_bindings`/`authz_decisions` schema | `C3` §4–§6 | Unchanged. §7 below states only how a **local** identity acquires a row in `C3`'s existing `role_bindings` table; the table, its columns, and its enforcement are `C3`'s |
| The `audit_log` mechanism, the trigger, `app.actor_fingerprint`/`app.action_id` | `C1` §3.5 | Unchanged. §8 below states which local-mechanism events are audited through it; it does not add a column or a second mechanism |
| Per-component secret resolution (`<COMPONENT>_<PURPOSE>_FILE`), `secrets_metadata` | `C1` §6 | This contract's credential-verifier storage (§3) is a database column, not a component secret resolved through this mechanism — a stored verifier is not a secret the *service* holds independently of the database, it is data the database holds about an operator. Where this contract needs a secret of its own (none identified — §11), it would follow `C1` §6 unchanged |
| LDAP bind, LDAP failure handling, `DIRECTORY-POSTURE` | `C3` §2, §4.4 | Untouched. Correction C-2 names LDAP and local as the two Phase 1 mechanisms; this document is local's half only |
| RADIUS, TACACS | Later, per Correction C-2 | Not designed here. The registry (§2) only ensures a future mechanism can be added as a member |
| Container image, Kubernetes manifests, Ingress/Route | `UI2_0_B1_01C_CONTAINER_IMAGE_AND_KUBERNETES_DEPLOYMENT_CONTRACT.md` | §9 records a reported, unresolved tension; this document does not change that contract |
| User-management screens | A separate, later contract | §5 and §7 state what the mechanism requires of such a screen; the screen itself is out of scope |
| Self-service password reset, email flows, multi-factor, session-token cryptography | `C3` | Out of scope here, as it is out of scope for `C3` itself |
| Password-policy standards (complexity classes, expiry, history, reuse) | Deferred, Product Owner ruling 2026-09-13 (§4) | Not designed here |

### 1.3 Tables this contract sketches

This contract adds exactly one table, `local_credentials`, holding the
per-identity verifier and its parameters (§3.2). It adds no column to any
table `C3` or `C1` already owns. Where a local identity needs a
`role_bindings` row (`C3` §4.2), it is the same table, the same columns,
the same trigger — §7 states only how the row's `group_reference_encrypted`
column is populated for a local identity, since a local identity has no AD
group to reference.

### 1.4 Authority chain (highest first)

1. `AGENTS.md` — durable constitution: identity law, evidence laws,
   UNKNOWN/fail-closed law, sensitive-identity reporting law, privacy/DLP,
   and "Authority hierarchy" (never silently reconcile a contradiction
   between two authorities — report it).
2. `docs/design/UI2_0_C3_IDENTITY_SESSIONS_RBAC_CONTRACT.md` (`C3`, FROZEN)
   §3 (sessions), §4 (role tokens/bindings), §5 (RBAC enforcement), §6
   (gate chain) — mechanism-independent and unchanged, per Correction C-2's
   own §"What this correction settles" item 3. Correction C-2 itself
   (2026-09-12) is this contract's direct mandate.
3. This document, once its own status line reads `FROZEN` — not before.
4. `docs/design/UI2_0_C1_PLATFORM_SCHEMA_CONTRACT.md` (`C1`, FROZEN) §3.5
   (the `fn_audit_capture()` trigger mechanism this contract's new table
   attaches to, unchanged), §3.6/§6 (secrets pattern, referenced for
   contrast in §1.2 above, not reused for the verifier column itself), §7
   (opaque-identifier and CLASS 2 rules, which govern `local_credentials`
   exactly as they govern every other table in the schema).
5. `docs/design/UI2_0_B1_04B_DEVICE_MODEL_AND_ONBOARDING_CONTRACT.md` §6 —
   "a reference names a credential; it never holds one," cited here for the
   contrast this contract's §3 draws: a device credential is *referenced*
   from a table and *resolved* elsewhere; a local password verifier is
   *stored*, one-way, directly in its own table, because verifying a login
   requires comparing against it in-process — the two patterns differ
   because a one-way verifier is not a recoverable secret in the first
   place (§3.1).

**Evidence and precedent consulted — not authority.** None. This document's
scope did not require reading any `DRAFT`, `SUPERSEDED` or `DEPRECATED`
document.

---

## 2. The mechanism registry

Correction C-2: *"Authentication is a pluggable set of mechanisms, not one
mechanism... Local is always present. It is not a configuration option that
can be switched off, because it is the fallback that keeps the product
administrable when every external mechanism fails."* This section makes
that concrete.

### 2.1 The registry shape

A closed, repository-committed list of mechanism identifiers, each
implementing one interface:

```
Mechanism {
    mechanism_id: TEXT            -- closed vocabulary: 'local' | 'ldap' (Phase 1)
    attempt(identity, credential) -> AttemptOutcome
}

AttemptOutcome {
    result: 'SUCCESS' | 'REFUSED' | 'MECHANISM_UNAVAILABLE'
    resolved_actor_fingerprint: TEXT | null   -- present only on SUCCESS
    reason_code: TEXT | null                  -- present only on REFUSED/MECHANISM_UNAVAILABLE, closed vocabulary per mechanism
}
```

- **`local` is a permanent, non-removable member of the registry.** No
  configuration value, database row, or code path disables it. This is a
  direct restatement of Correction C-2's ruling, not a new decision — it is
  recorded here so the registry's own code has a place that says so, rather
  than an implementer having to infer it from `C3`'s prose.
- **`ldap` is `C3`'s existing mechanism (§2 there)**, wrapped behind the
  same interface. This contract does not change one line of `C3` §2; it
  states only that `C3`'s bind logic is reachable through this registry's
  `attempt()` call for `mechanism_id = 'ldap'`.
- **Adding a member is adding an implementation of `Mechanism`, registered
  under a new `mechanism_id`, never a change to the login flow, the session
  model, or the RBAC model.** This is what "adding LDAP later is adding a
  member and not rewriting the flow" (this document's own objective)
  concretely means, now restated the other direction: adding `radius` or
  `tacacs` later — named as later by Correction C-2, not designed here — is
  the same shape of change. Nothing about this registry's interface assumes
  a directory bind or a local table; `AttemptOutcome` is mechanism-agnostic.

### 2.2 How a mechanism is selected for one login attempt

`POST /login` carries `{identity, credential, mechanism_id}`. The
**operator's own selection at submission time chooses the mechanism**; the
server does not try mechanisms in sequence for one submitted attempt. This
is the narrowest resolution of Correction C-2's own open question ("whether
'fallback' means *after a directory failure* or *always available in
parallel*... the precise trigger is not fixed here"): this contract fixes
that a **local login is always an explicit choice**, not an automatic retry
after an LDAP failure — an automatic retry would mean submitting the same
credential value against a second mechanism without the operator's
knowledge, which this contract does not do. Correction C-2's deeper
question (does a directory *outage* itself count as a trigger for something
beyond "the operator can always choose `local` on the login screen") stays
open — §11.

- **The login screen offers both mechanisms whenever more than one is
  registered** (an explicit choice — e.g., a mechanism selector or two
  distinct login forms — the concrete screen is a later contract, §1.2).
  `local` is always offered, because it is always registered (§2.1).
- **One attempt, one mechanism.** A single `POST /login` call is routed to
  exactly the one `Mechanism.attempt()` the request names; it is never
  fanned out to every registered mechanism for the same submitted
  credential.

### 2.3 How the outcome is reported

The registry's `AttemptOutcome` (§2.1) is mapped to the HTTP response by
the **same uniform, non-identity-revealing contract regardless of which
mechanism produced it**:

- `SUCCESS` → session created against `C3` §3's existing model, using
  `resolved_actor_fingerprint` exactly as `C3` §3.2 already consumes an
  actor fingerprint from an LDAP bind — the session table does not know or
  care which mechanism produced the fingerprint.
- `REFUSED` → `401 INVALID_CREDENTIALS`, the same body `C3` §2.4 already
  specifies for an LDAP bind failure, **regardless of mechanism** — §5
  states local's own lockout-driven refusal reasons and confirms none of
  them appears in the response.
- `MECHANISM_UNAVAILABLE` → a mechanism-specific unavailability response
  (`C3` §2.2's `503 DIRECTORY_UNAVAILABLE` for `ldap`; §5 states local has
  no equivalent unavailable state, because it depends on nothing external).

This is what makes the outcome reporting mechanism-independent: a caller of
the registry (the login HTTP handler) branches on `AttemptOutcome.result`,
never on `mechanism_id`, to decide the HTTP response shape.

---

## 3. Local credential storage

### 3.1 The stored value is a one-way verifier, never a recoverable form

No product table ever holds a recoverable password. This is the same rule
`UI2_0_B1_04B_DEVICE_MODEL_AND_ONBOARDING_CONTRACT.md` §6 states for device
credentials ("a reference names a credential; it never holds one") applied
to the different, stricter case of an operator's own password: a device
credential is *referenced* and *resolved elsewhere* because the worker must
present the live value to a device; an operator's password is never
presented to anything after verification, so nothing in this product ever
needs it back. The stored column is therefore not a reference to a secret
held elsewhere — it is the one-way output of a verification function, and
recovering the original input from it is intended to be computationally
infeasible.

**Forbidden, explicitly, because a plausible-sounding wrong design has been
seen in comparable products:**

- reversible encryption of the password (any scheme with a decrypt
  operation, however the key is custodied);
- a plain-text column;
- a fast general-purpose digest (`SHA-256`, `MD5`, or any digest not
  purpose-built and parameterized for password hashing) with or without a
  salt — these are optimized for speed, which is exactly the property a
  password verifier must not have, because speed is what makes offline
  guessing cheap.

### 3.2 The verifier: algorithm family, salt, and recorded parameters

The stored value is produced by a **memory-hard password hashing function**
— the `Argon2` family (`Argon2id`, the hybrid variant, is the applicable
member: it resists both GPU/ASIC parallel-guessing attacks the memory-hard
property defends against and the side-channel concerns the pure
data-dependent `Argon2d` variant carries). `Argon2id` is named because it is
the current OWASP-recommended default for exactly this purpose; a
`bcrypt`/`scrypt` fallback is not designed here because nothing in this
brief's scope requires one — an `UNKNOWN` register entry (§11) records that
the concrete Java library and its exact default parameters are not fixed by
this contract.

```sql
CREATE TABLE local_credentials (
    local_identity_id   TEXT        PRIMARY KEY,   -- opaque, application-generated (identity law)
    verifier             BYTEA       NOT NULL,       -- Argon2id output, never a reversible form
    salt                 BYTEA       NOT NULL,       -- per-credential, cryptographically random, never reused across identities
    algorithm_id         TEXT        NOT NULL,       -- closed vocabulary, e.g. 'argon2id'; recorded so a future algorithm change is a new value, not a silent reinterpretation of old rows
    memory_cost_kib      INTEGER     NOT NULL,        -- Argon2 'm' parameter, recorded per-row
    time_cost            INTEGER     NOT NULL,        -- Argon2 't' parameter, recorded per-row
    parallelism          INTEGER     NOT NULL,        -- Argon2 'p' parameter, recorded per-row
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

- **Per-credential salt** (`salt`): generated fresh for every credential —
  never derived from the identity, never reused across rows, never
  shortened or padded (identity-law-style opacity for the salt itself,
  though it is not a product identifier — the discipline is the same:
  no normalization, no inference of equivalence from formatting).
- **Recorded parameters, not implied ones**: `memory_cost_kib`,
  `time_cost`, `parallelism` are stored **alongside** each verifier, not
  fixed globally in code, so that:
  - parameters can be **raised** later (e.g., increasing `memory_cost_kib`
    as hardware improves) **without invalidating existing credentials** —
    an existing row's stored parameters are exactly what verification uses
    for that row, so an old verifier still checks correctly against a
    login attempt even after the service's *default* parameters for new/
    changed credentials move up;
  - a verification path always reads a row's own `algorithm_id`/parameter
    columns rather than assuming the service's current defaults, which is
    what makes a future parameter increase non-breaking;
  - a **rehash-on-successful-login** upgrade path (recompute the verifier
    under the current default parameters immediately after a successful
    login whose stored parameters are below the current default,
    overwriting `verifier`/`salt`/the parameter columns and `updated_at`)
    is possible under this schema without any migration — this is what
    "parameters can be raised later" requires structurally, though whether
    the product actually performs this rehash is left to implementation
    and is not itself a load-bearing clause of this contract.
- **No product table ever holds a recoverable password**: `local_credentials`
  has no column of a shape that could hold one — every column is either an
  opaque binary output, a random salt, an enum, an integer parameter, or a
  timestamp, exactly as `C1` §3.6 states of `secrets_metadata` ("no column
  in this table can hold a secret value by construction").
- **CLASS 2, opaque identifier**: `local_identity_id` follows `C1` §7's
  identity law exactly as every other identifier in the schema — opaque
  `TEXT`, application-generated, never a numeric surrogate, never the raw
  username itself (a separate, non-sensitive `local_identity_name` column
  for display/lookup is assumed necessary but its exact shape is `UNKNOWN`,
  §11, since login must resolve a submitted username to a row and this
  contract does not yet fix whether that lookup column is case-normalized —
  case-normalizing a *username* is a different question from
  identity-law-governed identifier normalization, and is not decided here).

---

## 4. Password change and password rules

Per this movement's brief and the Product Owner's 2026-09-13 deferral of
password-policy standards: the minimum a new password must satisfy to be
**storable**, stated as a small number of checkable rules — not a policy.

- **PW-1. Minimum length.** A new password must be at least 12 characters.
  No maximum length is imposed beyond what the hashing library itself
  bounds (Argon2 has no practical application-relevant maximum at this
  length).
- **PW-2. Non-empty after the RFC 4513 check.** The same empty-password
  trap `C3` §2.2 closes for LDAP applies identically here for a different
  reason: an empty password must never reach the hashing function at all —
  refused before hashing, with the same generic response §5 defines.
- **PW-3. Not equal to the identity's own username** (case-insensitive
  comparison on the display name only, never on `local_identity_id` — the
  opaque identifier is never compared against user input). A trivial,
  checkable floor, not a blocklist or a complexity class.

No complexity class, no expiry, no history/reuse rule, and no scoring
function is defined. This is the Product Owner's explicit deferral
(2026-09-13, recorded in §6 below) and this section states nothing beyond
it.

**Changing a password is possible from the first release.** A
`POST /local-credentials/{id}/change-password` (or equivalent) path exists
and is callable by the identity itself (current password re-verified
first, exactly as a login attempt is) or by `role:security_admin`
administering another identity's credential. This is a mechanism
requirement, not a screen design (§1.2); a successor screen contract
supplies the UI.

---

## 5. Login behaviour and lockout

### 5.1 Failed-attempt counting and threshold

- **LOCK-1. Counted dimension: per identity, not per source.** A failed
  local login attempt increments a counter scoped to the target
  `local_identity_id`. This differs from `C3` §2.4's LDAP throttle, which
  runs **both** per-source and per-identity — local authentication has no
  directory to protect from a bind-amplification attack against a corporate
  account (`C3`'s `SR-D7` concern), and this product's own login endpoint
  is the only thing a per-source throttle would protect. A per-source
  throttle on the shared `/login` endpoint is still warranted, but as a
  **mechanism-independent** rate limit on the endpoint itself (already
  implied by `C3` §2.4 for the `ldap` path and reused unchanged for
  `local` submissions against the same endpoint) — this contract does not
  duplicate that endpoint-level throttle, it states only the **local**-
  mechanism-specific, per-identity lockout that sits behind it.

  **Why per-identity and not also a separate local per-source threshold**:
  the risk this lockout defends against is guessing one bootstrap or
  operator password by brute force; the attacker's location does not change
  which identity is being guessed, and a second per-source counter would
  add complexity without closing a distinct threat this product's own
  endpoint-level throttle does not already narrow. If real-environment
  evidence later shows source-scoped local guessing is a live threat beyond
  what the endpoint throttle covers, that is a revisit, not a gap this
  document leaves silently unaddressed — recorded in §11.

- **LOCK-2. Threshold: 5 consecutive failed attempts** for the same
  `local_identity_id`, mirroring `C3` §2.4's LDAP per-identity number
  (5 per 5 minutes) for consistency of operator experience across
  mechanisms, though the clearing behaviour differs (§5.2) because local
  has no directory-side lockout to defer to.

### 5.2 What the lockout does and how it clears

- **Effect**: on the 5th consecutive failed attempt, `local_credentials`
  gains a `locked_until` timestamp (schema addition to §3.2's table:
  `locked_until TIMESTAMPTZ`, nullable, `NULL` = not locked). While
  `locked_until` is in the future, every login attempt for that identity —
  including one with the correct password — is refused with the same
  `401 INVALID_CREDENTIALS` body a wrong password produces (§5.3); the
  lockout is never revealed as a distinct state to the caller.
- **Clearing**: `locked_until` is set to **now + 15 minutes** at the
  moment of the 5th failure (a self-expiring throttle, the same shape as
  `C3` §2.4's LDAP per-identity throttle — "the throttle window expires
  and normal attempts resume," "not a permanent application-side
  lockout"). A **successful** login attempt (correct password, submitted
  after `locked_until` has elapsed) resets the failed-attempt counter to
  zero and clears `locked_until` to `NULL`. `role:security_admin` may also
  clear a lockout administratively (an audited action, §8) — this is a
  mechanism requirement for the account-management screen the successor
  screen contract designs, not designed here.
- **Failed-attempt counter storage**: a `failed_attempt_count INTEGER NOT
  NULL DEFAULT 0` column on `local_credentials`, incremented on each
  failure, reset to `0` on the success that follows a non-locked state or
  the elapse of `locked_until`.

### 5.3 The refusal never reveals whether an identity exists

Every local login failure — unknown `local_identity_name`, wrong password,
empty password (PW-2), and an active lockout — returns the **identical**
`401 INVALID_CREDENTIALS` body, with no field, header, or distinguishable
timing behaviour that lets a caller tell them apart. This is the same rule
`C3` §2.4 states for LDAP ("no response body, header, or timing
characteristic... allowed to differ based on whether the submitted identity
exists") applied identically to local: a lockout is a refusal, and "a
refusal is a refusal" (this movement's own framing). Concretely:

- an unknown username and a locked, correctly-guessed username produce the
  same response;
- the lookup for "does this username exist" and the (skipped, because
  locked) Argon2 verification are both performed inside a response-time
  envelope that does not leak which branch executed — an exact constant-
  time guarantee is not fixed by this contract (an `UNKNOWN`, §11), but no
  code path may return **faster** for an unknown username than for a known,
  locked one, since a fast rejection is itself a timing signal.

---

## 6. The bootstrap accounts

### 6.1 Two accounts on a clean, empty database

On a clean, empty database, exactly two local identities exist:
`nexusadmin` and `claudeadmin`.

- **`nexusadmin`** — the Product Owner's own account.
- **`claudeadmin`** — the assistant's own account. It exists so that
  **actions taken by the automation are attributable to it rather than to
  the Product Owner** — this is the entire audit-attribution reason the
  second account is worth having, stated explicitly because it is the
  reason, not an incidental detail: every `audit_log` row (`C1` §3.5)
  carries an `actor_fingerprint`, and without `claudeadmin`, every
  automation-caused mutation would carry `nexusadmin`'s fingerprint,
  making a human's and an agent's actions indistinguishable in the one
  place (`audit_log`) this product's whole audit design depends on for
  that distinction. This is a direct instance of `AGENTS.md`'s own
  invariant, "Agent actions are attributable to the agent's own identity,
  never merged with a human administrator's," applied to the first place
  in UI 2.0 where an agent identity exists at all.

Both accounts are seeded with a stored verifier per §3.2's schema (a
generated or deployment-supplied initial password — the exact seeding
mechanism, e.g. a Flyway seed migration versus a first-boot bootstrap
routine, is `UNKNOWN`, §11, because it is an implementation choice this
contract's scope does not reach).

### 6.2 The forced-change deferral — a dated Product Owner decision

**A forced password change is NOT required at this stage.** This is
recorded as the Product Owner's own decision, not this contract's
preference:

> **Product Owner decision, 2026-09-13.** Password-policy standards are
> later work. The Product Owner was told, before ruling, that a bootstrap
> credential surviving unchanged onto a reachable server is the product's
> weakest point, and declined to require a forced password change at this
> stage for that stated reason: introducing password-policy standards
> (which a forced-change mechanism presupposes — a "changed" password must
> be checked against *some* rule beyond §4's minimum, or forcing the change
> accomplishes nothing) is explicitly deferred, not accidentally omitted.

**The risk, stated plainly, not argued.** A bootstrap credential
(`nexusadmin` or `claudeadmin`) that is never changed and reaches a
network-reachable server is the single weakest point in this
authentication design: every other control this contract specifies
(memory-hard hashing, lockout, non-revealing refusals, RBAC) assumes the
credential being guessed is not a documented, product-shipped default. This
document does not re-argue the Product Owner's ruling — the risk is
recorded here so the ruling's cost is visible next to the ruling itself,
per this movement's own instruction to record the decision, the risk, and
a revisit condition rather than relitigate.

**Named REVISIT CONDITION.** The forced password change (or an equivalent
control — e.g., a mandatory rotation gate at deployment time) is **required
before either of the following, whichever comes first**:

1. the login surface becomes reachable from outside the server (§9 defines
   what "the server" means here); or
2. any production deployment.

Until one of these conditions is reached, the deferral stands. **Changing a
password is possible from the first release** regardless of the deferral —
§4's change-password path is not gated by this deferral; only the
*compulsion* to change is deferred, exactly as this movement's brief
requires.

---

## 7. RBAC in the local mechanism

`C3` §4 already owns the RBAC model in full — the closed role-token
vocabulary, `role_bindings`' schema, the four-eyes/self-grant rule, the
`E1`–`E7` gate chain — and it is mechanism-independent (Correction C-2 item
3). This section states only how a **local** identity acquires a row in
`C3`'s existing `role_bindings` table.

### 7.1 The group-reference gap, and how local closes it

`C3` §4.2's `role_bindings.group_reference_encrypted` stores "the
directory's own opaque group identifier, encrypted." A local identity has
no directory and no group. Correction C-2 names this as "the sharpest open
question and it is an authorization question, not a login question."

This contract closes it the narrowest way that reuses `C3`'s schema
unchanged: for a local identity, `group_reference_encrypted` holds an
**encrypted reference to the local identity itself**
(`local_identity_id`, encrypted under the identical envelope `C3` §4.2
already specifies, `group_reference_key_id` identifying the same key
custody chain) rather than a directory group id. A `role_bindings` row for
a local identity is therefore a **direct** binding — one row names one
local identity, not a group of them — while a `role_bindings` row for an
LDAP-resolved identity remains a group binding exactly as `C3` §4 already
specifies. `C3`'s enforcement logic (§5.1's `PERMITTED`/`DENIED` evaluation
against "the actor's resolved group set") is unchanged: for a local actor,
the "resolved group set" is the single-element set containing that
identity's own encrypted reference, resolved at login the same way `C3`
§4.4's initial group-set resolution already runs (mechanism-agnostic: it
reads whatever the authenticating mechanism reports, §2.3).

**Why this is a closure, not a redesign of `C3`**: no column is added, no
column's type changes, and no enforcement branch in `C3` §5 or §6
distinguishes a local actor from an LDAP one — `role_bindings` and
`actor_authz_state` are populated identically in shape (an encrypted
reference plus a key id) regardless of mechanism; only what the encrypted
bytes decrypt to differs.

### 7.2 Distinct team roles — the forward requirement

The Product Owner's forward requirement (2026-09-13): distinct teams will
hold distinct roles — one seeing failover, another configuration — and the
model must already represent that even while only one operator account
(beyond the two bootstrap accounts) exists.

This is already representable **without any change to `C3` or this
contract**: `C3` §4.1's closed role-token vocabulary already distinguishes
roles by function (`role:operator`, `role:backup_admin`,
`role:compliance_admin`, etc.), and §7.1 above makes a `role_bindings` row
for a local identity a one-row-per-identity binding — nothing stops a
`security_admin` from binding `nexusadmin` to one role token and a future
local identity to a different one, today, under this contract's own
schema. The forward requirement is satisfied by **not needing a
successor** when a second team's account is created — the record here is
that this contract's authors checked this specifically and found no gap,
not that a new mechanism was built to close one.

### 7.3 Bootstrap role binding

`C3` §4.3's bootstrap rule ("the first `role:security_admin` binding is
created by a CLI-only, deployment-controlled operator action... reachable
only from a deployment-controlled step, never a running service, never the
browser") is reused unchanged. `nexusadmin`'s first `role:security_admin`
binding is created this way, satisfying `C3` §4.3's existing bootstrap
path — this contract adds no second bootstrap mechanism for role binding;
it only supplies the local identity (§6) that path binds a role to.
`claudeadmin`'s role binding — what role the assistant's own account
holds — is `UNKNOWN` (§11): this contract fixes that the account exists and
why (§6.1), not what it may do.

---

## 8. Audit

Every authentication attempt, lockout, password change, and role-binding
change for the local mechanism is audited through `C1` §3.5's existing
`fn_audit_capture()` mechanism — no second audit mechanism, no local-only
log.

- **`local_credentials` carries the same trigger shape** `C1` §3.5
  specifies for every mutation-bearing table (`AFTER INSERT OR UPDATE OR
  DELETE FOR EACH ROW EXECUTE FUNCTION fn_audit_capture('local_identity_id')`),
  with the same `SET LOCAL app.actor_fingerprint`/`app.action_id`
  discipline the Java transaction interceptor already applies (`C1` §3.5,
  `C3` §3.4) before any mutation.
- **A login attempt that does not mutate `local_credentials`** (a failed
  attempt only increments `failed_attempt_count` — which **is** a mutation
  and **is** captured by the trigger above) is audited by the same means; a
  successful login additionally produces `C3` §3.5's existing `sessions`
  audit row exactly as an LDAP-authenticated login does.
- **What the trigger captures, and what it must never capture**: `C1`
  §3.5's `before_state`/`after_state` columns capture `to_jsonb(OLD)`/
  `to_jsonb(NEW)` of the mutated row — for `local_credentials`, that row
  contains `verifier`, `salt`, and the three parameter columns (§3.2).
  **This is forbidden.** No credential, no verifier, no salt and no
  parameter set may ever reach an audit row, a log line, or an error
  message (this movement's own invariant, restated from `AGENTS.md`'s
  raw-evidence and sensitive-identity laws). `local_credentials` therefore
  **cannot** use `C1` §3.5's generic `fn_audit_capture()` unmodified: this
  contract requires a **narrower** capture function for this one table —
  `fn_audit_capture_local_credentials()` — that builds `before_state`/
  `after_state` from an explicit column allowlist
  (`local_identity_id`, `failed_attempt_count`, `locked_until`,
  `created_at`, `updated_at` only), never `to_jsonb(OLD)`/`to_jsonb(NEW)`
  wholesale. This is a **named exception to `C1` §3.5's generic trigger
  shape**, analogous in kind (though not in mechanism) to `C3` §3.5's own
  named, tested exception for the `sessions` table's heartbeat columns —
  both exist because the generic mechanism would otherwise capture
  something it must not.
- **Password change is audited as an `UPDATE` of `verifier`/`salt`/the
  parameter columns**, captured by the same allowlist rule above — the
  audit row records **that** a change happened (`local_identity_id`,
  `occurred_at`, `actor_fingerprint`, `action_id`) and never the old or new
  verifier bytes.
- **Role-binding changes** for a local identity are audited by `C3` §4.2's
  existing `trg_audit_role_bindings` trigger, unchanged — `role_bindings`
  never stores a password-shaped value regardless of mechanism, so no
  allowlist exception is needed there.
- **No error message may name a credential value.** A hashing-library
  exception, a constraint violation, or a lockout refusal is logged/
  reported using component/purpose/identity-reference names only, per `C1`
  §6.2's existing rule for every other secret ("no secret value ever
  appears in a log line or an exception message; only the
  variable/component/purpose name does") applied here to the verifier.

---

## 9. Exposure

**Product Owner directive, 2026-09-13: no screen is exposed outside the
server.** The consequence for this contract: the local (and LDAP) login
surface is reachable **in-cluster only** — `POST /login`, `POST
/login/resolve`, and every path this contract or `C3` defines are not
intended to be reachable from outside the Kubernetes cluster's own network
boundary.

**Reported tension, not resolved here.**
`docs/design/UI2_0_B1_01C_CONTAINER_IMAGE_AND_KUBERNETES_DEPLOYMENT_CONTRACT.md`
§5.2 defines an `Ingress` object explicitly for **external reach** ("`Ingress`
| external reach on the local cluster and on the Ingress-based stage") and
§8's `PORT-1`/`PORT-3` fix that object (or its `Route` substitute on the
corporate platform, per Correction... no correction — per that contract's
own §2/§8) as a **permanent, non-conditional** part of the manifest set
across all three deployment stages, with no platform-conditional construct
permitted that would omit it. That contract's own text does not describe
the Ingress as optional, disabled-by-default, or gated behind a directive
like this one.

This is a direct tension between two authorities this contract must not
silently reconcile, per `AGENTS.md` "Authority hierarchy" ("Never silently
reconcile a disagreement between two authorities below — report the
contradiction and let the human or the higher authority resolve it."):

- **Authority A**: the Product Owner's 2026-09-13 directive, "no screen is
  exposed outside the server" — a verbal ruling recorded in this
  movement's own session-start packet, not yet written into any FROZEN
  contract.
- **Authority B**: `UI2_0_B1_01C_CONTAINER_IMAGE_AND_KUBERNETES_DEPLOYMENT_CONTRACT.md`
  (FROZEN) §5.2/§8, which defines and fixes an `Ingress`/`Route` object as
  the product's external reach path, present in every stage's manifest set
  by construction.

This contract **takes no position on which authority governs** the
eventual deployment manifest. It records the tension so the Product Owner
(or a successor to the deployment contract) resolves it explicitly — for
example, by scoping the Ingress to a network policy that only admits
in-cluster or VPN-bounded traffic, by removing the Ingress for this
product's login surface specifically, or by superseding this directive.
Until resolved, this contract's own login-surface design (§2, §5) does not
assume network isolation as a security control — the lockout (§5), the
non-revealing refusal (§5.3), and the credential-storage discipline (§3)
are specified as if the login endpoint may be reachable, because whether it
actually is remains unresolved by two disagreeing authorities.

---

## 10. What a reverse proxy may and may not do

**May: terminate TLS.** A reverse proxy in front of this service may
terminate the TLS connection from the browser/client, exactly as any
standard ingress/load-balancer deployment does; nothing in this contract or
`C3` requires the application process itself to hold the TLS certificate.

**May not: own identity.** A reverse proxy must never perform
authentication on this product's behalf — it must never itself validate a
credential, mint a session, or assert an already-authenticated identity to
this service via a trusted header. The reason is `C3`'s own, restated
precisely for this contract's purpose:

- **The one-active-session-per-identity rule** (`C3` §3.1's structural
  partial unique index) requires the service itself to be the single
  authority that knows, for every request, which `sessions` row is
  `ACTIVE` for which identity. An upstream proxy performing authentication
  and merely forwarding "user X is authenticated" would place the
  session-uniqueness decision outside the one place (`C3`'s `sessions`
  table and its trigger-enforced index) that can make it structurally
  impossible to violate — the proxy has no `sessions` table of its own to
  enforce the same constraint against, so the invariant would degrade to
  "usually true" rather than "structurally guaranteed."
- **The audit trail requires the service to know who authenticated.**
  `C1` §3.5's `fn_audit_capture()` mechanism raises `audit_context_missing`
  unless `app.actor_fingerprint` is set inside the same transaction as a
  mutation, and that fingerprint must trace back to a verified bind or a
  verified local credential check this service itself performed (§3, `C3`
  §3.2) — not a header a proxy attached. A proxy-asserted identity header
  is not evidence this service can trust as the actor of record for its
  own audit trail without re-verifying it, which would mean the service is
  still doing the authentication, only redundantly.

A reverse proxy may still perform network-layer access control (source IP
allowlisting, mTLS to the proxy's own client population, rate limiting at
the edge) — none of that is "owning identity" in the sense this section
forbids; it is a network-boundary control layered in front of a service
that still performs its own authentication and still owns its own session
and audit state.

---

## 11. Acceptance criteria and test specification

### 11.1 Acceptance criteria (mirrors this movement's own AC-1..AC-12)

1. **AC-1.** This document exists at
   `docs/design/UI2_0_C3A_LOCAL_AUTHENTICATION_CONTRACT.md`, its status
   line reads `DRAFT`, and §"Status" states explicitly that it authorizes
   nothing.
2. **AC-2.** §2 specifies the mechanism registry so that adding a member
   is adding an implementation of `Mechanism`, never a change to the login
   flow, session model, or RBAC model (§2.1); `local` is stated
   non-removable, citing Correction C-2 (§2.1).
3. **AC-3.** §3 names `Argon2id`, a per-credential salt, and recorded
   parameters (`memory_cost_kib`, `time_cost`, `parallelism`), and
   forbids reversible encryption, plain text, and a bare fast digest
   (§3.1).
4. **AC-4.** §6 specifies both bootstrap accounts, states the
   audit-attribution reason for `claudeadmin` (§6.1), records the
   2026-09-13 Product Owner deferral of the forced change with its
   reason (§6.2), states the risk plainly, and names the revisit
   condition (§6.2).
5. **AC-5.** §5 specifies lockout counting (per identity, §5.1), the
   threshold (5, §5.1), the effect (refused login, §5.2), how it clears
   (15-minute self-expiry or a successful post-expiry login, §5.2), and
   states the refusal never reveals identity existence (§5.3).
6. **AC-6.** §7 specifies how a local identity acquires a `role_bindings`
   row (§7.1) and records the Product Owner's forward requirement for
   distinct team roles (§7.2).
7. **AC-7.** §8 specifies audit coverage for every named event and
   forbids credential material from every audit row, log line, and error
   message, including the named exception to `C1` §3.5's generic trigger
   (§8).
8. **AC-8.** §9 records the exposure directive and reports, without
   resolving, the tension with the deployment contract's Ingress.
9. **AC-9.** §10 states a reverse proxy may terminate TLS but may not own
   identity, with the reason from `C3` (session uniqueness, audit trail).
10. **AC-10.** §11.2 below is the test specification, including the
    forced-bootstrap-change test and the no-credential-in-audit test.
11. **AC-11.** §11.3 below is the UNKNOWN register.
12. **AC-12.** The repository privacy gate
    (`scripts/repository_privacy_check.py`) reports zero findings against
    this diff, and the diff is exactly one added file under
    `docs/design/`.

### 11.2 Test specification

Each test below is independently runnable against a Testcontainers-backed
PostgreSQL instance, mirroring `C1`/`C3`'s own discipline (no test against
a real corporate directory or a real deployment — this contract designs no
such test).

1. **Verifier storage never holds a recoverable form.** A test seeds a
   `local_credentials` row through the credential-set path and asserts:
   `verifier` and `salt` are `BYTEA`, not equal to the plaintext password
   or any substring of it; `algorithm_id = 'argon2id'`; the three
   parameter columns are non-null integers.
2. **Per-credential salts are distinct.** Two identities set the identical
   plaintext password; their `salt` values differ and their `verifier`
   values differ.
3. **Raising parameters does not invalidate existing credentials.** A row
   is seeded with parameter set `P1`; the service's *default* parameters
   are changed to `P2` (`P2` costlier than `P1`) without touching the row;
   a login attempt with the correct password against the unchanged row
   still succeeds, verified against `P1` (the row's own recorded
   parameters), not `P2`.
4. **Lockout threshold and effect.** Five consecutive failed attempts
   against one `local_identity_id` set `locked_until` to a future
   timestamp; a sixth attempt with the *correct* password is refused with
   the identical `401 INVALID_CREDENTIALS` body a wrong password produces.
5. **Lockout clears by self-expiry.** With `locked_until` forced into the
   past (simulating elapsed time), a correct-password attempt succeeds and
   resets `failed_attempt_count` to `0` and `locked_until` to `NULL`.
6. **Refusal uniformity across causes.** Four attempts — unknown username,
   wrong password for a known username, empty password, and a
   correct-password attempt against a currently-locked identity — each
   produce byte-identical `401 INVALID_CREDENTIALS` response bodies (field
   set and values, excluding any request-correlation id already permitted
   to vary).
7. **PW-1/PW-2/PW-3 are enforced at credential-set/change time.** An
   11-character password, an empty password, and a password equal to the
   identity's own display name are each refused before any hash is
   computed (asserted via a spy/mocked hashing call count of zero for
   these three cases); a 12-character password meeting PW-2/PW-3 succeeds.
8. **Forced-bootstrap-change test — proves the deferral, not a
   requirement.** On a freshly seeded database, a login with each
   bootstrap account's initial credential succeeds **without** the server
   requiring a password-change step first (no `403`/redirect-to-change
   response, no forced-change flag blocking any other action) — proving
   §6.2's deferral is real and not silently enforced anyway. A second
   assertion in the same test proves the *positive* requirement standing
   alongside the deferral: the change-password path (§4) is reachable and
   succeeds for a bootstrap account without any special-cased restriction,
   proving "changing a password is possible from the first release" holds
   even though it is not compelled.
9. **No credential material appears in any audit row.** A password is set
   for an identity, changed once, and three failed login attempts are
   made against it. Every resulting `audit_log` row for
   `local_credentials` (via `fn_audit_capture_local_credentials()`, §8) is
   asserted to contain none of: the plaintext password, the `verifier`
   bytes, the `salt` bytes, or any of the three parameter columns' values,
   in either `before_state` or `after_state` — asserted by checking the
   JSONB keys present are exactly the §8 allowlist
   (`local_identity_id`, `failed_attempt_count`, `locked_until`,
   `created_at`, `updated_at`), not merely that specific values are
   absent, so a future column addition to `local_credentials` fails this
   test until the allowlist is updated deliberately.
10. **No credential material appears in any log line or error message.** A
    forced hashing-library failure (e.g., an invalid parameter
    combination) and a database constraint violation on `local_credentials`
    are each triggered in a test harness that captures all log output; the
    captured text is asserted to contain no substring equal to the test's
    known plaintext password, verifier bytes (base64 or hex form), or salt
    bytes.
11. **Registry membership: `local` cannot be disabled.** A test asserts no
    configuration value, environment variable, or database row exists that
    the login-mechanism-resolution code path reads to decide whether
    `local` is offered — a static/architectural test enumerating the
    mechanism registry's construction and asserting `local` is
    unconditionally present in the constructed set (mirroring `C3`
    `AG-J2`/`AG-J3`'s style of static, code-shape assertions).
12. **Mechanism-independent outcome reporting.** A test stubs a second
    `Mechanism` implementation whose `attempt()` returns `REFUSED`, and
    asserts the HTTP response is byte-identical in shape to a local
    `REFUSED` outcome's response (§2.3) — proving the login handler
    branches on `AttemptOutcome.result`, never on `mechanism_id`.

### 11.3 UNKNOWN register

| # | UNKNOWN | What would settle it |
|---|---|---|
| U-1 | The concrete Java Argon2 library and its exact default parameter values (`memory_cost_kib`, `time_cost`, `parallelism`) | A B1-scope implementation movement selecting and recording a specific library (e.g. a Bouncy Castle or a dedicated Argon2 binding) and default parameters, benchmarked against the target deployment hardware |
| U-2 | The exact seeding mechanism for the two bootstrap accounts (Flyway seed migration vs. a first-boot bootstrap routine) and how each account's initial password is generated or supplied | A B1-scope implementation movement fixing the concrete migration/bootstrap mechanism, consistent with `C1` §2.4's `ui2_migrate`-role posture for other bootstrap-shaped actions |
| U-3 | Whether `local_identity_name` (the display/lookup username) is stored case-normalized, and if so, by what rule | A decision by the successor contract or implementation movement that owns the user-management screen (§1.2), since the answer affects what "unknown username" means for §5.3's non-revealing refusal |
| U-4 | Whether an exact constant-time guarantee is required for the username-existence-vs-lockout distinction (§5.3), or whether a coarser "no code path returns measurably faster for an unknown identity" bound suffices | Real-environment timing measurement once an implementation exists, per `AGENTS.md`'s automated-vs-real-environment-validation distinction |
| U-5 | `claudeadmin`'s initial role binding (which role token, if any, beyond existing) | A Product Owner ruling — this contract fixes only that the account exists and why (§6.1/§7.3), not its authorized scope |
| U-6 | Whether Correction C-2's open "fallback trigger" question (automatic vs. always-available-in-parallel) needs further resolution beyond §2.2's "operator's own selection" answer — e.g., whether an LDAP outage should surface a UI hint steering the operator toward `local` | A Product Owner ruling, since it is a UX/product decision this contract's brief scoped out (screen design, §1.2) |
| U-7 | The exposure/Ingress tension (§9) | A Product Owner ruling or a successor to `UI2_0_B1_01C_CONTAINER_IMAGE_AND_KUBERNETES_DEPLOYMENT_CONTRACT.md` — explicitly not settled by this document |
| U-8 | Whether a rehash-on-login parameter upgrade (§3.2) is actually implemented, versus parameters only ever being raised for newly-set credentials | An implementation movement's own design choice; this contract states only that the schema does not block it |

---

## 12. Cross-references

- `docs/design/UI2_0_C3_IDENTITY_SESSIONS_RBAC_CONTRACT.md` (`C3`, FROZEN)
  — §2 (LDAP bind, reused pattern for §2.3's uniform outcome reporting),
  §3 (sessions, unchanged), §4 (role tokens/bindings, extended by §7),
  §5 (RBAC enforcement, unchanged), §6 (gate chain, unchanged), Correction
  C-2 (this document's direct mandate).
- `docs/design/UI2_0_C1_PLATFORM_SCHEMA_CONTRACT.md` (`C1`, FROZEN) — §3.5
  (`fn_audit_capture()`, extended by §8's named exception), §3.6/§6
  (secrets pattern, contrasted in §1.2), §7 (opaque-identifier/CLASS 2
  rules, applied to `local_credentials` in §3.2).
- `docs/design/UI2_0_B1_04B_DEVICE_MODEL_AND_ONBOARDING_CONTRACT.md` §6 —
  "a reference names a credential; it never holds one," contrasted against
  §3.1's stored-verifier pattern.
- `docs/design/UI2_0_B1_01C_CONTAINER_IMAGE_AND_KUBERNETES_DEPLOYMENT_CONTRACT.md`
  §5.2, §8 — the Ingress/Route object this document's §9 reports a tension
  against.
- `AGENTS.md` — identity law, evidence laws, UNKNOWN/fail-closed law,
  sensitive-identity reporting law, raw-evidence law, privacy/DLP,
  "Authority hierarchy" (the tension-reporting rule §9 follows).
