# Support bundle collection (vendor TAC files) — design

## Status

**DRAFT — DO NOT FREEZE.** Written 2026-11-14 from the Product Owner's request of the same day. It guides
investigation only. It approves no device command, schema, role or upload path. Every command in §6 needs the PO's
exact-literal approval through the network-device command gate before any code issues it. Every `UNKNOWN` below
stays `UNKNOWN` until a vendor page or a PO-attended lab or pilot measurement settles it.

Research notes with the full per-artefact tables (outside the repository, session notes of 2026-11-14):
`support-bundle-research-{checkpoint,paloalto,fortigate,cisco-asa}-1114.md`. Vendor pages were read through a
summarising fetch tool. Every literal option below must be checked against the raw vendor page, or a lab device,
before the contract is frozen.

## 0. Summary for the Product Owner

- **What you asked for.** An administrator picks a device, ticks the files a vendor's support team (TAC) usually
  asks for, picks "now" or a time tonight, and later downloads the result from neXus. Optionally, neXus also uploads
  the result to the vendor's support case.
- **Collection is feasible for all four vendors.** It reuses what neXus already does for backups. neXus connects
  through its existing controlled connection, runs the vendor's own collection tool, copies the result into the
  encrypted store, checks the fingerprint (SHA-256) on both sides, and deletes the file it made on the device.
- **"cpdata_collect" is Check Point's `cpdata_collector` (CPSDC, sk164414).** Check Point documents it for
  Maestro/Scalable Platforms. Whether it runs on ordinary Gaia gateways, management servers or MDS is `UNKNOWN`. It
  is not in the first catalogue (question Q3).
- **Lowest-impact first.** The first artefact per vendor needs no new device-side tool run, or only an API call:
  - Check Point: the device's own latest HCP report. It already exists on the box and neXus already copies it daily
    for system logs.
  - Palo Alto: the tech support file through the XML API.
  - Cisco ASA: `show crashinfo`, then `show tech-support` read from the session.
  - FortiGate: `diagnose debug crashlog read`, then `execute tac report` read from the session.

  `cpinfo` and a fresh HCP run come later. They put measurable load on the device, and Check Point itself says not to
  run cpinfo while any CPU core is above 70%.
- **Direct upload to the vendor case is possible but should come last, behind your decision and the bank's.** A
  bundle contains configuration, names and addresses, and often password hashes and other secrets. It cannot be
  masked, because the vendor's tools need it intact. Uploading means sending bank data to a vendor outside the bank.
  The proposal:
  - Downloads only in phases 1–4.
  - Upload in phase 5 only after the bank's information-security and compliance offices agree.
  - Every upload is a per-case request approved by a second, different administrator (four-eyes).
  - Uploads go from neXus, never from the firewall, so firewalls never get internet access.
  - Every upload is audited.

  How scriptable upload is differs by vendor:
  - **Cisco (CXD): fully scriptable.** HTTPS PUT with the case number and a per-case token.
  - **Fortinet: semi-scriptable.** SFTP works only after the TAC engineer enables it for that ticket.
  - **Palo Alto: device-only.** The documented scripted path runs from the device itself (`scp` to `tacupload`).
  - **Check Point: device-only.** cpinfo uploads from the device itself, and no Linux uploader for a central host
    exists.
- **Commands.** 39 gate rows need your exact-literal approval, listed in §6:
  - 28 new device commands or API calls;
  - 3 more ASA rows, asked only if a fallback proves necessary;
  - 8 commands neXus already runs, re-approved for this new purpose.

  They are grouped so that each delivery step asks only for its own batch.
- **Questions for you: 12, listed in §10.** The most important are:
  - Q1: may an administrator pick any time, or only a job window?
  - Q2: how long are bundles kept?
  - Q8: is upload wanted at all, given the bank data-transfer question?

## 1. Scope and non-goals

In scope:

- A new job type, a closed per-vendor artefact catalogue, the scheduled or immediate run, device-load guards,
  encrypted storage with retention and quota, RBAC, audit, the download UI, CLI parity, and a later optional
  per-case upload step.
- Vendors: Check Point (Gaia gateway, ClusterXL, VSX, Security Management, MDS), Palo Alto (PAN-OS firewall,
  Panorama), FortiGate, Cisco ASA.

Not in scope:

- Parsing bundle contents. Bundles are opaque, stored as received and never shown in the UI.
- Debug sessions that change device state, for example `fw ctl debug`, `debug dataplane`, packet captures, or core
  dumps forced by `crashinfo force`. These reload or reconfigure the device and are class 2 or higher.
- FXOS chassis bundles (Firepower 4100/9300 `show tech-support chassis`) until neXus manages FXOS endpoints (Q11).
- Any configuration change made to make collection easier. Examples: FortiGate `config system console / set output
  standard`, enabling ASA `ssh scopy`, or Check Point consent flags. neXus never makes such changes. The device teams
  own them.

## 2. What neXus already has (reuse map)

| Need | Existing piece | Reuse |
|---|---|---|
| Run a long vendor tool without holding SSH open; exit code in a file; never re-run after a lost lease | `MdsExportExecutor` / `MdsExportPlan` (`ui2/worker/.../backup/cp/`), V61 + V206 gate rows | Same pattern for `cpinfo` and `hcp -r`: `setsid nohup bash --noprofile --norc -c "...; echo $? > rc"`, poll, point-of-no-return marker, `OUTCOME_UNKNOWN` leaves the work directory |
| Check Point shell normalization (no login shell, `BASH_ENV`/`ENV` cleared) | `MdsExportPlan.CP_ENV` (CP_REMOTE_SHELL_NORMALIZATION_CONTRACT_2026_11_11.md §6.1) | Mandatory prefix for every CP command here. The 2026-11-11 MDS fork storm came from a login shell under `mds_backup` |
| Free-space precheck | `df -P /var/log` + `BackupCapabilityExecutor.parseDfAvailableBytes` | Same parser |
| Bundle → digest → SFTP stream into the vault → compare → remove by exact name (BK-3) | `MdsExportExecutor.run`, `ArtefactStore.open(...).sink()`, `DeviceTransport.fetchStreaming` | Same, unchanged |
| PAN XML API with streamed export into the vault | `PaloAltoBackupExecutor` (`type=export&category=device-state` streaming), `PanXmlApiTransport`, keygen per run | Same transport. Adds the async job (start / status / get) |
| FortiGate interactive shell, `config global`, `--More--` answered in session (no console config change) | `FortiGatePlan`, `FortiGateExecutor` (V80 gate rows) | Same session handling. Output streamed to the vault, not to memory or transcript |
| ASA privileged session, file on `disk0:`, SCP pull, delete by name | `CiscoAsaPlan.backupArchive / scpPath / deleteArchive`, the ASA team's SCP enablement | Session capture first. The disk0 + SCP form is a fallback only |
| Device's own HCP report already copied daily | System-log phase B `cp_hcp_report` (newest `*.tar.gz` in `/var/log/hcp/last/`, 06:00 window, 7 days, ≤ 4.73 MiB measured on 52 gateways) | Artefact `cp_hcp_latest` can copy the already-stored file. **No device contact** |
| Browser download of a large artefact: role, reason ≥ 8 chars, single-use 60 s ticket, audit row before the first byte | `BackupController.downloadTicket / download`, `BackupDownloadService`, PO record 2026-09-22 | Same two-step route under its own action and role |
| Admin manual ops bypass windows; business-hours confirmation | `ManualAdmission`, `JobWindowPolicy.requireDeviceJobOpen`, `jobs.window_bypass_role`, PO record 2026-10-20 | "Run now" uses it as is. "Run at a time" needs Q1 |
| Deferred claim | `jobs.admission_not_before` (V128, used by endpoint admission deferrals) | The scheduled start time and "device busy, retry in 10 min" |
| Expiring artefacts with a janitor | `ArtefactClass.REPORT` + `expires_at` + retention janitor (V190) | New class `support_bundle` with `expires_at` |
| A transient device file is not a recovery write | PO record 14K BW-2 ("a backup creation writes a transient artefact and removes it") | Same classification for the work directory, bundle and TSF job file |

## 3. Vendor research

Confidence tags: **OFFICIAL** means a vendor documentation page. **COMMUNITY** means a forum post or a third party.
**UNKNOWN** means not established. Runtime, size and CPU impact are `UNKNOWN` for almost every artefact. The
vendors publish one sample at most. These are exactly what the pilot step (§9 S7) measures.

### 3.1 Check Point

| Artefact | Command (Expert unless noted) | What the vendor says | Lands at | Secrets | Confidence / source |
|---|---|---|---|---|---|
| **cpinfo** | `cpinfo [-z] [-o <file>] [-l] [-k] [-x] [-D] [-i] [-S] [-d] [-V <days>\|-N] [-T <sec>] [-c <Domain>] [-s <SR> -u <email>\|-K <CK>]` | Run `top` first. Do **not** run if any CPU core is above 70%. Use a maintenance window. Sub-command timeout 300 s by default. `-l` adds `fw.log` and `-k` adds kernel tables (more CPU and memory). `-x` skips the management DB export (on SMS/MDS a separate `*_migrate_export_out.tgz`). `-D` disables upload. `-d` skips the self-update check. Upload limit 10 GB. Self-updates from `/opt/CPinfo-10/` when the consent flag allows | current directory, `<Host>_<DD_MM_YYYY_HH_mm>.info[.gz]`, or `-o` | Collects the install directory incl. `$FWDIR/log/*`, message logs, configuration and DB files, core dumps. `-S` censors password hashes (the **only** documented sanitisation). Private keys, certificates, SIC material: `UNKNOWN` | OFFICIAL, https://support.checkpoint.com/results/sk/sk92739 , https://support.checkpoint.com/results/sk/sk125092 . Runtime and size `UNKNOWN` |
| cpinfo on MDS | `mdsenv` then `cpinfo` (two `.info` files), or `cpinfo -c <Domain>` | `-x` skips the MDS export | as above | as above, plus every domain's data unless `-x` | OFFICIAL, sk92739. **Off by default here**: the MDS had a fork storm on 2026-11-11 (INCIDENT_2026_11_11_MDS_FORK_STORM.md) |
| cpinfo on VSX | `vsenv <VSID>` then `cpinfo`. No per-VS flag | one run per VS context | as above | as above | OFFICIAL, sk92739. Whether VS output differs from VS0: `UNKNOWN` |
| **HCP** (Health Check Point) | `hcp -r all --include-wts yes` (documented default). VSX: `-c <VSID>` from VS0. MDS: `--domain <Domain>`. Impact: `--set-performance-impact {low\|medium\|high}` (persistent), `hcp -l` lists each test's impact | The device runs HCP on its own schedule (every 6 h by default per HCP-894; neXus measured a run at about 03:00 on 36/52 gateways) | `/var/log/hcp/last/hcp_last_report.tar.gz` per sk171436. neXus measured `hcp_report_<host>_<dd_mm_yy_HH_MM>.tar.gz` on the fleet (system-log contract §8.1). **Contradiction**: the name varies by HCP take, so pick by listing, never by a fixed name | HCP-1664: the Expert password hash is no longer shown. Rest `UNKNOWN` | OFFICIAL, https://support.checkpoint.com/results/sk/sk171436 . Runtime `UNKNOWN`. Size ≤ 4.73 MiB measured (52 gateways) |
| **cpdata_collector (CPSDC)** — the PO's "cpdata_collect" | `cpdata_collector --run [-b members] [-c] [-d] [-m size] [-t window] [-p path] [-u]`. Also `cpinfo -Q "-r <flags>"` | Documented for Scalable Platforms (Maestro/chassis). Per-file cap 100 MB by default, 7-day modification window. Installed through Automatic Updates (`autoupdatercli install`) | `/var/log/cpdata_collector_sp_<DATE>_<TIME>.tgz` | `UNKNOWN` | OFFICIAL, https://support.checkpoint.com/results/sk/sk164414 . On plain Gaia GW/SMS/MDS: `UNKNOWN` (Q3) |
| Gaia extras | `clish -c 'show asset all'`, `clish -c 'show version all'`, `cphaprob stat`, `cpstat os -f multi_cpu` | Commands exist. No official "collect for an SR" list was found. cpinfo already includes messages and `$FWDIR/log/*`. CPView history is included through cpinfo `-V/-N` (not supported on Security Management Servers, sk101878) | session output | low (serials and versions are identity data, so treat as sensitive) | OFFICIAL as commands (Gaia R82 Admin Guide). "TAC asks for it": `UNKNOWN` |
| CPM Doctor | `cpm_doctor.sh` | Only mentioned in passing on official pages | — | — | COMMUNITY / `UNKNOWN`. Not proposed |

**Upload (Check Point).** cpinfo uploads by itself: `-s <SR>` with `-u <email>` (an interactive User Center
password prompt, not allowed with `-i`) or `-K <CK>` (a licence CK). Facts:

- Egress from **the device**: HTTPS 443 to `services.checkpoint.com`, and 443 or SFTP 22 to three Check Point hosts.
  A proxy can be set on the device.
- Limits: 10 GB per upload (sk92526). It needs the consent flag "Upload Information" (sk175504).
- `cp_uploader` has been deprecated since 2016 (sk84000).
- Upload from a central host: Check Point offers only the Windows `CPWinUploader` (sk108152). No Linux uploader and
  no SR-attachment API were found (`UNKNOWN`).
- Check Point's own data-handling and retention statement for SR files: `UNKNOWN`.

### 3.2 Palo Alto Networks

| Artefact | Command / API | What the vendor says | Lands at | Secrets | Confidence / source |
|---|---|---|---|---|---|
| **Tech support file (TSF)** | XML API, async: `type=export&category=tech-support` (start, returns job id) → `...&action=status&job-id=N` (`<status>FIN</status>` = ready) → `...&action=get&job-id=N` (octet-stream `.tgz`; **a successful get deletes the job**) → `...&action=finish&job-id=N` (stop a stuck job). CLI: `request tech-support dump`, `scp export tech-support to ...` | Async because the debug data is large. One sample: about 19.7 MB in about 90 s. GUI generation needs SuperUser. API role requirement `UNKNOWN` | sample `<resultfile>//tmp/techsupport.tgz</resultfile>`. Retention of an unretrieved file `UNKNOWN` | KB: "device configuration, system information, and some logs", "does not include traffic logs", "safe to share". COMMUNITY: passwords and private keys removed, names kept. No sanitisation option | OFFICIAL, https://docs.paloaltonetworks.com/ngfw/api/pan-os-xml-api-request-types-and-actions/export-files-api/export-technical-support-data , https://knowledgebase.paloaltonetworks.com/kcsArticleDetail?id=kA10g000000ClRl . MP CPU and disk impact `UNKNOWN` |
| Panorama TSF | the same API against Panorama (Panorama > Support > Generate Tech Support File) | — | — | as above | OFFICIAL. Whether Panorama can produce or fetch a **managed firewall's** TSF (`target=<serial>`): `UNKNOWN`. Collect each firewall directly |
| Stats dump | Panorama CLI `scp export stats-dump to ...`. No XML API category found | 7 days of traffic summaries for an SLR. Up to about 1 h per firewall | — | traffic metadata: sensitive | OFFICIAL, https://docs.paloaltonetworks.com/panorama/10-2/panorama-admin/troubleshooting/generate-a-stats-dump-file-for-a-managed-firewall . **Not proposed** (CLI push to an SCP server, heavy, not a TAC staple) |
| Core files | `show system files`, `scp export core-file management-plane from <f> to ...` | only Palo Alto engineers can read them | — | memory contents | OFFICIAL, https://knowledgebase.paloaltonetworks.com/kcsArticleDetail?id=kA10g000000ClQO . Later, listing only |
| Device state, configuration | `type=export&category=device-state` / `configuration` | already neXus backups | — | keys | Already collected by backups. **Not repeated** in a bundle |

**HA and vsys.** One TSF per device, taken on each member through its own management address. No vsys parameter
exists (device-wide). Palo Alto's high-MP-CPU case guide asks for the TSFs of both peers. That guide is a featured
article on the community site, so treat it as COMMUNITY.

**Upload (Palo Alto).** The documented routes are:

- **Customer Support Portal**, manual.
- **Device-originated** `scp export tech-support to <case>@tacupload.paloaltonetworks.com:./`. The username is the
  case number with leading zeros. The password is **the case contact's e-mail address**, so it is not a secret. The
  case gets an automatic comment after about 5 minutes.

The TAC Upload web UI was retired on 2025-05-22. Still `UNKNOWN`:

- whether any SCP client, not only the device, may use `tacupload`;
- its port and proxy support, and its size limit;
- any case-attachment API.

### 3.3 Fortinet FortiGate

| Artefact | Command | What the vendor says | Lands at | Secrets | Confidence / source |
|---|---|---|---|---|---|
| **TAC report** | `execute tac report` (same output as `diagnose debug report`). Multi-VDOM: `config global` first | Needs `super_admin`. Run while the issue is present. Output goes **to the session only**: no file on the device, so capture the SSH session. The GUI export ("FortiCare Debug Report" from 7.4.0) truncates the start. Runtime "a few seconds" in one tip and "a few minutes" in another (conflict). **Known defect**: with DoS policies configured, `diagnose ips anomaly list` inside the report kills the session (`free(): invalid pointer`). Fixed in 7.4.10, 7.6.6 and 8.0.0 | session | Contains global configuration, serial, firmware, HA diagnostics, crash log. Whether `ENC` secrets are masked: `UNKNOWN` | OFFICIAL (Fortinet-authored technical tips), https://community.fortinet.com/t5/FortiGate/Technical-Tip-Download-Debug-Logs-and-execute-tac-report/ta-p/189549 , https://community.fortinet.com/t5/FortiGate/Troubleshooting-Tip-Unable-to-generate-TAC-report-past-diagnose/ta-p/422562 . Size and CPU `UNKNOWN` |
| Crash log | `diagnose debug crashlog read` | ring buffer of at most 16384 lines | session | process names, addresses | OFFICIAL (snippet level), https://community.fortinet.com/t5/FortiGate/Technical-Tip-How-to-check-when-the-crash-log-is-full/ta-p/274727 |
| Configuration | `GET /api/v2/monitor/system/config/backup?scope=global` | reversibly encrypted secrets. 8.0.1 adds `password_mask` | — | yes | Already a neXus backup (`fgt_config_backup`). **Not repeated** |
| Debug report over REST | `UNKNOWN` path | only a third-party wrapper lists it | — | — | COMMUNITY. **Not proposed** |

Fortinet recommends `config system console / set output standard` before collecting. That is a configuration change,
and neXus answers `--More--` in the session instead (`FortiGatePlan`, unchanged).

**HA.** Fortinet says to collect from both primary and secondary. It gives `execute ha manage <index> <admin>` as the
way to reach the secondary. neXus proposes a direct session to each member's own management address instead, with
no hop through the primary (Q10).

**Upload (Fortinet).** The options are:

- **Portal, manual.** A comment is required before an attachment. No size limit is stated.
- **E-mail.** Limited to 20 MB, and Fortinet steers sensitive data away from it.
- **SFTP** to `support.fortinet.com:2222`. It works only after **the TAC engineer enables SFTP for that ticket**. The
  credentials are a per-ticket user and password from the portal, and files are kept 15 days
  (https://community.fortinet.com/t5/FortiGate/Technical-Tip-Uploading-large-files-to-a-support-ticket-using/ta-p/252019).

No public ticket API was found. A fully unattended upload is not possible.

### 3.4 Cisco ASA

| Artefact | Command | What the vendor says | Lands at | Secrets | Confidence / source |
|---|---|---|---|---|---|
| **show tech-support** | `show tech-support [detail \| file <url> \| no-config \| performance]`. `file` accepts `disk0:` etc. The forensic guide uses `show tech-support detail \| redirect disk0:/tech-support.txt` | **Do not use `terminal pager 0` with show commands: "can lead to a huge CPU load"**. No runtime or size figures. Multi-context: run from the system context (forensic guide). Whether one run covers every context `UNKNOWN`. Failover: `UNKNOWN` | session, or the `file` target | Includes the running configuration unless `no-config`. `show running-config` masks many passwords with `*****`. Whether show tech masks every secret (VPN PSKs, SNMP, keys): `UNKNOWN` | OFFICIAL, https://www.cisco.com/c/en/us/td/docs/security/asa/asa-cli-reference/S/asa-command-ref-S/show-t-commands.html , https://sec.cloudapps.cisco.com/security/center/resources/forensic_guides/asa_forensic_investigation.html |
| **crashinfo** | `show crashinfo` (latest crash file only from 9.7(1)). Never `crashinfo force` (reloads) or `clear crashinfo` | real crashes start `: Saved_Crash` | flash, `crashinfo_YYYYMMDD_HHMMSS_UTC` | memory and stack data | OFFICIAL, https://www.cisco.com/c/en/us/td/docs/security/asa/asa-cli-reference/S/asa-command-ref-S/show-cr-to-show-cz-commands.html |
| FXOS bundle | `connect local-mgmt` → `show tech-support fprm detail` (1000/2100/3100). 4100/9300 FXOS 2.7+: `show tech-support chassis 1 detail` | "several minutes". Sample sizes about 6 MB (2100/3100) to about 152 MB (9300 chassis). In ASA appliance mode the file also appears at `disk0:/fxos/` | `workspace:/techsupport/` | `UNKNOWN` | OFFICIAL, https://www.cisco.com/c/en/us/support/docs/security/sourcefire-defense-center/117663-technote-SourceFire-00.html . **Not proposed yet** (Q11) |

The ASA team enables SCP on the ASA, and neXus never enables it. `ssh scopy enable` is on by default from 9.19(1)
and deprecated in 9.23(1) (search summary, to verify), with no directory or wildcard support.

**Upload (Cisco CXD).** This is the most scriptable path of the four. All points are OFFICIAL from
https://www.cisco.com/c/en/us/support/web/tac/tac-customer-file-uploads.html :

- Host `cxd.cisco.com`. HTTPS PUT `https://cxd.cisco.com/home/<file>` with Basic auth `<SR number>:<upload token>`;
  success is 201.
- Also SCP/SFTP on 22 and FTPS on 990.
- The token is generated per case in Support Case Manager (Attachments, Generate Token). It is upload-only, with no
  read access.
- No file size limit.
- Files stay in CXD for 4 days and are encrypted in transit and at rest. They stay with the case up to 18 months after
  closure, and the customer may request a purge.
- The SCM token lifetime is `UNKNOWN`. The 72-day figure applies to Smart Bonding only.
- The Support Case API v3 is GET-only and partner-scoped, so there is no customer API to create a token or attach a
  file.

### 3.5 What every bundle has in common

- **It is secret-bearing and cannot be masked.** Configuration, host and object names, addresses, and often password
  hashes and other secrets. The vendors' own sanitisation is partial at best: Check Point `-S` covers hashes only;
  Palo Alto states only "safe to share"; Cisco and Fortinet say nothing. Running the neXus Data Sanitizer over a
  bundle would break the vendor's analysis tools (DiagnosticsView, HCP index, TSF parsers) and is not proposed.
- **It is per member.** No vendor offers a cluster-wide bundle. Both HA members are separate runs.
- **Runtime, size and CPU impact are `UNKNOWN`.** The pilot measures them per vendor (S7) before any schedule beyond
  one device.

## 4. Data protection for a bank (download and upload)

Download stays inside the bank. The real exposure is the upload. The proposed rules:

1. **Upload is off by default.** It is a separate phase (S8) behind:
   - a PO decision (Q8);
   - the bank's information-security and compliance offices. Vendor support servers are outside Türkiye, so
     cross-border transfer of bank data may fall under BDDK and KVKK rules. That is the bank's call, not neXus's.
2. **Egress from neXus, never from a firewall.** Firewalls keep no internet path. Where the only documented
   scripted path is device-originated (Check Point cpinfo `-s`, Palo Alto `scp ... tacupload`), neXus does not
   automate it. The administrator downloads the bundle and uploads it by hand on the vendor portal.
3. **Per-case request, four-eyes.** One administrator (`security_admin`) requests the upload of named items to one
   case. A **different** `security_admin` approves it. The approval screen shows:
   - device (pseudonymised in aiview contexts);
   - each item's name, size and SHA-256;
   - destination host and protocol;
   - case number;
   - the requester's reason.

   An approval is valid for one upload of exactly those items, for at most 24 hours (proposal).
4. **Credentials are typed per upload and never stored.** The Cisco token or the Fortinet per-ticket SFTP password
   lives in worker memory for the upload only. It is never written to the database, logs or transcript.
5. **Proxy and allowlist.** neXus reaches only the vendor hosts in §3, through the bank's proxy. The allowlist is a
   deployment setting and is empty by default.
6. **Audit.** Request, approval, refusal, each upload start and end (bytes, SHA-256, HTTP status), and every
   download. The audit row is written before the first byte, as for backups (OR-3).

## 5. Architecture

### 5.1 Objects

- **Support bundle request.** One device, a list of artefact kinds, a run mode (`NOW` or `AT <time>`), an optional
  vendor case reference (free text up to 40 characters, `[A-Za-z0-9-]`; used only as a label and for a later upload),
  a reason of at least 8 characters, and the requester. Requests for several devices create one request per device.
- **Item.** One artefact kind inside a request, with its own state and outcome, artefact reference, size, SHA-256 and
  gate ids used.
- **Job.** One `jobs` row per request: job type `support_bundle_collect`, capability per vendor
  (`cp_support_bundle`, `pan_support_bundle`, `fgt_support_bundle`, `asa_support_bundle`). Items run **sequentially
  in one session**, cheapest first. A failed item does not stop the next, except a failed precheck, which stops them
  all.

Schema, numbered at implementation time and dry-run on the live DB in `BEGIN/ROLLBACK` before the deploy:

- `support_bundle_request`: `id`, `device_id`, `run_mode`, `run_at`, `case_ref`, `reason`, `requested_by`,
  `requested_at`, `job_id`, `state`, `outcome`.
- `support_bundle_item`: `request_id`, `kind`, `state`, `outcome_code`, `outcome_detail`, `artefact_ref`,
  `size_bytes`, `sha256`, `started_at`, `finished_at`, `vs_id` or `domain_ref` where used.
- Phase S8: `support_bundle_upload` (`id`, items, `destination_kind`, `case_ref`, `requested_by`, `approved_by`,
  `approved_at`, `expires_at`, `state`, `bytes_sent`, `http_status`).
- `backup_artefact.artefact_class` gains `support_bundle`.

### 5.2 Artefact catalogue (closed vocabulary; the browser sends kinds, never commands)

| Kind | Vendor / role | Device load (expected) | Default | Step |
|---|---|---|---|---|
| `cp_hcp_latest` | CP gateway, VSX host | **none** if the system-log copy from today is in the vault; otherwise one `ls` + one SFTP get | ticked | S2 |
| `cp_gaia_extras` | CP all | four short reads | ticked | S2 |
| `cp_hcp_run` | CP gateway; VSX `-c <VSID>`; MDS `--domain` off | medium (vendor tests; impact level per test) | unticked | S6 |
| `cp_cpinfo` | CP gateway; VSX per VS; SMS with `-x`; **MDS off by default** | **high** (vendor: no core above 70%) | unticked | S6 |
| `cp_cpsdc` | CP, only if Q3 says yes | `UNKNOWN` | hidden | — |
| `pan_tsf` | PAN firewall, Panorama | management plane, `UNKNOWN` | ticked | S3 |
| `asa_crashinfo` | ASA | low | ticked | S4 |
| `asa_show_tech` | ASA (system context in multi-context) | `UNKNOWN`. Cisco's pager warning | ticked | S4 |
| `fgt_crashlog` | FortiGate | low | ticked | S5 |
| `fgt_tac_report` | FortiGate | `UNKNOWN`. Refused on versions with the known defect when the stored configuration has a DoS policy (§5.4) | ticked | S5 |

`cp_cpinfo` options are fixed, not chosen per request:

- **Always:** `-z -D -d -S`. Compressed, no upload, no self-update check, hashes censored.
- **Also `-x` on SMS.**
- **Never** `-l` (traffic log) or `-k` (kernel tables). If TAC asks for them, they are a separate kind and a separate
  approval (Q4).

### 5.3 Scheduling and job windows

- **Run now.** This is an administrator's manual job (PO 2026-10-20): `security_admin` only, never held to a window,
  and inside business hours (08:00–20:00) the existing confirmation (`BUSINESS_HOURS_CONFIRM_REQUIRED`, ack header)
  applies. The job carries `window_bypass_role`.
- **Run at a time.** Today `JobWindowPolicy.requireOpen(Instant)` says "an explicit time: always the window, grant or
  not". It was written for the failover scheduled start. Two options (Q1):
  - **(a) Recommended: treat it as a deferred admin manual job.** The administrator picks any time in the next 7 days.
    The job is written with `window_bypass_role` and `admission_not_before = <time>`. If the chosen time is inside
    business hours, the confirmation is asked **at submission, against the chosen time**. The existing claim-time
    recheck (Argon P2-1) still refuses a job whose run time drifted into business hours without an ack. This needs a
    one-line PO amendment to the 2026-10-20 record, because today it covers immediate submissions only.
  - **(b) Window only.** The picker offers 00:00, 06:00, 12:00 and 18:00 slots only (60 minutes each).
- Either way, the UI warns about times that collide with neXus's own night work:
  - 02:00–02:59 scheduled backups and MDS export;
  - about 03:00, the device's own HCP run (measured on 36/52 gateways);
  - 06:00, the system-log HCP copy.

  It suggests 00:00 or 04:00. A collision is not refused. The busy-device guard (§5.4) defers the run instead.
- A scheduled request may be cancelled until its job is claimed. After the point of no return (§5.5) a cancel only
  stops further items. The running tool finishes and its work directory is cleaned up if its exit code arrives.

### 5.4 Device-load guards (all fail-closed; refusal reasons shown to the requester)

| Guard | Where | Rule |
|---|---|---|
| One bundle per device | DB, partial unique index on active support-bundle jobs per `device_id` | A second request for a device with one already waiting or running is refused at submission (`SUPPORT_BUNDLE_ALREADY_ACTIVE`) |
| Not both HA members at once | claim | If a support-bundle job is RUNNING on another member of the same cluster or HA pair, defer by 10 minutes (`admission_not_before`). Requesting both members is allowed; they run one after the other. Membership comes from inventory (cluster id). Unknown membership is treated as "single", and the UI says so |
| Device busy | claim | A RUNNING backup, MDS export, configuration or policy job on the same device defers by 10 minutes. Per-device job serialization is written but not merged, so this is a local check until it lands |
| Give up | claim | Not started within 3 hours after its time: `NOT_STARTED_DEVICE_BUSY` (proposal) |
| Fleet cap | claim | At most 2 support-bundle jobs running fleet-wide (setting); the rest defer |
| CPU precheck | first commands of the run | CP: any core above 70% refuses `cp_cpinfo` and `cp_hcp_run` (**vendor rule**, sk92739), and the cheap items still run. PAN, FGT, ASA: management CPU above 80% (**local heuristic**, not a vendor figure) refuses `pan_tsf`, `fgt_tac_report` and `asa_show_tech` |
| Disk precheck | first commands | CP: free bytes in `/var/log` below 2 GiB refuses `cp_cpinfo` and `cp_hcp_run` (local heuristic, same parser as the MDS export). PAN: `show system disk-space` with root or `/opt/panlogs` above 90% refuses `pan_tsf` (heuristic). ASA: no file is written in the default form. FGT: no file |
| Version guard | before connect, from stored data | FortiGate below 7.4.10 (7.4 train), below 7.6.6 (7.6 train), or on 7.0/7.2, **and** a stored configuration containing `config firewall DoS-policy`: `fgt_tac_report` is refused (`VENDOR_DEFECT_SESSION_CRASH`) and the crash log still runs. Uses the stored configuration; no device command |
| Size bound | fetch | Per kind, as a safety bound, not a vendor figure: cpinfo 20 GiB (vendor upload cap is 10 GB, so twice that); TSF 2 GiB; session captures 256 MiB; HCP 16 MiB (as system-log). Over the bound: `TOO_LARGE`, nothing stored, device file removed |
| Run deadline | worker | cpinfo and HCP runs: 2 h; TSF job: 30 min; session captures: 20 min. On deadline: PAN `action=finish`; CP: `OUTCOME_UNKNOWN`, work directory left (the MDS export rule); FGT and ASA: session closed, item `TIMED_OUT` |

### 5.5 Execution per vendor

Every CP command carries the `CP_ENV` prefix. The work directory is `/var/log/nexus-sb-<token>`, with the token
taken from the job id only (`MdsExportPlan.workDir` rule).

**Check Point (SSH, Expert):**

1. Version: `show version all` (existing gate).
2. Prechecks: `cpstat os -f multi_cpu` and `df -P /var/log`.
3. `cp_gaia_extras`: four reads, streamed into the bundle directory.
4. `cp_hcp_latest`:
   1. If the system-log store holds a `cp_hcp_report` for this device from the last 24 hours, copy it inside the
      vault. No device contact.
   2. Otherwise `ls -lgo /var/log/hcp/last/` and SFTP-get the newest `*.tar.gz` by its listed name.
5. `mkdir`. **Point of no return marked.**
6. Heavy items, one at a time: start with `setsid nohup ... echo $? > <tool>.rc`, poll the rc file every 30 s up to
   the deadline, then `ls`.
   - HCP writes into `/var/log/hcp/last/`. neXus copies the newest archive by its listed name into the work
     directory. It **replaces the device's own "last" report**: that is what any manual `hcp -r` does, and the UI
     says so.
7. `tar -czf`, `sha256sum`, SFTP stream into the vault, compare, then `rm -rf` of the exact work directory and
   bundle.
   - A digest mismatch deletes nothing on the device (BK-3).

**VSX:**

- cpinfo runs once per selected VS: `vsenv <VSID>` and cpinfo in one command line. The VSID is validated as an
  integer from inventory.
- HCP runs from VS0 with `-c <VSID>`.

**MDS:** `cp_cpinfo` and `cp_hcp_run` are hidden until Q5 (fork-storm history).

**Palo Alto (XML API, one key per run):**

1. Prechecks: `show system resources` and `show system disk-space`.
2. Start `type=export&category=tech-support` and record the job id.
3. Poll `action=status` every 15 s until `FIN`, up to 30 minutes.
4. Stream `action=get` into the vault through the existing streaming transport. A successful get deletes the job on
   the device. The SHA-256 is computed on receipt. There is no device-side digest, the same as device-state backups.
5. On deadline, send `action=finish`, then record `TIMED_OUT`.

The size bound and gzip check reuse `PaloAltoBackupExecutor`'s stream validation (`gzip` magic, XML error body →
refusal).

**FortiGate (SSH interactive, existing session handling):**

1. `get system status`: confirm the version and HA mode against the version guard.
2. `get system performance status` as the CPU precheck.
3. `config global` (multi-VDOM only).
4. `diagnose debug crashlog read`, then `execute tac report`, then `end`.
5. Output is **streamed into the vault sink** as it arrives. It is never buffered whole in memory and never written
   to the job transcript, which records only lengths and timings.
6. `--More--` is answered in session.
7. Completion: the prompt returns. A session that drops mid-report gives `SESSION_LOST`, and the partial capture is
   stored and marked partial (TAC can still use it).

**Cisco ASA (SSH, privileged EXEC):**

1. Precheck `show cpu usage`.
2. Multi-context: `changeto system`.
3. `show crashinfo`, then `show tech-support`.
4. Output is streamed into the vault sink.

**This session does not send `terminal pager 0`**, because of Cisco's CPU warning. The `<--- More --->` prompt is
answered in session instead. That is a behaviour difference from the inventory session, which does send it. Whether
that inventory practice should change is a separate question (risk R7).

**Fallback, behind its own approval:** `show tech-support file disk0:/nexus-sb-<hex>.txt`, SCP pull, then
`delete /noconfirm disk0:/nexus-sb-<hex>.txt`. It is used only if session capture proves unreliable in the pilot.

### 5.6 Storage, retention, quota

- **Store.** Encrypted artefact store, class `support_bundle`, one artefact per item, plus a `manifest.json` per
  request (kinds, sizes, digests, neXus version, device pseudonym, no hostname). The download is a `.tgz` of the
  items, produced as a stream.
- **Retention.** `expires_at` = collection plus **14 days** by default (Q2). A `security_admin` may extend it once to
  60 days with a reason, for example while a case is open. The retention janitor deletes expired items.
  `security_admin` may delete early with a reason (audited).
- **Quota.** Per device at most 3 requests stored. Fleet cap 50 GiB of `support_bundle` bytes (setting; proposal,
  Q2). A request that would exceed it is refused at submission with the current use shown. This integrates with the
  vault quota lane (`vault_has_no_enforced_quota_on_a_shared_host`): when that lane lands, `support_bundle` is one
  of its classes and these caps become its per-class limits.
- **Never parsed, never shown.** Bundles never feed inventory, compliance, the sanitizer, aiview or any report. This
  design is the forensics contract that the raw-evidence law requires before raw device output may be retained.

### 5.7 RBAC and audit

| Action (new, `ActionRegistry`) | Route | Who |
|---|---|---|
| `support_bundle_read` | `GET /api/v2/support-bundles`, `GET /api/v2/devices/{id}/support-bundles` | operator and above, and aiview: **metadata only**. Pseudonymised device, kinds, state, outcome, size, digest prefix. Never bytes, paths or case reference |
| `support_bundle_request` | `POST /api/v2/devices/{id}/support-bundles` | `security_admin` only (Q6: also `operation_admin`?) |
| `support_bundle_cancel` | `POST /api/v2/support-bundles/{id}/cancel` | `security_admin` |
| `support_bundle_retrieve` | `POST .../{id}/download-ticket`, `GET .../{id}/download?ticket=` | `security_admin`. Reason of at least 8 characters, single-use 60 s ticket, audit row before the first byte (the backup download pattern). **Never aiview** |
| `support_bundle_retention_set` / `support_bundle_delete` | `POST .../{id}/extend`, `POST .../{id}/delete` | `security_admin`, with a reason |
| `support_bundle_upload_request` / `_approve` (S8) | `POST .../{id}/uploads`, `POST /api/v2/support-bundle-uploads/{id}/approve` | `security_admin`. The approver must differ from the requester (four-eyes) |

Every action is audited through the existing audit ledger. CLI parity: `nexus support-bundle request | list | cancel
| download | extend | delete`, under the same actions.

### 5.8 Download UI

The UI lives under **Tools → Support bundles** (the Tools group already holds Packet analysis, Data sanitizer and
Diagnostics). The device workspace gets a "Collect support bundle" action that opens the same form with the device
preset. The mockup is `ops-scripts/support-bundle-mockup-1114.html`. It is outside the repository and shows the
masked state only.

1. **New request.**
   - Device picker.
   - Vendor-specific ticks, each with a load badge and an "Adds to device load" note.
   - Run now / Run at, with the window and collision hints.
   - Optional case reference and a reason.
   - A live guard preview. Examples: "HA peer FW-TANGO-05 has a bundle scheduled 00:00, this one will start
     after it", "Fortinet defect guard: TAC report will be skipped".
2. **Requests list.** State per item, size, digest prefix, expiry, and a Download button (asks for a reason, then
   the browser download).
3. **Upload panel (S8).** Request, approve and history, with destination host and item digests.

## 6. Command gate table (proposed literals; each needs the PO's exact-literal approval)

Notation: `%s` is the run's work directory; `<VSID>` is an inventory-validated integer; `<n>` is a device-issued job
number, parsed as digits only. **Class** follows the V61 precedent: file-creating and removing commands are recorded
`recovery-write` but are BW-2 transient artefacts (PO 14K), not recovery writes. Session-capture reads are `read`.

**Check Point** (`cp_gaia_gateway`, also `cp_vsx_gateway`/`cp_security_management` where noted; transport `SSH_EXEC`,
shell `expert`; every literal is prefixed by `CP_ENV` = `unset BASH_ENV ENV; . /etc/profile.d/CP.sh >/dev/null 2>&1;
unset BASH_ENV ENV; `):

| # | Gate id (proposed) | Literal | Class | Timeout | Frequency | Secret risk | Step |
|---|---|---|---|---|---|---|---|
| C1 | `cp_sb_cpu_multi` | `cpstat os -f multi_cpu` | read | 30 s | once per run | none | S2 |
| C2 | `cp_sb_df_var_log` | `df -P /var/log` | read | 30 s | once per run | none | S2 |
| C3 | `cp_sb_show_asset_all` | `clish -c 'show asset all'` | read | 60 s | once per run | identity (serials) | S2 |
| C4 | `cp_sb_show_version_all` | `clish -c 'show version all'` (existing gate `cp_gaia_show_version_all`, new purpose) | read | 30 s | once per run | none | S2 |
| C5 | `cp_sb_cphaprob_stat` | `cphaprob stat` (existing gate `cp_gaia_cphaprob_stat`, new purpose) | read | 30 s | once per run | identity | S2 |
| C6 | `cp_sb_ls_hcp_last` | `ls -lgo --time-style=+%s /var/log/hcp/last/` (system-log `cp_syslog_collect_ls_hcp`, new purpose, no `bash -lc`) | read | 30 s | once per run | file names carry host names | S2 |
| C7 | `cp_sb_sftp_get_hcp` | SFTP get of `/var/log/hcp/last/<listed name>`; name pattern `[A-Za-z0-9_.-]+\.tar\.gz` | read | 300 s | once per run | report content | S2 |
| C8 | `cp_sb_mkdir` | `mkdir -p %s` | recovery-write (BW-2) | 30 s | once per run | none | S6 |
| C9 | `cp_sb_hcp_start` | `cd %1$s && setsid nohup bash --noprofile --norc -c "hcp -r all --include-wts yes -s > %1$s/hcp.log 2>&1; echo \$? > %1$s/hcp.rc" </dev/null >/dev/null 2>&1 &` | recovery-write (BW-2) | 60 s | once per run, never retried | report content | S6 |
| C10 | `cp_sb_hcp_start_vs` | as C9 with `hcp -r all --include-wts yes -s -c <VSID>` (from VS0) | recovery-write (BW-2) | 60 s | once per VS per run | report content | S6 |
| C11 | `cp_sb_copy_hcp` | `cp /var/log/hcp/last/<listed name> %s/` | recovery-write (BW-2) | 60 s | once per HCP run | report content | S6 |
| C12 | `cp_sb_cpinfo_start` | `cd %1$s && setsid nohup bash --noprofile --norc -c "cpinfo -z -D -d -S -o %1$s/cpinfo > %1$s/cpinfo.log 2>&1; echo \$? > %1$s/cpinfo.rc" </dev/null >/dev/null 2>&1 &` | recovery-write (BW-2) | 60 s | once per run, never retried | **secret-bearing** | S6 |
| C13 | `cp_sb_cpinfo_start_sms` | as C12 with `-x` added (Security Management) | recovery-write (BW-2) | 60 s | once per run | **secret-bearing** | S6 |
| C14 | `cp_sb_cpinfo_start_vs` | as C12 with `vsenv <VSID> >/dev/null 2>&1 && cpinfo ...` inside the quoted command | recovery-write (BW-2) | 60 s | once per VS per run | **secret-bearing** | S6 |
| C15 | `cp_sb_poll_rc` | `cat %s/<hcp\|cpinfo>.rc` | read | 30 s | every 30 s until the deadline | none | S6 |
| C16 | `cp_sb_list_workdir` | `ls -l %s` | read | 30 s | once per item | file names | S6 |
| C17 | `cp_sb_bundle` | `cd %1$s && tar -czf %1$s.tgz .` | recovery-write (BW-2) | 3600 s | once per run | **secret-bearing** | S6 |
| C18 | `cp_sb_sha256` | `sha256sum %s.tgz` | read | 600 s | once per run | none | S6 |
| C19 | `cp_sb_sftp_get_bundle` | SFTP streaming get of `%s.tgz` | read | 7200 s | once per run | **secret-bearing** | S6 |
| C20 | `cp_sb_remove_workdir` | `rm -rf %1$s %1$s.tgz` | recovery-write (BW-2) | 120 s | once per run, retried once | none | S6 |

C12 to C14 need a lab check of `-i` (non-interactive). sk92739 says `-i` goes with `-n` or `-R`, and with `-D` there
may be no SR prompt at all. The run also feeds stdin from `/dev/null`, so a prompt makes cpinfo exit and the rc file
records the failure. No retry follows.

**Palo Alto** (`pan_firewall`, `panorama`; transport `XML_API`, existing keygen):

| # | Gate id | Literal | Class | Timeout | Frequency | Secret risk | Step |
|---|---|---|---|---|---|---|---|
| P1 | `pan_sb_system_resources` | `type=op&cmd=<show><system><resources></resources></system></show>` | read | 30 s | once per run | none | S3 |
| P2 | `pan_sb_disk_space` | `type=op&cmd=<show><system><disk-space></disk-space></system></show>` | read | 30 s | once per run | none | S3 |
| P3 | `pan_sb_tsf_start` | `type=export&category=tech-support` | recovery-write (BW-2: the device writes `/tmp/techsupport.tgz`) | 60 s | once per run, never retried | **secret-bearing** | S3 |
| P4 | `pan_sb_tsf_status` | `type=export&category=tech-support&action=status&job-id=<n>` | read | 30 s | every 15 s until FIN or deadline | none | S3 |
| P5 | `pan_sb_tsf_get` | `type=export&category=tech-support&action=get&job-id=<n>` | read (removes the device job on success) | 1800 s | once per run | **secret-bearing** | S3 |
| P6 | `pan_sb_tsf_finish` | `type=export&category=tech-support&action=finish&job-id=<n>` | recovery-write (BW-2 cleanup) | 30 s | at most once, on deadline | none | S3 |

**FortiGate** (`fortigate`; transport `SSH_INTERACTIVE`, the existing session):

| # | Gate id | Literal | Class | Timeout | Frequency | Secret risk | Step |
|---|---|---|---|---|---|---|---|
| F1 | `fgt_sb_get_system_status` | `get system status` (existing, new purpose) | read | 30 s | once per run | identity | S5 |
| F2 | `fgt_sb_performance_status` | `get system performance status` | read | 30 s | once per run | none | S5 |
| F3 | `fgt_sb_config_global` / `fgt_sb_end` | `config global` / `end` (existing context moves) | context | 10 s | once per run | none | S5 |
| F4 | `fgt_sb_crashlog_read` | `diagnose debug crashlog read` | read | 120 s | once per run | low | S5 |
| F5 | `fgt_sb_tac_report` | `execute tac report` | read (no device file) | 1200 s | once per run | **secret-bearing** (global configuration) | S5 |

**Cisco ASA** (`cisco_asa_firewall`; transport `SSH_INTERACTIVE`, privileged EXEC, **no** `terminal pager 0`):

| # | Gate id | Literal | Class | Timeout | Frequency | Secret risk | Step |
|---|---|---|---|---|---|---|---|
| A1 | `asa_sb_show_cpu_usage` | `show cpu usage` | read | 30 s | once per run | none | S4 |
| A2 | `asa_sb_changeto_system` | `changeto system` (multi-context only) | context | 10 s | once per run | none | S4 |
| A3 | `asa_sb_show_crashinfo` | `show crashinfo` | read | 120 s | once per run | memory and stack data | S4 |
| A4 | `asa_sb_show_tech_support` | `show tech-support` | read | 1200 s | once per run | **secret-bearing** (running configuration) | S4 |
| A5–A7 | fallback only | `show tech-support file disk0:/nexus-sb-<hex>.txt`, SCP pull of that name, `delete /noconfirm disk0:/nexus-sb-<hex>.txt` | recovery-write (BW-2) | 1200/1800/60 s | once per run | **secret-bearing** | only if S4 shows session capture unreliable |

Count: 20 Check Point, 6 Palo Alto, 6 FortiGate (F3 is two literals), 4 ASA, plus 3 ASA fallback rows: 39 rows.

- **31 new literals.** CP C1–C3, C8–C18 and C20; P1–P6; F2, F4, F5; A1–A4; and the A5–A7 fallback.
  - `df -P /var/log` (C2) exists only for the MDS scope today, so for gateways it counts as new.
- **8 existing commands for a new purpose.**
  - C4, C5 and F1 exist already.
  - The F3 context moves (`config global`, `end`) are existing.
  - C6 exists as a system-log read and is re-approved without its `bash -lc` wrapper.
  - C7 reuses the system-log SFTP read.
  - C19 reuses the backup SFTP stream.

Approval goes per step: S2 asks for C1–C7, S3 for P1–P6, S4 for A1–A4, S5 for F1–F5, and S6 for C8–C20.

Every row also needs these gate fields at contract time:

- unsupported behaviour, for example:
  - CP: `hcp` not installed gives rc 127, item `UNSUPPORTED`;
  - PAN: an XML error body means refused;
  - FGT: `Unknown action` means `UNSUPPORTED`;
  - ASA: `% Invalid input` means `UNSUPPORTED`;
- session reuse: one session per run;
- retry: none after a command has started;
- safe telemetry: lengths, timings and rc values only.

## 7. Outcomes

Per item:

- `COMPLETED`
- `PARTIAL` (FGT/ASA capture ended early; stored and marked)
- `REFUSED_PRECHECK` (CPU / disk / version, with the measured value)
- `UNSUPPORTED`
- `TIMED_OUT`
- `TOO_LARGE`
- `DIGEST_MISMATCH` (nothing removed on the device)
- `OUTCOME_UNKNOWN` (lost contact after start; work directory left; the UI offers "remove leftover work directory",
  which issues C20 for that exact recorded name after an rc file is seen)
- `CLEANUP_FAILED`
- `CANCELLED`

Per request:

- `COMPLETED` when every item completed;
- `PARTIAL` when at least one item stored;
- `FAILED` otherwise;
- `NOT_STARTED_DEVICE_BUSY`.

Notifications reuse the existing masked notification path: request finished or failed, and leftover work directory.

## 8. Risks

| # | Risk | Mitigation |
|---|---|---|
| R1 | Bundles carry bank secrets (configuration, hashes, possibly keys) | encrypted at rest; `security_admin` only; reason and audit per download; 14-day expiry; never parsed or shown; upload off by default with four-eyes |
| R2 | cpinfo or HCP loads a production gateway | vendor 70% per-core rule enforced; HA one member at a time; fleet cap 2; off by default; pilot measures first |
| R3 | MDS: the fork storm history (2026-11-11) | MDS heavy items hidden (Q5); `CP_ENV` normalization on every CP command |
| R4 | FortiGate session crash defect | version and DoS-policy guard from stored data; crash log still collected |
| R5 | Leftover files on devices after lost contact | exit code file, exact-name cleanup, UI action, notification; never wildcard deletes |
| R6 | Vault growth | 14-day expiry, per-device and fleet caps, the vault quota lane |
| R7 | ASA `terminal pager 0` CPU warning applies to the existing inventory session too | this design avoids it. A follow-up is proposed to review the inventory session (not changed here) |
| R8 | HCP run replaces the device's own "last" report | stated in the UI. `cp_hcp_latest` (no run) is the default |
| R9 | Upload sends bank data abroad | S8 only after Q8 and the bank's compliance office; neXus-side egress only; per-case four-eyes; credentials never stored |
| R10 | Vendor option wording was read through summaries | lab verification of every literal before freeze (S1 task) |
| R11 | A scheduled time collides with backups, HCP or system logs | UI hints; claim-time deferral; give-up after 3 h |

## 9. Delivery plan (small steps; each ships alone)

| Step | What | Device contact | Needs |
|---|---|---|---|
| S0 | PO answers Q1–Q12; this document moves to FROZEN for S1–S3 scope | none | PO |
| S1 | Schema (request, item, artefact class), RBAC actions, list/download routes, CLI parity, UI shell with the catalogue but every kind disabled, retention janitor class, quota counters. Verify every literal in §6 against the raw vendor pages | none | migration dry-run, Java tests, preview e2e |
| S2 | CP `cp_hcp_latest` (copy from the vault first) and `cp_gaia_extras` | read-only, light | gates C1–C7 |
| S3 | PAN `pan_tsf` | API, BW-2 transient | gates P1–P6 |
| S4 | ASA `asa_crashinfo`, `asa_show_tech` (session capture) | read-only | gates A1–A4 |
| S5 | FortiGate `fgt_crashlog`, `fgt_tac_report` with the version guard | read-only | gates F1–F5 |
| S6 | CP `cp_hcp_run` and `cp_cpinfo` (gateways, VSX per VS, SMS with `-x`) | heavy, BW-2 | gates C8–C20 |
| S7 | PO-attended pilot: one device per vendor, outside business hours; record runtime, size, CPU before and after into a measurement record (committed with the commands it used) | yes | PO present |
| S8 | Upload, Cisco CXD first (HTTPS PUT, typed token), Fortinet SFTP second (typed per-ticket password), four-eyes, allowlist, audit | vendor hosts, from neXus | Q8, bank compliance, egress allowlist |

Every step runs the Java tests, the architecture tests, the tools tests and the preview e2e before ship (standing
rules). Steps S2–S6 are each AUTOMATED_VALIDATED at merge and REAL_ENV_VALIDATED only after S7.

## 10. Open questions for the Product Owner

| # | Question | Recommendation |
|---|---|---|
| Q1 | "Run at a time": any time an administrator picks (treated as a deferred admin manual job, business-hours confirm against the chosen time), or only the 00/06/12/18 windows? | Any time (option a), with collision hints; amend the 2026-10-20 record in one line |
| Q2 | Retention and quota: 14 days default (extend once to 60 with a reason), 3 per device, 50 GiB fleet? | Yes |
| Q3 | `cpdata_collector` (CPSDC): Check Point documents it for Maestro/Scalable Platforms. Should we check on one gateway whether it is installed (one `ls`, a new gate) before deciding, or leave it out? | Leave out until a TAC engineer actually asks for it |
| Q4 | cpinfo options fixed at `-z -D -d -S` (+`-x` on SMS). Traffic logs (`-l`) and kernel tables (`-k`) excluded. Agree? | Yes |
| Q5 | MDS: keep cpinfo and HCP runs off on Multi-Domain Servers for now (fork storm history)? | Yes, until the MDS fixes have run cleanly for 30 days |
| Q6 | Request and download are `security_admin` only, as the brief says. Should `operation_admin` also be allowed to *request* (never download)? | No, `security_admin` only for now |
| Q7 | Default ticks per vendor as in §5.2 (light items ticked, cpinfo and HCP run unticked)? | Yes |
| Q8 | Upload to vendor cases: wanted at all, given that bundles cannot be masked and the data leaves the bank? If yes, which vendors first? | Ask the bank's information-security and compliance offices first; then Cisco CXD only, then Fortinet SFTP; Check Point and Palo Alto stay manual (device-originated only) |
| Q9 | Four-eyes for upload: approver must be a different `security_admin`; approval valid 24 h for exactly the listed items? | Yes |
| Q10 | HA members: collect each member over its own management address (no `execute ha manage` / peer hop)? | Yes |
| Q11 | FXOS chassis bundles (Firepower 4100/9300 `show tech-support chassis`): needed? neXus has no FXOS endpoints today | Not now |
| Q12 | ASA: avoid `terminal pager 0` in this session (Cisco CPU warning). Also open a follow-up to review the inventory session that uses it? | Yes to both |

## 11. Sources

Official pages, with the confidence notes in §3:

- Check Point:
  - sk92739 (cpinfo): https://support.checkpoint.com/results/sk/sk92739
  - sk125092 (cpinfo files): https://support.checkpoint.com/results/sk/sk125092
  - sk171436 (HCP): https://support.checkpoint.com/results/sk/sk171436
  - sk164414 (CPSDC): https://support.checkpoint.com/results/sk/sk164414
  - sk84000 (uploader): https://support.checkpoint.com/results/sk/sk84000
  - sk108152: https://support.checkpoint.com/results/sk/sk108152
  - sk92526 (10 GB limit): https://support.checkpoint.com/results/sk/sk92526
  - sk175504 (consent): https://support.checkpoint.com/results/sk/sk175504
  - sk101878 (CPView): https://support.checkpoint.com/results/sk/sk101878
  - Gaia R82 Admin Guide, Hardware Health Monitoring:
    https://sc1.checkpoint.com/documents/R82/WebAdminGuides/EN/CP_R82_Gaia_AdminGuide/Content/Topics-GAG/Hardware-Health-Monitoring.htm
- Palo Alto:
  - Export Technical Support Data API:
    https://docs.paloaltonetworks.com/ngfw/api/pan-os-xml-api-request-types-and-actions/export-files-api/export-technical-support-data
  - Export Files API: https://docs.paloaltonetworks.com/ngfw/api/pan-os-xml-api-request-types-and-actions/export-files-api
  - Async requests:
    https://docs.paloaltonetworks.com/ngfw/api/pan-os-xml-api-request-types-and-actions/asynchronous-and-synchronous-requests-to-the-pan-os-xml-api
  - KB TSF: https://knowledgebase.paloaltonetworks.com/kcsArticleDetail?id=kA10g000000ClRl
  - KB core files: https://knowledgebase.paloaltonetworks.com/kcsArticleDetail?id=kA10g000000ClQO
  - Device > Support (12.1): https://docs.paloaltonetworks.com/ngfw/help/12-1/device/device-support
  - Stats dump:
    https://docs.paloaltonetworks.com/panorama/10-2/panorama-admin/troubleshooting/generate-a-stats-dump-file-for-a-managed-firewall
- Fortinet (Fortinet-authored technical tips):
  - TAC report: https://community.fortinet.com/t5/FortiGate/Technical-Tip-Download-Debug-Logs-and-execute-tac-report/ta-p/189549
  - Defect: https://community.fortinet.com/t5/FortiGate/Troubleshooting-Tip-Unable-to-generate-TAC-report-past-diagnose/ta-p/422562
  - IoC collection: https://community.fortinet.com/t5/FortiGate/Technical-Tip-Collect-Indicators-of-Compromise-IoC-debugs-on-a/ta-p/330629
  - SFTP upload: https://community.fortinet.com/t5/FortiGate/Technical-Tip-Uploading-large-files-to-a-support-ticket-using/ta-p/252019
  - REST config backup: https://community.fortinet.com/t5/FortiGate/Technical-Tip-Get-backup-config-file-on-FortiGate-using-RestAPI/ta-p/202286
  - HA CLI: https://community.fortinet.com/t5/FortiGate/Technical-Tip-Managing-individual-cluster-units-with-the-CLI/ta-p/197110
- Cisco:
  - show tech-support: https://www.cisco.com/c/en/us/td/docs/security/asa/asa-cli-reference/S/asa-command-ref-S/show-t-commands.html
  - show crashinfo: https://www.cisco.com/c/en/us/td/docs/security/asa/asa-cli-reference/S/asa-command-ref-S/show-cr-to-show-cz-commands.html
  - crashinfo commands: https://www.cisco.com/c/en/us/td/docs/security/asa/asa-cli-reference/A-H/asa-command-ref-A-H/clf-crx-commands.html
  - ASA forensic guide: https://sec.cloudapps.cisco.com/security/center/resources/forensic_guides/asa_forensic_investigation.html
  - FXOS files: https://www.cisco.com/c/en/us/support/docs/security/sourcefire-defense-center/117663-technote-SourceFire-00.html
  - Customer file uploads (CXD): https://www.cisco.com/c/en/us/support/web/tac/tac-customer-file-uploads.html
  - Smart Bonding attachments: https://developer.cisco.com/docs/smart-bonding-customer-api/attachment-information
  - Case API: https://developer.cisco.com/docs/support-apis/case/

COMMUNITY sources used only where marked: the Palo Alto TSF-contents forum answers and the Fortinet config-backup
secrets analysis (netragard.com).
