# PO decision record 2026-11-16: Check Point install-history discovery read (one-off)

Gate: `cp_install_history_discovery_once` (migration V245, fixture `gate_registry_fixture.yaml`).

## Approved literal (byte for byte)

```
unset BASH_ENV ENV; ls -l --time-style=+%Y-%m-%dT%H:%M:%S /opt/CPInstLog 2>&1 | tail -n 80; find /opt/CPda /var/log/CPda -maxdepth 3 -name 'DA_Actions*' 2>/dev/null | head -5
```

## Approved bounds

CLASS_0 (read); check_point gateway; expert shell; SSH_EXEC; 30 s timeout; no retry; once; on one STANDBY non-VSX R81.20
gateway; normal neXus SSH exec path and account. The command is sent in the plain expert shell like the other plain
reads (for example `uptime`), never wrapped in `bash -lc`, vsenv or any login shell.

## Approval

Product owner, 2026-11-16, chat: "Evet, onaylıyorum".

## Purpose

Find where Check Point keeps Jumbo/hotfix install history and in what format, so Jumbo install dates can be read. A
production literal for all Check Point devices needs a separate PO approval after this result.

## Mechanics

The worker sends the read only while `UI2_CP_INSTALL_HISTORY_DISCOVERY_DEVICE` equals the id of the device being
inventoried (unset or empty means never), in the physical context of a non-VSX Gaia gateway. The env is set for one
inventory run and removed right after. The output is recorded only in the job transcript; it is not parsed or stored. A
failure or timeout is logged and never changes the inventory outcome.

## 2026-11-16 first run: no answer kept
The first run (16:41) completed the inventory, but inventory jobs keep no transcript and the worker logged only the answer length; the worker pod was replaced when the switch was removed, so the answer was lost. Fix: the worker now logs the answer reduced to file-name and date characters (at most 90 lines of 160 characters), and logs SKIPPED with the reason when the switch names the device but the read does not apply. A second run of the same literal on the same device needs the PO's go-ahead.
