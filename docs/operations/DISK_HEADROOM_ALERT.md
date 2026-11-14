# Disk headroom alert

Status: IMPLEMENTED, AUTOMATED_VALIDATED (backlog `scheduled_fleet_backup_enable_decision`, retitled "disk headroom
alert"). Real-environment validation is still open: the first live threshold crossing has not been observed.

A disk-full incident happened on 2026-10-10. `SystemStatusService` used to report only usable bytes; it now compares
usage with thresholds, shows the result in the platform status payload and the top-bar health light, and sends one
syslog notification per threshold crossing.

## Volumes

| Name | What it is | Measured from |
| --- | --- | --- |
| `root` | the host root disk as the pod sees it | the file store of `/` |
| `artefact_store` | the backup/evidence volume (a host-path volume, so the host disk) | the file store of `UI2_ARTEFACT_STORE_ROOT`, only when this pod mounts it |

The database volume is **not** measured: the status service reports the database size (`pg_database_size`) but not
the volume it lives on, so there is nothing to compare. A volume the pod does not mount is not listed; a volume that
cannot be read is listed as `unknown`, never as full (collection failure is not a known-bad state).

Usage is `df`'s figure: used / (used + usable), rounded up, so the percentage an operator sees on the host is the one
compared with the thresholds.

## Thresholds (configuration)

| Property | Environment variable | Default |
| --- | --- | --- |
| `ui2.disk-headroom.warning-percent` | `UI2_DISK_WARNING_PERCENT` | 80 |
| `ui2.disk-headroom.critical-percent` | `UI2_DISK_CRITICAL_PERCENT` | 90 |
| `ui2.disk-headroom.hysteresis-percent` | `UI2_DISK_HYSTERESIS_PERCENT` | 2 |

Rules: `1 <= warning < critical <= 100` and `0 <= hysteresis < warning`. Invalid values do not stop startup; the
service logs `[DISK_HEADROOM_CONFIG_INVALID]` and uses the defaults.

## Status payload and health light

`GET /api/v2/system/pods` (the read the top-bar light and the Platform status page already make) carries a `disk`
section on every answer, including when pods cannot be read:

```json
{"level": "critical", "thresholds": {"warning_percent": 80, "critical_percent": 90},
 "volumes": [{"name": "root", "total_bytes": 1000, "usable_bytes": 280, "used_percent": 72, "level": "ok"}]}
```

`level` per volume is `ok`, `warning`, `critical` or `unknown`; the top-level `level` is the worst measured one. The
payload shows the **current** reading with no hysteresis. `GET /api/v2/system/storage` adds `used_percent` and `level`
to `artefact_volume`.

Top-bar light (`PlatformHealthIndicator.platformHealth`): a **critical** volume makes it `degraded` with the reason
`Disk critical: <volume> is <n>% used.` It keeps its rule that only current conditions degrade: the light reads the
live measurement, never a past alert, so it returns to OK as soon as the disk is back under the threshold. A warning
does not degrade the light; it is raised as a notification and shown as a banner on the Platform status page. Pod
problems and quarantined work are still reported first.

## Notification

Through the existing syslog target (Administration > Notifications), message id `DISK_HEADROOM`, checked every 60 s
(`DiskHeadroomAlerts`). The last level per volume is kept in `notification_state` (key `disk_headroom_level:<volume>`):

| Change | Sends |
| --- | --- |
| OK -> WARNING | one WARNING line |
| OK or WARNING -> CRITICAL | one CRITICAL line |
| WARNING or CRITICAL -> OK | one INFORMATIONAL "recovered" line (re-arms the alert) |
| CRITICAL -> WARNING | nothing, but the critical alert is re-armed |
| no change | nothing |

- A level is left only when usage falls `hysteresis` points below its threshold (default: warning held until 77 %,
  critical until 87 %), so a volume hovering on a threshold sends one line, not one per wobble.
- The new level is recorded only after the line was sent; a failed send is retried on the next pass.
- With syslog disabled nothing is sent or recorded, so enabling it later reports a condition that still holds.
- A line carries the volume name, the percentage and the threshold only; no path, host or address.
- Mail is not wired for this alert (it would need a notification route type and a migration); syslog is the channel.
