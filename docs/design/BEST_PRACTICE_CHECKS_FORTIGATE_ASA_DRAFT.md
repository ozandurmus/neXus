**Status:** FROZEN for the 16 controls listed in "Frozen scope (PO decision 2026-09-27, option A)" at the end of this
document; every other candidate row stays DRAFT and is not implementation authority.

# FortiGate and Cisco ASA best-practice check candidates

This is a configuration-evidence proposal, not an approved control catalog, device-command gate, or authorization to collect more data. Candidate severities are provisional; no framework mapping or Overview counting policy is approved here. The source column identifies vendor guidance for the setting or recommendation, not a certified CIS control number. CIS FortiGate 7.x and Cisco ASA benchmark section numbers remain **UNKNOWN** until the exact licensed editions and versions are reviewed.

## Fit with the existing implementation

`CheckPointComplianceCatalog` and `PaloAltoComplianceCatalog` in `ui2/worker/.../compliance/catalog/` define Java `ComplianceControl` records, not YAML. Each has an opaque ID, title, rationale, `Severity` (`CRITICAL`, `HIGH`, `MEDIUM`, `LOW`), control plane and scope, explicit `FrameworkMapping` entries (framework, reference, version, mapping strength), and one or more vendor bindings. A binding names the vendor/platform, `EvidenceRequirement` (section, setting key, evidence ID, gate ID), and `AssertionRule`. The Check Point evaluator resolves parsed `ConfigSection`/`ConfigSetting` values or sanitized text; the Palo Alto evaluator reads its configuration projection. The assertion engine supports presence, absence, equality, regex, numeric comparisons and list checks. Results distinguish `PASS`, `FAIL`, `UNKNOWN` and `NOT_APPLICABLE`; missing evidence may display as `DATA_UNAVAILABLE`. The compliance service currently exposes only Check Point and Palo Alto catalogs/evaluators, so these rows describe future work only.

FortiGate's current `show` projection retains non-secret `config`/`edit`/`set` lines, indexes the outer section per VDOM, and replaces secret-bearing settings with `[withheld]`. ASA's single-context `more system:running-config` projection retains safe lines and masks lines containing password, secret, key or certificate material. Thus a future evaluator must inspect **sanitized text with block/context boundaries**, not just section counts; it must never inspect withheld values. FortiOS `show` can omit defaults; missing lines require a version-specific proven default or yield `UNAVAILABLE`, never an automatic PASS. A missing section, partial collection, masked line, unproven context, or unsupported version likewise yields `UNKNOWN / DATA_UNAVAILABLE`. A visible contradictory line can support FAIL only when its semantics and scope are established. Configuration intent never proves live service exposure, peer health, time synchronization, log delivery, or firmware support.

In the tables, `P` means PASS, `F` FAIL, and `U` UNKNOWN / DATA_UNAVAILABLE. `U` also applies whenever the prerequisite is unproven. Placeholders in patterns are grammar markers, not collected identity values. For interface and administrator lists, evaluate **every applicable entry**; a single passing entry is insufficient. `UNKNOWN` in a row flags a proposed threshold or interpretation needing vendor/version evidence before implementation. No gate IDs are proposed.

## FortiGate candidates (15)

Evidence is the existing sanitized FortiOS `show` text. `config global` versus `config vdom` and each `edit` boundary must be preserved. Version references below use FortiOS 7.4 documentation as a syntax anchor; applicability to the fleet's actual versions is unverified.

| Group | Proposed ID / title | Severity | Exact configuration evidence | Proposed P / F / U rule | Source |
| --- | --- | --- | --- | --- | --- |
| Management access | `fg_admin_no_cleartext` / No HTTP or Telnet on managed interfaces | high | `config system interface › edit <interface> › set allowaccess <services>` | P: every in-scope interface's explicit list excludes `http` and `telnet`; F: either token appears; U: interface inventory, VDOM scope, or omitted default is unresolved. | [F1], [F2] |
| Management access | `fg_admin_trusted_hosts` / Restrict administrator source networks | high | `config system admin › edit <admin> › set trusthost1..10 <network>` and `set ip6-trusthost1..10 <prefix>` | P: each interactive administrator has a proven restrictive IPv4/IPv6 effective allowlist; F: a proven unrestricted effective allowlist; U: omitted defaults, alternate access paths or address classification unresolved. **UNKNOWN:** what counts as an approved trusted network is policy-dependent. | [F2], [F3] |
| Management access | `fg_admin_idle_timeout` / Bound idle administrator sessions | medium | `config system global › set admintimeout <minutes>` | P: explicit value 1–10; F: explicit value above 10; U: absent/unreadable. **UNKNOWN:** 10-minute threshold awaits PO baseline. | [F3] |
| Authentication & accounts | `fg_admin_lockout_attempts` / Limit failed logins | high | `config system global › set admin-lockout-threshold <count>` | P: explicit 1–5; F: explicit 6–10; U: absent/unreadable. **UNKNOWN:** five-attempt threshold awaits PO baseline. | [F3], [F4] |
| Authentication & accounts | `fg_admin_lockout_duration` / Hold locked accounts | medium | `config system global › set admin-lockout-duration <seconds>` | P: explicit value at least 300; F: explicit lower value; U: absent/unreadable. **UNKNOWN:** five-minute threshold awaits PO baseline. | [F3], [F4] |
| Authentication & accounts | `fg_admin_mfa` / Require MFA for interactive administrators | high | `config system admin › edit <admin> › set two-factor <mode>` | P: each applicable account has an effective non-`disable` mode; F: a proven password-only account has `disable`; U: external authentication, PKI, emergency-account policy, or omitted defaults unresolved. **UNKNOWN:** equivalent external MFA proof cannot come from this field. | [F3], [F2] |
| Logging / NTP / SNMP | `fg_remote_logging` / Configure remote log destination | high | `config log syslogd setting › set status enable › set server <destination>` **or** `config log fortianalyzer setting › set status enable › set server <destination>` | P: at least one complete enabled destination in the relevant VDOM; F: all observed destinations explicitly disabled or incomplete in a complete projection; U: no complete collection or alternative destination not assessed. Delivery remains runtime-only. | [F5] |
| Logging / NTP / SNMP | `fg_ntp_configured` / Configure time synchronization | medium | `config system ntp › set ntpsync enable`; for custom source, `config ntpserver › edit <id> › set server <destination>` | P: `ntpsync enable` and a proven FortiGuard or custom source; F: explicit `ntpsync disable`; U: source/default unavailable. Synchronized time needs runtime evidence. | [F6], [F2] |
| Logging / NTP / SNMP | `fg_snmp_legacy_community` / Avoid legacy SNMP community configuration | medium | `config system snmp community › edit <id>`; correlate `config system interface › edit <interface> › set allowaccess ... snmp ...` | P: complete configuration proves no active community and, if SNMP is used, v3-only configuration; F: active community plus SNMP exposure; U: omitted defaults or operational exposure unresolved. **UNKNOWN:** exact enable/disable semantics must be checked per version. | [F2], [F7] |
| Crypto / TLS / SSH | `fg_ssh_v1_disabled` / Disable SSH v1 compatibility | high | `config system global › set admin-ssh-v1 disable` | P: explicit `disable`; F: explicit `enable`; U: line absent without proven version default. | [F3] |
| Crypto / TLS / SSH | `fg_admin_tls_minimum` / Limit admin TLS to 1.2+ | high | `config system global › set admin-https-ssl-versions <versions>` | P: explicit list contains only `tlsv1-2`/`tlsv1-3`; F: explicit list includes older versions; U: omitted default or release-specific token semantics. | [F3], [F2] |
| Crypto / TLS / SSH | `fg_global_telnet_disabled` / Disable global Telnet service | high | `config system global › set admin-telnet disable` | P: explicit `disable`; F: explicit `enable`; U: absent/unreadable. Interface exposure is separately assessed above. | [F3], [F2] |
| HA | `fg_ha_heartbeat_defined` / Define heartbeat interfaces for configured HA | high | `config system ha › set mode a-p|a-a` plus `set hbdev <interface-priority pairs>` | P: HA mode and nonempty heartbeat list both explicit; F: HA mode explicit with proven empty heartbeat configuration; U: omission/default or member coverage unresolved. NOT_APPLICABLE only for independently established standalone devices. No health claim. | [F8] |
| HA | `fg_ha_mode_consistent` / Compare declared HA mode across known members | medium | Each independently collected member's `config system ha › set mode <mode>` | P: all known members explicitly declare the same HA mode; F: explicit mismatch; U: one-sided/missing observation or unproven pair identity. **UNKNOWN:** whether mode alone is a useful best-practice threshold; this is a consistency candidate, not health. | [F8] |
| Firmware / support | `fg_firmware_supported` / Run supported firmware | high | No retained version evidence: the processor drops `#config-version` headers | P/F: **never from current configuration**; U until separate trusted runtime version and support/advisory source are available. Listed again below because it cannot be a config-only check. | [F2] |

## Cisco ASA candidates (16)

Evidence is the existing sanitized **single-context** ASA running-configuration text. The ASA processor masks any line containing `key`, including some non-secret SSH or failover settings; masked lines cannot support PASS or FAIL. The Cisco hardening guide is older; current ASA command references control version-specific syntax. All HA rows are conditional on an independently established pair and correct system context.

| Group | Proposed ID / title | Severity | Exact configuration evidence | Proposed P / F / U rule | Source |
| --- | --- | --- | --- | --- | --- |
| Management access | `asa_ssh_sources_restricted` / Restrict SSH management sources | high | Global `ssh <network> <mask> <interface>` lines | P: every effective SSH rule is within PO-approved management ranges; F: a proven unrestricted rule; U: range policy, context, or complete rule set unknown. **UNKNOWN:** approved ranges are a PO input. | [C1], [C2] |
| Management access | `asa_http_sources_restricted` / Restrict ASDM HTTPS sources | high | `http server enable [<port>]` and `http <network> <mask> <interface>` | P: enabled HTTPS has only approved source rules, or HTTPS is proven disabled; F: enabled and a proven unrestricted rule; U: omitted default/context/range policy unknown. | [C1], [C2] |
| Management access | `asa_telnet_absent` / Avoid Telnet management | high | Global `telnet <network> <mask> <interface>` lines | P: complete config proves no Telnet access rule; F: any effective Telnet rule; U: incomplete config or other context. Absence is usable only after complete projection is proven. | [C1], [C2] |
| Management access | `asa_ssh_idle_timeout` / Bound SSH idle sessions | medium | `ssh timeout <minutes>` | P: explicit 1–10; F: explicit value above 10; U: absent/unreadable. **UNKNOWN:** 10-minute threshold awaits PO baseline. | [C1], [C2] |
| Authentication & accounts | `asa_ssh_aaa` / Authenticate SSH administrators individually | high | `aaa authentication ssh console LOCAL|<server-group> [LOCAL]` | P: valid explicit AAA method plus independently proven local or server group; F: explicit disabled method; U: public-key-only accounts, absent/default behavior or group validity unresolved. | [C2], [C3] |
| Authentication & accounts | `asa_http_aaa` / Authenticate ASDM administrators individually | high | `http server enable`; `aaa authentication http console LOCAL|<server-group> [LOCAL]` | P: when ASDM is enabled, valid explicit AAA method; F: ASDM enabled with proven missing effective AAA; U: omitted/default semantics or group validity. NOT_APPLICABLE when ASDM is proven disabled. | [C2], [C3] |
| Authentication & accounts | `asa_admin_accounting` / Account for SSH sessions | medium | `aaa accounting ssh console <server-group>` | P: explicit command and proven group; F: explicit disable under complete config; U: group viability or absent/default behavior unresolved. **UNKNOWN:** accounting destination and retention policy are outside config proof. | [C3] |
| Logging / NTP / SNMP | `asa_logging_enabled` / Enable system logging | high | Global `logging enable` / `no logging enable` | P: explicit enable; F: explicit disable; U: neither or incomplete projection. | [C1] |
| Logging / NTP / SNMP | `asa_remote_syslog` / Configure remote log destination | high | `logging enable` plus `logging host <interface> <destination> [<transport>]` | P: enabled and at least one visible destination; F: enabled with proven complete config and no destination; U: masked/incomplete line or alternative sink. Delivery needs runtime evidence. | [C1] |
| Logging / NTP / SNMP | `asa_log_timestamps` / Timestamp logs | medium | `logging timestamp` / `no logging timestamp` | P: explicit enable; F: explicit disable; U: absent without proven default. | [C1] |
| Logging / NTP / SNMP | `asa_ntp_server` / Configure NTP source | medium | Global `ntp server <destination> [options]` | P: at least one valid configured source; F: proven complete config with no source and no approved alternative; U: incomplete config or alternative time source. Actual synchronization is runtime-only. | [C1] |
| Crypto / TLS / SSH | `asa_ssh_v2` / Require SSH protocol v2 | high | `ssh version 2` (where supported) | P: explicit v2 or documented version-specific v2-only default; F: proven v1-enabled release/configuration; U: version missing or command removed. **UNKNOWN:** Cisco documents removal of `ssh version` in 9.16; syntax alone cannot be required across releases. | [C1], [C4] |
| Crypto / TLS / SSH | `asa_tls_floor` / Require TLS 1.2+ | high | `ssl server-version tlsv1.2|tlsv1.3 [dtls...]` | P: explicit minimum 1.2+ on applicable release; F: explicit older minimum; U: omitted/default version or service scope unknown. **UNKNOWN:** server-version also affects VPN; PO must confirm desired scope. | [C5] |
| HA | `asa_failover_link` / Define failover communication link | high | System-context `failover`; `failover lan interface <name> <interface>` | P: known pair and both settings explicit in each member's system context; F: configured failover with proven missing link; U: single-context projection, other member, or pair identity unavailable. No health claim. | [C6] |
| HA | `asa_stateful_link` / Define stateful failover link | medium | System-context `failover link <name> [<interface>]` | P: known pair, applicable mode and explicit link; F: required by PO policy but proven absent; U: stateful failover requirement, shared-link variant or system context unresolved. **UNKNOWN:** stateful failover is a design choice, not universally mandatory. | [C6] |
| Firmware / support | `asa_firmware_supported` / Run supported signed image | high | `ASA Version <version>` may be present in configuration text; `boot system <image>` names an image but proves neither running version nor support/signature | P/F: **never from current configuration**; U until trusted running-image and vendor support/signature evidence exist. | [C1] |

## Checks requiring runtime or external evidence

These are **not** approvals to run a new command. The read/provenance would need its own PO-approved gate and safety review before collection; no exact endpoint or command is proposed here.

| Check | Additional read or evidence needed | Why config is insufficient |
| --- | --- | --- |
| FortiGate supported firmware, relevant PSIRT advisories, support registration | Identity-verified running FortiOS/build plus current vendor lifecycle/advisory/support status | `#config-version` is discarded; configured image or past backup cannot prove running/support state. |
| ASA supported, signed running image | Identity-verified running software/build, signature verification outcome and current Cisco support/advisory status | `ASA Version`/`boot system` text cannot prove active image integrity or support. |
| HA membership, roles, heartbeat and synchronization (both vendors) | Independent, same-pass status from every member, joined by verified opaque identity | One configuration or one peer report proves neither pair health nor bidirectional agreement. |
| NTP synchronization and clock accuracy (both vendors) | Current sync state, selected source and measured offset | Server configuration proves intent only. |
| Remote log delivery (both vendors) | Current sender state plus collector receipt/lag under a sanctioned evidence path | Destination configuration does not prove delivery, retention or integrity. |
| Exposed management service and TLS/SSH negotiation (both vendors) | Authorized runtime listener/handshake observation with approved scope | Enabled settings do not prove reachability, effective policy, or negotiated protocol. |
| MFA actually enforced and external AAA available (both vendors) | Approved authentication-path/identity-provider evidence without credential or secret disclosure | Local configuration does not prove external enforcement or service availability. |

## Product Owner decisions needed before implementation

1. Which exact CIS FortiGate 7.x and Cisco ASA benchmark **editions/versions and section references** may be used, and which candidates map to CIS, PCI DSS, NIST 800-53, or the financial baseline? Do not infer a mapping from a similar Check Point/Palo Alto title.
2. Which provisional severities count as **critical** on Overview, and is critical based on severity alone or only on selected failed controls? Unknown/unavailable must remain separate from FAIL.
3. What are the approved thresholds and exceptions for administrator timeout, lockout, MFA, management source ranges, syslog destinations and stateful HA? Confirm the actual FortiOS/ASA version range and whether ASA system/multiple-context configurations are in scope.
4. Should unsupported, incomplete or default-omitting configuration projections show `DATA_UNAVAILABLE`, and which independently proven defaults may be evaluated? No silent FAIL/PASS from a missing line.

## Source key

- [F1] Fortinet, [FortiOS 7.4.6 CLI Reference: `config system interface`](https://docs.fortinet.com/document/fortigate/7.4.6/cli-reference/317104469/config-system-interface).
- [F2] Fortinet, [FortiOS 7.4 Best Practices: Hardening](https://docs.fortinet.com/document/fortigate/7.4.0/best-practices/555436).
- [F3] Fortinet, [FortiOS 7.4.6 CLI Reference: `config system global`](https://docs.fortinet.com/document/fortigate/7.4.6/cli-reference/339914554/config-system-global) and [`config system admin`](https://docs.fortinet.com/document/fortigate/7.4.6/cli-reference/390485493/config-system-admin).
- [F4] Fortinet, [Setting administrator password retries and lockout time](https://docs.fortinet.com/document/fortigate/7.4.3/administration-guide/631730/setting-the-administrator-password-retries-and-lockout-time).
- [F5] Fortinet, [FortiOS 7.4.6 Administration Guide: Log settings and targets](https://docs.fortinet.com/document/fortigate/7.4.6/administration-guide/250999/log-settings-and-targets).
- [F6] Fortinet, [FortiOS 7.4 CLI Reference: `config system ntp`](https://docs.fortinet.com/document/fortigate/7.4.10/cli-reference/105110478/config-system-ntp).
- [F7] Fortinet, [FortiOS 7.4 CLI Reference: `config system snmp community`](https://docs.fortinet.com/document/fortigate/7.4.7/cli-reference/111967164).
- [F8] Fortinet, [FortiOS 7.4.6 Administration Guide: Failover protection](https://docs.fortinet.com/document/fortigate/7.4.6/administration-guide/489324).
- [C1] Cisco, [Guide to Harden Cisco ASA Firewall](https://www.cisco.com/c/dam/en/us/support/docs/security/asa-5500-x-series-next-generation-firewalls/200150-Hardening-Cisco-ASA-Firewall.pdf) (historical guide; validate current release semantics separately).
- [C2] Cisco, [ASA 9.20 General Operations: Management Access](https://www.cisco.com/c/en/us/td/docs/security/asa/asa920/configuration/general/asa-920-general-config/admin-management.html).
- [C3] Cisco, [ASA Command Reference: AAA authentication/accounting](https://www.cisco.com/c/en/us/td/docs/security/asa/asa-cli-reference/A-H/asa-command-ref-A-H/aa-ac-commands.html).
- [C4] Cisco, [ASA Command Reference: SSH version and timeout](https://www.cisco.com/c/en/us/td/docs/security/asa/asa-cli-reference/S/asa-command-ref-S/so-st-commands.html).
- [C5] Cisco, [ASA 9.20 VPN CLI Guide: General VPN Parameters](https://www.cisco.com/c/en/us/td/docs/security/asa/asa920/configuration/vpn/asa-920-vpn-config/vpn-params.html).
- [C6] Cisco, [ASA 9.20 General Operations: Failover for High Availability](https://www.cisco.com/content/en/us/td/docs/security/asa/asa920/configuration/general/asa-920-general-config.pdf).

## Product Owner direction (2026-09-27)
"CP ve PAN'da uyguladığın gibi hepsi için değerlendirebiliriz; yakalayabiliyorsak çekelim o veriyi Forti'de ve ASA'da."
Implement the configuration-evaluable candidates for FortiGate and Cisco ASA the same way Check Point and Palo Alto
controls work today; checks needing runtime reads wait for their command approval. Severity and framework mapping follow
the closest existing Check Point / Palo Alto control until the PO adjusts them.

## Frozen scope (PO decision 2026-09-27, option A)
The Product Owner chose option A: freeze the candidates that the stored sanitized configuration can evaluate today,
with the draft severities as written, and implement them now. This section is implementation authority for exactly
these 16 control ids; the rest of the document remains DRAFT.

**FortiGate (7):** `fg_admin_no_cleartext`, `fg_remote_logging`, `fg_ntp_configured`, `fg_ssh_v1_disabled`,
`fg_admin_tls_minimum`, `fg_global_telnet_disabled`, `fg_ha_heartbeat_defined`.

**Cisco ASA (9):** `asa_http_sources_restricted`, `asa_telnet_absent`, `asa_ssh_aaa`, `asa_http_aaa`,
`asa_logging_enabled`, `asa_remote_syslog`, `asa_log_timestamps`, `asa_ntp_server`, `asa_failover_link`.

Rules that bind the implementation:
1. PASS / FAIL / UNKNOWN exactly as each row's rule states. A missing line without a proven default, a masked
   (`[withheld]` or masked-by-keyword) line, a partial collection, or an unproven VDOM/context yields UNKNOWN
   (displayed as data unavailable), never PASS or FAIL.
2. Severity: the row's severity. Framework mapping: the closest existing Check Point / Palo Alto control's mapping,
   named in a code comment; the PO may adjust later without re-freezing.
3. `asa_http_sources_restricted`: until the PO supplies approved management ranges, "unrestricted" means an
   explicit any-source rule (`http 0.0.0.0 0.0.0.0 <interface>`, or `::/0`) -> FAIL; HTTPS server proven disabled,
   or every `http` rule narrower than any-source -> PASS; otherwise UNKNOWN.
4. HA rows (`fg_ha_heartbeat_defined`, `asa_failover_link`) make no health claim; NOT_APPLICABLE only when the
   configuration explicitly shows no HA/failover.
5. No new device command, no new read: evaluation uses only the already-stored configuration projections.

Excluded until their UNKNOWNs are resolved (PO baseline, runtime read, or version proof): `fg_admin_trusted_hosts`,
`fg_admin_idle_timeout`, `fg_admin_lockout_attempts`, `fg_admin_lockout_duration`, `fg_admin_mfa`,
`fg_snmp_legacy_community`, `fg_ha_mode_consistent`, `fg_firmware_supported`, `asa_ssh_sources_restricted`,
`asa_ssh_idle_timeout`, `asa_admin_accounting`, `asa_ssh_v2`, `asa_tls_floor`, `asa_stateful_link`,
`asa_firmware_supported`.
