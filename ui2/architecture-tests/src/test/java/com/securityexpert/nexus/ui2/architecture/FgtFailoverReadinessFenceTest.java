package com.securityexpert.nexus.ui2.architecture;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.capability.GateRegistryFixtureLoader;
import com.securityexpert.nexus.ui2.capability.GateRow;
import com.securityexpert.nexus.ui2.jobs.failover.FgtFailoverReadinessSwitch;
import com.securityexpert.nexus.ui2.platform.ActionClass;
import com.securityexpert.nexus.ui2.service.failover.FailoverVendor;
import com.securityexpert.nexus.ui2.worker.failover.FgtFailoverJobExecutor;

/**
 * FortiGate failover is READINESS ONLY (PO_DECISION_RECORD_2026_11_17_FGT_FAILOVER_READINESS_READS.md): the switch method
 * (diagnose sys ha reset-uptime) is design only. Nothing built here may reach a device write.
 */
class FgtFailoverReadinessFenceTest {
    private static final Set<String> WRITE_PATH = Set.of("prepareDispatch", "dispatch", "confirmDispatch", "mutationAdmission",
        "prepareResumeDispatch", "dispatchResume", "requestBound", "createRequestApproval", "approveRequest", "workerState");

    @Test void noFortigateFailoverGateRowIsAWriteOrAResetUptime() {
        List<GateRow> rows = GateRegistryFixtureLoader.loadFromStream(getClass().getClassLoader()
            .getResourceAsStream("capabilities/gate_registry_fixture.yaml"));
        for (GateRow row : rows) {
            if (row.gateId().startsWith("fgt_failover_")) assertEquals(ActionClass.CLASS_0_READ, row.actionClass(), row.gateId());
            if ("fortinet".equals(row.vendor())) {
                String key = row.canonicalCommandKey().toLowerCase(java.util.Locale.ROOT);
                assertFalse(key.contains("reset-uptime") || key.contains("failover set") || key.contains("session stat"), row.gateId());
            }
        }
    }

    @Test void v247InsertsOnlyThreeSignedOffReadRowsAndDoesNotOpenApprovalsForFortigate() throws IOException {
        String sql;
        try (var in = getClass().getClassLoader().getResourceAsStream("db/migration/V247__fgt_failover_readiness.sql")) {
            assertNotNull(in);
            sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        String body = sql.lines().filter(l -> !l.strip().startsWith("--")).collect(java.util.stream.Collectors.joining("\n"));
        assertEquals(3, body.split("'SIGNED_OFF'", -1).length - 1);
        assertEquals(3, body.split("'fortinet', 'fortigate'", -1).length - 1);
        assertEquals(3, body.split("'SSH_EXEC'", -1).length - 1);
        assertFalse(body.contains("failover_approval"), "approvals stay impossible for a FortiGate cluster");
        assertFalse(body.contains("operational-state-change") || body.contains("'write'"));
        String lower = body.toLowerCase(java.util.Locale.ROOT);
        assertFalse(lower.contains("reset-uptime") || lower.contains("session stat") || lower.contains("failover set"));
    }

    @Test void theWorkerExecutorHasNoWritePathAndTheSwitchNeverEnablesExecution() {
        var classes = Ui2ArchitectureTest.productionClasses();
        for (var type : classes) {
            if (type.getName().equals(FgtFailoverJobExecutor.class.getName()) || type.getName().startsWith(FgtFailoverJobExecutor.class.getName() + "$")) {
                for (var method : type.getMethods())
                    for (var call : method.getMethodCallsFromSelf())
                        assertFalse(call.getTargetOwner().getName().contains("JooqCpFailoverRepository") && WRITE_PATH.contains(call.getName()),
                            method.getName() + " -> " + call.getName());
                assertTrue(type.getAllRawInterfaces().stream().noneMatch(i -> i.getName().contains("FailoverDeviceExecutor")));
                assertTrue(type.getDirectDependenciesFromSelf().stream().noneMatch(d ->
                    d.getTargetClass().getName().contains("FailoverMutationSwitch")));
            }
        }
        assertFalse(FailoverVendor.FORTINET.executionAvailable());
        assertFalse(FailoverVendor.FORTINET.readinessAvailable());
        assertTrue(FgtFailoverReadinessSwitch.fromValue("on").enabled());
        assertFalse(FgtFailoverReadinessSwitch.fromValue("true").enabled());
    }

    @Test @SuppressWarnings("unchecked")
    void theFortigateRoutePrefixMapsReadsAndTheReadinessRequestOnly() throws Exception {
        var field = com.securityexpert.nexus.ui2.service.security.SecurityWebMvcConfig.class.getDeclaredField("ACTION_ID_BY_ROUTE");
        field.setAccessible(true);
        var routes = (java.util.Map<String, String>) field.get(null);
        Set<String> fgt = routes.keySet().stream().filter(r -> r.contains("/api/v2/fgt-failover")).collect(java.util.stream.Collectors.toSet());
        assertEquals(Set.of("GET /api/v2/fgt-failover/units", "GET /api/v2/fgt-failover/units/*", "GET /api/v2/fgt-failover/summary",
            "GET /api/v2/fgt-failover/runs", "GET /api/v2/fgt-failover/runs/*", "POST /api/v2/fgt-failover/units/*/readiness"), fgt);
        for (String route : fgt) assertEquals(routes.get(route.replace("fgt-failover", "asa-failover")), routes.get(route), route);
    }
}
