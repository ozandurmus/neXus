# LDAP service-bind login (Active Directory) — design and contract, 2026-11-11

Status: FROZEN 2026-11-11 after the Argon review (`ARGON_LDAP_SERVICE_BIND_REVIEW_2026_11_11.md`, disposition in §12).
Decision: `docs/design/PO_DECISION_RECORD_2026_11_11_LDAP_SERVICE_BIND.md`.
Replaces: the `%s` bind-DN-template model of `directory_profiles` (V26).

## 1. Why

On 2026-11-11 the PO configured LDAP and could not sign in. The saved profile was
unusable in four independent ways (no `%s` in the template, empty access group, no
CA PEM, an IP host under a strict hostname check), and the loader swallowed the
exception, so the only symptom was `directory_profile_invalid` with nothing logged.
Two more defects were found while designing this:

- The one directory role binding on the live system names profile `default`, while
  the loader used the row UUID as profile id, so it could never match.
- A directory sign-in's group set is fresh for 300 s, and nothing refreshes it
  (`DirectoryAuthorizationRefresh` has no caller and revalidation is constructed
  disabled). A directory session would lose every role after five minutes.

The PO compared this with Tufin's Active Directory integration and decided to adopt
the same model: a service account, a `sAMAccountName` search, an administrators
group and a users group, and an optional "trust any certificate" mode.

## 2. Settings model (one row, `ldap_settings`)

| Field | Rule (save-time validation, each failure has its own message) |
|---|---|
| `enabled` | boolean. Off: the LDAP mechanism answers "directory unavailable"; local login is untouched. |
| `server_type` | `ACTIVE_DIRECTORY` only (closed set; the only type in scope). |
| `servers` | 1..5 entries, each a hostname or IPv4/IPv6 literal, no scheme, no port, no spaces, no duplicates. Tried in order. |
| `port` | 1..65535, default 636. |
| `domain_dn` | Search base. Must parse as a DN (UnboundID `DN`), must not be empty. |
| `admin_group_dn` | Must parse as a DN. Required. |
| `users_group_dn` | Must parse as a DN. Required. May not equal `admin_group_dn`. |
| `bind_dn` | Service account. Must parse as a DN (AD also accepts `user@domain`; we accept a DN or a UPN `name@domain`). |
| `bind_password` | Write-only. Stored as `bind_password_encrypted` (AES-256-GCM, `CredentialStoreCipher`, the credential store's key) plus its key id. Never logged, never returned; reads return `bindPasswordSet: true/false`. An empty value on save or test keeps the stored one **only while the connection target is unchanged** (servers, port, bind DN, LDAPS, trust mode, certificate); otherwise the form must carry the password again, so the stored secret is never sent to a newly entered server. |
| `connect_timeout_seconds` | 1..60, default 10. Used as the connect and the response timeout. |
| `ldaps` | boolean, default true. Off is plain LDAP: the warning in the UI says passwords cross the network in clear text. |
| `trust_mode` | `TRUST_ANY` or `TRUST_PEM`. Only meaningful when `ldaps` is on. |
| `trust_pem` | Required in `TRUST_PEM`: 1..10 `CERTIFICATE` blocks, nothing else (no keys, no trailing text), each parses as X.509 and is inside its validity window at save time. It may be a CA or the server's own certificate. |

The browser never receives the password or the ciphertext. Every write is audited
through the table's audit trigger (actor and action, never values).

## 3. Trust

- `TRUST_ANY`: TLS with a trust manager that accepts any chain and no hostname check.
  An IP address works. The UI shows a warning; the PO owns this risk exactly as in
  `PO_DECISION_RECORD_2026_09_21_PAN_TLS_VERIFICATION_DISABLED.md`.
- `TRUST_PEM`: PKIX against only the pasted certificates as anchors (no JDK default
  CAs), plus JDK endpoint identification `LDAPS`: a hostname must match a DNS SAN /
  CN, an IP address must match an IP SAN. A pinned leaf certificate is accepted as
  its own anchor (JDK PKIX accepts a chain whose first certificate is a trust anchor).
  PKIX does not check an anchor's dates, so after PKIX accepts the chain every
  presented certificate must also be inside its validity window.
- The TLS handshake completes inside `connect()` (an `SSLSocketVerifier` that calls
  `startHandshake()`), so a TLS failure is a connect-time failure: `tls_trust_failed`,
  next server. UnboundID's `HostNameSSLSocketVerifier` is not used.
- `ldaps = false`: plain TCP. No StartTLS in this model (not in the Tufin form).

## 4. Login flow (`UnboundIdOperatorBindAdapter`)

Input: the typed user name and a `char[]` password.

0. Refuse before any network call: empty password (RFC 4513 unauthenticated bind),
   blank user name, user name longer than 256 characters, settings disabled.
1. Open a connection to the first server that accepts TCP + TLS; on a connect
   error, a TLS failure or a timeout, try the next server. A server that answered
   an LDAP operation is not abandoned for the next one.
2. Bind as the service account. Failure: `service_bind_failed`.
3. Search `domain_dn`, subtree, filter
   `(&(objectCategory=person)(objectClass=user)(sAMAccountName=<value>))`, built
   from typed UnboundID `Filter.createEqualityFilter` components, which apply RFC
   4515 escaping to the value (`*`, `(`, `)`, `\`, NUL). `objectCategory=person`
   excludes computer and trust accounts, which inherit `objectClass=user`.
   Attributes `1.1` only, size limit 2, time limit = timeout, referrals not
   followed, search references ignored. Zero entries or `noSuchObject`:
   `user_not_found`. Two or more, or `sizeLimitExceeded`: `user_ambiguous`. The
   found DN must parse, must not be the null DN and must lie below `domain_dn`;
   otherwise `user_not_found`.
4. Bind as the found entry's DN with the user's password, on a second connection to
   the same server, while the service connection stays open. Wrong password, locked
   or disabled account: `user_bind_failed`; result 8/13 (LDAP signing or TLS
   required): `strong_auth_required`. The password `char[]` and its UTF-8 bytes
   are zeroed right after the bind. The user bind comes before any group query
   (Argon finding 8): a wrong password costs the domain controller no nested walk.
5. Membership, on the service connection, for each configured group:
   base-scope search on the group DN with
   `(member:1.2.840.113556.1.4.1941:=<user DN>)` (LDAP_MATCHING_RULE_IN_CHAIN),
   built with `Filter.createExtensibleMatchFilter` (never string concatenation),
   attributes `1.1`. One entry back means member. A missing group (noSuchObject)
   counts as not a member. The group set holds the **configured** DN strings, the
   same strings the role-binding sync writes, so the evaluator's exact match holds.
   Why IN_CHAIN and not `memberOf`: `memberOf` lists direct memberships only, so a
   user placed in a nested group (the common AD practice of role group inside an
   access group) would be refused; IN_CHAIN resolves nesting on the domain
   controller in one bounded query per group, and asking the group (base scope)
   keeps the cost to two small queries. It is AD-specific, which matches the
   only server type in scope. Primary-group membership (`primaryGroupID`) is not
   expressed through `member` and is not honoured; the groups used here are not
   primary groups.
6. Not in either group: `not_in_group`.
   The order of reason codes is fixed: a wrong password reports `user_bind_failed`
   even when the user is also outside both groups.
7. Success: an observation with principal = the user's DN (encrypted at rest as
   today), group set = the configured group DNs the user is a member of (a subset of
   {admin, users}), profile id `default`.

The browser sees the same `INVALID_CREDENTIALS` body for every refusal, and
`DIRECTORY_UNAVAILABLE` (503) for service bind, TLS, timeout and configuration
failures. The server logs exactly one line per failed directory attempt:
`ldap_login_refused reason=<code>`, with `<code>` from the closed set
`service_bind_failed, user_not_found, user_ambiguous, user_bind_failed,
not_in_group, tls_trust_failed, timeout, connect_failed, strong_auth_required,
search_failed, directory_disabled, settings_invalid, directory_error`. Never the
user name, a DN, the password, the server address, or an exception (its message
can carry all of those). When every server fails before answering, the code is the
last server's. (`connect_failed`, `strong_auth_required`, `search_failed`,
`directory_disabled`, `settings_invalid` and `directory_error` extend the PO list
for the cases it does not name; they carry no identity either.) The login throttle
keys the user name case-insensitively, as AD compares `sAMAccountName`.

## 5. Roles

Saving the settings synchronises system-managed `DIRECTORY_GROUP` role bindings for
profile `default` (created by actor `system:ldap-settings`):

- administrators group DN -> `role:security_admin`, `role:operation_admin`, `role:operator`,
  `role:onboarding_admin`, `role:backup_admin`, `role:compliance_admin`, `role:viewer`
  (PO-ratified 2026-11-11, decision record)
- users group DN -> `role:viewer`

The sync runs inside the role-binding table lock and reads the settings row inside
it, so concurrent saves converge on the last committed row. It matches existing
managed bindings by decrypting their group reference (the cipher is randomized). It
creates missing bindings and revokes system-managed bindings that no longer
match the settings (group DN changed). Bindings an administrator created by hand are
never touched. `RbacEvaluator` is unchanged: it already matches a binding's group
reference against the actor's resolved group set.

Profile id is the constant `default`. It is the id the role-binding screen already
uses, so the live hand-made binding keeps its meaning. Only the two configured groups
are resolved at sign-in, so a hand-made `DIRECTORY_GROUP` binding matches only when it
names one of them (same DN string); a binding for any other group is inert.

## 6. Freshness and revalidation

The login observation stays valid for 300 s (unchanged). When `RbacEvaluator` meets
a directory actor whose group set is stale, it calls the existing
`DirectoryAuthorizationRefresh` once, which now runs: service bind, base search on
the principal DN with `(&(objectCategory=person)(objectClass=user)
(!(userAccountControl:1.2.840.113556.1.4.803:=2)))` (an enabled user), the same
IN_CHAIN membership checks, and publishes a new 300 s observation. The directory
round trip runs outside the table-locking directory mutation; the mutation then
re-checks that the actor's proof and session are unchanged before it publishes or
revokes. One refresh per actor at a time; a failed refresh is not retried for 15 s;
`evaluateAny` stops after a stale outcome instead of refreshing once per token.
`GET /session/status` evaluates before it lists roles, and lists roles only from a
fresh group set. Outcomes:

- still in a group: roles refreshed, request continues;
- in no group any more, or the user entry is gone or disabled: the session is revoked
  (`session_access_group_lost`, existing path);
- directory unreachable: the decision stays `AUTHZ_NOT_EVALUATED` (fail closed)
  for that request; the next request retries.

A settings save retires the trust generation and expires every directory actor's
group set (existing `expireDirectory`, which deletes the rows), so directory users
sign in again under the new settings. A pod start does not: only a generation that
actually served sign-ins expires anything when it is retired.

## 7. Test connection

`POST /api/v2/config/ldap/test` with `{ "settings": <unsaved form, optional>,
"test_user": "<name>" }`, `role:security_admin`, CSRF-protected like every write.
It runs steps 1–4 (service bind, user search, group membership) and never a user
bind, so it needs no user password. Response: per server, `reached`/`tls`/`service_bind`
outcome; then `user_found: 0|1|many`, `in_admin_group`, `in_users_group`, and the
admin/users group existence. It never returns attributes, the DN, or any value from
the directory. A blank password in the form means "use the stored password".

## 8. Storage and migration (V204)

Renumbered from V205 to V204 on 2026-11-11 before shipping: the advisory branch that held V204 did not ship and main ends at V203 (Flyway runs without out-of-order). The Argon review below names V205; it is the same migration.

- Create `ldap_settings` (singleton: `id smallint primary key check (id = 1)`),
  grants to `ui2_app`, audit trigger as for `directory_profiles`.
- Delete any `actor_authz_state` row that carries a directory profile id (0 live).
- Retire `directory_profiles`: drop its trigger, function and table. The live row is
  invalid on every axis and cannot be migrated meaningfully (no `%s`, no access
  group, no CA). Its history stays in `audit_log`. No live actor holds directory
  state (`actor_authz_state` with a profile id: 0 rows), so nothing references it.
- The PO re-enters the settings in the new form.

## 9. Interfaces

- REST: `GET /api/v2/config/ldap` (settings without secret), `PUT /api/v2/config/ldap`
  (validate, 400 `INVALID_SETTINGS` with a list of field problems), `POST
  /api/v2/config/ldap/test`. Read `LDAP_CONFIG_READ`, write and test
  `LDAP_CONFIG_WRITE` (both `role:security_admin`).
- CLI (`NexusSessionCli`): `ldap`, `ldap-set <settings.json> [--password-file <f>]`
  (the password is read from a prompt without echo or from the file, never from the
  settings file), `ldap-test <user> [settings.json] [--password-file <f>]`.
- UI: the LDAP screen is rebuilt to the Tufin field list, with save-time errors next
  to fields and a Test connection panel.

## 10. Kept as is

Local login, the root/break-glass identity, the single-session conflict flow, the
directory login throttle, the generic 401 body, and the at-rest encryption of the
principal and group references.

## 11. Tests

UnboundID in-memory directory server: service bind ok/fail, user found / not found /
ambiguous, filter-escaping (a name with `*`, `)(` and `\` finds nothing and matches
no other entry), user bind ok/fail, admin/users/none membership, nested membership
(the in-memory server does not implement rule 1941, so the test server registers an
interceptor that evaluates the IN_CHAIN filter by walking `member` — production code
is not changed for the test), multi-server failover, timeout, TLS: trust-any with an
IP and a certificate for another name, PEM mode refusing a wrong certificate and a
hostname mismatch, accepting a matching one. Log capture asserts no user name, DN or
password in any log line. Settings validation table tests. Revalidation: refresh,
group lost -> revoke, directory down -> not evaluated. Migration applies on the
integration database.

## 12. Argon review disposition

Review: `ARGON_LDAP_SERVICE_BIND_REVIEW_2026_11_11.md` (unaltered). Every finding is
taken; where the reviewer read the pre-rewrite classes, the fix is in the rewrite.

| # | Finding | Disposition |
|---|---|---|
| 1 | `LDAPSearchException` on sizeLimit / noSuchObject | Caught per search: ambiguous, not found, not a member, user gone (revocation). |
| 2 | Pinned leaf refused; anchor dates unchecked | No CA-only rule in the new loader; every presented certificate's dates checked after PKIX. |
| 3 | Lazy TLS handshake; UnboundID host-name verifier | Handshake forced in `connect()`; JDK endpoint identification only; trust-any adds no check. |
| 4 | Refresh holds a DB transaction over LDAP I/O; N refreshes per request; stale roles in status | LDAP outside the lock, single flight per actor, 15 s back-off, `evaluateAny` stops on stale, status evaluates first. |
| 5 | Stored password exfiltration via blank password | Blank keeps the stored password only for the unchanged connection target (save and test). |
| 6 | Filter construction, entry DN check, computer accounts, group string identity | Typed extensible filter; DN below `domain_dn`; `objectCategory=person`; configured strings. |
| 7 | Disabled accounts at revalidation | Enabled-user filter at revalidation. |
| 8 | Group queries before the user bind | User bind first; reason-code order kept. |
| 9 | Sync race, randomized cipher, disable, hand-made bindings | Sync under the table lock reading the row inside it; decrypt to compare; disable withdraws trust and expires directory state; §5 states hand-made scope. |
| 10 | Missing codes; signing required; exception leakage | `search_failed`, `strong_auth_required` added; no exception or message is ever logged. |
| 11 | Throttle case; temp PEM file; residual state | Case-insensitive throttle key; PEM parsed in memory; V204 deletes directory actor state. |
