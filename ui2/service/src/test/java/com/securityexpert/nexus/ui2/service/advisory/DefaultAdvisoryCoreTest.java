package com.securityexpert.nexus.ui2.service.advisory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class DefaultAdvisoryCoreTest {

    private final AdvisoryCore core = new DefaultAdvisoryCore(List.of(new PaloAltoAdvisoryParser()));

    @Test
    void productKeyMapping() {
        assertEquals(Optional.of("paloalto:pan-os"), core.productKey("palo_alto", "gateway", "PA-440"));
        assertEquals(Optional.of("paloalto:pan-os"), core.productKey("palo_alto", "management_server", "Panorama"));
        assertEquals(Optional.of("fortinet:fortios"), core.productKey("fortinet", "gateway", "FortiGate-60F"));
        assertEquals(Optional.of("fortinet:fortimanager"), core.productKey("fortinet", "management_server", "x"));
        assertEquals(Optional.of("fortinet:fortimanager"), core.productKey("fortinet", "appliance", "FortiManager-VM64"));
        assertEquals(Optional.of("fortinet:fortimanager"), core.productKey("fortinet", null, "fmg-200g"));
        assertEquals(Optional.of("fortinet:fortios"), core.productKey("fortinet", null, null));
        assertEquals(Optional.of("cisco:asa"), core.productKey("cisco_asa", "gateway", "ASA5516"));
        assertEquals(Optional.empty(), core.productKey("check_point", "gateway", "x"));
        assertEquals(Optional.empty(), core.productKey(null, null, null));
    }

    @Test
    void lookups() {
        assertTrue(core.parser("paloalto").isPresent());
        assertTrue(core.parser("fortinet").isEmpty());
        assertTrue(core.parser(null).isEmpty());
        for (String k : List.of(AdvisoryModel.PRODUCT_PAN_OS, AdvisoryModel.PRODUCT_FORTIOS, AdvisoryModel.PRODUCT_FORTIMANAGER, AdvisoryModel.PRODUCT_CISCO_ASA)) {
            assertEquals(k, core.comparator(k).orElseThrow().productKey());
        }
        assertTrue(core.comparator("other").isEmpty());
    }
}
