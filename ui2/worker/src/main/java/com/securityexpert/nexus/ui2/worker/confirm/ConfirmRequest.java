package com.securityexpert.nexus.ui2.worker.confirm;

import java.util.Optional;

import com.securityexpert.nexus.ui2.jobs.transport.ApiTarget;
import com.securityexpert.nexus.ui2.jobs.transport.ConnectionTarget;

/**
 * One confirm attempt's inputs (DA-2, EC-4): the device's own endpoint,
 * never the management plane and never a cluster virtual address (13F
 * CL-1) -- the caller is responsible for resolving {@code
 * endpoints.address_ref} into exactly one of the two target shapes below
 * before building this record; this type does not choose between them.
 */
public record ConfirmRequest(
        Vendor vendor,
        Optional<ConnectionTarget> connectionTarget,
        Optional<ApiTarget> apiTarget,
        String credentialRef,
        String trustRuleRef) {

    public static ConfirmRequest checkPoint(ConnectionTarget target, String credentialRef, String trustRuleRef) {
        return new ConfirmRequest(Vendor.CHECK_POINT, Optional.of(target), Optional.empty(), credentialRef, trustRuleRef);
    }

    public static ConfirmRequest paloAlto(ApiTarget target, String credentialRef) {
        return new ConfirmRequest(Vendor.PALO_ALTO, Optional.empty(), Optional.of(target), credentialRef, "");
    }
}
