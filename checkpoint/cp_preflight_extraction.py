"""SecurityExpert -- OP.0b S5, Check Point preflight command extraction.

Contract: `docs/history/phase/OP_0B_1_COMMAND_GATE_PACKAGE.md` (status:
APPROVED (2026-09-03) -- SCOPED PER THE PO OVERRIDES) -- per-command gate
records CP-A4..CP-A8, CP-B1.

Pure, in-memory parsers for the newly-approved `S5` command battery. Same
discipline as `checkpoint/cp_preflight_projection.py` (S3): no I/O, no
device/network access, no readiness verdict, no PASS/FAIL inference. Each
function accepts already-collected stdout text and returns a small,
normalized, safe dict -- short classification strings/booleans/bounded
integers only, never a raw buffer passed through. The caller (the S5
collector) must discard the raw stdout immediately after calling these
functions; nothing in this module persists it (raw-evidence law).

Unrecognized/unexpected vendor text always yields ``None``/"not observed" in
the returned dict -- never a guessed classification (fail-closed per
`AGENTS.md` "UNKNOWN / fail-closed law" and this gate's own per-row
"Unsupported semantics" entries).
"""
from __future__ import annotations

import re
from typing import Any

__all__ = [
    "parse_cphaprob_a_if",
    "parse_cphaprob_ia_list",
    "parse_cp_sync_status",
    "parse_fw_stat_policy",
    "parse_cp_failover_history",
    "parse_vsx_stat_v",
]

#: A count above this is almost certainly a parse error against unexpected
#: output shape, not a real device value -- fail closed rather than retain
#: an implausible number (mirrors the label-length guard in `preflight_model`).
_MAX_SAFE_COUNT = 10_000


def _clamp_count(value: int) -> int | None:
    return value if 0 <= value <= _MAX_SAFE_COUNT else None


# --- CP-A4: `cphaprob -a if` -- link/interface health -----------------------

_IF_STATE_RE = re.compile(r"^\s*\S+\s+(UP|DOWN)\b", re.IGNORECASE)


def parse_cphaprob_a_if(stdout: str | None) -> dict[str, Any]:
    """Minimum safe link-health evidence: whether any listed interface is
    reporting `Down`, and how many rows were observed. No interface name or
    IP is retained -- the caller receives an up/down classification and a
    bounded count only (gate CP-A4 "Safe retained fields")."""
    text = str(stdout or "")
    states: list[str] = []
    for line in text.splitlines():
        match = _IF_STATE_RE.match(line)
        if match:
            states.append(match.group(1).strip().lower())
    if not states:
        return {"observed": False, "any_down": None, "interface_count": None}
    return {
        "observed": True,
        "any_down": any(state == "down" for state in states),
        "interface_count": _clamp_count(len(states)),
    }


# --- CP-A5: `cphaprob -ia list` -- critical device (pnote) enumeration -----

_PNOTE_STATE_RE = re.compile(r"(?im)^\s*current\s+state\s*:\s*(\S[^\r\n]*)$")


def parse_cphaprob_ia_list(stdout: str | None) -> dict[str, Any]:
    """Minimum safe pnote evidence: problem/no-problem per registered
    critical device, aggregated to a count and a boolean -- never raw device
    names (gate CP-A5 "Safe retained fields"; frozen contract D-V6)."""
    text = str(stdout or "")
    states = _PNOTE_STATE_RE.findall(text)
    if not states:
        return {"observed": False, "device_count": None, "any_problem": None}
    problem_count = sum(1 for state in states if state.strip().lower().startswith("problem"))
    return {
        "observed": True,
        "device_count": _clamp_count(len(states)),
        "any_problem": problem_count > 0,
    }


# --- CP-A6: `cphaprob syncstat` / `fw ctl pstat` -- state sync status ------

_SYNC_STATUS_RE = re.compile(r"(?im)^\s*sync\s+status\s*:\s*(\S[^\r\n]*)$")
_SYNC_OK_TOKENS = {"ok"}
_SYNC_NOT_OK_TOKENS = {
    "lost", "not synchronized", "never been synchronized", "collision",
}


def parse_cp_sync_status(stdout: str | None) -> dict[str, Any]:
    """Minimum safe state-sync evidence for either approved A6 form. The
    field vocabulary beyond `Sync Status: OK`/a small known-bad set is
    `UNKNOWN` by the gate's own admission (CP-A6 "Unsupported semantics") --
    any token outside the two known sets fails closed to `status=None`."""
    text = str(stdout or "")
    match = _SYNC_STATUS_RE.search(text)
    if not match:
        return {"observed": False, "status": None}
    token = match.group(1).strip().lower()
    if token in _SYNC_OK_TOKENS:
        return {"observed": True, "status": "ok"}
    if token in _SYNC_NOT_OK_TOKENS or any(bad in token for bad in _SYNC_NOT_OK_TOKENS):
        return {"observed": True, "status": "not_ok"}
    # Recognized command output, unrecognized status vocabulary: observed but
    # fails closed to no classification, never inferred healthy.
    return {"observed": True, "status": None}


# --- CP-A7: `fw stat` -- installed policy identity -------------------------

_POLICY_NAME_RE = re.compile(r"(?im)^\s*policy\s*name\s*:\s*(\S[^\r\n]*)$")


def parse_fw_stat_policy(stdout: str | None) -> dict[str, Any]:
    """Minimum safe policy-parity evidence: the installed policy name as an
    opaque comparison token only, never displayed raw (gate CP-A7 "Safe
    retained fields"). Returns the raw parsed name only transiently -- the
    caller (projection layer) is responsible for tokenizing it before it
    reaches a `PreflightFact`; this function itself performs no I/O and
    retains nothing beyond its own return value."""
    text = str(stdout or "")
    match = _POLICY_NAME_RE.search(text)
    if not match:
        return {"observed": False, "policy_name": None}
    name = match.group(1).strip()
    return {"observed": True, "policy_name": name or None}


# --- CP-A8: `show cluster failover` / `cphaprob show_failover` -------------

_FAILOVER_COUNT_RE = re.compile(r"(?im)^\s*(?:cluster\s+)?failover\s+count\s*:\s*(\d+)\s*$")
_FAILOVER_REASON_RE = re.compile(r"(?im)^\s*(?:last\s+failover\s+)?reason\s*:\s*(\S[^\r\n]*)$")
_FAILOVER_TIME_RE = re.compile(r"(?im)^\s*last\s+failover\s+(?:event|time)\s*:\s*(\S[^\r\n]*)$")

#: Known-safe reason vocabulary only -- never a free-text pass-through (gate
#: CP-A8 "Safe retained fields": "known-safe enum only"). An unrecognized
#: reason string still counts toward `count` but classifies as `None` here.
_FAILOVER_REASON_CLASSES = {
    "interface": "interface_link_down",
    "link down": "interface_link_down",
    "manual": "manual_operator_action",
    "cpstop": "manual_operator_action",
    "policy": "policy_install",
    "install": "policy_install",
    "high load": "high_load",
    "cpu": "high_load",
    "memory": "high_load",
}

_MAX_SAFE_LABEL = 64


def _classify_failover_reason(raw: str | None) -> str | None:
    if not raw:
        return None
    lowered = raw.lower()
    for marker, classification in _FAILOVER_REASON_CLASSES.items():
        if marker in lowered:
            return classification
    return None


def parse_cp_failover_history(stdout: str | None) -> dict[str, Any]:
    """Minimum safe flap/failover-history evidence: aggregate count plus the
    single most recent event's reason class and time -- earlier history in
    the device's own default output is never parsed into retained fields
    (gate CP-A8 "Raw output persistence": "History depth: minimum necessary
    only"). No numeric flap/failure threshold is applied here (`D-F3`)."""
    text = str(stdout or "")
    count_match = _FAILOVER_COUNT_RE.search(text)
    reason_match = _FAILOVER_REASON_RE.search(text)
    time_match = _FAILOVER_TIME_RE.search(text)
    if not count_match and not reason_match and not time_match:
        return {"observed": False, "count": None, "last_reason_class": None, "last_event_time": None}
    count = _clamp_count(int(count_match.group(1))) if count_match else None
    last_event_time = None
    if time_match:
        candidate = time_match.group(1).strip()
        if candidate and len(candidate) <= _MAX_SAFE_LABEL:
            last_event_time = candidate
    return {
        "observed": True,
        "count": count,
        "last_reason_class": _classify_failover_reason(reason_match.group(1) if reason_match else None),
        "last_event_time": last_event_time,
    }


# --- CP-B1: `vsx stat -v` -- VSID enumeration (VSX battery only) ----------

_VSID_ROW_RE = re.compile(r"(?im)^\s*VSID\s+(\d+)\s+\S+\s+(\S+)\s*$")
_VS_STATUS_TOKENS = {"active", "standby", "down"}


def parse_vsx_stat_v(stdout: str | None) -> dict[str, Any]:
    """Minimum safe VS enumeration evidence: VSID + a bounded status enum
    per row. VS *names* are never retained (gate CP-B1 "Safe retained
    fields"). A device-reported `Unknown` status stays unclassified here --
    the projection layer turns that into `FactState.UNKNOWN`, never an
    inferred healthy/unhealthy state (sk178589, gate CP-B1)."""
    text = str(stdout or "")
    rows: list[dict[str, str | None]] = []
    for match in _VSID_ROW_RE.finditer(text):
        vsid, status_token = match.group(1), match.group(2).strip().lower()
        status = status_token if status_token in _VS_STATUS_TOKENS else None
        rows.append({"vsid": vsid, "status": status})
    return {"observed": bool(rows), "vs_rows": rows}
