package com.securityexpert.nexus.ui2.worker.configuration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.persistence.device.configuration.ConfigurationDeviationEntry;
import com.securityexpert.nexus.ui2.persistence.device.configuration.ConfigurationDeviationEntry.DeviationKind;
import com.securityexpert.nexus.ui2.persistence.device.configuration.ConfigurationDeviationSummary;
import com.securityexpert.nexus.ui2.persistence.device.configuration.ConfigurationIndexEntry;

/**
 * V176 MODIFIED (docs/design/BACKUP_MAJOR_DEVIATIONS_2026_10_23.md): a Check Point section that keeps its count while
 * its sanitized lines change. Synthetic documentation addresses only (RFC 5737); no entry ever carries a line.
 */
class IndexDeviationModifiedTest {

    private static ConfigurationIndexEntry cp(String section, int count) {
        return new ConfigurationIndexEntry("physical", section, Optional.empty(), count);
    }

    private static String text(String... setLines) {
        return "# SecurityExpert Check Point Gaia configuration evidence (redacted)\n" + String.join("\n", setLines) + "\n";
    }

    private static final List<ConfigurationIndexEntry> INDEX = List.of(cp("interface", 2), cp("static-route", 1), cp("ntp", 1));

    @Test
    void anInterfaceAddressEditWithTheSameLineCountIsModified() {
        String before = text("set interface eth1 ipv4-address 192.0.2.1 mask-length 24", "set interface eth1 state on",
                "set static-route 198.51.100.0/24 nexthop gateway address 192.0.2.254 on", "set ntp active on");
        String after = text("set interface eth1 ipv4-address 192.0.2.9 mask-length 24", "set interface eth1 state on",
                "set static-route 198.51.100.0/24 nexthop gateway address 192.0.2.254 on", "set ntp active on");

        ConfigurationDeviationSummary summary = IndexDeviationComputer.compute(INDEX, INDEX, Optional.of(before),
                Optional.of(after), "check_point");

        assertEquals(ConfigurationDeviationSummary.Status.COMPUTED, summary.status());
        assertEquals(1, summary.entries().size());
        ConfigurationDeviationEntry entry = summary.entries().get(0);
        assertEquals(DeviationKind.MODIFIED, entry.kind());
        assertEquals("interface", entry.section());
        assertEquals("physical", entry.context());
        assertEquals(2, entry.oldCount());
        assertEquals(2, entry.newCount());
    }

    @Test
    void aRecountedSectionIsNotAlsoReportedAsModifiedAndLineOrderDoesNotCount() {
        List<ConfigurationIndexEntry> newIndex = List.of(cp("interface", 2), cp("static-route", 2), cp("ntp", 1));
        String before = text("set interface eth1 state on", "set interface eth1 mtu 1500",
                "set static-route 198.51.100.0/24 nexthop gateway address 192.0.2.254 on", "set ntp active on");
        String after = text("set interface eth1 mtu 1500", "set interface eth1 state on",
                "set static-route 198.51.100.0/24 nexthop gateway address 192.0.2.254 on",
                "set static-route 203.0.113.0/24 nexthop gateway address 192.0.2.254 on", "set ntp active on");

        ConfigurationDeviationSummary summary = IndexDeviationComputer.compute(INDEX, newIndex, Optional.of(before),
                Optional.of(after), "check_point");

        assertEquals(1, summary.entries().size());
        assertEquals(DeviationKind.RECOUNTED, summary.entries().get(0).kind());
        assertEquals("static-route", summary.entries().get(0).section());
    }

    @Test
    void anInterfaceCommentEditIsIgnored() {
        String before = text("set interface eth1 comments old", "set interface eth1 state on",
                "set static-route default nexthop gateway address 192.0.2.254 on", "set ntp active on");
        String after = text("set interface eth1 comments new", "set interface eth1 state on",
                "set static-route default nexthop gateway address 192.0.2.254 on", "set ntp active on");

        assertTrue(IndexDeviationComputer.compute(INDEX, INDEX, Optional.of(before), Optional.of(after), "check_point")
                .entries().isEmpty());
    }

    @Test
    void withoutThePreviousTextOrForAnotherVendorTheSummaryIsCountOnly() {
        String before = text("set interface eth1 ipv4-address 192.0.2.1 mask-length 24", "set interface eth1 state on");
        String after = text("set interface eth1 ipv4-address 192.0.2.9 mask-length 24", "set interface eth1 state on");

        assertTrue(IndexDeviationComputer.compute(INDEX, INDEX, Optional.empty(), Optional.of(after), "check_point")
                .entries().isEmpty());
        assertTrue(IndexDeviationComputer.compute(INDEX, INDEX, Optional.of(before), Optional.of(after), "palo_alto")
                .entries().isEmpty());
        assertEquals(ConfigurationDeviationSummary.Status.NOT_COMPUTABLE,
                IndexDeviationComputer.compute(null, INDEX, Optional.of(before), Optional.of(after), "check_point").status());
    }
}
