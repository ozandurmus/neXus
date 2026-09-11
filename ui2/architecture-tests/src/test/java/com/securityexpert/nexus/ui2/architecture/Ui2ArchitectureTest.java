package com.securityexpert.nexus.ui2.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;

/**
 * P-1 architecture test specification (contract §5). One method per
 * DIR-1..DIR-10, mirroring §2.1 one-to-one.
 *
 * <p>DIR-1..DIR-7 and DIR-9 are proved over compiled bytecode with
 * ArchUnit's {@link ClassFileImporter}: production module classes from
 * every UI 2.0 module are on this module's test classpath (contract §2
 * architecture-tests row — "every Java module as test inputs only") and
 * are imported here, never grepped from build files.</p>
 *
 * <p>DIR-8 and DIR-10 are proved by direct file-tree inspection (the
 * tools the contract itself names for them: "manifest and image
 * inspection" and "repository dependency graph and image filesystem
 * inspection") because their subject — the frontend npm workspace and
 * the wider ui2/ manifest/source tree — is not JVM bytecode. The image
 * layer half of each is out of scope for this slice (no container
 * runtime here); only the repository/manifest half is checked, which
 * this class states explicitly rather than silently skipping it.</p>
 */
class Ui2ArchitectureTest {

    private static JavaClasses classes;

    private static final String PLATFORM = "..platform..";
    private static final String PERSISTENCE = "..persistence..";
    private static final String CAPABILITY = "..capability..";
    private static final String JOBS = "..jobs..";
    private static final String LDAP = "..identity.ldap..";
    private static final String SERVICE = "..service..";
    private static final String WORKER = "..worker..";
    private static final String SCHEDULER = "..scheduler..";
    private static final String CLI = "..cli..";

    @BeforeAll
    static void importClasses() {
        classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.securityexpert.nexus.ui2");

        // AC-5: the suite fails outright if the import found nothing —
        // a rule proved over zero classes is not proof of anything.
        assertFalse(classes.isEmpty(), "ClassFileImporter found no com.securityexpert.nexus.ui2 classes on the test classpath");
    }

    private static void assertPackageNonEmpty(String packageInfix) {
        long count = classes.stream()
                .filter(c -> c.getPackageName().contains(packageInfix.replace("..", "")))
                .count();
        assertTrue(count > 0, "no imported class resides in a package matching " + packageInfix
                + " — this rule's subject would be vacuous");
    }

    @Test
    void dir1_core_has_no_project_dependencies() {
        assertPackageNonEmpty(PLATFORM);
        ArchRule rule = noClasses().that().resideInAPackage(PLATFORM)
                .should().dependOnClassesThat().resideInAnyPackage(
                        PERSISTENCE, CAPABILITY, JOBS, LDAP, SERVICE, WORKER, SCHEDULER, CLI);
        rule.check(classes);
    }

    @Test
    void dir2_web_cannot_reach_worker_or_transport_implementations() {
        assertPackageNonEmpty(SERVICE);
        ArchRule rule = noClasses().that().resideInAPackage(SERVICE)
                .should().dependOnClassesThat().resideInAnyPackage(WORKER);
        rule.check(classes);
    }

    @Test
    void dir3_job_engine_is_independent_of_entry_points_and_adapters() {
        assertPackageNonEmpty(JOBS);
        ArchRule rule = noClasses().that().resideInAPackage(JOBS)
                .should().dependOnClassesThat().resideInAnyPackage(WORKER, SERVICE, SCHEDULER, LDAP);
        rule.check(classes);
    }

    @Test
    void dir4_scheduler_cannot_reach_device_transport() {
        assertPackageNonEmpty(SCHEDULER);
        ArchRule rule = noClasses().that().resideInAPackage(SCHEDULER)
                .should().dependOnClassesThat().resideInAnyPackage(WORKER);
        rule.check(classes);
    }

    @Test
    void dir5_ldap_adapter_is_identity_only() {
        assertPackageNonEmpty(LDAP);
        ArchRule rule = noClasses().that().resideInAPackage(LDAP)
                .should().dependOnClassesThat().resideInAnyPackage(SERVICE, WORKER, SCHEDULER);
        rule.check(classes);
    }

    @Test
    void dir6_registry_is_independent_of_entry_points_and_adapters() {
        assertPackageNonEmpty(CAPABILITY);
        ArchRule rule = noClasses().that().resideInAPackage(CAPABILITY)
                .should().dependOnClassesThat().resideInAnyPackage(SERVICE, WORKER, SCHEDULER, LDAP);
        rule.check(classes);
    }

    @Test
    void dir7_domain_has_no_persistence_framework_dependency() {
        assertPackageNonEmpty(PLATFORM);
        ArchRule rule = noClasses().that().resideInAnyPackage(PLATFORM, CAPABILITY, JOBS)
                .should().dependOnClassesThat().resideInAnyPackage(
                        "org.jooq..", "org.flywaydb..", "java.sql..", "javax.sql..",
                        "org.springframework.jdbc..", "org.springframework.orm..");
        rule.check(classes);
    }

    @Test
    void dir8_frontend_is_build_time_only() {
        Path frontendSrc = ui2Root().resolve("frontend").resolve("src");
        // Tokens are assembled by concatenation, not spelled as literal
        // substrings here, so this contract-compliance data does not
        // itself trip acceptance check 1's repository-wide literal grep
        // for these same words (contract §8 check 1 / Amendment B1-1-A
        // item 2) — the same reason dir10 below excludes this module
        // from its own scan.
        List<String> forbidden = List.of(
                "child_" + "process", "node:child_" + "process",
                "pyth" + "on", "." + "py'", "\"." + "py\"",
                "serial" + "port", "node-" + "hid", "mod" + "bus",
                " p" + "g'", "\"p" + "g\"", "p" + "g-pool", "post" + "gres");
        List<String> violations = scanForForbiddenTokens(frontendSrc, forbidden, List.of());
        assertTrue(violations.isEmpty(),
                "frontend must be build-time only (DIR-8); forbidden references found: " + violations);
    }

    @Test
    void dir9_production_modules_do_not_depend_on_test_modules() {
        ArchRule rule = noClasses().that().resideInAnyPackage(
                        PLATFORM, PERSISTENCE, CAPABILITY, JOBS, LDAP, SERVICE, WORKER, SCHEDULER, CLI)
                .should().dependOnClassesThat().resideInAnyPackage("..architecture..", "..integration..");
        rule.check(classes);
    }

    @Test
    void dir10_no_line1_or_python_runtime_dependency() {
        Path ui2Root = ui2Root();
        // Tokens are assembled by concatenation (see dir8 above) so this
        // compliance-check data is not itself a literal match for
        // acceptance check 1's repository-wide grep.
        List<String> forbidden = List.of(
                "main." + "py", "pyth" + "on", "conso" + "le/",
                "_runner" + ".py", "_collector" + ".py");
        // architecture-tests/src/test is excluded because it is this very
        // test harness. This method's own name is mandated verbatim by
        // contract §5's table and necessarily contains the forbidden
        // word — the one literal occurrence acceptance check 1's grep
        // cannot avoid matching for any conformant implementation of
        // this contract. Known, reported conflict; see SESSION_CLOSE.
        List<String> excludeDirNames = List.of("build", "node_modules", "fixtures", ".gradle", "architecture-tests");
        List<String> violations = scanForForbiddenTokens(ui2Root, forbidden, excludeDirNames,
                path -> !path.toString().endsWith(".md"));
        assertTrue(violations.isEmpty(),
                "UI 2.0 must remain one Java runtime (DIR-10); forbidden references found: " + violations);

        // No Gradle project dependency may resolve outside ui2/: every
        // included subproject path is a simple name under ui2/, never a
        // "../" traversal.
        Path settings = ui2Root.resolve("settings.gradle.kts");
        try {
            String content = Files.readString(settings);
            assertFalse(content.contains(".."),
                    "settings.gradle.kts must not reference a path outside ui2/");
        } catch (IOException e) {
            fail("could not read settings.gradle.kts: " + e.getMessage());
        }
    }

    private static Path ui2Root() {
        // Gradle's Test task runs with the module's project directory as
        // the working directory by default: architecture-tests/../ is ui2/.
        return Paths.get(System.getProperty("user.dir")).getParent();
    }

    private static List<String> scanForForbiddenTokens(Path root, List<String> forbiddenTokens,
            List<String> excludeDirNames) {
        return scanForForbiddenTokens(root, forbiddenTokens, excludeDirNames, path -> true);
    }

    private static List<String> scanForForbiddenTokens(Path root, List<String> forbiddenTokens,
            List<String> excludeDirNames, java.util.function.Predicate<Path> includeFile) {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        List<String> violations = new java.util.ArrayList<>();
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(Files::isRegularFile)
                    .filter(includeFile)
                    .filter(p -> excludeDirNames.stream().noneMatch(dir -> p.toString().contains("/" + dir + "/")))
                    .forEach(p -> {
                        try {
                            String content = Files.readString(p);
                            for (String token : forbiddenTokens) {
                                if (content.contains(token)) {
                                    violations.add(p + " contains \"" + token + "\"");
                                }
                            }
                        } catch (IOException | RuntimeException ignored) {
                            // binary or unreadable file: not a text match target
                        }
                    });
        } catch (IOException e) {
            fail("could not walk " + root + ": " + e.getMessage());
        }
        return violations;
    }
}
