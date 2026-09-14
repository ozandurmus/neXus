"""Frozen contract clause-list shape guards."""
from __future__ import annotations

import re
from pathlib import Path

import pytest


ROOT = Path(__file__).resolve().parents[1]

# A FROZEN status line claims frozen content; this makes that claim checkable.
_CONTRACTS = (
    (
        "docs/design/UI2_0_C7_BACKUP_ARTEFACT_RESTORE_ENGINE_CONTRACT.md",
        "### 5.3 Preconditions (`AC-5`)",
        (
            "Connectivity",
            "Backup validity",
            "Target identity match",
            "Version match",
            "No concurrent restore or backup against the same target",
            "Credential resolution",
            "No unreconciled prior restore-write outcome",
        ),
    ),
    (
        "docs/design/UI2_0_C2_JOB_EXECUTION_CONTRACT.md",
        "## 6. Pre-execution checks",
        (
            "Approval state, re-checked fresh",
            "Connectivity precondition",
            "Line-1/Java device-contact coordination window",
            "Write-admission ledger, class-scoped.",
            "Device registry / allowlist re-check",
            "Credential resolution",
        ),
    ),
)


def _section(text: str, heading: str) -> str:
    start = text.index(heading)
    level = len(heading) - len(heading.lstrip("#"))
    following = re.search(rf"^#{{1,{level}}} ", text[start + len(heading):], re.MULTILINE)
    end = start + len(heading) + following.start() if following else len(text)
    return text[start:end]


def _clause_labels(path: Path, heading: str) -> tuple[str, ...]:
    section = _section(path.read_text(encoding="utf-8"), heading)
    return tuple(re.findall(r"^\|\s*\d+\s*\|\s*\*\*([^*]+)\*\*", section, re.MULTILINE))


def _assert_shape(path: Path, heading: str, expected: tuple[str, ...]) -> None:
    found = _clause_labels(path, heading)
    assert found == expected, (
        f"Frozen contract shape changed in {path.as_posix()}, section {heading!r}: "
        f"expected {expected!r}, found {found!r}. Changing a frozen contract's "
        "shape is a Product Owner decision, not a test to update."
    )


def _variant_text(heading: str, labels: list[str], explanation: str = "explanation") -> str:
    return heading + "\n\n" + "\n".join(
        f"| {number} | **{label}** — {explanation} |"
        for number, label in enumerate(labels, start=1)
    )


@pytest.mark.parametrize(("relative_path", "heading", "expected"), _CONTRACTS)
def test_frozen_contract_clause_shapes(relative_path: str, heading: str, expected: tuple[str, ...]):
    _assert_shape(ROOT / relative_path, heading, expected)


@pytest.mark.parametrize(("relative_path", "heading", "expected"), _CONTRACTS)
@pytest.mark.parametrize("change", ("remove", "add", "reorder"))
def test_frozen_contract_shape_guard_rejects_clause_changes(
    tmp_path: Path, relative_path: str, heading: str, expected: tuple[str, ...], change: str
):
    labels = list(expected)
    if change == "remove":
        labels.pop()
    elif change == "add":
        labels.append("A new check")
    else:
        labels[0], labels[1] = labels[1], labels[0]
    variant = tmp_path / Path(relative_path).name
    variant.write_text(_variant_text(heading, labels), encoding="utf-8")
    with pytest.raises(AssertionError, match="Product Owner decision"):
        _assert_shape(variant, heading, expected)


@pytest.mark.parametrize(("relative_path", "heading", "expected"), _CONTRACTS)
def test_frozen_contract_shape_guard_allows_explanation_rewording(
    tmp_path: Path, relative_path: str, heading: str, expected: tuple[str, ...]
):
    variant = tmp_path / Path(relative_path).name
    variant.write_text(_variant_text(heading, list(expected), "clarified explanation"), encoding="utf-8")
    _assert_shape(variant, heading, expected)
