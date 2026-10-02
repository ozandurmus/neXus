package com.securityexpert.nexus.ui2.worker.discovery.pan;

import com.securityexpert.nexus.ui2.policy.PolicySnapshot;
import java.util.Map;

/** Existing p1c seam; the shared offline mapper also serves stored local firewall policy. */
public final class PanoramaPolicyMapper {
    public PolicySnapshot map(PolicySnapshot.Metadata metadata, String xml, String deviceGroup,
            Map<String, String> parents, boolean ancestorObjectsTakePrecedence) {
        return new com.securityexpert.nexus.ui2.policy.PanoramaPolicyMapper()
                .map(metadata, xml, deviceGroup, parents, ancestorObjectsTakePrecedence);
    }
}
