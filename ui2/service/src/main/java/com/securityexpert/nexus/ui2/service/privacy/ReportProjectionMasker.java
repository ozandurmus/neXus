package com.securityexpert.nexus.ui2.service.privacy;

import com.securityexpert.nexus.ui2.packetanalysis.export.ReportMasker;
import java.util.Objects;

/**
 * The privacy projection of a downloaded Packet Analysis workbook: the same pseudonymizer and subnet-preserving
 * address masker that rewrite the JSON responses of the replay viewer, behind the packet-analysis module's
 * {@link ReportMasker}. Names are pseudonymized (and registered, so engine text that repeats them is rewritten too);
 * free text goes through the address masker and then the name dictionary. The workbook itself never lists an address.
 */
public final class ReportProjectionMasker implements ReportMasker {
    private final TopologyNamePseudonymizer names;
    private final SubnetPreservingIpMasker addresses;

    public ReportProjectionMasker(TopologyNamePseudonymizer names, SubnetPreservingIpMasker addresses) {
        this.names = Objects.requireNonNull(names, "names");
        this.addresses = addresses;
    }

    @Override
    public String name(String raw) {
        return names.maskDeviceName(raw, null);
    }

    @Override
    public String text(String raw) {
        String scrubbed = addresses == null ? raw : addresses.maskText(raw);
        return names.maskText(scrubbed);
    }

    /**
     * Reports R1 (REPORTS_R1_CONTRACT.md "One masking pass"): a device name with its cluster reference and device id, so
     * a report names a device exactly as every other masked screen does ({@code FW-...-M1} for a cluster member).
     */
    public String device(String raw, String clusterRef, String deviceId) {
        return names.maskDeviceName(raw, clusterRef, deviceId);
    }

    /** Reports R1: a cluster reference as its pseudonym ({@code CLS-...}). */
    public String cluster(String raw) {
        return names.maskClusterName(raw);
    }

    /** Reports R1: an address or CIDR through the subnet-preserving masker; without one, the address is withheld. */
    public String address(String raw) {
        if (raw == null || raw.isBlank()) return raw;
        if (addresses == null) return "MASKED";
        addresses.registerSubnet(raw);
        return addresses.mask(raw);
    }

    /** Reports R1: a serial number as its keyed token ({@code SN-...}). */
    public String serial(String raw) {
        return names.maskSerial(raw);
    }

    @Override
    public boolean masking() {
        return true;
    }
}
