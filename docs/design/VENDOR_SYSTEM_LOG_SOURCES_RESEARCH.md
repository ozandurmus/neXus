<!-- Status: RESEARCH (reference only, not implementation authority). Public-documentation research for the device system-log programme (DEVICE_SYSTEM_LOG_PROGRAMME_CONTRACT.md), 2026-10-15. -->
# Device system/event log sources: per-vendor read paths (non Check Point / non PAN-OS)

Research date: 2026-10-15. Scope: each managed device's **own** system/event log (not traffic logs), read **read-only** and **incrementally** over SSH/CLI or the device's REST/XML API.

Conventions:
- Every claim carries a source tag like `[F1]`. All sources are listed at the end of each vendor section.
- **UNCONFIRMED** = not found in official vendor documentation during this research. That includes behaviour seen only in community posts, third-party code, or inferred by me.
- **NOT READ-ONLY** marks commands or calls that change device state or configuration. Never send these from a read-only collector.

---

## 1. Fortinet FortiGate (FortiOS 7.x)

### 1.1 Where the logs live
- **Memory**: logging to memory is on by default. When the buffer is full, the oldest messages are overwritten, and all memory logs are cleared when the unit restarts. The statement comes from FortiOS 5.2 help; I found no 7.4 page that restates it. [F5]
- **Disk**: available only on models with a disk/SSD. On flash-only units it is disabled by default and Fortinet recommends keeping it off. To check, run `get system status` and look for "Log hard disk". [F5][F6]
- Event logs are split by **subtype**. Exposed selectors include `system`, `ha`, `vpn`, `user`, `router`, `wireless`, `wad`, `endpoint`, `connector`, `security-rating`, `fortiextender`, `compliance-check`. Each exists per source (`memory`, `disk`, `fortianalyzer`, `forticloud`). [F3]

### 1.2 CLI (SSH)
Documented `execute log` subcommands (FortiOS 7.4.11 CLI reference) [F1]:

```
execute log filter reset            # resets ALL filter settings ('reset field' resets only field filters)
execute log filter device <name>    # press Enter to list options (memory / disk ...)
execute log filter category <name>  # press Enter to list options (event, traffic, ...)
execute log filter field <name> <arg1..7>
execute log filter free-style <expression>
execute log filter ha-member <serial>
execute log filter start-line <n>   # n >= 1
execute log filter view-lines <n>   # 5..1000
execute log filter max-checklines <n>   # 0 or 100..1000000
execute log filter dump             # show current filter
execute log display                 # print the lines selected by the filter
```
- `view-lines` caps one display call at **1000 lines**. Paging is done by moving `start-line` forward and calling `display` again. [F1][F2]
- **Subtype selection inside the event category**: done with `filter field subtype <value>` or a `free-style` expression. **UNCONFIRMED** (the exact field names and expression grammar are not in the reference page; on the device, list them with `execute log filter field` + Enter).
- **NOT READ-ONLY (never send):** `execute log delete`, `delete-all` (also rebuilds the report DB), `roll`, `shift-time`, `restore`, `flush-cache`, `flush-cache-all`, `upload`, `fortianalyzer* manual-failover`. `backup`/`raw-backup` push data to external servers. [F1]
- The `execute log filter ...` commands do not change configuration, but they set **filter state** that later `display` calls depend on. Always start a session with `execute log filter reset` and set every dimension explicitly. Whether this state is per-session or per-admin is **UNCONFIRMED**.
- The order of `display` output (newest-first or oldest-first) is **UNCONFIRMED**. Measure it on a lab unit before relying on `start-line` for incremental reads.

### 1.3 REST API
- Path shape: `GET /api/v2/log/<source>/event/<subtype>`, e.g. `/api/v2/log/memory/event/system` or `/api/v2/log/disk/event/ha`. A traffic example of this pattern (`/api/v2/log/disk/traffic/forward/system`) is confirmed on a Fortinet community thread. The event/subtype mapping comes from the Fortinet-authored Ansible `fortios_log_fact` selectors (`disk_event_system` and so on). [F3][F4] The official per-version log API reference sits on FNDN, which needs a login, so **the exact paths are UNCONFIRMED in public vendor docs**.
- Query parameters for event selectors: `rows`, `start` (row number of the first row to return), `session_id` (continue a previous request), `serial_no` (retrieve from a given device, e.g. an HA member), `is_ha_member`, `filter`, `extra` (`reverse_lookup|country_id`), `vdom`. [F3]
- Response fields include `total_lines`, `start`, `rows`, `session_id`, `serial`, `vdom`, `version`, `build`, `status`, `subcategory`. [F3] Community evidence (7.2.9/7.2.10) shows `ready:false` and `percent_logs_processed`, which means the query is asynchronous: re-poll with the same `session_id` until it is ready. The default is 400 rows; `rows=999` was accepted. No documented maximum. [F4] (community, **UNCONFIRMED** in vendor docs)
- Raw format: append `/raw` to the selector path. [F3]
- Privileges: an API admin whose access profile has `loggrp read` ("Administrator access to Logging and Reporting including viewing log messages"). Use `loggrp custom` with `loggrp-permission data-access read` for finer scope. [F7][F8] GET on `/api/v2/log/*` is read-only by nature.

### 1.4 Content and incremental keys
- The header carries `date`, `time`, `logid` (10 digits; the last 6 digits are the message ID), `type`, `subtype`, `level`, `vd`, and `eventtime` (epoch time when the log was triggered; it may differ slightly from date/time). [F9][F10]
- Examples in the system subtype: `32002 LOG_ID_ADMIN_LOGIN_FAIL` (alert) and `44546 LOGID_EVENT_CONFIG_ATTR` ("Attribute configured", with config path and attribute fields). [F11][F12] HA failover events appear under subtype `ha`; VPN up/down under `vpn`. [F3] Specific HA, link, license and disk message IDs are **UNCONFIRMED** (look them up in the Log Message Reference per release).
- **Incremental key**: `eventtime` (plus `logid` and a content hash for de-duplication). There is no documented monotonic sequence number, so row offsets (`start`/`start-line`) shift as new logs arrive and are not stable cursors. A time filter in `filter` (e.g. on `date`/`time`/`eventtime`) is **UNCONFIRMED** syntax.

### 1.5 Pitfalls
- The memory buffer is lost on reboot and silently wraps. Daily reads can miss events on busy units, so read more often or prefer disk where present. [F5]
- `execute log display` and `filter view-lines` are capped at 1000 per call. [F1]
- The REST log query is asynchronous (`ready`, `session_id`). Polling must finish, or the query times out on the device. [F4] (community)

Sources (Fortinet):
- [F1] FortiOS 7.4.11 CLI reference, `execute log`: https://docs.fortinet.com/document/fortigate/7.4.11/cli-reference/209945028/execute-log (8.0.0 equivalent: https://docs.fortinet.com/document/fortigate/8.0.0/cli-reference/209945028/execute-log)
- [F2] Fortinet community, display more lines via CLI (view-lines 5–1000, default 10): https://community.fortinet.com/t5/FortiGate/Technical-Tip-Display-more-log-lines-through-CLI/ta-p/266906
- [F3] Fortinet-authored Ansible `fortios_log_fact` (selectors, params, return fields): https://ansible-galaxy-fortios-docs.readthedocs.io/en/galaxy-2.1.6/fortios_log_fact.html and https://ansible-galaxy-fortios-docs.readthedocs.io/en/main/fortios_log_fact.html
- [F4] Fortinet community, log API rows/default 400/session_id/ready: https://community.fortinet.com/support-forum-92/how-to-get-all-trafic-logs-by-rest-api-192777
- [F5] FortiOS 5.2 help, memory vs disk: https://help.fortinet.com/fos50hlp/52/FortiOS%205.2%20Help/logs.127.34.html ; FortiOS 7.4.1 admin guide log buffer: https://docs.fortinet.com/document/fortigate/7.4.1/administration-guide/574979
- [F6] Fortinet community, logging to local disk: https://community.fortinet.com/fortigate-3/technical-tip-configuring-logging-to-the-fortigate-local-hard-disk-95141
- [F7] FortiOS 7.4.0 CLI `config system accprofile` (loggrp): https://docs.fortinet.com/document/fortigate/7.4.0/cli-reference/2620/config-system-accprofile
- [F8] FortiOS 7.4.4 admin guide, administrator profiles: https://docs.fortinet.com/document/fortigate/7.4.4/administration-guide/294491
- [F9] FortiOS 7.4 log message fields: https://docs.fortinet.com/document/fortigate/7.4.3/fortios-log-message-reference/357866/log-message-fields
- [F10] FortiOS 7.4 log ID numbers: https://docs.fortinet.com/document/fortigate/7.4.0/fortios-log-message-reference/34314/log-id-numbers
- [F11] 32002 LOG_ID_ADMIN_LOGIN_FAIL: https://docs.fortinet.com/document/fortigate/7.4.2/fortios-log-message-reference/32002/32002-log-id-admin-login-fail
- [F12] 44546 LOGID_EVENT_CONFIG_ATTR: https://docs.fortinet.com/document/fortigate/7.4.4/fortios-log-message-reference/44546/44546-logid-event-config-attr

---

## 1b. Fortinet FortiManager (7.x)

### Where the logs live / what they contain
- The local Event Log is "an audit log of actions made by users". It is kept in memory or on the internal disk, viewed under *System Settings > Event Log*, filterable by level, user, sub type and message, and downloadable as CSV or normal format. A Historical Log view lists rolled files; that view also offers **delete/clear (NOT READ-ONLY)**. Displayed fields: Number, Date/Time, Level, User, Sub Type, Description, Operation, Performed On, Changes, Message. [M1]
- Subtypes include system (1), fgfm (2), HA (15), fgd (17), iolog (20), dvm (22), fmgws (23), docker (41). The 10-digit log_id encodes type, subtype and message ID. [M2]
- Local disk log settings: `config system locallog disk setting` (`max-log-file-size`, `roll-schedule`, `diskfull overwrite|nolog`, `log-disk-full-percentage`; 7.2+ adds `max-log-file-num`, `log-disk-quota`). Configuration only. [M3]

### CLI
- The FortiManager 7.4.5 `execute log` tree lists only `adom disk_quota`, `device disk_quota`, `device permissions`, `device vdom`, `dlp-files clear`, `import`, `ips-pkt clear`, `quarantine-files clear` and `storage-warning`, plus `log-fetch` and `log-integrity`. **None of these is a documented viewer for the local event log**, and the `... clear` ones are NOT READ-ONLY. [M4] `diagnose log device` shows device log usage, not event content. [M5]
- A CLI way to print FortiManager's own event log is **UNCONFIRMED** (none documented).

### API
- JSON-RPC over HTTPS: every call is a `POST /jsonrpc`. Login is `exec /sys/login/user`; 7.2+ supports REST API users with `Authorization: Bearer`. The admin needs "JSON API Access" set to Read or Read-Write (default: none). There are **32 session slots per user**, so always log out. The full object reference is on FNDN (login). [M6]
- A JSON-RPC URL that returns the **local event log** is **UNCONFIRMED**. A third-party MCP project calls `/sys/event/log`; this is not in any Fortinet document and should not be trusted without a lab test. [M7]
- Documented alternative: forward FortiManager local logs to syslog or FortiAnalyzer (`config system locallog syslogd ...` / `fortianalyzer`), which is a configuration change (NOT READ-ONLY; needs PO approval). [M8]

Sources (FortiManager):
- [M1] FortiManager 7.2.2 admin guide, Event Log: https://docs.fortinet.com/document/fortimanager/7.2.2/administration-guide/762822 (7.4.2: https://docs.fortinet.com/document/fortimanager/7.4.2/administration-guide/762822/event-log)
- [M2] FortiManager 7.4 log types and subtypes: https://docs.fortinet.com/document/fortimanager/7.4.0/log-message-reference/238528/fortimanager-log-types-and-subtypes (7.4.1: https://docs.fortinet.com/document/fortimanager/7.4.1/log-message-reference/238528/fortimanager-log-types-and-subtypes)
- [M3] FortiManager CLI `system locallog`: https://docs.fortinet.com/document/fortimanager/7.4.5/cli-reference/520182 ; https://docs.fortinet.com/document/fortimanager/7.2.0/cli-reference/520182
- [M4] FortiManager 7.4.5 CLI `execute`: https://docs.fortinet.com/document/FortiManager/7.4.5/cli-reference/23598/execute
- [M5] FortiManager 7.4.5 CLI `diagnose log`: https://docs.fortinet.com/document/fortimanager/7.4.5/cli-reference/556530
- [M6] Fortinet community, Using FortiManager/FortiAnalyzer API: https://community.fortinet.com/t5/FortiManager/Technical-Tip-Using-FortiManager-FortiAnalyzer-API/ta-p/221089 ; FMG API best practices: https://docs.fortinet.com/document/fortimanager/7.6.0/api-best-practices
- [M7] Third-party (not vendor): https://github.com/jmpijll/fortimanager-mcp (src/fortimanager_mcp/api/system.py)
- [M8] Fortinet community, sending FMG local logs to syslog / FortiAnalyzer: https://community.fortinet.com/fortimanager-27/technical-tip-configure-fortimanager-to-send-logs-to-a-syslog-server-93911 ; https://community.fortinet.com/fortimanager-27/technical-tip-how-to-send-fortimanager-local-event-logs-to-fortianalyzer-104630

---

## 2. Cisco ASA (9.x)

### Buffer behaviour
- `logging buffered [logging_list | level]` enables the internal buffer. It is disabled by default and needs `logging enable`. The default buffer size is **4 KB**; `logging buffer-size <bytes>` changes it. [C1]
- The buffer is circular: new messages are appended, and old ones are overwritten on wrap. **Changing the buffer size purges the existing buffered logs.** [C2]
- Maximum buffer size: the I–R command reference page gives no range. 1,048,576 bytes appears only in community posts (**UNCONFIRMED**). `logging flash-bufferwrap` will not write a buffer larger than 2 MB to flash. [C1][C2]
- Whether the buffer survives a reload is **UNCONFIRMED** (the 9.20 guide does not say; it is RAM-backed).

### Reading
```
show logging                         # buffer contents + logging settings
show logging setting                 # settings only
show logging message [syslog_id|all] # modified/disabled messages
show logging queue
show logging | include <regex>       # also: exclude | begin | grep [-v]
```
[C2][C3][C4]
- Paging: the pager shows `<--- More --->`. `terminal pager 0` changes **only the current session** and is not saved. `pager lines N` is a **global configuration command that is saved (NOT READ-ONLY)**. [C5][C4]
- **NOT READ-ONLY:** `clear logging buffer` (empties the buffer), `clear logging asdm`, `logging savelog`, any `logging ...` config line, `logging buffer-size` (also purges). [C2]

### Message content (examples from the official syslog guide)
- Failover: `%ASA-1-104001 Switching to ACTIVE`, `%ASA-1-104002 Switching to STANDBY`, `%ASA-1-103001 No response from other firewall`, `%ASA-1-105005 Lost Failover communications with mate`. [C6]
- Config/commands: `%ASA-5-111008 User executed the command`, `%ASA-5-111010 ... executed '<cmd>'`. [C6]
- Auth failures: `%ASA-6-113005` (AAA server), `%ASA-6-113015` (local DB). [C6]
- Reload/boot/crash: `%ASA-5-199001 Reloaded at ... by ... Reload reason`, `%ASA-6-199002 Startup completed`, `%ASA-6-199005 Startup begin`, `%ASA-1-199010 Signal ... caught`, `%ASA-1-199012 Stack overflow`. [C6]
- Link down: `%ASA-4-411002` ("Line protocol on Interface ... changed state to down"). This was seen only in third-party mirrors (**UNCONFIRMED** in the official page set I read). [C7]
- Format: `[<PRI>]: [Timestamp] [Device-ID] : %ASA-Level-Message_number: Message_text`. Message IDs run 100000–999999. [C2][C6]

### Incremental keys
- Timestamps appear only if `logging timestamp` is configured. 9.10(1)+ supports `logging timestamp rfc5424` (UTC, ISO format). Turning either on is a **configuration change (NOT READ-ONLY)**; check `show running-config logging` first. [C1][C2]
- **Sequence numbers:** no ASA syslog sequence-number option is documented in the 9.20 logging chapter or the I–R reference. `service sequence-number` is an IOS command, and I found **no evidence it exists on ASA (UNCONFIRMED)**. The incremental key is therefore timestamp + full-line hash. Duplicate lines within the same second are possible.
- Practical limit: at the default 4 KB with an informational buffer level, the buffer holds only seconds to minutes of history. A daily read will see only the tail. A larger buffer or a `logging list` restricted to system message IDs is a config change for the PO to decide. [C1][C2]

### Privileges
- Without local command authorization, privilege levels are ignored and users get level 15. With `aaa authorization command LOCAL`, defaults are: level 0 for a fixed small set (`show checksum`, `show curpriv`, `show version`, `pager`, ...), and **everything else at level 15**. A read-only collector needs, for example, `privilege show level 5 command logging` (configured once by the PO; NOT READ-ONLY to set). [C8]

### ASDM / REST / HTTP
- **ASA REST API**: supported only from 9.3(2) **through 9.16**. It is "not supported on newer hardware models and is no longer being developed". Cisco recommends the ASA HTTP interface instead. Monitoring calls need privilege ≥3; GET needs ≥5; PUT/POST/DELETE need 15. [C9]
- **ASA HTTP interface** (the ASDM transport): `GET https://<asa>/admin/exec/show+logging` with Basic auth and an allowed User-Agent (`ASDM`, `CSM`, `REST API Agent` by default). HTTP status reflects the connection, not the command result. **Caution: the same `/admin/exec/` path also accepts configuration-mode commands**, so a collector must hard-whitelist `show` commands. [C10]

Sources (Cisco):
- [C1] ASA Command Reference I–R, `logging ...`: https://www.cisco.com/c/en/us/td/docs/security/asa/asa-cli-reference/I-R/asa-command-ref-I-R/m_log-lz.html
- [C2] ASA 9.20 General Operations CLI guide, Logging: https://www.cisco.com/c/en/us/td/docs/security/asa/asa920/configuration/general/asa-920-general-config/monitor-syslog.html
- [C3] ASA Command Reference S, `show logging`: https://www.cisco.com/c/en/us/td/docs/security/asa/asa-cli-reference/S/asa-command-ref-S/m_show_is-show_m.html
- [C4] ASA Command Reference, Using the CLI (output filters, pager): https://www.cisco.com/c/en/us/td/docs/security/asa/asa-cli-reference/A-H/asa-command-ref-A-H/cli-usage.html
- [C5] ASA Command Reference T–Z, `terminal pager`: https://www.cisco.com/c/en/us/td/docs/security/asa/asa-cli-reference/T-Z/asa-command-ref-T-Z/m_ta-tk.html ; `pager`: https://www.cisco.com/c/en/us/td/docs/security/asa/asa-cli-reference/I-R/asa-command-ref-I-R/pa-pn-commands.html
- [C6] ASA Syslog Messages 101001–199027: https://www.cisco.com/c/en/us/td/docs/security/asa/syslog/asa-syslog/syslog-messages-101001-to-199021.html
- [C7] Third-party mirror for 411002: https://kb.eventtracker.com/evtpass/evtPages/MessageCode_ASA-4-411002_67862.asp
- [C8] ASA 9.20 Management Access (command authorization): https://www.cisco.com/c/en/us/td/docs/security/asa/asa920/configuration/general/asa-920-general-config/admin-management.html ; `privilege` command: https://www.cisco.com/c/en/us/td/docs/security/asa/asa-cli-reference/I-R/asa-command-ref-I-R/pr-pz-commands.html
- [C9] Cisco ASA REST API Quick Start Guide: https://www.cisco.com/c/en/us/td/docs/security/asa/api/quick-start/qsg-asa-api.html
- [C10] Cisco Secure Firewall ASA HTTP Interface for Automation: https://www.cisco.com/c/en/us/td/docs/security/asa/misc/http-interface/asa-http-interface.html

---

## 3. Radware DefensePro and Cyber Controller / APSolute Vision

**Documentation caveat:** Radware's current DefensePro, Vision and Cyber Controller guides and REST references sit behind the Radware customer portal. The only official guide I could read is a **Radware-authored DefensePro 8.13.01 user guide published by Cisco (2017)** [R1]. Treat anything version-specific as needing confirmation on 8.x / CC 10.x.

### What exists and where
- **Vision / Cyber Controller Alerts table**: shows "APSolute Vision alerts, device alerts, DefensePro security alerts, and device configuration messages". This is the central store of device health and audit events. [R1]
- DefensePro reports to Vision via SNMP traps (Vision installs itself as trap target when you click Submit) and via SRP over UDP 2088 for attack data. It can also send syslog (UDP, up to 5 servers). Radware recommends having Vision relay syslog for all devices. [R1]
- **Configuration auditing** on the device is **disabled by default**, to avoid load. When enabled on both Vision and the device, each change produces records in Vision's Audit database. [R1]
- The device "Configuration Log File" (exported through Vision: *Operations > Export Configuration Log File*) contains "a report of configuration errors". It is not a general event log. [R1]
- Per-user trap levels and "Enable Configuration Tracing" (MIB variable, new value, time, tool, user) are delivered as notifications, not as a pull log. [R1]

### Read paths
- **Vision / Cyber Controller REST** (shapes from Radware's own GitHub sample code, not reference docs, so **UNCONFIRMED** as a contract):
  - `POST /mgmt/system/user/login` with JSON `{"username","password"}` → session cookie. [R2][R3]
  - `GET /mgmt/system/config/itemlist/alldevices` (or `/defensepro`) to list managed devices. [R2][R3]
  - `GET /mgmt/device/byip/<dp-ip>/config/<MIB-table>` for device tables. [R2][R3]
  - `POST /mgmt/monitor/reporter/reports-ext/<REPORT>` for attack and traffic reports (e.g. `DP_ATTACK_REPORTS`). These are security/traffic data, out of scope. [R3]
  - **No public source found for an Alerts-table or Audit REST endpoint: UNCONFIRMED.** Ask Radware or read the on-box API docs.
- **DefensePro CLI (SSH)**: commands documented in [R1] are mostly `manage ...` configuration commands. **No CLI command to display the device's alert/event history was found (UNCONFIRMED).** CLI limits: about 120 simultaneous commands, 256 characters per command; three bad logins lock the source IP for 10 minutes. [R1]
- **DefensePro APSolute API (SOAP/XML over HTTPS)**: disabled by default; enabling it is `manage Web-services status` (NOT READ-ONLY). [R1]

### Pitfalls (NOT READ-ONLY)
- Vision **device lock** (`/mgmt/system/config/tree/device/byip/<ip>/lock|unlock`) is a mutation that blocks other admins. Never call it for reads. [R1][R3]
- `manage terminal traps-outputs set-on` (stream traps to SSH sessions) and `manage support save-file` (writes `support.tar` on the device) both change state. [R1]
- Vision `.../config/techdump` and `gettechdata` generate files on the device. [R3]
- Device user "Access Level" defaults to **Read-Write**. Read-only must be set explicitly; with RADIUS, Service-Type 7 means read-only and 6 means read-write. [R1]

### Incremental key
- Alert rows carry a time field in Vision's UI; the field names in the REST JSON are **UNCONFIRMED**. Plan on time + row id after a lab measurement.

Sources (Radware):
- [R1] Radware DefensePro DDoS Mitigation User Guide 8.13.01 (for Cisco Firepower, Radware/Cisco 2017): https://www.cisco.com/c/dam/en/us/td/docs/security/firepower/fxos/radware/DPforCiscoFP_8_13_01_UG.pdf ; 8.10.01: https://www.cisco.com/c/dam/en/us/td/docs/security/firepower/fxos/radware/RadwareDefenseProDDoSMitigation_ForCiscoFP_8_10_01_UG.pdf
- [R2] Radware official GitHub sample, DP_config_analyzer `vision.py`: https://github.com/Radware/DP_config_analyzer
- [R3] Radware official GitHub sample, DP-Attack-Analyzer `clsVision.py`: https://github.com/Radware/DP-Attack-Analyzer

---

## 4. Infoblox NIOS Grid

### CLI (SSH to each member)
```
show log [syslog | debug | audit | ifmapclient | ifmapserver | category_api_logs | ufclient_logs] [<log num 0-9>] [tail <n> | /regex/]
show logfiles            # list rolled files and sizes
```
- Syslog is the default log. `tail <n>` defaults to 10 lines. `/regex/` filters lines. `<log num>` reads rolled files 0–9 (syslog, audit, ifmap). The synopsis also shows `follow`, which the argument table does not describe: **UNCONFIRMED**. Assume it streams and never returns, and do not use it in automation. [I1]
- **NOT READ-ONLY:** `rotate log [syslog|debug|audit|ifmapserver]` (forces a rotation) and `set debug` (Infoblox advises leaving debug off). [I2][I3]
- Rotation: syslog rolls at its maximum size (default and maximum **300 MB**, configurable 10–300 MB; 20 MB on Riverbed virtual appliances) into `.0`…`.9`, gzip-compressed, keeping 10 generations. [I4]

### WAPI (Grid Master) and member logs
- `POST /wapi/v2.x/fileop?_function=get_log_files` with body `{"log_type":"SYSLOG", "member":"<member fqdn>", "node_type":"ACTIVE|BACKUP", "include_rotated":false}`. `log_type` ∈ `SYSLOG, AUDITLOG, MSMGMTLOG, DELTALOG, OUTBOUND, PTOPLOG, DISCOVERY_CSV_ERRLOG`. Pass only one of `member`, `msserver` or `endpoint`. The call returns `{token, url}`. Then `GET <url>` to fetch the archive, then `POST fileop?_function=downloadcomplete` with `{"token":...}`. [I5][I6]
- **So yes: the Grid Master returns any member's syslog (and either HA node) through WAPI.** The GUI does the same: *Administration > Logs > Syslog*, with a member dropdown and Active/Passive tabs for HA pairs. [I5][I7]
- GUI syslog entry fields: Timestamp (date, time, member time zone), Facility, Level (EMERGENCY…DEBUG), Server (name plus PID), Message. [I7]

### Privileges
- Limited-access admin groups need the Grid permission **"View and download syslog"** (RO) on the Grid member(s). They can use GUI, API or CLI but not the console. CLI access for non-superusers is a separate per-group setting with command groups. [I8][I9]
- Required WAPI permission for `get_log_files` specifically: **UNCONFIRMED** (the WAPI page lists no permission).

### Content and incremental key
- Syslog covers system daemons and services. The audit log covers admin actions (logins, object changes). Specific message texts for HA failover, link, license and disk events are **UNCONFIRMED** (no catalog found).
- **No sequence number.** The incremental key is the syslog timestamp (member-local TZ) + line hash. `get_log_files` always returns the **whole current file** (optionally with rotated ones) as an archive, so de-duplicate by timestamp. A day's syslog can approach 300 MB per file on busy members.

### Pitfalls
- `get_log_files` is an HTTP **POST that creates a temporary download artifact on the GM**. It does not change configuration, but it is not a GET. Always call `downloadcomplete`. [I6]
- The WAPI fileop file area defaults to a 500 MB limit; local exports older than a week are removed. [I6]

Sources (Infoblox):
- [I1] NIOS 9.0 CLI `show log`: https://docs.infoblox.com/space/nios90/1430618127 (8.3: https://infoblox-docs.atlassian.net/wiki/spaces/nios83/pages/25592786/show+log)
- [I2] NIOS 9.0 CLI commands (incl. `rotate log`): https://docs.infoblox.com/space/nios90/280856042
- [I3] NIOS 9.0 `show debug` / 8.5 `set debug`: https://docs.infoblox.com/space/nios90/280658612 ; https://docs.infoblox.com/space/nios85/35782686
- [I4] NIOS 8.5 Using a Syslog Server (rotation, size): https://docs.infoblox.com/space/nios85/35784616/Using+a+Syslog+Server ; NIOS Admin Guide: https://docs.infoblox.com/space/NAG8/22252249/Using%20a%20Syslog%20Server
- [I5] Infoblox community, get_log_files 3-step flow and valid log types: https://community.infoblox.com/t5/api-examples/searching-for-record-audit-history-quot-information-like/m-p/15252 ; https://community.infoblox.com/t5/nios-dns-dhcp-ipam/download-ptoplog-via-wapi/m-p/32937
- [I6] WAPI 2.13.7 `fileop` reference (a customer-hosted copy of the on-box wapidoc; the official copy is served at `https://<GM>/wapidoc/`): https://ipam.illinois.edu/wapidoc/objects/fileop.html
- [I7] NIOS 9.0 Viewing the Syslog: https://docs.infoblox.com/space/nios90/1380844786
- [I8] NIOS 8.5 Administrative Permission for the Grid: https://docs.infoblox.com/display/nios85/Administrative+Permission+for+the+Grid
- [I9] NIOS 8.6 About Admin Groups: https://docs.infoblox.com/space/nios86/1102839812/About+Admin+Groups

---

## 5. Symantec/Broadcom ProxySG (SGOS 7.x) and Management Center

### ProxySG: CLI
```
> show event-log configuration                     # standard mode: configuration only
# show event-log                                   # privileged: whole event log
# show event-log start "YYYY-mm-dd HH:MM:SS" end "YYYY-mm-dd HH:MM:SS"
#(config event-log) view [start [YYYY-mm-dd] [HH:MM:SS]] [end [YYYY-mm-dd] [HH:MM:SS]] [regex <re> | substring <s>]
```
- In the 7.3/7.4 references, standard-mode `show event-log [configuration]` shows **configuration only**. Contents come from privileged `# show event-log` (a Broadcom KB shows the start/end form) or `view` under `(config event-log)`. [P1][P2][P3]
- Filter semantics: start and end are inclusive, in the appliance time zone. If you omit the date on one bound you must omit it on both (it then means today). regex and substring are case-sensitive and matched against the record text, excluding date/time. [P2]
- Record format: `2019-10-22 09:00:02+00:00UTC "<message>" <n> <code e.g. 2D0006:96> <source file:line>`. The trailing fields are not documented as event IDs. [P2]
- **NOT READ-ONLY / avoid:** entering `configure` → `event-log` (the `view` subcommand itself is read-only, but the collector would be in config mode). All other `(config event-log)` subcommands change state: `level`, `log-size`, `when-full`, `syslog add|remove|clear|format|...`, `mail add|remove|clear`, `notifications`. [P4]
- Privilege: standard mode is the read-only CLI level. Privileged (`enable`) mode is read-write, and Broadcom advises not giving the enable password to read-only users. [P5] **Event-log contents over CLI therefore effectively need enable**. A pure read-only CLI account probably cannot read them (**UNCONFIRMED**; test on device).

### ProxySG: HTTPS (advanced URL)
- `GET https://<sg>:8082/eventlog/fetch=0xffffffff` with Basic auth. Broadcom's sample archive script polls this URL. The CLI equivalent is `show advanced-url /Eventlog/fetch=0xFFFFFFFF`. [P6][P1]
- Read-only admins are granted by policy (VPM Admin Access layer, "Allow Read-only Access"; default is deny unless console account or explicit policy). Policy is not evaluated for serial console or SNMP. [P5][P7] Whether read-only policy admits `/eventlog/fetch` is **UNCONFIRMED**, but it is the most promising read-only path.

### ProxySG: size and incremental reads
- Event log size: default 50 MB in the 7.3 GUI page (10 MB in 6.7 and in some 7.3 CLI samples). `when-full overwrite` drops the older half / oldest entries FIFO; `stop` stops logging. The log is **two files rotated on overflow**, and Broadcom advises fetching before the older file is overwritten. [P8][P6][P4]
- Incremental key: timestamp + record hash (no sequence number documented). Use `start "<last-seen>"` on CLI and de-duplicate at the boundary second.
- Content: event levels are severe, configuration, policy, trace (7.3.2.1+), informational, verbose; the log contains only the configured levels. [P4][P2] Specific event codes for HA/failover, link, license, disk and auth failures: **UNCONFIRMED** (no public code catalog found).

### Management Center (MC 3.x/4.x)
- REST base `https://<mc>:8082/api`, JSON only, Basic or `X-Auth-Token` (`POST /auth/users/{user}/api-token`, 1–720 days). The on-box reference is at `:8082/help/api`. [P9][P10]
- `GET /system/audit` returns MC's **own** audit log (MC user and system actions: EVENT and AUDIT records), paginated with `start` (0-based) and `limit` (default 5000). `GET /devices` and `GET /devices/{uuid}/health` return the device list and health. RBAC: give the account view-only permissions (e.g. VIEW_DEVICE), not global admin. All API activity is itself written to the audit log. [P10][P11]
- MC local logs (`log.log` 10 MB ×9, `device.log` daily ×30, `localhost_access.log`, etc.) are viewed and downloaded via *Administration > Logs*. The same page offers **Delete (NOT READ-ONLY)**. [P12]
- `PUT /devices/{uuid}/command` passes an arbitrary CLI string to the managed ProxySG. The sample reply shows a `(config)` prompt. It can carry config commands (**NOT READ-ONLY by design; whitelist `show` only**, or do not use it). [P13]
- MC has no documented endpoint that returns a **managed ProxySG's event log** (**UNCONFIRMED**). Read the ProxySG directly.

Sources (Broadcom):
- [P1] Broadcom KB 165912, View event log using the CLI: https://knowledge.broadcom.com/external/article/165912/view-event-log-using-the-command-line-in.html
- [P2] ProxySG 7.3 View Event Log Configuration and Contents: https://techdocs.broadcom.com/us/en/symantec-security-software/web-and-network-security/edge-swg/7-3/getting-started/page-help-administration/page-help-logging/page-help-event-logging/view-event-log.html
- [P3] ProxySG 7.3 standard-mode `show`: https://techdocs.broadcom.com/us/en/symantec-security-software/web-and-network-security/edge-swg/7-3/index2/_standard/show.html ; 7.4 CLI PDF: https://techdocs.broadcom.com/content/dam/broadcom/techdocs/us/en/dita/symantec-security-software/web-and-network-security/edge-swg/generated-pdfs/cli-74.pdf
- [P4] ProxySG 7.3 `(config event-log)`: https://techdocs.broadcom.com/us/en/symantec-security-software/web-and-network-security/edge-swg/7-3/index2/_configure/config_event_log.html
- [P5] ProxySG 7.3 Secure Access to the Appliance: https://techdocs.broadcom.com/us/en/symantec-security-software/web-and-network-security/edge-swg/7-3/getting-started/page-help-configuration/page-help-authentication/page-help-console-access.html
- [P6] ProxySG 7.3 Securely Retrieve Event Logs: https://techdocs.broadcom.com/us/en/symantec-security-software/web-and-network-security/edge-swg/7-3/getting-started/page-help-administration/page-help-logging/page-help-event-logging/retrieve-event-log.html
- [P7] Broadcom KB 166063 read-only user; KB 167092 admin access policy: https://knowledge.broadcom.com/external/article/166063/create-a-readonly-user-account-for-the-p.html ; https://knowledge.broadcom.com/external/article/167092/use-policy-to-control-edge-swg-proxysg-a.html
- [P8] ProxySG 7.3 Configure Event Logging (size); 6.7 Setting Event Log Size: https://techdocs.broadcom.com/us/en/symantec-security-software/web-and-network-security/edge-swg/7-3/getting-started/page-help-administration/page-help-logging/page-help-event-logging.html ; https://techdocs.broadcom.com/us/en/symantec-security-software/web-and-network-security/edge-swg/6-7/maintain_event_log_1.html
- [P9] MC 4.1 REST API overview: https://techdocs.broadcom.com/us/en/symantec-security-software/web-and-network-security/management-center/4-1/api.html
- [P10] MC 4.1 REST API best practices: https://techdocs.broadcom.com/us/en/symantec-security-software/web-and-network-security/management-center/4-1/api/mc-api-best.html
- [P11] MC 3.3 Audit Log viewer; MC 3.1.1.1 API guide (`/system/audit`): https://techdocs.broadcom.com/us/en/symantec-security-software/web-and-network-security/management-center/3-3/global_settings/audit_log_viewer.html ; https://techdocs.broadcom.com/content/dam/broadcom/techdocs/symantec-security-software/web-and-network-security/management-center/2-4/generated-pdfs/MC-3.1.1.1-API-Guide.pdf
- [P12] MC 3.3 Preview, Download, and Delete Logs: https://techdocs.broadcom.com/us/en/symantec-security-software/web-and-network-security/management-center/3-3/global_settings/log_preview.html
- [P13] MC 3.3 `PUT /devices/{uuid}/command`: https://techdocs.broadcom.com/us/en/symantec-security-software/web-and-network-security/management-center/3-3/api/Paths/executecommand.html

---

## 6. Comparison

| Vendor / product | Best read path | Incremental key | Per-read limit | Read-only privilege | Main NOT-read-only traps | Confidence |
|---|---|---|---|---|---|---|
| FortiGate 7.x | REST `GET /api/v2/log/{memory\|disk}/event/{subtype}` | `eventtime` + `logid` + hash (no sequence) | default 400 rows; poll `session_id` until ready; CLI ≤1000 lines/view | accprofile `loggrp read` | `execute log delete/delete-all/roll/shift-time/restore/upload` | CLI: official. REST path/params: Fortinet Ansible + community (FNDN login) |
| FortiManager 7.x | none documented for local event log; GUI/CSV export; forward via locallog (config change) | Date/Time | n/a | JSON API Access = Read | `execute log ... clear`, Historical Log delete; 32 JSON-RPC sessions/user | Low: read path UNCONFIRMED |
| Cisco ASA 9.x | SSH `show logging` (or HTTP `/admin/exec/show+logging`) | timestamp (needs `logging timestamp`) + hash; no sequence | buffer default 4 KB, circular; resize purges | level 15 by default; `privilege show level N command logging` with LOCAL authz | `clear logging buffer`, `pager lines`, any `logging ...`; `/admin/exec` accepts config commands; REST API ended at 9.16 | High (official) |
| Radware DefensePro / Vision / CC | Vision/CC REST (login, device list); Alerts-table endpoint UNCONFIRMED | UNCONFIRMED | UNCONFIRMED | device user Access Level read-only (default RW); RADIUS svc-type 7 | device lock/unlock, techdump, `manage ...` commands, Web-services enable | Low: docs behind portal |
| Infoblox NIOS | WAPI `fileop get_log_files` on GM for any member (SYSLOG, AUDITLOG); or SSH `show log syslog tail N` per member | timestamp (member TZ) + hash | whole file per call (≤300 MB, 10 rotations) | "View and download syslog" (RO); CLI per-group | `rotate log`, `set debug`; `get_log_files` is a POST that creates a file (call `downloadcomplete`) | High CLI/GUI; WAPI from on-box doc copy + community |
| ProxySG SGOS 7.x | HTTPS `:8082/eventlog/fetch=0xffffffff`; or `# show event-log start "..." end "..."` | timestamp + hash | log 50 MB default (two files, rotate on overflow) | policy "Allow Read-only Access"; CLI contents need enable (RW) | `(config event-log)` subcommands; enable password exposure | Medium-high (official), read-only HTTPS path UNCONFIRMED |
| Symantec Management Center | REST `GET /system/audit?start=&limit=` (MC's own audit only) | offset `start` + record time | limit default 5000 | RBAC view-only (e.g. VIEW_DEVICE) | `PUT /devices/{uuid}/command`, log Delete, settings apply | High (official) |

## 7. Recommended read shape per vendor

1. **FortiGate**: REST, once per subtype (`system`, `ha`, `vpn`, `user`, `router`), from `memory` (and `disk` where present). Loop `rows=<N>`, `start=<offset>`, follow `session_id` until ready/complete, and keep records with `eventtime > last_eventtime`, de-duplicated on (`eventtime`, `logid`, hash). The CLI fallback is `execute log filter reset` → `device` → `category event` → `view-lines 1000` → `start-line` paging → `display`. **Measure first:** the max `rows`, the sort order, and whether `filter` accepts a time bound.
2. **FortiManager**: no read-only pull is confirmed today. Mark it "not collectable" until a lab test validates a JSON-RPC local-log call. The documented alternative (locallog → syslog) is a configuration change for the PO.
3. **Cisco ASA**: SSH session with `terminal pager 0` (session-only), then `show logging`. Parse lines after the header, key on timestamp + hash, and drop lines at or before the last seen timestamp. Precheck `show running-config logging` for `logging buffered` / `logging timestamp`; if they are absent, report "no usable buffer" rather than changing config. Do not use the REST API (EOL after 9.16). Use `/admin/exec` only with a hard `show`-only whitelist.
4. **Radware**: through Cyber Controller/Vision only (DefensePro has no documented event-history CLI). Login → `alldevices` → alerts endpoint *to be obtained from Radware or the on-box API docs*. Never call lock/unlock. Until then, mark it "not collectable".
5. **Infoblox**: one WAPI flow per member per day on the Grid Master: `get_log_files {log_type: SYSLOG, member, node_type: ACTIVE (and BACKUP for HA), include_rotated: true only if the last read predates rotation .0}` → GET url → `downloadcomplete`. Filter by timestamp > last seen. Repeat with `AUDITLOG` if wanted. The SSH fallback is `show log syslog tail <N>` (never `follow`).
6. **ProxySG**: HTTPS `GET :8082/eventlog/fetch=0xffffffff` with a read-only policy account. Filter client-side by timestamp > last seen, and fetch often enough that the older of the two files is not overwritten between reads. The CLI fallback is `enable` → `show event-log start "<last-seen>" end "<now>"` (needs the RW enable password, so the PO decides).
7. **Management Center**: `GET /api/system/audit?start=<offset>&limit=<n>` with a view-only token, for MC's own audit trail. Read managed ProxySGs directly (item 6), never through `/devices/{uuid}/command`.
