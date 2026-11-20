# Design Note: `aiview` as a Masked Read-Only Administrator

**Status:** `DRAFT -- DO NOT FREEZE`
**Date:** 2026-11-20
**Backlog Item:** `aiview_masked_readonly_admin`
**Authority:** `AGENTS.md` ("Mandatory AIView inspection & post-parsing masking law", "Sensitive identity reporting law", "Raw-evidence law", "Contract-status law")

---

## 1. Code Inventory: Administration & Operations GET Routes and Screens

Under `AGENTS.md`, UI reviews and screenshots use `aiview` (`role:replay_viewer`). Routes map via `SecurityWebMvcConfig.ACTION_ID_BY_ROUTE` to `ActionRegistry` (`GateChain` E1–E4), navigation locks come from `SessionStatusController` (`GET /session/status`), and masking runs in `PrivacyMaskingResponseBodyAdvice`.

Today, 11 of 16 Administration tabs return `403` (locked) for `aiview`. Moreover, `PrivacyMaskingResponseBodyAdvice.maskObject` walks only `Map`, `List`, `String`, `JobEvent`, and `JobPage`—skipping Java `record` DTOs—while services pre-masking fields in a raw `Map` are double-masked by `maskMap`.

|Route (`GET`) & Screen / Tab|Role Today (`ActionRegistry`)|Masked Today|Gap|
|:---|:---|:---|:---|
|`/session/status` *(Shell / locks)*|Active session (ungated)|No|`can_read_administration` true only for `security_admin`; `permissions` omits `Admin` & `Operations`; `display_name` raw.|
|`/devices`, `/devices/*` *(Admin › Registry › Management & Exclusions; Operations)*|Any session (`device_read`)|Yes (`hostname`, `management_ip`, `serial_number`, `cluster_member_ref`)|Payload masked; `DeviceRegistryPanel` renders row mutation menu (`Change credentials…`, `Delete`).|
|`/discovery/runs/*` *(Admin › Registry › Import)*|Any session (`discovery_run_read`)|Yes (`candidates` withheld)|None (`candidate_count` & `candidates_withheld: true` only, PO 2026-11-11).|
|`/credentials` *(Admin › Registry › Credentials)*|`security_admin` (`credential_list`)|No (`403`)|403; `username`, `display_name`, `credential_reference_id` absent from `maskMap`; no `can_write: false`.|
|`/api/v2/ssh-trust` *(Admin › Registry › SSH trust; Operations banner)*|Any session (`ssh_trust_read`)|Partial (`hostname` masked)|`PrivacyMaskingResponseBodyAdvice:253` passes `presented_fingerprint` & `expected_fingerprint_prefix` raw.|
|`/local-identities` *(Admin › Access › Local identities)*|`security_admin` (`local_identity_list`)|No (`403`)|403; `local_identity_name` (usernames) absent from `maskMap`; no `can_write: false`.|
|`/api/v2/config/local-password-policy` *(Admin › Access › Password policy)*|`security_admin` (`local_password_policy_read`)|Partial (`403`; `can_write: false`)|403; `updated_by` (`actor_fingerprint`) absent from `maskMap`.|
|`/sessions` *(Admin › Access › Sessions)*|`security_admin` (`session_revoke_by_admin`)|No (`403`)|Coupled to write action `session_revoke_by_admin`; `identity_labels`, `actor_fingerprint`, `ended_by_actor_fingerprint` raw; no `can_write: false`.|
|`/roles` *(Admin › Access › Roles & permissions)*|`security_admin` (`rbac_role_read`)|No (`403`)|403; `RbacRoleRecord` skipped by `maskObject`; custom `name`, `token_string`, `description` raw.|
|`/role-bindings` *(Admin › Access › Roles & permissions)*|`security_admin` (`role_binding_create`)|No (`403`)|Coupled to write action `role_binding_create`; `RoleBindingView` skipped by `maskObject`; `group_reference` (AD/SAML DNs) & `created_by_actor_fingerprint` raw.|
|`/api/v2/config/ldap` *(Admin › Access › LDAP settings)*|`security_admin` (`ldap_config_read`)|No (`403`)|403; `LdapSettingsService.View` skipped by `maskObject`; `servers`, `domainDn`, `adminGroupDn`, `usersGroupDn`, `bindDn`, `trustPem` raw.|
|`/api/v2/config/saml` *(Admin › Access › SAML sign-in)*|`security_admin` (`saml_config_read`)|No (`403`)|403; `SamlSettingsService.View` skipped by `maskObject`; SP/IdP URLs, entity IDs, group values, `idpSigningCertPem`, `spKey.certPem` raw.|
|`/api/v2/config/local-auth-mode` *(Admin › Access › Local sign-in)*|`security_admin` (`local_auth_mode_read`)|Partial (`403`; `can_write: false`)|403; `updated_by` (`actor_fingerprint`) absent from `maskMap`.|
|`/api/v2/config/scheduled-jobs` *(Admin › Scheduled jobs)*|Any session (`scheduled_jobs_read`)|Yes (`changed_by` $\rightarrow$ `USR-`, `reason` withheld, `can_write: false`)|`local:<uuid>` in `changed_by` must canonicalize with `/local-identities`.|
|`/api/v2/config/notifications` *(Admin › Notifications & logging › Notifications)*|`security_admin` (`notification_config_read`)|No (`403`)|403; `NotificationSettings` skipped by `maskObject`; `syslog_host`, `smtp_host`, `smtp_from`, `smtp_to`, `routes[].recipients`, `routes[].last_error` raw.|
|`/notifications` *(Top-bar drawer)*|Any session (`notifications_read`)|Partial (`hostname` masked; `message`/`detail` via `maskText`)|Free-text `message`/`detail` uses `maskText` (registered hostnames/IPv4 only).|
|`/api/v2/config/system-logs` *(Admin › Notifications & logging › Log collection)*|`security_admin` (`system_log_settings_read`)|Partial (`403`; `can_write: false`)|403; `updated_by` (`actor_fingerprint`) absent from `maskMap`.|
|`/api/v2/system/pods`, `/api/v2/system/storage` *(Admin › Platform status)*|Any session (`system_status_read`)|Yes (`top_devices[].hostname` masked)|None (pod names/byte counts hold no identities).|
|`/api/v2/security/scan-summary/latest` *(Admin › Platform status › Security scans)*|`security_admin`, `operation_admin`, `replay_viewer` (`security_scan_summary_read`)|Yes|None (rule IDs, counts, and repo paths hold no identities).|
|`/audit-logs` *(Admin › Audit log)*|`security_admin` (`audit_log_read`)|No (`403`)|403; `AuditEvent` skipped by `maskObject`; `actor` (`actor_fingerprint`) & `target` (`row_pk`: DNs, user/device IDs) raw.|
|`/project-plan` *(Admin › Delivery plan)*|Any session (`project_plan_read`)|Yes (roadmap metadata)|Backend open, but UI locks tab via `planeGranted("Admin")`.|
|`/api/v2/job-window` *(Operations header)*|Any session (`device_read`)|Yes (`admin_window_bypass: false`)|None.|
|`/api/v2/{cp,pan,asa,fgt}-failover/summary`, `/units`, `/units/*`, `/runs`, `/runs/*` *(Operations › HA & readiness)*|`operator`, `security_admin`, `operation_admin`, `viewer`, `replay_viewer` (`cp_failover_read`)|Yes (cluster/member names masked; write flags `false`)|`GET /units/{clusterId}` omits `maskedName`/`maskedMembers`; stopped-run `message` uses `maskText`.|
|`/api/v2/{cp,pan}-failover/approvals`, `/incidents`, `/config-syncs/*` *(Operations › HA & readiness)*|`operator`, `security_admin`, `operation_admin`, `viewer`, `replay_viewer` (`cp_failover_read`)|Partial (`approvedBy` stripped; `canRelease` `false`; `maskedName` set)|Free-text `reason` in `/approvals` & `/incidents` falls through to `maskText` (not withheld).|
|`/api/v2/jobs`, `/jobs/stats`, `/jobs/facets`, `/jobs/export.csv` *(Operations › Jobs)*|Any session (`job_log_read`)|Yes (`JobPage`/`JobEvent` & CSV masked; target sort refused; ID-only search)|Failed-job `terminal_reason` uses `maskText` (known hostnames/IPv4 only).|
|`/jobs/*/transcript`, `/api/v2/jobs/*/transcript/health` *(Operations › Jobs › Transcript)*|`/transcript`: `security_admin`, `backup_admin` (`job_transcript_read` + hard `403` for `aiview`); `/health`: any session|`/transcript`: Excluded (`403`); `/health`: Yes (counts/status)|Raw `/jobs/*/transcript` holds device CLI/API output; must stay excluded.|
|`/api/v2/scripts`, `/api/v2/scripts/*` *(Operations › Script Execution)*|Any session (`script_read`)|Partial (no body; `egress`, `run_as`, `created_by*` masked)|Script `name` and `credential_refs` slugs pass through unmasked.|
|`/api/v2/system-logs/health`, `/api/v2/system-logs/*` *(Operations › Device health & Logs)*|`operator`, `operation_admin`, `security_admin`, `replay_viewer` (`system_log_read`; `?unmasked=true` requires `system_log_read_unmasked`)|Yes (names & lines masked; `unmasked=true` refused for `aiview`)|None (`system_log_read_unmasked` excluded and audited).|
|`/api/v2/operations/policy-install/status` *(Operations › Policy installation)*|Any session (`policy_install_read`)|Buggy (double-masked)|`PolicyInstallMonitoringService` pre-masks `hostname` & `policy_name` in raw `Map`; `maskMap` hashes twice.|
|`/api/v2/operations/static-route/preview`, `/preview/*` *(Operations › Static routes)*|`operator`, `operation_admin`, `security_admin`, `viewer`, `replay_viewer` (`static_route_read`)|Buggy (double-masked + `command_text` gap)|`StaticRoutePreviewService` pre-masks `target_label`/`member_label` in raw `Map` (double-masked by `maskMap`); `command_text` embeds raw interface/route names.|

---

## 2. Proposed Model

### 2.1 Read-Only Permission Set for `aiview`
1. **Decouple coupled routes:** Split `GET /sessions` onto `SESSION_READ` (`session_read`) and `GET /role-bindings` onto `ROLE_BINDING_READ` (`role_binding_read`), keeping `SESSION_REVOKE` and `ROLE_BINDING_CREATE` on `POST`.
2. **Grant `RoleToken.REPLAY_VIEWER` on read actions only:** Add `RoleToken.REPLAY_VIEWER` to `alternativeRoleTokens` on `LOCAL_IDENTITY_LIST`, `LOCAL_PASSWORD_POLICY_READ`, `LOCAL_AUTH_MODE_READ`, `SESSION_READ`, `RBAC_ROLE_READ`, `ROLE_BINDING_READ`, `LDAP_CONFIG_READ`, `SAML_CONFIG_READ`, `CREDENTIAL_LIST` (PO Q1), `NOTIFICATION_CONFIG_READ`, `SYSTEM_LOG_SETTINGS_READ`, and `AUDIT_LOG_READ`.
3. **Unlock shell inspection without writes:** In `SessionStatusController`, set `can_read_administration = true` and `can_write_administration = false` for `aiview`, and add `"Admin"` and `"Operations"` to `permissions` so all tabs render read-only (`can_write: false`).

### 2.2 Writes and Raw/Unmasked Views Excluded by Construction
Exclusion is enforced by four server barriers, never by UI hiding:
1. **`GateChain` E1 Machine Guard:** `aiview-e2e` (`session.machine() == true`) is refused (`403 MACHINE_SESSION_READ_ONLY`, audited) on any non-`GET`/`HEAD` request outside `GateChain.isReadOnlyPost`. No admin mutation, test probe (`POST /api/v2/config/ldap/test`, `POST /api/v2/config/notifications/test-*`), or operations write enters `isReadOnlyPost`.
2. **`GateChain` E4 Closed Descriptors:** Every mutating action (`*_WRITE`, `*_CREATE`, `*_DELETE`, `*_REVOKE`, `*_SET`, `*_MANAGE`, `*_APPROVE`, `*_START`, `*_RELEASE`, `*_UPLOAD`, `JOB_CANCEL`, `JOB_RECONCILE`) and raw/download action (`SYSTEM_LOG_READ_UNMASKED`, `PACKET_ANALYSIS_RAW_VIEW`, `REPORT_RAW_VIEW`, `DEVICE_CONFIGURATION_TEXT_READ`, `ZONE_MATRIX_ZONES_READ`, `JOB_TRANSCRIPT_READ`, `DEVICE_BACKUP_RETRIEVE`, `SUPPORT_BUNDLE_RETRIEVE`, `DATA_SANITIZE_RUN`, `DATA_SANITIZE_REVERT`) omits `RoleToken.REPLAY_VIEWER`.
3. **Controller & Service Invariant:** Every mutating endpoint, `mayWrite(request)` check, and raw-view branch (`?unmasked=true`, `REPORT_RAW_VIEW`, `/jobs/*/transcript`) refuses when `GateChainInterceptor.IS_REPLAY_VIEWER_ATTRIBUTE` is `true` (`role:replay_viewer` except `RbacEvaluator.ROOT_AUTHORITY`).
4. **DTO Exclusion:** Secret material (verifiers/salts, credential secrets/passphrases, LDAP/SAML private keys, `audit_log.before_json`/`after_json`, script bodies) never enters any read DTO.

### 2.3 Server-Side Masking (`AdminMaskedResponse<T>` & `AdminPrincipalPseudonymizer`)
1. **Pre-Masked Envelope (`AdminMaskedResponse<T>`):** Controllers wrap pre-projected payloads in `AdminMaskedResponse<T>(body, masked)`. `PrivacyMaskingResponseBodyAdvice` verifies `masked == true` for `isReplayViewer`, sets `X-Nexus-Masked: true`, and unwraps `body` without `maskMap`—eliminating `record` bypass and double-masking on `/api/v2/operations/policy-install/status` and `/api/v2/operations/static-route/preview`.
2. **Canonical Principal Pseudonymization (`AdminPrincipalPseudonymizer`):** Using `IdentityTokens` (`HMAC-SHA256` under `HmacKeyProvider`'s 256-bit key), `AdminPrincipalPseudonymizer` resolves `local:<uuid>` and `<uuid>` via `LocalIdentityResolver` to the canonical username before hashing so each human principal emits the same `USR-<10-hex>` token across `/local-identities`, `/sessions`, `/role-bindings`, `/audit-logs`, `/api/v2/config/scheduled-jobs`, `/api/v2/config/system-logs`, `/api/v2/config/local-auth-mode`, and `/api/v2/config/local-password-policy`. System literals (`system:*`/`migration:*`/`bootstrap:*`) stay verbatim; domain prefixes cover `LID-`, `GRP-`, `DN-`, `SESS-`, `CRED-`/`CREF-`, `MAIL-`, `ROLE-`, and `FP-`.

### 2.4 Audit of Reads
Every `GET` passing `GateChain` E4 writes an immutable row to `authz_decision_log` (`session_id`, `actor_fingerprint`, `action_id`, `target_ref`, `outcome`, `authority`, `reason_code`, `binding_id` at `GateChain.java:155`). Splitting `SESSION_READ` and `ROLE_BINDING_READ` audits every `aiview` read under its distinct `action_id`.

---

## 3. Risks, Proof Tests & Open Product Owner Questions

### 3.1 Risks
1. **Unmasked fields & Java `record` bypass:** `PrivacyMaskingResponseBodyAdvice` returns unknown `record` instances untouched and only masks device/IP keys in `Map` responses; controllers must use `AdminMaskedResponse<T>`.
2. **Double-masking:** Pre-masking `hostname`, `policy_name`, `target_label`, or `member_label` in a service and returning a plain `Map` causes `maskMap` to hash the pseudonym twice (`PolicyInstallMonitoringService`, `StaticRoutePreviewService`).
3. **Free text & compiled commands:** `maskText` only scrubs registered device names and IPv4 literals. Failover `reason`, custom role `description`, notification `routes[].last_error`, and PEM bodies (`trustPem`, `idpSigningCertPem`, `spCertPem`) must be withheld, and `StaticRoutePreviewService` `command_text` must be compiled from masked interface/route tokens.
4. **Exports, downloads & raw views:** Raw downloads (`/backups/*/download`, `/api/v2/support-bundles/*/download`, `/api/v2/packet-analysis/captures/*/download`) and raw views (`/api/v2/system-logs/chunks/*/text?unmasked=true`, `/jobs/*/transcript`, `/devices/*/configuration/text`, `/api/v2/zone-matrix/versions/*?blocks=1`) stay `403`; permitted exports (`/api/v2/jobs/export.csv`, `/api/v2/reports/runs/*/download`, `/api/v2/policy/*.csv`) must mask before byte serialization.
5. **Ordering/filter oracles & root collision:** `/local-identities` and `/credentials` must sort by `(created_at, masked_id)` for `aiview`, filters must match post-masked tokens only, and `nexusadmin` stays unmasked via `RbacEvaluator.ROOT_AUTHORITY`.

### 3.2 Proof Tests (Per-Route Leak & Refusal Suite)
`AiViewAdminAndOperationsLeakTest` seeds synthetic canary values across every store (`canary-user-alpha91`, `CN=CanaryAdmins,DC=canarycorp,DC=internal`, `ldaps-canary.canarycorp.internal`, `canary-cred-user`, `smtp-canary.canarycorp.internal`, `canary-failover-reason`, `canary-route-comment`, `canary-script-ref`, `SHA256:canaryfingerprint`, plus synthetic device hostnames/IPs/serials) and runs:
1. **Per-route zero-leak test:** Calls every Administration and Operations `GET` route in §1 as `aiview` and `aiview-e2e`; asserts `200 OK`, `X-Nexus-Masked: true`, and zero canary substrings (raw, case-folded, URL-encoded, Base64-encoded).
2. **Cross-route join & non-double-masking test:** Verifies `USR-<10-hex>`, `GRP-<10-hex>`, and `FW-<WORD>-<nn>` tokens match across `/local-identities`, `/sessions`, `/role-bindings`, `/api/v2/config/ldap`, `/audit-logs`, `/api/v2/operations/policy-install/status`, and `/api/v2/operations/static-route/preview`.
3. **Write & raw-view refusal test:** Calls every mutating `POST`/`PUT`/`PATCH`/`DELETE` route and raw-view/download route (`/jobs/*/transcript`, `?unmasked=true`, `/devices/*/configuration/text`, `/backups/*/download`, `/api/v2/support-bundles/*/download`) as `aiview` and `aiview-e2e`, asserting `403`.

### 3.3 Open Product Owner Questions
1. **Q1 (`GET /credentials`):** Should `aiview` see masked credential posture rows (`CRED-` name, `CREF-` ref, kind enum, `USR-` username, SNMPv3 enums, timestamps, zero secrets) or counts-only?
2. **Q2 (LDAP & SAML URLs/Certificates):** In `/api/v2/config/ldap` and `/api/v2/config/saml`, confirm masking internal URLs/entity IDs via `TopologyNamePseudonymizer` and replacing PEM certificates with `"[CERTIFICATE WITHHELD IN AIVIEW — SHA256:<10-hex>]"`.
3. **Q3 (SSH fingerprints & failover reasons):** Should `/api/v2/ssh-trust` mask `presented_fingerprint`/`expected_fingerprint_prefix` to `FP-<10-hex>`, and should `/api/v2/{cp,pan}-failover/approvals` and `/incidents` withhold free-text `reason`?
4. **Q4 (`GET /jobs/*/transcript`):** Confirm `/jobs/*/transcript` stays `403` for `aiview` (only `/api/v2/jobs/*/transcript/health` visible).
5. **Q5 (Seeded accounts & HMAC key):** Confirm `AdminPrincipalPseudonymizer` uses the shared `IdentityTokens` HMAC key and masks all local accounts including `nexusadmin` to `USR-<10-hex>` while keeping `system:*`/`migration:*`/`bootstrap:*` verbatim.
