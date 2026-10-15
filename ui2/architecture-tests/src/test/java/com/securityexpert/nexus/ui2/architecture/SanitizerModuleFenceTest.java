package com.securityexpert.nexus.ui2.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.jupiter.api.Test;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;

/**
 * Data Sanitizer module fence (docs/design/DATA_SANITIZER_CONTRACT.md §4 "plain-Java module"): the engine depends on
 * no other UI 2.0 module, on no framework, database or network API, and no other module but the service reaches it.
 * It reads files it is given and writes the output it is given -- nothing else.
 */
class SanitizerModuleFenceTest {

    private static final JavaClasses SANITIZER = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.securityexpert.nexus.ui2.sanitizer");

    @Test
    void theEngineIsPlainJavaWithNoPathToAnotherModuleTheDatabaseOrTheNetwork() {
        assertFalse(SANITIZER.isEmpty(), "no sanitizer class was imported");
        noClasses().that().resideInAPackage("..ui2.sanitizer..").should().dependOnClassesThat()
                .resideInAnyPackage("..ui2.platform..", "..ui2.persistence..", "..ui2.service..", "..ui2.worker..",
                        "..ui2.jobs..", "..ui2.capability..", "..ui2.cli..", "..ui2.scheduler..", "..identity.ldap..",
                        "org.springframework..", "org.jooq..", "java.sql..", "javax.sql..", "java.net.http..",
                        "com.jcraft..")
                .check(SANITIZER);
        noClasses().that().resideInAPackage("..ui2.sanitizer..").should().dependOnClassesThat()
                .haveFullyQualifiedName("java.net.Socket").orShould().dependOnClassesThat()
                .haveFullyQualifiedName("java.net.URL").orShould().dependOnClassesThat()
                .haveFullyQualifiedName("java.lang.ProcessBuilder")
                .check(SANITIZER);
    }

    @Test
    void onlyTheServiceDependsOnTheEngine() throws Exception {
        Path ui2 = Paths.get(System.getProperty("user.dir")).getParent();
        for (String module : new String[] {"platform-core", "persistence", "capability-registry", "job-engine", "ldap-adapter",
                "scheduler", "cli", "worker"}) {
            String build = Files.readString(ui2.resolve(module).resolve("build.gradle.kts"));
            assertFalse(build.contains("project(\":sanitizer\")"), module + " must not depend on the sanitizer");
        }
        assertTrue(Files.readString(ui2.resolve("service/build.gradle.kts")).contains("project(\":sanitizer\")"));
    }
}
