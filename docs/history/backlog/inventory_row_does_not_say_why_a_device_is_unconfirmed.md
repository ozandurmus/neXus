# The inventory row shows Registered, not confirmed without saying why: 47 of 107 devices failed on connect_failed and the reason is only visible by opening each device

status: planned · target: InventoryScreen row rendering; jobs.terminal_reason

## 2026-10-17 -- project JSON compacted (size budget)

Title (full): The inventory row shows Registered, not confirmed without saying why: 47 of 107 devices failed on connect_failed and the reason is only visible by opening each device

Target (full): InventoryScreen row rendering; jobs.terminal_reason

2026-11-14 backlog truth: DONE. Evidence: Commit ae6cc63a: JobStatusIndicator with terminalReason in InventoryPanels.tsx; latest_job_terminal_reason in device select. Unconfirmed rows show the failure reason
