#!/usr/bin/env python3
"""Fail-closed PR regression routing for the approved LDAP delivery."""

import argparse
from pathlib import Path
import sys


LDAP_PREFIXES = {
    "ui2/cli/",
    "ui2/ldap-adapter/",
    "ui2/persistence/",
    "ui2/platform-core/",
    "ui2/service/",
    "ui2/frontend/tests/ProjectPlanPanel.test.tsx",
    "ui2/frontend/tests/fixtures/project-plan-provenance.json",
}
DELIVERY_FILES = {
    ".github/workflows/validation.yml",
    "AI_HANDOVER.md",
    "docs/AI_DEVELOPMENT_PROTOCOL.md",
    "docs/design/DECISION_RECORD_SUCCESSOR_INDEX.md",
    "docs/design/PO_DECISION_RECORD_2026_09_15C_REGRESSION_GATE_RUNS_WHEN_THE_CORE_CHANGES.md",
    "project/feature_registry.json",
    "scripts/ci_regression_scope.py",
    "tests/test_ci_regression_scope.py",
    "tests/test_ci_workflow_fast_pr_regression.py",
    "tests/conftest.py",
    "tests/test_gov_po_3_ci_privacy_gate_baseline.py",
    "tests/test_nexus_engineer_tool_gate.py",
}
MAJOR_PREFIXES = ("ui2/build.gradle.kts", "ui2/settings.gradle.kts", "gradle/", "requirements")


def classify(paths: list[str]) -> str:
    if not paths:
        return "blocked"
    if all(path.startswith("docs/") or path.endswith(".md") for path in paths):
        return "skip"
    if any(path.startswith(MAJOR_PREFIXES) for path in paths):
        return "full"
    if all(path in DELIVERY_FILES or path.startswith(tuple(LDAP_PREFIXES)) for path in paths):
        return "targeted"
    return "blocked"


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--github-output", type=Path, required=True)
    args = parser.parse_args()
    paths = [line for line in sys.stdin.read().splitlines() if line]
    with args.github_output.open("a", encoding="utf-8") as output:
        output.write(f"classification={classify(paths)}\n")


if __name__ == "__main__":
    main()
