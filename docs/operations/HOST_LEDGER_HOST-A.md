# Host ledger — `HOST-A`

Append-only. One entry per operation that touches the host or its cluster, per
`PO_DECISION_RECORD_2026_09_15A` EV-2 and EV-3. Counts and shapes only; no
address, hostname, username, port number or incumbent container name.

Each entry carries: date, tier, why, the intended commands as literals, the
expected end state, the exact rollback, and — after execution — exit status,
wall time, the four baseline counts, and any divergence.

---

## Entry 0 — baseline, 2026-09-15, `HOST_R`

**Why.** Establish the incumbent's footprint before neXus touches the host, so
that a later question of attribution has something to compare against (`EV-1`).

**Taken.** A read-only inventory over SSH: operating system and kernel, core and
memory counts, disk capacity and free space, uptime and load, container runtime
version, running container count, container network count and their address
block sizes, listening port count, presence or absence of a Kubernetes
distribution, host interface address block size, and whether the CGNAT range
routes locally.

**Observed, as counts and shapes.**

| Measure | Value |
|---|---|
| Operating system | Current Ubuntu LTS, kernel 6.8 |
| Cores / memory | 16 / 62 GiB, with 48 GiB free |
| Disk | 1 TiB, 22% used |
| Uptime / load | 11 days / ~3.5 across 16 cores |
| Container runtime | Docker, with Compose available |
| Incumbent containers running | 14 |
| Incumbent container networks | 2, both `/16` inside `172.16/12` |
| Host listening ports | 5 |
| Kubernetes present | no |
| Host interface | one `/23`, RFC1918 |
| CGNAT range routes via | the local interface, not the corporate tunnel |

**Divergence.** None; this entry establishes the baseline rather than comparing
against one.

**Note.** This inventory was taken before this ledger and its governing record
existed. It is recorded here once, reduced to counts and shapes, and is not
re-taken without a named authorization.

---
