# Phase 0.5.2 - Check Point ClusterXL Aggregate View

## Goal
Represent classic Check Point ClusterXL as one logical inventory object while preserving member-specific runtime evidence.

## Collection behavior
Existing CP interface/route collection is unchanged:
- `ip -details -4 addr show`
- `ip -4 route show table all`
- execution through the existing MDS -> CPRID path

For classic `cluster_member` objects only, Phase 0.5.2 adds the read-only command:

```text
cphaprob -a -m if
```

Only the `Virtual cluster interfaces:` section is parsed. This provides live ClusterXL VIP/interface evidence. A failure of this extra probe does not invalidate the existing interface/route inventory.

VSX cluster members are explicitly excluded from this classic ClusterXL aggregation path.

## Cluster identity
The backend does not depend on `-1/-2` names for identity. Members are grouped by:

```text
CMA + exact live virtual-interface/VIP fingerprint
```

A conventional `<base>-CLS` label is offered only when all collected member names match a `<base>-1`, `<base>-2`, ... pattern. The record explicitly marks that label as `inferred_member_pattern`; it is not claimed to be the authoritative SmartConsole cluster-object name.

A future management-object adapter can replace the label without changing the runtime cluster identity.

## UI
Classic CP members that share a ClusterXL VIP fingerprint are shown as one logical item.

Interface table adds for cluster view:
- Role (`VIP` / `MEMBER`)
- Member
- Interface
- IP
- Mask/network

VIP prefix/network is derived from the matching live member interface when available. The VIP itself always comes from `cphaprob`.

Routing table adds a `Member` column and intentionally does not deduplicate member routes. This makes member-specific routing differences visible.

## Backward compatibility
- Standalone CP gateways remain unchanged.
- VSX UI logic remains unchanged.
- Panorama UI logic remains unchanged.
- Existing CP member interface/route collection remains unchanged.
- ClusterXL probe is additive and non-blocking.

## Support telemetry
The support bundle now reports non-sensitive counts:
- ClusterXL groups
- ClusterXL members
- virtual interfaces
- per-device cluster probe status

## Known limitation
The display cluster name is not yet guaranteed to be the exact SmartConsole object name. Runtime identity/VIP data is authoritative; the human label may be inferred from member naming. Management-object name resolution should be added only after a real management API/object sample is validated.
