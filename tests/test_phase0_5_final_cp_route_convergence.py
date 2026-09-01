from pathlib import Path
import pytest
from utils.html_export import compose_report_script as _composed_report_script

pytestmark = pytest.mark.inventory

ROOT = Path(__file__).resolve().parents[1]
APP = _composed_report_script()


def test_checkpoint_cluster_routes_are_collapsed_to_logical_view():
    assert "base.routes = collapseLogicalRoutes(memberRoutes, members);" in APP
    assert "base.routeDivergence = hasMemberDivergence(base.routes, members);" in APP


def test_checkpoint_vsx_physical_parent_routes_are_collapsed_too():
    assert "parent.routes = collapseLogicalRoutes(parent.routes, parent.members);" in APP
    assert "parent.routeDivergence = hasMemberDivergence(parent.routes, parent.members);" in APP


def test_route_member_tabs_only_appear_for_actual_divergence_for_all_vendors():
    assert 'const divergentLogicalView = Boolean(entry.routeDivergence && members.length >= 2);' in APP
    assert 'if (!divergentLogicalView)' in APP
    assert '{id: "logical", label: "Logical"}' in APP
    assert '{id: "diff", label: "Diff only"}' in APP
    assert 'Gateway routing view' not in APP


def test_checkpoint_route_table_no_longer_forces_member_specific_view():
    assert 'if (cpPhysicalCluster)' not in APP[APP.index("function renderRouteTable(entry)"):APP.index("function switchTab(nextTab)")]
