package com.securityexpert.nexus.ui2.discovery.cp;

/**
 * Contract §5.1: the three exhaustive, mutually exclusive outcomes of host
 * resolution (HR-1/HR-2/HR-3).
 */
public enum HostResolution {
    /** HR-1: management address present and equals own address. */
    PHYSICAL_DEVICE,
    /**
     * HR-2: management address present and differs from own address,
     * including the case where own address is empty. Not a separate rule
     * from the differing case, and not a value to repair.
     */
    VIRTUAL_SYSTEM_HOSTED,
    /** HR-3: management address absent. Never UNKNOWN, never a guess. */
    NOT_APPLICABLE;

    /**
     * HR-4: a pure function of the two address fields alone. Does not take
     * an object type or a candidate kind parameter — that absence from the
     * signature is what makes "host resolution does not branch on object
     * type" a structural fact rather than a discipline.
     */
    public static HostResolution resolve(Address managementAddress, Address ownAddress) {
        if (managementAddress.value().isEmpty()) {
            return NOT_APPLICABLE;
        }
        if (ownAddress.isPresent() && managementAddress.equalsTrimmed(ownAddress)) {
            return PHYSICAL_DEVICE;
        }
        return VIRTUAL_SYSTEM_HOSTED;
    }
}
