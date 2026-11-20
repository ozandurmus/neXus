# Check Point VSX Configuration Collection Without VS Context — Findings and Command Gate Proposal (`cp_vsx_config_collected_without_vs_context`)

## Status

**DRAFT — FINDINGS AND PO DECISION PROPOSAL, 2026-11-20. NOT IMPLEMENTATION AUTHORITY.**
Prepared for backlog item `cp_vsx_config_collected_without_vs_context`. No network device or host was contacted, and no command, gate row, migration, or collector behavior was modified in this lane because the required per-virtual-system (and `vsenv 0`) configuration literals do not exist as `SIGNED_OFF` rows in `ui2/capability-registry/src/main/resources/capabilities/gate_registry_fixture.yaml` and are currently prohibited by frozen `docs/design/PO_DECISION_RECORD_2026_09_14G_CONFIGURATION_COLLECTION_MEASURED_FORMS.md` §1 (`CG-1`).

---

## 1. Executive Summary

1. **All four Check Point configuration reads run bare without `vsenv 0`, `vsenv <VSID>`, or a Clish `set virtual-system <VSID>` context switch.** `ConfigurationReadPlan` and `ConfigurationCapabilityExecutor.collectCheckPoint` execute `clish -c 'show hostname'`, `clish -c 'show version all'`, `clish -c 'cpstat os -f hw_info'`, and `clish -c 'show configuration'` directly over `SSH_EXEC` in the landing shell without probing `vsx stat -v` or selecting a virtual-system context.
2. **Evidence problem today:**
   - **Virtual-system device rows (`devices.virtual_system_ref` present):** When a Check Point virtual system imported from Discovery (`STANDALONE_VIRTUAL_SYSTEM` K-4 or `VIRTUAL_SYSTEM_MEMBER` K-9) runs `configuration_collect`, `WorkerClaimLoop` and `ConfigurationJobExecutor` do not pass `virtual_system_ref` into `ConfigurationRequest` or `ConfigurationCapabilityExecutor`. The collector connects to the VSX host's management address, collects the VS0/chassis Gaia configuration and VS0 hostname, overwrites the virtual-system device row's `observed_hostname` with the VS0 chassis hostname, stores `device_configuration_index` rows under `context = "physical"`, and records a `backup_artefact` manifest row explicitly attributing the VS0/chassis configuration artefact to the virtual system's `virtual_system_ref`.
   - **VSX gateway/member device rows:** Unlike `InventoryCapabilityExecutor` (which probes `vsx stat -v`, wraps physical reads in `bash -lc 'vsenv 0 && <read>'`, and collects per-VS interfaces and routes under `bash -lc 'vsenv <VSID> && <read>'` keyed by `context = "<VSID>"`), `ConfigurationCapabilityExecutor` performs a single unscoped `clish -c 'show configuration'` read and hardcodes `context = "physical"` for every index entry. Furthermore, `CheckPointGaiaConfigParser.extractCanonicalSetLines` strips `set virtual-system <digits>` lines (`VSX_SELECTOR_RE`) instead of switching the active parse context, attributing all `set` lines in a multi-context stream to `"physical"`.
   - **Landing-context hazard on VSX:** As measured in `docs/design/CP_INVENTORY_MEASUREMENT_FINDINGS_2026_09_14.md` §8, a fresh SSH session to a VSX gateway can land in a non-zero virtual-system context (e.g., prompt `:3`). Because `ConfigurationReadPlan` does not wrap host-level reads in `bash -lc 'vsenv 0 && ...'`, Expert-level side effects or subcommands invoked without an explicit context switch are not guaranteed to execute in `VS0`.
3. **Gate registry verdict:** Zero `vsenv 0`, `vsenv <VSID>`, or `set virtual-system <VSID>` gate rows exist for Check Point configuration collection in `gate_registry_fixture.yaml` (or migrations `V1`..`V253`). Implementation is therefore blocked pending Product Owner measurement/sign-off of the exact literals and a successor decision record to `14G` `CG-1`.

---

## 2. Exact Configuration Reads Running on a VSX Gateway Today

In [`ConfigurationReadPlan.java`](file:///Users/OzanDur/Codo/nexus-q-08-cp-vsx-config-vs-context/ui2/worker/src/main/java/com/securityexpert/nexus/ui2/worker/configuration/ConfigurationReadPlan.java#L33-L40), [`ConfigurationCapabilityExecutor.java`](file:///Users/OzanDur/Codo/nexus-q-08-cp-vsx-config-vs-context/ui2/worker/src/main/java/com/securityexpert/nexus/ui2/worker/configuration/ConfigurationCapabilityExecutor.java#L177-L186), [`ConfigurationCapabilities.java`](file:///Users/OzanDur/Codo/nexus-q-08-cp-vsx-config-vs-context/ui2/worker/src/main/java/com/securityexpert/nexus/ui2/worker/configuration/ConfigurationCapabilities.java#L32-L43), [`DeviceCompositionConfiguration.java`](file:///Users/OzanDur/Codo/nexus-q-08-cp-vsx-config-vs-context/ui2/service/src/main/java/com/securityexpert/nexus/ui2/service/boot/DeviceCompositionConfiguration.java#L388-L403), and [`cp_configuration_collect.yaml`](file:///Users/OzanDur/Codo/nexus-q-08-cp-vsx-config-vs-context/ui2/capability-registry/src/main/resources/capabilities/cp_configuration_collect.yaml#L26-L45), every Check Point configuration collection run issues the following closed literal set over `SSH_EXEC` (`shell_context: expert`):

| Order | Constant in `ConfigurationReadPlan` | Exact Literal Sent (`canonical_command_key`) | Existing `gate_id` in `gate_registry_fixture.yaml` | Timeout | `vsenv` / VS Context Applied? |
| --- | --- | --- | --- | --- | --- |
| 1 | `CP_SHOW_HOSTNAME` | `clish -c 'show hostname'` | `cp_configuration_show_hostname` | 30 s | **No** (bare `clish -c`) |
| 2 | `CP_SHOW_VERSION_ALL` | `clish -c 'show version all'` | `cp_configuration_show_version_all` | 30 s | **No** (bare `clish -c`) |
| 3 | `CP_CPSTAT_OS_HW_INFO` | `clish -c 'cpstat os -f hw_info'` | `cp_configuration_cpstat_os_hw_info` | 30 s | **No** (bare `clish -c`) |
| 4 | `CP_SHOW_CONFIGURATION` | `clish -c 'show configuration'` | `cp_configuration_show_configuration` | 60 s | **No** (bare `clish -c`; `session_reuse_rule` explicitly states `"no per-virtual-system repetition"`) |

Additionally:
- `vsx stat -v` (`InventoryReadPlan.CP_VSX_STAT`, gated under `cp_inventory_vsx_stat_v` for inventory runs) is **never issued** by `ConfigurationCapabilityExecutor`.

---

## 3. Detailed Evidence Problem Today

### 3.1 Virtual-system device rows (`devices.virtual_system_ref` set) receive VS0/chassis evidence

When Check Point Discovery enumerates a VSX estate (`docs/design/CP_AND_VSX_DISCOVERY_CONTRACT.md` K-4 `STANDALONE_VIRTUAL_SYSTEM`, K-9 `VIRTUAL_SYSTEM_MEMBER`), [`DiscoveryRunService.java`](file:///Users/OzanDur/Codo/nexus-q-08-cp-vsx-config-vs-context/ui2/service/src/main/java/com/securityexpert/nexus/ui2/service/discovery/DiscoveryRunService.java#L313-L322) imports an individual virtual system as a `devices` row with:
- `role = "gateway"`,
- `virtual_system_ref = Optional.of(candidate.stableIdentifier())`, and
- `endpointAddress(candidate) = candidate.managementAddress()` (the physical VSX host/member management IP).

When `configuration_collect` runs against that `targetDeviceId`:
1. **Context modifier dropped at dispatch:** [`WorkerClaimLoop.java`](file:///Users/OzanDur/Codo/nexus-q-08-cp-vsx-config-vs-context/ui2/worker/src/main/java/com/securityexpert/nexus/ui2/worker/confirm/WorkerClaimLoop.java#L822-L827) calls `buildConfigurationRequest(job.capabilityId(), endpoint.endpointId(), endpoint.addressRef(), device.credentialReferenceId())`. Neither `device.virtualSystemRef()` nor any VSID is included in [`ConfigurationRequest`](file:///Users/OzanDur/Codo/nexus-q-08-cp-vsx-config-vs-context/ui2/worker/src/main/java/com/securityexpert/nexus/ui2/worker/configuration/ConfigurationRequest.java).
2. **Unscoped read on the VSX host:** `ConfigurationCapabilityExecutor.collectCheckPoint` connects to the VSX host's management IP and runs the four bare `clish -c '...'` reads in the default Clish context (`VS0`).
3. **False identity attribution:** [`ConfigurationJobExecutor.java`](file:///Users/OzanDur/Codo/nexus-q-08-cp-vsx-config-vs-context/ui2/worker/src/main/java/com/securityexpert/nexus/ui2/worker/configuration/ConfigurationJobExecutor.java#L157-L169) calls `deviceRepository.refreshObservedFacts(targetDeviceId, completed.identity().hostname(), Optional.empty(), completed.identity().softwareVersion(), ...)`. Because `clish -c 'show hostname'` reports the VS0 physical host's Gaia hostname, the virtual system's `devices.observed_hostname` is overwritten with the physical chassis hostname.
4. **False configuration and backup-manifest attribution:**
   - `device_configuration_run` and `configuration_artefact` store the VS0/chassis Gaia configuration under the virtual system's `device_id`.
   - `device_configuration_index` stores the VS0 section counts under `context = "physical"` ([`CheckPointGaiaConfigProcessor.java`](file:///Users/OzanDur/Codo/nexus-q-08-cp-vsx-config-vs-context/ui2/worker/src/main/java/com/securityexpert/nexus/ui2/worker/configuration/cp/CheckPointGaiaConfigProcessor.java#L35-L53), [`ConfigurationServiceClient.java`](file:///Users/OzanDur/Codo/nexus-q-08-cp-vsx-config-vs-context/ui2/worker/src/main/java/com/securityexpert/nexus/ui2/worker/configuration/server/ConfigurationServiceClient.java#L84-L90)).
   - [`ConfigurationJobExecutor.recordBackupArtefactManifest`](file:///Users/OzanDur/Codo/nexus-q-08-cp-vsx-config-vs-context/ui2/worker/src/main/java/com/securityexpert/nexus/ui2/worker/configuration/ConfigurationJobExecutor.java#L241-L258) reads `virtualSystemRef = confirmFacts.flatMap(DeviceConfirmFacts::virtualSystemRef)` and writes a `backup_artefact` row (`artefact_class = "configuration"`) with both `device_id = targetDeviceId` and `virtual_system_ref = virtualSystemRef` — explicitly certifying the VS0/chassis configuration snapshot as belonging to that virtual system.

### 3.2 Physical VSX gateway rows lack per-VS configuration context and strip `set virtual-system <VSID>`

For a physical VSX gateway member row:
1. **Parser strips `set virtual-system <VSID>` selectors:** In [`CheckPointGaiaConfigParser.java`](file:///Users/OzanDur/Codo/nexus-q-08-cp-vsx-config-vs-context/ui2/worker/src/main/java/com/securityexpert/nexus/ui2/worker/configuration/cp/CheckPointGaiaConfigParser.java#L91-L93), `VSX_SELECTOR_RE` matches `(?i)^set\s+virtual-system\s+\d+$`, and `extractCanonicalSetLines` (lines 227–229) discards those lines (`continue;`). If a Gaia `show configuration` stream includes `set virtual-system <VSID>` blocks, all subsequent `set interface`, `set static-route`, `set ospf`, or `set bgp` lines are collapsed into the single caller-supplied context (`"physical"`), both corrupting the `"physical"` section counts/settings and losing the per-VS attribution.
2. **No per-VS read loop:** When per-VS Gaia Clish settings are not emitted in a top-level `clish -c 'show configuration'` call, `ConfigurationCapabilityExecutor` never enumerates non-zero VSIDs via `vsx stat -v` and never queries each virtual system's context.

### 3.3 Why `bash -lc 'vsenv <VSID> && clish -c "show configuration"'` alone did not switch Clish context in the 2026-09-14 measurement

Per [`docs/design/CP_CONFIGURATION_MEASUREMENT_FINDINGS_2026_09_14.md`](file:///Users/OzanDur/Codo/nexus-q-08-cp-vsx-config-vs-context/docs/design/CP_CONFIGURATION_MEASUREMENT_FINDINGS_2026_09_14.md#L37-L52) §3:
- `vsenv <VSID>` is an **Expert-shell** shell function that switches the Expert environment/namespace for Check Point binaries (`fw`, `cphaprob`, `ip`, `cpstat`).
- Spawning `clish -c 'show configuration'` from inside `bash -lc 'vsenv <VSID> && ...'` starts a new **Gaia Clish** process whose internal Clish virtual-system context still defaults to `0` (`VS0`), which is why `bash -lc 'vsenv 3 && clish -c "show configuration"'` returned the exact same 235 `set` lines as `VS0` (+1 `Context is set to Virtual Device … (ID 3).` banner line from `vsenv`).
- In Gaia Clish, switching Clish to a virtual system requires executing `set virtual-system <VSID>` and `show configuration` **within the same `clish` process** (see [`docs/design/DISCOVERY_VS_COLLECTION_PYTHON_KNOW_HOW_AUDIT_2026_09_12.md`](file:///Users/OzanDur/Codo/nexus-q-08-cp-vsx-config-vs-context/docs/design/DISCOVERY_VS_COLLECTION_PYTHON_KNOW_HOW_AUDIT_2026_09_12.md#L388-L407) §4.3). In the 2026-09-14 measurement script (`scripts/measurement/cp_config_shape.sh`), `printf 'set virtual-system %s\nshow configuration\nexit\n' "$VSID" | clish` over a non-PTY stdin pipe output only `vsxid 3` and exited before printing configuration; neither `clish -c "set virtual-system <VSID>" -c "show configuration"` nor interactive PTY `clish` (`execInteractive`) was measured at that time.

---

## 4. Exact Literals and Gate Changes Requiring Product Owner Approval

Per `AGENTS.md` ("Network action taxonomy" and "Vendor semantics law"), no new or modified device command, command context, or frequency may be added without Product Owner sign-off. To support VS-context-aware Check Point configuration collection on VSX, the Product Owner would need to approve:

### 4.1 Contract Amendment

- A successor PO Decision Record amending `docs/design/PO_DECISION_RECORD_2026_09_14G_CONFIGURATION_COLLECTION_MEASURED_FORMS.md` §1 `CG-1` to withdraw or qualify `"No per-virtual-system repetition"` on Check Point VSX gateways once the per-VS command form is confirmed on hardware.

### 4.2 Gate Row Widening on Existing `SIGNED_OFF` Row (VSX Detection)

| Existing `gate_id` | Canonical Command Key | Current `max_frequency` / `session_reuse_rule` | Proposed Change Requiring PO Approval |
| --- | --- | --- | --- |
| `cp_inventory_vsx_stat_v` | `vsx stat -v` | `once per device per run` / `one session per device per run` (`CP_INVENTORY_COMMAND_GATE_ENTRIES.md` entry 6) | Widen caller scope in `max_frequency` / `source_document_pointer` to permit execution once per device per `configuration_collect` run (or approve a dedicated `cp_configuration_vsx_stat_v` row for `vsx stat -v`) to detect VSX and enumerate active VSIDs before choosing `vsenv 0` vs. bare reads. |

### 4.3 Candidate New Gate Literals for PO Hardware Measurement and Sign-Off

Depending on whether the Product Owner prefers (A) the Expert `vsenv` login-shell wrapper pattern matching `InventoryReadPlan` (`CF-2`) or (B) the in-Clish virtual-system selector for per-VS Gaia `show configuration`, the exact literals to approve are:

#### Group 1 — Explicit `VS0` physical-context wrappers on a VSX host (preventing non-zero landing-context contamination, matching `InventoryReadPlan` `CF-2`)

| Proposed `gate_id` | Vendor / Scope / Shell / Transport | Exact `canonical_command_key` | Class | Timeout | Retry | Max Frequency | Session Reuse | Secret-Output Risk |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| `cp_configuration_show_hostname_vsenv0` | `check_point` / `cp_gaia_gateway` / `expert` / `SSH_EXEC` | `bash -lc 'vsenv 0 && clish -c '\''show hostname'\'''` | `read` (`CLASS_0_READ`) | 30 s | `none` | `once per VSX device per configuration run` | `one session per device per run` | `none` |
| `cp_configuration_show_version_all_vsenv0` | `check_point` / `cp_gaia_gateway` / `expert` / `SSH_EXEC` | `bash -lc 'vsenv 0 && clish -c '\''show version all'\'''` | `read` (`CLASS_0_READ`) | 30 s | `none` | `once per VSX device per configuration run` | `one session per device per run` | `none` |
| `cp_configuration_cpstat_os_hw_info_vsenv0` | `check_point` / `cp_gaia_gateway` / `expert` / `SSH_EXEC` | `bash -lc 'vsenv 0 && clish -c '\''cpstat os -f hw_info'\'''` *(or direct Expert `bash -lc 'vsenv 0 && cpstat os -f hw_info'`)* | `read` (`CLASS_0_READ`) | 30 s | `none` | `once per VSX device per configuration run` | `one session per device per run` | `none` |
| `cp_configuration_show_configuration_vsenv0` | `check_point` / `cp_gaia_gateway` / `expert` / `SSH_EXEC` | `bash -lc 'vsenv 0 && clish -c '\''show configuration'\'''` | `read` (`CLASS_0_READ`) | 60 s | `none` | `once per VSX device per configuration run` | `one session per device per run` | `none (CG-3 secret lines withheld in view, encrypted at rest)` |

#### Group 2 — Per-Virtual-System (`<VSID>`) configuration and identity literals

> **Measurement note:** Because `bash -lc 'vsenv 3 && clish -c "show configuration"'` returned VS0's Clish configuration on 2026-09-14 (`CP_CONFIGURATION_MEASUREMENT_FINDINGS_2026_09_14.md` §3), the Product Owner must choose and approve the exact per-VS configuration read form after confirming whether `clish -c 'set virtual-system <VSID>' -c 'show configuration'` (or `bash -lc 'vsenv <VSID> && clish -c "set virtual-system <VSID>" -c "show configuration"'`) returns the virtual system's own Clish configuration over `SSH_EXEC`.

| Proposed `gate_id` | Vendor / Scope / Shell / Transport | Exact `canonical_command_key` | Class | Timeout | Retry | Max Frequency | Session Reuse | Secret-Output Risk |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| `cp_configuration_vsid_show_configuration` *(Form A: `vsenv` wrapper)* | `check_point` / `cp_gaia_gateway` / `expert` / `SSH_EXEC` | `bash -lc 'vsenv <VSID> && clish -c '\''show configuration'\'''` | `read` (`CLASS_0_READ`) | 60 s | `none` | `once per virtual system per configuration run` | `one session per device per run, VS0 never re-entered` | `none (CG-3 secret lines withheld in view, encrypted at rest)` |
| `cp_configuration_vsid_clish_set_vs_show_configuration` *(Form B: in-Clish context switch in one process)* | `check_point` / `cp_gaia_gateway` / `expert` / `SSH_EXEC` | `clish -c 'set virtual-system <VSID>' -c 'show configuration'` *(or `bash -lc 'vsenv <VSID> && clish -c "set virtual-system <VSID>" -c "show configuration"'`)* | `read` (`CLASS_0_READ`) | 60 s | `none` | `once per virtual system per configuration run` | `one session per device per run` | `none (CG-3 secret lines withheld in view, encrypted at rest)` |
| `cp_configuration_vsid_show_hostname` *(optional, if per-VS hostname is refreshed in-context)* | `check_point` / `cp_gaia_gateway` / `expert` / `SSH_EXEC` | `bash -lc 'vsenv <VSID> && clish -c '\''show hostname'\'''` | `read` (`CLASS_0_READ`) | 30 s | `none` | `once per virtual system per configuration run` | `one session per device per run` | `none` |
| `cp_configuration_vsid_cpstat_os_hw_info` *(optional, if per-VS `cpstat` identity refresh runs in-context; note V254 on unmerged branch `batch/b55-pansettle-1119`)* | `check_point` / `cp_gaia_gateway` / `expert` / `SSH_EXEC` | `bash -lc 'vsenv <VSID> && clish -c '\''cpstat os -f hw_info'\'''` *(or `bash -lc 'vsenv <VSID> && cpstat os -f hw_info'`)* | `read` (`CLASS_0_READ`) | 30 s | `none` | `once per virtual system per configuration run` | `one session per device per run` | `none` |

---

## 5. Proposed Implementation Change (After Gate Sign-Off)

Once the Product Owner approves the successor contract and the exact gate rows above are committed (with a migration number assigned by the Product Owner after `V255`):

1. **`ConfigurationReadPlan.java`:**
   - Add `CP_VSX_STAT = "vsx stat -v"` (or reuse `InventoryReadPlan.CP_VSX_STAT`), `checkPointPhysicalCommand(String read, boolean vsxHost)` wrapping physical reads in `bash -lc 'vsenv 0 && <read>'` when `vsxHost` is true, and `checkPointVsidConfigurationCommand(String vsid)` (validating `vsid` as digits-only `\\d+` before interpolation, matching `InventoryReadPlan.requireDigitsOnly`).
2. **`ConfigurationRequest.java` and `WorkerClaimLoop.java`:**
   - Carry `Optional<String> virtualSystemRef` on `ConfigurationRequest` (populated from `device.virtualSystemRef()` in `WorkerClaimLoop.buildConfigurationRequest` or `confirmFacts.flatMap(DeviceConfirmFacts::virtualSystemRef)` in `ConfigurationJobExecutor`).
3. **`ConfigurationCapabilityExecutor.java` (`collectCheckPoint`):**
   - Probe `vsx stat -v` first (skipping on known Quantum Spark models and management servers, exactly as `InventoryCapabilityExecutor` does).
   - If the target `devices` row has a specific `virtualSystemRef` / VSID target modifier:
     - Run the configuration read scoped to that `<VSID>` (never attributing `VS0`'s configuration or `VS0`'s `show hostname` to a non-zero virtual-system device row), and pass `context = "<VSID>"` to the parser.
   - If the target `devices` row is a physical VSX gateway member (`vsxStat.vsx() == true` and no single-VS modifier):
     - Run the host-level identity refresh and `VS0` configuration read under `vsenv 0` (`context = "physical"`).
     - For each non-zero VSID reported by `vsx stat -v`, run the approved per-VS configuration literal, parse its sections under `context = "<VSID>"`, and merge the resulting per-context `ConfigurationIndexEntry` rows and sanitized per-context sections into the run.
4. **`CheckPointGaiaConfigParser.java`, `CheckPointGaiaConfigProcessor.java`, and `ConfigurationServiceClient.java`:**
   - Update `CheckPointGaiaConfigParser` so that when `set virtual-system <digits>` (`VSX_SELECTOR_RE`) is encountered inside a configuration stream, it transitions the active context label/index key to that `<VSID>` (with `"0"` mapping to `"physical"`) rather than discarding the selector and attributing subsequent lines to `"physical"`.
   - Allow `CheckPointGaiaConfigProcessor.process(rawConfig, context)` and `ConfigurationServiceClient.parseCheckPoint(deviceId, rawConfig, context)` to accept the caller's target context (`"physical"` or validated `<VSID>`) instead of hardcoding `"physical"`.
5. **`ConfigurationJobExecutor.java`:**
   - Guard `deviceRepository.refreshObservedFacts(...)` so a VS0 physical hostname read never overwrites the `observed_hostname` of a device row whose `virtual_system_ref` targets a non-zero virtual system.
   - Ensure `recordBackupArtefactManifest` only binds `virtual_system_ref` when the configuration artefact was actually collected for that virtual system's context.
6. **Tests:**
   - `ConfigurationReadPlanTest`: verify physical `vsenv 0` wrappers, per-VSID command generation, and digits-only VSID validation (rejecting non-numeric VSIDs).
   - `CheckPointGaiaConfigParserTest` and `CheckPointGaiaConfigProcessorTest`: verify multi-context `set virtual-system <VSID>` transitions and per-VS `ConfigurationIndexEntry` context attribution (`"physical"` vs. `"<VSID>"`).
   - `ConfigurationJobExecutorEndToEndTest`: verify VSX gateway collection (`vsx stat -v` + `vsenv 0` + per-VS reads) and verify that a virtual-system-scoped device row collects in its VS context without overwriting its hostname with VS0's hostname.
