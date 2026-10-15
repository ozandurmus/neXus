package com.securityexpert.nexus.ui2.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.platform.JobWindowPolicy;
import com.securityexpert.nexus.ui2.platform.PacketAnalysisJob;
import com.securityexpert.nexus.ui2.worker.confirm.WorkerClaimLoop;
import com.securityexpert.nexus.ui2.worker.packetanalysis.PacketAnalysisClaimLoop;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;

/**
 * Packet Analysis module fence (PO decision record 2026-10-15; the 2026-10-05 ownership incident): the new module
 * claims exactly {@code packet_analysis_run}, no existing worker role gains it, the migration that introduced it
 * changes no existing module's ownership or capability mapping, and the analysis worker has no path to a device.
 */
class PacketAnalysisModuleFenceTest {

    private static final Path MIGRATIONS = ui2Root().resolve("service/src/main/resources/db/migration");

    @Test void theModuleClaimsItsOneJobTypeAndNoOtherRoleClaimsIt() {
        assertEquals(List.of(PacketAnalysisJob.CAPABILITY), PacketAnalysisClaimLoop.CLAIMS);
        for (boolean fallback : new boolean[] {false, true}) {
            assertFalse(WorkerClaimLoop.claimCapabilities("general", fallback).contains(PacketAnalysisJob.CAPABILITY));
            assertFalse(WorkerClaimLoop.claimCapabilities("policy", fallback).contains(PacketAnalysisJob.CAPABILITY));
        }
        assertEquals(java.util.Set.of(PacketAnalysisJob.CAPABILITY), JobWindowPolicy.STORED_DATA_JOB_TYPES,
                "only device-free work may be exempt from the polling windows");
    }

    @Test void theMigrationAddsOneModuleAndOneMappingAndTouchesNoExistingOwnership() throws Exception {
        Path migration;
        try (var files = Files.list(MIGRATIONS)) {
            migration = files.filter(p -> p.getFileName().toString().endsWith("__packet_analysis_shell.sql")).findFirst().orElseThrow();
        }
        String sql = Files.readString(migration);
        // The mapping V154 replaces is the latest earlier ui2_job_module definition (V128, then V151).
        int version = migrationVersion(migration);
        Path previous;
        try (var files = Files.list(MIGRATIONS)) {
            previous = files.filter(p -> migrationVersion(p) < version)
                    .filter(p -> readUnchecked(p).contains("CREATE OR REPLACE FUNCTION ui2_job_module"))
                    .max(java.util.Comparator.comparingInt(PacketAnalysisModuleFenceTest::migrationVersion)).orElseThrow();
        }
        String before = caseBody(Files.readString(previous));
        String after = caseBody(sql);
        assertEquals(before.replace(" ELSE NULL END", "") + " WHEN capability = 'packet_analysis_run' THEN 'packet_analysis'"
                + " ELSE NULL END", after, "every " + previous.getFileName() + " mapping is kept byte-for-byte; "
                + "exactly one branch is added");
        assertFalse(Pattern.compile("(?i)UPDATE\\s+module_runtime_control").matcher(sql).find(),
                "no existing module row is changed");
        Matcher inserts = Pattern.compile("(?i)INSERT INTO module_runtime_control[^;]*;").matcher(sql);
        assertTrue(inserts.find());
        assertTrue(inserts.group().contains("('packet_analysis', 'packet_analysis', FALSE"));
        assertFalse(inserts.find(), "exactly one module row is inserted");
        for (String table : List.of("packet_analysis_case", "packet_analysis_capture", "packet_analysis_run", "packet_analysis_ledger"))
            assertTrue(sql.contains("CREATE TRIGGER trg_audit_" + table + "\n"), table + " carries its audit trigger");
    }

    @Test void theAnalysisWorkerHasNoDeviceOrCredentialPath() {
        var classes = new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.securityexpert.nexus.ui2.worker.packetanalysis");
        assertFalse(classes.isEmpty());
        noClasses().that().resideInAPackage("..worker.packetanalysis..").should().dependOnClassesThat()
                .resideInAnyPackage("..worker.transport..", "..worker.credential..", "..persistence.credential..",
                        "..capability..", "com.jcraft..")
                .check(classes);
    }

    private static int migrationVersion(Path migration) {
        Matcher version = Pattern.compile("^V(\\d+)__").matcher(migration.getFileName().toString());
        return version.find() ? Integer.parseInt(version.group(1)) : -1;
    }

    private static String readUnchecked(Path file) {
        try {
            return Files.readString(file);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private static String caseBody(String sql) {
        Matcher matcher = Pattern.compile("FUNCTION ui2_job_module\\(capability TEXT\\).*?SELECT CASE(.*?)\\$\\$", Pattern.DOTALL)
                .matcher(sql);
        assertTrue(matcher.find(), "ui2_job_module definition not found");
        return matcher.group(1).replaceAll("\\s+", " ").strip();
    }

    private static Path ui2Root() {
        return Paths.get(System.getProperty("user.dir")).getParent();
    }
}
