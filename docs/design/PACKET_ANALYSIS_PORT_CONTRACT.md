# Packet Analysis — PacketBreaker port contract

## Status

**DRAFT — implementation authority for phase 0 (parity harness), the numeric layer and the product shell (§5)**, under
`PO_DECISION_RECORD_2026_10_15_PACKETBREAKER_PORTED_INTO_NEXUS.md` (RATIFIED). Later phases are frozen one at a time
once the PacketBreaker handover (`docs/HANDOVER_TO_NEXUS.md` in the frozen PacketBreaker repository, tag
`freeze-before-nexus-port`) is read.

## 1. Reference

- Behavioural reference: the frozen PacketBreaker repository on the operator's Mac (`~/Codo/PacketBreaker`, version
  0.3.10, Python 3.12 venv in `.venv`), tshark from Wireshark.app.
- Measured 2026-10-15: 37 synthetic scenarios (`packetbreaker demo DIR --scenario X`) generate in 74 s total with 6
  in parallel; 119 MB of pcaps, 234 MB of `report.json`. Pcaps are byte-identical across runs; reports differ only in
  random capture ids, generation times and paths.

## 2. Module layout in neXus

| Piece | Where | Notes |
|---|---|---|
| Engine | new Gradle module `ui2/packet-analysis` | Plain Java, no Spring. tshark subprocess, pcap/pcapng block walker, DuckDB through `org.duckdb:duckdb_jdbc`, stages, report model. SQL kept as resource files, ported near-verbatim. A `main` for parity runs. |
| Synthetic captures | `ui2/packet-analysis/src/testFixtures` | Java port of the PacketBreaker generators; byte-identical output proven by SHA-256 against the Python reference. |
| Job | worker job type `packet_analysis_run` | Dedicated worker deployment with tshark in its image, CPU/memory limits, lower priority, one analysis at a time. |
| Storage | PVC for captures + per-case DuckDB | 2 GB/file, 100 GB cap, 7-day retention pruner (decision record §5). Case metadata and masked summaries in PostgreSQL. |
| API + UI | service + frontend "Packet Analysis" | Streamed upload, RBAC actions `PACKET_ANALYSIS_RUN` / `PACKET_ANALYSIS_RAW_VIEW`, masked projection for aiview. |

## 3. Phase 0 — parity harness (this contract authorizes it)

1. `tools/packet_analysis/parity/reference.py` (run with the frozen PacketBreaker venv): for every scenario and the
   variant axes the CLI offers (`--ip-id increment|zero|constant|random`, `--ipv6`), generate the captures, run the
   analysis, and write a **canonical report**: capture ids replaced by capture file names, volatile fields removed
   (`generated_at`, absolute paths, durations, engine version), keys sorted, floats rendered with a fixed rule.
2. Committed goldens (small): per scenario/variant, the SHA-256 of every generated pcap, the SHA-256 of the canonical
   report and of each top-level report section, and the canonical `findings`, `verdict`, `quality`, `segments` and
   counts in full (gzip if larger than 256 KB). Everything else is regenerated locally on demand.
3. `tools/packet_analysis/parity/compare.py`: diff a Java canonical report against the Python one section by section
   and print the first differing paths.
4. Java generator port with a unit test per scenario asserting the pcap hashes.

## 4. Parity rule for every later phase

A port stage ships only when, for every golden scenario/variant it covers, the Java canonical sections equal the
golden ones. Differences that are deliberate (a proven Python bug) are listed in this contract with the scenario, the
section path and the reason, and the PO is told.

## 5. Containers (PO 2026-10-15: "tek bir yekün container bize sorun çıkarabilir")

- API, upload, case list, results, RBAC, audit, masking: the existing `ui2-service` and frontend — no new container.
- Engine: ONE new deployment `ui2-packet-analysis` (tshark + DuckDB + Java engine), own CPU/memory limits (start 4 CPU
  / 10 GiB), lower priority class, one analysis at a time per pod, claims only `packet_analysis_run` jobs. Moves to its
  own image (UBI9 + JRE 21 + tshark) so tshark never enters the other neXus images; until then it runs the common image
  with the stub engine.
- Storage: PVC `ui2-packet-analysis-data` (local-path, RWO; single node so service and engine pods share it). Only the
  engine writes a case's DuckDB file (DuckDB is single-writer). Ingest and analysis stay in one pod for the same reason.
- Why not one combined container: an 8 GB analysis spike or OOM would take uploads, UI and API down with it; tshark CVEs
  would land in every neXus image scan; limits and scaling could not be set for the engine alone.

## 6. Schedule (PO 2026-10-15: same pace as PacketBreaker itself, built 2026-10-08..14 in 168 commits)

| Day | Parallel lanes | Gate |
|---|---|---|
| 1 | corpus + parity harness · Python-exact numeric/format layer · neXus shell (API, job, RBAC, retention, deployment, UI) with stub engine | corpus reproducible; shell green |
| 2 | ingest (tshark -> DuckDB, metadata walker) · topology and case setup | L1 `packets` identical on the corpus |
| 3 | core engine (selection, matching, clocks, loss classification, segments, verdict, export v11) | L2/L3 core sections identical |
| 4 | detectors in 3 lanes: time/event · integrity/proxy/TLS/transport · UDP/DNS/RTP/SIP/vendor | whole corpus identical |
| 5 | UI port into neXus, HTML export/compare/anonymizer, real-case parity on the operator's Mac, ship | same verdict on the three real cases |

Defaults until the PO says otherwise: payload zeroing at upload optional and off; repository visibility and tshark
licensing are the PO's.

## 7. Out of scope until frozen per phase

Pipeline stage boundaries, DuckDB schema, API shapes, UI, image build (tshark packaging on UBI9), and the neXus
correlation (firewall, interface, MAC, zone and rule on the path).
