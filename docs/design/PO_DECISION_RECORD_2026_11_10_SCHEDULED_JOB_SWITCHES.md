# PO decision record - 2026-11-10: per-family on/off switches for scheduled jobs

**Status: RATIFIED - PO DECISION (chat, 2026-11-10, about 21:00 GMT+3), relayed by the PO session.**

## PO answer (verbatim)
"artık veri toplamaya geçelim" ("let's move on to collecting data now"),
"zamanlı işleri hepsini açmanı istiyorum" ("I want you to turn all the scheduled jobs on").
The PO also asked for an on/off control for scheduled jobs in the UI, because none existed.

## Decisions

1. **Development mode ends; every scheduled job family is ON.** `UI2_SCHEDULING_ENABLED=true` in
   `deploy/ui2/50-service-deployment.yaml`; migration V203 seeds all six families ON.
2. **One switch per family, stored in the database (V203 `scheduled_job_family`), audited.** Families:
   `discovery_refresh`, `nightly_inventory`, `configuration_refresh`, `ha_readiness` (Check Point and Palo Alto
   readiness passes), `scheduled_backup` (`BackupScheduler`, including the MDS export) and `system_log_collection`.
   Each scheduler reads its row at the start of every tick (and before each unit where a tick admits one unit at a
   time), so a change applies from the next tick without a restart. A missing or unreadable row is OFF (fail closed).
3. **Switching a family OFF leaves nothing behind.** In the same audited transaction the family's queued jobs
   (`REQUESTED`, not yet leased by a worker, submitted by that family's system actor) are cancelled with
   `terminal_reason = SCHEDULED_FAMILY_SWITCHED_OFF`, and the `discovery_run` rows that pointed at them are closed.
   Readiness runs close through the existing job-terminal trigger. Jobs already running finish; manually started jobs
   are never touched. A one-minute sweep repeats the cancellation for a tick that read "on" an instant before the switch.
4. **`UI2_SCHEDULING_ENABLED` stays the hard master off switch.** When it is false, every family is shown LOCKED with
   the reason, and the API refuses changes (`409 SCHEDULING_LOCKED_BY_DEPLOYMENT`). The system-log collection keeps its
   own deployment switches (`UI2_SYSTEM_LOG_SCHEDULED_COLLECTION_ENABLED`, `..._INDEPENDENT`, PO 2026-11-05). The backup
   policy's own schedule switch (Backups screen) still applies on top of the family switch.
5. **Who may change it.** `security_admin` and `operation_admin` (action `scheduled_jobs_set`); a reason of 5-500
   characters is mandatory; inside business hours (08:00-20:00 GMT+3) the change needs the same acknowledgement as an
   administrator's manual job (PO 2026-10-20). Every signed-in role reads the screen (`scheduled_jobs_read`); for the
   aiview persona the person who changed it is a `USR-` token and the reason is withheld. The 00/06/12/18 windows are
   unchanged: the switch only decides whether a family submits work in them.
6. **Where.** Administration > Platform > Scheduled jobs; CLI parity: `schedules` and
   `schedule-set <family> on|off <reason>` (`--business-hours-ack` inside business hours).
