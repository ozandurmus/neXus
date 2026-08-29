"""0.7.3 (CE.1) — local, file-based user-authored compliance check pack.

A check is *data*: a name, one or more evidence steps (a bounded selector into
*already-collected* read-only evidence + one assertion), and a verdict mapping.
It matches the expectation → compliant; it does not → not compliant; there is no
evidence to judge → UNKNOWN (never an inferred PASS).

The pack lives in RuntimeRoot (``data/state/compliance_checks.json``), never in
the source repository, and mirrors :mod:`utils.control_assignment` /
:mod:`utils.inventory_exclusions`: schema-versioned, fail-closed, no logging of
matched values. Decisions D1–D16 and the reserved-``remediation`` rule are in
``docs/design/COMPLIANCE_CHECK_ENGINE.md`` §§10–11.
"""
from __future__ import annotations

from dataclasses import dataclass
import re
from pathlib import Path
from typing import Any

from utils.compliance_catalog import SEVERITY_VALUES


POLICY_RELATIVE_PATH = Path("state") / "compliance_checks.json"
SUPPORTED_SCHEMA_VERSION = 1

CHECK_ID_RE = re.compile(r"^x_[a-z0-9_]+$")
MAX_PATTERN_LEN = 512

_FRAMEWORKS = ("CIS", "PCI-DSS", "BDDK")
_MODES = ("enforced", "advisory")
_COMBINE = ("all", "any")
# STATUS_VALUES minus WAIVED (a waiver is applied outside the engine).
_VERDICT_STATUSES = ("PASS", "FINDING", "UNKNOWN", "NOT_APPLICABLE", "PLANNED")

VALID_OPS = frozenset({
    "present", "absent", "equals", "not_equals",
    "matches", "not_match", "any_match", "none_match",
    "gte", "lte", "in", "not_in", "count_gte", "count_lte",
})
_PATTERN_OPS = frozenset({"matches", "not_match", "any_match", "none_match"})
_VALUE_OPS = frozenset({"equals", "not_equals"})
_NUMERIC_OPS = frozenset({"gte", "lte", "count_gte", "count_lte"})
_LIST_OPS = frozenset({"in", "not_in"})

SOURCE_NAMESPACES = frozenset({
    "current_configuration", "unified", "crypto_facts", "alignment",
})

# CE.1 fast-follow: `unified.interfaces` / `unified.routes` are merged-inventory
# collections whose rows carry network identity (interface addresses / names,
# route targets). They are limited to operators that assert on shape only and
# never echo an observed value into the payload; the engine renders a count-only
# `observed` for them (see utils.compliance_check_engine).
_INVENTORY_COLLECTION_KEYS = frozenset({"interfaces", "routes"})
_INVENTORY_COLLECTION_OPS = frozenset({"present", "absent", "count_gte", "count_lte"})

_SEGMENT_RE = re.compile(
    r"^([a-z_][a-z0-9_]*)(?:\[([a-z_][a-z0-9_]*)=([^\]]+)\])?$"
)

# Reject the common catastrophic-backtracking shapes: a group that contains an
# unbounded quantifier and is itself quantified, e.g. (a+)+ (a*)* (.*)+ .
_REDOS_RE = re.compile(r"\([^()]*[+*][^()]*\)\s*[+*?]")


class CompliancePackError(RuntimeError):
    """Raised when a local compliance check pack exists but is not safe to use."""


@dataclass(frozen=True)
class CheckStep:
    source: str
    selector: "ParsedSelector"
    select: str
    op: str
    pattern: re.Pattern[str] | None
    value: Any
    values: tuple[str, ...]


@dataclass(frozen=True)
class ComplianceCheck:
    id: str
    title: str
    rationale: str
    severity: str
    mode: str
    applies_to: dict[str, tuple[str, ...]]
    frameworks: tuple[dict[str, Any], ...]
    steps: tuple[CheckStep, ...]
    combine: str
    on_pass: str
    on_fail: str
    on_no_evidence: str

    @property
    def advisory(self) -> bool:
        return self.mode == "advisory"

    def applies_to_subject(self, *, vendor: str, platform_family: str, entity_type: str) -> bool:
        checks = (
            ("vendor", vendor), ("platform_family", platform_family),
            ("entity_type", entity_type),
        )
        for key, actual in checks:
            allowed = self.applies_to.get(key)
            if allowed and str(actual or "").strip().lower() not in allowed:
                return False
        return True


@dataclass(frozen=True)
class CompliancePack:
    pack_id: str
    pack_version: str
    source: str            # "missing" | "disabled" | "runtime_file"
    enabled: bool
    checks: tuple[ComplianceCheck, ...]

    @property
    def is_active(self) -> bool:
        return self.source == "runtime_file" and self.enabled

    def check_ids(self) -> frozenset[str]:
        return frozenset(c.id for c in self.checks)

    @property
    def advisory_count(self) -> int:
        return sum(1 for c in self.checks if c.advisory)


# --- selector grammar (D13) ------------------------------------------------

@dataclass(frozen=True)
class _Segment:
    key: str
    filter_attr: str
    filter_value: str


@dataclass(frozen=True)
class ParsedSelector:
    namespace: str
    segments: tuple[_Segment, ...]


def parse_selector(text: object) -> ParsedSelector:
    if not isinstance(text, str) or not text.strip():
        raise CompliancePackError("check step source must be a non-empty string")
    parts = text.strip().split(".")
    namespace = parts[0]
    if namespace not in SOURCE_NAMESPACES:
        raise CompliancePackError(
            f"check step source namespace '{namespace}' is not one of {sorted(SOURCE_NAMESPACES)}"
        )
    segments: list[_Segment] = []
    for raw in parts[1:]:
        m = _SEGMENT_RE.match(raw)
        if not m:
            raise CompliancePackError(f"check step source segment '{raw}' is malformed")
        key, fattr, fval = m.group(1), m.group(2) or "", m.group(3) or ""
        segments.append(_Segment(key=key, filter_attr=fattr, filter_value=fval))
    return ParsedSelector(namespace=namespace, segments=tuple(segments))


def is_inventory_collection_selector(selector: ParsedSelector) -> bool:
    """True for a ``unified.interfaces`` / ``unified.routes`` selector — the
    merged-inventory collections that carry network identity (CE.1 fast-follow)."""
    return (
        selector.namespace == "unified"
        and bool(selector.segments)
        and selector.segments[0].key in _INVENTORY_COLLECTION_KEYS
    )


# --- validation helpers --------------------------------------------------

def _fail(message: str) -> CompliancePackError:
    return CompliancePackError(message)


def _str(value: object, field: str) -> str:
    if not isinstance(value, str) or not value.strip():
        raise _fail(f"check {field} must be a non-empty string")
    text = value.strip()
    if any(ch in text for ch in ("\x00", "\r", "\n")):
        raise _fail(f"check {field} contains control characters")
    return text


def _str_list(value: object, field: str) -> tuple[str, ...]:
    if value is None:
        return ()
    if not isinstance(value, list) or not all(isinstance(v, str) and v.strip() for v in value):
        raise _fail(f"check {field} must be a list of non-empty strings")
    return tuple(v.strip().lower() for v in value)


def _lint_pattern(pattern: str) -> None:
    if len(pattern) > MAX_PATTERN_LEN:
        raise _fail(f"check step pattern exceeds {MAX_PATTERN_LEN} characters")
    if _REDOS_RE.search(pattern):
        raise _fail("check step pattern has a nested-quantifier shape rejected by the safety linter")
    if pattern.count("*") + pattern.count("+") > 12:
        raise _fail("check step pattern has too many unbounded quantifiers")
    if ".*.*" in pattern.replace(" ", ""):
        raise _fail("check step pattern has an adjacent .*.* shape rejected by the safety linter")
    try:
        re.compile(pattern)
    except re.error as exc:
        raise _fail(f"check step pattern does not compile: {exc}") from exc


def _frameworks(value: object) -> tuple[dict[str, Any], ...]:
    if value is None:
        return ()
    if not isinstance(value, list):
        raise _fail("check frameworks must be a list")
    out: list[dict[str, Any]] = []
    for row in value:
        if not isinstance(row, dict):
            raise _fail("check framework entry must be an object")
        name = str(row.get("framework") or "")
        if name not in _FRAMEWORKS:
            raise _fail(f"check framework '{name}' must be one of {_FRAMEWORKS}")
        reference = _str(row.get("reference"), "framework reference")
        applies = row.get("applies", True)
        if not isinstance(applies, bool):
            raise _fail("check framework 'applies' must be a boolean")
        out.append({"framework": name, "reference": reference, "applies": applies})
    return tuple(out)


def _step(raw: object) -> CheckStep:
    if not isinstance(raw, dict):
        raise _fail("check evidence step must be an object")
    source = _str(raw.get("source"), "step source")
    selector = parse_selector(source)
    select = str(raw.get("select") or "").strip()
    assertion = raw.get("assert")
    if not isinstance(assertion, dict):
        raise _fail("check step 'assert' must be an object")
    op = str(assertion.get("op") or "")
    if op not in VALID_OPS:
        raise _fail(f"check step assert op '{op}' is not one of {sorted(VALID_OPS)}")

    if is_inventory_collection_selector(selector) and op not in _INVENTORY_COLLECTION_OPS:
        raise _fail(
            f"check step assert op '{op}' is not allowed on "
            f"'unified.{selector.segments[0].key}' — merged-inventory collections carry "
            f"network identity and are limited to {sorted(_INVENTORY_COLLECTION_OPS)}"
        )

    pattern: re.Pattern[str] | None = None
    value: Any = None
    values: tuple[str, ...] = ()

    if op in _PATTERN_OPS:
        raw_pattern = assertion.get("pattern")
        if not isinstance(raw_pattern, str) or not raw_pattern:
            raise _fail(f"check step assert op '{op}' requires a 'pattern' string")
        _lint_pattern(raw_pattern)
        pattern = re.compile(raw_pattern)
    elif op in _NUMERIC_OPS:
        value = assertion.get("value")
        if not isinstance(value, (int, float)) or isinstance(value, bool):
            raise _fail(f"check step assert op '{op}' requires a numeric 'value'")
    elif op in _VALUE_OPS:
        if "value" not in assertion:
            raise _fail(f"check step assert op '{op}' requires a 'value'")
        value = assertion.get("value")
    elif op in _LIST_OPS:
        vs = assertion.get("values")
        if not isinstance(vs, list) or not vs:
            raise _fail(f"check step assert op '{op}' requires a non-empty 'values' list")
        values = tuple(str(v) for v in vs)

    return CheckStep(
        source=source, selector=selector, select=select,
        op=op, pattern=pattern, value=value, values=values,
    )


def _check(raw: object, seen: set[str]) -> ComplianceCheck:
    if not isinstance(raw, dict):
        raise _fail("check entry must be an object")

    if "remediation" in raw:
        raise _fail(
            "check 'remediation' is not supported in CE.1 (reserved for the write-capable "
            "future — see docs/design/COMPLIANCE_CHECK_ENGINE.md §§11-12)"
        )

    check_id = _str(raw.get("id"), "id")
    if not CHECK_ID_RE.match(check_id):
        raise _fail(f"check id '{check_id}' must match ^x_[a-z0-9_]+$ (user checks are x_-prefixed)")
    if check_id in seen:
        raise _fail(f"duplicate check id '{check_id}'")
    seen.add(check_id)

    severity = str(raw.get("severity") or "")
    if severity not in SEVERITY_VALUES:
        raise _fail(f"check '{check_id}' severity must be one of {SEVERITY_VALUES}")

    mode = str(raw.get("mode") or "enforced")
    if mode not in _MODES:
        raise _fail(f"check '{check_id}' mode must be one of {_MODES}")

    applies_raw = raw.get("applies_to") or {}
    if not isinstance(applies_raw, dict):
        raise _fail(f"check '{check_id}' applies_to must be an object")
    applies_to = {
        key: _str_list(applies_raw.get(key), f"'{check_id}' applies_to.{key}")
        for key in ("vendor", "platform_family", "entity_type")
        if applies_raw.get(key) is not None
    }

    evidence = raw.get("evidence")
    if not isinstance(evidence, dict):
        raise _fail(f"check '{check_id}' needs an 'evidence' object")
    steps_raw = evidence.get("steps")
    if not isinstance(steps_raw, list) or not steps_raw:
        raise _fail(f"check '{check_id}' needs a non-empty evidence.steps list")
    combine = str(evidence.get("combine") or "all")
    if combine not in _COMBINE:
        raise _fail(f"check '{check_id}' evidence.combine must be one of {_COMBINE}")
    steps = tuple(_step(s) for s in steps_raw)

    verdict = raw.get("verdict") or {}
    if not isinstance(verdict, dict):
        raise _fail(f"check '{check_id}' verdict must be an object")

    def _status(key: str, default: str) -> str:
        candidate = str(verdict.get(key) or default)
        if candidate not in _VERDICT_STATUSES:
            raise _fail(f"check '{check_id}' verdict.{key} must be one of {_VERDICT_STATUSES}")
        return candidate

    return ComplianceCheck(
        id=check_id,
        title=_str(raw.get("title"), f"'{check_id}' title"),
        rationale=_str(raw.get("rationale"), f"'{check_id}' rationale"),
        severity=severity,
        mode=mode,
        applies_to=applies_to,
        frameworks=_frameworks(raw.get("frameworks")),
        steps=steps,
        combine=combine,
        on_pass=_status("on_pass", "PASS"),
        on_fail=_status("on_fail", "FINDING"),
        on_no_evidence=_status("on_no_evidence", "UNKNOWN"),
    )


def pack_path(data_root: Path) -> Path:
    return Path(data_root) / POLICY_RELATIVE_PATH


_EMPTY = CompliancePack(
    pack_id="securityexpert.user.local", pack_version="0",
    source="missing", enabled=False, checks=(),
)


def load_compliance_checks(data_root: Path | None) -> CompliancePack:
    """Load the local-only check pack without logging or returning matched values."""
    root = Path(data_root) if data_root is not None else (Path(__file__).resolve().parent.parent / "data")
    path = pack_path(root)
    if not path.exists():
        return _EMPTY

    import json
    try:
        raw = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise _fail("compliance check pack cannot be read safely") from exc

    if not isinstance(raw, dict) or raw.get("version") != SUPPORTED_SCHEMA_VERSION:
        raise _fail("compliance check pack has an unsupported schema version")

    pack_id = _str(raw.get("pack_id"), "pack_id") if raw.get("pack_id") is not None else "securityexpert.user.local"
    pack_version = _str(raw.get("pack_version"), "pack_version") if raw.get("pack_version") is not None else "1"

    if raw.get("enabled", True) is False:
        return CompliancePack(
            pack_id=pack_id, pack_version=pack_version,
            source="disabled", enabled=False, checks=(),
        )

    checks_raw = raw.get("checks")
    if not isinstance(checks_raw, list):
        raise _fail("compliance check pack 'checks' must be a list")
    seen: set[str] = set()
    checks = tuple(_check(c, seen) for c in checks_raw)

    return CompliancePack(
        pack_id=pack_id, pack_version=pack_version,
        source="runtime_file", enabled=True, checks=checks,
    )
