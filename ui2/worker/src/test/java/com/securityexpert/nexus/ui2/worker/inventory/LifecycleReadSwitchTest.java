package com.securityexpert.nexus.ui2.worker.inventory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.worker.inventory.LifecycleReadSwitch.Kind;

class LifecycleReadSwitchTest {

    private static LifecycleReadSwitch of(Map<String, String> env) {
        return LifecycleReadSwitch.from(env::get);
    }

    @Test
    void bothUnsetSendsNothing() {
        LifecycleReadSwitch off = of(Map.of());
        for (Kind kind : Kind.values()) {
            assertFalse(off.allows(kind, "dev-1"), kind.name());
            assertFalse(off.allows(kind, null), kind.name());
        }
        assertFalse(off.anyFor("dev-1"));
        assertEquals(1, off.mcLicenseMax());
        assertFalse(LifecycleReadSwitch.OFF.allows(Kind.CP_CPLIC_PRINT, "*"));
    }

    @Test
    void aKindWithoutADeviceListAndADeviceListWithoutAKindSendNothing() {
        assertFalse(of(Map.of("UI2_LIFECYCLE_READS", "cp_cplic_print")).allows(Kind.CP_CPLIC_PRINT, "dev-1"));
        assertFalse(of(Map.of("UI2_LIFECYCLE_READ_DEVICES", "dev-1")).allows(Kind.CP_CPLIC_PRINT, "dev-1"));
        assertFalse(of(Map.of("UI2_LIFECYCLE_READS", " ", "UI2_LIFECYCLE_READ_DEVICES", " ")).anyFor("dev-1"));
    }

    @Test
    void aKindIsSentOnlyForAListedDeviceOrStar() {
        LifecycleReadSwitch listed = of(Map.of("UI2_LIFECYCLE_READS", " cp_cplic_print , ASA_SHOW_LICENSE_ALL ",
                "UI2_LIFECYCLE_READ_DEVICES", "dev-1, dev-2"));
        assertTrue(listed.allows(Kind.CP_CPLIC_PRINT, "dev-1"));
        assertTrue(listed.allows(Kind.ASA_SHOW_LICENSE_ALL, "dev-2"));
        assertFalse(listed.allows(Kind.CP_CPLIC_PRINT, "dev-3"));
        assertFalse(listed.allows(Kind.CP_CPLIC_PRINT, null));
        assertFalse(listed.allows(Kind.CP_CPLP_LIST, "dev-1"), "kind not enabled");
        LifecycleReadSwitch star = of(Map.of("UI2_LIFECYCLE_READS", "mc_system_version", "UI2_LIFECYCLE_READ_DEVICES", "*"));
        assertTrue(star.allows(Kind.MC_SYSTEM_VERSION, "anything"));
        assertTrue(star.allows(Kind.MC_SYSTEM_VERSION, null));
        assertFalse(star.allows(Kind.MC_DEVICE_LICENSE, "anything"));
    }

    @Test
    void unknownTokensAreIgnored() {
        LifecycleReadSwitch sw = of(Map.of("UI2_LIFECYCLE_READS", "cplic print,bogus,,cp_cplp_list", "UI2_LIFECYCLE_READ_DEVICES", "*"));
        assertTrue(sw.allows(Kind.CP_CPLP_LIST, "d"));
        assertFalse(sw.allows(Kind.CP_CPLIC_PRINT, "d"));
    }

    @Test
    void mcLicenseMaxDefaultsToOneAndRejectsGarbage() {
        assertEquals(3, of(Map.of("UI2_LIFECYCLE_MC_LICENSE_MAX", " 3 ")).mcLicenseMax());
        assertEquals(0, of(Map.of("UI2_LIFECYCLE_MC_LICENSE_MAX", "0")).mcLicenseMax());
        assertEquals(1, of(Map.of("UI2_LIFECYCLE_MC_LICENSE_MAX", "many")).mcLicenseMax());
        assertEquals(1, of(Map.of("UI2_LIFECYCLE_MC_LICENSE_MAX", "-2")).mcLicenseMax());
    }
}
