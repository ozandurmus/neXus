# Log funnel backtest on known events - 2026-11-11 (counts only)

Status: EVIDENCE RECORD (non-authoritative). It decides nothing and approves no code, command or outbound connection.
Next step of the question-funnel measurement, asked for by the PO on 2026-11-11 ("test it on past events"). Inputs:
[LOG_QUESTION_FUNNEL_MEASUREMENT_2026_11_10.md](LOG_QUESTION_FUNNEL_MEASUREMENT_2026_11_10.md) (the draft detector and the
`base` policy, unchanged here),
[LOG_ANOMALY_AGENT_DESIGN_2026_11_10.md](LOG_ANOMALY_AGENT_DESIGN_2026_11_10.md) (DRAFT; §4.4 detectors, §10 measurements).

No raw log line, word, name, address, serial or template text left HOST-A or appears here. Devices are not named; only
counts leave the pod. Nothing was tuned on these events: the detector is the funnel record's draft, run as it was.

## 0. Answer in short

- **The draft detector finds every known event it can be tested on.** Seven event rows were usable: the two members of
  each of three failover runs (a Check Point run that was stopped, a Check Point run that succeeded, a Palo Alto run that
  succeeded, run on 21 and 22 October) and one Check Point crash-file alert (9 November). With the device
  clock read as Istanbul wall time, **all seven produced a signature finding** (a burst of high-availability lines on all
  six failover members, a crash finding on the crash device). Read as UTC, only 3 of 7 did.
- **The clock reading is itself a finding.** In the failover window the Check Point members show 28 and 60 high-
  availability lines read as Istanbul time against 0 and 2 read as UTC. The evidence says the line timestamps of the
  Check Point devices are Istanbul wall time (two readings were tried, no others). For Palo Alto the two readings give
  a similar line count (13 against 12), but link lines (8 against 0) and the burst count (2 of 2 against 1 of 2) also
  favour Istanbul time; that is suggestive, not proof.
- **The signature finding alone is not a page.** Without a known event, a signature finding appears in the same
  4-hour clock window on **23 % and 42 % of Check Point device-windows and 5 % of Palo Alto ones** (config A), on 2 % and
  30 % of Check Point and 3 % of Palo Alto ones with two days of history (config B). It is a candidate for the
  question funnel, not an alarm. The funnel's `base` policy, not the signature, decides what is asked.
- **The detector needs its history.** With two to five days of history (config B), the `base` policy would ask about
  **89 % and 92 % of Check Point device-windows** (94 and 98 of 106): new messages are everywhere. With almost none
  (config A), almost nothing can be recognised as routine in the two main sources. The 7-day learning rule of the
  funnel measurement is not a detail; it is what keeps the question volume at the measured 46 a day.
- **Only one event has real history behind it: the crash alert** (17 to 19 learning days on the event devices, config
  C). It is found, but the same device shows a signature in 1 of 5 comparable windows on other days and a `base`
  question in 3 of 5. One device cannot say more.
- **What could not be tested.** The two MDS crash nights (15 and 16 October) precede the log pool (first chunk on
  17 October) and MDS servers are not in the collection at all. The record therefore has **three failover runs and one
  crash alert**, nothing else, and the failover runs precede any week of history.

## 1. What was asked

The PO, after the funnel record: "test the detector on past events". The events the PO named: the failover trial of
22 October and the MDS crash nights.

## 2. Method

### 2.1 Ground truth

Read-only from the application database inside the pod, counts only:

| Source | Rows | Meaning |
|---|---:|---|
| `failover_run` (kind FAILOVER) | 3 runs, 6 member rows | 1 Check Point run stopped, 1 Check Point run succeeded, 1 Palo Alto run succeeded |
| `device_crash_alert` | 1 row | newest crash-file time of one Check Point device |

Event windows: for a failover, from 30 minutes before the run started to 60 minutes after it finished; for the crash
alert, from 4 hours before to 2 hours after the file time. A window is a run of hourly buckets (4 for the failovers).

### 2.2 What the detector did

The funnel record's draft detector, unchanged: crash lines, cpwd restarts, bursts of high-availability or link lines
(at least 3 lines in 10 minutes), the hourly rate against the device's own history, and templates never seen before.
Three flags per window: **signature** (crash, restart, high-availability burst, link burst), **question** (the `base`
policy would ask), **any** (any finding at all, including excluded classes, so the loosest). Routine templates (seen
on at least 80 % of learning days) suppress the signature bits, which needs enough history.

### 2.3 Two clock readings

The collector does not record a time zone. Each event window was read twice: as Istanbul wall time and as UTC wall
time against the bucketed line times. The reading that matches the events is the device's own.

### 2.4 Controls

- **Own control:** the same clock window on the other days of the test window, on the same device.
- **Fleet control:** the same window on every other active device of the same vendor ("device-windows"). It includes the
  event device's cluster partner, one device-window per event row.

### 2.5 Three configurations

The pool holds only a few days of lines before the failover night, so no single configuration serves every question:

| Config | Test window | Learning rule | What it can show |
|---|---|---|---|
| A | 7 days ending 24 October | 7 days, as in the funnel record | Signature findings from the two main sources. One of 55 Check Point devices has a week of history in the messages log and none of 38 Palo Alto devices in the system log, so little is routine and nothing is "new" there. The longer-kept cpd and fwk logs have a week on about two thirds of devices, so a few question findings still appear in the fleet control. |
| B | 3 days ending 23 October | 2 days | The most history the pool has before the failover runs: a median of 4 days and at most 12 for Check Point messages, 3 to 6 days for Palo Alto. Every finding kind runs, on thin history. |
| C | 7 days ending 11 November | 7 days | The crash alert with 17 to 19 learning days. Only the event devices were read (3 Check Point, 2 Palo Alto). |

All three read a 35-day window of the pool: 7,310, 6,318 and 1,985 chunks (7.2, 6.2 and 5.2 GB), no read error, no
time cut-off. Each run took about 22 minutes inside the pod.

## 3. Results

### 3.1 The events

`failover_other` is the stopped Check Point run, `failover_succeeded` a run that succeeded. "Device not in the read set" is
0 everywhere: every event device was read.

| Event kind | Vendor | Event-member rows | Device not in the read set |
|---|---:|---:|---:|
| crash_alert | cp | 1 | 0 |
| failover_other | cp | 2 | 0 |
| failover_succeeded | cp | 2 | 0 |
| failover_succeeded | pan | 2 | 0 |

### 3.2 What was found (device-window counts; rows are event member rows)

The headline view, local (Istanbul) reading, from the tables below:

| Event rows | Signature, config A | Signature, config B | `base` question, config B | Signature, config C |
|---|---:|---:|---:|---:|
| Check Point failover, stopped run (2 members) | 2 of 2 | 2 of 2 | 2 of 2 | outside test window |
| Check Point failover, succeeded (2 members) | 2 of 2 | 2 of 2 | 2 of 2 | outside test window |
| Palo Alto failover, succeeded (2 members) | 2 of 2 | 2 of 2 | 2 of 2 | outside test window |
| Check Point crash alert (1 device) | outside test window | outside test window | outside test window | 1 of 1 |

Read as UTC: Check Point stopped run 0 of 2, Check Point succeeded run 2 of 2 (by crash-class lines only, no high-
availability burst), Palo Alto run 1 of 2, crash alert 0 of 1 (the hourly rate fires, so the `base` question is 1 of 1).

**Config A** (7-day test window; the question column is 0 on the event devices because the main sources lack history)

#### Backtest: what the draft detector finds in the event window (device clock read two ways)

| Event kind | Vendor | Clock | Rows in the test window | Device active | Signature | Question | Any finding | HA lines | Link lines |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| crash_alert | cp | local | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| crash_alert | cp | utc | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| failover_other | cp | local | 2 | 2 | 2 | 0 | 2 | 28 | 0 |
| failover_other | cp | utc | 2 | 2 | 0 | 0 | 0 | 0 | 0 |
| failover_succeeded | cp | local | 2 | 2 | 2 | 0 | 2 | 60 | 0 |
| failover_succeeded | cp | utc | 2 | 2 | 2 | 0 | 2 | 2 | 0 |
| failover_succeeded | pan | local | 2 | 2 | 2 | 0 | 2 | 13 | 8 |
| failover_succeeded | pan | utc | 2 | 2 | 1 | 0 | 1 | 12 | 0 |

#### Backtest: the same detector in windows without a known event (controls)

| Event kind | Vendor | Clock | Own: windows | signature | question | any | Fleet: device-windows | signature | question | any |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| crash_alert | cp | local | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| crash_alert | cp | utc | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| failover_other | cp | local | 10 | 1 | 0 | 1 | 106 | 24 | 6 | 30 |
| failover_other | cp | utc | 12 | 0 | 0 | 0 | 98 | 2 | 0 | 2 |
| failover_succeeded | cp | local | 10 | 7 | 0 | 7 | 106 | 44 | 6 | 48 |
| failover_succeeded | cp | utc | 12 | 8 | 0 | 8 | 106 | 68 | 28 | 70 |
| failover_succeeded | pan | local | 12 | 0 | 0 | 0 | 74 | 4 | 0 | 4 |
| failover_succeeded | pan | utc | 12 | 2 | 0 | 2 | 74 | 15 | 0 | 15 |

#### Backtest: which finding kinds fire on the event devices

| Event kind | Vendor | Clock | crash | restart | burst_ha | burst_link | rate | rate_excluded | new_rare_severity | new_rare | new_rare_excluded | new_common | new_signature |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| crash_alert | cp | local | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| crash_alert | cp | utc | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| failover_other | cp | local | 0 | 0 | 2 | 0 | 0 | 0 | 0 | 0 | 0 | 1 | 1 |
| failover_other | cp | utc | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| failover_succeeded | cp | local | 0 | 0 | 2 | 0 | 0 | 0 | 0 | 0 | 0 | 1 | 1 |
| failover_succeeded | cp | utc | 2 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| failover_succeeded | pan | local | 0 | 0 | 2 | 1 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| failover_succeeded | pan | utc | 0 | 0 | 1 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |

**Config B** (3-day test window, 2 learning days)

#### Backtest: what the draft detector finds in the event window (device clock read two ways)

| Event kind | Vendor | Clock | Rows in the test window | Device active | Signature | Question | Any finding | HA lines | Link lines |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| crash_alert | cp | local | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| crash_alert | cp | utc | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| failover_other | cp | local | 2 | 2 | 2 | 2 | 2 | 28 | 0 |
| failover_other | cp | utc | 2 | 2 | 0 | 1 | 1 | 0 | 0 |
| failover_succeeded | cp | local | 2 | 2 | 2 | 2 | 2 | 60 | 0 |
| failover_succeeded | cp | utc | 2 | 2 | 2 | 1 | 2 | 2 | 0 |
| failover_succeeded | pan | local | 2 | 2 | 2 | 2 | 2 | 13 | 8 |
| failover_succeeded | pan | utc | 2 | 2 | 1 | 2 | 2 | 12 | 0 |

#### Backtest: the same detector in windows without a known event (controls)

| Event kind | Vendor | Clock | Own: windows | signature | question | any | Fleet: device-windows | signature | question | any |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| crash_alert | cp | local | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| crash_alert | cp | utc | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| failover_other | cp | local | 2 | 1 | 1 | 2 | 106 | 2 | 94 | 104 |
| failover_other | cp | utc | 4 | 0 | 1 | 1 | 98 | 0 | 3 | 39 |
| failover_succeeded | cp | local | 2 | 1 | 1 | 2 | 106 | 32 | 98 | 104 |
| failover_succeeded | cp | utc | 4 | 4 | 1 | 4 | 106 | 68 | 33 | 88 |
| failover_succeeded | pan | local | 4 | 0 | 0 | 2 | 74 | 2 | 14 | 14 |
| failover_succeeded | pan | utc | 4 | 2 | 0 | 2 | 74 | 11 | 60 | 74 |

#### Backtest: which finding kinds fire on the event devices

| Event kind | Vendor | Clock | crash | restart | burst_ha | burst_link | rate | rate_excluded | new_rare_severity | new_rare | new_rare_excluded | new_common | new_signature |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| crash_alert | cp | local | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| crash_alert | cp | utc | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| failover_other | cp | local | 0 | 0 | 2 | 0 | 2 | 2 | 0 | 0 | 1 | 2 | 2 |
| failover_other | cp | utc | 0 | 0 | 0 | 0 | 1 | 1 | 0 | 0 | 0 | 0 | 0 |
| failover_succeeded | cp | local | 0 | 0 | 2 | 0 | 2 | 2 | 0 | 0 | 1 | 2 | 2 |
| failover_succeeded | cp | utc | 2 | 0 | 0 | 0 | 1 | 1 | 0 | 0 | 0 | 1 | 0 |
| failover_succeeded | pan | local | 0 | 0 | 2 | 1 | 0 | 0 | 0 | 2 | 2 | 1 | 2 |
| failover_succeeded | pan | utc | 0 | 0 | 1 | 0 | 2 | 0 | 0 | 0 | 1 | 1 | 0 |

**Config C** (the crash alert's week, event devices only)

#### Backtest: what the draft detector finds in the event window (device clock read two ways)

| Event kind | Vendor | Clock | Rows in the test window | Device active | Signature | Question | Any finding | HA lines | Link lines |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| crash_alert | cp | local | 1 | 1 | 1 | 1 | 1 | 0 | 0 |
| crash_alert | cp | utc | 1 | 1 | 0 | 1 | 1 | 0 | 0 |
| failover_other | cp | local | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| failover_other | cp | utc | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| failover_succeeded | cp | local | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| failover_succeeded | cp | utc | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| failover_succeeded | pan | local | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| failover_succeeded | pan | utc | 0 | 0 | 0 | 0 | 0 | 0 | 0 |

#### Backtest: the same detector in windows without a known event (controls)

| Event kind | Vendor | Clock | Own: windows | signature | question | any | Fleet: device-windows | signature | question | any |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| crash_alert | cp | local | 5 | 1 | 3 | 5 | 2 | 0 | 1 | 1 |
| crash_alert | cp | utc | 5 | 4 | 3 | 5 | 2 | 0 | 0 | 1 |
| failover_other | cp | local | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| failover_other | cp | utc | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| failover_succeeded | cp | local | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| failover_succeeded | cp | utc | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| failover_succeeded | pan | local | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| failover_succeeded | pan | utc | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |

#### Backtest: which finding kinds fire on the event devices

| Event kind | Vendor | Clock | crash | restart | burst_ha | burst_link | rate | rate_excluded | new_rare_severity | new_rare | new_rare_excluded | new_common | new_signature |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| crash_alert | cp | local | 1 | 0 | 0 | 0 | 1 | 1 | 0 | 1 | 0 | 0 | 1 |
| crash_alert | cp | utc | 0 | 0 | 0 | 0 | 1 | 1 | 0 | 0 | 0 | 0 | 0 |
| failover_other | cp | local | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| failover_other | cp | utc | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| failover_succeeded | cp | local | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| failover_succeeded | cp | utc | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| failover_succeeded | pan | local | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| failover_succeeded | pan | utc | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |

### 3.3 How to read the controls

| Vendor | Signature in a control window, config A | Config B | `base` question in a control window, config B |
|---|---|---|---|
| Check Point, fleet device-windows | 24 of 106 (23 %), 44 of 106 (42 %) | 2 of 106 (2 %), 32 of 106 (30 %) | 94 of 106 (89 %), 98 of 106 (92 %) |
| Palo Alto, fleet device-windows | 4 of 74 (5 %) | 2 of 74 (3 %) | 14 of 74 (19 %) |
| Check Point, own control windows | 1 of 10, 7 of 10 | 1 of 2, 1 of 2 | 1 of 2, 1 of 2 |
| Palo Alto, own control windows | 0 of 12 | 0 of 4 | 0 of 4 |
| Crash device, own control windows (config C) | signature 1 of 5 | | `base` question 3 of 5 |

Two causes are in the code and one is not explained:

- In A, nothing is routine, so crash-class lines and cpwd restarts that a week of history would call routine can
  count as signature findings. That can inflate A's Check Point rate (23 % and 42 %). With two days of history (B) the
  rate is 2 % in the stopped run's window.
- In B the new-template findings run on thin history and flag almost every Check Point device-window; this is the
  flooding the 7-day gate prevents.
- **Not explained:** why the window of the succeeded Check Point run still shows a signature on 30 % of Check Point
  device-windows in B (42 % in A) while the stopped run's window shows 2 %. The data has counts only; a real fleet-wide
  event at that hour, or a routine signature that two learning days cannot recognise, are both possible.

## 4. What this changes (proposals; nothing here is a decision)

1. **Carry the device's own time offset.** Hour buckets from the line time are right only if the offset is known. For
   Check Point it is Istanbul wall time; assume nothing for other sources, and record the offset per device and
   source.
2. **State "learning" explicitly, and ask nothing then.** A device and source with fewer than 7 days of history gets
   the state `NOT_EVALUABLE`, not a degraded question stream. B is the evidence: 89 to 92 % of device-windows.
3. **Treat the signature as a candidate, never as an alarm.** It has to pass the `base` policy (or a stricter high-
   availability rule) before a question is asked. A sweep of the burst threshold (3, 10, 20, 30 lines in 10 minutes)
   against the same controls would show whether a Check Point threshold separates the known failovers (28 and 60
   lines in the window; 13 for Palo Alto) from the fleet's background. That is one more in-pod run of about 25
   minutes; it is not part of this record.
4. **Make every future failover trial and crash alert a test case.** The ledger already holds them. A repeat of this
   backtest is only informative for events that have a week of history behind them, so it should run again after
   enough new events and after the MDS collection (design record of 2026-11-11) has produced its first week.

## 5. Limits

- **Three failover runs, one crash alert.** All the Check Point events together involve three devices and the Palo Alto
  run two. The sample is a smoke test of recall, not a measure of it; a precision figure needs many more events.
- **The failover runs precede full history.** The log pool starts in mid-October and the files hold few days of
  messages before the failover runs (median 1 day in A's window, 4 in B's; at most 12 for Check Point and 6 for Palo
  Alto), so configs A and B are not the steady-state detector. Only the crash alert (config C) has a
  real learning period, and it is one device.
- **Controls are imperfect.** The own control uses other days at the same clock window, which may hold other events
  themselves; the fleet control includes the event device's cluster partner and
  every device busy at that hour.
- **The two clock readings are the only two tried.** A different offset would give a different result; the data
  supports Istanbul wall time for Check Point and does not settle it for Palo Alto.
- **MDS crash nights cannot be tested.** The pool starts on 17 October and MDS servers are excluded from collection.
- **The `any` column is the loosest** (it includes excluded classes such as administrator-session bursts) and is shown
  for completeness only.
- **Config B had to be repeated once**: the first attempt lost its pod to a rollout and was discarded; the second ran
  on a quiet host. The aggregates in this record come from the complete runs only.

## 6. How it was run (reproduce)

Source in `scripts/measurement/log_question_funnel/`: `LogFunnelMeasure.java` (new options `--end-day`, `--backtest`,
`--only-event-devices`, `--events-file`), `run_in_pod.sh`, `backtest_runner.sh`, `selftest.py`, `tables.py`.

```sh
# on the workstation: compile and self-test on the synthetic corpus (it includes synthetic events for the backtest)
javac --release 21 -cp "BOOT-INF/classes:BOOT-INF/lib/*" -d payload/classes LogFunnelMeasure.java
python3 selftest.py java "payload/classes:BOOT-INF/classes:BOOT-INF/lib/*" ../log_masking_risk/techvocab.txt
# the gated runner: starts only on a quiet host (not before 03:15 Istanbul, no ship, deploy or e2e, every pod Running,
# service pod older than 10 minutes, twice in a row), repeats a run that loses its pod, three configurations
bash backtest_runner.sh payload out 0315
#   A: --end-day 2026-10-24 --test-days 7 --min-learn-days 7 --backtest 1
#   B: --end-day 2026-10-23 --test-days 3 --min-learn-days 2 --backtest 1
#   C:                      --test-days 7 --min-learn-days 7 --backtest 1 --only-event-devices 1
python3 tables.py out/A.tsv   # also prints the whole funnel for that window; the backtest tables are the last three
```

Run in the pod next to the service, single-threaded; the host must be quiet (a ship that rolls the pod deletes
`/app/tmp` and the run is repeated).
