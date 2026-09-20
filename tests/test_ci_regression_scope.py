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
    ]) == "ldap"


def test_delivery_files_still_select_ldap_regression():
    assert classify(["scripts/ci_regression_scope.py", "tests/test_ci_regression_scope.py"]) == "ldap"


def test_discovery_and_inventory_are_separate_components():
    assert classify(["ui2/discovery/src/main/kotlin/Example.kt"]) == "discovery"
    assert classify(["ui2/persistence/src/main/java/com/securityexpert/nexus/ui2/persistence/device/JooqDeviceRepository.java"]) == "inventory"
    assert classify(["ui2/discovery/src/main/kotlin/Example.kt", "ui2/frontend/src/screens/InventoryScreen.tsx"]) == "discovery+inventory"


def test_pr_434_inventory_paths_select_inventory_coverage():
    assert classify([
        "ui2/frontend/src/screens/DeviceRegistryPanel.tsx",
        "ui2/frontend/src/screens/InventoryPanels.tsx",
        "ui2/frontend/src/screens/InventoryScreen.tsx",
        "ui2/frontend/src/shell/deviceCopy.ts",
        "ui2/frontend/tests/AdministrationScreen.test.tsx",
        "ui2/persistence/src/main/java/com/securityexpert/nexus/ui2/persistence/device/JooqDeviceRepository.java",
        "ui2/persistence/src/test/java/com/securityexpert/nexus/ui2/persistence/device/JooqDeviceRepositoryTest.java",
        "ui2/service/src/main/java/com/securityexpert/nexus/ui2/service/privacy/TopologyNamePseudonymizer.java",
        "ui2/service/src/test/java/com/securityexpert/nexus/ui2/service/privacy/PrivacyMaskingResponseBodyAdviceTest.java",
        "ui2/service/src/test/java/com/securityexpert/nexus/ui2/service/privacy/TopologyNamePseudonymizerTest.java",
    ]) == "inventory"


def test_unmapped_persistence_path_is_blocked():
    assert classify(["ui2/persistence/src/main/java/com/securityexpert/nexus/ui2/persistence/OtherRepository.java"]) == "blocked"


def test_docs_only_skips_full_regression():
    assert classify(["docs/design/record.md", "README.md"]) == "skip"


def test_major_changes_require_full_regression():
    assert classify(["ui2/build.gradle.kts"]) == "full"


def test_unknown_or_empty_scope_is_blocked():
    assert classify(["unmapped/file.txt"]) == "blocked"
    assert classify([]) == "blocked"
