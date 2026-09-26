package com.securityexpert.nexus.ui2.service.device.diagnostic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.capability.GateRegistryFixtureLoader;
import com.securityexpert.nexus.ui2.capability.GateRow;

import com.securityexpert.nexus.ui2.jobs.admission.JobAdmissionService;
import com.securityexpert.nexus.ui2.persistence.device.DeviceRecord;
import com.securityexpert.nexus.ui2.persistence.device.DeviceRepository;
import com.securityexpert.nexus.ui2.persistence.device.DeviceSummaryRecord;
import com.securityexpert.nexus.ui2.persistence.device.inventory.DeviceInventoryRepository;
import com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryContext;
import com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryInterface;
import com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryRun;
import com.securityexpert.nexus.ui2.persistence.jobrecords.JobRecordDao;
import com.securityexpert.nexus.ui2.platform.DeviceEnrollmentState;
import com.securityexpert.nexus.ui2.service.privacy.TopologyNamePseudonymizer;

class DiagnosticServiceTest {
    @Test
    void onlyAnInventoriedPhysicalPortCanReachAdmission() {
        DeviceRepository devices = mock(DeviceRepository.class);
        DeviceInventoryRepository inventory = mock(DeviceInventoryRepository.class);
        JobAdmissionService admission = mock(JobAdmissionService.class);
        List<GateRow> gates = GateRegistryFixtureLoader.loadFromStream(getClass().getClassLoader()
                .getResourceAsStream("capabilities/gate_registry_fixture.yaml"));
        var service = new DiagnosticService(devices, inventory, admission, mock(JobRecordDao.class),
                new TopologyNamePseudonymizer("synthetic-test-key".getBytes()),
                key -> gates.stream().filter(row -> row.key().equals(key)).toList());
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
        when(devices.listAll()).thenReturn(List.of(summary));
        String projected = service.targets(true).get(0).target();
        assertTrue(projected.startsWith("FW-"));
        assertFalse(projected.contains("synthetic-device-name"));
        assertEquals("synthetic-device-name", service.targets(false).get(0).target());
        when(summary.vendorHint()).thenReturn("cisco");
        when(summary.role()).thenReturn("gateway");
        assertEquals(1, service.targets(false).size());
    }
}
