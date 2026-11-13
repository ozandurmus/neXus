package com.securityexpert.nexus.ui2.architecture;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.capability.GateRegistryFixtureLoader;
import com.securityexpert.nexus.ui2.capability.GateRow;
import com.securityexpert.nexus.ui2.jobs.failover.AsaFailoverReadinessSwitch;
import com.securityexpert.nexus.ui2.platform.ActionClass;
import com.securityexpert.nexus.ui2.service.failover.CpFailoverService;
import com.securityexpert.nexus.ui2.service.failover.FailoverVendor;
import com.securityexpert.nexus.ui2.worker.failover.AsaFailoverJobExecutor;
import com.tngtech.archunit.core.importer.ClassFileImporter;

/**
 * Cisco ASA failover is READINESS ONLY (PO_DECISION_REQUEST_2026_11_13_ASA_FAILOVER_READINESS.md): the ASA switch
 * ({@code no failover active}) waits for the decision council. Nothing built here may reach a device write.
 */
class AsaFailoverReadinessFenceTest {
    private static final Set<String> WRITE_PATH = Set.of("prepareDispatch", "dispatch", "confirmDispatch", "mutationAdmission",
        "prepareResumeDispatch", "dispatchResume", "requestBound", "createRequestApproval", "approveRequest", "workerState");

    @Test void noCiscoAsaGateRowHasAnActionClassOtherThanRead() {
        List<GateRow> rows = GateRegistryFixtureLoader.loadFromStream(getClass().getClassLoader()
            .getResourceAsStream("capabilities/gate_registry_fixture.yaml"));
        var asa = rows.stream().filter(r -> "cisco_asa".equals(r.vendor())).toList();
        assertTrue(asa.size() >= 8, "the inventory, measurement and readiness rows");
        // The failover rows (the one-time measurement and the readiness reads) are reads, and no Cisco ASA row is a failover
        // write: the only recovery writes are the backup archive's own create and delete of its file on the device's flash.
        for (GateRow row : asa) {
            if (row.gateId().startsWith("asa_failover_") || row.gateId().startsWith("asa_measure_"))
                assertEquals(ActionClass.CLASS_0_READ, row.actionClass(), row.gateId());
            else if (row.actionClass() != ActionClass.CLASS_0_READ)
                assertTrue(Set.of("asa_backup_archive", "asa_delete_archive").contains(row.gateId()), row.gateId());
            assertFalse(row.canonicalCommandKey().toLowerCase(java.util.Locale.ROOT).contains("failover active"), row.gateId());
            assertFalse(row.violatesWriteMarkerDenylist(), row.gateId());
        }
    }

    @Test void v212InsertsOnlyDraftedReadRowsAndDoesNotOpenApprovalsForAsa() throws IOException {
        String sql;
        try (var in = getClass().getClassLoader().getResourceAsStream("db/migration/V212__asa_failover_readiness.sql")) {
            assertNotNull(in);
            sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertFalse(sql.contains("SIGNED_OFF"), "a DRAFTED row never resolves KNOWN");
        assertEquals(4, sql.split("'DRAFTED'", -1).length - 1);
        assertEquals(4, sql.split("'cisco_asa', 'cisco_asa_firewall'", -1).length - 1);
        assertEquals(4, sql.split("'SSH_INTERACTIVE'", -1).length - 1);
        assertFalse(sql.contains("failover_approval"), "approvals stay impossible for a Cisco ASA pair");
        assertFalse(sql.contains("operational-state-change") || sql.contains("'write'"));
        assertFalse(sql.toLowerCase(java.util.Locale.ROOT).contains("failover active"));
    }

    @Test void theWorkerExecutorHasNoWritePathAndTheSwitchNeverEnablesExecution() {
        var classes = new ClassFileImporter().importClasses(AsaFailoverJobExecutor.class, CpFailoverService.class);
        for (var type : classes) {
            if (type.getName().equals(AsaFailoverJobExecutor.class.getName()) || type.getName().startsWith(AsaFailoverJobExecutor.class.getName() + "$")) {
                for (var method : type.getMethods())
                    for (var call : method.getMethodCallsFromSelf())
                        assertFalse(call.getTargetOwner().getName().contains("JooqCpFailoverRepository") && WRITE_PATH.contains(call.getName()),
                            method.getName() + " -> " + call.getName());
                assertTrue(type.getAllRawInterfaces().stream().noneMatch(i -> i.getName().contains("FailoverDeviceExecutor")));
                assertTrue(type.getDirectDependenciesFromSelf().stream().noneMatch(d ->
                    d.getTargetClass().getName().contains("FailoverMutationSwitch")));
            }
        }
        // Readiness only: the vendor descriptor never offers execution for ASA, with the switch on or off.
        assertFalse(FailoverVendor.CISCO_ASA.executionAvailable());
        assertFalse(FailoverVendor.CISCO_ASA.readinessAvailable());
        assertTrue(AsaFailoverReadinessSwitch.fromValue("on").enabled());
        assertFalse(AsaFailoverReadinessSwitch.fromValue("true").enabled());
    }

    @Test @SuppressWarnings("unchecked")
    void theAsaRoutePrefixMapsReadsAndTheReadinessRequestOnly() throws Exception {
        var field = com.securityexpert.nexus.ui2.service.security.SecurityWebMvcConfig.class.getDeclaredField("ACTION_ID_BY_ROUTE");
        field.setAccessible(true);
        var routes = (java.util.Map<String, String>) field.get(null);
        Set<String> asa = routes.keySet().stream().filter(r -> r.contains("/api/v2/asa-failover")).collect(java.util.stream.Collectors.toSet());
        assertEquals(Set.of("GET /api/v2/asa-failover/units", "GET /api/v2/asa-failover/units/*", "GET /api/v2/asa-failover/summary",
            "GET /api/v2/asa-failover/runs", "GET /api/v2/asa-failover/runs/*", "POST /api/v2/asa-failover/units/*/readiness"), asa);
    }
}
