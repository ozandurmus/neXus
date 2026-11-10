# NXS-LOCAL-0374 — System logs phase B: collectors, masked view, settings route, Spike Detective data

## Summary

Check Point and Palo Alto system-log collectors (V160, V161, V167), the masked view (logmask-v3), the settings route and Spike Detective data collection are merged. Spike data and management-server collection stay off until a security_admin switches them on.

2026-11-10 (plan refresh): status in_progress -> automated_validated. All phase B code is merged and the scheduled system-log collection runs live on its own switch (00/06/12/18); its first scheduled run collected 97 devices (PO_SESSION_HANDOVER_2026_11_05.md section 4). logmask-v4 (PR 658) closed two of the masking residual gaps. Still open and tracked in the backlog: the 512 MiB spike cap in the pruner (spike_data_cap_not_enforced_by_pruner), the remaining masking gaps (masking_residual_gaps_system_log_and_sanitizer) and PO acceptance under aiview; spike data stays off.
