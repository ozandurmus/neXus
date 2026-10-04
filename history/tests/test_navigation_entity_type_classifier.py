"""`project/backlog.json` id `navigation_entity_type_classifier_for_content_level_p2_p3`
-- the real-device/unified-evidence -> `EntityType` classifier
(`utils/entity_type_classifier.py`), follow-up to `M11`
(`relay/NXS-LOCAL-0022`).

Proves: all eight closed `EntityType` values are reachable from a realistic
`unified.json`-shaped evidence entry (`AC-4`), ambiguous/insufficient
evidence fails closed rather than guessing (`AC-4`), and the classifier
composes with the existing D2 (`utils.capability_applicability.resolve_d2`)
and D3 (`utils.capability_vendor_support.resolve_d3`) producers for the
`ha_readiness` worked example already in D2's own table -- "a standalone
firewall has no HA readiness" (`AC-5`).

Every test below calls pure functions with plain typed arguments and asserts
on the return value only -- no I/O, no device, no network, matching
`tests/test_m10_3_entity_and_vendor_support_producers.py`'s own style.
"""
from __future__ import annotations

from utils.capability_applicability import (
    APPLICABLE,
    NOT_APPLICABLE,
    EntityType,
    resolve_d2,
)
from utils.capability_vendor_support import SUPPORTED, resolve_d3
from utils.entity_type_classifier import EntityTypeClassification, classify_entity_type
from utils.restore_readiness import resolve_vendor


def _cp(device: str, *, group_id: str | None = None) -> dict:
    row: dict = {"source": "cp", "device": device}
    if group_id:
        row["cluster_topology"] = {"group_id": group_id, "display_name": f"{device}-CLS"}
    return row


def _vsx(device: str, *, vs_id: str | None = None, group_id: str | None = None) -> dict:
    row: dict = {"source": "vsx", "device": device, "vsys": "" if vs_id is None else f"vs{vs_id}"}
    if vs_id is not None:
        row["vs_id"] = vs_id
    if group_id:
        row["cluster_topology"] = {"group_id": group_id, "display_name": f"{device}-CLS"}
    return row


def _pan(device: str, *, management_ip: str) -> dict:
    return {"source": "panorama", "device": device, "management_ip": management_ip}


class TestEachOfTheEightEntityTypes:
    def test_cp_standalone_gateway(self):
        result = classify_entity_type([_cp("cp-edge-01")])
        assert result.entity_type is EntityType.CP_STANDALONE_GATEWAY

    def test_cp_clusterxl_member_single_row_drilldown(self):
        result = classify_entity_type([_cp("cp-core-01", group_id="uitest-cxl")])
        assert result.entity_type is EntityType.CP_CLUSTERXL_MEMBER

    def test_cp_clusterxl_cluster_grouped_members(self):
        result = classify_entity_type(
            [
                _cp("cp-core-01", group_id="uitest-cxl"),
                _cp("cp-core-02", group_id="uitest-cxl"),
            ]
        )
        assert result.entity_type is EntityType.CP_CLUSTERXL_CLUSTER

    def test_cp_vsx_host_cluster_single_host_row(self):
        result = classify_entity_type([_vsx("vsx-gw-01")])
        assert result.entity_type is EntityType.CP_VSX_HOST_CLUSTER

    def test_cp_vsx_host_cluster_grouped_hosts(self):
        result = classify_entity_type(
            [
                _vsx("vsx-gw-01", group_id="uitest-vsx"),
                _vsx("vsx-gw-02", group_id="uitest-vsx"),
            ]
        )
        assert result.entity_type is EntityType.CP_VSX_HOST_CLUSTER

    def test_cp_virtual_system(self):
        result = classify_entity_type([_vsx("vsx-gw-01", vs_id="10")])
        assert result.entity_type is EntityType.CP_VIRTUAL_SYSTEM

    def test_pan_standalone_firewall(self):
        result = classify_entity_type(
            [_pan("pan-solo", management_ip="192.0.2.10")],
            pan_ha_runtime={"pan-solo": {"enabled": "no"}},
        )
        assert result.entity_type is EntityType.PAN_STANDALONE_FIREWALL

    def test_pan_ha_member_single_row_drilldown(self):
        result = classify_entity_type(
            [_pan("pan-ha-01", management_ip="192.0.2.111")],
            pan_ha_runtime={"pan-ha-01": {"enabled": "yes", "peer_ip": "192.0.2.112"}},
        )
        assert result.entity_type is EntityType.PAN_HA_MEMBER

    def test_pan_ha_pair_grouped_and_mutually_corroborated(self):
        result = classify_entity_type(
            [
                _pan("pan-ha-01", management_ip="192.0.2.111"),
                _pan("pan-ha-02", management_ip="192.0.2.112"),
            ],
            pan_ha_runtime={
                "pan-ha-01": {"enabled": "yes", "peer_ip": "192.0.2.112"},
                "pan-ha-02": {"enabled": "yes", "peer_ip": "192.0.2.111"},
            },
        )
        assert result.entity_type is EntityType.PAN_HA_PAIR

    def test_all_eight_values_are_the_closed_enum(self):
        reached = {
            classify_entity_type([_cp("d1")]).entity_type,
            classify_entity_type([_cp("d2", group_id="g1")]).entity_type,
            classify_entity_type([_cp("d3", group_id="g2"), _cp("d4", group_id="g2")]).entity_type,
            classify_entity_type([_vsx("d5")]).entity_type,
            classify_entity_type([_vsx("d6", group_id="g3"), _vsx("d7", group_id="g3")]).entity_type,
            classify_entity_type([_vsx("d8", vs_id="1")]).entity_type,
            classify_entity_type(
                [_pan("d9", management_ip="10.0.0.1")], pan_ha_runtime={"d9": {"enabled": "no"}}
            ).entity_type,
            classify_entity_type(
                [_pan("d10", management_ip="10.0.0.2")],
                pan_ha_runtime={"d10": {"enabled": "yes", "peer_ip": "10.0.0.3"}},
            ).entity_type,
            classify_entity_type(
                [_pan("d11", management_ip="10.0.0.4"), _pan("d12", management_ip="10.0.0.5")],
                pan_ha_runtime={
                    "d11": {"enabled": "yes", "peer_ip": "10.0.0.5"},
                    "d12": {"enabled": "yes", "peer_ip": "10.0.0.4"},
                },
            ).entity_type,
        }
        assert reached == set(EntityType)


class TestFailClosed:
    def test_no_entries_fails_closed(self):
        result = classify_entity_type([])
        assert result.entity_type is None

    def test_pan_row_without_ha_runtime_fails_closed_never_defaults_to_standalone(self):
        """`unified.json` alone carries no PAN HA evidence -- a caller that
        forgets to supply `pan_ha_runtime` must get a named gap, never a
        silent 'standalone' guess."""
        result = classify_entity_type([_pan("pan-unknown", management_ip="192.0.2.1")])
        assert result.entity_type is None
        assert "pan_ha_runtime" in result.reason

    def test_pan_ha_enabled_but_no_peer_ip_fails_closed(self):
        result = classify_entity_type(
            [_pan("pan-half-configured", management_ip="192.0.2.1")],
            pan_ha_runtime={"pan-half-configured": {"enabled": "yes"}},
        )
        assert result.entity_type is None

    def test_pan_pair_not_mutually_corroborated_fails_closed(self):
        """B2 bidirectional corroboration NOT ESTABLISHED (section 5.2) --
        both sides declare HA enabled but the peer_ip/management_ip facts do
        not agree; must not be rendered as a confident pair."""
        result = classify_entity_type(
            [
                _pan("pan-a", management_ip="192.0.2.1"),
                _pan("pan-b", management_ip="192.0.2.2"),
            ],
            pan_ha_runtime={
                "pan-a": {"enabled": "yes", "peer_ip": "192.0.2.99"},
                "pan-b": {"enabled": "yes", "peer_ip": "192.0.2.98"},
            },
        )
        assert result.entity_type is None

    def test_mixed_source_group_fails_closed(self):
        result = classify_entity_type([_cp("cp-1", group_id="g"), _vsx("vsx-1", group_id="g")])
        assert result.entity_type is None

    def test_group_with_disagreeing_cluster_identity_fails_closed(self):
        result = classify_entity_type([_cp("cp-1", group_id="g1"), _cp("cp-2", group_id="g2")])
        assert result.entity_type is None

    def test_unrecognized_source_fails_closed(self):
        result = classify_entity_type([{"source": "mystery", "device": "x"}])
        assert result.entity_type is None

    def test_result_is_a_plain_dataclass_never_a_ninth_value(self):
        result = classify_entity_type([_cp("cp-edge-01")])
        assert isinstance(result, EntityTypeClassification)
        assert result.entity_type is None or result.entity_type in set(EntityType)


class TestComposesWithD2AndD3ForTheHaReadinessWorkedExample:
    """`utils/capability_applicability.py`'s own D2 table cites this exact
    worked example ('a standalone firewall has no HA readiness',
    `NAVIGATION_INFORMATION_ARCHITECTURE.md` section 7.2). This proves the
    new classifier's output feeds `resolve_d2`/`resolve_d3` correctly for
    one real content case, and that P2 (type) and P3 (vendor evidence) are
    genuinely independent: the vendor DOES support `ha_readiness` in
    general (D3 `SUPPORTED`) even though this specific entity type does not
    apply (D2 `NOT_APPLICABLE`)."""

    def test_cp_standalone_gateway_ha_readiness_not_applicable_but_vendor_supported(self):
        row = _cp("cp-edge-01")
        classification = classify_entity_type([row])
        assert classification.entity_type is EntityType.CP_STANDALONE_GATEWAY

        d2 = resolve_d2(entity_type=classification.entity_type, capability="ha_readiness")
        assert d2.value == NOT_APPLICABLE

        vendor = resolve_vendor(row)
        assert vendor == "checkpoint"
        d3 = resolve_d3(vendor=vendor, capability="ha_readiness")
        assert d3.value == SUPPORTED

    def test_pan_standalone_firewall_ha_readiness_not_applicable_but_vendor_supported(self):
        row = _pan("pan-solo", management_ip="192.0.2.10")
        classification = classify_entity_type([row], pan_ha_runtime={"pan-solo": {"enabled": "no"}})
        assert classification.entity_type is EntityType.PAN_STANDALONE_FIREWALL

        d2 = resolve_d2(entity_type=classification.entity_type, capability="ha_readiness")
        assert d2.value == NOT_APPLICABLE

        vendor = resolve_vendor(row)
        assert vendor == "panorama"
        d3 = resolve_d3(vendor=vendor, capability="ha_readiness")
        assert d3.value == SUPPORTED

    def test_cp_clusterxl_cluster_ha_readiness_applicable(self):
        rows = [_cp("cp-core-01", group_id="uitest-cxl"), _cp("cp-core-02", group_id="uitest-cxl")]
        classification = classify_entity_type(rows)
        assert classification.entity_type is EntityType.CP_CLUSTERXL_CLUSTER

        d2 = resolve_d2(entity_type=classification.entity_type, capability="ha_readiness")
        assert d2.value == APPLICABLE
