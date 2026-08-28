# Phase 0.5 Final — UI/UX Closure

This is the final release of the 0.5 line. Collector commands and parser semantics are intentionally not redesigned here. The release closes presentation behavior before Phase 0.6 collector modernization and configuration evidence work.

## Vendor-aware presentation

A single generic cluster table was visually correct for Check Point ClusterXL but too repetitive for Palo Alto HA and logical VSX contexts. The final rule is:

> Common data model, vendor-aware presentation.

### Check Point physical ClusterXL

The SmartConsole-style matrix remains the primary interface view:

`Interface | Cluster VIP | Member 1 | Member 2 | Network`

This preserves the operational distinction between virtual cluster addresses and per-member addresses. Routing remains selectable by physical member.

### Check Point VSX logical contexts

The physical VSX cluster remains the parent. Logical Virtual Systems are children. Identical member-side interface and route records are collapsed into one logical row. If one physical member differs, that row retains member scope and is highlighted rather than being silently deduplicated.

### Palo Alto HA

Palo Alto HA no longer uses the Check Point matrix presentation. The HA pair is one parent with VSYS children. Identical data across HA members is shown once. Member-specific interface or route data is preserved with `Member Scope` only where a difference exists.

For routes, the normal state is one logical route table. If a member divergence is detected, the UI exposes:

- `Logical`
- member 1
- member 2
- `Diff only`

This keeps normal operation visually quiet while preserving forensic detail when an override or drift exists.

### Palo Alto VSYS identity

VSYS identifiers are supplemented by Virtual Router context. Example:

`VSYS 2 | PAYMENTS-RTR`

If multiple Virtual Routers are associated with one VSYS, the first router is shown with an additional-count indicator and the full list remains in the detail context.

## Visual system

The final 0.5 UI adds:

- persistent dark/light mode (`localStorage`, system-aware default),
- semantic theme variables rather than hard-coded dark colors,
- stronger surface hierarchy and softer material-style elevation,
- improved typography using system UI fonts,
- consistent radii, focus rings, hover states and chips,
- sticky table headers,
- clearer tree connectors and active hierarchy state,
- explicit divergence badges and member-scope chips,
- responsive reductions for smaller screens.

No external fonts, CDN assets or remote JavaScript are required.

## Safety and correctness

The UI never invents a VIP, member relationship or logical equality. Deduplication happens only when normalized runtime records are equal. Any member-only record remains visible and is marked as a difference.

Palo Alto HA parent inference is still a 0.5 UI-only heuristic based on the conservative member-name + VSYS/VR compatibility logic introduced in 0.5.3. Phase 0.6 should replace inferred HA identity with authoritative management/API evidence when available.

## Deferred to Phase 0.6

- Check Point Management API as authoritative gateway/cluster/member identity.
- Gaia API comparison against current CPRID runtime collection.
- Panorama/PAN-OS authoritative HA relationship collection.
- configuration snapshot/evidence/history.
- semantic fixes already tracked for VSX canonical network and Panorama default-route type classification.
