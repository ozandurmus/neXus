# NXS-LOCAL-0377 — Backups and diagnostics: major deviations, size-anomaly alert, backup type, local-log reads

## Summary

Backups flag major deviations from stored configuration (V176), record their type so snapshot retention applies, keep the last good copy on a size anomaly (V180) and raise a daily Backup size deviation alert (V189), and the fleet table sorts by size. Five PO-approved Check Point local-log diagnostics shipped (V181-V183); their masked output over-masks sizes and words (diagnostic_mask_overreach).

Evidence (2026-11-10 plan refresh): the size deviation alert's first live pass found firewall logs inside 47 of 56 Check Point backups; the PO fixed one gateway and kept retention at 7 days (PO_SESSION_HANDOVER_2026_11_05.md section 4). The local-log diagnostics ran live on 2026-10-31; the masked output was unreadable, tracked as diagnostic_mask_overreach.
