package com.securityexpert.nexus.ui2.service.privacy;

import java.util.List;

/**
 * Every registered device as the masking layer needs it to keep device pseudonyms unique: the opaque device ID, the
 * hostname a response shows for it, and its cluster reference, oldest registration first. Two devices whose names
 * give the same pseudonym (a Cisco ASA failover pair replicates one hostname onto both units) are told apart only
 * through this list; the oldest keeps the pseudonym, the others get a deterministic disambiguator.
 */
@FunctionalInterface
public interface MaskingDeviceDirectory {

    /** Oldest registration first, ties broken by device ID; the order decides which device keeps a shared pseudonym. */
    List<Entry> devices();

    record Entry(String deviceId, String hostname, String clusterRef) {
    }
}
