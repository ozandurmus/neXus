from pathlib import Path
import pytest

pytestmark = pytest.mark.inventory


def test_ui_contains_live_and_old_data_contract():
    app = Path("static/app.js").read_text(encoding="utf-8")
    css = Path("static/style.css").read_text(encoding="utf-8")
    assert "inventory_status" in app
    assert 'return "LIVE"' in app
    assert 'return "OLD DATA"' in app
    assert "lastSuccessfulCollection" in app
    assert ".inventory-health.live" in css
    assert ".inventory-health.stale" in css
