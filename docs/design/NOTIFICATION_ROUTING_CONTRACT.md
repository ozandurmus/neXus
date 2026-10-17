# Mail notifications by type, each with its own recipient list, through an unauthenticated SMTP relay

**Status:** FROZEN -- Product Owner direction 2026-09-27 (below); the notification types were delegated to the
engineering session ("tipleri belirle") and are fixed here.

Product Owner, 2026-09-27: "SMTP tanımı yapıp relay email attırmak istiyorum. Accountsuz. Standart relay altyapısını
ve tanımlarının yapılacağı ekrandan emin ol. Bildirim için tipleri belirle ve mail gönderim listesi için alan hazırla,
örneğin admin event ..."

## 1. What exists (measured 2026-09-27)
- Administration › Platform › Notifications (`NotificationSettingsPanel.tsx`), security_admin only
  (`notification_config_read/write`): syslog, SMTP relay host / port / STARTTLS / from / one recipients field,
  "When a job fails", Save, Send test syslog, Send test mail.
- `SmtpRelaySender` (raw socket, EHLO/MAIL/RCPT/DATA, optional STARTTLS, 15 s timeouts) sends **without an account**
  -- exactly the PO's relay model. Keep it; no authentication is added.
- `NotificationForwarder` (service pod, every 60 s): audit rows -> syslog; failed jobs -> syslog and one mail to the
  single recipients list. One shared job-failure watermark for both channels.

## 2. Notification types (fixed)
| Type key | Title (UI) | Source | Event |
| --- | --- | --- | --- |
| `admin_event` | Administration changes | `audit_log.action_id` | local identity created / password set / enabled / disabled (`local_credential_*`), role granted / revoked (`role_binding_create`, `role_binding_revoke`), credential created / secret replaced (`credential_create`, `credential_replace_secret`, `device_credential_set`, `device_secret_set`), device registered / deleted (`device_register`, `device_delete` -- one line per device, not per row), backup target set (`device_backup_target_set`), notification settings / routes changed (new audit rows, §4). |
| `login_security` | Sign-in and access | `audit_log.action_id` | `local_login_failure`, `machine_session_token_refused`, `machine_session_roles_refused`, `machine_session_read_only`, `session_login_takeover` (a new sign-in replaced an open session). |
| `backup_failure` | Backup failures | `jobs` | backup job types (`*_backup`, `https_vendor_backup`) with state FAILED, or COMPLETED with a `partial:` terminal reason. |
| `job_failure` | Other job failures | `jobs` | every other job type with state FAILED (collection, inventory, confirm, discovery). |
| `config_change` | Configuration changes | `configuration_notification` | each new row (device, kind, summary). |
| `compliance_regression` | Compliance regressions | stored compliance evaluations | a control whose display status goes from PASS to FAIL on a device (all severities; the severity is shown). Needs a small last-notified state (§3). |
| `device_health` | Device reachability | `jobs` | a device whose last 3 finished jobs (any type) all FAILED with a connect/transport reason -> "unreachable", once, until one succeeds -> "reachable again"; and any job failing with a host-key mismatch reason. |

## 3. Data (migration V100, dry-run before deploy)
- `notification_routes(type text primary key check (type in the seven keys), enabled boolean not null default false,
  recipients text null, watermark_audit bigint, watermark_time timestamptz, last_sent_at timestamptz,
  last_error text, updated_at, updated_by)`; seed one row per type, watermarks at "now" so nothing historical is sent;
  grant select/update to `ui2_app`.
- `notification_state(key text primary key, value text not null, updated_at)` for `compliance_regression`
  (key `c:<device_id>:<control_id>`, last status) and `device_health` (key `h:<device_id>`, reachable/unreachable).
- Existing `notification_settings.smtp_to` becomes the **default recipients**: a route with an empty `recipients`
  sends to it. Existing `notify_job_failure` true -> seed `backup_failure` and `job_failure` enabled.
- Audit triggers on `notification_settings` and `notification_routes` (action ids `notification_settings_update`,
  `notification_route_update`) -- they feed `admin_event` too.

## 4. Sending
- Each tick, per enabled route with a mail relay configured: collect its new events past its **own** watermark, send
  **one digest mail** to its recipients (route list, else default), subject `neXus – <Title>: <n> event(s)`, body one
  line per event (time UTC, device name, what happened, job id / link path), at most 200 lines then "and N more";
  advance that route's watermark only after the relay accepted the mail; on failure store `last_error`, keep the
  watermark, retry next tick. Routes are independent (a failing route never re-sends another route's events).
- Syslog keeps today's behaviour (audit forwarding and job failures); not per type in this build.
- Message content is for the operators' own relay: device names and reasons unmasked (as today); never secrets --
  terminal reasons are already secret-free; no configuration text is ever included.
- A single leader: guard the tick with a Postgres advisory lock so a second service replica never double-sends.

## 5. Screen (Administration › Platform › Notifications)
- "Mail relay" card: relay host, port, STARTTLS switch, from address, default recipients; helper text: *standard SMTP
  relay without an account; the relay must accept mail from this server's address*.
- "Notification types" card: one row per type -- enabled switch, title, one-line description (from §2), recipients
  field (comma separated; placeholder "default recipients"), last sent / last error, **Send test** (sends a sample
  digest of that type, using the values currently in the form, to that row's effective recipients).
- Save validates every address (existing email rule) and shows problems per field.
- API: `GET/PUT /api/v2/config/notifications` gains `routes: [{type, enabled, recipients, last_sent_at, last_error}]`;
  `POST /api/v2/config/notifications/test-mail` accepts an optional `{type, settings}` body (unsaved form values).
  Same actions and role (security_admin).

## 6. Out of scope
SMTP authentication, per-type syslog, SMS/Teams/webhooks, per-device subscriptions.

## 7. Amendment 2026-10-17 -- notification type `device_crash_file` (PO: system-log phase B, Q5)
Authority: `DEVICE_SYSTEM_LOG_PHASE_B_CONTRACT.md` §7 and §13 Q5, approved by the Product Owner on 2026-10-17
(`PO_DECISION_RECORD_2026_10_17_SYSTEM_LOG_PHASE_B.md`, Q5: "the notification type `device_crash_file` is added (amends the
FROZEN notification routing contract and its V100 check)"). Nothing else in §1-§6 changes; the digest, watermark, leader and
recipient rules of §4 apply to the new type unchanged. Migration `V160__system_log_phase_b.sql` carries the data change and
is dry-run before deploy like V100.

Type added to the §2 table:

| Type key | Title (UI) | Source | Event |
| --- | --- | --- | --- |
| `device_crash_file` | Device crash and dump files | `device_crash_alert` | one new row per (device, collection run, process): new files in `/var/log/crash` or `/var/log/dump/usermode` on a Check Point gateway, with the parsed process name (`UNKNOWN` when the name does not match the measured pattern), the number of new files and the newest file time. Listing only; no file content is read or sent. |

- **Which files alert.** A name not seen in the previous listing, or the same name with a changed size or time (matched by
  name digest and time). A device's first listing is a baseline ("present before monitoring"): its existing files raise no
  alert. A removed file raises no alert.
- **What does not alert.** Rotation-loss gaps of the system-log collector (`ROTATED_AWAY`, `TRUNCATED`) never notify; they
  show on the Logs screen and in the daily summary (phase B contract §4.5, §13 Q5).
- **Watermark.** The route's `watermark_time` against `device_crash_alert.created_at`. A mail line is: time UTC, device name,
  count and process, newest file time, job link.
- **Type set.** §2 and §3 list seven types and the V100 check allowed seven. V113 later added `security_scan` without an
  amendment here; the check now allows nine: the seven of §2, `security_scan` and `device_crash_file`. V160 replaces the
  check (`notification_routes_type_check`) with the nine-type list and seeds `device_crash_file` disabled, with its
  watermark at "now", so nothing historical is sent. The type list in the service (`NotificationRoute.TYPES`) and the
  Notifications screen row follow.
- **Data.** `device_crash_alert(alert_id, device_id, process, file_count, newest_mtime, job_id, created_at, notified_at)`,
  unique per (device, job, process), created by V160.
