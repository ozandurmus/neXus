package com.securityexpert.nexus.ui2.worker.configuration;

import java.util.Optional;

import com.securityexpert.nexus.ui2.jobs.transport.ApiTarget;
import com.securityexpert.nexus.ui2.jobs.transport.ConnectionTarget;

/** One {@code configuration_collect} attempt's inputs -- mirrors {@code worker.inventory.InventoryRequest}. */
public record ConfigurationRequest(
        ConfigurationVendor vendor,
        Optional<ConnectionTarget> connectionTarget,
        Optional<ApiTarget> apiTarget,
        String credentialRef,
        String trustRuleRef) {

    public static ConfigurationRequest checkPoint(ConnectionTarget target, String credentialRef, String trustRuleRef) {
        return new ConfigurationRequest(ConfigurationVendor.CHECK_POINT, Optional.of(target), Optional.empty(),
                credentialRef, trustRuleRef);
    }

    public static ConfigurationRequest fortiGate(ConnectionTarget target, String credentialRef, String trustRuleRef) {
        return new ConfigurationRequest(ConfigurationVendor.FORTINET, Optional.of(target), Optional.empty(), credentialRef, trustRuleRef);
    }

    public static ConfigurationRequest paloAlto(ApiTarget target, String credentialRef) {
        return new ConfigurationRequest(ConfigurationVendor.PALO_ALTO, Optional.empty(), Optional.of(target),
                credentialRef, "");
    }
}
