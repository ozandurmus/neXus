# Policy change event trigger: collect a policy minutes after it is installed (design)

## Status

**DRAFT -- DO NOT FREEZE (2026-11-26, rev 2 — revised for external architecture & security review findings). Not implementation authority.**
Docs only: no code, no migration, no gate row, no manifest, no firewall rule and no device command is added or sent by
this document. Every vendor semantic that the repository or official vendor documentation does not settle is marked
`UNKNOWN` (vendor semantics law); the open decisions for the Product Owner are in section 9. Device-side logging
configuration is never performed by Overwatch (section 2.6).

**Origin.** PO decision 2026-11-26 (chat, relayed by the orchestrator), option B of four: keep the existing scheduled
collections and add an event-triggered path that collects the changed policy within minutes of a "policy installed" /
"configuration committed" event. Repository baseline cadences today (verified against source):

- **Policy collection** (`cp_policy_collect`, `pan_policy_collect`): runs after scheduled discovery in the **6-hour**
  `00:00 / 06:00 / 12:00 / 18:00 Europe/Istanbul` windows (`PolicyCollectionJobExecutor.afterDiscovery`). Check Point
  runs `FULL` in the `00:00` window (refreshing daily hit counts) and `CHANGED_ONLY` at `06:00 / 12:00 / 18:00` with
  `UI2_POLICY_CP_CHANGED_ONLY_DOMAINS="*"` already live across all Domains in `deploy/ui2/57-policy-deployment.yaml`
  (`CpChangedOnlyDomains.TRIAL_DOMAIN` is only the unset-environment fallback). Panorama runs `FULL` each 6-hour window.
- **Configuration collection** (`cp_configuration_collect`, `pan_configuration_collect`, `fgt_configuration_collect`,
  `asa_configuration_collect`, `proxysg_configuration_collect`): runs **once daily at `00:00 Europe/Istanbul`**
  (`ConfigurationRefreshScheduler`, cron `0 0 0 * * *`), **not** every 6 hours. Because Cisco ASA and FortiGate policies
  are derived on read from stored configuration artefacts (`LocalFirewallPolicyService`), their scheduled policy
  freshness today is **24 hours**, not 6 hours.
- **Scheduled fleet backups and Check Point MDS export** (`cp_mds_export`): run **once daily in the `02:00–02:59
  Europe/Istanbul` backup window** (`ScheduledBackupWindowPolicy`, `BackupCollectService.requestScheduledMdsExport`;
  `mds_backup` carries a Check Point TAC serialization constraint against concurrent management activity).

For orientation only, the brief describes Tufin SecureTrack as fetching a revision within about 60 s of a syslog
message, with a 60-minute safety poll and a 5-minute "revision changed?" poll for devices without syslog. Tufin's public
pages confirm syslog-triggered change detection and a fallback fetch; the 60-minute and 5-minute figures were not found
on the pages opened (section 2.7).

Inputs read (repository, by narrow search):

- [EVENT_SIGNAL_INTAKE_ARCHITECTURE.md](EVENT_SIGNAL_INTAKE_ARCHITECTURE.md) (FROZEN, Slice 1 only, Python Line-1):
  precedent for "a signal is a trigger only, never evidence" and the signed HTTPS webhook envelope (section 3, 4.1, 4.5).
- [PO_DECISION_RECORD_2026_10_20_ADMIN_MANUAL_JOBS_AND_BUSINESS_HOURS.md](PO_DECISION_RECORD_2026_10_20_ADMIN_MANUAL_JOBS_AND_BUSINESS_HOURS.md)
  (RATIFIED), `JobWindowPolicy.java`, `ScheduledBackupWindowPolicy.java`, `JooqJobLeaseDao.java` (`V168`): job windows,
  backup window, and atomic claim SQL (section 6.2).
- [MODULE_POD_RUNTIME_CONTRACT.md](MODULE_POD_RUNTIME_CONTRACT.md) (FROZEN), `V258__script_execution_slice2.sql`,
  `deploy/ui2/01-priority-classes.yaml`, `deploy/ui2/46-database-networkpolicy.yaml`, `deploy/ui2/57-policy-deployment.yaml`:
  module roles, priority classes, DB ingress policy, and endpoint admission (section 4.2, 6.4).
- [DATABASE_ROLES_AND_DATA_OWNERSHIP_DESIGN_2026_11_14.md](DATABASE_ROLES_AND_DATA_OWNERSHIP_DESIGN_2026_11_14.md) and
  `V5__audit_redaction_policy.sql`: narrow per-workload DB roles and `audit_redaction_policy` (section 4.2, 4.7, 5).
- [PO_DECISION_RECORD_2026_10_02_CP_POLICY_PARALLEL_SESSIONS.md](PO_DECISION_RECORD_2026_10_02_CP_POLICY_PARALLEL_SESSIONS.md)
  (FROZEN): at most four parallel Check Point policy sessions.
- [VENDOR_BACKUP_CONTRACTS_2026_09_22.md](VENDOR_BACKUP_CONTRACTS_2026_09_22.md) (§7 `cp_mds_export`) and
  [OPERATIONS_POLICY_INSTALL_CONTRACT_DRAFT_2026_11_17.md](OPERATIONS_POLICY_INSTALL_CONTRACT_DRAFT_2026_11_17.md):
  `mds_backup` serialization and gateway `device_policy_install` (`cpstat`) runtime truth vs management intent.
- [MDS_LOG_EXPORTER_HEALTH_PROPOSAL_2026_10_22.md](MDS_LOG_EXPORTER_HEALTH_PROPOSAL_2026_10_22.md) (DRAFT): the
  institution's MDS already runs 17 Log Exporter targets over 5 Domains plus the MDS level (to a SIEM over UDP and to a
  firewall-policy tool over TCP), and they once stopped silently for 12 days.
- [FORTINET_CONTRACT.md](FORTINET_CONTRACT.md) (FROZEN) and
  [FMG_SINGLE_COMMAND_DIAGNOSTIC_CLI.md](FMG_SINGLE_COMMAND_DIAGNOSTIC_CLI.md) (FROZEN): FortiManager is present and
  enrolled in the institution's estate (JSON-RPC + SSH discovery/inventory/diagnostics), though not yet a policy source.
- [POLICY_INSIGHTS_DESIGN_2026_11_24.md](POLICY_INSIGHTS_DESIGN_2026_11_24.md) and
  [POLICY_INSIGHTS_BRIEF_2026_11_24.md](POLICY_INSIGHTS_BRIEF_2026_11_24.md): the bank's traffic logs are in Splunk (section 3).
- `deploy/hosta/nexus-host-firewall.nft`, `deploy/hosta/traefik-config.yaml`, and
  [ENTERPRISE_STACK_COMPARISON_2026_11_10.md](ENTERPRISE_STACK_COMPARISON_2026_11_10.md): HOST-A `nftables` / Traefik
  and corporate OpenShift Route ingress constraints (section 4.3).
- Policy, configuration, masking and gate sources: `PolicyCollectionRepository.java`, `PolicyCollectionJobExecutor.java`,
  `CpChangedOnlyDomains.java`, `CpDomainReuse.java`, `PolicyCompletenessCoordinator.java`, `CpShowChangesReader.java`,
  `PolicyRevisionRecorder.java`, `PanoramaPolicyCollector.java`, `LocalFirewallPolicyService.java`,
  `ConfigurationRefreshScheduler.java`, `TopologyNamePseudonymizer.java`, and `gate_registry` migrations `V78`, `V80`,
  `V116`, `V117`, `V121`, `V123`, `V124`, `V125`, `V130`, `V163`, `V192`, `V221`.

## 1. Purpose and non-goals

### 1.1 Purpose

Detect a policy install or configuration commit within minutes and trigger a bounded read-only collection of the
affected unit, instead of waiting up to 6 hours (Check Point / Panorama policy) or 24 hours (ASA / FortiGate policy via
daily `00:00` configuration collection):

| Vendor | Where the change is made and announced | What Overwatch collects today (repository truth) |
|---|---|---|
| Check Point | Security Management Server / Multi-Domain Server (MDS), per Domain. A gateway does not install policy by itself: the management server publishes a session and installs a policy package onto gateways. | Management policy intent per source and Domain (`cp_policy_collect`, `CHANGED_ONLY` or `FULL`); `UI2_POLICY_CP_CHANGED_ONLY_DOMAINS="*"` is live across all Domains on `06/12/18` scheduled runs (`00:00` runs `FULL`). `CpDomainReuse` compares `show-last-published-session` (Publish, not Install). Gateway runtime install truth (`device_policy_install` via `cpstat -f policy fw`) is a separate inventory/monitoring read, not refreshed by `cp_policy_collect` (section 6.1). |
| Palo Alto | A firewall local commit, or a Panorama commit followed by a push (`commit-all`) to device groups / templates. | Panorama policy intent per device group (`PanoramaPolicyCollector`), downloading all DGs and querying per-firewall hit counts (`collectHits`) unless scoped; `PanoramaConfigLogReader` runs on one trial source. Local firewall `effective_running` rules are parsed by `LocalFirewallPolicyService.panSnapshots()` **only** when zero Panorama `management_server` rows exist in `devices` (so local PAN rules are already excluded in this Panorama-managed estate). |
| Fortinet | FortiGate immediate per-edit CLI/GUI configuration change; FortiManager install of a policy package / device config to a FortiGate. | FortiGate policy is derived on read from each FortiGate's daily `00:00` `show` configuration artefact (`LocalFirewallPolicyService.fgtSnapshots()`, `FortiGateConfigPolicyParser`). FortiManager **is** enrolled in the estate (`FORTINET_CONTRACT.md`: confirm, inventory, ADOM discovery, diagnostics), **not** yet a policy-collection source. |
| Cisco | ASA configuration session end / write (CLI/ASDM); FMC deploy to FTD. | ASA policy is derived on read from each ASA's daily `00:00` `more system:running-config` configuration artefact (`LocalFirewallPolicyService.asaSnapshots()`, `AsaConfigPolicyParser`). FMC/FTD: not enrolled collection sources in the repository. |

Optionally (question Q5) non-policy configuration changes could also trigger device configuration collection.

### 1.2 Non-goals

- Not a SIEM: no correlation, search, retention or alerting on log content.
- Not log analytics: the device system-log programme and the log detection engine
  ([DEVICE_SYSTEM_LOG_PHASE_B_CONTRACT.md](DEVICE_SYSTEM_LOG_PHASE_B_CONTRACT.md),
  [LOG_ANOMALY_AGENT_DESIGN_2026_11_10.md](LOG_ANOMALY_AGENT_DESIGN_2026_11_10.md)) are separate and unchanged. This
  receiver never feeds them and they never feed it.
- No raw log retention: a message is parsed in memory into a closed set of fields and immediately discarded (raw-evidence law).
- Not a replacement for the 6-hour scheduled policy collection (including the `00:00` daily `FULL` run) or the `00:00`
  daily configuration collection, both of which remain authoritative and protected from starvation (section 6.4, 8).
- No device write: Overwatch never configures logging on a device (section 2.6).
- Not a change-attribution or ticket-matching feature: out of scope here.

## 2. Event sources per vendor

Research date 2026-11-26, official vendor pages and RFCs only; every URL below was opened. `OFFICIAL` = the cited page
says it; `UNKNOWN` = the official pages opened do not settle it (a community indication, where one exists, is labelled
and never used as a contract). Field names are the vendors'; whether the institution's devices emit these exact shapes
is `UNKNOWN` until the 1-day SIEM fact check and pilot measurement (section 3, 8).

### 2.1 Check Point (Security Management Server / MDS)

| Point | Finding | Source |
|---|---|---|
| Event | Audit log with operation number **7 "Install Security policy"** (8 "Uninstall Security policy"; 39/41 MDS "Install Last Policy"; 40 "MDS Assign Global Policy"). OFFICIAL. | [sk144192](https://support.checkpoint.com/results/sk/sk144192) |
| Fields | `administrator`, `operation`, `operation number`, `objectname`, `objecttype`, `fieldschanges`, `logic changes`, `session_name`, `session_description`, `subject`, `client_ip`, `Device Name`, `Device Type`. OFFICIAL. Note: `Device Name` is a presentation hostname, never an identity join key (`AGENTS.md`). | sk144192 |
| Policy package / installed-on gateway in the event | Not in the sk144192 field table: `UNKNOWN`. The collector must therefore re-read the Domain to learn what changed (section 6.1). | sk144192 |
| Domain in the event | No `domain_name` or `domain_uid` audit field listed: `UNKNOWN`. Each Domain Management Server runs its own Log Exporter daemon, so a **per-Domain target** binds the Domain via its dedicated mTLS client certificate (`MTLS_PINNED_1_TO_1`, section 4.6) or via a relay's enrolled per-Domain `opaque_emitter_key` (`MULTIPLEXED_RELAY_OR_MANAGER`), never by hostname. | [Log Exporter introduction](https://sc1.checkpoint.com/documents/Log_Exporter/EN/Content/Topics/Introduction.htm) |
| Publish vs Install semantic gap | No distinct "Publish" operation number is listed in sk144192 (`UNKNOWN`). **Publish != Install**: SmartConsole Publish creates a management session (`show-last-published-session` advances) without changing gateway enforcement; Install Policy emits operation 7 without necessarily creating a new published session if the session was already published earlier. `show-last-published-session` MUST NOT be used as a `MISSED_EVENT` oracle for operation-7 events (section 6.1, 6.8). | sk144192; `CpDomainReuse.java` |
| Audit export & mandatory audit-only stream prerequisite | Log Exporter exports "Security logs, Audit logs, or both … Audit logs exist on both the Management Server and the Log Server." OFFICIAL. `cp_log_export` documents `filter-action-in`, `filter-origin-in`, `filter-blade-in` and `FilterConfiguration.xml` field filtering; whether an **audit-only** filter (zero Security/traffic logs) works cleanly on the estate's R82 MDS build is `UNKNOWN` until verified on-box. **Hard prerequisite**: a verified audit-only stream (zero traffic logs) is mandatory before connecting any direct Check Point Log Exporter target to Overwatch; otherwise high-rate traffic logs saturate the per-source rate bucket / trip the flood fuse (section 4.4) and drop operation-7 install events. | [CLI appendix](https://sc1.checkpoint.com/documents/Log_Exporter/EN/Content/Topics/Appendix_A_Deployment-CLI.htm), [R81 MDS cp_log_export](https://sc1.checkpoint.com/documents/R81/WebAdminGuides/EN/CP_R81_Multi-DomainSecurityManagement_AdminGuide/Topics-MDSG/CLI/cp_log_export.htm) |
| Configuration | `cp_log_export add name <N> [domain-server {mds|all}] target-server <host> target-port <port> protocol {udp|tcp} format {syslog|splunk|cef|leef|generic|json|logrhythm|rsa}`; on an MDS `domain-server` is mandatory (`mds` = MDS-level audit only, `all` = all Domains); one Domain = `mdsenv <Domain>` first. OFFICIAL. | CLI appendix, R81 MDS guide |
| TLS | `encrypted true` with `ca-cert` (PEM), `client-cert` (P12), `client-secret`; "only mutual authentication is allowed", TLS 1.2, RSA keys only ("ECC keys are not supported"). OFFICIAL. | [TLS configuration](https://sc1.checkpoint.com/documents/Log_Exporter/EN/Content/Topics/TLS-Configuration.htm) |
| Unique id for dedupe | No log `uid` in the sk144192 audit table (`UNKNOWN` whether JSON/Splunk format exports `loguid`). Fallback dedupe uses **keyed HMAC-SHA256** (`UI2_HOSTNAME_FINGERPRINT_KEY_FILE`) over `(source_id, resolved_domain_ref, operation_no, device_event_time_sec, session_name)` — never plain SHA-256 because `session_name` often embeds admin usernames or hostnames (section 4.7). | |

### 2.2 Palo Alto (firewall and Panorama)

| Point | Finding | Source |
|---|---|---|
| Event | **Config log** with `cmd = commit` and `result` (`Submitted`, `Succeeded`, `Failed`, `Unauthorized`). OFFICIAL. The trigger is `cmd=commit` + `result=Succeeded`. | [Config log fields](https://docs.paloaltonetworks.com/pan-os/11-0/pan-os-admin/monitoring/use-syslog-for-monitoring/syslog-field-descriptions/config-log-fields) |
| Other Config fields | `path` (512 bytes), `admin`, `client` (Web/CLI), `vsys`, `vsys_name`, `device_name`, `dg_id`, `dg_hier_level_1..4` (numeric ids, mapped via `show dg-hierarchy`), `comment`, `seqno`; `before-/after-change-detail` only in custom formats. **No template field.** OFFICIAL. | Config log fields |
| System log "commit succeeded" | The official System Log Reference opened does not list a commit-success message; "Commit job succeeded for user …" appears only in a community thread (not opened). `UNKNOWN`; not used. | [Informational system log messages](https://docs.paloaltonetworks.com/pan-os/11-0/pan-os-admin/monitoring/use-syslog-for-monitoring/system-log-reference/informational-system-log-messages), [System log fields](https://docs.paloaltonetworks.com/pan-os/11-0/pan-os-admin/monitoring/use-syslog-for-monitoring/syslog-field-descriptions/system-log-fields) |
| Panorama push / commit-all & `dg_id` mapping | Panorama's own logs can be forwarded from Panorama > Log Settings. The exact log message for a push (`commit-all`) to device groups / templates, and whether it populates `dg_id` / `dg_hier_level_*`, are `UNKNOWN`. Note: `PanoramaPolicyCollector` reads `<show><dg-hierarchy/></show>` today in memory without persisting numeric `dg_id` values; per-DG resolution from numeric `dg_id` requires persisting `(source_id, dg_id, dg_name, container_ref)` (section 6.6). | [Panorama log forwarding](https://docs.paloaltonetworks.com/panorama/administration/manage-log-collection/configure-log-forwarding-from-panorama-to-external-destinations); `PanoramaPolicyCollector.java` |
| Device identity field | `device_name` is a presentation hostname (never a join key). Standard PAN CSV/IETF syslog headers commonly carry `serial`, though the Config-log field table opened omits header columns (`UNKNOWN` until measured). Without either a 1:1 mTLS cert (`Profile A`) or a verified `serial` / `dg_id` (`Profile B`), a multiplexed PAN event evaluates to `NOT_EVALUABLE` (section 4.6). | Config log fields |
| Dedupe | `seqno` per device (hashed via keyed HMAC-SHA256). OFFICIAL field. | Config log fields |
| Configuration | Syslog server profile: TCP, UDP or SSL ("For SSL, the firewall supports only TLSv1.2"); format BSD (default) or IETF; client certificate "Required only if the syslog server uses client authentication" (Certificate for Secure Syslog). OFFICIAL. | [Configure syslog monitoring](https://docs.paloaltonetworks.com/pan-os/11-1/pan-os-admin/monitoring/use-syslog-for-monitoring/configure-syslog-monitoring) |

### 2.3 Fortinet (FortiGate and FortiManager)

| Point | Finding | Source |
|---|---|---|
| FortiGate event | Log **44547** `LOGID_EVENT_CONFIG_OBJATTR` "Object attribute configured" and **44546** `LOGID_EVENT_CONFIG_ATTR` "Attribute configured" (type `event`, subtype `system`). Fields: `cfgtid` ("Config transaction id"), `cfgpath`, `cfgobj`, `cfgattr` (up to 4096), `action`, `user`, `ui`, `vd`, `msg`; 44546 also lists `devid`. OFFICIAL. FortiOS applies each edit immediately: these are **per-edit** events, grouped by `cfgtid` and debounced (section 6.3). A policy edit is `cfgpath` under `firewall.policy` (exact path set across policy/NAT/object tables per FortiOS version: `UNKNOWN`, to be measured). | [44547](https://docs.fortinet.com/document/fortigate/7.4.4/fortios-log-message-reference/44547/44547-logid-event-config-objattr), [44546](https://docs.fortinet.com/document/fortigate/7.6.0/fortios-log-message-reference/44546/44546-logid-event-config-attr) |
| Device identity | `devid` (serial-formatted device ID in FortiOS structured logs; exact byte-for-byte equality with `devices` discovery serial must be proven in pilot measurement, never normalized). Whether 44547 carries `devid` in the common syslog header: `UNKNOWN` on the page opened (standard FortiOS header normally prepends `devid`, to be verified). | 44546 |
| FortiGate configuration | `config log syslogd setting`: `mode` `udp` / `legacy-reliable` (RFC 3195) / `reliable` (RFC 6587); `enc-algorithm` (TLS in `reliable` mode); `ssl-min-proto-version`; `certificate`; `format` `default`/`csv`/`cef`/`rfc5424`/`json`. Whether `certificate` is presented as a mutual-TLS client certificate: `UNKNOWN`. | [config log syslogd setting](https://docs.fortinet.com/document/fortigate/7.4.4/cli-reference/141516630/config-log-syslogd-setting) |
| FortiManager event | DM category **21023** `LOG_ID_policy_package_install` (Info), **21024** install failure (Error), **21015** `LOG_ID_cfg_install` (Notice), 21004–21006 scheduled install. Fields: `adom`, `device`, `pkg`/`instpkg`, `vdom`, `vdoms`, `revision`, `pkg_oid`, `dev_oid`, `session_id`, `status`/`result`, `user`. OFFICIAL. Note: `device` is a display name unless `FORTINET_CONTRACT.md` ADOM discovery persists a proven `(fmg_source_id, dev_oid) -> device_id` relation (`dev_oid` is not persisted today). | [FortiManager 7.6.4 event log reference](https://docs.fortinet.com/document/fortimanager/7.6.4/log-message-reference/100000/event); `FORTINET_CONTRACT.md` |
| FortiManager in the estate | FortiManager **is** present and enrolled in the institution's estate (`FORTINET_CONTRACT.md`, `FMG_SINGLE_COMMAND_DIAGNOSTIC_CLI.md`, `V80`/`V81`/`V85`). Its syslog CLI (`config system locallog syslogd setting` with `dm`/`devcfg`; `config system syslog` with `reliable`, `secure-connection`, `local-cert`, `peer-cert-cn`) is OFFICIAL. Because Overwatch parses Fortinet policy from each FortiGate's own `show` configuration (`fgt_configuration_collect`), a FortiManager 21023/21015 install event (once `dev_oid` or serial mapping is persisted) triggers the target FortiGate's `fgt_configuration_collect`. | [locallog](https://docs.fortinet.com/document/fortimanager/7.6.0/cli-reference/520182/locallog), [syslog](https://docs.fortinet.com/document/fortimanager/7.6.0/cli-reference/310920/syslog) |

### 2.4 Cisco (ASA, FMC/FTD)

| Point | Finding | Source |
|---|---|---|
| ASA events | `%ASA-5-111010: User 'username', running 'application-name' from IP ip_addr, executed 'cmd'` ("A user made a configuration change"); `%ASA-5-111008` (any non-`show` command); `%ASA-5-111005: IP_address end configuration: OK` (left configuration mode); `%ASA-5-111004: IP_address end configuration: {FAILED|OK}` (configuration written); `%ASA-5-111001` / `111007` begin configuration writing / reading. OFFICIAL. Trigger: **111005** or **111004 OK**, with **111010** as a debounced fallback. Command text is never retained; every accepted config-end triggers `asa_configuration_collect` (from which `LocalFirewallPolicyService` derives policy). | [ASA syslog messages 101001-199021](https://www.cisco.com/c/en/us/td/docs/security/asa/syslog/asa-syslog/syslog-messages-101001-to-199021.html) |
| ASA device identity | Absent from the message body; optional `logging device-id {string | context-name | hostname | ipaddress}` header options carry no hardware serial (`UNKNOWN` whether `logging device-id` can emit an opaque enrolled token; note also that ASA failover pairs replicate one hostname onto both units, `TopologyNamePseudonymizer.java` line 207). Identity therefore requires either a relay's per-unit `opaque_emitter_key` (`Profile B`) or a 1:1 non-SNAT peer IP / unique client cert per unit (`Profile A`/`C`, section 4.6). | `TopologyNamePseudonymizer.java` |
| ASA transport hazard (`logging permit-hostdown`) | `logging host <if> <ip> [tcp/port|udp/port] [format emblem] [secure [reference-identity <name>]]`; `logging trap {severity|message_list}` (message list can filter to `111004–111010`). Mutual TLS support on ASA is `UNKNOWN`. **CRITICAL AVAILABILITY HAZARD (OFFICIAL):** *"If the syslog server is down and you configured TCP syslog, new connections through the ASA are blocked unless you configure `logging permit-hostdown`."* Therefore, **direct Cisco ASA TCP or TLS syslog to Overwatch is PROHIBITED** unless `logging permit-hostdown` is verified present in the unit's stored `running-config`; use a SIEM/syslog relay or UDP for ASA (section 4.3, 4.4). | [ASA 9.19 monitor syslog](https://www.cisco.com/c/en/us/td/docs/security/asa/asa919/configuration/general/asa-919-general-config/monitor-syslog.html) |
| FMC | System > Configuration > Audit Log > Send Audit Log to Syslog; UDP 514 default, TCP 1470 with TLS and optional client certificate; only active FMC sends in HA. OFFICIAL. Per-device "deployment succeeded" audit format: `UNKNOWN`. FMC/FTD are not enrolled collection sources today: out of scope. | [FMC 7.4 system configuration](https://www.cisco.com/c/en/us/td/docs/security/secure-firewall/management-center/admin/740/management-center-admin-74/system-config.html), [FMC audit to syslog TechNote](https://www.cisco.com/c/en/us/support/docs/security/secure-firewall-management-center/221019-configure-fmc-to-send-audit-logs-to-a-sy.html) |

### 2.5 Transport standards

- [RFC 5424](https://www.rfc-editor.org/rfc/rfc5424): message format; receivers must accept 480 octets and should accept 2048.
- [RFC 5425](https://www.rfc-editor.org/rfc/rfc5425): TLS transport on TCP 6514, octet-counting framing (`MSG-LEN SP SYSLOG-MSG`), both sides must implement certificate-based authentication (updated by RFC 9662).
- [RFC 5426](https://www.rfc-editor.org/rfc/rfc5426): UDP 514; IPv4 receivers must accept 480 octets, IPv6 1180, all should accept 2048.
- [RFC 6587](https://www.rfc-editor.org/rfc/rfc6587): plain TCP, **Historic**; the IESG does not recommend syslog over plain TCP. Octet counting and LF-trailer framing are defined there.

### 2.6 Who configures the devices

Every device/manager logging line in sections 2.1–2.4 is owned and executed exclusively by the institution's firewall
team through their change-management process, never by Overwatch: Overwatch sends no device write for this feature,
holds no write credential for it, and never generates bypass scripts (it may display the enrolled receiver address,
port, `required_transport`, and expected certificate fingerprint / emitter key for `security_admin` reference). Relay
options (section 3) place the forwarding rule on the SIEM / log-collector tier instead of on devices.

### 2.7 Comparison product (Tufin SecureTrack), for orientation only

Tufin's knowledge centre states: syslog-based change detection for Check Point management, Cisco, Fortinet, Juniper and
Palo Alto ("devices must be configured to send syslogs to SecureTrack"); periodic polling for Panorama, FortiManager and
Cisco FMC; an "automatic fetch" when no syslog arrives within the configured frequency; a 60-second default window that
merges Save Policy and Install Policy; polling intervals set dynamically "based on revision processing time"
([Tufin 1940](https://forum.tufin.com/support/kc/latest/Content/Suite/1940.htm),
[Tufin 8924](https://forum.tufin.com/support/kc/latest/Content/Suite/8924.htm)). The 60-minute safety poll and the
5-minute revision poll of the brief were not found on the pages opened: `UNKNOWN`. Which ASA message IDs Tufin uses:
`UNKNOWN`.

## 3. Feed architecture and the 1-day SIEM fact check

**What the repository establishes.** The bank's traffic logs are in Splunk
([POLICY_INSIGHTS_BRIEF_2026_11_24.md](POLICY_INSIGHTS_BRIEF_2026_11_24.md);
[POLICY_INSIGHTS_DESIGN_2026_11_24.md](POLICY_INSIGHTS_DESIGN_2026_11_24.md) marks the Splunk index, sourcetype and
field names `UNKNOWN`). The MDS already exports logs from 17 Log Exporter targets across 5 Domains plus the MDS level
"to a SIEM over UDP and to a firewall-policy tool over TCP"
([MDS_LOG_EXPORTER_HEALTH_PROPOSAL_2026_10_22.md](MDS_LOG_EXPORTER_HEALTH_PROPOSAL_2026_10_22.md) §1). Whether a
`syslog-ng` / `rsyslog` aggregation tier sits in front of Splunk, whether management/audit/config logs (not just traffic
logs) reach it for CP, PAN, FGT and ASA, and what fields survive normalization are `UNKNOWN`.

**Mandatory 1-day SIEM / collector fact check before freezing Q1.** Before writing intake code, run a 1-day read-only
fact check with the SIEM/collector team (counts and field-presence booleans only, no raw log lines or hostnames copied)
answering four questions:

1. Does a pre-SIEM `syslog-ng` / `rsyslog` collector exist in front of Splunk, or do devices send straight to Splunk
   forwarders?
2. For each vendor (CP op 7, PAN Config `cmd=commit`, FGT `44546`/`44547` / FMG `21023`, ASA `111004`/`111005`), are
   those events already ingested today?
3. Does the collector preserve an **opaque non-hostname discriminator** (`serial`, `devid`, `dg_id`, or a dedicated
   per-Domain/per-device collector input port/tag that maps to an enrolled `opaque_emitter_key`), or does Splunk only
   retain `host` / `dvc` (hostnames / NATed forwarder IPs)?
4. Are Splunk real-time / sub-minute alerts permitted by SIEM governance, or only scheduled searches (e.g., 5–15 min
   cron + indexing lag)?

**CRITICAL ANTI-PATTERN PROHIBITION (Splunk `host` / `dvc` hostname join hazard):** Under `AGENTS.md` ("Presentation
identity != security identity. A hostname, display label, or inferred ordinal (`-1`/`-2`) is never a join key or an
identity gate"), Overwatch **MUST NEVER** match a Splunk `host`, `dvc`, `orig_host`, syslog `HOSTNAME`, Check Point
`Device Name`, or Palo Alto `device_name` field against `devices.hostname`, `discovery_candidate.display_name`, or
`TopologyNamePseudonymizer`. Hostnames collide across HA members (ASA pairs share one hostname), drift from inventory,
and are trivially spoofable inside unauthenticated syslog headers. Any relay event carrying only a hostname evaluates
fail-closed to `NOT_EVALUABLE` (section 4.6).

**Three feed architectures compared (Q1):**

| Dimension | Option 1: Filtered `syslog-ng` / `rsyslog` relay (`TLS_MUTUAL` 6514) | Option 2: Splunk HTTPS webhook (`POST` 443, HMAC + nonce) | Option 3: Direct device / Log Exporter `TLS_MUTUAL` (6514) |
|---|---|---|---|
| Device changes | Zero on devices; one filter/destination rule on the syslog tier | Zero on devices; one alert/webhook action in Splunk | One target + client cert per MDS Domain / manager / firewall (firewall team) |
| Latency | Sub-second streaming (before Splunk indexing) | Indexing + alert schedule delay (`UNKNOWN`; scheduled searches add 5–15 min) | Seconds |
| Identity binding (`§4.6`) | Relay authenticated by mTLS; relay stamps an enrolled `opaque_emitter_key` per upstream input/port or passes raw `serial`/`devid` (`Profile B`) | Webhook authenticated by keyed HMAC (`kid`); JSON carries `serial`/`devid`/`dg_id` or enrolled `opaque_emitter_key` (`Profile B`). **Blocked if Splunk only has `host`/`dvc` hostnames** | Each per-Domain / per-device sender has its own pinned mTLS client cert (`Profile A`, no payload ID needed) |
| Parser surface in Overwatch | Bounded RFC 5425 syslog allowlist parser + optional relay tag | Small strict JSON parser (`<= 8 KiB`), zero syslog framing | Bounded RFC 5425 syslog allowlist parser; requires **verified audit-only** CP stream (`§2.1`) |
| HOST-A & OpenShift ingress (`§4.3`) | Needs TCP 6514 (`prerouting` negated `drop` on HOST-A; SNI or LB Service on OpenShift) | Uses **existing HTTPS 443** Ingress / OpenShift Route — **zero new firewall ports** | Needs TCP 6514 exposed to device management networks (`@syslog_allowed`) |
| Pipeline dependency | Depends on syslog tier + upstream exporter (e.g. MDS Log Exporter) | Depends on syslog tier + Splunk indexer + search scheduler + upstream exporter | Depends only on the device/MDS exporter (still subject to the 12-day MDS exporter silent-stop risk) |

## 4. Receiver architecture in Overwatch

### 4.1 Principles (kept from the frozen Slice 1 intake)

- **A signal is a trigger, never evidence.** The receiver never calls a collector, never opens a device session, never
  holds device credentials or artefact keys, never writes evidence and never builds a command. Its only privileged act
  on a validated, identity-resolved, non-duplicate, debounced event is to request an existing typed job (`capability_id`
  + inventory `source_id`/`domain_ref`/`device_id`) through Overwatch's job-admission boundary.
- **Zero message parameters enter any command.** Nothing extracted from the event (strings, paths, package names,
  session names, timestamps) is passed to the worker. The collector resolves endpoints, domains and credentials strictly
  from inventory by opaque ID, identically to a scheduled run.
- **Closed allowlist, immediate discard.** Only the allowlisted event classes of section 2 are parsed; everything else
  is dropped unread and counted per `event_source`.

### 4.2 Placement, job-submission boundary, and governance footprint

Recommendation: run a **separate small listener pod** (`ui2-event-intake`, same container image, dedicated entrypoint
role `event_intake`), **not** inside `ui2-service`.

| Aspect | Separate `ui2-event-intake` pod (Recommended) | Inside `ui2-service` |
|---|---|---|
| Blast radius of a parser bug or flood | Isolated to `ui2-event-intake` (`PriorityClass: ui2-low`); console, API, failover and workers unaffected | Shares JVM, heap, threads and pods with the operator console and failover API |
| Mounted secrets | ONLY its listener TLS keypair, narrow `ui2_event_intake` DB credentials, `ui2-hostname-fingerprint-key`, and internal submit token. **Zero** `ui2-credential-store-key`, `ui2-artefact-store-key`, or `/app/artefact-store` PVC | Holds LDAP, session, credential-store, artefact-store and full `ui2_app` DB privileges |
| Network exposure & egress | Reachable only on the intake port; **zero egress to device networks** (`NetworkPolicy` allows only `kube-dns`, `database:5432`, and `ui2-service` ClusterIP) | `ui2-service` would accept traffic from device/SIEM networks while holding broad internal reach |
| Governance cost | Explicit governance footprint below | Smaller manifest footprint, unacceptable privilege coupling |

**Job-submission mechanism (no SQL job-submission function exists in PostgreSQL today):** Today, job creation runs in
Java (`PolicyCollectionRepository.enqueue` and `ConfigurationCollectService` writing `jobs` + `policy_collection_request`
inside `AuditedTransactionBoundary`). Two concrete designs for crossing from `ui2-event-intake` into `jobs`:

1. **Option A (Recommended) — Internal SHA-256-token-authenticated HTTP call to `ui2-service` (`POST /internal/event-triggers/submit`, ClusterIP only):**
   - `ui2-event-intake` uses a narrow PostgreSQL role (`ui2_event_intake`, per
     [DATABASE_ROLES_AND_DATA_OWNERSHIP_DESIGN_2026_11_14.md](DATABASE_ROLES_AND_DATA_OWNERSHIP_DESIGN_2026_11_14.md))
     with **`SELECT` only** on an enrollment projection (`v_enrolled_event_source`, `event_source_emitter_map`) and
     **`SELECT, INSERT, UPDATE, DELETE` only** on its four intake tables (`policy_change_event`, `event_source_counter`,
     `event_trigger_scope`, `event_intake_nonce`) — **zero `INSERT`/`SELECT` grants on `jobs`, `policy_collection_request`,
     `devices`, `credentials`, or `audit_log`**.
   - When a debounce timer in `event_trigger_scope` matures, `ui2-event-intake` sends `{scope_id, event_id}` over
     ClusterIP to `ui2-service`, authenticated by a dedicated mounted Kubernetes `Secret` bearer token verified in
     constant time against its SHA-256 digest (reachable only from `app.kubernetes.io/component: event-intake` via
     `NetworkPolicy`). `ui2-service` re-reads `event_trigger_scope`, checks the `security_admin` kill-switch, job
     windows, `01:45–03:00` backup blackout, and `PolicyCollectionRepository` / `ConfigurationCollectService` rules in
     one place, and writes the audited `jobs` row as actor `system:event-trigger`.
2. **Option B (Alternative) — Dedicated PostgreSQL `SECURITY DEFINER` RPC (`fn_submit_event_triggered_job(...)` with `SET search_path = pg_catalog, public`):**
   - Eliminates the internal HTTP hop, at the cost of duplicating `PolicyCollectionRepository.enqueue`, backup-window
     blackout, `FamilySwitch`/kill-switch, and `fn_audit_capture` checks in PL/pgSQL and granting indirect `jobs`
     creation to the network-exposed pod's database session.

**Required governance & manifest footprint when implementing `ui2-event-intake` (out of scope of this draft, listed for completeness):**

- Amend [MODULE_POD_RUNTIME_CONTRACT.md](MODULE_POD_RUNTIME_CONTRACT.md) §1 to add `event_intake` as a zero-claim
  listener role (`0` durable `job_type` claims; unknown roles fail startup).
- Extend `module_runtime_control_module_check` (`V154` / `V258`) if `event_intake` registers heartbeats/drain in
  `module_runtime_control`.
- Update `deploy/ui2/46-database-networkpolicy.yaml` (`ui2-db-ingress`) to add `event-intake` to the allowed
  `app.kubernetes.io/component` list on TCP `5432`.
- Assign `priorityClassName: ui2-low` (`-100`, matching `58-packet-analysis-deployment.yaml`) so `ui2-event-intake` can
  never preempt `ui2-service` or `ui2-worker` (`800000`).
- Ship a strict `NetworkPolicy` on `ui2-event-intake` with **zero device-management egress** (DNS `53`, PostgreSQL
  `5432`, and `ui2-service` ClusterIP only).

### 4.3 Protocol and how traffic reaches it

**Protocol hierarchy & anti-downgrade rule:**

1. **HTTPS webhook (`RELAY_HTTPS` on TCP 443, section 4.5):** used when Q1 selects Splunk HTTPS webhook. Authenticated
   by per-source keyed HMAC-SHA256 (`X-Signal-Key-Id`, `X-Signal-Timestamp`, `X-Signal-Nonce`, `X-Signal-Signature`)
   with optional mTLS.
2. **Syslog over mutual TLS (`TLS_MUTUAL` on dedicated TCP 6514, RFC 5425, TLS 1.2+ AEAD ciphers):** mandatory default
   for syslog intake (`ClientAuth.REQUIRE` enforced in the TLS handshake on port `6514`).
3. **Reduced-grade fallbacks (disabled by default; require explicit `security_admin` per-source opt-in):**
   - One-way TLS (`TLS_SERVER_ONLY`), if ever needed for a sender proven unable to present a client certificate, MUST
     bind a **separate listener port** (e.g., `6515`) rather than setting `ClientAuth.OPTIONAL` on `6514`.
   - UDP `514` (`UDP_LEGACY`, RFC 5426): unauthenticated, spoofable, lossy; allowed only for explicitly enrolled Profile
     C sources (such as Cisco ASA without `logging permit-hostdown`, section 2.4).
   - Plain TCP (RFC 6587 Historic): disabled by default; prohibited for Cisco ASA without verified `logging permit-hostdown`.
4. **Per-source `required_transport` anti-downgrade lock:** every `event_source` stores `required_transport`. A source
   enrolled as `TLS_MUTUAL` is **refused immediately** (`TRANSPORT_DOWNGRADE_REFUSED`) if an attacker or misconfiguration
   sends packets claiming its identity on a one-way TLS, TCP, UDP, or HTTPS port — even from an IP inside `@syslog_allowed`.

**HOST-A ingress today (`deploy/hosta/nexus-host-firewall.nft` and `deploy/hosta/traefik-config.yaml`):**

- `nexus-host-firewall.nft` defines `chain prerouting` (`type filter hook prerouting priority -150; policy accept;`),
  which drops only external `NEW` packets to NodePorts `30000-32767`, and `chain input` (`priority filter - 10; policy
  drop;`), which accepts TCP `22` (`@ssh_allowed`, `@sftp_push_allowed`) and TCP `{ 80, 443 }` (`Traefik` redirects `80`
  to `443` `websecure` with `nexus-default-tls`).
- **CRITICAL `nftables` `prerouting` trap:** Because `chain prerouting` has **`policy accept`** and Kubernetes/k3s
  `hostPort` and ServiceLB ports perform `dstnat` at `prerouting priority -100` (bypassing `chain input` completely!),
  any syslog port exposed via `hostPort` or ServiceLB **MUST** enforce an explicit **negated `drop`** in `chain
  prerouting` (`priority -150`):
  `iifname "ens160" ct state new tcp dport 6514 ip saddr != @syslog_allowed drop` (plus an explicit `meta nfproto ipv6
  iifname "ens160" ct state new tcp dport 6514 drop` unless an IPv6 allowlist set is populated). Adding an `accept` rule
  in `prerouting` or a rule in `input` would leave a DNAT-ed port open to the entire network! `@syslog_allowed` is
  populated only in `/etc/nexus/firewall.nft` on HOST-A, never in the repository, under
  [PO_DECISION_RECORD_2026_09_24_HOST_A_OWNED_BY_NEXUS_AGENT_SUDO.md](PO_DECISION_RECORD_2026_09_24_HOST_A_OWNED_BY_NEXUS_AGENT_SUDO.md).
- **Traefik TCP passthrough source-IP masking:** Routing TCP `6514` through a Traefik TCP router forwards connections
  over the cluster pod network, replacing the client's source IP with Traefik's pod IP as seen by `ui2-event-intake`.
  Therefore:
  - **If Q1 uses HTTPS webhook:** reuse existing HTTPS `443` (`Traefik` path/host route -> `ui2-event-intake`), zero new
    ports or `nftables` edits.
  - **If Q1 uses `TLS_MUTUAL`-only syslog (`Profile A`/`B`):** Traefik TCP `6514` TLS passthrough works (identity is the
    client certificate, while HOST-A `nftables` enforces `@syslog_allowed` at the edge; verify on HOST-A whether k3s
    Traefik binds via ServiceLB DNAT in `prerouting` so the negated `drop` sits in `prerouting`).
  - **If peer IP preservation is required (`Profile C`):** Traefik TCP proxying cannot be used without PROXY protocol;
    use `hostPort` on `ui2-event-intake` with the `prerouting` negated `drop` above.

**Corporate OpenShift target:** Standard OpenShift Routes carry HTTPS (`443`) and TLS passthrough **only on `443` when
the client sends a TLS `SNI` `server_name` extension**; they never carry UDP or arbitrary port `6514` without a
platform-approved `LoadBalancer` / `NodePort` Service. Whether Check Point Log Exporter, PAN-OS, or FortiOS emit SNI on
syslog-TLS is `UNKNOWN`. The HTTPS webhook option (`POST` on `443` with SNI) fits a standard OpenShift Route cleanly
with zero special cluster networking.

### 4.4 Limits, framing safety, and wire-speed shedding (NO TCP read-pause)

**HARD PROHIBITION — Never pause TCP socket reads ("kernel window backpressure"):** The receiver MUST NEVER stop reading
from a TCP/TLS syslog socket to throttle a noisy sender. Stalling TCP reads shrinks the TCP receive window to zero: on
Cisco ASA without `logging permit-hostdown` this **blocks new production firewall connections through the data plane**
(section 2.4), and on Check Point Log Exporter, Panorama/PAN-OS, and FortiOS it stalls exporter pipelines and local log
buffers. Instead, sockets are **always drained at wire speed** and excess messages are shed in memory:

| Limit / Safeguard | Specification | Rationale |
|---|---|---|
| Pre-allocation RFC 5425 octet-count check | Read at most 5 ASCII digits (`1..8192`) before `SP` **prior to buffer allocation**. Non-digit prefix or `MSG-LEN > 8192` -> count `OVERSIZE` / `FRAME_ERROR` and close connection immediately. (If LF framing is explicitly enabled for a legacy sender, scan into a fixed reusable 8 KiB buffer and discard until `\n` on overflow.) | Prevents multi-gigabyte heap allocation OOM and RFC 5425 frame-smuggling attacks; accommodates FortiGate 4096-byte `cfgattr` |
| Per-frame read timeout | **5 seconds** once the first byte of a frame is read (`IDLE_TIMEOUT = 10 min` applies only *between* complete frames; TLS handshake timeout = `10 s`) | Defeats slowloris partial-frame socket exhaustion while allowing quiet persistent connections |
| Connection bounds | At most **64** concurrent TLS/TCP connections total; at most **4** per peer IP | Bounded file descriptors and TLS handshake memory |
| Per-source rate & wire-speed shedding | Token bucket: **50 msg/s** sustained, burst **500**. Over-rate frames are **read from the socket at wire speed and immediately discarded** (`RATE_DROPPED` counter), never pausing `read()` | Keeps firewall/exporter TCP send buffers clear while bounding parser CPU |
| Per-connection flood circuit breaker | Hard fuse: **> 5,000 msg/s or > 10 MiB/s sustained for 10 s** -> trip `FLOOD_FUSE_TRIPPED`, close socket, enforce 60 s reconnect cool-down, mark source `DEGRADED` (reinforces why CP streams MUST be verified audit-only before connection, §2.1, and why direct ASA TCP/TLS without `permit-hostdown` is forbidden, §2.4) | Protects pod NIC/CPU if a firewall team accidentally exports full traffic logs |
| Global rate & bounded parser queue | **2,000 msg/s** global parser budget; **1,000** slot in-memory candidate ring/queue (`QUEUE_FULL` drops unread at wire speed) | Socket I/O threads never block on parsing or DB writes |

Every drop increments in-memory counters flushed periodically to `event_source_counter` (section 4.7), never writing a
log line containing message text or sender hostnames.

### 4.5 Parsing (syslog allowlist) and HTTPS relay security contract

**Syslog path (`TLS_MUTUAL` / `UDP_LEGACY`):**

1. Frame within the 8 KiB pre-allocation bound and 5 s frame timeout; decode UTF-8 with replacement, never passing bytes
   to any shell, SQL, reflection, XML entity, or format-string sink.
2. Detect RFC 5424 or RFC 3164 header; on malformed header increment `UNPARSABLE` and discard.
3. **Fast pre-filter** on fixed vendor/event tokens from section 2 (Check Point operation `7`/`8`/`39`/`40`/`41`; PAN
   `CONFIG` + `commit`; FortiGate `44546`/`44547` or FortiManager `21023`/`21024`/`21015`; ASA `111004`/`111005`/`111010`;
   or an allowlisted relay `HEARTBEAT` marker). Anything else is dropped immediately as `NOT_ALLOWLISTED` before field
   extraction.
4. Extract only the closed per-vendor field subset (opaque device/scope token if present, event class, outcome enum,
   device wall-clock string, dedupe token / `session_name` hashed immediately in memory via keyed HMAC-SHA256). Each
   field enforces strict length and regex bounds; out-of-bounds -> `UNPARSABLE` / `NOT_EVALUABLE`, no trigger.
5. Zero/discard the frame buffer. Administrator usernames, comments, IP addresses, rule/object names and CLI commands
   are never retained.

**HTTPS relay path (`RELAY_HTTPS`, `POST /api/v1/event-intake/events`):**

- **8 KiB body cap** enforced on the input stream before JSON deserialization (`> 8192` bytes -> `413`, `OVERSIZE`).
- **Authenticated HMAC envelope** (ported to Java from frozen Slice 1, [EVENT_SIGNAL_INTAKE_ARCHITECTURE.md](EVENT_SIGNAL_INTAKE_ARCHITECTURE.md)):
  - Required headers: `X-Signal-Key-Id` (`kid`), `X-Signal-Timestamp` (epoch seconds), `X-Signal-Nonce` (`16..128` chars
    `[A-Za-z0-9_.-]`), `X-Signal-Signature` (`sha256=<hex>`).
  - Signature covers `ASCII(timestamp + "." + nonce + ".") || raw_body_bytes` under the enrolled `event_source` key
    matching `kid`, compared via constant-time `MessageDigest.isEqual`.
  - **Dual `kid` key rotation:** each `RELAY_HTTPS` `event_source` supports `current_kid` and `next_kid` (with optional
    `next_valid_until`), enabling zero-downtime SIEM secret rotation by `security_admin`.
  - **Fail-closed replay protection (both required):** (1) `abs(now - timestamp) <= 300` s (`±300 s` window); (2) atomic
    `INSERT INTO event_intake_nonce(source_id, nonce, expires_at) VALUES (..., now() + interval '10 minutes') ON CONFLICT
    DO NOTHING` — `0` rows inserted rejects as `409 REPLAYED_NONCE`, and **any PostgreSQL error fails closed (`503`)**,
    never admitting an unrecorded nonce.
- **Closed JSON schema (`failOnUnknownProperties = true`):** `signal_id`, `message_type` (`CHANGE_EVENT` | `HEARTBEAT`),
  `vendor`, `event_class`, `event_outcome`, `opaque_emitter_key` (nullable), `opaque_target_id` (nullable: exact serial /
  `devid` / `dg_id`), `device_event_time` (nullable). Any unknown field or hostname-shaped value where an opaque key is
  required fails closed (`400` / `NOT_EVALUABLE`).

### 4.6 Sender-binding profiles, identity resolution, and dual-pin rotation

Presentation identity != security identity (`AGENTS.md`). Because vendor logs differ in whether they carry an opaque
payload identifier (Check Point op 7 and Cisco ASA `111005` carry none in the body; SIEM relays multiplex many devices),
each enrolled `event_source` declares **one explicit `binding_profile`** configured by `security_admin`:

| Profile | Typical sender | Transport authentication | Payload / target resolution rule (`MATCH` criteria) |
|---|---|---|---|
| **Profile A: `MTLS_PINNED_1_TO_1`** | Per-Domain Check Point Log Exporter (`mdsenv <Domain>`), or a single firewall/appliance with its own unique client cert | `required_transport = TLS_MUTUAL`. Client certificate full SHA-256 fingerprint matches `cert_sha256_current` or valid `cert_sha256_next` on `event_source`. | `event_source` is pinned 1:1 by `security_admin` to exactly one inventory `(source_id, domain_ref)` or `device_id`. **The mTLS cert fingerprint IS the security identity** (`MATCH` requires no payload ID). **Fail-closed conflict check:** if the message *also* carries an allowlisted opaque payload ID (`serial`, `devid`, `uid`) that disagrees with the pinned inventory entity, outcome = **`MISMATCH`** (no trigger). |
| **Profile B: `MULTIPLEXED_RELAY_OR_MANAGER`** | Filtered `syslog-ng`/`rsyslog` relay, Splunk HTTPS webhook, Panorama, FortiManager, or MDS-level `domain-server all` target | `required_transport` in `{TLS_MUTUAL, RELAY_HTTPS}` authenticates the relay or manager (`cert_sha256_*` or HMAC `kid`). | Transport proves *who relayed*, **not** which child entity changed. **An opaque non-hostname discriminator is MANDATORY** (`NOT_EVALUABLE` if absent): **(i)** exact inventory-proven opaque ID linked to that manager/scope (`devices` serial, proven FortiGate `devid == serial`, persisted `(fmg_source_id, dev_oid)`, or persisted Panorama `(source_id, dg_id) -> container_ref` — no case folding, zero stripping or int cast), **OR (ii)** an explicit `security_admin`-enrolled `(event_source_id, opaque_emitter_key) -> (target_source_id, domain_ref / device_id)` row in `event_source_emitter_map` (where `opaque_emitter_key` is a dedicated relay listener-port/stream token, **never** a hostname/`host`/`dvc`). Multiple matches = `AMBIGUOUS`. |
| **Profile C: `IP_ALLOWLIST_REDUCED_GRADE`** | Direct UDP `514` or one-way TLS from a device proven unable to use mTLS or a relay (e.g., ASA UDP) | `required_transport` in `{UDP_LEGACY, TLS_SERVER_ONLY}`. Exact peer source IP matches the 1:1 address enrolled on `event_source` (`security_grade = REDUCED_IP_ONLY`). | **Requires end-to-end client IP preservation** (no SNAT, no Traefik TCP proxy, no shared NAT IP; enrollment refuses an IP shared by two `event_source` rows as `AMBIGUOUS`). Resolves to the 1:1 enrolled `device_id`; if payload carries an opaque ID, it must also match (`MISMATCH` otherwise). |

**Unenrolled senders and dual-pin certificate rotation:**

- **Unenrolled traffic:** dropped at TLS handshake / accept / packet receipt and counted **only** in a global hourly
  bucket keyed by the closed enum `address_class IN ('RFC1918', 'OTHER_IPV4', 'IPV6')` — **never storing or logging the
  source IP**.
- **Dual-pin SHA-256 certificate rotation:** every `TLS_MUTUAL` `event_source` holds `cert_sha256_current` (NOT NULL)
  and `cert_sha256_next` (NULLABLE, with `cert_next_valid_until TIMESTAMPTZ`). `security_admin` stages the renewed
  certificate's SHA-256 in `cert_sha256_next` ahead of firewall/relay cutover and promotes it afterward — zero-downtime
  rotation without relaxing `ClientAuth.REQUIRE`.

### 4.7 Persistence, bounded write amplification, and privacy schema

**Bounding database write amplification:** A noisy or flapping source sending 50 allowlisted events/s must never insert
50 PostgreSQL rows/s (180,000 rows/hour). Row insertion into `policy_change_event` is strictly **transition-only**:

- `policy_change_event` rows are written **only** for accepted `MATCH` + `SUCCEEDED` events that **open or coalesce into
  a debounce window, trigger, or defer a scope** (at most 1 row on window open + 1 final outcome update, hard-capped at
  **`<= 60` rows per `event_source` per hour**).
- All excess `DEBOUNCED` repeats within an already-dirty scope, `CAPPED` overflows, `DUPLICATE`s, `NOT_TRIGGERED_IDENTITY`
  (`MISMATCH`, `NOT_EVALUABLE`, `AMBIGUOUS`), `NOT_TRIGGERED_FAILED_INSTALL`, `NOT_ALLOWLISTED`, `UNPARSABLE`,
  `OVERSIZE`, `RATE_DROPPED`, and `QUEUE_FULL` outcomes increment **in-memory counters** flushed every 10–60 s via
  single-row upserts to `event_source_counter (source_id, hour_bucket)` (plus `event_listener_counter (hour_bucket,
  address_class)` for unenrolled drops).

Proposed intake tables (illustrative schema; SQL migration is out of scope of this draft):

1. **`policy_change_event` (90-day retention, partitioned or indexed on `received_at`):**
   - `event_id` (`UUID` PK), `event_source_id` (opaque UUID), `target_source_id` / `device_id` / `domain_ref` (resolved
     opaque inventory refs), `vendor`, `event_class` (`POLICY_INSTALLED`, `CONFIG_COMMITTED`, `PANORAMA_PUSHED`,
     `MANAGER_INSTALLED`), `event_outcome` (`SUCCEEDED`), `identity_outcome` (`MATCH`), `binding_profile`,
     `security_grade` (`MTLS_STRONG`, `HMAC_STRONG`, `REDUCED_IP_ONLY`), `transport` (`TLS_MUTUAL`, `TLS_SERVER_ONLY`,
     `UDP_LEGACY`, `RELAY_HTTPS`), `received_at` (`TIMESTAMPTZ`), `device_event_time` (`TEXT` as sent, nullable, never
     time-zone converted).
   - **`dedupe_bucket` (`DATE`, UTC day of `received_at`) + `dedupe_key` (`TEXT`, keyed HMAC-SHA256 under
     `UI2_HOSTNAME_FINGERPRINT_KEY_FILE` domain `"policy-event-dedupe-v1"` over `(event_source_id, resolved_scope_ref,
     event_class, vendor_unique_or_session_token)`):** enforced by `UNIQUE (event_source_id, dedupe_bucket, dedupe_key)`
     plus a lookup check across `(dedupe_bucket, dedupe_bucket - 1)` where `received_at >= now() - interval '24 hours'`.
     This guarantees **24-hour deduplication** while retaining **90 days** of history without blocking legitimate reuse
     of a `session_name` or wrapped `seqno` weeks later.
   - `trigger_outcome`: `DEBOUNCING`, `TRIGGERED` (+ `job_id`), `COALESCED` (+ `job_id`), `DEFERRED_SOURCE_BUSY`,
     `DEFERRED_BACKUP_WINDOW`, `DEFERRED_WINDOW_GUARD`, `NOT_TRIGGERED_KILL_SWITCH`, `NOT_TRIGGERED_NO_BASELINE_SNAPSHOT`.
2. **`event_trigger_scope` (1 row per collection unit — section 6.3, 6.4):**
   - `(target_kind, source_id, scope_ref)` PK, `first_event_at`, `last_event_at`, `last_event_id`, `trigger_due_at`,
     `max_due_at`, `dirty` (`BOOLEAN`), `active_job_id` (`TEXT NULL`), `active_job_started_at` (`TIMESTAMPTZ NULL`),
     `defer_reason` (`TEXT NULL`), `last_triggered_at` (`TIMESTAMPTZ NULL`), `hour_bucket` (`TIMESTAMPTZ`),
     `hour_trigger_count` (`SMALLINT`).
3. **`event_source`, `event_source_emitter_map`, `event_source_counter`, `event_listener_counter`, `event_intake_nonce`:**
   - **Mandatory `audit_redaction_policy` registration (`V5__audit_redaction_policy.sql` rule):** because `event_source`
     changes by `security_admin` pass through `fn_audit_capture`, the migration creating `event_source` MUST insert:
     - Tier 1 (`tier = 1`, `'live secret material'`) for any `RELAY_HTTPS` HMAC secret ciphertext column on `event_source`;
     - Tier 2 (`tier = 2`, `'event-source network operational identity'`) for `event_source.allowed_peer_addresses` (matching `endpoints.address_ref` in `V5`), so `audit_log.after_state` stores `sha256:<hex>` rather than raw operational IPs.

## 5. Security

| Threat | Structural bound |
|---|---|
| Spoofed message or compromised relay/device | Can only enqueue a `CLASS_0` read-only job for an **already-enrolled** inventory target using its stored credential; zero event fields enter any command (4.1). Bounded by 90 s / 10 min debounce, 10 min per-unit cooldown, `<= 6` triggers/unit/hour, `<= 30` triggers/hour fleet-wide, `01:45–03:00` backup blackout, and the `security_admin` runtime kill-switch. |
| Transport downgrade (`TLS_MUTUAL` -> UDP / plain TCP / one-way TLS) | Per-source `required_transport` enforced before identity evaluation; `TLS_MUTUAL` terminates on its own port (`ClientAuth.REQUIRE`) separate from any reduced-grade listener (4.3). |
| Replay | `TLS_MUTUAL` session integrity + 24h keyed HMAC `dedupe_key` on syslog; signed `timestamp.nonce.body` (`±300 s`) + fail-closed PostgreSQL `event_intake_nonce` table on `RELAY_HTTPS` (4.5). |
| TCP backpressure firewall outage | TCP reads are **never paused**; wire-speed shedding + 5 s frame timeout + pre-allocation `<= 8192` octet check + per-connection flood fuse; direct ASA TCP/TLS prohibited unless `logging permit-hostdown` is verified (2.4, 4.4). |
| Database write-amplification DoS | Transition-only `policy_change_event` writes (`<= 60` rows/source/hour); all noise/drops/duplicates batched in memory into `event_source_counter`; unenrolled traffic counted only by closed `address_class` enum (`RFC1918`, `OTHER_IPV4`, `IPV6`) (4.6, 4.7). |
| Pod compromise / lateral movement | Separate `ui2-event-intake` pod at `PriorityClass: ui2-low`, zero device-network egress (`NetworkPolicy`), zero device credentials/artefact keys, zero `jobs` DB grants (internal token-authenticated submit to `ui2-service`) (4.2). |
| Offline brute-force of `session_name` / operational IP leaks in `audit_log` | `dedupe_key` uses keyed HMAC-SHA256 (`UI2_HOSTNAME_FINGERPRINT_KEY_FILE`); `event_source` network/secret columns registered in `audit_redaction_policy` (Tier 1/2); UI and `aiview` render only masked `TopologyNamePseudonymizer` tokens (`FW-…`, `CLS-…`, `DOM-…` — note prefix is `DOM-`, never `DMN-`) and never expose peer IPs (4.7). |
| Parser attack surface | Closed pre-filter, bounded regex/length extractors, zero deserialization/JNDI/XML-entity/shell sinks, fuzz tests on RFC 5425/6587 framing + vendor extractors + JSON schema, CodeQL and `tools/security/` clean before exposure. |
| Silent feed block / exporter stall | Real-time transport/heartbeat liveness (`DISCONNECTED` / `HEARTBEAT_STALE` within minutes) + MDS Log Exporter health check, independent of 14-day policy silence; 6h policy and 24h configuration schedules remain guaranteed (6.4, 7). |

## 6. Trigger semantics, scheduling non-starvation, and command gates

### 6.1 The collection unit per vendor and runtime-truth boundaries

| Event | Unit triggered | Capability & mode | Codebase reality & required safeguards |
|---|---|---|---|
| CP policy installed (op 7; Domain D via `Profile A` cert or `Profile B` `opaque_emitter_key`) | MDS `source_id` + Domain `D` | `cp_policy_collect`, `CHANGED_ONLY` (never `FULL` / never `show-hits`) | **Publish vs Install (`CpDomainReuse`):** If session `S1` was already collected after an earlier Publish (e.g. by the 12:00 scheduled run) and op 7 installs `S1` at 14:15 without a new Publish, `CpDomainReuse` sees `S1 == S1` and finishes in seconds as `REUSED` (rulebase intent was already captured; counted as `TRIGGERED_DOMAIN_REUSED`, not a parser error). **Gateway install-state boundary:** `cp_policy_collect` updates management `policy_snapshot` intent only; per-gateway runtime truth in `device_policy_install` (`cpstat -f policy fw`) is NOT refreshed by `cp_policy_collect` (Phase 2 option, Q7). |
| PAN firewall local commit (`cmd=commit`, `result=Succeeded`) | Firewall `device_id` | `pan_configuration_collect` (if Q5 includes PAN config; local policy excluded when Panorama is enrolled) | Verified in `LocalFirewallPolicyService.panSnapshots()` (`line 183`): local firewall rules are **excluded** from policy derivation whenever any Panorama `management_server` is enrolled. In a Panorama estate, local firewall commits affect the Configuration/Compliance plane only. |
| PAN Panorama push (`commit-all` / DG `G`) | Panorama `source_id` + DG `G` (`domainRef`) | `pan_policy_collect` (**Phase 2**, after Panorama collector seams below) | **Current `PanoramaPolicyCollector` limitation:** even with `domainRef = G`, it currently downloads **all** DGs on Panorama (`line 177`) and calls `collectHits()` (`line 219`), opening direct HTTPS XML-API sessions to **every firewall in DG `G`**. Before enabling Panorama triggers, add `skipHits = true` (zero firewall fan-out), ancestor-only XPath fetch via `show dg-hierarchy`, and persisted `(source_id, dg_id, dg_name)` mapping (section 6.6). |
| FortiGate config edit (`44546`/`44547`) | FortiGate `device_id` | `fgt_configuration_collect` (`LocalFirewallPolicyService` re-parses policy on read) | Grouped by `cfgtid`, debounced (90 s / 10 min); in policy-only scope (Q5), filter `cfgpath` to policy/NAT/referenced-object prefixes once measured. Note: each run writes an encrypted configuration artefact (Q5). |
| FortiManager package/config install (`21023` / `21015`, not `21024` failure) | Target FortiGate `device_id` | `fgt_configuration_collect` | Requires persisting `(fmg_source_id, dev_oid)` or serial mapping during `fmg_discovery_enumerate` (`FORTINET_CONTRACT.md`); `21024` (`FAILED`) is recorded as `NOT_TRIGGERED_FAILED_INSTALL`. |
| Cisco ASA end of configuration (`111005`, `111004 OK`) | ASA `device_id` | `asa_configuration_collect` (`LocalFirewallPolicyService` re-parses policy on read) | Every accepted config-end triggers (CLI text is discarded); each run writes an encrypted configuration artefact (Q5). |

Failed installs/commits (`event_outcome = FAILED`) never trigger (`NOT_TRIGGERED_FAILED_INSTALL`).

### 6.2 Job windows, `V168` claim SQL, `gate_registry.max_frequency` amendments, and `CpShowChangesReader`

**1. Job-window rule & dedicated claim-exemption column (`JooqJobLeaseDao` / `V168`):**

- `JobWindowPolicy` admits automatic device jobs only in the 60-minute windows starting at `00:00 / 06:00 / 12:00 / 18:00
  Europe/Istanbul` (`UI2_JOB_WINDOW_MINUTES`, contract default `60`).
  [PO_DECISION_RECORD_2026_10_20_ADMIN_MANUAL_JOBS_AND_BUSINESS_HOURS.md](PO_DECISION_RECORD_2026_10_20_ADMIN_MANUAL_JOBS_AND_BUSINESS_HOURS.md)
  exempts **only** interactive `security_admin` / `operation_admin` submissions (`jobs.window_bypass_role`) and states:
  *"Scheduled and automatic jobs keep the windows exactly as before."*
- **Never overload `jobs.window_bypass_role`:** `V168__admin_manual_window_bypass.sql` constrains `window_bypass_role IN
  ('security_admin', 'operation_admin')` and `JooqJobLeaseDao.BUSINESS_HOURS_UNACKNOWLEDGED_REFUSAL_SQL` rejects any
  `window_bypass_role IS NOT NULL` job claimed during `08:00–20:00` without interactive human `business_hours_ack_at`.
- **Required clean schema (if PO approves Q6):** add a dedicated nullable column `jobs.trigger_window_exemption TEXT NULL
  CHECK (trigger_window_exemption IN ('EVENT_TRIGGERED_POLICY', 'EVENT_TRIGGERED_CONFIG'))` + `jobs.trigger_event_id UUID
  NULL`, constrained to `submitted_by_actor_fingerprint = 'system:event-trigger'` and the allowlisted `CLASS_0` read
  capabilities, with partial index `jobs_event_trigger_requested ON jobs (capability_id) WHERE state = 'REQUESTED' AND
  trigger_window_exemption IS NOT NULL`. Update `JooqJobLeaseDao.CLAIM_SQL` and `ClaimStatementText.SQL` identically so
  `trigger_window_exemption IS NOT NULL` is claimable outside `00/06/12/18` **except during the `01:45–03:00` backup
  blackout** (section 6.4), without triggering `BUSINESS_HOURS_UNACKNOWLEDGED_REFUSAL_SQL`.
- **Mandatory `UI2_JOB_WINDOW_MINUTES=60` test & pilot gate:** `deploy/ui2/57-policy-deployment.yaml` currently carries a
  leftover `UI2_JOB_WINDOW_MINUTES: "360"` from earlier development mode, which keeps windows open 24/7 and would mask
  claim-SQL, backup-blackout, and `OUTSIDE_JOB_WINDOW` bugs! Automated integration tests and the live pilot MUST verify
  behavior under **`UI2_JOB_WINDOW_MINUTES=60`**.

**2. Network-device command-gate `max_frequency` amendments (`AGENTS.md` + PO 2026-10-20 §4):**

Existing signed-off `gate_registry` rows explicitly pin `max_frequency` to **`automatic at most once per domain/Panorama
per 6 hours`**. Raising automatic frequency for event-triggered runs requires a PO-approved Flyway amendment naming each
affected `gate_id` (adding *"plus event-triggered `CLASS_0` read at most 6/hour per unit (10-min cooldown) and 30/hour
fleet-wide, outside 01:45–03:00 backup blackout"*):

- **Check Point (`cp_policy_collect`, `CHANGED_ONLY` — hit-count gates `V121`/`V130 *_hits` are NOT amended because event
  runs never pass `show-hits true`):**
  - `V116`: `cp_policy_packages`, `cp_policy_access_rulebase`, `cp_policy_nat_rulebase`
  - `V123`: `cp_policy_packages_paged`
  - `V124` (19 object-inventory gates, read only when domain changed and inventory cache expired):
    `cp_policy_packages_50`, `cp_policy_times`, `cp_policy_time_groups`, `cp_policy_gateways_and_servers`,
    `cp_policy_hosts`, `cp_policy_networks`, `cp_policy_groups`, `cp_policy_groups_with_exclusion`,
    `cp_policy_address_ranges`, `cp_policy_services_tcp`, `cp_policy_services_udp`, `cp_policy_services_icmp`,
    `cp_policy_services_icmp6`, `cp_policy_services_other`, `cp_policy_services_sctp`, `cp_policy_services_dce_rpc`,
    `cp_policy_services_rpc`, `cp_policy_service_groups`, `cp_policy_access_roles`, `cp_policy_dynamic_objects`,
    `cp_policy_dns_domains`, `cp_policy_security_zones`, `cp_policy_unused_objects`
  - `V125`: `cp_policy_last_published_session`
  - `V130`: `cp_policy_access_rulebase_uid`
  - `V163` / `V192`: `cp_policy_nat_rulebase_page100` (if active), `cp_policy_nat_rulebase_by_uid`
- **Palo Alto (`pan_policy_collect`, Phase 2 — hit-count gates `pan_policy_rule_hit_count` (`V121`) and
  `pan_policy_nat_rule_hit_count` (`V163`) are NOT amended because event runs enforce `skipHits = true`):**
  - `V117`: `pan_policy_shared`, `pan_policy_device_group`, `pan_policy_devicegroups`, `pan_policy_hierarchy`
  - `V221`: `pan_policy_predefined_applications` (cached/skipped on event-triggered runs or amended)
  - `V163`: `pan_policy_config_log_enqueue` (`P-2`), if run on event-triggered jobs rather than deferred to 6h windows
- **FortiGate / Cisco ASA (`fgt_configuration_collect` / `asa_configuration_collect`, Phase 2):**
  - `V80`: `fgt_get_system_status`, `fgt_show_configuration`
  - `V78`: `asa_more_system_running_config` (and associated confirm/version gates in `asa_configuration_collect`)

**3. Fixing out-of-window `CpShowChangesReader` diff loss in `PolicyCompletenessCoordinator`:**

Today, `PolicyCompletenessCoordinator.afterCollection()` (`lines 57–68`) calls `CpShowChangesReader.read()`, which
immediately returns `Result.unknown("OUTSIDE_JOB_WINDOW", 0)` outside `00/06/12/18` (`line 124`), yet
`PolicyCompletenessCoordinator` **still writes the new `CHANGED` `markerJson` into `policy_revision`!** At the next
in-window scheduled run, `recorder.baseline()` sees `baseline == currentMarker` and skips `show-changes`, **permanently
losing daytime object-level change diffs**. Before any out-of-window CP triggered job runs:

- Track a separate **`last_diffed_marker` cursor** for `CpShowChangesReader` (distinct from the latest `policy_revision`
  snapshot marker), so out-of-window triggered collections update `policy_snapshot` and `policy_revision` immediately
  while deferring `C-1`/`C-2` (`cp_policy_show_changes` / `cp_policy_show_task`) to the next `00/06/12/18` window across
  `[last_diffed_marker -> latest_marker]` (preserving `C-1`/`C-2`'s once-per-6h in-window rule with zero lost diffs).

**4. Audited `security_admin` runtime kill-switch:**

Provide a DB-backed `event_trigger_settings` switch (global + per-vendor + per-`event_source`, toggled by
`security_admin` via audited UI/CLI action, same pattern as `ScheduledJobFamily` / `FamilySwitch` in `V203`): disabling
it halts both new event-job submissions in `ui2-service` and unclaimed `trigger_window_exemption` claims immediately
without pod restart.

### 6.3 Debounce, coalescing, and strict no-retry (`event_trigger_scope`)

- **Quiet period & max wait:** per collection unit in `event_trigger_scope`, set `trigger_due_at = now + 90 s` on each
  arriving `MATCH` event, capped by `max_due_at = first_event_at + 10 min` so continuous edits still flush after 10 min.
- **Per-unit cooldown & rate caps:** at least **10 minutes** between two triggered job starts for the same unit
  (`last_triggered_at + 10 min`), at most **6** triggered runs per unit per hour, and at most **30** per hour fleet-wide.
- **Dirty coalescing while running (max backlog = 1):** if a new `MATCH` event arrives *after* `active_job_started_at`
  while `active_job_id` is running, set `dirty = true` (at most one follow-up run after completion + cooldown, never a
  queue of N).
- **Scheduled/manual coalescing:** if a scheduled or manual job covering that unit (`domainRef = ""` or matching
  `domainRef`/`device_id`) starts at or after `last_event_at`, clear `dirty = false` and mark the pending event
  `COALESCED` into that `job_id`.
- **HARD NO-RETRY INVARIANT:** if `active_job_id` terminates in `FAILED`, `REJECTED`, `CANCELLED`, or `OUTCOME_UNKNOWN`,
  clearing `active_job_id` **NEVER** re-triggers on its own (`retry_rule = none`); a follow-up job is submitted **only**
  if `dirty = true` from a **genuinely newer** `MATCH` event received after `active_job_started_at`.

### 6.4 Preventing MDS-wide lock drops, scheduled 6h / `00:00` `FULL` starvation, and `02:00` backup collisions

Inspection of `PolicyCollectionRepository.enqueue()` (`lines 76–94`) and `PolicyCollectionJobExecutor.afterDiscovery()`
(`lines 60–66`) reveals four coupling hazards that this design explicitly resolves:

1. **MDS/Panorama-wide active-job lock (`DEFERRED_SOURCE_BUSY`):**
   - `PolicyCollectionRepository.enqueue()` rejects (`Optional.empty()`) if **any** `policy_collection_request` for the
     same `source_id` is in `('REQUESTED','CLAIMED','EXECUTING','RECONCILING')` — even for a sibling Domain!
   - Therefore, when Domain B's `trigger_due_at` matures while Domain A (or a scheduled run) is active on the same MDS,
     `ui2-service` MUST NOT drop `Optional.empty()`: it leaves `event_trigger_scope.dirty = true` with `defer_reason =
     DEFERRED_SOURCE_BUSY` and dispatches Domain B once `source_id` becomes idle (provided Domain B was not already
     covered by an all-Domains scheduled run that started after `last_event_at`).
   - Similarly, if `enqueue()` finds no prior baseline `policy_snapshot` for `(sourceId, domainRef)` (`lines 79–80`),
     clear `dirty` with `NOT_TRIGGERED_NO_BASELINE_SNAPSHOT` (the next scheduled all-Domains run creates the baseline).
2. **Zero starvation of `00/06/12/18` scheduled runs and the `00:00` daily `FULL` run:**
   - Today, `PolicyCollectionJobExecutor.afterDiscovery()` calls `repository.enqueue(sourceId, "", true, ACTOR, mode)`
     and **silently discards `Optional.empty()`**! If an event-triggered single-Domain job were active when `00:00`
     discovery finished, the MDS's **entire daily `00:00 FULL` collection (and hit-count refresh) would be dropped**!
   - **Mandatory two-layer fix:**
     1. **Pre-window guard band (`XX:45–XX:59` before `00:00 / 06:00 / 12:00 / 18:00`):** hold new event-triggered
        submissions in `event_trigger_scope` with `defer_reason = DEFERRED_WINDOW_GUARD` so they coalesce cleanly into
        the upcoming scheduled window instead of occupying the MDS across the boundary.
     2. **Durable pending-scheduled-run latch:** if `afterDiscovery()` finds an active policy job on `source_id`, persist
        a durable `pending_scheduled_run (source_id, run_id, mode)` row (`FULL` sticky over `CHANGED_ONLY`, never
        overwritten back to `CHANGED_ONLY`). When the active job finishes, the pending scheduled all-Domains job is
        enqueued **first** (strict priority over any `DEFERRED_SOURCE_BUSY` event scopes, coalescing them automatically).
3. **Explicit `01:45–03:00 Europe/Istanbul` Backup & `cp_mds_export` Blackout Guard Band:**
   - Daily fleet backups and Check Point `cp_mds_export` (`mds_backup -b -l`, `02:00–02:59` backup window / `02:30` MDS
     export; Check Point TAC serialization constraint against concurrent MDS sessions, `VENDOR_BACKUP_CONTRACTS_2026_09_22.md`
     §7 & `MDS_FORK_STORM_FIX_CONTRACT_2026_11_11.md`) must never collide with event-triggered collections.
   - **Rule:** from **`01:45` through `03:00 Europe/Istanbul`** (15-minute pre-window drain margin + full `02:00–02:59`
     backup window), AND whenever any `cp_mds_export` or backup job is non-terminal (`REQUESTED`, `CLAIMED`, `EXECUTING`)
     on that `source_id` / `device_id`, **zero event-triggered jobs are enqueued or claimed**. Events arriving in that
     blackout are recorded and held in `event_trigger_scope` (`DEFERRED_BACKUP_WINDOW`) until `>= 03:00` and backup
     completion.
4. **Endpoint admission & drain (`MODULE_POD_RUNTIME_CONTRACT.md` §§2–3):**
   - Triggered jobs pass unchanged through `EndpointAdmissionRepository` (1 session per ordinary endpoint; `<= 4`
     parallel CP policy sessions; `<= 10` active device jobs fleet-wide) and standard `SIGTERM`/module drain checkpoint
     rules. `event_trigger_scope` lives in PostgreSQL, so pod restarts lose no pending debounce or deferred trigger.

### 6.5 HA, clusters, VSX

Evidence identity != operational identity (`AGENTS.md`). Two members of an HA pair (ASA, FortiGate, PAN) may both log a
commit during config sync: when the unit is per-Domain or per-DG (CP MDS, Panorama), debounce coalesces them; when the
unit is per-device (`asa_configuration_collect`, `fgt_configuration_collect`), each member's event triggers only that
member's own read and never assumes the peer is in sync. Check Point cluster and VSX policy installs are emitted by the
Domain Management Server, so the unit is the MDS Domain.

### 6.6 Check Point MDS Domains and Panorama fan-out / `dg_id` seams

- **Check Point MDS:** each Domain is collected independently via `domainRef` (`cp_policy_collect`, `CHANGED_ONLY`),
  serialized per `source_id` by `PolicyCollectionRepository` + `DEFERRED_SOURCE_BUSY` (section 6.4) and bounded to `<= 4`
  SSH sessions during execution.
- **Palo Alto Panorama (Phase 2 collector prerequisites before enabling triggers):**
  1. **`skipHits = true` (true zero firewall fan-out):** `PanoramaPolicyCollector` (`lines 219, 381–427`) currently
     connects directly to every enrolled physical firewall in a DG to fetch `rule-hit-count`. Event-triggered Panorama
     runs MUST pass `skipHits = true` (and skip `measureNatHits`), contacting **Panorama only** (fan-out to firewalls = 0).
  2. **Ancestor-only DG download:** `PanoramaPolicyCollector` (`line 177`) currently downloads XML for all `N` DGs on
     Panorama even when `scope.domainRef()` targets one DG. Because `<show><dg-hierarchy/></show>` is already fetched
     first (`line 147`), scope event-triggered runs to download only `/config/shared` + the target DG's ancestor chain
     `[root .. targetDG]` (and reuse cached `predefinedApplications` rather than streaming 64 MiB on every push).
  3. **Persisted `(source_id, dg_id, dg_name, container_ref)` table** populated from `<show><dg-hierarchy/></show>` so
     numeric `dg_id` / `dg_hier_level_1..4` fields in PAN logs resolve by opaque ID, plus separating Panorama's 6h
     `policy_collection_domain(sourceId, "")` throttle (`PolicyCollectionRepository.java` line 85) from event-triggered
     per-DG runs.

### 6.7 Provenance of triggered runs

Triggered jobs carry `jobs.trigger_event_id` (and `origin = EVENT_TRIGGERED` in `PolicyCollectionJobExecutor.recordRun`
step notes) so operators see which event initiated the run, while producing identical `policy_snapshot` /
`configuration_artefact` evidence to a scheduled run.

### 6.8 Safety poll and why Phase 1 defaults to NO hourly device poll

An hourly device-polling loop has two concrete drawbacks verified against the codebase:

1. **Check Point Publish != Install false-alarm trap (`§2.1`, `§6.1`):** `show-last-published-session` advances on every
   SmartConsole **Publish**, whereas Log Exporter emits operation 7 only on **Install Policy**. Using
   `show-last-published-session` as a 60-minute `MISSED_EVENT` oracle would trigger unnecessary collections and falsely
   degrade source health (`MISSED_EVENT` / `DEGRADED`) every time an administrator publishes work-in-progress without
   installing!
2. **Extra daytime device sessions & new commands:** standalone CP/PAN marker reads outside full collection do not exist
   as separate job types today, and FortiGate / ASA have no cheap revision-counter command in `gate_registry` (adding one
   requires the network-device command gate).

**Phase 1 design (Recommended in Q4):** **No hourly device safety poll in Phase 1.** Instead, feed loss is caught within
minutes by **transport/relay liveness + MDS Log Exporter health** (section 7), while the guaranteed 6-hour scheduled
policy run (`00/06/12/18`) and daily `00:00` configuration run bound worst-case staleness with zero extra device reads.
If Phase 2 adds a 60-minute standalone marker poll (Q4 Option B), it must use an install-accurate / commit-accurate
marker under its own `gate_registry` approval and never classify CP Publish-without-Install as `MISSED_EVENT`.

## 7. What the operator sees (decoupling pipeline liveness from policy silence)

A quiet firewall or Domain may legitimately go 30+ days without a policy change, whereas a dead TLS exporter on a busy
Domain must raise attention within minutes — not after 14 days! The UI (policy view, device page, and overview) therefore
displays **two separate, orthogonal facts** per Domain / device group / device:

### 7.1 Transport & pipeline liveness (per `event_source`, real-time)

| Liveness state | Rule | Operator wording |
|---|---|---|
| `CONNECTED` | Persistent `TLS_MUTUAL` source has `>= 1` authenticated open session (and, on CP MDS, MDS Log Exporter health is not `STOPPED`/`STALE` when enabled) | "Live TLS session connected (since …)." |
| `HEARTBEAT_OK` | `RELAY_HTTPS` or syslog relay with periodic canary heartbeat received within threshold (e.g. `<= 15 min`) | "Relay heartbeat healthy (last ping …)." |
| `DISCONNECTED` | Enrolled persistent `TLS_MUTUAL` source has `0` open sessions past reconnect grace (`> 5 min`) | "TLS event stream disconnected since …; covered by 6h scheduled collection." |
| `HEARTBEAT_STALE` | Relay missed expected heartbeat window (`> 15 min`), or linked MDS Log Exporter health check (`MDS_LOG_EXPORTER_HEALTH_PROPOSAL_2026_10_22.md`) reports target stopped/unreadable | "Event pipeline stalled (relay heartbeat or MDS Log Exporter unhealthy)." |
| `PASSIVE_NO_HEARTBEAT` | One-shot webhook or `UDP_LEGACY` source with no heartbeat configured | "Passive event source configured; liveness between change events is not measurable." |
| `DEGRADED` | `FLOOD_FUSE_TRIPPED`, `RATE_DROPPED`/`OVERSIZE`/`QUEUE_FULL`, `IDENTITY_PROBLEM` (`MISMATCH`/`NOT_EVALUABLE`/`AMBIGUOUS`), or confirmed `MISSED_EVENT` in last 7 days | "Event stream degraded (dropped or unmatched frames in last 7d); see source counters." |
| `NOT_ENROLLED` | No `event_source` speaks for this unit | "Not covered by change events; collected on schedule (6h policy / 00:00 daily config)." |

### 7.2 Change-event activity & collection freshness (per Domain / DG / device, informational)

- **Last change event at** (`device_event_time` as sent + Overwatch `received_at`) and **policy collected at** (with
  live pending status when `event_trigger_scope.dirty = true`: *"Change detected 14:02 — collection debouncing (due
  14:04)"*, *"deferred: MDS busy (`DEFERRED_SOURCE_BUSY`)"*, or *"held for 02:00–03:00 backup window"*).
- **`NO_CHANGE_EVENTS_N_DAYS` (`N = 14`, informational only, never an alarm when liveness is `CONNECTED`/`HEARTBEAT_OK`):**
  worded strictly as *"No policy-install events received for N days"* (never *"no changes"* when liveness is
  `DISCONNECTED`, `HEARTBEAT_STALE`, or `PASSIVE_NO_HEARTBEAT`).
- **Fleet coverage** on the policy overview: units with healthy live event coverage (`CONNECTED` or `HEARTBEAT_OK`) /
  total policy units per vendor.
- **Mandatory `aiview` masking (`AGENTS.md`):** `aiview` (`role:replay_viewer`) sees the exact same states, counts and
  timestamps strictly through `TopologyNamePseudonymizer` (`FW-TANGO-04`, `CLS-ROMEO-01`, `DOM-TANGO-04` — domain prefix
  is `DOM-`, never `DMN-`), with zero raw hostnames, zero session names and zero peer IP addresses.

## 8. Rollout and Domain-size-aware validation

1. **Step 0 — 1-day SIEM / collector fact check (read-only, zero code):** settle Q1's four facts with the SIEM and
   firewall teams (section 3).
2. **Step 1 — Pilot (Check Point, one small/medium actively changed MDS Domain):**
   - Select one small/medium Domain that experiences regular policy installs (for example `CpChangedOnlyDomains.TRIAL_DOMAIN`,
     917 rules in 2 packages, if actively changed, or the smallest Domain with weekly installs — note that scheduled
     `CHANGED_ONLY` already runs across `*` all Domains today).
   - **Hard pre-pilot gates:** (a) verified **audit-only** stream (zero Security/traffic logs on the Log Exporter target
     or relay filter); (b) verified `Profile A` (1:1 mTLS cert) or `Profile B` (enrolled `opaque_emitter_key`, zero
     hostname joins); (c) `DEFERRED_SOURCE_BUSY`, `00/06/12/18` scheduled-run non-starvation latch, `01:45–03:00` backup
     blackout, `CpShowChangesReader` diff cursor, and `security_admin` kill-switch tested under **`UI2_JOB_WINDOW_MINUTES=60`**.
3. **Step 2 — 14-day pilot measurement record** (following
   [SNMP_M0_MEASUREMENT_RECORD_2026_11_24.md](SNMP_M0_MEASUREMENT_RECORD_2026_11_24.md): counts, latencies and closed
   classes only, zero addresses or raw names):

   | Metric | Purpose |
   |---|---|
   | Events received/day by class + `NOT_ALLOWLISTED` / `UNPARSABLE` / `OVERSIZE` / `RATE_DROPPED` | Confirms audit-only filter (zero traffic-log leakage) and parser fit |
   | Identity `MATCH` / `MISMATCH` / `NOT_EVALUABLE` / `AMBIGUOUS` | Confirms sender-binding profile fit |
   | `DEDUPE` hits, `DEBOUNCED`, `COALESCED`, `CAPPED`, `DEFERRED_SOURCE_BUSY`, `DEFERRED_BACKUP_WINDOW` | Debounce, cap and contention behavior |
   | `event -> job start` (p50 / p95 / max) | Dispatch latency (target: p95 `<= 3 min` when MDS idle and outside `01:45–03:00` / pre-window guards) |
   | `event -> job complete` (p50 / p95 / max), bucketed by Domain rule count | End-to-end freshness against **Domain-size-aware SLO** (below) |
   | `TRIGGERED_DOMAIN_COLLECTED` vs `TRIGGERED_DOMAIN_REUSED` (`CpDomainReuse` `S1 == S1`) | Measures already-published-then-installed frequency vs fresh rulebase reads |
   | Scheduled `00/06/12/18` & `00:00 FULL` completion rate (must be 100%, zero dropped runs) | Proves non-starvation of scheduled & daily hit-count runs |
   | Transport liveness transitions (`DISCONNECTED` / `HEARTBEAT_STALE`), pod CPU/heap p95, MDS SSH session peak | Operational stability and resource footprint |

4. **Step 3 — Widen with Domain-size-aware freshness SLOs:**
   - Widen to remaining CP Domains only when: `MATCH >= 99%` of allowlisted events, `0` `AMBIGUOUS`, `0` traffic-log
     flood fuse trips, `0` starved scheduled runs, and end-to-end freshness meets **Domain-size-aware targets**:
     - **Small / medium Domains (`< 5,000` rules):** p95 `event -> collection complete <= 10 min` (when MDS is idle).
     - **Large Domains (`15,000+` rules + object dictionaries):** re-reading a full Domain takes ~75 min serial or
       **~20–30 min** across the 4-session parallel cap (`PO_DECISION_RECORD_2026_10_02_CP_POLICY_PARALLEL_SESSIONS.md`);
       set p95 `event -> collection start <= 3 min` (when idle) and `event -> collection complete <= 35–45 min` unless a
       per-package seam (Q7) is built later.
   - Expand to Panorama, FortiGate/FortiManager, and Cisco ASA one vendor at a time, each behind its own collector
     prerequisites (section 6.1, 6.6) and 14-day measurement record.

## 9. Open questions for the Product Owner

> **Decision Record (`FROZEN — PO APPROVED 2026-11-26`):** The Product Owner approved the recommended `Q1`–`Q9` package and the mandatory 1-day read-only SIEM/collector fact-check checklist in [PO_DECISION_RECORD_2026_11_26_POLICY_CHANGE_EVENT_TRIGGER.md](PO_DECISION_RECORD_2026_11_26_POLICY_CHANGE_EVENT_TRIGGER.md). This design document remains `DRAFT -- DO NOT FREEZE` while the Section 10 `UNKNOWN` items are measured.

Recommendation first in each, with explicit `Artı:` and `Eksi:` trade-offs.

**Q1. Feed architecture (gated on the 1-day read-only SIEM/collector fact check of section 3)?**
- **Recommended — PO APPROVED (2026-11-26) (selected by the 1-day SIEM fact check): Option 1A — Filtered `syslog-ng` / `rsyslog` TLS relay if a pre-SIEM syslog tier exists, OR Option 1B — Splunk HTTPS webhook (`POST` over 443) if logs go straight to Splunk and Splunk preserves opaque non-hostname IDs / emitter tags with `<= 1–2 min` alert latency.**
  - *Option 1A — Filtered `syslog-ng` / `rsyslog` relay (`TLS_MUTUAL` on 6514, stamping per-input `opaque_emitter_key` or preserving `serial`/`devid`):* **Artı:** zero changes on firewalls/MDS; sub-second streaming before Splunk indexing; filters out traffic logs upstream; avoids Splunk real-time search governance limits and `host` normalization. **Eksi:** requires a pre-SIEM syslog tier owned by the collector team; needs TCP `6514` ingress (`Q9`); depends on upstream exporters (e.g., MDS Log Exporter).
  - *Option 1B — Splunk structured HTTPS webhook (`POST` on 443, keyed HMAC `timestamp.nonce.body` + PostgreSQL nonce table + dual `kid` rotation):* **Artı:** zero changes on devices; zero new firewall ports on HOST-A or OpenShift (reuses HTTPS `443` Route/Ingress); tiny strict JSON parser (`<= 8 KiB`), no syslog framing in Overwatch. **Eksi:** **blocked (`NOT_EVALUABLE`) if Splunk only exposes `host`/`dvc` hostnames** (`AGENTS.md` forbids hostname joins); scheduled Splunk searches add 5–15 min indexing/cron delay if real-time alerts are disabled; adds Splunk indexer/scheduler to the dependency chain.
- **Option 1C — Direct device / MDS Log Exporter `TLS_MUTUAL` (TCP 6514), where no collector carries the audit/config logs.** **Artı:** lowest latency (seconds); no SIEM dependency; per-Domain client cert (`Profile A`) solves CP Domain identity cleanly without payload fields. **Eksi:** firewall-team change per MDS Domain / manager / device; **hard prerequisite** to verify an audit-only CP Log Exporter filter first so traffic logs do not flood the stream (`§2.1`); exposes port `6514` to device management subnets (`@syslog_allowed`).

**Q2. Syslog transport & sender authentication (applies only if Q1 chooses Option 1A or 1C; skipped if Q1 = Option 1B HTTPS webhook)?**
- **Recommended — PO APPROVED (2026-11-26): RFC 5425 mutual TLS (`TLS_MUTUAL`) on dedicated TCP 6514, with per-source `required_transport` anti-downgrade lock, dual-pin SHA-256 cert rotation (`current` + `next`), wire-speed shedding (never TCP read-pause), and hard prohibition on direct Cisco ASA TCP/TLS unless `logging permit-hostdown` is verified in stored `running-config`.** **Artı:** cryptographic sender identity (`Profile A`/`B`), integrity, zero downgrade path, zero risk of TCP window stalls blocking firewall data-plane traffic. **Eksi:** client certificates to issue and rotate (mitigated by dual-pin `current` + `next`); FortiGate and ASA client-cert support is `UNKNOWN`.
- **Option 2B — Per-source reduced-grade fallback (`UDP_LEGACY` 514 or separate-port `TLS_SERVER_ONLY`, `Profile C`) ONLY for vendors proven unable to use mTLS or a relay (e.g., direct ASA without `permit-hostdown`).** **Artı:** safe non-blocking fallback for ASA if relay is unavailable. **Eksi:** UDP is spoofable and silently lossy; requires non-SNAT peer IP preservation (incompatible with Traefik TCP proxy); reduced security grade.
- **Option 2C — Plain TCP (RFC 6587 Historic).** **Artı:** none over TLS where TLS exists. **Eksi:** unencrypted, unauthenticated, Historic RFC, carries the same ASA `permit-hostdown` outage hazard — **not recommended**.

**Q3. Which vendor first for the 14-day pilot?**
- **Recommended — PO APPROVED (2026-11-26): Check Point — one small/medium, actively changed MDS Domain (e.g., the 917-rule `TRIAL_DOMAIN` if actively changed, or smallest active Domain), gated on 3 mandatory prerequisites: (1) verified audit-only stream (zero traffic logs), (2) `Profile A` mTLS cert or `Profile B` enrolled `opaque_emitter_key` (never hostname join), and (3) `DEFERRED_SOURCE_BUSY` + scheduled-run protection + `01:45–03:00` backup blackout + `CpShowChangesReader` cursor fix validated under `UI2_JOB_WINDOW_MINUTES=60`.** **Artı:** highest operator value; `CHANGED_ONLY` is already live across all Domains (`*`); small/medium Domain finishes in minutes without stressing the MDS. **Eksi:** Log Exporter once stalled silently for 12 days (mitigated by §7 liveness + MDS exporter health); op 7 omits package/gateway names so the Domain is re-read.
- **Option 3B — Palo Alto (Panorama).** **Artı:** one Panorama push covers many firewalls; XML-API is fast once scoped. **Eksi:** requires refactoring `PanoramaPolicyCollector` first (`skipHits = true` to prevent direct firewall fan-out, ancestor-only DG XML fetch, and persisted `(source_id, dg_id, dg_name)` table, `§6.6`); Panorama `commit-all` log shape is `UNKNOWN`.
- **Option 3C — FortiGate (with/without FortiManager) or Cisco ASA.** **Artı:** refreshes policies that today update only once every 24h (`00:00` daily config run). **Eksi:** FortiOS emits per-edit logs (`44546`/`44547`); ASA requires `permit-hostdown` or relay/UDP; every triggered config read writes a new encrypted configuration/backup artefact to disk (`Q5`).

**Q4. Safety poll strategy?**
- **Recommended — PO APPROVED (2026-11-26) (Phase 1 default): No hourly device safety poll in Phase 1 — rely on the guaranteed 6-hour scheduled policy collection (`00/06/12/18`), real-time transport/relay heartbeat liveness (`CONNECTED` / `HEARTBEAT_OK` / `DISCONNECTED` / `HEARTBEAT_STALE`), and Check Point MDS Log Exporter health monitoring.** **Artı:** zero extra hourly SSH/XML-API sessions on MDS/Panorama/firewalls; eliminates false `MISSED_EVENT` / `DEGRADED` alarms caused by Check Point SmartConsole Publish-without-Install (`show-last-published-session` != op 7); detects broken connections or stalled exporters within minutes rather than polling devices blindly. **Eksi:** a single dropped event on an otherwise live connection waits up to the 6-hour scheduled policy window (or 24h daily config window for ASA/FGT).
- **Option 4B (Phase 2 option) — Standalone 60-minute lightweight marker poll where an install/commit-accurate marker is proven and gated.** **Artı:** bounds silent single-event loss to 60 minutes. **Eksi:** new automatic job/command-gate scope across daytime hours; cannot use CP `show-last-published-session` as an install-loss oracle without cross-checking install state; no cheap marker command exists for ASA/FortiGate without new `gate_registry` commands.

**Q5. Should non-policy configuration changes also trigger device configuration collection?**
- **Recommended — PO APPROVED (2026-11-26): Policy plane only in Phase 1; evaluate non-policy configuration triggers later per vendor.** **Artı:** minimal blast radius and device load; clean pilot metrics; when Phase 2 adds ASA and FortiGate policy triggers, their policy *is* derived from `asa_configuration_collect` / `fgt_configuration_collect`, automatically upgrading ASA/FGT policy freshness from 24h (`00:00` daily) to minutes. **Eksi:** Check Point and Palo Alto device configuration (interfaces, routes, OS settings — collected **once daily at `00:00`** by `ConfigurationRefreshScheduler`, **not** every 6 hours) remains on its daily 24-hour schedule; and once ASA/FortiGate config triggers are enabled, each triggered run writes a new gzipped envelope-encrypted artefact (`configuration_artefact` / `backup_artefact`), increasing PVC disk and retention churn if edits are frequent.
- **Option 5B — Trigger both policy and configuration planes for all vendors from the start.** **Artı:** keeps Configuration and Compliance planes fresh alongside Policy. **Eksi:** much broader event-allowlist and `gate_registry` surface; high-frequency CLI/OS edits cause frequent full-config SSH/API pulls and encrypted-artefact storage churn on `/app/artefact-store`.

**Q6. Job-window exemption, `gate_registry.max_frequency` amendments, and scheduling safeguards?**
- **Recommended — PO APPROVED (2026-11-26): Approve a single bundled `EVENT_TRIGGERED` operational amendment containing all six mandatory safeguards:**
  1. Dedicated `jobs.trigger_window_exemption` column + partial index in `JooqJobLeaseDao.CLAIM_SQL` / `ClaimStatementText.SQL` (never overloading admin `window_bypass_role` in `V168`);
  2. Explicit `gate_registry.max_frequency` migration updates naming each affected `CLASS_0` policy/config `gate_id` (`V116`, `V123`, `V124`, `V125`, `V130`, `V163`, `V192` for CP; `V117`, `V221` for PAN Phase 2; `V78`, `V80` for ASA/FGT Phase 2) bounded to `<= 6`/unit/hour (10-min cooldown) and `<= 30`/hour fleet-wide;
  3. Hard **`01:45–03:00 Europe/Istanbul` backup blackout** (protecting `02:00–02:59` fleet backups and `02:30` `cp_mds_export` TAC serialization) plus **`00/06/12/18` scheduled-run non-starvation** (`DEFERRED_SOURCE_BUSY` + pre-window guard + sticky `pending_scheduled_run` `FULL`/`CHANGED_ONLY` latch);
  4. Audited **`security_admin` runtime kill-switch** (global + per-source instant freeze);
  5. Collector bounds: CP `CHANGED_ONLY` (no `show-hits`), Panorama `skipHits = true` + ancestor-only DG fetch, and separate `CpShowChangesReader` diff cursor in `PolicyCompletenessCoordinator` so out-of-window runs never lose `show-changes` diffs;
  6. Validation under **`UI2_JOB_WINDOW_MINUTES=60`** (reconciling the stale `"360"` setting in `57-policy-deployment.yaml`).
  **Artı:** the only option that delivers daytime policy freshness within minutes while preserving `V168` admin audit integrity, `cp_mds_export` safety, `00:00 FULL` hit counts, `show-changes` completeness, and one-click operator stop control. **Eksi:** amends PO 2026-10-20 ("automatic jobs keep the windows") and `gate_registry` 6-hour ceilings for this bounded class; opens read-only MDS/device sessions during business hours after real changes.
- **Option 6B — Outside business hours only (`20:00–08:00`, excluding `01:45–03:00` backup blackout).** **Artı:** zero daytime device sessions; covers night maintenance windows (`21:00–24:00`). **Eksi:** daytime policy installs still wait up to 6 hours for the `12:00` or `18:00` window.
- **Option 6C — Hold all events to the next `00/06/12/18` window (`WINDOW_HELD`).** **Artı:** zero window or `gate_registry` frequency changes. **Eksi:** forfeits "collect within minutes", providing almost nothing beyond today's 6h `CHANGED_ONLY` schedule.

**Q7. Check Point collection granularity, large-Domain SLO, and gateway `cpstat` boundary?**
- **Recommended — PO APPROVED (2026-11-26): Domain-level `CHANGED_ONLY` (`cp_policy_collect`) in Phase 1 with a Domain-size-aware SLO (`<= 10 min` p95 for `< 5k`-rule Domains; `<= 35–45 min` p95 for `15k+`-rule Domains across `<= 4` sessions; `<= 3 min` p95 dispatch when idle), keeping per-gateway `device_policy_install` (`cpstat`) as a separate Phase 2 option.** **Artı:** zero new CP collector commands; `CpDomainReuse` returns `REUSED` in seconds if the session was already collected after an earlier Publish; preserves full rulebase + object-dictionary consistency. **Eksi:** an install of one small package in a `15k+`-rule Domain still takes ~20–30 min (4 sessions) or ~75 min (serial) when published objects changed; refreshes management `policy_snapshot` intent only, not gateway `cpstat` install timestamps.
- **Option 7B — Build a per-package CP partial-collection seam and/or Phase 2 bounded `cpstat -f policy fw` refresh on `policy_target_binding` gateways.** **Artı:** faster turnaround on `15k+`-rule Domains and immediate gateway enforcement proof (`device_policy_install`) even when `CpDomainReuse` is `REUSED`. **Eksi:** substantial collector complexity to prove shared-layer/object completeness without full-Domain read; gateway `cpstat` fan-out requires its own `gate_registry` frequency amendment and per-gateway endpoint admission budget.

**Q8. Receiver placement, job-submission mechanism, and module governance footprint?**
- **Recommended — PO APPROVED (2026-11-26): Separate `ui2-event-intake` pod (`PriorityClass: ui2-low`, zero device-network egress, zero device/artefact secrets) submitting due triggers via an internal SHA-256-token-authenticated HTTP call to `ui2-service` (`ui2_event_intake` DB role holds ZERO grants on `jobs`), with explicit amendments to `MODULE_POD_RUNTIME_CONTRACT.md` §1, `module_runtime_control_module_check` (`V258`), and `46-database-networkpolicy.yaml`.** **Artı:** strongest blast-radius and least-privilege isolation; centralizes all job/window/kill-switch admission in `ui2-service` Java code without duplicating logic in PL/pgSQL; even full pod compromise cannot touch devices, credentials, artefacts, or `jobs`. **Eksi:** one extra Deployment/Service/NetworkPolicy, narrow DB role, internal token Secret, and runtime-contract amendment.
- **Option 8B — Separate `ui2-event-intake` pod calling a PostgreSQL `SECURITY DEFINER` RPC (`fn_submit_event_triggered_job`) instead of internal HTTP to `ui2-service`.** **Artı:** avoids the internal HTTP call to `ui2-service`. **Eksi:** duplicates `PolicyCollectionRepository.enqueue`, backup-window blackout, and kill-switch logic inside PL/pgSQL and gives the network-facing pod's DB session direct job-enqueue capability.
- **Option 8C — Listener inside `ui2-service`.** **Artı:** no new pod or `46-database-networkpolicy.yaml` update. **Eksi:** couples external event traffic directly into the console/failover JVM that holds credential-store, artefact-store and full `ui2_app` secrets — **not recommended**.

**Q9. HOST-A and corporate OpenShift ingress path (conditional on Q1)?**
- **If Q1 = Option 1B (Splunk HTTPS webhook) — Recommended — PO APPROVED (2026-11-26): Reuse existing HTTPS `443` edge (`Traefik` `websecure` path/host route on HOST-A; standard HTTPS `Route` on OpenShift) forwarding only `/api/v1/event-intake/*` to `ui2-event-intake`.** **Artı:** zero new ports, zero `nexus-host-firewall.nft` DNAT hazards, native OpenShift `Route` compatibility. **Eksi:** available only for HTTPS webhook.
- **If Q1 = Option 1A or 1C (Syslog TLS `6514`):**
  - *Sub-option 9A (Recommended — PO APPROVED (2026-11-26) when all syslog senders use `TLS_MUTUAL`, `Profile A`/`B`):* **Traefik TCP entryPoint `6514` with TLS passthrough + explicit negated `drop` (`ip saddr != @syslog_allowed drop` + IPv6 drop) in `nexus-host-firewall.nft` `chain prerouting` (`priority -150`, verified on HOST-A).** **Artı:** single TLS/TCP edge router on HOST-A. **Eksi:** Traefik TCP proxying masks client source IP to cluster pod IP (so **Profile C IP-allowlist cannot be used behind Traefik**); corporate OpenShift still requires either client SNI on `443` passthrough (`UNKNOWN`) or a platform-approved `LoadBalancer`/NodePort Service for `6514`.
  - *Sub-option 9B (PO APPROVED (2026-11-26) if peer source IP must be preserved for `Profile C`):* **`hostPort: 6514` on `ui2-event-intake` + mandatory negated `drop` (`iifname "ens160" ct state new tcp dport 6514 ip saddr != @syslog_allowed drop` + IPv6 drop) in `chain prerouting` (`priority -150`, before `dstnat -100`, because `prerouting` has `policy accept`).** **Artı:** preserves un-NATed client source IP to the socket. **Eksi:** bypasses Traefik, limits scheduling to one intake pod per node, and relies critically on the `prerouting` negated `drop` rule.

## 10. What this document does not settle (`UNKNOWN` summary)

- **1-day SIEM / collector fact check (section 3, Q1):** whether a pre-SIEM `syslog-ng`/`rsyslog` tier exists, which
  vendors' audit/config logs reach the collector/Splunk today, whether opaque non-hostname IDs (`serial`, `devid`,
  `dg_id`, or per-stream `opaque_emitter_key`) survive, and real-time vs scheduled Splunk alert latency.
- **Check Point vendor specifics (section 2.1):** whether operation-7 audit logs carry package/gateway/Domain fields or
  `loguid` in JSON/Splunk format; the exact `cp_log_export` / `FilterConfiguration.xml` configuration on R82 MDS that
  guarantees an **audit-only** export stream (zero Security/traffic logs — mandatory prerequisite before direct connection).
- **Palo Alto vendor specifics (section 2.2):** presence of `serial` in forwarded Config-log headers; the exact Panorama
  `commit-all` / push log message and whether it populates `dg_id` / `dg_hier_level_1..4`.
- **Fortinet vendor specifics (section 2.3):** whether `44547` prepends `devid` in the syslog header and proves exact
  inventory serial equality; exact `cfgpath` prefixes covering all policy/NAT/object edits; whether FortiOS `certificate`
  supports mutual-TLS client auth.
- **Cisco vendor specifics (section 2.4):** whether estate ASAs already have `logging permit-hostdown` configured; ASA
  mutual-TLS client-cert support; FMC per-device deploy-success syslog shape (FMC out of scope).
- **Platform & transport specifics (section 4.3, 6.2):** whether k3s Traefik on HOST-A receives `80/443`/`6514` via
  ServiceLB `prerouting` DNAT or host `input` (verified on HOST-A before firewall edit); whether vendor syslog-TLS
  clients send TLS `SNI` for OpenShift `Route` passthrough or require an OpenShift `LoadBalancer` Service; how long each
  vendor buffers TCP/TLS syslog during a receiver pod restart.
