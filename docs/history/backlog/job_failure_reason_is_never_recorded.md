# A FAILED job records no reason: outcome, terminal_reason and finished_at stay empty and the run summary is circular

status: planned · target: jobs.outcome/terminal_reason/finished_at; blocks ui2_b1_07 and ui2_b1_08 being useful

## 2026-10-17 -- project JSON compacted (size budget)

Title (full): A FAILED job records no reason: outcome, terminal_reason and finished_at stay empty and the run summary is circular

Target (full): jobs.outcome/terminal_reason/finished_at; blocks ui2_b1_07 and ui2_b1_08 being useful

2026-11-14 backlog truth: DONE. Evidence: Live check: 821 FAILED jobs, 0 missing terminal_reason or finished_at; written by JooqJobRecordDao. Failed jobs carry a reason and end time
