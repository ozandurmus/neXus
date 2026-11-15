# PO decision record - 2026-11-15: support bundle collection (vendor TAC files)

## Status

RATIFIED - 2026-11-15 (about 12:15-12:25 local), Product Owner answers relayed by the PO session. This record freezes
`SUPPORT_BUNDLE_COLLECTION_DESIGN_2026_11_14.md` for the scope it names below and approves the device command
literals listed in section 2. It approves nothing else: every literal not listed here stays unapproved, and approval
of one literal never approves the next (AGENTS.md "Network action taxonomy", 2026-09-26 amendment).

## 1. Policy decisions (design section 10.2)

| # | Question | PO decision (2026-11-15) |
| --- | --- | --- |
| Q1 | "Run at a time": any time, or only the 00/06/12/18 windows | Any time the administrator picks. Inside business hours (08:00-20:00 Europe/Istanbul) the existing confirmation applies, against the chosen time. The UI shows collision hints (02:00 backups and MDS export, about 03:00 device HCP, 06:00 system-log copy). A collision is a hint, not a refusal. This is a one-line amendment to the 2026-10-20 job-window record: an explicit admin-picked time on a support-bundle request is a deferred admin manual job. |
| Q2 | Retention and quota | A bundle is deleted, after a confirmation dialog, once it has been downloaded. A bundle that was never downloaded is deleted after 14 days, with a warning shown first. 3 live requests per device and 50 GiB fleet-wide stay as designed. |
| Q3 | `cpdata_collector` (CPSDC) | One read gate is approved to find out whether the tool is installed: `command -v cpdata_collector` (Check Point, read, 30 s, once per run, item `cpdata_collector_present`). The run literal was first held back; the PO then approved `--list` and `--run` (section 2), as gate rows only. |
| Q4 | cpinfo options | `-z -D -d -S`, plus `-x` on a Security Management Server. Never `-l` or `-k`. |
| Q5 | MDS | cpinfo and HCP-run commands are included for MDS targets. The UI shows a clear warning when the target is an MDS. There is no block. |
| Q6 | Who may request and download | `security_admin` only, for the request and for the download. |
| Q7 | Default ticks | As recommended in design section 5.2: light items ticked, `cp_cpinfo` and `cp_hcp_run` (HCP) unticked. |
| Q8 | Vendor upload | Wanted for all four vendors. It is built later, after research (design step S8). Nothing of it is built or approved here. |
| Q9 | Four-eyes on upload | NO four-eyes. The same administrator may request, download and upload. This overrides design sections 4 (rule 3), 5.1 (`approved_by <> requested_by` check) and 5.10 (`SUPPORT_BUNDLE_UPLOAD_APPROVE`, `SELF_APPROVAL_REFUSED`); the S8 research and design re-decide the upload flow without a second approver. The audit of every upload stays. |
| Q10 | HA members | Each HA member is collected over its own management address. No `execute ha manage` or peer hop. |
| Q11 | FXOS | No FXOS chassis bundles. |
| Q12 | ASA pager | No `terminal pager 0` in the ASA support-bundle session (the `<--- More --->` prompt is answered in session). A backlog item reviews the inventory session's use of `terminal pager 0`. |

## 2. Approved device command literals (design section 6, exact)

| Step | Rows | Decision |
| --- | --- | --- |
| S2 Check Point light reads | C1-C7 | APPROVED exactly as written in section 6. |
| S2 additional read | `command -v cpdata_collector` (gate `cp_sb_cpdata_collector_present`, read, 30 s, once per run) | APPROVED. |
| S3 Palo Alto | P1-P6 | APPROVED exactly as written. |
| S4 Cisco ASA | A1-A4 | APPROVED exactly as written. |
| S4 ASA fallback | A5-A7 | NOT approved. |
| S5 FortiGate | F1-F5 | APPROVED exactly as written. |
| S6 Check Point heavy | C8-C20 | APPROVED exactly as written. |
| `cpdata_collector --list` | gate `cp_sb_cpdata_collector_list`, read, 60 s | APPROVED later on 2026-11-15 (relayed by the coordinator). Gate row only; no executor uses it in the S1/S2 build. |
| `cpdata_collector --run` | gate `cp_sb_cpdata_collector_run`, recovery-write (BW-2), once per run, never retried: `cd <workdir> && setsid nohup bash --noprofile --norc -c "timeout 1800 cpdata_collector --run -p <workdir>/cpsdc.tgz -t 3 -m 100 > <workdir>/cpsdc.log 2>&1; echo \$? > <workdir>/cpsdc.rc" </dev/null >/dev/null 2>&1 &` | APPROVED later on 2026-11-15 (relayed by the coordinator). Gate row only; execution is built in a later lane. |

Approval of a literal is not approval of a build step: this record authorises the gate rows. Real-environment
validation stays a separate gate (design step S7, PO-attended pilot).

## 3. Delivery

Design steps S1 (core: data model, RBAC, request / list / download, delete-after-download, expiry with warning,
audit, CLI parity, fail-closed masking, UI) and S2 (Check Point light items) are built first. Steps S3-S6 follow in
separate lanes. S8 (upload) waits for its research.

## 4. Amendment - 2026-11-15, about 22:30 local (Product Owner, relayed by the PO session)

**4.1 cpdata_collector exit code read - APPROVED.** `cat <workdir>/cpsdc.rc` (gate `cp_sb_poll_cpsdc` for a gateway and
`cpm_sb_poll_cpsdc` for a management server; Check Point, `CP_ENV` prefix like every Check Point exec literal; class 0 read;
30 s; every 30 s until the deadline, which is the device bound `timeout 1800` plus one minute). Gate rows: migration V235 and
`gate_registry_fixture.yaml`. The `cpdata_collector_run` item polls it and no longer waits blind; the exit code is recorded
as `TOOL_EXIT_<rc>` (127 stays `UNSUPPORTED`). The log tail literal (`cpsdc.log`) was **NOT** approved: nothing reads it.

**4.2 Host key mismatch warns and continues for support bundle jobs - DECIDED.** Support bundle jobs are class 1 only because
of the BW-2 transient writes. A host key mismatch on a support bundle job (capabilities `cp_support_bundle`,
`pan_support_bundle`, `asa_support_bundle`, `fgt_support_bundle`) behaves as for a class 0 read in WARN mode: the connection
continues, the observation is audited, and the job and device are flagged `ssh_identity_unverified` before credentials are
sent. STRICT mode, a rejected device and failover execution still refuse. This is narrow: the MDS export and every other class
1 job still refuse a mismatch. This amends the "WARN is read only" rule of `SshHostKeyPolicyStore` for these four capabilities
only.
