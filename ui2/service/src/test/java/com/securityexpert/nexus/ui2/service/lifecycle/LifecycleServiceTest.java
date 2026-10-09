package com.securityexpert.nexus.ui2.service.lifecycle;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import com.securityexpert.nexus.ui2.persistence.device.DeviceRepository;
import com.securityexpert.nexus.ui2.persistence.lifecycle.LifecycleCatalogEntry;
import com.securityexpert.nexus.ui2.persistence.lifecycle.LifecycleCatalogRepository;

class LifecycleServiceTest {
    @Test void discoveryOrParentSummaryValuesNeverSupplyOwnRuntimeProductFacts() {
        var devices = mock(DeviceRepository.class);
        var catalog = mock(LifecycleCatalogRepository.class);
        when(devices.listAll()).thenReturn(List.of(LifecycleProjectionTest.device("device-1", "check_point", "CLS-ROMEO-01")));
        when(catalog.list()).thenReturn(List.of(new LifecycleCatalogEntry("row-1", "CHECKPOINT", "HARDWARE", "Example Appliance",
                null, LocalDate.of(2030, 1, 1), null, "MANUAL", "Synthetic source", "synthetic-actor", Instant.EPOCH)));
        when(catalog.storedInventory()).thenReturn(Map.of("device-1", new LifecycleCatalogRepository.StoredInventory(null, null, null)));
        var row = new LifecycleService(catalog, devices).fleet(LocalDate.of(2026, 10, 9)).getFirst();
        assertNull(row.get("model")); assertNull(row.get("software_version"));
        assertEquals("NO_LIFECYCLE_DATA", row.get("status")); assertEquals("UNKNOWN", row.get("risk"));
        assertEquals("CLS-ROMEO-01", row.get("cluster_member_ref"));
    }
}
