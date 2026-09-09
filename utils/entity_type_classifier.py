"""Real-device / unified-evidence -> `EntityType` classifier.

`project/backlog.json` id `navigation_entity_type_classifier_for_content_level_p2_p3`
(P2), follow-up to `M11` (`relay/NXS-LOCAL-0022`). M11 wired `D1` (stage 0)
into the navigation availability_rule and left P2 (entity applicability) and
P3 (evidence state) content unwired -- deliberately out of its own
`scope.out` as "new identity/producer-surface scope creep"
(`project/backlog.json`, same id). This module is that producer.

Entity-type authority: `docs/design/NAVIGATION_INFORMATION_ARCHITECTURE.md`
section 5.2's table -- the closed eight logical entity types already named
by `utils.capability_applicability.EntityType`. **This module never extends
or redefines that enum.** It is a distinct producer surface from D2
(`utils.capability_applicability.resolve_d2`, `EntityType` ->
`ApplicabilityResult`) and D3 (`utils.capability_vendor_support.resolve_d3`,
vendor -> `VendorSupportResult`) -- it feeds them (an `EntityType` this
module resolves is the very input `resolve_d2` expects), and does not
replace, modify or duplicate either producer's own frozen table.

Input shape: real `unified.json` rows (`utils/merge.py::run_merge`'s output
-- plain `dict`s, no schema exists anywhere in this repository; see
`utils/restore_readiness.py::resolve_entity_id`/`resolve_vendor`, the one
shared identity/vendor convention this module also reuses rather than
re-deriving). One call classifies **one selected logical subject's**
evidence:

- **one row** (`len(entries) == 1`) -- a single device/VS drilldown. Section
  5.2: a ClusterXL/PAN-pair *member* is "drilldown only, never the default
  view of the cluster/pair" -- so a lone row belonging to a cluster/pair
  classifies as the **member** type, never the cluster/pair type.
- **two or more rows** (`len(entries) >= 2`) -- the rows a caller gathers
  when the *cluster/pair itself* is selected (section 5.2's "default
  selection" for ClusterXL/VSX/PAN-pair rows), i.e. all member rows sharing
  one proven cluster/pair identity. Classifies as the **cluster/pair**
  type only when every row agrees on that identity; any disagreement,
  mixed source, or unconfirmed pairing fails closed.

PAN HA state (`enabled`/`peer_ip`) is not carried by `unified.json` itself
(confirmed by `utils/failover/assessment.py` line ~832: "unified.json
carries no PAN peer relationship today") -- it lives in the separate
`pan_config_telemetry.json` evidence, passed here as `pan_ha_runtime`
(`{device: {"enabled": ..., "peer_ip": ...}}`, the same shape
`utils/failover_readiness_ui.py::extract_pan_ha_runtime` produces). Without
it, a PAN row's type is genuinely unknown -- fails closed rather than
defaulting to standalone.

Fail-closed rule (`AC-4`/`AC-5` of this movement's own `SESSION_START`,
mirroring section 5.2's own PAN `B₂` rule): whenever the evidence does not
positively establish exactly one of the eight types, `entity_type` is
`None` and `reason` names the exact gap. Never a guess, never a fuzzy or
partial value, never a ninth value.
"""
from __future__ import annotations

from dataclasses import dataclass
from typing import Any, Mapping, Sequence

from utils.capability_applicability import EntityType

_NAV = "NAVIGATION_INFORMATION_ARCHITECTURE.md"

_HA_ENABLED_TOKENS = frozenset({"yes", "true", "1"})


@dataclass(frozen=True)
class EntityTypeClassification:
    """One classification attempt's answer, plus the exact reasoning cited
    for it. `entity_type is None` is the fail-closed outcome -- a genuine
    "cannot classify", never treated as a ninth enum value."""

    entity_type: EntityType | None
    reason: str


def _source(row: Mapping[str, Any]) -> str:
    return str(row.get("source") or "").strip().lower()


def _device(row: Mapping[str, Any]) -> str:
    return str(row.get("device") or "").strip()


def _vs_id(row: Mapping[str, Any]) -> str:
    return str(row.get("vs_id") or "").strip()


def _cluster_group_id(row: Mapping[str, Any]) -> str:
    """The grouping key `checkpoint/cp_runner.py::enrich_cluster_topology`
    writes (`cluster_topology.group_id`), with the legacy flat `cluster`
    string as fallback -- the same two fields `utils/failover/assessment.py`
    reads to group ClusterXL/VSX-host rows."""
    topology = row.get("cluster_topology")
    if isinstance(topology, Mapping):
        group_id = str(topology.get("group_id") or "").strip()
        if group_id:
            return group_id
    return str(row.get("cluster") or "").strip()


def _management_ip(row: Mapping[str, Any]) -> str:
    return str(row.get("management_ip") or "").strip()


def _classify_single_cp(row: Mapping[str, Any]) -> EntityTypeClassification:
    group_id = _cluster_group_id(row)
    if group_id:
        return EntityTypeClassification(
            EntityType.CP_CLUSTERXL_MEMBER,
            reason=(
                f"{_NAV} section 5.2: single 'cp' row carries cluster_topology "
                f"group_id={group_id!r} -- member drilldown, never the cluster's "
                "own default view"
            ),
        )
    return EntityTypeClassification(
        EntityType.CP_STANDALONE_GATEWAY,
        reason=f"{_NAV} section 5.2: single 'cp' row, no cluster_topology/cluster field",
    )


def _classify_single_vsx(row: Mapping[str, Any]) -> EntityTypeClassification:
    vs_id = _vs_id(row)
    if vs_id:
        return EntityTypeClassification(
            EntityType.CP_VIRTUAL_SYSTEM,
            reason=f"{_NAV} section 5.2: single 'vsx' row carries vs_id={vs_id!r} (VSID)",
        )
    return EntityTypeClassification(
        EntityType.CP_VSX_HOST_CLUSTER,
        reason=(
            f"{_NAV} section 5.2: single 'vsx' row with no vs_id -- the physical "
            "VSX host (the enum has no separate lone-host type; host and its "
            "cluster share one type)"
        ),
    )


def _classify_single_panorama(
    row: Mapping[str, Any], pan_ha_runtime: Mapping[str, Mapping[str, Any]] | None
) -> EntityTypeClassification:
    device = _device(row)
    runtime = (pan_ha_runtime or {}).get(device)
    if runtime is None:
        return EntityTypeClassification(
            None,
            reason=(
                f"pan_ha_runtime evidence not provided for device {device!r} -- "
                "unified.json carries no PAN HA state; cannot establish standalone "
                "vs. HA-member without it (fail closed, never defaulted to standalone)"
            ),
        )
    enabled = str(runtime.get("enabled") or "").strip().lower()
    if enabled not in _HA_ENABLED_TOKENS:
        return EntityTypeClassification(
            EntityType.PAN_STANDALONE_FIREWALL,
            reason=f"pan_ha_runtime.enabled={runtime.get('enabled')!r} for {device!r}",
        )
    peer_ip = str(runtime.get("peer_ip") or "").strip()
    if not peer_ip:
        return EntityTypeClassification(
            None,
            reason=(
                f"pan_ha_runtime.enabled='yes' for {device!r} but no peer_ip -- "
                "HA state is genuinely ambiguous, not standalone (fail closed)"
            ),
        )
    return EntityTypeClassification(
        EntityType.PAN_HA_MEMBER,
        reason=(
            f"{_NAV} section 5.2: HA enabled with a declared peer_ip for "
            f"{device!r} -- member drilldown; B₂ bidirectional corroboration "
            "is a pair-level (not member-level) question, see the group case"
        ),
    )


def _classify_group_cp(entries: Sequence[Mapping[str, Any]]) -> EntityTypeClassification:
    if any(_vs_id(row) for row in entries):
        return EntityTypeClassification(
            None,
            reason="'cp' rows with a vs_id present -- not a ClusterXL grouping (fail closed)",
        )
    group_ids = {_cluster_group_id(row) for row in entries}
    if len(group_ids) != 1 or not next(iter(group_ids)):
        return EntityTypeClassification(
            None,
            reason=f"entries do not share exactly one cluster_topology.group_id: {sorted(group_ids)!r}",
        )
    return EntityTypeClassification(
        EntityType.CP_CLUSTERXL_CLUSTER,
        reason=(
            f"{_NAV} section 5.2: {len(entries)} 'cp' rows share one "
            f"cluster_topology.group_id={next(iter(group_ids))!r} -- the cluster's "
            "own default selection"
        ),
    )


def _classify_group_vsx(entries: Sequence[Mapping[str, Any]]) -> EntityTypeClassification:
    if any(_vs_id(row) for row in entries):
        return EntityTypeClassification(
            None,
            reason="'vsx' rows with a vs_id present -- virtual systems, not host-cluster members (fail closed)",
        )
    group_ids = {_cluster_group_id(row) for row in entries}
    if len(group_ids) != 1 or not next(iter(group_ids)):
        return EntityTypeClassification(
            None,
            reason=f"entries do not share exactly one cluster_topology.group_id: {sorted(group_ids)!r}",
        )
    return EntityTypeClassification(
        EntityType.CP_VSX_HOST_CLUSTER,
        reason=(
            f"{_NAV} section 5.2: {len(entries)} 'vsx' host rows (no vs_id) "
            f"share one cluster_topology.group_id={next(iter(group_ids))!r}"
        ),
    )


def _classify_group_panorama(
    entries: Sequence[Mapping[str, Any]], pan_ha_runtime: Mapping[str, Mapping[str, Any]] | None
) -> EntityTypeClassification:
    if len(entries) != 2:
        return EntityTypeClassification(
            None,
            reason=f"a PAN HA pair is exactly two members, got {len(entries)} (fail closed)",
        )
    row_a, row_b = entries
    device_a, device_b = _device(row_a), _device(row_b)
    runtime_a = (pan_ha_runtime or {}).get(device_a)
    runtime_b = (pan_ha_runtime or {}).get(device_b)
    if runtime_a is None or runtime_b is None:
        return EntityTypeClassification(
            None,
            reason=f"pan_ha_runtime evidence missing for {device_a!r} and/or {device_b!r}",
        )
    enabled_a = str(runtime_a.get("enabled") or "").strip().lower() in _HA_ENABLED_TOKENS
    enabled_b = str(runtime_b.get("enabled") or "").strip().lower() in _HA_ENABLED_TOKENS
    if not (enabled_a and enabled_b):
        return EntityTypeClassification(
            None,
            reason=f"HA not enabled on both members ({device_a!r}, {device_b!r})",
        )
    peer_ip_a = str(runtime_a.get("peer_ip") or "").strip()
    peer_ip_b = str(runtime_b.get("peer_ip") or "").strip()
    mgmt_a, mgmt_b = _management_ip(row_a), _management_ip(row_b)
    if peer_ip_a and peer_ip_b and mgmt_a and mgmt_b and peer_ip_a == mgmt_b and peer_ip_b == mgmt_a:
        return EntityTypeClassification(
            EntityType.PAN_HA_PAIR,
            reason=(
                f"{_NAV} section 5.2: {device_a!r} and {device_b!r} mutually agree "
                "peer_ip <-> management_ip -- the pair's own default selection"
            ),
        )
    return EntityTypeClassification(
        None,
        reason=(
            f"{_NAV} section 5.2: peer_ip/management_ip mutual agreement not "
            f"established between {device_a!r} and {device_b!r} -- B₂ "
            "bidirectional corroboration NOT ESTABLISHED; the workspace must not "
            "imply a confident pair (fail closed)"
        ),
    )


def classify_entity_type(
    entries: Sequence[Mapping[str, Any]],
    *,
    pan_ha_runtime: Mapping[str, Mapping[str, Any]] | None = None,
) -> EntityTypeClassification:
    """Classify one selected logical subject's real `unified.json` evidence
    onto exactly one of the eight closed `EntityType` values, or fail closed.

    `entries`: the row(s) for the one subject currently selected -- one row
    for a device/VS drilldown, two or more rows (all agreeing on one proven
    cluster/pair identity) for a cluster/pair's own default selection.
    `pan_ha_runtime`: PAN HA state keyed by device (`{"enabled": ..., "peer_ip":
    ...}`) -- required for any 'panorama'-sourced entry; `unified.json` alone
    never carries it.
    """
    if not entries:
        return EntityTypeClassification(None, reason="no evidence entries provided")

    sources = {_source(row) for row in entries}
    if len(entries) == 1:
        row = entries[0]
        source = _source(row)
        if source == "cp":
            return _classify_single_cp(row)
        if source == "vsx":
            return _classify_single_vsx(row)
        if source == "panorama":
            return _classify_single_panorama(row, pan_ha_runtime)
        return EntityTypeClassification(None, reason=f"unrecognized source {row.get('source')!r}")

    if len(sources) != 1:
        return EntityTypeClassification(
            None, reason=f"entries mix sources {sorted(sources)!r} -- not one grouped logical subject"
        )
    devices = {_device(row) for row in entries}
    if len(devices) < 2:
        return EntityTypeClassification(
            None,
            reason="a cluster/pair grouping needs 2+ distinct devices, got repeats of the same device",
        )

    (source,) = sources
    if source == "cp":
        return _classify_group_cp(entries)
    if source == "vsx":
        return _classify_group_vsx(entries)
    if source == "panorama":
        return _classify_group_panorama(entries, pan_ha_runtime)
    return EntityTypeClassification(None, reason=f"unrecognized source {source!r}")
