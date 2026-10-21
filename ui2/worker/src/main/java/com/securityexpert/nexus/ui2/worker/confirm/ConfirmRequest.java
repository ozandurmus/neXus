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
        Optional<String> modelHint,
        boolean managementServer) {

    /** A gateway / firewall confirm (not a Panorama). */
    public ConfirmRequest(Vendor vendor, Optional<ConnectionTarget> connectionTarget, Optional<ApiTarget> apiTarget,
            String credentialRef, String trustRuleRef, Optional<String> modelHint) {
        this(vendor, connectionTarget, apiTarget, credentialRef, trustRuleRef, modelHint, false);
    }

    public static ConfirmRequest checkPoint(ConnectionTarget target, String credentialRef, String trustRuleRef) {
        return checkPoint(target, credentialRef, trustRuleRef, Optional.empty());
    }

    /**
     * @param modelHint the device's already-known model, when its discovery candidate carried one
     * (e.g. a Check Point Management Server's own "hardware" field, joined in before any SSH
     * confirm ever ran) -- a hint, never confirmed evidence (management-plane observation != direct
     * -device runtime truth); a known Spark hint selects the approved Gaia Embedded reads.
     */
    public static ConfirmRequest checkPoint(ConnectionTarget target, String credentialRef, String trustRuleRef,
            Optional<String> modelHint) {
        return new ConfirmRequest(Vendor.CHECK_POINT, Optional.of(target), Optional.empty(), credentialRef, trustRuleRef,
                modelHint);
    }

    /** A Palo Alto firewall: its identity read is the type=version request since PO 2026-10-21 (show system info is retired for it). */
    public static ConfirmRequest paloAlto(ApiTarget target, String credentialRef) {
        return new ConfirmRequest(Vendor.PALO_ALTO, Optional.empty(), Optional.of(target), credentialRef, "", Optional.empty());
    }

    /** A Panorama: keeps the system info identity read under its own gate row (out of the 2026-10-21 scope). */
    public static ConfirmRequest panorama(ApiTarget target, String credentialRef) {
        return new ConfirmRequest(Vendor.PALO_ALTO, Optional.empty(), Optional.of(target), credentialRef, "", Optional.empty(),
                true);
    }
}
