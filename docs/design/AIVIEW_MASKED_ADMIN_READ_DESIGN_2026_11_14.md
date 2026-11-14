# Architecture Design: `aiview` as a Masked, Read-Only Administrator

**Status:** `DRAFT` (`DO NOT FREEZE` — design specification for Product Owner review; does not authorize implementation until frozen)
**Date:** 2026-11-14
**Backlog Item:** `aiview_masked_readonly_admin`
**Scope:** Administration plane (`AdministrationScreen`, `/local-identities`, `/sessions`, `/roles`, `/role-bindings`, `/api/v2/config/ldap`, `/credentials`, `/api/v2/config/notifications`, `/api/v2/config/system-logs`, `/audit-logs`, `/api/v2/ssh-trust`, `/api/v2/config/scheduled-jobs`, `/api/v2/system/*`, `/project-plan`)

---

## 0. Problem Statement & Objective

Under `AGENTS.md` ("Mandatory AIView inspection & post-parsing masking law"), all UI inspections, visual reviews, screenshots, validation evidence, and Product Owner sign-offs are conducted exclusively under the `aiview` persona (`role:replay_viewer`).

Today, `aiview` cannot inspect most of the **Administration** plane because:
1. Most Administration read endpoints require `role:security_admin` in `ActionRegistry`, and `SessionStatusController` sets `can_read_administration = false` unless the session holds `role:security_admin`.
2. Two Administration read endpoints (`GET /sessions` and `GET /role-bindings`) are wired in `SecurityWebMvcConfig` directly to write action descriptors (`ActionRegistry.SESSION_REVOKE` and `ActionRegistry.ROLE_BINDING_CREATE`).
3. `PrivacyMaskingResponseBodyAdvice` only masks device hostnames (`TopologyNamePseudonymizer`), IPv4/IPv6 literals (`SubnetPreservingIpMasker`), and explicitly wrapped domain envelopes (`PolicyResponse`, `ChangeFeedResponse`, `IpSearchResponse`, `PacketDeviceEventsResponse`, `ReportResponse`). There is no general-purpose, cross-screen principal projection covering local usernames, directory Distinguished Names (DNs), AD group references, session actor fingerprints, credential usernames, notification recipient addresses, or audit-log actor/target identifiers.

Consequently, AI agents cannot verify Administration features after a build ships, forcing human operators to log in as `security_admin` and capture manual screenshots.

This document specifies a target architecture where `aiview` (`role:replay_viewer`, both browser and machine sessions) receives a **masked, read-only Administration projection**:
- Every principal-identifying field (usernames, actor fingerprints, directory DNs, group references, recipient addresses, credential labels/usernames, natural-key audit targets) is replaced on the server with a deterministic, non-reversible pseudonym (`USR-<hex>`, `GRP-<hex>`, `DN-<hex>`, `MAIL-<hex>`, `CRED-<hex>`, `SESS-<hex>`) that is consistent across Administration screens within a session window.
- Counts, booleans, enums, timestamps, role tokens, and health states remain real so layout, pagination, badges, and relational joins remain verifiable.
- Zero write, approve, revoke, test-connection, secret-mutate, or raw-export actions are reachable by `aiview` (enforced independently at `GateChain` E1, `GateChain` E4 RBAC, controller guards, and UI `can_write` flags).

---

## 1. Current Model

### 1.1 Closed Role Vocabulary & UI Planes

`RoleToken` (`ui2/platform-core/src/main/java/com/securityexpert/nexus/ui2/platform/RoleToken.java`) defines the closed 8-role vocabulary:

| Enum Constant | Wire Token | Current Purpose |
| :--- | :--- | :--- |
| `VIEWER` | `role:viewer` | Base read access across operational planes |
| `OPERATOR` | `role:operator` | Operational execution (e.g., readiness runs, packet analysis) |
| `ONBOARDING_ADMIN` | `role:onboarding_admin` | Device registration, discovery import/acknowledge, trust enrollment |
| `BACKUP_ADMIN` | `role:backup_admin` | Backup and recovery operations |
| `COMPLIANCE_ADMIN` | `role:compliance_admin` | Compliance rules and evaluations |
| `SECURITY_ADMIN` | `role:security_admin` | Identity, RBAC, sessions, credentials, LDAP, notifications, audit, trust |
| `OPERATION_ADMIN` | `role:operation_admin` | High-impact operational approvals, failover, scheduled-job switches |
| `REPLAY_VIEWER` | `role:replay_viewer` | Mandatory masked inspection persona (`aiview` / `aiview-e2e`) |

`SessionStatusController` (`GET /session/status`, ungated route reading the active session cookie) computes two fields that drive the frontend shell (`SessionContext.tsx`, `navModel.ts`, `AdministrationScreen.tsx`):
- `can_read_administration`: `true` if and only if `rbacEvaluator.evaluate(actor, Optional.of(RoleToken.SECURITY_ADMIN), now)` returns `PERMITTED`. For `aiview`, this is `false`.
- `permissions` (plane array): computed by `SessionStatusController.collectPlanes(actor, now)`:
  - `Devices`, `Config`, `Compliance`, `Policy`: granted when `VIEWER` or `REPLAY_VIEWER` is permitted.
  - `PacketAnalysis`: granted when `OPERATOR`, `SECURITY_ADMIN`, or `REPLAY_VIEWER` is permitted.
  - `Operations`: granted when `OPERATOR`, `BACKUP_ADMIN`, `ONBOARDING_ADMIN`, or `OPERATION_ADMIN` is permitted.
  - `Admin`: granted only when `SECURITY_ADMIN`, `ONBOARDING_ADMIN`, or `OPERATION_ADMIN` is permitted.
  - `Sanitizer`: granted only when `SECURITY_ADMIN` is permitted.

For `aiview`, `/session/status` returns:
```json
{
  "authenticated": true,
  "can_read_administration": false,
  "permissions": ["Devices", "Config", "Compliance", "Policy", "PacketAnalysis"]
}
```

### 1.2 How `aiview` Sessions Are Seeded, Created, and Governed

`AiViewIdentitySeedingRunner` (`@Order(2)` in `ui2/service/src/main/java/com/securityexpert/nexus/ui2/service/boot/AiViewIdentitySeedingRunner.java`) idempotently seeds two local identities on startup and reconciles their role bindings on every boot to exact `AIVIEW_ROLE_TOKENS = List.of(RoleToken.VIEWER, RoleToken.REPLAY_VIEWER)`:

1. **`aiview` (interactive / browser session):**
   - Seeded with an initial password from `BootstrapCredentialDefaults` (`mustChangePassword = false`).
   - Used by human operators, Product Owner verification, and browser automation via standard `POST /login` (`LoginFlow`).
   - In `LocalAuthenticationConfiguration.loginFlow`, `role:replay_viewer` is registered in `idleTimeoutExemptRoles`: the 30-minute idle timeout (`IDLE_TIMEOUT`) is waived for `aiview`, while the 10-hour absolute session lifetime (`ABSOLUTE_TIMEOUT`) remains enforced.
2. **`aiview-e2e` (machine session):**
   - Seeded with a random 40-character initial password that is immediately discarded (`aiview-e2e` never authenticates via `/login`).
   - Created via `POST /internal/machine-session` (`MachineSessionController`), which:
     - Verifies that `UI2_E2E_MACHINE_TOKEN_SHA256` is configured on the server and matches the SHA-256 digest of the `X-Nexus-Machine-Token` request header in constant time.
     - Resolves the `aiview-e2e` account (`enabled == true`, `!mustChangePassword()`).
     - Enforces an **exact role-set invariant**: `roles.equals(Set.of(RoleToken.VIEWER, RoleToken.REPLAY_VIEWER))`. If any extra role (such as `role:security_admin`) were bound to `aiview-e2e`, `MachineSessionController` would refuse session creation with `403 MACHINE_IDENTITY_MISCONFIGURED`.
     - Mints a session cookie with `machine = true` via `sessionService.createMachineSession(...)`.

**What `aiview` may do today:**
- Read masked fleet inventory, configuration, compliance, change feed, IP search, path analysis, reports, packet analysis cases, and policy tables.
- Trigger explicitly approved read-only collection/diagnostic POSTs (`GateChain.isReadOnlyPost`):
  - `FMG_DIAGNOSTIC_RUN` (CLASS_0 diagnostic reads, PO decision 2026-10-04)
  - `CP_READINESS_START` and `POLICY_COLLECT` (PO decision 2026-10-04)
  - `PACKET_ANALYSIS_RUN` on cases `aiview` created itself (PO decision 2026-10-18; `PACKET_ANALYSIS_RAW_VIEW` remains `security_admin` only)
  - `DEVICE_INVENTORY_COLLECT` and `SYSTEM_LOG_MEASURE_START` (PO decision 2026-11-05)
  - `DEVICE_CONFIGURATION_COLLECT` and `DISCOVERY_RUN_START` (PO decision 2026-11-11; `GET /discovery/runs/{id}` returns state and counts only, withholding candidate rows)
  - Read-shaped POST queries (`POLICY_READ`, `PATH_ANALYSIS_READ`, `PATH_ANALYSIS_VALIDATE`, `REPORT_READ`, `REPORT_GENERATE`)

### 1.3 `ActionRegistry`, `SecurityWebMvcConfig`, and `GateChain` Rules

Every protected HTTP endpoint in `SecurityWebMvcConfig` is wrapped in a `GateChainInterceptor` bound to an `ActionDescriptor` from `ActionRegistry`:

- **`GateChain.evaluate` (`E1`–`E6`):**
  - **E1 (Authentication & Machine Guard):** Validates the session token. If `session.machine()` is `true`, any HTTP method other than `GET`, `HEAD`, or `isReadOnlyPost(method, action.actionId())` is immediately rejected with `403 MACHINE_SESSION_READ_ONLY`. (`GateChainInterceptor.preHandle` also checks `gateChain.machineWriteRefusal(token, method, action.actionId())` before body hashing.)
  - **E2 (Target Validation):** Rejects unknown targets (`404 TARGET_NOT_FOUND`).
  - **E3 (Action Validation):** Checks `ActionRegistry.findById`.
  - **E4 (RBAC Evaluation):** Evaluates `rbacEvaluator.evaluate(actor, action.requiredRole(), now)`. If denied and `action.alternativeRoles()` is non-empty, iterates through `alternativeRoles()` in order until one permits the actor.
  - **E5 (Concurrency / Single-Flight Window):** Applies to state-changing or device-polling actions (`windowGated = true`). For `aiview` (`isReplayViewer(decision)`: permitted via `RoleToken.REPLAY_VIEWER` and authority `!= RbacEvaluator.ROOT_AUTHORITY`), E5 is enforced when `action.windowGated()` is true, and E6 is bypassed because `aiview` actions are read-only/collection-only.
  - **E6 (Operational Safety Gate):** Enforces confirm tokens, dry-run validation, or dual-control approval tokens for high-impact writes.
- **`GateChainInterceptor.isReplayViewer` (`IS_REPLAY_VIEWER_ATTRIBUTE`):**
  - After `GateChain.evaluate` returns `Allowed`, `GateChainInterceptor` checks whether the actor holds `RoleToken.REPLAY_VIEWER` (cached per `sessionId` for 60 seconds; fails closed toward `replayViewer = true` on lookup error) **and** does not hold root authority (`RbacEvaluator.ROOT_AUTHORITY`, which distinguishes `nexusadmin` from `aiview` because `FirstBootIdentitySeedingRunner` binds `nexusadmin` to all `RoleToken.values()`).
  - When true, `GateChainInterceptor` sets `request.setAttribute("com.securityexpert.nexus.ui2.IS_REPLAY_VIEWER", Boolean.TRUE)`.

### 1.4 Current Administration Plane Inventory (`AdministrationScreen.tsx` & Backend Routes)

`AdministrationScreen.tsx` and `navModel.ts` organize Administration into **7 pages (14 tabs)**. Their current locks and backend gates are:

| Page (`navModel.ts`) | Tab ID | UI Lock Today | Backend Read Route(s) | `ActionRegistry` Descriptor | Required / Alt Roles Today | `aiview` Reachable Today? |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **1. Device registry** (`registry`) | `device-management` | None | `GET /devices` | `DEVICE_READ` | `Optional.empty()` | **Yes** (masked via `TopologyNamePseudonymizer`) |
| | `inventory-exclusions` | None | `GET /devices` | `DEVICE_READ` | `Optional.empty()` | **Yes** (read-only; toggle uses `DEVICE_INVENTORY_SIGNIFICANCE_SET`) |
| | `credentials` | `administratorOnly: true` (`!canReadAdministration`) | `GET /credentials` | `CREDENTIAL_LIST` | `role:security_admin` | **No (403 + UI locked)** |
| | `ssh-trust` | None (writes gated on `canReadAdministration`) | `GET /api/v2/ssh-trust` | `SSH_TRUST_READ` | `Optional.empty()` | **Yes** (hostnames/IPs masked by `PrivacyMaskingResponseBodyAdvice`) |
| **2. Access and identity** (`access`) | `local-identities` | Page `lock: "adminRead"` + tab `administratorOnly` | `GET /local-identities` | `LOCAL_IDENTITY_LIST` | `role:security_admin` | **No (403 + UI locked)** |
| | `sessions` | Page `lock: "adminRead"` + tab `administratorOnly` | `GET /sessions` | `SESSION_REVOKE` *(write action reused for GET)* | `role:security_admin` | **No (403 + UI locked)** |
| | `roles-permissions` | Page `lock: "adminRead"` + tab `administratorOnly` | `GET /roles`<br>`GET /role-bindings` | `RBAC_ROLE_READ`<br>`ROLE_BINDING_CREATE` *(write action reused for GET)* | `role:security_admin` | **No (403 + UI locked)** |
| | `ldap-settings` | Page `lock: "adminRead"` + tab `administratorOnly` | `GET /api/v2/config/ldap` | `LDAP_CONFIG_READ` | `role:security_admin` | **No (403 + UI locked)** |
| **3. Scheduled jobs** (`scheduled-jobs`) | `scheduled-jobs` | None | `GET /api/v2/config/scheduled-jobs` | `SCHEDULED_JOBS_READ` | `Optional.empty()` | **Yes** (`ScheduledJobsController` already masks `changed_by` via `IdentityTokens.user`, nulls `reason`, sets `can_write: false`) |
| **4. Notifications and logging** (`platform`) | `notifications` | Page `lock: "adminRead"` + tab `administratorOnly` | `GET /api/v2/config/notifications` | `NOTIFICATION_CONFIG_READ` | `role:security_admin` | **No (403 + UI locked)** |
| | `system-logs` | Page `lock: "adminRead"` + tab `administratorOnly` | `GET /api/v2/config/system-logs` | `SYSTEM_LOG_SETTINGS_READ` | `role:security_admin` | **No (403 + UI locked)** |
| **5. Platform status** (`platform-status`) | `platform-status` | None | `GET /api/v2/system/pods`<br>`GET /api/v2/system/storage` | `SYSTEM_STATUS_READ` | `Optional.empty()` | **Yes** |
| **6. Audit log** (`audit`) | `audit-logs` | Page `lock: "adminRead"` + tab `administratorOnly` | `GET /audit-logs` | `AUDIT_LOG_READ` | `role:security_admin` | **No (403 + UI locked)** |
| **7. Delivery plan** (`product`) | `delivery-plan` | Page `lock: "adminPlane"` (`!planeGranted("Admin")`) | `GET /project-plan` | `PROJECT_PLAN_READ` | `Optional.empty()` | **Backend Yes / UI Locked** (`PLANE_ADMIN` not granted to `aiview`) |

### 1.5 Current Masking Architecture & Why Generic Advice Is Insufficient for Admin

Under `AGENTS.md`:
- **Sensitive identity reporting law:** Never reproduce serials, management addresses, credentials, usernames/principals, host-key material, or raw device identity values into chat, docs, or screenshots.
- **Post-parsing raw data prohibition:** All UI inspections and agent workflows must operate over deterministic pseudonyms.
- **UNKNOWN / fail-closed law:** Any unverified or free-text field that cannot be safely classified must be withheld rather than passed through raw.

Today, masking relies on three mechanisms:
1. **`PrivacyMaskingResponseBodyAdvice`:** Intercepts controller responses when `IS_REPLAY_VIEWER_ATTRIBUTE` is `true`, sets HTTP header `X-Nexus-Masked: true`, and:
   - Unwraps pre-masked controller envelopes (`IpSearchResponse`, `ChangeFeedResponse`, `PacketDeviceEventsResponse`, `ReportResponse`) or delegates `PolicyResponse` to `PolicyPrivacy.mask(...)`.
   - For all other JSON responses (`Map`, `Collection`, records serialized via Jackson `convertValue`), runs recursive `maskObject`, which dispatches only on device/network map keys (`device_id`, `device_name`, `hostname`, `host_id`, `cluster_name`, `ip`, `management_ip`, `peer_ip`, `src_ip`, `dst_ip`, `zone`, `interface`, `vrf_name`, `virtual_router`) and falls back to `topologyPseudonymizer.maskText(ipMasker.maskText(text))`.
   - **Gap:** `maskObject` does **not** recognize `local_identity_name`, `actor_fingerprint`, `ended_by_actor_fingerprint`, `created_by_actor_fingerprint`, `identity_labels`, `group_reference`, `domainDn`, `adminGroupDn`, `usersGroupDn`, `bindDn`, `trustPem`, `username`, `smtp_from`, `smtp_to`, `recipients`, `actor`, `target`, or `updated_by`. Since usernames (`jdoe`), DNs (`DC=corp,DC=internal`), and group names are neither registered device names nor IPv4/IPv6 literals, `maskObject` would pass them through **unmasked** if those endpoints were simply opened to `aiview`.
2. **`PolicyPrivacy` (`ui2/service/src/main/java/com/securityexpert/nexus/ui2/service/policy/PolicyPrivacy.java`):** Demonstrates fail-closed structural projection: only explicitly allowlisted keys and closed enum vocabularies pass through; free-text comments/descriptions are replaced with `"Withheld in AIView"`.
3. **`IdentityTokens` (`ui2/sanitizer/src/main/java/com/securityexpert/nexus/ui2/sanitizer/core/IdentityTokens.java`) & `ScheduledJobsController`:**
   - `IdentityTokens` computes `HMAC-SHA256(privacyKey, kind + "\u0000" + raw.strip())` using `HmacKeyProvider` (32-byte key from `NEXUS_PRIVACY_HMAC_KEY`, `sanitizer_secret_key` DB row, or ephemeral boot random key) and formats a 10-hex-character token such as `USR-4A9F1C08B2`.
   - `ScheduledJobsController.getScheduledJobs` is the existing reference pattern in Administration: when `isReplayViewer(request)` is true, it sets `can_write = false`, withholds free-text `reason` (`null`), and transforms `changed_by` via `names.identityTokens().user(trimmed)` while leaving `system:*` and `migration:*` literals intact.

---

## 2. Target Architecture

### 2.1 Core Design Principles

1. **Explicit Per-Endpoint Projection, Never Implicit Fallthrough:**
   Every Administration read endpoint opened to `aiview` MUST project its response through a typed, fail-closed server-side projection before returning, wrapped in a pre-masked envelope (`AdminMaskedResponse<T>`) recognized by `PrivacyMaskingResponseBodyAdvice` (just like `ChangeFeedResponse` and `IpSearchResponse`). `PrivacyMaskingResponseBodyAdvice` never guesses which fields in an Administration payload are principals, and never double-masks an already-projected `AdminMaskedResponse`.
2. **Deterministic, Non-Reversible Principal Pseudonyms with Cross-Screen Join Consistency:**
   A given underlying principal (whether referenced as a `local_identity_name`, a `local:<uuid>` or directory `actor_fingerprint`, a `role_binding.group_reference`, an `ldap.adminGroupDn`, or an `audit_log.actor`) MUST resolve to the **exact same pseudonym token** across all Administration screens within a session window so agents can verify relational integrity (e.g., "User `USR-7F3A91C04B` is enabled in Local Identities, holds `role:operator` in Role Bindings, has 2 active rows in Sessions, and appears as `actor` in Audit Log").
3. **Server-Authoritative `can_write: false` Affordances:**
   The frontend (`AdministrationScreen.tsx` and child panels) must never infer write capability from role names. Every Administration read payload (or `/session/status`) supplies explicit boolean capability flags (`can_read_administration: true`, `can_write_administration: false`, plus per-resource `can_write: false` / `can_revoke: false`), causing all mutating buttons, forms, dialogs, and toggles to render disabled/hidden in `aiview` mode while keeping read tables, badges, filters, and counters visible.
4. **Triple-Layer Write Prohibition:**
   No write, approve, revoke, password/secret mutation, connection test, or raw export action is reachable by `aiview`:
   - **Layer 1 (`GateChain` E1):** Machine sessions (`aiview-e2e`) are blocked on all non-GET/HEAD requests not in `GateChain.isReadOnlyPost` (`403 MACHINE_SESSION_READ_ONLY`). No Administration write or test-POST is ever added to `isReadOnlyPost`.
   - **Layer 2 (`GateChain` E4 / `ActionRegistry`):** Read and write action descriptors are strictly separated. `RoleToken.REPLAY_VIEWER` is added to `alternativeRoles` **only** on read `ActionDescriptor`s; every write/revoke/test `ActionDescriptor` remains `role:security_admin` (or `role:operation_admin` / `role:onboarding_admin`) only.
   - **Layer 3 (Controller Defense-in-Depth):** Every Administration mutating controller method explicitly checks `if (isReplayViewer(httpRequest)) return ResponseEntity.status(403)...` at entry, so even a hypothetical RBAC misconfiguration cannot allow a `role:replay_viewer` session to mutate state.

### 2.2 ActionRegistry & Session Status Changes

#### 2.2.1 Decoupling Coupled Read/Write Routes

Two read endpoints currently share write `ActionDescriptor`s in `SecurityWebMvcConfig` and must be split in `ActionRegistry`:

| Endpoint | Current `ActionDescriptor` | Target `ActionDescriptor` | Required Role | Alternative Roles |
| :--- | :--- | :--- | :--- | :--- |
| `GET /sessions` | `SESSION_REVOKE` | **`SESSION_READ`** *(new)* | `role:security_admin` | `List.of(RoleToken.REPLAY_VIEWER)` |
| `POST /sessions/revoke` | `SESSION_REVOKE` | `SESSION_REVOKE` *(unchanged)* | `role:security_admin` | `List.of()` *(none)* |
| `GET /role-bindings` | `ROLE_BINDING_CREATE` | **`ROLE_BINDING_READ`** *(new)* | `role:security_admin` | `List.of(RoleToken.REPLAY_VIEWER)` |
| `POST /role-bindings` | `ROLE_BINDING_CREATE` | `ROLE_BINDING_CREATE` *(unchanged)* | `role:security_admin` | `List.of()` *(none)* |

#### 2.2.2 Administration Read Actions Gaining `RoleToken.REPLAY_VIEWER`

The following existing read-only `ActionDescriptor`s in `ActionRegistry` gain `RoleToken.REPLAY_VIEWER` in `alternativeRoles()` (with `windowGated = false`, `requiresConfirmToken = false`, `requiresApprovalToken = false`):

1. `LOCAL_IDENTITY_LIST` (`GET /local-identities`)
2. `SESSION_READ` (`GET /sessions`)
3. `RBAC_ROLE_READ` (`GET /roles`)
4. `ROLE_BINDING_READ` (`GET /role-bindings`)
5. `LDAP_CONFIG_READ` (`GET /api/v2/config/ldap`)
6. `CREDENTIAL_LIST` (`GET /credentials`) — *posture-only projection; see §2.4.6 and PO Question Q1*
7. `NOTIFICATION_CONFIG_READ` (`GET /api/v2/config/notifications`)
8. `SYSTEM_LOG_SETTINGS_READ` (`GET /api/v2/config/system-logs`)
9. `AUDIT_LOG_READ` (`GET /audit-logs`)

Already open to `aiview` (`Optional.empty()` in `ActionRegistry`):
- `DEVICE_READ` (`GET /devices`)
- `SSH_TRUST_READ` (`GET /api/v2/ssh-trust`)
- `SCHEDULED_JOBS_READ` (`GET /api/v2/config/scheduled-jobs`)
- `SYSTEM_STATUS_READ` (`GET /api/v2/system/pods`, `GET /api/v2/system/storage`)
- `PROJECT_PLAN_READ` (`GET /project-plan`)

#### 2.2.3 `SessionStatusController` & Frontend Navigation Unlocks

In `SessionStatusController` (`GET /session/status`):
- `can_read_administration`: becomes `true` when `SECURITY_ADMIN` **or** `REPLAY_VIEWER` is permitted.
- **`can_write_administration`** *(new boolean field in `/session/status`)*: `true` only when `SECURITY_ADMIN` is permitted **and** `!isReplayViewer`.
- `permissions`: `Admin` plane is added to `collectPlanes` when `REPLAY_VIEWER` is permitted (or `navModel.ts` unlocks `product` / `delivery-plan` via `can_read_administration`), allowing `aiview` to navigate to all 7 Administration pages in `AdministrationScreen.tsx` while all panels bind their mutating controls to `can_write_administration` / per-payload `can_write`.

### 2.3 Canonical Principal Pseudonymization (`AdminPrincipalPseudonymizer`)

#### 2.3.1 Keying & Session-Window Determinism

To satisfy both **"deterministic across screens within a session window"** and **"non-reversible against offline dictionary guessing of corporate usernames"**, `AdminPrincipalPseudonymizer` builds on `HmacKeyProvider` (`IdentityTokens`) with explicit domain separation and canonical principal resolution:

```text
token(prefix, canonicalValue) = prefix + "-" + UpperHex(HMAC-SHA256(key, domain + "\0" + canonicalValue))[0..10]
```

**Key scoping options (see §4.3 PO Question Q2):**
- **Mode A — Process/Privacy-Key Scoped (matches existing `IdentityTokens.user()` in `ScheduledJobsController`, `ChangeFeedService`, and `SystemLogQueryService`):**
  Uses the 256-bit `HmacKeyProvider` secret directly (`domain = "admin-principal:v1:" + prefix`). Because `ScheduledJobsController` (`GET /api/v2/config/scheduled-jobs`), `ChangeFeedService` (`GET /api/v2/changes`), and `SystemLogQueryService` (`GET /api/v2/system-logs`) already use `IdentityTokens.user(raw)` (`HMAC-SHA256(key, "user\0" + raw)`), using `IdentityTokens.user(canonicalUser)` for user principals guarantees that `USR-XXXXXXXXXX` in Administration **also** joins cleanly with `changed_by` in Scheduled Jobs and `actor` in Change Feed / System Logs across the entire session.
- **Mode B — Session-Window Epoch Scoped:**
  Derives a window key `K_window = HMAC-SHA256(privacyKey, "epoch:" + floor(sessionCreatedAt / windowDuration))` (where `windowDuration` equals the 10-hour `ABSOLUTE_TIMEOUT` or a daily UTC epoch). All requests within the session window produce identical tokens across all screens, while tokens automatically rotate across windows.

#### 2.3.2 Cross-Screen Principal Canonicalization Rules

A critical subtlety discovered during the codebase audit is that different tables store references to the **same user or group** in different formats:
- `local_identity` stores `local_identity_id` (UUID string, e.g. `"8b5e..."`) and `local_identity_name` (e.g. `"alice"`).
- `LoginFlow` mints local sessions with `actor_fingerprint = "local:" + identity.localIdentityId()`.
- `SessionAdminController.list` returns `actor_fingerprint = "local:8b5e..."` and populates `identity_labels.put(sessionId, "alice")` by looking up `localIdentityStore.findById("8b5e...")`.
- `RoleBindingAdminService` stores local user role bindings with `binding_kind = "LEGACY"`, `directory_profile_id = "local"`, and `group_reference = localIdentityId` (`"8b5e..."`), whereas directory group bindings store `binding_kind = "DIRECTORY_GROUP"` and `group_reference = groupDn` (e.g. `"CN=NetOps,OU=Groups,DC=example,DC=internal"`).
- `AuditLogQueryService` and `ScheduledJobsController` record `actor` / `changed_by` as either an `actor_fingerprint` (`"local:8b5e..."`, `"ldap:..."`), a username, or a system literal (`"system:ldap-settings"`, `"bootstrap:first-boot"`).

If the pseudonymizer hashed raw strings without canonicalizing local identity references first, `"alice"` in `GET /local-identities`, `"8b5e..."` in `GET /role-bindings`, and `"local:8b5e..."` in `GET /sessions` would hash to **three different `USR-` tokens**, breaking cross-screen verification in `LocalIdentitiesPanel` (which joins `local_identity_id` to `role_bindings.group_reference` to render each user's assigned role chips!) and `SessionsPanel`.

Therefore, `AdminPrincipalPseudonymizer` applies the following **Canonical Principal Resolution** before hashing:

| Entity / Concept | Canonical Input Before HMAC | Output Token Format | Cross-Screen Join Guarantee |
| :--- | :--- | :--- | :--- |
| **System / Bootstrap Actor Literal** | Exact allowlist match: `system:*`, `migration:*`, `bootstrap:*`, `anonymous`, `unknown` | Preserved verbatim (e.g. `system:ldap-settings`, `bootstrap:first-boot`) | Distinguishes automated platform actions from human principals without leaking identities. |
| **Local Identity ID (`local_identity_id`)** | `"local-id:" + lower(local_identity_id.strip())` | `LID-<10-hex>` (or opaque UUID used as join key within Access panels) | `GET /local-identities` `local_identity_id` and `GET /role-bindings` `group_reference` (when `directory_profile_id == "local"`) both map through the same `localIdentityId` token so `LocalIdentitiesPanel` role badges join 1:1. |
| **Human / Service User Principal (`USR`)** | Resolves `local:<id>` or raw `<id>` via `LocalIdentityStore` to canonical `lower(local_identity_name)`; for directory principals, `lower(dnOrPrincipal.strip())` | `USR-<10-hex>` | `GET /local-identities` (`local_identity_name`), `GET /sessions` (`actor_fingerprint`, `ended_by_actor_fingerprint`, `identity_labels[sid]`), `GET /role-bindings` (`created_by_actor_fingerprint`), `GET /audit-logs` (`actor`), `GET /api/v2/config/scheduled-jobs` (`changed_by`), and `GET /api/v2/config/system-logs` (`updated_by`) all emit the **exact same `USR-<10-hex>`** for the same user. |
| **Directory Group DN (`GRP`)** | Normalized RFC 4514 DN (`lower(dn.strip())` with RDN whitespace normalized) | `GRP-<10-hex>` | `GET /api/v2/config/ldap` (`adminGroupDn`, `usersGroupDn`) and `GET /role-bindings` (`group_reference` when `binding_kind == "DIRECTORY_GROUP"`) emit the **exact same `GRP-<10-hex>`**. |
| **Directory Base / Bind DN (`DN`)** | Normalized RFC 4514 DN (`lower(dn.strip())`) | `DN-<10-hex>` | Masks `domainDn` and `bindDn` in `GET /api/v2/config/ldap`. |
| **Session Identifier (`SESS`)** | `"sess:" + session_id` | `SESS-<10-hex>` | `GET /sessions` `sessions[].session_id` and `identity_labels` map keys both map to `SESS-<10-hex>`. |
| **Credential Profile (`CRED`)** | `"cred:" + credential_id` | `CRED-<10-hex>` | Masks `display_name` in `GET /credentials`; `username` is masked as `USR-<10-hex>`. |
| **Notification Address (`MAIL`)** | `"mail:" + lower(address.strip())` | `MAIL-<10-hex>` | Masks `smtp_from`, `smtp_to`, and each comma-split entry in `routes[].recipients` in `GET /api/v2/config/notifications`. |
| **Custom Role Name / Description (`ROLE`)** | System roles (`is_system == true`) keep their closed product names (`Viewer`, `Security Admin`, etc.); custom roles (`is_system == false`) map name to `ROLE-<10-hex>` and description to `"Withheld in AIView"` | `ROLE-<10-hex>` | Prevents custom role names/descriptions containing corporate team or department names from leaking. |

---

### 2.4 Endpoint-by-Endpoint and Field-by-Field Projection Specification

#### 2.4.1 `GET /local-identities` (`LocalIdentityAdministrationController`)
- **Action Descriptor:** `LOCAL_IDENTITY_LIST` (`role:security_admin`, alt: `role:replay_viewer`)
- **Ordering Control:** For `aiview`, rows are sorted by `(created_at ASC, masked_local_identity_id ASC)` rather than raw `local_identity_name` so alphabetical ordering does not leak the lexicographical position of real usernames.
- **Field Projection:**

| JSON Field | Type | `aiview` Projection | Rationale |
| :--- | :--- | :--- | :--- |
| `can_write` *(new)* | `boolean` | `false` (`true` for `security_admin`) | Disables "Create user", "Enable/Disable", "Reset password", and "Assign roles" actions in `LocalIdentitiesPanel.tsx`. |
| `identities[].local_identity_id` | `string` | `LID-<10-hex>` (deterministic HMAC of UUID) | Matches `group_reference` on `LEGACY`/`local` role bindings so role chips render accurately. |
| `identities[].local_identity_name` | `string` | `USR-<10-hex>` | Deterministic user pseudonym. (See PO Question Q3 regarding whether `aiview` / `aiview-e2e` / `nexusadmin` keep literal names or become `USR-`.) |
| `identities[].enabled` | `boolean` | Real value | Operational state verification. |
| `identities[].must_change_password` | `boolean` | Real value | Operational state verification. |
| `identities[].created_at` | `string` (ISO-8601) | Real value | Timestamp verification. |
| `identities[].password_set_at` | `string` (ISO-8601) | Real value | Timestamp verification. |

*(Note: Password verifiers, salts, and iteration counts in `local_identity` are already excluded from `LocalIdentityView` and never leave the store.)*

#### 2.4.2 `GET /sessions` (`SessionAdminController`)
- **Action Descriptor:** `SESSION_READ` (`role:security_admin`, alt: `role:replay_viewer`)
- **Field Projection:**

| JSON Field | Type | `aiview` Projection | Rationale |
| :--- | :--- | :--- | :--- |
| `can_write` / `can_revoke` *(new)* | `boolean` | `false` | Hides/disables the "Revoke" button in `SessionsPanel.tsx`. |
| `sessions[].session_id` | `string` | `SESS-<10-hex>` | Replaces the raw SHA-256 session token digest with a compact session pseudonym. |
| `sessions[].actor_fingerprint` | `string` | `USR-<10-hex>` (resolved through canonical user rules) | Preserves per-user session grouping (`actorSessionCounts` in `SessionsPanel.tsx`) and joins to `GET /local-identities` and `GET /audit-logs`. |
| `sessions[].created_at` | `string` | Real value | Session age verification. |
| `sessions[].last_seen_at` | `string` | Real value | Idle/activity verification. |
| `sessions[].expires_at` | `string` | Real value | Expiration verification. |
| `sessions[].revoked_at` | `string \| null` | Real value | Revocation state verification. |
| `sessions[].ended_reason` | `string \| null` | Real enum (`LOGOUT`, `REVOKED`, `IDLE_TIMEOUT`, `ABSOLUTE_TIMEOUT`, etc.) | Closed-vocabulary lifecycle state. |
| `sessions[].ended_by_actor_fingerprint` | `string \| null` | `USR-<10-hex>` or `system:*` or `null` | Masks the revoking administrator's identity. |
| `identity_labels` | `Map<String, String>` | Keys mapped to `SESS-<10-hex>`; values mapped to matching `USR-<10-hex>` | `SessionsPanel.tsx` displays `identity_labels[s.session_id] ?? s.actor_fingerprint` cleanly as `USR-<10-hex>`. |

#### 2.4.3 `GET /roles` (`RbacRoleController`)
- **Action Descriptor:** `RBAC_ROLE_READ` (`role:security_admin`, alt: `role:replay_viewer`)
- **Field Projection:**

| JSON Field | Type | `aiview` Projection | Rationale |
| :--- | :--- | :--- | :--- |
| `id` | `string` (UUID) | Real UUID | Opaque generated UUID. |
| `name` | `string` | Real name if `is_system == true`; `ROLE-<10-hex>` if `is_system == false` | Built-in role names (`Viewer`, `Security Admin`, etc.) are product constants; custom role names may contain org/team names. |
| `token_string` | `string` | Real value if `is_system == true`; `role:custom_<10-hex>` if `is_system == false` | Custom role tokens are derived from custom role names (`RbacRoleController.createRole`). |
| `description` | `string` | Real description if `is_system == true`; `"Withheld in AIView"` if `is_system == false` | Custom role descriptions are operator-entered free text. |
| `is_system` | `boolean` | Real value | Distinguishes built-in vs custom roles in `CustomRolesPanel.tsx`. |
| `permissions` | `Map<String, String>` | Real map (`plane -> "none" \| "read" \| "write"`) | Closed vocabulary (`Admin`, `Compliance`, `Config`, `Devices`, `Operations`, `PacketAnalysis`, `Policy`, `Sanitizer`). |
| `created_at` / `updated_at` | `string` | Real value | Timestamps. |

#### 2.4.4 `GET /role-bindings` (`RoleBindingAdminController`)
- **Action Descriptor:** `ROLE_BINDING_READ` (`role:security_admin`, alt: `role:replay_viewer`)
- **Field Projection:**

| JSON Field | Type | `aiview` Projection | Rationale |
| :--- | :--- | :--- | :--- |
| `can_write` *(new)* | `boolean` | `false` | Disables binding creation/deletion in `LocalIdentitiesPanel.tsx` and `CustomRolesPanel.tsx`. |
| `bindings[].binding_id` | `string` | Real UUID (or `BND-<10-hex>` if natural/composite) | Opaque binding identifier. |
| `bindings[].role_token` | `string` | Closed `RoleToken` wire value (e.g. `role:security_admin`) | Closed product vocabulary. |
| `bindings[].binding_kind` | `string` | Real enum (`DIRECTORY_GROUP`, `DIRECTORY_PRINCIPAL`, `LEGACY`) | Closed enum vocabulary. |
| `bindings[].directory_profile_id` | `string` | `"local"` or `"default"` preserved; custom profile IDs -> `DIR-<10-hex>` | Preserves `"local"` join check in `LocalIdentitiesPanel.tsx`. |
| `bindings[].group_reference` | `string` | If `directory_profile_id == "local"` -> `LID-<10-hex>` (matching `local_identity_id`); if `DIRECTORY_GROUP` -> `GRP-<10-hex>` (matching `ldap.adminGroupDn` / `usersGroupDn`); if `DIRECTORY_PRINCIPAL` -> `USR-<10-hex>` | Preserves both local-user role badge joins and LDAP group binding joins without exposing raw DNs or usernames. |
| `bindings[].created_at` | `string` | Real value | Timestamp. |
| `bindings[].created_by_actor_fingerprint` | `string` | `USR-<10-hex>` (or `system:*` / `bootstrap:*` verbatim) | Masks the admin who created the binding. |

#### 2.4.5 `GET /api/v2/config/ldap` (`DirectorySettingsController`)
- **Action Descriptor:** `LDAP_CONFIG_READ` (`role:security_admin`, alt: `role:replay_viewer`)
- **Field Projection:**

| JSON Field | Type | `aiview` Projection | Rationale |
| :--- | :--- | :--- | :--- |
| `can_write` *(new)* | `boolean` | `false` | Disables form inputs, "Save settings", and "Test connection" in `DirectorySettingsPanel.tsx`. |
| `configured` | `boolean` | Real value | Posture check. |
| `enabled` | `boolean` | Real value | Posture check. |
| `serverType` | `string` | Real enum (`ACTIVE_DIRECTORY`, `OPENLDAP`, etc.) | Closed vendor/protocol enum. |
| `servers` | `List<String>` | Each entry masked via `SubnetPreservingIpMasker` (if IP) or `TopologyNamePseudonymizer` (`DIR-SRV-<n>.example.internal` / `192.0.2.x`) | Never expose internal AD domain controller hostnames or private IPs. |
| `port` | `int` | Real value (e.g. `389`, `636`) | Protocol port number. |
| `domainDn` | `string` | `DN-<10-hex>` (or `""` if blank) | Masks corporate base DN. |
| `adminGroupDn` | `string` | `GRP-<10-hex>` (or `""` if blank) | Matches `group_reference` in `GET /role-bindings`. |
| `usersGroupDn` | `string` | `GRP-<10-hex>` (or `""` if blank) | Matches `group_reference` in `GET /role-bindings`. |
| `bindDn` | `string` | `DN-<10-hex>` (or `""` if blank) | Masks service-account bind DN. |
| `bindPasswordSet` | `boolean` | Real boolean | Confirms whether bind secret is stored without revealing secret material. |
| `connectTimeoutSeconds` | `int` | Real value | Numeric timeout setting. |
| `ldaps` | `boolean` | Real value | TLS posture boolean. |
| `trustMode` | `string` | Real enum (`JVM_DEFAULT`, `PINNED_PEM`) | TLS trust posture enum. |
| `trustPem` | `string` | `""` if empty; `"[CERTIFICATE WITHHELD IN AIVIEW — SHA256:<10-hex>]"` if non-empty | Raw X.509 PEM certificates contain internal CA Issuer/Subject DNs and SAN hostnames; withholding the PEM body prevents certificate-based corporate identity leaks while proving a pinned cert is present. |
| `updatedAt` | `string \| null` | Real value | Timestamp. |
| `adminGroupRoles` | `List<String>` | Real closed `RoleToken` list | Role mapping verification. |
| `usersGroupRoles` | `List<String>` | Real closed `RoleToken` list | Role mapping verification. |

#### 2.4.6 `GET /credentials` (`CredentialAdministrationController`)
- **Action Descriptor:** `CREDENTIAL_LIST` (`role:security_admin`, alt: `role:replay_viewer` — *see PO Question Q1*)
- **Ordering Control:** Sorted by `(created_at ASC, masked_credential_id ASC)` for `aiview`.
- **Field Projection (Posture-Only Masked Projection):**

| JSON Field | Type | `aiview` Projection | Rationale |
| :--- | :--- | :--- | :--- |
| `can_write` *(new)* | `boolean` | `false` | Disables "Add credential", "Rotate secret", and "Delete" in `CredentialsPanel.tsx`. |
| `credentials[].credential_id` | `string` | Real UUID (or `CRD-<10-hex>`) | Opaque identifier. |
| `credentials[].credential_reference_id` | `string` | `CREF-<10-hex>` | Masks human-chosen reference slugs. |
| `credentials[].display_name` | `string` | `CRED-<10-hex>` | Replaces operator-named credential labels (which often embed site, vendor, or account names). |
| `credentials[].kind` | `string` | Real enum (`SSH_PASSWORD`, `SSH_PRIVATE_KEY`, `API_TOKEN`, `SNMP_V2C`, `SNMP_V3`) | Closed credential kind vocabulary. |
| `credentials[].username` | `string \| null` | `USR-<10-hex>` if non-blank; `null` if blank | Masks device/API login usernames while proving whether a username is configured. |
| `credentials[].created_at` | `string` | Real value | Timestamp. |
| `credentials[].secret_set_at` | `string` | Real value | Proves secret rotation timestamp without exposing secret material. |
| `credentials[].snmp_security_level` | `string \| null` | Real enum (`NO_AUTH_NO_PRIV`, `AUTH_NO_PRIV`, `AUTH_PRIV`) | Closed SNMPv3 posture enum. |
| `credentials[].snmp_auth_protocol` | `string \| null` | Real enum (`SHA`, `SHA224`, `SHA256`, `SHA384`, `SHA512`, `MD5`) | Closed SNMPv3 posture enum. |
| `credentials[].snmp_priv_protocol` | `string \| null` | Real enum (`AES128`, `AES192`, `AES256`, `DES`) | Closed SNMPv3 posture enum. |
| `credentials[].snmp_context_name` | `string \| null` | `"CTX-" + <10-hex>` if non-blank; `null` if blank | SNMP context names can reflect internal VRF/tenant names. |

*(Note: Encrypted secret ciphertext, nonces, key versions, private keys, API tokens, and SNMP community/auth/priv secrets live only in `CredentialRecord` in the database and are never present on `CredentialMetadataView`.)*

#### 2.4.7 `GET /api/v2/config/notifications` (`NotificationSettingsController`)
- **Action Descriptor:** `NOTIFICATION_CONFIG_READ` (`role:security_admin`, alt: `role:replay_viewer`)
- **Field Projection:**

| JSON Field | Type | `aiview` Projection | Rationale |
| :--- | :--- | :--- | :--- |
| `can_write` *(new)* | `boolean` | `false` | Disables Save and Test buttons in `NotificationSettingsPanel.tsx`. |
| `notification_identity` | `string` | Masked via `TopologyNamePseudonymizer` (or `NTF-ID-<10-hex>`) | Masks instance sender label if customized to customer name. |
| `syslog_enabled` | `boolean` | Real value | Posture boolean. |
| `syslog_host` | `string` | Masked IP (`192.0.2.x`) or masked hostname via `TopologyNamePseudonymizer` (`""` if blank) | Masks SIEM / syslog collector endpoint. |
| `syslog_port` | `int` | Real value | Port number. |
| `syslog_protocol` | `string` | Real enum (`UDP`, `TCP`, `TLS`) | Protocol enum. |
| `smtp_enabled` | `boolean` | Real value | Posture boolean. |
| `smtp_host` | `string` | Masked IP (`192.0.2.x`) or masked hostname (`""` if blank) | Masks internal mail relay hostname/IP. |
| `smtp_port` | `int` | Real value | Port number. |
| `smtp_tls` | `boolean` | Real value | TLS boolean. |
| `smtp_from` | `string` | `MAIL-<10-hex>` (or `""` if blank) | Masks sender address without using `@` domain syntax. |
| `smtp_to` | `string` | Comma-separated `MAIL-<10-hex>` per recipient (or `""` if blank) | Masks recipient addresses while preserving recipient count. |
| `routes[].category` | `string` | Real enum (`CRITICAL_ALERTS`, `COMPLIANCE_DRIFT`, `BACKUP_FAILURES`, `SYSTEM_HEALTH`) | Closed category enum (`NotificationSettings.CATEGORIES`). |
| `routes[].email_enabled` / `syslog_enabled` / `webui_enabled` | `boolean` | Real values | Routing matrix booleans. |
| `routes[].recipients` | `string` | Comma-separated `MAIL-<10-hex>` per recipient (or `""` if blank) | Masks per-route recipient overrides while preserving count and cross-route recipient equality. |
| `routes[].last_sent_at` | `string \| null` | Real value | Delivery timestamp. |
| `routes[].last_error` | `string \| null` | `null` if null; `"DELIVERY_ERROR_WITHHELD_IN_AIVIEW"` if non-null | SMTP/Syslog exception messages frequently echo raw server hostnames, IP addresses, or rejected recipient addresses. |

#### 2.4.8 `GET /api/v2/config/system-logs` (`SystemLogSettingsController`)
- **Action Descriptor:** `SYSTEM_LOG_SETTINGS_READ` (`role:security_admin`, alt: `role:replay_viewer`)
- **Field Projection:**
  - `SystemLogSettingsController.read` **already** computes `can_write = !isReplayViewer(request) && hasWriteRole(...)`.
  - All numeric retention/limit fields (`retention_days`, `max_total_rows`, `poll_interval_seconds`, `max_events_per_device_poll`, `PerVendorFetchLimit`, `bounds`) and `enabled` are safe operational integers/booleans and remain real.
  - **Required Fix:** `updated_by` (currently passed raw from `settings.updatedBy()`, which stores `session.actorFingerprint()`) MUST be projected through `AdminPrincipalPseudonymizer.user(settings.updatedBy())` -> `USR-<10-hex>` (preserving `system:*` / `migration:*`).

#### 2.4.9 `GET /audit-logs` (`AuditLogController` & `AuditLogQueryService`)
- **Action Descriptor:** `AUDIT_LOG_READ` (`role:security_admin`, alt: `role:replay_viewer`)
- **Current Architecture Note:** `AuditLogQueryService` queries `audit_log` (`audit_event_id`, `occurred_at`, `actor`, `action_id`, `table_name`, `row_pk`, `operation`) and maps rows through `AuditPresentationAllowlist`. It **never** selects `before_json` or `after_json`.
- **Gap in Current Implementation:** `AuditLogQueryService.listRecent` passes `actor` (`rs.getString("actor")`) and `row_pk` (`rs.getString("row_pk")`) directly into `AuditPresentationAllowlist.project(...)` without masking. In `audit_log`, `actor` holds usernames or actor fingerprints, and `row_pk` for tables like `local_identity`, `role_binding`, or `devices` can hold identity keys or device IDs.
- **Field Projection for `aiview`:**

| JSON Field | Type | `aiview` Projection | Rationale |
| :--- | :--- | :--- | :--- |
| `id` | `string` | Real `audit_event_id` (UUID / sequence ID) | Opaque event ID. |
| `occurred_at` | `string` | Real ISO-8601 timestamp | Event chronology. |
| `actor` | `string` | `USR-<10-hex>` via canonical user resolver (`system:*`, `migration:*`, `bootstrap:*` kept verbatim) | Joins with `GET /sessions`, `GET /local-identities`, and `GET /role-bindings` while hiding real operator identities. |
| `action` | `string` | Real `action_id` (or `table_name:operation` fallback — both closed vocabularies) | Closed action/schema vocabulary. |
| `outcome` | `string` | Real operation code (`INSERT`, `UPDATE`, `DELETE`, `PERMITTED`, `DENIED`) | Closed outcome vocabulary. |
| `target` | `string` | Table-aware projection of `(table_name, row_pk)`:<br>- If `table_name == "devices"` -> `TopologyNamePseudonymizer` pseudonym<br>- If `table_name == "local_identity"` -> `LID-<10-hex>`<br>- If `row_pk` matches a pure UUID -> preserved UUID<br>- Otherwise -> `TGT-<10-hex>` | Prevents composite keys, natural keys, or embedded usernames/DNs in `row_pk` from leaking in the `target` column. |
| `total` / `limit` / `offset` | `int` | Real values | Pagination verification. |

- **Query Filter Support (`?actor=`):** If an `aiview` session filters `GET /audit-logs?actor=USR-4A9F1C08B2`, the controller filters the projected stream by matching the masked `USR-` token (never allowing a raw username probe oracle; see §3.1).

#### 2.4.10 Already-Reachable Administration Endpoints (Hardening Review)

| Endpoint | Current Status for `aiview` | Required Hardening |
| :--- | :--- | :--- |
| `GET /devices` (`DeviceRegistryPanel`) | Open (`DEVICE_READ`); masked by `TopologyNamePseudonymizer` & `SubnetPreservingIpMasker` | Keep read-only; ensure `DeviceRegistryPanel.tsx` hides/disables Register, Edit, Delete, and Significance toggles when `!can_write_administration`. |
| `GET /api/v2/ssh-trust` (`SshHostKeyPanel`) | Open (`SSH_TRUST_READ`); hostnames/IPs masked by `PrivacyMaskingResponseBodyAdvice` | Add explicit `can_write: false` to response and wrap in `AdminMaskedResponse`; ensure `fingerprint` (`SHA256:...` host key hash) is either tokenized (`FP-<10-hex>`) or verified compliant with `AGENTS.md` ("do not unnecessarily reproduce ... host-key material"). |
| `GET /api/v2/config/scheduled-jobs` (`ScheduledJobsPanel`) | Open (`SCHEDULED_JOBS_READ`); `changed_by` masked via `IdentityTokens.user`, `reason` nulled, `can_write: false` | Align `changed_by` with `AdminPrincipalPseudonymizer.user(...)` so `local:<uuid>` fingerprints resolve to the same `USR-<10-hex>` as `GET /local-identities`. |
| `GET /api/v2/system/pods` & `/storage` (`PlatformStatusPanel`) | Open (`SYSTEM_STATUS_READ`) | Pod names are closed service role names (`ui2-service`, `postgres`, etc.); storage figures are numeric bytes. No human principals present. |
| `GET /project-plan` (`DeliveryPlanPanel`) | Open at backend (`PROJECT_PLAN_READ`), locked in UI by `PLANE_ADMIN` | Grant `Admin` plane read visibility (or gate `product` page on `can_read_administration`) so `aiview` can verify Delivery Plan rendering. |

---

### 2.5 Strictly Forbidden Endpoints & Data (Never Reachable by `aiview`)

The following endpoints and data classes MUST remain **100% forbidden (`403 Forbidden`)** to both interactive `aiview` and machine `aiview-e2e` sessions:

1. **Identity & RBAC Mutations:**
   - `POST /local-identities` (`LOCAL_IDENTITY_CREATE`)
   - `POST /local-identities/{id}/enable` & `/disable` (`LOCAL_IDENTITY_ENABLE`, `LOCAL_IDENTITY_DISABLE`)
   - `POST /local-identities/{id}/password` (`LOCAL_IDENTITY_SET_PASSWORD`)
   - `POST /sessions/revoke` (`SESSION_REVOKE`)
   - `POST /role-bindings` (`ROLE_BINDING_CREATE`)
   - `POST /role-bindings/{id}/delete` (`ROLE_BINDING_DELETE`)
   - `POST /roles`, `PUT /roles/{id}`, `DELETE /roles/{id}` (`RBAC_ROLE_WRITE`)
2. **Directory / LDAP Mutations & Active Network Probes:**
   - `PUT /api/v2/config/ldap` (`LDAP_CONFIG_WRITE`)
   - `POST /api/v2/config/ldap/test` (`LDAP_CONFIG_TEST` — **must stay forbidden** even as a test action because its request body accepts an arbitrary `servers` list and `bindDn`/`bindPassword`, which could otherwise be abused as an SSRF / credential-oracle probe from `aiview`).
3. **Credential Vault Mutations & Secret Material:**
   - `POST /credentials` (`CREDENTIAL_CREATE`)
   - `POST /credentials/{id}/secret` (`CREDENTIAL_REPLACE_SECRET`)
   - `POST /credentials/{id}/delete` (`CREDENTIAL_DELETE`)
   - Any raw secret, password verifier, salt, private key, API token, SNMP community string, SNMP auth/priv secret, or LDAP bind password.
4. **Platform Settings, Trust & Schedule Mutations:**
   - `PUT /api/v2/config/notifications` & `POST /api/v2/config/notifications/test` (`NOTIFICATION_CONFIG_WRITE`, `NOTIFICATION_TEST_SEND` — test-send triggers external network traffic to SMTP/Syslog and must stay forbidden to `aiview`).
   - `PUT /api/v2/config/system-logs` (`SYSTEM_LOG_SETTINGS_WRITE`)
   - `PUT /api/v2/config/scheduled-jobs/{family}` (`SCHEDULED_JOBS_WRITE`)
   - `PUT /api/v2/ssh-trust/mode`, `POST /api/v2/ssh-trust/{id}/accept`, `POST /api/v2/ssh-trust/{id}/reject` (`SSH_TRUST_MANAGE`)
   - `POST /api/v2/tls-certificate/import` & `GET /api/v2/tls-certificate` (raw PEM material)
5. **Forensic / Raw Unmasked Exports & Audit Snapshots:**
   - `audit_log.before_json` and `audit_log.after_json` (never queried or exposed).
   - `PACKET_ANALYSIS_RAW_VIEW` (remains `role:security_admin` only).
   - Unmasked configuration text (`DEVICE_CONFIGURATION_TEXT_READ`, `role:security_admin` only).

---

## 3. Abuse Cases, Controls & Canary Principal Verification

### 3.1 Threat / Abuse Case Matrix

| # | Abuse Case / Re-Identification Vector | Attack Mechanics | Architectural Control |
| :--- | :--- | :--- | :--- |
| **A1** | **Offline dictionary / rainbow-table attack on `USR-` or `GRP-` tokens** | An attacker with a list of corporate usernames (`jdoe`, `asmith`) computes `SHA-256(username)` to reverse `USR-<10-hex>`. | Tokens are computed with **HMAC-SHA256** under the server-held 256-bit `HmacKeyProvider` secret key (never exposed to any API or client), with domain-separation prefixes (`"user\0"`, `"grp\0"`). Without the server secret, offline brute-forcing is infeasible. |
| **A2** | **Probe-oracle attack via filter parameters (`?actor=jdoe`)** | An `aiview` caller passes a guessed raw username `?actor=jdoe` to `GET /audit-logs` and observes whether rows are returned, using the filter as an existence oracle. | In `aiview` mode, filter parameters on principal fields (`actor`, `search`) are evaluated **only against the post-masked `USR-` / `GRP-` / `LID-` token** (or rejected if not matching the token regex `^(USR\|GRP\|LID\|SESS\|system\|bootstrap)-[A-Z0-9:_-]+$`). Passing a raw username never matches raw DB columns for `aiview`. |
| **A3** | **Lexicographical ordering leak** | `GET /local-identities` or `GET /credentials` sorts rows `ORDER BY local_identity_name ASC` in SQL; an observer infers that row 1 starts with `a` and row 20 starts with `z`. | For `aiview` projections, the controller re-sorts collections by `(created_at ASC, masked_token ASC)` before returning. |
| **A4** | **Cross-screen quasi-identifier / cardinality join** | Correlating `local_identity` creation timestamps, role counts, and active session counts across screens to infer which pseudonym is the bootstrap `nexusadmin` vs `aiview` vs a human operator. | Well-known bootstrap accounts (`nexusadmin`, `aiview`, `aiview-e2e`) are structural platform fixtures seeded by code; human accounts have their names, DNs, and groups pseudonymized, and free-text fields (`description`, `reason`, `last_error`, `trustPem`) are withheld. |
| **A5** | **Timing / side-channel oracle on LDAP or Notification test endpoints** | Triggering `POST /api/v2/config/ldap/test` or `POST /api/v2/config/notifications/test` to measure response latency or observe error strings containing internal hostnames. | Both test endpoints remain strictly `role:security_admin` (`403` for `aiview`), and stored `routes[].last_error` in `GET /api/v2/config/notifications` is replaced with constant `"DELIVERY_ERROR_WITHHELD_IN_AIVIEW"`. |
| **A6** | **Human screenshots or DOM inspection in `aiview` mode** | A human or browser agent inspects React state, network responses, or HTML `title` / `data-*` attributes, or shares a screenshot from an `aiview` session. | Masking occurs **exclusively on the server** before JSON serialization (`AdminMaskedResponse` + `X-Nexus-Masked: true`). Raw strings never reach the browser network tab, React props, DOM attributes, or screenshots. |
| **A7** | **`PrivacyMaskingResponseBodyAdvice` double-masking or missed-field fallthrough** | A controller returns an un-enveloped DTO; `PrivacyMaskingResponseBodyAdvice` either misses a principal key or re-masks an already-masked token. | Every Administration controller method returning principal data constructs a dedicated masked record wrapped in `AdminMaskedResponse<T>`. `PrivacyMaskingResponseBodyAdvice.beforeBodyWrite` unwraps `AdminMaskedResponse` directly (after setting `X-Nexus-Masked: true`), bypassing generic heuristic map walking. |
| **A8** | **Privilege escalation via `MachineSessionController`** | Someone binds `role:security_admin` to `aiview` or `aiview-e2e` in the database to make Admin screens work. | **Prohibited.** `AiViewIdentitySeedingRunner` resets `aiview` and `aiview-e2e` bindings on boot to `[role:viewer, role:replay_viewer]`, and `MachineSessionController` rejects `aiview-e2e` with `403 MACHINE_IDENTITY_MISCONFIGURED` if its role set deviates from exact `Set.of(VIEWER, REPLAY_VIEWER)`. |

### 3.2 Canary Principal Verification Suite (`AiViewAdminCanaryPrincipalLeakTest`)

To prove mathematically and continuously in CI that **no raw principal string ever leaves the server for an `aiview` session**, the implementation MUST include an integration test suite (`ui2/service/src/test/java/com/securityexpert/nexus/ui2/service/security/AiViewAdminCanaryPrincipalLeakTest.java`) built around **Canary Principals**.

#### 3.2.1 Canary Fixtures Seeded Before the Test
The test seeds the in-memory / test database stores with high-entropy, unmistakable synthetic canary strings across every principal column:

| Store / Table | Column / Field | Synthetic Canary String |
| :--- | :--- | :--- |
| `local_identity` | `local_identity_name` | `canary-local-operator-alpha91` |
| `sessions` | `actor_fingerprint` | `local:canary-local-operator-alpha91` |
| `sessions` | `ended_by_actor_fingerprint` | `ldap:canary-revoker-admin-beta82` |
| `rbac_roles` (custom) | `name`, `description` | `CanaryCustomSecOpsRole`, `Canary description for internal team delta73` |
| `role_binding` | `group_reference`, `created_by_actor_fingerprint` | `CN=CanaryNetAdmins,OU=SecGroups,DC=canarycorp,DC=internal`, `local:canary-binding-author-gamma64` |
| `ldap_settings` | `servers`, `domainDn`, `adminGroupDn`, `usersGroupDn`, `bindDn`, `trustPem` | `ldaps-canary-dc01.canarycorp.internal`, `DC=canarycorp,DC=internal`, `CN=CanaryNetAdmins,OU=SecGroups,DC=canarycorp,DC=internal`, `CN=CanaryViewers,OU=SecGroups,DC=canarycorp,DC=internal`, `CN=canary-ldap-bind-svc,OU=Svc,DC=canarycorp,DC=internal`, `-----BEGIN CERTIFICATE-----\nCANARY_CERT_SUBJECT_CANARYCORP\n-----END CERTIFICATE-----` |
| `credentials` | `credential_reference_id`, `display_name`, `username`, `snmp_context_name` | `canary-cred-ref-01`, `Canary Core Firewall Admin Cred`, `canary-ssh-login-user`, `canary-snmp-vrf-ctx` |
| `notification_settings` | `notification_identity`, `syslog_host`, `smtp_host`, `smtp_from`, `smtp_to`, `routes[].recipients`, `routes[].last_error` | `canary-nexus-sender`, `syslog-canary.canarycorp.internal`, `smtp-canary.canarycorp.internal`, `canary-alerts-sender-token`, `canary-noc-recipient-token`, `canary-soc-recipient-token`, `550 Rejected by smtp-canary.canarycorp.internal for canary-soc-recipient-token` |
| `system_log_settings` | `updated_by` | `local:canary-syslog-editor-epsilon55` |
| `scheduled_job_family` | `changed_by`, `reason` | `local:canary-sched-editor-zeta44`, `Paused by canary operator for maintenance window` |
| `audit_log` | `actor`, `row_pk` | `canary-audit-actor-theta33`, `CN=CanaryNetAdmins,OU=SecGroups,DC=canarycorp,DC=internal` |
| `ssh_host_key` | `hostname`, `ip_address` | `canary-fw-edge-01.canarycorp.internal`, `192.0.2.99` |

#### 3.2.2 Required Assertions in `AiViewAdminCanaryPrincipalLeakTest`
1. **Zero Canary Substring Leak Across All Read Routes:**
   Execute `GET` as both `aiview` (browser session) and `aiview-e2e` (machine session) against:
   - `/session/status`
   - `/local-identities`
   - `/sessions`
   - `/roles`
   - `/role-bindings`
   - `/api/v2/config/ldap`
   - `/credentials`
   - `/api/v2/config/notifications`
   - `/api/v2/config/system-logs`
   - `/audit-logs`
   - `/api/v2/config/scheduled-jobs`
   - `/api/v2/ssh-trust`
   - `/api/v2/system/pods` and `/api/v2/system/storage`
   - `/project-plan`
   For every response:
   - Assert HTTP status `200 OK` and response header `X-Nexus-Masked: true`.
   - Assert that the raw response body string (and all response headers) contains **zero occurrences** of any seeded canary string (checked case-insensitively, URL-encoded, and Base64-encoded), including common substrings (`canary`, `canarycorp`, `alpha91`, `beta82`, `delta73`, `gamma64`, `epsilon55`, `zeta44`, `theta33`).
2. **Cross-Screen Token Join Consistency Assertion:**
   - Extract the `USR-<10-hex>` token emitted for `canary-local-operator-alpha91` in `GET /local-identities` (`identities[].local_identity_name`).
   - Assert that the **exact same `USR-<10-hex>`** appears in `GET /sessions` (`actor_fingerprint` and `identity_labels`).
   - Extract the `GRP-<10-hex>` token emitted for `adminGroupDn` in `GET /api/v2/config/ldap`.
   - Assert that the **exact same `GRP-<10-hex>`** appears as `group_reference` in `GET /role-bindings` and as `target` in `GET /audit-logs`.
3. **Counts and States Preservation Assertion:**
   - Assert that the number of returned rows in `/local-identities`, `/sessions`, `/roles`, `/role-bindings`, `/credentials`, and `/audit-logs` matches the exact count seen by `security_admin`.
   - Assert that all `can_write` / `can_revoke` / `can_write_administration` flags are `false` for `aiview` and `true` for `security_admin`.
4. **Exhaustive Write-Endpoint Refusal Assertion:**
   - Issue every Administration `POST`, `PUT`, and `DELETE` route listed in §2.5 under both `aiview` (human replay viewer) and `aiview-e2e` (machine replay viewer).
   - Assert `403 Forbidden` on every call (`MACHINE_SESSION_READ_ONLY` for `aiview-e2e`; `INSUFFICIENT_ROLE` / `REPLAY_VIEWER_READ_ONLY` for `aiview`).

---

## 4. Small Delivery Steps, Risks & PO Questions

### 4.1 Small, Rollback-Friendly Delivery Slices

To obey the `AGENTS.md` incremental engineering law, implementation should be split into **4 small, independently testable slices** after this design contract is frozen:

1. **Slice 1 — `AdminPrincipalPseudonymizer` & `AdminMaskedResponse` Foundation (`ui2:sanitizer` + `ui2:service`):**
   - Implement `AdminPrincipalPseudonymizer` (canonical `USR-`, `LID-`, `GRP-`, `DN-`, `SESS-`, `CRED-`, `MAIL-`, `ROLE-`, `TGT-` token generation backed by `IdentityTokens` / `HmacKeyProvider` + `LocalIdentityStore` lookup for `local:<uuid>` canonicalization).
   - Introduce `AdminMaskedResponse<T>` envelope in `PrivacyMaskingResponseBodyAdvice` so pre-projected Admin responses set `X-Nexus-Masked: true` and unwrap without heuristic map-walking.
   - Unit tests proving determinism, domain separation, `system:*` preservation, and cross-table join equality.
2. **Slice 2 — Access & Identity Read Split and Masked Projections (`ui2:service`):**
   - Split `SESSION_READ` from `SESSION_REVOKE` and `ROLE_BINDING_READ` from `ROLE_BINDING_CREATE` in `ActionRegistry` and `SecurityWebMvcConfig`.
   - Add `RoleToken.REPLAY_VIEWER` to `alternativeRoles` for `LOCAL_IDENTITY_LIST`, `SESSION_READ`, `RBAC_ROLE_READ`, `ROLE_BINDING_READ`, and `LDAP_CONFIG_READ`.
   - Implement masked read projections and `can_write: false` flags in `LocalIdentityAdministrationController`, `SessionAdminController`, `RbacRoleController`, `RoleBindingAdminController`, and `DirectorySettingsController`.
   - Update `SessionStatusController` (`can_read_administration: true`, `can_write_administration: false` for `aiview`).
3. **Slice 3 — Platform, Credentials & Audit Masked Projections + Canary Suite (`ui2:service` + `:architecture-tests`):**
   - Add `RoleToken.REPLAY_VIEWER` to `NOTIFICATION_CONFIG_READ`, `SYSTEM_LOG_SETTINGS_READ`, `AUDIT_LOG_READ`, and (subject to PO Q1) `CREDENTIAL_LIST`.
   - Implement masked read projections in `NotificationSettingsController`, `SystemLogSettingsController` (`updated_by`), `AuditLogController` / `AuditLogQueryService`, `CredentialAdministrationController`, and align `ScheduledJobsController` / `SshHostKeyController`.
   - Add `AiViewAdminCanaryPrincipalLeakTest` and update `AiviewDiagnosticReadOnlyTest` / `:architecture-tests:test` to pin the exact `ActionRegistry` allowlist and write refusals.
4. **Slice 4 — Frontend Read-Only Admin Affordances (`ui2/frontend`):**
   - Update `SessionContext.tsx`, `AdministrationScreen.tsx`, `navModel.ts`, and child panels (`LocalIdentitiesPanel.tsx`, `SessionsPanel.tsx`, `CustomRolesPanel.tsx`, `DirectorySettingsPanel.tsx`, `CredentialsPanel.tsx`, `NotificationSettingsPanel.tsx`, `SystemLogSettingsPanel.tsx`, `AuditLogsPanel.tsx`, `DeviceRegistryPanel.tsx`) to unlock Administration navigation on `can_read_administration` while binding all create/edit/delete/revoke/test controls to `can_write_administration` / per-endpoint `can_write`.
   - Add Vitest unit tests for every panel in read-only `aiview` mode (`can_write: false`).

### 4.2 Technical & Governance Risks

| Risk | Severity | Mitigation |
| :--- | :--- | :--- |
| **R1: `nexusadmin` holds `RoleToken.REPLAY_VIEWER`** (`FirstBootIdentitySeedingRunner` binds `nexusadmin` to all `RoleToken.values()`). | High | `GateChainInterceptor.isReplayViewer` already checks `!RbacEvaluator.ROOT_AUTHORITY.equals(decision.authority().orElse(null))`. Every controller helper that checks `isReplayViewer(request)` MUST read the `GateChainInterceptor.IS_REPLAY_VIEWER_ATTRIBUTE` request attribute (never raw `roles.contains(REPLAY_VIEWER)`), ensuring `nexusadmin` always sees unmasked data and `can_write: true`. |
| **R2: `AiviewDiagnosticReadOnlyTest` / architecture test pins exact `REPLAY_VIEWER` action count.** | Medium | Update the pinned allowlist test in the same slice as the `ActionRegistry` change, explicitly asserting both the permitted read actions and that every sibling write/test action (`SESSION_REVOKE`, `ROLE_BINDING_CREATE`, `LDAP_CONFIG_WRITE`, `LDAP_CONFIG_TEST`, `NOTIFICATION_TEST_SEND`, etc.) refuses `REPLAY_VIEWER`. |
| **R3: Local identity cache staleness when canonicalizing `local:<uuid>` to username.** | Low | Resolve `local:<uuid>` via `LocalIdentityStore` at projection time (or canonicalize both `GET /local-identities` `local_identity_name` and `local:<uuid>` through `local_identity_id` when the account exists in `local_identity`), so a username rename or lookup never desynchronizes tokens across screens. |
| **R4: Frontend panels assuming `can_read_administration == true` implies write permission.** | Medium | Several panels (`SshHostKeyPanel.tsx`, `AdministrationScreen.tsx`) currently pass `canReadAdministration` to enable write controls. Introducing `can_write_administration` in `/session/status` and `can_write` on each endpoint response cleanly separates read visibility from write authority. |

### 4.3 Open Questions for Product Owner Decision (Before Freezing)

1. **Q1 — Credentials Tab (`GET /credentials`):**
   Should `aiview` see the **masked posture rows** specified in §2.4.6 (`CRED-<10-hex>` display name, kind enum, `USR-<10-hex>` username, SNMPv3 security level/protocol enums, and timestamps, with zero secret material), OR should `GET /credentials` return **counts-only / remain locked** for `aiview`?
   *(Recommendation: Masked posture rows per §2.4.6 so agents can verify credential table rendering, SNMPv3 posture badges, and rotation timestamps without human screenshots.)*
2. **Q2 — Principal HMAC Key Lifetime (Privacy-Key Persistent vs Session-Window Rotating):**
   Should `AdminPrincipalPseudonymizer` use the **same `IdentityTokens` HMAC key** already used by Scheduled Jobs (`GET /api/v2/config/scheduled-jobs`), Change Feed (`GET /api/v2/changes`), and System Logs (`GET /api/v2/system-logs`) so `USR-<10-hex>` tokens join across **both** Administration and operational planes, OR should it derive a **per-session-window (e.g. 10h / 24h epoch) subkey**?
   *(Recommendation: Use the shared `IdentityTokens` HMAC key with canonical local-ID resolution so `USR-XXXXXXXXXX` is consistent across Scheduled Jobs, Change Feed, System Logs, and all Administration tabs within the running service lifecycle.)*
3. **Q3 — Built-In Seeded Account Names (`aiview`, `aiview-e2e`, `nexusadmin`):**
   In `GET /local-identities` and `GET /sessions`, should the three code-seeded accounts (`aiview`, `aiview-e2e`, `nexusadmin`) also be pseudonymized to `USR-<10-hex>`, or should `aiview` / `aiview-e2e` remain visible as literal role-persona identifiers while all other local and directory principals are masked to `USR-<10-hex>`?
   *(Recommendation: Mask all human/operator accounts including `nexusadmin` to `USR-<10-hex>`, while allowing `system:*`, `migration:*`, and `bootstrap:*` automated actors to remain verbatim.)*
4. **Q4 — Delivery Plan Page (`GET /project-plan`):**
   `GET /project-plan` is already open to `aiview` at the backend (`ActionRegistry.PROJECT_PLAN_READ` has `Optional.empty()`), but the UI tab (`product` / `delivery-plan`) is locked by `planeGranted("Admin")`. Confirm that unlocking Administration read visibility for `aiview` should also unlock the read-only **Delivery plan** page in the UI.
