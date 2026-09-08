# Local LDAP / Active-Directory Authorization for `D7`

## Status

**DRAFT — discussion proposal. Not implementation authority**
(`AGENTS.md` "Authority hierarchy" item 2, "Contract-status law"). This
document changes no code, contacts no directory, and authorizes no
implementation movement. Promotion to `FROZEN`, and dispatch of the first
implementation slice (§10), are two separate Product Owner decisions.

Parent contract this draft must not violate:
`docs/design/CAPABILITY_STATE_VOCABULARY_AND_PRESENTATION.md`
(**FROZEN — PRODUCT OWNER APPROVED, 2026-09-06**) — §3.3 `D7`, §3.4 `E1`–`E7`,
§5.3, §5.4, §5.4.1. `D7` is **actor authorization and nothing else**; the
bearer-token session gate (`E1`), the closed-registry membership check (`E2`)
and taxonomy admissibility (`E3`) keep their full enforcement power and are
**not** `D7`. This draft adds a layer on top of them; it re-evaluates neither
`utils/action_taxonomy.py` (`E3`) nor `console/app.py::_require_api_auth`
(`E1`).

Explicitly **decoupled from `DEPLOY.1A`**. This is a local-operator,
loopback-console-scoped design for a single human running `--console` on their
own machine against their own corporate Active Directory Domain Controller. It
is not a multi-tenant server design, inherits none of `DEPLOY.1A`'s
assumptions (server deployment, OIDC, multi-user RBAC, role separation,
production TLS termination), and does not extend or depend on that track.

Movement: `ARCHITECTURE` (this document) → Product Owner review → freeze
decision → separately authorized `IMPLEMENTATION` (§10 slicing).

---

## 1. Objective and framing

The frozen capability-state contract names `D7` (authorization) as one of seven
dimensions and gives it a total, four-valued, action-scoped algebra. It has
**no producer**. Today `utils/operate/authorization.py::DenyAllAuthorizer`
governs `CLASS 2` only and denies unconditionally; `static/navigation_ui.js::
navigationAuthorizationContext()` reports `model: "none"`; every `CLASS 0`
console action resolves `E4 = NO_APPLICABLE_AUTHORITY` — non-blocking, and
explicitly **not a grant**.

The previously assumed route to a real actor-authorization authority was
`DEPLOY.1A`'s OIDC/RBAC boundary, which is blocked on external server
availability (`CURRENT_STATE.md` "Open blockers": `DEPLOY.1` gates → server
availability, external). That blocker is real, and it is also the wrong
dependency for the actual near-term need.

The actual need is narrower and already has infrastructure: the operator runs
the console on a machine already joined to, or able to reach, a corporate
Active Directory domain. Authenticating that operator against the existing
Domain Controller and reading their existing AD group membership gives `D7` a
**real, named, applicable authority** without any server, any OIDC
deployment, or any new identity store. Nothing in this design is a stepping
stone to `DEPLOY.1A`, and nothing in it substitutes for `DEPLOY.1A` when a
server surface eventually exists.

**Two distinct components, deliberately named apart** (§4):

| id | Component | Which gate family | Frozen-contract classification |
| --- | --- | --- | --- |
| **`A1`** | **Actor binding + session admission** — an LDAP simple bind establishes *which* actor this console process serves; a configured access group is a precondition of serving the session | augments `E1` | **not `D7`** |
| **`A2`** | **The `local_ldap` `D7` producer** — per declared action, one of the four `E4` outcomes, from the bound actor's group set | `E4` / `D7` | **is `D7`** |

Folding `A1` into `D7` is exactly the overreach the frozen contract's own
historical note records ("an earlier revision listed five 'authorization
regimes' that folded those gates into `D7`, which overstated what the product
decides about actors"). This draft keeps them apart, and it is the reason the
smallest first slice (§10) is honestly described as **not yet a `D7`
producer**.

---

## 2. Source evidence inspected

Read-only inspection of this worktree on 2026-09-08. No directory, device,
network or credential was contacted; no code or test was changed; no test was
run for the drafting itself (§9).

- `docs/design/CAPABILITY_STATE_VOCABULARY_AND_PRESENTATION.md` §3.3 `D7`
  (four outcomes, ownership matrix, "must never control"), §3.4 `E1`–`E7`,
  §4.1.3 `I18`/`I19`, §4.1.5 (input-condition handling), §5.3 (five
  authorization rules), §5.4 (affordance resolution), §5.4.1 (denied vs
  undetermined vs no-applicable-authority), §5.4.3 (what each output may
  read), acceptance criteria `AC-CS-5`, `AC-CS-33`, `AC-CS-35`, `AC-CS-66`,
  `AC-CS-67`, `AC-CS-68`, `AC-CS-80`.
- `utils/action_taxonomy.py` — the five classes, `console_refusal()`. `E3`'s
  single evaluation point. Not re-evaluated by anything proposed here.
- `console/auth.py` — per-launch 256-bit bearer token, never written to a
  file, registered with the redaction registry, `hmac.compare_digest`
  comparison, `origin_is_trusted()`.
- `console/app.py::_require_api_auth` — bearer token + `Origin` /
  `Sec-Fetch-Site` enforcement on every `/api/*` route; `/` and `/assets/*`
  unauthenticated by design (C1-6).
- `console/server.py::run_console` — loopback-only bind, not configurable by
  flag or environment; launch token printed once to stdout in the URL
  fragment; optional dependency imported inside a function with a fail-closed
  preflight (`console_dependency_preflight`).
- `application/services.py::_resolve_or_prompt` / `_build_runtime_config` —
  the `DEV.2.1`/`DEV.2.2` credential pattern: `<VAR>_FILE` > `<VAR>` > TTY
  prompt (`getpass` for secrets), `RuntimeConfigError` naming every missing
  variable when stdin is not a TTY, `register_sensitive_value` for both
  principal and secret, `principal_fingerprint` for audit correlation, both
  locals cleared before return.
- `utils/runtime_config_source.py::resolve_value` — the resolution primitive;
  fail-closed on a set-but-unusable `<VAR>_FILE`; no secret value in any
  exception message.
- `utils/operate/authorization.py` — the only `Authorizer` outside `tests/`;
  `CLASS 2` only; unconditional `deny("authorization_not_configured")`;
  "'admin' (the console's per-launch bearer token) means nothing to this
  boundary".
- `utils/pan_tls_trust.py` — the existing CA-bundle preflight posture:
  configured bundle unreadable → hard transport failure *before* any network
  call, with a value-free error message.
- `utils/logger.py::principal_fingerprint` — 12-hex-character SHA-256 prefix,
  the repository's existing non-secret audit correlator for a principal.
- `docs/design/LOCAL_CONTROL_PLANE_RUNTIME_AND_ENROLLMENT.md` §11.3, §12
  (the `M1`…`M14` row table), §12.1 — see §7 below.
- `requirements.txt`, `requirements-console.txt`, `requirements-dev.txt` — the
  current dependency surface and the established optional-requirements
  pattern.
- `AGENTS.md` (identity law, sensitive-identity reporting law, UNKNOWN /
  fail-closed law, vendor-semantics law, diagnostic-path law, privacy/DLP),
  `docs/AI_DEVELOPMENT_PROTOCOL.md` "Approval boundaries".

---

## 3. Scope

### In

- The two components `A1` / `A2` and their placement in the frozen `E1`–`E7`
  gate model.
- Five resolved decisions with stated tradeoffs (§5): credential sourcing,
  fail-open vs fail-closed, what persists with no database, group-to-permission
  granularity, library choice. Plus two further load-bearing decisions this
  design cannot honestly leave open (transport trust, bind model).
- Reconciliation with the existing console session model (§6) and with the
  frozen `M14` row (§7).
- The named unknowns that must be closed before any freeze (§8).
- The delivery slicing, smallest first slice named (§10).

### Out

- **Any code, test or project-state change beyond this document** and a single
  backlog pointer.
- **Any live LDAP/AD network call.** None was made in drafting; none is
  authorized by this document.
- **`DEPLOY.1A` / server OIDC-RBAC design.** Not extended, not depended on,
  not superseded. When a server surface exists it re-asks every question here
  rather than inheriting the answers — the same rule
  `LOCAL_CONTROL_PLANE_RUNTIME_AND_ENROLLMENT.md` §11.3 already binds on the
  local-loopback enrollment permission.
- **Any change to `E1`, `E2` or `E3`.** The bearer token, the closed job
  registry and the action taxonomy are untouched.
- **Credential management, password change, account lifecycle, directory
  writes.** Every directory operation proposed here is a read
  (`CLASS_0_READ` in the product's own taxonomy; the taxonomy governs
  network-*device* actions, and a Domain Controller is not one of the
  product's devices — see `U-3`).
- **A browser-side credential form.** The browser never sees, holds or sends
  a directory credential (§6).

---

## 4. Where this sits in the frozen gate model

The frozen contract's gates, in the order a console action meets them, with
this design's additions marked:

```
  request arrives at /api/*
    E1  bearer launch token + Origin/Sec-Fetch-Site + loopback binding   [unchanged]
        └── A1  actor binding: which principal does this console serve?  [NEW, E1 family]
            and, when an access group is configured, session admission
    E2  closed-registry action identity                                  [unchanged]
    E3  taxonomy / surface admissibility (console_refusal)               [unchanged]
    E4  the action's applicable authorization authority = D7             [NEW PRODUCER: A2]
    E5  subject/target integrity the action's own contract requires      [unchanged]
    E6  the action's declared semantic prerequisites                     [unchanged]
    E7  admission + immediately-before-execution live checks             [unchanged]
```

Four invariants this placement is designed to keep, each traceable to a frozen
acceptance criterion:

1. **`E3` is never re-evaluated inside `E4`** (§5.4). `A2` reads the action's
   `action_id` and the actor's group set. It never reads `ActionClass`,
   `console_refusal()`, or `console_submittable`. A `CLASS 1` action refused by
   `E3` and denied by `A2` records **both** reasons, refusal last
   (`H5c`, §5.3 rule 2).
2. **`D7` changes no visibility** (`AC-CS-33`). `A1`'s session admission is a
   session-level decision taken before any surface renders; it is not a
   per-module or per-tab visibility rule, and `A2` contributes only to
   `action_affordance[action_id]`. An action must not vanish because the actor
   lacks access (§5.4.3).
3. **No dimension is computed from `D7`, and `D7` from no other dimension**
   (`AC-CS-5`). `A2`'s only inputs are the bound actor, the action id and the
   directory. It reads no capability projection — and a cached capability
   projection never grants permission (`AC-CS-68`, §5.3 rule 4).
4. **Server-side refusal is independent of UI enablement** (`AC-CS-35`). Every
   affordance `A2` produces is advisory (§5.4.2); the submission path
   re-evaluates `E4` server-side regardless of what the browser was shown.

---

## 5. Resolved decisions

Seven decisions, `LD-1`…`LD-7`. Each is a recommendation with its tradeoff
stated, not an open question.

### 5.1 `LD-1` — bind credential sourcing

**Decision: bind as the *operator*, with their own directory credential,
resolved through the existing `DEV.2.1`/`DEV.2.2` mechanism. No service
account, no second credential path, no new resolution primitive.**

New variables, resolved by `utils/runtime_config_source.resolve_value` exactly
as the device credential is, with `<VAR>_FILE` taking precedence over `<VAR>`
and a TTY prompt as the last resort:

| Variable | Kind | Prompt kind | Notes |
| --- | --- | --- | --- |
| `SECURITYEXPERT_LDAP_URI` | endpoint | `endpoint` | `ldaps://` scheme required (`LD-6`). Never carries credentials in the URL |
| `SECURITYEXPERT_LDAP_BIND_DN` | principal | `line` | the operator's own bind DN or UPN |
| `SECURITYEXPERT_LDAP_BIND_SECRET` | secret | `secret` (`getpass`) | the operator's own password |
| `SECURITYEXPERT_LDAP_CA_BUNDLE` | trust reference | `line` | path to the corporate CA bundle (`LD-6`) |
| `SECURITYEXPERT_LDAP_SEARCH_BASE` | directory scope | `line` | the search base for the membership read |
| `SECURITYEXPERT_LDAP_ACCESS_GROUP` | group reference | `line` | slice 1's single access group (`LD-4`) |

**Why the operator's own credential rather than a service account.** A service
account is a long-lived secret that must exist at rest somewhere on the
operator's machine for the console to start unattended. The operator
credential exists only for the duration of one `--console` launch, is entered
into a `getpass` prompt on a TTY the browser cannot reach, and — decisively —
makes the bound principal *identical* to the actor `D7` is asked about. A
service account would require a separate mapping from "the human at this
console" to "the principal we searched for", and the console has no user
identity in its HTTP session to supply one (§6). The tradeoff accepted: the
console cannot be started non-interactively **as a specific operator** without
placing that operator's secret in a `_FILE` mount, which is exactly the
`DEV.2.1` pattern's intended use and is the operator's own decision to make.

**Why a distinct variable namespace, not `SECURITYEXPERT_PRINCIPAL` /
`SECURITYEXPERT_SECRET`.** Those name the credential the product uses against
*network devices*. A directory principal and a device principal are different
identities against different systems with different blast radii; reusing the
variables would conflate them and would make a device credential silently
usable as a console authorization credential. `AGENTS.md`'s diagnostic-path
law forbids a *parallel credential path* — it does not require two different
principals to share one variable. The **mechanism** is reused (`resolve_value`,
`_resolve_or_prompt`, `RuntimeConfigError`); only the **identity namespace** is
new, and that separation is the point.

**Binding rules, all inherited from the existing pattern and all mandatory:**

- Never in a config file committed to the repository. No group DN, bind DN,
  directory hostname or search base appears in any repository file —
  `AGENTS.md`'s sensitive-identity law covers usernames/principals and
  management addresses, and a corporate directory DN is both.
- Never in a URL. `ldap://user:secret@host` form is rejected at validation,
  not merely discouraged.
- **Never in an argv.** No `--ldap-password`, `--ldap-bind-dn` or equivalent
  flag exists. The orchestrator and the console both log or surface argv;
  a credential must never enter one.
- Both DN and secret go through `register_sensitive_value` before any
  directory call, so neither can reach a log line even accidentally
  (`console/auth.py` sets this precedent for the launch token).
- The secret local is cleared immediately after the bind
  (`_build_runtime_config` sets `secret = None` before returning; `LD-3`
  extends this to the console process's steady state).
- Audit records `principal_fingerprint(bind_dn)` — never the DN itself.
- **A bind with a non-empty DN and an empty secret is rejected before the
  call.** RFC 4513 permits a directory to answer such a request as a
  successful *unauthenticated* bind; treating that success as authentication
  would be a silent authentication bypass.

### 5.2 `LD-2` — fail-open vs fail-closed

**Decision: fail-closed on execution, honest-unknown in presentation. An
unreachable or unreadable directory is never a denial, never a grant, and
never a downgrade to "no authority applies".**

The frozen contract already supplies the vocabulary; this decision is a mapping
onto it, not a new binary:

| Condition | `E4` outcome | Presentation (§5.4.1) | Submission-time enforcement |
| --- | --- | --- | --- |
| the `local_ldap` authority is **not configured** | `NO_APPLICABLE_AUTHORITY` | nothing said about authorization | unchanged — `E1`–`E3` decide, exactly as today |
| configured; actor bound; membership **positively confirmed** | `PERMITTED` | nothing said | proceeds to `E5`–`E7` |
| configured; actor bound; membership **positively absent** | `DENIED(local_ldap, actor_not_in_required_group)` | refusal tone, refusal copy, authority named | **refuse** |
| configured; directory unreachable, read failed, TLS trust preflight failed, or the connection dropped | `AUTHZ_NOT_EVALUATED(local_ldap)` | `UNDETERMINED`; neutral tone; "permission for this action has not been determined"; **never** refusal wording (`AC-CS-80`) | **refuse**, reason `authorization_unevaluated`; recorded as an unevaluated-gate event, **never as a denial** |
| configured; console launched but the bind never succeeded | the console does not start in this profile at all (`A1`) | n/a | n/a |

**The banned transition.** A configured `local_ldap` authority that cannot be
reached must **never** resolve to `NO_APPLICABLE_AUTHORITY`. That value means
*no authority applies to this action*; configuring one makes it apply.
Degrading to it on failure would be non-blocking by the frozen contract's own
rule and would therefore be a **silent fail-open** — the most dangerous
failure mode this design has, and the one an implementation is most likely to
introduce by accident. It is called out here so a test can be written against
it (`AG-4`, §12).

**Why fail-closed on execution.** `AGENTS.md`'s UNKNOWN / fail-closed law:
"Absence of evidence is not evidence of absence. Collection failure is not a
known-bad state." An unreachable DC tells us nothing about the actor's
permissions, so we neither assert a denial (which would misattribute a refusal
to the directory) nor allow the action (which would make the authority
decorative the moment the network hiccups). The action is refused, and the
audit record says *why* — unevaluated, not denied.

**Why not fail-closed in presentation too.** Rendering an unreachable
directory as `DENIED` would tell the operator "you were refused" when they were
not. `AC-CS-80` is explicit that `AUTHZ_NOT_EVALUATED` is rendered and audited
distinctly from a confirmed denial, with neutral tone. The cost accepted: an
operator sees an undetermined control and, on clicking, gets a refusal. That is
honest — the alternative states a fact the product does not have.

**Tradeoff considered and rejected: a grace window** (keep serving the last
positive answer for N minutes after the directory goes away). It is convenient
and it is fail-open with a timer. `LD-3`'s bounded re-validation interval
already provides all the tolerance this design is willing to buy, and it
expires *toward* `AUTHZ_NOT_EVALUATED`, not toward `PERMITTED`.

### 5.3 `LD-3` — what persists with no database available today

**Decision: nothing is persisted. Actor identity and group membership are
in-memory, process-lifetime state, bounded by a re-validation interval, and
they die with the console process.**

What exists today, and what this uses:

| State | Lifetime | Storage | Why |
| --- | --- | --- | --- |
| bound actor (DN, and its `principal_fingerprint`) | the `--console` process | memory only | identical lifetime to the launch token, which is also per-process and never written to a file (`console/auth.py`) |
| the bind secret | **until the bind returns** | memory, then cleared | mirrors `_build_runtime_config`'s `secret = None`. A corporate password is not held for the console's whole uptime |
| the bound LDAP connection | the console process, or until it drops | memory | re-reads use this connection; if it drops and cannot be re-established without the secret, `D7` goes to `AUTHZ_NOT_EVALUATED` and the operator relaunches |
| resolved group membership | the console process, re-validated on a bounded interval | memory only | see below |
| the authorization decision record | one log line per decision, through the existing redaction-aware logger | existing logs | there is no queryable audit store today, and this design does not invent one |

**Re-validation, not caching.** Membership is resolved at bind time and held
with its `resolved_at` timestamp. The next authorization question after the
interval expires triggers a fresh read over the existing connection.
Recommended default interval: **15 minutes**, adjustable only by the same
`<VAR>` / `<VAR>_FILE` mechanism, with a hard floor so it cannot be set to
"never". Two rules make this safe:

- A **stale positive is never served past its interval.** If the re-read
  fails, membership becomes `AUTHZ_NOT_EVALUATED` — it does not keep answering
  `PERMITTED` from the expired value (`LD-2`'s fail-closed direction).
- A **re-read that positively returns "not a member" takes effect
  immediately.** Revocation propagates within one interval; the interval is
  the exposure window and should be described to the operator as such.

**Why nothing is persisted.** A group-membership cache on disk is a durable
record of a named human's position in the corporate directory. `AGENTS.md`'s
sensitive-identity reporting law says usernames/principals are not reproduced
into durable artifacts; the raw-evidence law says parse to the minimum
semantics and discard. The value bought by persistence — surviving a console
restart — is worth very little when the console restart already requires a
fresh interactive bind anyway (`LD-1`). And a persisted positive that outlives
a directory revocation is precisely the fail-open this design spends `LD-2` on
avoiding.

**Explicitly not the home for this, today:** the `M4` local control-plane
metadata store. Its own frozen ownership boundary
(`LOCAL_CONTROL_PLANE_RUNTIME_AND_ENROLLMENT.md` §12) says it "**never** owns
registry rows, credentials, trust, raw config, backup bytes, CAS or `OP.2`
authority". A group-membership cache is adjacent enough to credentials and
trust that placing it there needs its own explicit decision, not an
assumption. See §11.

### 5.4 `LD-4` — group-to-permission mapping model

**Decision: per-action granularity is the eventual target and is what `D7`
actually owes. The first implementation slice targets a single access group
gating the console session, and that slice is honestly *not* a `D7`
producer.**

The frozen contract's `E4` outcomes are action-scoped
(`actor × action × entity`, never capability-scoped, §3.3). `AC-CS-5`,
`AC-CS-33`, `AC-CS-66`, `AC-CS-67` and `AC-CS-80` are all per-action criteria.
A design that only ever answers "may this human use the console" does not
satisfy any of them — it satisfies a *session* question, which §3.4 assigns to
`E1`'s family, not to `D7`.

**Why the first slice is still the single group.** Every console-submittable
job today is `CLASS_0_READ` (`utils/action_taxonomy.py`: `CLASS 1` is not
console-submittable, `CLASS 2` has no member, `CLASS 3`/`CLASS 4` are
prohibited). A per-action group map over today's registry would name the same
group once per action — false granularity that invites an operator to believe
the product distinguishes permissions it does not. The single access group
answers the question the operator actually has now ("only my AD group can open
this console") without inventing a permission taxonomy ahead of the actions
that would justify one.

**The eventual model (`A2`, slice 2).** A two-level indirection, chosen for a
privacy reason as much as a design one:

```
  console/registry.py action_id ──► role token ──► AD group reference
        (repository, reviewable)      (repository)   (runtime config, never in the repo)
```

- The **action → role token** map is repository-committed and code-reviewable:
  `recovery_attest_cp → role:operator`, `inventory_refresh_cp → role:reader`.
  It contains no corporate identity.
- The **role token → group reference** binding comes from runtime
  configuration (`SECURITYEXPERT_LDAP_ROLE_OPERATOR_GROUP`, or its `_FILE`
  variant), because a group DN is a corporate directory identity and
  `AGENTS.md`'s privacy law keeps those out of repository files entirely.
- An action with **no role token declared** resolves `NO_APPLICABLE_AUTHORITY`
  — correct by the frozen algebra, and it keeps the map additive rather than
  requiring every action to be enumerated before the first one works.
- A role token declared but **not bound** to a group in runtime configuration
  is `AUTHZ_NOT_EVALUATED(local_ldap)`, not `NO_APPLICABLE_AUTHORITY`: an
  authority is configured and this action's question was not answered.

**Membership must be evaluated by the directory, not by client-side string
comparison.** Asking the directory "is this DN a member of this group"
(a filtered search) pushes DN-equality and nested-group semantics onto the
authority that owns them. `AGENTS.md`'s identity law forbids inventing an
equivalence rule — case-folding a DN, or normalizing punctuation, to make two
values match — and a client-side `memberOf` string comparison is exactly that
temptation. Where a client-side comparison is unavoidable, it is exact after
whitespace stripping only, and any need beyond that is `UNKNOWN` until vendor
documentation settles it (`U-1`, §8).

### 5.5 `LD-5` — LDAP library

**Decision: `ldap3` (pure Python), as a new *optional* dependency in its own
requirements file, imported lazily behind a fail-closed preflight. This is a
dependency addition and therefore requires explicit Product Owner approval
(`docs/AI_DEVELOPMENT_PROTOCOL.md` "Approval boundaries") — this document
proposes it and does not add it.**

| | `ldap3` | `python-ldap` |
| --- | --- | --- |
| Implementation | pure Python; protocol implemented in-process | C binding to OpenLDAP `libldap` + `libsasl` |
| Install on the operator's machine | wheel-free install works anywhere CPython does; no compiler, no system headers | needs `libldap`/`libsasl` headers and a toolchain to build; Windows has no first-party wheels and macOS needs OpenSSL/Cyrus-SASL present |
| Portability to **this** target | Windows profile (`py`, `%LOCALAPPDATA%`) and macOS profile both work identically | the weakest point: this runs on the operator's own workstation, not a curated Linux server image |
| TLS | via the stdlib `ssl` module, so the CA-bundle posture in `LD-6` is the same mechanism `utils/pan_tls_trust.py` already assumes | via `libldap`'s own TLS stack — a second, differently-configured trust surface |
| Attack surface | larger *in Python*: protocol/BER handling is library code we ship | smaller in Python, larger in native code; a memory-safety surface the rest of this product does not have |
| Maintenance | slower release cadence; a real risk to name, not a disqualifier | actively maintained, but the build problem above is structural |

**Recommendation: `ldap3`**, because portability to a Windows/macOS operator
workstation is the binding constraint, and because a single TLS trust
mechanism shared with the existing PAN posture is worth more here than
`python-ldap`'s smaller Python-side surface.

**Why a third-party dependency at all, given the stdlib-only precedent.** The
alternative is implementing LDAP over TLS by hand: BER/DER encoding, the bind
and search protocol, result-code handling. That is a security-critical wire
protocol, and writing one is categorically worse than depending on an
established implementation. The stdlib-only precedent that matters
(`scripts/orchestrator.py`, the dashboard app) covers *local tooling with no
protocol surface*; the product itself already depends on `paramiko`, `lxml`,
`requests` and `cryptography` for exactly this reason. `ldap3` is the same
kind of dependency as `paramiko`.

**Placement**: a new `requirements-console-ldap.txt`, not `requirements.txt`.
The import is lazy and inside a function, with a fail-closed preflight and an
actionable message, following `console/server.py::console_dependency_preflight`
and `utils.coordinator_backend._psycopg`. Consequence: the collection,
render, maintenance and recovery paths never import it, and a machine without
it fails clean at `--console` startup rather than mid-request.

### 5.6 `LD-6` — transport trust (not asked, load-bearing)

**Decision: `ldaps://` or StartTLS with corporate-CA certificate
verification, mandatory, with no insecure fallback and no verification-disabling
flag.**

A simple bind over plain `ldap://` sends the operator's corporate password in
cleartext. `AGENTS.md`'s Palo Alto section already states the governing
principle for this repository: "Production TLS requires trusted corporate CA
verification. Historical POC TLS-verification exceptions are technical debt,
never production design." That applies verbatim here, and this design does not
get a POC exception.

Concretely, mirroring `utils/pan_tls_trust.py`: a configured CA bundle that
cannot be found or read is a **hard failure raised before any network call**,
with a value-free message (no path, no address, no credential). A missing
`SECURITYEXPERT_LDAP_CA_BUNDLE` does not silently fall back to the system trust
store — it is a configuration error, because "which CA signs my Domain
Controller" is exactly the fact this preflight exists to pin.

### 5.7 `LD-7` — bind timing and scope

**Decision: one bind at console launch, on the TTY, before the listener
starts. Not per-request, and never from the browser.**

Per-request binds would put a directory round trip in front of every
`/api/*` call and would require holding the operator's password for the process
lifetime to perform them (`LD-3` explicitly refuses to). Launch-time binding
also means an unreachable directory is discovered before the console prints
its URL, rather than as a wall of undetermined controls.

The cost accepted, stated plainly: **authentication freshness is bounded by
the console process's uptime**, while **authorization freshness is bounded by
`LD-3`'s re-validation interval**. A long-running console does not re-prove
the operator is still the operator. For a loopback, single-operator console on
that operator's own machine this is proportionate; for anything else it is
not, and that is one more reason `DEPLOY.1A` must re-ask rather than inherit.

---

## 6. Reconciliation with the existing console authentication (AC-5)

**The bearer-token session model stays exactly as it is.** `CON.0`/`CON.1`'s
per-launch token, `hmac.compare_digest` comparison, `Origin` /
`Sec-Fetch-Site` enforcement, loopback-only binding, and the
unauthenticated-by-design `/` and `/assets/*` routes are all unchanged. This
design adds a layer; it removes none.

| Question | Answered by | Changed by this design? |
| --- | --- | --- |
| may this *request* be served at all? | `E1` — the launch token, origin, loopback bind | **no** |
| *which actor* does this console process serve? | `A1` — the LDAP bind at launch | **new** |
| may that actor open a console session at all? | `A1` — the configured access group | **new** |
| is this action a known member of the closed registry? | `E2` | no |
| may an action of this class be submitted on this surface? | `E3` | no |
| is **this actor** permitted **this action**? | `E4` / `D7` — `A2` | **new producer** |

Four non-substitution rules, each of which should be a test:

1. A valid AD group membership **never** substitutes for a missing or
   incorrect bearer token. `/api/*` still returns 401.
2. A valid bearer token **never** substitutes for group membership. The token
   proves the browser was launched by this process; it says nothing about who
   the human is. `utils/operate/authorization.py` already states this posture
   for `CLASS 2`: "'admin' (the console's per-launch bearer token) means
   nothing to this boundary."
3. Neither is inferred from the other, in either direction.
4. **The browser never handles a directory credential.** There is no login
   form, no credential field, no directory password in any request body,
   header or URL. The bind happens on the CLI's TTY before the listener
   starts (`LD-7`). This keeps the console's existing "no credential over the
   HTTP surface" posture exactly intact.

---

## 7. Authority reconciliation — the frozen `M14` row

`docs/design/LOCAL_CONTROL_PLANE_RUNTIME_AND_ENROLLMENT.md` is **FROZEN**. Its
§12 movement table defines `M14` as **"Production OIDC/RBAC integration"**,
objective `DEPLOY.1A`, prerequisite "`DEPLOY.1` external", and §12.1 adds:
"`M14` — Production OIDC/RBAC. It **does not retroactively validate local
shortcuts** (§11.3)."

This draft proposes different content for the same identifier. Per `AGENTS.md`
"Authority hierarchy" — *"Never silently reconcile a disagreement between two
authorities"* — the contradiction is reported here rather than resolved:

| Option | What it means | Cost |
| --- | --- | --- |
| **(1) — recommended — a distinct id, `M14L`** | This local-LDAP work gets its own movement identifier. `M14` keeps its frozen meaning (production OIDC/RBAC, `DEPLOY.1A`, still blocked on external server availability) | none to the frozen contract; two ids to track instead of one |
| **(2) redefine `M14`** | A narrowly scoped amendment to the frozen §12 row and the §12.1 clarification | amends a FROZEN document; and it makes §11.3's rule ("`M14` does not retroactively validate local shortcuts") self-referential, since the redefined `M14` *is* a local design |

**Recommendation: option (1).** §11.3's rule exists precisely to stop a
local-loopback permission from becoming the production posture by inheritance.
Keeping `M14` as the production-OIDC movement preserves that rule intact, and
naming this work `M14L` makes the relationship legible: `M14L` gives `D7` a
producer locally; `M14` remains owed for the server surface, and re-asks every
question in §5 rather than inheriting an answer.

**This is a Product Owner decision, not this draft's.** The Product Owner's
2026-09-08 direction that motivated this movement described decoupling `M14`
from a DEPLOY.1A dependency it should not have had; both options above deliver
that decoupling. Only option (2) requires touching a frozen document, and this
draft does not do so.

---

## 8. Named unknowns — must be closed before any freeze

Per `AGENTS.md`'s vendor-semantics law, a load-bearing semantic that official
documentation has not established is `UNKNOWN` here, not filled in from general
knowledge.

| id | Unknown | Why it is load-bearing | How it closes |
| --- | --- | --- | --- |
| **`U-1`** | The exact directory-side filter for **nested/transitive** group membership in Active Directory, and its DN-equality semantics | If nested groups are not matched, an operator whose access is granted through a parent group is silently denied. If a matching rule is used without confirming its semantics, the product asserts an equivalence it has not proven (identity law) | Microsoft's own directory documentation, cited in the frozen version of this document. A candidate matching-rule OID is known to exist for this purpose; this draft deliberately does not name it as fact |
| **`U-2`** | Whether the operator's environment terminates `ldaps://` on a certificate chaining to a CA the workstation can be pointed at, or requires StartTLS on the plain port | Determines whether `LD-6`'s preflight can be satisfied at all | A single read-only observation on the operator's own machine, under `docs/reference/REAL_ENV_VALIDATION_PROTOCOL.md` — proposed by the agent, executed by the Product Owner, returning a SAFE SUMMARY (relationship, not values) |
| **`U-3`** | Whether a Domain Controller read falls under `docs/AI_DEVELOPMENT_PROTOCOL.md`'s **network-device command gate** | The gate governs vendor *network-device* commands (Check Point, Palo Alto). A DC is a new network-access pattern against a system the product has never contacted; "new network-access patterns" independently require explicit approval under "Approval boundaries" regardless of the gate's applicability | Product Owner ruling at freeze. This draft's position: the ten-field gate is the right *shape* for the record even if the DC is not a "device", and filling it in costs little |
| **`U-4`** | Whether `E7`'s "read the registry again immediately before execution" has an authorization analogue — i.e. whether `E4` must be re-evaluated immediately before execution as well as at submission | `AC-ST-4` mandates the double check for the *registry*; the frozen contract does not extend it to `E4`, and `LD-3`'s interval is the only current bound on staleness | Product Owner / contract reading at freeze. Conservative default if unresolved: re-evaluate `E4` at submission only, and state the interval as the exposure window |

---

## 9. What this document does **not** decide or authorize

Mirroring `PRIVATE_REPLAY_ARCHITECTURE.md`'s and `OP.1`'s own framing:

- **No code was written or changed.** No test was added or changed. No
  behavior of `console/`, `utils/`, `application/` or any collector is altered
  by this document.
- **No live LDAP/AD network call was made**, by any means, in producing this
  document. No directory was contacted, no credential was resolved, and no
  real group, DN, hostname or search base appears anywhere in it.
- **This document does not authorize dispatch of an implementation
  movement.** Freezing it would not either: §10's slice 1 needs its own
  explicit Product Owner go-ahead, exactly as `M1`…`M14` each do under
  `LOCAL_CONTROL_PLANE_RUNTIME_AND_ENROLLMENT.md` §12.1.
- **It adds no dependency.** `LD-5` proposes `ldap3`; adding it is a separate
  approval under "Approval boundaries".
- **It amends no frozen document.** §7 reports a contradiction and recommends
  the option that requires no amendment.
- **It resolves no `U-x` unknown** (§8) and must not be frozen while `U-1`
  stands, because `U-1` determines whether the membership check is correct.
- It makes **no claim of real-environment validation**. Everything in §5 is a
  design recommendation from repository evidence.

---

## 10. Delivery slices

**Slice 1 — `A1`: bind + single access group, session gating only.** The
smallest useful implementation, and the one the Product Owner's near-term need
actually describes.

- Launch-time LDAP simple bind over verified TLS (`LD-1`, `LD-6`, `LD-7`),
  credentials resolved through the existing `DEV.2.1` mechanism.
- One configured access group; membership positively confirmed or the console
  does not serve (`LD-2`'s launch row).
- `LD-3`'s in-memory, re-validated, never-persisted state.
- `ldap3` as an optional dependency behind a fail-closed preflight (`LD-5`),
  pending its own approval.
- **Honest classification: this slice augments `E1`. It is not yet a `D7`
  producer, and it must not be reported as one.** `E4` continues to return
  `NO_APPLICABLE_AUTHORITY` for `CLASS 0` console actions, exactly as today,
  and `navigationAuthorizationContext()` continues to report that no
  actor-authorization model exists. Nothing in `M3`'s acceptance criteria for
  `D7` is discharged by slice 1.
- Not in slice 1: any per-action mapping, any change to `E1`/`E2`/`E3`, any
  browser-visible authorization state, any persisted state.

**Slice 2 — `A2`: the `local_ldap` `D7` producer.** The action → role token →
group indirection (`LD-4`), the four-valued `E4` output, its audit record, and
the `AC-CS-5` / `AC-CS-33` / `AC-CS-66` / `AC-CS-67` / `AC-CS-68` / `AC-CS-80`
test obligations. This is the slice that actually gives `D7` a producer.

**Slice 3 — presentation.** Wiring `E4`'s outcome into
`action_affordance[action_id]` per §5.4, with `AUTHZ_NOT_EVALUATED` rendered in
neutral tone and never with refusal wording (`AC-CS-80`), and both reasons
preserved when `E3` and `E4` both refuse (`H5c`).

Slices are separately authorized. Passing slice 1 authorizes neither slice 2
nor slice 3.

---

## 11. Later amendment — what a database would additionally enable

**Clearly separated, and assumed by nothing above.** Everything in §5 works
today with filesystem and memory only. If and when a database exists for the
local control plane, the following become *possible*; none of them is designed
here, and each needs its own decision:

| Capability | What it would add | Why it is not assumed today |
| --- | --- | --- |
| Encrypted persistent membership cache | membership surviving a console restart, with a longer TTL | `LD-3`: a durable record of a named human's directory position, and a persisted positive that can outlive a revocation. Also needs an encryption-key custody decision the product does not have |
| Durable authorization decision history | a queryable audit of who was permitted/denied/unevaluated for which action, over time | today there is one redaction-aware log line per decision and no queryable store. Retention, access control and privacy classification would all need deciding |
| Multi-session actor state | more than one console process sharing one actor's authorization state | out of scope by construction — this is a single-operator, loopback design (§3) |
| Role/group mapping stored outside runtime configuration | editing the role → group binding without a restart | `LD-4` deliberately keeps group references out of the repository; moving them into a database changes where a corporate identity lives, which is a privacy decision, not a convenience one |

The `M4` local control-plane metadata store is **not** automatically the home
for any of these (`LD-3`).

---

## 12. Acceptance gates for the eventual implementation

Not gates on this document. Recorded so the eventual implementation movement
inherits them rather than re-deriving them.

| id | Gate |
| --- | --- |
| `AG-1` | No directory credential, DN, group reference, hostname or search base appears in any repository file, any argv, any URL, or any log line. Tested with synthetic values, asserting the redaction registry covers both DN and secret |
| `AG-2` | A bind with an empty secret is refused **before** the network call (`LD-1`, RFC 4513 unauthenticated-bind trap) |
| `AG-3` | A configured-but-unreadable CA bundle raises before any network call, with a value-free message (`LD-6`, mirroring `pan_tls_trust`) |
| `AG-4` | **A configured `local_ldap` authority that is unreachable never resolves to `NO_APPLICABLE_AUTHORITY`** — the fail-open trap named in `LD-2`, tested directly |
| `AG-5` | `E4` is total: every declared action resolves to exactly one of the four outcomes (`AC-CS-67`) |
| `AG-6` | `E3` is evaluated exactly once and never inside `E4`; a `CLASS 1` action refused by `E3` **and** denied by `E4` records both reasons, refusal last (`H5c`) |
| `AG-7` | Varying `D7` across all values changes no rendered structure — no entry, module or tab appears or disappears (`AC-CS-33`) |
| `AG-8` | `AUTHZ_NOT_EVALUATED` renders and audits distinctly from `DENIED`, in neutral tone, never with refusal wording (`AC-CS-80`) |
| `AG-9` | A valid group membership does not satisfy `E1`, and a valid bearer token does not satisfy `E4`; neither is inferred from the other (§6) |
| `AG-10` | With the authority unconfigured, behavior is byte-for-byte today's behavior: `NO_APPLICABLE_AUTHORITY`, no new import on any non-console path, no new startup requirement |
| `AG-11` | An expired re-validation interval whose re-read fails yields `AUTHZ_NOT_EVALUATED`, never a served stale `PERMITTED` (`LD-3`) |
| `AG-12` | Full regression + repository privacy gate + `git diff --check`; render harness only if slice 3 touches a UI module or payload builder |

---

## 13. Cross-references

- `docs/design/CAPABILITY_STATE_VOCABULARY_AND_PRESENTATION.md` — FROZEN.
  The `D7`/`E4` contract this design produces for. Authority over every
  vocabulary term used here.
- `docs/design/LOCAL_CONTROL_PLANE_RUNTIME_AND_ENROLLMENT.md` — FROZEN. §11.3
  (the local-pilot exemption risk), §12/§12.1 (the `M14` row). See §7.
- `docs/design/OPERATOR_CONSOLE_ARCHITECTURE.md` — the console's own
  contract; `C-D2` (cookieless bearer token) is what §6 preserves.
- `utils/action_taxonomy.py` — `E3`'s single source of truth. Untouched.
- `utils/operate/authorization.py` — the only existing authorization
  authority; `CLASS 2` only; unchanged by this design.
- `application/services.py`, `utils/runtime_config_source.py` — the
  `DEV.2.1`/`DEV.2.2` credential pattern reused by `LD-1`.
- `utils/pan_tls_trust.py` — the CA-bundle preflight posture reused by `LD-6`.
- `docs/AI_DEVELOPMENT_PROTOCOL.md` — "Approval boundaries" (dependency
  additions, new network-access patterns), the network-device command gate
  (`U-3`).
- `docs/reference/REAL_ENV_VALIDATION_PROTOCOL.md` — the procedure `U-2`
  closes under.
