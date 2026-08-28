"""0.7.1b — local, file-based compliance control assignment policy.

Which catalogued controls apply to which firewall, and which (control, device)
cells are formally waived, is environment-specific operator state. It lives in
RuntimeRoot (``data/state/control_assignments.json``), never in the source
repository, and mirrors ``utils.inventory_exclusions``: schema-versioned,
fail-closed, no logging of matched values.

The policy file may name real devices (operator-local). The compliance payload
never echoes those names — matching happens in-process and only resolved
control-id sets and counts reach the shareable artifact.
"""
from __future__ import annotations

from dataclasses import dataclass
from datetime import date, datetime
import json
from pathlib import Path
from typing import Any, Iterable

from utils.compliance_catalog import all_subject_control_ids


POLICY_RELATIVE_PATH = Path("state") / "control_assignments.json"
SUPPORTED_SCHEMA_VERSION = 1
DEFAULT_MODE_VALUES = ("all_applicable", "none")

_WILDCARD = "*"
_SPECIFICITY = {"all": 0, "vendor": 1, "group": 2, "device_name": 3}


class ControlAssignmentPolicyError(RuntimeError):
    """Raised when a local control-assignment policy exists but is not safe to use."""


@dataclass(frozen=True)
class _MatchRule:
    device_name: str = ""
    vendor: str = ""
    name_prefix: str = ""

    def matches(self, device_name: str, vendor: str) -> bool:
        dn = device_name.strip().lower()
        vk = vendor.strip().lower()
        if self.device_name and self.device_name != dn:
            return False
        if self.vendor and self.vendor != vk:
            return False
        if self.name_prefix and not dn.startswith(self.name_prefix):
            return False
        return bool(self.device_name or self.vendor or self.name_prefix)


@dataclass(frozen=True)
class _Assignment:
    scope: str                      # all | vendor | group | device_name
    key: str                        # "" for all; vendor key; group name; device name
    include: frozenset[str]
    exclude: frozenset[str]
    include_all: bool
    exclude_all: bool
    order: int


@dataclass(frozen=True)
class _Waiver:
    control_id: str
    device_name: str                # "" == any device
    reason: str
    approver: str
    expires: date | None


@dataclass(frozen=True)
class ControlAssignmentPolicy:
    source: str                     # "missing" | "disabled" | "runtime-policy"
    default_mode: str
    groups: tuple[tuple[str, tuple[_MatchRule, ...]], ...]
    assignments: tuple[_Assignment, ...]
    waivers: tuple[_Waiver, ...]

    @property
    def is_active(self) -> bool:
        return self.source == "runtime-policy"

    @property
    def group_count(self) -> int:
        return len(self.groups)

    @property
    def waiver_count(self) -> int:
        return len(self.waivers)

    def _group_matches(self, name: str, device_name: str, vendor: str) -> bool:
        for group_name, rules in self.groups:
            if group_name == name:
                return any(rule.matches(device_name, vendor) for rule in rules)
        return False

    def _assignment_applies(self, assignment: _Assignment, device_name: str, vendor: str) -> bool:
        if assignment.scope == "all":
            return True
        if assignment.scope == "vendor":
            return assignment.key == vendor.strip().lower()
        if assignment.scope == "device_name":
            return assignment.key == device_name.strip().lower()
        if assignment.scope == "group":
            return self._group_matches(assignment.key, device_name, vendor)
        return False

    def resolve(
        self,
        device_name: str,
        vendor: str,
        applicable_ids: Iterable[str],
    ) -> frozenset[str]:
        """The set of control ids in scope for this device.

        ``all_applicable`` default → start from every applicable id; ``none`` →
        start empty. Matching assignments are then applied least-specific first
        (all → vendor → group → device_name, file order within a tier), each
        adding (``include``) or removing (``exclude``) ids or ``"*"``.
        """
        applicable = {str(i) for i in applicable_ids}
        current = set(applicable) if self.default_mode == "all_applicable" else set()

        if not self.is_active:
            return frozenset(current)

        ordered = sorted(
            (a for a in self.assignments if self._assignment_applies(a, device_name, vendor)),
            key=lambda a: (_SPECIFICITY[a.scope], a.order),
        )
        for assignment in ordered:
            if assignment.include_all:
                current |= applicable
            elif assignment.include:
                current |= (assignment.include & applicable)
            if assignment.exclude_all:
                current -= applicable
            elif assignment.exclude:
                current -= assignment.exclude
        return frozenset(current & applicable)

    def waiver_for(
        self,
        control_id: str,
        device_name: str,
        now: datetime,
    ) -> _Waiver | None:
        dn = device_name.strip().lower()
        today = now.date()
        for waiver in self.waivers:
            if waiver.control_id != control_id:
                continue
            if waiver.device_name and waiver.device_name != dn:
                continue
            if waiver.expires is not None and today > waiver.expires:
                continue
            return waiver
        return None


def policy_path(data_root: Path) -> Path:
    return Path(data_root) / POLICY_RELATIVE_PATH


def _fail(message: str) -> "ControlAssignmentPolicyError":
    return ControlAssignmentPolicyError(message)


def _str(value: object, field: str) -> str:
    if not isinstance(value, str) or not value.strip():
        raise _fail(f"control-assignment {field} must be a non-empty string")
    text = value.strip()
    if len(text) > 255 or any(ch in text for ch in ("\x00", "\r", "\n")):
        raise _fail(f"control-assignment {field} contains unsupported characters")
    return text


def _id_list(value: object, field: str, known: frozenset[str]) -> tuple[frozenset[str], bool]:
    if value is None:
        return frozenset(), False
    if not isinstance(value, list):
        raise _fail(f"control-assignment {field} must be a list")
    ids: set[str] = set()
    take_all = False
    for item in value:
        token = _str(item, f"{field} entry")
        if token == _WILDCARD:
            take_all = True
            continue
        if token not in known:
            raise _fail(f"control-assignment {field} references unknown control id '{token}'")
        ids.add(token)
    return frozenset(ids), take_all


def _match_rule(raw: object) -> _MatchRule:
    if not isinstance(raw, dict):
        raise _fail("control-assignment group match entry must be an object")
    device_name = str(raw.get("device_name") or "").strip().lower()
    vendor = str(raw.get("vendor") or "").strip().lower()
    name_prefix = str(raw.get("name_prefix") or "").strip().lower()
    if not (device_name or vendor or name_prefix):
        raise _fail("control-assignment group match entry needs device_name, vendor or name_prefix")
    return _MatchRule(device_name=device_name, vendor=vendor, name_prefix=name_prefix)


def _expires(raw: object) -> date | None:
    if raw is None or raw == "":
        return None
    if not isinstance(raw, str):
        raise _fail("control-assignment waiver expires must be an ISO date string (YYYY-MM-DD)")
    try:
        return datetime.strptime(raw.strip(), "%Y-%m-%d").date()
    except ValueError as exc:
        raise _fail("control-assignment waiver expires is not a valid YYYY-MM-DD date") from exc


def load_control_assignments(data_root: Path) -> ControlAssignmentPolicy:
    """Load the local-only policy without logging or returning matched values."""
    known = all_subject_control_ids()
    empty = ControlAssignmentPolicy(
        source="missing", default_mode="all_applicable",
        groups=(), assignments=(), waivers=(),
    )
    path = policy_path(data_root)
    if not path.exists():
        return empty
    try:
        raw = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise _fail("control-assignment policy cannot be read safely") from exc

    if not isinstance(raw, dict) or raw.get("version") != SUPPORTED_SCHEMA_VERSION:
        raise _fail("control-assignment policy has an unsupported schema version")
    if raw.get("enabled", True) is False:
        return ControlAssignmentPolicy(
            source="disabled", default_mode="all_applicable",
            groups=(), assignments=(), waivers=(),
        )

    default_mode = str(raw.get("default_mode") or "all_applicable").strip().lower()
    if default_mode not in DEFAULT_MODE_VALUES:
        raise _fail(f"control-assignment default_mode must be one of {DEFAULT_MODE_VALUES}")

    groups_raw = raw.get("groups", {})
    if not isinstance(groups_raw, dict):
        raise _fail("control-assignment groups must be an object")
    groups: list[tuple[str, tuple[_MatchRule, ...]]] = []
    for name, body in groups_raw.items():
        group_name = _str(name, "group name").lower()
        body = body if isinstance(body, dict) else {}
        match_raw = body.get("match", [])
        if not isinstance(match_raw, list) or not match_raw:
            raise _fail(f"control-assignment group '{group_name}' needs a non-empty match list")
        groups.append((group_name, tuple(_match_rule(rule) for rule in match_raw)))
    group_names = {g[0] for g in groups}

    assignments_raw = raw.get("assignments", [])
    if not isinstance(assignments_raw, list):
        raise _fail("control-assignment assignments must be a list")
    assignments: list[_Assignment] = []
    for order, row in enumerate(assignments_raw):
        if not isinstance(row, dict):
            raise _fail("control-assignment assignment entry must be an object")
        target = row.get("target")
        if not isinstance(target, dict) or len(target) != 1:
            raise _fail("control-assignment assignment target must be a single-key object")
        (tkey, tval), = target.items()
        if tkey == "all":
            scope, key = "all", ""
        elif tkey in ("vendor", "group", "device_name"):
            scope = tkey
            key = _str(tval, f"target {tkey}").lower()
            if scope == "group" and key not in group_names:
                raise _fail(f"control-assignment assignment references unknown group '{key}'")
        else:
            raise _fail("control-assignment assignment target key must be all/vendor/group/device_name")
        include, include_all = _id_list(row.get("include"), "assignment include", known)
        exclude, exclude_all = _id_list(row.get("exclude"), "assignment exclude", known)
        if not (include or include_all or exclude or exclude_all):
            raise _fail("control-assignment assignment must set include and/or exclude")
        assignments.append(_Assignment(
            scope=scope, key=key, include=include, exclude=exclude,
            include_all=include_all, exclude_all=exclude_all, order=order,
        ))

    waivers_raw = raw.get("waivers", [])
    if not isinstance(waivers_raw, list):
        raise _fail("control-assignment waivers must be a list")
    waivers: list[_Waiver] = []
    for row in waivers_raw:
        if not isinstance(row, dict):
            raise _fail("control-assignment waiver entry must be an object")
        control_id = _str(row.get("control_id"), "waiver control_id")
        if control_id not in known:
            raise _fail(f"control-assignment waiver references unknown control id '{control_id}'")
        device_name = str(row.get("device_name") or "").strip().lower()
        waivers.append(_Waiver(
            control_id=control_id,
            device_name=device_name if device_name != _WILDCARD else "",
            reason=_str(row.get("reason"), "waiver reason"),
            approver=_str(row.get("approver"), "waiver approver"),
            expires=_expires(row.get("expires")),
        ))

    return ControlAssignmentPolicy(
        source="runtime-policy",
        default_mode=default_mode,
        groups=tuple(groups),
        assignments=tuple(assignments),
        waivers=tuple(waivers),
    )
