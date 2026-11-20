package com.securityexpert.nexus.ui2.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.platform.ScriptExecutionJob;
import com.securityexpert.nexus.ui2.worker.confirm.WorkerClaimLoop;
import com.securityexpert.nexus.ui2.worker.scriptrunner.ScriptInterpreter;
import com.securityexpert.nexus.ui2.worker.scriptrunner.ScriptRunnerClaimLoop;
import com.tngtech.archunit.core.domain.JavaClasses;

/**
 * Architectural fences for the dedicated {@code ui2-script-runner} pod and {@code script_run} job type
 * ({@code SCRIPT_EXECUTION_CONTRACT.md} sections 3.2-3.4, 5, 7 and Amendment 2026-11-18).
 */
class ScriptRunnerModuleFenceTest {

    private static JavaClasses classes;

    @BeforeAll
    static void importClasses() {
        classes = Ui2ArchitectureTest.productionClasses();
    }

    @Test
    void scriptRunnerClaimLoopClaimsOnlyScriptRunAndGeneralWorkerNeverClaimsIt() throws Exception {
        assertEquals(List.of("script_run"), ScriptRunnerClaimLoop.CAPABILITIES);
        assertEquals("script_run", ScriptExecutionJob.CAPABILITY);
        assertEquals("script", ScriptExecutionJob.TARGET_KIND);
        assertEquals("script_runner", ScriptExecutionJob.MODULE);

        Path workerLoopSource = Paths.get(System.getProperty("user.dir")).getParent()
                .resolve("worker/src/main/java/com/securityexpert/nexus/ui2/worker/confirm/WorkerClaimLoop.java");
        String source = Files.readString(workerLoopSource);
        assertFalse(source.contains("script_run") || source.contains("ScriptExecutionJob"),
                "General WorkerClaimLoop must never claim script_run; only ui2-script-runner owns script_run");
    }

    @Test
    void serviceNeverImportsTheBrokerAndOnlyTheDispatchServiceReleasesAScriptBody() throws Exception {
        noClasses().that().resideInAPackage("..service..")
                .should().dependOnClassesThat().resideInAPackage("..worker.scriptrunner..")
                .check(classes);
        Path serviceController = Paths.get(System.getProperty("user.dir")).getParent()
                .resolve("service/src/main/java/com/securityexpert/nexus/ui2/service/api/ScriptExecutionController.java");
        assertFalse(Files.readString(serviceController).contains("SCRIPT_BODY"),
                "ScriptExecutionController must never reference or retrieve SCRIPT_BODY");
    }

    /**
     * Review findings 1 and 5 (SCRIPT_EXECUTION_CONTRACT.md Amendment 2026-11-18 (b)): the broker never executes a script
     * in its own process tree (no ProcessBuilder, no Runtime.exec), so nothing a script does can read the broker's
     * environment or memory; and it holds no store key: it never touches the artefact store, the credential store, the
     * session table or the script tables. The service releases what a run needs over the internal port.
     */
    @Test
    void theBrokerSpawnsNoProcessAndHoldsNoStoreKeyOrScriptTableAccess() {
        noClasses().that().resideInAPackage("..worker.scriptrunner..")
                .should().dependOnClassesThat().haveFullyQualifiedName("java.lang.ProcessBuilder")
                .check(classes);
        noClasses().that().resideInAPackage("..worker.scriptrunner..")
                .should().callMethod(Runtime.class, "exec", String.class)
                .check(classes);
        noClasses().that().resideInAPackage("..worker.scriptrunner..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "..persistence.artefact..",
                        "..persistence.credential..",
                        "..persistence.identity..",
                        "..persistence.script..")
                .check(classes);
        noClasses().that().resideInAPackage("..worker.scriptrunner..")
                .should().dependOnClassesThat().haveSimpleNameEndingWith("StoreCipher")
                .check(classes);
    }

    @Test
    void scriptRunnerPackageNeverDependsOnDeviceTransportsOrVendorCollectors() {
        noClasses().that().resideInAPackage("..worker.scriptrunner..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "..worker.transport..",
                        "..worker.inventory..",
                        "..worker.configuration..",
                        "..worker.backup..",
                        "..worker.policy..",
                        "..worker.failover..",
                        "..worker.diagnostic..")
                .check(classes);
    }

    @Test
    void interpreterTableCoversEveryContractSection7Kind() {
        Path sample = Path.of("/app/scratch/run-1/work/script.tmp");
        Map<String, String> expectedFirstArg = Map.of(
                "sh", "bash",
                "bash", "bash",
                "py", ScriptInterpreter.PY_INTERPRETER,
                "jar", "java",
                "js", "node",
                "mjs", "node",
                "pl", "perl",
                "rb", "ruby",
                "ps1", "pwsh",
                "bin", sample.toAbsolutePath().toString());
        for (var entry : expectedFirstArg.entrySet()) {
            List<String> cmd = ScriptInterpreter.commandFor(entry.getKey(), sample);
            assertFalse(cmd.isEmpty(), entry.getKey());
            assertEquals(entry.getValue(), cmd.get(0), "Interpreter for kind " + entry.getKey());
        }
        assertTrue(ScriptInterpreter.requiresExecutableBit("bin"));
        assertFalse(ScriptInterpreter.requiresExecutableBit("sh"));
        assertFalse(ScriptInterpreter.requiresExecutableBit("py"));
    }
}
