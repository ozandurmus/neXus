"""SecurityExpert -- OP.0b S5, Check Point preflight command battery.

Contract: `docs/history/phase/OP_0B_1_COMMAND_GATE_PACKAGE.md` (status:
APPROVED (2026-09-03) -- SCOPED PER THE PO OVERRIDES) -- "Minimum battery --
PO-frozen for implementation", "Retry -- PO override", "B1 session -- PO
clarification", "Approval record".

Fixed, internal, typed command battery for the Check Point dedicated
preflight collector (`checkpoint/preflight_collector.py`). This module is
**not** a generic command runner: every command text below is a hardcoded
literal, never built from caller/browser/config input (task S5 §8/§9). It
also carries the deterministic version/platform dispatch for `A6`/`A8` --
evidence-based, decided from already-collected evidence, never a
failure-driven fallback (task §3/§13; gate "Retry -- PO override").

Absent by construction, never scheduled by anything in this module or its
caller: `A9` (configured recovery/preemption source -- `DEFERRED_UNKNOWN`),
`A10` (`cphaprob state`), `A11` (`cplic print` / `cpstat os`) -- both
`OPTIONAL_APPROVED` but PO-withheld from this slice -- and every rejected
mutating command from the gate's "Rejected mutating operations" table.
"""
from __future__ import annotations

import re
from enum import Enum

from configuration.checkpoint_config_probe import EXPERT_READ_ONLY_COMMANDS

__all__ = [
    "CPPreflightRead",
    "COMMAND_TEXT",
    "FORBIDDEN_COMMAND_MARKERS",
    "resolve_a6_form",
    "resolve_a8_form",
    "build_member_schedule",
    "assert_battery_excludes_forbidden_commands",
]


class CPPreflightRead(str, Enum):
    """The entire PO-approved Check Point preflight read battery (gate
    "Minimum battery -- PO-frozen for implementation"). No other command ID
    exists in this enum."""

    A1_HOSTNAME = "A1_hostname"
    A2_VERSION = "A2_version"
    A3_CPHAPROB_STAT = "A3_cphaprob_stat"
    A4_LINK_IF = "A4_cphaprob_a_if"
    A5_PNOTE_LIST = "A5_cphaprob_ia_list"
    A6_SYNCSTAT = "A6_cphaprob_syncstat"
    A6_PSTAT = "A6_fw_ctl_pstat"
    A7_FW_STAT = "A7_fw_stat"
    A8_CLISH_FAILOVER = "A8_clish_show_cluster_failover"
    A8_EXPERT_FAILOVER = "A8_cphaprob_show_failover"
    B1_VSX_STAT = "B1_vsx_stat_v"


#: Fixed command text per read -- literal only, never interpolated from
#: caller/browser/config input. `A1`/`A2` reuse the exact allow-listed
#: strings the repository's own probe primitive already issues
#: (`configuration.checkpoint_config_probe.EXPERT_READ_ONLY_COMMANDS`) so
#: this module does not fork a second copy of that text (task §19).
COMMAND_TEXT: dict[CPPreflightRead, str] = {
    CPPreflightRead.A1_HOSTNAME: EXPERT_READ_ONLY_COMMANDS["hostname"],
    CPPreflightRead.A2_VERSION: EXPERT_READ_ONLY_COMMANDS["version"],
    CPPreflightRead.A3_CPHAPROB_STAT: "cphaprob stat",
    CPPreflightRead.A4_LINK_IF: "cphaprob -a if",
    CPPreflightRead.A5_PNOTE_LIST: "cphaprob -ia list",
    CPPreflightRead.A6_SYNCSTAT: "cphaprob syncstat",
    CPPreflightRead.A6_PSTAT: "fw ctl pstat",
    CPPreflightRead.A7_FW_STAT: "fw stat",
    CPPreflightRead.A8_CLISH_FAILOVER: "clish -c 'show cluster failover'",
    CPPreflightRead.A8_EXPERT_FAILOVER: "cphaprob show_failover",
    CPPreflightRead.B1_VSX_STAT: "vsx stat -v",
}

#: Text markers that must never appear anywhere in `COMMAND_TEXT` -- `A9`/
#: `A10`/`A11`, every rejected mutating operation, the reset form of `A8`,
#: and any history-depth flag. Deliberately checked as a standing invariant
#: (task §17/§27 tests 3-6, 14, 15), not merely by the enum's own contents.
FORBIDDEN_COMMAND_MARKERS: tuple[str, ...] = (
    "cphaprob state",              # A10 -- not "cphaprob stat" (A3, approved)
    "cplic print",                 # A11
    "cpstat os",                   # A11
    "register",                    # pnote register/unregister (mutating)
    "unregister",
    "reset history",               # A8 mutating reset form
    "-d",                          # cphaprob -d ... register/unregister family
    "fw ctl set int vsid",         # mutating kernel-parameter set
    "clusterxl_admin",             # CLASS 2 -- operational state change
    "cphaprob -l list",            # superseded, never used (only -ia list)
    "sync-to-remote",              # PAN CLASS 2, carried-forward reject list
    "suspend",                     # PAN CLASS 2 state suspend/functional
    "-history",                    # any history-depth style flag
    "--history",
)


def assert_battery_excludes_forbidden_commands() -> None:
    """Deterministic guard (task §17): raises if any battery command text
    contains a forbidden marker. Safe to call at import time or from a test."""
    for read, text in COMMAND_TEXT.items():
        lowered = text.lower()
        for marker in FORBIDDEN_COMMAND_MARKERS:
            if marker in lowered:
                raise AssertionError(
                    f"forbidden command marker {marker!r} found in {read.value} text {text!r}"
                )


assert_battery_excludes_forbidden_commands()


# --- A6/A8 evidence-based dispatch (never failure-driven) -------------------

_R80_20 = (80, 20)
_VERSION_RE = re.compile(r"^R(\d{2})(?:\.(\d{2}))?(?:\.\d+)?$", re.IGNORECASE)


def _parse_version_tuple(sw_version: str | None) -> tuple[int, int] | None:
    if not sw_version:
        return None
    match = _VERSION_RE.match(sw_version.strip())
    if not match:
        return None
    major = int(match.group(1))
    minor = int(match.group(2)) if match.group(2) else 0
    return (major, minor)


def resolve_a6_form(sw_version: str | None) -> CPPreflightRead | None:
    """Choose the A6 form from already-collected version evidence (`A2`)
    only -- `None` means the version could not be established, so neither
    form is scheduled (`CAPABILITY_GAP`, not a fallback attempt). Per gate
    CP-A6: `cphaprob syncstat` R80.20+; `fw ctl pstat` before R80.20."""
    parsed = _parse_version_tuple(sw_version)
    if parsed is None:
        return None
    return CPPreflightRead.A6_SYNCSTAT if parsed >= _R80_20 else CPPreflightRead.A6_PSTAT


_EMBEDDED_PLATFORM_FAMILY = "gaia_embedded"
_ENTERPRISE_PLATFORM_FAMILIES = frozenset({"gaia"})


def resolve_a8_form(platform_family: str | None) -> CPPreflightRead | None:
    """Choose the A8 form from already-collected platform classification
    only (`configuration.checkpoint_config_collector._classify_platform`,
    itself derived from `A2`'s already-collected version text) -- `None`
    means platform evidence is insufficient, so neither form is scheduled.
    Per gate CP-A8: Clish `show cluster failover` for enterprise Gaia;
    Expert `cphaprob show_failover` for Spark/Gaia Embedded."""
    if platform_family == _EMBEDDED_PLATFORM_FAMILY:
        return CPPreflightRead.A8_EXPERT_FAILOVER
    if platform_family in _ENTERPRISE_PLATFORM_FAMILIES:
        return CPPreflightRead.A8_CLISH_FAILOVER
    return None


def build_member_schedule(
    *, is_vsx: bool, a6_form: CPPreflightRead | None, a8_form: CPPreflightRead | None,
) -> tuple[CPPreflightRead, ...]:
    """Deterministic, bounded read schedule for one physical member.

    Fixed order: `A1`, `A2`, `A3`, `A4`, `A5`, then the resolved `A6` form
    (if any), `A7`, then the resolved `A8` form (if any), then `B1` only
    when `is_vsx`. Maximum length 9 (VSX) / 8 (non-VSX) -- matches the gate's
    per-member ceiling exactly (`<=18`/`<=16` required invocations per
    selected pair, task §5/§27 tests 31/32). `B1` never adds a session of
    its own -- it is scheduled into the same per-member battery the caller
    executes over one already-open session (task §16/§27 test 8).
    """
    if a6_form is not None and a6_form not in (CPPreflightRead.A6_SYNCSTAT, CPPreflightRead.A6_PSTAT):
        raise ValueError(f"a6_form must be an A6 read or None, got {a6_form!r}")
    if a8_form is not None and a8_form not in (CPPreflightRead.A8_CLISH_FAILOVER, CPPreflightRead.A8_EXPERT_FAILOVER):
        raise ValueError(f"a8_form must be an A8 read or None, got {a8_form!r}")

    schedule: list[CPPreflightRead] = [
        CPPreflightRead.A1_HOSTNAME,
        CPPreflightRead.A2_VERSION,
        CPPreflightRead.A3_CPHAPROB_STAT,
        CPPreflightRead.A4_LINK_IF,
        CPPreflightRead.A5_PNOTE_LIST,
    ]
    if a6_form is not None:
        schedule.append(a6_form)
    schedule.append(CPPreflightRead.A7_FW_STAT)
    if a8_form is not None:
        schedule.append(a8_form)
    if is_vsx:
        schedule.append(CPPreflightRead.B1_VSX_STAT)
    return tuple(schedule)
