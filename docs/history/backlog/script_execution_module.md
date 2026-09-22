# Script Execution: operator scripts (.sh/.py/.jar or inline) on a cron schedule in an isolated runner; report on Jobs, notify via syslog/SMTP relay

status: planned · target: PO decision record 2026-09-22 (automation editor and script execution); contract SCRIPT_EXECUTION_CONTRACT.md to write first

2026-09-22 PO answered the four decisions (own pod; run as a chosen actor/credential; 30-day retention; every mainstream language, .ps1 instead of .bat) and ratified the device-write amendment (PO_DECISION_RECORD_2026_09_22_SCHEDULED_DEVICE_WRITES_FROM_SCRIPTS.md): named target, named actor+credential, ledgered, gated write command with RB.x-kind contract, explicit per-target writes:allowed. First write use case: weekly Cisco ASA configuration push to a DR device -- measure the ASA configuration-replace mechanism first.

PO 2026-09-23 order: backup for the other vendors first, then Script Execution, then failover (read side first; execution under its own frozen contract, one pilot cluster, maintenance window).
