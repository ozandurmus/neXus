# PO decision record - 2026-10-30: local firewall log diagnostics on Check Point Gaia

**Status: RATIFIED - PO DECISION (chat, 2026-10-30).** Backed by automated validation only; not yet run on a device.

## Context
A Check Point gateway keeps about 288 GB of local firewall logs. To investigate, the engineer needs five read-only commands that
were not in the approved diagnostic catalog.

## PO answer (verbatim)
"Bunlari sen calistirabilirsin" ("You can run these").

## Decision
These five literals are approved, exactly as written, as CLASS_0 reads for `cp_gaia_gateway` (Expert, SSH_EXEC, 30 s, no retry),
seeded SIGNED_OFF by V181 and mirrored in `gate_registry_fixture.yaml`:

| Gate | Literal |
| --- | --- |
| `cp_localdiag_du_fwdir_log` | `bash -lc 'du -sh $FWDIR/log'` |
| `cp_localdiag_ls_fwdir_log` | `bash -lc 'ls -la $FWDIR/log \| tail -20'` |
| `cp_localdiag_cpstat_fw_log_connection` | `bash -lc 'cpstat fw -f log_connection'` |
| `cp_localdiag_cat_fw1logs_cpbak` | `bash -lc 'cat /var/CPbackup/schemes/fw1logs.cpbak'` |
| `cp_localdiag_cphaprob_state` | `bash -lc 'cphaprob state'` |

Notes: `$FWDIR` needs an Expert shell, so every literal uses the `bash -lc` login-shell form of the existing reads (V157). The
`| tail -20` pipe stays inside the `bash -lc` literal, as `cp_syslog_measure_hcp_listing` already does. They use the diagnostic
output mask and size cap; no write, no new capability. The aiview mask hides integers of 6 or more digits, so exact byte sizes
in the `ls -la` listing may not be readable; `du -sh` is the size evidence.

## Addendum 2026-10-31 -- follow the log directory symlink (V182)

The first run (2026-10-31 00:03) showed that `$FWDIR/log` on the gateway is a
symlink: `du -sh $FWDIR/log` returned 0 and `ls -la $FWDIR/log | tail -20`
listed only the link. The PO approved in chat ("Komutlar onayli") the same two
commands with a trailing slash, which makes both follow the link:

| gate_id | Command |
|---|---|
| `cp_localdiag_du_fwdir_log_dir` | `du -sh $FWDIR/log/` |
| `cp_localdiag_ls_fwdir_log_dir` | `ls -la $FWDIR/log/ \| tail -20` |

Same class, context, masking and frequency as the V181 rows. `du` gets a 60 s
timeout because the directory holds several hundred GB.
