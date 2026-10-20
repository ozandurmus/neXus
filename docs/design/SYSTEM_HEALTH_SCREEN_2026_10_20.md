# Operations › System health — implementation record (2026-10-20)

**Status: RECORD — implementation of mockup screen 2 ("Sistem Sağlığı — Filoda kim zorlanıyor?", artifact of
2026-10-17) over the data the system-log programme already stores (PO decision 2026-10-20). It introduces no device
command, no table and no new action id; it is a read over
[DEVICE_SYSTEM_LOG_PHASE_B_CONTRACT.md](DEVICE_SYSTEM_LOG_PHASE_B_CONTRACT.md) §5.2 tables and the artefact store, and
it follows the shapes recorded in
[DEVICE_SYSTEM_LOG_MEASUREMENT_FINDINGS_2026_10_17.md](DEVICE_SYSTEM_LOG_MEASUREMENT_FINDINGS_2026_10_17.md).**

## 1. Where it is

- UI: **Operations › System health** (fourth tab of the Operations screen, slug `?screen=operations&tab=health`; the
  narrow-width drawer lists it under OPERATE beside Jobs). It is a tab, not a rail entry: it answers the same OPERATE
  question as HA readiness and Jobs ("what in the fleet needs a hand"), and it adds no screen id. A row opens
  Device › Logs (`?screen=inventory&device_id=…&tab=logs&log_day=yyyy-MM-dd`, the row's newest stored day).
- API: `GET /api/v2/system-logs/health` — action `system_log_read` (operator and above, aiview included); the body is
  counters, state words and opaque ids, and its `hostname` / `cluster_member_ref` fields go through the privacy
  masking advice for the aiview persona like every other device row.
- CLI: `syslog-health` (CLI parity; the row belongs to this record, the approved contract §14 table is unchanged).
- Code: `SystemHealthService`, `SarDailyText`, `CpwdRestartLines` (service `systemlog`), `SystemHealthController`,
  `SystemLogReadRepository.fleetLastRuns / sarFileRefs / cpwdChunkRefs`, `SystemHealthPanel.tsx`.

## 2. Data sources and what is UNKNOWN today

| Column | Source (stored already) | Shape relied on | UNKNOWN when |
|---|---|---|---|
| CPU p95 per day, 7-day sparkline | `system_log_file` kind `cp_sar_daily` (yesterday's `/var/log/sa/sar<DD>`, 06:00 window, 30-day retention), decrypted from the artefact store and parsed once per file into counters | findings §4.1: line 1 `Linux <kernel> (<host>)<TAB><date><TAB><arch> (<N> CPU)`, blank line, a 24-hour `HH:MM:SS CPU` + 10-column header, one `all` row and one row per CPU per sample; sections blank-line separated with their own header; `Average:` rows skipped. Busy = `100 - %idle` of the `all` row; p95 = nearest rank. The sar day is the file mtime's calendar day in Europe/Istanbul (the name is stored as a digest, only `DD` is in it). The sample interval was not measured; the parser does not need it | no stored sar day in the window; file `CORRUPT` or unreadable; a text without CPU samples; Palo Alto (`No source`) |
| Memory used p95 | the same text's `%memused` column, when the report has a memory section | sysstat `sar -r` column vocabulary; **a memory section was not measured on this fleet** (20-line heads show the first CPU sample only) | the text has no memory section |
| Process restarts | `system_log_chunk` rows of `cp_cpwd_elg` in the window, decrypted and counted once per chunk | findings §3.3: `[<proc> <pid> <tid>]@<host>[<DD> <Mon> <HH:MM:SS>] <text>`; the hour bucket is `<Mon> <DD> <HH>` device-local. Rule `cpwd-restart-v1` (**PROVISIONAL**): a line whose text contains the word *restart* in any form is one restart; termination-only lines (`terminated`, `exited`) are counted apart. The watchdog's wording was masked in the measurement and no real sample is in the repository, so this is a vocabulary rule until a real-environment sample is recorded | cpwd never collected, or its newest read ended in a failure outcome with no chunk in the window; store not mounted. A cpwd source that ran (`COLLECTED`/`NO_NEW_DATA`) with no chunk is **0 restarts, evidenced** (13 of 53 gateways have an empty `cpwd.elg`) |
| New crash/dump | `device_crash_alert` rows of the window (contract §7: first listing is a baseline; present-before-monitoring files never alarm) | one alert per (device, run, process) | the crash listing (`cp_crash_listing`) has never run for the device |
| Last collection | newest `system_log_run` row per (device, source) | outcome vocabulary of §9 | never: `NEVER` (no run row); `OFF` for a management server while `include_management_servers` is off; `FAILED` when a source of the newest job ended `UNKNOWN` or `STORAGE_REFUSED`; `UNSUPPORTED` (Gaia Embedded) |
| Palo Alto rows | `pan_system` / `pan_config` run outcomes only | — | CPU, memory, restarts and crash are `No source` (NOT_APPLICABLE): the PAN system log carries no CPU figure and there is no crash listing or watchdog source; the row's state is therefore UNKNOWN today |

Not shown, on purpose: no composite score; no "stale/outdated" judgement (the last collection time is shown, the
reader judges); nothing is derived from the chunk index that the text does not support.

## 3. Rules (fixed, shown on the screen)

- Threshold: **CPU p95 85 %**, the one documented default (`ui2.system-health.cpu-p95-threshold-percent`
  overrides it; the value in force is in the body and on the KPI card).
- Each fact is CRITICAL, ATTENTION, NORMAL, UNKNOWN or NOT_APPLICABLE; the device's **state word is the worst fact**
  in the order CRITICAL › ATTENTION › UNKNOWN › NORMAL (an unevidenced fact is never read as healthy). A device with
  no applicable fact is UNKNOWN.
- **Critical**: a new crash/dump alert in the 7-day window; or CPU p95 above the threshold on **3 stored sar days in a
  row** (the sar "window" is a day: one file per day).
- **Attention**: CPU p95 above the threshold on 1 or 2 stored days; or **3 or more cpwd restarts in one device-local
  hour**.
- **Normal**: every applicable fact evidenced and none of the above.
- Rows sort worst first (state order, then newest CPU p95 descending, then pseudonym).
- KPI "CPU p95 above threshold": devices whose **newest** stored sar day is yesterday or today and above the threshold
  (the "24 h" of the mockup, given that sar arrives once a day), beside the evidenced and UNKNOWN counts.
- KPI "Collection not OK": devices whose newest run is FAILED, UNSUPPORTED, OFF or NEVER, by state.

## 4. Cost and caching

A sar text can be tens of MiB (findings §4.1: up to about 16 MiB per text file on 128-CPU devices, 32 MiB bound).
The fleet is therefore computed in the background: a first computation 45 s after start, then every 10 minutes;
each file and chunk is decrypted and parsed **once** and kept as counters by id (an artefact is immutable); a request
is answered from the snapshot in well under a second. The body says `computation.state`: `COMPUTING` until the first
snapshot (the panel says so as a status notice and asks again every 5 s), `REFRESHING` when a stale snapshot is served
while a refresh runs, `READY` otherwise. Nothing is stored beyond what the programme already stores; the counters live
in service memory only.

## 5. Tests

`SarDailyTextTest`, `CpwdRestartLinesTest` (synthetic texts in the measured shapes), `SystemHealthServiceTest`
(really encrypted synthetic sar and cpwd artefacts, the state rule, sorting, KPIs, cache, missing store, corrupt day),
`SystemHealthControllerTest` (no-store, masking of the body for the replay viewer), `SystemLogReadSecurityRouteTest`
(eight routes under `system_log_read`), `NexusSessionCliTest` (`syslog-health`), `JooqSystemLogReadRepositoryTest`
(the three new statements are bound reads without name digests), `SystemHealthPanel.test.tsx` (KPIs, UNKNOWN / No
source, sparkline words, deep link, computing notice as `role=status`, restricted and error states), the Operations
tab, tab coverage and rail tests.

## 6. Open points (not decided here)

- A real-environment sample of `cpwd.elg` restart lines (masked by the read projection) would turn `cpwd-restart-v1`
  from PROVISIONAL into a measured shape; until then the count is labelled with the rule id.
- Whether the fleet's sar text carries a memory section is unmeasured; the column stays UNKNOWN until a stored text
  shows one.
- The Logs tab may use `log_day` from the row link once it has a day selector (lane `ui-logs-tree`); the parameter is
  harmless until then.
