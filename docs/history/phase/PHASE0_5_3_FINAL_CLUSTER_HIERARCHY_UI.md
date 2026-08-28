# Phase 0.5.3 — Final 0.5 Cluster Hierarchy UI

Phase 0.5.3 closes the 0.5 UI/reliability line before 0.6 collector/config-evidence work.

## Goals

- Present a firewall HA/cluster as the primary inventory object instead of duplicating physical members.
- Keep member-level evidence available for troubleshooting and asymmetry checks.
- Render cluster interfaces in a SmartConsole-like matrix: interface on the left, VIP/member gateways across the top, IP addresses at the intersection.
- Render routing per gateway using member tabs instead of interleaving both gateways in one table.
- Place VSX Virtual Systems and PAN-OS VSYS views under their physical cluster parent with an expandable/collapsible tree.
- Make global/network search find children and automatically expand their parent.
- Preserve existing collectors, freshness/LKG semantics, support-bundle privacy and parser behavior.

## Check Point / ClusterXL

The existing `cphaprob -a -m if` read-only runtime probe is now attempted for every discovered `cluster_member`, including VSX physical members. Failure of this extra probe is non-blocking and does not change the existing interface/route result.

Runtime VIP fingerprinting remains the preferred Check Point cluster identity. Member-name inference is used only as a display/fallback relation when runtime VSX evidence shows that the same pair backs the same VS contexts.

Real-world member names ending in an extra separator, for example `NAME-1_` / `NAME-2_`, are now recognized for the familiar `NAME-CLS` display label. The label is still marked as inferred; authoritative management-object naming is deferred to the 0.6 Management API work.

## VSX hierarchy

VSX deduplication is now scoped by physical cluster + VSYS + VSID instead of VSYS name alone. This prevents two unrelated clusters with the same VSYS name from being merged.

Example UI hierarchy:

```text
FW-TEST-VSX-CLS
  > VS-APP-A
  > VS-APP-B
```

Physical member rows are removed from the top level when they can be safely associated with the VSX pair. Member provenance is retained on both interface and routing rows.

## Palo Alto hierarchy

The current Panorama runtime collector does not yet return an authoritative HA-cluster object. Phase 0.5.3 therefore uses a deliberately conservative UI-only inference:

1. device names must form a 1/2 pair; and
2. live VSYS signatures must substantially match; and
3. live Virtual Router signatures must substantially match.

Only then is a `*-CLS` parent generated. PAN VSYS children are derived from the runtime interface `vsys` field. Routes are attached to a child only where the Virtual Router can be mapped back to that VSYS; ambiguous routes remain visible on the parent and are not guessed into a child.

This relation is explicitly not authoritative management configuration. Phase 0.6 should replace it with vendor API/management truth where available.

## UI behavior

Cluster/interface view:

```text
Interface | Cluster VIP | GW-1 | GW-2 | Network
```

A missing VIP/member address is shown as an empty marker rather than fabricated data.

Routing view:

```text
[ GW-1 ] [ GW-2 ]
```

Only the selected member's routing table is displayed. This keeps normal HA tables readable while allowing deliberate per-member overrides or unexpected mismatches to be inspected.

Sidebar cluster parents are collapsible. Search over firewall name, child VSX/VSYS, interface, IP, subnet or route keeps the parent visible and automatically opens it when a child matches.

## Collection-method impact

No existing primary collection method is replaced in this phase:

- CP interface/routes: unchanged CPRID runtime commands.
- VSX: unchanged prompt-aware nested SSH/runtime collection.
- Panorama: unchanged operational API `target=<serial>` collection.
- CP direct SSH fallback: remains observe-only.

The only collector extension is the non-blocking ClusterXL `cphaprob` probe on VSX physical cluster members.

## Validation

- Python regression suite: 55 passed, 2 expected xfails.
- `node --check static/app.js`: pass.
- `python -m compileall -q main.py checkpoint panorama utils`: pass.
- Runtime UI-model evaluation against a representative runtime sample produced `FW-TEST-VSX-CLS` with `VS-APP-A` and `VS-APP-B` children.
- Runtime UI-model search for `VS-APP-A` returned the `FW-TEST-VSX-CLS` parent and auto-expanded the matching child.
- Interface matrix rendering exposed both physical member headers; routing tabs exposed both gateway buttons.

The two expected xfails remain the previously deferred semantic items: VSX canonical network calculation and Panorama default-route type ordering.
