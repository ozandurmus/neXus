# Notification identity exposure audit — 2026-11-05

Status: DRAFT (audit + proposal; not a frozen contract, authorizes no implementation).
Scope: outbound notification e-mail (`NotificationMailRouter`, `SmtpRelaySender`) and syslog forwarding
(`SyslogSender`, `NotificationForwarder`). Paths are under
`ui2/service/src/main/java/com/securityexpert/nexus/ui2/service/`. Read-only audit of source; no live system touched.

## Finding

Messages that leave the appliance carry raw device hostnames, and fall back to the raw `device_id` when no
hostname is observed. There is no masking step and no setting to request one. Free-text columns
(`terminal_reason`, `summary`, `row_pk`) are also forwarded after only length/line-break clamping (`safe()`), so they
can carry identities the SQL did not select.

## Fields carrying operational identity, per notification type

E-mail digest lines are built in `notification/NotificationMailRouter.java` (`collect`, from line 77).
`device_name` = `coalesce(observed_hostname, <device id>)`: a raw hostname, else a raw device id.

| Type (route) | Identity-bearing fields | file:line |
|---|---|---|
| `admin_event`, `login_security` | `row_pk` (audit row key; for `device_register`/`device_delete` it is the device id; other tables' keys may embed names) | NotificationMailRouter.java:97,103-104 |
| `backup_failure`, `job_failure` | `device_name` (hostname / `target_device_id`); `terminal_reason` (free text from the job, may contain hostnames/IPs/serial fragments) | NotificationMailRouter.java:113-124 (name 114, 121; reason 123). Failover jobs are already replaced by the fixed labels "CP/PAN failover unit" (119-121) |
| `config_change` | `device_name`; `summary` (free text) | NotificationMailRouter.java:128-136 |
| `device_crash_file` | `device_name`; `process` (process name, not a network identity) | NotificationMailRouter.java:141-151 |
| `backup_size_anomaly` | `device_name`; `summary` (documented as sizes/ratios/percentages only, PO 2026-11-04, line 155-156) | NotificationMailRouter.java:157-167 |
| `compliance_regression` | `device_name`; `controlId` (control identifier, not a device identity) | NotificationMailRouter.java:171-186 |
| `device_health` | `device_name` in three line shapes: host key mismatch (206-213), unreachable (229-230), reachable again (233-234); the "HTTPS certificate changed" line (216-218) carries only the job link, no name | NotificationMailRouter.java:191-240 |
| `security_scan` | none by design: counts and tool/severity labels; package ids at SecurityScanSummary.java:85-86 are software identifiers, not devices | notification/SecurityScanSummary.java:43-86 |

Every digest line also carries a relative link `/operations/jobs/<job_id>` or `notification <id>`; these are neXus
internal ids, not device identities.

Syslog (`notification/NotificationForwarder.java`):

| Message | Identity-bearing fields | file:line |
|---|---|---|
| `JOB_FAILED` (when `notifyJobFailure` and syslog enabled) | `observed_hostname` else raw `target_device_id` (select 82, choice 98-101); `terminal_reason` truncated to 300 chars (102-104). Failover jobs use fixed labels | NotificationForwarder.java:79-106, send at 111 |
| `AUDIT` (when `forwardAuditToSyslog`) | `table_name`, `action_id`, `actor_fingerprint` (a fingerprint, not a username) — no device identity selected | NotificationForwarder.java:56-67 |

Syslog transport (`notification/SyslogSender.java`): the RFC 5424 HOSTNAME field is the pod name from the
`HOSTNAME` environment variable (lines 42, 61-63) — appliance identity, not a managed-device identity.

SMTP envelope (`notification/SmtpRelaySender.java`): `EHLO nexus` (46, 57), `MAIL FROM`/`From:` from the configured
`smtp_from` (59, 65), `Message-ID: <uuid@nexus>` (69). No managed-device identity; the subject comes from
`subject()` (NotificationMailRouter.java:73-76) and contains only the type title and a count. IPs and serials are not
selected by any SQL here; they can only arrive through the free-text columns above.

Test sends (`api/NotificationSettingsController.java:57, 94-95`) use fixed sample text — no exposure.

## Gaps not closed by masking the name column

1. Free text (`terminal_reason`, `summary`, `row_pk`) is untrusted for identity content; masking only the
   `device_name` column leaves these. `TopologyNamePseudonymizer.maskText(String)` (privacy/TopologyNamePseudonymizer.java:393)
   exists for this and should be applied to every free-text field in masked mode.
2. The raw-`device_id` fallback is a stable internal key; in masked mode print a pseudonym derived from it, not the id.
3. Standing identity rules apply: opaque identifiers, no casual normalization; the pseudonym is a presentation label
   only.

## Proposed option

Setting `notification_identity` with values `masked` | `raw`, **default `masked`**, in `notification_settings`
(V51) and `NotificationSettings`, applying to both e-mail and syslog.

- Masked: device name -> `TopologyNamePseudonymizer.maskDeviceName(raw, null, deviceId)` (privacy/TopologyNamePseudonymizer.java:146);
  unknown device -> `maskManagedDeviceName` (162) or a fixed "unknown device" label; free text -> `maskText` (393);
  serials -> `maskSerial` (349). The same pseudonyms as the UI's AIView masking, so a recipient's mail matches what
  `aiview` shows (AGENTS.md AIView law).
- Raw: today's behaviour. Changing to `raw` is a `security_admin` action, audited like `notification_settings_update`
  (NotificationMailRouter.java:29).
- Fail closed: if the pseudonymizer or its key is unavailable, send the fixed label rather than the raw value.

## Why implementation is not part of this change

The setting is not small: it needs a Flyway migration (storage change, which AGENTS.md requires a frozen contract
for), a new `NotificationSettings` component (the record and its secondary constructor at
NotificationSettings.java:10-31 are used widely, including tests), store read/write
(`NotificationSettingsStore`), controller validation, the settings UI, and a change from static
`NotificationMailRouter.collect`/`tick` and `NotificationForwarder.forwardJobFailures` to receive a
`TopologyNamePseudonymizer`. It also changes what recipients see by default, which is a product decision.
Recommended next step: Product Owner approves the default (`masked`) and the migration; then a small build adds the
column, threads the pseudonymizer through `tick`/`collect`/`forwardJobFailures`, and adds tests asserting no
`observed_hostname`/device-id value appears in any digest or syslog line in masked mode, including free-text fields.
