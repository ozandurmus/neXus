# System-log detection engine — data-driven design (phase C of the system-log programme)

**Status: FROZEN (PO 2026-11-24, "Hepsini kabul, FROZEN": every §12 recommendation accepted; decisions in `PO_DECISION_RECORD_2026_11_24_LOG_DETECTION_ENGINE.md`).** It authorizes the phased implementation in §10 within the decisions recorded there. It authorizes no device command, outbound connection or credential. Every value marked PROVISIONAL is a starting value for measurement. Every UNKNOWN stays UNKNOWN until it is measured or cited.

- **Request (PO, 2026-11-24 evening, translated):** "If we tie decisions to scenarios we will either raise alarms rarely
  (a disk or DIMM failure is rare) or miss things. Some templates should simply warn: error, warning -- capture those.
  Not everything is a scenario; sometimes the warnings are already in the logs. And we have really good data: about a
  hundred devices' logs for months. We could analyse that and build an engine from it."
- **Trigger case (2026-11-24, masked):** §1.1.
- **Inputs read** (background and evidence, not authority; the DRAFT 2026-11-10 design is superseded where §1.3 says so):
  - [LOG_ANOMALY_AGENT_DESIGN_2026_11_10.md](LOG_ANOMALY_AGENT_DESIGN_2026_11_10.md) (DRAFT, §4 detector);
  - [PO_DECISION_RECORD_2026_11_10_LOG_ANOMALY_AGENT.md](PO_DECISION_RECORD_2026_11_10_LOG_ANOMALY_AGENT.md) (RATIFIED, direction only);
  - [PO_DECISION_RECORD_2026_11_11_LOG_FUNNEL_NOT_EVALUABLE_RULE.md](PO_DECISION_RECORD_2026_11_11_LOG_FUNNEL_NOT_EVALUABLE_RULE.md) (RATIFIED);
  - [LOG_AGENT_CLAUDE_TRIAL_2026_11_10.md](LOG_AGENT_CLAUDE_TRIAL_2026_11_10.md) (case C22 has the same DIMM shape as the trigger case);
  - [ARGON_LOG_ANOMALY_AGENT_REVIEW_2026_11_10.md](ARGON_LOG_ANOMALY_AGENT_REVIEW_2026_11_10.md);
  - [DEVICE_SYSTEM_LOG_PHASE_B_CONTRACT.md](DEVICE_SYSTEM_LOG_PHASE_B_CONTRACT.md) (APPROVED) and
    [DEVICE_SYSTEM_LOG_MEASUREMENT_FINDINGS_2026_10_17.md](DEVICE_SYSTEM_LOG_MEASUREMENT_FINDINGS_2026_10_17.md);
  - [LOG_QUESTION_FUNNEL_MEASUREMENT_2026_11_10.md](LOG_QUESTION_FUNNEL_MEASUREMENT_2026_11_10.md) and
    [LOG_FUNNEL_BACKTEST_2026_11_11.md](LOG_FUNNEL_BACKTEST_2026_11_11.md) (evidence records);
  - [DATA_SANITIZER_CONTRACT.md](DATA_SANITIZER_CONTRACT.md);
  - source: `V160__system_log_phase_b.sql` (`system_log_chunk`, `system_log_gap`), `SystemLogReadService`,
    `LogMasking` (rule set `logmask-v8`), `SystemLogSettingsService` (`raw_retention_days`), `SanitizerModuleFenceTest`.
- **Source baseline:** branch head `107dd74d`. Repository reading only. No live system, database, device or external
  site was queried for this document; literature citations in §13 come from the author's reading and were not re-read
  in this lane (no outbound connection).
- **Pool facts (measured 2026-11-24, counts only, supplied with the task):** 57 Check Point gateways and 38 Palo Alto
  firewalls. Live lines: `cp_messages` 8.8 M (since 2026-10-09), `cp_fwk_elg` 121.6 M, `cp_cpd_elg` 7.7 M,
  `cp_cpwd_elg` 1.8 M, `cp_fwd_elg` 0.7 M, `cp_mq_mng_elg` 16.5 M, `pan_system` 2.6 M. Collected every 6 hours,
  encrypted per chunk, raw retention per the phase B contract.

## 0. Summary

1. **Three deterministic layers, no LLM in detection.**
   - **Layer A, severity:** vendor severity fields plus a closed list of hardware and kernel markers. A severe line
     becomes evidence without any scenario.
   - **Layer B, statistics learned from the pool:** a fleet template catalog mined from masked text, with per-template
     fleet prevalence, per-device robust baselines and first-seen dates.
   - **Layer C, scenario cards:** named findings with action text for a few known high-value cases.

   Cards refine A and B. They are not the only way a finding is raised.
2. **A finding needs no scenario.** A fleet-rare template that turns up on a device, spikes against its own baseline,
   or carries a hardware marker becomes a finding. The trigger case (45 EDAC corrected-error lines on one DIMM slot
   label in 4.5 hours, machine-check lines after each boot) must rank in the top three of its device-day from layers A
   and B alone. The DIMM card in layer C only names it and adds the action text (§4.5).
3. **One score, used only for ordering:** `score = 100 x S x R x C`, where S is severity, R is fleet rarity and C is
   change on this device. A product, not a sum, because attention needs all three: a severe line seen on every device
   is background, a rare harmless line is a curiosity, and an unchanged chronic line is known. The finding severity
   (CRITICAL / ATTENTION / INFO / UNKNOWN) comes from rules. Statistics alone can never produce CRITICAL (§7.1).
4. **Learning means measuring, then freezing.** A census (P0, built by a separate lane) measures the pool. The catalog
   is mined offline on a training window, frozen as a versioned catalog and matched deterministically online. Every
   catalog change is a reviewed diff. Thresholds are tuned on a hold-out replay, with findings counted per device-day
   and a labelled sample as the precision proxy (§6).
5. **Coverage is part of the answer.** Gaps, `NOT_EVALUABLE` pairs, unparsed lines and windows where masking was
   unavailable are shown as UNKNOWN, never as healthy (§7.4).
6. **Masked processing only.** Lines are masked by `LogMasking` before mining. The new tables hold template ids,
   vocabulary-form template text, counts, timestamps and evidence pointers, never a log line (§8).
7. **Phasing:** P0 census → P1 severity and hardware markers → P2 catalog, rarity and rate → P3 scenario cards → P4
   the LLM explainer under the 2026-11-10 decisions. The trigger case is golden case G1 and is checked in every phase
   (§10).
8. **Reported, not reconciled (§1.5):**
   - the PO brief says "worker job", but the sanitizer fence keeps masking out of the worker;
   - this design adds a composite score, which the 2026-11-10 DRAFT ruled out;
   - pool statistics come close to the 2026-11-10 non-goal "no machine-learning model trained on customer logs";
   - the ratified 2026-11-11 rule left open whether a signature finding is shown on a young pair.

   Each one is a PO question in §12.

## 1. Starting point

### 1.1 Trigger case (masked)

| Fact (2026-11-24) | Value |
|---|---|
| Device | `FW-TANGO-04`, a Check Point VSX cluster member (collection is VS0 only, phase B §2.2) |
| Event | Two unexpected reboots |
| `cp_messages` content (stored chunks) | 45 EDAC corrected-error (CE) lines, all on one DIMM slot label, within 4.5 hours; machine-check ("Hardware Error") lines after each boot; no uncorrected-error (UE) line; no OOM-killer line |
| What Overwatch raised | Nothing |

Overwatch had every line it needed and raised nothing, because phase C, the detector, does not exist. Phase B stores
chunks and counters only (phase B §2.2). The only log-derived signals in the product are:

- the cpwd restart rule and sar CPU/memory p95 on the System health screen (`SystemHealthService`, `CpwdRestartLines`);
- the crash and dump file alert (`device_crash_alert`).

None of them reads kernel lines.

The case has the shape of trial case C22 ("Check Point corrected memory errors increasing on one DIMM"). All three
models and all three judges handled C22 well (LLM trial record). The trial gave the model a finding that a detector
had already produced. The missing piece is the detector, and that is what this design specifies.

### 1.2 What the earlier measurements already settle

| Measured fact | Record | Consequence here |
|---|---|---|
| An error word is in 97 % of `cp_fwk_elg` lines and 59 % of `cp_fwd_elg` lines | funnel §3.5 | A free-text error word cannot be the severity signal. Layer A uses vendor fields and a closed marker list. Noise is controlled per template (§3.4) |
| Six sources hold 5,411 templates in vocabulary form; the top 100 per source cover 81 to 100 % of lines | funnel §3.4 | A fleet catalog is small enough to review by hand |
| Ten of 55 gateways write 98 % of `cp_fwk_elg`; ten templates hold 98 % of its lines | funnel §0 | Volume is a few templates on a few devices. Per-template dedup and chronic marking remove most of it |
| `cp_messages` is 77.5 % administrator and session lines | funnel §3.5 | That class gets a low S by default (§3.1) |
| A per-line time exists on every line only in `cp_messages` and `pan_system`; about 5 % of lines in `cp_cpd_elg` and `cp_fwd_elg` carry one | funnel §0, phase B §4.7 | Hourly rate baselines run on those two sources only. The others use the 6-hour collection-run grain |
| With 2 to 5 days of history, the draft detector would query 89 to 92 % of Check Point device-windows | backtest §0 | History gates rate and new-template findings (ratified 7-day rule) |
| Check Point line times read as Istanbul wall time match the known events; read as UTC they do not | backtest §0 | Times stay `DEVICE_LOCAL` as stored. Windows are evaluated in that basis |
| `cp_mq_mng_elg` line form was never measured (no tail gate, not read by the funnel) | phase B §4.7, funnel §2.2 | The source is out of layers A and C until the census measures it. Layer B counts it only after P0 |

### 1.3 Which parts of the 2026-11-10 design this supersedes

The 2026-11-10 design stays in the repository unchanged except for a pointer banner. This document supersedes its
**detector** sections only:

| 2026-11-10 section | Status after this document |
|---|---|
| §4.2 Data the detectors need (tables) | Superseded by §8.3 |
| §4.3 Template extraction | Superseded by §4.1 (masking before mining; offline Drain build, frozen catalog, deterministic online matching) |
| §4.4 Detectors (first set), its severity table and the sentence "There is no composite score" | Superseded by §3, §4, §5 and §7. **Exception:** the 2026-11-11 amendment inside §4.4 (`NOT_EVALUABLE` below 7 days, `BASELINE_LEARNING` 7 to 13 days) comes from a RATIFIED PO record. A DRAFT cannot supersede it, so it carries over unchanged (§7.4) |
| §8.1 rows "Operations › System health", "Device logs panel", "Operations: findings feed", and the template catalogue in the "Log agent" row | Superseded by §9 |
| §8.2 Finding (detector output) | Superseded by §7.2 |
| §10 phases 1 and 1b and measurements E2, E3, E4, E7, E8 | Superseded by §10 (P0 to P3) |
| §11 "Determinism" and "Evaluability" | Carried over into §11 |
| Not superseded | §0 items 1 and 2 (detect first, explain second); §1; §3 (masking boundary and egress); §4.1 (rules: the LLM never creates, closes or re-grades a finding); §4.5 to §4.8; §5 to §7; §8.3; §9; §12. These remain the design for P4 |

### 1.4 Terms

| Term | Meaning here |
|---|---|
| Line | One stored line after the phase B secret redaction. Multi-line `cp_cpd_elg` records are joined into one record before processing |
| Masked line | The line after `LogMasking` (the aiview projection, rule set `logmask-v8` today) |
| Normalised line | The masked line with every variable part turned into a typed placeholder (§4.1) |
| Template | A normalised token sequence that the frozen catalog assigns one id. Example in vocabulary form: `kernel: EDAC MC<NUM>: <NUM> CE memory read error on <DIMM_LABEL> (channel:<NUM> slot:<NUM> page:<HEX> offset:<HEX> grain:<NUM>)` |
| Cohort | The devices that are compared for prevalence: same vendor and same source, with evaluable history in the window |
| Evidence row | A counted occurrence of a template in a window, with up to three pointers (chunk id, offset, length). It is not a finding by itself |
| Finding | A record that one deterministic rule (layer A, B or C, with id and version) raised from evidence rows |
| Device-day | One device and one `DEVICE_LOCAL` calendar day |

### 1.5 Contradictions reported, not reconciled

1. **"Worker job" and the sanitizer fence.** The brief places detection in a worker job after each collection window,
   with masking before mining. `SanitizerModuleFenceTest` forbids every module except the service from depending on
   the sanitizer, and `LogMasking` lives in the service for that reason. The two cannot both hold as written. §8.1
   proposes where the job runs. PO question 2.
2. **Composite score.** 2026-11-10 §4.4 says "There is no composite score". This design adds one for ordering only,
   and severity still comes from rules. It is a deliberate change. PO question 3.
3. **"No machine-learning model trained on customer logs"** (2026-11-10 §1.4, non-goal). The catalog, prevalence and
   baselines are deterministic descriptive statistics over the pool: inspectable counts, medians and a frozen
   template list. There are no learned weights, the results are reproducible, and nothing is trained outside
   HOST-A. This design reads them as outside the non-goal. That reading is the PO's to confirm. PO question 9.
4. **Signature findings on a young pair.** The ratified 2026-11-11 rule bars model questions from a pair with under 7
   days of history. It explicitly left open whether a deterministic signature finding on such a pair is shown to an
   operator (record §3). This design proposes yes for layer A and layer C hardware findings. PO question 1.
5. **Start of the pool.** The census facts give `cp_messages` lines "since 2026-10-09". The 2026-11-11 backtest
   recorded the first chunk on 2026-10-17. Both can be true: the first read of a file collects the lines already on
   the device, so line times can predate the first chunk. That reading is UNKNOWN until the census separates line
   time from `collected_at`. It matters for the training window (§6.1).
6. **Raw retention.** Phase B §5.3 sets 30 days as the default (`raw_retention_days`, PO-settable). Lines since
   2026-10-09 still being live on 2026-11-24 implies an effective setting above 30 days, or line times older than
   their chunks (item 5). The census reports the effective setting. This design does not assume either.

## 2. Pipeline overview

```
 phase B collection (unchanged) --> encrypted chunks + system_log_chunk / system_log_gap / system_log_run
                                                   |
                         [1] log-analysis job, per closed collection window (§8.1)
                                                   |
   decrypt chunk in memory --> join multi-line records --> LogMasking (logmask-vN) --> normalise (typed placeholders)
                                                   |
                         [2] match against frozen catalog vK  (unmatched --> provisional id, counted)
                                                   |
           evidence rows: (device, source, template, bucket, count, <=3 pointers)   -- no text
                                                   |
     [3] layer A severity     [4] layer B prevalence / baseline / new / rate / silence / boot burst
                                                   |
                         [5] layer C scenario cards (consume A and B, add names and action text)
                                                   |
     log_finding (severity by rule, score for ordering, coverage state) --> screens, CLI, P4 explainer (on demand)

 offline, per catalog version: masked normalised lines of the training window --> Drain build --> review --> freeze vK+1
```

Raw text exists only in memory inside the job, exactly as on the existing masked read path. Nothing in this pipeline
writes a line, masked or not, to a table, a file or a log.

## 3. Layer A — severity without scenarios

### 3.1 Severity sources per log family

S is the severity weight in [0.05, 1] that every evidence row carries.

| Family | Field or token | Values to S (PROVISIONAL) | Measured state |
|---|---|---|---|
| `pan_system` | `severity` (vendor field, present on all 28-field entries) | critical 1.0; high 0.8; medium 0.5; low 0.3; informational 0.15 | Four values observed in 3,900 sampled entries: informational 3,750, medium 116, critical 30 (on 6 firewalls), high 4. `low` was not observed. Its existence is vendor-documented in general but not cited here, so UNKNOWN |
| `cp_messages` (Gaia syslog) | Syslog priority, where present in the stored line | emerg/alert/crit 1.0; err 0.5; warning 0.3; notice/info 0.15; debug 0.05 | **Probably absent.** All 1,060 sampled lines match `<Mon> <DD> <HH:MM:SS> <YYYY> <host> <prog>[<pid>]: <text>`, which has no priority or facility field. Changing the device's syslog format would be a device configuration change and is out of scope. The census confirms presence as a count. If absent, S for this family comes from the marker list (§3.2) and the catalog class only |
| `cp_fwk_elg` | Header token `[<LEVEL>]` in the connection-line form | Mapping as for syslog once the token values are known | Header on 625 of 901 visible sampled lines. The token values were masked in the measurement: UNKNOWN until the census lists them (vocabulary words only) |
| `cp_cpd_elg`, `cp_cpwd_elg`, `cp_fwd_elg` | No level field in the measured header `[<proc> <pid> <tid>]@<host>[<DD> <Mon> <HH:MM:SS>]` | A leading level word in a fixed position after the header (closed list: EMERG, ALERT, CRIT, ERR, ERROR, WARN, WARNING, NOTICE, INFO, DEBUG) maps like syslog. A level word anywhere else is ignored | Whether such a fixed-position token exists is UNKNOWN. The census counts it by position |
| `cp_mq_mng_elg` | Not measured | None until measured | UNKNOWN |
| Any template with no severity source | — | **0.3 by default.** An unknown severity is not a low severity (UNKNOWN law) | — |
| Operator override in the catalog | Per template, audited, versioned | Any listed value, with a reason | — |

A vendor severity field is **field presence, not semantic proof** (AGENTS.md evidence laws). For the score, PAN
`critical` is taken to mean "the vendor considers this critical". What a given event means stays with the template's
catalog entry and, where it is load-bearing, with a cited vendor document.

### 3.2 Hardware and kernel markers (closed list)

The markers match the **normalised template**, not free text. They are generic Linux kernel messages, not scenarios:
none of them names a component model or a failure story. All provenance stays `PROVISIONAL` until two checks pass:

- (a) the census counts each marker on the pool, by device count and line count, to show that the pattern matches
  real lines;
- (b) the message form is cited from Linux kernel documentation or the vendor (Check Point for Gaia), as the vendor
  semantics law requires for a safety-relevant claim.

| Marker id | Matches (template form, before citation) | S | Note |
|---|---|---|---|
| `hw.edac.ce` | EDAC corrected-error line (`EDAC MC<NUM>: <NUM> CE ...`) | 0.8 | The DIMM slot label is kept as a keyed token, so slots can be told apart by equality only (identity law: opaque, no parsing of its parts) |
| `hw.edac.ue` | EDAC uncorrected-error line (`... UE ...`) | 1.0 | |
| `hw.mce` | Machine-check line with the kernel's hardware-error prefix (`[Hardware Error]: ...`) | 0.8 | Corrected and fatal machine checks share the prefix; a fatal sub-form gets 1.0 only once it is cited |
| `kern.panic` | Kernel panic line | 1.0 | Usually seen only after the reboot, if at all, because a panic may not reach the disk |
| `kern.oops` | Kernel oops or BUG line | 0.8 | |
| `kern.oom` | OOM-killer line (killed process) | 0.8 | The process name becomes a keyed token |
| `io.error` | Block-layer I/O error line | 0.8 | Device names are typed placeholders |
| `fs.readonly` | Filesystem remounted read-only | 1.0 | |
| `kern.boot` | Kernel boot banner (first line of a boot) | 0.15 | Not a severity marker: it is the anchor for boot-burst detection (§4.3) and for the unexpected-reboot card (§5) |

New markers are added only by a catalog version (§6.5), with the same two provenance checks. Marker count and wording
are configuration with a version. They are never edited ad hoc.

### 3.3 Every severe line becomes evidence

Every line with S ≥ 0.5, and every marker line, produces an **evidence row**: device, source, template, bucket,
count, first and last time, pointers. No scenario and no baseline is required, which answers the PO's "some
templates should simply warn". An evidence row becomes a **finding** under the noise rules below. An evidence row
that does not become a finding stays countable and visible in the device's template list. Nothing is dropped.

### 3.4 Noise control

1. **Dedup.** One evidence row per (device, template, bucket). A bucket is an hour where lines carry a time, else the
   collection run. A burst of 10,000 identical lines is one row with a count.
2. **Incident grouping.** Findings on one device within 10 minutes form one incident (PROVISIONAL). The same template
   on many devices in one window becomes one fleet finding, such as a vendor update service failing everywhere.
3. **Chronic marking.** A template present on at least 80 % of the device's usable baseline days, at a rate within
   its baseline, is `CHRONIC` on that device. A chronic template raises a finding only on a rate change (§4.3), never
   for its presence. This is what turns the 97 %-error-word `cp_fwk_elg` into a handful of rows.
4. **Fleet background.** A template on at least 50 % of its cohort's devices in the window is `FLEET_BACKGROUND`.
   It is listed as INFO and raises ATTENTION only through a rate change.
5. **Hardware exemption.** Markers with S = 1.0 (`hw.edac.ue`, `kern.panic`, `fs.readonly`) are exempt from rules 3
   and 4: they always raise a finding. `hw.edac.ce`, `hw.mce`, `kern.oom` and `io.error` are exempt from rule 4 only.
   A chronic corrected-error stream on one device is still a known state, and it raises again on any rate change.
6. **Per-template rate limit.** After a finding is raised, the same (device, template, rule) raises again only after
   a quiet period (PROVISIONAL 24 hours), or sooner if the score at least doubles or the severity rises. The counts
   keep accumulating on the open finding.
7. **Suppression and acknowledgement** (§7.3) apply after all of the above.

## 4. Layer B — statistics learned from the pool

### 4.1 Mining on masked text

1. **Mask first.** One `LogMasking` session per chunk, rule set recorded per chunk. If masking is unavailable
   (`MaskingUnavailableException`), the chunk is not processed and the window is UNKNOWN with reason
   `MASKING_UNAVAILABLE`. It is never processed unmasked.
2. **Normalise.** Every variable part becomes a typed placeholder:
   - numbers, hex, paths, interface names, MAC and address forms;
   - quoted strings, pids and timestamps;
   - the phase B `<redacted>` marker, as its own placeholder (Argon finding 8);
   - **every masking pseudonym class**: `FW-*`/`CLS-*` device and cluster pseudonyms, `h-*` host labels, `USR-*`,
     `SN-*`, `POL-`/`RUL-`/`OBJ-`/`ZON-`/`VSN-`/`DMN-` estate names, and masked addresses.

   Without that last step, templates would split per device on pseudonyms. Check Point keeps the process name as the
   first key. Palo Alto keeps the vendor enumeration (`type`, `subtype`, `eventid`, `module`) as the first key, and
   the masked `opaque` text is mined inside each key.
3. **Build offline with Drain.** Drain is a fixed-depth parse tree with a token-similarity threshold (He et al. 2017;
   among the most accurate parsers in the LogPAI benchmark, Zhu et al. 2019; §13). It runs offline, per source, over
   the normalised lines of the training window. Starting parameters are depth 4 and similarity 0.4 (PROVISIONAL,
   tuned in P0 on template count, top-100 coverage and a labelled split/merge sample).
4. **Freeze.** The result is reviewed (§6.5) and frozen as catalog version K.
5. **Match online, deterministically.** Each normalised line is matched against frozen catalog K: exact token
   sequence first, then a lookup in K's frozen tree that never creates a cluster.

   Drain run online depends on arrival order (the reason 2026-11-10 §4.3 rejected it), and this split keeps that out
   of production: the same chunk under the same catalog version always gives the same template ids.
6. **Unmatched lines.** A line that matches nothing gets a **provisional template id**: a keyed digest of its
   normalised token sequence. It is counted, it can raise `NEW` and `FLEET_RARE` findings like any template, and it is
   folded into a catalog template at the next build. The fold is recorded in the diff.
7. **Template id.** A keyed HMAC of (source, normaliser version, normalised token sequence), computed **before** the
   closed-vocabulary filter (Argon finding 6, adopted 2026-11-10). The stored and displayed text is the
   closed-vocabulary form: every word outside the technical vocabulary becomes `<W>`. The id is keyed, so it cannot be
   reversed by a dictionary attack on the text.

### 4.2 What the catalog holds per template

| Statistic | Definition | Grain |
|---|---|---|
| Fleet prevalence `n_t` | Number of cohort devices with at least one line of the template in the window. Shown as "on n of N gateways" | Training window, then rolling 28 days |
| Cohort size `N` | Cohort devices with evaluable history in the same window | Same |
| Per-device baseline | Median `m` and median absolute deviation `MAD` of the count per bucket, over the device's usable baseline buckets. Kept separately per HA role segment and per software-version segment (§6.4) | Hour for `cp_messages` and `pan_system`; collection run (6 h) for the others |
| Presence share | Share of usable baseline days on which the template occurs on the device (input to `CHRONIC`) | Day |
| Periodicity | Share of usable baseline windows in which the template occurs (input to `SILENCE`) | Bucket |
| Post-boot profile | Median count in the 30 minutes after a `kern.boot` anchor, over earlier boots in the cohort (input to `BOOT_BURST`) | Per boot |
| First seen | First bucket in which the template occurred, per device and fleet-wide | — |
| Class | Severity source (§3.1), marker id (§3.2), operator class, card links | Catalog version |

Usable buckets exclude windows with a gap, the first-run baseline read, `BACKLOG` reads, the day of a software
version change and windows already flagged by a CRITICAL finding (2026-11-10 §4.4 rules, carried over). A flagged
ATTENTION window is **not** excluded automatically (Argon finding 7: "flagged windows not auto-excluded").

### 4.3 Statistical findings

| Kind | Raised when (PROVISIONAL thresholds) | Needs history |
|---|---|---|
| `NEW_ON_DEVICE` | A template occurs on the device for the first time since its learning period began | Pair evaluable (≥ 7 days, ratified rule) |
| `FLEET_RARE` | A template with `n_t / N` ≤ 5 % (at most 2 of 57 Check Point gateways, 1 of 38 Palo Alto firewalls) occurs on the device in the window, and is new or rate-changed there | Fleet window only; the device-side part needs the pair to be evaluable |
| `RATE_SPIKE` | `z ≥ 6` and `x ≥ 5 · max(m, 1)` and `x ≥ x_min` (20 for S < 0.5; 3 for markers; 5 otherwise). See §4.4 for `z` | Pair evaluable and not `BASELINE_LEARNING` |
| `SILENCE` | A template with periodicity ≥ 90 % is absent in a bucket whose run outcome is `COLLECTED`, with no overlapping gap | Same as `RATE_SPIKE` |
| `BOOT_BURST` | After a `kern.boot` anchor, a template's count in the next 30 minutes exceeds its post-boot profile by the `RATE_SPIKE` test, or the template is absent from the profile and has S ≥ 0.5 | Cohort post-boot profile with at least 5 boots, else the device's own; fewer than 5 boots means UNKNOWN for this kind |

### 4.4 Score

```
score = 100 x S x R x C            (0 to 100; used to order findings, never to set severity)

S  severity weight, 0.05 to 1        (§3.1, §3.2; default 0.3 when unknown)

R  fleet rarity, 0 to 1:   R = ln((N + 1) / (n_t + 1)) / ln(N + 1)
       N   = cohort size, n_t = cohort devices with the template in the window
       (an inverse-document-frequency weight over devices; a template never seen before in the fleet has R = 1)

C  change on this device, 0.1 to 1:  C = max(C_new, C_rate, C_boot, C_silence, 0.1)
       C_new     = 1 for NEW_ON_DEVICE, else 0
       C_rate    = clip((z - 3) / (10 - 3), 0, 1),  z = (x - m) / sigma,
                   sigma = max(1.4826 x MAD, sqrt(m), 1)            (the scale floor: Argon finding 7)
       C_boot    = 1 for BOOT_BURST, else 0
       C_silence = 1 for SILENCE, else 0
       0.1       = present but unchanged (chronic): visible at the bottom, never at the top
```

Values of R for the Check Point cohort (N = 57): a template on 0 devices before gives 1.00, on 1 gives 0.83, on 2
gives 0.73, on 5 gives 0.56, on 10 gives 0.41, on 29 gives 0.16 and on all 57 gives 0.

**Why a product.** Each factor is a necessary condition for an operator to want to look:

- a severe template on every device (R ≈ 0) is the estate's background;
- a rare template that is informational (S ≈ 0.15) is a curiosity;
- a rare severe template that has looked the same on this device for weeks (C = 0.1) is a known chronic state.

With a sum, any one large factor could carry a finding to the top. With a product, the top needs all three. Rare and
severe therefore ranks first, provided something changed.

**Robust baseline.** The scale is `1.4826 x MAD`, the consistent estimator of the standard deviation under normality
(Rousseeuw and Croux 1993; Leys et al. 2013). It is not moved by the very bursts it is meant to find. The floor
`max(..., sqrt(m), 1)` stops a zero MAD, which is common for templates whose usual count is 0 or constant, from
turning one extra line into an infinite score. `sqrt(m)` is the Poisson standard deviation of a count with mean `m`.

**What the score is not.** It does not decide severity, does not decide whether a finding exists, and is not a
probability. It orders findings inside a device-day, a fleet list and the chip, and it is a tuning metric in the
backtest. Its three factors are shown next to it on every finding.

### 4.5 The trigger case under this score (no DIMM rule involved)

The fleet prevalence of the EDAC templates is UNKNOWN until the census. The worked values assume they occurred on at
most 2 of 57 gateways in the window; the census replaces the assumption with the measured `n_t`.

| Evidence on the device-day of `FW-TANGO-04` | S | R | C | Score |
|---|---|---|---|---|
| EDAC CE template, 45 lines in 4.5 hours on one slot-label token (`hw.edac.ce`) | 0.8 | ≥ 0.73 | 1 (new on device, or `z` ≫ 10 against a baseline of 0) | ≥ 58 |
| Machine-check template after each boot (`hw.mce`, `BOOT_BURST`) | 0.8 | ≥ 0.73 | 1 | ≥ 58 |
| Kernel boot banner and the normal start-up templates | 0.15 | fleet-common, small | ≤ 1 | ≤ 15 |
| `cp_fwk_elg` and `cp_messages` background (chronic, administrator and session lines) | 0.05 to 0.5 | any | 0.1 | ≤ 5 |
| Strongest plausible competitor: a rate spike of a one-device `cp_fwk_elg` error template | 0.5 | 0.83 | ≤ 1 | ≤ 42 |

From layers A and B alone, the two hardware templates take the top two places of the device-day. They are grouped
into one incident because both are within 10 minutes of a boot. No DIMM scenario was used: `hw.edac.ce` is a generic
kernel marker in the same closed list as the OOM killer. The layer C DIMM card (§5) then names the incident, adds the
action text and raises it from ATTENTION to CRITICAL because of the reboots.

**Acceptance (golden case G1):** in the P2 replay, the EDAC incident ranks in the top 3 of its device-day. The replay
also records, for information only, the rank when the marker list is switched off (S = 0.3 default for every kernel
template). That shows how much of the result depends on the marker list and how much on statistics alone. If the
census shows the EDAC templates are common in the fleet (high `n_t`), R falls. The case then ranks through the
marker's hardware exemption and the rate change. That outcome is recorded, not argued away.

### 4.6 Expected windows

Some windows legitimately produce new templates and bursts:

- a software upgrade (version change in inventory);
- a policy install (configuration collection);
- a failover that Overwatch itself ran (the failover job ledger);
- an HA role change (cluster state history).

A statistical finding (layer B) that falls inside such a window is labelled `EXPECTED_WINDOW` with the reason and is
capped at INFO. Layer A markers with S ≥ 0.8 and layer C cards are **not** capped, because a DIMM does not fail
because of a policy install. The window sources are existing Overwatch records. Joins use device ids and job ids,
never display names.

## 5. Layer C — scenario cards

A card is a versioned rule over layer A and B evidence. It adds a name, an action text, evidence fields and a severity
rule. A card never sees a line that layers A and B did not count, and removing a card removes no finding: the
underlying A/B finding stays, unnamed.

| Card id | Trigger (over A/B evidence) | Severity | Evidence fields | Action text (draft) |
|---|---|---|---|---|
| `card.memory.dimm` | `hw.edac.ce` count ≥ 10 in 24 h on one slot-label token, or any `hw.edac.ue`; `hw.mce` within ± 1 h counted as context | ATTENTION; **CRITICAL** if any UE, or if an unexpected reboot (card below) happens within 24 h of CE lines | CE count per slot-label token, first and last time, CE per hour, UE count (0 is shown as 0), MCE count after each boot, OOM count, reboot count, peer member's same-window CE count or its UNKNOWN state | "Corrected memory errors are concentrated on one memory slot. Open a hardware case with the vendor; plan a controlled failover and replacement; check whether the peer member shows the same." |
| `card.disk` | `io.error` ≥ 3 in 1 h, or `fs.readonly` | ATTENTION; CRITICAL for `fs.readonly` | Count, device placeholder token, first and last time, artefact-volume and device disk context where already collected | "Disk I/O errors or a read-only filesystem. Check the disk health and the hardware status; plan replacement." |
| `card.process.crashloop` | The same process (keyed token) restarted ≥ 3 times in 1 h (the existing `cpwd-restart-v1` idea), or a new crash/dump file (`device_crash_alert`) together with a restart | ATTENTION; CRITICAL at ≥ 3 restarts with a new dump file | Process token, restart count, dump file count, `kern.oom` in the window | "A process is restarting repeatedly. Check the crash files and the vendor support article for the process." |
| `card.ha.flap` | HA state-transition templates ≥ 3 in 10 min or ≥ 6 in 1 h on one member (classed in the catalog, not by keyword) | ATTENTION | Transition count, window, the peer member's own count. One member's report about its peer is **not** corroboration (AGENTS.md evidence laws) | "Cluster state changed repeatedly. Compare both members' HA state screens; check the sync and monitored interfaces." |
| `card.interface.flap` | Link down/up templates ≥ 3 in 10 min or ≥ 6 in 1 h for one interface token | ATTENTION | Interface token, transition count, both members' counts | "An interface is flapping. Check the cabling, the switch port and the counters." |
| `card.logserver.disconnect` | Log-transport disconnect templates (classed in the catalog), or `SILENCE` of the device's periodic log-transport templates | ATTENTION | Count, duration, silence window | "The device lost its log server connection; logs may be buffered or lost." |
| `card.reboot.unexpected` | A `kern.boot` anchor with no shutdown-sequence template (classed in the catalog from planned reboots; PROVISIONAL) in the 30 min before the last line preceding it, and no gap overlapping that boundary. **With a gap there, the card is UNKNOWN**, never "unexpected" | ATTENTION; CRITICAL for two or more in 7 days, or together with `card.memory.dimm` | Boot count, boundary coverage, last-line time before each boot, shutdown templates found (0 shown as 0), uptime from inventory where it exists as corroboration | "The device rebooted without a clean shutdown sequence. Check the hardware event log and the crash files." |

Each card carries:

- provenance: `MEASURED` (tested against labelled pool events), `VENDOR_DOC` (with a citation) or `PROVISIONAL`;
- its golden cases.

A `PROVISIONAL` card may produce at most ATTENTION. CRITICAL needs `MEASURED` or `VENDOR_DOC` (§7.1).

## 6. Learning from the data, honestly

### 6.1 Windows

| Window | Definition (PROVISIONAL; dates follow from the census, §1.5 item 5) |
|---|---|
| Training | The first 28 days of usable history per device-source, at least 7 (ratified rule). With `cp_messages` from 2026-10-09 by line time, that is about 2026-10-09 to 2026-11-05 |
| Hold-out | Every day after the training window that is still in the pool, about 2026-11-06 to 2026-11-24 (19 days). The trigger case falls inside it, so G1 is a true out-of-sample test |
| Rolling baseline in production | Trailing 28 days, recomputed daily, excluding unusable buckets (§4.2) |
| Catalog rebuild | Monthly, or after a fleet-wide upgrade. Always a new version, never an edit |

**History is perishable.** Raw chunks expire after the retention period, but derived counts do not (§8.5). If
counting does not start before the oldest chunks expire, that history cannot be rebuilt later. P0 therefore stores
the census counts, and P2 counts from the first day it runs. PO question 5 covers whether to raise raw retention
temporarily.

### 6.2 Backtest

1. **Replay** the 30 days that end on the newest complete day (or the whole hold-out, if shorter), one day at a time.
   Each day uses the catalog and the baselines **as they would have been on that day**: no look-ahead. A template
   first seen on day d is not in the catalog for days before d.
2. **Count** findings per device-day, per layer (A, B, C), per kind and per severity. Report median, p90 and maximum
   per device-day and per device-week, separately for Check Point and Palo Alto, and the fleet total per day.
3. **Compare** with the alert budget (§7.2) and with the 2026-11-10 funnel record. That record measured about 46
   eligible model questions a day under its `base` policy. The two numbers measure different things (questions and
   findings), and both are reported.
4. **Golden cases** (recall proxy). Each must produce the expected finding in the replay:

| Id | Case | Expected |
|---|---|---|
| G1 | `FW-TANGO-04`, 2026-11-24: CE burst on one slot label, machine checks after boots, two reboots | Top 3 of the device-day from A+B (§4.5); `card.memory.dimm` CRITICAL and `card.reboot.unexpected` from P3 |
| G2 to G4 | The three failover runs of 2026-10-21/22 (backtest record) | HA findings on all six members, labelled `EXPECTED_WINDOW` because Overwatch ran them. A failover that Overwatch did not run is not labelled |
| G5 | The crash-file alert of 2026-11-09 | `card.process.crashloop` or a layer A finding |
| G6 | Planned reboots known from change records, if the PO can name any (masked) | Not `card.reboot.unexpected`. This is the card's false-positive test |

The failover runs precede any pair with 7 days of history (backtest record), so under the ratified rule G2 to G4
can test layers A and C only. That is reported as such.

5. **Precision proxy.** Draw a stratified random sample of 100 hold-out findings: 40 from the top score decile, 40
   uniformly at random, 20 from the bottom half, with every layer and severity represented. Each finding is labelled
   independently by the PO and by an engineering agent as `ACTIONABLE` (an operator should have seen it),
   `INFORMATIVE` (true, no action), `NOISE` or `CANNOT_TELL`. The labellers read the finding and its masked evidence
   through the aiview projection only.

   Reported: precision proxy = (ACTIONABLE + INFORMATIVE) / (labelled − CANNOT_TELL), the actionable share per
   severity, and the agreement between the two labellers. No external model labels pool data: that would be an
   egress, which the 2026-11-10 decisions have not approved.
6. **Targets (PROVISIONAL, PO question 4):**
   - CRITICAL: at least 90 % `ACTIONABLE`;
   - ATTENTION: at least 60 % `ACTIONABLE` or `INFORMATIVE`;
   - every golden case found;
   - the budget met.

   A miss means changing thresholds or classes in a new catalog or configuration version, then running a new
   backtest. The backtest is never edited after the fact.

### 6.3 Determinism and reproducibility

Same chunks + same masking rule set + same catalog version + same configuration version gives the same evidence rows
and the same findings. Each finding records all four versions. Re-indexing is idempotent per (chunk, catalog
version, masking rule set).

### 6.4 Upgrade and HA-role awareness

- **Version segments.** A software version change (from inventory) starts a new baseline segment for the device.
  Until the segment has 7 usable days:
  - `NEW_ON_DEVICE` is judged against the cohort devices already on that version. A template that is new on the
    device but common on that version is `UPGRADE_NOVELTY`, INFO;
  - rate findings use the cohort's per-version median, scaled by the device's own previous ratio, with
    `BASELINE_LEARNING` shown.
- **HA role segments.** An active and a standby member write different templates at different rates. Baselines are
  kept per role, where cluster state history gives the role. Where it does not, the baseline is pooled and the
  finding carries `HA_ROLE_UNKNOWN`. A role change is an expected window (§4.6).
- **Cohort.** Prevalence is computed per vendor and source. A finer cohort (model family or version) is PO question
  10.

### 6.5 Freezing and diffing a catalog version

A catalog version has:

- a manifest: normaliser version, masking rule set, Drain parameters, training window, source list, marker list
  version and class table;
- a digest over the sorted (template id, class) list;
- review state `DRAFT` → `FROZEN`. Only `FROZEN` versions are matched online.

The diff against the previous version lists:

- templates added, removed, merged and split, each with its line share;
- class and severity changes;
- marker list changes;
- the mapping old id → new id(s). Counts recorded under the old version are carried through merges. Where a split
  cannot be mapped because the raw chunks have expired, the history is marked `UNKNOWN_BEFORE` with the date.

`security_admin` freezes a version from the admin screen or the CLI (§9). The freeze is audited. A version is never
edited after the freeze.

### 6.6 What stays UNKNOWN until measured

| Unknown | Measured by | Phase |
|---|---|---|
| Effective raw retention; oldest line time against oldest `collected_at` per source | Census | P0 |
| Lines per device-day per source (percentiles), time-parse share, multi-line share | Census | P0 |
| Whether `cp_messages` lines carry a syslog priority; the `cp_fwk_elg` level token values; fixed-position level words in the other elg files | Census (vocabulary words and counts only) | P0 |
| `cp_mq_mng_elg` line form | Census | P0 |
| Template count per source **after masking** (the funnel counted the vocabulary form on unmasked in-memory text), and whether pseudonym placeholders split or merge templates | Census with a Drain dry run | P0 |
| Fleet prevalence distribution; one-device template share | Census | P0 |
| Device and line counts per marker of §3.2, including the EDAC and machine-check counts of G1 | Census | P0 |
| Masking throughput (lines per second per core) and the job duration per window | Census, then job telemetry | P0, P1 |
| Findings per device-day per layer; precision proxy | Backtest | P1, P2 |
| Shutdown-sequence templates on Gaia | Labelled planned reboots plus a vendor citation | P3 |
| Vendor meaning of any template used by a CRITICAL rule | Vendor documentation | Before each freeze |

## 7. Finding severity, budget, acknowledgement and coverage

### 7.1 Severity of a finding

| Severity | Raised by | Rule |
|---|---|---|
| CRITICAL | Layer A markers with S = 1.0 (`hw.edac.ue`, `kern.panic`, `fs.readonly`); PAN `critical` events whose template is classed `VENDOR_DOC` or `MEASURED`; layer C cards at their CRITICAL condition | Needs `MEASURED` or `VENDOR_DOC` provenance. **A statistical finding alone is never CRITICAL** |
| ATTENTION | Layer A markers with S = 0.8; non-chronic S ≥ 0.5 templates; `FLEET_RARE` with S ≥ 0.3; `RATE_SPIKE` with S ≥ 0.3; `SILENCE` of a template classed as periodic health output; `BOOT_BURST` with S ≥ 0.5; cards at their ATTENTION condition | — |
| INFO | `NEW_ON_DEVICE` for fleet-common templates; `UPGRADE_NOVELTY`; `EXPECTED_WINDOW`; chronic and fleet-background severe templates; any finding with S < 0.3 | Listed, not counted in the chip |
| UNKNOWN | A device-source-window that cannot be evaluated (§7.4) | Shown with its reason. Never read as health or as a fault |

### 7.2 Finding record and alert budget

A finding holds:

- id, layer, rule id and version, kind, card id;
- device, source and window (with time basis);
- severity and the rule that set it;
- score with S, R and C;
- counts, baseline `m` and `MAD`, `z`, prevalence `n_t / N`, first-seen;
- incident group, up to 40 evidence pointers, the four versions of §6.3, coverage state, expected-window label;
- review state (`OPEN`, `ACKNOWLEDGED`, `SUPPRESSED`, `CLOSED`) with who and when.

**Alert budget (PROVISIONAL, PO question 4):**

- median device-week: at most 1 ATTENTION-or-higher finding;
- p95 device-week: at most 5;
- fleet: at most 20 ATTENTION-or-higher a day;
- CRITICAL: at most 3 a fleet-week outside real incidents.

The budget is a **tuning target and a release gate**, not a runtime filter. If findings exceed it, the next catalog
or configuration version tightens thresholds. At runtime nothing is dropped to meet a number: overflow stays listed
and is counted ("+12 more ATTENTION").

### 7.3 Acknowledgement and suppression

- **Acknowledge** (`operation_admin`, `security_admin`): one finding, with a note. It clears the chip. The finding is
  raised again if the score doubles, the severity rises, or new evidence arrives more than 24 hours later
  (PROVISIONAL). Audited.
- **Suppress** (`security_admin`): a rule (template or card) × (one device, a list of devices, or the cohort), with a
  reason and an expiry of at most 90 days. Suppressed findings are still created and counted, carry the suppression
  id, and are listed under a "suppressed" filter. Layer A markers with S = 1.0 can be acknowledged but **cannot be
  suppressed** by rule. Audited.
- **Reclassify** (`security_admin`): a template's S or class in the catalog. This takes effect only through a new
  catalog version (§6.5).

### 7.4 Coverage is shown, never hidden

Each (device, source, window) carries one coverage state:

| State | When |
|---|---|
| `EVALUATED` | Collected, masked, matched and evaluated by all layers |
| `BASELINE_LEARNING` | 7 to 13 usable days (ratified rule): layer A, layer C, `NEW_ON_DEVICE` and `FLEET_RARE` only |
| `NOT_EVALUABLE` | Under 7 usable days (ratified rule). No finding of this pair becomes a model question (P4). Whether layer A and C hardware findings are shown to operators is PO question 1 |
| `GAP` | A `system_log_gap` row overlaps the window. Rate and silence findings are not evaluated for it |
| `NOT_COLLECTED` | The run outcome is not `COLLECTED` (including collection switched off) |
| `MASKING_UNAVAILABLE` | `LogMasking` refused (§4.1) |
| `UNPARSED_HIGH` | More than 5 % (PROVISIONAL) of the window's lines are unparsed or unmatched |
| `UNSUPPORTED` | The source is not analysed yet (`cp_mq_mng_elg` until P0 measures it) |

A device shows "no findings" only together with its coverage, for example "No findings; 4 of 4 windows evaluated" or
"No findings in 3 of 4 windows; 1 window UNKNOWN (gap)". A device with incomplete coverage is never shown green.

## 8. Placement and privacy

### 8.1 Where the job runs

| Option | Pros | Cons |
|---|---|---|
| **(a) A log-analysis job executed in ui2-service, enqueued when a collection window closes (recommended for P1 to P3)** | `LogMasking` and the estate dictionary are already there; no fence change; one masking authority | It competes with the API. Bounded concurrency, a per-window time budget and a backlog shown as UNKNOWN are mandatory; the duration is measured in P0 |
| (b) The analysis container of the 2026-11-05 direction, once it exists | Load isolation, which is the container principle's own ground | It does not exist yet. Masking must be re-established at the new boundary, and the fence must allow that module to depend on the sanitizer |
| (c) The worker, as the brief says | Next to collection | Needs `SanitizerModuleFenceTest` relaxed, which puts the masking key into the pod that holds device credentials. Not recommended |

Recommendation: (a) now, moved to (b) when the analysis container ships, on measured load. The worker's part is only
to enqueue the job on window close. PO question 2.

### 8.2 Masking before mining

The only text the miner and the matcher ever see is masked and normalised (§4.1). Concretely:

- The job decrypts a chunk through the artefact store into memory, as `SystemLogReadService` does.
- One `LogMasking` session masks the lines.
- The normaliser turns every pseudonym into a typed placeholder.
- Matching works on the normalised tokens.
- The raw bytes are discarded at the end of the chunk.

The offline catalog build runs on the same path in the same process and writes only the vocabulary-form templates
and the manifest. The masking rule set per chunk is stored so that a masking upgrade is visible in the catalog diff.

### 8.3 What is stored (provisional names; one migration at implementation time, with the BEGIN/ROLLBACK dry run and audit triggers like V160)

| Table | Content | Log text? |
|---|---|---|
| `log_template_catalog` | Version, manifest jsonb (§6.5), digest, state, frozen by and at | No |
| `log_template` | Catalog version, template id, source, vocabulary-form text, marker id, S, class, provenance, card links, fleet first seen | Vocabulary form only |
| `log_template_device_stat` | Device, template, segment (version, HA role), first seen, last seen, presence share, periodicity, `m`, `MAD`, buckets used | No |
| `log_event_count` | Device, source, template (catalog or provisional id), bucket start, time basis, count, first and last time, up to 3 pointers (chunk id, offset, length). Partitioned by month | No |
| `log_index_state` | Chunk id, catalog version, masking rule set, normaliser version, lines, matched, provisional, unparsed | No |
| `log_coverage` | Device, source, window, state (§7.4), reason, day count | No |
| `log_finding` | §7.2 | No |
| `log_finding_action` | Acknowledgement, suppression and reclassification events with actor, reason and expiry | Operator text, bounded and audited |

Pointers resolve to text only through the existing read path, which masks for aiview and for every role without
`system_log_read_unmasked`. File names stay HMACs (phase B §5.2). No table above stores a host name, address, serial
or user name in clear. The template text cannot hold one either: masking runs first, pseudonyms become placeholders,
and non-vocabulary words become `<W>`.

### 8.4 aiview

aiview sees findings, scores, templates in vocabulary form, prevalence as counts and evidence through the masked
projection, with device pseudonyms (`FW-TANGO-04`, `CLS-ROMEO-01`) and synthetic addresses (`192.0.2.x`) in every
preview and fixture. aiview never acknowledges, suppresses, reclassifies or freezes. Its view stays masked whatever
the PO decides on P4 real names (decision A4 of 2026-11-10 applies to authorised roles only).

### 8.5 Retention

| Data | Retention (PROVISIONAL) |
|---|---|
| Hourly or per-run `log_event_count` | 30 days, then daily roll-ups to 180 days (Argon finding 11, adopted 2026-11-10) |
| `log_finding`, `log_finding_action`, `log_coverage` | 180 days (same as the parsed index of phase A) |
| `log_template_device_stat` | Current plus the previous segment per device |
| Catalog versions and their templates | Kept; they are small and hold no line |
| Raw chunks | Unchanged: phase B retention. After expiry a finding keeps its numbers and shows "evidence expired" |

## 9. Screens and CLI

Every preview and fixture uses masked state only.

**Devices list — log chip.** One chip per device: the worst open finding and its count, or the coverage state when
there is nothing to show. The rows below are illustrative, not measured; only the first row follows the trigger case.

```
 Device        Vendor  Log
 FW-TANGO-04   CP      [CRITICAL 2]     memory: corrected errors on one slot; 2 unexpected reboots
 FW-JULIET-06  CP      [ATTENTION 1]    rare template spike (cpd)
 FW-BRAVO-02   PAN     [UNKNOWN]        learning, 4 of 7 days
 FW-SIERRA-06  CP      [-]              no findings, 4 of 4 windows evaluated
```

**Device detail — log findings timeline.** Day lanes with the four collection windows, boot anchors as markers, and
gaps and UNKNOWN windows shaded. The mock below uses the trigger case; the score uses the §4.5 assumption, and the
cluster pseudonym is illustrative. Each incident card shows:

- name (card name, or the kind for unnamed findings), severity with its rule, score with S, R and C;
- template in vocabulary form;
- counts against the baseline, "on 2 of 57 gateways", first seen;
- the peer member's state for the same window (count, or its UNKNOWN reason; never presented as corroboration);
- up to 40 masked evidence lines with links into the log viewer at chunk and offset;
- the expected-window label;
- actions (acknowledge, suppress) for the permitted roles.

A coverage strip shows the states of §7.4 per source.

```
 FW-TANGO-04  2026-11-24  (DEVICE_LOCAL)                 coverage: messages 4/4  cpd 4/4  fwk 4/4  mq_mng UNSUPPORTED
 00 ---------- 06 ---------- 12 -----B---- 18 --B------- 24
 [CRITICAL] card.memory.dimm  score 58 (S 0.8  R 0.73  C 1.0)
   EDAC MC<NUM>: <NUM> CE memory read error on <DIMM_LABEL> ...    45 lines / 4.5 h, one slot-label token, baseline 0
   [Hardware Error]: ... after boot B1, B2                         UE 0   OOM 0
   peer member (CLS-ROMEO-01): <CE count in the same window, or its UNKNOWN reason>
 [CRITICAL] card.reboot.unexpected  2 boots, no shutdown sequence before either, boundary coverage complete
```

**Fleet "rare events" view.** Templates seen on few devices in the last 7 days, ordered by their highest score.
Columns: template (vocabulary form), source, S class, devices (count, then masked names on expand), first seen in the
fleet, trend. A second tab lists fleet findings (the same new template on many devices in one window).

**Template catalog (admin).** Catalog versions with state and diff. Per template: source, vocabulary text, marker,
S, class, provenance, prevalence, lines per day, chronic share, linked cards, suppressions. "Show an example" opens
the masked log viewer at a pointer. Freeze, reclassify and suppress are `security_admin` actions; aiview reads.

**CLI parity** (phase B §14 rule):

- `log-findings [--device <id>] [--severity <s>]`, `log-finding <id>`;
- `log-finding-ack <id> --note`, `log-suppress ...`;
- `log-templates [--rare]`, `log-template <id>`;
- `log-catalog`, `log-catalog-diff <from> <to>`, `log-catalog-freeze <version>`;
- `log-coverage <device>`.

## 10. Phasing

Each phase is behind its own setting. Rollback means switching the setting off and leaving the stored rows. No phase
starts before the previous phase's exit check is recorded. Notifications stay off through P3 unless the PO decides
otherwise (PO question 7).

| Phase | Content | Real-environment check (counts and states only, aiview projection) |
|---|---|---|
| **P0 census** (a separate lane builds the census tool) | All of §6.6 marked P0. A read-only run in the pod on the stored pool, counts only, following the funnel record's method and its output filter | The census reproduces G1 from counts: on `FW-TANGO-04`'s device-day, 45 `hw.edac.ce` lines on one slot-label token within 4.5 hours; `hw.mce` after each of 2 `kern.boot` anchors; 0 `hw.edac.ue`; 0 `kern.oom`. Effective retention and template counts after masking are recorded |
| **P1 severity and hardware markers** | Masking-first indexer for the evaluated sources, layer A, dedup, chronic and fleet-background marking, coverage states, findings in **shadow** for 14 days (stored, shown only on the admin screen), device timeline, CLI | Replay of G1 gives an ATTENTION finding for the CE burst on the device-day. Shadow volume is counted against the budget. A database check shows no line text in any new table |
| **P2 catalog, rarity and rate** | Offline Drain build, catalog v1 frozen after review, online matching, prevalence, baselines, all layer B kinds, score, the hold-out backtest of §6.2, fleet rare-events view, chip | G1 ranks in the top 3 of its device-day (§4.5), with the "marker list off" rank recorded. G2 to G5 per §6.2. Precision proxy and budget against the targets. The PO reviews one week of findings |
| **P3 scenario cards** | The seven cards of §5 with provenance and golden cases | G1 shows `card.memory.dimm` CRITICAL and `card.reboot.unexpected` for both boots. G6 (planned reboots) is not flagged. A PO-reviewed week shows card volume within budget |
| **P4 LLM explainer** | The 2026-11-10 design §3 and §4.5 to §8.3 under the 2026-11-10 decisions: on demand only, egress level per PO decision, `ui2-llm-gateway`; no question from a `NOT_EVALUABLE` pair | Per 2026-11-10 §10 phase 2. The explainer never changes a finding |

## 11. Tests (outline)

- **Determinism:** the same chunks with the same four versions give the same template ids, evidence rows and findings.
  Re-indexing is idempotent.
- **Masking first:** a planted identity of every class in every source format never reaches `log_template`,
  `log_event_count` or any finding field. `MaskingUnavailableException` gives `MASKING_UNAVAILABLE`, never unmasked
  processing.
- **No text in tables:** a schema test lists the columns of the new tables and fails on any free-text column beyond
  vocabulary-form template text and bounded operator notes.
- **Evaluability (carried over):** a pair with fewer than 7 usable days gives `NOT_EVALUABLE` and no model question,
  a planted crash on such a pair included. The same pair one day past the threshold gives its signature finding.
- **Severity floor:** a statistical-only finding is never CRITICAL. A `PROVISIONAL` card never exceeds ATTENTION. An
  S = 1.0 marker cannot be suppressed.
- **Coverage:** a window with a gap gives no rate or silence finding and shows `GAP`. A device with incomplete
  coverage never renders as healthy.
- **Golden cases:** synthetic masked replicas of G1 (two boots, CE burst, machine checks), of the failover runs and of
  a planned reboot, with expected ranks and cards.
- **Score:** unit tests of R, of C with the MAD floor at MAD = 0, and of the product.
- **UI:** render harness with masked fixtures for the chip, the timeline, the rare-events view and the catalog.

## 12. Open PO questions (recommendation first)

1. **Show layer A and C hardware findings on a pair with under 7 days of history?** (The question the 2026-11-11 record
   left open.)
   - **Recommended: yes, as findings with the label "history N days", and never as a model question.**
     - Pros: hardware markers need no baseline, and a new device or a device after a long gap is exactly when a DIMM
       or disk report matters.
     - Cons: a young pair's first days may show markers that turn out to be chronic.
   - No: nothing is shown until 7 days.
     - Pros: one simple rule.
     - Cons: the trigger case would have been silent on a young pair.
2. **Where does the job run, given the sanitizer fence?**
   - **Recommended: ui2-service now, enqueued on window close, moving to the analysis container on measured load
     (§8.1).**
     - Pros: no fence change; one masking authority.
     - Cons: shares the service's CPU; needs bounds and telemetry.
   - The analysis container first.
     - Pros: isolation.
     - Cons: blocked until that container exists.
   - The worker.
     - Pros: next to collection.
     - Cons: relaxes the fence and puts the masking key next to device credentials.
3. **Accept the composite score, for ordering only?**
   - **Recommended: yes, with severity still from rules and statistics never CRITICAL.**
     - Pros: the PO's "rare + severe first" becomes one visible, explainable number.
     - Cons: one more concept for operators. Mitigated by showing S, R and C.
   - No score; order by severity, then time.
     - Pros: simpler.
     - Cons: the trigger case sits among same-severity background lines.
4. **Alert budget and precision targets.**
   - **Recommended: the PROVISIONAL values of §6.2 and §7.2, reviewed after the P2 backtest.**
     - Pros: they are measured before they bind.
     - Cons: none beyond a later revision.
   - Fix the numbers now.
     - Pros: a firm gate.
     - Cons: the numbers would rest on no measurement.
5. **Raw retention during learning.**
   - **Recommended: keep the current setting and start counting in P0. Raise retention temporarily (for example to 90
     days) only if the census shows the disk headroom for it.**
     - Pros: derived counts are small and permanent.
     - Cons: re-mining with a changed normaliser can only reach back as far as raw retention.
   - Raise to 90 days now.
     - Pros: more re-mining room.
     - Cons: Check Point growth per day is still `PENDING` in phase B §5.3, and the 2026-10-10 disk incident is the
       precedent.
6. **How is template text stored?**
   - **Recommended: vocabulary form only (§4.1).**
     - Pros: no residual identity can sit in a table.
     - Cons: some templates read as `<W> <W> failed`; the masked example line covers that.
   - Full masked form.
     - Pros: more readable.
     - Cons: any masking miss is stored permanently.
7. **Notifications.**
   - **Recommended: none through P3. After the P3 check, CRITICAL `card.memory.dimm`, `card.disk` and
     `card.reboot.unexpected` go to the existing notification forwarder.**
     - Pros: no alert fatigue during tuning.
     - Cons: the trigger case would only be on screen until then.
   - CRITICAL from P1.
     - Pros: earlier value.
     - Cons: unmeasured volume.
8. **Who labels the precision sample?**
   - **Recommended: the PO and an engineering agent, independently, through the aiview projection.**
     - Pros: two views and an agreement rate; no egress.
     - Cons: PO time, about 100 findings.
   - The agent alone.
     - Pros: cheap.
     - Cons: no human calibration.
   - An external model.
     - Not available: it would be an egress of real pool content, which the 2026-11-10 decisions do not approve.
9. **Are pool statistics outside the 2026-11-10 non-goal "no machine-learning model trained on customer logs"?**
   - **Recommended: yes. They are deterministic counts and medians with a frozen, reviewable catalog, computed and
     kept on HOST-A.**
     - Pros: this is what the PO asked for.
     - Cons: wording. If the PO reads the non-goal strictly, the non-goal is amended by a PO record.
   - No: layer B is dropped.
     - Cons: the engine falls back to scenarios, which the PO rejected.
10. **Prevalence cohort.**
    - **Recommended: vendor and source in P2; model family as an option in a later catalog version if the census shows
      hardware-family templates distort rarity.**
      - Pros: large cohorts and stable R.
      - Cons: a template that exists only on one hardware family looks rare.
    - Model family now.
      - Pros: fairer per platform.
      - Cons: small cohorts make R noisy.
11. **Severity of corrected memory errors.**
    - **Recommended: ATTENTION, raised to CRITICAL on UE, reboot or the trigger combination (§5).**
      - Pros: corrected errors alone are often survivable.
      - Cons: one more rule to explain.
    - CRITICAL on any CE burst.
      - Pros: simple.
      - Cons: chronic single-bit errors would page.

## 13. Sources

Repository sources are named inline. The literature below is cited from the author's reading and was **not re-read
in this lane** (no outbound connection was allowed). Before a freeze, the references that a CRITICAL rule depends on
are re-read and cited by section.

- Pinjia He, Jieming Zhu, Zibin Zheng, Michael R. Lyu. "Drain: An Online Log Parsing Approach with Fixed Depth Tree."
  IEEE International Conference on Web Services (ICWS), 2017.
- Jieming Zhu, Shilin He, Jinyang Liu, Pinjia He, Qi Xie, Zibin Zheng, Michael R. Lyu. "Tools and Benchmarks for
  Automated Log Parsing." ICSE Software Engineering in Practice, 2019 (the LogPAI / Loghub benchmark).
- Peter J. Rousseeuw, Christophe Croux. "Alternatives to the Median Absolute Deviation." Journal of the American
  Statistical Association 88 (424), 1993.
- Christophe Leys, Christophe Ley, Olivier Klein, Philippe Bernard, Laurent Licata. "Detecting outliers: Do not use
  standard deviation around the mean, use absolute deviation around the median." Journal of Experimental Social
  Psychology 49 (4), 2013.
- Karen Spärck Jones. "A statistical interpretation of term specificity and its application in retrieval." Journal of
  Documentation 28 (1), 1972 (the inverse-document-frequency form used for R).
- Linux kernel documentation, RAS and EDAC (`Documentation/admin-guide/ras.rst`) and the x86 machine-check code
  (the hardware-error message prefix): the marker forms of §3.2. **To be re-read and cited before P1 freezes the
  marker list.** For Gaia's kernel, Check Point documentation is the higher source where it exists.
