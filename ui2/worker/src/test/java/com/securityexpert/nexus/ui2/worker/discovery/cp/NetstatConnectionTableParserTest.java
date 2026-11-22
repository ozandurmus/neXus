package com.securityexpert.nexus.ui2.worker.discovery.cp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.discovery.cp.Address;

/**
 * §7.4 record §4 item 6: pins what {@link NetstatConnectionTableParser} does
 * with one {@code netstat -an} text reading. Pure function over a literal
 * string; synthetic 192.0.2.x / 198.51.100.x addresses only.
 */
class NetstatConnectionTableParserTest {

    private static final String HEADER = "Proto Recv-Q Send-Q  Local Address          Foreign Address        (state)";

    @Test
    void wellFormedTcpLineProducesOneRowWithForeignAddressPortAndState() {
        List<ConnectionTableRow> rows = NetstatConnectionTableParser.parse(
                "tcp4       0      0  192.0.2.10:443         198.51.100.7:51234     ESTABLISHED");
        assertEquals(List.of(new ConnectionTableRow(Address.of("198.51.100.7"), 51234, true)), rows);
    }

    @Test
    void nonEstablishedStateProducesRowWithEstablishedFalse() {
        List<ConnectionTableRow> rows = NetstatConnectionTableParser.parse(
                "tcp4 0 0 192.0.2.10:443 198.51.100.7:51234 TIME_WAIT");
        assertEquals(List.of(new ConnectionTableRow(Address.of("198.51.100.7"), 51234, false)), rows);
    }

    @Test
    void stateComparisonIsCaseSensitiveAndLowercaseEstablishedIsNotEstablished() {
        List<ConnectionTableRow> rows = NetstatConnectionTableParser.parse(
                "tcp4 0 0 192.0.2.10:443 198.51.100.7:51234 established");
        assertEquals(List.of(new ConnectionTableRow(Address.of("198.51.100.7"), 51234, false)), rows);
    }

    @Test
    void rowsKeepInputOrderAndAddressTextIsNotNormalised() {
        String text = HEADER + "\n"
                + "tcp4 0 0 192.0.2.10:443 198.51.100.7:51234 ESTABLISHED\n"
                + "tcp4 0 0 192.0.2.10:443 198.51.100.007:9 CLOSE_WAIT\n";
        List<ConnectionTableRow> rows = NetstatConnectionTableParser.parse(text);
        assertEquals(List.of(
                new ConnectionTableRow(Address.of("198.51.100.7"), 51234, true),
                new ConnectionTableRow(Address.of("198.51.100.007"), 9, false)), rows);
    }

    @Test
    void headerBlankNonTcpAndWindowsLineEndingsAreSkippedOrTrimmed() {
        String text = "Active Internet connections\n"
                + HEADER + "\n"
                + "\n"
                + "   \n"
                + "udp4 0 0 192.0.2.10:53 198.51.100.7:53\n"
                + "udp4 0 0 *.53 *.*\n"
                + "tcp4 0 0 192.0.2.10:443 198.51.100.7:51234 ESTABLISHED\r\n";
        List<ConnectionTableRow> rows = NetstatConnectionTableParser.parse(text);
        assertEquals(List.of(new ConnectionTableRow(Address.of("198.51.100.7"), 51234, true)), rows);
    }

    @Test
    void listeningSocketsWithWildcardPeersProduceNoRow() {
        String text = "tcp4 0 0 *.22 *.* LISTEN\n"
                + "tcp4 0 0 *:443 *:* LISTEN\n"
                + "tcp4 0 0 192.0.2.10:443 *.* LISTEN\n";
        assertEquals(List.of(), NetstatConnectionTableParser.parse(text));
    }

    @Test
    void malformedForeignAddressesAreSkippedWithoutGuessing() {
        String text = "tcp4 0 0 192.0.2.10:443 198.51.100.7:abc ESTABLISHED\n"
                + "tcp4 0 0 192.0.2.10:443 198.51.100.7: ESTABLISHED\n"
                + "tcp4 0 0 192.0.2.10:443 198.51.100.7 ESTABLISHED\n";
        assertEquals(List.of(), NetstatConnectionTableParser.parse(text));
    }

    @Test
    void truncatedLinesDoNotThrowAndYieldNoRow() {
        String text = "tcp4\n"
                + "tcp4 0 0\n"
                + "tcp4 0 0 192.0.2.10:443\n"
                + "tcp4 0 0 192.0.2.10:443 198.51.100.7:51234\n"
                + "tcp4 0 0 192.0.2.10:443 198.51.100.7:5";
        assertEquals(List.of(), NetstatConnectionTableParser.parse(text));
    }

    @Test
    void lineWithoutStateColumnIsSkippedRatherThanReportedAsNotEstablished() {
        List<ConnectionTableRow> rows = NetstatConnectionTableParser.parse(
                "tcp4 0 0 192.0.2.10:443 198.51.100.7:51234\n"
                + "tcp4 0 0 192.0.2.10:443 198.51.100.7:51235 ESTABLISHED\n");
        assertEquals(List.of(new ConnectionTableRow(Address.of("198.51.100.7"), 51235, true)), rows);
    }

    @Test
    void emptyInputReturnsEmptyList() {
        assertTrue(NetstatConnectionTableParser.parse("").isEmpty());
        assertTrue(NetstatConnectionTableParser.parse("\n").isEmpty());
        assertEquals(List.of(), NetstatConnectionTableParser.parse("   \n\t\n"));
    }
}
