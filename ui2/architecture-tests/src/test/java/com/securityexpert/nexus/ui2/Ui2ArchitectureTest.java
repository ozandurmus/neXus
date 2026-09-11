package com.securityexpert.nexus.ui2;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Ui2ArchitectureTest {
    private static final Path UI2 = Path.of("..").toAbsolutePath().normalize();

    private static String build(String module) throws Exception {
        return Files.readString(UI2.resolve(module).resolve("build.gradle.kts"));
    }

    private static boolean dependsOn(String module, String dependency) throws Exception {
        return Pattern.compile("project\\(\\s*\\\":" + Pattern.quote(dependency) + "\\\"\\s*\\)")
                .matcher(build(module)).find();
    }

    @Test void dir1_core_has_no_project_dependencies() throws Exception {
        assertFalse(build("platform-core").contains("project("));
    }

    @Test void dir2_web_cannot_reach_worker_or_transport_implementations() throws Exception {
        assertFalse(build("service").contains("project(\":worker\")"));
        assertFalse(dependsOn("service", "worker"));
    }

    @Test void dir3_job_engine_is_independent_of_entry_points_and_adapters() throws Exception {
        String text = build("job-engine");
        assertFalse(text.contains("project(\":service\")") || text.contains("project(\":worker\")"));
        for (String dependency : List.of("service", "worker", "scheduler", "ldap-adapter")) {
            assertFalse(dependsOn("job-engine", dependency));
        }
    }

    @Test void dir4_scheduler_cannot_reach_device_transport() throws Exception {
        assertFalse(build("scheduler").contains("project(\":worker\")"));
        assertFalse(dependsOn("scheduler", "worker"));
    }

    @Test void dir5_ldap_adapter_is_identity_only() throws Exception {
        String text = build("ldap-adapter");
        assertFalse(text.contains("project(\":service\")") || text.contains("project(\":worker\")"));
        for (String dependency : List.of("service", "worker", "scheduler")) {
            assertFalse(dependsOn("ldap-adapter", dependency));
        }
    }

    @Test void dir6_registry_is_independent_of_entry_points_and_adapters() throws Exception {
        String text = build("capability-registry");
        assertFalse(text.contains("project(\":service\")") || text.contains("project(\":worker\")"));
    }

    @Test void dir7_domain_has_no_persistence_framework_dependency() throws Exception {
        assertFalse(build("platform-core").matches("(?s).*jooq|flyway|jdbc|spring.*"));
    }

    @Test void dir8_frontend_is_build_time_only() throws Exception {
        assertFalse(Files.readString(UI2.resolve("frontend/package.json")).contains("node-server"));
    }

    @Test void dir9_production_modules_do_not_depend_on_test_modules() throws Exception {
        for (String module : List.of("platform-core", "persistence", "service", "worker", "scheduler")) {
            String text = build(module);
            assertFalse(text.contains("architecture-tests") || text.contains("integration-tests"));
        }
    }

    @Test void dir10_no_line1_or_python_runtime_dependency() throws Exception {
        String settings = Files.readString(UI2.resolve("settings.gradle.kts"));
        assertTrue(settings.contains("platform-core") && !settings.contains("python"));
    }
}
