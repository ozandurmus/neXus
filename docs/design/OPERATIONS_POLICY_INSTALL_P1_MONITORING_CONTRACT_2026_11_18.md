# Operations > Policy Installation — Phase 1 (P1: Read-Only Monitoring) Contract

## Status

`FROZEN -- PO APPROVED (chat 2026-11-18, "Evet, dondur")`

Scope: **Phase 1 (P1 — Read-Only Monitoring) only**, derived from §2.1 of [OPERATIONS_POLICY_INSTALL_CONTRACT_DRAFT_2026_11_17.md](OPERATIONS_POLICY_INSTALL_CONTRACT_DRAFT_2026_11_17.md) with the three fixes required by the decision council and ratified in [PO_DECISION_RECORD_2026_11_18_POLICY_INSTALL_P1_MONITORING.md](PO_DECISION_RECORD_2026_11_18_POLICY_INSTALL_P1_MONITORING.md). Phases P2 (Verify Only), P3 (Manual Install), and P4 (Scheduled Automatic Install) remain `DRAFT -- DO NOT FREEZE` in [OPERATIONS_POLICY_INSTALL_CONTRACT_DRAFT_2026_11_17.md](OPERATIONS_POLICY_INSTALL_CONTRACT_DRAFT_2026_11_17.md).

---

## 1. Hard Scope and Non-Goals (P1 Boundary)

1. **Zero new network-device commands:** P1 issues no new device command and adds no row to `ui2/capability-registry/src/main/resources/capabilities/gate_registry_fixture.yaml`. It consumes only data already stored by existing collections from reads that are already `SIGNED_OFF` in `gate_registry_fixture.yaml`.
2. **Candidate reads `GI-1`, `GI-2`, and `GI-3` are excluded from P1 (Council Fix c):**
   - `GI-1` (Check Point VSX per-VS `cpstat -f policy fw`), `GI-2` (Panorama `<show><jobs><all/></jobs></show>`), and `GI-3` (FortiManager `exec /securityconsole/package/status`) are **not** part of P1.
   - Any row whose evaluation would require `GI-1`, `GI-2`, or `GI-3` MUST remain `UNKNOWN` with reason `"needs a read not approved yet"` (including the specific candidate gate context where applicable).
   - **Shell normalization rule for future `GI-1`:** `GI-1` may only ever come back in the non-login shell form (`. /etc/profile.d/CP.sh >/dev/null 2>&1; unset BASH_ENV ENV; vsenv <VSID> >/dev/null && cpstat -f policy fw`), **never** `bash -lc` ([MDS_FORK_STORM_FIX_CONTRACT_2026_11_11.md](MDS_FORK_STORM_FIX_CONTRACT_2026_11_11.md)).
3. **Read-only HTTP and RBAC boundary:**
   - P1 exposes `GET` routes only (`GET /api/v2/operations/policy-install/status`).
   - No write path, no `ActionRegistry` write action, no schema migration adding a device command, no schedule table, no approval table, and no credential reference.
   - Any authenticated role (`security_admin`, `operation_admin`, `compliance_admin`, `operator`, and `aiview` / `role:replay_viewer`) may read the endpoint.
   - Under `aiview` (`role:replay_viewer`), all target device names, container/domain names, package/device-group names, and installed policy names pass through `TopologyNamePseudonymizer` (`DEV-...` / `CLS-...`, `DOM-...`, `POL-...`), with zero rule names, zero IP addresses, and free-text search (`q`) evaluated strictly **after** masking.

---

## 2. Closed State Vocabulary and Evidence Law

### 2.1 Closed State Vocabulary

Every row returned by P1 MUST have a `state` from the following closed set of seven values:

| State | Meaning | Applicable Vendors |
| --- | --- | --- |
| `INSTALLED` | Target is assigned in management **and** direct gateway runtime evidence corroborates that the expected package/policy is installed and its gateway `installed_at` is at or after the package's own `last_change_at` (or no post-baseline package change exists). | Check Point, Palo Alto (Panorama) |
| `OUT_OF_DATE` | Direct gateway runtime evidence shows the package/device-group was installed earlier than the package's own `last_change_at`, or Panorama `<show><devicegroups/></show>` reports `OUT_OF_SYNC`. | Check Point, Palo Alto (Panorama) |
| `NOT_INSTALLED` | Target is assigned to a package/device-group in management discovery, and direct gateway runtime evidence was collected but reports no policy installed or a different installed `policy_name`. | Check Point, Palo Alto (Panorama) |
| `SAVED` | Running configuration matches startup configuration (`asa_more_system_running_config` vs `asa_show_startup_config`) with no newer unsaved configuration change. | Cisco ASA only |
| `UNSAVED` | Running configuration differs from startup configuration, or a newer configuration change was observed after the last startup-matching backup. | Cisco ASA only |
| `NOT_APPLICABLE` | Device applies and persists policy changes immediately without a staged package installation step; accompanied by an HA parity indicator (`EQUAL`, `DIFFER`, or `UNKNOWN`). | Directly managed FortiGate only |
| `UNKNOWN` | Evidence is missing, incomplete, unenrolled, management-only (`"MANAGEMENT_ONLY"`), or requires an unapproved read (`"needs a read not approved yet"`). Every `UNKNOWN` row carries a human-readable `reason`. | All vendors |

### 2.2 Constitutional Evidence Invariants

Per `AGENTS.md` ("Evidence laws" and "UNKNOWN / fail-closed law"):
1. **Management claim != gateway runtime truth:** A row MUST NEVER be marked `INSTALLED` from management-plane claims alone (`cp_policy_gateways_and_servers` `policyInstallations[].installed = true` or Panorama `pan_policy_devicegroups` `syncStatus = IN_SYNC`). When management claims installed/in-sync but direct gateway runtime evidence (`device_policy_install`) is absent or has no timestamp, the state MUST be `UNKNOWN` with reason `"MANAGEMENT_ONLY"`.
2. **Absence of evidence != evidence of absence:** Missing `device_policy_install`, missing ASA startup/running config entries, or missing FortiGate checksum results yield `UNKNOWN` (or `ha_parity = "UNKNOWN"`), never `NOT_INSTALLED` or `UNSAVED`.

---

## 3. Vendor State Derivation Rules (With Council Fixes a, b, c)

### 3.1 Check Point (`check_point`) — Council Fix (a) & (c)

- **Stored inputs (already `SIGNED_OFF`):**
  - Packages and assigned targets from `policy_snapshot` (`vendor = 'CP'`, from `cp_policy_packages`).
  - Management gateway inventory from `cp_policy_object_inventory` (`object_type = 'gateways-and-servers'`, from `cp_policy_gateways_and_servers`) and `PolicySnapshotRepository.inventoryLinks`.
  - **Per-package** `last_change_at` from `policy_rule_history` grouped by `policy_id` (`max(case when not initial_capture then coalesce(fn_policy_change_time(changed_on), collected_at) else fn_policy_change_time(changed_on) end)`), scoped strictly to that package's `policy_id`.
  - **Council Fix (a) — Per-Package Change Comparison Only:** Check Point `OUT_OF_DATE` MUST be computed **PER PACKAGE** (comparing the package's own `last_change_at` and package name against the gateway's `device_policy_install.installed_at` and `device_policy_install.policy_name`). Domain-wide publish timestamps (`cp_policy_last_published_session` / `cp_policy_domain_run.signal` / domain-level `policy_revision`) MUST NOT be used to mark a package `OUT_OF_DATE`, because publishing Package B in the same MDS domain advances the domain's last-published session without modifying Package A.
  - Direct gateway runtime read from `device_policy_install` (populated by `cp_policy_install_cpstat` and `cp_policy_install_cpstat_vsenv0`).
- **Derivation order per `(package, target)` row:**
  1. **Non-VS0 virtual system target (`Council Fix c`):** If the target requires a per-VS `cpstat` read inside a non-zero virtual system context (`context` present and not `""`, `"0"`, `"VS0"`, or `"physical"`), return `state = UNKNOWN`, `reason = "needs a read not approved yet (GI-1: per-VS cpstat policy read)"`.
  2. **Missing direct gateway runtime record (`device_policy_install == null` or target unenrolled):**
     - If management gateway inventory claims `installed == true` for this package, return `state = UNKNOWN`, `reason = "MANAGEMENT_ONLY"`.
     - Otherwise return `state = UNKNOWN`, `reason = "Direct gateway cpstat policy read not collected yet"`.
  3. **Policy name mismatch or empty on gateway:**
     - If `device_policy_install.policy_name` is blank, `"-"`, `"none"`, or does not equal the package name, return `state = NOT_INSTALLED`, `reason = "Gateway reports policy '<reported>' instead of package '<expected>'"` (or `"Gateway reports no installed policy"`).
  4. **Missing gateway install timestamp:**
     - If `device_policy_install.installed_at == null`, return `state = UNKNOWN`, `reason = "Gateway cpstat policy read has no parseable install timestamp"`.
  5. **Per-package timestamp comparison:**
     - If package `last_change_at == null`, return `state = UNKNOWN`, `reason = "Package last change timestamp is unknown"`.
     - If `last_change_at.isAfter(installed_at)` (beyond a 120-second clock-skew tolerance, or strictly `last_change_at > installed_at` when exact), return `state = OUT_OF_DATE`, `reason = "Package changed after gateway installation timestamp"`. (Note: to remain strictly fail-closed on package edits after install, any `last_change_at > installed_at` marks the package `OUT_OF_DATE`.)
     - Otherwise (`!last_change_at.isAfter(installed_at)`), return `state = INSTALLED`, `reason = "Direct gateway cpstat corroborates package name and install timestamp"`.

### 3.2 Palo Alto Networks (`palo_alto` / Panorama) — Council Fix (c)

- **Stored inputs (already `SIGNED_OFF`):**
  - Device groups and target firewall bindings (`syncStatus`: `IN_SYNC`, `OUT_OF_SYNC`, `UNKNOWN`) from `policy_snapshot` (`vendor = 'PAN'`, from `pan_policy_devicegroups`).
  - Per-device-group `last_change_at` from `policy_rule_history` (`not initial_capture`, grouped by `policy_id`).
  - Direct firewall runtime commit timestamp from `device_policy_install` (populated by `pan_policy_install_show_jobs` on `pan_firewall`).
- **Derivation order per `(device_group, target)` row:**
  1. If Panorama `syncStatus` is `"OUT_OF_SYNC"`, return `state = OUT_OF_DATE`, `reason = "Panorama device-group shared policy status is OUT_OF_SYNC"`.
  2. If Panorama `syncStatus` is `"IN_SYNC"`:
     - If target is unenrolled or `device_policy_install == null` or `device_policy_install.installed_at == null`, return `state = UNKNOWN`, `reason = "MANAGEMENT_ONLY"` (management claims `IN_SYNC`, but direct firewall commit timestamp is missing).
     - If `device_policy_install.policy_name` is present and does not match the device group name, return `state = NOT_INSTALLED`, `reason = "Firewall reports different policy container"`.
     - If `last_change_at != null && last_change_at.isAfter(installed_at)`, return `state = OUT_OF_DATE`, `reason = "Device group rules changed after firewall's latest commit timestamp"`.
     - Otherwise return `state = INSTALLED`, `reason = "Panorama IN_SYNC corroborated by direct firewall commit job timestamp"`.
  3. Otherwise (`syncStatus` missing or `"UNKNOWN"`):
     - Return `state = UNKNOWN`, `reason = "Panorama device-group sync status is UNKNOWN (or needs a read not approved yet: GI-2)"`.

### 3.3 Cisco ASA (`cisco_asa`) — Council Fix (b)

- **Stored inputs (already `SIGNED_OFF`):**
  - Enrolled Cisco ASA gateways (`vendor_hint = 'cisco_asa'`, `role in ('gateway', 'firewall')`).
  - Latest completed backup artefact (`backup_artefact` + `backup_artefact_entry`) containing `running-config.txt` (from `asa_more_system_running_config`) and `startup-config.txt` (from `asa_show_startup_config`) SHA-256 digests (`entry_sha256`), plus latest `device_configuration_run` (`read_kind = 'show_configuration'`).
- **Derivation order per ASA gateway (`Council Fix b` — never `INSTALLED`):**
  1. Cisco ASA rows MUST NEVER use `INSTALLED`, `OUT_OF_DATE`, or `NOT_INSTALLED`.
  2. If no completed backup artefact with both `running-config.txt` and `startup-config.txt` digests exists for the gateway, return `state = UNKNOWN`, `reason = "Running vs startup configuration comparison not collected yet"`.
  3. If `running-config.txt` SHA-256 differs from `startup-config.txt` SHA-256, or if a newer `device_configuration_run` after the backup recorded `change_state = 'changed'`, return `state = UNSAVED`, `reason = "running-config differs from startup-config (unsaved running configuration changes)"`.
  4. If `running-config.txt` SHA-256 equals `startup-config.txt` SHA-256 (and no newer `change_state = 'changed'` configuration run exists), return `state = SAVED`, `reason = "running-config matches startup-config"`.

**Amendment 2026-11-18 (live evidence, recorded under the PO standing rule "a parser gap is our bug").** The first
live read showed every ASA `UNSAVED`. Steps 3 and 4 compared digests of two differently formed reads:
`running-config.txt` is `more system:running-config` (keys in clear, its own header) and `startup-config.txt` is
`show startup-config` (keys masked, its own header), so the digests differ on every ASA whatever the saved state.
Until a normalized in-memory comparison is measured and recorded, step 3 is replaced: a digest difference returns
`state = UNKNOWN`, `reason = "Saved state not measured yet: the running and startup reads differ in form (keys, header
lines)"`; a newer `change_state = 'changed'` configuration run returns `state = UNKNOWN`, `reason = "Configuration
changed after the last backup; saved state not measured since"`. Step 4 (equal digests, no newer change) stays
`SAVED`. No new device command. `show checksum` is not a substitute: the Cisco ASA command reference says the
checksum is computed when the configuration is stored in flash, so it describes the saved configuration only.

**Amendment 2026-11-18 (b): the normalized relation.** While both texts are in memory during the ASA backup, the worker
(`AsaConfigRelation`, no new device command and no new read) drops lines beginning with `:` and `Cryptochecksum` lines,
trims trailing whitespace, ignores blank lines, and compares the rest in order. A pair is equal when identical, or when
the startup line holds the mask token `*****` and the running line equals it with each token replaced by a non-empty
value (fixed pieces matched in order). The result is `MATCH`, `MISMATCH` or `NOT_EVALUABLE` (either text missing or not
an ASA configuration) with three counts (lines compared, lines differing, lines equal only by mask); only that is stored,
in `asa_config_relation` (V250, one row per backup artefact), never any text. Steps 3 and 4 become: the latest backup's
relation `MATCH` with no newer `change_state = 'changed'` run is `SAVED`; `MISMATCH` (same condition) is `UNSAVED`; a
newer changed run, `NOT_EVALUABLE`, or a backup with no stored relation (taken before V250) is `UNKNOWN` with the reason
naming which. Assumption to confirm on a real unit: the ASA prints stored keys as `*****` in `show startup-config`.

**Amendment 2026-11-19: key and comparison fixed (live evidence: 0 of 12 relation rows joined; 7 of 12 showed
2040-20775 differing lines).** Two defects. (1) The relation row was keyed by the storage reference of the stored bundle
(the value the manifest keeps in `backup_artefact.recovery_volume_path`), but the service joins `backup_artefact.artefact_id`,
a different random id, so no row ever joined and every ASA read "not measured". The worker now records the row under the
storage reference while the bundle is written and, as soon as the manifest row exists, re-keys it to the manifest's
`artefact_id` (`linkManifest`); V253 re-keys the existing rows through `recovery_volume_path`. (2) The in-order
comparison cascaded after one inserted or removed line. The normalized lines are now aligned with a Myers diff (exact up
to an edit distance of 2500, beyond that an order-free multiset difference, so cost stays bounded);
`lines_differing` is inserted plus deleted lines (a changed line counts 2), and a deleted running line and an inserted
startup line of the same change that are equal once the mask is applied count as one equal-by-mask pair. V253 adds
`differing_keywords` (jsonb): the top 10 command keywords of the differing lines with counts. The keyword is the first
word of the line's top-level command (sub-lines of a block count under their block, so a certificate's hex rows count
under `crypto`); a word that is not a plain lower-case command word becomes `other`. Counts only: no line or value is
stored or logged, and the `[ASA_CONFIG_RELATION]` log line carries the same counts. Purpose: tell real unsaved changes
from read-format noise (for example blocks or defaults shown only by `more system:running-config`).

### 3.4 FortiGate (`fortinet`) and FortiManager — Council Fix (b) & (c)

- **Directly managed FortiGate gateways (`vendor_hint = 'fortinet'`, `role in ('gateway', 'firewall')`):**
  - **State (`Council Fix b`):** Always `state = NOT_APPLICABLE` because FortiOS applies and saves configuration changes immediately under `cfg-save automatic`.
  - **HA parity indicator (`ha_parity`):** Derived from the latest finished `fgt_failover_readiness` check #4 (`fgt_failover_checksum_cluster`) in `failover_run` + `failover_check_result`:
    - `ha_parity = "EQUAL"` (`reason = "FortiOS applies changes immediately; HA cluster checksums equal"`) when `derived->>'checksums' = 'MATCH'` or check status is `PASS`.
    - `ha_parity = "DIFFER"` (`reason = "FortiOS applies changes immediately; HA cluster checksums differ"`) when `derived->>'checksums' = 'MISMATCH'` or check reason is `CHECKSUM_DIFFERS`.
    - `ha_parity = "UNKNOWN"` (`reason = "FortiOS applies changes immediately; HA cluster checksum parity unknown"`) when no `fgt_failover_checksum_cluster` readiness result is stored yet.
- **FortiManager-managed policy status (`vendor_hint = 'fortinet'`, `role = 'management_server'` or FortiManager ADOM/package rows):**
  - **State (`Council Fix b & c`):** Always `state = UNKNOWN` with `reason = "needs a read not approved yet (GI-3: FortiManager package status not measured yet)"`.

---

## 4. Service Endpoint and Frontend Contract

### 4.1 Read-Only Endpoint

- **Route:** `GET /api/v2/operations/policy-install/status`
- **ActionRegistry:** `ActionRegistry.POLICY_INSTALL_READ` (`id = "policy_install_read"`, `ActionClass.CLASS_0_READ`, `consoleSubmittable = true`, `Optional.empty()` — accessible to all authenticated roles, including `aiview` with mandatory masking).
- **Query parameters:**
  - `vendor` (optional): `""` (all), `"check_point"`, `"palo_alto"`, `"cisco_asa"`, `"fortinet"`.
  - `state` (optional): `""` (all), `"INSTALLED"`, `"OUT_OF_DATE"`, `"NOT_INSTALLED"`, `"SAVED"`, `"UNSAVED"`, `"NOT_APPLICABLE"`, `"UNKNOWN"`.
  - `q` (optional): case-insensitive substring filter applied **after** `aiview` pseudonymization across target `hostname`, `container_name`, `package_name`, `policy_name`, and `reason`.
  - `page` (optional, default `0`, page size `200`).
- **Response shape (`Map<String, Object>`):**
  - `counts`: map with keys `INSTALLED`, `OUT_OF_DATE`, `NOT_INSTALLED`, `SAVED`, `UNSAVED`, `NOT_APPLICABLE`, `UNKNOWN` (computed across all rows before filter/pagination or matching vendor/search scope so header KPI badges always show all seven state counts).
  - `total`: filtered row count.
  - `page`: current page index.
  - `page_size`: page size (`200`).
  - `masked`: `true` when serving `aiview` (`role:replay_viewer`), `false` otherwise.
  - `contract_ref`: `"docs/design/OPERATIONS_POLICY_INSTALL_P1_MONITORING_CONTRACT_2026_11_18.md"`.
  - `rows`: list of row maps, each containing:
    - `row_id` (`String`): deterministic row identifier
    - `device_id` (`String`, nullable): enrolled gateway UUID text when bound
    - `hostname` (`String`): target gateway/cluster name (pseudonymized for `aiview`)
    - `context` (`String`): VS/VSYS/context identifier when applicable
    - `vendor` (`String`): `"check_point"` | `"palo_alto"` | `"cisco_asa"` | `"fortinet"`
    - `container_id` (`String`): domain / device-group / management container ID
    - `container_name` (`String`): domain / device-group / container label (pseudonymized for `aiview`)
    - `package_id` (`String`): policy package / container snapshot ID
    - `package_name` (`String`): policy package / device-group / config label (pseudonymized for `aiview`)
    - `state` (`String`): one of `INSTALLED`, `OUT_OF_DATE`, `NOT_INSTALLED`, `SAVED`, `UNSAVED`, `NOT_APPLICABLE`, `UNKNOWN`
    - `ha_parity` (`String`, nullable): `"EQUAL"` | `"DIFFER"` | `"UNKNOWN"` for directly managed FortiGate rows (`null` for other vendors)
    - `policy_name` (`String`, nullable): installed policy name reported by gateway (`device_policy_install.policy_name`, pseudonymized for `aiview`)
    - `installed_at` (`String`, nullable): ISO-8601 timestamp from `device_policy_install.installed_at` (or ASA backup / FGT check observation time)
    - `last_change_at` (`String`, nullable): ISO-8601 timestamp of the package's own last change (or latest ASA config change)
    - `reason` (`String`): human-readable explanation of the derived state (safe tokens only; no unmasked hostnames)

### 4.2 Frontend Screen (`Operations > Policy installation`)

- **Location:** `OperationsScreen.tsx` tab slug `"policy-install"`, label `"Policy installation"` (`?screen=operations&tab=policy-install`).
- **Top summary:** Displays counts per state (`INSTALLED`, `OUT_OF_DATE`, `NOT_INSTALLED`, `SAVED`, `UNSAVED`, `NOT_APPLICABLE`, `UNKNOWN`).
- **"Not built yet" notice:** Prominent banner stating that Phase 2 (Verify) and Phases 3–4 (Manual and Scheduled Policy Installation) are **Not built yet** (`DRAFT -- DO NOT FREEZE`), linking to `docs/design/OPERATIONS_POLICY_INSTALL_P1_MONITORING_CONTRACT_2026_11_18.md`.
- **Zero write/action buttons:** No button on the tab starts verification, installation, commit, approval, or scheduling.
- **Unified table columns:**
  1. Target (`hostname` + optional `context`)
  2. Vendor (`Check Point`, `Palo Alto`, `Cisco ASA`, `FortiGate`)
  3. Container / package (`container_name` / `package_name`)
  4. State (chip for `state`, plus HA parity indicator badge `checksums equal` / `checksums differ` / `checksums unknown` on `NOT_APPLICABLE` FortiGate rows)
  5. Installed policy name (`policy_name`, masked for `aiview`)
  6. Installed at (`installed_at`)
  7. Last change at (`last_change_at`)
  8. Reason (displayed with full text on hover via `title`)
