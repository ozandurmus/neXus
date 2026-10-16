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

    @Override
    public boolean masking() {
        return true;
    }
}
