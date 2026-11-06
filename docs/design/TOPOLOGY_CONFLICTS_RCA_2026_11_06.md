# Topology conflicts after the 2026-11-05 fleet inventory — root cause (2026-11-06)

Status: FINDINGS (root-cause record; authorizes no change). Counts and opaque ids only; no address, name or serial
was read into chat or this record.

## Observation
The first topology build after aiview's fleet inventory collection (2026-11-05 21:27-21:28 UTC, 131 of 132 devices)
reported 300 conflicts against 254 in the build just before, with the same code (image unchanged; the new build was
triggered by a ui2-service restart). Diff by kind and vendor (old -> new):

| Kind | Vendor | Old | New |
|---|---|---:|---:|
| ADDRESS_PREFIX_UNKNOWN | Check Point | 18 | 54 |
| INTERFACE_WITHOUT_CONTEXT | Palo Alto | 3 | 25 |
| CLUSTER_MEMBERS_DISAGREE | Check Point | 2 | 0 |
| DUPLICATE_PRIVATE_PREFIX | Palo Alto | 14 | 12 |
| NEXT_HOP_UNREACHABLE | Palo Alto | 5 | 0 |
| OVERLAPPING_PREFIX | Palo Alto | 5 | 2 |

Nodes 496 -> 388 (cloud 336 -> 179, cluster unit 118 -> 155, routing context 42 -> 54); attachments 1,026 -> 851.
Twenty masked aiview path queries before and after: no regression (route table empty 4 -> 2, ambiguous ingress
7 -> 6, exits known topology 9 -> 12; the address sample differs because the node set changed).

## Root cause
No builder defect. The conflicts grew because the new inventory is more truthful than the 2026-11-03 one, which was
collected before two collector changes.

1. **Check Point, ADDRESS_PREFIX_UNKNOWN (+36), 16 VSX members.**
   - The interface, address and route rows of these devices are identical between the two runs: the same 317
     prefixless addresses on the same interfaces and contexts, and the same 5,418 routes. Only the address role
     changed. After the G5 change of 2026-11-04 (commit ed068bbb), the `cphaprob -a if` addresses of a VSX
     cluster-member VS are stored as `cluster_virtual`: 310 now, 19 before; they were `member` before.
   - Topology units use the derived cluster reference (`coalesce(c_parent.display_name, d.cluster_member_ref,
     dc.cluster_reference)` in `JooqDeviceRepository`), not the `devices.cluster_member_ref` column. That reference
     is set and unambiguous for 14 of the 16 devices: 29 matching discovery candidates, one distinct cluster value.
     So these are cluster units, and a Check Point cluster unit attaches only through `cluster_virtual` addresses.
   - Before G5, the VS VIPs were `member` addresses, which a cluster unit ignores, so they were silently absent
     from the topology. Now they are attachment candidates. None has a prefix: `cphaprob -a if` prints the address
     without a mask (worker fixtures `cphaprob_a_m_if*.txt`). No connected route of the same context contains them
     either: the 296 connected routes on those interfaces are /28 networks unrelated to the VIPs, so the
     connected-route fallback (`TopologyBuilder.connectedPrefix`) cannot apply.
   - The builder therefore reports one ADDRESS_PREFIX_UNKNOWN per VS context instead of guessing. The topology did
     not lose an attachment; these VIPs had none before. Why the member VS interfaces carry /28 networks is a vendor
     semantic this record leaves UNKNOWN.
2. **Palo Alto, INTERFACE_WITHOUT_CONTEXT (+22), 25 firewalls.**
   - The 2026-11-03 runs carried no virtual router on any of the 1,673 interfaces. The new runs map 1,105 of them
     to 55 virtual routers; 568 have none, and 38 of those carry addresses.
   - With more than one routing table per device, the builder builds one node per virtual router. It leaves out an
     interface that belongs to none, and reports INTERFACE_WITHOUT_CONTEXT once per device and context, by design.
   - The per-router split also explains the drop in cloud nodes (more next hops resolve) and the fall in PAN
     NEXT_HOP_UNREACHABLE and OVERLAPPING_PREFIX.

The VSX fix's own effect, fewer duplicate addresses, was already in the 254 baseline: the devices re-inventoried
right after the fix on 2026-11-04 are VSX members (DUPLICATE_ADDRESS 116 in both builds).

## What would reduce the remaining UNKNOWNs (not started)
- **VSX VIP prefixes.** A mask needs a source the repository does not have today. Two candidates:
  - the management-plane cluster object (`show-simple-cluster` / `show-gateways-and-servers details-level full`
    interface `mask-length`, already collected, but `CpObjectInventoryParser` drops `interfaces`). This mixes
    evidence planes and needs a decision and a lower evidence grade;
  - a device-side read that prints the VIP mask. That would be a new command and needs PO exact-command approval.
- **PAN interfaces outside any virtual router.** Likely management, HA or unassigned interfaces; no action unless
  path queries need them.
