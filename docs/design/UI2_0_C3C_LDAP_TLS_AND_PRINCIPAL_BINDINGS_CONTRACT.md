# UI 2.0 — C3C LDAP TLS trust and directory-principal bindings

## Status

**FROZEN — PRODUCT OWNER AUTHORIZED, 2026-09-15.** Movement
`NXS-LOCAL-0241`, approved task and canonical relay correction sequence 3
authorize this successor freeze, including encrypted group and individual
directory-principal bindings. This is design authority, not evidence of a
working adapter or corporate-policy approval for deployment.

## 1. Scope and amendment boundary

This successor to `docs/design/UI2_0_C3_IDENTITY_SESSIONS_RBAC_CONTRACT.md`
(C3, FROZEN) fixes explicit trust-material loading for both LDAP adapters,
standard-user authentication, and server-side authorization by either a
directory group or an individual directory principal. It replaces C3 §4's
group-only LDAP matching with §5 below and narrows C3 §4.2's display rule:
directory references and display attributes are not browser-visible for
either binding kind. It strengthens C3 §4.3 by refusing mutations whose
self-grant relationship cannot be evaluated. Other C3 session, gate-chain,
role-token, bootstrap and audit rules remain applicable.

`docs/design/UI2_0_C3A_LOCAL_AUTHENTICATION_CONTRACT.md` (C3A, FROZEN)
continues to govern local authentication. No automatic LDAP-to-local retry,
new authentication mechanism, exposure decision, NetworkPolicy, device
command, vendor-specific membership rule, live directory read, deployment
apply or implementation change is authorized by this movement. Existing
local/root behavior is outside this amendment; it must not become an
exception for a directory actor or a means of bypassing the new directory
binding protections.

Corporate reference persistence and service-account revalidation retain
C3 §4.4's joint `DIRECTORY-POSTURE` gate. It applies equally to individual
corporate principal bindings. Freeze approval does not flip that gate:
corporate-policy verification and deployment authorization remain required.
Synthetic local tests may exercise both kinds without corporate access.

## 2. Evidence and limits

The permitted source reads establish:

- `RoleBindingRecord`, `RoleBindingRepository`, and
  `JooqRoleBindingRepository` expose `groupReferenceEncrypted` and
  `groupReferenceKeyId`; the actual columns are
  `group_reference_encrypted` and `group_reference_key_id`. No binding-kind
  discriminator or authenticated principal reference exists there today.
- `RbacEvaluator.evaluate` checks binding presence, then
  `ActorAuthzStateRecord.isFresh`, then exact group-set membership. Its
  local and seeded-root branches already differ from the LDAP branch;
  C3A §7.1's description of an entirely branch-free implementation is not
  the implementation reality. This successor does not reconcile that
  historical discrepancy by changing local behavior.
- `actor_authz_state` holds `group_references`, `resolved_at`, and
  `valid_until`. `isFresh` requires the evaluation time to be strictly
  before `valid_until`. No separate principal proof exists today.
- `RoleBindingAdminService` checks create/revoke self-membership but treats
  missing/stale admin groups as an empty set. That is insufficient proof
  of non-self membership. The controller currently accepts a raw
  `groupReference`; the successor must replace this input for directory
  bindings with a server-resolved opaque selection (§5).
- The trust-loader finding describes the missing explicit format/PIN and
  a locally measured silent-empty PKCS12 load. It did not perform a TLS
  handshake and does not establish the estate's format or trust status.

Evidence-only, not authority: the DRAFT
`docs/design/M14_LOCAL_LDAP_AUTHORIZATION_ARCHITECTURE.md` and reported
`docs/design/LDAP_TLS_TRUST_STORE_PIN_GAP_2026_09_12.md` motivate the checks;
their open environment and vendor-semantic questions are not decisions.

Primary technical references:

- [Java 21 KeyStore](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/security/KeyStore.html)
  documents explicit type selection, password-bearing loading, and trusted
  certificate entries. The implementation must enumerate loaded entries
  to prove a usable anchor set rather than infer it from file readability.
- [UnboundID TrustStoreTrustManager](https://docs.ldap.com/ldap-sdk/docs/javadoc/com/unboundid/util/ssl/TrustStoreTrustManager.html)
  documents explicit format/PIN support and that `getAcceptedIssuers()`
  always returns an empty array. That method cannot prove an empty store.
  The published page currently describes SDK 7.0.5; compatibility with the
  repository's pinned SDK must be checked in the implementation movement.
- [RFC 4513 §§3.1.3, 5.1](https://www.rfc-editor.org/rfc/rfc4513.html)
  defines LDAP server identity checking and simple-bind security concerns,
  including the empty-password unauthenticated bind. These are protocol
  references, not approval of an Active Directory matching rule.

No raw directory response, real identity, certificate or credential was
retrieved. Deployment and LDAP-adapter implementation were not inspected;
source observations above are restricted to the permitted RBAC evidence.

## 3. Explicit CA trust, format and rotation

### 3.1 Configuration and preflight

One immutable validated trust snapshot is used by operator bind and
service-account revalidation. Deployment supplies a server-owned directory
profile containing endpoint, explicit transport (`LDAPS` or `STARTTLS`),
trust-material file reference and explicit format (`PEM`, `PKCS12`, `JKS`).
These are design fields, not claims about current configuration names.
There is no extension sniffing, JVM-default format selection or fallback to
system roots. Endpoint/profile values never originate in a browser.

- PEM is an X.509 CA certificate bundle, parsed with the existing JDK
  certificate facility into an in-memory trust store. Private keys and
  non-certificate content are refused. PEM takes no store PIN.
- PKCS12 and JKS use explicit `KeyStore` types and an explicit PIN from a
  component-purpose `_FILE` secret mount, following C3's existing secret
  custody pattern. A missing/empty PIN is refused for these formats; a
  deployment needing an unprotected store supplies a PEM CA bundle.
  No PIN value is placed in environment values, argv, a database, API
  output, logs or repository metadata. Mutable PIN buffers are cleared
  after loading, including failure paths.
- Before any directory connection, parse the actual material, validate
  the declared format, and enumerate trusted X.509 CA entries. Require at
  least one currently usable CA anchor. Key entries, leaf-only bundles,
  malformed/empty stores, wrong PINs, unreadable files and invalid anchors
  fail closed. Do not use `getAcceptedIssuers()` as the anchor-count check.
- Build the trust manager from this validated in-memory store, so preflight
  and handshake cannot read different file generations. Normal certificate
  path and validity checks remain enabled. A store PIN protects store
  loading; it is distinct from certificate/public-key pinning.

LDAP configured with invalid trust material fails startup preflight as C3
requires; no directory operation is attempted. LDAP not configured creates
no LDAP trust loader or connection and leaves the existing local mechanism
available. Errors expose only safe component/reason codes, not file
contents, paths, aliases, subjects or identities.

### 3.2 Transport and certificate identity

Corporate CA path verification **and** verification of the configured
endpoint's server identity are mandatory before any bind or identity/group
search. A trusted issuer alone is insufficient. Use SDK/JDK supported
identity verification proven by handshake tests; do not assume that a
trust manager checks the endpoint name.

LDAPS negotiates TLS before LDAP messages. STARTTLS performs only the TLS
upgrade before authentication/search; refusal, unsupported upgrade,
identity mismatch or handshake failure closes the connection. Neither
mode retries as plaintext or changes transport automatically. No trust-all,
hostname-check disable switch, trust-on-first-use, browser-uploaded trust,
or plaintext simple-bind seam is permitted in the production path.
Directory referrals are not followed automatically; another endpoint needs
its own server-owned approved profile and full verification, never
credential forwarding to a server named in a directory response.

The successor does **not** introduce leaf/SPKI pinning. Corporate CA trust
plus endpoint identity verification is the required model. Certificate
renewal under the approved CA needs no application pin update. Adding a
pinning model later requires its own contract; pin mismatch must never
become a CA-verification bypass.

### 3.3 Rotation and revalidation connection lifetime

Trust changes use operator-controlled atomic replacement and an explicit
validated reload; no browser/API trust edit or background learning occurs.
Validate a complete candidate before publication. Successful publication
atomically replaces the shared snapshot, retires all old revalidation
connections, and prevents any operation on an old snapshot from publishing
new authorization freshness. Subsequent binds use the new generation.
New CA rollovers may include an explicitly approved overlap bundle; old
anchors are removed by another validated reload after the rollover.

On a requested reload failure, LDAP admission and revalidation stop until a
valid generation is installed. Do not silently use old trust for new calls.
Existing authorization is not refreshed or extended; it expires at the
existing `valid_until`. Administrative trust withdrawal immediately
invalidates directory authorization freshness and retires directory
connections. Local authentication is not refreshed or revoked by this
directory-only event. This makes routine replacement and security
withdrawal explicit, separately testable operations.

## 4. Standard-user authentication and service-account boundary

Login uses the submitting operator's **own** transient credential, including
an ordinary directory user with no directory administration privileges.
Reject empty passwords and run C3's source/identity throttles before any
LDAP call. Use the existing controlled login request/transport; no new
credential API, proxy-asserted identity, anonymous prelookup or service-account
authentication on behalf of the user is introduced.

After verified TLS, attempt exactly one operator simple bind on a fresh
connection. Under that authenticated connection resolve exactly one own
principal and the minimum existing access/group evidence needed for
admission. Insufficient read rights, missing/ambiguous principal identity or
unproven correspondence to the authenticated identity yields no session or
authorization freshness. Do not demand elevated rights or substitute the
service account to make an ordinary user's login succeed. A successful bind
proves authentication only; C3's access admission and role checks still run.

The password remains in mutable memory for that bind, is cleared on every
exit, and is never stored, hashed into a local verifier, cached for takeover,
revalidation or retry, or included in responses, logs, audit, tracing,
exceptions, dumps or argv. The existing login ingress and LDAP bind are the
only necessary credential-bearing exchanges; no response or general-purpose
credential-management API carries it. Close the operator connection after
the bounded resolution, before returning the login response; no per-user
pool or subsequent password rebind exists. Preserve C3's session-conflict
flow without retaining the operator password.

Bad/empty credentials return C3's generic `401 INVALID_CREDENTIALS`;
TLS/transport failure returns `503 DIRECTORY_UNAVAILABLE`; throttling uses
the existing `429 LOGIN_RATE_LIMITED`. No identity-existence distinction or
raw SDK exception escapes. Directory-read failure is insufficient evidence,
not proof the authenticated account is unauthorized.

Revalidation, if `DIRECTORY-POSTURE` is enabled with recorded corporate
approval, uses only C3's dedicated least-privilege read-only service account
and its separately mounted password file. Re-read that secret each cycle;
retire prior authenticated connections on credential rotation and clear
temporary buffers. Resolve only the required own-principal/access/group
relationships within approved bases; principal revalidation grants no
arbitrary attribute/password-hash read or directory write. The service
account never creates a human session or impersonates a successful user
bind. Both adapters use §3's identical trust/identity policy. While disabled,
no service-account secret read, pool or scheduled directory call occurs.

## 5. Typed encrypted bindings and shared RBAC

### 5.1 Persistence and security identity

Both kinds live in the existing `role_bindings` repository/audit path.
Keep `binding_id`, role vocabulary, ciphertext/key-id columns, revocation
history and audit transaction ownership. This contract authorizes the
following **additive successor migration**, not an edit to existing Flyway
SQL and not execution of a migration in this movement:

| Table | Additive fields | Meaning |
|---|---|---|
| `role_bindings` | `binding_kind TEXT NOT NULL DEFAULT 'LEGACY'`; `directory_profile_id TEXT` | Closed kinds `LEGACY`, `DIRECTORY_GROUP`, `DIRECTORY_PRINCIPAL`. New directory rows require a profile; existing local/legacy rows retain their original representation. |
| `actor_authz_state` | `directory_profile_id TEXT`; `principal_reference_encrypted BYTEA`; `principal_reference_key_id TEXT` | One authenticated opaque directory principal, encrypted with the existing component secret-custody mechanism, attached to the same bounded observation/freshness interval as its group set. |

Add CHECK constraints restricting the kind and requiring a non-null profile
for the two directory kinds. Principal ciphertext and key id must be present
or absent together; a published new directory observation requires all
three actor fields. This ephemeral cache keeps its existing no-audit-trigger
rule and is deleted when its owning actor has no active session. Existing
rows lack principal proof and must be refreshed by a verified login or
approved revalidation before evaluating the new directory kinds.

For `DIRECTORY_GROUP`, `group_reference_encrypted` encrypts the opaque group
reference. For `DIRECTORY_PRINCIPAL`, that same column encrypts the opaque
principal reference. The kind/profile remain safe server-owned metadata;
neither is inferred from decrypted spelling. Encryption uses the existing
envelope/key-id custody and supports key rotation without changing identity.

The directory profile is an opaque server-owned namespace identifier. Exact
reference equality is evaluated only within the same profile and kind,
against identity-proven directory evidence. Do not join by display name,
submitted login spelling, case folding, DN rewriting, numeric conversion,
or C3's truncated actor fingerprint. That fingerprint remains a correlator,
not independent proof of directory identity; an ambiguous actor-to-principal
association fails closed. No new vendor attribute or equality rule is frozen
here. If current directory evidence cannot prove a principal reference,
individual matching is `AUTHZ_NOT_EVALUATED`, never a guessed match.

Backfill legacy directory rows only from proven server-side provenance;
never guess local versus directory, group versus principal, or profile from
identifier formatting. Ambiguous legacy rows are excluded from directory
matching and reported as unevaluable pending controlled resolution. Keep
legacy local evaluation intact; local rows cannot satisfy either new directory
kind. Before enabling the successor, clear legacy directory caches and
validate backfill, then enable reader/writer changes together. Rollback
disables the new directory path and expires its caches; an old reader must
not interpret principal ciphertext as a group. No rollback removes history
or weakens TLS checks.

### 5.2 Evaluation, administration and audit

Extend the existing `RbacEvaluator.evaluate` LDAP branch and its callers,
not a second evaluator or browser permission engine. The required token may
have bindings of both kinds. Evaluate currently active rows on every gate
check; revoked bindings cannot remain permitted through a role cache.

| Evidence | Outcome |
|---|---|
| Action declares no required token | Existing `NO_APPLICABLE_AUTHORITY`, subject to the other gates. |
| Token has no active binding | `AUTHZ_NOT_EVALUATED`, `role_token_unbound`. |
| Required directory observation missing/stale | `AUTHZ_NOT_EVALUATED`, existing `actor_group_set_stale`; no stale principal exception. |
| A fresh same-profile group reference matches a group row, **or** a fresh authenticated principal reference matches an individual row | `PERMITTED`, `ui2_ldap`, matching opaque `binding_id`. |
| All relevant rows are evaluable and none matches | `DENIED`, `actor_not_in_required_binding`. |
| No proven match and identity/profile/kind/decryption/membership evidence is ambiguous or unavailable for a relevant row | `AUTHZ_NOT_EVALUATED`, `directory_binding_not_evaluable`. |

The last two reason codes explicitly extend C3 §5.2's closed LDAP vocabulary;
`actor_not_in_required_group` remains compatible for existing paths. Select
the lexically first opaque matching `binding_id` if multiple rows match,
solely for deterministic audit attribution, never to infer identity.
Preserve the existing `403 ACTION_REFUSED`/decision-id envelope and the
shared `authz_decisions`/audit path. Individual assignment grants no access
admission exemption and no refresh beyond `valid_until`. An unavailable
directory starves both kinds' freshness; it never grants from a saved login
or treats service-account bind success as human authentication.

Create and revoke use the existing `role:security_admin` gate and audited
transaction path. Server-side directory selection returns only short-lived
opaque handles, scoped to acting session, profile and binding kind. Browser
requests select a handle, never a raw group/principal reference; handles
cannot be replayed across actors, profiles or expiry, and are revalidated
at mutation time. Responses, lists and audit linkage show safe kind/role
and `binding_id` only. No raw directory reference, name, attribute or
decrypted ciphertext enters browser output, logs or audit state.

Apply C3's four-eyes/self-grant rule identically on create and revoke:
refuse `403 SELF_GRANT_REFUSED` when the acting directory admin belongs to
the target group **or** is the target principal. Prove non-self locally
from fresh identity/group evidence; missing/stale/ambiguous evidence refuses
without mutation as `403 AUTHZ_NOT_EVALUATED`. A local acting admin's
mechanism identity must be proven distinct from a directory target; do not
compare its display name. No new root bypass for directory binding mutations
is introduced. Recheck permission, target resolution and non-self proof in
the mutation boundary to prevent selection/revocation races. Preserve
bootstrap's deployment-only boundary and the existing last-enabled-admin
protection; an unevaluable directory binding cannot be counted as a proven
remaining enabled administrator. Both kinds use identical protections and
audit coverage; neither gets a direct database or unaudited shortcut.

## 6. Implementation successor and validation gates

Next movement: **IMPLEMENTATION**, architecture/security reasoning while
integrating trust and typed identity; lower to targeted-validation reasoning
once the implementation is fixed. Scope is limited to the existing operator
and revalidation adapters, shared trust construction/configuration, additive
identity migration/repositories, existing RBAC/admin caller chain and their
necessary tests. Inspect actual configuration names and pinned SDK before
editing; do not copy design field names blindly. No new dependency, directory
transport, vendor matching rule, host command or live access is implicit.

The successor must leave runnable local checks using synthetic identities,
certificates and an isolated test directory, with no corporate credentials:

1. Load PEM, explicitly typed JKS and protected PKCS12. Refuse wrong/missing
   PIN, wrong format, unreadable/empty/corrupt/leaf-only material and zero
   usable anchors before connection. Verify PIN clearing and sanitized
   error capture. A positive handshake, not an issuer-array count, proves
   loaded trust actually works.
2. For **both adapters and both explicit transports**, accept a corporate-CA
   analogue with matching endpoint identity; reject untrusted CA, expired
   certificate, wrong endpoint and failed/refused STARTTLS. Assert zero
   binds/searches on rejection and no plaintext retry/referral forwarding.
   Production construction cannot reach the legacy plain-socket test seam.
3. A synthetic ordinary user with only required read rights can bind and
   resolve its own proof without service-account calls. Empty password makes
   zero directory calls; incorrect credentials and insufficient/ambiguous
   proof never create sessions. Capture persistence/API/log/exception output
   to assert no operator password/raw principal; assert connection closure
   and buffer clearing on every success/failure/conflict path.
4. Exercise the `DIRECTORY-POSTURE` disabled state (zero secret reads/calls),
   approved synthetic revalidation, failed revalidation and strict freshness
   expiry for **both** binding kinds. Rotation retires old credential/trust
   connections; in-flight old generations cannot publish freshness. Invalid
   reload stops new calls; trust withdrawal invalidates directory freshness.
5. Prove group-only, individual-only and mixed OR matching through the same
   evaluator/audit path, including wrong profile, identical spellings across
   kinds/local namespaces, missing principal, stale cache, revoked row,
   undecryptable row, ambiguous legacy backfill and deterministic binding
   attribution. No fingerprint/display-name equality supplies a grant.
6. Prove create/revoke self-grant refusal for both kinds, stale/unknown
   non-self refusal, handle scoping/expiry/replay protection, mutation races,
   last-admin protection and atomic audit context. Verify no raw reference
   in browser/API/audit/logs and no local/root regression. Test additive
   migration, proven backfill and fail-closed rollback with old readers.

This movement's gates are the dispatched authority/cross-reference pytest,
repository privacy check and `git diff --check origin/main`. No adapter test
is claimed run here. The implementation successor also runs regression,
privacy and, if UI/payload changes, the required render harness. Mark its
network-facing behavior `AUTOMATED_VALIDATED` after local tests only;
real-environment validation requires separate authorization and sanitized
relationship-only evidence before `REAL_ENV_VALIDATED`/`DONE`.

## 7. UNKNOWN register and Definition of Done

| UNKNOWN | Required closure; implementation restriction |
|---|---|
| Estate trust format/PIN, endpoint certificate/transport and CA rollover procedure | Deployment-controlled local comparison and separately approved TLS validation. No estate default or production readiness claim follows from this freeze. |
| Pinned SDK/JDK exact hostname-verifier and trust-loader behavior | Check installed APIs and the synthetic handshake matrix before adapter acceptance; published SDK docs are not proof about the pinned binary. |
| Ordinary-user rights and an identity-proven opaque principal reference | Synthetic tests first, then separately approved directory evidence. Refuse insufficient evidence; no elevated/login service-account substitute. |
| Active Directory nested/transitive membership and DN equivalence | Existing M14/C3 unknowns remain open. No new filter, matching rule or normalization is authorized; unevaluable relationships stay unevaluable. |
| Corporate approval for reference persistence and service-account revalidation | Recorded `DIRECTORY-POSTURE` verification/deployment action; keep both corporate binding kinds and service-account use gated until then. |
| Legacy binding provenance and directory namespace assignment | Controlled inventory during the successor; exclude ambiguous rows rather than infer identity. |

Architecture Definition of Done: this one successor is FROZEN with explicit
trust formats/PIN custody, verified TLS and no downgrade, transient
standard-user bind, separate gated revalidation, encrypted group/individual
binding semantics, shared RBAC/audit protections, a bounded implementation
and rollback plan, and passing authority/privacy/diff gates. Architecture
completion is not implementation completion or live-directory certification.

## Cross-references

- `docs/design/UI2_0_C3_IDENTITY_SESSIONS_RBAC_CONTRACT.md` — FROZEN
  predecessor; sessions, directory-posture gate, role tokens and audit.
- `docs/design/UI2_0_C3A_LOCAL_AUTHENTICATION_CONTRACT.md` — FROZEN local
  mechanism, unchanged by this directory successor.
- `docs/design/M14_LOCAL_LDAP_AUTHORIZATION_ARCHITECTURE.md` — DRAFT
  evidence-only precedent; no implementation authority.
- `docs/design/LDAP_TLS_TRUST_STORE_PIN_GAP_2026_09_12.md` — finding,
  evidence only; not a handshake result or deployment approval.
