"""SecurityExpert -- OP.0b S6, Palo Alto preflight command battery.

Contract: `docs/history/phase/OP_0B_1_COMMAND_GATE_PACKAGE.md` (status:
APPROVED (2026-09-03) -- SCOPED PER THE PO OVERRIDES) -- "Minimum battery --
PO-frozen for implementation", "Retry -- PO override", "Approval record".
`P1`/`P2` are the frozen `OP.0b.0` battery (§24 table: `P1` = `show system
info`, direct API, identity gate; `P2` = `show high-availability state`,
direct API (`D-T1`) or Panorama proxy).

Fixed, internal, typed command battery for the Palo Alto dedicated
preflight collector (`panorama/preflight_collector.py`). This module is
**not** a generic command runner: every op-command XML string below is a
hardcoded literal, never built from caller/browser/config input (task S6
§15). No new application-level command retry, no failure-driven fallback
(task §4).

Absent by construction, never scheduled by anything in this module or its
caller: `P3` (`show high-availability all` -- technically `OPTIONAL_APPROVED`
but PO-withheld from this slice), `P5` (`show high-availability
link-monitoring` -- `DEFERRED_UNKNOWN`, exact syntax unconfirmed), and every
rejected mutating PAN operation (`request high-availability state
suspend/functional`, `sync-to-remote`, config commit/write).
"""
from __future__ import annotations

from enum import Enum

__all__ = [
    "PANPreflightRead",
    "COMMAND_TEXT",
    "FORBIDDEN_COMMAND_MARKERS",
    "build_member_schedule",
    "assert_battery_excludes_forbidden_commands",
]


class PANPreflightRead(str, Enum):
    """The entire PO-approved Palo Alto preflight read battery (gate
    "Minimum battery -- PO-frozen for implementation": `P1`, `P2` existing +
    `P4` new = 3 required reads/member). No other command ID exists here."""

    P1_SYSTEM_INFO = "P1_show_system_info"
    P2_HA_STATE = "P2_show_high_availability_state"
    P4_PATH_MONITORING = "P4_show_high_availability_path_monitoring"


#: Fixed op-command XML per read -- literal only, never interpolated from
#: caller/browser/config input. Matches the exact CLI-to-API translation
#: pattern every other op-command in `configuration.panorama_config_collector`
#: already uses (`show system info` -> `get_direct_system_info`; `show
#: high-availability state` -> `get_target_ha_runtime_state`) -- this module
#: does not fork new command semantics, only names the fixed battery.
COMMAND_TEXT: dict[PANPreflightRead, str] = {
    PANPreflightRead.P1_SYSTEM_INFO: "<show><system><info></info></system></show>",
    PANPreflightRead.P2_HA_STATE: "<show><high-availability><state></state></high-availability></show>",
    PANPreflightRead.P4_PATH_MONITORING: "<show><high-availability><path-monitoring></path-monitoring></high-availability></show>",
}

#: Text markers that must never appear anywhere in `COMMAND_TEXT` -- `P3`/
#: `P5`, every rejected mutating PAN operation, and any commit/config-write
#: shape. Checked as a standing invariant (task §12-14/§21 tests 2-5), not
#: merely by the enum's own contents.
FORBIDDEN_COMMAND_MARKERS: tuple[str, ...] = (
    "high-availability><all",           # P3 -- show high-availability all
    "link-monitoring",                  # P5 -- show high-availability link-monitoring
    "suspend",                          # request high-availability state suspend
    "functional",                       # request high-availability state functional
    "sync-to-remote",                   # PAN CLASS 2 mutating sync
    "<request>",                        # any mutating <request> op family
    "<commit",                          # config commit
    "<edit",                            # config write
    "<set>",                            # config write
    "<delete>",                         # config write
    "<rename>",                         # config write
    "<move>",                           # config write
    "<clone>",                          # config write
)


def assert_battery_excludes_forbidden_commands() -> None:
    """Deterministic guard (task §12-14): raises if any battery command text
    contains a forbidden marker. Safe to call at import time or from a test."""
    for read, text in COMMAND_TEXT.items():
        lowered = text.lower()
        for marker in FORBIDDEN_COMMAND_MARKERS:
            if marker in lowered:
                raise AssertionError(
                    f"forbidden command marker {marker!r} found in {read.value} text {text!r}"
                )


assert_battery_excludes_forbidden_commands()


#: Fixed, deterministic per-member read order -- the entire eligible battery,
#: always all three (no dispatch branching exists for PAN, unlike CP's A6/A8;
#: task §21 test 1). Maximum length 3, matching the gate's own per-member
#: ceiling exactly (`<=6` required invocations/selected pair, task §3/§21
#: test 9).
_MEMBER_SCHEDULE: tuple[PANPreflightRead, ...] = (
    PANPreflightRead.P1_SYSTEM_INFO,
    PANPreflightRead.P2_HA_STATE,
    PANPreflightRead.P4_PATH_MONITORING,
)


def build_member_schedule() -> tuple[PANPreflightRead, ...]:
    """Deterministic, bounded read schedule for one physical member --
    identical for every member, no dispatch branching (unlike CP's `A6`/
    `A8`). Always the fixed 3-read battery: `P1`, `P2`, `P4`."""
    return _MEMBER_SCHEDULE
