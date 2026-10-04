"""Shared PAN device identity normalization (OP.0a.P7 contract, defect 2)
and managed-device-discovery XML parsing (backlog `pan_hostname_parser_unification`).

`panorama/panorama_runtime_runner.py` and `configuration/panorama_config_collector.py`
each parsed Panorama's managed-device-discovery `<show><devices><all/></devices></show>`
response independently, to build `unified.json`'s `device` field and
`pan_config_telemetry.json`'s `device`/`entity_id` field respectively. Before
`normalize_pan_hostname` existed the two parses diverged on whitespace handling
(one stripped, one did not), which could silently split one physical device into
two different identity strings if Panorama ever returned a hostname with
incidental whitespace -- invisible to hand-written test fixtures (which
never inject that whitespace) and, unlike a resolvable pairing gap, not even
reported as a fail-closed reason: the device would simply vanish from HA-unit
consideration. `parse_pan_managed_device_entry` closes the rest of the gap
(mirroring CP's `utils.restore_readiness.resolve_entity_id` precedent for a
single-source-of-truth parser): both callers now walk the same `<entry>`
element through this one function instead of two independent XML reads, so
no field on this response shape can drift apart again the way hostname did.

Both callers must go through these functions so the two parses can never
drift apart again.
"""
from __future__ import annotations

from typing import Any


def normalize_pan_hostname(raw_hostname: str | None, *, serial: str | None) -> str:
    """Canonical PAN device identity string: stripped hostname, or the
    device's serial when no hostname text is present. Never returns `None` --
    callers that need an empty/missing signal should check their own inputs
    before calling this."""
    text = str(raw_hostname or "").strip()
    if text:
        return text
    return str(serial or "").strip()


def parse_pan_managed_device_entry(entry: Any) -> dict[str, Any]:
    """Parse one `<devices><entry>` element from Panorama's managed-device-
    discovery `<show><devices><all/></devices></show>` response into the
    superset of fields either caller reads. A caller keeps only the keys it
    needs; `serial` is `""` (never `None`) when neither the `<serial>` child
    nor the entry's `name` attribute (Panorama echoes the serial there too)
    is present, so callers can filter on it directly like `if not serial:
    continue`.
    """
    serial = str(entry.findtext("serial") or entry.get("name") or "").strip()
    return {
        "serial": serial,
        "hostname": normalize_pan_hostname(entry.findtext("hostname"), serial=serial),
        "connected": (entry.findtext("connected") or "").strip().lower(),
        "management_ip": (entry.findtext("ip-address") or "").strip() or None,
        "model": (entry.findtext("model") or "").strip() or None,
        "sw_version": (entry.findtext("sw-version") or "").strip() or None,
        "shared_policy_status": (
            entry.findtext("shared-policy-status")
            or entry.findtext("shared-policy")
            or ""
        ).strip() or None,
        "template_status": (
            entry.findtext("template-status")
            or entry.findtext("template")
            or ""
        ).strip() or None,
        "ha_state": (entry.findtext("ha-state") or "").strip() or None,
    }
