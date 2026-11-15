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
| Q3 | `cpdata_collector` (CPSDC) | One read gate is approved to find out whether the tool is installed: `command -v cpdata_collector` (Check Point, read, 30 s, once per run, item `cpdata_collector_present`). The run literal of `cpdata_collector` is NOT approved: it is registered as a pending gate placeholder that refuses. |
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
| `cpdata_collector` run literal | (placeholder `cp_sb_cpdata_collector_run`) | NOT approved. Registered only as a pending gate that refuses. |

Approval of a literal is not approval of a build step: this record authorises the gate rows. Real-environment
validation stays a separate gate (design step S7, PO-attended pilot).

## 3. Delivery

Design steps S1 (core: data model, RBAC, request / list / download, delete-after-download, expiry with warning,
audit, CLI parity, fail-closed masking, UI) and S2 (Check Point light items) are built first. Steps S3-S6 follow in
separate lanes. S8 (upload) waits for its research.
