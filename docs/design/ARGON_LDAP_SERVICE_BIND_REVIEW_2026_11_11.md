<!-- Argon consultation 2026-11-10T23:04:15Z, model gemini-4-argon-high, effort high; unaltered output -->

**Role:** `REVIEWER` (External Read-Only Security Review)

Below is the security and correctness review of [LDAP_SERVICE_BIND_DESIGN_2026_11_11.md](file:///Users/OzanDur/Codo/nexus-ldapsvc-1111/docs/design/LDAP_SERVICE_BIND_DESIGN_2026_11_11.md) and [PO_DECISION_RECORD_2026_11_11_LDAP_SERVICE_BIND.md](file:///Users/OzanDur/Codo/nexus-ldapsvc-1111/docs/design/PO_DECISION_RECORD_2026_11_11_LDAP_SERVICE_BIND.md) against the current implementation in `ui2/ldap-adapter` and `ui2/service`.

---

## Numbered Findings

### 1. [BLOCKER] (Q1, §4 Steps 3–4, §6) UnboundID `LDAPConnection.search()` throws `LDAPSearchException` on `SIZE_LIMIT_EXCEEDED` and `NO_SUCH_OBJECT` instead of returning a `SearchResult`
* **Where:** [LDAP_SERVICE_BIND_DESIGN_2026_11_11.md](file:///Users/OzanDur/Codo/nexus-ldapsvc-1111/docs/design/LDAP_SERVICE_BIND_DESIGN_2026_11_11.md#L67-L78), [UnboundIdOperatorBindAdapter.java](file:///Users/OzanDur/Codo/nexus-ldapsvc-1111/ui2/ldap-adapter/src/main/java/com/securityexpert/nexus/ui2/identity/ldap/UnboundIdOperatorBindAdapter.java#L79-L84), [UnboundIdRevalidationAdapter.java](file:///Users/OzanDur/Codo/nexus-ldapsvc-1111/ui2/ldap-adapter/src/main/java/com/securityexpert/nexus/ui2/identity/ldap/UnboundIdRevalidationAdapter.java#L61-L70), [DirectoryConnection.java](file:///Users/OzanDur/Codo/nexus-ldapsvc-1111/ui2/ldap-adapter/src/main/java/com/securityexpert/nexus/ui2/identity/ldap/DirectoryConnection.java#L40-L51).
* **Problem:** In the UnboundID LDAP SDK, `LDAPConnection.search(SearchRequest)` does **not** return a `SearchResult` when the server responds with a non-`SUCCESS` result code; it throws `LDAPSearchException` (a subclass of `LDAPException`). This breaks three load-bearing branches in the design if `LDAPException` is caught as a generic directory failure as in the current adapters:
  1. **Step 3 (`sizeLimit = 2`, `user_ambiguous`):** When >2 entries match in AD, AD returns 2 entries with result code `4` (`SIZE_LIMIT_EXCEEDED`), throwing `LDAPSearchException`. Catching `LDAPException` generically returns `503 DIRECTORY_UNAVAILABLE` instead of `401 INVALID_CREDENTIALS` (`user_ambiguous`).
  2. **Step 4 (`noSuchObject` on a configured group DN):** A `BASE` search on a non-existent group DN throws `LDAPSearchException` with result code `32` (`NO_SUCH_OBJECT`), aborting the login with `503 DIRECTORY_UNAVAILABLE` even if the other configured group exists and contains the user (or instead of returning `401 not_in_group`).
  3. **Section 6 (Revalidation when user is deleted or moved in AD):** A `BASE` search on a deleted/renamed principal DN throws `LDAPSearchException(ResultCode.NO_SUCH_OBJECT)`. In [UnboundIdRevalidationAdapter.java](file:///Users/OzanDur/Codo/nexus-ldapsvc-1111/ui2/ldap-adapter/src/main/java/com/securityexpert/nexus/ui2/identity/ldap/UnboundIdRevalidationAdapter.java#L68-L69), `catch (LDAPException e)` turns this into `"directory_unavailable"` (`AUTHZ_NOT_EVALUATED`) instead of `"access_group_lost"`, so a deleted or moved user's session is **never revoked**.
* **Concrete Fix:** Catch `LDAPSearchException` explicitly on each search:
  * In Step 3: treat `e.getResultCode() == ResultCode.SIZE_LIMIT_EXCEEDED` (or `e.getEntryCount() >= 2`) as `user_ambiguous` (`INVALID_CREDENTIALS`).
  * In Step 4: treat `e.getResultCode() == ResultCode.NO_SUCH_OBJECT` as "not a member" (`false`).
  * In Section 6 (principal base search): treat `e.getResultCode() == ResultCode.NO_SUCH_OBJECT` as `"access_group_lost"` so [DirectoryAuthorizationRefresh.java](file:///Users/OzanDur/Codo/nexus-ldapsvc-1111/ui2/service/src/main/java/com/securityexpert/nexus/ui2/service/security/DirectoryAuthorizationRefresh.java#L34-L37) revokes the session (`session_access_group_lost`).

---

### 2. [BLOCKER] (Q4, §3 `TRUST_PEM`) Current `DirectoryTrustPolicy.load()` rejects pinned leaf certificates, and JDK PKIX does not enforce expiration on a matched trust anchor at handshake time
* **Where:** [DirectoryTrustPolicy.java](file:///Users/OzanDur/Codo/nexus-ldapsvc-1111/ui2/ldap-adapter/src/main/java/com/securityexpert/nexus/ui2/identity/ldap/DirectoryTrustPolicy.java#L144-L157), [LDAP_SERVICE_BIND_DESIGN_2026_11_11.md](file:///Users/OzanDur/Codo/nexus-ldapsvc-1111/docs/design/LDAP_SERVICE_BIND_DESIGN_2026_11_11.md#L51-L54).
* **Problem:**
  1. [DirectoryTrustPolicy.java](file:///Users/OzanDur/Codo/nexus-ldapsvc-1111/ui2/ldap-adapter/src/main/java/com/securityexpert/nexus/ui2/identity/ldap/DirectoryTrustPolicy.java#L145-L149) currently enforces `ca.getBasicConstraints() < 0` and `usage[5]` (`keyCertSign`). A Domain Controller's leaf certificate has `cA=false` (`getBasicConstraints() == -1`) and `keyUsage = digitalSignature | keyEncipherment` (no `keyCertSign`), so `DirectoryTrustPolicy.load()` unconditionally throws `IllegalArgumentException` (`directory_trust_load_refused`) for any pinned leaf certificate.
  2. When a leaf certificate (or CA certificate) is placed in a `KeyStore` as a `trustedCertificateEntry`, OpenJDK's `PKIXValidator` treats `chain[0]` matching a trust anchor as trusted without running RFC 5280 path validation on the anchor itself, which means **JDK PKIX does not check `notAfter` / `notBefore` on the trust anchor during TLS handshakes**. A pinned leaf certificate that was valid at save time (§2) and expires weeks later will remain trusted indefinitely unless `chain[0].checkValidity()` is enforced at handshake time.
* **Concrete Fix:**
  * In `DirectoryTrustPolicy.load()`, allow a certificate entry when either `(ca.getBasicConstraints() >= 0 && (usage == null || (usage.length > 5 && usage[5])))` (CA anchor) **or** `ca.getBasicConstraints() < 0` (pinned end-entity certificate).
  * Wrap the `X509ExtendedTrustManager` produced by `TrustManagerFactory` so `checkServerTrusted` always calls `chain[0].checkValidity()` (and for multi-cert chains, calls `cert.checkValidity()` on the matched anchor) on every TLS handshake.

---

### 3. [BLOCKER] (Q1, Q4, §3, §4 Steps 1–2) UnboundID lazy TLS handshake and `HostNameSSLSocketVerifier` interaction breaks Step 1 failover, `TRUST_ANY`, and `tls_trust_failed` classification
* **Where:** [DirectoryConnection.java](file:///Users/OzanDur/Codo/nexus-ldapsvc-1111/ui2/ldap-adapter/src/main/java/com/securityexpert/nexus/ui2/identity/ldap/DirectoryConnection.java#L22-L33), [DirectoryTrustPolicy.java](file:///Users/OzanDur/Codo/nexus-ldapsvc-1111/ui2/ldap-adapter/src/main/java/com/securityexpert/nexus/ui2/identity/ldap/DirectoryTrustPolicy.java#L167-L195).
* **Problem:**
  1. In [DirectoryConnection.java](file:///Users/OzanDur/Codo/nexus-ldapsvc-1111/ui2/ldap-adapter/src/main/java/com/securityexpert/nexus/ui2/identity/ldap/DirectoryConnection.java#L22), `options.setSSLSocketVerifier(new HostNameSSLSocketVerifier(true, false))` is hardcoded. If left in place for `TRUST_ANY`, UnboundID will reject IP literals without SANs and hostname mismatches even when the `SSLContext` trusts all certificates. Conversely, in `TRUST_PEM` mode, UnboundID's `HostNameSSLSocketVerifier` **exempts numeric loopback addresses (`127.0.0.1`)** from hostname verification ([DirectoryTrustPolicy.java](file:///Users/OzanDur/Codo/nexus-ldapsvc-1111/ui2/ldap-adapter/src/main/java/com/securityexpert/nexus/ui2/identity/ldap/DirectoryTrustPolicy.java#L166)), so relying on it alone breaks local/in-memory hostname-mismatch tests (§11).
  2. In UnboundID, when `connectTimeoutMillis > 0`, `ConnectThread` calls `socketFactory.createSocket()` (the zero-arg overload!) and then `socket.connect( new InetSocketAddress(inetAddress, port), timeout)`. If `SSLSocketVerifier` is the default `TrustAllSSLSocketVerifier` (a no-op), **UnboundID does not run the TLS handshake during `connection.connect()`** — the handshake is deferred until the first bytes of `connection.bind()` are written in Step 2! That causes a TLS handshake failure on Server 1 to throw inside Step 2 (`bind`) instead of Step 1 (`connect`), breaking multi-server failover on TLS errors and misclassifying `tls_trust_failed` as `service_bind_failed` or `connect_failed`.
* **Concrete Fix:**
  * In `TRUST_PEM` mode: keep `EndpointCheckingSocketFactory` (`parameters.setEndpointIdentificationAlgorithm("LDAPS")`) **and** set a custom `SSLSocketVerifier` on `LDAPConnectionOptions` that calls `sslSocket.startHandshake()` during `connect()` (and converts `SSLHandshakeException` / `CertificateException` into a distinct marker or cause so Step 1 classifies it as `tls_trust_failed` and fails over to the next server). Do **not** use UnboundID's `HostNameSSLSocketVerifier` as the sole verifier.
  * In `TRUST_ANY` mode: do **not** wrap with `EndpointCheckingSocketFactory` and do **not** use `HostNameSSLSocketVerifier`; use an `SSLSocketVerifier` that only calls `sslSocket.startHandshake()` so TLS handshake failures still happen during Step 1 (`connect`) and trigger failover.

---

### 4. [BLOCKER] (Q5, §6) Calling `DirectoryAuthorizationRefresh` from `RbacEvaluator` holds a DB transaction across network LDAP I/O, triggers up to $N$ sequential LDAP revalidations in `evaluateAny()`, and races `SessionStatusController`
* **Where:** [DirectoryAuthorizationRefresh.java](file:///Users/OzanDur/Codo/nexus-ldapsvc-1111/ui2/service/src/main/java/com/securityexpert/nexus/ui2/service/security/DirectoryAuthorizationRefresh.java#L26-L48), [RbacEvaluator.java](file:///Users/OzanDur/Codo/nexus-ldapsvc-1111/ui2/service/src/main/java/com/securityexpert/nexus/ui2/service/security/RbacEvaluator.java#L141-L146), [RbacEvaluator.java](file:///Users/OzanDur/Codo/nexus-ldapsvc-1111/ui2/service/src/main/java/com/securityexpert/nexus/ui2/service/security/RbacEvaluator.java#L180-L189), [SessionStatusController.java](file:///Users/OzanDur/Codo/nexus-ldapsvc-1111/ui2/service/src/main/java/com/securityexpert/nexus/ui2/service/api/SessionStatusController.java#L118-L158).
* **Problem:** Three concrete defects in the existing classes wired by Section 6:
  1. **DB connection-pool starvation under slow/unreachable AD:** [DirectoryAuthorizationRefresh.java](file:///Users/OzanDur/Codo/nexus-ldapsvc-1111/ui2/service/src/main/java/com/securityexpert/nexus/ui2/service/security/DirectoryAuthorizationRefresh.java#L26-L32) calls `directory.revalidatePrincipal(principal)` **inside** `bindings.directoryMutation(tx -> ...)`. With up to 5 servers and `connect_timeout_seconds` up to 60s, concurrent browser requests after the 300s TTL (or after `expireDirectory` on settings save) will hold open JDBC transactions across blocking network LDAP I/O, exhausting the HikariCP pool and starving local/break-glass logins.
  2. **Multiplied network calls in `evaluateAny()`:** [RbacEvaluator.java](file:///Users/OzanDur/Codo/nexus-ldapsvc-1111/ui2/service/src/main/java/com/securityexpert/nexus/ui2/service/security/RbacEvaluator.java#L180-L189) loops over `requiredTokens` and calls `evaluate()` for each token. When the directory is unreachable, `refresh()` returns `false` and `actor_authz_state` remains stale, so `evaluateAny()` with 4 role tokens will execute **4 consecutive full LDAP revalidation attempts** in a single HTTP request.
  3. **Stale/revoked session leak in `GET /session/status`:** [SessionStatusController.java](file:///Users/OzanDur/Codo/nexus-ldapsvc-1111/ui2/service/src/main/java/com/securityexpert/nexus/ui2/service/api/SessionStatusController.java#L118-L157) reads `actorAuthzStateRepository.find(...)` at line 118 **without checking `state.isFresh(now)`** (and without checking `state.directoryProfileId().equals(binding.directoryProfileId())`), populates `authenticated: true` and `role_tokens` from the stale state, and only calls `rbacEvaluator.evaluate(...)` at line 155. If line 155 triggers revalidation and revokes the session (`session_access_group_lost`), `/session/status` still returns `200 OK` with `authenticated: true` and the old role tokens!
* **Concrete Fix:**
  1. In `DirectoryAuthorizationRefresh.refresh()`, read `(state, session)` outside the write transaction, run `directory.revalidatePrincipal(principal)` outside any DB transaction (coalesced per `actorFingerprint` via a single-flight lock / `ConcurrentHashMap<String, Object>`), and open `bindings.directoryMutation(tx -> ...)` only to persist the refreshed observation or revoke the session.
  2. In `RbacEvaluator.evaluate()`, when `authzState.isPresent() && !authzState.get().isFresh(now)`, call `refresh()` at most once; in `evaluateAny()`, break out of the loop immediately if `decision.reasonCode()` is `REASON_ACTOR_GROUP_SET_STALE` (since `refresh()` already ran and failed).
  3. In `SessionStatusController.status()`, invoke `rbacEvaluator.evaluate()` **before** reading `actorAuthzStateRepository`, re-verify `activeSession` afterwards, and only emit `role_tokens` when `state.isFresh(now)` and `state.directoryProfileId().equals(binding.directoryProfileId())`.

---

### 5. [BLOCKER] (Q7/§7 & §2) Stored `bind_password` exfiltration via `POST /api/v2/config/ldap/test` (and `PUT /api/v2/config/ldap`) when `bind_password` is blank
* **Where:** [LDAP_SERVICE_BIND_DESIGN_2026_11_11.md](file:///Users/OzanDur/Codo/nexus-ldapsvc-1111/docs/design/LDAP_SERVICE_BIND_DESIGN_2026_11_11.md#L37), [LDAP_SERVICE_BIND_DESIGN_2026_11_11.md](file:///Users/OzanDur/Codo/nexus-ldapsvc-11_11.md#L143-L149).
* **Problem:** Section 7 states: *"POST `/api/v2/config/ldap/test` with `{ "settings": <unsaved form, optional>, "test_user": "<name>" }` ... A blank password in the form means 'use the stored password'."* Section 2 states: *"An empty value on save keeps the stored one."*
  Because `ldaps = false` and `trust_mode = TRUST_ANY` are supported, an authenticated caller with `LDAP_CONFIG_WRITE` can submit an unsaved `settings` payload to `/api/v2/config/ldap/test` (or `/api/v2/config/ldap`) with `bind_password: ""`, `servers: ["<attacker-listener-ip>"]`, and `ldaps: false` (or `trust_mode: "TRUST_ANY"`). The backend will decrypt the stored production AD service-account password and transmit it in a cleartext `SimpleBindRequest` to the caller-specified host.
* **Concrete Fix:** When `bind_password` is blank in `POST /api/v2/config/ldap/test` or `PUT /api/v2/config/ldap`:
  1. Reject with `400 INVALID_SETTINGS` if no stored password exists yet in `ldap_settings` (first-time setup).
  2. Reject with `400 INVALID_SETTINGS` (`bind_password_required_on_connection_change`) if any connection/trust/identity field differs from the stored row: `servers`, `port`, `bind_dn`, `ldaps` (especially `true -> false`), `trust_mode` (especially `TRUST_PEM -> TRUST_ANY`), or `trust_pem`.

---

### 6. [BLOCKER] (Q1, Q3, §4 Steps 3–5, §5) User & group DN validation, `IN_CHAIN` filter construction, and `objectClass=user` matching AD computer accounts
* **Where:** [LDAP_SERVICE_BIND_DESIGN_2026_11_11.md](file:///Users/OzanDur/Codo/nexus-ldapsvc-1111/docs/design/LDAP_SERVICE_BIND_DESIGN_2026_11_11.md#L67-L95).
* **Problem:**
  1. **Filter injection / parse failure in Step 4 (`IN_CHAIN`):** Step 3 explicitly specifies `Filter.createEqualityFilter(...)`, but Step 4 writes `(member:1.2.840.113556.1.4.1941:=<user DN>)` as a string template. In AD, a valid user DN frequently contains parentheses or escaped characters (e.g., `CN=Jane Doe (Admin),OU=Ops,DC=corp,DC=local` or `CN=Doe\, Jane,...`). Building Step 4's filter via `Filter.create("(member:1.2.840.113556.1.4.1941:=" + userDn + ")")` throws `LDAPException` (invalid filter) or permits filter injection if the server returns a crafted DN.
  2. **Returned entry DN validation before Step 5 bind:** Step 5 binds as the searched entry's DN. If a rogue/misconfigured directory returns an entry with an empty DN `""` or a DN outside `domain_dn`, Step 5 could attempt an anonymous bind or bind against an arbitrary naming context.
  3. **`objectClass=user` matches computer accounts in AD:** In Active Directory's schema, `computer` inherits from `user`. `(&(objectClass=user)(sAMAccountName=<value>))` matches machine accounts (`HOST$`) and trust accounts, and forces AD to scan the multi-valued `objectClass` index without the single-valued `objectCategory=person` index.
  4. **Group DN string identity between Step 7 and Section 5:** `RbacEvaluator` ([RbacEvaluator.java](file:///Users/OzanDur/Codo/nexus-ldapsvc-1111/ui2/service/src/main/java/com/securityexpert/nexus/ui2/service/security/RbacEvaluator.java#L169)) and `SessionStatusController` ([SessionStatusController.java](file:///Users/OzanDur/Codo/nexus-ldapsvc-1111/ui2/service/src/main/java/com/securityexpert/nexus/ui2/service/api/SessionStatusController.java#L137)) use `state.groupReferences().contains(reference)` (exact `String.equals`, per `AGENTS.md` Identity Law). If Step 4 adds `searchResultEntry.getDN()` (AD's casing) to `groupReferences` while Section 5 writes `ldap_settings.admin_group_dn` (admin-typed casing/spacing) into `role_bindings`, `contains()` will return `false` whenever casing or RDN whitespace differs.
* **Concrete Fix:**
  1. Build the Step 4 filter exclusively with `Filter.createExtensibleMatchFilter("member", "1.2.840.113556.1.4.1941", false, userDn)`.
  2. Before Step 5, parse `entry.getDN()` as an UnboundID `DN`, verify `!dn.isNullDN()` and `dn.isDescendantOf(new DN(settings.domainDn()), false)`, and keep `options.setBindWithDNRequiresPassword(true)` on the connection options.
  3. Use `(&(objectCategory=person)(objectClass=user)(!(objectClass=computer))(sAMAccountName=<value>))` in Step 3.
  4. In Step 4/Step 7 and Section 6, when the base search on `settings.adminGroupDn()` (or `settings.usersGroupDn()`) returns 1 entry, add the **configured setting string** (`settings.adminGroupDn()` / `settings.usersGroupDn()`) to `groupReferences` — the exact same string written by Section 5's role-binding sync.

---

### 7. [SHOULD] (Q9, §6) Disabled or expired AD accounts are not detected during 300s revalidation
* **Where:** [LDAP_SERVICE_BIND_DESIGN_2026_11_11.md](file:///Users/OzanDur/Codo/nexus-ldapsvc-1111/docs/design/LDAP_SERVICE_BIND_DESIGN_2026_11_11.md#L125-L135).
* **Problem:** At initial login (Step 5), AD rejects disabled (`userAccountControl` bit 2, `UF_ACCOUNTDISABLE`) and expired accounts during the user bind. At revalidation (§6), there is no user bind — only a service-account base search on the principal DN with `(objectClass=user)` and the two `IN_CHAIN` group checks. In AD, disabling a compromised user's account leaves the user object and its `member` links intact, so revalidation will continue succeeding every 300s for the session's entire `absoluteLifetime`.
* **Concrete Fix:** Change the Section 6 principal base-search filter (and optionally Step 3's search filter) from `(objectClass=user)` to:
  ```java
  Filter.createANDFilter(
      Filter.createEqualityFilter("objectCategory", "person"),
      Filter.createEqualityFilter("objectClass", "user"),
      Filter.createNOTFilter(Filter.createEqualityFilter("objectClass", "computer")),
      Filter.createNOTFilter(Filter.createExtensibleMatchFilter(
          "userAccountControl", "1.2.840.113556.1.4.803", false, "2")))
  ```
  When an AD account is disabled (`UF_ACCOUNTDISABLE = 2`), the base search returns 0 entries and Section 6 revokes the session (`session_access_group_lost`).

---

### 8. [SHOULD] (Q2, §4 Steps 4–6) Running `IN_CHAIN` group queries before the user bind amplifies timing side-channels and DC load on invalid-password attempts
* **Where:** [LDAP_SERVICE_BIND_DESIGN_2026_11_11.md](file:///Users/OzanDur/Codo/nexus-ldapsvc-1111/docs/design/LDAP_SERVICE_BIND_DESIGN_2026_11_11.md#L74-L92).
* **Problem:** Step 4 executes two recursive `LDAP_MATCHING_RULE_IN_CHAIN` queries before Step 5 verifies the user's password, even though Step 6 mandates that a wrong password always reports `user_bind_failed` ahead of `not_in_group`. Because Step 5 already uses a separate connection to the same server, running Step 4 before Step 5 performs two unnecessary AD link-table graph walks for every wrong-password attempt and increases the response-time delta between `user_not_found` (1 search) and `user_bind_failed` (1 search + 2 `IN_CHAIN` searches + 1 user bind).
* **Concrete Fix:** Swap Steps 4 and 5: after Step 3 resolves the single user DN, run Step 5 (user bind on the second connection) while keeping the service-account connection open; only if the user bind succeeds, run Step 4 (the two `IN_CHAIN` group queries on the service-account connection) and return `not_in_group` if neither matches. Close both connections in `finally`. This preserves the exact same reason-code precedence (`user_bind_failed` before `not_in_group`) while eliminating `IN_CHAIN` queries on failed password attempts.

---

### 9. [SHOULD] (Q6, §5, §6) Managed role-binding sync race condition, `enabled = false` session linger, and hand-made group bindings
* **Where:** [LDAP_SERVICE_BIND_DESIGN_2026_11_11.md](file:///Users/OzanDur/Codo/nexus-ldapsvc-1111/docs/design/LDAP_SERVICE_BIND_DESIGN_2026_11_11.md#L107-L139), [RbacEvaluator.java](file:///Users/OzanDur/Codo/nexus-ldapsvc-1111/ui2/service/src/main/java/com/securityexpert/nexus/ui2/service/security/RbacEvaluator.java#L158-L177).
* **Problem:**
  1. **Concurrent save race:** If two `PUT /api/v2/config/ldap` requests run concurrently without serializing on the singleton `ldap_settings` row, `role_bindings` can end up synced to one request's group DNs while `ldap_settings` stores the other's.
  2. **Ciphertext comparison:** `GroupReferenceCipher` uses randomized encryption, so existing `system:ldap-settings` bindings cannot be matched by comparing `group_reference_encrypted` bytes; they must be decrypted via `GroupReferenceCipher` and matched by `(role_token, decrypted_group_dn)`.
  3. **Disabling LDAP (`enabled = false`):** If an admin saves `enabled = false`, existing directory sessions remain valid until their 300s observation expires unless `expireDirectory` is called and `DirectoryAuthorizationRefresh` treats `enabled = false` as revoking or refusing directory sessions.
  4. **Hand-made `DIRECTORY_GROUP` bindings:** Section 5 says *"Bindings an administrator created by hand are never touched."* However, because Step 4 only checks `admin_group_dn` and `users_group_dn`, any hand-made `DIRECTORY_GROUP` binding for a third AD group (or with different DN casing from `ldap_settings`) will never be added to `observation.groupReferences()` and will silently never match.
* **Concrete Fix:**
  1. Execute `ldap_settings` upsert and the `system:ldap-settings` `role_bindings` sync inside a single `TransactionBoundary` transaction locking `ldap_settings WHERE id = 1 FOR UPDATE`, then call `trust.withdraw()` / `actors.expireDirectory()` after commit.
  2. Confirm the PO-ratified admin role set in `PO_DECISION_RECORD_2026_11_11_LDAP_SERVICE_BIND.md` (§Role mapping is still marked `PENDING`) before freezing implementation.
  3. Either include active hand-made `DIRECTORY_GROUP` binding DNs (for profile `default`) in Step 4/§6's `IN_CHAIN` checks, or explicitly state in §5 that only the two configured groups (`admin_group_dn`, `users_group_dn`) and `DIRECTORY_PRINCIPAL` bindings are evaluated.

---

### 10. [SHOULD] (Q8, Q9, §4, §7) Reason-code gaps for post-bind search failures, AD `STRONG_AUTH_REQUIRED` (LDAP signing), and `LDAPException` diagnostic-message leakage
* **Where:** [LDAP_SERVICE_BIND_DESIGN_2026_11_11.md](file:///Users/OzanDur/Codo/nexus-ldapsvc-1111/docs/design/LDAP_SERVICE_BIND_DESIGN_2026_11_11.md#L97-L105), [LDAP_SERVICE_BIND_DESIGN_2026_11_11.md](file:///Users/OzanDur/Codo/nexus-ldapsvc-1111/docs/design/LDAP_SERVICE_BIND_DESIGN_2026_11_11.md#L143-L149).
* **Problem:**
  1. **Missing reason code for Step 3/4 search errors:** If Step 2 (`service_bind`) succeeds, but Step 3 returns `NO_SUCH_OBJECT` (32, wrong `domain_dn`), `INSUFFICIENT_ACCESS_RIGHTS` (50, service account lacks read rights), `REFERRAL` (10, `domain_dn` belongs to another domain), or `OPERATIONS_ERROR` (1), none of the 10 closed reason codes (`service_bind_failed`, `user_not_found`, `user_ambiguous`, `user_bind_failed`, `not_in_group`, `tls_trust_failed`, `timeout`, `directory_disabled`, `settings_invalid`, `connect_failed`) applies.
  2. **Modern AD LDAP signing (`ResultCode.STRONG_AUTH_REQUIRED = 8` / `CONFIDENTIALITY_REQUIRED = 13`):** When `ldaps = false` against a modern Windows Server DC with `LDAPServerIntegrity = 2` (Require signing), AD rejects simple binds with result code `8` (`STRONG_AUTH_REQUIRED`). Logging this as `service_bind_failed` misleads operators into rotating the service password instead of turning on LDAPS.
  3. **UnboundID `LDAPException` message leakage:** `LDAPException.getMessage()`, `getDiagnosticMessage()`, and `getMatchedDN()` contain DNs, filter strings (including the typed username), and server host:port. Passing `e` to SLF4J (`log.warn("...", e)`) violates the §4 no-identity logging guarantee.
* **Concrete Fix:**
  * Add `search_failed` and `strong_auth_required` to the closed `reason` set (both returning `503 DIRECTORY_UNAVAILABLE` on `/login`, and reported distinctly in `/api/v2/config/ldap/test`).
  * Specify that when multiple servers fail in Step 1, the single logged reason code is the failure code of the last attempted server.
  * Never pass `LDAPException` or `e.getMessage()` to the logger; log only the mapped enum token.

---

### 11. [NIT] (Q1, Q7, Q9) Case-insensitive username throttling, temporary PEM file cleanup, and V205 migration hygiene
* **Where:** [LoginController.java](file:///Users/OzanDur/Codo/nexus-ldapsvc-1111/ui2/service/src/main/java/com/securityexpert/nexus/ui2/service/api/LoginController.java#L66-L68), [DirectoryAuthenticationConfiguration.java](file:///Users/OzanDur/Codo/nexus-ldapsvc-1111/ui2/service/src/main/java/com/securityexpert/nexus/ui2/service/boot/DirectoryAuthenticationConfiguration.java#L65-L68), [LDAP_SERVICE_BIND_DESIGN_2026_11_11.md](file:///Users/OzanDur/Codo/nexus-ldapsvc-1111/docs/design/LDAP_SERVICE_BIND_DESIGN_2026_11_11.md#L152-L160).
* **Problem:**
  1. **Case-variant throttle bypass:** AD `sAMAccountName` is case-insensitive, but [LoginController.java](file:///Users/OzanDur/Codo/nexus-ldapsvc-1111/ui2/service/src/main/java/com/securityexpert/nexus/ui2/service/api/LoginController.java#L66-L80) passes `request.username()` verbatim to `directoryThrottle.admit()` and `directoryThrottle.succeeded()`. If `LoginAttemptThrottle` does not normalize username case (`toLowerCase(Locale.ROOT).trim()`), an attacker can rotate casing (`admin`, `Admin`, `aDmIn`) to bypass per-username rate limits and lock out the AD account on the DC.
  2. **Temp-file leak in `DynamicDirectoryProvider`:** [DirectoryAuthenticationConfiguration.java](file:///Users/OzanDur/Codo/nexus-ldapsvc-1111/ui2/service/src/main/java/com/securityexpert/nexus/ui2/service/boot/DirectoryAuthenticationConfiguration.java#L65) calls `Files.createTempFile("ldap-trust", ".pem")` on reload and never deletes it. With `ldap_settings.trust_pem`, parse the PEM `String` directly in memory without writing a temp file.
  3. **Dropping `directory_profiles` in V205 (Q7):** There is no reason to keep `directory_profiles`. However, V205 should include `DELETE FROM actor_authz_state WHERE directory_profile_id IS NOT NULL;` before/with the table drop so non-production/test databases that contain residual directory state remain clean.

---

## Summary Mapping to Review Questions (Q1–Q9)

| Question | Verdict | Findings |
|---|---|---|
| **1. Login flow (§4) fail-closed & bypasses** | Needs fixes for UnboundID `LDAPSearchException` handling, entry DN descendant/non-empty check, `objectCategory=person`, and Step 5 non-49 error mapping. Subtree `SearchResultReference` ignoring on `domain_dn` is right for AD domain roots as long as `followReferrals=false` and top-level `ResultCode.REFERRAL` fails closed. | **#1, #3, #6** |
| **2. Group membership before user bind (§4)** | Reporting `user_bind_failed` before `not_in_group` is sound and leak-free in HTTP/logs, but running `IN_CHAIN` before Step 5 creates avoidable timing & DC load amplification on wrong passwords. Swap Steps 4 and 5 while keeping the reason-code priority. | **#8** |
| **3. `LDAP_MATCHING_RULE_IN_CHAIN` (`1.2.840.113556.1.4.1941`)** | Right choice for single-domain AD nested groups; bounded to 2 base-scope queries. Must use `Filter.createExtensibleMatchFilter` (never string concatenation) and store the configured group DN string in `groupReferences`. | **#1, #6, #8** |
| **4. `TRUST_PEM` mode & UnboundID SSL verifier** | `DirectoryTrustPolicy.load()` currently rejects leaf certs (`getBasicConstraints() < 0` and `keyUsage[5]`); JDK PKIX does not check expiration on matched trust anchors at handshake time; UnboundID defers TLS handshake into Step 2 (`bind`) unless `SSLSocketVerifier` forces `startHandshake()`, and `HostNameSSLSocketVerifier` breaks `TRUST_ANY` and exempts `127.0.0.1`. | **#2, #3** |
| **5. Revalidation from `RbacEvaluator` (§6)** | Revoking on "in no group" is right, but `DirectoryAuthorizationRefresh` currently runs network LDAP I/O inside a DB transaction, `evaluateAny()` triggers up to $N$ sequential revalidations per request when AD is down, and `SessionStatusController` reads stale state before revalidation runs. | **#1, #4, #7** |
| **6. Managed role-binding sync on save (§5)** | Must lock `ldap_settings` (`FOR UPDATE`) in the same transaction as `role_bindings` sync, decrypt existing bindings to compare group DNs, ratify the `PENDING` role list in the PO decision record, and address hand-made `DIRECTORY_GROUP` bindings. | **#6, #9** |
| **7. Dropping `directory_profiles` in V205 (§8)** | Safe to drop; no reason to keep it. Clear any non-null `actor_authz_state` rows in V205 and stop creating temp `.pem` files in `DynamicDirectoryProvider`. | **#11** |
| **8. Logging reason-code set (§4)** | Leak-free only if `LDAPException.getMessage()` / stack traces are never logged. Add `search_failed` and `strong_auth_required` to the closed set, and define deterministic logging when all servers fail in Step 1. | **#10** |
| **9. Missing production AD requirements** | Guard against stored `bind_password` exfiltration on `/api/v2/config/ldap/test` when `bind_password` is blank; filter out disabled AD accounts (`!(userAccountControl:1.2.840.113556.1.4.803:=2)`) during §6 revalidation; handle AD result code `8` (`STRONG_AUTH_REQUIRED`) when `ldaps = false`; normalize username case in `LoginAttemptThrottle`. | **#5, #7, #10, #11** |

---

## Verbatim Instructions Received (Isolation Audit per `roles/REVIEWER.md`)

```text
/plan # Review request: neXus LDAP service-bind login (Active Directory), design before implementation

You are an external security reviewer. Read-only. Review the design note and the PO decision record:

- docs/design/LDAP_SERVICE_BIND_DESIGN_2026_11_11.md
- docs/design/PO_DECISION_RECORD_2026_11_11_LDAP_SERVICE_BIND.md

Current code it replaces (read as needed):
- ui2/ldap-adapter/src/main/java/com/securityexpert/nexus/ui2/identity/ldap/ (UnboundIdOperatorBindAdapter, DirectoryProfile, DirectoryTrustPolicy, DirectoryConnection, UnboundIdRevalidationAdapter)
- ui2/service/src/main/java/com/securityexpert/nexus/ui2/service/boot/DirectoryAuthenticationConfiguration.java
- ui2/service/src/main/java/com/securityexpert/nexus/ui2/service/security/{LoginFlow,RbacEvaluator,DirectoryAuthorizationRefresh}.java
- ui2/service/src/main/java/com/securityexpert/nexus/ui2/service/api/{LoginController,DirectorySettingsController,SessionStatusController}.java

Fixed by the Product Owner (do not argue these, but do state concrete risks you see in how they are implemented):
sAMAccountName search with a service account; Tufin-like form; "trust any certificate" mode exists (PO-owned risk); LDAPS can be switched off.

Questions:
1. Is the login flow (section 4) correct and fail-closed? Any authentication bypass: empty password / anonymous bind, filter injection, ambiguous match, referrals, a user DN that is not the searched entry, server failover after a partial answer?
2. Is computing group membership under the service account before the user bind, and reporting user_bind_failed first, sound? Any information leak?
3. LDAP_MATCHING_RULE_IN_CHAIN on the group (base scope) vs memberOf: is the choice right for AD; any cost or correctness trap?
4. TRUST_PEM mode: PKIX with only pasted anchors plus JDK "LDAPS" endpoint identification, accepting a pinned leaf. Correct? Pitfalls with UnboundID's own SSL socket verifier?
5. Revalidation on stale group set from RbacEvaluator (section 6): any race, recursion, or lockout risk; is revoking the session on "in no group" right?
6. Managed role-binding sync on save (section 5): risks (e.g. lock-out of the last security_admin, sync racing a concurrent save, hand-made bindings)?
7. Dropping directory_profiles in the migration (section 8): any reason to keep it?
8. Logging: is the reason-code set sufficient and leak-free?
9. Anything missing that a production AD integration needs (disabled accounts at revalidation, password-expired results, LDAP result codes 49 sub-codes, channel binding / LDAP signing requirements on modern AD, UPN bind DN form)?

Answer with a numbered list of findings, each marked BLOCKER / SHOULD / NIT, with the concrete fix. Be specific to this design; no generic advice.
```
