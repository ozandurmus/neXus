#!/usr/bin/env python3
"""Fail-closed PR regression routing for closed component selections."""

import argparse
from pathlib import Path
import sys


LDAP_PREFIXES = {
    "ui2/cli/",
    "ui2/ldap-adapter/",
    "ui2/platform-core/",
    "ui2/frontend/tests/ProjectPlanPanel.test.tsx",
    "ui2/frontend/tests/fixtures/project-plan-provenance.json",
    "ui2/frontend/src/auth/LoginScreen.tsx",
    "ui2/frontend/tests/LoginScreen.test.tsx",
}
LDAP_ANCHORS = ("ui2/cli/", "ui2/ldap-adapter/", "ui2/platform-core/")
INVENTORY_PREFIXES = (
    "ui2/persistence/src/main/java/com/securityexpert/nexus/ui2/persistence/device/",
    "ui2/persistence/src/test/java/com/securityexpert/nexus/ui2/persistence/device/",
    "ui2/service/src/main/java/com/securityexpert/nexus/ui2/service/device/",
    "ui2/service/src/test/java/com/securityexpert/nexus/ui2/service/device/",
    "ui2/worker/src/main/java/com/securityexpert/nexus/ui2/worker/inventory/",
    "ui2/worker/src/test/java/com/securityexpert/nexus/ui2/worker/inventory/",
    "ui2/frontend/src/screens/DeviceRegistryPanel.tsx",
    "ui2/frontend/src/screens/Inventory",
    "ui2/frontend/src/screens/DeviceWorkspaceScreen.tsx",
    "ui2/frontend/src/shell/deviceCopy.ts",
    "ui2/frontend/tests/AdministrationScreen.test.tsx",
    "ui2/frontend/tests/Inventory",
)
DISCOVERY_PREFIXES = (
    "ui2/job-engine/src/main/java/com/securityexpert/nexus/ui2/jobs/discovery/",
    "ui2/persistence/src/main/java/com/securityexpert/nexus/ui2/persistence/discovery/",
    "ui2/persistence/src/test/java/com/securityexpert/nexus/ui2/persistence/discovery/",
    "ui2/platform-core/src/main/java/com/securityexpert/nexus/ui2/discovery/",
    "ui2/platform-core/src/test/java/com/securityexpert/nexus/ui2/discovery/",
    "ui2/service/src/main/java/com/securityexpert/nexus/ui2/service/discovery/",
    "ui2/service/src/test/java/com/securityexpert/nexus/ui2/service/discovery/",
    "ui2/worker/src/main/java/com/securityexpert/nexus/ui2/worker/discovery/",
    "ui2/worker/src/test/java/com/securityexpert/nexus/ui2/worker/discovery/",
    "ui2/frontend/src/shell/AddDeviceDialog.tsx",
    "ui2/frontend/tests/AddDeviceDialog.test.tsx",
)
PRIVACY_PREFIXES = (
    "ui2/service/src/main/java/com/securityexpert/nexus/ui2/service/privacy/",
    "ui2/service/src/test/java/com/securityexpert/nexus/ui2/service/privacy/",
)
SHELL_PREFIXES = (
    "ui2/frontend/src/",
    "ui2/frontend/tests/",
)
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
# Migration roots always run live regression, including their Markdown files.
# Unknown paths still block, even in a mixed change containing a known full route.
MAJOR_PREFIXES = ("ui2/build.gradle.kts", "ui2/settings.gradle.kts", "gradle/", "requirements", "history/", "tools/")
TOOLING_FILES = {
    "AGENTS.md", "AI_START_HERE.md", "CLAUDE.md", "pytest.ini",
    "scripts/repository_privacy_check.py", "utils/repository_privacy.py",
    "tests/test_live_project_governance.py", "tests/test_live_tooling_discovery.py",
    "tests/README.md", "tests/test_cold_start_budget.py",
    "tests/test_action_taxonomy_java_parity.py",
    "tests/test_gov_po_2_privacy_entrypoint.py", "tests/test_security_scans.py",
    "tests/test_dev0_4_repository_privacy_gate.py",
}
COMPONENT_PREFIXES = (
    ("discovery", DISCOVERY_PREFIXES),
    ("inventory", INVENTORY_PREFIXES),
    ("privacy", PRIVACY_PREFIXES),
    ("shell", SHELL_PREFIXES),
)


def component_for_path(path: str) -> str | None:
    for name, prefixes in COMPONENT_PREFIXES:
        if path.startswith(prefixes):
            return name
    return None


def classify(paths: list[str]) -> str:
    if not paths:
        return "blocked"

    def full_path(path: str) -> bool:
        return path in TOOLING_FILES or path.startswith(MAJOR_PREFIXES)

    if any(full_path(path) for path in paths):
        def known(path: str) -> bool:
            return (full_path(path) or path in DELIVERY_FILES
                    or component_for_path(path) is not None
                    or path.startswith(tuple(LDAP_PREFIXES))
                    or path.startswith("docs/") or path.endswith(".md"))
        return "full" if all(known(path) for path in paths) else "blocked"
    if all(path.startswith("docs/") or path.endswith(".md") for path in paths):
        return "skip"
    ldap = all(path in DELIVERY_FILES for path in paths) or (
        all(path in DELIVERY_FILES or path.startswith(tuple(LDAP_PREFIXES)) or path.startswith(("ui2/persistence/", "ui2/service/")) for path in paths)
        and any(path.startswith(LDAP_ANCHORS) for path in paths)
    )
    if ldap:
        return "ldap"
    components = {component for path in paths if (component := component_for_path(path))}
    if components and all(
        path in DELIVERY_FILES or component_for_path(path)
        for path in paths
    ):
        return "+".join(sorted(components))
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
