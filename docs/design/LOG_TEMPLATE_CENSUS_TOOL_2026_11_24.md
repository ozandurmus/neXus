# System-log template census tool (P0 of the detection engine)

Status: tool description, 2026-11-24 (PO request, lane `p1-log-template-census-tool`). Not a contract and not a freeze:
it introduces no device command, no schema and no write. It measures; the detection engine design waits for its report.

## Purpose

The detection engine will be built on the stored system logs of about 100 devices (months of `system_log_chunk`).
Before any detector, the pool is measured: which message templates exist per source, how common each is across the
fleet, and which are rare and severe. The census is a one-off, read-only program; the PO session runs it on HOST-A.

## Where the code lives

| Piece | Location |
|---|---|
| Entry point (arguments, output, exit code) | `ui2/worker/.../worker/diagnostic/SystemLogTemplateCensusMain` |
| Census (aggregation, report, output guard) | `.../worker/diagnostic/SystemLogTemplateCensus` |
| Drain-style miner | `.../worker/diagnostic/TemplateMiner` |
| Header parse, severity, markers, normalisation | `.../worker/diagnostic/CensusLineShapes` |
| Privacy guard | `.../worker/diagnostic/CensusMaskGuard` |
| Seam (JDK-only types) | `ui2/platform-core/.../platform/SystemLogCensusFeed` |
| Decrypt + aiview masking + pseudonyms | `ui2/service/.../service/systemlog/SystemLogCensusFeedProvider` |
| Run script | `scripts/measurement/log_template_census/run_in_pod.sh` |

Why two modules: the masking (`LogMasking`), the privacy key and the device pseudonyms exist only in the service module,
and `Ui2ArchitectureTest` dir2 forbids the service and the worker from depending on each other. The census therefore lives
in the worker module (as requested) and reaches the decrypting, masking feed through `SystemLogCensusFeed`, a JDK-only
interface in `platform-core`; the entry point loads `SystemLogCensusFeedProvider` by class name, as the launcher loads a
role's main class. Both are packed into the one boot jar (`/app/service.jar`).

## Running it in the pod

The pod must mount the database credentials, the artefact-store key and the privacy key: the ui2-service pod does (the
worker pod mounts no privacy key). The jar must contain this build; for a branch build copy it into the pod and set
`CENSUS_JAR`. Then:

```
sh run_in_pod.sh census1 --from 2026-09-01 --to 2026-11-23 --sources cp_messages,cp_cpd_elg,cp_fwd_elg,cp_fwk_elg,pan_system
```

which is the same as (PropertiesLauncher, `loader.main`, the pattern of the 2026-11-10 measurements):

```
java -Xmx3g -Djava.io.tmpdir=/app/tmp -Duser.home=/app/home -cp /app/service.jar \
  -Dloader.main=com.securityexpert.nexus.ui2.worker.diagnostic.SystemLogTemplateCensusMain \
  org.springframework.boot.loader.launch.PropertiesLauncher --from ... --to ... --sources ... --out /app/tmp/census1.json
```

Exit codes: 0 report written; 2 bad arguments; 3 run failed (including the output guard; then nothing is written).
Standard error carries progress counts and one-word failure labels only.

## Arguments

| Argument | Meaning | Default |
|---|---|---|
| `--from`, `--to` | inclusive window of `system_log_chunk.log_day` (`yyyy-MM-dd`); chunks without a log day are not read | required |
| `--sources` | comma-separated source keys, e.g. `cp_messages,cp_cpd_elg,pan_system` (families with a measured header: `cp_messages`, `cp_fwd_elg`, `cp_cpd_elg`, `cp_cpwd_elg`, `cp_mq_mng_elg`, `cp_fwk_elg`, `pan_system`) | required |
| `--max-lines` | stop after this many lines | 200000000 |
| `--depth`, `--similarity`, `--max-children` | Drain parameters | 4, 0.5, 100 |
| `--max-templates` | memory cap per source on the template table; shapes beyond it count as `overflow_lines` | 20000 |
| `--max-chunk-bytes` | a chunk is read up to this size, cut at a line boundary (`chunks_truncated`) | 16 MiB |
| `--out` | report file, directly under `/app/tmp` (written via `.part` and renamed); without it the report goes to stdout | stdout |

## What it does

1. Selects live (`pruned_at is null`) chunks of the window and sources, ordered by (source, device, day), on a read-only
   database session (`SET SESSION CHARACTERISTICS AS TRANSACTION READ ONLY`, rolled back at the end). It writes no table.
2. Per chunk: decrypts through the artefact store (`ArtefactStore.retrieve`, the call `SystemLogReadService` makes; no new
   crypto), masks every line with the current `LogMasking.RULE_SET`, then drops and counts any line in which an identity
   literal of the raw line (IPv4 outside the ranges the permutation keeps, IPv6, MAC, e-mail, host name under a generic or
   local suffix) survived unchanged. Raw text exists only inside `SystemLogCensusFeedProvider.Feed.read`.
3. Parses the masked line's header per source (continuation lines without a header are `unparsed_lines` and not mined),
   reads severity from the family's level field only where it exists, and normalises the message: numbers, hex, ids,
   UUIDs, addresses, e-mail, host names and masked tokens (`h-...`, `USR-...`, `FW-...-nn`, ...) become `<*>`.
4. Mines templates per source with `TemplateMiner` (He et al. Drain: length layer, `depth-2` token layers, similarity
   threshold, children cap; tokens with a digit route as wildcards).
5. Marks severity and the closed marker set per line; a template carries the highest severity seen and the first marker.

### Severity classes

`critical`, `error`, `warning`, `info`, `unknown` (no level field on the line). Gaia `cp_messages`: a leading `<PRI>`
(priority mod 8) or a `facility.level` token after the host; elg sources and `cp_fwk_elg`: the bracketed level of the
header or a leading level word of the text (`ERROR`, `[Warning]`, ...); `pan_system`: `severity=` (critical -> critical,
high -> error, medium -> warning, low/informational -> info). Message words never set a severity.

### Markers (closed set)

`edac_ce`, `edac_ue`, `mce_hardware_error`, `kernel_panic`, `io_error`, `readonly_remount`, `oom_killer`, `boot`
(patterns in `CensusLineShapes.marker`; first match wins in that order).

## Output (one JSON object)

- `tool`, `report_version`, `mask_rule_set`, `window`, `params`, `totals` (chunks read/failed, failure kinds as lowercase
  exception class names, lines, devices, `stopped_by_max_lines`).
- `families.<source>`: `chunks`, `chunks_failed`, `chunks_truncated`, `devices`, `lines`, `unparsed_lines`,
  `guard_rejected_lines`, `overflow_lines`, `template_count`, and `templates[]`, most frequent first:
  `template_id` (first 12 hex digits of SHA-256 of the full template text), `template` (masked, at most 200 characters),
  `count`, `device_count` (fleet prevalence), `first_day`, `last_day`, `severity`, `marker`, `max_device_day_count`
  (largest count of one device on one day; exact when the feed delivers chunks grouped by (source, device, day), which
  the SQL ordering does).
- `rare_and_severe[]` (top 100): templates with `device_count <= 3` and severity `error`/`critical` or any marker, ordered
  by severity, then marker present, then count. Each entry: `family`, `template_id`, `template`, `count`, `device_count`,
  `severity`, `marker`, `devices[]` with the aiview `device` pseudonym, its `count` and per-day `days[]` counts.

## Privacy

- Masked names only: device pseudonyms come from `TopologyNamePseudonymizer` (the aiview pseudonyms, `FW-...`); a value that
  does not look like a pseudonym is replaced by `DEVICE-<n>`. Internal device ids, host names and addresses are never output.
- Output guard: the finished JSON is scanned for IPv4, IPv6, MAC, e-mail and host-name shaped text; a hit fails the run
  (exit 3, class labels only, nothing written). Lines flagged during reading are counted (`guard_rejected_lines`), never printed.
- The masking permutes addresses (a masked line still holds address-shaped text), so the line-level check compares raw
  and masked forms: a surviving identity literal is a leak; a permuted one is not. The ranges the permutation keeps on
  purpose (0/8, 127/8, 224/4, 240/4, `::`, `::1`, `ff00::/8`) are not flagged. A fixed point of the permutation would be
  dropped as well (fail closed).
- Library logging is off and `System.err` carries counts only; no exception message is printed.
- A template with a rare device set carries that device's per-day counts; nothing else about a device leaves the process.

## Known limits

- Level detection is limited to the fields above; `cp_messages` lines without `<PRI>` or `facility.level` have severity
  `unknown`, so a hardware fault there is found through its marker, not its severity.
- Unparsed continuation lines are counted, not mined.
- The FQDN pattern uses generic and local suffixes only (not country codes), to avoid flagging file names such as `x.so`.
- Day boundaries are `log_day` as stored (device-local basis, DEVICE_LOCAL).
- A source without a measured header (`cp_spike_*`, `pan_config`) is not in the supported list; if forced, the whole line is
  the message and no level is read.

## Tests

`cd ui2 && ./gradlew :worker:test --tests '*Census*' --tests '*TemplateMiner*'` (miner, shapes, guard, census over masked
synthetic fixtures including the EDAC/MCE shape of `tools/llm_trial/corpus.py` case C22, entry point) and
`./gradlew :service:test --tests '*SystemLogCensusFeed*'` (real encrypted artefact store, masking, leak drop).
