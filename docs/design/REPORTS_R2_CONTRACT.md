# Reports R2 contract

**Status: DRAFT - 2026-11-05. DO NOT IMPLEMENT until this status line says FROZEN.** Written by the engineer (cloud
session, document only, no live access). It builds on `REPORTS_R1_CONTRACT.md` (FROZEN) and on
`REPORTS_DESIGN_2026_11_05_ARGON.md` with its appended engineer decisions. Where this draft and R1 differ, **R1 wins**.
R2 changes nothing R1 freezes; it only adds to it. Where this draft and the Argon design differ, this draft records
why (§12). The design's §4.7 SQL is **not** authority: it names `notification_routes` columns that do not exist
(`event_type`, `recipient_addresses`). The real schema is in §3.1.

Everything below was checked against the source on `cloud-base/1105` (commit `bab573e`). Anything the source does not
establish is marked UNKNOWN. Examples use only masked forms (`FW-TANGO-04`, `CLS-ROMEO-01`, `192.0.2.x`).

## 1. Scope

R2 adds two things to R1.
1. **Scheduled reports with e-mail delivery.** Any R1 or R2 report can run on a cron schedule, evaluated in
   Europe/Istanbul. Each run is stored in History like a manual run and, when mail is configured, sent as an e-mail.
   The e-mail is **always MASKED**.
2. **Five more reports:**
   - RPT-06 policy hygiene;
   - RPT-07 policy change;
   - RPT-08 configuration change and drift;
   - RPT-09 lifecycle / end of life;
   - RPT-10 inventory.

**Not in R2:**
- RAW scheduled reports and RAW e-mail. RAW stays in-app only, as R1 has it (engineer decision 3).
- Week-over-week trend deltas (R3).
- RPT-04 and RPT-11 to RPT-16 (R3).
- Any new device command or new collection. All R2 reports read only the neXus database and the artefact store, as
  R1 requires.
- Any new comparison semantics that the source does not already compute. For example, RPT-07's
  management-to-gateway installation alignment is limited to what exists today (§8.2).

**Inherited from R1 unchanged:**
- the one-masking-pass rule;
- "data as of" and coverage on every report and section;
- UNKNOWN is always a shown category;
- the B1 freshness scale, or the HA banner threshold until B1 lands;
- the `report` artefact class, 30-day retention and the 25 MiB file cap;
- the bounded executor (at most 2 concurrent builds) with read-only transactions and a statement timeout;
- OpenPDF, POI and RFC 4180 CSV with formula-injection escaping;
- the actions `report_read`, `report_generate` and `report_raw_view`, plus the per-domain read check;
- the audit row on every generate and download.

## 2. Source facts this contract relies on

| Fact | Where |
|---|---|
| `notification_routes` columns are `type` (PK, CHECK list), `enabled`, `recipients`, `watermark_audit`, `watermark_time`, `last_sent_at`, `last_error`, `updated_at`, `updated_by`. The CHECK is named `notification_routes_type_check`. | V100. The list is widened by V113 (`security_scan`), V160 §4 (`device_crash_file`) and V189 (`backup_size_anomaly`). |
| Inserting a route row requires `updated_by`: `fn_audit_notification_config` raises "requires an actor" otherwise. Route row inserts and deletes and `enabled`/`recipients` updates are audited as `notification_route_update`. | V100 |
| `recipients` and `last_error` are tier-2 redacted in the audit capture. | V153 |
| `NotificationRoute.TYPES` and `NotificationMailRouter.TITLES` are parallel lists, matched by index. | `svc/notification/NotificationRoute.java`, `NotificationMailRouter.java` |
| `NotificationMailRouter.collect` ends in `default -> throw new IllegalArgumentException("unknown notification type")`. `tick` turns that into `last_error = 'event collection failed'` on every pass. | `NotificationMailRouter.java` |
| The mail router runs only from `NotificationForwarder.tick`, which is `@Scheduled(fixedDelay = 60_000)`. With `UI2_SCHEDULING_ENABLED=false` (the current deployment, development mode since 2026-11-03), **no notification mail is sent at all**. | `NotificationForwarder.java`, `boot/SchedulingConfiguration.java`, `deploy/ui2/50-service-deployment.yaml` |
| `SmtpRelaySender.send(settings, recipients, subject, body)` sends one `text/plain; charset=UTF-8`, `8bit` message over plain SMTP with optional verified STARTTLS. It has no authentication and no attachments. A reply code it does not accept becomes an `IOException` carrying the reply text. | `svc/notification/SmtpRelaySender.java` |
| Recipient syntax is checked against `[^@\s<>"]+@[^@\s<>"]+`. Lists are split on `[,;\s]+`. A route's own recipients, when blank, fall back to the global `smtp_to` (`effectiveRecipients`). | `NotificationSettings.java`, `NotificationRoute.java` |
| Five-field cron is evaluated in a given zone by `BackupScheduleDue.dueSlot(cron, lastRun, now, catchUpWindowSeconds, zone)`. It uses Spring `CronExpression` with a seconds field prepended. | `svc/device/backup/BackupScheduleDue.java` |
| `JobWindowPolicy.ZONE = "Europe/Istanbul"`. | `platform-core/.../JobWindowPolicy.java` |
| The independent-switch pattern is `SystemLogCollectionScheduler`. | `svc/device/diagnostic/SystemLogCollectionScheduler.java` |
| No `report_run`, `ReportDocument`, `report_*` action or `report` artefact class exists yet. R1 is being implemented in parallel and takes the next free V number. The newest migration on this base is V189. | repository search |

Paths: `svc/` = `ui2/service/src/main/java/com/securityexpert/nexus/ui2/service/` and `per/` =
`ui2/persistence/src/main/java/com/securityexpert/nexus/ui2/persistence/`. Migrations live in
`ui2/service/src/main/resources/db/migration/`.

How `SystemLogCollectionScheduler` implements the independent switch:
- The bean exists only when its own `enabled` property is true; the code default is false.
- A `@Scheduled(cron = "0 * * * * *", zone = JobWindowPolicy.ZONE)` method ticks under the global switch.
- When `independent=true`, a `SmartLifecycle` start creates its own single-thread daemon `ScheduledExecutorService`
  that runs the same `tick()` every minute.

## 3. Schema

The migration is **one** file: the next free V number after R1's `report_run` migration (expected V191 if R1 takes
V190 and nothing else lands first). As in R1, it is dry-run in BEGIN/ROLLBACK on the live database before deploy
(engineer step). Conventions follow V185/V186:
- `TEXT` with length checks;
- `TIMESTAMPTZ`;
- `GRANT ... TO ui2_app`;
- `fn_audit_capture` on operator-configuration tables;
- tier-2 entries in `audit_redaction_policy`;
- every other column of an audited table listed in `AuditPresentationAllowlist`. This is enforced by
  `AuditPresentationAllowlistMatchesLiveSchemaTest`.

### 3.1 Exact SQL

```sql
-- V<next> -- Reports R2: report schedules, scheduled-report deliveries, notification type scheduled_report
-- (docs/design/REPORTS_R2_CONTRACT.md §3).
--
-- report_schedule   operator configuration (security_admin, action report_schedule_manage); audited by fn_audit_capture.
--                   No projection column: a scheduled report is MASKED by construction (§5), and report_run enforces it.
-- report_delivery   one row per (schedule, fired slot): the claim that makes a slot fire once (§4.4), and the delivery
--                   outcome. Derived rows written by the service: no audit trigger (V188 convention). The send itself is
--                   audited by the application (§6.2). No FK to report_schedule: history outlives a deleted schedule
--                   (V160 convention) and is pruned by retention (§7).
-- notification_routes  new type scheduled_report: the master switch and the default recipients for report e-mail.
--                   Seeded DISABLED (unlike V189): report mail carries attachments to new recipients and must be
--                   switched on by a security_admin. Watermark columns are unused for this type.
SELECT set_config('app.actor_fingerprint', 'migration:V<next>_reports_r2_schedules', true);
SELECT set_config('app.action_id', 'audit_redaction_policy_seed_by_migration', true);

CREATE TABLE IF NOT EXISTS report_schedule (
    schedule_id      UUID PRIMARY KEY,
    template_id      TEXT NOT NULL CHECK (template_id IN ('RPT-01', 'RPT-02', 'RPT-03', 'RPT-05', 'RPT-06',
                         'RPT-07', 'RPT-08', 'RPT-09', 'RPT-10')),
    name             TEXT NOT NULL CHECK (length(name) BETWEEN 1 AND 160),
    enabled          BOOLEAN NOT NULL DEFAULT false,
    -- exactly five space-separated fields, evaluated in Europe/Istanbul (§4.2); full validation is in the service
    cron             TEXT NOT NULL CHECK (length(cron) <= 64 AND cron ~ '^\S+( \S+){4}$'),
    formats          TEXT[] NOT NULL CHECK (cardinality(formats) BETWEEN 1 AND 3
                         AND formats <@ ARRAY['PDF', 'XLSX', 'CSV']::TEXT[]),
    parameters_json  JSONB NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(parameters_json) = 'object'),
    -- NULL = the scheduled_report route's effective recipients (route recipients, else global smtp_to)
    recipients       TEXT CHECK (recipients IS NULL OR length(recipients) BETWEEN 1 AND 2000),
    last_slot_at     TIMESTAMPTZ,
    created_by       TEXT NOT NULL CHECK (length(created_by) BETWEEN 1 AND 256),
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by       TEXT NOT NULL CHECK (length(updated_by) BETWEEN 1 AND 256),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS report_schedule_enabled ON report_schedule (enabled) WHERE enabled;

GRANT SELECT, INSERT, UPDATE, DELETE ON report_schedule TO ui2_app;

DROP TRIGGER IF EXISTS trg_audit_report_schedule ON report_schedule;
CREATE TRIGGER trg_audit_report_schedule
    AFTER INSERT OR UPDATE OR DELETE ON report_schedule
    FOR EACH ROW EXECUTE FUNCTION fn_audit_capture('schedule_id');

INSERT INTO audit_redaction_policy (table_name, column_name, tier, reason) VALUES
    ('report_schedule', 'name', 2, 'operator free text'),
    ('report_schedule', 'recipients', 2, 'recipient mail addresses')
ON CONFLICT (table_name, column_name) DO NOTHING;

CREATE TABLE IF NOT EXISTS report_delivery (
    delivery_id      UUID PRIMARY KEY,
    schedule_id      UUID NOT NULL,
    template_id      TEXT NOT NULL CHECK (template_id IN ('RPT-01', 'RPT-02', 'RPT-03', 'RPT-05', 'RPT-06',
                         'RPT-07', 'RPT-08', 'RPT-09', 'RPT-10')),
    scheduled_for    TIMESTAMPTZ NOT NULL,
    run_now          BOOLEAN NOT NULL DEFAULT false,
    state            TEXT NOT NULL CHECK (state IN ('CLAIMED', 'BUILDING', 'SENDING', 'SENT', 'SENT_LINK_ONLY',
                         'FAILED', 'SKIPPED')),
    outcome_code     TEXT CHECK (outcome_code IS NULL OR outcome_code IN ('BUILD_FAILED', 'REPORT_SIZE_CAP',
                         'STORAGE_BUDGET', 'INTERRUPTED', 'SMTP_OFF', 'ROUTE_DISABLED', 'NO_RECIPIENTS',
                         'ATTACHMENT_CAP', 'RELAY_SIZE_LIMIT', 'RELAY_REFUSED', 'RELAY_UNAVAILABLE')),
    attempts         SMALLINT NOT NULL DEFAULT 0 CHECK (attempts BETWEEN 0 AND 3),
    next_attempt_at  TIMESTAMPTZ,
    recipient_count  INTEGER CHECK (recipient_count IS NULL OR recipient_count BETWEEN 0 AND 20),
    attached_count   SMALLINT CHECK (attached_count IS NULL OR attached_count BETWEEN 0 AND 3),
    attached_bytes   BIGINT CHECK (attached_bytes IS NULL OR attached_bytes BETWEEN 0 AND 10485760),
    linked_count     SMALLINT CHECK (linked_count IS NULL OR linked_count BETWEEN 0 AND 3),
    claimed_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at      TIMESTAMPTZ,
    expires_at       TIMESTAMPTZ NOT NULL,
    CONSTRAINT report_delivery_finished CHECK (
        (state IN ('SENT', 'SENT_LINK_ONLY', 'FAILED', 'SKIPPED')) = (finished_at IS NOT NULL))
);
CREATE UNIQUE INDEX IF NOT EXISTS report_delivery_slot ON report_delivery (schedule_id, scheduled_for, run_now);
CREATE INDEX IF NOT EXISTS report_delivery_open ON report_delivery (claimed_at)
    WHERE state IN ('CLAIMED', 'BUILDING', 'SENDING');
CREATE INDEX IF NOT EXISTS report_delivery_expires ON report_delivery (expires_at);

GRANT SELECT, INSERT, UPDATE, DELETE ON report_delivery TO ui2_app;

-- R1's report_run: the trigger of a run, its delivery, and the closed template list widened to R2.
-- A scheduled run is MASKED and a file (never the JSON preview), enforced here as well as in the service.
-- The template CHECK name below is PostgreSQL's default for R1's inline column check; if R1's migration names it
-- differently, use R1's name. The constraint content is what this contract fixes.
ALTER TABLE report_run ADD COLUMN IF NOT EXISTS run_trigger TEXT NOT NULL DEFAULT 'MANUAL'
    CHECK (run_trigger IN ('MANUAL', 'SCHEDULED'));
ALTER TABLE report_run ADD COLUMN IF NOT EXISTS delivery_id UUID;
ALTER TABLE report_run ADD CONSTRAINT report_run_scheduled_masked CHECK (
    (run_trigger = 'MANUAL' AND delivery_id IS NULL)
    OR (run_trigger = 'SCHEDULED' AND delivery_id IS NOT NULL AND projection_mode = 'MASKED' AND format <> 'JSON'));
CREATE UNIQUE INDEX IF NOT EXISTS report_run_delivery_format ON report_run (delivery_id, format)
    WHERE delivery_id IS NOT NULL;
ALTER TABLE report_run DROP CONSTRAINT IF EXISTS report_run_template_id_check;
ALTER TABLE report_run ADD CONSTRAINT report_run_template_id_check CHECK (template_id IN ('RPT-01', 'RPT-02',
    'RPT-03', 'RPT-05', 'RPT-06', 'RPT-07', 'RPT-08', 'RPT-09', 'RPT-10'));

-- notification_routes: same pattern as V113 / V160 §4 / V189. updated_by is required by fn_audit_notification_config.
ALTER TABLE notification_routes DROP CONSTRAINT IF EXISTS notification_routes_type_check;
ALTER TABLE notification_routes ADD CONSTRAINT notification_routes_type_check
    CHECK (type IN ('admin_event', 'login_security', 'backup_failure', 'job_failure',
        'config_change', 'compliance_regression', 'device_health', 'security_scan', 'device_crash_file',
        'backup_size_anomaly', 'scheduled_report'));
INSERT INTO notification_routes(type, enabled, watermark_audit, watermark_time, updated_by)
VALUES ('scheduled_report', false, 0, now(), 'reports-r2-migration')
ON CONFLICT (type) DO NOTHING;
```

### 3.2 Required Java changes for the new route type

These must land with the migration, or the router breaks.
- **`NotificationRoute.TYPES`** gains `"scheduled_report"` at the end.
- **`NotificationMailRouter.TITLES`** gains `"Scheduled reports"` at the same index.
- **`NotificationMailRouter.tick`** skips the type before `collect`
  (`if ("scheduled_report".equals(type)) continue;`). Without that line, an enabled route hits the `default` throw and
  writes `last_error = 'event collection failed'` every minute.
- **`last_error` for this type** is written only by the report delivery (§5), and only from the closed outcome texts.
  It never carries relay reply text: the relay reply could echo addresses.
- **`last_sent_at`** is set on SENT or SENT_LINK_ONLY.
- **Notification settings validation:** `NotificationSettings.problems()` and the settings panel
  (`frontend/src/components/settings/NotificationSettingsPanel.tsx`, `auth/adminApi.ts`) show the type like the others.
  The existing check "enabled route with no effective recipients" applies unchanged.

### 3.3 Classification

| Table.column | Audit handling |
|---|---|
| `report_schedule.name`, `.recipients` | tier 2 redacted (above) |
| every other `report_schedule` column | audited in full: added to `AuditPresentationAllowlist` |
| `report_delivery.*` | not audited by trigger (derived rows). It holds counts only, never addresses. |
| `report_run.run_trigger`, `.delivery_id` | as R1 decides for `report_run`. If R1 audits `report_run` by trigger, these two go into the allowlist. |

`parameters_json` is audited in full because its schema is closed (§4.3): only enums, ISO-8601 durations and opaque
ids, never free text.

## 4. Schedules and cron

### 4.1 The scheduler bean (independent-switch pattern)

`svc/report/ReportScheduleRunner` copies the `SystemLogCollectionScheduler` structure:

```
@Component
@ConditionalOnProperty(name = "ui2.reports.schedule.enabled", havingValue = "true", matchIfMissing = false)
public class ReportScheduleRunner implements SmartLifecycle, DisposableBean
    ACTOR = "system:report-schedule"
    start(): if enabled && independent && executor == null -> single-thread daemon executor
             "report-schedule-runner", scheduleAtFixedRate(this::tick, 0, 1, MINUTES)
    @Scheduled(cron = "0 * * * * *", zone = JobWindowPolicy.ZONE) public void tick()
```

| Property (environment variable) | Code default | Meaning |
|---|---|---|
| `ui2.reports.schedule.enabled` (`UI2_REPORTS_SCHEDULE_ENABLED`) | `false` | The bean exists. Without it no schedule fires; schedules can still be created, edited and run now (§4.5). |
| `ui2.reports.schedule.independent` (`UI2_REPORTS_SCHEDULE_INDEPENDENT`) | `false` | The bean ticks on its own executor while the global `ui2.scheduling.enabled` (`UI2_SCHEDULING_ENABLED`) is false. |

| `UI2_SCHEDULING_ENABLED` | `..._SCHEDULE_ENABLED` | `..._INDEPENDENT` | Effect |
|---|---|---|---|
| any | false | any | Nothing fires. The Schedules tab shows "Scheduler off in this deployment". |
| true | true | false | Fires through `@Scheduled`. |
| false (development mode) | true | false | Nothing fires: the `@Scheduled` method is not registered. The tab says so. |
| false (development mode) | true | true | Fires on the bean's own executor. |
| true | true | true | Both paths call `tick()`. A slot still builds once (§4.4). |

**Why the independent mode is safe in development mode.** Development mode switched off scheduled *device* work:
discovery, inventory, configuration, readiness and backups. A report touches no device, needs no job window and no
endpoint admission (R1). Running schedules while device schedules are off is therefore allowed. Every report shows its
data age, so a report built from paused collections says "stale", never "fresh".

**Deployment step (decision point at freeze, §11 D1).** The R2 deploy sets both variables to `"true"` in
`deploy/ui2/50-service-deployment.yaml`, with a comment like the system-log one. Mail still stays off until a
`security_admin` enables the `scheduled_report` route (§5.1).

**Scheduler status is visible.** `GET /api/v2/reports/schedules` returns `scheduler: {state: OFF | GLOBAL |
INDEPENDENT}`, computed from the three properties. The UI never shows a "next run" as if it will happen when the
state is `OFF`.

### 4.2 Cron evaluation in Europe/Istanbul

- **Form.** Exactly five fields (minute, hour, day of month, month, day of week), as backup policy cron. Rejected:
  - six-field (seconds) expressions;
  - macros (`@weekly` and others; Spring would accept them, the schedule API does not);
  - expressions longer than 64 characters.
- **Evaluation.** `BackupScheduleDue.dueSlot(cron, lastSlot, now, catchUp, ZoneId.of(JobWindowPolicy.ZONE))`, unchanged.
  - The class moves to a shared package, or a public delegate is added; its behaviour does not change.
  - `ZoneId` is always the named zone, never a fixed offset. Europe/Istanbul has no daylight saving time under the
    current tzdata, so DST behaviour is not load-bearing today. A test pins what `CronExpression.next` does across a
    DST change in a zone that has one, so a tzdata change cannot surprise us silently.
- **Minimum interval.** A cron is refused when any two consecutive occurrences in the next 7 days (Istanbul) are less
  than 60 minutes apart, or when it has no occurrence in the next 366 days.
- **Catch-up window: 6 hours.**
  - A slot missed because the service was down is fired once, late, if it is at most 6 hours old. The report and the
    e-mail state both "scheduled for" and "generated at".
  - Older missed slots are not run. The Schedules tab shows `last_slot_at`, so a gap is visible.
- **Stored and displayed times.** `scheduled_for` and `last_slot_at` are stored as `TIMESTAMPTZ`. They are displayed
  in Istanbul time with UTC alongside, as R1's header does.
- **Cron preview.** `POST /api/v2/reports/schedules/cron-preview` returns the next 5 occurrences (Istanbul and UTC) or
  the refusal reason. It is pure computation.

### 4.3 Schedule parameters (closed schema per template)

- **Relative date ranges only.** `range` takes one of `P1D`, `P7D` or `P30D`, measured back from `scheduled_for`.
  No absolute dates, because a schedule must stay meaningful as time moves on.
- **Per-template keys:**

| Template | Keys |
|---|---|
| RPT-02 | `framework` (R1 list) |
| RPT-06 | `policy_ids` (1..20 opaque `policy_snapshot.policy_id`), `hit_days` (one of 30/90/180) |
| RPT-07 | `policy_ids` (0..20; empty = all), `include_initial` = false only |
| RPT-08, RPT-09, RPT-10 | `vendor` (optional, closed vendor list) |

- **Unknown keys** are refused (400), never ignored.
- **Formats:** 1 to 3 of PDF, XLSX, CSV. For RPT-06, RPT-07, RPT-08 and RPT-10, CSV carries only the report's main
  register table (as R1 does for CSV).
- **Limits:**
  - at most 50 schedules in total, and at most 20 enabled;
  - at most 20 recipients per schedule (the same syntax check as `NotificationSettings`), stored like
    `notification_routes.recipients`.

### 4.4 Firing a slot exactly once

Each `tick()` does the following.
1. Return at once if another `tick()` of this process is running (in-process `AtomicBoolean`). This covers the
   "both paths tick" row of the table in §4.1.
2. Open a short transaction and take `pg_try_advisory_xact_lock(<REPORT_SCHEDULE_LOCK>)`. Use a new constant, distinct
   from the forwarder's `10010051` and registered next to it. If the lock is not taken, return.
3. For each enabled schedule, compute `dueSlot`. If there is a slot:
   - `INSERT INTO report_delivery (...) ON CONFLICT (schedule_id, scheduled_for, run_now) DO NOTHING`, with state
     CLAIMED and `expires_at = claimed_at + 30 days`;
   - set `report_schedule.last_slot_at` to the slot.

   Only the inserting tick builds. The unique index is what guarantees one build per slot, across ticks, executor
   paths and restarts.
4. **Builds** run outside the tick, on R1's bounded report executor.
   - Scheduled builds use **at most one** of its two slots, so interactive users are never fully blocked.
   - The schedule's formats are built **from one document model and one masking pass**: one `report_run` per format,
     `run_trigger = 'SCHEDULED'`, `projection_mode = 'MASKED'`, `requested_by = ACTOR`.
5. **Interrupted work.** A delivery still CLAIMED, BUILDING or SENDING 30 minutes after `claimed_at` is closed as
   FAILED / INTERRUPTED. It is not rebuilt.
6. **Storage budget.** If the sum of `plaintext_bytes` of unexpired `report` artefacts exceeds
   `ui2.reports.storage-budget-bytes` (`UI2_REPORTS_STORAGE_BUDGET_BYTES`, default 5 GiB), the delivery is SKIPPED /
   STORAGE_BUDGET and nothing is built. Manual runs are not affected by this budget (R1's per-file cap still applies).
7. **Report size cap.** A format over R1's 25 MiB cap fails that run (R1 rule). The delivery is FAILED /
   REPORT_SIZE_CAP if no format succeeded. Otherwise it proceeds with the formats that succeeded and lists the failed
   format in the mail body.
8. **Retention sweep.** The same tick runs the retention sweep of §7 once an hour. Retention therefore does not depend
   on the global `@Scheduled` switch.

### 4.5 Run now

`POST /api/v2/reports/schedules/{id}/run-now` (`report_schedule_manage`) claims a delivery with `run_now = true` and
`scheduled_for = now()`, then follows §4.4 steps 4 to 7 and §5. This is how a `security_admin` tests a schedule. It
works even when the scheduler is OFF, because nothing has to tick to start it.

## 5. E-mail delivery

### 5.1 Who receives what, and when nothing is sent

**Recipients** are taken in this order:
1. `report_schedule.recipients`, if set;
2. otherwise the `scheduled_report` route's `effectiveRecipients`: the route's own recipients, else the global
   `smtp_to`.

**Skips.** The checks below run in order after a successful build. Each sets SKIPPED with the outcome code shown. The
report is still in History either way; only the mail is skipped.

| Check | Outcome code |
|---|---|
| `notification_settings.smtp_enabled` is false or no relay host | `SMTP_OFF` |
| `scheduled_report` route disabled | `ROUTE_DISABLED` |
| no effective recipients | `NO_RECIPIENTS` |

The mail is sent by the report delivery itself, not by `NotificationMailRouter`. The router runs only under the global
`@Scheduled` switch (§2), so routing report mail through it would be silent in development mode.

### 5.2 `SmtpRelaySender` multipart extension (no new mail library)

**New overload:**
`send(NotificationSettings settings, List<String> recipients, String subject, String body, List<Attachment> attachments)`
with `record Attachment(String fileName, String contentType, byte[] bytes)`.

**Behaviour:**
- **Unchanged without attachments.** With an empty list, the bytes on the wire are exactly today's `text/plain`
  message; only `Date` and `Message-ID` differ between any two messages. All existing callers keep the existing
  overloads.
- **Message layout with attachments** (RFC 2045/2046, JDK only):

  ```
  MIME-Version: 1.0
  Content-Type: multipart/mixed; boundary="=_nexus_<32 hex>"

  --=_nexus_<32 hex>
  Content-Type: text/plain; charset=UTF-8
  Content-Transfer-Encoding: 8bit

  <body>
  --=_nexus_<32 hex>
  Content-Type: application/pdf
  Content-Disposition: attachment; filename="nexus-RPT-01-20261109T0730-masked.pdf"
  Content-Transfer-Encoding: base64

  <java.util.Base64.getMimeEncoder(76, CRLF) output>
  --=_nexus_<32 hex>--
  ```

- **Boundary.** `=_nexus_` plus a random UUID in hex. `_` is outside the base64 alphabet, so the boundary cannot occur
  in an attachment. The sender also asserts the boundary does not occur in the text body.
- **Text part encoding.** The text part keeps `8bit`, like today's digests. The relay behaviour for `8bit` is
  therefore unchanged and already proven.
- **Content types:**
  - PDF: `application/pdf`;
  - XLSX: `application/vnd.openxmlformats-officedocument.spreadsheetml.sheet`;
  - CSV: `text/csv; charset=UTF-8`.
- **File names** are ASCII only: `nexus-<template>-<yyyyMMdd'T'HHmm Istanbul>-masked.<ext>`. No RFC 2231 encoding is
  needed. They never contain the schedule name or any estate name.
- **Dot-stuffing.** The existing per-line rule applies to the whole DATA payload.
- **Reply codes.** The sender throws a typed `SmtpReplyException(int code)` (a subclass of `IOException`, so existing
  callers are unchanged). The delivery can then tell a size refusal (552, or 554 issued at end of DATA) from other
  failures.
- **SIZE (should).** When the EHLO reply advertises `SIZE n` and the encoded message is larger than `n`, the sender
  refuses before DATA with `SmtpReplyException(552)`. This needs `Session.expect` to keep the EHLO lines; it does not
  change any other reply handling.
- **Unchanged:** authentication (still none), the STARTTLS verification and the 15 s timeouts.

### 5.3 10 MiB attachment cap and link fallback

- **The cap** is `10 * 1024 * 1024` plaintext bytes in total per e-mail, counted before base64. That is about 13.7 MiB
  on the wire. Formats are considered in the fixed order PDF, XLSX, CSV. Each is attached while the running total stays
  within the cap; any format that does not fit becomes a link.
- **Outcomes:**
  - something attached and something linked: SENT with outcome `ATTACHMENT_CAP`;
  - nothing fits: SENT_LINK_ONLY with outcome `ATTACHMENT_CAP`.
- **Relay size refusal.** If the relay refuses the message for size (§5.2), the delivery resends **once** with links
  only: SENT_LINK_ONLY with outcome `RELAY_SIZE_LIMIT`. The relay's actual limit is UNKNOWN until the first such
  refusal (§10 R2).
- **The link** points at the run in Reports › History.
  - It is a relative path (`/reports/history?run=<run_id>`), as today's digests use `/operations/jobs/<id>`.
  - It becomes absolute only when `ui2.reports.public-base-url` (`UI2_REPORTS_PUBLIC_BASE_URL`, `https://` only,
    default empty) is set. neXus has no configured public URL today (UNKNOWN for the deployment).
  - The link needs a signed-in session with `report_read`, and it works only within the 30-day retention.
- **Integrity.** Attachment bytes are read from the encrypted artefact store and decrypted in memory only. They are
  checked against the stored `plaintext_sha256` before attaching, and never written to disk in clear.

### 5.4 Always MASKED

- **The sender cannot be given RAW content.** The delivery builder takes only the R1 masked document type
  (`MaskedReportDocument` or the R1 equivalent) and artefacts whose `report_run.projection_mode = 'MASKED'`. Three
  layers enforce MASKED:
  - the schedule API has no projection parameter;
  - `report_schedule` has no projection column;
  - `report_run_scheduled_masked` refuses a scheduled RAW row.
- **Subject:** `neXus report – <template title> – <yyyy-MM-dd Istanbul>`. Only the fixed template title and a date.
- **Body** is built only from the masked document's summary block:
  - template id and title, range, "scheduled for" and "generated at" (Istanbul and UTC);
  - "data as of" per plane and coverage;
  - the template's headline lines (§8, "mail headline"), with the UNKNOWN share shown wherever the report shows it;
  - each format as `attached (size)` or `link`, plus any format that failed;
  - the fixed footer: "MASKED projection. Names are installation-stable pseudonyms; RAW is available only in neXus to
    authorised roles."
- **What never appears** in the subject, body, file names or attachments: the schedule name, the recipients, any
  actor or any raw estate value.
- **Retries:**
  - on `RELAY_UNAVAILABLE` (connection or timeout) or a 4xx reply: up to 3 attempts in total, at +5 and +15 minutes,
    using the same artefacts with no rebuild;
  - then FAILED / `RELAY_UNAVAILABLE`, or FAILED / `RELAY_REFUSED` for a 5xx reply other than a size refusal.
  - The route's `last_error` gets the fixed text of the outcome code only.

## 6. RBAC and audit

### 6.1 Actions

| Action | Roles | Purpose |
|---|---|---|
| `report_schedule_manage` (new) | `role:security_admin` only, no alternatives. Never `role:replay_viewer`. | Create, update, enable, disable, delete and run-now schedules; see recipients. Registered as `new ActionDescriptor(REPORT_SCHEDULE_MANAGE, true, Optional.of(RoleToken.SECURITY_ADMIN))`, like `NOTIFICATION_CONFIG_WRITE`. |
| `report_read` (R1) | as R1 | List schedules **without recipients** (recipient count only) and list deliveries. |

**Routes** are mapped in `svc/security/SecurityWebMvcConfig.java`. All of them are under `/api/v2/reports/schedules`:

| Route | Action |
|---|---|
| `GET` (collection) | `report_read` |
| `GET /{id}/deliveries` | `report_read` |
| `POST` (collection), `PUT /{id}`, `DELETE /{id}`, `POST /{id}/run-now` | `report_schedule_manage` |
| `POST /cron-preview` | `report_read` |

**Domain reads.** Creating or updating a schedule also requires the caller to hold the template's domain read actions
(§8; R1's rule). `security_admin` holds all of them today.

**Whose authority a scheduled run uses.** A scheduled run executes as `system:report-schedule`, under the authority
of the schedule record. That record was created or last changed by a `security_admin` and is audited.
- The service cannot re-check that this person still holds the role when the slot fires: role bindings can be LDAP
  groups, which are resolved at sign-in. That check is UNKNOWN.
- The mitigation is visibility instead: the tab shows `updated_by` (masked for aiview), and any `security_admin` can
  disable or delete a schedule (§10 R5).

**History visibility (addition to R1).** A History row, manual or scheduled, is listed and downloadable only for a
viewer who holds every domain read action of its template. A scheduled MASKED RPT-06 run is visible to aiview
(`policy_read` includes `role:replay_viewer`) but not to a role without `policy_read`.

### 6.2 Audit

**Schedule changes** are captured by `trg_audit_report_schedule`, with `app.action_id` set by the service to one of
`report_schedule_create`, `report_schedule_update`, `report_schedule_enable`, `report_schedule_disable`,
`report_schedule_delete` or `report_schedule_run_now`. Name and recipients are digested (tier 2).

**Each fired slot** writes one application audit row:
- `report_schedule_fire`: actor `system:report-schedule`, `row_pk` = delivery id, with template, formats and
  `scheduled_for`.
- Each built format then writes R1's generate row. `requested_by` is the system actor; the projection is MASKED.

**Each send attempt** writes one row:
- `report_delivery_send`, with the delivery id, state, outcome code, attempt, recipient **count**, attached count and
  bytes, and linked count.
- Addresses are never written to the audit log, the application log or `report_delivery`.

**Route changes** (`enabled`, `recipients` of `scheduled_report`) are audited by the existing V100 trigger as
`notification_route_update`. They are also already in the `admin_event` digest's action list
(`notification_route_update`).

**Downloads** of a scheduled run follow R1 (one row per download).

## 7. Retention

| Object | Retention |
|---|---|
| Scheduled `report_run` rows and their `report` artefacts | 30 days, exactly R1. The Argon design's 90 days for weekly and monthly reports is **not** adopted, because R1 is frozen at 30. |
| `report_delivery` rows | `expires_at = claimed_at + 30 days`. Deleted by the sweep. |
| `report_schedule` rows | Until deleted. Deleting stops future slots; existing runs and deliveries keep their own expiry. |

**The sweep** runs hourly from the report scheduler tick (§4.4 step 8). It deletes expired `report` artefacts (through
the artefact store), then their `report_run` rows, then expired `report_delivery` rows. Each deletion is audited as R1
requires for retention.
- Expiry is also enforced at read time: a download or link after `expires_at` returns 410, whether or not the sweep has
  run.
- When the report scheduler bean is off, R1's retention mechanism applies.
- Contradiction note (§12 C3): R1 refers to "the retention janitor"; the source has per-class pruners in the worker
  (for example `SystemLogRetentionPruner`, `PacketAnalysisRetentionPruner`) and no generic janitor.

## 8. The five new reports

### Common rules for all five

- Each builder produces R1's document model: header, "data as of", coverage and sections. The single masking pass then
  runs before any renderer, exactly as in R1.
- **UNKNOWN share** is always shown as `n of N (p %)` with numerator and denominator.
  - It is never subtracted out of a denominator to make a rate look better.
  - When a section's UNKNOWN share is above 50 %, its headline is "Mostly unknown: …". No pass-like or clean
    statement is made from the known remainder.
  - A count of zero findings is written as "none found among the n evaluated", never "none".
- **"Not collected"** (a read deliberately not sent) and **"collection failed"** stay separate categories from UNKNOWN
  where the source distinguishes them, and from each other.
- **Evidence grade** is labelled per section: direct device read, manager-derived, or computed. Manager-derived data
  never stands in for a direct read.
- **Empty planes.** When a plane has no rows, the section still renders, with `NO_EVIDENCE_IN_WINDOW` and the plane
  named. A silent 0 % or 100 % is never shown.

### 8.1 RPT-06 Policy hygiene

**Question.** For the selected policies, which rules are proven redundant, conflicting, disabled, unused, expired or
broad, and how many rules could not be proven?

**Domain read.** `policy_read`.

**Data:**
- `PolicyQueryService.find(id)` and `PolicyQueryService.hygiene(snapshot, page, "", "", days)` (public).
  - Per policy it gives `counts` per class (`PolicyHygiene.CLASSES`: shadowed, conflict, disabled, unused, expired,
    broad, unknown).
  - `coverage {rules, shadowProven, shadowUnknown, hitsCollected, hitsMissing}`.
  - `unknownReasons [{evidence, rules}]`, `budgetReached`, `blockers` and the rule rows (200 per page).
- Tables: `policy_snapshot` (V115) and `cp_policy_object_inventory` (V124/V126).
- Object hygiene: `PolicyQueryService.domainObjects(..., hygiene = unused|duplicates, masked)` and `objectDuplicates`.
- The builder lives in `svc/policy` as a small public adapter, because `analysis(...)` and `PolicyRuleMetrics` are
  package-private. It goes through `analysis`, so it keeps that method's single-flight cache, `Semaphore(2)` and work
  budgets (`RULE_WORK = 1_000_000`, `TOTAL_WORK = 200_000_000`).
- No fleet-wide hygiene is persisted anywhere. RPT-06 is therefore bounded to **1 to 20 explicitly chosen policies**,
  analysed sequentially, with a 10-minute build deadline. Past the deadline the remaining policies are listed as "not
  analysed (time budget)", never dropped.

**Sections:**
1. **Honesty banner**, per policy and in total:
   - rules analysed;
   - **shadow UNKNOWN share** `shadowUnknown / rules`;
   - **hit evidence missing share** `hitsMissing / rules`;
   - `budgetReached` stated when true;
   - the snapshot's `collected_at`;
   - the label "management policy snapshot: configured intent, not proof of enforcement".
2. **UNKNOWN reasons:** `unknownReasons` as returned (fixed strings from `PolicyHygiene.REASONS` and `EVIDENCE`),
   with counts.
3. **Findings by class**, per policy. Count of rules per class, severity split (HIGH/MEDIUM/LOW). The unknown class is
   shown as its own column, never merged.
4. **Top blockers:** `blockers` (rule numbers and counts only).
5. **Object hygiene**, Check Point domains only, where `cp_policy_object_inventory` exists:
   - unused objects; an object whose `unused` flag is null is UNKNOWN, with reason `UNUSED_NOT_COLLECTED` where given;
   - duplicate groups.
6. **Rule register:** rule rows with class, severity, evidence, covering rule, counter window, hit source and
   collected-at. These are the existing `hygiene.csv` columns, and the CSV format of RPT-06 is this table.

**Honesty rules:**
- "No shadowed rules" may be said only when `shadowUnknown = 0` and `budgetReached = false`. Otherwise the text is
  "n proven redundant; m rules not provable".
- `unused` is reported only with its counter window and hit source. Rules whose hit evidence is missing are counted
  under hit evidence missing, never as unused.
- When the shadow UNKNOWN share is above 50 %, the report-level verdict is **MOSTLY UNKNOWN**.

**Mail headline.** Policies analysed, rules, shadow UNKNOWN share, and proven findings by class.

**Release gate.** The engineer decision of 2026-11-05 says RPT-06 should ship once the UNKNOWN share drops ("about 85
%" then; not re-measured here, no live access). RPT-06 is built in R2c (§9). Whether its catalogue card is enabled
while the measured fleet share is above 50 % is decision D3 (§11).

**Tests:**
- builder on synthetic snapshots (FW-TANGO-04 policies):
  - all-proven;
  - mixed UNKNOWN;
  - `budgetReached`;
  - missing hit evidence;
  - the >50 % MOSTLY UNKNOWN verdict;
- the "no shadowed rules" wording appears only when it is proven;
- the time-budget path lists the policies it did not analyse;
- byte-level masking of the PDF/XLSX/CSV, with rule names, object names and IP literals masked to `192.0.2.x`;
- `policy_read` refusal.

### 8.2 RPT-07 Policy change

**Question.** Which rules were added, modified or removed in the period, by whom (as collected), and what is the
installation state per gateway as the source records it?

**Domain read.** `policy_read`.

**Data:**
- `PolicyChangesService.changes(Request, masked)` and `.csv(Request, masked, out)`.
  - `Request(from, to, policy, device, actor, changeType, field, q, cursor, limit, includeInitial = false)`.
  - It reads `policy_rule_history` (V120, `initial_capture` from V150) through `PolicyChangeRepository.rows` and
    `summary`.
  - `change_type` is one of added, removed, modified. `FIELDS` are as in source.
- Installation state, where it exists (no new comparison):
  - Check Point: `PolicyQueryService.installations(...)`, from `cp_policy_object_inventory` `gateways-and-servers`.
    Per gateway: targeted, installed true / false / null; null is UNKNOWN.
  - Palo Alto: target `syncStatus` (IN_SYNC, OUT_OF_SYNC, UNKNOWN), as collected.
  - Per device: `device_policy_install` (V60), giving `policy_name`, `installed_at` (NULL when unparseable =
    UNKNOWN), `observed_at`. This is the latest observation only, not history.

**Sections:**
1. **Change summary:** counts by change type and by policy in the range, with `initialCaptures` stated separately
   (excluded).
2. **Change register:** the `changes.csv` columns (Time, Observed at collection, Policy, Devices, Rule number, Rule
   name, Change type, Actor, Field, Before, After, Collected at). Paged through the cursor up to 20 000 rows; above
   that the register says "truncated at 20 000 of N" (the summary stays complete).
3. **Actor coverage:** the share of changes whose actor is `UNKNOWN (observed by collection)`, and the share whose time
   is only the collection time (`observedAtCollection`).
4. **Installation state:** the three sources above, each labelled with its own evidence grade and age. Check Point
   targets in the snapshot carry `syncStatus` `UNKNOWN` by construction (collector), which is stated.

**Not in R2.** A computed "management revision vs installed policy" alignment. No code computes it today. Joining
`device_policy_install.policy_name` to a snapshot by name would use a presentation name as a join key (AGENTS.md
evidence and identity laws). It needs its own contract.

**Honesty rules:**
- An empty change list over a range where the policy had no snapshot collection is "no collection in range", not "no
  changes": compare the snapshot `collected_at` with the range.
- Unknown actors and collection-time timestamps are always counted and shown.

**Mail headline.** Added, modified and removed counts; the UNKNOWN actor share; gateways with installed false or
UNKNOWN.

**Tests:**
- `includeInitial` is always false;
- the truncation statement;
- the "no collection in range" path;
- the UNKNOWN actor share;
- installation null shown as UNKNOWN;
- masking of actors, policy names, gateway names and before/after values;
- `policy_read` refusal.

### 8.3 RPT-08 Configuration change and drift

**Question.** Which devices' configurations changed in the period, how much (major or minor), which devices could not
be compared and why, and which clusters show member differences?

**Domain reads.** `device_read`, `device_backup_read` and `policy_read` (change feed). `system_log_read` is optional:
without it the `pan_config` and `cp_cpwd_elg` feed sources report `NOT_PERMITTED`, as the feed does today.

**Data:**
- `BackupDeviationService.evaluate()` returns `Result(evaluatedAt, window, devicesKnown, compared, notCompared,
  activeMajor)`.
  - `DeviceClass` is MAJOR, MINOR or NONE.
  - `NotComparedReason` is NO_READ_IN_WINDOW, FIRST_READ_ONLY, NOT_COMPUTABLE or VENDOR_NOT_CATEGORISED.
  - `MajorDeviationRules.Category` is INTERFACES, ROUTING, ACCESS_RULES, NAT, ADMIN_ACCESS or HIGH_AVAILABILITY.
  - Its window is the fixed `WINDOW = 7 days`. This section therefore always covers the last 7 days before generation,
    whatever the report range, and says so.
- `device_configuration_run` (V16), for `change_state` changed / unchanged / first_run, `read_kind` and
  `collected_at`.
- `configuration_run_deviation_summary` (V22, `status` COMPUTED or NOT_COMPUTABLE).
- `configuration_run_deviation_entry` (V22/V176: context, section, source, `kind` ADDED / REMOVED / RECOUNTED /
  MODIFIED, `old_count`, `new_count`). Sections and counts only, never configuration lines.
- `cluster_member_diff` (V52), the latest row per cluster: `comparable`, `diff_section_count`, `diff_setting_count`,
  `diff_sections`, `computed_at`.
- `ChangeFeedService.feed(Request, masked, Permissions, now)`: max range 31 days, 200 per page.
  - Its sources are exactly `policy_rule_history`, `pan_config`, `device_policy_install`, `policy_change_evidence`
    (USER); `jobs` (NEXUS_JOB); `cp_cpwd_elg` and `device_crash_alert` (DEVICE_EVENT).
  - Coverage state per source is OK, PARTIAL, UNAVAILABLE, NOT_PERMITTED or OFF.
  - The feed has **no configuration-run source**. Configuration changes come from the tables above, not from the feed.

**Sections:**
1. **Deviation summary (7 days):** devices MAJOR, MINOR and NONE, plus **not compared** by reason with counts.
   `devicesKnown` is the denominator, and not-compared devices stay in it.
2. **Changed devices:** per device, the change count in the range, the major categories, and the changed sections with
   entry-count deltas (`deviation_entry`). A `NOT_COMPUTABLE` summary is shown as such.
3. **Cluster member parity:** per cluster, comparable yes or no.
   - Non-comparable clusters count as **UNKNOWN** (as on Overview). Their zero diff counts are never shown as "no
     difference".
   - The fixed caveat is printed: member-specific settings (Check Point `CP_MEMBER_SPECIFIC`, Palo Alto
     `PAN_MEMBER_SPECIFIC` in `ConfigurationProjection`) count as a difference only when missing on a member, not when
     values differ.
   - The age of `computed_at` is shown. `ClusterDiffTask` is `@Scheduled`, so in development mode these rows can be
     old, and they are labelled stale.
4. **Correlated timeline:** feed events in the range (USER, NEXUS_JOB, DEVICE_EVENT), with each source's coverage state
   printed. OFF, UNAVAILABLE and NOT_PERMITTED are shown, never omitted.

**Honesty rules:**
- **Not-compared share** = `notCompared / devicesKnown`. **Non-comparable cluster share** = non-comparable /
  clusters.
- Both follow the 50 % rule.
- "No change" is said only for devices with at least two comparable reads in the window.

**Mail headline.** Major and minor device counts; not-compared count and share; clusters with differences; UNKNOWN
clusters.

**Tests:**
- the 7-day window statement when the range differs;
- not-compared reasons stay in the denominator;
- a non-comparable cluster with zero counts is rendered UNKNOWN;
- the member-specific caveat is present;
- the stale `computed_at` label;
- each feed coverage state is rendered, including `NOT_PERMITTED` without `system_log_read`;
- no configuration text appears in any output byte;
- masking of device, cluster, context and section names;
- domain-read refusals.

### 8.4 RPT-09 Lifecycle / end of life

**Question.** Which devices are past or near end of support for hardware or software, and for how many is lifecycle
data missing?

**Domain read.** `device_read`.

**Data:**
- `LifecycleService.fleet(LocalDate today)` (Istanbul date of generation) and `LifecycleService.summary(rows)`.
  - Keys: devices, past_end_of_support, within_180_days, within_365_days, no_lifecycle_data, licenses_within_60_days.
- Per-device values from `LifecycleProjection`:
  - `status` MATCHED or NO_LIFECYCLE_DATA (MATCHED only when both hardware and software match);
  - `risk` EXPIRED (<0 days), HIGH (<180), MEDIUM (<365), LOW (both support dates known), else UNKNOWN;
  - `support_days` and `next_milestone`, each possibly null;
  - match `reason` ("Ambiguous catalog matches", "Model/version missing or software train unsupported", "No catalog
    match");
  - `license_status` NOT_COLLECTED, UNKNOWN or COLLECTED (Infoblox only);
  - `support_contract_status` always NOT_COLLECTED.
- Tables:
  - `lifecycle_catalog` (V144, which ships empty and is filled by import or manual entry);
  - `devices.observed_model`, `observed_software_version` (V12);
  - `infoblox_grid_summary.licenses` from the latest `device_inventory_run`;
  - `device_platform_facts.hotfix_level` (V46).
- **Lifecycle does not read `hotfix_level`.** The hotfix section reads it directly, without judging it.
- **No new thresholds.** The 180- and 365-day bands are `LifecycleProjection`'s own.

**Sections:**
1. **Catalog coverage banner:** catalog row count, and `no_lifecycle_data` as `n of N (p %)`. When `lifecycle_catalog`
   is empty, the whole report verdict is **NO LIFECYCLE DATA**, and no device is described as supported.
2. **Risk buckets:** EXPIRED, HIGH, MEDIUM, LOW, UNKNOWN, NO_LIFECYCLE_DATA. Device counts, separated by hardware and
   software where the projection gives them.
3. **Milestone table:** per device: model, software version, matched catalog dates (end of sale, end of engineering,
   end of support) with `source` (IMPORT or MANUAL), days remaining, and next milestone. Null values are written
   UNKNOWN.
4. **Not collected:** the fixed statement that support contracts are not collected for any vendor, and that licences
   are collected only for Infoblox (with the `licenses_within_60_days` count).
5. **Software and hotfix distribution:** groups of (vendor, model, software version, hotfix level). A null
   `hotfix_level` is UNKNOWN. This is descriptive only, with no "standard" or "compliant" judgement.

**Honesty rules:**
- NO_LIFECYCLE_DATA and UNKNOWN risk are never folded into LOW.
- The 50 % rule applies to `no_lifecycle_data / devices`.

**Mail headline.** Expired, HIGH and MEDIUM counts; no-lifecycle-data count and share.

**Tests:**
- the empty-catalog verdict;
- a mixed catalog with each risk value;
- a device with model or version missing;
- the Infoblox licence path versus NOT_COLLECTED;
- null hotfix rendered as UNKNOWN;
- generation date taken in Istanbul;
- masking of hostnames and cluster refs, while models, versions and dates stay visible;
- `device_read` refusal (only an unauthenticated request is refused, since `device_read` is open to any session).

### 8.5 RPT-10 Inventory

**Question.** What does the estate consist of (devices, roles, clusters, virtual systems, interfaces, routes), and how
fresh is each device's inventory read?

**Domain reads.** `device_read` and `system_status_read` (Overview age buckets).

**Data:**
- Device and inventory tables:
  - `devices`: role is gateway, management_server or appliance (V21/V65); `enrollment_state` is DRAFT, ENROLLED,
    UNREACHABLE or DEGRADED; `disabled` (V3); `cluster_member_ref`, `virtual_system_ref`, `observed_*` (V12);
    `backup_target` (V39).
  - `device_inventory_run` (V13; `virtual_systems` V29; `not_collected` V171).
  - `device_interface` (V13; `vlan_id` V20; `zone` and `mac_address` V148; `routing_context` and `evidence_grade`
    V184). `kind` and `state` are up, down or unknown.
  - `device_interface_address` (V13; `family`, `role` member or cluster_virtual).
  - `device_route` (V13; `protocol` set; V184 `is_active`, `is_ecmp`, `metric`, `next_hop_kind`, where NULL =
    UNKNOWN).
  - `device_inventory_ha` (V20; `role`, `cluster_mode`, `source`).
- Service views:
  - `InventoryQueryService.deviceInventory(id)` and `.clusterInventory(ref)`;
  - `ClusterInventoryMerger.merge(...)` (computed, not stored);
  - `OverviewService` `inventoryAge` buckets `lt24h`, `h24_72`, `gt72h`, `never` (over ENROLLED devices);
  - `ManagementTreeService` counts (domains, clusters, gateways, management_appliances, virtual_systems, imported,
    not_in_nexus, acknowledged), which are **manager-derived**.

**Sections:**
1. **Estate summary:** enrolled devices by vendor, role and enrollment state; disabled devices listed separately; and
   inventory age buckets.
2. **Clusters and virtual systems:**
   - clusters with members (`cluster_member_ref`) and HA facts per member (`device_inventory_ha`, with its `source`);
   - virtual systems: the stored `virtual_systems` value per device is shown masked, and **no count is derived from
     that free text**;
   - the manager-derived virtual-system count from `ManagementTreeService` is labelled manager-derived.
3. **Interfaces and routes (counts only):**
   - per device, from its latest run: interfaces by kind and state;
   - routes by protocol, with active / inactive / UNKNOWN (`is_active` NULL);
   - the count of interfaces whose `evidence_grade` is CONFIG_DERIVED versus RUNTIME.
4. **Not collected:** per device, the `not_collected` read kinds. What those reads would provide is UNKNOWN, not
   empty.
5. **Managed but not in neXus:** the `not_in_nexus` and `acknowledged` counts per manager, labelled manager-derived.

**Not in R2:**
- Per-route and per-address listings, because of size and sensitivity. Counts only.
- The design's `excluded_from_inventory` register: **that column does not exist**.
- Serial numbers in any projection.

**Honesty rules:**
- A device with no inventory run is in `never`, not omitted.
- Unknown interface state and route activity are their own columns.
- The 50 % rule applies to `never + gt72h` over enrolled devices.

**Mail headline.** Enrolled devices; age buckets; clusters; devices with not-collected reads.

**Tests:**
- age buckets including `never`;
- disabled devices separated;
- no virtual-system count derived from free text;
- the manager-derived label;
- NULL V184 columns rendered UNKNOWN;
- `not_collected` rendering;
- byte-level masking of hostnames, cluster refs and any address in the output (`192.0.2.x`);
- domain-read refusals.

## 9. Phasing

| Step | Content | Depends on |
|---|---|---|
| **R2a: schedules and delivery** | Migration (§3), `ReportScheduleRunner` (§4), schedule API and Schedules tab, `SmtpRelaySender` multipart (§5.2), delivery (§5), the route type wiring (§3.2), retention sweep (§7), RBAC and audit (§6). Ships with the four R1 reports as the only schedulable templates. | R1 merged (`report_run`, executor, masking pass, renderers, artefact class) |
| **R2b: RPT-09, RPT-10, RPT-08** | The three lower-risk reports: cheap queries, existing services. | R2a for scheduling; R1 for the on-screen and file paths |
| **R2c: RPT-07, then RPT-06** | Policy reports. RPT-06 last, behind decision D3. | R2b |

**Definition of done for each step:**
- the tests in §8 and §10;
- `:service:test :persistence:test :architecture-tests:test`;
- frontend vitest and tsc;
- the HTML render harness where UI modules change;
- the repository privacy gate.

**Status rules:**
- **R2a reaches REAL_ENV_VALIDATED** only after the engineer, as aiview:
  - creates a schedule;
  - observes one fired slot in development mode (independent mode on);
  - receives the MASKED e-mail through the corporate relay;
  - checks that the attachments open and are masked;
  - triggers the link fallback once with an oversize report.
- **R2b and R2c** each need every new report generated once on the live estate as aiview.

## 10. Tests, risks and mitigations

### 10.1 Tests for R2a

**Migration:**
- applies on top of R1's migration;
- the `notification_routes` CHECK has all 11 types;
- the `scheduled_report` row is seeded disabled with `updated_by` set, and its audit row exists;
- `report_run_scheduled_masked` refuses a SCHEDULED RAW row and a SCHEDULED JSON row;
- `report_delivery_slot` refuses a second claim of the same slot;
- `AuditPresentationAllowlistMatchesLiveSchemaTest` stays green.

**Router:**
- an enabled `scheduled_report` route never reaches `collect`;
- `last_error` stays null across ticks;
- `TYPES` and `TITLES` have the same length.

**Scheduler (copy the `SchedulingConfigurationTest` and `SystemLogCollectionScheduler` test shapes):**
- no bean when disabled;
- `@Scheduled` registered only under the global switch;
- the executor starts only when enabled and independent;
- with both paths active, one build per slot;
- the scheduler status (OFF, GLOBAL, INDEPENDENT) is reported.

**Cron:**
- five fields only, with macros and seconds refused;
- minimum interval;
- catch-up at 6 h: a 5 h-old slot fires, a 7 h-old slot does not;
- Istanbul evaluation;
- the pinned DST behaviour in a DST zone;
- `cron-preview`.

**Delivery:**
- the skip order (SMTP_OFF, ROUTE_DISABLED, NO_RECIPIENTS);
- recipient precedence;
- attachment selection under the 10 MiB cap in the order PDF, XLSX, CSV;
- link-only;
- one link-only resend on 552;
- retry schedule and limit;
- INTERRUPTED closing after 30 min;
- STORAGE_BUDGET skip;
- `last_error` carries only fixed texts.

**`SmtpRelaySender` (against a local fake relay):**
- byte-identical text/plain output with no attachments, apart from Date and Message-ID;
- multipart structure parses with a MIME parser in test scope only;
- base64 round-trip equals the artefact bytes;
- dot-stuffing;
- the boundary is absent from the body;
- a SIZE advertisement triggers the refusal before DATA;
- `SmtpReplyException` codes.

**Masking:**
- the full MIME message of each template (subject, body, decoded attachments) contains none of the synthetic raw
  fixture values;
- the schedule name and the recipients never appear in the message.

**RBAC:**
- aiview and every non-`security_admin` role are refused all manage routes;
- the list hides recipients without `report_schedule_manage`;
- History visibility follows the template's domain reads.

**Audit:**
- one `report_schedule_fire` row and one `report_delivery_send` row per attempt, with no address in any audit or log
  line;
- the schedule trigger captures with name and recipients digested.

**Retention:**
- the sweep deletes artefacts, runs and deliveries after 30 days;
- 410 at read time after expiry.

### 10.2 Risks

| # | Risk | Mitigation |
|---|---|---|
| R1 | The e-mail leaves the appliance boundary. | Always MASKED, enforced in three layers (§5.4). The route is seeded disabled. Recipients are set by `security_admin` only and audited. Byte-level tests on the whole message. |
| R2 | The corporate relay's message-size limit is UNKNOWN. | 10 MiB plaintext cap, the SIZE check, and one link-only resend on a size refusal (§5.3). The first real delivery is part of REAL_ENV_VALIDATED. |
| R3 | Pseudonyms are installation-stable, so a recipient who also has RAW access in neXus can re-identify them. | This is intended (same as aiview). Only roles with `report_raw_view` see RAW, in-app. The mail footer says so. |
| R4 | Development mode: reports built from paused collections look current. | Every report carries per-plane data age and stale labels (R1). RPT-08 states the age of `cluster_member_diff`. Independent mode is a deliberate deployment step (D1). |
| R5 | A schedule outlives its creator's role. LDAP-bound role membership cannot be checked offline (UNKNOWN). | `updated_by` is visible on the tab. Any `security_admin` can disable it. Only MASKED leaves. Possible later step: a review date. |
| R6 | CPU cost of RPT-06 (up to about 5 CPU s per snapshot per source comment; up to 20 snapshots). | The shared `Semaphore(2)` and single-flight cache, one scheduled build slot out of two, a 10-minute deadline and sequential analysis. Heavy builds move to the analysis container when it lands (engineer decision 6). |
| R7 | Storage growth from many schedules. | Limits of 50 schedules / 20 enabled and a 5 GiB budget (STORAGE_BUDGET skip), plus 30-day retention. |
| R8 | Double fire under both tick paths, or after a restart. | Unique claim index plus advisory lock plus in-process guard. Missed slots older than 6 h are not replayed. |
| R9 | Header or SMTP injection through recipients or the subject. | The recipient syntax excludes whitespace, `<`, `>` and `"`, so CR and LF are excluded. The subject is a fixed title and a date. File names are generated ASCII. |
| R10 | RPT-06 is mostly UNKNOWN today (engineer estimate of about 85 %, not re-measured). | The MOSTLY UNKNOWN verdict and decision D3. |

## 11. Decisions requested at freeze

- **D1.** Should the R2a deploy set `UI2_REPORTS_SCHEDULE_ENABLED=true` and `UI2_REPORTS_SCHEDULE_INDEPENDENT=true` in
  the service manifest while development mode lasts? Recommended: yes (§4.1). Mail still needs the route enabled.
- **D2.** Is `ui2.reports.public-base-url` set at deploy, so e-mail links are absolute? Recommended: yes, if the PO
  names the URL; otherwise links stay relative.
- **D3.** Is the RPT-06 catalogue card enabled while the measured fleet shadow UNKNOWN share is above 50 %?
  Recommended: enabled, with the MOSTLY UNKNOWN verdict, because hiding it hides the gap. Alternative: build it in R2c
  and enable it later.
- **D4.** The limits: 50 / 20 schedules, 20 recipients, a 5 GiB budget, 6 h catch-up and a 1 h minimum interval.
  Recommended as written.

### 11.1 Engineer decisions (2026-11-05; PO delegated design calls)

- **D1: yes.** The R2a deploy sets both variables to `"true"` in the service manifest. Manifest env changes are not
  applied by the deploy, so the ship is followed by `kubectl set env` on the service deployment.
- **D2: yes.** `ui2.reports.public-base-url` is `https://ui2.nexus.local`.
- **D3: yes.** The RPT-06 card is enabled with the MOSTLY UNKNOWN verdict while the measured share is above 50 %.
- **D4: accepted** as written.

These decisions do not freeze this contract; the status line above still governs.

## 12. Contradictions and corrections (reported, not silently reconciled)

- **C1. Argon design vs source** (corrected here):
  - `notification_routes` has `type` / `recipients`, not `event_type` / `recipient_addresses`, and no `event_type`
    CHECK.
  - Inventory tables are `device_interface`, `device_route` and `device_inventory_ha`, not `device_inventory_interface`,
    `device_inventory_route` and `device_inventory_ha_fact`.
  - `devices.excluded_from_inventory` does not exist.
  - `ChangeFeedService` has no configuration-run source.
  - Lifecycle does not read `hotfix_level`.
  - No code computes management-to-gateway installation alignment.
  - The 90-day retention is not adopted (R1 says 30).
- **C2. R1's "ActionClass" labels.** R1 labels `report_read` and `report_generate` CLASS_0 and `report_raw_view`
  CLASS_1. In the Java source, `ActionClass` (`platform-core`) classifies **network-device** actions, and
  `ActionDescriptor` carries a console flag and role tokens, not an `ActionClass`. R2 gives `report_schedule_manage`
  no `ActionClass` label, because it touches no device. How R1's labels map onto the code is for the R1 implementation
  and the PO to settle; this draft does not reinterpret a frozen contract.
- **C3. R1's "existing report projection masker" and "retention janitor".**
  - `svc/privacy/ReportProjectionMasker` exists, but it is the Packet Analysis workbook masker
    (`PacketAnalysisController`).
  - Retention is implemented by per-class pruners, and no generic janitor was found.
  - R2 depends only on R1's single masking pass and R1's retention, whatever classes implement them.
- **C4. Outside R2 scope, flagged.** Today's notification digests (`NotificationMailRouter`, `NotificationForwarder`)
  put `devices.observed_hostname`, a raw estate name, into e-mail and syslog lines. That predates R2 and is not changed
  here. R2 report mail is masked regardless.
