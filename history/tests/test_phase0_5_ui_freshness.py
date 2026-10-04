from pathlib import Path
import pytest
from utils.html_export import compose_report_script as _composed_report_script

pytestmark = pytest.mark.inventory


def test_ui_contains_live_and_old_data_contract():
    app = _composed_report_script()
    css = Path("static/style.css").read_text(encoding="utf-8")
    assert "inventory_status" in app
    assert 'return "LIVE"' in app
    assert 'return "OLD DATA"' in app
    assert "lastSuccessfulCollection" in app
    assert ".inventory-health.live" in css
    assert ".inventory-health.stale" in css
