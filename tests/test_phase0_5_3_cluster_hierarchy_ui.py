from pathlib import Path

from checkpoint.cp_runner import _cluster_display_name
import pytest
from utils.html_export import compose_report_script as _composed_report_script

pytestmark = pytest.mark.inventory

ROOT = Path(__file__).resolve().parents[1]
APP = _composed_report_script()
CSS = (ROOT / "static" / "style.css").read_text(encoding="utf-8")
TEMPLATE = (ROOT / "templates" / "index.html").read_text(encoding="utf-8")
SCRIPT = (ROOT / "checkpoint" / "scripts" / "cp_inventory.sh").read_text(encoding="utf-8")


def test_cluster_display_name_handles_real_trailing_separator_member_names():
    display, source = _cluster_display_name([
        "FW-TEST-VSX-1_",
        "FW-TEST-VSX-2_",
    ])
    assert display == "FW-TEST-VSX-CLS"
    assert source == "inferred_member_pattern"


def test_cluster_display_name_handles_zero_padded_trailing_separator_member_names():
    """Real-env finding: some estate objects use zero-padded ordinals
    (NAME-01_ / NAME-02_), which the single-digit pattern above didn't cover
    -- it fell back to the raw, underscore-suffixed member names instead of a
    clean "-CLS" label."""
    display, source = _cluster_display_name([
        "FW-CKP-ARKTEST-01_",
        "FW-CKP-ARKTEST-02_",
    ])
    assert display == "FW-CKP-ARKTEST-CLS"
    assert source == "inferred_member_pattern"


def test_vsx_cluster_probe_is_read_only_and_attempted_for_all_cluster_members():
    assert 'if [ "$OBJ_NORM" = "cluster_member" ]; then' in SCRIPT
    assert '"cphaprob -a -m if"' in SCRIPT
    assert "VSX physical members" in SCRIPT


def test_ui_has_hierarchical_cluster_navigation_and_search_expansion_contract():
    for marker in [
        "function buildInventoryHierarchy(",
        "function filteredHierarchy(",
        "const expandedGroups = new Set()",
        "parentDisplayName",
        "device-child",
        "tree-toggle",
    ]:
        assert marker in APP or marker in CSS


def test_cluster_interface_matrix_and_conditional_route_comparison_contract():
    assert "function renderClusterInterfaceMatrix(" in APP
    assert "Cluster VIP" in APP
    assert "matrix-member-header" in CSS
    assert 'id="routeMemberTabs"' in TEMPLATE
    assert "Route comparison" in APP
    assert "activeRouteViewByEntry" in APP
    assert "routeDivergence" in APP


def test_panorama_ha_hierarchy_consults_canonical_ha_readiness_units():
    """OP.0b S9: PAN HA pairing for the Inventory tree is read from the
    canonical `failoverReadinessData.units` (`utils.failover.assessment.
    _derive_pan_units`), not re-inferred client-side from hostname-ordinal
    matching and VSYS/VR Jaccard similarity -- that heuristic is retired."""
    assert "function panoramaPairCompatible(" not in APP
    assert "vsysSimilarity" not in APP
    assert "routerSimilarity" not in APP
    assert "function haReadinessUnitsByType(" in APP
    assert 'haReadinessUnitsByType("pan_ha_pair", "panorama")' in APP
    assert "function panoramaVsysChildren(" in APP
    assert 'entityType: "pan_vsys"' in APP
    assert 'entityType: "pan_cluster"' in APP


def test_cp_vsx_cluster_synthesis_consults_canonical_ha_readiness_units():
    """OP.0b S9: when `aggregateCpClusters` finds no runtime-proven ClusterXL
    VIP fingerprint for a VSX physical pair, the Inventory tree falls back to
    the canonical `cp_vsx_cluster` unit `utils.failover.assessment.
    _derive_cp_units` already grouped (`cluster_topology.group_id`, or its
    legacy `cluster`-field fallback) -- not a client-side hostname-token
    overlap guess."""
    assert 'haReadinessUnitsByType("cp_vsx_cluster", "checkpoint")' in APP
    assert "clusterNameSource: \"ha_readiness_group\"" in APP
