# Check Point platform facts: 9 of 62 gateways have a facts row but no jumbo take (7 of them no appliance family or serial either) -- find why the read parses nothing on these (Clish-landing shell, VSX context, or no jumbo installed) and say UNKNOWN with a reason

status: planned · target:

## 2026-10-17 -- project JSON compacted (size budget)

Title (full): Check Point platform facts: 9 of 62 gateways have a facts row but no jumbo take (7 of them no appliance family or serial either) -- find why the read parses nothing on these (Clish-landing shell, VSX context, or no jumbo installed) and say UNKNOWN with a reason

2026-11-14 backlog truth: DONE. Evidence: live device_platform_facts: all 63 CP rows have hotfix and family; 2 lack serial; NXS-LOCAL-0381 (V195). Facts now populated for all gateways
