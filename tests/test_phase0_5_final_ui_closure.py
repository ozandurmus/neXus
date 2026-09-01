from pathlib import Path
import pytest
from utils.html_export import compose_report_script as _composed_report_script

pytestmark = pytest.mark.inventory

ROOT = Path(__file__).resolve().parents[1]
APP = _composed_report_script()
CSS = (ROOT / "static" / "style.css").read_text(encoding="utf-8")
TEMPLATE = (ROOT / "templates" / "index.html").read_text(encoding="utf-8")


def test_final_ui_has_persistent_light_dark_theme_toggle():
    assert 'id="themeToggle"' in TEMPLATE
    assert 'html[data-theme="light"]' in CSS
    assert 'function preferredTheme(' in APP
    assert 'function applyTheme(' in APP
    assert 'localStorage.getItem("fbuddy-theme")' in APP
    assert 'localStorage.setItem("fbuddy-theme"' in APP


def test_smartconsole_matrix_is_limited_to_checkpoint_physical_clusters():
    assert 'entry.source === "cp"' in APP
    assert '["cp_cluster", "cp_vsx_cluster"].includes(entry.entityType)' in APP
    assert 'function renderClusterInterfaceMatrix(' in APP


def test_panorama_and_vsx_use_logical_deduplicated_member_rows():
    assert 'function mergeLogicalMemberRows(' in APP
    assert 'function collapseLogicalInterfaces(' in APP
    assert 'function collapseLogicalRoutes(' in APP
    assert 'sharedAcrossMembers' in APP
    assert 'Member Scope' in APP


def test_panorama_vsys_title_includes_virtual_router_context():
    assert 'virtualRouters: routers' in APP
    assert '"VSYS " + vsys + (routerDisplay ? " | " + routerDisplay : "")' in APP


def test_logical_route_view_only_exposes_member_tabs_when_divergence_exists():
    assert 'const divergentLogicalView = Boolean(entry.routeDivergence && members.length >= 2)' in APP
    assert '{id: "logical", label: "Logical"}' in APP
    assert '{id: "diff", label: "Diff only"}' in APP
    assert 'Route comparison' in APP
