package com.securityexpert.nexus.ui2.worker.configuration;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

/** AC-3: the closed literal set (14G CG-1/CG-4) -- exactly these literals, in this order. */
class ConfigurationReadPlanTest {

    @Test
    void checkPointIdentityReadsAreExactlyThreeInOrder() {
        assertEquals(List.of("clish -c 'show hostname'", "clish -c 'show version all'", "clish -c 'cpstat os -f hw_info'"),
                ConfigurationReadPlan.CHECK_POINT_IDENTITY_READS);
        assertEquals("clish -c 'show configuration'", ConfigurationReadPlan.CP_SHOW_CONFIGURATION);
    }

    @Test
    void paloAltoXmlReadsAreEffectiveRunningThenMerged() {
        assertEquals(List.of(ConfigurationReadPlan.PAN_EFFECTIVE_RUNNING, ConfigurationReadPlan.PAN_MERGED),
                ConfigurationReadPlan.PALO_ALTO_XML_READS);
        assertEquals(java.util.Map.of("type", "config", "action", "show", "xpath", "/config"),
                ConfigurationReadPlan.PAN_ACTIVE_FORM_PARAMS);
    }
}
