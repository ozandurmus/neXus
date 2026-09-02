"""Shared PAN device identity normalization (OP.0a.P7 contract, defect 2).

`panorama/panorama_runtime_runner.py` and `configuration/panorama_config_collector.py`
each parse Panorama's managed-device-discovery `<show><devices><all/></devices></show>`
response independently, to build `unified.json`'s `device` field and
`pan_config_telemetry.json`'s `device`/`entity_id` field respectively. Before
this module existed the two parses diverged on whitespace handling (one
stripped, one did not), which could silently split one physical device into
two different identity strings if Panorama ever returned a hostname with
incidental whitespace -- invisible to hand-written test fixtures (which
never inject that whitespace) and, unlike a resolvable pairing gap, not even
reported as a fail-closed reason: the device would simply vanish from HA-unit
consideration.

Both callers must go through this one function so the two parses can never
drift apart again.
"""
from __future__ import annotations


def normalize_pan_hostname(raw_hostname: str | None, *, serial: str | None) -> str:
    """Canonical PAN device identity string: stripped hostname, or the
    device's serial when no hostname text is present. Never returns `None` --
    callers that need an empty/missing signal should check their own inputs
    before calling this."""
    text = str(raw_hostname or "").strip()
    if text:
        return text
    return str(serial or "").strip()
