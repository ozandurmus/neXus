# HOST-A PacketBreaker co-location plan

## Status

**SUPERSEDED 2026-10-15 before ratification** by `PO_DECISION_RECORD_2026_10_15_PACKETBREAKER_PORTED_INTO_NEXUS.md` (PacketBreaker becomes a neXus feature, not a separate product). Kept as the record of the capacity measurement and risks. On 2026-10-15 the Product Owner asked to host PacketBreaker (a
separate project of the same owner: offline pcap correlation and analysis, FastAPI + DuckDB + tshark) on HOST-A under
a different name, without disturbing neXus's container layout. Answers given in chat the same day:

- Workload: an operator uploads pcaps of 100 MB to 1–2 GB; each analysis runs 5–10 minutes.
- Uploaded files may be deleted after one week.
- No second disk or NIC: use the existing disk; expected consumption is well under 100 GB.
- The agent builds and operates it (from a separate chat); it is "our project".

This plan amends `PO_DECISION_RECORD_2026_09_24_HOST_A_OWNED_BY_NEXUS_AGENT_SUDO.md`, which today makes HOST-A neXus's
host alone.

## 1. Risks this plan designs out

- **Disk.** On 2026-09-24 a co-hosted product filled the shared root disk and took neXus down twice. PacketBreaker
  writes uploads and DuckDB files on the same root LV (486 GB free of 1005 GB on 2026-10-15). It gets a hard
  application-level cap and a one-week retention, and its container logs are bounded.
- **Memory and CPU.** DuckDB and tshark are memory-hungry during a 5–10 minute analysis. A container limit plus
  PacketBreaker's own `memory_limit_mb` keep it inside its share; a lower priority class makes the kubelet evict it
  before any neXus pod.
- **Access.** PacketBreaker today is a single-user local app: it accepts only loopback `Host` headers, has no login,
  and has endpoints that open arbitrary server paths (`POST /api/project`, `POST /api/captures/attach`). Exposed as is
  on a server, any reachable user could read files inside its container. It needs a server mode (§4) before it is
  reachable.
- **Data class.** Pcaps carry customer payload. They stay inside PacketBreaker's own volume and namespace; neXus never
  reads them and PacketBreaker never reads neXus data.

## 2. Capacity (measured 2026-10-15, read-only)

| Resource | Total | neXus now | Proposed PacketBreaker ceiling |
|---|---|---|---|
| vCPU | 16 | ~1 core average | 4 (limit), 1 (request) |
| RAM | 60 GiB | ~6 GiB | 10 GiB limit; `memory_limit_mb` 7000 |
| Root disk | 1005 GB | 469 GB used | 100 GB application cap |

## 3. Host layout

1. **Namespace `packetbreaker`**, separate from `ui2`, `ui2-build`, `ui2-security`. Its own service account
   (namespace-scoped, no token automount), no shared secrets.
2. **ResourceQuota + LimitRange** on the namespace (§2 ceilings), **PriorityClass** below neXus.
3. **Storage**: one `local-path` PVC mounted at `/data` (uploads + project DuckDB). local-path does not enforce size,
   so the app enforces the 100 GB cap (§4) and a CronJob in the namespace deletes uploads older than 7 days.
4. **Network**: default-deny NetworkPolicy; ingress only from the k3s ingress controller to the app port; egress none
   (the app makes no outbound calls by its own rule). Explicit deny toward the `ui2*` namespaces.
5. **Reachability**: the existing k3s ingress (443) routes by host name — e.g. `packetbreaker.<corp-domain>` →
   PacketBreaker, the current name → neXus. Needs one DNS record pointing at HOST-A and a TLS certificate for that name
   (the corporate CA, as for neXus). neXus's ingress, ports and certificates stay unchanged.
6. **Image and deploy**: built and rolled out by PacketBreaker's own pipeline into its namespace; never through
   neXus's `run_build.sh`, ship gates or `ui2-build` jobs (those collide: an overlapping job broke a neXus preview
   build on 2026-10-15).
7. **Container logs** bounded (k3s `container-log-max-size` / `container-log-max-files` already apply node-wide; check
   they are set before go-live).

## 4. PacketBreaker server mode (work in the PacketBreaker repository)

A configuration flag (e.g. `--server`), off by default so the desktop app is unchanged:

1. Bind `0.0.0.0` inside the container; accept the configured public host name in the `Host` check instead of
   loopback only (CSRF/origin checks stay).
2. **Authentication** in front of every route. First version: ingress basic-auth or OIDC proxy with a small user list
   held in a Kubernetes secret; later, optional sign-in against the same directory neXus uses.
3. **Disable path-opening routes** (`/api/project` with a free path, `/api/captures/attach`); upload is the only way in.
4. **Upload limits**: streamed to disk (no whole-file buffering), 2 GB per file, refuse when `/data` usage would pass
   100 GB.
5. **Retention**: uploads and per-upload results older than 7 days deleted by the CronJob; the UI shows the deletion
   date.
6. **One analysis at a time** (the app's job pool), queued; health endpoint for the kubelet probes.

## 5. Order of work

1. PacketBreaker repo: server mode (§4) + container image (tshark + Python), tested locally.
2. Product Owner: DNS name for the host, confirmation of who may sign in.
3. HOST-A: namespace, quota, priority class, PVC, NetworkPolicy, ingress rule, retention CronJob (§3) — by manifest
   from the PacketBreaker repo, applied by the agent.
4. Go-live check: neXus e2e green before and after; disk, memory and CPU of neXus unchanged under one 2 GB analysis.

## 6. Proposed amendment to the 2026-09-24 decision record (for ratification)

> HOST-A remains neXus's host. PacketBreaker may run in namespace `packetbreaker` on the conditions of
> `HOST_A_PACKETBREAKER_COLOCATION_PLAN.md` §3–§4: a resource quota and a lower priority class, a 100 GB application
> disk cap with 7-day retention, default-deny network policy toward neXus, its own ingress host name, its own build
> pipeline, and no shared secrets. If PacketBreaker degrades neXus (disk, CPU, memory or network), the agent may scale
> it to zero without a prior ask and reports it to the Product Owner.
