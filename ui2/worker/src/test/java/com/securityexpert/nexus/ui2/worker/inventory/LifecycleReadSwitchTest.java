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

    @Test
    void groupBKindsAreNamedByTheirTokensAndAreOffByDefault() {
        String[] tokens = {"pan_license_info", "pan_support_info", "fgt_update_info_contract", "fgt_autoupdate_versions",
                "fmg_fmupdate_dbcontract", "mc_show_licenses", "infoblox_upgradestatus_grid", "infoblox_upgradestatus_vnode"};
        Kind[] kinds = {Kind.PAN_LICENSE_INFO, Kind.PAN_SUPPORT_INFO, Kind.FGT_UPDATE_INFO_CONTRACT, Kind.FGT_AUTOUPDATE_VERSIONS,
                Kind.FMG_FMUPDATE_DBCONTRACT, Kind.MC_SHOW_LICENSES, Kind.INFOBLOX_UPGRADESTATUS_GRID,
                Kind.INFOBLOX_UPGRADESTATUS_VNODE};
        for (int i = 0; i < tokens.length; i++) {
            assertEquals(tokens[i], kinds[i].token());
            assertFalse(LifecycleReadSwitch.OFF.allows(kinds[i], "dev-1"));
            assertFalse(of(Map.of()).allows(kinds[i], "dev-1"));
            LifecycleReadSwitch only = of(Map.of("UI2_LIFECYCLE_READS", tokens[i], "UI2_LIFECYCLE_READ_DEVICES", "dev-1"));
            for (Kind other : Kind.values()) {
                assertEquals(other == kinds[i], only.allows(other, "dev-1"), tokens[i] + " vs " + other);
            }
            assertFalse(only.allows(kinds[i], "dev-2"));
        }
    }
}
