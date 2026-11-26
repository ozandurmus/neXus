# ZoneMatrixPolicyBridge is still 1713 lines after the NAT builder split (limit 1500); further split needed

status: planned · target: 8ec46955

2026-11-26 backlog truth: ZoneMatrixPolicyBridge.java is 1447 lines on base de37c67a (wc -l), below the 1500 the title names, so the further split this item asked for is no longer needed. No test in tools/tests or ui2/architecture-tests enforces a source-file line limit (the only 1500 in tools/tests is the project QUEUE word limit in test_project_files_budget.py); the limit was only this item's own wording. Closed as done; if the file grows past 1500 again, open a new item. Automated check only (line count).
