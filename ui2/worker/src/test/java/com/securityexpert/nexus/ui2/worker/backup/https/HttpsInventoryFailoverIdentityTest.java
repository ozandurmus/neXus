package com.securityexpert.nexus.ui2.worker.backup.https;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryContext;
import com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryHaFact;

class HttpsInventoryFailoverIdentityTest {

    private static HttpsVendorExecutor.Identity identity(Optional<String> role, Optional<String> mode) {
        return new HttpsVendorExecutor.Identity(Optional.of("FW-TANGO-04"), Optional.of("model"), Optional.of("9.18(4)"),
                List.of(), role, Optional.empty(), Optional.of("SYNTHA0001"), mode);
    }

    @Test
    void anAsaRoleBecomesOnePhysicalHaFact() {
        List<InventoryHaFact> facts = HttpsInventoryJobExecutor.haFacts("cisco_asa", identity(Optional.of("active"), Optional.empty()));
        assertEquals(1, facts.size());
        assertEquals(InventoryContext.PHYSICAL, facts.get(0).context());
        assertEquals("active", facts.get(0).role());
        assertEquals(InventoryHaFact.SOURCE_ASA_SHOW_FAILOVER, facts.get(0).source());
        assertTrue(facts.get(0).clusterMode().isEmpty());
    }

    @Test
    void aFortiGateRoleCarriesItsMode() {
        List<InventoryHaFact> facts = HttpsInventoryJobExecutor.haFacts("fortinet", identity(Optional.of("primary"), Optional.of("a-p")));
        assertEquals(InventoryHaFact.SOURCE_FGT_HA_STATUS, facts.get(0).source());
        assertEquals(Optional.of("a-p"), facts.get(0).clusterMode());
    }

    @Test
    void noRoleOrAnotherVendorWritesNoFact() {
        assertTrue(HttpsInventoryJobExecutor.haFacts("cisco_asa", identity(Optional.empty(), Optional.empty())).isEmpty());
        assertTrue(HttpsInventoryJobExecutor.haFacts("cisco_asa", identity(Optional.of(" "), Optional.empty())).isEmpty());
        assertTrue(HttpsInventoryJobExecutor.haFacts("infoblox", identity(Optional.of("active"), Optional.empty())).isEmpty());
    }

    @Test
    void serialsAreRecordedOnlyForTheTwoFailoverVendors() {
        assertEquals(Optional.of("asa_show_version"), HttpsInventoryJobExecutor.serialSource("cisco_asa"));
        assertEquals(Optional.of("fgt_get_system_status"), HttpsInventoryJobExecutor.serialSource("fortinet"));
        assertTrue(HttpsInventoryJobExecutor.serialSource("infoblox").isEmpty());
        assertTrue(HttpsInventoryJobExecutor.serialSource("radware").isEmpty());
    }
}
