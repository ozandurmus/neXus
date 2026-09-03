"""SecurityExpert -- OP.0b S6, Palo Alto preflight command extraction.

Contract: `docs/history/phase/OP_0B_1_COMMAND_GATE_PACKAGE.md` (status:
APPROVED (2026-09-03) -- SCOPED PER THE PO OVERRIDES) -- per-command gate
record PAN-P4.

Pure, in-memory parser for the one newly-approved PAN command (`P4`, `show
high-availability path-monitoring`). `P1`/`P2` need no new extraction here:
`P1` is `configuration.panorama_config_collector.get_direct_system_info`'s
own already-safe return dict, and `P2` reuses that module's existing
`_parse_pan_ha_preflight_fields`/five-leaf extraction unchanged (task §24 --
"do not create a P2 parser #2").

Same discipline as `checkpoint/cp_preflight_extraction.py` (S5): no I/O, no
device/network access, no readiness verdict. Accepts an already-fetched XML
response root (an `lxml.etree._Element`) and returns a small, normalized,
safe dict -- a bounded count and a per-path up/down enum only. Destination
IPs and full path objects are never retained (gate PAN-P4 "Safe retained
fields"). Unrecognized/unexpected vendor shape always yields `observed:
False` -- never a guessed classification (fail-closed; gate PAN-P4
"Unsupported semantics": "parser must fail closed on any unrecognized state
token").
"""
from __future__ import annotations

from typing import Any

__all__ = ["parse_pan_path_monitoring"]

#: A count above this is almost certainly a parse error against unexpected
#: response shape, not a real monitored-path count -- fail closed rather
#: than retain an implausible number.
_MAX_SAFE_COUNT = 1000

_UP_TOKENS = {"up", "ok", "success"}
_DOWN_TOKENS = {"down", "fail", "failed"}


def parse_pan_path_monitoring(root: Any) -> dict[str, Any]:
    """Minimum safe path-monitoring evidence from an already-fetched `show
    high-availability path-monitoring` response: whether the feature is
    enabled (if the response states so), how many monitored path entries
    were observed, and an aggregate up/down classification. No destination
    IP, full path/group object, or raw XML is retained -- only a bounded
    count and a safe enum per entry contribute to the aggregate.

    `root` is the same `lxml.etree._Element` the caller's already-completed
    API call produced (mirrors `_parse_pan_ha_preflight_fields`'s shape
    for `P2`) -- this function issues no request itself.
    """
    if root is None:
        return {"observed": False, "enabled": None, "path_count": None, "any_down": None}

    enabled_text = root.findtext(".//result/enabled")
    enabled = enabled_text.strip().lower() if enabled_text and enabled_text.strip() else None
    if enabled not in {"yes", "no", None}:
        enabled = None
    elif enabled is not None:
        enabled = enabled == "yes"

    states: list[str] = []
    for node in root.iter():
        tag = str(node.tag or "")
        if tag.rsplit("}", 1)[-1] != "state":
            continue
        text = (node.text or "").strip().lower()
        if text:
            states.append(text)

    if not states:
        return {"observed": bool(enabled is not None), "enabled": enabled, "path_count": None, "any_down": None}

    classified = [
        "up" if token in _UP_TOKENS else "down" if token in _DOWN_TOKENS else None
        for token in states
    ]
    count = len(states)
    if count > _MAX_SAFE_COUNT:
        return {"observed": True, "enabled": enabled, "path_count": None, "any_down": None}

    known = [c for c in classified if c is not None]
    any_down = (any(c == "down" for c in known)) if known else None
    return {
        "observed": True,
        "enabled": enabled,
        "path_count": count,
        "any_down": any_down,
    }
