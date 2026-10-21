package com.securityexpert.nexus.ui2.architecture;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.jobs.failover.FailoverMutationSwitch;
import com.securityexpert.nexus.ui2.persistence.JooqCpFailoverRepository;
import com.securityexpert.nexus.ui2.service.api.CpFailoverController;
import com.securityexpert.nexus.ui2.service.failover.CpFailoverService;
import com.securityexpert.nexus.ui2.worker.failover.CpFailoverJobExecutor;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;

/**
 * CP_FAILOVER_MEMBER_RECOVERY_CONTRACT.md §7 group 10: the resumed {@code clusterXL_admin up} is reachable only from
 * the resume route, through the service, the one repository admission/intent/send path and the failover worker. The
 * console, script runs, readiness and the generic failover routes cannot reach it.
 */
class FailoverResumeFenceTest {
    private static JavaClasses classes;

    @BeforeAll
    static void importClasses() {
        classes = new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.securityexpert.nexus.ui2");
        assertFalse(classes.isEmpty());
    }

    private static Set<String> callersOf(Class<?> owner, String method) {
        return classes.stream().flatMap(c -> c.getMethodCallsFromSelf().stream())
            .filter(call -> call.getTarget().getOwner().getName().equals(owner.getName())
                && call.getTarget().getName().equals(method))
            .map(call -> call.getOriginOwner().getName()).collect(Collectors.toSet());
    }
    private static void mustCall(Class<?> owner, String method, Class<?> target, String targetMethod) {
        JavaClass type = classes.get(owner);
        assertTrue(type.getMethods().stream().filter(m -> m.getName().equals(method))
            .anyMatch(m -> m.getMethodCallsFromSelf().stream().anyMatch(call ->
                call.getTarget().getOwner().getName().equals(target.getName())
                    && call.getTarget().getName().equals(targetMethod))), owner.getSimpleName() + "#" + method);
    }

    @Test void onlyTheResumeRouteServiceAndFailoverWorkerReachTheResumedWrite() {
        Map<String, Set<String>> allowed = Map.of(
            "requestResume", Set.of(CpFailoverService.class.getName()),
            "approveResume", Set.of(CpFailoverService.class.getName()),
            "resumeAdmission", Set.of(CpFailoverJobExecutor.class.getName()),
            "prepareResumeDispatch", Set.of(CpFailoverJobExecutor.class.getName()),
            "dispatchResume", Set.of(CpFailoverJobExecutor.class.getName()),
            "resumeState", Set.of(CpFailoverJobExecutor.class.getName()));
        allowed.forEach((method, owners) -> assertEquals(owners, callersOf(JooqCpFailoverRepository.class, method), method));
        assertEquals(Set.of(CpFailoverController.class.getName()), callersOf(CpFailoverService.class, "resumeReturn"));
        assertEquals(Set.of(CpFailoverController.class.getName()), callersOf(CpFailoverService.class, "approveResume"));
    }

    @Test void theResumeChecksTheSwitchAndTheDurableAdmissionBeforeContactAndBeforeTheWrite() {
        mustCall(CpFailoverService.class, "resumeReturn", FailoverMutationSwitch.class, "enabled");
        mustCall(CpFailoverService.class, "approveResume", FailoverMutationSwitch.class, "enabled");
        mustCall(CpFailoverJobExecutor.class, "resume", FailoverMutationSwitch.class, "enabled");
        mustCall(CpFailoverJobExecutor.class, "resume", CpFailoverJobExecutor.class, "requireResumeAdmission");
        mustCall(CpFailoverJobExecutor.class, "requireResumeAdmission", JooqCpFailoverRepository.class, "resumeAdmission");
        mustCall(CpFailoverJobExecutor.class, "requireMutationAdmission", CpFailoverJobExecutor.class, "requireResumeAdmission");
        mustCall(CpFailoverJobExecutor.class, "command", JooqCpFailoverRepository.class, "prepareResumeDispatch");
        mustCall(CpFailoverJobExecutor.class, "command", JooqCpFailoverRepository.class, "dispatchResume");
        mustCall(CpFailoverJobExecutor.class, "state", JooqCpFailoverRepository.class, "resumeState");
        mustCall(CpFailoverJobExecutor.class, "stop", JooqCpFailoverRepository.class, "resumeState");
    }

    @Test void readinessNeverWritesAndTheResumeWriteIsTheRunsOwnUp() throws Exception {
        var writeAllowed = CpFailoverJobExecutor.class.getDeclaredMethod("writeAllowed", String.class, String.class);
        writeAllowed.setAccessible(true);
        assertEquals(false, writeAllowed.invoke(null, "READINESS", "clusterXL_admin up"));
        assertEquals(false, writeAllowed.invoke(null, "READINESS", "clusterXL_admin down"));
        var up = CpFailoverJobExecutor.class.getDeclaredField("UP");
        up.setAccessible(true);
        assertEquals("clusterXL_admin up", up.get(null), "the command string is unchanged (gate cp_failover_up, V101)");
    }
}
