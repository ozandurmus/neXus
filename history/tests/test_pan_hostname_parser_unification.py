"""backlog pan_hostname_parser_unification: proves panorama_runtime_runner.get_devices
and panorama_config_collector.get_devices both route through the single shared
panorama.pan_identity.parse_pan_managed_device_entry parser for the
<show><devices><all/></devices></show> response shape, and that the fields
they have in common (serial, hostname, connected, management_ip) agree
byte-for-byte for the same entry -- the one thing that could silently drift
apart again if a third hand-rolled walk were ever added.
"""
from __future__ import annotations

from lxml import etree

from panorama import panorama_runtime_runner as runner
from configuration import panorama_config_collector as collector
from panorama.pan_identity import parse_pan_managed_device_entry
import pytest

pytestmark = pytest.mark.inventory

ENTRY_XML = b'''<entry name="SER1">
  <serial>  SER1  </serial>
  <hostname>  pan-fw-1  </hostname>
  <connected> Yes </connected>
  <ip-address> 192.0.2.10 </ip-address>
  <model>PA-820</model>
  <sw-version>10.2.4</sw-version>
  <shared-policy-status>In Sync</shared-policy-status>
  <template-status>In Sync</template-status>
  <ha-state>active</ha-state>
</entry>'''


def test_shared_parser_used_by_both_call_sites():
    import inspect

    assert "parse_pan_managed_device_entry" in inspect.getsource(runner.get_devices)
    assert "parse_pan_managed_device_entry" in inspect.getsource(collector.get_devices)


def test_shared_parser_output_matches_both_callers_on_a_common_entry():
    entry = etree.fromstring(ENTRY_XML)
    parsed = parse_pan_managed_device_entry(entry)

    class FakeResponse:
        content = (
            b'<response status="success"><result><devices>' + ENTRY_XML + b'</devices></result></response>'
        )

        def raise_for_status(self):
            return None

    class MonkeypatchedRequests:
        @staticmethod
        def get(*args, **kwargs):
            return FakeResponse()

        @staticmethod
        def post(*args, **kwargs):
            return FakeResponse()

    runner_requests, collector_requests = runner.requests, collector.requests
    try:
        runner.requests = MonkeypatchedRequests
        collector.requests = MonkeypatchedRequests

        runtime_devices = runner.get_devices("https://panorama.example", "secret")
        config_devices = collector.get_devices(
            "https://panorama.example", "secret", verify=False, timeout=5
        )
    finally:
        runner.requests = runner_requests
        collector.requests = collector_requests

    assert runtime_devices == [{
        "serial": parsed["serial"],
        "hostname": parsed["hostname"],
        "connected": parsed["connected"],
        "management_ip": parsed["management_ip"],
    }]
    assert config_devices == [parsed]
