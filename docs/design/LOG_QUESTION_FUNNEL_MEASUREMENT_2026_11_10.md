# Log question funnel measurement - 2026-11-10 (counts and class labels only)

Status: EVIDENCE RECORD (non-authoritative). It decides nothing and approves no code, command or outbound connection.
Measurement asked for by the PO on 2026-11-10 ("should we not also measure, from the logs we already hold, what would
be asked to the AI and what would not?"). Inputs:
[LOG_ANOMALY_AGENT_DESIGN_2026_11_10.md](LOG_ANOMALY_AGENT_DESIGN_2026_11_10.md) (DRAFT; §4.3 templates, §4.4
detectors, §10 measurements E2, E3, E4 and E7),
[PO_DECISION_RECORD_2026_11_10_LOG_ANOMALY_AGENT.md](PO_DECISION_RECORD_2026_11_10_LOG_ANOMALY_AGENT.md) (A1: AI
trial before the detector; A8: calls on demand only),
[LOG_MASKING_RISK_MEASUREMENT_2026_11_10.md](LOG_MASKING_RISK_MEASUREMENT_2026_11_10.md) (what would leak) and
[LOG_AGENT_CLAUDE_TRIAL_2026_11_10.md](LOG_AGENT_CLAUDE_TRIAL_2026_11_10.md) (cost per explanation).

No raw log line, word, name, address, serial or template text left HOST-A or appears here.

## 0. Answer in short

- **Yes, this was the missing measurement, and it is now done once.** A draft of the deterministic detector ran over
  every stored chunk of six sources: 22,041 chunks, 118.9 million lines, 19.2 GB, 55 Check Point and 38 Palo Alto
  devices, no read error.
- **What would not be asked.** In the test week there were 12,673 device-hours with log lines. In **83.8 %** of them
  the draft detector found nothing. In a further **8.6 %** the only findings were bursts of administrator and session
  lines, a class excluded by rule. In **2.0 %** a signature alone fired, which fixed text can describe. The remaining
  **2.6 %** had only messages that are new on a device but common in the fleet, or excluded-class findings next to a
  signature.
- **What would be asked.** **3.0 %** of device-hours (375) qualify under the `base` policy. Merged into episodes, that
  is **324 questions in the week: about 46 a day for the whole fleet, 0.5 per device per day.** Two thirds of
  device-days have none; the busiest device-day has 5. The narrowest policy gives 65 a week, the widest 1,587.
- **Cost of that volume.** About 1,390 eligible questions a month under `base`. At the trial's measured price per
  level-1 explanation that is about **USD 24 a month on Sonnet 5.5** (USD 47 on Opus 5.5, under USD 2 on Haiku 5.5),
  if every eligible episode were asked. Calls are on demand only (decision A8), so real spend is lower. The proposed
  ceiling of USD 30 a month covers `base`.
- **Three things the logs themselves showed:**
  1. `cp_fwk_elg` is 80 % of all lines read (94.7 million). Ten of 55 gateways write 98 % of it, ten templates make up
     98 % of its lines, and 97 % of its lines contain an error word. Per-incident questions do not fit this; a
     one-off question per template does.
  2. `cp_messages` is 85 % two processes (`xpand`, `clish`) and 77.5 % administrator and session lines. Almost all of
     its rate findings (6,163 of 6,470) are bursts of that class.
  3. Only `cp_messages` and `pan_system` carry a time on every line. In `cp_cpd_elg` and `cp_fwd_elg` about 5 % of
     lines do, so an hourly rate detector cannot run there (§3.3).
- **Limits.** One week, one pool, thresholds not validated against known events, keyword classes not checked against
  message text, and only 17 to 26 days of history behind the test week. The numbers size the question volume; they do
  not prove that the questions are the right ones (§6).

## 1. What was asked and why it was missing

Two things were measured before this record: what an outside party would see if log data were sent (the masking risk
record), and how well a model explains a finding and at what price (the Claude trial, on synthetic logs). Neither says
**how many questions the real logs would produce, and of which kind**. The price of one question was known; the
number of questions was not. The deterministic detector that decides it does not exist yet, because the PO chose the
AI trial first (decision A1).

This measurement runs a draft of that detector, as a one-off program outside the product, over the stored log pool,
and sorts every device-hour of one week into four buckets:

| Bucket | Meaning | Asked to a model? |
|---|---|---|
| Routine | No finding: the lines are the device's usual background | No |
| Answered by code | A known signature fired (crash word, watchdog restart, burst of HA or link lines) and nothing else | Not by default; fixed text is enough |
| Worth asking | A rare new message, a rate change without a signature, or several signals at once | Yes, this is the question volume |
| Not to be asked | The only findings are in classes excluded by rule (administrator and session lines, scheduler lines) | No, by rule |

## 2. Method

### 2.1 Where it ran and what left HOST-A

- A one-off Java program, `scripts/measurement/log_question_funnel/LogFunnelMeasure.java`, ran as a second JVM inside
  the ui2-service pod on the deployed service jar (`PropertiesLauncher`, `loader.main`), the route of the masking risk
  measurement. It used the service's artefact-store key to read chunks. It did not use `LogMasking`: nothing is
  masked because no text is printed.
- The database session was read-only (`SET SESSION CHARACTERISTICS AS TRANSACTION READ ONLY`) and ran two plain
  queries over the chunk ledger: per-source totals, and the chunk list. No jsonb query, no write, no device command,
  no model call.
- Raw lines stayed in that JVM's memory. The program prints fixed labels and numbers only; every printed field must
  match `[a-z0-9_.]+`, library output is discarded, and the pod-side filter `show_safe.sh` (from the masking risk
  measurement) counted **0** filtered lines in both runs. Process names and Palo Alto subtypes are printed only when
  they are on a fixed allowlist written beforehand; every other name is counted as `other_process` or
  `other_subtype`. Template hashes are never printed.
- The program was checked first on a synthetic corpus with planted events (`selftest.py`, 28 expectations) and then
  on a 4-device pilot in the pod.

### 2.2 Sample

Every stored chunk of six sources for every device: `cp_messages`, `cp_cpwd_elg`, `cp_cpd_elg`, `cp_fwd_elg`,
`cp_fwk_elg` and `pan_system`. These are the sources the design allows for analysis. The other four stored sources
(`cp_mq_mng_elg`, `cp_spike_csv`, `cp_spike_log`, `pan_config`) were not read; their volumes are in §3.1 from the
ledger.

### 2.3 Time

- The window is the 35 days that end on the day of the newest chunk. The last 7 days are the **test week**; the 28
  days before are the **learning period**. A device-source needs lines on at least 7 learning days, otherwise it has
  "short history" and produces no new-template or rate finding.
- A line's time is read from the line where the format has one (`cp_messages`, the elg header, the `fwk` header, the
  Palo Alto `receive_time`). Device-local time is used as printed; no zone is applied. A line without a time inherits
  the time of the last timed line of its chunk (the rule adopted from the Argon review), and failing that the chunk's
  collection time.
- Lines older than the window count as "seen before" for the new-template test and are otherwise ignored.

### 2.4 Templates

- **Check Point.** The header is cut off; the process name is kept. Each token of the message becomes a typed
  placeholder (number, address, MAC, hex, path, interface name) or a word. In the **vocabulary form**, a word is kept
  only if it is in the technical vocabulary of the masking risk measurement (`techvocab.txt`, 1,780 words with the
  masking's stop-word lists); every other word becomes one placeholder. In the **finer form**, words are kept as
  written and digit runs inside them are collapsed. The detector uses the vocabulary form, so a host or user name
  cannot split a template. Only the first 40 tokens count.
- **Palo Alto.** The detector's template is the vendor enumeration (subtype, event id, module). The finer form adds
  the vocabulary form of the message text.
- A template is **fleet-common** when at least half of the devices that have the source have it, and **one-device**
  when exactly one has it.
- A template is **routine** on a device when it occurs on at least 80 % of that device's learning days.

### 2.5 Line classes (PROVISIONAL)

Fourteen classes from keyword lists written without reading log text, checked in priority order: `crash`,
`proc_restart`, `ha_state`, `link`, `routing`, `vpn`, `auth_admin`, `config_change`, `resource`, `license_update`,
`time_dns`, `log_transport`, `cron_routine`, `other`. A few process names and Palo Alto subtypes set the class
directly (for example `sshd` and `clish` are `auth_admin`; subtype `ha` is `ha_state`). A class is a counting label.
It is not a statement about what a vendor message means (`AGENTS.md`, vendor semantics law).

### 2.6 Draft detectors

| Detector | Rule in this measurement | Differs from design §4.4 |
|---|---|---|
| Crash | A `crash`-class line in the test week whose template is not routine on the device | Keyword class, not a curated signature list |
| Watchdog restart | A `cp_cpwd_elg` line with a restart word, template not routine (the existing `cpwd-restart-v1` idea) | — |
| HA burst, link burst | Three or more `ha_state` (or `link`) lines within 10 minutes, each with its own time, templates not routine | Counts class lines, not parsed state transitions per object |
| New template | First seen on the device in the test week, never before the window, device-source has enough history. Prevalence: one-device, some devices, fleet-common | — |
| Rate change | Hourly count of one template against the same device's learning hours: count >= 20, robust score >= 6 with a scale floor of 1, and count >= 5 x max(median, mean, 1). Also run with strict (50 / 10 / 10 x) and loose (10 / 4 / 3 x) thresholds | No hour-of-day or weekday split (history is too short); only for device-sources where at least 80 % of lines carry their own time |
| Silence, sustained shift, cross-device correlation | Not run | — |

### 2.7 Policies (what counts as a question)

A device-hour qualifies under a policy as below. Consecutive qualifying hours on one device are one **episode**; an
episode is the unit a person would ask about once.

| Policy | A device-hour qualifies when |
|---|---|
| `narrow` | It has a rare (not fleet-common) new template with an error or warning word, outside the excluded and the signature classes |
| `base` | It has a rare new template outside those classes; or a rate change with no signature in the same hour; or two of the three kinds (signature, rate change, rare new template) together |
| `strict_rate`, `loose_rate` | As `base`, with the strict or loose rate thresholds |
| `code_only` | It has a signature and does not qualify under `base` |
| `excluded_only` | Its only findings are in the excluded classes (`auth_admin`, `cron_routine`) |
| `wide` | It has any finding at all, including fleet-common new templates and excluded classes |

A rare new template of a signature class (a never-seen crash message, for example) is treated as covered by its
signature and does not by itself make a question.

## 3. Results

### 3.1 The stored pool

| Source | Devices | Chunks | Lines | GB | Read by this run |
|---|---:|---:|---:|---:|---:|
| `cp_cpd_elg` | 55 | 2,684 | 12,834,358 | 0.58 | yes |
| `cp_cpwd_elg` | 54 | 3,507 | 1,931,781 | 0.54 | yes |
| `cp_fwd_elg` | 52 | 4,127 | 836,494 | 0.22 | yes |
| `cp_fwk_elg` | 55 | 3,395 | 94,708,736 | 15.55 | yes |
| `cp_messages` | 55 | 4,983 | 6,548,986 | 0.84 | yes |
| `cp_mq_mng_elg` | 55 | 2,438 | 13,369,064 | 4.15 | no |
| `cp_spike_csv` | 55 | 179 | 25,745 | 0.00 | no |
| `cp_spike_log` | 55 | 1,485 | 143,216 | 0.03 | no |
| `pan_config` | 38 | 505 | 4,895 | 0.00 | no |
| `pan_system` | 38 | 3,345 | 2,088,869 | 1.45 | yes |
| **all** |  | 26,648 | 132,492,144 | 23.36 |  |

The six sources read are 82 % of the stored bytes. `cp_fwk_elg` alone is two thirds of the pool. From the chunk ledger:
the gateway with the most `cp_fwk_elg` lines holds 18.5 % of them, the top five 78.4 %, the top ten 98.0 %; the median
gateway holds about 48,000 lines, the largest 17.5 million.

### 3.2 What was read, and where each line's time came from

| Source | Devices | Lines | Time on the line | Inherited in chunk | From collection time | Unparsed | Over key cap |
|---|---:|---:|---:|---:|---:|---:|---:|
| `cp_messages` | 55 | 6,534,764 | 100.0% | 0.0% | 0.0% | 0 | 0 |
| `cp_cpwd_elg` | 54 | 1,931,781 | 58.9% | 9.5% | 31.6% | 0 | 0 |
| `cp_cpd_elg` | 55 | 12,826,416 | 5.4% | 85.7% | 8.9% | 0 | 0 |
| `cp_fwd_elg` | 52 | 824,603 | 5.2% | 53.0% | 41.8% | 0 | 0 |
| `cp_fwk_elg` | 55 | 94,680,727 | 62.8% | 2.2% | 35.1% | 0 | 0 |
| `pan_system` | 38 | 2,088,869 | 100.0% | 0.0% | 0.0% | 0 | 0 |

`cp_messages` and `pan_system` print a time on every line. In `cp_cpd_elg` and `cp_fwd_elg` about one line in twenty
does. In `cp_fwk_elg` and `cp_cpwd_elg` a third of the lines sit in chunks before any timed line, so they could only
be placed at the collection time. The header patterns here are the ones of the findings record of 2026-10-17; a second
line form in `cp_fwk_elg` that this program does not recognise would look the same in this table and is not ruled out.

### 3.3 History and where an hourly rate can be measured

| Source | Learning days min | median | max | Test days median | Devices with enough history | Devices with short history | Hourly rate evaluable (devices) | not evaluable |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| `cp_messages` | 12 | 18 | 26 | 7 | 55 | 0 | 55 | 0 |
| `cp_cpwd_elg` | 1 | 18 | 28 | 7 | 40 | 14 | 1 | 53 |
| `cp_cpd_elg` | 17 | 26 | 28 | 7 | 55 | 0 | 1 | 54 |
| `cp_fwd_elg` | 1 | 22 | 28 | 7 | 47 | 5 | 0 | 52 |
| `cp_fwk_elg` | 1 | 17 | 28 | 4 | 49 | 6 | 17 | 38 |
| `pan_system` | 17 | 18 | 20 | 7 | 38 | 0 | 38 | 0 |

The test week has lines on all seven days for the median device of every source except `cp_fwk_elg` (four days). The
learning period is short: a median of 17 to 26 days. An hourly rate is measurable on every device for `cp_messages`
and `pan_system`, on 17 of 55 for `cp_fwk_elg`, and on at most one device for the other three sources.

### 3.4 Templates

| Source | Templates (vocabulary form) | Finer templates | Lines in top 10 | top 100 | top 1,000 | Fleet-common templates | their lines | One-device templates | their lines |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| `cp_messages` | 1,022 | 3,643 | 72.1% | 97.9% | 100.0% | 185 | 89.9% | 291 | 5.1% |
| `cp_cpwd_elg` | 204 | 997 | 98.8% | 100.0% | 100.0% | 28 | 93.8% | 51 | 0.0% |
| `cp_cpd_elg` | 981 | 8,618 | 54.8% | 81.3% | 100.0% | 358 | 91.5% | 212 | 0.1% |
| `cp_fwd_elg` | 749 | 2,198 | 65.3% | 90.9% | 100.0% | 227 | 76.6% | 192 | 12.0% |
| `cp_fwk_elg` | 2,315 | 2,690 | 98.4% | 99.9% | 100.0% | 62 | 2.2% | 571 | 0.1% |
| `pan_system` | 140 | 447 | 91.2% | 100.0% | 100.0% | 24 | 75.6% | 18 | 12.0% |

In the vocabulary form the six sources have 5,411 templates together (the design assumed about 5,000 for the one-off
classification). The top 100 templates of each source cover 81 % to 100 % of its lines. Keeping words as written
(the finer form) multiplies the count by 1.2 to 8.8, most in `cp_cpd_elg`. In `cp_fwk_elg` the templates that most
devices share carry only 2.2 % of the lines: the volume is a few templates on a few devices.

### 3.5 Line classes

| Class | `cp_messages` | `cp_cpwd_elg` | `cp_cpd_elg` | `cp_fwd_elg` | `cp_fwk_elg` | `pan_system` | Lines, all sources |
|---|---:|---:|---:|---:|---:|---:|---:|
| crash | 0.0% | 0.2% | 0.0% | 0.0% | 0.0% | 0.0% | 4,173 |
| proc_restart | 0.0% | 2.9% | 0.0% | 0.4% | 0.0% | 0.0% | 62,902 |
| ha_state | 0.2% | 0.0% | 0.0% | 0.0% | 0.0% | 0.2% | 25,268 |
| link | 0.0% | 0.0% | 0.0% | 0.0% | 0.0% | 0.7% | 16,157 |
| routing | 0.0% | 0.0% | 0.0% | 0.0% | 0.0% | 0.2% | 3,211 |
| vpn | 0.0% | 0.0% | 0.1% | 0.0% | 0.0% | 21.1% | 456,998 |
| auth_admin | 77.5% | 0.0% | 0.1% | 0.0% | 0.0% | 11.5% | 5,317,313 |
| config_change | 0.2% | 0.0% | 0.0% | 1.2% | 0.1% | 0.2% | 120,436 |
| resource | 0.5% | 0.0% | 0.0% | 0.0% | 0.0% | 0.3% | 54,658 |
| license_update | 0.0% | 0.0% | 0.0% | 0.0% | 0.2% | 11.4% | 387,085 |
| time_dns | 0.0% | 0.0% | 0.0% | 0.0% | 0.0% | 0.1% | 2,436 |
| log_transport | 0.0% | 0.0% | 0.0% | 0.0% | 0.0% | 0.0% | 165 |
| cron_routine | 0.0% | 0.0% | 0.0% | 0.0% | 0.0% | 0.0% | 0 |
| other | 21.6% | 96.9% | 99.8% | 98.4% | 99.7% | 54.4% | 112,436,358 |
| with an error word or high severity | 2.9% | 2.2% | 1.2% | 58.8% | 97.0% | 6.4% | 92,857,003 |

- 94.6 % of all lines are in no keyword class. The classes that matter by volume are administrator and session lines
  (5.3 million, 95 % of them in `cp_messages`) and, on Palo Alto, VPN and update lines.
- An error word is not a usable severity signal: 97 % of `cp_fwk_elg` lines and 59 % of `cp_fwd_elg` lines contain one.

### 3.6 Routine share and processes

| Source | Lines in the test week | In routine templates | On devices with short history |
|---|---:|---:|---:|
| `cp_messages` | 1,402,283 | 98.2% | 0.0% |
| `cp_cpwd_elg` | 330,739 | 59.6% | 1.0% |
| `cp_cpd_elg` | 1,712,850 | 15.2% | 0.0% |
| `cp_fwd_elg` | 200,241 | 38.1% | 0.0% |
| `cp_fwk_elg` | 24,038,662 | 63.8% | 0.0% |
| `pan_system` | 572,005 | 98.2% | 0.0% |

| Process | Lines | Share |
|---|---:|---:|
| `xpand` | 4,674,502 | 71.5% |
| `clish` | 903,917 | 13.8% |
| `other_process` | 590,192 | 9.0% |
| `cloningd` | 173,482 | 2.7% |
| `kernel` | 165,722 | 2.5% |
| `spike_detective` | 14,165 | 0.2% |
| `pm` | 9,344 | 0.1% |
| `nohdr` | 1,007 | 0.0% |
| `fwd` | 851 | 0.0% |
| `fwk` | 641 | 0.0% |
| `httpd2` | 338 | 0.0% |
| `routed` | 149 | 0.0% |

| Palo Alto subtype | Lines | Share |
|---|---:|---:|
| `general` | 1,269,455 | 60.8% |
| `vpn` | 431,891 | 20.7% |
| `device_telemetry` | 152,569 | 7.3% |
| `userid` | 105,985 | 5.1% |
| `auth` | 82,928 | 4.0% |
| `lacp` | 13,796 | 0.7% |
| `other_subtype` | 6,708 | 0.3% |
| `url_filtering` | 5,642 | 0.3% |
| `ha` | 3,686 | 0.2% |
| `sslmgr` | 3,331 | 0.2% |
| `routing` | 3,217 | 0.2% |
| `satd` | 3,192 | 0.2% |
| `ras` | 3,192 | 0.2% |
| `ntpd` | 2,120 | 0.1% |

98 % of the test week's `cp_messages` and `pan_system` lines are in templates the same device shows on most days.

### 3.7 Findings in the test week

| Signature | Measure | Count |
|---|---:|---:|
| burst_ha / cp_fwk_elg | routine_suppressed_lines | 32 |
| burst_ha / cp_messages | routine_suppressed_lines | 536 |
| burst_ha | episodes | 266 |
| burst_link | episodes | 42 |
| burst_link / pan_system | routine_suppressed_lines | 3,941 |
| crash / cp_cpwd_elg | lines | 493 |
| crash / cp_messages | lines | 109 |
| restart / cp_cpwd_elg | lines | 150 |

| Source | Rate findings, base thresholds | strict | loose | of base: excluded classes |
|---|---:|---:|---:|---:|
| `cp_messages` | 6,470 | 164 | 10,921 | 6,163 |
| `cp_cpwd_elg` | 0 | 0 | 0 | 0 |
| `cp_cpd_elg` | 51 | 20 | 60 | 0 |
| `cp_fwd_elg` | 0 | 0 | 0 | 0 |
| `cp_fwk_elg` | 72 | 67 | 85 | 0 |
| `pan_system` | 14 | 1 | 71 | 0 |

| Finding kind | Device-hours |
|---|---:|
| burst_ha | 180 |
| burst_link | 34 |
| crash | 190 |
| new_fleet_common | 512 |
| new_rare | 136 |
| new_rare_excluded_class | 86 |
| new_rare_signature_class | 31 |
| new_rare_with_severity | 67 |
| rate | 232 |
| rate_excluded_class | 1,435 |
| rate_loose | 440 |
| rate_strict | 120 |
| restart | 9 |

New-template events by source, prevalence and class group:

| Source | Prevalence | Class group | Severity | New-template events |
|---|---:|---:|---:|---:|
| `cp_cpd_elg` | device_only | other classes | no severity word | 12 |
| `cp_cpd_elg` | fleet_common | other classes | no severity word | 43 |
| `cp_cpd_elg` | fleet_common | other classes | with severity | 4 |
| `cp_cpd_elg` | fleet_some | other classes | no severity word | 97 |
| `cp_cpd_elg` | fleet_some | other classes | with severity | 6 |
| `cp_cpwd_elg` | fleet_common | other classes | no severity word | 102 |
| `cp_cpwd_elg` | fleet_common | other classes | with severity | 5 |
| `cp_cpwd_elg` | fleet_some | other classes | no severity word | 35 |
| `cp_cpwd_elg` | fleet_some | other classes | with severity | 27 |
| `cp_cpwd_elg` | fleet_some | signature class | no severity word | 4 |
| `cp_cpwd_elg` | fleet_some | signature class | with severity | 5 |
| `cp_fwd_elg` | device_only | other classes | no severity word | 51 |
| `cp_fwd_elg` | device_only | other classes | with severity | 9 |
| `cp_fwd_elg` | fleet_common | other classes | no severity word | 866 |
| `cp_fwd_elg` | fleet_common | other classes | with severity | 14 |
| `cp_fwd_elg` | fleet_some | other classes | no severity word | 191 |
| `cp_fwd_elg` | fleet_some | other classes | with severity | 93 |
| `cp_fwk_elg` | device_only | other classes | with severity | 4 |
| `cp_fwk_elg` | fleet_common | other classes | no severity word | 3 |
| `cp_fwk_elg` | fleet_some | other classes | no severity word | 77 |
| `cp_fwk_elg` | fleet_some | other classes | with severity | 14 |
| `cp_fwk_elg` | fleet_some | signature class | no severity word | 7 |
| `cp_messages` | device_only | excluded class | no severity word | 101 |
| `cp_messages` | device_only | other classes | no severity word | 25 |
| `cp_messages` | device_only | signature class | no severity word | 2 |
| `cp_messages` | device_only | signature class | with severity | 1 |
| `cp_messages` | fleet_common | excluded class | no severity word | 321 |
| `cp_messages` | fleet_common | other classes | no severity word | 262 |
| `cp_messages` | fleet_some | excluded class | no severity word | 309 |
| `cp_messages` | fleet_some | other classes | no severity word | 312 |
| `cp_messages` | fleet_some | other classes | with severity | 16 |
| `cp_messages` | fleet_some | signature class | no severity word | 19 |
| `pan_system` | fleet_common | excluded class | no severity word | 4 |
| `pan_system` | fleet_common | excluded class | with severity | 14 |
| `pan_system` | fleet_common | signature class | no severity word | 2 |
| `pan_system` | fleet_common | signature class | with severity | 2 |
| `pan_system` | fleet_some | excluded class | no severity word | 36 |
| `pan_system` | fleet_some | excluded class | with severity | 8 |
| `pan_system` | fleet_some | other classes | no severity word | 352 |
| `pan_system` | fleet_some | other classes | with severity | 46 |
| `pan_system` | fleet_some | signature class | no severity word | 13 |
| `pan_system` | fleet_some | signature class | with severity | 37 |

- Rate findings are almost only in `cp_messages`, and 95 % of those are in the excluded classes.
- 3,551 new-template events in one week (5.5 per device-day) is inflated by the short history; the 512 device-hours
  whose new template is fleet-common are most likely messages the learning period was too short to have seen.
- The crash and burst counts are keyword counts and were not checked against message text. Whether the 190
  device-hours with a crash-class line are real crashes cannot be said from this record; a curated signature list has
  to settle it before these counts are used.

### 3.8 The funnel

| Measure | Check Point | Palo Alto |
|---|---:|---:|
| Devices | 55 | 38 |
| Device-hours with lines | 6,480 | 6,193 |

| Policy | Device-hours | Episodes CP | Episodes PAN | Episodes | per observed device-day | per day, fleet |
|---|---:|---:|---:|---:|---:|---:|
| narrow | 67 | 55 | 10 | 65 | 0.10 | 9 |
| base | 375 | 306 | 18 | 324 | 0.50 | 46 |
| strict_rate | 280 | 240 | 13 | 253 | 0.39 | 36 |
| loose_rate | 563 | 437 | 36 | 473 | 0.73 | 68 |
| code_only | 255 | 107 | 33 | 140 | 0.22 | 20 |
| excluded_only | 1,094 | 1,071 | 0 | 1,071 | 1.65 | 153 |
| wide | 2,058 | 1,528 | 59 | 1,587 | 2.45 | 227 |

| Policy | Observed device-days | 0 episodes | 1 | 2 | 3-5 | 6-10 | 11+ | p50 | p90 | p99 | max |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| narrow | 648 | 90.6% | 8.8% | 0.6% | 0.0% | 0.0% | 0.0% | 0 | 0 | 1 | 2 |
| base | 648 | 67.0% | 21.5% | 7.4% | 4.2% | 0.0% | 0.0% | 0 | 2 | 3 | 5 |
| strict_rate | 648 | 70.5% | 21.8% | 6.0% | 1.7% | 0.0% | 0.0% | 0 | 1 | 3 | 4 |
| loose_rate | 648 | 56.9% | 23.8% | 12.3% | 6.9% | 0.0% | 0.0% | 0 | 2 | 4 | 5 |
| code_only | 648 | 83.8% | 12.8% | 2.5% | 0.9% | 0.0% | 0.0% | 0 | 1 | 2 | 5 |
| excluded_only | 648 | 46.3% | 1.7% | 8.0% | 44.0% | 0.0% | 0.0% | 2 | 4 | 4 | 5 |
| wide | 648 | 36.1% | 7.1% | 3.1% | 46.9% | 6.8% | 0.0% | 3 | 5 | 7 | 8 |

| Episodes per test day | day 1 | day 2 | day 3 | day 4 | day 5 | day 6 | day 7 |
|---|---:|---:|---:|---:|---:|---:|---:|
| narrow | 13 | 8 | 11 | 3 | 4 | 23 | 3 |
| base | 41 | 85 | 86 | 17 | 15 | 66 | 14 |
| strict_rate | 38 | 65 | 61 | 11 | 8 | 60 | 10 |
| loose_rate | 58 | 126 | 121 | 24 | 28 | 76 | 40 |
| code_only | 24 | 27 | 39 | 10 | 4 | 29 | 7 |
| excluded_only | 75 | 135 | 149 | 157 | 205 | 198 | 152 |
| wide | 161 | 281 | 268 | 237 | 216 | 253 | 171 |
| devices observed | 90 | 93 | 93 | 93 | 93 | 93 | 93 |

Day 1 is 2026-11-04 and day 7 is 2026-11-10.

### 3.9 Session lines

| Measure | Count |
|---|---:|
| Lines of the admin and session class | 5,317,313 |
| of them with an IPv4 address | 203,047 |
| Distinct addresses in those lines | 64 |
| Devices on which the most widespread address occurs | 50 |
| Lines carrying that address | 49,194 |

One address occurs in session lines on 50 devices and in 24 % of the session lines that carry an address. From the
chunk ledger, `cp_messages` collection ran 0 times on day 1, 165 times on day 2 and 219 to 220 times on days 3 to 7
(55 gateways, four windows). The `excluded_only` episodes per day were 75 on the day without collection and 135 to 205
on the others. If the 75 are the background of other administrator activity, neXus's own collection sessions would
explain about 45 % to 65 % of these bursts on the other days. This compares daily counts; it is not a line-by-line
match.

Meta: bytes_read=19180005234, chunk_errors=0, chunks_listed=22041, chunks_read=22041, devices_cp=55, devices_pan=38, min_learn_days=7, tech_vocab_words=1780, test_days=7, total_seconds=464, truncated_by_time=0, window_days=35

## 4. What would be asked and what would not

Of the 12,673 device-hours with lines in the test week:

| Bucket | Device-hours | Share | Episodes in the week | Asked? |
|---|---:|---:|---:|---|
| No finding | 10,615 | 83.8% | — | No |
| Only excluded-class findings (administrator and session bursts) | 1,094 | 8.6% | 1,071 | No, by rule |
| Signature only (`code_only`) | 255 | 2.0% | 140 | Not by default; fixed text |
| Only fleet-common new messages, or excluded-class findings next to a signature | 334 | 2.6% | not counted | No; reviewed once at fleet level |
| Worth asking (`base`) | 375 | 3.0% | 324 | Yes |

- **By line.** At Level 1 no log line is sent at all. At Level 2 at most 40 lines go with a question: at most 12,960
  lines a week under `base`, against 28.3 million lines written in the test week, under 0.05 %.
- **Sensitivity.** The question volume moves with the rate thresholds: 253 episodes with the strict set, 324 with
  the base set, 473 with the loose set. Dropping rate changes and keeping only rare new messages with an error or
  warning word (`narrow`) leaves 65.
- **By vendor.** 306 of the 324 `base` episodes are on Check Point gateways, 18 on Palo Alto firewalls.
- **By day.** 14 to 86 `base` episodes a day. Three days (2, 3 and 6) carry 73 % of the week.

## 5. Cost of the question volume

Price per level-1 explanation without cache, from the trial record: Haiku 5.5 USD 0.001, Sonnet 5.5 USD 0.017, Opus
5.5 USD 0.034. A month is the test week times 30/7.

| Policy | Eligible questions a month | Haiku 5.5 | Sonnet 5.5 | Opus 5.5 |
|---|---:|---:|---:|---:|
| `narrow` | 279 | USD 0.3 | USD 4.7 | USD 9.5 |
| `strict_rate` | 1,084 | USD 1.1 | USD 18.4 | USD 36.9 |
| `base` | 1,389 | USD 1.4 | USD 23.6 | USD 47.2 |
| `loose_rate` | 2,027 | USD 2.0 | USD 34.5 | USD 68.9 |
| `wide` | 6,801 | USD 6.8 | USD 115.6 | USD 231.2 |

These are ceilings for "every eligible episode is asked". With calls on demand only, by two roles (decision A8), the
real number is whatever people click. The design's upper bound was 2,760 device-days with a call a month; measured,
33 % of device-days have a `base` episode, about 920 device-days a month.

The one-off classification of templates (programme phase C) is a different kind of question: 5,411 templates in all,
and the top 100 of each source (about 600) cover more than 97 % of all lines. At these prices it costs a few dollars
once, and it is the only way the `cp_fwk_elg` volume gets an explanation.

## 6. Limits

- **One week, one pool, no ground truth.** The thresholds were neither tuned nor validated against labelled events.
  The planned check against the failover runs of 2026-10-22 was not done: the detectors only evaluate the last seven
  days, and the run did not single out those devices.
- **Short history.** 17 to 26 learning days at the median. New-template counts are too high for that reason, and no
  hour-of-day or weekday baseline was possible.
- **Keyword classes are unverified.** No message text was read. The crash class and the HA and link bursts count
  words, not vendor events; the excluded class was assigned by words such as `admin` and by process name.
- **Rate detection ran on two sources.** For `cp_cpd_elg`, `cp_fwd_elg` and `cp_cpwd_elg` it could not run; a
  collection-run grain was not measured.
- **Hour grain.** Findings are per device-hour and episodes merge consecutive hours; the design groups within 10
  minutes. Silence, sustained shifts and cross-device correlation were not run.
- **Time.** Device-local time as printed, no zone. A third of `cp_fwk_elg` lines were placed at collection time.
- **Templates.** Only the first 40 tokens of a message count; the technical vocabulary is the 1,780-word stand-in of
  the masking risk measurement.
- **The collection schedule changed inside the test week** (no log collection on its first day), which moves the
  session-line bursts.
- **Not repeated.** The program has no randomness, but it ran once on the full pool. The pool changes with every
  collection, so a later run gives other numbers.

What this record changes in the DRAFT design, for its next revision:

1. §4.4: an hourly rate detector is possible on `cp_messages` and `pan_system` only. For the other sources the
   collection-run grain is the rule, not the fallback, or the detector is not built for them.
2. §4.4 and §4.8: severity cannot come from error words. It has to come from the catalogue class of the template.
3. §4.5: template classification is not an optional first pass. It is the main tool for `cp_fwk_elg`, where the
   volume is repeated messages on ten devices.
4. §6.2 and §3.2: the administrator and session class is the largest class by far and the largest source of rate
   findings; excluding it by rule removes two thirds of all episodes with a finding.
5. §4.8: about 0.5 eligible questions per device per day under `base`, not 2 per device-day with findings every day.

## 7. How it was run (reproduce)

Source in `scripts/measurement/log_question_funnel/`: `LogFunnelMeasure.java`, `run_in_pod.sh`, `selftest.py`,
`tables.py`. `techvocab.txt` and `show_safe.sh` are the files of `scripts/measurement/log_masking_risk/`.

```sh
# on the workstation: compile against the service jar's classes, then self-test on the synthetic corpus
javac --release 21 -cp "BOOT-INF/classes:BOOT-INF/lib/*" -d payload/classes LogFunnelMeasure.java
python3 selftest.py java "payload/classes:BOOT-INF/classes:BOOT-INF/lib/*" ../log_masking_risk/techvocab.txt
cp ../log_masking_risk/techvocab.txt ../log_masking_risk/show_safe.sh run_in_pod.sh payload/
# into the pod (plain tar), run, read back only the filtered aggregates, clean up
tar cf - -C payload . | kubectl -n ui2 exec -i <ui2-service pod> -- sh -c 'mkdir -p /app/tmp/logfunnel && tar xf - -C /app/tmp/logfunnel'
kubectl -n ui2 exec <pod> -- sh /app/tmp/logfunnel/run_in_pod.sh full 0 2400     # label, devices per vendor (0 = all), seconds
kubectl -n ui2 exec <pod> -- sh -c 'cd /app/tmp/logfunnel && sh show_safe.sh full.out' > full.tsv
python3 tables.py full.tsv
kubectl -n ui2 exec <pod> -- rm -rf /app/tmp/logfunnel
```

The JVM ran with `-Xmx2g` next to the service, single-threaded. Do not start it during a deploy e2e or a collection
window; a ship that rolls the pod deletes `/app/tmp`.
