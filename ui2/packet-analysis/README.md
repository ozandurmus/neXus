# packet-analysis

Java port of the frozen PacketBreaker engine (`docs/design/PACKET_ANALYSIS_PORT_CONTRACT.md`). Plain Java 21, no
Spring, no project dependencies; the only runtime library is `org.duckdb:duckdb_jdbc` 1.5.6.0 (the same DuckDB
engine version as the reference).

## Numeric layer (`com.securityexpert.nexus.ui2.packetanalysis.numeric`)

Exact CPython 3.12 semantics for every numeric and number-formatting construct the Python engine uses, so later port
stages call these helpers instead of re-deriving Python behaviour. `NUMERIC_INVENTORY.md` lists each construct with
its call sites and the helper that ports it.

| Class | Python |
|---|---|
| `PyNum`, `PyInt`, `PyFloat`, `PyArith` | int/float values, operators and exact mixed comparisons |
| `PyStatistics` | `statistics.median`, `statistics.quantiles` (inclusive/exclusive) |
| `PyTimSort` | `sorted()` / `list.sort()` (CPython listsort, NaN-exact) |
| `PyBuiltins` | `sum` (3.12 compensated), `min`, `max` |
| `PyRound` | `round(x)`, `round(x, n)`, `round(int, n)` |
| `PyFormat` | f-string / `format()` specs, locale-free |
| `PyFloatRepr` | `repr(float)`, `str(float)`, `json.dumps` float text |
| `PyMath` | `//`, `%`, `divmod`, true division, `math.floor/ceil`, `int(float)` |
| `PyConvert` | `float(str)`, `int(str, base)` |
| `PyTime` | `datetime.fromtimestamp` microsecond rounding |

Error mapping: `ValueError`/`StatisticsError` -> `IllegalArgumentException`; `OverflowError`,
`ZeroDivisionError` and an int result wider than 64 bits -> `ArithmeticException`.

### Differential vectors

`src/test/resources/packetanalysis/numeric/reference_vectors.json.gz` holds about 119,000 cases computed by CPython
(plus a shared pool of 16,207 input floats): random IEEE bit patterns, all powers of two and ten, subnormals, signed
zeros, NaN and infinities, `.5` ties, timestamps and millisecond magnitudes, short/odd/even/duplicate/NaN lists for
`median`/`quantiles`/`sorted` (up to 3,000 elements with runs so the merge and gallop paths run), mixed int/float
lists, and malformed number strings. `CPythonDifferentialTest` requires exact equality: strings character for
character, floats by `Double.doubleToRawLongBits`, ints by value, Python exceptions by mapped Java class. The only
relaxation is NaN: only NaN-ness is compared, because the sign and payload of a NaN produced by arithmetic is
CPU-specific (x86 sets the sign bit, arm64 does not) and never visible in `repr`/`json` text.
`NumericTrapsTest` shows the individual traps as readable examples.

Regenerate the vectors with the frozen PacketBreaker interpreter (CPython 3.12; the script refuses any other
version). Output is deterministic: the same interpreter writes a byte-identical file.

```sh
~/Codo/PacketBreaker/.venv/bin/python -I tools/packet_analysis/numeric/gen_cases.py
cd ui2 && ./gradlew :packet-analysis:test -q
```

To extend coverage, add cases to the relevant `section_*` function in `gen_cases.py`, keep the compressed file under
2 MB, regenerate, and commit the script and the `.json.gz` together.

Regenerate the inventory tables (everything below `## Inventory` in `NUMERIC_INVENTORY.md`):

```sh
~/Codo/PacketBreaker/.venv/bin/python -I tools/packet_analysis/numeric/scan_inventory.py \
    ~/Codo/PacketBreaker/src/packetbreaker
```

Nothing in this module runs Python: the vectors are static test data. Architecture rule DIR-10
(`Ui2ArchitectureTest`) scans `ui2/` sources for the lowercase interpreter name, so Java and Gradle files here
spell it "CPython"/"reference" instead.

## Ingest (`...packetanalysis.ingest`) and store (`...packetanalysis.store`)

Port of the reference ingest (`ingest`, `batch_ingest`, `metadata`, `timestamps`, the `store` schema). Gate: L1
parity, the `packets`, `excluded_frames`, `captures` and `settings` rows identical to a reference ingest of the same
files.

| Class | Reference |
|---|---|
| `CaptureMetadata` | container walker: pcap (LE/BE, us/ns), pcapng (sections, IDB names, ISB drop counters), snoop; zero tails, damaged final records |
| `TsharkFields`, `TsharkRunner` | the exact tshark argument list and 77 core (+ vendor) fields; subprocess with closed stdin, bounded stderr, cancellation, optional timeout |
| `PyCsv` | the reference csv reader (tshark stdout, universal newlines) and writer (batch file) |
| `PacketParser`, `VendorFields`, `TimestampRules` | one field record to one `packets` row; vendor JSON; timestamp exclusion reasons |
| `CaptureIngest` | per-file ingest: identity/caching, checkpoints per 50,000-row batch, `read_csv` load, summary and warnings, pre-filter retry |
| `BatchIngest` | several files: worker budget, optional parallelism, the >5x pre-filter (scan, IP-pair filter, skip rule), folder listing |
| `ProjectStore`, `Db`, `MemoryBudget`, `MemoryPlan`, `PacketColumns`, `Sql` | project directory, schema + migrations 1..6, DuckDB settings (memory 25% of the given total, 1-8 GiB, lowered to the memory plan's ceiling; explicit threads; spill to `.spill`; external access only for the batch load, no extension autoload) |
| `IngestCli` | `main` for parity runs: ingest files/folders into a new `project.duckdb` |

All SQL text is in `src/main/resources/packetanalysis/{store,ingest}/*.sql`, copied verbatim. The batch load writes
the same CSV bytes as the reference and runs the same `read_csv` statement, so DuckDB performs the same type
conversion (empty string to NULL, `True`/`False`, float repr). JSON text (`PyJson`, numeric package) follows the
reference `json.dumps` byte for byte.

Tests: `MetadataVectorsTest` (1,760 reference vectors: every truncation, zero tails, bit flips),
`PacketParserVectorsTest` (row CSV text identical to the reference), `PyCsvTest`, `PyJsonTest`, `ProjectStoreTest`
(DuckDB, no tshark) and `TsharkIngestTest` (skipped when tshark is missing). Vectors are regenerated with the frozen
interpreter: `tools/packet_analysis/parity/l1_metadata_vectors.py`, `l1_parser_vectors.py`.

L1 parity against the reference on synthetic captures (needs the frozen PacketBreaker checkout and tshark):

```sh
~/Codo/PacketBreaker/.venv/bin/python -I tools/packet_analysis/parity/l1_compare.py \
    --captures <scratch>/captures --generate --derived            # add --prefilter for the case default
```

Parity runs pin DuckDB to one thread on both sides (`--threads`, default 1; Java system property
`packetanalysis.duckdb.threads`), because multi-threaded tie order is not deterministic in the reference. Ingest SQL
whose output order is not fixed: the three `GROUP BY` queries feeding the inventory (`excluded_reasons`,
`vendor_stages`, `tunnel_counts`, compared order-insensitively), `ip_pairs` (`DISTINCT`, used as a set and sorted),
and the `packets` row order itself (parallel `read_csv`, `preserve_insertion_order=false`; always read by key).

Not ported yet: resume after a cancelled/failed ingest is implemented as in the reference (checkpoint skip) but not
parity-tested; the `profile` timing metrics; the Fortinet text import that produces `source_metadata`; F5 trailer
and snoop/Check Point captures have the code path but no synthetic corpus.

## Topology and case setup (`com.securityexpert.nexus.ui2.packetanalysis.topology`)

Port of the reference `topology/` package (models, suggest, order, groups, assemble) and `case_setup.py` minus
ingest. Input is an ingested `project.duckdb`; output is the reference JSON, byte for byte.

| Class | Reference |
|---|---|
| `Topology`, `Point`, `NatMapping`, `ClockOverride` | `models.py` (validation, stage-order normalization, `model_dump` key order and int/float typing) |
| `Suggest` | `suggest.py` (stage pairs, shared clocks, host captures, client CIDRs) |
| `Order` | `order.py` (TTL / microsecond unit order, separate-clock isolation, name mismatch warnings) |
| `Groups` | `groups.py` (default points, exclusion reasons, multi-path client groups) |
| `Assemble` | `assemble.py` (`assemble_topology`, `assemble_and_analyze` with an `Analyzer` hook, `confirm_suggested_topology`) |
| `CaseSetup` | `case_setup.py` (`capture_paths`, `inventory_overview`, `create_case` after ingest) |
| `ProjectDb` | `store.Project.connect/get/set/inventory`, `rows()` |
| `PyJson`, `Py`, `PyTuple` | `json.dumps`/`loads` text (ensure_ascii, indent), `repr`/`str`, truthiness, `==` |

SQL is copied verbatim from the reference into `src/main/resources/packetanalysis/topology/sql/` (f-string fields
such as `{placeholders}` are filled the same way) and runs on `org.duckdb:duckdb_jdbc` 1.5.6.0, the reference's
engine version. `TopologyMain` (`suggest|assemble|case|confirm|overview --project DIR`) is the parity entry point.

Parity: `tools/packet_analysis/parity/topology_compare.py` (run with the reference interpreter) builds the demo
scenario folders and the multi-path / ambiguity folders of the reference tests, runs the reference and this port
on the same ingest snapshot and requires identical JSON text and identical stored settings rows. Both sides run
DuckDB with one thread there (`-Dpacketanalysis.duckdb.threads=1`): with several threads the reference itself is
not deterministic, because `ORDER BY spread_us LIMIT 200` and `arg_min(frame, abs(delta))` in the suggestion SQL
have ties that parallel aggregation breaks differently from run to run (suggestion order, stage-pair choice and
evidence frames change between two reference runs on the same database).

## Parity corpus (phase 0)

Java port of PacketBreaker ("Packet Analysis"), per
`docs/design/PO_DECISION_RECORD_2026_10_15_PACKETBREAKER_PORTED_INTO_NEXUS.md` (RATIFIED) and
`docs/design/PACKET_ANALYSIS_PORT_CONTRACT.md` (phase 0 authority only).

Phase 0 state: an empty plain-Java module (no engine code yet) with `org.duckdb:duckdb_jdbc` pinned to the
DuckDB version of the frozen Python reference, and the parity goldens in `src/test/resources/parity/`.
The synthetic capture generators stay Python-only; their output is frozen as a corpus archive.

## Parity levels

| Level | What | How it is hashed |
|---|---|---|
| inputs | every generated capture file | SHA-256 of the file bytes |
| L1 ingest | `packets` table per capture file, `capture_id` replaced by the file name | `canonical.L1_SQL`: SHA-256 over `to_json(row)` lines ordered by `frame` |
| L2 intermediate | `obs`, `observation_matches`, `events`, `flow_summary`, `clock_values` (TEMP, snapshotted in-process) | dumped to Parquet with capture ids replaced by `<capture:NAME>`; `canonical.l2_hash_sql`: SHA-256 over the sorted `to_json(row)` lines |
| L3 | PacketBreaker golden payload, report, FindingsExport v11 | PacketBreaker's own golden canonicalization (ported in `canonical.py`): volatile keys dropped, capture ids -> `<capture:NAME>`, other 32-hex ids -> `<id>`, lists sorted, JSON-string fields parsed, floats to 9 significant digits, local times masked; SHA-256 of the whole and of each top-level section |

L1/L2 hashes are computed by SQL inside DuckDB, so the Java side runs the identical statements through
`duckdb_jdbc` of the same version (`DuckDbReferenceVersionTest` guards the version). Note that the L3 float
rule keeps 9 significant digits: epoch-second timestamps keep only 10-second resolution there; L2 keeps full
precision.

## Golden set

- Tier 1: the PacketBreaker author's goldens `demo`, `nat`, `offload`, `integrity_all`, `dns_diagnosis`,
  `fifty_flow_replica`, `folder_b_replica`, `oct8_replica`, built exactly as the frozen
  `tests/test_golden.py` builds them. The ported canonicalization reproduces PacketBreaker's serializer and
  its committed `tests/golden/*.json.gz` byte for byte (`pb_serializer_identical`,
  `pb_committed_golden_identical` in each case file).
- Tier 2: the 37 `packetbreaker demo --scenario X` scenarios at CLI defaults, plus one-axis variants
  (`--ip-id zero|constant|random`, `--ipv6`, `--rounds 40`) kept only when the generated inputs differ from
  the default and from every variant already kept, plus `--confirm-demo-nat` for `demo` and `nat`.

Committed: `manifest.json` (versions, archive SHA-256, determinism result, case index),
`cases/<case>.json` (input, L1, L2 and L3 hashes) and `cases/<case>.key-sections.json.gz` (the canonical key
sections `verdict`, `findings`, `quality`, `segments`, `finding_count`, `flow_count`; gzip, mtime 0). In the
committed key sections only, IPv4 literals the repository privacy gate classifies as private are replaced by
`<ipv4:SHA256_12>`; every hash is taken over the unmasked canonical form. The gate does not read `.gz`
files, so `tools/tests/test_packet_analysis_parity.py` applies the gate's own IPv4, e-mail and user-path
rules to the decompressed key sections. Everything else (captures, topology, ground truth, Parquet dumps, full
canonical JSON) is in the corpus archive named in `manifest.json`, kept outside the repository.

## Regenerate the goldens

Needs the frozen PacketBreaker checkout with its virtualenv and tshark (Wireshark). The frozen commit is
exported with `git archive` into the work directory; the PacketBreaker repository is not modified.

```sh
~/Codo/PacketBreaker/.venv/bin/python tools/packet_analysis/parity/reference.py \
    --work /path/to/scratch/pbpar \
    --goldens ui2/packet-analysis/src/test/resources/parity --jobs 6
```

It runs every case twice into `run1/` and `run2/`, fails when any recorded hash differs between the runs,
writes `corpus/packet-analysis-parity-corpus.tar.gz` (deterministic tar, gzip mtime 0) and rewrites the
goldens. `--only CASE...` runs a subset without writing goldens.

## Compare a candidate

```sh
# A Java case result with the golden case shape (any of inputs/l1/l2/l3):
python3 tools/packet_analysis/parity/compare.py \
    --reference ui2/packet-analysis/src/test/resources/parity/cases/demo.json --candidate-case java-demo.json

# A raw Java report plus its capture id -> file name map (canonicalized by canonical.py):
python3 tools/packet_analysis/parity/compare.py \
    --reference ui2/packet-analysis/src/test/resources/parity/cases/demo.json \
    --candidate-report report.json --capture-map capture-map.json
```

Section hashes are compared first; for mismatching sections whose reference content is available (committed
key sections, or a full canonical report from the archive as `--reference`) the first `--limit` differing
JSON paths are printed. Exit status 0 only on a full match.

Tests: `python3 -m pytest -q tools/tests/test_packet_analysis_parity.py` and
`cd ui2 && ./gradlew :packet-analysis:test`.

## Core engine (`com.securityexpert.nexus.ui2.packetanalysis.engine`, `.report`)

Port of the reference core: observation selection, NAT suggestions and confirmed mappings, tuple
canonicalization, sequence translation, occurrence matching, clock alignment, TCP session linking, byte-range
matching, classification, segments, flow summary/quality, headlines and the report assembly.

- `StageGraph.core()` is the explicit stage graph that replaces the implicit call order of the reference
  `report.analyze`: each stage names the reference function, the DuckDB tables/views it reads and the ones it
  creates; construction fails if a stage reads a table no earlier stage creates. Stages run in reference order.
- SQL is copied verbatim into `src/main/resources/packetanalysis/engine/sql/<module>.sql` (`-- name:` blocks;
  f-string fields keep their `{name}` and are substituted as the reference does, bound `?` stay bound). The only
  edits are the tie-breaks listed in `DETERMINISM.md`.
- Ported beyond the analysis package because the core reads their output: `fragments.normalize_fragments` /
  `guard_missing`, `integrity.payloads.link_modified_payloads` (packet-key relinking), `delivery.prepare_delivery`
  (filters `observation_matches`), `vendors.point_selector`, `capture_layers.tunnel_report`.
- Ported by the detector lanes: capture-layer decoding (`apply_layers`) and application indexing
  (`application_index`) by lane B, UDP/SIP decoding (`udp_index`) and F5 connections by lane C, device-stage
  proofs (`device_drops`) by lane A.
- Typed report: `CoreSections` records (+ `Maybe` for keys the reference adds only on some paths),
  `ReportModel` binds strictly and writes the reference `json.dumps(indent=2)` text via Jackson + `PyJsonWriter`.

Every reference stage is ported (lanes A, B and C below); the report's `unknown_sections` stays empty. The
verdict and `lead_degradation` follow the reference with every detector's findings.

Parity (L2 tables + L3 core sections of the final report; every lane merged, so the reference runs unmodified)
against the frozen reference, DuckDB threads=1 on both sides:

```sh
~/Codo/PacketBreaker/.venv/bin/python -I tools/packet_analysis/parity/core_compare.py WORKDIR [-v] [--extra]
```

JUnit fixtures (`src/test/resources/packetanalysis/engine/*.json.gz`) are frozen reference inputs written by
`tools/packet_analysis/parity/export_fixture.py` (base tables after reference ingest + topology, capture paths
reduced to file names, plus the reference core-only sections).

## Time and event detectors (lane A)

Port of `timeseries`, `onset` (+ `symptoms.label_retransmissions`, `proxy_analysis.restrict_onset_domains`),
`microbursts`, `security` (lifecycle and heuristics), `host_path`, `cycles`, `delivery.delivery_report`,
`hold_release`, `aggregates`, `incident`, `pilot.flow_columns`, `session_events` and `device_drops` (one class per
detector: `Timeseries`, `Onsets`, `Microbursts`, `Security`, `HostPath`, `Cycles`, `DeliveryReport`,
`Aggregates`, `Incident`, `SessionEvents`, `DeviceDrops`; shared
reference expression semantics in `PyOps`, lane state and topology settings in `TimeEvents`). SQL is verbatim in
`sql/<module>.sql` with the tie-breaks of `DETERMINISM.md`. Report sections `timeseries`, `onsets`,
`microbursts`, `sessions`, `session_events`, `security`, `host_path`, `cycles`, `incident`, `delivery`,
`vendor_device_events`, `vendor_device_event_count`, `confirmed_device_drops`, `confirmed_device_drop_count` are
produced; segments and findings carry the detector keys (`segments` and `findings` are untyped maps in
`CoreSections.Report` now, because detectors add keys whose order depends on the path).

Inputs from other lanes: `host_path` local service time reads `proxy_requests` (lane B). The retransmission
pages (`EngineCli --retransmissions`) come from lane B's `Transport`.

Parity (all intermediate tables of core + lane, every core and lane report section against the unmodified
reference report, DuckDB threads=1):

```sh
~/Codo/PacketBreaker/.venv/bin/python -I tools/packet_analysis/parity/det_a_compare.py WORKDIR [-v] [--only NAME ...]
~/Codo/PacketBreaker/.venv/bin/python -I tools/packet_analysis/parity/det_a_export_fixture.py WORKDIR NAME  # JUnit fixture
```

JUnit: `TimeEventDetectorsTest` (reference unit cases, negative first; full-section parity on `det_a_*` fixtures;
threads=1 vs threads=4).

## Path detectors (lane B: integrity, proxies, TLS, application index, capture layers, transport)

| Class | Reference |
|---|---|
| `Integrity` | `integrity/` (`analyze_integrity`: RST/ICMP origins, endpoint origins, payload changes, downstream-only packets, MTU/MSS, TTL/DSCP/option changes, duplication, asymmetric routing; `group_downstream_findings`) |
| `FragmentFindings` | `fragments.fragment_findings` (report `fragment_points`, `fragmentation` findings) |
| `CaptureLayers` | `capture_layers` (tshark JSON layer decode, `apply_layers`, `overlay_layers`, `fragment_metadata`) |
| `ApplicationIndex` | `application_index` (isolated two-pass tshark decode, `event_rows`, certificates, cache identity check, `apply_tcp_analysis`; report `application_status`) |
| `Proxies` | `proxy_analysis`, `proxy_matching`, `proxy_waterfall`, `proxy_resets`, `application_evidence` (report `proxies`) |
| `Tls` | `tls_analysis.tls_checks` (report `tls`, `tls_interception` findings) |
| `Transport` | `transport` (TCP limitation profiles and export, outside `analyze`), with `retransmissions` and `waterfall.service_times` |

The stages are registered in `StageGraph.core()`; outputs live in `EngineContext.path` (`PathState`). Parity (reference
interpreter, DuckDB threads=1 both sides; synthetic scenarios plus the reference test fixture builders for
proxies, fragments and tunnel layers):

```sh
~/Codo/PacketBreaker/.venv/bin/python -I tools/packet_analysis/parity/det_b_compare.py WORKDIR [-v] [--only NAME ...]
~/Codo/PacketBreaker/.venv/bin/python -I tools/packet_analysis/parity/export_detb_fixture.py WORKDIR NAME   # JUnit fixture
```

It compares the lane B intermediate tables, each lane B stage's direct return value (recorded by the Java stage in
`parity.detectors`) and the report sections; accepted differences are the tie-breaks of `DETERMINISM.md`, each
shown as "order only" or with its content difference. `restrict_onset_domains` belongs to the onset stage
(lane A); `f5_report` to the vendor lane.

## Export and compare (`com.securityexpert.nexus.ui2.packetanalysis.export`, step S15)

| Class | Reference |
|---|---|
| `FieldDiff` | `field_diff.py` (`values`, `fieldDiffs`, `packetDiff`; SQL in `engine/sql/field_diff.sql`) |
| `Export` | `export.py` (`Finding`, `FindingsExport` v11, `exportData`, `htmlReport`, `exportReport`; SQL in `engine/sql/export.sql`; offline gzipped JS assets in `packetanalysis/export/*.js.gz`) |
| `EngineExport` | `engine_export.py` (`columnName`, `exportTables` for `csv` ZIP of 8 CSVs + `README.json` and `xlsx` OOXML workbook with inline strings; SQL in `engine/sql/engine_export.sql`) |
| `ReferenceCompare` | `reference.py` (`ReferenceItem`, `Reference`, `parsePath`, `resolve`, `compareReference`, `referenceTemplate`, `unwrap`, `compareRuns`) |
| `ExportCli`, `CompareCli` | CLI entry points for `export` (`json`/`html`/`csv`/`xlsx` + `--reference-template`) and `compare` (`--reference` template or `--baseline` run) |

Parity against the frozen reference (JSON/CSV byte-identical after canonicalization, HTML identical except volatile timestamps and documented tie-breaks, XLSX compared cell by cell, `compare` byte-identical):

```sh
~/Codo/PacketBreaker/.venv/bin/python -I tools/packet_analysis/parity/export_compare.py WORKDIR [-v] [--only NAME ...]
```

JUnit: `ReferenceCompareTest` and `ExportAndFieldDiffTest` (negative cases first, plus `sameInputSameExportAcrossThreadCounts` across `threads=1` and `threads=4`).

## Expert report (`...packetanalysis.export.ExpertReport`)

An Excel workbook for one analysed case, built only from what the engine stored (`settings.report`, the topology and the
`obs`, `observation_matches`, `cycle_*`, `adjacent_match_rates`, `events`, `flow_summary` tables). Turkish text, the
reference report's palette and fonts, native Excel charts, values as constants (no formulas). It states numbers and
never a verdict: no "clean / delay here" marker and no threshold of its own; a method decision on that is pending.

| Sheet | Content (engine fields) |
|---|---|
| Özet | heading `<case title>: N kayıt noktası, <capture date>` (the case title as the caller projected it: the masked pseudonym for a masked reader; `Paket analizi` when none is given), chain strip (topology devices and points in `forward` order), at most five numbered findings in a fixed order (`window`, `hop-delta`, `loss`, `retransmission`, `rtt-first`), before/after KPI table with separate `Fark (sonra − önce)` and `Oran (sonra/önce)` columns (the ratio is empty where the earlier value is zero), the remaining findings as a smaller `Diğer gözlemler` block below the table, two bar charts (hop transit, per-point RTT) and three line charts on one time axis |
| Zaman Çizelgesi | one row per second bucket (`timeseries` origin/width): first-point packets, measured cycles, RTT, retransmission flags, loss events, and per forward hop the mean/maximum matched-packet transit; the engine's onsets (`onsets.items`) to the right |
| Hop Analizi | A hop table (matched packets of `observation_matches` per hop, mean/maximum per period, `segments` p50/p95/uncertainty), B per-point RTT (`cycle_points`), C cycle RX/TX per hop (`cycle_pairs`), D match coverage with colour scales (`adjacent_match_rates`), E per-point packets and retransmission flags (`obs`) |
| Örnek Olaylar | A the longest N cycles (`cycle_points`, N = `top`, default 25) split into adjacent-point RTT differences (stacked bar), B the retransmission ladder of the flow with most retransmissions (`flow_summary`, copies of one data segment per point in `obs`); a missing section is one line saying so |
| Yöntem ve Veri | capture points, glossary (project term to standard term, definition, limit), limits (host-side logs were not part of the analysis, unless host telemetry is attached), engine limitations, source capture SHA-256 values, parameters |

Before/after: by default the split is the earliest onset of the engine (segment and host-boundary scopes first, as the
engine's own cycle periods; any scope when there is none). Without any onset the report says that no event window was
found and shows whole-capture figures. `start`/`end` (epoch seconds) override it: Önce is the compared range up to
`start`, Sonra is `start` to `end` (default the end of the common window). Second buckets are assigned by their start.

Finding templates (each emitted only when its data exists): `window`, `hop-delta`, `hop-forward`, `hop-reverse`, `rtt`,
`rtt-first`, `cycles`, `match`, `loss`, `retransmission`, `rate`, `clock`, `longest`, `ladder` (`ExpertFindings`). `hop-delta`
names the hop component (RX or TX of one adjacent point pair, `cycle_pairs`) whose mean added time grew most from before to
after and the largest change among the other hops: numbers only, no cause and no threshold. Those of `window`, `hop-delta`, `loss`,
`retransmission`, `rtt-first` that exist form the Özet block, in that order; the others are "other observations".

Number formats: every numeric cell has an explicit format by kind (`ExpertReportTest.everyNumericCellOfTheWorkbookHasAnExplicitNumberFormat`
walks the workbook and fails on General): milliseconds `0.000` (`0.0` in a column whose values are all 100 or more), counts
`#,##0`, rates `#,##0.0`, ratios `0.00"×"`, signed differences `+0.000;-0.000;0.000` / `+#,##0;-#,##0;0`, shares `0.0%`. The
UTC column of the timeline and host grids is written only when the report zone is not UTC (otherwise it would repeat the
local clock).

Determinism (same case and options, same bytes; checked in `ExpertReportTest`): every mean is an exact integer sum
(nanoseconds or microseconds) divided in Java, so the order DuckDB's threads add numbers in cannot matter; every query
has a total order; no timestamp or random value enters the file; number and date text is locale-free; the package is
repacked with a fixed entry order, entry time (1980-01-02, set so that no time-zone dependent extra field is written),
document properties and chart language tag. Output is identical across runs, DuckDB threads 1 and 4, JVM time zones
and locales.

Privacy: `ExpertReportOptions.masker` is the projection. `ReportMasker.NONE` is the raw projection; the service passes a
masker backed by its name pseudonymizer and address masker, so names, flow labels (ordinals instead of identifiers) and
engine text are rewritten and the SHA-256 column is withheld, as for the replay viewer's JSON. The workbook never lists an
address or a payload field in either projection. Service route
`GET /api/v2/packet-analysis/cases/{id}/export/expert-report?start=&end=&top=` (action `packet_analysis_read`):
`security_admin` (raw view) gets the raw projection and a `RAW_VIEWED` ledger row, every other reader the masked one
(`REPORT_VIEWED`). CLI: `pa-expert-report <caseId> <dest.xlsx> [start=<epoch>] [end=<epoch>] [top=<n>]`; on a local project,
`ExpertReportCli --project DIR --output FILE.xlsx [--window-start E --window-end E --top N --threads N --title T]`.

## Host telemetry and the Sunucu Korelasyonu sheet (`...packetanalysis.telemetry`, expert report stage B)

A case can carry server-side text files next to its captures. They are parsed in a single streaming pass, by content
(never by file name), tolerant per line: a malformed line is skipped whole and counted by reason, never half applied.
The report lists every attached file, including the ones it could not use, with the reason.

| Format (detected from content) | Fields kept |
|---|---|
| `sar` text (`sar -A`, `-u -q -w -n DEV,TCP,ETCP`; 12 h or 24 h clock; banner date in ISO, `dd.mm.yyyy` or `mm/dd/yy`) | CPU `all` row: `%usr`/`%user`, `%sys`/`%system`, `%iowait`, `%idle`; `runq-sz`, `plist-sz`, `ldavg-1`, `blocked`; `proc/s`, `cswch/s`; per interface `rxpck/s`, `txpck/s` (at most 64 interfaces); TCP `active/s`, `passive/s`, `iseg/s`, `oseg/s`; ETCP `atmptf/s`, `estres/s`, `retrans/s`, `isegerr/s`, `orsts/s`. Blocks are told apart by their whole header (NFS also prints `retrans/s`). |
| `vmstat` with or without `-t` | `r b in cs us sy id wa st`. With `-t` the header's last word is the file's own zone label. The first row (average since boot) is not a sample. Without `-t` the rows are placed one second apart from a start time the user must give. |
| `pidstat` (process or `-t` thread rows) | rows printed per interval, and their change from the previous interval. No process name, user or PID is kept. |
| `tc -s qdisc` and `/proc/net/softnet_stat` snapshots, each under a timestamp line (`date`, ISO, epoch, optionally framed by `#`/`=`/brackets) | per device the root qdisc's `dropped` counter (children added only when no root is listed); softnet `processed`, `dropped`, `time_squeeze` summed over CPUs. Counters are shown as their increase; a counter that goes down is a reset and counts as no increase. |
| plain syslog / `/var/log/messages` (classic or ISO stamps) | lines per second, and program tags with their line counts. The message text is never kept. |

Anything else is listed as "not recognized" with a closed reason (empty file, no known format in the first 400 lines,
sysstat file without a block the report uses, no timestamps, needs a start time).

Alignment (the user confirms it; nothing is guessed silently): per file a time zone (IANA id or `+03:00`), a clock offset
in seconds added to the file's time, and, for files without a date (sar/pidstat without banner) or without times
(`vmstat` without `-t`), the local time of the first line. A file with no confirmed zone is read as UTC; the sheet's A
section, the method limits and a finding all say so. A timestamp that carries its own offset (`+03:00`, `Z`, epoch) is used
as written. The file's own zone label (vmstat `-t`) is shown, and a contradiction with the zone used is reported.
Classic syslog has no year: the year is the one (previous, current or next) that puts the line nearest the capture start.

Placement on the capture timeline's second grid (the same buckets and rows as Zaman Çizelgesi): a gauge shows the last
sample of the bucket, a counter its increase, a count the sum; sample time is the stamp at the end of the measured interval.

| Section of the sheet | Content |
|---|---|
| A. Saat eşlemesi | per file: format, host name, zone used (confirmed or assumed), file's own label, offset, start, first/last sample, overlap with the capture, lines used/ignored/skipped by reason, notes |
| B | vmstat `r b us sy id wa in cs`, sar CPU, run queue, process list, `proc/s`, `cswch/s` |
| C | sar TCP/ETCP columns and the busiest four interfaces' `rxpck/s`/`txpck/s` |
| D | qdisc `Δ dropped` per device (largest six), softnet `Δ dropped`, `Δ time_squeeze` |
| E | pidstat row count and `Δ` rows |
| F | syslog lines per second, and the most frequent program tags |
| chart | one line chart on the capture timeline, every series scaled to its own maximum (percent) so that series of different size share one axis; includes the capture's retransmission flags |

Findings added (facts only, no verdict word or threshold; stage C is pending): `host-align`, `host-runq`, `host-cpu`,
`host-retrans`, `host-qdisc`, `host-softnet`, `host-pidstat`, `host-syslog`, e.g. "run queue ... ortalaması (önce → sonra):
a → b; en büyük değer M, saat T". With files attached the Yöntem note "host-side logs were not part of the analysis" is
replaced by the actual limits (UTC assumptions, bucket rule, no NTP skew known).

Privacy: host names, interface and qdisc device names, program tags and file names go through `ReportMasker.name`;
SHA-256 values are withheld from masked readers; message text, user names and process names are not read into the
report in any projection.

Service: `POST /api/v2/packet-analysis/cases/{id}/telemetry` (multipart `file`, text only, 200 MB by default
`ui2.packet-analysis.max-telemetry-bytes`, stored in `<root>/cases/<id>/telemetry/`, same store cap, same 7-day retention and
pruner as the captures), `PUT /api/v2/packet-analysis/telemetry/{id}` (`time_zone`, `offset_seconds`, `anchor_local`),
`DELETE /api/v2/packet-analysis/telemetry/{id}`; all `packet_analysis_run`; the case detail lists the files. Ledger events
`TELEMETRY_UPLOADED`, `TELEMETRY_ALIGNED`, `TELEMETRY_DELETED` (migration V158). CLI: `pa-telemetry-upload`,
`pa-telemetry-align`, `pa-telemetry-delete`; on a local project `ExpertReportCli --telemetry FILE [--telemetry-zone Z
--telemetry-offset S --telemetry-start T]`.

Determinism: parsing and placement use integer milliseconds and fixed ordering (samples by time then file order; files by
name then id; devices and interfaces ranked by total then name); the workbook is byte-identical across runs, DuckDB
threads, attachment order, JVM time zones and locales (`HostCorrelationTest`).

## Memory plan, spill guard and analysis window guard (`...store.MemoryPlan`, `.SpillGuard`, `.AnalysisFailure`, `...topology.AnalysisWindowGuard`)

Real captures (180 MB to 900 MB, 1.4M to 3.5M packets) use 10 to 14 GB of memory in the engine and spill tens of GB.
A pod limit that is smaller than heap + DuckDB + native memory lets the kernel kill the pod mid-run, which loses the
run and is not fail-closed. The rules below make the limits add up and make every limit end a run cleanly. None of
them changes a result (`DETERMINISM.md`, "Resource guardrails").

**The arithmetic (HOST-A worker, `deploy/ui2/58-packet-analysis-deployment.yaml`).** DuckDB's `memory_limit` bounds
its buffer manager only; the JVM heap, metaspace, code cache, direct buffers, thread stacks, DuckDB natives outside the
buffer manager and the `tshark` child count against the pod limit as well.

| Part | Setting | GiB |
|---|---|---|
| Pod limit (HOST-A has 16 cores, 60 GB) | `resources.limits.memory: 24Gi`, `cpu: "4"` | 24 |
| JVM heap | `JAVA_TOOL_OPTIONS=-Xms512m -Xmx6g` | 6 |
| DuckDB `memory_limit` | `UI2_PACKET_ANALYSIS_DUCKDB_MEMORY_MB=14336` | 14 |
| Left for native / off-heap / `tshark` | `-XX:MaxMetaspaceSize=512m`, `-XX:MaxDirectMemorySize=1g`, code cache, stacks, DuckDB natives, one `tshark` | 4 |
| DuckDB threads | `UI2_PACKET_ANALYSIS_DUCKDB_THREADS=4` (the CPU limit; results are identical across thread counts) | |

The engine enforces the same rule at run time: the DuckDB limit is lowered to
`ceiling = container - heap - max(2 GiB, container / 8)` (container = the JVM's view of the pod limit, `--memory-mb` on
the command line). For this pod: `24 - 6 - 3 = 15 GiB >= 14 GiB`, one GiB of margin beyond the engine's own reserve;
`tools/tests/test_ui2_deployment_manifests.py` and `MemoryPlanTest` assert the sum. The old pod (10 GiB, heap
`MaxRAMPercentage=50`, DuckDB limit unset = DuckDB's default of 80% of the machine) could not hold one real case.
All three connection openers of a case (`ProjectStore`, `topology.ProjectDb`, `engine.Engine`) take the same
`memory_limit`, `temp_directory` and `max_temp_directory_size`, so the engine's connection is no longer unbounded.

**Spill.** DuckDB spills to `<case>/.spill` on the analysis volume (the PVC), not to RAM or the container filesystem.
Its `max_temp_directory_size` (`UI2_PACKET_ANALYSIS_MAX_TEMP_MB`, default 40 GiB, lowered to the volume's free space less
2 GiB) is only a soft bound: DuckDB 1.5.6 exceeded it by 4x in a sort (256 MiB setting, 1 GiB written) and by 4.7x on
real case 1 (40 GiB setting, 188 GiB written, which is more than the 120 GiB claim). `SpillGuard` is the hard bound: while a
run is in progress it polls the size of the case's spill directory (and the volume's free space) every second, and past
`UI2_PACKET_ANALYSIS_HARD_TEMP_MB` (60 GiB, half the claim; default twice the soft ceiling) it interrupts the run's DuckDB
connections. The interrupted run ends as the memory-limit failure below.

**A run that reaches a limit ends cleanly.** DuckDB's `Out of Memory Error`, the spill guard, and a JVM
`OutOfMemoryError` in the engine thread all become `FAILED` with reason `ANALYSIS_MEMORY_LIMIT`
(`AnalysisFailure`, `PacketAnalysisJobExecutor`): the run ledger row has `failure_reason = ANALYSIS_MEMORY_LIMIT`, the
UI shows it with "Narrow the analysis window (start and end time) or analyse fewer or smaller captures, then run it
again." (`PacketAnalysisScreen`). The command line exits 4 with the same reason in `run.json` (`failure.reason`,
`failure.message`). A capture that fails at the memory limit during ingest is no longer counted as "a file that failed"
and the swallowed export step re-throws a memory failure. The reason never quotes a statement, a path or capture content.
The second closed reason is `ANALYSIS_WINDOW_REQUIRED` (below). `ENGINE_FAILED` stays for every other failure.

**Analysis window guard.** The engine analyses the common coverage of the captures in the path unless the topology
has its own `start`/`end`. Window and budget: when a capture has more than `UI2_PACKET_ANALYSIS_MAX_PACKETS_PER_CAPTURE`
(default 150,000; 0 = off) packets in that range, or the range is longer than `UI2_PACKET_ANALYSIS_MAX_WINDOW_SECONDS`
(default 0 = off), the analysis is bounded to a window:

- a window is feasible when no capture has more than the budget in it; the winner covers the most captures (each with at
  least 20 packets), then is the widest (60, 30, 10, 5, 2, 1, 0.5, 0.2, 0.1 s; within the seconds budget if set), then the
  earliest. This is the window selection of the S16 real-case measurements (at most 150,000 packets per capture), computed
  from the stored timestamps only: same input, same window. If no window covers two captures the run stops with
  `ANALYSIS_WINDOW_REQUIRED` and asks for an explicit window;
- an explicit `start`/`end` in the topology is never changed; a window the guard wrote itself (recognised by the stored
  setting `analysis_window`) is recomputed on the next run, one the user edited is theirs;
- it is visible: the topology gets `start`/`end`, the report has `analysis_window` (`mode: guarded`, `reason: budget`, the
  window, the busiest capture before and inside the window, the budget, a one-line `note`), `run.json` always has
  `analysis_window` (`full`, `guarded`, `explicit` or `disabled`), and the masked run summary carries the numbers
  (the UI shows "Analysed window: ..., reason: budget");
- within budget it does nothing: no `start`/`end`, no `analysis_window` key in the report (the frozen end-to-end outputs
  are compared unchanged, `PacketAnalysisEndToEndTest`).

Command line: `--max-packets-per-capture N`, `--max-window-seconds N`, `--max-temp-mb N` next to `--memory-mb`,
`--duckdb-memory-mb`, `--threads`. Measured with the production plan on anonymized real cases (Mac, 10 CPUs, heap 6g,
`--memory-mb 24576 --duckdb-memory-mb 14336 --threads 4`, spill hard limit 60 GiB; wall time includes tshark ingest;
RSS in GiB from `/usr/bin/time -l`):

| Case | Captures, size, packets | Mode | Result | Wall | Peak RSS | Max spill | Window |
|---|---|---|---|---|---|---|---|
| 1 | 3, 285 MB, 1.44M | guarded | analysed | 147 s | 17.7 | 3.9 GB | 30 s (range 10,842 s, busiest capture 484,422 -> 104,577 packets) |
| 1 | same | full range | stopped, exit 4 `ANALYSIS_MEMORY_LIMIT` | 157 s | 14.8 | 61.4 GB at the trip | whole range |
| 1 | same | full range, no spill guard (before) | analysed | 829 s | 15.1 | 188 GiB | whole range |
| 3 | 9, 902 MB, 3.45M | guarded | analysed | 594 s | 15.2 | 1.1 GB | 10 s (range 120 s, 867,255 -> 67,336 packets, 9 captures) |
| 3 | same | full range | analysed | 579 s | 14.2 | 1.5 GB | whole range |
| 5 | 3, 179 MB | guarded | analysed | 100 s | 17.3 | 3.3 GB | 30 s (range 55.7 s, 300,157 -> 140,553 packets) |
| 5 | same | full range | analysed | 395 s | 15.5 | 40.3 GB | whole range |

Peak RSS stays between 14 and 18 GiB, below the 20 GiB of heap + DuckDB and far below the 24 GiB pod limit (peak
footprint 17 to 20 GiB). The window guard bounds the analysed scope and the pathological spill: case 5 is 4x faster
and case 1 5.6x faster than their whole-range runs, and the spill guard turns case 1's 188 GiB whole-range run into a
clean stop after 61 GB. Neither shrinks the ingest or the stages that read whole captures, so case 3 (ingest-dominated,
little spill) takes the same time either way.

Tests: `MemoryPlanTest` (arithmetic, ceiling, spill bound, same settings on every connection), `AnalysisFailureTest`
(real DuckDB out-of-memory at the memory and spill limit, an operator that cannot spill, cause chains, JVM heap),
`SpillGuardTest` (a real sort interrupted past the hard limit, sticky trip), `AnalysisWindowGuardTest` (within budget
untouched, deterministic window, explicit/stale windows, no feasible window, seconds budget), `EngineMemoryLimitTest`
(the engine on a frozen project under a 2 MiB limit is classified), `PacketAnalysisEndToEndTest` (`mode: full` and
unchanged frozen output; a range with no window stops with exit 4 and `run.json`), worker `PacketAnalysisJobExecutorTest`
(FAILED with `ANALYSIS_MEMORY_LIMIT` from a real tiny-limit DuckDB error and from a heap error, `ANALYSIS_WINDOW_REQUIRED`,
the masked summary) and `PacketAnalysisResourcesTest` (environment to properties).

Known limits: the service pod reads cases through the same openers (DuckDB limit a quarter of its pod, 1 to 8 GiB, bounded
by the same ceiling) but without the spill guard, which watches runs only; the window guard looks at timestamps of
the stored packets, so ingest memory for a very large capture is bounded by the DuckDB limit and the spill guard, not by
the window.

## End-to-end pipeline (`PacketAnalysisMain`)

One process, no network, no UI: capture folder (or files plus a topology) -> ingest (tshark) -> topology / case
setup -> engine -> `report.json` and `findings-export.json` (FindingsExport schema 11, including the transport
profiles, the DNS page and the field differences the reference export adds). The export is built by the one
product implementation, `CaseRunner.prepareAndExport` -> `Export.exportData` (field differences in
`export/FieldDiff`); the neXus service calls the same method, so the corpus verifies what ships.

```
java -cp <runtime classpath> com.securityexpert.nexus.ui2.packetanalysis.PacketAnalysisMain \
    --out OUT [--case-dir DIR] [--memory-mb N] [--duckdb-memory-mb N] [--cpus N] [--threads N] CAPTURE_FOLDER
java ... PacketAnalysisMain --out OUT --topology topology.json [--confirm-suggested-nat] CAPTURE...
```

Without `--topology` it is the reference `case` command (`create_case`: default points, pre-filter on, automatic
assembly); with it, the `demo` / `analyze` path (pre-filter off, files ingested in the given order, topology
capture ids given as file names). `--threads` sets the DuckDB threads (default `min(8, max(1, cpus - 1))`),
`--memory-mb` the memory budget (DuckDB gets a quarter, 1-8 GiB, unless `--duckdb-memory-mb`; always lowered to
`memory - heap - reserve`), `--case-dir` the project directory (default `OUT/case`). `run.json` records budget, the
memory plan, timings, the stage log and the analysis window; exit status 3 means the assembly was ambiguous (no
analysis; the case context is in `run.json`), 4 that the run stopped at a resource limit (`failure.reason` is
`ANALYSIS_MEMORY_LIMIT` or `ANALYSIS_WINDOW_REQUIRED`).

`PacketAnalysisEndToEndTest` runs it on three tiny synthetic fixtures (`src/test/resources/packetanalysis/e2e/`,
case mode, a demo topology, NAT confirmation) at threads 1 and 4 against frozen output that
`tools/packet_analysis/parity/e2e_fixtures.py` writes only after it matched the reference; skipped without tshark.

Corpus parity (all 206 cases, L1/L2/L3, threads 1 vs 4, accepted tie-breaks in `corpus_ties.py`):

`tools/packet_analysis/parity/corpus_run.py --archive .../packet-analysis-parity-corpus.tar.gz --work SCRATCH
[--jobs 5] [--timing 5]`, run with the frozen reference interpreter (see the script header).

## Masked projection of payload-derived text (`com.securityexpert.nexus.ui2.packetanalysis.privacy`)

Every session without the `packet_analysis_raw_view` action (the replay viewer / aiview, and run roles that do not
hold raw view) reads the engine report, the findings export and the case DuckDB views through `PayloadMasker`
(`PacketAnalysisController.maskPayload`). Values that come from packet payload or from names carried in captures
become deterministic keyed tokens: `KIND-` + 10 upper-case hex of an HMAC-SHA256 under the product's privacy key
(`TopologyNamePseudonymizer.maskPayloadToken`, the same key as the device-name pseudonyms). The same value always gives
the same token, so "same host", "same path segment" and "same Call-ID" stay visible. Host names are tokenized
case-folded without a root dot (RFC 4343), so an HTTP Host, a TLS SNI and a DNS question for one name share a token.
Structure stays: HTTP method, version, scheme, port and slashes; SIP method and status; numbers, timings, booleans and
enum codes. Raw-view sessions read the raw values (field differences and downloads stay ledgered as `RAW_VIEWED`,
full-report views as `REPORT_VIEWED`).

Addresses are not rewritten here: address fields keep their value for run roles (as before) and the subnet-preserving
IP masker rewrites them for the replay viewer. Device and point names stay with `TopologyNamePseudonymizer` in
`PrivacyMaskingResponseBodyAdvice` (replay viewer only); names that come out of a capture or an import (Fortinet
source and interface names, capture interface names, file paths) are masked here for the replay viewer only.

Fail closed: `PayloadFields` classifies every string-bearing key. A string under an unlisted key becomes an opaque
`TXT-` token at runtime, and two tests fail on it: `PayloadMaskerTest.everyStringKeyInTheFrozenEngineOutputsIsClassified`
(every frozen synthetic engine output in `src/test/resources`) and
`PacketAnalysisPayloadMaskingApiTest.everyStringIsClassifiedAndTheReplayViewerReceivesOnlySafeFieldsOrMaskedValues`
(every JSON endpoint on a synthetic case). Free-text fields (engine prose, labels, Wireshark filters, finding ids) keep
their text, but every payload value found anywhere in the same response is replaced inside them too.

| Field path (report / view) | Source | Masked treatment |
|---|---|---|
| `proxies.transactions[].request` | HTTP request line (application index), or the TLS SNI for a TLS leg | method + `/PATH-…/PATH-…?QUERY-…` + version; an SNI becomes `HOST-…` |
| waterfall `requests[].label`, `items[kind=http].title` | HTTP request line (proxy waterfall, `/waterfall`) | request-line structure, as above |
| application events `line`, `host`, `xff` | HTTP request line, Host header, X-Forwarded-For (`app_protocol.metadata`) | request line as above; `HOST-…[:port]`; addresses to the IP masker |
| `f5.requests[].request` | HTTP request (F5 trailer frames, tshark `http.request.*`) | request-line structure; absolute form keeps scheme and port, host and userinfo become `HOST-…` / `USER-…` |
| evidence `vendor."http.request.uri"` | HTTP request URI (vendor fields) | path segments and query as `PATH-…` / `QUERY-…` |
| `tls[].sni`, `findings[type=tls_interception].metrics.sni` | TLS ClientHello SNI | `HOST-…` |
| `metrics.before_chain[]` / `after_chain[]` `.subject`, `.issuer` | certificate subject / issuer names | `CERT-…` (the fingerprint stays) |
| `sip.comparisons[].call_id`, `sip.one_way[].call_id`, `findings[].metrics.call_id` | SIP Call-ID | `CALL-…` |
| `findings[].evidence[].protocol_identity."sip.Call-ID"` | SIP Call-ID (UDP index) | `CALL-…`; numeric identities (`sip.CSeq.seq`, `dns.id`, ...) stay |
| `sip.comparisons[].changes."sip.Contact" / "sip.Via" / "sip.From" / "sip.To"` `.before` / `.after` | SIP header identities | `SIP-…` |
| `sip.comparisons[].changes."sdp.connection_info.address"`, RTP `metrics.advertised_address` / `actual_address` | SDP / RTP addresses | address: IP masker |
| `sip.comparisons[].changes."sdp.media.port"` | SDP media port | unchanged (number) |
| `/dns` `items[].question` (also `qname`, `dns_name`) | DNS question name (`path_fields.dns_name`) | `HOST-…` |
| evidence `vendor."f5ethtrailer.vip"` | F5 virtual server name | `VNAME-…` |
| evidence `vendor."f5ethtrailer.rstcausetxt"`, `f5.resets[].reason` | F5 reset cause text | `VTXT-…` (`rstcause`, `flowid`, `peerid`, `tmm`, ... stay) |
| evidence `vendor."fw1.interface"` | Check Point fw monitor interface name | `VNAME-…` (`fw1.direction`, `fw1.chain`, `fw1.uuid` stay) |
| evidence `vendor."f5ethtrailer.peeraddr*"` | F5 peer addresses | address: IP masker |
| `fortinet_conversion.source`, `.clock_group`, captures `inventory.source_metadata.source` | Fortinet import source file name | replay viewer: capture-name pseudonym; run roles: unchanged |
| `fortinet_conversion.files[].path`, captures `path` | analysis-volume file path | replay viewer: `[REDACTED_PATH]` |
| `fortinet_conversion.files[].interface`, Fortinet point `label` / `vendor_stage`, point / capture `interface` | Fortinet or capture interface name | replay viewer: interface pseudonym (`OBJ-…`) |
| `findings[].id`, `summary`, `headline`, `reason`, `note`, `label`, `location`, `content_filter`, `flow_filter`, ... | engine text that may embed the values above | text kept; embedded payload values replaced by their tokens |
| any other string key | not classified | `TXT-…` (fail closed) |

Served by `/cases/{id}/state` (report, captures, topology, Fortinet conversion), `/flows`, `/frames`, `/diagnosis`,
`/charts`, `/ladder`, `/waterfall`, `/timeseries`, `/topology/overlay`, `/topology/inspect`, `/findings`, `/events`,
`/dns`, `/export/findings` (FindingsExport JSON), `/sessions/evidence`, `/reference/template`, `/compare`,
`/correlation`, `PUT /topology` (its response) and `/runs/{id}/report` (run roles; the replay viewer keeps the stored
masked summary). The HTML/JSON/CSV/XLSX downloads (`/export/download`), `/field-diff` and capture downloads stay
raw-view only.
