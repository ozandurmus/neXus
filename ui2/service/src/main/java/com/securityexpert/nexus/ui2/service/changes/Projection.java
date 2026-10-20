package com.securityexpert.nexus.ui2.service.changes;

/**
 * What one request may show of an identity: the plain view, or the aiview masked view (device pseudonyms from
 * {@code TopologyNamePseudonymizer}, keyed {@code USR-} principal tokens, the system-log masking rules on free text).
 * Every event builder asks this object; none decides masking itself.
 */
public interface Projection {
    boolean masked();

    /** The device's display name: hostname or pseudonym; the opaque id when no hostname is recorded; UNKNOWN when unknown. */
    String device(String deviceId);

    /** The vendor hint recorded for the device, or null when the device is not in the inventory. */
    String vendor(String deviceId);

    /** A user or administrator name: itself, or its keyed {@code USR-} token. Null and blank stay as they are. */
    String principal(String raw);

    /** Free text that may carry names, addresses or serials: itself, or the masked projection. */
    String text(String raw);
}
