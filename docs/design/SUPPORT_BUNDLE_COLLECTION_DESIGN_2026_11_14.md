# Support bundle collection (vendor TAC files) — design

## Status

**FROZEN - 2026-11-15** for the scope and decisions of `PO_DECISION_RECORD_2026_11_15_SUPPORT_BUNDLE_COLLECTION.md`
(the Product Owner's answers to Q1-Q12 and the approval of the section 6 command literals for S2-S6, except the ASA
fallback A5-A7, which is not approved). Where this document and that record disagree, the record wins; the places it
overrides are marked "PO 2026-11-15" below. Written 2026-11-14 from the Product Owner's request of the same day;
completed 2026-11-15 with exact bundle contents, part-by-part fail-closed masking, size limits, retention mechanics,
RBAC descriptors, audit ledger rules, CLI parity, and a step-by-step shippable build plan. The vendor upload (S8) is
not frozen: it needs its own research. Every `UNKNOWN` below stays `UNKNOWN` until a vendor page or a PO-attended lab
or pilot measurement settles it.

**PO 2026-11-15 overrides, in one place.**

- Q1: any time the admin picks (deferred admin manual job), business-hours confirm, collision hints.
- Q2: a bundle is deleted (after a confirm dialog) once downloaded; a bundle never downloaded is deleted after 14 days,
  with a warning first. This replaces the "extend once up to 60 days" mechanics of section 5.6 for the build.
- Q3: gate `cp_sb_cpdata_collector_present` (`command -v cpdata_collector`, read, 30 s, once per run) is approved;
  the `--list` and `--run` literals were approved later the same day as gate rows only (`cp_sb_cpdata_collector_list`, `cp_sb_cpdata_collector_run`). Catalogue item
  `cpdata_collector_present` records the result.
- Q5: MDS cpinfo and HCP-run are included, with a clear UI warning, no block.
- Q6: request and download are `security_admin` only.
- Q9: no four-eyes on upload; the same administrator may request, download and upload.
- Q10: each HA member over its own management address. Q11: no FXOS. Q12: no `terminal pager 0` in the ASA session,
  plus a backlog item for the inventory session.

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
| Check Point shell normalization (no login shell, `BASH_ENV`/`ENV` cleared) | `MdsExportPlan.CP_ENV` (shipped code; the draft shell normalization contract is not authority) | Mandatory prefix for every CP command here. The 2026-11-11 MDS fork storm came from a login shell under `mds_backup` |
| Free-space precheck | `df -P /var/log` + `BackupCapabilityExecutor.parseDfAvailableBytes` | Same parser |
| Bundle → digest → SFTP stream into the vault → compare → remove by exact name (BK-3) | `MdsExportExecutor.run`, `ArtefactStore.open(...).sink()`, `DeviceTransport.fetchStreaming` | Same, unchanged |
| PAN XML API with streamed export into the vault | `PaloAltoBackupExecutor` (`type=export&category=device-state` streaming), `PanXmlApiTransport`, keygen per run | Same transport. Adds the async job (start / status / get) |
| FortiGate interactive shell, `config global`, `--More--` answered in session (no console config change) | `FortiGatePlan`, `FortiGateExecutor` (V80 gate rows) | Same session handling. Output streamed to the vault, not to memory or transcript |
| ASA privileged session, file on `disk0:`, SCP pull, delete by name | `CiscoAsaPlan.backupArchive / scpPath / deleteArchive`, the ASA team's SCP enablement | Session capture first. The disk0 + SCP form is a fallback only |
| Device's own HCP report already copied daily | System-log phase B `cp_hcp_report` (`CpSystemLogCollectJobExecutor`, newest `*.tar.gz` in `/var/log/hcp/last/`, 06:00 window, 7 days, ≤ 4.73 MiB measured on 52 gateways) | Artefact `cp_hcp_latest` can copy the already-stored file. **No device contact** |
| Browser download of a large artefact: role, reason ≥ 8 chars, single-use 60 s ticket, audit row before the first byte | `BackupController.downloadTicket / download`, `BackupDownloadService`, `JooqBackupArtefactRetrievalRepository`, PO record 2026-09-22 | Same two-step route under its own action (`support_bundle_retrieve`) and role (`security_admin`) |
| Admin manual ops bypass windows; business-hours confirmation | `ManualAdmission`, `JobWindowPolicy.requireDeviceJobOpen`, `jobs.window_bypass_role`, PO record 2026-10-20 | "Run now" uses it as is. "Run at a time" needs Q1 |
| Deferred claim | `jobs.admission_not_before` (V128, used by endpoint admission deferrals) | The scheduled start time and "device busy, retry in 10 min" |
| Expiring artefacts with a janitor | `ArtefactClass.REPORT` + `expires_at` + `RetentionPruningService` / `JooqRetentionQueries` (V190) | New class `ArtefactClass.SUPPORT_BUNDLE = "support_bundle"` with `expires_at` |
| Write-time secret scrubbing on text captures | `LogRedactor` (`cp-text-v1`, `SecretRules`), `PanLogRedactor` (`pan-config-v2`), `DiagnosticText.scrubSecrets` | Every text-based support-bundle item (`cp_gaia_extras`, `asa_crashinfo`, `asa_show_tech`, `fgt_crashlog`, `fgt_tac_report`) is scrubbed line-by-line before encryption |
| Read-time / export-time identity masking & filename masking | `LogMasking` (`logmask-v8`, `LogMasking.Session.maskFileName`, `LogMasking.Session.maskLine`), `FreeTextIdentityRules`, `Sanitizer` / `TextSanitizer` | HCP filenames masked on ingest (`hcp_report_FW-TANGO-04_...tar.gz`); optional `?mode=sanitized` text export reuses `LogMasking.Session` and fails closed on `MaskingUnavailableException` |
| AIView HTTP response masking funnel | `PrivacyMaskingResponseBodyAdvice`, `TopologyNamePseudonymizer`, `SubnetPreservingIpMasker`, `FreeTextEstate`, `AiviewMaskingLeakAuditTest`, `AiviewDiagnosticReadOnlyTest` | Every `support_bundle_read` route returns `Map<String, Object>` through the funnel, omits `requested_by`, `approved_by`, `reason`, and `case_ref` for `aiview`, and fails closed (`FreeTextEstate.UNAVAILABLE`) if cold |
| Application log IP scrubbing & audit column redaction | `MaskingFormatter` / `LogAddressMasker` (`ui2/platform-core/logging/`), `AuditRedactionPolicy` (`audit_redaction_policy` table) | All worker/service logs scrub IPs to `<ip>`; `reason`, `case_ref`, and `outcome_detail` registered in `audit_redaction_policy` |
| Session CLI parity | `NexusSessionCli`, `SanitizerCli`, `CutoverCli`, `FailoverCli` (`ui2/cli`) | New `SupportBundleCli` wired into `NexusSessionCli` using the same session cookie, CSRF header, `--business-hours-ack` flag, and streamed downloads |
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

### 5.1 Objects and database schema

- **Support bundle request (`support_bundle_request`).** One device, a list of artefact kinds, a run mode (`NOW` or
  `AT`), an optional vendor case reference (free text up to 40 characters, `^[A-Za-z0-9_-]{1,40}$`; used only as a
  label for `security_admin` and for a later upload), a mandatory operator justification (`reason` of 8..500
  characters), the requesting actor fingerprint, timestamps, expiry, and single-extension state. Requests for several
  devices create one independent request row per device.
- **Item (`support_bundle_item`).** One artefact kind (and optional VSX `vs_id` or `domain_ref`) inside a request, with
  its own execution state, outcome code, masked/safe outcome detail, foreign key to `backup_artefact(artefact_id)`,
  archive entry filename, plaintext size, SHA-256 digest, redaction ruleset tag, and gate IDs used.
- **Job (`jobs`).** One `jobs` row per request: job type `support_bundle_collect`, capability per vendor
  (`cp_support_bundle`, `pan_support_bundle`, `fgt_support_bundle`, `asa_support_bundle`). Items run **sequentially
  in one session**, cheapest first. A failed item does not stop the next, except a failed precheck or lost session,
  which stops the remaining items that depend on it.

Exact DDL shape (numbered at implementation time after the active migration reservation queue in `CURRENT_STATE.md`
and dry-run on the live DB in `BEGIN/ROLLBACK` before deploy):

```sql
CREATE TABLE IF NOT EXISTS support_bundle_request (
    id                  UUID        PRIMARY KEY,
    device_id           TEXT        NOT NULL,
    vendor              TEXT        NOT NULL CHECK (vendor IN ('check_point', 'palo_alto', 'fortinet', 'cisco_asa')),
    platform_role       TEXT        NOT NULL,
    run_mode            TEXT        NOT NULL CHECK (run_mode IN ('NOW', 'AT')),
    run_at              TIMESTAMPTZ NOT NULL,
    case_ref            TEXT        CHECK (case_ref IS NULL OR case_ref ~ '^[A-Za-z0-9_-]{1,40}$'),
    reason              TEXT        NOT NULL CHECK (char_length(btrim(reason)) BETWEEN 8 AND 500),
    requested_by        TEXT        NOT NULL,
    requested_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    started_at          TIMESTAMPTZ,
    finished_at         TIMESTAMPTZ,
    expires_at          TIMESTAMPTZ NOT NULL,
    extended_once       BOOLEAN     NOT NULL DEFAULT FALSE,
    extended_by         TEXT,
    extended_reason     TEXT        CHECK (extended_reason IS NULL OR char_length(btrim(extended_reason)) BETWEEN 8 AND 500),
    deleted_by          TEXT,
    deleted_at          TIMESTAMPTZ,
    deleted_reason      TEXT        CHECK (deleted_reason IS NULL OR char_length(btrim(deleted_reason)) BETWEEN 8 AND 500),
    job_id              TEXT        REFERENCES jobs(job_id),
    work_dir_path       TEXT        CHECK (work_dir_path IS NULL OR work_dir_path ~ '^/var/log/nexus-sb-[a-z0-9]{12}$'),
    work_dir_cleaned    BOOLEAN     NOT NULL DEFAULT TRUE,
    precheck_json       JSONB       NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(precheck_json) = 'object'),
    state               TEXT        NOT NULL CHECK (state IN (
                            'QUEUED', 'RUNNING', 'COMPLETED', 'PARTIAL', 'FAILED', 'CANCELLED', 'EXPIRED', 'DELETED')),
    outcome             TEXT        CHECK (outcome IS NULL OR outcome IN (
                            'COMPLETED', 'PARTIAL', 'FAILED', 'CANCELLED', 'NOT_STARTED_DEVICE_BUSY'))
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_support_bundle_active_per_device
    ON support_bundle_request (device_id)
    WHERE state IN ('QUEUED', 'RUNNING');

CREATE INDEX IF NOT EXISTS idx_support_bundle_request_device_time
    ON support_bundle_request (device_id, requested_at DESC);

CREATE INDEX IF NOT EXISTS idx_support_bundle_request_expiry
    ON support_bundle_request (expires_at)
    WHERE state IN ('COMPLETED', 'PARTIAL');

CREATE TABLE IF NOT EXISTS support_bundle_item (
    id                  UUID        PRIMARY KEY,
    request_id          UUID        NOT NULL REFERENCES support_bundle_request(id) ON DELETE CASCADE,
    ordinal             INT         NOT NULL CHECK (ordinal BETWEEN 1 AND 32),
    kind                TEXT        NOT NULL CHECK (kind IN (
                            'cp_hcp_latest', 'cp_gaia_extras', 'cp_hcp_run', 'cp_cpinfo',
                            'pan_tsf', 'asa_crashinfo', 'asa_show_tech', 'fgt_crashlog', 'fgt_tac_report')),
    vs_id               INT         CHECK (vs_id IS NULL OR vs_id BETWEEN 0 AND 4095),
    domain_ref          TEXT,
    state               TEXT        NOT NULL CHECK (state IN (
                            'QUEUED', 'RUNNING', 'COMPLETED', 'PARTIAL', 'REFUSED_PRECHECK',
                            'UNSUPPORTED', 'TIMED_OUT', 'TOO_LARGE', 'DIGEST_MISMATCH',
                            'OUTCOME_UNKNOWN', 'CLEANUP_FAILED', 'REDACTION_FAILED', 'CANCELLED')),
    outcome_code        TEXT        CHECK (outcome_code IS NULL OR outcome_code ~ '^[A-Z0-9_]{1,64}$'),
    outcome_detail      TEXT        CHECK (outcome_detail IS NULL OR char_length(outcome_detail) <= 500),
    archive_entry_name  TEXT        CHECK (archive_entry_name IS NULL OR archive_entry_name ~ '^[A-Za-z0-9_./-]{1,160}$'),
    artefact_id         TEXT        REFERENCES backup_artefact(artefact_id),
    size_bytes          BIGINT      CHECK (size_bytes IS NULL OR size_bytes >= 0),
    sha256              TEXT        CHECK (sha256 IS NULL OR sha256 ~ '^[0-9a-f]{64}$'),
    redaction_ruleset   TEXT        NOT NULL DEFAULT 'none',
    gate_ids_used       TEXT[]      NOT NULL DEFAULT '{}',
    started_at          TIMESTAMPTZ,
    finished_at         TIMESTAMPTZ,
    UNIQUE (request_id, kind, COALESCE(vs_id, -1), COALESCE(domain_ref, ''))
);

CREATE INDEX IF NOT EXISTS idx_support_bundle_item_request
    ON support_bundle_item (request_id, ordinal);
```

Phase S8 adds `support_bundle_upload` (`id`, `request_id`, `item_ids UUID[]`, `destination_kind` in
`('cisco_cxd', 'fortinet_sftp')`, `destination_host`, `case_ref`, `reason`, `requested_by`, `requested_at`,
`approved_by`, `approved_at`, `approval_reason`, `expires_at`, `state`, `bytes_sent`, `remote_status`, `finished_at`,
with `CHECK (approved_by IS NULL OR approved_by <> requested_by)` enforcing four-eyes in the database).

`backup_artefact.artefact_class` check constraint (`V190` pattern) gains `'support_bundle'`, and a partial index
`idx_backup_artefact_support_bundle_expiry ON backup_artefact (expires_at) WHERE artefact_class = 'support_bundle'`
accelerates hourly janitor pruning.

### 5.2 Artefact catalogue (closed vocabulary; the browser sends kinds, never commands)

| Kind | Vendor / role | Execution order | Device load (expected) | Default | Step |
|---|---|---|---|---|---|
| `cp_gaia_extras` | CP all | 1 (cheapest) | five short reads (two shared with precheck) | ticked | S2 |
| `cp_hcp_latest` | CP gateway, VSX host | 2 | **none** if today's system-log copy is in the vault; otherwise one `ls` + one SFTP get | ticked | S2 |
| `cp_hcp_run` | CP gateway; VSX `-c <VSID>`; MDS `--domain` off | 3 | medium (vendor tests; impact level per test) | unticked | S6 |
| `cp_cpinfo` | CP gateway; VSX per VS; SMS with `-x`; **MDS off by default** | 4 (heaviest) | **high** (vendor: no core above 70%) | unticked | S6 |
| `cp_cpsdc` | CP, only if Q3 says yes | — | `UNKNOWN` | hidden | — |
| `pan_tsf` | PAN firewall, Panorama | 1 | management plane, `UNKNOWN` | ticked | S3 |
| `asa_crashinfo` | ASA | 1 | low | ticked | S4 |
| `asa_show_tech` | ASA (system context in multi-context) | 2 | `UNKNOWN`. Cisco's pager warning | ticked | S4 |
| `fgt_crashlog` | FortiGate | 1 | low | ticked | S5 |
| `fgt_tac_report` | FortiGate | 2 | `UNKNOWN`. Refused on versions with the known defect when the stored configuration has a DoS policy (§5.8) | ticked | S5 |

`cp_cpinfo` options are fixed, not chosen per request:

- **Always:** `-z -D -d -S`. Compressed, no upload, no self-update check, password hashes censored.
- **Also `-x` on SMS.**
- **Never** `-l` (traffic log) or `-k` (kernel tables). If TAC asks for them, they are a separate kind and a separate
  approval (Q4).

### 5.3 Exact contents of a bundle and `manifest.json` specification

A support bundle is downloaded as a single streamed gzipped tar archive (`.tgz`) assembled on the fly by
`SupportBundleDownloadService` from the request's completed item artefacts in `ArtefactStore` plus a freshly rendered
`manifest.json`. The outer archive file name in `Content-Disposition` follows `BackupController.download`:

```text
nexus-support-bundle-<vendor>-<requestId.substring(0, 8)>-<yyyyMMdd-HHmm>.tgz
```

It never contains a raw device hostname, management IP, serial number, or case reference.

#### 5.3.1 Outer `.tgz` archive directory layout

```text
nexus-support-bundle-<requestId-8>/
├── manifest.json
└── items/
    ├── cp_gaia_extras/
    │   ├── show-version-all.txt
    │   ├── show-asset-all.txt
    │   ├── cphaprob-stat.txt
    │   ├── multi-cpu.txt
    │   └── df-var-log.txt
    ├── cp_hcp_latest/
    │   └── hcp_report_<device-pseudonym>_<dd>_<mm>_<yy>_<HH>_<MM>.tar.gz
    ├── cp_hcp_run/
    │   ├── hcp_report_<device-pseudonym>_<dd>_<mm>_<yy>_<HH>_<MM>.tar.gz
    │   └── vs-<vsid>/hcp_report_<device-pseudonym>_<dd>_<mm>_<yy>_<HH>_<MM>.tar.gz
    ├── cp_cpinfo/
    │   ├── cpinfo.info.gz
    │   └── vs-<vsid>/cpinfo.info.gz
    ├── pan_tsf/
    │   └── techsupport.tgz
    ├── asa_crashinfo/
    │   └── show-crashinfo.txt
    ├── asa_show_tech/
    │   └── show-tech-support.txt
    ├── fgt_crashlog/
    │   └── diagnose-debug-crashlog-read.txt
    └── fgt_tac_report/
        └── execute-tac-report.txt
```

Only items requested in that run and reaching `COMPLETED` or `PARTIAL` appear under `items/`; skipped, refused, or
failed items are recorded in `manifest.json` with their `state` and `outcome_code` so the engineer has a complete record
of why an item is absent.

#### 5.3.2 Exact per-item contents and storage format

| Kind | Vault storage format (`backup_artefact`) | Inner files / structure | Write-time processing |
|---|---|---|---|
| `cp_gaia_extras` | One `.tar.gz` artefact (`ArtefactClass.SUPPORT_BUNDLE`) containing 5 UTF-8 text files | `show-version-all.txt` (`clish -c 'show version all'`), `show-asset-all.txt` (`clish -c 'show asset all'`), `cphaprob-stat.txt` (`cphaprob stat`), `multi-cpu.txt` (`cpstat os -f multi_cpu` from precheck), `df-var-log.txt` (`df -P /var/log` from precheck) | Each text file passes through `LogRedactor` (`cp-text-v1`) + `DiagnosticText.scrubSecrets` before entering the tar stream |
| `cp_hcp_latest` | One `.tar.gz` artefact (either cloned inside the vault from a `cp_hcp_report` system-log artefact $\le 24\text{ h}$ old or SFTP-streamed from `/var/log/hcp/last/`) | Vendor HCP archive (`hcp_report_<pseudonym>_<dd>_<mm>_<yy>_<HH>_<MM>.tar.gz`) containing Check Point HTML/JSON/text health report | Entry filename masked via `LogMasking.Session.maskFileName`; binary `.tar.gz` bytes verified for gzip magic (`0x1f 0x8b`) and kept intact for vendor tools |
| `cp_hcp_run` | One `.tar.gz` artefact per run (or per VS when `-c <VSID>` is used) | Freshly generated vendor HCP archive copied from `/var/log/hcp/last/`. Note: `hcp.log` and `hcp.rc` in `/var/log/nexus-sb-<token>` are control files only and are never archived | Entry filename masked via `LogMasking.Session.maskFileName`; gzip magic verified |
| `cp_cpinfo` | One `.info.gz` artefact per run (or per VS under `vs-<vsid>/cpinfo.info.gz`) | Output of `cpinfo -z -D -d -S -o %s/cpinfo` (`-x` added on SMS). `cpinfo.log` and `cpinfo.rc` are control files only and are never archived | Check Point `-S` censors password hashes at generation; gzip magic and device-side `sha256sum` verified against vault stream digest (BK-3) |
| `pan_tsf` | One `.tgz` artefact | Output of PAN-OS XML API `type=export&category=tech-support&action=get&job-id=<n>` (`techsupport.tgz`) | Verified for gzip magic (`0x1f 0x8b`), rejecting XML `<response status="error">` bodies (`PaloAltoBackupExecutor` rule) |
| `asa_crashinfo` | One `.txt.gz` artefact | UTF-8 session capture of `show crashinfo`, decompressed into `items/asa_crashinfo/show-crashinfo.txt` (or kept `.txt` in the outer `.tgz`) | Streamed line-by-line through `LogRedactor` (`cp-text-v1`) + `DiagnosticText.scrubSecrets` before vault encryption |
| `asa_show_tech` | One `.txt.gz` artefact | UTF-8 session capture of `show tech-support` (from `system` context on multi-context ASA, answering `<--- More --->` in session) | Streamed line-by-line through `LogRedactor` (`cp-text-v1`) + `DiagnosticText.scrubSecrets` before vault encryption |
| `fgt_crashlog` | One `.txt.gz` artefact | UTF-8 session capture of `diagnose debug crashlog read` (`config global` on multi-VDOM) | Streamed line-by-line through `LogRedactor` (`cp-text-v1`) + `DiagnosticText.scrubSecrets` before vault encryption |
| `fgt_tac_report` | One `.txt.gz` artefact | UTF-8 session capture of `execute tac report` (`config global` on multi-VDOM, answering `--More--` in session) | Streamed line-by-line through `LogRedactor` (`cp-text-v1`) + `DiagnosticText.scrubSecrets` (including FortiGate `ENC <blob>` scrubbing) before vault encryption |

#### 5.3.3 Exact `manifest.json` schema

`manifest.json` is written as the first entry of the outer `.tgz`. It contains only opaque identifiers, deterministic
AIView pseudonyms, timestamps, precheck metrics, and per-item outcomes and digests — **never** a raw device hostname,
management IP, serial number, `requested_by`, `reason`, or `case_ref`:

```json
{
  "schema_version": 1,
  "nexus_version": "2.0.0",
  "download_mode": "raw",
  "request_id": "d8f19c2a-4b10-4e8e-9c1a-7b3e2f109a44",
  "job_id": "a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d",
  "device_id": "3f9a1c02-7d44-4b88-91e0-5c2d1a8f0b11",
  "device_pseudonym": "FW-TANGO-04",
  "vendor": "check_point",
  "platform_role": "cp_gaia_gateway",
  "requested_at": "2026-11-15T01:00:00Z",
  "started_at": "2026-11-15T01:00:04Z",
  "finished_at": "2026-11-15T01:02:19Z",
  "expires_at": "2026-11-29T01:02:19Z",
  "state": "COMPLETED",
  "outcome": "COMPLETED",
  "prechecks": {
    "cpu_max_core_pct": 32,
    "disk_free_bytes": 12884901888,
    "version_guard": "PASSED"
  },
  "items": [
    {
      "ordinal": 1,
      "kind": "cp_gaia_extras",
      "vs_id": null,
      "state": "COMPLETED",
      "outcome_code": "COMPLETED",
      "archive_entry_name": "items/cp_gaia_extras/",
      "artefact_id": "9f8e7d6c-5b4a-4321-8765-1029384756ab",
      "size_bytes": 18432,
      "sha256": "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
      "redaction_ruleset": "cp-text-v1+diagnostic-secrets-v1",
      "gate_ids_used": [
        "cp_sb_show_version_all",
        "cp_sb_show_asset_all",
        "cp_sb_cphaprob_stat",
        "cp_sb_cpu_multi",
        "cp_sb_df_var_log"
      ],
      "started_at": "2026-11-15T01:00:05Z",
      "finished_at": "2026-11-15T01:00:14Z"
    }
  ]
}
```

### 5.4 Masking applied to each part (reusing existing maskers, fail-closed)

Every surface of the support-bundle subsystem MUST reuse the existing repository maskers without inventing parallel
regexes or fallback pass-throughs, and every masker MUST fail closed when its dictionary or key state is unavailable:

| Surface / Part | Existing Masker Reused (Exact Class & Method) | What is Masked / Scrubbed | Fail-Closed Behaviour |
|---|---|---|---|
| **1. Text-based items at write time** (`cp_gaia_extras`, `asa_crashinfo`, `asa_show_tech`, `fgt_crashlog`, `fgt_tac_report`) | `LogRedactor.redact(line, result)` (`ui2/worker/systemlog/redaction/LogRedactor.java`, ruleset `cp-text-v1` + `SecretRules`) chained with `DiagnosticText.scrubSecrets(line)` (`ui2/platform-core/DiagnosticText.java`) | Passwords, SNMP communities/USM keys, RADIUS/TACACS/LDAP shared secrets, WPA/IKE PSKs, CLI `-p`/`-w` flags, auth blobs, `$1$/$5$/$6$/$2a$` crypt hashes, 32/40/64-hex keys, PEM `PRIVATE KEY` blocks, FortiGate `ENC <blob>` secrets, and secret-keyword lines $\rightarrow$ `<redacted>` / `[SECRET REDACTED]` | Line buffer is bounded to 64 KiB; a line exceeding 64 KiB without `\n` is replaced wholesale with `[LINE_REDACTED_OVERLONG]`. Any `RuntimeException` inside the redactor aborts the item stream (`state = 'REDACTION_FAILED'`), closes/deletes the partial vault sink without committing a `backup_artefact` row, and persists **zero** unredacted bytes |
| **2. Text-based items in optional sanitized download mode** (`?mode=sanitized`, CLI `--sanitized`) | `LogMasking.openSession()` $\rightarrow$ `LogMasking.Session.maskLine(line, rawDeviceHostname)` (`ui2/service/systemlog/LogMasking.java`, ruleset `logmask-v8`: `SerialRule`, `IdentityFields`, `TextSanitizer` with `TokenSink.DISCARD`, `EstateDictionary`, `EstateNameCatalog`, `FreeTextIdentityRules`) | Replaces device/cluster/VS/domain/policy/rule/object/zone names (`FW-TANGO-04`, `CLS-ROMEO-01`, `POL-`, `RUL-`, `OBJ-`, `ZON-`), serials (`SN-`), principals (`USR-`), IPv4/IPv6 addresses (`SubnetPreservingIpMasker` / `TextSanitizer`), FQDNs, home paths, and hex/dash IPs | If `LogMasking.openSession()` cannot build the `EstateDictionary` and has no cached snapshot, it throws `LogMasking.MaskingUnavailableException`; `SupportBundleDownloadService` catches it before opening the response stream and returns HTTP `503 Service Unavailable` (`MASKING_UNAVAILABLE`) with zero bytes sent. Binary items (`cp_cpinfo`, `cp_hcp_*`, `pan_tsf`) are omitted in `sanitized` mode (`"omitted_in_sanitized_mode": true` in `manifest.json`) because byte-level rewriting corrupts compressed archives |
| **3. Binary / vendor-archive items** (`cp_hcp_latest`, `cp_hcp_run`, `cp_cpinfo`, `pan_tsf`) | Check Point native `-S` flag on `cpinfo` (censors password hashes); `LogMasking.Session.maskFileName(rawName, rawDeviceHostname)` on HCP archive filenames; AES-256-GCM envelope encryption in `ArtefactStore` | HCP filename `hcp_report_<rawHost>_15_11_26_03_00.tar.gz` becomes `hcp_report_FW-TANGO-04_15_11_26_03_00.tar.gz`. Binary archive payload is kept intact for TAC tools in default (`mode=raw`) downloads | If `LogMasking.openSession()` throws `MaskingUnavailableException` during worker ingest, the worker falls back to the fixed literal filename `hcp_report.tar.gz` (never retaining the raw hostname in `archive_entry_name`). Binary items are strictly unreachable by `aiview` (`role:replay_viewer`) and omitted in `mode=sanitized` |
| **4. `manifest.json` and outer `.tgz` filename** | `TopologyNamePseudonymizer.pseudonymizeDevice(rawHostname)` (`ui2/service/privacy/TopologyNamePseudonymizer.java`) | `device_pseudonym` holds `FW-TANGO-04`; outer filename uses `<vendor>` and `<requestId.substring(0, 8)>`. Raw hostname, management IP, serial, `requested_by`, `reason`, and `case_ref` are excluded from `manifest.json` | If `rawHostname` is null/blank or `TopologyNamePseudonymizer` fails, `device_pseudonym` is written as `"UNKNOWN"` (never falling back to a raw hostname or address) |
| **5. HTTP API JSON responses (`support_bundle_read`) & AIView (`role:replay_viewer`)** | `PrivacyMaskingResponseBodyAdvice` (`ui2/service/privacy/PrivacyMaskingResponseBodyAdvice.java`), `TopologyNamePseudonymizer`, `SubnetPreservingIpMasker`, `FreeTextEstate` (`ui2/service/privacy/FreeTextEstate.java`) | All read routes return `ResponseEntity<Map<String, Object>>` (an approved `FUNNEL_TYPES` in `AiviewMaskingLeakAuditTest`). Keys `"hostname"`, `"reason"`, and `"detail"` (for `outcome_detail`) are automatically rewritten by the advice for `aiview`. In addition, when `GateChainInterceptor.IS_REPLAY_VIEWER_ATTRIBUTE` is `true`, `SupportBundleController` explicitly **omits** `requested_by`, `extended_by`, `deleted_by`, `approved_by`, `reason`, `extended_reason`, `deleted_reason`, and `case_ref` from the response map | On a cold pod where `TopologyNamePseudonymizer`'s `deviceDirectory` or `FreeTextEstate`'s `EstateDictionary` has not loaded, `TopologyNamePseudonymizer.maskText` returns `FreeTextEstate.UNAVAILABLE` (`"<masked: estate dictionary unavailable>"`), never raw prose. `AiviewMaskingLeakAuditTest` fails the build if any unreviewed identity key or unmasked return type is introduced |
| **6. Precheck & failure `outcome_detail` strings** | Safe structured templates at construction + `TopologyNamePseudonymizer.maskText` / `SubnetPreservingIpMasker.maskFreeText` via `PrivacyMaskingResponseBodyAdvice` | `outcome_detail` is generated exclusively from numeric measurements and closed enums (e.g., `"CPU core 2 at 78% exceeds 70% limit"`, `"/var/log free 1420 MiB below 2048 MiB limit"`, `"FortiOS 7.4.4 with DoS-policy refused (VENDOR_DEFECT_SESSION_CRASH)"`), never echoing raw device CLI banners or paths | If an unexpected transport exception message is caught, only the exception class name and a fixed code (`TRANSPORT_IO_ERROR`, `SSH_EOF`) are stored in `outcome_detail` — raw exception messages (`e.getMessage()`) are never stored in `support_bundle_item.outcome_detail` |
| **7. Job logs, transcripts, and diagnostic previews** | `PrivacyMaskingResponseBodyAdvice` (`JobEvent` branch), `DiagnosticText.masked` (256 KiB cap, `/diagnostic-safe-words.txt` allowlist), `PayloadMasker` (`ui2/packet-analysis/privacy/PayloadMasker.java`), `MaskingFormatter` / `LogAddressMasker` (`ui2/platform-core/logging/`) | Session captures are streamed directly to `ArtefactStore` and **never** written to `job_transcript`. Transcript rows hold only gate IDs, byte counts, durations, and `rc`. Service/worker logs scrub IPv4/IPv6 to `<ip>` via `LogAddressMasker` | `DiagnosticText.masked` hashes every token not in `/diagnostic-safe-words.txt`; `PayloadMasker` fails closed to `TXT-<10hex>` for any unclassified key |
| **8. Audit log (`audit_log`)** | `AuditRedactionPolicy` (`ui2/service/audit/AuditRedactionPolicy.java`) + DB `fn_audit_redact` reading `audit_redaction_policy` | `support_bundle_request.reason`, `case_ref`, `extended_reason`, `deleted_reason`, `support_bundle_item.outcome_detail`, and `support_bundle_upload.case_ref` / `reason` are registered in `audit_redaction_policy` (tier 1) | `fn_audit_capture` raises an exception and aborts the transaction if `app.actor_fingerprint` or `app.action_id` is unset |

### 5.5 Size limits, buffer bounds, and quota rules

All byte limits are enforced during streaming via a bounded stream wrapper (`BoundedInputStream` /
`BoundedOutputStream`) **before** bytes are written to `ArtefactStore`. Exceeding any item or request limit immediately
aborts the stream, closes and discards the partial vault file without inserting a `backup_artefact` row, removes any
temporary file on the device (`C20` / `P6`), and records `state = 'TOO_LARGE'`.

| Limit Scope | Target | Exact Limit | Enforcement & Rationale |
|---|---|---|---|
| **Per-item byte limit** | `cp_gaia_extras` | **1 MiB** (`1,048,576` B) total; **256 KiB** (`262,144` B, `DiagnosticText.MAX_BYTES`) per command | 5 short CLI text outputs (`show version all`, `show asset all`, `cphaprob stat`, `multi_cpu`, `df -P /var/log`) |
| **Per-item byte limit** | `cp_hcp_latest` | **16 MiB** (`16,777,216` B) | Matches `CpSystemLogCollectJobExecutor.HCP_MAX_BYTES`; fleet max across 52 gateways is $4.73\text{ MiB}$ |
| **Per-item byte limit** | `cp_hcp_run` | **16 MiB** (`16,777,216` B) per gateway / VS | Same as `cp_hcp_latest` |
| **Per-item byte limit** | `cp_cpinfo` | **20 GiB** (`21,474,836,480` B) per `.info.gz` | Check Point SR upload limit is 10 GB (`sk92526`); 20 GiB is $2\times$ vendor upload cap |
| **Per-item byte limit** | `pan_tsf` | **2 GiB** (`2,147,483,648` B) | Typical firewall TSF is 20–200 MiB; Panorama/large firewalls up to ~1.5 GiB |
| **Per-item byte limit** | `asa_crashinfo` | **64 MiB** (`67,108,864` B) | Latest flash crashinfo file |
| **Per-item byte limit** | `asa_show_tech` | **256 MiB** (`268,435,456` B) | Session capture of `show tech-support` |
| **Per-item byte limit** | `fgt_crashlog` | **32 MiB** (`33,554,432` B) | Ring buffer of at most 16,384 lines |
| **Per-item byte limit** | `fgt_tac_report` | **256 MiB** (`268,435,456` B) | Session capture of `execute tac report` |
| **Per-request aggregate cap** | All items in one `support_bundle_request` | **25 GiB** (`26,843,545,600` B) plaintext | Bounds a multi-item or multi-VS Check Point request |
| **In-memory stream buffer** | Worker SFTP / HTTP / SSH stream copy & download `.tgz` assembly | **64 KiB** (`65,536` B) chunk buffer; **64 KiB** max line buffer for text redaction | Zero whole-file heap buffering (`byte[]` prohibited); constant heap footprint regardless of bundle size |
| **Control log tail read** | `cpinfo.log` / `hcp.log` on non-zero `.rc` | **8 KiB** (`8,192` B) tail | Used only to classify failure code (`UNSUPPORTED`, `DISK_FULL`); never stored raw |
| **Per-device request count cap** | Live (`COMPLETED` / `PARTIAL`, non-expired, non-deleted) requests per `device_id` | **3 requests** | Refused at submission with HTTP `409 Conflict` (`DEVICE_BUNDLE_QUOTA_EXCEEDED`), naming the oldest request ID and its `expires_at` |
| **Fleet storage quota** | Sum of live `backup_artefact.ciphertext_bytes` where `artefact_class = 'support_bundle'` and `NOT_REMOVED` | **50 GiB** (`53,687,091,200` B, configurable) | Excluded from `JooqRetentionQueries.liveCiphertextBytes()` (just like `system_log`) so support bundles never consume the 400 GiB backup/configuration budget (`DEFAULT_STORAGE_BUDGET_BYTES`). Refused at submission (`FLEET_BUNDLE_QUOTA_EXCEEDED`) when exceeded |
| **Store volume free-space floor** | `ArtefactStore` filesystem usable space | $\ge$ **10%** of total volume and $\ge$ **5 GiB** free (`SystemLogArtefactVolume.MIN_FREE_FRACTION` rule) | Checked at admission and before each item write; fails closed with `STORE_VOLUME_LOW_SPACE` |
| **Device disk precheck** | Check Point `/var/log` (`df -P /var/log`); Palo Alto `/` and `/opt/panlogs` (`show system disk-space`) | CP `/var/log` $\ge$ **2 GiB** free for `cp_hcp_run`, $\ge$ **5 GiB** free for `cp_cpinfo`; PAN `/` and `/opt/panlogs` $\le$ **90%** used | Refuses heavy items (`REFUSED_PRECHECK`) while allowing no-disk items (`cp_gaia_extras`, vault-cached `cp_hcp_latest`) |

### 5.6 Retention, TTL extension, early deletion, and janitor integration

- **Artefact class.** `ArtefactClass.SUPPORT_BUNDLE = "support_bundle"` in
  `ui2/persistence/src/main/java/com/securityexpert/nexus/ui2/persistence/artefact/ArtefactClass.java`. Every stored
  item writes a `backup_artefact` row with `artefact_class = 'support_bundle'`, `retention_tier = 'support_bundle_14d'`
  (or `'support_bundle_extended'`), and `expires_at` matching the parent `support_bundle_request.expires_at`.
- **Default TTL (14 days).** At request completion, `expires_at = finished_at + INTERVAL '14 days'` (Q2).
- **Single extension up to 60 days.** A `security_admin` may call `POST /api/v2/support-bundles/{id}/extend`
  (`support_bundle_retention_set`) with `{"days": 1..60, "reason": "<>= 8 chars>"}` while the request is `COMPLETED` or
  `PARTIAL` and `extended_once = FALSE`. In one `AuditedTransactionBoundary`:
  1. `support_bundle_request.expires_at` is set to ` LEAST(requested_at + INTERVAL '60 days', now() + (days || ' days')::interval) `,
     `extended_once = TRUE`, `extended_by = actorFingerprint`, `extended_reason = reason`.
  2. All live `backup_artefact` rows linked via `support_bundle_item.artefact_id` have their `expires_at` and
     `retention_tier = 'support_bundle_extended'` updated to match.
  3. A second extension call on the same request is refused with HTTP `409 Conflict` (`ALREADY_EXTENDED`).
- **Early deletion by `security_admin`.** `POST /api/v2/support-bundles/{id}/delete` (`support_bundle_delete`) requires
  a `reason` of at least 8 characters. For each live item artefact:
  1. The encrypted file on the recovery volume is deleted via `RetentionStorePort.deleteArtefact` (`Files.deleteIfExists`).
  2. Only after the file is confirmed gone from disk, `JooqRetentionQueries.recordRemoved` inserts the `'removed'` row
     into `artefact_retention_ledger` under the acting `security_admin`'s fingerprint and `support_bundle_delete`
     action ID (preserving the truthful-tombstone invariant).
  3. `support_bundle_request` transitions to `state = 'DELETED'`, recording `deleted_by`, `deleted_at`, and
     `deleted_reason`.
- **Hourly retention janitor (`RetentionPruningService` + `JooqRetentionQueries`).**
  - `RetentionPruningService.RetentionStorePort` adds `default List<ArtefactRecord> findExpiredSupportBundles() { return List.of(); }`
    and `PruningSummary` adds `int supportBundlesPruned` (mirroring `findExpiredReports()` and `reportsPruned` in
    `V190`).
  - `JooqRetentionQueries.expiredSupportBundles()` selects `from backup_artefact a where a.artefact_class = 'support_bundle' and a.expires_at is not null and a.expires_at < now()` + `NOT_REMOVED` ordered by `a.expires_at, a.artefact_id`.
  - **No "keep last per device" exemption:** Unlike `backup` (`isSoleRemainingBackupForDevice`) and `configuration`
    (`isLatestConfigurationArchive`), support bundles are temporary case forensics and follow the `report` rule: once
    `expires_at < now()`, every expired support-bundle artefact is deleted from disk and tombstoned in
    `artefact_retention_ledger`, and its parent `support_bundle_request.state` is updated to `'EXPIRED'`.
- **Never parsed, never shown.** Bundles never feed inventory, compliance, the sanitizer, aiview or any report. This
  design is the forensics contract that the raw-evidence law requires before raw device output may be retained.

### 5.7 Scheduling and job windows

- **Run now.** This is an administrator's manual job (PO 2026-10-20): `security_admin` only, never held to a window,
  and inside business hours (08:00–20:00 `Europe/Istanbul`) the existing confirmation
  (`BUSINESS_HOURS_CONFIRM_REQUIRED`, `X-Nexus-Business-Hours-Ack: true` header) applies via
  `JobWindowPolicy.requireDeviceJobOpen`. The job carries `window_bypass_role = 'role:security_admin'`.
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

  It suggests 00:00 or 04:00. A collision is not refused. The busy-device guard (§5.8) defers the run instead.
- A scheduled request may be cancelled until its job is claimed. After the point of no return (§5.9) a cancel only
  stops further items. The running tool finishes and its work directory is cleaned up if its exit code arrives.

### 5.8 Device-load guards (all fail-closed; refusal reasons shown to the requester)

| Guard | Where | Rule |
|---|---|---|
| One bundle per device | DB, partial unique index `uq_support_bundle_active_per_device` on active support-bundle requests per `device_id` | A second request for a device with one already `QUEUED` or `RUNNING` is refused at submission (`SUPPORT_BUNDLE_ALREADY_ACTIVE`) |
| Not both HA members at once | claim | If a support-bundle job is `RUNNING` on another member of the same cluster or HA pair, defer by 10 minutes (`admission_not_before`). Requesting both members is allowed; they run one after the other. Membership comes from inventory (cluster id). Unknown membership is treated as "single", and the UI says so |
| Device busy | claim | A `RUNNING` backup, MDS export, configuration, readiness, or policy job on the same device defers by 10 minutes |
| Give up | claim | Not started within 3 hours after its scheduled `run_at`: request transitions to `FAILED` with outcome `NOT_STARTED_DEVICE_BUSY` |
| Fleet cap | claim | At most 2 support-bundle jobs running fleet-wide (setting); the rest defer by 5 minutes |
| CPU precheck | first commands of the run | CP: any core above 70% refuses `cp_cpinfo` and `cp_hcp_run` (**vendor rule**, sk92739), and the cheap items still run. PAN, FGT, ASA: management CPU above 80% (**local heuristic**, not a vendor figure) refuses `pan_tsf`, `fgt_tac_report` and `asa_show_tech` |
| Disk precheck | first commands | CP: free bytes in `/var/log` below 2 GiB refuses `cp_hcp_run` and below 5 GiB refuses `cp_cpinfo` (local heuristic, same `BackupCapabilityExecutor.parseDfAvailableBytes` parser as MDS export). PAN: `show system disk-space` with root or `/opt/panlogs` above 90% refuses `pan_tsf` (heuristic). ASA: no file is written in the default form. FGT: no file |
| Version guard | before connect, from stored data | FortiGate below 7.4.10 (7.4 train), below 7.6.6 (7.6 train), or on 7.0/7.2, **and** a stored configuration containing `config firewall DoS-policy`: `fgt_tac_report` is refused (`VENDOR_DEFECT_SESSION_CRASH`) and the crash log still runs. Uses the stored configuration; no device command |
| Size bound | fetch | Per kind, enforced via bounded stream (§5.5): `cp_gaia_extras` 1 MiB; `cp_hcp_*` 16 MiB; `cp_cpinfo` 20 GiB; `pan_tsf` 2 GiB; `asa_crashinfo` 64 MiB; `fgt_crashlog` 32 MiB; `asa_show_tech` and `fgt_tac_report` 256 MiB. Over the bound: `TOO_LARGE`, nothing stored, device file removed |
| Run deadline | worker | `cp_cpinfo` and `cp_hcp_run`: 2 h; `pan_tsf` job: 30 min; session captures: 20 min. On deadline: PAN `action=finish`; CP: `OUTCOME_UNKNOWN`, work directory left (the MDS export rule); FGT and ASA: session closed, item `TIMED_OUT` |

### 5.9 Execution per vendor

Every CP command carries the `MdsExportPlan.CP_ENV` prefix (`unset BASH_ENV ENV; . /etc/profile.d/CP.sh >/dev/null 2>&1; unset BASH_ENV ENV; `). The work directory is `/var/log/nexus-sb-<token>`, with the 12-char `<token>` derived from the job UUID (`MdsExportPlan.workDir` rule).

**Check Point (SSH, Expert):**

1. Version: `show version all` (existing gate).
2. Prechecks: `cpstat os -f multi_cpu` and `df -P /var/log`.
3. `cp_gaia_extras`: `show version all`, `show asset all`, `cphaprob stat`, plus the two precheck outputs, scrubbed
   line-by-line via `LogRedactor` + `DiagnosticText.scrubSecrets` and packaged into one `.tar.gz` artefact in the vault.
4. `cp_hcp_latest`:
   1. If the system-log store holds a `cp_hcp_report` for this device from the last 24 hours, copy it inside the
      vault. No device contact.
   2. Otherwise `ls -lgo --time-style=+%s /var/log/hcp/last/` and SFTP-get the newest `*.tar.gz` by its listed name
      (validated against `^[A-Za-z0-9_.-]{1,128}$`), masking its stored entry name via
      `LogMasking.Session.maskFileName`.
5. `mkdir -p /var/log/nexus-sb-<token>` (only when `cp_hcp_run` or `cp_cpinfo` is in the request). **Point of no return
   marked** (`work_dir_path` recorded, `work_dir_cleaned = FALSE`).
6. Heavy items, one at a time: start with `setsid nohup bash --noprofile --norc -c "...; echo \$? > <tool>.rc" </dev/null >/dev/null 2>&1 &`, poll the `.rc` file every 30 s up to the deadline, then `ls -l`.
   - HCP writes into `/var/log/hcp/last/`. neXus copies the newest archive by its listed name into the work
     directory. It **replaces the device's own "last" report**: that is what any manual `hcp -r` does, and the UI
     says so.
7. For each produced archive, compute `sha256sum` on the device, SFTP-stream into the `ArtefactStore` sink, compare
   device SHA-256 against the streamed vault SHA-256, and only on `MATCH` execute `rm -rf` of the exact work directory
   and mark `work_dir_cleaned = TRUE`.
   - A digest mismatch deletes nothing on the device (`DIGEST_MISMATCH`, BK-3).

**VSX:**

- `cp_cpinfo` runs once per selected VS: `vsenv <VSID> >/dev/null 2>&1 && cpinfo ...` in one command line. The VSID is
  validated as an integer ($0..4095$) against stored inventory for that gateway.
- `cp_hcp_run` runs from VS0 with `-c <VSID>`.

**MDS:** `cp_cpinfo` and `cp_hcp_run` are hidden until Q5 (fork-storm history).

**Palo Alto (XML API, one key per run):**

1. Prechecks: `show system resources` and `show system disk-space`.
2. Start `type=export&category=tech-support` and record the numeric `<job>` ID (`^[0-9]{1,12}$`).
3. Poll `action=status&job-id=<n>` every 15 s until `<status>FIN</status>`, up to 30 minutes.
4. Stream `action=get&job-id=<n>` into the vault through the existing streaming transport. A successful `get` deletes
   the job on the device. The SHA-256 is computed on receipt. There is no device-side digest, the same as device-state
   backups.
5. On deadline, send `action=finish&job-id=<n>`, then record `TIMED_OUT`.

The size bound and gzip check reuse `PaloAltoBackupExecutor`'s stream validation (`gzip` magic `0x1f 0x8b`, XML error
body → refusal).

**FortiGate (SSH interactive, existing session handling):**

1. `get system status`: confirm the version and HA mode against the version guard.
2. `get system performance status` as the CPU precheck.
3. `config global` (multi-VDOM only).
4. `diagnose debug crashlog read`, then `execute tac report`, then `end`.
5. Output is **streamed line-by-line through `LogRedactor` + `DiagnosticText.scrubSecrets` into the vault sink** as it
   arrives. It is never buffered whole in memory and never written to the job transcript, which records only lengths
   and timings.
6. `--More--` is answered in session (`FortiGatePlan`).
7. Completion: the prompt returns. A session that drops mid-report gives `PARTIAL` (with `outcome_code = 'SESSION_LOST'`)
   if at least 4 KiB was captured, or `FAILED` otherwise.

**Cisco ASA (SSH, privileged EXEC):**

1. Precheck `show cpu usage`.
2. Multi-context: `changeto system`.
3. `show crashinfo`, then `show tech-support`.
4. Output is streamed line-by-line through `LogRedactor` + `DiagnosticText.scrubSecrets` into the vault sink.

**This session does not send `terminal pager 0`**, because of Cisco's CPU warning. The `<--- More --->` prompt is
answered in session instead. That is a behaviour difference from the inventory session, which does send it. Whether
that inventory practice should change is a separate question (risk R7 / Q12).

**Fallback, behind its own approval:** `show tech-support file disk0:/nexus-sb-<hex>.txt`, SCP pull, then
`delete /noconfirm disk0:/nexus-sb-<hex>.txt`. It is used only if session capture proves unreliable in the pilot.

### 5.10 RBAC actions (`ActionRegistry`) and audit ledger

#### 5.10.1 Exact `ActionRegistry` descriptors and `SecurityWebMvcConfig` route bindings

Every route is registered in `SecurityWebMvcConfig.ACTION_ID_BY_ROUTE` and evaluated by `GateChain` (E3 + E4). No
write, cancel, retrieve, retention, cleanup, or upload action ever includes `RoleToken.REPLAY_VIEWER`, and
`SupportBundleController` also checks `!Boolean.TRUE.equals(request.getAttribute(GateChainInterceptor.IS_REPLAY_VIEWER_ATTRIBUTE))`
on every non-read handler as defense-in-depth (matching `PAN_FAILOVER_CONFIG_SYNC` and `TOPOLOGY_OVERRIDE_WRITE`).

| `ActionRegistry` Constant & Action ID | `consoleSubmittable` | Required / Permitted `RoleToken`s | HTTP Route(s) | Notes & `aiview` Boundary |
|---|---|---|---|---|
| `SUPPORT_BUNDLE_READ = "support_bundle_read"` | `true` | `Optional.of(RoleToken.OPERATOR)`, alternatives `Set.of(RoleToken.OPERATION_ADMIN, RoleToken.SECURITY_ADMIN, RoleToken.VIEWER, RoleToken.REPLAY_VIEWER)` | `GET /api/v2/support-bundles`<br>`GET /api/v2/devices/*/support-bundles`<br>`GET /api/v2/support-bundles/*` | Metadata only: opaque IDs, pseudonymised `hostname`, kinds, states, outcomes, sizes, 12-char `digest_prefix`, `expires_at`. For `aiview`, controller omits `requested_by`, `extended_by`, `deleted_by`, `approved_by`, `reason`, `extended_reason`, `deleted_reason`, and `case_ref`. Added to `AiviewDiagnosticReadOnlyTest` `held` allowlist |
| `SUPPORT_BUNDLE_REQUEST = "support_bundle_request"` | `true` | `Optional.of(RoleToken.SECURITY_ADMIN)` (Q6 asks whether `RoleToken.OPERATION_ADMIN` should also be added) | `POST /api/v2/devices/*/support-bundles` | Mandatory `reason` ($\ge 8$ chars), closed `kinds` list; inside business hours requires `X-Nexus-Business-Hours-Ack: true`. **Never `aiview`** |
| `SUPPORT_BUNDLE_CANCEL = "support_bundle_cancel"` | `true` | `Optional.of(RoleToken.SECURITY_ADMIN)` | `POST /api/v2/support-bundles/*/cancel` | Mandatory `reason` ($\ge 8$ chars). Cancels queued job or stops remaining unstarted items. **Never `aiview`** |
| `SUPPORT_BUNDLE_RETRIEVE = "support_bundle_retrieve"` | `true` | `Optional.of(RoleToken.SECURITY_ADMIN)` | `POST /api/v2/support-bundles/*/download-ticket`<br>`GET /api/v2/support-bundles/*/download`<br>`POST /api/v2/support-bundles/*/download` | Mandatory `reason` ($\ge 8$ chars). Two-step 60 s single-use ticket for browser download (`?ticket=`) or direct `POST` for CLI; supports optional `?mode=sanitized`. Pre-first-byte audit row required. **Never `aiview`** |
| `SUPPORT_BUNDLE_RETENTION_SET = "support_bundle_retention_set"` | `true` | `Optional.of(RoleToken.SECURITY_ADMIN)` | `POST /api/v2/support-bundles/*/extend` | Mandatory `reason` ($\ge 8$ chars); single extension up to 60 days from `requested_at`. **Never `aiview`** |
| `SUPPORT_BUNDLE_DELETE = "support_bundle_delete"` | `true` | `Optional.of(RoleToken.SECURITY_ADMIN)` | `POST /api/v2/support-bundles/*/delete` | Mandatory `reason` ($\ge 8$ chars); deletes encrypted files on disk first, then writes `removed` ledger rows. **Never `aiview`** |
| `SUPPORT_BUNDLE_CLEANUP_WORKDIR = "support_bundle_cleanup_workdir"` | `true` | `Optional.of(RoleToken.SECURITY_ADMIN)` | `POST /api/v2/support-bundles/*/cleanup-workdir` | Mandatory `reason` ($\ge 8$ chars); admits a one-command cleanup job (`C20`) for an `OUTCOME_UNKNOWN` request whose `.rc` file has landed. **Never `aiview`** |
| `SUPPORT_BUNDLE_UPLOAD_REQUEST = "support_bundle_upload_request"` (S8) | `true` | `Optional.of(RoleToken.SECURITY_ADMIN)` | `POST /api/v2/support-bundles/*/uploads` | Creates a `PENDING_APPROVAL` upload row for named items and a `case_ref`. **Never `aiview`** |
| `SUPPORT_BUNDLE_UPLOAD_APPROVE = "support_bundle_upload_approve"` (S8) | `true` | `Optional.of(RoleToken.SECURITY_ADMIN)` | `POST /api/v2/support-bundle-uploads/*/approve` | Approver fingerprint MUST differ from `requested_by` (`SELF_APPROVAL_REFUSED`, four-eyes). Valid 24 h. **Never `aiview`** |
| `SUPPORT_BUNDLE_UPLOAD_EXECUTE = "support_bundle_upload_execute"` (S8) | `true` | `Optional.of(RoleToken.SECURITY_ADMIN)` | `POST /api/v2/support-bundle-uploads/*/execute` | Accepts the one-time case token/password in the request body, holds it in worker memory only for the transfer, never persists it. **Never `aiview`** |

All nine write/retrieve/upload actions are added to the `closed` assertion array in
`AiviewDiagnosticReadOnlyTest.replayViewerHoldsExactlyTheDecidedActionsAndPacketAnalysisRunNeverRawView()`.

#### 5.10.2 Audit ledger, pre-first-byte download invariant, and `AuditRedactionPolicy`

1. **Transactional audit capture (`AuditedTransactionBoundary`).** Every insert or update on `support_bundle_request`,
   `support_bundle_item`, and `support_bundle_upload` runs inside
   `AuditedTransactionBoundary.inTransaction(actorFingerprint, actionId, ...)`, which sets `app.actor_fingerprint` and
   `app.action_id` on the PostgreSQL transaction.
   - `trg_audit_support_bundle_request` fires `AFTER INSERT OR UPDATE ON support_bundle_request FOR EACH ROW EXECUTE FUNCTION fn_audit_capture('id')`.
   - `trg_audit_support_bundle_item` fires `AFTER UPDATE OF state ON support_bundle_item FOR EACH ROW WHEN (NEW.state IN ('COMPLETED', 'PARTIAL', 'REFUSED_PRECHECK', 'UNSUPPORTED', 'TIMED_OUT', 'TOO_LARGE', 'DIGEST_MISMATCH', 'OUTCOME_UNKNOWN', 'CLEANUP_FAILED', 'REDACTION_FAILED', 'CANCELLED') AND OLD.state IS DISTINCT FROM NEW.state) EXECUTE FUNCTION fn_audit_capture('id')` (mirroring `trg_audit_report_run` in `V190` so polling updates do not spam `audit_log`).
2. **Pre-first-byte download audit (`SupportBundleDownloadService`).** Directly reuses the `BackupDownloadService` /
   `JooqBackupArtefactRetrievalRepository` pattern (14I `OR-3` and PO decision record 2026-09-22):
   - Step 1 (`POST /api/v2/support-bundles/{id}/download-ticket`): validates `id` format, authenticated
     `actorFingerprint`, and `reason.strip().length() >= 8`; issues a 24-byte random hex single-use ticket valid for
     60 seconds bound to `(requestId, actorFingerprint, reason, mode)`.
   - Step 2 (`GET /api/v2/support-bundles/{id}/download?ticket=...` or direct CLI `POST /api/v2/support-bundles/{id}/download`):
     `SupportBundleDownloadService.prepare(actorFingerprint, requestId, reason, mode)` verifies the request has at least
     one `COMPLETED` or `PARTIAL` item, and **before opening any `ArtefactStore.retrieve` stream**, executes an
     `AuditedTransactionBoundary` (`action_id = 'support_bundle_retrieve'`) that records one
     `backup_artefact_retrieval` row per included item (`action = 'support_bundle_downloaded'` or
     `'support_bundle_downloaded_sanitized'`) and invokes `audit_support_bundle_download(p_request_id, p_mode)`.
   - **Fail-closed guarantee:** If the audit write throws any `RuntimeException` or `SQLException`,
     `SupportBundleDownloadService.prepare` returns `Outcome.AuditRefused` $\rightarrow$ HTTP `500 Internal Server Error`
     (`AUDIT_REFUSED`), and **zero bytes** are decrypted or sent.
3. **`audit_redaction_policy` (`AuditRedactionPolicy.java`).** The migration inserts tier-1 redaction policy rows into
   `audit_redaction_policy` for every free-text / case-reference column so `fn_audit_redact` redacts them in
   `audit_log.before_state` and `after_state`:
   - `('support_bundle_request', 'reason', 1, 'Operator justification may contain environment names')`
   - `('support_bundle_request', 'extended_reason', 1, 'Operator retention extension justification')`
   - `('support_bundle_request', 'deleted_reason', 1, 'Operator early deletion justification')`
   - `('support_bundle_request', 'case_ref', 1, 'External vendor support case reference')`
   - `('support_bundle_item', 'outcome_detail', 1, 'Precheck or failure detail string')`
   - `('support_bundle_upload', 'case_ref', 1, 'External vendor support case reference')`
   - `('support_bundle_upload', 'reason', 1, 'Operator upload justification')`
   - `('support_bundle_upload', 'approval_reason', 1, 'Approver upload justification')`

### 5.11 CLI parity (`SupportBundleCli` / `NexusSessionCli`)

Per Product Owner standing rule (`cli_parity_for_every_ui_action`), every UI button has a 1-to-1 session-authenticated
CLI command in `ui2/cli/src/main/java/com/securityexpert/nexus/ui2/cli/SupportBundleCli.java`, wired into
`NexusSessionCli.run(...)` and `NexusSessionCli.printUsage()` alongside `FailoverCli`, `SanitizerCli`, and
`CutoverCli`. All commands use the stored session cookie (`ui2_session`), CSRF token (`X-CSRF-Token`), and global
`--business-hours-ack` flag (`X-Nexus-Business-Hours-Ack: true`) over the exact same HTTP routes and `GateChain` role
gates as the browser:

| CLI Command & Arguments | HTTP Method & Route | RBAC Action | Behaviour & Stream Handling |
|---|---|---|---|
| `support-bundles [deviceId] [state=<state>] [limit=<n>]` | `GET /api/v2/devices/<deviceId>/support-bundles` or `GET /api/v2/support-bundles?...` | `support_bundle_read` | Prints JSON list of support-bundle requests (masked when the session belongs to `aiview`) |
| `support-bundle <requestId>` | `GET /api/v2/support-bundles/<requestId>` | `support_bundle_read` | Prints full request status, prechecks, per-item states, sizes, 12-char `digest_prefix`, and `expires_at` |
| `support-bundle-request <deviceId> <kindsCsv> <reason> [--at <ISO-8601>] [--case <caseRef>] [--vs <vsidCsv>] [--business-hours-ack]` | `POST /api/v2/devices/<deviceId>/support-bundles` | `support_bundle_request` | Validates `<deviceId>` and comma-separated `<kindsCsv>`, sends JSON request body; inside business hours without `--business-hours-ack`, prints `409 BUSINESS_HOURS_CONFIRM_REQUIRED` and exits `1` |
| `support-bundle-cancel <requestId> <reason>` | `POST /api/v2/support-bundles/<requestId>/cancel` | `support_bundle_cancel` | Cancels a `QUEUED` request or stops remaining items of a `RUNNING` request |
| `support-bundle-download <requestId> <reason> <destPath.tgz> [--sanitized]` | `POST /api/v2/support-bundles/<requestId>/download[?mode=sanitized]` | `support_bundle_retrieve` | Streams `HttpResponse.BodyHandlers.ofInputStream()` directly to `<destPath.tgz>` via `Files.copy` (never buffered in heap, matching `NexusSessionCli.backupDownload`), prints byte size and warning banner |
| `support-bundle-extend <requestId> <days> <reason>` | `POST /api/v2/support-bundles/<requestId>/extend` | `support_bundle_retention_set` | Extends `expires_at` once by up to 60 days from `requested_at` |
| `support-bundle-delete <requestId> <reason>` | `POST /api/v2/support-bundles/<requestId>/delete` | `support_bundle_delete` | Early deletion of stored item artefacts with mandatory reason |
| `support-bundle-cleanup <requestId> <reason>` | `POST /api/v2/support-bundles/<requestId>/cleanup-workdir` | `support_bundle_cleanup_workdir` | Enqueues cleanup of a leftover `/var/log/nexus-sb-<token>` work directory after an `OUTCOME_UNKNOWN` run has written its `.rc` file |
| `support-bundle-upload-request <requestId> <cisco_cxd\|fortinet_sftp> <caseRef> <kindsCsv> <reason>` (S8) | `POST /api/v2/support-bundles/<requestId>/uploads` | `support_bundle_upload_request` | Submits a per-case upload request for four-eyes approval |
| `support-bundle-upload-approve <uploadId> <reason>` (S8) | `POST /api/v2/support-bundle-uploads/<uploadId>/approve` | `support_bundle_upload_approve` | Second `security_admin` approves the pending upload |
| `support-bundle-upload-execute <uploadId> [--token-file <path>]` (S8) | `POST /api/v2/support-bundle-uploads/<uploadId>/execute` | `support_bundle_upload_execute` | Reads the one-time CXD token or Fortinet ticket password from `--token-file <path>` (`SecretFile.readRequired`) or `System.console().readPassword(...)` (**never** a command-line argument), zeroes the `char[]` in `finally`, and triggers the upload |

**Exit codes** (identical to `NexusSessionCli` and `SanitizerCli`):
- `0`: HTTP `2xx` success (and file written for `support-bundle-download`).
- `1`: HTTP `400`/`404`/`409`/`5xx` refusal or I/O error (prints structured error code and reason to `stderr`).
- `2`: CLI usage error (missing argument, malformed UUID, unknown flag).
- `3`: HTTP `401` or `403` authentication/authorization refusal.

### 5.12 Download UI

The UI lives under **Tools → Support bundles** (the Tools group already holds Packet analysis, Data sanitizer and
Diagnostics). The device workspace gets a "Collect support bundle" action that opens the same form with the device
preset. The mockup is `ops-scripts/support-bundle-mockup-1114.html`. It is outside the repository and shows the
masked state only.

1. **New request.**
   - Device picker (rendering deterministic pseudonyms such as `FW-TANGO-04` under `aiview`).
   - Vendor-specific ticks, each with a load badge, size cap badge, and an "Adds to device load" note.
   - Run now / Run at, with the window and collision hints.
   - Optional case reference and a mandatory reason ($\ge 8$ chars).
   - A live guard preview. Examples: "HA peer FW-TANGO-05 has a bundle scheduled 00:00, this one will start
     after it", "Fortinet defect guard: TAC report will be skipped".
2. **Requests list.** State per item, size, 12-char digest prefix, expiry, Extend TTL button, Delete button, and a
   Download button (asks for a reason and optional "Sanitized text-only export" toggle, requests the 60 s download
   ticket, and triggers the browser's native streamed download). Under `aiview`, action buttons (`Request`, `Cancel`,
   `Download`, `Extend`, `Delete`, `Upload`) are disabled/hidden and `requested_by`, `reason`, and `case_ref` are
   withheld.
3. **Upload panel (S8).** Request, approve and history, with destination host and item digests.

### 5.13 Request again and automatic retry (PO 2026-11-26, V274)

A support-bundle request that never starts within the 3-hour claim window (`state = 'FAILED'`, `outcome = 'NOT_STARTED_DEVICE_BUSY'`, `started_at IS NULL`) — or a request cancelled before start (`state = 'CANCELLED'`, `started_at IS NULL`) — should not force a `security_admin` to re-enter the device, artefact kinds, virtual-system/domain scope, case reference, and schedule by hand:

1. **Why it did not start (closed vocabulary only).** `support_bundle_request.hold_reasons` (`TEXT[]`, constrained to `MODULE_DRAIN`, `COLLECTION_WINDOW`, `DEVICE_JOB_RUNNING`, `HA_PEER_RUNNING`, `FLEET_CAP`) records what held the request at submission, during claim deferrals, and at the 3-hour give-up check. Both the list row and request detail (`GET /api/v2/support-bundles/{id}` and the UI detail dialog) render a plain sentence built exclusively from those closed values and the expiry timestamp (`finished_at`), never from raw device or exception text. On the creation form, when a module drain is active or the target start time is inside a scheduled collection window (`00:00`, `06:00`, `12:00`, `18:00` `Europe/Istanbul`), a pre-submit warning states what the request will wait for (`"this will wait until ..."`).
2. **Quiet-hour selection.** Both the UI's suggested `"Run at"` and the service's automatic retry pick the next quiet slot in `Europe/Istanbul` (`04:00`, or `20:00`, `21:00`, `22:00`, `23:00`, `23:30`, with at least a 5-minute lead), avoiding the `00/06/12/18` collection windows, `02:00` scheduled backups, `~03:00` device HCP runs, and `08:00–20:00` business hours.
3. **Request again (UI + existing request route).** Rows with `FAILED` / `NOT_STARTED_DEVICE_BUSY` or `CANCELLED` before start offer `"Request again"`, pre-filling the existing request form with the same `device_id`, item kinds (plus `vs_id` / `domain_ref` scope), `case_ref`, `previous_request_id`, and suggested next quiet hour. Submission reuses `POST /api/v2/devices/{deviceId}/support-bundles` under `support_bundle_request` (`security_admin` only, never `aiview`), requiring a fresh operator reason and enforcing the existing business-hours confirmation rule.
4. **One automatic retry (service).** `SupportBundleService.retryExpiredBusy()` evaluates each expired-busy request (`state = 'FAILED'`, `outcome = 'NOT_STARTED_DEVICE_BUSY'`, `started_at IS NULL`, `auto_retry = FALSE`, `retry_outcome IS NULL`) at most once (`uq_support_bundle_auto_retry_once` partial unique index on `previous_request_id WHERE auto_retry = TRUE`):
   - Creates one `QUEUED` follow-up request scheduled (`AT`) at the next quiet hour as the original requester (`requested_by`), with reason `"automatic retry of <request id>"`, `previous_request_id = <original id>`, `auto_retry = TRUE`, and the same items (`kind`, `vs_id`, `domain_ref`). Both rows expose the bidirectional link (`previous_request_id` / `retry_request_id`), and the queued retry remains visible and cancellable in the list.
   - **Hard boundaries:** never retries a retry (`auto_retry = TRUE`), never retries a request cancelled by an operator (`state = 'CANCELLED'`), and never retries when a follow-up request already exists. If either the current evaluation time or the next quiet slot falls inside configured `BusinessHours` (where `JobRecordDao` / `ManualAdmissionRecord` requires an interactive `X-Nexus-Business-Hours-Ack` confirmation), or if device eligibility/quotas refuse admission, no automatic retry is created (`retry_outcome = 'SKIPPED_BUSINESS_HOURS'` / `'SKIPPED_QUOTA'` / `'SKIPPED_INELIGIBLE'`) and the operator uses `"Request again"` instead.

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
| C0 | `cp_sb_cpdata_collector_present` | `command -v cpdata_collector` (PO 2026-11-15, Q3) | read | 30 s | once per run | none | S2 |
| C21 | `cp_sb_cpdata_collector_list` | `cpdata_collector --list` (approved later 2026-11-15; gate row only, executor later) | read | 60 s | once per run | none | later |
| C23 | `cp_sb_poll_cpsdc` | `cat %s/cpsdc.rc` (PO 2026-11-15 about 22:30, record section 4.1; gate rows V239) | read | 30 s | every 30 s until the deadline (1860 s) | none | later |
| C22 | `cp_sb_cpdata_collector_run` | `cd %1$s && setsid nohup bash --noprofile --norc -c "timeout 1800 cpdata_collector --run -p %1$s/cpsdc.tgz -t 3 -m 100 > %1$s/cpsdc.log 2>&1; echo \$? > %1$s/cpsdc.rc" </dev/null >/dev/null 2>&1 &` (approved later 2026-11-15; gate row only, executor later) | recovery-write (BW-2) | 60 s | once per run, never retried | secret-bearing | later |

**Amendments of 2026-11-15, about 22:30 (PO record section 4).** C23 (`cat <workdir>/cpsdc.rc`) is approved and replaces the
blind wait of the `cpdata_collector_run` item; the log tail is not approved and a failure is `TOOL_EXIT_<rc>`. A host key
mismatch on a support bundle job warns and continues, as for a class 0 read, although the job is class 1 (BW-2); it applies to
the four support bundle capabilities only.

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

**PO 2026-11-15:** C0-C7, P1-P6, A1-A4, F1-F5 and C8-C20 are APPROVED exactly as written; A5-A7 are NOT approved; C21 and C22 (cpdata_collector --list / --run) are approved as gate rows, built later.

Count: 20 Check Point, 6 Palo Alto, 6 FortiGate (F3 is two literals), 4 ASA, plus 3 ASA fallback rows: 39 rows.

- **31 new literals.** CP C1–C3, C8–C18 and C20; P1–P6; F2, F4, F5; A1–A4; and the A5–A7 fallback.
  - `df -P /var/log` (C2) exists only for the MDS scope today, so for gateways it counts as new.
- **8 existing commands for a new purpose.**
  - C4, C5 and F1 exist already.
  - The F3 context moves (`config global`, `end`) are existing.
  - C6 exists as a system-log read and is re-approved without its `bash -lc` wrapper.
  - C7 reuses the system-log SFTP read.
  - C19 reuses the backup SFTP stream.

Approval goes per step: S2 asks for C0–C7, S3 for P1–P6, S4 for A1–A4, S5 for F1–F5, and S6 for C8–C20.

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
- `PARTIAL` (FGT/ASA capture ended early after $\ge 4\text{ KiB}$; stored and marked)
- `REFUSED_PRECHECK` (CPU / disk / version, with the measured numeric value)
- `UNSUPPORTED`
- `TIMED_OUT`
- `TOO_LARGE`
- `DIGEST_MISMATCH` (nothing removed on the device)
- `REDACTION_FAILED` (write-time secret redactor threw an exception; partial vault file discarded, zero bytes stored)
- `OUTCOME_UNKNOWN` (lost contact after start; work directory left; the UI offers "remove leftover work directory",
  which issues C20 for that exact recorded name after an rc file is seen)
- `CLEANUP_FAILED`
- `CANCELLED`

Per request:

- `COMPLETED` when every item completed;
- `PARTIAL` when at least one item stored;
- `FAILED` otherwise;
- `CANCELLED` when cancelled before any item stored;
- `NOT_STARTED_DEVICE_BUSY`.

Request lifecycle states (`support_bundle_request.state`): `QUEUED`, `RUNNING`, `COMPLETED`, `PARTIAL`, `FAILED`,
`CANCELLED`, `EXPIRED` (set by the hourly retention janitor once all item artefacts expire), and `DELETED` (set by
`support_bundle_delete`).

Notifications reuse the existing masked notification path: request finished or failed, and leftover work directory.

## 8. Risks

| # | Risk | Mitigation |
|---|---|---|
| R1 | Bundles carry bank secrets (configuration, hashes, possibly keys) | encrypted at rest; write-time `LogRedactor` + `DiagnosticText.scrubSecrets` on all text items; `cpinfo -S` on Check Point; `security_admin` only; reason and pre-byte audit per download; 14-day expiry; never parsed or shown; upload off by default with four-eyes |
| R2 | cpinfo or HCP loads a production gateway | vendor 70% per-core rule enforced; HA one member at a time; fleet cap 2; off by default; pilot measures first |
| R3 | MDS: the fork storm history (2026-11-11) | MDS heavy items hidden (Q5); `CP_ENV` normalization on every CP command |
| R4 | FortiGate session crash defect | version and DoS-policy guard from stored data; crash log still collected |
| R5 | Leftover files on devices after lost contact | exit code file, exact-name cleanup, UI action, notification; never wildcard deletes |
| R6 | Vault growth | 14-day expiry, per-device (3) and fleet (50 GiB) caps excluded from the 400 GiB backup budget, 10% volume free-space floor |
| R7 | ASA `terminal pager 0` CPU warning applies to the existing inventory session too | this design avoids it. The follow-up review is done 2026-11-15: the inventory, readiness, policy and failover ASA sessions no longer send it either (V233; docs/design/CISCO_ASA_CONTRACT.md, Paging) |
| R8 | HCP run replaces the device's own "last" report | stated in the UI. `cp_hcp_latest` (no run) is the default |
| R9 | Upload sends bank data abroad | S8 only after Q8 and the bank's compliance office; neXus-side egress only; per-case four-eyes; credentials never stored |
| R10 | Vendor option wording was read through summaries | lab verification of every literal before freeze (S1 task) |
| R11 | A scheduled time collides with backups, HCP or system logs | UI hints; claim-time deferral; give-up after 3 h |

## 9. Delivery plan (shippable steps with concrete tests)

Every step is independently shippable, leaves `main` green, and runs the module unit/integration tests, architecture
tests, frontend Vitest suite, preview e2e suite, and `tools/privacy/repository_privacy_check.py` before merge. Steps
`S2`–`S6` reach `AUTOMATED_VALIDATED` at merge and advance to `REAL_ENV_VALIDATED` / `DONE` only after the PO-attended
pilot (`S7`).

| Step | Scope & Deliverables | Device Contact | Gate Rows | Concrete Tests Required to Ship |
|---|---|---|---|---|
| **S0** | PO answers Q1–Q12 (§10.2) and ratifies the §6 command-gate literals for `S2`–`S3`; this design document transitions from `DRAFT — DO NOT FREEZE` to `FROZEN` for the ratified scope. Verify every literal in §6 against raw vendor pages | none | — | `python3 tools/privacy/repository_privacy_check.py`<br>`pytest tools/tests/test_live_project_governance.py` |
| **S1** | **Foundation, schema, RBAC, download/masking plumbing, retention janitor, CLI & UI shell (no device execution):**<br>• Flyway migration (`support_bundle_request`, `support_bundle_item`, `backup_artefact` class `'support_bundle'`, `trg_audit_support_bundle_*`, `audit_support_bundle_download`, `audit_redaction_policy` rows)<br>• `ArtefactClass.SUPPORT_BUNDLE`, `JooqSupportBundleRepository`, `JooqRetentionQueries.expiredSupportBundles()` & exclusion of `'support_bundle'` from `liveCiphertextBytes()`, `RetentionPruningService` support-bundle pass<br>• `ActionRegistry` descriptors (`SUPPORT_BUNDLE_READ`, `SUPPORT_BUNDLE_REQUEST`, `SUPPORT_BUNDLE_CANCEL`, `SUPPORT_BUNDLE_RETRIEVE`, `SUPPORT_BUNDLE_RETENTION_SET`, `SUPPORT_BUNDLE_DELETE`, `SUPPORT_BUNDLE_CLEANUP_WORKDIR`) & `SecurityWebMvcConfig` route map<br>• `SupportBundleController`, `SupportBundleService` (quota checks: 3/device, 50 GiB fleet, 10% volume floor, active-per-device guard, window/business-hours check), `SupportBundleDownloadService` (60 s single-use ticket, pre-first-byte audit, streamed `.tgz` + `manifest.json`, `?mode=sanitized` via `LogMasking.Session` failing closed on `MaskingUnavailableException`)<br>• `SupportBundleTextRedactor` (`LogRedactor` + `DiagnosticText.scrubSecrets` with 64 KiB line cap and fail-closed `REDACTION_FAILED`)<br>• `SupportBundleCli` wired into `NexusSessionCli`<br>• Frontend `SupportBundlesScreen.tsx` & `SupportBundlesPreview.tsx` with catalogue rendered (kinds disabled until their vendor step lands) | none | none | • **Schema & Audit IT:** `SupportBundleSchemaAndAuditIntegrationTest` (constraints, `uq_support_bundle_active_per_device`, `trg_audit_support_bundle_request`, `trg_audit_support_bundle_item` terminal-only firing, `AuditRedactionPolicy` coverage for `reason`/`case_ref`/`outcome_detail`)<br>• **Service & Download Unit:** `SupportBundleServiceTest` (3/device cap, 50 GiB fleet cap, 10% free-space floor, business-hours ack, single TTL extension up to 60 d, early delete truthful tombstone), `SupportBundleDownloadServiceTest` (pre-first-byte audit refusal sends 0 bytes, 60 s single-use ticket binding, `manifest.json` has pseudonym `FW-TANGO-04` and zero raw host/IP/serial/reason/case\_ref, `?mode=sanitized` masks lines via `LogMasking` and returns 503 `MASKING_UNAVAILABLE` when cold while omitting binary items)<br>• **Redactor Unit:** `SupportBundleTextRedactorTest` (redacts `SecretRules`, PEM keys, FortiGate `ENC`, 64 KiB overlong line $\rightarrow$ `[LINE_REDACTED_OVERLONG]`, exception $\rightarrow$ `REDACTION_FAILED` with 0 bytes persisted)<br>• **Retention Unit & IT:** `SupportBundleRetentionPruningTest` (expired bundles pruned without sole-backup exemption; `liveCiphertextBytes()` excludes `support_bundle`)<br>• **Security & Architecture:** `AiviewDiagnosticReadOnlyTest` (`SUPPORT_BUNDLE_READ` in `held`; all write/retrieve actions in `closed`), `AiviewMaskingLeakAuditTest` (all routes return `FUNNEL_TYPES`, `aiview` responses omit `requested_by`/`reason`/`case_ref` and mask `hostname`/`detail`, cold pod returns `FreeTextEstate.UNAVAILABLE`)<br>• **CLI Unit:** `SupportBundleCliTest` (all subcommands, `--business-hours-ack`, `--sanitized`, exit codes 0/1/2/3)<br>• **Frontend & E2E:** `SupportBundlesScreen.test.tsx` (Vitest) + preview Playwright check |
| **S2** | **Check Point light items (`cp_gaia_extras`, `cp_hcp_latest`):**<br>• `CpSupportBundleExecutor` & `CpSupportBundlePlan` (`MdsExportPlan.CP_ENV` prefix on all commands)<br>• CPU (`cpstat os -f multi_cpu`) & disk (`df -P /var/log`) precheck parsers<br>• `cp_gaia_extras`: 5 text outputs scrubbed via `SupportBundleTextRedactor` and tarred into `ArtefactStore` (1 MiB cap)<br>• `cp_hcp_latest`: zero-contact vault copy when a `cp_hcp_report` $\le 24\text{ h}$ old exists; otherwise `ls -lgo --time-style=+%s /var/log/hcp/last/` + SFTP stream (16 MiB cap), filename masked via `LogMasking.Session.maskFileName` (fallback `hcp_report.tar.gz` on `MaskingUnavailableException`)<br>• Enable `cp_gaia_extras` and `cp_hcp_latest` in UI catalogue | read-only, light | `C1`–`C7` | • `CpSupportBundlePlanTest` (`CP_ENV` prefix on every literal, no login shell, gate ID resolution)<br>• `CpSupportBundleLightExecutorTest` (vault-copy hit makes 0 SSH calls; SFTP fallback picks newest `.tar.gz`, rejects unsafe names, masks `<host>` in filename via `LogMasking`, enforces 1 MiB / 16 MiB stream bounds, records zero command output in `job_transcript`)<br>• `GateRegistryFixtureTest` (`C1`–`C7` registered and read-only) |
| **S3** | **Palo Alto Tech Support File (`pan_tsf`):**<br>• `PanSupportBundleExecutor` using `PanXmlApiTransport`<br>• `show system resources` (MP CPU $\le 80\%$ guard) and `show system disk-space` (`/` and `/opt/panlogs` $\le 90\%$ guard)<br>• Async export `type=export&category=tech-support` $\rightarrow$ poll `action=status&job-id=<n>` every 15 s (up to 30 min) $\rightarrow$ stream `action=get&job-id=<n>` into `ArtefactStore` (2 GiB bound, gzip magic `0x1f 0x8b` check, XML error refusal) $\rightarrow$ `action=finish&job-id=<n>` on timeout<br>• Enable `pan_tsf` in UI catalogue | XML API, BW-2 transient | `P1`–`P6` | • `PanSupportBundleExecutorTest` (CPU > 80% or disk > 90% $\rightarrow$ `REFUSED_PRECHECK`; async poll until `FIN`; gzip magic verified; XML error body refused; > 2 GiB stream aborted as `TOO_LARGE` with 0 vault rows; 30 min timeout calls `action=finish` and records `TIMED_OUT`; job ID parser rejects non-digits) |
| **S4** | **Cisco ASA (`asa_crashinfo`, `asa_show_tech`):**<br>• `AsaSupportBundleExecutor` over `SSH_INTERACTIVE` (privileged EXEC, **never** sending `terminal pager 0`, answering `<--- More --->` in session)<br>• `show cpu usage` precheck ($\le 80\%$)<br>• Multi-context `changeto system`<br>• Stream `show crashinfo` (64 MiB cap) and `show tech-support` (256 MiB cap) through `SupportBundleTextRedactor` into `ArtefactStore`; mid-stream disconnect $\ge 4\text{ KiB}$ stored as `PARTIAL` (`SESSION_LOST`)<br>• Enable `asa_crashinfo` and `asa_show_tech` in UI catalogue | read-only | `A1`–`A4` (`A5`–`A7` only if pilot requires disk0 fallback) | • `AsaSupportBundleExecutorTest` (asserts `terminal pager 0` is never sent; `<--- More --->` pager handled; multi-context sends `changeto system`; CPU > 80% refuses `asa_show_tech` while allowing `asa_crashinfo`; secrets redacted via `SupportBundleTextRedactor`; 64 MiB / 256 MiB bounds enforced; mid-session drop $\rightarrow$ `PARTIAL`) |
| **S5** | **FortiGate (`fgt_crashlog`, `fgt_tac_report`) with defect guard:**<br>• `FgtSupportBundleExecutor` over `SSH_INTERACTIVE` (`FortiGatePlan` `--More--` handling, `config global` / `end` on multi-VDOM)<br>• Pre-connection stored-data version guard: FortiOS $< 7.4.10$ (7.4), $< 7.6.6$ (7.6), or $7.0/7.2$ combined with `config firewall DoS-policy` in stored configuration refuses `fgt_tac_report` (`VENDOR_DEFECT_SESSION_CRASH`) before running it, while still collecting `fgt_crashlog`<br>• `get system status` live version corroboration + `get system performance status` CPU guard ($\le 80\%$)<br>• Stream `diagnose debug crashlog read` (32 MiB cap) and `execute tac report` (256 MiB cap) through `SupportBundleTextRedactor` into `ArtefactStore`<br>• Enable `fgt_crashlog` and `fgt_tac_report` in UI catalogue | read-only | `F1`–`F5` | • `FgtSupportBundleDefectGuardTest` (matrix of FortiOS versions 7.0.x, 7.2.x, 7.4.4, 7.4.10, 7.6.5, 7.6.6, 8.0.0 $\times$ presence/absence of `config firewall DoS-policy`)<br>• `FgtSupportBundleExecutorTest` (`config global` / `end` on multi-VDOM; `--More--` pager handling; `ENC` secrets scrubbed; 32 MiB / 256 MiB stream bounds; session drop $\rightarrow$ `PARTIAL`) |
| **S6** | **Check Point heavy items (`cp_hcp_run`, `cp_cpinfo`):**<br>• Extend `CpSupportBundleExecutor` with per-core CPU $< 70\%$ check and `/var/log` free space ($\ge 2\text{ GiB}$ for `cp_hcp_run`, $\ge 5\text{ GiB}$ for `cp_cpinfo`)<br>• Workdir `/var/log/nexus-sb-<12hex>`, point-of-no-return marker (`work_dir_path`, `work_dir_cleaned = FALSE`), detached `setsid nohup bash --noprofile --norc -c "...; echo \$? > .rc" </dev/null >/dev/null 2>&1 &`, 30 s `.rc` polling up to 2 h deadline<br>• VSX per-VS execution (`-c <VSID>` for HCP, `vsenv <VSID>` for cpinfo); SMS `-x` flag; MDS blocked unless Q5 enables it<br>• Device `sha256sum` vs. streamed vault SHA-256 verification (BK-3) before `rm -rf`; leftover workdir cleanup route & UI button (`C20` after `.rc` exists)<br>• HA peer serialization & fleet concurrency cap (2) at claim time | heavy, BW-2 | `C8`–`C20` | • `CpSupportBundleHeavyExecutorTest` (any CPU core $> 70\%$ or `/var/log` low refuses heavy items while completing `cp_gaia_extras` and `cp_hcp_latest`; detached launch never retries after point of no return; SHA-256 mismatch leaves workdir intact and marks `DIGEST_MISMATCH`; lost lease / timeout marks `OUTCOME_UNKNOWN` and keeps workdir; cleanup job runs `C20` only when `.rc` is present and matches exact `/var/log/nexus-sb-<12hex>` pattern; VSX integer validation; SMS adds `-x`; MDS refused)<br>• `SupportBundleClaimConcurrencyTest` (HA peer `RUNNING` defers by 10 min; device busy defers by 10 min; 3 h give-up marks `NOT_STARTED_DEVICE_BUSY`; fleet cap 2 enforced) |
| **S7** | **PO-attended real-environment pilot:**<br>• Run one bundle per vendor on a PO-selected pilot device outside business hours (`aiview` verification mode in UI)<br>• Measure runtime, output size, and CPU/disk before and after; commit the masked measurement record (`FW-TANGO-04`, etc.) | real devices (PO present) | ratified `C1`–`C20`, `P1`–`P6`, `A1`–`A4`, `F1`–`F5` | • Live `BEGIN/ROLLBACK` migration dry-run before deploy<br>• Post-run verification under `aiview` persona (`X-Nexus-Masked: true`)<br>• `python3 tools/privacy/repository_privacy_check.py` on the measurement record |
| **S8** | **Per-case vendor upload (`support_bundle_upload`):**<br>• Only after Q8 approval and bank compliance sign-off<br>• `support_bundle_upload` table, four-eyes approval (`SUPPORT_BUNDLE_UPLOAD_REQUEST`, `SUPPORT_BUNDLE_UPLOAD_APPROVE`, `SUPPORT_BUNDLE_UPLOAD_EXECUTE`), 24 h approval TTL, deployment egress host allowlist (`cxd.cisco.com`, `support.fortinet.com:2222`), proxy support, one-time token/password in worker memory only (zeroed in `finally`), pre-byte and post-byte audit rows, CLI & UI upload panel | vendor hosts from neXus (never from firewall) | none | • `SupportBundleUploadServiceTest` (self-approval refused `SELF_APPROVAL_REFUSED`; expired > 24 h approval refused; host outside allowlist refused; token/password char array zeroed after run and absent from DB, logs, and `audit_log`; pre-byte audit failure aborts upload)<br>• `CiscoCxdUploadClientTest` & `FortinetSftpUploadClientTest` (local loopback mock servers)<br>• `AiviewDiagnosticReadOnlyTest` (`SUPPORT_BUNDLE_UPLOAD_*` in `closed`) |

## 10. Resolved design questions and open Product Owner decisions

### 10.1 Questions resolved by existing codebase and frozen contracts

The following architectural and implementation questions are settled by existing repository code and frozen contracts
and do not require a new Product Owner decision:

| # | Question | Resolution from Codebase / Contract | Authority / Code Reference |
|---|---|---|---|
| R-1 | How must Check Point SSH commands be wrapped to prevent login-shell side effects or fork storms? | Every Check Point command is prefixed with `MdsExportPlan.CP_ENV` (`unset BASH_ENV ENV; . /etc/profile.d/CP.sh >/dev/null 2>&1; unset BASH_ENV ENV; `) and long tools run detached under `setsid nohup bash --noprofile --norc -c "...; echo \$? > .rc" </dev/null >/dev/null 2>&1 &`. | `MdsExportPlan.java` line 49 (shipped code; the shell normalization contract is still a draft and is not authority) |
| R-2 | How is the Check Point HCP report filename contradiction (`hcp_last_report.tar.gz` vs. `hcp_report_<host>_<dd_mm_yy_HH_MM>.tar.gz`) handled, and how is `<host>` kept out of filenames? | Never hardcode the filename. List `/var/log/hcp/last/` with `ls -lgo --time-style=+%s /var/log/hcp/last/`, filter by `SAFE_FILE_NAME` (`^[A-Za-z0-9_.-]{1,128}$`) ending in `.tar.gz`, pick the newest mtime, and mask the stored entry name via `LogMasking.Session.maskFileName(name, deviceHostname)` (falling back to `hcp_report.tar.gz` on `MaskingUnavailableException`). | `DEVICE_SYSTEM_LOG_PHASE_B_CONTRACT.md` §8.1; `CpSystemLogCollectJobExecutor.java` lines 77–81, 315–329; `LogMasking.java` line 183 |
| R-3 | How do support-bundle bytes avoid consuming the 400 GiB backup/configuration vault budget, and how are expired bundles pruned without keeping the "sole remaining backup for a device"? | `JooqRetentionQueries.liveCiphertextBytes()` excludes `artefact_class IN ('system_log', 'support_bundle')`, while `liveSupportBundleCiphertextBytes()` enforces the separate 50 GiB support-bundle cap. `RetentionPruningService` prunes `support_bundle` rows past `expires_at` using the `V190` `expiredReports()` pattern (no `isSoleRemainingBackupForDevice` check, file deleted on disk before writing the `removed` row in `artefact_retention_ledger`). | `JooqRetentionQueries.java` lines 115–159; `RetentionPruningService.java` lines 215–224; `V190__reports_r1_report_run.sql` |
| R-4 | How are multi-gigabyte bundles downloaded in the browser and CLI without buffering in memory or sending bytes before the audit row is committed? | Reuse the `BackupController.downloadTicket` / `download` + `BackupDownloadService.prepare` two-step pattern: a 60 s single-use ticket bound to `(requestId, actorFingerprint, reason, mode)`, an `AuditedTransactionBoundary` writing `backup_artefact_retrieval` and `audit_log` before `ArtefactStore.retrieve` opens any ciphertext stream, and `StreamingResponseBody` piping `TarArchiveOutputStream` $\rightarrow$ `GzipCompressorOutputStream` in 64 KiB chunks. | `BackupController.java` lines 94–193; `BackupDownloadService.java` lines 67–99; `JooqBackupArtefactRetrievalRepository.java` |
| R-5 | How are text items (`cp_gaia_extras`, `asa_crashinfo`, `asa_show_tech`, `fgt_crashlog`, `fgt_tac_report`), API responses, `manifest.json`, and `aiview` projections masked so they reuse existing maskers and fail closed? | Write-time text streams chain `LogRedactor` (`cp-text-v1` + `SecretRules`) and `DiagnosticText.scrubSecrets` (64 KiB line cap; exception $\rightarrow$ `REDACTION_FAILED` with 0 bytes stored). Optional `?mode=sanitized` export runs `LogMasking.Session.maskLine` (`logmask-v8`, failing closed with 503 `MASKING_UNAVAILABLE` if cold) and omits binary items. Read APIs return `Map<String, Object>` through `PrivacyMaskingResponseBodyAdvice`, omit `requested_by`/`approved_by`/`reason`/`case_ref` for `aiview`, and fail closed to `FreeTextEstate.UNAVAILABLE` if dictionaries are cold. | `LogRedactor.java`; `DiagnosticText.java`; `LogMasking.java`; `PrivacyMaskingResponseBodyAdvice.java`; `FreeTextEstate.java`; `AiviewMaskingLeakAuditTest.java`; `AiviewDiagnosticReadOnlyTest.java` |
| R-6 | How does FortiGate session capture avoid changing `config system console / set output standard`? | Reuse `FortiGateExecutor` / `FortiGatePlan`, which answers `--More--` prompts interactively in the SSH channel without modifying device console configuration. | `FortiGateExecutor.java`; `V80` gate rows |
| R-7 | Are temporary work directories and tech-support dump files on a device classified as Class 1/2 configuration writes? | No. Per PO decision record 14K `BW-2` and `V61`/`V206` (`MdsExportPlan`), creating and deleting a transient archive in `/var/log/nexus-sb-<token>` or `/tmp/techsupport.tgz` is a `BW-2` transient operational artefact, not a device configuration or recovery mutation. | PO decision record 14K `BW-2`; `V61` / `V206` |

### 10.2 Product Owner decisions (2026-11-15)

All twelve questions are decided in `PO_DECISION_RECORD_2026_11_15_SUPPORT_BUNDLE_COLLECTION.md` section 1; that table
is the authority. Summary: Q1 any time, business-hours confirm, hints; Q2 delete after a confirmed download, 14 days
for an undownloaded bundle with a warning first; Q3 presence read approved, run literal not; Q4 `-z -D -d -S` (+`-x` on
SMS); Q5 MDS included with a warning; Q6 `security_admin` only; Q7 defaults as recommended; Q8 upload wanted for all
four vendors, built later after research; Q9 no four-eyes; Q10 each member over its own address; Q11 no FXOS; Q12 no
`terminal pager 0`, with a backlog item for the inventory session.

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
