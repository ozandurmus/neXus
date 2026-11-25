# Script Execution: operator scripts (.sh/.py/.jar or inline) on a cron schedule in an isolated runner; report on Jobs, notify via syslog/SMTP relay

status: planned · target: PO decision record 2026-09-22 (automation editor and script execution); contract SCRIPT_EXECUTION_CONTRACT.md to write first

2026-09-22 PO answered the four decisions (own pod; run as a chosen actor/credential; 30-day retention; every mainstream language, .ps1 instead of .bat) and ratified the device-write amendment (PO_DECISION_RECORD_2026_09_22_SCHEDULED_DEVICE_WRITES_FROM_SCRIPTS.md): named target, named actor+credential, ledgered, gated write command with RB.x-kind contract, explicit per-target writes:allowed. First write use case: weekly Cisco ASA configuration push to a DR device -- measure the ASA configuration-replace mechanism first.

PO 2026-09-23 order: backup for the other vendors first, then Script Execution, then failover (read side first; execution under its own frozen contract, one pilot cluster, maintenance window).

## 2026-10-17 -- project JSON compacted (size budget)

Title (full): Script Execution: operator scripts (.sh/.py/.jar or inline) on a cron schedule in an isolated runner; report on Jobs, notify via syslog/SMTP relay

Target (full): PO decision record 2026-09-22 (automation editor and script execution); contract SCRIPT_EXECUTION_CONTRACT.md to write first

2026-11-14 backlog truth: still open (P2, size L). Today: Operator scripts cannot be scheduled in product; PO order put other work first. Evidence: SCRIPT_EXECUTION_CONTRACT.md exists; no script runner pod/migration/code in ui2

2026-11-18 reconciliation: the PO asked about this module again and wants it language-independent. The contract docs/design/SCRIPT_EXECUTION_CONTRACT.md has been FROZEN since 2026-09-22 and already fixes language independence (its section 4 decision 4 and section 7: as many industry-standard languages as the runner image can carry, with .ps1 through PowerShell Core instead of .bat; the .sh/.py/.jar wording is only the first set). Nothing has been built. Priority raised from P2 to P1 because the contract names this item P1; the backlog row had drifted. The device-write part still needs the constitutional amendment named in the contract section 6.

Slice 1 shipped f141bb2b (V251); slice 2 shipped 084c89d7 (V258): per-run pod, broker role, masked audited output. Image nodejs:22 restored ea732041.

Slice 3 (V266, 2026-11-23): run-end syslog/SMTP notifications per script.notify via NotificationForwarder/ScriptRunNotifier; per-script mail-on-failure-only option (widened script.notify CHECK); outcome recorded append-only in script_run_notification and shown in the run history row. AUTOMATED_VALIDATED only (service unit tests, vitest); PostgreSQL integration tests and real relay/collector delivery not yet proven.
2026-11-23 real-environment checklist: the PO runs slices 1-3 once with harmless non-device scripts (PO decision 2026-11-23); steps, exact CLI forms, clause per step and boolean/count evidence tokens are in docs/design/SCRIPT_EXECUTION_REAL_ENV_VALIDATION_CHECKLIST_2026_11_23.md (DRAFT, decides nothing); the item stays AUTOMATED_VALIDATED until that run passes.
2026-11-25: S3.6b closed (GET /api/v2/scripts and the detail route carry last_run_outcome and last_run_at of the latest finished run; the Script Execution list shows a Last run column with its age; pin test enabled, traceability row PROVEN). Real-environment validation is still the PO's run.
