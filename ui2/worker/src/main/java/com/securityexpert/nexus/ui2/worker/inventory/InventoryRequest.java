package com.securityexpert.nexus.ui2.worker.inventory;

import java.util.Optional;

import com.securityexpert.nexus.ui2.jobs.transport.ApiTarget;
import com.securityexpert.nexus.ui2.jobs.transport.ConnectionTarget;

/**
 * One inventory-collect attempt's inputs (14C D-7): the device's own
 * endpoint, never a cluster virtual address (13F CL-1) -- the caller
 * resolves {@code endpoints.address_ref} into exactly one of the two
 * target shapes below, mirroring {@code worker.confirm.ConfirmRequest}.
 */
public record InventoryRequest(
        InventoryVendor vendor,
        Optional<ConnectionTarget> connectionTarget,
        Optional<ApiTarget> apiTarget,
        String credentialRef,
        String trustRuleRef,
        Optional<String> modelHint) {

    public static InventoryRequest checkPoint(ConnectionTarget target, String credentialRef, String trustRuleRef) {
        return checkPoint(target, credentialRef, trustRuleRef, Optional.empty());
    }

    /**
     * @param modelHint the device's already-known model, when its discovery candidate carried one
     * (e.g. a Check Point Management Server's own "hardware" field, joined in before this
     * collection ever ran) -- a hint, never confirmed evidence, mirroring {@code
     * ConfirmRequest.checkPoint}'s own modelHint parameter exactly: {@link InventoryCapabilityExecutor}
     * uses it only to choose which already-approved channel to try first, never to skip or alter
     * the commands themselves.
     */
    public static InventoryRequest checkPoint(ConnectionTarget target, String credentialRef, String trustRuleRef,
            Optional<String> modelHint) {
        return new InventoryRequest(InventoryVendor.CHECK_POINT, Optional.of(target), Optional.empty(), credentialRef,
                trustRuleRef, modelHint);
    }

    public static InventoryRequest paloAlto(ApiTarget target, String credentialRef) {
        return new InventoryRequest(InventoryVendor.PALO_ALTO, Optional.empty(), Optional.of(target), credentialRef,
                "", Optional.empty());
    }
}
