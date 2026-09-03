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

#: `cphaprob -a if`'s status vocabulary. The state token is not reliably the
#: second column: real output annotates the interface (`eth1 (Sync)  UP`),
#: pads a `Interface Name:  Status:` table, and mixes in non-monitored rows.
#: OP.0b S8-A: the position-bound regex above matched nothing on the real
#: pair, so the row scan below looks for the *token*, not a column index.
_IF_STATUS_DOWN = {"down"}
_IF_STATUS_UP = {"up"}
#: Present and deliberately not counted as either: a non-monitored interface
#: is not evidence of a healthy link nor of a failed one (UNKNOWN law).
_IF_STATUS_OTHER = {"non-monitored", "non_monitored", "inactive", "disconnected"}
_IF_STATUS_TOKENS = _IF_STATUS_DOWN | _IF_STATUS_UP | _IF_STATUS_OTHER

#: Lines that carry a status word but are summaries/headers, not interfaces.
_IF_NON_ROW_RE = re.compile(
    r"(?i)^\s*(required|virtual|interface\s+name|status|monitored|cluster)\b.*:\s*\S*\s*$"
)
#: An interface-like first token: `eth1`, `bond1.100`, `Sync`, `Mgmt`, `eth1:1`.
_IF_NAME_RE = re.compile(r"^[A-Za-z][A-Za-z0-9._:-]*$")


def parse_cphaprob_a_if(stdout: str | None) -> dict[str, Any]:
    """Minimum safe link-health evidence: whether any listed interface is
    reporting `Down`, and how many rows were observed. No interface name or
    IP is retained -- the caller receives an up/down classification and a
    bounded count only (gate CP-A4 "Safe retained fields").

    Shape-tolerant by necessity, not by preference: the same real-environment
    lesson as `parse_fw_stat_policy`. A row counts when its first token looks
    like an interface name and *some* later token is a known status word --
    never by column position. Anything outside the known status vocabulary
    fails closed (contributes no row), never a guessed classification."""
    text = str(stdout or "")
    states: list[str] = []
    for line in text.splitlines():
        if not line.strip() or _IF_NON_ROW_RE.match(line):
            continue
        columns = line.split()
        if len(columns) < 2 or not _IF_NAME_RE.match(columns[0]):
            continue
        for token in columns[1:]:
            candidate = token.strip().strip("(),").lower()
            if candidate in _IF_STATUS_TOKENS:
                states.append(candidate)
                break
    if not states:
        # Legacy strictly-second-column shape, kept as a fallback so no
        # previously-parseable output regresses.
        for line in text.splitlines():
            match = _IF_STATE_RE.match(line)
            if match:
                states.append(match.group(1).strip().lower())
    if not states:
        return {"observed": False, "any_down": None, "interface_count": None}
    return {
        "observed": True,
        "any_down": any(state in _IF_STATUS_DOWN for state in states),
        "interface_count": _clamp_count(len(states)),
    }


# --- CP-A5: `cphaprob -ia list` -- critical device (pnote) enumeration -----

_PNOTE_STATE_RE = re.compile(r"(?im)^\s*current\s+state\s*:\s*(\S[^\r\n]*)$")

#: `cphaprob -ia list` also ships as a fixed-width column table on current
#: releases -- the same real-output lesson as `fw stat` and `cphaprob -a if`:
#:
#:     Device Name:        Registration number:  Timeout:  Current state:  ...
#:     Interface Active Check   0    none    OK      1190.4 sec
#:
#: The `Current state:` line regex above cannot match there (the header line
#: does not end at that column), so the table is parsed by locating the
#: `Current state` header column and slicing each following row at it.
_PNOTE_TABLE_HEADER_RE = re.compile(r"(?i)current\s+state\s*:?")
_PNOTE_DEVICE_HEADER_RE = re.compile(r"(?i)device\s+name\s*:?")
#: Known-bad vocabulary. Anything else observed is a state we do not claim to
#: understand -- counted as a device, never asserted healthy (UNKNOWN law).
_PNOTE_PROBLEM_PREFIXES = ("problem", "error", "failed")


def _parse_pnote_table(text: str) -> list[str]:
    """Column-table form: return the `Current state` cell of each data row."""
    lines = [line for line in text.splitlines() if line.strip()]
    for index, line in enumerate(lines):
        state_match = _PNOTE_TABLE_HEADER_RE.search(line)
        if not state_match or not _PNOTE_DEVICE_HEADER_RE.search(line):
            continue
        start = state_match.start()
        # The state cell runs from this header column to the next header's.
        following = [m.start() for m in re.finditer(r"\S+\s*:", line) if m.start() > start]
        end = min(following) if following else len(line)
        states: list[str] = []
        for row in lines[index + 1:]:
            if len(row) <= start:
                continue
            cell = row[start:end].strip()
            if cell:
                states.append(cell)
        return states
    return []


def parse_cphaprob_ia_list(stdout: str | None) -> dict[str, Any]:
    """Minimum safe pnote evidence: problem/no-problem per registered
    critical device, aggregated to a count and a boolean -- never raw device
    names (gate CP-A5 "Safe retained fields"; frozen contract D-V6).

    Both real output shapes are supported: the per-device `Current state:`
    block form and the fixed-width column table. Neither shape is guessed at
    -- an output matching no known shape stays `observed=False`, never an
    inferred healthy state."""
    text = str(stdout or "")
    states = _PNOTE_STATE_RE.findall(text)
    if not states:
        states = _parse_pnote_table(text)
    if not states:
        return {"observed": False, "device_count": None, "any_problem": None}
    problem_count = sum(
        1 for state in states
        if state.strip().lower().startswith(_PNOTE_PROBLEM_PREFIXES)
    )
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

#: `fw stat`'s actual output is a column table, not a `Policy name:` line --
#: OP.0b S8-A real-environment evidence (the pre-existing regex above was
#: written against an assumed shape and matched nothing on a real gateway):
#:
#:     HOST      POLICY           DATE
#:     localhost <policy>         3Sep2026 22:30:59 :  [>iface] [<iface]
#:
#: The header is matched structurally (HOST/POLICY/DATE in order) and the
#: policy is the second whitespace-delimited column of the following row.
#: Both shapes stay supported: no approved command changed, only the set of
#: real outputs this parser recognizes.
_FW_STAT_HEADER_RE = re.compile(r"(?i)^\s*host\s+policy\s+date\s*$")
#: A policy column that is absent/placeholder is *not* an observed policy --
#: fail closed (UNKNOWN law) rather than tokenizing a placeholder.
_FW_STAT_EMPTY_POLICY = {"-", "--", "none", "n/a"}


def parse_fw_stat_policy(stdout: str | None) -> dict[str, Any]:
    """Minimum safe policy-parity evidence: the installed policy name as an
    opaque comparison token only, never displayed raw (gate CP-A7 "Safe
    retained fields"). Returns the raw parsed name only transiently -- the
    caller (projection layer) is responsible for tokenizing it before it
    reaches a `PreflightFact`; this function itself performs no I/O and
    retains nothing beyond its own return value."""
    text = str(stdout or "")
    match = _POLICY_NAME_RE.search(text)
    if match:
        name = match.group(1).strip()
        return {"observed": True, "policy_name": name or None}

    # Real `fw stat` column table (see _FW_STAT_HEADER_RE): take the second
    # column of the first data row after the header. Never the DATE column,
    # never a placeholder.
    lines = [ln for ln in text.splitlines() if ln.strip()]
    for index, line in enumerate(lines):
        if not _FW_STAT_HEADER_RE.match(line):
            continue
        for row in lines[index + 1:]:
            columns = row.split()
            if len(columns) < 2:
                continue
            candidate = columns[1].strip()
            if candidate.lower() in _FW_STAT_EMPTY_POLICY:
                return {"observed": False, "policy_name": None}
            return {"observed": True, "policy_name": candidate or None}
        break
    return {"observed": False, "policy_name": None}


# --- CP-A8: `show cluster failover` / `cphaprob show_failover` -------------

#: The count line's real wording varies by form and release -- `Failover
#: counter`, `Cluster failover count`, `Number of failovers` -- and may carry
#: trailing text after the number. OP.0b S8-A: the previous anchor required
#: exactly "failover count:" at end of line and matched nothing on the real
#: pair, leaving `cp_failover_count` UNKNOWN while A8 itself succeeded.
#: Widened to the known vocabulary only; an unrecognized wording still yields
#: no count rather than a guessed one.
_FAILOVER_COUNT_RE = re.compile(
    r"(?im)^\s*(?:(?:cluster|total)\s+)?"
    r"(?:number\s+of\s+failovers|failover\s+counters?|failover\s+count|failovers)"
    r"\s*(?:since\s+[^:\r\n]*)?[:=]\s*(\d+)\b"
)
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
