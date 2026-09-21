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
        String trustRuleRef,
        Optional<String> modelHint) {

    public static ConfirmRequest checkPoint(ConnectionTarget target, String credentialRef, String trustRuleRef) {
        return checkPoint(target, credentialRef, trustRuleRef, Optional.empty());
    }

    /**
     * @param modelHint the device's already-known model, when its discovery candidate carried one
     * (e.g. a Check Point Management Server's own "hardware" field, joined in before any SSH
     * confirm ever ran) -- a hint, never confirmed evidence (management-plane observation != direct
     * -device runtime truth); {@link ConfirmCapabilityExecutor} uses it only to choose which already
     * -approved channel (exec vs. interactive shell) to try first for the identity read, never to
     * skip or alter the closed command set itself.
     */
    public static ConfirmRequest checkPoint(ConnectionTarget target, String credentialRef, String trustRuleRef,
            Optional<String> modelHint) {
        return new ConfirmRequest(Vendor.CHECK_POINT, Optional.of(target), Optional.empty(), credentialRef, trustRuleRef,
                modelHint);
    }

    public static ConfirmRequest paloAlto(ApiTarget target, String credentialRef) {
        return new ConfirmRequest(Vendor.PALO_ALTO, Optional.empty(), Optional.of(target), credentialRef, "", Optional.empty());
    }
}
