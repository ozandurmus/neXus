package com.securityexpert.nexus.ui2.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.tngtech.archunit.core.domain.JavaCall;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.properties.HasName;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.capability.GateRegistryFixtureLoader;
import com.securityexpert.nexus.ui2.capability.GateRegistryPort;
import com.securityexpert.nexus.ui2.jobs.diagnostic.DiagnosticRead;
import com.securityexpert.nexus.ui2.jobs.pathvalidation.HopPlan;
import com.securityexpert.nexus.ui2.jobs.pathvalidation.PathValidation;
import com.securityexpert.nexus.ui2.worker.confirm.WorkerClaimLoop;

/**
 * Path analysis stage A6 (PATH_ANALYSIS_A6_COMMAND_PROPOSAL.md rows 2-5; AGENTS.md "No Browser -> device path"): the
 * browser can never supply command text. The API and the service planner only produce a plan of values; the approved
 * literal is assembled from a plan in the worker alone, and only by {@link PathValidation}'s validating builders.
 */
class PathValidationFenceTest {

    private static final String VALIDATION = "com.securityexpert.nexus.ui2.service.pathanalysis.validation..";
    private static final String CONTROLLER = "com.securityexpert.nexus.ui2.service.api.PathValidationController";
    private static final String WORKER = "com.securityexpert.nexus.ui2.worker.pathvalidation..";
    private static final String BUILDERS = "cpRouteGet|panFibLookup|panSecurityPolicyMatch|panNatPolicyMatch";

    private final JavaClasses classes = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.securityexpert.nexus.ui2");

    @Test
    void onlyTheWorkerAssemblesAnApprovedLiteralAndTheApiNeverReachesATransport() {
        assertTrue(classes.stream().anyMatch(c -> c.getName().equals(CONTROLLER)), "vacuous: no controller imported");
        assertTrue(classes.stream().anyMatch(c -> c.getPackageName().startsWith(WORKER.replace("..", ""))),
                "vacuous: no worker class imported");
        noClasses().that().resideOutsideOfPackages(WORKER, "com.securityexpert.nexus.ui2.jobs.pathvalidation..")
                .should().callMethodWhere(JavaCall.Predicates.target(HasName.Predicates.nameMatching(BUILDERS))
                        .and(JavaCall.Predicates.target(com.tngtech.archunit.core.domain.properties.HasOwner.Predicates
                                .With.owner(com.tngtech.archunit.base.DescribedPredicate.describe("PathValidation",
                                        owner -> owner.getName().equals(PathValidation.class.getName()))))))
                .because("command text exists only in the worker, built from a validated plan, never in the API")
                .check(classes);
        noClasses().that().resideInAPackage(VALIDATION).or().haveFullyQualifiedName(CONTROLLER)
                .should().dependOnClassesThat().resideInAnyPackage("..ui2.worker..", "..jobs.transport..",
                        "..jobs.diagnostic..")
                .because("the API admits typed jobs; it never contacts a device or uses the diagnostic_read catalog")
                .check(classes);
    }

    @Test
    void aPlanCarriesValuesNeverACommandGateOrFreeTextField() {
        Set<String> components = Arrays.stream(HopPlan.class.getRecordComponents()).map(c -> c.getName())
                .collect(java.util.stream.Collectors.toSet());
        Set<String> expect = Arrays.stream(HopPlan.Expect.class.getRecordComponents()).map(c -> c.getName())
                .collect(java.util.stream.Collectors.toSet());
        for (String name : components) {
            assertFalse(name.toLowerCase().matches(".*(command|cmd|gate|literal|template|argv|script|note|free|raw).*"), name);
        }
        for (String name : expect) {
            assertFalse(name.toLowerCase().matches(".*(command|cmd|gate|literal|template|argv|script|note|free|raw).*"), name);
        }
    }

    @Test
    void theControllerAcceptsOnlyTheQueryFieldsAndABranchId() throws Exception {
        String source = Files.readString(ui2Root().resolve(
                "service/src/main/java/com/securityexpert/nexus/ui2/service/api/PathValidationController.java"));
        Matcher removed = Pattern.compile("rest\\.remove\\(\"([a-z_]+)\"\\)").matcher(source);
        List<String> own = removed.results().map(m -> m.group(1)).toList();
        assertEquals(List.of("branch_id"), own, "the one field beyond the A5a query");
        assertTrue(source.contains("PathAnalysisController.parse(rest)"), "the rest goes through the A5a strict parser");
        String query = Files.readString(ui2Root().resolve(
                "service/src/main/java/com/securityexpert/nexus/ui2/service/api/PathAnalysisController.java"));
        assertTrue(query.contains("private static final Set<String> FIELDS = Set.of(\"src\", \"dst\", \"protocol\", \"port\", "
                + "\"ingress_node_id\",\n            \"ingress_interface_name\", \"edge_mode\");"), "the A5a field set is unchanged");
    }

    @Test
    void theGatesAreNeverInTheDiagnosticReadCatalogAndThePlatformClaimsTheJob() {
        var rows = GateRegistryFixtureLoader.loadFromStream(getClass().getClassLoader()
                .getResourceAsStream("capabilities/gate_registry_fixture.yaml"));
        GateRegistryPort gates = key -> rows.stream().filter(r -> r.key().equals(key)).toList();
        for (String vendor : List.of("check_point", "palo_alto")) {
            for (String model : new String[] {null, "", "synthetic-model"}) {
                assertTrue(DiagnosticRead.commands(vendor, "gateway", model, gates).stream()
                        .noneMatch(c -> PathValidation.GATE_IDS.contains(c.gateId())), vendor);
                for (String gate : PathValidation.GATE_IDS)
                    assertTrue(DiagnosticRead.resolve(vendor, "gateway", model, gate, "203.0.113.10", gates).isEmpty(), gate);
            }
        }
        assertTrue(WorkerClaimLoop.claimCapabilities("general", false).contains(PathValidation.CAPABILITY));
        assertFalse(WorkerClaimLoop.claimCapabilities("policy", false).contains(PathValidation.CAPABILITY));
        assertEquals("DIAGNOSTIC", WorkerClaimLoop.purposeOf(PathValidation.CAPABILITY));
    }

    @Test
    void v188KeepsEveryV178ModuleMappingAndAddsPathValidationToDiagnosticsOnly() throws Exception {
        Path migrations = ui2Root().resolve("service/src/main/resources/db/migration");
        String before = caseBody(Files.readString(migrations.resolve("V178__pan_config_sync_remediation.sql")));
        String after = caseBody(Files.readString(migrations.resolve("V188__path_analysis_validation.sql")));
        assertEquals(before.replace("'cp_spike_layout_review') THEN 'diagnostics'",
                "'cp_spike_layout_review','path_validation') THEN 'diagnostics'"), after);
        try (var files = Files.list(migrations)) {
            assertEquals(List.of("V178__pan_config_sync_remediation.sql", "V188__path_analysis_validation.sql"),
                    files.map(p -> p.getFileName().toString()).filter(n -> {
                        try {
                            return Files.readString(migrations.resolve(n)).contains("CREATE OR REPLACE FUNCTION ui2_job_module")
                                    && Integer.parseInt(n.substring(1, n.indexOf("__"))) >= 178;
                        } catch (java.io.IOException e) {
                            throw new java.io.UncheckedIOException(e);
                        }
                    }).sorted().toList(), "no other mapping since V178");
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
