from pathlib import Path
from types import SimpleNamespace

from panorama import panorama_runtime_runner as runner
import pytest
from utils.html_export import compose_report_script as _composed_report_script

pytestmark = pytest.mark.inventory


ROOT = Path(__file__).resolve().parents[1]
APP = _composed_report_script()
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


def test_panorama_managed_device_discovery_strips_serial_and_falls_back_to_name(monkeypatch):
    # pan_hostname_parser_unification: proves panorama_runtime_runner.get_devices
    # now shares configuration.panorama_config_collector's more defensive serial
    # extraction (via panorama.pan_identity.parse_pan_managed_device_entry) --
    # whitespace-padded <serial> text is stripped, a missing <serial> element
    # falls back to the entry's name attribute, and an entry with neither is
    # dropped, rather than being appended with an empty/whitespace identity.
    payload = b'''<response status="success"><result><devices>
      <entry name="SER1">
        <serial>  SER1  </serial><hostname>  PAN-1  </hostname><connected> YES </connected>
        <ip-address> 192.0.2.10 </ip-address>
      </entry>
      <entry name="SER2">
        <hostname>PAN-2</hostname><connected>no</connected>
      </entry>
      <entry>
        <hostname>ORPHAN</hostname>
      </entry>
    </devices></result></response>'''

    monkeypatch.setattr(runner.requests, "get", lambda *args, **kwargs: FakeResponse(payload))
    devices = runner.get_devices("https://panorama.example", "secret")

    assert devices == [
        {
            "serial": "SER1",
            "hostname": "PAN-1",
            "connected": "yes",
            "management_ip": "192.0.2.10",
        },
        {
            "serial": "SER2",
            "hostname": "PAN-2",
            "connected": "no",
            "management_ip": None,
        },
    ]


def test_ui_propagates_member_management_ip_to_cluster_and_vsys():
    assert "managementIp: safe(item.management_ip" in APP
    assert "base.memberManagement = memberEntries" in APP
    assert "memberManagement: [...(parent.memberManagement || [])]" in APP
    assert 'id="detailManagement"' in HTML
    assert 'class="management-chip"' in APP
