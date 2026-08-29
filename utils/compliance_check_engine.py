"""0.7.3 (CE.1) — evaluate a user-authored compliance check against a subject's
*already-collected* evidence.

Pure functions. ``evaluate_check`` returns ``(status, summary, coverage,
step_details)``; :mod:`utils.compliance_posture` wraps that with the standard
control shape. Discipline (unchanged from the 0.6.6B / 0.7.1b / 0.7.2 lineage):
no evidence to judge → ``on_no_evidence`` (default ``UNKNOWN``), never an
inferred ``PASS``; a regex that times out or errors → that step is inconclusive,
never a pass.
"""
from __future__ import annotations

import re
from typing import Any

from utils.compliance_check_pack import (
    CheckStep,
    ComplianceCheck,
    ParsedSelector,
    parse_selector,  # re-exported for callers that only import the engine
)

__all__ = [
    "resolve_source", "apply_select", "apply_assertion", "evaluate_check",
    "parse_selector", "redacted_selector",
]

_FILTER_VALUE_RE = re.compile(r"(\[[a-z_][a-z0-9_]*=)[^\]]+\]")


def redacted_selector(source: str) -> str:
    """A structural view of a selector with any ``[attr=value]`` filter value
    blanked — the value could be an operator-supplied device name / IP (D12)."""
    return _FILTER_VALUE_RE.sub(r"\1...]", str(source or ""))

# Best-effort wall-clock guard for user regex evaluation. The `regex` module
# supports timeout= directly; stdlib `re` does not, so there we cap the input
# length fed to the matcher (the pattern is already linted + length-capped at
# pack load).
try:  # pragma: no cover - import shape only
    import regex as _regex  # type: ignore
    _HAVE_REGEX = True
except Exception:  # pragma: no cover
    _regex = None
    _HAVE_REGEX = False

_MATCH_INPUT_CAP = 20_000
_REGEX_TIMEOUT_S = 0.25


def _as_list(value: Any) -> list[Any]:
    if value is None:
        return []
    return value if isinstance(value, list) else [value]


def _as_dict(value: Any) -> dict[str, Any]:
    return value if isinstance(value, dict) else {}


# --- source resolution --------------------------------------------------

def resolve_source(subject_evidence: dict[str, Any], selector: ParsedSelector) -> Any:
    """Walk the namespaced evidence.

    A path that cannot be walked (a missing key, or a ``[attr=value]`` filter
    that matches nothing) resolves to ``None`` — distinct from a value that
    resolves to a genuinely empty list. The engine treats ``None`` as
    "no evidence to judge" for every operator.
    """
    current: Any = _as_dict(subject_evidence).get(selector.namespace)
    for segment in selector.segments:
        if current is None:
            return None
        current = _drill(current, segment)
    return current


def _drill(current: Any, segment: Any) -> Any:
    if isinstance(current, list):
        out: list[Any] = []
        for item in current:
            value = _drill(item, segment)
            if value is None:
                continue
            if isinstance(value, list):
                out.extend(value)
            else:
                out.append(value)
        return out or None
    if isinstance(current, dict):
        value = current.get(segment.key)
        if value is None:
            return None
        if segment.filter_attr and isinstance(value, list):
            filtered = [
                item for item in value
                if str(_as_dict(item).get(segment.filter_attr)) == segment.filter_value
            ]
            return filtered or None
        return value
    return None


def apply_select(value: Any, dotted_key: str) -> Any:
    if not dotted_key:
        return value
    keys = [k for k in dotted_key.split(".") if k]
    current = value
    for key in keys:
        if isinstance(current, list):
            current = [_as_dict(item).get(key) for item in current]
        elif isinstance(current, dict):
            current = current.get(key)
        else:
            return None
    return current


# --- assertions -------------------------------------------------------

def _values(value: Any) -> list[str]:
    return [str(v) for v in _as_list(value) if v is not None and str(v) != ""]


def _numbers(value: Any) -> list[float]:
    out: list[float] = []
    for v in _as_list(value):
        try:
            out.append(float(str(v).strip()))
        except (TypeError, ValueError):
            continue
    return out


def _search(pattern: re.Pattern[str], text: str) -> bool:
    if _HAVE_REGEX:
        try:
            return _regex.search(pattern.pattern, text, timeout=_REGEX_TIMEOUT_S) is not None
        except Exception:
            raise TimeoutError("regex evaluation exceeded its time budget")
    return pattern.search(text[:_MATCH_INPUT_CAP]) is not None


def apply_assertion(value: Any, step: CheckStep) -> bool | None:
    """True / False / None (inconclusive — no evidence to judge).

    ``value is None`` means the selector did not resolve at all → ``None`` for
    every operator (a missing evidence section is never a pass or a fail-by-
    absence). A value that resolved to an empty list *is* judged: ``present`` →
    False, ``absent`` → True, ``count_*`` → 0.
    """
    if value is None:
        return None

    op = step.op
    vals = _values(value)

    if op == "present":
        return len(vals) > 0
    if op == "absent":
        return len(vals) == 0

    if op in ("count_gte", "count_lte"):
        n = len(vals)
        return n >= step.value if op == "count_gte" else n <= step.value

    if not vals:
        return None

    if op in ("matches", "any_match"):
        return any(_search(step.pattern, v) for v in vals)
    if op in ("not_match", "none_match"):
        return not any(_search(step.pattern, v) for v in vals)

    if op == "equals":
        return any(v == str(step.value) for v in vals)
    if op == "not_equals":
        return all(v != str(step.value) for v in vals)
    if op == "in":
        return any(v in step.values for v in vals)
    if op == "not_in":
        return all(v not in step.values for v in vals)

    if op in ("gte", "lte"):
        nums = _numbers(value)
        if not nums:
            return None
        return any(n >= step.value for n in nums) if op == "gte" else any(n <= step.value for n in nums)

    return None  # pragma: no cover - VALID_OPS is exhaustive above


# --- check evaluation ------------------------------------------------

def _describe_expected(step: CheckStep) -> str:
    # D12 — the raw pattern can encode an internal hostname / IP; it stays in the
    # local pack file and never enters this payload.
    if step.op in ("matches", "any_match", "not_match", "none_match"):
        return f"{step.op} (regex pattern, redacted)"
    if step.op in ("in", "not_in"):
        return f"{step.op} {list(step.values)}"
    if step.value is not None:
        return f"{step.op} {step.value}"
    return step.op


def _redact_observed(value: Any) -> str:
    """A short, bounded description of what was seen — never the full evidence."""
    vals = _values(value)
    if not vals:
        return "no matching evidence"
    head = ", ".join(v[:60] for v in vals[:3])
    more = "" if len(vals) <= 3 else f" (+{len(vals) - 3} more)"
    return f"{len(vals)} value(s): {head}{more}"


def evaluate_check(
    subject_evidence: dict[str, Any],
    check: ComplianceCheck,
) -> tuple[str, str, str, list[dict[str, str]]]:
    step_results: list[bool | None] = []
    step_details: list[dict[str, str]] = []
    any_missing = False

    for index, step in enumerate(check.steps, start=1):
        raw = resolve_source(subject_evidence, step.selector)
        selected = apply_select(raw, step.select) if step.select else raw
        try:
            result = apply_assertion(selected, step)
        except TimeoutError:
            result = None
            step_details.append({
                "step": str(index),
                "expected": _describe_expected(step),
                "observed": "pattern evaluation timed out — treated as inconclusive",
            })
            step_results.append(None)
            any_missing = True
            continue

        if result is None:
            any_missing = True
        step_results.append(result)
        step_details.append({
            "step": str(index),
            "expected": _describe_expected(step),
            "observed": _redact_observed(selected),
        })

    conclusive = [r for r in step_results if r is not None]

    if check.combine == "any":
        if any(r is True for r in step_results):
            status = check.on_pass
        elif conclusive and all(r is False for r in conclusive) and not any_missing:
            status = check.on_fail
        elif not conclusive:
            status = check.on_no_evidence
        else:
            status = check.on_no_evidence
    else:  # "all"
        if any(r is False for r in step_results):
            status = check.on_fail
        elif conclusive and all(r is True for r in conclusive) and not any_missing:
            status = check.on_pass
        else:
            status = check.on_no_evidence

    if not conclusive:
        coverage = "not_collected"
    elif any_missing:
        coverage = "partial"
    else:
        coverage = "complete"

    passed = [d for d, r in zip(step_details, step_results) if r is True]
    failed = [d for d, r in zip(step_details, step_results) if r is False]
    if status == check.on_pass:
        summary = f"All {len(check.steps)} evidence step(s) matched the expectation." if check.combine == "all" \
            else f"{len(passed)} of {len(check.steps)} evidence step(s) matched the expectation."
    elif status == check.on_fail:
        first = failed[0] if failed else {"expected": "", "observed": ""}
        summary = f"Evidence step {first.get('step', '?')} did not match: expected {first.get('expected')}, observed {first.get('observed')}."
    else:
        summary = "The evidence needed to judge this check was not collected for this subject."

    return status, summary, coverage, step_details
