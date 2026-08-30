from pathlib import Path
from types import SimpleNamespace

from panorama import panorama_runtime_runner as runner
import pytest

pytestmark = pytest.mark.inventory


ROOT = Path(__file__).resolve().parents[1]
APP = (ROOT / "static" / "app.js").read_text(encoding="utf-8")
HTML = (ROOT / "templates" / "index.html").read_text(encoding="utf-8")


class FakeResponse:
    def __init__(self, content: bytes):
        self.content = content

    def raise_for_status(self):
        return None


def test_panorama_managed_device_discovery_keeps_management_ip(monkeypatch):
    payload = b'''<response status="success"><result><devices>
      <entry name="SER1">
        <serial>SER1</serial><hostname>PAN-1</hostname><connected>yes</connected>
        <ip-address>192.0.2.10</ip-address>
      </entry>
    </devices></result></response>'''

    monkeypatch.setattr(runner.requests, "get", lambda *args, **kwargs: FakeResponse(payload))
    devices = runner.get_devices("https://panorama.example", "secret")

    assert devices == [{
        "serial": "SER1",
        "hostname": "PAN-1",
        "connected": "yes",
        "management_ip": "192.0.2.10",
    }]


def test_ui_propagates_member_management_ip_to_cluster_and_vsys():
    assert "managementIp: safe(item.management_ip" in APP
    assert "base.memberManagement = memberEntries" in APP
    assert "memberManagement: [...(parent.memberManagement || [])]" in APP
    assert 'id="detailManagement"' in HTML
    assert 'class="management-chip"' in APP
