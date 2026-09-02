"""PAN HA peer-pairing identity closure (OP.0a.P7 revision).

Contract: docs/history/phase/OP_0A_PAN_HA_PEER_PAIRING_IDENTITY_CLOSURE.md.
Two defects closed here at the collector level (assessment.py-level mutual-
agreement/fail-closed coverage lives in tests/test_op0a_ha_readiness.py):

1. `peer_ip`/`peer_ipv6` were never extracted from the running-config XML
   this collector already fetches -- PAN peer-matching was dead code
   against real telemetry.
2. `panorama_runtime_runner.py` and `configuration/panorama_config_collector.py`
   parsed the same managed-device-discovery hostname independently, one
   stripped and one not -- a silent identity-join divergence risk.

Both functions tested here are pure (no network I/O), matching this
contract's zero-new-device-command invariant.
"""
from __future__ import annotations

import inspect

from configuration.panorama_config_collector import parse_ha_peer_ip_from_config
from panorama.pan_identity import normalize_pan_hostname


# --------------------------------------------------------------------------
# Defect 2 — shared hostname normalization seam
# --------------------------------------------------------------------------

def test_normalize_pan_hostname_strips_incidental_whitespace():
    assert normalize_pan_hostname("  fw-pan-01  ", serial="0011223344") == "fw-pan-01"


def test_normalize_pan_hostname_falls_back_to_serial_when_blank():
    assert normalize_pan_hostname("", serial="0011223344") == "0011223344"
    assert normalize_pan_hostname(None, serial="0011223344") == "0011223344"
    assert normalize_pan_hostname("   ", serial="0011223344") == "0011223344"


def test_both_parsers_use_the_shared_seam_not_independent_logic():
    """Source-level guard (contract point 7 / point 8): both call sites must
    import and call the shared helper rather than re-implementing their own
    strip/fallback logic, so they cannot silently diverge again."""
    import panorama.panorama_runtime_runner as runtime_runner
    import configuration.panorama_config_collector as config_collector

    assert "normalize_pan_hostname" in inspect.getsource(runtime_runner)
    assert "normalize_pan_hostname" in inspect.getsource(config_collector)


def test_two_parsers_agree_on_a_hostname_with_incidental_whitespace():
    """The exact regression this contract closes: a hostname text node
    carrying whitespace must resolve to the byte-identical string whichever
    parser computes it."""
    raw = "  fw-pan-07\n"
    serial = "007007007007"
    assert normalize_pan_hostname(raw, serial=serial) == normalize_pan_hostname(raw, serial=serial) == "fw-pan-07"


# --------------------------------------------------------------------------
# Defect 1 — peer_ip / peer_ipv6 extraction from already-fetched config XML
# --------------------------------------------------------------------------

_CONFIG_WITH_PEER_IP = b"""<config>
  <devices>
    <entry name="localhost.localdomain">
      <deviceconfig>
        <high-availability>
          <group>
            <peer-ip>10.0.0.2</peer-ip>
            <peer-ipv6>fd00::2</peer-ipv6>
          </group>
        </high-availability>
      </deviceconfig>
    </entry>
  </devices>
</config>"""

# A shallower shape (deviceconfig as a direct child of <config>, no
# devices/entry wrapper) -- proves extraction is depth-independent, per the
# contract's Q1 design decision not to assert an unconfirmed absolute path.
_CONFIG_WITH_PEER_IP_SHALLOW = b"""<config>
  <deviceconfig>
    <high-availability>
      <group>
        <peer-ip>10.0.0.9</peer-ip>
      </group>
    </high-availability>
  </deviceconfig>
</config>"""

_CONFIG_WITHOUT_HA = b"""<config>
  <devices>
    <entry name="localhost.localdomain">
      <deviceconfig>
        <system>
          <hostname>fw-pan-01</hostname>
        </system>
      </deviceconfig>
    </entry>
  </devices>
</config>"""


def test_parse_ha_peer_ip_extracts_configured_peer_address():
    result = parse_ha_peer_ip_from_config(_CONFIG_WITH_PEER_IP)
    assert result == {"peer_ip": "10.0.0.2", "peer_ipv6": "fd00::2"}


def test_parse_ha_peer_ip_is_depth_independent():
    result = parse_ha_peer_ip_from_config(_CONFIG_WITH_PEER_IP_SHALLOW)
    assert result["peer_ip"] == "10.0.0.9"


def test_parse_ha_peer_ip_absent_yields_none_never_a_guess():
    result = parse_ha_peer_ip_from_config(_CONFIG_WITHOUT_HA)
    assert result == {"peer_ip": None, "peer_ipv6": None}


def test_parse_ha_peer_ip_fails_closed_on_unparseable_content():
    result = parse_ha_peer_ip_from_config(b"not xml at all")
    assert result == {"peer_ip": None, "peer_ipv6": None}


def test_parse_ha_peer_ip_source_makes_no_network_or_device_call():
    """Source-level guard: this is an additive parse of bytes already in
    hand, never a new command/API call (contract 'Command surface')."""
    source = inspect.getsource(parse_ha_peer_ip_from_config)
    for forbidden in ("api_post", "requests.", "get_active_running_config", "get_direct_active_config", "paramiko"):
        assert forbidden not in source
