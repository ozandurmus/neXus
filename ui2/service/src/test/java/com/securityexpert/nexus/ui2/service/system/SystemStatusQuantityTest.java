package com.securityexpert.nexus.ui2.service.system;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class SystemStatusQuantityTest {

    @Test
    void kubernetesCpuQuantitiesBecomeMillicores() {
        assertEquals(12, SystemStatusService.milliCpu("12m"));
        assertEquals(15, SystemStatusService.milliCpu("15234567n"));
        assertEquals(1000, SystemStatusService.milliCpu("1"));
        assertEquals(250, SystemStatusService.milliCpu("0.25"));
    }

    @Test
    void kubernetesMemoryQuantitiesBecomeBytes() {
        assertEquals(2L * 1024 * 1024 * 1024, SystemStatusService.bytes("2Gi"));
        assertEquals(199L * 1024 * 1024, SystemStatusService.bytes("199Mi"));
        assertEquals(204800L * 1024, SystemStatusService.bytes("204800Ki"));
    }
}
