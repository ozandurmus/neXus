# PO decision record 2026-11-17: Check Point install-history read at every inventory run

## Status

2026-11-17 successor amendment to `PO_DECISION_RECORD_2026_11_16_CP_INSTALL_HISTORY_DISCOVERY.md`: the one-off discovery read is superseded by this production read.

Gate: `cp_install_history_inventory` (migration V246, fixture `gate_registry_fixture.yaml`).

## Approval

Product owner, 2026-11-17, chat: "Evet, onaylıyorum", of the exact literal below.

## Approved literal (byte for byte)

```
unset BASH_ENV ENV; cd /opt/CPInstLog 2>/dev/null || exit 0; for f in $(ls -1tr install_fw1_R*.elg install_SecurePlatform_HOTFIX_*JUMBO*.log 2>/dev/null | tail -n 20); do echo "$(date -r "$f" +%Y-%m-%dT%H:%M:%S) $f $(grep -a -o -m1 -E 'Bundle_T[0-9]+' "$f" | head -n1)"; done
```

## Scope and exclusions

CLASS_0 (read); Check Point Gaia gateways only. Skipped, with the reason logged as `[CP_INSTALL_HISTORY] outcome=SKIPPED reason=...`:
management servers (`management_server`), VSX hosts (`vsx_host`), and sessions that landed in clish (`clish_landing`).

## Bounds

Each inventory run; plain expert shell exactly like `uptime`, never `bash -lc`, vsenv or any login shell; timeout 30 s; no retry.
A failure, timeout or unusable answer is logged (`outcome=READ_FAILED <exception name>`) and never fails or changes the inventory outcome.
The worker log carries counts only (`outcome=READ events=<n> base=<n> jumbo=<n>`): no file name, host name or address.

## Switch

`UI2_CP_INSTALL_HISTORY_READ` (system property `ui2.cp.install-history.read` overrides). Default on (`1`); `0` turns the read off for the run
(logged as `reason=switch_off`). The V245 one-off switch `UI2_CP_INSTALL_HISTORY_DISCOVERY_DEVICE` and its code path are removed; the V245
migration and its fixture entry stay as history.

## Storage

Table `device_install_event` (V246): one row per install-log file and local time (device, file name, local time unique); kind
`BASE_INSTALL` or `JUMBO_INSTALL`, product version (for example R81.20), Jumbo take number when the log names one, first and last observed.
The device prints its own clock without an offset, so the install time is stored and shown as the device's local time and is never converted.
Rows are upserted at each run (a repeat only moves `last_observed_at` and may fill a missing take) and are never deleted by the product.
At most 20 events are kept per read (the 20 most recently modified matching files).

Read side, no new routes: `GET /devices/{id}/software-history` and `GET /api/v2/software-history/changes?days=N` carry `install_events`;
the CLI commands `software-history` and `software-changes` print them; the device page and the Lifecycle "Recent upgrades" tab show them.

## Evidence

The 2026-11-17 one-off (record 2026-11-16, second run) on one STANDBY R81.20 gateway showed the four Jumbo installs and the base install with their dates under
`/opt/CPInstLog`. `DA_Actions*` files were not found. Jumbo take numbers are not in the file names, so the `Bundle_T<n>` search inside each file is
best effort and unverified: when it finds nothing the take is stored empty and shown as "take not in log".

## Purpose

Show Check Point Jumbo install dates on the device page and in the Lifecycle "Recent upgrades" tab, from the device's own install log instead of
from what neXus observed between inventory runs.
