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


def test_panorama_ha_and_vsys_hierarchy_is_conservative_runtime_inference():
    assert "function panoramaPairCompatible(" in APP
    assert "vsysSimilarity >= 0.75" in APP
    assert "routerSimilarity >= 0.60" in APP
    assert "function panoramaVsysChildren(" in APP
    assert 'entityType: "pan_vsys"' in APP
    assert 'entityType: "pan_cluster"' in APP
