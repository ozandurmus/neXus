from scripts.ci_regression_scope import classify


def test_approved_ldap_delivery_is_targeted():
    assert classify([
        "ui2/ldap-adapter/src/main/java/Example.java",
        "ui2/platform-core/src/main/java/Example.java",
        "ui2/persistence/src/test/java/ExampleTest.java",
        "ui2/service/src/main/resources/db/migration/V25__directory_principal_bindings.sql",
        "ui2/frontend/tests/ProjectPlanPanel.test.tsx",
        "ui2/frontend/src/auth/LoginScreen.tsx",
        "ui2/frontend/tests/LoginScreen.test.tsx",
        "ui2/frontend/tests/fixtures/project-plan-provenance.json",
        ".github/workflows/validation.yml",
        "tests/conftest.py",
        "tests/test_gov_po_3_ci_privacy_gate_baseline.py",
        "tests/test_nexus_engineer_tool_gate.py",
    ]) == "targeted"


def test_docs_only_skips_full_regression():
    assert classify(["docs/design/record.md", "README.md"]) == "skip"


def test_major_changes_require_full_regression():
    assert classify(["ui2/build.gradle.kts"]) == "full"


def test_unknown_or_empty_scope_is_blocked():
    assert classify(["unmapped/file.txt"]) == "blocked"
    assert classify([]) == "blocked"
