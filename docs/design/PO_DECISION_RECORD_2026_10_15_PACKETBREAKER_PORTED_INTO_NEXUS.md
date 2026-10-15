# PO decision record 2026-10-15 — PacketBreaker is ported into neXus as a native Java feature

## Status

**RATIFIED — PRODUCT OWNER, 2026-10-15**, in chat: "farklı ürün olmasın. Bizim ürüne ek feature olarak koysak ?",
"bunu refactor edip kendin javaya çevirip gömemez miyiz", and on the conditions below: "1- Diğer tarafta geliştirme
olmayacak, 2- ok."

Supersedes `HOST_A_PACKETBREAKER_COLOCATION_PLAN.md` (a separate product on HOST-A) before it was ratified.

## Decision

1. **One product.** PacketBreaker (offline multi-capture pcap correlation; Python + DuckDB + tshark, ~20k lines,
   291 tests, React UI) becomes a neXus feature ("Packet Analysis"), rewritten in Java inside the neXus code base. It
   uses neXus sign-in, RBAC, audit, ingress, build and ship gates; no separate host name, user list or pipeline.
2. **Frozen source.** No new development in the PacketBreaker repository from 2026-10-15. Its `main`, tagged
   `freeze-before-nexus-port`, is the behavioural reference for the port; after the port the Java feature is the only
   source.
3. **Parity first.** Before any stage of the port ships, a parity harness runs the same synthetic captures through
   the frozen Python and the Java implementation and compares the findings JSON on stable fields. A stage that does
   not match is not shipped. Real customer captures are used for parity only on the operator's machine and are never
   committed.
4. **Engine shape.** tshark stays the only dissector (installed in the analysis worker image); heavy work stays in
   DuckDB SQL through its JDBC driver. Analysis runs in a dedicated worker with CPU/memory limits and a lower priority
   than device jobs; one analysis at a time, queued.
5. **Raw-evidence exception (AGENTS.md, Raw-evidence law).** Uploaded pcaps are raw evidence and may be stored, on
   these terms:
   - retained **7 days** from upload, then deleted with their derived DuckDB data by a scheduled pruner (ledgered
     like backup retention);
   - stored only in the analysis volume, never in browser artefacts, logs, support bundles or the repository;
   - per-file limit 2 GB; total store capped (initially 100 GB) — an upload that would pass the cap is refused;
   - these terms bind captures uploaded by operators; they authorize no new device command.
6. **Roles.**
   - Upload and run an analysis: a new action (`PACKET_ANALYSIS_RUN`) granted to operator-level roles and above.
   - View raw packet details and download a capture: a separate action (`PACKET_ANALYSIS_RAW_VIEW`), security_admin
     by default.
   - `aiview` (`role:replay_viewer`): masked findings and summaries only — addresses and names pass through the
     masked projection; no raw packet view, no download.
   - Every upload, analysis, raw view, download and deletion is audited.
7. **Phases.** (1) ingest + core flow/loss analysis; (2) topology and path integrity; (3) application and security
   hints; (4) UI and neXus correlation (firewall, interface, MAC, zone and matching rule on the path). Each phase ships
   through its own PRs with the full neXus gates.
