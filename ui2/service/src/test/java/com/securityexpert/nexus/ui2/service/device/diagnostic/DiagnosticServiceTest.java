package com.securityexpert.nexus.ui2.service.device.diagnostic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.capability.GateRegistryFixtureLoader;
import com.securityexpert.nexus.ui2.capability.GateRow;
import com.securityexpert.nexus.ui2.capability.SignOffState;
import com.securityexpert.nexus.ui2.jobs.diagnostic.DiagnosticRead;
import com.securityexpert.nexus.ui2.platform.ActionClass;

import com.securityexpert.nexus.ui2.jobs.admission.JobAdmissionService;
import com.securityexpert.nexus.ui2.persistence.device.DeviceRecord;
import com.securityexpert.nexus.ui2.persistence.device.DeviceRepository;
import com.securityexpert.nexus.ui2.persistence.device.DeviceSummaryRecord;
import com.securityexpert.nexus.ui2.persistence.device.EndpointRecord;
import com.securityexpert.nexus.ui2.persistence.device.inventory.DeviceInventoryRepository;
import com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryContext;
import com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryInterface;
import com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryRun;
import com.securityexpert.nexus.ui2.persistence.jobrecords.JobRecordDao;
import com.securityexpert.nexus.ui2.platform.DeviceEnrollmentState;
import com.securityexpert.nexus.ui2.service.privacy.TopologyNamePseudonymizer;

class DiagnosticServiceTest {
    @Test
    void pendingCpviewGateCannotCreateJobEvenForPlainMember() {
        var jobs = mock(JobRecordDao.class);
        var service = cpviewService(SignOffState.DRAFTED, jobs);
        var result = service.submitRead("device-1", DiagnosticRead.CPVIEW_GATE, null,
                java.util.UUID.randomUUID().toString(), "synthetic-actor", true);
        assertEquals("DIAGNOSTIC_RUN_NOT_PERMITTED",
                ((com.securityexpert.nexus.ui2.jobs.admission.AdmissionResult.Refused) result).code());
        verifyNoInteractions(jobs);
    }

    @Test
    void signedOffCpviewGateAdmitsExactlyOnePlainMemberMeasurement() {
        var jobs = mock(JobRecordDao.class);
        var service = cpviewService(SignOffState.SIGNED_OFF, jobs);
        String requestId = java.util.UUID.randomUUID().toString();
        when(jobs.insertDiagnosticRead(anyString(), eq("diagnostic:" + requestId), eq("device-1"),
                eq(DiagnosticRead.CPVIEW_GATE), eq("cpview -p"), eq("synthetic-actor")))
                .thenReturn(new JobRecordDao.DiagnosticAdmission("ADMITTED", "job-1"));

        var result = service.submitRead("device-1", DiagnosticRead.CPVIEW_GATE, null,
                requestId, "synthetic-actor", true);

        assertEquals(new com.securityexpert.nexus.ui2.jobs.admission.AdmissionResult.Admitted("job-1"), result);
        verify(jobs).insertDiagnosticRead(anyString(), eq("diagnostic:" + requestId), eq("device-1"),
                eq(DiagnosticRead.CPVIEW_GATE), eq("cpview -p"), eq("synthetic-actor"));
        verifyNoMoreInteractions(jobs);
    }

    private DiagnosticService cpviewService(SignOffState state, JobRecordDao jobs) {
        var devices = mock(DeviceRepository.class);
        var inventory = mock(DeviceInventoryRepository.class);
        when(devices.find("device-1")).thenReturn(Optional.of(new DeviceRecord("device-1", "gateway", "check_point",
                "manual", Instant.now(), false, DeviceEnrollmentState.ENROLLED, false, "synthetic-reference")));
        when(devices.findEndpointByDeviceId("device-1")).thenReturn(Optional.of(new EndpointRecord(
                "endpoint-1", "device-1", "ssh_exec", "192.0.2.10", Instant.now())));
        when(devices.findSummary("device-1")).thenReturn(Optional.of(new DeviceSummaryRecord("device-1", "gateway", "check_point",
                DeviceEnrollmentState.ENROLLED, Optional.empty(), Optional.of("invented-appliance"), Optional.empty(), Optional.empty(), Optional.of("opaque-cluster"))));
        when(inventory.findLatestRun("device-1")).thenReturn(Optional.of(new InventoryRun("run-1", "device-1", "old-job", Instant.now(), 1,
                List.of(new InventoryContext("physical", List.of(), List.of())), List.of(), Optional.empty())));
        var row = cpviewGate(state);
        return new DiagnosticService(devices, inventory, null, jobs,
                new TopologyNamePseudonymizer("synthetic-test-key".getBytes()),
                key -> row.key().equals(key) ? List.of(row) : List.of(),
                new com.securityexpert.nexus.ui2.service.boot.DeviceCompositionConfiguration.ArtefactStoreAccess(
                        mock(com.securityexpert.nexus.ui2.persistence.artefact.ArtefactStore.class)), null, null);
    }

    private GateRow cpviewGate(SignOffState state) {
        return new GateRow(DiagnosticRead.CPVIEW_GATE, "check_point", "cp_gaia_gateway", "expert", "SSH_EXEC",
                "cpview -p", ActionClass.CLASS_0_READ, state, 30, "none", "one measurement; no polling",
                "one trusted SSH session; no retry", "TIMEOUT or UNKNOWN", "masked projection only",
                List.of("sectionNames", "fieldNames", "valueTypes", "units", "allowlistedCounters", "scopeMarkerPresent"),
                "synthetic-test-gate");
    }

    @Test
    void virtualSystemFilteringIsPerDeviceAndPreservesProfileMemo() {
        var devices=mock(DeviceRepository.class);
        var inventory=mock(DeviceInventoryRepository.class);
        // Cpview eligibility checks are separate from VS filtering and profile memoisation.
        var rows=GateRegistryFixtureLoader.loadFromStream(getClass().getClassLoader()
            .getResourceAsStream("capabilities/gate_registry_fixture.yaml")).stream()
            .filter(row -> !DiagnosticRead.CPVIEW_GATE.equals(row.gateId())).toList();
        var lookups=new java.util.HashMap<com.securityexpert.nexus.ui2.capability.CanonicalCommandKey,Integer>();
        var service=new DiagnosticService(devices,inventory,null,mock(JobRecordDao.class),
            new TopologyNamePseudonymizer("synthetic-test-key".getBytes()),key -> {
                lookups.merge(key,1,Integer::sum);
                return rows.stream().filter(r -> r.key().equals(key)).toList();
            },new com.securityexpert.nexus.ui2.service.boot.DeviceCompositionConfiguration.ArtefactStoreAccess(null),null,null);
        var summaries=new java.util.ArrayList<DeviceSummaryRecord>();
        var ids = new java.util.ArrayList<>(List.of("plain-first","vs-middle","plain-last"));
        for (int i=3;i<81;i++) ids.add("plain-"+i);
        for (String id:ids) {
            summaries.add(new DeviceSummaryRecord(id,"gateway","check_point",DeviceEnrollmentState.ENROLLED,
                Optional.of("FW-TANGO-04"),Optional.of("synthetic-gaia"),Optional.empty(),Optional.empty(),Optional.empty()));
        }
        when(devices.listReadCollectionTargets("ssh_exec")).thenReturn(summaries);
        when(inventory.findLatestContextIdsByDevice()).thenReturn(java.util.Map.of(
            "plain-first",List.of("physical"),"vs-middle",List.of("physical","001"),"plain-last",List.of("physical")));
        var targets=service.targets(true);
        assertEquals(81,targets.size());
        for(var target:targets) {
            assertTrue(target.commands().stream().anyMatch(c -> "cp_failover_syncstat".equals(c.get("gate_id"))));
            assertEquals(target.deviceId().equals("vs-middle"),target.commands().stream()
                .anyMatch(c -> ((String)c.get("command_template")).contains("<VSID>")));
        }
        assertEquals(List.of("001"),targets.get(1).virtualSystems());
        org.mockito.Mockito.verify(inventory).findLatestContextIdsByDevice();
        org.mockito.Mockito.verify(inventory,org.mockito.Mockito.never()).findLatestContextIds(org.mockito.ArgumentMatchers.anyString());
        org.mockito.Mockito.verify(devices,org.mockito.Mockito.never()).find(org.mockito.ArgumentMatchers.anyString());
        org.mockito.Mockito.verify(devices,org.mockito.Mockito.never()).findEndpointByDeviceId(org.mockito.ArgumentMatchers.anyString());
        assertFalse(lookups.isEmpty());
        assertTrue(lookups.values().stream().allMatch(count -> count==1));
    }

    @Test
    void storedSparkPlatformOffersIdentityReadsWithAnOpaqueModelToken() {
        var devices = mock(DeviceRepository.class);
        var platform = mock(com.securityexpert.nexus.ui2.persistence.device.DevicePlatformFactsRepository.class);
        var rows = GateRegistryFixtureLoader.loadFromStream(getClass().getClassLoader()
                .getResourceAsStream("capabilities/gate_registry_fixture.yaml"));
        var inventory = mock(DeviceInventoryRepository.class);
        when(inventory.findLatestContextIdsByDevice()).thenReturn(java.util.Map.of("device-1",List.of("physical", "001")));
        var service = new DiagnosticService(devices, inventory, null,
                mock(JobRecordDao.class), new TopologyNamePseudonymizer("synthetic-test-key".getBytes()),
                key -> rows.stream().filter(row -> row.key().equals(key)).toList(),
                new com.securityexpert.nexus.ui2.service.boot.DeviceCompositionConfiguration.ArtefactStoreAccess(null),
                null, null, platform);
        var summary = new DeviceSummaryRecord("device-1", "gateway", "check_point", DeviceEnrollmentState.ENROLLED,
                Optional.of("FW-TANGO-04"), Optional.of("V0"), Optional.empty(), Optional.empty(), Optional.of("synthetic-cluster"));
        when(devices.listAll()).thenReturn(List.of(summary));
        when(devices.listReadCollectionTargets("ssh_exec")).thenReturn(List.of(summary));
        when(devices.find("device-1")).thenReturn(Optional.of(new DeviceRecord("device-1", "gateway", "check_point",
                "manual", Instant.now(), false, DeviceEnrollmentState.ENROLLED, false, "credential-ref")));
        when(devices.findEndpointByDeviceId("device-1")).thenReturn(Optional.of(
                new EndpointRecord("endpoint-1", "device-1", "ssh_exec", "192.0.2.10", Instant.now())));
        when(platform.findAll()).thenReturn(java.util.Map.of("device-1",new com.securityexpert.nexus.ui2.persistence.device.DevicePlatformFacts(
                "device-1", Optional.empty(), Optional.empty(), Optional.of("gaia_embedded"),
                java.util.Map.of(), Optional.empty(), "cp_spark_show_diag_software_version", Optional.empty())));

        var target = service.targets(true).get(0);
        assertEquals(List.of("001"), target.virtualSystems());
        assertTrue(target.cluster().startsWith("CLS-"));
        assertFalse(target.cluster().contains("synthetic-cluster"));
        var commands = target.commands();
        assertTrue(commands.stream().anyMatch(command -> "System diagnostics".equals(command.get("description"))));
        assertTrue(commands.stream().anyMatch(command -> "cp_spark_show_diag".equals(command.get("gate_id"))));
        assertTrue(commands.stream().anyMatch(command -> "cp_spark_show_software_version".equals(command.get("gate_id"))));
    }

    @Test
    void retainedOutputIsAuthorizedSeparatelyFromHistoryAndMaskedForAi() throws Exception {
        var devices=mock(DeviceRepository.class);
        var jobs=mock(JobRecordDao.class);
        var store=mock(com.securityexpert.nexus.ui2.persistence.artefact.ArtefactStore.class);
        var identities=mock(com.securityexpert.nexus.ui2.service.security.LocalIdentityResolver.class);
        byte[] key="synthetic-test-key".getBytes();
        var service=new DiagnosticService(devices,mock(DeviceInventoryRepository.class),mock(JobAdmissionService.class),jobs,
            new TopologyNamePseudonymizer(key), k -> List.of(),
            new com.securityexpert.nexus.ui2.service.boot.DeviceCompositionConfiguration.ArtefactStoreAccess(store),identities,
            new com.securityexpert.nexus.ui2.service.privacy.SubnetPreservingIpMasker(key));
        String id="00000000-0000-0000-0000-000000000001";
        var job=new JobRecordDao.DiagnosticJob(id,"device-1",null,"COMPLETED",null,false,4,null,null,
            "get system status","actor",Instant.now(),0);
        when(jobs.findDiagnostic(id)).thenReturn(Optional.of(job));
        when(jobs.diagnosticOutput(id,"actor")).thenReturn(Optional.of(new JobRecordDao.DiagnosticOutputRef("opaque-output",new byte[]{1})));
        when(store.retrieve(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.eq(false)))
            .thenAnswer(call -> new java.io.ByteArrayInputStream("Hostname: synthetic-private-name\nStatus: UP\nAddress: 192.0.2.44\npassword: synthetic-secret".getBytes()));
        when(devices.listAll()).thenReturn(List.of());
        String admin=(String)service.output(id,"actor",false).orElseThrow().get("output");
        assertTrue(admin.contains("synthetic-private-name"));
        assertFalse(admin.contains("synthetic-secret"));
        String ai=(String)service.output(id,"actor",true).orElseThrow().get("output");
        assertTrue(ai.contains("Status: UP"));
        assertFalse(ai.contains("synthetic-private-name"));
        assertFalse(ai.contains("192.0.2.44"));
        assertFalse(service.output(id,"actor",true).orElseThrow().containsKey("wrappedKey"));
    }

    @Test
    void projectsQueuePositionAndMeasuredExecutionDuration() {
        var jobs = mock(JobRecordDao.class);
        var service = new DiagnosticService(mock(DeviceRepository.class), mock(DeviceInventoryRepository.class), null, jobs,
                new TopologyNamePseudonymizer("synthetic-test-key".getBytes()), k -> List.of(),
                new com.securityexpert.nexus.ui2.service.boot.DeviceCompositionConfiguration.ArtefactStoreAccess(null),
                mock(com.securityexpert.nexus.ui2.service.security.LocalIdentityResolver.class), null);
        String id = "00000000-0000-0000-0000-000000000001";
        Instant submitted = Instant.parse("2026-09-30T10:00:00Z");
        when(jobs.findDiagnostic(id)).thenReturn(Optional.of(new JobRecordDao.DiagnosticJob(id, "device-1", null,
                "REQUESTED", null, false, null, null, null, "get system status", "actor", submitted, null,
                "fgt_get_system_status")));
        when(jobs.diagnosticQueuePosition(id)).thenReturn(Optional.of(2));
        var queued = service.output(id, "actor", true).orElseThrow();
        assertEquals(2, queued.get("queuePosition"));
        assertEquals(null, queued.get("durationMs"));
        when(jobs.findDiagnostic(id)).thenReturn(Optional.of(new JobRecordDao.DiagnosticJob(id, "device-1", null,
                "COMPLETED", null, false, null, null, null, "get system status", "actor", submitted, 0,
                "fgt_get_system_status", submitted.plusSeconds(10), submitted.plusSeconds(12))));
        var done = service.output(id, "actor", true).orElseThrow();
        assertEquals(null, done.get("queuePosition"));
        assertEquals(2000L, done.get("durationMs"));
        assertEquals("System version and status", done.get("description"));
    }

    @Test
    void onlyAnInventoriedPhysicalPortCanReachAdmission() {
        DeviceRepository devices = mock(DeviceRepository.class);
        DeviceInventoryRepository inventory = mock(DeviceInventoryRepository.class);
        JobAdmissionService admission = mock(JobAdmissionService.class);
        List<GateRow> gates = GateRegistryFixtureLoader.loadFromStream(getClass().getClassLoader()
                .getResourceAsStream("capabilities/gate_registry_fixture.yaml"));
        var service = new DiagnosticService(devices, inventory, admission, mock(JobRecordDao.class),
                new TopologyNamePseudonymizer("synthetic-test-key".getBytes()),
                key -> gates.stream().filter(row -> row.key().equals(key)).toList(),
                new com.securityexpert.nexus.ui2.service.boot.DeviceCompositionConfiguration.ArtefactStoreAccess(null),
                mock(com.securityexpert.nexus.ui2.service.security.LocalIdentityResolver.class),
                mock(com.securityexpert.nexus.ui2.service.privacy.SubnetPreservingIpMasker.class));
        when(devices.find("device-1")).thenReturn(Optional.of(new DeviceRecord("device-1", "management_server", "fortinet",
                "manual", Instant.now(), false, DeviceEnrollmentState.ENROLLED, false, "credential-ref")));
        var iface = new InventoryInterface("if-1", "port5", Optional.empty(), "physical", "unknown", List.of());
        when(inventory.findLatestRun("device-1")).thenReturn(Optional.of(new InventoryRun("run-1", "device-1", "job-1",
                Instant.now(), 1, List.of(new InventoryContext("physical", List.of(iface), List.of())))));

        assertTrue(service.preview("device-1", "port5").isPresent());
        assertEquals(List.of("port5"), service.ports("device-1"));
        assertEquals("diagnose fmnetwork interface detail port5", service.preview("device-1", "port5").get().command());
        assertFalse(service.preview("device-1", "port5; execute factoryreset").isPresent());
        assertFalse(service.preview("device-1", "port6").isPresent());
        assertEquals("DIAGNOSTIC_TARGET_UNAVAILABLE", ((com.securityexpert.nexus.ui2.jobs.admission.AdmissionResult.Refused)
                service.submit("device-1", "port6", "00000000-0000-0000-0000-000000000001", "actor")).code());
        verifyNoInteractions(admission);

        DeviceSummaryRecord summary = mock(DeviceSummaryRecord.class);
        when(summary.deviceId()).thenReturn("device-1");
        when(summary.vendorHint()).thenReturn("fortinet");
        when(summary.role()).thenReturn("management_server");
        when(summary.observedHostname()).thenReturn(Optional.of("synthetic-device-name"));
        when(summary.observedModel()).thenReturn(Optional.empty());
        when(devices.listAll()).thenReturn(List.of(summary));
        when(devices.listReadCollectionTargets("ssh_exec")).thenReturn(List.of(summary));
        when(devices.findEndpointByDeviceId("device-1")).thenReturn(Optional.of(
            new EndpointRecord("endpoint-1","device-1","ssh_exec","192.0.2.10",Instant.now())));
        String projected = service.targets(true).get(0).target();
        assertTrue(projected.startsWith("FW-"));
        assertFalse(projected.contains("synthetic-device-name"));
        assertEquals("synthetic-device-name", service.targets(false).get(0).target());
        when(summary.vendorHint()).thenReturn("cisco");
        when(summary.role()).thenReturn("gateway");
        assertEquals(1, service.targets(false).size());
    }

    @Test
    void targetCommandsAndAdmissionFollowDeviceScopeAndRejectUnknownGateBeforeJobCreation() {
        var devices=mock(DeviceRepository.class);
        var jobs=mock(JobRecordDao.class);
        var rows=GateRegistryFixtureLoader.loadFromStream(getClass().getClassLoader()
            .getResourceAsStream("capabilities/gate_registry_fixture.yaml"));
        var registryReady = new java.util.concurrent.atomic.AtomicBoolean(true);
        var service=new DiagnosticService(devices,mock(DeviceInventoryRepository.class),mock(JobAdmissionService.class),jobs,
            new TopologyNamePseudonymizer("synthetic-test-key".getBytes()),
            key -> registryReady.get() ? rows.stream().filter(row -> row.key().equals(key)).toList() : List.of(),
            new com.securityexpert.nexus.ui2.service.boot.DeviceCompositionConfiguration.ArtefactStoreAccess(
                mock(com.securityexpert.nexus.ui2.persistence.artefact.ArtefactStore.class)),
            mock(com.securityexpert.nexus.ui2.service.security.LocalIdentityResolver.class),
            mock(com.securityexpert.nexus.ui2.service.privacy.SubnetPreservingIpMasker.class));
        var device=new DeviceRecord("device-1","gateway","fortinet","manual",Instant.now(),false,
            DeviceEnrollmentState.ENROLLED,false,"credential-ref");
        when(devices.find("device-1")).thenReturn(Optional.of(device));
        when(devices.findEndpointByDeviceId("device-1")).thenReturn(Optional.of(
            new EndpointRecord("endpoint-1","device-1","ssh_exec","192.0.2.10",Instant.now())));
        var summary=mock(DeviceSummaryRecord.class);
        when(summary.deviceId()).thenReturn("device-1");
        when(summary.vendorHint()).thenReturn("fortinet");
        when(summary.role()).thenReturn("gateway");
        when(summary.observedHostname()).thenReturn(Optional.of("synthetic-device-name"));
        when(summary.observedModel()).thenReturn(Optional.empty());
        when(devices.listAll()).thenReturn(List.of(summary));
        when(devices.listReadCollectionTargets("ssh_exec")).thenReturn(List.of(summary));
        when(devices.findSummary("device-1")).thenReturn(Optional.of(summary));
        var commands=service.targets(true).get(0).commands();
        assertTrue(commands.stream().anyMatch(c -> "fgt_get_system_status".equals(c.get("gate_id"))));
        assertTrue(commands.stream().allMatch(c -> Boolean.TRUE.equals(c.get("runnable"))));
        assertFalse(commands.stream().anyMatch(c -> String.valueOf(c.get("gate_id")).startsWith("cp_")));
        assertFalse(commands.stream().anyMatch(c -> "fgt_context_moves".equals(c.get("gate_id"))));
        String requestId="00000000-0000-0000-0000-000000000001";
        assertTrue(service.submitRead("device-1","unknown_gate",null,requestId,"actor")
            instanceof com.securityexpert.nexus.ui2.jobs.admission.AdmissionResult.Refused);
        assertTrue(service.submitRead("device-1","fgt_execute_ha_manage_list","bad;token",requestId,"actor")
            instanceof com.securityexpert.nexus.ui2.jobs.admission.AdmissionResult.Refused);
        for (var row : rows) {
            if (row.actionClass() != com.securityexpert.nexus.ui2.platform.ActionClass.CLASS_0_READ) {
                var refused = (com.securityexpert.nexus.ui2.jobs.admission.AdmissionResult.Refused)
                        service.submitRead("device-1", row.gateId(), null, requestId, "actor", true);
                assertEquals("DIAGNOSTIC_RUN_NOT_PERMITTED", refused.code(), row.gateId());
            }
        }
        assertEquals("DIAGNOSTIC_RUN_NOT_PERMITTED", ((com.securityexpert.nexus.ui2.jobs.admission.AdmissionResult.Refused)
                service.submitRead("device-1", "unknown_gate", null, requestId, "actor", true)).code());
        registryReady.set(false);
        assertEquals("DIAGNOSTIC_RUN_NOT_PERMITTED", ((com.securityexpert.nexus.ui2.jobs.admission.AdmissionResult.Refused)
                service.submitRead("device-1", "fgt_get_system_status", null, requestId, "actor", true)).code());
        registryReady.set(true);
        verifyNoInteractions(jobs);
        when(jobs.insertDiagnosticRead(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.eq("diagnostic:" + requestId),
                org.mockito.ArgumentMatchers.eq("device-1"), org.mockito.ArgumentMatchers.eq("fgt_get_system_status"),
                org.mockito.ArgumentMatchers.eq("get system status"), org.mockito.ArgumentMatchers.eq("actor")))
                .thenReturn(new JobRecordDao.DiagnosticAdmission("ADMITTED", "job-1"));
        assertTrue(service.submitRead("device-1", "fgt_get_system_status", null, requestId, "actor", true)
                instanceof com.securityexpert.nexus.ui2.jobs.admission.AdmissionResult.Admitted);
    }
}
