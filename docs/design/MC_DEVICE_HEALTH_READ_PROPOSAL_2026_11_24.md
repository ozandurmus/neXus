# Symantec Management Center — managed-device health read proposal

Status: DRAFT -- DO NOT FREEZE; proposes a read for PO literal approval; approves nothing

All candidate reads in this document are read-only (`ActionClass.CLASS_0_READ`, gate `action_class = 'read'`), sent over
the existing controlled HTTPS transport (`HttpsDeviceClient`, port `8082`, endpoint-pinned TLS, pre-emptive HTTP Basic
authentication with the enrolled Management Center's existing credential), bounded in size, with no retry
(`retry_rule = 'none'`), and excluded from raw job transcripts (`JobTranscriptScope.withoutRecording`). Every vendor
response is parsed in memory to a closed health-state enum, error/warning counts, check timestamp, and short vendor
error/warning text with addresses, hostnames, and identifiers masked; raw vendor JSON is discarded in memory and never
persisted (`AGENTS.md`, "Raw-evidence law" and "Mandatory AIView inspection & post-parsing masking law").

**Why (PO 2026-11-24).** On an enrolled Symantec Management Center (`MC-ALPHA-01`), a managed Reporter (`MD-BRAVO-02`)
had its Reporter application stopped for 3.5 hours. `MC-ALPHA-01` showed `MD-BRAVO-02` red on Device Health
(`"Unknown error"`, connection status failed), while Overwatch kept showing it as `"Fully managed"` (`FULLY_MANAGED`),
because Overwatch reads only `managementStatus` from `GET /api/devices` (`ManagementCenterDevices.java`). Overwatch must
read and show the Management Center's own health and error state per managed device (`ProxySG`, `Reporter`, `Cloud (WSS)`)
so that any device red on the Management Center is red in Overwatch too, and any unreadable state is `UNKNOWN`, never
`OK`.

| # | Vendor / context | Exact command | Purpose | Replaces / extends |
|---|---|---|---|---|
| C1 | Symantec Management Center (`bluecoat_management_center`, HTTPS `8082`) | `GET /api/devices/status` | **Recommended first:** read the MC's `StatusData` array (`monitorState`, `errors[]`, `warnings[]`, `checkDate`) for all registered managed devices (`sgos6x`, `rptr`, `cp`) in one request per inventory run | New; paired with existing `GET /api/devices` in MC inventory |
| C2 | Symantec Management Center (`bluecoat_management_center`, HTTPS `8082`) | `GET /api/devices/{uuid}/health` | Per-device alternative: read `DeviceHealthData` for one managed device `{uuid}` listed by `GET /api/devices` in the same run | New; bounded once per managed device per MC inventory run |
| C3 | Symantec Management Center (`bluecoat_management_center`, HTTPS `8082`) | `GET /api/devices/{uuid}/status` | Per-device `StatusData` read for `{uuid}` (existence as a distinct route in MC 4.1 is `UNKNOWN`) | New; per-device counterpart to `C1` if exposed |
| C4 | Symantec Management Center (`bluecoat_management_center`, HTTPS `8082`) | `GET /api/devices/health` | Fleet health summary endpoint behind the Device Health dashboard (exact response schema `UNKNOWN`) | New; fleet summary alternative/companion |

`{uuid}` in `C2` and `C3` is filled solely from the same run's `GET /api/devices` response (`ManagementCenterDevices.devices`)
and validated against `HttpsVendorPlan.isMcDeviceUuid` (`[0-9A-Fa-f-]{16,64}`) before the path is built; it never
originates from browser or free-text input.

---

## 1. Current state on `origin/main` (verified in source)

### 1.1 Management Center endpoints Overwatch already calls

All Symantec Management Center routes live in
`ui2/worker/src/main/java/com/securityexpert/nexus/ui2/worker/backup/https/HttpsVendorPlan.java` (default port
`MC_DEFAULT_PORT = 8082`, applied when an MC endpoint has no explicit port in `WorkerClaimLoop.java:298-303`,
`DiscoveryJobExecutor.java:225`, and `HttpsCertificateTrustRepository.java:34,94`) and are executed by
`HttpsVendorExecutor.java`. Every approved request is seeded into `gate_registry` by Flyway and mirrored in
`ui2/capability-registry/src/main/resources/capabilities/gate_registry_fixture.yaml`:

| Constant (`HttpsVendorPlan`) | HTTP method & path | Gate ID & migration (`gate_registry_fixture.yaml`) | Shell / transport / class | Timeout / cap / retry | Session reuse | How often it runs (`origin/main`) |
|---|---|---|---|---|---|---|
| `MC_DEVICES` | `GET /api/devices` | `bluecoat_mc_devices` (`V75`, line `1413`) | `not_applicable` / `HTTPS` / `read` | `30 s` (`SHORT`), `8 MiB` cap, `none` | `one client per run` | Once per MC confirm (`confirmManagementCenter`); once per MC inventory (`inventoryManagementCenter`); once per MC discovery run (`mcDeviceList`); once per MC backup job (`backupManagementCenter`); once per ProxySG confirm, inventory, backup, or configuration read (`mcEntry`) |
| `MC_SYSTEM_VERSION` | `GET /api/system/version` | `bluecoat_mc_system_version` (`V198`, line `5244`) | `not_applicable` / `HTTPS` / `read` | Gate `60 s` (called at `SHORT = 30 s` in `managementCenterIdentity`), `64 KiB` cap, `none` | `one client per run` | Once per MC confirm and once per MC inventory; when `LifecycleReadSwitch` enables `mc_system_version`, the existing response is observed in memory without issuing a second HTTP call (`HttpsVendorExecutor.java:586-595`) |
| `MC_DEVICE_LICENSE` | `GET /api/devices/{uuid}/license` | `bluecoat_mc_device_license` (`V198`, line `5227`) | `not_applicable` / `HTTPS` / `read` | `60 s` (`lifecycleGet`), `1 MiB` (`TEXT_MAX`), `none` | `one client per run` (`withoutRecording`) | Behind `LifecycleReadSwitch` (`Kind.MC_DEVICE_LICENSE`): once per managed device per MC inventory run, capped at `UI2_LIFECYCLE_MC_LICENSE_MAX` (default `1` in code; fleet-wide run recorded in `LIFECYCLE_READS_MEASUREMENTS_2026_11_10.md`) |
| `MC_DEVICE_COMMAND` | `PUT /api/devices/{uuid}/command` with body `"show version"` | `bluecoat_mc_command_show_version` (`V76`, line `1429`) | `config_mode_via_mc` / `HTTPS` / `read` | `120 s`, `64 MiB` cap, `none` | `one client per run` | Once per `ProxySG` (`type = sgos6x`) per MC or ProxySG backup job |
| `MC_DEVICE_COMMAND` | `PUT /api/devices/{uuid}/command` with body `"show configuration"` | `bluecoat_mc_command_show_configuration` (`V76`, line `1445`) | `config_mode_via_mc` / `HTTPS` / `read` | `120 s`, `64 MiB` cap, `none` | `one client per run` | Once per `ProxySG` per backup job, and once per `ProxySG` configuration collection (`configurationProxySgViaManagementCenter`) |
| `MC_DEVICE_COMMAND` | `PUT /api/devices/{uuid}/command` with body `"show interface all"` | `bluecoat_mc_command_show_interface_all` (`V82`, line `2357`) | `config_mode_via_mc` / `HTTPS` / `read` | `120 s`, `64 MiB` cap, `none` | `one client per run` | Once per enrolled `ProxySG` per ProxySG inventory run (`inventoryProxySgViaManagementCenter`) |
| `MC_DEVICE_COMMAND` | `PUT /api/devices/{uuid}/command` with body `"show ip-route-table"` | `bluecoat_mc_command_show_ip_route_table` (`V82`, line `2373`) | `config_mode_via_mc` / `HTTPS` / `read` | `120 s`, `64 MiB` cap, `none` | `one client per run` | Once per enrolled `ProxySG` per ProxySG inventory run (`inventoryProxySgViaManagementCenter`) |
| `MC_DEVICE_COMMAND` | `PUT /api/devices/{uuid}/command` with body `"show licenses"` | `bluecoat_mc_command_show_licenses` (`V199`, line `5363`) | `config_mode_via_mc` / `HTTPS` / `read` | `120 s`, `1 MiB` (`TEXT_MAX`), `none` | `one client per run` (`withoutRecording`) | Behind `LifecycleReadSwitch` (`Kind.MC_SHOW_LICENSES`): at most `UI2_LIFECYCLE_MC_LICENSE_MAX` ProxySGs per MC inventory run |

### 1.2 Session, authentication, and scheduling shape

- **Transport and session (`HttpsDeviceClient.java`):** Stateless HTTP over TLS on port `8082`. Each call sends
  pre-emptive HTTP Basic authentication (`Authorization: Basic ...`) from the credential store; no session cookie is
  stored across calls. `HttpsDeviceClient.client(target)` builds a fresh `java.net.http.HttpClient` with a `15 s` TCP/TLS
  connect timeout, endpoint-scoped leaf-certificate pinning (`CertificatePinningTrustManager`), and manual same-host/port
  `GET` redirect following up to `3` hops, closing the client in `finally` via `EndpointRuntime.closeHttpClients()`.
- **Cadence (`NightlyInventoryScheduler.java`, `JobWindowPolicy.java`):** Scheduled inventory runs at the four shared
  six-hour windows (`00:00`, `06:00`, `12:00`, `18:00` `Europe/Istanbul`, default `60`-minute window) when
  `ScheduledJobFamily.NIGHTLY_INVENTORY` (`V203`) is enabled. Manual runs by `security_admin` / `operation_admin` bypass
  the window (with business-hours confirmation `08:00–20:00`); `operator` and `aiview` manual runs keep the window.
  Scheduled backups run once daily at `02:00 Europe/Istanbul`.

### 1.3 Where Management Center facts land today

1. **Managed-device rows (`grid_member`, `V71` / `V72`):** `V71__infoblox_grid_members.sql` established rendering
   manager-listed members under their manager, and `V72__infoblox_grid_member_facts.sql` created `grid_member` (child of
   `device_inventory_run(run_id)`, written atomically in `JooqDeviceInventoryRepository.recordRun`). In
   `ManagementCenterDevices.parse(JsonNode)` (`ManagementCenterDevices.java:26-47`):
   - `name` -> `grid_member.host_name` (exposed in `InventoryController.toGridMemberBody` as `virtual_system`; masked as
     a `MD-...` / topology pseudonym for `aiview` by `PrivacyMaskingResponseBodyAdvice.java:248-251`).
   - `host` -> `grid_member.vip_address` (exposed as `address`, IP-masked for `aiview`).
   - `type` -> `grid_member.platform` via `typeLabel`: `"ProxySG"` (`sgos6x`/`sgos`), `"Reporter"` (`rptr`),
     `"Cloud (WSS)"` (`cp`).
   - `model` (fallback `platform`) -> `grid_member.hardware_type`.
   - `osVersion` (+ `" (build " + build + ")"`) -> `grid_member.hypervisor`.
   - `managementStatus` (uppercased, e.g. `"FULLY_MANAGED"`) -> **`grid_member.node_status`**. On the Devices screen
     (`ui2/frontend/src/screens/ManagedDevicesPanel.tsx:23-32,88`), `node_status` renders under the **Management**
     column via `managementWord` / `managementTone`: `UP`, `MANAGED`, `FULLY_MANAGED`, and `CONNECTED` render as a
     neutral grey chip (`"Fully managed"`).
   - `type` and `deploymentStatus` -> **`grid_member.services`** (stored as comma-joined `service=status` text in
     `JooqDeviceInventoryRepository.java:54`): `TYPE_<TYPE>=WORKING` (hard-coded `"WORKING"` whenever `type` is
     non-blank, not a health observation) plus `DEPLOYMENT=<DEPLOYMENT_STATUS>` (shown in the **Deployment** column of
     `ManagedDevicesPanel.tsx:43,89`).
   - `grid_master`, `master_candidate`, `ha_enabled` are `false`; `ha_status`, `replication`, and the four `*_percent`
     columns are `NULL`.
2. **MC identity (`device`):** `GET /api/system/version` refreshes `device` observed facts (`name`, model
   `"Symantec Management Center"`, version `<version> (build <buildNumber>)`) via `HttpsInventoryJobExecutor.java:157`.
3. **Managed-device licenses (`V200`):** `GET /api/devices/{uuid}/license` is parsed by `ManagementCenterLicenseParser`
   into `device_license_read` and `device_license_item` (`evidence_grade = 'MANAGER_REPORTED'`, `member_ref` = managed
   device name).
4. **Discovery (`BlueCoatDiscoveryCandidateMapper.java`):** Maps `GET /api/devices` to `discovery_candidate` rows using
   the same `managementStatus` field; only `sgos6x` is importable as an Overwatch device, while `rptr` and `cp` remain
   MC-listed managed devices shown under the MC's **Managed estate** tab.

### 1.4 Could the existing `GET /api/devices` answer already carry an ignored health field?

**Plainly: no, for the 16 fields measured on `GET /api/devices`.** `ManagementCenterDevices.java:15-17` records the
complete field set measured on the live Management Center on `2026-09-25`:

`applianceId`, `build`, `deploymentStatus`, `description`, `hardwareSerialNumber`, `host`, `lastChanged`,
`lastChangedBy`, `managementStatus`, `model`, `name`, `osVersion`, `platform`, `serialNumber`, `type`, `uuid`

This list matches the official `DeviceData` example for `GET /devices` and `GET /devices/{uuid}` in the Broadcom
Management Center 4.1 REST API reference field-for-field. **None of the 16 fields is a health state, error list, warning
list, or runtime connection status:** `managementStatus` (`FULLY_MANAGED`) describes whether the device is enrolled under
MC management, and `deploymentStatus` (`DEPLOYED`) describes provisioning lifecycle. Neither changed during the 3.5-hour
Reporter outage. Whether an undocumented query parameter on `/api/devices` could attach health properties is `UNKNOWN`
(none was found in official TechDocs excerpts, and altering the query string would require its own command-gate entry
under `AGENTS.md` regardless). A separate read-only `GET` endpoint is therefore required.

### 1.5 Management Center version handling in source and estate version

- `HttpsVendorExecutor.managementCenterIdentity` (`HttpsVendorExecutor.java:530-549`) reads `version` and `buildNumber`
  from `GET /api/system/version` and formats `<version> (build <buildNumber>)`.
- The enrolled Management Center on the estate (`MC-ALPHA-01`, count `1`) runs **`4.1.3.2 (build 305750)`**, managing
  `7` entries across `sgos6x` (`4` ProxySGs on `SGOS 7.4.15.1`), `rptr` (Reporter), and `cp` (WSS)
  (`docs/design/LIFECYCLE_SECURITY_CHECKS_DESIGN_2026_11_10.md` §2.2; `LIFECYCLE_READS_MEASUREMENTS_2026_11_10.md`
  line `166`).
- Neither `HttpsVendorPlan`, `HttpsVendorExecutor`, nor `ManagementCenterDevices` branches on the MC version: all MC
  REST calls use the unversioned `/api/...` base path, and `LifecycleKeys.ruled("BLUECOAT")` (`LifecycleKeys.java:59-64`)
  is `false` (exact-text matching). Official documentation research below therefore targets **Management Center 4.1**
  (and notes 3.3 where Broadcom TechDocs shares identical pages).

---

## 2. Candidate endpoints (official Broadcom / Symantec documentation)

**Documentation fetch disclosure (`AGENTS.md` vendor semantics law).** Direct outbound HTTP fetches from this worker
environment to `techdocs.broadcom.com` are blocked by the sandbox policy. Research used the environment's grounded web
search over `techdocs.broadcom.com` (Management Center 4.1 REST API reference and Administration Guide). Where grounded
search returned verbatim Swagger2Markup or Administration Guide text, those passages are quoted in §3 with confidence
`MEDIUM` (summarizing-tool retrieval; the full HTML pages and/or the appliance's built-in read-only API reference at
`https://<mc-host>:8082/help/api` must be read directly before freeze). Every field, enum value, or behaviour not backed
by a retrieved verbatim passage is marked **`UNKNOWN`** and is never filled from general model knowledge.

### 2.1 Candidate comparison matrix

| Attribute | C1: `GET /api/devices/status` *(Recommended)* | C2: `GET /api/devices/{uuid}/health` | C3: `GET /api/devices/{uuid}/status` | C4: `GET /api/devices/health` |
|---|---|---|---|---|
| **Scope** | Fleet collection (one `StatusData` entry per registered device) | Per managed device (`{uuid}` from `GET /api/devices`) | Per managed device (`{uuid}` from `GET /api/devices`) | Fleet health summary / collection |
| **Read/write class** | `CLASS_0_READ` (`GET`, `read`) | `CLASS_0_READ` (`GET`, `read`) | `CLASS_0_READ` (`GET`, `read`) | `CLASS_0_READ` (`GET`, `read`) |
| **Privilege needed** | `VIEW_DEVICE` (`targetType: DEVICE`, `READ`); sufficiency of existing MC account `UNKNOWN` until M0 | Same as `C1` | Same as `C1` | Same as `C1` |
| **Expected size / cap** | `< 32 KiB` for ~10 devices; cap `1 MiB` (`TEXT_MAX`) | `< 16 KiB` per device; cap `1 MiB` (`TEXT_MAX`) | `< 8 KiB` per device; cap `1 MiB` (`TEXT_MAX`) | `< 32 KiB`; cap `1 MiB` (`TEXT_MAX`) |
| **Documented schema** | `< StatusData >` array (verbatim Swagger2Markup example retrieved) | `DeviceHealthData` (schema name retrieved; exact field table `UNKNOWN`) | `UNKNOWN` (search summaries note `/devices/status` is the primary route; per-UUID route `UNKNOWN` in 4.1 WADL) | `UNKNOWN` (`DeviceHealthData` array vs dashboard counts `UNKNOWN`) |
| **Timeout / retry** | `30 s` / `none` | `30 s` / `none` | `30 s` / `none` | `30 s` / `none` |
| **Frequency** | Once per inventory run per enrolled MC (`1` call covers all managed devices) | Once per inventory run per managed device, bounded by `UI2_MC_DEVICE_HEALTH_MAX` | Once per inventory run per managed device, bounded by `UI2_MC_DEVICE_HEALTH_MAX` | Once per inventory run per enrolled MC |
| **Session reuse** | Existing `HttpsDeviceClient` (`one client per run`, Basic auth, `withoutRecording`) | Same as `C1` | Same as `C1` | Same as `C1` |

### 2.2 Candidate C1 — `GET /api/devices/status` (fleet status collection; recommended first)

- **Exact method and path:** `GET /api/devices/status` (no placeholders; MC base `/api` + documented path
  `/devices/status` on port `8082`).
- **Read/write class:** Read-only HTTP `GET` (`ActionClass.CLASS_0_READ`, gate `action_class = 'read'`).
- **Privilege / role needed:** Broadcom's REST API Best Practices (`best-practices-for-management-center-rest-api.html`)
  names `VIEW_DEVICE` (`targetType: DEVICE`, operation `READ`) for checking device health. The existing Overwatch MC
  account already succeeds on `GET /api/devices`, `GET /api/devices/{uuid}/license`, and `PUT /api/devices/{uuid}/command`;
  whether its role includes `GET /api/devices/status` without RBAC adjustment is **`UNKNOWN`** until the first M0 read
  (`401`/`403` fails closed to `UNKNOWN`).
- **Expected answer size:** JSON array with one `StatusData` object per registered device. On `MC-ALPHA-01` (`7` managed
  devices), expected `< 16 KiB` (`< 64 KiB` even with dozens of devices); bounded at `TEXT_MAX = 1 MiB` (`1,048,576` B).
- **Fields and documented meaning (`StatusData`, from `GET-devices-status.html` and `statusdata.html`):**
  - `uuid` (`string`): unique identifier of the managed device; joined by exact opaque string equality (`AGENTS.md`
    identity law) to the `uuid` returned by `GET /api/devices` in the same inventory run.
  - `name` (`string`): device display name in Management Center.
  - `monitorState` (`string`): monitoring status of the device; documented example `"MONITORED"`. Exhaustive enum values
    for deactivated / unmonitored / pre-deployed devices (for example whether Inactive is `"UNMONITORED"` or
    `"INACTIVE"`): **`UNKNOWN`**.
  - `errors` (`array` of `{messageId, message, properties}`): active error items on the device. `messageId` is a slash
    path code (documented example `"/BC/MC/CORE/GENERAL"`); `message` is human-readable error text (documented example
    `"Base license expired 2020-12-30"`); `properties` carries optional context or `"null"`.
  - `warnings` (`array` of `{messageId, message, properties}`): active warning items on the device, same element shape.
  - `checkDate` (`string`, date-time, documented example `"2021-01-15T15:12:23"`): timestamp when Management Center last
    checked the device's status. Timezone basis (UTC vs appliance-local wall clock): **`UNKNOWN`**.
  - `startDate` (`string`, date-time, documented example `"2021-01-12T05:22:03"`): timestamp when monitoring or the
    current state began (exact distinction: **`UNKNOWN`**).
  - **Health colour / state field:** No separate top-level colour field appears in the documented `StatusData` example.
    Whether MC `4.1.3.2` sends an additional state field or derives red/yellow/green/grey strictly from `monitorState`,
    `errors[]`, and `warnings[]` is **`UNKNOWN`** until verified in `/help/api` and M0.
  - **Connection status field:** No separate `connectionStatus` property appears in the documented `StatusData` example.
    Whether per-device connection failure (and `"Unknown error"` for a stopped Reporter) is reported inside `errors[]`
    (via `messageId` / `message`) or in an undocumented `StatusData` field is **`UNKNOWN`** until verified.
- **Timeout, retry, frequency, session reuse:** `30 s` timeout (`SHORT`), `retry_rule = 'none'`, executed **once per
  inventory run per enrolled Management Center** immediately after `GET /api/devices` using the same `HttpsDeviceClient`
  and credentials inside `JobTranscriptScope.withoutRecording`.
- **Unsupported behaviour by MC version:** Documented identically in MC 3.3 and MC 4.1 (`4.1.3.2` in estate). If the
  call returns non-2xx (`401`, `403`, `404`, `5xx`), times out, truncates at `1 MiB`, or fails JSON/schema parsing, the
  MC inventory job still records the device list from `GET /api/devices`, and every managed device's `health_state` is
  set to **`UNKNOWN`** (never `OK`). Any device in `GET /api/devices` absent from `StatusData[]` also gets **`UNKNOWN`**.
- **Secret-output risk:** `name` carries managed-device names; `errors[].message`, `warnings[].message`, and
  `properties` are vendor text that can contain IPv4/IPv6 addresses, FQDNs/hostnames, serial numbers, or usernames.
- **Sanitized projection kept:** Only:
  1. `health_state` enum (`ERROR` | `WARNING` | `OK` | `INACTIVE` | `UNKNOWN`),
  2. `error_count` and `warning_count` (`int >= 0`),
  3. up to `5` sanitized issue entries, each keeping only a validated vendor code (`messageId` matching
     `^/[A-Za-z0-9_./-]{1,80}$`, else omitted) and `message` truncated to `160` characters with IPv4/IPv6 literals,
     managed-device hostnames/addresses/serials from the same run, and `5+`-digit runs replaced by safe placeholders
     before storage (and passed through `PrivacyMaskingResponseBodyAdvice` for `aiview`),
  4. `health_checked_at` (`checkDate` validated against ISO local/offset date-time shape; never guessed).
  `properties`, `startDate`, and the raw JSON payload are discarded in memory.

### 2.3 Candidate C2 — `GET /api/devices/{uuid}/health` (per-device health metrics)

- **Exact method and path with placeholders:** `GET /api/devices/{uuid}/health`, where `{uuid}` is each managed device's
  UUID from the same run's `GET /api/devices` list, validated by `HttpsVendorPlan.isMcDeviceUuid` (`[0-9A-Fa-f-]{16,64}`).
- **Read/write class:** Read-only HTTP `GET` (`ActionClass.CLASS_0_READ`, gate `action_class = 'read'`).
- **Privilege / role needed:** `VIEW_DEVICE` (`targetType: DEVICE`, `READ`); sufficiency of the existing MC account is
  **`UNKNOWN`** until verified.
- **Expected answer size:** Single-device JSON (`DeviceHealthData`); expected `< 16 KiB` per device, capped at
  `TEXT_MAX = 1 MiB`.
- **Fields and documented meaning:** Grounded search over MC 4.1 TechDocs identifies the path `GET /devices/{uuid}/health`
  and response schema name `DeviceHealthData` (covering component health such as system resources, license validity, and
  user-defined/connectivity checks), **but no verbatim Swagger2Markup JSON example was retrieved** (search summaries
  produced conflicting synthetic field names). Consequently, exact field names and documented meanings for overall
  health colour/state, error and warning list, connection status, and last contact time are **`UNKNOWN`** until
  `techdocs.broadcom.com/.../management-center/4-1/api/` and `https://<mc-host>:8082/help/api` are read directly.
- **Timeout, retry, frequency, session reuse:** `30 s` timeout per call, `retry_rule = 'none'`, **once per inventory run
  per managed device**, bounded by a worker cap `UI2_MC_DEVICE_HEALTH_MAX` (modelled on `UI2_LIFECYCLE_MC_LICENSE_MAX`;
  `1` during M0 measurement, `50` default once enabled across the `7` managed devices), reusing `HttpsDeviceClient`
  (`one client per run`, `JobTranscriptScope.withoutRecording`).
- **Unsupported behaviour by MC version / device type:** Documented in MC 4.1 (`4.1.3.2`). Whether MC supports
  `/devices/{uuid}/health` for `rptr` (`Reporter` — the device type of the 2026-11-24 outage) and `cp` (`Cloud (WSS)`),
  or only for `sgos6x` (`ProxySG`), is **`UNKNOWN`**. Any `400`/`404`/`5xx`, timeout, cap skip, or unparseable body for
  one `{uuid}` sets that device's `health_state` to **`UNKNOWN`** (`UNSUPPORTED` only if an explicit documented vendor
  code proves non-applicability) without failing the inventory run or skipping remaining devices.
- **Secret-output risk & sanitized projection kept:** Component descriptions/messages may embed addresses, hostnames, or
  serials. Kept projection is identical to `C1` (closed `health_state` enum, counts, masked short code/text `<= 160`
  chars, check timestamp; raw JSON discarded in memory).

### 2.4 Candidate C3 — `GET /api/devices/{uuid}/status` (per-device status)

- **Exact method and path with placeholders:** `GET /api/devices/{uuid}/status` (`{uuid}` validated by
  `HttpsVendorPlan.isMcDeviceUuid`).
- **Read/write class / privilege / timeout / frequency / session reuse:** `CLASS_0_READ` (`GET`), `VIEW_DEVICE`
  (`UNKNOWN` until verified), `30 s` timeout, `1 MiB` cap, `retry_rule = 'none'`, once per inventory run per managed
  device (bounded by `UI2_MC_DEVICE_HEALTH_MAX`), `one client per run` (`withoutRecording`).
- **Fields, version support, secret risk, sanitized projection:** Search summaries conflict on whether MC 4.1 exposes
  `/devices/{uuid}/status` separately from `/devices/status` (`UNKNOWN` until checked in `/api/application.wadl` /
  `/help/api`). If present, its fields, secret-output risks, fail-closed `UNKNOWN` handling, and sanitized projection are
  identical to a single element of `C1` (`StatusData`), at the cost of *N* HTTP requests instead of `1`.

### 2.5 Candidate C4 — `GET /api/devices/health` (fleet health summary)

- **Exact method and path:** `GET /api/devices/health` (no placeholders).
- **Read/write class / privilege / timeout / frequency / session reuse:** `CLASS_0_READ` (`GET`), `VIEW_DEVICE`
  (`UNKNOWN` until verified), `30 s` timeout, `1 MiB` cap, `retry_rule = 'none'`, once per inventory run per enrolled
  MC, `one client per run` (`withoutRecording`).
- **Fields, version support, secret risk, sanitized projection:** Listed under MC 4.1 `/devices` paths in TechDocs
  summaries as the fleet health endpoint behind the Device Health dashboard, but its exact JSON schema (whether it
  returns per-device `uuid` + error messages or only aggregate circle-chart counts) is **`UNKNOWN`** until verified in
  `/help/api`. If per-device, same sanitized projection and fail-closed `UNKNOWN` rules as `C1`.

### 2.6 Endpoints inspected and excluded

| Endpoint | Documented purpose | Why excluded |
|---|---|---|
| `GET /api/devices/connection` and `GET /api/devices/{uuid}/connection` | `DeviceConnectionData`: returns `uuid`, `name`, `host`, and base64 `hostKey` ("Returns the host key that is used to attach to this device") | Exposes SSH host-key material while carrying no documented health colour, `errors[]`, or `warnings[]` list |
| `GET /api/alerts` | System/notification alert log (`NotificationAlert` paging object) | Event log rather than current per-device state; depends on alert settings ("Raise alerts on device errors") and cannot distinguish `OK` from `Inactive` |
| `PUT /api/devices/{uuid}/command` | Runs a CLI literal on a ProxySG at `#(config)` | ProxySG-only (`sgos6x`), cannot query Reporter (`rptr`) or WSS (`cp`), and fails when MC cannot reach the device instead of reading MC's Device Health verdict |

---

## 3. Load-bearing semantics and official evidence

Confidence levels: `HIGH` = verbatim official page read directly in full; `MEDIUM` = verbatim official sentence or
Swagger2Markup block retrieved via grounded search over `techdocs.broadcom.com` (full page must be re-read directly
before freeze); `LOW` = indirect official mention only; `UNKNOWN` = not established from retrieved official text.

| # | Load-bearing semantic | Quoted official sentence (or `NOT_FOUND`) | Official URL(s) | MC version | Confidence |
|---|---|---|---|---|---|
| S1 | What health colours `Error`, `Warning`, and `OK` mean | `"Device health is always represented by status colors: Error (red), Warning (yellow), and OK (green)."` and `"A device's health status is determined by system-defined thresholds on the device: if a service or other monitored component exceeds a threshold, the device goes into a Warning or Error state."` | `https://techdocs.broadcom.com/us/en/symantec-security-software/web-and-network-security/management-center/4-1/add-and-monitor-devices-in-management-center/monitor-device-health-on-mc.html` (also `/3-3/Monitor-Device-Health-on-MC.html`) | `4.1` (`4.1.3.2` in estate), `3.3` | `MEDIUM` |
| S2 | What `Inactive` (grey) means | `"A gray status color indicates an absence of health status and represents an Inactive device. Some jobs and operations cannot occur on inactive or pre-deployed devices."` | `https://techdocs.broadcom.com/us/en/symantec-security-software/web-and-network-security/management-center/4-1/add-and-monitor-devices-in-management-center/monitor-device-health-on-mc.html` | `4.1` | `MEDIUM` (UI semantic); `UNKNOWN` (exact API enum token) |
| S3 | What the Device Health dashboard aggregates | `"Symantec Management Center collects health status information on device components including system resources, license validity, and user-defined health checks, and displays the aggregate health status in several areas."` and `"The Device Health widget gives an overall picture of the health of monitored devices in a circle graph."` / `"The Top Problem Devices widget lists the devices that are consistently displaying with errors or warnings."` | `https://techdocs.broadcom.com/us/en/symantec-security-software/web-and-network-security/management-center/4-1/add-and-monitor-devices-in-management-center/monitor-device-health-on-mc.html` | `4.1` | `MEDIUM` |
| S4 | `GET /api/devices/status` contract and `StatusData` fields | `"Return the status of each device registered with the system."` Response `200`: `"List of statuses"`, schema `< StatusData > array` with `uuid`, `name`, `checkDate`, `startDate`, `warnings` (`{messageId, message, properties}`), `errors` (`{messageId, message, properties}`), `monitorState` (`"MONITORED"`). | `https://techdocs.broadcom.com/us/en/symantec-security-software/web-and-network-security/management-center/4-1/api/Paths/GET-devices-status.html` and `https://techdocs.broadcom.com/us/en/symantec-security-software/web-and-network-security/management-center/4-1/mc-rest-api/definitions/statusdata.html` (also `/3-3/management-center-3-3-5-1/mc-rest-api/paths/get-devices-status.html`) | `4.1`, `3.3` | `MEDIUM` |
| S5 | Exact rule mapping REST API fields (`StatusData` / `DeviceHealthData`) to `ERROR` / `WARNING` / `OK` / `INACTIVE`, and all `monitorState` enum literals | `NOT_FOUND` in retrieved excerpts. (Whether `monitorState == "MONITORED"` with non-empty `errors[]` is the exact condition for red `Error`, non-empty `warnings[]` for yellow `Warning`, both empty for green `OK`, and which `monitorState` literal marks grey `Inactive` is not stated verbatim in retrieved text.) | `https://techdocs.broadcom.com/us/en/symantec-security-software/web-and-network-security/management-center/4-1/mc-rest-api/definitions/statusdata.html` and on-box `https://<mc-host>:8082/help/api` | `4.1` | `UNKNOWN` |
| S6 | Whether connection failure (and Reporter stopped `"Unknown error"`) is reported per device in the REST API, and in which endpoint/field | `NOT_FOUND` in retrieved REST API excerpts. (The 2026-11-24 incident proves MC `4.1.3.2`'s UI shows a stopped Reporter red with `"Unknown error"` and failed connection status; which REST field in `GET /api/devices/status` or `GET /api/devices/{uuid}/health` carries both facts is unverified.) | `https://techdocs.broadcom.com/us/en/symantec-security-software/web-and-network-security/management-center/4-1/api/Paths/GET-devices-status.html` and on-box `https://<mc-host>:8082/help/api` | `4.1` | `UNKNOWN` |
| S7 | Exact schema and device-type coverage (`sgos6x`, `rptr`, `cp`) of `GET /api/devices/{uuid}/health`, `GET /api/devices/health`, and `GET /api/devices/{uuid}/status` | `"Return health metrics for a specific device"` / schema name `DeviceHealthData` reported in search summaries; verbatim field table and per-type (`rptr`, `cp`) behaviour `NOT_FOUND`. | `https://techdocs.broadcom.com/us/en/symantec-security-software/web-and-network-security/management-center/4-1/management-center-rest-api.html` and on-box `https://<mc-host>:8082/help/api`, `https://<mc-host>:8082/api/application.wadl` | `4.1` | `UNKNOWN` |
| S8 | Whether the REST answer is cached by the MC vs polled live on `GET`, and how fresh it is | `"Configure how often Management Center polls devices for statistics and health monitoring data."` / `"In the Device Polling Interval field, specify the frequency (in minutes) at which Management Center should poll connected devices."` (`StatusData` carries `checkDate`). Default interval in minutes, `checkDate` timezone, and an explicit guarantee that `GET` reads cached poll state without contacting managed devices: `NOT_FOUND`. | `https://techdocs.broadcom.com/us/en/symantec-security-software/web-security/management-center/3-3/administering-management-center/configure-general-system-settings/set-the-device-polling-interval.html` and `https://techdocs.broadcom.com/us/en/symantec-security-software/web-and-network-security/management-center/4-1/add-and-monitor-devices-in-management-center/add-a-device-to-mc.html` | `4.1`, `3.3` | `MEDIUM` (periodic MC polling + `checkDate`); `UNKNOWN` (default minutes, timezone, zero-live-poll guarantee) |
| S9 | REST API base path (`/api` on `8082`) and RBAC permission for a health/status read | `"All REST API URLs use the base parent address: https://<mc-ip-address>:8082/api."` and `"Define targetType (such as, DEVICE, POLICY) and operation constraints (such as, ANY, READ) within the user PermissionData schema. Example: A script checking device health should have VIEW_DEVICE permissions, but not MANAGE_POLICY."` | `https://techdocs.broadcom.com/us/en/symantec-security-software/web-and-network-security/management-center/4-1/management-center-rest-api.html` and `https://techdocs.broadcom.com/us/en/symantec-security-software/web-and-network-security/management-center/4-1/best-practices-for-management-center-rest-api.html` | `4.1` | `MEDIUM` |

### 3.1 Exact URLs that must be read directly before the Product Owner decides

Because this sandbox could not fetch raw HTML pages directly, an engineer or the Product Owner with browser access must
read the following official pages (and/or the read-only on-box Swagger/WADL pages already cited in
`VENDOR_BACKUP_CONTRACTS_2026_09_22.md` §8a, which issue no device command) to confirm `S5`–`S8` before freezing:

1. `https://techdocs.broadcom.com/us/en/symantec-security-software/web-and-network-security/management-center/4-1/management-center-rest-api.html`
2. `https://techdocs.broadcom.com/us/en/symantec-security-software/web-and-network-security/management-center/4-1/best-practices-for-management-center-rest-api.html`
3. `https://techdocs.broadcom.com/us/en/symantec-security-software/web-and-network-security/management-center/4-1/api/Paths/GET-devices-status.html`
4. `https://techdocs.broadcom.com/us/en/symantec-security-software/web-and-network-security/management-center/4-1/mc-rest-api/definitions/statusdata.html`
5. `https://techdocs.broadcom.com/us/en/symantec-security-software/web-and-network-security/management-center/4-1/add-and-monitor-devices-in-management-center/monitor-device-health-on-mc.html`
6. `https://techdocs.broadcom.com/us/en/symantec-security-software/web-and-network-security/management-center/4-1/add-and-monitor-devices-in-management-center/add-a-device-to-mc.html`
7. On-box MC `4.1.3.2` documentation (read-only static docs on `MC-ALPHA-01`, no managed-device contact):
   `https://<mc-host>:8082/help/api` and `https://<mc-host>:8082/api/application.wadl` — specifically comparing
   `/devices/status`, `/devices/{uuid}/status`, `/devices/health`, and `/devices/{uuid}/health`.

---

## 4. Storage and screen sketch (proposal only; nothing written)

### 4.1 Storage (`grid_member` extension; migration number named only)

- **Why existing `grid_member` columns are not overloaded:** `grid_member.node_status` already stores `managementStatus`
  (`FULLY_MANAGED`), which is a separate management-plane fact (`AGENTS.md`: configuration/management intent != runtime
  health; never collapse distinct facts). `grid_member.services` is serialized in `JooqDeviceInventoryRepository.java:54`
  as comma-joined `service=status` tokens and cannot safely hold vendor error strings containing `,` or `=`. (In
  addition, `ManagementCenterDevices.parse` currently writes synthetic `TYPE_<TYPE>=WORKING` into `services`; while
  `ManagedDevicesPanel.tsx` ignores `TYPE_*`, implementation should ensure no consumer mistakes `"WORKING"` for health.)
- **Proposed schema change (`V268` — named only; repository head on `origin/main` is `V267`, renumber if another lane
  claims `V268` first):** Add six nullable columns to `grid_member` (keeping atomic single-transaction writes in
  `JooqDeviceInventoryRepository.recordRun` and keeping existing `V242`/`V261` table grants for `inventory`,
  `worker_general`, and `service`; Infoblox and FortiManager rows leave these columns `NULL`):
  - `health_state TEXT CHECK (health_state IS NULL OR health_state IN ('ERROR', 'WARNING', 'OK', 'INACTIVE', 'UNKNOWN'))`
  - `connection_state TEXT` — closed token when reported separately (for example `CONNECTED` / `FAILED` / `UNKNOWN`),
    else `NULL`
  - `error_count INTEGER CHECK (error_count IS NULL OR error_count >= 0)`
  - `warning_count INTEGER CHECK (warning_count IS NULL OR warning_count >= 0)`
  - `health_summary TEXT` — sanitized, pre-scrubbed summary (`<= 5` codes/messages joined cleanly, max `512` chars; no
    raw JSON, no `properties` blob)
  - `health_checked_at TEXT` — MC's `checkDate` string (`<= 32` chars; promoted to `TIMESTAMPTZ` only if §3 `S8` proves
    MC sends an unambiguous timezone/UTC instant)

### 4.2 Devices screen under the Management Center (`ManagedDevicesPanel.tsx`)

Under `Devices > MC-ALPHA-01 > Managed estate` (`ui2/frontend/src/screens/ManagedDevicesPanel.tsx`), keep the existing
**Management** column (`Fully managed` from `managementStatus`) so operators see *both* "enrolled in MC" and "currently
failing", and add a **Health** column (plus a detail line when one managed device is selected):

| Device | Address | Type | Model | OS version | Health | Management | Deployment |
|---|---|---|---|---|---|---|---|
| `MD-BRAVO-02` | `192.0.2.15` | Reporter | Reporter VA | 11.1.x | **`Error`** · `Unknown error` | `Fully managed` | `Deployed` |
| `FW-TANGO-04` | `192.0.2.20` | ProxySG | S200 | SGOS 7.4.15.1 | **`OK`** | `Fully managed` | `Deployed` |
| `MD-CHARLIE-03` | `192.0.2.25` | Cloud (WSS) | WSS | — | **`Inactive`** | `Fully managed` | `Deployed` |

- **Chip mapping (`StatusChip`):**
  - `ERROR` -> `tone="err"` (red), label `"Error"`
  - `WARNING` -> `tone="warn"` (amber), label `"Warning"`
  - `OK` -> `tone="ok"` (green), label `"OK"`
  - `INACTIVE` -> `tone="neutral"` (grey), label `"Inactive"`
  - `UNKNOWN` -> `tone="warn"` (amber, never green `"ok"`), label `"UNKNOWN"`
- **Error text & provenance:** The cell shows the chip and, when `error_count > 0` or `warning_count > 0`, the count and
  first masked vendor message (full masked summary and `health_checked_at` age on hover / selected-device header),
  labelled `manager-reported` (`MANAGER_REPORTED`, never presented as a direct device read).
- **Two-layer masking (`AGENTS.md` AIView law):**
  1. **Before database write (worker parser):** Strip/replace IPv4 and IPv6 literals, any `name`, `host`,
     `serialNumber`, or `hardwareSerialNumber` value appearing in the same run's `GET /api/devices` list, email-like
     tokens, and digit runs of `5+` digits (preserving `yyyy-MM-dd` dates).
  2. **On API response (`PrivacyMaskingResponseBodyAdvice`):** Pass `health_summary` through `TopologyNamePseudonymizer`
     so `aiview` sees only deterministic pseudonyms (`MC-ALPHA-01`, `MD-BRAVO-02`, `FW-TANGO-04`, `192.0.2.x`).

### 4.3 Fail-closed rule: an unreadable MC or health read is `UNKNOWN`, never `OK`

1. **Health endpoint fails while `GET /api/devices` succeeds:** HTTP `401`/`403`/`404`/`5xx`, timeout, truncation, or
   unparsed JSON sets `health_state = 'UNKNOWN'` on every affected `grid_member` row (with a closed reason code in
   `health_summary`, e.g. `HEALTH_READ_FAILED`), **never `OK`** and never falling back to `FULLY_MANAGED`.
2. **Device missing from health reply or skipped by cap:** Any `uuid` present in `GET /api/devices` but absent from the
   health/status response (or beyond `UI2_MC_DEVICE_HEALTH_MAX`) gets `health_state = 'UNKNOWN'`, **never `OK`**.
3. **Entire Management Center (`MC-ALPHA-01`) cannot be read:** `HttpsInventoryJobExecutor` marks the inventory job
   `FAILED` (`connect_failed` / `authentication_failed`). When the latest MC inventory job failed, when `health_state`
   is `NULL` (runs recorded before the feature or with the switch off), or when `collected_at` is older than the
   freshness bound (`24 h`, matching `DiagnosticService.MAX_INVENTORY_AGE`), the UI renders member health as
   **`UNKNOWN`** (with the failed/stale reason), **never `OK`**.
4. **Enrolled ProxySG standalone row:** An imported `ProxySG` whose own inventory runs via `MC-ALPHA-01` is not marked
   healthy from `MC-ALPHA-01`'s `managementStatus`; if surfaced on the ProxySG dossier later, its manager-reported
   health follows the same `UNKNOWN` fail-closed rule.

---

## 5. Recommendation and Product Owner decision

### 5.1 Recommendation

Recommend **Candidate C1 (`GET /api/devices/status`) first**, verified against `/help/api` (§3.1) and executed first as a
one-run shape-only measurement (`M0`, behind an off-by-default worker switch logging field names and value types only,
per `AGENTS.md` 2026-09-26 amendment, storing nothing until the measured shape freezes this contract):

- **Why `C1` (`GET /api/devices/status`) first:**
  - *Pros:* Only candidate with a corroborated verbatim Swagger2Markup specification and `StatusData` schema (`uuid`,
    `name`, `checkDate`, `warnings[]`, `errors[]`, `monitorState`) in Broadcom TechDocs; issues **1 HTTP `GET` per MC
    inventory run** instead of *N* per-UUID requests (`AGENTS.md`: stability over collection speed; minimal polling);
    covers all device types (`sgos6x`, `rptr`, `cp`) in one response; carries vendor `errors[].message` and `messageId`
    directly.
  - *Cons:* Documented example shows no separate `overallHealth` colour enum or explicit `connectionStatus` field; §3.1
    check + `M0` must confirm how `MC-ALPHA-01` (`4.1.3.2`) represents `"Unknown error"` and failed connection status in
    `StatusData`.
- **Other candidates — pros and cons:**
  - **`C2` (`GET /api/devices/{uuid}/health`):** *Pros:* Per-device `DeviceHealthData`, natural per-UUID cap, may carry
    component-level connectivity/resource statuses if `C1` omits them. *Cons:* Exact schema and support for `rptr`
    (Reporter) and `cp` (WSS) are `UNKNOWN` today; sends *N* sequential requests per run instead of `1`. Promote `C2`
    ahead of `C1` **only if** reading `/help/api` shows `C1` lacks the Reporter health/connection error.
  - **`C3` (`GET /api/devices/{uuid}/status`):** *Pros:* Single-device `StatusData`. *Cons:* Route existence in MC 4.1
    is `UNKNOWN`, and it multiplies request count by *N* for the same `StatusData` record `C1` returns in one call.
  - **`C4` (`GET /api/devices/health`):** *Pros:* Single call per MC run. *Cons:* Exact schema `UNKNOWN`; may return
    dashboard aggregate counts without per-device `uuid` and error text.

### 5.2 Exact Product Owner question

After reading the URLs in §3.1 to confirm whether `GET /api/devices/status` (`C1`, recommended; sent once per inventory
run as a single collection `GET` covering every managed device, so at most once per managed device) or per-device
`GET /api/devices/{uuid}/health` (`C2`) carries the Reporter health and connection-status failure on MC `4.1.3.2`, ask
the Product Owner literally:

- For recommended **C1** (one collection `GET` per MC inventory run covering each managed device):
  **"approve GET /api/devices/status on each enrolled Management Center, once per inventory run per managed device, read-only"**
  *(executed as a single `GET /api/devices/status` request per Management Center inventory run covering all managed
  devices, never *N* requests)*.
- Or, if §3.1 shows per-device **C2** is required instead:
  **"approve GET /api/devices/{uuid}/health on each enrolled Management Center, once per inventory run per managed device, read-only"**.
