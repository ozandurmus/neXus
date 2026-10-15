# HOST-A packet broker co-location plan

## Status

**DRAFT — awaiting Product Owner ratification.** The Product Owner asked on 2026-10-15 to use HOST-A for a separate
packet broker project "farklı podlar olarak", without affecting neXus, and chose "Ayrı bir kontener" over a separate
VM. This document is the plan and the proposed amendment to
`PO_DECISION_RECORD_2026_09_24_HOST_A_OWNED_BY_NEXUS_AGENT_SUDO.md`, which today makes HOST-A neXus's host alone.
Nothing here is built until the Product Owner ratifies §6.

## 1. Why this needs a plan, not just a namespace

- **Precedent.** On 2026-09-24 a co-hosted product filled the shared root disk (~6–8 GB/min of container logs) and took
  neXus down twice; that is why HOST-A was reinstalled for neXus alone. A packet broker writes capture data and logs at
  line rate, so the same failure is the first risk to design out.
- **Single node.** HOST-A is one k3s node: neXus's database, service, workers and the broker would share one kernel, one
  disk, one kubelet. A namespace is a naming and policy boundary, not a resource or kernel boundary.
- **Data class.** Mirrored traffic is customer payload (CLASS 3 in `PRIVACY_AND_DATA_HANDLING.md` terms). Today the
  host holds masked-by-default neXus data; adding raw traffic raises what a compromise of the host exposes.

## 2. Measured capacity (2026-10-15, read-only)

| Resource | Total | Used by neXus now | Free |
|---|---|---|---|
| vCPU | 16 | ~1 core average (node 5%) | ~15 |
| RAM | 60 GiB | ~6 GiB (rest is page cache) | ~54 GiB available |
| Root disk (single LV) | 1005 GB | 469 GB (50%) | 486 GB |
| NICs | 1 (management) | all neXus traffic | — |

CPU and RAM are not the constraint. **Disk and the single NIC are.**

## 3. Recommended shape

1. **Namespace `pbroker`**, no shared secrets, its own service account with namespace-scoped RBAC only; no access to
   `ui2`, `ui2-build`, `ui2-security`.
2. **Resource ceiling** — `ResourceQuota` and `LimitRange` on `pbroker` (starting point: 6 CPU, 16 GiB RAM, no
   `hostPath`), plus a `PriorityClass` below neXus so the kubelet evicts broker pods first under pressure.
3. **Disk isolation (mandatory)** — a **separate virtual disk** added to the VM, mounted at `/srv/pbroker` with its own
   filesystem, used through a fixed-size local volume. Broker capture files and container logs never land on the root
   LV. Container log rotation for the namespace capped (k3s `container-log-max-size` / `-max-files`). A full broker
   disk must stop the broker, never neXus.
4. **Traffic isolation** — a **second vNIC** dedicated to mirrored traffic (SPAN/ERSPAN/TAP feed), attached only to the
   broker pod (Multus `macvlan`/`host-device`), never to the node's default route. The management NIC carries no
   mirror traffic.
5. **Network policy** — default-deny ingress and egress in `pbroker`; explicit allows only for the broker's own outputs.
   A deny from `pbroker` to the `ui2` namespaces and to the neXus database port. (k3s ships an embedded NetworkPolicy
   controller; the existing `ui2-policy` and `security-egress` policies prove it is enforced.)
6. **Operations boundary** — the neXus agent does not read broker data and the broker does not read neXus data
   (AGENTS.md, Host action boundary). Broker images, builds and deploys go through their own pipeline, never
   `run_build.sh` or the ui2 ship gates.
7. **Monitoring** — the existing disk-pressure watch extended to the broker disk; an alert when the broker disk passes
   80% and when the root LV passes 70%.

## 4. Devil's advocate (Product Owner decides)

- A separate VM on the same hypervisor gives a real kernel, disk and NIC boundary for little extra work and keeps the
  2026-09-24 "neXus alone" decision intact. Recommended if the broker will carry production traffic at volume.
- If the broker needs DPDK, CPU pinning or hugepages, it will compete with neXus's database at the kernel level; that
  pushes the answer to a separate VM regardless.
- If the broker is a proof of concept with low traffic, the container shape in §3 is enough.

## 5. Open questions for the Product Owner

1. Expected mirrored traffic rate (Mb/s or Gb/s) and whether captures are stored or only forwarded.
2. Who builds and operates the broker (this agent, another team, a vendor image).
3. Whether the hypervisor team can add a second vNIC (port group allowing promiscuous mode for the mirror) and a
   second virtual disk, and their sizes.

## 6. Proposed amendment to the 2026-09-24 decision record (for ratification)

> HOST-A remains neXus's host. One additional, isolated workload — the packet broker — may run in namespace `pbroker`
> on the conditions of `HOST_A_PACKET_BROKER_COLOCATION_PLAN.md` §3: its own disk, its own mirror NIC, a resource
> quota and a lower priority class, default-deny network policy toward neXus, and no shared secrets. If the broker
> degrades neXus (disk, CPU, memory or network), the agent may scale the broker to zero without a prior ask and reports
> it to the Product Owner.
