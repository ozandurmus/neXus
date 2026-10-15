# PacketBreaker handover to neXus — 2026-10-15

## Status

**REFERENCE — handover from the PacketBreaker author session**, received in chat 2026-10-15 (Turkish original kept by
the Product Owner). Condensed to English here; real host and customer names from the original are replaced by case
labels. Inputs to `PACKET_ANALYSIS_PORT_CONTRACT.md`; not implementation authority by itself.

## Reference point

PacketBreaker `main` 5b608e6 (PR #29), version 0.3.10, findings schema v11. Python is frozen: no new features; only
requested critical fixes and the phase-0 test assets.

## Code base (author's measurement)

- 21.2k lines Python in 88 modules: 18.5k production, 2.7k synthetic test generators. 822 tests in 68 files.
  8 golden scenarios. 36 HTTP endpoints, 7 CLI commands. 8.9k lines React/TS UI (React 19, ECharts 6, React Flow 12).
- About one fifth of the source is DuckDB SQL, with 209 table/view definitions; DuckDB JDBC runs the same engine, so
  the SQL ports near-verbatim. The rewrite is the orchestration and the statistics computed in Python.
- Runtime dependencies: duckdb, fastapi, uvicorn, pydantic, psutil, tzdata; tshark is the only external binary,
  called by 7 modules (ingest, batch_ingest, udp_index, application_index, capture_layers, anonymize, fortinet).
- Data flow: pcap/pcapng folder -> metadata (headers, zero tails, truncated records) -> `tshark -T fields` (77 core +
  vendor fields) -> `project.duckdb` (`packets`, PK capture_id+frame, ~45 columns) -> topology (suggest, order, groups,
  assemble) -> core engine (selection, matching, clocks, classification, segments) -> detectors (onset, host_path,
  cycles, incident, session_events, integrity, proxy, udp, ...) -> report dict + FindingsExport v11 -> REST + UI.
- Schema migrations 1..6. DuckDB memory budget: 25% of RAM, min 1 GB, max 8 GB.

## Layers and Java counterparts

| Layer | Python | Lines | Java | Difficulty |
|---|---|---|---|---|
| Ingest and storage | ingest, batch_ingest, metadata, timestamps, store | 1,334 | ProcessBuilder for tshark, TSV stream -> DuckDB Appender, ByteBuffer pcap headers | medium |
| Topology and case setup | topology/*, case_setup | 1,700 | records + Jackson, pure Java algorithms | medium |
| Core engine | analysis/*, matching, clock, byte_ranges, translation, evidence, headlines, export | 3,458 | SQL resources as is; clock envelope and statistics in Java | high |
| Time and event detectors | timeseries, onset, microbursts, host_path, hold_release, cycles, delivery, incident, device_drops, aggregates, session_events, security, retransmissions | 3,763 | one detector class each, mostly SQL | high |
| Integrity, proxy, protocol, vendor | integrity/*, proxy_*, tls, transport, fragments, capture_layers, application_index, udp_index, udp, dns, rtp, sip, vendors, fortinet | 5,406 | detector classes; extra tshark calls for tunnels/app indexing | medium |
| UI and output | app, cli, waterfall, stream_charts, field_diff, reference, anonymize, engine_export, assistant, pilot | 2,800 | product REST layer; pcap writer for the anonymizer | low-medium |
| Test generators | synthetic, realistic, *_synthetic (12) | 2,687 | not ported; their pcaps become a frozen corpus | none |

## Strengths to keep

Every finding is tied to file, frame and display filter; insufficient evidence returns `unknown` with a reason.
Validated untuned on three real cases (labelled here Case-1008: hold-and-release / qdisc drop; Case-0930: two paths,
chronic loss; Case-W: session behaviour). Golden tests compare semantically; tie ordering is deterministic. Test rules
(distractors, negative tests first, naive-implementation check) are written down.

## Weaknesses that affect the port

- Findings v11 types only the top level; the `report` object is free-form and the UI and goldens depend on its inner
  shape. The Java side must type it explicitly.
- Implicit order dependency: detectors use each other's temporary tables; the call order inside `report.analyze`
  matters. Java writes it as an explicit stage graph.
- SQL built with f-strings (values embedded as strings). Java should bind parameters, but keep the structure 1:1 at
  first so differences stay traceable.
- Tests generate pcaps at run time with Python and need tshark; the corpus must be frozen for Java.
- Single-user local design (no auth, one job worker, loopback-only Host/Origin checks) — redesigned in the product.
- Known limits: a full case analysis takes 5-8 minutes; session close is read from the point nearest the client (a
  missed FIN there looks like RST); tshark field names are version-dependent (PARSER_VERSION 13).

## Parity strategy (author's)

- Canonicalization (existing golden rules): floats to 9 significant digits, lists sorted, JSON-string fields parsed,
  32-hex ids and local times masked.
- Three levels: L1 ingest (`packets` row hash), L2 intermediate tables (obs, matches, clock offsets, segments, dumped
  from Python as parquet), L3 output (FindingsExport v11 + report, canonical diff).
- Numeric traps: `statistics.quantiles(n=100, method="inclusive")` and `median` (45 call sites) need an exact Java
  helper; Python `round()` is banker's rounding; `f"{x:.3f}"` -> `String.format(Locale.ROOT, ...)` (a Turkish locale
  prints a decimal comma); insertion-ordered dicts -> `LinkedHashMap`; tie-break ORDER BY must match; DuckDB
  Python and JDBC versions pinned equal; same tshark version both sides; time zones via java.time, +03:00 rendered
  identically.
- Acceptance corpus: 8 golden scenarios (demo, nat, offload, integrity_all, dns_diagnosis, fifty_flow, folder_b,
  oct8); all generator pcaps with topologies as fixed files; real cases with payload zeroed, never in the repository,
  in separate restricted storage. Negative tests first for every detector.

## Phases (author's estimate ~3 months)

0 freeze and corpus (1-2 days) · 1 skeleton + ingest, gate L1 identical (~1 week) · 2 topology and case setup (~1
week) · 3 core engine MVP: selection, matching, clocks, loss classification, segments, verdict, export v11 (2-3
weeks) · 4 time and event detectors, gate: same verdict and findings on the three real cases (2-3 weeks) · 5
integrity, proxy, protocol, vendor (3-4 weeks) · 6 UI, HTML export, compare, anonymizer, Python retired (~2 weeks).
Detectors not yet ported report `unknown` ("not in this engine version"), never vanish.

**Option raised (decision needed): shadow mode** — run the frozen Python engine as a separate container inside the
product from phase 1; every analysis runs on both engines and differences are recorded. Users get the full feature set
at once and parity is measured on real traffic.

## Target architecture (author's)

Separate engine service in the product deployment (tshark + JRE 21 + DuckDB in one glibc image; DuckDB JDBC ships
linux-amd64/arm64 natives, musl breaks it). Modules pb-core, pb-ingest, pb-engine, pb-api (keep `/api/*`, add product
auth, reuse the React UI). One writer per case (DuckDB single-writer; RWO volume, never NFS/RWX). Set DuckDB
`memory_limit` and `threads` explicitly from the pod limit. No outbound calls; upload payload-zeroing option,
retention, encrypted volume.

## Open questions the author listed, with neXus answers

| Question | neXus answer |
|---|---|
| Java stack | Java 21, Spring Boot (4.0 migration shipping), Gradle multi-module, own job engine with worker deployments |
| Integration shape | separate engine worker/service (agreed) |
| Users and identity | neXus sign-in (local + LDAP adapter), RBAC actions per decision record §6 |
| Capture input | browser upload, 100 MB-2 GB (PO 2026-10-15) |
| Anonymization / retention | raw pcaps 7 days (decision record §5); payload zeroing at upload — PO to decide |
| Cluster | k3s on HOST-A (not OpenShift), UBI9 base images, local-path RWO volumes |
| tshark GPLv2 in the image | PO / legal to confirm (internal use) |
| Repository visibility | `ozandurmus/packetbreaker` is public — PO to decide (recommend private) |
