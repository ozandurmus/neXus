# Vendor backup contracts — eight vendors

## Status

**FROZEN — 2026-09-22, on the Product Owner's directive ("write the
contracts; the order does not matter, start from the top").** Measurement
source: `VENDOR_BACKUP_MEASUREMENTS_2026_09_22.md` (Backbox trails). Where a
vendor's sequence was not fully measured, the section says **MEASURE FIRST**
and names what the first live run must record before that step ships; that
step is `UNKNOWN` until then, never guessed.

Blue Coat: the Product Owner named **ProxySG (appliance or virtual)**; the
trail on file is SSL Visibility and is not used. ProxySG's contract is written
from Symantec's own CLI documentation and marked MEASURE FIRST.

## 0. Common contract for every new vendor

### 0.1 Onboarding (once per vendor)
- `devices.vendor_hint` value and `endpoints.transport_kind`; the V16/V17
  `CHECK (vendor IN ('check_point','palo_alto'))` constraints are widened by
  one migration listing all eight.
- Add device: vendor option, endpoint (address, port), credential reference;
  a **second secret** where the vendor needs one (ASA enable password,
  Provider-1 expert password) as a second credential reference on the
  endpoint, never a field on the device.
- Confirm capability: one gated identity read per vendor (named per section);
  `ENROLLED` only after it answers.
- Screen: vendor label and avatar; the backup fleet table needs nothing else.

### 0.2 Transports
- SSH exec / interactive: existing worker transport (host key trusted on
  first use per the SSH trust decision; enable/expert handled by the
  interactive shell as a gated step whose secret comes from the credential
  store).
- **Generic HTTPS client (new, built first)**: cookie jar per run, HTTP basic
  auth, form POST, JSON POST, streamed download into the artefact store with a
  size cap, no redirects across hosts, TLS per the PAN decision record
  (verification disabled for this internal environment; a per-vendor override
  can re-enable it). Gate rows for HTTPS steps are keyed by `METHOD path`
  (query and form fields listed in the row), transport kind `HTTPS`.

### 0.3 Backup run shape (all vendors)
1. connect (or login) — 2. identity read (hostname/model/version; refuses on
mismatch with the enrolled identity) — 3. produce (device-side export or
direct read) — 4. fetch into the envelope-encrypted store, streamed —
5. verify (device digest where the vendor offers one, otherwise size and
format check) — 6. clean up what the run created on the device, by name —
7. manifest row (vendor, software version, plaintext/ciphertext digests),
content listing (V41), retention (V44), deviation `first/unchanged/changed`
by plaintext digest. A bundle (several members) is one gzip tar as the PAN
bundle is. Nothing the device answered is persisted raw outside the artefact.

### 0.4 Failure semantics
`credential_unresolvable`, `connect_failed`, `identity_mismatch`,
`submit_refused: <device's words, masked>`, `artefact_store_failed`,
`digest_mismatch`, `cleanup_failed` (backup kept, device marked
ineligible until cleaned), `outcome_unknown` (never folded into failed).

### 0.5 Tests per vendor
Scripted-transport end-to-end (happy path in order, refusal, truncated
export, cleanup failure), parser tests on masked measured output, gate
alignment test, and a first live run recorded in the backlog note before
`real_env_validated`.

## 1. Radware DefensePro — `vendor_radware_defensepro_backup`
- Transport: HTTPS, basic auth, port 443.
- Confirm: `GET /` (banner) — MEASURE FIRST which read returns model/version
  (the trail only records the backup POST); until then confirm is the backup
  POST's HTTP 200 + a non-empty configuration text with the expected leading
  line, and the identity fields stay UNKNOWN.
- Backup: `POST /dynamic/File/Configuration/ReceivefromDevice` with the
  form body Backbox sent (`Download…`, recorded in the trail; copied
  verbatim into the gate row). Artefact: `DefensePro_backup_configuration.txt`
  (single member, class `backup`, no gzip). Verify: HTTP 200, `text/*`,
  size > 1 KB, first line matches the measured shape. Cleanup: none (nothing
  created on the device).
- Gate rows: `radware_backup_receive_configuration` (read, 120 s).

## 2. Pulse Secure / Ivanti Secure Access — `vendor_pulse_secure_backup`
- Transport: HTTPS form login. Sequence (measured): POST
  `/dana-na/auth/url_admin/login.cgi` (`tz_offset`, `username`, `password`);
  if the response carries `DSIDFormDataStr` (an existing admin session), POST
  the "continue the session" form (`btnContinue`, `FormDataStr`); GET
  `/dana-admin/cached/config/config.cgi?type=system` and read `xsauth`.
- Confirm: GET `/dana-admin/sysinfo/sysinfo.cgi` (model, version, hostname).
- Backup: four exports, POST with `xsauth`: `type=system`, `type=user`,
  `type=ivs` (`op=Export`; the export password is **set from a credential
  store reference**, never empty — the .cfg files are otherwise unencrypted
  at rest on the operator's machine after download), and `type=exportxml`
  (`action=Export`, checkbox flags as measured). Bundle members:
  `system.cfg`, `users.cfg`, `ivs.cfg`, `export.xml`. Logout: GET
  `/dana-na/auth/logout.cgi?xsauth=…`. Verify: each export non-empty and
  not an HTML login page (the appliance answers the login form on an
  expired session — that is `submit_refused`, never a stored backup).
- Gate rows: `pulse_login`, `pulse_continue_session`, `pulse_xsauth`,
  `pulse_sysinfo`, `pulse_export_system|user|ivs|xml`, `pulse_logout`.

## 3. Palo Alto Panorama — `vendor_panorama_backup`
- Transport: the existing XML API transport (keygen) + SSH (V43 reader).
- Confirm: `<show><system><info/></system></show>` (existing PAN read;
  model must be Panorama / M-series / virtual).
- Backup: **MEASURE FIRST** `type=export&category=device-state` on the
  Panorama; if it answers the documented device-state bundle, that is the
  member `device-state.tgz` and the web-form login Backbox used
  (`/php/login.php` + `config.export.php?bundle=true`) is not adopted. If it
  does not, the HTTPS client runs the measured form sequence and the member
  is `bundle.tgz`. Second member: `running-config.set` via `PanSetConfigReader`
  (already gated for firewalls; the Panorama gate rows are the same four
  literals with `platform_role_scope: panorama`).
- Gate rows: `pan_backup_export_device_state` reused with a Panorama scope
  row; four `pan_backup_ssh_*` Panorama scope rows.

## 4. Infoblox Grid Manager — `vendor_infoblox_grid_backup`
- Transport: HTTPS basic auth, WAPI.
- Confirm: `GET /wapidoc/` → WAPI version (measured); `GET /wapi/v<ver>/grid`
  (`_return_fields=name`) for the grid name → identity.
- Backup: `POST /wapi/v<ver>/fileop?_function=getgriddata` (JSON
  `{"type":"BACKUP"}`) → `url` + `token`; `GET <url>` streamed → member
  `database.bak`; `POST /wapi/v<ver>/fileop?_function=downloadcomplete`
  with the token (always, also after a failed download — the appliance holds
  the file otherwise). Verify: HTTP 200, size > 1 MB, gzip magic (the Grid
  backup is a tar.gz). Cleanup: `downloadcomplete` is the cleanup.
- Inventory (PO 2026-09-25, night: "backup is backup, inventory is inventory"):
  the Grid Manager has its own Collect, `https_inventory_collect` (Collect now and
  the 23:00 schedule), which reads `GET /wapi/v<ver>/member` once (gate
  `infoblox_member_list`) and records one inventory run: a context per member
  (its host name) with interfaces LAN1 (VIP), MGMT (`node_info[0].mgmt_network_setting`),
  LAN2 (`lan2_port_setting` when enabled) and every `additional_ip_list` entry,
  static routes from `static_routes`, the member facts below, and the member
  names as the device's "virtual systems" so the Devices tree lists them as
  "Grid members" (masked for aiview like any virtual system). The confirm
  establishes identity only; the backup only backs up. Members are not devices:
  the grid backup already carries every member's configuration, and a member
  exposes no WAPI of its own.
- Member facts (PO 2026-09-25, V72, measured on the production grid, WAPI 2.13.7): the
  same read carries `master_candidate`, `enable_ha`, `vip_setting`, `node_info` and
  `service_status`; per member neXus keeps platform, hardware type (`node_info[0].hwtype`,
  `hwmodel` is empty on VNIOS), hypervisor, Grid Master (the member whose VIP is the
  address dialled -- nothing else identifies the master), master candidate, HA
  (`enable_ha`, `ha_status`), node status and replication, disk / memory / CPU / DB
  capacity percentages parsed from the node service descriptions, and every
  member-level service as `service=status` (`WORKING`, `INACTIVE`, `WARNING`,
  `UNKNOWN`). Free-text descriptions are not stored (they carry addresses). Shown
  under the Grid Manager as the "Grid members" tab; the member name is masked for
  aiview as a virtual system, the VIP as an address.
- Gate rows: `infoblox_wapidoc_version`, `infoblox_grid_identity`,
  `infoblox_member_list`, `infoblox_fileop_getgriddata`, `infoblox_download`,
  `infoblox_fileop_downloadcomplete`.

## 5. Fortinet FortiGate — `vendor_fortinet_fortigate_backup`
- Transport: SSH interactive (prompt changes per context; the shell
  re-learns the prompt from each answer).
- Confirm: `get system status` (version, hostname, serial).
- Backup: `config global` → `config system console` → `set output standard`
  → `end` → `end`; `show` at global to enumerate VDOMs (parsed in the worker:
  `config vdom` / `edit <name>` lines — MEASURE FIRST the exact block shape
  on this estate's FortiOS); then per VDOM `config vdom` → `edit <vdom>` →
  `show full-configuration` → `end`; plus `config global` →
  `show full-configuration` → `end` for the global scope. Bundle members:
  `global.conf`, `vdom-<name>.conf` (secret-bearing: hashed passwords,
  PSKs). Verify: each ends with the FortiOS `end` line and has ≥ 5 `config`
  blocks. Cleanup: none. `set output standard` is a session setting, not a
  configuration write, and is gated as read.
- Gate rows: `fortigate_get_system_status`, `fortigate_console_output_standard`
  (the five-command sequence as one row with a documented sequence),
  `fortigate_show_vdoms`, `fortigate_vdom_enter`, `fortigate_show_full_configuration`,
  `fortigate_context_end`.

## 6. Cisco ASA — `vendor_cisco_asa_backup`
- Transport: SSH interactive; `enable` with the enable secret (second
  credential reference); `terminal pager 0`.
- Confirm: `show version` (hardware, serial, software version).
- Backup: `changeto system` (multi-context only); `more system:running-config`;
  `show startup-config`; contexts from `show running-config | include ^context`
  — **MEASURE FIRST** the per-context loop on a multi-context ASA (the trail
  hides it behind Backbox's local `gotoLine`); until measured, multi-context
  boxes store the system context only and the manifest says
  `contexts: UNKNOWN`. Native archive: `backup /noconfirm location
  disk0:nexus-<jobid>.tar.gz` then fetch — ASA offers SCP, not SFTP:
  **MEASURE FIRST** whether `scp -f` over an exec channel works on this
  estate; if not, the archive step is skipped and the bundle holds the text
  reads only (stated in the manifest). Cleanup: `delete /noconfirm
  disk0:nexus-<jobid>.tar.gz`, by the exact name this run created (ledger
  as V45). Bundle members: `running-config.txt`, `startup-config.txt`,
  `show-version.txt`, optional `asa-backup.tar.gz` (secret-bearing:
  certificates and keys).
- Gate rows: `asa_enable`, `asa_terminal_pager_0`, `asa_show_version`,
  `asa_changeto_system`, `asa_more_running_config`, `asa_show_startup_config`,
  `asa_show_contexts`, `asa_backup_archive` (class 1 recovery write: creates a
  file on the device), `asa_delete_archive`.

## 7. Check Point Provider-1 / MDS — `vendor_cp_provider1_mds_backup`
- Transport: SSH; `expert` with the expert password (second credential
  reference) when the login shell is Clish; `export TMOUT=36000` as the
  Gaia backup does.
- Confirm: `fwm mds ver` + `mdsstat` (MDS identity and state).
- Backup: working directory `/var/log/nexus-<jobid>` (created by the run,
  removed by the run); `cplic print -x`, `netstat -rn`, `uname -a` into it;
  `clish -c "save configuration gaia_config.txt"` (measured: `lock database
  override` first, then the save, answering `Y`); `tar zcf
  /var/log/nexus-<jobid>/etc.tar.gz /etc/hosts /etc/resolv.conf …` (the file
  list Backbox tars, copied into the gate row); `$CPMDIR/scripts/mds_backup
  -b -l -d /var/log/nexus-<jobid>` (batch, without logs); `ls` for the
  `*.mdsbk.tar`; `tar -pczf /var/log/nexus-<jobid>/provider1.tar.gz *`;
  `sha256sum` on the device; SFTP fetch streamed; digest compare; `rm -rf
  /var/log/nexus-<jobid>` by name. Artefact: `provider1.tar.gz`
  (secret-bearing). `mds_backup` is class 1 (recovery write: it creates
  files and takes the MDS database lock for its duration — MEASURE FIRST its
  run time on this estate; the run deadline is set from it).
- Gate rows: `mds_expert`, `mds_fwm_mds_ver`, `mds_mdsstat`, `mds_lock_database_override`,
  `mds_save_configuration`, `mds_tar_etc`, `mds_backup`, `mds_ls_backup`,
  `mds_tar_bundle`, `mds_sha256sum`, `mds_rm_workdir`.

## 8. Blue Coat ProxySG (appliance or virtual) — `vendor_bluecoat_proxysg_backup`
- Transport: SSH interactive (ProxySG CLI), `enable` with the enable
  password (second credential reference); optionally HTTPS on the management
  console port (8082) for the archive.
- Confirm: `show version` (SGOS version, serial, model).
- Backup (from Symantec's ProxySG CLI reference; **MEASURE FIRST** on one
  appliance before the gate rows are signed off): `enable` → `show
  configuration expanded` (the full configuration including defaults) and
  `show configuration` (the operator-set configuration); the installable
  configuration archive `configure terminal` → `archive-configuration` is a
  write to a remote host and is **not** used. Bundle members:
  `configuration.txt`, `configuration-expanded.txt`, `show-version.txt`
  (secret-bearing: hashed passwords, keyrings are withheld by SGOS itself).
  Verify: the `show configuration` output ends with the SGOS prompt and
  starts with `!- ` / `; ` header lines as documented. Cleanup: none.
- Gate rows: `proxysg_enable`, `proxysg_show_version`, `proxysg_show_configuration`,
  `proxysg_show_configuration_expanded` — all read.

## 8a. ProxySG through the Symantec Management Center — measured 2026-09-25 (read-only)

Read with the Product Owner's MC web session, GET only, nothing changed on the MC or a device:
- The MC REST API (`https://<mc>:8082/api`, basic auth or `X-Auth-Token`) has **no backup resource**. Its WADL
  (`/api/application.wadl`, 122 operations) and on-box guide (`/help/api/`) list devices, groups, files, jobs,
  job results and job artifacts. Device backups exist only as MC jobs (the guide's example names a
  `DeviceBackups` job); none is defined on this MC (15 job definitions: 14 `INSTALL_POLICY`, 1 `COLLECT_SYS_INFO`;
  0 job artifacts).
- Job artifacts (`GET /jobs/artifact`, `GET /jobs/artifact/{uuid}/content`) carry an `ArtifactSource`; one value
  is `SAVE_CONFIG` ("Device Configuration Capture") -- an MC job that captures a device's configuration as a
  downloadable artifact. Using it means **creating a job on the MC** (a change on the MC).
- `PUT /devices/{uuid}/command` executes one CLI command on a managed device through the MC and returns
  `{"reply", "messages", "status"}`. The guide's example shows the MC's session on the ProxySG is at the
  **`#(config)` prompt** -- so only exact read literals may ever be sent (`show version`, `show configuration`);
  anything else could be a configuration change. This path needs no ProxySG credential of neXus's own and no
  job on the MC.
- Proposed gate rows (not yet signed off): `bluecoat_mc_command_show_version`,
  `bluecoat_mc_command_show_configuration` -- `PUT /api/devices/{uuid}/command` with exactly that body, action class
  read, one per device per run, first run on the test proxy only.

## 9. Order of work (Product Owner: "start from the top")

Radware → Pulse Secure → Panorama → Infoblox → FortiGate → Cisco ASA →
Provider-1 → ProxySG, preceded by the generic HTTPS client and the one
onboarding migration. Each vendor ships with its gate rows, capability yaml,
executor, scripted end-to-end test and a backlog note of its first live run.

## Amendment 2026-09-23 — §7 Check Point Provider-1 / MDS (implemented as V61, `cp_mds_export`)

Product Owner, 2026-09-23: an MDS takes **two** backup types — the Gaia backup a gateway takes (`add backup local`,
the existing `cp_gateway_backup`, now admitted for a Check Point management server) and the **MDS export** below.

- **Scope:** one `mds_backup -b -l` backs up the whole Multi-Domain Server and every domain (R81.20 CLI reference:
  "backs up binaries and data from a Multi-Domain Server"; "do not create or delete Domains … until the backup
  operation completes"). A single domain has its own API (`backup-domain`), not implemented.
- **Changes from §7 as written:**
  - Gaia configuration is read with `clish -c 'show configuration'` (already SIGNED_OFF for configuration
    collection) instead of `lock database override` + `save configuration` + answering `Y`.
  - `mds_backup` runs in the background from `/var/log` with its exit code written to `mds_backup.rc`, polled by the
    run (14H: no SSH command held open for the backup's duration); no exit code before the run deadline
    (default 4 h, `UI2_MDS_EXPORT_RUN_DEADLINE_SECONDS`) is OUTCOME_UNKNOWN and the work directory is left in place.
  - The `/etc` tar is **not** included yet: the file list Backbox tars is not recorded in the repository (MEASURE
    FIRST: copy it from trail 34410129).
  - Expert password (second secret) not needed on this estate: the MDS session lands in Expert.
- **Lock:** the vendor asks that no SmartConsole changes are made until mds_backup completes. The nightly export runs
  at 02:00 Europe/Istanbul (`MdsExportScheduler`), and only for a server whose first MDS export an operator started
  and saw complete (run time measured under watch).
- **Measurement still open:** mds_backup run time and bundle size on this estate (logged as `[MDS_EXPORT]`).

## Amendment 2026-09-24 — §0.2, §1, §4 implemented (V64)

- **HTTPS transport:** `HttpsDeviceClient` (worker) — basic auth pre-emptive, form and JSON POST, streamed download
  with a size bound, redirects only on the same host and port, TLS per the PAN decision record. No cookie state is
  needed by Infoblox or Radware and none is kept.
- **Onboarding:** vendors `infoblox` and `radware`, role `appliance`, transport `https`, confirm capability
  `device_confirm_https` (the SSH/XML-API confirm's lifecycle without the Check Point / Palo Alto identity-mismatch and
  HA peer-follow steps). Infoblox confirm reads the WAPI version (`/wapidoc/`) and the grid name
  (`/wapi/v<ver>/grid`); Radware confirm proves reachability and the credential only (identity MEASURE FIRST).
- **Backup:** one capability `https_vendor_backup` routed by vendor through the existing backup job (envelope
  encryption, manifest, retention, deviation, download unchanged). Infoblox as measured (version read, not assumed;
  `downloadcomplete` always sent, with the version — Backbox sends it without one and fails); a download URL on
  another host or port is refused. Radware sends the measured form with `IncludePKeys=on`; the passphrase is the
  password of the credential bound as the device's `export_passphrase` second secret (`device_secret_reference`,
  audited); without it the run is refused rather than taken without keys.
- **Verify:** non-empty (Infoblox) / ≥ 1 KB (Radware); the gzip magic is logged, not enforced, until a live run
  records the real format (the grid backup on file was 996 KB, so the earlier "> 1 MB" check is withdrawn).
- **Open:** first live run of each (real_env_validated); Radware identity read; the other six vendors follow §5–§8.
