# Check Point backup refused on 7 gateways (2026-09-22): neither 'show diskspace' nor 'df -P /var/log' parsed to a free-space value, so the fail-closed precheck refused -- measure the real output shape on one of them and extend the parser; likely part of the 19 targets without an archive

status: planned · target:

## 2026-10-17 -- project JSON compacted (size budget)

Title (full): Check Point backup refused on 7 gateways (2026-09-22): neither 'show diskspace' nor 'df -P /var/log' parsed to a free-space value, so the fail-closed precheck refused -- measure the real output shape on one of them and extend the parser; likely part of the 19 targets without an archive

2026-11-14 backlog truth: DONE. Evidence: commits 93155a42 (df fallback), 688bafe4 (Spark routed); live: no free-space refusals in 30 days. Backup precheck no longer refuses on unparsed free space
