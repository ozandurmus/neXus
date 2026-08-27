from pathlib import Path


PROJECT_ROOT = Path(__file__).resolve().parents[1]
TEMPLATE = (PROJECT_ROOT / "templates" / "index.html").read_text(encoding="utf-8")
APP = (PROJECT_ROOT / "static" / "app.js").read_text(encoding="utf-8")
STYLE = (PROJECT_ROOT / "static" / "style.css").read_text(encoding="utf-8")


def test_template_keeps_existing_inventory_controls_and_panels():
    required_ids = [
        "globalSearch",
        "vendorFilter",
        "stats",
        "subnetSearch",
        "deviceList",
        "detailTitle",
        "detailSubtitle",
        "detailCounts",
        "interfacesTab",
        "routingTab",
        "interfacesPanel",
        "routingPanel",
        "interfaceSearch",
        "routeSearch",
        "interfaceTable",
        "routeTable",
    ]

    for element_id in required_ids:
        assert f'id="{element_id}"' in TEMPLATE


def test_frontend_keeps_current_normalization_filter_sort_and_vsx_dedup_features():
    required_functions = [
        "function normalizedSource(",
        "function calculateNetwork(",
        "function normalizeInterfaceRow(",
        "function flattenInterfaces(",
        "function normalizeRouteRow(",
        "function flattenRoutes(",
        "function collectPanoramaVirtualRouters(",
        "function deduplicateInventory(",
        "function filteredInventory(",
        "function renderDeviceList(",
        "function renderSelected(",
        "function renderInterfaceTable(",
        "function renderRouteTable(",
        "function switchTab(",
    ]

    for function_signature in required_functions:
        assert function_signature in APP


def test_frontend_keeps_legacy_panorama_and_both_route_field_names():
    assert "vr_data" in APP
    assert "item.routes" in APP
    assert "item.routing" in APP


def test_dark_theme_and_sidebar_scroll_contract_remain_present():
    assert "overflow" in STYLE
    assert ".sidebar" in STYLE
    assert "#111" in STYLE or "#0" in STYLE
